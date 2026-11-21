package com.example.bankcore.loan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 返済方法：元利均等は毎回の返済額が一定、元金均等は毎回の元金が一定（返済額は逓減）。 */
class RepaymentMethodTest {

    private static List<LoanMath.Installment> schedule(RepaymentMethod method) {
        return LoanMath.schedule(1_200_000, 300, method, LoanMath.dueDates(LocalDate.of(2030, 4, 1), 12));
    }

    @Test
    void twoMethods() {
        assertThat(RepaymentMethod.values()).containsExactly(RepaymentMethod.EQUAL_PAYMENT, RepaymentMethod.EQUAL_PRINCIPAL);
    }

    @Test
    void equalPaymentKeepsTheInstallmentConstant() {
        List<LoanMath.Installment> s = schedule(RepaymentMethod.EQUAL_PAYMENT);
        // 最終回（端数調整）を除き返済額が同じ
        assertThat(s.subList(0, 11).stream().map(LoanMath.Installment::totalMinor).distinct()).hasSize(1);
        assertThat(s.getLast().balanceAfterMinor()).isZero();
    }

    @Test
    void equalPrincipalKeepsPrincipalConstantAndPaymentsDecline() {
        List<LoanMath.Installment> s = schedule(RepaymentMethod.EQUAL_PRINCIPAL);
        assertThat(s.stream().map(LoanMath.Installment::principalMinor).distinct()).hasSize(1);
        assertThat(s.getFirst().totalMinor()).isGreaterThan(s.getLast().totalMinor());
        assertThat(s.getLast().balanceAfterMinor()).isZero();
    }
}
