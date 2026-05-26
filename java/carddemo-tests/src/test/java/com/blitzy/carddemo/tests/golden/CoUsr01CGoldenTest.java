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
 * Byte-for-byte golden-record parity test for {@code COUSR01C}
 * (User Add Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COUSR01C.cbl} &mdash; the
 * {@code PROGRAM-ID COUSR01C} ({@code app/cbl/COUSR01C.cbl:L23}) online-CICS
 * program backing transaction {@code CU01} ({@code WS-TRANID PIC X(04) VALUE
 * 'CU01'} at {@code app/cbl/COUSR01C.cbl:L37}, mapset {@code COUSR01} via
 * {@code COPY COUSR01.} at {@code app/cbl/COUSR01C.cbl:L48}). The program is
 * the <strong>admin-only user-add</strong> transaction: it accepts a
 * five-field BMS payload (first name, last name, 8-character user id, 8-
 * character plaintext password, 1-character user type) from the
 * {@code COUSR1A} map and, after non-blank validation in the fixed COBOL
 * order, performs a single {@code EXEC CICS WRITE} against the
 * {@code USRSEC} VSAM KSDS ({@code WS-USRSEC-FILE PIC X(08) VALUE
 * 'USRSEC  '} at {@code app/cbl/COUSR01C.cbl:L39}). On a duplicate primary
 * key the COBOL {@code EVALUATE WS-RESP-CD} block at
 * {@code app/cbl/COUSR01C.cbl:L260-L266} catches both
 * {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)} and rerenders the
 * screen with the verbatim {@code 'User ID already exist...'} message
 * (note: the misspelling &quot;exist&quot; instead of &quot;exists&quot;
 * is preserved verbatim from the COBOL source per AAP &sect;0.7.1).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.user.CoUsr01C}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COUSR01C is translated into the
 * {@code application/user/} subpackage co-located with its sibling
 * translations {@code CoUsr00C} (user list, transaction {@code CU00}),
 * {@code CoUsr02C} (user update, transaction {@code CU02}), and
 * {@code CoUsr03C} (user delete, transaction {@code CU03}). The Java
 * translation has a 2-argument constructor {@code CoUsr01C(
 * UserSecurityRepository userSecurityRepository, ProgramRegistry
 * programRegistry)} matching the COBOL collaborator surface (one
 * repository port for the {@code USRSEC} KSDS plus the dynamic-CALL
 * routing facility carried as a collaborator for {@code EXEC CICS XCTL}
 * dispatch to {@code COADM01C} on PF3 and to {@code COSGN00C} on the
 * empty-commarea path). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against file-backed
 * adapters wired to the auxiliary fixture returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>Plaintext Password Preservation (AAP &sect;0.1.3)</h2>
 *
 * <p>This is the <strong>defining behavioural invariant</strong> of the
 * COUSR01C parity test. The COBOL program at
 * {@code app/cbl/COUSR01C.cbl:L157} writes {@code PASSWDI OF COUSR1AI}
 * verbatim into {@code SEC-USR-PWD PIC X(08)}
 * ({@code app/cpy/CSUSR01Y.cpy:L21}) without any hashing, salting, or
 * transformation; the subsequent {@code WRITE} at
 * {@code app/cbl/COUSR01C.cbl:L240-L248} persists the 80-byte
 * {@code SEC-USER-DATA} record to {@code USRSEC} with the password
 * column carrying the operator's plaintext keystrokes. Per AAP
 * &sect;0.1.3 the Java translation preserves this behaviour exactly:
 * {@code SEC-USR-PWD} bytes are written verbatim to the USRSEC fixture,
 * NO password hashing (BCrypt, Argon2, PBKDF2) is introduced, and the
 * decision to add hashing is explicitly OUT OF SCOPE for this refactor
 * (flagged in {@code java/MIGRATION_NOTES.md} for a follow-up effort).
 * The {@code usrsec_after.txt} expected fixture carries the
 * post-{@code WRITE} 80-byte record layout
 * ({@code SEC-USR-ID PIC X(08)} + {@code SEC-USR-FNAME PIC X(20)} +
 * {@code SEC-USR-LNAME PIC X(20)} + {@code SEC-USR-PWD PIC X(08)} +
 * {@code SEC-USR-TYPE PIC X(01)} + {@code SEC-USR-FILLER PIC X(23)} per
 * {@code app/cpy/CSUSR01Y.cpy:L17-L23}) with the newly written plaintext
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
 * logger inside {@link com.blitzy.carddemo.application.user.CoUsr01C}
 * NEVER receives the plaintext value as an argument to any
 * {@code log.info(...)} / {@code log.warn(...)} / {@code log.error(...)}
 * call (only the user-id appears in log messages). The
 * {@code bms_output.txt} capture serializes the BMS screen state
 * including the {@code PASSWD} field at BMS position (11, 55) which
 * carries the {@code DRK} (dark) attribute per
 * {@code app/bms/COUSR01.bms:L126-L130} &mdash; the 3270 terminal
 * renderer hides the password from the operator's eye, but the bytes
 * ARE present in the serialized screen buffer (this is COBOL's
 * existing behaviour, preserved verbatim under AAP &sect;0.7.1
 * "preserve current behavior &hellip; with minimal risk"). For
 * {@code stdout.txt}, any byte sequence resembling a plaintext
 * password breaks AAP &sect;0.7.2 and blocks the PR unconditionally.</p>
 *
 * <h2>Single WRITE Pattern (Distinct from COUSR02C / COUSR03C)</h2>
 *
 * <p>Unlike its sibling {@code COUSR02C} (which uses a two-phase
 * {@code READ}+{@code REWRITE} pattern) and {@code COUSR03C} (which uses
 * a two-phase {@code READ}+{@code DELETE} pattern), {@code COUSR01C}
 * issues a <strong>single</strong> {@code EXEC CICS WRITE} verb at
 * {@code app/cbl/COUSR01C.cbl:L240-L248}. There is NO preceding
 * {@code READ ... UPDATE} preamble &mdash; VSAM's primary-key uniqueness
 * constraint detects collisions at the WRITE moment via the
 * {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} response codes.
 * The Java translation collapses this single CICS verb into one
 * {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
 * port call ({@code insert(SecUserData)}); the file-backed adapter
 * checks for the user-id's prior existence before appending the new
 * 80-byte record (preserving the COBOL DUPKEY semantics observably even
 * though the implementation differs internally). On success the file
 * grows by exactly 80 bytes per {@code app/cpy/CSUSR01Y.cpy} and the
 * captured {@code usrsec_after.txt} carries the appended record at the
 * end of the file with all five operator-provided fields populated and
 * the 23-byte {@code SEC-USR-FILLER} zero/space padding intact.</p>
 *
 * <h2>DUPKEY Error Handling (AAP &sect;0.7.1)</h2>
 *
 * <p>When the operator attempts to add a user-id that already exists in
 * {@code USRSEC}, the {@code EVALUATE WS-RESP-CD} block at
 * {@code app/cbl/COUSR01C.cbl:L250-L274} branches to the
 * {@code WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)} pair at L260-L266
 * which (a) sets the error flag, (b) loads the verbatim
 * {@code 'User ID already exist...'} message into
 * {@code WS-MESSAGE}, (c) positions the cursor on {@code USERIDL} via
 * {@code MOVE -1 TO USERIDL}, and (d) re-sends the screen. Note the
 * <strong>verbatim misspelling</strong> &quot;exist&quot; (not
 * &quot;exists&quot;) is preserved per AAP &sect;0.7.1's preserve-as-is
 * mandate &mdash; the Java translation must NOT &quot;fix&quot; this
 * misspelling; any normalization breaks byte parity. The
 * {@code input_scenario.txt} fixture exercises this branch by
 * submitting a user-id that already exists in the pre-WRITE
 * {@code usrsec.txt} auxiliary fixture, exercising the DUPKEY response
 * code path end-to-end through {@link
 * com.blitzy.carddemo.domain.port.UserSecurityRepository#insert}.</p>
 *
 * <h2>Verbatim Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>The following message literals MUST be preserved byte-for-byte by
 * the Java translation and appear in the captured fixtures. Each
 * carries the exact trailing {@code ...} ellipsis (note the variations
 * in spacing &mdash; some have a leading space, some do not) from the
 * COBOL source; ANY normalization (trimming the ellipsis, collapsing
 * whitespace, recasing) breaks byte parity and blocks the PR:</p>
 * <ul>
 *   <li>{@code 'First Name can NOT be empty...'} at
 *       {@code app/cbl/COUSR01C.cbl:L120} (the first validation check;
 *       mixed-case {@code "can NOT"} preserved verbatim).</li>
 *   <li>{@code 'Last Name can NOT be empty...'} at L126.</li>
 *   <li>{@code 'User ID can NOT be empty...'} at L132.</li>
 *   <li>{@code 'Password can NOT be empty...'} at L138.</li>
 *   <li>{@code 'User Type can NOT be empty...'} at L144.</li>
 *   <li>{@code 'User <id> has been added ...'} at L255-L257 &mdash;
 *       success message constructed via {@code STRING 'User '
 *       DELIMITED BY SIZE SEC-USR-ID DELIMITED BY SPACE ' has been
 *       added ...' DELIMITED BY SIZE INTO WS-MESSAGE}; the
 *       {@code DELIMITED BY SPACE} on {@code SEC-USR-ID} means only
 *       the first whitespace-delimited token of the user-id appears
 *       in the success message (so a user id like {@code "USER001 "}
 *       renders as {@code 'User USER001 has been added ...'} with
 *       the trailing spaces of the PIC X(08) field truncated). The
 *       attribute colour is set to {@code DFHGREEN} on this branch
 *       per L254.</li>
 *   <li>{@code 'User ID already exist...'} at L263 &mdash;
 *       <strong>verbatim COBOL misspelling</strong> ({@code 'exist'}
 *       not {@code 'exists'}) preserved per AAP &sect;0.7.1
 *       ("preserve current behavior ... edge cases ... error codes")
 *       and documented in {@code java/MIGRATION_NOTES.md}; the Java
 *       translation must NOT correct this typo.</li>
 *   <li>{@code 'Unable to Add User...'} at L270 (fallback for any
 *       other non-NORMAL {@code WS-RESP-CD} value; uppercase
 *       {@code 'A'} in {@code 'Add'} preserved).</li>
 *   <li>{@code CCDA-MSG-INVALID-KEY} ({@code 'Invalid key pressed.
 *       Please see below...'} from {@code app/cpy/CSMSG01Y.cpy})
 *       emitted by the {@code WHEN OTHER} branch of the EIBAID
 *       {@code EVALUATE} at {@code app/cbl/COUSR01C.cbl:L98-L102}.</li>
 * </ul>
 *
 * <p>Additionally, the Java translation introduces a NEW verbatim
 * message {@code "User Type must be 'A' (Admin) or 'U' (User)"} per
 * AAP &sect;0.6.10 ("closed business taxonomies are exhaustive") to
 * reject {@code USRTYPE} characters other than {@code 'A'} or
 * {@code 'U'} before the WRITE. The COBOL source does NOT validate
 * the user-type (it silently writes whatever character was entered);
 * the Java translation enforces the closed-set
 * {@link com.blitzy.carddemo.domain.commarea.UserType} taxonomy. This
 * deviation is documented in {@code java/MIGRATION_NOTES.md} and the
 * {@code stdout.txt} / {@code bms_output.txt} captures reflect this
 * additional validation on the relevant scenario submission.</p>
 *
 * <h2>AID-Key Dispatch (Enter, PF3, PF4, with PF12 Java Extension)</h2>
 *
 * <p>The AID-key dispatch in the COBOL program at
 * {@code app/cbl/COUSR01C.cbl:L90-L103} accepts three AID values; any
 * other AID (including PF12) falls through to the verbatim
 * {@code CCDA-MSG-INVALID-KEY} error message at L98-L102:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; invoke
 *       {@code PROCESS-ENTER-KEY}: validate FNAMEI/LNAMEI/USERIDI/
 *       PASSWDI/USRTYPEI non-blank in fixed COBOL order (stopping at
 *       the first blank), perform {@code WRITE-USER-SEC-FILE} on
 *       success.</li>
 *   <li><strong>PF3 (Back)</strong> &mdash; set
 *       {@code CDEMO-TO-PROGRAM = 'COADM01C'} and emit
 *       {@code EXEC CICS XCTL PROGRAM(COADM01C)} per
 *       {@code app/cbl/COUSR01C.cbl:L93-L95}; unlike COUSR02C/COUSR03C
 *       this XCTL target is HARD-CODED to {@code COADM01C} and does
 *       NOT honour {@code CDEMO-FROM-PROGRAM}.</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; invoke
 *       {@code CLEAR-CURRENT-SCREEN} which {@code PERFORM}s
 *       {@code INITIALIZE-ALL-FIELDS} (MOVE SPACES to USERIDI, FNAMEI,
 *       LNAMEI, PASSWDI, USRTYPEI, and WS-MESSAGE) per
 *       {@code app/cbl/COUSR01C.cbl:L287-L295} and then re-SEND-MAPs
 *       an empty entry screen with the cursor positioned on FNAMEI
 *       via {@code MOVE -1 TO FNAMEL}.</li>
 *   <li><strong>PF12 (Exit)</strong> &mdash; <strong>NEW behaviour in
 *       Java translation</strong>: the COBOL source does NOT
 *       explicitly handle PF12 (it falls through to {@code WHEN
 *       OTHER} producing the invalid-key error). The Java translation
 *       elevates PF12 to an explicit XCTL to {@code COSGN00C}
 *       (signon) per the schema mandate so the operator can always
 *       exit to the signon prompt from any online program. This
 *       deviation is documented in {@code java/MIGRATION_NOTES.md}
 *       and the {@code stdout.txt} / {@code bms_output.txt} captures
 *       reflect the Java behaviour rather than the COBOL behaviour
 *       on the PF12 scenario submission &mdash; this is the ONE
 *       intentional break from idiom-for-idiom translation in
 *       COUSR01C.</li>
 *   <li><strong>WHEN OTHER</strong> &mdash; emit the verbatim
 *       {@code CCDA-MSG-INVALID-KEY} ({@code 'Invalid key pressed.
 *       Please see below...'} from {@code app/cpy/CSMSG01Y.cpy}) and
 *       re-SEND-MAP with cursor positioned on FNAMEI via
 *       {@code MOVE -1 TO FNAMEL}.</li>
 * </ul>
 *
 * <h2>NO Auto-Trigger Flow (Distinct from COUSR02C / COUSR03C)</h2>
 *
 * <p>Unlike its sibling {@code COUSR02C} and {@code COUSR03C} (which
 * auto-trigger PROCESS-ENTER-KEY on first-pass entry when
 * {@code CDEMO-CU02-USR-SELECTED} or {@code CDEMO-CU03-USR-SELECTED}
 * is populated from a preceding {@code COUSR00C} row selection),
 * {@code COUSR01C} has NO equivalent auto-trigger &mdash; the user-add
 * screen is always entered empty because there is no preceding row
 * selection to seed the user-id (the operator is adding a NEW user, not
 * updating or deleting an existing one). The first-pass entry path at
 * {@code app/cbl/COUSR01C.cbl:L83-L87} unconditionally sends the empty
 * map with cursor on FNAMEI. The Java translation preserves this
 * absence of auto-trigger behaviour exactly: there is no
 * {@code cdemoCu01UsrSelected} field on the input record.</p>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cousr01c/expected/} encodes the
 * full state space of the user-add transaction: initial empty entry
 * (signon redirect from empty commarea), valid Admin user submission
 * (SEC-USER-TYPE='A' written successfully), valid User user submission
 * (SEC-USER-TYPE='U' written successfully), DUPKEY error on existing
 * user-id (matching one of the records in the pre-WRITE
 * {@code usrsec.txt} fixture), invalid SEC-USER-TYPE rejection (any
 * character other than 'A' or 'U' produces the Java-introduced
 * validation message), the five empty-field validation paths (FNAME,
 * LNAME, USERID, PASSWD, USRTYPE) in fixed COBOL order, PF3 back to
 * COADM01C, PF4 clear, PF12 exit to COSGN00C (Java extension), and
 * invalid key. The harness compares the three captured outputs
 * ({@code stdout.txt}, {@code bms_output.txt}, post-WRITE
 * {@code usrsec.txt} versus {@code usrsec_after.txt}) byte-for-byte to
 * the captured COBOL baseline.</p>
 *
 * <h2>Auxiliary Input Fixture</h2>
 *
 * <p>The single auxiliary fixture
 * {@code src/test/resources/golden/cousr01c/expected/usrsec.txt} backs
 * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
 * port that COUSR01C writes to via {@code EXEC CICS WRITE}. The
 * fixture lives under {@code expected/} (not {@code input/}) because
 * it is test-owned scaffolding shared by BOTH the COBOL CICS capture
 * run AND the Java translation under test; both sides must agree on
 * the pre-WRITE user-id vocabulary for byte-for-byte parity to be
 * meaningful (the DUPKEY scenario depends on at least one existing
 * record). The harness's {@code runProgram(...)} hook copies the
 * fixture into a temp directory before the run so the source-
 * controlled file is NEVER mutated in place; the post-WRITE state is
 * captured separately into the run's temp file and compared against
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
 * under {@code src/test/resources/golden/cousr01c/expected/}.</p>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.user.CoUsr01C
 * @see CoUsr02CGoldenTest
 * @see CoUsr03CGoldenTest
 * @since 25
 */
@DisplayName("COUSR01C \u2014 User Add Golden-Record Parity (plaintext password preserved)")
public class CoUsr01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COUSR01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cousr01c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cousr01c/expected/}. Encodes the
     * multi-submission pseudo-conversation sequence (initial empty
     * entry, valid Admin user WRITE, valid User user WRITE, DUPKEY on
     * existing user-id, invalid SEC-USER-TYPE rejection, five empty-
     * field validations in COBOL order FNAME &rarr; LNAME &rarr;
     * USERID &rarr; PASSWD &rarr; USRTYPE, PF3 back to COADM01C, PF4
     * clear, PF12 exit to COSGN00C (Java extension), invalid key)
     * consumed by the harness orchestrator to drive the CoUsr01C
     * online-CICS state machine through the full state space of the
     * user-add transaction.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cousr01c/expected/}. Records the
     * SLF4J/DISPLAY emissions including the verbatim messages from AAP
     * &sect;0.7.1 (with the trailing {@code ...} ellipsis preserved
     * exactly, including the verbatim COBOL misspelling
     * {@code 'User ID already exist...'} per
     * {@code app/cbl/COUSR01C.cbl:L263}). The capture also includes any
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} emissions
     * on the error branches of WRITE-USER-SEC-FILE (the commented
     * DISPLAY at {@code app/cbl/COUSR01C.cbl:L268} remains commented in
     * the COBOL source, so it does NOT contribute to the capture &mdash;
     * the Java translation preserves this omission). Per AAP &sect;0.7.2
     * the capture contains NO password bytes whatsoever &mdash; the
     * SLF4J logger NEVER receives the plaintext {@code SEC-USR-PWD}
     * value as an argument to any log call.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cousr01c/expected/}. Records the
     * serialized {@code CoUsr01Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * the five user-facing input fields {@code FNAME} (20 chars, pos
     * 8/18 per {@code app/bms/COUSR01.bms:L84-L88}), {@code LNAME} (20
     * chars, pos 8/56 per L97-L101), {@code USERID} (8 chars, pos
     * 11/15 per L111-L115), {@code PASSWD} (8 chars, pos 11/55, DRK
     * attribute per L126-L130), {@code USRTYPE} (1 char, pos 14/17 per
     * L141-L145) plus the standard header line ({@code TRNNAME},
     * {@code TITLE01}, {@code CURDATE}, {@code PGMNAME}, {@code TITLE02},
     * {@code CURTIME}), the {@code "Add User"} banner at line 4 pos 35
     * per L75-L79, the {@code ERRMSG} status line at pos 23/1 per
     * L151-L154, and the keymap legend
     * {@code 'ENTER=Add User  F3=Back  F4=Clear  F12=Exit'} at pos
     * 24/1 per L155-L159. The PASSWD field bytes ARE present in the
     * serialized buffer (DRK only hides them from the terminal renderer
     * per COBOL's existing behaviour preserved under AAP &sect;0.7.1).
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the auxiliary input fixture under
     * {@code src/test/resources/golden/cousr01c/expected/} that backs
     * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port. Contains the pre-WRITE state of the {@code USRSEC}
     * dataset: one or more 80-byte {@code SEC-USER-DATA} records
     * ({@code SEC-USR-ID PIC X(08)} +
     * {@code SEC-USR-FNAME PIC X(20)} +
     * {@code SEC-USR-LNAME PIC X(20)} +
     * {@code SEC-USR-PWD PIC X(08)} +
     * {@code SEC-USR-TYPE PIC X(01)} +
     * {@code SEC-USR-FILLER PIC X(23)} = 80 bytes per
     * {@code app/cpy/CSUSR01Y.cpy:L17-L23}). At least one record's
     * {@code SEC-USR-ID} matches the user-id submitted in the DUPKEY
     * scenario so the WRITE collides with an existing record and the
     * {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} branch at
     * {@code app/cbl/COUSR01C.cbl:L260-L266} fires the verbatim
     * {@code 'User ID already exist...'} message. The fixture lives
     * under {@code expected/} (not {@code input/}) because it is test-
     * owned scaffolding shared between COBOL capture and Java
     * translation. Read-only at the file-system level &mdash; the
     * harness's {@code runProgram(...)} hook copies it into a temp
     * directory before the test run so the source-controlled file is
     * never mutated in place.
     */
    private static final String USRSEC_TXT = "usrsec.txt";

    /**
     * Name of the expected post-WRITE USRSEC fixture under
     * {@code src/test/resources/golden/cousr01c/expected/}. The harness
     * compares the actual {@code usrsec.txt} file written by the Java
     * translation after a successful WRITE to this expected baseline
     * byte-for-byte. Records the post-WRITE state of the {@code USRSEC}
     * dataset: same record layout as {@code usrsec.txt} but with the
     * newly added records appended (the COUSR01C scenario submits
     * BOTH a new Admin user with {@code SEC-USR-TYPE='A'} AND a new
     * User user with {@code SEC-USR-TYPE='U'}, so the post-WRITE file
     * grows by exactly 160 bytes &mdash; two 80-byte records). Per AAP
     * &sect;0.1.3 the {@code SEC-USR-PWD} bytes at offset 48 of each
     * newly appended record carry the <strong>plaintext password</strong>
     * verbatim &mdash; NO hashing is applied; the column is populated
     * with the operator's keystrokes byte-for-byte. Pre-existing
     * records remain byte-identical to their pre-WRITE form (COUSR01C
     * never modifies existing records &mdash; only appends new ones).
     */
    private static final String USRSEC_AFTER_TXT = "usrsec_after.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.user.CoUsr01C}{@code .class}.
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
        return com.blitzy.carddemo.application.user.CoUsr01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cousr01c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the multi-
     * submission CICS pseudo-conversation that drives the CoUsr01C
     * online-state machine through the full state space (initial
     * empty entry, valid Admin user WRITE, valid User user WRITE,
     * DUPKEY on existing user-id, invalid SEC-USER-TYPE rejection,
     * the five empty-field validations in COBOL order FNAME &rarr;
     * LNAME &rarr; USERID &rarr; PASSWD &rarr; USRTYPE, PF3 back,
     * PF4 clear, PF12 exit (Java extension), invalid key); it lives
     * alongside the expected outputs under the per-program
     * {@code cousr01c/} subtree because it is a harness-internal
     * fixture (not part of the immutable {@code app/data/ASCII/}
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
     * {@code src/test/resources/golden/cousr01c/expected/stdout.txt},
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
     * fixture paths reflecting the COUSR01C collaborator surface:</p>
     * <ol>
     *   <li>{@code src/test/resources/golden/cousr01c/expected/usrsec.txt}
     *       &mdash; pre-WRITE USRSEC baseline with the 80-byte
     *       {@code SEC-USER-DATA} record layout per
     *       {@code app/cpy/CSUSR01Y.cpy}. At least one record's
     *       {@code SEC-USR-ID PIC X(08)} primary key collides with the
     *       user-id submitted in the DUPKEY scenario so the
     *       {@code EXEC CICS WRITE} in paragraph
     *       {@code WRITE-USER-SEC-FILE} at
     *       {@code app/cbl/COUSR01C.cbl:L240-L248} returns
     *       {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} and the
     *       Java translation's {@code FileUserSecurityRepository}
     *       adapter throws the equivalent exception to surface the
     *       verbatim {@code 'User ID already exist...'} error message.
     *       Backs the {@link
     *       com.blitzy.carddemo.domain.port.UserSecurityRepository}
     *       constructor dependency injected into
     *       {@link com.blitzy.carddemo.application.user.CoUsr01C}. The
     *       file lives under {@code expected/} (not {@code input/})
     *       because it is shared scaffolding for both COBOL capture
     *       and Java translation.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object)} immutable to preserve
     * deterministic ordering. Because COUSR01C MODIFIES the USRSEC
     * dataset on the WRITE path (appending newly added records), the
     * harness's {@code runProgram(...)} hook copies the fixture into a
     * temp directory before the run so the source-controlled
     * {@code usrsec.txt} is NEVER mutated in place; the post-WRITE
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
     * <p>Declares the three byte-for-byte parity targets for COUSR01C.
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
     *       preserved exactly, including the COBOL misspelling
     *       {@code 'User ID already exist...'}). Per AAP &sect;0.7.2
     *       contains NO password bytes &mdash; the SLF4J logger inside
     *       {@link com.blitzy.carddemo.application.user.CoUsr01C}
     *       NEVER receives the plaintext password value as an argument
     *       to any log call (only the user-id appears in log
     *       messages).</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoUsr01Output} screen states (one
     *       per submission in the scenario). Each frame carries the
     *       five user-facing fields ({@code FNAME}, {@code LNAME},
     *       {@code USERID}, {@code PASSWD} with DRK attribute,
     *       {@code USRTYPE}) plus the standard header and the
     *       {@code "Add User"} banner. The PASSWD column bytes are
     *       present in the serialized buffer (preserved COBOL
     *       behaviour per AAP &sect;0.7.1).</li>
     *   <li>{@link #USRSEC_TXT} ({@code usrsec.txt}) &mdash; the
     *       post-WRITE state of the USRSEC dataset, compared against
     *       the {@link #USRSEC_AFTER_TXT} expected baseline. After
     *       successful WRITEs the newly added records' 80 bytes carry
     *       the operator-provided column values including the
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
     * pending the COBOL COUSR01C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cousr01c/expected/} per the
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
     * running surefire with {@code -Dtest=CoUsr01CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoUsr02CGoldenTest} and {@link CoUsr03CGoldenTest}.</p>
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
        "Awaiting COBOL COUSR01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 8 invariants must hold before "
            + "removing @Disabled): "
            + "(1) Admin-only access guard preserved: the empty-commarea "
            + "path at app/cbl/COUSR01C.cbl:L78-L80 redirects to "
            + "COSGN00C when EIBCALEN = 0, forcing operator authentication "
            + "before reaching the add-user screen; COUSR01C itself never "
            + "inspects the commarea's UserType because the access guard "
            + "is enforced upstream by COADM01C (the ONLY legitimate XCTL "
            + "source for CU01). The captured stdout.txt carries NO "
            + "admin-type warning bytes \u2014 the verification depends "
            + "on the upstream menu routing only. "
            + "(2) Valid new Admin user written with SEC-USER-TYPE='A': "
            + "the COBOL block at app/cbl/COUSR01C.cbl:L153-L160 MOVEs "
            + "USERIDI, FNAMEI, LNAMEI, PASSWDI, USRTYPEI into the "
            + "respective SEC-USR-* columns of SEC-USER-DATA, then "
            + "PERFORMs WRITE-USER-SEC-FILE which issues EXEC CICS WRITE "
            + "at L240-L248 against the USRSEC dataset; on DFHRESP(NORMAL) "
            + "at L251-L259 the verbatim 'User <id> has been added ...' "
            + "success message fires (constructed via STRING with "
            + "DELIMITED BY SPACE on SEC-USR-ID so only the first "
            + "whitespace-delimited token of the id appears) with "
            + "DFHGREEN colour per L254. The Java translation produces "
            + "byte-identical SEC-USR-TYPE='A' bytes at offset 56 of the "
            + "appended 80-byte record in usrsec_after.txt. "
            + "(3) Valid new User user written with SEC-USER-TYPE='U': "
            + "same WRITE path as (2) but with USRTYPEI='U' so the "
            + "appended record carries SEC-USR-TYPE='U' (0x55) at offset "
            + "56. The Java FileUserSecurityRepository adapter appends "
            + "this second record after the Admin user's record, so the "
            + "post-WRITE file grows by exactly 160 bytes (two 80-byte "
            + "SEC-USER-DATA records) versus the pre-WRITE usrsec.txt. "
            + "(4) DUPKEY error on existing user-id: the COBOL EVALUATE "
            + "WS-RESP-CD block at app/cbl/COUSR01C.cbl:L250-L274 "
            + "branches to WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC) at "
            + "L260-L266 which (a) sets WS-ERR-FLG='Y', (b) loads the "
            + "verbatim 'User ID already exist...' message (NOTE the "
            + "misspelling 'exist' not 'exists' is preserved per AAP "
            + "\u00a70.7.1 and documented in MIGRATION_NOTES.md), (c) "
            + "positions the cursor on USERIDL via MOVE -1, and (d) "
            + "re-sends the screen. The Java translation produces this "
            + "message byte-for-byte; ANY normalization (correcting the "
            + "misspelling, trimming the ellipsis, collapsing whitespace) "
            + "breaks parity. "
            + "(5) Invalid SEC-USER-TYPE rejected: per the CoUsr01C Java "
            + "translation's added validation per AAP \u00a70.4.1 and "
            + "\u00a70.6.10 ('closed business taxonomies are exhaustive', "
            + "'validates SEC-USER-TYPE \u2208 {A, U}'), an USRTYPE "
            + "value other than 'A' (Admin) or 'U' (User) produces the "
            + "verbatim 'User Type must be 'A' (Admin) or 'U' (User)' "
            + "message. This rule is enforced before the WRITE so an "
            + "invalid type can never reach USRSEC. NOTE: the COBOL "
            + "source does NOT perform this validation (it silently "
            + "writes whatever character was entered); the Java "
            + "translation is intentionally stricter and the deviation "
            + "is documented in MIGRATION_NOTES.md. "
            + "(6) Blank required-fields rejected: the five empty-field "
            + "validations at app/cbl/COUSR01C.cbl:L117-L151 short-"
            + "circuit in the FIXED COBOL order FNAME \u2192 LNAME "
            + "\u2192 USERID \u2192 PASSWD \u2192 USRTYPE; only the "
            + "FIRST empty field triggers an error message. The verbatim "
            + "'First Name can NOT be empty...', 'Last Name can NOT be "
            + "empty...', 'User ID can NOT be empty...', 'Password can "
            + "NOT be empty...', 'User Type can NOT be empty...' "
            + "messages must appear byte-for-byte; mixed-case 'can NOT' "
            + "preserved verbatim. The cursor position is set via MOVE "
            + "-1 to the corresponding *L field (FNAMEL, LNAMEL, "
            + "USERIDL, PASSWDL, USRTYPEL respectively) per L122, L128, "
            + "L134, L140, L146. ANY normalization breaks parity. "
            + "(7) PLAINTEXT password preserved exactly (PIC X(08)) per "
            + "AAP \u00a70.1.3: COBOL L157 writes PASSWDI OF COUSR1AI "
            + "verbatim into SEC-USR-PWD without any hashing; the WRITE "
            + "at L240-L248 persists the 80-byte SEC-USER-DATA record "
            + "with the plaintext password column at offset 48 per "
            + "app/cpy/CSUSR01Y.cpy:L21. The Java FileUserSecurityRepo"
            + "sitory adapter writes the plaintext bytes verbatim to "
            + "usrsec.txt; NO BCrypt, Argon2, or PBKDF2 transformation "
            + "is applied. The usrsec_after.txt expected fixture carries "
            + "the newly written plaintext password bytes at offset 48 "
            + "of EACH appended record; any byte-level mismatch on this "
            + "column breaks AAP \u00a70.1.3 and blocks the PR. The "
            + "decision to migrate USRSEC to BCrypt/Argon2 is an "
            + "explicit follow-up effort flagged in MIGRATION_NOTES.md "
            + "and is OUT OF SCOPE for this refactor. "
            + "(8) Password NEVER appears in stdout.txt per AAP "
            + "\u00a70.7.2: the SLF4J logger inside CoUsr01C NEVER "
            + "receives the plaintext SEC-USR-PWD value as an argument "
            + "to any log.info(...) / log.warn(...) / log.error(...) "
            + "call \u2014 only the user-id appears in log messages. "
            + "The captured stdout.txt contains NO password bytes; any "
            + "byte sequence resembling a plaintext password breaks AAP "
            + "\u00a70.7.2 and blocks the PR unconditionally. (Note: "
            + "bms_output.txt DOES carry the PASSWD field bytes at BMS "
            + "position (11,55) because COUSR01 BMS map's DRK attribute "
            + "hides the field from the terminal renderer but the bytes "
            + "are present in the serialized screen buffer per COBOL's "
            + "existing behaviour preserved under AAP \u00a70.7.1 "
            + "\u2014 storage and logging are distinct surfaces per "
            + "AAP \u00a70.1.3.) "
            + "Additionally: the PF3 (Back) target is hard-coded to "
            + "COADM01C per app/cbl/COUSR01C.cbl:L93-L95 (deliberately "
            + "different from COUSR02C/COUSR03C which honour "
            + "CDEMO-FROM-PROGRAM); the PF4 (Clear) path invokes "
            + "INITIALIZE-ALL-FIELDS at L287-L295 to MOVE SPACES to all "
            + "five input fields and WS-MESSAGE. The Java-introduced "
            + "PF12 \u2192 COSGN00C exit (a deliberate deviation from "
            + "the COBOL WHEN OTHER fall-through documented in "
            + "MIGRATION_NOTES.md) fires its own XCTL captured in "
            + "stdout.txt distinct from the verbatim "
            + "CCDA-MSG-INVALID-KEY message which still fires for any "
            + "OTHER AID key (e.g. PF5, PF6) per L98-L102."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
