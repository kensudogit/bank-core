package com.example.bankcore.ledger;

/**
 * 仕訳明細 1 行。借方・貸方のどちらか一方だけが正の値を持つ。
 *
 * @param accountId 顧客預金科目の補助元帳（口座）。現金科目など補助を持たない科目では null。
 * @param loanId    貸出金・貸倒引当金科目の補助元帳（融資）。それ以外の科目では null。
 */
public record JournalLine(String glCode, Long accountId, long debitMinor, long creditMinor, String currency,
                          Long loanId) {

    public JournalLine {
        if (debitMinor < 0 || creditMinor < 0) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        if ((debitMinor > 0) == (creditMinor > 0)) {
            throw new IllegalArgumentException("exactly one of debit or credit must be positive");
        }
    }

    /** 融資の補助科目を持たない明細（預金・現金・損益科目）。 */
    public JournalLine(String glCode, Long accountId, long debitMinor, long creditMinor, String currency) {
        this(glCode, accountId, debitMinor, creditMinor, currency, null);
    }

    public static JournalLine debit(String glCode, Long accountId, long amountMinor, String currency) {
        return new JournalLine(glCode, accountId, amountMinor, 0, currency);
    }

    public static JournalLine credit(String glCode, Long accountId, long amountMinor, String currency) {
        return new JournalLine(glCode, accountId, 0, amountMinor, currency);
    }

    /** 融資を補助科目に持つ借方（貸出金の増加、貸倒引当金の取崩し）。 */
    public static JournalLine debitLoan(String glCode, long loanId, long amountMinor, String currency) {
        return new JournalLine(glCode, null, amountMinor, 0, currency, loanId);
    }

    /** 融資を補助科目に持つ貸方（貸出金の回収、貸倒引当金の繰入れ）。 */
    public static JournalLine creditLoan(String glCode, long loanId, long amountMinor, String currency) {
        return new JournalLine(glCode, null, 0, amountMinor, currency, loanId);
    }
}
