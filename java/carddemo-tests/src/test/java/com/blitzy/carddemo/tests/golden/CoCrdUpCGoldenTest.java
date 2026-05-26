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
 * Byte-for-byte golden-record parity test for {@code COCRDUPC}
 * (Card Update Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COCRDUPC.cbl} &mdash; the
 * {@code PROGRAM-ID COCRDUPC} online-CICS program backing transaction
 * {@code CCUP} ({@code LIT-THISTRANID VALUE 'CCUP'} at
 * {@code app/cbl/COCRDUPC.cbl:L221}, mapset {@code COCRDUP}, map
 * {@code CCRDUPA}). The program performs a multi-pass card-detail UPDATE
 * flow over the CARDFILE (CARDDAT) VSAM KSDS via the
 * {@code READ ... UPDATE} &rarr; optimistic-concurrency check &rarr;
 * {@code REWRITE} pattern. It is the only card-domain program in
 * {@code app/cbl/} that issues a {@code REWRITE} against CARDDAT
 * (sibling COCRDLIC is a list, COCRDSLC is a read-only detail view).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.card.CoCrdUpC}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COCRDUPC is translated into the
 * {@code application/card/} subpackage co-located with its sibling
 * translations {@code CoCrdLiC} (card list) and {@code CoCrdSlC} (card view
 * &mdash; the read-only sibling). The Java translation has a 3-argument
 * constructor
 * {@code CoCrdUpC(CardRepository cardRepository,
 * CardXrefRepository cardXrefRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator
 * surface (one repository port per VSAM file plus the dynamic-CALL routing
 * facility carried as a collaborator for XCTL dispatch to
 * {@code COMEN01C} or {@code COCRDLIC}). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against
 * file-backed adapters wired to the auxiliary fixtures returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>READ + REWRITE Flow ({@code 9000-READ-DATA} &rarr;
 * {@code 9200-WRITE-PROCESSING})</h2>
 *
 * <p>The COBOL paragraph {@code 9000-READ-DATA} at
 * {@code app/cbl/COCRDUPC.cbl:L1343-L1372} invokes the inner paragraph
 * {@code 9100-GETCARD-BYACCTCARD} ({@code app/cbl/COCRDUPC.cbl:L1376-L1415})
 * to perform a {@code EXEC CICS READ FILE('CARDDAT')} keyed by
 * {@code WS-CARD-RID = WS-CARD-RID-CARDNUM (PIC X(16))
 * + WS-CARD-RID-ACCT-ID (PIC 9(11))}. On a successful read the program
 * caches the full 150-byte pre-image as the "OLD" snapshot used by the
 * downstream optimistic-concurrency check. The COBOL paragraph
 * {@code 9200-WRITE-PROCESSING} at
 * {@code app/cbl/COCRDUPC.cbl:L1420-L1494} then:</p>
 * <ol>
 *   <li><strong>Re-reads under lock</strong>
 *       ({@code EXEC CICS READ ... UPDATE}) acquiring the VSAM record
 *       lock on CARDDAT.</li>
 *   <li><strong>Optimistic-concurrency check</strong> via
 *       {@code 9300-CHECK-CHANGE-IN-REC} at
 *       {@code app/cbl/COCRDUPC.cbl:L1498-L1521} compares each of the six
 *       tracked fields (acct-id, card-num, name, status, exp-month,
 *       exp-year) of the locked record against the cached snapshot. The
 *       embossed-name comparison applies {@code FUNCTION UPPER-CASE} (or
 *       the {@code INSPECT ... CONVERTING LIT-LOWER TO LIT-UPPER}
 *       equivalent) so case-only differences do NOT count as a concurrent
 *       change. The Java translation mirrors this via
 *       {@link String#toUpperCase(java.util.Locale)} pinned to
 *       {@link java.util.Locale#ROOT}.</li>
 *   <li><strong>Reject on concurrent change</strong> &mdash; if any field
 *       differs the program emits the verbatim COBOL message
 *       {@code "Record changed by some one else. Please review"}
 *       (two-word "some one", preserved per AAP &sect;0.7.1) and returns
 *       to the BMS screen without {@code REWRITE}.</li>
 *   <li><strong>REWRITE</strong> &mdash; otherwise the program issues
 *       {@code EXEC CICS REWRITE} with the updated record image.
 *       Unchanged fields (CVV, customer-id foreign keys, etc.) preserve
 *       their pre-image byte-for-byte; only the four editable fields
 *       (name, status, exp-month, exp-year) carry the user input. On
 *       success the state transitions to
 *       {@code CCUP-CHANGES-OKAYED-AND-DONE} ({@code 'C'}) and INFOMSG
 *       becomes {@code "Changes committed to database"}.</li>
 * </ol>
 *
 * <p><strong>Implementation-hardening note on SYNCPOINT</strong> &mdash;
 * the COBOL COCRDUPC source uses {@code EXEC CICS SYNCPOINT} (commit) at
 * {@code app/cbl/COCRDUPC.cbl:L470} but does NOT use
 * {@code SYNCPOINT ROLLBACK}. Per AAP &sect;0.4.1 the SOLE
 * {@code SYNCPOINT ROLLBACK} in the entire {@code app/cbl/} tree lives
 * in {@code COACTUPC} (verified by sibling
 * {@link CoActUpCGoldenTest}). The Java translation
 * {@link com.blitzy.carddemo.application.card.CoCrdUpC} nevertheless
 * applies a compensating-write pattern on {@code REWRITE} failure as an
 * implementation-hardening decision documented in
 * {@code java/MIGRATION_NOTES.md} &sect;1.12.14; this defends against
 * partial-update inconsistency between the {@code CardRepository} write
 * site and the AID/screen acknowledgement returned to the operator.
 * Because COCRDUPC writes to ONLY one record (a single card detail),
 * this hardening does NOT exercise a cross-record rollback path the way
 * COACTUPC's two-REWRITE flow does.</p>
 *
 * <h2>Validation Chain ({@code 1210-EDIT-ACCOUNT} &mdash;
 * {@code 1260-EDIT-EXPIRY-YEAR})</h2>
 *
 * <p>Paragraph {@code 1200-EDIT-MAP-INPUTS} at
 * {@code app/cbl/COCRDUPC.cbl:L641-L717} runs the six-step validation
 * chain over the BMS input fields. Each paragraph sets a tri-state
 * {@code WS-EDIT-*-FLAG} ({@code '0'} = NOT_OK, {@code '1'} = ISVALID,
 * {@code ' '} = BLANK) and emits a verbatim error message:</p>
 * <ol>
 *   <li>{@code 1210-EDIT-ACCOUNT} (line 721) &mdash; account-id must be
 *       an 11-digit non-zero numeric;
 *       {@code "Account number must be a non zero 11 digit number"}
 *       (line 189) on failure.</li>
 *   <li>{@code 1220-EDIT-CARD} (line 762) &mdash; card-number must be a
 *       16-digit numeric;
 *       {@code "Card number if supplied must be a 16 digit number"}
 *       (line 193) on failure.</li>
 *   <li>{@code 1230-EDIT-NAME} (line 806) &mdash; cardholder name must
 *       contain only alphabetic characters and spaces (validated by an
 *       {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM
 *       TO LIT-ALL-SPACES-TO} sweep);
 *       {@code "Card name can only contain alphabets and spaces"}
 *       (line 183) on failure.</li>
 *   <li>{@code 1240-EDIT-CARDSTATUS} (line 845) &mdash; status must be
 *       {@code 'Y'} or {@code 'N'} (from the COBOL
 *       {@code FLG-YES-NO-VALID 88-level VALUES 'Y', 'N'});
 *       {@code "Card Active Status must be Y or N"} (line 195) on
 *       failure.</li>
 *   <li>{@code 1250-EDIT-EXPIRY-MON} (line 877) &mdash; month must
 *       satisfy {@code VALID-MONTH VALUES 1 THRU 12};
 *       {@code "Card expiry month must be between 1 and 12"} (line 197)
 *       on failure.</li>
 *   <li>{@code 1260-EDIT-EXPIRY-YEAR} (line 913) &mdash; year must
 *       satisfy {@code VALID-YEAR VALUES 1950 THRU 2099};
 *       {@code "Invalid card expiry year"} (line 199) on failure.</li>
 * </ol>
 *
 * <p>If ANY validation step fails the {@code CCUP-CHANGE-ACTION} field
 * transitions to {@code 'E'} ({@code ChangesNotOk}); the program loops
 * back to {@code 3000-SEND-MAP} WITHOUT issuing a {@code REWRITE}.
 * Verifying this NO-WRITE invariant is verification point&nbsp;(2) of
 * the activation checklist below.</p>
 *
 * <h2>AID-Key Dispatch ({@code 2000-DECIDE-ACTION})</h2>
 *
 * <p>Paragraph {@code 2000-DECIDE-ACTION} at
 * {@code app/cbl/COCRDUPC.cbl:L948-L1029} handles five valid AID keys
 * (see {@code PFK-VALID} semantics around COBOL line 414):</p>
 * <ul>
 *   <li>{@code ENTER} &mdash; process inputs (validate or save).</li>
 *   <li>{@code PFK03} (Exit) &mdash; {@code EXEC CICS XCTL
 *       PROGRAM(LIT-MENUPGM)} to {@code COMEN01C}; INFOMSG carries the
 *       exit acknowledgement.</li>
 *   <li>{@code PFK04} (Clear) &mdash; reset BMS map; redisplay empty
 *       form.</li>
 *   <li>{@code PFK05} (Save) &mdash; only when
 *       {@code CCUP-CHANGES-OK-NOT-CONFIRMED} ({@code 'N'}); invokes
 *       {@code 9200-WRITE-PROCESSING} to commit the validated changes
 *       to CARDDAT via {@code REWRITE}.</li>
 *   <li>{@code PFK12} (Cancel) &mdash; only when NOT
 *       {@code CCUP-DETAILS-NOT-FETCHED}; reverts the in-flight edit
 *       and re-displays the unmodified pre-image. <strong>The PF12
 *       cancel path MUST NOT issue {@code REWRITE}</strong> &mdash;
 *       this is verification point&nbsp;(4) of the activation checklist
 *       below and is asserted by the byte-for-byte parity check that
 *       confirms the post-test {@code carddata.txt} (the REWRITE result)
 *       reflects only the PFK05 saves, not the PFK12 cancels.</li>
 * </ul>
 *
 * <p>All other AID keys produce the verbatim
 * {@link com.blitzy.carddemo.domain.text.SystemMessages#INVALID_KEY_MSG}
 * preserved per AAP &sect;0.7.1.</p>
 *
 * <h2>PAN Masking Verification (AAP &sect;0.7.2)</h2>
 *
 * <p>The captured fixtures verify <strong>two separate code paths</strong>
 * with different PAN visibility:</p>
 * <ul>
 *   <li>{@code stdout.txt} &mdash; SLF4J / DISPLAY trace. Per AAP
 *       &sect;0.7.2 ("no card PAN logged in full; mask all but last 4
 *       digits in logs and error messages"), every log line mentioning
 *       a card number must show only the last 4 digits (12 leading
 *       mask characters such as {@code "************1234"}). The Java
 *       translation routes PAN through a masking helper before SLF4J
 *       emission; the captured stdout fixture asserts the masked form
 *       byte-for-byte.</li>
 *   <li>{@code bms_output.txt} &mdash; serialized
 *       {@code CoCrdUpOutput} screen states (one per submission in
 *       the scenario). The BMS screen displays the <strong>FULL</strong>
 *       PAN ({@code CARDSIDI PIC X(16)} in the BMS symbolic map
 *       {@code app/cpy-bms/COCRDUP.CPY}) because the user is
 *       authorized to view their own card data through the 3270
 *       terminal; the masking rule applies to logs only, not to
 *       authorized screen display. Storage and logging are different
 *       surfaces (per AAP &sect;0.1.1 surfaced implicit
 *       requirement).</li>
 * </ul>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cocrdupc/expected/} encodes a
 * sequence of terminal submissions exercising the full state space of
 * the update transaction:</p>
 * <ol>
 *   <li><strong>Initial fetch</strong> (first-time invocation,
 *       {@code EIBCALEN = 0}) &mdash; user supplies an account-id and
 *       card-number; paragraph {@code 1100-RECEIVE-MAP} populates the
 *       BMS map, paragraph {@code 9000-READ-DATA} performs the READ
 *       via {@code 9100-GETCARD-BYACCTCARD}, and the
 *       {@code DetailsNotFetched} &rarr; {@code ShowDetails} state
 *       transition occurs. INFOMSG =
 *       {@code "Details of selected card shown above"}.</li>
 *   <li><strong>Edit with valid fields</strong> (name, status,
 *       exp-month, exp-year all valid) &mdash; drives the
 *       {@code ShowDetails} &rarr; {@code ChangesOkNotConfirmed} state
 *       path; all six validation paragraphs succeed; INFOMSG =
 *       {@code "Changes validated.Press F5 to save"} (no space after
 *       the period, preserved per AAP &sect;0.7.1). <strong>No
 *       {@code REWRITE} occurs yet</strong> &mdash; the program waits
 *       for an explicit PF5 confirmation.</li>
 *   <li><strong>Edit with invalid fields</strong> (e.g.
 *       cardholder-name containing digits, status='X', exp-month=13,
 *       exp-year=1949) &mdash; drives the {@code ShowDetails} &rarr;
 *       {@code ChangesNotOk} state transition; paragraphs
 *       {@code 1230-EDIT-NAME} / {@code 1240-EDIT-CARDSTATUS} /
 *       {@code 1250-EDIT-EXPIRY-MON} / {@code 1260-EDIT-EXPIRY-YEAR}
 *       reject the inputs with verbatim COBOL error messages; NO
 *       {@code REWRITE} occurs; the on-disk state remains unchanged.
 *       (Verification point&nbsp;(2) of the activation checklist.)</li>
 *   <li><strong>PF5 save</strong> on the previously-validated form
 *       &mdash; drives the {@code ChangesOkNotConfirmed} &rarr;
 *       {@code ChangesOkayedAndDone} state path through paragraph
 *       {@code 9200-WRITE-PROCESSING}; the optimistic-concurrency
 *       check in {@code 9300-CHECK-CHANGE-IN-REC} passes (no
 *       concurrent modification injected by the test); the
 *       {@code REWRITE} succeeds. Expected outcome:
 *       {@code carddata.txt} reflects the new image of the updated
 *       slot with unmodified fields byte-identical to the
 *       pre-image (verification point&nbsp;(1)); {@code stdout.txt}
 *       contains {@code "Changes committed to database"} with PAN
 *       masked (verification point&nbsp;(3)).</li>
 *   <li><strong>PF3 back</strong> &mdash; the user presses
 *       {@code PFK03} (Exit); the program XCTLs to {@code COMEN01C}
 *       per {@code LIT-MENUPGM} at
 *       {@code app/cbl/COCRDUPC.cbl:L235}; NO {@code REWRITE}
 *       occurs.</li>
 *   <li><strong>PF4 clear</strong> &mdash; user clears the form; the
 *       BMS map is reset to a blank state; NO {@code REWRITE}
 *       occurs.</li>
 *   <li><strong>PF12 cancel</strong> on a partially-edited form
 *       &mdash; <strong>MUST NOT issue {@code REWRITE}</strong>; the
 *       program reverts the in-flight edit and re-displays the
 *       unmodified pre-image. The post-test {@code carddata.txt}
 *       reflects ONLY the PFK05 saves above, NOT this cancel.
 *       (Verification point&nbsp;(4) of the activation checklist
 *       below &mdash; the most important PFK12 invariant.)</li>
 * </ol>
 *
 * <h2>Auxiliary Input Fixtures</h2>
 *
 * <p>Two ASCII fixtures from {@code app/data/ASCII/} are wired through
 * {@link #auxiliaryInputs()} to back the READ + REWRITE flow:</p>
 * <ol>
 *   <li>{@code carddata.txt} &mdash; CARDFILE (CARDDAT) baseline, 50
 *       card records at 150 bytes each per
 *       {@code app/cpy/CVACT02Y.cpy:&sect;CARD-RECORD}; random access
 *       by composite key {@code CARD-NUM PIC X(16) + ACCT-ID
 *       PIC 9(11)} in paragraph {@code 9100-GETCARD-BYACCTCARD}; the
 *       REWRITE target.</li>
 *   <li>{@code cardxref.txt} &mdash; CARDXREF cross-reference, 50
 *       50-byte records per
 *       {@code app/cpy/CVACT03Y.cpy:&sect;CARD-XREF-RECORD};
 *       supports the {@link
 *       com.blitzy.carddemo.domain.port.CardXrefRepository}
 *       constructor dependency.</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.11 the fixtures are read
 * DIRECTLY from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash;
 * NOT copied into {@code java/carddemo-tests/} (the COBOL source tree
 * remains the single source of truth for fixture data). Because
 * COCRDUPC mutates CARDDAT via {@code REWRITE}, the post-test state of
 * {@code carddata.txt} (the REWRITE output) is asserted byte-for-byte
 * against the captured {@code cocrdupc/expected/carddata.txt} fixture
 * via the multi-output {@link #expectedOutputs()} declaration. The
 * auxiliary {@code cardxref.txt} is read-only and MUST be
 * byte-identical to its pre-test state after the run; the harness's
 * runProgram(...) hook copies the inputs into a temp directory before
 * the run so any inadvertent in-place mutation does NOT pollute the
 * immutable {@code app/data/ASCII/} fixtures.</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the single-output
 * default), this test declares THREE byte-for-byte parity targets:</p>
 * <ol>
 *   <li>{@code carddata.txt} &mdash; the updated CARDFILE after all
 *       PFK05 saves in the scenario. Verifies the
 *       {@code 9200-WRITE-PROCESSING} {@code REWRITE} produces a
 *       byte-identical record-level update versus the COBOL
 *       baseline.</li>
 *   <li>{@code stdout.txt} &mdash; the PAN-masked SLF4J/DISPLAY
 *       trace. Verifies the AAP &sect;0.7.2 logging policy (last 4
 *       digits only).</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@code CoCrdUpOutput} screen states with FULL PAN. One
 *       serialized state per submission in the scenario, concatenated
 *       in submission order. Sequence ordering is preserved exactly
 *       because the user-facing view depends on the strict
 *       submit-then-render protocol of the CICS pseudo-conversational
 *       pattern.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the READ + REWRITE byte sequence, the
 * verbatim COBOL error messages, the optimistic-concurrency comparison
 * outcomes, the PAN-masking behavior in logs, the PFK12-MUST-NOT-REWRITE
 * invariant, or the screen sequencing breaks parity and blocks the
 * PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below is
 * annotated {@code @Disabled} with a 4-point verification reason citing
 * the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * under {@code src/test/resources/golden/cocrdupc/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.card.CoCrdUpC
 * @since 25
 */
@DisplayName("COCRDUPC \u2014 Card Update Golden-Record Parity")
public class CoCrdUpCGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COCRDUPC} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cocrdupc";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cocrdupc/expected/}. Encodes the
     * multi-pass submission sequence (initial fetch, edit-valid,
     * edit-invalid, PFK05 save, PFK03 back, PFK04 clear, PFK12 cancel)
     * consumed by the harness orchestrator to drive the CoCrdUpC
     * online-CICS state machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured CARDDAT post-image under
     * {@code src/test/resources/golden/cocrdupc/expected/}. The expected
     * state of the CARDFILE after the scenario's {@code REWRITE}
     * operations have been applied; the PFK05 saves write through, the
     * PFK12 cancels do NOT (verification point&nbsp;(4) of the
     * activation checklist).
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cocrdupc/expected/}. Records the
     * PAN-masked SLF4J/DISPLAY emissions for the scenario. Per AAP
     * &sect;0.7.2 every PAN reference in this file shows the last 4
     * digits only (12 leading mask characters).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cocrdupc/expected/}. Records the
     * serialized {@code CoCrdUpOutput} screen states (one per
     * submission, concatenated in submission order). Per AAP
     * &sect;0.7.2 these records display FULL PAN because the BMS screen
     * is an authorized rendering surface distinct from the SLF4J
     * logging surface.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the card cross-reference fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
     * constructor dependency of
     * {@link com.blitzy.carddemo.application.card.CoCrdUpC}. Read
     * directly via {@link GoldenRecordTest#resolveAppDataPath(String)}
     * &mdash; NOT copied into this module per AAP &sect;0.4.1 and
     * &sect;0.6.11.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.card.CoCrdUpC}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope dependency on {@code carddemo-application}
     * in {@code java/carddemo-tests/pom.xml} (per AAP &sect;0.5.1).</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.card.CoCrdUpC.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cocrdupc/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the multi-pass
     * submission sequence (initial fetch, edit-valid, edit-invalid,
     * PFK05 save, PFK03 back, PFK04 clear, PFK12 cancel) for the
     * online-CICS pseudo-conversational test; it lives alongside the
     * expected outputs under the per-program {@code cocrdupc/} subtree
     * because it is a harness-internal fixture (not part of the
     * immutable {@code app/data/ASCII/} dataset).</p>
     */
    @Override
    protected Path inputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, INPUT_SCENARIO_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to the captured CARDFILE
     * post-image at
     * {@code src/test/resources/golden/cocrdupc/expected/carddata.txt}
     * resolved via {@link GoldenRecordTest#resolveExpectedOutputPath(
     * String, String)}. This is retained for harness backward
     * compatibility (single-output convention); the actual
     * byte-for-byte parity assertions iterate the multi-element list
     * returned by {@link #expectedOutputs()} rather than this single
     * path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, CARDDATA_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable 2-element {@link List} of auxiliary input
     * fixture paths reflecting the COCRDUPC collaborator surface:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/carddata.txt} &mdash; CARDFILE
     *       (CARDDAT) baseline (50 card records at 150 bytes each per
     *       {@code app/cpy/CVACT02Y.cpy}). Random read+REWRITE by
     *       composite key in paragraph
     *       {@code 9100-GETCARD-BYACCTCARD}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.CardRepository}
     *       constructor dependency.</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; CARDXREF
     *       cross-reference (50 50-byte records per
     *       {@code app/cpy/CVACT03Y.cpy}). Backs the
     *       {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
     *       constructor dependency.</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object, Object)}
     * immutable to preserve deterministic ordering. Ordering matters
     * here because the harness's
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration
     * hook wires the supplied fixtures to the file-based repository
     * adapters in declaration order; reordering would change which
     * adapter binds which fixture and break the READ+REWRITE
     * collaborator graph.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(
            resolveAppDataPath(CARDDATA_TXT),
            resolveAppDataPath(CARDXREF_TXT)
        );
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the three byte-for-byte parity targets for COCRDUPC.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure
     * message.</p>
     * <ol>
     *   <li>{@link #CARDDATA_TXT} ({@code carddata.txt}) &mdash; the
     *       CARDFILE post-image after the scenario's
     *       {@code REWRITE} operations. Verifies the
     *       {@code 9200-WRITE-PROCESSING} writes the new field values
     *       in place while preserving every other byte of the 150-byte
     *       card record (CVV, customer-id foreign key, and the
     *       remaining FILLER bytes must match the pre-image
     *       exactly).</li>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2. Every
     *       PAN reference is masked to its last 4 digits before
     *       emission (12 leading mask characters such as
     *       {@code "************1234"}).</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoCrdUpOutput} screen states (one
     *       per submission in the scenario) with FULL PAN. The BMS
     *       screen is an authorized rendering surface distinct from
     *       the logging surface; PAN masking does NOT apply to this
     *       output per AAP &sect;0.7.2.</li>
     * </ol>
     *
     * <p>Returned list is {@link List#of(Object, Object, Object)}
     * immutable.</p>
     */
    @Override
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(
            new ExpectedOutput(CARDDATA_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, CARDDATA_TXT)),
            new ExpectedOutput(STDOUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT)),
            new ExpectedOutput(BMS_OUTPUT_TXT,
                resolveExpectedOutputPath(PROGRAM_DIR, BMS_OUTPUT_TXT))
        );
    }

    /**
     * Byte-for-byte parity assertion, currently {@code @Disabled}
     * pending the COBOL COCRDUPC baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cocrdupc/expected/} per the
     * capture procedure documented in
     * {@code java/MIGRATION_NOTES.md}. The method body delegates to
     * {@link GoldenRecordTest#byteForByteParity()} so the actual
     * byte-by-byte assertion logic remains centralised in the base
     * class.</p>
     *
     * <p><strong>Why the {@code @Test} annotation is re-declared on
     * this override</strong>: empirically verified against JUnit
     * Jupiter 5.13.1 (pinned in {@code java/pom.xml} dependencyManagement
     * per AAP &sect;0.5.1), the JUnit Platform's annotation lookup
     * does NOT inherit {@code @Test} when a subclass overrides a
     * parent's {@code @Test}-annotated method &mdash; running surefire
     * with {@code -Dtest=CoCrdUpCGoldenTest} produces "Tests run: 0"
     * when {@code @Test} is omitted from the override but "Tests run: 1,
     * Skipped: 1" when re-declared. Without {@code @Test} here, this
     * test class would be silently dropped from the test suite,
     * defeating the AAP &sect;0.6.11 PR-gate purpose of the harness
     * skeleton. This pattern matches sibling
     * {@link CbCus01CGoldenTest}, {@link CoActVwCGoldenTest}, and
     * {@link CoActUpCGoldenTest}.</p>
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
        "Awaiting COBOL COCRDUPC baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 4 must hold before removing "
            + "@Disabled): "
            + "(1) READ + REWRITE preserves all unmodified fields "
            + "byte-for-byte: the 9200-WRITE-PROCESSING REWRITE at "
            + "app/cbl/COCRDUPC.cbl:L1420-L1494 mutates ONLY the four "
            + "editable fields (name, status, exp-month, exp-year); "
            + "all other bytes of the 150-byte CARD-RECORD (CVV, "
            + "card-num, acct-id, customer-id foreign key, FILLER) "
            + "must match the pre-image exactly. "
            + "(2) Validation rejects invalid fields without REWRITE: "
            + "the six-step validation chain (1210-EDIT-ACCOUNT through "
            + "1260-EDIT-EXPIRY-YEAR at app/cbl/COCRDUPC.cbl:L721-L945) "
            + "must transition CCUP-CHANGE-ACTION to 'E' "
            + "(ChangesNotOk) on any failure and loop back to "
            + "3000-SEND-MAP WITHOUT issuing REWRITE; the verbatim "
            + "COBOL error messages (\"Card name can only contain "
            + "alphabets and spaces\", \"Card Active Status must be Y "
            + "or N\", \"Card expiry month must be between 1 and 12\", "
            + "\"Invalid card expiry year\") are preserved per AAP "
            + "\u00a70.7.1. "
            + "(3) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in full in "
            + "bms_output.txt (the BMS screen is an authorized "
            + "rendering surface distinct from the logging surface). "
            + "(4) PF12 cancel does NOT REWRITE: the PFK12 dispatch "
            + "in 2000-DECIDE-ACTION (app/cbl/COCRDUPC.cbl:L948-L1029) "
            + "short-circuits the save path; no REWRITE occurs; the "
            + "post-test carddata.txt reflects ONLY the PFK05 saves, "
            + "NOT the PFK12 cancels. This is the most important "
            + "invariant for the update-then-cancel flow."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
