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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Apache Kafka / Amazon MSK producer adapter — the sole entry point for every
 * inter-service event published by the CardDemo Java target.
 *
 * <p>Per AAP &sect;0.7.1 ("MSK (Kafka) topics used for all inter-service
 * transaction events &mdash; partition by account ID") and AAP &sect;0.6.5
 * ("MSK Topic Ordering Guarantees"), this adapter is the single chokepoint
 * for all event publication. Every call produces a record with:</p>
 *
 * <ul>
 *   <li><b>Partition key = zero-padded 11-digit account ID</b> via
 *       {@link #accountKey(long)}. This guarantees per-account ordering
 *       across producers because Kafka's default partitioner hashes the
 *       key bytes and consistently maps the same key to the same partition.
 *       The 11-digit format matches the COBOL {@code PIC 9(11)} ACCT-ID
 *       layout ({@code app/cpy/CVACT01Y.cpy:L6}) and the Redis cache key
 *       format used by {@link CacheService}.</li>
 *   <li><b>Idempotent producer</b> with {@code acks=all},
 *       {@code enable.idempotence=true}, {@code retries=Integer.MAX_VALUE},
 *       {@code max.in.flight.requests.per.connection=5} configured in
 *       {@code KafkaConfig.producerFactory()}.</li>
 *   <li><b>JSON value serialization</b> for human-inspectable bodies, via
 *       Spring Kafka's {@code JsonSerializer}.</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.6.5)</h2>
 * <p>Replaces: CICS Transient Data Queues (TDQs) and intra-COBOL
 * inter-program {@code LINK} calls that the original mainframe used for
 * inter-service event flow. Specifically:</p>
 * <ul>
 *   <li>{@code app/cbl/CORPT00C.cbl} &mdash; report submission: previously
 *       wrote to CICS TDQ {@code JOBS}; now publishes to
 *       {@code report.requested} Kafka topic.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} &mdash; transaction add: previously
 *       updated VSAM and emitted no event; now also publishes
 *       {@code transaction.posted}.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} &mdash; bill payment: now publishes
 *       {@code account.updated} after the dual write.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update: now publishes
 *       {@code account.updated}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; interest calculation: publishes
 *       {@code account.updated} per account whose interest was applied, and
 *       {@code ledger.balanced} when the EOD reconciliation completes.</li>
 * </ul>
 *
 * <h2>Topic catalog (AAP &sect;0.7.1, &sect;0.6.5)</h2>
 * <ul>
 *   <li>{@code transaction.posted} &mdash; emitted by transaction add and
 *       posting flows; partitioned by account ID.</li>
 *   <li>{@code account.updated} &mdash; emitted by every account-mutating
 *       flow (debit, credit, interest, manual update).</li>
 *   <li>{@code ledger.balanced} &mdash; emitted by EOD reconciliation upon
 *       successful debit/credit balance.</li>
 *   <li>{@code report.requested} &mdash; emitted by the online report
 *       submission flow (replacing the CORPT00C → TDQ JOBS → JES bridge),
 *       consumed by {@link KafkaEventConsumer} which triggers a Step
 *       Functions execution.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>{@link KafkaTemplate} is thread-safe and intended to be shared. The
 * adapter holds a single template reference and exposes only stateless
 * methods; topic names are injected at construction time.</p>
 *
 * @see com.awsm2.carddemo.config.KafkaConfig
 * @see com.awsm2.carddemo.adapter.KafkaEventConsumer
 */
