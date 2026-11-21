package com.example.bankcore.outbox;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** Outbox 配信：未配信を配信して記録し、失敗分は次回に再送する。 */
class OutboxDispatcherTest extends AbstractIntegrationTest {

    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository outbox;

    @Test
    void dispatchesPendingEventsOnce() {
        openAccount("A", 1_000);
        long pending = outbox.findAll().size();
        assertThat(pending).isPositive();

        assertThat(dispatcher.dispatchBatch()).isEqualTo(pending);
        assertThat(publisher.published()).hasSize((int) pending);
        assertThat(dispatcher.dispatchBatch()).isZero();
        assertThat(outbox.findAll()).allSatisfy(e -> assertThat(e.publishedAt()).isNotNull());
    }

    @Test
    void failedEventIsRetriedNextBatch() {
        Account a = openAccount("A", 1_000);
        int pending = outbox.findAll().size();
        publisher.failNext(1);

        assertThat(dispatcher.dispatchBatch()).isEqualTo(pending - 1);
        assertThat(outbox.findAll()).anySatisfy(e -> {
            assertThat(e.publishedAt()).isNull();
            assertThat(e.lastError()).contains("simulated broker outage");
        });
        assertThat(dispatcher.dispatchBatch()).isEqualTo(1);
        assertThat(outbox.findAll()).allSatisfy(e -> assertThat(e.publishedAt()).isNotNull());
        assertThat(a.id()).isPositive();
    }
}
