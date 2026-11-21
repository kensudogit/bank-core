package com.example.bankcore.loan;

/** 保証の種類と、保全額として認める割合。 */
public enum GuaranteeType {
    /** 保証会社による保証：100% 認める */
    GUARANTEE_COMPANY(10000),
    /** 個人保証：保証人の資力が不確かなので 50% だけ認める */
    INDIVIDUAL(5000);

    private final int recognitionBp;

    GuaranteeType(int recognitionBp) {
        this.recognitionBp = recognitionBp;
    }

    public int recognitionBp() {
        return recognitionBp;
    }
}
