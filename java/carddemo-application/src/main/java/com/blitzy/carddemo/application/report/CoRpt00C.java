/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.application.report;

// JEP 511 (finalized in Java 25): a single declaration imports all packages
// exported by java.base. This brings in:
//   * java.util.Objects                     -- constructor null-guards (JEP 513
//                                              flexible constructor bodies)
//   * java.lang.String / Integer / Long     -- the verbatim error message
//                                              constants and all parsing helpers
//   * java.time.LocalDate /
//     LocalDateTime / LocalTime              -- Monthly/Yearly date range
//                                              computation and POPULATE-HEADER-INFO
//                                              date/time formatting
//   * java.time.format.DateTimeFormatter    -- MM/dd/yy and HH:mm:ss patterns
//                                              translating WS-CURDATE-MM-DD-YY and
//                                              WS-CURTIME for CURDATEO/CURTIMEO
//   * java.util.Locale                      -- Locale.ROOT for deterministic,
//                                              locale-independent formatting
//   * java.util.OptionalInt                 -- tryParseInt() helper for COBOL
//                                              NUMVAL-C / IS NOT NUMERIC checks
// Per AAP §0.5.1 / §0.6.7 / §0.7.3 — replaces individual java.util/java.time
// imports with a single module-level import using a finalized Java 25 feature.
import module java.base;

// SLF4J facade per AAP §0.5.1 (org.slf4j:slf4j-api 2.0.16). Replaces COBOL
// DISPLAY statements; backend (Logback) is wired by the carddemo-app
// composition root.
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Project-internal imports per the file schema (depends_on_files).
import com.blitzy.carddemo.application.ProgramRegistry;
import com.blitzy.carddemo.application.util.DateValidator;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.commarea.CardDemoCommarea;
import com.blitzy.carddemo.domain.commarea.PgmContext;
import com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey;
import com.blitzy.carddemo.domain.text.ScreenTitle;
import com.blitzy.carddemo.domain.text.SystemMessages;

/**
 * Java 25 LTS translation of the COBOL online program
 * {@code CORPT00C} ("Print Transaction Reports"; CICS transaction
 * {@code CR00}; {@code app/cbl/CORPT00C.cbl}, 650 lines).
 *
 * <p>This online program prompts the operator to choose a Monthly, Yearly,
 * or Custom date range for a transaction report, requires a Y/N
 * confirmation, and submits a JES batch job to print the report.
 *
 * <h2>Source artifacts</h2>
 * <ul>
 *   <li>COBOL program: {@code app/cbl/CORPT00C.cbl}
 *       ({@code PROGRAM-ID. CORPT00C}; transaction id {@code "CR00"}).</li>
 *   <li>BMS map: {@code app/bms/CORPT00.bms} (mapset {@code CORPT00},
 *       map {@code CORPT0A}, size 24x80).</li>
 *   <li>Symbolic copybook: {@code app/cpy-bms/CORPT00.CPY}
 *       (CORPT0AI/CORPT0AO with 18 leaf fields).</li>
 *   <li>Copybooks consumed: {@code COCOM01Y} (commarea), {@code COTTL01Y}
 *       (titles), {@code CSDAT01Y} (date/time formatting),
 *       {@code CSMSG01Y} (invalid-key message), {@code CSMSG02Y}
 *       (auxiliary messages), {@code CVTRA05Y} (TRAN-RECORD layout).</li>
 * </ul>
 *
 * <h2>TDQ-to-direct-invocation deviation (AAP §0.4.1 / §0.6.8)</h2>
 * <p>In COBOL, dispatch of the batch report job (the TRANREPT cataloged
 * procedure) is performed by writing an 18-line JCL deck to the CICS
 * extra-partition Transient Data Queue named {@code JOBS} via {@code EXEC
 * CICS WRITEQ TD QUEUE('JOBS')}. CICS TDQ is out of scope for the Java
 * translation (AAP §0.2.2 explicitly excludes CICS configuration and
 * mainframe orchestration). The dispatch is therefore replaced with direct
 * invocation of the nested {@link ReportSubmitter} functional interface
 * (TDQ replacement). The composition root in {@code carddemo-app} wires
 * {@code ReportSubmitter} to a lambda that invokes
 * {@code com.blitzy.carddemo.application.transaction.CbTrn03C} directly,
 * preserving observable behavior end-to-end. The verbatim error message
 * {@code "Unable to Write TDQ (JOBS)..."} is preserved on the dispatch-failure
 * path for byte-for-byte parity with the COBOL baseline.
 *
 * <p>The COBOL JCL template construction (lines 81-127 of CORPT00C.cbl,
 * the {@code JOB-DATA} structure of 1000 JOB-LINES) is dropped because the
 * JVM executes the batch use case directly rather than building a JCL deck
 * and writing it line-by-line to TDQ.
 *
 * <h2>COBOL Paragraph &rarr; Java Method Mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} (lines 163-202) &rarr;
 *       {@link #execute(CoRpt00Input, CardDemoCommarea)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} (lines 208-456) &rarr;
 *       {@link #processEnterKey(CoRpt00Input, CardDemoCommarea, State)}</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} (lines 462-510) +
 *       {@code WIRTE-JOBSUB-TDQ} (lines 515-535) &rarr;
 *       {@link #submitJobToIntrdr(CoRpt00Input, CardDemoCommarea, State)}
 *       (the two COBOL paragraphs collapse into one because the
 *       per-JOB-LINE TDQ write loop is replaced by a single
 *       {@link ReportSubmitter#submit} call)</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} (lines 540-551) &rarr;
 *       {@link #returnToPrevScreen(CardDemoCommarea, String)}</li>
 *   <li>{@code SEND-TRNRPT-SCREEN} (lines 556-580) +
 *       {@code POPULATE-HEADER-INFO} (lines 609-628) &rarr;
 *       {@link #sendScreen(CoRpt00Input, CardDemoCommarea, State)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} (lines 633-646) &rarr;
 *       {@link #initializeAllFields(State)}</li>
 * </ul>
 *
 * <h2>Java 25 finalized features used</h2>
 * <ul>
 *   <li>JEP 511 module imports: {@code import module java.base;} replaces
 *       individual java.util/java.time/java.time.format imports.</li>
 *   <li>JEP 513 flexible constructor bodies: {@link Outcome.Xctl} and
 *       {@link Outcome.SendMap} run validation before the implicit
 *       canonical field-binding.</li>
 *   <li>Sealed {@link Outcome} interface with two record permits enforces
 *       exhaustiveness at composition-root call sites.</li>
 *   <li>Pattern-matching {@code switch} over the {@link AidKey} sealed
 *       hierarchy with all 16 permits and NO {@code default} branch
 *       (AAP §0.6.2 mandate).</li>
 *   <li>{@link java.time}/{@link java.time.format.DateTimeFormatter} —
 *       never {@link java.util.Date} or {@link java.util.Calendar}.</li>
 * </ul>
 *
 * <h2>Architectural compliance (AAP §0.7.4 forbidden constructs)</h2>
 * <ul>
 *   <li>No Spring, no Lombok, no Jakarta Bean Validation, no JPA.</li>
 *   <li>No {@code ThreadLocal} (per-invocation {@link State} is method-scoped).</li>
 *   <li>No {@code double}/{@code float}; no monetary values in this program.</li>
 *   <li>No {@code java.util.Date}/{@code java.util.Calendar}.</li>
 *   <li>No {@code java.io.File} (no file I/O in this program).</li>
 *   <li>No {@code --enable-preview}; no preview features.</li>
 *   <li>No reflection/dynamic proxies.</li>
 * </ul>
 *
 * <h2>Concurrency model</h2>
 * <p>{@code CoRpt00C} is thread-safe by construction: the three injected
 * collaborators ({@link ProgramRegistry}, {@link DateValidator},
 * {@link ReportSubmitter}) are immutable shared state, and the per-call
 * {@link State} is allocated locally inside
 * {@link #execute(CoRpt00Input, CardDemoCommarea)} and never escapes the
 * call stack. Multiple virtual threads (per AAP §0.6.6) may invoke
 * {@link #execute} concurrently with no synchronization.
 *
 * <h2>Self-registration with ProgramRegistry</h2>
 * <p>The constructor registers {@code this::dispatch} under the COBOL
 * {@code PROGRAM-ID} {@code "CORPT00C"} so that other programs that
 * {@code EXEC CICS XCTL PROGRAM('CORPT00C')} can route through
 * {@link ProgramRegistry#invoke(String, CardDemoCommarea)}. The
 * {@link #dispatch} helper adapts the
 * {@code ProgramRegistry.ProgramHandler} signature
 * ({@code CardDemoCommarea -> CardDemoCommarea}) to the
 * {@link #execute(CoRpt00Input, CardDemoCommarea)} signature by supplying
 * an empty {@link CoRpt00Input} (first-entry semantics) and extracting the
 * commarea from the returned {@link Outcome}.
 *
 * @see CoRpt00Input
 * @see CoRpt00Output
 * @see ProgramRegistry
 * @see DateValidator
 * @since 1.0.0
 */
@CobolProgram(
        value = "CORPT00C",
        sourcePath = "app/cbl/CORPT00C.cbl",
        translationDate = "2025-01-20",
        notes = "Online Print Transaction Reports submission program "
                + "(CICS transaction CR00). CICS TDQ JOBS write "
                + "(EXEC CICS WRITEQ TD QUEUE('JOBS')) is replaced with "
                + "direct invocation of an injected ReportSubmitter "
                + "functional interface (composition-root-wired to CbTrn03C). "
                + "JCL template construction (JOB-LINES) is dropped because "
                + "the JVM executes the batch use case directly. Per AAP "
                + "§0.4.1 and §0.6.8."
)
public final class CoRpt00C {

