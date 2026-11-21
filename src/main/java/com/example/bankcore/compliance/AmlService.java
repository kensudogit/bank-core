package com.example.bankcore.compliance;

import com.example.bankcore.account.AccountService;
import com.example.bankcore.account.AccountStatus;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * コンプライアンス：AML（マネー・ローンダリング対策）と不正取引の監視、アラートの案件管理、疑わしい取引の届出。
 *
 * <h2>監視ルール（取引は前回の続きから検査する）</h2>
 * <ul>
 *   <li>LARGE_CASH：200 万円以上の現金入出金</li>
 *   <li>STRUCTURING：7 日以内に 50〜200 万円未満の現金入金が 3 回以上・合計 200 万円以上（分割による基準回避の疑い）</li>
 *   <li>RAPID_MOVEMENT：100 万円以上の出金・送金で、直前 2 日の入金の 90% 以上を移動（通過口座の疑い）</li>
 *   <li>HIGH_RISK_CUSTOMER：リスク評価 HIGH の顧客の 100 万円以上の取引</li>
 *   <li>FOREIGN_SCREENING：海外送金が制裁リスト・高リスク国の照合で保留になった</li>
 *   <li>CARD_VELOCITY：同じカードで 10 分以内に 5 回以上のオーソリ（カードの不正利用の疑い）</li>
 *   <li>CARD_DECLINES：同じカードで 10 分以内に 3 回以上の否決（カード番号の総当たりなどの疑い）</li>
 * </ul>
 * アラートは OPEN → INVESTIGATING → CLOSED（問題なし）/ REPORTED（疑わしい取引の届出）。完了には理由が必要。
 * 届出時は顧客のリスク評価を HIGH にし、必要なら口座を凍結する。
 */
@Service
public class AmlService {

    private static final Logger log = LoggerFactory.getLogger(AmlService.class);

    public static final long LARGE_CASH = 2_000_000;
    public static final long STRUCT_MIN = 500_000;
    public static final long RAPID_MIN = 1_000_000;

    public record Alert(long id, String rule, Long customerId, Long accountId, String reference, int score, String detail,
                        String status, String assignedTo, String resolutionNote, OffsetDateTime createdAt) {
    }

    public record ScanReport(int transactionsScanned, int authorizationsScanned, int alertsCreated) {
    }

