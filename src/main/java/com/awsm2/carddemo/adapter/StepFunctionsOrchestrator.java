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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import java.util.Objects;
import java.util.UUID;

/**
 * AWS Step Functions orchestrator adapter — starts state machine executions
 * for the CardDemo batch and online-to-batch flows.
 *
 * <p>Per AAP &sect;0.7.1 ("Isolate all AWS service integrations in dedicated
 * adapter classes &mdash; never inline AWS SDK calls in business logic") and
 * AAP &sect;0.6.3 ("JCL → Step Functions Orchestration"), this adapter is
 * the sole entry point for {@link SfnClient#startExecution} invocations. The
 * methods exposed here are used by:</p>
 *
 * <ul>
 *   <li>{@link KafkaEventConsumer#onReportRequested} &mdash; bridges the
 *       online report submission flow to the batch report job by starting
 *       the {@code eod-batch-pipeline} (or a report-specific) state machine
 *       execution.</li>
 *   <li>{@code ReportSubmissionService} &mdash; convenience helper for
 *       services that publish directly via REST rather than via the Kafka
 *       bridge.</li>
 *   <li>Operational tooling that needs to launch the EOD pipeline on demand
 *       (e.g., a recovery job after a missed schedule).</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.6.3)</h2>
 * <p>Replaces: JCL job-stream submission via JES (the COBOL source's
 * end-of-day batch chain documented in {@code README.md} Running-full-batch:
 * POSTTRAN → INTCALC → COMBTRAN → CREASTMT / TRANREPT). The chain is now
 * a single {@code eod-batch-pipeline.asl.json} state machine definition
 * loaded under {@code src/main/resources/stepfunctions/} and provisioned
 * by Terraform in {@code infrastructure/terraform/stepfunctions.tf}. Starting
 * an execution corresponds to a JES {@code $S} job-submission command.</p>
 *
 * <h2>Execution naming (AAP &sect;0.6.3)</h2>
 * <p>Each execution receives a unique, deterministic-prefix name of the form
 * {@code "<stateMachineId>-<epochMillis>-<uuid>"}. The stateMachineId prefix
 * makes executions easy to filter in the Step Functions console; the epoch
 * timestamp gives ordering across re-runs; the UUID guarantees uniqueness
 * even if two executions are started in the same millisecond. Step Functions
 * enforces a 1&ndash;80-character bound and an
 * {@code [a-zA-Z0-9-_]} character set on execution names &mdash; the chosen
 * format respects both.</p>
 *
 * <h2>Failure handling</h2>
 * <p>{@link SfnException} (the SDK v2 base for all Step Functions service
 * errors &mdash; including {@code ExecutionAlreadyExistsException},
 * {@code InvalidArnException}, {@code InvalidExecutionInputException},
 * {@code InvalidNameException}, {@code StateMachineDeletingException}, and
 * {@code StateMachineDoesNotExistException}) is propagated unchanged to the
 * caller. The adapter does not retry or fall back &mdash; rerunning a
 * failed StartExecution is an operational decision that depends on the
 * specific error code, and the SDK already implements a default exponential
 * backoff for transient throttling.</p>
 *
 * @see com.awsm2.carddemo.config.AwsSdkConfig#sfnClient()
 */
