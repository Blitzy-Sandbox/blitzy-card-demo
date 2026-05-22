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

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;

/**
 * Report-submission service — the Java migration of the 649-line CICS COBOL
 * program {@code app/cbl/CORPT00C.cbl} (TRANID {@code CR00}, the
 * transaction-report submission dispatcher). Validates the operator's
 * three-way report-mode selection ({@code MONTHLY}, {@code YEARLY},
 * {@code CUSTOM}), computes the resolved date window, enforces the
 * confirmation gate, and dispatches the {@code TRNRPT00} JCL job to a
 * downstream batch executor via {@link ReportJobDispatcher} — the Java
 * replacement for the mainframe TDQ (Transient Data Queue) integration with
 * JES.
 *
 * <h2>COBOL Provenance — CORPT00C.cbl Three Report Modes</h2>
 *
 * <p>The COBOL {@code PROCESS-ENTER-KEY} paragraph (lines 208-456 of
 * CORPT00C.cbl) implements a three-way {@code EVALUATE TRUE} that selects
 * the report-mode branch based on which BMS radio button the operator
 * filled in:
 *
 * <ol>
 *   <li><b>Monthly</b> (lines 213-238) — date window = first day of
 *       current month → last day of current month. Computed via the COBOL
 *       {@code FUNCTION CURRENT-DATE} intrinsic plus the
 *       {@code DATE-OF-INTEGER}/{@code INTEGER-OF-DATE} arithmetic trick
 *       (lines 229-231) to derive the last day of the month. The Java
 *       migration uses {@code LocalDate.now(clock).withDayOfMonth(1)} and
 *       {@code .with(TemporalAdjusters.lastDayOfMonth())} for the same
 *       semantics with built-in leap-year handling.</li>
 *   <li><b>Yearly</b> (lines 239-255) — date window = first day of
 *       current year → December 31 of current year.</li>
 *   <li><b>Custom</b> (lines 256-436) — user-supplied start/end dates,
 *       each validated via {@code CSUTLDTC} (the COBOL {@code CEEDAYS}
 *       intrinsic wrapper). The Java migration validates them with strict
 *       ISO-{@code YYYY-MM-DD} parsing via
 *       {@link DateTimeFormatter#ISO_LOCAL_DATE} + {@link ResolverStyle#STRICT}.</li>
 * </ol>
 *
 * <p>After mode resolution, the COBOL {@code SUBMIT-JOB-TO-INTRDR}
 * paragraph (lines 462-510) validates the {@code CONFIRMI} confirmation
 * prompt:
 * <ul>
 *   <li>{@code 'Y' OR 'y'} → assemble JCL and write to TDQ</li>
 *   <li>{@code 'N' OR 'n'} → re-initialise the screen and abort</li>
 *   <li>any other value → re-display with an error message</li>
 * </ul>
 *
 * <h2>Reject Messages (verbatim COBOL strings per AAP §0.10.4)</h2>
 *
 * <p>Each reject path emits a message that the test suite anchors on via
 * {@code .containsIgnoringCase(...)} matchers. The literals below preserve
 * the load-bearing phrases from the original COBOL strings while
 * collapsing the COBOL three-part date-input model (separate month, day,
 * year fields) into the Java single-string ISO model.
 *
 * <ul>
 *   <li>{@link #MSG_SELECT_REPORT_TYPE} = {@code "Please select a report
 *       type..."} — anchored on the load-bearing phrases {@code "select"}
 *       and {@code "report"}; mirrors the COBOL {@code 'Select a report
 *       type to print report...'} (line 438 of CORPT00C.cbl).</li>
 *   <li>{@link #MSG_START_DATE_EMPTY} = {@code "Start Date can NOT be
 *       empty"} — anchored on {@code "start date"} and {@code "empty"};
 *       mirrors the three COBOL strings {@code 'Start Date - Month/Day/Year
 *       can NOT be empty...'} (lines 261, 268, 275).</li>
 *   <li>{@link #MSG_END_DATE_EMPTY} = {@code "End Date can NOT be empty"}
 *       — anchored on {@code "end date"} and {@code "empty"}; mirrors the
 *       three COBOL strings {@code 'End Date - Month/Day/Year can NOT be
 *       empty...'} (lines 282, 289, 296).</li>
 *   <li>{@link #MSG_START_DATE_INVALID} = {@code "Start Date - Not a
 *       valid date..."} — anchored on {@code "start date"}; verbatim from
 *       COBOL line 400 ({@code 'Start Date - Not a valid date...'}).</li>
 *   <li>{@link #MSG_END_DATE_INVALID} = {@code "End Date - Not a valid
 *       date..."} — anchored on {@code "end date"}; verbatim from COBOL
 *       line 420 ({@code 'End Date - Not a valid date...'}).</li>
 *   <li>{@link #MSG_END_BEFORE_START} = {@code "End Date should be greater
 *       than Start Date"} — Java-migration addition (no COBOL equivalent;
 *       see <em>Java Migration: Date-Ordering Check</em> below).</li>
 *   <li>{@link #MSG_CANCELLED} = {@code "Confirmation cancelled by user"}
 *       — Java-migration addition (the COBOL {@code 'N'} branch at line
 *       480-483 of CORPT00C.cbl clears the screen with no message; the
 *       Java migration surfaces an explicit cancel message so the REST
 *       controller layer can return a clear cancellation outcome).</li>
 *   <li>{@link #MSG_SUBMITTED} = {@code "Report submitted for printing
 *       ..."} — verbatim from COBOL line 450 ({@code ' report submitted
 *       for printing ...'}).</li>
 * </ul>
 *
 * <h2>JCL JOB Name Constant</h2>
 *
 * <p>{@link #JOB_NAME} = {@code "TRNRPT00"} — verbatim from line 84 of
 * CORPT00C.cbl ({@code //TRNRPT00 JOB 'TRAN REPORT',CLASS=A,MSGCLASS=0,...}).
 * This is the load-bearing JCL JOB name that downstream Spring Batch
 * consumers use to look up the corresponding {@code Job} bean; tests
 * assert on this constant via {@code assertThat(params.getJobName())
 * .isEqualTo("TRNRPT00")}.
 *
 * <h2>Java Migration: Date-Ordering Check</h2>
 *
 * <p>The COBOL original does not check that the {@code Start Date &lt;= End
 * Date} relationship holds; the downstream {@code CBTRN03C} report
 * processor would have produced an empty report (or thrown a runtime
 * error) for an inverted range. The Java migration adds an explicit
 * {@code start.isAfter(end)} check that rejects the submission with
 * {@link #MSG_END_BEFORE_START} so the operator gets immediate feedback at
 * the REST controller layer rather than waiting for the batch job to
 * complete. This is a documented Java-migration addition (AAP §0.10.2:
 * "All deviations from literal COBOL logic must be documented with the
 * original COBOL paragraph name and reason for divergence").
 *
 * <h2>Java Migration: Confirmation Cancel Message</h2>
 *
 * <p>The COBOL {@code 'N'} confirmation branch (lines 480-483 of
 * CORPT00C.cbl) re-initialises all fields and re-displays the screen
 * without writing any message to {@code ERRMSGO}; the operator sees a
 * blank screen and infers the cancellation. The Java migration surfaces
 * an explicit {@link #MSG_CANCELLED} message so REST callers receive a
 * clear cancellation outcome. The {@code 'cancelled'} substring on the
 * message is the test-anchor token.
 *
 * <h2>Constructor Injection (No Spring Stereotype)</h2>
 *
 * <p>This class deliberately omits the {@code @Service} stereotype
 * annotation; subsequent migration agents will add it when the full Spring
 * application context is wired up. For now, the constructor accepts the
 * two collaborators directly so unit tests can wire a Mockito mock
 * dispatcher and a fixed {@code Clock} without a Spring context —
 * matching the convention established by {@link AuthenticationService},
 * {@link UserDeleteService}, and the rest of the service package (AAP
 * §0.10.10 style consistency).
 *
 * <h2>Require Test Coverage Rule (AAP §0.10.1)</h2>
 *
 * <p>This service holds the COMPLETE submission dispatcher logic. The
 * corresponding {@code ReportSubmissionServiceTest} exercises every branch
 * via real method calls with a mocked {@link ReportJobDispatcher} at the
 * downstream-service-call boundary only — no business logic (date
 * computation, validation, dispatch composition) is duplicated inside the
 * test. The {@link Clock} injection seam supports deterministic
 * date-assertion in {@code MONTHLY} / {@code YEARLY} mode tests; the
 * fixed-clock fixture at {@code 2024-01-15T00:00:00Z} produces the
 * deterministic windows asserted by the test suite.
 *
 * @see ReportSubmissionRequest
 * @see ReportSubmissionResult
 * @see ReportJobDispatcher
 * @see ReportJobParameters
 */
