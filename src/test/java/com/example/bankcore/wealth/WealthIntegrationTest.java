package com.example.bankcore.wealth;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.crm.CustomerService.NewCustomer;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 資産運用・信託：投資信託の販売（適合性）・解約・払込、金銭信託の受託と満期払出し。実 PostgreSQL。 */
class WealthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    WealthService wealth;

    @Autowired
    CustomerService customers;

    @Autowired
    ReconciliationService reconciliation;

    private Account customerAccount(int riskTolerance, boolean verified) {
        var c = customers.register(new NewCustomer("INDIVIDUAL", "Hanako", null, null, null, 6_000_000L,
                riskTolerance, 3, null));
        if (verified) {
            customers.decideKyc(c.id(), true);
        }
        Account a = openAccount("Hanako", 1_000_000);
        customers.linkAccount(c.id(), a.id());
        return a;
    }

    @Test
    void fundSalesRespectSuitabilityAndBalanceTheBooks() {
        Account a = customerAccount(3, true);
        // 適合性の原則：リスク水準 5 の商品はリスク許容度 3 の顧客に販売しない
        assertThatThrownBy(() -> wealth.subscribe(a.id(), "F-EQ", 100_000, key()))
                .isInstanceOf(BusinessConflictException.class);

        String k = key();
        // 申込 110,000（手数料 1.1% 内枠）：手数料 1,196、払込額 108,804、口数 = 108,804 × 10,000 ÷ 11,800
        WealthService.OrderResult sub = wealth.subscribe(a.id(), "F-BAL", 110_000, k);
        assertThat(sub.order().feeMinor()).isEqualTo(1_196);
        assertThat(sub.order().amountMinor()).isEqualTo(108_804);
        assertThat(sub.order().units()).isEqualTo(92_206);
        assertThat(balanceOf(a.id())).isEqualTo(890_000);
        assertThat(wealth.subscribe(a.id(), "F-BAL", 110_000, k).replayed()).isTrue();
        assertThat(reconciliation.run().ok()).isTrue();

        // 解約 50,000 口 × 11,800 ÷ 10,000 = 59,000
        WealthService.OrderResult red = wealth.redeem(a.id(), "F-BAL", 50_000, key());
        assertThat(red.order().amountMinor()).isEqualTo(59_000);
        assertThat(balanceOf(a.id())).isEqualTo(949_000);
        assertThat(wealth.holdings(a.id())).singleElement().satisfies(h -> assertThat(h.units()).isEqualTo(42_206));
        assertThatThrownBy(() -> wealth.redeem(a.id(), "F-BAL", 50_000, key()))
                .isInstanceOf(BusinessConflictException.class);

        assertThat(wealth.settleSubscriptions("F-BAL")).isEqualTo(108_804);
        assertThat(wealth.settleSubscriptions("F-BAL")).isZero();
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void customersWithoutKycCannotBuy() {
        Account a = customerAccount(5, false);
        assertThatThrownBy(() -> wealth.subscribe(a.id(), "F-BOND", 100_000, key()))
                .isInstanceOf(BusinessConflictException.class);
        Account unlinked = openAccount("Nobody", 100_000);
        assertThatThrownBy(() -> wealth.subscribe(unlinked.id(), "F-BOND", 50_000, key()))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void moneyTrustIsSegregatedAndPaidOutOnceAtMaturity() {
        Account a = customerAccount(1, true);
        WealthService.TrustResult t = wealth.entrust(a.id(), 500_000, 12, 100, key());
        assertThat(t.trust().maturityDate()).isEqualTo(INITIAL_BUSINESS_DATE.plusMonths(12));
        assertThat(balanceOf(a.id())).isEqualTo(500_000);
        assertThat(reconciliation.run().ok()).isTrue();

        LocalDate maturity = t.trust().maturityDate();
        assertThat(wealth.payoutMatured(maturity.minusDays(1))).isZero();
        assertThat(wealth.payoutMatured(maturity)).isEqualTo(1);
        assertThat(wealth.payoutMatured(maturity)).isZero();
        // 配当 = 500,000 × 1% × 365 ÷ 365 = 5,000
        assertThat(wealth.trust(t.trust().id()).dividendMinor()).isEqualTo(5_000);
        assertThat(balanceOf(a.id())).isEqualTo(1_005_000);
        assertThat(reconciliation.run().ok()).isTrue();
    }
}
