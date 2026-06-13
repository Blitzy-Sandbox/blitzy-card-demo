package com.cardemo.service.report;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.awspring.cloud.sqs.operations.SqsTemplate;

import com.cardemo.config.AwsConfig;
import com.cardemo.exception.ValidationException;
import com.cardemo.model.dto.ReportRequest;
import com.cardemo.service.shared.DateValidationService;

/**
 * Report-submission service &mdash; the Java&nbsp;25 / Spring&nbsp;Boot&nbsp;3.x translation of the
 * online CICS program <strong>{@code app/cbl/CORPT00C.cbl}</strong> (CICS transaction {@code CR00},
 * BMS map {@code CORPT0A}, mapset {@code CORPT00}). It realizes feature <strong>F-018</strong>
 * (transaction-report submission) of the preserved CardDemo estate.
 *
 * <h2>Why this service is architecturally special</h2>
 * <p>{@code CORPT00C} is the <strong>single coupling point between the online and batch worlds</strong>
 * in the entire CardDemo estate (AAP &sect;0.6.3, blueprint {@code docs/technical-specifications.md}
 * L30). On the mainframe the operator chose a <em>Monthly</em>, <em>Yearly</em>, or <em>Custom</em>
 * transaction report and, on a {@code 'Y'} confirmation, the program built a JCL deck and wrote it
 * line-by-line to the CICS Transient Data Queue {@code JOBS} ({@code WRITEQ TD}), which triggered JES
 * batch submission of the downstream report job ({@code TRANREPT} &rarr; {@code CBTRN03C}).</p>
 *
 * <h2>Technology substitutions (documented per the Minimal Change Clause &mdash; AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><strong>CICS TDQ {@code WRITEQ('JOBS')} / JES submission &rarr; a single AWS SQS publish.</strong>
 *       The legacy "build a JCL deck and write each of its lines to the {@code JOBS} TDQ" hand-off is
 *       replaced by <em>one</em> structured message published to the FIFO queue
 *       {@code carddemo-report-jobs.fifo} (AAP &sect;0.1.2 transformation table "CICS TDQ {@code WRITEQ}
 *       &rarr; SQS"; &sect;0.6.3; blueprint L631). The JCL deck is a mainframe artifact and is
 *       <em>not</em> reproduced &mdash; the downstream Spring Batch report job is parameterized by the
 *       message payload (report type + start/end date). This is the sole permitted
 *       infrastructure-bridge migration in the whole estate. The queue name is resolved from
 *       {@link com.cardemo.config.AwsConfig.AwsResourceProperties} (never hardcoded); the
 *       {@link SqsTemplate} and its underlying client are auto-configured by Spring Cloud AWS 3.3.0
 *       from {@code spring.cloud.aws.*} (LocalStack-backed, zero live AWS &mdash; AAP &sect;0.7.7).</li>
 *   <li><strong>LE {@code CALL 'CSUTLDTC'} ({@code CEEDAYS}) date validation &rarr;
 *       {@link DateValidationService} ({@code java.time}).</strong> The custom-range real-date check is
 *       delegated to the sibling {@code service/shared/DateValidationService} (AAP &sect;0.4.2), which
 *       parses with {@link java.time.format.ResolverStyle#STRICT}. See
 *       {@link #processCustom(ReportRequest)} for the format workaround and the deliberate avoidance of
 *       the century-window variant.</li>
 * </ul>
 *
 * <h2>Observable-behavior parity (AAP &sect;0.7.2 &mdash; 100% behavioral parity)</h2>
 * <p>Every user-facing message string is reproduced <em>verbatim</em> from {@code CORPT00C.cbl}
 * (the messages are part of the external contract) and every control-flow decision preserves the
 * COBOL {@code EVALUATE TRUE} ordering (AAP &sect;0.7.4):</p>
 * <ol>
 *   <li>Report-type precedence is <strong>Monthly &rarr; Yearly &rarr; Custom &rarr; (none)</strong>.</li>
 *   <li>The custom-date cascade is <strong>empty-check &rarr; numeric-normalize &rarr; range-check
 *       &rarr; real-date-check</strong>, in that exact order.</li>
 *   <li>The cascade is <strong>fail-fast</strong>: in COBOL each validation failure performed
 *       {@code SEND-TRNRPT-SCREEN}, which ends in {@code GO TO RETURN-TO-CICS} &rarr;
 *       {@code EXEC CICS RETURN} (verified at {@code CORPT00C.cbl} line&nbsp;580) &mdash; a hard
 *       transaction exit. The faithful Java equivalent therefore <strong>throws
 *       {@link ValidationException} on the first failure</strong> and never accumulates errors.</li>
 *   <li>The confirmation gate applies to all three report types: {@code 'Y'}/{@code 'y'} submits,
 *       {@code 'N'}/{@code 'n'} cancels silently (no publish, no error), and an empty or any other
 *       value is an error.</li>
 * </ol>
 *
 * <h2>No data-layer dependency</h2>
 * <p>{@code CORPT00C} performs <em>no</em> monetary arithmetic and <em>no</em> VSAM record reads (the
 * {@code CVTRA05Y} transaction fields in its working storage are unused leftovers; the actual
 * {@code TRANSACT} reads happen later in the batch program {@code CBTRN03C}/{@code TRANREPT}).
 * Accordingly this service injects <strong>no</strong> {@code JpaRepository} and references
 * <strong>no</strong> {@code @Entity}; its only collaborators are {@link DateValidationService},
 * {@link SqsTemplate}, {@link com.cardemo.config.AwsConfig.AwsResourceProperties}, and
 * {@link ValidationException} (AAP &sect;0.7.3 decimal rules are not applicable here).</p>
 *
 * <h2>Thread-safety</h2>
 * <p>Stateless and singleton-safe: every field is {@code final} and immutable (an injected
 * {@link SqsTemplate}, the {@link com.cardemo.config.AwsConfig.AwsResourceProperties} binder, the
 * {@link DateValidationService}, and a {@link Clock}); all message strings are {@code static final}.</p>
 *
 * <p><strong>Traceability.</strong> Behavior is translated from the frozen AWS CardDemo COBOL baseline
 * at commit SHA {@code 27d6c6f}. The COBOL source is read-only reference material and is
 * <em>never</em> copied into this repository.</p>
 *
 * @see com.cardemo.service.shared.DateValidationService
 * @see com.cardemo.config.AwsConfig.AwsResourceProperties
 * @see io.awspring.cloud.sqs.operations.SqsTemplate
 */
