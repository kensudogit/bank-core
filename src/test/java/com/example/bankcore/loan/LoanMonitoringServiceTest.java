package com.example.bankcore.loan;

import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanMonitoringService.MonitoringReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 延滞判定と引当：区分の変化に応じて引当を積み増し、同じ日には 1 回だけ記帳する。 */
class LoanMonitoringServiceTest extends LoanTestSupport {

    @Autowired LoanMonitoringService monitoring;

    @Test
    void noLoansNothingToDo() {
        MonitoringReport r = monitoring.run();
        assertThat(r.loansChecked()).isZero();
        assertThat(r.businessDate()).isEqualTo(INITIAL_BUSINESS_DATE);
    }

    @Test
    void performingLoanGetsGeneralProvisionOnce() {
        Loan loan = disbursed(openAccount("A", 0), 2_000_000);
        MonitoringReport r = monitoring.run();
        assertThat(r.loansChecked()).isEqualTo(1);
        assertThat(r.delinquent()).isZero();
        assertThat(r.provisionsPosted()).isEqualTo(1);
        assertThat(r.totalProvisionMinor()).isEqualTo(10_000);   // 正常先 0.5%
        assertThat(monitoring.run().provisionsPosted()).isZero();
        assertThat(loans.get(loan.id()).loan().borrowerClass()).isEqualTo(BorrowerClass.NORMAL);
    }

    @Test
    void overdueLoanBecomesDelinquentWatchClass() {
        Loan loan = disbursed(openAccount("A", 0), 2_000_000);
        LocalDate date = INITIAL_BUSINESS_DATE.plusMonths(1).plusDays(30);
        setBusinessDate(date);
        MonitoringReport r = monitoring.run(date);
        assertThat(r.delinquent()).isEqualTo(1);
        assertThat(r.nonPerforming()).isZero();
        Loan l = loans.get(loan.id()).loan();
        assertThat(l.status()).isEqualTo(LoanStatus.DELINQUENT);
        assertThat(l.daysPastDue()).isEqualTo(30);
        assertThat(l.borrowerClass()).isEqualTo(BorrowerClass.WATCH);
        assertThat(l.provisionMinor()).isEqualTo(60_000);         // 要注意先 3%
    }

    @Test
    void curedLoanReturnsToActive() {
        var account = openAccount("A", 0);
        Loan loan = disbursed(account, 2_000_000);
        LocalDate date = INITIAL_BUSINESS_DATE.plusMonths(1).plusDays(10);
        setBusinessDate(date);
        monitoring.run();
        loans.repay(loan.id(), loans.get(loan.id()).amountDueMinor(), key(), null);
        setBusinessDate(date.plusDays(1));
        monitoring.run();
        Loan l = loans.get(loan.id()).loan();
        assertThat(l.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(l.daysPastDue()).isZero();
        assertThat(l.borrowerClass()).isEqualTo(BorrowerClass.NORMAL);
    }
}
