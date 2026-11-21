package com.example.bankcore.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 定期配信：配信の例外で定期実行を止めないこと、設定で無効化できること。 */
class OutboxSchedulerTest {

    @Test
    void pollDelegatesToDispatcher() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        when(dispatcher.dispatchBatch()).thenReturn(3);
        new OutboxScheduler(dispatcher).poll();
        verify(dispatcher, times(1)).dispatchBatch();
    }

    @Test
    void pollSwallowsFailuresSoTheScheduleKeepsRunning() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        when(dispatcher.dispatchBatch()).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> new OutboxScheduler(dispatcher).poll()).doesNotThrowAnyException();
    }

    @Test
    void enabledByDefaultAndDisabledByProperty() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(OutboxDispatcher.class, () -> mock(OutboxDispatcher.class))
                .withUserConfiguration(OutboxScheduler.class);
        runner.run(ctx -> assertThat(ctx).hasSingleBean(OutboxScheduler.class));
        runner.withPropertyValues("bank.outbox.scheduler-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OutboxScheduler.class));
    }
}
