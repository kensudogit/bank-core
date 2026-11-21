package com.example.bankcore.loan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 返済計算（元利均等・元金均等・遅延損害金）の単体テスト。DB なし。 */
class LoanMathTest {

    private static final LocalDate START = LocalDate.of(2030, 4, 1);

    @Test
    void equalPaymentMatchesTheAnnuityFormulaAndRepaysExactlyThePrincipal() {
        // 1,200,000 円・年 12%（月 1%）・12 回：P·r·(1+r)^n / ((1+r)^n − 1) = 106,618.55… → 切り上げ 106,619
        assertThat(LoanMath.equalPayment(1_200_000, 1200, 12)).isEqualTo(106_619);

        List<LoanMath.Installment> s = LoanMath.schedule(1_200_000, 1200, RepaymentMethod.EQUAL_PAYMENT,
                LoanMath.dueDates(START, 12));
        assertThat(s).hasSize(12);
        assertThat(s.getFirst().interestMinor()).isEqualTo(12_000);          // 1,200,000 × 1%
        assertThat(s.getFirst().principalMinor()).isEqualTo(94_619);
        assertThat(s.stream().mapToLong(LoanMath.Installment::principalMinor).sum()).isEqualTo(1_200_000);
        assertThat(s.getLast().balanceAfterMinor()).isZero();
        // 最終回は端数調整で、ほかの回の返済額を超えない
        assertThat(s.getLast().totalMinor()).isLessThanOrEqualTo(106_619);
        for (int k = 0; k < 11; k++) {
            assertThat(s.get(k).totalMinor()).isEqualTo(106_619);
        }
    }

    @Test
    void equalPrincipalPutsTheRemainderOnTheFirstInstallmentAndInterestDeclines() {
        List<LoanMath.Installment> s = LoanMath.schedule(1_000_003, 1200, RepaymentMethod.EQUAL_PRINCIPAL,
                LoanMath.dueDates(START, 4));
        assertThat(s).extracting(LoanMath.Installment::principalMinor)
                .containsExactly(250_003L, 250_000L, 250_000L, 250_000L);
        assertThat(s).extracting(LoanMath.Installment::interestMinor)
                .containsExactly(10_000L, 7_500L, 5_000L, 2_500L);   // 残高 × 1%（切り捨て）
        assertThat(s.getLast().balanceAfterMinor()).isZero();
    }

    @Test
    void zeroRateAndDueDatesAtMonthEnd() {
        assertThat(LoanMath.equalPayment(1_000, 0, 3)).isEqualTo(334);
        List<LoanMath.Installment> s = LoanMath.schedule(1_000, 0, RepaymentMethod.EQUAL_PAYMENT,
                LoanMath.dueDates(START, 3));
        assertThat(s).extracting(LoanMath.Installment::principalMinor).containsExactly(334L, 334L, 332L);
        // 1/31 実行の翌月は 2/28（月末に寄せる）
        assertThat(LoanMath.dueDates(LocalDate.of(2031, 1, 31), 2))
                .containsExactly(LocalDate.of(2031, 2, 28), LocalDate.of(2031, 3, 31));
    }

    @Test
    void lateFeeIsFourteenPointSixPercentPerYearByDay() {
        // 100,000 × 14.6% × 30 / 365 = 1,200
        assertThat(LoanMath.lateFee(100_000, 30)).isEqualTo(1_200);
        assertThat(LoanMath.lateFee(100_000, 0)).isZero();
        assertThat(LoanMath.lateFee(0, 30)).isZero();
    }

    @Test
    void borrowerClassBoundariesAndProvisionBasis() {
        assertThat(BorrowerClass.fromDaysPastDue(0)).isEqualTo(BorrowerClass.NORMAL);
        assertThat(BorrowerClass.fromDaysPastDue(1)).isEqualTo(BorrowerClass.WATCH);
        assertThat(BorrowerClass.fromDaysPastDue(90)).isEqualTo(BorrowerClass.SPECIAL_ATTENTION);
        assertThat(BorrowerClass.fromDaysPastDue(180)).isEqualTo(BorrowerClass.DOUBTFUL);
        assertThat(BorrowerClass.fromDaysPastDue(365)).isEqualTo(BorrowerClass.DEFACTO_BANKRUPT);
        // 正常先は残高全体に 0.5%、破綻懸念先は非保全部分に 70%
        assertThat(BorrowerClass.NORMAL.requiredProvision(1_000_000, 900_000)).isEqualTo(5_000);
        assertThat(BorrowerClass.DOUBTFUL.requiredProvision(1_000_000, 600_000)).isEqualTo(280_000);
        assertThat(BorrowerClass.DOUBTFUL.requiredProvision(1_000_000, 2_000_000)).isZero();
        assertThat(BorrowerClass.SPECIAL_ATTENTION.nonPerforming()).isTrue();
        assertThat(BorrowerClass.WATCH.nonPerforming()).isFalse();
    }
}
