package com.example.bankcore.ledger;

/** 勘定科目コード（V1 マイグレーションの gl_accounts と一致させる）。 */
public final class GlCode {

    /** 現金（資産）。借方で増加。 */
    public static final String CASH = "1000";

    /** 顧客預金（負債）。貸方で増加。口座 ID を補助科目として持つ。 */
    public static final String CUSTOMER_DEPOSITS = "2000";

    /** 支払利息（費用）。借方で増加。 */
    public static final String INTEREST_EXPENSE = "5000";

    /** 貸出金（資産）。借方で増加。融資 ID を補助科目として持つ。 */
    public static final String LOANS_RECEIVABLE = "1300";

    /** 貸倒引当金（資産の評価勘定）。貸方で増加。融資 ID を補助科目として持つ。 */
    public static final String LOAN_LOSS_ALLOWANCE = "1390";

    /** 貸出金利息（収益）。貸方で増加。 */
    public static final String LOAN_INTEREST_INCOME = "4100";

    /** 遅延損害金（収益）。貸方で増加。 */
    public static final String LATE_FEE_INCOME = "4110";

    /** 貸倒引当金繰入額（費用）。借方で増加（引当の戻入れは貸方）。 */
    public static final String PROVISION_EXPENSE = "5100";

    /** 貸倒損失（費用）。償却額のうち引当金で賄えない部分。 */
    public static final String LOAN_LOSS_EXPENSE = "5110";

    private GlCode() {
    }
}
