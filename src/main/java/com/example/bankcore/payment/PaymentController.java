package com.example.bankcore.payment;

import com.example.bankcore.payment.ForexService.Exchange;
import com.example.bankcore.payment.ForexService.ExchangeResult;
import com.example.bankcore.payment.ForexService.ForeignOrder;
import com.example.bankcore.payment.ForexService.ForeignRemittance;
import com.example.bankcore.payment.ForexService.ForeignResult;
import com.example.bankcore.payment.ForexService.FxRate;
import com.example.bankcore.payment.PaymentService.BillingItem;
import com.example.bankcore.payment.PaymentService.BillingResult;
import com.example.bankcore.payment.PaymentService.Biller;
import com.example.bankcore.payment.PaymentService.DebitRequest;
import com.example.bankcore.payment.PaymentService.DirectDebitReport;
import com.example.bankcore.payment.PaymentService.DomesticRemittance;
import com.example.bankcore.payment.PaymentService.IncomingItem;
import com.example.bankcore.payment.PaymentService.Mandate;
import com.example.bankcore.payment.PaymentService.OutgoingRequest;
import com.example.bankcore.payment.PaymentService.RemittanceResult;
import com.example.bankcore.payment.PaymentService.ZenginBatch;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.transaction.MoneyMovementService.MovementResult;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.TransactionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.example.bankcore.transaction.TransactionController.CORRELATION_ID;
import static com.example.bankcore.transaction.TransactionController.IDEMPOTENCY_KEY;
import static com.example.bankcore.transaction.TransactionController.REPLAYED;

/**
 * 為替業務 API（振替・国内為替・全銀・口座振替・外国為替）。資金が動く POST は {@code Idempotency-Key} 必須で、
 * 応答は資金移動 API と同じ（COMPLETED → 201、REJECTED → 422、再送は {@code Idempotent-Replayed: true}）。
 */
@RestController
@RequestMapping("/api")
public class PaymentController {

    public record BookTransferRequest(@NotNull Long sourceAccountId, @NotNull Long targetAccountId,
                                      @NotNull @Positive Long amountMinor,
                                      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                      @Size(max = 200) String memo) {
    }