public class ReportSubmissionService {

    // ---------------------------------------------------------------------
    // Reject messages — load-bearing phrases preserved per AAP §0.10.4
    // (Immutable Boundaries: downstream consumers reading the JSON error
    // envelope must see textual reasons anchored on the same key phrases
    // as the COBOL baseline).
    // ---------------------------------------------------------------------

    /**
     * Reject message returned when the operator has not selected a report
     * mode — anchored on the load-bearing phrases {@code "select"} and
     * {@code "report"}. Mirrors the COBOL {@code 'Select a report type to
     * print report...'} from line 438 of CORPT00C.cbl.
     */
    static final String MSG_SELECT_REPORT_TYPE = "Please select a report type...";

    /**
     * Reject message returned when {@code reportMode = "CUSTOM"} and
     * {@link ReportSubmissionRequest#getStartDate()} is empty — anchored on
     * the phrases {@code "start date"} and {@code "empty"}. Mirrors the
     * three COBOL strings {@code 'Start Date - Month/Day/Year can NOT be
     * empty...'} (lines 261, 268, 275 of CORPT00C.cbl), collapsed to a
     * single Java-side message because the Java migration uses one ISO
     * string for the date input.
     */
    static final String MSG_START_DATE_EMPTY = "Start Date can NOT be empty";

