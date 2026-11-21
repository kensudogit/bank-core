package com.example.bankcore.cards;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.posting.PostingSupport;
import com.example.bankcore.posting.PostingSupport.Reservation;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionRules;
import com.example.bankcore.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 決済業務：デビットカード（オーソリ・売上確定・取消）、電子マネー（チャージ・支払）、加盟店への売上精算。
 *
 * <h2>デビットカード</h2>
 * オーソリで承認額を口座の拘束額（held）として押さえ、支払可能額（記帳残高 − 拘束額 + 貸越枠）を減らす。
 * 売上確定で拘束を外して実際の金額を引き落とし、取消（オーソリの解除）では拘束だけを外す。
 * 同じ加盟店・同じ参照番号のオーソリは 1 回だけ受け付ける（通信の再送で二重に押さえない）。
 *
 * <h2>勘定の流れ</h2>
 * <pre>
 *  カード売上確定  借方 顧客預金           ／ 貸方 加盟店未払金
 *  電子マネーチャージ 借方 顧客預金        ／ 貸方 電子マネー未使用残高
 *  電子マネー支払  借方 電子マネー未使用残高 ／ 貸方 加盟店未払金
 *  加盟店精算      借方 加盟店未払金        ／ 貸方 顧客預金（加盟店の口座。手数料控除後）・加盟店手数料
 * </pre>
 */
@Service
public class CardService {

    static final String GL_MERCHANT_PAYABLE = "2430";
    static final String GL_EMONEY = "2440";
    static final String GL_MERCHANT_FEE = "4500";

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Merchant(long id, String name, String mcc, long accountId, int feeRateBp) {
    }

    public record Card(long id, UUID token, String last4, long accountId, String status, long dailyLimitMinor) {
    }

    public record Authorization(long id, long cardId, long merchantId, long amountMinor, String status,
                                String declineReason, String authCode, String externalRef, Long capturedMinor,
                                LocalDate businessDate, OffsetDateTime createdAt) {
    }

    public record CaptureResult(MoneyTransaction transaction, Authorization authorization, boolean replayed) {
    }

    public record Wallet(long id, long accountId, long balanceMinor, long maxBalanceMinor, String status) {
    }

    public record WalletResult(MoneyTransaction transaction, Wallet wallet, boolean replayed) {
    }

    public record Settlement(long merchantId, int items, long grossMinor, long feeMinor, long netMinor,
                             MoneyTransaction transaction) {
    }

    private static final RowMapper<Merchant> MERCHANT = (rs, i) -> new Merchant(rs.getLong("id"), rs.getString("name"),
            rs.getString("mcc"), rs.getLong("account_id"), rs.getInt("fee_rate_bp"));

    private static final RowMapper<Card> CARD = (rs, i) -> new Card(rs.getLong("id"), rs.getObject("token", UUID.class),
            rs.getString("last4"), rs.getLong("account_id"), rs.getString("status"), rs.getLong("daily_limit_minor"));

