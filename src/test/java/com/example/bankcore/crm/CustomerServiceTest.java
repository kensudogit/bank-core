package com.example.bankcore.crm;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.crm.CustomerService.Customer;
import com.example.bankcore.crm.CustomerService.NewCustomer;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 顧客管理：登録とセグメント判定・KYC・適合性・AML リスク・口座の紐づけ・検索・接触履歴・営業案件。 */
class CustomerServiceTest extends AbstractIntegrationTest {

    @Autowired CustomerService customers;

    private Customer individual(String name, Long income) {
        return customers.register(new NewCustomer("INDIVIDUAL", name, null, null, null, income, null, null, "rm1"));
    }

    @Test
    void segmentsByIncomeAndType() {
        assertThat(individual("A", null).segment()).isEqualTo("MASS");
        assertThat(individual("B", 9_999_999L).segment()).isEqualTo("MASS");
        assertThat(individual("C", 10_000_000L).segment()).isEqualTo("AFFLUENT");
        assertThat(individual("D", 30_000_000L).segment()).isEqualTo("PRIVATE");
        assertThat(customers.register(new NewCustomer("CORPORATE", "E社", null, null, null, 99_000_000L, null, null, null))
                .segment()).isEqualTo("CORPORATE");
    }

    @Test
    void registrationDefaultsAndValidation() {
        Customer c = individual("山田", null);
        assertThat(c.customerNumber()).startsWith("C");
        assertThat(c.riskTolerance()).isEqualTo(1);
        assertThat(c.investmentExperienceYears()).isZero();
        assertThat(c.kycStatus()).isEqualTo("PENDING");
        assertThatThrownBy(() -> customers.register(new NewCustomer("ROBOT", "X", null, null, null, null, null, null, null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> customers.register(new NewCustomer("INDIVIDUAL", "X", null, null, null, null, 6, null,
                null))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> customers.register(new NewCustomer("INDIVIDUAL", " ", null, null, null, null, null, null,
                null))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void kycIsDecidedOnce() {
        Customer c = individual("A", null);
        assertThat(customers.decideKyc(c.id(), false).kycStatus()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> customers.decideKyc(c.id(), true)).isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void suitabilityAndAmlRiskUpdates() {
        Customer c = individual("A", null);
        Customer s = customers.updateSuitability(c.id(), 4, 7);
        assertThat(s.riskTolerance()).isEqualTo(4);
        assertThat(s.investmentExperienceYears()).isEqualTo(7);
        assertThatThrownBy(() -> customers.updateSuitability(c.id(), 0, 1)).isInstanceOf(InvalidRequestException.class);
        assertThat(customers.updateAmlRisk(c.id(), "HIGH").amlRisk()).isEqualTo("HIGH");
        assertThatThrownBy(() -> customers.updateAmlRisk(c.id(), "EXTREME")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void linkingAccountsAndOwnerLookup() {
        Customer c = individual("A", null);
        Account a = openAccount("A", 0);
        assertThat(customers.ownerOf(a.id())).isNull();
        customers.linkAccount(c.id(), a.id());
        customers.linkAccount(c.id(), a.id());   // 同じ顧客への再紐づけは問題なし
        assertThat(customers.ownerOf(a.id()).id()).isEqualTo(c.id());
        Customer other = individual("B", null);
        assertThatThrownBy(() -> customers.linkAccount(other.id(), a.id())).isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> customers.linkAccount(c.id(), 999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void searchMatchesNameKanaAndNumberAndEscapesWildcards() {
        Customer c = customers.register(new NewCustomer("INDIVIDUAL", "佐藤 花子", "サトウ ハナコ", LocalDate.of(1990, 1, 1),
                null, null, null, null, null));
        individual("鈴木", null);
        assertThat(customers.search("佐藤")).extracting(Customer::id).containsExactly(c.id());
        assertThat(customers.search("ハナコ")).extracting(Customer::id).containsExactly(c.id());
        assertThat(customers.search(c.customerNumber())).extracting(Customer::id).containsExactly(c.id());
        assertThat(customers.search("%")).isEmpty();
        assertThat(customers.search(null)).hasSize(2);
        assertThatThrownBy(() -> customers.get(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void interactionsAndOpportunityPipeline() {
        Customer c = individual("A", null);
        customers.recordInteraction(c.id(), "PHONE", "INQUIRY", "金利の問い合わせ", "rm1");
        assertThat(customers.interactions(c.id())).singleElement()
                .satisfies(i -> assertThat(i.note()).isEqualTo("金利の問い合わせ"));

        var opp = customers.createOpportunity(c.id(), "LOAN", "住宅ローン", 30_000_000L, "rm1", LocalDate.of(2030, 6, 1));
        assertThat(opp.stage()).isEqualTo("LEAD");
        assertThatThrownBy(() -> customers.moveOpportunity(opp.id(), "UNKNOWN")).isInstanceOf(InvalidRequestException.class);
        customers.moveOpportunity(opp.id(), "NEGOTIATING");
        assertThat(customers.pipeline("rm1")).hasSize(1);
        assertThat(customers.pipeline("rm2")).isEmpty();
        assertThat(customers.moveOpportunity(opp.id(), "LOST").stage()).isEqualTo("LOST");
        assertThatThrownBy(() -> customers.moveOpportunity(opp.id(), "WON")).isInstanceOf(BusinessConflictException.class);
    }
}