    // ---------------------------------------------------------------------
    // SLF4J logger (org.slf4j:slf4j-api 2.0.16) — replaces COBOL DISPLAY
    // statements per AAP §0.5.1. Backend wired by carddemo-app via
    // Logback (logback-classic 1.5.19).
    // ---------------------------------------------------------------------
    private static final Logger LOGGER = LoggerFactory.getLogger(CoRpt00C.class);

    // ---------------------------------------------------------------------
    // Program identity constants (translates COBOL WORKING-STORAGE
    // VALUE clauses at app/cbl/CORPT00C.cbl:L37-L38).
    // ---------------------------------------------------------------------

    /** COBOL {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'} (line 37). */
    private static final String PGMNAME = "CORPT00C";

    /** COBOL {@code WS-TRANID PIC X(04) VALUE 'CR00'} (line 38). */
    private static final String TRANID = "CR00";

    /**
     * PF3 back-target hard-coded at MAIN-PARA line 188:
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}.
     */
    private static final String BACK_PROGRAM = ProgramRegistry.CO_MEN_01C;

    /**
     * EIBCALEN=0 cold-start target (lines 172-174):
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}.
     */
    private static final String SIGNON_PROGRAM = ProgramRegistry.CO_SGN_00C;

    /**
     * BMS mapset name per {@code MAPSET('CORPT00')} at lines 563-577.
     * Kept as a documenting constant because the Java translation does
     * not emit MAP/MAPSET CICS verbs directly; the composition root's
     * 3270 adapter consumes this label.
     */
    private static final String MAPSET_NAME = "CORPT00";

    /**
     * BMS map name per {@code MAP('CORPT0A')} at lines 564-572. Same
     * documenting role as {@link #MAPSET_NAME}.
     */
    private static final String MAP_NAME = "CORPT0A";

    /**
     * COBOL {@code WS-DATE-FORMAT PIC X(10) VALUE 'YYYY-MM-DD'}
     * (line 72). Passed to {@link DateValidator#validate(String, String)}
     * as the CSUTLDTC date-format parameter for both the start-date and
     * end-date validations.
     */
    private static final String DATE_FORMAT_PATTERN = "YYYY-MM-DD";

    /**
     * The CEEDAYS LE message number {@code "2513"} (FC-UNSUPP-RANGE — date
     * is past the supported Gregorian range) is treated as a non-fatal
     * warning by the COBOL source (lines 399-405 and 419-425). This
     * matches the CSUTLDTC contract documented in
     * {@code app/cbl/CSUTLDTC.cbl}.
     */
    private static final String MSG_NUM_CEEDAYS_WARNING = "2513";

    // ---------------------------------------------------------------------
    // Report-name constants for the WS-REPORT-NAME field (PIC X(10)).
    // Translates COBOL MOVE statements at lines 214 ('Monthly'), 240
    // ('Yearly'), and 433 ('Custom').
    // ---------------------------------------------------------------------

    /** Value used for {@code WS-REPORT-NAME} when MONTHLYI is selected. */
    private static final String REPORT_NAME_MONTHLY = "Monthly";

    /** Value used for {@code WS-REPORT-NAME} when YEARLYI is selected. */
    private static final String REPORT_NAME_YEARLY = "Yearly";

    /** Value used for {@code WS-REPORT-NAME} when CUSTOMI is selected. */
    private static final String REPORT_NAME_CUSTOM = "Custom";

    // ---------------------------------------------------------------------
    // Verbatim error messages (twenty values, preserved byte-for-byte from
    // app/cbl/CORPT00C.cbl per AAP §0.7.1 — preserve-as-is mandate).
    // The trailing "..." is preserved in every message. Per the COBOL
    // STRING ... DELIMITED BY SPACE/SIZE behavior, two messages
    // (MSG_CONFIRM_PROMPT_FORMAT, MSG_INVALID_CONFIRM_FORMAT) are
    // parameterized templates built with String.format / concatenation.
    // ---------------------------------------------------------------------

    /** COBOL line 261. */
    private static final String MSG_SDT_MM_EMPTY =
            "Start Date - Month can NOT be empty...";
    /** COBOL line 268. */
    private static final String MSG_SDT_DD_EMPTY =
            "Start Date - Day can NOT be empty...";
    /** COBOL line 275. */
    private static final String MSG_SDT_YYYY_EMPTY =
            "Start Date - Year can NOT be empty...";
    /** COBOL line 282. */
    private static final String MSG_EDT_MM_EMPTY =
            "End Date - Month can NOT be empty...";
    /** COBOL line 289. */
    private static final String MSG_EDT_DD_EMPTY =
            "End Date - Day can NOT be empty...";
    /** COBOL line 296. */
    private static final String MSG_EDT_YYYY_EMPTY =
            "End Date - Year can NOT be empty...";
    /** COBOL line 331. */
    private static final String MSG_SDT_MM_INVALID =
            "Start Date - Not a valid Month...";
    /** COBOL line 340. */
    private static final String MSG_SDT_DD_INVALID =
            "Start Date - Not a valid Day...";
    /** COBOL line 348. */
    private static final String MSG_SDT_YYYY_INVALID =
            "Start Date - Not a valid Year...";
    /** COBOL line 357. */
    private static final String MSG_EDT_MM_INVALID =
            "End Date - Not a valid Month...";
    /** COBOL line 366. */
    private static final String MSG_EDT_DD_INVALID =
            "End Date - Not a valid Day...";
    /** COBOL line 374. */
    private static final String MSG_EDT_YYYY_INVALID =
            "End Date - Not a valid Year...";
    /** COBOL line 400. */
    private static final String MSG_SDT_INVALID =
            "Start Date - Not a valid date...";
    /** COBOL line 420. */
    private static final String MSG_EDT_INVALID =
            "End Date - Not a valid date...";
    /** COBOL line 438 ('Select a report type to print report...'). */
    private static final String MSG_SELECT_TYPE =
            "Select a report type to print report...";
    /** COBOL line 531 ('Unable to Write TDQ (JOBS)...'). */
    private static final String MSG_TDQ_ERROR =
            "Unable to Write TDQ (JOBS)...";

    // ---------------------------------------------------------------------
    // Header date/time formatters per POPULATE-HEADER-INFO (lines
    // 618-628). Locale.ROOT is mandatory: deterministic, locale-
    // independent format avoids any locale-specific separators that
    // would break byte-for-byte parity per AAP §0.6.5.
    // ---------------------------------------------------------------------

