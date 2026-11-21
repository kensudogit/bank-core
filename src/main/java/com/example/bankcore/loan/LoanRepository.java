package com.example.bankcore.loan;

import com.example.bankcore.loan.CreditScoring.Recommendation;
import com.example.bankcore.loan.LoanModels.ApplicationStatus;
import com.example.bankcore.loan.LoanModels.Collateral;
import com.example.bankcore.loan.LoanModels.Guarantee;
import com.example.bankcore.loan.LoanModels.InstallmentStatus;
import com.example.bankcore.loan.LoanModels.LedgerEntry;
import com.example.bankcore.loan.LoanModels.LedgerKind;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanApplication;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanModels.ScheduledInstallment;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** 融資関連テーブルへのアクセス。ロック系メソッドはトランザクション内でのみ呼ぶ。 */
@Repository
public class LoanRepository {

    // ------------------------------------------------------------------ mappers

    private static final String APPLICATION_COLUMNS = """
            id, account_id, product, principal_minor, term_months, repayment_method, annual_income_minor,
            existing_annual_debt_minor, purpose, status, submitted_by, submitted_at, score, grade, dti_bp,
            coverage_bp, proposed_rate_bp, recommendation, screening_reasons, screened_at, approved_rate_bp,
            decided_by, decision_note, decided_at""";

    private static final RowMapper<LoanApplication> APPLICATION = (rs, i) -> new LoanApplication(
            rs.getLong("id"), rs.getLong("account_id"), LoanProduct.valueOf(rs.getString("product")),
            rs.getLong("principal_minor"), rs.getInt("term_months"),
            RepaymentMethod.valueOf(rs.getString("repayment_method")), rs.getLong("annual_income_minor"),
            rs.getLong("existing_annual_debt_minor"), rs.getString("purpose"),
            ApplicationStatus.valueOf(rs.getString("status")), rs.getString("submitted_by"),
            rs.getObject("submitted_at", OffsetDateTime.class),
            (Integer) rs.getObject("score"), rs.getString("grade"),
            rs.getObject("dti_bp") == null ? null : rs.getLong("dti_bp"),
            rs.getObject("coverage_bp") == null ? null : rs.getLong("coverage_bp"),
            (Integer) rs.getObject("proposed_rate_bp"),
            rs.getString("recommendation") == null ? null : Recommendation.valueOf(rs.getString("recommendation")),
            rs.getString("screening_reasons"), rs.getObject("screened_at", OffsetDateTime.class),
            (Integer) rs.getObject("approved_rate_bp"), rs.getString("decided_by"), rs.getString("decision_note"),
            rs.getObject("decided_at", OffsetDateTime.class));

    private static final RowMapper<Collateral> COLLATERAL = (rs, i) -> new Collateral(
            rs.getLong("id"), rs.getLong("application_id"), CollateralType.valueOf(rs.getString("type")),
            rs.getString("description"), rs.getLong("appraisal_minor"), rs.getInt("haircut_bp"),
            (Integer) rs.getObject("lien_rank"), rs.getString("status"),
            rs.getObject("appraised_at", OffsetDateTime.class));

    private static final RowMapper<Guarantee> GUARANTEE = (rs, i) -> new Guarantee(
            rs.getLong("id"), rs.getLong("application_id"), GuaranteeType.valueOf(rs.getString("type")),
            rs.getString("guarantor_name"), rs.getLong("coverage_minor"), rs.getInt("recognition_bp"),
            rs.getString("status"));

    private static final String LOAN_COLUMNS = """
            id, application_id, account_id, product, currency, principal_minor, outstanding_minor, annual_rate_bp,
            term_months, repayment_method, grade, status, days_past_due, borrower_class, provision_minor,
            disbursed_on, maturity_date, closed_on, version""";

    private static final RowMapper<Loan> LOAN = (rs, i) -> new Loan(
            rs.getLong("id"), rs.getLong("application_id"), rs.getLong("account_id"),
            LoanProduct.valueOf(rs.getString("product")), rs.getString("currency"), rs.getLong("principal_minor"),
            rs.getLong("outstanding_minor"), rs.getInt("annual_rate_bp"), rs.getInt("term_months"),
            RepaymentMethod.valueOf(rs.getString("repayment_method")), rs.getString("grade"),
            LoanStatus.valueOf(rs.getString("status")), rs.getInt("days_past_due"),
            BorrowerClass.valueOf(rs.getString("borrower_class")), rs.getLong("provision_minor"),
            rs.getObject("disbursed_on", LocalDate.class), rs.getObject("maturity_date", LocalDate.class),
            rs.getObject("closed_on", LocalDate.class), rs.getLong("version"));

