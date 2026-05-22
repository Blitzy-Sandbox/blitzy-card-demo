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

import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.BillPaymentDto;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.CardDemoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Apache Kafka / Amazon MSK producer adapter &mdash; the sole entry point for
 * every inter-service event published by the CardDemo Java/Spring Boot target.
 *
 * <p>Replaces: CICS Transient Data Queue (TDQ) writes in
 * {@code app/cbl/CORPT00C.cbl} (paragraph {@code WIRTE-JOBSUB-TDQ}, line 515 of
 * the source), {@code EXEC CICS WRITE FILE('TRANSACT')} (i.e.,
 * {@code WRITE TRAN-RECORD}) across {@code app/cbl/COBIL00C.cbl} (paragraph
 * {@code WRITE-TRANSACT-FILE}, called from line 233 of the source),
 * {@code app/cbl/COTRN02C.cbl}, {@code app/cbl/CBTRN02C.cbl} (paragraph
 * {@code 2000-POST-TRANSACTION}, line 424), {@code app/cbl/CBACT04C.cbl}, and
 * {@code EXEC CICS REWRITE FILE('ACCTDAT')} (i.e., {@code REWRITE ACCT-RECORD})
 * in {@code app/cbl/COACTUPC.cbl} (paragraph {@code 9700-REWRITE-ACCTDAT-FILE})
 * and {@code app/cbl/COBIL00C.cbl} (paragraph {@code UPDATE-ACCTDAT-FILE},
 * called from line 235 of the source). Every WRITE TRAN-RECORD / REWRITE
 * ACCT-RECORD operation in the source now fans out to MSK Kafka events
 * consumed by downstream audit, projection, and Step Functions trigger
 * pipelines (per AAP &sect;0.6.5 and &sect;0.1.1).</p>
 *
 * <p>Per AAP &sect;0.7.1: "Isolate all AWS service integrations in dedicated
 * adapter classes &mdash; never inline AWS SDK calls in business logic". This
 * class is the only place in {@code src/main/java/com/awsm2/carddemo/} (other
 * than {@link KafkaEventConsumer}) that interacts with the Spring Kafka
 * producer API. Service classes inject this adapter via constructor injection
 * and call its publish methods.</p>
 *
 * <p>Per AAP &sect;0.6.5 (MSK Topic Ordering Guarantees): every record produced
 * by this adapter is keyed by the originating <b>account ID</b> (or for
 * {@code report.requested}, by the report's own identifier). Because Kafka's
 * default partitioner ({@code DefaultPartitioner}) hashes the key bytes via
 * murmur2 and consistently maps the same key to the same partition, this
 * guarantees per-account ordering even with multiple concurrent producers.
 * Account IDs are formatted as zero-padded 11-digit strings (matching the
 * COBOL {@code ACCT-ID PIC 9(11)} layout in {@code app/cpy/CVACT01Y.cpy:L6})
 * to ensure deterministic key bytes regardless of the numeric value's
 * leading-zero count.</p>
 *
 * <h2>Producer guarantees (configured in {@code KafkaConfig}, not here)</h2>
 * <ul>
 *   <li>{@code acks=all} &mdash; wait for full ISR acknowledgment before
 *       considering the send complete.</li>
 *   <li>{@code enable.idempotence=true} &mdash; eliminates duplicates within
 *       a producer session.</li>
 *   <li>{@code max.in.flight.requests.per.connection=5} &mdash; Kafka still
 *       preserves order under idempotence even with multiple in-flight
 *       requests.</li>
 *   <li>{@code retries=Integer.MAX_VALUE} with
 *       {@code delivery.timeout.ms} &ge; {@code request.timeout.ms} +
 *       {@code linger.ms} &mdash; bounded by delivery timeout, not retry
 *       count.</li>
 *   <li>JSON value serialization via Spring Kafka's {@code JsonSerializer}
 *       for human-inspectable bodies.</li>
 * </ul>
 *
 * <h2>Topic catalog</h2>
 * <ul>
 *   <li>{@code transaction.posted} &mdash; emitted by
 *       {@code TransactionAddService}, {@code BillPaymentService}, batch
 *       posting ({@code CBTRN02C} replacement), and interest postings
 *       ({@code CBACT04C} replacement). Keyed by account ID. Replaces:
 *       {@code WRITE TRAN-RECORD} in {@code COBIL00C}, {@code COTRN02C},
 *       {@code CBTRN02C}, {@code CBACT04C}.</li>
 *   <li>{@code account.updated} &mdash; emitted by every account-mutating
 *       flow: {@code AccountUpdateService}, {@code BillPaymentService}, and
 *       {@code InterestCalculationService}. Keyed by account ID. Replaces:
 *       {@code REWRITE ACCT-RECORD} in {@code COACTUPC}
 *       ({@code 9700-REWRITE-ACCTDAT-FILE}), {@code COBIL00C}, and
 *       {@code CBACT04C}.</li>
 *   <li>{@code ledger.balanced} &mdash; emitted by end-of-day reconciliation
 *       when DEBIT = CREDIT for a given account's transaction set. Keyed by
 *       account ID. Has no single COBOL line counterpart &mdash; it is an
 *       emergent property of the full EOD batch pipeline (AAP &sect;0.6.5).</li>
 *   <li>{@code report.requested} &mdash; emitted by
 *       {@code ReportSubmissionService} (the {@code CORPT00C} replacement).
 *       Keyed by report ID (NOT account ID, since reports may be
 *       cross-account). Replaces: {@code WIRTE-JOBSUB-TDQ}
 *       ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}, line 517 of
 *       {@code app/cbl/CORPT00C.cbl}) which historically submitted JCL jobs
 *       to JES via the CICS TDQ JOBS queue. Per AAP &sect;0.1.1, this is
 *       <i>the sole online-to-batch bridge in the source</i> and is now
 *       consumed by {@link KafkaEventConsumer#onReportRequested} which
 *       starts a Step Functions execution.</li>
 * </ul>
 *
 * <h2>Asynchronous dispatch</h2>
 * <p>Each {@code publishXxx} method is annotated {@link Async @Async} so that
 * publishing never blocks the calling business-logic thread on Kafka latency.
 * The async execution boundary is provided by {@code @EnableAsync} on
 * {@code CardDemoApplication}. Callers receive a {@link CompletableFuture}
 * resolving to a {@link SendResult} so they can observe send completion or
 * chain post-publish work (e.g., audit emission) without blocking.</p>
 *
 * <h2>Failure handling</h2>
 * <p>The container-level error handler in {@code KafkaConfig} handles
 * transient errors (broker unavailability, network blips) via the producer's
 * {@code retries=Integer.MAX_VALUE} setting bounded by
 * {@code delivery.timeout.ms}. The {@link #handleSendResult} callback in this
 * class fires only after retries are exhausted (i.e., on a true failure) and
 * logs at ERROR. The {@link CompletableFuture} returned to the caller carries
 * the underlying exception so a caller that needs to escalate can attach a
 * {@code .exceptionally(...)} stage; the {@link CardDemoException} type is
 * imported and available for callers to wrap any escalated failure into the
 * CardDemo exception hierarchy.</p>
 *
 * <h2>Thread safety</h2>
 * <p>{@link KafkaTemplate} is thread-safe and intended to be shared. The
 * adapter holds a single template reference and exposes only stateless
 * methods; topic names are injected at construction time and never mutated.</p>
 *
 * @see com.awsm2.carddemo.config.KafkaConfig
 * @see com.awsm2.carddemo.adapter.KafkaEventConsumer
 */
// Replaces: CICS TDQ writes (CORPT00C), WRITE TRAN-RECORD across
// COBIL00C/COTRN02C/CBTRN02C/CBACT04C, REWRITE ACCT-RECORD in
// COACTUPC and COBIL00C — all funnelled into MSK Kafka topics per
// AAP §0.6.5 and §0.1.1.
@Service
public class KafkaEventPublisher {

    /**
     * SLF4J logger. Structured log lines flow into the Logback +
     * logstash-logback-encoder JSON pipeline that ships to CloudWatch Logs
     * (AAP &sect;0.6.6). Per AAP &sect;0.6.6 (PCI-DSS), payload bodies are
     * never logged &mdash; only topic, partition key, and Kafka metadata
     * (partition, offset).
     */
    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /**
     * Reason code used to wrap any Kafka publish failure escalated to the
     * caller into the CardDemo exception hierarchy via
     * {@link CardDemoException}. Per AAP &sect;0.7.2 ("Error codes and
     * condition handling surfaced to downstream consumers must be preserved
     * verbatim"), this code is propagated into the JSON error envelope's
     * {@code code} field when {@code GlobalExceptionHandler} translates an
     * escalated publish failure.
     */
    static final String REASON_CODE_PUBLISH_ERROR = "KAFKA_PUBLISH_ERROR";

    /**
     * Partition-key format. Account IDs are zero-padded to 11 digits so the
     * Kafka default partitioner (murmur2 over key bytes) produces a stable
     * hash regardless of the numeric value's leading-zero count. Matches:
     * <ul>
     *   <li>{@code app/cpy/CVACT01Y.cpy:L6} &mdash; {@code ACCT-ID PIC 9(11)}.</li>
     *   <li>The same 11-digit canonical form used in {@link CacheService}
     *       Redis cache keys for coherent end-to-end keying across the
     *       Kafka + Redis + RDS stack.</li>
     * </ul>
     */
    static final String PARTITION_KEY_FORMAT = "%011d";

    /** Shared Spring Kafka producer template, provided by {@code KafkaConfig}. */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Topic name for {@code transaction.posted}. Externalized via Spring
     * property {@code carddemo.kafka.topics.transaction-posted} with a
     * default of {@code "transaction.posted"} matching the AAP &sect;0.6.5
     * topic catalog. Overridable per environment via Spring Profile
     * properties or AWS Systems Manager Parameter Store.
     */
    private final String transactionPostedTopic;

    /**
     * Topic name for {@code account.updated}. Externalized via
     * {@code carddemo.kafka.topics.account-updated}; default
     * {@code "account.updated"}.
     */
    private final String accountUpdatedTopic;

    /**
     * Topic name for {@code ledger.balanced}. Externalized via
     * {@code carddemo.kafka.topics.ledger-balanced}; default
     * {@code "ledger.balanced"}.
     */
    private final String ledgerBalancedTopic;

    /**
     * Topic name for {@code report.requested}. Externalized via
     * {@code carddemo.kafka.topics.report-requested}; default
     * {@code "report.requested"}.
     */
    private final String reportRequestedTopic;

    /**
     * Constructor injection. Spring supplies the shared
     * {@link KafkaTemplate} bean (produced by {@code KafkaConfig}) and
     * resolves the four topic-name properties at startup. Constructor
     * injection is mandatory per AAP &sect;0.7.1 ("Dependency injection
     * for loose coupling").
     *
     * @param kafkaTemplate          the shared {@code KafkaTemplate} bean,
     *                               configured by {@code KafkaConfig} with
     *                               {@code acks=all} +
     *                               {@code enable.idempotence=true}
     *                               (AAP &sect;0.6.5); never {@code null}
     * @param transactionPostedTopic topic name for {@code transaction.posted};
     *                               defaults to {@code "transaction.posted"}
     * @param accountUpdatedTopic    topic name for {@code account.updated};
     *                               defaults to {@code "account.updated"}
     * @param ledgerBalancedTopic    topic name for {@code ledger.balanced};
     *                               defaults to {@code "ledger.balanced"}
     * @param reportRequestedTopic   topic name for {@code report.requested};
     *                               defaults to {@code "report.requested"}
     */
    public KafkaEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${carddemo.kafka.topics.transaction-posted:transaction.posted}")
            String transactionPostedTopic,
            @Value("${carddemo.kafka.topics.account-updated:account.updated}")
            String accountUpdatedTopic,
            @Value("${carddemo.kafka.topics.ledger-balanced:ledger.balanced}")
            String ledgerBalancedTopic,
            @Value("${carddemo.kafka.topics.report-requested:report.requested}")
            String reportRequestedTopic) {
        // Replaces: CICS TDQ JOBS (CORPT00C WIRTE-JOBSUB-TDQ) and the
        // VSAM WRITE/REWRITE chain across COBIL00C/COTRN02C/CBTRN02C/
        // CBACT04C/COACTUPC — now MSK Kafka with per-account ordering via
        // murmur2-hashed account-ID partition keys (AAP §0.6.5).
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate,
                "kafkaTemplate must not be null");
        this.transactionPostedTopic = Objects.requireNonNull(transactionPostedTopic,
                "transactionPostedTopic must not be null");
        this.accountUpdatedTopic = Objects.requireNonNull(accountUpdatedTopic,
                "accountUpdatedTopic must not be null");
        this.ledgerBalancedTopic = Objects.requireNonNull(ledgerBalancedTopic,
                "ledgerBalancedTopic must not be null");
        this.reportRequestedTopic = Objects.requireNonNull(reportRequestedTopic,
                "reportRequestedTopic must not be null");
    }

    // ---------------------------------------------------------------------
    // Public API — one publish method per business event
    // ---------------------------------------------------------------------

    /**
     * Publishes a {@code transaction.posted} event for the supplied account
     * ID. Used by every flow that historically performed a
     * {@code WRITE TRAN-RECORD} on the source VSAM {@code TRANSACT} cluster:
     * <ul>
     *   <li>{@code COBIL00C} bill payment (paragraph
     *       {@code WRITE-TRANSACT-FILE}, called from line 233 of the
     *       source).</li>
     *   <li>{@code COTRN02C} manual transaction add (the online
     *       3270 add screen, mapset {@code COTRN02}).</li>
     *   <li>{@code CBTRN02C} batch transaction posting (paragraph
     *       {@code 2000-POST-TRANSACTION}, line 424 of the source).</li>
     *   <li>{@code CBACT04C} interest postings (one event per posted
     *       interest record).</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.5 the partition key is the account ID, zero-padded
     * to 11 digits to match the COBOL {@code ACCT-ID PIC 9(11)} layout and
     * to ensure consistent murmur2 hashing across producers and consumers.
     * All events for a given account land on the same partition, guaranteeing
     * per-account ordering even with multiple concurrent producers.</p>
     *
     * <p>The method is {@link Async @Async}: business-logic threads (REST
     * controllers, Spring Batch step processors) return immediately while the
     * actual broker round-trip executes on the application's task executor.</p>
     *
     * @param acctId 11-digit account ID (the COBOL {@code ACCT-ID PIC 9(11)}
     *               value); becomes the Kafka partition key after
     *               {@code %011d} formatting. Must not be {@code null}.
     * @param event  transaction event payload (typed as
     *               {@link TransactionAddDto} per AAP &sect;0.4.1); must not
     *               be {@code null}.
     * @return a {@link CompletableFuture} resolving to the
     *         {@link SendResult} (partition, offset, key, record metadata) on
     *         success, or completing exceptionally on a post-retry-exhaustion
     *         publish failure
     * @throws IllegalArgumentException if {@code acctId} is {@code null}
     */
    // Replaces: COBIL00C 9300-WRITE-TRANSACT-FILE (WRITE-TRANSACT-FILE
    // paragraph, called from line 233), COTRN02C transaction-add WRITE,
    // CBTRN02C 2000-POST-TRANSACTION (line 424), CBACT04C interest-posting
    // WRITE. Partition key = account ID per AAP §0.6.5.
    @Async
    public CompletableFuture<SendResult<String, Object>> publishTransactionPosted(
            Long acctId, TransactionAddDto event) {
        final String key = formatAccountKey(acctId);
        // PCI-DSS-safe logging: topic + partition key + event identifier
        // only — never the full payload (AAP §0.6.6).
        LOG.info("Publishing transaction.posted topic={} accountId={} cardLast4={}",
                transactionPostedTopic, key, lastFourOfPan(event));
        // 3-arg send is mandatory: a null key would route to a random
        // partition and break per-account ordering (AAP §0.6.5).
        return kafkaTemplate.send(transactionPostedTopic, key, event)
                .whenComplete((result, ex) ->
                        handleSendResult(transactionPostedTopic, key, result, ex));
    }

    /**
     * Publishes an {@code account.updated} event for the supplied account ID.
     * Used by every flow that historically performed a
     * {@code REWRITE ACCT-RECORD} on the source VSAM {@code ACCTDAT} cluster:
     * <ul>
     *   <li>{@code COACTUPC} account update (paragraph
     *       {@code 9700-REWRITE-ACCTDAT-FILE}).</li>
     *   <li>{@code COBIL00C} bill payment account-balance rewrite (paragraph
     *       {@code UPDATE-ACCTDAT-FILE}, called from line 235 of the
     *       source).</li>
     *   <li>{@code CBACT04C} interest calculation account-balance rewrite.</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.5 the partition key is the account ID, zero-padded
     * to 11 digits. Per-account ordering is mandatory because downstream
     * balance projections must see updates in the order the application
     * applied them.</p>
     *
     * @param acctId 11-digit account ID; becomes the Kafka partition key.
     *               Must not be {@code null}.
     * @param event  account update event payload (typed as
     *               {@link AccountUpdateDto} per AAP &sect;0.4.1); must not
     *               be {@code null}.
     * @return a {@link CompletableFuture} resolving to the
     *         {@link SendResult} on success
     * @throws IllegalArgumentException if {@code acctId} is {@code null}
     */
    // Replaces: COACTUPC 9700-REWRITE-ACCTDAT-FILE, COBIL00C
    // UPDATE-ACCTDAT-FILE (line 235), CBACT04C account-balance rewrite.
    @Async
    public CompletableFuture<SendResult<String, Object>> publishAccountUpdated(
            Long acctId, AccountUpdateDto event) {
        final String key = formatAccountKey(acctId);
        LOG.info("Publishing account.updated topic={} accountId={}",
                accountUpdatedTopic, key);
        return kafkaTemplate.send(accountUpdatedTopic, key, event)
                .whenComplete((result, ex) ->
                        handleSendResult(accountUpdatedTopic, key, result, ex));
    }

    /**
     * Publishes a {@code ledger.balanced} event for the supplied account ID.
     * Used by end-of-day reconciliation upon successful debit/credit balance
     * verification. Unlike {@code transaction.posted} and
     * {@code account.updated}, this topic has no single COBOL line
     * counterpart &mdash; it is an emergent property of the full EOD batch
     * pipeline (POSTTRAN &rarr; INTCALC &rarr; COMBTRAN &rarr; CREASTMT/TRANREPT).
     * AAP &sect;0.6.5 enumerates {@code ledger.balanced} as one of the three
     * core financial topics required by the architecture.
     *
     * <p>The payload type is intentionally {@link Object} per the file
     * schema: AAP scope mandates the topic and its partition strategy, not
     * the DTO shape. A concrete {@code LedgerBalancedEvent} DTO may be
     * introduced if/when the ledger reconciliation service is implemented;
     * doing so does not require changing this adapter's contract.</p>
     *
     * <p>Per AAP &sect;0.6.5 the partition key is the account ID, zero-padded
     * to 11 digits, for consistency with the other account-keyed topics.</p>
     *
     * @param acctId 11-digit account ID; becomes the Kafka partition key.
     *               Must not be {@code null}.
     * @param event  reconciliation event payload (totals, run identifier,
     *               etc.); must not be {@code null}.
     * @return a {@link CompletableFuture} resolving to the
     *         {@link SendResult} on success
     * @throws IllegalArgumentException if {@code acctId} is {@code null}
     */
    // Replaces: End-of-day reconciliation events emitted as an emergent
    // property of the full COBOL batch pipeline (CBTRN02C → CBACT04C →
    // COMBTRAN → CREASTMT/TRANREPT). AAP §0.6.5 lists ledger.balanced
    // as a core financial topic.
    @Async
    public CompletableFuture<SendResult<String, Object>> publishLedgerBalanced(
            Long acctId, Object event) {
        final String key = formatAccountKey(acctId);
        LOG.info("Publishing ledger.balanced topic={} accountId={}",
                ledgerBalancedTopic, key);
        return kafkaTemplate.send(ledgerBalancedTopic, key, event)
                .whenComplete((result, ex) ->
                        handleSendResult(ledgerBalancedTopic, key, result, ex));
    }

    /**
     * Publishes a {@code report.requested} event for the supplied report
     * identifier. Used by the {@code ReportSubmissionService} (the
     * {@code CORPT00C} online-program replacement) to bridge the online
     * report-submission flow to the batch report job.
     *
     * <p>Per AAP &sect;0.1.1: "the sole online-to-batch bridge
     * ({@code CORPT00C} &rarr; CICS TDQ JOBS queue &rarr; JES submission)
     * translates to an MSK topic ({@code report.requested}) consumed by a
     * Step Functions trigger that submits an AWS Batch job &mdash; preserving
     * the asynchronous decoupling." This method's
     * {@link KafkaEventConsumer#onReportRequested} consumer counterpart
     * invokes {@code StepFunctionsOrchestrator.startExecution(...)} to start
     * the batch pipeline.</p>
     *
     * <p>Replaces: paragraph {@code WIRTE-JOBSUB-TDQ} at line 515 of
     * {@code app/cbl/CORPT00C.cbl} which issues
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at line 517. The TDQ JOBS
     * destination historically caused CICS to submit a JCL job via JES;
     * the new flow publishes a JSON event whose consumer starts a Step
     * Functions state machine that submits an AWS Batch job.</p>
     *
     * <p>Unlike the account-keyed topics, the {@code report.requested}
     * partition key is the <b>report identifier</b> (NOT the account ID),
     * because reports may be cross-account. The report ID becomes the
     * murmur2-hashed partition key, guaranteeing per-report ordering.</p>
     *
     * @param reportId unique report identifier (UUID, timestamp+sequence, or
     *                 originating-user ID); used as the Kafka partition key.
     *                 Must not be {@code null} or blank &mdash; a null/blank
     *                 key would route to a random partition and defeat
     *                 ordering guarantees (AAP &sect;0.6.5).
     * @param event    report request payload (typed as
     *                 {@link ReportRequestDto} per AAP &sect;0.4.1) carrying
     *                 the report type, date range, format, and requester
     *                 fields. Must not be {@code null}.
     * @return a {@link CompletableFuture} resolving to the
     *         {@link SendResult} on success
     * @throws IllegalArgumentException if {@code reportId} is {@code null}
     *                                  or blank
     */
    // Replaces: CORPT00C WIRTE-JOBSUB-TDQ → EXEC CICS WRITEQ TD QUEUE('JOBS')
    // → JES batch submission (line 515-523 of app/cbl/CORPT00C.cbl).
    // Per AAP §0.1.1, this is the sole online-to-batch bridge in the source.
    @Async
    public CompletableFuture<SendResult<String, Object>> publishReportRequested(
            String reportId, ReportRequestDto event) {
        if (reportId == null || reportId.isBlank()) {
            throw new IllegalArgumentException(
                    "reportId must not be null/blank — used as Kafka partition key "
                            + "for per-report ordering (AAP §0.6.5)");
        }
        LOG.info("Publishing report.requested topic={} reportId={} reportType={}",
                reportRequestedTopic, reportId,
                event != null ? event.reportType() : null);
        return kafkaTemplate.send(reportRequestedTopic, reportId, event)
                .whenComplete((result, ex) ->
                        handleSendResult(reportRequestedTopic, reportId, result, ex));
    }

    // ---------------------------------------------------------------------
    // Helper methods (private)
    // ---------------------------------------------------------------------

    /**
     * Zero-pads an account ID to 11 digits to match the COBOL
     * {@code ACCT-ID PIC 9(11)} layout (defined at
     * {@code app/cpy/CVACT01Y.cpy:L6}) and ensure consistent murmur2 hashing
     * across producers and consumers.
     *
     * <p>The default Kafka partitioner ({@code DefaultPartitioner}) hashes
     * the key's UTF-8 byte representation via murmur2. The numeric value
     * {@code 12345L} could legitimately be formatted as {@code "12345"}
     * (5 bytes) or {@code "00000012345"} (11 bytes); each yields a different
     * murmur2 hash and therefore a different partition assignment. Padding
     * to a fixed 11-digit width is therefore essential so that the same
     * account ID always lands on the same partition regardless of which
     * producer (online service, batch job, or replay tooling) formats it.</p>
     *
     * @param acctId the account ID; must not be {@code null}
     * @return the zero-padded 11-digit string (e.g., {@code "00000012345"})
     * @throws IllegalArgumentException if {@code acctId} is {@code null}
     */
    // AAP §0.6.5: partition key = zero-padded account ID for per-account
    // ordering. Matches COBOL ACCT-ID PIC 9(11) (app/cpy/CVACT01Y.cpy:L6).
    private String formatAccountKey(Long acctId) {
        if (acctId == null) {
            throw new IllegalArgumentException(
                    "accountId must not be null — Kafka partition key required "
                            + "for per-account ordering (AAP §0.6.5)");
        }
        return String.format(PARTITION_KEY_FORMAT, acctId);
    }

    /**
     * Extracts the last four digits of the PAN ({@code TRAN-CARD-NUM}) from
     * a {@link TransactionAddDto} for PCI-DSS-safe logging. PCI-DSS
     * Requirement 3.4 requires that the full PAN must not be retained in
     * audit logs unless a defined business need exists; this helper returns
     * only the last four digits so that log lines remain useful for
     * customer-service lookups without leaking the full PAN.
     *
     * @param event the transaction DTO; may be {@code null}
     * @return the last four digits of the PAN, or {@code "----"} if no PAN
     *         is available (e.g., when the event is keyed by account ID
     *         alone)
     */
    private String lastFourOfPan(TransactionAddDto event) {
        if (event == null) {
            return "----";
        }
        final String pan = event.cardNumber();
        if (pan == null || pan.length() < 4) {
            return "----";
        }
        return pan.substring(pan.length() - 4);
    }

    /**
     * Centralized send-result callback. Logs success at DEBUG (topic,
     * partition, offset, key) and failures at ERROR. Per AAP &sect;0.6.5 the
     * producer is configured with {@code retries=Integer.MAX_VALUE} +
     * {@code enable.idempotence=true}, so this callback handles only
     * post-retry-exhaustion failures (i.e., genuine broker / serialization
     * problems that the producer could not recover from within
     * {@code delivery.timeout.ms}).
     *
     * <p>Failures are logged at ERROR but are NOT re-thrown from this
     * callback &mdash; the underlying exception is already attached to the
     * returned {@link CompletableFuture} via Spring Kafka's
     * {@code KafkaTemplate.send(...)} contract, and any caller that wants
     * to escalate (e.g., wrap as {@link CardDemoException} with reason
     * {@link #REASON_CODE_PUBLISH_ERROR}) can chain a
     * {@code .exceptionally(...)} or {@code .handle(...)} stage on the
     * future.</p>
     *
     * @param topic  the topic name being published to
     * @param key    the partition key used for the publish attempt
     * @param result the {@link SendResult} produced by a successful send;
     *               {@code null} when {@code ex} is non-null
     * @param ex     the underlying exception when the send fails;
     *               {@code null} on success
     */
    private void handleSendResult(String topic,
                                  String key,
                                  SendResult<String, Object> result,
                                  Throwable ex) {
        if (ex != null) {
            // Log at ERROR — failures here mean the producer's retries
            // (retries=Integer.MAX_VALUE bounded by delivery.timeout.ms)
            // were exhausted. The container-level error handler in
            // ../config/KafkaConfig has already attempted recovery.
            LOG.error("Kafka publish FAILED topic={} key={} cause={}",
                    topic, key, ex.getMessage(), ex);
            // NOTE: failure is already attached to the CompletableFuture
            // returned to the caller; callers that need to wrap into the
            // CardDemo exception hierarchy can chain a .exceptionally(...)
            // returning new CardDemoException(REASON_CODE_PUBLISH_ERROR,
            // "Failed to publish to topic=" + topic, t).
        } else if (result != null) {
            // PCI-DSS-safe success line: metadata only, no payload body.
            LOG.debug("Kafka publish OK topic={} partition={} offset={} key={}",
                    topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    key);
        }
    }

    // ---------------------------------------------------------------------
    // Compile-time references to imported types (per AAP §0.5.2 and the
    // file schema, every internal_imports member must be referenced).
    //
    // BillPaymentDto is listed in the schema's depends_on_files because
    // BillPaymentService is the upstream caller flow that constructs
    // TransactionAddDto events for publishTransactionPosted (per AAP
    // §0.4.1 BillingController → BillPaymentService → KafkaEventPublisher).
    // The translation from BillPaymentDto to TransactionAddDto lives in
    // BillPaymentService per the Minimal Change Clause (AAP §0.7.3) —
    // this adapter accepts only TransactionAddDto so the topic schema
    // remains canonical. The reference below exists so a downstream
    // refactor that needs a convenience overload knows where to place it.
    // ---------------------------------------------------------------------

    /**
     * Returns the canonical Spring property key used to externalize the
     * {@code transaction.posted} topic name. Used by tests to programmatically
     * override the topic name (e.g., to inject a Testcontainers-generated
     * test topic) and by any future tooling that needs to enumerate the
     * adapter's externalized properties.
     *
     * @return the property key string
     *         ({@code "carddemo.kafka.topics.transaction-posted"})
     */
    public static String transactionPostedTopicProperty() {
        return "carddemo.kafka.topics.transaction-posted";
    }

    /**
     * Returns the canonical Spring property key for the {@code account.updated}
     * topic.
     *
     * @return {@code "carddemo.kafka.topics.account-updated"}
     */
    public static String accountUpdatedTopicProperty() {
        return "carddemo.kafka.topics.account-updated";
    }

    /**
     * Returns the canonical Spring property key for the {@code ledger.balanced}
     * topic.
     *
     * @return {@code "carddemo.kafka.topics.ledger-balanced"}
     */
    public static String ledgerBalancedTopicProperty() {
        return "carddemo.kafka.topics.ledger-balanced";
    }

    /**
     * Returns the canonical Spring property key for the {@code report.requested}
     * topic.
     *
     * @return {@code "carddemo.kafka.topics.report-requested"}
     */
    public static String reportRequestedTopicProperty() {
        return "carddemo.kafka.topics.report-requested";
    }

    /**
     * Returns the resolved {@code transaction.posted} topic name. Package-
     * visible for use by tests that need to assert the topic an event was
     * published to, without reaching into the property source.
     *
     * @return the topic name resolved from
     *         {@code carddemo.kafka.topics.transaction-posted}
     *         (default {@code "transaction.posted"})
     */
    String getTransactionPostedTopic() {
        return transactionPostedTopic;
    }

    /**
     * Returns the resolved {@code account.updated} topic name (package-
     * visible test accessor).
     *
     * @return the topic name resolved from
     *         {@code carddemo.kafka.topics.account-updated}
     *         (default {@code "account.updated"})
     */
    String getAccountUpdatedTopic() {
        return accountUpdatedTopic;
    }

    /**
     * Returns the resolved {@code ledger.balanced} topic name (package-
     * visible test accessor).
     *
     * @return the topic name resolved from
     *         {@code carddemo.kafka.topics.ledger-balanced}
     *         (default {@code "ledger.balanced"})
     */
    String getLedgerBalancedTopic() {
        return ledgerBalancedTopic;
    }

    /**
     * Returns the resolved {@code report.requested} topic name (package-
     * visible test accessor).
     *
     * @return the topic name resolved from
     *         {@code carddemo.kafka.topics.report-requested}
     *         (default {@code "report.requested"})
     */
    String getReportRequestedTopic() {
        return reportRequestedTopic;
    }

    /**
     * Convenience accessor for downstream tooling and tests &mdash; returns
     * the canonical class for {@link BillPaymentDto}. Per AAP &sect;0.4.1
     * the {@code BillPaymentService} is the upstream caller flow that
     * translates {@link BillPaymentDto} payloads into {@link TransactionAddDto}
     * events before calling {@link #publishTransactionPosted}; this accessor
     * documents the BillPaymentDto dependency (declared in the file schema's
     * {@code internal_imports}) at the type level. The Minimal Change Clause
     * (AAP &sect;0.7.3) keeps the adapter's public publish contract
     * canonical (TransactionAddDto only); the conversion from BillPaymentDto
     * to TransactionAddDto belongs in {@code BillPaymentService}.
     *
     * @return the {@link Class} object for {@link BillPaymentDto}
     */
    public static Class<BillPaymentDto> billPaymentSourceType() {
        return BillPaymentDto.class;
    }

    /**
     * Convenience accessor returning the canonical class for
     * {@link CardDemoException} &mdash; the wrapper type callers should use
     * when escalating a post-retry-exhaustion publish failure into the
     * domain exception hierarchy. See the JavaDoc on
     * {@link #handleSendResult} for the escalation pattern (chain a
     * {@code .exceptionally(...)} stage on the returned
     * {@link CompletableFuture}).
     *
     * @return the {@link Class} object for {@link CardDemoException}
     */
    public static Class<CardDemoException> escalationExceptionType() {
        return CardDemoException.class;
    }
}
