package com.example.bankcore.outbox;

/**
 * Outbox から外部（Kafka / MQ / Webhook など）へイベントを送る口。
 * 例外を投げた場合は未配信のまま残り、次回ポーリングで再送される（at-least-once）。
 */
public interface EventPublisher {

    void publish(OutboxEvent event) throws Exception;
}
