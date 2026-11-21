package com.example.bankcore.batch;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountStatus;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 日次締め（利息計上・業務日付更新）の再実行安全性のテスト。年利は既定の 100bp（1%）。 */
class DailyClosingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DailyClosingService closing;

    @Autowired
    BusinessCalendar calendar;

    @Autowired
    ReconciliationService reconciliation;

    private long interestPostingsFor(long accountId) {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE type = 'INTEREST' AND status = 'COMPLETED' "
                        + "AND target_account_id = :a")
                .param("a", accountId).query(Long.class).single();
    }

    @Test
    void dailyInterestIsTruncated() {
        assertThat(DailyClosingService.dailyInterest(3_650_000, 100)).isEqualTo(100);
        assertThat(DailyClosingService.dailyInterest(36_499, 100)).isZero();
        assertThat(DailyClosingService.dailyInterest(-1_000, 100)).isZero();
    }

    @Test
    void closingPostsInterestUsesBusinessDateAndAdvancesIt() {
        Account rich = openAccount("Rich", 3_650_000);  // 日額 100
        Account small = openAccount("Small", 1_000);    // 日額 0（計上なし）
        Account frozen = openAccount("Frozen", 3_650_000);
        accountService.changeStatus(frozen.id(), AccountStatus.FROZEN);

        var report = closing.run();

        assertThat(report.businessDate()).isEqualTo(INITIAL_BUSINESS_DATE);
        assertThat(report.dateAdvanced()).isTrue();
        assertThat(report.interestPostedThisRun()).isEqualTo(1);
        assertThat(report.zeroInterest()).isEqualTo(1);
        assertThat(balanceOf(rich.id())).isEqualTo(3_650_100);
        assertThat(balanceOf(small.id())).isEqualTo(1_000);
        assertThat(balanceOf(frozen.id())).isEqualTo(3_650_000);
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));

        // 利息仕訳は締め対象日の業務日付で記帳される：借方 支払利息 / 貸方 普通預金
        assertThat(jdbc.sql("""
                        SELECT e.business_date FROM journal_entries e
                          JOIN transactions t ON t.id = e.transaction_id WHERE t.type = 'INTEREST'""")
                .query(java.time.LocalDate.class).single()).isEqualTo(INITIAL_BUSINESS_DATE);
        assertThat(reconciliation.run().trialBalance()).anySatisfy(r -> {
            assertThat(r.glCode()).isEqualTo("5000");
            assertThat(r.debitMinor()).isEqualTo(100);
        });
        assertThat(reconciliation.run().ok()).isTrue();
        assertThat(closing.history()).singleElement().satisfies(h -> {
            assertThat(h.status()).isEqualTo("COMPLETED");
            assertThat(h.interestPostings()).isEqualTo(1);
        });
    }

    @Test
    void nextClosingProcessesTheNextDayOnly() {
        Account rich = openAccount("Rich", 3_650_000);

        closing.run();
        closing.run();

        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(2));
        assertThat(interestPostingsFor(rich.id())).isEqualTo(2);
        assertThat(closing.history()).hasSize(2);
    }

    @Test
    void crashMidwayThenRerunDoesNotDoublePost() {
        Account a = openAccount("A", 3_650_000);
        Account b = openAccount("B", 3_650_000);
        Account c = openAccount("C", 3_650_000);
        // 障害注入：口座 B への利息計上だけ失敗させる。計上は並行実行なので、A・C は計上済みか未処理のどちらか
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_interest_b() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.type = 'INTEREST' AND NEW.target_account_id = %d THEN
                    RAISE EXCEPTION 'injected batch failure';
                  END IF;
                  RETURN NEW;
                END; $$""".formatted(b.id())).update();
        jdbc.sql("CREATE TRIGGER trg_fail_interest_b BEFORE INSERT ON transactions "
                + "FOR EACH ROW EXECUTE FUNCTION fail_interest_b()").update();
        try {
            assertThatThrownBy(() -> closing.run()).hasMessageContaining("injected batch failure");
        } finally {
            jdbc.sql("DROP TRIGGER trg_fail_interest_b ON transactions").update();
            jdbc.sql("DROP FUNCTION fail_interest_b()").update();
        }

        long postedBeforeCrash = interestPostingsFor(a.id()) + interestPostingsFor(c.id());
        assertThat(interestPostingsFor(a.id())).isLessThanOrEqualTo(1);
        assertThat(interestPostingsFor(b.id())).isZero();
        assertThat(interestPostingsFor(c.id())).isLessThanOrEqualTo(1);
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE); // 日付は進んでいない
        assertThat(closing.history()).singleElement().satisfies(h -> {
            assertThat(h.status()).isEqualTo("RUNNING");
            assertThat(h.lastError()).contains("injected batch failure");
        });

        // 再実行：計上済みの口座はスキップし、残り（B を含む）だけ計上して日付を 1 日進める
        var report = closing.run();

        assertThat(report.alreadyPosted()).isEqualTo(postedBeforeCrash);
        assertThat(report.interestPostedThisRun()).isEqualTo(3 - postedBeforeCrash);
        assertThat(interestPostingsFor(a.id())).isEqualTo(1);
        assertThat(interestPostingsFor(b.id())).isEqualTo(1);
        assertThat(interestPostingsFor(c.id())).isEqualTo(1);
        assertThat(balanceOf(a.id())).isEqualTo(3_650_100);
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
        assertThat(closing.history()).singleElement().satisfies(h -> {
            assertThat(h.status()).isEqualTo("COMPLETED");
            assertThat(h.runCount()).isEqualTo(2);
            assertThat(h.interestPostings()).isEqualTo(3);
        });
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void concurrentClosingsPostOnceAndAdvanceDateOnce() throws Exception {
        List<Account> accounts = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            accounts.add(openAccount("Acct" + i, 3_650_000));
        }

        List<DailyClosingService.ClosingReport> reports = Concurrently.run(4, i -> closing.run());

        assertThat(reports).allSatisfy(r -> assertThat(r.businessDate()).isEqualTo(INITIAL_BUSINESS_DATE));
        assertThat(reports).filteredOn(DailyClosingService.ClosingReport::dateAdvanced).hasSize(1);
        assertThat(reports.stream().mapToInt(DailyClosingService.ClosingReport::interestPostedThisRun).sum())
                .isEqualTo(10);
        assertThat(accounts).allSatisfy(a -> assertThat(interestPostingsFor(a.id())).isEqualTo(1));
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void transactionsAreJournaledOnTheCurrentBusinessDate() {
        Account a = openAccount("A", 1_000);
        closing.run();
        long txId = movements.execute(MovementCommand.withdrawal(key(), a.id(), 100, "JPY", null, null)).transaction().id();

        assertThat(jdbc.sql("SELECT business_date FROM journal_entries WHERE transaction_id = :t")
                .param("t", txId).query(java.time.LocalDate.class).single())
                .isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
    }
}
