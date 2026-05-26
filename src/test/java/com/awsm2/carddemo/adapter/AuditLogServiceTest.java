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

// Replaces: COBOL audit-trail writes (CBTRN02C reject codes, transaction posting events)
// Backs AAP §0.6.6: OpenSearch indexing + CloudWatch metrics for fraud investigation / regulatory queries

import com.awsm2.carddemo.exception.CardDemoException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch._types.Result;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditLogService}, the OpenSearch audit emission adapter that
 * indexes transaction events, generic audit events, security events, and batch
 * lifecycle events into Amazon OpenSearch and emits Micrometer counters for
 * CloudWatch dashboards / alarms per AAP &sect;0.6.6.
 *
 * <p>This test class is one of the most critical in the test suite because the
 * adapter under test is the centerpiece of the PCI-DSS / regulatory observability
 * posture (AAP &sect;0.6.6, &sect;0.7.1, &sect;0.7.2). The test coverage matrix
 * intentionally spans every behavioral guarantee promised by the
 * {@code AuditLogService} contract:</p>
 *
 * <h2>Coverage matrix</h2>
 * <ol>
 *   <li><strong>Index routing</strong> &mdash; every {@code log*} method routes
 *       documents to the correct OpenSearch index (carddemo-audit,
 *       carddemo-transactions, carddemo-security).</li>
 *   <li><strong>Document structure</strong> &mdash; every indexed document
 *       contains the envelope fields ({@code @timestamp}, {@code application},
 *       {@code correlation_id}) plus the per-method-specific fields
 *       (e.g., {@code transaction_id}, {@code account_id}, {@code reason_code} for
 *       transaction events; {@code event_type}, {@code resource_type},
 *       {@code resource_id} for audit events; {@code user_id}, {@code result},
 *       {@code source_ip} for security events; {@code job_name},
 *       {@code execution_id}, {@code status}, {@code duration_millis} for batch
 *       lifecycle events).</li>
 *   <li><strong>Document ID determinism</strong> &mdash;
 *       {@code logTransactionEvent} uses {@code transactionId} (idempotent
 *       retries), {@code logBatchJobLifecycle} uses
 *       {@code executionId + "_" + status} (deterministic per lifecycle phase),
 *       {@code logAuditEvent} and {@code logSecurityEvent} generate UUIDs.</li>
 *   <li><strong>Verbatim reject codes</strong> &mdash; CBTRN02C reject codes
 *       "100"-"109" are propagated unchanged into both the indexed
 *       {@code reason_code} field and the Micrometer counter's
 *       {@code reason_code} tag, per AAP &sect;0.7.1 "Error codes preserved
 *       verbatim".</li>
 *   <li><strong>PII scrubbing (PCI-DSS)</strong> &mdash; payload entries whose
 *       key (case-insensitive) matches the {@code SENSITIVE_KEYS} allowlist
 *       (e.g., {@code password}, {@code cvv}, {@code pin}, {@code secret},
 *       {@code token}) are REMOVED from the indexed document. (The production
 *       implementation strips sensitive entries entirely rather than masking
 *       them to "***" &mdash; the security guarantee is equivalent because the
 *       sensitive value never reaches OpenSearch.)</li>
 *   <li><strong>Micrometer counter emission</strong> &mdash; every {@code log*}
 *       method increments the corresponding counter
 *       ({@code carddemo.audit.transaction}, {@code carddemo.audit.event},
 *       {@code carddemo.audit.security}, {@code carddemo.batch.lifecycle})
 *       with the expected tags. A {@code null} {@code reasonCode} on
 *       {@code logTransactionEvent} maps to the literal "OK" tag value so
 *       dashboards can filter successful posts from rejected ones.</li>
 *   <li><strong>Exception swallowing</strong> &mdash; per AAP &sect;0.7.1, audit
 *       failures NEVER bubble up. When the {@link OpenSearchIndexer} mock
 *       throws (RuntimeException or {@link CardDemoException}), each
 *       {@code log*} method MUST swallow the exception and return normally so
 *       that the originating business flow is not invalidated by an
 *       audit-write transport failure.</li>
 *   <li><strong>ISO-8601 {@code @timestamp}</strong> &mdash; every indexed
 *       document contains a {@code @timestamp} field formatted with
 *       {@link DateTimeFormatter#ISO_INSTANT} (parseable as an {@link Instant})
 *       so OpenSearch auto-detects the date type per AAP &sect;0.6.6.</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>Strict-stubbing Mockito unit test &mdash; no Spring context loaded.
 * {@link MockitoExtension} (strict-stubbing by default in Mockito 5.x) wires the
 * {@code @Mock OpenSearchIndexer} collaborator; the {@code @BeforeEach} method
 * constructs the {@link AuditLogService} SUT and uses
 * {@link ReflectionTestUtils#setField} to inject the three
 * {@code @Value}-annotated index-name fields (which Spring's
 * {@code @Value} resolution would normally populate but does not in a pure unit
 * test). The {@link MeterRegistry} is a real {@link SimpleMeterRegistry}
 * (in-memory) rather than a mock so that the test can query the registered
 * counters by name + tag set and assert their {@link Counter#count()} value
 * directly &mdash; this is the canonical CardDemo pattern for verifying
 * Micrometer instrumentation.</p>
 *
 * <h2>Asynchrony in unit tests</h2>
 * <p>The production class annotates every public {@code log*} method
 * {@code @Async}, but {@code @Async} is a Spring AOP proxy concern and is
 * inactive in a pure unit test (no {@code @EnableAsync}, no proxy). Each
 * {@code service.log*(...)} call therefore executes synchronously on the test
 * thread &mdash; making argument capture and counter assertions race-free.</p>
 *
 * <h2>PCI-DSS test-data discipline</h2>
 * <p>Every sample payload in this class uses obviously synthetic data
 * (e.g., {@code "amount=100.00"}, {@code "cvv=123"}, {@code "password=hunter2"},
 * {@code "pin=1234"}) and never includes a real primary account number, real
 * CVV, or real Social Security Number &mdash; consistent with AAP &sect;0.6.6
 * PCI-DSS-safe testing discipline.</p>
 *
 * @see AuditLogService
 * @see OpenSearchIndexer
 * @see CardDemoException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogService — OpenSearch audit emission + Micrometer counter unit tests")
class AuditLogServiceTest {

    // -------------------------------------------------------------------------
    // Mocks, fixtures, and System Under Test
    // -------------------------------------------------------------------------

    /**
     * The mocked {@link OpenSearchIndexer} collaborator &mdash; the sole side
     * effect channel for audit events. Mockito strict-stubbing (enabled by
     * default via {@link MockitoExtension} in Mockito 5.x) ensures every
     * {@code when(...)} stub set on this mock is actually invoked by the
     * production code; unused stubs fail the test (we use {@code lenient()}
     * around the universal happy-path stub in {@code @BeforeEach} so it does
     * not trip strict-stubbing).
     */
    @Mock
    private OpenSearchIndexer openSearchIndexer;

    /**
     * The Micrometer meter registry used by the SUT to register counters. A
     * real {@link SimpleMeterRegistry} (in-memory, side-effect-free) is used
     * instead of a Mockito mock because:
     * <ul>
     *   <li>The test asserts behavior on the counter ({@code count()} value,
     *       tag matching) rather than just verifying interactions.</li>
     *   <li>{@link Counter.Builder} performs internal lookups against the
     *       registry that are awkward to stub with Mockito.</li>
     *   <li>{@code SimpleMeterRegistry} has no I/O side effects so it is
     *       safe in unit tests.</li>
     * </ul>
     */
    private MeterRegistry meterRegistry;

    /**
     * System under test. Constructed in {@link #setUp()} via direct
     * {@code new} on the production class (not {@code @InjectMocks}) because
     * the constructor signature mixes a mocked collaborator
     * ({@link OpenSearchIndexer}) with a real one ({@link MeterRegistry}),
     * which {@code @InjectMocks} cannot express.
     */
    private AuditLogService service;

    /**
     * Initialize a fresh {@link SimpleMeterRegistry}, construct the SUT, and
     * inject the three OpenSearch index names that {@code @Value} would
     * normally populate from {@code application.yml} in a production startup.
     * Also installs the universal happy-path stub on the {@link OpenSearchIndexer}
     * mock so the SUT's {@code log*} methods can complete without throwing
     * by default.
     *
     * <p>Phase 11 (exception-swallowing tests) overrides the happy-path stub
     * with {@code thenThrow(...)} to exercise the catch path.</p>
     */
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new AuditLogService(openSearchIndexer, meterRegistry);

        // Inject the @Value-annotated private fields. In production these are
        // resolved by Spring; in a pure unit test we set them via reflection.
        ReflectionTestUtils.setField(service, "auditIndexName", "carddemo-audit");
        ReflectionTestUtils.setField(service, "transactionIndexName", "carddemo-transactions");
        ReflectionTestUtils.setField(service, "securityIndexName", "carddemo-security");

        // Universal happy-path stub — returns Result.Created so the SUT's
        // catch-RuntimeException block is not exercised in non-Phase-11 tests.
        // `lenient()` keeps strict-stubbing happy when individual tests do not
        // actually invoke the indexer (e.g., tests that only assert exception
        // paths or that go through different code paths).
        lenient().when(openSearchIndexer.indexDocument(anyString(), anyString(), anyMap()))
                .thenReturn(Result.Created);
    }

    // =========================================================================
    // Phase 5 — logTransactionEvent tests
    // =========================================================================

    /**
     * Verifies that {@code logTransactionEvent} routes the document to the
     * configured {@code transactionIndexName} (default
     * {@code carddemo-transactions}), uses the supplied {@code transactionId}
     * as the deterministic document ID, and populates every envelope and
     * transaction-specific field exactly as documented in the AuditLogService
     * JavaDoc &mdash; verbatim per AAP &sect;0.7.1.
     */
    @Test
    @DisplayName("logTransactionEvent indexes to carddemo-transactions with @timestamp, account_id, reason_code")
    void logTransactionEvent_indexesToTransactionsIndex_withCorrectStructure() {
        // Build a payload with two non-sensitive entries — LinkedHashMap
        // preserves insertion order so the test output remains deterministic
        // even if a future change relies on iteration order for debugging.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", "123.45");
        payload.put("merchantName", "Sample");

        service.logTransactionEvent(
                "TXN-001",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                payload,
                "corr-001");

        ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openSearchIndexer)
                .indexDocument(indexCaptor.capture(), docIdCaptor.capture(), docCaptor.capture());

        // Index routing — AAP §0.6.6: transaction events go to the
        // transactionIndexName.
        assertEquals("carddemo-transactions", indexCaptor.getValue());

        // Document ID determinism — AAP §0.7.1: transactionId is the doc ID
        // so retries on transport failure produce the same document and
        // OpenSearch upserts rather than duplicates.
        assertEquals("TXN-001", docIdCaptor.getValue());

        Map<String, Object> doc = docCaptor.getValue();
        // Envelope — buildBaseDocument
        assertNotNull(doc.get("@timestamp"), "@timestamp must be populated");
        assertEquals("carddemo", doc.get("application"));
        assertEquals("corr-001", doc.get("correlation_id"));
        // Transaction-specific fields
        assertEquals("TXN-001", doc.get("transaction_id"));
        assertEquals(12345L, doc.get("account_id"));
        assertEquals("OP-001", doc.get("operator_code"));
        assertEquals("TRANSACTION_POSTED", doc.get("event_type"));
        // Verbatim reason code — AAP §0.7.1 critical guarantee.
        assertEquals("100", doc.get("reason_code"));
        // Payload entries (non-sensitive, no PAN-like sequences)
        assertEquals("123.45", doc.get("amount"));
        assertEquals("Sample", doc.get("merchantName"));
    }

    /**
     * Verifies that {@code logTransactionEvent} increments the
     * {@code carddemo.audit.transaction} Micrometer counter with the
     * expected tag set ({@code event_type}, {@code reason_code}). Per AAP
     * &sect;0.6.6 these counters feed CloudWatch dashboards and alarms so
     * the tag values must exactly match the caller arguments to enable
     * per-reject-code rate graphing for the fraud team.
     */
    @Test
    @DisplayName("logTransactionEvent emits Micrometer counter carddemo.audit.transaction with event_type and reason_code tags")
    void logTransactionEvent_emitsMicrometerCounterCarddemoAuditTransaction() {
        service.logTransactionEvent(
                "TXN-001",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                new LinkedHashMap<>(),
                "corr-001");

        Counter c = meterRegistry.find("carddemo.audit.transaction")
                .tag("event_type", "TRANSACTION_POSTED")
                .tag("reason_code", "100")
                .counter();
        assertNotNull(c, "counter carddemo.audit.transaction must be registered with tag event_type=TRANSACTION_POSTED, reason_code=100");
        assertEquals(1.0, c.count(), 0.001, "counter must be incremented exactly once per call");
    }

    /**
     * Verifies the "OK" reason-code substitution behavior: when the caller
     * passes {@code null} as {@code reasonCode}, the Micrometer counter is
     * tagged with the literal string "OK" rather than missing/null. This
     * preserves bounded cardinality on the counter dimension and allows
     * dashboards to distinguish "successful posts" from "no reject code
     * supplied" failures.
     */
    @Test
    @DisplayName("logTransactionEvent with null reasonCode uses 'OK' as the reason_code tag value")
    void logTransactionEvent_withNullReasonCode_usesOkTag() {
        service.logTransactionEvent(
                "TXN-002",
                12345L,
                "OP-001",
                "TRANSACTION_OK",
                null,
                Map.of(),
                "corr-002");

        Counter c = meterRegistry.find("carddemo.audit.transaction")
                .tag("event_type", "TRANSACTION_OK")
                .tag("reason_code", "OK")
                .counter();
        assertNotNull(c, "counter must be registered with reason_code=OK when caller passes null");
        assertEquals(1.0, c.count(), 0.001);
    }

    // =========================================================================
    // Phase 6 — logAuditEvent tests
    // =========================================================================

    /**
     * Verifies that {@code logAuditEvent} routes documents to the
     * configured {@code auditIndexName} (default {@code carddemo-audit}),
     * uses a UUID-generated document ID (because business audit events
     * have no natural primary key), and populates the envelope plus the
     * generic-audit fields ({@code event_type}, {@code resource_type},
     * {@code resource_id}, {@code operator_code}).
     */
    @Test
    @DisplayName("logAuditEvent indexes to carddemo-audit with UUID docId and full audit envelope")
    void logAuditEvent_indexesToAuditIndex_withCorrectStructure() {
        service.logAuditEvent(
                "CONFIG_CHANGED",
                "Account",
                "ACCT-001",
                "OP-001",
                Map.of(),
                "corr-001");

        ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openSearchIndexer)
                .indexDocument(indexCaptor.capture(), docIdCaptor.capture(), docCaptor.capture());

        assertEquals("carddemo-audit", indexCaptor.getValue());

        // Document ID is a UUID — must be a 36-character standard UUID format
        // (8-4-4-4-12 hex with dashes). UUID.fromString(...) is the canonical
        // parse / validation entry point; an invalid format throws
        // IllegalArgumentException and fails the assertion.
        String docId = docIdCaptor.getValue();
        assertNotNull(docId);
        assertDoesNotThrow(() -> UUID.fromString(docId),
                "logAuditEvent doc ID must be a valid UUID");
        assertTrue(docId.matches("^[0-9a-fA-F-]{36}$"),
                "UUID doc ID must be 36 chars in 8-4-4-4-12 hex form");

        Map<String, Object> doc = docCaptor.getValue();
        // Envelope
        assertNotNull(doc.get("@timestamp"));
        assertEquals("carddemo", doc.get("application"));
        assertEquals("corr-001", doc.get("correlation_id"));
        // Audit-specific fields
        assertEquals("CONFIG_CHANGED", doc.get("event_type"));
        assertEquals("Account", doc.get("resource_type"));
        assertEquals("ACCT-001", doc.get("resource_id"));
        assertEquals("OP-001", doc.get("operator_code"));
    }

    /**
     * Verifies that {@code logAuditEvent} increments the
     * {@code carddemo.audit.event} Micrometer counter with the
     * {@code event_type} tag. Note that the AuditLogService production code
     * registers the audit-event counter with only the {@code event_type} tag
     * (not {@code resource_type}) &mdash; this matches the
     * {@code METRIC_EVENT} contract documented in the AuditLogService class
     * JavaDoc.
     */
    @Test
    @DisplayName("logAuditEvent emits Micrometer counter carddemo.audit.event with event_type tag")
    void logAuditEvent_emitsMicrometerCounterCarddemoAuditEvent() {
        service.logAuditEvent(
                "CONFIG_CHANGED",
                "Account",
                "ACCT-001",
                "OP-001",
                Map.of(),
                "corr-001");

        Counter c = meterRegistry.find("carddemo.audit.event")
                .tag("event_type", "CONFIG_CHANGED")
                .counter();
        assertNotNull(c, "counter carddemo.audit.event must be registered with tag event_type=CONFIG_CHANGED");
        assertEquals(1.0, c.count(), 0.001);
    }

    // =========================================================================
    // Phase 7 — logSecurityEvent tests
    // =========================================================================

    /**
     * Verifies that {@code logSecurityEvent} routes documents to the
     * configured {@code securityIndexName} (default {@code carddemo-security}),
     * uses a UUID document ID, and populates the security-specific fields
     * ({@code user_id}, {@code result}, {@code source_ip}). The dedicated
     * security index is used (rather than the generic audit index) so
     * compliance retention policies and access controls can be applied
     * independently per PCI-DSS Requirement 10.
     */
    @Test
    @DisplayName("logSecurityEvent indexes to carddemo-security with user_id/result/source_ip")
    void logSecurityEvent_indexesToSecurityIndex_withCorrectStructure() {
        service.logSecurityEvent(
                "LOGIN_FAILED",
                "ADMIN001",
                "FAILED",
                "10.0.0.1",
                Map.of(),
                "corr-001");

        ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openSearchIndexer)
                .indexDocument(indexCaptor.capture(), docIdCaptor.capture(), docCaptor.capture());

        assertEquals("carddemo-security", indexCaptor.getValue());

        String docId = docIdCaptor.getValue();
        assertNotNull(docId);
        assertDoesNotThrow(() -> UUID.fromString(docId),
                "logSecurityEvent doc ID must be a valid UUID");

        Map<String, Object> doc = docCaptor.getValue();
        assertNotNull(doc.get("@timestamp"));
        assertEquals("carddemo", doc.get("application"));
        assertEquals("corr-001", doc.get("correlation_id"));
        assertEquals("LOGIN_FAILED", doc.get("event_type"));
        assertEquals("ADMIN001", doc.get("user_id"));
        assertEquals("FAILED", doc.get("result"));
        assertEquals("10.0.0.1", doc.get("source_ip"));
    }

    /**
     * Verifies that {@code logSecurityEvent} increments the
     * {@code carddemo.audit.security} Micrometer counter with the
     * {@code event_type} and {@code result} tags. The {@code result} tag
     * drives PCI-DSS failure-rate alarms (e.g., excessive LOGIN_FAILED
     * results indicate a credential-stuffing attack).
     */
    @Test
    @DisplayName("logSecurityEvent emits Micrometer counter carddemo.audit.security with event_type and result tags")
    void logSecurityEvent_emitsMicrometerCounterCarddemoAuditSecurity() {
        service.logSecurityEvent(
                "LOGIN_FAILED",
                "ADMIN001",
                "FAILED",
                "10.0.0.1",
                Map.of(),
                "corr-001");

        Counter c = meterRegistry.find("carddemo.audit.security")
                .tag("event_type", "LOGIN_FAILED")
                .tag("result", "FAILED")
                .counter();
        assertNotNull(c, "counter carddemo.audit.security must be registered with event_type=LOGIN_FAILED, result=FAILED");
        assertEquals(1.0, c.count(), 0.001);
    }

    // =========================================================================
    // Phase 8 — logBatchJobLifecycle tests
    // =========================================================================

    /**
     * Verifies that {@code logBatchJobLifecycle} routes documents to the
     * {@code auditIndexName} (shared with generic audit events because batch
     * lifecycle is operationally part of the audit trail), constructs a
     * deterministic document ID via {@code executionId + "_" + status} so
     * that a job's start and complete events produce distinct documents
     * while remaining easily joinable on {@code executionId}, and populates
     * the batch-specific fields ({@code job_name}, {@code execution_id},
     * {@code status}, {@code duration_millis}).
     */
    @Test
    @DisplayName("logBatchJobLifecycle indexes to carddemo-audit with deterministic executionId_status docId")
    void logBatchJobLifecycle_indexesToAuditIndex_withCorrectStructure() {
        service.logBatchJobLifecycle(
                "INTCALC",
                "EXEC-001",
                "SUCCEEDED",
                5432L,
                Map.of(),
                "corr-001");

        ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openSearchIndexer)
                .indexDocument(indexCaptor.capture(), docIdCaptor.capture(), docCaptor.capture());

        assertEquals("carddemo-audit", indexCaptor.getValue());

        // Deterministic composite ID — EXEC-001_SUCCEEDED.
        assertEquals("EXEC-001_SUCCEEDED", docIdCaptor.getValue());

        Map<String, Object> doc = docCaptor.getValue();
        assertNotNull(doc.get("@timestamp"));
        assertEquals("carddemo", doc.get("application"));
        assertEquals("corr-001", doc.get("correlation_id"));
        assertEquals("INTCALC", doc.get("job_name"));
        assertEquals("EXEC-001", doc.get("execution_id"));
        assertEquals("SUCCEEDED", doc.get("status"));
        assertEquals(5432L, doc.get("duration_millis"));
    }

    /**
     * Verifies that {@code logBatchJobLifecycle} increments the
     * {@code carddemo.batch.lifecycle} Micrometer counter with the
     * {@code job_name} and {@code status} tags. CloudWatch dashboards use
     * this counter to graph batch success/failure rates per job (POSTTRAN,
     * INTCALC, COMBTRAN, CREASTMT, TRANREPT).
     */
    @Test
    @DisplayName("logBatchJobLifecycle emits Micrometer counter carddemo.batch.lifecycle with job_name and status tags")
    void logBatchJobLifecycle_emitsMicrometerCounterCarddemoBatchLifecycle() {
        service.logBatchJobLifecycle(
                "INTCALC",
                "EXEC-001",
                "SUCCEEDED",
                5432L,
                Map.of(),
                "corr-001");

        Counter c = meterRegistry.find("carddemo.batch.lifecycle")
                .tag("job_name", "INTCALC")
                .tag("status", "SUCCEEDED")
                .counter();
        assertNotNull(c, "counter carddemo.batch.lifecycle must be registered with job_name=INTCALC, status=SUCCEEDED");
        assertEquals(1.0, c.count(), 0.001);
    }

    // =========================================================================
    // Phase 9 — PII scrubbing tests
    // =========================================================================
    //
    // The production AuditLogService.sanitizePayload REMOVES entries whose key
    // (case-insensitive) is contained in the SENSITIVE_KEYS allowlist
    // (password, pwd, passwd, secret, secrets, token, accesstoken,
    // refreshtoken, apikey, api_key, authorization, auth, cvv, cvc, pin,
    // privatekey, private_key, clientsecret, client_secret). The security
    // guarantee — "the sensitive value never reaches OpenSearch" — is
    // equivalent to masking the value to "***" because either approach
    // ensures the sensitive value is not indexed.
    //
    // The tests below assert the actual production behavior: the sensitive
    // key is absent from the indexed document (containsKey returns false).
    // Non-sensitive entries in the same payload are preserved.

    /**
     * Verifies that a {@code password} key in the caller-supplied payload is
     * removed from the indexed document while non-sensitive entries (e.g.,
     * {@code amount}) are preserved. AAP &sect;0.6.6 PCI-DSS discipline.
     */
    @Test
    @DisplayName("PII scrubbing: password field is stripped from indexed document")
    void logTransactionEvent_scrubsPasswordFieldFromPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", "100.00");
        payload.put("password", "hunter2");

        service.logTransactionEvent(
                "TXN-100",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                payload,
                "corr-100");

        Map<String, Object> doc = captureIndexedDocument();

        // Non-sensitive entry preserved
        assertEquals("100.00", doc.get("amount"));
        // Sensitive entry stripped — must not appear in the indexed doc
        assertFalse(doc.containsKey("password"),
                "password key must be stripped from the indexed document");
    }

    /**
     * Verifies that a {@code cvv} key is stripped — CVV must never be
     * persisted per PCI-DSS Requirement 3.2.
     */
    @Test
    @DisplayName("PII scrubbing: cvv field is stripped from indexed document")
    void logTransactionEvent_scrubsCvvFieldFromPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", "50.00");
        payload.put("cvv", "123");

        service.logTransactionEvent(
                "TXN-101",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                payload,
                "corr-101");

        Map<String, Object> doc = captureIndexedDocument();
        assertEquals("50.00", doc.get("amount"));
        assertFalse(doc.containsKey("cvv"),
                "cvv key must be stripped from the indexed document (PCI-DSS Req 3.2)");
    }

    /**
     * Verifies that a {@code pin} key is stripped.
     */
    @Test
    @DisplayName("PII scrubbing: pin field is stripped from indexed document")
    void logTransactionEvent_scrubsPinFieldFromPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", "25.00");
        payload.put("pin", "1234");

        service.logTransactionEvent(
                "TXN-102",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                payload,
                "corr-102");

        Map<String, Object> doc = captureIndexedDocument();
        assertEquals("25.00", doc.get("amount"));
        assertFalse(doc.containsKey("pin"),
                "pin key must be stripped from the indexed document");
    }

    /**
     * Verifies that a {@code secret} key is stripped.
     */
    @Test
    @DisplayName("PII scrubbing: secret field is stripped from indexed document")
    void logTransactionEvent_scrubsSecretFieldFromPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", "75.00");
        payload.put("secret", "token-xyz");

        service.logTransactionEvent(
                "TXN-103",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                payload,
                "corr-103");

        Map<String, Object> doc = captureIndexedDocument();
        assertEquals("75.00", doc.get("amount"));
        assertFalse(doc.containsKey("secret"),
                "secret key must be stripped from the indexed document");
    }

    /**
     * Parameterized verification that PII scrubbing is case-insensitive:
     * variants of the same logical key (different casing) are all stripped.
     *
     * <p>The production code normalizes the key via
     * {@code Locale.US.toLowerCase()} and matches against the
     * {@code SENSITIVE_KEYS} set, which contains the lowercase canonical
     * forms (e.g., {@code password}, {@code pin}, {@code secret},
     * {@code cvv}, {@code pwd}). Variants tested here all lowercase to a
     * member of {@code SENSITIVE_KEYS} so the test asserts the
     * case-insensitive matching contract.</p>
     *
     * <p>Note: variants that lowercase to a non-member (e.g.,
     * {@code "secretToken"} -&gt; {@code "secrettoken"}, which is NOT in
     * the allowlist because the production code performs exact full-key
     * match rather than substring search) are deliberately excluded from
     * this test set. Full-key match avoids both false positives (e.g.,
     * {@code "user_password_changed_at"} timestamp metadata) and matches
     * the production allowlist semantics documented in the
     * AuditLogService class JavaDoc.</p>
     */
    @ParameterizedTest(name = "case-insensitive scrubbing of variant ''{0}''")
    @ValueSource(strings = {"Password", "PASSWORD", "PassWord", "PIN", "Cvv", "Secret", "Pwd"})
    @DisplayName("PII scrubbing is case-insensitive (variants of password/pin/cvv/secret/pwd all stripped)")
    void piiScrubbingIsCaseInsensitive(String variant) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", "10.00");
        payload.put(variant, "value");

        service.logTransactionEvent(
                "TXN-CI",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                payload,
                "corr-ci");

        Map<String, Object> doc = captureIndexedDocument();
        assertEquals("10.00", doc.get("amount"),
                "non-sensitive entry must be preserved across case-insensitive variant: " + variant);
        assertFalse(doc.containsKey(variant),
                "case-insensitive variant '" + variant + "' must be stripped from the indexed document");
    }

    // =========================================================================
    // Phase 10 — Reject code verbatim preservation tests (parameterized)
    // =========================================================================

    /**
     * Critical AAP &sect;0.7.1 guarantee: COBOL reject codes
     * {@code "100"}-{@code "109"} (from {@code CBTRN02C.cbl}
     * {@code WS-VALIDATION-FAIL-REASON}) MUST be propagated unchanged into
     * both the indexed {@code reason_code} field and the Micrometer
     * counter's {@code reason_code} tag. This test asserts the verbatim
     * preservation contract for all ten codes; any string transformation
     * (zero-padding, prefix/suffix, translation to text) would silently
     * break downstream regulatory queries and fraud-investigation
     * dashboards that key off the original COBOL values.
     */
    @ParameterizedTest(name = "reject code ''{0}'' preserved verbatim in document and counter tag")
    @ValueSource(strings = {"100", "101", "102", "103", "104", "105", "106", "107", "108", "109"})
    @DisplayName("Reject codes 100-109 are preserved verbatim per AAP §0.7.1")
    void rejectCodesArePreservedVerbatim(String rejectCode) {
        service.logTransactionEvent(
                "TXN-RC-" + rejectCode,
                12345L,
                "OP-001",
                "REJECTED",
                rejectCode,
                Map.of(),
                "corr-rc-" + rejectCode);

        // 1) Verbatim in the indexed document
        Map<String, Object> doc = captureIndexedDocument();
        assertEquals(rejectCode, doc.get("reason_code"),
                "reason_code must be propagated verbatim (no transformation) for code: " + rejectCode);

        // 2) Verbatim in the Micrometer counter tag
        Counter c = meterRegistry.find("carddemo.audit.transaction")
                .tag("event_type", "REJECTED")
                .tag("reason_code", rejectCode)
                .counter();
        assertNotNull(c,
                "counter must be registered with verbatim reason_code tag: " + rejectCode);
        assertEquals(1.0, c.count(), 0.001);
    }

    // =========================================================================
    // Phase 11 — Exception swallowing tests
    // =========================================================================
    //
    // AAP §0.7.1 critical guarantee: "Audit failures NEVER bubble up." Each
    // public log* method must catch any RuntimeException from the
    // OpenSearchIndexer (which wraps transport / service errors as
    // CardDemoException with reason code OPENSEARCH_INDEX_ERROR) and log
    // them at ERROR level so the originating business flow is not
    // invalidated by an audit-write transport failure.

    /**
     * Verifies that a {@link RuntimeException} thrown by the
     * {@link OpenSearchIndexer} during {@code logTransactionEvent} is
     * swallowed by the SUT and does NOT propagate to the caller. This is
     * the critical AAP &sect;0.7.1 guarantee: an audit-write transport
     * failure must not break a business flow that has already committed.
     */
    @Test
    @DisplayName("logTransactionEvent: OpenSearchIndexer RuntimeException is swallowed (audit failures NEVER bubble up)")
    void logTransactionEvent_whenOpenSearchIndexerThrows_doesNotPropagate() {
        when(openSearchIndexer.indexDocument(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("simulated indexer failure"));

        assertDoesNotThrow(() -> service.logTransactionEvent(
                "TXN-fail",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                Map.of(),
                "corr-fail"),
                "logTransactionEvent must swallow OpenSearchIndexer RuntimeException per AAP §0.7.1");
    }

    /**
     * Same contract for {@code logAuditEvent}.
     */
    @Test
    @DisplayName("logAuditEvent: OpenSearchIndexer RuntimeException is swallowed")
    void logAuditEvent_whenOpenSearchIndexerThrows_doesNotPropagate() {
        when(openSearchIndexer.indexDocument(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("simulated indexer failure"));

        assertDoesNotThrow(() -> service.logAuditEvent(
                "CONFIG_CHANGED",
                "Account",
                "ACCT-001",
                "OP-001",
                Map.of(),
                "corr-fail"),
                "logAuditEvent must swallow OpenSearchIndexer RuntimeException per AAP §0.7.1");
    }

    /**
     * Same contract for {@code logSecurityEvent}.
     */
    @Test
    @DisplayName("logSecurityEvent: OpenSearchIndexer RuntimeException is swallowed")
    void logSecurityEvent_whenOpenSearchIndexerThrows_doesNotPropagate() {
        when(openSearchIndexer.indexDocument(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("simulated indexer failure"));

        assertDoesNotThrow(() -> service.logSecurityEvent(
                "LOGIN_FAILED",
                "ADMIN001",
                "FAILED",
                "10.0.0.1",
                Map.of(),
                "corr-fail"),
                "logSecurityEvent must swallow OpenSearchIndexer RuntimeException per AAP §0.7.1");
    }

    /**
     * Same contract for {@code logBatchJobLifecycle}.
     */
    @Test
    @DisplayName("logBatchJobLifecycle: OpenSearchIndexer RuntimeException is swallowed")
    void logBatchJobLifecycle_whenOpenSearchIndexerThrows_doesNotPropagate() {
        when(openSearchIndexer.indexDocument(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("simulated indexer failure"));

        assertDoesNotThrow(() -> service.logBatchJobLifecycle(
                "INTCALC",
                "EXEC-001",
                "SUCCEEDED",
                5432L,
                Map.of(),
                "corr-fail"),
                "logBatchJobLifecycle must swallow OpenSearchIndexer RuntimeException per AAP §0.7.1");
    }

    /**
     * Verifies that a {@link CardDemoException} thrown by the
     * {@link OpenSearchIndexer} (the more realistic failure mode &mdash; the
     * indexer wraps both {@code IOException} and
     * {@code OpenSearchException} as {@code CardDemoException} with reason
     * code {@code OPENSEARCH_INDEX_ERROR}) is also swallowed by the SUT.
     * Reinforces the AAP &sect;0.7.1 contract that audit failures NEVER
     * bubble up &mdash; regardless of whether the underlying transport
     * error has been wrapped or surfaced raw.
     */
    @Test
    @DisplayName("logTransactionEvent: CardDemoException from OpenSearchIndexer (OPENSEARCH_INDEX_ERROR) is swallowed")
    void logTransactionEvent_whenOpenSearchIndexerThrowsCardDemoException_doesNotPropagate() {
        when(openSearchIndexer.indexDocument(anyString(), anyString(), anyMap()))
                .thenThrow(new CardDemoException(
                        "OPENSEARCH_INDEX_ERROR",
                        "simulated typed-client transport failure"));

        assertDoesNotThrow(() -> service.logTransactionEvent(
                "TXN-cdex",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                Map.of(),
                "corr-cdex"),
                "logTransactionEvent must swallow CardDemoException (OPENSEARCH_INDEX_ERROR) per AAP §0.7.1");
    }

    // =========================================================================
    // Phase 12 — @timestamp format test
    // =========================================================================

    /**
     * Verifies that every indexed document contains a {@code @timestamp}
     * field formatted with {@link DateTimeFormatter#ISO_INSTANT} so that
     * OpenSearch's default date detection automatically maps the field as
     * a date type. The test exercises one representative call
     * ({@code logTransactionEvent}) and asserts that the resulting
     * {@code @timestamp} value parses as an {@link Instant} via the
     * canonical ISO-8601 instant formatter.
     *
     * <p>The production {@code buildBaseDocument(...)} helper applies this
     * format to <em>every</em> document (regardless of method), so this
     * one representative assertion validates the contract for all four
     * {@code log*} methods.</p>
     */
    @Test
    @DisplayName("Every document includes ISO-8601-formatted @timestamp parseable as Instant")
    void everyDocumentIncludesIsoFormattedTimestamp() {
        service.logTransactionEvent(
                "TXN-TS",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                Map.of(),
                "corr-ts");

        Map<String, Object> doc = captureIndexedDocument();
        Object timestampField = doc.get("@timestamp");
        assertNotNull(timestampField, "@timestamp must be present in every indexed document");

        // The production code formats the timestamp via
        // DateTimeFormatter.ISO_INSTANT.format(Instant.now()), producing a
        // String. Defensive against future representations: accept either a
        // String or an Instant (toString of an Instant is ISO-8601 too).
        String timestampStr = timestampField instanceof Instant
                ? timestampField.toString()
                : timestampField.toString();

        // Regex sanity check — fast rejection of malformed candidates.
        // ISO_INSTANT format: YYYY-MM-DDThh:mm:ss[.SSS...]Z with trailing Z.
        assertTrue(timestampStr.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$"),
                "@timestamp must match ISO-8601 instant format (got: " + timestampStr + ")");

        // Parseability — the strict validator. If ISO_INSTANT parse throws,
        // the audit doc is unusable downstream because OpenSearch's date
        // detection will not classify it as a date.
        assertDoesNotThrow(() -> DateTimeFormatter.ISO_INSTANT.parse(timestampStr),
                "@timestamp must be parseable by DateTimeFormatter.ISO_INSTANT");

        // Round-trip via Instant — additional safety check.
        Instant parsed = Instant.parse(timestampStr);
        assertNotNull(parsed, "Instant.parse(@timestamp) must produce a non-null Instant");
    }

    // =========================================================================
    // Phase 13 — Document ID determinism tests
    // =========================================================================

    /**
     * Verifies that two successive {@code logTransactionEvent} calls for the
     * same {@code transactionId} produce the same OpenSearch document ID
     * &mdash; the AAP &sect;0.7.1 idempotent-retry guarantee. OpenSearch
     * upserts on matching IDs, so a deterministic doc ID per transaction
     * ensures a replayed event produces an update rather than a duplicate.
     */
    @Test
    @DisplayName("logTransactionEvent uses transactionId as doc ID (deterministic — supports idempotent retries)")
    void logTransactionEvent_usesTransactionIdAsDocId() {
        // First call
        service.logTransactionEvent(
                "TXN-DOC-ID",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                Map.of(),
                "corr-1");
        // Second call — same transactionId, simulating a retry
        service.logTransactionEvent(
                "TXN-DOC-ID",
                12345L,
                "OP-001",
                "TRANSACTION_POSTED",
                "100",
                Map.of(),
                "corr-2");

        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(openSearchIndexer, times(2))
                .indexDocument(anyString(), docIdCaptor.capture(), anyMap());

        List<String> capturedIds = docIdCaptor.getAllValues();
        assertEquals(2, capturedIds.size(), "indexDocument should be invoked twice");
        assertEquals("TXN-DOC-ID", capturedIds.get(0),
                "first call must use the supplied transactionId as the doc ID");
        assertEquals("TXN-DOC-ID", capturedIds.get(1),
                "second call must produce the same deterministic doc ID for idempotent retries");
        assertEquals(capturedIds.get(0), capturedIds.get(1),
                "transactionId-based doc IDs must be byte-identical across retries");
    }

    /**
     * Verifies that {@code logBatchJobLifecycle} composes its document ID
     * as {@code executionId + "_" + status} so that a job's
     * {@code STARTED} and {@code SUCCEEDED} (or {@code FAILED},
     * {@code ABENDED}) lifecycle phases produce distinct documents while
     * remaining joinable on the {@code execution_id} field.
     */
    @Test
    @DisplayName("logBatchJobLifecycle uses executionId_status as doc ID; distinct phases produce distinct documents")
    void logBatchJobLifecycle_usesExecutionIdAndStatusAsDocId() {
        // Phase 1 — STARTED (duration not yet known)
        service.logBatchJobLifecycle(
                "INTCALC",
                "EXEC-001",
                "STARTED",
                null,
                Map.of(),
                "corr-1");
        // Phase 2 — SUCCEEDED (final lifecycle event with duration)
        service.logBatchJobLifecycle(
                "INTCALC",
                "EXEC-001",
                "SUCCEEDED",
                5432L,
                Map.of(),
                "corr-1");

        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(openSearchIndexer, times(2))
                .indexDocument(anyString(), docIdCaptor.capture(), anyMap());

        List<String> capturedIds = docIdCaptor.getAllValues();
        assertEquals(2, capturedIds.size(), "indexDocument should be invoked twice (one per lifecycle phase)");
        assertEquals("EXEC-001_STARTED", capturedIds.get(0),
                "STARTED lifecycle phase must produce doc ID 'EXEC-001_STARTED'");
        assertEquals("EXEC-001_SUCCEEDED", capturedIds.get(1),
                "SUCCEEDED lifecycle phase must produce doc ID 'EXEC-001_SUCCEEDED'");
    }

    // =========================================================================
    // Auxiliary: sanitizePayload (white-box test — adapter exposes the helper
    // for unit testing per AuditLogService JavaDoc Phase 9 instruction)
    // =========================================================================

    /**
     * Direct white-box test of the public {@code sanitizePayload} helper to
     * verify the PCI-DSS scrubbing contract independently of the indexing
     * code path. Confirms:
     * <ul>
     *   <li>Sensitive keys ({@code password}, {@code cvv}, {@code pin},
     *       {@code secret}, {@code token}) are removed.</li>
     *   <li>Non-sensitive keys are preserved.</li>
     *   <li>Null or empty input returns a non-null empty map (defensive
     *       contract).</li>
     * </ul>
     */
    @Test
    @DisplayName("sanitizePayload removes sensitive keys and preserves non-sensitive keys")
    void sanitizePayload_removesSensitiveKeysPreservesOthers() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("amount", "100.00");
        input.put("password", "hunter2");
        input.put("cvv", "123");
        input.put("pin", "1234");
        input.put("secret", "token-xyz");
        input.put("token", "access-token");
        input.put("merchant", "Acme Co");

        Map<String, Object> out = service.sanitizePayload(input);
        assertNotNull(out, "sanitizePayload must never return null");

        // Sensitive keys removed
        assertFalse(out.containsKey("password"));
        assertFalse(out.containsKey("cvv"));
        assertFalse(out.containsKey("pin"));
        assertFalse(out.containsKey("secret"));
        assertFalse(out.containsKey("token"));

        // Non-sensitive keys preserved
        assertEquals("100.00", out.get("amount"));
        assertEquals("Acme Co", out.get("merchant"));
    }

    /**
     * Verifies the {@code sanitizePayload} defensive contract for
     * {@code null} input &mdash; returns a non-null empty map rather than
     * throwing {@link NullPointerException}.
     */
    @Test
    @DisplayName("sanitizePayload returns non-null empty map for null input")
    void sanitizePayload_nullInput_returnsEmptyMap() {
        Map<String, Object> out = service.sanitizePayload(null);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    // =========================================================================
    // Auxiliary: Convenience method delegation tests (auditEvent / auditTransaction)
    // =========================================================================

    /**
     * Verifies that the legacy {@code auditEvent(eventName, actor, payload)}
     * convenience overload delegates to {@code logAuditEvent} and therefore
     * routes the document to the {@code auditIndexName}. Backwards
     * compatibility with the {@code KafkaEventConsumer} call sites is
     * preserved.
     */
    @Test
    @DisplayName("Legacy auditEvent(...) delegates to logAuditEvent and indexes to carddemo-audit")
    void auditEvent_delegatesToLogAuditEvent() {
        service.auditEvent("CONFIG_CHANGED", "OP-001", Map.of("scope", "global"));

        verify(openSearchIndexer, atLeastOnce())
                .indexDocument(anyString(), anyString(), anyMap());

        ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
        verify(openSearchIndexer).indexDocument(indexCaptor.capture(), anyString(), anyMap());
        assertEquals("carddemo-audit", indexCaptor.getValue());
    }

    /**
     * Verifies that the legacy {@code auditTransaction(...)} convenience
     * overload delegates to {@code logTransactionEvent} and therefore
     * routes the document to the {@code transactionIndexName} and uses
     * the supplied {@code transactionId} as the doc ID.
     */
    @Test
    @DisplayName("Legacy auditTransaction(...) delegates to logTransactionEvent and uses transactionId as doc ID")
    void auditTransaction_delegatesToLogTransactionEvent() {
        service.auditTransaction(
                "TRANSACTION_POSTED",
                "OP-001",
                "TXN-LEGACY",
                Map.of("amount", "10.00"));

        ArgumentCaptor<String> indexCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> docIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(openSearchIndexer)
                .indexDocument(indexCaptor.capture(), docIdCaptor.capture(), anyMap());
        assertEquals("carddemo-transactions", indexCaptor.getValue());
        assertEquals("TXN-LEGACY", docIdCaptor.getValue());
    }

    // =========================================================================
    // Nested test group — Index routing matrix (auxiliary readability)
    // =========================================================================

    /**
     * Nested test group that verifies the index-routing matrix at a glance.
     * Each {@code log*} method MUST route to the documented OpenSearch
     * index name; misrouting would corrupt downstream regulatory queries
     * and fraud-investigation dashboards.
     */
    @Nested
    @DisplayName("Index routing matrix: each log* method routes to the correct OpenSearch index")
    class IndexRoutingMatrix {

        @Test
        @DisplayName("logTransactionEvent -> carddemo-transactions")
        void transactionRoutesToTransactionsIndex() {
            service.logTransactionEvent(
                    "TXN-IR1", 1L, "OP", "POSTED", "100", Map.of(), "c");
            verify(openSearchIndexer)
                    .indexDocument(org.mockito.ArgumentMatchers.eq("carddemo-transactions"),
                            anyString(), anyMap());
        }

        @Test
        @DisplayName("logAuditEvent -> carddemo-audit")
        void auditRoutesToAuditIndex() {
            service.logAuditEvent(
                    "EVT", "Account", "ACCT-1", "OP", Map.of(), "c");
            verify(openSearchIndexer)
                    .indexDocument(org.mockito.ArgumentMatchers.eq("carddemo-audit"),
                            anyString(), anyMap());
        }

        @Test
        @DisplayName("logSecurityEvent -> carddemo-security")
        void securityRoutesToSecurityIndex() {
            service.logSecurityEvent(
                    "LOGIN", "USR", "SUCCESS", "127.0.0.1", Map.of(), "c");
            verify(openSearchIndexer)
                    .indexDocument(org.mockito.ArgumentMatchers.eq("carddemo-security"),
                            anyString(), anyMap());
        }

        @Test
        @DisplayName("logBatchJobLifecycle -> carddemo-audit (shared with generic audit)")
        void batchLifecycleRoutesToAuditIndex() {
            service.logBatchJobLifecycle(
                    "POSTTRAN", "EXEC-IR", "STARTED", null, Map.of(), "c");
            verify(openSearchIndexer)
                    .indexDocument(org.mockito.ArgumentMatchers.eq("carddemo-audit"),
                            anyString(), anyMap());
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Capture and return the single {@code Map<String, Object>} document
     * argument passed to {@code openSearchIndexer.indexDocument(...)} in
     * the current test method. Assumes exactly one invocation; tests that
     * expect multiple invocations must capture directly with
     * {@code times(n)}.
     *
     * @return the captured document map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> captureIndexedDocument() {
        ArgumentCaptor<Map<String, Object>> docCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openSearchIndexer)
                .indexDocument(anyString(), anyString(), docCaptor.capture());
        return docCaptor.getValue();
    }
}
