package com.example.bankcore.common;

import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/** 例外から HTTP ステータス（RFC 9457 Problem Details）への対応。 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void invalidRequestIs400WithMessage() {
        ProblemDetail p = handler.invalid(new InvalidRequestException("amountMinor must be positive"));
        assertThat(p.getStatus()).isEqualTo(400);
        assertThat(p.getDetail()).isEqualTo("amountMinor must be positive");
    }

    @Test
    void notFoundIs404() {
        assertThat(handler.notFound(new NotFoundException("account 1 not found")).getStatus()).isEqualTo(404);
    }

    @Test
    void idempotencyAndBusinessConflictsAre409() {
        ProblemDetail p = handler.conflict(new IdempotencyConflictException("k1"));
        assertThat(p.getStatus()).isEqualTo(409);
        assertThat(p.getDetail()).contains("k1");
        assertThat(handler.businessConflict(new BusinessConflictException("loan 1 is CLOSED")).getStatus()).isEqualTo(409);
    }

    @Test
    void lockFailureIs503AndTellsClientToRetryWithSameKey() {
        ProblemDetail p = handler.busy(new PessimisticLockingFailureException("lock timeout"));
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getDetail()).contains("Idempotency-Key");
    }
}
