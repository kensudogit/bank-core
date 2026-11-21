package com.example.bankcore.securities;

import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.posting.PostingSupport;
import com.example.bankcore.posting.PostingSupport.Reservation;
import com.example.bankcore.transaction.MoneyTransaction;
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
 * 有価証券業務（市場系）：銀行自身の運用として国債・社債・株式を売買し、利金・償還を受け取り、時価で評価する。
 *
 * <h2>勘定の流れ（その他有価証券として扱う）</h2>
 * <pre>
 *  購入    借方 有価証券（約定金額）・有価証券利息（経過利子の支払）／ 貸方 日銀預け金
 *  売却    借方 日銀預け金 ／ 貸方 有価証券（簿価）・有価証券利息（経過利子の受取）・売却益（または 借方 売却損）
 *  利金    借方 日銀預け金 ／ 貸方 有価証券利息配当金
 *  償還    借方 日銀預け金（額面）／ 貸方 有価証券（簿価）・売却益（または 借方 売却損）
 *  時価評価 借方 有価証券 ／ 貸方 その他有価証券評価差額金（評価益の増加。減少はその逆）
 * </pre>
 * 簿価は移動平均法。評価差額は前回の評価との差額だけを記帳し（洗替の結果と同じ）、売却・償還時に持分を取り崩す。
 * 債券の数量は額面（円）、価格は額面 100 円あたり。経過利子は 額面 × 利率 × 前回利払日からの日数 ÷ 365。
 */
@Service
public class SecuritiesService {

    static final String GL_CLEARING = "1100";
    static final String GL_SECURITIES = "1400";
    static final String GL_VALUATION_RESERVE = "3100";
    static final String GL_SECURITIES_INCOME = "4300";
    static final String GL_GAIN = "4310";
    static final String GL_LOSS = "5300";

    public record Security(String code, String name, String type, Integer couponRateBp, LocalDate maturityDate,
                           BigDecimal modifiedDuration) {
        boolean bond() {
            return !"STOCK".equals(type);
        }
    }

    public record Holding(String code, long quantity, long bookValueMinor, long valuationAdjustmentMinor,
                          LocalDate lastValuedOn) {
    }

    public record Trade(long transactionId, String code, String side, long quantity, BigDecimal price,
                        long principalMinor, long accruedInterestMinor, long realizedPnlMinor, LocalDate tradeDate,
                        String trader) {
    }

    public record TradeResult(MoneyTransaction transaction, Trade trade, Holding holding, boolean replayed) {
    }

    /** @param marketValueMinor 時価（評価に使った価格がなければ簿価）, bpvMinor 金利 1bp 上昇時の評価損 */
    public record Position(Security security, Holding holding, BigDecimal price, LocalDate priceDate,
                           long marketValueMinor, long unrealizedMinor, long bpvMinor) {
    }

    public record IncomeReport(LocalDate businessDate, int coupons, long couponMinor, int redemptions, long redeemedMinor) {
    }

    public record ValuationReport(LocalDate businessDate, int valued, long totalAdjustmentMinor) {
    }

    private static final RowMapper<Security> SECURITY = (rs, i) -> new Security(rs.getString("code"), rs.getString("name"),
            rs.getString("type"), (Integer) rs.getObject("coupon_rate_bp"), rs.getObject("maturity_date", LocalDate.class),
            rs.getBigDecimal("modified_duration"));

    private static final RowMapper<Holding> HOLDING = (rs, i) -> new Holding(rs.getString("code"), rs.getLong("quantity"),
            rs.getLong("book_value_minor"), rs.getLong("valuation_adjustment_minor"),
            rs.getObject("last_valued_on", LocalDate.class));

    private static final RowMapper<Trade> TRADE = (rs, i) -> new Trade(rs.getLong("transaction_id"), rs.getString("code"),
            rs.getString("side"), rs.getLong("quantity"), rs.getBigDecimal("price"), rs.getLong("principal_minor"),
            rs.getLong("accrued_interest_minor"), rs.getLong("realized_pnl_minor"),
            rs.getObject("trade_date", LocalDate.class), rs.getString("trader"));

    private final JdbcClient jdbc;
    private final PostingSupport posting;
    private final BusinessCalendar calendar;

