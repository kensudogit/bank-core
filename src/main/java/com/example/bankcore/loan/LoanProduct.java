package com.example.bankcore.loan;

/**
 * 融資商品。金額は円（JPY の最小通貨単位）、期間は月数、金利は bp。
 *
 * @param baseRateBp           基準金利（格付による上乗せ前）
 * @param requiresRealEstate   不動産担保が必須か（住宅ローン）
 * @param totalVolumeRule      総量規制（年収の 1/3 を超える貸付の禁止）の対象か（消費者向け無担保ローン）
 */
public enum LoanProduct {
    /** 住宅ローン：長期・不動産担保必須 */
    HOUSING(90, 60, 420, 1_000_000L, 100_000_000L, true, false),
    /** 事業性融資 */
    BUSINESS(180, 6, 120, 1_000_000L, 500_000_000L, false, false),
    /** 消費者ローン（無担保） */
    CONSUMER(800, 6, 84, 100_000L, 5_000_000L, false, true);

    private final int baseRateBp;
    private final int minTermMonths;
    private final int maxTermMonths;
    private final long minPrincipalMinor;
    private final long maxPrincipalMinor;
    private final boolean requiresRealEstate;
    private final boolean totalVolumeRule;

    LoanProduct(int baseRateBp, int minTermMonths, int maxTermMonths, long minPrincipalMinor, long maxPrincipalMinor,
                boolean requiresRealEstate, boolean totalVolumeRule) {
        this.baseRateBp = baseRateBp;
        this.minTermMonths = minTermMonths;
        this.maxTermMonths = maxTermMonths;
        this.minPrincipalMinor = minPrincipalMinor;
        this.maxPrincipalMinor = maxPrincipalMinor;
        this.requiresRealEstate = requiresRealEstate;
        this.totalVolumeRule = totalVolumeRule;
    }

    public int baseRateBp() {
        return baseRateBp;
    }

    public int minTermMonths() {
        return minTermMonths;
    }

    public int maxTermMonths() {
        return maxTermMonths;
    }

    public long minPrincipalMinor() {
        return minPrincipalMinor;
    }

    public long maxPrincipalMinor() {
        return maxPrincipalMinor;
    }

    public boolean requiresRealEstate() {
        return requiresRealEstate;
    }

    public boolean totalVolumeRule() {
        return totalVolumeRule;
    }
}
