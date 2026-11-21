package com.example.bankcore.wealth;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.crm.CustomerService.Customer;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.posting.PostingSupport;
import com.example.bankcore.posting.PostingSupport.Reservation;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionRules;
import com.example.bankcore.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 資産運用・信託関連：投資信託の販売（購入・解約）と、金銭信託の受託・満期払出し。
 *
 * <h2>販売のルール</h2>
 * <ul>
 *   <li>口座が顧客に紐づき、本人確認（KYC）済みであること</li>
 *   <li>適合性の原則：商品のリスク水準が顧客のリスク許容度を超える場合は販売しない</li>
 * </ul>
 *
 * <h2>勘定の流れ（販売会社としての銀行）</h2>
 * <pre>
 *  投信購入     借方 顧客預金（申込金額）／ 貸方 投信払込未済金（払込額）・投信販売手数料
 *  払込         借方 投信払込未済金       ／ 貸方 日銀預け金（受託銀行へ払込）
 *  投信解約     借方 日銀預け金           ／ 貸方 顧客預金（解約代金。受託銀行から同日入金とする簡略化）
 *  金銭信託受託 借方 顧客預金             ／ 貸方 金銭信託受託金（信託勘定。銀行の財産と分別して管理）
 *  満期払出し   借方 金銭信託受託金（元本）・信託配当金 ／ 貸方 顧客預金
 * </pre>
 * 保有口数は顧客の資産なので勘定には載せず、fund_holdings で管理する。
 * 口数 = 払込額 × 10,000 ÷ 基準価額（切り捨て）、解約代金 = 口数 × 基準価額 ÷ 10,000（切り捨て）。
 */
@Service
public class WealthService {

    static final String GL_CLEARING = "1100";
    static final String GL_FUND_PAYABLE = "2420";
    static final String GL_TRUST = "2600";
    static final String GL_FUND_FEE = "4400";
    static final String GL_TRUST_DIVIDEND = "5400";

    public record Fund(String code, String name, int riskLevel, BigDecimal nav, LocalDate navDate, int purchaseFeeBp,
                       int trustFeeBp, String status) {
    }

    public record FundHolding(long accountId, String fundCode, long units, long costMinor, long marketValueMinor) {
    }

    public record FundOrder(long transactionId, long accountId, long customerId, String fundCode, String side,
                            long amountMinor, long units, BigDecimal nav, long feeMinor, boolean settled) {
    }

    public record OrderResult(MoneyTransaction transaction, FundOrder order, boolean replayed) {
    }

    public record MoneyTrust(long id, long accountId, long customerId, long principalMinor, int expectedRateBp,
                             LocalDate startDate, LocalDate maturityDate, String status, Long dividendMinor) {
    }

    public record TrustResult(MoneyTransaction transaction, MoneyTrust trust, boolean replayed) {
    }

    private static final RowMapper<Fund> FUND = (rs, i) -> new Fund(rs.getString("code"), rs.getString("name"),
            rs.getInt("risk_level"), rs.getBigDecimal("nav"), rs.getObject("nav_date", LocalDate.class),
            rs.getInt("purchase_fee_bp"), rs.getInt("trust_fee_bp"), rs.getString("status"));

    private static final RowMapper<FundOrder> ORDER = (rs, i) -> new FundOrder(rs.getLong("transaction_id"),
            rs.getLong("account_id"), rs.getLong("customer_id"), rs.getString("fund_code"), rs.getString("side"),
            rs.getLong("amount_minor"), rs.getLong("units"), rs.getBigDecimal("nav"), rs.getLong("fee_minor"),
            rs.getBoolean("settled"));

    private static final RowMapper<MoneyTrust> TRUST = (rs, i) -> new MoneyTrust(rs.getLong("id"), rs.getLong("account_id"),
            rs.getLong("customer_id"), rs.getLong("principal_minor"), rs.getInt("expected_rate_bp"),
            rs.getObject("start_date", LocalDate.class), rs.getObject("maturity_date", LocalDate.class),
            rs.getString("status"), rs.getObject("dividend_minor") == null ? null : rs.getLong("dividend_minor"));

    private final JdbcClient jdbc;
    private final PostingSupport posting;
    private final AccountRepository accounts;
    private final CustomerService customers;
    private final BusinessCalendar calendar;