    private static final RowMapper<Alert> ALERT = (rs, i) -> new Alert(rs.getLong("id"), rs.getString("rule"),
            rs.getObject("customer_id") == null ? null : rs.getLong("customer_id"),
            rs.getObject("account_id") == null ? null : rs.getLong("account_id"), rs.getString("reference"),
            rs.getInt("score"), rs.getString("detail"), rs.getString("status"), rs.getString("assigned_to"),
            rs.getString("resolution_note"), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final AccountService accounts;

    public AmlService(JdbcClient jdbc, AccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    // ================================================================== 監視

    /**
     * 前回の続きから取引・オーソリ・海外送金を検査してアラートを作る。カーソル行をロックするので、
     * 複数インスタンスから同時に呼ばれても同じ範囲を二重に検査しない。アラートは（ルール, 参照）で一意。
     */
    @Transactional
    public ScanReport scan() {
        Map<String, Object> cursor = jdbc.sql("SELECT * FROM aml_scan_cursor WHERE id = 1 FOR UPDATE").query().singleRow();
        long lastTx = ((Number) cursor.get("last_transaction_id")).longValue();
        long lastAuth = ((Number) cursor.get("last_authorization_id")).longValue();
        long lastForeign = ((Number) cursor.get("last_foreign_id")).longValue();
        int created = 0;

        List<Map<String, Object>> txs = jdbc.sql("""
                        SELECT t.id, t.type, t.source_account_id, t.target_account_id, t.amount_minor, t.currency, t.created_at
                          FROM transactions t
                         WHERE t.id > :last AND t.status = 'COMPLETED' ORDER BY t.id LIMIT 5000""")
                .param("last", lastTx).query().listOfRows();
        long maxTx = lastTx;
        for (Map<String, Object> t : txs) {
            long id = ((Number) t.get("id")).longValue();
            maxTx = Math.max(maxTx, id);
            if ("JPY".equals(String.valueOf(t.get("currency")).trim())) {
                created += checkTransaction(t, id);
            }
        }

        List<Map<String, Object>> auths = jdbc.sql("""
                        SELECT id, card_id, status, created_at FROM card_authorizations WHERE id > :last ORDER BY id LIMIT 5000""")
                .param("last", lastAuth).query().listOfRows();
        long maxAuth = lastAuth;
        for (Map<String, Object> a : auths) {
            long id = ((Number) a.get("id")).longValue();
            maxAuth = Math.max(maxAuth, id);
            created += checkCard(a, id);
        }

        List<Map<String, Object>> held = jdbc.sql("""
                        SELECT id, account_id, screening_result FROM foreign_remittances
                         WHERE id > :last AND status = 'HELD' ORDER BY id""")
                .param("last", lastForeign).query().listOfRows();
        long maxForeign = jdbc.sql("SELECT COALESCE(MAX(id), :last) FROM foreign_remittances").param("last", lastForeign)
                .query(Long.class).single();
        for (Map<String, Object> f : held) {
            long accountId = ((Number) f.get("account_id")).longValue();
            created += alert("FOREIGN_SCREENING", accountId, "FR:" + f.get("id"), 80, String.valueOf(f.get("screening_result")));
        }

        jdbc.sql("""
                        UPDATE aml_scan_cursor SET last_transaction_id = :tx, last_authorization_id = :auth,
                               last_foreign_id = :foreign WHERE id = 1""")
                .param("tx", maxTx).param("auth", maxAuth).param("foreign", maxForeign).update();
        log.info("aml scan: transactions={} authorizations={} alerts={}", txs.size(), auths.size(), created);
        return new ScanReport(txs.size(), auths.size(), created);
    }

    private int checkTransaction(Map<String, Object> t, long txId) {
        String type = (String) t.get("type");
        long amount = ((Number) t.get("amount_minor")).longValue();
        Long source = t.get("source_account_id") == null ? null : ((Number) t.get("source_account_id")).longValue();
        Long target = t.get("target_account_id") == null ? null : ((Number) t.get("target_account_id")).longValue();
        OffsetDateTime at = (OffsetDateTime) jdbc.sql("SELECT created_at FROM transactions WHERE id = :id").param("id", txId)
                .query(OffsetDateTime.class).single();
        int n = 0;
        boolean cash = "DEPOSIT".equals(type) || "WITHDRAWAL".equals(type);
        Long cashAccount = "DEPOSIT".equals(type) ? target : source;
        if (cash && amount >= LARGE_CASH) {
            n += alert("LARGE_CASH", cashAccount, "TX:" + txId, 60,
                    String.format("現金%s %,d 円（200 万円以上）", "DEPOSIT".equals(type) ? "入金" : "出金", amount));
        }
        if ("DEPOSIT".equals(type) && amount >= STRUCT_MIN && amount < LARGE_CASH) {
            Map<String, Object> agg = jdbc.sql("""
                            SELECT count(*) AS n, COALESCE(SUM(amount_minor), 0) AS total FROM transactions
                             WHERE type = 'DEPOSIT' AND status = 'COMPLETED' AND target_account_id = :a
                               AND amount_minor >= :min AND amount_minor < :max
                               AND created_at > :at - interval '7 days' AND created_at <= :at""")
                    .param("a", target).param("min", STRUCT_MIN).param("max", LARGE_CASH).param("at", at)
                    .query().singleRow();
            long count = ((Number) agg.get("n")).longValue();
            long total = ((Number) agg.get("total")).longValue();
            if (count >= 3 && total >= LARGE_CASH) {
                n += alert("STRUCTURING", target, "STRUCT:" + target + ":" + at.toLocalDate(), 75,
                        String.format("7 日以内に 50〜200 万円未満の現金入金 %d 回・合計 %,d 円", count, total));
            }
        }
        boolean outflow = source != null && ("WITHDRAWAL".equals(type) || "TRANSFER".equals(type)
                || "REMITTANCE_OUT".equals(type) || "FOREIGN_REMITTANCE".equals(type));
        if (outflow && amount >= RAPID_MIN) {
            long inflow = jdbc.sql("""
                            SELECT COALESCE(SUM(amount_minor), 0) FROM transactions
                             WHERE target_account_id = :a AND status = 'COMPLETED' AND currency = 'JPY'
                               AND type IN ('DEPOSIT', 'TRANSFER', 'REMITTANCE_IN', 'LOAN_DISBURSEMENT')
                               AND created_at >= :at - interval '2 days' AND id < :id""")
                    .param("a", source).param("at", at).param("id", txId).query(Long.class).single();
            if (inflow >= RAPID_MIN && amount * 10 >= inflow * 9) {
                n += alert("RAPID_MOVEMENT", source, "RAPID:" + txId, 65,
                        String.format("直前 2 日の入金 %,d 円のうち %,d 円を移動", inflow, amount));
            }
        }
        for (Long account : new Long[]{source, target}) {
            if (account != null && amount >= RAPID_MIN && "HIGH".equals(customerRisk(account))) {
                n += alert("HIGH_RISK_CUSTOMER", account, "HRC:" + txId + ":" + account, 70,
                        String.format("高リスク顧客の取引 %,d 円（%s）", amount, type));
            }
        }
        return n;
    }

    private int checkCard(Map<String, Object> a, long authId) {
        long cardId = ((Number) a.get("card_id")).longValue();
        OffsetDateTime at = ((java.sql.Timestamp) a.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC);
        Map<String, Object> w = jdbc.sql("""
                        SELECT count(*) AS total, count(*) FILTER (WHERE status = 'DECLINED') AS declined
                          FROM card_authorizations
                         WHERE card_id = :c AND created_at > :at - interval '10 minutes' AND created_at <= :at AND id <= :id""")
                .param("c", cardId).param("at", at).param("id", authId).query().singleRow();
        long accountId = jdbc.sql("SELECT account_id FROM cards WHERE id = :c").param("c", cardId).query(Long.class).single();
        int n = 0;
        long window = at.toEpochSecond() / 600;   // 10 分ごとの枠で 1 件にまとめる
        if (((Number) w.get("total")).longValue() >= 5) {
            n += alert("CARD_VELOCITY", accountId, "CARDVEL:" + cardId + ":" + window, 70,
                    "10 分以内にオーソリ " + w.get("total") + " 回（カード#" + cardId + "）");
        }
        if (((Number) w.get("declined")).longValue() >= 3) {
            n += alert("CARD_DECLINES", accountId, "CARDDEC:" + cardId + ":" + window, 75,
                    "10 分以内に否決 " + w.get("declined") + " 回（カード#" + cardId + "）");
        }
        return n;
    }

    /** アラートを作る（同じルール・参照は 1 件だけ）。作成した場合 1。 */
    private int alert(String rule, Long accountId, String reference, int score, String detail) {
        Long customerId = accountId == null ? null : jdbc.sql("SELECT customer_id FROM accounts WHERE id = :a")
                .param("a", accountId).query(Long.class).optional().orElse(null);
        return jdbc.sql("""
                        INSERT INTO aml_alerts (rule, customer_id, account_id, reference, score, detail)
                        VALUES (:rule, :c, :a, :ref, :score, :detail)
                        ON CONFLICT ON CONSTRAINT uq_aml_alerts DO NOTHING""")
                .param("rule", rule).param("c", customerId).param("a", accountId).param("ref", reference)
                .param("score", score).param("detail", detail.length() > 400 ? detail.substring(0, 400) : detail)
                .update();
    }

    private String customerRisk(long accountId) {
        return jdbc.sql("SELECT c.aml_risk FROM customers c JOIN accounts a ON a.customer_id = c.id WHERE a.id = :a")
                .param("a", accountId).query(String.class).optional().orElse(null);
    }

    // ================================================================== 案件管理

    @Transactional
    public Alert investigate(long alertId, String investigator) {
        Alert a = lock(alertId);
        if (!"OPEN".equals(a.status())) {
            throw new BusinessConflictException("alert " + alertId + " is " + a.status());
        }
        jdbc.sql("UPDATE aml_alerts SET status = 'INVESTIGATING', assigned_to = :by, updated_at = now() WHERE id = :id")
                .param("by", text(investigator, "investigator", 50)).param("id", alertId).update();
        return get(alertId);
    }

    /** 調査の結果、問題なしとして完了する（理由が必要）。 */
    @Transactional
    public Alert close(long alertId, String by, String note) {
        Alert a = lock(alertId);
        if (!"OPEN".equals(a.status()) && !"INVESTIGATING".equals(a.status())) {
            throw new BusinessConflictException("alert " + alertId + " is already " + a.status());
        }
        jdbc.sql("""
                        UPDATE aml_alerts SET status = 'CLOSED', assigned_to = COALESCE(assigned_to, :by), resolution_note = :n,
                               updated_at = now() WHERE id = :id""")
                .param("by", text(by, "by", 50)).param("n", text(note, "note", 1000)).param("id", alertId).update();
        return get(alertId);
    }

    /**
     * 疑わしい取引の届出。届出の記録を残し、顧客のリスク評価を HIGH にする。
     *
     * @param freezeAccount true なら対象口座を凍結する（資金の流出を止める）
     */
    @Transactional
    public Alert report(long alertId, String by, String summary, boolean freezeAccount) {
        Alert a = lock(alertId);
        if (!"OPEN".equals(a.status()) && !"INVESTIGATING".equals(a.status())) {
            throw new BusinessConflictException("alert " + alertId + " is already " + a.status());
        }
        String who = text(by, "by", 50);
        String s = text(summary, "summary", 1000);
        jdbc.sql("""
                        UPDATE aml_alerts SET status = 'REPORTED', assigned_to = COALESCE(assigned_to, :by), resolution_note = :s,
                               updated_at = now() WHERE id = :id""")
                .param("by", who).param("s", s).param("id", alertId).update();
        jdbc.sql("""
                        INSERT INTO suspicious_transaction_reports (alert_id, customer_id, account_id, summary, reported_by)
                        VALUES (:a, :c, :acct, :s, :by)""")
                .param("a", alertId).param("c", a.customerId()).param("acct", a.accountId()).param("s", s).param("by", who)
                .update();
        if (a.customerId() != null) {
            jdbc.sql("UPDATE customers SET aml_risk = 'HIGH', updated_at = now() WHERE id = :c").param("c", a.customerId()).update();
        }
        if (freezeAccount && a.accountId() != null) {
            accounts.changeStatus(a.accountId(), AccountStatus.FROZEN);
        }
        return get(alertId);
    }

    @Transactional(readOnly = true)
    public List<Alert> alerts(String status) {
        return jdbc.sql("""
                        SELECT * FROM aml_alerts WHERE (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))
                         ORDER BY score DESC, id DESC LIMIT 500""")
                .param("s", status).query(ALERT).list();
    }

    @Transactional(readOnly = true)
    public Alert get(long id) {
        return jdbc.sql("SELECT * FROM aml_alerts WHERE id = :id").param("id", id).query(ALERT).optional()
                .orElseThrow(() -> new NotFoundException("alert " + id + " not found"));
    }

    private Alert lock(long id) {
        return jdbc.sql("SELECT * FROM aml_alerts WHERE id = :id FOR NO KEY UPDATE").param("id", id).query(ALERT).optional()
                .orElseThrow(() -> new NotFoundException("alert " + id + " not found"));
    }

    private static String text(String v, String name, int max) {
        if (v == null || v.isBlank() || v.length() > max) {
            throw new InvalidRequestException(name + " is required (max " + max + " characters)");
        }
        return v.trim();
    }
}
