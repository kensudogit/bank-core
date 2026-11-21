package com.example.bankcore.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Outbox に記録されたイベント。eventId は配信先での重複排除キーになる。
 */
public record OutboxEvent(
        long id,
        UUID eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        int attempts,
        String lastError) {
}
