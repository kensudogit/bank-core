package com.example.bankcore.compliance;

import com.example.bankcore.account.Account;
import com.example.bankcore.cards.CardService;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.crm.CustomerService;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AML 監視：ルールごとの発報・小口取引の除外・続きからの検査・案件の状態遷移。 */
class AmlServiceTest extends AbstractIntegrationTest {

    @Autowired AmlService aml;
    @Autowired CardService cards;
    @Autowired CustomerService customers;

    private AmlService.Alert only(String rule) {
        return aml.alerts(null).stream().filter(a -> a.rule().equals(rule)).findFirst().orElseThrow();
    }

    @Test
    void smallTransactionsRaiseNothingButAdvanceTheCursor() {
        Account a = openAccount("A", 400_000);
        Account b = openAccount("B", 0);
        movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 300_000, "JPY", null, null));
        AmlService.ScanReport r = aml.scan();
        assertThat(r.transactionsScanned()).isEqualTo(2);
        assertThat(r.alertsCreated()).isZero();
        assertThat(aml.scan().transactionsScanned()).isZero();   // 続きから（新しい取引なし）
    }

    @Test
    void largeCashDepositAndWithdrawalAreFlaggedWithCustomer() {
        var c = customers.register(new CustomerService.NewCustomer("INDIVIDUAL", "A", null, null, null, null, null, null,
                null));
        Account a = openAccount("A", 0);
        customers.linkAccount(c.id(), a.id());
        movements.execute(MovementCommand.deposit(key(), a.id(), 2_000_000, "JPY", null, null));
        movements.execute(MovementCommand.withdrawal(key(), a.id(), 500_000, "JPY", null, null));   // 大口未満・通過口座にも当たらない
        assertThat(aml.scan().alertsCreated()).isEqualTo(1);
        AmlService.Alert alert = only("LARGE_CASH");
        assertThat(alert.customerId()).isEqualTo(c.id());
        assertThat(alert.accountId()).isEqualTo(a.id());
        assertThat(alert.status()).isEqualTo("OPEN");
        assertThat(alert.detail()).contains("入金");
    }

    @Test
    void structuringNeedsThreeDepositsTotallingTheThreshold() {
        Account a = openAccount("A", 0);
        movements.execute(MovementCommand.deposit(key(), a.id(), 600_000, "JPY", null, null));
        movements.execute(MovementCommand.deposit(key(), a.id(), 600_000, "JPY", null, null));
        aml.scan();
        assertThat(aml.alerts(null)).isEmpty();   // 2 回・120 万円ではまだ該当しない
        movements.execute(MovementCommand.deposit(key(), a.id(), 900_000, "JPY", null, null));
        assertThat(aml.scan().alertsCreated()).isEqualTo(1);
        assertThat(only("STRUCTURING").detail()).contains("3 回");
    }

    @Test
    void cardDeclinesAreFlagged() {
        Account holder = openAccount("H", 100);
        CardService.Merchant m = cards.registerMerchant("EC", "5999", openAccount("Shop", 0).id(), 300);
        CardService.Card card = cards.issueCard(holder.id(), 1_000_000);
        for (int i = 0; i < 3; i++) {
            cards.authorize(card.token(), m.id(), 50_000, "D-" + i);   // 残高不足で否決
        }
        AmlService.ScanReport r = aml.scan();
        assertThat(r.authorizationsScanned()).isEqualTo(3);
        assertThat(only("CARD_DECLINES").accountId()).isEqualTo(holder.id());
        assertThat(aml.alerts(null)).extracting(AmlService.Alert::rule).doesNotContain("CARD_VELOCITY");
    }

    @Test
    void caseLifecycle() {
        Account a = openAccount("A", 0);
        movements.execute(MovementCommand.deposit(key(), a.id(), 3_000_000, "JPY", null, null));
        aml.scan();
        long id = only("LARGE_CASH").id();
        assertThatThrownBy(() -> aml.investigate(id, " ")).isInstanceOf(InvalidRequestException.class);
        assertThat(aml.investigate(id, "compliance1").status()).isEqualTo("INVESTIGATING");
        assertThatThrownBy(() -> aml.investigate(id, "compliance2")).isInstanceOf(BusinessConflictException.class);
        AmlService.Alert closed = aml.close(id, "compliance1", "給与振込の原資を確認");
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.assignedTo()).isEqualTo("compliance1");
        assertThatThrownBy(() -> aml.report(id, "c", "x", false)).isInstanceOf(BusinessConflictException.class);
        assertThat(aml.alerts("CLOSED")).hasSize(1);
        assertThat(aml.alerts("OPEN")).isEmpty();
        assertThatThrownBy(() -> aml.get(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reportWithoutFreezeKeepsAccountActive() {
        Account a = openAccount("A", 0);
        movements.execute(MovementCommand.deposit(key(), a.id(), 3_000_000, "JPY", null, null));
        aml.scan();
        AmlService.Alert r = aml.report(only("LARGE_CASH").id(), "compliance1", "届出", false);
        assertThat(r.status()).isEqualTo("REPORTED");
        assertThat(accountService.get(a.id()).isActive()).isTrue();
        assertThat(count("suspicious_transaction_reports")).isEqualTo(1);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
