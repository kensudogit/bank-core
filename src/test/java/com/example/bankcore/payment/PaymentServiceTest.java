package com.example.bankcore.payment;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.payment.PaymentService.BillingItem;
import com.example.bankcore.payment.PaymentService.Mandate;
import com.example.bankcore.payment.PaymentService.OutgoingRequest;
import com.example.bankcore.payment.PaymentService.RemittanceResult;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 為替業務（国内）：手数料・振替・他行振込・全銀バッチ・口座振替の契約管理と照会。 */
class PaymentServiceTest extends AbstractIntegrationTest {

    @Autowired PaymentService payments;
    @Autowired ReconciliationService reconciliation;

    @Test
    void domesticFeeTiers() {
        assertThat(PaymentService.domesticFee(1)).isEqualTo(330);
        assertThat(PaymentService.domesticFee(29_999)).isEqualTo(330);
        assertThat(PaymentService.domesticFee(30_000)).isEqualTo(440);
    }

    @Test
    void bookTransferBetweenOwnAccountsIsIdempotent() {
        Account a = openAccount("Taro", 10_000);
        Account b = openAccount("Taro", 0);
        String k = key();
        assertThat(payments.bookTransfer(a.id(), b.id(), 1_000, "JPY", "振替", k, null).transaction().status())
                .isEqualTo(TransactionStatus.COMPLETED);
        assertThat(payments.bookTransfer(a.id(), b.id(), 1_000, "JPY", "振替", k, null).replayed()).isTrue();
        assertThat(balanceOf(b.id())).isEqualTo(1_000);
    }

    @Test
    void domesticRemittanceValidationAndQueries() {
        Account a = openAccount("Taro", 100_000);
        assertThatThrownBy(() -> payments.sendDomestic(new OutgoingRequest(a.id(), "0901", "123", "1", "7654321",
                "ﾔﾏﾀﾞ", 0), key(), null)).isInstanceOf(InvalidRequestException.class);

        RemittanceResult r = payments.sendDomestic(new OutgoingRequest(a.id(), "0901", "123", "1", "7654321",
                "ヤマダ　ハナコ", 10_000), key(), null);
        assertThat(r.remittance().direction()).isEqualTo("OUT");
        assertThat(r.remittance().feeMinor()).isEqualTo(330);
        assertThat(payments.remittances("OUT", "PENDING")).hasSize(1);
        assertThat(payments.remittances("IN", null)).isEmpty();
        assertThat(payments.remittance(r.remittance().id()).amountMinor()).isEqualTo(10_000);
        assertThatThrownBy(() -> payments.remittance(999_999)).isInstanceOf(NotFoundException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void zenginBatchListingAndSettlementOnce() {
        Account a = openAccount("Taro", 100_000);
        payments.sendDomestic(new OutgoingRequest(a.id(), "0901", "123", "1", "7654321", "ﾔﾏﾀﾞ", 10_000), key(), null);
        PaymentService.ZenginBatch batch = payments.createZenginBatch();
        assertThat(batch.status()).isEqualTo("SENT");
        assertThat(payments.batches()).hasSize(1);
        assertThat(payments.batch(batch.id()).content()).isEqualTo(batch.content());
        payments.settleBatch(batch.id(), key(), null);
        assertThat(payments.batch(batch.id()).settledAt()).isNotNull();
        assertThatThrownBy(() -> payments.batch(999_999)).isInstanceOf(NotFoundException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void banksMasterIsSeeded() {
        assertThat(payments.banks()).isNotEmpty().allSatisfy(b -> assertThat(b).containsKeys("bank_code", "name"));
    }

    @Test
    void billerAndMandateLifecycle() {
        Account utility = openAccount("ガス", 0);
        Account payer = openAccount("Payer", 0);
        payments.registerBiller("GAS01", "みなとガス", utility.id());
        assertThat(payments.billers()).extracting(PaymentService.Biller::code).containsExactly("GAS01");

        Mandate m = payments.registerMandate(payer.id(), "GAS01", "P-1");
        assertThat(m.status()).isEqualTo("ACTIVE");
        assertThat(payments.mandates(payer.id())).hasSize(1);
        assertThat(payments.mandates(utility.id())).isEmpty();
        assertThat(payments.mandates(null)).hasSize(1);

        assertThat(payments.cancelMandate(m.id()).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> payments.cancelMandate(m.id())).isInstanceOf(BusinessConflictException.class);
        // 解約後の請求は取り込まれない
        assertThat(payments.importBilling(List.of(new BillingItem("GAS01", "P-1", 1_000, INITIAL_BUSINESS_DATE))))
                .extracting(PaymentService.BillingResult::result).containsExactly("NO_MANDATE");
    }

    @Test
    void importBillingValidatesSize() {
        assertThatThrownBy(() -> payments.importBilling(List.of())).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void directDebitRequestsAreListedWithResult() {
        Account utility = openAccount("電力", 0);
        Account payer = openAccount("Payer", 10_000);
        payments.registerBiller("EL01", "電力", utility.id());
        payments.registerMandate(payer.id(), "EL01", "C-1");
        payments.importBilling(List.of(new BillingItem("EL01", "C-1", 3_000, INITIAL_BUSINESS_DATE.plusDays(1))));
        // 引落日前は処理しない
        assertThat(payments.processDirectDebits(INITIAL_BUSINESS_DATE).processed()).isZero();
        assertThat(payments.processDirectDebits(INITIAL_BUSINESS_DATE.plusDays(1)).paid()).isEqualTo(1);
        assertThat(payments.debitRequests()).singleElement().satisfies(d -> {
            assertThat(d.status()).isEqualTo("PAID");
            assertThat(d.transactionId()).isNotNull();
        });
        assertThat(balanceOf(utility.id())).isEqualTo(3_000);
    }
}
