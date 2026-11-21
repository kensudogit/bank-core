package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.loan.LoanApplicationService.ApplicationView;
import com.example.bankcore.loan.LoanApplicationService.NewApplication;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

/** 融資のテストの共通部分（申込 → 審査 → 承認 → 実行）。 */
abstract class LoanTestSupport extends AbstractIntegrationTest {

    @Autowired protected LoanApplicationService applications;
    @Autowired protected LoanService loans;

    protected ApplicationView submitted(Account account, long principal) {
        return applications.apply(new NewApplication(account.id(), LoanProduct.BUSINESS, principal, 12,
                RepaymentMethod.EQUAL_PAYMENT, 8_000_000, 0, "運転資金", "teller1"));
    }

    protected ApplicationView approved(Account account, long principal) {
        long id = submitted(account, principal).application().id();
        applications.screen(id);
        return applications.decide(id, true, "manager1", "テスト用の承認", null);
    }

    protected Loan disbursed(Account account, long principal) {
        return loans.disburse(approved(account, principal).application().id(), key(), null).loan();
    }

    protected void setBusinessDate(LocalDate date) {
        jdbc.sql("UPDATE business_calendar SET current_business_date = :d").param("d", date).update();
    }
}
