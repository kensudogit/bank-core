package com.example.bankcore.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "bank")
public record BankProperties(
        @DefaultValue Transfer transfer,
        @DefaultValue Outbox outbox,
        @DefaultValue Interest interest,
        @DefaultValue Closing closing) {

    /**
     * @param lockTimeoutMs 行ロック待ちの上限。application.yml の hikari.connection-init-sql で
     *                      接続ごとの {@code lock_timeout} に設定される（トランザクションごとの SET を省くため）
     */
    public record Transfer(
            @DefaultValue("5000") long lockTimeoutMs,
            @DefaultValue("3") int maxAttempts) {
    }

    public record Outbox(
            @DefaultValue("true") boolean schedulerEnabled,
            @DefaultValue("1000") long pollIntervalMs,
            @DefaultValue("100") int batchSize) {
    }

    /** @param annualRateBp 年利（ベーシスポイント。100 = 1.00%） */
    public record Interest(@DefaultValue("100") long annualRateBp) {
    }

    /**
     * @param parallelism      日次締めで利息計上を並行実行するスレッド数（DB 接続プールより十分小さくする）
     * @param approvalRequired true なら、照合に合格した締めを実行者とは別の人が承認するまで業務日付を進めない
     */
    public record Closing(@DefaultValue("4") int parallelism, @DefaultValue("false") boolean approvalRequired) {
    }
}
