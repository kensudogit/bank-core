package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountStatus;
import com.example.bankcore.common.Exceptions.IdempotencyConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 資金移動サービス：入金・出金・振込・取消と、冪等性・拒否・Outbox。 */
class MoneyMovementServiceTest extends AbstractIntegrationTest {

    @Test
    void depositWithdrawAndTransferMoveBalances() {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        var w = movements.execute(MovementCommand.withdrawal(key(), a.id(), 200, "JPY", null, null));
        var t = movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 300, "JPY", null, null));
        assertThat(w.transaction().status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(t.replayed()).isFalse();
        assertThat(balanceOf(a.id())).isEqualTo(500);
        assertThat(balanceOf(b.id())).isEqualTo(300);
        assertThat(count("outbox_events")).isGreaterThanOrEqualTo(3);
    }

    @Test
    void rejectionIsRecordedAndChangesNothing() {
        Account a = openAccount("A", 100);
        Account b = openAccount("B", 0);
        var r = movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 101, "JPY", null, null));
        assertThat(r.transaction().status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(r.transaction().rejectReason()).isEqualTo(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(balanceOf(a.id())).isEqualTo(100);

        accountService.changeStatus(b.id(), AccountStatus.FROZEN);
        var frozen = movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 10, "JPY", null, null));
        assertThat(frozen.transaction().rejectReason()).isEqualTo(RejectReason.TARGET_ACCOUNT_NOT_ACTIVE);
    }

    @Test
    void replayAndConflictOnSameKey() {
        Account a = openAccount("A", 1_000);
        var first = movements.execute(MovementCommand.withdrawal("same", a.id(), 100, "JPY", null, null));
        var again = movements.execute(MovementCommand.withdrawal("same", a.id(), 100, "JPY", null, null));
        assertThat(again.replayed()).isTrue();
        assertThat(again.transaction().id()).isEqualTo(first.transaction().id());
        assertThat(balanceOf(a.id())).isEqualTo(900);
        assertThatThrownBy(() -> movements.execute(MovementCommand.withdrawal("same", a.id(), 101, "JPY", null, null)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void invalidCommandAndUnknownAccount() {
        assertThatThrownBy(() -> movements.execute(MovementCommand.deposit(key(), 1, 0, "JPY", null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> movements.execute(MovementCommand.deposit(key(), 999_999, 1, "JPY", null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void reverseRestoresBalancesAndIsLinked() {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        var t = movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 400, "JPY", null, null)).transaction();
        var rev = movements.reverse(key(), t.id(), "corr").transaction();
        assertThat(rev.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(rev.reversesTransactionId()).isEqualTo(t.id());
        assertThat(balanceOf(a.id())).isEqualTo(1_000);
        assertThat(balanceOf(b.id())).isZero();
        assertThat(movements.findReversalOf(t.id())).map(MoneyTransaction::id).contains(rev.id());
    }

    @Test
    void lookups() {
        Account a = openAccount("A", 0);
        var d = movements.execute(MovementCommand.deposit("lookup-key", a.id(), 5, "JPY", null, null)).transaction();
        assertThat(movements.get(d.id()).idempotencyKey()).isEqualTo("lookup-key");
        assertThat(movements.findByIdempotencyKey("lookup-key")).isPresent();
        assertThat(movements.findByIdempotencyKey("nope")).isEmpty();
        assertThatThrownBy(() -> movements.get(999_999)).isInstanceOf(NotFoundException.class);
    }
}