@Service
public class ReportSubmissionService {

    /** SLF4J logger for non-sensitive diagnostics (never logs secrets or full PII). */
    private static final Logger log = LoggerFactory.getLogger(ReportSubmissionService.class);

    // -------------------------------------------------------------------------------------------------
    // User-facing report names (COBOL WS-REPORT-NAME literals, CORPT00C.cbl L214 / L240 / L433). These
    // appear inside the confirmation and success messages and so are part of the observable contract.
    // -------------------------------------------------------------------------------------------------

    /** COBOL {@code MOVE 'Monthly' TO WS-REPORT-NAME} (CORPT00C.cbl L214). */
    private static final String REPORT_MONTHLY = "Monthly";

    /** COBOL {@code MOVE 'Yearly' TO WS-REPORT-NAME} (CORPT00C.cbl L240). */
    private static final String REPORT_YEARLY = "Yearly";

    /** COBOL {@code MOVE 'Custom' TO WS-REPORT-NAME} (CORPT00C.cbl L433). */
    private static final String REPORT_CUSTOM = "Custom";

    // -------------------------------------------------------------------------------------------------
    // Canonical machine report-type values carried in the SQS payload (the downstream Spring Batch
    // report job switches on these). They are the upper-case counterparts of the user-facing names
    // above; keeping the two distinct preserves the screen label while giving the batch a stable key.
    // -------------------------------------------------------------------------------------------------

    /** Machine report-type token for a monthly report (payload contract). */
    private static final String REPORT_TYPE_MONTHLY = "MONTHLY";

    /** Machine report-type token for a yearly report (payload contract). */
    private static final String REPORT_TYPE_YEARLY = "YEARLY";

    /** Machine report-type token for a custom-range report (payload contract). */
    private static final String REPORT_TYPE_CUSTOM = "CUSTOM";

    /**
     * FIFO message-group id for every report-submission message. SQS FIFO queues require a
     * {@code MessageGroupId}; a single fixed group preserves the strict, point-to-point ordering the
     * legacy CICS Transient Data Queue {@code JOBS} provided (messages in one group are delivered in
     * order). The per-message {@code MessageDeduplicationId} is generated fresh per submission (see
     * {@link #publishReportJob}) so a genuinely confirmed re-submission is never deduplicated away.
     */
    private static final String SQS_MESSAGE_GROUP_ID = "report-jobs";

    /**
     * Picture passed to {@link DateValidationService#validateDate(String, String)}. {@code CORPT00C}
     * passed {@code 'YYYY-MM-DD'} (with dashes) to {@code CSUTLDTC}; {@code DateValidationService} does
     * not recognize a dashed pattern and would fall back to {@code yyyyMMdd}, which cannot parse a
     * dashed string. The workaround is to compose an 8-digit {@code yyyyMMdd} value (no dashes) and
     * pass this picture &mdash; see {@link #processCustom(ReportRequest)}.
     */
    private static final String DATE_FORMAT_YYYYMMDD = "YYYYMMDD";

    // -------------------------------------------------------------------------------------------------
    // Verbatim COBOL message strings (CORPT00C.cbl). Copied exactly (spelling, capitalization, spacing,
    // punctuation, trailing ellipses) because they are part of the observable contract (AAP §0.7.2).
    // -------------------------------------------------------------------------------------------------

    /** CORPT00C.cbl L438 ({@code WHEN OTHER} report-type). */
    private static final String MSG_SELECT_REPORT_TYPE = "Select a report type to print report...";

    /** CORPT00C.cbl L261. */
    private static final String MSG_START_MONTH_EMPTY = "Start Date - Month can NOT be empty...";
    /** CORPT00C.cbl L268. */
    private static final String MSG_START_DAY_EMPTY = "Start Date - Day can NOT be empty...";
    /** CORPT00C.cbl L275. */
    private static final String MSG_START_YEAR_EMPTY = "Start Date - Year can NOT be empty...";
    /** CORPT00C.cbl L282. */
    private static final String MSG_END_MONTH_EMPTY = "End Date - Month can NOT be empty...";
    /** CORPT00C.cbl L289. */
    private static final String MSG_END_DAY_EMPTY = "End Date - Day can NOT be empty...";
    /** CORPT00C.cbl L296. */
    private static final String MSG_END_YEAR_EMPTY = "End Date - Year can NOT be empty...";

