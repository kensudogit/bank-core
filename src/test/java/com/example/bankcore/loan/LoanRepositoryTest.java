package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.loan.LoanModels.ApplicationStatus;
import com.example.bankcore.loan.LoanModels.InstallmentStatus;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 融資リポジトリ：申込・担保・融資・返済予定表の保存と照会。 */
class LoanRepositoryTest extends LoanTestSupport {

    @Autowired LoanRepository repo;

    @Test
    void applicationRoundTrip() {
        Account a = openAccount("A", 0);
        long id = repo.insertApplication(a.id(), LoanProduct.CONSUMER, 500_000, 24, RepaymentMethod.EQUAL_PRINCIPAL,
                4_000_000, 100_000, "教育資金", "teller9");
        var app = repo.findApplication(id).orElseThrow();
        assertThat(app.product()).isEqualTo(LoanProduct.CONSUMER);
        assertThat(app.repaymentMethod()).isEqualTo(RepaymentMethod.EQUAL_PRINCIPAL);
        assertThat(app.status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(app.purpose()).isEqualTo("教育資金");
        assertThat(repo.listApplications()).hasSize(1);
        assertThat(repo.findApplication(id + 1)).isEmpty();
    }

    @Test
    void securedAmountSumsActiveCollateralAndGuaranteesAndReleaseClearsIt() {
        Account a = openAccount("A", 0);
        long id = repo.insertApplication(a.id(), LoanProduct.BUSINESS, 1_000_000, 12, RepaymentMethod.EQUAL_PAYMENT,
                1, 0, null, "t");
        long c = repo.insertCollateral(id, CollateralType.REAL_ESTATE, "土地", 1_000_000, 1);
        repo.insertGuarantee(id, GuaranteeType.INDIVIDUAL, "保証人", 400_000);
        assertThat(repo.securedMinor(id)).isEqualTo(700_000 + 200_000);
        assertThat(repo.collaterals(id)).singleElement().satisfies(x -> assertThat(x.haircutBp()).isEqualTo(7_000));
        assertThat(repo.guarantees(id)).hasSize(1);

        repo.revalueCollateral(c, 2_000_000);
        assertThat(repo.findCollateral(c).orElseThrow().appraisalMinor()).isEqualTo(2_000_000);
        repo.releaseSecurity(id);
        assertThat(repo.securedMinor(id)).isZero();
    }

    @Test
    void loanInstallmentsAndQueries() {
        Account a = openAccount("A", 0);
        Loan loan = disbursed(a, 1_200_000);
        assertThat(repo.findLoan(loan.id())).isPresent();
        assertThat(repo.findLoanByApplication(loan.applicationId())).map(Loan::id).contains(loan.id());
        assertThat(repo.listLoans(null)).hasSize(1);
        assertThat(repo.openLoanIds()).containsExactly(loan.id());
        assertThat(repo.countDelinquent(a.id())).isZero();
        assertThat(repo.installments(loan.id())).hasSize(12)
                .allSatisfy(i -> assertThat(i.status()).isEqualTo(InstallmentStatus.SCHEDULED));
        assertThat(repo.ledger(loan.id())).hasSize(1);
    }

    @Test
    void autoDebitCandidatesAreDueLoansGroupedByAccount() {
        Account a = openAccount("A", 0);
        Account b = openAccount("B", 0);
        Loan l1 = disbursed(a, 1_200_000);
        Loan l2 = disbursed(a, 1_000_000);
        Loan l3 = disbursed(b, 1_000_000);
        String prefix = "loan-autodebit-" + INITIAL_BUSINESS_DATE + "-";
        // 期日前は対象なし
        assertThat(repo.autoDebitCandidatesByAccount(INITIAL_BUSINESS_DATE, prefix)).isEmpty();

        Map<Long, List<Long>> due = repo.autoDebitCandidatesByAccount(INITIAL_BUSINESS_DATE.plusMonths(1),
                "loan-autodebit-" + INITIAL_BUSINESS_DATE.plusMonths(1) + "-");
        assertThat(due).containsOnlyKeys(a.id(), b.id());
        assertThat(due.get(a.id())).containsExactly(l1.id(), l2.id());
        assertThat(due.get(b.id())).containsExactly(l3.id());
    }

    @Test
    void stateUpdates() {
        Loan loan = disbursed(openAccount("A", 0), 1_200_000);
        repo.updateProvision(loan.id(), 12_345);
        assertThat(repo.findLoan(loan.id()).orElseThrow().provisionMinor()).isEqualTo(12_345);
        repo.cancelOpenInstallments(loan.id());
        assertThat(repo.installments(loan.id()))
                .allSatisfy(i -> assertThat(i.status()).isEqualTo(InstallmentStatus.CANCELLED));
        assertThat(repo.listLoans(LoanStatus.ACTIVE)).hasSize(1);
    }
}
