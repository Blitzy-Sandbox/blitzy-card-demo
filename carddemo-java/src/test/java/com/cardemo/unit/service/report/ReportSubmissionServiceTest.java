package com.cardemo.unit.service.report;

import com.cardemo.config.AwsConfig;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.model.dto.ReportSubmissionResponse;
import com.cardemo.service.report.ReportSubmissionService;
import com.cardemo.service.shared.DateValidationService;

import io.awspring.cloud.sqs.operations.SqsSendOptions;
import io.awspring.cloud.sqs.operations.SqsTemplate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Fast, fully-mocked unit tests for {@link ReportSubmissionService} &mdash; the Java&nbsp;25 /
 * Spring&nbsp;Boot&nbsp;3.5.x translation of the online CICS program
 * <strong>{@code app/cbl/CORPT00C.cbl}</strong> (CICS transaction {@code CR00}, BMS map
 * {@code CORPT0A}), the <em>sole</em> online&rarr;batch bridge in the CardDemo estate (AAP
 * &sect;0.6.3). The COBOL source is read-only reference material and is <strong>never</strong>
 * copied into this repository; traceability is by the frozen baseline commit SHA {@code 27d6c6f}.
 *
 * <h2>Why these tests exist (behavioral-parity contract &mdash; AAP &sect;0.7.1&ndash;&sect;0.7.2)</h2>
 * <p>The service reproduces {@code CORPT00C} behavior exactly, so this suite encodes that contract.
 * Every user-facing assertion uses the <strong>verbatim COBOL message string</strong> taken from
 * {@code CORPT00C.cbl} (and the agent-prompt Appendix) as the <em>independent</em> source of truth
 * &mdash; assertions are never weakened to match the production code, and the service's own message
 * constants are never referenced (that would be tautological). Exact {@code .hasMessage(...)}
 * matching is used throughout (never {@code .hasMessageContaining(...)}).</p>
 *
 * <h2>Technology substitutions exercised here (Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>CICS TDQ {@code WRITEQ('JOBS')} / JES submission &rarr; one AWS SQS publish.</strong>
 *       The legacy "build a JCL deck and write each line to the {@code JOBS} transient-data queue"
 *       hand-off becomes a single message published to the FIFO queue
 *       {@code carddemo-report-jobs.fifo}. Here {@link SqsTemplate} is <em>mocked</em> and the publish
 *       is verified via the captured-consumer replay recipe (see {@link #captureSinglePublishedMessage()});
 *       the real LocalStack/Testcontainers SQS round-trip is a separate {@code *IT.java} integration
 *       concern and is deliberately <strong>not</strong> exercised in this fast unit test.</li>
 *   <li><strong>LE {@code CALL 'CSUTLDTC'} ({@code CEEDAYS}) date validation &rarr;
 *       {@link DateValidationService}.</strong> The custom-range real-date check is delegated to the
 *       sibling date validator, which is mocked here so the start-then-end ordering and the
 *       valid/invalid outcomes can be driven deterministically.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * <p>{@code CORPT00C} read "today" from {@code FUNCTION CURRENT-DATE} for the Monthly/Yearly range
 * derivation. To make those derivations deterministic this suite injects a fixed
 * {@link java.time.Clock} through the service's four-argument constructor (never relying on
 * wall-clock time, and never using {@code @InjectMocks}, which cannot supply a {@link Clock}).</p>
 *
 * <h2>Test isolation</h2>
 * <p>Pure unit test: no Spring context, no I/O, no network, no database, no Testcontainers, no
 * LocalStack and no live AWS. Every collaborator is a Mockito mock under the default
 * {@code STRICT_STUBS} strictness, so each scenario stubs only the collaborators it actually
 * exercises (the SQS queue name on the publish path; the date validator only on the custom
 * real-date path).</p>
 *
 * @see ReportSubmissionService
 * @see DateValidationService
 * @see io.awspring.cloud.sqs.operations.SqsTemplate
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSubmissionService — CORPT00C (CR00) behavioral-parity unit tests")
class ReportSubmissionServiceTest {

    // ---------------------------------------------------------------------------------------------
    // Fixed test constants.
    // ---------------------------------------------------------------------------------------------

    /** Fixed zone for the injected clock; UTC keeps "today" unambiguous across hosts. */
    private static final ZoneId ZONE = ZoneId.of("UTC");

    /** Default "today" &mdash; a 31-day month so the Monthly end-of-month is unambiguous by default. */
    private static final LocalDate DEFAULT_TODAY = LocalDate.of(2024, 3, 15);

    /**
     * The logical FIFO queue name returned by the mocked
     * {@link AwsConfig.AwsResourceProperties#getSqs() getSqs()}{@code .getReportJobsQueue()}; it is the
     * only resource-name literal in this test and is never a live endpoint/URL/credential.
     */
    private static final String FIFO_QUEUE = "carddemo-report-jobs.fifo";

    /** The fixed SQS FIFO message-group id the service uses for every report-submission message. */
    private static final String GROUP_ID = "report-jobs";

    /**
     * The picture the service passes to {@link DateValidationService#validateDate(String, String)}
     * for the custom real-date check (the {@code CSUTLDTC} replacement).
     */
    private static final String DATE_FORMAT = "YYYYMMDD";

    // ---------------------------------------------------------------------------------------------
    // Mocked collaborators (constructed fresh per test by MockitoExtension).
    // ---------------------------------------------------------------------------------------------

    /** SQS operations facade &mdash; the CICS TDQ {@code WRITEQ('JOBS')} replacement (mocked). */
    @Mock
    private SqsTemplate sqsTemplate;

    /**
     * AWS resource-name binder; deep-stubbed so {@code getSqs().getReportJobsQueue()} can be chained.
     * It is stubbed (via {@link #stubQueue()}) <em>only</em> on the publish path to keep strict stubs
     * satisfied.
     */
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private AwsConfig.AwsResourceProperties awsProperties;

    /** The {@code CSUTLDTC} &rarr; {@code java.time} date validator (mocked); stubbed only on the
     *  custom real-date path. */
    @Mock
    private DateValidationService dateValidationService;

    /**
     * Captures the fluent builder {@link Consumer} handed to {@link SqsTemplate#send(Consumer)} so it
     * can be replayed against a {@link #mockSendOptions() RETURNS_SELF} options mock for attribute
     * verification. Declared as a {@code @Captor} field (rather than {@code ArgumentCaptor.forClass})
     * so Mockito populates the parameterized type reflectively without an unchecked-cast warning.
     */
    @Captor
    private ArgumentCaptor<Consumer<SqsSendOptions<ReportSubmissionService.ReportJobMessage>>> sendCaptor;

    /** System under test, rebuilt per test by {@link #setUp()} with the {@link #DEFAULT_TODAY} clock. */
    private ReportSubmissionService service;

    @BeforeEach
    void setUp() {
        // Build the SUT with the default fixed clock. Collaborators are intentionally NOT stubbed
        // here: under STRICT_STUBS each scenario stubs only what it uses (publish / real-date paths).
        service = serviceWithToday(DEFAULT_TODAY);
    }

    // ---------------------------------------------------------------------------------------------
    // Construction & stubbing helpers.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a SUT bound to a fixed "today" so Monthly/Yearly date derivation is deterministic. Uses
     * the four-argument constructor (the {@link Clock}-accepting test constructor); {@code @InjectMocks}
     * cannot be used because it cannot supply the {@link Clock}.
     *
     * @param today the fixed local date the injected clock reports as "today"
     * @return a fresh service bound to a {@link Clock#fixed(java.time.Instant, ZoneId) fixed clock}
     */
    private ReportSubmissionService serviceWithToday(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(ZONE).toInstant(), ZONE);
        return new ReportSubmissionService(sqsTemplate, awsProperties, dateValidationService, clock);
    }

    /**
     * Stubs the FIFO queue name lookup. Call this <strong>only</strong> in publish-path tests
     * (confirmation {@code 'Y'}/{@code 'y'}); calling it elsewhere would trip the strict-stubs
     * {@code UnnecessaryStubbingException}. The accessor chain mirrors the service's real call
     * {@code awsProperties.getSqs().getReportJobsQueue()}.
     */
    private void stubQueue() {
        when(awsProperties.getSqs().getReportJobsQueue()).thenReturn(FIFO_QUEUE);
    }

    /**
     * Stubs the custom real-date validator so every {@code validateDate(..., "YYYYMMDD")} call reports
     * a valid date (both the start and the end check pass). Note the factory is
     * {@code ofValid()} (not {@code valid()}): {@code valid()} is reserved for the record's component
     * accessor in {@link DateValidationService.DateValidationResult}.
     */
    private void stubRealDatesValid() {
        when(dateValidationService.validateDate(anyString(), eq(DATE_FORMAT)))
                .thenReturn(DateValidationService.DateValidationResult.ofValid());
    }

    // ---- ReportRequest builders (ReportRequest is a plain DTO — instantiated, never mocked). ----

    /**
     * Builds a Monthly-report request.
     *
     * @param confirm the confirmation flag value (may be {@code null})
     * @return a request with the monthly flag set
     */
    private ReportRequest selectMonthly(String confirm) {
        ReportRequest r = new ReportRequest();
        r.setMonthly("Y");
        r.setConfirm(confirm);
        return r;
    }

    /**
     * Builds a Yearly-report request.
     *
     * @param confirm the confirmation flag value (may be {@code null})
     * @return a request with the yearly flag set
     */
    private ReportRequest selectYearly(String confirm) {
        ReportRequest r = new ReportRequest();
        r.setYearly("Y");
        r.setConfirm(confirm);
        return r;
    }

    /**
     * Builds a request with no report type selected (all flags blank/null) &mdash; the COBOL
     * {@code WHEN OTHER} (none-selected) case.
     *
     * @return an empty request
     */
    private ReportRequest selectNone() {
        return new ReportRequest();
    }

    /**
     * Builds a Custom-report request from the six date components plus the confirmation flag.
     *
     * @param sM start month, @param sD start day, @param sY start year
     * @param eM end month,   @param eD end day,   @param eY end year
     * @param confirm the confirmation flag value
     * @return a custom request populated with the supplied components
     */
    private ReportRequest selectCustom(String sM, String sD, String sY,
                                       String eM, String eD, String eY, String confirm) {
        ReportRequest r = new ReportRequest();
        r.setCustom("Y");
        r.setStartMonth(sM);
        r.setStartDay(sD);
        r.setStartYear(sY);
        r.setEndMonth(eM);
        r.setEndDay(eD);
        r.setEndYear(eY);
        r.setConfirm(confirm);
        return r;
    }

    /**
     * Builds a fully-valid custom range {@code 2023-06-01 .. 2023-06-30} with the supplied confirm
     * flag. All six components pass the empty and range stages; the real-date stage outcome is
     * governed by the {@link #dateValidationService} stub.
     *
     * @param confirm the confirmation flag value
     * @return a valid custom-range request
     */
    private ReportRequest validCustom(String confirm) {
        return selectCustom("06", "01", "2023", "06", "30", "2023", confirm);
    }

    // ---------------------------------------------------------------------------------------------
    // SQS verification recipe (zero-warning generics handling).
    // ---------------------------------------------------------------------------------------------

    /**
     * Verifies exactly one SQS publish, replays the captured fluent-builder consumer against a
     * {@code RETURNS_SELF} options mock, asserts the FIFO attributes (queue, message-group id, and a
     * non-blank deduplication id), and returns the captured {@link ReportSubmissionService.ReportJobMessage}
     * payload for the caller to assert. This is the {@code WRITEQ('JOBS')} &rarr; SQS-publish
     * verification; the SUT's {@code send(...)} is fire-and-forget, so its {@code SendResult} return is
     * never stubbed.
     *
     * @return the published message payload
     */
    private ReportSubmissionService.ReportJobMessage captureSinglePublishedMessage() {
        verify(sqsTemplate, times(1)).send(sendCaptor.capture());

        SqsSendOptions<ReportSubmissionService.ReportJobMessage> options = mockSendOptions();
        sendCaptor.getValue().accept(options); // replay the fluent builder lambda onto the mock

        verify(options).queue(FIFO_QUEUE);
        verify(options).messageGroupId(GROUP_ID);
        verify(options).messageDeduplicationId(argThat(s -> s != null && !s.isBlank()));

        ArgumentCaptor<ReportSubmissionService.ReportJobMessage> payloadCaptor =
                ArgumentCaptor.forClass(ReportSubmissionService.ReportJobMessage.class);
        verify(options).payload(payloadCaptor.capture());
        return payloadCaptor.getValue();
    }

    /**
     * Creates the generically-typed fluent {@link SqsSendOptions} mock used to replay the captured
     * builder consumer. Mockito cannot create a generically-typed mock without an unchecked cast, so
     * the suppression is localized here &mdash; this is the single accepted framework-interaction
     * suppression in this file (AAP &sect;0.7.8). {@link Answers#RETURNS_SELF} makes every fluent
     * setter return the same mock so the chained builder calls can be verified individually.
     *
     * @return a self-returning {@link SqsSendOptions} mock parameterized to the report payload type
     */
    @SuppressWarnings("unchecked")
    private static SqsSendOptions<ReportSubmissionService.ReportJobMessage> mockSendOptions() {
        return mock(SqsSendOptions.class, withSettings().defaultAnswer(Answers.RETURNS_SELF));
    }

    // =============================================================================================
    // 5.1 Report-type selection & EVALUATE TRUE ordering (CORPT00C.cbl L212-443).
    // =============================================================================================

    @Nested
    @DisplayName("5.1 Report-type selection & EVALUATE ordering")
    class ReportTypeSelectionAndEvaluateOrder {

        @Test
        @DisplayName("MONTHLY selected → proceeds to publish")
        void monthlySelected_proceedsToPublish() {
            stubQueue();

            ReportSubmissionResponse result =
                    service.submitReport(selectMonthly("Y"));

            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
            verify(sqsTemplate, times(1)).send(any());
        }

        @Test
        @DisplayName("YEARLY selected → proceeds to publish")
        void yearlySelected_proceedsToPublish() {
            stubQueue();

            ReportSubmissionResponse result =
                    service.submitReport(selectYearly("Y"));

            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
            verify(sqsTemplate, times(1)).send(any());
        }

        @Test
        @DisplayName("CUSTOM selected (valid dates) → proceeds to publish")
        void customSelected_validDates_proceedsToPublish() {
            stubRealDatesValid(); // CSUTLDTC replacement: both start & end report valid
            stubQueue();

            ReportSubmissionResponse result =
                    service.submitReport(validCustom("Y"));

            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
            verify(sqsTemplate, times(1)).send(any());
        }

        @Test
        @DisplayName("No report type selected → 'Select a report type to print report...' (no publish)")
        void noTypeSelected_throwsSelectReportType() {
            assertThatThrownBy(() -> service.submitReport(selectNone()))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Select a report type to print report...");

            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("null request → 'Select a report type to print report...' (no publish)")
        void nullRequest_throwsSelectReportType() {
            // A null body carries no report-type selection — the COBOL WHEN OTHER outcome.
            assertThatThrownBy(() -> service.submitReport(null))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Select a report type to print report...");

            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("Both Monthly & Yearly set → MONTHLY wins (EVALUATE order Monthly→Yearly→Custom)")
        void monthlyTakesPrecedenceOverYearly() {
            stubQueue();
            ReportRequest req = selectMonthly("Y");
            req.setYearly("Y"); // both flags set; EVALUATE TRUE must pick MONTHLY first

            service.submitReport(req);

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("MONTHLY");
            // The derived range is the MONTH range of DEFAULT_TODAY (2024-03), not the YEAR range.
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 3, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 3, 31));
        }
    }

    // =============================================================================================
    // 5.2 Monthly date derivation (CORPT00C.cbl L213-238) — deterministic via the fixed clock.
    //     Start = first day of the current month; End = last day of the current month.
    // =============================================================================================

    @Nested
    @DisplayName("5.2 Monthly date derivation")
    class MonthlyDateDerivation {

        @Test
        @DisplayName("today 2024-03-15 → 2024-03-01 .. 2024-03-31 (31-day month)")
        void march2024() {
            service = serviceWithToday(LocalDate.of(2024, 3, 15));
            stubQueue();

            service.submitReport(selectMonthly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("MONTHLY");
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 3, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 3, 31));
        }

        @Test
        @DisplayName("today 2024-04-10 → 2024-04-01 .. 2024-04-30 (30-day month)")
        void april2024() {
            service = serviceWithToday(LocalDate.of(2024, 4, 10));
            stubQueue();

            service.submitReport(selectMonthly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 4, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 4, 30));
        }

        @Test
        @DisplayName("today 2024-02-10 → 2024-02-01 .. 2024-02-29 (leap February)")
        void leapFebruary2024() {
            service = serviceWithToday(LocalDate.of(2024, 2, 10));
            stubQueue();

            service.submitReport(selectMonthly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 2, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        @DisplayName("today 2023-02-10 → 2023-02-01 .. 2023-02-28 (non-leap February)")
        void nonLeapFebruary2023() {
            service = serviceWithToday(LocalDate.of(2023, 2, 10));
            stubQueue();

            service.submitReport(selectMonthly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2023, 2, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2023, 2, 28));
        }

        @Test
        @DisplayName("today 2024-12-20 → 2024-12-01 .. 2024-12-31 (year-end rollover path)")
        void december2024() {
            // Exercises the COBOL "ADD 1 TO month → month>12 → roll year" branch (last day of Dec).
            service = serviceWithToday(LocalDate.of(2024, 12, 20));
            stubQueue();

            service.submitReport(selectMonthly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 12, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 12, 31));
        }
    }

    // =============================================================================================
    // 5.3 Yearly date derivation (CORPT00C.cbl L239-255): Jan 1 .. Dec 31 of the current year.
    // =============================================================================================

    @Nested
    @DisplayName("5.3 Yearly date derivation")
    class YearlyDateDerivation {

        @Test
        @DisplayName("today 2024-03-15 → 2024-01-01 .. 2024-12-31")
        void year2024() {
            service = serviceWithToday(LocalDate.of(2024, 3, 15));
            stubQueue();

            service.submitReport(selectYearly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("YEARLY");
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 12, 31));
        }

        @Test
        @DisplayName("today 2023-07-04 → 2023-01-01 .. 2023-12-31 (year-independence)")
        void year2023() {
            service = serviceWithToday(LocalDate.of(2023, 7, 4));
            stubQueue();

            service.submitReport(selectYearly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("YEARLY");
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2023, 1, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2023, 12, 31));
        }
    }

    // =============================================================================================
    // 5.4 Custom empty-component validation (CORPT00C.cbl L258-303) — fail-fast EVALUATE: only the
    //     first blank component fires, in the order start M/D/Y then end M/D/Y. The empty check runs
    //     on the RAW input, before NUMVAL-C normalization. No collaborator is stubbed (the failure
    //     precedes both the real-date check and the publish).
    // =============================================================================================

    @Nested
    @DisplayName("5.4 Custom empty-component validation (fail-fast)")
    class CustomEmptyValidation {

        @Test
        @DisplayName("start month empty → 'Start Date - Month can NOT be empty...'")
        void startMonthEmpty() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("", "01", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Month can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("start month null → 'Start Date - Month can NOT be empty...' (null branch)")
        void startMonthNull() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom(null, "01", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Month can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("start day empty → 'Start Date - Day can NOT be empty...'")
        void startDayEmpty() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Day can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("start year empty → 'Start Date - Year can NOT be empty...'")
        void startYearEmpty() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "01", "", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Year can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("end month empty → 'End Date - Month can NOT be empty...'")
        void endMonthEmpty() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "01", "2023", "", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - Month can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("end day empty → 'End Date - Day can NOT be empty...'")
        void endDayEmpty() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "01", "2023", "06", "", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - Day can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("end year empty → 'End Date - Year can NOT be empty...'")
        void endYearEmpty() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "01", "2023", "06", "30", "", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - Year can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }
    }

    // =============================================================================================
    // 5.5 Custom range validation (CORPT00C.cbl L329-379) — fail-fast, first failure wins.
    //
    // COBOL parity note: the program runs FUNCTION NUMVAL-C over each component (L305-327) and
    // re-stores the numeric result BEFORE the range tests. A non-numeric component therefore
    // collapses to 0 ("00"/"0000") and the COBOL `IS NOT NUMERIC` guard (and the year fields' only
    // test) can never fire — exactly mirrored by the SUT, where pad2/pad4 of a NUMVAL-C value is
    // always all-digits. The ONLY reachable range failures are month > 12 and day > 31; those are
    // asserted here. The non-numeric / impossible-date inputs are absorbed by NUMVAL-C and surface at
    // the real-date stage instead (see 5.6) — asserting them as range errors would contradict COBOL.
    // =============================================================================================

    @Nested
    @DisplayName("5.5 Custom range validation (month > 12 / day > 31)")
    class CustomRangeValidation {

        @Test
        @DisplayName("start month 13 → 'Start Date - Not a valid Month...'")
        void startMonthOutOfRange() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("13", "01", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Month...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("start day 32 → 'Start Date - Not a valid Day...'")
        void startDayOutOfRange() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "32", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Day...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("end month 13 → 'End Date - Not a valid Month...'")
        void endMonthOutOfRange() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "01", "2023", "13", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - Not a valid Month...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("end day 32 → 'End Date - Not a valid Day...'")
        void endDayOutOfRange() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "01", "2023", "06", "32", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - Not a valid Day...");
            verify(sqsTemplate, never()).send(any());
        }
    }

    // =============================================================================================
    // 5.6 Custom real-date validation (CORPT00C.cbl L388-426) — the CALL 'CSUTLDTC' replacement,
    //     delegated to the mocked DateValidationService. START is validated first, then END.
    //
    // Substitution note: this replaces the COBOL `CALL 'CSUTLDTC'` (LE CEEDAYS) date check. The legacy
    // '2513' MSG-NUM acceptance edge case is intentionally NOT replicated — it has no java.time
    // analogue and is absorbed by strict date semantics (the SUT treats valid()==false as invalid).
    // =============================================================================================

    @Nested
    @DisplayName("5.6 Custom real-date validation (CSUTLDTC → DateValidationService)")
    class CustomRealDateValidation {

        @Test
        @DisplayName("start date invalid → 'Start Date - Not a valid date...' (end never checked)")
        void startDateInvalid() {
            // month 02 / day 30 / year 2023 passes empty + range (day <= 31) but is not a real date.
            when(dateValidationService.validateDate(anyString(), eq(DATE_FORMAT)))
                    .thenReturn(DateValidationService.DateValidationResult.ofInvalid("x")); // START

            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("02", "30", "2023", "02", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid date...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("end date invalid (start valid) → 'End Date - Not a valid date...'")
        void endDateInvalid() {
            // Consecutive returns: 1st call (START) valid, 2nd call (END) invalid — robust to the
            // exact 8-digit string the SUT composes for each.
            when(dateValidationService.validateDate(anyString(), eq(DATE_FORMAT)))
                    .thenReturn(DateValidationService.DateValidationResult.ofValid())      // START
                    .thenReturn(DateValidationService.DateValidationResult.ofInvalid("x")); // END

            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "30", "2023", "02", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("End Date - Not a valid date...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("both dates valid → proceeds past the real-date stage (validateDate called twice)")
        void bothDatesValid_proceeds() {
            stubRealDatesValid(); // both START and END report valid
            // Confirm 'N' so the cascade reaches (and silently cancels at) the confirmation gate,
            // proving both real-date checks passed without reaching a publish.
            ReportSubmissionResponse result =
                    service.submitReport(validCustom("N"));

            assertThat(result.status()).isEqualTo("CANCELLED");
            assertThat(result.jobId()).as("cancelled submission carries no job id").isNull();
            verify(dateValidationService, times(2)).validateDate(anyString(), eq(DATE_FORMAT));
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("non-numeric month is absorbed by NUMVAL-C → surfaces at the real-date stage")
        void nonNumericMonthAbsorbedByNumvalC() {
            // COBOL NUMVAL-C("AB") = 0, re-stored as "00", which passes the month > 12 range guard;
            // the non-numeric value is NOT a "Not a valid Month" error. It is carried to the
            // real-date stage, where the (mocked) validator rejects the impossible "2023-00-01",
            // yielding the start-date message — exactly as CORPT00C's CSUTLDTC would.
            when(dateValidationService.validateDate(anyString(), eq(DATE_FORMAT)))
                    .thenReturn(DateValidationService.DateValidationResult.ofInvalid("x"));

            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("AB", "01", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid date...");
            verify(sqsTemplate, never()).send(any());
        }
    }

    // =============================================================================================
    // 5.7 Fail-fast precedence — proves first-failure-wins ordering across the validation stages
    //     (empty → range → real-date) and start-before-end, and that later stages are NOT reached.
    // =============================================================================================

    @Nested
    @DisplayName("5.7 Fail-fast precedence (first failure wins)")
    class FailFastPrecedence {

        @Test
        @DisplayName("empty start month + invalid end day → empty message wins (empty precedes range)")
        void emptyStageBeatsRangeStage() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("", "01", "2023", "06", "99", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Month can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("start month 13 + start year 20AB → month message wins (month range precedes year)")
        void startMonthRangeBeatsStartYear() {
            // Month range check runs before the year field; additionally, NUMVAL-C absorbs "20AB",
            // so the year guard is unreachable regardless — the month message is the only outcome.
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("13", "01", "20AB", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Month...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("start month + start day both empty → first EVALUATE match (month) wins")
        void firstEvaluateMatchWins() {
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("", "", "2023", "06", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Month can NOT be empty...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("range failure short-circuits the real-date stage (validator never invoked)")
        void rangeStageBeatsRealDateStage() {
            // start day 32 fails the range stage; the real-date stage (DateValidationService) must
            // never be reached. The absence of any stub here, combined with the explicit never()
            // verify, makes strict-stubs the second line of defense for this ordering guarantee.
            assertThatThrownBy(() -> service.submitReport(
                    selectCustom("06", "32", "2023", "02", "30", "2023", "Y")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Start Date - Not a valid Day...");
            verify(dateValidationService, never()).validateDate(anyString(), anyString());
            verify(sqsTemplate, never()).send(any());
        }
    }

    // =============================================================================================
    // 5.8 Confirmation gate (CORPT00C.cbl L462-494) — applies to ALL report types, AFTER date
    //     validation. The prompt embeds the report display name (Monthly/Yearly/Custom). SPACES and
    //     empty are treated as not-confirmed; only Y/y publishes; N/n is a silent cancel.
    // =============================================================================================

    @Nested
    @DisplayName("5.8 Confirmation gate")
    class ConfirmationGate {

        @Test
        @DisplayName("null confirm (Monthly) → 'Please confirm to print the Monthly report...'")
        void nullConfirmMonthly() {
            assertThatThrownBy(() -> service.submitReport(selectMonthly(null)))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Please confirm to print the Monthly report...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("empty confirm (Yearly) → 'Please confirm to print the Yearly report...'")
        void emptyConfirmYearly() {
            assertThatThrownBy(() -> service.submitReport(selectYearly("")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Please confirm to print the Yearly report...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("single-space confirm (Custom) → 'Please confirm to print the Custom report...'")
        void spaceConfirmCustom() {
            // CUSTOM must pass the real-date stage to reach the gate; stub both dates valid.
            // COBOL treats SPACES as not-confirmed (CONFIRM-NO false), so the gate prompts again.
            stubRealDatesValid();
            assertThatThrownBy(() -> service.submitReport(validCustom(" ")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("Please confirm to print the Custom report...");
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("confirm 'Y' → proceeds to publish")
        void uppercaseYPublishes() {
            stubQueue();
            service.submitReport(selectMonthly("Y"));
            verify(sqsTemplate, times(1)).send(any());
        }

        @Test
        @DisplayName("confirm 'y' (lowercase) → proceeds to publish")
        void lowercaseYPublishes() {
            stubQueue();
            service.submitReport(selectMonthly("y"));
            verify(sqsTemplate, times(1)).send(any());
        }

        @Test
        @DisplayName("confirm 'N' → silent cancel (no exception, no publish, status=CANCELLED)")
        void uppercaseNSilentCancel() {
            ReportSubmissionResponse result =
                    service.submitReport(selectMonthly("N"));

            assertThat(result.status()).isEqualTo("CANCELLED");
            assertThat(result.jobId()).as("cancelled submission carries no job id").isNull();
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("confirm 'n' (lowercase) → silent cancel")
        void lowercaseNSilentCancel() {
            ReportSubmissionResponse result =
                    service.submitReport(selectMonthly("n"));

            assertThat(result.status()).isEqualTo("CANCELLED");
            assertThat(result.jobId()).as("cancelled submission carries no job id").isNull();
            verify(sqsTemplate, never()).send(any());
        }

        @Test
        @DisplayName("confirm 'X' (invalid, Monthly) → '\"X\" is not a valid value to confirm...'")
        void invalidConfirmMonthly() {
            assertThatThrownBy(() -> service.submitReport(selectMonthly("X")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("\"X\" is not a valid value to confirm...");
            verify(sqsTemplate, never()).send(any());
        }
    }

    // =============================================================================================
    // 5.9 SQS publish (CORPT00C.cbl L496-535 — TDQ WRITEQ('JOBS') / JES submission → one SQS publish).
    //     SqsTemplate is MOCKED; the publish is verified by capturing the fluent-builder consumer and
    //     replaying it onto a RETURNS_SELF options mock (see captureSinglePublishedMessage()). The real
    //     LocalStack/Testcontainers SQS round-trip belongs to a separate *IT.java integration test —
    //     deliberately NOT exercised here.
    // =============================================================================================

    @Nested
    @DisplayName("5.9 SQS publish (mocked — verify once with correct payload/attributes)")
    class SqsPublish {

        @Test
        @DisplayName("MONTHLY confirmed → one publish to the FIFO queue with the month range payload")
        void monthlyPublishesCorrectPayload() {
            stubQueue();

            service.submitReport(selectMonthly("Y"));

            // captureSinglePublishedMessage() asserts: exactly one send(), queue == carddemo-report-jobs.fifo,
            // messageGroupId == report-jobs, and a non-blank (UUID) messageDeduplicationId.
            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("MONTHLY");
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 3, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 3, 31));
        }

        @Test
        @DisplayName("YEARLY confirmed → one publish with the calendar-year range payload")
        void yearlyPublishesCorrectPayload() {
            stubQueue();

            service.submitReport(selectYearly("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("YEARLY");
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2024, 12, 31));
        }

        @Test
        @DisplayName("CUSTOM confirmed → one publish with the parsed custom range payload")
        void customPublishesCorrectPayload() {
            stubRealDatesValid();
            stubQueue();

            service.submitReport(validCustom("Y"));

            ReportSubmissionService.ReportJobMessage msg = captureSinglePublishedMessage();
            assertThat(msg.reportType()).isEqualTo("CUSTOM");
            assertThat(msg.startDate()).isEqualTo(LocalDate.of(2023, 6, 1));
            assertThat(msg.endDate()).isEqualTo(LocalDate.of(2023, 6, 30));
        }

        @Test
        @DisplayName("each submission uses a distinct deduplication id (fresh UUID per publish)")
        void dedupIdsAreUniquePerSubmission() {
            stubQueue();

            service.submitReport(selectMonthly("Y"));
            service.submitReport(selectMonthly("Y"));

            verify(sqsTemplate, times(2)).send(sendCaptor.capture());
            List<Consumer<SqsSendOptions<ReportSubmissionService.ReportJobMessage>>> consumers =
                    sendCaptor.getAllValues();

            SqsSendOptions<ReportSubmissionService.ReportJobMessage> options1 = mockSendOptions();
            SqsSendOptions<ReportSubmissionService.ReportJobMessage> options2 = mockSendOptions();
            consumers.get(0).accept(options1);
            consumers.get(1).accept(options2);

            ArgumentCaptor<String> dedup1 = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> dedup2 = ArgumentCaptor.forClass(String.class);
            verify(options1).messageDeduplicationId(dedup1.capture());
            verify(options2).messageDeduplicationId(dedup2.capture());

            assertThat(dedup1.getValue()).isNotBlank();
            assertThat(dedup2.getValue()).isNotBlank();
            assertThat(dedup1.getValue()).isNotEqualTo(dedup2.getValue());
        }
    }

    // =============================================================================================
    // 5.10 Response contract (docs/api-contracts.md §5.7) — the returned ReportSubmissionResponse.
    //      A confirmed ('Y') submission yields status="SUBMITTED" with a generated, non-blank jobId
    //      and the canonical machine reportType token; a cancel ('N') yields status="CANCELLED" with a
    //      null jobId. The derived/parsed date RANGE is carried by the SQS payload (ReportJobMessage)
    //      and is asserted exhaustively in §5.2/§5.3/§5.9 — it is not part of the API response shape.
    // =============================================================================================

    @Nested
    @DisplayName("5.10 Response contract (api-contracts §5.7)")
    class ResponseContract {

        @Test
        @DisplayName("MONTHLY confirmed → SUBMITTED, non-blank jobId, reportType=MONTHLY")
        void monthlySuccessResult() {
            stubQueue();

            ReportSubmissionResponse result =
                    service.submitReport(selectMonthly("Y"));

            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
            assertThat(result.reportType()).isEqualTo("MONTHLY");
        }

        @Test
        @DisplayName("YEARLY confirmed → SUBMITTED, non-blank jobId, reportType=YEARLY")
        void yearlySuccessResult() {
            stubQueue();

            ReportSubmissionResponse result =
                    service.submitReport(selectYearly("Y"));

            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
            assertThat(result.reportType()).isEqualTo("YEARLY");
        }

        @Test
        @DisplayName("CUSTOM confirmed → SUBMITTED, non-blank jobId, reportType=CUSTOM")
        void customSuccessResult() {
            stubRealDatesValid();
            stubQueue();

            ReportSubmissionResponse result =
                    service.submitReport(validCustom("Y"));

            assertThat(result.status()).isEqualTo("SUBMITTED");
            assertThat(result.jobId()).as("queued job id (api-contracts §5.7)").isNotBlank();
            assertThat(result.reportType()).isEqualTo("CUSTOM");
        }

        @Test
        @DisplayName("cancel ('N') → CANCELLED, null jobId, reportType still populated")
        void cancelResult() {
            ReportSubmissionResponse result =
                    service.submitReport(selectMonthly("N"));

            assertThat(result.status()).isEqualTo("CANCELLED");
            assertThat(result.jobId()).as("cancelled submission carries no job id").isNull();
            // COBOL cleared the screen and suppressed submission; the machine report-type token is
            // still carried back for the caller's convenience.
            assertThat(result.reportType()).isEqualTo("MONTHLY");
        }
    }
}