    /** CORPT00C.cbl L331. */
    private static final String MSG_START_MONTH_INVALID = "Start Date - Not a valid Month...";
    /** CORPT00C.cbl L340. */
    private static final String MSG_START_DAY_INVALID = "Start Date - Not a valid Day...";
    /** CORPT00C.cbl L348. */
    private static final String MSG_START_YEAR_INVALID = "Start Date - Not a valid Year...";
    /** CORPT00C.cbl L357. */
    private static final String MSG_END_MONTH_INVALID = "End Date - Not a valid Month...";
    /** CORPT00C.cbl L366. */
    private static final String MSG_END_DAY_INVALID = "End Date - Not a valid Day...";
    /** CORPT00C.cbl L374. */
    private static final String MSG_END_YEAR_INVALID = "End Date - Not a valid Year...";

    /** CORPT00C.cbl L400. */
    private static final String MSG_START_DATE_INVALID = "Start Date - Not a valid date...";
    /** CORPT00C.cbl L420. */
    private static final String MSG_END_DATE_INVALID = "End Date - Not a valid date...";

    // Confirmation-gate message fragments (COBOL builds these with STRING ... DELIMITED BY ...).
    /** CORPT00C.cbl L466 prefix of the "please confirm" message. */
    private static final String MSG_CONFIRM_PREFIX = "Please confirm to print the ";
    /** CORPT00C.cbl L469 suffix of the "please confirm" message. */
    private static final String MSG_CONFIRM_SUFFIX = " report...";
    /** CORPT00C.cbl L450 suffix of the success message. */
    private static final String MSG_SUBMITTED_SUFFIX = " report submitted for printing ...";
    /** CORPT00C.cbl L486 prefix of the "not a valid value to confirm" message. */
    private static final String MSG_INVALID_CONFIRM_PREFIX = "\"";
    /** CORPT00C.cbl L488 suffix of the "not a valid value to confirm" message. */
    private static final String MSG_INVALID_CONFIRM_SUFFIX = "\" is not a valid value to confirm...";

    // -------------------------------------------------------------------------------------------------
    // Injected collaborators. Constructor injection with private-final fields (no field @Autowired),
    // matching the established service convention. This service reads NO data store: there is
    // deliberately no JpaRepository or @Entity here (CORPT00C performs no VSAM reads).
    // -------------------------------------------------------------------------------------------------

    /**
     * Auto-configured Spring Cloud AWS SQS operations facade used to publish the single
     * report-submission message (the TDQ {@code WRITEQ} replacement).
     */
    private final SqsTemplate sqsTemplate;

    /**
     * Strongly-typed binder for application-owned AWS resource names; the FIFO report-jobs queue name
     * is read from {@code awsProperties.getSqs().getReportJobsQueue()} (never hardcoded).
     */
    private final AwsConfig.AwsResourceProperties awsProperties;

    /**
     * The {@code java.time} replacement for the LE {@code CSUTLDTC}/{@code CEEDAYS} date API, used for
     * the custom-range real-date validation.
     */
    private final DateValidationService dateValidationService;

    /**
     * Source of "today" for the Monthly/Yearly range derivation. COBOL used the mainframe local date
     * via {@code FUNCTION CURRENT-DATE}; here it is read from this clock as
     * {@link LocalDate#now(Clock)}. It is injected so unit tests can pin a deterministic date; there
     * is intentionally no {@code Clock} Spring bean &mdash; the production constructor defaults it.
     */
    private final Clock clock;

    /**
     * Production constructor selected by Spring component scanning. The {@link Clock} defaults to the
     * JVM's default zone (mirroring the legacy {@code FUNCTION CURRENT-DATE} behavior); only the three
     * managed collaborators are injected.
     *
     * @param sqsTemplate           the auto-configured SQS operations facade (Spring Cloud AWS 3.3.0)
     * @param awsProperties         the bound AWS resource-name properties (supplies the FIFO queue name)
     * @param dateValidationService the {@code CSUTLDTC} &rarr; {@code java.time} date validator
     */
    @Autowired
    public ReportSubmissionService(SqsTemplate sqsTemplate,
                                   AwsConfig.AwsResourceProperties awsProperties,
                                   DateValidationService dateValidationService) {
        this(sqsTemplate, awsProperties, dateValidationService, Clock.systemDefaultZone());
    }

    /**
     * Test-friendly constructor that accepts a fixed {@link Clock} so the Monthly/Yearly "today"
     * derivation is deterministic in unit tests. Not used by Spring (the application context resolves
     * the {@link #ReportSubmissionService(SqsTemplate, AwsConfig.AwsResourceProperties,
     * DateValidationService) three-argument constructor} annotated {@code @Autowired}). A {@code null}
     * clock falls back to the system default zone.
     *
     * @param sqsTemplate           the SQS operations facade
     * @param awsProperties         the bound AWS resource-name properties
     * @param dateValidationService the date validator
     * @param clock                 the clock supplying "today"; {@code null} &rarr;
     *                              {@link Clock#systemDefaultZone()}
     */
    ReportSubmissionService(SqsTemplate sqsTemplate,
                            AwsConfig.AwsResourceProperties awsProperties,
                            DateValidationService dateValidationService,
                            Clock clock) {
        this.sqsTemplate = sqsTemplate;
        this.awsProperties = awsProperties;
        this.dateValidationService = dateValidationService;
        this.clock = (clock != null) ? clock : Clock.systemDefaultZone();
    }