    public SecuritiesService(JdbcClient jdbc, PostingSupport posting, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.calendar = calendar;
    }

    // ================================================================== 計算（純粋）

    /** 約定金額：債券は 額面 × 価格 ÷ 100、株式は 株数 × 価格（円未満四捨五入）。 */
    static long principal(Security s, long quantity, BigDecimal price) {
        BigDecimal amount = BigDecimal.valueOf(quantity).multiply(price);
        if (s.bond()) {
            amount = amount.movePointLeft(2);
        }
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 直近の利払日（年 2 回：償還日の月日と、その 6 か月前の月日）。 */
    static LocalDate lastCouponDate(Security s, LocalDate onOrBefore) {
        LocalDate d = s.maturityDate();
        while (d.isAfter(onOrBefore)) {
            d = d.minusMonths(6);
        }
        return d;
    }

    /** 経過利子 = 額面 × 利率 × 前回利払日からの日数 ÷ 365（切り捨て）。株式は 0。 */
    static long accruedInterest(Security s, long face, LocalDate tradeDate) {
        if (!s.bond()) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(lastCouponDate(s, tradeDate), tradeDate);
        return Math.multiplyExact(Math.multiplyExact(face, (long) s.couponRateBp()), days) / (10_000L * 365);
    }

    /** 利払日か（償還日の月日、または 6 か月ずらした月日）。 */
    static boolean couponDate(Security s, LocalDate date) {
        if (!s.bond() || date.isAfter(s.maturityDate())) {
            return false;
        }
        return lastCouponDate(s, date).equals(date);
    }

    // ================================================================== 相場

    @Transactional
    public void recordPrice(String code, LocalDate date, BigDecimal price) {
        security(code);
        if (price == null || price.signum() <= 0 || price.scale() > 4) {
            throw new InvalidRequestException("price must be positive with at most 4 decimals");
        }
        jdbc.sql("""
                        INSERT INTO security_prices (code, price_date, price) VALUES (:c, :d, :p)
                        ON CONFLICT (code, price_date) DO UPDATE SET price = EXCLUDED.price""")
                .param("c", code).param("d", date == null ? calendar.today() : date).param("p", price).update();
    }

    /** 基準日以前の直近の価格。 */
    Optional<Map<String, Object>> latestPrice(String code, LocalDate asOf) {
        return jdbc.sql("""
                        SELECT price, price_date FROM security_prices WHERE code = :c AND price_date <= :d
                         ORDER BY price_date DESC LIMIT 1""")
                .param("c", code).param("d", asOf).query().listOfRows().stream().findFirst();
    }

    // ================================================================== 売買

    /**
     * 売買の約定。資金は日銀預け金で決済する（同日決済の簡略化）。
     *
     * @param side BUY / SELL
     */
    public TradeResult trade(String code, String side, long quantity, BigDecimal price, String trader, String key) {
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new InvalidRequestException("side must be BUY or SELL");
        }
        if (quantity <= 0 || price == null || price.signum() <= 0 || trader == null || trader.isBlank()) {
            throw new InvalidRequestException("positive quantity and price and a trader are required");
        }
        return posting.inTransaction(() -> {
            Security s = security(code);
            LocalDate today = calendar.today();
            if (s.bond() && !today.isBefore(s.maturityDate())) {
                throw new BusinessConflictException(code + " has matured");
            }
            long principal = principal(s, quantity, price);
            long accrued = accruedInterest(s, quantity, today);
            Reservation res = posting.reserve(TransactionType.SECURITIES_TRADE, key,
                    PostingSupport.hash("SEC", code, side, quantity, price.stripTrailingZeros().toPlainString(), trader),
                    null, null, principal, "JPY", side + " " + code + " " + quantity + " @" + price.toPlainString(), null);
            if (res.isReplay()) {
                return new TradeResult(res.replayed(), tradeOf(res.replayed().id()), holding(code).orElse(null), true);
            }
            long txId = res.transactionId();
            Holding h = lockHolding(code);
            List<JournalLine> lines = new ArrayList<>();
            long pnl = 0;
            if ("BUY".equals(side)) {
                lines.add(JournalLine.debit(GL_SECURITIES, null, principal, "JPY"));
                if (accrued > 0) {
                    lines.add(JournalLine.debit(GL_SECURITIES_INCOME, null, accrued, "JPY"));
                }
                lines.add(JournalLine.credit(GL_CLEARING, null, principal + accrued, "JPY"));
                saveHolding(code, h.quantity() + quantity, h.bookValueMinor() + principal, h.valuationAdjustmentMinor());
            } else {
                if (quantity > h.quantity()) {
                    throw new BusinessConflictException("cannot sell " + quantity + " of " + code + " (holding " + h.quantity() + ")");
                }
                long cost = quantity == h.quantity() ? h.bookValueMinor() : h.bookValueMinor() * quantity / h.quantity();
                long adj = quantity == h.quantity() ? h.valuationAdjustmentMinor()
                        : h.valuationAdjustmentMinor() * quantity / h.quantity();
                pnl = principal - cost;
                lines.add(JournalLine.debit(GL_CLEARING, null, principal + accrued, "JPY"));
                lines.add(JournalLine.credit(GL_SECURITIES, null, cost, "JPY"));
                releaseAdjustment(lines, adj);
                if (accrued > 0) {
                    lines.add(JournalLine.credit(GL_SECURITIES_INCOME, null, accrued, "JPY"));
                }
                addPnl(lines, pnl);
                saveHolding(code, h.quantity() - quantity, h.bookValueMinor() - cost, h.valuationAdjustmentMinor() - adj);
            }
            posting.post(txId, side + " " + s.name() + " " + quantity, lines);
            insertTrade(txId, code, side, quantity, price, principal, accrued, pnl, today, trader);
            MoneyTransaction done = posting.complete(txId);
            posting.event("Security", code, "SecurityTraded", Map.of("transactionId", txId, "side", side,
                    "quantity", quantity, "principalMinor", principal));
            return new TradeResult(done, tradeOf(txId), holding(code).orElse(null), false);
        });
    }

