/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.golden;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Byte-for-byte golden-record parity test for {@code CORPT00C}
 * (Reports Online &mdash; Transaction Reports Submission, CICS
 * transaction&nbsp;{@code CR00}).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/CORPT00C.cbl} &mdash;
 * the {@code PROGRAM-ID CORPT00C} online-CICS program backing
 * transaction&nbsp;{@code CR00} ({@code WS-TRANID PIC X(04) VALUE 'CR00'}
 * at {@code app/cbl/CORPT00C.cbl:L38}). CORPT00C is an
 * <strong>online-to-batch bridge</strong>: a one-shot submission form
 * that gathers a report-mode radio selector (Monthly / Yearly / Custom),
 * an optional manual date range, and a Y/N confirmation; constructs an
 * 18-line JCL deck with substituted date values
 * ({@code JOB-DATA / JOB-LINES} structure at
 * {@code app/cbl/CORPT00C.cbl:L81-L127}); and writes the deck to the
 * CICS extra-partition Transient Data Queue named&nbsp;{@code JOBS} via
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} at
 * {@code app/cbl/CORPT00C.cbl:L517-L523}, where the z/OS JES initiator
 * subsequently picks up the request and starts the TRANREPT batch job
 * cataloged at {@code app/jcl/TRANREPT.jcl}.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.report.CoRpt00C}. Per AAP
 * &sect;0.3.1 / &sect;0.4.1 CORPT00C is translated into the
 * {@code application/report/} subpackage. The Java translation preserves
 * a 3-argument constructor
 * {@code CoRpt00C(ProgramRegistry, DateValidator, ReportSubmitter)}
 * matching the COBOL collaborator surface (dynamic-CALL routing,
 * CSUTLDTC date validation, and the TDQ replacement port).</p>
 *
 * <h2>IMPLEMENTATION DECISION &mdash; CICS TDQ &rarr; Direct Invocation</h2>
 *
 * <p>Per AAP &sect;0.4.1 ("online-to-batch bridge via CICS TDQ JOBS
 * queue; translate to direct invocation since CICS TDQ is out of scope")
 * and AAP &sect;0.2.2 (CICS configuration and mainframe replacement
 * orchestration are explicitly excluded from the refactor), the
 * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} dispatch at
 * {@code app/cbl/CORPT00C.cbl:L517-L523} is replaced with direct
 * invocation of the {@code ReportSubmitter} functional interface
 * injected into the {@link com.blitzy.carddemo.application.report.CoRpt00C}
 * constructor. The composition root in {@code carddemo-app} wires
 * {@code ReportSubmitter} to a lambda that invokes
 * {@code com.blitzy.carddemo.application.transaction.CbTrn03C} directly,
 * preserving end-to-end observable behavior. The verbatim COBOL error
 * message {@code "Unable to Write TDQ (JOBS)..."} at
 * {@code app/cbl/CORPT00C.cbl:L531} is preserved byte-for-byte on the
 * dispatch-failure path per AAP &sect;0.7.1 (preserve-as-is mandate).
 * This deviation is documented in {@code java/MIGRATION_NOTES.md} as
 * the canonical IMPLEMENTATION DECISION for this program.</p>
 *
 * <p>The COBOL JCL template construction (lines 81-127 of
 * {@code CORPT00C.cbl}, the {@code JOB-DATA / JOB-LINES} structure
 * iterated by the {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL
 * WS-IDX > 1000} loop at lines 499-510) is dropped because the JVM
 * executes the batch use case directly rather than building a JCL deck
 * and writing it line-by-line to TDQ. The captured {@code stdout.txt}
 * fixture documents this deviation: the per-JOB-LINE TDQ-write trace is
 * replaced by a single equivalent direct-invocation log line.</p>
 *
 * <h2>COBOL Paragraph &rarr; Java Method Mapping</h2>
 * <ul>
 *   <li>{@code MAIN-PARA} ({@code app/cbl/CORPT00C.cbl:L163-L202})
 *       &rarr; {@code execute(CoRpt00Input, CardDemoCommarea)}</li>
 *   <li>{@code PROCESS-ENTER-KEY} ({@code L208-L456}) &rarr;
 *       {@code processEnterKey(...)}</li>
 *   <li>{@code SUBMIT-JOB-TO-INTRDR} ({@code L462-L510}) +
 *       {@code WIRTE-JOBSUB-TDQ} ({@code L515-L535}) &rarr;
 *       {@code submitJobToIntrdr(...)} (the two paragraphs collapse
 *       into one because the per-JOB-LINE TDQ write loop is replaced
 *       by a single {@code ReportSubmitter.submit(...)} call)</li>
 *   <li>{@code RETURN-TO-PREV-SCREEN} ({@code L540-L551}) &rarr;
 *       {@code returnToPrevScreen(...)}</li>
 *   <li>{@code SEND-TRNRPT-SCREEN} ({@code L556-L580}) +
 *       {@code POPULATE-HEADER-INFO} ({@code L609-L628}) &rarr;
 *       {@code sendScreen(...)}</li>
 *   <li>{@code RECEIVE-TRNRPT-SCREEN} ({@code L596}) &rarr;
 *       {@code receiveScreen(...)}</li>
 *   <li>{@code INITIALIZE-ALL-FIELDS} ({@code L633-L646}) &rarr;
 *       {@code initializeAllFields(...)}</li>
 * </ul>
 *
 * <h2>Three Report Modes</h2>
 *
 * <p>The {@code CORPT0A} BMS map at {@code app/bms/CORPT00.bms} exposes
 * three mutually exclusive radio-style options ({@code MONTHLY},
 * {@code YEARLY}, {@code CUSTOM} at BMS lines 80-121) plus a manual
 * date-range pair ({@code SDTMM} / {@code SDTDD} / {@code SDTYYYY} and
 * {@code EDTMM} / {@code EDTDD} / {@code EDTYYYY} at BMS lines 127-194)
 * and a Y/N confirmation ({@code CONFIRM} at BMS lines 206-217). The
 * {@link com.blitzy.carddemo.application.report.CoRpt00C} processing
 * dispatch in paragraph {@code PROCESS-ENTER-KEY} branches per the
 * selected mode:</p>
 * <ol>
 *   <li><strong>Monthly</strong> ({@code MONTHLYI = 'Y'}) &mdash; the
 *       date range is fixed to the current calendar month (first-of-
 *       month through last-of-month); the verbatim
 *       {@code WS-REPORT-NAME VALUE 'Monthly'} is moved to the report
 *       name at {@code app/cbl/CORPT00C.cbl:L214}.</li>
 *   <li><strong>Yearly</strong> ({@code YEARLYI = 'Y'}) &mdash; the
 *       date range is fixed to the current calendar year
 *       ({@code 01-01-YYYY} through {@code 12-31-YYYY});
 *       {@code WS-REPORT-NAME VALUE 'Yearly'} at {@code L240}.</li>
 *   <li><strong>Custom</strong> ({@code CUSTOMI = 'Y'}) &mdash;
 *       user-supplied start/end dates collected from
 *       {@code SDTMM / SDTDD / SDTYYYY} and
 *       {@code EDTMM / EDTDD / EDTYYYY}; validated by CSUTLDTC
 *       ({@link com.blitzy.carddemo.application.util.DateValidator})
 *       before the TDQ dispatch path executes;
 *       {@code WS-REPORT-NAME VALUE 'Custom'} at {@code L433}.</li>
 * </ol>
 *
 * <p>Empty radio selectors trigger the verbatim
 * {@code "Select a report type to print report..."} message at
 * {@code app/cbl/CORPT00C.cbl:L438}. Empty date fields trigger the
 * twelve verbatim "{@code Start Date - Month can NOT be empty...}"
 * style messages preserved at {@code app/cbl/CORPT00C.cbl:L261-L304}.
 * Invalid date components trigger the {@code "Not a valid ..."} messages
 * at {@code L331-L425}. All messages are preserved byte-for-byte per
 * AAP &sect;0.7.1.</p>
 *
 * <h2>PF3 Back-Navigation</h2>
 *
 * <p>When the operator presses PF3 (the BMS footer at
 * {@code app/bms/CORPT00.bms:L226} declares
 * {@code 'ENTER=Continue  F3=Back'}), {@code MAIN-PARA} at
 * {@code app/cbl/CORPT00C.cbl:L186-L189} routes to
 * {@code RETURN-TO-PREV-SCREEN} with
 * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}. The Java translation
 * emits an {@code Outcome.Xctl} with
 * {@code targetProgram = "COMEN01C"} (ProgramRegistry constant
 * {@code CO_MEN_01C}). On EIBCALEN=0 (cold-start invocation, no
 * commarea) the program routes to {@code COSGN00C} per
 * {@code app/cbl/CORPT00C.cbl:L172-L174}.</p>
 *
 * <h2>Confirmation Prompt and Y/N Loop</h2>
 *
 * <p>The COBOL paragraph {@code SUBMIT-JOB-TO-INTRDR} at
 * {@code app/cbl/CORPT00C.cbl:L462-L510} stages a Y/N confirmation
 * before dispatch. Empty / low-values {@code CONFIRMI} re-renders the
 * screen with {@code "Please confirm to print the &lt;Monthly|Yearly
 * |Custom&gt; report..."} (built by the COBOL {@code STRING ...
 * DELIMITED BY SIZE / SPACE} construct preserved verbatim). Lowercase
 * {@code 'y'} / {@code 'n'} are accepted equivalently to uppercase per
 * the {@code EVALUATE TRUE WHEN CONFIRMI OF CORPT0AI = 'Y' OR 'y'}
 * pattern at lines 478-489. {@code 'N'} / {@code 'n'} clears all
 * fields via {@code INITIALIZE-ALL-FIELDS} ({@code L633-L646}) and
 * re-renders. Any other value triggers the
 * {@code '"<v>" is not a valid value to confirm...'} message at
 * {@code L487-L492}.</p>
 *
 * <h2>Date Validation Before Dispatch</h2>
 *
 * <p>The custom date-range path performs two
 * {@link com.blitzy.carddemo.application.util.DateValidator#validate(String,
 * String) DateValidator.validate(...)} calls (one for start date, one
 * for end date) using the {@code WS-DATE-FORMAT PIC X(10) VALUE
 * 'YYYY-MM-DD'} pattern at {@code app/cbl/CORPT00C.cbl:L72}. Any
 * validation failure prevents the TDQ-equivalent direct invocation
 * from firing; the screen is re-rendered with the specific error
 * message (start vs. end date; year vs. month vs. day component). The
 * CEEDAYS LE warning code {@code "2513"} (FC-UNSUPP-RANGE) is treated
 * as non-fatal per the CSUTLDTC contract documented at
 * {@code app/cbl/CSUTLDTC.cbl}, matching COBOL behavior at
 * {@code CORPT00C.cbl:L399-L405} and {@code L419-L425}.</p>
 *
 * <h2>Test Scenario</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/corpt00c/expected/} encodes a
 * multi-submission sequence covering the full state space of the
 * online-to-batch bridge:</p>
 * <ol>
 *   <li><strong>First-time entry</strong> ({@code EIBCALEN = 0}) &mdash;
 *       cold start; XCTL to {@code COSGN00C}.</li>
 *   <li><strong>Initial display</strong> (EIBCALEN&gt;0, not REENTER)
 *       &mdash; renders the empty CORPT0A map with the
 *       {@code SEND-ERASE-YES} erase flag.</li>
 *   <li><strong>Monthly selection</strong> &mdash; user types
 *       {@code MONTHLYI='Y'} and {@code CONFIRMI='Y'}; the path through
 *       {@code SUBMIT-JOB-TO-INTRDR} fires
 *       {@code ReportSubmitter.submit(...)}; the captured
 *       {@code stdout.txt} records the equivalent direct-invocation
 *       log message.</li>
 *   <li><strong>Yearly selection</strong> &mdash;
 *       {@code YEARLYI='Y'} and confirm; same dispatch path with
 *       {@code WS-REPORT-NAME = 'Yearly'}.</li>
 *   <li><strong>Custom selection (valid dates)</strong> &mdash;
 *       {@code CUSTOMI='Y'}, all six date components filled with a
 *       valid range; both DateValidator calls succeed; dispatch
 *       fires.</li>
 *   <li><strong>Custom selection (invalid month component)</strong>
 *       &mdash; verifies the {@code "Start Date - Not a valid Month..."}
 *       message at {@code L331}.</li>
 *   <li><strong>Custom selection (invalid date logical)</strong>
 *       &mdash; verifies the {@code "Start Date - Not a valid date..."}
 *       message at {@code L400} (CSUTLDTC returns non-zero
 *       severity).</li>
 *   <li><strong>Empty radio selectors</strong> &mdash; no
 *       {@code MONTHLYI/YEARLYI/CUSTOMI} = {@code 'Y'}; verifies
 *       {@code "Select a report type to print report..."} at
 *       {@code L438}.</li>
 *   <li><strong>Empty confirm</strong> &mdash; valid radio and
 *       dates but empty {@code CONFIRMI}; verifies the
 *       {@code "Please confirm to print the &lt;Mode&gt; report..."}
 *       STRING-construction at {@code L465-L474}.</li>
 *   <li><strong>Invalid confirm value</strong> &mdash; verifies the
 *       {@code '"<v>" is not a valid value to confirm...'} STRING at
 *       {@code L487-L492}.</li>
 *   <li><strong>Confirm = N</strong> &mdash; verifies that
 *       {@code INITIALIZE-ALL-FIELDS} runs and re-renders with cleared
 *       fields.</li>
 *   <li><strong>PF3 back</strong> &mdash; PF3 routes to
 *       {@code COMEN01C} via {@code Outcome.Xctl}.</li>
 *   <li><strong>Non-handled AID</strong> &mdash; the
 *       {@code EVALUATE EIBAID WHEN OTHER} branch at
 *       {@code L194-L199} emits {@code CCDA-MSG-INVALID-KEY}.</li>
 * </ol>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the base class's
 * single-output default), this test declares TWO byte-for-byte parity
 * targets:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the SLF4J / DISPLAY trace
 *       capturing the two COBOL {@code DISPLAY} sites in
 *       {@code CORPT00C} ({@code L210: DISPLAY 'PROCESS ENTER KEY'}
 *       fires unconditionally on every ENTER scenario;
 *       {@code L529: DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD}
 *       fires only on the dispatch-failure path) PLUS the equivalent
 *       direct-invocation log line that replaces the per-JOB-LINE TDQ
 *       write trace per the AAP &sect;0.4.1 IMPLEMENTATION
 *       DECISION.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@link com.blitzy.carddemo.application.report.CoRpt00C.Outcome
 *       Outcome}/{@code SendMap} states (one per submission;
 *       expected cardinality per the captured COBOL baseline). Each
 *       state preserves the {@code CORPT0AO} buffer layout per
 *       {@code app/cpy-bms/CORPT00.CPY:L121-L224} with the COBOL field
 *       attributes (color, highlight) faithfully retained at the
 *       binary level.</li>
 * </ol>
 *
 * <p>The {@code reptfile.txt} output mentioned in the agent prompt's
 * Phase&nbsp;0 reference is NOT declared here because the actual
 * batch-report production is performed by
 * {@code com.blitzy.carddemo.application.transaction.CbTrn03C} (the
 * TRANREPT batch driver), whose byte-for-byte parity is verified by
 * the sibling {@code CbTrn03CGoldenTest} fixture suite. This test
 * scopes only to the online dispatch contract.</p>
 *
 * <h2>Forbidden Constructs (cascaded from AAP &sect;0.7.4)</h2>
 *
 * <p>None of the following appear in this file: Spring / Spring Test
 * / Mockito-Spring / Testcontainers / LocalStack;
 * {@code double} / {@code float}; {@code ThreadLocal} (use
 * {@code ScopedValue} per AAP &sect;0.6.6); {@code java.io.File} (use
 * {@code java.nio.file.Path} per AAP &sect;0.6.5);
 * {@code java.util.Date} / {@code Calendar} (use {@code java.time}
 * per AAP &sect;0.6.4); preview features (JEP&nbsp;502, JEP&nbsp;505,
 * JEP&nbsp;507, JEP&nbsp;512 in production); {@code System.out} /
 * {@code System.err} (use SLF4J in production code; the test runner
 * itself prints assertion messages via AssertJ in the base class);
 * reflection ({@code Class.forName}, {@code Method.invoke}); the
 * {@code --enable-preview} JVM flag.</p>
 *
 * <h2>This Test is the Non-Negotiable PR Gate</h2>
 *
 * <p>Per AAP &sect;0.6.11, any deviation in the CORPT00C dispatch
 * contract (Monthly/Yearly/Custom branch selection, date validation
 * before dispatch, verbatim error messages, dispatch-equivalent log
 * message, PF3 back-navigation target, EIBCALEN=0 first-entry target,
 * Y/N confirmation loop, INITIALIZE-ALL-FIELDS clear-on-N behavior)
 * breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state:</strong> per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below
 * is annotated {@code @Disabled} with a 4-point verification reason
 * citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md} and the AAP &sect;0.4.1
 * IMPLEMENTATION DECISION for CICS TDQ &rarr; direct invocation
 * translation. The harness skeleton is unconditionally present so
 * JUnit discovers and reports this per-program test in CI from day
 * one. The {@code @Disabled} annotation will be removed in the same
 * PR that commits non-placeholder content under
 * {@code src/test/resources/golden/corpt00c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.report.CoRpt00C
 * @since 25
 */
@DisplayName("CORPT00C \u2014 Reports Online Golden-Record Parity (CICS TDQ \u2192 direct invocation)")
public class CoRpt00CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID CORPT00C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "corpt00c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/corpt00c/expected/}. Encodes the
     * multi-submission sequence (first-entry, initial display, Monthly,
     * Yearly, Custom valid, Custom invalid month, Custom invalid date,
     * empty radio, empty confirm, invalid confirm, confirm=N, PF3 back,
     * non-handled AID) consumed by the harness orchestrator to drive
     * the CoRpt00C online-CICS state machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/corpt00c/expected/}. Records the
     * SLF4J/DISPLAY emissions for the scenario including the equivalent
     * direct-invocation log line that replaces the per-JOB-LINE
     * {@code EXEC CICS WRITEQ TD QUEUE('JOBS')} trace per the AAP
     * &sect;0.4.1 IMPLEMENTATION DECISION.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/corpt00c/expected/}. Records the
     * serialized
     * {@link com.blitzy.carddemo.application.report.CoRpt00C.Outcome}
     * screen states (one {@code SendMap} per re-render submission;
     * {@code Xctl} submissions emit no SEND frame). Per AAP &sect;0.7.2
     * CORPT00C is N/A for PAN-masking concerns (no card data on screen).
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.report.CoRpt00C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests} declares
     * a test-scope dependency on {@code carddemo-application} in
     * {@code java/carddemo-tests/pom.xml} (per AAP &sect;0.5.1).</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.report.CoRpt00C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/corpt00c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * multi-submission sequence (first-entry, initial display, Monthly,
     * Yearly, Custom valid, Custom invalid, empty radio, empty confirm,
     * invalid confirm, confirm=N, PF3 back, non-handled AID) for the
     * online-CICS pseudo-conversational test; it lives alongside the
     * expected outputs under the per-program {@code corpt00c/} subtree
     * because it is a harness-internal fixture (not part of the
     * immutable {@code app/data/ASCII/} dataset). CORPT00C does NOT
     * read any of the 9 ASCII fixtures &mdash; the
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} declaration
     * at {@code app/cbl/CORPT00C.cbl:L40} and the
     * {@code COPY CVTRA05Y} at {@code L146} are vestigial (no
     * {@code EXEC CICS READ/WRITE/STARTBR} ever targets TRANSACT
     * within CORPT00C, faithfully preserved per AAP &sect;0.7.1).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_SCENARIO_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL
     * stdout trace at
     * {@code src/test/resources/golden/corpt00c/expected/stdout.txt},
     * resolved via {@link GoldenRecordTest#resolveExpectedOutputPath(
     * String, String)}. This is retained for harness backward
     * compatibility (single-output convention); the actual
     * byte-for-byte parity assertions iterate the multi-element list
     * returned by {@link #expectedOutputs()} rather than this single
     * path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for CORPT00C.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently,
     * identifying any mismatched output by name in the AssertJ
     * failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J / DISPLAY trace including the equivalent
     *       direct-invocation log message that replaces the per-
     *       {@code JOB-LINES} {@code EXEC CICS WRITEQ TD
     *       QUEUE('JOBS')} trace per the AAP &sect;0.4.1
     *       IMPLEMENTATION DECISION. Also captures the two
     *       unconditional/conditional COBOL DISPLAY sites at
     *       {@code app/cbl/CORPT00C.cbl:L210} and {@code L529}.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized
     *       {@link com.blitzy.carddemo.application.report.CoRpt00C.Outcome}
     *       screen states across all re-render submissions in the
     *       scenario (every {@code SendMap} outcome; {@code Xctl}
     *       outcomes emit no SEND frame).</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object, Object)}
     * immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL CORPT00C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/corpt00c/expected/} per the
     * capture procedure documented in
     * {@code java/MIGRATION_NOTES.md} and per the AAP &sect;0.4.1
     * IMPLEMENTATION DECISION for CICS TDQ &rarr; direct invocation
     * translation. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter&nbsp;5.13.1 (pinned in {@code java/pom.xml}
     * dependencyManagement per AAP &sect;0.5.1), the JUnit Platform's
     * annotation lookup does NOT inherit {@code @Test} when a subclass
     * overrides a parent's {@code @Test}-annotated method &mdash;
     * running surefire with {@code -Dtest=CoRpt00CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CbAct01CGoldenTest},
     * {@link CbAct02CGoldenTest}, {@link CbAct03CGoldenTest},
     * {@link CbCus01CGoldenTest}, {@link CbStm03AGoldenTest},
     * {@link CbStm03BGoldenTest}, {@link CbTrn01CGoldenTest},
     * {@link CbTrn02CGoldenTest}, {@link CbTrn03CGoldenTest}, and
     * {@link DateValidatorGoldenTest}.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook, or
     *                   any {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL CORPT00C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure "
            + "and the documented IMPLEMENTATION DECISION for CICS TDQ "
            + "\u2192 direct invocation translation (AAP \u00a70.4.1). "
            + "Verify: "
            + "(1) Monthly/Yearly/Custom date-range options dispatch "
            + "correctly through the injected ReportSubmitter "
            + "functional interface (replaces EXEC CICS WRITEQ TD "
            + "QUEUE('JOBS') at app/cbl/CORPT00C.cbl:L517-L523); "
            + "WS-REPORT-NAME values 'Monthly' (L214), 'Yearly' (L240), "
            + "and 'Custom' (L433) preserved verbatim per AAP "
            + "\u00a70.7.1. "
            + "(2) Date validation via CSUTLDTC "
            + "(com.blitzy.carddemo.application.util.DateValidator) "
            + "occurs BEFORE the enqueue-equivalent direct invocation; "
            + "validation failure prevents dispatch and emits the "
            + "specific verbatim error messages from "
            + "app/cbl/CORPT00C.cbl:L261-L425 (empty / not-a-valid-"
            + "month/day/year / not-a-valid-date variants) per AAP "
            + "\u00a70.7.1. CEEDAYS warning code 2513 (FC-UNSUPP-RANGE) "
            + "treated as non-fatal per the CSUTLDTC contract. "
            + "(3) Enqueue-equivalent direct invocation log message in "
            + "stdout.txt replaces the per-JOB-LINE TDQ write trace; "
            + "the verbatim 'Unable to Write TDQ (JOBS)...' message "
            + "(L531) preserved on the dispatch-failure path; the "
            + "DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD line "
            + "(L529) preserved on the same path; the unconditional "
            + "DISPLAY 'PROCESS ENTER KEY' (L210) fires once per ENTER "
            + "submission. "
            + "(4) PF3 back-navigation produces Outcome.Xctl with "
            + "targetProgram = 'COMEN01C' per app/cbl/CORPT00C.cbl:"
            + "L186-L189; EIBCALEN=0 first-entry produces Outcome.Xctl "
            + "with targetProgram = 'COSGN00C' per L172-L174; "
            + "non-handled AID emits CCDA-MSG-INVALID-KEY per "
            + "L194-L199. Confirmation loop: Y/y dispatches; N/n "
            + "clears via INITIALIZE-ALL-FIELDS and re-renders; "
            + "empty/low-values re-renders with the STRING-built "
            + "'Please confirm to print the <Mode> report...' "
            + "message; any other value emits the '\"<v>\" is not a "
            + "valid value to confirm...' STRING per L487-L492."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