    // =================================================================================================
    // Public entry point
    // =================================================================================================

    /**
     * Submits a transaction report, translating the {@code CORPT00C} {@code PROCESS-ENTER-KEY} action
     * (CORPT00C.cbl L208&ndash;456). The report type is resolved with the exact COBOL
     * {@code EVALUATE TRUE} precedence &mdash; Monthly, then Yearly, then Custom, then "none selected"
     * &mdash; the date range is established (and, for Custom, validated through a fail-fast cascade),
     * the confirmation gate is applied, and on a {@code 'Y'} confirmation a single message is published
     * to the SQS FIFO queue (the TDQ {@code WRITEQ('JOBS')} replacement).
     *
     * <p>This method does not return an error object: faithful to the COBOL program &mdash; whose every
     * validation failure performed {@code SEND-TRNRPT-SCREEN} ending in {@code GO TO RETURN-TO-CICS}
     * (a hard transaction exit at line&nbsp;580) &mdash; the <strong>first</strong> validation or
     * confirmation failure throws an (unchecked) {@link ValidationException} carrying the verbatim
     * COBOL message; no further checks run. The central {@code @RestControllerAdvice} maps that
     * exception to HTTP&nbsp;400.</p>
     *
     * @param request the bound, Jakarta-validated report-criteria payload (the {@code RECEIVE MAP}
     *                replacement); the three report-type flags, the six custom-date components, and the
     *                confirmation flag are read from it
     * @return a {@link ReportSubmissionResult}: {@code submitted == true} with the success message on a
     *         {@code 'Y'} confirmation, or {@code submitted == false} with a {@code null} message on an
     *         {@code 'N'} cancellation
     * @throws ValidationException on the first validation or confirmation failure (HTTP 400), carrying
     *                             the exact COBOL message string
     */
    public ReportSubmissionResult submitReport(ReportRequest request) {
        // A null body carries no report-type selection; this is the COBOL WHEN OTHER outcome.
        if (request == null) {
            throw new ValidationException(MSG_SELECT_REPORT_TYPE);
        }

        // COBOL EVALUATE TRUE (CORPT00C.cbl L212-443): a report-type flag is "selected" when it is
        // NOT = SPACES AND NOT = LOW-VALUES (i.e. any non-blank value). Precedence is fixed:
        // Monthly first, then Yearly, then Custom, then "none selected".
        if (isSelected(request.getMonthly())) {
            return processMonthly(request);
        } else if (isSelected(request.getYearly())) {
            return processYearly(request);
        } else if (isSelected(request.getCustom())) {
            return processCustom(request);
        }

        // WHEN OTHER (CORPT00C.cbl L437-442): no report type selected.
        throw new ValidationException(MSG_SELECT_REPORT_TYPE);
    }

    // =================================================================================================
    // Report-type handlers (one per EVALUATE TRUE branch)
    // =================================================================================================

    /**
     * Monthly report (CORPT00C.cbl L213&ndash;238): the range is the current calendar month.
     *
     * <p>COBOL set the start to {@code currentYear/currentMonth/01}, then advanced to day&nbsp;1 of the
     * <em>next</em> month (rolling the year when the month exceeded 12) and computed
     * {@code DATE-OF-INTEGER(INTEGER-OF-DATE(thatDate) - 1)} &mdash; i.e. the last day of the current
     * month. The faithful {@code java.time} equivalents are {@link LocalDate#withDayOfMonth(int)
     * withDayOfMonth(1)} and {@link LocalDate#with(java.time.temporal.TemporalAdjuster)
     * with(TemporalAdjusters.lastDayOfMonth())}. "Today" comes from the injected {@link Clock}
     * (COBOL {@code FUNCTION CURRENT-DATE}).</p>
     *
     * @param request the report request (its confirmation flag drives the gate)
     * @return the submission or cancellation result
     */
    private ReportSubmissionResult processMonthly(ReportRequest request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate startDate = today.withDayOfMonth(1);
        // Equivalent to COBOL "day=1, month+1 (roll year if >12), then minus one day" = last day of
        // the current month.
        LocalDate endDate = today.with(TemporalAdjusters.lastDayOfMonth());
        return confirmAndSubmit(request, REPORT_MONTHLY, REPORT_TYPE_MONTHLY, startDate, endDate);
    }

    /**
     * Yearly report (CORPT00C.cbl L239&ndash;255): the range is the current calendar year &mdash;
     * January&nbsp;1 through December&nbsp;31 of {@code FUNCTION CURRENT-DATE}'s year (read here from
     * the injected {@link Clock}).
     *
     * @param request the report request (its confirmation flag drives the gate)
     * @return the submission or cancellation result
     */
    private ReportSubmissionResult processYearly(ReportRequest request) {
        int year = LocalDate.now(clock).getYear();
        LocalDate startDate = LocalDate.of(year, 1, 1);
        LocalDate endDate = LocalDate.of(year, 12, 31);
        return confirmAndSubmit(request, REPORT_YEARLY, REPORT_TYPE_YEARLY, startDate, endDate);
    }

