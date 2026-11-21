package com.example.bankcore.support;

import com.example.bankcore.outbox.EventPublisher;
import com.example.bankcore.outbox.OutboxEvent;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** テスト用の配信先。受け取ったイベントを記録し、指定回数だけ失敗させることもできる。 */
@TestComponent
@Primary
public class RecordingEventPublisher implements EventPublisher {

    private final List<OutboxEvent> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();

    @Override
    public void publish(OutboxEvent event) {
        if (failuresRemaining.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new IllegalStateException("simulated broker outage");
        }
        published.add(event);
    }

    public List<OutboxEvent> published() {
        return List.copyOf(published);
    }

    public void failNext(int times) {
        failuresRemaining.set(times);
    }

    public void reset() {
        published.clear();
        failuresRemaining.set(0);
    }
}
