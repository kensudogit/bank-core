package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 取引リポジトリ：冪等キーでの予約・照会・状態遷移（PENDING からだけ確定できる）。 */
class TransactionRepositoryTest extends AbstractIntegrationTest {

    @Autowired TransactionRepository repo;

    @Test
    void reserveIsOncePerKey() {
        Account a = accountService.open("A", "JPY", 0);
        MovementCommand cmd = MovementCommand.deposit("dup-key", a.id(), 100, "JPY", "m", "corr");
        Optional<Long> first = repo.tryReserve(cmd, cmd.requestHash());
        assertThat(first).isPresent();
        assertThat(repo.tryReserve(cmd, cmd.requestHash())).isEmpty();

        MoneyTransaction pending = repo.findByIdempotencyKey("dup-key").orElseThrow();
        assertThat(pending.id()).isEqualTo(first.get());
        assertThat(pending.status()).isEqualTo(TransactionStatus.PENDING);
        assertThat(pending.requestHash()).isEqualTo(cmd.requestHash());
        assertThat(pending.correlationId()).isEqualTo("corr");
    }

    @Test
    void rejectFromPendingOnlyOnce() {
        Account a = accountService.open("A", "JPY", 0);
        MovementCommand cmd = MovementCommand.withdrawal(key(), a.id(), 100, "JPY", null, null);
        long id = repo.tryReserve(cmd, cmd.requestHash()).orElseThrow();
        MoneyTransaction rejected = repo.markRejected(id, RejectReason.INSUFFICIENT_FUNDS);
        assertThat(rejected.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(rejected.rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(rejected.completedAt()).isNotNull();
        // 確定済みはもう変えられない
        assertThatThrownBy(() -> repo.markRejected(id, RejectReason.CURRENCY_MISMATCH)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> repo.updatePendingAmount(id, 50)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updatePendingAmount() {
        Account a = accountService.open("A", "JPY", 0);
        MovementCommand cmd = MovementCommand.deposit(key(), a.id(), 100, "JPY", null, null);
        long id = repo.tryReserve(cmd, cmd.requestHash()).orElseThrow();
        repo.updatePendingAmount(id, 70);
        assertThat(repo.findById(id).orElseThrow().amountMinor()).isEqualTo(70);
    }

    @Test
    void findByAccountAndReversal() {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        var t = movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 100, "JPY", null, null)).transaction();
        assertThat(repo.findByAccount(a.id())).hasSize(2);   // 入金と振込
        assertThat(repo.findByAccount(b.id())).extracting(MoneyTransaction::id).contains(t.id());
        assertThat(repo.findCompletedReversalOf(t.id())).isEmpty();

        var rev = movements.reverse(key(), t.id(), null).transaction();
        assertThat(repo.findCompletedReversalOf(t.id())).map(MoneyTransaction::id).contains(rev.id());
        assertThat(repo.findById(999_999)).isEmpty();
    }
}