    public WealthService(JdbcClient jdbc, PostingSupport posting, AccountRepository accounts, CustomerService customers,
                         BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.customers = customers;
        this.calendar = calendar;
    }

    // ================================================================== 計算（純粋）

    /** 購入時手数料（申込金額に含む内枠方式）= 申込金額 × 手数料率 ÷ (1 + 手数料率)。切り捨て。 */
    static long purchaseFee(long amount, int feeBp) {
        return Math.multiplyExact(amount, feeBp) / (10_000L + feeBp);
    }

    static long unitsFor(long invest, BigDecimal nav) {
        return BigDecimal.valueOf(invest).multiply(BigDecimal.valueOf(10_000)).divide(nav, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    static long valueOf(long units, BigDecimal nav) {
        return BigDecimal.valueOf(units).multiply(nav).divide(BigDecimal.valueOf(10_000), 0, RoundingMode.DOWN)
                .longValueExact();
    }

    // ================================================================== 投資信託

    @Transactional
    public Fund updateNav(String code, BigDecimal nav, LocalDate date) {
        if (nav == null || nav.signum() <= 0 || nav.scale() > 2) {
            throw new InvalidRequestException("nav must be positive with at most 2 decimals");
        }
        int updated = jdbc.sql("UPDATE funds SET nav = :n, nav_date = :d WHERE code = :c")
                .param("n", nav).param("d", date == null ? calendar.today() : date).param("c", code).update();
        if (updated == 0) {
            throw new NotFoundException("fund " + code + " not found");
        }
        return fund(code);
    }

    /** 投資信託の購入（申込金額を指定。手数料込み）。 */
    public OrderResult subscribe(long accountId, String fundCode, long amountMinor, String key) {
        if (amountMinor < 10_000) {
            throw new InvalidRequestException("the minimum subscription is 10,000 JPY");
        }
        return posting.inTransaction(() -> {
            Customer customer = eligibleCustomer(accountId);
            Fund f = fund(fundCode);
            if (!"OPEN".equals(f.status())) {
                throw new BusinessConflictException("fund " + fundCode + " is closed for subscriptions");
            }
            // 適合性の原則：顧客のリスク許容度を超える商品は勧誘・販売しない
            if (f.riskLevel() > customer.riskTolerance()) {
                throw new BusinessConflictException("fund risk level " + f.riskLevel() + " exceeds the customer's risk tolerance "
                        + customer.riskTolerance());
            }
            long fee = purchaseFee(amountMinor, f.purchaseFeeBp());
            long invest = amountMinor - fee;
            long units = unitsFor(invest, f.nav());
            Reservation res = posting.reserve(TransactionType.FUND_SUBSCRIPTION, key,
                    PostingSupport.hash("FUND_SUB", accountId, fundCode, amountMinor), accountId, null, amountMinor, "JPY",
                    "投信購入 " + fundCode, null);
            if (res.isReplay()) {
                return new OrderResult(res.replayed(), orderOf(res.replayed().id()), true);
            }
            long txId = res.transactionId();
            Account a = posting.lockAccounts(accountId).get(accountId);
            Optional<RejectReason> rejection = TransactionRules.check(a, null, amountMinor, "JPY");
            if (rejection.isPresent()) {
                return new OrderResult(posting.reject(txId, rejection.get()), null, false);
            }
            List<JournalLine> lines = new ArrayList<>();
            lines.add(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, accountId, amountMinor, "JPY"));
            lines.add(JournalLine.credit(GL_FUND_PAYABLE, null, invest, "JPY"));
            if (fee > 0) {
                lines.add(JournalLine.credit(GL_FUND_FEE, null, fee, "JPY"));
            }
            posting.post(txId, "投信購入 " + f.name(), lines);
            jdbc.sql("""
                            INSERT INTO fund_holdings (account_id, fund_code, units, cost_minor) VALUES (:a, :f, :u, :c)
                            ON CONFLICT (account_id, fund_code) DO UPDATE
                               SET units = fund_holdings.units + :u, cost_minor = fund_holdings.cost_minor + :c, updated_at = now()""")
                    .param("a", accountId).param("f", fundCode).param("u", units).param("c", amountMinor).update();
            insertOrder(txId, accountId, customer.id(), fundCode, "SUBSCRIBE", invest, units, f.nav(), fee, false);
            MoneyTransaction done = posting.complete(txId);
            posting.event("Fund", fundCode, "FundSubscribed", Map.of("transactionId", txId, "units", units));
            return new OrderResult(done, orderOf(txId), false);
        });
    }

    /** 投資信託の解約（口数を指定）。解約代金は当日の基準価額で計算する。 */
    public OrderResult redeem(long accountId, String fundCode, long units, String key) {
        if (units <= 0) {
            throw new InvalidRequestException("units must be positive");
        }
        return posting.inTransaction(() -> {
            Customer customer = eligibleCustomer(accountId);
            Fund f = fund(fundCode);
            long amount = valueOf(units, f.nav());
            Reservation res = posting.reserve(TransactionType.FUND_REDEMPTION, key,
                    PostingSupport.hash("FUND_RED", accountId, fundCode, units), null, accountId, Math.max(1, amount),
                    "JPY", "投信解約 " + fundCode, null);
            if (res.isReplay()) {
                return new OrderResult(res.replayed(), orderOf(res.replayed().id()), true);
            }
            long txId = res.transactionId();
            var holding = jdbc.sql("SELECT units, cost_minor FROM fund_holdings WHERE account_id = :a AND fund_code = :f FOR NO KEY UPDATE")
                    .param("a", accountId).param("f", fundCode).query().listOfRows();
            long held = holding.isEmpty() ? 0 : ((Number) holding.getFirst().get("units")).longValue();
            if (units > held) {
                throw new BusinessConflictException("cannot redeem " + units + " units (holding " + held + ")");
            }
            if (amount <= 0) {
                throw new InvalidRequestException("redemption amount is zero");
            }
            long cost = ((Number) holding.getFirst().get("cost_minor")).longValue();
            long costPart = units == held ? cost : cost * units / held;
            posting.lockAccounts(accountId);
            posting.post(txId, "投信解約 " + f.name(), List.of(
                    JournalLine.debit(GL_CLEARING, null, amount, "JPY"),
                    JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, accountId, amount, "JPY")));
            jdbc.sql("""
                            UPDATE fund_holdings SET units = units - :u, cost_minor = cost_minor - :c, updated_at = now()
                             WHERE account_id = :a AND fund_code = :f""")
                    .param("u", units).param("c", costPart).param("a", accountId).param("f", fundCode).update();
            insertOrder(txId, accountId, customer.id(), fundCode, "REDEEM", amount, units, f.nav(), 0, true);
            MoneyTransaction done = posting.complete(txId);
            posting.event("Fund", fundCode, "FundRedeemed", Map.of("transactionId", txId, "units", units, "amountMinor", amount));
            return new OrderResult(done, orderOf(txId), false);
        });
    }

