package com.example.bankcore.batch;

import com.example.bankcore.batch.DailyClosingService.ClosingReport;
import com.example.bankcore.batch.DailyClosingService.ClosingStatus;
import com.example.bankcore.batch.DailyClosingService.Step;
import com.example.bankcore.common.BusinessCalendar;
import com.example.bankcore.common.Exceptions.BusinessConflictException;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日次締めサービス：利息計算・承認・差戻し・修復記録・ステップのやり直し・照会。
 * （ClosingControlIntegrationTest と同じ設定にしてテスト用の Spring コンテキストを共有する）
 */
@TestPropertySource(properties = "bank.closing.approval-required=true")
class DailyClosingServiceTest extends AbstractIntegrationTest {

    @Autowired DailyClosingService closing;
    @Autowired BusinessCalendar calendar;

    @Test
    void dailyInterestIsTruncatedAndZeroForNonPositive() {
        assertThat(DailyClosingService.dailyInterest(3_650_000, 100)).isEqualTo(100);   // 1% / 365 日
        assertThat(DailyClosingService.dailyInterest(3_649_999, 100)).isEqualTo(99);
        assertThat(DailyClosingService.dailyInterest(0, 100)).isZero();
        assertThat(DailyClosingService.dailyInterest(-1, 100)).isZero();
        assertThat(DailyClosingService.dailyInterest(1_000, 0)).isZero();
    }

    @Test
    void runPostsInterestAndWaitsForApproval() {
        var a = openAccount("A", 3_650_000);
        ClosingReport r = closing.run("op1");
        assertThat(closing.approvalRequired()).isTrue();
        assertThat(r.businessDate()).isEqualTo(INITIAL_BUSINESS_DATE);
        assertThat(r.interestPostedThisRun()).isEqualTo(1);
        assertThat(r.status()).isEqualTo("AWAITING_APPROVAL");
        assertThat(r.steps()).hasSize(Step.values().length);
        assertThat(balanceOf(a.id())).isEqualTo(3_650_100);

        ClosingStatus approved = closing.approve(INITIAL_BUSINESS_DATE, "manager1");
        assertThat(approved.closing().status()).isEqualTo("COMPLETED");
        assertThat(approved.closing().approvedBy()).isEqualTo("manager1");
        assertThat(approved.currentBusinessDate()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
        assertThat(approved.audit()).extracting(DailyClosingService.AuditRecord::action)
                .contains("RUN", "APPROVE", "COMPLETED");
        assertThat(closing.history()).first().satisfies(c -> assertThat(c.status()).isEqualTo("COMPLETED"));
    }

    @Test
    void rejectionRequiresReasonAndKeepsTheDate() {
        openAccount("A", 1_000);
        closing.run("op1");
        assertThatThrownBy(() -> closing.reject(INITIAL_BUSINESS_DATE, "manager1", " "))
                .isInstanceOf(InvalidRequestException.class);
        ClosingStatus s = closing.reject(INITIAL_BUSINESS_DATE, "manager1", "利息の確認が必要");
        assertThat(s.closing().status()).isEqualTo("REJECTED");
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE);
        assertThatThrownBy(() -> closing.approve(INITIAL_BUSINESS_DATE, "manager1"))
                .isInstanceOf(BusinessConflictException.class);
    }

    @Test
    void repairIsAuditedAndStepCanBeReset() {
        openAccount("A", 1_000);
        closing.run("op1");
        ClosingStatus repaired = closing.recordRepair(INITIAL_BUSINESS_DATE, "op2", "残高を修正");
        assertThat(repaired.audit()).anySatisfy(x -> {
            assertThat(x.action()).isEqualTo("REPAIR");
            assertThat(x.actor()).isEqualTo("op2");
        });
        ClosingStatus reset = closing.resetStep(INITIAL_BUSINESS_DATE, Step.AML_SCAN, "op2");
        assertThat(reset.steps()).filteredOn(st -> st.stepName().equals("AML_SCAN"))
                .singleElement().satisfies(st -> assertThat(st.status()).isEqualTo("FAILED"));
    }

    @Test
    void statusOfUnknownDateIsNotFound() {
        assertThatThrownBy(() -> closing.status(INITIAL_BUSINESS_DATE.minusDays(10))).isInstanceOf(NotFoundException.class);
        assertThat(closing.history()).isEmpty();
    }
}
