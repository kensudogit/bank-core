package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.BankProperties;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.ledger.LedgerService;
import com.example.bankcore.loan.LoanModels.ApplicationStatus;
import com.example.bankcore.loan.LoanModels.InstallmentStatus;
import com.example.bankcore.loan.LoanModels.LedgerEntry;
import com.example.bankcore.loan.LoanModels.LedgerKind;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanApplication;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanModels.ScheduledInstallment;
import com.example.bankcore.outbox.OutboxRepository;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionRepository;
import com.example.bankcore.transaction.TransactionRules;
import com.example.bankcore.transaction.TransactionType;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 融資の実行・返済・償却と、貸付元帳の照会。
 *
 * <h2>1 つの DB トランザクションで行うこと（資金移動 API と同じ考え方）</h2>
 * <ol>
 *   <li>冪等キーの予約（transactions に PENDING。同じキーの再送は記録済みの結果を返す）</li>
 *   <li>申込または融資の行ロック → 預金口座の行ロック（この順序を全処理で守り、デッドロックを防ぐ）</li>
 *   <li>ロック後の最新状態で業務ルールを検証（口座の状態・通貨・支払可能額、融資の状態）</li>
 *   <li>複式仕訳・預金残高・融資残高・返済予定表・貸付元帳・取引ステータス・Outbox をまとめて更新</li>
 * </ol>
 * 資金不足など「結果としての拒否」は REJECTED として記録してコミットする（再送しても同じ結果になる）。
 * 融資の状態が合わないなど「やり直しても通らない誤り」は例外（409）にしてロールバックし、冪等キーも残さない。
 *
 * <p>教育用の簡略化：利息は約定返済日の時点で返済予定表の額を収益にする（日次の未収利息計上は行わない）。</p>
 */
@Service
public class LoanService {

    /** @param loan 実行・返済後の融資（融資実行が拒否された場合は null） */
    public record LoanResult(MoneyTransaction transaction, Loan loan, boolean replayed) {
    }

    /**
     * @param amountDueMinor 本日までに期日が来た未払いの元金・利息・遅延損害金の合計
     * @param payoffMinor    本日完済する場合の金額（期日到来分 + 残りの元金）
     */
    public record LoanDetail(Loan loan, List<ScheduledInstallment> installments, long securedMinor,
                             long amountDueMinor, long payoffMinor, LocalDate businessDate) {
    }

    /** 返済額の充当結果（純粋な計算。{@link #allocate} 参照）。 */
    record Allocation(List<ScheduledInstallment> updated, long principal, long interest, long lateFee, long prepaid,
                      long excess) {
    }

    private final LoanRepository loans;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final LedgerService ledger;
    private final OutboxRepository outbox;
    private final BusinessCalendar calendar;
    private final TransactionTemplate tx;
    private final int maxAttempts;

    public LoanService(LoanRepository loans, AccountRepository accounts, TransactionRepository transactions,
                       LedgerService ledger, OutboxRepository outbox, BusinessCalendar calendar,
                       PlatformTransactionManager txManager, BankProperties properties) {
        this.loans = loans;
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.outbox = outbox;
        this.calendar = calendar;
        this.tx = new TransactionTemplate(txManager);
        this.maxAttempts = properties.transfer().maxAttempts();
    }

    // ================================================================== disbursement

    /**
     * 融資実行：承認済みの申込から融資と返済予定表を作り、貸付金を返済口座へ入金する。
     * 仕訳は 借方 貸出金（融資）／ 貸方 顧客預金（口座）。
     */
    public LoanResult disburse(long applicationId, String idempotencyKey, String correlationId) {
        LoanCommands.requireKey(idempotencyKey);
        return withRetry(() -> tx.execute(s -> disburseInTransaction(applicationId, idempotencyKey, correlationId)));
    }

