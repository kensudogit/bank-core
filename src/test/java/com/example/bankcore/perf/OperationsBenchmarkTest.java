package com.example.bankcore.perf;

import com.example.bankcore.account.Account;
import com.example.bankcore.batch.DailyClosingService;
import com.example.bankcore.cards.CardService;
import com.example.bankcore.compliance.AmlService;
import com.example.bankcore.crm.Customer360Service;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.loan.CollateralType;
import com.example.bankcore.loan.LoanApplicationService;
import com.example.bankcore.loan.LoanProduct;
import com.example.bankcore.loan.LoanService;
import com.example.bankcore.loan.RepaymentMethod;
import com.example.bankcore.risk.RiskService;
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
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 業務機能（日次締めの各ステップ・照合・AML 監視・融資・カード・リスク管理・顧客 360°）の実用的な件数での計測。
 * 通常の {@code mvn verify} では実行しない。
 *
 * <pre>
 * ./mvnw -B test -Dtest=OperationsBenchmarkTest -Dbench=true -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Testcontainers 上の PostgreSQL に対する相対比較用の数字であり、本番性能の見積もりではない。
 */
@EnabledIfSystemProperty(named = "bench", matches = "true")
class OperationsBenchmarkTest extends AbstractIntegrationTest {

    private static final int THREADS = 16;
    private static final int ACCOUNTS = 2_000;
    private static final int LOANS = 300;
    private static final int TRANSFERS = 30_000;
    private static final int CARD_AUTHS = 4_000;

    @Autowired DailyClosingService closing;
    @Autowired ReconciliationService reconciliation;
    @Autowired AmlService aml;
    @Autowired LoanApplicationService applications;
    @Autowired LoanService loans;
    @Autowired CardService cards;
    @Autowired RiskService risk;
    @Autowired CustomerService customers;
    @Autowired Customer360Service customer360;

    @Test
    void operationsAtVolume() throws Exception {
        // ---- 口座 2,000（うち 300 に融資、全口座を顧客に紐づけ）
        List<Account> accounts = new ArrayList<>();
        long[] customerIds = new long[ACCOUNTS];
        for (int i = 0; i < ACCOUNTS; i++) {
            Account a = accountService.open("Bench" + i, "JPY", 0);
            accounts.add(a);
            var c = customers.register(new CustomerService.NewCustomer("INDIVIDUAL", "Bench" + i, null, null, null,
                    8_000_000L, 3, 1, null));
            customers.linkAccount(c.id(), a.id());
            customerIds[i] = c.id();
        }
        long[] ids = accounts.stream().mapToLong(Account::id).toArray();
        time("deposits (" + ACCOUNTS + " accounts, " + THREADS + " threads)", () -> {
            parallel(ACCOUNTS, i -> movements.execute(MovementCommand.deposit(key(), ids[i], 5_000_000, "JPY", null, null)));
            return null;
        });

        Run loanRun = measured(LOANS, i -> {
            var app = applications.apply(new LoanApplicationService.NewApplication(ids[i], LoanProduct.BUSINESS,
                    1_200_000, 12, RepaymentMethod.EQUAL_PAYMENT, 8_000_000, 0, null, "teller1"));
            long appId = app.application().id();
            applications.addCollateral(appId, CollateralType.DEPOSIT, "定期", 2_000_000, null);
            applications.screen(appId);
            applications.decide(appId, true, "manager1", "bench", null);
            loans.disburse(appId, key(), null);
        });
        report("loan apply+screen+decide+disburse", loanRun);

        // ---- 取引量（振込 30,000 件・カードのオーソリ 4,000 件）
        Run transfers = measured(TRANSFERS, i -> {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            long from = ids[r.nextInt(ids.length)];
            long to;
            do {
                to = ids[r.nextInt(ids.length)];
            } while (to == from);
            movements.execute(MovementCommand.transfer(key(), from, to, 1 + r.nextInt(1_000), "JPY", null, null));
        });
        report("transfer", transfers);

        Account shop = openAccount("Shop", 0);
        CardService.Merchant m = cards.registerMerchant("Shop", "5411", shop.id(), 300);
        List<CardService.Card> cardList = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            cardList.add(cards.issueCard(ids[i], 1_000_000));
        }
        Run auths = measured(CARD_AUTHS, i -> {
            var a = cards.authorize(cardList.get(i % cardList.size()).token(), m.id(), 1_000 + i % 500, "B-" + i);
            if (i % 2 == 0) {
                cards.capture(a.id(), a.amountMinor(), key());
            }
        });
        report("card authorize (+capture half)", auths);