    /**
     * COBOL {@code WS-CURDATE-MM-DD-YY} format (8 chars): {@code MM/DD/YY}.
     * Lines 618-622 build this from CURRENT-DATE pieces; here we let
     * {@link DateTimeFormatter} do the job atomically.
     */
    private static final DateTimeFormatter DATE_MM_DD_YY =
            DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);

    /**
     * COBOL {@code WS-CURTIME-HH-MM-SS} format (8 chars): {@code HH:MM:SS}.
     * Lines 624-628.
     */
    private static final DateTimeFormatter TIME_HH_MM_SS =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    // ---------------------------------------------------------------------
    // Constructor-injected collaborators per AAP §0.3 hexagonal
    // architecture. No Spring container; the composition root in
    // carddemo-app wires concrete implementations.
    // ---------------------------------------------------------------------

    /**
     * Dynamic XCTL/LINK dispatch registry. {@code CoRpt00C} self-registers
     * its {@link #dispatch} handler under the program-id {@code "CORPT00C"}
     * in this constructor so that other CardDemo programs can XCTL into
     * CORPT00C by name. Never {@code null}.
     */
    private final ProgramRegistry programRegistry;

    /**
     * CSUTLDTC date-validation utility. {@link DateValidator} is a utility
     * class with a private constructor and only static methods, so the
     * caller cannot pass a non-null instance. The field is retained for
     * file-schema compatibility and to document the dependency; all calls
     * use the static {@link DateValidator#validate(String, String)} API.
     * The constructor does NOT null-guard this parameter for the same
     * reason.
     */
    private final DateValidator dateValidator;

    /**
     * TDQ replacement: the functional interface that dispatches the
     * TRANREPT batch report job. Wired by the composition root to invoke
     * {@code CbTrn03C} directly. Per AAP §0.4.1 / §0.6.8. Never
     * {@code null}.
     */
    private final ReportSubmitter reportSubmitter;

    /**
     * Constructs a {@code CoRpt00C} use-case instance with the three
     * collaborators required to service the {@code CR00} CICS transaction.
     *
     * <p>The constructor self-registers under the COBOL {@code PROGRAM-ID}
     * {@code "CORPT00C"} via
     * {@link ProgramRegistry#register(String, ProgramRegistry.ProgramHandler)}.
     * Per the registry contract, double-registration of the same normalized
     * program name throws {@link IllegalStateException}; the composition
     * root is therefore responsible for instantiating {@code CoRpt00C}
     * exactly once per {@code ProgramRegistry}.
     *
     * @param programRegistry  dynamic-CALL routing registry; must not be
     *                         {@code null}
     * @param dateValidator    CSUTLDTC equivalent (utility class; may be
     *                         {@code null} because all calls are static —
     *                         the parameter is retained for schema
     *                         compatibility)
     * @param reportSubmitter  TDQ replacement: lambda that invokes the
     *                         TRANREPT batch driver; must not be
     *                         {@code null}
     * @throws NullPointerException  if {@code programRegistry} or
     *                               {@code reportSubmitter} is {@code null}
     * @throws IllegalStateException if {@code "CORPT00C"} is already
     *                               registered with {@code programRegistry}
     */
    public CoRpt00C(
            ProgramRegistry programRegistry,
            DateValidator dateValidator,
            ReportSubmitter reportSubmitter) {
        this.programRegistry = Objects.requireNonNull(
                programRegistry, "programRegistry");
        // DateValidator is a utility class (private constructor); the
        // reference is documentary. All calls use the static API. We do
        // NOT null-guard this parameter because the static API works
        // regardless of whether a non-null instance was passed.
        this.dateValidator = dateValidator;
        this.reportSubmitter = Objects.requireNonNull(
                reportSubmitter, "reportSubmitter");

        // Per AAP §0.4.2: register self under the COBOL PROGRAM-ID so that
        // any other program performing EXEC CICS XCTL PROGRAM('CORPT00C')
        // dispatches to us via ProgramRegistry.invoke(...). The handler
        // signature is ProgramHandler.handle(CardDemoCommarea) →
        // CardDemoCommarea (does not carry an input record); the
        // dispatch(...) adapter synthesizes an empty CoRpt00Input for the
        // first-entry case and unwraps the resulting Outcome's commarea.
        programRegistry.register(PGMNAME, this::dispatch);
    }

    // =====================================================================
    // Outcome sealed interface — Java analog of the CICS verb selected at
    // the end of MAIN-PARA: either SEND-MAP + RETURN to redraw the screen,
    // or XCTL to transfer control to another program. Per AAP §0.6.2 the
    // sealed permits enforce exhaustiveness in downstream switches.
    // =====================================================================

    /**
     * Sealed disposition type returned by
     * {@link CoRpt00C#execute(CoRpt00Input, CardDemoCommarea)}. Exactly
     * two outcomes are permitted:
     * <ul>
     *   <li>{@link SendMap} &mdash; redraw the {@code CORPT0A} BMS map
     *       with the staged output and re-arm the {@code CR00}
     *       transaction for the next AID byte (COBOL idiom:
     *       {@code EXEC CICS SEND MAP(...) FROM(CORPT0AO)} +
     *       {@code EXEC CICS RETURN TRANSID('CR00')
     *       COMMAREA(CARDDEMO-COMMAREA)}).</li>
     *   <li>{@link Xctl} &mdash; transfer control to another CardDemo
     *       program with an updated commarea (COBOL idiom:
     *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
     *       COMMAREA(CARDDEMO-COMMAREA)}).</li>
     * </ul>
     *
     * <p>The sealed permits enforce exhaustiveness in downstream
     * pattern-matching switches at the composition root (AAP §0.6.2).
     */
    public sealed interface Outcome permits Outcome.SendMap, Outcome.Xctl {

        /**
         * Returns the commarea to be carried into the next CICS
         * interaction (next SEND-MAP redraw or next XCTL target).
         *
         * @return the updated commarea (never {@code null})
         */
        CardDemoCommarea commarea();

        /**
         * COBOL {@code SEND-TRNRPT-SCREEN} + {@code RETURN TRANSID(WS-TRANID)
         * COMMAREA(CARDDEMO-COMMAREA)} (lines 556-580 and 199-202): redraw
         * the CORPT0A BMS map and re-arm the {@code CR00} transaction for
         * the next AID byte. The {@code eraseScreen} flag corresponds to
         * the COBOL {@code WS-SEND-ERASE-FLG} that controls whether the
         * BMS hardware should erase the previous screen contents before
         * painting the new map (COBOL line 562: {@code IF SEND-ERASE-YES
         * EXEC CICS SEND ... ERASE}).
         *
         * @param output      the fully populated output map (never
         *                    {@code null})
         * @param commarea    the outbound commarea (PgmContext=REENTER on
         *                    every SendMap; never {@code null})
         * @param eraseScreen {@code true} if the hardware should erase the
         *                    previous screen contents
         */
        record SendMap(
                CoRpt00Output output,
                CardDemoCommarea commarea,
                boolean eraseScreen)
                implements Outcome {

            /**
             * Canonical (compact) constructor — JEP 513 flexible
             * constructor bodies. Rejects {@code null} {@code output} and
             * {@code commarea} with {@link NullPointerException} before
             * the implicit field binding.
             *
             * @throws NullPointerException if {@code output} or
             *                              {@code commarea} is
             *                              {@code null}
             */
            public SendMap {
                Objects.requireNonNull(output, "output");
                Objects.requireNonNull(commarea, "commarea");
            }
        }

        /**
         * COBOL {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
         * COMMAREA(CARDDEMO-COMMAREA)} (lines 548-551): transfer control
         * to another Java program in the CardDemo family. The composition
         * root in {@code carddemo-app} resolves {@code targetProgram} via
         * {@link ProgramRegistry#invoke(String, CardDemoCommarea)}.
         *
         * @param targetProgram the COBOL PROGRAM-ID of the target (for
         *                      example {@code "COSGN00C"} or
         *                      {@code "COMEN01C"}); must not be
         *                      {@code null} or blank
         * @param commarea      the outbound commarea (PgmContext=ENTER on
         *                      every Xctl per the COBOL idiom
         *                      {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT};
         *                      never {@code null})
         */
        record Xctl(String targetProgram, CardDemoCommarea commarea)
                implements Outcome {

            /**
             * Canonical (compact) constructor — JEP 513 flexible
             * constructor bodies. Validates the target program name
             * before the implicit field binding.
             *
             * @throws NullPointerException     if {@code targetProgram}
             *                                  or {@code commarea} is
             *                                  {@code null}
             * @throws IllegalArgumentException if {@code targetProgram}
             *                                  is blank
             */
            public Xctl {
                Objects.requireNonNull(targetProgram, "targetProgram");
                Objects.requireNonNull(commarea, "commarea");
                if (targetProgram.isBlank()) {
                    throw new IllegalArgumentException(
                            "targetProgram must not be blank");
                }
            }
        }
    }

    // =====================================================================
    // ReportSubmitter functional interface (TDQ replacement per AAP §0.4.1
    // / §0.6.8). The composition root in carddemo-app wires this to a
    // lambda that invokes CbTrn03C directly; the COBOL JCL deck build +
    // TDQ write loop is not modelled in Java because CICS TDQ is out of
    // scope.
    // =====================================================================

    /**
     * Replacement for COBOL paragraphs {@code SUBMIT-JOB-TO-INTRDR}
     * (lines 462-510) and {@code WIRTE-JOBSUB-TDQ} (lines 515-535).
     *
     * <p>In COBOL, the batch report job ({@code TRANREPT} cataloged
     * procedure) is dispatched by writing an 18-line JCL deck to the
     * CICS extra-partition Transient Data Queue named {@code JOBS},
     * one line at a time via {@code EXEC CICS WRITEQ TD QUEUE('JOBS')
     * FROM(JCL-RECORD) LENGTH(LENGTH OF JCL-RECORD)} until either the
     * sentinel record {@code /*EOF} is encountered or 1000 iterations
     * elapse. In Java, CICS TDQ is out of scope (AAP §0.4.1, §0.6.8).
     * The composition root in {@code carddemo-app} wires this functional
     * interface to a lambda that invokes
     * {@code com.blitzy.carddemo.application.transaction.CbTrn03C}
     * directly.
     *
     * <p>The {@code reportName} parameter is one of {@code "Monthly"},
     * {@code "Yearly"}, or {@code "Custom"} and is used only for the
     * success-message text emitted by {@link CoRpt00C}; the actual
     * date range that drives the report is communicated via
     * {@code startDate} and {@code endDate}.
     *
     * <p>Implementations should return {@code false} if the batch could
     * not be dispatched for any reason (corresponds to the COBOL
     * {@code "Unable to Write TDQ (JOBS)..."} error path at lines
     * 528-534). Implementations MUST NOT throw checked exceptions; any
     * runtime exception propagates through {@link CoRpt00C#execute} and
     * is the caller's responsibility to handle.
     */
    @FunctionalInterface
    public interface ReportSubmitter {

        /**
         * Dispatches the TRANREPT batch report with the given date range
         * and report name.
         *
         * @param startDate  first day of the report (inclusive; never
         *                   {@code null})
         * @param endDate    last day of the report (inclusive; never
         *                   {@code null})
         * @param reportName one of {@code "Monthly"}, {@code "Yearly"},
         *                   {@code "Custom"} (used only for the
         *                   success-message text; never {@code null})
         * @return {@code true} if the batch was successfully dispatched;
         *         {@code false} if the dispatch failed (analog of the
         *         COBOL "Unable to Write TDQ (JOBS)..." error path)
         */
        boolean submit(LocalDate startDate, LocalDate endDate, String reportName);
    }

    // =====================================================================
    // Per-invocation mutable state. Allocated fresh inside execute(...)
    // and never escapes the call stack — never shared across threads,
    // never carried across invocations.
    // =====================================================================

    /**
     * Per-call mutable scratchpad mirroring the COBOL WORKING-STORAGE
     * fields {@code WS-ERR-FLG}, {@code WS-SEND-ERASE-FLG},
     * {@code WS-MESSAGE}, {@code WS-REPORT-NAME}, plus the parsed
     * start/end dates and a focus-field hint that the BMS-rendering
     * adapter consumes to position the cursor. Never escapes the
     * enclosing call to {@link CoRpt00C#execute}.
     *
     * <p>Default values match the COBOL initialization at lines 165-170:
     * <ul>
     *   <li>{@code ERR-FLG-OFF TO TRUE} &rarr; {@code errFlgOn = false}</li>
     *   <li>{@code SEND-ERASE-YES TO TRUE} &rarr; {@code sendEraseYes = true}</li>
     *   <li>{@code MOVE SPACES TO WS-MESSAGE} &rarr; {@code message = ""}</li>
     *   <li>focus on MONTHLYL (the first user-input field on the screen)</li>
     *   <li>error message color RED (BMS-compile-time default for ERRMSG)</li>
     * </ul>
     */
    static final class State {

        /** COBOL {@code WS-ERR-FLG = 'Y'} / 88-level ERR-FLG-ON. */
        boolean errFlgOn = false;

        /**
         * COBOL {@code WS-SEND-ERASE-FLG = 'Y'} / 88-level SEND-ERASE-YES.
         * Flipped to {@code false} by the BMS-rendering adapter after the
         * first SEND-MAP-ERASE so that subsequent re-sends within the same
         * pseudo-conversation do not erase the screen.
         */
        boolean sendEraseYes = true;

        /** COBOL {@code WS-MESSAGE PIC X(80)} (line 39). */
        String message = "";

        /**
         * COBOL {@code WS-REPORT-NAME PIC X(10)} (line 58). One of
         * {@code "Monthly"}, {@code "Yearly"}, {@code "Custom"} once a
         * report type is selected; blank otherwise.
         */
        String reportName = "";

        /**
         * Synthetic component: COBOL field-length name (for example
         * {@code "MONTHLYL"}, {@code "SDTMML"}, {@code "CONFIRML"}) that
         * COBOL set to {@code -1} via {@code MOVE -1 TO XXXL}; the
         * BMS-rendering adapter uses this to position the cursor.
         * Defaults to {@code "MONTHLYL"} matching the COBOL first-entry
         * cursor positioning at line 180.
         */
        String focusField = "MONTHLYL";

        /**
         * Color of the error message in the output map. Defaults to
         * {@link CoRpt00Output.FieldColor#RED} (the BMS-compile-time
         * default for the ERRMSG field per
         * {@code app/bms/CORPT00.bms:L219}); flipped to
         * {@link CoRpt00Output.FieldColor#GREEN} on the success path
         * (COBOL line 448: {@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO}).
         */
        CoRpt00Output.FieldColor errMsgColor = CoRpt00Output.FieldColor.RED;

        /**
         * Holds the validated start date once derived. Used by
         * {@link ReportSubmitter#submit} after PROCESS-ENTER-KEY
         * succeeds.
         */
        LocalDate startDate;

        /** Holds the validated end date once derived. Same role as above. */
        LocalDate endDate;

        /**
         * Echo of the operator's MONTHLYI input (preserved across re-sends
         * via {@link CoRpt00Output}).
         */
        String echoMonthly = "";
        /** Echo of the operator's YEARLYI input. */
        String echoYearly = "";
        /** Echo of the operator's CUSTOMI input. */
        String echoCustom = "";
        /** Echo of the operator's SDTMMI input. */
        String echoStartMonth = "";
        /** Echo of the operator's SDTDDI input. */
        String echoStartDay = "";
        /** Echo of the operator's SDTYYYYI input. */
        String echoStartYear = "";
        /** Echo of the operator's EDTMMI input. */
        String echoEndMonth = "";
        /** Echo of the operator's EDTDDI input. */
        String echoEndDay = "";
        /** Echo of the operator's EDTYYYYI input. */
        String echoEndYear = "";
        /** Echo of the operator's CONFIRMI input. */
        String echoConfirmation = "";
    }

    // =====================================================================
    // Public entry method — translation of MAIN-PARA (lines 163-202 of
    // app/cbl/CORPT00C.cbl).
    // =====================================================================

    /**
     * Services one CICS pseudo-conversational round-trip for the
     * {@code CR00} CICS transaction (Print Transaction Reports).
     *
     * <p>This method is the Java translation of the COBOL
     * {@code MAIN-PARA} paragraph (lines 163-202). It mirrors the COBOL
     * control flow precisely:
     * <ol>
     *   <li>Initialize {@code ERR-FLG-OFF}, {@code TRANSACT-NOT-EOF},
     *       {@code SEND-ERASE-YES}, and {@code WS-MESSAGE = SPACES}
     *       (lines 165-170).</li>
     *   <li>If {@code EIBCALEN = 0} (commarea is {@code null}): XCTL to
     *       {@code COSGN00C} (lines 172-174 &mdash; cold start path).</li>
     *   <li>Otherwise, if the commarea's program-context is
     *       {@link PgmContext.Enter} (not {@code CDEMO-PGM-REENTER}):
     *       first-navigation path — set context to
     *       {@link PgmContext.Reenter}, blank the output (MOVE LOW-VALUES
     *       TO CORPT0AO), position cursor on MONTHLYL, and SEND-MAP
     *       (lines 177-181).</li>
     *   <li>Otherwise (re-entry): dispatch by {@code EIBAID} (lines
     *       182-196):
     *       <ul>
     *         <li>{@code DFHENTER} (Enter key) &rarr; PROCESS-ENTER-KEY
     *             (line 185-186)</li>
     *         <li>{@code DFHPF3} (PF3 key) &rarr; XCTL to COMEN01C
     *             (lines 187-189)</li>
     *         <li>Any other AID key (Clear, PA1/PA2, PF1/PF2/PF4..PF12)
     *             &rarr; invalid-key error + SEND-MAP (lines 190-194)</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>The pattern-matching {@code switch} over {@link AidKey} is
     * exhaustive with NO {@code default} branch — the Java 25 compiler
     * enforces that every one of the 16 sealed permits (Enter, Clear,
     * Pa1, Pa2, PfKey01..PfKey12) is handled. This is AAP §0.6.2's
     * idiom-for-idiom translation of the COBOL {@code EVALUATE EIBAID}.
     *
     * @param input    the {@code RECEIVE-MAP} input record (every field
     *                 already normalized to its PIC width by
     *                 {@link CoRpt00Input}'s compact constructor). May be
     *                 {@code null} for first-entry; the {@code null}
     *                 case mirrors COBOL's first-entry path where no
     *                 prior screen state has been received.
     * @param commarea the inbound DFHCOMMAREA, or {@code null} when
     *                 {@code EIBCALEN = 0} (cold start). Must not throw
     *                 when {@code null}.
     * @return the chosen {@link Outcome} — either a {@link Outcome.SendMap}
     *         to redraw the screen or an {@link Outcome.Xctl} to transfer
     *         control to another CardDemo program
     */
    public Outcome execute(CoRpt00Input input, CardDemoCommarea commarea) {
        // COBOL lines 165-170: initialize WS-ERR-FLG, WS-SEND-ERASE-FLG,
        // and WS-MESSAGE. Allocated fresh per call (never shared across
        // virtual threads).
        State state = new State();

        // COBOL lines 172-174: IF EIBCALEN = 0 → MOVE 'COSGN00C' TO
        // CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN. In the Java
        // model a null commarea corresponds to EIBCALEN = 0.
        if (commarea == null) {
            LOGGER.debug(
                    "CoRpt00C: EIBCALEN=0 (cold start) → XCTL to {}",
                    SIGNON_PROGRAM);
            // Build the cold-start commarea by starting from empty() and
            // routing to COSGN00C with PgmContext=ENTER.
            return returnToPrevScreen(CardDemoCommarea.empty(), SIGNON_PROGRAM);
        }

        // COBOL line 176: MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA.
        // No-op in Java — the commarea parameter IS the deserialized
        // buffer.

        PgmContext context = commarea.cdemoGeneralInfo().pgmContext();

        // COBOL lines 177-181: IF NOT CDEMO-PGM-REENTER → first
        // navigation. Set context to REENTER, blank output (MOVE
        // LOW-VALUES TO CORPT0AO), position cursor on MONTHLYL, and
        // SEND-MAP.
        if (context.isEnter()) {
            LOGGER.debug("CoRpt00C: first navigation → SEND-MAP with empty output");
            CardDemoCommarea reentered =
                    withPgmContext(commarea, PgmContext.REENTER);
            // First-navigation send-map: state.message="" (no error
            // text), focus on MONTHLYL (the COBOL default), and an empty
            // input record (since there is no operator input yet).
            return sendScreen(CoRpt00Input.empty(), reentered, state);
        }

        // COBOL lines 182-196: re-entry path. RECEIVE-MAP is a no-op
        // (the input parameter already carries the received data); then
        // dispatch by EIBAID. If input is null for some reason (defensive),
        // treat it as an empty input.
        CoRpt00Input safeInput = (input == null) ? CoRpt00Input.empty() : input;

        // EVALUATE EIBAID — exhaustive pattern-matching switch over all 16
        // AidKey permits. NO `default` branch (AAP §0.6.2). Every permit
        // must be enumerated: Enter, Clear, Pa1, Pa2, PfKey01..PfKey12.
        return switch (safeInput.aidKey()) {
            // COBOL line 185-186: WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY.
            case AidKey.Enter ignored ->
                    processEnterKey(safeInput, commarea, state);

            // COBOL line 187-189: WHEN DFHPF3 → MOVE 'COMEN01C' TO
            // CDEMO-TO-PROGRAM, PERFORM RETURN-TO-PREV-SCREEN.
            case AidKey.PfKey03 ignored ->
                    returnToPrevScreen(commarea, BACK_PROGRAM);

            // COBOL lines 190-194: WHEN OTHER → MOVE CCDA-MSG-INVALID-KEY
            // TO WS-MESSAGE, MOVE -1 TO MONTHLYL, PERFORM SEND-TRNRPT-SCREEN.
            // Every remaining AID key (Clear, PA1, PA2, PF1, PF2, PF4..PF12)
            // collapses to this branch. Each permit is enumerated to satisfy
            // exhaustiveness without resorting to a forbidden `default`.
            case AidKey.Clear ignored   -> invalidKey(safeInput, commarea, state);
            case AidKey.Pa1 ignored     -> invalidKey(safeInput, commarea, state);
            case AidKey.Pa2 ignored     -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey01 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey02 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey04 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey05 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey06 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey07 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey08 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey09 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey10 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey11 ignored -> invalidKey(safeInput, commarea, state);
            case AidKey.PfKey12 ignored -> invalidKey(safeInput, commarea, state);
        };
    }

    // =====================================================================
    // Invalid-key path (COBOL lines 190-194 — WHEN OTHER in EVALUATE EIBAID).
    // =====================================================================

    /**
     * Translates the COBOL {@code WHEN OTHER} branch of {@code EVALUATE
     * EIBAID} at lines 190-194:
     * <pre>
     *   MOVE 'Y'                       TO WS-ERR-FLG
     *   MOVE -1                        TO MONTHLYL OF CORPT0AI
     *   MOVE CCDA-MSG-INVALID-KEY      TO WS-MESSAGE
     *   PERFORM SEND-TRNRPT-SCREEN
     * </pre>
     * <p>{@code CCDA-MSG-INVALID-KEY} is the 50-character message from
     * {@code app/cpy/CSMSG01Y.cpy} translated to
     * {@link SystemMessages#INVALID_KEY_MSG}.
     *
     * @param input    the operator input record (echoed back to screen)
     * @param commarea the current commarea
     * @param state    per-invocation mutable state
     * @return a {@link Outcome.SendMap} to redraw the screen with the
     *         invalid-key message
     */
    private Outcome invalidKey(
            CoRpt00Input input, CardDemoCommarea commarea, State state) {
        state.errFlgOn = true;
        state.message = SystemMessages.INVALID_KEY_MSG;
        state.focusField = "MONTHLYL";
        return sendScreen(input, commarea, state);
    }

    // =====================================================================
    // PROCESS-ENTER-KEY (COBOL lines 208-456 of app/cbl/CORPT00C.cbl).
    // =====================================================================

    /**
     * Java translation of the COBOL {@code PROCESS-ENTER-KEY} paragraph
     * (lines 208-456). The outer {@code EVALUATE TRUE} dispatches on
     * whichever report-type indicator is non-blank (MONTHLYI, YEARLYI, or
     * CUSTOMI), with the fourth branch (WHEN OTHER) emitting the
     * "Select a report type to print report..." error.
     *
     * <p>For Monthly: start date = first day of current month, end date =
     * last day of current month (lines 213-238 — computed via
     * CURRENT-DATE then DATE-OF-INTEGER(INTEGER-OF-DATE(...) - 1)).
     *
     * <p>For Yearly: start date = Jan 1 of current year, end date = Dec
     * 31 of current year (lines 239-255).
     *
     * <p>For Custom: six empty checks, then six numeric/range checks,
     * then two {@link DateValidator#validate} calls (start and end
     * dates). The CEEDAYS warning message number {@code "2513"} is
     * tolerated as non-fatal (lines 396-426).
     *
     * <p>After the date range is computed, control transfers to
     * {@link #submitJobToIntrdr} which handles the confirmation
     * prompt and the actual TRANREPT dispatch. On a successful submit,
     * a green success message is emitted (lines 445-454).
     *
     * @param input    the operator input record
     * @param commarea the current commarea
     * @param state    per-invocation mutable state
     * @return the appropriate {@link Outcome} — usually
     *         {@link Outcome.SendMap}; never {@link Outcome.Xctl}
     */
    private Outcome processEnterKey(
            CoRpt00Input input, CardDemoCommarea commarea, State state) {
        // COBOL line 210: DISPLAY 'PROCESS ENTER KEY'. SLF4J replaces
        // DISPLAY (no card PAN content; safe to log at DEBUG).
        LOGGER.debug("CoRpt00C: PROCESS ENTER KEY");

        // COBOL lines 213-238: WHEN MONTHLYI OF CORPT0AI NOT = SPACES AND
        // LOW-VALUES. Compute month boundaries from CURRENT-DATE.
        if (input.isMonthlySelected()) {
            state.reportName = REPORT_NAME_MONTHLY;
            LocalDate today = LocalDate.now();
            // Start date = first day of current month (line 217-219):
            //   MOVE WS-CURDATE-YEAR  TO WS-START-DATE-YYYY
            //   MOVE WS-CURDATE-MONTH TO WS-START-DATE-MM
            //   MOVE '01'             TO WS-START-DATE-DD
            state.startDate = today.withDayOfMonth(1);
            // End date = last day of current month (lines 223-234):
            //   MOVE 1 TO WS-CURDATE-DAY
            //   ADD 1  TO WS-CURDATE-MONTH (overflow to next year if >12)
            //   COMPUTE WS-CURDATE-N = DATE-OF-INTEGER(
            //                            INTEGER-OF-DATE(WS-CURDATE-N) - 1)
            // i.e. last-day-of-current-month = first-of-next-month - 1 day.
            // LocalDate.lengthOfMonth() gives the same result.
            state.endDate = today.withDayOfMonth(today.lengthOfMonth());
            // PERFORM SUBMIT-JOB-TO-INTRDR (line 238).
            return submitJobToIntrdr(input, commarea, state);
        }

        // COBOL lines 239-255: WHEN YEARLYI OF CORPT0AI NOT = SPACES AND
        // LOW-VALUES.
        if (input.isYearlySelected()) {
            state.reportName = REPORT_NAME_YEARLY;
            int year = LocalDate.now().getYear();
            // Start date = Jan 1 of current year (lines 243-247):
            //   MOVE WS-CURDATE-YEAR TO WS-START-DATE-YYYY (and YYYY of end)
            //   MOVE '01' TO WS-START-DATE-MM, WS-START-DATE-DD
            state.startDate = LocalDate.of(year, 1, 1);
            // End date = Dec 31 of current year (lines 250-253):
            //   MOVE '12' TO WS-END-DATE-MM
            //   MOVE '31' TO WS-END-DATE-DD
            state.endDate = LocalDate.of(year, 12, 31);
            // PERFORM SUBMIT-JOB-TO-INTRDR (line 255).
            return submitJobToIntrdr(input, commarea, state);
        }

        // COBOL lines 256-436: WHEN CUSTOMI OF CORPT0AI NOT = SPACES AND
        // LOW-VALUES.
        if (input.isCustomSelected()) {
            // Validate the six date pieces. If any check fires, the
            // helper sets state.errFlgOn = true and returns a SendMap.
            Outcome validationFailure = validateCustomDates(input, commarea, state);
            if (validationFailure != null) {
                return validationFailure;
            }
            // COBOL line 433: MOVE 'Custom' TO WS-REPORT-NAME.
            state.reportName = REPORT_NAME_CUSTOM;
            // COBOL lines 434-436: IF NOT ERR-FLG-ON PERFORM
            // SUBMIT-JOB-TO-INTRDR. The validateCustomDates() helper
            // never sets errFlgOn without returning a non-null Outcome,
            // so reaching here is equivalent to NOT ERR-FLG-ON.
            return submitJobToIntrdr(input, commarea, state);
        }

        // COBOL lines 437-442: WHEN OTHER → emit the "Select a report
        // type..." error and position the cursor on MONTHLYL.
        state.message = MSG_SELECT_TYPE;
        state.errFlgOn = true;
        state.focusField = "MONTHLYL";
        return sendScreen(input, commarea, state);
    }

    // =====================================================================
    // Custom-date validation (COBOL lines 256-432 — the inner EVALUATE
    // and the six IF blocks).
    // =====================================================================

    /**
     * Translates the inner {@code EVALUATE TRUE} (lines 258-303 — six
     * empty checks) followed by the six numeric/range {@code IF} blocks
     * (lines 329-379) and the two {@code CSUTLDTC} calls (lines 388-426)
     * for the CUSTOM date range.
     *
     * <p>The fourteen checks fire in exactly the COBOL order; the first
     * failure short-circuits to {@code PERFORM SEND-TRNRPT-SCREEN} (which
     * never returns in COBOL — the SEND paragraph ends with
     * {@code GO TO RETURN-TO-CICS}). On success, the start and end
     * dates are parsed into {@link LocalDate} and stored on the state.
     *
     * @param input    the operator input record (with CUSTOMI selected)
     * @param commarea the current commarea
     * @param state    per-invocation mutable state
     * @return {@code null} when validation succeeds (proceed to
     *         SUBMIT-JOB-TO-INTRDR) or a non-null {@link Outcome.SendMap}
     *         carrying the appropriate error message
     */
    private Outcome validateCustomDates(
            CoRpt00Input input, CardDemoCommarea commarea, State state) {
        // ---- Six empty-field checks (lines 258-303). Each WHEN sets
        //      WS-ERR-FLG = 'Y', WS-MESSAGE, MOVE -1 TO <field>L, and
        //      PERFORMs SEND-TRNRPT-SCREEN.
        if (isBlankOrLow(input.startMonth())) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_MM_EMPTY, "SDTMML");
        }
        if (isBlankOrLow(input.startDay())) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_DD_EMPTY, "SDTDDL");
        }
        if (isBlankOrLow(input.startYear())) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_YYYY_EMPTY, "SDTYYYYL");
        }
        if (isBlankOrLow(input.endMonth())) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_MM_EMPTY, "EDTMML");
        }
        if (isBlankOrLow(input.endDay())) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_DD_EMPTY, "EDTDDL");
        }
        if (isBlankOrLow(input.endYear())) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_YYYY_EMPTY, "EDTYYYYL");
        }

        // ---- Six NUMVAL-C + range checks (lines 305-379). COBOL uses
        //      FUNCTION NUMVAL-C to coerce SPACES → 0 and to strip
        //      leading spaces; we replicate this with tryParseInt(...).
        //      Each IF sets WS-ERR-FLG = 'Y', WS-MESSAGE, MOVE -1 TO
        //      <field>L, and PERFORMs SEND-TRNRPT-SCREEN.

        // Start Date - Month: lines 305-307 (NUMVAL-C), 329-336 (IS NOT
        // NUMERIC OR > '12').
        OptionalInt sMonth = tryParseInt(input.startMonth());
        if (sMonth.isEmpty() || sMonth.getAsInt() < 0 || sMonth.getAsInt() > 12) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_MM_INVALID, "SDTMML");
        }
        // Start Date - Day: lines 309-311, 338-345.
        OptionalInt sDay = tryParseInt(input.startDay());
        if (sDay.isEmpty() || sDay.getAsInt() < 0 || sDay.getAsInt() > 31) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_DD_INVALID, "SDTDDL");
        }
        // Start Date - Year: lines 313-315, 347-353 (IS NOT NUMERIC; no
        // range check beyond the format).
        OptionalInt sYear = tryParseInt(input.startYear());
        if (sYear.isEmpty()) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_YYYY_INVALID, "SDTYYYYL");
        }
        // End Date - Month: lines 317-319, 355-362.
        OptionalInt eMonth = tryParseInt(input.endMonth());
        if (eMonth.isEmpty() || eMonth.getAsInt() < 0 || eMonth.getAsInt() > 12) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_MM_INVALID, "EDTMML");
        }
        // End Date - Day: lines 321-323, 364-371.
        OptionalInt eDay = tryParseInt(input.endDay());
        if (eDay.isEmpty() || eDay.getAsInt() < 0 || eDay.getAsInt() > 31) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_DD_INVALID, "EDTDDL");
        }
        // End Date - Year: lines 325-327, 373-379.
        OptionalInt eYear = tryParseInt(input.endYear());
        if (eYear.isEmpty()) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_YYYY_INVALID, "EDTYYYYL");
        }

        // ---- Two CSUTLDTC composite-date calls (lines 381-426).
        //      COBOL builds WS-START-DATE as YYYY-MM-DD (lines 381-386)
        //      then CALLs 'CSUTLDTC' with the date and the format mask.
        //      The Java translation calls DateValidator.validate(...)
        //      statically (DateValidator is a utility class).
        //      The CEEDAYS warning msg-no "2513" is tolerated.

        // COBOL line 381-386: MOVE SDTYYYYI → WS-START-DATE-YYYY, etc.
        // Translation: format pieces as %04d-%02d-%02d (zero-padded).
        String startDateStr = formatIsoDate(
                sYear.getAsInt(), sMonth.getAsInt(), sDay.getAsInt());
        // COBOL lines 388-406: CALL 'CSUTLDTC' USING WS-START-DATE,
        // WS-DATE-FORMAT, CSUTLDTC-RESULT. IF SEV-CD = '0000' CONTINUE
        // ELSE IF MSG-NUM NOT = '2513' → emit "Start Date - Not a valid
        // date..." error.
        var startResult = DateValidator.validate(startDateStr, DATE_FORMAT_PATTERN);
        if (!startResult.isSuccess()
                && !MSG_NUM_CEEDAYS_WARNING.equals(startResult.msgNo().strip())) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_INVALID, "SDTMML");
        }

        // COBOL line 408-414: same for WS-END-DATE.
        String endDateStr = formatIsoDate(
                eYear.getAsInt(), eMonth.getAsInt(), eDay.getAsInt());
        // COBOL lines 416-426: IF SEV-CD = '0000' CONTINUE ELSE IF MSG-NUM
        // NOT = '2513' → emit "End Date - Not a valid date..." error.
        var endResult = DateValidator.validate(endDateStr, DATE_FORMAT_PATTERN);
        if (!endResult.isSuccess()
                && !MSG_NUM_CEEDAYS_WARNING.equals(endResult.msgNo().strip())) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_INVALID, "EDTMML");
        }

        // Both dates validated. Parse them as LocalDate for the
        // ReportSubmitter call. If LocalDate.parse() fails (which should
        // be impossible given CSUTLDTC succeeded), surface as the
        // appropriate "Not a valid date..." error.
        try {
            state.startDate = LocalDate.parse(startDateStr);
        } catch (DateTimeParseException dtpe) {
            return failCustomValidation(input, commarea, state,
                    MSG_SDT_INVALID, "SDTMML");
        }
        try {
            state.endDate = LocalDate.parse(endDateStr);
        } catch (DateTimeParseException dtpe) {
            return failCustomValidation(input, commarea, state,
                    MSG_EDT_INVALID, "EDTMML");
        }
        return null;
    }

    /**
     * Centralizes the COBOL idiom "MOVE 'Y' TO WS-ERR-FLG; MOVE msg TO
     * WS-MESSAGE; MOVE -1 TO &lt;field&gt;L; PERFORM SEND-TRNRPT-SCREEN".
     *
     * @param input      the operator input record
     * @param commarea   the current commarea
     * @param state      per-invocation mutable state
     * @param message    the verbatim error message to emit
     * @param focusField the COBOL field-length name (e.g. {@code "SDTMML"})
     *                   that the BMS adapter uses to position the cursor
     * @return a {@link Outcome.SendMap} carrying the error
     */
    private Outcome failCustomValidation(
            CoRpt00Input input, CardDemoCommarea commarea, State state,
            String message, String focusField) {
        state.errFlgOn = true;
        state.message = message;
        state.focusField = focusField;
        return sendScreen(input, commarea, state);
    }

    // =====================================================================
    // SUBMIT-JOB-TO-INTRDR + WIRTE-JOBSUB-TDQ (COBOL lines 462-535).
    // Collapsed into one Java method because the per-JOB-LINE TDQ write
    // loop (line 498-508) is replaced by a single ReportSubmitter.submit
    // call.
    // =====================================================================

    /**
     * Translates the COBOL paragraphs {@code SUBMIT-JOB-TO-INTRDR} (lines
     * 462-510) and {@code WIRTE-JOBSUB-TDQ} (lines 515-535) into a single
     * Java method. The CICS TDQ write loop is replaced by a single call
     * to {@link ReportSubmitter#submit} per AAP §0.4.1 / §0.6.8.
     *
     * <p>Three sequential gates:
     * <ol>
     *   <li>If {@link CoRpt00Input#confirmation()} is blank or LOW-VALUES
     *       → emit {@code "Please confirm to print the <REPORT> report..."}
     *       and focus on CONFIRML (lines 464-474).</li>
     *   <li>EVALUATE TRUE on the confirmation value (lines 477-494):
     *       <ul>
     *         <li>'Y'/'y' → proceed to dispatch.</li>
     *         <li>'N'/'n' → reset all fields and re-send screen.</li>
     *         <li>Any other value → emit
     *             {@code "\"<X>\" is not a valid value to confirm..."}
     *             and focus on CONFIRML.</li>
     *       </ul>
     *   </li>
     *   <li>Invoke {@link ReportSubmitter#submit} (TDQ replacement). If
     *       it returns {@code false}, emit
     *       {@code "Unable to Write TDQ (JOBS)..."} and focus on
     *       MONTHLYL (lines 528-534). On success, emit the green
     *       {@code "<REPORT> report submitted for printing ..."} message
     *       (lines 445-454).</li>
     * </ol>
     *
     * @param input    the operator input record
     * @param commarea the current commarea
     * @param state    per-invocation mutable state — must have
     *                 {@code state.startDate}, {@code state.endDate}, and
     *                 {@code state.reportName} populated by the caller
     * @return the appropriate {@link Outcome.SendMap}
     */
    private Outcome submitJobToIntrdr(
            CoRpt00Input input, CardDemoCommarea commarea, State state) {
        // COBOL lines 464-474: IF CONFIRMI = SPACES OR LOW-VALUES → emit
        // "Please confirm to print the <REPORT> report..." and focus on
        // CONFIRML. The STRING ... DELIMITED BY SPACE on WS-REPORT-NAME
        // means the first whitespace-delimited token of the report name
        // is substituted; since our reportName values are "Monthly",
        // "Yearly", "Custom" (no embedded whitespace), the trim is a
        // no-op.
        if (input.isConfirmBlank()) {
            state.message = "Please confirm to print the "
                    + state.reportName + " report...";
            state.errFlgOn = true;
            state.focusField = "CONFIRML";
            return sendScreen(input, commarea, state);
        }

        // COBOL lines 476-494: EVALUATE CONFIRMI.
        if (input.isConfirmYes()) {
            // WHEN 'Y' or 'y' → CONTINUE (proceed to dispatch).
            // Fall through to the ReportSubmitter call below.
        } else if (input.isConfirmNo()) {
            // COBOL lines 480-483: WHEN 'N' or 'n' → PERFORM
            // INITIALIZE-ALL-FIELDS, MOVE 'Y' TO WS-ERR-FLG, PERFORM
            // SEND-TRNRPT-SCREEN. The errFlgOn = true short-circuits the
            // success-message append at lines 445-456 of CORPT00C.cbl.
            initializeAllFields(state);
            state.errFlgOn = true;
            return sendScreen(input, commarea, state);
        } else {
            // COBOL lines 484-493: WHEN OTHER → STRING '"' DELIMITED BY
            // SIZE, CONFIRMI DELIMITED BY SPACE, '" is not a valid value
            // to confirm...' DELIMITED BY SIZE INTO WS-MESSAGE. The
            // DELIMITED BY SPACE truncates CONFIRMI at the first space;
            // since CONFIRMI is PIC X(1) (1 char), this is effectively a
            // no-op except for whitespace handling.
            state.message = "\"" + firstSpaceDelimitedToken(input.confirmation())
                    + "\" is not a valid value to confirm...";
            state.errFlgOn = true;
            state.focusField = "CONFIRML";
            return sendScreen(input, commarea, state);
        }

        // COBOL lines 496-510 + 525-535: the JOB-LINES write loop is
        // replaced by a single ReportSubmitter.submit call. On dispatch
        // failure (analog of WS-RESP-CD != DFHRESP(NORMAL)), emit the
        // verbatim "Unable to Write TDQ (JOBS)..." message and focus on
        // MONTHLYL — note: COBOL focuses on MONTHLYL on TDQ failure,
        // NOT on CONFIRML.
        boolean dispatched;
        try {
            dispatched = reportSubmitter.submit(
                    state.startDate, state.endDate, state.reportName);
        } catch (RuntimeException ex) {
            // Defensive: a runtime exception inside the submitter is
            // equivalent to the COBOL "RESP not NORMAL" branch. Log at
            // WARN with the program-id context (no card PAN in this
            // program; safe to log freely).
            LOGGER.warn("CoRpt00C: ReportSubmitter.submit threw {}",
                    ex.getClass().getSimpleName(), ex);
            dispatched = false;
        }
        if (!dispatched) {
            LOGGER.warn("CoRpt00C: report dispatch failed for {}",
                    state.reportName);
            state.message = MSG_TDQ_ERROR;
            state.errFlgOn = true;
            state.focusField = "MONTHLYL";
            return sendScreen(input, commarea, state);
        }

        // COBOL lines 445-454: IF NOT ERR-FLG-ON PERFORM
        // INITIALIZE-ALL-FIELDS, MOVE DFHGREEN TO ERRMSGC, STRING
        // WS-REPORT-NAME DELIMITED BY SPACE ' report submitted for
        // printing ...' DELIMITED BY SIZE INTO WS-MESSAGE, MOVE -1 TO
        // MONTHLYL, PERFORM SEND-TRNRPT-SCREEN.
        initializeAllFields(state);
        state.errMsgColor = CoRpt00Output.FieldColor.GREEN;
        state.message = state.reportName + " report submitted for printing ...";
        state.focusField = "MONTHLYL";
        // Note: errFlgOn is intentionally left false on the success path
        // so callers can distinguish "submitted" from "error".
        return sendScreen(input, commarea, state);
    }

    // =====================================================================
    // INITIALIZE-ALL-FIELDS (COBOL lines 633-646).
    // =====================================================================

    /**
     * Translates the COBOL {@code INITIALIZE-ALL-FIELDS} paragraph (lines
     * 633-646): blanks the 10 input echoes and the message, and positions
     * the cursor on MONTHLYL. Called from the N/n confirmation path and
     * after a successful submit.
     *
     * @param state per-invocation mutable state
     */
    private static void initializeAllFields(State state) {
        state.focusField = "MONTHLYL";
        state.echoMonthly = "";
        state.echoYearly = "";
        state.echoCustom = "";
        state.echoStartMonth = "";
        state.echoStartDay = "";
        state.echoStartYear = "";
        state.echoEndMonth = "";
        state.echoEndDay = "";
        state.echoEndYear = "";
        state.echoConfirmation = "";
        state.message = "";
    }

    // =====================================================================
    // SEND-TRNRPT-SCREEN + POPULATE-HEADER-INFO (COBOL lines 556-580 and
    // 609-628).
    // =====================================================================

    /**
     * Translates the COBOL {@code SEND-TRNRPT-SCREEN} paragraph (lines
     * 556-580) together with the {@code POPULATE-HEADER-INFO} paragraph
     * (lines 609-628) that it invokes first.
     *
     * <p>Header fields populated:
     * <ul>
     *   <li>TITLE01O ← CCDA-TITLE01 ({@link ScreenTitle#TITLE_01})</li>
     *   <li>TITLE02O ← CCDA-TITLE02 ({@link ScreenTitle#TITLE_02})</li>
     *   <li>TRNNAMEO ← WS-TRANID ({@link #TRANID})</li>
     *   <li>PGMNAMEO ← WS-PGMNAME ({@link #PGMNAME})</li>
     *   <li>CURDATEO ← WS-CURDATE-MM-DD-YY (today formatted as
     *       {@code MM/dd/yy})</li>
     *   <li>CURTIMEO ← WS-CURTIME-HH-MM-SS (now formatted as
     *       {@code HH:mm:ss})</li>
     * </ul>
     *
     * <p>Operator-input echoes preserve the typed values so the screen
     * looks "sticky" to the operator after a SEND-MAP. The error message
     * and color are taken from the state.
     *
     * <p>The outbound commarea has {@code pgmContext = REENTER} (so the
     * next dispatch lands on the re-entry branch of MAIN-PARA) and
     * {@code fromTranId / fromProgram} set to the current program's
     * identifiers (for downstream programs that inspect those fields).
     *
     * @param input    the operator input record (may be empty for the
     *                 first-navigation send)
     * @param commarea the current commarea
     * @param state    per-invocation mutable state
     * @return a {@link Outcome.SendMap} carrying the populated output,
     *         the updated commarea, and the erase flag
     */
    private Outcome sendScreen(
            CoRpt00Input input, CardDemoCommarea commarea, State state) {
        // POPULATE-HEADER-INFO (lines 609-628). Use LocalDateTime.now()
        // once to derive both date and time portions.
        LocalDateTime now = LocalDateTime.now();
        String currentDate = now.toLocalDate().format(DATE_MM_DD_YY);
        String currentTime = now.toLocalTime().format(TIME_HH_MM_SS);

        // Build the output. The Builder seeds focusField="MONTHLYL" and
        // errMsgColor=RED by default; we override with the state values.
        CoRpt00Output output = CoRpt00Output.builder()
                .transactionName(TRANID)
                .title01(ScreenTitle.TITLE_01)
                .title02(ScreenTitle.TITLE_02)
                .currentDate(currentDate)
                .currentTime(currentTime)
                .programName(PGMNAME)
                // Echo operator input back to screen — preserve typing
                // across SEND-MAP. The input parameter is never null
                // here (execute() guards it).
                .monthly(input.monthly())
                .yearly(input.yearly())
                .custom(input.custom())
                .startMonth(input.startMonth())
                .startDay(input.startDay())
                .startYear(input.startYear())
                .endMonth(input.endMonth())
                .endDay(input.endDay())
                .endYear(input.endYear())
                .confirmation(input.confirmation())
                // Error / success message and its color.
                .errMsg(state.message)
                .errMsgColor(state.errMsgColor)
                // Cursor-positioning hint (synthetic, replaces COBOL
                // MOVE -1 TO XXXL).
                .focusField(state.focusField)
                .build();

        // COBOL line 562: IF SEND-ERASE-YES erase, else don't. Capture
        // and flip the flag so subsequent SENDs in the same
        // pseudo-conversation don't erase.
        boolean erase = state.sendEraseYes;
        state.sendEraseYes = false;

        // Mark the outbound commarea as REENTER so the next dispatch
        // lands on the re-entry branch. Also stamp from-program /
        // from-tran-id so downstream programs know who sent them here.
        CardDemoCommarea outCommarea = stampFromAndContext(
                commarea, PgmContext.REENTER);

        return new Outcome.SendMap(output, outCommarea, erase);
    }

    // =====================================================================
    // RETURN-TO-PREV-SCREEN (COBOL lines 540-551).
    // =====================================================================

    /**
     * Translates the COBOL {@code RETURN-TO-PREV-SCREEN} paragraph (lines
     * 540-551). If {@code CDEMO-TO-PROGRAM} is blank or LOW-VALUES,
     * defaults it to the supplied fallback target (COSGN00C for cold
     * start, COMEN01C for PF3). Sets {@code CDEMO-FROM-TRANID =
     * WS-TRANID}, {@code CDEMO-FROM-PROGRAM = WS-PGMNAME},
     * {@code CDEMO-PGM-CONTEXT = ZEROS} (i.e. {@link PgmContext#ENTER}).
     *
     * @param commarea      the current commarea
     * @param fallbackTarget the default target if CDEMO-TO-PROGRAM is
     *                       blank
     * @return a {@link Outcome.Xctl} carrying the updated commarea
     */
    private Outcome returnToPrevScreen(
            CardDemoCommarea commarea, String fallbackTarget) {
        // COBOL lines 542-544: IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES
        //                         MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
        // The PF3 path passes BACK_PROGRAM (COMEN01C) as the fallback;
        // the cold-start path passes SIGNON_PROGRAM (COSGN00C).
        String currentTo = commarea.cdemoGeneralInfo().toProgram();
        String target = (currentTo == null || currentTo.isBlank())
                ? fallbackTarget
                : currentTo.strip();
        // COBOL lines 545-547: MOVE WS-TRANID TO CDEMO-FROM-TRANID,
        //                     MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM,
        //                     MOVE ZEROS     TO CDEMO-PGM-CONTEXT.
        CardDemoCommarea outbound = buildXctlCommarea(commarea, target);
        // COBOL lines 548-551: EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
        //                          COMMAREA(CARDDEMO-COMMAREA).
        return new Outcome.Xctl(target, outbound);
    }

    // =====================================================================
    // ProgramRegistry dispatch adapter.
    // =====================================================================

    /**
     * Adapter that converts the
     * {@link ProgramRegistry.ProgramHandler#handle(CardDemoCommarea)}
     * signature ({@link CardDemoCommarea} → {@link CardDemoCommarea}) to
     * the {@link #execute(CoRpt00Input, CardDemoCommarea)} signature
     * ((input, commarea) → {@link Outcome}). Used by the constructor's
     * self-registration with the {@link ProgramRegistry}.
     *
     * <p>When invoked through the registry, no operator input is
     * available (the caller is another program performing
     * {@code EXEC CICS XCTL PROGRAM('CORPT00C')}); we therefore supply an
     * empty {@link CoRpt00Input} (first-entry semantics) and unwrap the
     * resulting {@link Outcome}'s commarea for return to the registry.
     *
     * @param commarea the inbound commarea (may be {@code null} when
     *                 EIBCALEN = 0)
     * @return the commarea from the resulting {@link Outcome} —
     *         {@link Outcome.SendMap#commarea()} for a SendMap, or
     *         {@link Outcome.Xctl#commarea()} for an Xctl
     */
    private CardDemoCommarea dispatch(CardDemoCommarea commarea) {
        Outcome outcome = execute(CoRpt00Input.empty(), commarea);
        // Pattern-matching switch over the sealed Outcome interface;
        // exhaustive over the two permits. No `default` (AAP §0.6.2).
        return switch (outcome) {
            case Outcome.SendMap sm -> sm.commarea();
            case Outcome.Xctl xc    -> xc.commarea();
        };
    }

    // =====================================================================
    // Commarea reconstruction helpers (no field-level with* methods on
    // CardDemoCommarea — must rebuild CdemoGeneralInfo with the new
    // values; AAP §0.4.2 + verified by reading the depended file).
    // =====================================================================

    /**
     * Builds an outbound commarea for an {@link Outcome.Xctl} by setting
     * the outbound routing fields per COBOL lines 545-547:
     * <pre>
     *   MOVE WS-TRANID    TO CDEMO-FROM-TRANID
     *   MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
     *   MOVE 'targetProg' TO CDEMO-TO-PROGRAM
     *   MOVE ZEROS        TO CDEMO-PGM-CONTEXT
     * </pre>
     *
     * <p>{@code toTranId}, {@code userId}, and {@code userType} are
     * preserved from the inbound commarea since the COBOL paragraph does
     * not modify them.
     *
     * @param current   the current commarea
     * @param toProgram the resolved target program name
     * @return a new {@link CardDemoCommarea} with the outbound fields
     *         set
     */
    private static CardDemoCommarea buildXctlCommarea(
            CardDemoCommarea current, String toProgram) {
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated =
                new CardDemoCommarea.CdemoGeneralInfo(
                        padExact(TRANID, CardDemoCommarea.LENGTH_FROM_TRANID),
                        padExact(PGMNAME, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                        gi.toTranId(),
                        padExact(toProgram, CardDemoCommarea.LENGTH_TO_PROGRAM),
                        gi.userId(),
                        gi.userType(),
                        PgmContext.ENTER);
        return current.withCdemoGeneralInfo(updated);
    }

    /**
     * Stamps {@code CDEMO-FROM-TRANID} and {@code CDEMO-FROM-PROGRAM}
     * with this program's identity and sets {@code CDEMO-PGM-CONTEXT}
     * to the supplied value. Used by {@link #sendScreen} to mark the
     * outbound commarea as REENTER so the next dispatch lands on the
     * re-entry branch of MAIN-PARA.
     *
     * @param current    the current commarea
     * @param newContext the new program context (typically
     *                   {@link PgmContext#REENTER} for a SEND-MAP
     *                   round-trip)
     * @return a new {@link CardDemoCommarea}
     */
    private static CardDemoCommarea stampFromAndContext(
            CardDemoCommarea current, PgmContext newContext) {
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated =
                new CardDemoCommarea.CdemoGeneralInfo(
                        padExact(TRANID, CardDemoCommarea.LENGTH_FROM_TRANID),
                        padExact(PGMNAME, CardDemoCommarea.LENGTH_FROM_PROGRAM),
                        gi.toTranId(),
                        gi.toProgram(),
                        gi.userId(),
                        gi.userType(),
                        newContext);
        return current.withCdemoGeneralInfo(updated);
    }

    /**
     * Returns a clone of the given commarea with only the
     * {@code pgmContext} field replaced. Used by the first-navigation
     * path to flip the inbound ENTER context to REENTER without
     * stamping from-program / from-tran-id (which is done later by
     * {@link #stampFromAndContext}).
     *
     * @param current    the current commarea
     * @param newContext the new program-context value
     * @return a new {@link CardDemoCommarea}
     */
    private static CardDemoCommarea withPgmContext(
            CardDemoCommarea current, PgmContext newContext) {
        CardDemoCommarea.CdemoGeneralInfo gi = current.cdemoGeneralInfo();
        CardDemoCommarea.CdemoGeneralInfo updated =
                new CardDemoCommarea.CdemoGeneralInfo(
                        gi.fromTranId(),
                        gi.fromProgram(),
                        gi.toTranId(),
                        gi.toProgram(),
                        gi.userId(),
                        gi.userType(),
                        newContext);
        return current.withCdemoGeneralInfo(updated);
    }

    // =====================================================================
    // Small utility helpers.
    // =====================================================================

    /**
     * Pads or truncates a string to an exact length. Length must be
     * positive; {@code null} input is treated as the empty string.
     * Required by {@link CardDemoCommarea.CdemoGeneralInfo}'s compact
     * constructor which enforces exact PIC X(N) widths.
     *
     * @param s      the string to pad/truncate (may be {@code null})
     * @param length the desired exact length (must be {@code > 0})
     * @return a string of exactly {@code length} characters
     * @throws IllegalArgumentException if {@code length <= 0}
     */
    private static String padExact(String s, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException(
                    "length must be positive: " + length);
        }
        String value = (s == null) ? "" : s;
        if (value.length() == length) {
            return value;
        }
        if (value.length() > length) {
            return value.substring(0, length);
        }
        return value + " ".repeat(length - value.length());
    }

    /**
     * Returns {@code true} if {@code s} is {@code null}, empty, or
     * consists entirely of ASCII SPACE ({@code 0x20}) or NULL / LOW-VALUE
     * ({@code '\u0000'}) characters. Matches the COBOL idiom
     * {@code field = SPACES OR LOW-VALUES} for any PIC X(n) field.
     *
     * @param s the string to inspect (may be {@code null})
     * @return {@code true} if the string is blank in the COBOL sense;
     *         {@code false} otherwise
     */
    private static boolean isBlankOrLow(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\u0000') {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the first whitespace-delimited token of {@code s}, or the
     * empty string if {@code s} is {@code null}/empty. Translates the
     * COBOL {@code STRING ... DELIMITED BY SPACE} clause.
     *
     * @param s the string to tokenize (may be {@code null})
     * @return the first whitespace-delimited token, or {@code ""}
     */
    private static String firstSpaceDelimitedToken(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        // Find the first space or low-value character.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\u0000') {
                return s.substring(0, i);
            }
        }
        return s;
    }

    /**
     * Parses a (possibly space-padded or empty) string as an
     * {@link OptionalInt}. Returns {@link OptionalInt#empty()} if the
     * input is {@code null}, empty, or non-numeric. Translates the COBOL
     * idiom {@code COMPUTE WS-NUM = FUNCTION NUMVAL-C(field)} followed
     * by an {@code IS NOT NUMERIC} test.
     *
     * <p>The COBOL NUMVAL-C function strips leading spaces and treats
     * an all-spaces input as zero. The COBOL {@code IS NOT NUMERIC} test
     * fires when the field has any non-digit character. Our combined
     * behavior: trim leading/trailing whitespace, then
     * {@link Integer#parseInt}; any parse error yields {@code empty()}.
     *
     * @param s the string to parse (may be {@code null})
     * @return the parsed integer, or {@link OptionalInt#empty()} on any
     *         error
     */
    private static OptionalInt tryParseInt(String s) {
        if (s == null) {
            return OptionalInt.empty();
        }
        String trimmed = s.strip();
        if (trimmed.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(trimmed));
        } catch (NumberFormatException nfe) {
            return OptionalInt.empty();
        }
    }

    /**
     * Formats a year/month/day triple as an ISO {@code YYYY-MM-DD} date
     * string with zero-padding. Translates the COBOL idiom:
     * <pre>
     *   MOVE YYYY TO WS-START-DATE-YYYY  (the FILLER '-' is built-in)
     *   MOVE MM   TO WS-START-DATE-MM
     *   MOVE DD   TO WS-START-DATE-DD
     * </pre>
     *
     * @param year  the year (any non-negative int; COBOL caller has
     *              already range-checked)
     * @param month the month (1-12; COBOL caller has already
     *              range-checked)
     * @param day   the day (1-31; COBOL caller has already range-checked)
     * @return the {@code YYYY-MM-DD} string (always 10 characters)
     */
    private static String formatIsoDate(int year, int month, int day) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
    }
}
