package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.BankProperties;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.ledger.LedgerService;
import com.example.bankcore.outbox.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 資金移動（振込・入金・出金・利息・取消）のアプリケーションサービス。
 *
 * <h2>1 つの DB トランザクションで行うこと</h2>
 * <ol>
 *   <li>関係口座の存在確認（存在しなければ 404。冪等キーは予約しない）</li>
 *   <li>冪等キーの予約（transactions に PENDING で INSERT … ON CONFLICT DO NOTHING）</li>
 *   <li>（取消のみ）元取引を行ロックし、取消可能か検証</li>
 *   <li>関係口座を 1 文で ID 昇順に SELECT … ORDER BY id FOR NO KEY UPDATE（デッドロック回避）</li>
 *   <li>ロック後の最新状態で業務ルール検証（状態・通貨・支払可能額）</li>
 *   <li>複式仕訳の作成 → 仕訳から口座残高を更新 → 記帳 → 取引ステータス更新 → Outbox 追記</li>
 * </ol>
 *
 * <h2>性能上の工夫</h2>
 * 口座ロックを持っている時間がそのまま同一口座への取引の直列化時間になるため、ロック取得後の DB 往復を減らしている。
 * 残高更新は複数口座を 1 文、仕訳はヘッダと明細を 1 文（CTE）、ステータス更新は RETURNING で結果を受け取り再読込しない。
 * 振込 1 件あたりの往復は約 17 回から 7 回（＋コミット）になった。
 * どこかで失敗すればすべてロールバックされ、部分的な更新は残らない。
 *
 * <p>業務ルール違反（資金不足など）は「REJECTED という結果」として記録・コミットする。
 * 同じ冪等キーで再送されても同じ拒否結果を返すためである。</p>
 */
@Service
public class MoneyMovementService {

    private static final Logger log = LoggerFactory.getLogger(MoneyMovementService.class);

    /** @param replayed true の場合、冪等キーにより過去の結果を返したことを示す */
    public record MovementResult(MoneyTransaction transaction, boolean replayed) {
    }

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final JournalRepository journal;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final BankProperties.Transfer config;

