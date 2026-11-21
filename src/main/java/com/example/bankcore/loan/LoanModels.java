package com.example.bankcore.loan;

import com.example.bankcore.loan.CreditScoring.Recommendation;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 融資の読み取りモデル（各テーブルの 1 行）。 */
public final class LoanModels {

    private LoanModels() {
    }

    /** 申込の状態：申込 → 審査済 → 承認／謝絶 → 実行済 */
    public enum ApplicationStatus { SUBMITTED, SCREENED, APPROVED, REJECTED, DISBURSED }

    /** 融資の状態。延滞中は DELINQUENT（返済で解消すれば ACTIVE に戻る） */
    public enum LoanStatus {
        ACTIVE, DELINQUENT, PAID_OFF, WRITTEN_OFF;

        public boolean open() {
            return this == ACTIVE || this == DELINQUENT;
        }
    }

    public enum InstallmentStatus { SCHEDULED, PARTIAL, PAID, CANCELLED }

    public enum LedgerKind { DISBURSEMENT, REPAYMENT, PROVISION, WRITE_OFF }

    public record LoanApplication(
            long id, long accountId, LoanProduct product, long principalMinor, int termMonths,
            RepaymentMethod repaymentMethod, long annualIncomeMinor, long existingAnnualDebtMinor, String purpose,
            ApplicationStatus status, String submittedBy, OffsetDateTime submittedAt,
            Integer score, String grade, Long dtiBp, Long coverageBp, Integer proposedRateBp,
            Recommendation recommendation, String screeningReasons, OffsetDateTime screenedAt,
            Integer approvedRateBp, String decidedBy, String decisionNote, OffsetDateTime decidedAt) {
    }

    public record Collateral(long id, long applicationId, CollateralType type, String description, long appraisalMinor,
                             int haircutBp, Integer lienRank, String status, OffsetDateTime appraisedAt) {
        /** 保全額（評価額 × 掛目） */
        public long securedMinor() {
            return "ACTIVE".equals(status) ? Math.multiplyExact(appraisalMinor, haircutBp) / 10_000 : 0;
        }
    }

    public record Guarantee(long id, long applicationId, GuaranteeType type, String guarantorName, long coverageMinor,
                            int recognitionBp, String status) {
        /** 保全額（保証額 × 認定割合） */
        public long securedMinor() {
            return "ACTIVE".equals(status) ? Math.multiplyExact(coverageMinor, recognitionBp) / 10_000 : 0;
        }
    }

    public record Loan(
            long id, long applicationId, long accountId, LoanProduct product, String currency,
            long principalMinor, long outstandingMinor, int annualRateBp, int termMonths,
            RepaymentMethod repaymentMethod, String grade, LoanStatus status, int daysPastDue,
            BorrowerClass borrowerClass, long provisionMinor, LocalDate disbursedOn, LocalDate maturityDate,
            LocalDate closedOn, long version) {
    }

    public record ScheduledInstallment(
            long loanId, int installmentNo, LocalDate dueDate, long principalDue, long interestDue,
            long principalPaid, long interestPaid, long lateFeePaid, InstallmentStatus status, LocalDate paidOn) {

        public long unpaidPrincipal() {
            return principalDue - principalPaid;
        }

        public long unpaidInterest() {
            return interestDue - interestPaid;
        }

        public boolean open() {
            return status == InstallmentStatus.SCHEDULED || status == InstallmentStatus.PARTIAL;
        }
    }

    /** 貸付元帳の 1 行。 */
    public record LedgerEntry(
            long transactionId, long loanId, LocalDate businessDate, LedgerKind kind,
            long principalMinor, long prepaidPrincipalMinor, long interestMinor, long lateFeeMinor,
            long provisionChangeMinor, long outstandingAfterMinor, long provisionAfterMinor, String note,
            OffsetDateTime postedAt) {
    }
}
