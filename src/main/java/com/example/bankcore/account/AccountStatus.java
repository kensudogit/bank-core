package com.example.bankcore.account;

public enum AccountStatus {
    /** 通常 */
    ACTIVE,
    /** 凍結（入出金とも不可） */
    FROZEN,
    /** 解約済み */
    CLOSED
}
