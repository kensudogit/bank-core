package com.example.bankcore.crm;

import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * 顧客管理（CRM）：顧客台帳、口座との紐づけ、本人確認（KYC）、投資の適合性情報、接触履歴、営業案件。
 * 顧客の全体像（口座・融資・投資信託・カード・送金・AML アラート）は {@link Customer360Service}。
 */
@Service
public class CustomerService {

    public record Customer(long id, String customerNumber, String customerType, String name, String nameKana,
                           LocalDate birthDate, String occupation, Long annualIncomeMinor, int riskTolerance,
                           int investmentExperienceYears, String amlRisk, String kycStatus, String segment,
                           String relationshipManager, OffsetDateTime createdAt) {
    }

    public record NewCustomer(String customerType, String name, String nameKana, LocalDate birthDate, String occupation,
                              Long annualIncomeMinor, Integer riskTolerance, Integer investmentExperienceYears,
                              String relationshipManager) {
    }

    public record Interaction(long id, long customerId, String channel, String kind, String note, String staff,
                              OffsetDateTime occurredAt) {
    }

    public record Opportunity(long id, long customerId, String product, String description, Long amountMinor,
                              String stage, String owner, LocalDate expectedClose, OffsetDateTime updatedAt) {
    }

    private static final Set<String> STAGES = Set.of("LEAD", "PROPOSED", "NEGOTIATING", "WON", "LOST");

