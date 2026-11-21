package com.example.bankcore;

import com.example.bankcore.common.BankProperties;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** アプリケーション全体：コンテキストが起動し、Flyway の移行が最後まで適用されていること。 */
class BankCoreApplicationTest extends AbstractIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired BankProperties properties;

    @Test
    void contextLoadsWithConfigurationProperties() {
        assertThat(context.getBean(BankCoreApplication.class)).isNotNull();
        assertThat(properties.transfer().maxAttempts()).isPositive();
        assertThat(properties.closing().approvalRequired()).isFalse();   // テストでは承認なし
    }

    @Test
    void allMigrationsApplied() {
        Integer failed = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success").query(Integer.class).single();
        String latest = jdbc.sql("SELECT max(CAST(version AS int))::text FROM flyway_schema_history WHERE version IS NOT NULL")
                .query(String.class).single();
        assertThat(failed).isZero();
        assertThat(Integer.parseInt(latest)).isGreaterThanOrEqualTo(11);
    }
}
