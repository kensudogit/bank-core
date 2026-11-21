package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;

import java.util.Optional;

/**
 * 口座の状態に依存する業務ルール。ロック取得後の最新の口座で評価する（純粋関数なので単体テスト可能）。
 */
public final class TransactionRules {

    private TransactionRules() {
    }

    /**
     * @param source 出金側口座（入金時は null）
     * @param target 入金側口座（出金時は null）
     * @return 拒否理由。問題なければ empty。
     */
    public static Optional<RejectReason> check(Account source, Account target, long amountMinor, String currency) {
        if (source != null && !source.isActive()) {
            return Optional.of(RejectReason.SOURCE_ACCOUNT_NOT_ACTIVE);
        }
        if (target != null && !target.isActive()) {
            return Optional.of(RejectReason.TARGET_ACCOUNT_NOT_ACTIVE);
        }
        if ((source != null && !source.currency().equals(currency))
                || (target != null && !target.currency().equals(currency))) {
            return Optional.of(RejectReason.CURRENCY_MISMATCH);
        }
        // 記帳残高ではなく「支払可能額（拘束額控除・貸越枠加算）」で判定する
        if (source != null && source.availableBalanceMinor() < amountMinor) {
            return Optional.of(RejectReason.INSUFFICIENT_FUNDS);
        }
        return Optional.empty();
    }
}