    /**
     * Custom-range report (CORPT00C.cbl L256&ndash;436): validate the six operator-entered date
     * components through the exact COBOL cascade, then derive the range. The cascade is
     * <strong>fail-fast</strong> &mdash; each stage throws {@link ValidationException} with the verbatim
     * COBOL message on its first failure (modeling each COBOL {@code PERFORM SEND-TRNRPT-SCREEN} &rarr;
     * program exit), so no later stage runs after a failure.
     *
     * <p>The stages, in order, are:</p>
     * <ol>
     *   <li><strong>(5a) Empty checks</strong> (L258&ndash;303): the raw {@code start month/day/year}
     *       then {@code end month/day/year} are tested for blank; the first blank wins. (COBOL tested
     *       {@code = SPACES OR LOW-VALUES} inside an {@code EVALUATE TRUE}.)</li>
     *   <li><strong>(5b) {@code NUMVAL-C} normalization</strong> (L305&ndash;327): each component is
     *       reduced to its numeric value (non-numeric &rarr; {@code 0}) and re-padded to its field
     *       width (MM/DD &rarr; 2 digits, YYYY &rarr; 4 digits), exactly as COBOL re-stored the
     *       {@code FUNCTION NUMVAL-C} result into the {@code PIC 99}/{@code PIC 9999} screen fields.</li>
     *   <li><strong>(5c) Range checks</strong> (L329&ndash;379): month {@code > 12} and day
     *       {@code > 31} are rejected; the first failure wins. COBOL also tested {@code IS NOT NUMERIC},
     *       but after step&nbsp;5b every component is all-digits, so that guard is structurally retained
     *       yet effectively unreachable (documented below). Zero/lower-bound and impossible
     *       combinations (month {@code "00"}, day {@code "00"}, Feb&nbsp;30, &hellip;) intentionally
     *       pass here and are caught by step&nbsp;5d &mdash; exactly as in COBOL.</li>
     *   <li><strong>(5d) Real-date validation</strong> (L381&ndash;426, COBOL {@code CALL 'CSUTLDTC'}):
     *       the start date, then the end date, are validated through {@link DateValidationService}.</li>
     *   <li><strong>(5e) Build the range</strong> (L429&ndash;433): the validated components become
     *       {@link LocalDate} values carried into the confirmation gate and the SQS payload.</li>
     * </ol>
     *
     * <p><strong>{@code DateValidationService} format workaround.</strong> {@code CORPT00C} passed the
     * picture {@code 'YYYY-MM-DD'} (with dashes) to {@code CSUTLDTC}. {@code DateValidationService} does
     * not recognize a dashed pattern and would default to {@code yyyyMMdd}, which cannot parse a dashed
     * string. We therefore compose an 8-digit {@code yyyyMMdd} value (no dashes, zero-padded) from the
     * normalized components and call {@link DateValidationService#validateDate(String, String)} with
     * {@link #DATE_FORMAT_YYYYMMDD}, then inspect {@link DateValidationService.DateValidationResult#valid()}.
     * We deliberately do <em>not</em> call the {@code validateCcyymmdd(String, String)} variant: it
     * imposes a 19xx/20xx century-window restriction that {@code CORPT00C}'s {@code CSUTLDTC}/
     * {@code CEEDAYS} call did <em>not</em> have, so using it would break parity. The COBOL
     * {@code CEEDAYS} {@code '2513'} message-acceptance quirk (accept when {@code SEV-CD != '0000'} and
     * {@code MSG-NUM = '2513'}) has no {@code java.time} analogue and is intentionally not replicated;
     * the component range checks plus {@code STRICT} parsing cover every real case, so a
     * {@code valid() == false} result is simply treated as invalid.</p>
     *
     * @param request the report request (its six date components and confirmation flag are read)
     * @return the submission or cancellation result
     * @throws ValidationException on the first failing check, with the verbatim COBOL message
     */
    private ReportSubmissionResult processCustom(ReportRequest request) {
        // ---- (5a) Empty checks — exact COBOL order, fail-fast on the first blank component. ----
        requireNotEmpty(request.getStartMonth(), MSG_START_MONTH_EMPTY);
        requireNotEmpty(request.getStartDay(), MSG_START_DAY_EMPTY);
        requireNotEmpty(request.getStartYear(), MSG_START_YEAR_EMPTY);
        requireNotEmpty(request.getEndMonth(), MSG_END_MONTH_EMPTY);
        requireNotEmpty(request.getEndDay(), MSG_END_DAY_EMPTY);
        requireNotEmpty(request.getEndYear(), MSG_END_YEAR_EMPTY);

        // ---- (5b) FUNCTION NUMVAL-C normalization: numeric value (non-numeric -> 0), then re-pad. ----
        int startMonth = numvalC(request.getStartMonth());
        int startDay = numvalC(request.getStartDay());
        int startYear = numvalC(request.getStartYear());
        int endMonth = numvalC(request.getEndMonth());
        int endDay = numvalC(request.getEndDay());
        int endYear = numvalC(request.getEndYear());

        String startMonthNorm = pad2(startMonth);
        String startDayNorm = pad2(startDay);
        String startYearNorm = pad4(startYear);
        String endMonthNorm = pad2(endMonth);
        String endDayNorm = pad2(endDay);
        String endYearNorm = pad4(endYear);

        // ---- (5c) Range checks — separate ordered tests, fail-fast on the first failure. ----
        // COBOL: IF <field> IS NOT NUMERIC OR <field> > '12'/'31'. After 5b each *Norm string is
        // all-digits, so the !isAllDigits(...) guard is structurally retained for fidelity yet never
        // fires; only the numeric upper-bound (equivalent to the COBOL 2-char string compare) is
        // reachable. The year fields had only the (now-unreachable) IS NOT NUMERIC test, no bound.
        if (!isAllDigits(startMonthNorm) || startMonth > 12) {
            throw new ValidationException(MSG_START_MONTH_INVALID);
        }
        if (!isAllDigits(startDayNorm) || startDay > 31) {
            throw new ValidationException(MSG_START_DAY_INVALID);
        }
        if (!isAllDigits(startYearNorm)) {
            throw new ValidationException(MSG_START_YEAR_INVALID);
        }
        if (!isAllDigits(endMonthNorm) || endMonth > 12) {
            throw new ValidationException(MSG_END_MONTH_INVALID);
        }
        if (!isAllDigits(endDayNorm) || endDay > 31) {
            throw new ValidationException(MSG_END_DAY_INVALID);
        }
        if (!isAllDigits(endYearNorm)) {
            throw new ValidationException(MSG_END_YEAR_INVALID);
        }

        // ---- (5d) Real-date validation (CSUTLDTC -> DateValidationService). Start first, then end. ----
        // Compose an 8-digit yyyyMMdd value (no dashes) — the format workaround documented above.
        String startYyyyMmDd = compose(startYearNorm, startMonthNorm, startDayNorm);
        if (!dateValidationService.validateDate(startYyyyMmDd, DATE_FORMAT_YYYYMMDD).valid()) {
            throw new ValidationException(MSG_START_DATE_INVALID);
        }
        String endYyyyMmDd = compose(endYearNorm, endMonthNorm, endDayNorm);
        if (!dateValidationService.validateDate(endYyyyMmDd, DATE_FORMAT_YYYYMMDD).valid()) {
            throw new ValidationException(MSG_END_DATE_INVALID);
        }

        // ---- (5e) Build the validated LocalDate range (COBOL WS-START-DATE / WS-END-DATE, YYYY-MM-DD).
        // Step 5d confirmed both are real calendar dates under STRICT resolution, so LocalDate.of(...)
        // cannot throw here.
        LocalDate startDate = LocalDate.of(startYear, startMonth, startDay);
        LocalDate endDate = LocalDate.of(endYear, endMonth, endDay);

        return confirmAndSubmit(request, REPORT_CUSTOM, REPORT_TYPE_CUSTOM, startDate, endDate);
    }