        // ---- 単発の重い処理
        time("reconciliation", () -> reconciliation.run().ok());
        time("risk dashboard", risk::dashboard);
        time("customer 360 (x50)", () -> {
            for (int i = 0; i < 50; i++) {
                customer360.view(customerIds[i]);
            }
            return null;
        });
        time("aml scan (" + (TRANSFERS + ACCOUNTS) + " tx + " + CARD_AUTHS + " auths)", () -> {
            long created = 0;
            AmlService.ScanReport r;
            do {
                r = aml.scan();
                created += r.alertsCreated();
            } while (r.transactionsScanned() > 0 || r.authorizationsScanned() > 0);
            return created;
        });

        // ---- 日次締め（1 回目の約定返済日。融資 300 件の自動引落し・延滞判定を含む）
        jdbc.sql("UPDATE business_calendar SET current_business_date = :d")
                .param("d", INITIAL_BUSINESS_DATE.plusMonths(1)).update();
        long start = System.nanoTime();
        var report = closing.run("bench");
        System.out.printf("[bench] daily closing total: %d ms (status=%s)%n", (System.nanoTime() - start) / 1_000_000,
                report.status());
        for (var s : report.steps()) {
            long ms = s.finishedAt() == null ? -1
                    : java.time.Duration.between(s.startedAt(), s.finishedAt()).toMillis();
            System.out.printf("[bench]   %-22s %6d ms  processed=%d%n", s.stepName(), ms, s.processed());
        }
        assertThat(report.reconciliationOk()).isTrue();
        System.out.printf("[bench] volume: journal_lines=%d transactions=%d%n",
                count("journal_lines"), count("transactions"));
    }

    // ------------------------------------------------------------------ helpers

    private interface Body {
        void run(int i) throws Exception;
    }

    private void parallel(int n, Body body) throws Exception {
        Concurrently.run(THREADS, t -> {
            for (int i = t; i < n; i += THREADS) {
                try {
                    body.run(i);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return null;
        });
    }

    private record Run(long elapsedNanos, long[] latencies) {
    }

    private Run measured(int n, Body body) throws Exception {
        long start = System.nanoTime();
        List<long[]> per = Concurrently.run(THREADS, t -> {
            List<Long> lat = new ArrayList<>();
            for (int i = t; i < n; i += THREADS) {
                long s = System.nanoTime();
                try {
                    body.run(i);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                lat.add(System.nanoTime() - s);
            }
            return lat.stream().mapToLong(Long::longValue).toArray();
        });
        return new Run(System.nanoTime() - start, per.stream().flatMapToLong(Arrays::stream).sorted().toArray());
    }

    private static void report(String label, Run run) {
        long[] l = run.latencies();
        System.out.printf("[bench] %s: %d ops, %.0f ops/s, p50=%.1fms p95=%.1fms p99=%.1fms%n", label, l.length,
                l.length * 1e9 / run.elapsedNanos(), pct(l, 0.50), pct(l, 0.95), pct(l, 0.99));
    }

    private static <T> void time(String label, Callable<T> work) throws Exception {
        long start = System.nanoTime();
        work.call();
        System.out.printf("[bench] %s: %d ms%n", label, (System.nanoTime() - start) / 1_000_000);
    }

    private static double pct(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * p))] / 1e6;
    }
}
