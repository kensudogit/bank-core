package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import com.example.bankcore.transaction.MoneyMovementService.MovementResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Test
    void sameKeySamePayloadReturnsOriginalResultAndMovesMoneyOnce() {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 0);
        String k = key();
        MovementCommand cmd = MovementCommand.transfer(k, alice.id(), bob.id(), 1_000, "JPY", "dinner", null);

        MovementResult first = movements.execute(cmd);
        MovementResult second = movements.execute(cmd);
        MovementResult third = movements.execute(cmd);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(third.replayed()).isTrue();
        assertThat(second.transaction()).isEqualTo(first.transaction());
        assertThat(balanceOf(alice.id())).isEqualTo(9_000);
        assertThat(balanceOf(bob.id())).isEqualTo(1_000);
        assertThat(jdbc.sql("SELECT count(*) FROM transactions WHERE idempotency_key = :k")
                .param("k", k).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id = :id")
                .param("id", Long.toString(first.transaction().id())).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 0);
        String k = key();
        movements.execute(MovementCommand.transfer(k, alice.id(), bob.id(), 1_000, "JPY", null, null));

        assertThatThrownBy(() -> movements.execute(
                MovementCommand.transfer(k, alice.id(), bob.id(), 2_000, "JPY", null, null)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> movements.execute(
                MovementCommand.deposit(k, bob.id(), 1_000, "JPY", null, null)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(balanceOf(alice.id())).isEqualTo(9_000);
    }

    @Test
    void rejectedResultIsAlsoReplayedEvenAfterFundsArrive() {
        Account alice = openAccount("Alice", 500);
        Account bob = openAccount("Bob", 0);
        String k = key();
        MovementCommand cmd = MovementCommand.transfer(k, alice.id(), bob.id(), 1_000, "JPY", null, null);

        MovementResult first = movements.execute(cmd);
        movements.execute(MovementCommand.deposit(key(), alice.id(), 10_000, "JPY", null, null));
        MovementResult replay = movements.execute(cmd);

        assertThat(first.transaction().status()).isEqualTo(TransactionStatus.REJECTED);
        // 同じキーの再送は「最初の結果」を返す。新しい振込をしたいなら新しいキーを使う。
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transaction().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(balanceOf(bob.id())).isZero();
    }

    @Test
    void outcomeCanBeLookedUpByKeyAfterUnknownResponse() {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 0);
        String k = key();
        movements.execute(MovementCommand.transfer(k, alice.id(), bob.id(), 100, "JPY", null, null));

        // クライアントが応答を受け取れなかった想定：キーで結果を照会する
        assertThat(movements.findByIdempotencyKey(k))
                .hasValueSatisfying(tx -> assertThat(tx.status()).isEqualTo(TransactionStatus.COMPLETED));
        assertThat(movements.findByIdempotencyKey(key())).isEmpty();
    }

    @Test
    void concurrentRequestsWithSameKeyExecuteExactlyOnce() throws Exception {
        Account alice = openAccount("Alice", 100_000);
        Account bob = openAccount("Bob", 0);
        String k = key();
        MovementCommand cmd = MovementCommand.transfer(k, alice.id(), bob.id(), 1_000, "JPY", null, null);

        List<MovementResult> results = Concurrently.run(20, i -> movements.execute(cmd));

        assertThat(results).hasSize(20);
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(results).extracting(r -> r.transaction().id()).containsOnly(results.get(0).transaction().id());
        assertThat(balanceOf(alice.id())).isEqualTo(99_000);
        assertThat(balanceOf(bob.id())).isEqualTo(1_000);
        assertThat(jdbc.sql("SELECT count(*) FROM journal_entries e JOIN transactions t ON t.id = e.transaction_id "
                + "WHERE t.idempotency_key = :k").param("k", k).query(Long.class).single()).isEqualTo(1);
    }
}
