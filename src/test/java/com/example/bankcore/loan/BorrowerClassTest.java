package com.example.bankcore.loan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 債務者区分：延滞日数からの判定、引当額（無担保部分基準の区分を含む）、不良債権・償却の対象。 */
class BorrowerClassTest {

    @ParameterizedTest
    @CsvSource({
            "-5, NORMAL", "0, NORMAL", "1, WATCH", "89, WATCH", "90, SPECIAL_ATTENTION", "179, SPECIAL_ATTENTION",
            "180, DOUBTFUL", "364, DOUBTFUL", "365, DEFACTO_BANKRUPT", "1000, DEFACTO_BANKRUPT"})
    void classifiesByDaysPastDue(int days, BorrowerClass expected) {
        assertThat(BorrowerClass.fromDaysPastDue(days)).isEqualTo(expected);
    }

    @Test
    void provisionOnWholeBalanceForPerformingClasses() {
        // 正常先 0.5%、要注意先 3%、要管理先 15%（担保は考慮しない）
        assertThat(BorrowerClass.NORMAL.requiredProvision(1_000_000, 900_000)).isEqualTo(5_000);
        assertThat(BorrowerClass.WATCH.requiredProvision(1_000_000, 900_000)).isEqualTo(30_000);
        assertThat(BorrowerClass.SPECIAL_ATTENTION.requiredProvision(1_000_000, 0)).isEqualTo(150_000);
    }

    @Test
    void provisionOnUnsecuredPortionForDoubtfulAndBankrupt() {
        assertThat(BorrowerClass.DOUBTFUL.requiredProvision(1_000_000, 400_000)).isEqualTo(420_000);   // 60 万 × 70%
        assertThat(BorrowerClass.DEFACTO_BANKRUPT.requiredProvision(1_000_000, 400_000)).isEqualTo(600_000);
        assertThat(BorrowerClass.DEFACTO_BANKRUPT.requiredProvision(1_000_000, 2_000_000)).isZero();   // 担保超過
    }

    @Test
    void nonPerformingAndWriteOffThresholds() {
        assertThat(BorrowerClass.WATCH.nonPerforming()).isFalse();
        assertThat(BorrowerClass.SPECIAL_ATTENTION.nonPerforming()).isTrue();
        assertThat(BorrowerClass.SPECIAL_ATTENTION.writeOffEligible()).isFalse();
        assertThat(BorrowerClass.DOUBTFUL.writeOffEligible()).isTrue();
        assertThat(BorrowerClass.DEFACTO_BANKRUPT.writeOffEligible()).isTrue();
    }

    @Test
    void ratesIncreaseWithSeverityAndLabelsAreJapanese() {
        BorrowerClass[] all = BorrowerClass.values();
        for (int i = 1; i < all.length; i++) {
            assertThat(all[i].provisionRateBp()).isGreaterThan(all[i - 1].provisionRateBp());
            assertThat(all[i].pdFloorBp()).isGreaterThanOrEqualTo(all[i - 1].pdFloorBp());
        }
        assertThat(BorrowerClass.NORMAL.label()).isEqualTo("正常先");
        assertThat(BorrowerClass.DEFACTO_BANKRUPT.provisionRateBp()).isEqualTo(10_000);
    }
}
