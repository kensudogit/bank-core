package com.example.bankcore.crm;

import com.example.bankcore.crm.CustomerService.Customer;
import com.example.bankcore.crm.CustomerService.Interaction;
import com.example.bankcore.crm.CustomerService.Opportunity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 顧客 360°：顧客に紐づく口座・融資・投資信託・金銭信託・カード・電子マネー・送金・AML アラート・接触履歴・営業案件と、
 * 預かり資産（預金の円建て残高 + 投資信託の評価額 + 金銭信託の元本）と融資残高の合計を 1 回で返す（営業支援・情報系）。
 */
@Service
public class Customer360Service {

    public record Customer360(Customer customer, List<Map<String, Object>> accounts, List<Map<String, Object>> loans,
                              List<Map<String, Object>> funds, List<Map<String, Object>> trusts,
                              List<Map<String, Object>> cards, List<Map<String, Object>> remittances,
                              List<Map<String, Object>> amlAlerts, List<Interaction> interactions,
                              List<Opportunity> openOpportunities, long depositsJpyMinor, long fundValueMinor,
                              long trustPrincipalMinor, long totalAssetsMinor, long loanOutstandingMinor) {
    }

    private final JdbcClient jdbc;
    private final CustomerService customers;

    public Customer360Service(JdbcClient jdbc, CustomerService customers) {
        this.jdbc = jdbc;
        this.customers = customers;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Customer360 view(long customerId) {
        Customer c = customers.get(customerId);
        List<Map<String, Object>> accounts = rows("""
                SELECT id, account_number, currency, posted_balance_minor, held_balance_minor, status
                  FROM accounts WHERE customer_id = :c ORDER BY id""", customerId);
        List<Map<String, Object>> loans = rows("""
                SELECT l.id, l.product, l.outstanding_minor, l.annual_rate_bp, l.status, l.days_past_due, l.borrower_class,
                       l.maturity_date
                  FROM loans l JOIN accounts a ON a.id = l.account_id WHERE a.customer_id = :c ORDER BY l.id""", customerId);
        List<Map<String, Object>> funds = rows("""
                SELECT h.account_id, h.fund_code, f.name, h.units, h.cost_minor, FLOOR(h.units * f.nav / 10000) AS value_minor
                  FROM fund_holdings h JOIN funds f ON f.code = h.fund_code JOIN accounts a ON a.id = h.account_id
                 WHERE a.customer_id = :c AND h.units > 0 ORDER BY h.fund_code""", customerId);
        List<Map<String, Object>> trusts = rows("""
                SELECT id, account_id, principal_minor, expected_rate_bp, maturity_date, status
                  FROM money_trusts WHERE customer_id = :c ORDER BY id""", customerId);
        List<Map<String, Object>> cards = rows("""
                SELECT k.id, k.last4, k.account_id, k.status, k.daily_limit_minor
                  FROM cards k JOIN accounts a ON a.id = k.account_id WHERE a.customer_id = :c ORDER BY k.id""", customerId);
        List<Map<String, Object>> remittances = rows("""
                SELECT 'DOMESTIC' AS kind, r.id, r.direction, r.amount_minor, 'JPY' AS currency, r.status, r.created_at
                  FROM domestic_remittances r JOIN accounts a ON a.id = r.account_id WHERE a.customer_id = :c
                UNION ALL
                SELECT 'FOREIGN', f.id, 'OUT', f.amount_minor, f.currency, f.status, f.created_at
                  FROM foreign_remittances f JOIN accounts a ON a.id = f.account_id WHERE a.customer_id = :c
                 ORDER BY created_at DESC LIMIT 50""", customerId);
        List<Map<String, Object>> alerts = rows("""
                SELECT id, rule, score, status, detail, created_at FROM aml_alerts WHERE customer_id = :c
                 ORDER BY id DESC LIMIT 50""", customerId);

        long deposits = accounts.stream().filter(a -> "JPY".equals(String.valueOf(a.get("currency")).trim()))
                .mapToLong(a -> Math.max(0, ((Number) a.get("posted_balance_minor")).longValue())).sum();
        long fundValue = funds.stream().mapToLong(f -> ((Number) f.get("value_minor")).longValue()).sum();
        long trustPrincipal = trusts.stream().filter(t -> "ACTIVE".equals(t.get("status")))
                .mapToLong(t -> ((Number) t.get("principal_minor")).longValue()).sum();
        long loanOutstanding = loans.stream().mapToLong(l -> ((Number) l.get("outstanding_minor")).longValue()).sum();
        List<Opportunity> open = customers.pipeline(null).stream().filter(o -> o.customerId() == customerId).toList();
        return new Customer360(c, accounts, loans, funds, trusts, cards, remittances, alerts,
                customers.interactions(customerId).stream().limit(20).toList(), open, deposits, fundValue,
                trustPrincipal, deposits + fundValue + trustPrincipal, loanOutstanding);
    }

    private List<Map<String, Object>> rows(String sql, long customerId) {
        return jdbc.sql(sql).param("c", customerId).query().listOfRows();
    }
}
