package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import com.example.bankcore.transaction.MoneyMovementService.MovementResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void concurrentWithdrawalsFromOneAccountCannotOverspend() throws Exception {
        Account alice = openAccount("Alice", 10_000);
        Account bob = openAccount("Bob", 0);

        // 残高 10,000 に対し 1,000 の振込を 30 本同時に投げる → ちょうど 10 本だけ成功するはず
        List<MovementResult> results = Concurrently.run(30, i -> movements.execute(
                MovementCommand.transfer(key(), alice.id(), bob.id(), 1_000, "JPY", null, null)));

        long completed = results.stream().filter(r -> r.transaction().status() == TransactionStatus.COMPLETED).count();
        long rejected = results.stream().filter(r -> r.transaction().rejectReason() == RejectReason.INSUFFICIENT_FUNDS).count();
        assertThat(completed).isEqualTo(10);
        assertThat(rejected).isEqualTo(20);
        assertThat(balanceOf(alice.id())).isZero();
        assertThat(balanceOf(bob.id())).isEqualTo(10_000);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void lockTimeoutIsConfiguredOnEveryPooledConnection() {
        // 取引ごとの SET を省き、hikari.connection-init-sql で接続単位に設定している（テストでは 10000ms）
        assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("10s");
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        Account a = openAccount("A", 100_000);
        Account b = openAccount("B", 100_000);
        Account c = openAccount("C", 100_000);
        Account[] ring = {a, b, c};

        // A→B, B→A, B→C, C→A ... と逆方向・循環する振込を同時に流す。
        // ロックを ID 昇順で取るため、デッドロック（40P01）は発生しない。
        List<MovementResult> results = Concurrently.run(60, i -> {
            Account from = ring[i % 3];
            Account to = (i / 3) % 2 == 0 ? ring[(i + 1) % 3] : ring[(i + 2) % 3];
            return movements.execute(MovementCommand.transfer(key(), from.id(), to.id(), 100, "JPY", null, null));
        });

        assertThat(results).allSatisfy(r -> assertThat(r.transaction().status()).isEqualTo(TransactionStatus.COMPLETED));
        // 行内振込は総額を保存する
        assertThat(balanceOf(a.id()) + balanceOf(b.id()) + balanceOf(c.id())).isEqualTo(300_000);
        assertThat(count("journal_entries")).isEqualTo(3 + 60);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void transferWaitsForRowLockHeldByAnotherTransaction() throws Exception {
        Account alice = openAccount("Alice", 1_000);
        Account bob = openAccount("Bob", 0);

        // 別トランザクションが短時間 Alice の行ロックを保持している状況を作る
        var holder = new Thread(() -> jdbc.sql("""
                DO $$ BEGIN
                  PERFORM 1 FROM accounts WHERE id = %d FOR UPDATE;
                  PERFORM pg_sleep(1);
                END $$""".formatted(alice.id())).update());
        holder.start();
        Thread.sleep(200);

        var result = movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 100, "JPY", null, null));
        holder.join();

        assertThat(result.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(bob.id())).isEqualTo(100);
    }
}
