package com.example.bankcore.loan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 与信審査のスコアリング（DB に依存しない純粋な計算）。点数（0〜100）から格付 A〜E・推奨判断・提示金利・PD を出す。
 *
 * <pre>
 *  返済比率 DTI =（既存借入の年間返済 + 今回の初年度返済）÷ 年収
 *      20%以下 40 / 30%以下 30 / 35%以下 20 / 40%以下 10 / 超 0
 *  保全率 =（担保の掛目後評価額 + 保証の認定額）÷ 申込額
 *      100%以上 30 / 80%以上 22 / 50%以上 12 / 0% 超 6 / 0（無担保の消費者ローンは 10）
 *  年収          1,000 万以上 15 / 600 万以上 12 / 400 万以上 9 / 250 万以上 5 / 未満 0
 *  取引実績      預金残高が申込額の 10% 以上 15 / 残高あり 8 / なし 0
 *  延滞中の融資  あり −40
 *  格付          80〜 A / 65〜 B / 50〜 C / 35〜 D / 〜34 E
 *  推奨          A〜C 承認、D 要検討、E または形式要件違反は謝絶
 *  形式要件      DTI 40% 超、延滞中の融資あり、住宅ローンで不動産担保なし、消費者ローンで年収の 1/3 超（総量規制）
 * </pre>
 *
 * 教育用の簡略化であり、実際の審査モデル（統計モデルの検証・説明責任・公平性の確認など）を代替するものではない。
 */
public final class CreditScoring {

    /** 格付ごとの上乗せ金利（bp） */
    public static final Map<String, Integer> SPREAD_BP = Map.of("A", 0, "B", 30, "C", 70, "D", 150, "E", 300);

    /** 格付ごとの 1 年デフォルト確率（bp。信用リスク計測に使う） */
    public static final Map<String, Integer> PD_BP = Map.of("A", 20, "B", 50, "C", 150, "D", 400, "E", 1000);

    /** 適用金利の上限（bp）。利息制限法の上限（元本 100 万円以上 15%）を超えないようにする */
    public static final int MAX_RATE_BP = 1500;

    public enum Recommendation { APPROVE, REFER, DECLINE }

    /**
     * @param securedMinor        担保の掛目後評価額 + 保証の認定額
     * @param hasRealEstate       有効な不動産担保があるか
     * @param depositBalanceMinor 返済口座の預金残高
     * @param delinquentLoans     同じ口座の延滞中の融資件数
     */
    public record Input(LoanProduct product, long principalMinor, int termMonths, RepaymentMethod method,
                        long annualIncomeMinor, long existingAnnualDebtMinor, long securedMinor,
                        boolean hasRealEstate, long depositBalanceMinor, int delinquentLoans) {
    }

    /**
     * @param dtiBp            返済比率（bp。10000 = 100%）
     * @param coverageBp       保全率（bp）
     * @param proposedRateBp   提示金利（基準金利 + 格付の上乗せ、上限あり）
     * @param annualRepaymentMinor 初年度の返済額（提示金利で計算）
     */
    public record Result(int score, String grade, long dtiBp, long coverageBp, int proposedRateBp, int pdBp,
                         long annualRepaymentMinor, Recommendation recommendation, List<String> reasons) {
    }

    private CreditScoring() {
    }

    public static Result evaluate(Input in) {
        List<String> reasons = new ArrayList<>();
        // 格付は返済比率から決まるので、返済比率の計算には中位（C）の金利を仮に使う（循環を避ける）
        int estimatedRate = Math.min(MAX_RATE_BP, in.product().baseRateBp() + SPREAD_BP.get("C"));
        long annualRepayment = annualRepayment(in, estimatedRate);
        long totalDebt = Math.addExact(in.existingAnnualDebtMinor(), annualRepayment);
        // 年収 0 は返済比率を計算できないため、必ず基準超過になる値にする
        long dtiBp = in.annualIncomeMinor() > 0 ? Math.multiplyExact(totalDebt, 10_000L) / in.annualIncomeMinor() : 99_999;
        long coverageBp = Math.multiplyExact(Math.min(in.securedMinor(), Math.multiplyExact(in.principalMinor(), 10L)), 10_000L)
                / in.principalMinor();

        int score = 0;
        score += dtiBp <= 2000 ? 40 : dtiBp <= 3000 ? 30 : dtiBp <= 3500 ? 20 : dtiBp <= 4000 ? 10 : 0;
        reasons.add("返済比率 " + percent(dtiBp));
        if (in.securedMinor() > 0) {
            score += coverageBp >= 10_000 ? 30 : coverageBp >= 8000 ? 22 : coverageBp >= 5000 ? 12 : 6;
            reasons.add("保全率 " + percent(coverageBp));
        } else {
            score += in.product() == LoanProduct.CONSUMER ? 10 : 0;
            reasons.add("無担保・無保証");
        }
        long income = in.annualIncomeMinor();
        score += income >= 10_000_000 ? 15 : income >= 6_000_000 ? 12 : income >= 4_000_000 ? 9 : income >= 2_500_000 ? 5 : 0;
        long tenPercent = in.principalMinor() / 10;
        score += in.depositBalanceMinor() >= tenPercent ? 15 : in.depositBalanceMinor() > 0 ? 8 : 0;
        if (in.delinquentLoans() > 0) {
            score -= 40;
            reasons.add("延滞中の融資あり");
        }
        score = Math.max(0, Math.min(100, score));
        String grade = score >= 80 ? "A" : score >= 65 ? "B" : score >= 50 ? "C" : score >= 35 ? "D" : "E";

        // 形式要件（法令・規程）に反する場合は点数にかかわらず謝絶
        boolean hardDecline = false;
        if (dtiBp > 4000) {
            hardDecline = true;
            reasons.add("返済比率が 40% を超過");
        }
        if (in.delinquentLoans() > 0) {
            hardDecline = true;
        }
        if (in.product().requiresRealEstate() && !in.hasRealEstate()) {
            hardDecline = true;
            reasons.add("住宅ローンには不動産担保が必要");
        }
        if (in.product().totalVolumeRule() && Math.multiplyExact(in.principalMinor(), 3L) > in.annualIncomeMinor()) {
            hardDecline = true;
            reasons.add("総量規制（年収の 3 分の 1）を超過");
        }
        Recommendation rec = hardDecline || grade.equals("E") ? Recommendation.DECLINE
                : grade.equals("D") ? Recommendation.REFER : Recommendation.APPROVE;
        int rate = Math.min(MAX_RATE_BP, in.product().baseRateBp() + SPREAD_BP.get(grade));
        return new Result(score, grade, dtiBp, coverageBp, rate, PD_BP.get(grade),
                annualRepayment(in, rate), rec, List.copyOf(reasons));
    }

    /** 初年度の返済額（審査時点の仮の期日で返済予定表を作って合計する）。 */
    static long annualRepayment(Input in, long rateBp) {
        List<LocalDate> dates = LoanMath.dueDates(LocalDate.of(2000, 1, 1), in.termMonths());
        return LoanMath.firstYearRepayment(LoanMath.schedule(in.principalMinor(), rateBp, in.method(), dates));
    }

    private static String percent(long bp) {
        return String.format("%d.%02d%%", bp / 100, bp % 100);
    }
}
