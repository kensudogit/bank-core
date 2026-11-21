package com.example.bankcore.risk;

import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.loan.CreditRiskService;
import com.example.bankcore.payment.ForexService;
import com.example.bankcore.securities.SecuritiesService;
import com.example.bankcore.securities.SecuritiesService.Position;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * リスク管理（市場リスク・流動性リスク）と、信用リスクを合わせたリスク管理ダッシュボード。
 *
 * <h2>市場リスク</h2>
 * <ul>
 *   <li>有価証券：時価 × 日次ボラティリティ × 2.326 を 1 日・99% の VaR とする（分散共分散法、銘柄間の相関は保守的に 1）。
 *       金利リスクは BPV（金利 1bp 上昇時の評価損）と +100bp の金利ショック</li>
 *   <li>外国為替：外国為替ポジション科目の通貨別残高を、顧客の外貨買いに対して銀行がカバーしていない持ち高とみなし、
 *       仲値で円換算して VaR と ±10% の為替ショックを計算する</li>
 * </ul>
 *
 * <h2>流動性リスク（LCR に倣った簡略版）</h2>
 * 適格流動資産（現金・日銀預け金・国債の時価）÷ 30 日間の純資金流出（預金の流出見込み・未決済の為替・投信払込・
 * 加盟店未払金・電子マネー − 融資の約定返済の流入の 50%）。資金の満期構成（マチュリティ・ラダー）も示す。
 * 教育用の簡略化であり、規制上の計算ではない。
 */
@Service
public class RiskService {

    /** 1 日・99% の片側 z 値 */
    static final BigDecimal Z99 = new BigDecimal("2.326");
    /** 日次ボラティリティ（%） */
    static final Map<String, BigDecimal> DAILY_VOL_PCT = Map.of(
            "JGB", new BigDecimal("0.20"), "CORPORATE_BOND", new BigDecimal("0.30"), "STOCK", new BigDecimal("1.50"),
            "USD", new BigDecimal("0.60"), "EUR", new BigDecimal("0.65"));

    public record SecurityRisk(String code, String type, long marketValueMinor, long var99Minor, long bpvMinor) {
    }

    public record FxRisk(String currency, long positionMinor, long positionJpyMinor, long var99Minor,
                         long shockLossMinor) {
    }

    /**
     * @param totalVar99Minor   相関 1（単純合計）の VaR
     * @param rateShock100bpMinor +100bp の金利上昇による有価証券の評価損
     */
    public record MarketRisk(List<SecurityRisk> securities, List<FxRisk> fx, long totalVar99Minor,
                             long uncorrelatedVar99Minor, long totalBpvMinor, long rateShock100bpMinor) {
    }

    public record LadderBucket(String bucket, long inflowMinor, long outflowMinor, long gapMinor) {
    }

    /** @param lcrPct LCR（%）。純資金流出が 0 のときは null */
    public record LiquidityRisk(long hqlaMinor, long outflows30dMinor, long inflows30dMinor, Long lcrPct,
                                List<LadderBucket> maturityLadder) {
    }

    public record Dashboard(LocalDate businessDate, CreditRiskService.RiskReport credit, MarketRisk market,
                            LiquidityRisk liquidity) {
    }

    private final JdbcClient jdbc;
    private final SecuritiesService securities;
    private final ForexService forex;
    private final CreditRiskService credit;
    private final BusinessCalendar calendar;

