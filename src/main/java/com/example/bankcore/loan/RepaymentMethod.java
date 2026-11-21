package com.example.bankcore.loan;

/** 返済方法。 */
public enum RepaymentMethod {
    /** 元利均等返済：毎回の返済額（元金 + 利息）が一定。利息が先に多く、元金の減りは遅い */
    EQUAL_PAYMENT,
    /** 元金均等返済：毎回の元金が一定。利息は残高に応じて減るので、返済額は回を追うごとに減る */
    EQUAL_PRINCIPAL
}
