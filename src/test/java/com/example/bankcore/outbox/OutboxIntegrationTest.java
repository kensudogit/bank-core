package com.example.bankcore.outbox;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.support.Concurrently;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ProcessedEventRepository processedEvents;

    @Test
    void dispatcherPublishesPendingEventsAndMarksThemPublished() {
        Account alice = openAccount("Alice", 1_000); // DepositCompleted が 1 件
        Account bob = openAccount("Bob", 0);
        movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 100, "JPY", null, null));

        int sent = dispatcher.dispatchBatch();

        assertThat(sent).isEqualTo(2);
        assertThat(publisher.published()).extracting(OutboxEvent::eventType)
                .containsExactly("DepositCompleted", "TransferCompleted");
        assertThat(outbox.findAll()).allSatisfy(e -> assertThat(e.publishedAt()).isNotNull());
        assertThat(dispatcher.dispatchBatch()).isZero();
    }

    @Test
    void brokerOutageKeepsEventForRetry() {
        openAccount("Alice", 1_000);
        publisher.failNext(1);

        assertThat(dispatcher.dispatchBatch()).isZero();
        assertThat(outbox.findAll()).singleElement().satisfies(e -> {
            assertThat(e.publishedAt()).isNull();
            assertThat(e.attempts()).isEqualTo(1);
            assertThat(e.lastError()).contains("simulated broker outage");
        });

        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(outbox.findAll()).singleElement().satisfies(e -> {
            assertThat(e.publishedAt()).isNotNull();
            assertThat(e.attempts()).isEqualTo(2);
        });
        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    void crashAfterPublishBeforeCommitCausesRedeliveryThatConsumerDeduplicates() {
        openAccount("Alice", 1_000);

        // 1 回目：publish は成功したが、published_at を記録する前にクラッシュした想定
        //   → published_at を NULL に戻して再現する
        dispatcher.dispatchBatch();
        jdbc.sql("UPDATE outbox_events SET published_at = NULL").update();
        dispatcher.dispatchBatch();

        List<OutboxEvent> delivered = publisher.published();
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(0).eventId()).isEqualTo(delivered.get(1).eventId());

        // 受信側は eventId で重複排除する
        long applied = delivered.stream()
                .filter(e -> processedEvents.markProcessed("notification-service", e.eventId()))
                .count();
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void concurrentDispatchersDoNotPublishTheSameEventTwice() throws Exception {
        Account alice = openAccount("Alice", 100_000);
        Account bob = openAccount("Bob", 0);
        for (int i = 0; i < 49; i++) {
            movements.execute(MovementCommand.transfer(key(), alice.id(), bob.id(), 1, "JPY", null, null));
        }

        List<Integer> sentPerDispatcher = Concurrently.run(4, i -> {
            int total = 0;
            int n;
            while ((n = dispatcher.dispatchBatch()) > 0) {
                total += n;
            }
            return total;
        });

        assertThat(sentPerDispatcher.stream().mapToInt(Integer::intValue).sum()).isEqualTo(50);
        List<UUID> ids = publisher.published().stream().map(OutboxEvent::eventId).toList();
        assertThat(ids).hasSize(50).doesNotHaveDuplicates();
    }
}
