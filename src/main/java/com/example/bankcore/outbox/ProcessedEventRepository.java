package com.example.bankcore.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 受信側の重複排除（Inbox パターン）。
 * 消費者は「業務処理」と「markProcessed」を同じトランザクションで行い、
 * false が返ったら（=処理済み）業務処理をスキップする。
 */
@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 初回なら true、既に処理済みなら false */
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.sql("""
                        INSERT INTO processed_events (consumer, event_id) VALUES (:consumer, :eventId)
                        ON CONFLICT DO NOTHING""")
                .param("consumer", consumer)
                .param("eventId", eventId)
                .update() == 1;
    }
}
