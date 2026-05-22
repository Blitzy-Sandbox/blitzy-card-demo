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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sfn.model.SfnException;

import java.util.Objects;

/**
 * Apache Kafka / Amazon MSK consumer adapter.
 *
 * <p>Per AAP &sect;0.6.5 ("MSK Topic Ordering Guarantees") and AAP
 * &sect;0.7.1 ("MSK (Kafka) topics used for all inter-service transaction
 * events"), this adapter hosts the {@link KafkaListener @KafkaListener}
 * endpoints that consume the events produced by {@link KafkaEventPublisher}.
 * The consumer responsibilities are:</p>
 *
 * <ul>
 *   <li><b>{@code report.requested} consumption</b> &mdash; bridges the
 *       online {@code CORPT00C} report-submission flow to the batch report
 *       job by invoking
 *       {@link StepFunctionsOrchestrator#startExecution(String, String, Object)}
 *       on receipt of each report request. This is the central
 *       online-to-batch bridge identified in AAP &sect;0.1.1 ("The sole
 *       online-to-batch bridge (CORPT00C → CICS TDQ JOBS queue → JES
 *       submission) translates to an MSK topic (`report.requested`)
 *       consumed by a Step Functions trigger").</li>
 *   <li><b>{@code transaction.posted} / {@code account.updated} /
 *       {@code ledger.balanced} consumption</b> &mdash; consumed by audit,
 *       reporting, and downstream account-projection services. Default
 *       handlers in this class emit an audit record via
 *       {@link AuditLogService} for traceability; service-specific handlers
 *       in later checkpoints may extend or override this behavior by adding
 *       additional @KafkaListener-annotated methods elsewhere in the
 *       application.</li>
 * </ul>
 *
 * <h2>Acknowledgement contract (AAP &sect;0.6.5)</h2>
 * <p>The listener container factory in {@code KafkaConfig} is configured
 * with {@code AckMode.MANUAL}. Every handler in this class follows the
 * <strong>"acknowledge only after successful processing"</strong> rule:</p>
 * <ol>
 *   <li>Receive the record + {@link Acknowledgment} parameter.</li>
 *   <li>Process the payload (in this adapter, fan out to
 *       {@link StepFunctionsOrchestrator} or {@link AuditLogService}).</li>
 *   <li>On success, call {@code acknowledgment.acknowledge()} to commit the
 *       offset.</li>
 *   <li>On exception, DO NOT acknowledge — the
 *       {@link org.springframework.kafka.listener.DefaultErrorHandler}
 *       configured in {@code KafkaConfig} will retry up to 3 times with
 *       1-second back-off and then route the poison record to
 *       {@code &lt;topic&gt;.DLT} via
 *       {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}.</li>
 * </ol>
 *
 * <p>This pattern guarantees at-least-once delivery semantics. Duplicate
 * delivery on retry is acceptable because every consumer-side state mutation
 * is idempotent (Step Functions execution names are unique per call;
 * OpenSearch document IDs are deterministic).</p>
 *
 * <h2>Thread safety</h2>
 * <p>The Spring Kafka {@code ConcurrentMessageListenerContainer} dispatches
 * each {@code @KafkaListener} method on its own dedicated thread per
 * partition assignment. This adapter is stateless beyond the injected
 * collaborators, all of which are thread-safe singletons.</p>
 *
 * @see KafkaEventPublisher
 * @see com.awsm2.carddemo.config.KafkaConfig
 */
