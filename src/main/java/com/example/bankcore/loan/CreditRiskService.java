package com.example.bankcore.loan;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 信用リスク管理：貸出ポートフォリオの期待損失・不良債権・集中リスクと、ストレステスト。
 *
 * <ul>
 *   <li>EAD（デフォルト時のエクスポージャー）= 貸出金残高</li>
 *   <li>PD（1 年デフォルト確率）= 格付の PD と債務者区分の下限の大きい方</li>
 *   <li>LGD（デフォルト時損失率）= 保全部分 10%、非保全部分 45% の加重平均</li>
 *   <li>EL（期待損失）= EAD × PD × LGD。引当金カバー率 = 貸倒引当金 ÷ EL</li>
 *   <li>不良債権比率 = 要管理先以下の残高 ÷ 総残高。集中度は借り手（預金口座の名義人）別のハーフィンダール指数</li>
 *   <li>ストレス：PD を倍率で引き上げ、担保価値を一律に下げた場合の EL</li>
 * </ul>
 * REPEATABLE READ で 1 つのスナップショットから計算する。教育用の簡略化であり、規制資本の計算ではない。
 */
@Service
public class CreditRiskService {

    /** LGD：保全部分 10%、非保全部分 45%（bp） */
    static final int LGD_SECURED_BP = 1000;
    static final int LGD_UNSECURED_BP = 4500;

    public record Bucket(String key, int loans, long outstandingMinor, long expectedLossMinor) {
    }

    public record Exposure(String borrower, long outstandingMinor, long shareBp) {
    }

    /**
     * @param nplRatioBp            不良債権比率（bp）
     * @param provisionCoverageBp   貸倒引当金 ÷ 期待損失（bp）
     * @param concentrationHhi      借り手別の残高シェアのハーフィンダール指数（0〜10000。高いほど集中）
     * @param stressedExpectedLossMinor ストレス時の期待損失
     */
    public record RiskReport(int loans, long outstandingMinor, long securedMinor, long expectedLossMinor,
                             long provisionMinor, long provisionCoverageBp, long nonPerformingMinor, long nplRatioBp,
                             long concentrationHhi, List<Exposure> topExposures, List<Bucket> byGrade,
                             List<Bucket> byBorrowerClass, int stressPdMultiplierPct, int stressCollateralDropPct,
                             long stressedExpectedLossMinor) {
    }

    private record Row(long id, String borrower, String grade, BorrowerClass cls, long outstanding, long provision,
                       long secured) {
    }

    private final JdbcClient jdbc;

    public CreditRiskService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param stressPdMultiplierPct  ストレス時の PD 倍率（%。200 = 2 倍）
     * @param stressCollateralDropPct ストレス時の担保価値の下落率（%）
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RiskReport report(int stressPdMultiplierPct, int stressCollateralDropPct) {
        if (stressPdMultiplierPct < 0 || stressPdMultiplierPct > 1000 || stressCollateralDropPct < 0
                || stressCollateralDropPct > 100) {
            throw new com.example.bankcore.common.Exceptions.InvalidRequestException(
                    "stressPdMultiplierPct must be 0-1000 and stressCollateralDropPct 0-100");
        }
        List<Row> rows = jdbc.sql("""
                        SELECT l.id, a.owner_name, l.grade, l.borrower_class, l.outstanding_minor, l.provision_minor,
                               COALESCE((SELECT SUM(c.appraisal_minor * c.haircut_bp / 10000) FROM collaterals c
                                          WHERE c.application_id = l.application_id AND c.status = 'ACTIVE'), 0) AS col,
                               COALESCE((SELECT SUM(g.coverage_minor * g.recognition_bp / 10000) FROM guarantees g
                                          WHERE g.application_id = l.application_id AND g.status = 'ACTIVE'), 0) AS gua
                          FROM loans l JOIN accounts a ON a.id = l.account_id
                         WHERE l.status IN ('ACTIVE', 'DELINQUENT')
                         ORDER BY l.id""")
                .query((rs, i) -> new Row(rs.getLong("id"), rs.getString("owner_name"), rs.getString("grade"),
                        BorrowerClass.valueOf(rs.getString("borrower_class")), rs.getLong("outstanding_minor"),
                        rs.getLong("provision_minor"), rs.getLong("col") + rs.getLong("gua")))
                .list();

        long outstanding = 0;
        long securedTotal = 0;
        long el = 0;
        long stressedEl = 0;
        long provision = 0;
        long npl = 0;
        Map<String, long[]> byGrade = new LinkedHashMap<>();
        Map<String, long[]> byClass = new LinkedHashMap<>();
        Map<String, Long> byBorrower = new LinkedHashMap<>();
        for (Row r : rows) {
            long secured = Math.min(r.secured(), r.outstanding());
            int pdBp = pdBp(r.grade(), r.cls());
            long loss = expectedLoss(r.outstanding(), secured, pdBp);
            long stressedSecured = secured * (100 - stressCollateralDropPct) / 100;
            int stressedPd = (int) Math.min(10_000L, (long) pdBp * stressPdMultiplierPct / 100);
            long stressedLoss = expectedLoss(r.outstanding(), stressedSecured, stressedPd);

            outstanding += r.outstanding();
            securedTotal += secured;
            el += loss;
            stressedEl += stressedLoss;
            provision += r.provision();
            if (r.cls().nonPerforming()) {
                npl += r.outstanding();
            }
            add(byGrade, r.grade() == null ? "-" : r.grade(), r.outstanding(), loss);
            add(byClass, r.cls().name(), r.outstanding(), loss);
            byBorrower.merge(r.borrower(), r.outstanding(), Long::sum);
        }

        long hhi = 0;
        List<Exposure> exposures = new ArrayList<>();
        for (Map.Entry<String, Long> e : byBorrower.entrySet()) {
            long shareBp = outstanding == 0 ? 0 : e.getValue() * 10_000 / outstanding;
            hhi += shareBp * shareBp / 10_000;
            exposures.add(new Exposure(e.getKey(), e.getValue(), shareBp));
        }
        exposures.sort(Comparator.comparingLong(Exposure::outstandingMinor).reversed());

        return new RiskReport(rows.size(), outstanding, securedTotal, el, provision,
                el == 0 ? 0 : provision * 10_000 / el, npl, outstanding == 0 ? 0 : npl * 10_000 / outstanding, hhi,
                exposures.stream().limit(5).toList(), buckets(byGrade), buckets(byClass),
                stressPdMultiplierPct, stressCollateralDropPct, stressedEl);
    }

    /** 格付の PD と債務者区分の下限の大きい方。 */
    static int pdBp(String grade, BorrowerClass cls) {
        int gradePd = grade == null ? CreditScoring.PD_BP.get("C") : CreditScoring.PD_BP.get(grade);
        return Math.max(gradePd, cls.pdFloorBp());
    }

    /** EL = PD ×（保全部分 × 10% + 非保全部分 × 45%）。 */
    static long expectedLoss(long outstanding, long secured, int pdBp) {
        long lgdAmount = (secured * LGD_SECURED_BP + (outstanding - secured) * LGD_UNSECURED_BP) / 10_000;
        return lgdAmount * pdBp / 10_000;
    }

    private static void add(Map<String, long[]> map, String key, long outstanding, long loss) {
        long[] v = map.computeIfAbsent(key, k -> new long[3]);
        v[0]++;
        v[1] += outstanding;
        v[2] += loss;
    }

    private static List<Bucket> buckets(Map<String, long[]> map) {
        return map.entrySet().stream()
                .map(e -> new Bucket(e.getKey(), (int) e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }
}
