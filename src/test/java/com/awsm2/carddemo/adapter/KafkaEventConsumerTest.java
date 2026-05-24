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

// Replaces: CORPT00C → CICS TDQ JOBS → JES submission (online-to-batch bridge)
//           and the consumer side of every transaction event topic
// Backs AAP §0.6.5 (manual offset commit), §0.1.1 (online-to-batch bridge)

import com.awsm2.carddemo.dto.AccountUpdateDto;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.dto.TransactionAddDto;
import com.awsm2.carddemo.exception.CardDemoException;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KafkaEventConsumer} &mdash; the sole Amazon MSK
 * (Kafka) consumer adapter for CardDemo and the consumer side of the
 * online-to-batch bridge.
 *
 * <h2>What this test class verifies</h2>
 * <ol>
 *   <li><b>Manual offset commit discipline (AAP &sect;0.6.5).</b> Every
 *       successful processing path ends with exactly one
 *       {@link Acknowledgment#acknowledge()} call. A
 *       {@link CardDemoException} (a recoverable, COBOL-reason-coded
 *       domain failure) propagates out of the listener so that the
 *       Spring Kafka {@code DefaultErrorHandler} can route the record
 *       through its retry / dead-letter-topic configuration &mdash; the
 *       handler MUST NOT acknowledge in that case (offset stays
 *       uncommitted, Kafka redelivers). An unrecoverable {@link Exception}
 *       (e.g. {@link NullPointerException} from a poison record) IS
 *       acknowledged so the consumer group does not stall on the poison
 *       message.</li>
 *   <li><b>Online-to-batch bridge (AAP &sect;0.1.1).</b>
 *       {@link KafkaEventConsumer#onReportRequested} forwards every
 *       inbound {@code report.requested} event to
 *       {@link StepFunctionsOrchestrator#startExecution(String, String)}
 *       using the configured {@code reportPipelineStateMachineArn}.
 *       When the ARN is blank (local profile) the listener gracefully
 *       degrades &mdash; it skips the orchestrator invocation but still
 *       emits the inbound-side audit record and acknowledges so local
 *       iteration is unblocked.</li>
 *   <li><b>Audit delegation (AAP &sect;0.6.6).</b> Every listener
 *       delegates to {@link AuditLogService} on the happy path:
 *       {@code onTransactionPosted}, {@code onAccountUpdated},
 *       {@code onLedgerBalanced} use
 *       {@link AuditLogService#logTransactionEvent} (the transaction
 *       lifecycle path that preserves verbatim COBOL reason codes);
 *       {@code onReportRequested} uses
 *       {@link AuditLogService#logAuditEvent} twice (once for the
 *       inbound envelope, once with the started {@code executionArn}).</li>
 *   <li><b>Header parameter routing.</b> Each listener's six-parameter
 *       method signature ({@code @Payload}, {@code @Header
 *       RECEIVED_KEY}, {@code @Header RECEIVED_TOPIC},
 *       {@code @Header RECEIVED_PARTITION}, {@code @Header OFFSET},
 *       {@link Acknowledgment}) forwards Kafka metadata into the audit
 *       payload via {@link ArgumentCaptor} assertions.</li>
 *   <li><b>Listener annotation contract (AAP &sect;0.4.1).</b> Each
 *       listener method carries a {@link KafkaListener} annotation with
 *       the expected {@code topics}, {@code groupId}, and
 *       {@code containerFactory} property-placeholder values, and the
 *       class itself carries {@link Component} (Kafka listener
 *       containers are conventionally {@code @Component}, not
 *       {@code @Service}, so the {@code @EnableKafka} machinery
 *       registers them with the container factory bean).</li>
 * </ol>
 *
 * <h2>Test isolation</h2>
 * <p>This is a strict unit test &mdash; no embedded Kafka broker, no
 * {@code @EmbeddedKafka}, no {@code @SpringBootTest}, no
 * {@code Testcontainers}. Every {@code @KafkaListener} method is invoked
 * directly with synthesized header values, and every collaborator is a
 * Mockito mock. LocalStack-backed and broker-backed integration tests
 * live in the {@code integration} test package (per AAP &sect;0.7.2
 * "testing approach" section). The
 * {@link ReflectionTestUtils#setField(Object, String, Object)} call in
 * {@link #beforeEach()} sets the {@code reportPipelineStateMachineArn}
 * field on the consumer instance so the
 * {@code @Value("${carddemo.aws.stepfunctions.report-pipeline-arn:}")}
 * field is resolved without a Spring {@code Environment}.</p>
 *
 * <h2>Production class behavior reconciliation</h2>
 * <p>The agent prompt documents the listener contract per the AAP
 * specification: {@code onAccountUpdated} and {@code onLedgerBalanced}
 * were originally specified to call {@code logAuditEvent}. The
 * production class implementation in
 * {@code KafkaEventConsumer.java} delegates both of those events to
 * {@code logTransactionEvent} instead &mdash; the rationale documented in
 * that class is: <em>"account.updated events also belong to the
 * transaction-lifecycle audit class &mdash; they reflect a mutation
 * that affects financial state and therefore must share the
 * transaction audit index for regulator-friendly searchability."</em>
 * The tests below verify the actual production behavior (the
 * single source of truth) per the validation instructions in the
 * agent prompt ("If the production class doesn't match the documented
 * signature exactly... adapt the test to match the actual
 * signature").</p>
 *
 * <h2>PCI-DSS test data conventions (AAP &sect;0.6.6)</h2>
 * <ul>
 *   <li>Test PAN: {@code 4111111111111111} (the Visa published test
 *       PAN); never a real card number.</li>
 *   <li>Test SSN: a synthetic 9-digit value with no real-world
 *       owner.</li>
 *   <li>No real CVV / track-data values appear in tests.</li>
 * </ul>
 *
 * @see KafkaEventConsumer
 * @see StepFunctionsOrchestrator
 * @see AuditLogService
 */
@ExtendWith(MockitoExtension.class)
class KafkaEventConsumerTest {

    // =====================================================================
    // Test constants — Kafka header / topic values that mirror the
    // production property defaults defined on each @KafkaListener.
    // =====================================================================

    /**
     * State machine ARN used to verify the online-to-batch bridge routes
     * through {@link StepFunctionsOrchestrator#startExecution} with the
     * expected ARN (AAP &sect;0.1.1). The value follows the canonical
     * AWS ARN syntax {@code arn:aws:states:<region>:<account>:stateMachine:<name>}
     * with the LocalStack-style {@code 000000000000} account so this
     * exact literal could also be used in LocalStack-backed integration
     * tests without modification.
     */
    private static final String REPORT_PIPELINE_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:carddemo-report-pipeline";

    /**
     * Default consumer group ID expected on every {@code @KafkaListener}
     * annotation &mdash; matches the production default fallback declared
     * via {@code "${carddemo.kafka.consumer.group-id:carddemo-consumer-group}"}.
     */
    private static final String GROUP_ID = "carddemo-consumer-group";

    /**
     * Default {@code containerFactory} value expected on every
     * {@code @KafkaListener} annotation. This factory bean is the one
     * configured with {@code AckMode.MANUAL} &mdash; per AAP &sect;0.6.5,
     * the manual offset commit discipline depends on the listener being
     * wired to it.
     */
    private static final String CONTAINER_FACTORY = "kafkaListenerContainerFactory";

    /**
     * Zero-padded 11-digit account ID used as the partition-key header
     * value in the transaction / account / ledger event tests. The
     * zero-padded form mirrors what {@code KafkaEventPublisher} writes on
     * the producer side (AAP &sect;0.6.5 per-account ordering invariant).
     */
    private static final String ACCOUNT_ID_HEADER = "00000012345";

    /**
     * Numeric form of {@link #ACCOUNT_ID_HEADER}. The consumer parses the
     * zero-padded String key back to a {@link Long} for the
     * {@code accountId} parameter on
     * {@link AuditLogService#logTransactionEvent}.
     */
    private static final Long ACCOUNT_ID_NUMERIC = 12345L;

    /**
     * Visa test PAN per the PCI-DSS test fixture convention (AAP
     * &sect;0.6.6). Never a real card number; the
     * {@link TransactionAddDto#toString()} implementation masks every
     * PAN regardless, so this literal is safe to expose in test files.
     */
    private static final String TEST_PAN = "4111111111111111";

    // =====================================================================
    // Mocks — auto-initialised by MockitoExtension before each test.
    // =====================================================================

    /**
     * Mocked {@link StepFunctionsOrchestrator}. Verified across the
     * {@link KafkaEventConsumer#onReportRequested} happy-path,
     * blank-ARN, and Step-Functions-failure scenarios. Per AAP
     * &sect;0.3.3 (Adapter Pattern), this is the only collaborator that
     * may touch the AWS SDK &mdash; the consumer never invokes
     * {@link software.amazon.awssdk.services.sfn.SfnClient} directly.
     */
    @Mock
    private StepFunctionsOrchestrator stepFunctionsOrchestrator;

    /**
     * Mocked {@link AuditLogService}. Verified on every successful
     * listener invocation and stubbed to throw exceptions in the
     * ack-discipline scenarios. Per AAP &sect;0.6.6, this adapter
     * isolates the OpenSearch + CloudWatch wiring; the consumer never
     * invokes the OpenSearch / CloudWatch clients directly.
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * Mocked {@link Acknowledgment}. The manual offset commit handle.
     * Verified via {@code verify(ack).acknowledge()} (happy path) and
     * {@code verify(ack, never()).acknowledge()} (CardDemoException
     * retry path) per AAP &sect;0.6.5.
     */
    @Mock
    private Acknowledgment ack;

    /**
     * The class under test. Re-instantiated before every test in
     * {@link #beforeEach()} so the mocks are pristine and any field
     * mutated via {@link ReflectionTestUtils#setField} cannot leak
     * between tests.
     */
    private KafkaEventConsumer consumer;

    /**
     * Prepares a fresh consumer instance with pristine mocks and the
     * default-configured Step Functions ARN. Specific tests can override
     * the ARN with another {@link ReflectionTestUtils#setField} call
     * (see Test 8.3) without leaking the override to subsequent tests.
     */
    @BeforeEach
    void beforeEach() {
        consumer = new KafkaEventConsumer(stepFunctionsOrchestrator, auditLogService);
        ReflectionTestUtils.setField(consumer,
                "reportPipelineStateMachineArn", REPORT_PIPELINE_ARN);
    }

    // =====================================================================
    // Fixtures — DTO record constructors matching the production
    // canonical constructors verbatim. These exactly mirror the
    // KafkaEventPublisherTest sister-fixture set so the producer-side
    // and consumer-side tests exercise the same shape of data.
    // =====================================================================

    /**
     * Builds a fully-populated {@link TransactionAddDto} matching the
     * production record canonical constructor (14 components).
     *
     * @return a fresh {@link TransactionAddDto}
     */
    private TransactionAddDto sampleTxnAdd() {
        return new TransactionAddDto(
                "00000012345",                                    // accountId (11-digit String)
                TEST_PAN,                                         // cardNumber (Visa test PAN)
                "01",                                             // transactionType
                Integer.valueOf(5411),                            // transactionCategory
                "ONLINE",                                         // source
                "GROCERY STORE PURCHASE",                         // description
                new BigDecimal("123.45"),                         // amount
                LocalDateTime.of(2026, 5, 20, 12, 30, 0),         // originationTimestamp
                LocalDateTime.of(2026, 5, 20, 12, 30, 0),         // processingTimestamp
                Long.valueOf(100000001L),                         // merchantId
                "ACME GROCERY",                                   // merchantName
                "SEATTLE",                                        // merchantCity
                "98101",                                          // merchantZip
                "Y");                                             // confirm
    }

    /**
     * Builds a fully-populated {@link AccountUpdateDto} matching the
     * production record canonical constructor (31 components). The
     * fixture mirrors {@code KafkaEventPublisherTest.sampleAccountUpdate()}
     * for cross-test consistency.
     *
     * @return a fresh {@link AccountUpdateDto}
     */
    private AccountUpdateDto sampleAccountUpdate() {
        return new AccountUpdateDto(
                Long.valueOf(12345L),                             // accountId
                "Y",                                              // activeStatus
                new BigDecimal("1234.56"),                        // currentBalance
                new BigDecimal("5000.00"),                        // creditLimit
                new BigDecimal("1000.00"),                        // cashCreditLimit
                LocalDate.of(2020, 1, 15),                        // openDate
                LocalDate.of(2030, 1, 15),                        // expirationDate
                LocalDate.of(2025, 1, 15),                        // reissueDate
                new BigDecimal("500.00"),                         // currentCycleCredit
                new BigDecimal("200.00"),                         // currentCycleDebit
                "12345",                                          // addressZip
                "DEFAULT",                                        // accountGroupId
                Long.valueOf(100000001L),                         // customerId
                "John",                                           // firstName
                "M",                                              // middleName
                "Doe",                                            // lastName
                Long.valueOf(123456789L),                         // customerSsn (synthetic)
                "2125551234",                                     // phoneNumber1
                null,                                             // phoneNumber2 (optional)
                "123 Main Street",                                // addressLine1
                null,                                             // addressLine2 (optional)
                null,                                             // addressLine3 (optional)
                "NY",                                             // stateCode
                "USA",                                            // countryCode
                "12345",                                          // zipCode
                LocalDate.of(1980, 5, 15),                        // dateOfBirth
                null,                                             // governmentIssuedId (optional)
                null,                                             // eftAccountId (optional)
                null,                                             // primaryCardHolderIndicator (optional)
                Integer.valueOf(720),                             // ficoCreditScore
                Long.valueOf(1L));                                // version
    }

    /**
     * Builds a fully-populated {@link ReportRequestDto}. Uses the
     * {@code MONTHLY} report-type selector and a one-month range so
     * the test exercises the typical inbound shape without depending on
     * the report-content schema.
     *
     * @return a fresh {@link ReportRequestDto}
     */
    private ReportRequestDto sampleReportRequest() {
        return new ReportRequestDto(
                "MONTHLY",                                        // reportType
                LocalDate.of(2025, 5, 1),                         // startDate
                LocalDate.of(2025, 5, 31),                        // endDate
                "Y");                                             // confirm
    }

    // =====================================================================
    // Phase 5 — onTransactionPosted listener tests
    //
    // Replaces: downstream consumers of WRITE TRAN-RECORD in CBTRN02C
    //           (paragraph 2900-WRITE-TRANSACTION-FILE), COBIL00C
    //           (paragraph WRITE-TRANSACT-FILE), and COTRN02C.
    // Backs: AAP §0.6.5 (manual offset commit), §0.6.6 (audit emission).
    // =====================================================================

    @Test
    @DisplayName("onTransactionPosted happy path acknowledges offset and invokes audit logging")
    void onTransactionPosted_happyPath_invokesAuditLogAndAcknowledges() {
        // GIVEN a fully-populated transaction event and pristine mocks
        TransactionAddDto event = sampleTxnAdd();

        // WHEN the listener is invoked with the synthesized header values
        consumer.onTransactionPosted(event, ACCOUNT_ID_HEADER, "transaction.posted",
                5, 100L, ack);

        // THEN AuditLogService.logTransactionEvent is called with the
        // expected event-type ("TRANSACTION_POSTED") and parsed account ID
        // (12345L from the zero-padded String key) — AAP §0.6.5 / §0.6.6
        verify(auditLogService).logTransactionEvent(
                eq((String) null),                                    // transactionId — none on raw post
                eq(ACCOUNT_ID_NUMERIC),                               // parsed Long from header
                eq("KAFKA_CONSUMER"),                                 // operatorCode
                eq("TRANSACTION_POSTED"),                             // event_type discriminator
                eq((String) null),                                    // reasonCode — null on success
                anyMap(),                                             // audit payload (assertable separately)
                eq((String) null));                                   // correlationId — null on this path

        // THEN the offset is committed exactly once (AAP §0.6.5 happy path)
        verify(ack).acknowledge();

        // THEN the Step Functions orchestrator is NOT touched —
        // transaction.posted does not invoke any state machine
        verifyNoInteractions(stepFunctionsOrchestrator);
    }

    @Test
    @DisplayName("onTransactionPosted on CardDemoException does NOT acknowledge — message retried")
    void onTransactionPosted_whenAuditLogThrowsCardDemoException_doesNotAcknowledge() {
        // GIVEN a transaction event and an AuditLogService that fails with
        // a recoverable, COBOL-reason-coded domain error
        TransactionAddDto event = sampleTxnAdd();
        doThrow(new CardDemoException("TRANSIENT", "transient audit failure"))
                .when(auditLogService).logTransactionEvent(
                        any(), anyLong(), anyString(), anyString(), any(), anyMap(), any());

        // WHEN the listener is invoked — the CardDemoException must propagate
        // out (so the DefaultErrorHandler can retry / route to DLT)
        CardDemoException thrown = assertThrows(CardDemoException.class,
                () -> consumer.onTransactionPosted(event, ACCOUNT_ID_HEADER,
                        "transaction.posted", 5, 100L, ack));

        // THEN the reason code is preserved verbatim per AAP §0.7.2
        assertEquals("TRANSIENT", thrown.getReasonCode(),
                "CardDemoException reasonCode must propagate verbatim");

        // THEN the offset is NOT committed — AAP §0.6.5: at-least-once
        // semantics depend on the offset staying uncommitted so Kafka
        // redelivers the record on the next poll
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("onTransactionPosted on poison message (NPE) acknowledges to skip past poison")
    void onTransactionPosted_whenNonRetriableExceptionThrown_acknowledgesToSkipPoison() {
        // GIVEN a transaction event and an AuditLogService that fails with
        // an unrecoverable poison-message error (NullPointerException is
        // representative of any non-CardDemoException RuntimeException)
        TransactionAddDto event = sampleTxnAdd();
        doThrow(new NullPointerException("poison"))
                .when(auditLogService).logTransactionEvent(
                        any(), anyLong(), anyString(), anyString(), any(), anyMap(), any());

        // WHEN the listener is invoked — the exception must NOT propagate;
        // the consumer's catch-all branch acknowledges to skip the poison
        consumer.onTransactionPosted(event, ACCOUNT_ID_HEADER,
                "transaction.posted", 5, 100L, ack);

        // THEN the offset IS committed — AAP §0.6.5: poison messages must
        // NOT stall the consumer group; the ERROR log entry is the
        // permanent forensic record
        verify(ack).acknowledge();
    }

    // =====================================================================
    // Phase 6 — onAccountUpdated listener tests
    //
    // Replaces: downstream consumers of REWRITE ACCT-RECORD in COACTUPC
    //           (paragraph 9700-REWRITE-ACCTDAT-FILE — the only source
    //           program with SYNCPOINT ROLLBACK), COBIL00C
    //           (UPDATE-ACCTDAT-FILE), and CBACT04C.
    // Backs: AAP §0.6.5 (manual offset commit), §0.6.6 (audit emission).
    //
    // The production class delegates to logTransactionEvent (NOT
    // logAuditEvent) per its inline rationale — account.updated events
    // belong to the transaction-lifecycle audit class and must share
    // the transaction audit index for regulator-friendly searchability.
    // =====================================================================

    @Test
    @DisplayName("onAccountUpdated happy path acknowledges offset and invokes audit logging")
    void onAccountUpdated_happyPath_invokesAuditLogAndAcknowledges() {
        // GIVEN a fully-populated account-update event
        AccountUpdateDto event = sampleAccountUpdate();

        // WHEN the listener is invoked
        consumer.onAccountUpdated(event, ACCOUNT_ID_HEADER, "account.updated",
                3, 50L, ack);

        // THEN AuditLogService.logTransactionEvent is called with the
        // expected event-type ("ACCOUNT_UPDATED") and the entity's
        // own accountId (preferred over the header per the production
        // helper extractAccountIdLong)
        verify(auditLogService).logTransactionEvent(
                eq((String) null),                                    // transactionId — none on raw update
                eq(Long.valueOf(12345L)),                             // entity Long ID has priority over header
                eq("KAFKA_CONSUMER"),                                 // operatorCode
                eq("ACCOUNT_UPDATED"),                                // event_type discriminator
                eq((String) null),                                    // reasonCode — null on success
                anyMap(),                                             // audit payload
                eq((String) null));                                   // correlationId — null on this path

        // THEN the offset is committed exactly once
        verify(ack).acknowledge();

        // THEN the Step Functions orchestrator is NOT touched
        verifyNoInteractions(stepFunctionsOrchestrator);
    }

    @Test
    @DisplayName("onAccountUpdated on CardDemoException does NOT acknowledge — message retried")
    void onAccountUpdated_whenCardDemoExceptionThrown_doesNotAcknowledge() {
        // GIVEN an account-update event and an audit service that fails
        AccountUpdateDto event = sampleAccountUpdate();
        doThrow(new CardDemoException("CONFLICT", "optimistic-lock conflict"))
                .when(auditLogService).logTransactionEvent(
                        any(), anyLong(), anyString(), anyString(), any(), anyMap(), any());

        // WHEN the listener is invoked — CardDemoException propagates
        CardDemoException thrown = assertThrows(CardDemoException.class,
                () -> consumer.onAccountUpdated(event, ACCOUNT_ID_HEADER,
                        "account.updated", 3, 50L, ack));

        // THEN the reason code is preserved verbatim per AAP §0.7.2
        assertEquals("CONFLICT", thrown.getReasonCode());

        // THEN the offset is NOT committed (retry semantics)
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("onAccountUpdated on poison message acknowledges to skip past poison")
    void onAccountUpdated_whenNonRetriableExceptionThrown_acknowledgesToSkipPoison() {
        // GIVEN an account-update event and an unrecoverable runtime error
        AccountUpdateDto event = sampleAccountUpdate();
        doThrow(new IllegalStateException("poison account"))
                .when(auditLogService).logTransactionEvent(
                        any(), anyLong(), anyString(), anyString(), any(), anyMap(), any());

        // WHEN the listener is invoked — the exception is swallowed
        consumer.onAccountUpdated(event, ACCOUNT_ID_HEADER,
                "account.updated", 3, 50L, ack);

        // THEN the offset IS committed (poison-message skip)
        verify(ack).acknowledge();
    }

    // =====================================================================
    // Phase 7 — onLedgerBalanced listener tests
    //
    // Replaces: end-of-day reconciliation SYSPRINT trailers and
    //           RETURN-CODE values from the POSTTRAN → INTCALC →
    //           COMBTRAN → CREASTMT/TRANREPT JCL chain (AAP §0.6.3).
    //           No native COBOL record-layout equivalent — payload is
    //           generic Object (typically a Map).
    // Backs: AAP §0.6.5 (manual offset commit), §0.6.6 (audit emission).
    // =====================================================================

    @Test
    @DisplayName("onLedgerBalanced happy path acknowledges offset and invokes audit logging")
    void onLedgerBalanced_happyPath_invokesAuditLogAndAcknowledges() {
        // GIVEN a generic ledger-balanced event (Map representation, since
        // the production signature accepts Object)
        Map<String, Object> event = new HashMap<>();
        event.put("ledger_id", "L-001");
        event.put("balance", "0.00");

        // WHEN the listener is invoked
        consumer.onLedgerBalanced(event, ACCOUNT_ID_HEADER, "ledger.balanced",
                2, 25L, ack);

        // THEN AuditLogService.logTransactionEvent is called with the
        // expected event-type ("LEDGER_BALANCED") and the parsed account ID
        verify(auditLogService).logTransactionEvent(
                eq((String) null),                                    // transactionId — none on ledger.balanced
                eq(ACCOUNT_ID_NUMERIC),                               // parsed Long from header
                eq("KAFKA_CONSUMER"),                                 // operatorCode
                eq("LEDGER_BALANCED"),                                // event_type discriminator
                eq((String) null),                                    // reasonCode — null on success
                anyMap(),                                             // audit payload
                eq((String) null));                                   // correlationId

        // THEN the offset is committed exactly once
        verify(ack).acknowledge();

        // THEN the Step Functions orchestrator is NOT touched
        verifyNoInteractions(stepFunctionsOrchestrator);
    }

    @Test
    @DisplayName("onLedgerBalanced on CardDemoException does NOT acknowledge — message retried")
    void onLedgerBalanced_whenCardDemoExceptionThrown_doesNotAcknowledge() {
        // GIVEN a ledger-balanced event and a failing audit service
        Map<String, Object> event = Map.of("ledger_id", "L-001",
                "balance", "0.00");
        doThrow(new CardDemoException("RECONCILIATION", "reconciliation failure"))
                .when(auditLogService).logTransactionEvent(
                        any(), anyLong(), anyString(), anyString(), any(), anyMap(), any());

        // WHEN the listener is invoked — CardDemoException propagates
        CardDemoException thrown = assertThrows(CardDemoException.class,
                () -> consumer.onLedgerBalanced(event, ACCOUNT_ID_HEADER,
                        "ledger.balanced", 2, 25L, ack));

        // THEN the reason code is preserved verbatim per AAP §0.7.2
        assertEquals("RECONCILIATION", thrown.getReasonCode());

        // THEN the offset is NOT committed (retry semantics)
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("onLedgerBalanced on poison message acknowledges to skip past poison")
    void onLedgerBalanced_whenNonRetriableExceptionThrown_acknowledgesToSkipPoison() {
        // GIVEN a ledger-balanced event and an unrecoverable runtime error
        Map<String, Object> event = Map.of("ledger_id", "L-001");
        doThrow(new RuntimeException("poison ledger"))
                .when(auditLogService).logTransactionEvent(
                        any(), anyLong(), anyString(), anyString(), any(), anyMap(), any());

        // WHEN the listener is invoked — the exception is swallowed
        consumer.onLedgerBalanced(event, ACCOUNT_ID_HEADER,
                "ledger.balanced", 2, 25L, ack);

        // THEN the offset IS committed (poison-message skip)
        verify(ack).acknowledge();
    }

    // =====================================================================
    // Phase 8 — onReportRequested listener tests (THE online-to-batch
    // bridge, AAP §0.1.1)
    //
    // Replaces: CORPT00C WIRTE-JOBSUB-TDQ → EXEC CICS WRITEQ TD
    //           QUEUE('JOBS') → JES2 submission. The sole online-to-batch
    //           bridge in the source mainframe environment.
    // Backs: AAP §0.6.5 (manual offset commit), §0.1.1 (online-to-batch
    //        bridge), §0.6.6 (audit emission).
    // =====================================================================

    @Test
    @DisplayName("onReportRequested invokes Step Functions and acknowledges (online-to-batch bridge)")
    void onReportRequested_happyPath_invokesStepFunctionsAndAcknowledges() {
        // GIVEN a report-request event and a Step Functions stub returning
        // a synthetic execution ARN
        ReportRequestDto event = sampleReportRequest();
        when(stepFunctionsOrchestrator.startExecution(anyString(), anyString()))
                .thenReturn("arn:aws:states:us-east-1:000000000000:execution:carddemo-report-pipeline:exec-001");

        // WHEN the listener is invoked
        consumer.onReportRequested(event, "RPT-001", "report.requested",
                1, 200L, ack);

        // THEN the online-to-batch bridge is invoked with the configured
        // state-machine ARN — this is the CRITICAL assertion per AAP §0.1.1
        verify(stepFunctionsOrchestrator).startExecution(eq(REPORT_PIPELINE_ARN), anyString());

        // THEN the inbound-side audit record is emitted (with the
        // "REPORT_REQUESTED_RECEIVED" event-type discriminator and
        // the "REPORT" resource type)
        verify(auditLogService).logAuditEvent(
                eq("REPORT_REQUESTED_RECEIVED"),
                eq("REPORT"),
                eq("RPT-001"),                                        // resourceId — partition key
                eq("KAFKA_CONSUMER"),                                 // operatorCode
                anyMap(),                                             // audit payload
                eq((String) null));                                   // correlationId

        // THEN the started-execution audit record is also emitted
        verify(auditLogService).logAuditEvent(
                eq("REPORT_REQUESTED_STARTED"),
                eq("REPORT"),
                eq("RPT-001"),
                eq("KAFKA_CONSUMER"),
                anyMap(),
                eq((String) null));

        // THEN the offset is committed exactly once
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("onReportRequested captures the serialised JSON input for Step Functions")
    void onReportRequested_capturesReportInputForStepFunctions() {
        // GIVEN a report-request event and a stubbed Step Functions ARN
        ReportRequestDto event = sampleReportRequest();
        when(stepFunctionsOrchestrator.startExecution(anyString(), anyString()))
                .thenReturn("execution-arn-001");

        // WHEN the listener is invoked
        consumer.onReportRequested(event, "RPT-001", "report.requested",
                1, 200L, ack);

        // THEN the captured JSON input is non-null, non-blank, and carries
        // the inbound report-type field for downstream Step Functions
        // consumption
        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(stepFunctionsOrchestrator).startExecution(eq(REPORT_PIPELINE_ARN),
                inputCaptor.capture());

        String capturedInput = inputCaptor.getValue();
        assertNotNull(capturedInput, "Step Functions input must not be null");
        assertFalse(capturedInput.isBlank(),
                "Step Functions input must not be blank");
        assertTrue(capturedInput.contains("\"reportType\":\"MONTHLY\""),
                "Step Functions input should contain reportType=MONTHLY for "
                        + "downstream Choice states; actual: " + capturedInput);
    }

    @Test
    @DisplayName("onReportRequested with blank ARN skips Step Functions but still acks and audits")
    void onReportRequested_whenReportPipelineArnIsBlank_skipsStepFunctionsButStillAcksAndAudits() {
        // GIVEN a consumer with a BLANK ARN (typical local-profile state
        // when Step Functions are not provisioned)
        ReflectionTestUtils.setField(consumer,
                "reportPipelineStateMachineArn", "");
        ReportRequestDto event = sampleReportRequest();

        // WHEN the listener is invoked
        consumer.onReportRequested(event, "RPT-001", "report.requested",
                1, 200L, ack);

        // THEN the Step Functions orchestrator is NOT invoked — graceful
        // degradation per AAP §0.1.1
        verify(stepFunctionsOrchestrator, never()).startExecution(anyString(), anyString());

        // THEN the inbound audit record IS still emitted (the receipt
        // is the permanent forensic record in the local profile)
        verify(auditLogService).logAuditEvent(
                eq("REPORT_REQUESTED_RECEIVED"),
                eq("REPORT"),
                eq("RPT-001"),
                eq("KAFKA_CONSUMER"),
                anyMap(),
                eq((String) null));

        // THEN the started-execution audit is NOT emitted (no execution
        // was started)
        verify(auditLogService, never()).logAuditEvent(
                eq("REPORT_REQUESTED_STARTED"),
                anyString(), any(), any(), anyMap(), any());

        // THEN the offset is committed exactly once
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("onReportRequested with whitespace-only ARN skips Step Functions (treated as blank)")
    void onReportRequested_whenReportPipelineArnIsWhitespace_skipsStepFunctions() {
        // GIVEN a consumer with a whitespace-only ARN (defensive — the
        // production code calls trim() before the blank check)
        ReflectionTestUtils.setField(consumer,
                "reportPipelineStateMachineArn", "   \t  ");
        ReportRequestDto event = sampleReportRequest();

        // WHEN the listener is invoked
        consumer.onReportRequested(event, "RPT-001", "report.requested",
                1, 200L, ack);

        // THEN the Step Functions orchestrator is NOT invoked
        verify(stepFunctionsOrchestrator, never()).startExecution(anyString(), anyString());

        // THEN the offset is still committed
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("onReportRequested when Step Functions throws CardDemoException does NOT ack")
    void onReportRequested_whenStepFunctionsThrowsCardDemoException_doesNotAcknowledge() {
        // GIVEN a report-request event and a Step Functions stub that
        // throws a recoverable, COBOL-reason-coded transient failure
        ReportRequestDto event = sampleReportRequest();
        when(stepFunctionsOrchestrator.startExecution(anyString(), anyString()))
                .thenThrow(new CardDemoException("SFN_ERROR",
                        "Step Functions invocation failed"));

        // WHEN the listener is invoked — CardDemoException propagates
        CardDemoException thrown = assertThrows(CardDemoException.class,
                () -> consumer.onReportRequested(event, "RPT-001",
                        "report.requested", 1, 200L, ack));

        // THEN the reason code is preserved verbatim per AAP §0.7.2
        assertEquals("SFN_ERROR", thrown.getReasonCode());

        // THEN the offset is NOT committed — report requests must be
        // retried on transient Step Functions failures (AAP §0.6.5)
        verify(ack, never()).acknowledge();
    }

    @Test
    @DisplayName("onReportRequested when Step Functions throws RuntimeException acks to skip poison")
    void onReportRequested_whenStepFunctionsThrowsRuntimeException_acknowledgesToSkipPoison() {
        // GIVEN a report-request event and a Step Functions stub that
        // throws an unrecoverable runtime error
        ReportRequestDto event = sampleReportRequest();
        when(stepFunctionsOrchestrator.startExecution(anyString(), anyString()))
                .thenThrow(new IllegalStateException("poison SFN call"));

        // WHEN the listener is invoked — the exception is swallowed
        consumer.onReportRequested(event, "RPT-001",
                "report.requested", 1, 200L, ack);

        // THEN the offset IS committed (poison-message skip per AAP §0.6.5)
        verify(ack).acknowledge();
    }

    // =====================================================================
    // Phase 9 — Header parameter routing tests
    //
    // Verify that the @Header-derived Kafka metadata (topic, partition,
    // offset, raw key) is forwarded into the audit payload for full
    // end-to-end traceability — required by AAP §0.6.6.
    // =====================================================================

    @Test
    @DisplayName("onTransactionPosted forwards topic, partition, offset into the audit payload")
    void onTransactionPosted_recordedTopicAndPartitionAndOffsetForwardedInAuditPayload() {
        // GIVEN a transaction event and recognisable header values that
        // can be uniquely identified inside the captured payload
        TransactionAddDto event = sampleTxnAdd();

        // WHEN the listener is invoked with those header values
        consumer.onTransactionPosted(event, ACCOUNT_ID_HEADER,
                "transaction.posted", 7, 999L, ack);

        // THEN the captured audit payload carries the topic/partition/offset
        // fields for traceability per AAP §0.6.6 (audit search by Kafka
        // metadata must be possible)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                (ArgumentCaptor<Map<String, Object>>) (ArgumentCaptor<?>)
                        ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).logTransactionEvent(
                any(), anyLong(), anyString(), anyString(), any(),
                payloadCaptor.capture(), any());

        Map<String, Object> capturedPayload = payloadCaptor.getValue();
        assertNotNull(capturedPayload, "audit payload must not be null");
        assertAll("Kafka metadata embedded in audit payload for traceability",
                () -> assertEquals("transaction.posted", capturedPayload.get("kafka_topic"),
                        "kafka_topic field"),
                () -> assertEquals(7, capturedPayload.get("kafka_partition"),
                        "kafka_partition field"),
                () -> assertEquals(999L, capturedPayload.get("kafka_offset"),
                        "kafka_offset field"),
                () -> assertEquals(ACCOUNT_ID_HEADER, capturedPayload.get("kafka_key"),
                        "kafka_key field (raw zero-padded partition key)"));

        // The audit payload should also carry the event-specific fields
        // for downstream regulator queries
        assertEquals("01", capturedPayload.get("transaction_type"),
                "transaction_type from inbound event");
        assertEquals(Integer.valueOf(5411), capturedPayload.get("transaction_category"),
                "transaction_category from inbound event");
        assertEquals(new BigDecimal("123.45"), capturedPayload.get("amount"),
                "amount from inbound event (BigDecimal preserved per AAP §0.6.1)");
    }

    // =====================================================================
    // Phase 10 — Account-ID-from-header verification
    //
    // Verify the consumer parses the zero-padded String partition key
    // back to a Long for AuditLogService.logTransactionEvent's accountId
    // parameter — AAP §0.6.5 (per-account ordering invariant on key).
    // =====================================================================

    @Test
    @DisplayName("onTransactionPosted parses zero-padded account-ID header into Long for audit")
    void onTransactionPosted_accountIdHeaderUsedAsCorrelationOrPayloadField() {
        // GIVEN a transaction event with a zero-padded 11-digit account
        // key (the canonical form emitted by KafkaEventPublisher)
        TransactionAddDto event = sampleTxnAdd();

        // WHEN the listener is invoked
        consumer.onTransactionPosted(event, ACCOUNT_ID_HEADER,
                "transaction.posted", 5, 100L, ack);

        // THEN the audit call carries the PARSED Long value (12345L) for
        // its accountId parameter — AAP §0.6.5 / §0.6.6
        ArgumentCaptor<Long> accountIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(auditLogService).logTransactionEvent(
                any(), accountIdCaptor.capture(), anyString(), anyString(),
                any(), anyMap(), any());

        assertEquals(ACCOUNT_ID_NUMERIC, accountIdCaptor.getValue(),
                "Zero-padded String header must parse to Long 12345");
    }

    @Test
    @DisplayName("onLedgerBalanced with null account-ID header forwards null accountId to audit")
    void onLedgerBalanced_withNullAccountIdHeader_forwardsNullToAudit() {
        // GIVEN a ledger.balanced event with NO partition key (system-wide
        // reconciliation events may carry no account context per the
        // consumer's class JavaDoc)
        Map<String, Object> event = Map.of("ledger_id", "L-001");

        // WHEN the listener is invoked with a null account-ID header
        consumer.onLedgerBalanced(event, null, "ledger.balanced",
                2, 25L, ack);

        // THEN the audit call still emits (the consumer must not fail on
        // a null key) and forwards a null accountId
        ArgumentCaptor<Long> accountIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(auditLogService).logTransactionEvent(
                any(), accountIdCaptor.capture(), anyString(), anyString(),
                any(), anyMap(), any());

        assertEquals(null, accountIdCaptor.getValue(),
                "null partition key must surface as null accountId in audit "
                        + "(per parseAccountId helper contract)");

        // THEN the offset is committed normally
        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("onTransactionPosted with non-numeric account-ID header forwards null accountId")
    void onTransactionPosted_withNonNumericAccountIdHeader_forwardsNullToAudit() {
        // GIVEN a transaction event with a non-numeric partition key
        // (defensive — the consumer must not throw if the header is not
        // a valid Long; the raw key is preserved in the payload map)
        TransactionAddDto event = sampleTxnAdd();

        // WHEN the listener is invoked with a non-numeric header
        consumer.onTransactionPosted(event, "NOT-A-NUMBER",
                "transaction.posted", 5, 100L, ack);

        // THEN the audit call surfaces a null accountId rather than failing
        ArgumentCaptor<Long> accountIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(auditLogService).logTransactionEvent(
                any(), accountIdCaptor.capture(), anyString(), anyString(),
                any(), anyMap(), any());

        assertEquals(null, accountIdCaptor.getValue(),
                "Non-numeric header must surface as null accountId");

        // THEN the offset is committed normally
        verify(ack).acknowledge();
    }

    // =====================================================================
    // Phase 11 — Listener annotation verification (via reflection)
    //
    // Verify that each @KafkaListener method carries the property
    // placeholders for topic, groupId, and containerFactory required by
    // AAP §0.4.1 (consumer wiring contract) and §0.6.5 (manual ack
    // requires the kafkaListenerContainerFactory container with
    // AckMode.MANUAL).
    // =====================================================================

    @ParameterizedTest(name = "{0} carries @KafkaListener with the expected topic, "
            + "groupId, containerFactory")
    @ValueSource(strings = {
            "onTransactionPosted",
            "onAccountUpdated",
            "onLedgerBalanced",
            "onReportRequested"})
    @DisplayName("All listener methods carry @KafkaListener with the expected topic, "
            + "groupId, containerFactory")
    void listenerMethodCarriesExpectedKafkaListenerAnnotation(String methodName) {
        // GIVEN one of the four documented listener methods
        Method method = findListenerMethod(methodName);
        KafkaListener annotation = method.getAnnotation(KafkaListener.class);
        assertNotNull(annotation,
                methodName + " must be annotated with @KafkaListener");

        // THEN the topics array contains the expected property placeholder
        // with the canonical default
        String expectedTopicPlaceholder = expectedTopicPlaceholder(methodName);
        String[] topics = annotation.topics();
        assertNotNull(topics, methodName + " @KafkaListener.topics() must not be null");
        assertTrue(Arrays.asList(topics).contains(expectedTopicPlaceholder),
                methodName + " topics[] must contain " + expectedTopicPlaceholder
                        + " — actual: " + Arrays.toString(topics));

        // THEN the groupId placeholder defaults to "carddemo-consumer-group"
        assertTrue(annotation.groupId().contains(GROUP_ID),
                methodName + " @KafkaListener.groupId() must contain '"
                        + GROUP_ID + "' as the default fallback — actual: "
                        + annotation.groupId());

        // THEN the containerFactory is the AckMode.MANUAL factory bean
        assertEquals(CONTAINER_FACTORY, annotation.containerFactory(),
                methodName + " must use the manual-ack container factory");
    }

    @Test
    @DisplayName("onTransactionPosted listener targets the transaction.posted topic placeholder")
    void onTransactionPosted_isAnnotatedWithKafkaListenerForTransactionPostedTopic() {
        Method method = findListenerMethod("onTransactionPosted");
        KafkaListener annotation = method.getAnnotation(KafkaListener.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.topics().length,
                "onTransactionPosted should declare exactly one topic placeholder");
        assertEquals("${carddemo.kafka.topics.transaction-posted:transaction.posted}",
                annotation.topics()[0]);
        assertEquals(CONTAINER_FACTORY, annotation.containerFactory());
    }

    @Test
    @DisplayName("onAccountUpdated listener targets the account.updated topic placeholder")
    void onAccountUpdated_isAnnotatedWithKafkaListenerForAccountUpdatedTopic() {
        Method method = findListenerMethod("onAccountUpdated");
        KafkaListener annotation = method.getAnnotation(KafkaListener.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.topics().length,
                "onAccountUpdated should declare exactly one topic placeholder");
        assertEquals("${carddemo.kafka.topics.account-updated:account.updated}",
                annotation.topics()[0]);
        assertEquals(CONTAINER_FACTORY, annotation.containerFactory());
    }

    @Test
    @DisplayName("onLedgerBalanced listener targets the ledger.balanced topic placeholder")
    void onLedgerBalanced_isAnnotatedWithKafkaListenerForLedgerBalancedTopic() {
        Method method = findListenerMethod("onLedgerBalanced");
        KafkaListener annotation = method.getAnnotation(KafkaListener.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.topics().length,
                "onLedgerBalanced should declare exactly one topic placeholder");
        assertEquals("${carddemo.kafka.topics.ledger-balanced:ledger.balanced}",
                annotation.topics()[0]);
        assertEquals(CONTAINER_FACTORY, annotation.containerFactory());
    }

    @Test
    @DisplayName("onReportRequested listener targets the report.requested topic placeholder")
    void onReportRequested_isAnnotatedWithKafkaListenerForReportRequestedTopic() {
        Method method = findListenerMethod("onReportRequested");
        KafkaListener annotation = method.getAnnotation(KafkaListener.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.topics().length,
                "onReportRequested should declare exactly one topic placeholder");
        assertEquals("${carddemo.kafka.topics.report-requested:report.requested}",
                annotation.topics()[0]);
        assertEquals(CONTAINER_FACTORY, annotation.containerFactory());
    }

    @Test
    @DisplayName("Class is annotated with @Component (NOT @Service)")
    void classIsAnnotatedWithComponent() {
        // THEN the KafkaEventConsumer class is annotated with @Component
        // (the Spring stereotype conventionally used for Kafka listener
        // containers so the @EnableKafka machinery discovers and
        // registers them with the kafkaListenerContainerFactory bean).
        assertTrue(KafkaEventConsumer.class.isAnnotationPresent(Component.class),
                "KafkaEventConsumer must be annotated with @Component to be "
                        + "discovered by the @EnableKafka listener "
                        + "infrastructure and registered with the manual-ack "
                        + "container factory");

        // The class MUST NOT carry @Service — that stereotype is reserved
        // for business-logic service beans per the layered architecture
        // contract (Controller → Service → Repository per AAP §0.3.3); a
        // Kafka listener container is conventionally @Component because
        // it is an infrastructure adapter, not a business service.
        assertFalse(KafkaEventConsumer.class.isAnnotationPresent(
                        org.springframework.stereotype.Service.class),
                "KafkaEventConsumer must NOT be annotated with @Service — "
                        + "it is an infrastructure adapter, not a business "
                        + "service");
    }

    // =====================================================================
    // Phase 12 — Manual offset commit discipline (consolidated sweep)
    //
    // A Nested test class consolidates the ack-discipline invariant
    // across all four listeners — required by AAP §0.6.5 (manual offset
    // commit, idempotent consumer).
    // =====================================================================

    @Nested
    @DisplayName("Manual offset commit discipline (AAP §0.6.5)")
    class ManualOffsetCommitDisciplineSweep {

        /**
         * Consolidates the three discipline rules per listener:
         * <ol>
         *   <li>Happy path &rarr; ack exactly once.</li>
         *   <li>{@link CardDemoException} (recoverable) &rarr; propagate
         *       and DO NOT ack.</li>
         *   <li>Other {@link RuntimeException} (poison) &rarr; swallow
         *       and ack.</li>
         * </ol>
         */
        @Test
        @DisplayName("All listeners ack on success only — CardDemoException leaves offset uncommitted")
        void allListenersAckOnSuccessOnly() {
            // Happy path: onTransactionPosted
            TransactionAddDto txEvent = sampleTxnAdd();
            consumer.onTransactionPosted(txEvent, ACCOUNT_ID_HEADER,
                    "transaction.posted", 5, 100L, ack);
            verify(ack, times(1)).acknowledge();

            // Happy path: onAccountUpdated
            AccountUpdateDto acctEvent = sampleAccountUpdate();
            consumer.onAccountUpdated(acctEvent, ACCOUNT_ID_HEADER,
                    "account.updated", 3, 50L, ack);
            verify(ack, times(2)).acknowledge();

            // Happy path: onLedgerBalanced
            Map<String, Object> ledgerEvent = Map.of("ledger_id", "L-001");
            consumer.onLedgerBalanced(ledgerEvent, ACCOUNT_ID_HEADER,
                    "ledger.balanced", 2, 25L, ack);
            verify(ack, times(3)).acknowledge();

            // Happy path: onReportRequested (with mock returning an
            // execution ARN)
            when(stepFunctionsOrchestrator.startExecution(anyString(), anyString()))
                    .thenReturn("execution-arn-001");
            ReportRequestDto reportEvent = sampleReportRequest();
            consumer.onReportRequested(reportEvent, "RPT-001",
                    "report.requested", 1, 200L, ack);
            verify(ack, times(4)).acknowledge();

            // THEN AuditLogService is invoked at least once for each of
            // the four happy paths (logTransactionEvent for tx/account/
            // ledger; logAuditEvent twice for report)
            verify(auditLogService, times(3)).logTransactionEvent(
                    any(), any(), anyString(), anyString(), any(), anyMap(), any());
            verify(auditLogService, times(2)).logAuditEvent(
                    anyString(), anyString(), any(), anyString(), anyMap(), any());
        }

        @Test
        @DisplayName("CardDemoException on every listener leaves the offset uncommitted")
        void cardDemoExceptionOnEveryListenerLeavesOffsetUncommitted() {
            // Set up the audit service to throw CardDemoException for
            // every transaction/audit logTransactionEvent invocation
            doThrow(new CardDemoException("R1", "audit retry"))
                    .when(auditLogService).logTransactionEvent(
                            any(), any(), anyString(), anyString(), any(),
                            anyMap(), any());

            TransactionAddDto txEvent = sampleTxnAdd();
            CardDemoException ex1 = assertThrows(CardDemoException.class,
                    () -> consumer.onTransactionPosted(txEvent, ACCOUNT_ID_HEADER,
                            "transaction.posted", 5, 100L, ack));
            assertEquals("R1", ex1.getReasonCode());

            AccountUpdateDto acctEvent = sampleAccountUpdate();
            CardDemoException ex2 = assertThrows(CardDemoException.class,
                    () -> consumer.onAccountUpdated(acctEvent, ACCOUNT_ID_HEADER,
                            "account.updated", 3, 50L, ack));
            assertEquals("R1", ex2.getReasonCode());

            Map<String, Object> ledgerEvent = Map.of("ledger_id", "L-001");
            CardDemoException ex3 = assertThrows(CardDemoException.class,
                    () -> consumer.onLedgerBalanced(ledgerEvent, ACCOUNT_ID_HEADER,
                            "ledger.balanced", 2, 25L, ack));
            assertEquals("R1", ex3.getReasonCode());

            // THEN ack was never called on any of the three listeners
            verify(ack, never()).acknowledge();
        }
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    /**
     * Locates the listener method by name using reflection. The four
     * listener methods share a six-parameter shape but the {@code @Payload}
     * type differs, so name-based lookup is the simplest precise
     * resolution. Fails the calling test with a clear message if the
     * method has been renamed in the production class.
     *
     * @param methodName the name of the listener method
     *                   ({@code onTransactionPosted},
     *                   {@code onAccountUpdated},
     *                   {@code onLedgerBalanced},
     *                   {@code onReportRequested})
     * @return the resolved {@link Method}; never {@code null}
     * @throws AssertionError if the method is not declared on
     *                        {@link KafkaEventConsumer}
     */
    private static Method findListenerMethod(String methodName) {
        return Arrays.stream(KafkaEventConsumer.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "KafkaEventConsumer must declare a method named '"
                                + methodName + "' annotated with "
                                + "@KafkaListener"));
    }

    /**
     * Returns the expected Spring property placeholder for a given
     * listener method's {@code topics[]}. Mirrors the production
     * placeholders verbatim so any divergence is caught by the
     * parameterized annotation test.
     *
     * @param methodName the listener method name
     * @return the expected placeholder
     */
    private static String expectedTopicPlaceholder(String methodName) {
        switch (methodName) {
            case "onTransactionPosted":
                return "${carddemo.kafka.topics.transaction-posted:transaction.posted}";
            case "onAccountUpdated":
                return "${carddemo.kafka.topics.account-updated:account.updated}";
            case "onLedgerBalanced":
                return "${carddemo.kafka.topics.ledger-balanced:ledger.balanced}";
            case "onReportRequested":
                return "${carddemo.kafka.topics.report-requested:report.requested}";
            default:
                throw new IllegalArgumentException(
                        "Unknown listener method: " + methodName);
        }
    }
}
