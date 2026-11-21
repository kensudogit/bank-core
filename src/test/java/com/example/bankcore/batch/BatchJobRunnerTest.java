package com.example.bankcore.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** コマンドラインからのジョブ起動：ジョブの終了コードをそのままプロセスの終了コードにする。 */
class BatchJobRunnerTest {

    @Test
    void exitCodeIsFailedUntilTheJobRuns() {
        BatchJobRunner runner = new BatchJobRunner(mock(BatchJobs.class), mock(ConfigurableApplicationContext.class),
                "daily-closing", "JP1", false);
        assertThat(runner.getExitCode()).isEqualTo(BatchJobs.FAILED);
    }

    @Test
    void runsTheNamedJobAsOperatorAndExposesItsExitCode() {
        BatchJobs jobs = mock(BatchJobs.class);
        when(jobs.run("reconciliation", "JP1"))
                .thenReturn(new BatchJobs.JobResult("reconciliation", BatchJobs.NEEDS_REPAIR, "MISMATCH", "x", null));
        BatchJobRunner runner = new BatchJobRunner(jobs, mock(ConfigurableApplicationContext.class), "reconciliation",
                "JP1", false);
        runner.run(new DefaultApplicationArguments());
        verify(jobs).run("reconciliation", "JP1");
        assertThat(runner.getExitCode()).isEqualTo(BatchJobs.NEEDS_REPAIR);
    }

    @Test
    void onlyCreatedWhenAJobNameIsGiven() {
        ApplicationContextRunner ctx = new ApplicationContextRunner()
                .withBean(BatchJobs.class, () -> mock(BatchJobs.class))
                .withUserConfiguration(BatchJobRunner.class)
                .withPropertyValues("bank.job.exit=false");
        ctx.run(c -> assertThat(c).doesNotHaveBean(BatchJobRunner.class));
        ctx.withPropertyValues("bank.job.name=closing-status")
                .run(c -> assertThat(c).hasSingleBean(BatchJobRunner.class));
    }
}