    public RiskService(JdbcClient jdbc, SecuritiesService securities, ForexService forex, CreditRiskService credit,
                       BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.securities = securities;
        this.forex = forex;
        this.credit = credit;
        this.calendar = calendar;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dashboard dashboard() {
        return new Dashboard(calendar.today(), credit.report(200, 30), market(), liquidity());
    }

    /** VaR = z × σ × 時価（円未満四捨五入）。 */
    static long var99(long exposure, BigDecimal dailyVolPct) {
        return Z99.multiply(dailyVolPct).multiply(BigDecimal.valueOf(Math.abs(exposure))).movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Transactional(readOnly = true)
    public MarketRisk market() {
        List<SecurityRisk> sec = new ArrayList<>();
        long total = 0;
        double squares = 0;
        long bpv = 0;
        for (Position p : securities.portfolio()) {
            long v = var99(p.marketValueMinor(), DAILY_VOL_PCT.get(p.security().type()));
            sec.add(new SecurityRisk(p.security().code(), p.security().type(), p.marketValueMinor(), v, p.bpvMinor()));
            total += v;
            squares += (double) v * v;
            bpv += p.bpvMinor();
        }
        List<FxRisk> fx = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("""
                        SELECT currency, SUM(debit_minor - credit_minor) AS bal FROM journal_lines
                         WHERE gl_code = '1900' AND currency <> 'JPY' GROUP BY currency ORDER BY currency""")
                .query().listOfRows()) {
            String ccy = String.valueOf(r.get("currency")).trim();
            // 為替ポジション科目の借方残高 = 顧客に売った外貨のうち銀行がカバーしていない分（外貨の売り持ち）
            long position = -((Number) r.get("bal")).longValue();
            BigDecimal ttm = forex.rate(ccy).ttm();
            long jpy = BigDecimal.valueOf(position).movePointLeft(2).multiply(ttm).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            long v = var99(jpy, DAILY_VOL_PCT.getOrDefault(ccy, new BigDecimal("0.70")));
            fx.add(new FxRisk(ccy, position, jpy, v, Math.abs(jpy) / 10));
            total += v;
            squares += (double) v * v;
        }
        return new MarketRisk(sec, fx, total, Math.round(Math.sqrt(squares)), bpv, bpv * 100);
    }

    @Transactional(readOnly = true)
    public LiquidityRisk liquidity() {
        LocalDate today = calendar.today();
        long cashAndBoj = Math.max(0, gl("1000", true) + gl("1100", true));
        long jgb = securities.portfolio().stream().filter(p -> "JGB".equals(p.security().type()))
                .mapToLong(Position::marketValueMinor).sum();
        long hqla = cashAndBoj + jgb;

        // 預金の流出見込み：法人の預金 25%、それ以外（個人・未紐づけ）5%
        Map<String, Object> deposits = jdbc.sql("""
                        SELECT COALESCE(SUM(a.posted_balance_minor) FILTER (WHERE c.customer_type = 'CORPORATE'), 0) AS corp,
                               COALESCE(SUM(a.posted_balance_minor) FILTER (WHERE c.customer_type IS DISTINCT FROM 'CORPORATE'), 0) AS retail
                          FROM accounts a LEFT JOIN customers c ON c.id = a.customer_id
                         WHERE a.currency = 'JPY' AND a.posted_balance_minor > 0 AND a.status <> 'CLOSED'""")
                .query().singleRow();
        long outflows = ((Number) deposits.get("corp")).longValue() * 25 / 100
                + ((Number) deposits.get("retail")).longValue() * 5 / 100
                + gl("2400", false) + gl("2420", false) + gl("2430", false) + gl("2440", false) * 20 / 100;
        long dueIn30 = loanInflows(today, today.plusDays(30));
        long inflows = dueIn30 / 2;
        long net = outflows - Math.min(inflows, outflows * 75 / 100);
        Long lcr = net <= 0 ? null : hqla * 100 / net;

        List<LadderBucket> ladder = new ArrayList<>();
        int[][] buckets = {{0, 7}, {8, 30}, {31, 90}, {91, 365}, {366, 36_500}};
        String[] names = {"0-7日", "8-30日", "31-90日", "91-365日", "1年超"};
        for (int i = 0; i < buckets.length; i++) {
            LocalDate from = today.plusDays(buckets[i][0]);
            LocalDate to = today.plusDays(buckets[i][1]);
            long in = loanInflows(from, to) + securityRedemptions(from, to);
            long out = jdbc.sql("""
                            SELECT COALESCE(SUM(principal_minor), 0) FROM money_trusts
                             WHERE status = 'ACTIVE' AND maturity_date BETWEEN :f AND :t""")
                    .param("f", from).param("t", to).query(Long.class).single();
            ladder.add(new LadderBucket(names[i], in, out, in - out));
        }
        return new LiquidityRisk(hqla, outflows, inflows, lcr, ladder);
    }

    /** 期間内に期日が来る融資の未払い元利金。 */
    private long loanInflows(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(i.principal_due - i.principal_paid + i.interest_due - i.interest_paid), 0)
                          FROM loan_installments i JOIN loans l ON l.id = i.loan_id
                         WHERE l.status IN ('ACTIVE', 'DELINQUENT') AND i.status IN ('SCHEDULED', 'PARTIAL')
                           AND i.due_date BETWEEN :f AND :t""")
                .param("f", from).param("t", to).query(Long.class).single();
    }

    /** 期間内に償還される債券の額面。 */
    private long securityRedemptions(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(h.quantity), 0) FROM security_holdings h JOIN securities s ON s.code = h.code
                         WHERE s.type <> 'STOCK' AND s.maturity_date BETWEEN :f AND :t""")
                .param("f", from).param("t", to).query(Long.class).single();
    }

    /** 勘定科目の円建て残高（資産は借方 − 貸方、負債は貸方 − 借方）。 */
    private long gl(String code, boolean asset) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN :asset THEN debit_minor - credit_minor ELSE credit_minor - debit_minor END), 0)
                          FROM journal_lines WHERE gl_code = :c AND currency = 'JPY'""")
                .param("asset", asset).param("c", code).query(Long.class).single();
    }
}
