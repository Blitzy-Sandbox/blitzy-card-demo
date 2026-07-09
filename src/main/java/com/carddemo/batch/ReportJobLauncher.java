package com.carddemo.batch;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.awspring.cloud.sqs.annotation.SqsListener;

import com.carddemo.exception.FileProcessingException;
import com.carddemo.observability.CorrelationIdFilter;

/**
 * SQS FIFO&nbsp;&rarr;&nbsp;Spring Batch launcher &mdash; the <strong>receive side</strong> of the
 * migrated {@code CORPT00C} report-submission bridge. It listens on the report-request FIFO queue,
 * parses each report-request message, and launches the transaction-detail report job
 * ({@code transactionReportJob}) with the requested date window and a propagated correlation id.
 *
 * <h2>COBOL lineage (reference-only, source commit SHA {@code 27d6c6f})</h2>
 * <p>The source artifact is <strong>not</strong> copied into this repository; it is referenced by
 * commit SHA for traceability only (see {@code docs/traceability-matrix.md}). On the mainframe,
 * {@code app/cbl/CORPT00C.cbl} (CICS transaction {@code CR00}, "Transaction Reports") let an operator
 * request a Monthly, Yearly, or Custom report from a 3270 screen. After deriving the reporting window
 * ({@code PROCESS-ENTER-KEY}) and requiring a {@code Y}/{@code N} confirmation
 * ({@code SUBMIT-JOB-TO-INTRDR}), it built a JCL job stream and submitted it to JES by writing each
 * card to a CICS <em>extra-partition Transient Data Queue</em>
 * ({@code WIRTE-JOBSUB-TDQ}: {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}); the mainframe internal reader
 * (INTRDR) then picked up that stream and started the {@code TRANREPT} batch job.</p>
 *
 * <p>Per the migration architecture (AAP&nbsp;&sect;0.8.5, "the {@code CORPT00C} CICS TDQ&nbsp;&rarr;
 * JES report bridge maps to an SQS FIFO-triggered Spring Batch launch"), the order-preserving,
 * fire-and-forget TDQ&nbsp;&rarr;&nbsp;JES bridge is split into two Spring components:</p>
 * <ul>
 *   <li>the <strong>send side</strong> &mdash; {@code com.carddemo.service.ReportService} &mdash;
 *       which resolves the window, applies the confirmation gate, and enqueues exactly one message on
 *       the {@code carddemo-report-jobs.fifo} queue (the migrated {@code WIRTE-JOBSUB-TDQ}); and</li>
 *   <li>the <strong>receive side</strong> &mdash; <em>this class</em> &mdash; which is the modern
 *       analogue of the JES internal reader: it consumes the queued message and starts the batch job
 *       via {@link JobLauncher}.</li>
 * </ul>
 *
 * <h2>Message contract (Gate&nbsp;5)</h2>
 * <p>The SQS message body is the JSON serialization of the send side's report-request record; its
 * stable fields are {@code jobId}, {@code reportName}, {@code startDate}, {@code endDate} and
 * {@code correlationId}, with dates encoded as ISO-8601 {@code YYYY-MM-DD} strings (byte-identical to
 * the legacy {@code PARM-START-DATE} / {@code PARM-END-DATE} symbols). The launcher owns its own
 * inbound model ({@link ReportJobRequest}) and tolerates unknown fields so the contract can evolve
 * additively without breaking this consumer.</p>
 *
 * <h2>Job parameters, idempotency, and restartability</h2>
 * <p>The launcher translates the message into {@link JobParameters}:</p>
 * <ul>
 *   <li>{@code startDate} / {@code endDate} &mdash; <em>identifying</em>; consumed by the step-scoped
 *       {@code TransactionReportProcessor} (and the report writer). When the message omits a bound the
 *       legacy JCL defaults {@value #DEFAULT_START_DATE} / {@value #DEFAULT_END_DATE} are applied so
 *       the launched instance is always fully self-describing.</li>
 *   <li>{@code jobId} &mdash; <em>identifying</em>; the deduplication key. Because the send side mints
 *       a fresh {@link UUID} {@code jobId} per request, every genuine request yields a distinct,
 *       restartable {@code JobInstance}; and because a FIFO redelivery carries the same {@code jobId},
 *       a redelivered request re-resolves to the same instance. A completed instance is therefore
 *       rejected with {@link JobInstanceAlreadyCompleteException} (dedup &mdash; the report is never
 *       produced twice), a running instance with {@link JobExecutionAlreadyRunningException}, while a
 *       previously <em>failed</em> instance is restarted by the redelivery (the JCL rerun analogue).
 *       No unique {@code run.id} is added, precisely so this {@code jobId}-based deduplication is not
 *       defeated. The rationale is recorded in {@code docs/decision-log.md} (Explainability rule).</li>
 *   <li>{@code correlationId} &mdash; <em>non-identifying</em>; keyed by
 *       {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} so the {@code BatchCorrelationIdListener}
 *       re-publishes the <em>same</em> id into the MDC, stitching the end-to-end trace
 *       REST&nbsp;&rarr;&nbsp;SQS&nbsp;&rarr;&nbsp;batch. It is deliberately non-identifying so a
 *       freshly minted id (when a message carries none) cannot alter the instance identity and thus
 *       cannot defeat {@code jobId} deduplication.</li>
 * </ul>
 *
 * <h2>Error handling and delivery semantics</h2>
 * <p>The listener never calls {@code System.exit} and never swallows a launch failure silently. A
 * malformed JSON body, and each checked {@link org.springframework.batch.core.repository JobExecution}
 * failure declared by {@link JobLauncher#run(Job, JobParameters)}
 * ({@link JobExecutionAlreadyRunningException}, {@link JobInstanceAlreadyCompleteException},
 * {@link JobRestartException}, {@link JobParametersInvalidException}), is logged with SLF4J and
 * rethrown as an unchecked {@link FileProcessingException}. Rethrowing propagates the failure to the
 * SQS container so the broker's redelivery / dead-letter-queue policy applies; the duplicate-delivery
 * cases are logged at {@code WARN} (an expected idempotency outcome) whereas genuine launch faults are
 * logged at {@code ERROR}. No queue credential or other secret is ever logged.</p>
 *
 * <h2>Threading and configuration</h2>
 * <p>The bean is stateless (its collaborators are effectively immutable after construction) and
 * therefore thread-safe across concurrent SQS deliveries. The queue name is externalized to
 * {@code carddemo.aws.sqs.report-queue} (default {@code carddemo-report-jobs.fifo}) and is never
 * hardcoded; all AWS access is exercised against LocalStack with zero live AWS credentials
 * (AAP&nbsp;&sect;0.7.7). This class performs no monetary arithmetic, so no
 * {@code float}/{@code double} decimal-precision concern applies (AAP&nbsp;&sect;0.8.2).</p>
 *
 * @see com.carddemo.observability.CorrelationIdFilter
 * @see FileProcessingException
 */
