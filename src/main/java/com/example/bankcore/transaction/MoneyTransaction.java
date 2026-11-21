package com.example.bankcore.transaction;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.OffsetDateTime;

/** 取引レコード（transactions テーブル）。冪等キーと結果を保持する。 */
public record MoneyTransaction(
        long id,
        String idempotencyKey,
        @JsonIgnore String requestHash,
        TransactionType type,
        Long sourceAccountId,
        Long targetAccountId,
        long amountMinor,
        String currency,
        String memo,
        TransactionStatus status,
        RejectReason rejectReason,
        String correlationId,
        Long reversesTransactionId,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        Long loanId) {
}
