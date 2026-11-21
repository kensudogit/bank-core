package com.example.bankcore.posting;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.BankProperties;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.ledger.LedgerService;
import com.example.bankcore.outbox.OutboxRepository;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionRepository;
import com.example.bankcore.transaction.TransactionType;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 業務サービス（為替・有価証券・決済など）が資金を動かすときの共通手順。資金移動 API と同じ保証を与える。
 *
 * <ol>
 *   <li>{@link #reserve}：冪等キーを予約する。同じキー・同じ内容の再送は記録済みの取引を返し、内容が違えば 409</li>
 *   <li>呼び出し側が業務データの行ロック → {@link #lockAccounts}（口座を ID 昇順）の順にロックし、ルールを検証する</li>
 *   <li>{@link #post}：複式仕訳を記帳し、仕訳の顧客預金行から預金残高を更新する（残高と仕訳が必ず一致する）</li>
 *   <li>{@link #complete} / {@link #reject} と {@link #event}（Outbox）</li>
 * </ol>
 * すべて呼び出し側の 1 つの DB トランザクション（{@link #inTransaction}）の中で行う。
 */
@Component
public class PostingSupport {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");

    /**
     * @param transactionId 予約できた新しい取引 ID（再送の場合は null）
     * @param replayed      同じ冪等キーで記録済みの取引（新規の場合は null）
     */
    public record Reservation(Long transactionId, MoneyTransaction replayed) {
        public boolean isReplay() {
            return replayed != null;
        }
    }

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final int maxAttempts;

    public PostingSupport(TransactionRepository transactions, AccountRepository accounts, LedgerService ledger,
                          OutboxRepository outbox, PlatformTransactionManager txManager, BankProperties properties) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.maxAttempts = properties.transfer().maxAttempts();
    }

    /** 1 つの DB トランザクションで実行する。ロック待ちタイムアウトはジッター付きで有限回やり直す（冪等キーで安全）。 */
    public <T> T inTransaction(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(s -> work.get());
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

    public static void requireKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new InvalidRequestException("Idempotency-Key must be 1-100 chars of [A-Za-z0-9._:-]");
        }
    }

    /** 正規化した要求内容の SHA-256。 */
    public static String hash(Object... parts) {
        StringBuilder canonical = new StringBuilder();
        for (Object p : parts) {
            canonical.append(Objects.toString(p, "")).append('|');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 冪等キーの予約。トランザクション内で呼ぶ。 */
    public Reservation reserve(TransactionType type, String key, String requestHash, Long sourceAccountId,
                               Long targetAccountId, long amountMinor, String currency, String memo,
                               String correlationId) {
        requireKey(key);
        Optional<Long> id = transactions.tryReserveLoan(type, key, requestHash, sourceAccountId, targetAccountId,
                amountMinor, currency, memo, correlationId, null);
        if (id.isPresent()) {
            return new Reservation(id.get(), null);
        }
        MoneyTransaction existing = transactions.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("idempotency record vanished"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);
        }
        return new Reservation(null, existing);
    }

    /** 口座を ID 昇順でロックする（デッドロック回避。業務データの行ロックの後に呼ぶ）。 */
    public Map<Long, Account> lockAccounts(Collection<Long> accountIds) {
        return accounts.lockAllInIdOrder(accountIds);
    }

    /** 同じ口座が重複して渡されても 1 回だけロックする（手数料口座 = 送金元口座など）。 */
    public Map<Long, Account> lockAccounts(long... accountIds) {
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        for (long id : accountIds) {
            ids.add(id);
        }
        return accounts.lockAllInIdOrder(ids);
    }

    /** 仕訳を記帳し、顧客預金科目の明細から預金残高を更新する（負債科目なので 貸方で増加・借方で減少）。 */
    public void post(long transactionId, String description, List<JournalLine> lines) {
        Map<Long, Long> deltas = new TreeMap<>();
        for (JournalLine line : lines) {
            if (GlCode.CUSTOMER_DEPOSITS.equals(line.glCode())) {
                deltas.merge(line.accountId(), Math.subtractExact(line.creditMinor(), line.debitMinor()), Math::addExact);
            }
        }
        deltas.values().removeIf(d -> d == 0);
        if (!deltas.isEmpty()) {
            accounts.applyDeltas(deltas);
        }
        ledger.post(transactionId, description, lines);
    }

    public MoneyTransaction complete(long transactionId) {
        return transactions.markCompleted(transactionId);
    }

    public MoneyTransaction reject(long transactionId, RejectReason reason) {
        return transactions.markRejected(transactionId, reason);
    }

    public void event(String aggregateType, Object aggregateId, String eventType, Map<String, ?> payload) {
        outbox.append(aggregateType, String.valueOf(aggregateId), eventType, payload);
    }
}
