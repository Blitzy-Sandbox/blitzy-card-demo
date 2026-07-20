package com.carddemo.reporting.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.carddemo.reporting.model.JobAcknowledgement;
import com.carddemo.reporting.model.JobStatus;
import com.carddemo.reporting.model.ReportRequest;
import com.carddemo.reporting.model.StatementRequest;

/**
 * Unit tests for the reporting job-stub handlers.
 *
 * <p>These are plain (context-free) unit tests: the handlers are POJOs with no injected
 * collaborators, so they are instantiated directly. The tests pin the two honesty guarantees that
 * finding P4-M06 / AAP-35 ("No fabricated completion") require of every {@code [DEFERRED]} stub:</p>
 * <ol>
 *   <li>the {@code jobId} is always the nil-UUID sentinel (never a freshly-minted random UUID that
 *       would masquerade as a real tracking id), and</li>
 *   <li>{@code getJobStatus} never fabricates completion — it reports the non-terminal
 *       {@code ACCEPTED} status with a {@code null} {@code completedAt}.</li>
 * </ol>
 * <p>They also assert the complete P5-M15 batch roster: daily posting, interest calculation, and
 * the batch transaction report each return their own distinct {@code jobType}.</p>
 */
class ReportingJobHandlersTest {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final ReportJobHandler reportJobHandler = new ReportJobHandler();
    private final StatementJobHandler statementJobHandler = new StatementJobHandler();
    private final DailyPostingJobHandler dailyPostingJobHandler = new DailyPostingJobHandler();
    private final InterestCalculationJobHandler interestCalculationJobHandler = new InterestCalculationJobHandler();
    private final TransactionReportJobHandler transactionReportJobHandler = new TransactionReportJobHandler();

    @Test
    void submitReportJobReturnsAcceptedAckWithNilSentinelId() {
        JobAcknowledgement ack = reportJobHandler.submitReportJob(
                new ReportRequest().reportType(ReportRequest.ReportTypeEnum.MONTHLY));

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.REPORT);
        assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
        assertThat(ack.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitReportJobToleratesNullRequest() {
        JobAcknowledgement ack = reportJobHandler.submitReportJob(null);

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.REPORT);
        assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
    }

    @Test
    void getJobStatusNeverFabricatesCompletion() {
        UUID requested = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

        JobStatus status = reportJobHandler.getJobStatus(requested.toString());

        // The core P4-M06 guarantee: no fabricated COMPLETED, no fabricated completion timestamp.
        assertThat(status.getStatus()).isEqualTo(JobStatus.StatusEnum.ACCEPTED);
        assertThat(status.getStatus()).isNotEqualTo(JobStatus.StatusEnum.COMPLETED);
        assertThat(status.getCompletedAt()).isNull();
        // The requested id is echoed honestly when it is a valid UUID.
        assertThat(status.getJobId()).isEqualTo(requested);
        assertThat(status.getMessage()).contains("[DEFERRED]");
    }

    @Test
    void getJobStatusUsesNilSentinelForUnparseableId() {
        JobStatus status = reportJobHandler.getJobStatus("not-a-uuid");

        assertThat(status.getStatus()).isEqualTo(JobStatus.StatusEnum.ACCEPTED);
        assertThat(status.getCompletedAt()).isNull();
        // Unparseable id must NOT be replaced by a random UUID — it falls back to the nil sentinel.
        assertThat(status.getJobId()).isEqualTo(NIL_UUID);
    }

    @Test
    void submitStatementJobReturnsAcceptedAckWithNilSentinelId() {
        JobAcknowledgement ack = statementJobHandler.submitStatementJob(
                new StatementRequest().accountId("00000000011").format(StatementRequest.FormatEnum.HTML));

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.STATEMENT);
        assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
        assertThat(ack.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitStatementJobToleratesNullRequest() {
        JobAcknowledgement ack = statementJobHandler.submitStatementJob(null);

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.STATEMENT);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
    }

    @Test
    void submitDailyPostingJobReturnsDistinctTypedAck() {
        JobAcknowledgement ack = dailyPostingJobHandler.submitDailyPostingJob();

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.DAILY_POSTING);
        assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
        assertThat(ack.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitInterestCalculationJobReturnsDistinctTypedAck() {
        JobAcknowledgement ack = interestCalculationJobHandler.submitInterestCalculationJob();

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.INTEREST_CALCULATION);
        assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
        assertThat(ack.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitTransactionReportJobReturnsDistinctTypedAck() {
        JobAcknowledgement ack = transactionReportJobHandler.submitTransactionReportJob();

        assertThat(ack.getJobType()).isEqualTo(JobAcknowledgement.JobTypeEnum.TRANSACTION_REPORT);
        assertThat(ack.getStatus()).isEqualTo(JobAcknowledgement.StatusEnum.ACCEPTED);
        assertThat(ack.getJobId()).isEqualTo(NIL_UUID);
        assertThat(ack.getSubmittedAt()).isNotNull();
    }
}
