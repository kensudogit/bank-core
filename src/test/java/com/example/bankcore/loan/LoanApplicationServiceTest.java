package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.loan.LoanApplicationService.ApplicationView;
import com.example.bankcore.loan.LoanApplicationService.NewApplication;
import com.example.bankcore.loan.LoanModels.ApplicationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 融資の申込受付：入力検証・担保と保証・審査・決裁（職務分離）。 */
class LoanApplicationServiceTest extends LoanTestSupport {

    private NewApplication req(long account, LoanProduct p, long principal, int term) {
        return new NewApplication(account, p, principal, term, RepaymentMethod.EQUAL_PAYMENT, 8_000_000, 0, null, "teller1");
    }

    @Test
    void applyValidatesProductRanges() {
        Account a = openAccount("A", 0);
        assertThatThrownBy(() -> applications.apply(req(a.id(), LoanProduct.BUSINESS, 999_999, 12)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("principal");
        assertThatThrownBy(() -> applications.apply(req(a.id(), LoanProduct.BUSINESS, 1_000_000, 121)))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("term");
        assertThatThrownBy(() -> applications.apply(req(a.id(), null, 1_000_000, 12)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> applications.apply(new NewApplication(a.id(), LoanProduct.BUSINESS, 1_000_000, 12,
                RepaymentMethod.EQUAL_PAYMENT, -1, 0, null, "teller1"))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> applications.apply(new NewApplication(a.id(), LoanProduct.BUSINESS, 1_000_000, 12,
                RepaymentMethod.EQUAL_PAYMENT, 1, 0, null, " "))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void applyRequiresExistingJpyAccount() {
        assertThatThrownBy(() -> applications.apply(req(999_999, LoanProduct.BUSINESS, 1_000_000, 12)))
                .isInstanceOf(NotFoundException.class);
        Account usd = openAccount("U", "USD", 0);
        assertThatThrownBy(() -> applications.apply(req(usd.id(), LoanProduct.BUSINESS, 1_000_000, 12)))
                .hasMessageContaining("JPY");
    }

    @Test
    void submittedApplicationHasNoScreeningYet() {
        ApplicationView v = submitted(openAccount("A", 0), 1_000_000);
        assertThat(v.application().status()).isEqualTo(ApplicationStatus.SUBMITTED);
        assertThat(v.application().score()).isNull();
        assertThat(v.collaterals()).isEmpty();
        assertThat(v.securedMinor()).isZero();
        assertThat(applications.list()).hasSize(1);
    }

    @Test
    void collateralAndGuaranteeIncreaseSecuredAmountAndRevaluationChangesIt() {
        long id = submitted(openAccount("A", 0), 1_000_000).application().id();
        ApplicationView v = applications.addCollateral(id, CollateralType.DEPOSIT, "定期預金", 500_000, null);
        assertThat(v.securedMinor()).isEqualTo(500_000);
        v = applications.addGuarantee(id, GuaranteeType.GUARANTEE_COMPANY, "保証会社", 300_000);
        assertThat(v.securedMinor()).isEqualTo(800_000);
        v = applications.revalueCollateral(v.collaterals().getFirst().id(), 200_000);
        assertThat(v.securedMinor()).isEqualTo(500_000);

        assertThatThrownBy(() -> applications.addCollateral(id, CollateralType.REAL_ESTATE, "土地", 0, 1))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> applications.addCollateral(id, CollateralType.REAL_ESTATE, "土地", 1, 10))
                .hasMessageContaining("lienRank");
        assertThatThrownBy(() -> applications.addGuarantee(id, GuaranteeType.INDIVIDUAL, "保証人", 0))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> applications.revalueCollateral(999_999, 1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void decisionRequiresScreeningAndADifferentPerson() {
        long id = submitted(openAccount("A", 0), 1_000_000).application().id();
        assertThatThrownBy(() -> applications.decide(id, true, "manager1", null, null))
                .isInstanceOf(BusinessConflictException.class).hasMessageContaining("screened");
        ApplicationView screened = applications.screen(id);
        assertThat(screened.application().status()).isEqualTo(ApplicationStatus.SCREENED);
        assertThat(screened.application().score()).isNotNull();
        assertThat(screened.application().recommendation()).isNotNull();

        assertThatThrownBy(() -> applications.decide(id, true, "teller1", "x", null))
                .isInstanceOf(BusinessConflictException.class);
        assertThatThrownBy(() -> applications.decide(id, true, "manager1", "x", 99_999))
                .isInstanceOf(InvalidRequestException.class);
        ApplicationView approved = applications.decide(id, true, "manager1", "承認", 250);
        assertThat(approved.application().status()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(approved.application().approvedRateBp()).isEqualTo(250);
        assertThat(approved.application().decidedBy()).isEqualTo("manager1");
        // 決裁後は審査し直せない
        assertThatThrownBy(() -> applications.screen(id)).isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void rejectedApplicationIsClosedForChanges() {
        long id = submitted(openAccount("A", 0), 1_000_000).application().id();
        applications.screen(id);
        ApplicationView rejected = applications.decide(id, false, "manager1", "返済能力不足", null);
        assertThat(rejected.application().status()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(rejected.application().approvedRateBp()).isNull();
        assertThatThrownBy(() -> applications.addGuarantee(id, GuaranteeType.INDIVIDUAL, "保証人", 1))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void unknownApplicationIsNotFound() {
        assertThatThrownBy(() -> applications.get(999_999)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> applications.screen(999_999)).isInstanceOf(NotFoundException.class);
    }
}
