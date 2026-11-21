package com.example.bankcore.batch;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.BankProperties;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.loan.LoanMonitoringService;
import com.example.bankcore.loan.LoanService;
import com.example.bankcore.payment.PaymentService;
import com.example.bankcore.transaction.MoneyMovementService;
import com.example.bankcore.transaction.MovementCommand;
import com.example.bankcore.transaction.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 日次締め：業務日付（勘定日付）の取引を確定させ、照合と承認を経て業務日付を翌日へ進める。
 *
 * <h2>締めの流れ</h2>
 * <ol>
 *   <li>{@code INTEREST_POSTING}   普通預金利息の計上</li>
 *   <li>{@code DIRECT_DEBIT}       引落日の来た口座振替</li>
 *   <li>{@code LOAN_AUTO_DEBIT}    融資の約定返済の自動引落し</li>
 *   <li>{@code LOAN_MONITORING}    融資の延滞判定と貸倒引当金の見直し</li>
 *   <li>{@code SECURITIES_INCOME}  有価証券の利金・償還</li>
 *   <li>{@code SECURITIES_VALUATION} 有価証券の時価評価</li>
 *   <li>{@code TRUST_PAYOUT}       満期を迎えた金銭信託の払出し</li>
 *   <li>{@code MERCHANT_SETTLEMENT} 加盟店への売上精算</li>
 *   <li>{@code AML_SCAN}           AML・不正取引の監視（当日の取引を含めて検査する）</li>
 *   <li>{@code RECONCILIATION}     照合：仕訳の貸借・預金残高と元帳・試算表・融資と為替の補助元帳・取引件数</li>
 *   <li>承認（{@code bank.closing.approval-required=true} のとき）：締めの実行者とは別の人が承認して初めて日付を進める</li>
 * </ol>
 * 「バッチが正常終了したこと」と「勘定が正しいこと」は別の条件として扱う。全ステップが成功しても照合が不一致なら
 * RECON_FAILED で止め、日付は進めない（修復して再実行する）。
 *
 * <h2>障害復旧・再実行しても二重計上しない仕組み</h2>
 * <ul>
 *   <li>ステップごとの実行記録（closing_steps）で、どこまで確定したかを特定する。再実行は完了済みのステップを飛ばし、
 *       失敗・未実行のステップから続ける。照合ステップは状態が変わり得るので毎回実行する</li>
 *   <li>各ステップの記帳は決定的な冪等キー（{@code interest-<日付>-<口座>}・{@code debit-<請求>}・
 *       {@code loan-autodebit-<日付>-<融資>}・{@code provision-<日付>-<融資>}）で行い、1 件 = 1 コミット。
 *       途中で落ちても確定済みの分はそのまま、未処理の分だけを再実行で拾う</li>
 *   <li>業務日付の更新は「現在値が締め対象日と一致するときだけ +1」の比較更新（CAS）。締めが同時に走っても 1 日しか進まない</li>
 *   <li>締めの確定（COMPLETED）と業務日付の更新は同じトランザクション</li>
 * </ul>
 * 実行・承認・差戻し・修復・ステップの再実行指示は closing_audit に記録する。
 *
 * <p>教育用の簡略化：利息は締め開始時点の記帳残高 × 年利 ÷ 365（円未満切り捨て）で計算する。</p>
 */
@Service
public class DailyClosingService {

    private static final Logger log = LoggerFactory.getLogger(DailyClosingService.class);

    /** 締めのステップ（番号順に実行する）。 */
    public enum Step {
        INTEREST_POSTING, DIRECT_DEBIT, LOAN_AUTO_DEBIT, LOAN_MONITORING, SECURITIES_INCOME, SECURITIES_VALUATION,
        TRUST_PAYOUT, MERCHANT_SETTLEMENT, AML_SCAN, RECONCILIATION
    }