    /**
     * 購入代金の受託銀行への払込（未払込の購入注文をまとめて払い込む）。
     * 冪等キー {@code fund-settle-<業務日付>-<銘柄>} により、同じ日の再実行では二重に払い込まない。
     */
    public long settleSubscriptions(String fundCode) {
        LocalDate today = calendar.today();
        return posting.inTransaction(() -> {
            List<Long> orders = jdbc.sql("""
                            SELECT transaction_id FROM fund_orders WHERE fund_code = :f AND side = 'SUBSCRIBE' AND NOT settled
                             ORDER BY transaction_id FOR NO KEY UPDATE""")
                    .param("f", fundCode).query(Long.class).list();
            if (orders.isEmpty()) {
                return 0L;
            }
            long total = jdbc.sql("SELECT SUM(amount_minor) FROM fund_orders WHERE transaction_id IN (:ids)")
                    .param("ids", orders).query(Long.class).single();
            Reservation res = posting.reserve(TransactionType.FUND_SETTLEMENT, "fund-settle-" + today + "-" + fundCode
                            + "-" + orders.getFirst(), PostingSupport.hash("FUND_SETTLE", fundCode, orders), null, null,
                    total, "JPY", "投信払込 " + fundCode, null);
            if (res.isReplay()) {
                return 0L;
            }
            posting.post(res.transactionId(), "投信払込 " + fundCode, List.of(
                    JournalLine.debit(GL_FUND_PAYABLE, null, total, "JPY"),
                    JournalLine.credit(GL_CLEARING, null, total, "JPY")));
            jdbc.sql("UPDATE fund_orders SET settled = true WHERE transaction_id IN (:ids)").param("ids", orders).update();
            posting.complete(res.transactionId());
            return total;
        });
    }

