package com.example.bankcore.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 返済計算（DB に依存しない純粋な計算）。金額は最小通貨単位の long、金利は年利 bp。
 *
 * <ul>
 *   <li>月利息 = 残高 × 年利 ÷ 12（1 円未満切り捨て）</li>
 *   <li>元利均等：毎回の返済額 = P·r·(1+r)^n / ((1+r)^n − 1)（r = 年利 / 12、1 円未満切り上げ）。最終回で端数を調整</li>
 *   <li>元金均等：毎回の元金 = P ÷ n（割り切れない端数は初回に加える）。利息は残高に応じて減る</li>
 *   <li>遅延損害金 = 延滞元利金 × 年 14.6% × 延滞日数 ÷ 365（1 円未満切り捨て）</li>
 * </ul>
 */
public final class LoanMath {

    /** 遅延損害金の年率（bp）。14.6% */
    public static final int LATE_FEE_RATE_BP = 1460;

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private LoanMath() {
    }

    /** 1 回分の返済。 */
    public record Installment(int number, LocalDate dueDate, long principalMinor, long interestMinor,
                              long balanceAfterMinor) {
        public long totalMinor() {
            return Math.addExact(principalMinor, interestMinor);
        }
    }

    /** 月利息（残高 × 年利 ÷ 12、切り捨て）。 */
    public static long monthlyInterest(long balanceMinor, long annualRateBp) {
        if (balanceMinor <= 0 || annualRateBp <= 0) {
            return 0;
        }
        return Math.multiplyExact(balanceMinor, annualRateBp) / 120_000L;
    }

    /** 元利均等返済の毎回の返済額（1 円未満切り上げ。切り上げで銀行側が受け取り不足にならない）。 */
    public static long equalPayment(long principalMinor, long annualRateBp, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("number of installments must be positive");
        }
        if (annualRateBp == 0) {
            return Math.ceilDiv(principalMinor, n);
        }
        BigDecimal r = BigDecimal.valueOf(annualRateBp).divide(BigDecimal.valueOf(120_000L), MC);
        BigDecimal pow = BigDecimal.ONE.add(r).pow(n, MC);
        return BigDecimal.valueOf(principalMinor).multiply(r, MC).multiply(pow, MC)
                .divide(pow.subtract(BigDecimal.ONE), MC)
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();
    }

    /** 毎月の約定返済日。実行日の k か月後（月末を超える日は月末に寄せる）。 */
    public static List<LocalDate> dueDates(LocalDate disbursedOn, int n) {
        List<LocalDate> dates = new ArrayList<>(n);
        for (int k = 1; k <= n; k++) {
            dates.add(disbursedOn.plusMonths(k));
        }
        return dates;
    }

    /**
     * 返済予定表。元金の合計は必ず principalMinor に一致する（最終回で端数を吸収する）。
     *
     * @param dueDates 各回の期日（件数が回数になる）
     */
    public static List<Installment> schedule(long principalMinor, long annualRateBp, RepaymentMethod method,
                                             List<LocalDate> dueDates) {
        int n = dueDates.size();
        if (n == 0) {
            throw new IllegalArgumentException("at least one installment is required");
        }
        if (principalMinor < 0 || annualRateBp < 0) {
            throw new IllegalArgumentException("principal and rate must not be negative");
        }
        List<Installment> list = new ArrayList<>(n);
        long balance = principalMinor;
        switch (method) {
            case EQUAL_PAYMENT -> {
                long payment = equalPayment(principalMinor, annualRateBp, n);
                for (int k = 1; k <= n; k++) {
                    long interest = monthlyInterest(balance, annualRateBp);
                    long principal = Math.max(0, payment - interest);
                    // 返済額を切り上げているので、最終回（または残高を超える回）は残高ちょうどにして過払いを防ぐ
                    if (k == n || principal > balance) {
                        principal = balance;
                    }
                    balance -= principal;
                    list.add(new Installment(k, dueDates.get(k - 1), principal, interest, balance));
                }
            }
            case EQUAL_PRINCIPAL -> {
                long base = principalMinor / n;
                long remainder = principalMinor % n;
                for (int k = 1; k <= n; k++) {
                    long interest = monthlyInterest(balance, annualRateBp);
                    long principal = k == n ? balance : (k == 1 ? base + remainder : base);
                    balance -= principal;
                    list.add(new Installment(k, dueDates.get(k - 1), principal, interest, balance));
                }
            }
        }
        return list;
    }

    /** 遅延損害金（延滞元利金 × 14.6% × 延滞日数 ÷ 365、切り捨て）。 */
    public static long lateFee(long overdueMinor, long daysLate) {
        if (overdueMinor <= 0 || daysLate <= 0) {
            return 0;
        }
        return Math.multiplyExact(Math.multiplyExact(overdueMinor, LATE_FEE_RATE_BP), daysLate) / (10_000L * 365);
    }

    /** 最初の 12 回（12 回未満なら全回）の返済額の合計。与信審査の年間返済額に使う。 */
    public static long firstYearRepayment(List<Installment> schedule) {
        return schedule.stream().limit(12).mapToLong(Installment::totalMinor).sum();
    }
}
