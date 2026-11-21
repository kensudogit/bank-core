package com.example.bankcore.loan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 担保の種類：掛目（評価額のうち保全として認める割合）。 */
class CollateralTypeTest {

    @Test
    void haircutsReflectLiquidity() {
        assertThat(CollateralType.DEPOSIT.haircutBp()).isEqualTo(10_000);      // 預金担保は 100%
        assertThat(CollateralType.REAL_ESTATE.haircutBp()).isEqualTo(7_000);
        assertThat(CollateralType.SECURITIES.haircutBp()).isEqualTo(6_000);
    }

    @Test
    void haircutsAreWithinZeroToHundredPercent() {
        for (CollateralType t : CollateralType.values()) {
            assertThat(t.haircutBp()).isBetween(1, 10_000);
        }
    }
}
