package com.example.bankcore.batch;

import com.example.bankcore.account.Account;
import com.example.bankcore.batch.DailyClosingService.ClosingReport;
import com.example.bankcore.batch.DailyClosingService.ClosingStatus;
import com.example.bankcore.batch.DailyClosingService.Step;
import com.example.bankcore.batch.DailyClosingService.StepRecord;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.loan.CollateralType;
import com.example.bankcore.loan.LoanApplicationService;
import com.example.bankcore.loan.LoanApplicationService.NewApplication;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanProduct;
import com.example.bankcore.loan.LoanService;
import com.example.bankcore.loan.RepaymentMethod;
import com.example.bankcore.payment.PaymentService;
import com.example.bankcore.payment.PaymentService.BillingItem;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 日次締めの運用統制：照合ゲート・承認・差戻し・修復・障害からの再開・ジョブの終了コード。実 PostgreSQL。 */
@TestPropertySource(properties = "bank.closing.approval-required=true")
class ClosingControlIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DailyClosingService closing;

    @Autowired
    BatchJobs jobs;

    @Autowired
    BusinessCalendar calendar;

    @Autowired
    PaymentService payments;

    @Autowired
    LoanApplicationService applications;

    @Autowired
    LoanService loans;

    @Autowired
    MockMvc mvc;

    private static StepRecord step(ClosingStatus s, Step step) {
        return s.steps().stream().filter(r -> r.stepName().equals(step.name())).findFirst().orElseThrow();
    }

    @Test
    void closingWaitsForApprovalByADifferentPersonBeforeAdvancingTheDate() {
        openAccount("Alice", 3_650_000);
        ClosingReport r = closing.run("operator1");
        assertThat(r.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(r.reconciliationOk()).isTrue();
        assertThat(r.dateAdvanced()).isFalse();
        assertThat(r.exitCode()).isZero();
        assertThat(r.steps()).extracting(StepRecord::stepName)
                .containsExactly("INTEREST_POSTING", "DIRECT_DEBIT", "LOAN_AUTO_DEBIT", "LOAN_MONITORING",
                        "SECURITIES_INCOME", "SECURITIES_VALUATION", "TRUST_PAYOUT", "MERCHANT_SETTLEMENT", "AML_SCAN",
                        "RECONCILIATION");
        assertThat(r.steps()).allSatisfy(s -> assertThat(s.status()).isEqualTo("COMPLETED"));
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE);
        assertThat(jobs.run("closing-status", "JP1").exitCode()).isEqualTo(BatchJobs.AWAITING_APPROVAL);

        // 職務分離：締めの実行者は承認できない
        assertThatThrownBy(() -> closing.approve(INITIAL_BUSINESS_DATE, "operator1"))
                .isInstanceOf(BusinessConflictException.class);

        ClosingStatus approved = closing.approve(INITIAL_BUSINESS_DATE, "supervisor1");
        assertThat(approved.closing().status()).isEqualTo("COMPLETED");
        assertThat(approved.closing().approvedBy()).isEqualTo("supervisor1");
        assertThat(approved.closing().reconciliationOk()).isTrue();
        assertThat(approved.closing().journalCount()).isEqualTo(approved.closing().transactionCount());
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
        assertThat(approved.audit()).extracting(DailyClosingService.AuditRecord::action)
                .containsExactly("RUN", "AWAITING_APPROVAL", "APPROVE", "COMPLETED");
        assertThat(jobs.run("closing-status", "JP1").exitCode()).isEqualTo(BatchJobs.OK);
    }

    @Test
    void reconciliationMismatchBlocksTheClosingUntilRepairedAndRerun() {
        Account a = openAccount("Alice", 100_000);
        // 障害注入：仕訳を通さずに残高を書き換える（元帳と残高が食い違う）
        jdbc.sql("UPDATE accounts SET posted_balance_minor = posted_balance_minor + 1 WHERE id = :id")
                .param("id", a.id()).update();

        ClosingReport failed = closing.run("operator1");
        assertThat(failed.status()).isEqualTo("RECON_FAILED");
        assertThat(failed.reconciliationOk()).isFalse();
        assertThat(failed.exitCode()).isEqualTo(BatchJobs.NEEDS_REPAIR);
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE);
        ClosingStatus s = closing.status(INITIAL_BUSINESS_DATE);
        assertThat(s.closing().reconciliation()).contains("預金残高の不一致 1");
        assertThat(step(s, Step.RECONCILIATION).status()).isEqualTo("FAILED");
        assertThatThrownBy(() -> closing.approve(INITIAL_BUSINESS_DATE, "supervisor1"))
                .isInstanceOf(BusinessConflictException.class);
        assertThat(jobs.run("reconciliation", "JP1").exitCode()).isEqualTo(BatchJobs.NEEDS_REPAIR);

        // 修復して記録し、再実行する。完了済みのステップは飛ばし、照合だけをやり直す
        jdbc.sql("UPDATE accounts SET posted_balance_minor = posted_balance_minor - 1 WHERE id = :id")
                .param("id", a.id()).update();
        closing.recordRepair(INITIAL_BUSINESS_DATE, "operator2", "口座残高を元帳に合わせて修正（障害票 #42）");
        ClosingReport rerun = closing.run("operator2");
        assertThat(rerun.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(rerun.interestPostedThisRun()).isZero();
        ClosingStatus after = closing.status(INITIAL_BUSINESS_DATE);
        assertThat(step(after, Step.INTEREST_POSTING).attempts()).isEqualTo(1);
        assertThat(step(after, Step.RECONCILIATION).attempts()).isEqualTo(2);
        assertThat(after.audit()).extracting(DailyClosingService.AuditRecord::action)
                .contains("RECON_FAILED", "REPAIR");
        closing.approve(INITIAL_BUSINESS_DATE, "supervisor1");
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
    }

    @Test
    void rejectedClosingCanBeRerunAndApproved() {
        openAccount("Alice", 10_000);
        closing.run("operator1");
        ClosingStatus rejected = closing.reject(INITIAL_BUSINESS_DATE, "supervisor1", "利息計上額の確認が必要");
        assertThat(rejected.closing().status()).isEqualTo("REJECTED");
        assertThat(jobs.run("closing-status", "JP1").exitCode()).isEqualTo(BatchJobs.NEEDS_REPAIR);
        assertThat(closing.run("operator1").status()).isEqualTo("AWAITING_APPROVAL");
        closing.approve(INITIAL_BUSINESS_DATE, "supervisor1");
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
    }

    @Test
    void failedStepIsResumedWithoutRepeatingCompletedSteps() {
        Account utility = openAccount("Utility", 0);
        Account payer = openAccount("Payer", 50_000);
        payments.registerBiller("UTIL01", "Utility", utility.id());
        payments.registerMandate(payer.id(), "UTIL01", "P-1");
        payments.importBilling(List.of(new BillingItem("UTIL01", "P-1", 7_000, INITIAL_BUSINESS_DATE)));
        // 障害注入：口座振替の記帳だけ失敗させる
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_direct_debit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.type = 'DIRECT_DEBIT' THEN RAISE EXCEPTION 'injected debit failure'; END IF;
                  RETURN NEW;
                END; $$""").update();
        jdbc.sql("CREATE TRIGGER trg_fail_dd BEFORE INSERT ON transactions FOR EACH ROW EXECUTE FUNCTION fail_direct_debit()")
                .update();
        try {
            BatchJobs.JobResult r = jobs.run("daily-closing", "JP1");
            assertThat(r.exitCode()).isEqualTo(BatchJobs.FAILED);
            assertThat(r.message()).contains("injected debit failure");
        } finally {
            jdbc.sql("DROP TRIGGER trg_fail_dd ON transactions").update();
            jdbc.sql("DROP FUNCTION fail_direct_debit()").update();
        }
        ClosingStatus s = closing.status(INITIAL_BUSINESS_DATE);
        assertThat(s.closing().status()).isEqualTo("RUNNING");
        assertThat(s.closing().lastError()).contains("injected debit failure");
        assertThat(step(s, Step.INTEREST_POSTING).status()).isEqualTo("COMPLETED");
        assertThat(step(s, Step.DIRECT_DEBIT).status()).isEqualTo("FAILED");
        long interestTx = count("transactions WHERE type = 'INTEREST'");

        BatchJobs.JobResult resumed = jobs.run("daily-closing", "JP1");
        assertThat(resumed.exitCode()).isZero();
        ClosingStatus after = closing.status(INITIAL_BUSINESS_DATE);
        assertThat(step(after, Step.INTEREST_POSTING).attempts()).isEqualTo(1);   // 確定済みのステップは飛ばす
        assertThat(step(after, Step.DIRECT_DEBIT).attempts()).isEqualTo(2);
        assertThat(count("transactions WHERE type = 'INTEREST'")).isEqualTo(interestTx);
        assertThat(balanceOf(utility.id())).isEqualTo(7_000);

        // 完了済みのステップを明示的に再実行させても、冪等キーで二重計上しない
        closing.resetStep(INITIAL_BUSINESS_DATE, Step.DIRECT_DEBIT, "operator2");
        closing.run("operator2");
        assertThat(step(closing.status(INITIAL_BUSINESS_DATE), Step.DIRECT_DEBIT).attempts()).isEqualTo(3);
        assertThat(balanceOf(utility.id())).isEqualTo(7_000);
        assertThat(balanceOf(payer.id())).isEqualTo(50_000 - 7_000 + 1);   // + 当日の利息 1 円
    }

    @Test
    void closingAutoDebitsLoanInstallmentsOnce() {
        Account taro = openAccount("Taro", 0);
        var app = applications.apply(new NewApplication(taro.id(), LoanProduct.BUSINESS, 1_200_000, 12,
                RepaymentMethod.EQUAL_PAYMENT, 8_000_000, 0, null, "teller1"));
        applications.addCollateral(app.application().id(), CollateralType.DEPOSIT, "定期預金", 2_000_000, null);
        applications.screen(app.application().id());
        applications.decide(app.application().id(), true, "manager1", "承認", null);
        Loan loan = loans.disburse(app.application().id(), key(), null).loan();
        jdbc.sql("UPDATE business_calendar SET current_business_date = :d")
                .param("d", INITIAL_BUSINESS_DATE.plusMonths(1)).update();
        long due = loans.get(loan.id()).amountDueMinor();
        long before = balanceOf(taro.id());

        ClosingReport r = closing.run("operator1");
        assertThat(r.reconciliationOk()).isTrue();
        assertThat(step(closing.status(r.businessDate()), Step.LOAN_AUTO_DEBIT).processed()).isEqualTo(1);
        assertThat(balanceOf(taro.id())).isLessThanOrEqualTo(before - due + 1_000);   // 利息計上分の誤差を許容
        assertThat(loans.get(loan.id()).amountDueMinor()).isZero();
        long repayments = count("transactions WHERE type = 'LOAN_REPAYMENT'");

        closing.resetStep(r.businessDate(), Step.LOAN_AUTO_DEBIT, "operator2");
        closing.run("operator2");
        assertThat(count("transactions WHERE type = 'LOAN_REPAYMENT'")).isEqualTo(repayments);
    }

    @Test
    void jobEndpointReturnsExitCodesForTheScheduler() throws Exception {
        openAccount("Alice", 1_000);
        mvc.perform(post("/api/batch/jobs/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0));
        mvc.perform(post("/api/batch/jobs/daily-closing").param("operator", "JP1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        mvc.perform(post("/api/batch/jobs/no-such-job"))
                .andExpect(jsonPath("$.exitCode").value(BatchJobs.INVALID));
    }
}
