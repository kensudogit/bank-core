package com.example.bankcore.outbox;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 受信側の重複排除：同じ受信者が同じイベントを処理するのは 1 回だけ。 */
class ProcessedEventRepositoryTest extends AbstractIntegrationTest {

    @Autowired ProcessedEventRepository processed;

    @Test
    void firstDeliveryIsProcessedDuplicatesAreNot() {
        UUID id = UUID.randomUUID();
        assertThat(processed.markProcessed("notifier", id)).isTrue();
        assertThat(processed.markProcessed("notifier", id)).isFalse();
    }

    @Test
    void differentConsumersAreIndependent() {
        UUID id = UUID.randomUUID();
        assertThat(processed.markProcessed("notifier", id)).isTrue();
        assertThat(processed.markProcessed("analytics", id)).isTrue();
        assertThat(count("processed_events")).isEqualTo(2);
    }
}
