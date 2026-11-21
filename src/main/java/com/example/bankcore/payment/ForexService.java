package com.example.bankcore.payment;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountRepository;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.ledger.JournalRepository;
import com.example.bankcore.posting.PostingSupport;
import com.example.bankcore.posting.PostingSupport.Reservation;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionRepository;
import com.example.bankcore.transaction.TransactionRules;
import com.example.bankcore.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外国為替：為替相場の管理、両替（円 ⇔ 外貨）、海外送金（制裁リスト・高リスク国の照合と保留）。
 *
 * <h2>勘定の流れ（仕訳は通貨ごとに貸借一致させる）</h2>
 * <pre>
 *  外貨買い（円→USD）  JPY: 借方 顧客預金（円口座）  ／ 貸方 外国為替ポジション
 *                      USD: 借方 外国為替ポジション   ／ 貸方 顧客預金（外貨口座）
 *  海外送金（円口座）  JPY: 借方 顧客預金 ／ 貸方 外国為替ポジション、USD: 借方 外国為替ポジション ／ 貸方 外国送金未決済
 *  海外送金（外貨口座）USD: 借方 顧客預金 ／ 貸方 外国送金未決済
 *  送金手数料          JPY: 借方 顧客預金 ／ 貸方 為替手数料
 *  謝絶による返金      送金時の仕訳の貸借を入れ替える
 * </pre>
 * 外国為替ポジション科目の通貨別残高が、銀行が持つ外貨の持ち高（為替リスク）を表す。
 * 外貨の最小単位はセント（小数 2 桁）。円換算は顧客に支払う側を切り捨て、受け取る側を切り上げる。
 */
@Service
public class ForexService {

    static final String GL_FX_POSITION = "1900";
    static final String GL_UNSETTLED_FOREIGN = "2410";
    /** 海外送金の手数料（円） */
    public static final long FOREIGN_FEE_JPY = 4_000;

    private static final Pattern BIC = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final Pattern IBAN_OR_ACCOUNT = Pattern.compile("[A-Z0-9]{5,34}");
    private static final Pattern SWIFT_TEXT = Pattern.compile("[A-Z0-9 .,()/'+:?-]{1,70}");

    public record FxRate(String currency, BigDecimal ttm, BigDecimal spread, BigDecimal tts, BigDecimal ttb,
                         String updatedBy, OffsetDateTime updatedAt) {
    }

    public record Exchange(long transactionId, String side, long fromAccountId, long toAccountId, String currency,
                           long foreignMinor, long yenMinor, BigDecimal rate) {
    }

    public record ExchangeResult(MoneyTransaction transaction, Exchange exchange, boolean replayed) {
    }

    public record ForeignRemittance(long id, long accountId, long feeAccountId, String currency, long amountMinor,
                                    long debitMinor, String debitCurrency, BigDecimal appliedRate, long feeMinor,
                                    String beneficiaryName, String beneficiaryAccount, String swiftBic, String country,
                                    String purpose, String status, String screeningResult, String message,
                                    long transactionId, String decidedBy, OffsetDateTime createdAt) {
    }

    public record ForeignResult(MoneyTransaction transaction, ForeignRemittance remittance, boolean replayed) {
    }

    public record ForeignOrder(long accountId, Long feeAccountId, String currency, long amountMinor,
                               String beneficiaryName, String beneficiaryAccount, String swiftBic, String country,
                               String purpose) {
    }

    /** 照合結果。hit なら保留にする。 */
    public record ScreeningResult(boolean hit, String rule, int score, String detail) {
    }