    private static final RowMapper<Authorization> AUTH = (rs, i) -> new Authorization(rs.getLong("id"),
            rs.getLong("card_id"), rs.getLong("merchant_id"), rs.getLong("amount_minor"), rs.getString("status"),
            rs.getString("decline_reason"), rs.getString("auth_code"), rs.getString("external_ref"),
            rs.getObject("captured_minor") == null ? null : rs.getLong("captured_minor"),
            rs.getObject("business_date", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<Wallet> WALLET = (rs, i) -> new Wallet(rs.getLong("id"), rs.getLong("account_id"),
            rs.getLong("balance_minor"), rs.getLong("max_balance_minor"), rs.getString("status"));

    private final JdbcClient jdbc;
    private final PostingSupport posting;
    private final AccountRepository accounts;
    private final BusinessCalendar calendar;

    public CardService(JdbcClient jdbc, PostingSupport posting, AccountRepository accounts, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.calendar = calendar;
    }

    // ================================================================== 加盟店・カード

    @Transactional
    public Merchant registerMerchant(String name, String mcc, long accountId, int feeRateBp) {
        Account a = account(accountId);
        if (!"JPY".equals(a.currency()) || name == null || name.isBlank() || mcc == null || !mcc.matches("[0-9]{4}")
                || feeRateBp < 0 || feeRateBp > 1000) {
            throw new InvalidRequestException("a JPY settlement account, name, 4-digit MCC and fee 0-1000 bp are required");
        }
        long id = jdbc.sql("INSERT INTO merchants (name, mcc, account_id, fee_rate_bp) VALUES (:n, :m, :a, :f) RETURNING id")
                .param("n", name.trim()).param("m", mcc).param("a", accountId).param("f", feeRateBp).query(Long.class).single();
        return merchant(id);
    }

    /** デビットカードの発行。カード番号は保存せず、ランダムなトークンと下 4 桁だけを持つ。 */
    @Transactional
    public Card issueCard(long accountId, long dailyLimitMinor) {
        Account a = account(accountId);
        if (!a.isActive() || !"JPY".equals(a.currency()) || dailyLimitMinor <= 0) {
            throw new InvalidRequestException("an active JPY account and a positive daily limit are required");
        }
        String last4 = String.format("%04d", RANDOM.nextInt(10_000));
        long id = jdbc.sql("""
                        INSERT INTO cards (token, last4, account_id, daily_limit_minor) VALUES (:t, :l, :a, :limit) RETURNING id""")
                .param("t", UUID.randomUUID()).param("l", last4).param("a", accountId).param("limit", dailyLimitMinor)
                .query(Long.class).single();
        return card(id);
    }

    @Transactional
    public Card setCardStatus(long cardId, boolean active) {
        card(cardId);
        jdbc.sql("UPDATE cards SET status = :s WHERE id = :id").param("s", active ? "ACTIVE" : "BLOCKED").param("id", cardId).update();
        return card(cardId);
    }

    // ================================================================== オーソリ・売上確定・取消

    /**
     * オーソリ。承認すれば承認額を口座の拘束額に加える。否決（カード停止・限度額超過・残高不足など）も記録する
     * （不正利用の監視に使う）。同じ加盟店・参照番号の再送には最初の結果を返す。
     */
    public Authorization authorize(UUID cardToken, long merchantId, long amountMinor, String externalRef) {
        if (amountMinor <= 0 || externalRef == null || !externalRef.matches("[A-Za-z0-9-]{1,40}")) {
            throw new InvalidRequestException("a positive amount and an external reference are required");
        }
        return posting.inTransaction(() -> {
            Card card = jdbc.sql("SELECT * FROM cards WHERE token = :t").param("t", cardToken).query(CARD).optional()
                    .orElseThrow(() -> new NotFoundException("card not found"));
            merchant(merchantId);
            Optional<Authorization> existing = jdbc.sql("""
                            SELECT * FROM card_authorizations WHERE merchant_id = :m AND external_ref = :r""")
                    .param("m", merchantId).param("r", externalRef).query(AUTH).optional();
            if (existing.isPresent()) {
                return existing.get();
            }
            // 口座をロックしてから限度額・残高を判定する（同じ口座への同時オーソリで拘束額を取りこぼさない）
            Account a = posting.lockAccounts(card.accountId()).get(card.accountId());
            LocalDate today = calendar.today();
            String decline = null;
            if (!"ACTIVE".equals(card.status())) {
                decline = "CARD_BLOCKED";
            } else if (!a.isActive()) {
                decline = "ACCOUNT_NOT_ACTIVE";
            } else {
                long usedToday = jdbc.sql("""
                                SELECT COALESCE(SUM(CASE WHEN status = 'CAPTURED' THEN captured_minor ELSE amount_minor END), 0)
                                  FROM card_authorizations
                                 WHERE card_id = :c AND business_date = :d AND status IN ('AUTHORIZED', 'CAPTURED')""")
                        .param("c", card.id()).param("d", today).query(Long.class).single();
                if (usedToday + amountMinor > card.dailyLimitMinor()) {
                    decline = "DAILY_LIMIT";
                } else if (a.availableBalanceMinor() < amountMinor) {
                    decline = "INSUFFICIENT_FUNDS";
                }
            }
            if (decline == null) {
                jdbc.sql("UPDATE accounts SET held_balance_minor = held_balance_minor + :d, version = version + 1, updated_at = now() WHERE id = :id")
                        .param("d", amountMinor).param("id", a.id()).update();
            }
            long id = jdbc.sql("""
                            INSERT INTO card_authorizations (card_id, merchant_id, amount_minor, status, decline_reason,
                                auth_code, external_ref, business_date)
                            VALUES (:c, :m, :a, :s, :reason, :code, :ref, :d) RETURNING id""")
                    .param("c", card.id()).param("m", merchantId).param("a", amountMinor)
                    .param("s", decline == null ? "AUTHORIZED" : "DECLINED").param("reason", decline)
                    .param("code", decline == null ? String.format("%06d", RANDOM.nextInt(1_000_000)) : null)
                    .param("ref", externalRef).param("d", today).query(Long.class).single();
            posting.event("Card", card.id(), decline == null ? "CardAuthorized" : "CardDeclined",
                    Map.of("authorizationId", id, "amountMinor", amountMinor, "reason", decline == null ? "" : decline));
            return authorization(id);
        });
    }

    /** 売上確定（承認額以下）。拘束を外し、確定額を引き落として加盟店未払金に計上する。 */
    public CaptureResult capture(long authorizationId, long amountMinor, String key) {
        return posting.inTransaction(() -> {
            Authorization pre = authorization(authorizationId);
            long accountId = card(pre.cardId()).accountId();
            Reservation res = posting.reserve(TransactionType.CARD_CAPTURE, key,
                    PostingSupport.hash("CAPTURE", authorizationId, amountMinor), accountId, null, amountMinor, "JPY",
                    "カード売上 #" + authorizationId, null);
            if (res.isReplay()) {
                return new CaptureResult(res.replayed(), authorization(authorizationId), true);
            }
            Authorization auth = lockAuthorization(authorizationId);
            if (!"AUTHORIZED".equals(auth.status())) {
                throw new BusinessConflictException("authorization " + authorizationId + " is " + auth.status());
            }
            if (amountMinor <= 0 || amountMinor > auth.amountMinor()) {
                throw new InvalidRequestException("capture amount must be between 1 and the authorized amount");
            }
            posting.lockAccounts(accountId);
            jdbc.sql("UPDATE accounts SET held_balance_minor = held_balance_minor - :d, version = version + 1, updated_at = now() WHERE id = :id")
                    .param("d", auth.amountMinor()).param("id", accountId).update();
            long txId = res.transactionId();
            posting.post(txId, "カード売上 #" + authorizationId, List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, accountId, amountMinor, "JPY"),
                    JournalLine.credit(GL_MERCHANT_PAYABLE, null, amountMinor, "JPY")));
            jdbc.sql("""
                            UPDATE card_authorizations SET status = 'CAPTURED', captured_minor = :a, capture_transaction_id = :tx
                             WHERE id = :id""")
                    .param("a", amountMinor).param("tx", txId).param("id", authorizationId).update();
            receivable(txId, auth.merchantId(), amountMinor, "CARD");
            MoneyTransaction done = posting.complete(txId);
            posting.event("Card", auth.cardId(), "CardCaptured", Map.of("authorizationId", authorizationId,
                    "transactionId", txId, "amountMinor", amountMinor));
            return new CaptureResult(done, authorization(authorizationId), false);
        });
    }