    static final RowMapper<Customer> CUSTOMER = (rs, i) -> new Customer(rs.getLong("id"), rs.getString("customer_number"),
            rs.getString("customer_type"), rs.getString("name"), rs.getString("name_kana"),
            rs.getObject("birth_date", LocalDate.class), rs.getString("occupation"),
            rs.getObject("annual_income_minor") == null ? null : rs.getLong("annual_income_minor"),
            rs.getInt("risk_tolerance"), rs.getInt("investment_experience_years"), rs.getString("aml_risk"),
            rs.getString("kyc_status"), rs.getString("segment"), rs.getString("relationship_manager"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Interaction> INTERACTION = (rs, i) -> new Interaction(rs.getLong("id"),
            rs.getLong("customer_id"), rs.getString("channel"), rs.getString("kind"), rs.getString("note"),
            rs.getString("staff"), rs.getObject("occurred_at", OffsetDateTime.class));

    private static final RowMapper<Opportunity> OPPORTUNITY = (rs, i) -> new Opportunity(rs.getLong("id"),
            rs.getLong("customer_id"), rs.getString("product"), rs.getString("description"),
            rs.getObject("amount_minor") == null ? null : rs.getLong("amount_minor"), rs.getString("stage"),
            rs.getString("owner"), rs.getObject("expected_close", LocalDate.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Customer register(NewCustomer c) {
        if (!"INDIVIDUAL".equals(c.customerType()) && !"CORPORATE".equals(c.customerType())) {
            throw new InvalidRequestException("customerType must be INDIVIDUAL or CORPORATE");
        }
        int risk = c.riskTolerance() == null ? 1 : c.riskTolerance();
        if (risk < 1 || risk > 5) {
            throw new InvalidRequestException("riskTolerance must be between 1 and 5");
        }
        long id = jdbc.sql("""
                        INSERT INTO customers (customer_number, customer_type, name, name_kana, birth_date, occupation,
                            annual_income_minor, risk_tolerance, investment_experience_years, segment, relationship_manager)
                        VALUES ('C' || nextval('customer_number_seq'), :type, :name, :kana, :birth, :occupation, :income,
                                :risk, :exp, :segment, :rm)
                        RETURNING id""")
                .param("type", c.customerType()).param("name", text(c.name(), "name", 100))
                .param("kana", c.nameKana()).param("birth", c.birthDate()).param("occupation", c.occupation())
                .param("income", c.annualIncomeMinor()).param("risk", risk)
                .param("exp", c.investmentExperienceYears() == null ? 0 : c.investmentExperienceYears())
                .param("segment", segment(c)).param("rm", c.relationshipManager())
                .query(Long.class).single();
        return get(id);
    }

    /** 本人確認の結果を記録する。投資商品の販売や口座開設は VERIFIED の顧客に限る。 */
    @Transactional
    public Customer decideKyc(long customerId, boolean verified) {
        int updated = jdbc.sql("""
                        UPDATE customers SET kyc_status = :s, updated_at = now() WHERE id = :id AND kyc_status = 'PENDING'""")
                .param("s", verified ? "VERIFIED" : "REJECTED").param("id", customerId).update();
        if (updated == 0) {
            get(customerId);
            throw new BusinessConflictException("KYC for customer " + customerId + " is already decided");
        }
        return get(customerId);
    }

    /** 適合性情報（リスク許容度・投資経験）の更新。 */
    @Transactional
    public Customer updateSuitability(long customerId, int riskTolerance, int experienceYears) {
        if (riskTolerance < 1 || riskTolerance > 5 || experienceYears < 0) {
            throw new InvalidRequestException("riskTolerance must be 1-5 and experience must not be negative");
        }
        get(customerId);
        jdbc.sql("""
                        UPDATE customers SET risk_tolerance = :r, investment_experience_years = :e, updated_at = now()
                         WHERE id = :id""")
                .param("r", riskTolerance).param("e", experienceYears).param("id", customerId).update();
        return get(customerId);
    }

    /** マネロン対策の顧客リスク評価の更新（AML の調査結果などによる）。 */
    @Transactional
    public Customer updateAmlRisk(long customerId, String risk) {
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(risk)) {
            throw new InvalidRequestException("aml risk must be LOW, MEDIUM or HIGH");
        }
        get(customerId);
        jdbc.sql("UPDATE customers SET aml_risk = :r, updated_at = now() WHERE id = :id")
                .param("r", risk).param("id", customerId).update();
        return get(customerId);
    }

    /** 口座の名義人を紐づける（1 口座 1 顧客。紐づけ済みの口座を別の顧客に付け替えることはしない）。 */
    @Transactional
    public Customer linkAccount(long customerId, long accountId) {
        get(customerId);
        // 未紐づけの口座は customer_id が NULL なので、行の有無と値を分けて読む
        var row = jdbc.sql("SELECT customer_id FROM accounts WHERE id = :a FOR NO KEY UPDATE").param("a", accountId)
                .query().listOfRows();
        if (row.isEmpty()) {
            throw new NotFoundException("account " + accountId + " not found");
        }
        Long current = row.getFirst().get("customer_id") == null ? null : ((Number) row.getFirst().get("customer_id")).longValue();
        if (current != null && current != customerId) {
            throw new BusinessConflictException("account " + accountId + " belongs to another customer");
        }
        jdbc.sql("UPDATE accounts SET customer_id = :c WHERE id = :a").param("c", customerId).param("a", accountId).update();
        return get(customerId);
    }

    @Transactional(readOnly = true)
    public Customer get(long id) {
        return jdbc.sql("SELECT * FROM customers WHERE id = :id").param("id", id).query(CUSTOMER).optional()
                .orElseThrow(() -> new NotFoundException("customer " + id + " not found"));
    }

    /** 顧客の検索（氏名・カナ・顧客番号の部分一致）。 */
    @Transactional(readOnly = true)
    public List<Customer> search(String q) {
        String like = "%" + (q == null ? "" : q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return jdbc.sql("""
                        SELECT * FROM customers
                         WHERE name LIKE :q OR COALESCE(name_kana, '') LIKE :q OR customer_number LIKE :q
                         ORDER BY id LIMIT 200""")
                .param("q", like).query(CUSTOMER).list();
    }

    /** 口座の名義人の顧客（未紐づけなら null）。 */
    @Transactional(readOnly = true)
    public Customer ownerOf(long accountId) {
        return jdbc.sql("SELECT c.* FROM customers c JOIN accounts a ON a.customer_id = c.id WHERE a.id = :a")
                .param("a", accountId).query(CUSTOMER).optional().orElse(null);
    }

    // ------------------------------------------------------------------ 接触履歴・営業案件

    @Transactional
    public Interaction recordInteraction(long customerId, String channel, String kind, String note, String staff) {
        get(customerId);
        long id = jdbc.sql("""
                        INSERT INTO customer_interactions (customer_id, channel, kind, note, staff)
                        VALUES (:c, :ch, :k, :n, :s) RETURNING id""")
                .param("c", customerId).param("ch", channel).param("k", kind).param("n", text(note, "note", 1000))
                .param("s", text(staff, "staff", 50)).query(Long.class).single();
        return jdbc.sql("SELECT * FROM customer_interactions WHERE id = :id").param("id", id).query(INTERACTION).single();
    }

    @Transactional(readOnly = true)
    public List<Interaction> interactions(long customerId) {
        return jdbc.sql("SELECT * FROM customer_interactions WHERE customer_id = :c ORDER BY occurred_at DESC, id DESC LIMIT 200")
                .param("c", customerId).query(INTERACTION).list();
    }

    @Transactional
    public Opportunity createOpportunity(long customerId, String product, String description, Long amountMinor,
                                         String owner, LocalDate expectedClose) {
        get(customerId);
        long id = jdbc.sql("""
                        INSERT INTO sales_opportunities (customer_id, product, description, amount_minor, owner, expected_close)
                        VALUES (:c, :p, :d, :a, :o, :e) RETURNING id""")
                .param("c", customerId).param("p", product).param("d", text(description, "description", 200))
                .param("a", amountMinor).param("o", text(owner, "owner", 50)).param("e", expectedClose)
                .query(Long.class).single();
        return opportunity(id);
    }

    /** 案件の進捗更新。成約（WON）・失注（LOST）した案件は動かさない。 */
    @Transactional
    public Opportunity moveOpportunity(long id, String stage) {
        if (!STAGES.contains(stage)) {
            throw new InvalidRequestException("stage must be one of " + STAGES);
        }
        int updated = jdbc.sql("""
                        UPDATE sales_opportunities SET stage = :s, updated_at = now()
                         WHERE id = :id AND stage NOT IN ('WON', 'LOST')""")
                .param("s", stage).param("id", id).update();
        if (updated == 0) {
            opportunity(id);
            throw new BusinessConflictException("opportunity " + id + " is already closed");
        }
        return opportunity(id);
    }

    @Transactional(readOnly = true)
    public List<Opportunity> pipeline(String owner) {
        return jdbc.sql("""
                        SELECT * FROM sales_opportunities
                         WHERE stage NOT IN ('WON', 'LOST') AND (CAST(:o AS varchar) IS NULL OR owner = CAST(:o AS varchar))
                         ORDER BY expected_close NULLS LAST, id""")
                .param("o", owner).query(OPPORTUNITY).list();
    }

    private Opportunity opportunity(long id) {
        return jdbc.sql("SELECT * FROM sales_opportunities WHERE id = :id").param("id", id).query(OPPORTUNITY).optional()
                .orElseThrow(() -> new NotFoundException("opportunity " + id + " not found"));
    }

    /** 顧客区分：法人 / 年収 3,000 万円以上 PRIVATE / 1,000 万円以上 AFFLUENT / それ以外 MASS。 */
    private static String segment(NewCustomer c) {
        if ("CORPORATE".equals(c.customerType())) {
            return "CORPORATE";
        }
        long income = c.annualIncomeMinor() == null ? 0 : c.annualIncomeMinor();
        return income >= 30_000_000 ? "PRIVATE" : income >= 10_000_000 ? "AFFLUENT" : "MASS";
    }

    private static String text(String v, String name, int max) {
        if (v == null || v.isBlank() || v.length() > max) {
            throw new InvalidRequestException(name + " is required (max " + max + " characters)");
        }
        return v.trim();
    }
}
