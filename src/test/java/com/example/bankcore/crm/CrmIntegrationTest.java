package com.example.bankcore.crm;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.crm.CustomerService.Customer;
import com.example.bankcore.crm.CustomerService.NewCustomer;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 顧客管理（CRM）：顧客登録・KYC・口座の紐づけ・接触履歴・営業案件・顧客 360°。 */
class CrmIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    CustomerService customers;

    @Autowired
    Customer360Service customer360;

    @Test
    void customerLifecycleAndThreeSixtyView() {
        Customer c = customers.register(new NewCustomer("INDIVIDUAL", "山田 太郎", "ヤマダ タロウ",
                LocalDate.of(1980, 5, 1), "会社員", 12_000_000L, 3, 5, "rm1"));
        assertThat(c.customerNumber()).startsWith("C");
        assertThat(c.segment()).isEqualTo("AFFLUENT");
        assertThat(c.kycStatus()).isEqualTo("PENDING");
        assertThat(customers.decideKyc(c.id(), true).kycStatus()).isEqualTo("VERIFIED");
        assertThatThrownBy(() -> customers.decideKyc(c.id(), false)).isInstanceOf(BusinessConflictException.class);

        Account a1 = openAccount("山田 太郎", 300_000);
        Account a2 = openAccount("山田 太郎", 200_000);
        customers.linkAccount(c.id(), a1.id());
        customers.linkAccount(c.id(), a2.id());
        Customer other = customers.register(new NewCustomer("CORPORATE", "架空商事", null, null, null, null, null, null, null));
        assertThat(other.segment()).isEqualTo("CORPORATE");
        assertThatThrownBy(() -> customers.linkAccount(other.id(), a1.id())).isInstanceOf(BusinessConflictException.class);

        customers.recordInteraction(c.id(), "BRANCH", "PROPOSAL", "投資信託の提案", "rm1");
        var opp = customers.createOpportunity(c.id(), "FUND", "バランス型ファンド", 1_000_000L, "rm1", LocalDate.of(2030, 5, 1));
        customers.moveOpportunity(opp.id(), "PROPOSED");
        assertThat(customers.pipeline("rm1")).singleElement().satisfies(o -> assertThat(o.stage()).isEqualTo("PROPOSED"));
        customers.moveOpportunity(opp.id(), "WON");
        assertThatThrownBy(() -> customers.moveOpportunity(opp.id(), "LOST")).isInstanceOf(BusinessConflictException.class);

        Customer360Service.Customer360 view = customer360.view(c.id());
        assertThat(view.accounts()).hasSize(2);
        assertThat(view.depositsJpyMinor()).isEqualTo(500_000);
        assertThat(view.totalAssetsMinor()).isEqualTo(500_000);
        assertThat(view.interactions()).hasSize(1);
        assertThat(view.openOpportunities()).isEmpty();
        assertThat(customers.search("ヤマダ")).extracting(Customer::id).containsExactly(c.id());
        assertThat(customers.updateSuitability(c.id(), 5, 10).riskTolerance()).isEqualTo(5);
    }
}
