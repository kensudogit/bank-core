package com.example.bankcore.ledger;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 照合：取引後は一致、口座残高を直接書き換えると不一致を検出し、試算表は貸借が合う。 */
class ReconciliationServiceTest extends AbstractIntegrationTest {

    @Autowired ReconciliationService reconciliation;

    @Test
    void emptyLedgerIsConsistent() {
        ReconciliationService.Report r = reconciliation.run();
        assertThat(r.ok()).isTrue();
        assertThat(r.unbalancedJournals()).isEmpty();
        assertThat(r.accountMismatches()).isEmpty();
        assertThat(r.loanMismatches()).isEmpty();
        assertThat(r.subledgerMismatches()).isEmpty();
    }

    @Test
    void consistentAfterMovementsAndTrialBalanceBalances() {
        Account a = openAccount("A", 10_000);
        Account b = openAccount("B", 0);
        movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 2_500, "JPY", null, null));
        movements.execute(MovementCommand.withdrawal(key(), b.id(), 500, "JPY", null, null));

        ReconciliationService.Report r = reconciliation.run();
        assertThat(r.ok()).isTrue();
        long debit = r.trialBalance().stream().mapToLong(ReconciliationService.TrialBalanceRow::debitMinor).sum();
        long credit = r.trialBalance().stream().mapToLong(ReconciliationService.TrialBalanceRow::creditMinor).sum();
        assertThat(debit).isEqualTo(credit).isPositive();
    }

    @Test
    void detectsBalanceTamperingPerAccount() {
        Account a = openAccount("A", 1_000);
        jdbc.sql("UPDATE accounts SET posted_balance_minor = posted_balance_minor + 7 WHERE id = :id")
                .param("id", a.id()).update();

        ReconciliationService.Report r = reconciliation.run();
        assertThat(r.ok()).isFalse();
        assertThat(r.accountMismatches()).singleElement().satisfies(m -> {
            assertThat(m.accountId()).isEqualTo(a.id());
            assertThat(m.accountBalanceMinor()).isEqualTo(1_007);
            assertThat(m.ledgerBalanceMinor()).isEqualTo(1_000);
        });
    }
}