    private LoanResult disburseInTransaction(long applicationId, String key, String correlationId) {
        LoanApplication pre = loans.findApplication(applicationId)
                .orElseThrow(() -> new NotFoundException("loan application " + applicationId + " not found"));
        Account account = accounts.findById(pre.accountId()).orElseThrow();
        String hash = LoanCommands.hash("DISBURSE", applicationId);
        Optional<Long> reserved = transactions.tryReserveLoan(TransactionType.LOAN_DISBURSEMENT, key, hash, null,
                pre.accountId(), pre.principalMinor(), account.currency(), "融資実行 申込#" + applicationId,
                correlationId, null);
        if (reserved.isEmpty()) {
            return replay(key, hash);
        }
        long txId = reserved.get();

        LoanApplication app = loans.lockApplication(applicationId).orElseThrow();
        if (app.status() != ApplicationStatus.APPROVED) {
            throw new BusinessConflictException("application " + applicationId + " is not approved (status="
                    + app.status() + ")");
        }
        Account target = accounts.lockAllInIdOrder(Set.of(app.accountId())).get(app.accountId());
        Optional<RejectReason> rejection = TransactionRules.check(null, target, app.principalMinor(), target.currency());
        if (rejection.isPresent()) {
            return new LoanResult(transactions.markRejected(txId, rejection.get()), null, false);
        }

        LocalDate today = calendar.today();
        List<LocalDate> dueDates = LoanMath.dueDates(today, app.termMonths());
        List<LoanMath.Installment> schedule = LoanMath.schedule(app.principalMinor(), app.approvedRateBp(),
                app.repaymentMethod(), dueDates);
        long loanId = loans.insertLoan(app, target.currency(), app.approvedRateBp(), today, dueDates.getLast());
        loans.insertInstallments(loanId, schedule);

        long principal = app.principalMinor();
        String ccy = target.currency();
        accounts.applyDeltas(Map.of(target.id(), principal));
        ledger.post(txId, "融資実行 融資#" + loanId + " -> " + target.accountNumber(), List.of(
                JournalLine.debitLoan(GlCode.LOANS_RECEIVABLE, loanId, principal, ccy),
                JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, target.id(), principal, ccy)));
        loans.insertLedger(txId, loanId, LedgerKind.DISBURSEMENT, principal, 0, 0, 0, 0, principal, 0, "融資実行");
        loans.markApplicationDisbursed(applicationId);
        MoneyTransaction completed = transactions.markCompleted(txId, loanId);
        outbox.append("Loan", Long.toString(loanId), "LoanDisbursed", payload(txId, loanId, principal, ccy));
        return new LoanResult(completed, loans.findLoan(loanId).orElseThrow(), false);
    }

    // ================================================================== repayment

    /**
     * 返済（返済口座から引落し）。期日の来た回に古い順に 遅延損害金 → 利息 → 元金 の順で充当し、
     * 残りは繰上返済として元金に充て、残りの回の返済額を再計算する（期間はそのまま・返済額軽減型）。
     * 仕訳は 借方 顧客預金 ／ 貸方 貸出金・貸出金利息・遅延損害金。完済時は残っている貸倒引当金を戻し入れる。
     */
    public LoanResult repay(long loanId, long amountMinor, String idempotencyKey, String correlationId) {
        LoanCommands.requireKey(idempotencyKey);
        if (amountMinor <= 0) {
            throw new InvalidRequestException("amountMinor must be positive");
        }
        return withRetry(() -> tx.execute(s -> repayInTransaction(loanId, amountMinor, idempotencyKey, correlationId)));
    }

    private LoanResult repayInTransaction(long loanId, long amount, String key, String correlationId) {
        Loan pre = loans.findLoan(loanId).orElseThrow(() -> new NotFoundException("loan " + loanId + " not found"));
        String hash = LoanCommands.hash("REPAY", loanId, amount);
        Optional<Long> reserved = transactions.tryReserveLoan(TransactionType.LOAN_REPAYMENT, key, hash,
                pre.accountId(), null, amount, pre.currency(), "返済 融資#" + loanId, correlationId, loanId);
        if (reserved.isEmpty()) {
            return replay(key, hash);
        }
        long txId = reserved.get();

        Loan loan = loans.lockLoan(loanId).orElseThrow();
        if (!loan.status().open()) {
            throw new BusinessConflictException("loan " + loanId + " is " + loan.status());
        }
        Account source = accounts.lockAllInIdOrder(Set.of(loan.accountId())).get(loan.accountId());
        Optional<RejectReason> rejection = TransactionRules.check(source, null, amount, loan.currency());
        if (rejection.isPresent()) {
            return new LoanResult(transactions.markRejected(txId, rejection.get()), loan, false);
        }

        LocalDate today = calendar.today();
        List<ScheduledInstallment> installments = loans.installments(loanId);
        Allocation a = allocate(installments, loan.outstandingMinor(), amount, today);
        if (a.excess() > 0) {
            return new LoanResult(transactions.markRejected(txId, RejectReason.AMOUNT_EXCEEDS_LOAN_BALANCE), loan, false);
        }
        for (ScheduledInstallment i : a.updated()) {
            InstallmentStatus st = i.unpaidPrincipal() == 0 && i.unpaidInterest() == 0
                    ? InstallmentStatus.PAID : InstallmentStatus.PARTIAL;
            loans.updatePayment(i, st, today);
        }
        long outstandingAfter = loan.outstandingMinor() - a.principal() - a.prepaid();
        if (a.prepaid() > 0) {
            reamortize(loan, outstandingAfter, today);
        }

        String ccy = loan.currency();
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, source.id(), amount, ccy));
        addCreditLoan(lines, GlCode.LOANS_RECEIVABLE, loanId, a.principal() + a.prepaid(), ccy);
        addCredit(lines, GlCode.LOAN_INTEREST_INCOME, a.interest(), ccy);
        addCredit(lines, GlCode.LATE_FEE_INCOME, a.lateFee(), ccy);

        List<ScheduledInstallment> after = loans.installments(loanId);
        boolean paidOff = outstandingAfter == 0 && after.stream().noneMatch(ScheduledInstallment::open);
        long provisionChange = 0;
        if (paidOff) {
            // 完済した融資に引当は不要なので、残っている貸倒引当金を戻し入れる（借方 貸倒引当金 ／ 貸方 繰入額）
            if (loan.provisionMinor() > 0) {
                lines.add(JournalLine.debitLoan(GlCode.LOAN_LOSS_ALLOWANCE, loanId, loan.provisionMinor(), ccy));
                lines.add(JournalLine.credit(GlCode.PROVISION_EXPENSE, null, loan.provisionMinor(), ccy));
                provisionChange = -loan.provisionMinor();
                loans.updateProvision(loanId, 0);
            }
            loans.updateBalanceAndState(loanId, 0, LoanStatus.PAID_OFF, 0, BorrowerClass.NORMAL, today);
            loans.releaseSecurity(loan.applicationId());
        } else {
            int dpd = daysPastDue(after, today);
            loans.updateBalanceAndState(loanId, outstandingAfter, dpd > 0 ? LoanStatus.DELINQUENT : LoanStatus.ACTIVE,
                    dpd, BorrowerClass.fromDaysPastDue(dpd), null);
        }

        accounts.applyDeltas(Map.of(source.id(), -amount));
        ledger.post(txId, "返済 融資#" + loanId + " <- " + source.accountNumber(), lines);
        loans.insertLedger(txId, loanId, LedgerKind.REPAYMENT, a.principal(), a.prepaid(), a.interest(), a.lateFee(),
                provisionChange, outstandingAfter, loan.provisionMinor() + provisionChange,
                paidOff ? "完済" : a.prepaid() > 0 ? "返済（繰上返済を含む）" : "返済");
        MoneyTransaction completed = transactions.markCompleted(txId, loanId);
        Map<String, Object> event = payload(txId, loanId, amount, ccy);
        event.put("principalMinor", a.principal() + a.prepaid());
        event.put("interestMinor", a.interest());
        event.put("lateFeeMinor", a.lateFee());
        event.put("paidOff", paidOff);
        outbox.append("Loan", Long.toString(loanId), paidOff ? "LoanPaidOff" : "LoanRepaid", event);
        return new LoanResult(completed, loans.findLoan(loanId).orElseThrow(), false);
    }

    /**
     * 返済額の充当（純粋な計算）。期日が本日以前の未完済の回に、古い順に 遅延損害金 → 利息 → 元金 で充当する。
     * 期日到来分を払い終えて余った額は繰上返済（元金）に充てる。残りの元金を超える分は excess として返す。
     */
    static Allocation allocate(List<ScheduledInstallment> installments, long outstandingMinor, long amount,
                               LocalDate today) {
        long remaining = amount;
        long principal = 0;
        long interest = 0;
        long fee = 0;
        List<ScheduledInstallment> updated = new ArrayList<>();
        for (ScheduledInstallment i : installments) {
            if (remaining == 0) {
                break;
            }
            if (!i.open() || i.dueDate().isAfter(today)) {
                continue;
            }
            long payFee = Math.min(feeDue(i, today), remaining);
            remaining -= payFee;
            long payInterest = Math.min(i.unpaidInterest(), remaining);
            remaining -= payInterest;
            long payPrincipal = Math.min(i.unpaidPrincipal(), remaining);
            remaining -= payPrincipal;
            if (payFee + payInterest + payPrincipal == 0) {
                continue;
            }
            fee += payFee;
            interest += payInterest;
            principal += payPrincipal;
            updated.add(new ScheduledInstallment(i.loanId(), i.installmentNo(), i.dueDate(), i.principalDue(),
                    i.interestDue(), i.principalPaid() + payPrincipal, i.interestPaid() + payInterest,
                    i.lateFeePaid() + payFee, i.status(), i.paidOn()));
        }
        long principalLeft = outstandingMinor - principal;
        long prepaid = Math.min(remaining, principalLeft);
        long excess = remaining - prepaid;
        return new Allocation(updated, principal, interest, fee, prepaid, excess);
    }

    /**
     * 未払いの遅延損害金。延滞元利金 × 14.6% × 延滞日数 ÷ 365 から、その回で支払済みの遅延損害金を差し引く
     * （教育用の簡略化：一部返済で延滞元利金が減った期間も、現在の未払い額で日数分を計算する）。
     */
    static long feeDue(ScheduledInstallment i, LocalDate today) {
        long daysLate = ChronoUnit.DAYS.between(i.dueDate(), today);
        long overdue = i.unpaidPrincipal() + i.unpaidInterest();
        return Math.max(0, LoanMath.lateFee(overdue, daysLate) - i.lateFeePaid());
    }

    /** 延滞日数：期日を過ぎて完済していない最も古い回の期日から本日まで。 */
    static int daysPastDue(List<ScheduledInstallment> installments, LocalDate today) {
        return installments.stream()
                .filter(i -> i.open() && i.dueDate().isBefore(today))
                .map(ScheduledInstallment::dueDate)
                .min(LocalDate::compareTo)
                .map(d -> (int) ChronoUnit.DAYS.between(d, today))
                .orElse(0);
    }

    /** 繰上返済後、本日より後の未着手の回について、残りの元金で返済予定を作り直す（回数と期日は変えない）。 */
    private void reamortize(Loan loan, long outstandingAfter, LocalDate today) {
        List<ScheduledInstallment> future = loans.installments(loan.id()).stream()
                .filter(i -> i.status() == InstallmentStatus.SCHEDULED && i.dueDate().isAfter(today))
                .toList();
        if (outstandingAfter == 0 || future.isEmpty()) {
            loans.cancelOpenInstallments(loan.id());
            return;
        }
        List<LoanMath.Installment> plan = LoanMath.schedule(outstandingAfter, loan.annualRateBp(),
                loan.repaymentMethod(), future.stream().map(ScheduledInstallment::dueDate).toList());
        for (int k = 0; k < future.size(); k++) {
            loans.reschedule(loan.id(), future.get(k).installmentNo(), plan.get(k).principalMinor(),
                    plan.get(k).interestMinor());
        }
    }

    // ================================================================== write-off

    /**
     * 償却（直接償却）：回収不能と判断した貸出金を帳簿から落とす。破綻懸念先・実質破綻先のみ。
     * 仕訳は 借方 貸倒引当金（引当済みの範囲）・貸倒損失（不足分）／ 貸方 貸出金。
     */
    public LoanResult writeOff(long loanId, String approvedBy, String reason, String idempotencyKey,
                               String correlationId) {
        LoanCommands.requireKey(idempotencyKey);
        String by = LoanCommands.requireText(approvedBy, "approvedBy", 50);
        String why = LoanCommands.requireText(reason, "reason", 150);
        return withRetry(() -> tx.execute(s -> writeOffInTransaction(loanId, by, why, idempotencyKey, correlationId)));
    }

    private LoanResult writeOffInTransaction(long loanId, String by, String reason, String key, String correlationId) {
        Loan pre = loans.findLoan(loanId).orElseThrow(() -> new NotFoundException("loan " + loanId + " not found"));
        String hash = LoanCommands.hash("WRITE_OFF", loanId, by, reason);
        // 金額はロック後の残高で確定させる（予約時は仮の値。完済済みなら下で 409 になりロールバックされる）
        Optional<Long> reserved = transactions.tryReserveLoan(TransactionType.LOAN_WRITE_OFF, key, hash, null, null,
                Math.max(1, pre.outstandingMinor()), pre.currency(), "償却 融資#" + loanId + " " + reason,
                correlationId, loanId);
        if (reserved.isEmpty()) {
            return replay(key, hash);
        }
        long txId = reserved.get();

        Loan loan = loans.lockLoan(loanId).orElseThrow();
        if (!loan.status().open()) {
            throw new BusinessConflictException("loan " + loanId + " is " + loan.status());
        }
        if (!loan.borrowerClass().writeOffEligible()) {
            throw new BusinessConflictException("only DOUBTFUL or DEFACTO_BANKRUPT loans can be written off (class="
                    + loan.borrowerClass() + ")");
        }
        long outstanding = loan.outstandingMinor();
        transactions.updatePendingAmount(txId, outstanding);
        long fromAllowance = Math.min(loan.provisionMinor(), outstanding);
        long loss = outstanding - fromAllowance;
        long excessAllowance = loan.provisionMinor() - fromAllowance;
        String ccy = loan.currency();

        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.creditLoan(GlCode.LOANS_RECEIVABLE, loanId, outstanding, ccy));
        if (fromAllowance > 0) {
            lines.add(JournalLine.debitLoan(GlCode.LOAN_LOSS_ALLOWANCE, loanId, fromAllowance, ccy));
        }
        if (loss > 0) {
            lines.add(JournalLine.debit(GlCode.LOAN_LOSS_EXPENSE, null, loss, ccy));
        }
        if (excessAllowance > 0) {
            // 残高を上回る引当が残っていれば戻し入れる（償却後の融資に引当は残さない）
            lines.add(JournalLine.debitLoan(GlCode.LOAN_LOSS_ALLOWANCE, loanId, excessAllowance, ccy));
            lines.add(JournalLine.credit(GlCode.PROVISION_EXPENSE, null, excessAllowance, ccy));
        }
        LocalDate today = calendar.today();
        loans.cancelOpenInstallments(loanId);
        loans.markWrittenOff(loanId, today);
        ledger.post(txId, "償却 融資#" + loanId, lines);
        loans.insertLedger(txId, loanId, LedgerKind.WRITE_OFF, outstanding, 0, 0, 0, -loan.provisionMinor(), 0, 0,
                "償却（承認 " + by + "）: " + reason);
        MoneyTransaction completed = transactions.markCompleted(txId, loanId);
        Map<String, Object> event = payload(txId, loanId, outstanding, ccy);
        event.put("fromAllowanceMinor", fromAllowance);
        event.put("lossMinor", loss);
        outbox.append("Loan", Long.toString(loanId), "LoanWrittenOff", event);
        return new LoanResult(completed, loans.findLoan(loanId).orElseThrow(), false);
    }

    // ================================================================== auto debit (daily closing)

    /** @param debited 引き落とした融資の件数、shortfall 期日到来分の全額を引き落とせなかった件数 */
    public record AutoDebitReport(LocalDate businessDate, int loansDue, int debited, int shortfall) {
    }

    /**
     * 約定返済の自動引落し（日次締めのステップ）。期日の来た未払いがある融資について、返済口座の支払可能額の範囲で
     * 引き落とす（足りない分は延滞になり、次のステップの延滞判定に反映される）。
     * 冪等キーを {@code loan-autodebit-<業務日付>-<融資ID>} と決めるので、締めを再実行しても二重に引き落とさない。
     */
    public AutoDebitReport autoDebit(LocalDate businessDate) {
        return autoDebit(businessDate, 1);
    }

    /**
     * @param parallelism 並行数。同じ返済口座の融資は 1 つのタスクで順に処理する（支払可能額を融資間で取り合わない）。
     *                    口座が違えばロックが重ならないので並行に引き落とせる
     */
    public AutoDebitReport autoDebit(LocalDate businessDate, int parallelism) {
        String keyPrefix = "loan-autodebit-" + businessDate + "-";
        // 期日の来ていない融資・この業務日付に引落し済みの融資は 1 本の SQL で除く（融資ごとの照会をしない）
        Map<Long, List<Long>> byAccount = loans.autoDebitCandidatesByAccount(businessDate, keyPrefix);
        AtomicInteger due = new AtomicInteger();
        AtomicInteger debited = new AtomicInteger();
        AtomicInteger shortfall = new AtomicInteger();
        Consumer<List<Long>> perAccount = loanIds -> {
            for (long loanId : loanIds) {
                String key = keyPrefix + loanId;
                LoanDetail d = get(loanId);
                if (d.amountDueMinor() == 0) {
                    continue;
                }
                due.incrementAndGet();
                long available = accounts.findById(d.loan().accountId()).map(Account::availableBalanceMinor).orElse(0L);
                long amount = Math.min(d.amountDueMinor(), Math.max(0, available));
                if (amount < d.amountDueMinor()) {
                    shortfall.incrementAndGet();
                }
                if (amount == 0) {
                    continue;
                }
                LoanResult r = repay(loanId, amount, key, null);
                if (r.transaction().status() == com.example.bankcore.transaction.TransactionStatus.COMPLETED) {
                    debited.incrementAndGet();
                }
            }
        };
        List<List<Long>> groups = new ArrayList<>(byAccount.values());
        int threads = Math.max(1, Math.min(parallelism, groups.size()));
        if (threads == 1) {
            groups.forEach(perAccount);
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (List<Long> g : groups) {
                    futures.add(pool.submit(() -> perAccount.accept(g)));
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
                        throw new IllegalStateException("interrupted", e);
                    }
                }
                if (first != null) {
                    throw first;   // 引落し済みの分はコミット済み。残りは再実行で拾う（冪等キーで二重にならない）
                }
            } finally {
                pool.shutdown();
            }
        }
        return new AutoDebitReport(businessDate, due.get(), debited.get(), shortfall.get());
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public LoanDetail get(long loanId) {
        Loan loan = loans.findLoan(loanId).orElseThrow(() -> new NotFoundException("loan " + loanId + " not found"));
        List<ScheduledInstallment> installments = loans.installments(loanId);
        LocalDate today = calendar.today();
        long due = 0;
        long duePrincipal = 0;
        for (ScheduledInstallment i : installments) {
            if (i.open() && !i.dueDate().isAfter(today)) {
                due += feeDue(i, today) + i.unpaidInterest() + i.unpaidPrincipal();
                duePrincipal += i.unpaidPrincipal();
            }
        }
        long payoff = due + (loan.outstandingMinor() - duePrincipal);
        return new LoanDetail(loan, installments, loans.securedMinor(loan.applicationId()), due, payoff, today);
    }

    @Transactional(readOnly = true)
    public List<Loan> list(LoanStatus status) {
        return loans.listLoans(status);
    }

    /** 貸付元帳（実行・返済・引当・償却の履歴と、各時点の残高）。 */
    @Transactional(readOnly = true)
    public List<LedgerEntry> ledger(long loanId) {
        loans.findLoan(loanId).orElseThrow(() -> new NotFoundException("loan " + loanId + " not found"));
        return loans.ledger(loanId);
    }

    /** 返済シミュレーション（DB に何も書かない）。 */
    public List<LoanMath.Installment> simulate(long principalMinor, int annualRateBp, int termMonths,
                                               RepaymentMethod method) {
        if (principalMinor <= 0 || termMonths <= 0 || termMonths > 600 || annualRateBp < 0
                || annualRateBp > CreditScoring.MAX_RATE_BP || method == null) {
            throw new InvalidRequestException("invalid simulation parameters");
        }
        return LoanMath.schedule(principalMinor, annualRateBp, method, LoanMath.dueDates(calendar.today(), termMonths));
    }

    // ================================================================== helpers

    private LoanResult replay(String key, String hash) {
        MoneyTransaction existing = transactions.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("idempotency record vanished"));
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyConflictException(key);
        }
        Loan loan = existing.loanId() == null ? null : loans.findLoan(existing.loanId()).orElse(null);
        return new LoanResult(existing, loan, true);
    }

    private static void addCredit(List<JournalLine> lines, String gl, long amount, String ccy) {
        if (amount > 0) {
            lines.add(JournalLine.credit(gl, null, amount, ccy));
        }
    }

    private static void addCreditLoan(List<JournalLine> lines, String gl, long loanId, long amount, String ccy) {
        if (amount > 0) {
            lines.add(JournalLine.creditLoan(gl, loanId, amount, ccy));
        }
    }

    private static Map<String, Object> payload(long txId, long loanId, long amount, String currency) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("transactionId", txId);
        p.put("loanId", loanId);
        p.put("amountMinor", amount);
        p.put("currency", currency);
        return p;
    }

    /** ロック待ちタイムアウトはジッター付きで有限回やり直す。冪等キーがあるので二重実行にはならない。 */
    private <T> T withRetry(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                try {
                    Thread.sleep((long) (50 * Math.pow(2, attempt - 1)) + ThreadLocalRandom.current().nextLong(50));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted during retry backoff", ie);
                }
            }
        }
    }
}
