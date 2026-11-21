package com.example.bankcore.risk;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** リスク管理：保有なしの市場リスク、流動性（預金流出の想定と HQLA）、ダッシュボードの構成。 */
class RiskServiceTest extends AbstractIntegrationTest {

    @Autowired RiskService risk;

    @Test
    void noPositionsMeansNoMarketRisk() {
        RiskService.MarketRisk m = risk.market();
        assertThat(m.securities()).isEmpty();
        assertThat(m.fx()).isEmpty();
        assertThat(m.totalVar99Minor()).isZero();
        assertThat(m.totalBpvMinor()).isZero();
    }

    @Test
    void depositsCreateOutflowsAndCashCountsAsHqla() {
        Account a = openAccount("A", 10_000_000);
        RiskService.LiquidityRisk l = risk.liquidity();
        assertThat(l.hqlaMinor()).isPositive();
        assertThat(l.outflows30dMinor()).isPositive().isLessThanOrEqualTo(10_000_000);
        assertThat(l.maturityLadder()).hasSize(5);
        assertThat(l.lcrPct()).isNotNull().isPositive();
        assertThat(a.id()).isPositive();
    }

    @Test
    void dashboardCombinesCreditMarketAndLiquidity() {
        openAccount("A", 1_000_000);
        RiskService.Dashboard d = risk.dashboard();
        assertThat(d.businessDate()).isEqualTo(INITIAL_BUSINESS_DATE);
        assertThat(d.credit().loans()).isZero();
        assertThat(d.market()).isNotNull();
        assertThat(d.liquidity().hqlaMinor()).isPositive();
    }
}