    private static final RowMapper<ScheduledInstallment> INSTALLMENT = (rs, i) -> new ScheduledInstallment(
            rs.getLong("loan_id"), rs.getInt("installment_no"), rs.getObject("due_date", LocalDate.class),
            rs.getLong("principal_due"), rs.getLong("interest_due"), rs.getLong("principal_paid"),
            rs.getLong("interest_paid"), rs.getLong("late_fee_paid"),
            InstallmentStatus.valueOf(rs.getString("status")), rs.getObject("paid_on", LocalDate.class));

    private static final RowMapper<LedgerEntry> LEDGER = (rs, i) -> new LedgerEntry(
            rs.getLong("transaction_id"), rs.getLong("loan_id"), rs.getObject("business_date", LocalDate.class),
            LedgerKind.valueOf(rs.getString("kind")), rs.getLong("principal_minor"),
            rs.getLong("prepaid_principal_minor"), rs.getLong("interest_minor"), rs.getLong("late_fee_minor"),
            rs.getLong("provision_change_minor"), rs.getLong("outstanding_after_minor"),
            rs.getLong("provision_after_minor"), rs.getString("note"), rs.getObject("posted_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public LoanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ applications

    public long insertApplication(long accountId, LoanProduct product, long principalMinor, int termMonths,
                                  RepaymentMethod method, long annualIncomeMinor, long existingAnnualDebtMinor,
                                  String purpose, String submittedBy) {
        return jdbc.sql("""
                        INSERT INTO loan_applications (account_id, product, principal_minor, term_months, repayment_method,
                            annual_income_minor, existing_annual_debt_minor, purpose, status, submitted_by)
                        VALUES (:account, :product, :principal, :term, :method, :income, :debt, :purpose, 'SUBMITTED', :by)
                        RETURNING id""")
                .param("account", accountId)
                .param("product", product.name())
                .param("principal", principalMinor)
                .param("term", termMonths)
                .param("method", method.name())
                .param("income", annualIncomeMinor)
                .param("debt", existingAnnualDebtMinor)
                .param("purpose", purpose)
                .param("by", submittedBy)
                .query(Long.class)
                .single();
    }

    public Optional<LoanApplication> findApplication(long id) {
        return jdbc.sql("SELECT " + APPLICATION_COLUMNS + " FROM loan_applications WHERE id = :id")
                .param("id", id).query(APPLICATION).optional();
    }

    /** 申込の状態遷移（審査・決裁・実行）を直列化する行ロック。 */
    public Optional<LoanApplication> lockApplication(long id) {
        return jdbc.sql("SELECT " + APPLICATION_COLUMNS + " FROM loan_applications WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id).query(APPLICATION).optional();
    }

    public List<LoanApplication> listApplications() {
        return jdbc.sql("SELECT " + APPLICATION_COLUMNS + " FROM loan_applications ORDER BY id DESC LIMIT 200")
                .query(APPLICATION).list();
    }

    public void saveScreening(long id, CreditScoring.Result r) {
        jdbc.sql("""
                        UPDATE loan_applications
                           SET status = 'SCREENED', score = :score, grade = :grade, dti_bp = :dti, coverage_bp = :cov,
                               proposed_rate_bp = :rate, recommendation = :rec, screening_reasons = :reasons,
                               screened_at = now()
                         WHERE id = :id""")
                .param("score", r.score())
                .param("grade", r.grade())
                .param("dti", r.dtiBp())
                .param("cov", r.coverageBp())
                .param("rate", r.proposedRateBp())
                .param("rec", r.recommendation().name())
                .param("reasons", String.join(" / ", r.reasons()))
                .param("id", id)
                .update();
    }

    public void saveDecision(long id, boolean approve, Integer approvedRateBp, String decidedBy, String note) {
        jdbc.sql("""
                        UPDATE loan_applications
                           SET status = :status, approved_rate_bp = :rate, decided_by = :by, decision_note = :note,
                               decided_at = now()
                         WHERE id = :id""")
                .param("status", approve ? "APPROVED" : "REJECTED")
                .param("rate", approvedRateBp)
                .param("by", decidedBy)
                .param("note", note)
                .param("id", id)
                .update();
    }

    public void markApplicationDisbursed(long id) {
        jdbc.sql("UPDATE loan_applications SET status = 'DISBURSED' WHERE id = :id AND status = 'APPROVED'")
                .param("id", id).update();
    }

    // ------------------------------------------------------------------ collateral / guarantees

    public long insertCollateral(long applicationId, CollateralType type, String description, long appraisalMinor,
                                 Integer lienRank) {
        return jdbc.sql("""
                        INSERT INTO collaterals (application_id, type, description, appraisal_minor, haircut_bp, lien_rank)
                        VALUES (:app, :type, :description, :appraisal, :haircut, :lien)
                        RETURNING id""")
                .param("app", applicationId)
                .param("type", type.name())
                .param("description", description)
                .param("appraisal", appraisalMinor)
                .param("haircut", type.haircutBp())
                .param("lien", lienRank)
                .query(Long.class)
                .single();
    }

    public long insertGuarantee(long applicationId, GuaranteeType type, String guarantorName, long coverageMinor) {
        return jdbc.sql("""
                        INSERT INTO guarantees (application_id, type, guarantor_name, coverage_minor, recognition_bp)
                        VALUES (:app, :type, :name, :coverage, :recognition)
                        RETURNING id""")
                .param("app", applicationId)
                .param("type", type.name())
                .param("name", guarantorName)
                .param("coverage", coverageMinor)
                .param("recognition", type.recognitionBp())
                .query(Long.class)
                .single();
    }

    public List<Collateral> collaterals(long applicationId) {
        return jdbc.sql("SELECT * FROM collaterals WHERE application_id = :app ORDER BY id")
                .param("app", applicationId).query(COLLATERAL).list();
    }

    public List<Guarantee> guarantees(long applicationId) {
        return jdbc.sql("SELECT * FROM guarantees WHERE application_id = :app ORDER BY id")
                .param("app", applicationId).query(GUARANTEE).list();
    }

    public Optional<Collateral> findCollateral(long id) {
        return jdbc.sql("SELECT * FROM collaterals WHERE id = :id").param("id", id).query(COLLATERAL).optional();
    }

    /** 担保の再評価（評価額と評価日を更新する）。 */
    public void revalueCollateral(long id, long appraisalMinor) {
        jdbc.sql("UPDATE collaterals SET appraisal_minor = :appraisal, appraised_at = now() WHERE id = :id")
                .param("appraisal", appraisalMinor).param("id", id).update();
    }

    /** 完済時に担保・保証を解除する。 */
    public void releaseSecurity(long applicationId) {
        jdbc.sql("UPDATE collaterals SET status = 'RELEASED' WHERE application_id = :app AND status = 'ACTIVE'")
                .param("app", applicationId).update();
        jdbc.sql("UPDATE guarantees SET status = 'RELEASED' WHERE application_id = :app AND status = 'ACTIVE'")
                .param("app", applicationId).update();
    }

    /** 有効な担保（掛目後）と保証（認定額）の合計 = 保全額。 */
    public long securedMinor(long applicationId) {
        return jdbc.sql("""
                        SELECT COALESCE((SELECT SUM(appraisal_minor * haircut_bp / 10000) FROM collaterals
                                          WHERE application_id = :app AND status = 'ACTIVE'), 0)
                             + COALESCE((SELECT SUM(coverage_minor * recognition_bp / 10000) FROM guarantees
                                          WHERE application_id = :app AND status = 'ACTIVE'), 0)""")
                .param("app", applicationId)
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------ loans

    public long insertLoan(LoanApplication app, String currency, int rateBp, LocalDate disbursedOn, LocalDate maturity) {
        return jdbc.sql("""
                        INSERT INTO loans (application_id, account_id, product, currency, principal_minor, outstanding_minor,
                            annual_rate_bp, term_months, repayment_method, grade, status, disbursed_on, maturity_date)
                        VALUES (:app, :account, :product, :currency, :principal, :principal, :rate, :term, :method, :grade,
                                'ACTIVE', :disbursed, :maturity)
                        RETURNING id""")
                .param("app", app.id())
                .param("account", app.accountId())
                .param("product", app.product().name())
                .param("currency", currency)
                .param("principal", app.principalMinor())
                .param("rate", rateBp)
                .param("term", app.termMonths())
                .param("method", app.repaymentMethod().name())
                .param("grade", app.grade())
                .param("disbursed", disbursedOn)
                .param("maturity", maturity)
                .query(Long.class)
                .single();
    }

    public Optional<Loan> findLoan(long id) {
        return jdbc.sql("SELECT " + LOAN_COLUMNS + " FROM loans WHERE id = :id").param("id", id).query(LOAN).optional();
    }

    public Optional<Loan> findLoanByApplication(long applicationId) {
        return jdbc.sql("SELECT " + LOAN_COLUMNS + " FROM loans WHERE application_id = :app")
                .param("app", applicationId).query(LOAN).optional();
    }

    /**
     * 融資行のロック。返済・延滞判定・引当・償却を融資単位で直列化する。
     * ロック順は常に「融資（または申込）→ 預金口座」。振込は口座だけをロックするので、この順序ならデッドロックしない。
     */
    public Optional<Loan> lockLoan(long id) {
        return jdbc.sql("SELECT " + LOAN_COLUMNS + " FROM loans WHERE id = :id FOR NO KEY UPDATE")
                .param("id", id).query(LOAN).optional();
    }

    public List<Loan> listLoans(LoanStatus status) {
        return jdbc.sql("SELECT " + LOAN_COLUMNS
                        + " FROM loans WHERE (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar)) ORDER BY id")
                .param("status", status == null ? null : status.name())
                .query(LOAN)
                .list();
    }

    public List<Long> openLoanIds() {
        return jdbc.sql("SELECT id FROM loans WHERE status IN ('ACTIVE', 'DELINQUENT') ORDER BY id")
                .query(Long.class).list();
    }

    /**
     * 自動引落しの対象（期日の来た未払いの返済予定があり、この業務日付の引落しがまだ記録されていない融資）を
     * 返済口座ごとにまとめて返す。キーは口座 ID、値は融資 ID（昇順）。
     */
    public Map<Long, List<Long>> autoDebitCandidatesByAccount(LocalDate businessDate, String keyPrefix) {
        Map<Long, List<Long>> byAccount = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT l.id, l.account_id FROM loans l
                         WHERE l.status IN ('ACTIVE', 'DELINQUENT')
                           AND EXISTS (SELECT 1 FROM loan_installments i
                                        WHERE i.loan_id = l.id AND i.status IN ('SCHEDULED', 'PARTIAL') AND i.due_date <= :d)
                           AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.idempotency_key = :prefix || l.id)
                         ORDER BY l.account_id, l.id""")
                .param("d", businessDate).param("prefix", keyPrefix)
                .query((rs, i) -> {
                    byAccount.computeIfAbsent(rs.getLong("account_id"), k -> new ArrayList<>()).add(rs.getLong("id"));
                    return null;
                }).list();
        return byAccount;
    }

    public int countDelinquent(long accountId) {
        return jdbc.sql("SELECT count(*) FROM loans WHERE account_id = :a AND status = 'DELINQUENT'")
                .param("a", accountId).query(Integer.class).single();
    }

    public void updateBalanceAndState(long id, long outstandingMinor, LoanStatus status, int daysPastDue,
                                      BorrowerClass borrowerClass, LocalDate closedOn) {
        jdbc.sql("""
                        UPDATE loans SET outstanding_minor = :outstanding, status = :status, days_past_due = :dpd,
                               borrower_class = :class, closed_on = :closed, version = version + 1, updated_at = now()
                         WHERE id = :id""")
                .param("outstanding", outstandingMinor)
                .param("status", status.name())
                .param("dpd", daysPastDue)
                .param("class", borrowerClass.name())
                .param("closed", closedOn)
                .param("id", id)
                .update();
    }

    public void updateProvision(long id, long provisionMinor) {
        jdbc.sql("UPDATE loans SET provision_minor = :p, version = version + 1, updated_at = now() WHERE id = :id")
                .param("p", provisionMinor).param("id", id).update();
    }

    /** 償却：残高と引当を 0 にして WRITTEN_OFF にする（同じ文で更新し、CHECK 制約を満たす）。 */
    public void markWrittenOff(long id, LocalDate closedOn) {
        jdbc.sql("""
                        UPDATE loans SET outstanding_minor = 0, provision_minor = 0, status = 'WRITTEN_OFF',
                               closed_on = :closed, version = version + 1, updated_at = now()
                         WHERE id = :id""")
                .param("closed", closedOn).param("id", id).update();
    }

    // ------------------------------------------------------------------ installments

    public void insertInstallments(long loanId, List<LoanMath.Installment> schedule) {
        for (LoanMath.Installment i : schedule) {
            jdbc.sql("""
                            INSERT INTO loan_installments (loan_id, installment_no, due_date, principal_due, interest_due)
                            VALUES (:loan, :no, :due, :principal, :interest)""")
                    .param("loan", loanId)
                    .param("no", i.number())
                    .param("due", i.dueDate())
                    .param("principal", i.principalMinor())
                    .param("interest", i.interestMinor())
                    .update();
        }
    }

    public List<ScheduledInstallment> installments(long loanId) {
        return jdbc.sql("SELECT * FROM loan_installments WHERE loan_id = :loan ORDER BY installment_no")
                .param("loan", loanId).query(INSTALLMENT).list();
    }

    public void updatePayment(ScheduledInstallment i, InstallmentStatus status, LocalDate paidOn) {
        jdbc.sql("""
                        UPDATE loan_installments
                           SET principal_paid = :pp, interest_paid = :ip, late_fee_paid = :fp, status = :status,
                               paid_on = :paid
                         WHERE loan_id = :loan AND installment_no = :no""")
                .param("pp", i.principalPaid())
                .param("ip", i.interestPaid())
                .param("fp", i.lateFeePaid())
                .param("status", status.name())
                .param("paid", paidOn)
                .param("loan", i.loanId())
                .param("no", i.installmentNo())
                .update();
    }

    /** 繰上返済後の再計算：未着手の回の元金・利息を置き換える。 */
    public void reschedule(long loanId, int installmentNo, long principalDue, long interestDue) {
        jdbc.sql("""
                        UPDATE loan_installments SET principal_due = :p, interest_due = :i
                         WHERE loan_id = :loan AND installment_no = :no AND status = 'SCHEDULED'""")
                .param("p", principalDue).param("i", interestDue).param("loan", loanId).param("no", installmentNo)
                .update();
    }

    public void cancelOpenInstallments(long loanId) {
        jdbc.sql("UPDATE loan_installments SET status = 'CANCELLED' WHERE loan_id = :loan AND status IN ('SCHEDULED', 'PARTIAL')")
                .param("loan", loanId).update();
    }

    // ------------------------------------------------------------------ loan ledger

    public void insertLedger(long transactionId, long loanId, LedgerKind kind, long principal, long prepaid,
                             long interest, long lateFee, long provisionChange, long outstandingAfter,
                             long provisionAfter, String note) {
        jdbc.sql("""
                        INSERT INTO loan_ledger (transaction_id, loan_id, business_date, kind, principal_minor,
                            prepaid_principal_minor, interest_minor, late_fee_minor, provision_change_minor,
                            outstanding_after_minor, provision_after_minor, note)
                        SELECT :tx, :loan, current_business_date, :kind, :principal, :prepaid, :interest, :fee,
                               :provision, :outstanding, :provisionAfter, :note
                          FROM business_calendar WHERE id = 1""")
                .param("tx", transactionId)
                .param("loan", loanId)
                .param("kind", kind.name())
                .param("principal", principal)
                .param("prepaid", prepaid)
                .param("interest", interest)
                .param("fee", lateFee)
                .param("provision", provisionChange)
                .param("outstanding", outstandingAfter)
                .param("provisionAfter", provisionAfter)
                .param("note", note)
                .update();
    }

    public List<LedgerEntry> ledger(long loanId) {
        return jdbc.sql("SELECT * FROM loan_ledger WHERE loan_id = :loan ORDER BY transaction_id")
                .param("loan", loanId).query(LEDGER).list();
    }
}
