package com.example.bankcore.loan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 融資商品：金利・期間・金額の範囲と、商品ごとの審査条件。 */
class LoanProductTest {

    @Test
    void housingRequiresRealEstateAndAllowsLongTerms() {
        assertThat(LoanProduct.HOUSING.requiresRealEstate()).isTrue();
        assertThat(LoanProduct.HOUSING.maxTermMonths()).isEqualTo(420);
        assertThat(LoanProduct.HOUSING.totalVolumeRule()).isFalse();
    }

    @Test
    void consumerIsSubjectToTotalVolumeRuleAndHasHighestRate() {
        assertThat(LoanProduct.CONSUMER.totalVolumeRule()).isTrue();
        assertThat(LoanProduct.CONSUMER.baseRateBp())
                .isGreaterThan(LoanProduct.BUSINESS.baseRateBp())
                .isGreaterThan(LoanProduct.HOUSING.baseRateBp());
    }

    @Test
    void rangesAreConsistent() {
        for (LoanProduct p : LoanProduct.values()) {
            assertThat(p.minTermMonths()).isPositive().isLessThanOrEqualTo(p.maxTermMonths());
            assertThat(p.minPrincipalMinor()).isPositive().isLessThan(p.maxPrincipalMinor());
            assertThat(p.baseRateBp()).isPositive();
        }
    }
}
