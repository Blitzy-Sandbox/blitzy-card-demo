/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.awsm2.carddemo.adapter;

import com.awsm2.carddemo.exception.CardDemoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionStatus;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.util.UUID;

/**
 * AWS Step Functions orchestration adapter for CardDemo batch pipelines.
 *
 * <p><b>Replaces:</b> JCL job-stream sequencing across
 * {@code POSTTRAN.jcl} ({@code STEP15 EXEC PGM=CBTRN02C}),
 * {@code INTCALC.jcl} ({@code STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}),
 * {@code COMBTRAN.jcl} ({@code STEP05R SORT + STEP10 IDCAMS REPRO}),
 * {@code CREASTMT.JCL} (multi-step IDCAMS + SORT + CBSTM03A), and
 * {@code TRANREPT.jcl} (multi-step REPROC + SORT + CBTRN03C) — together
 * with the online-to-batch bridge documented in
 * {@code CORPT00C.cbl WIRTE-JOBSUB-TDQ} → CICS TDQ {@code 'JOBS'} →
 * JES2 submission.</p>
 *
 * <p>Per AAP §0.6.3 ("JCL → Step Functions Orchestration"), each JCL
 * {@code STEP} becomes a Step Functions {@code Task} state, {@code COND=}
 * mappings become {@code Choice} states, parallel steps (e.g.,
 * {@code CREASTMT} + {@code TRANREPT} downstream of {@code COMBTRAN})
 * become {@code Parallel} states, and failure handling is centralised in
 * {@code Catch} / {@code Retry} declarations within the ASL JSON
 * (defined under {@code src/main/resources/stepfunctions/}). This adapter
 * is the SOLE place in the CardDemo Spring Boot codebase that invokes the
 * AWS SDK v2 {@link SfnClient}, per AAP §0.7.1 ("Isolate all AWS service
 * integrations in dedicated adapter classes — never inline AWS SDK calls
 * in business logic").</p>
 *
 * <h2>Replaces: JCL job stream sequencing</h2>
 * <p>Class-level replacement: JCL job stream sequencing (POSTTRAN →
 * INTCALC → COMBTRAN → CREASTMT/TRANREPT parallel) + CORPT00C JOBS TDQ
 * submission. The end-of-day batch chain documented in {@code README.md}
 * §"Running full batch" now corresponds 1:1 to a single Step Functions
 * state machine execution started by {@link #startEodPipeline(String)}.</p>
 *
 * <h2>Execution-name uniqueness contract</h2>
 * <p>AWS Step Functions enforces that execution names be unique per state
 * machine for 90 days. This adapter generates a guaranteed-unique name
 * of the form {@code carddemo-{epoch-millis}-{short-uuid}} for every
 * {@link #startExecution(String, String)} call. The format respects the
 * Step Functions execution-name constraints (1–80 characters, character
 * set {@code [a-zA-Z0-9-_]}).</p>
 *
 * <h2>Retry / Catch semantics</h2>
 * <p>Retry and Catch policies are declared inside the ASL JSON state
 * machine definitions, not here. This adapter only starts executions —
 * it never inspects, interprets, or augments per-state retry behaviour.
 * The AWS SDK v2 default retry policy ({@code RetryMode.STANDARD}, with
 * exponential back-off + full jitter) is applied at the
 * {@link SfnClient} bean level by {@code AwsSdkConfig} for transient
 * service-side throttling.</p>
 *
 * <h2>Error propagation contract</h2>
 * <ul>
 *   <li>{@link SfnException} (the SDK v2 base for all Step Functions
 *       service errors including
 *       {@code ExecutionAlreadyExistsException},
 *       {@code InvalidArnException}, {@code InvalidExecutionInputException},
 *       {@code InvalidNameException},
 *       {@code StateMachineDeletingException}, and
 *       {@code StateMachineDoesNotExistException}) is propagated
 *       unchanged to the caller per AAP §0.7.1. Re-running a failed
 *       {@code StartExecution} is an operational decision that depends
 *       on the specific error code; this adapter does NOT swallow.</li>
 *   <li>{@link CardDemoException} (with the verbatim reason code
 *       {@code "CONFIG_ERROR"}) is thrown when a convenience method
 *       ({@link #startEodPipeline(String)},
 *       {@link #startReportPipeline(String)}) is invoked but the
 *       corresponding state-machine ARN was not configured. Per AAP §0.7.2
 *       the reason code is propagated verbatim by
 *       {@code GlobalExceptionHandler} into the API response envelope.</li>
 *   <li>{@link IllegalArgumentException} is thrown for caller bugs (null
 *       or blank required parameters) and surfaces as HTTP 400 via the
 *       global handler.</li>
 * </ul>
 *
 * <h2>Synchronous client choice</h2>
 * <p>This adapter uses the synchronous {@link SfnClient} (not
 * {@code SfnAsyncClient}) because:</p>
 * <ul>
 *   <li>Step Functions {@code StartExecution} latency is low
 *       (~100 ms) — async would add complexity without measurable
 *       benefit.</li>
 *   <li>Spring {@code @Async} at the caller layer (e.g.,
 *       {@code KafkaEventConsumer}, {@code ReportSubmissionService})
 *       provides concurrency without requiring the SDK to be async.</li>
 *   <li>Sync clients integrate cleanly with Spring's
 *       {@code @Transactional} semantics when a transactional caller
 *       also needs to start a state-machine execution as part of its
 *       unit-of-work.</li>
 * </ul>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig#sfnClient()
 */
