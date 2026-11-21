package com.example.bankcore.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 一定間隔で Outbox を配信する。テストでは無効化して手動で dispatchBatch() を呼ぶ。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "bank.outbox", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxDispatcher dispatcher;

    public OutboxScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${bank.outbox.poll-interval-ms:1000}")
    public void poll() {
        try {
            int n = dispatcher.dispatchBatch();
            if (n > 0) {
                log.debug("dispatched {} outbox events", n);
            }
        } catch (RuntimeException e) {
            log.error("outbox dispatch failed", e);
        }
    }
}