    /**
     * Reject message returned when {@code reportMode = "CUSTOM"} and
     * {@link ReportSubmissionRequest#getEndDate()} is empty — anchored on
     * {@code "end date"} and {@code "empty"}. Mirrors the three COBOL
     * strings {@code 'End Date - Month/Day/Year can NOT be empty...'}
     * (lines 282, 289, 296 of CORPT00C.cbl).
     */
    static final String MSG_END_DATE_EMPTY = "End Date can NOT be empty";

    /**
     * Reject message returned when {@code reportMode = "CUSTOM"} and the
     * start date fails strict-ISO {@code YYYY-MM-DD} parsing — anchored on
     * {@code "start date"}. Verbatim from COBOL line 400
     * ({@code 'Start Date - Not a valid date...'}).
     */
    static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";

    /**
     * Reject message returned when {@code reportMode = "CUSTOM"} and the
     * end date fails strict-ISO parsing — anchored on {@code "end date"}.
     * Verbatim from COBOL line 420 ({@code 'End Date - Not a valid
     * date...'}).
     */
    static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    /**
     * Reject message returned when {@code reportMode = "CUSTOM"} and the
     * start date is after the end date — anchored on {@code "end date"}
     * AND {@code "start date"} so the corresponding test assertion can
     * verify both phrases. This is a Java-migration addition with no
     * direct COBOL equivalent (see class-level <em>Java Migration:
     * Date-Ordering Check</em>).
     */
    static final String MSG_END_BEFORE_START = "End Date should be greater than Start Date";