@Service
public class StepFunctionsOrchestrator {

    /**
     * Verbatim reason code emitted when a convenience method is invoked
     * but the configured state-machine ARN is blank/missing. Per AAP §0.7.2
     * the {@code GlobalExceptionHandler} propagates this verbatim into the
     * JSON {@code ApiResponse.code} field so downstream consumers receive
     * an unambiguous configuration signal.
     */
    static final String CONFIG_ERROR_REASON_CODE = "CONFIG_ERROR";

    /** Length of the short UUID suffix appended to execution names. */
    private static final int SHORT_UUID_LENGTH = 8;

    /** Default empty JSON object used when no input is supplied. */
    private static final String EMPTY_JSON_INPUT = "{}";

    /** Class-level SLF4J logger — structured JSON logging per AAP §0.6.6. */
    private static final Logger LOG = LoggerFactory.getLogger(StepFunctionsOrchestrator.class);

    /**
     * AWS SDK v2 Step Functions client. Constructor-injected; configured
     * by {@code AwsSdkConfig.sfnClient()} with profile-specific endpoint
     * override, credentials provider, and retry policy.
     */
    private final SfnClient sfnClient;

    /**
     * ARN of the end-of-day batch pipeline state machine
     * ({@code eod-batch-pipeline.asl.json}) — provisioned by Terraform in
     * {@code infrastructure/terraform/stepfunctions.tf} and externalised
     * via Spring Cloud AWS Parameter Store per AAP §0.7.1. May be blank
     * in profiles where the EOD pipeline is not provisioned (e.g., local
     * developer environments without LocalStack Step Functions support);
     * {@link #startEodPipeline(String)} then throws
     * {@link CardDemoException} with reason code
     * {@value #CONFIG_ERROR_REASON_CODE}.
     */
    @Value("${carddemo.aws.stepfunctions.eod-batch-pipeline-arn:}")
    private String eodPipelineArn;

