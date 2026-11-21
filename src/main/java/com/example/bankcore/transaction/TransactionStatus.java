package com.example.bankcore.transaction;

public enum TransactionStatus {
    /** 処理中。同一 DB トランザクション内でのみ存在し、コミットされることはない。 */
    PENDING,
    /** 記帳完了 */
    COMPLETED,
    /** 業務ルールにより拒否（資金不足・凍結など）。残高・仕訳は変化しない。 */
    REJECTED
}