    @Transactional(readOnly = true)
    public List<Fund> funds() {
        return jdbc.sql("SELECT * FROM funds ORDER BY risk_level, code").query(FUND).list();
    }

    /** 口座の保有投資信託と評価額（当日の基準価額）。 */
    @Transactional(readOnly = true)
    public List<FundHolding> holdings(long accountId) {
        return jdbc.sql("""
                        SELECT h.account_id, h.fund_code, h.units, h.cost_minor, f.nav
                          FROM fund_holdings h JOIN funds f ON f.code = h.fund_code
                         WHERE h.account_id = :a AND h.units > 0 ORDER BY h.fund_code""")
                .param("a", accountId)
                .query((rs, i) -> new FundHolding(rs.getLong("account_id"), rs.getString("fund_code"), rs.getLong("units"),
                        rs.getLong("cost_minor"), valueOf(rs.getLong("units"), rs.getBigDecimal("nav"))))
                .list();
    }

    // ================================================================== 金銭信託

    /** 金銭信託の受託：口座から元本を受け入れ、信託勘定で分別管理する。 */
    public TrustResult entrust(long accountId, long principalMinor, int termMonths, int expectedRateBp, String key) {
        if (principalMinor < 100_000 || termMonths < 1 || termMonths > 120 || expectedRateBp < 0 || expectedRateBp > 1000) {
            throw new InvalidRequestException("principal >= 100,000, term 1-120 months, rate 0-1000 bp");
        }
        return posting.inTransaction(() -> {
            Customer customer = eligibleCustomer(accountId);
            Reservation res = posting.reserve(TransactionType.TRUST_ENTRUST, key,
                    PostingSupport.hash("TRUST", accountId, principalMinor, termMonths, expectedRateBp), accountId, null,
                    principalMinor, "JPY", "金銭信託 受託", null);
            if (res.isReplay()) {
                return new TrustResult(res.replayed(), trustByTransaction(res.replayed().id()), true);
            }
            long txId = res.transactionId();
            Account a = posting.lockAccounts(accountId).get(accountId);
            Optional<RejectReason> rejection = TransactionRules.check(a, null, principalMinor, "JPY");
            if (rejection.isPresent()) {
                return new TrustResult(posting.reject(txId, rejection.get()), null, false);
            }
            posting.post(txId, "金銭信託 受託", List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, accountId, principalMinor, "JPY"),
                    JournalLine.credit(GL_TRUST, null, principalMinor, "JPY")));
            LocalDate start = calendar.today();
            long id = jdbc.sql("""
                            INSERT INTO money_trusts (account_id, customer_id, principal_minor, expected_rate_bp, start_date,
                                maturity_date, status, entrust_transaction_id)
                            VALUES (:a, :c, :p, :r, :s, :m, 'ACTIVE', :tx) RETURNING id""")
                    .param("a", accountId).param("c", customer.id()).param("p", principalMinor).param("r", expectedRateBp)
                    .param("s", start).param("m", start.plusMonths(termMonths)).param("tx", txId).query(Long.class).single();
            MoneyTransaction done = posting.complete(txId);
            posting.event("MoneyTrust", id, "TrustEntrusted", Map.of("trustId", id, "principalMinor", principalMinor));
            return new TrustResult(done, trust(id), false);
        });
    }

    /**
     * 満期を迎えた金銭信託の払出し（日次締めのステップ）。配当 = 元本 × 予定配当率 × 日数 ÷ 365（切り捨て）。
     * 冪等キー {@code trust-payout-<信託ID>} で、再実行しても二重に払い出さない。
     */
    public int payoutMatured(LocalDate date) {
        int paid = 0;
        for (long id : jdbc.sql("SELECT id FROM money_trusts WHERE status = 'ACTIVE' AND maturity_date <= :d ORDER BY id")
                .param("d", date).query(Long.class).list()) {
            if (Boolean.TRUE.equals(posting.inTransaction(() -> payout(id)))) {
                paid++;
            }
        }
        return paid;
    }

    private boolean payout(long trustId) {
        MoneyTrust t = jdbc.sql("SELECT * FROM money_trusts WHERE id = :id FOR NO KEY UPDATE").param("id", trustId)
                .query(TRUST).single();
        if (!"ACTIVE".equals(t.status())) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(t.startDate(), t.maturityDate());
        long dividend = Math.multiplyExact(Math.multiplyExact(t.principalMinor(), (long) t.expectedRateBp()), days)
                / (10_000L * 365);
        Reservation res = posting.reserve(TransactionType.TRUST_PAYOUT, "trust-payout-" + trustId,
                PostingSupport.hash("TRUST_PAYOUT", trustId), null, t.accountId(), t.principalMinor() + dividend, "JPY",
                "金銭信託 満期払出し #" + trustId, null);
        if (res.isReplay()) {
            return false;
        }
        posting.lockAccounts(t.accountId());
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(GL_TRUST, null, t.principalMinor(), "JPY"));
        if (dividend > 0) {
            lines.add(JournalLine.debit(GL_TRUST_DIVIDEND, null, dividend, "JPY"));
        }
        lines.add(JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, t.accountId(), t.principalMinor() + dividend, "JPY"));
        posting.post(res.transactionId(), "金銭信託 満期払出し #" + trustId, lines);
        jdbc.sql("""
                        UPDATE money_trusts SET status = 'MATURED', payout_transaction_id = :tx, dividend_minor = :d WHERE id = :id""")
                .param("tx", res.transactionId()).param("d", dividend).param("id", trustId).update();
        posting.complete(res.transactionId());
        posting.event("MoneyTrust", trustId, "TrustMatured", Map.of("trustId", trustId, "dividendMinor", dividend));
        return true;
    }

    @Transactional(readOnly = true)
    public MoneyTrust trust(long id) {
        return jdbc.sql("SELECT * FROM money_trusts WHERE id = :id").param("id", id).query(TRUST).optional()
                .orElseThrow(() -> new NotFoundException("money trust " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<MoneyTrust> trusts(long accountId) {
        return jdbc.sql("SELECT * FROM money_trusts WHERE account_id = :a ORDER BY id").param("a", accountId).query(TRUST).list();
    }

    // ================================================================== helpers

    /** 口座の名義人が登録済み・本人確認済みであること。 */
    private Customer eligibleCustomer(long accountId) {
        accounts.findById(accountId).orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        Customer c = customers.ownerOf(accountId);
        if (c == null) {
            throw new BusinessConflictException("account " + accountId + " is not linked to a customer");
        }
        if (!"VERIFIED".equals(c.kycStatus())) {
            throw new BusinessConflictException("customer " + c.customerNumber() + " has not completed KYC");
        }
        return c;
    }

    Fund fund(String code) {
        return jdbc.sql("SELECT * FROM funds WHERE code = :c").param("c", code).query(FUND).optional()
                .orElseThrow(() -> new NotFoundException("fund " + code + " not found"));
    }

    private void insertOrder(long txId, long accountId, long customerId, String fundCode, String side, long amount,
                             long units, BigDecimal nav, long fee, boolean settled) {
        jdbc.sql("""
                        INSERT INTO fund_orders (transaction_id, account_id, customer_id, fund_code, side, amount_minor, units,
                            nav, fee_minor, settled)
                        VALUES (:tx, :a, :c, :f, :side, :amount, :units, :nav, :fee, :settled)""")
                .param("tx", txId).param("a", accountId).param("c", customerId).param("f", fundCode).param("side", side)
                .param("amount", amount).param("units", units).param("nav", nav).param("fee", fee).param("settled", settled)
                .update();
    }

    private FundOrder orderOf(long txId) {
        return jdbc.sql("SELECT * FROM fund_orders WHERE transaction_id = :tx").param("tx", txId).query(ORDER)
                .optional().orElse(null);
    }

    private MoneyTrust trustByTransaction(long txId) {
        return jdbc.sql("SELECT * FROM money_trusts WHERE entrust_transaction_id = :tx").param("tx", txId).query(TRUST)
                .optional().orElse(null);
    }
}
