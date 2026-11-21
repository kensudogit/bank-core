package com.example.bankcore.wealth;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.crm.CustomerService.NewCustomer;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 投資信託と金銭信託：商品一覧・申込の検証・保有評価・信託の照会。 */
class WealthServiceTest extends AbstractIntegrationTest {

    @Autowired WealthService wealth;
    @Autowired CustomerService customers;
    @Autowired ReconciliationService reconciliation;

    private Account verifiedAccount(int riskTolerance, long deposit) {
        var c = customers.register(new NewCustomer("INDIVIDUAL", "Hanako", null, null, null, 6_000_000L, riskTolerance, 3,
                null));
        customers.decideKyc(c.id(), true);
        Account a = openAccount("Hanako", deposit);
        customers.linkAccount(c.id(), a.id());
        return a;
    }

    @Test
    void fundsListHasRiskLevelsAndNav() {
        assertThat(wealth.funds()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.riskLevel()).isBetween(1, 5);
            assertThat(f.nav()).isPositive();
        });
    }

    @Test
    void subscriptionValidationAndFunds() {
        Account a = verifiedAccount(5, 50_000);
        assertThatThrownBy(() -> wealth.subscribe(a.id(), "F-BAL", 0, key())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> wealth.subscribe(a.id(), "NOPE", 10_000, key())).isInstanceOf(NotFoundException.class);
        var r = wealth.subscribe(a.id(), "F-BAL", 60_000, key());   // 残高不足
        assertThat(r.transaction().rejectReason()).isNotNull();
        assertThat(balanceOf(a.id())).isEqualTo(50_000);
    }

    @Test
    void holdingsAreValuedAtNav() {
        Account a = verifiedAccount(5, 1_000_000);
        var sub = wealth.subscribe(a.id(), "F-BAL", 110_000, key());
        assertThat(wealth.holdings(a.id())).singleElement().satisfies(h -> {
            assertThat(h.fundCode()).isEqualTo("F-BAL");
            assertThat(h.units()).isEqualTo(sub.order().units());
            // 取得原価は手数料込みの申込金額
            assertThat(h.costMinor()).isEqualTo(sub.order().amountMinor() + sub.order().feeMinor()).isEqualTo(110_000);
            assertThat(h.marketValueMinor()).isPositive();
        });
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void redeemWithoutHoldingIsConflict() {
        Account a = verifiedAccount(5, 100_000);
        assertThatThrownBy(() -> wealth.redeem(a.id(), "F-BAL", 1, key())).isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void trustListingAndValidation() {
        Account a = verifiedAccount(1, 1_000_000);
        var t = wealth.entrust(a.id(), 300_000, 6, 50, key());
        assertThat(t.trust().principalMinor()).isEqualTo(300_000);
        assertThat(wealth.trusts(a.id())).extracting(WealthService.MoneyTrust::id).containsExactly(t.trust().id());
        assertThat(wealth.trust(t.trust().id()).status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> wealth.entrust(a.id(), 0, 6, 50, key())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> wealth.trust(999_999)).isInstanceOf(NotFoundException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }
}
