package com.example.bankcore.loan;

/**
 * 債務者区分（自己査定を延滞日数だけで簡略化したもの）と、区分ごとの貸倒引当率・デフォルト確率の下限。
 *
 * <ul>
 *   <li>正常先・要注意先・要管理先：貸出金残高全体に引当率を掛ける（一般貸倒引当金）</li>
 *   <li>破綻懸念先・実質破綻先：担保・保証で保全されていない部分に引当率を掛ける（個別貸倒引当金）</li>
 * </ul>
 * 要管理先以下を不良債権（リスク管理債権）として扱う。
 *
 * @param provisionRateBp 引当率
 * @param pdFloorBp       信用リスク計測で使うデフォルト確率の下限（格付の PD と大きい方を使う）
 */
public enum BorrowerClass {
    NORMAL("正常先", 50, 0, false),
    WATCH("要注意先", 300, 500, false),
    SPECIAL_ATTENTION("要管理先", 1500, 2000, false),
    DOUBTFUL("破綻懸念先", 7000, 5000, true),
    DEFACTO_BANKRUPT("実質破綻先", 10000, 10000, true);

    private final String label;
    private final int provisionRateBp;
    private final int pdFloorBp;
    private final boolean unsecuredBasis;

    BorrowerClass(String label, int provisionRateBp, int pdFloorBp, boolean unsecuredBasis) {
        this.label = label;
        this.provisionRateBp = provisionRateBp;
        this.pdFloorBp = pdFloorBp;
        this.unsecuredBasis = unsecuredBasis;
    }

    /** 延滞日数による区分：0 日 正常先 / 1〜89 日 要注意先 / 90〜179 日 要管理先 / 180〜364 日 破綻懸念先 / 365 日〜 実質破綻先。 */
    public static BorrowerClass fromDaysPastDue(int daysPastDue) {
        if (daysPastDue <= 0) {
            return NORMAL;
        }
        if (daysPastDue < 90) {
            return WATCH;
        }
        if (daysPastDue < 180) {
            return SPECIAL_ATTENTION;
        }
        if (daysPastDue < 365) {
            return DOUBTFUL;
        }
        return DEFACTO_BANKRUPT;
    }

    /**
     * 必要な貸倒引当金。
     *
     * @param outstandingMinor 貸出金残高
     * @param securedMinor     担保（掛目後）・保証で保全されている額
     */
    public long requiredProvision(long outstandingMinor, long securedMinor) {
        long basis = unsecuredBasis ? Math.max(0, outstandingMinor - securedMinor) : outstandingMinor;
        return Math.multiplyExact(basis, provisionRateBp) / 10_000;
    }

    /** 不良債権（要管理先以下）か。 */
    public boolean nonPerforming() {
        return compareTo(SPECIAL_ATTENTION) >= 0;
    }

    /** 償却（直接償却）の対象にできる区分か（破綻懸念先以下）。 */
    public boolean writeOffEligible() {
        return compareTo(DOUBTFUL) >= 0;
    }

    public String label() {
        return label;
    }

    public int provisionRateBp() {
        return provisionRateBp;
    }

    public int pdFloorBp() {
        return pdFloorBp;
    }
}