    /** オーソリの取消（売上にならなかった）。拘束だけを外す。 */
    public Authorization release(long authorizationId) {
        return posting.inTransaction(() -> {
            Authorization auth = lockAuthorization(authorizationId);
            if (!"AUTHORIZED".equals(auth.status())) {
                throw new BusinessConflictException("authorization " + authorizationId + " is " + auth.status());
            }
            long accountId = card(auth.cardId()).accountId();
            posting.lockAccounts(accountId);
            jdbc.sql("UPDATE accounts SET held_balance_minor = held_balance_minor - :d, version = version + 1, updated_at = now() WHERE id = :id")
                    .param("d", auth.amountMinor()).param("id", accountId).update();
            jdbc.sql("UPDATE card_authorizations SET status = 'RELEASED' WHERE id = :id").param("id", authorizationId).update();
            return authorization(authorizationId);
        });
    }

    // ================================================================== 電子マネー

    @Transactional
    public Wallet openWallet(long accountId) {
        account(accountId);
        Optional<Long> id = jdbc.sql("INSERT INTO wallets (account_id) VALUES (:a) ON CONFLICT DO NOTHING RETURNING id")
                .param("a", accountId).query(Long.class).optional();
        if (id.isEmpty()) {
            throw new BusinessConflictException("account " + accountId + " already has a wallet");
        }
        return wallet(id.get());
    }