    /**
     * Reject message returned when the operator's confirmation is anything
     * other than {@code "Y"} or {@code "y"} (typically {@code "N"} or
     * {@code "n"}) — anchored on {@code "cancel"}. Java-migration addition
     * (the COBOL {@code 'N'} branch produces no message; see class-level
     * <em>Java Migration: Confirmation Cancel Message</em>).
     */
    static final String MSG_CANCELLED = "Confirmation cancelled by user";

    /**
     * Success confirmation message — verbatim from COBOL line 450
     * ({@code ' report submitted for printing ...'}). The COBOL flow
     * prepends {@code WS-REPORT-NAME} ({@code 'Monthly'} / {@code 'Yearly'}
     * / {@code 'Custom'}) via the {@code STRING} construct at lines
     * 448-454; the Java migration uses a single canonical message because
     * the {@link ReportSubmissionResult#getJobHandle()} accessor already
     * surfaces the mode-agnostic dispatcher handle for any downstream
     * caller that needs to distinguish the modes.
     */
    static final String MSG_SUBMITTED = "Report submitted for printing ...";

    // ---------------------------------------------------------------------
    // JCL submission constants — verbatim from CORPT00C.cbl JOB-DATA group
    // ---------------------------------------------------------------------

    /**
     * JCL JOB name dispatched on every successful submission — verbatim
     * from line 84 of CORPT00C.cbl ({@code //TRNRPT00 JOB 'TRAN
     * REPORT',CLASS=A,MSGCLASS=0,...}). Load-bearing constant: downstream
     * Spring Batch consumers look up the corresponding {@code Job} bean
     * using this exact name; renaming would break the batch-job lookup
     * contract. Tests assert on this constant via
     * {@code assertThat(params.getJobName()).isEqualTo("TRNRPT00")}.
     */
    static final String JOB_NAME = "TRNRPT00";

    // ---------------------------------------------------------------------
    // Report mode tokens — case-insensitive selectors recognised on the
    // ReportSubmissionRequest.reportMode field.
    // ---------------------------------------------------------------------

    /**
     * Report-mode token for the {@code MONTHLYI} COBOL branch (line 213 of
     * CORPT00C.cbl). Date window: first → last day of the current month.
     */
    static final String MODE_MONTHLY = "MONTHLY";

    /**
     * Report-mode token for the {@code YEARLYI} COBOL branch (line 239 of
     * CORPT00C.cbl). Date window: January 1 → December 31 of the current
     * year.
     */
    static final String MODE_YEARLY = "YEARLY";

    /**
     * Report-mode token for the {@code CUSTOMI} COBOL branch (line 256 of
     * CORPT00C.cbl). Date window: user-supplied
     * {@link ReportSubmissionRequest#getStartDate()} /
     * {@link ReportSubmissionRequest#getEndDate()} after strict-ISO
     * parsing.
     */
    static final String MODE_CUSTOM = "CUSTOM";

    /**
     * Confirmation token that proceeds with submission — case-insensitive,
     * matches both {@code "Y"} and {@code "y"} from CORPT00C.cbl line 478
     * ({@code WHEN CONFIRMI = 'Y' OR 'y'}).
     */
    private static final String CONFIRM_YES = "Y";

