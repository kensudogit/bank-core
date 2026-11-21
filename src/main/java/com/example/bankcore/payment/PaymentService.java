package com.example.bankcore.payment;

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
import com.example.bankcore.transaction.MoneyMovementService;
import com.example.bankcore.transaction.MoneyMovementService.MovementResult;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.MovementCommand;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionRules;
import com.example.bankcore.transaction.TransactionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 為替業務（国内）：振替・他行宛振込（仕向）・全銀データ作成と決済・他行からの振込（被仕向）・口座振替。
 *
 * <h2>勘定の流れ</h2>
 * <pre>
 *  仕向（受付）   借方 顧客預金（振込金 + 手数料） ／ 貸方 未決済為替借（振込金）・為替手数料
 *  全銀決済       借方 未決済為替借                ／ 貸方 日銀預け金
 *  受付の取消     受付の逆（送信前のみ。手数料も返す）
 *  組戻し         借方 日銀預け金                  ／ 貸方 顧客預金（決済後。相手行から戻った振込金。手数料は返さない）
 *  被仕向         借方 日銀預け金                  ／ 貸方 顧客預金（名義不一致などは 仮受金 で保留）
 *  保留の解消     借方 仮受金                      ／ 貸方 顧客預金（入金）または 日銀預け金（仕向銀行へ返却）
 *  口座振替       借方 顧客預金（契約者）          ／ 貸方 顧客預金（収納企業）
 * </pre>
 * 各処理は {@link PostingSupport} の手順（冪等キー → 業務データのロック → 口座のロック → 記帳）で 1 トランザクション。
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** 当行の金融機関コード（全銀データのヘッダーに使う。デモ用の架空コード） */
    public static final String OUR_BANK_CODE = "0999";

    // ------------------------------------------------------------------ models

    public record DomesticRemittance(long id, String direction, Long accountId, String bankCode, String branchCode,
                                     String destAccountType, String destAccountNo, String destNameKana,
                                     String remitterName, long amountMinor, long feeMinor, String status,
                                     LocalDate valueDate, Long batchId, String externalRef, long transactionId,
                                     String note, OffsetDateTime createdAt) {
    }

    public record ZenginBatch(long id, LocalDate valueDate, int recordCount, long totalMinor, String content,
                              String status, OffsetDateTime createdAt, OffsetDateTime settledAt) {
    }

    public record RemittanceResult(MoneyTransaction transaction, DomesticRemittance remittance, boolean replayed) {
    }

    public record OutgoingRequest(long accountId, String bankCode, String branchCode, String destAccountType,
                                  String destAccountNo, String destNameKana, long amountMinor) {
    }

    public record IncomingItem(String bankCode, String externalRef, String accountNumber, String nameKana,
                               String remitterName, long amountMinor) {
    }

    public record Biller(String code, String name, long accountId) {
    }

    public record Mandate(long id, long accountId, String billerCode, String payerNumber, String status) {
    }

    public record DebitRequest(long id, long mandateId, long amountMinor, LocalDate dueDate, String status,
                               String resultCode, Long transactionId) {
    }

    public record BillingItem(String billerCode, String payerNumber, long amountMinor, LocalDate dueDate) {
    }

    public record BillingResult(BillingItem item, String result, Long requestId) {
    }

    public record DirectDebitReport(LocalDate businessDate, int processed, int paid, int insufficientFunds,
                                    int noTransaction) {
    }

    private static final RowMapper<DomesticRemittance> REMITTANCE = (rs, i) -> new DomesticRemittance(
            rs.getLong("id"), rs.getString("direction"), (Long) rs.getObject("account_id"), rs.getString("bank_code"),
            rs.getString("branch_code"), rs.getString("dest_account_type"), rs.getString("dest_account_no"),
            rs.getString("dest_name_kana"), rs.getString("remitter_name"), rs.getLong("amount_minor"),
            rs.getLong("fee_minor"), rs.getString("status"), rs.getObject("value_date", LocalDate.class),
            (Long) rs.getObject("batch_id"), rs.getString("external_ref"), rs.getLong("transaction_id"),
            rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class));

    private static final RowMapper<ZenginBatch> BATCH = (rs, i) -> new ZenginBatch(
            rs.getLong("id"), rs.getObject("value_date", LocalDate.class), rs.getInt("record_count"),
            rs.getLong("total_minor"), rs.getString("content"), rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("settled_at", OffsetDateTime.class));

    private static final RowMapper<Mandate> MANDATE = (rs, i) -> new Mandate(rs.getLong("id"), rs.getLong("account_id"),
            rs.getString("biller_code"), rs.getString("payer_number"), rs.getString("status"));

    private static final RowMapper<DebitRequest> REQUEST = (rs, i) -> new DebitRequest(rs.getLong("id"),
            rs.getLong("mandate_id"), rs.getLong("amount_minor"), rs.getObject("due_date", LocalDate.class),
            rs.getString("status"), rs.getString("result_code"), (Long) rs.getObject("transaction_id"));

    private final JdbcClient jdbc;
    private final PostingSupport posting;
    private final AccountRepository accounts;
    private final BusinessCalendar calendar;
    private final MoneyMovementService movements;

    public PaymentService(JdbcClient jdbc, PostingSupport posting, AccountRepository accounts,
                          BusinessCalendar calendar, MoneyMovementService movements) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.calendar = calendar;
        this.movements = movements;
    }

    /** 他行宛振込の手数料：3 万円未満 330 円、3 万円以上 440 円。 */
    public static long domesticFee(long amountMinor) {
        return amountMinor < 30_000 ? 330 : 440;
    }

    // ================================================================== 振替

    /**
     * 振替：同じ名義の口座間の資金移動（振込と同じ仕訳で、名義が同じことだけを追加で確かめる）。
     * 名義は口座の owner_name で比較する（教育用の簡略化。実際は顧客番号で判定する）。
     */
    public MovementResult bookTransfer(long sourceAccountId, long targetAccountId, long amountMinor, String currency,
                                       String memo, String key, String correlationId) {
        Account source = account(sourceAccountId);
        Account target = account(targetAccountId);
        if (!source.ownerName().equals(target.ownerName())) {
            throw new BusinessConflictException("book transfers are only allowed between accounts of the same owner");
        }
        return movements.execute(MovementCommand.transfer(key, sourceAccountId, targetAccountId, amountMinor, currency,
                memo == null ? "振替" : memo, correlationId));
    }

    // ================================================================== 国内為替：仕向

    /** 他行宛振込の受付。振込金と手数料を引き落とし、全銀データの作成まで PENDING で保持する。 */
    public RemittanceResult sendDomestic(OutgoingRequest r, String key, String correlationId) {
        PostingSupport.requireKey(key);
        validateOutgoing(r);
        String nameZ = Zengin.toZenginChars(r.destNameKana()).trim();
        if (nameZ.isEmpty() || nameZ.length() > 30) {
            throw new InvalidRequestException("destNameKana must be 1-30 Zengin characters (katakana / A-Z / 0-9)");
        }
        long fee = domesticFee(r.amountMinor());
        return posting.inTransaction(() -> {
            Account pre = account(r.accountId());
            String hash = PostingSupport.hash("REMIT_OUT", r.accountId(), r.bankCode(), r.branchCode(),
                    r.destAccountType(), r.destAccountNo(), nameZ, r.amountMinor());
            Reservation res = posting.reserve(TransactionType.REMITTANCE_OUT, key, hash, r.accountId(), null,
                    r.amountMinor(), pre.currency(), "他行振込 " + r.bankCode() + "-" + r.branchCode() + " " + nameZ,
                    correlationId);
            if (res.isReplay()) {
                return new RemittanceResult(res.replayed(), remittanceByTransaction(res.replayed().id()), true);
            }
            long txId = res.transactionId();
            Account source = posting.lockAccounts(r.accountId()).get(r.accountId());
            Optional<RejectReason> rejection = TransactionRules.check(source, null, r.amountMinor() + fee, "JPY");
            if (rejection.isPresent()) {
                return new RemittanceResult(posting.reject(txId, rejection.get()), null, false);
            }
            posting.post(txId, "他行振込 " + source.accountNumber() + " -> " + r.bankCode() + "-" + r.branchCode(), List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, source.id(), r.amountMinor() + fee, "JPY"),
                    JournalLine.credit(GL_UNSETTLED_DOMESTIC, null, r.amountMinor(), "JPY"),
                    JournalLine.credit(GL_FEE_INCOME, null, fee, "JPY")));
            long id = jdbc.sql("""
                            INSERT INTO domestic_remittances (direction, account_id, bank_code, branch_code, dest_account_type,
                                dest_account_no, dest_name_kana, amount_minor, fee_minor, status, value_date, transaction_id)
                            VALUES ('OUT', :account, :bank, :branch, :type, :no, :name, :amount, :fee, 'PENDING', :date, :tx)
                            RETURNING id""")
                    .param("account", source.id()).param("bank", r.bankCode()).param("branch", r.branchCode())
                    .param("type", r.destAccountType()).param("no", r.destAccountNo()).param("name", nameZ)
                    .param("amount", r.amountMinor()).param("fee", fee).param("date", calendar.today()).param("tx", txId)
                    .query(Long.class).single();
            MoneyTransaction done = posting.complete(txId);
            posting.event("Remittance", id, "DomesticRemittanceAccepted", Map.of("remittanceId", id,
                    "transactionId", txId, "amountMinor", r.amountMinor(), "feeMinor", fee));
            return new RemittanceResult(done, remittance(id), false);
        });
    }

    /** 送信前（PENDING）の振込の取消し。振込金と手数料を返す。 */
    public RemittanceResult cancelDomestic(long remittanceId, String key, String correlationId) {
        return posting.inTransaction(() -> {
            DomesticRemittance pre = remittance(remittanceId);
            Reservation res = posting.reserve(TransactionType.REMITTANCE_RETURN, key,
                    PostingSupport.hash("REMIT_CANCEL", remittanceId), null, pre.accountId(),
                    pre.amountMinor() + pre.feeMinor(), "JPY", "他行振込 取消 #" + remittanceId, correlationId);
            if (res.isReplay()) {
                return new RemittanceResult(res.replayed(), remittance(remittanceId), true);
            }
            DomesticRemittance r = lockRemittance(remittanceId);
            if (!"OUT".equals(r.direction()) || !"PENDING".equals(r.status())) {
                throw new BusinessConflictException("only pending outgoing remittances can be cancelled (status="
                        + r.status() + ")");
            }
            Account a = requireOpen(posting.lockAccounts(r.accountId()).get(r.accountId()));
            long txId = res.transactionId();
            posting.post(txId, "他行振込 取消 #" + remittanceId, List.of(
                    JournalLine.debit(GL_UNSETTLED_DOMESTIC, null, r.amountMinor(), "JPY"),
                    JournalLine.debit(GL_FEE_INCOME, null, r.feeMinor(), "JPY"),
                    JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, a.id(), r.amountMinor() + r.feeMinor(), "JPY")));
            setRemittanceStatus(remittanceId, "CANCELLED", "送信前に取消");
            MoneyTransaction done = posting.complete(txId);
            posting.event("Remittance", remittanceId, "DomesticRemittanceCancelled", Map.of("remittanceId", remittanceId));
            return new RemittanceResult(done, remittance(remittanceId), false);
        });
    }

    /** 組戻し：決済済みの振込が相手行から戻ってきた。振込金だけを口座に戻す（手数料は返さない）。 */
    public RemittanceResult returnDomestic(long remittanceId, String reason, String key, String correlationId) {
        String why = requireText(reason, "reason", 100);
        return posting.inTransaction(() -> {
            DomesticRemittance pre = remittance(remittanceId);
            Reservation res = posting.reserve(TransactionType.REMITTANCE_RETURN, key,
                    PostingSupport.hash("REMIT_RETURN", remittanceId, why), null, pre.accountId(), pre.amountMinor(),
                    "JPY", "組戻し #" + remittanceId, correlationId);
            if (res.isReplay()) {
                return new RemittanceResult(res.replayed(), remittance(remittanceId), true);
            }
            DomesticRemittance r = lockRemittance(remittanceId);
            if (!"OUT".equals(r.direction()) || !"SETTLED".equals(r.status())) {
                throw new BusinessConflictException("only settled outgoing remittances can be returned (status="
                        + r.status() + "; cancel a pending one instead)");
            }
            Account a = requireOpen(posting.lockAccounts(r.accountId()).get(r.accountId()));
            long txId = res.transactionId();
            posting.post(txId, "組戻し #" + remittanceId, List.of(
                    JournalLine.debit(GL_CLEARING, null, r.amountMinor(), "JPY"),
                    JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, a.id(), r.amountMinor(), "JPY")));
            setRemittanceStatus(remittanceId, "RETURNED", "組戻し: " + why);
            MoneyTransaction done = posting.complete(txId);
            posting.event("Remittance", remittanceId, "DomesticRemittanceReturned", Map.of("remittanceId", remittanceId));
            return new RemittanceResult(done, remittance(remittanceId), false);
        });
    }

    // ================================================================== 全銀データ

    /**
     * 全銀データの作成：振込指定日が業務日付以前の PENDING の振込を 1 ファイル（総合振込）にまとめ、SENT にする。
     * 対象行をロックしてから作るので、同時に取消しが走ってもファイルと状態が食い違わない。
     */
    @Transactional
    public ZenginBatch createZenginBatch() {
        LocalDate today = calendar.today();
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM domestic_remittances
                         WHERE direction = 'OUT' AND status = 'PENDING' AND value_date <= :d
                         ORDER BY id FOR NO KEY UPDATE""")
                .param("d", today).query(Long.class).list();
        if (ids.isEmpty()) {
            throw new BusinessConflictException("no pending remittances to send");
        }
        List<Zengin.Item> items = jdbc.sql("""
                        SELECT r.id, r.bank_code, b.name_kana, r.branch_code, r.dest_account_type, r.dest_account_no,
                               r.dest_name_kana, r.amount_minor
                          FROM domestic_remittances r JOIN banks b ON b.bank_code = r.bank_code
                         WHERE r.id IN (:ids) ORDER BY r.id""")
                .param("ids", ids)
                .query((rs, i) -> new Zengin.Item(rs.getLong("id"), rs.getString("bank_code"), rs.getString("name_kana"),
                        rs.getString("branch_code"), rs.getString("dest_account_type").charAt(0),
                        rs.getString("dest_account_no"), rs.getString("dest_name_kana"), rs.getLong("amount_minor")))
                .list();
        long total = items.stream().mapToLong(Zengin.Item::amountMinor).sum();
        long batchId = jdbc.sql("""
                        INSERT INTO zengin_batches (value_date, record_count, total_minor, content, status)
                        VALUES (:d, :n, :total, :content, 'SENT') RETURNING id""")
                .param("d", today).param("n", items.size()).param("total", total)
                .param("content", Zengin.transferFile(items, today, OUR_BANK_CODE))
                .query(Long.class).single();
        jdbc.sql("UPDATE domestic_remittances SET status = 'SENT', batch_id = :b WHERE id IN (:ids)")
                .param("b", batchId).param("ids", ids).update();
        posting.event("ZenginBatch", batchId, "ZenginBatchSent", Map.of("batchId", batchId, "records", items.size(),
                "totalMinor", total));
        return batch(batchId);
    }

    /** 全銀の決済完了：未決済為替借を日銀預け金で決済し、振込を SETTLED にする。 */
    public ZenginBatch settleBatch(long batchId, String key, String correlationId) {
        return posting.inTransaction(() -> {
            ZenginBatch pre = batch(batchId);
            Reservation res = posting.reserve(TransactionType.CLEARING_SETTLEMENT, key,
                    PostingSupport.hash("SETTLE", batchId), null, null, pre.totalMinor(), "JPY",
                    "全銀決済 バッチ#" + batchId, correlationId);
            if (res.isReplay()) {
                return batch(batchId);
            }
            ZenginBatch b = jdbc.sql("SELECT * FROM zengin_batches WHERE id = :id FOR NO KEY UPDATE")
                    .param("id", batchId).query(BATCH).single();
            if (!"SENT".equals(b.status())) {
                throw new BusinessConflictException("batch " + batchId + " is already " + b.status());
            }
            // 組戻し・取消しは SENT の間は受け付けないので、バッチの合計がそのまま決済額になる
            long txId = res.transactionId();
            posting.post(txId, "全銀決済 バッチ#" + batchId, List.of(
                    JournalLine.debit(GL_UNSETTLED_DOMESTIC, null, b.totalMinor(), "JPY"),
                    JournalLine.credit(GL_CLEARING, null, b.totalMinor(), "JPY")));
            jdbc.sql("UPDATE zengin_batches SET status = 'SETTLED', settled_at = now() WHERE id = :id")
                    .param("id", batchId).update();
            jdbc.sql("UPDATE domestic_remittances SET status = 'SETTLED' WHERE batch_id = :id AND status = 'SENT'")
                    .param("id", batchId).update();
            posting.complete(txId);
            posting.event("ZenginBatch", batchId, "ZenginBatchSettled", Map.of("batchId", batchId));
            return batch(batchId);
        });
    }

    // ================================================================== 国内為替：被仕向

    /**
     * 他行からの振込を取り込む。1 件ごとに別トランザクションで処理し、冪等キーを {@code remit-in-<銀行>-<参照番号>}
     * と決めるので、同じファイルを 2 回取り込んでも二重に入金しない。口座番号と名義が一致すれば入金、
     * それ以外（口座なし・取引不可・名義不一致）は仮受金で保留する。
     */
    public List<RemittanceResult> receiveIncoming(List<IncomingItem> items) {
        if (items == null || items.isEmpty() || items.size() > 500) {
            throw new InvalidRequestException("1-500 items are required");
        }
        List<RemittanceResult> results = new ArrayList<>();
        for (IncomingItem item : items) {
            validateIncoming(item);
            results.add(posting.inTransaction(() -> receiveOne(item)));
        }
        return results;
    }

    private RemittanceResult receiveOne(IncomingItem in) {
        Optional<Account> candidate = accountByNumber(in.accountNumber());
        String key = "remit-in-" + in.bankCode() + "-" + in.externalRef();
        String hash = PostingSupport.hash("REMIT_IN", in.bankCode(), in.externalRef(), in.accountNumber(),
                Zengin.normalizeName(in.nameKana()), in.amountMinor());
        Reservation res = posting.reserve(TransactionType.REMITTANCE_IN, key, hash, null,
                candidate.map(Account::id).orElse(null), in.amountMinor(), "JPY",
                "被仕向 " + in.bankCode() + " " + in.externalRef(), null);
        if (res.isReplay()) {
            return new RemittanceResult(res.replayed(), remittanceByTransaction(res.replayed().id()), true);
        }
        long txId = res.transactionId();
        String note = null;
        Account target = null;
        if (candidate.isEmpty()) {
            note = "口座なし";
        } else {
            target = posting.lockAccounts(candidate.get().id()).get(candidate.get().id());
            if (!target.isActive()) {
                note = "取引不可の口座（" + target.status() + "）";
            } else if (!"JPY".equals(target.currency())) {
                note = "円以外の口座";
            } else if (!Zengin.normalizeName(target.ownerName()).equals(Zengin.normalizeName(in.nameKana()))) {
                note = "受取人名不一致";
            }
        }
        boolean credit = note == null;
        posting.post(txId, "被仕向 " + in.bankCode() + " " + in.externalRef(), List.of(
                JournalLine.debit(GL_CLEARING, null, in.amountMinor(), "JPY"),
                credit ? JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, target.id(), in.amountMinor(), "JPY")
                       : JournalLine.credit(GL_SUSPENSE, null, in.amountMinor(), "JPY")));
        long id = jdbc.sql("""
                        INSERT INTO domestic_remittances (direction, account_id, bank_code, branch_code, dest_account_type,
                            dest_account_no, dest_name_kana, remitter_name, amount_minor, status, value_date,
                            external_ref, transaction_id, note)
                        VALUES ('IN', :account, :bank, '000', '1', :no, :name, :remitter, :amount, :status, :date,
                                :ref, :tx, :note)
                        RETURNING id""")
                .param("account", credit ? target.id() : null).param("bank", in.bankCode())
                .param("no", in.accountNumber()).param("name", Zengin.toZenginChars(in.nameKana()))
                .param("remitter", in.remitterName()).param("amount", in.amountMinor())
                .param("status", credit ? "CREDITED" : "HELD").param("date", calendar.today())
                .param("ref", in.externalRef()).param("tx", txId).param("note", note)
                .query(Long.class).single();
        MoneyTransaction done = posting.complete(txId);
        posting.event("Remittance", id, credit ? "IncomingRemittanceCredited" : "IncomingRemittanceHeld",
                Map.of("remittanceId", id, "amountMinor", in.amountMinor()));
        return new RemittanceResult(done, remittance(id), false);
    }

    /**
     * 保留した被仕向の解消（役席の判断）：指定口座へ入金するか、仕向銀行へ返却する。
     *
     * @param accountId 入金先（返却の場合は null）
     */
    public RemittanceResult resolveHeld(long remittanceId, Long accountId, String decidedBy, String key,
                                        String correlationId) {
        String by = requireText(decidedBy, "decidedBy", 50);
        return posting.inTransaction(() -> {
            DomesticRemittance pre = remittance(remittanceId);
            boolean credit = accountId != null;
            if (credit) {
                account(accountId);
            }
            Reservation res = posting.reserve(credit ? TransactionType.REMITTANCE_IN : TransactionType.REMITTANCE_RETURN,
                    key, PostingSupport.hash("RESOLVE", remittanceId, accountId, by), null, accountId,
                    pre.amountMinor(), "JPY", "被仕向 保留解消 #" + remittanceId, correlationId);
            if (res.isReplay()) {
                return new RemittanceResult(res.replayed(), remittance(remittanceId), true);
            }
            DomesticRemittance r = lockRemittance(remittanceId);
            if (!"IN".equals(r.direction()) || !"HELD".equals(r.status())) {
                throw new BusinessConflictException("only held incoming remittances can be resolved (status="
                        + r.status() + ")");
            }
            long txId = res.transactionId();
            if (credit) {
                Account a = posting.lockAccounts(accountId).get(accountId);
                if (!a.isActive() || !"JPY".equals(a.currency())) {
                    throw new BusinessConflictException("account " + accountId + " cannot receive the credit");
                }
                posting.post(txId, "被仕向 保留解消（入金）#" + remittanceId, List.of(
                        JournalLine.debit(GL_SUSPENSE, null, r.amountMinor(), "JPY"),
                        JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, accountId, r.amountMinor(), "JPY")));
                jdbc.sql("UPDATE domestic_remittances SET status = 'CREDITED', account_id = :a, note = :note WHERE id = :id")
                        .param("a", accountId).param("note", clip(r.note() + " → " + by + " が入金")).param("id", remittanceId)
                        .update();
            } else {
                posting.post(txId, "被仕向 保留解消（返却）#" + remittanceId, List.of(
                        JournalLine.debit(GL_SUSPENSE, null, r.amountMinor(), "JPY"),
                        JournalLine.credit(GL_CLEARING, null, r.amountMinor(), "JPY")));
                setRemittanceStatus(remittanceId, "RETURNED", r.note() + " → " + by + " が返却");
            }
            MoneyTransaction done = posting.complete(txId);
            posting.event("Remittance", remittanceId, "IncomingRemittanceResolved", Map.of("remittanceId", remittanceId,
                    "credited", credit));
            return new RemittanceResult(done, remittance(remittanceId), false);
        });
    }

    // ================================================================== 口座振替

    @Transactional
    public Biller registerBiller(String code, String name, long accountId) {
        if (code == null || !code.matches("[A-Z0-9]{1,8}")) {
            throw new InvalidRequestException("biller code must be 1-8 chars of A-Z / 0-9");
        }
        Account a = account(accountId);
        if (!"JPY".equals(a.currency())) {
            throw new InvalidRequestException("the biller's settlement account must be a JPY account");
        }
        int inserted = jdbc.sql("INSERT INTO billers (code, name, account_id) VALUES (:c, :n, :a) ON CONFLICT DO NOTHING")
                .param("c", code).param("n", requireText(name, "name", 100)).param("a", accountId).update();
        if (inserted == 0) {
            throw new BusinessConflictException("biller " + code + " already exists");
        }
        return new Biller(code, name, accountId);
    }

    @Transactional(readOnly = true)
    public List<Biller> billers() {
        return jdbc.sql("SELECT code, name, account_id FROM billers ORDER BY code")
                .query((rs, i) -> new Biller(rs.getString("code"), rs.getString("name"), rs.getLong("account_id")))
                .list();
    }

    /** 口座振替契約の登録（収納企業ごとのお客さま番号は 1 契約だけ）。 */
    @Transactional
    public Mandate registerMandate(long accountId, String billerCode, String payerNumber) {
        account(accountId);
        if (payerNumber == null || !payerNumber.matches("[A-Za-z0-9-]{1,20}")) {
            throw new InvalidRequestException("payerNumber must be 1-20 chars of [A-Za-z0-9-]");
        }
        if (jdbc.sql("SELECT count(*) FROM billers WHERE code = :c").param("c", billerCode).query(Integer.class).single() == 0) {
            throw new NotFoundException("biller " + billerCode + " not found");
        }
        Optional<Long> id = jdbc.sql("""
                        INSERT INTO debit_mandates (account_id, biller_code, payer_number) VALUES (:a, :b, :p)
                        ON CONFLICT ON CONSTRAINT uq_mandates_payer DO NOTHING RETURNING id""")
                .param("a", accountId).param("b", billerCode).param("p", payerNumber).query(Long.class).optional();
        if (id.isEmpty()) {
            throw new BusinessConflictException("a mandate for " + billerCode + "/" + payerNumber + " already exists");
        }
        return mandate(id.get());
    }

    @Transactional
    public Mandate cancelMandate(long mandateId) {
        int updated = jdbc.sql("UPDATE debit_mandates SET status = 'CANCELLED' WHERE id = :id AND status = 'ACTIVE'")
                .param("id", mandateId).update();
        if (updated == 0) {
            throw new BusinessConflictException("mandate " + mandateId + " is not active");
        }
        return mandate(mandateId);
    }

    @Transactional(readOnly = true)
    public List<Mandate> mandates(Long accountId) {
        return jdbc.sql("SELECT * FROM debit_mandates WHERE (CAST(:a AS bigint) IS NULL OR account_id = CAST(:a AS bigint)) ORDER BY id")
                .param("a", accountId).query(MANDATE).list();
    }

    /** 収納企業からの請求データの取込。契約のない請求・同じ引落日の重複は取り込まずに結果で返す。 */
    @Transactional
    public List<BillingResult> importBilling(List<BillingItem> items) {
        if (items == null || items.isEmpty() || items.size() > 1000) {
            throw new InvalidRequestException("1-1000 items are required");
        }
        List<BillingResult> results = new ArrayList<>();
        for (BillingItem b : items) {
            if (b.amountMinor() <= 0 || b.dueDate() == null) {
                results.add(new BillingResult(b, "INVALID", null));
                continue;
            }
            Optional<Long> mandateId = jdbc.sql("""
                            SELECT id FROM debit_mandates WHERE biller_code = :b AND payer_number = :p AND status = 'ACTIVE'""")
                    .param("b", b.billerCode()).param("p", b.payerNumber()).query(Long.class).optional();
            if (mandateId.isEmpty()) {
                results.add(new BillingResult(b, "NO_MANDATE", null));
                continue;
            }
            Optional<Long> id = jdbc.sql("""
                            INSERT INTO debit_requests (mandate_id, amount_minor, due_date) VALUES (:m, :amount, :due)
                            ON CONFLICT ON CONSTRAINT uq_debit_requests DO NOTHING RETURNING id""")
                    .param("m", mandateId.get()).param("amount", b.amountMinor()).param("due", b.dueDate())
                    .query(Long.class).optional();
            results.add(new BillingResult(b, id.isPresent() ? "IMPORTED" : "DUPLICATE", id.orElse(null)));
        }
        return results;
    }

    @Transactional(readOnly = true)
    public List<DebitRequest> debitRequests() {
        return jdbc.sql("SELECT * FROM debit_requests ORDER BY id DESC LIMIT 500").query(REQUEST).list();
    }

    /**
     * 引落日の到来した請求の引落し（夜間バッチ・日次締めから呼ぶ）。1 件ごとに別トランザクションで、
     * 冪等キー {@code debit-<請求ID>} により再実行しても二重に引き落とさない。残高不足は一部だけ引き落とさず振替不能にする。
     */
    public DirectDebitReport processDirectDebits(LocalDate businessDate) {
        List<Long> due = jdbc.sql("SELECT id FROM debit_requests WHERE status = 'PENDING' AND due_date <= :d ORDER BY id")
                .param("d", businessDate).query(Long.class).list();
        int paid = 0;
        int insufficient = 0;
        int none = 0;
        for (long requestId : due) {
            String code = posting.inTransaction(() -> debitOne(requestId));
            if (code == null) {
                continue;
            }
            switch (code) {
                case "0" -> paid++;
                case "1" -> insufficient++;
                default -> none++;
            }
        }
        log.info("direct debits {}: paid={} insufficient={} noTransaction={}", businessDate, paid, insufficient, none);
        return new DirectDebitReport(businessDate, paid + insufficient + none, paid, insufficient, none);
    }

    /** @return 結果コード（処理済みで対象外なら null） */
    private String debitOne(long requestId) {
        DebitRequest req = jdbc.sql("SELECT * FROM debit_requests WHERE id = :id FOR NO KEY UPDATE")
                .param("id", requestId).query(REQUEST).single();
        if (!"PENDING".equals(req.status())) {
            return null;
        }
        Mandate m = mandate(req.mandateId());
        long billerAccount = jdbc.sql("SELECT account_id FROM billers WHERE code = :c")
                .param("c", m.billerCode()).query(Long.class).single();
        if (!"ACTIVE".equals(m.status())) {
            return finishRequest(requestId, "FAILED", "2", null);
        }
        Reservation res = posting.reserve(TransactionType.DIRECT_DEBIT, "debit-" + requestId,
                PostingSupport.hash("DEBIT", requestId), m.accountId(), billerAccount, req.amountMinor(), "JPY",
                "口座振替 " + m.billerCode() + " " + m.payerNumber(), null);
        if (res.isReplay()) {
            return null;
        }
        long txId = res.transactionId();
        Map<Long, Account> locked = posting.lockAccounts(m.accountId(), billerAccount);
        Account payer = locked.get(m.accountId());
        Account biller = locked.get(billerAccount);
        Optional<RejectReason> rejection = TransactionRules.check(payer, biller, req.amountMinor(), "JPY");
        if (rejection.isPresent()) {
            posting.reject(txId, rejection.get());
            return finishRequest(requestId, "FAILED", rejection.get() == RejectReason.INSUFFICIENT_FUNDS ? "1" : "2", txId);
        }
        posting.post(txId, "口座振替 " + m.billerCode() + " " + m.payerNumber(), List.of(
                JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, payer.id(), req.amountMinor(), "JPY"),
                JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, biller.id(), req.amountMinor(), "JPY")));
        posting.complete(txId);
        posting.event("DirectDebit", requestId, "DirectDebitPaid", Map.of("requestId", requestId, "transactionId", txId));
        return finishRequest(requestId, "PAID", "0", txId);
    }

    private String finishRequest(long id, String status, String code, Long txId) {
        jdbc.sql("""
                        UPDATE debit_requests SET status = :s, result_code = :c, transaction_id = :tx, processed_at = now()
                         WHERE id = :id""")
                .param("s", status).param("c", code).param("tx", txId).param("id", id).update();
        return code;
    }

    // ================================================================== queries

    @Transactional(readOnly = true)
    public List<DomesticRemittance> remittances(String direction, String status) {
        return jdbc.sql("""
                        SELECT * FROM domestic_remittances
                         WHERE (CAST(:dir AS varchar) IS NULL OR direction = CAST(:dir AS varchar))
                           AND (CAST(:st AS varchar) IS NULL OR status = CAST(:st AS varchar))
                         ORDER BY id DESC LIMIT 500""")
                .param("dir", direction).param("st", status).query(REMITTANCE).list();
    }

    @Transactional(readOnly = true)
    public DomesticRemittance remittance(long id) {
        return jdbc.sql("SELECT * FROM domestic_remittances WHERE id = :id").param("id", id).query(REMITTANCE)
                .optional().orElseThrow(() -> new NotFoundException("remittance " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<ZenginBatch> batches() {
        return jdbc.sql("SELECT * FROM zengin_batches ORDER BY id DESC LIMIT 100").query(BATCH).list();
    }

    @Transactional(readOnly = true)
    public ZenginBatch batch(long id) {
        return jdbc.sql("SELECT * FROM zengin_batches WHERE id = :id").param("id", id).query(BATCH)
                .optional().orElseThrow(() -> new NotFoundException("zengin batch " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> banks() {
        return jdbc.sql("SELECT bank_code, name, name_kana FROM banks ORDER BY bank_code").query().listOfRows();
    }

    // ================================================================== helpers

    static final String GL_CLEARING = "1100";
    static final String GL_UNSETTLED_DOMESTIC = "2400";
    static final String GL_SUSPENSE = "2500";
    static final String GL_FEE_INCOME = "4200";

    private void validateOutgoing(OutgoingRequest r) {
        if (r.amountMinor() <= 0) {
            throw new InvalidRequestException("amountMinor must be positive");
        }
        if (r.bankCode() == null || !r.bankCode().matches("[0-9]{4}") || OUR_BANK_CODE.equals(r.bankCode())) {
            throw new InvalidRequestException("bankCode must be another bank's 4-digit code (use transfers for our bank)");
        }
        if (jdbc.sql("SELECT count(*) FROM banks WHERE bank_code = :b").param("b", r.bankCode()).query(Integer.class).single() == 0) {
            throw new NotFoundException("bank " + r.bankCode() + " not found");
        }
        if (r.branchCode() == null || !r.branchCode().matches("[0-9]{3}")) {
            throw new InvalidRequestException("branchCode must be 3 digits");
        }
        if (r.destAccountType() == null || !r.destAccountType().matches("[124]")) {
            throw new InvalidRequestException("destAccountType must be 1 (普通), 2 (当座) or 4 (貯蓄)");
        }
        if (r.destAccountNo() == null || !r.destAccountNo().matches("[0-9]{7}")) {
            throw new InvalidRequestException("destAccountNo must be 7 digits");
        }
    }

    private void validateIncoming(IncomingItem in) {
        if (in.amountMinor() <= 0 || in.bankCode() == null || !in.bankCode().matches("[0-9]{4}")
                || in.externalRef() == null || !in.externalRef().matches("[A-Za-z0-9-]{1,40}")
                || in.accountNumber() == null || in.nameKana() == null || in.nameKana().isBlank()) {
            throw new InvalidRequestException("invalid incoming item: " + in);
        }
        if (jdbc.sql("SELECT count(*) FROM banks WHERE bank_code = :b").param("b", in.bankCode()).query(Integer.class).single() == 0) {
            throw new InvalidRequestException("unknown sending bank " + in.bankCode());
        }
    }

    private Account account(long id) {
        return accounts.findById(id).orElseThrow(() -> new NotFoundException("account " + id + " not found"));
    }

    private Optional<Account> accountByNumber(String number) {
        return jdbc.sql("SELECT id FROM accounts WHERE account_number = :n").param("n", number).query(Long.class)
                .optional().flatMap(accounts::findById);
    }

    private static Account requireOpen(Account a) {
        if (a.status() == com.example.bankcore.account.AccountStatus.CLOSED) {
            throw new BusinessConflictException("account " + a.id() + " is closed");
        }
        return a;
    }

    private DomesticRemittance lockRemittance(long id) {
        return jdbc.sql("SELECT * FROM domestic_remittances WHERE id = :id FOR NO KEY UPDATE").param("id", id)
                .query(REMITTANCE).single();
    }

    private DomesticRemittance remittanceByTransaction(long txId) {
        return jdbc.sql("SELECT * FROM domestic_remittances WHERE transaction_id = :tx").param("tx", txId)
                .query(REMITTANCE).optional().orElse(null);
    }

    private void setRemittanceStatus(long id, String status, String note) {
        jdbc.sql("UPDATE domestic_remittances SET status = :s, note = :n WHERE id = :id")
                .param("s", status).param("n", clip(note)).param("id", id).update();
    }

    private Mandate mandate(long id) {
        return jdbc.sql("SELECT * FROM debit_mandates WHERE id = :id").param("id", id).query(MANDATE).optional()
                .orElseThrow(() -> new NotFoundException("mandate " + id + " not found"));
    }

    static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new InvalidRequestException(name + " is required (max " + max + " characters)");
        }
        return value.trim();
    }

    private static String clip(String s) {
        return s == null || s.length() <= 200 ? s : s.substring(0, 200);
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
