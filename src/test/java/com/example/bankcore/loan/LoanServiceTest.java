package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.loan.LoanModels.LedgerKind;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanService.LoanDetail;
import com.example.bankcore.loan.LoanService.LoanResult;
import com.example.bankcore.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 融資サービス：実行・返済・自動引落し・照会・シミュレーション。 */
class LoanServiceTest extends LoanTestSupport {

    @Test
    void disburseCreditsAccountAndIsIdempotent() {
        Account a = openAccount("A", 0);
        long appId = approved(a, 1_200_000).application().id();
        LoanResult first = loans.disburse(appId, "disb-1", null);
        assertThat(first.replayed()).isFalse();
        assertThat(first.loan().outstandingMinor()).isEqualTo(1_200_000);
        assertThat(balanceOf(a.id())).isEqualTo(1_200_000);

        LoanResult again = loans.disburse(appId, "disb-1", null);
        assertThat(again.replayed()).isTrue();
        assertThat(balanceOf(a.id())).isEqualTo(1_200_000);
        // 実行済みの申込は別キーでも二度は実行できない
        assertThatThrownBy(() -> loans.disburse(appId, "disb-2", null)).isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void disburseRequiresApproval() {
        long appId = submitted(openAccount("A", 0), 1_200_000).application().id();
        assertThatThrownBy(() -> loans.disburse(appId, key(), null)).isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> loans.disburse(appId, "bad key", null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void repayReducesOutstandingAndRecordsLedger() {
        Account a = openAccount("A", 0);
        Loan loan = disbursed(a, 1_200_000);
        LoanResult r = loans.repay(loan.id(), 200_000, "rep-1", null);
        assertThat(r.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(r.loan().outstandingMinor()).isLessThan(1_200_000);
        assertThat(balanceOf(a.id())).isEqualTo(1_000_000);
        assertThat(loans.ledger(loan.id())).extracting(LoanModels.LedgerEntry::kind)
                .containsExactly(LedgerKind.DISBURSEMENT, LedgerKind.REPAYMENT);

        assertThatThrownBy(() -> loans.repay(loan.id(), 200_001, "rep-1", null))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> loans.repay(loan.id(), 0, key(), null)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void fullRepaymentPaysOffTheLoan() {
        Account a = openAccount("A", 500_000);
        Loan loan = disbursed(a, 1_200_000);
        long payoff = loans.get(loan.id()).payoffMinor();
        LoanResult r = loans.repay(loan.id(), payoff, key(), null);
        assertThat(r.loan().status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(r.loan().outstandingMinor()).isZero();
        assertThatThrownBy(() -> loans.repay(loan.id(), 1, key(), null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void repaymentBeyondFundsIsRejected() {
        Account a = openAccount("A", 0);
        Loan loan = disbursed(a, 1_200_000);
        movements.execute(com.example.bankcore.transaction.MovementCommand.withdrawal(key(), a.id(), 1_150_000, "JPY",
                null, null));
        LoanResult r = loans.repay(loan.id(), 100_000, key(), null);
        assertThat(r.transaction().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(r.loan().outstandingMinor()).isEqualTo(1_200_000);
    }

    @Test
    void getShowsAmountDueOnlyAfterTheDueDate() {
        Loan loan = disbursed(openAccount("A", 0), 1_200_000);
        LoanDetail before = loans.get(loan.id());
        assertThat(before.amountDueMinor()).isZero();
        assertThat(before.installments()).hasSize(12);
        assertThat(before.payoffMinor()).isEqualTo(1_200_000);

        setBusinessDate(INITIAL_BUSINESS_DATE.plusMonths(1));
        LoanDetail due = loans.get(loan.id());
        assertThat(due.amountDueMinor()).isEqualTo(due.installments().getFirst().principalDue()
                + due.installments().getFirst().interestDue());
    }

    @Test
    void autoDebitCollectsDueAmountOncePerDay() {
        Account a = openAccount("A", 0);
        Loan loan = disbursed(a, 1_200_000);
        setBusinessDate(INITIAL_BUSINESS_DATE.plusMonths(1));

        LoanService.AutoDebitReport r = loans.autoDebit(INITIAL_BUSINESS_DATE.plusMonths(1), 4);
        assertThat(r.loansDue()).isEqualTo(1);
        assertThat(r.debited()).isEqualTo(1);
        assertThat(r.shortfall()).isZero();
        assertThat(loans.get(loan.id()).amountDueMinor()).isZero();
        // 同じ業務日付で再実行しても二重に引き落とさない
        assertThat(loans.autoDebit(INITIAL_BUSINESS_DATE.plusMonths(1)).loansDue()).isZero();
    }

    @Test
    void autoDebitWithInsufficientFundsTakesWhatIsAvailable() {
        Account a = openAccount("A", 0);
        Loan loan = disbursed(a, 1_200_000);
        movements.execute(com.example.bankcore.transaction.MovementCommand.withdrawal(key(), a.id(), 1_150_000, "JPY",
                null, null));
        setBusinessDate(INITIAL_BUSINESS_DATE.plusMonths(1));
        LoanService.AutoDebitReport r = loans.autoDebit(INITIAL_BUSINESS_DATE.plusMonths(1), 2);
        assertThat(r.shortfall()).isEqualTo(1);
        assertThat(r.debited()).isEqualTo(1);
        assertThat(balanceOf(a.id())).isZero();
        assertThat(loans.get(loan.id()).amountDueMinor()).isPositive();
    }

    @Test
    void listAndSimulate() {
        Loan loan = disbursed(openAccount("A", 0), 1_200_000);
        assertThat(loans.list(LoanStatus.ACTIVE)).extracting(Loan::id).containsExactly(loan.id());
        assertThat(loans.list(LoanStatus.PAID_OFF)).isEmpty();

        List<LoanMath.Installment> s = loans.simulate(1_000_000, 200, 10, RepaymentMethod.EQUAL_PRINCIPAL);
        assertThat(s).hasSize(10);
        assertThat(s.getFirst().dueDate()).isEqualTo(INITIAL_BUSINESS_DATE.plusMonths(1));
        assertThatThrownBy(() -> loans.simulate(0, 200, 10, RepaymentMethod.EQUAL_PAYMENT))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> loans.simulate(1, 200, 601, RepaymentMethod.EQUAL_PAYMENT))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> loans.get(999_999)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> loans.ledger(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void writeOffValidatesInputAndRequiresDoubtfulClass() {
        Loan loan = disbursed(openAccount("A", 0), 1_200_000);
        assertThatThrownBy(() -> loans.writeOff(loan.id(), " ", "x", key(), null))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> loans.writeOff(loan.id(), "manager1", "回収不能", key(), null))
                .isInstanceOf(BusinessConflictException.class);
    }
}
