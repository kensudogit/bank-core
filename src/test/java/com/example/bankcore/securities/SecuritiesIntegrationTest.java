package com.example.bankcore.securities;

import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.securities.SecuritiesService.TradeResult;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 有価証券業務：売買（経過利子・移動平均簿価・売却損益）、時価評価、利金、償還。実 PostgreSQL。 */
class SecuritiesIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    SecuritiesService securities;

    @Autowired
    ReconciliationService reconciliation;

    private void setDate(LocalDate d) {
        jdbc.sql("UPDATE business_calendar SET current_business_date = :d").param("d", d).update();
    }

    private long gl(String code) {
        return jdbc.sql("SELECT COALESCE(SUM(debit_minor - credit_minor), 0) FROM journal_lines WHERE gl_code = :c")
                .param("c", code).query(Long.class).single();
    }

    @Test
    void bondTradeValuationSaleAndCoupon() {
        String k = key();
        // 額面 1,000 万円を 99.50 円で購入。経過利子 = 1,000 万 × 0.8% × 12 日（3/20 から）÷ 365 = 2,630
        TradeResult buy = securities.trade("JGB10-370", "BUY", 10_000_000, new BigDecimal("99.50"), "dealer1", k);
        assertThat(buy.trade().principalMinor()).isEqualTo(9_950_000);
        assertThat(buy.trade().accruedInterestMinor()).isEqualTo(2_630);
        assertThat(gl("1400")).isEqualTo(9_950_000);
        assertThat(gl("1100")).isEqualTo(-9_952_630);
        assertThat(securities.trade("JGB10-370", "BUY", 10_000_000, new BigDecimal("99.50"), "dealer1", k).replayed()).isTrue();

        // 時価評価：100.20 → 評価差額 +70,000（同じ日の再実行では記帳しない）
        securities.recordPrice("JGB10-370", INITIAL_BUSINESS_DATE, new BigDecimal("100.20"));
        assertThat(securities.markToMarket(INITIAL_BUSINESS_DATE).totalAdjustmentMinor()).isEqualTo(70_000);
        securities.markToMarket(INITIAL_BUSINESS_DATE);
        assertThat(gl("3100")).isEqualTo(-70_000);
        assertThat(reconciliation.run().ok()).isTrue();

        // 半分を 100.50 で売却：簿価 4,975,000、売却益 50,000、評価差額 35,000 を取り崩す
        TradeResult sell = securities.trade("JGB10-370", "SELL", 5_000_000, new BigDecimal("100.50"), "dealer1", key());
        assertThat(sell.trade().realizedPnlMinor()).isEqualTo(50_000);
        assertThat(sell.holding().bookValueMinor()).isEqualTo(4_975_000);
        assertThat(sell.holding().valuationAdjustmentMinor()).isEqualTo(35_000);
        assertThat(gl("4310")).isEqualTo(-50_000);
        assertThatThrownBy(() -> securities.trade("JGB10-370", "SELL", 6_000_000, new BigDecimal("100"), "dealer1", key()))
                .isInstanceOf(BusinessConflictException.class);

        // 利払日（9/20）：残り額面 500 万 × 0.8% ÷ 2 = 20,000。再実行しても 1 回だけ
        LocalDate couponDay = LocalDate.of(2030, 9, 20);
        setDate(couponDay);
        assertThat(securities.processIncome(couponDay).couponMinor()).isEqualTo(20_000);
        assertThat(securities.processIncome(couponDay).coupons()).isZero();
        assertThat(securities.portfolio()).singleElement().satisfies(p -> assertThat(p.bpvMinor()).isPositive());
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void redemptionAtMaturityAndStockTrading() {
        assertThatThrownBy(() -> securities.trade("JGB5-160", "BUY", 1_000_000, new BigDecimal("100"), "dealer1", key()))
                .isInstanceOf(BusinessConflictException.class);   // 2028 年に償還済み
        securities.trade("CB-SAKURA", "BUY", 1_000_000, new BigDecimal("100.00"), "dealer1", key());
        TradeResult stock = securities.trade("STK-1001", "BUY", 1_000, new BigDecimal("1500"), "dealer1", key());
        assertThat(stock.trade().principalMinor()).isEqualTo(1_500_000);
        assertThat(stock.trade().accruedInterestMinor()).isZero();

        LocalDate maturity = LocalDate.of(2030, 6, 20);
        setDate(maturity);
        SecuritiesService.IncomeReport r = securities.processIncome(maturity);
        assertThat(r.couponMinor()).isEqualTo(6_000);        // 100 万 × 1.2% ÷ 2
        assertThat(r.redeemedMinor()).isEqualTo(1_000_000);
        assertThat(securities.holding("CB-SAKURA").orElseThrow().quantity()).isZero();
        assertThat(securities.processIncome(maturity).redemptions()).isZero();
        assertThat(reconciliation.run().ok()).isTrue();
    }
}