    /**
     * ARN of the file/data provisioning state machine
     * ({@code file-provisioning.asl.json}) used as the destination of
     * {@code report.requested}-triggered orchestration in profiles that
     * route report generation through Step Functions. Externalised via
     * Spring Cloud AWS Parameter Store per AAP §0.7.1. May be blank in
     * profiles where the pipeline is not provisioned;
     * {@link #startReportPipeline(String)} then throws
     * {@link CardDemoException} with reason code
     * {@value #CONFIG_ERROR_REASON_CODE}.
     *
     * <p>The property key matches
     * {@code carddemo.aws.stepfunctions.file-provisioning-arn} declared
     * in {@code application.yml} so that the same ARN is consumed by
     * this adapter and by {@code KafkaEventConsumer}.</p>
     */
    @Value("${carddemo.aws.stepfunctions.file-provisioning-arn:}")
    private String reportPipelineArn;

    /**
     * Constructor — Spring supplies the {@link SfnClient} bean from
     * {@code AwsSdkConfig}.
     *
     * <p>Replaces: JCL job stream sequencing entry-point — the moral
     * equivalent of "wiring up the JES initiator that picks JOBs from
     * the spool". The state-machine ARNs ({@link #eodPipelineArn},
     * {@link #reportPipelineArn}) are field-injected via {@code @Value}
     * rather than passed as constructor parameters so that Spring's
     * property resolution (and Parameter Store integration) resolves them
     * after the bean is constructed — this allows the adapter to be
     * instantiated in tests with a null SfnClient stub and the ARNs set
     * via {@code @TestPropertySource} or field reflection.</p>
     *
     * @param sfnClient the SDK v2 Step Functions client bean; never
     *                  {@code null}
     * @throws NullPointerException if {@code sfnClient} is {@code null}
     */
    public StepFunctionsOrchestrator(SfnClient sfnClient) {
        // Replaces: JES2 initiator wiring + JCL job stream submission per
        // AAP §0.6.3. Pure infrastructure adapter — no business logic.
        if (sfnClient == null) {
            throw new NullPointerException("sfnClient must not be null");
        }
        this.sfnClient = sfnClient;
    }

    // ---------------------------------------------------------------------
    // Public API — schema-mandated per AAP §0.4.1
    // ---------------------------------------------------------------------

    /**
     * Starts a Step Functions state machine execution.
     *
     * <p>Replaces: JES2 job submission via JCL JOB card or
     * {@code CORPT00C WIRTE-JOBSUB-TDQ}. Per AAP §0.6.3 each JCL "JOB"
     * becomes a Step Functions execution invoking a state machine.</p>
     *
     * <p>The unique execution name follows the format
     * {@code carddemo-{epoch-millis}-{short-uuid}}. Step Functions
     * requires unique execution names per state machine within 90 days;
     * the epoch-millis + 8-char UUID suffix make collisions astronomically
     * unlikely even under high concurrency.</p>
     *
     * @param stateMachineArn ARN of the target state machine (e.g., the
     *                        ARN of {@code eod-batch-pipeline}); must not
     *                        be {@code null} or blank
     * @param inputJson       JSON input passed to the state machine
     *                        (replaces JCL {@code SYMBOLIC} parameters such
     *                        as {@code PARM='2022071800'} from
     *                        {@code INTCALC.jcl}); {@code null} is treated
     *                        as an empty JSON object {@code "{}"}
     * @return the execution ARN returned by Step Functions — caller logs
     *         and tracks for subsequent {@link #getExecutionStatus(String)}
     *         queries
     * @throws IllegalArgumentException if {@code stateMachineArn} is
     *                                  {@code null} or blank
     * @throws SfnException             on any Step Functions service error
     *                                  (propagated unchanged per AAP §0.7.1)
     */
    public String startExecution(String stateMachineArn, String inputJson) {
        // Replaces: JES submission via JCL JOB card or CORPT00C WIRTE-JOBSUB-TDQ.
        // Per AAP §0.6.3 each JCL job stream maps to a Step Functions
        // state machine; starting an execution is the moral equivalent
        // of submitting JCL.
        if (stateMachineArn == null || stateMachineArn.isBlank()) {
            throw new IllegalArgumentException("stateMachineArn must not be null/blank");
        }
        // Generate a unique execution name. Step Functions requires unique
        // execution names per state machine within 90 days; the format
        // carddemo-{epoch-millis}-{short-uuid} respects the 80-char limit
        // and [a-zA-Z0-9-_] character set (UUID hyphens are allowed).
        String executionName = buildExecutionName();
        String resolvedInput = (inputJson == null) ? EMPTY_JSON_INPUT : inputJson;

        StartExecutionRequest request = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName)
                .input(resolvedInput)
                .build();