    // =================================================================================================
    // Confirmation gate + SQS publish (COBOL SUBMIT-JOB-TO-INTRDR / WIRTE-JOBSUB-TDQ)
    // =================================================================================================

    /**
     * Applies the confirmation gate to all three report types and, on confirmation, publishes the
     * report-submission message. This is the {@code SUBMIT-JOB-TO-INTRDR} paragraph (CORPT00C.cbl
     * L462&ndash;510) together with the success-message construction (L445&ndash;456).
     *
     * <p>Confirmation semantics, preserving the COBOL ordering exactly:</p>
     * <ul>
     *   <li><strong>blank/empty</strong> (COBOL {@code CONFIRMI = SPACES OR LOW-VALUES}, L464) &rarr;
     *       throw {@link ValidationException} "{@code Please confirm to print the <name> report...}".</li>
     *   <li><strong>{@code 'Y'} / {@code 'y'}</strong> (L478) &rarr; publish the message and return a
     *       success result whose message is "{@code <name> report submitted for printing ...}".</li>
     *   <li><strong>{@code 'N'} / {@code 'n'}</strong> (L480) &rarr; cancel silently: do not publish,
     *       do not throw, and return a {@code submitted == false} result with a {@code null} message
     *       (COBOL cleared the screen via {@code INITIALIZE-ALL-FIELDS} and set the error flag so the
     *       success message was suppressed).</li>
     *   <li><strong>any other value</strong> (L484) &rarr; throw {@link ValidationException}
     *       "{@code "<value>" is not a valid value to confirm...}".</li>
     * </ul>
     *
     * @param request    the report request (its confirmation flag is read)
     * @param reportName the user-facing report name ({@code Monthly}/{@code Yearly}/{@code Custom})
     * @param reportType the canonical machine report-type token carried in the payload
     * @param startDate  the inclusive range start
     * @param endDate    the inclusive range end
     * @return the submission result on confirmation, or the cancellation result on {@code 'N'}/{@code 'n'}
     * @throws ValidationException when confirmation is blank or an unrecognized value
     */
    private ReportSubmissionResult confirmAndSubmit(ReportRequest request, String reportName,
                                                    String reportType, LocalDate startDate,
                                                    LocalDate endDate) {
        String confirm = request.getConfirm();

        // COBOL L464: IF CONFIRMI = SPACES OR LOW-VALUES -> "Please confirm ...".
        if (confirm == null || confirm.isBlank()) {
            throw new ValidationException(MSG_CONFIRM_PREFIX + reportName + MSG_CONFIRM_SUFFIX);
        }

        // COBOL EVALUATE on CONFIRMI (L477-494): Y/y proceed, N/n cancel, otherwise error.
        if (confirm.equals("Y") || confirm.equals("y")) {
            // TDQ WRITEQ('JOBS') / JES submission -> single SQS publish (the only online->batch bridge).
            publishReportJob(reportType, startDate, endDate);
            // Success message (COBOL L449-451): STRING WS-REPORT-NAME DELIMITED BY SPACE
            // ' report submitted for printing ...' DELIMITED BY SIZE INTO WS-MESSAGE.
            String message = reportName + MSG_SUBMITTED_SUFFIX;
            return new ReportSubmissionResult(true, message, reportType, startDate, endDate);
        } else if (confirm.equals("N") || confirm.equals("n")) {
            // COBOL L480-483: cancel — clear fields, suppress the success message, do not submit.
            return new ReportSubmissionResult(false, null, reportType, startDate, endDate);
        } else {
            // COBOL L484-490: '"' CONFIRMI DELIMITED BY SPACE '" is not a valid value to confirm...'.
            throw new ValidationException(
                    MSG_INVALID_CONFIRM_PREFIX + delimitBySpace(confirm) + MSG_INVALID_CONFIRM_SUFFIX);
        }
    }