    // ================================================================== 利金・償還・時価評価（日次締めのステップ）

    /**
     * 利払日の利金と、償還日の償還を記帳する。冪等キー {@code coupon-<銘柄>-<日付>}・{@code redeem-<銘柄>} により
     * 再実行しても二重計上しない。
     */
    public IncomeReport processIncome(LocalDate date) {
        int coupons = 0;
        long couponTotal = 0;
        int redemptions = 0;
        long redeemed = 0;
        for (Security s : jdbc.sql("""
                        SELECT s.* FROM securities s JOIN security_holdings h ON h.code = s.code
                         WHERE s.type <> 'STOCK' AND h.quantity > 0 ORDER BY s.code""").query(SECURITY).list()) {
            if (couponDate(s, date)) {
                Long amount = posting.inTransaction(() -> postCoupon(s, date));
                if (amount != null) {
                    coupons++;
                    couponTotal += amount;
                }
            }
            if (date.equals(s.maturityDate())) {
                Long amount = posting.inTransaction(() -> redeem(s, date));
                if (amount != null) {
                    redemptions++;
                    redeemed += amount;
                }
            }
        }
        return new IncomeReport(date, coupons, couponTotal, redemptions, redeemed);
    }

    private Long postCoupon(Security s, LocalDate date) {
        Holding h = lockHolding(s.code());
        long coupon = Math.multiplyExact(h.quantity(), (long) s.couponRateBp()) / 20_000L;   // 年利の半分
        if (coupon == 0) {
            return null;
        }
        Reservation res = posting.reserve(TransactionType.SECURITIES_INCOME, "coupon-" + s.code() + "-" + date,
                PostingSupport.hash("COUPON", s.code(), date, h.quantity()), null, null, coupon, "JPY",
                "利金 " + s.code() + " " + date, null);
        if (res.isReplay()) {
            return null;
        }
        posting.post(res.transactionId(), "利金 " + s.name(), List.of(
                JournalLine.debit(GL_CLEARING, null, coupon, "JPY"),
                JournalLine.credit(GL_SECURITIES_INCOME, null, coupon, "JPY")));
        posting.complete(res.transactionId());
        return coupon;
    }