    private static final RowMapper<FxRate> RATE = (rs, i) -> {
        BigDecimal ttm = rs.getBigDecimal("ttm");
        BigDecimal spread = rs.getBigDecimal("spread");
        return new FxRate(rs.getString("currency"), ttm, spread, ttm.add(spread), ttm.subtract(spread),
                rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class));
    };

    private static final RowMapper<Exchange> EXCHANGE = (rs, i) -> new Exchange(rs.getLong("transaction_id"),
            rs.getString("side"), rs.getLong("from_account_id"), rs.getLong("to_account_id"), rs.getString("currency"),
            rs.getLong("foreign_minor"), rs.getLong("yen_minor"), rs.getBigDecimal("rate"));

    private static final RowMapper<ForeignRemittance> FOREIGN = (rs, i) -> new ForeignRemittance(rs.getLong("id"),
            rs.getLong("account_id"), rs.getLong("fee_account_id"), rs.getString("currency"), rs.getLong("amount_minor"),
            rs.getLong("debit_minor"), rs.getString("debit_currency"), rs.getBigDecimal("applied_rate"),
            rs.getLong("fee_minor"), rs.getString("beneficiary_name"), rs.getString("beneficiary_account"),
            rs.getString("swift_bic"), rs.getString("country"), rs.getString("purpose"), rs.getString("status"),
            rs.getString("screening_result"), rs.getString("message"), rs.getLong("transaction_id"),
            rs.getString("decided_by"), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;
    private final PostingSupport posting;
    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final TransactionRepository transactions;

    public ForexService(JdbcClient jdbc, PostingSupport posting, AccountRepository accounts, JournalRepository journal,
                        TransactionRepository transactions) {
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.journal = journal;
    }

    // ================================================================== 為替相場

    @Transactional(readOnly = true)
    public List<FxRate> rates() {
        return jdbc.sql("SELECT * FROM fx_rates ORDER BY currency").query(RATE).list();
    }

    @Transactional(readOnly = true)
    public FxRate rate(String currency) {
        return jdbc.sql("SELECT * FROM fx_rates WHERE currency = :c").param("c", currency).query(RATE).optional()
                .orElseThrow(() -> new NotFoundException("no FX rate for " + currency));
    }

    /** 公示相場の更新（仲値と片側スプレッド）。スプレッドが仲値以上だと買値が 0 以下になるので拒否する。 */
    @Transactional
    public FxRate updateRate(String currency, BigDecimal ttm, BigDecimal spread, String updatedBy) {
        if (ttm == null || ttm.signum() <= 0 || spread == null || spread.signum() < 0 || spread.compareTo(ttm) >= 0
                || ttm.scale() > 4 || spread.scale() > 4) {
            throw new InvalidRequestException("ttm must be positive and 0 <= spread < ttm (max 4 decimals)");
        }
        int updated = jdbc.sql("UPDATE fx_rates SET ttm = :ttm, spread = :sp, updated_by = :by, updated_at = now() WHERE currency = :c")
                .param("ttm", ttm).param("sp", spread).param("by", PaymentService.requireText(updatedBy, "updatedBy", 50))
                .param("c", currency).update();
        if (updated == 0) {
            throw new NotFoundException("no FX rate for " + currency);
        }
        return rate(currency);
    }

    /** 外貨 → 円（外貨の最小単位はセント）。 */
    static long yen(long foreignMinor, BigDecimal rate, RoundingMode mode) {
        return BigDecimal.valueOf(foreignMinor).movePointLeft(2).multiply(rate).setScale(0, mode).longValueExact();
    }

    // ================================================================== 両替

    /**
     * 両替：同じ名義の円口座と外貨口座の間で、外貨額を指定して交換する。
     * 円 → 外貨（顧客の外貨買い）は TTS で円を切り上げ、外貨 → 円（外貨売り）は TTB で円を切り捨てる。
     */
    public ExchangeResult exchange(long fromAccountId, long toAccountId, long foreignMinor, String key,
                                   String correlationId) {
        PostingSupport.requireKey(key);
        if (foreignMinor <= 0 || fromAccountId == toAccountId) {
            throw new InvalidRequestException("a positive foreign amount and two different accounts are required");
        }
        return posting.inTransaction(() -> {
            Account from = account(fromAccountId);
            Account to = account(toAccountId);
            if (!from.ownerName().equals(to.ownerName())) {
                throw new BusinessConflictException("exchanges are only allowed between accounts of the same owner");
            }
            boolean buying = "JPY".equals(from.currency()) && !"JPY".equals(to.currency());
            boolean selling = !"JPY".equals(from.currency()) && "JPY".equals(to.currency());
            if (!buying && !selling) {
                throw new InvalidRequestException("one account must be JPY and the other a foreign currency");
            }
            String ccy = buying ? to.currency() : from.currency();
            FxRate r = rate(ccy);
            BigDecimal applied = buying ? r.tts() : r.ttb();
            long yen = yen(foreignMinor, applied, buying ? RoundingMode.CEILING : RoundingMode.DOWN);
            if (yen <= 0) {
                throw new InvalidRequestException("amount is too small to exchange");
            }
            String memo = (buying ? "外貨買 " : "外貨売 ") + ccy + " " + BigDecimal.valueOf(foreignMinor).movePointLeft(2)
                    + " @" + applied.stripTrailingZeros().toPlainString();
            // 相場は要求内容に含めない（再送時に相場が変わっていても同じ取引として扱う）
            Reservation res = posting.reserve(TransactionType.FX_EXCHANGE, key,
                    PostingSupport.hash("FX", fromAccountId, toAccountId, foreignMinor), fromAccountId, toAccountId,
                    foreignMinor, ccy, memo, correlationId);
            if (res.isReplay()) {
                return new ExchangeResult(res.replayed(), exchangeOf(res.replayed().id()), true);
            }
            long txId = res.transactionId();
            Map<Long, Account> locked = posting.lockAccounts(fromAccountId, toAccountId);
            long out = buying ? yen : foreignMinor;
            long in = buying ? foreignMinor : yen;
            Optional<RejectReason> rejection = TransactionRules.check(locked.get(fromAccountId), null, out, from.currency())
                    .or(() -> TransactionRules.check(null, locked.get(toAccountId), in, to.currency()));
            if (rejection.isPresent()) {
                return new ExchangeResult(posting.reject(txId, rejection.get()), null, false);
            }
            List<JournalLine> lines = buying
                    ? List.of(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, fromAccountId, yen, "JPY"),
                              JournalLine.credit(GL_FX_POSITION, null, yen, "JPY"),
                              JournalLine.debit(GL_FX_POSITION, null, foreignMinor, ccy),
                              JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, toAccountId, foreignMinor, ccy))
                    : List.of(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, fromAccountId, foreignMinor, ccy),
                              JournalLine.credit(GL_FX_POSITION, null, foreignMinor, ccy),
                              JournalLine.debit(GL_FX_POSITION, null, yen, "JPY"),
                              JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, toAccountId, yen, "JPY"));
            posting.post(txId, memo, lines);
            jdbc.sql("""
                            INSERT INTO fx_exchanges (transaction_id, side, from_account_id, to_account_id, currency,
                                foreign_minor, yen_minor, rate)
                            VALUES (:tx, :side, :from, :to, :ccy, :fx, :yen, :rate)""")
                    .param("tx", txId).param("side", buying ? "BUY" : "SELL").param("from", fromAccountId)
                    .param("to", toAccountId).param("ccy", ccy).param("fx", foreignMinor).param("yen", yen)
                    .param("rate", applied).update();
            MoneyTransaction done = posting.complete(txId);
            posting.event("FxExchange", txId, "FxExchanged", Map.of("transactionId", txId, "side", buying ? "BUY" : "SELL",
                    "currency", ccy, "foreignMinor", foreignMinor, "yenMinor", yen));
            return new ExchangeResult(done, exchangeOf(txId), false);
        });
    }

    // ================================================================== 海外送金

    /**
     * 海外送金の受付。送金額と手数料（円）を引き落とし、受取人を制裁リスト・高リスク国と照合する。
     * 該当の可能性があれば HELD（資金は引き落としたまま役席の判断を待つ）、なければ SENT（MT103 電文を作成）。
     */
    public ForeignResult sendForeign(ForeignOrder o, String key, String correlationId) {
        PostingSupport.requireKey(key);
        String name = upper(o.beneficiaryName());
        String acct = upper(o.beneficiaryAccount()).replace(" ", "");
        String bic = upper(o.swiftBic());
        String country = upper(o.country());
        String purpose = upper(o.purpose());
        if (!SWIFT_TEXT.matcher(name).matches() || !IBAN_OR_ACCOUNT.matcher(acct).matches() || !BIC.matcher(bic).matches()
                || !country.matches("[A-Z]{2}") || !SWIFT_TEXT.matcher(purpose).matches() || o.amountMinor() <= 0
                || o.currency() == null || "JPY".equals(o.currency())) {
            throw new InvalidRequestException("invalid foreign remittance order");
        }
        long feeAccountId = o.feeAccountId() == null ? o.accountId() : o.feeAccountId();
        return posting.inTransaction(() -> {
            Account source = account(o.accountId());
            Account feeAccount = account(feeAccountId);
            if (!source.ownerName().equals(feeAccount.ownerName()) || !"JPY".equals(feeAccount.currency())) {
                throw new InvalidRequestException("the fee account must be a JPY account of the same owner");
            }
            boolean fromYen = "JPY".equals(source.currency());
            if (!fromYen && !source.currency().equals(o.currency())) {
                throw new InvalidRequestException("a foreign currency account can only send its own currency");
            }
            FxRate r = rate(o.currency());
            long debit = fromYen ? yen(o.amountMinor(), r.tts(), RoundingMode.CEILING) : o.amountMinor();
            Reservation res = posting.reserve(TransactionType.FOREIGN_REMITTANCE, key,
                    PostingSupport.hash("FOREIGN", o.accountId(), feeAccountId, o.currency(), o.amountMinor(), name, acct,
                            bic, country, purpose),
                    o.accountId(), null, o.amountMinor(), o.currency(), "海外送金 " + o.currency() + " " + country,
                    correlationId);
            if (res.isReplay()) {
                return new ForeignResult(res.replayed(), foreignByTransaction(res.replayed().id()), true);
            }
            long txId = res.transactionId();
            Map<Long, Account> locked = posting.lockAccounts(o.accountId(), feeAccountId);
            long yenFromSource = fromYen ? debit : 0;
            long yenFee = FOREIGN_FEE_JPY;
            Optional<RejectReason> rejection = feeAccountId == o.accountId()
                    ? TransactionRules.check(locked.get(o.accountId()), null, debit + yenFee, "JPY")
                    : TransactionRules.check(locked.get(o.accountId()), null, debit, source.currency())
                            .or(() -> TransactionRules.check(locked.get(feeAccountId), null, yenFee, "JPY"));
            if (rejection.isPresent()) {
                return new ForeignResult(posting.reject(txId, rejection.get()), null, false);
            }
            List<JournalLine> lines = new ArrayList<>();
            if (fromYen) {
                lines.add(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, o.accountId(), yenFromSource, "JPY"));
                lines.add(JournalLine.credit(GL_FX_POSITION, null, yenFromSource, "JPY"));
                lines.add(JournalLine.debit(GL_FX_POSITION, null, o.amountMinor(), o.currency()));
            } else {
                lines.add(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, o.accountId(), o.amountMinor(), o.currency()));
            }
            lines.add(JournalLine.credit(GL_UNSETTLED_FOREIGN, null, o.amountMinor(), o.currency()));
            lines.add(JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, feeAccountId, yenFee, "JPY"));
            lines.add(JournalLine.credit(PaymentService.GL_FEE_INCOME, null, yenFee, "JPY"));
            posting.post(txId, "海外送金 " + o.currency() + " " + BigDecimal.valueOf(o.amountMinor()).movePointLeft(2)
                    + " -> " + country, lines);

            ScreeningResult s = screen(name, country);
            String status = s.hit() ? "HELD" : "SENT";
            long id = jdbc.sql("""
                            INSERT INTO foreign_remittances (account_id, fee_account_id, currency, amount_minor, debit_minor,
                                debit_currency, applied_rate, fee_minor, beneficiary_name, beneficiary_account, swift_bic,
                                country, purpose, status, screening_result, transaction_id)
                            VALUES (:a, :fa, :ccy, :amt, :debit, :dccy, :rate, :fee, :name, :acct, :bic, :country, :purpose,
                                    :status, :screening, :tx)
                            RETURNING id""")
                    .param("a", o.accountId()).param("fa", feeAccountId).param("ccy", o.currency())
                    .param("amt", o.amountMinor()).param("debit", debit).param("dccy", source.currency())
                    .param("rate", fromYen ? r.tts() : null).param("fee", yenFee).param("name", name).param("acct", acct)
                    .param("bic", bic).param("country", country).param("purpose", purpose).param("status", status)
                    .param("screening", s.detail()).param("tx", txId).query(Long.class).single();
            if (!s.hit()) {
                setMessage(id, mt103(id, source, o.currency(), o.amountMinor(), name, acct, bic, purpose, LocalDate.now()));
            }
            MoneyTransaction done = posting.complete(txId);
            posting.event("ForeignRemittance", id, s.hit() ? "ForeignRemittanceHeld" : "ForeignRemittanceSent",
                    Map.of("remittanceId", id, "transactionId", txId, "screening", s.detail()));
            return new ForeignResult(done, foreign(id), false);
        });
    }

    /**
     * 保留した海外送金の判断（役席）：送金（RELEASE）するか、謝絶して送金額と手数料を返す（REJECT）。
     * 返金は送金時の仕訳の貸借を入れ替えて記帳する（外貨の持ち高も元に戻る）。
     */
    public ForeignResult decide(long remittanceId, boolean release, String decidedBy, String note, String key,
                                String correlationId) {
        String by = PaymentService.requireText(decidedBy, "decidedBy", 50);
        String why = PaymentService.requireText(note, "note", 200);
        return posting.inTransaction(() -> {
            ForeignRemittance pre = foreign(remittanceId);
            Reservation res = release ? null : posting.reserve(TransactionType.FOREIGN_REFUND, key,
                    PostingSupport.hash("FOREIGN_REFUND", remittanceId), null, pre.accountId(), pre.amountMinor(),
                    pre.currency(), "海外送金 謝絶返金 #" + remittanceId, correlationId);
            if (res != null && res.isReplay()) {
                return new ForeignResult(res.replayed(), foreign(remittanceId), true);
            }
            ForeignRemittance f = jdbc.sql("SELECT * FROM foreign_remittances WHERE id = :id FOR NO KEY UPDATE")
                    .param("id", remittanceId).query(FOREIGN).single();
            if (!"HELD".equals(f.status())) {
                throw new BusinessConflictException("only held remittances can be decided (status=" + f.status() + ")");
            }
            String screening = clip(f.screeningResult() + " / 判断 " + by + ": " + why);
            if (release) {
                Account source = account(f.accountId());
                jdbc.sql("UPDATE foreign_remittances SET status = 'SENT', decided_by = :by, screening_result = :s WHERE id = :id")
                        .param("by", by).param("s", screening).param("id", remittanceId).update();
                setMessage(remittanceId, mt103(remittanceId, source, f.currency(), f.amountMinor(), f.beneficiaryName(),
                        f.beneficiaryAccount(), f.swiftBic(), f.purpose(), LocalDate.now()));
                posting.event("ForeignRemittance", remittanceId, "ForeignRemittanceSent", Map.of("remittanceId", remittanceId));
                // 送金の解放は資金を動かさない（受付時に引落し済み）。受付時の取引を返す
                return new ForeignResult(transactions.findById(f.transactionId()).orElseThrow(), foreign(remittanceId), false);
            }
            long txId = res.transactionId();
            posting.lockAccounts(f.accountId(), f.feeAccountId());
            List<JournalLine> refund = journal.linesForTransaction(f.transactionId()).stream()
                    .map(l -> new JournalLine(l.glCode(), l.accountId(), l.creditMinor(), l.debitMinor(), l.currency()))
                    .toList();
            posting.post(txId, "海外送金 謝絶返金 #" + remittanceId, refund);
            jdbc.sql("UPDATE foreign_remittances SET status = 'REJECTED', decided_by = :by, screening_result = :s WHERE id = :id")
                    .param("by", by).param("s", screening).param("id", remittanceId).update();
            MoneyTransaction done = posting.complete(txId);
            posting.event("ForeignRemittance", remittanceId, "ForeignRemittanceRejected", Map.of("remittanceId", remittanceId));
            return new ForeignResult(done, foreign(remittanceId), false);
        });
    }

    /** 制裁リスト（名前の類似度）と高リスク国の照合。 */
    @Transactional(readOnly = true)
    public ScreeningResult screen(String beneficiaryName, String country) {
        double best = 0;
        String bestName = null;
        for (Map<String, Object> e : jdbc.sql("SELECT name, list_name FROM sanctions_list").query().listOfRows()) {
            double sim = Screening.similarity(beneficiaryName, (String) e.get("name"));
            if (sim > best) {
                best = sim;
                bestName = e.get("name") + "（" + e.get("list_name") + "）";
            }
        }
        if (best >= Screening.MATCH_THRESHOLD) {
            return new ScreeningResult(true, "SANCTIONS", (int) Math.round(best * 100),
                    String.format("制裁リスト該当の可能性: %s 類似度 %.2f", bestName, best));
        }
        Optional<String> risky = jdbc.sql("SELECT name FROM high_risk_countries WHERE country = :c").param("c", country)
                .query(String.class).optional();
        if (risky.isPresent()) {
            return new ScreeningResult(true, "HIGH_RISK_COUNTRY", 70, "高リスク国宛: " + country + "（" + risky.get() + "）");
        }
        return new ScreeningResult(false, null, 0, "該当なし");
    }

    /** SWIFT MT103（顧客送金）形式の電文（簡略）。 */
    static String mt103(long id, Account source, String currency, long amountMinor, String name, String account,
                        String bic, String purpose, LocalDate valueDate) {
        String amount = BigDecimal.valueOf(amountMinor).movePointLeft(2).toPlainString().replace('.', ',');
        String bic11 = bic.length() == 8 ? bic + "XXX" : bic;
        return "{1:F01BANKCOREXXXX0000000000}{2:I103" + bic11 + "N}{4:\r\n"
                + ":20:FR" + Zengin.number(id, 14) + "\r\n"
                + ":23B:CRED\r\n"
                + ":32A:" + valueDate.format(DateTimeFormatter.ofPattern("yyMMdd")) + currency + amount + "\r\n"
                + ":50K:/" + source.accountNumber() + "\r\n" + upper(source.ownerName()) + "\r\n"
                + ":57A:" + bic + "\r\n"
                + ":59:/" + account + "\r\n" + name + "\r\n"
                + ":70:" + purpose + "\r\n"
                + ":71A:SHA\r\n-}";
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public ForeignRemittance foreign(long id) {
        return jdbc.sql("SELECT * FROM foreign_remittances WHERE id = :id").param("id", id).query(FOREIGN).optional()
                .orElseThrow(() -> new NotFoundException("foreign remittance " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<ForeignRemittance> foreignRemittances(String status) {
        return jdbc.sql("SELECT * FROM foreign_remittances WHERE (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar)) ORDER BY id DESC LIMIT 500")
                .param("s", status).query(FOREIGN).list();
    }

    @Transactional(readOnly = true)
    public List<Exchange> exchanges() {
        return jdbc.sql("SELECT * FROM fx_exchanges ORDER BY transaction_id DESC LIMIT 500").query(EXCHANGE).list();
    }

    private Exchange exchangeOf(long txId) {
        return jdbc.sql("SELECT * FROM fx_exchanges WHERE transaction_id = :tx").param("tx", txId).query(EXCHANGE)
                .optional().orElse(null);
    }

    private ForeignRemittance foreignByTransaction(long txId) {
        return jdbc.sql("SELECT * FROM foreign_remittances WHERE transaction_id = :tx").param("tx", txId).query(FOREIGN)
                .optional().orElse(null);
    }

    private void setMessage(long id, String message) {
        jdbc.sql("UPDATE foreign_remittances SET message = :m WHERE id = :id").param("m", message).param("id", id).update();
    }

    private Account account(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("account " + id + " not found"));
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String clip(String s) {
        return s == null || s.length() <= 400 ? s : s.substring(0, 400);
    }
}
