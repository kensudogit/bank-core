package com.example.bankcore.transaction;

import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.ledger.JournalRepository.PostedLine;
import com.example.bankcore.transaction.MoneyMovementService.MovementResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 資金移動 API。すべての POST は {@code Idempotency-Key} ヘッダー必須。
 *
 * <ul>
 *   <li>COMPLETED → 201 Created</li>
 *   <li>REJECTED（資金不足など）→ 422 Unprocessable Entity（結果は記録され、再送しても同じ応答）</li>
 *   <li>同じキーで内容が違う → 409 Conflict</li>
 *   <li>再送で過去の結果を返した場合は {@code Idempotent-Replayed: true}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class TransactionController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String REPLAYED = "Idempotent-Replayed";

    public record TransferRequest(
            @NotNull Long sourceAccountId,
            @NotNull Long targetAccountId,
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 200) String memo) {
    }

    public record CashRequest(
            @NotNull Long accountId,
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 200) String memo) {
    }

    /** @param reversedByTransactionId この取引を取り消した取消取引の ID（未取消なら null） */
    public record TransactionDetail(MoneyTransaction transaction, Long reversedByTransactionId,
                                    List<PostedLine> journalLines) {
    }

    private final MoneyMovementService service;
    private final JournalRepository journal;

    public TransactionController(MoneyMovementService service, JournalRepository journal) {
        this.service = service;
        this.journal = journal;
    }

    @PostMapping("/transfers")
    public ResponseEntity<MoneyTransaction> transfer(
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = CORRELATION_ID, required = false) String correlationId,
            @Valid @RequestBody TransferRequest req) {
        return respond(service.execute(MovementCommand.transfer(key, req.sourceAccountId(), req.targetAccountId(),
                req.amountMinor(), req.currency(), req.memo(), correlationId)));
    }

    @PostMapping("/deposits")
    public ResponseEntity<MoneyTransaction> deposit(
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = CORRELATION_ID, required = false) String correlationId,
            @Valid @RequestBody CashRequest req) {
        return respond(service.execute(MovementCommand.deposit(key, req.accountId(),
                req.amountMinor(), req.currency(), req.memo(), correlationId)));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<MoneyTransaction> withdrawal(
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = CORRELATION_ID, required = false) String correlationId,
            @Valid @RequestBody CashRequest req) {
        return respond(service.execute(MovementCommand.withdrawal(key, req.accountId(),
                req.amountMinor(), req.currency(), req.memo(), correlationId)));
    }

    @GetMapping("/transactions/{id}")
    public TransactionDetail get(@PathVariable long id) {
        MoneyTransaction tx = service.get(id);
        Long reversedBy = service.findReversalOf(id).map(MoneyTransaction::id).orElse(null);
        return new TransactionDetail(tx, reversedBy, journal.linesForTransaction(id));
    }

    /**
     * 取消（反対仕訳）。元取引の貸借を入れ替えた仕訳を新しい取引として記帳する。
     * 取消済み・未完了・取消取引そのものの取消は 409。取消で残高が不足する場合は 422（REJECTED として記録）。
     */
    @PostMapping("/transactions/{id}/reversal")
    public ResponseEntity<MoneyTransaction> reverse(
            @PathVariable long id,
            @RequestHeader(IDEMPOTENCY_KEY) String key,
            @RequestHeader(value = CORRELATION_ID, required = false) String correlationId) {
        return respond(service.reverse(key, id, correlationId));
    }

    /** 応答を受け取れなかった（タイムアウト等）クライアントが、冪等キーで結果を確認する。 */
    @GetMapping("/transactions")
    public MoneyTransaction findByKey(@RequestParam("idempotencyKey") String key) {
        return service.findByIdempotencyKey(key)
                .orElseThrow(() -> new NotFoundException("no transaction for the idempotency key"));
    }

    private static ResponseEntity<MoneyTransaction> respond(MovementResult result) {
        HttpStatus status = result.transaction().status() == TransactionStatus.COMPLETED
                ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .header(REPLAYED, Boolean.toString(result.replayed()))
                .body(result.transaction());
    }
}
