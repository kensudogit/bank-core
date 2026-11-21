package com.example.bankcore.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 取引レコード：API 応答にリクエストハッシュ（内部情報）を出さないこと。 */
class MoneyTransactionTest {

    @Test
    void requestHashIsNotSerialized() throws Exception {
        MoneyTransaction tx = new MoneyTransaction(1, "key-1", "secret-hash", TransactionType.DEPOSIT, null, 2L, 100, "JPY",
                "memo", TransactionStatus.COMPLETED, null, "corr", null, OffsetDateTime.now(), OffsetDateTime.now(), null);
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(tx);
        assertThat(json).doesNotContain("secret-hash").doesNotContain("requestHash")
                .contains("\"idempotencyKey\":\"key-1\"").contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void recordEqualityByValue() {
        OffsetDateTime t = OffsetDateTime.now();
        MoneyTransaction a = new MoneyTransaction(1, "k", "h", TransactionType.TRANSFER, 1L, 2L, 5, "JPY", null,
                TransactionStatus.PENDING, null, null, null, t, null, null);
        MoneyTransaction b = new MoneyTransaction(1, "k", "h", TransactionType.TRANSFER, 1L, 2L, 5, "JPY", null,
                TransactionStatus.PENDING, null, null, null, t, null, null);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
