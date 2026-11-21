package com.example.bankcore.loan;

import com.example.bankcore.common.Exceptions.InvalidRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** 融資の更新系コマンドの共通処理（冪等キーの形式検証と要求ハッシュ）。 */
final class LoanCommands {

    /** 資金移動 API（{@code MovementCommand}）と同じ冪等キーの形式 */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");

    private LoanCommands() {
    }

    static void requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new InvalidRequestException("Idempotency-Key must be 1-100 chars of [A-Za-z0-9._:-]");
        }
    }

    /** 正規化した要求内容の SHA-256。同じ冪等キーで内容の違う要求を見分けるために保存する。 */
    static String hash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object p : parts) {
            canonical.append(Objects.toString(p, "")).append('|');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidRequestException(name + " is required");
        }
        if (value.length() > max) {
            throw new InvalidRequestException(name + " must be at most " + max + " characters");
        }
        return value.trim();
    }
}
