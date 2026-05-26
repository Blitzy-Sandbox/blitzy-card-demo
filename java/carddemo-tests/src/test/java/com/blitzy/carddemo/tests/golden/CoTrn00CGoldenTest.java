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
 * Byte-for-byte golden-record parity test for {@code COTRN00C}
 * (Transaction List Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COTRN00C.cbl} &mdash; the
 * {@code PROGRAM-ID COTRN00C} ({@code app/cbl/COTRN00C.cbl:L22-L23}) online-CICS
 * program backing transaction {@code CT00} ({@code WS-TRANID PIC X(04) VALUE
 * 'CT00'} at {@code app/cbl/COTRN00C.cbl:L37}, mapset {@code COTRN00} via
 * {@code COPY COTRN00.} at {@code app/cbl/COTRN00C.cbl:L72}). The program is
 * the <strong>paged transaction-list controller</strong>: it issues a paged
 * {@code STARTBR / READNEXT / READPREV / ENDBR} traversal of the
 * {@code TRANSACT} VSAM KSDS ({@code WS-TRANSACT-FILE PIC X(08) VALUE
 * 'TRANSACT'} at {@code app/cbl/COTRN00C.cbl:L39}) optionally narrowed by a
 * {@code TRNIDIN} numeric search key, packs the next 10 hits into the BMS
 * map row slots {@code TRNID01..TRNID10}, and dispatches row-level user
 * selections to the sibling {@code COTRN01C} transaction-view program.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.transaction.CoTrn00C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COTRN00C is translated into the
 * {@code application/transaction/} subpackage co-located with its sibling
 * translations {@code CoTrn01C} (transaction view, transaction
 * {@code CT01}) and {@code CoTrn02C} (transaction add, transaction
 * {@code CT02}). The Java translation has a 2-argument constructor
 * {@code CoTrn00C(TransactionRepository transactionRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator surface
 * (one repository port for the {@code TRANSACT} KSDS plus the dynamic-CALL
 * routing facility carried as a collaborator for {@code EXEC CICS XCTL}
 * dispatch to {@code COMEN01C} and {@code COTRN01C}). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against file-backed
 * adapters wired to the auxiliary fixtures returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>10 Rows Per Page (NOT 7 like CoCrdLiC)</h2>
 *
 * <p>The most observable distinguishing characteristic of COTRN00C versus
 * the card-list sibling COCRDLIC is the per-page row count. COTRN00C packs
 * <strong>ten</strong> rows per BMS map via the
 * {@code TRNID01..TRNID10} field group declared in {@code COPY COTRN00.}
 * at {@code app/cbl/COTRN00C.cbl:L72}; the {@code ROWS_PER_PAGE = 10}
 * constant at {@code java/carddemo-application/.../CoTrn00C.java} is the
 * Java translation's reified counterpart. COCRDLIC uses seven rows per
 * page via {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 * {@code app/cbl/COCRDLIC.cbl:L177-L178}; the difference is observable in
 * the BMS output row count and in the {@code bms_output.txt} fixture
 * captured under {@code src/test/resources/golden/cotrn00c/expected/}.
 * Any divergence to a different row count breaks the byte-for-byte parity
 * contract and is a regression in the Java translation.</p>
 *
 * <h2>STARTBR / READNEXT / READPREV / ENDBR Paged-Browse Pattern</h2>
 *
 * <p>The COBOL paragraphs {@code PROCESS-PAGE-FORWARD} and
 * {@code PROCESS-PAGE-BACKWARD} realize the paged-browse pattern over
 * TRANSACT. The forward path issues {@code EXEC CICS STARTBR} keyed by the
 * current {@code TRAN-ID} cursor, then {@code READNEXT} up to ten times,
 * packing each hit into the next slot of the {@code TRNID01..TRNID10}
 * group, and finally {@code ENDBR} to release the browse cursor. The
 * backward path substitutes {@code READPREV} for {@code READNEXT}. Both
 * paths emit verbatim error messages when the cursor reaches a file
 * boundary or when an unexpected RESP code is returned by CICS &mdash;
 * see the verbatim error-message catalog below.</p>
 *
 * <h2>Row Selection XCTLs to COTRN01C</h2>
 *
 * <p>The COBOL dispatch logic routes user actions through three sealed
 * {@link com.blitzy.carddemo.application.transaction.CoTrn00C.Outcome}
 * targets. Pressing ENTER with a row selection character of {@code 'S'} or
 * {@code 's'} on row position {@code I-SELECTED} emits
 * {@code EXEC CICS XCTL PROGRAM('COTRN01C')} transferring control to the
 * transaction-view program (transaction {@code CT01}) with the chosen
 * {@code TRAN-ID} propagated through the commarea via
 * {@code CDEMO-CT00-TRN-SELECTED PIC X(16)} at
 * {@code app/cbl/COTRN00C.cbl:L70}. Pressing PF3 emits the XCTL to
 * {@code COMEN01C} (main menu, transaction {@code CM00}), per
 * {@code BACK_PROGRAM = ProgramRegistry.CO_MEN_01C}.</p>
 *
 * <h2>PF-Key Dispatch (Enter, PF03, PF07, PF08)</h2>
 *
 * <p>The AID-key validity check in the COBOL program accepts only four
 * AID values; any other AID falls through to the verbatim
 * {@code CCDA-MSG-INVALID-KEY} error message from
 * {@code app/cpy/CSMSG01Y.cpy}. The accepted AIDs are:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; process row selections; if any row
 *       has an {@code 'S'} marker dispatch via XCTL to COTRN01C;
 *       otherwise re-display the current page or reposition via the
 *       {@code TRNIDIN} numeric search key.</li>
 *   <li><strong>PF03 (Exit)</strong> &mdash; emit
 *       {@code EXEC CICS XCTL PROGRAM('COMEN01C')} to the main menu.</li>
 *   <li><strong>PF07 (Page Up)</strong> &mdash; subtract 1 from
 *       {@code CDEMO-CT00-PAGE-NUM}, seed the cursor from
 *       {@code CDEMO-CT00-TRNID-FIRST}, invoke
 *       {@code PROCESS-PAGE-BACKWARD}, and re-send the map. On the first
 *       page PF07 emits the verbatim
 *       {@code 'You are already at the top of the page...'} message and
 *       re-displays the same page.</li>
 *   <li><strong>PF08 (Page Down)</strong> &mdash; add 1 to
 *       {@code CDEMO-CT00-PAGE-NUM}, seed the cursor from
 *       {@code CDEMO-CT00-TRNID-LAST}, invoke
 *       {@code PROCESS-PAGE-FORWARD}, and re-send the map. On the last
 *       page PF08 emits the verbatim
 *       {@code 'You are already at the bottom of the page...'} message
 *       and re-displays the same page.</li>
 * </ul>
 *
 * <p>The Java translation's {@code Outcome} sealed interface (permits
 * {@code SendMap} and {@code Xctl}) replaces the COBOL CICS verbs
 * {@code EXEC CICS SEND MAP} / {@code EXEC CICS RETURN} and
 * {@code EXEC CICS XCTL} respectively; the exhaustive pattern-matching
 * switch over the sixteen {@code AidKey} permits enforces compile-time
 * completeness per AAP &sect;0.6.7 (no {@code default} branch).</p>
 *
 * <h2>Verbatim Error-Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>Per AAP &sect;0.7.1 (preserve current behavior exactly, including
 * verbatim error messages with trailing ellipsis) the following seven
 * message literals MUST be preserved byte-for-byte by the Java translation
 * and appear in the captured fixtures. Each carries the exact trailing
 * {@code ...} ellipsis from the COBOL source; ANY normalization (e.g.,
 * trimming the ellipsis, collapsing whitespace) breaks byte parity and
 * blocks the PR:</p>
 * <ul>
 *   <li>{@code 'You are at the top of the page...'} at
 *       {@code app/cbl/COTRN00C.cbl:L608} (STARTBR NOTFND during forward
 *       paging when the cursor is at the file boundary).</li>
 *   <li>{@code 'You have reached the bottom of the page...'} at
 *       {@code app/cbl/COTRN00C.cbl:L642} (READNEXT ENDFILE while
 *       packing forward into the 10-row slot group).</li>
 *   <li>{@code 'You have reached the top of the page...'} at
 *       {@code app/cbl/COTRN00C.cbl:L676} (READPREV ENDFILE during
 *       backward paging).</li>
 *   <li>{@code 'Unable to lookup transaction...'} at
 *       {@code app/cbl/COTRN00C.cbl:L615}, {@code L649}, and {@code L683}
 *       (STARTBR / READNEXT / READPREV returning an unexpected RESP code;
 *       the lowercase {@code 't'} in {@code 'transaction'} is preserved
 *       per the COBOL source).</li>
 *   <li>{@code 'You are already at the top of the page...'} at
 *       {@code app/cbl/COTRN00C.cbl:L248} (PF07 pressed when
 *       {@code CDEMO-CT00-PAGE-NUM = 1}; no-op that re-displays the
 *       same page).</li>
 *   <li>{@code 'You are already at the bottom of the page...'} at
 *       {@code app/cbl/COTRN00C.cbl:L270} (PF08 pressed when
 *       {@code NEXT-PAGE-NO} is set; no-op that re-displays the
 *       same page).</li>
 *   <li>{@code 'Tran ID must be Numeric ...'} at
 *       {@code app/cbl/COTRN00C.cbl:L214} (non-numeric content in
 *       {@code TRNIDIN}; note the embedded space before the ellipsis is
 *       preserved verbatim per the COBOL source).</li>
 * </ul>
 *
 * <h2>TRAN-ORIG-TS &rarr; MM/DD/YY Date Format</h2>
 *
 * <p>The COBOL {@code TRAN-ORIG-TS PIC X(26)} timestamp from
 * {@code app/cpy/CVTRA05Y.cpy} is rendered on the transaction list screen
 * as an 8-character {@code MM/DD/YY} value via the formatter
 * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} at
 * {@code app/cbl/COTRN00C.cbl:L57}. The Java translation uses
 * {@code DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT)} (declared
 * in {@link com.blitzy.carddemo.application.transaction.CoTrn00C}) to
 * produce byte-identical output. The {@code YY} field is the last two
 * digits of the timestamp's {@code YYYY} year (characters 0..3 of the
 * 26-byte timestamp); the {@code MM} and {@code DD} fields are
 * characters 5..6 and 8..9 of the timestamp respectively. Any deviation
 * to a different date format (e.g., {@code MM/DD/YYYY} or
 * {@code YYYY-MM-DD}) breaks parity.</p>
 *
 * <h2>TRAN-AMT &rarr; {@code "%+013.2f"} Amount Format</h2>
 *
 * <p>The COBOL {@code WS-TRAN-AMT PIC +99999999.99} edited numeric at
 * {@code app/cbl/COTRN00C.cbl:L56} produces a 12-character fixed-width
 * representation: {@code "+12345678.90"} (or {@code "-12345678.90"} for
 * negative values). The Java translation uses
 * {@code String.format(Locale.ROOT, "%+013.2f", scaled)} to produce the
 * same width and content. The {@link java.util.Locale#ROOT} argument is
 * <strong>critical</strong>: it prevents locale-specific decimal
 * separators (e.g., a comma in {@code de-DE}, a thin space in
 * {@code fr-FR}) from corrupting the byte representation. Any deviation
 * from {@code Locale.ROOT} breaks parity on developer machines configured
 * with non-US locales.</p>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cotrn00c/expected/} encodes a sequence
 * of terminal submissions exercising the full state space of the paged
 * transaction-list transaction:</p>
 * <ol>
 *   <li><strong>Initial list</strong> (first-time invocation,
 *       {@code EIBCALEN = 0}) &mdash; the paragraph initializes the
 *       commarea, sets {@code CDEMO-CT00-PAGE-NUM = 1}, and invokes
 *       {@code PROCESS-PAGE-FORWARD} from {@code LOW-VALUES} key
 *       (i.e. browse from start) to populate the first 10 rows.</li>
 *   <li><strong>PF08 forward paging to end</strong> &mdash; advances
 *       through successive pages until {@code READNEXT} returns
 *       {@code ENDFILE}, at which point the verbatim
 *       {@code 'You have reached the bottom of the page...'} message
 *       fires.</li>
 *   <li><strong>PF07 backward paging to top</strong> &mdash; retreats
 *       through successive pages until {@code READPREV} returns
 *       {@code ENDFILE}, at which point the verbatim
 *       {@code 'You have reached the top of the page...'} message
 *       fires.</li>
 *   <li><strong>Row {@code 'S'} selection</strong> &mdash; selects a
 *       specific row on the current page; the COBOL program XCTLs to
 *       {@code COTRN01C} with {@code CDEMO-CT00-TRN-SELECTED} set to the
 *       chosen {@code TRAN-ID}.</li>
 *   <li><strong>PF03 exit</strong> &mdash; XCTLs to {@code COMEN01C}
 *       (main menu) per the dispatch rule
 *       {@code BACK_PROGRAM = ProgramRegistry.CO_MEN_01C}.</li>
 * </ol>
 *
 * <p>Each submission produces a serialized {@code CoTrn00Output} screen
 * state plus zero or more SLF4J log lines; the harness concatenates all
 * screen states into {@code bms_output.txt} and all log lines into
 * {@code stdout.txt}. The two outputs are compared byte-for-byte to the
 * captured COBOL baseline.</p>
 *
 * <h2>Auxiliary Input Fixture</h2>
 *
 * <p>The single auxiliary fixture {@code app/data/ASCII/dailytran.txt}
 * backs the {@link com.blitzy.carddemo.domain.port.TransactionRepository}
 * port that COTRN00C reads via the paged
 * {@code STARTBR / READNEXT / READPREV / ENDBR} traversal. The COBOL
 * program targets the {@code TRANSACT} VSAM KSDS (a permanent
 * transaction file), but the {@code app/data/ASCII/} directory contains
 * only {@code dailytran.txt} (the daily-transaction landing file with
 * the same 350-byte CVTRA05Y/CVTRA06Y record layout); per AAP
 * &sect;0.4.1, {@code dailytran.txt} is the canonical fixture for
 * transaction-stream tests and is read directly without copying. Because
 * COTRN00C is strictly read-only (browse verbs only; no {@code REWRITE},
 * no {@code WRITE}, no {@code SYNCPOINT}), the fixture MUST be
 * byte-identical to its pre-test state after the run; the harness's
 * {@code runProgram(...)} hook copies the input into a temp directory
 * before the run so any inadvertent in-place mutation does NOT pollute
 * the immutable {@code app/data/ASCII/} fixtures.</p>
 *
 * <h2>Scaffolding state</h2>
 *
 * <p>Per AAP &sect;0.6.11 ("Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available; the harness skeleton, base class, and per-program test
 * classes are created unconditionally"), the {@link #byteForByteParity()}
 * override below is annotated {@code @Disabled} with a 7-point
 * verification reason citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is unconditionally
 * present so JUnit discovers and reports this per-program test in CI from
 * day one. The {@code @Disabled} annotation will be removed in the same
 * PR that commits non-placeholder content under
 * {@code src/test/resources/golden/cotrn00c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.transaction.CoTrn00C
 * @since 25
 */
@DisplayName("COTRN00C \u2014 Transaction List Golden-Record Parity (10 rows per page)")
public class CoTrn00CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COTRN00C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cotrn00c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cotrn00c/expected/}. Encodes the
     * five-submission pseudo-conversation sequence (initial list, PF8
     * forward to end, PF7 backward to top, row selection {@code 'S'},
     * PF3 back to COMEN01C) consumed by the harness orchestrator to
     * drive the CoTrn00C online-CICS state machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cotrn00c/expected/}. Records the
     * SLF4J/DISPLAY emissions including the seven verbatim error messages
     * from AAP &sect;0.7.1 (with trailing {@code ...} ellipsis preserved
     * exactly). The COBOL source itself issues no {@code DISPLAY} verbs
     * (the captured baseline may be empty); reserved for future Java
     * translation log surfaces.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cotrn00c/expected/}. Records the
     * serialized {@code CoTrn00Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * exactly 10 row slots ({@code TRNID01..TRNID10}) per the
     * {@code ROWS_PER_PAGE = 10} cap declared on
     * {@link com.blitzy.carddemo.application.transaction.CoTrn00C}.
     * Dates render as {@code MM/DD/YY}; amounts render as
     * {@code +99999999.99} (sign-prefixed, fixed width 12) via
     * {@code String.format(Locale.ROOT, "%+013.2f", scaled)}.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the daily-transaction fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.TransactionRepository}
     * used by the paged {@code STARTBR / READNEXT / READPREV / ENDBR}
     * traversal of the {@code TRANSACT} KSDS equivalent. The COBOL
     * program targets the permanent {@code TRANSACT} KSDS, but the
     * {@code app/data/ASCII/} directory contains only
     * {@code dailytran.txt} with the same 350-byte CVTRA05Y/CVTRA06Y
     * record layout; per AAP &sect;0.4.1, {@code dailytran.txt} is the
     * canonical fixture for transaction-stream tests and is read
     * directly via {@link GoldenRecordTest#resolveAppDataPath(String)}
     * &mdash; NOT copied into this module per AAP &sect;0.4.1 and
     * &sect;0.6.11.
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.transaction.CoTrn00C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block stays minimal and restricted to {@link java.nio.file.Path},
     * {@link java.util.List}, and the JUnit Jupiter API annotations
     * ({@link DisplayName}, {@link Disabled}, {@link Test}). The
     * fully-qualified class literal compiles cleanly because
     * {@code carddemo-tests} declares a test-scope dependency on
     * {@code carddemo-application} (transitively via {@code carddemo-app})
     * in {@code java/carddemo-tests/pom.xml} per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.transaction.CoTrn00C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cotrn00c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * five-submission CICS pseudo-conversation that drives the
     * CoTrn00C online-state machine through the full state space
     * (initial list with 10 rows; PF8 forward to end; PF7 backward to
     * top; row selection {@code 'S'} XCTLing to COTRN01C; PF3 back to
     * COMEN01C); it lives alongside the expected outputs under the
     * per-program {@code cotrn00c/} subtree because it is a
     * harness-internal fixture (not part of the immutable
     * {@code app/data/ASCII/} dataset).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_SCENARIO_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at
     * {@code src/test/resources/golden/cotrn00c/expected/stdout.txt},
     * resolved via {@link GoldenRecordTest#resolveExpectedOutputPath(
     * String, String)}. This is retained for harness backward
     * compatibility (single-output convention); the actual byte-for-byte
     * parity assertions iterate the multi-element list returned by
     * {@link #expectedOutputs()} rather than this single path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 1-element {@link List} of auxiliary input
     * fixture paths reflecting the COTRN00C collaborator surface:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/dailytran.txt} &mdash; transaction
     *       stream baseline with the 350-byte CVTRA05Y/CVTRA06Y record
     *       layout. Paged {@code STARTBR / READNEXT / READPREV / ENDBR}
     *       traversal in paragraphs {@code PROCESS-PAGE-FORWARD} and
     *       {@code PROCESS-PAGE-BACKWARD}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.TransactionRepository}
     *       constructor dependency. The COBOL program targets the
     *       permanent {@code TRANSACT} KSDS, but the
     *       {@code app/data/ASCII/} directory contains only
     *       {@code dailytran.txt} with the matching record layout, so
     *       {@code dailytran.txt} is the canonical transaction-stream
     *       fixture per AAP &sect;0.4.1.</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object)} immutable to
     * preserve deterministic ordering. Ordering matters here because
     * the harness's
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration hook
     * wires the supplied fixtures to the file-based repository adapters
     * in declaration order; reordering would change which adapter binds
     * which fixture and break the paged-browse read path.</p>
     *
     * <p>Because COTRN00C is strictly read-only (browse verbs only; no
     * {@code REWRITE}, no {@code WRITE}, no {@code SYNCPOINT}), the
     * fixture MUST be byte-identical to its pre-test state after the
     * run; the harness's runProgram(...) hook copies the input into a
     * temp directory before the run so any inadvertent in-place mutation
     * does NOT pollute the immutable {@code app/data/ASCII/}
     * fixtures.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(resolveAppDataPath(DAILYTRAN_TXT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for COTRN00C.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J/DISPLAY trace including the seven verbatim error
     *       messages per AAP &sect;0.7.1 (with trailing {@code ...}
     *       ellipsis preserved exactly). The COBOL source contains no
     *       {@code DISPLAY} verbs so the captured baseline may be
     *       empty; the Java translation may add SLF4J emissions in
     *       which case the captured form will be regenerated per
     *       {@code java/MIGRATION_NOTES.md}.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoTrn00Output} screen states (one
     *       per submission in the scenario). Each frame carries
     *       exactly 10 row slots ({@code TRNID01..TRNID10}) per the
     *       {@code ROWS_PER_PAGE = 10} constant on
     *       {@link com.blitzy.carddemo.application.transaction.CoTrn00C}.
     *       Dates render as {@code MM/DD/YY} (last two digits of
     *       year); amounts render as {@code +99999999.99} (sign-
     *       prefixed, fixed width 12) via
     *       {@code String.format(Locale.ROOT, "%+013.2f", scaled)}.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object, Object)} immutable.</p>
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
     * pending the COBOL COTRN00C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cotrn00c/expected/} per the
     * capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * dependencyManagement per AAP &sect;0.5.1), the JUnit Platform's
     * annotation lookup does NOT inherit {@code @Test} when a subclass
     * overrides a parent's {@code @Test}-annotated method &mdash;
     * running surefire with {@code -Dtest=CoTrn00CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdLiCGoldenTest}, {@link CoCrdSlCGoldenTest}, and
     * {@link CoCrdUpCGoldenTest}.</p>
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
        "Awaiting COBOL COTRN00C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 7 must hold before removing "
            + "@Disabled): "
            + "(1) 10 rows per page (NOT 7 like CoCrdLiC): every BMS "
            + "frame in bms_output.txt MUST carry exactly ten row slots "
            + "(TRNID01..TRNID10) per the ROWS_PER_PAGE = 10 constant "
            + "on com.blitzy.carddemo.application.transaction.CoTrn00C "
            + "and the TRNID01..TRNID10 field group in COPY COTRN00. at "
            + "app/cbl/COTRN00C.cbl:L72. Any deviation to a different "
            + "per-page count breaks parity and blocks the PR. "
            + "(2) STARTBR/READNEXT/READPREV/ENDBR sequencing: PF08 "
            + "forward paging invokes PROCESS-PAGE-FORWARD (STARTBR + "
            + "10-count READNEXT loop + ENDBR), seeded from "
            + "CDEMO-CT00-TRNID-LAST. PF07 backward paging invokes "
            + "PROCESS-PAGE-BACKWARD (STARTBR + 10-count READPREV loop "
            + "+ ENDBR), seeded from CDEMO-CT00-TRNID-FIRST. Browse "
            + "cursors are always released by the matching ENDBR; no "
            + "cursor leakage. "
            + "(3) PF7 backward / PF8 forward navigation: PF07 "
            + "decrements CDEMO-CT00-PAGE-NUM and repaginates "
            + "backward; PF08 increments CDEMO-CT00-PAGE-NUM and "
            + "repaginates forward. PF03 XCTLs to COMEN01C "
            + "(BACK_PROGRAM = ProgramRegistry.CO_MEN_01C). "
            + "(4) Row selection XCTLs to COTRN01C: selection char 'S' "
            + "or 's' on a non-blank row dispatches "
            + "EXEC CICS XCTL PROGRAM('COTRN01C') with "
            + "CDEMO-CT00-TRN-SELECTED PIC X(16) at "
            + "app/cbl/COTRN00C.cbl:L70 set to the chosen TRAN-ID; "
            + "VIEW_PROGRAM = ProgramRegistry.CO_TRN_01C on the Java "
            + "translation. "
            + "(5) MM/DD/YY date format (YY = last 2 of YYYY): "
            + "TRAN-ORIG-TS PIC X(26) from app/cpy/CVTRA05Y.cpy is "
            + "rendered as MM/DD/YY via "
            + "DateTimeFormatter.ofPattern(\"MM/dd/yy\", Locale.ROOT); "
            + "matches the WS-TRAN-DATE PIC X(08) VALUE '00/00/00' "
            + "formatter at app/cbl/COTRN00C.cbl:L57. Any deviation to "
            + "MM/DD/YYYY or YYYY-MM-DD breaks parity. "
            + "(6) Verbatim error-message catalog preserved per AAP "
            + "\u00a70.7.1 with trailing '...' ellipsis exact: "
            + "'You are at the top of the page...' at L608 (STARTBR "
            + "NOTFND); 'You have reached the bottom of the page...' "
            + "at L642 (READNEXT ENDFILE); 'You have reached the top "
            + "of the page...' at L676 (READPREV ENDFILE); 'Unable to "
            + "lookup transaction...' at L615/L649/L683 (unexpected "
            + "RESP code; lowercase 't' in 'transaction' preserved); "
            + "'You are already at the top of the page...' at L248 "
            + "(PF07 no-op); 'You are already at the bottom of the "
            + "page...' at L270 (PF08 no-op); 'Tran ID must be Numeric "
            + "...' at L214 (TRNIDIN non-numeric; embedded space "
            + "before ellipsis preserved). ANY normalization (e.g., "
            + "trimming ellipsis, collapsing whitespace, switching to "
            + "uppercase 'T') breaks byte parity. "
            + "(7) String.format(Locale.ROOT, \"%+013.2f\", scaled) "
            + "for amounts: WS-TRAN-AMT PIC +99999999.99 at "
            + "app/cbl/COTRN00C.cbl:L56 produces the 12-character "
            + "sign-prefixed fixed-width representation. Locale.ROOT "
            + "is REQUIRED to suppress locale-specific decimal "
            + "separators; any switch to Locale.getDefault() breaks "
            + "parity on developer machines configured with non-US "
            + "locales. "
            + "Read-only invariant: COTRN00C never modifies any "
            + "record (no REWRITE, no WRITE, no SYNCPOINT; only "
            + "STARTBR / READNEXT / READPREV / ENDBR browse verbs), "
            + "so the dailytran.txt auxiliary fixture MUST be "
            + "byte-identical to its pre-test state after the run; "
            + "any divergence indicates a regression."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
