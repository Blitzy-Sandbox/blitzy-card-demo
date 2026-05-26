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
 * Byte-for-byte golden-record parity test for {@code COADM01C}
 * (Admin Menu Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COADM01C.cbl} &mdash; the
 * {@code PROGRAM-ID COADM01C} ({@code app/cbl/COADM01C.cbl:L23}) online-CICS
 * program backing transaction {@code CA00} ({@code WS-TRANID PIC X(04) VALUE
 * 'CA00'} at {@code app/cbl/COADM01C.cbl:L37}, mapset {@code COADM01} via
 * {@code COPY COADM01.} at {@code app/cbl/COADM01C.cbl:L53}). The program
 * is the <strong>admin-only main menu</strong>: it presents the
 * administrative user-maintenance options sourced from the static menu
 * table {@code COADM02Y} ({@code app/cpy/COADM02Y.cpy} &rarr;
 * {@link com.blitzy.carddemo.domain.menu.AdminMenuTable}, 4 entries:
 * {@code COUSR00C} User List, {@code COUSR01C} User Add, {@code COUSR02C}
 * User Update, {@code COUSR03C} User Delete &mdash; per
 * {@code app/cpy/COADM02Y.cpy:L20-L42}) and dispatches the operator's
 * selection via {@code EXEC CICS XCTL} to the chosen user-maintenance
 * program. PF3 returns to the signon program ({@code COSGN00C}) and any
 * other AID key surfaces the standard invalid-key error message
 * ({@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.menu.CoAdm01C}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COADM01C is translated into the
 * {@code application/menu/} subpackage co-located with the sibling
 * translation {@link com.blitzy.carddemo.application.menu.CoMen01C}
 * (regular-user main menu, transaction {@code CM00}). The Java
 * translation has a 2-argument constructor
 * {@code CoAdm01C(ProgramRegistry programRegistry, Clock clock)} matching
 * the COBOL collaborator surface (the dynamic-CALL routing facility for
 * {@code EXEC CICS XCTL} dispatch to {@code COUSR00C/01C/02C/03C} and
 * {@code COSGN00C}, plus a {@link java.time.Clock} for deterministic
 * rendering of the {@code CURDATEO}/{@code CURTIMEO} header fields per
 * {@code POPULATE-HEADER-INFO} at
 * {@code app/cbl/COADM01C.cbl:L202-L221}). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators with no auxiliary
 * fixtures because COADM01C performs <strong>NO file I/O</strong> at
 * runtime &mdash; the program operates entirely on the static
 * {@code COADM02Y} menu table embedded in the program's working-storage
 * (the {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} declaration at
 * {@code app/cbl/COADM01C.cbl:L39} is dead code preserved verbatim per
 * AAP &sect;0.7.1).</p>
 *
 * <h2>Transaction ID Distinction: {@code CA00} (NOT {@code CM00})</h2>
 *
 * <p>COADM01C runs under CICS transaction {@code CA00}
 * ({@code WS-TRANID PIC X(04) VALUE 'CA00'} at
 * {@code app/cbl/COADM01C.cbl:L37}) and emits {@code "CA00"} into the
 * {@code TRNNAMEO} header field on every {@code SEND MAP}. The
 * structurally near-identical regular-user main menu sibling
 * {@link com.blitzy.carddemo.application.menu.CoMen01C} runs under
 * transaction {@code CM00} ({@code WS-TRANID VALUE 'CM00'} in
 * {@code app/cbl/COMEN01C.cbl}). The captured {@code bms_output.txt}
 * fixture under {@code src/test/resources/golden/coadm01c/expected/}
 * therefore carries {@code "CA00"} in the {@code TRNNAMEO} position of
 * every frame and {@code "COADM01C"} in the {@code PGMNAMEO} position;
 * any drift to {@code "CM00"} or {@code "COMEN01C"} indicates a
 * mis-wiring of the sibling pair and breaks the byte-for-byte parity
 * contract.</p>
 *
 * <h2>4-Entry Admin Menu (COADM02Y) versus 10-Entry Main Menu (COMEN02Y)</h2>
 *
 * <p>The most observable distinguishing characteristic of COADM01C versus
 * the regular-user main menu sibling COMEN01C is the menu cardinality.
 * COADM01C dispatches a <strong>4-entry</strong> admin menu populated
 * from {@code COADM02Y.cpy} ({@code CDEMO-ADMIN-OPT-COUNT PIC 9(02)
 * VALUE 4} at {@code app/cpy/COADM02Y.cpy:L20}):</p>
 * <ol>
 *   <li><strong>Option 1</strong> &mdash; {@code "User List (Security)"}
 *       &rarr; XCTL {@code COUSR00C} (transaction {@code CU00})</li>
 *   <li><strong>Option 2</strong> &mdash; {@code "User Add (Security)"}
 *       &rarr; XCTL {@code COUSR01C} (transaction {@code CU01})</li>
 *   <li><strong>Option 3</strong> &mdash; {@code "User Update (Security)"}
 *       &rarr; XCTL {@code COUSR02C} (transaction {@code CU02})</li>
 *   <li><strong>Option 4</strong> &mdash; {@code "User Delete (Security)"}
 *       &rarr; XCTL {@code COUSR03C} (transaction {@code CU03})</li>
 * </ol>
 *
 * <p>The BMS map at {@code app/bms/COADM01.bms} declares 12 option-line
 * fields {@code OPTN001..OPTN012} but the {@link CoAdm01Output} record
 * models only 10 ({@code OPTN001O..OPTN010O}) matching the
 * {@code BUILD-MENU-OPTIONS} paragraph at
 * {@code app/cbl/COADM01C.cbl:L226-L263} which has cases for
 * {@code WHEN 1} through {@code WHEN 10}. Because the admin table has
 * only 4 entries, slots 5&ndash;10 of every captured frame remain blank
 * (40 spaces each); the captured {@code bms_output.txt} encodes this
 * exact layout. The sibling COMEN01C populates up to 10 slots from its
 * 10-entry menu table, so the COMEN01C fixture would show populated
 * slots 1&ndash;10 by contrast.</p>
 *
 * <h2>Admin-Only Access Guard (AAP &sect;0.7.1)</h2>
 *
 * <p>COADM01C is restricted to users with {@code SEC-USR-TYPE='A'}
 * (Admin), but the access guard is <strong>NOT enforced inside
 * COADM01C itself</strong>. Per the agent_prompt Phase 7.1 key insight,
 * the guard is enforced upstream by {@link
 * com.blitzy.carddemo.application.signon.CoSgn00C}: the signon program
 * inspects {@code SEC-USR-TYPE} after a successful authentication, and
 * if the value is {@code 'A'} it XCTLs to {@code COADM01C}; otherwise
 * (value {@code 'U'}) it XCTLs to {@code COMEN01C} (regular-user main
 * menu). COADM01C itself never inspects the commarea's
 * {@code CDEMO-USER-TYPE} field and never re-validates the access
 * privilege at runtime &mdash; admin-only access is enforced by
 * <em>dispatch</em>, not by <em>per-option filtering</em>. The captured
 * {@code stdout.txt} carries NO admin-type warning or rejection
 * messages; the verification depends on the upstream signon routing
 * only.</p>
 *
 * <h2>Coming-Soon Message: Verbatim COBOL Quirk</h2>
 *
 * <p>The "coming soon" placeholder branch in {@code PROCESS-ENTER-KEY}
 * at {@code app/cbl/COADM01C.cbl:L147-L154} contains a deliberate
 * <strong>commented-out</strong> {@code STRING} line for the option
 * name interpolation. Lines L149-L153 read:</p>
 *
 * <pre>
 * STRING 'This option '       DELIMITED BY SIZE
 * *                CDEMO-ADMIN-OPT-NAME(WS-OPTION)
 * *                                DELIMITED BY SIZE
 *                  'is coming soon ...'   DELIMITED BY SIZE
 *      INTO WS-MESSAGE
 * </pre>
 *
 * <p>The asterisks in column 7 of L150-L151 mark those two lines as
 * COBOL comments. The resulting concatenation therefore omits the
 * option-name interpolation and emits the literal message
 * {@code "This option is coming soon ..."} (with a single space between
 * {@code "option"} and {@code "is"} from the trailing space of the
 * first literal). Per AAP &sect;0.7.1 ("if a COBOL paragraph contains
 * dead code or obvious bugs, translate it faithfully and flag it in a
 * MIGRATION_NOTES.md; do not 'fix' it in this refactor") the Java
 * translation in {@link com.blitzy.carddemo.application.menu.CoAdm01C}
 * preserves this exact message text via the
 * {@link com.blitzy.carddemo.application.menu.CoAdm01C#COMING_SOON_MSG}
 * constant. In practice this branch is never taken at runtime because
 * all four entries in {@link com.blitzy.carddemo.domain.menu.AdminMenuTable}
 * have real program names ({@code COUSR00C}, {@code COUSR01C},
 * {@code COUSR02C}, {@code COUSR03C}) and none start with the dummy
 * prefix {@code "DUMMY"} that the COBOL guard at
 * {@code app/cbl/COADM01C.cbl:L138} tests for &mdash; the
 * "coming soon" path is dead code preserved for fidelity.</p>
 *
 * <h2>Invalid Option Validation</h2>
 *
 * <p>The {@code PROCESS-ENTER-KEY} paragraph at
 * {@code app/cbl/COADM01C.cbl:L115-L155} validates the entered option
 * number with three guards (per L127-L129):</p>
 * <ol>
 *   <li><strong>Non-numeric</strong>: {@code WS-OPTION IS NOT NUMERIC}
 *       triggers when the input contains anything other than digits
 *       after the {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}
 *       zero-fill of leading spaces (L123).</li>
 *   <li><strong>Out-of-range</strong>: {@code WS-OPTION >
 *       CDEMO-ADMIN-OPT-COUNT} where {@code CDEMO-ADMIN-OPT-COUNT = 4}
 *       per {@code app/cpy/COADM02Y.cpy:L20}.</li>
 *   <li><strong>Zero</strong>: {@code WS-OPTION = ZEROS} (option 0 is
 *       not a valid selection).</li>
 * </ol>
 *
 * <p>Any of these three conditions fires the verbatim error message
 * {@code "Please enter a valid option number..."} (with trailing
 * {@code "..."} ellipsis preserved exactly) per
 * {@code app/cbl/COADM01C.cbl:L130-L132}, sets the error flag
 * ({@code WS-ERR-FLG = 'Y'}), and re-renders the screen via
 * {@code SEND-MENU-SCREEN}. Any normalization of the ellipsis,
 * collapsing of whitespace, or recasing of the message text breaks
 * byte parity and blocks the PR per AAP &sect;0.6.11.</p>
 *
 * <h2>PF3 Back to Signon</h2>
 *
 * <p>The {@code EVALUATE EIBAID} block at
 * {@code app/cbl/COADM01C.cbl:L93-L103} handles three AID-key cases:</p>
 * <ul>
 *   <li><strong>DFHENTER</strong> &rarr; invoke {@code PROCESS-ENTER-KEY}
 *       (the option-selection branch).</li>
 *   <li><strong>DFHPF3</strong> &rarr; set {@code CDEMO-TO-PROGRAM =
 *       'COSGN00C'} and invoke {@code RETURN-TO-SIGNON-SCREEN}, which
 *       emits {@code EXEC CICS XCTL PROGRAM('COSGN00C')} returning the
 *       operator to the signon screen.</li>
 *   <li><strong>WHEN OTHER</strong> &rarr; set {@code WS-ERR-FLG = 'Y'}
 *       and emit the verbatim {@code CCDA-MSG-INVALID-KEY} message
 *       ({@code "Invalid key pressed. Please see below..."} per
 *       {@code app/cpy/CSMSG01Y.cpy}); re-render the screen.</li>
 * </ul>
 *
 * <p>There is no EVALUATE {@code WHEN DFHPF12} branch in COADM01C
 * (unlike sibling user-maintenance programs which honor PF12) &mdash;
 * any AID key other than ENTER or PF3 falls through to
 * {@code CCDA-MSG-INVALID-KEY}. The Java translation enumerates all 16
 * permits of the sealed
 * {@link com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} hierarchy
 * via an exhaustive pattern-matching {@code switch} per AAP &sect;0.7.3
 * with NO {@code default} branch &mdash; any AID key other than
 * {@code Enter} or {@code PfKey03} maps to the invalid-key message
 * branch.</p>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/coadm01c/expected/} encodes a sequence
 * of terminal submissions exercising the full state space of the
 * admin-menu transaction (per the agent_prompt Phase 0 and the fixture
 * directory's authoritative README):</p>
 * <ol>
 *   <li><strong>Initial display</strong> &mdash; first-entry path with
 *       {@code EIBCALEN &ne; 0} and {@code CDEMO-PGM-CONTEXT = ENTER}.
 *       Sets {@code CDEMO-PGM-REENTER} and re-sends the empty menu
 *       screen with all 4 admin-option lines populated, slots
 *       5&ndash;10 blank, and {@code ERRMSGO} blank.</li>
 *   <li><strong>Valid menu-option selection</strong> &mdash; operator
 *       enters {@code "1"} (or {@code "01"}); {@code PROCESS-ENTER-KEY}
 *       validates numeric, in-range, non-zero, and the target program
 *       name does NOT start with {@code "DUMMY"}; {@code XCTL
 *       PROGRAM('COUSR00C')} dispatches the user-list program.</li>
 *   <li><strong>Invalid option number</strong> &mdash; operator enters
 *       {@code "9"} (out of range &gt;4); the verbatim
 *       {@code "Please enter a valid option number..."} message fires
 *       and the screen re-renders.</li>
 *   <li><strong>PF3 back to signon</strong> &mdash; operator presses
 *       PF3; the EVALUATE branch sets {@code CDEMO-TO-PROGRAM =
 *       'COSGN00C'} and {@code XCTL PROGRAM('COSGN00C')} returns the
 *       operator to the signon screen.</li>
 * </ol>
 *
 * <p>Each submission produces a serialized {@code CoAdm01Output} screen
 * state plus zero or more SLF4J log lines; the harness concatenates all
 * screen states into {@code bms_output.txt} and all log lines into
 * {@code stdout.txt}. The two outputs are compared byte-for-byte to the
 * captured COBOL baseline.</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the base class's
 * single-output default), this test declares TWO byte-for-byte parity
 * targets:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the SLF4J/{@code DISPLAY} trace.
 *       COADM01C has limited {@code DISPLAY} verbs (the program is
 *       online-CICS with output mediated by BMS rather than terminal
 *       writes), so the captured baseline may be sparse; whatever is
 *       captured must be byte-identical.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@code CoAdm01Output} BMS screen states (one per submission in
 *       the scenario, concatenated in submission order). Each frame
 *       carries the standard 2-line header
 *       ({@code TRNNAMEO}={@code "CA00"},
 *       {@code TITLE01O}/{@code TITLE02O}={@code CCDA-TITLE01}/02 from
 *       {@code app/cpy/COTTL01Y.cpy}, {@code CURDATEO}=MM/DD/YY,
 *       {@code PGMNAMEO}={@code "COADM01C"}, {@code CURTIMEO}=HH:MM:SS),
 *       the {@code "Admin Menu"} banner at line 4 pos 35, ten 40-char
 *       option-line slots {@code OPTN001O..OPTN010O} (slots 1&ndash;4
 *       populated with the menu entries, slots 5&ndash;10 blank), the
 *       {@code "Please select an option :"} prompt at line 20, the
 *       {@code OPTIONO} numeric input echo, the {@code ERRMSGO} error
 *       line at line 23, and the standard footer
 *       {@code "ENTER=Continue  F3=Exit"} at line 24.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the admin menu-table cardinality, the
 * verbatim COBOL messages, the XCTL target selection, the AID-key
 * dispatch, the date/time header formatting, the BMS layout, or the
 * scenario sequencing breaks parity and blocks the PR.</p>
 *
 * <h2>Initial {@code @Disabled} Scaffolding</h2>
 *
 * <p>Per AAP &sect;0.6.11 ("Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available; the harness skeleton, base class, and per-program test
 * classes are created unconditionally"), the {@link #byteForByteParity()}
 * override below is annotated {@code @Disabled} with a 3-point
 * verification reason citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md} per the agent_prompt Phase 2. The
 * harness skeleton is unconditionally present so JUnit discovers and
 * reports this per-program test in CI from day one. The
 * {@code @Disabled} annotation will be removed in the same PR that
 * commits non-placeholder content under
 * {@code src/test/resources/golden/coadm01c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.menu.CoAdm01C
 * @see com.blitzy.carddemo.domain.menu.AdminMenuTable
 * @since 25
 */
@DisplayName("COADM01C \u2014 Admin Menu Golden-Record Parity")
public class CoAdm01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COADM01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "coadm01c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/coadm01c/expected/}. Encodes the
     * multi-submission CICS pseudo-conversation sequence (initial
     * display, valid menu-option selection, invalid option number,
     * PF3 back to signon) consumed by the harness orchestrator to
     * drive the {@code CoAdm01C} online-CICS state machine through the
     * full state space of the admin-menu transaction. Lives alongside
     * the expected outputs under the per-program {@code coadm01c/}
     * subtree because it is a harness-internal fixture (not part of
     * the immutable {@code app/data/ASCII/} dataset).
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/coadm01c/expected/}. Records the
     * SLF4J/{@code DISPLAY} emissions from COADM01C (the program has
     * limited {@code DISPLAY} verbs &mdash; output is primarily
     * BMS-mediated &mdash; so the captured trace may be sparse). Per
     * AAP &sect;0.7.2 the capture contains NO password bytes
     * whatsoever &mdash; COADM01C never reads {@code SEC-USR-PWD} onto
     * any log surface (the program does NO file I/O at runtime; the
     * {@code WS-USRSEC-FILE} working-storage declaration at
     * {@code app/cbl/COADM01C.cbl:L39} is dead code preserved verbatim
     * per AAP &sect;0.7.1).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/coadm01c/expected/}. Records the
     * serialized {@code CoAdm01Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * the standard 2-line header
     * ({@code TRNNAMEO}={@code "CA00"},
     * {@code TITLE01O}/{@code TITLE02O}={@code CCDA-TITLE01}/02 from
     * {@code app/cpy/COTTL01Y.cpy}, {@code CURDATEO}=MM/DD/YY,
     * {@code PGMNAMEO}={@code "COADM01C"}, {@code CURTIMEO}=HH:MM:SS),
     * the {@code "Admin Menu"} banner at line 4 pos 35, ten 40-char
     * option-line slots {@code OPTN001O..OPTN010O} (slots 1&ndash;4
     * populated with the menu entries from {@code COADM02Y}, slots
     * 5&ndash;10 blank because the admin table has only 4 entries),
     * the {@code "Please select an option :"} prompt at line 20, the
     * {@code OPTIONO} numeric input echo (2 chars), the {@code ERRMSGO}
     * error line at line 23 (78 chars), and the standard footer
     * {@code "ENTER=Continue  F3=Exit"} at line 24. Per AAP &sect;0.7.2
     * NO {@code PASSWD} field appears in any frame &mdash; the COADM01
     * BMS map at {@code app/bms/COADM01.bms} declares no such field.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.menu.CoAdm01C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests} declares
     * a test-scope dependency on {@code carddemo-application}
     * (transitively via {@code carddemo-app}) in
     * {@code java/carddemo-tests/pom.xml} per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.menu.CoAdm01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/coadm01c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * multi-submission CICS pseudo-conversation that drives the
     * {@code CoAdm01C} online-state machine through the full state
     * space (initial display with all 4 admin-option lines populated,
     * valid menu-option selection &rarr; XCTL to {@code COUSR00C/01C/
     * 02C/03C}, invalid option number error path, PF3 back to
     * {@code COSGN00C}); it lives alongside the expected outputs
     * under the per-program {@code coadm01c/} subtree because it is
     * a harness-internal fixture (not part of the immutable
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
     * {@code src/test/resources/golden/coadm01c/expected/stdout.txt},
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
     * <p>Declares the two byte-for-byte parity targets for COADM01C per
     * the agent_prompt Phase 1 multi-output declaration. Overriding
     * this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure
     * message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J/{@code DISPLAY} trace. COADM01C has limited
     *       {@code DISPLAY} verbs in the source so the captured
     *       baseline may be sparse or empty; whatever is captured must
     *       be byte-identical. Per AAP &sect;0.7.2 contains NO
     *       password bytes &mdash; COADM01C does NO file I/O at
     *       runtime and never reads {@code SEC-USR-PWD} onto any log
     *       surface.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoAdm01Output} screen states (one
     *       per submission in the scenario). Each frame carries the
     *       standard header ({@code TRNNAMEO}={@code "CA00"},
     *       {@code PGMNAMEO}={@code "COADM01C"},
     *       {@code TITLE01O}/{@code TITLE02O}=CCDA titles,
     *       {@code CURDATEO}=MM/DD/YY, {@code CURTIMEO}=HH:MM:SS),
     *       the {@code "Admin Menu"} banner, ten 40-char option-line
     *       slots ({@code OPTN001O..OPTN010O} &mdash; 4 populated, 6
     *       blank), the {@code OPTIONO} input echo, and the
     *       {@code ERRMSGO} status line. Per AAP &sect;0.7.2 NO
     *       {@code PASSWD} field appears in any frame &mdash; the
     *       COADM01 BMS map at {@code app/bms/COADM01.bms} declares no
     *       such field.</li>
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
     * pending the COBOL COADM01C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/coadm01c/expected/} per the
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
     * running surefire with {@code -Dtest=CoAdm01CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdLiCGoldenTest}, {@link CoCrdSlCGoldenTest},
     * {@link CoCrdUpCGoldenTest}, {@link CoTrn00CGoldenTest},
     * {@link CoTrn01CGoldenTest}, {@link CoTrn02CGoldenTest},
     * {@link CoUsr00CGoldenTest}, {@link CoUsr01CGoldenTest},
     * {@link CoUsr02CGoldenTest}, and {@link CoUsr03CGoldenTest}.</p>
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
        "Awaiting COBOL COADM01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 3 invariants must hold before "
            + "removing @Disabled): "
            + "(1) COADM02Y menu entries populated: the 4-entry static "
            + "table from app/cpy/COADM02Y.cpy is faithfully reified in "
            + "com.blitzy.carddemo.domain.menu.AdminMenuTable with "
            + "exactly four AdminMenuEntry instances in the canonical "
            + "order [1: 'User List (Security)' -> COUSR00C; 2: 'User "
            + "Add (Security)' -> COUSR01C; 3: 'User Update "
            + "(Security)' -> COUSR02C; 4: 'User Delete (Security)' -> "
            + "COUSR03C] per CDEMO-ADMIN-OPT-COUNT = 4 at "
            + "app/cpy/COADM02Y.cpy:L20. The BUILD-MENU-OPTIONS "
            + "paragraph at app/cbl/COADM01C.cbl:L226-L263 packs these "
            + "4 entries into slots OPTN001O..OPTN004O of the BMS "
            + "output, leaving slots OPTN005O..OPTN010O blank (40 "
            + "spaces each) because the case-by-case dispatch covers "
            + "WHEN 1 through WHEN 10 but the PERFORM VARYING terminates "
            + "at WS-IDX > CDEMO-ADMIN-OPT-COUNT (= 4). The captured "
            + "bms_output.txt frames must show exactly this layout "
            + "byte-for-byte; any drift to 10 populated entries (the "
            + "main-menu cardinality) indicates incorrect wiring to "
            + "MainMenuTable instead of AdminMenuTable and breaks "
            + "parity. "
            + "(2) XCTL targets per option selection: a valid option "
            + "selection (numeric, 1..4, non-zero, target program does "
            + "NOT start with 'DUMMY') triggers EXEC CICS XCTL "
            + "PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)) per "
            + "app/cbl/COADM01C.cbl:L142-L145. The Java translation "
            + "routes this via programRegistry.invoke(targetProgram, "
            + "commarea) where targetProgram resolves from "
            + "AdminMenuTable.ENTRIES.get(option - 1).programName(). "
            + "Option 1 -> COUSR00C; Option 2 -> COUSR01C; Option 3 -> "
            + "COUSR02C; Option 4 -> COUSR03C. The 'coming soon' "
            + "fallback at app/cbl/COADM01C.cbl:L147-L154 (when target "
            + "name starts with 'DUMMY') is dead code at runtime "
            + "because all four COADM02Y entries are real program "
            + "names; the captured stdout.txt and bms_output.txt "
            + "therefore contain NO 'This option is coming soon ...' "
            + "byte sequence. The COMING_SOON_MSG constant is "
            + "preserved verbatim in CoAdm01C nonetheless per AAP "
            + "\u00a70.7.1 for fidelity with the commented-out STRING "
            + "interpolation at app/cbl/COADM01C.cbl:L149-L151. PF3 "
            + "dispatches XCTL PROGRAM('COSGN00C') per L97-L98 "
            + "(verbatim hardcoded literal in COBOL, NOT a lookup "
            + "through CDEMO-FROM-PROGRAM); the Java translation "
            + "preserves this distinction by using "
            + "CoAdm01C.SIGNON_PROGRAM = \"COSGN00C\" as the PF3 "
            + "destination. "
            + "(3) Admin-only access guard (no User-type users can "
            + "reach this program): COADM01C does NOT perform a "
            + "runtime SEC-USR-TYPE check &mdash; the access guard is "
            + "enforced upstream by COSGN00C which inspects "
            + "SEC-USR-TYPE after successful authentication and "
            + "XCTLs to COADM01C only when the value is 'A' (Admin); "
            + "User-type users ('U') are dispatched to COMEN01C "
            + "(regular-user main menu) instead. The captured "
            + "stdout.txt and bms_output.txt therefore carry NO "
            + "admin-type warning or rejection messages &mdash; the "
            + "verification depends on the upstream COSGN00C routing "
            + "only. The COBOL working-storage declarations of "
            + "WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  ' at L39 and "
            + "COPY CSUSR01Y. at L58 are dead inclusions preserved "
            + "verbatim per AAP \u00a70.7.1; COADM01C never actually "
            + "reads USRSEC at runtime. Verbatim error messages "
            + "preserved byte-for-byte: 'Please enter a valid option "
            + "number...' from L131 (with trailing '...' ellipsis "
            + "EXACTLY 3 dots) on numeric/range/zero validation "
            + "failure; CCDA-MSG-INVALID-KEY ('Invalid key pressed. "
            + "Please see below...' from app/cpy/CSMSG01Y.cpy) on the "
            + "EIBAID WHEN OTHER branch at L99-L102. ANY normalization "
            + "(trimming ellipsis, collapsing whitespace, recasing) "
            + "breaks byte parity and blocks the PR."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
