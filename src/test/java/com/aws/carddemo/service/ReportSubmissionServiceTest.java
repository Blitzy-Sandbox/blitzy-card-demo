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
package com.aws.carddemo.service;

// ---------------------------------------------------------------------------
// Project-internal imports (AAP §0.5.5 Cross-File Test Dependencies; file
// schema internal_imports).
//
//   * TestFixtures — single source of truth for shared test constants. This
//     test uses TestFixtures.Dates.FIXED_CLOCK_INSTANT (the deterministic
//     2024-01-15T00:00:00Z instant that anchors the MONTHLY and YEARLY
//     report date-range assertions) and TestFixtures.Dates.REPORT_START_DATE
//     / TestFixtures.Dates.REPORT_END_DATE (the canonical CUSTOM-mode
//     start/end-date fixture pair captured from the baseline
//     transaction_report.txt).
//
//     Schema authority: file_schema.internal_imports[0] declares this
//     dependency explicitly.
//
//   * ReportSubmissionService / ReportSubmissionRequest /
//     ReportSubmissionResult / ReportJobDispatcher / ReportJobParameters /
//     ReportJobHandle — the production classes under test. No explicit
//     imports because they share this test's package
//     (com.aws.carddemo.service); Java resolves simple-named references via
//     package membership. This matches the convention established by every
//     other test under com.aws.carddemo.service (AAP §0.10.10 style
//     consistency).
// ---------------------------------------------------------------------------
import com.aws.carddemo.testsupport.TestFixtures;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter API (file schema external_imports; AAP §0.10.7 framework
// constraint — JUnit 5 only, never JUnit 4 / Vintage).
//
//   * @BeforeEach — re-instantiates the ReportSubmissionService system under
//     test before every method; paired with Mockito's default per-method
//     @Mock instantiation to enforce strict test isolation (AAP §0.10.9).
//     Each test sees a fresh ReportJobDispatcher mock and a fresh service
//     instance.
//   * @DisplayName — human-readable scenario names on the outer class and
//     on each @Nested grouping plus the individual @Test methods (AAP
//     §0.10.6 naming convention).
//   * @Nested — groups HappyPath / RejectPaths / AsyncDispatch scenarios
//     into the three semantic sections that match this test class's
//     exports.members_exposed schema entries.
//   * @Test — single-execution test marker for non-parameterised methods.
//   * @ExtendWith — wires MockitoExtension below.
// ---------------------------------------------------------------------------
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// ---------------------------------------------------------------------------
// JUnit 5 Jupiter Params (file schema external_imports for junit-jupiter-
// params).
//
//   * @ParameterizedTest + @ValueSource(strings = ...) — drives the invalid
//     CUSTOM start-date and end-date rejection scenarios across the four
//     COBOL-equivalent malformed-input categories: month overflow
//     (2024-13-01), day overflow (2024-02-30), non-numeric input
//     (abcd-01-15), and wrong separator format (2024/06/01). These cover
//     the CSUTLDTC date-format variants enumerated in CORPT00C.cbl per AAP
//     §0.1.1 R6 edge-case coverage requirement.
// ---------------------------------------------------------------------------
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// ---------------------------------------------------------------------------
// Mockito 5 (file schema external_imports for mockito-core; resolved via
// spring-boot-starter-test BOM 3.3.x to Mockito 5.11.0 per setup log).
//
//   * @Mock — Mockito field-injection annotation; MockitoExtension processes
//     this annotation and produces a fresh ReportJobDispatcher mock per
//     @Test method, guaranteeing test isolation.
//   * ArgumentCaptor — captures the ReportJobParameters instance passed to
//     dispatcher.dispatch(...) so the test can assert on the typed
//     LocalDate startDate/endDate values and the verbatim "TRNRPT00" JCL
//     JOB name without relying on toString() or argument-matching DSL.
//   * MockitoExtension — activates STRICT_STUBS strictness by default
//     (AAP §0.10.1: "Mockito strictness is STRICT_STUBS; unused stubs
//     raise UnnecessaryStubbingException."). Each reject-path test
//     deliberately avoids stubbing the dispatcher so the never() assertion
//     proves the production code never reaches the dispatch boundary on
//     rejected calls.
// ---------------------------------------------------------------------------
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// ---------------------------------------------------------------------------
// JDK 17 standard-library imports (file schema external_imports for
// java.time).
//
//   * Clock — injected dependency on ReportSubmissionService. Tests use a
//     Clock.fixed(...) instance to anchor the MONTHLY / YEARLY mode date
//     computation at a deterministic instant; production wiring would
//     inject Clock.systemDefaultZone().
//   * Instant — the parse target for the FIXED_CLOCK_INSTANT constant
//     (2024-01-15T00:00:00Z).
//   * LocalDate — the assertion target for the dispatched
//     ReportJobParameters start/end-date fields. Direct equality
//     comparison against LocalDate.of(2024, 1, 1) etc. is the canonical
//     way to assert COBOL-equivalent date-range computations.
//   * ZoneOffset — the time-zone argument to Clock.fixed(...). UTC ensures
//     the fixed clock is stable regardless of the host time zone, matching
//     the COBOL FUNCTION CURRENT-DATE semantic (which always returns a
//     local-time value at the mainframe's configured time zone, but the
//     migration uses UTC because it is the cluster-wide normalisation
//     for downstream reporting).
// ---------------------------------------------------------------------------
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

// ---------------------------------------------------------------------------
// Static imports — AssertJ fluent DSL + Mockito DSL (AAP §0.10.10 "All
// assertions use AssertJ (fluent) rather than mixing AssertJ + Hamcrest +
// Assertions.assertEquals"; all Mockito stubs use the
// when(...).thenReturn(...) style).
//
//   * Assertions.assertThat — fluent assertion entry point used throughout.
//   * ArgumentMatchers.any — relaxes argument matching in the never()
//     verifications (the assertions care about whether the dispatch method
//     is called at all, not about the specific argument value).
//   * Mockito.never — verifies that dispatch was NOT invoked; used to
//     prove that every reject path short-circuits BEFORE the dispatcher
//     boundary (defence-in-depth invariant: no TDQ submission on a
//     rejected request).
//   * Mockito.verify — interaction assertion; pairs with .never() and
//     with ArgumentCaptor for the happy-path captor assertions.
//   * Mockito.when — stub configuration; sets up the dispatcher's return
//     value (a ReportJobHandle) on the happy paths.
// ---------------------------------------------------------------------------
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportSubmissionService}, the migrated Java
 * equivalent of the 649-line CICS COBOL program {@code app/cbl/CORPT00C.cbl}
 * (TRANID {@code CR00}, the transaction-report submission dispatcher). The
 * service submits a transaction-report batch job via an asynchronous
 * dispatcher ({@link ReportJobDispatcher}) that replaces the original
 * mainframe TDQ (Transient Data Queue) integration with JES.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl Three Report Modes</h2>
 *
 * <ul>
 *   <li><b>Monthly</b> — date range = first to last day of current month
 *       (COBOL parity: lines 213-238 of CORPT00C.cbl).</li>
 *   <li><b>Yearly</b> — date range = first to last day of current year
 *       (lines 239-255).</li>
 *   <li><b>Custom</b> — user-supplied start and end dates, both validated
 *       via {@code CSUTLDTC} (the COBOL {@code CEEDAYS} intrinsic wrapper,
 *       lines 256-436).</li>
 * </ul>
 *
 * <h2>Reject Conditions (COBOL parity per AAP §0.10.4)</h2>
 *
 * <p>The test suite asserts the load-bearing phrases from the original
 * COBOL strings via AssertJ's {@code .containsIgnoringCase(...)} matchers:
 *
 * <ul>
 *   <li>{@link RejectPaths#submit_noMode_rejectsWithSelectReportTypeMessage()}
 *       — {@code "Please select a report type..."} (mirrors COBOL line
 *       438 {@code 'Select a report type to print report...'}); anchored
 *       on {@code "select"} and {@code "report"}.</li>
 *   <li>{@link RejectPaths#submit_customMissingStartDate_rejectsWithStartDateEmptyMessage()}
 *       — {@code "Start Date can NOT be empty"} (mirrors COBOL lines 261,
 *       268, 275); anchored on {@code "start date"} and {@code "empty"}.</li>
 *   <li>{@link RejectPaths#submit_customMissingEndDate_rejectsWithEndDateEmptyMessage()}
 *       — {@code "End Date can NOT be empty"} (mirrors COBOL lines 282,
 *       289, 296); anchored on {@code "end date"} and {@code "empty"}.</li>
 *   <li>{@link RejectPaths#submit_customInvalidStartDate_rejectsWithStartDateInvalidMessage(String)}
 *       — {@code "Start Date - Not a valid date..."} (verbatim COBOL line
 *       400); anchored on {@code "start date"}.</li>
 *   <li>{@link RejectPaths#submit_customInvalidEndDate_rejectsWithEndDateInvalidMessage(String)}
 *       — {@code "End Date - Not a valid date..."} (verbatim COBOL line
 *       420); anchored on {@code "end date"}.</li>
 *   <li>{@link RejectPaths#submit_startAfterEnd_rejectsWithEndAfterStartMessage()}
 *       — {@code "End Date should be greater than Start Date"}
 *       (Java-migration addition; no COBOL equivalent); anchored on both
 *       {@code "end date"} and {@code "start date"}.</li>
 *   <li>{@link RejectPaths#submit_confirmationNo_rejectsWithCancelledMessage()}
 *       — {@code "Confirmation cancelled by user"} (Java-migration
 *       addition; the COBOL {@code 'N'} branch at lines 480-483 produces
 *       no message); anchored on {@code "cancel"}.</li>
 * </ul>
 *
 * <h2>JES/TDQ Submission Constants</h2>
 *
 * <ul>
 *   <li>JCL JOB name: {@code TRNRPT00} (verbatim COBOL line 84;
 *       asserted by {@link AsyncDispatch#submit_validRequest_dispatchesWithCorrectJobName()})</li>
 *   <li>JCL PROC: {@code TRANREPT} (verbatim COBOL line 94)</li>
 * </ul>
 *
 * <h2>Test Coverage Matrix</h2>
 *
 * <p>The 12 test methods (3 happy + 7 reject + 2 dispatch) cover every
 * branch of the production submission logic:
 *
 * <table border="1">
 *   <caption>ReportSubmissionService test coverage matrix</caption>
 *   <tr><th>Branch</th><th>Test method</th></tr>
 *   <tr><td>Happy path — MONTHLY mode</td>
 *       <td>{@link HappyPath#submit_monthly_dispatchesWithMonthRange()}</td></tr>
 *   <tr><td>Happy path — YEARLY mode</td>
 *       <td>{@link HappyPath#submit_yearly_dispatchesWithYearRange()}</td></tr>
 *   <tr><td>Happy path — CUSTOM with valid dates</td>
 *       <td>{@link HappyPath#submit_customWithValidDates_dispatchesWithProvidedDates()}</td></tr>
 *   <tr><td>Reject — empty mode (COBOL line 438)</td>
 *       <td>{@link RejectPaths#submit_noMode_rejectsWithSelectReportTypeMessage()}</td></tr>
 *   <tr><td>Reject — CUSTOM empty start date (COBOL lines 259-279)</td>
 *       <td>{@link RejectPaths#submit_customMissingStartDate_rejectsWithStartDateEmptyMessage()}</td></tr>
 *   <tr><td>Reject — CUSTOM empty end date (COBOL lines 280-300)</td>
 *       <td>{@link RejectPaths#submit_customMissingEndDate_rejectsWithEndDateEmptyMessage()}</td></tr>
 *   <tr><td>Reject — invalid start date (COBOL line 400)</td>
 *       <td>{@link RejectPaths#submit_customInvalidStartDate_rejectsWithStartDateInvalidMessage(String)}</td></tr>
 *   <tr><td>Reject — invalid end date (COBOL line 420)</td>
 *       <td>{@link RejectPaths#submit_customInvalidEndDate_rejectsWithEndDateInvalidMessage(String)}</td></tr>
 *   <tr><td>Reject — start after end (Java-migration addition)</td>
 *       <td>{@link RejectPaths#submit_startAfterEnd_rejectsWithEndAfterStartMessage()}</td></tr>
 *   <tr><td>Reject — confirmation cancelled (COBOL lines 480-483)</td>
 *       <td>{@link RejectPaths#submit_confirmationNo_rejectsWithCancelledMessage()}</td></tr>
 *   <tr><td>Dispatch — JCL JOB name parity</td>
 *       <td>{@link AsyncDispatch#submit_validRequest_dispatchesWithCorrectJobName()}</td></tr>
 *   <tr><td>Dispatch — handle round-trip</td>
 *       <td>{@link AsyncDispatch#submit_validRequest_returnsDispatcherHandle()}</td></tr>
 * </table>
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>Tests instantiate the real {@link ReportSubmissionService} via its
 * public two-argument constructor (dispatcher + clock) and assert on the
 * {@link ReportSubmissionResult} returned by the real
 * {@link ReportSubmissionService#submit(ReportSubmissionRequest)} method.
 * The only mocked collaborator is the {@link ReportJobDispatcher}
 * (downstream-service-call boundary, the only mock category permitted
 * under AAP §0.10.1 for this service). The {@link Clock} field is also
 * injected, but tests use a real {@code Clock.fixed(...)} instance rather
 * than a mock — clocks are time sources, not business logic, so a fixed
 * instance is the cleanest way to provide deterministic behaviour. No
 * business logic — date computation, validation cascade, dispatch
 * composition — is duplicated in any test body; the tests assert only
 * on observable outputs ({@link ReportSubmissionResult#isSuccess()},
 * {@link ReportSubmissionResult#getMessage()},
 * {@link ReportSubmissionResult#getJobHandle()}, and the
 * {@link ReportJobParameters} captured by {@link ArgumentCaptor}).
 *
 * <h2>Defence-in-Depth Invariant</h2>
 *
 * <p>Every reject-path test includes the
 * {@code verify(dispatcher, never()).dispatch(any())} invariant. This
 * proves that the production code's reject paths short-circuit BEFORE the
 * dispatcher boundary, eliminating the operationally-critical failure mode
 * where a malformed request silently triggers a batch-job submission.
 * Combined with Mockito's default {@code STRICT_STUBS} mode (which raises
 * {@link org.mockito.exceptions.misusing.UnnecessaryStubbingException} on
 * any unused stub), these assertions provide complete coverage of the
 * "no TDQ write on rejection" contract that COBOL CORPT00C.cbl establishes
 * implicitly by gating {@code PERFORM SUBMIT-JOB-TO-INTRDR} (line 238 /
 * 255 / 435) behind the validation cascade.
 *
 * @see ReportSubmissionService
 * @see ReportSubmissionRequest
 * @see ReportSubmissionResult
 * @see ReportJobDispatcher
 * @see ReportJobParameters
 * @see ReportJobHandle
 * @see TestFixtures.Dates
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportSubmissionService — CORPT00C.cbl migration parity")
final class ReportSubmissionServiceTest {

    /**
     * Mocked {@link ReportJobDispatcher} — the asynchronous job-dispatch
     * boundary the production {@link ReportSubmissionService} delegates to.
     * Per AAP §0.10.1, the only mocked collaborator
     * (downstream-service-call is one of the four permitted mock
     * categories). Re-created per {@code @Test} method by
     * {@link MockitoExtension}, ensuring strict isolation between
     * scenarios. The {@link Clock} dependency is injected as a real
     * fixed-time instance rather than a mock because clocks are time
     * sources, not business logic, so a real {@code Clock.fixed(...)} is
     * the cleanest way to provide deterministic date behaviour.
     */
    @Mock
    private ReportJobDispatcher dispatcher;

    /**
     * Deterministic fixed clock anchored at
     * {@link TestFixtures.Dates#FIXED_CLOCK_INSTANT}
     * ({@code 2024-01-15T00:00:00Z}). The {@code MONTHLY} and
     * {@code YEARLY} tests assert on the date window computed by
     * {@link ReportSubmissionService} from this clock:
     * <ul>
     *   <li>{@code MONTHLY}: window = 2024-01-01 → 2024-01-31</li>
     *   <li>{@code YEARLY}: window = 2024-01-01 → 2024-12-31</li>
     * </ul>
     *
     * <p>The clock is constructed once per test class (this field is
     * {@code final}) because {@code Clock.fixed(...)} instances are
     * immutable — there is no per-test mutation to worry about. Using
     * {@link ZoneOffset#UTC} ensures the fixed clock is stable regardless
     * of the host time zone; the production wiring layer is expected to
     * use the same UTC normalisation for cluster-wide date consistency
     * across downstream reporting systems.
     */
    private final Clock fixedClock = Clock.fixed(
        Instant.parse(TestFixtures.Dates.FIXED_CLOCK_INSTANT),
        ZoneOffset.UTC);

    /**
     * System under test — the real {@link ReportSubmissionService}
     * instance. Re-created per {@code @Test} via {@link #setUp()} to
     * mirror the recreation of the {@link #dispatcher} mock collaborator
     * above and to prevent any accidental state leak between tests (the
     * production service is currently stateless aside from its two
     * collaborator fields, but the per-method reset is defensive for
     * future state additions).
     */
    private ReportSubmissionService service;

    /**
     * Constructs a fresh {@link ReportSubmissionService} before every test
     * method, injecting the freshly-instantiated {@link #dispatcher} mock
     * and the deterministic {@link #fixedClock}. The combination of
     * per-method {@code @Mock} instantiation (driven by
     * {@link MockitoExtension}) and per-method service construction here
     * guarantees that no stub or interaction from one test leaks into
     * another (AAP §0.10.9 test isolation).
     */
    @BeforeEach
    void setUp() {
        service = new ReportSubmissionService(dispatcher, fixedClock);
    }

    // =========================================================================
    // HAPPY PATH — three report modes
    //
    // CORPT00C.cbl PROCESS-ENTER-KEY (lines 208-456) implements a three-way
    // EVALUATE that selects the report mode based on the BMS radio button:
    //   * MONTHLYI (line 213) → first/last day of current month
    //   * YEARLYI  (line 239) → first/last day of current year
    //   * CUSTOMI  (line 256) → user-supplied start/end dates
    //
    // The three @Test methods below cover each mode's date computation and
    // assert that the dispatched ReportJobParameters carry the correct
    // typed LocalDate values plus the verbatim "TRNRPT00" JCL JOB name.
    // =========================================================================

    /**
     * Happy-path scenarios for
     * {@link ReportSubmissionService#submit(ReportSubmissionRequest)}.
     *
     * <p>Three tests exercise the three COBOL-equivalent report modes
     * (MONTHLY / YEARLY / CUSTOM), each asserting:
     * <ol>
     *   <li>{@link ReportSubmissionResult#isSuccess()} returns {@code true}.</li>
     *   <li>The dispatcher was invoked exactly once (via
     *       {@link ArgumentCaptor}-driven {@code verify(...)}).</li>
     *   <li>The captured {@link ReportJobParameters} carry the correct
     *       {@link LocalDate} start/end-date values (asserted directly
     *       against {@code LocalDate.of(...)} literals).</li>
     *   <li>(MONTHLY only) The captured parameters carry the verbatim
     *       {@code "TRNRPT00"} JCL JOB name.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Happy path — three report modes")
    class HappyPath {

        /**
         * MONTHLY mode happy path. With the fixed clock at
         * {@code 2024-01-15T00:00:00Z}, the COBOL-equivalent monthly window
         * is the first → last day of January 2024 ({@code 2024-01-01} →
         * {@code 2024-01-31}).
         *
         * <p>COBOL parity: lines 213-238 of CORPT00C.cbl, where the workflow:
         * <ol>
         *   <li>Sets {@code WS-START-DATE} to {@code WS-CURDATE-YEAR ||
         *       '-' || WS-CURDATE-MONTH || '-01'} (lines 217-220).</li>
         *   <li>Computes the last day of the month via the integer-date
         *       arithmetic trick at lines 223-231 (advance month by one,
         *       set day to 1, then subtract 1 day to get the last day of
         *       the original month — natively handles leap years and
         *       year roll-over).</li>
         *   <li>Calls {@code PERFORM SUBMIT-JOB-TO-INTRDR} (line 238).</li>
         * </ol>
         * The Java migration replaces this with the equivalent
         * {@code LocalDate.withDayOfMonth(1)} / {@code .lengthOfMonth()}
         * call.
         *
         * <p>The test asserts on the captured {@link ReportJobParameters}
         * (via {@link ArgumentCaptor}) rather than on the request fields,
         * proving that the production code performs the date computation
         * itself rather than echoing test inputs (AAP §0.10.1 Require Test
         * Coverage rule: "Tests MUST NOT reimplement any business or
         * calculation logic inside test bodies").
         */
        @Test
        @DisplayName("submit(MONTHLY) computes month-range and dispatches job")
        void submit_monthly_dispatchesWithMonthRange() {
            // Arrange — populate the request with MONTHLY mode and a Y
            // confirmation so the request passes both the mode-selection
            // gate and the confirmation gate. The fixed clock at
            // 2024-01-15T00:00:00Z anchors the expected window.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("MONTHLY");
            request.setConfirmation("Y");

            // Stub the dispatcher to return a placeholder handle. Mockito
            // STRICT_STUBS will not fail because the production code WILL
            // call dispatch(...) on the happy path.
            when(dispatcher.dispatch(any())).thenReturn(ReportJobHandle.of("TRNRPT00-001"));

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — success flag is true.
            assertThat(result.isSuccess()).isTrue();

            // Assert (2) — the dispatcher was invoked exactly once with the
            // computed parameters. Capture the argument so we can assert
            // on each field individually.
            ArgumentCaptor<ReportJobParameters> captor =
                ArgumentCaptor.forClass(ReportJobParameters.class);
            verify(dispatcher).dispatch(captor.capture());
            ReportJobParameters params = captor.getValue();

            // Assert (3) — the typed LocalDate values match the
            // COBOL-equivalent monthly window for fixed clock 2024-01-15:
            // start = 2024-01-01, end = 2024-01-31.
            assertThat(params.getStartDate())
                .as("MONTHLY mode start date = first day of current month")
                .isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(params.getEndDate())
                .as("MONTHLY mode end date = last day of current month")
                .isEqualTo(LocalDate.of(2024, 1, 31));

            // Assert (4) — the JCL JOB name matches the COBOL literal
            // "TRNRPT00" (line 84 of CORPT00C.cbl). Asserting this on the
            // first happy-path test plus the dedicated AsyncDispatch test
            // provides defence-in-depth for the load-bearing job-name
            // constant.
            assertThat(params.getJobName())
                .as("JCL JOB name 'TRNRPT00' per CORPT00C line 84")
                .isEqualTo("TRNRPT00");
        }

        /**
         * YEARLY mode happy path. With the fixed clock at
         * {@code 2024-01-15T00:00:00Z}, the COBOL-equivalent yearly window
         * is the first day of January → December 31 of 2024
         * ({@code 2024-01-01} → {@code 2024-12-31}).
         *
         * <p>COBOL parity: lines 239-255 of CORPT00C.cbl, where the
         * workflow sets:
         * <ul>
         *   <li>{@code WS-START-DATE} = {@code WS-CURDATE-YEAR || '-01-01'}
         *       (lines 243-247).</li>
         *   <li>{@code WS-END-DATE} = {@code WS-CURDATE-YEAR || '-12-31'}
         *       (lines 250-253).</li>
         * </ul>
         * The Java migration uses {@code LocalDate.withDayOfYear(1)} for
         * the start and {@code .withMonth(12).withDayOfMonth(31)} for the
         * end, producing the identical 12-month window.
         *
         * <p>The test does not re-assert the JCL JOB name (covered by the
         * MONTHLY test and the dedicated AsyncDispatch test) so the
         * assertions stay focused on the year-boundary computation.
         */
        @Test
        @DisplayName("submit(YEARLY) computes year-range and dispatches job")
        void submit_yearly_dispatchesWithYearRange() {
            // Arrange — populate the request with YEARLY mode.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("YEARLY");
            request.setConfirmation("Y");

            when(dispatcher.dispatch(any())).thenReturn(ReportJobHandle.of("TRNRPT00-002"));

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — success flag is true.
            assertThat(result.isSuccess()).isTrue();

            // Assert (2) — capture the dispatched parameters.
            ArgumentCaptor<ReportJobParameters> captor =
                ArgumentCaptor.forClass(ReportJobParameters.class);
            verify(dispatcher).dispatch(captor.capture());
            ReportJobParameters params = captor.getValue();

            // Assert (3) — the typed LocalDate values match the
            // COBOL-equivalent yearly window for fixed clock 2024-01-15:
            // start = 2024-01-01, end = 2024-12-31.
            assertThat(params.getStartDate())
                .as("YEARLY mode start date = first day of current year")
                .isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(params.getEndDate())
                .as("YEARLY mode end date = December 31 of current year")
                .isEqualTo(LocalDate.of(2024, 12, 31));
        }

        /**
         * CUSTOM mode happy path with valid user-supplied dates. The COBOL
         * workflow (lines 256-436 of CORPT00C.cbl) validates the user
         * inputs via {@code CSUTLDTC} and then submits the job with
         * {@code WS-START-DATE} / {@code WS-END-DATE} populated verbatim
         * from {@code SDTMMI} / {@code SDTDDI} / {@code SDTYYYYI} etc.
         * (lines 381-386). The Java migration replicates this by
         * strict-ISO parsing the request's start/end fields and passing
         * the resulting {@link LocalDate} values through to the
         * dispatcher without modification.
         *
         * <p>The fixture window {@code 2023-06-01} → {@code 2023-06-30}
         * is deliberately a full calendar month so the assertion is
         * unambiguous and avoids any accidental overlap with the
         * MONTHLY / YEARLY computations driven by the fixed clock at
         * 2024-01-15.
         */
        @Test
        @DisplayName("submit(CUSTOM, validDates) dispatches with provided dates")
        void submit_customWithValidDates_dispatchesWithProvidedDates() {
            // Arrange — populate the request with CUSTOM mode and the
            // canonical fixture window (June 2023).
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("CUSTOM");
            request.setStartDate("2023-06-01");
            request.setEndDate("2023-06-30");
            request.setConfirmation("Y");

            when(dispatcher.dispatch(any())).thenReturn(ReportJobHandle.of("TRNRPT00-003"));

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — success flag is true.
            assertThat(result.isSuccess()).isTrue();

            // Assert (2) — capture the dispatched parameters.
            ArgumentCaptor<ReportJobParameters> captor =
                ArgumentCaptor.forClass(ReportJobParameters.class);
            verify(dispatcher).dispatch(captor.capture());
            ReportJobParameters params = captor.getValue();

            // Assert (3) — the typed LocalDate values match the
            // user-supplied window exactly, proving the production code
            // passes the parsed values through without modification.
            assertThat(params.getStartDate())
                .as("CUSTOM mode start date = user-supplied value")
                .isEqualTo(LocalDate.of(2023, 6, 1));
            assertThat(params.getEndDate())
                .as("CUSTOM mode end date = user-supplied value")
                .isEqualTo(LocalDate.of(2023, 6, 30));
        }
    }

    // =========================================================================
    // REJECT PATHS — validation and confirmation
    //
    // Seven reject branches: five inherited from COBOL parity (no mode,
    // empty start date, empty end date, invalid start date, invalid end
    // date) plus two Java-migration additions (start-after-end ordering
    // check, explicit confirmation-cancelled message). The production code
    // performs the rejects in the documented order:
    //   1. Mode unset / unrecognised → MSG_SELECT_REPORT_TYPE
    //   2. (CUSTOM only) start date empty → MSG_START_DATE_EMPTY
    //   3. (CUSTOM only) end date empty → MSG_END_DATE_EMPTY
    //   4. (CUSTOM only) start date unparseable → MSG_START_DATE_INVALID
    //   5. (CUSTOM only) end date unparseable → MSG_END_DATE_INVALID
    //   6. (CUSTOM only) start > end → MSG_END_BEFORE_START
    //   7. confirmation != "Y"/"y" → MSG_CANCELLED
    //
    // The never() assertions on every reject test verify the
    // defence-in-depth invariant: the dispatcher is NEVER invoked on a
    // rejected request (no TDQ write, no batch-job submission).
    // =========================================================================

    /**
     * Reject-path scenarios for
     * {@link ReportSubmissionService#submit(ReportSubmissionRequest)}.
     *
     * <p>Seven tests cover the seven reject branches. Every test
     * additionally asserts the load-bearing
     * {@code verify(dispatcher, never()).dispatch(any())} invariant to
     * prove the production code short-circuits before reaching the
     * downstream-service-call boundary on a rejected request.
     */
    @Nested
    @DisplayName("Reject paths — validation and confirmation")
    class RejectPaths {

        /**
         * Reject path: the operator has not selected a report mode (empty
         * string passed to {@code setReportMode}). The COBOL workflow
         * (lines 437-442 of CORPT00C.cbl) reaches the {@code WHEN OTHER}
         * branch of the three-way mode {@code EVALUATE} and produces
         * {@code 'Select a report type to print report...'}. The Java
         * migration surfaces a similar message anchored on the
         * load-bearing phrases {@code "select"} and {@code "report"}.
         *
         * <p>Even though the request carries a valid {@code "Y"}
         * confirmation, the reject fires because the mode check happens
         * first in the validation order — proving the workflow's
         * fail-fast structure.
         */
        @Test
        @DisplayName("submit rejects when report mode is empty")
        void submit_noMode_rejectsWithSelectReportTypeMessage() {
            // Arrange — empty mode, valid confirmation. The "Y"
            // confirmation should NOT cause the reject to use the
            // cancellation message; mode-selection is checked first.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("");
            request.setConfirmation("Y");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the mode-selection
            // message anchored on "select" and "report".
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Verbatim COBOL message 'Please select a report type...'")
                .containsIgnoringCase("select")
                .containsIgnoringCase("report");

            // Assert (2) — defence-in-depth: the dispatcher is NEVER
            // invoked on a rejected request.
            verify(dispatcher, never()).dispatch(any());
        }

        /**
         * Reject path: {@code reportMode = "CUSTOM"} but the start date is
         * empty. The COBOL workflow (lines 259-279 of CORPT00C.cbl)
         * produces one of three messages depending on whether the
         * sub-field that is empty is {@code SDTMMI} (month),
         * {@code SDTDDI} (day), or {@code SDTYYYYI} (year):
         * {@code 'Start Date - Month can NOT be empty...'} (line 261),
         * {@code 'Start Date - Day can NOT be empty...'} (line 268), or
         * {@code 'Start Date - Year can NOT be empty...'} (line 275). The
         * Java migration collapses the three COBOL sub-fields into a
         * single ISO string and produces one unified reject message
         * anchored on {@code "start date"} and {@code "empty"}.
         *
         * <p>The end date is populated with a valid value to isolate the
         * start-date check; mock-strict mode will reject any unused
         * stubbing, so the test does not stub the dispatcher (the
         * never() assertion below proves the production code never
         * reaches the dispatch boundary).
         */
        @Test
        @DisplayName("submit rejects when CUSTOM start date is empty")
        void submit_customMissingStartDate_rejectsWithStartDateEmptyMessage() {
            // Arrange — CUSTOM mode, empty start date, valid end date.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("CUSTOM");
            request.setStartDate("");
            request.setEndDate("2024-06-30");
            request.setConfirmation("Y");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the start-date-empty
            // message.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Verbatim COBOL message 'Start Date can NOT be empty'")
                .containsIgnoringCase("start date")
                .containsIgnoringCase("empty");

            // Assert (2) — defence-in-depth: no dispatch on reject.
            verify(dispatcher, never()).dispatch(any());
        }

        /**
         * Reject path: {@code reportMode = "CUSTOM"} but the end date is
         * empty. The COBOL workflow (lines 280-300 of CORPT00C.cbl)
         * produces the parallel {@code 'End Date - Month/Day/Year can NOT
         * be empty...'} messages. The Java migration collapses these into
         * one message anchored on {@code "end date"} and {@code "empty"}.
         *
         * <p>The start date is populated with a valid value to isolate
         * the end-date check (proving that the production code performs
         * the start-date check first and only THEN the end-date check).
         */
        @Test
        @DisplayName("submit rejects when CUSTOM end date is empty")
        void submit_customMissingEndDate_rejectsWithEndDateEmptyMessage() {
            // Arrange — CUSTOM mode, valid start date, empty end date.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("CUSTOM");
            request.setStartDate("2024-06-01");
            request.setEndDate("");
            request.setConfirmation("Y");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the end-date-empty
            // message.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Verbatim COBOL message 'End Date can NOT be empty'")
                .containsIgnoringCase("end date")
                .containsIgnoringCase("empty");

            // Assert (2) — defence-in-depth: no dispatch on reject.
            verify(dispatcher, never()).dispatch(any());
        }

        /**
         * Reject path (parameterised): {@code reportMode = "CUSTOM"} but
         * the start date fails strict-ISO parsing. The COBOL workflow
         * (lines 388-406 of CORPT00C.cbl) calls {@code CSUTLDTC} on the
         * start date and produces {@code 'Start Date - Not a valid
         * date...'} (line 400) when the validator returns a non-zero
         * severity code (and the message-number is NOT the
         * {@code 2513} "warning only" code per line 399).
         *
         * <p>The Java migration uses
         * {@link java.time.format.DateTimeFormatter#ISO_LOCAL_DATE} with
         * {@link java.time.format.ResolverStyle#STRICT} to reproduce the
         * COBOL {@code CSUTLDTC} strict-validation semantic. The
         * {@code @ValueSource} below covers the four malformed-input
         * categories enumerated in the AAP per §0.1.1 R6 edge-case
         * coverage:
         *
         * <ul>
         *   <li>{@code "2024-13-01"} — month overflow (13 > 12).</li>
         *   <li>{@code "2024-02-30"} — day overflow (Feb 30 doesn't
         *       exist; strict resolver rejects what lenient resolver
         *       would silently roll over to March 1 or 2).</li>
         *   <li>{@code "abcd-01-15"} — non-numeric year.</li>
         *   <li>{@code "2024/06/01"} — wrong separator (slash instead of
         *       hyphen).</li>
         * </ul>
         *
         * <p>The end date is populated with the valid value
         * {@code "2024-06-30"} so the start-date check is isolated; if
         * the start-date check failed silently and the production code
         * proceeded to the end-date check, the test would not catch the
         * bug because the end date IS valid. The
         * {@code containsIgnoringCase("start date")} assertion proves the
         * reject path that fired was the start-date one, not the
         * end-date one.
         *
         * @param invalidStart one of the four malformed start-date
         *                     fixtures
         */
        @ParameterizedTest(name = "[{index}] invalid start date ''{0}''")
        @ValueSource(strings = {"2024-13-01", "2024-02-30", "abcd-01-15", "2024/06/01"})
        @DisplayName("submit rejects invalid CUSTOM start date")
        void submit_customInvalidStartDate_rejectsWithStartDateInvalidMessage(String invalidStart) {
            // Arrange — CUSTOM mode, invalid start date, valid end date.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("CUSTOM");
            request.setStartDate(invalidStart);
            request.setEndDate("2024-06-30");
            request.setConfirmation("Y");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the start-date-invalid
            // message anchored on "start date". Verbatim COBOL message
            // is "Start Date - Not a valid date..." from line 400.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Verbatim COBOL message 'Start Date - Not a valid date...'")
                .containsIgnoringCase("start date");

            // Assert (2) — defence-in-depth: no dispatch on reject.
            verify(dispatcher, never()).dispatch(any());
        }

        /**
         * Reject path (parameterised): {@code reportMode = "CUSTOM"}, the
         * start date is valid, but the end date fails strict-ISO parsing.
         * The COBOL workflow (lines 408-426 of CORPT00C.cbl) calls
         * {@code CSUTLDTC} on the end date and produces {@code 'End Date
         * - Not a valid date...'} (line 420) when the validator returns
         * a non-zero severity code.
         *
         * <p>The {@code @ValueSource} below covers three of the four
         * malformed-input categories (month overflow, day overflow, and
         * non-numeric year). The wrong-separator category
         * ({@code "2024/06/01"}) is omitted from the end-date variant
         * because the start-date variant already covers that fixture and
         * a separate test row would add no additional path coverage —
         * the strict-ISO parser uses the same parse logic for both
         * fields.
         *
         * @param invalidEnd one of the three malformed end-date fixtures
         */
        @ParameterizedTest(name = "[{index}] invalid end date ''{0}''")
        @ValueSource(strings = {"2024-13-01", "2024-02-30", "abcd-01-15"})
        @DisplayName("submit rejects invalid CUSTOM end date")
        void submit_customInvalidEndDate_rejectsWithEndDateInvalidMessage(String invalidEnd) {
            // Arrange — CUSTOM mode, valid start date, invalid end date.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("CUSTOM");
            request.setStartDate("2024-06-01");
            request.setEndDate(invalidEnd);
            request.setConfirmation("Y");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the end-date-invalid
            // message anchored on "end date". Verbatim COBOL message is
            // "End Date - Not a valid date..." from line 420.
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Verbatim COBOL message 'End Date - Not a valid date...'")
                .containsIgnoringCase("end date");

            // Assert (2) — defence-in-depth: no dispatch on reject.
            verify(dispatcher, never()).dispatch(any());
        }

        /**
         * Reject path: {@code reportMode = "CUSTOM"}, both dates parse
         * successfully, but {@code startDate > endDate}. The COBOL
         * original does not explicitly check this invariant; the
         * downstream {@code CBTRN03C} report processor would produce an
         * empty report (or runtime error) for an inverted range. The
         * Java migration adds an explicit
         * {@link ReportSubmissionService#MSG_END_BEFORE_START} reject so
         * the operator gets immediate feedback at the REST controller
         * layer rather than waiting for the batch job to complete.
         *
         * <p>The fixture window {@code 2024-12-01} → {@code 2024-06-30}
         * is unambiguously inverted (December is well past June). Both
         * dates are individually valid ISO strings so the strict-parser
         * checks pass and the test isolates the ordering check.
         *
         * <p>The assertion uses both {@code "end date"} AND
         * {@code "start date"} as anchor phrases to verify the
         * load-bearing comparison is communicated to the operator (the
         * canonical message reads "End Date should be greater than Start
         * Date").
         */
        @Test
        @DisplayName("submit rejects when start date > end date")
        void submit_startAfterEnd_rejectsWithEndAfterStartMessage() {
            // Arrange — CUSTOM mode, inverted date window.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("CUSTOM");
            request.setStartDate("2024-12-01");
            request.setEndDate("2024-06-30");
            request.setConfirmation("Y");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the start-after-end
            // message anchored on both "end date" and "start date".
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Java-migration message 'End Date should be greater than Start Date'")
                .containsIgnoringCase("end date")
                .containsIgnoringCase("start date");

            // Assert (2) — defence-in-depth: no dispatch on reject.
            verify(dispatcher, never()).dispatch(any());
        }

        /**
         * Reject path: the operator's confirmation is {@code "N"} rather
         * than {@code "Y"}. The COBOL workflow (lines 480-483 of
         * CORPT00C.cbl) re-initialises all input fields and re-displays
         * the screen with no message written to {@code ERRMSGO}; the
         * Java migration surfaces an explicit
         * {@link ReportSubmissionService#MSG_CANCELLED} message so REST
         * callers receive a clear cancellation outcome rather than a
         * silent return.
         *
         * <p>The fixture uses MONTHLY mode (valid) so the rejection is
         * unambiguously caused by the confirmation gate, not the
         * mode-selection gate or the date-validation gate. This isolates
         * the confirmation-cancelled branch.
         */
        @Test
        @DisplayName("submit rejects when confirmation is N")
        void submit_confirmationNo_rejectsWithCancelledMessage() {
            // Arrange — valid MONTHLY mode, N confirmation.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("MONTHLY");
            request.setConfirmation("N");

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — failure outcome with the cancellation message
            // anchored on "cancel".
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getMessage())
                .as("Java-migration message 'Confirmation cancelled by user'")
                .containsIgnoringCase("cancel");

            // Assert (2) — defence-in-depth: no dispatch on cancellation.
            verify(dispatcher, never()).dispatch(any());
        }
    }

    // =========================================================================
    // ASYNC DISPATCH — TDQ replacement
    //
    // The mainframe TDQ write to QUEUE('JOBS') in CORPT00C.cbl WIRTE-
    // JOBSUB-TDQ (lines 515-535) is replaced by the
    // ReportJobDispatcher.dispatch(ReportJobParameters) boundary in the
    // Java migration. The two @Test methods below assert two load-bearing
    // properties of this replacement:
    //   1. JCL JOB name parity — the dispatched parameters carry the
    //      verbatim "TRNRPT00" token from CORPT00C line 84.
    //   2. Handle round-trip — the dispatcher's return value is surfaced
    //      verbatim on the ReportSubmissionResult to downstream callers.
    // =========================================================================

    /**
     * Async-dispatch scenarios for
     * {@link ReportSubmissionService#submit(ReportSubmissionRequest)}.
     *
     * <p>Two tests cover the load-bearing dispatcher-boundary contracts:
     * <ol>
     *   <li>JCL JOB name {@code "TRNRPT00"} parity (verbatim COBOL line
     *       84 of CORPT00C.cbl).</li>
     *   <li>Dispatcher handle round-trip — the
     *       {@link ReportJobHandle} returned by
     *       {@link ReportJobDispatcher#dispatch(ReportJobParameters)} is
     *       surfaced verbatim on the
     *       {@link ReportSubmissionResult#getJobHandle()} so downstream
     *       callers can use it for status polling.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Async dispatch — TDQ replacement")
    class AsyncDispatch {

        /**
         * Asserts that {@link ReportSubmissionService#submit(ReportSubmissionRequest)}
         * dispatches the batch job with the verbatim {@code "TRNRPT00"} JCL
         * JOB name from line 84 of CORPT00C.cbl
         * ({@code //TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,...}).
         * This is the load-bearing job-name constant: downstream Spring
         * Batch consumers look up the corresponding {@code Job} bean using
         * this exact name; any drift would break the batch-job lookup
         * contract and silently skip the report generation.
         *
         * <p>The fixture uses MONTHLY mode (the simplest happy path) so
         * the test focuses on the dispatched job-name field rather than
         * on the date computation (which is exercised by the
         * {@link HappyPath} group).
         */
        @Test
        @DisplayName("submit dispatches with JCL JOB name TRNRPT00 (COBOL parity)")
        void submit_validRequest_dispatchesWithCorrectJobName() {
            // Arrange — simplest happy path: MONTHLY mode, Y confirmation.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("MONTHLY");
            request.setConfirmation("Y");

            when(dispatcher.dispatch(any())).thenReturn(ReportJobHandle.of("HANDLE-001"));

            // Act — invoke the real production method.
            service.submit(request);

            // Assert — capture the dispatched parameters and check the
            // verbatim "TRNRPT00" job name. This is the only load-bearing
            // assertion of this test; the date-range assertions are
            // covered by the HappyPath group.
            ArgumentCaptor<ReportJobParameters> captor =
                ArgumentCaptor.forClass(ReportJobParameters.class);
            verify(dispatcher).dispatch(captor.capture());
            assertThat(captor.getValue().getJobName())
                .as("JCL JOB name 'TRNRPT00' per CORPT00C.cbl line 84 "
                    + "(load-bearing constant for downstream batch lookup)")
                .isEqualTo("TRNRPT00");
        }

        /**
         * Asserts that the
         * {@link ReportJobHandle} returned by
         * {@link ReportJobDispatcher#dispatch(ReportJobParameters)} is
         * surfaced verbatim on the
         * {@link ReportSubmissionResult#getJobHandle()} accessor so
         * downstream REST controllers can use the handle for status
         * polling.
         *
         * <p>The test stubs the dispatcher to return a specific handle
         * identifier ({@code "TRNRPT00-12345"}) and asserts that the
         * exact same handle instance comes back through
         * {@link ReportSubmissionResult#getJobHandle()}. The
         * {@link ReportJobHandle#equals(Object)} implementation is
         * value-based on the wrapped identifier, so this assertion
         * succeeds whether the production code returns the original
         * handle reference or constructs a value-equal copy — but in
         * either case the production contract is satisfied because
         * downstream callers see the same opaque token.
         *
         * <p>This is the COBOL-equivalent of the {@code WIRTE-JOBSUB-TDQ}
         * post-condition (lines 515-535 of CORPT00C.cbl) where the
         * {@code WS-RESP-CD} returned by {@code EXEC CICS WRITEQ TD}
         * communicates the TDQ-write success to the calling paragraph;
         * the Java migration uses a typed handle instead of a numeric
         * response code so downstream tracking is unambiguous.
         */
        @Test
        @DisplayName("submit returns the dispatcher's handle to the caller")
        void submit_validRequest_returnsDispatcherHandle() {
            // Arrange — simplest happy path with a specific handle.
            ReportSubmissionRequest request = new ReportSubmissionRequest();
            request.setReportMode("MONTHLY");
            request.setConfirmation("Y");

            ReportJobHandle expectedHandle = ReportJobHandle.of("TRNRPT00-12345");
            when(dispatcher.dispatch(any())).thenReturn(expectedHandle);

            // Act — invoke the real production method.
            ReportSubmissionResult result = service.submit(request);

            // Assert (1) — success outcome.
            assertThat(result.isSuccess())
                .as("Successful submission must return isSuccess() = true")
                .isTrue();

            // Assert (2) — the handle returned by the dispatcher comes
            // back through the result accessor verbatim (value-equal).
            // ReportJobHandle.equals is value-based on the wrapped
            // identifier, so the production code may return either the
            // exact reference or a value-equal copy.
            assertThat(result.getJobHandle())
                .as("Dispatcher handle is surfaced verbatim to the caller")
                .isEqualTo(expectedHandle);
        }
    }
}
