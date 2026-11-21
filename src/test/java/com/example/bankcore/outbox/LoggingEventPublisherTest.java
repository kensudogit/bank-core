package com.example.bankcore.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ログ出力の配信先：イベントの識別情報と内容をログに残す。 */
@ExtendWith(OutputCaptureExtension.class)
class LoggingEventPublisherTest {

    @Test
    void logsEventIdentityAndPayload(CapturedOutput output) {
        UUID id = UUID.randomUUID();
        new LoggingEventPublisher().publish(new OutboxEvent(1, id, "Account", "42", "MoneyTransferred",
                "{\"amount\":100}", OffsetDateTime.now(), null, 0, null));
        assertThat(output.getAll()).contains(id.toString()).contains("MoneyTransferred").contains("Account:42")
                .contains("{\"amount\":100}");
    }
}
