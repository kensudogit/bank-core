package com.example.bankcore.ledger;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB 側の安全網（貸借一致トリガー・変更禁止トリガー）と照合処理のテスト。 */
class LedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void databaseRejectsUnbalancedJournalAtCommit() {
        Account alice = openAccount("Alice", 1_000);
        // 仕訳を持たない（拒否された）取引を 1 件作り、そこに不正な仕訳を付けようとする
        long rejectedTxId = movements.execute(MovementCommand.withdrawal(key(), alice.id(), 5_000, "JPY", null, null))
                .transaction().id();
        long journalsBefore = count("journal_entries");

        // アプリを経由せず、借方だけの仕訳を直接書き込もうとする
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entryId = jdbc.sql("""
                            INSERT INTO journal_entries (transaction_id, business_date, description)
                            VALUES (:tx, CURRENT_DATE, 'bad')
                            RETURNING id""")
                    .param("tx", rejectedTxId).query(Long.class).single();
            jdbc.sql("""
                            INSERT INTO journal_lines (journal_entry_id, line_no, gl_code, account_id, debit_minor, currency)
                            VALUES (:e, 1, '2000', :a, 999, 'JPY')""")
                    .param("e", entryId).param("a", alice.id()).update();
        })).hasMessageContaining("not balanced");

        assertThat(count("journal_entries")).isEqualTo(journalsBefore);
    }

    @Test
    void postedJournalLinesAreImmutable() {
        openAccount("Alice", 1_000);

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_lines SET credit_minor = 2000").update())
                .rootCause().hasMessageContaining("journal_lines is append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries").update())
                .rootCause().hasMessageContaining("journal_entries is append-only");
    }

    @Test
    void reconciliationIsCleanAfterNormalOperations() {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 5_000);
        movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 2_500, "JPY", null, null));
        movements.execute(MovementCommand.withdrawal(key(), bob.id(), 1_000, "JPY", null, null));

        ReconciliationService.Report report = reconciliation.run();

        assertThat(report.ok()).isTrue();
        assertThat(report.unbalancedJournals()).isEmpty();
        assertThat(report.accountMismatches()).isEmpty();
        // 現金：入金 15,000 - 出金 1,000 = 借方残 14,000 / 顧客預金：貸方残 14,000
        assertThat(report.trialBalance()).anySatisfy(r -> {
            assertThat(r.glCode()).isEqualTo(GlCode.CASH);
            assertThat(r.debitMinor() - r.creditMinor()).isEqualTo(14_000);
        });
    }

    @Test
    void reconciliationDetectsInjectedBalanceDiscrepancy() {
        Account alice = openAccount("Alice", 10_000);
        // 仕訳を通さずに残高だけを書き換える（不正操作・バグの模擬）
        jdbc.sql("UPDATE accounts SET posted_balance_minor = posted_balance_minor + 1 WHERE id = :id")
                .param("id", alice.id()).update();

        ReconciliationService.Report report = reconciliation.run();

        assertThat(report.ok()).isFalse();
        assertThat(report.accountMismatches()).singleElement().satisfies(m -> {
            assertThat(m.accountId()).isEqualTo(alice.id());
            assertThat(m.accountBalanceMinor()).isEqualTo(10_001);
            assertThat(m.ledgerBalanceMinor()).isEqualTo(10_000);
        });
    }
}
