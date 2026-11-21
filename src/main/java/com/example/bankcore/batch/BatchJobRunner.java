package com.example.bankcore.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * コマンドラインからジョブを 1 つ実行して終了する（JP1/AJS などのジョブから直接起動する場合）。
 *
 * <pre>
 * java -jar bank-core.jar --spring.main.web-application-type=none --bank.outbox.scheduler-enabled=false \
 *      --bank.job.name=daily-closing --bank.job.operator=JP1
 * </pre>
 * 終了コードは {@link BatchJobs} の取り決めに従う。{@code bank.job.name} を指定しない通常起動では何もしない。
 */
@Component
@ConditionalOnProperty(prefix = "bank.job", name = "name")
public class BatchJobRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(BatchJobRunner.class);

    private final BatchJobs jobs;
    private final ConfigurableApplicationContext context;
    private final String job;
    private final String operator;
    private final boolean exitAfterRun;
    private int exitCode = BatchJobs.FAILED;

    public BatchJobRunner(BatchJobs jobs, ConfigurableApplicationContext context,
                          @Value("${bank.job.name}") String job,
                          @Value("${bank.job.operator:JP1}") String operator,
                          @Value("${bank.job.exit:true}") boolean exitAfterRun) {
        this.jobs = jobs;
        this.context = context;
        this.job = job;
        this.operator = operator;
        this.exitAfterRun = exitAfterRun;
    }

    @Override
    public void run(ApplicationArguments args) {
        BatchJobs.JobResult result = jobs.run(job, operator);
        exitCode = result.exitCode();
        log.info("job {} finished: exitCode={} status={} message={}", job, exitCode, result.status(), result.message());
        if (exitAfterRun) {
            System.exit(SpringApplication.exit(context, this));
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