@Component
public class KafkaEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventConsumer.class);

    /**
     * Audit event-name constants — used for OpenSearch dashboard filters and
     * CloudWatch alarm regexes. Stable identifiers; do not rename.
     */
    static final String EVENT_REPORT_REQUESTED_RECEIVED = "REPORT_REQUESTED_RECEIVED";
    static final String EVENT_REPORT_REQUESTED_STARTED  = "REPORT_REQUESTED_STARTED";
    static final String EVENT_REPORT_REQUESTED_FAILED   = "REPORT_REQUESTED_FAILED";

    /** Logical stateMachineId used as the execution-name prefix per AAP §0.6.3. */
    static final String EOD_STATE_MACHINE_ID = "eod-batch-pipeline";

    private final StepFunctionsOrchestrator stepFunctionsOrchestrator;
    private final AuditLogService auditLogService;
    private final String eodStateMachineArn;

    /**
     * Constructor injection — Spring supplies the orchestrator, audit
     * adapter, and resolves the EOD state machine ARN at startup.
     *
     * @param stepFunctionsOrchestrator  the Step Functions adapter; never
     *                                   {@code null}
     * @param auditLogService            the async audit adapter; never
     *                                   {@code null}
     * @param eodStateMachineArn         ARN of the EOD batch state machine
     *                                   ({@code carddemo.aws.stepfunctions.eod-batch-pipeline-arn});
     *                                   may be blank in local profile, in
     *                                   which case
     *                                   {@link #onReportRequested(...)}
     *                                   logs and rejects rather than calling
     *                                   the orchestrator
     */
    public KafkaEventConsumer(
            StepFunctionsOrchestrator stepFunctionsOrchestrator,
            AuditLogService auditLogService,
            @Value("${carddemo.aws.stepfunctions.eod-batch-pipeline-arn:}")
            String eodStateMachineArn) {
        // Replaces: CICS TDQ JOBS dispatcher loop + JES initiator polling
        // (CORPT00C → TDQ → JES) per AAP §0.1.1 online-to-batch bridge.
        this.stepFunctionsOrchestrator = Objects.requireNonNull(stepFunctionsOrchestrator,
                "stepFunctionsOrchestrator must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
        this.eodStateMachineArn = (eodStateMachineArn == null) ? "" : eodStateMachineArn.trim();
    }

    // ---------------------------------------------------------------------
    // Listeners — one @KafkaListener per topic
    // ---------------------------------------------------------------------

    /**
     * Consumes the {@code report.requested} topic and starts a Step
     * Functions execution to run the corresponding batch report job.
     *
     * <p>This is the central online-to-batch bridge — the COBOL source's
     * {@code CORPT00C} program submitted reports via CICS TDQ JOBS, which
     * a JES initiator picked up to spawn a batch job. In the Java target
     * the online {@code ReportSubmissionService} publishes to this topic
     * via {@link KafkaEventPublisher#publishReportRequested(String, Object)};
     * this listener consumes and starts a Step Functions execution that
     * runs the equivalent of the JCL job stream.</p>
     *
     * <p>The Step Functions ARN is resolved at startup from
     * {@code carddemo.aws.stepfunctions.eod-batch-pipeline-arn}. A blank
     * ARN (typically the {@code local} profile) causes the handler to log
     * at {@code WARN} and acknowledge the message without starting an
     * execution &mdash; this keeps local development unblocked when Step
     * Functions are not provisioned.</p>
     *
     * <p>Acknowledgement: the message is acked ONLY after Step Functions
     * acknowledges the StartExecution. {@link SfnException} causes the
     * listener to skip the ack so the
     * {@link org.springframework.kafka.listener.DefaultErrorHandler}
     * retries and ultimately routes to the {@code report.requested.DLT}
     * topic for forensic review.</p>
     *
     * @param payload         the report request payload (forwarded as the
     *                        Step Functions input)
     * @param key             Kafka record key (the report ID or originator
     *                        user ID, used here for log correlation)
     * @param acknowledgment  Spring Kafka acknowledgment handle; must be
     *                        called exactly once on success
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.report-requested:report.requested}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onReportRequested(
            @Payload Object payload,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            Acknowledgment acknowledgment) {

        // COBOL: CORPT00C → CICS TDQ JOBS → JES submission (AAP §0.1.1 bridge).
        // Replaces: TDQ-consumer + JES initiator that historically picked up
        // the report submission and spawned the batch job.
        LOG.info("Kafka onReportRequested topic=report.requested key={}", key);
        auditLogService.auditEvent(EVENT_REPORT_REQUESTED_RECEIVED, key, payloadAsAuditMap(payload));

        // Local-profile escape hatch: if no ARN is configured, ack and exit
        // so developer iteration is not blocked by missing AWS provisioning.
        if (eodStateMachineArn.isBlank()) {
            LOG.warn("No EOD state-machine ARN configured (carddemo.aws.stepfunctions.eod-batch-pipeline-arn); "
                    + "acknowledging report.requested without starting an execution key={}", key);
            acknowledgment.acknowledge();
            return;
        }

        try {
            // Start the Step Functions execution and ONLY THEN acknowledge.
            // A failure here propagates to the DefaultErrorHandler which
            // retries 3x and then routes to report.requested.DLT.
            var response = stepFunctionsOrchestrator.startExecution(
                    eodStateMachineArn,
                    EOD_STATE_MACHINE_ID,
                    payload);
            LOG.info("Started Step Functions execution executionArn={} for report key={}",
                    response.executionArn(), key);
            auditLogService.auditEvent(EVENT_REPORT_REQUESTED_STARTED, key,
                    java.util.Map.of("executionArn", response.executionArn()));
            // Successful processing — commit the offset so the broker
            // moves past this record on the next consumer poll.
            acknowledgment.acknowledge();
        } catch (SfnException e) {
            // Per AAP §0.6.5 — DO NOT ack on failure. The DefaultErrorHandler
            // retries and then routes to report.requested.DLT after 3
            // attempts. Re-throw so Spring sees the failure on this
            // listener invocation.
            LOG.error("Step Functions execution FAILED for report.requested key={} cause={}",
                    key, e.getMessage(), e);
            auditLogService.auditEvent(EVENT_REPORT_REQUESTED_FAILED, key,
                    java.util.Map.of("cause", e.getMessage()));
            throw e;
        }
    }

    /**
     * Consumes the {@code transaction.posted} topic for audit emission.
     *
     * <p>Service-specific projections (e.g., daily transaction aggregates)
     * can attach additional listeners with different consumer groups. This
     * default listener exists so every {@code transaction.posted} event
     * lands in the OpenSearch audit index without requiring a downstream
     * service to be online.</p>
     *
     * @param record         the full Kafka {@link ConsumerRecord} (so the
     *                       handler has access to topic, partition,
     *                       offset, headers if needed for the audit trail)
     * @param acknowledgment manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.transaction-posted:transaction.posted}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTransactionPosted(ConsumerRecord<String, Object> record,
                                    Acknowledgment acknowledgment) {
        // Replaces: CBTRN02C audit lines + downstream batch jobs that
        // historically read the daily transaction file directly.
        LOG.info("Kafka onTransactionPosted topic={} partition={} offset={} key={}",
                record.topic(), record.partition(), record.offset(), record.key());
        try {
            auditLogService.auditTransaction("TRANSACTION_POSTED",
                    record.key(),
                    record.key(), // partition key is the account ID, not the txn ID
                    payloadAsAuditMap(record.value()));
            acknowledgment.acknowledge();
        } catch (RuntimeException e) {
            LOG.error("Failure handling transaction.posted topic={} key={} cause={}",
                    record.topic(), record.key(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Consumes the {@code account.updated} topic for audit emission.
     *
     * @param record         the full Kafka record
     * @param acknowledgment manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.account-updated:account.updated}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onAccountUpdated(ConsumerRecord<String, Object> record,
                                 Acknowledgment acknowledgment) {
        // Replaces: COACTUPC + CBACT04C audit lines.
        LOG.info("Kafka onAccountUpdated topic={} key={}", record.topic(), record.key());
        try {
            auditLogService.auditEvent("ACCOUNT_UPDATED", record.key(),
                    payloadAsAuditMap(record.value()));
            acknowledgment.acknowledge();
        } catch (RuntimeException e) {
            LOG.error("Failure handling account.updated key={} cause={}",
                    record.key(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Consumes the {@code ledger.balanced} topic for audit emission.
     *
     * @param record         the full Kafka record
     * @param acknowledgment manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.ledger-balanced:ledger.balanced}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onLedgerBalanced(ConsumerRecord<String, Object> record,
                                 Acknowledgment acknowledgment) {
        // Replaces: end-of-day balancing audit lines in the COBOL batch chain.
        LOG.info("Kafka onLedgerBalanced topic={} key={}", record.topic(), record.key());
        try {
            auditLogService.auditEvent("LEDGER_BALANCED", record.key(),
                    payloadAsAuditMap(record.value()));
            acknowledgment.acknowledge();
        } catch (RuntimeException e) {
            LOG.error("Failure handling ledger.balanced key={} cause={}",
                    record.key(), e.getMessage(), e);
            throw e;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Coerces an arbitrary payload object into a {@code Map<String, Object>}
     * suitable for {@link AuditLogService#auditEvent}. Maps pass through
     * unchanged; other types are wrapped under {@code "value"} so the audit
     * record always has a uniform shape.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> payloadAsAuditMap(Object payload) {
        if (payload == null) {
            return java.util.Collections.emptyMap();
        }
        if (payload instanceof java.util.Map<?, ?> map) {
            return (java.util.Map<String, Object>) map;
        }
        return java.util.Map.of("value", payload);
    }
}
