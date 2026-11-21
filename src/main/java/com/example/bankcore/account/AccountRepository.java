package com.example.bankcore.account;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AccountRepository {

    private static final String COLUMNS = """
            id, account_number, owner_name, currency, posted_balance_minor, held_balance_minor,
            overdraft_limit_minor, status, version, created_at, updated_at""";

    static final RowMapper<Account> MAPPER = (rs, i) -> new Account(
            rs.getLong("id"),
            rs.getString("account_number"),
            rs.getString("owner_name"),
            rs.getString("currency"),
            rs.getLong("posted_balance_minor"),
            rs.getLong("held_balance_minor"),
            rs.getLong("overdraft_limit_minor"),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String ownerName, String currency, long overdraftLimitMinor) {
        return jdbc.sql("""
                        INSERT INTO accounts (account_number, owner_name, currency, overdraft_limit_minor)
                        VALUES (lpad(nextval('account_number_seq')::text, 7, '0'), :owner, :currency, :overdraft)
                        RETURNING id""")
                .param("owner", ownerName)
                .param("currency", currency)
                .param("overdraft", overdraftLimitMinor)
                .query(Long.class)
                .single();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public boolean exists(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM accounts WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public List<Account> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts ORDER BY id").query(MAPPER).list();
    }

    /**
     * 悲観ロックで口座行を取得する。呼び出し側は必ず ID の昇順で 1 件ずつ呼ぶこと（デッドロック回避）。
     *
     * <p>{@code FOR UPDATE} ではなく {@code FOR NO KEY UPDATE} を使う。transactions / journal_lines から
     * accounts への外部キー検査は対象口座行に {@code FOR KEY SHARE} ロックを取る。{@code FOR UPDATE} は
     * これと競合するため、「両方が FK 検査で KEY SHARE を持ったまま互いの FOR UPDATE を待つ」デッドロックが
     * ID 昇順ロックでも発生する。キー列を変えない残高更新には NO KEY UPDATE で十分であり、
     * 口座更新同士は互いに排他されつつ FK 検査とは競合しない。</p>
     */
    public Optional<Account> lockById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** 存在する口座の件数（存在確認を 1 往復で済ませる）。 */
    public int countExisting(Collection<Long> ids) {
        return jdbc.sql("SELECT count(*) FROM accounts WHERE id IN (:ids)")
                .param("ids", ids)
                .query(Integer.class)
                .single();
    }

    /**
     * 複数口座を 1 文で ID 昇順にロックする。
     *
     * <p>{@code ORDER BY id ... FOR NO KEY UPDATE} の実行計画では、ソート済みの行を上から順にロックする
     * （LockRows ノードが Sort ノードの上にある）ため、口座を 1 件ずつ昇順にロックするのと同じ順序になり、
     * デッドロック回避の性質を保ったまま往復回数を減らせる。</p>
     */
    public Map<Long, Account> lockAllInIdOrder(Collection<Long> ids) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE")
                .param("ids", ids)
                .query(MAPPER)
                .list()
                .forEach(a -> locked.put(a.id(), a));
        return locked;
    }

    /** 複数口座の記帳済み残高を 1 文で増減する。ロック取得後にのみ呼ぶ。 */
    public void applyDeltas(Map<Long, Long> deltaByAccount) {
        StringBuilder values = new StringBuilder();
        Map<String, Object> params = new HashMap<>();
        int i = 0;
        for (Map.Entry<Long, Long> e : deltaByAccount.entrySet()) {
            values.append(i == 0 ? "" : ", ")
                    .append("(CAST(:id").append(i).append(" AS bigint), CAST(:d").append(i).append(" AS bigint))");
            params.put("id" + i, e.getKey());
            params.put("d" + i, e.getValue());
            i++;
        }
        int updated = jdbc.sql("""
                        UPDATE accounts a
                           SET posted_balance_minor = a.posted_balance_minor + v.delta,
                               version = a.version + 1,
                               updated_at = now()
                          FROM (VALUES %s) AS v(id, delta)
                         WHERE a.id = v.id""".formatted(values))
                .params(params)
                .update();
        if (updated != deltaByAccount.size()) {
            throw new IllegalStateException("expected " + deltaByAccount.size() + " account updates but got " + updated);
        }
    }

    public void updateStatus(long id, AccountStatus status) {
        jdbc.sql("UPDATE accounts SET status = :status, version = version + 1, updated_at = now() WHERE id = :id")
                .param("status", status.name())
                .param("id", id)
                .update();
    }
}
