package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.ledger.JournalRepository.PostedLine;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 取消（反対仕訳）のテスト。 */
class ReversalIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JournalRepository journal;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void reversingTransferRestoresBalancesWithMirroredJournal() {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 0);
        long originalId = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 3_000, "JPY", null, null))
                .transaction().id();
        List<PostedLine> originalLines = journal.linesForTransaction(originalId);

        var reversal = movements.reverse(key(), originalId, "corr-rev");

        MoneyTransaction rev = reversal.transaction();
        assertThat(rev.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(rev.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(rev.reversesTransactionId()).isEqualTo(originalId);
        assertThat(rev.sourceAccountId()).isEqualTo(bob.id());
        assertThat(rev.targetAccountId()).isEqualTo(alice.id());
        assertThat(balanceOf(alice.id())).isEqualTo(10_000);
        assertThat(balanceOf(bob.id())).isZero();

        // 反対仕訳：元の仕訳の借方と貸方が入れ替わっている。元の仕訳は変更されない。
        List<PostedLine> reversalLines = journal.linesForTransaction(rev.id());
        assertThat(reversalLines).hasSize(originalLines.size());
        for (int i = 0; i < originalLines.size(); i++) {
            assertThat(reversalLines.get(i).accountId()).isEqualTo(originalLines.get(i).accountId());
            assertThat(reversalLines.get(i).debitMinor()).isEqualTo(originalLines.get(i).creditMinor());
            assertThat(reversalLines.get(i).creditMinor()).isEqualTo(originalLines.get(i).debitMinor());
        }
        assertThat(journal.linesForTransaction(originalId)).isEqualTo(originalLines);
        assertThat(count("outbox_events WHERE event_type = 'TransactionReversed'")).isEqualTo(1);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void reversingDepositAndWithdrawalPostsAgainstCash() {
        Account alice = openAccount("Alice", 0);
        long dep = movements.execute(MovementCommand.deposit(key(), alice.id(), 5_000, "JPY", null, null)).transaction().id();
        long wd = movements.execute(MovementCommand.withdrawal(key(), alice.id(), 1_000, "JPY", null, null)).transaction().id();

        movements.reverse(key(), wd, null);
        assertThat(balanceOf(alice.id())).isEqualTo(5_000);
        movements.reverse(key(), dep, null);
        assertThat(balanceOf(alice.id())).isZero();
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void cannotReverseTwiceButSameKeyReplays() {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 0);
        long originalId = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 1_000, "JPY", null, null))
                .transaction().id();
        String k = key();

        var first = movements.reverse(k, originalId, null);
        var replay = movements.reverse(k, originalId, null);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().id()).isEqualTo(first.transaction().id());
        assertThatThrownBy(() -> movements.reverse(key(), originalId, null))
                .isInstanceOf(BusinessConflictException.class).hasMessageContaining("already reversed");
        assertThat(balanceOf(alice.id())).isEqualTo(10_000);
    }

    @Test
    void cannotReverseRejectedTransactionOrAReversal() {
        Account alice = openAccount("Alice", 100);
        Account bob = openAccount("Bob", 0);
        long rejected = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 1_000, "JPY", null, null))
                .transaction().id();
        long ok = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 50, "JPY", null, null))
                .transaction().id();
        long reversal = movements.reverse(key(), ok, null).transaction().id();
        String k = key();

        assertThatThrownBy(() -> movements.reverse(k, rejected, null)).isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> movements.reverse(key(), reversal, null)).isInstanceOf(BusinessConflictException.class);
        // 409 の場合は冪等キーを消費しない
        assertThat(movements.findByIdempotencyKey(k)).isEmpty();
    }

    @Test
    void reversalNeedsFundsAndCanBeRetriedWithNewKeyAfterRejection() {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 0);
        long originalId = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 1_000, "JPY", null, null))
                .transaction().id();
        movements.execute(MovementCommand.withdrawal(key(), bob.id(), 600, "JPY", null, null)); // Bob が使ってしまった

        var rejected = movements.reverse(key(), originalId, null);
        assertThat(rejected.transaction().rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);

        movements.execute(MovementCommand.deposit(key(), bob.id(), 600, "JPY", null, null));
        var retried = movements.reverse(key(), originalId, null);

        assertThat(retried.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(alice.id())).isEqualTo(1_000);
        assertThat(balanceOf(bob.id())).isZero();
    }

    @Test
    void concurrentReversalsWithDifferentKeysSucceedOnlyOnce() throws Exception {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 5_000);
        long originalId = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 1_000, "JPY", null, null))
                .transaction().id();

        List<String> outcomes = Concurrently.run(10, i -> {
            try {
                return movements.reverse(key(), originalId, null).transaction().status().name();
            } catch (BusinessConflictException e) {
                return "CONFLICT";
            }
        });

        assertThat(outcomes).filteredOn("COMPLETED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("CONFLICT"::equals).hasSize(9);
        assertThat(balanceOf(alice.id())).isEqualTo(10_000);
        assertThat(balanceOf(bob.id())).isEqualTo(5_000);
        assertThat(reconciliation.run().ok()).isTrue();
    }
}