    /**
     * Publishes the single report-submission message &mdash; the migration of the entire COBOL
     * "build a JCL deck and loop writing each line to TDQ {@code JOBS}" block (the {@code JOB-LINES}
     * loop plus {@code WIRTE-JOBSUB-TDQ}, CORPT00C.cbl L496&ndash;535).
     *
     * <p><strong>TDQ {@code WRITEQ('JOBS')} / JES submission &rarr; one SQS publish to
     * {@code carddemo-report-jobs.fifo}</strong> (AAP &sect;0.6.3). The JCL deck is a mainframe artifact
     * and is not reproduced; the downstream Spring Batch report job is parameterized solely by this
     * message ({@code reportType} + {@code startDate} + {@code endDate}). The queue name is resolved
     * from {@link com.cardemo.config.AwsConfig.AwsResourceProperties} and is never hardcoded.</p>
     *
     * <p>Because the destination is a <strong>FIFO</strong> queue, both a {@code MessageGroupId} (a
     * fixed group preserving TDQ-style ordering) and a {@code MessageDeduplicationId} (a fresh
     * {@link UUID} per submission, so a genuinely confirmed re-submission is never deduplicated) are
     * set. The {@link SqsTemplate} serializes the payload record to JSON via the configured Jackson
     * converter; the {@link LocalDate} fields render as ISO-8601 {@code YYYY-MM-DD}, matching the COBOL
     * {@code PARM-START-DATE}/{@code PARM-END-DATE} ({@code YYYY-MM-DD}) contract.</p>
     *
     * <p><strong>Publish-failure path.</strong> COBOL checked {@code WS-RESP-CD} after the
     * {@code WRITEQ TD} and, on a non-{@code NORMAL} response, emitted "{@code Unable to Write TDQ
     * (JOBS)...}" (L515&ndash;535). Here the failure is not swallowed: it is logged at {@code ERROR}
     * and the unchecked messaging exception is allowed to propagate to the central
     * {@code @RestControllerAdvice} (&rarr; HTTP&nbsp;500). It is deliberately <em>not</em> wrapped in
     * a {@code CardDemoException} (that base type is {@code abstract} and cannot be instantiated).</p>
     *
     * @param reportType the canonical machine report-type token
     * @param startDate  the inclusive range start
     * @param endDate    the inclusive range end
     */
    private void publishReportJob(String reportType, LocalDate startDate, LocalDate endDate) {
        String queue = awsProperties.getSqs().getReportJobsQueue();
        ReportJobMessage message = new ReportJobMessage(reportType, startDate, endDate);
        try {
            sqsTemplate.send(to -> to
                    .queue(queue)
                    .payload(message)
                    .messageGroupId(SQS_MESSAGE_GROUP_ID)                 // FIFO ordering group
                    .messageDeduplicationId(UUID.randomUUID().toString())); // unique per submission
        } catch (RuntimeException ex) {
            // COBOL parity (WIRTE-JOBSUB-TDQ): WS-RESP-CD != NORMAL -> 'Unable to Write TDQ (JOBS)...'.
            // Surface the failure (log + propagate); the central advice renders HTTP 500. Do NOT wrap
            // in CardDemoException (abstract).
            log.error("Failed to publish report-submission message to SQS queue [{}] "
                    + "(TDQ WRITEQ('JOBS') parity path); reportType={}", queue, reportType, ex);
            throw ex;
        }
        log.info("Published report-submission message to SQS queue [{}]: reportType={}, "
                + "startDate={}, endDate={}", queue, reportType, startDate, endDate);
    }

    // =================================================================================================
    // Private helpers
    // =================================================================================================

    /**
     * Reports whether a report-type flag was selected, mirroring the COBOL test
     * {@code <flag> NOT = SPACES AND NOT = LOW-VALUES}: any non-{@code null}, non-blank value counts as
     * selected (the COBOL program did not require the flag to equal {@code 'Y'}).
     *
     * @param flag the raw flag value
     * @return {@code true} when {@code flag} is non-{@code null} and not blank
     */
    private static boolean isSelected(String flag) {
        return flag != null && !flag.isBlank();
    }