        try {
            StartExecutionResponse response = sfnClient.startExecution(request);
            LOG.info("Step Functions execution started stateMachineArn={} executionArn={} name={}",
                    stateMachineArn, response.executionArn(), executionName);
            return response.executionArn();
        } catch (SfnException e) {
            // Propagate per AAP §0.7.1 — SfnException.awsErrorDetails()
            // carries the AWS service error code (e.g., "InvalidArn",
            // "StateMachineDoesNotExist", "ExecutionLimitExceeded") which
            // the caller (or @ControllerAdvice GlobalExceptionHandler)
            // inspects to render the appropriate operator response.
            LOG.error("Step Functions startExecution FAILED stateMachineArn={} executionName={} cause={}",
                    stateMachineArn, executionName,
                    awsErrorMessage(e), e);
            throw e;
        }
    }

    /**
     * Queries the current status of a previously started execution.
     *
     * <p>Replaces: SDSF/CEMT status display + manual JES inspection by
     * operators. In the source mainframe environment, operators used SDSF
     * ({@code ST} command) or CEMT to view the status of submitted JCL
     * jobs; this method provides the equivalent visibility into
     * Step Functions executions.</p>
     *
     * @param executionArn the ARN returned by
     *                     {@link #startExecution(String, String)} (or one
     *                     of the named-pipeline convenience methods); must
     *                     not be {@code null} or blank
     * @return the current {@link ExecutionStatus} of the execution: one of
     *         {@code RUNNING}, {@code SUCCEEDED}, {@code FAILED},
     *         {@code TIMED_OUT}, {@code ABORTED}, or
     *         {@code PENDING_REDRIVE} (Step Functions Redrive feature)
     * @throws IllegalArgumentException if {@code executionArn} is
     *                                  {@code null} or blank
     * @throws SfnException             on any Step Functions service error
     *                                  (e.g.,
     *                                  {@code ExecutionDoesNotExistException})
     *                                  propagated unchanged per AAP §0.7.1
     */
    public ExecutionStatus getExecutionStatus(String executionArn) {
        // Replaces: SDSF/CEMT status display — operator-visible job state.
        if (executionArn == null || executionArn.isBlank()) {
            throw new IllegalArgumentException("executionArn must not be null/blank");
        }
        DescribeExecutionRequest request = DescribeExecutionRequest.builder()
                .executionArn(executionArn)
                .build();
        try {
            DescribeExecutionResponse response = sfnClient.describeExecution(request);
            LOG.debug("Step Functions status executionArn={} status={} startDate={}",
                    executionArn, response.status(), response.startDate());
            return response.status();
        } catch (SfnException e) {
            LOG.error("Step Functions describeExecution FAILED executionArn={} cause={}",
                    executionArn, awsErrorMessage(e), e);
            throw e;
        }
    }

    /**
     * Convenience starter for the end-of-day batch pipeline
     * (POSTTRAN → INTCALC → COMBTRAN → CREASTMT/TRANREPT parallel).
     *
     * <p>Replaces: full nightly JCL chain
     * ({@code POSTTRAN.jcl} → {@code INTCALC.jcl} →
     * {@code COMBTRAN.jcl} → {@code CREASTMT.JCL} / {@code TRANREPT.jcl}
     * per AAP §0.6.3). The pipeline definition is loaded from
     * {@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}
     * and provisioned by Terraform in
     * {@code infrastructure/terraform/stepfunctions.tf}.</p>
     *
     * <p>The {@code batchRunDate} parameter is serialised as the JSON field
     * {@code "batchRunDate"} in the state-machine input — the moral
     * equivalent of the JCL {@code SYMBOLIC} parameter
     * {@code PARM='2022071800'} from {@code INTCALC.jcl}. The
     * Step Functions Task states inside the pipeline pass this date down
     * to the AWS Batch job-definition environment variables which the
     * Spring Batch jobs read from {@code @Value}-injected fields.</p>
     *
     * @param batchRunDate ISO-8601 batch run date (typically
     *                     {@code YYYY-MM-DD}); passed verbatim into the
     *                     state-machine input JSON. May be {@code null}
     *                     or empty — Step Functions accepts a literal
     *                     string field including empty value.
     * @return the execution ARN returned by Step Functions
     * @throws CardDemoException        with reason code
     *                                  {@value #CONFIG_ERROR_REASON_CODE}
     *                                  if {@code carddemo.aws.stepfunctions.eod-batch-pipeline-arn}
     *                                  is not configured for the active
     *                                  profile (typically a developer
     *                                  environment without provisioned
     *                                  Step Functions)
     * @throws SfnException             on any Step Functions service error
     *                                  (propagated unchanged per AAP §0.7.1)
     */
    public String startEodPipeline(String batchRunDate) {
        // Replaces: full nightly JCL chain — POSTTRAN.jcl → INTCALC.jcl
        // → COMBTRAN.jcl → CREASTMT.JCL/TRANREPT.jcl per AAP §0.6.3.
        if (eodPipelineArn == null || eodPipelineArn.isBlank()) {
            // Per AAP §0.7.2 reason codes are preserved verbatim and
            // propagated by GlobalExceptionHandler into the API response.
            throw new CardDemoException(
                    CONFIG_ERROR_REASON_CODE,
                    "carddemo.aws.stepfunctions.eod-batch-pipeline-arn not configured",
                    null);
        }
        // Build a minimal JSON object {"batchRunDate":"<value>"} — even when
        // the date is null or empty, the state machine receives a
        // syntactically valid input rather than a malformed payload.
        String safeDate = (batchRunDate == null) ? "" : batchRunDate;
        String input = String.format("{\"batchRunDate\":\"%s\"}", escapeJsonString(safeDate));
        LOG.info("Starting EOD pipeline batchRunDate={} arn={}", safeDate, eodPipelineArn);
        return startExecution(eodPipelineArn, input);
    }

    /**
     * Convenience starter for the transaction report pipeline.
     *
     * <p>Replaces: CORPT00C → CICS TDQ JOBS → JES {@code TRANREPT.jcl}
     * submission (AAP §0.1.1 designates this as the sole online-to-batch
     * bridge in the source mainframe environment). In the Java target,
     * the {@code ReportSubmissionService} publishes report requests to
     * MSK topic {@code report.requested}; {@code KafkaEventConsumer}
     * subscribes and invokes this method to start the pipeline.</p>
     *
     * <p>The {@code reportRequestJson} payload typically carries the JCL
     * symbolic equivalents from the source {@code CORPT00C JOB-DATA}
     * template — {@code PARM-START-DATE}, {@code PARM-END-DATE},
     * {@code WS-REPORT-NAME}, and the requesting operator's user ID —
     * all of which the underlying state-machine Task states forward to
     * the {@code CBTRN03C}-equivalent Spring Batch job
     * ({@code TransactionReportJob}).</p>
     *
     * @param reportRequestJson JSON payload containing the report's date
     *                          range, format, requester, and any
     *                          JCL-symbolic equivalents from the CORPT00C
     *                          {@code JOB-DATA} template; {@code null} is
     *                          treated as an empty JSON object
     * @return the execution ARN returned by Step Functions
     * @throws CardDemoException        with reason code
     *                                  {@value #CONFIG_ERROR_REASON_CODE}
     *                                  if {@code carddemo.aws.stepfunctions.file-provisioning-arn}
     *                                  is not configured
     * @throws SfnException             on any Step Functions service error
     *                                  (propagated unchanged per AAP §0.7.1)
     */
    public String startReportPipeline(String reportRequestJson) {
        // Replaces: CORPT00C WIRTE-JOBSUB-TDQ JOB-DATA template → CICS
        // TDQ JOBS → JES TRANREPT.jcl submission (AAP §0.1.1 sole
        // online-to-batch bridge). The MSK topic report.requested carries
        // the message; this method is invoked by KafkaEventConsumer
        // .onReportRequested(...) to complete the bridge.
        if (reportPipelineArn == null || reportPipelineArn.isBlank()) {
            throw new CardDemoException(
                    CONFIG_ERROR_REASON_CODE,
                    "carddemo.aws.stepfunctions.file-provisioning-arn not configured",
                    null);
        }
        LOG.info("Starting report pipeline arn={}", reportPipelineArn);
        return startExecution(reportPipelineArn, reportRequestJson);
    }

    // ---------------------------------------------------------------------
    // Package-private helpers (visible to ad-hoc + production tests)
    // ---------------------------------------------------------------------

    /**
     * Builds a Step Functions-legal execution name of the form
     * {@code carddemo-{epoch-millis}-{short-uuid}}. Length is bounded by
     * the constant prefix (8) + epoch millis (13) + short UUID
     * ({@value #SHORT_UUID_LENGTH}) + 2 separators = ≤ 80 chars (well
     * under the AWS limit).
     *
     * <p>The character set ({@code [a-zA-Z0-9-_]}) is naturally respected
     * by the literal {@code carddemo} prefix, decimal digits in the
     * epoch, and the lowercase hex + hyphen characters from
     * {@link UUID#randomUUID()}.</p>
     *
     * @return a unique execution name; never {@code null}
     */
    String buildExecutionName() {
        // Step Functions requires execution names to be unique per state
        // machine within 90 days. The format carddemo-{epoch-millis}-
        // {short-uuid} provides:
        //   * a sortable timestamp prefix for operator log correlation,
        //   * a UUID-derived suffix for collision-free uniqueness even
        //     under same-millisecond concurrency.
        String shortUuid = UUID.randomUUID().toString().substring(0, SHORT_UUID_LENGTH);
        return "carddemo-" + System.currentTimeMillis() + "-" + shortUuid;
    }

    /**
     * Safely extracts the AWS service error message from an
     * {@link SfnException}. Defensive against malformed responses where
     * {@link SfnException#awsErrorDetails()} could be {@code null} (rare
     * but possible during local mocking).
     *
     * @param e the SDK exception
     * @return the error message, or a fallback constant when unavailable
     */
    private static String awsErrorMessage(SfnException e) {
        if (e == null) {
            return "<no exception>";
        }
        try {
            if (e.awsErrorDetails() != null
                    && e.awsErrorDetails().errorMessage() != null) {
                return e.awsErrorDetails().errorMessage();
            }
        } catch (RuntimeException ignored) {
            // fall through to e.getMessage()
        }
        return e.getMessage();
    }

    /**
     * Minimal JSON string escaping for embedding the batch-run-date into
     * the convenience {@link #startEodPipeline(String)} input.
     *
     * <p>This is NOT a general JSON serializer — it is sufficient for the
     * ISO-8601 date format and other ASCII-clean inputs typically supplied
     * to {@code startEodPipeline}. Callers needing full JSON serialization
     * should use {@link #startExecution(String, String)} directly with a
     * pre-serialized payload from a Jackson {@code ObjectMapper}.</p>
     *
     * @param raw the input string to escape; never {@code null}
     * @return a JSON-safe representation of the input
     */
    private static String escapeJsonString(String raw) {
        // Escape only the minimum set: backslash, double-quote, and
        // control characters that would render the JSON invalid.
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
