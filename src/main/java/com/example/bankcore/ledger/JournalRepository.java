package com.example.bankcore.ledger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JournalRepository {

    /** 口座明細 1 行（顧客から見た入出金）。 */
    public record StatementLine(
            long journalEntryId, long transactionId, LocalDate businessDate, String description,
            long debitMinor, long creditMinor, String currency, OffsetDateTime postedAt) {
    }

    /** 仕訳明細の参照用。 */
    public record PostedLine(
            long journalEntryId, int lineNo, String glCode, Long accountId,
            long debitMinor, long creditMinor, String currency, Long loanId) {
    }

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 仕訳ヘッダと明細を 1 文（データ変更 CTE）で記帳する。業務日付は business_calendar から同じ文の中で読む。
     * 往復は 1 回で、明細の件数が増えても変わらない。
     *
     * @return 仕訳 ID
     */
    public long insertEntryWithLines(long transactionId, String description, List<JournalLine> lines) {
        StringBuilder values = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        params.put("tx", transactionId);
        params.put("description", description);
        for (int i = 0; i < lines.size(); i++) {
            JournalLine line = lines.get(i);
            values.append(i == 0 ? "" : ", ").append("""
                    (%d, CAST(:gl%d AS varchar), CAST(:account%d AS bigint), CAST(:debit%d AS bigint),
                     CAST(:credit%d AS bigint), CAST(:currency%d AS char(3)), CAST(:loan%d AS bigint))"""
                    .formatted(i + 1, i, i, i, i, i, i));
            params.put("gl" + i, line.glCode());
            params.put("account" + i, line.accountId());
            params.put("debit" + i, line.debitMinor());
            params.put("credit" + i, line.creditMinor());
            params.put("currency" + i, line.currency());
            params.put("loan" + i, line.loanId());
        }
        return jdbc.sql("""
                        WITH entry AS (
                            INSERT INTO journal_entries (transaction_id, business_date, description)
                            SELECT :tx, current_business_date, :description FROM business_calendar WHERE id = 1
                            RETURNING id)
                        INSERT INTO journal_lines
                            (journal_entry_id, line_no, gl_code, account_id, debit_minor, credit_minor, currency, loan_id)
                        SELECT entry.id, v.line_no, v.gl_code, v.account_id, v.debit, v.credit, v.currency, v.loan_id
                          FROM entry, (VALUES %s) AS v(line_no, gl_code, account_id, debit, credit, currency, loan_id)
                        RETURNING journal_entry_id""".formatted(values))
                .params(params)
                .query(Long.class)
                .list()
                .getFirst();
    }

    public List<PostedLine> linesForTransaction(long transactionId) {
        return jdbc.sql("""
                        SELECT l.journal_entry_id, l.line_no, l.gl_code, l.account_id,
                               l.debit_minor, l.credit_minor, l.currency, l.loan_id
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.journal_entry_id
                         WHERE e.transaction_id = :tx
                         ORDER BY l.line_no""")
                .param("tx", transactionId)
                .query((rs, i) -> new PostedLine(
                        rs.getLong("journal_entry_id"), rs.getInt("line_no"), rs.getString("gl_code"),
                        (Long) rs.getObject("account_id"), rs.getLong("debit_minor"),
                        rs.getLong("credit_minor"), rs.getString("currency"), (Long) rs.getObject("loan_id")))
                .list();
    }

    public List<StatementLine> statement(long accountId) {
        return jdbc.sql("""
                        SELECT e.id AS entry_id, e.transaction_id, e.business_date, e.description,
                               l.debit_minor, l.credit_minor, l.currency, e.posted_at
                          FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.journal_entry_id
                         WHERE l.account_id = :account AND l.gl_code = '2000'
                         ORDER BY e.id""")
                .param("account", accountId)
                .query((rs, i) -> new StatementLine(
                        rs.getLong("entry_id"), rs.getLong("transaction_id"),
                        rs.getObject("business_date", LocalDate.class), rs.getString("description"),
                        rs.getLong("debit_minor"), rs.getLong("credit_minor"), rs.getString("currency"),
                        rs.getObject("posted_at", OffsetDateTime.class)))
                .list();
    }
}
