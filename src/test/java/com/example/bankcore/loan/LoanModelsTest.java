package com.example.bankcore.loan;

import com.example.bankcore.loan.LoanModels.Collateral;
import com.example.bankcore.loan.LoanModels.Guarantee;
import com.example.bankcore.loan.LoanModels.InstallmentStatus;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanModels.ScheduledInstallment;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 融資のモデル：保全額（有効な担保・保証だけ）、未払額、状態の判定。 */
class LoanModelsTest {

    @Test
    void collateralSecuredAmountAppliesHaircutOnlyWhileActive() {
        Collateral active = new Collateral(1, 1, CollateralType.REAL_ESTATE, "土地", 10_000_000, 7_000, 1, "ACTIVE",
                OffsetDateTime.now());
        assertThat(active.securedMinor()).isEqualTo(7_000_000);
        Collateral released = new Collateral(1, 1, CollateralType.REAL_ESTATE, "土地", 10_000_000, 7_000, 1, "RELEASED",
                OffsetDateTime.now());
        assertThat(released.securedMinor()).isZero();
    }

    @Test
    void guaranteeSecuredAmountAppliesRecognitionRate() {
        assertThat(new Guarantee(1, 1, GuaranteeType.INDIVIDUAL, "保証人", 2_000_000, 5_000, "ACTIVE").securedMinor())
                .isEqualTo(1_000_000);
        assertThat(new Guarantee(1, 1, GuaranteeType.INDIVIDUAL, "保証人", 2_000_000, 5_000, "RELEASED").securedMinor())
                .isZero();
    }

    @Test
    void installmentUnpaidAmountsAndOpenState() {
        ScheduledInstallment partial = new ScheduledInstallment(1, 1, LocalDate.of(2030, 5, 1), 100_000, 3_000,
                40_000, 3_000, 0, InstallmentStatus.PARTIAL, null);
        assertThat(partial.unpaidPrincipal()).isEqualTo(60_000);
        assertThat(partial.unpaidInterest()).isZero();
        assertThat(partial.open()).isTrue();
        assertThat(new ScheduledInstallment(1, 1, LocalDate.of(2030, 5, 1), 1, 1, 1, 1, 0, InstallmentStatus.PAID,
                LocalDate.of(2030, 5, 1)).open()).isFalse();
        assertThat(new ScheduledInstallment(1, 1, LocalDate.of(2030, 5, 1), 1, 1, 0, 0, 0, InstallmentStatus.CANCELLED,
                null).open()).isFalse();
    }

    @Test
    void onlyActiveAndDelinquentLoansAreOpen() {
        assertThat(LoanStatus.ACTIVE.open()).isTrue();
        assertThat(LoanStatus.DELINQUENT.open()).isTrue();
        assertThat(LoanStatus.PAID_OFF.open()).isFalse();
        assertThat(LoanStatus.WRITTEN_OFF.open()).isFalse();
    }

    @Test
    void enumsMatchStoredValues() {
        assertThat(LoanModels.ApplicationStatus.values()).extracting(Enum::name)
                .containsExactly("SUBMITTED", "SCREENED", "APPROVED", "REJECTED", "DISBURSED");
        assertThat(LoanModels.LedgerKind.values()).extracting(Enum::name)
                .containsExactly("DISBURSEMENT", "REPAYMENT", "PROVISION", "WRITE_OFF");
    }
}
