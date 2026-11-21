package com.example.bankcore.outbox;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Outbox イベント：未配信（publishedAt が null）の状態と値の同一性。 */
class OutboxEventTest {

    @Test
    void unpublishedEventHasNoPublishedAt() {
        OutboxEvent e = new OutboxEvent(1, UUID.randomUUID(), "Loan", "9", "LoanDisbursed", "{}", OffsetDateTime.now(),
                null, 0, null);
        assertThat(e.publishedAt()).isNull();
        assertThat(e.attempts()).isZero();
        assertThat(e.lastError()).isNull();
    }

    @Test
    void equalityByValue() {
        UUID id = UUID.randomUUID();
        OffsetDateTime t = OffsetDateTime.now();
        assertThat(new OutboxEvent(1, id, "A", "1", "T", "{}", t, null, 0, null))
                .isEqualTo(new OutboxEvent(1, id, "A", "1", "T", "{}", t, null, 0, null));
    }
}
