package com.example.bankcore.transaction;

import com.example.bankcore.account.Account;
import com.example.bankcore.account.AccountStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 拒否理由：業務ルールの各判定がそれぞれの理由に対応していること。 */
class RejectReasonTest {

    private static Account acct(String currency, long balance, AccountStatus status) {
        return new Account(1, "0000001", "X", currency, balance, 0, 0, status, 0, OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void listsAllReasons() {
        assertThat(RejectReason.values()).extracting(Enum::name).containsExactly(
                "SOURCE_ACCOUNT_NOT_ACTIVE", "TARGET_ACCOUNT_NOT_ACTIVE", "CURRENCY_MISMATCH", "INSUFFICIENT_FUNDS",
                "AMOUNT_EXCEEDS_LOAN_BALANCE");
    }

    @Test
    void eachRuleProducesItsReason() {
        Account ok = acct("JPY", 1_000, AccountStatus.ACTIVE);
        assertThat(TransactionRules.check(acct("JPY", 1_000, AccountStatus.FROZEN), ok, 1, "JPY"))
                .contains(RejectReason.SOURCE_ACCOUNT_NOT_ACTIVE);
        assertThat(TransactionRules.check(ok, acct("JPY", 0, AccountStatus.CLOSED), 1, "JPY"))
                .contains(RejectReason.TARGET_ACCOUNT_NOT_ACTIVE);
        assertThat(TransactionRules.check(ok, acct("USD", 0, AccountStatus.ACTIVE), 1, "JPY"))
                .contains(RejectReason.CURRENCY_MISMATCH);
        assertThat(TransactionRules.check(ok, null, 1_001, "JPY")).contains(RejectReason.INSUFFICIENT_FUNDS);
        assertThat(TransactionRules.check(ok, null, 1_000, "JPY")).isEmpty();
    }
}