@Component
public class ReportJobLauncher {

    /** SLF4J logger; emits only non-sensitive identifiers (jobId, correlationId), never credentials. */
    private static final Logger log = LoggerFactory.getLogger(ReportJobLauncher.class);

    /**
     * Identifying job-parameter key for the inclusive reporting-window start, read by the step-scoped
     * {@code TransactionReportProcessor} via {@code #{jobParameters['startDate']}}.
     */
    private static final String JOB_PARAM_START_DATE = "startDate";

    /**
     * Identifying job-parameter key for the inclusive reporting-window end, read by the step-scoped
     * {@code TransactionReportProcessor} via {@code #{jobParameters['endDate']}}.
     */
    private static final String JOB_PARAM_END_DATE = "endDate";

    /**
     * Identifying job-parameter key carrying the request's {@code jobId}. It is the
     * {@code JobInstance} deduplication key: a FIFO redelivery of the same request re-resolves to the
     * same instance, so a completed report is never produced twice.
     */
    private static final String JOB_PARAM_JOB_ID = "jobId";

    /**
     * Legacy JCL {@code PARM-START-DATE} default ({@code app/proc/TRANREPT.prc}) applied when an
     * inbound message omits the window start; keeps the launched instance fully self-describing.
     */
    private static final String DEFAULT_START_DATE = "2022-01-01";

    /**
     * Legacy JCL {@code PARM-END-DATE} default ({@code app/proc/TRANREPT.prc}) applied when an inbound
     * message omits the window end.
     */
    private static final String DEFAULT_END_DATE = "2022-07-06";

    /** Launches the Spring Batch job; the Spring Boot auto-configured {@link JobLauncher}. */
    private final JobLauncher jobLauncher;

