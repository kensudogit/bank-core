package com.example.bankcore.loan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 保証の種類：保全として認める割合（保証会社は全額、個人保証は半分）。 */
class GuaranteeTypeTest {

    @Test
    void recognitionRates() {
        assertThat(GuaranteeType.GUARANTEE_COMPANY.recognitionBp()).isEqualTo(10_000);
        assertThat(GuaranteeType.INDIVIDUAL.recognitionBp()).isEqualTo(5_000);
        assertThat(GuaranteeType.values()).hasSize(2);
    }
}
