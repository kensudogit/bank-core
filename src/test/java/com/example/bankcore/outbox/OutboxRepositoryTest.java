package com.example.bankcore.outbox;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outbox リポジトリ：業務トランザクション内での追記・未配信の取得・配信済み／失敗の記録。 */
class OutboxRepositoryTest extends AbstractIntegrationTest {

    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager txManager;

    private UUID append(String aggregateId, String type) {
        return new TransactionTemplate(txManager)
                .execute(s -> outbox.append("Account", aggregateId, type, Map.of("amount", 100, "memo", "日本語")));
    }

    private List<OutboxEvent> locked(int limit) {
        return new TransactionTemplate(txManager).execute(s -> outbox.lockUnpublished(limit));
    }

    @Test
    void appendRequiresAnOuterTransaction() {
        assertThatThrownBy(() -> outbox.append("Account", "1", "X", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void appendStoresJsonPayload() {
        UUID id = append("1", "Deposited");
        OutboxEvent e = outbox.findAll().getFirst();
        assertThat(e.eventId()).isEqualTo(id);
        assertThat(e.eventType()).isEqualTo("Deposited");
        assertThat(e.payload()).contains("\"amount\"").contains("日本語");
        assertThat(e.publishedAt()).isNull();
        assertThat(outbox.findByAggregate("Account", "1")).hasSize(1);
        assertThat(outbox.findByAggregate("Account", "2")).isEmpty();
    }

    @Test
    void markPublishedAndFailed() {
        append("1", "A");
        append("2", "B");
        List<OutboxEvent> pending = new TransactionTemplate(txManager).execute(s -> outbox.lockUnpublished(10));
        assertThat(pending).hasSize(2);

        outbox.markFailed(pending.get(1).id(), "x".repeat(2_000));
        outbox.markPublished(List.of(pending.get(0).id()));
        outbox.markPublished(List.of());   // 空なら何もしない

        List<OutboxEvent> all = outbox.findAll();
        assertThat(all.get(0).publishedAt()).isNotNull();
        assertThat(all.get(0).attempts()).isEqualTo(1);
        assertThat(all.get(1).publishedAt()).isNull();
        assertThat(all.get(1).lastError()).hasSize(1_000);
        assertThat(locked(10))
                .extracting(OutboxEvent::id).containsExactly(pending.get(1).id());
    }

    @Test
    void lockUnpublishedHonoursLimitAndOrder() {
        append("1", "A");
        append("2", "B");
        append("3", "C");
        assertThat(locked(2))
                .extracting(OutboxEvent::eventType).containsExactly("A", "B");
    }
}
