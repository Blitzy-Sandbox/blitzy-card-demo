/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.service;

import com.carddemo.service.ReportService.ReportRequestMessage;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Consumer half of the <strong>F-011 asynchronous submit-then-process report
 * bridge</strong>, replacing the CICS internal-reader trigger of program
 * {@code app/cbl/CORPT00C.cbl} at source commit SHA {@code 27d6c6f}.
 *
 * <h2>What the legacy program does</h2>
 * {@code CORPT00C} (transaction {@code CR00}) builds a JCL stream and writes it to
 * the CICS extra-partition Transient Data Queue {@code JOBS} (paragraphs
 * {@code SUBMIT-JOB-TO-INTRDR} / {@code WIRTE-JOBSUB-TDQ}); the CICS internal
 * reader then submits that JCL, asynchronously launching the transaction-report
 * batch job. The observable contract is <em>submit-then-process</em>: the online
 * transaction returns immediately while the report is produced later by a
 * separate batch unit of work.
 *
 * <h2>How the target preserves it</h2>
 * {@link ReportService} (the producer half) publishes a single typed
 * {@link ReportRequestMessage} to the SQS FIFO queue
 * {@code carddemo-report-jobs.fifo} instead of writing JCL to a TDQ. This class is
 * the counterpart that the CICS internal reader represented: an
 * {@link SqsListener &#64;SqsListener} receives that message and launches the
 * Spring Batch {@code transactionReportJob}
 * ({@link com.carddemo.batch.job.TransactionReportJobConfig}), which renders the
 * date-windowed {@code CBTRN03C}/{@code CVTRA07Y} report and writes a 133-byte
 * fixed-width object to the S3 output bucket. Wiring the two halves together
 * completes the bridge end to end: <em>submit &rarr; SQS FIFO &rarr; consume
 * &rarr; batch &rarr; S3</em>. This realizes the design recorded in AAP
 * &sect;0.6.6 and {@code DECISION_LOG} D-004; it adds no new feature, only the
 * already-designed trigger that closes F-011.
 *
 * <h2>Job-parameter mapping (critical)</h2>
 * {@code transactionReportJob} requires the inclusive reporting window under the
 * exact keys {@code reportStartDate} and {@code reportEndDate} &mdash; the
 * constants the job's {@code ReportJobParametersValidator} enforces and the
 * step-scoped reader late-binds via
 * {@code #{jobParameters['reportStartDate']}} / {@code ['reportEndDate']}. This
 * consumer therefore maps {@link ReportRequestMessage#startDate()} &rarr;
 * {@code reportStartDate} and {@link ReportRequestMessage#endDate()} &rarr;
 * {@code reportEndDate}. The resolved {@code reportName} is carried as a
 * <em>non-identifying</em> context parameter, and a fresh {@code launchId}
 * ({@link UUID}) is added as an identifying parameter so every submission becomes
 * a distinct {@code JobInstance} (a completed instance can otherwise never be
 * re-run, which would surface as {@code JobInstanceAlreadyCompleteException}).
 *
 * <h2>FIFO head-of-line safety</h2>
 * Every submission is published to the single message group {@code report-jobs},
 * so the FIFO queue processes one message at a time and a message that is not
 * acknowledged blocks the entire group until it is redriven. To avoid a
 * poison-message loop that would stall <em>all</em> subsequent reports, the
 * listener method is total: it never propagates an exception. A launch failure
 * (the checked {@link JobExecutionException} family) or any unexpected
 * {@link RuntimeException} is logged at {@code ERROR} and swallowed so the message
 * is acknowledged and the group keeps flowing. This faithfully mirrors the
 * mainframe semantics, where an internal-reader submission triggers the job once
 * and a job failure surfaces in the spool rather than auto-resubmitting; richer
 * retry/dead-letter handling is intentionally out of scope (no DLQ is defined in
 * the AAP).
 *
 * <h2>Conditional activation</h2>
 * The bean is created only when {@code carddemo.report.consumer.enabled} is
 * {@code true} (the default when the property is absent, via
 * {@code matchIfMissing = true}). The base {@code application.yml} enables it for
 * production and local runs; {@code application-test.yml} disables it so the
 * publish-contract integration tests (which receive from the queue themselves to
 * assert the published message) are not drained by a live listener. The dedicated
 * {@code ReportJobConsumerIT} re-enables it to verify the full round trip.
 *
 * <p>The listener container itself is auto-registered by
 * {@code spring-cloud-aws-starter-sqs}; this class only supplies the annotated
 * handler and its collaborators via constructor injection.</p>
 */
@Component
@ConditionalOnProperty(name = "carddemo.report.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class ReportJobConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReportJobConsumer.class);

    /**
     * Identifying job-parameter key carrying the inclusive window start; must equal
     * {@code TransactionReportJobConfig.START_DATE_PARAM} ({@code reportStartDate}).
     */
    static final String START_DATE_PARAM = "reportStartDate";

    /**
     * Identifying job-parameter key carrying the inclusive window end; must equal
     * {@code TransactionReportJobConfig.END_DATE_PARAM} ({@code reportEndDate}).
     */
    static final String END_DATE_PARAM = "reportEndDate";

    /** Non-identifying job-parameter key carrying the resolved report name for traceability. */
    static final String REPORT_NAME_PARAM = "reportName";

    /** Identifying job-parameter key guaranteeing a unique {@code JobInstance} per submission. */
    static final String LAUNCH_ID_PARAM = "launchId";

    /**
     * Span operation name for the consumer span opened when a report-request message is
     * received, mirroring the producer's "{destination} send" with "{destination} receive"
     * so the publish&rarr;consume hop reads as one messaging operation in Jaeger.
     */
    private static final String SQS_RECEIVE_SPAN_NAME = "report-jobs receive";

    /** Remote-service name attached to the consumer span, identifying the messaging system. */
    private static final String MESSAGING_REMOTE_SERVICE = "sqs";

    private final JobLauncher jobLauncher;

    private final Job transactionReportJob;

    private final Tracer tracer;

    private final Propagator propagator;

    /**
     * Creates the consumer with the Spring Boot auto-configured {@link JobLauncher} and the
     * transaction-report job bean.
     *
     * @param jobLauncher          the synchronous job launcher (auto-configured because the
     *                             application does not declare {@code @EnableBatchProcessing});
     *                             never {@code null}
     * @param transactionReportJob the {@code transactionReportJob} bean, injected by qualified
     *                             name so the correct job is selected in the multi-job context;
     *                             never {@code null}
     * @param tracer               the Micrometer {@link Tracer} used to open the consumer span
     *                             that re-parents the batch job into the producer's trace;
     *                             never {@code null}
     * @param propagator           the {@link Propagator} (W3C trace-context by default) that
     *                             extracts the {@code traceparent} from the inbound SQS message
     *                             headers so the consumer span continues the producer's trace;
     *                             never {@code null}
     */
    public ReportJobConsumer(JobLauncher jobLauncher,
                             @Qualifier("transactionReportJob") Job transactionReportJob,
                             Tracer tracer,
                             Propagator propagator) {
        this.jobLauncher = jobLauncher;
        this.transactionReportJob = transactionReportJob;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Receives a report-request message from the FIFO report queue and launches
     * {@code transactionReportJob} for its inclusive date window, completing the F-011
     * submit-then-process bridge.
     *
     * <p>The queue is referenced by the same configuration property the producer resolves its
     * name from ({@code carddemo.aws.sqs.report-queue}), so the listener and publisher can never
     * drift apart and no queue name is hardcoded. The message body is deserialized into the same
     * {@link ReportRequestMessage} record the producer publishes, keeping a single source of
     * truth for the payload contract.</p>
     *
     * <p>The method is deliberately total &mdash; it never throws &mdash; so that a single failing
     * message cannot wedge the FIFO {@code report-jobs} group (see the class-level
     * &quot;FIFO head-of-line safety&quot; note). Outcomes are logged: a normal completion at
     * {@code INFO}, and a non-{@code COMPLETED} batch status or a launch/processing failure at
     * {@code ERROR}.</p>
     *
     * <p><strong>Distributed tracing across the SQS boundary.</strong> The producer injects the
     * active W3C trace context into the message headers; this method extracts it and opens a
     * {@link Span.Kind#CONSUMER CONSUMER} span as a child of the producer span, then launches the
     * batch job <em>within that span's scope</em> so the job/step spans re-parent beneath it. The
     * result is a single continuous trace spanning REST submit &rarr; SQS publish &rarr; SQS
     * consume &rarr; batch, fulfilling the Observability rule's cross-boundary tracing
     * requirement. The span is always ended; failures are recorded on it but never rethrown, so
     * the method stays total and the FIFO group is never head-of-line blocked.</p>
     *
     * @param message the published report request carrying the resolved report name and the
     *                inclusive {@code YYYY-MM-DD} window bounds
     * @param headers the inbound message headers (mapped from the SQS message attributes) from
     *                which the W3C {@code traceparent} is extracted to continue the producer's trace
     */
    @SqsListener("${carddemo.aws.sqs.report-queue}")
    public void onReportRequest(ReportRequestMessage message,
                                @Headers Map<String, Object> headers) {
        // Continue the producer's trace across the SQS boundary: extract the W3C trace context
        // the producer injected into the message headers and open a CONSUMER span as its child.
        // Launching the batch job inside this span's scope re-parents the job/step spans under it.
        Span consumerSpan = propagator.extract(headers, (carrier, key) -> {
                    Object value = (carrier != null) ? carrier.get(key) : null;
                    return (value != null) ? value.toString() : null;
                })
                .name(SQS_RECEIVE_SPAN_NAME)
                .kind(Span.Kind.CONSUMER)
                .remoteServiceName(MESSAGING_REMOTE_SERVICE)
                .start();

        // Make the consumer span current for the duration of the job launch so the batch
        // job/step spans re-parent beneath it. The scope is closed explicitly in the finally
        // block (rather than via try-with-resources) so the scope handle is referenced in-body,
        // avoiding the -Xlint:try "auto-closeable resource scope is never referenced" warning
        // that the zero-warning build (-Werror) treats as an error.
        Tracer.SpanInScope scope = tracer.withSpan(consumerSpan);
        try {
            String launchId = UUID.randomUUID().toString();
            log.info("Received report-job request from SQS FIFO bridge: reportName={}, startDate={}, "
                            + "endDate={}, launchId={}",
                    message.reportName(), message.startDate(), message.endDate(), launchId);

            JobParameters parameters = new JobParametersBuilder()
                    // Non-identifying: carried for traceability, excluded from JobInstance identity.
                    .addString(REPORT_NAME_PARAM, message.reportName(), false)
                    // Identifying: the required window keys the job validates and the reader binds.
                    .addString(START_DATE_PARAM, message.startDate())
                    .addString(END_DATE_PARAM, message.endDate())
                    // Identifying: makes every submission a fresh JobInstance (avoids re-run of a
                    // completed instance).
                    .addString(LAUNCH_ID_PARAM, launchId)
                    .toJobParameters();

            try {
                JobExecution execution = jobLauncher.run(transactionReportJob, parameters);
                if (execution.getStatus() == BatchStatus.COMPLETED) {
                    log.info("transactionReportJob completed for report '{}' window [{} .. {}]: "
                                    + "executionId={}, exitCode={}, launchId={}",
                            message.reportName(), message.startDate(), message.endDate(),
                            execution.getId(), execution.getExitStatus().getExitCode(), launchId);
                } else {
                    log.error("transactionReportJob did not complete normally for report '{}' window "
                                    + "[{} .. {}]: executionId={}, status={}, exitCode={}, launchId={}",
                            message.reportName(), message.startDate(), message.endDate(),
                            execution.getId(), execution.getStatus(),
                            execution.getExitStatus().getExitCode(), launchId);
                }
            } catch (JobExecutionException ex) {
                // Launch-level failure (e.g. invalid parameters, restart): record on the span,
                // then log and swallow so the FIFO group is not blocked by an unacknowledged message.
                consumerSpan.error(ex);
                log.error("Failed to launch transactionReportJob for report '{}' window [{} .. {}] "
                                + "(launchId={})",
                        message.reportName(), message.startDate(), message.endDate(), launchId, ex);
            } catch (RuntimeException ex) {
                // Defensive: any unexpected error must not propagate, or it would wedge the single
                // FIFO message group (head-of-line block) and stall every later report.
                consumerSpan.error(ex);
                log.error("Unexpected error processing report-job request for report '{}' window "
                                + "[{} .. {}] (launchId={})",
                        message.reportName(), message.startDate(), message.endDate(), launchId, ex);
            }
        } finally {
            // Always close the scope and end the consumer span (success or failure) so the
            // trace is complete and the thread's previous span context is restored.
            scope.close();
            consumerSpan.end();
        }
    }
}