    private Long redeem(Security s, LocalDate date) {
        Holding h = lockHolding(s.code());
        if (h.quantity() == 0) {
            return null;
        }
        Reservation res = posting.reserve(TransactionType.SECURITIES_TRADE, "redeem-" + s.code(),
                PostingSupport.hash("REDEEM", s.code(), h.quantity()), null, null, h.quantity(), "JPY",
                "償還 " + s.code(), null);
        if (res.isReplay()) {
            return null;
        }
        long txId = res.transactionId();
        long face = h.quantity();
        long pnl = face - h.bookValueMinor();
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(GL_CLEARING, null, face, "JPY"));
        if (h.bookValueMinor() > 0) {
            lines.add(JournalLine.credit(GL_SECURITIES, null, h.bookValueMinor(), "JPY"));
        }
        releaseAdjustment(lines, h.valuationAdjustmentMinor());
        addPnl(lines, pnl);
        posting.post(txId, "償還 " + s.name(), lines);
        saveHolding(s.code(), 0, 0, 0);
        insertTrade(txId, s.code(), "REDEEM", face, BigDecimal.valueOf(100), face, 0, pnl, date, "SYSTEM");
        posting.complete(txId);
        return face;
    }

    /**
     * 時価評価：保有銘柄を基準日以前の直近の価格で評価し、評価差額の前回からの増減を記帳する（価格のない銘柄は簿価のまま）。
     * 冪等キー {@code valuation-<銘柄>-<日付>} で、同じ日に何度実行しても 1 回だけ。
     */
    public ValuationReport markToMarket(LocalDate date) {
        int valued = 0;
        long total = 0;
        for (Holding pre : jdbc.sql("SELECT * FROM security_holdings WHERE quantity > 0 ORDER BY code").query(HOLDING).list()) {
            Optional<Map<String, Object>> price = latestPrice(pre.code(), date);
            if (price.isEmpty()) {
                continue;
            }
            Long adj = posting.inTransaction(() -> value(pre.code(), (BigDecimal) price.get().get("price"), date));
            if (adj != null) {
                valued++;
                total += adj;
            }
        }
        return new ValuationReport(date, valued, total);
    }

    private Long value(String code, BigDecimal price, LocalDate date) {
        Security s = security(code);
        Holding h = lockHolding(code);
        long fair = principal(s, h.quantity(), price);
        long newAdjustment = fair - h.bookValueMinor();
        long delta = newAdjustment - h.valuationAdjustmentMinor();
        if (delta == 0) {
            return newAdjustment;
        }
        Reservation res = posting.reserve(TransactionType.SECURITIES_VALUATION, "valuation-" + code + "-" + date,
                PostingSupport.hash("VALUATION", code, date, delta), null, null, Math.abs(delta), "JPY",
                "時価評価 " + code + " " + date, null);
        if (res.isReplay()) {
            return null;
        }
        long abs = Math.abs(delta);
        posting.post(res.transactionId(), "時価評価 " + s.name(), delta > 0
                ? List.of(JournalLine.debit(GL_SECURITIES, null, abs, "JPY"),
                          JournalLine.credit(GL_VALUATION_RESERVE, null, abs, "JPY"))
                : List.of(JournalLine.debit(GL_VALUATION_RESERVE, null, abs, "JPY"),
                          JournalLine.credit(GL_SECURITIES, null, abs, "JPY")));
        jdbc.sql("""
                        UPDATE security_holdings SET valuation_adjustment_minor = :adj, last_valued_on = :d, updated_at = now()
                         WHERE code = :c""")
                .param("adj", newAdjustment).param("d", date).param("c", code).update();
        posting.complete(res.transactionId());
        return newAdjustment;
    }

    // ================================================================== 照会

    @Transactional(readOnly = true)
    public List<Security> securities() {
        return jdbc.sql("SELECT * FROM securities ORDER BY code").query(SECURITY).list();
    }

    /** ポートフォリオ（時価・評価損益・BPV）。 */
    @Transactional(readOnly = true)
    public List<Position> portfolio() {
        LocalDate today = calendar.today();
        List<Position> list = new ArrayList<>();
        for (Holding h : jdbc.sql("SELECT * FROM security_holdings WHERE quantity > 0 ORDER BY code").query(HOLDING).list()) {
            Security s = security(h.code());
            Optional<Map<String, Object>> p = latestPrice(h.code(), today);
            BigDecimal price = p.map(m -> (BigDecimal) m.get("price")).orElse(null);
            long mv = price == null ? h.bookValueMinor() + h.valuationAdjustmentMinor() : principal(s, h.quantity(), price);
            long bpv = s.modifiedDuration() == null ? 0
                    : s.modifiedDuration().multiply(BigDecimal.valueOf(mv)).movePointLeft(4)
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
            list.add(new Position(s, h, price, p.map(m -> ((java.sql.Date) m.get("price_date")).toLocalDate()).orElse(null),
                    mv, mv - h.bookValueMinor(), bpv));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<Trade> trades(String code) {
        return jdbc.sql("""
                        SELECT * FROM security_trades WHERE (CAST(:c AS varchar) IS NULL OR code = CAST(:c AS varchar))
                         ORDER BY transaction_id DESC LIMIT 500""")
                .param("c", code).query(TRADE).list();
    }

    @Transactional(readOnly = true)
    public Optional<Holding> holding(String code) {
        return jdbc.sql("SELECT * FROM security_holdings WHERE code = :c").param("c", code).query(HOLDING).optional();
    }

    Security security(String code) {
        return jdbc.sql("SELECT * FROM securities WHERE code = :c").param("c", code).query(SECURITY).optional()
                .orElseThrow(() -> new NotFoundException("security " + code + " not found"));
    }

    // ================================================================== helpers

    /** 銘柄の保有行をロックする（なければ数量 0 で作ってからロックする）。売買・評価・利金を銘柄単位で直列化する。 */
    private Holding lockHolding(String code) {
        jdbc.sql("INSERT INTO security_holdings (code, quantity, book_value_minor) VALUES (:c, 0, 0) ON CONFLICT DO NOTHING")
                .param("c", code).update();
        return jdbc.sql("SELECT * FROM security_holdings WHERE code = :c FOR NO KEY UPDATE").param("c", code)
                .query(HOLDING).single();
    }

    private void saveHolding(String code, long quantity, long book, long adjustment) {
        jdbc.sql("""
                        UPDATE security_holdings SET quantity = :q, book_value_minor = :b, valuation_adjustment_minor = :a,
                               updated_at = now() WHERE code = :c""")
                .param("q", quantity).param("b", book).param("a", adjustment).param("c", code).update();
    }

    /** 売却・償還した分の評価差額を取り崩す（評価益なら 借方 評価差額金 ／ 貸方 有価証券）。 */
    private static void releaseAdjustment(List<JournalLine> lines, long adjustment) {
        if (adjustment > 0) {
            lines.add(JournalLine.debit(GL_VALUATION_RESERVE, null, adjustment, "JPY"));
            lines.add(JournalLine.credit(GL_SECURITIES, null, adjustment, "JPY"));
        } else if (adjustment < 0) {
            lines.add(JournalLine.debit(GL_SECURITIES, null, -adjustment, "JPY"));
            lines.add(JournalLine.credit(GL_VALUATION_RESERVE, null, -adjustment, "JPY"));
        }
    }

    private static void addPnl(List<JournalLine> lines, long pnl) {
        if (pnl > 0) {
            lines.add(JournalLine.credit(GL_GAIN, null, pnl, "JPY"));
        } else if (pnl < 0) {
            lines.add(JournalLine.debit(GL_LOSS, null, -pnl, "JPY"));
        }
    }

    private void insertTrade(long txId, String code, String side, long quantity, BigDecimal price, long principal,
                             long accrued, long pnl, LocalDate date, String trader) {
        jdbc.sql("""
                        INSERT INTO security_trades (transaction_id, code, side, quantity, price, principal_minor,
                            accrued_interest_minor, realized_pnl_minor, trade_date, trader)
                        VALUES (:tx, :c, :side, :q, :p, :principal, :accrued, :pnl, :d, :trader)""")
                .param("tx", txId).param("c", code).param("side", side).param("q", quantity).param("p", price)
                .param("principal", principal).param("accrued", accrued).param("pnl", pnl).param("d", date)
                .param("trader", trader).update();
    }

    private Trade tradeOf(long txId) {
        return jdbc.sql("SELECT * FROM security_trades WHERE transaction_id = :tx").param("tx", txId).query(TRADE)
                .optional().orElse(null);
    }
}
