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
 * Byte-for-byte golden-record parity test for {@code COUSR00C}
 * (User List Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COUSR00C.cbl} &mdash; the
 * {@code PROGRAM-ID COUSR00C} ({@code app/cbl/COUSR00C.cbl:L23}) online-CICS
 * program backing transaction {@code CU00} ({@code WS-TRANID PIC X(04) VALUE
 * 'CU00'} at {@code app/cbl/COUSR00C.cbl:L37}, mapset {@code COUSR00} via
 * {@code COPY COUSR00.} at {@code app/cbl/COUSR00C.cbl:L76}). The program is
 * the <strong>admin-only user-list</strong> transaction: it performs a
 * paginated browse of the {@code USRSEC} VSAM KSDS ({@code WS-USRSEC-FILE
 * PIC X(08) VALUE 'USRSEC  '} at {@code app/cbl/COUSR00C.cbl:L39}) via the
 * {@code STARTBR}/{@code READNEXT}/{@code READPREV}/{@code ENDBR} browse
 * verbs at 10 rows per page (the {@code USER-REC OCCURS 10 TIMES} group at
 * {@code app/cbl/COUSR00C.cbl:L57-L64}). The operator may type a
 * single-letter selection ({@code 'U'} for update or {@code 'D'} for
 * delete) into any row's {@code SELxxxx} field; the controller XCTLs to
 * {@link com.blitzy.carddemo.application.user.CoUsr02C} (user update,
 * transaction {@code CU02}) or
 * {@link com.blitzy.carddemo.application.user.CoUsr03C} (user delete,
 * transaction {@code CU03}) respectively, carrying the selected
 * {@code USER-ID PIC X(08)} in {@code CDEMO-CU00-USR-SELECTED} of the
 * commarea. Function keys: {@code ENTER}=process selection (or refresh
 * from {@code USRIDIN} search key); {@code PF3}=back to admin menu
 * (COADM01C); {@code PF7}=page backward via
 * {@code CDEMO-CU00-USRID-FIRST}; {@code PF8}=page forward via
 * {@code CDEMO-CU00-USRID-LAST}.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.user.CoUsr00C}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COUSR00C is translated into the
 * {@code application/user/} subpackage co-located with its drill-through
 * targets {@link com.blitzy.carddemo.application.user.CoUsr02C} (user
 * update, transaction {@code CU02}) and
 * {@link com.blitzy.carddemo.application.user.CoUsr03C} (user delete,
 * transaction {@code CU03}), as well as the sibling
 * {@link com.blitzy.carddemo.application.user.CoUsr01C} (user add,
 * transaction {@code CU01}). The constructor takes two ports:
 * {@link com.blitzy.carddemo.domain.port.UserSecurityRepository} (the
 * {@code USRSEC} adapter providing {@code STARTBR}/{@code READNEXT}/
 * {@code READPREV}/{@code ENDBR} browse semantics) and
 * {@link com.blitzy.carddemo.application.ProgramRegistry} (the dynamic
 * {@code CALL} dispatcher for {@code XCTL} targets).</p>
 *
 * <h2>Admin-Only Access (SEC-USR-TYPE='A')</h2>
 *
 * <p>COUSR00C is the canonical admin-restricted browse: the only
 * legitimate XCTL source is {@link com.blitzy.carddemo.application.menu.CoAdm01C}
 * (admin menu, transaction {@code CA00}), and the empty-commarea path at
 * {@code app/cbl/COUSR00C.cbl} (under the {@code IF EIBCALEN = 0} guard)
 * redirects to {@code COSGN00C} when no commarea is supplied, forcing
 * operator authentication before reaching the user-list screen. COUSR00C
 * itself never inspects the commarea's {@code CDEMO-USER-TYPE} field
 * because the access guard is enforced upstream by the admin menu (the
 * {@code SEC-USR-TYPE='A'} check). The captured {@code stdout.txt}
 * carries NO admin-type warning bytes &mdash; the verification depends
 * on the upstream menu routing only.</p>
 *
 * <h2>NO Password in Logs (AAP &sect;0.7.2)</h2>
 *
 * <p>The <strong>defining security invariant</strong> of the COUSR00C
 * parity test: the {@code SEC-USR-PWD} column of the {@code USRSEC}
 * dataset MUST NEVER appear in the {@code stdout.txt} capture. The
 * COBOL program at {@code app/cbl/COUSR00C.cbl:L191-L200} explicitly
 * projects only {@code SEC-USR-ID} (to {@code USER-ID(WS-IDX)}),
 * {@code SEC-USR-FNAME}/{@code SEC-USR-LNAME} (concatenated via
 * {@code STRING} into {@code USER-NAME(WS-IDX)}), and {@code SEC-USR-TYPE}
 * (to {@code USER-TYPE(WS-IDX)}); it NEVER reads {@code SEC-USR-PWD} onto
 * any screen field or any log surface. Per AAP &sect;0.1.3 storage and
 * logging are <em>different surfaces</em> &mdash; the password is
 * preserved verbatim in {@code SEC-USR-PWD PIC X(08)}
 * ({@code app/cpy/CSUSR01Y.cpy:L21}) within the {@code USRSEC} fixture
 * (the canonical credential store), but the SLF4J logger inside
 * {@link com.blitzy.carddemo.application.user.CoUsr00C} NEVER receives
 * the plaintext value as an argument to any {@code log.info(...)} /
 * {@code log.warn(...)} / {@code log.error(...)} call (only the user-id
 * appears in log messages, when at all). Furthermore, the {@code COUSR00}
 * BMS map at {@code app/bms/COUSR00.bms} declares NO {@code PASSWD}
 * field &mdash; only {@code USRIDIN} (search key), the ten row groups
 * ({@code SEL0001..SEL0010}, {@code USRID01..USRID10},
 * {@code FNAME01..FNAME10}, {@code LNAME01..LNAME10},
 * {@code UTYPE01..UTYPE10}), the standard header, and {@code ERRMSG}.
 * Any byte sequence resembling a plaintext password in
 * {@code stdout.txt} or {@code bms_output.txt} breaks AAP &sect;0.7.2
 * and blocks the PR unconditionally.</p>
 *
 * <h2>STARTBR/READNEXT/READPREV/ENDBR Sequencing</h2>
 *
 * <p>The COBOL paragraphs {@code PROCESS-PAGE-FORWARD} and
 * {@code PROCESS-PAGE-BACKWARD} realize the canonical VSAM browse
 * pattern. Forward paging seeds {@code STARTBR} from
 * {@code CDEMO-CU00-USRID-LAST} (or {@code LOW-VALUES} on first entry),
 * issues up to ten {@code READNEXT}s, and releases the cursor via
 * {@code ENDBR}. Backward paging seeds {@code STARTBR} from
 * {@code CDEMO-CU00-USRID-FIRST}, issues up to ten {@code READPREV}s,
 * and releases the cursor via {@code ENDBR}. Browse cursors are always
 * released by the matching {@code ENDBR}; no cursor leakage. The Java
 * translation preserves these contracts through
 * {@link com.blitzy.carddemo.domain.port.UserSecurityRepository#startBrowse}
 * / {@code readNext} / {@code readPrev} / {@code endBrowse} port
 * methods. Read-only invariant: COUSR00C never modifies any record (no
 * {@code REWRITE}, no {@code WRITE}, no {@code DELETE}, no
 * {@code SYNCPOINT}; only browse verbs), so the {@code usrsec.txt}
 * auxiliary fixture MUST be byte-identical to its pre-test state after
 * the run; any divergence indicates a regression.</p>
 *
 * <h2>Row Selection XCTL Dispatch ('U' &rarr; COUSR02C; 'D' &rarr; COUSR03C)</h2>
 *
 * <p>The {@code ENTER} key handler scans rows 1-10 for a non-blank
 * {@code USER-SEL} field. On the first non-blank match it inspects the
 * selection character: {@code 'U'} XCTLs to
 * {@link com.blitzy.carddemo.application.user.CoUsr02C} (user update,
 * transaction {@code CU02}) with {@code CDEMO-CU02-USR-SELECTED} set to
 * the row's {@code USER-ID}; {@code 'D'} XCTLs to
 * {@link com.blitzy.carddemo.application.user.CoUsr03C} (user delete,
 * transaction {@code CU03}) with {@code CDEMO-CU03-USR-SELECTED} set to
 * the row's {@code USER-ID}. Any other selection character emits the
 * verbatim {@code 'Invalid selection ...'} message (with trailing
 * {@code ...} ellipsis preserved exactly) and re-renders the current
 * page. If no row has a non-blank selection, the {@code ENTER} key
 * falls through to a forward scan from the {@code USRIDIN} search key
 * (or {@code LOW-VALUES} if {@code USRIDIN} is blank).</p>
 *
 * <h2>Test Scope &mdash; PR Gate (AAP &sect;0.6.11)</h2>
 *
 * <p>This is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11 ("These are non-negotiable and run on every PR"). The
 * test asserts byte-for-byte equality between two captured outputs of
 * the Java translation and the COBOL baseline:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the SLF4J/{@code DISPLAY} trace
 *       (verbatim error messages with trailing ellipsis preserved);
 *       per AAP &sect;0.7.2 contains NO password bytes.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@code CoUsr00Output} BMS screen states across the paging and
 *       selection sequence; carries the user-id, first-name, last-name,
 *       and user-type columns for up to ten rows per frame but NO
 *       password column (COUSR00 BMS map omits PASSWD entirely).</li>
 * </ol>
 *
 * <h2>Initial {@code @Disabled} Scaffolding</h2>
 *
 * <p>Per AAP &sect;0.6.11 ("Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available"), this test is created with {@code @Disabled} and a
 * detailed 6-point verification reason citing the COBOL capture
 * procedure documented in {@code java/MIGRATION_NOTES.md}. The harness
 * skeleton is unconditionally present so JUnit discovers and reports
 * this per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits non-placeholder
 * content under {@code src/test/resources/golden/cousr00c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.user.CoUsr00C
 * @since 25
 */
@DisplayName("COUSR00C \u2014 User List Golden-Record Parity (admin-only)")
public class CoUsr00CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COUSR00C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cousr00c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cousr00c/expected/}. Encodes the
     * multi-submission pseudo-conversation sequence (initial display
     * from admin menu, PF8 forward paging, PF7 backward paging, row
     * 'U' selection &rarr; XCTL COUSR02C, row 'D' selection &rarr;
     * XCTL COUSR03C, PF3 back to admin menu) consumed by the harness
     * orchestrator to drive the {@code CoUsr00C} online-CICS state
     * machine through the full state space of the user-list
     * transaction.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cousr00c/expected/}. Records the
     * SLF4J/{@code DISPLAY} emissions (with trailing {@code ...}
     * ellipsis preserved exactly when present). Per AAP &sect;0.7.2 the
     * capture contains NO password bytes whatsoever &mdash; COUSR00C
     * never reads {@code SEC-USR-PWD} from the {@code USRSEC} record
     * onto any log surface; the SLF4J logger inside
     * {@link com.blitzy.carddemo.application.user.CoUsr00C} receives
     * only the user-id and never the password as a log argument.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cousr00c/expected/}. Records the
     * serialized {@code CoUsr00Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * the search-key field {@code USRIDIN} (8 chars), the ten row
     * groups {@code SEL0001..SEL0010} (1 char each, selection input),
     * {@code USRID01..USRID10} (8 chars each, user-id),
     * {@code FNAME01..FNAME10} (25 chars each, concatenated first+last
     * name), {@code UTYPE01..UTYPE10} (8 chars each, user-type), plus
     * the standard header line ({@code TRNNAME}, {@code TITLE01},
     * {@code CURDATE}, {@code PGMNAME}, {@code TITLE02},
     * {@code CURTIME}), the {@code "List Users"} banner, and the
     * {@code ERRMSG} status line. Per AAP &sect;0.7.2 NO
     * {@code PASSWD} field appears in any frame &mdash; the COUSR00
     * BMS map at {@code app/bms/COUSR00.bms} declares no such field.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the auxiliary input fixture under
     * {@code src/test/resources/golden/cousr00c/expected/} that backs
     * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port. Contains the pre-browse state of the {@code USRSEC}
     * dataset: multiple 80-byte {@code SEC-USER-DATA} records
     * ({@code SEC-USR-ID PIC X(08)} +
     * {@code SEC-USR-FNAME PIC X(20)} +
     * {@code SEC-USR-LNAME PIC X(20)} +
     * {@code SEC-USR-PWD PIC X(08)} +
     * {@code SEC-USR-TYPE PIC X(01)} +
     * {@code SEC-USR-FILLER PIC X(23)} = 80 bytes per
     * {@code app/cpy/CSUSR01Y.cpy:L17-L23}). The fixture lives under
     * {@code expected/} (not {@code input/}) because it is test-owned
     * scaffolding shared between COBOL capture and Java translation.
     *
     * <p>Plaintext password bytes ARE present in this fixture at
     * offset 48 of each 80-byte record per AAP &sect;0.1.3 (storage
     * surface preserves plaintext for behavioural parity), but those
     * bytes MUST NEVER appear in the {@code stdout.txt} or
     * {@code bms_output.txt} captures per AAP &sect;0.7.2 (logging
     * surface masks them entirely). The fixture is therefore used as
     * a <strong>negative assertion source</strong>: every plaintext
     * password byte in {@code usrsec.txt} must be ABSENT from the
     * captured output bytes.</p>
     *
     * <p>Read-only at the file-system level &mdash; COUSR00C only
     * issues browse verbs ({@code STARTBR}/{@code READNEXT}/
     * {@code READPREV}/{@code ENDBR}), never {@code WRITE} /
     * {@code REWRITE} / {@code DELETE} / {@code SYNCPOINT}. The
     * harness's {@code runProgram(...)} hook copies the fixture into a
     * temp directory before the run so any inadvertent mutation does
     * NOT pollute the source-controlled file; the post-run copy is
     * expected to be byte-identical to the pre-run copy.</p>
     */
    private static final String USRSEC_TXT = "usrsec.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.user.CoUsr00C}{@code .class}.
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
        return com.blitzy.carddemo.application.user.CoUsr00C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cousr00c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * multi-submission CICS pseudo-conversation that drives the
     * {@code CoUsr00C} online-state machine through the full state
     * space (initial display from admin menu, PF8 forward paging
     * through the {@code USRSEC} dataset, PF7 backward paging, row
     * 'U' selection &rarr; XCTL COUSR02C, row 'D' selection &rarr;
     * XCTL COUSR03C, invalid selection re-render, PF3 back to admin
     * menu); it lives alongside the expected outputs under the
     * per-program {@code cousr00c/} subtree because it is a
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
     * {@code src/test/resources/golden/cousr00c/expected/stdout.txt},
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
     * fixture paths reflecting the COUSR00C collaborator surface:</p>
     * <ol>
     *   <li>{@code src/test/resources/golden/cousr00c/expected/usrsec.txt}
     *       &mdash; the pre-browse {@code USRSEC} dataset with the
     *       80-byte {@code SEC-USER-DATA} record layout per
     *       {@code app/cpy/CSUSR01Y.cpy:L17-L23}. Browse access via
     *       {@code STARTBR} (seeded from {@code USRIDIN} or
     *       {@code LOW-VALUES}) +
     *       {@code READNEXT}/{@code READPREV} (up to ten per page) +
     *       {@code ENDBR} in paragraphs {@code PROCESS-PAGE-FORWARD}
     *       and {@code PROCESS-PAGE-BACKWARD}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     *       constructor dependency injected into
     *       {@link com.blitzy.carddemo.application.user.CoUsr00C}. The
     *       file lives under {@code expected/} (not {@code input/})
     *       because it is shared scaffolding for both COBOL capture
     *       and Java translation.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object)} immutable to preserve
     * deterministic ordering. Because COUSR00C is strictly read-only
     * (browse verbs only; no {@code WRITE}, {@code REWRITE},
     * {@code DELETE}, or {@code SYNCPOINT}), the fixture MUST be
     * byte-identical to its pre-test state after the run; the
     * harness's {@code runProgram(...)} hook copies the input into a
     * temp directory before the run so any inadvertent in-place
     * mutation does NOT pollute the source-controlled file.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(resolveExpectedOutputPath(PROGRAM_DIR, USRSEC_TXT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for COUSR00C.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure
     * message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J/{@code DISPLAY} trace. The COBOL source has limited
     *       {@code DISPLAY} verbs in COUSR00C so the captured baseline
     *       may be sparse or empty; whatever is captured must be
     *       byte-identical. Per AAP &sect;0.7.2 contains NO password
     *       bytes &mdash; the SLF4J logger inside
     *       {@link com.blitzy.carddemo.application.user.CoUsr00C}
     *       NEVER receives the plaintext password as an argument to
     *       any log call (only the user-id, when at all, appears in
     *       log messages).</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoUsr00Output} screen states (one
     *       per submission in the scenario). Each frame carries up
     *       to ten row slots ({@code USRID01..USRID10},
     *       {@code FNAME01..FNAME10},
     *       {@code UTYPE01..UTYPE10}) per the {@code USER-REC OCCURS
     *       10 TIMES} group at {@code app/cbl/COUSR00C.cbl:L57-L64},
     *       plus the search-key {@code USRIDIN}, the standard header,
     *       and the {@code ERRMSG} status line. NO {@code PASSWD}
     *       field appears in any frame &mdash; the COUSR00 BMS map
     *       at {@code app/bms/COUSR00.bms} declares no such field.</li>
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
     * pending the COBOL COUSR00C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cousr00c/expected/} per the
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
     * running surefire with {@code -Dtest=CoUsr00CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdLiCGoldenTest}, {@link CoCrdSlCGoldenTest},
     * {@link CoCrdUpCGoldenTest}, {@link CoTrn00CGoldenTest},
     * {@link CoTrn01CGoldenTest}, {@link CoTrn02CGoldenTest},
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
        "Awaiting COBOL COUSR00C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 6 invariants must hold before "
            + "removing @Disabled): "
            + "(1) Admin-only access guard preserved (SEC-USR-TYPE='A'): "
            + "the empty-commarea path under the IF EIBCALEN = 0 guard "
            + "in app/cbl/COUSR00C.cbl redirects to COSGN00C when no "
            + "commarea is supplied, forcing operator authentication "
            + "before reaching the user-list screen; COUSR00C itself "
            + "never inspects the commarea's CDEMO-USER-TYPE field "
            + "because the access guard is enforced upstream by "
            + "COADM01C (the ONLY legitimate XCTL source for CU00). "
            + "The captured stdout.txt carries NO admin-type warning "
            + "bytes \u2014 the verification depends on the upstream "
            + "menu routing only. "
            + "(2) STARTBR/READNEXT/READPREV/ENDBR sequencing exact: "
            + "PF08 forward paging invokes PROCESS-PAGE-FORWARD "
            + "(STARTBR seeded from CDEMO-CU00-USRID-LAST or "
            + "LOW-VALUES on first entry, 10-count READNEXT loop, "
            + "ENDBR). PF07 backward paging invokes "
            + "PROCESS-PAGE-BACKWARD (STARTBR seeded from "
            + "CDEMO-CU00-USRID-FIRST, 10-count READPREV loop, "
            + "ENDBR). Browse cursors are always released by the "
            + "matching ENDBR; no cursor leakage. The Java "
            + "translation preserves these contracts through "
            + "UserSecurityRepository.startBrowse / readNext / "
            + "readPrev / endBrowse port methods. "
            + "(3) PF7 backward / PF8 forward navigation produces "
            + "verbatim boundary messages with trailing '...' "
            + "ellipsis preserved exactly: 'You are already at the "
            + "top of the page...' on PF7 at page 1 (per "
            + "WS-PAGE-NUM = 1 check); 'You have reached the bottom "
            + "of the page...' on PF8 when NEXT-PAGE-NO (the "
            + "CDEMO-CU00-NEXT-PAGE-FLG = 'N' condition at "
            + "app/cbl/COUSR00C.cbl:L72-L73). ANY normalization "
            + "(trimming ellipsis, collapsing whitespace, recasing) "
            + "breaks byte parity. PF03 XCTLs to COADM01C "
            + "(BACK_PROGRAM = ProgramRegistry.CO_ADM_01C) with the "
            + "PgmContext reset to ENTER so the admin menu treats "
            + "the return as a first-time entry. "
            + "(4) Row 'U' selection XCTLs to COUSR02C with selected "
            + "user-id: ENTER scans rows 1-10 for non-blank "
            + "USER-SEL. On first non-blank with selection 'U' (case "
            + "preserved exactly per app/cbl/COUSR00C.cbl), the "
            + "program XCTLs to COUSR02C (UPDATE_PROGRAM = "
            + "ProgramRegistry.CO_USR_02C) with "
            + "CDEMO-CU02-USR-SELECTED PIC X(08) populated from the "
            + "row's USER-ID; the auto-trigger flow on the receiving "
            + "side (CoUsr02C) produces a populated edit screen on "
            + "the first frame rather than an empty entry screen. "
            + "(5) Row 'D' selection XCTLs to COUSR03C with selected "
            + "user-id: ENTER scans rows 1-10 for non-blank "
            + "USER-SEL. On first non-blank with selection 'D' (case "
            + "preserved exactly), the program XCTLs to COUSR03C "
            + "(DELETE_PROGRAM = ProgramRegistry.CO_USR_03C) with "
            + "CDEMO-CU03-USR-SELECTED PIC X(08) populated from the "
            + "row's USER-ID; the auto-trigger flow on the receiving "
            + "side (CoUsr03C) produces a populated confirmation "
            + "screen on the first frame. Any selection character "
            + "other than 'U' or 'D' emits the verbatim 'Invalid "
            + "selection ...' message with trailing ellipsis "
            + "preserved exactly and re-renders the current page. "
            + "(6) Password column NEVER appears in stdout.txt or "
            + "bms_output.txt per AAP \u00a70.7.2: the COUSR00 BMS "
            + "map at app/bms/COUSR00.bms declares NO PASSWD field "
            + "\u2014 only USRIDIN, SEL0001..SEL0010, "
            + "USRID01..USRID10, FNAME01..FNAME10, "
            + "UTYPE01..UTYPE10, plus the standard header, banner, "
            + "and ERRMSG are present; the COBOL program at "
            + "app/cbl/COUSR00C.cbl:L191-L200 explicitly projects "
            + "only SEC-USR-ID, SEC-USR-FNAME, SEC-USR-LNAME, and "
            + "SEC-USR-TYPE from SEC-USER-DATA, NEVER reading "
            + "SEC-USR-PWD onto any screen or log surface. The "
            + "SLF4J logger inside CoUsr00C NEVER receives the "
            + "plaintext SEC-USR-PWD value as an argument to any "
            + "log.info(...) / log.warn(...) / log.error(...) call. "
            + "Any byte sequence from the usrsec.txt fixture's "
            + "plaintext password column (offset 48 of each 80-byte "
            + "record per app/cpy/CSUSR01Y.cpy:L21) appearing in "
            + "either stdout.txt or bms_output.txt breaks AAP "
            + "\u00a70.7.2 and blocks the PR unconditionally. The "
            + "usrsec.txt auxiliary fixture is used as a NEGATIVE "
            + "ASSERTION SOURCE: every plaintext password byte in "
            + "it must be ABSENT from the captured output bytes. "
            + "Read-only invariant: COUSR00C never modifies any "
            + "record (no REWRITE, no WRITE, no DELETE, no "
            + "SYNCPOINT; only STARTBR / READNEXT / READPREV / "
            + "ENDBR browse verbs), so the usrsec.txt auxiliary "
            + "fixture MUST be byte-identical to its pre-test state "
            + "after the run; any divergence indicates a regression."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
