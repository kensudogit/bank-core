package com.example.bankcore.loan;

import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.ledger.LedgerService;
import com.example.bankcore.loan.LoanModels.LedgerKind;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.outbox.OutboxRepository;
import com.example.bankcore.transaction.TransactionRepository;
import com.example.bankcore.transaction.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 延滞・不良債権管理：業務日付ごとに、すべての有効な融資の延滞日数・債務者区分を更新し、貸倒引当金を見直す。
 * 日次締め（{@code DailyClosingService}）から呼ばれるほか、API からも実行できる。
 *
 * <h2>再実行しても二重計上しない仕組み</h2>
 * <ul>
 *   <li>融資ごとに 1 トランザクション。融資行をロックしてから判定するので、同時に走った返済と矛盾しない</li>
 *   <li>引当の増減は冪等キー {@code provision-<業務日付>-<融資ID>} で記帳する。同じ日に何度実行しても 1 回だけ</li>
 *   <li>延滞日数・区分の更新は、その時点の返済予定表から決まる値の上書きなので何度実行しても同じ結果</li>
 * </ul>
 * 引当の仕訳：増加は 借方 貸倒引当金繰入額 ／ 貸方 貸倒引当金、減少（戻入れ）はその逆。
 */
@Service
public class LoanMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(LoanMonitoringService.class);

    /**
     * @param provisionsPosted この実行で引当を記帳した融資の件数
     * @param totalProvisionMinor 実行後の貸倒引当金の合計
     */
    public record MonitoringReport(LocalDate businessDate, int loansChecked, int delinquent, int nonPerforming,
                                   int provisionsPosted, long totalProvisionMinor) {
    }

    private record LoanOutcome(boolean delinquent, boolean nonPerforming, boolean posted, long provision) {
    }

    private final LoanRepository loans;
    private final TransactionRepository transactions;
    private final LedgerService ledger;
    private final OutboxRepository outbox;
    private final BusinessCalendar calendar;
    private final TransactionTemplate tx;

    public LoanMonitoringService(LoanRepository loans, TransactionRepository transactions, LedgerService ledger,
                                 OutboxRepository outbox, BusinessCalendar calendar, PlatformTransactionManager txManager) {
        this.loans = loans;
        this.transactions = transactions;
        this.ledger = ledger;
        this.outbox = outbox;
        this.calendar = calendar;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 現在の業務日付で実行する。 */
    public MonitoringReport run() {
        return run(calendar.today());
    }

    public MonitoringReport run(LocalDate businessDate) {
        int checked = 0;
        int delinquent = 0;
        int npl = 0;
        int posted = 0;
        long totalProvision = 0;
        for (long loanId : loans.openLoanIds()) {
            LoanOutcome o = tx.execute(s -> monitor(loanId, businessDate));
            if (o == null) {
                continue; // 一覧取得後に完済・償却された
            }
            checked++;
            delinquent += o.delinquent() ? 1 : 0;
            npl += o.nonPerforming() ? 1 : 0;
            posted += o.posted() ? 1 : 0;
            totalProvision += o.provision();
        }
        log.info("loan monitoring {}: checked={} delinquent={} nonPerforming={} provisionsPosted={}",
                businessDate, checked, delinquent, npl, posted);
        return new MonitoringReport(businessDate, checked, delinquent, npl, posted, totalProvision);
    }

    private LoanOutcome monitor(long loanId, LocalDate date) {
        Loan loan = loans.lockLoan(loanId).orElseThrow();
        if (!loan.status().open()) {
            return null;
        }
        int dpd = LoanService.daysPastDue(loans.installments(loanId), date);
        BorrowerClass cls = BorrowerClass.fromDaysPastDue(dpd);
        LoanStatus status = dpd > 0 ? LoanStatus.DELINQUENT : LoanStatus.ACTIVE;
        loans.updateBalanceAndState(loanId, loan.outstandingMinor(), status, dpd, cls, null);

        long secured = loans.securedMinor(loan.applicationId());
        long required = cls.requiredProvision(loan.outstandingMinor(), secured);
        long delta = required - loan.provisionMinor();
        boolean posted = delta != 0 && postProvision(loan, date, delta, required, cls);
        long provisionAfter = posted ? required : loan.provisionMinor();
        return new LoanOutcome(dpd > 0, cls.nonPerforming(), posted, provisionAfter);
    }

    /** @return 記帳した場合 true（同じ業務日付で記帳済みなら false） */
    private boolean postProvision(Loan loan, LocalDate date, long delta, long required, BorrowerClass cls) {
        String key = "provision-" + date + "-" + loan.id();
        String hash = LoanCommands.hash("PROVISION", loan.id(), date, delta);
        Optional<Long> reserved = transactions.tryReserveLoan(TransactionType.LOAN_PROVISION, key, hash, null, null,
                Math.abs(delta), loan.currency(), "貸倒引当金 " + (delta > 0 ? "繰入" : "戻入") + " 融資#" + loan.id(),
                null, loan.id());
        if (reserved.isEmpty()) {
            return false; // この業務日付の引当は記帳済み（引当は 1 日 1 回）
        }
        long txId = reserved.get();
        long amount = Math.abs(delta);
        String ccy = loan.currency();
        List<JournalLine> lines = delta > 0
                ? List.of(JournalLine.debit(GlCode.PROVISION_EXPENSE, null, amount, ccy),
                          JournalLine.creditLoan(GlCode.LOAN_LOSS_ALLOWANCE, loan.id(), amount, ccy))
                : List.of(JournalLine.debitLoan(GlCode.LOAN_LOSS_ALLOWANCE, loan.id(), amount, ccy),
                          JournalLine.credit(GlCode.PROVISION_EXPENSE, null, amount, ccy));
        ledger.post(txId, "貸倒引当金 融資#" + loan.id() + "（" + cls.label() + "）", lines);
        loans.updateProvision(loan.id(), required);
        loans.insertLedger(txId, loan.id(), LedgerKind.PROVISION, 0, 0, 0, 0, delta, loan.outstandingMinor(), required,
                cls.label() + " 引当率 " + cls.provisionRateBp() / 100.0 + "%");
        transactions.markCompleted(txId, loan.id());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("transactionId", txId);
        event.put("loanId", loan.id());
        event.put("borrowerClass", cls.name());
        event.put("provisionChangeMinor", delta);
        event.put("provisionMinor", required);
        outbox.append("Loan", Long.toString(loan.id()), "LoanProvisionAdjusted", event);
        return true;
    }
}
