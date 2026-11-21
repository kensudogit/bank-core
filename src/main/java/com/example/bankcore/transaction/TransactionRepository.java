package com.example.bankcore.transaction;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class TransactionRepository {

    private static final String COLUMNS = """
            id, idempotency_key, request_hash, type, source_account_id, target_account_id, amount_minor,
            currency, memo, status, reject_reason, correlation_id, reverses_transaction_id, created_at, completed_at,
            loan_id""";

    private static final RowMapper<MoneyTransaction> MAPPER = (rs, i) -> new MoneyTransaction(
            rs.getLong("id"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            TransactionType.valueOf(rs.getString("type")),
            (Long) rs.getObject("source_account_id"),
            (Long) rs.getObject("target_account_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("memo"),
            TransactionStatus.valueOf(rs.getString("status")),
            rs.getString("reject_reason") == null ? null : RejectReason.valueOf(rs.getString("reject_reason")),
            rs.getString("correlation_id"),
            (Long) rs.getObject("reverses_transaction_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            (Long) rs.getObject("loan_id"));

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 冪等キーの予約。
     *
     * <p>{@code ON CONFLICT DO NOTHING} は、同じキーを挿入した別トランザクションが未コミットの場合、
     * その結果（コミット／ロールバック）が確定するまで待つ。したがって同一キーの同時リクエストは
     * ここで直列化され、後続は「確定済みの結果」を読むか、先行がロールバックした場合は自分が予約できる。</p>
     *
     * @return 予約できた場合は新しい取引 ID、既に存在する場合は empty
     */
    public Optional<Long> tryReserve(MovementCommand cmd, String requestHash) {
        return jdbc.sql("""
                        INSERT INTO transactions
                            (idempotency_key, request_hash, type, source_account_id, target_account_id,
                             amount_minor, currency, memo, status, correlation_id, reverses_transaction_id)
                        VALUES (:key, :hash, :type, :source, :target, :amount, :currency, :memo, 'PENDING', :corr, :reverses)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id""")
                .param("key", cmd.idempotencyKey())
                .param("hash", requestHash)
                .param("type", cmd.type().name())
                .param("source", cmd.sourceAccountId())
                .param("target", cmd.targetAccountId())
                .param("amount", cmd.amountMinor())
                .param("currency", cmd.currency())
                .param("memo", cmd.memo())
                .param("corr", cmd.correlationId())
                .param("reverses", cmd.reversesTransactionId())
                .query(Long.class)
                .optional();
    }

    /**
     * 融資の取引の冪等キー予約（{@link #tryReserve} と同じ直列化の性質を持つ）。
     *
     * @param loanId 融資実行の予約時はまだ融資が無いので null（完了時に {@link #markCompleted(long, Long)} で設定する）
     * @return 予約できた場合は新しい取引 ID、既に存在する場合は empty
     */
    public Optional<Long> tryReserveLoan(TransactionType type, String key, String requestHash, Long sourceAccountId,
                                         Long targetAccountId, long amountMinor, String currency, String memo,
                                         String correlationId, Long loanId) {
        return jdbc.sql("""
                        INSERT INTO transactions
                            (idempotency_key, request_hash, type, source_account_id, target_account_id,
                             amount_minor, currency, memo, status, correlation_id, loan_id)
                        VALUES (:key, :hash, :type, :source, :target, :amount, :currency, :memo, 'PENDING', :corr, :loan)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        RETURNING id""")
                .param("key", key)
                .param("hash", requestHash)
                .param("type", type.name())
                .param("source", sourceAccountId)
                .param("target", targetAccountId)
                .param("amount", amountMinor)
                .param("currency", currency)
                .param("memo", memo)
                .param("corr", correlationId)
                .param("loan", loanId)
                .query(Long.class)
                .optional();
    }

    /** 予約後、ロックした最新状態で金額が確定する取引（償却など）の金額を確定させる。PENDING の間だけ変更できる。 */
    public void updatePendingAmount(long id, long amountMinor) {
        int updated = jdbc.sql("UPDATE transactions SET amount_minor = :amount WHERE id = :id AND status = 'PENDING'")
                .param("amount", amountMinor)
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("transaction " + id + " is not pending");
        }
    }

    public Optional<MoneyTransaction> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE idempotency_key = :key")
                .param("key", key)
                .query(MAPPER)
                .optional();
    }

    public Optional<MoneyTransaction> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<MoneyTransaction> findByAccount(long accountId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM transactions WHERE source_account_id = :a OR target_account_id = :a ORDER BY id")
                .param("a", accountId)
                .query(MAPPER)
                .list();
    }

    /**
     * 元取引の行ロック（取消の直列化用）。新しい取消行の外部キー検査が元取引行に FOR KEY SHARE を取るため、
     * 口座と同じ理由で FOR UPDATE ではなく FOR NO KEY UPDATE を使う。
     */
    public Optional<MoneyTransaction> lockById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transactions WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** 元取引に対する有効な（COMPLETED の）取消。 */
    public Optional<MoneyTransaction> findCompletedReversalOf(long originalId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM transactions WHERE reverses_transaction_id = :id AND status = 'COMPLETED'")
                .param("id", originalId)
                .query(MAPPER)
                .optional();
    }

    /** PENDING → COMPLETED。更新後の行を RETURNING で受け取り、再読込の往復を省く。 */
    public MoneyTransaction markCompleted(long id) {
        return jdbc.sql("UPDATE transactions SET status = 'COMPLETED', completed_at = now() "
                        + "WHERE id = :id AND status = 'PENDING' RETURNING " + COLUMNS)
                .param("id", id)
                .query(MAPPER)
                .single();
    }

    /** 融資の取引の完了。融資実行では、ここで作成した融資を紐づける。 */
    public MoneyTransaction markCompleted(long id, Long loanId) {
        return jdbc.sql("UPDATE transactions SET status = 'COMPLETED', completed_at = now(), "
                        + "loan_id = COALESCE(:loan, loan_id) WHERE id = :id AND status = 'PENDING' RETURNING " + COLUMNS)
                .param("loan", loanId)
                .param("id", id)
                .query(MAPPER)
                .single();
    }

    /** PENDING → REJECTED。更新後の行を返す。 */
    public MoneyTransaction markRejected(long id, RejectReason reason) {
        return jdbc.sql("UPDATE transactions SET status = 'REJECTED', reject_reason = :reason, completed_at = now() "
                        + "WHERE id = :id AND status = 'PENDING' RETURNING " + COLUMNS)
                .param("reason", reason.name())
                .param("id", id)
                .query(MAPPER)
                .single();
    }
}
