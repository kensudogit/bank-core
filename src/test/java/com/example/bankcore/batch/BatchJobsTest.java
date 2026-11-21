package com.example.bankcore.batch;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** ジョブと終了コード：正常 0・照合不一致 4・起動条件の誤り 12（承認なしの設定で締めは完了まで進む）。 */
class BatchJobsTest extends AbstractIntegrationTest {

    @Autowired BatchJobs jobs;

    @Test
    void unknownJobIsInvalid() {
        BatchJobs.JobResult r = jobs.run("no-such-job", "JP1");
        assertThat(r.exitCode()).isEqualTo(BatchJobs.INVALID);
        assertThat(r.status()).isEqualTo("UNKNOWN_JOB");
    }

    @Test
    void closingStatusWithoutAnyClosingIsInvalid() {
        assertThat(jobs.run("closing-status", "JP1").exitCode()).isEqualTo(BatchJobs.INVALID);
    }

    @Test
    void reconciliationJobReportsMismatchAsNeedsRepair() {
        Account a = openAccount("A", 1_000);
        assertThat(jobs.run("reconciliation", "JP1").exitCode()).isEqualTo(BatchJobs.OK);
        jdbc.sql("UPDATE accounts SET posted_balance_minor = 1 WHERE id = :id").param("id", a.id()).update();
        BatchJobs.JobResult r = jobs.run("reconciliation", "JP1");
        assertThat(r.exitCode()).isEqualTo(BatchJobs.NEEDS_REPAIR);
        assertThat(r.status()).isEqualTo("MISMATCH");
    }

    @Test
    void dailyClosingThenStatusAreOk() {
        openAccount("A", 3_650_000);
        BatchJobs.JobResult r = jobs.run("daily-closing", "JP1");
        assertThat(r.exitCode()).isEqualTo(BatchJobs.OK);
        assertThat(r.status()).isEqualTo("COMPLETED");
        assertThat(r.detail()).isInstanceOf(DailyClosingService.ClosingReport.class);
        assertThat(jobs.run("closing-status", "JP1").exitCode()).isEqualTo(BatchJobs.OK);
    }

    @Test
    void exceptionsBecomeFailedExitCode() {
        // 実行者が空 → 締めが例外を投げる → 8
        BatchJobs.JobResult r = jobs.run("daily-closing", " ");
        assertThat(r.exitCode()).isEqualTo(BatchJobs.FAILED);
        assertThat(r.status()).isEqualTo("FAILED");
    }

    @Test
    void exitCodesAreDistinct() {
        assertThat(new int[]{BatchJobs.OK, BatchJobs.AWAITING_APPROVAL, BatchJobs.NEEDS_REPAIR, BatchJobs.FAILED,
                BatchJobs.INVALID}).doesNotHaveDuplicates().containsExactly(0, 1, 4, 8, 12);
    }
}
