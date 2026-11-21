package com.example.bankcore.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 照合（リコンサイル）。
 * <ol>
 *   <li>全仕訳が通貨ごとに貸借一致しているか</li>
 *   <li>口座残高（accounts.posted_balance_minor）が、顧客預金科目の仕訳から導出した残高と一致するか</li>
 *   <li>試算表：全科目の借方合計 = 貸方合計</li>
 *   <li>融資の貸出金残高・貸倒引当金（loans）が、貸出金・貸倒引当金科目の仕訳から導出した額と一致するか</li>
 *   <li>為替の経過勘定（未決済為替借・仮受金・外国送金未決済）が、未決済の送金明細の合計と一致するか</li>
 * </ol>
 * REPEATABLE READ で 1 つのスナップショットから読み、照合中の記帳による誤検知を防ぐ。
 */
@Service
public class ReconciliationService {

    public record UnbalancedJournal(long journalEntryId, String currency, long debitMinor, long creditMinor) {
    }

    public record AccountMismatch(long accountId, long accountBalanceMinor, long ledgerBalanceMinor) {
    }

    public record TrialBalanceRow(String glCode, String currency, long debitMinor, long creditMinor) {
    }

    /** 経過勘定の残高と、それを構成する明細（送金など）の合計の不一致。 */
    public record SubledgerMismatch(String glCode, String currency, long ledgerBalanceMinor, long subledgerTotalMinor) {
    }

    public record LoanMismatch(long loanId, long outstandingMinor, long ledgerOutstandingMinor,
                               long provisionMinor, long ledgerProvisionMinor) {
    }

    public record Report(
            boolean ok,
            List<UnbalancedJournal> unbalancedJournals,
            List<AccountMismatch> accountMismatches,
            List<TrialBalanceRow> trialBalance,
            List<LoanMismatch> loanMismatches,
            List<SubledgerMismatch> subledgerMismatches) {
    }

    private final JdbcClient jdbc;

