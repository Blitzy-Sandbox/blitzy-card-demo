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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Asynchronous audit log adapter — emits PCI-DSS-safe, sanitized audit
 * records to Amazon OpenSearch for searchable retention.
 *
 * <p>Per AAP &sect;0.6.6 ("Audit, Observability, and PCI-DSS"), every
 * material business event in the CardDemo Java target produces an audit
 * record indexed in OpenSearch:</p>
 * <ul>
 *   <li><b>Authentication events</b> &mdash; signon success / failure,
 *       JWT issuance.</li>
 *   <li><b>Account mutations</b> &mdash; account view, account update
 *       (with before/after image), balance debit / credit.</li>
 *   <li><b>Transaction posting</b> &mdash; new transaction, rejection
 *       (codes 100&ndash;109), interest calculation.</li>
 *   <li><b>User administration</b> &mdash; user add / update / delete.</li>
 *   <li><b>Batch job lifecycle</b> &mdash; POSTTRAN, INTCALC, COMBTRAN,
 *       CREASTMT, TRANREPT (per AAP &sect;0.6.3).</li>
 * </ul>
 *
 * <h2>Replaces (AAP &sect;0.6.6)</h2>
 * <p>Replaces COBOL {@code DISPLAY} statements scattered across batch and
 * online programs that historically wrote diagnostic lines to JES SYSPRINT.
 * Specifically:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} {@code DISPLAY}-of-rejects (reject
 *       codes 100&ndash;109).</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} before/after image diagnostics emitted
 *       under {@code SYNCPOINT ROLLBACK}.</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} interest-calculation per-account audit.</li>
 *   <li>{@code app/cbl/COSGN00C.cbl} signon-attempt log lines.</li>
 * </ul>
 *
 * <h2>Asynchrony (AAP &sect;0.7.1)</h2>
 * <p>Every public audit method is annotated {@code @Async}, dispatching the
 * call to the Spring task executor configured by
 * {@code com.awsm2.carddemo.CardDemoApplication#EnableAsync}. The audit
 * write happens off-thread so that:</p>
 * <ul>
 *   <li>Business flows (account update, transaction post, signon) never
 *       block on OpenSearch indexing latency.</li>
 *   <li>OpenSearch outages cannot cascade-fail the originating service.</li>
 * </ul>
 *
 * <h2>Sanitization (AAP &sect;0.6.6 PCI-DSS)</h2>
 * <p>Every payload routed through {@link #auditEvent(String, String, Map)}
 * passes through {@link #sanitizePayload(Map)} which:</p>
 * <ul>
 *   <li><b>Removes</b> any entry whose key matches the case-insensitive
 *       PII / secrets allowlist
 *       ({@code password}, {@code pwd}, {@code passwd}, {@code secret},
 *       {@code token}, {@code apiKey}, {@code api_key}, {@code authorization},
 *       {@code cvv}, {@code cvc}, {@code pin}, {@code privateKey},
 *       {@code clientSecret}). These are PRIMARY ACCOUNT-DATA INTEGRITY
 *       fields and must never reach the audit log.</li>
 *   <li><b>Masks</b> any string value matching the PAN-like 13&ndash;19-digit
 *       sequence regex by replacing all but the trailing 4 digits with
 *       {@code '*'} characters (e.g., {@code "4111111111111111"} becomes
 *       {@code "************1111"}). This is the PCI-DSS "PAN must never
 *       appear in full in logs / SIEM" rule.</li>
 *   <li><b>Recursively</b> applies the same rules to nested
 *       {@link Map} values so a sub-structure like
 *       {@code {"customer": {"password": "secret"}}} is sanitized correctly.</li>
 * </ul>
 *
 * <h2>Failure mode (AAP &sect;0.7.1 "non-blocking")</h2>
 * <p>OpenSearch transport failures are caught at the adapter boundary and
 * logged at {@code ERROR}, but NEVER re-thrown to the caller. The business
 * flow that triggered the audit call has already completed; an audit-write
 * failure must not invalidate it. For high-importance audits that require
 * guaranteed delivery, the upstream service should rely on a dual sink
 * (e.g., a CloudWatch metric counter) rather than expecting this adapter to
 * propagate the failure.</p>
 *
 * @see com.awsm2.carddemo.adapter.OpenSearchIndexer
 */
@Component
public class AuditLogService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogService.class);

    /**
     * Default OpenSearch index for audit records. Aligned with the index
     * name expected by infrastructure dashboards in
     * {@code infrastructure/terraform/opensearch.tf}.
     */
    static final String AUDIT_INDEX = "carddemo-audit";

    /**
     * Sensitive field name allowlist for sanitization. The match is
     * case-insensitive on the entire key (not a substring) to avoid both
     * (a) false positives like a field named {@code "user_password_changed_at"}
     * that may legitimately appear in audit trails as a timestamp, and (b)
     * false negatives where a developer used a non-standard casing like
     * {@code "PWD"}. The set is immutable.
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
     * PAN-like sequence regex: 13 to 19 consecutive digits, anchored as a
     * full match (no embedded surrounding context). Matches the major card
     * brand BIN-and-length combinations (Visa 16, Mastercard 16, Amex 15,
     * Diners 14, common loyalty 13-19). Used by {@link #maskPanLike(String)}
     * to mask card numbers that may have leaked into otherwise non-sensitive
     * fields like {@code "narration"} or {@code "message"}.
     */
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b\\d{13,19}\\b");

    /**
     * The OpenSearch indexer adapter. {@code @Async} dispatches the call to
     * a Spring task executor, so the indexer call happens off the calling
     * thread; the indexer itself synchronously writes to OpenSearch on that
     * worker thread.
     */
    private final OpenSearchIndexer openSearchIndexer;

    /**
     * Constructor injection. The {@link OpenSearchIndexer} adapter is the
     * sole side-effect channel for audit events; this service composes
     * sanitization + async dispatch on top of the indexer.
     *
     * @param openSearchIndexer the OpenSearch indexer bean from
     *                          {@code com.awsm2.carddemo.adapter}; never
     *                          {@code null}
     */
    public AuditLogService(OpenSearchIndexer openSearchIndexer) {
        // Replaces: DISPLAY statements in CBTRN02C, COACTUPC, CBACT04C, COSGN00C
        // — now indexed into OpenSearch for fraud investigation per AAP §0.6.6.
        this.openSearchIndexer = Objects.requireNonNull(openSearchIndexer,
                "openSearchIndexer must not be null");
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Asynchronously emits an audit event with the supplied event name,
     * actor identifier, and arbitrary structured payload.
     *
     * <p>The method is {@link Async @Async} — it returns immediately to the
     * caller; the actual OpenSearch index call runs on the task executor.
     * Failures are logged but never propagated.</p>
     *
     * <p>The payload is sanitized via {@link #sanitizePayload(Map)} before
     * indexing &mdash; sensitive keys are removed and PAN-like strings are
     * masked. The unsanitized map is NEVER logged or transmitted.</p>
     *
     * @param eventName short descriptive name (e.g., {@code "ACCOUNT_UPDATE"},
     *                  {@code "TRANSACTION_POST"}, {@code "SIGNON_FAILURE"});
     *                  may be {@code null} or blank but should not be
     * @param actor     identifier of the actor that triggered the event
     *                  (typically the user ID or service name); may be
     *                  {@code null}
     * @param payload   arbitrary structured payload; may be {@code null} or
     *                  empty
     */
    @Async
    public void auditEvent(String eventName, String actor, Map<String, Object> payload) {
        // Replaces: DISPLAY-of-events in COBOL programs — now structured
        // audit records persisted in OpenSearch for fraud investigation.
        try {
            // Always allocate an ID at the adapter boundary; the caller
            // doesn't control idempotency, so a UUID is the safest option.
            // For idempotent retries the upstream service should use the
            // OpenSearchIndexer directly with a deterministic ID.
            String docId = UUID.randomUUID().toString();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("@timestamp", Instant.now().toString());
            doc.put("eventName", eventName);
            doc.put("actor", actor);
            // Sanitize the payload before adding it — never store raw
            // user-supplied data that may contain PAN / passwords / tokens.
            doc.put("payload", sanitizePayload(payload));

            openSearchIndexer.indexDocument(AUDIT_INDEX, docId, doc);
        } catch (RuntimeException e) {
            // Catch-all per AAP §0.7.1: audit failures must not propagate
            // to the business flow that emitted the event. Log at ERROR so
            // operations can detect and alarm, but never re-throw.
            LOG.error("Audit emission FAILED event={} actor={} cause={}",
                    eventName, actor, e.getMessage(), e);
        }
    }

    /**
     * Audit event variant for transaction-specific events. Convenience
     * wrapper that adds {@code transactionId} to the standard envelope so
     * OpenSearch queries can filter by transaction ID directly.
     *
     * @param eventName     event name
     * @param actor         actor identifier
     * @param transactionId the COBOL {@code TRAN-ID} (16-digit)
     * @param payload       structured payload
     */
    @Async
    public void auditTransaction(String eventName,
                                 String actor,
                                 String transactionId,
                                 Map<String, Object> payload) {
        // Replaces: CBTRN02C DISPLAY-of-rejects and per-transaction audit lines
        try {
            String docId = (transactionId != null && !transactionId.isBlank())
                    ? transactionId + "-" + UUID.randomUUID()
                    : UUID.randomUUID().toString();
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("@timestamp", Instant.now().toString());
            doc.put("eventName", eventName);
            doc.put("actor", actor);
            doc.put("transactionId", transactionId);
            doc.put("payload", sanitizePayload(payload));

            openSearchIndexer.indexDocument(AUDIT_INDEX, docId, doc);
        } catch (RuntimeException e) {
            LOG.error("Transaction audit FAILED event={} txnId={} actor={} cause={}",
                    eventName, transactionId, actor, e.getMessage(), e);
        }
    }

    /**
     * Sanitizes a payload map before persistence: removes sensitive keys
     * and masks PAN-like string values. Returns a new map; the input is
     * never mutated.
     *
     * <p>Public for unit testing (CardDemo test conventions per AAP
     * &sect;0.7.2 require white-box testing of PCI-DSS sanitization logic);
     * application callers should rely on {@link #auditEvent(String, String, Map)}
     * which calls this method internally.</p>
     *
     * @param input arbitrary payload; may be {@code null} or empty
     * @return a sanitized copy &mdash; never {@code null}; an empty map if
     *         the input was empty or {@code null}
     */
    public Map<String, Object> sanitizePayload(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return new LinkedHashMap<>();
        }
        // Preserve insertion order so OpenSearch indexes fields in the
        // order the caller declared them — improves dashboard readability.
        Map<String, Object> out = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            // Step 1 — remove entries with sensitive keys.
            if (key != null && SENSITIVE_KEYS.contains(key.toLowerCase(java.util.Locale.US))) {
                continue;
            }
            // Step 2 — recurse into nested maps so sub-structures are
            // sanitized too. A value of any other type (Number, Boolean,
            // List) is passed through unchanged.
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedTyped = (Map<String, Object>) nested;
                out.put(key, sanitizePayload(nestedTyped));
            } else if (value instanceof String str) {
                // Step 3 — mask PAN-like sequences in string values. Other
                // sensitive scalars (passwords, tokens) are removed by the
                // key-name check above; this step handles the case where a
                // card number leaks into a description / narration field.
                out.put(key, maskPanLike(str));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    /**
     * Masks any PAN-like 13&ndash;19-digit sequence in the supplied string
     * by replacing all but the trailing 4 digits with {@code '*'}. Used by
     * {@link #sanitizePayload(Map)} to defend against PAN leakage in
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
}
