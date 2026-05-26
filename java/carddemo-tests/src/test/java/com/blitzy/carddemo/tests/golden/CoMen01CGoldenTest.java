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
 * Byte-for-byte golden-record parity test for {@code COMEN01C}
 * (Main Menu Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COMEN01C.cbl} &mdash; the
 * {@code PROGRAM-ID COMEN01C} ({@code app/cbl/COMEN01C.cbl:L23}) online-CICS
 * program backing transaction {@code CM00} ({@code WS-TRANID PIC X(04) VALUE
 * 'CM00'} at {@code app/cbl/COMEN01C.cbl:L37}, mapset {@code COMEN01} via
 * {@code COPY COMEN01.} at {@code app/cbl/COMEN01C.cbl:L53}). The program
 * is the <strong>regular-user main menu</strong>: it presents the
 * non-administrative day-to-day transaction options sourced from the
 * static menu table {@code COMEN02Y} ({@code app/cpy/COMEN02Y.cpy} &rarr;
 * {@link com.blitzy.carddemo.domain.menu.MainMenuTable}, 10 entries:
 * {@code COACTVWC} Account View, {@code COACTUPC} Account Update,
 * {@code COCRDLIC} Credit Card List, {@code COCRDSLC} Credit Card View,
 * {@code COCRDUPC} Credit Card Update, {@code COTRN00C} Transaction List,
 * {@code COTRN01C} Transaction View, {@code COTRN02C} Transaction Add,
 * {@code CORPT00C} Transaction Reports, {@code COBIL00C} Bill Payment
 * &mdash; per {@code app/cpy/COMEN02Y.cpy:L21-L84}) and dispatches the
 * operator's selection via {@code EXEC CICS XCTL} to the chosen target
 * program. PF3 returns to the signon program ({@code COSGN00C}) and any
 * other AID key surfaces the standard invalid-key error message
 * ({@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.menu.CoMen01C}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COMEN01C is translated into the
 * {@code application/menu/} subpackage co-located with the sibling
 * translation {@link com.blitzy.carddemo.application.menu.CoAdm01C}
 * (admin-user main menu, transaction {@code CA00}). The Java
 * translation has a 2-argument constructor
 * {@code CoMen01C(ProgramRegistry programRegistry, Clock clock)} matching
 * the COBOL collaborator surface (the dynamic-CALL routing facility for
 * {@code EXEC CICS XCTL} dispatch to {@code COACTVWC/COACTUPC/COCRDLIC/
 * COCRDSLC/COCRDUPC/COTRN00C/COTRN01C/COTRN02C/CORPT00C/COBIL00C} and
 * {@code COSGN00C}, plus a {@link java.time.Clock} for deterministic
 * rendering of the {@code CURDATEO}/{@code CURTIMEO} header fields per
 * {@code POPULATE-HEADER-INFO} at {@code app/cbl/COMEN01C.cbl:L212-L231}).
 * The base harness {@link GoldenRecordTest#runProgram(Class,
 * java.nio.file.Path, java.util.List)} hook resolves these collaborators
 * with no auxiliary fixtures because COMEN01C performs <strong>NO file
 * I/O</strong> at runtime &mdash; the program operates entirely on the
 * static {@code COMEN02Y} menu table embedded in the program's
 * working-storage (the {@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '}
 * declaration at {@code app/cbl/COMEN01C.cbl:L39} is dead code preserved
 * verbatim per AAP &sect;0.7.1).</p>
 *
 * <h2>Transaction ID Distinction: {@code CM00} (NOT {@code CA00})</h2>
 *
 * <p>COMEN01C runs under CICS transaction {@code CM00}
 * ({@code WS-TRANID PIC X(04) VALUE 'CM00'} at
 * {@code app/cbl/COMEN01C.cbl:L37}) and emits {@code "CM00"} into the
 * {@code TRNNAMEO} header field on every {@code SEND MAP}. The
 * structurally near-identical admin-user main menu sibling
 * {@link com.blitzy.carddemo.application.menu.CoAdm01C} runs under
 * transaction {@code CA00} ({@code WS-TRANID VALUE 'CA00'} in
 * {@code app/cbl/COADM01C.cbl}). The captured {@code bms_output.txt}
 * fixture under {@code src/test/resources/golden/comen01c/expected/}
 * therefore carries {@code "CM00"} in the {@code TRNNAMEO} position of
 * every frame and {@code "COMEN01C"} in the {@code PGMNAMEO} position;
 * any drift to {@code "CA00"} or {@code "COADM01C"} indicates a
 * mis-wiring of the sibling pair and breaks the byte-for-byte parity
 * contract.</p>
 *
 * <h2>10-Entry Main Menu (COMEN02Y) versus 4-Entry Admin Menu (COADM02Y)</h2>
 *
 * <p>The most observable distinguishing characteristic of COMEN01C versus
 * the admin-user main menu sibling COADM01C is the menu cardinality.
 * COMEN01C dispatches a <strong>10-entry</strong> regular-user main menu
 * populated from {@code COMEN02Y.cpy} ({@code CDEMO-MENU-OPT-COUNT PIC
 * 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:L21}):</p>
 * <ol>
 *   <li><strong>Option 1</strong> &mdash; {@code "Account View"}
 *       &rarr; XCTL {@code COACTVWC}</li>
 *   <li><strong>Option 2</strong> &mdash; {@code "Account Update"}
 *       &rarr; XCTL {@code COACTUPC}</li>
 *   <li><strong>Option 3</strong> &mdash; {@code "Credit Card List"}
 *       &rarr; XCTL {@code COCRDLIC}</li>
 *   <li><strong>Option 4</strong> &mdash; {@code "Credit Card View"}
 *       &rarr; XCTL {@code COCRDSLC}</li>
 *   <li><strong>Option 5</strong> &mdash; {@code "Credit Card Update"}
 *       &rarr; XCTL {@code COCRDUPC}</li>
 *   <li><strong>Option 6</strong> &mdash; {@code "Transaction List"}
 *       &rarr; XCTL {@code COTRN00C}</li>
 *   <li><strong>Option 7</strong> &mdash; {@code "Transaction View"}
 *       &rarr; XCTL {@code COTRN01C}</li>
 *   <li><strong>Option 8</strong> &mdash; {@code "Transaction Add"}
 *       &rarr; XCTL {@code COTRN02C}</li>
 *   <li><strong>Option 9</strong> &mdash; {@code "Transaction Reports"}
 *       &rarr; XCTL {@code CORPT00C}</li>
 *   <li><strong>Option 10</strong> &mdash; {@code "Bill Payment"}
 *       &rarr; XCTL {@code COBIL00C}</li>
 * </ol>
 *
 * <p>The BMS map at {@code app/bms/COMEN01.bms} declares 12 option-line
 * fields {@code OPTN001..OPTN012}; the COBOL {@code BUILD-MENU-OPTIONS}
 * paragraph at {@code app/cbl/COMEN01C.cbl:L236-L277} has explicit cases
 * for {@code WHEN 1} through {@code WHEN 12}. Because the main menu
 * table has only 10 entries, slots 11&ndash;12 of every captured frame
 * remain blank (40 spaces each); the captured {@code bms_output.txt}
 * encodes this exact layout. The sibling COADM01C populates only 4 slots
 * from its 4-entry admin menu table, so the COADM01C fixture would show
 * populated slots 1&ndash;4 (with slots 5&ndash;10 blank) by contrast.</p>
 *
 * <h2>User-Type Access Guard (AAP &sect;0.7.1)</h2>
 *
 * <p>COMEN01C is the destination for users with {@code SEC-USR-TYPE='U'}
 * (regular User), with the upstream routing enforced by {@link
 * com.blitzy.carddemo.application.signon.CoSgn00C}: the signon program
 * inspects {@code SEC-USR-TYPE} after a successful authentication, and
 * if the value is {@code 'A'} (Admin) it XCTLs to {@code COADM01C};
 * otherwise (value {@code 'U'}) it XCTLs to {@code COMEN01C} (this
 * program). Unlike COADM01C (which performs NO runtime user-type
 * check), COMEN01C <strong>does</strong> perform a per-option access
 * guard at {@code app/cbl/COMEN01C.cbl:L136-L143}:</p>
 *
 * <pre>
 * IF CDEMO-USRTYP-USER AND
 *    CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
 *     SET ERR-FLG-ON          TO TRUE
 *     MOVE SPACES             TO WS-MESSAGE
 *     MOVE 'No access - Admin Only option... ' TO
 *                             WS-MESSAGE
 *     PERFORM SEND-MENU-SCREEN
 * END-IF
 * </pre>
 *
 * <p>This guard rejects any User-type operator who selects an
 * admin-only menu entry by emitting the verbatim error message
 * {@code "No access - Admin Only option... "} (with single trailing
 * space preserved exactly &mdash; see {@link
 * com.blitzy.carddemo.application.menu.CoMen01C#NO_ACCESS_ADMIN_MSG})
 * and suppressing the XCTL. <strong>In practice this branch is
 * currently dead code</strong> because all 10 entries in
 * {@code COMEN02Y.cpy} carry {@code USRTYPE='U'} (regular-user); no
 * entry is admin-only. Per AAP &sect;0.7.1 ("if a COBOL paragraph
 * contains dead code or obvious bugs, translate it faithfully and flag
 * it in a MIGRATION_NOTES.md; do not 'fix' it in this refactor") the
 * Java translation preserves this entire conditional block including
 * the message constant, even though no captured fixture frame can
 * legitimately exercise the rejection path with the current menu
 * table.</p>
 *
 * <h2>Coming-Soon Message: First-Word Interpolation</h2>
 *
 * <p>The "coming soon" placeholder branch in {@code PROCESS-ENTER-KEY}
 * at {@code app/cbl/COMEN01C.cbl:L157-L164} differs from the sibling
 * COADM01C in one critical respect: the {@code STRING} option-name
 * interpolation is <strong>NOT commented out</strong>. Lines
 * L159-L163 read:</p>
 *
 * <pre>
 * STRING 'This option '       DELIMITED BY SIZE
 *         CDEMO-MENU-OPT-NAME(WS-OPTION)
 *                         DELIMITED BY SPACE
 *         'is coming soon ...'   DELIMITED BY SIZE
 *    INTO WS-MESSAGE
 * </pre>
 *
 * <p>The {@code DELIMITED BY SPACE} clause on the
 * {@code CDEMO-MENU-OPT-NAME} reference emits only the
 * <strong>first whitespace-delimited token</strong> of the option name
 * &mdash; for example, the entry {@code "Account View"} produces
 * {@code "This option Account is coming soon ..."} (NOT
 * {@code "This option Account View is coming soon ..."}). Per AAP
 * &sect;0.7.1 the Java translation in
 * {@link com.blitzy.carddemo.application.menu.CoMen01C} preserves this
 * exact first-word interpolation via the {@code COMING_SOON_PREFIX}
 * + {@code firstWord} + {@code COMING_SOON_SUFFIX} concatenation
 * (where {@code firstWord} is computed by splitting the trimmed
 * option-name on whitespace and taking element zero); any drift to
 * full-name interpolation or naive trimming breaks byte parity. The
 * COBOL guard at {@code app/cbl/COMEN01C.cbl:L146} (the leading-5-char
 * {@code DUMMY} prefix check) suppresses XCTL for placeholder entries
 * whose program names start with {@code "DUMMY"}; none of the current
 * 10 entries in {@code COMEN02Y.cpy} use that prefix so the
 * "coming soon" fall-through is presently dead code, but it is
 * preserved verbatim per AAP &sect;0.7.1 for faithful translation.</p>
 *
 * <h2>Invalid Option Validation</h2>
 *
 * <p>The {@code PROCESS-ENTER-KEY} paragraph at
 * {@code app/cbl/COMEN01C.cbl:L115-L165} validates the entered option
 * number with three guards (per L127-L129):</p>
 * <ol>
 *   <li><strong>Non-numeric</strong>: {@code WS-OPTION IS NOT NUMERIC}
 *       triggers when the input contains anything other than digits
 *       after the {@code INSPECT WS-OPTION-X REPLACING ALL ' ' BY '0'}
 *       zero-fill of leading spaces (L123).</li>
 *   <li><strong>Out-of-range</strong>: {@code WS-OPTION &gt;
 *       CDEMO-MENU-OPT-COUNT} where {@code CDEMO-MENU-OPT-COUNT = 10}
 *       per {@code app/cpy/COMEN02Y.cpy:L21}.</li>
 *   <li><strong>Zero</strong>: {@code WS-OPTION = ZEROS} (option 0 is
 *       not a valid selection).</li>
 * </ol>
 *
 * <p>Any of these three conditions fires the verbatim error message
 * {@code "Please enter a valid option number..."} (with trailing
 * {@code "..."} ellipsis preserved exactly) per
 * {@code app/cbl/COMEN01C.cbl:L131-L132}, sets the error flag
 * ({@code WS-ERR-FLG = 'Y'}), and re-renders the screen via
 * {@code SEND-MENU-SCREEN}. Any normalization of the ellipsis,
 * collapsing of whitespace, or recasing of the message text breaks
 * byte parity and blocks the PR per AAP &sect;0.6.11.</p>
 *
 * <h2>PF3 Back to Signon</h2>
 *
 * <p>The {@code EVALUATE EIBAID} block at
 * {@code app/cbl/COMEN01C.cbl:L93-L103} handles three AID-key cases:</p>
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
 * <p>The Java translation enumerates all 16 permits of the sealed
 * {@link com.blitzy.carddemo.domain.text.CcWorkAreas.AidKey} hierarchy
 * via an exhaustive pattern-matching {@code switch} per AAP &sect;0.7.3
 * with NO {@code default} branch &mdash; any AID key other than
 * {@code Enter} or {@code PfKey03} maps to the invalid-key message
 * branch.</p>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/comen01c/expected/} encodes a
 * sequence of terminal submissions exercising the full state space of
 * the main-menu transaction (per the agent_prompt Phase 0 and the
 * fixture directory's authoritative README):</p>
 * <ol>
 *   <li><strong>Initial display</strong> &mdash; first-entry path with
 *       {@code EIBCALEN &ne; 0} and {@code CDEMO-PGM-CONTEXT = ENTER}.
 *       Sets {@code CDEMO-PGM-REENTER} and re-sends the empty menu
 *       screen with all 10 main-menu option lines populated, slots
 *       11&ndash;12 blank, and {@code ERRMSGO} blank.</li>
 *   <li><strong>Valid menu-option selection</strong> &mdash; operator
 *       enters a valid option {@code "1"} (or {@code "01"});
 *       {@code PROCESS-ENTER-KEY} validates numeric, in-range,
 *       non-zero, the user-type access guard passes (all entries are
 *       USRTYPE='U'), and the target program name does NOT start with
 *       {@code "DUMMY"}; {@code XCTL PROGRAM('COACTVWC')} dispatches
 *       the account-view program.</li>
 *   <li><strong>Invalid option number</strong> &mdash; operator enters
 *       {@code "15"} (out of range &gt;10); the verbatim
 *       {@code "Please enter a valid option number..."} message fires
 *       and the screen re-renders.</li>
 *   <li><strong>PF3 back to signon</strong> &mdash; operator presses
 *       PF3; the EVALUATE branch sets {@code CDEMO-TO-PROGRAM =
 *       'COSGN00C'} and {@code XCTL PROGRAM('COSGN00C')} returns the
 *       operator to the signon screen.</li>
 * </ol>
 *
 * <p>Each submission produces a serialized {@code CoMen01Output} screen
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
 *       COMEN01C has limited {@code DISPLAY} verbs (the program is
 *       online-CICS with output mediated by BMS rather than terminal
 *       writes), so the captured baseline may be sparse; whatever is
 *       captured must be byte-identical.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@code CoMen01Output} BMS screen states (one per submission in
 *       the scenario, concatenated in submission order). Each frame
 *       carries the standard 2-line header
 *       ({@code TRNNAMEO}={@code "CM00"},
 *       {@code TITLE01O}/{@code TITLE02O}={@code CCDA-TITLE01}/02 from
 *       {@code app/cpy/COTTL01Y.cpy}, {@code CURDATEO}=MM/DD/YY,
 *       {@code PGMNAMEO}={@code "COMEN01C"}, {@code CURTIMEO}=HH:MM:SS),
 *       the menu banner, twelve 40-char option-line slots
 *       {@code OPTN001O..OPTN012O} (slots 1&ndash;10 populated with the
 *       menu entries from {@code COMEN02Y}, slots 11&ndash;12 blank
 *       because the main menu table has only 10 entries), the
 *       {@code "Please select an option :"} prompt, the
 *       {@code OPTIONO} numeric input echo, the {@code ERRMSGO} error
 *       line, and the standard footer {@code "ENTER=Continue
 *       F3=Exit"}.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the main menu-table cardinality, the
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
 * override below is annotated {@code @Disabled} with a 4-point
 * verification reason citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md} per the agent_prompt Phase 2. The
 * harness skeleton is unconditionally present so JUnit discovers and
 * reports this per-program test in CI from day one. The
 * {@code @Disabled} annotation will be removed in the same PR that
 * commits non-placeholder content under
 * {@code src/test/resources/golden/comen01c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.menu.CoMen01C
 * @see com.blitzy.carddemo.domain.menu.MainMenuTable
 * @see CoAdm01CGoldenTest
 * @since 25
 */
@DisplayName("COMEN01C \u2014 Main Menu Golden-Record Parity")
public class CoMen01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COMEN01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "comen01c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/comen01c/expected/}. Encodes the
     * multi-submission CICS pseudo-conversation sequence (initial
     * display, valid menu-option selection, invalid option number,
     * PF3 back to signon) consumed by the harness orchestrator to
     * drive the {@code CoMen01C} online-CICS state machine through the
     * full state space of the main-menu transaction. Lives alongside
     * the expected outputs under the per-program {@code comen01c/}
     * subtree because it is a harness-internal fixture (not part of
     * the immutable {@code app/data/ASCII/} dataset).
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/comen01c/expected/}. Records the
     * SLF4J/{@code DISPLAY} emissions from COMEN01C (the program has
     * limited {@code DISPLAY} verbs &mdash; output is primarily
     * BMS-mediated &mdash; so the captured trace may be sparse). Per
     * AAP &sect;0.7.2 the capture contains NO password bytes
     * whatsoever &mdash; COMEN01C never reads {@code SEC-USR-PWD} onto
     * any log surface (the program does NO file I/O at runtime; the
     * {@code WS-USRSEC-FILE} working-storage declaration at
     * {@code app/cbl/COMEN01C.cbl:L39} is dead code preserved verbatim
     * per AAP &sect;0.7.1).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/comen01c/expected/}. Records the
     * serialized {@code CoMen01Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * the standard 2-line header
     * ({@code TRNNAMEO}={@code "CM00"},
     * {@code TITLE01O}/{@code TITLE02O}={@code CCDA-TITLE01}/02 from
     * {@code app/cpy/COTTL01Y.cpy}, {@code CURDATEO}=MM/DD/YY,
     * {@code PGMNAMEO}={@code "COMEN01C"}, {@code CURTIMEO}=HH:MM:SS),
     * the menu banner, twelve 40-char option-line slots
     * {@code OPTN001O..OPTN012O} (slots 1&ndash;10 populated with the
     * 10 menu entries from {@code COMEN02Y}, slots 11&ndash;12 blank
     * because the main menu table has only 10 entries), the
     * {@code "Please select an option :"} prompt, the {@code OPTIONO}
     * numeric input echo (2 chars), the {@code ERRMSGO} error line
     * (78 chars), and the standard footer
     * {@code "ENTER=Continue  F3=Exit"}. Per AAP &sect;0.7.2 NO
     * {@code PASSWD} field appears in any frame &mdash; the COMEN01
     * BMS map at {@code app/bms/COMEN01.bms} declares no such field.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.menu.CoMen01C}{@code .class}.
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
        return com.blitzy.carddemo.application.menu.CoMen01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/comen01c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * multi-submission CICS pseudo-conversation that drives the
     * {@code CoMen01C} online-state machine through the full state
     * space (initial display with all 10 main-menu option lines
     * populated, valid menu-option selection &rarr; XCTL to
     * {@code COACTVWC} / {@code COACTUPC} / {@code COCRDLIC} /
     * {@code COCRDSLC} / {@code COCRDUPC} / {@code COTRN00C} /
     * {@code COTRN01C} / {@code COTRN02C} / {@code CORPT00C} /
     * {@code COBIL00C}, invalid option number error path, PF3 back to
     * {@code COSGN00C}); it lives alongside the expected outputs under
     * the per-program {@code comen01c/} subtree because it is a
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
     * {@code src/test/resources/golden/comen01c/expected/stdout.txt},
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
     * <p>Declares the two byte-for-byte parity targets for COMEN01C per
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
     *       SLF4J/{@code DISPLAY} trace. COMEN01C has limited
     *       {@code DISPLAY} verbs in the source so the captured
     *       baseline may be sparse or empty; whatever is captured must
     *       be byte-identical. Per AAP &sect;0.7.2 contains NO
     *       password bytes &mdash; COMEN01C does NO file I/O at
     *       runtime and never reads {@code SEC-USR-PWD} onto any log
     *       surface.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoMen01Output} screen states (one
     *       per submission in the scenario). Each frame carries the
     *       standard header ({@code TRNNAMEO}={@code "CM00"},
     *       {@code PGMNAMEO}={@code "COMEN01C"},
     *       {@code TITLE01O}/{@code TITLE02O}=CCDA titles,
     *       {@code CURDATEO}=MM/DD/YY, {@code CURTIMEO}=HH:MM:SS),
     *       twelve 40-char option-line slots
     *       ({@code OPTN001O..OPTN012O} &mdash; 10 populated from
     *       {@link com.blitzy.carddemo.domain.menu.MainMenuTable},
     *       2 blank), the {@code OPTIONO} input echo, and the
     *       {@code ERRMSGO} status line. Per AAP &sect;0.7.2 NO
     *       {@code PASSWD} field appears in any frame &mdash; the
     *       COMEN01 BMS map at {@code app/bms/COMEN01.bms} declares no
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
     * pending the COBOL COMEN01C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/comen01c/expected/} per the
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
     * running surefire with {@code -Dtest=CoMen01CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoAdm01CGoldenTest}, {@link CoActVwCGoldenTest},
     * {@link CoActUpCGoldenTest}, {@link CoCrdLiCGoldenTest},
     * {@link CoCrdSlCGoldenTest}, {@link CoCrdUpCGoldenTest},
     * {@link CoTrn00CGoldenTest}, {@link CoTrn01CGoldenTest},
     * {@link CoTrn02CGoldenTest}, {@link CoUsr00CGoldenTest},
     * {@link CoUsr01CGoldenTest}, {@link CoUsr02CGoldenTest}, and
     * {@link CoUsr03CGoldenTest}.</p>
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
        "Awaiting COBOL COMEN01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 4 invariants must hold before "
            + "removing @Disabled): "
            + "(1) COMEN02Y menu entries populated: the 10-entry static "
            + "table from app/cpy/COMEN02Y.cpy is faithfully reified in "
            + "com.blitzy.carddemo.domain.menu.MainMenuTable with exactly "
            + "ten MainMenuEntry instances in the canonical order [1: "
            + "'Account View' -> COACTVWC; 2: 'Account Update' -> COACTUPC; "
            + "3: 'Credit Card List' -> COCRDLIC; 4: 'Credit Card View' -> "
            + "COCRDSLC; 5: 'Credit Card Update' -> COCRDUPC; 6: "
            + "'Transaction List' -> COTRN00C; 7: 'Transaction View' -> "
            + "COTRN01C; 8: 'Transaction Add' -> COTRN02C; 9: "
            + "'Transaction Reports' -> CORPT00C; 10: 'Bill Payment' -> "
            + "COBIL00C] per CDEMO-MENU-OPT-COUNT = 10 at "
            + "app/cpy/COMEN02Y.cpy:L21. All 10 entries carry USRTYPE='U' "
            + "(regular user). The BUILD-MENU-OPTIONS paragraph at "
            + "app/cbl/COMEN01C.cbl:L236-L277 packs these 10 entries into "
            + "slots OPTN001O..OPTN010O of the BMS output, leaving slots "
            + "OPTN011O..OPTN012O blank (40 spaces each) because the "
            + "case-by-case dispatch covers WHEN 1 through WHEN 12 but the "
            + "PERFORM VARYING terminates at WS-IDX > CDEMO-MENU-OPT-COUNT "
            + "(= 10). The captured bms_output.txt frames must show "
            + "exactly this layout byte-for-byte; any drift to 4 populated "
            + "entries (the admin-menu cardinality) indicates incorrect "
            + "wiring to AdminMenuTable instead of MainMenuTable and "
            + "breaks parity. "
            + "(2) XCTL targets per option selection: a valid option "
            + "selection (numeric, 1..10, non-zero, target program does "
            + "NOT start with 'DUMMY', user-type access check passes) "
            + "triggers EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME("
            + "WS-OPTION)) per app/cbl/COMEN01C.cbl:L152-L155. The Java "
            + "translation routes this via programRegistry.invoke("
            + "targetProgram, commarea) where targetProgram resolves from "
            + "MainMenuTable.ENTRIES.get(option - 1).programName(). Option "
            + "1 -> COACTVWC; Option 2 -> COACTUPC; Option 3 -> COCRDLIC; "
            + "Option 4 -> COCRDSLC; Option 5 -> COCRDUPC; Option 6 -> "
            + "COTRN00C; Option 7 -> COTRN01C; Option 8 -> COTRN02C; "
            + "Option 9 -> CORPT00C; Option 10 -> COBIL00C. The 'coming "
            + "soon' fallback at app/cbl/COMEN01C.cbl:L157-L164 (when "
            + "target name starts with 'DUMMY') is dead code at runtime "
            + "because all ten COMEN02Y entries are real program names; "
            + "the captured stdout.txt and bms_output.txt therefore "
            + "contain NO 'This option <firstword> is coming soon ...' "
            + "byte sequence with any current entry. The "
            + "COMING_SOON_PREFIX + first-word + COMING_SOON_SUFFIX "
            + "interpolation in CoMen01C is preserved verbatim "
            + "nonetheless per AAP \u00a70.7.1 for fidelity with the "
            + "DELIMITED BY SPACE clause at app/cbl/COMEN01C.cbl:L161 "
            + "which emits only the first whitespace-delimited token of "
            + "the option name. PF3 dispatches XCTL "
            + "PROGRAM('COSGN00C') per L97-L98 (verbatim hardcoded "
            + "literal in COBOL, NOT a lookup through CDEMO-FROM-PROGRAM); "
            + "the Java translation preserves this distinction by using "
            + "CoMen01C.SIGNON_PROGRAM = \"COSGN00C\" as the PF3 "
            + "destination. "
            + "(3) User-type access permitted (Admin should be redirected "
            + "to COADM01C): COMEN01C is the User-type destination "
            + "established by the upstream COSGN00C routing &mdash; the "
            + "signon program inspects SEC-USR-TYPE after authentication "
            + "and XCTLs to COMEN01C when the value is 'U' (User); "
            + "Admin-type users ('A') are dispatched to COADM01C "
            + "(admin main menu) instead. Within COMEN01C itself, the "
            + "PROCESS-ENTER-KEY paragraph at app/cbl/COMEN01C.cbl:L136-"
            + "L143 performs a per-option access guard rejecting any "
            + "User who selects an admin-only entry (USRTYPE='A') with "
            + "the verbatim message 'No access - Admin Only option... ' "
            + "(single trailing space preserved exactly &mdash; see "
            + "CoMen01C.NO_ACCESS_ADMIN_MSG); however this branch is "
            + "currently dead code because all 10 COMEN02Y entries "
            + "carry USRTYPE='U' (no entry is admin-only). The captured "
            + "stdout.txt and bms_output.txt therefore carry NO "
            + "'No access - Admin Only option... ' byte sequence with "
            + "the current menu table; if a future menu-table revision "
            + "introduces an admin-only entry, the guard activates and "
            + "the message must appear verbatim in the captured fixture. "
            + "(4) Invalid option produces error message without XCTL: "
            + "the three validation guards at app/cbl/COMEN01C.cbl:L127-"
            + "L129 (non-numeric, > CDEMO-MENU-OPT-COUNT (= 10), or "
            + "zeros) all emit the verbatim message 'Please enter a "
            + "valid option number...' (trailing '...' ellipsis EXACTLY "
            + "3 dots) per L131-L132, set WS-ERR-FLG='Y', and PERFORM "
            + "SEND-MENU-SCREEN to re-render the menu with the error "
            + "message at ERRMSGO of COMEN1AO. CRITICALLY: no XCTL is "
            + "issued on the invalid-option path &mdash; the BMS frame "
            + "renders the unchanged 10-entry menu plus the error "
            + "message, and control RETURNs via EXEC CICS RETURN "
            + "TRANSID('CM00') for the next operator submission. The "
            + "captured bms_output.txt for the invalid-option scenario "
            + "step must show identical OPTN001O..OPTN012O slot content "
            + "to the initial display (a 'soft re-render') with only "
            + "the ERRMSGO and OPTIONO fields differing. The EVALUATE "
            + "EIBAID WHEN OTHER branch at app/cbl/COMEN01C.cbl:L99-L102 "
            + "produces the verbatim CCDA-MSG-INVALID-KEY message "
            + "('Invalid key pressed. Please see below...' from "
            + "app/cpy/CSMSG01Y.cpy) for AID keys other than DFHENTER "
            + "and DFHPF3. ANY normalization of these messages (trimming "
            + "ellipsis, collapsing whitespace, recasing, dropping "
            + "trailing space from the admin-only message) breaks byte "
            + "parity and blocks the PR."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
