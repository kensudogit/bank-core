package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountStatus;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB を使わない単体テスト：業務ルールと入力検証。 */
class TransactionRulesTest {

    private static Account account(long id, long posted, long held, long overdraft, AccountStatus status, String ccy) {
        return new Account(id, "000000" + id, "owner", ccy, posted, held, overdraft, status, 0, null, null);
    }

    private static Account active(long id, long posted) {
        return account(id, posted, 0, 0, AccountStatus.ACTIVE, "JPY");
    }

    @Test
    void allowsTransferWithinAvailableBalance() {
        assertThat(TransactionRules.check(active(1, 1000), active(2, 0), 1000, "JPY")).isEmpty();
    }

    @Test
    void rejectsInsufficientFunds() {
        assertThat(TransactionRules.check(active(1, 999), active(2, 0), 1000, "JPY"))
                .contains(RejectReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void availableBalanceSubtractsHoldsAndAddsOverdraft() {
        Account held = account(1, 1000, 300, 0, AccountStatus.ACTIVE, "JPY");
        assertThat(held.availableBalanceMinor()).isEqualTo(700);
        assertThat(TransactionRules.check(held, active(2, 0), 701, "JPY")).contains(RejectReason.INSUFFICIENT_FUNDS);

        Account overdraft = account(1, 0, 0, 500, AccountStatus.ACTIVE, "JPY");
        assertThat(TransactionRules.check(overdraft, active(2, 0), 500, "JPY")).isEmpty();
    }

    @Test
    void rejectsInactiveAccounts() {
        Account frozen = account(1, 1000, 0, 0, AccountStatus.FROZEN, "JPY");
        Account closed = account(2, 0, 0, 0, AccountStatus.CLOSED, "JPY");
        assertThat(TransactionRules.check(frozen, active(2, 0), 1, "JPY")).contains(RejectReason.SOURCE_ACCOUNT_NOT_ACTIVE);
        assertThat(TransactionRules.check(active(1, 1000), closed, 1, "JPY")).contains(RejectReason.TARGET_ACCOUNT_NOT_ACTIVE);
    }

    @Test
    void rejectsCurrencyMismatch() {
        Account usd = account(2, 0, 0, 0, AccountStatus.ACTIVE, "USD");
        assertThat(TransactionRules.check(active(1, 1000), usd, 1, "JPY")).contains(RejectReason.CURRENCY_MISMATCH);
    }

    @Test
    void depositOnlyChecksTarget() {
        assertThat(TransactionRules.check(null, active(2, 0), 1_000_000, "JPY")).isEmpty();
    }

    @Test
    void validatesCommandShape() {
        assertThatThrownBy(() -> MovementCommand.transfer("k1", 1, 2, 0, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> MovementCommand.transfer("k1", 1, 2, -5, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.transfer("k1", 1, 1, 100, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("differ");
        assertThatThrownBy(() -> MovementCommand.transfer("k1", 1, 2, 100, "jpy", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.transfer("bad key!", 1, 2, 100, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void requestHashIsStableAndSensitiveToContent() {
        MovementCommand a = MovementCommand.transfer("k1", 1, 2, 100, "JPY", "rent", "corr-1");
        MovementCommand sameButOtherCorrelation = MovementCommand.transfer("k1", 1, 2, 100, "JPY", "rent", "corr-2");
        MovementCommand differentAmount = MovementCommand.transfer("k1", 1, 2, 101, "JPY", "rent", "corr-1");

        assertThat(a.requestHash()).hasSize(64).isEqualTo(sameButOtherCorrelation.requestHash());
        assertThat(a.requestHash()).isNotEqualTo(differentAmount.requestHash());
    }
}
