package com.example.bankcore.loan;

import com.example.bankcore.loan.CreditScoring.Input;
import com.example.bankcore.loan.CreditScoring.Recommendation;
import com.example.bankcore.loan.CreditScoring.Result;
import com.example.bankcore.loan.LoanModels.InstallmentStatus;
import com.example.bankcore.loan.LoanModels.ScheduledInstallment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 与信審査のスコアリング・返済額の充当・信用リスク計算の単体テスト。DB なし。 */
class CreditScoringTest {

    private static Input housing(long income, long secured, boolean realEstate, int delinquent) {
        return new Input(LoanProduct.HOUSING, 30_000_000, 420, RepaymentMethod.EQUAL_PAYMENT, income, 0,
                secured, realEstate, 5_000_000, delinquent);
    }

    @Test
    void strongApplicantIsApprovedAtTheBestGrade() {
        Result r = CreditScoring.evaluate(housing(12_000_000, 40_000_000, true, 0));
        assertThat(r.grade()).isEqualTo("A");
        assertThat(r.recommendation()).isEqualTo(Recommendation.APPROVE);
        assertThat(r.proposedRateBp()).isEqualTo(LoanProduct.HOUSING.baseRateBp());
        assertThat(r.dtiBp()).isLessThan(2000);
        assertThat(r.coverageBp()).isGreaterThanOrEqualTo(10_000);
    }

    @Test
    void hardRulesDeclineRegardlessOfScore() {
        // 住宅ローンに不動産担保なし
        assertThat(CreditScoring.evaluate(housing(12_000_000, 40_000_000, false, 0)).recommendation())
                .isEqualTo(Recommendation.DECLINE);
        // 延滞中の融資あり
        assertThat(CreditScoring.evaluate(housing(12_000_000, 40_000_000, true, 1)).recommendation())
                .isEqualTo(Recommendation.DECLINE);
        // 返済比率 40% 超
        Result highDti = CreditScoring.evaluate(housing(2_000_000, 40_000_000, true, 0));
        assertThat(highDti.dtiBp()).isGreaterThan(4000);
        assertThat(highDti.recommendation()).isEqualTo(Recommendation.DECLINE);
        // 消費者ローンの総量規制：年収 300 万円に対して 120 万円（1/3 超）
        Result overLimit = CreditScoring.evaluate(new Input(LoanProduct.CONSUMER, 1_200_000, 36,
                RepaymentMethod.EQUAL_PAYMENT, 3_000_000, 0, 0, false, 500_000, 0));
        assertThat(overLimit.recommendation()).isEqualTo(Recommendation.DECLINE);
        assertThat(overLimit.reasons()).anyMatch(s -> s.contains("総量規制"));
    }

    @Test
    void repaymentIsAppliedToLateFeeThenInterestThenPrincipalAndTheRestIsPrepayment() {
        LocalDate today = LocalDate.of(2030, 6, 1);
        // 1 回目は 31 日延滞（元金 10,000・利息 1,000）、2 回目は本日期日、3 回目は将来
        List<ScheduledInstallment> installments = List.of(
                new ScheduledInstallment(1, 1, LocalDate.of(2030, 5, 1), 10_000, 1_000, 0, 0, 0, InstallmentStatus.SCHEDULED, null),
                new ScheduledInstallment(1, 2, today, 10_000, 900, 0, 0, 0, InstallmentStatus.SCHEDULED, null),
                new ScheduledInstallment(1, 3, LocalDate.of(2030, 7, 1), 10_000, 800, 0, 0, 0, InstallmentStatus.SCHEDULED, null));
        long fee = LoanMath.lateFee(11_000, 31);           // 11,000 × 14.6% × 31 / 365 = 136
        assertThat(fee).isEqualTo(136);

        // 期日到来分 = 136 + 1,000 + 10,000 + 900 + 10,000 = 22,036。さらに 5,000 を繰上返済
        LoanService.Allocation a = LoanService.allocate(installments, 30_000, 27_036, today);
        assertThat(a.lateFee()).isEqualTo(136);
        assertThat(a.interest()).isEqualTo(1_900);
        assertThat(a.principal()).isEqualTo(20_000);
        assertThat(a.prepaid()).isEqualTo(5_000);
        assertThat(a.excess()).isZero();

        // 少額なら遅延損害金 → 利息 → 元金の順に一部だけ充当
        LoanService.Allocation partial = LoanService.allocate(installments, 30_000, 600, today);
        assertThat(partial.lateFee()).isEqualTo(136);
        assertThat(partial.interest()).isEqualTo(464);
        assertThat(partial.principal()).isZero();

        // 完済額（期日到来分 + 残りの元金 10,000）を超える分は excess
        assertThat(LoanService.allocate(installments, 30_000, 32_037, today).excess()).isEqualTo(1);
        assertThat(LoanService.daysPastDue(installments, today)).isEqualTo(31);
    }

    @Test
    void expectedLossUsesGradePdWithClassFloorAndSecuredLgd() {
        // 残高 1,000,000、保全 600,000：LGD 額 = 600,000×10% + 400,000×45% = 240,000
        assertThat(CreditRiskService.expectedLoss(1_000_000, 600_000, 10_000)).isEqualTo(240_000);
        assertThat(CreditRiskService.expectedLoss(1_000_000, 600_000, 150)).isEqualTo(3_600);
        assertThat(CreditRiskService.pdBp("A", BorrowerClass.NORMAL)).isEqualTo(20);
        assertThat(CreditRiskService.pdBp("A", BorrowerClass.DOUBTFUL)).isEqualTo(5000);
    }
}
