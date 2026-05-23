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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.KafkaEventPublisher;
import com.awsm2.carddemo.dto.ReportRequestDto;
import com.awsm2.carddemo.exception.ValidationException;
import com.awsm2.carddemo.service.ReportSubmissionService.ReportSubmissionResult;
import com.awsm2.carddemo.validation.DateValidationService;
import com.awsm2.carddemo.validation.DateValidationService.DateValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JUnit 5 + Mockito + AssertJ unit tests for
 * {@link ReportSubmissionService}.
 *
 * <p><b>COBOL provenance.</b> {@link ReportSubmissionService} translates
 * the CICS COBOL program {@code app/cbl/CORPT00C.cbl} (CICS transaction
 * id {@code CR00}, mapset {@code CORPT00}, map {@code CORPT0A}) into a
 * Java {@code @Service} class per the one-service-per-COBOL-program
 * rule (AAP &sect;0.7.1 "Isolate each COBOL program's logic in its own
 * dedicated Java service class"). The source program is the
 * <i>sole online-to-batch bridge</i> in the CardDemo source: it
 * assembles a literal JCL job-submission record at lines 81-127 of the
 * source and writes each line to the CICS Transient Data Queue
 * ({@code TDQ}) named {@code JOBS} via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS') FROM(JCL-RECORD)}
 * (paragraph {@code WIRTE-JOBSUB-TDQ}, line 515-535 of the source).
 * The JES2 reader then picks the JCL up from the spool and submits the
 * report batch job ({@code app/jcl/TRANREPT.jcl} &rarr;
 * {@code app/cbl/CBTRN03C.cbl}).</p>
 *
 * <p>In the Java target this entire mechanism is replaced by a single
 * {@code report.requested} MSK Kafka publish (per AAP &sect;0.1.1).
 * These tests verify the behavioural invariants of that translation:</p>
 *
 * <ul>
 *   <li><b>{@link KafkaEventPublisher#publishReportRequested} is the
 *       sole channel</b> &mdash; verified by Mockito
 *       {@code verify(kafkaEventPublisher).publishReportRequested(...)}
 *       on the happy path and
 *       {@code verify(kafkaEventPublisher, never()).publishReportRequested(...)}
 *       on validation-failure paths.</li>
 *   <li><b>Date range validation</b> for {@code CUSTOM} report type
 *       (monthly / yearly use service-computed periods and bypass the
 *       date-validation service) &mdash; verified by
 *       {@link DateValidation}.</li>
 *   <li><b>Report-type validation</b> &mdash; one of
 *       {@code MONTHLY / YEARLY / CUSTOM}, verified by
 *       {@link ReportTypeValidation}.</li>
 *   <li><b>Audit emission</b> &mdash; an audit event is emitted for
 *       every successful submission (AAP &sect;0.6.6 audit-trail rule)
 *       and is NOT emitted when validation throws before the publish
 *       step. Verified by {@link HappyPath#submitReport_emitsAuditEvent}
 *       and the {@code verify(auditLogService, never()).auditEvent(...)}
 *       assertions in the validation-failure tests.</li>
 *   <li><b>Confirmation envelope</b> &mdash; the returned
 *       {@link ReportSubmissionResult} carries a non-{@code null}
 *       UUID-style {@code requestId} (used as the MSK partition key
 *       per AAP &sect;0.6.5) and a human-readable
 *       acknowledgement message. Verified by
 *       {@link HappyPath#submitReport_returnsConfirmationWithReportId}.</li>
 * </ul>
 *
 * <p><b>Test taxonomy.</b> The {@link Nested} groups below mirror the
 * file-schema-specified behavioural taxonomy verbatim
 * (per the agent prompt):</p>
 *
 * <ol>
 *   <li>{@link HappyPath} &mdash; happy-path submission with all
 *       collaborators producing successful results: kafka publishes
 *       once, audit is emitted once, the response carries a non-null
 *       UUID.</li>
 *   <li>{@link ReportTypeValidation} &mdash; the {@code reportType}
 *       contract: {@code MONTHLY} / {@code YEARLY} pass without dates;
 *       {@code CUSTOM} requires both dates; unknown values throw
 *       {@link ValidationException}.</li>
 *   <li>{@link DateValidation} &mdash; the date validation cascade for
 *       {@code CUSTOM}: invalid start date, invalid end date,
 *       end-before-start chronology.</li>
 *   <li>{@link KafkaEventDetails} &mdash; verifies the precise shape
 *       of the MSK event: the key is the issued {@code requestId},
 *       and the payload DTO matches the inbound request
 *       ({@code reportType} / {@code startDate} / {@code endDate}).</li>
 *   <li>{@link FailureHandling} &mdash; verifies how the service
 *       behaves when {@link KafkaEventPublisher#publishReportRequested}
 *       fails: synchronous throw propagates to the caller (matches
 *       the COBOL {@code RESP-CD} non-zero abort semantics of
 *       {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}); a Kafka
 *       {@link CompletableFuture#failedFuture(Throwable)} return is
 *       fire-and-forget (matches the {@code @Async} producer
 *       configuration per AAP &sect;0.6.5: {@code acks=all} +
 *       {@code enable.idempotence=true} + {@code retries=MAX_VALUE}
 *       guarantees in-broker retry).</li>
 * </ol>
 *
 * <p><b>Mock wiring (AAP &sect;0.7.2 unit-test approach: JUnit 5 +
 * Mockito).</b> {@code @ExtendWith(MockitoExtension.class)} bootstraps
 * Mockito's per-test mock initialisation;
 * {@code @Mock} declares the three collaborators
 * ({@link KafkaEventPublisher}, {@link DateValidationService},
 * {@link AuditLogService}); {@code @InjectMocks} instantiates the
 * {@link ReportSubmissionService} via its constructor injecting all
 * three mocks. Per-group {@code @BeforeEach} stubs are used in
 * {@link HappyPath} and {@link KafkaEventDetails} (where every test
 * exercises the full publish path); per-test stubs are used in the
 * other groups (where most tests need only the date-validation stub or
 * no stub at all). Mockito's strict-stubbing default
 * ({@code MockitoExtension.Strictness.STRICT_STUBS}) is preserved
 * without {@code lenient()} suppressions.</p>
 *
 * <p><b>PCI-DSS discipline.</b> No real card numbers, PANs, or
 * passwords appear in any test fixture &mdash; the only data carried
 * across the test boundary is report-type selectors, dates, and the
 * confirmation flag, none of which contain cardholder data. The
 * {@code ReportRequestDto} record itself contains no PAN fields by
 * design (it replaces the BMS report-selection screen, not a
 * transaction-entry screen).</p>
 *
 * @see ReportSubmissionService
 *      the system under test (translates {@code app/cbl/CORPT00C.cbl})
 * @see ReportRequestDto
 *      request payload record (replaces {@code app/cpy-bms/CORPT00.CPY})
 * @see KafkaEventPublisher#publishReportRequested(String, ReportRequestDto)
 *      the MSK adapter method that replaces
 *      {@code EXEC CICS WRITEQ TD QUEUE('JOBS')}
 * @see DateValidationService
 *      the date-validation cascade that replaces
 *      {@code app/cbl/CSUTLDTC.cbl} + {@code app/cpy/CSUTLDPY.cpy}
 * @see AuditLogService
 *      the audit-emission adapter (replaces COBOL {@code DISPLAY} +
 *      DALYREJS trailers per AAP &sect;0.6.6)
 * @see ValidationException
 *      the validation-failure exception (mapped to HTTP 400 by
 *      {@code GlobalExceptionHandler} per AAP &sect;0.3.4)
 */
// COBOL: CORPT00C:SUBMIT-REPORT — online-to-batch bridge unit tests
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSubmissionService — CORPT00C online-to-batch bridge via MSK")
class ReportSubmissionServiceTest {

    // =========================================================================
    // Test fixture constants
    //
    // The COBOL CORPT00C source uses literal dates derived from the current
    // FUNCTION CURRENT-DATE call (lines 215-237 of the source); for unit
    // testing we use deterministic fixed dates that exercise the validation
    // cascade without depending on the current calendar day. The dates
    // chosen mirror the example values documented on the ReportRequestDto
    // OpenAPI @Schema annotation (LocalDate.of(2026, 1, 1) and
    // LocalDate.of(2026, 12, 31)) so the test assertions remain readable.
    // =========================================================================

    /**
     * Canonical CUSTOM-range start date. Chosen to match the OpenAPI example
     * value documented on {@link ReportRequestDto#startDate()} and to
     * exercise the year-2026 century check that
     * {@link DateValidationService} (port of {@code app/cpy/CSUTLDWY.cpy}
     * lines 9-10 century check) applies.
     */
    private static final LocalDate CUSTOM_START_DATE = LocalDate.of(2026, 1, 1);

    /**
     * Canonical CUSTOM-range end date. Chosen so that
     * {@link #CUSTOM_START_DATE} {@code <} this date, satisfying the
     * chronological invariant enforced at lines 525-540 of
     * {@link ReportSubmissionService}.
     */
    private static final LocalDate CUSTOM_END_DATE = LocalDate.of(2026, 12, 31);

    /**
     * Confirmation value that proceeds with submission. Matches the COBOL
     * branch at line 478 of {@code app/cbl/CORPT00C.cbl}
     * ({@code WHEN CONFIRMI = 'Y' OR 'y'}).
     */
    private static final String CONFIRM_YES = "Y";

    /**
     * Date-format mask passed to
     * {@link DateValidationService#validate(String, String)} by the
     * production code (constant {@code DATE_FORMAT_MASK} at line 205 of
     * {@link ReportSubmissionService}). Mirrors the COBOL
     * {@code WS-DATE-FORMAT} literal at line 72 of
     * {@code app/cbl/CORPT00C.cbl}.
     */
    private static final String DATE_FORMAT_MASK = "YYYY-MM-DD";

    /**
     * UUID regex matcher for {@code requestId} verification. UUID v4 is
     * 32 hex digits in 8-4-4-4-12 groups joined by dashes.
     * {@link ReportSubmissionService} generates the request id via
     * {@link UUID#randomUUID()}.{@link UUID#toString() toString()} at
     * line 384 of the service, which always produces this canonical form.
     */
    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    // =========================================================================
    // Mocks and SUT
    //
    // Mockito's MockitoExtension processes these annotations per test method:
    //   * @Mock fields are initialised to fresh Mockito mocks before each
    //     test (so stubs and verifications from one test do not bleed into
    //     another).
    //   * @InjectMocks instantiates the SUT and constructor-injects the
    //     matching @Mock fields. ReportSubmissionService has three
    //     constructor parameters in the order (KafkaEventPublisher,
    //     AuditLogService, DateValidationService); the @Mock field NAMES
    //     match the parameter names exactly so Mockito performs by-name
    //     injection regardless of declaration order.
    // =========================================================================

    /**
     * Mock MSK Kafka producer adapter.
     *
     * <p>The production service calls
     * {@link KafkaEventPublisher#publishReportRequested(String,
     * ReportRequestDto)} exactly once per successful submission at
     * line 393 of {@link ReportSubmissionService}. The signature is
     * {@code CompletableFuture<SendResult<String, Object>>
     * publishReportRequested(String, ReportRequestDto)}; the production
     * service does not chain on the returned future (fire-and-forget
     * &mdash; the @Async producer's {@code acks=all} +
     * {@code enable.idempotence=true} + {@code retries=MAX_VALUE}
     * settings handle transient broker failures internally per AAP
     * &sect;0.6.5).</p>
     *
     * <p>Stubbed via {@code when(...).thenReturn(CompletableFuture.completedFuture(null))}
     * on the happy path and via
     * {@code doThrow(...).when(...).publishReportRequested(...)} on the
     * {@link FailureHandling} synchronous-throw test.</p>
     */
    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    /**
     * Mock date-validation service.
     *
     * <p>The production service calls
     * {@link DateValidationService#validate(String, String)} once per
     * non-null date at lines 565-571 of {@link ReportSubmissionService},
     * passing {@code date.toString()} (always ISO-8601 {@code yyyy-MM-dd}
     * because {@link LocalDate#toString()} produces that exact form) and
     * the {@link #DATE_FORMAT_MASK} mask. Stubbed via
     * {@code when(...).thenReturn(DateValidationResult.VALID)} on the
     * happy path and via
     * {@code when(...).thenReturn(DateValidationResult.invalid(code, msg))}
     * on the {@link DateValidation} invalid-date tests.</p>
     */
    @Mock
    private DateValidationService dateValidationService;

    /**
     * Mock audit-log adapter.
     *
     * <p>The production service calls
     * {@link AuditLogService#auditEvent(String, String, Map)} exactly
     * once per successful submission at lines 404-405 of
     * {@link ReportSubmissionService}, with event name
     * {@code "REPORT_REQUESTED"}, actor {@code "system"}, and a payload
     * carrying {@code requestId}, {@code reportType}, and any non-null
     * dates. Verified via
     * {@code verify(auditLogService).auditEvent(...)} on the happy path
     * and via {@code verify(auditLogService, never()).auditEvent(...)}
     * on validation-failure paths (the service must not emit an audit
     * record for requests that fail validation before reaching the
     * publish step).</p>
     */
    @Mock
    private AuditLogService auditLogService;

    /**
     * The system under test.
     *
     * <p>Instantiated by Mockito via the three-arg constructor
     * {@link ReportSubmissionService#ReportSubmissionService(
     * KafkaEventPublisher, AuditLogService, DateValidationService)}
     * with the three mocks above injected as collaborators. The
     * constructor's null-safety guards (lines 274-279 of the service)
     * are inert here because Mockito never returns null mocks.</p>
     */
    @InjectMocks
    private ReportSubmissionService service;

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Build a CUSTOM-type report request fixture using
     * {@link #CUSTOM_START_DATE} and {@link #CUSTOM_END_DATE} as the
     * date range and {@link #CONFIRM_YES} as the confirmation flag.
     * The dates are always within the same calendar year (2026) so
     * the {@code startDate <= endDate} invariant is satisfied by
     * default; the {@link DateValidation#submitReport_endDateBeforeStartDate_throwsValidation}
     * test deliberately constructs a fresh DTO with the dates swapped
     * to violate the invariant.
     *
     * @return a fresh CUSTOM-type {@link ReportRequestDto}
     */
    private static ReportRequestDto customReport() {
        // COBOL: PROCESS-ENTER-KEY WHEN CUSTOMI NOT = SPACES (line 256
        // of app/cbl/CORPT00C.cbl); CUSTOM mode requires both dates.
        return new ReportRequestDto("CUSTOM", CUSTOM_START_DATE,
                CUSTOM_END_DATE, CONFIRM_YES);
    }

    /**
     * Build a MONTHLY-type report request fixture with no dates and
     * the {@link #CONFIRM_YES} confirmation. Mirrors the COBOL
     * {@code WHEN MONTHLYI NOT = SPACES} branch at line 213 of
     * {@code app/cbl/CORPT00C.cbl} which derives start/end dates from
     * {@code FUNCTION CURRENT-DATE} (lines 215-237).
     *
     * @return a fresh MONTHLY-type {@link ReportRequestDto}
     */
    private static ReportRequestDto monthlyReport() {
        // COBOL: WHEN MONTHLYI NOT = SPACES — line 213 of CORPT00C.cbl
        return new ReportRequestDto("MONTHLY", null, null, CONFIRM_YES);
    }

    /**
     * Build a YEARLY-type report request fixture with no dates and
     * the {@link #CONFIRM_YES} confirmation. Mirrors the COBOL
     * {@code WHEN YEARLYI NOT = SPACES} branch at line 239 of
     * {@code app/cbl/CORPT00C.cbl} which sets start={@code YYYY-01-01}
     * and end={@code YYYY-12-31}.
     *
     * @return a fresh YEARLY-type {@link ReportRequestDto}
     */
    private static ReportRequestDto yearlyReport() {
        // COBOL: WHEN YEARLYI NOT = SPACES — line 239 of CORPT00C.cbl
        return new ReportRequestDto("YEARLY", null, null, CONFIRM_YES);
    }

    /**
     * Stub the Kafka publisher to return an already-completed
     * {@code CompletableFuture<SendResult<String, Object>>} resolved to
     * {@code null}. This mirrors the contract of
     * {@link KafkaEventPublisher#publishReportRequested(String,
     * ReportRequestDto)} when the underlying broker accepts the message
     * and acknowledges per AAP &sect;0.6.5 ({@code acks=all}); the
     * actual {@link SendResult} value is irrelevant here because the
     * production service does not chain on the returned future.
     */
    private void stubKafkaSuccess() {
        // COBOL: WIRTE-JOBSUB-TDQ (line 515-535 of CORPT00C.cbl) — the
        // RESP-CD = 0 success branch ('Job submitted...').
        when(kafkaEventPublisher.publishReportRequested(anyString(),
                any(ReportRequestDto.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    /**
     * Stub the date-validation service to return
     * {@link DateValidationResult#VALID} for any input. Used by tests
     * that exercise the CUSTOM date-validation path on the happy side
     * (i.e., they need the date check to succeed so the flow proceeds
     * to the Kafka publish).
     */
    private void stubDateValidationValid() {
        // COBOL: CSUTLDTC.cbl A000-MAIN — RESULT-SEV-CD = '0000' (valid).
        when(dateValidationService.validate(anyString(), eq(DATE_FORMAT_MASK)))
                .thenReturn(DateValidationResult.VALID);
    }

    // =========================================================================
    // @Nested groups
    // =========================================================================

    /**
     * Happy-path tests &mdash; the four-step submission flow completes
     * end-to-end:
     *
     * <ol>
     *   <li>validateRequest passes (valid reportType, optional dates),</li>
     *   <li>validateConfirmation passes (confirm = "Y"),</li>
     *   <li>UUID requestId generated and passed to
     *       {@link KafkaEventPublisher#publishReportRequested},</li>
     *   <li>{@link AuditLogService#auditEvent} emitted with the
     *       AAP &sect;0.6.6 verbatim event name {@code REPORT_REQUESTED},</li>
     *   <li>{@link ReportSubmissionResult} returned carrying the UUID and
     *       a confirmation message.</li>
     * </ol>
     *
     * <p>COBOL provenance: this group exercises the full {@code MAIN-PARA}
     * &rarr; {@code PROCESS-ENTER-KEY} &rarr; {@code SUBMIT-JOB-TO-INTRDR}
     * &rarr; {@code WIRTE-JOBSUB-TDQ} chain of {@code app/cbl/CORPT00C.cbl}
     * (lines 163-535 of the source).</p>
     */
    @Nested
    @DisplayName("HappyPath — successful end-to-end submission")
    class HappyPath {

        /**
         * Common happy-path mock stubbing executed before every test
         * in this nested group. Stubs Kafka to return an
         * already-completed future (matching the
         * {@code acks=all}-acknowledged broker happy path per AAP
         * &sect;0.6.5). The date-validation stub is intentionally NOT
         * placed here because some tests in this group exercise
         * MONTHLY/YEARLY paths that bypass the date validator (per
         * AAP &sect;0.4.1) &mdash; including a lenient-mode stub here
         * would mask the bypass behaviour.
         */
        @BeforeEach
        void stubHappyPathDefaults() {
            // COBOL: WIRTE-JOBSUB-TDQ (line 517-535 of CORPT00C.cbl) —
            // RESP-CD = 0 success branch ("Job submitted successfully").
            stubKafkaSuccess();
        }

        /**
         * Verify that a MONTHLY submission publishes the
         * {@code report.requested} Kafka event exactly once via the
         * {@link KafkaEventPublisher} adapter (replaces the COBOL
         * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at line 517 of the
         * source).
         */
        @Test
        // COBOL: CORPT00C:SUBMIT-REPORT — happy-path publish
        @DisplayName("MONTHLY submission publishes report.requested exactly once")
        void submitReport_monthlyType_publishesReportRequested() {
            // Arrange — kafka stub installed by @BeforeEach.
            ReportRequestDto request = monthlyReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert: kafka publish invoked exactly once with the inbound
            // DTO. The first arg (requestId) is any non-null UUID string
            // because the service generates it via UUID.randomUUID();
            // the second arg must equal the inbound request DTO exactly
            // (records compare structurally).
            verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            assertThat(result).isNotNull();
        }

        /**
         * Verify the {@link ReportSubmissionResult} response envelope
         * carries a non-null {@code requestId} matching the canonical
         * UUID format ({@code 8-4-4-4-12} hex digits). The {@code requestId}
         * is the MSK partition key per AAP &sect;0.6.5 and serves as the
         * correlation identifier for downstream Step Functions execution.
         */
        @Test
        // COBOL: implicit — CORPT00C returns control to CICS via
        // EXEC CICS RETURN (lines 587-591); the Java target carries the
        // request ID back as a JSON response field.
        @DisplayName("Returns confirmation with a non-null UUID-style requestId")
        void submitReport_returnsConfirmationWithReportId() {
            // Arrange — kafka stub installed by @BeforeEach.
            ReportRequestDto request = monthlyReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert: result envelope is non-null and carries a UUID
            // requestId plus a non-blank acknowledgement message.
            assertThat(result).isNotNull();
            assertThat(result.requestId())
                    .as("requestId must be a non-null UUID")
                    .isNotNull()
                    .isNotBlank()
                    .matches(UUID_REGEX);
            assertThat(result.message())
                    .as("message must be a non-blank acknowledgement")
                    .isNotNull()
                    .isNotBlank()
                    .contains(result.requestId());
        }

        /**
         * Verify that an audit event is emitted exactly once per
         * successful submission via
         * {@link AuditLogService#auditEvent(String, String, Map)}
         * (AAP &sect;0.6.6 audit-trail rule). The event name is the
         * AAP-specified constant {@code "REPORT_REQUESTED"} and the
         * actor is the service-level fallback {@code "system"} (the
         * production code constant {@code AUDIT_OPERATOR_SYSTEM} at
         * line 222 of {@link ReportSubmissionService}).
         */
        @Test
        // COBOL: replaces DISPLAY 'PROCESS ENTER KEY' (line 210) + any
        // operational diagnostic emissions; AAP §0.6.6 audit-trail.
        @DisplayName("Emits a REPORT_REQUESTED audit event exactly once")
        void submitReport_emitsAuditEvent() {
            // Arrange — kafka stub installed by @BeforeEach.
            ReportRequestDto request = monthlyReport();

            // Act
            service.submitReport(request);

            // Assert: auditEvent invoked exactly once with the
            // AAP-specified event name and the service-level fallback
            // actor. The payload Map is verified loosely with anyMap()
            // because its precise field set is the subject of the
            // KafkaEventDetails group below.
            verify(auditLogService).auditEvent(eq("REPORT_REQUESTED"),
                    eq("system"), anyMap());
        }

        /**
         * Verify the end-to-end happy-path invariant: kafka publish
         * happens, audit emission happens, and the result is returned —
         * all three side-effects coordinated. This test complements
         * the per-collaborator tests above by asserting the combined
         * behaviour in a single scenario.
         */
        @Test
        // COBOL: full CORPT00C:MAIN-PARA → PROCESS-ENTER-KEY →
        // SUBMIT-JOB-TO-INTRDR → WIRTE-JOBSUB-TDQ chain.
        @DisplayName("CUSTOM submission: publish + audit + response all succeed")
        void submitReport_customType_endToEndHappyPath() {
            // Arrange — kafka stub installed by @BeforeEach; CUSTOM
            // exercises the date validator so we add that stub here.
            stubDateValidationValid();
            ReportRequestDto request = customReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert — kafka, audit, and response are all observed.
            verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            verify(auditLogService).auditEvent(eq("REPORT_REQUESTED"),
                    eq("system"), anyMap());
            assertThat(result).isNotNull();
            assertThat(result.requestId()).matches(UUID_REGEX);

            // Each non-null date is validated exactly once — CUSTOM has
            // both start and end populated, so the date validation
            // service is called twice in total.
            verify(dateValidationService).validate(
                    eq(CUSTOM_START_DATE.toString()), eq(DATE_FORMAT_MASK));
            verify(dateValidationService).validate(
                    eq(CUSTOM_END_DATE.toString()), eq(DATE_FORMAT_MASK));
        }
    }

    /**
     * Report-type validation tests &mdash; verifies the
     * {@code reportType} contract: the allowed-types list
     * {@code MONTHLY / YEARLY / CUSTOM} (case-insensitive). Per AAP
     * &sect;0.4.1 the {@link DateValidationService} is bypassed for
     * MONTHLY/YEARLY (the service computes the date window internally
     * from the current calendar); only CUSTOM consults the date
     * validator.
     *
     * <p>COBOL provenance: the {@code EVALUATE TRUE WHEN MONTHLYI /
     * YEARLYI / CUSTOMI} discriminator at lines 213, 239, 256 of
     * {@code app/cbl/CORPT00C.cbl}, plus the implicit
     * {@code WHEN OTHER} reject branch (the Java target rejects an
     * unknown selector explicitly for a clean JSON error envelope, per
     * lines 476-486 of {@link ReportSubmissionService}).</p>
     */
    @Nested
    @DisplayName("ReportTypeValidation — reportType domain enforcement")
    class ReportTypeValidation {

        /**
         * Verify that MONTHLY is accepted without explicit start/end
         * dates. The service computes the calendar window internally
         * (matching the COBOL flow at lines 213-238 of the source) and
         * publishes the event. The {@link DateValidationService} is
         * NOT called because both dates are {@code null}
         * (production line 518-523 guards on null).
         */
        @Test
        // COBOL: WHEN MONTHLYI NOT = SPACES — line 213 of CORPT00C.cbl
        @DisplayName("MONTHLY accepted without explicit dates; date service bypassed")
        void submitReport_monthlyType_acceptedWithoutDates() {
            // Arrange
            stubKafkaSuccess();
            ReportRequestDto request = monthlyReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert: success — kafka publishes, no date validation
            // happens (DateValidationService is invoked only for non-null
            // dates; MONTHLY carries null dates by design).
            assertThat(result).isNotNull();
            verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            verifyNoInteractions(dateValidationService);
        }

        /**
         * Verify that YEARLY is accepted without explicit dates &mdash;
         * same contract as MONTHLY but with a calendar-year window
         * ({@code YYYY-01-01 .. YYYY-12-31}, lines 239-255 of the
         * source).
         */
        @Test
        // COBOL: WHEN YEARLYI NOT = SPACES — line 239 of CORPT00C.cbl
        @DisplayName("YEARLY accepted without explicit dates; date service bypassed")
        void submitReport_yearlyType_acceptedWithoutDates() {
            // Arrange
            stubKafkaSuccess();
            ReportRequestDto request = yearlyReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert: identical to the MONTHLY happy path — kafka
            // publishes, date validation is bypassed.
            assertThat(result).isNotNull();
            verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            verifyNoInteractions(dateValidationService);
        }

        /**
         * Verify that CUSTOM requires a non-null {@code startDate}.
         * Mirrors the COBOL {@code WHEN SDTMMI = SPACES OR LOW-VALUES}
         * guard at lines 259-280 of {@code CORPT00C.cbl} which prevents
         * the JCL stream from being assembled with empty date triplets.
         */
        @Test
        // COBOL: WHEN SDTMMI = SPACES OR LOW-VALUES — lines 259-280 of CORPT00C.cbl
        @DisplayName("CUSTOM without startDate throws ValidationException")
        void submitReport_customWithoutStartDate_throwsValidation() {
            // Arrange — CUSTOM with null startDate
            ReportRequestDto request = new ReportRequestDto(
                    "CUSTOM", null, CUSTOM_END_DATE, CONFIRM_YES);

            // Act + Assert — service must throw before reaching the
            // Kafka publish step.
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("startDate");

            // Verify side-effects: NO kafka publish, NO audit emission.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that CUSTOM with both dates null throws on the first
         * missing date (startDate). Combines the two prior negative
         * cases into a single assertion to document the cascade order
         * (startDate checked before endDate per lines 497-510 of
         * {@link ReportSubmissionService}).
         */
        @Test
        // COBOL: WHEN SDTMMI = SPACES (line 259) precedes EDTMMI guard
        @DisplayName("CUSTOM with both dates null throws — startDate checked first")
        void submitReport_customType_requiresStartAndEndDates() {
            // Arrange — CUSTOM with both dates null
            ReportRequestDto request = new ReportRequestDto(
                    "CUSTOM", null, null, CONFIRM_YES);

            // Act + Assert — startDate is checked first (lines 497-503
            // of ReportSubmissionService), so the error message
            // references startDate.
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("startDate");

            // No publish, no audit, no date-validation call (the missing
            // start-date guard fires before the date-validator call).
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
            verifyNoInteractions(dateValidationService);
        }

        /**
         * Verify that an unknown report type ({@code "XXX"}) throws
         * {@link ValidationException}. Mirrors the COBOL implicit
         * {@code WHEN OTHER} branch (when {@code MONTHLYI / YEARLYI /
         * CUSTOMI} are all SPACES) at the end of the
         * {@code EVALUATE TRUE} block in {@code PROCESS-ENTER-KEY}.
         * The Java target rejects unknown selectors explicitly with a
         * clean JSON error envelope, per lines 476-486 of
         * {@link ReportSubmissionService}.
         */
        @Test
        // COBOL: implicit WHEN OTHER of EVALUATE TRUE in PROCESS-ENTER-KEY
        @DisplayName("Unknown reportType (e.g. 'XXX') throws ValidationException")
        void submitReport_invalidType_throwsValidation() {
            // Arrange — unknown reportType
            ReportRequestDto request = new ReportRequestDto(
                    "XXX", null, null, CONFIRM_YES);

            // Act + Assert — reportType is checked before any other
            // field, so the error message references reportType.
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("reportType");

            // No publish, no audit, no date-validation call.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
            verifyNoInteractions(dateValidationService);
        }

        /**
         * Verify that a null reportType throws
         * {@link ValidationException}. The DTO record allows null
         * (Bean Validation runs at the controller layer, not in the
         * service), so the service defensively rejects null per lines
         * 466-474 of {@link ReportSubmissionService}.
         */
        @Test
        // COBOL: implicit — all three MONTHLYI/YEARLYI/CUSTOMI are SPACES,
        // so EVALUATE TRUE falls through with no match (lines 437-442 of
        // CORPT00C.cbl): "Select a report type to print report..."
        @DisplayName("Null reportType throws ValidationException")
        void submitReport_nullType_throwsValidation() {
            // Arrange — null reportType
            ReportRequestDto request = new ReportRequestDto(
                    null, null, null, CONFIRM_YES);

            // Act + Assert
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("reportType");

            // No publish, no audit, no date-validation call.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
            verifyNoInteractions(dateValidationService);
        }
    }

    /**
     * Date-validation tests &mdash; verifies the
     * {@link DateValidationService} delegation contract for the CUSTOM
     * report type. Per AAP &sect;0.4.1, the service is bypassed for
     * MONTHLY/YEARLY (covered by {@link ReportTypeValidation}) and
     * invoked once per non-null date for CUSTOM.
     *
     * <p>COBOL provenance: replaces the {@code CALL 'CSUTLDTC'} cascade
     * at lines 388-394 (start date) and 408-414 (end date) of
     * {@code app/cbl/CORPT00C.cbl}. The CSUTLDTC subprogram applies the
     * COBOL LE {@code CEEDAYS} validation (leap year, month-day range,
     * century check) which the Java
     * {@link DateValidationService#validate(String, String)} method
     * ports verbatim (per AAP &sect;0.5.2 dependency-removal table).</p>
     */
    @Nested
    @DisplayName("DateValidation — CUSTOM date cascade")
    class DateValidation {

        /**
         * Verify that an invalid {@code startDate} (as judged by
         * {@link DateValidationService}) throws
         * {@link ValidationException}. Mirrors the COBOL rejection
         * branch at line 400 of {@code CORPT00C.cbl}:
         * {@code "Start Date - Not a valid date..."}.
         */
        @Test
        // COBOL: CORPT00C:PROCESS-ENTER-KEY lines 396-406 — Start Date
        // invalid rejection.
        @DisplayName("Invalid startDate throws ValidationException")
        void submitReport_customWithInvalidStartDate_throwsValidation() {
            // Arrange — stub date validation to return invalid for the
            // startDate string; the service iterates start before end
            // (line 518-523 of ReportSubmissionService), so the start
            // failure short-circuits the end check.
            when(dateValidationService.validate(
                    eq(CUSTOM_START_DATE.toString()), eq(DATE_FORMAT_MASK)))
                    .thenReturn(DateValidationResult.invalid("2003",
                            "Start Date - Not a valid date"));
            ReportRequestDto request = customReport();

            // Act + Assert
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("startDate")
                    .hasMessageContaining("Not a valid date");

            // No publish, no audit — failure happens BEFORE the Kafka
            // publish step.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that an invalid {@code endDate} (as judged by
         * {@link DateValidationService}) throws
         * {@link ValidationException}. Mirrors the COBOL rejection
         * branch at line 420 of {@code CORPT00C.cbl}:
         * {@code "End Date - Not a valid date..."}.
         */
        @Test
        // COBOL: CORPT00C:PROCESS-ENTER-KEY lines 416-426 — End Date
        // invalid rejection.
        @DisplayName("Invalid endDate throws ValidationException")
        void submitReport_customWithInvalidEndDate_throwsValidation() {
            // Arrange — startDate valid, endDate invalid. The service
            // validates startDate first; we explicitly stub it as valid
            // so the cascade reaches the endDate validation.
            when(dateValidationService.validate(
                    eq(CUSTOM_START_DATE.toString()), eq(DATE_FORMAT_MASK)))
                    .thenReturn(DateValidationResult.VALID);
            when(dateValidationService.validate(
                    eq(CUSTOM_END_DATE.toString()), eq(DATE_FORMAT_MASK)))
                    .thenReturn(DateValidationResult.invalid("2003",
                            "End Date - Not a valid date"));
            ReportRequestDto request = customReport();

            // Act + Assert
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("endDate")
                    .hasMessageContaining("Not a valid date");

            // No publish, no audit.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that {@code endDate < startDate} throws
         * {@link ValidationException} even when both dates pass
         * structural validation. The chronological invariant is
         * enforced explicitly at lines 525-540 of
         * {@link ReportSubmissionService}; the COBOL source assumes
         * the operator provides a sensible range without an explicit
         * check, so this is a defensive Java enhancement that
         * preserves a clean validation error rather than producing
         * an empty downstream report.
         */
        @Test
        // COBOL: implicit chronology invariant — no explicit COBOL line.
        // The JCL parameters PARM-START-DATE-1 and PARM-END-DATE-1 at
        // lines 105-111 of CORPT00C.cbl presume an ordered range.
        @DisplayName("endDate before startDate throws ValidationException")
        void submitReport_endDateBeforeStartDate_throwsValidation() {
            // Arrange — swap the dates: end (Jan 1) < start (Dec 31).
            // Both dates are individually valid per DateValidationService.
            stubDateValidationValid();
            ReportRequestDto request = new ReportRequestDto(
                    "CUSTOM",
                    CUSTOM_END_DATE,    // 2026-12-31 used as start
                    CUSTOM_START_DATE,  // 2026-01-01 used as end
                    CONFIRM_YES);

            // Act + Assert — service throws ValidationException with a
            // message referencing the endDate <= startDate chronology
            // (per lines 535-540 of ReportSubmissionService).
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("startDate")
                    .hasMessageContaining("endDate");

            // No publish, no audit.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that for CUSTOM with both dates equal (the inclusive
         * boundary case: a single-day report), the chronological
         * invariant {@code startDate <= endDate} is satisfied and the
         * service proceeds normally to publish the event. This is a
         * positive-case companion to the swap test above, verifying
         * that the comparison is strictly {@code isAfter} (not
         * {@code !isBefore} which would reject equal dates).
         */
        @Test
        // COBOL: implicit — chronology check at lines 525-540 of
        // ReportSubmissionService uses isAfter (strict), so equal dates
        // are accepted (single-day report window).
        @DisplayName("Equal startDate and endDate (single-day window) succeeds")
        void submitReport_equalStartAndEndDate_succeeds() {
            // Arrange — start == end
            stubKafkaSuccess();
            stubDateValidationValid();
            ReportRequestDto request = new ReportRequestDto(
                    "CUSTOM", CUSTOM_START_DATE, CUSTOM_START_DATE,
                    CONFIRM_YES);

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert — proceeds to publish + audit + return.
            assertThat(result).isNotNull();
            verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            verify(auditLogService).auditEvent(eq("REPORT_REQUESTED"),
                    eq("system"), anyMap());
        }
    }

    /**
     * Kafka event-detail tests &mdash; verifies the precise shape of
     * the {@code report.requested} MSK event:
     *
     * <ul>
     *   <li><b>Key:</b> the issued UUID {@code requestId} (also returned
     *       in {@link ReportSubmissionResult#requestId()}).</li>
     *   <li><b>Payload:</b> the inbound {@link ReportRequestDto} carried
     *       verbatim (no field translation).</li>
     * </ul>
     *
     * <p>COBOL provenance: replaces the COBOL JCL templating at lines
     * 81-127 of {@code app/cbl/CORPT00C.cbl} (the {@code JOB-DATA}
     * 80-byte literal stream). The Java target intentionally does NOT
     * reproduce the JCL string construction; instead the MSK consumer
     * (Step Functions trigger Lambda) reads the DTO and synthesises
     * the Step Functions execution input. The MSK key + payload
     * together provide complete provenance.</p>
     */
    @Nested
    @DisplayName("KafkaEventDetails — MSK event key + payload assertions")
    class KafkaEventDetails {

        /**
         * Common stubbing executed before every test in this nested
         * group. Every {@link KafkaEventDetails} test exercises the
         * full happy-path flow (so it can inspect the captured DTO and
         * key), so the kafka publisher must always be stubbed to
         * succeed. The date validation stub is added per-test because
         * some tests use MONTHLY (no dates &rarr; no date-validator
         * call) while others use CUSTOM.
         */
        @BeforeEach
        void stubKafkaDetailsDefaults() {
            // COBOL: WIRTE-JOBSUB-TDQ success — RESP-CD = 0.
            stubKafkaSuccess();
        }

        /**
         * Verify the event payload exactly matches the inbound
         * {@link ReportRequestDto}, including all four record
         * components ({@code reportType}, {@code startDate},
         * {@code endDate}, {@code confirm}). Uses
         * {@link ArgumentCaptor} to capture the DTO passed to
         * {@link KafkaEventPublisher#publishReportRequested}.
         */
        @Test
        // COBOL: implicit — the JCL stream at lines 105-121 of
        // CORPT00C.cbl carries PARM-START-DATE-1, PARM-END-DATE-1, and
        // the implicit report-type identifier; the Java target carries
        // them as DTO fields on the Kafka message.
        @DisplayName("Event payload mirrors the inbound DTO field-for-field")
        void submitReport_eventContainsReportId_andReportType() {
            // Arrange — CUSTOM with explicit dates so all four DTO
            // fields are non-null. Date validator stubbed here because
            // CUSTOM exercises it.
            stubDateValidationValid();
            ReportRequestDto request = customReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Capture the DTO passed to publishReportRequested.
            ArgumentCaptor<ReportRequestDto> dtoCaptor =
                    ArgumentCaptor.forClass(ReportRequestDto.class);
            ArgumentCaptor<String> keyCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(kafkaEventPublisher).publishReportRequested(
                    keyCaptor.capture(), dtoCaptor.capture());

            // Assert: the captured DTO equals the inbound request
            // (records use structural equality, so all four components
            // must match).
            ReportRequestDto captured = dtoCaptor.getValue();
            assertThat(captured).isNotNull();
            assertThat(captured.reportType()).isEqualTo("CUSTOM");
            assertThat(captured.startDate()).isEqualTo(CUSTOM_START_DATE);
            assertThat(captured.endDate()).isEqualTo(CUSTOM_END_DATE);
            assertThat(captured.confirm()).isEqualTo(CONFIRM_YES);
            // Stronger: full structural equality with the inbound DTO.
            assertThat(captured).isEqualTo(request);

            // The result's requestId is the same as the kafka key —
            // both come from a single UUID.randomUUID() call.
            assertThat(keyCaptor.getValue()).isEqualTo(result.requestId());
        }

        /**
         * Verify that the Kafka partition key is exactly the
         * {@code requestId} returned in the
         * {@link ReportSubmissionResult}. Per AAP &sect;0.6.5 the
         * partition key drives the murmur2 hash that selects the
         * topic partition; using the requestId guarantees per-report
         * ordering even with multiple concurrent submitters.
         */
        @Test
        // AAP §0.6.5: partition key = requestId for per-report ordering.
        @DisplayName("Kafka partition key equals the returned requestId")
        void submitReport_eventKeyIsReportId() {
            // Arrange — kafka stub installed by @BeforeEach. MONTHLY
            // bypasses date validation entirely.
            ReportRequestDto request = monthlyReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Capture the key (first arg).
            ArgumentCaptor<String> keyCaptor =
                    ArgumentCaptor.forClass(String.class);
            verify(kafkaEventPublisher).publishReportRequested(
                    keyCaptor.capture(), eq(request));

            // Assert: the captured key equals the requestId returned to
            // the caller. Both flow from the same UUID.randomUUID()
            // call inside the service, so equality is by reference
            // (same String instance).
            String capturedKey = keyCaptor.getValue();
            assertThat(capturedKey)
                    .as("Kafka key must equal the returned requestId")
                    .isNotNull()
                    .isNotBlank()
                    .isEqualTo(result.requestId())
                    .matches(UUID_REGEX);
        }

        /**
         * Verify that the audit payload Map contains the same
         * structural fields as the Kafka event &mdash; the audit
         * record and the Kafka message remain in lockstep so
         * downstream OpenSearch search returns the same view of the
         * submission whether queried via Kafka events or audit
         * records. This complements
         * {@link HappyPath#submitReport_emitsAuditEvent} which only
         * checks that audit is invoked; this test inspects the
         * payload contents.
         */
        @Test
        // AAP §0.6.6: audit payload must carry the same structural
        // fields as the Kafka message for searchable lockstep retention.
        @DisplayName("Audit payload Map mirrors the report fields")
        void submitReport_auditPayload_includesReportFields() {
            // Arrange — kafka stub installed by @BeforeEach. CUSTOM
            // with explicit dates so the audit payload can carry all
            // four field projections (requestId, reportType, startDate,
            // endDate; confirm is intentionally omitted per
            // buildAuditPayload at lines 669-691 of the service).
            stubDateValidationValid();
            ReportRequestDto request = customReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Capture the audit payload.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor =
                    ArgumentCaptor.forClass(Map.class);
            verify(auditLogService).auditEvent(
                    eq("REPORT_REQUESTED"),
                    eq("system"),
                    payloadCaptor.capture());

            // Assert the payload structure.
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                    .as("audit payload must carry requestId")
                    .containsEntry("requestId", result.requestId());
            assertThat(payload)
                    .as("audit payload must carry normalized reportType")
                    .containsEntry("reportType", "CUSTOM");
            assertThat(payload)
                    .as("audit payload must carry ISO-8601 startDate")
                    .containsEntry("startDate", CUSTOM_START_DATE.toString());
            assertThat(payload)
                    .as("audit payload must carry ISO-8601 endDate")
                    .containsEntry("endDate", CUSTOM_END_DATE.toString());
            assertThat(payload)
                    .as("audit payload must NOT carry the confirm flag — "
                            + "the confirm field is a gate signal only, "
                            + "not a report-semantics field (per lines 654-658 "
                            + "of ReportSubmissionService)")
                    .doesNotContainKey("confirm");
        }
    }

    /**
     * Failure-handling tests &mdash; verifies how
     * {@link ReportSubmissionService} reacts to
     * {@link KafkaEventPublisher#publishReportRequested} failures.
     *
     * <p>Per AAP &sect;0.6.5 the producer is configured with
     * {@code acks=all} + {@code enable.idempotence=true} +
     * {@code retries=Integer.MAX_VALUE}, so transient broker
     * unavailability is retried internally and never surfaces as a
     * synchronous exception. The production service is fire-and-forget
     * (it does not chain on the returned {@code CompletableFuture}):
     * a synchronous throw from the adapter (e.g., null/blank
     * {@code reportId} per the publisher's preconditions) DOES
     * propagate, while a {@link CompletableFuture#failedFuture(Throwable)}
     * return is silently swallowed.</p>
     *
     * <p>This group exercises both contracts to document the actual
     * production behaviour and ensure regressions are caught.</p>
     */
    @Nested
    @DisplayName("FailureHandling — Kafka publish failure propagation")
    class FailureHandling {

        /**
         * Verify that a synchronous {@link RuntimeException} thrown by
         * {@link KafkaEventPublisher#publishReportRequested} propagates
         * out of {@link ReportSubmissionService#submitReport} verbatim.
         * This is the path taken when the adapter rejects the request
         * before the underlying Kafka producer accepts it (e.g., null
         * {@code reportId} or an internal mapping failure).
         *
         * <p>The COBOL analogue is {@code EXEC CICS WRITEQ TD
         * QUEUE('JOBS')} returning a non-zero {@code RESP-CD} at lines
         * 517-527 of {@code CORPT00C.cbl} which the COBOL program then
         * surfaces to the operator as a "Unable to submit job..." error
         * (line 527-535). The Java target propagates the exception so
         * the {@code GlobalExceptionHandler} can translate it to an
         * HTTP 5xx response.</p>
         */
        @Test
        // COBOL: RESP-CD non-zero from EXEC CICS WRITEQ TD QUEUE('JOBS')
        // — lines 517-535 of CORPT00C.cbl ("Unable to submit job ...").
        @DisplayName("Synchronous Kafka publish exception propagates")
        void submitReport_kafkaPublishFails_propagatesException() {
            // Arrange — Kafka publish throws synchronously. doThrow is
            // used here (not when(...).thenReturn(failedFuture(...)))
            // because the production service does not chain on the
            // returned future, so a failed future would NOT propagate
            // (see the asynchronous-failure test below for that path).
            RuntimeException brokerDown =
                    new RuntimeException("MSK broker unreachable (simulated)");
            doThrow(brokerDown)
                    .when(kafkaEventPublisher)
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            ReportRequestDto request = monthlyReport();

            // Act + Assert — the exception propagates verbatim.
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("MSK broker unreachable");

            // Audit is NOT emitted — the audit emission happens AFTER
            // the Kafka publish at lines 404-405 of the service, so a
            // synchronous Kafka failure prevents audit from running.
            // This matches the COBOL flow where the JCL submission
            // failure aborts the program before any operational
            // diagnostic is logged.
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that an asynchronous failure (a
         * {@link CompletableFuture#failedFuture(Throwable)} returned by
         * the Kafka adapter) does NOT propagate out of
         * {@link ReportSubmissionService#submitReport}. The production
         * service is fire-and-forget: it discards the returned future,
         * relying on the producer's internal retry logic
         * ({@code acks=all} + {@code retries=Integer.MAX_VALUE} bounded
         * by {@code delivery.timeout.ms}, per AAP &sect;0.6.5).
         *
         * <p>This test documents the actual production contract; an
         * asynchronous future failure is observable only by the
         * Spring-Kafka error handler configured in
         * {@code KafkaConfig}, not by the calling service. The audit
         * event IS still emitted because the service proceeds past the
         * publish step unhindered.</p>
         */
        @Test
        // AAP §0.6.5: fire-and-forget producer with internal retry —
        // async future failure is NOT propagated to the caller.
        @DisplayName("Async failed-future is fire-and-forget — service completes successfully")
        void submitReport_kafkaPublishFutureFailsAsync_completesSuccessfully() {
            // Arrange — Kafka publish returns a failed future. The
            // production service does not chain on the future, so this
            // failure is silently swallowed (the producer's internal
            // retry handles transient errors per AAP §0.6.5).
            CompletableFuture<SendResult<String, Object>> failed =
                    CompletableFuture.failedFuture(
                            new RuntimeException(
                                    "MSK transient broker error (simulated)"));
            when(kafkaEventPublisher.publishReportRequested(anyString(),
                    any(ReportRequestDto.class)))
                    .thenReturn(failed);
            ReportRequestDto request = monthlyReport();

            // Act — service completes successfully despite the failed
            // future (fire-and-forget contract).
            ReportSubmissionResult result = service.submitReport(request);

            // Assert — the result envelope is populated normally and the
            // audit emission happens (the service proceeds past the
            // discarded future).
            assertThat(result).isNotNull();
            assertThat(result.requestId()).matches(UUID_REGEX);
            verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            verify(auditLogService).auditEvent(eq("REPORT_REQUESTED"),
                    eq("system"), anyMap());
        }

        /**
         * Verify that an invalid confirmation flag (anything other than
         * {@code "Y"} or {@code "N"}, or {@code null}/blank) throws
         * {@link ValidationException} BEFORE any Kafka publish or audit
         * emission. The COBOL source treats this as the
         * {@code '"<x>" is not a valid value to confirm...'} branch at
         * lines 484-493 of {@code app/cbl/CORPT00C.cbl}.
         */
        @Test
        // COBOL: CORPT00C:SUBMIT-JOB-TO-INTRDR lines 484-493 — invalid
        // confirm value rejection.
        @DisplayName("Invalid confirm value throws ValidationException before publish")
        void submitReport_invalidConfirm_throwsBeforePublish() {
            // Arrange — confirm = "X" (not Y/N).
            ReportRequestDto request = new ReportRequestDto(
                    "MONTHLY", null, null, "X");

            // Act + Assert
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("confirm");

            // No publish, no audit — confirmation gate fires before
            // those steps.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that a null confirmation flag throws
         * {@link ValidationException}. Mirrors the COBOL
         * {@code IF CONFIRMI = SPACES OR LOW-VALUES} guard at line 464
         * of the source.
         */
        @Test
        // COBOL: IF CONFIRMI = SPACES OR LOW-VALUES — line 464 of CORPT00C.cbl
        @DisplayName("Null confirm throws ValidationException")
        void submitReport_nullConfirm_throwsValidation() {
            // Arrange — confirm = null
            ReportRequestDto request = new ReportRequestDto(
                    "MONTHLY", null, null, null);

            // Act + Assert
            assertThatThrownBy(() -> service.submitReport(request))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("confirm");

            // No publish, no audit.
            verify(kafkaEventPublisher, never())
                    .publishReportRequested(anyString(),
                            any(ReportRequestDto.class));
            verify(auditLogService, never())
                    .auditEvent(anyString(), anyString(), anyMap());
        }

        /**
         * Verify that the publish-then-audit ordering is preserved on
         * the happy path &mdash; the Kafka publish must happen
         * <i>before</i> the audit emission so that, in the unlikely
         * event of a synchronous Kafka failure, the audit record does
         * not falsely indicate that the report was submitted. This
         * test uses an {@link org.mockito.InOrder} verifier to assert
         * the temporal ordering.
         */
        @Test
        // AAP §0.6.6 / agent-prompt phase-4 critical rule: "Verify
        // Kafka topic event is published BEFORE the response is
        // returned (i.e., not after)".
        @DisplayName("Publish happens BEFORE audit emission (in-order)")
        void submitReport_publishBeforeAudit_inOrder() {
            // Arrange
            stubKafkaSuccess();
            ReportRequestDto request = monthlyReport();

            // Act
            ReportSubmissionResult result = service.submitReport(request);

            // Assert — in-order verification: kafka publish first, then
            // audit emission. This guarantees that the audit record is
            // never emitted unless the kafka publish has at least been
            // attempted, preserving the COBOL
            // PROCESS-ENTER-KEY → WIRTE-JOBSUB-TDQ ordering.
            org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                    kafkaEventPublisher, auditLogService);
            inOrder.verify(kafkaEventPublisher)
                    .publishReportRequested(anyString(), eq(request));
            inOrder.verify(auditLogService).auditEvent(
                    eq("REPORT_REQUESTED"), eq("system"), anyMap());
            assertThat(result).isNotNull();
        }
    }
}