    public record StepRecord(String stepName, int stepNo, String status, int attempts, int processed, String detail,
                             OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    /**
     * @param status           実行後の締めの状態（RECON_FAILED / AWAITING_APPROVAL / COMPLETED）
     * @param reconciliationOk 照合に合格したか
     */
    public record ClosingReport(
            LocalDate businessDate,
            LocalDate nextBusinessDate,
            boolean dateAdvanced,
            int accountsScanned,
            int interestPostedThisRun,
            int alreadyPosted,
            int zeroInterest,
            long annualRateBp,
            String status,
            boolean reconciliationOk,
            List<StepRecord> steps) {

        /**
         * ジョブスケジューラ（JP1/AJS など）向けの終了コード。
         * 0：照合合格（承認待ちまたは完了）、4：照合不一致（要修復）。ステップの失敗は例外となり 8 を返す。
         */
        public int exitCode() {
            return reconciliationOk ? 0 : 4;
        }
    }

    public record ClosingRecord(
            LocalDate businessDate, String status, OffsetDateTime startedAt, OffsetDateTime completedAt,
            int runCount, int interestPostings, String lastError, String operator, String approvedBy,
            OffsetDateTime approvedAt, Boolean reconciliationOk, String reconciliation, Integer journalCount,
            Integer transactionCount, Long debitTotalMinor) {
    }

    public record AuditRecord(long id, LocalDate businessDate, String action, String actor, String detail,
                              OffsetDateTime occurredAt) {
    }

    /** 締めの状態（ステップの実行記録と監査ログを含む）。 */
    public record ClosingStatus(ClosingRecord closing, List<StepRecord> steps, List<AuditRecord> audit,
                                LocalDate currentBusinessDate) {
    }

    private record InterestCounts(int scanned, int posted, int already, int zero) {
    }

    private record StepOutcome(int processed, String detail) {
    }

    private final BusinessCalendar calendar;
    private final AccountRepository accounts;
    private final MoneyMovementService movements;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final long annualRateBp;
    private final int parallelism;
    private final boolean approvalRequired;
    private final LoanMonitoringService loanMonitoring;
    private final LoanService loans;
    private final PaymentService payments;
    private final ReconciliationService reconciliation;
    private final com.example.bankcore.securities.SecuritiesService securities;
    private final com.example.bankcore.wealth.WealthService wealth;
    private final com.example.bankcore.cards.CardService cards;
    private final com.example.bankcore.compliance.AmlService aml;

    public DailyClosingService(BusinessCalendar calendar, AccountRepository accounts, MoneyMovementService movements,
                               JdbcClient jdbc, PlatformTransactionManager txManager, BankProperties properties,
                               LoanMonitoringService loanMonitoring, LoanService loans, PaymentService payments,
                               ReconciliationService reconciliation,
                               com.example.bankcore.securities.SecuritiesService securities,
                               com.example.bankcore.wealth.WealthService wealth,
                               com.example.bankcore.cards.CardService cards,
                               com.example.bankcore.compliance.AmlService aml) {
        this.securities = securities;
        this.wealth = wealth;
        this.cards = cards;
        this.aml = aml;
        this.calendar = calendar;
        this.accounts = accounts;
        this.movements = movements;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.annualRateBp = properties.interest().annualRateBp();
        this.parallelism = Math.max(1, properties.closing().parallelism());
        this.approvalRequired = properties.closing().approvalRequired();
        this.loanMonitoring = loanMonitoring;
        this.loans = loans;
        this.payments = payments;
        this.reconciliation = reconciliation;
    }

    /** 日額利息（最小通貨単位、切り捨て）。 */
    public static long dailyInterest(long balanceMinor, long annualRateBp) {
        if (balanceMinor <= 0 || annualRateBp <= 0) {
            return 0;
        }
        return Math.multiplyExact(balanceMinor, annualRateBp) / (10_000L * 365);
    }

    public boolean approvalRequired() {
        return approvalRequired;
    }

    /** 締めの実行者を指定しない実行（システムによる実行）。 */
    public ClosingReport run() {
        return run("SYSTEM");
    }

    /**
     * 当日の業務日付で締めを実行する。途中で失敗しても、もう一度呼べば完了済みのステップを飛ばして続きから処理する。
     *
     * @param operator 締めの実行者（承認者はこれとは別の人でなければならない）
     */
    public ClosingReport run(String operator) {
        String op = requireActor(operator, "operator");
        LocalDate date = calendar.today();
        registerRun(date, op);
        audit(date, "RUN", op, null);
        try {
            InterestCounts interest = new InterestCounts(0, 0, 0, 0);
            Optional<InterestCounts> postedInterest = step(date, Step.INTEREST_POSTING, () -> {
                InterestCounts c = postInterest(date);
                return new Result<>(c, new StepOutcome(c.posted(), "計上 " + c.posted() + " 件・計上済み " + c.already()
                        + " 件・利息 0 円 " + c.zero() + " 件"));
            });
            if (postedInterest.isPresent()) {
                interest = postedInterest.get();
            }
            step(date, Step.DIRECT_DEBIT, () -> {
                PaymentService.DirectDebitReport r = payments.processDirectDebits(date);
                return new Result<>(r, new StepOutcome(r.processed(), "振替済 " + r.paid() + " 件・資金不足 "
                        + r.insufficientFunds() + " 件・取引なし " + r.noTransaction() + " 件"));
            });
            step(date, Step.LOAN_AUTO_DEBIT, () -> {
                LoanService.AutoDebitReport r = loans.autoDebit(date, parallelism);
                return new Result<>(r, new StepOutcome(r.debited(), "期日到来 " + r.loansDue() + " 件・引落し "
                        + r.debited() + " 件・不足 " + r.shortfall() + " 件"));
            });
            step(date, Step.LOAN_MONITORING, () -> {
                LoanMonitoringService.MonitoringReport r = loanMonitoring.run(date);
                return new Result<>(r, new StepOutcome(r.loansChecked(), "延滞 " + r.delinquent() + " 件・不良債権 "
                        + r.nonPerforming() + " 件・引当記帳 " + r.provisionsPosted() + " 件"));
            });
            step(date, Step.SECURITIES_INCOME, () -> {
                var r = securities.processIncome(date);
                return new Result<>(r, new StepOutcome(r.coupons() + r.redemptions(), "利金 " + r.coupons() + " 件 "
                        + r.couponMinor() + " 円・償還 " + r.redemptions() + " 件 " + r.redeemedMinor() + " 円"));
            });
            step(date, Step.SECURITIES_VALUATION, () -> {
                var r = securities.markToMarket(date);
                return new Result<>(r, new StepOutcome(r.valued(), "評価 " + r.valued() + " 銘柄・評価差額 "
                        + r.totalAdjustmentMinor() + " 円"));
            });
            step(date, Step.TRUST_PAYOUT, () -> {
                int n = wealth.payoutMatured(date);
                return new Result<>(n, new StepOutcome(n, "満期払出し " + n + " 件"));
            });
            step(date, Step.MERCHANT_SETTLEMENT, () -> {
                var r = cards.settleMerchants();
                return new Result<>(r, new StepOutcome(r.size(), "精算 " + r.size() + " 加盟店・"
                        + r.stream().mapToLong(com.example.bankcore.cards.CardService.Settlement::netMinor).sum() + " 円"));
            });
            step(date, Step.AML_SCAN, () -> {
                var r = aml.scan();
                return new Result<>(r, new StepOutcome(r.alertsCreated(), "検査 取引 " + r.transactionsScanned()
                        + " 件・オーソリ " + r.authorizationsScanned() + " 件・アラート " + r.alertsCreated() + " 件"));
            });
            boolean reconOk = reconcile(date, op);

            boolean advanced = false;
            String status;
            if (!reconOk) {
                status = "RECON_FAILED";
            } else if (approvalRequired) {
                status = setStatusUnlessCompleted(date, "AWAITING_APPROVAL");
                if ("AWAITING_APPROVAL".equals(status)) {
                    audit(date, "AWAITING_APPROVAL", op, null);
                }
            } else {
                advanced = complete(date, "AUTO");
                status = "COMPLETED";
            }
            log.info("daily closing {} by {}: status={} interestPosted={} advanced={}", date, op, status,
                    interest.posted(), advanced);
            return new ClosingReport(date, date.plusDays(1), advanced, interest.scanned(), interest.posted(),
                    interest.already(), interest.zero(), annualRateBp, status, reconOk, steps(date));
        } catch (RuntimeException e) {
            recordError(date, e);
            audit(date, "STEP_FAILED", op, message(e));
            throw e;
        }
    }

    /**
     * 承認。照合に合格して承認待ちの締めについて、実行者とは別の人が承認すると業務日付を進める。
     * 承認の直前にもう一度照合し、承認待ちの間に勘定が変わっていないことを確かめる。
     */
    public ClosingStatus approve(LocalDate businessDate, String approver) {
        String by = requireActor(approver, "approver");
        ClosingRecord c = record(businessDate);
        if (!"AWAITING_APPROVAL".equals(c.status())) {
            throw new BusinessConflictException("closing " + businessDate + " is not awaiting approval (status=" + c.status() + ")");
        }
        if (by.equals(c.operator())) {
            throw new BusinessConflictException("the operator who ran the closing cannot approve it");
        }
        if (!reconcile(businessDate, by)) {
            throw new BusinessConflictException("reconciliation failed just before approval; repair and re-run the closing");
        }
        audit(businessDate, "APPROVE", by, null);
        boolean advanced = complete(businessDate, by);
        if (!advanced) {
            throw new BusinessConflictException("business date has already moved past " + businessDate);
        }
        return status(businessDate);
    }

    /** 差戻し。承認待ちの締めを REJECTED にする（修復して再実行する）。 */
    public ClosingStatus reject(LocalDate businessDate, String approver, String reason) {
        String by = requireActor(approver, "approver");
        String why = requireText(reason, "reason");
        int updated = tx.execute(s -> jdbc.sql("""
                        UPDATE daily_closings SET status = 'REJECTED', last_error = :why
                         WHERE business_date = :d AND status = 'AWAITING_APPROVAL'""")
                .param("why", why).param("d", businessDate).update());
        if (updated == 0) {
            throw new BusinessConflictException("closing " + businessDate + " is not awaiting approval");
        }
        audit(businessDate, "REJECT", by, why);
        return status(businessDate);
    }

    /**
     * 修復の記録。照合不一致や差戻しの原因を、取消（反対仕訳）などの通常の手続きで直したことを記録する。
     * 修復後は締めを再実行し、照合からやり直す（記帳済みの仕訳を直接書き換えることはしない）。
     */
    public ClosingStatus recordRepair(LocalDate businessDate, String actor, String description) {
        String by = requireActor(actor, "actor");
        ClosingRecord c = record(businessDate);
        if ("COMPLETED".equals(c.status())) {
            throw new BusinessConflictException("closing " + businessDate + " is already completed");
        }
        audit(businessDate, "REPAIR", by, requireText(description, "description"));
        return status(businessDate);
    }

    /** 完了済みのステップを再実行させる（各ステップは冪等なので、再実行しても二重計上はしない）。 */
    public ClosingStatus resetStep(LocalDate businessDate, Step step, String actor) {
        String by = requireActor(actor, "actor");
        ClosingRecord c = record(businessDate);
        if ("COMPLETED".equals(c.status())) {
            throw new BusinessConflictException("closing " + businessDate + " is already completed");
        }
        int updated = tx.execute(s -> jdbc.sql("""
                        UPDATE closing_steps SET status = 'FAILED', detail = 'reset by ' || :by
                         WHERE business_date = :d AND step_name = :step""")
                .param("by", by).param("d", businessDate).param("step", step.name()).update());
        if (updated == 0) {
            throw new NotFoundException("step " + step + " has not run for " + businessDate);
        }
        audit(businessDate, "STEP_RESET", by, step.name());
        return status(businessDate);
    }

    public ClosingStatus status(LocalDate businessDate) {
        return new ClosingStatus(record(businessDate), steps(businessDate), auditTrail(businessDate), calendar.today());
    }

    public List<ClosingRecord> history() {
        return jdbc.sql("SELECT * FROM daily_closings ORDER BY business_date DESC").query((rs, i) -> closingRecord(rs)).list();
    }

    // ================================================================== steps

    private record Result<T>(T value, StepOutcome outcome) {
    }

    /**
     * ステップを実行する。完了済みのステップは飛ばす（empty を返す）。失敗したら FAILED を記録して例外を投げる。
     * 同時に 2 本の締めが同じステップを実行しても、ステップの中の記帳は冪等なので二重計上にはならない。
     */
    private <T> Optional<T> step(LocalDate date, Step step, Supplier<Result<T>> body) {
        boolean start = Boolean.TRUE.equals(tx.execute(s -> jdbc.sql("""
                        INSERT INTO closing_steps (business_date, step_no, step_name, status)
                        VALUES (:d, :no, :name, 'RUNNING')
                        ON CONFLICT (business_date, step_name) DO UPDATE
                           SET status = 'RUNNING', attempts = closing_steps.attempts + 1, started_at = now(),
                               finished_at = NULL, detail = NULL
                         WHERE closing_steps.status <> 'COMPLETED'
                        RETURNING 1""")
                .param("d", date).param("no", step.ordinal() + 1).param("name", step.name())
                .query(Integer.class).optional().isPresent()));
        if (!start) {
            return Optional.empty();
        }
        try {
            Result<T> r = body.get();
            finishStep(date, step, "COMPLETED", r.outcome().processed(), r.outcome().detail());
            return Optional.ofNullable(r.value());
        } catch (RuntimeException e) {
            finishStep(date, step, "FAILED", 0, message(e));
            throw e;
        }
    }

    private void finishStep(LocalDate date, Step step, String status, int processed, String detail) {
        tx.executeWithoutResult(s -> jdbc.sql("""
                        UPDATE closing_steps SET status = :status, processed = :processed, detail = :detail, finished_at = now()
                         WHERE business_date = :d AND step_name = :name""")
                .param("status", status).param("processed", processed).param("detail", detail)
                .param("d", date).param("name", step.name()).update());
    }

    /** 利息計上（口座ごとに 1 トランザクション。冪等キー interest-<業務日付>-<口座ID>）。 */
    private InterestCounts postInterest(LocalDate date) {
        String keyPrefix = "interest-" + date + "-";
        // 計上済み口座を 1 回のクエリでまとめて取得する（口座ごとに冪等キーを照会しない）
        Set<Long> alreadyPostedIds = new HashSet<>(jdbc.sql("""
                        SELECT target_account_id FROM transactions
                         WHERE type = 'INTEREST' AND idempotency_key LIKE :prefix""")
                .param("prefix", keyPrefix + "%")
                .query(Long.class)
                .list());
        int scanned = 0;
        int already = 0;
        int zero = 0;
        List<MovementCommand> commands = new ArrayList<>();
        for (Account account : accounts.findAll()) {
            if (!account.isActive()) {
                continue;
            }
            scanned++;
            if (alreadyPostedIds.contains(account.id())) {
                already++; // 前回の実行（またはクラッシュ前）で計上済み
                continue;
            }
            long amount = dailyInterest(account.postedBalanceMinor(), annualRateBp);
            if (amount == 0) {
                zero++;
                continue;
            }
            commands.add(MovementCommand.interest(
                    keyPrefix + account.id(), account.id(), amount, account.currency(), "利息 " + date));
        }
        int[] counts = postInParallel(commands);
        return new InterestCounts(scanned, counts[0], already + counts[1], zero);
    }

    /**
     * 口座ごとの利息計上（1 口座 = 1 トランザクション）を並行実行する。
     * どれかが失敗したら新しい計上の開始をやめ、実行中のものの完了を待ってから最初の例外を投げる
     * （計上済み分はコミット済みで、残りは再実行で拾う）。
     *
     * @return [計上した件数, 計上済みだった件数]
     */
    private int[] postInParallel(List<MovementCommand> commands) {
        if (commands.isEmpty()) {
            return new int[]{0, 0};
        }
        AtomicInteger posted = new AtomicInteger();
        AtomicInteger already = new AtomicInteger();
        AtomicBoolean failed = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(parallelism, commands.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (MovementCommand cmd : commands) {
                futures.add(pool.submit(() -> {
                    if (failed.get()) {
                        return;
                    }
                    try {
                        var result = movements.execute(cmd);
                        if (result.replayed()) {
                            already.incrementAndGet();
                        } else if (result.transaction().status() == TransactionStatus.COMPLETED) {
                            posted.incrementAndGet();
                        }
                    } catch (IdempotencyConflictException e) {
                        // 同時に走った別の締め処理が、異なる残高から計算して先に計上した
                        already.incrementAndGet();
                    } catch (RuntimeException e) {
                        failed.set(true);
                        throw e;
                    }
                }));
            }
            RuntimeException first = null;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    if (first == null) {
                        first = e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted during daily closing", e);
                }
            }
            if (first != null) {
                throw first;
            }
            return new int[]{posted.get(), already.get()};
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================== reconciliation gate

    /**
     * 照合ステップ（毎回実行する）。総勘定元帳の照合に加え、取引と仕訳の件数を突き合わせる：
     * 完了した取引には必ず仕訳が 1 つあり、仕訳は必ず完了した取引のものであり、未完了（PENDING）の取引が残っていないこと。
     * 結果は daily_closings に保存し、不一致なら RECON_FAILED にする。
     */
    private boolean reconcile(LocalDate date, String actor) {
        tx.executeWithoutResult(s -> jdbc.sql("""
                        INSERT INTO closing_steps (business_date, step_no, step_name, status) VALUES (:d, :no, 'RECONCILIATION', 'RUNNING')
                        ON CONFLICT (business_date, step_name) DO UPDATE
                           SET status = 'RUNNING', attempts = closing_steps.attempts + 1, started_at = now(), finished_at = NULL""")
                .param("d", date).param("no", Step.RECONCILIATION.ordinal() + 1).update());
        ReconciliationService.Report report = reconciliation.run();
        long pending = count("SELECT count(*) FROM transactions WHERE status = 'PENDING'");
        long withoutJournal = count("""
                SELECT count(*) FROM transactions t
                 WHERE t.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.transaction_id = t.id)""");
        long journalWithoutCompleted = count("""
                SELECT count(*) FROM journal_entries e JOIN transactions t ON t.id = e.transaction_id
                 WHERE t.status <> 'COMPLETED'""");
        var day = jdbc.sql("""
                        SELECT count(DISTINCT e.id) AS journals, COALESCE(SUM(l.debit_minor), 0) AS debit
                          FROM journal_entries e JOIN journal_lines l ON l.journal_entry_id = e.id
                         WHERE e.business_date = :d""")
                .param("d", date).query().singleRow();
        int journals = ((Number) day.get("journals")).intValue();
        long debit = ((Number) day.get("debit")).longValue();
        int completedTx = (int) count("""
                SELECT count(*) FROM transactions t JOIN journal_entries e ON e.transaction_id = t.id
                 WHERE t.status = 'COMPLETED' AND e.business_date = '%s'""".formatted(date));

        boolean ok = report.ok() && pending == 0 && withoutJournal == 0 && journalWithoutCompleted == 0
                && journals == completedTx;
        String summary = "仕訳の貸借不一致 " + report.unbalancedJournals().size()
                + " / 預金残高の不一致 " + report.accountMismatches().size()
                + " / 融資補助元帳の不一致 " + report.loanMismatches().size()
                + " / 為替経過勘定の不一致 " + report.subledgerMismatches().size()
                + " / 未完了の取引 " + pending
                + " / 仕訳のない完了取引 " + withoutJournal
                + " / 完了していない取引の仕訳 " + journalWithoutCompleted
                + " / 当日の仕訳 " + journals + " 件・完了取引 " + completedTx + " 件・借方合計 " + debit;
        tx.executeWithoutResult(s -> {
            jdbc.sql("""
                            UPDATE daily_closings
                               SET reconciliation_ok = :ok, reconciliation = :summary, journal_count = :journals,
                                   transaction_count = :tx, debit_total_minor = :debit,
                                   status = CASE WHEN status = 'COMPLETED' THEN status
                                                 WHEN :ok THEN status ELSE 'RECON_FAILED' END
                             WHERE business_date = :d""")
                    .param("ok", ok).param("summary", summary).param("journals", journals).param("tx", completedTx)
                    .param("debit", debit).param("d", date).update();
            jdbc.sql("""
                            UPDATE closing_steps SET status = :st, processed = :n, detail = :detail, finished_at = now()
                             WHERE business_date = :d AND step_name = 'RECONCILIATION'""")
                    .param("st", ok ? "COMPLETED" : "FAILED").param("n", journals).param("detail", summary)
                    .param("d", date).update();
        });
        if (!ok) {
            audit(date, "RECON_FAILED", actor, summary);
            log.warn("daily closing {}: reconciliation failed: {}", date, summary);
        }
        return ok;
    }

    // ================================================================== state

    private void registerRun(LocalDate date, String operator) {
        tx.executeWithoutResult(s -> jdbc.sql("""
                        INSERT INTO daily_closings (business_date, status, operator) VALUES (:d, 'RUNNING', :op)
                        ON CONFLICT (business_date) DO UPDATE
                           SET run_count = daily_closings.run_count + 1, last_error = NULL, operator = :op,
                               status = CASE WHEN daily_closings.status = 'COMPLETED' THEN 'COMPLETED' ELSE 'RUNNING' END""")
                .param("d", date)
                .param("op", operator)
                .update());
    }

    private String setStatusUnlessCompleted(LocalDate date, String status) {
        return tx.execute(s -> jdbc.sql("""
                        UPDATE daily_closings SET status = CASE WHEN status = 'COMPLETED' THEN status ELSE :st END
                         WHERE business_date = :d RETURNING status""")
                .param("st", status).param("d", date).query(String.class).single());
    }

    /** 締めの確定（COMPLETED）と業務日付の更新を 1 トランザクションで行う。 */
    private boolean complete(LocalDate date, String approvedBy) {
        Boolean advanced = tx.execute(s -> {
            jdbc.sql("""
                            UPDATE daily_closings
                               SET status = 'COMPLETED', completed_at = COALESCE(completed_at, now()),
                                   approved_by = COALESCE(approved_by, :by), approved_at = COALESCE(approved_at, now()),
                                   interest_postings = (SELECT count(*) FROM transactions
                                                         WHERE type = 'INTEREST' AND status = 'COMPLETED'
                                                           AND idempotency_key LIKE :prefix)
                             WHERE business_date = :d""")
                    .param("by", approvedBy)
                    .param("prefix", "interest-" + date + "-%")
                    .param("d", date)
                    .update();
            return calendar.advanceFrom(date);
        });
        if (Boolean.TRUE.equals(advanced)) {
            audit(date, "COMPLETED", approvedBy, "業務日付を " + date.plusDays(1) + " に更新");
        }
        return Boolean.TRUE.equals(advanced);
    }

    private void recordError(LocalDate date, RuntimeException e) {
        try {
            tx.executeWithoutResult(s -> jdbc.sql("UPDATE daily_closings SET last_error = :err WHERE business_date = :d")
                    .param("err", message(e))
                    .param("d", date)
                    .update());
        } catch (RuntimeException ignored) {
            log.warn("failed to record closing error", ignored);
        }
    }

    private void audit(LocalDate date, String action, String actor, String detail) {
        try {
            tx.executeWithoutResult(s -> jdbc.sql("""
                            INSERT INTO closing_audit (business_date, action, actor, detail) VALUES (:d, :a, :actor, :detail)""")
                    .param("d", date).param("a", action).param("actor", actor).param("detail", detail).update());
        } catch (RuntimeException e) {
            log.warn("failed to write closing audit", e);
        }
    }

    private ClosingRecord record(LocalDate date) {
        return jdbc.sql("SELECT * FROM daily_closings WHERE business_date = :d").param("d", date)
                .query((rs, i) -> closingRecord(rs)).optional()
                .orElseThrow(() -> new NotFoundException("no closing for " + date));
    }

    private static ClosingRecord closingRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClosingRecord(rs.getObject("business_date", LocalDate.class), rs.getString("status"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("completed_at", OffsetDateTime.class),
                rs.getInt("run_count"), rs.getInt("interest_postings"), rs.getString("last_error"),
                rs.getString("operator"), rs.getString("approved_by"), rs.getObject("approved_at", OffsetDateTime.class),
                (Boolean) rs.getObject("reconciliation_ok"), rs.getString("reconciliation"),
                (Integer) rs.getObject("journal_count"), (Integer) rs.getObject("transaction_count"),
                rs.getObject("debit_total_minor") == null ? null : rs.getLong("debit_total_minor"));
    }

    private List<StepRecord> steps(LocalDate date) {
        return jdbc.sql("SELECT * FROM closing_steps WHERE business_date = :d ORDER BY step_no").param("d", date)
                .query((rs, i) -> new StepRecord(rs.getString("step_name"), rs.getInt("step_no"), rs.getString("status"),
                        rs.getInt("attempts"), rs.getInt("processed"), rs.getString("detail"),
                        rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)))
                .list();
    }

    private List<AuditRecord> auditTrail(LocalDate date) {
        return jdbc.sql("SELECT * FROM closing_audit WHERE business_date = :d ORDER BY id").param("d", date)
                .query((rs, i) -> new AuditRecord(rs.getLong("id"), rs.getObject("business_date", LocalDate.class),
                        rs.getString("action"), rs.getString("actor"), rs.getString("detail"),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static String message(RuntimeException e) {
        String m = String.valueOf(e.getMessage());
        return m.substring(0, Math.min(1000, m.length()));
    }

    private static String requireActor(String actor, String name) {
        if (actor == null || actor.isBlank() || actor.length() > 50) {
            throw new InvalidRequestException(name + " is required (max 50 characters)");
        }
        return actor.trim();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 1000) {
            throw new InvalidRequestException(name + " is required (max 1000 characters)");
        }
        return value.trim();
    }
}
