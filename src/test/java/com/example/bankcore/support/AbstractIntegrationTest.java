package com.example.bankcore.support;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountService;
import com.example.bankcore.transaction.MoneyMovementService;
import com.example.bankcore.transaction.MovementCommand;
import com.example.bankcore.transaction.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 実 PostgreSQL（Testcontainers）に対する統合テストの基底クラス。
 *
 * <p>コンテナは JVM 内で 1 つだけ起動して全テストクラスで共有する（シングルトンパターン）。
 * すべてのサブクラスが同じ設定を使うため Spring コンテキストもキャッシュされ再利用される。</p>
 */
@SpringBootTest(properties = {
        "bank.outbox.scheduler-enabled=false",
        // 既存の締めのテストは自動確定（承認なし）。承認フローは ClosingControlIntegrationTest で確かめる
        "bank.closing.approval-required=false",
        "bank.transfer.lock-timeout-ms=10000",
        "spring.datasource.hikari.maximum-pool-size=30"
})
@AutoConfigureMockMvc
@Import(RecordingEventPublisher.class)
public abstract class AbstractIntegrationTest {

    protected static final java.time.LocalDate INITIAL_BUSINESS_DATE = java.time.LocalDate.of(2030, 4, 1);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bankcore")
            .withUsername("bankcore")
            .withPassword("bankcore");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected AccountService accountService;

    @Autowired
    protected MoneyMovementService movements;

    @Autowired
    protected RecordingEventPublisher publisher;

    @BeforeEach
    void cleanDatabase() {
        // TRUNCATE は行トリガー（仕訳の変更禁止）を発火しない
        jdbc.sql("""
                TRUNCATE processed_events, outbox_events, journal_lines, journal_entries, loan_ledger,
                         domestic_remittances, zengin_batches, debit_requests, debit_mandates, billers, fx_exchanges,
                         foreign_remittances, transactions,
                         loan_installments, loans, collaterals, guarantees, loan_applications, accounts,
                         closing_steps, closing_audit, daily_closings, security_trades, security_holdings,
                         security_prices, fund_orders, fund_holdings, money_trusts, merchant_receivables,
                         card_authorizations, cards, merchants, wallets, suspicious_transaction_reports, aml_alerts,
                         customer_interactions, sales_opportunities, customers
                RESTART IDENTITY CASCADE""").update();
        // ID の採番を戻すので、AML 監視の進捗も先頭に戻す
        jdbc.sql("UPDATE aml_scan_cursor SET last_transaction_id = 0, last_authorization_id = 0, last_foreign_id = 0")
                .update();
        jdbc.sql("UPDATE business_calendar SET current_business_date = :d")
                .param("d", INITIAL_BUSINESS_DATE).update();
        publisher.reset();
    }

    protected Account openAccount(String owner, long initialDeposit) {
        return openAccount(owner, "JPY", initialDeposit);
    }

    protected Account openAccount(String owner, String currency, long initialDeposit) {
        Account account = accountService.open(owner, currency, 0);
        if (initialDeposit > 0) {
            var result = movements.execute(MovementCommand.deposit(
                    "init-" + UUID.randomUUID(), account.id(), initialDeposit, currency, "initial", null));
            assertThat(result.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        }
        return accountService.get(account.id());
    }

    protected long balanceOf(long accountId) {
        return accountService.get(accountId).postedBalanceMinor();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }
}
