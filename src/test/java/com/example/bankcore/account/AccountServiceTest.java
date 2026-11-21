package com.example.bankcore.account;

import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 口座サービス：開設・照会・一覧・状態変更の業務ルール。 */
class AccountServiceTest extends AbstractIntegrationTest {

    @Test
    void openCreatesActiveZeroBalanceAccountWithSevenDigitNumber() {
        Account a = accountService.open("Alice", "JPY", 50_000);
        assertThat(a.id()).isPositive();
        assertThat(a.accountNumber()).matches("\\d{7}");
        assertThat(a.ownerName()).isEqualTo("Alice");
        assertThat(a.currency()).isEqualTo("JPY");
        assertThat(a.postedBalanceMinor()).isZero();
        assertThat(a.overdraftLimitMinor()).isEqualTo(50_000);
        assertThat(a.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void openRejectsNegativeOverdraft() {
        assertThatThrownBy(() -> accountService.open("Alice", "JPY", -1)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void accountNumbersAreUnique() {
        Account a = accountService.open("A", "JPY", 0);
        Account b = accountService.open("B", "JPY", 0);
        assertThat(a.accountNumber()).isNotEqualTo(b.accountNumber());
    }

    @Test
    void getUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> accountService.get(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listReturnsAccountsInIdOrder() {
        Account a = accountService.open("A", "JPY", 0);
        Account b = accountService.open("B", "USD", 0);
        assertThat(accountService.list()).extracting(Account::id).containsExactly(a.id(), b.id());
    }

    @Test
    void freezeAndReactivate() {
        Account a = accountService.open("A", "JPY", 0);
        assertThat(accountService.changeStatus(a.id(), AccountStatus.FROZEN).status()).isEqualTo(AccountStatus.FROZEN);
        Account back = accountService.changeStatus(a.id(), AccountStatus.ACTIVE);
        assertThat(back.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(back.version()).isGreaterThan(a.version());
    }

    @Test
    void closeRequiresZeroBalanceAndIsFinal() {
        Account funded = openAccount("Funded", 1_000);
        assertThatThrownBy(() -> accountService.changeStatus(funded.id(), AccountStatus.CLOSED))
                .isInstanceOf(InvalidRequestException.class);

        Account empty = accountService.open("Empty", "JPY", 0);
        assertThat(accountService.changeStatus(empty.id(), AccountStatus.CLOSED).status()).isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(() -> accountService.changeStatus(empty.id(), AccountStatus.ACTIVE))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void changeStatusOfUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> accountService.changeStatus(999_999, AccountStatus.FROZEN))
                .isInstanceOf(NotFoundException.class);
    }
}
