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

// Replaces: CICS Transient Data Queue (TDQ) consumer pattern — JOBS queue
// in CORPT00C and audit log consumers in batch programs (AAP §0.1.1).

import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.CardDemoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Amazon MSK / Apache Kafka consumer adapter for CardDemo transaction event
 * topics.
 *
 * <p><b>Replaces:</b> CICS Transient Data Queue (TDQ) consumer pattern —
 * specifically the {@code JOBS} queue consumed by JES2 after
 * {@code app/cbl/CORPT00C.cbl} paragraph {@code WIRTE-JOBSUB-TDQ} (line 515
 * of the source) issues
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)} to write
 * 80-byte JCL records. The sole online-to-batch bridge identified in AAP
 * &sect;0.1.1: <em>"the sole online-to-batch bridge (CORPT00C → CICS TDQ
 * JOBS queue → JES submission) translates to an MSK topic
 * ({@code report.requested}) consumed by a Step Functions trigger that
 * submits an AWS Batch job — preserving the asynchronous decoupling."</em>
 * In addition to that central bridge, this adapter consumes the
 * {@code transaction.posted}, {@code account.updated}, and
 * {@code ledger.balanced} topics that replace downstream consumers of
 * {@code WRITE TRAN-RECORD} in {@code CBTRN02C}, {@code COBIL00C},
 * {@code COTRN02C}, and {@code CBACT04C} as well as
 * {@code REWRITE ACCT-RECORD} in {@code COACTUPC},
 * {@code COBIL00C}, and {@code CBACT04C}.</p>
 *
 * <p>Per AAP &sect;0.7.1 ("Isolate all AWS service integrations in
 * dedicated adapter classes — never inline AWS SDK calls in business
 * logic"), this class is the only place in
 * {@code src/main/java/com/awsm2/carddemo/} (other than
 * {@link KafkaEventPublisher}) that interacts with the Spring Kafka API.
 * Service classes and batch jobs MUST NOT register their own
 * {@code @KafkaListener} methods — they delegate to this adapter via
 * Spring's bean container.</p>
 *
 * <h2>Topics consumed (AAP &sect;0.6.5)</h2>
 * <ol>
 *   <li>{@code transaction.posted} &mdash; downstream audit and reporting
 *       consumer of {@link KafkaEventPublisher#publishTransactionPosted}
 *       events emitted by every {@code WRITE TRAN-RECORD} producer in
 *       the source. Payload deserialised to {@link TransactionAddDto}.</li>
 *   <li>{@code account.updated} &mdash; downstream audit consumer of
 *       {@link KafkaEventPublisher#publishAccountUpdated} events emitted by
 *       every {@code REWRITE ACCT-RECORD} producer in the source. Payload
 *       deserialised to {@link AccountUpdateDto}.</li>
 *   <li>{@code ledger.balanced} &mdash; end-of-day reconciliation event
 *       (no native record-layout equivalent in the source; the COBOL
 *       analogue is the SYSPRINT trailer of the
 *       {@code POSTTRAN} / {@code COMBTRAN} job chain). Payload typed as
 *       {@link Object} because the publisher contract is generic and the
 *       consumer only emits an audit event.</li>
 *   <li>{@code report.requested} &mdash; the online-to-batch bridge
 *       (AAP &sect;0.1.1). Consumed events trigger a Step Functions
 *       execution via {@link StepFunctionsOrchestrator#startExecution} that
 *       in turn submits the AWS Batch report job (the cloud-native
 *       equivalent of the JES2 initiator that historically read the
 *       {@code JOBS} TDQ and ran {@code app/jcl/TRANREPT.jcl}). Payload
 *       deserialised to {@link ReportRequestDto}.</li>
 * </ol>
 *
 * <h2>Per-account ordering invariant (AAP &sect;0.6.5)</h2>
 * <p>The Kafka producer ({@link KafkaEventPublisher}) keys every transaction
 * and account event by the originating 11-digit account ID. Kafka's default
 * partitioner (murmur2) maps the same key to the same partition, so all
 * events for a single account land on a single partition and are consumed
 * in producer-emit order even with multiple concurrent producers. AAP
 * &sect;0.6.5: <em>"per-account ordering guaranteed by partition-by-account-id
 * on producer side"</em>.</p>
 *
 * <h2>Acknowledgement contract (AAP &sect;0.6.5)</h2>
 * <p>The listener container factory (
 * {@code com.awsm2.carddemo.config.KafkaConfig#kafkaListenerContainerFactory})
 * is configured with {@code enable.auto.commit=false} and
 * {@code AckMode.MANUAL}. Every handler in this class follows the
 * <strong>"acknowledge only after successful processing"</strong> rule:</p>
 * <ol>
 *   <li>Receive the deserialised payload, the partition-key header (account
 *       ID), the topic/partition/offset metadata headers, and the
 *       {@link Acknowledgment} handle.</li>
 *   <li>Emit an INFO-level structured log line with topic / partition /
 *       offset / account_id for traceability.</li>
 *   <li>Delegate to {@link AuditLogService} to record the consumed event in
 *       OpenSearch (AAP &sect;0.6.6 audit trail requirement).</li>
 *   <li>For {@code report.requested} only: invoke
 *       {@link StepFunctionsOrchestrator#startExecution(String, String)} to
 *       start the report pipeline and emit a second audit record carrying
 *       the started {@code executionArn}.</li>
 *   <li>On success, call {@code ack.acknowledge()} to commit the offset.</li>
 *   <li>On {@link CardDemoException} (a recoverable, domain-meaningful
 *       failure that preserves a verbatim COBOL reason code per AAP
 *       &sect;0.7.2): DO NOT acknowledge — the container's
 *       {@link org.springframework.kafka.listener.DefaultErrorHandler}
 *       (3 retries with 1-second back-off, then route to {@code &lt;topic&gt;.DLT}
 *       via {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer})
 *       handles redelivery and dead-letter routing per AAP &sect;0.6.6.</li>
 *   <li>On any other unrecoverable {@link Exception} (e.g.,
 *       {@link NullPointerException}, deserialisation residue): log at ERROR
 *       with topic / partition / offset / account_id context, then
 *       {@code ack.acknowledge()} to skip the poison message. The error log
 *       line is the permanent audit record; future iterations may route
 *       these to the DLT explicitly by re-throwing instead of acking, but
 *       per the current AAP discipline (Minimal Change Clause) the ack
 *       path is preserved.</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * <p>The Spring Kafka {@code ConcurrentMessageListenerContainer} dispatches
 * each {@code @KafkaListener} method on its own dedicated thread per
 * partition assignment. This adapter is stateless beyond the injected
 * collaborators ({@link StepFunctionsOrchestrator},
 * {@link AuditLogService}) and the immutable
 * {@link #reportPipelineStateMachineArn} field resolved at startup. All
 * injected collaborators are thread-safe singletons.</p>
 *
 * @see KafkaEventPublisher
 * @see com.awsm2.carddemo.config.KafkaConfig
 * @see StepFunctionsOrchestrator
 * @see AuditLogService
 */
@Component
public class KafkaEventConsumer {

    // -------------------------------------------------------------------------
    // Logger — structured JSON logging per AAP §0.6.6 observability rules.
    // -------------------------------------------------------------------------
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventConsumer.class);

    // -------------------------------------------------------------------------
    // Event-name constants — stable identifiers used by OpenSearch dashboard
    // filters and CloudWatch alarm regexes. Renaming any of these is a
    // breaking change for downstream consumers.
    // -------------------------------------------------------------------------

    /** Audit event name emitted on successful {@code transaction.posted} consumption. */
    static final String EVENT_TRANSACTION_POSTED = "TRANSACTION_POSTED";

    /** Audit event name emitted on successful {@code account.updated} consumption. */
    static final String EVENT_ACCOUNT_UPDATED = "ACCOUNT_UPDATED";

    /** Audit event name emitted on successful {@code ledger.balanced} consumption. */
    static final String EVENT_LEDGER_BALANCED = "LEDGER_BALANCED";

    /** Audit event name emitted on inbound {@code report.requested} receipt. */
    static final String EVENT_REPORT_REQUESTED_RECEIVED = "REPORT_REQUESTED_RECEIVED";

    /**
     * Audit event name emitted after a successful
     * {@link StepFunctionsOrchestrator#startExecution(String, String)} call —
     * carries the started {@code executionArn} for end-to-end correlation
     * back to the inbound {@code report.requested} envelope.
     */
    static final String EVENT_REPORT_REQUESTED_STARTED = "REPORT_REQUESTED_STARTED";

    /** Resource-type tag used on {@code logAuditEvent} for report.requested events. */
    static final String RESOURCE_TYPE_REPORT = "REPORT";

    /** Operator code used when the inbound event carries no explicit operator. */
    static final String OPERATOR_KAFKA_CONSUMER = "KAFKA_CONSUMER";

    /**
     * JSON literal returned by the report-pipeline serialiser when the
     * inbound {@link ReportRequestDto} is {@code null} — Step Functions
     * accepts an empty JSON object as input.
     */
    static final String EMPTY_JSON_OBJECT = "{}";

    // -------------------------------------------------------------------------
    // Collaborators — constructor-injected (AAP §0.7.1 dependency injection
    // for loose coupling).
    // -------------------------------------------------------------------------

    /**
     * AWS Step Functions orchestration adapter. Invoked by
     * {@link #onReportRequested} to start the report-pipeline state machine
     * (replaces JES2 initiator polling per AAP §0.1.1).
     */
    private final StepFunctionsOrchestrator stepFunctionsOrchestrator;

    /**
     * CloudTrail / OpenSearch audit log adapter. Invoked by every listener
     * method to record consumed events for compliance per AAP §0.6.6.
     */
    private final AuditLogService auditLogService;

    /**
     * ARN of the Step Functions state machine that fulfils
     * {@code report.requested} events — externalised via Spring Cloud AWS
     * Parameter Store / Secrets Manager per AAP §0.7.1 so it can be
     * rotated without code changes.
     *
     * <p>The property key matches the one declared in the agent prompt
     * ({@code carddemo.stepfunctions.report-pipeline-arn}) with a fallback
     * chain that resolves to the existing
     * {@code carddemo.aws.stepfunctions.file-provisioning-arn} property
     * declared in {@code application.yml} (which corresponds to
     * {@code StepFunctionsOrchestrator}'s {@code reportPipelineArn} field).
     * A blank value (typically the {@code local} profile when Step
     * Functions are not provisioned) causes {@link #onReportRequested} to
     * log at WARN and acknowledge the message without starting an
     * execution — this keeps local development unblocked.</p>
     */
    @Value("${carddemo.stepfunctions.report-pipeline-arn:${carddemo.aws.stepfunctions.file-provisioning-arn:}}")
    private String reportPipelineStateMachineArn;

    /**
     * Constructor — Spring DI supplies the orchestrator and audit adapter
     * beans. Both must be non-{@code null}; explicit
     * {@link Objects#requireNonNull(Object, String)} guards protect unit
     * tests, ad-hoc instantiations, and bean-definition errors that would
     * otherwise surface as opaque {@link NullPointerException}s at first
     * use.
     *
     * <p>Per AAP §0.7.1: <em>"Dependency injection for loose coupling"</em>.
     * The {@link #reportPipelineStateMachineArn} is field-injected via
     * {@code @Value} rather than constructor-injected so that the property
     * resolution / Parameter Store integration happens after the bean is
     * constructed — this allows the adapter to be instantiated in unit
     * tests with mocked collaborators and the ARN set via
     * {@code @TestPropertySource} or field reflection.</p>
     *
     * @param stepFunctionsOrchestrator the Step Functions adapter; never
     *                                  {@code null}
     * @param auditLogService           the audit log adapter; never
     *                                  {@code null}
     * @throws NullPointerException if either collaborator is {@code null}
     */
    public KafkaEventConsumer(StepFunctionsOrchestrator stepFunctionsOrchestrator,
                              AuditLogService auditLogService) {
        // Replaces: CICS TDQ JOBS dispatcher loop + JES initiator polling
        // (CORPT00C → TDQ → JES) per AAP §0.1.1 online-to-batch bridge.
        // AAP §0.6.5: per-account ordering guaranteed by partition-by-account-id
        // on producer side; consumer-side commit is manual per AckMode.MANUAL.
        this.stepFunctionsOrchestrator = Objects.requireNonNull(stepFunctionsOrchestrator,
                "stepFunctionsOrchestrator must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    // =========================================================================
    // Listeners — schema-mandated public API (AAP §0.4.1).
    //
    // Every listener method shares the same six-parameter shape:
    //   - @Payload <DtoType> event           — deserialised Kafka record value
    //   - @Header(RECEIVED_KEY) String accountId  — partition key = account ID
    //   - @Header(RECEIVED_TOPIC) String topic    — source topic name
    //   - @Header(RECEIVED_PARTITION) int partition — source partition index
    //   - @Header(OFFSET) long offset         — record offset within partition
    //   - Acknowledgment ack                  — manual offset-commit handle
    //
    // Per AAP §0.6.5 ack.acknowledge() is called ONLY on the happy path.
    // =========================================================================

    /**
     * Consumes the {@code transaction.posted} topic and records an audit
     * event for each consumed transaction.
     *
     * <p>Replaces: downstream consumers of {@code WRITE TRAN-RECORD} in
     * {@code app/cbl/CBTRN02C.cbl} (paragraph
     * {@code 2900-WRITE-TRANSACTION-FILE}, line 562), {@code COBIL00C.cbl}
     * (paragraph {@code WRITE-TRANSACT-FILE}, line 510), and
     * {@code COTRN02C.cbl}. In the source these programs wrote 350-byte
     * {@code TRAN-RECORD} entries to the {@code TRANSACT} VSAM KSDS; in
     * the Java target the producer
     * ({@link KafkaEventPublisher#publishTransactionPosted}) republishes
     * each post as a {@code transaction.posted} event keyed by account ID
     * for downstream audit + projection consumers.</p>
     *
     * <p>The audit emission path uses
     * {@link AuditLogService#logTransactionEvent} (the schema-required
     * transaction-lifecycle path) which preserves verbatim reject codes
     * 100–109 per AAP &sect;0.7.2 — the consumer-side audit is the second
     * line of defence for the COBOL audit-trail rule
     * ("audit trail generation must be preserved exactly"). The originating
     * service is responsible for the first line of defence at the producer
     * side; this consumer-side emission catches any consumer that has its
     * own auditing concerns (e.g., a regulatory-reporting consumer that
     * runs against {@code transaction.posted} independently of the original
     * publisher).</p>
     *
     * @param event     the deserialised {@link TransactionAddDto} payload
     * @param accountId the 11-digit account ID (Kafka partition key); used
     *                  to preserve per-account ordering per AAP &sect;0.6.5
     * @param topic     the source topic name (typically
     *                  {@code transaction.posted})
     * @param partition the source partition index
     * @param offset    the record offset within the partition
     * @param ack       the manual acknowledgment handle; must be called
     *                  exactly once on success
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.transaction-posted:transaction.posted}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo-consumer-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onTransactionPosted(
            @Payload TransactionAddDto event,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String accountId,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) int partition,
            @Header(value = KafkaHeaders.OFFSET, required = false) long offset,
            Acknowledgment ack) {

        // Consumes events that replace COBOL WRITE TRAN-RECORD in CBTRN02C
        // (line 562), COBIL00C (line 510), and COTRN02C — see AAP §0.4.1
        // transformation row for transaction.posted.
        // AAP §0.6.5: per-account ordering guaranteed by partition-by-account-id
        // on producer side.

        LOG.info("Kafka onTransactionPosted topic={} partition={} offset={} account_id={}",
                topic, partition, offset, accountId);

        try {
            // Delegate to logTransactionEvent — the schema-required transaction
            // audit path that preserves verbatim transaction IDs and reject
            // codes (AAP §0.7.2 "preserve audit trail content exactly").
            // The reasonCode argument is null on a successful post; if a
            // future iteration needs to surface a reject code on consumption,
            // it can be propagated via a typed payload field.
            auditLogService.logTransactionEvent(
                    null,                                   // transactionId — not in TransactionAddDto (id assigned on persist)
                    parseAccountId(accountId),              // numeric form of partition key
                    OPERATOR_KAFKA_CONSUMER,                // synthetic operator marker
                    EVENT_TRANSACTION_POSTED,               // event_type discriminator
                    null,                                   // reasonCode — null on success
                    buildTransactionPayload(event,
                            topic, partition, offset, accountId),
                    null                                    // correlationId — none on this path
            );

            // AAP §0.6.5: ack.acknowledge() ONLY after successful processing.
            ack.acknowledge();
        } catch (CardDemoException e) {
            // Recoverable domain failure — preserve verbatim reason code per
            // AAP §0.7.2. DO NOT acknowledge: the container's
            // DefaultErrorHandler (3 retries with 1-second back-off, then
            // DLT) handles redelivery and dead-letter routing.
            LOG.error(
                    "CardDemoException handling transaction.posted topic={} partition={} offset={} "
                            + "account_id={} reasonCode={} cause={}",
                    topic, partition, offset, accountId,
                    e.getReasonCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            // Unrecoverable poison-message error (e.g., NullPointerException,
            // residual deserialisation issue). Log at ERROR with full
            // operational context and acknowledge to skip — the error log
            // line is the permanent audit record.
            LOG.error(
                    "Unrecoverable error handling transaction.posted topic={} partition={} offset={} "
                            + "account_id={} cause={}",
                    topic, partition, offset, accountId, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    /**
     * Consumes the {@code account.updated} topic and records an audit
     * event for each consumed account-mutation.
     *
     * <p>Replaces: downstream consumers of {@code REWRITE ACCT-RECORD} in
     * {@code app/cbl/COACTUPC.cbl} (paragraph
     * {@code 9700-REWRITE-ACCTDAT-FILE}, the only program in the source
     * that uses {@code SYNCPOINT ROLLBACK} — see AAP &sect;0.1.1),
     * {@code COBIL00C.cbl} (paragraph {@code UPDATE-ACCTDAT-FILE}, called
     * from line 235), and {@code CBACT04C.cbl} (interest-accrual REWRITE).
     * In the source these programs rewrote 300-byte
     * {@code ACCOUNT-RECORD} entries in the {@code ACCTDAT} VSAM KSDS;
     * the producer {@link KafkaEventPublisher#publishAccountUpdated}
     * republishes each rewrite as an {@code account.updated} event keyed
     * by account ID.</p>
     *
     * @param event     the deserialised {@link AccountUpdateDto} payload
     * @param accountId the 11-digit account ID (partition key)
     * @param topic     the source topic name (typically
     *                  {@code account.updated})
     * @param partition the source partition index
     * @param offset    the record offset within the partition
     * @param ack       the manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.account-updated:account.updated}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo-consumer-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onAccountUpdated(
            @Payload AccountUpdateDto event,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String accountId,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) int partition,
            @Header(value = KafkaHeaders.OFFSET, required = false) long offset,
            Acknowledgment ack) {

        // Consumes events that replace COBOL REWRITE ACCT-RECORD in COACTUPC
        // (9700-REWRITE-ACCTDAT-FILE, the only source program with
        // SYNCPOINT ROLLBACK), COBIL00C (UPDATE-ACCTDAT-FILE), and CBACT04C
        // — see AAP §0.4.1 transformation row for account.updated.
        // AAP §0.6.5: per-account ordering guaranteed by partition-by-account-id
        // on producer side.

        LOG.info("Kafka onAccountUpdated topic={} partition={} offset={} account_id={}",
                topic, partition, offset, accountId);

        try {
            // Use logTransactionEvent because account.updated events also
            // belong to the transaction-lifecycle audit class — they reflect
            // a mutation that affects financial state and therefore must
            // share the transaction audit index for regulator-friendly
            // searchability. The schema purpose statement for
            // AuditLogService notes:
            //   "uses logTransactionEvent for transaction/account/ledger events"
            auditLogService.logTransactionEvent(
                    null,                                   // transactionId — none on raw account update
                    extractAccountIdLong(event, accountId), // entity Long ID has priority over header
                    OPERATOR_KAFKA_CONSUMER,
                    EVENT_ACCOUNT_UPDATED,
                    null,                                   // reasonCode — null on success
                    buildAccountPayload(event,
                            topic, partition, offset, accountId),
                    null
            );

            ack.acknowledge();
        } catch (CardDemoException e) {
            LOG.error(
                    "CardDemoException handling account.updated topic={} partition={} offset={} "
                            + "account_id={} reasonCode={} cause={}",
                    topic, partition, offset, accountId,
                    e.getReasonCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOG.error(
                    "Unrecoverable error handling account.updated topic={} partition={} offset={} "
                            + "account_id={} cause={}",
                    topic, partition, offset, accountId, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    /**
     * Consumes the {@code ledger.balanced} topic and records an audit
     * event for each end-of-day reconciliation event.
     *
     * <p>Replaces: the end-of-day reconciliation completion notice that
     * the COBOL batch chain ({@code app/jcl/POSTTRAN.jcl} →
     * {@code app/jcl/INTCALC.jcl} → {@code app/jcl/COMBTRAN.jcl} →
     * {@code app/jcl/CREASTMT.JCL} / {@code app/jcl/TRANREPT.jcl}) emits
     * via SYSPRINT trailer records and {@code RETURN-CODE} values. In the
     * Java target the producer for this topic is end-of-day reconciliation
     * code that does not exist in the source as a dedicated COBOL program
     * — the AAP &sect;0.6.5 ordering invariant still applies (partition by
     * account ID for any per-account ledger events).</p>
     *
     * <p>The payload type is {@link Object} per the schema because the
     * publisher contract is intentionally generic — different reconciliation
     * paths may emit different shapes, and this consumer only needs to
     * record the event for audit. Spring Kafka's {@code JsonDeserializer}
     * maps the inbound JSON to either a {@link Map} (default) or any
     * project-specific record type configured via type-headers.</p>
     *
     * @param event     the deserialised payload (typed as {@link Object})
     * @param accountId the 11-digit account ID (partition key); may be
     *                  blank for system-wide reconciliation events that
     *                  carry no account context
     * @param topic     the source topic name (typically
     *                  {@code ledger.balanced})
     * @param partition the source partition index
     * @param offset    the record offset within the partition
     * @param ack       the manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.ledger-balanced:ledger.balanced}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo-consumer-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onLedgerBalanced(
            @Payload Object event,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String accountId,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) int partition,
            @Header(value = KafkaHeaders.OFFSET, required = false) long offset,
            Acknowledgment ack) {

        // Consumes events that replace COBOL end-of-day reconciliation
        // SYSPRINT trailers + RETURN-CODE values in the POSTTRAN → INTCALC
        // → COMBTRAN → CREASTMT/TRANREPT JCL chain (AAP §0.6.3).
        // AAP §0.6.5: per-account ordering guaranteed by partition-by-account-id
        // on producer side.

        LOG.info("Kafka onLedgerBalanced topic={} partition={} offset={} account_id={}",
                topic, partition, offset, accountId);

        try {
            auditLogService.logTransactionEvent(
                    null,                                   // transactionId — not in ledger.balanced payload
                    parseAccountId(accountId),
                    OPERATOR_KAFKA_CONSUMER,
                    EVENT_LEDGER_BALANCED,
                    null,
                    buildLedgerPayload(event,
                            topic, partition, offset, accountId),
                    null
            );

            ack.acknowledge();
        } catch (CardDemoException e) {
            LOG.error(
                    "CardDemoException handling ledger.balanced topic={} partition={} offset={} "
                            + "account_id={} reasonCode={} cause={}",
                    topic, partition, offset, accountId,
                    e.getReasonCode(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOG.error(
                    "Unrecoverable error handling ledger.balanced topic={} partition={} offset={} "
                            + "account_id={} cause={}",
                    topic, partition, offset, accountId, e.getMessage(), e);
            ack.acknowledge();
        }
    }

    /**
     * Consumes the {@code report.requested} topic and starts a Step
     * Functions execution to run the corresponding AWS Batch report job.
     *
     * <p><b>Replaces:</b> {@code CORPT00C WIRTE-JOBSUB-TDQ → EXEC CICS
     * WRITEQ TD QUEUE('JOBS') → JES2 submission} (AAP &sect;0.1.1 — the
     * sole online-to-batch bridge in the source mainframe environment).
     * The COBOL program {@code app/cbl/CORPT00C.cbl} paragraph
     * {@code WIRTE-JOBSUB-TDQ} (line 515 of the source) writes 80-byte JCL
     * records to the CICS TDQ named {@code JOBS}; the JES2 internal reader
     * polls the queue and submits the JCL it finds (typically
     * {@code app/jcl/TRANREPT.jcl}) to the spool. In the Java target,
     * {@code ReportSubmissionService} publishes report submissions to MSK
     * topic {@code report.requested}; this listener consumes them and
     * invokes {@link StepFunctionsOrchestrator#startExecution} which (in
     * turn) submits the AWS Batch report job — preserving the asynchronous
     * decoupling of the source bridge.</p>
     *
     * <p>The Step Functions state machine ARN is resolved at startup from
     * {@code carddemo.stepfunctions.report-pipeline-arn} (with a fallback
     * to the existing {@code carddemo.aws.stepfunctions.file-provisioning-arn}
     * property). A blank ARN (typically the {@code local} profile when
     * Step Functions are not provisioned) causes this listener to log at
     * WARN, emit the inbound-event audit record, and acknowledge the
     * message without starting an execution — keeping local development
     * unblocked.</p>
     *
     * <p>Audit emission discipline: two records per consumed event —</p>
     * <ol>
     *   <li>{@link #EVENT_REPORT_REQUESTED_RECEIVED} — captures the
     *       inbound envelope (reportType, dates, confirm flag) for
     *       traceability before the orchestrator call.</li>
     *   <li>{@link #EVENT_REPORT_REQUESTED_STARTED} — captures the
     *       Step Functions {@code executionArn} returned by
     *       {@link StepFunctionsOrchestrator#startExecution} so the
     *       end-to-end correlation (inbound Kafka offset → outbound Step
     *       Functions execution) is searchable in OpenSearch.</li>
     * </ol>
     *
     * @param event     the deserialised {@link ReportRequestDto} payload —
     *                  may be {@code null} only if the Kafka record value
     *                  was empty / null (which itself should be filtered
     *                  upstream); the listener treats {@code null} as a
     *                  poison message and acks to skip
     * @param accountId the partition key — for report.requested this is
     *                  typically the originator user ID or the report ID;
     *                  retained as "accountId" for consistency with the
     *                  schema-mandated method signature
     * @param topic     the source topic name (typically
     *                  {@code report.requested})
     * @param partition the source partition index
     * @param offset    the record offset within the partition
     * @param ack       the manual acknowledgment handle
     */
    @KafkaListener(
            topics = "${carddemo.kafka.topics.report-requested:report.requested}",
            groupId = "${carddemo.kafka.consumer.group-id:carddemo-consumer-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onReportRequested(
            @Payload ReportRequestDto event,
            @Header(value = KafkaHeaders.RECEIVED_KEY, required = false) String accountId,
            @Header(value = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(value = KafkaHeaders.RECEIVED_PARTITION, required = false) int partition,
            @Header(value = KafkaHeaders.OFFSET, required = false) long offset,
            Acknowledgment ack) {

        // Replaces: CORPT00C WIRTE-JOBSUB-TDQ → EXEC CICS WRITEQ TD QUEUE('JOBS')
        // → JES submission (AAP §0.1.1 sole online-to-batch bridge).
        // AAP §0.6.5: per-account ordering guaranteed by partition-by-account-id
        // on producer side.

        LOG.info(
                "Kafka onReportRequested topic={} partition={} offset={} key={} reportType={}",
                topic, partition, offset, accountId,
                (event != null ? event.reportType() : null));

        try {
            // ----- Step 1: audit the inbound event ---------------------------
            // logAuditEvent — the schema-required generic audit path used for
            // events that have no natural transaction ID (per AuditLogService
            // depends_on_files purpose: "logAuditEvent for inbound report
            // requests, and logAuditEvent again to capture the started Step
            // Functions executionArn for the report pipeline").
            auditLogService.logAuditEvent(
                    EVENT_REPORT_REQUESTED_RECEIVED,
                    RESOURCE_TYPE_REPORT,
                    accountId,                              // resourceId — partition key (report-id / user-id)
                    OPERATOR_KAFKA_CONSUMER,
                    buildReportPayload(event,
                            topic, partition, offset, accountId),
                    null
            );

            // ----- Step 2: short-circuit on blank ARN ------------------------
            // Local-profile escape hatch — keep developer iteration unblocked
            // when the Step Functions state machine is not provisioned. The
            // inbound audit record above is the permanent receipt.
            String arn = reportPipelineStateMachineArn != null
                    ? reportPipelineStateMachineArn.trim()
                    : "";
            if (arn.isEmpty()) {
                LOG.warn(
                        "No report-pipeline state-machine ARN configured "
                                + "(carddemo.stepfunctions.report-pipeline-arn); "
                                + "acknowledging report.requested without starting an execution "
                                + "topic={} partition={} offset={} key={}",
                        topic, partition, offset, accountId);
                ack.acknowledge();
                return;
            }

            // ----- Step 3: serialise the payload and start the execution ----
            // The orchestrator schema-required signature is
            // startExecution(String stateMachineArn, String inputJson) — we
            // serialise the ReportRequestDto record to a minimal JSON object
            // here so the orchestrator stays AWS-SDK-pure (no Jackson coupling
            // in the orchestrator; the consumer owns the wire format of its
            // own inputs).
            String inputJson = toReportInputJson(event);
            String executionArn = stepFunctionsOrchestrator.startExecution(arn, inputJson);

            LOG.info(
                    "Started Step Functions execution executionArn={} arn={} topic={} key={} offset={}",
                    executionArn, arn, topic, accountId, offset);

            // ----- Step 4: audit the started execution ----------------------
            // Second audit record carries the executionArn — completes the
            // end-to-end correlation chain (Kafka inbound offset →
            // Step Functions execution ARN) per AAP §0.6.6.
            auditLogService.logAuditEvent(
                    EVENT_REPORT_REQUESTED_STARTED,
                    RESOURCE_TYPE_REPORT,
                    accountId,
                    OPERATOR_KAFKA_CONSUMER,
                    buildReportStartedPayload(executionArn, arn, event,
                            topic, partition, offset, accountId),
                    null
            );

            // ----- Step 5: acknowledge ---------------------------------------
            // AAP §0.6.5: ack.acknowledge() ONLY after successful processing.
            ack.acknowledge();
        } catch (CardDemoException e) {
            LOG.error(
                    "CardDemoException handling report.requested topic={} partition={} offset={} "
                            + "key={} reasonCode={} cause={}",
                    topic, partition, offset, accountId,
                    e.getReasonCode(), e.getMessage(), e);
            // Recoverable per AAP §0.7.2 — DefaultErrorHandler retries 3x
            // with 1-second back-off and then routes to report.requested.DLT.
            throw e;
        } catch (Exception e) {
            LOG.error(
                    "Unrecoverable error handling report.requested topic={} partition={} offset={} "
                            + "key={} cause={}",
                    topic, partition, offset, accountId, e.getMessage(), e);
            // Acknowledge to skip — the error log line above is the
            // permanent forensic record of the poison message.
            ack.acknowledge();
        }
    }

    // =========================================================================
    // Package-private helpers (visible to ad-hoc + production unit tests)
    // =========================================================================

    /**
     * Parses the partition-key {@link String} into a numeric account ID
     * suitable for the {@link AuditLogService#logTransactionEvent}
     * {@code accountId} parameter. Returns {@code null} on any parse
     * failure so the audit event still emits with a {@code null} account
     * field rather than failing the listener.
     *
     * @param raw the raw partition key — may be {@code null} or blank for
     *            events that carry no account context (e.g., system-wide
     *            ledger.balanced events)
     * @return the parsed {@link Long} account ID, or {@code null} when
     *         the input is null, blank, or non-numeric
     */
    static Long parseAccountId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // The header value is not numeric — surface as null so the
            // audit event still emits. The header (raw) value is preserved
            // in the audit payload via the buildXxxPayload helpers.
            return null;
        }
    }

    /**
     * Returns the account ID for the audit event, preferring the entity's
     * own {@link AccountUpdateDto#accountId()} field over the partition-key
     * header (which may differ if the producer used a derived key). Falls
     * back to {@link #parseAccountId(String)} when the entity field is
     * absent.
     *
     * @param event             the deserialised {@link AccountUpdateDto}
     *                          payload — may be {@code null} (treated as
     *                          a poison message)
     * @param headerAccountId   the partition-key header value
     * @return the resolved account ID, or {@code null} when neither
     *         source produces a valid Long
     */
    static Long extractAccountIdLong(AccountUpdateDto event, String headerAccountId) {
        if (event != null && event.accountId() != null) {
            return event.accountId();
        }
        return parseAccountId(headerAccountId);
    }

    /**
     * Builds the audit payload for a {@code transaction.posted} event.
     *
     * <p>Captures the inbound event's verbatim fields plus the Kafka
     * metadata (topic, partition, offset, raw account-id header) for full
     * traceability. The {@link AuditLogService#logTransactionEvent}
     * sanitiser will mask any PAN-like sequences before persisting.</p>
     *
     * @param event     the deserialised payload; may be {@code null}
     * @param topic     source topic
     * @param partition source partition index
     * @param offset    source offset
     * @param accountId raw partition-key header
     * @return a new mutable map suitable for the audit envelope
     */
    static Map<String, Object> buildTransactionPayload(TransactionAddDto event,
                                                      String topic,
                                                      int partition,
                                                      long offset,
                                                      String accountId) {
        Map<String, Object> payload = newOrderedPayload(topic, partition, offset, accountId);
        if (event != null) {
            // PAN is masked by AuditLogService.sanitizePayload via the
            // PAN_PATTERN regex — including the raw cardNumber here is
            // safe; the sanitiser replaces it with the masked form.
            payload.put("event_account_id", event.accountId());
            payload.put("card_number", event.cardNumber());
            payload.put("transaction_type", event.transactionType());
            payload.put("transaction_category", event.transactionCategory());
            payload.put("source", event.source());
            payload.put("description", event.description());
            payload.put("amount", event.amount());
            payload.put("origination_timestamp", event.originationTimestamp());
            payload.put("merchant_id", event.merchantId());
            payload.put("merchant_name", event.merchantName());
            payload.put("merchant_city", event.merchantCity());
            payload.put("merchant_zip", event.merchantZip());
            payload.put("confirm", event.confirm());
        }
        return payload;
    }

    /**
     * Builds the audit payload for an {@code account.updated} event.
     *
     * <p>Captures the inbound entity's primary fields (account ID, active
     * status, balances, version) plus Kafka metadata. The
     * {@link AccountUpdateDto#customerSsn()} field is excluded by the
     * caller (we use a dedicated allowlist of fields) to avoid leaking PII
     * into the audit index — the {@code AuditLogService} sanitiser would
     * also mask it, but defence-in-depth dictates excluding at the source.</p>
     *
     * @param event     the deserialised payload; may be {@code null}
     * @param topic     source topic
     * @param partition source partition index
     * @param offset    source offset
     * @param accountId raw partition-key header
     * @return a new mutable map suitable for the audit envelope
     */
    static Map<String, Object> buildAccountPayload(AccountUpdateDto event,
                                                  String topic,
                                                  int partition,
                                                  long offset,
                                                  String accountId) {
        Map<String, Object> payload = newOrderedPayload(topic, partition, offset, accountId);
        if (event != null) {
            payload.put("event_account_id", event.accountId());
            payload.put("active_status", event.activeStatus());
            payload.put("current_balance", event.currentBalance());
            payload.put("credit_limit", event.creditLimit());
            payload.put("cash_credit_limit", event.cashCreditLimit());
            payload.put("open_date", event.openDate());
            payload.put("expiration_date", event.expirationDate());
            payload.put("reissue_date", event.reissueDate());
            payload.put("current_cycle_credit", event.currentCycleCredit());
            payload.put("current_cycle_debit", event.currentCycleDebit());
            payload.put("version", event.version());
            // NOTE: customerSsn / customerDateOfBirth / phone numbers /
            // customer address fields are intentionally omitted — they
            // are PII and even masked storage in the audit index would
            // increase the attack surface. The producer is the
            // authoritative ledger of the customer-record mutation; the
            // audit consumer records only the financial-state mutation.
        }
        return payload;
    }

    /**
     * Builds the audit payload for a {@code ledger.balanced} event.
     *
     * <p>The payload is typed as {@link Object} because the publisher
     * contract is generic. If the value is itself a {@link Map} the
     * helper merges it under {@code "event"}; otherwise the value is
     * stored under the same key for uniformity.</p>
     *
     * @param event     the deserialised payload; may be {@code null}
     * @param topic     source topic
     * @param partition source partition index
     * @param offset    source offset
     * @param accountId raw partition-key header
     * @return a new mutable map suitable for the audit envelope
     */
    static Map<String, Object> buildLedgerPayload(Object event,
                                                  String topic,
                                                  int partition,
                                                  long offset,
                                                  String accountId) {
        Map<String, Object> payload = newOrderedPayload(topic, partition, offset, accountId);
        if (event != null) {
            // Store under a stable "event" key for searchability — downstream
            // dashboards can wildcard on event.* without needing to know
            // the per-producer shape.
            payload.put("event", event);
        }
        return payload;
    }

    /**
     * Builds the audit payload for the inbound side of a
     * {@code report.requested} event — captures the request envelope so
     * the regulatory audit trail records what the operator requested.
     *
     * @param event     the deserialised payload; may be {@code null}
     * @param topic     source topic
     * @param partition source partition index
     * @param offset    source offset
     * @param accountId raw partition-key header (originator user / report id)
     * @return a new mutable map suitable for the audit envelope
     */
    static Map<String, Object> buildReportPayload(ReportRequestDto event,
                                                  String topic,
                                                  int partition,
                                                  long offset,
                                                  String accountId) {
        Map<String, Object> payload = newOrderedPayload(topic, partition, offset, accountId);
        if (event != null) {
            payload.put("report_type", event.reportType());
            payload.put("start_date", event.startDate());
            payload.put("end_date", event.endDate());
            payload.put("confirm", event.confirm());
        }
        return payload;
    }

    /**
     * Builds the audit payload for the "execution started" side of a
     * {@code report.requested} event — captures both the original request
     * envelope and the resulting Step Functions {@code executionArn} so
     * the end-to-end correlation is searchable in one document.
     *
     * @param executionArn    the ARN returned by
     *                        {@link StepFunctionsOrchestrator#startExecution}
     * @param stateMachineArn the ARN of the state machine that was started
     * @param event           the deserialised payload; may be {@code null}
     * @param topic           source topic
     * @param partition       source partition index
     * @param offset          source offset
     * @param accountId       raw partition-key header
     * @return a new mutable map suitable for the audit envelope
     */
    static Map<String, Object> buildReportStartedPayload(String executionArn,
                                                         String stateMachineArn,
                                                         ReportRequestDto event,
                                                         String topic,
                                                         int partition,
                                                         long offset,
                                                         String accountId) {
        Map<String, Object> payload = newOrderedPayload(topic, partition, offset, accountId);
        payload.put("execution_arn", executionArn);
        payload.put("state_machine_arn", stateMachineArn);
        if (event != null) {
            payload.put("report_type", event.reportType());
            payload.put("start_date", event.startDate());
            payload.put("end_date", event.endDate());
            payload.put("confirm", event.confirm());
        }
        return payload;
    }

    /**
     * Allocates a new ordered audit-payload map seeded with the Kafka
     * metadata fields. {@link LinkedHashMap} preserves insertion order so
     * the OpenSearch document and any human-readable dump renders the
     * metadata block before the event-specific fields.
     *
     * @param topic           source topic
     * @param partition       source partition index
     * @param offset          source offset
     * @param rawAccountIdKey raw partition-key header (kept as a separate
     *                        field so the original key bytes are
     *                        recoverable even if the numeric parse
     *                        succeeds and overwrites the typed accountId
     *                        in the parent envelope)
     * @return a new mutable {@link LinkedHashMap}
     */
    static Map<String, Object> newOrderedPayload(String topic,
                                                 int partition,
                                                 long offset,
                                                 String rawAccountIdKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kafka_topic", topic);
        payload.put("kafka_partition", partition);
        payload.put("kafka_offset", offset);
        payload.put("kafka_key", rawAccountIdKey);
        return payload;
    }

    /**
     * Serialises a {@link ReportRequestDto} into the minimal JSON payload
     * required by the Step Functions state-machine input.
     *
     * <p>Per AAP &sect;0.7.1 (Adapter Pattern), AWS SDK calls must stay in
     * dedicated adapter classes; the
     * {@link StepFunctionsOrchestrator#startExecution(String, String)} API
     * accepts a pre-serialised JSON string so this adapter owns the wire
     * format of its own inputs without coupling the orchestrator to
     * Jackson. The output JSON object carries the four
     * {@code ReportRequestDto} fields in the canonical order
     * {@code reportType, startDate, endDate, confirm} — the Step
     * Functions Task states inside
     * {@code src/main/resources/stepfunctions/eod-batch-pipeline.asl.json}
     * (and the report-pipeline equivalent) reference these field names
     * verbatim via {@code "$.reportType"} JSONPath expressions.</p>
     *
     * <p>A {@code null} or empty input deliberately produces the JSON
     * literal {@code "{}"} — Step Functions accepts an empty object as a
     * valid input and the consumer treats a missing payload as a no-op
     * request that the downstream state machine handles via its
     * {@code Choice} states.</p>
     *
     * @param event the source DTO; may be {@code null}
     * @return a well-formed JSON object literal; never {@code null} or blank
     */
    static String toReportInputJson(ReportRequestDto event) {
        if (event == null) {
            return EMPTY_JSON_OBJECT;
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append('{');
        boolean first = true;

        // reportType — required field but defensive null-check preserves
        // the no-throw contract on the input-serialisation path.
        first = appendJsonField(sb, first, "reportType", quoteJsonString(event.reportType()));
        // startDate — optional (only required when reportType=CUSTOM)
        first = appendJsonField(sb, first, "startDate", quoteJsonDate(event.startDate()));
        // endDate — optional (only required when reportType=CUSTOM)
        first = appendJsonField(sb, first, "endDate", quoteJsonDate(event.endDate()));
        // confirm — optional ('Y' / 'N' / null per ReportRequestDto contract)
        appendJsonField(sb, first, "confirm", quoteJsonString(event.confirm()));

        sb.append('}');
        return sb.toString();
    }

    /**
     * Appends a single {@code "key":value} pair to the supplied JSON object
     * builder, separated from the previous pair by a comma when
     * {@code firstPair} is {@code false}.
     *
     * @param sb        the in-progress JSON object builder
     * @param firstPair {@code true} on the first pair (no leading comma);
     *                  {@code false} for subsequent pairs
     * @param key       the unquoted key
     * @param literal   the already-formatted JSON literal value (e.g.
     *                  {@code "\"MONTHLY\""}, {@code "null"},
     *                  {@code "2026-01-01"}); never raw user input
     * @return {@code false} after the pair has been appended (so subsequent
     *         calls add a leading comma)
     */
    private static boolean appendJsonField(StringBuilder sb, boolean firstPair,
                                           String key, String literal) {
        if (!firstPair) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":").append(literal);
        return false;
    }

    /**
     * Formats a {@link String} as a JSON literal: a double-quoted, escaped
     * string for non-null inputs, or the bare literal {@code null} when
     * the input is {@code null}. Escapes the minimum set required for
     * valid JSON: backslash, double-quote, and the standard control
     * characters.
     *
     * @param raw the input string; may be {@code null}
     * @return the JSON literal; never {@code null}
     */
    static String quoteJsonString(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * Formats a {@link LocalDate} as a JSON literal in ISO-8601
     * {@code "yyyy-MM-dd"} form. {@code null} dates render as the bare
     * literal {@code null} so the resulting JSON remains valid even when
     * the optional date fields are absent.
     *
     * @param date the source date; may be {@code null}
     * @return the JSON literal; never {@code null}
     */
    static String quoteJsonDate(LocalDate date) {
        if (date == null) {
            return "null";
        }
        // LocalDate.toString() emits ISO-8601 yyyy-MM-dd already — the
        // same format Jackson would use under @JsonFormat(pattern = "yyyy-MM-dd")
        // and the format the Step Functions ASL date utilities expect.
        return "\"" + date.toString() + "\"";
    }
}
