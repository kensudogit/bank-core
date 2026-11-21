package com.example.bankcore.cards;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** カードと電子決済：加盟店・カード発行・オーソリ（拘束）・売上確定・取消・電子マネー・加盟店精算。 */
class CardServiceTest extends AbstractIntegrationTest {

    @Autowired CardService cards;
    @Autowired ReconciliationService reconciliation;

    private long held(long accountId) {
        return accountService.get(accountId).heldBalanceMinor();
    }

    @Test
    void merchantAndCardRegistration() {
        Account shop = openAccount("Shop", 0);
        CardService.Merchant m = cards.registerMerchant("書店", "5942", shop.id(), 250);
        assertThat(cards.merchant(m.id()).feeRateBp()).isEqualTo(250);
        Account holder = openAccount("H", 0);
        CardService.Card c = cards.issueCard(holder.id(), 50_000);
        assertThat(c.status()).isEqualTo("ACTIVE");
        assertThat(c.token()).isNotNull();
        assertThat(cards.cards(holder.id())).extracting(CardService.Card::id).containsExactly(c.id());
        assertThat(cards.setCardStatus(c.id(), false).status()).isNotEqualTo("ACTIVE");
        assertThat(cards.setCardStatus(c.id(), true).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> cards.card(999_999)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> cards.merchant(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void authorizationHoldsFundsAndCaptureDebitsOnlyCapturedAmount() {
        Account holder = openAccount("H", 10_000);
        Account shop = openAccount("Shop", 0);
        CardService.Merchant m = cards.registerMerchant("店", "5411", shop.id(), 300);
        CardService.Card c = cards.issueCard(holder.id(), 100_000);

        CardService.Authorization a = cards.authorize(c.token(), m.id(), 4_000, "A-1");
        assertThat(held(holder.id())).isEqualTo(4_000);
        assertThat(cards.authorizations(c.id())).hasSize(1);
        assertThat(cards.authorization(a.id()).status()).isEqualTo("AUTHORIZED");

        String k = key();
        CardService.CaptureResult cap = cards.capture(a.id(), 3_500, k);
        assertThat(cap.replayed()).isFalse();
        assertThat(cards.capture(a.id(), 3_500, k).replayed()).isTrue();
        assertThat(held(holder.id())).isZero();
        assertThat(balanceOf(holder.id())).isEqualTo(6_500);
        assertThatThrownBy(() -> cards.capture(a.id(), 1, key())).isInstanceOf(BusinessConflictException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void captureCannotExceedAuthorizedAmountAndReleaseFreesHold() {
        Account holder = openAccount("H", 10_000);
        CardService.Merchant m = cards.registerMerchant("店", "5411", openAccount("Shop", 0).id(), 300);
        CardService.Card c = cards.issueCard(holder.id(), 100_000);
        CardService.Authorization a = cards.authorize(c.token(), m.id(), 1_000, "A-1");
        assertThatThrownBy(() -> cards.capture(a.id(), 1_001, key())).isInstanceOf(RuntimeException.class);
        assertThat(cards.release(a.id()).status()).isNotEqualTo("AUTHORIZED");
        assertThat(held(holder.id())).isZero();
        assertThatThrownBy(() -> cards.release(a.id())).isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void unknownCardTokenIsNotFound() {
        CardService.Merchant m = cards.registerMerchant("店", "5411", openAccount("Shop", 0).id(), 300);
        assertThatThrownBy(() -> cards.authorize(UUID.randomUUID(), m.id(), 100, "X"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void walletTopUpAndPaymentAreIdempotent() {
        Account holder = openAccount("H", 20_000);
        CardService.Merchant m = cards.registerMerchant("店", "5411", openAccount("Shop", 0).id(), 300);
        CardService.Wallet w = cards.openWallet(holder.id());
        assertThat(w.balanceMinor()).isZero();
        String k = key();
        assertThat(cards.topUp(w.id(), 5_000, k).wallet().balanceMinor()).isEqualTo(5_000);
        assertThat(cards.topUp(w.id(), 5_000, k).replayed()).isTrue();
        assertThat(cards.wallet(w.id()).balanceMinor()).isEqualTo(5_000);
        assertThat(balanceOf(holder.id())).isEqualTo(15_000);
        assertThat(cards.pay(w.id(), m.id(), 1_200, key()).wallet().balanceMinor()).isEqualTo(3_800);
        assertThatThrownBy(() -> cards.topUp(w.id(), 0, key())).isInstanceOf(InvalidRequestException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void settlementPaysNetOfFeeOncePerSale() {
        Account holder = openAccount("H", 100_000);
        Account shop = openAccount("Shop", 0);
        CardService.Merchant m = cards.registerMerchant("店", "5411", shop.id(), 500);   // 5%
        CardService.Card c = cards.issueCard(holder.id(), 100_000);
        cards.capture(cards.authorize(c.token(), m.id(), 10_000, "S-1").id(), 10_000, key());
        assertThat(cards.settleMerchants()).singleElement().satisfies(s -> {
            assertThat(s.items()).isEqualTo(1);
            assertThat(s.feeMinor()).isEqualTo(500);
            assertThat(s.netMinor()).isEqualTo(9_500);
        });
        assertThat(balanceOf(shop.id())).isEqualTo(9_500);
        assertThat(cards.settleMerchants()).isEmpty();
        assertThat(reconciliation.run().ok()).isTrue();
    }
}
