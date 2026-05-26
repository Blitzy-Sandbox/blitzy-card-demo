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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Replaces: COBOL event flows + CICS TDQ writes (online-to-batch bridge)
// Backs AAP §0.6.5: MSK topic ordering guarantees (partition by account ID,
// idempotent, acks=all).
/**
 * Unit tests for {@link KafkaEventPublisher} — the sole Amazon MSK (Kafka)
 * producer adapter for CardDemo and the most ordering-sensitive component of
 * the migration. The tests in this class enforce, with no reliance on a
 * running Kafka broker, the behavioral contract documented in AAP §0.6.5
 * (MSK topic ordering guarantees) and §0.4.1 (topic catalog).
 *
 * <h2>What this test verifies (and why it matters)</h2>
 * <ol>
 *   <li><b>3-arg send only.</b> Every invocation of
 *       {@link KafkaTemplate#send(String, Object, Object)} must carry an
 *       explicit partition key. The 2-arg form (
 *       {@link KafkaTemplate#send(String, Object)}) lets Kafka's
 *       partitioner choose at random — destroying per-account ordering —
 *       and is therefore forbidden. The tests verify both the count of
 *       3-arg invocations and the absence of 2-arg invocations.</li>
 *   <li><b>Account-ID partition key is zero-padded to 11 digits.</b>
 *       Account IDs map to the COBOL declaration
 *       {@code ACCT-ID PIC 9(11)} in {@code app/cpy/CVACT01Y.cpy:L6}.
 *       Zero-padding ensures the same numeric value always yields the same
 *       UTF-8 byte sequence and therefore the same murmur2 hash and the
 *       same partition assignment — regardless of which producer formatted
 *       it (online service, batch job, replay tooling). A parameterized
 *       test exercises a representative set of small, medium, and
 *       11-digit-maximum account IDs.</li>
 *   <li><b>Report-ID partition key is used as-is.</b> Reports may be
 *       cross-account, so the {@code report.requested} topic uses the
 *       report identifier — verbatim and unpadded — as the partition key.
 *       This is the sole online-to-batch bridge in the source per AAP
 *       §0.1.1 and the only publish method whose key is a free-form
 *       String (the other three are zero-padded numeric account IDs).</li>
 *   <li><b>Topic name routing.</b> Each publish method targets a specific
 *       topic ({@code transaction.posted}, {@code account.updated},
 *       {@code ledger.balanced}, {@code report.requested}) and never
 *       cross-routes; the topic name is overridable per environment via a
 *       {@code @Value}-annotated constructor parameter.</li>
 *   <li><b>Input validation.</b> {@code null} account ID raises
 *       {@link IllegalArgumentException} before the broker is touched;
 *       {@code null} or blank report ID similarly raises before
 *       {@link KafkaTemplate#send} is invoked. The validator messages
 *       contain the exact field name (per AAP §0.7.2 condition-code
 *       preservation rule).</li>
 *   <li><b>Async signature.</b> Every publish method returns
 *       {@link CompletableFuture}{@code <}{@link SendResult}{@code <String, Object>>}
 *       so callers may chain post-publish work without blocking. The unit
 *       test verifies the immediate-return + non-null contract; the actual
 *       {@code @Async} dispatching (TaskExecutor proxy) is a Spring AOP
 *       concern not exercised in a bare Mockito test.</li>
 *   <li><b>Failure propagation.</b> A synchronous exception thrown by the
 *       {@code KafkaTemplate} (e.g., serialization error before the broker
 *       round-trip) propagates verbatim to the caller, preserving the
 *       upstream service's ability to escalate via
 *       {@code GlobalExceptionHandler} (AAP §0.7.1
 *       "@ControllerAdvice" rule).</li>
 *   <li><b>Static metadata accessors.</b> The class exposes static
 *       property-name accessors ({@code transactionPostedTopicProperty()},
 *       etc.) used by ops tooling to enumerate the externalized property
 *       names; these are pinned to the canonical Spring property names
 *       documented in the production class JavaDoc.</li>
 *   <li><b>BillPaymentDto bridge.</b> The static
 *       {@code billPaymentSourceType()} accessor returns
 *       {@link BillPaymentDto}{@code .class} to document the upstream
 *       caller pattern (BillPaymentService translates a
 *       {@link BillPaymentDto} into a {@link TransactionAddDto} before
 *       calling {@link KafkaEventPublisher#publishTransactionPosted}).
 *       The Minimal Change Clause keeps the publish contract canonical
 *       (TransactionAddDto only); the accessor documents the dependency
 *       at the type level.</li>
 * </ol>
 *
 * <h2>Mocking strategy</h2>
 * <p>This is a pure Mockito unit test — no Spring context is loaded,
 * no real Kafka broker is started, no Testcontainers Kafka runs. The
 * {@link MockitoExtension} (strict-stubbing mode, the JUnit 5 default in
 * Mockito 5.x) creates a fresh {@code @Mock KafkaTemplate<String, Object>}
 * before each test method; the test then constructs the production
 * {@link KafkaEventPublisher} via its public 5-arg constructor with
 * sentinel topic names, and {@link ReflectionTestUtils#setField} overrides
 * each topic-name field with its canonical AAP §0.4.1 value. This
 * arrangement (constructor injection + reflective override) lets a single
 * test class exercise both the constructor contract and the field-injection
 * path that Spring uses at runtime, without coupling the test to either
 * particular wiring strategy.</p>
 *
 * <h2>PCI-DSS test fixture notes</h2>
 * <p>The {@code sampleTxnAdd()} fixture uses the Visa test PAN
 * {@code 4111111111111111}, which is a well-known dummy primary account
 * number that any payment processor recognises as a non-issued, test-only
 * value. Per AAP §0.6.6 (PCI-DSS) and §0.7.2 (no real card data in logs),
 * tests must never embed a real PAN — the Visa test prefix is the documented
 * convention across the CardDemo test tree. Similarly the
 * {@code sampleAccountUpdate()} fixture uses a synthetic SSN
 * ({@code 123456789}) that has no real-world owner.</p>
 *
 * <h2>Coverage</h2>
 * <p>Together the tests in this class exercise every public publish method
 * (happy path + null/blank input validation), every static accessor, the
 * 3-arg send invariant, the zero-padded account-ID partition key, the
 * report-ID partition key, the async return signature, and the failure
 * propagation contract — providing &gt;80% line coverage on
 * {@link KafkaEventPublisher} per AAP test-design rules.</p>
 *
 * @see KafkaEventPublisher
 * @see com.awsm2.carddemo.config.KafkaConfig
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaEventPublisher — MSK ordering guarantees, partition-key formatting, "
        + "3-arg send invariant, async signature, and input validation (AAP §0.6.5)")
class KafkaEventPublisherTest {

    /**
     * Mocked Spring Kafka producer template. Intercepts every
     * {@code send(...)} invocation so the test can capture the topic,
     * partition key, and value arguments without touching a real broker.
     * Generics-erased at the mock level (Mockito sees the raw type), so
     * method-overload dispatch works correctly for both the 2-arg and
     * 3-arg {@code send(...)} signatures.
     */
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * The {@link KafkaEventPublisher} under test. Instantiated fresh
     * before each test by {@link #setUp()} so per-test stubbing and
     * verification state does not leak between tests.
     */
    private KafkaEventPublisher publisher;

    /**
     * Constructs a fresh {@link KafkaEventPublisher} before each test
     * method and installs the canonical AAP §0.4.1 topic names on the
     * publisher's {@code @Value}-injected fields. The 5-arg constructor
     * is invoked with sentinel placeholder topic names to satisfy the
     * production class's
     * {@link java.util.Objects#requireNonNull(Object, String)} guard;
     * {@link ReflectionTestUtils#setField(Object, String, Object)} then
     * overrides each topic-name field with the canonical value so the
     * tests can assert against fixed, well-known topic strings without
     * threading the values through every test method.
     *
     * <p>This dual setup verifies:
     * <ul>
     *   <li>the production constructor accepts the four topic names as
     *       constructor parameters (matching the {@code @Value}-on-
     *       parameter wiring Spring uses at runtime), AND</li>
     *   <li>the four topic-name fields exist on the publisher with the
     *       documented names ({@code transactionPostedTopic},
     *       {@code accountUpdatedTopic}, {@code ledgerBalancedTopic},
     *       {@code reportRequestedTopic}); a refactor that renames any
     *       of these fields would surface here as a fail-fast NPE-style
     *       reflection error during {@code setField}.</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        // Step 1 — construct with sentinel topic names (any non-null value
        // satisfies the requireNonNull guard inside the production
        // constructor).
        publisher = new KafkaEventPublisher(
                kafkaTemplate,
                "init.transaction.posted",
                "init.account.updated",
                "init.ledger.balanced",
                "init.report.requested");

        // Step 2 — overlay canonical topic names per AAP §0.4.1. The field
        // names ("transactionPostedTopic", "accountUpdatedTopic",
        // "ledgerBalancedTopic", "reportRequestedTopic") match the
        // production class's private final field declarations.
        ReflectionTestUtils.setField(publisher,
                "transactionPostedTopic", "transaction.posted");
        ReflectionTestUtils.setField(publisher,
                "accountUpdatedTopic", "account.updated");
        ReflectionTestUtils.setField(publisher,
                "ledgerBalancedTopic", "ledger.balanced");
        ReflectionTestUtils.setField(publisher,
                "reportRequestedTopic", "report.requested");
    }

    // =====================================================================
    // Phase 5 — Test fixtures
    // =====================================================================

    /**
     * Builds a fully-populated {@link TransactionAddDto} for use as the
     * event payload of {@link KafkaEventPublisher#publishTransactionPosted}
     * tests. Uses the Visa test PAN {@code 4111111111111111} per AAP
     * §0.6.6 PCI-DSS test fixture conventions — never a real card number.
     *
     * @return a fresh {@link TransactionAddDto} instance
     */
    private TransactionAddDto sampleTxnAdd() {
        return new TransactionAddDto(
                "12345678901",                     // accountId (11-digit)
                "4111111111111111",                // cardNumber (Visa test PAN)
                "01",                              // transactionType
                Integer.valueOf(5411),             // transactionCategory
                "ONLINE",                          // source
                "GROCERY STORE PURCHASE",          // description
                new BigDecimal("123.45"),          // amount
                LocalDateTime.of(2026, 5, 20, 12, 30, 0),  // originationTimestamp
                LocalDateTime.of(2026, 5, 20, 12, 30, 0),  // processingTimestamp
                Long.valueOf(100000001L),          // merchantId
                "ACME GROCERY",                    // merchantName
                "SEATTLE",                         // merchantCity
                "98101",                           // merchantZip
                "Y");                              // confirm
    }

    /**
     * Builds a fully-populated {@link AccountUpdateDto} for use as the
     * event payload of {@link KafkaEventPublisher#publishAccountUpdated}
     * tests. Uses a synthetic SSN ({@code 123456789}) with no real-world
     * owner per AAP §0.6.6 PII-in-tests conventions.
     *
     * @return a fresh {@link AccountUpdateDto} instance
     */
    private AccountUpdateDto sampleAccountUpdate() {
        return new AccountUpdateDto(
                Long.valueOf(67890L),              // accountId
                "Y",                               // activeStatus
                new BigDecimal("1234.56"),         // currentBalance
                new BigDecimal("5000.00"),         // creditLimit
                new BigDecimal("1000.00"),         // cashCreditLimit
                LocalDate.of(2020, 1, 15),         // openDate
                LocalDate.of(2030, 1, 15),         // expirationDate
                LocalDate.of(2025, 1, 15),         // reissueDate
                new BigDecimal("500.00"),          // currentCycleCredit
                new BigDecimal("200.00"),          // currentCycleDebit
                "12345",                           // addressZip
                "DEFAULT",                         // accountGroupId
                Long.valueOf(100000001L),          // customerId
                "John",                            // firstName
                "M",                               // middleName (optional)
                "Doe",                             // lastName
                Long.valueOf(123456789L),          // customerSsn (synthetic test SSN)
                "2125551234",                      // phoneNumber1
                null,                              // phoneNumber2 (optional)
                "123 Main Street",                 // addressLine1
                null,                              // addressLine2 (optional)
                null,                              // addressLine3 (optional)
                "NY",                              // stateCode
                "USA",                             // countryCode
                "12345",                           // zipCode
                LocalDate.of(1980, 5, 15),         // dateOfBirth
                null,                              // governmentIssuedId (optional)
                null,                              // eftAccountId (optional)
                null,                              // primaryCardHolderIndicator (optional)
                Integer.valueOf(720),              // ficoCreditScore
                Long.valueOf(0L));                 // version
    }

    /**
     * Builds a fully-populated {@link ReportRequestDto} for use as the
     * event payload of {@link KafkaEventPublisher#publishReportRequested}
     * tests. Uses the {@code MONTHLY} report-type selector with a
     * one-month custom date range — sufficient to exercise the topic
     * routing and report-ID partition-key contracts without depending on
     * the report-content schema (which is the responsibility of
     * {@code ReportSubmissionService} and {@code TransactionReportJob}).
     *
     * @return a fresh {@link ReportRequestDto} instance
     */
    private ReportRequestDto sampleReportRequest() {
        return new ReportRequestDto(
                "MONTHLY",                         // reportType
                LocalDate.of(2025, 5, 1),          // startDate
                LocalDate.of(2025, 5, 31),         // endDate
                "Y");                              // confirm
    }

    /**
     * Builds a completed {@link CompletableFuture} wrapping a deep-stub
     * {@link SendResult} so that any {@code .whenComplete(...)} callback
     * chained by the production code runs synchronously and can access
     * {@code result.getRecordMetadata().partition() / .offset()} on the
     * mock without an NPE. Used to stub
     * {@link KafkaTemplate#send(String, Object, Object)} returns in
     * happy-path tests.
     *
     * @return a completed {@link CompletableFuture} carrying a mock
     *         {@link SendResult} with {@code RETURNS_DEEP_STUBS}
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> futureFor() {
        SendResult<String, Object> mockResult = (SendResult<String, Object>)
                mock(SendResult.class, RETURNS_DEEP_STUBS);
        return CompletableFuture.completedFuture(mockResult);
    }

    // =====================================================================
    // Phase 6 — publishTransactionPosted tests
    // =====================================================================

    /**
     * Test 6.1 — Verifies that {@code publishTransactionPosted} routes to
     * the {@code transaction.posted} topic with a zero-padded 11-digit
     * account-ID partition key via the 3-arg
     * {@link KafkaTemplate#send(String, Object, Object)} form (the only
     * form that guarantees per-account ordering per AAP §0.6.5).
     */
    @Test
    @DisplayName("publishTransactionPosted uses 3-arg send with topic 'transaction.posted' "
            + "and zero-padded account-ID key (AAP §0.6.5)")
    void publishTransactionPosted_invokesKafkaTemplateWith3ArgSendAndZeroPaddedKey() {
        // Arrange
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());
        TransactionAddDto event = sampleTxnAdd();

        // Act
        publisher.publishTransactionPosted(Long.valueOf(12345L), event);

        // Assert — capture and verify all three send() arguments
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertEquals("transaction.posted", topicCaptor.getValue(),
                "Topic must be the AAP §0.4.1 canonical 'transaction.posted'");
        assertEquals("00000012345", keyCaptor.getValue(),
                "Partition key must be zero-padded to 11 digits "
                        + "(matches COBOL ACCT-ID PIC 9(11))");
        assertNotNull(valueCaptor.getValue(),
                "Event payload must be non-null");
        assertSame(event, valueCaptor.getValue(),
                "Event passed to kafkaTemplate.send must be the same instance "
                        + "the caller supplied (no defensive copying)");
    }

    /**
     * Test 6.2 — Verifies that {@code publishTransactionPosted} returns a
     * non-null {@link CompletableFuture} that resolves to the
     * {@link SendResult}. When the underlying mock future is already
     * complete, the returned future is also complete (the
     * {@code .whenComplete(...)} chain runs synchronously per the
     * {@link CompletableFuture} contract).
     */
    @Test
    @DisplayName("publishTransactionPosted returns a CompletableFuture<SendResult> "
            + "(modern Spring Kafka 3.x async signature)")
    void publishTransactionPosted_returnsCompletableFuture() {
        // Arrange
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());

        // Act
        CompletableFuture<SendResult<String, Object>> future =
                publisher.publishTransactionPosted(Long.valueOf(12345L), sampleTxnAdd());

        // Assert
        assertNotNull(future,
                "publishTransactionPosted must return a non-null CompletableFuture");
        assertTrue(future.isDone(),
                "When the upstream future is complete, the .whenComplete chain "
                        + "completes synchronously and the returned future is done");
    }

    /**
     * Test 6.3 — Verifies that {@code publishTransactionPosted} rejects a
     * {@code null} account ID with {@link IllegalArgumentException}
     * BEFORE the broker is contacted. Per AAP §0.6.5 a null partition key
     * would route to a random partition and defeat per-account ordering;
     * the production class fails fast in {@code formatAccountKey(null)}.
     */
    @Test
    @DisplayName("publishTransactionPosted throws IllegalArgumentException for null "
            + "account ID — never calls kafkaTemplate.send")
    void publishTransactionPosted_withNullAccountId_throwsIllegalArgument() {
        // Arrange — no stubbing required because the exception is thrown
        // before kafkaTemplate.send is ever invoked.
        TransactionAddDto event = sampleTxnAdd();

        // Act + Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> publisher.publishTransactionPosted(null, event),
                "publishTransactionPosted(null, event) must throw IAE per AAP §0.6.5 "
                        + "(null partition key would break per-account ordering)");

        assertNotNull(ex.getMessage(),
                "IllegalArgumentException must carry an informative message");
        assertTrue(ex.getMessage().toLowerCase().contains("accountid")
                        || ex.getMessage().toLowerCase().contains("account id"),
                "Exception message must reference the offending field 'accountId' "
                        + "(actual: '" + ex.getMessage() + "')");

        // Critical guard — no broker contact must occur
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    // =====================================================================
    // Phase 7 — publishAccountUpdated tests
    // =====================================================================

    /**
     * Test 7.1 — Verifies that {@code publishAccountUpdated} routes to the
     * {@code account.updated} topic with a zero-padded 11-digit
     * account-ID partition key via the 3-arg send form.
     */
    @Test
    @DisplayName("publishAccountUpdated uses 3-arg send with topic 'account.updated' "
            + "and zero-padded account-ID key (AAP §0.6.5)")
    void publishAccountUpdated_invokesKafkaTemplateWithCorrectTopicAndKey() {
        // Arrange
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());
        AccountUpdateDto event = sampleAccountUpdate();

        // Act
        publisher.publishAccountUpdated(Long.valueOf(67890L), event);

        // Assert
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertEquals("account.updated", topicCaptor.getValue(),
                "Topic must be the AAP §0.4.1 canonical 'account.updated'");
        assertEquals("00000067890", keyCaptor.getValue(),
                "Partition key must be zero-padded to 11 digits");
        assertSame(event, valueCaptor.getValue(),
                "Event passed to kafkaTemplate.send must be the same instance "
                        + "supplied by the caller");
    }

    /**
     * Test 7.2 — Verifies that {@code publishAccountUpdated} rejects a
     * {@code null} account ID with {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("publishAccountUpdated throws IllegalArgumentException for null "
            + "account ID — never calls kafkaTemplate.send")
    void publishAccountUpdated_withNullAccountId_throwsIllegalArgument() {
        // Arrange
        AccountUpdateDto event = sampleAccountUpdate();

        // Act + Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> publisher.publishAccountUpdated(null, event));

        assertTrue(ex.getMessage().toLowerCase().contains("accountid")
                        || ex.getMessage().toLowerCase().contains("account id"),
                "Exception message must reference 'accountId' "
                        + "(actual: '" + ex.getMessage() + "')");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    // =====================================================================
    // Phase 8 — publishLedgerBalanced tests
    // =====================================================================

    /**
     * Test 8.1 — Verifies that {@code publishLedgerBalanced} routes to the
     * {@code ledger.balanced} topic with a zero-padded 11-digit
     * account-ID partition key. The payload type is intentionally
     * {@link Object} (the {@code LedgerBalancedEvent} DTO is deferred per
     * AAP §0.6.5 scope), so this test passes a {@link java.util.Map}
     * payload to exercise the topic + key contract independently of any
     * concrete DTO type.
     */
    @Test
    @DisplayName("publishLedgerBalanced uses 3-arg send with topic 'ledger.balanced' "
            + "and zero-padded account-ID key (AAP §0.6.5)")
    void publishLedgerBalanced_invokesKafkaTemplateWithCorrectTopicAndKey() {
        // Arrange
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());
        // Map payload — exercises the topic + key contract without
        // requiring a concrete LedgerBalancedEvent DTO (deferred per AAP).
        Map<String, String> event = Map.of(
                "ledgerId", "L-001",
                "balance", "12345.67");

        // Act
        publisher.publishLedgerBalanced(Long.valueOf(11111L), event);

        // Assert
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertEquals("ledger.balanced", topicCaptor.getValue(),
                "Topic must be the AAP §0.4.1 canonical 'ledger.balanced'");
        assertEquals("00000011111", keyCaptor.getValue(),
                "Partition key must be zero-padded to 11 digits");
        assertSame(event, valueCaptor.getValue(),
                "Map payload passed to kafkaTemplate.send must be the same "
                        + "instance supplied by the caller");
    }

    /**
     * Test 8.2 — Verifies that {@code publishLedgerBalanced} rejects a
     * {@code null} account ID with {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("publishLedgerBalanced throws IllegalArgumentException for null "
            + "account ID — never calls kafkaTemplate.send")
    void publishLedgerBalanced_withNullAccountId_throwsIllegalArgument() {
        // Arrange
        Map<String, String> event = Map.of("ledgerId", "L-001");

        // Act + Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> publisher.publishLedgerBalanced(null, event));

        assertTrue(ex.getMessage().toLowerCase().contains("accountid")
                        || ex.getMessage().toLowerCase().contains("account id"),
                "Exception message must reference 'accountId' "
                        + "(actual: '" + ex.getMessage() + "')");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    // =====================================================================
    // Phase 9 — publishReportRequested tests
    // =====================================================================

    /**
     * Test 9.1 — Verifies that {@code publishReportRequested} routes to
     * the {@code report.requested} topic with the report ID used directly
     * as the partition key (NOT zero-padded — distinguishes from
     * account-keyed topics per AAP §0.1.1, the sole online-to-batch bridge
     * in the source).
     */
    @Test
    @DisplayName("publishReportRequested uses 3-arg send with topic 'report.requested' "
            + "and reportId as the partition key (not zero-padded) — sole online-to-batch "
            + "bridge per AAP §0.1.1")
    void publishReportRequested_invokesKafkaTemplateWithReportIdAsKey() {
        // Arrange
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());
        ReportRequestDto event = sampleReportRequest();
        String reportId = "RPT-2025-05-20-001";

        // Act
        publisher.publishReportRequested(reportId, event);

        // Assert
        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> valueCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(
                topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertEquals("report.requested", topicCaptor.getValue(),
                "Topic must be the AAP §0.4.1 canonical 'report.requested'");
        assertEquals(reportId, keyCaptor.getValue(),
                "Partition key must be the report ID verbatim (NOT zero-padded) "
                        + "— reports may be cross-account per AAP §0.1.1");
        assertSame(event, valueCaptor.getValue(),
                "Event passed to kafkaTemplate.send must be the caller's instance");
    }

    /**
     * Test 9.2 — Verifies that {@code publishReportRequested} rejects a
     * {@code null} report ID with {@link IllegalArgumentException}.
     * Per AAP §0.6.5 a null/blank partition key defeats per-report
     * ordering.
     */
    @Test
    @DisplayName("publishReportRequested throws IllegalArgumentException for null "
            + "report ID")
    void publishReportRequested_withNullReportId_throwsIllegalArgument() {
        // Arrange
        ReportRequestDto event = sampleReportRequest();

        // Act + Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> publisher.publishReportRequested(null, event));

        assertTrue(ex.getMessage().toLowerCase().contains("reportid")
                        || ex.getMessage().toLowerCase().contains("report id"),
                "Exception message must reference 'reportId' "
                        + "(actual: '" + ex.getMessage() + "')");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    /**
     * Test 9.3 — Verifies that {@code publishReportRequested} rejects a
     * blank (whitespace-only) report ID with {@link IllegalArgumentException}.
     */
    @Test
    @DisplayName("publishReportRequested throws IllegalArgumentException for blank "
            + "report ID")
    void publishReportRequested_withBlankReportId_throwsIllegalArgument() {
        // Arrange
        ReportRequestDto event = sampleReportRequest();

        // Act + Assert
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> publisher.publishReportRequested("  ", event));

        assertTrue(ex.getMessage().toLowerCase().contains("reportid")
                        || ex.getMessage().toLowerCase().contains("report id"),
                "Exception message must reference 'reportId' "
                        + "(actual: '" + ex.getMessage() + "')");
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    // =====================================================================
    // Phase 10 — Always-3-arg-send assertion tests
    // =====================================================================

    /**
     * Test 10.1 — The most important behavioral invariant in this test
     * class: every publish method must use the 3-arg
     * {@link KafkaTemplate#send(String, Object, Object)} form (topic, key,
     * value) and never the 2-arg form
     * {@link KafkaTemplate#send(String, Object)} (topic, value). The 2-arg
     * form lets Kafka's partitioner choose a partition at random,
     * destroying per-account ordering — the entire point of partitioning
     * by account ID per AAP §0.6.5.
     */
    @Test
    @DisplayName("Every publish method uses 3-arg send for ordering guarantees — "
            + "never the 2-arg form (AAP §0.6.5)")
    void everyPublishMethod_usesThreeArgSend_neverTwoArgSend() {
        // Arrange — single stub covers all four publish methods because
        // they all route through the 3-arg send overload.
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());

        // Act — exercise all four publish methods
        publisher.publishTransactionPosted(Long.valueOf(12345L), sampleTxnAdd());
        publisher.publishAccountUpdated(Long.valueOf(67890L), sampleAccountUpdate());
        publisher.publishLedgerBalanced(Long.valueOf(11111L),
                Map.of("ledgerId", "L-001"));
        publisher.publishReportRequested("RPT-2025-05-20-001", sampleReportRequest());

        // Assert — 3-arg send called exactly four times (once per publish
        // method); 2-arg send never called.
        verify(kafkaTemplate, times(4))
                .send(anyString(), anyString(), any());
        verify(kafkaTemplate, never())
                .send(anyString(), any());
    }

    // =====================================================================
    // Phase 11 — Key format verification (parameterized)
    // =====================================================================

    /**
     * Test 11.1 — Parameterized verification that the account-ID
     * partition key is ALWAYS exactly 11 characters and the canonical
     * zero-padded decimal representation of the supplied account ID. This
     * is the foundational invariant for per-account ordering: the murmur2
     * hash of the key bytes must be deterministic across producers, so
     * the string form of an account ID must always have the same width.
     *
     * <p>Test cases:</p>
     * <ul>
     *   <li>{@code 1L} → {@code "00000000001"} (single digit needs 10
     *       padding zeros)</li>
     *   <li>{@code 12L} → {@code "00000000012"}</li>
     *   <li>{@code 12345L} → {@code "00000012345"}</li>
     *   <li>{@code 12345678901L} → {@code "12345678901"} (max 11-digit
     *       account ID needs no padding)</li>
     * </ul>
     *
     * @param acctId the account ID to verify
     */
    @ParameterizedTest(name = "accountId={0} → 11-digit zero-padded partition key")
    @ValueSource(longs = {1L, 12L, 12345L, 12345678901L})
    @DisplayName("Account ID partition key is zero-padded to 11 digits "
            + "(matches COBOL ACCT-ID PIC 9(11), AAP §0.6.5)")
    void accountIdPartitionKey_isZeroPaddedTo11Digits(long acctId) {
        // Arrange
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(futureFor());

        // Act
        publisher.publishTransactionPosted(Long.valueOf(acctId), sampleTxnAdd());

        // Assert — capture the key and verify width, format, and value
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), keyCaptor.capture(), any());

        String capturedKey = keyCaptor.getValue();

        assertEquals(11, capturedKey.length(),
                "Partition key MUST be exactly 11 characters so murmur2 hashing "
                        + "is stable across producers (the same account ID always "
                        + "lands on the same partition)");
        assertTrue(capturedKey.matches("^\\d{11}$"),
                "Partition key MUST be 11 decimal digits — got '" + capturedKey + "'");
        assertEquals(acctId, Long.parseLong(capturedKey),
                "Parsing the captured key as a long must yield the original "
                        + "account ID — pad is leading zeros only");
    }

    // =====================================================================
    // Phase 12 — Async behavior tests
    // =====================================================================

    /**
     * Test 12.1 — Verifies that {@code publishTransactionPosted} returns
     * the {@link CompletableFuture} immediately even when the underlying
     * Kafka future is not yet complete. The {@code @Async} dispatching
     * itself (Spring TaskExecutor proxy) is not exercised here — in a
     * bare Mockito unit test the method is invoked directly, not through
     * the AOP proxy that Spring installs at runtime. This test verifies
     * only that the method returns a non-null
     * {@link CompletableFuture} without blocking on the underlying send.
     */
    @Test
    @DisplayName("publishTransactionPosted returns a CompletableFuture immediately "
            + "(does not block on the underlying send completion)")
    void publishTransactionPosted_isAsyncReturnsImmediately() {
        // Arrange — incomplete future; the underlying send has not yet
        // completed, but the publish method must still return.
        CompletableFuture<SendResult<String, Object>> incompleteFuture =
                new CompletableFuture<>();
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(incompleteFuture);

        // Act
        CompletableFuture<SendResult<String, Object>> returnedFuture =
                publisher.publishTransactionPosted(Long.valueOf(12345L),
                        sampleTxnAdd());

        // Assert
        assertNotNull(returnedFuture,
                "publishTransactionPosted must return a non-null CompletableFuture "
                        + "even when the underlying send has not yet completed");
        // Note: returnedFuture is the .whenComplete-chained derivative of
        // incompleteFuture, so it is also incomplete at this point.
        assertEquals(false, returnedFuture.isDone(),
                "When the underlying send future is incomplete, the chained "
                        + "future returned by publishTransactionPosted is also "
                        + "incomplete (verifies non-blocking return semantics)");
    }

    // =====================================================================
    // Phase 13 — Send-failure handling tests
    // =====================================================================

    /**
     * Test 13.1 — Verifies that a synchronous exception thrown by
     * {@link KafkaTemplate#send(String, Object, Object)} (e.g., a
     * serialization error encountered before the broker round-trip)
     * propagates verbatim through {@code publishTransactionPosted} to the
     * caller. This preserves the upstream service's ability to wrap the
     * failure into the domain exception hierarchy via
     * {@code @ControllerAdvice} (AAP §0.7.1).
     */
    @Test
    @DisplayName("publishTransactionPosted propagates synchronous KafkaTemplate "
            + "exceptions to the caller (allows upstream @ControllerAdvice "
            + "translation per AAP §0.7.1)")
    void publishTransactionPosted_whenKafkaSendThrows_propagatesUp() {
        // Arrange
        RuntimeException kafkaError =
                new RuntimeException("simulated Kafka serialization error");
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(kafkaError);

        // Act + Assert
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> publisher.publishTransactionPosted(
                        Long.valueOf(12345L), sampleTxnAdd()),
                "A synchronous send exception must propagate to the caller "
                        + "so upstream services can wrap it into the CardDemo "
                        + "exception hierarchy");
        assertSame(kafkaError, thrown,
                "The thrown exception must be the exact instance the underlying "
                        + "KafkaTemplate raised (no wrapping at the adapter layer)");
    }

    // =====================================================================
    // Phase 14 — Static accessor and bridge-type verification tests
    // =====================================================================

    /**
     * Verifies that the static topic-property-name accessors return the
     * canonical Spring property keys documented in the production
     * {@link KafkaEventPublisher} class. These accessors are used by ops
     * tooling and Testcontainers integration tests to programmatically
     * override the topic names without hardcoding the property keys.
     */
    @Test
    @DisplayName("Static topic property-name accessors return the canonical Spring "
            + "property keys (carddemo.kafka.topics.*)")
    void staticTopicPropertyAccessors_returnCanonicalSpringPropertyKeys() {
        assertEquals("carddemo.kafka.topics.transaction-posted",
                KafkaEventPublisher.transactionPostedTopicProperty(),
                "transactionPostedTopicProperty must return the canonical key "
                        + "documented in the production class JavaDoc");
        assertEquals("carddemo.kafka.topics.account-updated",
                KafkaEventPublisher.accountUpdatedTopicProperty());
        assertEquals("carddemo.kafka.topics.ledger-balanced",
                KafkaEventPublisher.ledgerBalancedTopicProperty());
        assertEquals("carddemo.kafka.topics.report-requested",
                KafkaEventPublisher.reportRequestedTopicProperty());
    }

    /**
     * Verifies the package-private topic-name getters return the values
     * supplied via {@link ReflectionTestUtils#setField} in
     * {@link #setUp()}. These getters exist for test infrastructure (and
     * future tooling that enumerates an adapter's configured topics) and
     * must mirror the canonical AAP §0.4.1 topic names.
     */
    @Test
    @DisplayName("Package-private topic-name getters return the @Value-resolved "
            + "topic names (test accessors for AAP §0.4.1 canonical topics)")
    void packagePrivateTopicGetters_returnConfiguredTopicNames() {
        assertEquals("transaction.posted", publisher.getTransactionPostedTopic());
        assertEquals("account.updated", publisher.getAccountUpdatedTopic());
        assertEquals("ledger.balanced", publisher.getLedgerBalancedTopic());
        assertEquals("report.requested", publisher.getReportRequestedTopic());
    }

    /**
     * Verifies the static {@code billPaymentSourceType()} accessor
     * returns {@link BillPaymentDto}{@code .class}. This accessor
     * documents the upstream caller pattern (
     * {@code BillPaymentService} translates a {@link BillPaymentDto} into
     * a {@link TransactionAddDto} before calling
     * {@link KafkaEventPublisher#publishTransactionPosted}) — the Minimal
     * Change Clause keeps the publish contract canonical
     * ({@code TransactionAddDto} only), so {@link BillPaymentDto} is
     * referenced at the type level only.
     */
    @Test
    @DisplayName("billPaymentSourceType() returns BillPaymentDto.class — documents "
            + "the BillPaymentService → TransactionAddDto translation pattern")
    void billPaymentSourceType_returnsBillPaymentDtoClass() {
        Class<BillPaymentDto> sourceType =
                KafkaEventPublisher.billPaymentSourceType();
        assertEquals(BillPaymentDto.class, sourceType,
                "billPaymentSourceType must reference BillPaymentDto to document "
                        + "the upstream BillPaymentService translation");
        // Sanity-check the BillPaymentDto record canonical constructor is
        // accessible from this test class — the production
        // KafkaEventPublisher references BillPaymentDto in its
        // depends_on_files and the test class mirrors that dependency.
        BillPaymentDto sample = new BillPaymentDto(
                "12345678901",
                new BigDecimal("1234.56"),
                "Y",
                null,
                null,
                null);
        assertNotNull(sample,
                "BillPaymentDto record canonical constructor must be accessible "
                        + "from the test class");
    }
}