    /**
     * Strict {@code YYYY-MM-DD} date parser used for {@code CUSTOM}-mode
     * start/end-date validation. {@link ResolverStyle#STRICT} ensures
     * Java's calendar arithmetic does not silently roll over invalid
     * values (e.g., {@code "2024-02-30"} → {@code 2024-03-01} under
     * lenient parsing); the parser raises {@link DateTimeParseException}
     * for every malformed input listed in the test
     * {@code @ParameterizedTest @ValueSource} ({@code "2024-13-01"},
     * {@code "2024-02-30"}, {@code "abcd-01-15"}, {@code "2024/06/01"}).
     */
    private static final DateTimeFormatter STRICT_ISO_DATE =
        DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);

    // ---------------------------------------------------------------------
    // Collaborator boundaries — the two injected dependencies
    // ---------------------------------------------------------------------

    /**
     * Asynchronous report-job dispatcher — Java replacement for the
     * mainframe TDQ {@code 'JOBS'} write at lines 517-523 of CORPT00C.cbl
     * ({@code EXEC CICS WRITEQ TD QUEUE('JOBS')}). Constructor-injected so
     * unit tests can wire a Mockito mock without a Spring context. Per
     * AAP §0.10.1, this is the only mock boundary for the corresponding
     * test (the {@link Clock} field below is also injected, but tests use
     * a real {@code Clock.fixed(...)} instance rather than a mock).
     */
    private final ReportJobDispatcher dispatcher;

    /**
     * Time source for deterministic {@code MONTHLY} / {@code YEARLY} date
     * computation — Java replacement for the COBOL
     * {@code FUNCTION CURRENT-DATE} intrinsic (lines 215, 241, 611 of
     * CORPT00C.cbl). Constructor-injected so unit tests can inject a
     * {@code Clock.fixed(...)} instance for deterministic
     * date-range assertions. In production, the wiring layer injects
     * {@code Clock.systemDefaultZone()}.
     */
    private final Clock clock;

    /**
     * Constructs a new {@code ReportSubmissionService}.
     *
     * @param dispatcher the asynchronous report-job dispatcher; must not
     *                   be {@code null} — the service does not guard
     *                   against {@code null} collaborators because Spring
     *                   DI would surface the misconfiguration at startup;
     *                   unit tests wire a Mockito mock
     * @param clock      the time source used to compute the date window
     *                   for {@code MONTHLY} and {@code YEARLY} modes; must
     *                   not be {@code null} — tests inject a
     *                   {@code Clock.fixed(...)} instance for
     *                   deterministic date-range assertions
     */
    public ReportSubmissionService(ReportJobDispatcher dispatcher, Clock clock) {
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /**
     * Submit a transaction-report batch job after enforcing the
     * COBOL-inherited reject paths plus the two Java-migration-added
     * checks (date-ordering, explicit cancellation message). Implements the
     * Java equivalent of {@code CORPT00C.cbl} {@code PROCESS-ENTER-KEY}
     * (lines 208-456) plus {@code SUBMIT-JOB-TO-INTRDR} (lines 462-510).
     *
     * <h3>Workflow</h3>
     *
     * <ol>
     *   <li><b>Mode selection</b> — normalises
     *       {@link ReportSubmissionRequest#getReportMode()} to uppercase,
     *       trimmed; matches against {@link #MODE_MONTHLY},
     *       {@link #MODE_YEARLY}, {@link #MODE_CUSTOM}. Unknown or empty
     *       mode rejects with {@link #MSG_SELECT_REPORT_TYPE}.</li>
     *   <li><b>{@code MONTHLY} date computation</b> (COBOL parity: lines
     *       217-237) — {@code LocalDate.now(clock).withDayOfMonth(1)} for
     *       the start; {@code .withDayOfMonth(lengthOfMonth())} for the
     *       end. The latter handles leap years implicitly (February 29 on
     *       leap years, 28 otherwise).</li>
     *   <li><b>{@code YEARLY} date computation</b> (COBOL parity: lines
     *       243-253) — {@code LocalDate.now(clock).withDayOfYear(1)} for
     *       the start; {@code .withMonth(12).withDayOfMonth(31)} for the
     *       end.</li>
     *   <li><b>{@code CUSTOM} date validation</b> (COBOL parity: lines
     *       259-426) — empties first ({@link #MSG_START_DATE_EMPTY} /
     *       {@link #MSG_END_DATE_EMPTY}), then strict-ISO parsing
     *       ({@link #MSG_START_DATE_INVALID} /
     *       {@link #MSG_END_DATE_INVALID}), then the Java-migration-added
     *       {@code start &lt;= end} check
     *       ({@link #MSG_END_BEFORE_START}).</li>
     *   <li><b>Confirmation gate</b> (COBOL parity: lines 478-483) — any
     *       value other than {@code "Y"}/{@code "y"} rejects with
     *       {@link #MSG_CANCELLED}.</li>
     *   <li><b>Dispatch</b> (COBOL parity: lines 515-535) — assemble
     *       {@link ReportJobParameters} and pass to
     *       {@link ReportJobDispatcher#dispatch(ReportJobParameters)}.
     *       The handle returned by the dispatcher is surfaced on the
     *       success {@link ReportSubmissionResult}.</li>
     * </ol>
     *
     * <p>Each reject path short-circuits and returns immediately on
     * failure; the dispatcher is never consulted on any reject path
     * (defence-in-depth verified by the corresponding test suite via
     * {@code verify(dispatcher, never()).dispatch(any())} assertions).
     *
     * <p>Infrastructure errors (dispatcher unreachable, queue full, etc.)
     * surface as {@link RuntimeException} subclasses thrown by the
     * dispatcher; the service does not catch them, letting the controller
     * layer's exception-handler chain produce the Java equivalent of the
     * COBOL {@code 'Unable to Write TDQ (JOBS)...'} response (line 531-532
     * of CORPT00C.cbl).
     *
     * @param request the report-submission request carrying the mode,
     *                date window (for {@code CUSTOM} mode), and
     *                confirmation token; must not be {@code null}
     * @return a populated {@link ReportSubmissionResult} encoding either
     *         success (with the {@code 'Report submitted for printing
     *         ...'} message and the dispatcher-assigned handle) or
     *         failure (with one of the seven COBOL-equivalent or
     *         Java-migration-added reject messages and a {@code null}
     *         job handle)
     */
    public ReportSubmissionResult submit(ReportSubmissionRequest request) {
        // Step 1 — Resolve the report mode and the associated date window.
        // Returns a {@code DateRangeResolution} that either carries a
        // reject message (in which case we short-circuit) or carries the
        // computed start/end dates ready for dispatch.
        DateRangeResolution range = resolveDateRange(request);
        if (range.rejectMessage != null) {
            return ReportSubmissionResult.failure(range.rejectMessage);
        }

        // Step 2 — Confirmation gate (COBOL parity: lines 478-483 of
        // CORPT00C.cbl). Normalises the operator's input to a single
        // uppercase character and compares against the canonical "Y"
        // token. Any other value (empty, "N", "n", junk) is treated as a
        // cancellation per the Java-migration phrasing.
        String confirmation = trimToEmpty(request.getConfirmation()).toUpperCase(Locale.ROOT);
        if (!CONFIRM_YES.equals(confirmation)) {
            return ReportSubmissionResult.failure(MSG_CANCELLED);
        }

        // Step 3 — Dispatch to the asynchronous batch executor (COBOL
        // parity: WIRTE-JOBSUB-TDQ at lines 515-535 of CORPT00C.cbl).
        // The dispatcher is the single mock boundary per AAP §0.10.1.
        // Build the parameter object inline so the dispatch is the only
        // observable interaction between the service and the boundary.
        ReportJobParameters parameters =
            ReportJobParameters.of(JOB_NAME, range.startDate, range.endDate);
        ReportJobHandle handle = dispatcher.dispatch(parameters);

        // Step 4 — Build the success response (COBOL parity: lines
        // 448-454 of CORPT00C.cbl STRING 'Monthly/Yearly/Custom report
        // submitted for printing ...'). The Java migration uses a single
        // canonical message because the dispatcher handle and the
        // ReportSubmissionRequest.reportMode are already available to
        // downstream callers via separate fields; combining them into
        // one string would force downstream parsers to disassemble the
        // message.
        return ReportSubmissionResult.success(MSG_SUBMITTED, handle);
    }

    /**
     * Resolves the requested {@code reportMode} into a date window or a
     * reject message. Extracted as a private helper so the
     * {@link #submit(ReportSubmissionRequest)} method body remains the
     * three-step workflow (resolve → confirm → dispatch) and so each
     * mode-specific COBOL paragraph is visible as a distinct branch of
     * the switch below.
     *
     * @param request the inbound request (read-only — never mutated)
     * @return a {@link DateRangeResolution} carrying either the start/end
     *         dates (success path) or a non-{@code null}
     *         {@code rejectMessage} (failure path)
     */
    private DateRangeResolution resolveDateRange(ReportSubmissionRequest request) {
        String mode = trimToEmpty(request.getReportMode()).toUpperCase(Locale.ROOT);

        switch (mode) {
            case MODE_MONTHLY:
                // COBOL parity: lines 213-237 of CORPT00C.cbl. The COBOL
                // workflow performs the same first-day / last-day
                // computation using FUNCTION CURRENT-DATE plus the
                // DATE-OF-INTEGER trick. The Java migration uses
                // LocalDate.withDayOfMonth(1) / lengthOfMonth() for the
                // same semantics with native leap-year handling.
                LocalDate todayMonthly = LocalDate.now(clock);
                return DateRangeResolution.ofDates(
                    todayMonthly.withDayOfMonth(1),
                    todayMonthly.withDayOfMonth(todayMonthly.lengthOfMonth()));

            case MODE_YEARLY:
                // COBOL parity: lines 239-253 of CORPT00C.cbl. First day
                // of current year through December 31 of current year.
                LocalDate todayYearly = LocalDate.now(clock);
                return DateRangeResolution.ofDates(
                    todayYearly.withDayOfYear(1),
                    todayYearly.withMonth(12).withDayOfMonth(31));

            case MODE_CUSTOM:
                // COBOL parity: lines 256-436 of CORPT00C.cbl. The COBOL
                // workflow performs three layers of validation (empty
                // sub-fields, numeric/range sub-fields, CSUTLDTC date
                // validation); the Java migration collapses the first
                // two layers into the strict-ISO parser invocation
                // because the Java migration carries a single ISO string
                // per date rather than three sub-fields. The third layer
                // (start &lt;= end) is a Java-migration addition.
                return resolveCustomDateRange(request);

            default:
                // COBOL parity: lines 437-442 of CORPT00C.cbl. The
                // WHEN OTHER branch of the three-way EVALUATE produces
                // the "Select a report type to print report..." reject.
                return DateRangeResolution.ofReject(MSG_SELECT_REPORT_TYPE);
        }
    }

    /**
     * Validates and parses the user-supplied custom-mode dates. Extracted
     * as a private helper to keep {@link #resolveDateRange(ReportSubmissionRequest)}
     * focused on the three-way mode {@code switch}.
     *
     * <p>The validation order is:
     * <ol>
     *   <li>Start-date empty check (COBOL parity: lines 259-279 of
     *       CORPT00C.cbl).</li>
     *   <li>End-date empty check (COBOL parity: lines 280-300).</li>
     *   <li>Start-date strict-ISO parse (COBOL parity: lines 388-406
     *       CSUTLDTC call).</li>
     *   <li>End-date strict-ISO parse (COBOL parity: lines 408-426).</li>
     *   <li>{@code start.isAfter(end)} ordering check (Java-migration
     *       addition).</li>
     * </ol>
     *
     * @param request the inbound request (read-only)
     * @return a resolution carrying either the parsed dates or the first
     *         applicable reject message
     */
    private DateRangeResolution resolveCustomDateRange(ReportSubmissionRequest request) {
        String startRaw = trimToEmpty(request.getStartDate());
        String endRaw = trimToEmpty(request.getEndDate());

        // Layer 1a — Start date present.
        if (startRaw.isEmpty()) {
            return DateRangeResolution.ofReject(MSG_START_DATE_EMPTY);
        }

        // Layer 1b — End date present.
        if (endRaw.isEmpty()) {
            return DateRangeResolution.ofReject(MSG_END_DATE_EMPTY);
        }

        // Layer 2a — Start date parses as strict ISO.
        LocalDate startDate;
        try {
            startDate = LocalDate.parse(startRaw, STRICT_ISO_DATE);
        } catch (DateTimeParseException e) {
            return DateRangeResolution.ofReject(MSG_START_DATE_INVALID);
        }

        // Layer 2b — End date parses as strict ISO.
        LocalDate endDate;
        try {
            endDate = LocalDate.parse(endRaw, STRICT_ISO_DATE);
        } catch (DateTimeParseException e) {
            return DateRangeResolution.ofReject(MSG_END_DATE_INVALID);
        }

        // Layer 3 — Java-migration ordering check. The COBOL workflow
        // does not enforce this invariant; the downstream CBTRN03C
        // report processor would produce an empty report (or runtime
        // error) for an inverted range. The Java migration surfaces an
        // immediate reject so the operator gets actionable feedback at
        // the REST controller layer.
        if (startDate.isAfter(endDate)) {
            return DateRangeResolution.ofReject(MSG_END_BEFORE_START);
        }

        return DateRangeResolution.ofDates(startDate, endDate);
    }

    /**
     * Null-safe {@code trim()} replacement that collapses {@code null} to
     * the empty string. Used to normalise nullable request fields so the
     * empty-check predicates do not need to distinguish {@code null} from
     * blank — matching the COBOL {@code SPACES OR LOW-VALUES} predicate
     * semantics that treats both states identically.
     *
     * @param value the inbound string; may be {@code null}
     * @return a non-{@code null}, trimmed string (possibly empty)
     */
    private static String trimToEmpty(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    /**
     * Tagged-union value type carrying either a resolved {@code (startDate,
     * endDate)} pair or a non-{@code null} {@code rejectMessage}. The
     * {@link #submit(ReportSubmissionRequest)} caller treats
     * {@code rejectMessage != null} as the reject signal and consumes
     * {@code startDate}/{@code endDate} only on the success path.
     *
     * <p>Defined as a {@code private static final} nested class rather
     * than a {@code record} because the project's Java 17 target accepts
     * either style and the explicit class makes the load-bearing
     * "exactly one of these two state shapes" invariant more visible at
     * the call site. The two static factories
     * ({@link #ofDates(LocalDate, LocalDate)} and
     * {@link #ofReject(String)}) enforce this invariant.
     */
    private static final class DateRangeResolution {

        /** Resolved start date — non-{@code null} only on success. */
        private final LocalDate startDate;

        /** Resolved end date — non-{@code null} only on success. */
        private final LocalDate endDate;

        /** Reject message — non-{@code null} only on rejection. */
        private final String rejectMessage;

        private DateRangeResolution(LocalDate startDate, LocalDate endDate, String rejectMessage) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.rejectMessage = rejectMessage;
        }

        /**
         * Builds the success-path resolution carrying the resolved
         * {@code startDate} and {@code endDate}.
         */
        private static DateRangeResolution ofDates(LocalDate startDate, LocalDate endDate) {
            return new DateRangeResolution(startDate, endDate, null);
        }

        /**
         * Builds the reject-path resolution carrying the supplied reject
         * message. The {@code startDate}/{@code endDate} fields remain
         * {@code null} and the caller is expected to short-circuit before
         * consulting them.
         */
        private static DateRangeResolution ofReject(String rejectMessage) {
            return new DateRangeResolution(null, null, rejectMessage);
        }
    }
}
