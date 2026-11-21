package com.example.bankcore.cards;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.RejectReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 決済業務：デビットカード（オーソリ・限度額・売上確定・取消）、電子マネー、加盟店精算。実 PostgreSQL。 */
class CardsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CardService cards;

    @Autowired
    ReconciliationService reconciliation;

    private long held(long accountId) {
        return accountService.get(accountId).heldBalanceMinor();
    }

    @Test
    void debitCardHoldsCapturesAndSettlesWithTheMerchant() {
        Account holder = openAccount("Taro", 50_000);
        Account shop = openAccount("Shop", 0);
        CardService.Merchant m = cards.registerMerchant("コンビニ", "5411", shop.id(), 300);
        CardService.Card card = cards.issueCard(holder.id(), 30_000);
        assertThat(card.last4()).hasSize(4);

        CardService.Authorization auth = cards.authorize(card.token(), m.id(), 20_000, "POS-1");
        assertThat(auth.status()).isEqualTo("AUTHORIZED");
        assertThat(auth.authCode()).hasSize(6);
        assertThat(held(holder.id())).isEqualTo(20_000);
        assertThat(accountService.get(holder.id()).availableBalanceMinor()).isEqualTo(30_000);
        // 同じ参照番号の再送は同じオーソリ（二重に押さえない）
        assertThat(cards.authorize(card.token(), m.id(), 20_000, "POS-1").id()).isEqualTo(auth.id());
        assertThat(held(holder.id())).isEqualTo(20_000);
        assertThat(cards.authorize(card.token(), m.id(), 15_000, "POS-2").declineReason()).isEqualTo("DAILY_LIMIT");
        assertThat(reconciliation.run().ok()).isTrue();

        // 売上確定は承認額以下。拘束を外して確定額だけ引き落とす
        cards.capture(auth.id(), 18_000, key());
        assertThat(balanceOf(holder.id())).isEqualTo(32_000);
        assertThat(held(holder.id())).isZero();
        assertThatThrownBy(() -> cards.capture(auth.id(), 1, key())).isInstanceOf(BusinessConflictException.class);

        CardService.Authorization small = cards.authorize(card.token(), m.id(), 5_000, "POS-3");
        cards.release(small.id());
        assertThat(held(holder.id())).isZero();

        CardService.Card big = cards.issueCard(holder.id(), 1_000_000);
        assertThat(cards.authorize(big.token(), m.id(), 40_000, "POS-4").declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        cards.setCardStatus(big.id(), false);
        assertThat(cards.authorize(big.token(), m.id(), 100, "POS-5").declineReason()).isEqualTo("CARD_BLOCKED");

        // 電子マネー：チャージ・支払・残高不足・上限
        CardService.Wallet w = cards.openWallet(holder.id());
        cards.topUp(w.id(), 10_000, key());
        assertThat(cards.pay(w.id(), m.id(), 3_000, key()).wallet().balanceMinor()).isEqualTo(7_000);
        assertThat(cards.pay(w.id(), m.id(), 8_000, key()).transaction().rejectReason())
                .isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThatThrownBy(() -> cards.topUp(w.id(), 95_000, key())).isInstanceOf(BusinessConflictException.class);
        assertThat(balanceOf(holder.id())).isEqualTo(22_000);
        assertThat(reconciliation.run().ok()).isTrue();

        // 加盟店精算：売上 21,000 − 手数料 3%（630）= 20,370 を加盟店の口座へ
        var settlements = cards.settleMerchants();
        assertThat(settlements).singleElement().satisfies(s -> {
            assertThat(s.grossMinor()).isEqualTo(21_000);
            assertThat(s.feeMinor()).isEqualTo(630);
        });
        assertThat(balanceOf(shop.id())).isEqualTo(20_370);
        assertThat(cards.settleMerchants()).isEmpty();
        assertThat(reconciliation.run().ok()).isTrue();
    }
}
