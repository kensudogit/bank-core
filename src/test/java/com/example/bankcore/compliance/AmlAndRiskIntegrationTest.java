package com.example.bankcore.compliance;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountStatus;
import com.example.bankcore.cards.CardService;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.crm.CustomerService.NewCustomer;
import com.example.bankcore.payment.ForexService;
import com.example.bankcore.risk.RiskService;
import com.example.bankcore.securities.SecuritiesService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** コンプライアンス（AML 監視・案件管理・届出）とリスク管理（市場・流動性）。実 PostgreSQL。 */
class AmlAndRiskIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    AmlService aml;

    @Autowired
    CustomerService customers;

    @Autowired
    CardService cards;

    @Autowired
    RiskService risk;

    @Autowired
    SecuritiesService securities;

    @Autowired
    ForexService forex;

    @Test
    void monitoringRaisesEachRuleOnceAndCasesCanBeReported() {
        var c = customers.register(new NewCustomer("INDIVIDUAL", "Suspect", null, null, null, null, null, null, null));
        Account a = openAccount("Suspect", 0);
        Account b = openAccount("Other", 0);
        customers.linkAccount(c.id(), a.id());

        movements.execute(MovementCommand.deposit(key(), a.id(), 2_500_000, "JPY", null, null));     // 大口現金
        for (int i = 0; i < 3; i++) {
            movements.execute(MovementCommand.deposit(key(), b.id(), 700_000, "JPY", null, null));   // 分割入金
        }
        Account c2 = openAccount("Mule", 0);
        movements.execute(MovementCommand.deposit(key(), c2.id(), 1_500_000, "JPY", null, null));
        movements.execute(MovementCommand.transfer(key(), c2.id(), b.id(), 1_400_000, "JPY", null, null));   // 通過口座

        Account holder = openAccount("CardUser", 100_000);
        Account shop = openAccount("Shop", 0);
        CardService.Merchant m = cards.registerMerchant("EC", "5999", shop.id(), 300);
        CardService.Card card = cards.issueCard(holder.id(), 1_000_000);
        for (int i = 0; i < 5; i++) {
            cards.authorize(card.token(), m.id(), 1_000, "R-" + i);
        }

        AmlService.ScanReport first = aml.scan();
        assertThat(aml.alerts(null)).extracting(AmlService.Alert::rule)
                .contains("LARGE_CASH", "STRUCTURING", "RAPID_MOVEMENT", "CARD_VELOCITY");
        assertThat(first.alertsCreated()).isEqualTo(aml.alerts(null).size());
        assertThat(aml.scan().alertsCreated()).isZero();                 // 再実行で重複させない

        AmlService.Alert large = aml.alerts(null).stream().filter(x -> x.rule().equals("LARGE_CASH")).findFirst().orElseThrow();
        assertThat(large.customerId()).isEqualTo(c.id());
        aml.investigate(large.id(), "compliance1");
        assertThatThrownBy(() -> aml.close(large.id(), "compliance1", " ")).isInstanceOf(InvalidRequestException.class);
        AmlService.Alert reported = aml.report(large.id(), "compliance1", "資金源の説明が得られない", true);
        assertThat(reported.status()).isEqualTo("REPORTED");
        assertThat(customers.get(c.id()).amlRisk()).isEqualTo("HIGH");
        assertThat(accountService.get(a.id()).status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(count("suspicious_transaction_reports")).isEqualTo(1);

        // 高リスク顧客になった後の 100 万円以上の取引はアラート
        accountService.changeStatus(a.id(), AccountStatus.ACTIVE);
        movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 1_000_000, "JPY", null, null));
        aml.scan();
        assertThat(aml.alerts("OPEN")).extracting(AmlService.Alert::rule).contains("HIGH_RISK_CUSTOMER");
    }

    @Test
    void riskDashboardMeasuresMarketAndLiquidityRisk() {
        Account yen = openAccount("Taro", 10_000_000);
        Account usd = accountService.open("Taro", "USD", 0);
        forex.exchange(yen.id(), usd.id(), 1_000_000, key(), null);        // 顧客が USD 10,000 を購入
        securities.trade("JGB10-370", "BUY", 5_000_000, new BigDecimal("100"), "dealer1", key());
        securities.trade("STK-1001", "BUY", 100, new BigDecimal("2000"), "dealer1", key());

        RiskService.Dashboard d = risk.dashboard();
        List<RiskService.SecurityRisk> sec = d.market().securities();
        assertThat(sec).hasSize(2);
        // 株式 200,000 × 1.5% × 2.326 = 6,978
        assertThat(sec.stream().filter(s -> s.code().equals("STK-1001")).findFirst().orElseThrow().var99Minor())
                .isEqualTo(6_978);
        assertThat(d.market().fx()).singleElement().satisfies(f -> {
            assertThat(f.currency()).isEqualTo("USD");
            assertThat(f.positionMinor()).isEqualTo(-1_000_000);              // 外貨の売り持ち（未カバー）
            assertThat(f.positionJpyMinor()).isEqualTo(-1_500_000);
            assertThat(f.var99Minor()).isPositive();
        });
        assertThat(d.market().totalVar99Minor()).isGreaterThanOrEqualTo(d.market().uncorrelatedVar99Minor());
        assertThat(d.market().totalBpvMinor()).isPositive();
        assertThat(d.liquidity().hqlaMinor()).isGreaterThan(0);
        assertThat(d.liquidity().outflows30dMinor()).isPositive();
        assertThat(d.liquidity().maturityLadder()).hasSize(5);
        assertThat(d.credit().loans()).isZero();
    }
}
