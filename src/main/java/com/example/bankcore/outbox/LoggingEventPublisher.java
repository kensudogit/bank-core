package com.example.bankcore.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 既定の配信先：ログに出すだけ。実運用では Kafka / MQ 等の実装に差し替える
 * （差し替える Bean に @Primary を付けるか、このクラスを置き換える）。
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("publish event id={} type={} aggregate={}:{} payload={}",
                event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId(), event.payload());
    }
}
