package com.example.bankcore.common;

import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 業務例外：非検査例外であり（ロールバック対象）、メッセージを保持する。 */
class ExceptionsTest {

    @Test
    void allAreUncheckedSoTransactionsRollBack() {
        assertThat(new InvalidRequestException("x")).isInstanceOf(RuntimeException.class).hasMessage("x");
        assertThat(new NotFoundException("y")).isInstanceOf(RuntimeException.class).hasMessage("y");
        assertThat(new BusinessConflictException("z")).isInstanceOf(RuntimeException.class).hasMessage("z");
    }

    @Test
    void idempotencyConflictNamesTheKey() {
        assertThat(new IdempotencyConflictException("abc"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Idempotency-Key 'abc' was already used with a different request");
    }
}
