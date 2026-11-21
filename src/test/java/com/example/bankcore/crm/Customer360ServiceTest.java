package com.example.bankcore.crm;

import com.example.bankcore.account.Account;
import com.example.bankcore.cards.CardService;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.crm.CustomerService.Customer;
import com.example.bankcore.crm.CustomerService.NewCustomer;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 顧客 360°：顧客に紐づく口座・カード・接触履歴・案件をまとめ、資産を合計する。 */
class Customer360ServiceTest extends AbstractIntegrationTest {

    @Autowired CustomerService customers;
    @Autowired Customer360Service customer360;
    @Autowired CardService cards;

    @Test
    void emptyCustomer() {
        Customer c = customers.register(new NewCustomer("INDIVIDUAL", "A", null, null, null, null, null, null, null));
        Customer360Service.Customer360 v = customer360.view(c.id());
        assertThat(v.customer().id()).isEqualTo(c.id());
        assertThat(v.accounts()).isEmpty();
        assertThat(v.totalAssetsMinor()).isZero();
        assertThat(v.loanOutstandingMinor()).isZero();
    }

    @Test
    void aggregatesOnlyThisCustomersJpyDepositsAndOpenOpportunities() {
        Customer c = customers.register(new NewCustomer("INDIVIDUAL", "A", null, null, null, null, null, null, "rm1"));
        Account jpy = openAccount("A", 400_000);
        Account usd = openAccount("A", "USD", 10_000);
        Account notLinked = openAccount("A", 999_000);
        customers.linkAccount(c.id(), jpy.id());
        customers.linkAccount(c.id(), usd.id());
        cards.issueCard(jpy.id(), 100_000);
        customers.createOpportunity(c.id(), "FUND", "提案", 100_000L, "rm1", LocalDate.of(2030, 5, 1));

        Customer360Service.Customer360 v = customer360.view(c.id());
        assertThat(v.accounts()).hasSize(2);
        assertThat(v.depositsJpyMinor()).isEqualTo(400_000);   // 外貨預金・紐づけていない口座は含めない
        assertThat(v.cards()).hasSize(1);
        assertThat(v.openOpportunities()).hasSize(1);
        assertThat(v.totalAssetsMinor()).isEqualTo(v.depositsJpyMinor() + v.fundValueMinor() + v.trustPrincipalMinor());
        assertThat(notLinked.id()).isPositive();
    }

    @Test
    void unknownCustomerIsNotFound() {
        assertThatThrownBy(() -> customer360.view(999_999)).isInstanceOf(NotFoundException.class);
    }
}
