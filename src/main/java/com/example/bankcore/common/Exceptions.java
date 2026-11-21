package com.example.bankcore.common;

/** アプリ共通の例外。HTTP ステータスへの対応は {@link ApiExceptionHandler} を参照。 */
public final class Exceptions {

    private Exceptions() {
    }

    /** 入力そのものが不正（400）。冪等レコードは作らない。 */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    /** 対象が存在しない（404）。 */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 現在の状態では実行できない操作（409）。例：取消済み取引の再取消。冪等キーは消費しない。 */
    public static class BusinessConflictException extends RuntimeException {
        public BusinessConflictException(String message) {
            super(message);
        }
    }

    /** 同じ冪等キーで異なる内容のリクエストが来た（409）。 */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("Idempotency-Key '" + key + "' was already used with a different request");
        }
    }
}