    public MoneyMovementService(TransactionRepository transactions, AccountRepository accounts, LedgerService ledger,
                                JournalRepository journal, OutboxRepository outbox,
                                PlatformTransactionManager txManager, BankProperties properties) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.ledger = ledger;
        this.journal = journal;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.config = properties.transfer();
    }

    /**
     * ロック待ちタイムアウト等の一時的な失敗は、ジッター付きで有限回リトライする。
     * 冪等キーがあるので、前回がコミット済みなら再実行しても二重計上にはならない。
     */
    public MovementResult execute(MovementCommand cmd) {
        cmd.validate();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return tx.execute(status -> executeInTransaction(cmd));
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= config.maxAttempts()) {
                    throw e;
                }
                long backoff = (long) (50 * Math.pow(2, attempt - 1)) + ThreadLocalRandom.current().nextLong(50);
                log.info("lock failure on attempt {} for key={}, retrying in {}ms", attempt, cmd.idempotencyKey(), backoff);
                sleep(backoff);
            }
        }
    }

    /**
     * 取引の取消。元取引の仕訳の貸借を入れ替えた反対仕訳を記帳する（元の仕訳は変更しない）。
     * 元取引の金額・口座は記帳後に変わらないため、ロック前に読んでコマンドを組み立ててよい。
     */
    public MovementResult reverse(String idempotencyKey, long originalId, String correlationId) {
        MoneyTransaction original = get(originalId);
        if (!original.type().isGenericMovement()) {
            // 融資・為替の取引は業務データ（貸出金残高・送金の状態など）と一緒に記帳しているため、
            // 仕訳だけを反対にすると不整合になる。各業務の手続き（返済・組戻し・謝絶など）で戻す
            throw new BusinessConflictException("transactions of type " + original.type() + " cannot be reversed here");
        }
        return execute(MovementCommand.reversalOf(original, idempotencyKey, correlationId));
    }

    /** タイムアウト後など、結果が不明なときにクライアントが状態を問い合わせるための参照。 */
    public Optional<MoneyTransaction> findByIdempotencyKey(String key) {
        return transactions.findByIdempotencyKey(key);
    }

    public MoneyTransaction get(long id) {
        return transactions.findById(id).orElseThrow(() -> new NotFoundException("transaction " + id + " not found"));
    }

    public Optional<MoneyTransaction> findReversalOf(long originalId) {
        return transactions.findCompletedReversalOf(originalId);
    }

    private MovementResult executeInTransaction(MovementCommand cmd) {
        // 行ロック待ちの上限（lock_timeout）は接続初期化 SQL で設定済み（application.yml 参照）
        Set<Long> accountIds = accountIds(cmd);

        // 0. 口座の存在確認。口座は削除されないので、ここで存在すればロック時にも存在する。
        //    予約前に確認しないと、存在しない口座 ID で transactions の外部キー違反になる。
        if (accounts.countExisting(accountIds) != accountIds.size()) {
            Long missing = accountIds.stream().filter(id -> !accounts.exists(id)).findFirst().orElse(null);
            throw new NotFoundException("account " + missing + " not found");
        }

        String hash = cmd.requestHash();

        // 1. 冪等キーの予約
        Optional<Long> reserved = transactions.tryReserve(cmd, hash);
        if (reserved.isEmpty()) {
            MoneyTransaction existing = transactions.findByIdempotencyKey(cmd.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency record vanished"));
            if (!existing.requestHash().equals(hash)) {
                throw new IdempotencyConflictException(cmd.idempotencyKey());
            }
            return new MovementResult(existing, true);
        }
        long txId = reserved.get();

        // 2. 取消の場合：元取引をロックして取消可能か確認（違反は例外 → ロールバックで冪等キーも解放）
        if (cmd.type() == TransactionType.REVERSAL) {
            assertReversible(cmd.reversesTransactionId());
        }

        // 3. 口座を ID 昇順でロック（1 文）
        Map<Long, Account> locked = accounts.lockAllInIdOrder(accountIds);
        Account source = cmd.sourceAccountId() == null ? null : locked.get(cmd.sourceAccountId());
        Account target = cmd.targetAccountId() == null ? null : locked.get(cmd.targetAccountId());

        // 4. ロック後の最新状態で業務ルールを検証
        Optional<RejectReason> rejection = TransactionRules.check(source, target, cmd.amountMinor(), cmd.currency());
        if (rejection.isPresent()) {
            return new MovementResult(transactions.markRejected(txId, rejection.get()), false);
        }

        // 5. 仕訳を作り、仕訳の顧客預金行から残高を更新する（残高と仕訳が必ず一致する）
        List<JournalLine> lines = journalLines(cmd);
        Map<Long, Long> deltas = new TreeMap<>();
        for (JournalLine line : lines) {
            if (GlCode.CUSTOMER_DEPOSITS.equals(line.glCode())) {
                // 負債科目：貸方で増加・借方で減少
                deltas.merge(line.accountId(), Math.subtractExact(line.creditMinor(), line.debitMinor()), Math::addExact);
            }
        }
        accounts.applyDeltas(deltas);
        ledger.post(txId, description(cmd, source, target), lines);
        MoneyTransaction completed = transactions.markCompleted(txId);
        outbox.append("Transaction", Long.toString(txId), eventType(cmd.type()), eventPayload(txId, cmd));

        return new MovementResult(completed, false);
    }

    private void assertReversible(long originalId) {
        MoneyTransaction original = transactions.lockById(originalId)
                .orElseThrow(() -> new NotFoundException("transaction " + originalId + " not found"));
        if (original.status() != TransactionStatus.COMPLETED) {
            throw new BusinessConflictException("only completed transactions can be reversed (status="
                    + original.status() + ")");
        }
        if (original.type() == TransactionType.REVERSAL) {
            throw new BusinessConflictException("a reversal cannot itself be reversed");
        }
        transactions.findCompletedReversalOf(originalId).ifPresent(r -> {
            throw new BusinessConflictException("transaction " + originalId + " is already reversed by transaction " + r.id());
        });
    }

    /**
     * 取引種別ごとの仕訳。顧客預金は銀行の負債なので、預金が減る口座が借方・増える口座が貸方。
     */
    private List<JournalLine> journalLines(MovementCommand cmd) {
        long amount = cmd.amountMinor();
        String ccy = cmd.currency();
        return switch (cmd.type()) {
            case TRANSFER -> List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, cmd.sourceAccountId(), amount, ccy),
                    JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, cmd.targetAccountId(), amount, ccy));
            case DEPOSIT -> List.of(
                    JournalLine.debit(GlCode.CASH, null, amount, ccy),
                    JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, cmd.targetAccountId(), amount, ccy));
            case WITHDRAWAL -> List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, cmd.sourceAccountId(), amount, ccy),
                    JournalLine.credit(GlCode.CASH, null, amount, ccy));
            case INTEREST -> List.of(
                    JournalLine.debit(GlCode.INTEREST_EXPENSE, null, amount, ccy),
                    JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, cmd.targetAccountId(), amount, ccy));
            // 取消：元の仕訳明細の貸借をそのまま入れ替える（反対仕訳）
            case REVERSAL -> journal.linesForTransaction(cmd.reversesTransactionId()).stream()
                    .map(l -> new JournalLine(l.glCode(), l.accountId(), l.creditMinor(), l.debitMinor(), l.currency()))
                    .toList();
            default -> throw notGeneric(cmd.type());
        };
    }

    /** 関係口座 ID（昇順）。 */
    private static Set<Long> accountIds(MovementCommand cmd) {
        Set<Long> ids = new TreeSet<>();
        if (cmd.sourceAccountId() != null) {
            ids.add(cmd.sourceAccountId());
        }
        if (cmd.targetAccountId() != null) {
            ids.add(cmd.targetAccountId());
        }
        return ids;
    }

    private static String description(MovementCommand cmd, Account source, Account target) {
        return switch (cmd.type()) {
            case TRANSFER -> "振込 " + source.accountNumber() + " -> " + target.accountNumber();
            case DEPOSIT -> "現金入金 " + target.accountNumber();
            case WITHDRAWAL -> "現金出金 " + source.accountNumber();
            case INTEREST -> cmd.memo() != null ? cmd.memo() : "利息 " + target.accountNumber();
            case REVERSAL -> cmd.memo();
            default -> throw notGeneric(cmd.type());
        };
    }

    private static String eventType(TransactionType type) {
        return switch (type) {
            case TRANSFER -> "TransferCompleted";
            case DEPOSIT -> "DepositCompleted";
            case WITHDRAWAL -> "WithdrawalCompleted";
            case INTEREST -> "InterestPosted";
            case REVERSAL -> "TransactionReversed";
            default -> throw notGeneric(type);
        };
    }

    /** 融資・為替などの取引は、それぞれの業務サービスが業務データと一緒に記帳する。 */
    private static IllegalStateException notGeneric(TransactionType type) {
        return new IllegalStateException(type + " is posted by its own business service, not MoneyMovementService");
    }

    private static Map<String, Object> eventPayload(long txId, MovementCommand cmd) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", txId);
        payload.put("type", cmd.type().name());
        payload.put("sourceAccountId", cmd.sourceAccountId());
        payload.put("targetAccountId", cmd.targetAccountId());
        payload.put("amountMinor", cmd.amountMinor());
        payload.put("currency", cmd.currency());
        payload.put("reversesTransactionId", cmd.reversesTransactionId());
        payload.put("correlationId", cmd.correlationId());
        return payload;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during retry backoff", e);
        }
    }
}
