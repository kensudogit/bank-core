package com.example.bankcore.loan;

import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.loan.CreditRiskService.RiskReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 信用リスク：ポートフォリオの残高・期待損失・集中度・ストレステスト。 */
class CreditRiskServiceTest extends LoanTestSupport {

    @Autowired CreditRiskService risk;

    @Test
    void emptyPortfolio() {
        RiskReport r = risk.report(200, 30);
        assertThat(r.loans()).isZero();
        assertThat(r.outstandingMinor()).isZero();
        assertThat(r.expectedLossMinor()).isZero();
        assertThat(r.topExposures()).isEmpty();
    }

    @Test
    void aggregatesLoansAndStressIncreasesExpectedLoss() {
        disbursed(openAccount("A", 0), 2_000_000);
        disbursed(openAccount("B", 0), 1_000_000);
        RiskReport r = risk.report(200, 30);
        assertThat(r.loans()).isEqualTo(2);
        assertThat(r.outstandingMinor()).isEqualTo(3_000_000);
        assertThat(r.expectedLossMinor()).isPositive();
        assertThat(r.stressedExpectedLossMinor()).isGreaterThan(r.expectedLossMinor());
        assertThat(r.topExposures()).first().satisfies(e -> {
            assertThat(e.borrower()).isEqualTo("A");
            assertThat(e.outstandingMinor()).isEqualTo(2_000_000);
        });
        assertThat(r.concentrationHhi()).isPositive();
        assertThat(r.byGrade().stream().mapToLong(CreditRiskService.Bucket::outstandingMinor).sum()).isEqualTo(3_000_000);
        assertThat(r.byBorrowerClass()).isNotEmpty();
        assertThat(r.nonPerformingMinor()).isZero();
    }

    @Test
    void noStressMeansStressedEqualsBase() {
        disbursed(openAccount("A", 0), 2_000_000);
        RiskReport r = risk.report(100, 0);
        assertThat(r.stressedExpectedLossMinor()).isEqualTo(r.expectedLossMinor());
    }

    @Test
    void rejectsOutOfRangeStressParameters() {
        assertThatThrownBy(() -> risk.report(-1, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> risk.report(1001, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> risk.report(100, 101)).isInstanceOf(InvalidRequestException.class);
    }
}
