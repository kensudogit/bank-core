package com.example.bankcore.payment;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.payment.ForexService.ExchangeResult;
import com.example.bankcore.payment.ForexService.ForeignOrder;
import com.example.bankcore.payment.ForexService.ForeignResult;
import com.example.bankcore.payment.PaymentService.BillingItem;
import com.example.bankcore.payment.PaymentService.BillingResult;
import com.example.bankcore.payment.PaymentService.DirectDebitReport;
import com.example.bankcore.payment.PaymentService.IncomingItem;
import com.example.bankcore.payment.PaymentService.OutgoingRequest;
import com.example.bankcore.payment.PaymentService.RemittanceResult;
import com.example.bankcore.payment.PaymentService.ZenginBatch;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 為替業務（振替・国内為替・全銀・被仕向・口座振替・両替・海外送金）。実 PostgreSQL。 */
class PaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PaymentService payments;

    @Autowired
    ForexService forex;

    @Autowired
    ReconciliationService reconciliation;

    private void assertBooksBalanced() {
        ReconciliationService.Report r = reconciliation.run();
        assertThat(r.subledgerMismatches()).isEmpty();
        assertThat(r.ok()).isTrue();
    }

    @Test
    void bookTransferRequiresTheSameOwner() {
        Account a1 = openAccount("Taro", 10_000);
        Account a2 = openAccount("Taro", 0);
        Account other = openAccount("Jiro", 0);
        assertThat(payments.bookTransfer(a1.id(), a2.id(), 3_000, "JPY", null, key(), null).transaction().status())
                .isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(a2.id())).isEqualTo(3_000);
        assertThatThrownBy(() -> payments.bookTransfer(a1.id(), other.id(), 1, "JPY", null, key(), null))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void outgoingRemittanceZenginFileSettlementCancellationAndReturn() {
        Account taro = openAccount("Taro", 100_000);
        String k = key();
        OutgoingRequest req = new OutgoingRequest(taro.id(), "0901", "123", "1", "7654321", "ヤマダ　ハナコ", 50_000);
        RemittanceResult sent = payments.sendDomestic(req, k, null);
        assertThat(sent.remittance().status()).isEqualTo("PENDING");
        assertThat(sent.remittance().destNameKana()).isEqualTo("ﾔﾏﾀﾞ ﾊﾅｺ");       // 全銀文字（半角カナ）
        assertThat(sent.remittance().feeMinor()).isEqualTo(440);
        assertThat(balanceOf(taro.id())).isEqualTo(49_560);
        assertThat(payments.sendDomestic(req, k, null).replayed()).isTrue();
        assertThat(balanceOf(taro.id())).isEqualTo(49_560);
        assertBooksBalanced();

        // 資金不足は REJECTED（振込金 + 手数料で判定）
        RemittanceResult tooMuch = payments.sendDomestic(new OutgoingRequest(taro.id(), "0901", "123", "1", "7654321",
                "ﾔﾏﾀﾞ ﾊﾅｺ", 49_500), key(), null);
        assertThat(tooMuch.transaction().rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);

        // 送信前の取消しは手数料も返す
        payments.cancelDomestic(sent.remittance().id(), key(), null);
        assertThat(balanceOf(taro.id())).isEqualTo(100_000);
        assertBooksBalanced();

        RemittanceResult second = payments.sendDomestic(new OutgoingRequest(taro.id(), "0902", "001", "2", "0000001",
                "ｶ)ｻﾝﾌﾟﾙ", 20_000), key(), null);
        ZenginBatch batch = payments.createZenginBatch();
        assertThat(batch.recordCount()).isEqualTo(1);
        assertThat(batch.totalMinor()).isEqualTo(20_000);
        String[] records = batch.content().split("\r\n");
        assertThat(records).hasSize(4);
        assertThat(records).allSatisfy(rec -> assertThat(rec).hasSize(120));
        assertThat(records[0]).startsWith("1210");
        assertThat(records[1]).startsWith("20902");
        assertThat(records[2]).startsWith("8000001000000020000");
        assertThatThrownBy(payments::createZenginBatch).isInstanceOf(BusinessConflictException.class);

        long id = second.remittance().id();
        // 送信後・決済前は取消しも組戻しもできない
        assertThatThrownBy(() -> payments.cancelDomestic(id, key(), null)).isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> payments.returnDomestic(id, "誤振込", key(), null))
                .isInstanceOf(BusinessConflictException.class);

        String settleKey = key();
        assertThat(payments.settleBatch(batch.id(), settleKey, null).status()).isEqualTo("SETTLED");
        assertThat(payments.settleBatch(batch.id(), settleKey, null).status()).isEqualTo("SETTLED");   // 再送は安全
        assertThat(payments.remittance(id).status()).isEqualTo("SETTLED");
        assertBooksBalanced();

        // 組戻し：振込金だけが戻る（手数料 330 は返さない）
        long before = balanceOf(taro.id());
        payments.returnDomestic(id, "誤振込", key(), null);
        assertThat(balanceOf(taro.id())).isEqualTo(before + 20_000);
        assertThat(payments.remittance(id).status()).isEqualTo("RETURNED");
        assertBooksBalanced();
    }

    @Test
    void incomingRemittancesAreCreditedOnceOrHeldOnNameMismatch() {
        Account hanako = openAccount("サトウ　ハナコ", 0);
        IncomingItem ok = new IncomingItem("0903", "REF-1", hanako.accountNumber(), "ｻﾄｳ ﾊﾅｺ", "ｱｵﾊﾞ ｼｮｳｼﾞ", 30_000);
        IncomingItem mismatch = new IncomingItem("0903", "REF-2", hanako.accountNumber(), "ｽｽﾞｷ ｲﾁﾛｳ", "X", 5_000);
        IncomingItem noAccount = new IncomingItem("0903", "REF-3", "9999999", "ﾀﾅｶ", "X", 7_000);
        List<RemittanceResult> results = payments.receiveIncoming(List.of(ok, mismatch, noAccount));
        assertThat(results).extracting(r -> r.remittance().status()).containsExactly("CREDITED", "HELD", "HELD");
        assertThat(balanceOf(hanako.id())).isEqualTo(30_000);

        // 同じファイルの再取込は二重入金しない
        List<RemittanceResult> again = payments.receiveIncoming(List.of(ok));
        assertThat(again.getFirst().replayed()).isTrue();
        assertThat(balanceOf(hanako.id())).isEqualTo(30_000);
        assertBooksBalanced();    // 仮受金 = 保留 12,000

        // 保留の解消：1 件は確認のうえ入金、1 件は仕向銀行へ返却
        payments.resolveHeld(results.get(1).remittance().id(), hanako.id(), "manager1", key(), null);
        payments.resolveHeld(results.get(2).remittance().id(), null, "manager1", key(), null);
        assertThat(balanceOf(hanako.id())).isEqualTo(35_000);
        assertThat(payments.remittance(results.get(2).remittance().id()).status()).isEqualTo("RETURNED");
        assertBooksBalanced();
    }

    @Test
    void directDebitsAreCollectedOnceAndInsufficientFundsFail() {
        Account utility = openAccount("みなと電力", 0);
        Account rich = openAccount("Rich", 50_000);
        Account poor = openAccount("Poor", 1_000);
        payments.registerBiller("ELEC01", "みなと電力", utility.id());
        payments.registerMandate(rich.id(), "ELEC01", "C-001");
        payments.registerMandate(poor.id(), "ELEC01", "C-002");

        List<BillingResult> imported = payments.importBilling(List.of(
                new BillingItem("ELEC01", "C-001", 8_000, INITIAL_BUSINESS_DATE),
                new BillingItem("ELEC01", "C-002", 8_000, INITIAL_BUSINESS_DATE),
                new BillingItem("ELEC01", "C-001", 8_000, INITIAL_BUSINESS_DATE),
                new BillingItem("ELEC01", "C-999", 8_000, INITIAL_BUSINESS_DATE)));
        assertThat(imported).extracting(BillingResult::result)
                .containsExactly("IMPORTED", "IMPORTED", "DUPLICATE", "NO_MANDATE");

        DirectDebitReport report = payments.processDirectDebits(INITIAL_BUSINESS_DATE);
        assertThat(report.paid()).isEqualTo(1);
        assertThat(report.insufficientFunds()).isEqualTo(1);
        assertThat(balanceOf(rich.id())).isEqualTo(42_000);
        assertThat(balanceOf(poor.id())).isEqualTo(1_000);
        assertThat(balanceOf(utility.id())).isEqualTo(8_000);
        // 再実行しても二重に引き落とさない
        assertThat(payments.processDirectDebits(INITIAL_BUSINESS_DATE).processed()).isZero();
        assertThat(balanceOf(rich.id())).isEqualTo(42_000);
        assertBooksBalanced();
    }

    @Test
    void exchangeUsesTtsAndTtbAndKeepsEachCurrencyBalanced() {
        Account yen = openAccount("Taro", 1_000_000);
        Account usd = accountService.open("Taro", "USD", 0);
        String k = key();
        ExchangeResult buy = forex.exchange(yen.id(), usd.id(), 10_000, k, null);    // USD 100.00 を TTS 151 で買う
        assertThat(buy.exchange().side()).isEqualTo("BUY");
        assertThat(buy.exchange().yenMinor()).isEqualTo(15_100);
        assertThat(balanceOf(yen.id())).isEqualTo(984_900);
        assertThat(balanceOf(usd.id())).isEqualTo(10_000);
        assertThat(forex.exchange(yen.id(), usd.id(), 10_000, k, null).replayed()).isTrue();

        ExchangeResult sell = forex.exchange(usd.id(), yen.id(), 5_000, key(), null);   // USD 50.00 を TTB 149 で売る
        assertThat(sell.exchange().yenMinor()).isEqualTo(7_450);
        assertThat(balanceOf(usd.id())).isEqualTo(5_000);
        assertThat(balanceOf(yen.id())).isEqualTo(992_350);

        // USD 0.01 の買いは 1.51 円 → 切り上げて 2 円
        assertThat(forex.exchange(yen.id(), usd.id(), 1, key(), null).exchange().yenMinor()).isEqualTo(2);
        Account other = openAccount("Jiro", 0);
        assertThatThrownBy(() -> forex.exchange(yen.id(), accountService.open("Jiro", "USD", 0).id(), 100, key(), null))
                .isInstanceOf(BusinessConflictException.class);
        assertThat(other.id()).isPositive();
        assertThatThrownBy(() -> movements.reverse(key(), buy.transaction().id(), null))
                .isInstanceOf(BusinessConflictException.class);
        assertBooksBalanced();
    }

    @Test
    void foreignRemittanceIsScreenedHeldAndRefundedOnRejection() {
        Account taro = openAccount("Taro", 1_000_000);
        ForeignResult clean = forex.sendForeign(new ForeignOrder(taro.id(), null, "USD", 100_000, "John Smith",
                "US12345678901", "BOFAUS3N", "US", "Family support"), key(), null);
        assertThat(clean.remittance().status()).isEqualTo("SENT");
        assertThat(clean.remittance().debitMinor()).isEqualTo(151_000);            // USD 1,000.00 × TTS 151
        assertThat(clean.remittance().message()).contains(":32A:").contains("USD1000,00");
        assertThat(balanceOf(taro.id())).isEqualTo(1_000_000 - 151_000 - 4_000);
        assertBooksBalanced();

        long before = balanceOf(taro.id());
        ForeignResult sanctioned = forex.sendForeign(new ForeignOrder(taro.id(), null, "USD", 50_000,
                "Ivan Petrovich Demostein", "GB29NWBK60161331926819", "NWBKGB2L", "GB", "Trade"), key(), null);
        assertThat(sanctioned.remittance().status()).isEqualTo("HELD");
        assertThat(sanctioned.remittance().screeningResult()).contains("制裁リスト");
        assertThat(balanceOf(taro.id())).isLessThan(before);
        assertBooksBalanced();

        forex.decide(sanctioned.remittance().id(), false, "manager1", "制裁対象の疑い", key(), null);
        assertThat(balanceOf(taro.id())).isEqualTo(before);                         // 送金額と手数料を全額返金
        assertThat(forex.foreign(sanctioned.remittance().id()).status()).isEqualTo("REJECTED");

        ForeignResult risky = forex.sendForeign(new ForeignOrder(taro.id(), null, "USD", 1_000, "Kim Lee",
                "KP1234567890", "KKBCKPPY", "KP", "Gift"), key(), null);
        assertThat(risky.remittance().status()).isEqualTo("HELD");
        forex.decide(risky.remittance().id(), true, "manager1", "確認済み", null, null);
        assertThat(forex.foreign(risky.remittance().id()).status()).isEqualTo("SENT");
        assertThat(forex.foreign(risky.remittance().id()).message()).isNotBlank();
        assertBooksBalanced();
    }

    @Test
    void rateUpdateValidatesSpread() {
        forex.updateRate("USD", new BigDecimal("152.5000"), new BigDecimal("1.0000"), "dealer1");
        assertThat(forex.rate("USD").tts()).isEqualByComparingTo("153.5");
        assertThatThrownBy(() -> forex.updateRate("USD", new BigDecimal("1"), new BigDecimal("1"), "dealer1"))
                .isInstanceOf(com.example.bankcore.common.Exceptions.InvalidRequestException.class);
        forex.updateRate("USD", new BigDecimal("150.0000"), new BigDecimal("1.0000"), "seed");   // 他のテストのために戻す
    }
}
