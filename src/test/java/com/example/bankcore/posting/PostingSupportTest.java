package com.example.bankcore.posting;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.ledger.GlCode;
import com.example.bankcore.ledger.JournalLine;
import com.example.bankcore.ledger.ReconciliationService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MoneyTransaction;
import com.example.bankcore.transaction.RejectReason;
import com.example.bankcore.transaction.TransactionStatus;
import com.example.bankcore.transaction.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 業務サービス共通の記帳手順：予約 → ロック → 記帳（残高は仕訳から更新）→ 確定 → Outbox。 */
class PostingSupportTest extends AbstractIntegrationTest {

    @Autowired PostingSupport posting;
    @Autowired ReconciliationService reconciliation;

    /** 口座から現金で払い出す業務取引（電子マネーのチャージを例に）。 */
    private MoneyTransaction payOut(String key, long accountId, long amount) {
        return posting.inTransaction(() -> {
            PostingSupport.Reservation r = posting.reserve(TransactionType.WALLET_TOPUP, key,
                    PostingSupport.hash("TOPUP", accountId, amount), accountId, null, amount, "JPY", "チャージ", null);
            if (r.isReplay()) {
                return r.replayed();
            }
            Account a = posting.lockAccounts(accountId).get(accountId);
            if (a.availableBalanceMinor() < amount) {
                return posting.reject(r.transactionId(), RejectReason.INSUFFICIENT_FUNDS);
            }
            posting.post(r.transactionId(), "チャージ", List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, accountId, amount, "JPY"),
                    JournalLine.credit(GlCode.CASH, null, amount, "JPY")));
            posting.event("Account", accountId, "WalletToppedUp", Map.of("amount", amount));
            return posting.complete(r.transactionId());
        });
    }

    @Test
    void postUpdatesBalanceFromDepositLinesAndBooksStayBalanced() {
        Account a = openAccount("A", 1_000);
        MoneyTransaction t = payOut("topup-1", a.id(), 300);
        assertThat(t.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(a.id())).isEqualTo(700);
        assertThat(count("outbox_events WHERE event_type = 'WalletToppedUp'")).isEqualTo(1);
        assertThat(reconciliation.run().ok()).isTrue();
    }

    @Test
    void replayReturnsTheRecordedTransactionAndDifferentContentConflicts() {
        Account a = openAccount("A", 1_000);
        MoneyTransaction first = payOut("topup-2", a.id(), 300);
        MoneyTransaction again = payOut("topup-2", a.id(), 300);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(balanceOf(a.id())).isEqualTo(700);
        assertThatThrownBy(() -> payOut("topup-2", a.id(), 301)).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void rejectLeavesBalanceUntouched() {
        Account a = openAccount("A", 100);
        MoneyTransaction t = payOut(key(), a.id(), 101);
        assertThat(t.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(t.rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(balanceOf(a.id())).isEqualTo(100);
    }

    @Test
    void failureInsideTransactionRollsEverythingBack() {
        Account a = openAccount("A", 1_000);
        assertThatThrownBy(() -> posting.inTransaction(() -> {
            var r = posting.reserve(TransactionType.WALLET_TOPUP, "rollback", "h", a.id(), null, 1, "JPY", null, null);
            posting.post(r.transactionId(), "x", List.of(
                    JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, a.id(), 1, "JPY"),
                    JournalLine.credit(GlCode.CASH, null, 1, "JPY")));
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(balanceOf(a.id())).isEqualTo(1_000);
        assertThat(count("transactions WHERE idempotency_key = 'rollback'")).isZero();
    }

    @Test
    void lockAccountsDeduplicatesAndSorts() {
        Account a = openAccount("A", 0);
        Account b = openAccount("B", 0);
        Map<Long, Account> locked = posting.inTransaction(() -> posting.lockAccounts(b.id(), a.id(), b.id()));
        assertThat(locked.keySet()).containsExactly(a.id(), b.id());
    }

    @Test
    void keyValidationAndHash() {
        assertThatThrownBy(() -> PostingSupport.requireKey("bad key")).isInstanceOf(InvalidRequestException.class);
        assertThat(PostingSupport.hash("A", 1)).isEqualTo(PostingSupport.hash("A", 1)).hasSize(64);
        assertThat(PostingSupport.hash("A", 1)).isNotEqualTo(PostingSupport.hash("A", 2));
        assertThat(new PostingSupport.Reservation(1L, null).isReplay()).isFalse();
    }
}
