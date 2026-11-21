package com.example.bankcore.outbox;

import com.example.bankcore.common.BankProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Outbox のポーリング配信。
 *
 * <p>配信は at-least-once：publish 成功後・コミット前にプロセスが落ちると、
 * 次回同じイベントが再送される。したがって受信側は eventId で重複排除する
 * （{@link ProcessedEventRepository} 参照）。</p>
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final int batchSize;

    public OutboxDispatcher(OutboxRepository outbox, EventPublisher publisher, BankProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = properties.outbox().batchSize();
    }

    /** @return 配信に成功した件数 */
    @Transactional
    public int dispatchBatch() {
        List<OutboxEvent> events = outbox.lockUnpublished(batchSize);
        List<Long> published = new ArrayList<>();
        for (OutboxEvent event : events) {
            try {
                publisher.publish(event);
                published.add(event.id());
            } catch (Exception e) {
                log.warn("failed to publish outbox event id={} type={}: {}", event.eventId(), event.eventType(), e.toString());
                outbox.markFailed(event.id(), e.toString());
            }
        }
        // 行ロックはコミットまで保持しているので、まとめて 1 文で配信済みにしても他のディスパッチャとは競合しない
        outbox.markPublished(published);
        return published.size();
    }
}
