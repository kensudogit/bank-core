package com.example.bankcore.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxEvent> MAPPER = (rs, i) -> new OutboxEvent(
            rs.getLong("id"),
            rs.getObject("event_id", UUID.class),
            rs.getString("aggregate_type"),
            rs.getString("aggregate_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("published_at", OffsetDateTime.class),
            rs.getInt("attempts"),
            rs.getString("last_error"));

    private static final String COLUMNS =
            "id, event_id, aggregate_type, aggregate_id, event_type, payload::text AS payload, "
                    + "created_at, published_at, attempts, last_error";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 業務データと同じトランザクションでイベントを記録する。
     * MANDATORY にしておくことで「トランザクション外で Outbox に書く」誤用を防ぐ。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType, Map<String, ?> payload) {
        UUID eventId = UUID.randomUUID();
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not serializable", e);
        }
        jdbc.sql("""
                        INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:eventId, :aggType, :aggId, :type, CAST(:payload AS jsonb))""")
                .param("eventId", eventId)
                .param("aggType", aggregateType)
                .param("aggId", aggregateId)
                .param("type", eventType)
                .param("payload", json)
                .update();
        return eventId;
    }

    /**
     * 未配信イベントをロックして取得する。SKIP LOCKED により複数インスタンスの
     * ディスパッチャが同じイベントを同時に掴まない。
     */
    public List<OutboxEvent> lockUnpublished(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    /** 配信済みを 1 文でまとめて記録する（イベントごとの UPDATE を省く）。 */
    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1, last_error = NULL WHERE id IN (:ids)")
                .param("ids", ids)
                .update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("error", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 1000)))
                .param("id", id)
                .update();
    }

    public List<OutboxEvent> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events ORDER BY id").query(MAPPER).list();
    }

    public List<OutboxEvent> findByAggregate(String aggregateType, String aggregateId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox_events WHERE aggregate_type = :t AND aggregate_id = :id ORDER BY id")
                .param("t", aggregateType)
                .param("id", aggregateId)
                .query(MAPPER)
                .list();
    }
}
