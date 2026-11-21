package com.example.bankcore.common;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 設定値のバインド：未指定なら既定値、指定すれば上書き。 */
class BankPropertiesTest {

    private static BankProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("bank", BankProperties.class);
    }

    @Test
    void defaultsWhenNothingConfigured() {
        BankProperties p = bind(Map.of());
        assertThat(p.transfer().lockTimeoutMs()).isEqualTo(5000);
        assertThat(p.transfer().maxAttempts()).isEqualTo(3);
        assertThat(p.outbox().schedulerEnabled()).isTrue();
        assertThat(p.outbox().pollIntervalMs()).isEqualTo(1000);
        assertThat(p.outbox().batchSize()).isEqualTo(100);
        assertThat(p.interest().annualRateBp()).isEqualTo(100);
        assertThat(p.closing().parallelism()).isEqualTo(4);
        assertThat(p.closing().approvalRequired()).isFalse();
    }

    @Test
    void overridesFromKebabCaseKeys() {
        BankProperties p = bind(Map.of(
                "bank.transfer.max-attempts", "5",
                "bank.outbox.scheduler-enabled", "false",
                "bank.interest.annual-rate-bp", "25",
                "bank.closing.parallelism", "8",
                "bank.closing.approval-required", "true"));
        assertThat(p.transfer().maxAttempts()).isEqualTo(5);
        assertThat(p.outbox().schedulerEnabled()).isFalse();
        assertThat(p.interest().annualRateBp()).isEqualTo(25);
        assertThat(p.closing().parallelism()).isEqualTo(8);
        assertThat(p.closing().approvalRequired()).isTrue();
    }
}