@Component
public class StepFunctionsOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(StepFunctionsOrchestrator.class);

    /**
     * Execution-name format: stateMachineId prefix + epoch-millis + UUID.
     * Step Functions allows {@code [a-zA-Z0-9-_]} in execution names, so
     * the UUID's hyphens are preserved while no additional separator is
     * needed beyond the simple {@code "-"} delimiter.
     */
    private static final String EXECUTION_NAME_FORMAT = "%s-%d-%s";

    /**
     * Step Functions enforces an 80-character maximum on execution names.
     * After accounting for the epoch millis (13 chars) and UUID (36 chars),
     * the stateMachineId prefix is bounded to leave headroom. Anything
     * over this is truncated with a deterministic suffix to keep the name
     * legal while preserving the unique UUID portion.
     */
    private static final int MAX_EXECUTION_NAME_LENGTH = 80;

    private final SfnClient sfnClient;
    private final ObjectMapper objectMapper;

    /**
     * Constructor injection. {@link SfnClient} is the SDK v2 Step Functions
     * client bean produced by {@code AwsSdkConfig}; {@link ObjectMapper} is
     * Spring Boot's auto-configured Jackson mapper.
     *
     * @param sfnClient    the SDK v2 client bean; never {@code null}
     * @param objectMapper Jackson mapper for serializing the input payload;
     *                     never {@code null}
     */
    public StepFunctionsOrchestrator(SfnClient sfnClient, ObjectMapper objectMapper) {
        // Replaces: JES $S job-submission command + JCL job-stream sequencing
        // per AAP §0.6.3. Pure infrastructure adapter — no business logic.
        this.sfnClient = Objects.requireNonNull(sfnClient, "sfnClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper,
                "objectMapper must not be null");
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Starts a Step Functions state machine execution.
     *
     * <p>The supplied {@code stateMachineArn} should be a fully-qualified
     * ARN (e.g.,
     * {@code arn:aws:states:us-east-1:123456789012:stateMachine:eod-batch-pipeline});
     * the value is typically sourced from
     * {@code carddemo.aws.stepfunctions.eod-batch-pipeline-arn} or
     * {@code carddemo.aws.stepfunctions.file-provisioning-arn} in
     * {@code application*.yml}.</p>
     *
     * <p>The {@code stateMachineId} parameter is the short logical name
     * used as the execution-name prefix (e.g., {@code "eod-batch-pipeline"});
     * it is independent of the ARN and is human-readable.</p>
     *
     * @param stateMachineArn  ARN of the state machine to invoke; must not
     *                         be {@code null} or blank
     * @param stateMachineId   short logical name used as the execution-name
     *                         prefix; must not be {@code null} or blank
     * @param input            arbitrary JSON-serializable input passed to
     *                         the state machine; may be {@code null} (Step
     *                         Functions accepts an absent input)
     * @return the SDK {@link StartExecutionResponse} (carrying the
     *         executionArn and startDate)
     * @throws IllegalArgumentException if {@code stateMachineArn} or
     *                                  {@code stateMachineId} is
     *                                  {@code null}/blank
     * @throws SfnException             on any Step Functions service error
     */
    public StartExecutionResponse startExecution(String stateMachineArn,
                                                 String stateMachineId,
                                                 Object input) {
        // Replaces: JES $S [JOB] command for the corresponding JCL job stream.
        // Per AAP §0.6.3 each batch chain (EOD, provisioning) maps to a
        // Step Functions state machine; calling startExecution is the
        // moral equivalent of submitting the JCL.
        if (stateMachineArn == null || stateMachineArn.isBlank()) {
            throw new IllegalArgumentException("stateMachineArn must not be null/blank");
        }
        if (stateMachineId == null || stateMachineId.isBlank()) {
            throw new IllegalArgumentException("stateMachineId must not be null/blank");
        }

        // Build a unique execution name with the required prefix-epoch-UUID
        // format. Truncate from the prefix end if the combined value would
        // exceed 80 chars — the UUID portion is preserved for uniqueness.
        String executionName = buildExecutionName(stateMachineId);
        String inputJson = serializeInput(input);

        StartExecutionRequest.Builder requestBuilder = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName);
        if (inputJson != null) {
            requestBuilder.input(inputJson);
        }
        try {
            StartExecutionResponse response = sfnClient.startExecution(requestBuilder.build());
            LOG.info("Step Functions StartExecution OK arn={} name={} executionArn={} startDate={}",
                    stateMachineArn,
                    executionName,
                    response.executionArn(),
                    response.startDate());
            return response;
        } catch (SfnException e) {
            // Propagate per AAP §0.7.1 — SfnException carries
            // awsErrorDetails() that the caller (or @ControllerAdvice) can
            // inspect to render the appropriate operator response.
            LOG.error("Step Functions StartExecution FAILED arn={} name={} cause={}",
                    stateMachineArn, executionName, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Variant of {@link #startExecution(String, String, Object)} that
     * accepts an already-serialized JSON input string. Useful when the
     * caller has its own JSON object (e.g., a {@code Map} that should be
     * serialized via the application's configured Jackson mapper at the
     * caller side, perhaps with non-default modules).
     *
     * @param stateMachineArn  ARN of the state machine
     * @param stateMachineId   short logical name for the execution-name prefix
     * @param inputJson        pre-serialized JSON input string; may be
     *                         {@code null} or blank
     * @return the StartExecutionResponse from the SDK
     * @throws IllegalArgumentException if {@code stateMachineArn} or
     *                                  {@code stateMachineId} is
     *                                  {@code null}/blank
     * @throws SfnException on Step Functions service errors
     */
    public StartExecutionResponse startExecutionWithJson(String stateMachineArn,
                                                         String stateMachineId,
                                                         String inputJson) {
        // Replaces: parameterized JES $S [JOB,'PARM=value'] — JCL PARM
        // strings now translate to JSON input.
        if (stateMachineArn == null || stateMachineArn.isBlank()) {
            throw new IllegalArgumentException("stateMachineArn must not be null/blank");
        }
        if (stateMachineId == null || stateMachineId.isBlank()) {
            throw new IllegalArgumentException("stateMachineId must not be null/blank");
        }

        String executionName = buildExecutionName(stateMachineId);
        StartExecutionRequest.Builder requestBuilder = StartExecutionRequest.builder()
                .stateMachineArn(stateMachineArn)
                .name(executionName);
        if (inputJson != null && !inputJson.isBlank()) {
            requestBuilder.input(inputJson);
        }
        try {
            StartExecutionResponse response = sfnClient.startExecution(requestBuilder.build());
            LOG.info("Step Functions StartExecution OK arn={} name={} executionArn={}",
                    stateMachineArn, executionName, response.executionArn());
            return response;
        } catch (SfnException e) {
            LOG.error("Step Functions StartExecution FAILED arn={} name={} cause={}",
                    stateMachineArn, executionName, e.getMessage(), e);
            throw e;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Builds a Step Functions-legal execution name of the form
     * {@code "<stateMachineId>-<epochMillis>-<uuid>"}. Truncates the
     * stateMachineId prefix if necessary to fit within
     * {@link #MAX_EXECUTION_NAME_LENGTH}.
     *
     * @param stateMachineId the logical prefix
     * @return a unique execution name; never {@code null}; always
     *         {@code &le;} 80 characters
     */
    private String buildExecutionName(String stateMachineId) {
        // Replaces: JES JOB names — Step Functions execution names are
        // bounded to 80 chars and may contain only [a-zA-Z0-9-_].
        long epochMillis = System.currentTimeMillis();
        String uuid = UUID.randomUUID().toString(); // 36 chars including hyphens
        String safePrefix = sanitizeForExecutionName(stateMachineId);

        // Compute the maximum allowable prefix length: 80 - 1 (sep) - 13 (epoch)
        // - 1 (sep) - 36 (uuid) = 29 chars headroom for the stateMachineId.
        int epochLen = Long.toString(epochMillis).length();
        int maxPrefixLen = MAX_EXECUTION_NAME_LENGTH - epochLen - uuid.length() - 2;
        if (safePrefix.length() > maxPrefixLen && maxPrefixLen > 0) {
            safePrefix = safePrefix.substring(0, maxPrefixLen);
        }
        return String.format(EXECUTION_NAME_FORMAT, safePrefix, epochMillis, uuid);
    }

    /**
     * Step Functions execution names must contain only {@code [a-zA-Z0-9-_]}
     * characters. Replaces any disallowed character with {@code '_'}.
     * Defensive — typical state-machine IDs already comply.
     */
    private static String sanitizeForExecutionName(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    /**
     * Serializes the supplied input to JSON via the injected Jackson
     * {@link ObjectMapper}. A {@code null} input yields {@code null}
     * (Step Functions accepts an absent input). Serialization failures
     * surface as {@link IllegalArgumentException} (the supplied object is
     * not JSON-serializable, which is a caller bug).
     */
    private String serializeInput(Object input) {
        if (input == null) {
            return null;
        }
        if (input instanceof String s) {
            // A caller-supplied string is treated as already-serialized JSON
            // for symmetry with startExecutionWithJson(); this avoids
            // accidentally double-encoding ("foo" → "\"foo\"").
            return s;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize Step Functions input as JSON: " + e.getMessage(), e);
        }
    }
}
