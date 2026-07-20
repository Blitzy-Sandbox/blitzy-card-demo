package com.carddemo.reporting.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.carddemo.reporting.jobs.DailyPostingJobHandler;
import com.carddemo.reporting.jobs.InterestCalculationJobHandler;
import com.carddemo.reporting.jobs.ReportJobHandler;
import com.carddemo.reporting.jobs.StatementJobHandler;
import com.carddemo.reporting.jobs.TransactionReportJobHandler;
import com.carddemo.reporting.model.JobAcknowledgement;

/**
 * Unit test for the non-host-serving job invocation mechanism (finding P4-m04 / AAP-38).
 *
 * <p>Verifies that {@link JobInvocationRunner#invokeAll()} actually triggers every job-stub
 * handler once — proving the reporting job stubs are invokable without an HTTP server, scheduler,
 * or queue — and that each returned acknowledgement is an honest typed placeholder (nil-UUID
 * sentinel, {@code ACCEPTED} status). The five distinct {@code jobType}s confirm the full P5-M15
 * roster is wired into the runner in a deterministic order.</p>
 */
class JobInvocationRunnerTest {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final JobInvocationRunner runner = new JobInvocationRunner(
            new ReportJobHandler(),
            new StatementJobHandler(),
            new DailyPostingJobHandler(),
            new InterestCalculationJobHandler(),
            new TransactionReportJobHandler());

    @Test
    void invokeAllTriggersEveryJobStubOnce() {
        List<JobAcknowledgement> acks = runner.invokeAll();

        assertThat(acks).hasSize(5);
        assertThat(acks).extracting(JobAcknowledgement::getJobType).containsExactly(
                JobAcknowledgement.JobTypeEnum.REPORT,
                JobAcknowledgement.JobTypeEnum.STATEMENT,
                JobAcknowledgement.JobTypeEnum.DAILY_POSTING,
                JobAcknowledgement.JobTypeEnum.INTEREST_CALCULATION,
                JobAcknowledgement.JobTypeEnum.TRANSACTION_REPORT);
    }

    @Test
    void everyInvokedStubReturnsHonestTypedPlaceholder() {
        List<JobAcknowledgement> acks = runner.invokeAll();

        assertThat(acks).allSatisfy(ack -> {
            assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
            assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
            assertThat(ack.getSubmittedAt()).isNotNull();
        });
    }
}
