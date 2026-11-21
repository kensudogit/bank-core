package com.example.bankcore.outbox;

import com.example.bankcore.support.RecordingEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配信先の契約：配信に失敗したら例外を投げる（Dispatcher はそれを見て再送する）。 */
class EventPublisherTest {

    static OutboxEvent event(long id) {
        return new OutboxEvent(id, UUID.randomUUID(), "Account", "1", "Deposited", "{}", OffsetDateTime.now(), null, 0, null);
    }

    @Test
    void implementationsMayBeLambdasAndSignalFailureByThrowing() {
        EventPublisher failing = e -> {
            throw new java.io.IOException("broker down");
        };
        assertThatThrownBy(() -> failing.publish(event(1))).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void recordingImplementationKeepsOrderAndCanFailOnDemand() {
        RecordingEventPublisher p = new RecordingEventPublisher();
        p.failNext(1);
        assertThatThrownBy(() -> p.publish(event(1))).isInstanceOf(IllegalStateException.class);
        p.publish(event(2));
        p.publish(event(3));
        assertThat(p.published()).extracting(OutboxEvent::id).containsExactly(2L, 3L);
        p.reset();
        assertThat(p.published()).isEmpty();
    }
}
