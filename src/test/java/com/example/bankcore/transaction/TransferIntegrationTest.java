package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountStatus;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.ledger.JournalRepository.PostedLine;
import com.example.bankcore.outbox.OutboxEvent;
import com.example.bankcore.outbox.OutboxRepository;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JournalRepository journal;

    @Autowired
    OutboxRepository outbox;

    @Test
    void successfulTransferUpdatesBalancesJournalAndOutboxAtomically() {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 0);
        long txBefore = count("transactions");
        long journalsBefore = count("journal_entries");
        long outboxBefore = count("outbox_events");

        var result = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 3_000, "JPY", "rent", "corr-1"));

        MoneyTransaction tx = result.transaction();
        assertThat(result.replayed()).isFalse();
        assertThat(tx.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.correlationId()).isEqualTo("corr-1");
        assertThat(balanceOf(alice.id())).isEqualTo(7_000);
        assertThat(balanceOf(bob.id())).isEqualTo(3_000);

        // 取引 1 件・仕訳 1 件・Outbox 1 件
        assertThat(count("transactions")).isEqualTo(txBefore + 1);
        assertThat(count("journal_entries")).isEqualTo(journalsBefore + 1);
        assertThat(count("outbox_events")).isEqualTo(outboxBefore + 1);

        // 仕訳：借方 顧客預金(Alice) / 貸方 顧客預金(Bob)
        List<PostedLine> lines = journal.linesForTransaction(tx.id());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).satisfies(l -> {
            assertThat(l.glCode()).isEqualTo(GlCode.CUSTOMER_DEPOSITS);
            assertThat(l.accountId()).isEqualTo(alice.id());
            assertThat(l.debitMinor()).isEqualTo(3_000);
        });
        assertThat(lines.get(1)).satisfies(l -> {
            assertThat(l.accountId()).isEqualTo(bob.id());
            assertThat(l.creditMinor()).isEqualTo(3_000);
        });
        assertThat(lines.stream().mapToLong(PostedLine::debitMinor).sum())
                .isEqualTo(lines.stream().mapToLong(PostedLine::creditMinor).sum());

        List<OutboxEvent> events = outbox.findByAggregate("Transaction", Long.toString(tx.id()));
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("TransferCompleted");
            assertThat(e.payload()).contains("\"amountMinor\": 3000");
            assertThat(e.publishedAt()).isNull();
        });
    }

    @Test
    void insufficientFundsIsRejectedWithoutAnyLedgerOrEventChange() {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 0);
        long journalsBefore = count("journal_entries");
        long outboxBefore = count("outbox_events");

        var result = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 1_001, "JPY", null, null));

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(result.transaction().rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(balanceOf(alice.id())).isEqualTo(1_000);
        assertThat(balanceOf(bob.id())).isZero();
        assertThat(count("journal_entries")).isEqualTo(journalsBefore);
        assertThat(count("outbox_events")).isEqualTo(outboxBefore);
    }

    @Test
    void heldFundsAreNotAvailable() {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 0);
        jdbc.sql("UPDATE accounts SET held_balance_minor = 600 WHERE id = :id").param("id", alice.id()).update();

        var rejected = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 500, "JPY", null, null));
        var ok = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 400, "JPY", null, null));

        assertThat(rejected.transaction().rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(ok.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(alice.id())).isEqualTo(600);
    }

    @Test
    void frozenAccountCannotSendOrReceive() {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 1_000);
        accountService.changeStatus(bob.id(), AccountStatus.FROZEN);

        var toFrozen = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 100, "JPY", null, null));
        var fromFrozen = movements.execute(MovementCommand.transfer(key(), bob.id(), alice.id(), 100, "JPY", null, null));

        assertThat(toFrozen.transaction().rejectReason()).isEqualTo(RejectReason.TARGET_ACCOUNT_NOT_ACTIVE);
        assertThat(fromFrozen.transaction().rejectReason()).isEqualTo(RejectReason.SOURCE_ACCOUNT_NOT_ACTIVE);
        assertThat(balanceOf(alice.id())).isEqualTo(1_000);
        assertThat(balanceOf(bob.id())).isEqualTo(1_000);
    }

    @Test
    void currencyMismatchIsRejected() {
        Account jpy = openAccount("Alice", 1_000);
        Account usd = openAccount("Bob", "USD", 0);

        var result = movements.execute(MovementCommand.transfer(key(), jpy.id(), usd.id(), 100, "JPY", null, null));

        assertThat(result.transaction().rejectReason()).isEqualTo(RejectReason.CURRENCY_MISMATCH);
        assertThat(balanceOf(jpy.id())).isEqualTo(1_000);
    }

    @Test
    void unknownAccountIsNotFoundAndLeavesNoIdempotencyRecord() {
        Account alice = openAccount("Alice", 1_000);
        String k = key();

        assertThatThrownBy(() -> movements.execute(MovementCommand.transfer(k, alice.id(), 9_999, 100, "JPY", null, null)))
                .isInstanceOf(NotFoundException.class);
        assertThat(movements.findByIdempotencyKey(k)).isEmpty();
    }

    @Test
    void depositAndWithdrawalPostAgainstCash() {
        Account alice = openAccount("Alice", 0);

        var dep = movements.execute(MovementCommand.deposit(key(), alice.id(), 5_000, "JPY", null, null));
        var wd = movements.execute(MovementCommand.withdrawal(key(), alice.id(), 2_000, "JPY", null, null));
        var tooMuch = movements.execute(MovementCommand.withdrawal(key(), alice.id(), 3_001, "JPY", null, null));

        assertThat(dep.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(wd.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tooMuch.transaction().rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(balanceOf(alice.id())).isEqualTo(3_000);

        assertThat(journal.linesForTransaction(dep.transaction().id()))
                .extracting(PostedLine::glCode).containsExactly(GlCode.CASH, GlCode.CUSTOMER_DEPOSITS);
        assertThat(journal.linesForTransaction(wd.transaction().id()))
                .extracting(PostedLine::glCode).containsExactly(GlCode.CUSTOMER_DEPOSITS, GlCode.CASH);
    }

    @Test
    void failureInTheMiddleRollsBackEverything() {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 0);
        long journalsBefore = count("journal_entries");
        long outboxBefore = count("outbox_events");
        long txBefore = count("transactions");
        // 障害注入：仕訳明細への INSERT を必ず失敗させるトリガー
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_journal_line() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected failure'; END; $$""").update();
        jdbc.sql("""
                CREATE TRIGGER trg_fail_journal_line BEFORE INSERT ON journal_lines
                FOR EACH ROW EXECUTE FUNCTION fail_journal_line()""").update();
        String k = key();
        try {
            assertThatThrownBy(() -> movements.execute(
                    MovementCommand.transfer(k, alice.id(), bob.id(), 500, "JPY", null, null)))
                    .hasMessageContaining("injected failure");
        } finally {
            jdbc.sql("DROP TRIGGER trg_fail_journal_line ON journal_lines").update();
            jdbc.sql("DROP FUNCTION fail_journal_line()").update();
        }

        // 残高・取引・仕訳・Outbox のいずれも部分更新が残っていない
        assertThat(balanceOf(alice.id())).isEqualTo(1_000);
        assertThat(balanceOf(bob.id())).isZero();
        assertThat(count("transactions")).isEqualTo(txBefore);
        assertThat(count("journal_entries")).isEqualTo(journalsBefore);
        assertThat(count("outbox_events")).isEqualTo(outboxBefore);

        // 冪等キーも予約されていないので、障害解消後に同じキーで再実行できる
        var retry = movements.execute(MovementCommand.transfer(k, alice.id(), bob.id(), 500, "JPY", null, null));
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(bob.id())).isEqualTo(500);
    }
}
