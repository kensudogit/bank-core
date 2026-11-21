package com.example.bankcore.loan;

import com.example.bankcore.loan.CreditRiskService.RiskReport;
import com.example.bankcore.loan.LoanApplicationService.ApplicationView;
import com.example.bankcore.loan.LoanApplicationService.NewApplication;
import com.example.bankcore.loan.LoanModels.LedgerEntry;
import com.example.bankcore.loan.LoanModels.Loan;
import com.example.bankcore.loan.LoanModels.LoanApplication;
import com.example.bankcore.loan.LoanModels.LoanStatus;
import com.example.bankcore.loan.LoanMonitoringService.MonitoringReport;
import com.example.bankcore.loan.LoanService.LoanDetail;
import com.example.bankcore.loan.LoanService.LoanResult;
import com.example.bankcore.transaction.TransactionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.example.bankcore.transaction.TransactionController.CORRELATION_ID;
import static com.example.bankcore.transaction.TransactionController.IDEMPOTENCY_KEY;
import static com.example.bankcore.transaction.TransactionController.REPLAYED;

/**
 * 融資 API。資金が動く POST（実行・返済・償却）は {@code Idempotency-Key} 必須で、応答は資金移動 API と同じ
 * （COMPLETED → 201、REJECTED → 422、再送は {@code Idempotent-Replayed: true}）。
 */
@RestController
@RequestMapping("/api")
public class LoanController {

    public record ApplicationRequest(
            @NotNull Long accountId, @NotNull LoanProduct product, @NotNull @Positive Long principalMinor,
            @NotNull @Positive Integer termMonths, @NotNull RepaymentMethod repaymentMethod,
            @NotNull @PositiveOrZero Long annualIncomeMinor, @PositiveOrZero Long existingAnnualDebtMinor,
            @Size(max = 200) String purpose, @NotBlank @Size(max = 50) String submittedBy) {
    }

    public record CollateralRequest(@NotNull CollateralType type, @NotBlank @Size(max = 200) String description,
                                    @NotNull @Positive Long appraisalMinor, @Min(1) @Max(9) Integer lienRank) {
    }

    public record GuaranteeRequest(@NotNull GuaranteeType type, @NotBlank @Size(max = 100) String guarantorName,
                                   @NotNull @Positive Long coverageMinor) {
    }

    public record RevaluationRequest(@NotNull @Positive Long appraisalMinor) {
    }

    public record DecisionRequest(@NotNull Boolean approve, @NotBlank @Size(max = 50) String decidedBy,
                                  @Size(max = 400) String note, @Min(0) @Max(1500) Integer approvedRateBp) {
    }

    public record RepaymentRequest(@NotNull @Positive Long amountMinor) {
    }

    public record WriteOffRequest(@NotBlank @Size(max = 50) String approvedBy, @NotBlank @Size(max = 150) String reason) {
    }

    private final LoanApplicationService applications;
    private final LoanService loans;
    private final LoanMonitoringService monitoring;
    private final CreditRiskService risk;

    public LoanController(LoanApplicationService applications, LoanService loans, LoanMonitoringService monitoring,
                          CreditRiskService risk) {
        this.applications = applications;
        this.loans = loans;
        this.monitoring = monitoring;
        this.risk = risk;
    }

    // ---------------------------------------------------------------- 与信審査

