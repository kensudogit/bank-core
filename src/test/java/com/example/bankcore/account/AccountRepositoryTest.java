package com.example.bankcore.account;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 口座リポジトリ：採番・照会・ロック・残高の一括更新。 */
class AccountRepositoryTest extends AbstractIntegrationTest {

    @Autowired AccountRepository repo;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void insertAndFind() {
        long id = repo.insert("Alice", "JPY", 100);
        Account a = repo.findById(id).orElseThrow();
        assertThat(a.ownerName()).isEqualTo("Alice");
        assertThat(a.overdraftLimitMinor()).isEqualTo(100);
        assertThat(a.createdAt()).isNotNull();
        assertThat(repo.exists(id)).isTrue();
        assertThat(repo.exists(id + 1000)).isFalse();
        assertThat(repo.findById(id + 1000)).isEmpty();
    }

    @Test
    void countExistingIgnoresUnknownIds() {
        long a = repo.insert("A", "JPY", 0);
        long b = repo.insert("B", "JPY", 0);
        assertThat(repo.countExisting(List.of(a, b, 999_999L))).isEqualTo(2);
    }

    @Test
    void lockAllInIdOrderReturnsAccountsSortedById() {
        long a = repo.insert("A", "JPY", 0);
        long b = repo.insert("B", "JPY", 0);
        long c = repo.insert("C", "JPY", 0);
        Map<Long, Account> locked = new TransactionTemplate(txManager)
                .execute(s -> repo.lockAllInIdOrder(List.of(c, a, b)));
        assertThat(locked.keySet()).containsExactly(a, b, c);
    }

    @Test
    void lockByIdReturnsTheRow() {
        long a = repo.insert("A", "JPY", 0);
        Optional<Account> locked = new TransactionTemplate(txManager).execute(s -> repo.lockById(a));
        assertThat(locked).isPresent();
    }

    @Test
    void applyDeltasUpdatesBalancesAndVersions() {
        long a = repo.insert("A", "JPY", 0);
        long b = repo.insert("B", "JPY", 0);
        repo.applyDeltas(Map.of(a, 500L, b, 300L));
        assertThat(repo.findById(a).orElseThrow().postedBalanceMinor()).isEqualTo(500);
        assertThat(repo.findById(b).orElseThrow().postedBalanceMinor()).isEqualTo(300);
        assertThat(repo.findById(a).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void applyDeltasFailsWhenAnAccountIsMissing() {
        long a = repo.insert("A", "JPY", 0);
        assertThatThrownBy(() -> repo.applyDeltas(Map.of(a, 1L, 999_999L, 1L))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateStatusPersists() {
        long a = repo.insert("A", "JPY", 0);
        repo.updateStatus(a, AccountStatus.FROZEN);
        assertThat(repo.findById(a).orElseThrow().status()).isEqualTo(AccountStatus.FROZEN);
    }
}
