package com.example.bankcore.loan;

/**
 * 担保の種類と担保掛目（評価額のうち回収に充てられると見る割合）。
 * 値下がりや処分費用を見込んで、評価額そのものではなく掛目を掛けた額を保全額とする。
 */
public enum CollateralType {
    /** 不動産（抵当権）：掛目 70% */
    REAL_ESTATE(7000),
    /** 預金（質権）：掛目 100% */
    DEPOSIT(10000),
    /** 有価証券：価格変動が大きいので掛目 60% */
    SECURITIES(6000);

    private final int haircutBp;

    CollateralType(int haircutBp) {
        this.haircutBp = haircutBp;
    }

    public int haircutBp() {
        return haircutBp;
    }
}