    @PostMapping("/loan-applications")
    public ResponseEntity<ApplicationView> apply(@Valid @RequestBody ApplicationRequest r) {
        ApplicationView view = applications.apply(new NewApplication(r.accountId(), r.product(), r.principalMinor(),
                r.termMonths(), r.repaymentMethod(), r.annualIncomeMinor(),
                r.existingAnnualDebtMinor() == null ? 0 : r.existingAnnualDebtMinor(), r.purpose(), r.submittedBy()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping("/loan-applications")
    public List<LoanApplication> applications() {
        return applications.list();
    }

    @GetMapping("/loan-applications/{id}")
    public ApplicationView application(@PathVariable long id) {
        return applications.get(id);
    }

    @PostMapping("/loan-applications/{id}/collaterals")
    public ApplicationView addCollateral(@PathVariable long id, @Valid @RequestBody CollateralRequest r) {
        return applications.addCollateral(id, r.type(), r.description(), r.appraisalMinor(), r.lienRank());
    }

    @PostMapping("/loan-applications/{id}/guarantees")
    public ApplicationView addGuarantee(@PathVariable long id, @Valid @RequestBody GuaranteeRequest r) {
        return applications.addGuarantee(id, r.type(), r.guarantorName(), r.coverageMinor());
    }

    @PutMapping("/collaterals/{id}/appraisal")
    public ApplicationView revalue(@PathVariable long id, @Valid @RequestBody RevaluationRequest r) {
        return applications.revalueCollateral(id, r.appraisalMinor());
    }

    @PostMapping("/loan-applications/{id}/screening")
    public ApplicationView screen(@PathVariable long id) {
        return applications.screen(id);
    }

    @PostMapping("/loan-applications/{id}/decision")
    public ApplicationView decide(@PathVariable long id, @Valid @RequestBody DecisionRequest r) {
        return applications.decide(id, r.approve(), r.decidedBy(), r.note(), r.approvedRateBp());
    }

    // ---------------------------------------------------------------- 融資実行・返済・償却

    @PostMapping("/loan-applications/{id}/disbursement")
    public ResponseEntity<LoanResult> disburse(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                               @RequestHeader(value = CORRELATION_ID, required = false) String corr) {
        return respond(loans.disburse(id, key, corr));
    }

    @PostMapping("/loans/{id}/repayments")
    public ResponseEntity<LoanResult> repay(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                            @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                            @Valid @RequestBody RepaymentRequest r) {
        return respond(loans.repay(id, r.amountMinor(), key, corr));
    }

    @PostMapping("/loans/{id}/write-off")
    public ResponseEntity<LoanResult> writeOff(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                               @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                               @Valid @RequestBody WriteOffRequest r) {
        return respond(loans.writeOff(id, r.approvedBy(), r.reason(), key, corr));
    }

    @GetMapping("/loans")
    public List<Loan> list(@RequestParam(required = false) LoanStatus status) {
        return loans.list(status);
    }

    @GetMapping("/loans/{id}")
    public LoanDetail loan(@PathVariable long id) {
        return loans.get(id);
    }

    /** 貸付元帳。 */
    @GetMapping("/loans/{id}/ledger")
    public List<LedgerEntry> ledger(@PathVariable long id) {
        return loans.ledger(id);
    }

    /** 返済シミュレーション（元利均等・元金均等）。 */
    @GetMapping("/loan-simulations")
    public List<LoanMath.Installment> simulate(@RequestParam long principalMinor, @RequestParam int annualRateBp,
                                               @RequestParam int termMonths, @RequestParam RepaymentMethod method) {
        return loans.simulate(principalMinor, annualRateBp, termMonths, method);
    }

    // ---------------------------------------------------------------- 延滞管理・信用リスク

    /** 延滞判定と貸倒引当金の見直し（日次締めでも実行される。同じ業務日付で何度呼んでも引当は 1 回）。 */
    @PostMapping("/loans/monitoring")
    public MonitoringReport monitor() {
        return monitoring.run();
    }

    @GetMapping("/credit-risk")
    public RiskReport creditRisk(@RequestParam(defaultValue = "200") int stressPdMultiplierPct,
                                 @RequestParam(defaultValue = "30") int stressCollateralDropPct) {
        return risk.report(stressPdMultiplierPct, stressCollateralDropPct);
    }

    private static ResponseEntity<LoanResult> respond(LoanResult result) {
        HttpStatus status = result.transaction().status() == TransactionStatus.COMPLETED
                ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).header(REPLAYED, Boolean.toString(result.replayed())).body(result);
    }
}