    /**
     * Throws {@link ValidationException} with the supplied verbatim COBOL message when {@code value} is
     * blank, mirroring the COBOL {@code = SPACES OR LOW-VALUES} empty test (and its hard exit).
     *
     * @param value   the raw component value
     * @param message the verbatim COBOL message to carry on failure
     * @throws ValidationException when {@code value} is {@code null} or blank
     */
    private static void requireNotEmpty(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(message);
        }
    }

    /**
     * {@code FUNCTION NUMVAL-C} analogue: returns the numeric value of a screen component, treating a
     * blank or non-numeric value as {@code 0} (exactly as {@code NUMVAL-C} yields {@code 0} for
     * non-numeric input). Surrounding whitespace is ignored. The bound DTO already constrains these
     * fields to digits via {@code @Pattern}, so the non-numeric branch is defensive; a value too wide
     * to fit an {@code int} also collapses to {@code 0} (and is then rejected downstream as an invalid
     * date), never throwing.
     *
     * @param raw the raw component value (may be {@code null})
     * @return the parsed non-negative integer value, or {@code 0} for blank/non-numeric/oversized input
     */
    private static int numvalC(String raw) {
        if (raw == null) {
            return 0;
        }
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return 0;
        }
        try {
            long value = Long.parseLong(stripped);
            if (value < 0L || value > Integer.MAX_VALUE) {
                return 0;
            }
            return (int) value;
        } catch (NumberFormatException ex) {
            // NUMVAL-C semantics: non-numeric content yields zero.
            return 0;
        }
    }

    /**
     * Left-zero-pads a value to two digits, reproducing the COBOL re-store into a {@code PIC 99}
     * screen field after {@code NUMVAL-C} (CORPT00C.cbl L307/L311/L319/L323).
     *
     * @param value a non-negative component value
     * @return the value as a 2-digit (or wider, if {@code value > 99}) decimal string
     */
    private static String pad2(int value) {
        return String.format("%02d", value);
    }

    /**
     * Left-zero-pads a value to four digits, reproducing the COBOL re-store into a {@code PIC 9999}
     * screen field after {@code NUMVAL-C} (CORPT00C.cbl L315/L327).
     *
     * @param value a non-negative component value
     * @return the value as a 4-digit (or wider, if {@code value > 9999}) decimal string
     */
    private static String pad4(int value) {
        return String.format("%04d", value);
    }

    /**
     * Reports whether a string is non-empty and composed entirely of ASCII digits, mirroring the COBOL
     * {@code IS NUMERIC} class test. Used by the range checks to retain the COBOL {@code IS NOT NUMERIC}
     * guard for structural fidelity (it is unreachable after {@code NUMVAL-C} normalization).
     *
     * @param value the value to test
     * @return {@code true} when {@code value} is non-empty and all characters are {@code '0'..'9'}
     */
    private static boolean isAllDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Composes an 8-digit {@code yyyyMMdd} value (no separators) from the normalized, zero-padded
     * year, month, and day components &mdash; the format workaround for
     * {@link DateValidationService#validateDate(String, String)} described in
     * {@link #processCustom(ReportRequest)}.
     *
     * @param yearNorm  the 4-digit year
     * @param monthNorm the 2-digit month
     * @param dayNorm   the 2-digit day
     * @return the concatenated {@code yyyyMMdd} string
     */
    private static String compose(String yearNorm, String monthNorm, String dayNorm) {
        return yearNorm + monthNorm + dayNorm;
    }

    /**
     * Returns the portion of {@code value} up to (but excluding) the first space, reproducing the
     * COBOL {@code STRING ... DELIMITED BY SPACE} behavior used when echoing the rejected confirmation
     * value into the "not a valid value to confirm" message (CORPT00C.cbl L487).
     *
     * @param value the raw value (may be {@code null})
     * @return the text before the first space, the whole value when it contains no space, or {@code ""}
     *         when {@code value} is {@code null}
     */
    private static String delimitBySpace(String value) {
        if (value == null) {
            return "";
        }
        int space = value.indexOf(' ');
        return (space >= 0) ? value.substring(0, space) : value;
    }

    // =================================================================================================
    // Nested payload / result types (kept local to this service per the Minimal Change Clause)
    // =================================================================================================

    /**
     * The structured report-submission message published to the SQS FIFO queue &mdash; the modern
     * replacement for the COBOL JCL deck written to the {@code JOBS} TDQ. It carries only the
     * downstream report job's parameters: the canonical machine report type and the inclusive date
     * range. The {@link LocalDate} fields serialize as ISO-8601 {@code YYYY-MM-DD}, matching the legacy
     * {@code PARM-START-DATE}/{@code PARM-END-DATE} contract. Kept as a nested type (not a separate
     * top-level DTO) per the Minimal Change Clause (AAP &sect;0.7.1).
     *
     * @param reportType the canonical machine report-type token ({@code MONTHLY}/{@code YEARLY}/{@code CUSTOM})
     * @param startDate  the inclusive range start
     * @param endDate    the inclusive range end
     */
    public record ReportJobMessage(String reportType, LocalDate startDate, LocalDate endDate) {
    }

    /**
     * The outcome returned to the caller (the {@code ReportController}). On a {@code 'Y'} confirmation,
     * {@code submitted} is {@code true} and {@code message} holds the verbatim COBOL success text; on an
     * {@code 'N'} cancellation, {@code submitted} is {@code false} and {@code message} is {@code null}
     * (COBOL showed no message on cancel). The report type and derived range are always included for
     * the caller's convenience.
     *
     * @param submitted  {@code true} when the message was published; {@code false} on cancellation
     * @param message    the success message, or {@code null} on cancellation
     * @param reportType the canonical machine report-type token
     * @param startDate  the inclusive range start
     * @param endDate    the inclusive range end
     */
    public record ReportSubmissionResult(boolean submitted, String message, String reportType,
                                         LocalDate startDate, LocalDate endDate) {
    }
}