    /**
     * The transaction-detail report job (bean name {@code "transactionReportJob"}, defined in
     * {@code TransactionReportJob}); injected by qualifier to disambiguate from other {@link Job} beans.
     */
    private final Job transactionReportJob;

    /** JSON reader for the SQS report-request message body (the Spring-managed, fully configured mapper). */
    private final ObjectMapper objectMapper;

    /**
     * Creates the launcher with all collaborators injected by constructor (the single constructor is
     * auto-detected by Spring, so no {@code @Autowired} is required).
     *
     * @param jobLauncher          the Spring Batch job launcher; must not be {@code null}
     * @param transactionReportJob the transaction-report job bean, resolved by the qualifier
     *                             {@code "transactionReportJob"}; must not be {@code null}
     * @param objectMapper         the JSON mapper used to parse the report-request message body; must
     *                             not be {@code null}
     */
    public ReportJobLauncher(final JobLauncher jobLauncher,
                             @Qualifier("transactionReportJob") final Job transactionReportJob,
                             final ObjectMapper objectMapper) {
        this.jobLauncher = jobLauncher;
        this.transactionReportJob = transactionReportJob;
        this.objectMapper = objectMapper;
    }

    /**
     * SQS FIFO listener &mdash; the migrated JES internal reader. Consumes one report-request message
     * from {@code carddemo-report-jobs.fifo} (queue name externalized to
     * {@code carddemo.aws.sqs.report-queue}), parses it, and launches {@code transactionReportJob}
     * with the resolved date window, {@code jobId}, and propagated {@code correlationId}.
     *
     * <p>The correlation id is resolved from the message when present (non-blank) and otherwise minted
     * as a fresh {@link UUID}; either way it is passed under
     * {@link CorrelationIdFilter#CORRELATION_ID_MDC_KEY} so the batch job re-establishes the same id in
     * the MDC. Any failure &mdash; a malformed body or a batch launch fault &mdash; is logged and
     * rethrown as a {@link FileProcessingException} so SQS redelivery / DLQ handling applies; the
     * method never terminates the JVM and never swallows a failure.</p>
     *
     * @param messageBody the raw JSON report-request message body delivered by SQS (never {@code null}
     *                    for a normal delivery)
     * @throws FileProcessingException if the message body cannot be parsed, or if the batch job cannot
     *                                 be launched (including a duplicate-delivery rejection)
     */
    @SqsListener("${carddemo.aws.sqs.report-queue:carddemo-report-jobs.fifo}")
    public void onReportRequest(final String messageBody) {
        final ReportJobRequest request = parseMessage(messageBody);

        // Resolve the launch inputs, applying JCL defaults / UUID fallbacks for any blank field so the
        // launched instance is always well-defined even for a partially populated message.
        final String startDate = firstNonBlank(request.startDate(), DEFAULT_START_DATE);
        final String endDate = firstNonBlank(request.endDate(), DEFAULT_END_DATE);
        final String jobId = firstNonBlank(request.jobId(), UUID.randomUUID().toString());
        final String correlationId = firstNonBlank(request.correlationId(), UUID.randomUUID().toString());

        final JobParameters jobParameters = new JobParametersBuilder()
                // Identifying: the reporting window and the dedup key together define the JobInstance.
                .addString(JOB_PARAM_START_DATE, startDate)
                .addString(JOB_PARAM_END_DATE, endDate)
                .addString(JOB_PARAM_JOB_ID, jobId)
                // Non-identifying: observability only; must not participate in instance identity so a
                // freshly minted id cannot defeat jobId deduplication.
                .addString(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, correlationId, false)
                .toJobParameters();

        log.info("Launching transactionReportJob from SQS report request [jobId={}] window=[{} .. {}] correlationId={}",
                jobId, startDate, endDate, correlationId);

        try {
            final JobExecution execution = jobLauncher.run(transactionReportJob, jobParameters);
            log.info("Launched transactionReportJob [jobId={}] jobExecutionId={} status={} correlationId={}",
                    jobId, execution.getId(), execution.getStatus(), correlationId);
            // F4 (D-027): the auto-configured JobLauncher is synchronous, so run() returns the finished
            // JobExecution — and a FAILED step does NOT throw from run(); it returns status=FAILED. If the
            // job did not COMPLETE (for example a writer failed its S3 upload in afterStep), the report was
            // not produced, so we must NOT let the @SqsListener acknowledge the message. Throwing here keeps
            // the message unacknowledged: SQS redelivers it and, after maxReceiveCount, routes it to the
            // report DLQ (carddemo-report-jobs-dlq.fifo) for inspection instead of silently losing it.
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                log.error("transactionReportJob did not complete [jobId={}] batchStatus={} correlationId={}; "
                                + "message will be redelivered/DLQ'd", jobId, execution.getStatus(), correlationId);
                throw new FileProcessingException(
                        "Transaction report job did not complete for jobId=" + jobId
                                + " (batchStatus=" + execution.getStatus() + ")");
            }
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException e) {
            // Idempotency / FIFO at-least-once: this exact request is already running or has already
            // completed, so the report is deliberately NOT produced again. Surface it so the broker's
            // redelivery/DLQ policy captures the duplicate for inspection rather than silently dropping it.
            log.warn("Report request [jobId={}] not re-run ({}); duplicate delivery will be redelivered/DLQ'd. correlationId={}",
                    jobId, e.getClass().getSimpleName(), correlationId, e);
            throw new FileProcessingException(
                    "Transaction report job launch rejected as a duplicate for jobId=" + jobId, e);
        } catch (JobRestartException | JobParametersInvalidException e) {
            // Genuine launch fault (non-restartable state or invalid parameters). Fail loudly so the
            // message is redelivered / dead-lettered; never abend the JVM.
            log.error("Failed to launch transactionReportJob [jobId={}] correlationId={}", jobId, correlationId, e);
            throw new FileProcessingException(
                    "Unable to launch transaction report job for jobId=" + jobId, e);
        }
    }

    /**
     * Parses the raw SQS message body into a {@link ReportJobRequest}. A malformed body or a JSON
     * {@code null} literal is a broken batch-trigger contract that cannot be reprocessed, so it is
     * logged at {@code ERROR} and rethrown as a {@link FileProcessingException} (routing the message to
     * the DLQ) rather than allowing a raw parser exception to escape.
     *
     * @param messageBody the raw JSON message body
     * @return the parsed, non-{@code null} report request
     * @throws FileProcessingException if the body is not valid JSON or deserializes to {@code null}
     */
    private ReportJobRequest parseMessage(final String messageBody) {
        try {
            final ReportJobRequest request = objectMapper.readValue(messageBody, ReportJobRequest.class);
            if (request == null) {
                throw new FileProcessingException("Report-request message body was empty or a JSON null literal");
            }
            return request;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse SQS report-request message body as JSON", e);
            throw new FileProcessingException("Malformed report-request message; cannot parse the JSON body", e);
        }
    }

    /**
     * Returns {@code value} when it is non-{@code null} and not blank, otherwise {@code fallback}. Used
     * to apply the JCL date defaults and the UUID fallbacks for {@code jobId} / {@code correlationId}.
     *
     * @param value    the candidate value (may be {@code null} or blank)
     * @param fallback the replacement used when {@code value} is absent or blank; must be non-blank
     * @return {@code value} if usable, otherwise {@code fallback}
     */
    private static String firstNonBlank(final String value, final String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    /**
     * Launcher-owned, immutable model of the inbound SQS report-request message. Its component names
     * mirror the send side's serialized contract ({@code jobId}, {@code reportName}, {@code startDate},
     * {@code endDate}, {@code correlationId}); dates are ISO-8601 {@code YYYY-MM-DD} strings. Unknown
     * JSON properties are ignored so the external contract can evolve additively (Gate&nbsp;5) without
     * breaking this consumer. Jackson binds it via the canonical record constructor.
     *
     * @param jobId         unique job identifier / FIFO deduplication key for the enqueued report
     * @param reportName    human-readable report name ({@code Monthly}/{@code Yearly}/{@code Custom})
     * @param startDate     inclusive reporting-window start, ISO-8601 {@code YYYY-MM-DD}
     * @param endDate       inclusive reporting-window end, ISO-8601 {@code YYYY-MM-DD}
     * @param correlationId originating correlation id, propagated for end-to-end tracing
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ReportJobRequest(String jobId,
                                    String reportName,
                                    String startDate,
                                    String endDate,
                                    String correlationId) {
    }
}
