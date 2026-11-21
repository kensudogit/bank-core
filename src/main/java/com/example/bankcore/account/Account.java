package com.example.bankcore.account;

import java.time.OffsetDateTime;

/**
 * 預金口座。
 *
 * <ul>
 *   <li>postedBalance: 記帳済み残高</li>
 *   <li>heldBalance: 拘束額（差押え・仮押さえ等）</li>
 *   <li>overdraftLimit: 当座貸越枠</li>
 *   <li>availableBalance = posted - held + overdraftLimit（支払可能額）</li>
 * </ul>
 */
public record Account(
        long id,
        String accountNumber,
        String ownerName,
        String currency,
        long postedBalanceMinor,
        long heldBalanceMinor,
        long overdraftLimitMinor,
        AccountStatus status,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public long availableBalanceMinor() {
        return Math.addExact(Math.subtractExact(postedBalanceMinor, heldBalanceMinor), overdraftLimitMinor);
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
