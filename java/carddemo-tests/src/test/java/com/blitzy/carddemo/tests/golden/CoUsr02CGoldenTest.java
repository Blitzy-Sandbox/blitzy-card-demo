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
 * Byte-for-byte golden-record parity test for {@code COUSR02C}
 * (User Update Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COUSR02C.cbl} &mdash; the
 * {@code PROGRAM-ID COUSR02C} ({@code app/cbl/COUSR02C.cbl:L23}) online-CICS
 * program backing transaction {@code CU02} ({@code WS-TRANID PIC X(04) VALUE
 * 'CU02'} at {@code app/cbl/COUSR02C.cbl:L37}, mapset {@code COUSR02} via
 * {@code COPY COUSR02.} at {@code app/cbl/COUSR02C.cbl:L60}). The program is
 * the <strong>admin-only user-update</strong> transaction: it accepts an
 * 8-character {@code USRIDIN} key from the BMS screen, performs a
 * two-phase {@code READ}+{@code REWRITE} flow against the {@code USRSEC}
 * VSAM KSDS ({@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at
 * {@code app/cbl/COUSR02C.cbl:L39}). Pass 1 ({@code ENTER}) projects the
 * persisted {@code SEC-USER-DATA} fields onto the BMS map for in-place
 * edit including the plaintext password ({@code SEC-USR-PWD} &rarr;
 * {@code PASSWDI} at {@code app/cbl/COUSR02C.cbl:L169}); Pass 2
 * ({@code PF5} or {@code PF3}) re-reads with the {@code UPDATE} lock,
 * compares each edited field to its persisted counterpart, and on
 * any change executes {@code EXEC CICS REWRITE} to persist the new
 * 80-byte {@code SEC-USER-DATA} record back to {@code USRSEC}. It is
 * the natural drill-through target from the sibling {@code COUSR00C}
 * user-list program via row-selection {@code 'U'} (which XCTLs here
 * with {@code CDEMO-CU02-USR-SELECTED} populated, auto-triggering
 * the lookup on first-pass entry per
 * {@code app/cbl/COUSR02C.cbl:L99-L104}).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.user.CoUsr02C}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COUSR02C is translated into the
 * {@code application/user/} subpackage co-located with its sibling
 * translations {@code CoUsr00C} (user list, transaction {@code CU00}),
 * {@code CoUsr01C} (user add, transaction {@code CU01}), and
 * {@code CoUsr03C} (user delete, transaction {@code CU03}).</p>
 *
 * <h2>Plaintext Password Preservation (AAP &sect;0.1.3)</h2>
 *
 * <p>This is the <strong>defining behavioural invariant</strong> of the
 * COUSR02C parity test. The COBOL program at
 * {@code app/cbl/COUSR02C.cbl:L227-L230} writes {@code PASSWDI OF
 * COUSR2AI} verbatim into {@code SEC-USR-PWD PIC X(08)}
 * ({@code app/cpy/CSUSR01Y.cpy:L21}) without any hashing, salting, or
 * transformation; the {@code REWRITE} at L362 persists the 80-byte
 * {@code SEC-USER-DATA} record back to {@code USRSEC} with the password
 * column carrying the operator's plaintext keystrokes. Per AAP
 * &sect;0.1.3 the Java translation preserves this behaviour exactly:
 * {@code SEC-USR-PWD} bytes are written verbatim to the USRSEC fixture,
 * NO password hashing (BCrypt, Argon2, PBKDF2) is introduced, and the
 * decision to add hashing is explicitly OUT OF SCOPE for this refactor
 * (flagged in {@code java/MIGRATION_NOTES.md} for a follow-up effort).
 * The {@code usrsec_after.txt} expected fixture carries the
 * post-{@code REWRITE} 80-byte record layout
 * ({@code SEC-USR-ID PIC X(08)} + {@code SEC-USR-FNAME PIC X(20)} +
 * {@code SEC-USR-LNAME PIC X(20)} + {@code SEC-USR-PWD PIC X(08)} +
 * {@code SEC-USR-TYPE PIC X(01)} + {@code SEC-USR-FILLER PIC X(23)} per
 * {@code app/cpy/CSUSR01Y.cpy:L17-L23}) with the updated plaintext
 * password bytes intact at offset 48; any byte-level mismatch on this
 * column breaks AAP &sect;0.1.3 and blocks the PR.</p>
 *
 * <h2>NO Password in Logs (AAP &sect;0.7.2)</h2>
 *
 * <p>The complement to the plaintext-storage invariant: the password
 * value MUST NEVER appear in the {@code stdout.txt} capture. Per AAP
 * &sect;0.1.3 storage and logging are <em>different surfaces</em>
 * &mdash; the password is preserved verbatim in {@code SEC-USR-PWD}
 * within {@code USRSEC} (the canonical credential store), but the SLF4J
 * logger inside {@link com.blitzy.carddemo.application.user.CoUsr02C}
 * NEVER receives the plaintext value as an argument to any
 * {@code log.info(...)} / {@code log.warn(...)} / {@code log.error(...)}
 * call (only the user-id appears in log messages). The
 * {@code bms_output.txt} capture serializes the BMS screen state
 * including the {@code PASSWD} field at BMS position (13, 16) which
 * carries the {@code DRK} (dark) attribute per
 * {@code app/bms/COUSR02.bms:L130-L134} &mdash; the 3270 terminal
 * renderer hides the password from the operator's eye, but the bytes
 * ARE present in the serialized screen buffer (this is COBOL's
 * existing behaviour, preserved verbatim under AAP &sect;0.7.1
 * "preserve current behavior … with minimal risk"). For
 * {@code stdout.txt}, any byte sequence resembling a plaintext
 * password breaks AAP &sect;0.7.2 and blocks the PR unconditionally.</p>
 *
 * <h2>READ + REWRITE Two-Phase Pattern</h2>
 *
 * <p>The COBOL paragraphs at {@code app/cbl/COUSR02C.cbl:L320-L353}
 * ({@code READ-USER-SEC-FILE}) and L358-L390
 * ({@code UPDATE-USER-SEC-FILE}) realize the canonical VSAM update
 * preamble: {@code EXEC CICS READ ... UPDATE} acquires the exclusive
 * record lock and validates existence, then on Pass 2 (PF5 or PF3)
 * the in-memory {@code SEC-USER-DATA} block is mutated field-by-field
 * (only when the screen value differs from the persisted value per
 * the four {@code IF ... NOT = SEC-USR-*} blocks at L219-L234), and
 * {@code EXEC CICS REWRITE FROM(SEC-USER-DATA)} at L360-L366 persists
 * the mutated 80-byte record back to {@code USRSEC}. The
 * {@code WS-USR-MODIFIED} flag at L45-L47 is the
 * <strong>distinguishing field for COUSR02C</strong> (absent in
 * COUSR03C): only when at least one of the four editable fields
 * ({@code FNAMEI}/{@code LNAMEI}/{@code PASSWDI}/{@code USRTYPEI})
 * differs from the persisted {@code SEC-USR-*} value does the
 * {@code REWRITE} actually execute; otherwise the program emits
 * the verbatim message {@code 'Please modify to update ...'} at L239
 * with {@code DFHRED} colour and returns to the screen without
 * modifying {@code USRSEC}. The Java translation collapses these two
 * CICS verbs into two
 * {@link com.blitzy.carddemo.domain.port.UserSecurityRepository} port
 * calls (find + update) preserving the same observable before/after
 * state on {@code usrsec.txt}.</p>
 *
 * <h2>Auto-Trigger Flow from COUSR00C (AAP &sect;0.7.1)</h2>
 *
 * <p>The COBOL block at {@code app/cbl/COUSR02C.cbl:L99-L104} implements
 * a one-shot auto-fetch when {@code COUSR02C} is entered with
 * {@code CDEMO-PGM-REENTER = FALSE} (first invocation) AND
 * {@code CDEMO-CU02-USR-SELECTED} populated to a non-blank,
 * non-LOW-VALUES value (the user id selected by the operator on the
 * preceding {@code COUSR00C} list screen via the row-selection
 * {@code 'U'} marker). The conditional copies the selected id to
 * {@code USRIDINI OF COUSR2AI} and immediately performs
 * {@code PROCESS-ENTER-KEY} (line 103) before SEND MAP &mdash;
 * producing a populated edit screen (with first name, last name,
 * plaintext password, and user type prefilled) on the first frame
 * rather than an empty entry screen. The Java translation preserves
 * this behavior by checking the input record's
 * {@code cdemoCu02UsrSelected} field for non-blank on first-pass entry
 * and auto-triggering the same code path as the explicit ENTER AID
 * key. The {@code input_scenario.txt} fixture exercises BOTH the
 * manual (operator typing the user-id) and auto-trigger (XCTL from
 * COUSR00C row 'U' selection) paths.</p>
 *
 * <h2>Verbatim Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>The following message literals MUST be preserved byte-for-byte by
 * the Java translation and appear in the captured fixtures. Each
 * carries the exact trailing {@code ...} ellipsis from the COBOL
 * source; ANY normalization (trimming the ellipsis, collapsing
 * whitespace, recasing) breaks byte parity and blocks the PR:</p>
 * <ul>
 *   <li>{@code 'User ID can NOT be empty...'} at
 *       {@code app/cbl/COUSR02C.cbl:L148,L182} (emitted by paragraphs
 *       {@code PROCESS-ENTER-KEY} AND {@code UPDATE-USER-INFO}).</li>
 *   <li>{@code 'First Name can NOT be empty...'} at L188.</li>
 *   <li>{@code 'Last Name can NOT be empty...'} at L194.</li>
 *   <li>{@code 'Password can NOT be empty...'} at L200.</li>
 *   <li>{@code 'User Type can NOT be empty...'} at L206.</li>
 *   <li>{@code 'User ID NOT found...'} at L342,L379 (emitted by
 *       paragraphs {@code READ-USER-SEC-FILE} AND
 *       {@code UPDATE-USER-SEC-FILE} on {@code DFHRESP(NOTFND)}).</li>
 *   <li>{@code 'Unable to lookup User...'} at L349.</li>
 *   <li>{@code 'Please modify to update ...'} at L239 (no-change
 *       short-circuit; note SINGLE space before ellipsis preserved
 *       verbatim).</li>
 *   <li>{@code 'Unable to Update User...'} at L386.</li>
 *   <li>{@code 'User <id> has been updated ...'} at L372-L374 &mdash;
 *       success message constructed via {@code STRING 'User '
 *       DELIMITED BY SIZE SEC-USR-ID DELIMITED BY SPACE
 *       ' has been updated ...' DELIMITED BY SIZE INTO WS-MESSAGE};
 *       the {@code DELIMITED BY SPACE} on {@code SEC-USR-ID} means
 *       only the first whitespace-delimited token of the user-id
 *       appears in the success message.</li>
 *   <li>{@code CCDA-MSG-INVALID-KEY} ({@code 'Invalid key pressed.
 *       Please see below...'} from {@code app/cpy/CSMSG01Y.cpy})
 *       emitted by the {@code WHEN OTHER} branch of the EIBAID
 *       {@code EVALUATE} at L127-L130.</li>
 * </ul>
 *
 * <h2>AID-Key Dispatch (Enter, PF3, PF4, PF5, PF12)</h2>
 *
 * <p>The AID-key dispatch in {@code app/cbl/COUSR02C.cbl:L108-L131}
 * accepts five AID values; any other AID falls through to
 * {@code CCDA-MSG-INVALID-KEY}:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; {@code PROCESS-ENTER-KEY} (Pass 1
 *       READ + populate edit fields including plaintext PASSWD).</li>
 *   <li><strong>PF3 (Save&amp;Exit)</strong> &mdash; PERFORM
 *       {@code UPDATE-USER-INFO} then XCTL to
 *       {@code CDEMO-FROM-PROGRAM} (defaulting to {@code COADM01C}
 *       when blank per L112-L118).</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; PERFORM
 *       {@code CLEAR-CURRENT-SCREEN} (re-initialize all fields and
 *       re-send empty entry screen).</li>
 *   <li><strong>PF5 (Save only)</strong> &mdash; PERFORM
 *       {@code UPDATE-USER-INFO} (Pass 2 actual REWRITE; stays on
 *       screen).</li>
 *   <li><strong>PF12 (Cancel)</strong> &mdash; XCTL to
 *       {@code COADM01C} unconditionally (ignoring
 *       {@code CDEMO-FROM-PROGRAM}, deliberately distinct from
 *       PF3).</li>
 * </ul>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cousr02c/expected/} encodes the
 * full state space of the user-update transaction: initial empty
 * entry (signon redirect), Pass 1 valid lookup (READ + populate
 * edit fields), auto-trigger from COUSR00C row 'U' selection,
 * user-id NOT found, the five empty-field validation paths
 * (USRIDIN, FNAME, LNAME, PASSWD, USRTYPE), Pass 2 valid REWRITE
 * (with mutation of one or more editable fields), no-modification
 * short-circuit ({@code 'Please modify to update ...'}), invalid
 * SEC-USER-TYPE rejection (per the
 * {@link com.blitzy.carddemo.application.user.CoUsr02C} translation's
 * added validation per AAP &sect;0.4.1 "validates SEC-USER-TYPE
 * &isin; {{@code 'A'},{@code 'U'}}"), PF3 Save&amp;Exit, PF4 Clear,
 * PF5 Save, PF12 Cancel, and invalid key. The harness compares the
 * three captured outputs ({@code stdout.txt}, {@code bms_output.txt},
 * post-REWRITE {@code usrsec.txt} versus {@code usrsec_after.txt})
 * byte-for-byte to the captured COBOL baseline.</p>
 *
 * <h2>Auxiliary Input Fixture</h2>
 *
 * <p>The single auxiliary fixture
 * {@code src/test/resources/golden/cousr02c/expected/usrsec.txt} backs
 * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
 * port that COUSR02C reads via {@code EXEC CICS READ} and modifies via
 * {@code EXEC CICS REWRITE}. The fixture lives under {@code expected/}
 * (not {@code input/}) because it is test-owned scaffolding shared by
 * BOTH the COBOL CICS capture run AND the Java translation under
 * test; both sides must agree on the user-id vocabulary for
 * byte-for-byte parity to be meaningful. The harness's
 * {@code runProgram(...)} hook copies the fixture into a temp
 * directory before the run so the source-controlled file is NEVER
 * mutated in place; the post-REWRITE state is captured separately
 * into the run's temp file and compared against
 * {@code usrsec_after.txt} for byte-for-byte parity.</p>
 *
 * <h2>Scaffolding state</h2>
 *
 * <p>Per AAP &sect;0.6.11 ("Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available; the harness skeleton, base class, and per-program test
 * classes are created unconditionally"), the {@link #byteForByteParity()}
 * override below is annotated {@code @Disabled} with an 8-point
 * verification reason citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * under {@code src/test/resources/golden/cousr02c/expected/}.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.user.CoUsr02C
 * @since 25
 */
@DisplayName("COUSR02C \u2014 User Update Golden-Record Parity (plaintext password preserved)")
public class CoUsr02CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COUSR02C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cousr02c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cousr02c/expected/}. Encodes the
     * multi-submission pseudo-conversation sequence (initial empty
     * entry, Pass 1 valid lookup, auto-trigger from COUSR00C row 'U',
     * user-id-not-found, five empty-field validations, Pass 2 valid
     * REWRITE, no-modification short-circuit, invalid SEC-USER-TYPE,
     * PF3 save&amp;exit, PF4 clear, PF5 save, PF12 cancel, invalid key)
     * consumed by the harness orchestrator to drive the CoUsr02C
     * online-CICS state machine through the full state space of the
     * user-update transaction.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cousr02c/expected/}. Records the
     * SLF4J/DISPLAY emissions including the verbatim messages from AAP
     * &sect;0.7.1 (with the trailing {@code ...} ellipsis preserved
     * exactly). The capture also includes any
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} emissions
     * on the error branches of READ-USER-SEC-FILE (L347) and
     * UPDATE-USER-SEC-FILE (L384). Per AAP &sect;0.7.2 the capture
     * contains NO password bytes whatsoever &mdash; the SLF4J logger
     * NEVER receives the plaintext {@code SEC-USR-PWD} value as an
     * argument to any log call.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cousr02c/expected/}. Records the
     * serialized {@code CoUsr02Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * the five user-facing input fields {@code USRIDIN} (8 chars, pos
     * 6/21), {@code FNAME} (20 chars, pos 11/18), {@code LNAME} (20
     * chars, pos 11/56), {@code PASSWD} (8 chars, pos 13/16, DRK
     * attribute), {@code USRTYPE} (1 char, pos 15/17) plus the standard
     * header line ({@code TRNNAME}, {@code TITLE01}, {@code CURDATE},
     * {@code PGMNAME}, {@code TITLE02}, {@code CURTIME}), the
     * {@code "Update User"} banner at line 4 pos 35, and the
     * {@code ERRMSG} status line. The PASSWD field bytes ARE present in
     * the serialized buffer (DRK only hides them from the terminal
     * renderer per COBOL's existing behaviour preserved under AAP
     * &sect;0.7.1).
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the auxiliary input fixture under
     * {@code src/test/resources/golden/cousr02c/expected/} that backs
     * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port. Contains the pre-REWRITE state of the {@code USRSEC}
     * dataset: one or more 80-byte {@code SEC-USER-DATA} records
     * ({@code SEC-USR-ID PIC X(08)} +
     * {@code SEC-USR-FNAME PIC X(20)} +
     * {@code SEC-USR-LNAME PIC X(20)} +
     * {@code SEC-USR-PWD PIC X(08)} +
     * {@code SEC-USR-TYPE PIC X(01)} +
     * {@code SEC-USR-FILLER PIC X(23)} = 80 bytes per
     * {@code app/cpy/CSUSR01Y.cpy:L17-L23}). The fixture lives under
     * {@code expected/} (not {@code input/}) because it is test-owned
     * scaffolding shared between COBOL capture and Java translation.
     * Read-only at the file-system level &mdash; the harness's
     * {@code runProgram(...)} hook copies it into a temp directory
     * before the test run so the source-controlled file is never
     * mutated in place.
     */
    private static final String USRSEC_TXT = "usrsec.txt";

    /**
     * Name of the expected post-REWRITE USRSEC fixture under
     * {@code src/test/resources/golden/cousr02c/expected/}. The harness
     * compares the actual {@code usrsec.txt} file written by the Java
     * translation after a successful Pass 2 REWRITE to this expected
     * baseline byte-for-byte. Records the post-REWRITE state of the
     * {@code USRSEC} dataset: same record layout as {@code usrsec.txt}
     * but with the targeted record's mutated columns (any subset of
     * {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, {@code SEC-USR-PWD},
     * {@code SEC-USR-TYPE}) reflecting the operator's edits. Per AAP
     * &sect;0.1.3 the {@code SEC-USR-PWD} bytes at offset 48 carry the
     * <strong>plaintext password</strong> verbatim &mdash; NO hashing
     * is applied; the column is rewritten with the operator's
     * keystrokes byte-for-byte. Records other than the targeted one
     * remain byte-identical to their pre-REWRITE form.
     */
    private static final String USRSEC_AFTER_TXT = "usrsec_after.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.user.CoUsr02C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the JUnit
     * Jupiter API annotations ({@link DisplayName}, {@link Disabled},
     * {@link Test}). The fully-qualified class literal compiles cleanly
     * because {@code carddemo-tests} declares a test-scope dependency
     * on {@code carddemo-application} (transitively via
     * {@code carddemo-app}) per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.user.CoUsr02C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cousr02c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the multi-
     * submission CICS pseudo-conversation that drives the CoUsr02C
     * online-state machine through the full state space (initial
     * empty entry, Pass 1 valid lookup, auto-trigger from COUSR00C,
     * user-id-not-found, five empty-field validations, Pass 2 valid
     * REWRITE, no-modification short-circuit, invalid SEC-USER-TYPE,
     * PF3 save&amp;exit, PF4 clear, PF5 save, PF12 cancel, invalid
     * key); it lives alongside the expected outputs under the per-
     * program {@code cousr02c/} subtree because it is a harness-
     * internal fixture (not part of the immutable {@code app/data/ASCII/}
     * dataset).</p>
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
     * {@code src/test/resources/golden/cousr02c/expected/stdout.txt},
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
     * fixture paths reflecting the COUSR02C collaborator surface:</p>
     * <ol>
     *   <li>{@code src/test/resources/golden/cousr02c/expected/usrsec.txt}
     *       &mdash; pre-REWRITE USRSEC baseline with the 80-byte
     *       {@code SEC-USER-DATA} record layout per
     *       {@code app/cpy/CSUSR01Y.cpy}. READ random access by
     *       8-character primary key {@code SEC-USR-ID PIC X(08)} in
     *       paragraph {@code READ-USER-SEC-FILE} at
     *       {@code app/cbl/COUSR02C.cbl:L320-L353}; REWRITE in paragraph
     *       {@code UPDATE-USER-SEC-FILE} at
     *       {@code app/cbl/COUSR02C.cbl:L358-L390}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     *       constructor dependency injected into
     *       {@link com.blitzy.carddemo.application.user.CoUsr02C}. The
     *       file lives under {@code expected/} (not {@code input/})
     *       because it is shared scaffolding for both COBOL capture
     *       and Java translation.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object)} immutable to preserve
     * deterministic ordering. Because COUSR02C MODIFIES the USRSEC
     * dataset on the PF5/PF3 REWRITE path, the harness's
     * {@code runProgram(...)} hook copies the fixture into a temp
     * directory before the run so the source-controlled
     * {@code usrsec.txt} is NEVER mutated in place; the post-REWRITE
     * state is captured separately into the run's temp file and
     * compared against {@code usrsec_after.txt} for byte-for-byte
     * parity.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(resolveExpectedOutputPath(PROGRAM_DIR, USRSEC_TXT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the three byte-for-byte parity targets for COUSR02C.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J/DISPLAY trace including the verbatim messages from
     *       AAP &sect;0.7.1 (with the trailing {@code ...} ellipsis
     *       preserved exactly). Per AAP &sect;0.7.2 contains NO
     *       password bytes &mdash; the SLF4J logger inside
     *       {@link com.blitzy.carddemo.application.user.CoUsr02C}
     *       NEVER receives the plaintext password value as an argument
     *       to any log call (only the user-id appears in log
     *       messages).</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoUsr02Output} screen states (one
     *       per submission in the scenario). Each frame carries the
     *       five user-facing fields ({@code USRIDIN}, {@code FNAME},
     *       {@code LNAME}, {@code PASSWD} with DRK attribute,
     *       {@code USRTYPE}) plus the standard header. The PASSWD
     *       column bytes are present in the serialized buffer
     *       (preserved COBOL behaviour per AAP &sect;0.7.1).</li>
     *   <li>{@link #USRSEC_TXT} ({@code usrsec.txt}) &mdash; the
     *       post-REWRITE state of the USRSEC dataset, compared
     *       against the {@link #USRSEC_AFTER_TXT} expected baseline.
     *       After a successful PF5/PF3 REWRITE the targeted record's
     *       80 bytes carry the mutated column values including the
     *       <strong>plaintext password</strong> bytes at offset 48 per
     *       AAP &sect;0.1.3 (NO hashing applied; verbatim keystrokes).
     *       The {@link ExpectedOutput#name()} {@code "usrsec.txt"}
     *       matches the file name written by the
     *       {@code FileUserSecurityRepository} adapter so the
     *       base-class
     *       {@link GoldenRecordTest#runProgram(Class, Path, List)}
     *       hook can look it up in the returned actual-output map.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object, Object, Object)}
     * immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT)),
            new ExpectedOutput(USRSEC_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, USRSEC_AFTER_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL COUSR02C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cousr02c/expected/} per the
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
     * running surefire with {@code -Dtest=CoUsr02CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton.</p>
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
        "Awaiting COBOL COUSR02C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 8 invariants must hold before "
            + "removing @Disabled): "
            + "(1) Admin-only access guard preserved: the empty-commarea "
            + "path at app/cbl/COUSR02C.cbl:L90-L92 redirects to "
            + "COSGN00C when EIBCALEN = 0, forcing operator authentication "
            + "before reaching the update screen; COUSR02C itself never "
            + "inspects the commarea's UserType because the access guard "
            + "is enforced upstream by COADM01C (the ONLY legitimate XCTL "
            + "source for CU02). The captured stdout.txt carries NO "
            + "admin-type warning bytes \u2014 the verification depends "
            + "on the upstream menu routing only. "
            + "(2) Valid update by entered user-id (READ + REWRITE): the "
            + "two-phase pattern at app/cbl/COUSR02C.cbl:L320-L353 "
            + "(READ-USER-SEC-FILE with the UPDATE preamble) and "
            + "L358-L390 (UPDATE-USER-SEC-FILE) collapses into two "
            + "UserSecurityRepository port calls (find then update) "
            + "temporally separated by the operator's PF5/PF3 keystroke. "
            + "The verbatim 'User <id> has been updated ...' success "
            + "message at COBOL L372-L374 (constructed via STRING with "
            + "DELIMITED BY SPACE on SEC-USR-ID so only the first "
            + "whitespace-delimited token of the id appears) fires on "
            + "the post-REWRITE frame. "
            + "(3) Auto-trigger flow from COUSR00C row 'U' selection: "
            + "when commarea carries non-blank "
            + "CDEMO-CU02-USR-SELECTED, the COBOL block at "
            + "app/cbl/COUSR02C.cbl:L99-L104 (under the IF NOT "
            + "CDEMO-PGM-REENTER first-pass branch) copies to "
            + "USRIDINI and immediately performs PROCESS-ENTER-KEY, "
            + "producing a populated edit screen on the first frame "
            + "rather than an empty entry screen. The Java "
            + "translation preserves this auto-trigger by checking "
            + "CoUsr02Input.cdemoCu02UsrSelected() for non-blank on "
            + "first-pass entry. "
            + "(4) User-id NOT found produces verbatim error: when "
            + "READ-USER-SEC-FILE returns DFHRESP(NOTFND) at "
            + "app/cbl/COUSR02C.cbl:L340-L345 OR when "
            + "UPDATE-USER-SEC-FILE returns DFHRESP(NOTFND) at "
            + "L377-L382, the verbatim 'User ID NOT found...' message "
            + "fires with trailing '...' ellipsis preserved exactly; "
            + "cursor returns to USRIDINI via MOVE -1 TO USRIDINL. "
            + "(5) Invalid SEC-USER-TYPE rejected: per the "
            + "CoUsr02C Java translation's added validation per AAP "
            + "\u00a70.4.1 ('validates SEC-USER-TYPE \u2208 {A, U}'), "
            + "an USRTYPE value other than 'A' (Admin) or 'U' (User) "
            + "produces the verbatim 'User Type must be 'A' (Admin) "
            + "or 'U' (User)' message. This rule is enforced before "
            + "the REWRITE so an invalid type can never reach USRSEC. "
            + "(6) Blank required-fields rejected: the five empty-"
            + "field validations at app/cbl/COUSR02C.cbl:L179-L213 "
            + "short-circuit in the order USRIDIN, FNAME, LNAME, "
            + "PASSWD, USRTYPE; only the FIRST empty field triggers "
            + "an error message. The verbatim 'User ID can NOT be "
            + "empty...', 'First Name can NOT be empty...', 'Last "
            + "Name can NOT be empty...', 'Password can NOT be "
            + "empty...', 'User Type can NOT be empty...' messages "
            + "must appear byte-for-byte; mixed-case 'can NOT' "
            + "preserved verbatim. ANY normalization breaks parity. "
            + "(7) Password update preserves plaintext bytes per AAP "
            + "\u00a70.1.3: COBOL L227-L230 writes PASSWDI verbatim "
            + "into SEC-USR-PWD without any hashing; the REWRITE at "
            + "L362 persists the 80-byte SEC-USER-DATA record with the "
            + "plaintext password column at offset 48 per "
            + "app/cpy/CSUSR01Y.cpy:L21. The Java FileUserSecurityRepo"
            + "sitory adapter writes the plaintext bytes verbatim to "
            + "usrsec.txt; NO BCrypt, Argon2, or PBKDF2 transformation "
            + "is applied. The usrsec_after.txt expected fixture "
            + "carries the updated plaintext password bytes at offset "
            + "48; any byte-level mismatch on this column breaks AAP "
            + "\u00a70.1.3 and blocks the PR. "
            + "(8) Password NEVER appears in stdout.txt per AAP "
            + "\u00a70.7.2: the SLF4J logger inside CoUsr02C NEVER "
            + "receives the plaintext SEC-USR-PWD value as an "
            + "argument to any log.info(...) / log.warn(...) / "
            + "log.error(...) call \u2014 only the user-id appears "
            + "in log messages. The captured stdout.txt contains NO "
            + "password bytes; any byte sequence resembling a "
            + "plaintext password breaks AAP \u00a70.7.2 and blocks "
            + "the PR unconditionally. (Note: bms_output.txt DOES "
            + "carry the PASSWD field bytes at BMS position (13,16) "
            + "because COUSR02 BMS map's DRK attribute hides the "
            + "field from the terminal renderer but the bytes are "
            + "present in the serialized screen buffer per COBOL's "
            + "existing behaviour preserved under AAP \u00a70.7.1 "
            + "\u2014 storage and logging are distinct surfaces per "
            + "AAP \u00a70.1.3.) "
            + "Additionally: the no-modification short-circuit at "
            + "COBOL L236-L243 fires 'Please modify to update ...' "
            + "(SINGLE space before ellipsis preserved verbatim) in "
            + "DFHRED colour when none of the four editable fields "
            + "differs from the persisted SEC-USR-* value, returning "
            + "to the screen without REWRITE. PF3 (Save&Exit) and "
            + "PF12 (Cancel) XCTL targets differ deliberately: PF3 "
            + "honours CDEMO-FROM-PROGRAM defaulting to COADM01C "
            + "when blank (COBOL L112-L118); PF12 hard-codes "
            + "COADM01C unconditionally (COBOL L124-L126)."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
