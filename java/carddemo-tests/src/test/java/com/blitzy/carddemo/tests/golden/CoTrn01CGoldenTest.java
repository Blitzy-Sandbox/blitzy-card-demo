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
 * Byte-for-byte golden-record parity test for {@code COTRN01C}
 * (Transaction View Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COTRN01C.cbl} &mdash; the
 * {@code PROGRAM-ID COTRN01C} ({@code app/cbl/COTRN01C.cbl:L22-L23})
 * online-CICS program backing transaction {@code CT01} ({@code WS-TRANID
 * PIC X(04) VALUE 'CT01'} at {@code app/cbl/COTRN01C.cbl:L37}, mapset
 * {@code COTRN01} via {@code COPY COTRN01.} at
 * {@code app/cbl/COTRN01C.cbl:L63}). The program is the
 * <strong>single-transaction detail viewer</strong>: it accepts a
 * 16-character {@code TRNIDIN} key from the BMS screen, performs ONE
 * random read against the {@code TRANSACT} VSAM KSDS
 * ({@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} at
 * {@code app/cbl/COTRN01C.cbl:L39}), projects all 14 fields of the
 * 350-byte CVTRA05Y {@code TRAN-RECORD} onto the BMS map {@code COTRN1A},
 * and re-displays the screen. It is the natural drill-through target
 * from the sibling {@code COTRN00C} transaction-list program (which
 * XCTLs here with {@code CDEMO-CT01-TRN-SELECTED} populated). It is
 * <strong>strictly read-only at the observable level</strong> even
 * though the COBOL {@code EXEC CICS READ} carries an {@code UPDATE}
 * clause (see "Read-Only Despite UPDATE Clause" below).</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.transaction.CoTrn01C}. Per AAP
 * &sect;0.4.1 (program-by-program mapping) COTRN01C is translated into
 * the {@code application/transaction/} subpackage co-located with its
 * sibling translations {@code CoTrn00C} (transaction list, transaction
 * {@code CT00}) and {@code CoTrn02C} (transaction add, transaction
 * {@code CT02}). The Java translation has a 2-argument constructor
 * {@code CoTrn01C(TransactionRepository transactionRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator
 * surface (one repository port for the {@code TRANSACT} KSDS plus the
 * dynamic-CALL routing facility carried as a collaborator for
 * {@code EXEC CICS XCTL} dispatch to {@code COMEN01C} and
 * {@code COTRN00C}). The base harness
 * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against
 * file-backed adapters wired to the auxiliary fixture returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>Read-Only Despite UPDATE Clause (AAP &sect;0.7.1)</h2>
 *
 * <p>The COBOL paragraph {@code READ-TRANSACT-FILE} at
 * {@code app/cbl/COTRN01C.cbl:L267-L296} issues
 * {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE) ... UPDATE} (line 275)
 * which on z/OS acquires a VSAM RLS exclusive lock and a snapshot of
 * the record &mdash; the conventional preamble to a {@code REWRITE}.
 * However, the COBOL source NEVER follows the read with
 * {@code EXEC CICS REWRITE}: the only fates of the {@code TRAN-RECORD}
 * variable are (a) projecting its fields onto {@code COTRN1AO} for
 * SEND MAP (lines 178-191) on a successful {@code DFHRESP(NORMAL)}, or
 * (b) discarding it on {@code DFHRESP(NOTFND)} with the verbatim
 * {@code "Transaction ID NOT found..."} message (lines 285-286), or
 * (c) discarding it on any other {@code RESP} code with the verbatim
 * {@code "Unable to lookup Transaction..."} message (lines 292-293) and
 * a {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} (line 290).
 * This is a copy-paste leftover from the sibling {@code COTRN02C}
 * (transaction add) template; per AAP &sect;0.7.1 ("If a COBOL paragraph
 * contains dead code or obvious bugs, translate it faithfully and flag
 * it in a MIGRATION_NOTES.md; do not 'fix' it in this refactor") the
 * Java translation
 * {@link com.blitzy.carddemo.application.transaction.CoTrn01C}
 * implements the read WITHOUT an {@code UPDATE}-equivalent lock and
 * documents the deviation in {@code java/MIGRATION_NOTES.md}. The
 * observable outcome &mdash; the {@code dailytran.txt} fixture remains
 * byte-identical to its pre-test state after the run &mdash; is
 * preserved exactly.</p>
 *
 * <h2>Verbatim Error-Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>Per AAP &sect;0.7.1 the following three message literals MUST be
 * preserved byte-for-byte by the Java translation and appear in the
 * captured fixtures. Each carries the exact trailing {@code ...}
 * ellipsis from the COBOL source; ANY normalization (trimming the
 * ellipsis, collapsing whitespace, recasing) breaks byte parity and
 * blocks the PR:</p>
 * <ul>
 *   <li>{@code 'Tran ID can NOT be empty...'} at
 *       {@code app/cbl/COTRN01C.cbl:L149} (emitted by paragraph
 *       {@code PROCESS-ENTER-KEY} when {@code TRNIDINI} is SPACES or
 *       LOW-VALUES; mixed-case "Tran ID can NOT" preserved
 *       verbatim).</li>
 *   <li>{@code 'Transaction ID NOT found...'} at
 *       {@code app/cbl/COTRN01C.cbl:L285-L286} (emitted by paragraph
 *       {@code READ-TRANSACT-FILE} on {@code DFHRESP(NOTFND)}; the
 *       record-not-found path).</li>
 *   <li>{@code 'Unable to lookup Transaction...'} at
 *       {@code app/cbl/COTRN01C.cbl:L292-L293} (emitted by paragraph
 *       {@code READ-TRANSACT-FILE} on any other {@code WS-RESP-CD}
 *       value; the capital {@code 'T'} in {@code 'Transaction'} is
 *       preserved per the COBOL source &mdash; distinguishing this
 *       message from the lowercase {@code 'transaction'} variant in
 *       sibling {@code COTRN00C}).</li>
 * </ul>
 *
 * <p>The catalog additionally includes {@code CCDA-MSG-INVALID-KEY}
 * ({@code 'Invalid key pressed. Please see below...'} from
 * {@code app/cpy/CSMSG01Y.cpy:L20-L21}, emitted by the
 * {@code WHEN OTHER} branch of the EIBAID {@code EVALUATE} at
 * {@code app/cbl/COTRN01C.cbl:L128-L131} for any AID key other than
 * ENTER, PF3, PF4, or PF5).</p>
 *
 * <h2>AID-Key Dispatch (Enter, PF3, PF4, PF5)</h2>
 *
 * <p>The AID-key validity check in the COBOL program at
 * {@code app/cbl/COTRN01C.cbl:L112-L132} accepts only four AID values;
 * any other AID falls through to the verbatim
 * {@code CCDA-MSG-INVALID-KEY} error message. The accepted AIDs are:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; invoke
 *       {@code PROCESS-ENTER-KEY}: validate {@code TRNIDIN} non-blank,
 *       perform {@code READ-TRANSACT-FILE} keyed by the 16-character
 *       value, project all 14 transaction fields onto the BMS map.</li>
 *   <li><strong>PF3 (Back)</strong> &mdash; emit
 *       {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} where
 *       {@code CDEMO-TO-PROGRAM} resolves to {@code CDEMO-FROM-PROGRAM}
 *       (the caller, typically {@code COTRN00C}) if non-blank, falling
 *       back to {@code COMEN01C} (the main menu) when
 *       {@code CDEMO-FROM-PROGRAM} is SPACES or LOW-VALUES per the
 *       conditional at {@code app/cbl/COTRN01C.cbl:L116-L121}. Note
 *       this is unique among the CT0x family &mdash; COTRN01 uses
 *       {@code CDEMO-FROM-PROGRAM} (not {@code CDEMO-TO-PROGRAM}) as
 *       the back target.</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; invoke
 *       {@code CLEAR-CURRENT-SCREEN} which {@code PERFORM}s
 *       {@code INITIALIZE-ALL-FIELDS} (MOVE SPACES to {@code TRNIDINI},
 *       {@code TRNIDI}, {@code CARDNUMI}, {@code TTYPCDI},
 *       {@code TCATCDI}, {@code TRNSRCI}, {@code TRNAMTI},
 *       {@code TDESCI}, {@code TORIGDTI}, {@code TPROCDTI},
 *       {@code MIDI}, {@code MNAMEI}, {@code MCITYI}, {@code MZIPI})
 *       and then {@code SEND-TRNVIEW-SCREEN}.</li>
 *   <li><strong>PF5 (Browse)</strong> &mdash; emit
 *       {@code EXEC CICS XCTL PROGRAM('COTRN00C')} to the
 *       transaction-browse list per
 *       {@code app/cbl/COTRN01C.cbl:L125-L127}. The BMS footer at
 *       {@code app/bms/COTRN01.bms} labels this as
 *       {@code "F5=Browse Tran."}.</li>
 * </ul>
 *
 * <p>The Java translation's {@link
 * com.blitzy.carddemo.application.transaction.CoTrn01C.Outcome} sealed
 * interface (permits {@code SendMap} and {@code Xctl}) replaces the
 * COBOL CICS verbs {@code EXEC CICS SEND MAP} / {@code EXEC CICS RETURN}
 * and {@code EXEC CICS XCTL} respectively; the exhaustive
 * pattern-matching switch over the sixteen {@code AidKey} permits
 * enforces compile-time completeness per AAP &sect;0.6.7 (no
 * {@code default} branch).</p>
 *
 * <h2>Auto-Trigger Flow from COTRN00C (AAP &sect;0.7.1)</h2>
 *
 * <p>The COBOL block at {@code app/cbl/COTRN01C.cbl:L99-L109} implements
 * a one-shot auto-fetch when {@code COTRN01C} is entered with
 * {@code CDEMO-PGM-REENTER = FALSE} (first invocation) AND
 * {@code CDEMO-CT01-TRN-SELECTED} populated to a non-blank, non-LOW-VALUES
 * value (the transaction id selected by the operator on the preceding
 * {@code COTRN00C} list screen via the row-selection {@code 'S'}
 * marker). The conditional at line 103-104
 * ({@code IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES})
 * copies the selected id to {@code TRNIDINI OF COTRN1AI} (line 105-106)
 * and immediately performs {@code PROCESS-ENTER-KEY} (line 107) before
 * SEND MAP &mdash; producing a populated detail screen on the first
 * frame rather than an empty entry screen. The Java translation
 * preserves this behavior by checking
 * {@link com.blitzy.carddemo.application.transaction.CoTrn01C}'s input
 * record's {@code trnIdIn} field for non-blank on first-pass entry and
 * auto-triggering the same code path as the explicit ENTER AID key.
 * The {@code input_scenario.txt} fixture exercises BOTH the manual
 * (operator typing) and auto-trigger (XCTL from COTRN00C) paths.</p>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cotrn01c/expected/} encodes a
 * sequence of terminal submissions exercising the full state space of
 * the read-only single-transaction view transaction:</p>
 * <ol>
 *   <li><strong>Initial empty entry</strong> ({@code EIBCALEN = 0},
 *       no commarea) &mdash; the program initializes the commarea,
 *       sets {@code CDEMO-TO-PROGRAM = 'COSGN00C'}, and invokes
 *       {@code RETURN-TO-PREV-SCREEN}; first-cycle SEND MAP shows an
 *       empty entry screen with cursor on {@code TRNIDINI}.</li>
 *   <li><strong>Valid 16-character tran-id lookup</strong> &mdash;
 *       enter a known transaction id from {@code dailytran.txt}
 *       (e.g.&nbsp;{@code 0000000000683580010001});
 *       {@code READ-TRANSACT-FILE} returns {@code DFHRESP(NORMAL)};
 *       all 14 fields project onto the BMS map; SEND MAP shows the
 *       populated detail screen.</li>
 *   <li><strong>Invalid 16-character tran-id</strong> (a 16-char
 *       value not in {@code dailytran.txt}, e.g.&nbsp;the all-9s
 *       {@code 9999999999999999}) &mdash; {@code READ-TRANSACT-FILE}
 *       returns {@code DFHRESP(NOTFND)}; the program emits the
 *       verbatim {@code 'Transaction ID NOT found...'} message and
 *       re-displays the screen.</li>
 *   <li><strong>Blank input</strong> &mdash; enter SPACES into
 *       {@code TRNIDIN}; {@code PROCESS-ENTER-KEY} emits the verbatim
 *       {@code 'Tran ID can NOT be empty...'} message at COBOL line
 *       149 and re-displays the screen.</li>
 *   <li><strong>PF4 (Clear)</strong> &mdash; press PF4 with populated
 *       fields; {@code CLEAR-CURRENT-SCREEN} blanks all 14 detail
 *       fields and the {@code TRNIDIN} input field; re-displays an
 *       empty entry screen.</li>
 *   <li><strong>PF5 (Browse)</strong> &mdash; press PF5; the
 *       outcome is {@code Outcome.Xctl} with target
 *       {@code 'COTRN00C'} (transaction-browse list).</li>
 *   <li><strong>PF3 (Back)</strong> with {@code CDEMO-FROM-PROGRAM =
 *       'COMEN01C'} &mdash; the outcome is {@code Outcome.Xctl} with
 *       target {@code 'COMEN01C'} (main menu); this is the
 *       {@code CDEMO-FROM-PROGRAM} branch.</li>
 *   <li><strong>Auto-trigger from COTRN00C</strong> &mdash; first-pass
 *       entry with {@code CDEMO-CT01-TRN-SELECTED} populated to a
 *       known id from {@code dailytran.txt}; first-cycle SEND MAP
 *       shows the populated detail screen WITHOUT requiring the
 *       operator to type the id (the COBOL block at L103-L108 fires).
 *       This exercises the auto-trigger code path documented in
 *       "Auto-Trigger Flow from COTRN00C" above.</li>
 *   <li><strong>Invalid AID key</strong> (e.g.&nbsp;PF1 or PA1) on
 *       a populated screen &mdash; the {@code WHEN OTHER} branch of
 *       the EIBAID {@code EVALUATE} at COBOL L128-L131 emits the
 *       verbatim {@code CCDA-MSG-INVALID-KEY} ({@code 'Invalid key
 *       pressed. Please see below...'}) and re-displays the
 *       screen.</li>
 * </ol>
 *
 * <p>Each submission produces a serialized {@code CoTrn01Output} screen
 * state plus zero or more SLF4J log lines; the harness concatenates all
 * screen states into {@code bms_output.txt} and all log lines into
 * {@code stdout.txt}. The two outputs are compared byte-for-byte to the
 * captured COBOL baseline.</p>
 *
 * <h2>Auxiliary Input Fixture</h2>
 *
 * <p>The single auxiliary fixture {@code app/data/ASCII/dailytran.txt}
 * backs the {@link com.blitzy.carddemo.domain.port.TransactionRepository}
 * port that COTRN01C reads via the single
 * {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE)} at COBOL line
 * 269-278. The COBOL program targets the {@code TRANSACT} VSAM KSDS (a
 * permanent transaction file), but the {@code app/data/ASCII/} directory
 * contains only {@code dailytran.txt} (the daily-transaction landing
 * file with the same 350-byte CVTRA05Y/CVTRA06Y record layout); per AAP
 * &sect;0.4.1, {@code dailytran.txt} is the canonical fixture for
 * transaction-stream tests and is read directly without copying.
 * Because COTRN01C is observably read-only despite the COBOL
 * {@code UPDATE} clause (see "Read-Only Despite UPDATE Clause" above),
 * the fixture MUST be byte-identical to its pre-test state after the
 * run; the harness's {@code runProgram(...)} hook copies the input
 * into a temp directory before the run so any inadvertent in-place
 * mutation does NOT pollute the immutable {@code app/data/ASCII/}
 * fixtures.</p>
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
 *       documented at
 *       {@link com.blitzy.carddemo.application.transaction.CoTrn01C}
 *       before SLF4J emission; the captured stdout fixture asserts
 *       the masked form byte-for-byte. Note the
 *       {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} at
 *       COBOL line 290 (the error path) is also captured in
 *       {@code stdout.txt} for parity.</li>
 *   <li>{@code bms_output.txt} &mdash; serialized
 *       {@code CoTrn01Output} screen states (one per submission in
 *       the scenario). The BMS screen displays the <strong>FULL</strong>
 *       PAN ({@code CARDNUMI PIC X(16)} in the BMS symbolic map
 *       {@code app/cpy-bms/COTRN01.CPY}) because the user is
 *       authorized to view their own transaction data through the
 *       3270 terminal; the masking rule applies to logs only, not to
 *       authorized screen display. Storage and logging are different
 *       surfaces (per AAP &sect;0.1.1 surfaced implicit
 *       requirement).</li>
 * </ul>
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
 *       {@code CoTrn01Output} screen states with FULL PAN. One
 *       serialized state per submission in the scenario, concatenated
 *       in submission order. Sequence ordering is preserved exactly
 *       because the user-facing view depends on the strict
 *       submit-then-render protocol of the CICS pseudo-conversational
 *       pattern.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the 16-character tran-id lookup, the
 * verbatim COBOL error messages (including the trailing
 * {@code ...} ellipses and the case-sensitive
 * {@code 'Transaction'} vs {@code 'transaction'} distinction from
 * sibling COTRN00C), the PAN-masking behavior in logs, the XCTL target
 * selection on PF3/PF5, the read-only fixture invariant despite the
 * COBOL {@code UPDATE} clause, the auto-trigger flow from COTRN00C
 * with {@code CDEMO-CT01-TRN-SELECTED}, or the screen sequencing
 * breaks parity and blocks the PR.</p>
 *
 * <p><strong>Scaffolding state</strong>: per AAP &sect;0.6.11 ("Initial
 * test scaffolding may use placeholder expected files marked
 * {@code @Disabled} until COBOL captures are available; the harness
 * skeleton, base class, and per-program test classes are created
 * unconditionally"), the {@link #byteForByteParity()} override below is
 * annotated {@code @Disabled} with a 6-point verification reason citing
 * the COBOL capture procedure documented in
 * {@code java/MIGRATION_NOTES.md}. The harness skeleton is
 * unconditionally present so JUnit discovers and reports this
 * per-program test in CI from day one. The {@code @Disabled} annotation
 * will be removed in the same PR that commits non-placeholder content
 * under {@code src/test/resources/golden/cotrn01c/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.transaction.CoTrn01C
 * @since 25
 */
@DisplayName("COTRN01C \u2014 Transaction View Golden-Record Parity")
public class CoTrn01CGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COTRN01C} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cotrn01c";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cotrn01c/expected/}. Encodes the
     * nine-submission sequence (empty entry; valid 16-char tran-id;
     * invalid tran-id; blank input; PF4 clear; PF5 browse; PF3 back;
     * auto-trigger from COTRN00C; invalid AID key) consumed by the
     * harness orchestrator to drive the CoTrn01C online-CICS state
     * machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cotrn01c/expected/}. Records the
     * PAN-masked SLF4J/DISPLAY emissions for the scenario (including
     * the {@code DISPLAY 'RESP:' ... 'REAS:' ...} on the
     * {@code WHEN OTHER} branch of READ-TRANSACT-FILE at COBOL line
     * 290). Per AAP &sect;0.7.2 every PAN reference in this file shows
     * the last 4 digits only (12 leading mask characters).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cotrn01c/expected/}. Records the
     * serialized {@code CoTrn01Output} screen states (one per
     * submission, concatenated in submission order). Per AAP
     * &sect;0.7.2 these records display FULL PAN because the BMS
     * screen is an authorized rendering surface distinct from the
     * SLF4J logging surface.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the DALYTRAN baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.TransactionRepository}
     * used by paragraph {@code READ-TRANSACT-FILE} (the single
     * {@code EXEC CICS READ DATASET(WS-TRANSACT-FILE)} call). Read
     * directly via {@link GoldenRecordTest#resolveAppDataPath(String)}
     * &mdash; NOT copied into this module per AAP &sect;0.4.1 and
     * &sect;0.6.11.
     */
    private static final String DAILYTRAN_TXT = "dailytran.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.transaction.CoTrn01C}{@code .class}.
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
        return com.blitzy.carddemo.application.transaction.CoTrn01C.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cotrn01c/expected/input_scenario.txt}
     * via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This synthesized scenario file encodes the nine-submission
     * sequence (empty entry; valid 16-char tran-id; invalid tran-id;
     * blank input; PF4 clear; PF5 browse; PF3 back; auto-trigger from
     * COTRN00C; invalid AID key) for the online-CICS
     * pseudo-conversational test; it lives alongside the expected
     * outputs under the per-program {@code cotrn01c/} subtree because
     * it is a harness-internal fixture (not part of the immutable
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
     * {@code src/test/resources/golden/cotrn01c/expected/stdout.txt},
     * resolved via
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}.
     * This is retained for harness backward compatibility (single-output
     * convention); the actual byte-for-byte parity assertions iterate
     * the multi-element list returned by {@link #expectedOutputs()}
     * rather than this single path.</p>
     */
    @Override
    protected Path expectedOutputFile() {
        return resolveExpectedOutputPath(PROGRAM_DIR, STDOUT_TXT);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns an immutable single-element {@link List} of auxiliary
     * input fixture paths reflecting the COTRN01C collaborator surface:
     * the daily transaction fixture {@code app/data/ASCII/dailytran.txt}
     * (350-byte fixed-width CVTRA05Y/CVTRA06Y records). Random read by
     * 16-character primary key {@code TRAN-ID PIC X(16)} in paragraph
     * {@code READ-TRANSACT-FILE} at
     * {@code app/cbl/COTRN01C.cbl:L267-L296}. Backs the
     * {@link com.blitzy.carddemo.domain.port.TransactionRepository}
     * constructor dependency injected into
     * {@link com.blitzy.carddemo.application.transaction.CoTrn01C}.</p>
     *
     * <p>Because COTRN01C is observably read-only despite the COBOL
     * {@code UPDATE} clause (see the "Read-Only Despite UPDATE Clause"
     * section of the class Javadoc above), the fixture MUST be
     * byte-identical to its pre-test state after the run; the
     * harness's {@code runProgram(...)} hook copies the input into a
     * temp directory before the run so any inadvertent in-place
     * mutation does NOT pollute the immutable {@code app/data/ASCII/}
     * fixtures.</p>
     */
    @Override
    protected List<Path> auxiliaryInputs() {
        return List.of(resolveAppDataPath(DAILYTRAN_TXT));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Declares the two byte-for-byte parity targets for COTRN01C.
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
     *       {@code "************1234"}). Also captures the
     *       {@code DISPLAY 'RESP:' ... 'REAS:' ...} on the error
     *       branch of READ-TRANSACT-FILE.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized
     *       {@code CoTrn01Output} screen states (one per submission
     *       in the scenario) with FULL PAN. The BMS screen is an
     *       authorized rendering surface distinct from the logging
     *       surface; PAN masking does NOT apply to this output per
     *       AAP &sect;0.7.2.</li>
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
     * pending the COBOL COTRN01C baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cotrn01c/expected/} per the
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
     * running surefire with {@code -Dtest=CoTrn01CGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdSlCGoldenTest}, {@link CoCrdUpCGoldenTest},
     * {@link CoTrn00CGoldenTest}, and {@link CoTrn02CGoldenTest}.</p>
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
        "Awaiting COBOL COTRN01C baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 6 must hold before removing "
            + "@Disabled): "
            + "(1) 16-character tran-id lookup against TRANSACT KSDS "
            + "(EXEC CICS READ DATASET(WS-TRANSACT-FILE) at "
            + "app/cbl/COTRN01C.cbl:L267-L296) returns the full 14-field "
            + "TRAN-RECORD (TRAN-ID, TRAN-CARD-NUM, TRAN-TYPE-CD, "
            + "TRAN-CAT-CD, TRAN-SOURCE, TRAN-DESC, TRAN-AMT, "
            + "TRAN-MERCHANT-ID, TRAN-MERCHANT-NAME, TRAN-MERCHANT-CITY, "
            + "TRAN-MERCHANT-ZIP, TRAN-ORIG-TS, TRAN-PROC-TS) projected "
            + "byte-for-byte onto the BMS map COTRN1A in bms_output.txt. "
            + "(2) Read-only invariant despite the COBOL UPDATE clause: "
            + "READ-TRANSACT-FILE issues EXEC CICS READ ... UPDATE at "
            + "COBOL line 275 but NEVER follows with REWRITE (copy-paste "
            + "leftover from sibling COTRN02C); per AAP \u00a70.7.1 the "
            + "Java translation faithfully reads without an UPDATE-"
            + "equivalent lock, so dailytran.txt MUST be byte-identical "
            + "to its pre-test state after the run; any divergence "
            + "indicates a regression. The deviation from the COBOL "
            + "UPDATE clause is documented in java/MIGRATION_NOTES.md. "
            + "(3) Verbatim error messages with trailing '...' preserved "
            + "per AAP \u00a70.7.1: \"Tran ID can NOT be empty...\" "
            + "(at app/cbl/COTRN01C.cbl:L149 from PROCESS-ENTER-KEY on "
            + "SPACES/LOW-VALUES input; mixed-case \"can NOT\" "
            + "preserved); \"Transaction ID NOT found...\" (at "
            + "app/cbl/COTRN01C.cbl:L285-L286 from READ-TRANSACT-FILE "
            + "on DFHRESP(NOTFND)); \"Unable to lookup Transaction...\" "
            + "(at app/cbl/COTRN01C.cbl:L292-L293 from "
            + "READ-TRANSACT-FILE on any other RESP code; capital 'T' "
            + "in 'Transaction' distinguishes this from sibling "
            + "COTRN00C's lowercase 'transaction' variant). The "
            + "WHEN OTHER branch also emits "
            + "CCDA-MSG-INVALID-KEY (\"Invalid key pressed. Please see "
            + "below...\") on any AID other than ENTER/PF3/PF4/PF5. "
            + "(4) Auto-trigger flow from COTRN00C verified: when "
            + "CoTrn01Input.trnIdIn() is non-blank on first-pass entry "
            + "(translating the COBOL block at app/cbl/COTRN01C.cbl:"
            + "L99-L109 which checks CDEMO-CT01-TRN-SELECTED), the "
            + "Java translation auto-triggers the ENTER code path and "
            + "produces a populated detail screen on the first SEND "
            + "MAP frame rather than an empty entry screen. "
            + "(5) AID-key dispatch verified: PF3 emits Outcome.Xctl "
            + "with target CDEMO-FROM-PROGRAM (defaulting to COMEN01C "
            + "when blank per the conditional at "
            + "app/cbl/COTRN01C.cbl:L116-L121; unique among the CT0x "
            + "family in using FROM-PROGRAM not TO-PROGRAM as the back "
            + "target); PF4 invokes CLEAR-CURRENT-SCREEN which "
            + "PERFORMs INITIALIZE-ALL-FIELDS (MOVE SPACES to all 13 "
            + "detail fields plus TRNIDINI) and re-SEND-MAPs; PF5 "
            + "emits Outcome.Xctl with target COTRN00C "
            + "(transaction-browse list, label 'F5=Browse Tran.' at "
            + "app/bms/COTRN01.bms). "
            + "(6) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in full in "
            + "bms_output.txt (the BMS screen is an authorized "
            + "rendering surface distinct from the logging surface). "
            + "The DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD on "
            + "the WHEN OTHER branch of READ-TRANSACT-FILE at COBOL "
            + "line 290 is also captured in stdout.txt for parity."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
