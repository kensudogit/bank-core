package com.example.bankcore.account;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 口座レコード：支払可能額の計算と状態判定。 */
class AccountTest {

    private static Account account(long posted, long held, long overdraft, AccountStatus status) {
        return new Account(1, "0000001", "Alice", "JPY", posted, held, overdraft, status, 0,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    void availableBalanceIsPostedMinusHeldPlusOverdraft() {
        assertThat(account(10_000, 3_000, 5_000, AccountStatus.ACTIVE).availableBalanceMinor()).isEqualTo(12_000);
        assertThat(account(0, 0, 0, AccountStatus.ACTIVE).availableBalanceMinor()).isZero();
        assertThat(account(1_000, 4_000, 0, AccountStatus.ACTIVE).availableBalanceMinor()).isEqualTo(-3_000);
    }

    @Test
    void availableBalanceDetectsOverflow() {
        assertThatThrownBy(() -> account(Long.MAX_VALUE, 0, 1, AccountStatus.ACTIVE).availableBalanceMinor())
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void onlyActiveAccountsAreActive() {
        assertThat(account(0, 0, 0, AccountStatus.ACTIVE).isActive()).isTrue();
        assertThat(account(0, 0, 0, AccountStatus.FROZEN).isActive()).isFalse();
        assertThat(account(0, 0, 0, AccountStatus.CLOSED).isActive()).isFalse();
    }
}
