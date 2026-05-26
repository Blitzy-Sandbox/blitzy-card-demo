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
 * Byte-for-byte golden-record parity test for {@code COCRDSLC}
 * (Card View Detail Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COCRDSLC.cbl} &mdash; the
 * {@code PROGRAM-ID COCRDSLC} online-CICS program backing transaction
 * {@code CCDL} ({@code LIT-THISTRANID VALUE 'CCDL'} at
 * {@code app/cbl/COCRDSLC.cbl:L165-L166}, mapset {@code COCRDSL}, map
 * {@code CCRDSLA}). The program is <strong>strictly read-only</strong>:
 * it accepts an account-id + card-number filter pair from the BMS screen,
 * performs a single random read against the CARDFILE (CARDDAT) VSAM KSDS
 * via the {@code 9100-GETCARD-BYACCTCARD} primary-key read path, and
 * sends back a populated {@code CCRDSLA} BMS map. It never modifies any
 * record (no {@code REWRITE}, no {@code WRITE}, no {@code SYNCPOINT}) and
 * is the simplest of the three card-domain online programs (sibling
 * {@code COCRDLIC} is a paginated list, sibling {@code COCRDUPC} is a
 * READ + REWRITE update).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.card.CoCrdSlC}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COCRDSLC is translated into the
 * {@code application/card/} subpackage co-located with its sibling
 * translations {@code CoCrdLiC} (card list) and {@code CoCrdUpC} (card
 * update). The Java translation has a 3-argument constructor
 * {@code CoCrdSlC(CardRepository cardRepository,
 * CardXrefRepository cardXrefRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator
 * surface (one repository port per VSAM file plus the dynamic-CALL
 * routing facility carried as a collaborator for XCTL dispatch to
 * {@code COMEN01C} or {@code COCRDLIC}). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against
 * file-backed adapters wired to the auxiliary fixtures returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>Two Read Paths: 9100 (Primary Key) and 9150 (Alternate Index)</h2>
 *
 * <p>The COBOL paragraph {@code 9000-READ-DATA} at
 * {@code app/cbl/COCRDSLC.cbl:L726-L732} invokes the inner paragraph
 * {@code 9100-GETCARD-BYACCTCARD} ({@code app/cbl/COCRDSLC.cbl:L736-L775})
 * to perform a {@code EXEC CICS READ FILE('CARDDAT')} keyed by the
 * composite primary key
 * {@code WS-CARD-RID-CARDNUM (PIC X(16)) + WS-CARD-RID-ACCT-ID
 * (PIC 9(11))}. The paragraph {@code 9150-GETCARD-BYACCT}
 * ({@code app/cbl/COCRDSLC.cbl:L779-L810}) is defined to read CARDDAT
 * via the {@code CARDAIX} alternate index (keyed by {@code XREF-ACCT-ID
 * PIC 9(11)} alone) but in the COBOL source it is NOT invoked from
 * {@code 9000-READ-DATA}; per AAP &sect;0.4.1 and the
 * {@code java/MIGRATION_NOTES.md} translation-decision entry, the Java
 * translation
 * {@link com.blitzy.carddemo.application.card.CoCrdSlC} additionally
 * wires the 9150 path when the user supplies only the account-id filter
 * (card-number blank), giving meaningful behavior in that case rather
 * than silently rejecting it. This implementation-hardening decision is
 * exercised by the test scenario below.</p>
 *
 * <p>The two read paths share the {@code EVALUATE WS-RESP-CD} dispatch
 * at {@code app/cbl/COCRDSLC.cbl:L752-L772} (for 9100) and L793-L808
 * (for 9150). On {@code DFHRESP(NORMAL)} the lookup succeeds and the
 * BMS map is populated. On {@code DFHRESP(NOTFND)} the program sets the
 * 88-level {@code DID-NOT-FIND-ACCTCARD-COMBO} ({@code "Did not find
 * cards for this search condition"} at {@code app/cbl/COCRDSLC.cbl:L154})
 * for 9100 or {@code DID-NOT-FIND-ACCT-IN-CARDXREF} ({@code "Did not
 * find this account in cards database"} at L152) for 9150 and
 * re-displays the screen with the verbatim error message. On any other
 * {@code WS-RESP-CD} value the program sets {@code XREF-READ-ERROR}
 * ({@code "Error reading Card Data File"} at L156) and re-displays.</p>
 *
 * <h2>XCTL Targets</h2>
 *
 * <p>The {@link com.blitzy.carddemo.application.card.CoCrdSlC.Outcome.Xctl}
 * outcome carries one of two target program-ids translating the
 * {@code EXEC CICS XCTL} paths in {@code COCRDSLC.cbl} (lines 318-345):</p>
 * <ul>
 *   <li>{@code COMEN01C} ({@code LIT-MENUPGM} at
 *       {@code app/cbl/COCRDSLC.cbl:L179-L180}) &mdash; menu return on
 *       {@code PFK03} (Exit), corresponding to the verbatim
 *       {@code "PF03 pressed.Exiting              "} message (no space
 *       after the period, 14 trailing spaces preserved per AAP
 *       &sect;0.7.1; literal at
 *       {@code app/cbl/COCRDSLC.cbl:L137}).</li>
 *   <li>{@code COCRDLIC} ({@code LIT-CCLISTPGM} at
 *       {@code app/cbl/COCRDSLC.cbl:L171-L172}) &mdash; card-list
 *       return when the program was XCTL'd from the card list
 *       (condition {@code CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM} at
 *       {@code app/cbl/COCRDSLC.cbl:L340}). In this case the user
 *       presses Enter to navigate back to the list rather than to the
 *       main menu.</li>
 * </ul>
 *
 * <h2>Validation Chain ({@code 2210-EDIT-ACCOUNT},
 * {@code 2220-EDIT-CARD})</h2>
 *
 * <p>Paragraph {@code 2000-PROCESS-INPUTS} invokes
 * {@code 2200-EDIT-MAP-INPUTS} which in turn runs the two-step
 * validation chain over the BMS input fields. Each paragraph sets a
 * tri-state {@code WS-EDIT-*-FLAG} ({@code '0'} = NOT_OK,
 * {@code '1'} = ISVALID, {@code ' '} = BLANK) and emits a verbatim
 * error message:</p>
 * <ol>
 *   <li>{@code 2210-EDIT-ACCOUNT} at
 *       {@code app/cbl/COCRDSLC.cbl:L640-L681} &mdash; account-id, if
 *       supplied, must be a non-zero 11-digit numeric. On the
 *       blank-and-both-blank case the 88-level
 *       {@code WS-PROMPT-FOR-ACCT} is asserted (literal
 *       {@code "Account number not provided"} at
 *       {@code app/cbl/COCRDSLC.cbl:L139}). On non-numeric input the
 *       literal
 *       {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"}
 *       is MOVE'd at {@code app/cbl/COCRDSLC.cbl:L670} (comma-no-space
 *       is intentional and preserved per AAP &sect;0.7.1).</li>
 *   <li>{@code 2220-EDIT-CARD} at
 *       {@code app/cbl/COCRDSLC.cbl:L685-L720} &mdash; card-number, if
 *       supplied, must be a non-zero 16-digit numeric. On the
 *       blank-and-both-blank case the 88-level
 *       {@code WS-PROMPT-FOR-CARD} is asserted (literal
 *       {@code "Card number not provided"} at
 *       {@code app/cbl/COCRDSLC.cbl:L141}). On non-numeric input the
 *       literal
 *       {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"}
 *       is MOVE'd at {@code app/cbl/COCRDSLC.cbl:L711} (comma-no-space
 *       is intentional and preserved per AAP &sect;0.7.1).</li>
 * </ol>
 *
 * <p>If both filters are blank the 88-level
 * {@code NO-SEARCH-CRITERIA-RECEIVED} fires (literal
 * {@code "No input received"} at the COBOL declaration). If ANY
 * validation step fails the program loops back to {@code 1000-SEND-MAP}
 * with the error message overlaid on the screen.</p>
 *
 * <h2>AID-Key Dispatch</h2>
 *
 * <p>The COBOL {@code EVALUATE TRUE} at
 * {@code app/cbl/COCRDSLC.cbl:L304-L381} dispatches on the AID key
 * pressed by the operator:</p>
 * <ul>
 *   <li>{@code ENTER} &mdash; process inputs; if both filters supplied
 *       and valid, invoke {@code 9000-READ-DATA} (the 9100 primary-key
 *       path). The Java translation additionally takes the 9150
 *       alternate-index path when only the account-id is supplied.</li>
 *   <li>{@code PFK03} (Exit) &mdash; {@code EXEC CICS XCTL
 *       PROGRAM(LIT-MENUPGM)} to {@code COMEN01C}; emits the verbatim
 *       {@code "PF03 pressed.Exiting              "} exit message (no
 *       space after the period, 14 trailing spaces preserved per AAP
 *       &sect;0.7.1).</li>
 *   <li>All other AID keys &mdash; produce the verbatim
 *       {@link com.blitzy.carddemo.domain.text.SystemMessages#INVALID_KEY_MSG}
 *       ({@code "Invalid key pressed"}) preserved per AAP
 *       &sect;0.7.1.</li>
 * </ul>
 *
 * <p>The Java translation's {@link
 * com.blitzy.carddemo.application.card.CoCrdSlC.Outcome} sealed
 * interface (permits {@code SendMap} and {@code Xctl}) replaces the
 * COBOL CICS verbs {@code EXEC CICS SEND MAP} / {@code EXEC CICS
 * RETURN} and {@code EXEC CICS XCTL} respectively; the exhaustive
 * pattern-matching switch over the sixteen {@code AidKey} permits
 * enforces compile-time completeness per AAP &sect;0.6.7 (no
 * {@code default} branch).</p>
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
 *       translation routes PAN through the {@code maskPan} helper
 *       (documented at
 *       {@link com.blitzy.carddemo.application.card.CoCrdSlC}) before
 *       SLF4J emission; the captured stdout fixture asserts the masked
 *       form byte-for-byte.</li>
 *   <li>{@code bms_output.txt} &mdash; serialized
 *       {@link com.blitzy.carddemo.application.card.CoCrdSlOutput}
 *       screen states (one per submission in the scenario). The BMS
 *       screen displays the <strong>FULL</strong> PAN
 *       ({@code CARDSIDI PIC X(16)} in the BMS symbolic map
 *       {@code app/cpy-bms/COCRDSL.CPY}) because the user is authorized
 *       to view their own card data through the 3270 terminal; the
 *       masking rule applies to logs only, not to authorized screen
 *       display. Storage and logging are different surfaces (per AAP
 *       &sect;0.1.1 surfaced implicit requirement).</li>
 * </ul>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cocrdslc/expected/} encodes a
 * sequence of terminal submissions exercising the full state space of
 * the read-only card view transaction:</p>
 * <ol>
 *   <li><strong>Blank entry</strong> (first-time invocation,
 *       {@code EIBCALEN = 0}) &mdash; the user supplies neither filter;
 *       paragraph {@code 2200-EDIT-MAP-INPUTS} fires both
 *       {@code WS-PROMPT-FOR-ACCT} and {@code WS-PROMPT-FOR-CARD};
 *       INFOMSG carries the
 *       {@code NO-SEARCH-CRITERIA-RECEIVED} prompt with the verbatim
 *       {@code "Account number not provided"} /
 *       {@code "Card number not provided"} messages preserved per AAP
 *       &sect;0.7.1.</li>
 *   <li><strong>Valid account-id + valid card-number</strong>
 *       (e.g. account {@code "00000000010"} +
 *       card {@code "4111111111111234"} from
 *       {@code app/data/ASCII/carddata.txt}) &mdash; triggers the 9100
 *       primary-key read path; populates the 9-field BMS map
 *       (account-id, card-number, name, status, expiration
 *       month/year); returns
 *       {@link com.blitzy.carddemo.application.card.CoCrdSlC.Outcome.SendMap}
 *       with the populated screen state.</li>
 *   <li><strong>Valid account-id, blank card-number</strong>
 *       &mdash; exercises the Java-translation-only 9150 alternate-index
 *       read path (per the implementation-hardening note above). The
 *       CARDAIX lookup recovers the card-number from the cross-reference
 *       fixture {@code cardxref.txt}; the resolved card record is then
 *       fetched from CARDDAT. The post-test state of both fixtures MUST
 *       be byte-identical to their pre-test state (read-only
 *       invariant).</li>
 *   <li><strong>Invalid account-id, valid card-number</strong>
 *       (e.g. account {@code "99999999999"} not present in
 *       {@code carddata.txt}) &mdash; the 9100 lookup fails with
 *       {@code NOTFND}; the program emits the verbatim
 *       {@code "Did not find cards for this search condition"} message
 *       and re-displays the screen.</li>
 *   <li><strong>Non-numeric account-id</strong>
 *       (e.g. {@code "ABC12345678"}) &mdash; paragraph
 *       {@code 2210-EDIT-ACCOUNT} rejects the input with the verbatim
 *       {@code "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"}
 *       (comma-no-space) message at COBOL line 670; NO read occurs.</li>
 *   <li><strong>Non-numeric card-number</strong>
 *       (e.g. {@code "4111ABCD11111234"}) &mdash; paragraph
 *       {@code 2220-EDIT-CARD} rejects the input with the verbatim
 *       {@code "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"}
 *       (comma-no-space) message at COBOL line 711; NO read occurs.</li>
 *   <li><strong>PF3 back</strong> &mdash; the user presses
 *       {@code PFK03} (Exit); the program emits the verbatim
 *       {@code "PF03 pressed.Exiting              "} message (no space
 *       after the period, 14 trailing spaces preserved per AAP
 *       &sect;0.7.1) and returns
 *       {@link com.blitzy.carddemo.application.card.CoCrdSlC.Outcome.Xctl}
 *       with {@code targetProgram = "COMEN01C"} per
 *       {@code LIT-MENUPGM} at
 *       {@code app/cbl/COCRDSLC.cbl:L179-L180}.</li>
 *   <li><strong>Invalid AID key (e.g. PFK07)</strong> &mdash; the
 *       {@code EVALUATE TRUE} dispatch falls through to the
 *       {@code WHEN OTHER} branch at
 *       {@code app/cbl/COCRDSLC.cbl:L373} and emits the verbatim
 *       {@link com.blitzy.carddemo.domain.text.SystemMessages#INVALID_KEY_MSG}
 *       ({@code "Invalid key pressed"}) preserved per AAP
 *       &sect;0.7.1.</li>
 * </ol>
 *
 * <h2>Auxiliary Input Fixtures</h2>
 *
 * <p>Two ASCII fixtures from {@code app/data/ASCII/} are wired through
 * {@link #auxiliaryInputs()} to back the two read paths:</p>
 * <ol>
 *   <li>{@code carddata.txt} &mdash; CARDFILE (CARDDAT) baseline, 50
 *       card records at 150 bytes each per
 *       {@code app/cpy/CVACT02Y.cpy:&sect;CARD-RECORD}; random read by
 *       composite key {@code CARD-NUM PIC X(16) + ACCT-ID PIC 9(11)}
 *       in paragraph {@code 9100-GETCARD-BYACCTCARD}. Backs the
 *       {@link com.blitzy.carddemo.domain.port.CardRepository}
 *       constructor dependency.</li>
 *   <li>{@code cardxref.txt} &mdash; CARDXREF cross-reference, 50
 *       50-byte records per
 *       {@code app/cpy/CVACT03Y.cpy:&sect;CARD-XREF-RECORD}; random
 *       read via the {@code CARDAIX} alternate index keyed by
 *       {@code XREF-ACCT-ID PIC 9(11)} in paragraph
 *       {@code 9150-GETCARD-BYACCT}. Backs the
 *       {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
 *       constructor dependency.</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.11 the fixtures are read
 * DIRECTLY from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash;
 * NOT copied into {@code java/carddemo-tests/} (the COBOL source tree
 * remains the single source of truth for fixture data). Because
 * COCRDSLC is strictly read-only (no {@code REWRITE}, no {@code WRITE},
 * no {@code SYNCPOINT}), the post-test state of BOTH fixtures MUST be
 * byte-identical to their pre-test state; any divergence indicates a
 * regression in the Java translation that the harness's
 * {@link GoldenRecordTest#byteForByteParity()} method will detect via
 * the read-only invariant.</p>
 *
 * <h2>Expected Outputs (multi-output scenario)</h2>
 *
 * <p>Per AAP &sect;0.6.11 multi-output pattern (overriding
 * {@link #expectedOutputs()} rather than relying on the single-output
 * default), this test declares TWO byte-for-byte parity targets:</p>
 * <ol>
 *   <li>{@code stdout.txt} &mdash; the PAN-masked SLF4J/DISPLAY trace.
 *       Verifies the AAP &sect;0.7.2 logging policy (last 4 digits
 *       only).</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@link com.blitzy.carddemo.application.card.CoCrdSlOutput}
 *       screen states with FULL PAN. One serialized state per
 *       submission in the scenario, concatenated in submission order.
 *       Sequence ordering is preserved exactly because the user-facing
 *       view depends on the strict submit-then-render protocol of the
 *       CICS pseudo-conversational pattern.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the 9100/9150 read-path selection,
 * the verbatim COBOL error messages (including the comma-no-space
 * filter validation messages), the PAN-masking behavior in logs, the
 * XCTL target selection on PFK03, the read-only fixture invariant, or
 * the screen sequencing breaks parity and blocks the PR.</p>
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
 * under {@code src/test/resources/golden/cocrdslc/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.card.CoCrdSlC
 * @since 25
 */
@DisplayName("COCRDSLC \u2014 Card View Golden-Record Parity")
public class CoCrdSlCGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COCRDSLC} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cocrdslc";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cocrdslc/expected/}. Encodes the
     * eight-submission sequence (blank entry; valid acct+card; valid
     * acct, blank card via 9150; invalid acct+card; non-numeric acct;
     * non-numeric card; PFK03 back; invalid AID key) consumed by the
     * harness orchestrator to drive the CoCrdSlC online-CICS state
     * machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cocrdslc/expected/}. Records the
     * PAN-masked SLF4J/DISPLAY emissions for the scenario. Per AAP
     * &sect;0.7.2 every PAN reference in this file shows the last 4
     * digits only (12 leading mask characters).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cocrdslc/expected/}. Records the
     * serialized {@code CoCrdSlOutput} screen states (one per
     * submission, concatenated in submission order). Per AAP
     * &sect;0.7.2 these records display FULL PAN because the BMS screen
     * is an authorized rendering surface distinct from the SLF4J
     * logging surface.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the CARDFILE baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardRepository} used by
     * paragraph {@code 9100-GETCARD-BYACCTCARD} (the primary-key read
     * path). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the CARDXREF cross-reference fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardXrefRepository} used
     * by paragraph {@code 9150-GETCARD-BYACCT} (the alternate-index
     * read path). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.card.CoCrdSlC}{@code .class}.
     * Referenced via fully-qualified class literal so this file's
     * import block stays minimal and restricted to
     * {@link java.nio.file.Path}, {@link java.util.List}, and the
     * JUnit Jupiter API annotations ({@link DisplayName},
     * {@link Disabled}, {@link Test}). The fully-qualified class
     * literal compiles cleanly because {@code carddemo-tests}
     * declares a test-scope dependency on {@code carddemo-application}
     * (transitively via {@code carddemo-app}) in
     * {@code java/carddemo-tests/pom.xml} per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.card.CoCrdSlC.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cocrdslc/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * eight-submission sequence (blank entry; valid acct+card; valid
     * acct, blank card via 9150; invalid acct+card; non-numeric acct;
     * non-numeric card; PFK03 back; invalid AID key) for the
     * online-CICS pseudo-conversational test; it lives alongside the
     * expected outputs under the per-program {@code cocrdslc/} subtree
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
     * <p>Returns the absolute {@link Path} to the captured COBOL stdout
     * trace at
     * {@code src/test/resources/golden/cocrdslc/expected/stdout.txt},
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
     * <p>Returns an immutable 2-element {@link List} of auxiliary input
     * fixture paths reflecting the COCRDSLC collaborator surface:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/carddata.txt} &mdash; CARDFILE
     *       (CARDDAT) baseline (50 card records at 150 bytes each per
     *       {@code app/cpy/CVACT02Y.cpy}). Random read by composite
     *       key in paragraph {@code 9100-GETCARD-BYACCTCARD}. Backs
     *       the {@link com.blitzy.carddemo.domain.port.CardRepository}
     *       constructor dependency.</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; CARDXREF
     *       cross-reference (50 50-byte records per
     *       {@code app/cpy/CVACT03Y.cpy}). Random read via the
     *       {@code CARDAIX} alternate index in paragraph
     *       {@code 9150-GETCARD-BYACCT}. Backs the
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
     * adapter binds which fixture and break the 9100/9150
     * read-path selection.</p>
     *
     * <p>Because COCRDSLC is strictly read-only both fixtures MUST be
     * byte-identical to their pre-test state after the run; the
     * harness's runProgram(...) hook copies the inputs into a temp
     * directory before the run so any inadvertent in-place mutation
     * does NOT pollute the immutable {@code app/data/ASCII/}
     * fixtures.</p>
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
     * <p>Declares the two byte-for-byte parity targets for COCRDSLC.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure
     * message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2. Every
     *       PAN reference is masked to its last 4 digits before
     *       emission (12 leading mask characters such as
     *       {@code "************1234"}).</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized
     *       {@link com.blitzy.carddemo.application.card.CoCrdSlOutput}
     *       screen states (one per submission in the scenario) with
     *       FULL PAN. The BMS screen is an authorized rendering
     *       surface distinct from the logging surface; PAN masking
     *       does NOT apply to this output per AAP &sect;0.7.2.</li>
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
     * pending the COBOL COCRDSLC baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cocrdslc/expected/} per the
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
     * running surefire with {@code -Dtest=CoCrdSlCGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest}, and
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
        "Awaiting COBOL COCRDSLC baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 4 must hold before removing "
            + "@Disabled): "
            + "(1) Valid acct+card returns full card detail via the 9100 "
            + "primary-key read path (paragraph 9100-GETCARD-BYACCTCARD "
            + "at app/cbl/COCRDSLC.cbl:L736-L775); the 9-field BMS map "
            + "(account-id, card-number, name, status, expiration "
            + "month/year) is populated byte-for-byte in bms_output.txt. "
            + "Invalid acct+card returns the verbatim COBOL error "
            + "messages \"Did not find cards for this search condition\" "
            + "(88-level DID-NOT-FIND-ACCTCARD-COMBO at "
            + "app/cbl/COCRDSLC.cbl:L153-L154) on 9100 NOTFND, \"Did "
            + "not find this account in cards database\" "
            + "(DID-NOT-FIND-ACCT-IN-CARDXREF at L151-L152) on 9150 "
            + "NOTFND, and \"Error reading Card Data File\" "
            + "(XREF-READ-ERROR at L155-L156) on any other WS-RESP-CD "
            + "value, all preserved per AAP \u00a70.7.1. "
            + "(2) Validation chain rejects malformed filters without "
            + "performing a read: paragraph 2210-EDIT-ACCOUNT at "
            + "app/cbl/COCRDSLC.cbl:L640-L681 emits the verbatim "
            + "\"ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER\" "
            + "(literal MOVE'd at COCRDSLC.cbl:L670, comma-no-space "
            + "intentional and preserved per AAP \u00a70.7.1) on "
            + "non-numeric account-id, and \"Account number not "
            + "provided\" (88-level WS-PROMPT-FOR-ACCT at L138-L139) "
            + "on blank-and-both-blank; paragraph 2220-EDIT-CARD at "
            + "L685-L720 emits \"CARD ID FILTER,IF SUPPLIED MUST BE A "
            + "16 DIGIT NUMBER\" (literal at L711, comma-no-space) on "
            + "non-numeric card-number, and \"Card number not "
            + "provided\" (WS-PROMPT-FOR-CARD at L140-L141) on "
            + "blank-and-both-blank. No read occurs on validation "
            + "failure. "
            + "(3) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in full in "
            + "bms_output.txt (the BMS screen is an authorized "
            + "rendering surface distinct from the logging surface). "
            + "(4) Read-only invariant: COCRDSLC never modifies any "
            + "record (no REWRITE, no WRITE, no SYNCPOINT), so the "
            + "two auxiliary fixtures (carddata.txt, cardxref.txt) "
            + "MUST be byte-identical to their pre-test state after "
            + "the run; any divergence indicates a regression. The "
            + "PFK03 exit path emits the verbatim "
            + "\"PF03 pressed.Exiting              \" (no space after "
            + "the period, 14 trailing spaces; literal at "
            + "app/cbl/COCRDSLC.cbl:L137) and XCTLs to COMEN01C "
            + "(LIT-MENUPGM at L179-L180); the invalid-AID path emits "
            + "SystemMessages.INVALID_KEY_MSG \"Invalid key pressed\" "
            + "per AAP \u00a70.7.1 (no rewrite, no XCTL)."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