    public ReconciliationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report run() {
        List<UnbalancedJournal> unbalanced = jdbc.sql("""
                        SELECT journal_entry_id, currency, SUM(debit_minor) AS d, SUM(credit_minor) AS c
                          FROM journal_lines
                         GROUP BY journal_entry_id, currency
                        HAVING SUM(debit_minor) <> SUM(credit_minor)
                         ORDER BY journal_entry_id""")
                .query((rs, i) -> new UnbalancedJournal(
                        rs.getLong("journal_entry_id"), rs.getString("currency"), rs.getLong("d"), rs.getLong("c")))
                .list();

        // 負債科目なので 残高 = 貸方合計 - 借方合計
        List<AccountMismatch> mismatches = jdbc.sql("""
                        SELECT a.id, a.posted_balance_minor,
                               COALESCE(SUM(l.credit_minor - l.debit_minor), 0) AS ledger_balance
                          FROM accounts a
                          LEFT JOIN journal_lines l ON l.account_id = a.id AND l.gl_code = '2000'
                         GROUP BY a.id, a.posted_balance_minor
                        HAVING a.posted_balance_minor <> COALESCE(SUM(l.credit_minor - l.debit_minor), 0)
                         ORDER BY a.id""")
                .query((rs, i) -> new AccountMismatch(
                        rs.getLong("id"), rs.getLong("posted_balance_minor"), rs.getLong("ledger_balance")))
                .list();

        List<TrialBalanceRow> trial = jdbc.sql("""
                        SELECT gl_code, currency, SUM(debit_minor) AS d, SUM(credit_minor) AS c
                          FROM journal_lines
                         GROUP BY gl_code, currency
                         ORDER BY gl_code, currency""")
                .query((rs, i) -> new TrialBalanceRow(
                        rs.getString("gl_code"), rs.getString("currency"), rs.getLong("d"), rs.getLong("c")))
                .list();

        boolean trialBalanced = trial.stream()
                .collect(java.util.stream.Collectors.groupingBy(TrialBalanceRow::currency,
                        java.util.stream.Collectors.summingLong(r -> r.debitMinor() - r.creditMinor())))
                .values().stream().allMatch(net -> net == 0);

        // 貸出金は資産なので 残高 = 借方 - 貸方、貸倒引当金は評価勘定なので 残高 = 貸方 - 借方
        List<LoanMismatch> loanMismatches = jdbc.sql("""
                        SELECT l.id, l.outstanding_minor, l.provision_minor,
                               COALESCE(SUM(j.debit_minor - j.credit_minor) FILTER (WHERE j.gl_code = '1300'), 0) AS lo,
                               COALESCE(SUM(j.credit_minor - j.debit_minor) FILTER (WHERE j.gl_code = '1390'), 0) AS lp
                          FROM loans l
                          LEFT JOIN journal_lines j ON j.loan_id = l.id
                         GROUP BY l.id, l.outstanding_minor, l.provision_minor
                        HAVING l.outstanding_minor <> COALESCE(SUM(j.debit_minor - j.credit_minor)
                                                                 FILTER (WHERE j.gl_code = '1300'), 0)
                            OR l.provision_minor <> COALESCE(SUM(j.credit_minor - j.debit_minor)
                                                               FILTER (WHERE j.gl_code = '1390'), 0)
                         ORDER BY l.id""")
                .query((rs, i) -> new LoanMismatch(rs.getLong("id"), rs.getLong("outstanding_minor"), rs.getLong("lo"),
                        rs.getLong("provision_minor"), rs.getLong("lp")))
                .list();

        // 経過勘定（負債）の残高 = 貸方 - 借方 と、未決済の明細の合計
        List<SubledgerMismatch> subledger = jdbc.sql("""
                        WITH gl AS (
                            -- 資産（有価証券）は 借方 − 貸方、負債は 貸方 − 借方
                            SELECT gl_code, currency,
                                   SUM(CASE WHEN gl_code = '1400' THEN debit_minor - credit_minor
                                            ELSE credit_minor - debit_minor END) AS bal
                              FROM journal_lines WHERE gl_code IN ('2400', '2500', '2410', '1400', '2420', '2600', '2430', '2440')
                             GROUP BY gl_code, currency),
                        sub AS (
                            SELECT '2400' AS gl_code, 'JPY' AS currency, SUM(amount_minor) AS total
                              FROM domestic_remittances WHERE direction = 'OUT' AND status IN ('PENDING', 'SENT')
                            UNION ALL
                            SELECT '2500', 'JPY', SUM(amount_minor)
                              FROM domestic_remittances WHERE direction = 'IN' AND status = 'HELD'
                            UNION ALL
                            SELECT '2410', currency, SUM(amount_minor)
                              FROM foreign_remittances WHERE status IN ('SENT', 'HELD') GROUP BY currency
                            UNION ALL
                            SELECT '1400', 'JPY', SUM(book_value_minor + valuation_adjustment_minor) FROM security_holdings
                            UNION ALL
                            SELECT '2420', 'JPY', SUM(amount_minor) FROM fund_orders WHERE side = 'SUBSCRIBE' AND NOT settled
                            UNION ALL
                            SELECT '2600', 'JPY', SUM(principal_minor) FROM money_trusts WHERE status = 'ACTIVE'
                            UNION ALL
                            SELECT '2430', 'JPY', SUM(gross_minor) FROM merchant_receivables WHERE NOT settled
                            UNION ALL
                            SELECT '2440', 'JPY', SUM(balance_minor) FROM wallets
                            UNION ALL
                            -- 口座の拘束額 - カードのオーソリで押さえている額（口座ごと。0 でなければ不一致）
                            SELECT 'HOLD:' || a.id, 'JPY', a.held_balance_minor - COALESCE((
                                       SELECT SUM(ca.amount_minor) FROM card_authorizations ca JOIN cards k ON k.id = ca.card_id
                                        WHERE k.account_id = a.id AND ca.status = 'AUTHORIZED'), 0)
                              FROM accounts a WHERE a.held_balance_minor > 0)
                        SELECT COALESCE(gl.gl_code, sub.gl_code) AS gl_code, COALESCE(gl.currency, sub.currency) AS currency,
                               COALESCE(gl.bal, 0) AS bal, COALESCE(sub.total, 0) AS total
                          FROM gl FULL JOIN sub ON sub.gl_code = gl.gl_code AND sub.currency = gl.currency
                         WHERE COALESCE(gl.bal, 0) <> COALESCE(sub.total, 0)
                         ORDER BY 1, 2""")
                .query((rs, i) -> new SubledgerMismatch(rs.getString("gl_code"), rs.getString("currency").trim(),
                        rs.getLong("bal"), rs.getLong("total")))
                .list();

        boolean ok = unbalanced.isEmpty() && mismatches.isEmpty() && trialBalanced && loanMismatches.isEmpty()
                && subledger.isEmpty();
        return new Report(ok, unbalanced, mismatches, trial, loanMismatches, subledger);
    }
}