@Component
public class KafkaEventPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Reason code for publish failures wrapped as {@link CardDemoException}. */
    static final String REASON_CODE_PUBLISH_ERROR = "KAFKA_PUBLISH_ERROR";

    /**
     * Partition-key format. Account IDs are zero-padded to 11 digits so the
     * Kafka default partitioner (murmur2 over key bytes) produces a stable
     * hash regardless of the numeric value's leading-zero count. Matches:
     * <ul>
     *   <li>{@code app/cpy/CVACT01Y.cpy:L6} &mdash; {@code ACCT-ID PIC 9(11)}.</li>
     *   <li>{@link CacheService#ACCOUNT_KEY_FORMAT} &mdash; same 11-digit
     *       canonical form used in Redis cache keys.</li>
     * </ul>
     */
    static final String PARTITION_KEY_FORMAT = "%011d";

    /**
     * Default timeout for synchronous send operations. Bounded at 10
     * seconds to prevent a stalled producer from blocking a request thread
     * indefinitely; this is the same upper bound used in
     * {@code KafkaConfig.producerFactory().delivery-timeout-ms}.
     */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topicTransactionPosted;
    private final String topicAccountUpdated;
    private final String topicLedgerBalanced;
    private final String topicReportRequested;

    /**
     * Constructor injection. Spring supplies the shared
     * {@link KafkaTemplate} bean (produced by {@code KafkaConfig}) and
     * resolves the four topic-name properties at startup.
     *
     * @param kafkaTemplate          the shared KafkaTemplate bean; never
     *                               {@code null}
     * @param topicTransactionPosted topic name for {@code transaction.posted}
     * @param topicAccountUpdated    topic name for {@code account.updated}
     * @param topicLedgerBalanced    topic name for {@code ledger.balanced}
     * @param topicReportRequested   topic name for {@code report.requested}
     */
    public KafkaEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${carddemo.kafka.topics.transaction-posted:transaction.posted}")
            String topicTransactionPosted,
            @Value("${carddemo.kafka.topics.account-updated:account.updated}")
            String topicAccountUpdated,
            @Value("${carddemo.kafka.topics.ledger-balanced:ledger.balanced}")
            String topicLedgerBalanced,
            @Value("${carddemo.kafka.topics.report-requested:report.requested}")
            String topicReportRequested) {
        // Replaces: CICS TDQ JOBS (CORPT00C) and inter-program LINK calls
        // — now MSK Kafka with per-account ordering via partition key.
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate,
                "kafkaTemplate must not be null");
        this.topicTransactionPosted = Objects.requireNonNull(topicTransactionPosted,
                "topicTransactionPosted must not be null");
        this.topicAccountUpdated = Objects.requireNonNull(topicAccountUpdated,
                "topicAccountUpdated must not be null");
        this.topicLedgerBalanced = Objects.requireNonNull(topicLedgerBalanced,
                "topicLedgerBalanced must not be null");
        this.topicReportRequested = Objects.requireNonNull(topicReportRequested,
                "topicReportRequested must not be null");
    }

    // ---------------------------------------------------------------------
    // Public API — one method per business event
    // ---------------------------------------------------------------------

    /**
     * Publishes a {@code transaction.posted} event partitioned by the
     * supplied account ID. Used by {@code TransactionAddService} and
     * {@code BillPaymentService} after a successful transaction write.
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}; the partition key
     * @param payload   event payload (typically a DTO with transaction
     *                  details); must not be {@code null}
     * @return the Kafka send metadata (offset, partition) on success
     * @throws CardDemoException with reason
     *         {@link #REASON_CODE_PUBLISH_ERROR} on broker / serialization
     *         failure
     */
    public SendResult<String, Object> publishTransactionPosted(long accountId, Object payload) {
        // COBOL: COTRN02C (PROCESS-TRAN-ADD) emits transaction.posted after
        // VSAM REWRITE of TRANSACT cluster. Per AAP §0.6.5 the partition key
        // is the account ID to guarantee per-account ordering.
        return publish(topicTransactionPosted, accountKey(accountId), payload);
    }

    /**
     * Publishes an {@code account.updated} event partitioned by the
     * supplied account ID. Used by {@code AccountUpdateService},
     * {@code BillPaymentService}, and {@code InterestCalculationService}
     * after a successful account mutation.
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}; the partition key
     * @param payload   event payload (before/after image or balance delta)
     * @return the Kafka send metadata on success
     * @throws CardDemoException on publish failure
     */
    public SendResult<String, Object> publishAccountUpdated(long accountId, Object payload) {
        // COBOL: COACTUPC (UPDATE-ACCT) + COBIL00C (PROCESS-PAYMENT) +
        // CBACT04C (CALC-INTEREST) emit account.updated after the dual
        // write completes inside @Transactional. Per-account ordering is
        // mandatory because downstream balance projections must see updates
        // in the order the application applied them.
        return publish(topicAccountUpdated, accountKey(accountId), payload);
    }

    /**
     * Publishes a {@code ledger.balanced} event partitioned by the
     * supplied account ID. Used by end-of-day reconciliation upon successful
     * debit/credit balance.
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}; the partition key
     * @param payload   event payload (reconciliation totals, run ID)
     * @return the Kafka send metadata on success
     * @throws CardDemoException on publish failure
     */
    public SendResult<String, Object> publishLedgerBalanced(long accountId, Object payload) {
        // COBOL: CBTRN02C end-of-batch reconciliation. Emits ledger.balanced
        // when DEBIT = CREDIT for the account's transaction set.
        return publish(topicLedgerBalanced, accountKey(accountId), payload);
    }

    /**
     * Publishes a {@code report.requested} event used to bridge the online
     * report submission flow ({@code CORPT00C}) to the batch report job
     * (Step Functions). The partition key is the supplied {@code reportId}
     * (or account ID if scoped) so consumers see per-key ordering.
     *
     * <p>Unlike the {@code transaction.posted} / {@code account.updated}
     * topics where the key is always an account ID, the {@code report.requested}
     * key is the report's own identifier (or a user-scoped value) because
     * report execution is keyed to the originating request, not to any
     * specific account.</p>
     *
     * @param reportKey partition key (report ID or originator user ID); must
     *                  not be {@code null} or blank
     * @param payload   event payload (report parameters, date range,
     *                  format); must not be {@code null}
     * @return the Kafka send metadata on success
     * @throws CardDemoException on publish failure
     */
    public SendResult<String, Object> publishReportRequested(String reportKey, Object payload) {
        // COBOL: CORPT00C — bridges the online flow to the batch flow that
        // historically lived in JES. Now consumed by KafkaEventConsumer
        // (onReportRequested) which starts a Step Functions execution per
        // AAP §0.1.1 "online-to-batch bridge".
        if (reportKey == null || reportKey.isBlank()) {
            throw new IllegalArgumentException("reportKey must not be null/blank");
        }
        return publish(topicReportRequested, reportKey, payload);
    }

    // ---------------------------------------------------------------------
    // Shared publish path
    // ---------------------------------------------------------------------

    /**
     * Synchronous send with a bounded timeout. The {@link CompletableFuture}
     * returned by {@link KafkaTemplate#send} is awaited up to
     * {@link #SEND_TIMEOUT_SECONDS} so request threads cannot block
     * indefinitely on a stalled broker. Failures are wrapped as typed
     * {@link CardDemoException} with reason {@link #REASON_CODE_PUBLISH_ERROR}.
     */
    private SendResult<String, Object> publish(String topic, String key, Object payload) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be null/blank");
        }
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            // Spring 6 KafkaTemplate returns CompletableFuture<SendResult>.
            CompletableFuture<SendResult<String, Object>> future =
                    kafkaTemplate.send(topic, key, payload);
            SendResult<String, Object> result =
                    future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // PCI-DSS-safe logging: topic / partition / offset / key only —
            // never the payload body (AAP §0.6.6 — payloads may contain
            // PAN-like or PII fields).
            LOG.info("Kafka send OK topic={} partition={} offset={} key={}",
                    topic,
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    key);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Kafka send INTERRUPTED topic={} key={}", topic, key, e);
            throw new CardDemoException(
                    REASON_CODE_PUBLISH_ERROR,
                    "Interrupted while publishing to topic=" + topic,
                    e);
        } catch (ExecutionException | TimeoutException e) {
            // ExecutionException wraps the underlying broker / serialization
            // failure; TimeoutException indicates the broker did not ack
            // within SEND_TIMEOUT_SECONDS (likely a network or broker stall).
            LOG.error("Kafka send FAILED topic={} key={} cause={}",
                    topic, key, e.getMessage(), e);
            throw new CardDemoException(
                    REASON_CODE_PUBLISH_ERROR,
                    "Failed to publish to topic=" + topic,
                    e);
        }
    }

    /**
     * Builds the canonical partition key for the supplied account ID:
     * zero-padded 11-digit decimal. Visible to other adapters via package
     * scope so test fixtures and downstream sanity checks can produce
     * matching keys without reimplementing the format string.
     *
     * @param accountId 11-digit COBOL {@code ACCT-ID}
     * @return the formatted key (e.g., {@code "00000012345"})
     */
    public static String accountKey(long accountId) {
        return String.format(PARTITION_KEY_FORMAT, accountId);
    }
}
