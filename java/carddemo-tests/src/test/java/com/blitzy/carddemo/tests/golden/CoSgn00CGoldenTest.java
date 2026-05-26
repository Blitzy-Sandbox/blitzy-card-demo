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
 * Byte-for-byte golden-record parity test for {@code COSGN00C}
 * (Signon Screen Handler for CICS Transaction {@code CC00}).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COSGN00C.cbl} &mdash; the
 * {@code PROGRAM-ID COSGN00C} ({@code app/cbl/COSGN00C.cbl:L23}) online-CICS
 * program backing transaction {@code CC00} ({@code WS-TRANID PIC X(04)
 * VALUE 'CC00'} at {@code app/cbl/COSGN00C.cbl:L37}). The program is the
 * <strong>authentication gateway</strong> of the CardDemo application: it
 * receives a user-id and password from BMS map {@code COSGN0A} (mapset
 * {@code COSGN00} via {@code COPY COSGN00.} at
 * {@code app/cbl/COSGN00C.cbl:L50}), performs a single {@code EXEC CICS
 * READ DATASET('USRSEC  ')} ({@code WS-USRSEC-FILE PIC X(08) VALUE 'USRSEC
 * '} at {@code app/cbl/COSGN00C.cbl:L39}) against the user-security KSDS,
 * compares the supplied plaintext password against {@code SEC-USR-PWD}
 * ({@code app/cpy/CSUSR01Y.cpy:L21}), and on success routes the operator
 * via {@code EXEC CICS XCTL} to either the admin menu ({@code COADM01C}
 * per {@code app/cbl/COSGN00C.cbl:L230-L234}) or the user menu
 * ({@code COMEN01C} per L235-L239) based on the {@code SEC-USR-TYPE PIC
 * X(01)} field of the matched user record (the
 * {@link com.blitzy.carddemo.domain.commarea.UserType} sealed taxonomy
 * from AAP &sect;0.6.10 with permits {@code Admin}=&apos;A&apos; and
 * {@code User}=&apos;U&apos; per {@code app/cpy/COCOM01Y.cpy:L27-L28}).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.signon.CoSgn00C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COSGN00C is translated into
 * the {@code application/signon/} subpackage as the sole occupant (no
 * sibling programs in the {@code signon/} domain since signon is a
 * standalone authentication gateway distinct from menu, account, card,
 * transaction, user, billpay, statement, and report domains). The Java
 * translation carries a constructor matching the COBOL collaborator
 * surface ({@code UserSecurityRepository} for the USRSEC KSDS lookup,
 * {@code ProgramRegistry} for the dynamic-CALL routing to the two XCTL
 * targets {@code COADM01C} / {@code COMEN01C}, and a {@code Clock} for
 * deterministic {@code FUNCTION CURRENT-DATE} replacement per AAP
 * &sect;0.6.4). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against file-backed
 * adapters wired to the auxiliary fixture returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>DFHCOMMAREA Nullable-Parameter Translation</h2>
 *
 * <p>The COBOL {@code LINKAGE SECTION DFHCOMMAREA} declaration at
 * {@code app/cbl/COSGN00C.cbl:L65-L67} (specifically {@code 05
 * LK-COMMAREA PIC X(01) OCCURS 1 TO 32767 TIMES DEPENDING ON EIBCALEN})
 * is variable-length per the CICS pseudo-conversational pattern: on
 * initial entry {@code EIBCALEN = 0} (no prior commarea) and the program
 * tests this at {@code app/cbl/COSGN00C.cbl:L80} ({@code IF EIBCALEN =
 * 0}) to branch to the initial-render path; on subsequent calls
 * {@code EIBCALEN} carries the byte length of the populated
 * {@code CARDDEMO-COMMAREA} from
 * {@code app/cpy/COCOM01Y.cpy:&sect;CARDDEMO-COMMAREA}. The Java
 * translation maps this directly to a nullable
 * {@link com.blitzy.carddemo.domain.commarea.CardDemoCommarea}
 * parameter: a {@code null} value encodes the COBOL {@code EIBCALEN = 0}
 * initial-entry case, while a non-null value carries the prior
 * commarea state. This translation is the canonical way to render
 * COBOL {@code OCCURS DEPENDING ON} on a {@code LINKAGE SECTION} block
 * whose presence depends on the call context (per AAP &sect;0.6.3).</p>
 *
 * <h2>Plaintext Password Preservation (AAP &sect;0.1.3)</h2>
 *
 * <p>This is the <strong>defining behavioural invariant</strong> of the
 * COSGN00C parity test, and the most security-critical invariant in the
 * entire golden-record harness. The COBOL program at
 * {@code app/cbl/COSGN00C.cbl:L221-L223} performs a direct plaintext
 * comparison: {@code IF SEC-USR-PWD = WS-USER-PWD} where both sides are
 * {@code PIC X(08)} EBCDIC byte sequences; there is NO hashing, salting,
 * peppering, key-stretching, or any cryptographic transformation
 * anywhere in the signon flow. The {@code SEC-USER-DATA} record layout
 * at {@code app/cpy/CSUSR01Y.cpy:L17-L23} stores the password verbatim
 * as {@code SEC-USR-PWD PIC X(08)} at offset 48 of the 80-byte record
 * (offset 0&#x2192;7 = SEC-USR-ID, 8&#x2192;27 = SEC-USR-FNAME,
 * 28&#x2192;47 = SEC-USR-LNAME, 48&#x2192;55 = SEC-USR-PWD,
 * 56 = SEC-USR-TYPE, 57&#x2192;79 = SEC-USR-FILLER). Per AAP &sect;0.1.3
 * the Java translation preserves this behaviour exactly: {@code SEC-USR-PWD}
 * bytes are read verbatim from the {@code usrsec.txt} fixture, compared
 * verbatim against the supplied password (after the COBOL-equivalent
 * {@link String#toUpperCase(java.util.Locale)} normalization with
 * {@link java.util.Locale#ROOT} mirroring {@code FUNCTION UPPER-CASE} at
 * {@code app/cbl/COSGN00C.cbl:L132-L137}), and NO password hashing
 * (BCrypt, Argon2, PBKDF2, scrypt) is introduced. The decision to move
 * USRSEC to a hashed storage scheme is explicitly OUT OF SCOPE for this
 * refactor (flagged in {@code java/MIGRATION_NOTES.md} for a follow-up
 * effort). The {@code usrsec.txt} auxiliary input fixture carries the
 * pre-test 80-byte {@code SEC-USER-DATA} records with the plaintext
 * password bytes intact at offset 48 of each record; any byte-level
 * mismatch on this column breaks AAP &sect;0.1.3 and blocks the PR.
 * Because COSGN00C is <strong>strictly read-only</strong> (no
 * {@code REWRITE}, no {@code WRITE}, no {@code SYNCPOINT}), the
 * post-test state of {@code usrsec.txt} MUST be byte-identical to its
 * pre-test state &mdash; any divergence indicates a regression.</p>
 *
 * <h2>NO Password in Logs (AAP &sect;0.7.2)</h2>
 *
 * <p>The complement to the plaintext-storage invariant: the password
 * value MUST NEVER appear in the {@code stdout.txt} capture. Per AAP
 * &sect;0.1.3 storage and logging are <em>different surfaces</em>
 * &mdash; the password is preserved verbatim in {@code SEC-USR-PWD}
 * within {@code USRSEC} (the canonical credential store), but the
 * SLF4J logger inside
 * {@link com.blitzy.carddemo.application.signon.CoSgn00C} NEVER
 * receives the plaintext value as an argument to any
 * {@code log.info(...)} / {@code log.warn(...)} /
 * {@code log.error(...)} call (only the user-id appears in log
 * messages, and only on the success path; on the
 * "Wrong Password. Try again ..." failure path at
 * {@code app/cbl/COSGN00C.cbl:L241-L246} the message is logged WITHOUT
 * the attempted plaintext value). The {@code bms_output.txt} capture
 * serializes the BMS screen state including the {@code PASSWD} field
 * at BMS position (20, 43) which carries the {@code DRK} (dark)
 * attribute per {@code app/bms/COSGN00.bms:L175-L180} &mdash; the 3270
 * terminal renderer hides the password from the operator's eye, but
 * the bytes ARE present in the serialized screen buffer (this is
 * COBOL's existing behaviour, preserved verbatim under AAP &sect;0.7.1
 * "preserve current behavior &hellip; with minimal risk"). For
 * {@code stdout.txt}, any byte sequence resembling a plaintext
 * password breaks AAP &sect;0.7.2 and blocks the PR unconditionally
 * (PCI-relevant control: audit logging masking).</p>
 *
 * <h2>User-Type Dispatch (Sealed Taxonomy)</h2>
 *
 * <p>Successful signon routes the operator via {@code EXEC CICS XCTL}
 * to one of two menu programs based on the matched user's
 * {@code SEC-USR-TYPE PIC X(01)} field (per the COBOL
 * {@code app/cbl/COSGN00C.cbl:L227-L240} block):</p>
 * <ul>
 *   <li><strong>{@code SEC-USR-TYPE = 'A'}</strong> (Admin) &rarr;
 *       {@code EXEC CICS XCTL PROGRAM('COADM01C') COMMAREA(CARDDEMO-
 *       COMMAREA)} per {@code app/cbl/COSGN00C.cbl:L230-L234}. The
 *       Java translation routes this through
 *       {@code ProgramRegistry.invoke("COADM01C", commarea)} returning
 *       {@code Outcome.Dispatched(commarea)}.</li>
 *   <li><strong>{@code SEC-USR-TYPE = 'U'}</strong> (User) or any
 *       other non-&apos;A&apos; value &rarr; {@code EXEC CICS XCTL
 *       PROGRAM('COMEN01C') COMMAREA(CARDDEMO-COMMAREA)} per
 *       {@code app/cbl/COSGN00C.cbl:L235-L239}. The Java translation
 *       routes this through
 *       {@code ProgramRegistry.invoke("COMEN01C", commarea)} returning
 *       {@code Outcome.Dispatched(commarea)}.</li>
 * </ul>
 *
 * <p>Per AAP &sect;0.6.10 the closed business taxonomy is realized as a
 * {@link com.blitzy.carddemo.domain.commarea.UserType} sealed
 * hierarchy with permits {@code Admin} (matching &apos;A&apos;) and
 * {@code User} (matching &apos;U&apos;), and the dispatch site uses an
 * exhaustive pattern-matching {@code switch} with NO {@code default}
 * branch (compiler-enforced exhaustiveness per AAP &sect;0.6.7). The
 * captured {@code stdout.txt} carries the SLF4J line identifying which
 * XCTL target fired (with the user-id but NOT the password).</p>
 *
 * <h2>Test Scenario</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cosgn00c/expected/} encodes a
 * sequence of five terminal submissions exercising the full state space
 * of the signon flow:</p>
 * <ol>
 *   <li><strong>Initial entry</strong> ({@code EIBCALEN = 0} encoded as
 *       a null commarea per the DFHCOMMAREA translation above) &mdash;
 *       triggers the COBOL {@code MOVE LOW-VALUES TO COSGN0AO} +
 *       {@code MOVE -1 TO USERIDL} + {@code PERFORM SEND-SIGNON-SCREEN}
 *       block at {@code app/cbl/COSGN00C.cbl:L80-L83}; renders the
 *       empty signon screen with cursor positioned on USERID.</li>
 *   <li><strong>Valid User signon</strong> (user-id &apos;USER0001&apos;,
 *       password &apos;PASSWORD&apos; matching the User record in
 *       {@code usrsec.txt} with {@code SEC-USR-TYPE='U'}) &mdash;
 *       triggers {@code PROCESS-ENTER-KEY} validation success +
 *       {@code READ-USER-SEC-FILE} with {@code WS-RESP-CD=0} +
 *       password-match success + {@code EXEC CICS XCTL
 *       PROGRAM('COMEN01C')} per
 *       {@code app/cbl/COSGN00C.cbl:L235-L239}. The captured stdout
 *       records the dispatch event WITHOUT the plaintext password.</li>
 *   <li><strong>Valid Admin signon</strong> (user-id &apos;ADMIN001&apos;,
 *       password &apos;ADMINPWD&apos; matching the Admin record in
 *       {@code usrsec.txt} with {@code SEC-USR-TYPE='A'}) &mdash;
 *       triggers {@code EXEC CICS XCTL PROGRAM('COADM01C')} per
 *       {@code app/cbl/COSGN00C.cbl:L230-L234}. The captured stdout
 *       records the dispatch event WITHOUT the plaintext password.</li>
 *   <li><strong>Invalid user-id</strong> (user-id &apos;NOSUCH00&apos;
 *       not present in {@code usrsec.txt}) &mdash; the
 *       {@code READ-USER-SEC-FILE} returns {@code WS-RESP-CD=13}
 *       (DFHRESP NOTFND); the program emits the verbatim
 *       {@code 'User not found. Try again ...'} message per
 *       {@code app/cbl/COSGN00C.cbl:L249} (preserved per AAP
 *       &sect;0.7.1) and re-renders the screen with cursor on USERID
 *       via {@code MOVE -1 TO USERIDL}.</li>
 *   <li><strong>Invalid password</strong> (user-id &apos;USER0001&apos;,
 *       password &apos;WRONGPWD&apos;) &mdash; the
 *       {@code READ-USER-SEC-FILE} returns {@code WS-RESP-CD=0} (record
 *       found) but {@code SEC-USR-PWD != WS-USER-PWD} so the program
 *       emits the verbatim {@code 'Wrong Password. Try again ...'}
 *       message per {@code app/cbl/COSGN00C.cbl:L242-L243} and
 *       re-renders the screen with cursor on PASSWD via
 *       {@code MOVE -1 TO PASSWDL}. CRITICAL: the captured
 *       {@code stdout.txt} for this submission carries NO byte sequence
 *       resembling either the supplied wrong password or the stored
 *       correct password per AAP &sect;0.7.2.</li>
 *   <li><strong>Blank user-id</strong> (empty USERIDI submission) &mdash;
 *       the {@code PROCESS-ENTER-KEY} validation at
 *       {@code app/cbl/COSGN00C.cbl:L117-L122} short-circuits before
 *       the USRSEC lookup and emits the verbatim {@code 'Please enter
 *       User ID ...'} message with cursor on USERIDL.</li>
 *   <li><strong>Blank password</strong> (user-id &apos;USER0001&apos;,
 *       empty PASSWDI submission) &mdash; the validation at
 *       {@code app/cbl/COSGN00C.cbl:L123-L127} short-circuits before
 *       the USRSEC lookup and emits the verbatim {@code 'Please enter
 *       Password ...'} message with cursor on PASSWDL.</li>
 * </ol>
 *
 * <p>The scenario ordering exercises blank validations (which run
 * BEFORE the USRSEC lookup per the COBOL {@code IF NOT ERR-FLG-ON
 * PERFORM READ-USER-SEC-FILE} guard at L138-L140), the three
 * {@code WS-RESP-CD} branches at L221-L257 (0/password-match,
 * 0/password-mismatch, 13/NOTFND), and both XCTL targets &mdash;
 * covering the full state space of the signon screen.</p>
 *
 * <h2>Verbatim Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>The following message literals MUST be preserved byte-for-byte by
 * the Java translation and appear in the captured fixtures. Each
 * carries the exact trailing {@code ...} ellipsis (note the variation
 * &mdash; some have a leading space before {@code ...}, some do not)
 * from the COBOL source; ANY normalization (trimming the ellipsis,
 * collapsing whitespace, recasing) breaks byte parity and blocks the
 * PR:</p>
 * <ul>
 *   <li>{@code 'Please enter User ID ...'} at
 *       {@code app/cbl/COSGN00C.cbl:L120} (blank USERIDI
 *       validation).</li>
 *   <li>{@code 'Please enter Password ...'} at
 *       {@code app/cbl/COSGN00C.cbl:L125} (blank PASSWDI
 *       validation).</li>
 *   <li>{@code 'Wrong Password. Try again ...'} at
 *       {@code app/cbl/COSGN00C.cbl:L242-L243} (WS-RESP-CD=0 with
 *       password mismatch).</li>
 *   <li>{@code 'User not found. Try again ...'} at
 *       {@code app/cbl/COSGN00C.cbl:L249} (WS-RESP-CD=13 NOTFND).</li>
 *   <li>{@code 'Unable to verify the User ...'} at
 *       {@code app/cbl/COSGN00C.cbl:L254} (any other non-zero,
 *       non-13 WS-RESP-CD; fallback I/O error path).</li>
 *   <li>{@code CCDA-MSG-INVALID-KEY} from {@code app/cpy/CSMSG01Y.cpy}
 *       emitted by the {@code WHEN OTHER} branch of the EIBAID
 *       {@code EVALUATE} at {@code app/cbl/COSGN00C.cbl:L91-L94}
 *       (any AID key other than ENTER or PF3 on the re-render
 *       path).</li>
 *   <li>{@code CCDA-MSG-THANK-YOU} from {@code app/cpy/CSMSG01Y.cpy}
 *       emitted on the PF3 path at
 *       {@code app/cbl/COSGN00C.cbl:L88-L90} ({@code WHEN DFHPF3}
 *       triggers {@code PERFORM SEND-PLAIN-TEXT} which ends the
 *       CICS session via {@code EXEC CICS RETURN} without a
 *       TRANSID).</li>
 * </ul>
 *
 * <h2>Auxiliary Input Fixture: {@code usrsec.txt}</h2>
 *
 * <p>One ASCII fixture is wired through {@link #auxiliaryInputs()} to
 * back the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
 * port: {@code usrsec.txt}, a synthesized fixture (not part of the
 * immutable {@code app/data/ASCII/} dataset since USRSEC was not
 * captured in the COBOL fixture set) carrying at least two pre-test
 * 80-byte {@code SEC-USER-DATA} records per
 * {@code app/cpy/CSUSR01Y.cpy:L17-L23} layout:</p>
 * <ol>
 *   <li>One Admin record with {@code SEC-USR-ID='ADMIN001'},
 *       {@code SEC-USR-PWD='ADMINPWD'}, {@code SEC-USR-TYPE='A'},
 *       backing the Valid Admin signon scenario submission (test
 *       step 3).</li>
 *   <li>One User record with {@code SEC-USR-ID='USER0001'},
 *       {@code SEC-USR-PWD='PASSWORD'}, {@code SEC-USR-TYPE='U'},
 *       backing the Valid User signon and Invalid password
 *       scenario submissions (test steps 2 and 5).</li>
 * </ol>
 *
 * <p>The plaintext password bytes at offset 48 of each record are the
 * canonical AAP &sect;0.1.3 preservation evidence: the file lives under
 * {@code src/test/resources/golden/cosgn00c/expected/} (not
 * {@code input/}) because it is shared scaffolding between the COBOL
 * capture run and the Java translation run &mdash; both must use the
 * identical USRSEC byte sequence to produce byte-identical outputs.
 * Because COSGN00C is strictly read-only (no {@code REWRITE}, no
 * {@code WRITE}, no {@code SYNCPOINT}), the post-test state of
 * {@code usrsec.txt} MUST be byte-identical to its pre-test state;
 * any divergence indicates a regression in the Java translation that
 * this harness's {@link GoldenRecordTest#byteForByteParity()} method
 * will detect.</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the single-output
 * default), this test declares TWO byte-for-byte parity targets:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the SLF4J/DISPLAY trace including
 *       the verbatim messages from AAP &sect;0.7.1 (with the trailing
 *       {@code ...} ellipsis preserved exactly). Per AAP &sect;0.7.2
 *       contains NO password bytes &mdash; the SLF4J logger inside
 *       {@link com.blitzy.carddemo.application.signon.CoSgn00C} NEVER
 *       receives the plaintext password value as an argument to any
 *       log call (only the user-id appears in log messages on the
 *       success path; on the failure path neither the supplied
 *       password nor the stored password appears).</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@link com.blitzy.carddemo.application.signon.CoSgn00C}
 *       {@code Outcome.Render.output()} screen states (one per
 *       re-render submission in the scenario). Each frame carries
 *       the standard header line ({@code TRNNAME} = &apos;CC00&apos;,
 *       {@code TITLE01} / {@code TITLE02} from
 *       {@code app/cpy/COTTL01Y.cpy}, {@code CURDATE},
 *       {@code PGMNAME} = &apos;COSGN00C&apos;, {@code CURTIME},
 *       {@code APPLID}, {@code SYSID}), the USERID input field
 *       (8 chars, GREEN, UNPROT per {@code app/bms/COSGN00.bms:L156-
 *       L160}), the PASSWD input field (8 chars, GREEN, DRK,
 *       UNPROT per L175-L180), the dollar-bill banner (lines 7-15,
 *       BLUE), the instruction line {@code 'Type your User ID and
 *       Password, then press ENTER:'} (line 17, TURQUOISE), and the
 *       ERRMSG status line (line 23, RED, BRT). The PASSWD column
 *       bytes ARE present in the serialized buffer (DRK only hides
 *       them from the terminal renderer per COBOL's existing
 *       behaviour preserved under AAP &sect;0.7.1).</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the password comparison semantics,
 * the XCTL target selection, the verbatim error messages, the
 * plaintext-storage preservation, the no-password-in-logs invariant,
 * or the screen sequencing breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11
 * ("Initial test scaffolding may use placeholder expected files
 * marked {@code @Disabled} until COBOL captures are available; the
 * harness skeleton, base class, and per-program test classes are
 * created unconditionally"), the {@link #byteForByteParity()} override
 * below is annotated {@code @Disabled} with a 7-point verification
 * reason citing the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled}
 * annotation will be removed in the same PR that commits
 * non-placeholder content under
 * {@code src/test/resources/golden/cosgn00c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.signon.CoSgn00C
 * @since 25
 */
@DisplayName("COSGN00C \u2014 Signon Golden-Record Parity (plaintext password preservation)")
public class CoSgn00CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COSGN00C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cosgn00c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cosgn00c/expected/}. Encodes the
     * multi-submission pseudo-conversation sequence (initial entry with
     * null commarea encoding {@code EIBCALEN = 0}, valid User signon
     * triggering XCTL to {@code COMEN01C}, valid Admin signon
     * triggering XCTL to {@code COADM01C}, invalid user-id producing
     * {@code 'User not found. Try again ...'}, invalid password
     * producing {@code 'Wrong Password. Try again ...'}, blank user-id
     * producing {@code 'Please enter User ID ...'}, blank password
     * producing {@code 'Please enter Password ...'}) consumed by the
     * harness orchestrator to drive the CoSgn00C online-CICS state
     * machine through the full state space of the signon transaction.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cosgn00c/expected/}. Records the
     * SLF4J/DISPLAY emissions including the verbatim messages from AAP
     * &sect;0.7.1 with the trailing {@code ...} ellipsis preserved
     * exactly. Per AAP &sect;0.7.2 the capture contains NO password
     * bytes whatsoever &mdash; the SLF4J logger NEVER receives the
     * plaintext {@code SEC-USR-PWD} value (nor the operator-supplied
     * password) as an argument to any log call. On the success paths
     * the user-id appears in log messages identifying which XCTL
     * target fired ({@code COADM01C} for Admin or {@code COMEN01C}
     * for User); on the failure paths the verbatim error message
     * appears WITHOUT either the supplied or the stored password
     * value.
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cosgn00c/expected/}. Records the
     * serialized {@code CoSgn00C} {@code Outcome.Render.output()}
     * screen states (one per re-render submission, concatenated in
     * submission order). Each frame carries the two user-facing input
     * fields {@code USERID} (8 chars, pos 19/43, GREEN, UNPROT per
     * {@code app/bms/COSGN00.bms:L156-L160}) and {@code PASSWD}
     * (8 chars, pos 20/43, GREEN, DRK, UNPROT per L175-L180) plus the
     * standard header line ({@code TRNNAME} = &apos;CC00&apos;,
     * {@code TITLE01} / {@code TITLE02} from {@code app/cpy/COTTL01Y.cpy},
     * {@code CURDATE}, {@code PGMNAME} = &apos;COSGN00C&apos;,
     * {@code CURTIME}, {@code APPLID}, {@code SYSID}), the dollar-bill
     * banner (lines 7-15, BLUE), the instruction line
     * {@code 'Type your User ID and Password, then press ENTER:'}
     * (line 17, TURQUOISE), the {@code ERRMSG} status line (pos 23/1,
     * RED, BRT per L197-L200), and the keymap legend
     * {@code 'ENTER=Sign-on  F3=Exit'} at pos 24/1 per L201-L205. The
     * PASSWD field bytes ARE present in the serialized buffer (DRK
     * only hides them from the terminal renderer per COBOL's existing
     * behaviour preserved under AAP &sect;0.7.1).
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the auxiliary input fixture under
     * {@code src/test/resources/golden/cosgn00c/expected/} that backs
     * the {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port. Contains the pre-test state of the {@code USRSEC} dataset:
     * at least two 80-byte {@code SEC-USER-DATA} records per
     * {@code app/cpy/CSUSR01Y.cpy:L17-L23} layout
     * ({@code SEC-USR-ID PIC X(08)} + {@code SEC-USR-FNAME PIC X(20)}
     * + {@code SEC-USR-LNAME PIC X(20)} + {@code SEC-USR-PWD PIC X(08)}
     * + {@code SEC-USR-TYPE PIC X(01)} + {@code SEC-USR-FILLER PIC
     * X(23)} = 80 bytes). The fixture includes one Admin record
     * ({@code SEC-USR-TYPE='A'}) and one User record
     * ({@code SEC-USR-TYPE='U'}) with known plaintext passwords at
     * offset 48 of each record per AAP &sect;0.1.3 plaintext-password
     * preservation requirement. The fixture lives under
     * {@code expected/} (not {@code input/}) because it is test-owned
     * scaffolding shared between COBOL capture and Java translation.
     * Because COSGN00C is strictly read-only (no {@code REWRITE}, no
     * {@code WRITE}, no {@code SYNCPOINT}), the post-test state MUST
     * be byte-identical to the pre-test state &mdash; the harness's
     * {@code runProgram(...)} hook copies the fixture into a temp
     * directory before the run so the source-controlled file is never
     * mutated in place.
     */
    private static final String USRSEC_TXT = "usrsec.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.signon.CoSgn00C}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope dependency on {@code carddemo-application}
     * (transitively via {@code carddemo-app}) per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.signon.CoSgn00C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cosgn00c/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the multi-
     * submission CICS pseudo-conversation that drives the CoSgn00C
     * online-state machine through the full state space (initial
     * empty entry with null commarea, valid User signon, valid Admin
     * signon, invalid user-id, invalid password, blank user-id, blank
     * password); it lives alongside the expected outputs under the
     * per-program {@code cosgn00c/} subtree because it is a harness-
     * internal fixture (not part of the immutable
     * {@code app/data/ASCII/} dataset).</p>
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
     * {@code src/test/resources/golden/cosgn00c/expected/stdout.txt},
     * resolved via {@link GoldenRecordTest#resolveExpectedOutputPath(
     * String, String)}. This is retained for harness backward
     * compatibility (single-output convention); the actual byte-for-
     * byte parity assertions iterate the multi-element list returned
     * by {@link #expectedOutputs()} rather than this single path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable single-element {@link List} of auxiliary
     * input fixture paths reflecting the COSGN00C collaborator
     * surface: just the USRSEC user-security KSDS backing the
     * {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port. The fixture
     * ({@code src/test/resources/golden/cosgn00c/expected/usrsec.txt})
     * carries the pre-test state of the USRSEC dataset: two 80-byte
     * {@code SEC-USER-DATA} records per
     * {@code app/cpy/CSUSR01Y.cpy:L17-L23} (one Admin with
     * {@code SEC-USR-TYPE='A'}, one User with
     * {@code SEC-USR-TYPE='U'}, each with the known plaintext
     * passwords at offset 48 per AAP &sect;0.1.3). Per the
     * {@code EXEC CICS READ DATASET('USRSEC  ')} construct at
     * {@code app/cbl/COSGN00C.cbl:L211-L219}, COSGN00C is strictly
     * read-only against USRSEC: the post-test state of the fixture
     * MUST be byte-identical to the pre-test state, so the harness's
     * {@code runProgram(...)} hook may safely point the
     * {@code FileUserSecurityRepository} adapter directly at the
     * fixture without copying. Returned list is
     * {@link List#of(Object)} immutable.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(resolveExpectedOutputPath(PROGRAM_DIR, USRSEC_TXT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for COSGN00C.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently,
     * identifying any mismatched output by name in the AssertJ
     * failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       SLF4J/DISPLAY trace including the verbatim messages from
     *       AAP &sect;0.7.1 (with the trailing {@code ...} ellipsis
     *       preserved exactly). Per AAP &sect;0.7.2 contains NO
     *       password bytes &mdash; the SLF4J logger inside
     *       {@link com.blitzy.carddemo.application.signon.CoSgn00C}
     *       NEVER receives the plaintext password value as an
     *       argument to any log call (only the user-id appears in
     *       log messages on the success and failure paths).</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code Outcome.Render.output()} screen
     *       states (one per re-render submission in the scenario).
     *       Each frame carries the two input fields ({@code USERID},
     *       {@code PASSWD} with DRK attribute) plus the standard
     *       header, the dollar-bill banner, the instruction line,
     *       the ERRMSG status line, and the keymap legend. The
     *       PASSWD column bytes are present in the serialized buffer
     *       (preserved COBOL behaviour per AAP &sect;0.7.1; the DRK
     *       attribute hides the field from the terminal renderer
     *       but the bytes remain in the screen buffer &mdash;
     *       storage and display are distinct surfaces per AAP
     *       &sect;0.1.3).</li>
     * </ol>
     *
     * <p>Note: {@code usrsec.txt} is NOT declared here even though
     * the fixture carries the plaintext password bytes &mdash; per
     * the read-only invariant of COSGN00C documented above, the
     * post-test state of USRSEC must match the pre-test state
     * byte-for-byte, which is verified implicitly by the absence of
     * any mutation channel in the
     * {@link com.blitzy.carddemo.domain.port.UserSecurityRepository}
     * port interface used by CoSgn00C (which exposes only the
     * {@code findById(String)} read method, no
     * {@code insert}/{@code update}/{@code delete} mutators).
     * Returned list is {@link List#of(Object, Object)} immutable.</p>
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
     * pending the COBOL COSGN00C baseline capture per AAP
     * &sect;0.6.11 ("Initial test scaffolding may use placeholder
     * expected files marked {@code @Disabled} until COBOL captures
     * are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the
     * same PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cosgn00c/expected/} per the
     * capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml}
     * dependencyManagement per AAP &sect;0.5.1), the JUnit
     * Platform's annotation lookup does NOT inherit {@code @Test}
     * when a subclass overrides a parent's {@code @Test}-annotated
     * method &mdash; running surefire with
     * {@code -Dtest=CoSgn00CGoldenTest} produces "Tests run: 0"
     * when {@code @Test} is omitted from the override but "Tests
     * run: 1, Skipped: 1" when re-declared. Without {@code @Test}
     * here, this test class would be silently dropped from the
     * test suite, defeating the AAP &sect;0.6.11 PR-gate purpose
     * of the harness skeleton. This pattern matches sibling
     * {@link CoUsr01CGoldenTest}, {@link CoUsr02CGoldenTest},
     * {@link CoUsr03CGoldenTest}, {@link CoActVwCGoldenTest},
     * {@link CoActUpCGoldenTest}, {@link CoMen01CGoldenTest}, and
     * all other online-program golden tests in this package.</p>
     *
     * @throws Exception if the program under test, the
     *                   {@link GoldenRecordTest#runProgram(Class,
     *                   java.nio.file.Path, java.util.List)} hook,
     *                   or any {@link java.nio.file.Files#readAllBytes(
     *                   java.nio.file.Path)} call fails
     */
    @Override
    @Test
    @Disabled(
        "Awaiting COBOL COSGN00C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 7 invariants must hold before "
            + "removing @Disabled): "
            + "(1) Valid User signon (SEC-USR-TYPE='U') XCTLs to "
            + "COMEN01C per app/cbl/COSGN00C.cbl:L235-L239 \u2014 the "
            + "captured stdout.txt records the dispatch event with the "
            + "user-id but NEVER the plaintext password per AAP "
            + "\u00a70.7.2; the bms_output.txt does not include a "
            + "post-XCTL render frame (XCTL ends the current program's "
            + "rendering contract). "
            + "(2) Valid Admin signon (SEC-USR-TYPE='A') XCTLs to "
            + "COADM01C per app/cbl/COSGN00C.cbl:L230-L234 \u2014 same "
            + "captured-output rules as (1) but with the dispatch "
            + "target verified as COADM01C and the user-id field "
            + "carrying the admin-record's primary key. The Java "
            + "translation uses the closed UserType sealed taxonomy "
            + "(Admin permits) per AAP \u00a70.6.10 with compiler-"
            + "enforced exhaustiveness in the pattern-matching switch "
            + "(no default branch per AAP \u00a70.6.7). "
            + "(3) Invalid user-id produces verbatim 'User not found. "
            + "Try again ...' per app/cbl/COSGN00C.cbl:L249 \u2014 "
            + "EXEC CICS READ returns WS-RESP-CD=13 (DFHRESP NOTFND); "
            + "the WHEN 13 branch of the EVALUATE WS-RESP-CD block at "
            + "L247-L251 sets WS-ERR-FLG='Y' and re-sends the signon "
            + "screen with cursor on USERIDL via MOVE -1. The trailing "
            + "' ...' ellipsis (with leading space) is preserved "
            + "verbatim per AAP \u00a70.7.1; any normalization "
            + "(trimming the ellipsis, collapsing whitespace, recasing) "
            + "breaks byte parity and blocks the PR. "
            + "(4) Invalid password produces verbatim 'Wrong Password. "
            + "Try again ...' per app/cbl/COSGN00C.cbl:L242-L243 "
            + "\u2014 EXEC CICS READ returns WS-RESP-CD=0 (record "
            + "found) but the IF SEC-USR-PWD = WS-USER-PWD comparison "
            + "at L223 fails; the ELSE branch at L241-L246 sets the "
            + "verbatim message and positions the cursor on PASSWDL "
            + "via MOVE -1. CRITICAL per AAP \u00a70.7.2: the captured "
            + "stdout.txt for this submission carries NO byte sequence "
            + "resembling either the supplied wrong password or the "
            + "stored correct password; the SLF4J logger NEVER receives "
            + "either value as a log argument (the wrong-password "
            + "failure event is logged with the user-id only). "
            + "(5) Blank inputs (empty USERIDI or empty PASSWDI) "
            + "short-circuit BEFORE the USRSEC lookup per the IF NOT "
            + "ERR-FLG-ON PERFORM READ-USER-SEC-FILE guard at "
            + "app/cbl/COSGN00C.cbl:L138-L140; the validations at "
            + "L117-L130 produce verbatim 'Please enter User ID ...' "
            + "(L120, blank USERIDI) or 'Please enter Password ...' "
            + "(L125, blank PASSWDI) with the cursor on the empty "
            + "field's *L attribute via MOVE -1. Only ONE error "
            + "message is shown at a time \u2014 the user-id blank "
            + "check runs BEFORE the password blank check per the "
            + "fixed COBOL EVALUATE TRUE ordering at L117-L130; the "
            + "FIRST blank wins. ANY normalization breaks byte parity "
            + "and blocks the PR. "
            + "(6) PLAINTEXT password preservation per AAP "
            + "\u00a70.1.3: the usrsec.txt auxiliary input fixture "
            + "carries the pre-test 80-byte SEC-USER-DATA records "
            + "with the plaintext password bytes at offset 48 of "
            + "each record per app/cpy/CSUSR01Y.cpy:L17-L23 "
            + "(SEC-USR-ID 0-7, SEC-USR-FNAME 8-27, SEC-USR-LNAME "
            + "28-47, SEC-USR-PWD 48-55, SEC-USR-TYPE 56, "
            + "SEC-USR-FILLER 57-79). The Java password comparison at "
            + "the IF SEC-USR-PWD = WS-USER-PWD analogue is performed "
            + "verbatim against the plaintext bytes \u2014 NO BCrypt, "
            + "NO Argon2, NO PBKDF2, NO scrypt transformation is "
            + "applied. The decision to migrate USRSEC to a hashed "
            + "scheme is explicitly OUT OF SCOPE for this refactor "
            + "and is flagged in MIGRATION_NOTES.md as a follow-up "
            + "effort. The post-test state of usrsec.txt MUST be "
            + "byte-identical to the pre-test state because COSGN00C "
            + "is strictly read-only against USRSEC (no REWRITE, no "
            + "WRITE, no SYNCPOINT); the UserSecurityRepository port "
            + "exposed to CoSgn00C provides only findById(String) and "
            + "has no mutator surface. "
            + "(7) Password NEVER appears in full in stdout.txt per "
            + "AAP \u00a70.7.2: the SLF4J logger inside CoSgn00C "
            + "NEVER receives the plaintext SEC-USR-PWD value (nor "
            + "the operator-supplied password) as an argument to any "
            + "log.info(...) / log.warn(...) / log.error(...) call "
            + "\u2014 only the user-id appears in log messages. The "
            + "captured stdout.txt contains NO password bytes; any "
            + "byte sequence resembling either the supplied or stored "
            + "password breaks AAP \u00a70.7.2 (PCI-relevant logging "
            + "masking control) and blocks the PR unconditionally. "
            + "(Note: bms_output.txt DOES carry the PASSWD field "
            + "bytes at BMS position (20,43) because the COSGN00 BMS "
            + "map's DRK attribute per app/bms/COSGN00.bms:L175-L180 "
            + "hides the field from the 3270 terminal renderer but "
            + "the bytes are present in the serialized screen buffer "
            + "per COBOL's existing behaviour preserved under AAP "
            + "\u00a70.7.1 \u2014 storage and logging are distinct "
            + "surfaces per AAP \u00a70.1.3.) "
            + "Additionally: the PF3 path at "
            + "app/cbl/COSGN00C.cbl:L88-L90 invokes SEND-PLAIN-TEXT "
            + "with the verbatim CCDA-MSG-THANK-YOU message and "
            + "ends the CICS session via EXEC CICS RETURN (no "
            + "TRANSID); the Java translation returns Outcome.Goodbye "
            + "carrying the thank-you message. The WHEN OTHER branch "
            + "of the EIBAID EVALUATE at L91-L94 emits the verbatim "
            + "CCDA-MSG-INVALID-KEY message and re-renders the "
            + "screen for any AID key other than ENTER or PF3. The "
            + "DFHCOMMAREA OCCURS DEPENDING ON EIBCALEN translation "
            + "maps EIBCALEN=0 to a null commarea parameter (initial "
            + "entry) and non-zero EIBCALEN to a populated "
            + "CardDemoCommarea per the variable-length LINKAGE "
            + "translation rule from AAP \u00a70.6.3."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