    public record DomesticRequest(@NotNull Long accountId, @NotBlank String bankCode, @NotBlank String branchCode,
                                  @NotBlank String destAccountType, @NotBlank String destAccountNo,
                                  @NotBlank String destNameKana, @NotNull @Positive Long amountMinor) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 100) String reason) {
    }

    public record ResolutionRequest(Long accountId, @NotBlank @Size(max = 50) String decidedBy) {
    }

    public record BillerRequest(@NotBlank String code, @NotBlank @Size(max = 100) String name, @NotNull Long accountId) {
    }

    public record MandateRequest(@NotNull Long accountId, @NotBlank String billerCode, @NotBlank String payerNumber) {
    }

    public record RateRequest(@NotNull @Positive BigDecimal ttm, @NotNull BigDecimal spread,
                              @NotBlank @Size(max = 50) String updatedBy) {
    }

    public record ExchangeRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId,
                                  @NotNull @Positive Long foreignAmountMinor) {
    }

    public record ForeignRequest(@NotNull Long accountId, Long feeAccountId, @NotBlank String currency,
                                 @NotNull @Positive Long amountMinor, @NotBlank String beneficiaryName,
                                 @NotBlank String beneficiaryAccount, @NotBlank String swiftBic,
                                 @NotBlank String country, @NotBlank String purpose) {
    }

    public record DecisionRequest(@NotNull Boolean release, @NotBlank @Size(max = 50) String decidedBy,
                                  @NotBlank @Size(max = 200) String note) {
    }

    private final PaymentService payments;
    private final ForexService forex;
    private final BusinessCalendar calendar;

    public PaymentController(PaymentService payments, ForexService forex, BusinessCalendar calendar) {
        this.payments = payments;
        this.forex = forex;
        this.calendar = calendar;
    }

    // ---------------------------------------------------------------- 振替

    @PostMapping("/book-transfers")
    public ResponseEntity<MoneyTransaction> bookTransfer(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                         @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                                         @Valid @RequestBody BookTransferRequest r) {
        MovementResult result = payments.bookTransfer(r.sourceAccountId(), r.targetAccountId(), r.amountMinor(),
                r.currency(), r.memo(), key, corr);
        return respond(result.transaction(), result.replayed(), result.transaction());
    }

    // ---------------------------------------------------------------- 国内為替

    @GetMapping("/banks")
    public List<Map<String, Object>> banks() {
        return payments.banks();
    }

    @PostMapping("/domestic-remittances")
    public ResponseEntity<RemittanceResult> sendDomestic(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                         @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                                         @Valid @RequestBody DomesticRequest r) {
        RemittanceResult result = payments.sendDomestic(new OutgoingRequest(r.accountId(), r.bankCode(), r.branchCode(),
                r.destAccountType(), r.destAccountNo(), r.destNameKana(), r.amountMinor()), key, corr);
        return respond(result.transaction(), result.replayed(), result);
    }

    @GetMapping("/domestic-remittances")
    public List<DomesticRemittance> remittances(@RequestParam(required = false) String direction,
                                                @RequestParam(required = false) String status) {
        return payments.remittances(direction, status);
    }

    @GetMapping("/domestic-remittances/{id}")
    public DomesticRemittance remittance(@PathVariable long id) {
        return payments.remittance(id);
    }

    @PostMapping("/domestic-remittances/{id}/cancellation")
    public ResponseEntity<RemittanceResult> cancel(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                   @RequestHeader(value = CORRELATION_ID, required = false) String corr) {
        RemittanceResult result = payments.cancelDomestic(id, key, corr);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/domestic-remittances/{id}/return")
    public ResponseEntity<RemittanceResult> returnRemittance(@PathVariable long id,
                                                             @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                             @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                                             @Valid @RequestBody ReasonRequest r) {
        RemittanceResult result = payments.returnDomestic(id, r.reason(), key, corr);
        return respond(result.transaction(), result.replayed(), result);
    }

    @PostMapping("/zengin-batches")
    public ResponseEntity<ZenginBatch> createBatch() {
        return ResponseEntity.status(HttpStatus.CREATED).body(payments.createZenginBatch());
    }

    @GetMapping("/zengin-batches")
    public List<ZenginBatch> batches() {
        return payments.batches();
    }

    @GetMapping("/zengin-batches/{id}")
    public ZenginBatch batch(@PathVariable long id) {
        return payments.batch(id);
    }

    @PostMapping("/zengin-batches/{id}/settlement")
    public ZenginBatch settle(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                              @RequestHeader(value = CORRELATION_ID, required = false) String corr) {
        return payments.settleBatch(id, key, corr);
    }

    /** 被仕向データの取込（1 件ごとに冪等。結果の配列を返す）。 */
    @PostMapping("/incoming-remittances")
    public List<RemittanceResult> receive(@RequestBody @NotEmpty List<IncomingItem> items) {
        return payments.receiveIncoming(items);
    }

    @PostMapping("/incoming-remittances/{id}/resolution")
    public ResponseEntity<RemittanceResult> resolve(@PathVariable long id, @RequestHeader(IDEMPOTENCY_KEY) String key,
                                                    @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                                    @Valid @RequestBody ResolutionRequest r) {
        RemittanceResult result = payments.resolveHeld(id, r.accountId(), r.decidedBy(), key, corr);
        return respond(result.transaction(), result.replayed(), result);
    }

    // ---------------------------------------------------------------- 口座振替

    @PostMapping("/billers")
    public ResponseEntity<Biller> registerBiller(@Valid @RequestBody BillerRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(payments.registerBiller(r.code(), r.name(), r.accountId()));
    }

    @GetMapping("/billers")
    public List<Biller> billers() {
        return payments.billers();
    }

    @PostMapping("/debit-mandates")
    public ResponseEntity<Mandate> registerMandate(@Valid @RequestBody MandateRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(payments.registerMandate(r.accountId(), r.billerCode(), r.payerNumber()));
    }

    @GetMapping("/debit-mandates")
    public List<Mandate> mandates(@RequestParam(required = false) Long accountId) {
        return payments.mandates(accountId);
    }

    @PostMapping("/debit-mandates/{id}/cancellation")
    public Mandate cancelMandate(@PathVariable long id) {
        return payments.cancelMandate(id);
    }

    @PostMapping("/debit-requests")
    public List<BillingResult> importBilling(@RequestBody @NotEmpty List<BillingItem> items) {
        return payments.importBilling(items);
    }

    @GetMapping("/debit-requests")
    public List<DebitRequest> debitRequests() {
        return payments.debitRequests();
    }

    /** 業務日付までに引落日の来た口座振替を実行する（日次締めでも実行される）。 */
    @PostMapping("/direct-debits/run")
    public DirectDebitReport runDirectDebits() {
        return payments.processDirectDebits(calendar.today());
    }

    // ---------------------------------------------------------------- 外国為替

    @GetMapping("/fx-rates")
    public List<FxRate> rates() {
        return forex.rates();
    }

    @PutMapping("/fx-rates/{currency}")
    public FxRate updateRate(@PathVariable String currency, @Valid @RequestBody RateRequest r) {
        return forex.updateRate(currency, r.ttm(), r.spread(), r.updatedBy());
    }

    @PostMapping("/fx-exchanges")
    public ResponseEntity<ExchangeResult> exchange(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                   @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                                   @Valid @RequestBody ExchangeRequest r) {
        ExchangeResult result = forex.exchange(r.fromAccountId(), r.toAccountId(), r.foreignAmountMinor(), key, corr);
        return respond(result.transaction(), result.replayed(), result);
    }

    @GetMapping("/fx-exchanges")
    public List<Exchange> exchanges() {
        return forex.exchanges();
    }

    @PostMapping("/foreign-remittances")
    public ResponseEntity<ForeignResult> sendForeign(@RequestHeader(IDEMPOTENCY_KEY) String key,
                                                     @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                                     @Valid @RequestBody ForeignRequest r) {
        ForeignResult result = forex.sendForeign(new ForeignOrder(r.accountId(), r.feeAccountId(), r.currency(),
                r.amountMinor(), r.beneficiaryName(), r.beneficiaryAccount(), r.swiftBic(), r.country(), r.purpose()),
                key, corr);
        return respond(result.transaction(), result.replayed(), result);
    }

    @GetMapping("/foreign-remittances")
    public List<ForeignRemittance> foreignRemittances(@RequestParam(required = false) String status) {
        return forex.foreignRemittances(status);
    }

    @GetMapping("/foreign-remittances/{id}")
    public ForeignRemittance foreignRemittance(@PathVariable long id) {
        return forex.foreign(id);
    }

    /** 保留中の海外送金の判断。謝絶（返金）の場合は Idempotency-Key 必須。 */
    @PostMapping("/foreign-remittances/{id}/decision")
    public ForeignResult decide(@PathVariable long id,
                                @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                @RequestHeader(value = CORRELATION_ID, required = false) String corr,
                                @Valid @RequestBody DecisionRequest r) {
        return forex.decide(id, r.release(), r.decidedBy(), r.note(), key, corr);
    }

    private static <T> ResponseEntity<T> respond(MoneyTransaction tx, boolean replayed, T body) {
        HttpStatus status = tx.status() == TransactionStatus.COMPLETED ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).header(REPLAYED, Boolean.toString(replayed)).body(body);
    }
}
