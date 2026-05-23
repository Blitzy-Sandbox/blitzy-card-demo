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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Audit log adapter &mdash; writes structured audit events to Amazon
 * OpenSearch for searchable retention and emits CloudWatch metrics for
 * alarm integration.
 *
 * <p>Per AAP &sect;0.6.6 ("Cross-Cutting: Audit, Observability, and
 * PCI-DSS"): <em>"Amazon OpenSearch indexes both CloudTrail events and
 * application-emitted audit logs (transaction IDs, timestamps, operator
 * codes &mdash; preserved verbatim from COBOL audit trail writes). The
 * {@code AuditLogService} writes to OpenSearch via the REST high-level
 * client; failures are buffered to a local SQS DLQ for retry."</em></p>
 *
 * <p>This adapter is the <strong>sole place</strong> in
 * {@code src/main/java/com/awsm2/carddemo/} where application code emits
 * audit records to OpenSearch. CloudTrail integration is an
 * infrastructure-level concern (an IAM-managed organization trail in
 * {@code infrastructure/terraform/cloudtrail.tf}) and is therefore
 * <strong>not</strong> invoked from application code &mdash; this adapter
 * complements CloudTrail by capturing application-level events such as
 * business decisions, validation failures, and batch job lifecycle
 * transitions that CloudTrail does not observe.</p>
 *
 * <h2>Replaces (AAP &sect;0.7.2)</h2>
 * <p>The COBOL source has no centralized audit-trail subsystem; instead,
 * individual programs emit {@code DISPLAY} statements and write trailer
 * records on rejection. This adapter consolidates audit emission for:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} &mdash; {@code WRITE DALYREJS-RECORD}
 *       (transaction rejection trailers, verbatim reject codes
 *       100&ndash;109 preserved per AAP &sect;0.7.2).</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} &mdash; {@code DISPLAY} of interest
 *       postings with account ID, timestamp, and amount.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; account/customer change
 *       records (before-image vs after-image, captured around
 *       {@code SYNCPOINT}/{@code SYNCPOINT ROLLBACK}).</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} &mdash; payment posting records.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} &mdash; manual transaction posting
 *       records.</li>
 *   <li>{@code app/cbl/CSUTLDTC.cbl} &mdash; date validation
 *       diagnostics.</li>
 * </ul>
 *
 * <h2>Public API (AAP &sect;0.6.6 / schema)</h2>
 * <p>Four asynchronous emission methods cover the audit taxonomy:</p>
 * <ul>
 *   <li>{@link #logTransactionEvent} &mdash; transaction lifecycle
 *       events keyed by {@code transactionId} for idempotent retries.
 *       Indexed into the transaction index.</li>
 *   <li>{@link #logAuditEvent} &mdash; generic business audit events
 *       (configuration changes, batch lifecycle, etc.). UUID-keyed,
 *       indexed into the audit index.</li>
 *   <li>{@link #logSecurityEvent} &mdash; authentication, authorization,
 *       and user-admin events. UUID-keyed, indexed into the dedicated
 *       security index for compliance retention.</li>
 *   <li>{@link #logBatchJobLifecycle} &mdash; Spring Batch
 *       {@code JobExecution} start / complete / fail transitions
 *       complementing AWS Batch + Step Functions CloudWatch events.
 *       Indexed into the audit index with a deterministic composite
 *       ID {@code executionId + "_" + status}.</li>
 * </ul>
 *
 * <h2>Asynchrony and non-blocking failure mode (AAP &sect;0.6.6)</h2>
 * <p>Every public method is annotated {@code @Async} so callers never
 * block on OpenSearch indexing latency &mdash; {@code @EnableAsync} is
 * declared on {@code com.awsm2.carddemo.CardDemoApplication} and the
 * Spring task executor dispatches each invocation onto a worker thread.
 * Transport-level failures are caught at the adapter boundary and logged
 * at {@code ERROR}; they are <strong>never</strong> re-thrown to the
 * caller because the originating business flow has already committed
 * and an audit-write failure must not invalidate it. Per AAP &sect;0.6.6
 * a future iteration will enqueue failed documents to a local SQS DLQ
 * for retry (out of scope per the Minimal Change Clause &mdash; the
 * current implementation logs the failure with full operational metadata
 * so operations can detect and alarm).</p>
 *
 * <h2>Verbatim preservation (AAP &sect;0.7.2)</h2>
 * <p>Reject codes ("100", "101", "102", "103", "104", &hellip;, "109"),
 * transaction IDs, account IDs, operator codes, and timestamps are passed
 * through to OpenSearch <strong>verbatim</strong> from caller arguments.
 * This adapter does <em>not</em> translate codes to human-readable
 * strings &mdash; downstream regulatory queries and fraud investigation
 * dashboards rely on the COBOL-original values for traceability.</p>
 *
 * <h2>PCI-DSS discipline (AAP &sect;0.6.6, &sect;0.7.2)</h2>
 * <p>Callers are responsible for passing only <strong>masked</strong>
 * primary account numbers (PAN) and must <strong>never</strong> include
 * CVV, plaintext password, or full SSN values in audit documents. As a
 * defense-in-depth measure this adapter sanitizes every supplied payload
 * via {@link #sanitizePayload(Map)} which:</p>
 * <ul>
 *   <li><strong>Removes</strong> entries whose key (case-insensitive)
 *       matches the secrets allowlist
 *       ({@code password}, {@code pwd}, {@code passwd}, {@code secret},
 *       {@code token}, {@code apiKey}, {@code authorization}, {@code cvv},
 *       {@code cvc}, {@code pin}, {@code privateKey},
 *       {@code clientSecret}).</li>
 *   <li><strong>Masks</strong> any string value matching the PAN-like
 *       13&ndash;19-digit sequence regex by replacing all but the
 *       trailing four digits with {@code '*'} characters
 *       (e.g. {@code "4111111111111111"} &rarr;
 *       {@code "************1111"}).</li>
 *   <li><strong>Recurses</strong> into nested {@link Map} values to
 *       sanitize substructures.</li>
 * </ul>
 *
 * <h2>Metrics (AAP &sect;0.6.6)</h2>
 * <p>Each public emission method also increments a Micrometer
 * {@link Counter} for CloudWatch alarm integration. The
 * {@link MeterRegistry} bean is wired by
 * {@code com.awsm2.carddemo.config.CloudWatchConfig} and publishes to
 * CloudWatch under namespace {@code CardDemo} in non-local profiles. The
 * counter names are stable and form part of the alarm contract:</p>
 * <ul>
 *   <li>{@code carddemo.audit.transaction} (tags: {@code event_type},
 *       {@code reason_code})</li>
 *   <li>{@code carddemo.audit.event} (tag: {@code event_type})</li>
 *   <li>{@code carddemo.audit.security} (tags: {@code event_type},
 *       {@code result})</li>
 *   <li>{@code carddemo.batch.lifecycle} (tags: {@code job_name},
 *       {@code status})</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>This adapter is stateless beyond the injected collaborators (the
 * {@link OpenSearchIndexer} and the {@link MeterRegistry}, both of which
 * are themselves thread-safe). Concurrent invocations across worker
 * threads are safe; each call constructs its own document map and
 * counter handle.</p>
 *
 * @see com.awsm2.carddemo.adapter.OpenSearchIndexer
 * @see com.awsm2.carddemo.config.CloudWatchConfig
 */
// Replaces: COBOL DISPLAY statements + DALYREJS rejection trailers + DB2 timestamps across app/cbl/*.cbl
@Service
public class AuditLogService {

    /**
     * SLF4J logger emitting ERROR-level operational diagnostics on audit
     * emission failures. Feeds the Logback + logstash-logback-encoder JSON
     * pipeline that ships structured logs to CloudWatch Logs per AAP
     * &sect;0.6.6.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AuditLogService.class);

    /**
     * Counter name &mdash; transaction audit emissions (success or reject).
     * Tags: {@code event_type}, {@code reason_code}.
     */
    private static final String METRIC_TRANSACTION = "carddemo.audit.transaction";

    /**
     * Counter name &mdash; generic business audit emissions. Tag:
     * {@code event_type}.
     */
    private static final String METRIC_EVENT = "carddemo.audit.event";

    /**
     * Counter name &mdash; security audit emissions. Tags:
     * {@code event_type}, {@code result}.
     */
    private static final String METRIC_SECURITY = "carddemo.audit.security";

    /**
     * Counter name &mdash; batch job lifecycle emissions. Tags:
     * {@code job_name}, {@code status}.
     */
    private static final String METRIC_BATCH = "carddemo.batch.lifecycle";

    /**
     * Sensitive field-name allowlist used by {@link #sanitizePayload(Map)}.
     * Match is performed case-insensitively on the lowercased key. The set
     * is immutable.
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "pwd", "passwd",
            "secret", "secrets",
            "token", "accesstoken", "refreshtoken",
            "apikey", "api_key",
            "authorization", "auth",
            "cvv", "cvc",
            "pin",
            "privatekey", "private_key",
            "clientsecret", "client_secret"
    );

    /**
     * PAN-like sequence regex: 13&ndash;19 consecutive digits aligned to a
     * word boundary. Matches the major card-brand BIN-and-length
     * combinations (Visa 16, Mastercard 16, Amex 15, Diners 14, common
     * loyalty 13&ndash;19). Used by {@link #maskPanLike(String)} to mask
     * card numbers that may have leaked into free-text fields.
     */
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b\\d{13,19}\\b");

    /**
     * Result code used by {@link #logTransactionEvent} when the caller
     * passes {@code null} as the reason code &mdash; preserves the
     * "OK / no reject" semantic in the metric dimension so dashboards can
     * filter successful posts from rejected ones.
     */
    private static final String REASON_CODE_OK = "OK";

    /**
     * The OpenSearch indexer adapter &mdash; the sole side-effect channel
     * for audit events. {@code @Async} dispatches each public method to a
     * Spring task executor; the indexer call happens synchronously on the
     * worker thread.
     */
    private final OpenSearchIndexer openSearchIndexer;

    /**
     * The Micrometer meter registry &mdash; bridged to CloudWatch by
     * {@code com.awsm2.carddemo.config.CloudWatchConfig} under namespace
     * {@code CardDemo}. {@code Counter} handles are obtained from the
     * registry on each emission rather than cached, because tag
     * combinations vary per call and Micrometer's internal cache reuses
     * counters with the same name + tag set.
     */
    private final MeterRegistry meterRegistry;

    /**
     * Externalized OpenSearch index name for generic audit events.
     * Defaults to {@code carddemo-audit}; may be overridden per profile
     * via {@code carddemo.opensearch.audit-index}.
     */
    @Value("${carddemo.opensearch.audit-index:carddemo-audit}")
    private String auditIndexName;

    /**
     * Externalized OpenSearch index name for transaction events.
     * Defaults to {@code carddemo-transactions}; may be overridden per
     * profile via {@code carddemo.opensearch.transaction-index}.
     */
    @Value("${carddemo.opensearch.transaction-index:carddemo-transactions}")
    private String transactionIndexName;

    /**
     * Externalized OpenSearch index name for security events. Defaults to
     * {@code carddemo-security}; may be overridden per profile via
     * {@code carddemo.opensearch.security-index}.
     */
    @Value("${carddemo.opensearch.security-index:carddemo-security}")
    private String securityIndexName;

    /**
     * Constructor injection per AAP &sect;0.7.1 ("Dependency injection for
     * loose coupling"). Both collaborators must be non-{@code null} &mdash;
     * Spring DI normally guarantees this, but the explicit
     * {@link Objects#requireNonNull(Object, String)} guard protects unit
     * tests, ad-hoc instantiations, and bean-definition errors.
     *
     * @param openSearchIndexer the OpenSearch indexer bean; never
     *                          {@code null}
     * @param meterRegistry     the Micrometer meter registry bean
     *                          (wired by {@code CloudWatchConfig}); never
     *                          {@code null}
     */
    public AuditLogService(OpenSearchIndexer openSearchIndexer,
                           MeterRegistry meterRegistry) {
        this.openSearchIndexer = Objects.requireNonNull(openSearchIndexer,
                "openSearchIndexer must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry,
                "meterRegistry must not be null");
    }

    // =========================================================================
    // Public API — schema-required audit emission methods (AAP §0.6.6)
    // =========================================================================

    /**
     * Records a transaction-lifecycle audit event into OpenSearch and
     * increments the {@code carddemo.audit.transaction} Micrometer counter.
     *
     * <p>This is the primary audit path for the daily transaction posting
     * pipeline ({@code CBTRN02C.cbl}), interactive transaction add
     * ({@code COTRN02C.cbl}), bill payment ({@code COBIL00C.cbl}), and
     * interest accrual ({@code CBACT04C.cbl}). Preserves verbatim
     * transaction IDs, timestamps, operator codes, and reject codes per
     * AAP &sect;0.7.2 audit-trail rule.</p>
     *
     * <p>The OpenSearch document is keyed by the supplied
     * {@code transactionId} so that retries on transport failure produce
     * the same document ID and OpenSearch upserts rather than duplicates
     * (idempotent dedup-on-retry).</p>
     *
     * @param transactionId verbatim {@code TRAN-ID} from
     *                      {@code CVTRA05Y.cpy} (16-byte). When
     *                      {@code null} or blank, a UUID falls back as
     *                      the document ID; callers should always supply
     *                      a transaction ID for idempotent retries.
     * @param accountId     11-digit {@code ACCT-ID} from
     *                      {@code CVACT01Y.cpy}; may be {@code null} for
     *                      events that have no account context
     * @param operatorCode  user/operator identifier
     *                      ({@code SEC-USR-ID} from {@code CSUSR01Y.cpy};
     *                      or a synthetic system marker such as
     *                      {@code "BATCH/POSTTRAN"})
     * @param eventType     short discriminator
     *                      ({@code "TRANSACTION_POSTED"},
     *                      {@code "TRANSACTION_REJECTED"},
     *                      {@code "INTEREST_CALCULATED"},
     *                      {@code "BILL_PAID"}, {@code "ACCOUNT_UPDATED"});
     *                      should never be {@code null} or blank but is
     *                      tolerated either way
     * @param reasonCode    verbatim COBOL reject code (e.g. {@code "100"},
     *                      {@code "101"}, {@code "102"}, {@code "103"},
     *                      {@code "104"}, &hellip;, {@code "109"}) or
     *                      {@code null} on success. NEVER translated to
     *                      a human-readable message per AAP &sect;0.7.2.
     * @param payload       optional structured fields (amount, currency,
     *                      masked card number, merchant ID, etc.).
     *                      Subject to PII / PAN sanitization.
     * @param correlationId MDC-derived correlation ID for end-to-end
     *                      traceability; may be {@code null}
     */
    @Async
    public void logTransactionEvent(String transactionId,
                                    Long accountId,
                                    String operatorCode,
                                    String eventType,
                                    String reasonCode,
                                    Map<String, Object> payload,
                                    String correlationId) {
        // Replaces: CBTRN02C 2500-WRITE-REJECT-REC + COBIL00C 9300-WRITE-TRANSACT-FILE
        // + COTRN02C (similar pattern) + CBACT04C interest-posting DISPLAY
        try {
            // Build the structured envelope. buildBaseDocument populates
            // @timestamp, correlation_id, and application name; we layer
            // transaction-specific fields on top of that.
            Map<String, Object> doc = buildBaseDocument(correlationId);
            doc.put("transaction_id", transactionId);
            doc.put("account_id", accountId);
            doc.put("operator_code", operatorCode);
            doc.put("event_type", eventType);
            // Reason code is preserved verbatim — see class JavaDoc and
            // AAP §0.7.2: reject codes "100"–"109" must never be
            // translated to human-readable strings here.
            doc.put("reason_code", reasonCode);
            // Sanitize the caller-supplied payload before merging — this
            // is the defense-in-depth control mandated by AAP §0.6.6
            // PCI-DSS discipline. The caller is the primary line of
            // defense (masked PAN, no CVV, no plaintext password); this
            // sanitization is the second.
            mergeSanitizedPayload(doc, payload);

            // Use transactionId as the OpenSearch document ID when the
            // caller provided one — guarantees idempotent retries on
            // transient OpenSearch failures. Fall back to a UUID
            // otherwise so we still record the event without colliding
            // with any existing document.
            String docId = (transactionId != null && !transactionId.isBlank())
                    ? transactionId
                    : UUID.randomUUID().toString();
            openSearchIndexer.indexDocument(transactionIndexName, docId, doc);

            // Increment the per-event-type, per-reason-code counter so
            // CloudWatch dashboards can graph the rate of each reject
            // code (especially 100–109) for fraud-team monitoring.
            Counter.builder(METRIC_TRANSACTION)
                    .tag("event_type", nullSafeTag(eventType))
                    .tag("reason_code", reasonCode != null ? reasonCode : REASON_CODE_OK)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            // Per AAP §0.6.6: audit failures are logged but NEVER thrown
            // to callers; a future iteration will route failed documents
            // to an SQS DLQ for retry. Operational metadata only — never
            // log the document body which may carry PII.
            LOG.error("Transaction audit emission FAILED transactionId={} eventType={} reasonCode={} cause={}",
                    transactionId, eventType, reasonCode, e.getMessage(), e);
        }
    }

    /**
     * Records a generic business audit event (configuration changes,
     * data-corrective writes, manual interventions, etc.) into the audit
     * index and increments the {@code carddemo.audit.event} Micrometer
     * counter.
     *
     * <p>A UUID document ID is generated because the caller's audit event
     * has no natural primary key &mdash; if you do have a stable key,
     * prefer the more specific
     * {@link #logTransactionEvent} or
     * {@link #logBatchJobLifecycle}.</p>
     *
     * @param eventType     short discriminator (e.g.
     *                      {@code "CONFIG_CHANGE"},
     *                      {@code "MANUAL_ADJUSTMENT"}); should not be
     *                      {@code null} but is tolerated
     * @param resourceType  kind of resource the event acted on
     *                      ({@code "ACCOUNT"}, {@code "CARD"},
     *                      {@code "CUSTOMER"}, etc.)
     * @param resourceId    identifier of the affected resource
     * @param operatorCode  user/operator identifier
     * @param payload       optional structured fields; subject to PII /
     *                      PAN sanitization
     * @param correlationId MDC-derived correlation ID; may be {@code null}
     */
    @Async
    public void logAuditEvent(String eventType,
                              String resourceType,
                              String resourceId,
                              String operatorCode,
                              Map<String, Object> payload,
                              String correlationId) {
        // Replaces: COBOL DISPLAY statements for operational diagnostics throughout app/cbl/*.cbl
        try {
            // UUIDs are non-deterministic, so retries on transport
            // failure would create duplicates; for idempotent retries
            // callers should compose a deterministic ID and use the
            // OpenSearchIndexer directly. For ordinary audit events
            // (configuration changes, etc.) the duplicate risk is
            // tolerable because the event semantics are "this happened
            // at least once".
            String id = UUID.randomUUID().toString();

            Map<String, Object> doc = buildBaseDocument(correlationId);
            doc.put("event_type", eventType);
            doc.put("resource_type", resourceType);
            doc.put("resource_id", resourceId);
            doc.put("operator_code", operatorCode);
            mergeSanitizedPayload(doc, payload);

            openSearchIndexer.indexDocument(auditIndexName, id, doc);

            Counter.builder(METRIC_EVENT)
                    .tag("event_type", nullSafeTag(eventType))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            LOG.error("Audit event emission FAILED eventType={} resourceType={} resourceId={} cause={}",
                    eventType, resourceType, resourceId, e.getMessage(), e);
        }
    }

    /**
     * Records a security audit event (sign-on success/failure, user
     * add/update/delete, role change, password change) into the dedicated
     * security index and increments the {@code carddemo.audit.security}
     * Micrometer counter.
     *
     * <p>A separate index ({@code carddemo-security} by default) is used
     * so compliance retention policies, access controls, and analytics
     * dashboards can be applied independently of business audit data
     * &mdash; aligns with PCI-DSS Requirement 10 (audit logs).</p>
     *
     * @param eventType     short discriminator
     *                      ({@code "SIGNON_SUCCESS"},
     *                      {@code "SIGNON_FAILURE"},
     *                      {@code "USER_ADD"}, {@code "USER_DELETE"},
     *                      {@code "PASSWORD_CHANGE"},
     *                      {@code "ROLE_CHANGE"})
     * @param userId        target user ID ({@code SEC-USR-ID} from
     *                      {@code CSUSR01Y.cpy})
     * @param result        outcome label, typically {@code "SUCCESS"} or
     *                      {@code "FAILURE"}; preserved as a metric tag
     *                      to drive failure-rate alarms
     * @param sourceIp      source IP address of the requesting client
     * @param payload       optional structured fields (user agent,
     *                      auth-method, etc.); subject to PII / PAN
     *                      sanitization
     * @param correlationId MDC-derived correlation ID; may be {@code null}
     */
    @Async
    public void logSecurityEvent(String eventType,
                                 String userId,
                                 String result,
                                 String sourceIp,
                                 Map<String, Object> payload,
                                 String correlationId) {
        // Replaces: COSGN00C signon DISPLAY, COUSR01C/02C/03C user admin DISPLAY
        try {
            String id = UUID.randomUUID().toString();

            Map<String, Object> doc = buildBaseDocument(correlationId);
            doc.put("event_type", eventType);
            doc.put("user_id", userId);
            doc.put("result", result);
            doc.put("source_ip", sourceIp);
            mergeSanitizedPayload(doc, payload);

            openSearchIndexer.indexDocument(securityIndexName, id, doc);

            Counter.builder(METRIC_SECURITY)
                    .tag("event_type", nullSafeTag(eventType))
                    .tag("result", nullSafeTag(result))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            LOG.error("Security audit emission FAILED eventType={} userId={} result={} sourceIp={} cause={}",
                    eventType, userId, result, sourceIp, e.getMessage(), e);
        }
    }

    /**
     * Records a Spring Batch / AWS Batch job lifecycle event into the
     * audit index and increments the {@code carddemo.batch.lifecycle}
     * Micrometer counter.
     *
     * <p>Captures application-level batch lifecycle (Spring Batch
     * {@code JobExecution} start, complete, fail, abend). AWS Batch and
     * Step Functions also emit CloudWatch events &mdash; this method
     * complements those by recording the same events alongside business
     * audit data, enabling end-to-end correlation between a Step
     * Functions execution and the application-level activity it
     * performed.</p>
     *
     * <p>The document ID combines {@code executionId} with the
     * {@code status} (e.g. {@code "exec-42_COMPLETED"}) so that a
     * job's start and complete events produce distinct documents while
     * remaining easily joinable on {@code executionId}.</p>
     *
     * @param jobName        Spring Batch job name ({@code "POSTTRAN"},
     *                       {@code "INTCALC"}, {@code "COMBTRAN"},
     *                       {@code "CREASTMT"}, {@code "TRANREPT"})
     * @param executionId    {@code JobExecution} identifier
     * @param status         lifecycle status
     *                       ({@code "STARTED"}, {@code "COMPLETED"},
     *                       {@code "FAILED"}, {@code "ABENDED"})
     * @param durationMillis elapsed time in milliseconds, or {@code null}
     *                       if not yet known (e.g. {@code STARTED})
     * @param payload        optional structured fields (parameter map,
     *                       record counts, etc.); subject to PII / PAN
     *                       sanitization
     * @param correlationId  MDC-derived correlation ID; may be
     *                       {@code null}
     */
    @Async
    public void logBatchJobLifecycle(String jobName,
                                     String executionId,
                                     String status,
                                     Long durationMillis,
                                     Map<String, Object> payload,
                                     String correlationId) {
        // Replaces: JES SYSPRINT + RETURN-CODE for POSTTRAN/INTCALC/COMBTRAN/CREASTMT/TRANREPT jobs
        try {
            Map<String, Object> doc = buildBaseDocument(correlationId);
            doc.put("job_name", jobName);
            doc.put("execution_id", executionId);
            doc.put("status", status);
            doc.put("duration_millis", durationMillis);
            mergeSanitizedPayload(doc, payload);

            // Deterministic composite ID — keeps start and complete
            // events in distinct documents while preserving a join key
            // on executionId. Fallback to UUID if either component is
            // null/blank, which prevents a misconfigured caller from
            // colliding on "null_null".
            String docId = (executionId != null && !executionId.isBlank()
                    && status != null && !status.isBlank())
                    ? executionId + "_" + status
                    : UUID.randomUUID().toString();

            openSearchIndexer.indexDocument(auditIndexName, docId, doc);

            Counter.builder(METRIC_BATCH)
                    .tag("job_name", nullSafeTag(jobName))
                    .tag("status", nullSafeTag(status))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            LOG.error("Batch lifecycle audit emission FAILED jobName={} executionId={} status={} cause={}",
                    jobName, executionId, status, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Public API — backwards-compatible convenience methods used by
    // KafkaEventConsumer. These predate the schema-required log* methods
    // above and delegate to logAuditEvent / logTransactionEvent so the
    // KafkaEventConsumer call-sites continue to compile.
    // =========================================================================

    /**
     * Convenience wrapper that emits a generic audit event without the
     * full {@link #logAuditEvent} parameter set. Used by
     * {@link KafkaEventConsumer} where the message envelope carries
     * {@code eventName} and {@code actor} as inseparable fields.
     *
     * <p>Internally this delegates to {@link #logAuditEvent} so the
     * structured envelope, sanitization, metric emission, and failure
     * handling remain centralized.</p>
     *
     * @param eventName event name (delegated to {@code eventType})
     * @param actor     actor identifier (delegated to
     *                  {@code operatorCode})
     * @param payload   structured payload; subject to sanitization
     */
    @Async
    public void auditEvent(String eventName, String actor, Map<String, Object> payload) {
        // Replaces: DISPLAY-of-events in COBOL programs — delegates to
        // logAuditEvent so the new schema-required path is exercised even
        // when called via the legacy two-argument shape.
        logAuditEvent(eventName, null, null, actor, payload, null);
    }

    /**
     * Convenience wrapper that emits a transaction-specific audit event
     * without the full {@link #logTransactionEvent} parameter set. Used
     * by {@link KafkaEventConsumer} when consuming the
     * {@code transaction.posted} topic, where each message carries an
     * {@code eventName}, {@code actor}, and verbatim
     * {@code transactionId}.
     *
     * @param eventName     event name (delegated to {@code eventType})
     * @param actor         actor identifier (delegated to
     *                      {@code operatorCode})
     * @param transactionId verbatim transaction ID
     * @param payload       structured payload; subject to sanitization
     */
    @Async
    public void auditTransaction(String eventName,
                                 String actor,
                                 String transactionId,
                                 Map<String, Object> payload) {
        // Replaces: CBTRN02C DISPLAY-of-rejects and per-transaction audit lines.
        // Delegates to logTransactionEvent — the new schema-required path —
        // preserving idempotent retry semantics (docId == transactionId).
        logTransactionEvent(transactionId, null, actor, eventName, null, payload, null);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build the envelope of fields common to every audit document:
     * {@code @timestamp} (current Instant as ISO-8601 string),
     * {@code correlation_id}, and {@code application} marker. Subclasses
     * of audit (transaction, security, batch) layer additional fields on
     * top of this base document.
     *
     * <p>Returning a fresh {@link HashMap} per invocation keeps the
     * adapter stateless and concurrent-safe.</p>
     *
     * @param correlationId optional MDC correlation ID; may be
     *                      {@code null}
     * @return a new mutable map prepopulated with the base envelope
     */
    private Map<String, Object> buildBaseDocument(String correlationId) {
        // Note: HashMap (not LinkedHashMap) is used here because the
        // schema's external_imports explicitly lists HashMap; the
        // top-level field order of an OpenSearch document does not affect
        // its indexed representation, only the JSON serialization order.
        Map<String, Object> doc = new HashMap<>();
        // ISO-8601 ("YYYY-MM-DDThh:mm:ss.sssZ") rendering of the current
        // instant — matches OpenSearch's default date detection so the
        // @timestamp field is automatically mapped as a date type.
        doc.put("@timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        doc.put("correlation_id", correlationId);
        doc.put("application", "carddemo");
        return doc;
    }

    /**
     * Sanitize the caller-supplied payload (via {@link #sanitizePayload})
     * and merge its entries into the supplied audit document. Skips
     * silently if the payload is {@code null} or empty.
     *
     * <p>Entries in the caller payload do NOT overwrite the envelope
     * fields set by {@link #buildBaseDocument(String)} or by the calling
     * {@code log*} method &mdash; the envelope is authoritative. This
     * defends against a payload that accidentally carries a
     * {@code "@timestamp"} or {@code "event_type"} key from a copy-paste
     * upstream.</p>
     *
     * @param doc     the target audit document (must not be {@code null})
     * @param payload optional caller-supplied fields
     */
    private void mergeSanitizedPayload(Map<String, Object> doc, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Map<String, Object> sanitized = sanitizePayload(payload);
        for (Map.Entry<String, Object> entry : sanitized.entrySet()) {
            // Don't let payload fields overwrite the envelope — the
            // envelope's @timestamp / correlation_id / event_type
            // semantics are owned by this adapter.
            if (!doc.containsKey(entry.getKey())) {
                doc.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Sanitize a payload map before persistence: removes sensitive keys
     * and masks PAN-like string values. Returns a new map; the input is
     * never mutated.
     *
     * <p>Public for unit testing (CardDemo test conventions per AAP
     * &sect;0.7.2 require white-box testing of PCI-DSS sanitization
     * logic); application callers should rely on the public
     * {@code log*} methods which call this method internally.</p>
     *
     * @param input arbitrary payload; may be {@code null} or empty
     * @return a sanitized copy &mdash; never {@code null}; an empty map
     *         if the input was empty or {@code null}
     */
    public Map<String, Object> sanitizePayload(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // Preserve insertion order so OpenSearch indexes fields in the
        // order the caller declared them — improves dashboard
        // readability.
        Map<String, Object> out = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // Step 1 — remove entries with sensitive keys (e.g. password,
            // cvv, pin, secret, token). Case-insensitive full-key match
            // avoids both false positives (e.g. "user_password_changed_at"
            // timestamps) and false negatives (e.g. "PWD" all-caps).
            if (key != null && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.US))) {
                continue;
            }
            // Step 2 — recurse into nested maps so substructures are
            // sanitized too. A value of any other type (Number, Boolean,
            // List) is passed through unchanged.
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedTyped = (Map<String, Object>) nested;
                out.put(key, sanitizePayload(nestedTyped));
            } else if (value instanceof String str) {
                // Step 3 — mask PAN-like sequences in string values.
                // Other sensitive scalars (passwords, tokens) are removed
                // by the key-name check above; this step handles the
                // case where a card number leaks into a description /
                // narration / merchant-name field.
                out.put(key, maskPanLike(str));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Mask any PAN-like 13&ndash;19-digit sequence in the supplied string
     * by replacing all but the trailing 4 digits with {@code '*'}. Used
     * by {@link #sanitizePayload(Map)} to defend against PAN leakage in
     * free-text fields.
     *
     * @param s the input string; may be {@code null} (returns {@code null})
     * @return the masked string, or the original if no PAN-like sequence
     *         was found
     */
    static String maskPanLike(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        var matcher = PAN_PATTERN.matcher(s);
        if (!matcher.find()) {
            return s;
        }
        // Reset and use replaceAll with a lambda to mask each match.
        return matcher.replaceAll(match -> {
            String digits = match.group();
            int last4Index = Math.max(0, digits.length() - 4);
            return "*".repeat(last4Index) + digits.substring(last4Index);
        });
    }

    /**
     * Normalize a tag value for Micrometer counters. Micrometer rejects
     * {@code null} tag values with a {@link NullPointerException}, so we
     * substitute the literal string {@code "unknown"} which keeps the
     * counter cardinality bounded and clearly flags misconfigured
     * callers in dashboards without dropping the metric.
     *
     * @param value the candidate tag value
     * @return the same value if non-{@code null}, otherwise
     *         {@code "unknown"}
     */
    private static String nullSafeTag(String value) {
        return value != null ? value : "unknown";
    }
}