    /** チャージ（口座 → 電子マネー）。残高上限を超えるチャージは受け付けない。 */
    public WalletResult topUp(long walletId, long amountMinor, String key) {
        if (amountMinor <= 0) {
            throw new InvalidRequestException("amountMinor must be positive");
        }
        return posting.inTransaction(() -> {
            Wallet pre = wallet(walletId);
            Reservation res = posting.reserve(TransactionType.WALLET_TOPUP, key,
                    PostingSupport.hash("TOPUP", walletId, amountMinor), pre.accountId(), null, amountMinor, "JPY",
                    "電子マネー チャージ", null);
            if (res.isReplay()) {
                return new WalletResult(res.replayed(), wallet(walletId), true);
            }
            Wallet w = lockWallet(walletId);
            if (!"ACTIVE".equals(w.status()) || w.balanceMinor() + amountMinor > w.maxBalanceMinor()) {
                throw new BusinessConflictException("the wallet is suspended or the top-up exceeds its maximum balance");
            }
            Account a = posting.lockAccounts(w.accountId()).get(w.accountId());
            Optional<RejectReason> rejection = TransactionRules.check(a, null, amountMinor, "JPY");
            if (rejection.isPresent()) {
                return new WalletResult(posting.reject(res.transactionId(), rejection.get()), w, false);
            }
            posting.post(res.transactionId(), "電子マネー チャージ", List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, w.accountId(), amountMinor, "JPY"),
                    JournalLine.credit(GL_EMONEY, null, amountMinor, "JPY")));
            setWalletBalance(walletId, w.balanceMinor() + amountMinor);
            return new WalletResult(posting.complete(res.transactionId()), wallet(walletId), false);
        });
    }

    /** 電子マネーでの支払。残高が足りなければ REJECTED。 */
    public WalletResult pay(long walletId, long merchantId, long amountMinor, String key) {
        if (amountMinor <= 0) {
            throw new InvalidRequestException("amountMinor must be positive");
        }
        return posting.inTransaction(() -> {
            merchant(merchantId);
            wallet(walletId);
            Reservation res = posting.reserve(TransactionType.WALLET_PAYMENT, key,
                    PostingSupport.hash("WALLET_PAY", walletId, merchantId, amountMinor), null, null, amountMinor, "JPY",
                    "電子マネー 支払 加盟店#" + merchantId, null);
            if (res.isReplay()) {
                return new WalletResult(res.replayed(), wallet(walletId), true);
            }
            Wallet w = lockWallet(walletId);
            if (!"ACTIVE".equals(w.status())) {
                throw new BusinessConflictException("wallet " + walletId + " is suspended");
            }
            if (w.balanceMinor() < amountMinor) {
                return new WalletResult(posting.reject(res.transactionId(), RejectReason.INSUFFICIENT_FUNDS), w, false);
            }
            posting.post(res.transactionId(), "電子マネー 支払", List.of(
                    JournalLine.debit(GL_EMONEY, null, amountMinor, "JPY"),
                    JournalLine.credit(GL_MERCHANT_PAYABLE, null, amountMinor, "JPY")));
            setWalletBalance(walletId, w.balanceMinor() - amountMinor);
            receivable(res.transactionId(), merchantId, amountMinor, "WALLET");
            return new WalletResult(posting.complete(res.transactionId()), wallet(walletId), false);
        });
    }

    // ================================================================== 加盟店精算

    /**
     * 加盟店ごとに未精算の売上をまとめ、手数料を差し引いて加盟店の口座へ支払う（日次締めのステップ）。
     * 冪等キーは精算対象の最初の売上の取引 ID から決めるので、再実行しても同じ売上を二重に精算しない。
     */
    public List<Settlement> settleMerchants() {
        List<Settlement> results = new ArrayList<>();
        for (long merchantId : jdbc.sql("SELECT DISTINCT merchant_id FROM merchant_receivables WHERE NOT settled ORDER BY 1")
                .query(Long.class).list()) {
            Settlement s = posting.inTransaction(() -> settle(merchantId));
            if (s != null) {
                results.add(s);
            }
        }
        return results;
    }

    private Settlement settle(long merchantId) {
        Merchant m = merchant(merchantId);
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT transaction_id, gross_minor FROM merchant_receivables WHERE merchant_id = :m AND NOT settled
                         ORDER BY transaction_id FOR NO KEY UPDATE""")
                .param("m", merchantId).query().listOfRows();
        if (rows.isEmpty()) {
            return null;
        }
        List<Long> ids = rows.stream().map(r -> ((Number) r.get("transaction_id")).longValue()).toList();
        long gross = rows.stream().mapToLong(r -> ((Number) r.get("gross_minor")).longValue()).sum();
        long fee = Math.multiplyExact(gross, (long) m.feeRateBp()) / 10_000;
        long net = gross - fee;
        Reservation res = posting.reserve(TransactionType.MERCHANT_SETTLEMENT, "merchant-settle-" + merchantId + "-" + ids.getFirst(),
                PostingSupport.hash("SETTLE", merchantId, ids), null, m.accountId(), gross, "JPY",
                "加盟店精算 " + m.name(), null);
        if (res.isReplay()) {
            return null;
        }
        posting.lockAccounts(m.accountId());
        List<JournalLine> lines = new ArrayList<>();
        lines.add(JournalLine.debit(GL_MERCHANT_PAYABLE, null, gross, "JPY"));
        if (net > 0) {
            lines.add(JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, m.accountId(), net, "JPY"));
        }
        if (fee > 0) {
            lines.add(JournalLine.credit(GL_MERCHANT_FEE, null, fee, "JPY"));
        }
        posting.post(res.transactionId(), "加盟店精算 " + m.name(), lines);
        jdbc.sql("UPDATE merchant_receivables SET settled = true, settlement_transaction_id = :tx WHERE transaction_id IN (:ids)")
                .param("tx", res.transactionId()).param("ids", ids).update();
        MoneyTransaction done = posting.complete(res.transactionId());
        posting.event("Merchant", merchantId, "MerchantSettled", Map.of("grossMinor", gross, "feeMinor", fee));
        return new Settlement(merchantId, ids.size(), gross, fee, net, done);
    }

    // ================================================================== 照会

    @Transactional(readOnly = true)
    public List<Authorization> authorizations(long cardId) {
        return jdbc.sql("SELECT * FROM card_authorizations WHERE card_id = :c ORDER BY id DESC LIMIT 200")
                .param("c", cardId).query(AUTH).list();
    }

    @Transactional(readOnly = true)
    public List<Card> cards(long accountId) {
        return jdbc.sql("SELECT * FROM cards WHERE account_id = :a ORDER BY id").param("a", accountId).query(CARD).list();
    }

    @Transactional(readOnly = true)
    public Card card(long id) {
        return jdbc.sql("SELECT * FROM cards WHERE id = :id").param("id", id).query(CARD).optional()
                .orElseThrow(() -> new NotFoundException("card " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Wallet wallet(long id) {
        return jdbc.sql("SELECT * FROM wallets WHERE id = :id").param("id", id).query(WALLET).optional()
                .orElseThrow(() -> new NotFoundException("wallet " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Merchant merchant(long id) {
        return jdbc.sql("SELECT * FROM merchants WHERE id = :id").param("id", id).query(MERCHANT).optional()
                .orElseThrow(() -> new NotFoundException("merchant " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Authorization authorization(long id) {
        return jdbc.sql("SELECT * FROM card_authorizations WHERE id = :id").param("id", id).query(AUTH).optional()
                .orElseThrow(() -> new NotFoundException("authorization " + id + " not found"));
    }

    // ================================================================== helpers

    private Authorization lockAuthorization(long id) {
        return jdbc.sql("SELECT * FROM card_authorizations WHERE id = :id FOR NO KEY UPDATE").param("id", id)
                .query(AUTH).optional().orElseThrow(() -> new NotFoundException("authorization " + id + " not found"));
    }

    private Wallet lockWallet(long id) {
        return jdbc.sql("SELECT * FROM wallets WHERE id = :id FOR NO KEY UPDATE").param("id", id).query(WALLET).single();
    }

    private void setWalletBalance(long id, long balance) {
        jdbc.sql("UPDATE wallets SET balance_minor = :b, version = version + 1 WHERE id = :id")
                .param("b", balance).param("id", id).update();
    }

    private void receivable(long txId, long merchantId, long gross, String source) {
        jdbc.sql("INSERT INTO merchant_receivables (transaction_id, merchant_id, gross_minor, source) VALUES (:tx, :m, :g, :s)")
                .param("tx", txId).param("m", merchantId).param("g", gross).param("s", source).update();
    }

    private Account account(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("account " + id + " not found"));
    }
}
