package com.example.bankcore.securities;

import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 有価証券：銘柄マスター・価格の登録・売買の検証・保有と約定の照会。 */
class SecuritiesServiceTest extends AbstractIntegrationTest {

    @Autowired SecuritiesService securities;
    @Autowired ReconciliationService reconciliation;

    @Test
    void masterContainsBondsAndStocks() {
        assertThat(securities.securities()).extracting(SecuritiesService.Security::code)
                .contains("JGB10-370", "CB-SAKURA", "STK-1001");
        assertThat(securities.securities()).filteredOn(s -> s.type().equals("STOCK"))
                .allSatisfy(s -> assertThat(s.couponRateBp()).isNull());
    }

    @Test
    void stockBuyAndSellUseMovingAverageCost() {
        securities.trade("STK-1001", "BUY", 100, new BigDecimal("1000"), "dealer1", key());
        securities.trade("STK-1001", "BUY", 100, new BigDecimal("2000"), "dealer1", key());
        assertThat(securities.holding("STK-1001").orElseThrow().bookValueMinor()).isEqualTo(300_000);
        var sell = securities.trade("STK-1001", "SELL", 100, new BigDecimal("1800"), "dealer1", key());
        // 平均単価 1,500 → 100 株の簿価 150,000、売却益 30,000
        assertThat(sell.trade().realizedPnlMinor()).isEqualTo(30_000);
        assertThat(sell.holding().quantity()).isEqualTo(100);
        assertThat(securities.trades("STK-1001")).hasSize(3);
        assertThat(securities.trades("JGB10-370")).isEmpty();
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void tradeValidation() {
        assertThatThrownBy(() -> securities.trade("STK-1001", "HOLD", 1, new BigDecimal("1"), "d", key()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> securities.trade("NOPE", "BUY", 1, new BigDecimal("1"), "d", key()))
                .isInstanceOf(NotFoundException.class);
        String k = key();
        securities.trade("STK-1001", "BUY", 10, new BigDecimal("1000"), "d", k);
        assertThatThrownBy(() -> securities.trade("STK-1001", "BUY", 11, new BigDecimal("1000"), "d", k))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void pricesAreValidatedAndUsedForValuation() {
        assertThatThrownBy(() -> securities.recordPrice("STK-1001", INITIAL_BUSINESS_DATE, BigDecimal.ZERO))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> securities.recordPrice("STK-1001", INITIAL_BUSINESS_DATE, new BigDecimal("1.00001")))
                .isInstanceOf(InvalidRequestException.class);
        securities.trade("STK-1001", "BUY", 100, new BigDecimal("1000"), "dealer1", key());
        securities.recordPrice("STK-1001", INITIAL_BUSINESS_DATE, new BigDecimal("900"));
        SecuritiesService.ValuationReport v = securities.markToMarket(INITIAL_BUSINESS_DATE);
        assertThat(v.valued()).isEqualTo(1);
        assertThat(v.totalAdjustmentMinor()).isEqualTo(-10_000);
        assertThat(securities.portfolio()).singleElement().satisfies(p -> {
            assertThat(p.price()).isEqualByComparingTo("900");
            assertThat(p.holding().valuationAdjustmentMinor()).isEqualTo(-10_000);
        });
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void nothingHeldNothingToProcess() {
        assertThat(securities.holding("STK-1001")).isEmpty();
        assertThat(securities.portfolio()).isEmpty();
        assertThat(securities.processIncome(INITIAL_BUSINESS_DATE).coupons()).isZero();
        assertThat(securities.markToMarket(INITIAL_BUSINESS_DATE).valued()).isZero();
    }
}
