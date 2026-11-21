package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.batch.DailyClosingService;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.ledger.JournalRepository.PostedLine;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.loan.CreditScoring.Recommendation;
import com.example.bankcore.loan.LoanApplicationService.ApplicationView;
import com.example.bankcore.loan.LoanApplicationService.NewApplication;
import com.example.bankcore.loan.LoanModels.ApplicationStatus;
import com.example.bankcore.loan.LoanModels.InstallmentStatus;
import com.example.bankcore.loan.LoanModels.LedgerKind;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanModels.ScheduledInstallment;
import com.example.bankcore.loan.LoanMonitoringService.MonitoringReport;
import com.example.bankcore.loan.LoanService.LoanDetail;
import com.example.bankcore.loan.LoanService.LoanResult;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 融資の申込から実行・返済・延滞・引当・償却・信用リスクまで（実 PostgreSQL）。 */
class LendingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    LoanApplicationService applications;

    @Autowired
    LoanService loans;

    @Autowired
    LoanMonitoringService monitoring;

    @Autowired
    CreditRiskService risk;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    JournalRepository journal;

    @Autowired
    DailyClosingService closing;

    private void setBusinessDate(LocalDate date) {
        jdbc.sql("UPDATE business_calendar SET current_business_date = :d").param("d", date).update();
    }

    /** 申込 → （担保）→ 審査 → 承認。謝絶推奨でも理由付きで承認する（テストの前提を作るため）。 */
    private ApplicationView approved(Account account, long principal, long realEstateAppraisal) {
        ApplicationView app = applications.apply(new NewApplication(account.id(), LoanProduct.BUSINESS, principal, 12,
                RepaymentMethod.EQUAL_PAYMENT, 8_000_000, 0, "運転資金", "teller1"));
        long id = app.application().id();
        if (realEstateAppraisal > 0) {
            applications.addCollateral(id, CollateralType.REAL_ESTATE, "本社土地建物", realEstateAppraisal, 1);
        }
        applications.screen(id);
        return applications.decide(id, true, "manager1", "テスト用の承認", null);
    }

    private Loan disbursed(Account account, long principal, long realEstateAppraisal) {
        ApplicationView app = approved(account, principal, realEstateAppraisal);
        LoanResult r = loans.disburse(app.application().id(), key(), null);
        assertThat(r.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        return r.loan();
    }

    @Test
    void screeningDecisionAndDisbursementPostLoanAgainstDeposit() {
        Account taro = openAccount("Taro", 2_000_000);
        ApplicationView app = applications.apply(new NewApplication(taro.id(), LoanProduct.BUSINESS, 3_000_000, 12,
                RepaymentMethod.EQUAL_PAYMENT, 8_000_000, 0, "設備資金", "teller1"));
        long appId = app.application().id();
        applications.addCollateral(appId, CollateralType.REAL_ESTATE, "工場", 5_000_000, 1);
        applications.addGuarantee(appId, GuaranteeType.INDIVIDUAL, "代表者", 1_000_000);

        ApplicationView screened = applications.screen(appId);
        assertThat(screened.application().status()).isEqualTo(ApplicationStatus.SCREENED);
        assertThat(screened.securedMinor()).isEqualTo(3_500_000 + 500_000);   // 不動産 70% + 個人保証 50%
        assertThat(screened.application().recommendation()).isEqualTo(Recommendation.APPROVE);
        assertThat(screened.application().coverageBp()).isGreaterThanOrEqualTo(10_000);

        // 職務分離：受付者は決裁できない。実行は承認後のみ
        assertThatThrownBy(() -> applications.decide(appId, true, "teller1", null, null))
                .isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> loans.disburse(appId, key(), null)).isInstanceOf(BusinessConflictException.class);
        ApplicationView decided = applications.decide(appId, true, "manager1", null, null);
        assertThat(decided.application().approvedRateBp()).isEqualTo(screened.application().proposedRateBp());

        String k = key();
        LoanResult first = loans.disburse(appId, k, "corr-1");
        Loan loan = first.loan();
        assertThat(loan.outstandingMinor()).isEqualTo(3_000_000);
        assertThat(loan.status()).isEqualTo(LoanStatus.ACTIVE);
        assertThat(loan.maturityDate()).isEqualTo(INITIAL_BUSINESS_DATE.plusMonths(12));
        assertThat(balanceOf(taro.id())).isEqualTo(5_000_000);

        List<ScheduledInstallment> schedule = loans.get(loan.id()).installments();
        assertThat(schedule).hasSize(12);
        assertThat(schedule.stream().mapToLong(ScheduledInstallment::principalDue).sum()).isEqualTo(3_000_000);

        // 仕訳：借方 貸出金（融資）／ 貸方 顧客預金（口座）
        List<PostedLine> lines = journal.linesForTransaction(first.transaction().id());
        assertThat(lines).extracting(PostedLine::glCode).containsExactly(GlCode.LOANS_RECEIVABLE, GlCode.CUSTOMER_DEPOSITS);
        assertThat(lines.getFirst().loanId()).isEqualTo(loan.id());
        assertThat(lines.getFirst().debitMinor()).isEqualTo(3_000_000);

        // 同じ冪等キーの再送は同じ結果。二重に実行されない
        LoanResult again = loans.disburse(appId, k, "corr-1");
        assertThat(again.replayed()).isTrue();
        assertThat(again.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(balanceOf(taro.id())).isEqualTo(5_000_000);
        // 別のキーでも 2 回目の実行はできない（申込は DISBURSED）
        assertThatThrownBy(() -> loans.disburse(appId, key(), null)).isInstanceOf(BusinessConflictException.class);

        assertThat(loans.ledger(loan.id())).singleElement()
                .satisfies(e -> assertThat(e.kind()).isEqualTo(LedgerKind.DISBURSEMENT));
        assertThat(count("outbox_events WHERE event_type = 'LoanDisbursed'")).isEqualTo(1);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void repaymentAllocatesInterestAndPrincipalAndPrepaymentReamortizes() {
        Account taro = openAccount("Taro", 2_000_000);
        Loan loan = disbursed(taro, 3_000_000, 5_000_000);

        // 期日前の返済はすべて繰上返済（元金）になり、残りの回が再計算される
        LoanResult pre = loans.repay(loan.id(), 100_000, key(), null);
        assertThat(pre.loan().outstandingMinor()).isEqualTo(2_900_000);
        assertThat(loans.get(loan.id()).installments().stream().mapToLong(ScheduledInstallment::principalDue).sum())
                .isEqualTo(2_900_000);

        // 1 回目の期日：請求額ちょうどを返済 → 1 回目が完済、利息は収益に計上
        setBusinessDate(INITIAL_BUSINESS_DATE.plusMonths(1));
        LoanDetail detail = loans.get(loan.id());
        ScheduledInstallment first = detail.installments().getFirst();
        assertThat(detail.amountDueMinor()).isEqualTo(first.principalDue() + first.interestDue());
        long before = balanceOf(taro.id());
        LoanResult paid = loans.repay(loan.id(), detail.amountDueMinor(), key(), null);
        assertThat(paid.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(taro.id())).isEqualTo(before - detail.amountDueMinor());
        assertThat(loans.get(loan.id()).installments().getFirst().status()).isEqualTo(InstallmentStatus.PAID);
        assertThat(journal.linesForTransaction(paid.transaction().id()))
                .anySatisfy(l -> {
                    assertThat(l.glCode()).isEqualTo(GlCode.LOAN_INTEREST_INCOME);
                    assertThat(l.creditMinor()).isEqualTo(first.interestDue());
                });

        // 完済額を超える返済は REJECTED として記録され、何も動かない
        long payoff = loans.get(loan.id()).payoffMinor();
        LoanResult tooMuch = loans.repay(loan.id(), payoff + 1, key(), null);
        assertThat(tooMuch.transaction().rejectReason()).isEqualTo(RejectReason.AMOUNT_EXCEEDS_LOAN_BALANCE);

        // 完済：融資は PAID_OFF、担保は解除
        LoanResult off = loans.repay(loan.id(), payoff, key(), null);
        assertThat(off.loan().status()).isEqualTo(LoanStatus.PAID_OFF);
        assertThat(off.loan().outstandingMinor()).isZero();
        assertThat(applications.get(loan.applicationId()).collaterals()).allSatisfy(c -> assertThat(c.status()).isEqualTo("RELEASED"));
        assertThat(loans.get(loan.id()).installments()).noneMatch(ScheduledInstallment::open);
        assertThatThrownBy(() -> loans.repay(loan.id(), 1, key(), null)).isInstanceOf(BusinessConflictException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void insufficientFundsIsRejectedAndConcurrentRepaymentsCannotOverdraw() throws Exception {
        Account hanako = openAccount("Hanako", 0);
        Loan loan = disbursed(hanako, 1_000_000, 0);           // 口座残高 1,000,000
        List<LoanResult> results = Concurrently.run(2, i -> loans.repay(loan.id(), 600_000, key(), null));
        assertThat(results).filteredOn(r -> r.transaction().status() == TransactionStatus.COMPLETED).hasSize(1);
        assertThat(results).filteredOn(r -> r.transaction().rejectReason() == RejectReason.INSUFFICIENT_FUNDS).hasSize(1);
        assertThat(balanceOf(hanako.id())).isEqualTo(400_000);
        assertThat(loans.get(loan.id()).loan().outstandingMinor()).isEqualTo(400_000);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void delinquencyRaisesProvisionOncePerDayAndWriteOffUsesTheAllowance() {
        Account jiro = openAccount("Jiro", 0);
        Loan loan = disbursed(jiro, 2_000_000, 0);                     // 無担保
        LocalDate firstDue = INITIAL_BUSINESS_DATE.plusMonths(1);

        // 期日前：正常先（残高の 0.5% を一般引当）
        MonitoringReport normal = monitoring.run();
        assertThat(normal.provisionsPosted()).isEqualTo(1);
        assertThat(loans.get(loan.id()).loan().provisionMinor()).isEqualTo(10_000);

        // 1 回目の期日から 100 日延滞 → 要管理先（不良債権）、引当 15%
        setBusinessDate(firstDue.plusDays(100));
        MonitoringReport r = monitoring.run();
        Loan l = loans.get(loan.id()).loan();
        assertThat(l.status()).isEqualTo(LoanStatus.DELINQUENT);
        assertThat(l.daysPastDue()).isEqualTo(100);
        assertThat(l.borrowerClass()).isEqualTo(BorrowerClass.SPECIAL_ATTENTION);
        assertThat(l.provisionMinor()).isEqualTo(300_000);
        assertThat(r.nonPerforming()).isEqualTo(1);
        // 同じ業務日付で再実行しても引当は二重に記帳されない
        assertThat(monitoring.run().provisionsPosted()).isZero();
        assertThat(count("transactions WHERE type = 'LOAN_PROVISION'")).isEqualTo(2);

        // 要管理先は償却できない
        assertThatThrownBy(() -> loans.writeOff(loan.id(), "manager1", "回収不能", key(), null))
                .isInstanceOf(BusinessConflictException.class);

        // 200 日延滞 → 破綻懸念先、非保全部分の 70% を個別引当
        setBusinessDate(firstDue.plusDays(200));
        monitoring.run();
        l = loans.get(loan.id()).loan();
        assertThat(l.borrowerClass()).isEqualTo(BorrowerClass.DOUBTFUL);
        assertThat(l.provisionMinor()).isEqualTo(1_400_000);

        // 延滞中の返済には遅延損害金がかかる
        LoanDetail d = loans.get(loan.id());
        long unpaidDue = d.installments().stream().filter(i -> !i.dueDate().isAfter(d.businessDate()))
                .mapToLong(i -> i.principalDue() + i.interestDue()).sum();
        assertThat(d.amountDueMinor()).isGreaterThan(unpaidDue);

        // 償却：引当で賄える 1,400,000 は貸倒引当金の取崩し、残り 600,000 は貸倒損失
        LoanResult w = loans.writeOff(loan.id(), "manager1", "破産手続開始", key(), null);
        assertThat(w.loan().status()).isEqualTo(LoanStatus.WRITTEN_OFF);
        assertThat(w.loan().outstandingMinor()).isZero();
        assertThat(w.loan().provisionMinor()).isZero();
        List<PostedLine> lines = journal.linesForTransaction(w.transaction().id());
        assertThat(lines).anySatisfy(x -> {
            assertThat(x.glCode()).isEqualTo(GlCode.LOAN_LOSS_ALLOWANCE);
            assertThat(x.debitMinor()).isEqualTo(1_400_000);
        });
        assertThat(lines).anySatisfy(x -> {
            assertThat(x.glCode()).isEqualTo(GlCode.LOAN_LOSS_EXPENSE);
            assertThat(x.debitMinor()).isEqualTo(600_000);
        });
        assertThat(loans.ledger(loan.id())).extracting(LoanModels.LedgerEntry::kind)
                .containsExactly(LedgerKind.DISBURSEMENT, LedgerKind.PROVISION, LedgerKind.PROVISION,
                        LedgerKind.PROVISION, LedgerKind.WRITE_OFF);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void dailyClosingRunsLoanMonitoringAndLoanTransactionsCannotBeReversed() {
        Account saburo = openAccount("Saburo", 0);
        Loan loan = disbursed(saburo, 1_000_000, 0);
        // 貸付金を全額引き出しておく（締めの自動引落しで返済できず延滞になる状況を作る）
        movements.execute(com.example.bankcore.transaction.MovementCommand.withdrawal(key(), saburo.id(), 1_000_000,
                "JPY", null, null));
        setBusinessDate(INITIAL_BUSINESS_DATE.plusMonths(1).plusDays(10));
        closing.run();
        Loan l = loans.get(loan.id()).loan();
        assertThat(l.borrowerClass()).isEqualTo(BorrowerClass.WATCH);
        assertThat(l.provisionMinor()).isEqualTo(30_000);    // 要注意先 3%
        long disbursementTx = loans.ledger(loan.id()).getFirst().transactionId();
        assertThatThrownBy(() -> movements.reverse(key(), disbursementTx, null))
                .isInstanceOf(BusinessConflictException.class);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void creditRiskReportShowsExpectedLossNplAndStress() {
        Account a = openAccount("A社", 0);
        Account b = openAccount("B社", 0);
        Loan secured = disbursed(a, 3_000_000, 5_000_000);
        Loan unsecured = disbursed(b, 1_000_000, 0);
        setBusinessDate(INITIAL_BUSINESS_DATE.plusMonths(1).plusDays(100));
        monitoring.run();   // 両方とも 100 日延滞 → 要管理先

        CreditRiskService.RiskReport rep = risk.report(200, 30);
        assertThat(rep.loans()).isEqualTo(2);
        assertThat(rep.outstandingMinor()).isEqualTo(4_000_000);
        assertThat(rep.nonPerformingMinor()).isEqualTo(4_000_000);
        assertThat(rep.nplRatioBp()).isEqualTo(10_000);
        assertThat(rep.expectedLossMinor()).isPositive();
        assertThat(rep.stressedExpectedLossMinor()).isGreaterThan(rep.expectedLossMinor());
        assertThat(rep.topExposures().getFirst().borrower()).isEqualTo("A社");
        assertThat(rep.topExposures().getFirst().shareBp()).isEqualTo(7_500);
        assertThat(rep.concentrationHhi()).isEqualTo(7_500 * 7_500 / 10_000 + 2_500 * 2_500 / 10_000);
        assertThat(secured.id()).isNotEqualTo(unsecured.id());
    }
}
