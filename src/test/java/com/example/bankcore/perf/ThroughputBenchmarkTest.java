package com.example.bankcore.perf;

import com.example.bankcore.account.Account;
import com.example.bankcore.batch.DailyClosingService;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 簡易スループット計測。通常の {@code mvn verify} では実行しない。
 *
 * <pre>
 * ./mvnw -B test -Dtest=ThroughputBenchmarkTest -Dbench=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>Testcontainers 上の PostgreSQL に対する相対比較用の数字であり、本番性能の見積もりではない。</p>
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class ThroughputBenchmarkTest extends AbstractIntegrationTest {

    private static final int THREADS = 16;
    private static final int TRANSFERS_PER_THREAD = 500;

    @Autowired
    DailyClosingService closing;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    void transfersAndDailyClosing() throws Exception {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            accounts.add(openAccount("Bench" + i, 100_000_000));
        }
        long[] ids = accounts.stream().mapToLong(Account::id).toArray();

        warmUp(ids);
        report("transfer (200 accounts, low contention)", runTransfers(ids));
        report("transfer (hot account: all touch 1 account)", runTransfers(new long[] {ids[0], ids[1]}));

        for (int i = 0; i < 1_800; i++) {
            accountService.open("Closing" + i, "JPY", 0);
        }
        // 利息が付くように 2,000 口座すべてへ入金
        List<Account> all = accountService.list();
        Concurrently.run(THREADS, t -> {
            for (int i = t; i < all.size(); i += THREADS) {
                movements.execute(MovementCommand.deposit(key(), all.get(i).id(), 10_000_000, "JPY", null, null));
            }
            return null;
        });
        long start = System.nanoTime();
        var closingReport = closing.run();
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("[bench] daily closing: %d accounts posted in %d ms (%.0f accounts/s)%n",
                closingReport.interestPostedThisRun(), ms, closingReport.interestPostedThisRun() * 1000.0 / ms);

        assertThat(reconciliation.run().ok()).isTrue();
    }

    private void warmUp(long[] ids) throws Exception {
        Concurrently.run(THREADS, t -> {
            for (int i = 0; i < 50; i++) {
                transferOnce(ids);
            }
            return null;
        });
    }

    private record Run(long elapsedNanos, long[] latenciesNanos) {
    }

    private Run runTransfers(long[] ids) throws Exception {
        long start = System.nanoTime();
        List<long[]> perThread = Concurrently.run(THREADS, t -> {
            long[] lat = new long[TRANSFERS_PER_THREAD];
            for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                long s = System.nanoTime();
                transferOnce(ids);
                lat[i] = System.nanoTime() - s;
            }
            return lat;
        });
        long elapsed = System.nanoTime() - start;
        long[] all = perThread.stream().flatMapToLong(Arrays::stream).sorted().toArray();
        return new Run(elapsed, all);
    }

    private void transferOnce(long[] ids) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long from = ids[r.nextInt(ids.length)];
        long to;
        do {
            to = ids[r.nextInt(ids.length)];
        } while (to == from);
        movements.execute(MovementCommand.transfer(key(), from, to, 1 + r.nextInt(100), "JPY", null, null));
    }

    private static void report(String label, Run run) {
        long[] l = run.latenciesNanos();
        double tps = l.length * 1e9 / run.elapsedNanos();
        System.out.printf("[bench] %s: %d tx, %.0f tx/s, p50=%.1fms p95=%.1fms p99=%.1fms%n",
                label, l.length, tps, pct(l, 0.50), pct(l, 0.95), pct(l, 0.99));
    }

    private static double pct(long[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * p))] / 1e6;
    }
}
