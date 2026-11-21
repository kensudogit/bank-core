package com.example.bankcore.transaction;

public enum RejectReason {
    SOURCE_ACCOUNT_NOT_ACTIVE,
    TARGET_ACCOUNT_NOT_ACTIVE,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    /** 返済額が、期日到来分と残りの元金の合計を超えている */
    AMOUNT_EXCEEDS_LOAN_BALANCE
}
