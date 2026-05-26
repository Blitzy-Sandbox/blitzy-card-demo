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
 * Byte-for-byte golden-record parity test for {@code COUSR03C}
 * (User Delete Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COUSR03C.cbl} &mdash; the
 * {@code PROGRAM-ID COUSR03C} ({@code app/cbl/COUSR03C.cbl:L23}) online-CICS
 * program backing transaction {@code CU03} ({@code WS-TRANID PIC X(04) VALUE
 * 'CU03'} at {@code app/cbl/COUSR03C.cbl:L37}, mapset {@code COUSR03} via
 * {@code COPY COUSR03.} at {@code app/cbl/COUSR03C.cbl:L60}). The program is
 * the <strong>admin-only user-delete</strong> transaction: it accepts an
 * 8-character {@code USRIDIN} key from the BMS screen, performs a
 * {@code DELETE}-preamble {@code EXEC CICS READ} (with the {@code UPDATE}
 * clause) against the {@code USRSEC} VSAM KSDS
 * ({@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC  '} at
 * {@code app/cbl/COUSR03C.cbl:L39}), displays the first name, last name,
 * and user type fields (NEVER the password) for visual confirmation,
 * and on {@code PF5} issues an {@code EXEC CICS DELETE} that physically
 * removes the record from {@code USRSEC}. It is the natural drill-through
 * target from the sibling {@code COUSR00C} user-list program (which XCTLs
 * here with {@code CDEMO-CU03-USR-SELECTED} populated, auto-triggering
 * the lookup on first-pass entry).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.user.CoUsr03C}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COUSR03C is translated into the
 * {@code application/user/} subpackage co-located with its sibling
 * translations {@code CoUsr00C} (user list, transaction {@code CU00}),
 * {@code CoUsr01C} (user add, transaction {@code CU01}), and
 * {@code CoUsr02C} (user update, transaction {@code CU02}). The Java
 * translation has a 2-argument constructor
 * {@code CoUsr03C(UserSecurityRepository userSecurityRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator
 * surface (one repository port for the {@code USRSEC} KSDS plus the
 * dynamic-CALL routing facility carried as a collaborator for
 * {@code EXEC CICS XCTL} dispatch to {@code COADM01C} on PF3/PF12 and
 * to {@code COSGN00C} on the empty-commarea path). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against file-backed
 * adapters wired to the auxiliary fixture returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>Admin-Only Access (AAP &sect;0.7.1)</h2>
 *
 * <p>COUSR03C does not perform an explicit {@code SEC-USR-TYPE = 'A'}
 * gate within its own paragraph body &mdash; the access guard is
 * enforced upstream by {@code COADM01C} (admin menu) which is the ONLY
 * legitimate XCTL source for {@code CU03}. The empty-commarea path at
 * {@code app/cbl/COUSR03C.cbl:L90-L92} redirects to {@code COSGN00C}
 * (signon) when {@code EIBCALEN = 0}, forcing the operator to
 * authenticate through the admin-bearing signon flow before being
 * allowed to reach the delete screen. The Java translation preserves
 * this composition exactly: {@code CoUsr03C} itself never inspects the
 * commarea's {@code UserType} but is unreachable from
 * {@code CoMen01C} (the regular-user main menu). The captured fixture's
 * {@code stdout.txt} reflects this by carrying NO admin-type warning
 * messages &mdash; the verification depends on the upstream menu
 * routing only.</p>
 *
 * <h2>NO PASSWORD ON SCREEN (AAP &sect;0.7.2)</h2>
 *
 * <p>This is the <strong>defining security invariant</strong> of the
 * COUSR03C parity test. The COUSR03 BMS map at
 * {@code app/bms/COUSR03.bms} (153 lines) declares only four user-facing
 * data fields: {@code USRIDIN} (8 chars, position 8/26), {@code FNAME}
 * (20 chars), {@code LNAME} (20 chars), and {@code USRTYPE} (1 char).
 * There is NO {@code PASSWD} field on the COUSR03 mapset &mdash; in
 * deliberate contrast to {@code COSGN00} (signon) which carries
 * {@code PASSWD} with the {@code ATTRB=DRK} dark-attribute, and to
 * {@code COUSR01} (user add) / {@code COUSR02} (user update) which
 * accept password edits. The COBOL program at
 * {@code app/cbl/COUSR03C.cbl:L165-L167} explicitly populates only
 * {@code FNAMEI}, {@code LNAMEI}, and {@code USRTYPEI} from the
 * {@code SEC-USER-DATA} record fetched from {@code USRSEC}; the
 * {@code SEC-USR-PWD PIC X(08)} component
 * ({@code app/cpy/CSUSR01Y.cpy:L21}) is NEVER projected onto the screen.
 * The Java translation
 * {@link com.blitzy.carddemo.application.user.CoUsr03C} replicates this
 * exclusion byte-for-byte: the captured {@code stdout.txt} contains NO
 * password bytes; the captured {@code bms_output.txt} contains NO
 * password bytes; the {@code usrsec_after.txt} (post-DELETE state)
 * contains all-remaining records with their passwords intact (because
 * the deleted record is gone entirely, not redacted). Any byte sequence
 * resembling a plaintext password in {@code stdout.txt} or
 * {@code bms_output.txt} breaks AAP &sect;0.7.2 and blocks the PR
 * unconditionally.</p>
 *
 * <h2>READ + DELETE Two-Phase Pattern</h2>
 *
 * <p>The COBOL paragraphs at {@code app/cbl/COUSR03C.cbl:L267-L300}
 * ({@code READ-USER-SEC-FILE}) and {@code app/cbl/COUSR03C.cbl:L305-L336}
 * ({@code DELETE-USER-SEC-FILE}) realize the canonical VSAM update
 * preamble: a {@code READ ... UPDATE} acquires the exclusive record
 * lock, validates the operator's intent against the displayed
 * confirmation screen ("Press PF5 key to delete this user ..."), and
 * the subsequent {@code DELETE} physically removes the record from the
 * KSDS leaving NO logical sentinel. The Java translation
 * collapses these two CICS verbs into two port calls
 * ({@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
 * {@code .findById(secUsrId)} then
 * {@code .delete(secUsrId)}) preserving the same observable
 * before/after state on {@code usrsec.txt}. The two phases are
 * temporally separated by the operator's PF5 keystroke: the READ fires
 * on the ENTER key (paragraph {@code PROCESS-ENTER-KEY} at
 * {@code app/cbl/COUSR03C.cbl:L142-L169}), the DELETE fires on the PF5
 * key (paragraph {@code DELETE-USER-INFO} at
 * {@code app/cbl/COUSR03C.cbl:L174-L192}) which itself performs both
 * the second READ AND the DELETE. The captured fixture's
 * {@code input_scenario.txt} exercises BOTH phases of the delete flow
 * (the confirmation screen alone AND the full READ+DELETE sequence) to
 * cover the complete state machine.</p>
 *
 * <h2>Auto-Trigger Flow from COUSR00C (AAP &sect;0.7.1)</h2>
 *
 * <p>The COBOL block at {@code app/cbl/COUSR03C.cbl:L99-L104} implements
 * a one-shot auto-fetch when {@code COUSR03C} is entered with
 * {@code CDEMO-PGM-REENTER = FALSE} (first invocation) AND
 * {@code CDEMO-CU03-USR-SELECTED} populated to a non-blank,
 * non-LOW-VALUES value (the user id selected by the operator on the
 * preceding {@code COUSR00C} list screen via the row-selection
 * {@code 'D'} marker). The conditional at lines 99-100
 * ({@code IF CDEMO-CU03-USR-SELECTED NOT = SPACES AND LOW-VALUES})
 * copies the selected id to {@code USRIDINI OF COUSR3AI} (lines 101-102)
 * and immediately performs {@code PROCESS-ENTER-KEY} (line 103) before
 * SEND MAP &mdash; producing a populated confirmation screen (with
 * first name, last name, and user type prefilled and the "Press PF5
 * key to delete this user ..." message) on the first frame rather
 * than an empty entry screen. The Java translation preserves this
 * behavior by checking
 * {@link com.blitzy.carddemo.application.user.CoUsr03C}'s input
 * record's {@code cdemoCu03UsrSelected} field for non-blank on
 * first-pass entry and auto-triggering the same code path as the
 * explicit ENTER AID key. The {@code input_scenario.txt} fixture
 * exercises BOTH the manual (operator typing the user-id) and
 * auto-trigger (XCTL from COUSR00C row 'D' selection) paths.</p>
 *
 * <h2>Verbatim Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>Per AAP &sect;0.7.1 the following six message literals MUST be
 * preserved byte-for-byte by the Java translation and appear in the
 * captured fixtures. Each carries the exact trailing {@code ...}
 * ellipsis (note the variations in spacing &mdash; some have a leading
 * space, some do not) from the COBOL source; ANY normalization
 * (trimming the ellipsis, collapsing whitespace, recasing) breaks byte
 * parity and blocks the PR:</p>
 * <ul>
 *   <li>{@code 'User ID can NOT be empty...'} at
 *       {@code app/cbl/COUSR03C.cbl:L147,L179} (emitted by paragraph
 *       {@code PROCESS-ENTER-KEY} at L147 AND by paragraph
 *       {@code DELETE-USER-INFO} at L179 when {@code USRIDINI} is SPACES
 *       or LOW-VALUES; mixed-case {@code "can NOT"} preserved
 *       verbatim).</li>
 *   <li>{@code 'Press PF5 key to delete this user ...'} at
 *       {@code app/cbl/COUSR03C.cbl:L283} (the confirmation prompt
 *       emitted after a successful {@code READ-USER-SEC-FILE} returns
 *       {@code DFHRESP(NORMAL)}; the field colour is set to
 *       {@code DFHNEUTR} on this branch per L285; note the SINGLE space
 *       before the ellipsis is preserved verbatim).</li>
 *   <li>{@code 'User ID NOT found...'} at
 *       {@code app/cbl/COUSR03C.cbl:L289,L325} (emitted by paragraph
 *       {@code READ-USER-SEC-FILE} at L289 AND by paragraph
 *       {@code DELETE-USER-SEC-FILE} at L325 on
 *       {@code DFHRESP(NOTFND)}; the record-not-found path).</li>
 *   <li>{@code 'Unable to lookup User...'} at
 *       {@code app/cbl/COUSR03C.cbl:L296} (emitted by paragraph
 *       {@code READ-USER-SEC-FILE} on any other {@code WS-RESP-CD}
 *       value; uppercase {@code 'U'} in {@code 'User'} preserved).</li>
 *   <li>{@code 'Unable to Update User...'} at
 *       {@code app/cbl/COUSR03C.cbl:L332} &mdash; <strong>verbatim COBOL
 *       bug</strong> per AAP &sect;0.7.1 ("If a COBOL paragraph contains
 *       dead code or obvious bugs, translate it faithfully and flag it
 *       in a MIGRATION_NOTES.md; do not 'fix' it in this refactor").
 *       The message says <strong>{@code 'Update'}</strong> (not
 *       {@code 'Delete'}) in the DELETE-failure path because the COBOL
 *       source is a copy-paste leftover from sibling {@code COUSR02C}
 *       (user update). The Java translation preserves the misspelling
 *       byte-for-byte and the deviation is documented in
 *       {@code java/MIGRATION_NOTES.md}.</li>
 *   <li>{@code 'User <id> has been deleted ...'} at
 *       {@code app/cbl/COUSR03C.cbl:L318-L321} &mdash; the success
 *       message constructed via {@code STRING 'User ' DELIMITED BY SIZE
 *       SEC-USR-ID DELIMITED BY SPACE ' has been deleted ...' DELIMITED
 *       BY SIZE INTO WS-MESSAGE}. The {@code DELIMITED BY SPACE} on
 *       {@code SEC-USR-ID} means only the first whitespace-delimited
 *       token of the user-id appears in the success message (so a user
 *       id like {@code "USER001 "} renders as {@code 'User USER001 has
 *       been deleted ...'} with the trailing spaces of the PIC X(08)
 *       field truncated). The Java translation reproduces this
 *       semantic via {@code SEC_USR_ID.split("\\s+", 2)[0]} or
 *       equivalent, then concatenates with the literal prefixes/
 *       suffixes including the leading/trailing spaces and the trailing
 *       ellipsis byte-for-byte.</li>
 * </ul>
 *
 * <p>The catalog additionally includes {@code CCDA-MSG-INVALID-KEY}
 * ({@code 'Invalid key pressed. Please see below...'} from
 * {@code app/cpy/CSMSG01Y.cpy}, emitted by the {@code WHEN OTHER}
 * branch of the EIBAID {@code EVALUATE} at
 * {@code app/cbl/COUSR03C.cbl:L126-L129} for any AID key other than
 * ENTER, PF3, PF4, PF5, or PF12).</p>
 *
 * <h2>AID-Key Dispatch (Enter, PF3, PF4, PF5, PF12)</h2>
 *
 * <p>The AID-key dispatch in the COBOL program at
 * {@code app/cbl/COUSR03C.cbl:L108-L130} accepts five AID values; any
 * other AID falls through to the verbatim {@code CCDA-MSG-INVALID-KEY}
 * error message:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; invoke
 *       {@code PROCESS-ENTER-KEY}: validate {@code USRIDIN} non-blank,
 *       perform {@code READ-USER-SEC-FILE} keyed by the 8-character
 *       value (with the {@code UPDATE} preamble), project
 *       {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME}, and
 *       {@code SEC-USR-TYPE} onto the BMS map, and emit the
 *       "Press PF5 key to delete this user ..." confirmation.</li>
 *   <li><strong>PF3 (Back)</strong> &mdash; emit
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} where
 *       {@code CDEMO-TO-PROGRAM} resolves to {@code CDEMO-FROM-PROGRAM}
 *       (the caller) if non-blank, falling back to {@code COADM01C}
 *       (admin menu) when {@code CDEMO-FROM-PROGRAM} is SPACES or
 *       LOW-VALUES per the conditional at
 *       {@code app/cbl/COUSR03C.cbl:L112-L117}.</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; invoke
 *       {@code CLEAR-CURRENT-SCREEN} which {@code PERFORM}s
 *       {@code INITIALIZE-ALL-FIELDS} (MOVE SPACES to {@code USRIDINI},
 *       {@code FNAMEI}, {@code LNAMEI}, {@code USRTYPEI}, and
 *       {@code WS-MESSAGE}) per {@code app/cbl/COUSR03C.cbl:L349-L356}
 *       and then re-SEND-MAPs an empty entry screen with the cursor
 *       positioned on {@code USRIDINI} via
 *       {@code MOVE -1 TO USRIDINL}.</li>
 *   <li><strong>PF5 (Delete)</strong> &mdash; invoke
 *       {@code DELETE-USER-INFO}: validate {@code USRIDIN} non-blank
 *       (re-check), perform {@code READ-USER-SEC-FILE} followed by
 *       {@code DELETE-USER-SEC-FILE} (the two-phase READ+DELETE), and
 *       on {@code DFHRESP(NORMAL)} of the DELETE emit the verbatim
 *       success message and clear all fields via
 *       {@code INITIALIZE-ALL-FIELDS}. The DELETE physically removes
 *       the record from {@code USRSEC} (subsequent records shift up
 *       logically); the {@code usrsec_after.txt} fixture captures the
 *       post-DELETE state of the file.</li>
 *   <li><strong>PF12 (Cancel)</strong> &mdash; per
 *       {@code app/cbl/COUSR03C.cbl:L123-L125}, hard-codes
 *       {@code CDEMO-TO-PROGRAM = 'COADM01C'} (the admin menu,
 *       unconditional &mdash; ignoring {@code CDEMO-FROM-PROGRAM}) and
 *       emits {@code EXEC CICS XCTL PROGRAM('COADM01C')}. This is a
 *       deliberate distinction from PF3, which honours
 *       {@code CDEMO-FROM-PROGRAM} as the back target.</li>
 * </ul>
 *
 * <p>The Java translation's {@code Outcome} sealed interface (permits
 * {@code SendMap} and {@code Xctl}) replaces the COBOL CICS verbs
 * {@code EXEC CICS SEND MAP} / {@code EXEC CICS RETURN} and
 * {@code EXEC CICS XCTL} respectively; the exhaustive pattern-matching
 * switch over the sixteen {@code AidKey} permits enforces compile-time
 * completeness per AAP &sect;0.6.7 (no {@code default} branch).</p>
 *
 * <h2>Physical DELETE Semantics</h2>
 *
 * <p>COBOL VSAM {@code EXEC CICS DELETE} (at
 * {@code app/cbl/COUSR03C.cbl:L307-L311}) physically removes the record
 * from the KSDS &mdash; there is NO logical-delete sentinel; subsequent
 * records compact and the file shrinks by exactly one
 * {@code SEC-USER-DATA} record (80 bytes per
 * {@code app/cpy/CSUSR01Y.cpy:L17-L23}). The Java
 * {@code FileUserSecurityRepository} adapter replicates this on-disk
 * behaviour: after a successful {@code DELETE} call the
 * {@code usrsec.txt} fixture is rewritten WITHOUT the deleted record,
 * matching the {@code usrsec_after.txt} expected fixture byte-for-byte.
 * The auxiliary fixture {@code usrsec.txt} (the pre-DELETE state)
 * lives alongside {@code usrsec_after.txt} (the post-DELETE state)
 * under the same per-program {@code cousr03c/expected/} subtree
 * because both are test-owned scaffolding shared between the COBOL
 * capture run and the Java translation under test; the deterministic
 * scenario contract {@code input_scenario.txt} drives both.</p>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cousr03c/expected/} encodes a
 * sequence of terminal submissions exercising the full state space of
 * the user-delete transaction:</p>
 * <ol>
 *   <li><strong>Initial empty entry</strong> ({@code EIBCALEN = 0},
 *       no commarea) &mdash; the program sets
 *       {@code CDEMO-TO-PROGRAM = 'COSGN00C'} and invokes
 *       {@code RETURN-TO-PREV-SCREEN}; first-cycle behaviour redirects
 *       to signon.</li>
 *   <li><strong>Valid lookup by entered user-id</strong> &mdash;
 *       operator enters a known user-id from {@code usrsec.txt};
 *       {@code READ-USER-SEC-FILE} returns {@code DFHRESP(NORMAL)};
 *       first name, last name, and user type prefill on the screen
 *       and {@code "Press PF5 key to delete this user ..."} appears
 *       in {@code ERRMSGO}.</li>
 *   <li><strong>Auto-trigger from COUSR00C row 'D' selection</strong>
 *       &mdash; commarea carries non-blank
 *       {@code CDEMO-CU03-USR-SELECTED}; the program auto-copies to
 *       {@code USRIDINI} and runs {@code PROCESS-ENTER-KEY}
 *       immediately, producing a populated confirmation screen on
 *       the first frame.</li>
 *   <li><strong>User-id NOT found</strong> &mdash; operator enters a
 *       user-id that does NOT exist in {@code usrsec.txt};
 *       {@code READ-USER-SEC-FILE} returns {@code DFHRESP(NOTFND)};
 *       the verbatim {@code 'User ID NOT found...'} message fires
 *       and cursor returns to {@code USRIDINI}.</li>
 *   <li><strong>Empty user-id submission</strong> &mdash; operator
 *       hits ENTER with {@code USRIDINI} blank; the verbatim
 *       {@code 'User ID can NOT be empty...'} message fires from
 *       {@code PROCESS-ENTER-KEY}.</li>
 *   <li><strong>PF5 DELETE happy path</strong> &mdash; following a
 *       successful lookup, operator hits PF5; the program performs
 *       {@code READ-USER-SEC-FILE} then {@code DELETE-USER-SEC-FILE}
 *       on the {@code USRSEC} dataset; the record is physically
 *       removed; the success message
 *       {@code 'User <id> has been deleted ...'} fires (with
 *       DELIMITED BY SPACE truncation on the id token) and all
 *       fields clear via {@code INITIALIZE-ALL-FIELDS}.</li>
 *   <li><strong>PF3 (Back)</strong> &mdash; emits Outcome.Xctl with
 *       target {@code CDEMO-FROM-PROGRAM} (defaulting to
 *       {@code COADM01C} when blank); preserves the commarea.</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; invokes
 *       {@code CLEAR-CURRENT-SCREEN}, blanking all fields and
 *       re-sending the empty entry screen.</li>
 *   <li><strong>PF12 (Cancel to COADM01C)</strong> &mdash;
 *       hard-codes target to {@code COADM01C} ignoring
 *       {@code CDEMO-FROM-PROGRAM}; emits Outcome.Xctl.</li>
 *   <li><strong>Invalid key</strong> &mdash; operator hits PF1
 *       (or any AID other than ENTER/PF3/PF4/PF5/PF12); WHEN OTHER
 *       branch fires verbatim {@code 'Invalid key pressed. Please
 *       see below...'} from {@code CCDA-MSG-INVALID-KEY}.</li>
 * </ol>
 *
 * <p>Each submission produces a serialized {@code CoUsr03Output}
 * screen state plus zero or more SLF4J log lines; the harness
 * concatenates all screen states into {@code bms_output.txt} and all
 * log lines into {@code stdout.txt}. The DELETE submission additionally
 * produces a rewritten {@code usrsec.txt} that the harness compares
 * against {@code usrsec_after.txt}. The three outputs are compared
 * byte-for-byte to the captured COBOL baseline.</p>
 *
 * <h2>Auxiliary Input Fixture</h2>
 *
 * <p>The single auxiliary fixture
 * {@code src/test/resources/golden/cousr03c/expected/usrsec.txt} backs
 * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
 * port that COUSR03C reads via {@code EXEC CICS READ} and modifies via
 * {@code EXEC CICS DELETE}. The fixture lives under
 * {@code expected/} (not {@code input/}) because it is test-owned
 * scaffolding shared by BOTH the COBOL CICS capture run AND the Java
 * translation under test, alongside its post-DELETE counterpart
 * {@code usrsec_after.txt}; both sides must agree on the user-id
 * vocabulary for byte-for-byte parity to be meaningful. The COBOL
 * program targets the permanent {@code USRSEC} VSAM KSDS (8-byte key
 * {@code SEC-USR-ID PIC X(08)}, 80-byte record per
 * {@code app/cpy/CSUSR01Y.cpy}); the harness's
 * {@code runProgram(...)} hook copies the fixture into a temp directory
 * before the run so the source-controlled fixture is NEVER mutated
 * in place (mutating committed test resources would corrupt subsequent
 * runs and is forbidden).</p>
 *
 * <h2>Scaffolding state</h2>
 *
 * <p>Per AAP &sect;0.6.11 ("Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available; the harness skeleton, base class, and per-program test
 * classes are created unconditionally"), the {@link #byteForByteParity()}
 * override below is annotated {@code @Disabled} with a 7-point
 * verification reason citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * under {@code src/test/resources/golden/cousr03c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.user.CoUsr03C
 * @since 25
 */
@DisplayName("COUSR03C \u2014 User Delete Golden-Record Parity")
public class CoUsr03CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COUSR03C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cousr03c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cousr03c/expected/}. Encodes the
     * ten-submission pseudo-conversation sequence (initial empty entry,
     * valid lookup, auto-trigger from COUSR00C, user-id-not-found,
     * empty user-id, PF5 DELETE happy path, PF3 back, PF4 clear, PF12
     * cancel, invalid key) consumed by the harness orchestrator to
     * drive the CoUsr03C online-CICS state machine through the full
     * state space of the user-delete transaction.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cousr03c/expected/}. Records the
     * SLF4J/DISPLAY emissions including the six verbatim messages from
     * AAP &sect;0.7.1 (with the trailing {@code ...} ellipsis preserved
     * exactly, including the leading SINGLE space in
     * {@code 'Press PF5 key to delete this user ...'} and the verbatim
     * COBOL bug {@code 'Unable to Update User...'} in the DELETE-failure
     * path). The capture also includes any
     * {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} emissions
     * on the error branches of READ-USER-SEC-FILE (L294) and
     * DELETE-USER-SEC-FILE (L330). Per AAP &sect;0.7.2 the capture
     * contains NO password bytes whatsoever &mdash; the COUSR03 BMS map
     * carries no PASSWD field and the program never reads
     * {@code SEC-USR-PWD} onto any screen or log surface.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cousr03c/expected/}. Records the
     * serialized {@code CoUsr03Output} screen states (one per
     * submission, concatenated in submission order). Each frame carries
     * the four user-facing fields {@code USRIDIN} (8 chars,
     * pos 8/26), {@code FNAME} (20 chars), {@code LNAME} (20 chars),
     * and {@code USRTYPE} (1 char) plus the standard header line
     * ({@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME},
     * {@code TITLE02}, {@code CURTIME}), the {@code "Delete User"}
     * banner at line 4 pos 35, and the {@code ERRMSG} status line. Per
     * AAP &sect;0.7.2 NO {@code PASSWD} field appears in any frame
     * &mdash; the COUSR03 BMS map at {@code app/bms/COUSR03.bms} (153
     * lines) declares no such field.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the auxiliary input fixture under
     * {@code src/test/resources/golden/cousr03c/expected/} that backs
     * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port. Contains the pre-DELETE state of the {@code USRSEC} dataset:
     * one or more 80-byte {@code SEC-USER-DATA} records
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
     * Name of the expected post-DELETE USRSEC fixture under
     * {@code src/test/resources/golden/cousr03c/expected/}. The
     * harness compares the actual {@code usrsec.txt} file written by
     * the Java translation after the PF5 DELETE submission to this
     * expected baseline byte-for-byte. Records the post-DELETE state
     * of the {@code USRSEC} dataset: same record layout as
     * {@code usrsec.txt} but with the deleted user's 80-byte record
     * physically removed (subsequent records shift up logically; the
     * file shrinks by exactly 80 bytes per AAP &sect;0.7.1's exact-
     * fidelity rule for VSAM DELETE semantics).
     */
    private static final String USRSEC_AFTER_TXT = "usrsec_after.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.user.CoUsr03C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the JUnit
     * Jupiter API annotations ({@link DisplayName}, {@link Disabled},
     * {@link Test}). The fully-qualified class literal compiles cleanly
     * because {@code carddemo-tests} declares a test-scope dependency on
     * {@code carddemo-application} (transitively via {@code carddemo-app})
     * in {@code java/carddemo-tests/pom.xml} per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.user.CoUsr03C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cousr03c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * ten-submission CICS pseudo-conversation that drives the
     * CoUsr03C online-state machine through the full state space
     * (initial empty entry, valid lookup, auto-trigger from COUSR00C,
     * user-id-not-found, empty user-id, PF5 DELETE happy path, PF3
     * back, PF4 clear, PF12 cancel, invalid key); it lives alongside
     * the expected outputs under the per-program {@code cousr03c/}
     * subtree because it is a harness-internal fixture (not part of
     * the immutable {@code app/data/ASCII/} dataset).</p>
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
     * {@code src/test/resources/golden/cousr03c/expected/stdout.txt},
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
     * fixture paths reflecting the COUSR03C collaborator surface:</p>
     * <ol>
     *   <li>{@code src/test/resources/golden/cousr03c/expected/usrsec.txt}
     *       &mdash; pre-DELETE USRSEC baseline with the 80-byte
     *       {@code SEC-USER-DATA} record layout per
     *       {@code app/cpy/CSUSR01Y.cpy}. READ random access by
     *       8-character primary key {@code SEC-USR-ID PIC X(08)} in
     *       paragraph {@code READ-USER-SEC-FILE} at
     *       {@code app/cbl/COUSR03C.cbl:L267-L278}; DELETE in paragraph
     *       {@code DELETE-USER-SEC-FILE} at
     *       {@code app/cbl/COUSR03C.cbl:L307-L311}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     *       constructor dependency injected into
     *       {@link com.blitzy.carddemo.application.user.CoUsr03C}. The
     *       file lives under {@code expected/} (not {@code input/})
     *       because it is shared scaffolding for both COBOL capture
     *       and Java translation.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object)} immutable to preserve
     * deterministic ordering. Because COUSR03C MODIFIES the USRSEC
     * dataset on the PF5 DELETE path (unlike the read-only sibling
     * COUSR00C list and COACTVW view programs), the harness's
     * {@code runProgram(...)} hook copies the fixture into a temp
     * directory before the run so the source-controlled
     * {@code usrsec.txt} is NEVER mutated in place; the post-DELETE
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
     * <p>Declares the three byte-for-byte parity targets for COUSR03C.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J/DISPLAY trace including the six verbatim messages
     *       from AAP &sect;0.7.1 (with the trailing {@code ...}
     *       ellipsis preserved exactly, including the verbatim COBOL
     *       bug {@code 'Unable to Update User...'} in the DELETE-failure
     *       path). Per AAP &sect;0.7.2 contains NO password bytes.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoUsr03Output} screen states (one
     *       per submission in the scenario). Each frame carries the
     *       four user-facing fields ({@code USRIDIN}, {@code FNAME},
     *       {@code LNAME}, {@code USRTYPE}) plus the standard header.
     *       Per AAP &sect;0.7.2 NO {@code PASSWD} field appears &mdash;
     *       the COUSR03 BMS map carries no such field.</li>
     *   <li>{@link #USRSEC_TXT} ({@code usrsec.txt}) &mdash; the
     *       post-DELETE state of the USRSEC dataset, compared against
     *       the {@link #USRSEC_AFTER_TXT} expected baseline. After a
     *       successful PF5 DELETE the deleted record's 80 bytes are
     *       physically absent from the file; the remaining records
     *       are byte-identical to their pre-DELETE form. The
     *       {@link ExpectedOutput#name()} {@code "usrsec.txt"}
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
     * pending the COBOL COUSR03C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cousr03c/expected/} per the
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
     * running surefire with {@code -Dtest=CoUsr03CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdSlCGoldenTest}, {@link CoCrdUpCGoldenTest},
     * {@link CoTrn00CGoldenTest}, {@link CoTrn01CGoldenTest}, and
     * {@link CoTrn02CGoldenTest}.</p>
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
        "Awaiting COBOL COUSR03C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 7 invariants must hold before "
            + "removing @Disabled): "
            + "(1) Admin-only access guard preserved: the empty-commarea "
            + "path at app/cbl/COUSR03C.cbl:L90-L92 redirects to "
            + "COSGN00C when EIBCALEN = 0, forcing operator authentication "
            + "before reaching the delete screen; COUSR03C itself never "
            + "inspects the commarea's UserType because the access guard "
            + "is enforced upstream by COADM01C (the ONLY legitimate XCTL "
            + "source for CU03). The captured stdout.txt carries NO "
            + "admin-type warning bytes \u2014 the verification depends "
            + "on the upstream menu routing only. "
            + "(2) Valid delete by entered user-id (READ + DELETE): the "
            + "two-phase pattern at app/cbl/COUSR03C.cbl:L267-L300 "
            + "(READ-USER-SEC-FILE with the UPDATE preamble) and "
            + "L305-L336 (DELETE-USER-SEC-FILE) collapses into two "
            + "UserSecurityRepository port calls (findById then delete) "
            + "temporally separated by the operator's PF5 keystroke. "
            + "The verbatim 'Press PF5 key to delete this user ...' "
            + "confirmation at COBOL L283 (SINGLE space before "
            + "ellipsis preserved) appears on the post-READ frame; the "
            + "verbatim 'User <id> has been deleted ...' success "
            + "message at COBOL L318-L321 (constructed via STRING with "
            + "DELIMITED BY SPACE on SEC-USR-ID so only the first "
            + "whitespace-delimited token of the id appears) fires on "
            + "the post-DELETE frame. "
            + "(3) Auto-trigger flow from COUSR00C row 'D' selection: "
            + "when commarea carries non-blank "
            + "CDEMO-CU03-USR-SELECTED, the COBOL block at "
            + "app/cbl/COUSR03C.cbl:L99-L104 (under the IF NOT "
            + "CDEMO-PGM-REENTER first-pass branch) copies to "
            + "USRIDINI and immediately performs PROCESS-ENTER-KEY, "
            + "producing a populated confirmation screen on the first "
            + "frame rather than an empty entry screen. The Java "
            + "translation preserves this auto-trigger by checking "
            + "CoUsr03Input.cdemoCu03UsrSelected() for non-blank on "
            + "first-pass entry. "
            + "(4) User-id NOT found produces verbatim error: when "
            + "READ-USER-SEC-FILE returns DFHRESP(NOTFND) at "
            + "app/cbl/COUSR03C.cbl:L287-L292 OR when "
            + "DELETE-USER-SEC-FILE returns DFHRESP(NOTFND) at "
            + "app/cbl/COUSR03C.cbl:L323-L328, the verbatim 'User ID "
            + "NOT found...' message fires with trailing '...' "
            + "ellipsis preserved exactly; cursor returns to "
            + "USRIDINI via MOVE -1 TO USRIDINL. Empty-id submission "
            + "fires 'User ID can NOT be empty...' from both "
            + "PROCESS-ENTER-KEY (L147) and DELETE-USER-INFO (L179); "
            + "mixed-case 'can NOT' preserved verbatim. ANY "
            + "normalization (trimming ellipsis, collapsing whitespace, "
            + "recasing) breaks byte parity. "
            + "(5) Confirmation prompt before DELETE: the two-phase "
            + "interaction enforces 'user types user-id \u2192 READ "
            + "displays current record + Press PF5 prompt \u2192 user "
            + "presses PF5 \u2192 DELETE executed'. The ENTER key "
            + "alone (PROCESS-ENTER-KEY) NEVER deletes; only PF5 "
            + "(DELETE-USER-INFO) does. The captured scenario "
            + "exercises both an ENTER-then-PF5 happy path AND an "
            + "ENTER-then-PF3 abandon path to verify the DELETE does "
            + "NOT fire on confirmation-screen abandon. "
            + "(6) Record physically removed from usrsec.txt after "
            + "DELETE: VSAM EXEC CICS DELETE at "
            + "app/cbl/COUSR03C.cbl:L307-L311 physically removes the "
            + "80-byte SEC-USER-DATA record from the KSDS (no logical-"
            + "delete sentinel; the file shrinks by exactly 80 bytes "
            + "per app/cpy/CSUSR01Y.cpy). The Java "
            + "FileUserSecurityRepository adapter replicates this "
            + "on-disk: after a successful delete the temp-copy "
            + "usrsec.txt is rewritten WITHOUT the deleted record, "
            + "matching usrsec_after.txt byte-for-byte. Subsequent "
            + "records compact in-order; no sentinel bytes are "
            + "introduced. "
            + "(7) Password column NEVER appears in stdout.txt or "
            + "bms_output.txt per AAP \u00a70.7.2: the COUSR03 BMS "
            + "map at app/bms/COUSR03.bms (153 lines) declares NO "
            + "PASSWD field \u2014 only USRIDIN, FNAME, LNAME, "
            + "USRTYPE are present; the COBOL program at "
            + "app/cbl/COUSR03C.cbl:L165-L167 explicitly projects "
            + "only FNAMEI, LNAMEI, USRTYPEI from SEC-USER-DATA, "
            + "NEVER reading SEC-USR-PWD onto any screen or log "
            + "surface. Any byte sequence resembling a plaintext "
            + "password in stdout.txt or bms_output.txt breaks AAP "
            + "\u00a70.7.2 and blocks the PR unconditionally. The "
            + "usrsec_after.txt fixture retains all remaining users' "
            + "SEC-USR-PWD bytes intact (the deleted record is gone "
            + "entirely, not redacted) because USRSEC is the canonical "
            + "credential store distinct from the logging surface "
            + "(storage and logging are different surfaces per AAP "
            + "\u00a70.1.1 surfaced implicit requirement). "
            + "Additionally: the verbatim COBOL bug 'Unable to Update "
            + "User...' at app/cbl/COUSR03C.cbl:L332 (says 'Update' "
            + "in the DELETE-failure path \u2014 copy-paste leftover "
            + "from sibling COUSR02C) is preserved verbatim per AAP "
            + "\u00a70.7.1 'do not fix in this refactor' and is "
            + "documented in java/MIGRATION_NOTES.md. The PF3 back "
            + "target honours CDEMO-FROM-PROGRAM (defaulting to "
            + "COADM01C when blank) per COBOL L112-L117; the PF12 "
            + "cancel target hard-codes COADM01C unconditionally per "
            + "COBOL L123-L125 \u2014 a deliberate distinction "
            + "preserved by the Java translation."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
