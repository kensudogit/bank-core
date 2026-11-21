package com.example.bankcore.transaction;

import com.example.bankcore.common.Exceptions.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 資金移動コマンド（振込・入金・出金・利息・取消の共通形）。
 *
 * <p>sourceAccountId は「残高が減る口座（顧客預金の借方）」、targetAccountId は「残高が増える口座（貸方）」。</p>
 *
 * @param correlationId          追跡用 ID。リクエスト内容ではないので要求ハッシュには含めない。
 * @param reversesTransactionId  取消（REVERSAL）の場合の元取引 ID
 */
public record MovementCommand(
        TransactionType type,
        String idempotencyKey,
        Long sourceAccountId,
        Long targetAccountId,
        long amountMinor,
        String currency,
        String memo,
        String correlationId,
        Long reversesTransactionId) {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("[A-Z]{3}");

    public static MovementCommand transfer(String key, long source, long target, long amount, String currency,
                                           String memo, String correlationId) {
        return new MovementCommand(TransactionType.TRANSFER, key, source, target, amount, currency, memo, correlationId, null);
    }

    public static MovementCommand deposit(String key, long target, long amount, String currency,
                                          String memo, String correlationId) {
        return new MovementCommand(TransactionType.DEPOSIT, key, null, target, amount, currency, memo, correlationId, null);
    }

    public static MovementCommand withdrawal(String key, long source, long amount, String currency,
                                             String memo, String correlationId) {
        return new MovementCommand(TransactionType.WITHDRAWAL, key, source, null, amount, currency, memo, correlationId, null);
    }

    public static MovementCommand interest(String key, long target, long amount, String currency, String memo) {
        return new MovementCommand(TransactionType.INTEREST, key, null, target, amount, currency, memo, null, null);
    }

    /** 元取引から取消コマンドを組み立てる（口座の向きを入れ替える）。 */
    public static MovementCommand reversalOf(MoneyTransaction original, String key, String correlationId) {
        return new MovementCommand(TransactionType.REVERSAL, key,
                original.targetAccountId(), original.sourceAccountId(),
                original.amountMinor(), original.currency(),
                "取消 取引#" + original.id(), correlationId, original.id());
    }

    /** 口座の状態に依存しない入力検証（違反は 400、冪等レコードは作らない）。 */
    public void validate() {
        if (idempotencyKey == null || !KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new InvalidRequestException("Idempotency-Key must be 1-100 chars of [A-Za-z0-9._:-]");
        }
        if (amountMinor <= 0) {
            throw new InvalidRequestException("amountMinor must be positive");
        }
        if (currency == null || !CURRENCY_PATTERN.matcher(currency).matches()) {
            throw new InvalidRequestException("currency must be an ISO 4217 code such as JPY");
        }
        if (memo != null && memo.length() > 200) {
            throw new InvalidRequestException("memo must be at most 200 characters");
        }
        switch (type) {
            case TRANSFER -> {
                if (sourceAccountId == null || targetAccountId == null) {
                    throw new InvalidRequestException("source and target accounts are required");
                }
                if (sourceAccountId.equals(targetAccountId)) {
                    throw new InvalidRequestException("source and target accounts must differ");
                }
            }
            case DEPOSIT, INTEREST -> {
                if (targetAccountId == null || sourceAccountId != null) {
                    throw new InvalidRequestException(type + " requires only a target account");
                }
            }
            case WITHDRAWAL -> {
                if (sourceAccountId == null || targetAccountId != null) {
                    throw new InvalidRequestException("withdrawal requires only a source account");
                }
            }
            case REVERSAL -> {
                if (reversesTransactionId == null || (sourceAccountId == null && targetAccountId == null)) {
                    throw new InvalidRequestException("reversal requires an original transaction");
                }
            }
            // 融資・為替などの取引は、それぞれの業務 API からのみ実行する
            default -> throw new InvalidRequestException(type + " must be executed through its business API");
        }
    }

    /**
     * 正規化したリクエスト内容の SHA-256。
     * 同じ冪等キーで内容が違うリクエストを検出するために保存する。
     */
    public String requestHash() {
        String canonical = String.join("|",
                type.name(),
                Objects.toString(sourceAccountId, ""),
                Objects.toString(targetAccountId, ""),
                Long.toString(amountMinor),
                currency,
                Objects.toString(memo, ""),
                Objects.toString(reversesTransactionId, ""));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
