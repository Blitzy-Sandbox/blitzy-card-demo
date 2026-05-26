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
 * Byte-for-byte golden-record parity test for {@code COCRDLIC}
 * (Card List Online Transaction).
 *
 * <p><strong>Source COBOL:</strong> {@code app/cbl/COCRDLIC.cbl} &mdash; the
 * {@code PROGRAM-ID COCRDLIC} ({@code app/cbl/COCRDLIC.cbl:L26-L27}) online-CICS
 * program backing transaction {@code CCLI} ({@code LIT-THISTRANID PIC X(4) VALUE
 * 'CCLI'} at {@code app/cbl/COCRDLIC.cbl:L181-L182}, mapset {@code COCRDLI}
 * at {@code app/cbl/COCRDLIC.cbl:L183-L184}, map {@code CCRDLIA} at
 * {@code app/cbl/COCRDLIC.cbl:L185-L186}). The program is the
 * <strong>paged card-list controller</strong>: it issues a paged
 * {@code STARTBR / READNEXT / READPREV / ENDBR} traversal of the
 * {@code CARDDAT} VSAM KSDS ({@code LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '}
 * at {@code app/cbl/COCRDLIC.cbl:L213-L214}) optionally narrowed by an
 * account-id filter via the {@code CARDAIX} alternate index
 * ({@code LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} at
 * {@code app/cbl/COCRDLIC.cbl:L215-L217}), packs the next 7 hits into the
 * BMS {@code WS-SCREEN-ROWS OCCURS 7 TIMES} array ({@code WS-MAX-SCREEN-LINES
 * PIC S9(4) COMP VALUE 7} at {@code app/cbl/COCRDLIC.cbl:L177-L178}; the
 * 196-byte {@code WS-ALL-ROWS} REDEFINES at
 * {@code app/cbl/COCRDLIC.cbl:L250-L260}), and dispatches row-level user
 * selections to sibling card programs.</p>
 *
 * <p><strong>Java class under test:</strong>
 * {@link com.blitzy.carddemo.application.card.CoCrdLiC}. Per AAP &sect;0.4.1
 * (program-by-program mapping) COCRDLIC is translated into the
 * {@code application/card/} subpackage co-located with its sibling
 * translations {@code CoCrdSlC} (card view, transaction {@code CCDL}) and
 * {@code CoCrdUpC} (card update, transaction {@code CCUP}). The Java
 * translation has a 3-argument constructor
 * {@code CoCrdLiC(CardRepository cardRepository,
 * CardXrefRepository cardXrefRepository,
 * ProgramRegistry programRegistry)} matching the COBOL collaborator surface
 * (one repository port per VSAM file plus the dynamic-CALL routing facility
 * carried as a collaborator for {@code EXEC CICS XCTL} dispatch to
 * {@code COMEN01C}, {@code COCRDSLC}, and {@code COCRDUPC}). The base
 * harness {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
 * java.util.List)} hook resolves these collaborators against file-backed
 * adapters wired to the auxiliary fixtures returned by
 * {@link #auxiliaryInputs()}.</p>
 *
 * <h2>7 Rows Per Page (NOT 10 like CoTrn00C)</h2>
 *
 * <p>The most observable distinguishing characteristic of COCRDLIC versus
 * the transaction-list sibling COTRN00C is the per-page row count.
 * COCRDLIC packs <strong>seven</strong> rows per BMS map via
 * {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7} at
 * {@code app/cbl/COCRDLIC.cbl:L177-L178}; the 196-byte
 * {@code WS-ALL-ROWS PIC X(196)} group at
 * {@code app/cbl/COCRDLIC.cbl:L253} is REDEFINED as
 * {@code WS-SCREEN-ROWS OCCURS 7 TIMES} at L255 (each row carries an
 * 11-byte {@code WS-ROW-ACCTNO}, a 16-byte {@code WS-ROW-CARD-NUM}, and a
 * 1-byte {@code WS-ROW-CARD-STATUS} for a 28-byte per-row total times 7
 * rows equals 196 bytes; the inline COBOL comment at
 * {@code app/cbl/COCRDLIC.cbl:L250} reads "28 CHARS X 7 ROWS = 196").
 * COTRN00C uses ten rows per page; the difference is observable in the
 * BMS output column count and in the {@code bms_output.txt} fixture
 * captured under {@code src/test/resources/golden/cocrdlic/expected/}.
 * Any divergence to a different row count breaks the byte-for-byte parity
 * contract and is a regression in the Java translation.</p>
 *
 * <h2>STARTBR / READNEXT / READPREV / ENDBR Paged-Browse Pattern</h2>
 *
 * <p>The COBOL paragraphs {@code 9000-READ-FORWARD}
 * ({@code app/cbl/COCRDLIC.cbl:L1123-L1261}) and
 * {@code 9100-READ-BACKWARDS} ({@code app/cbl/COCRDLIC.cbl:L1264-L1374})
 * realize the paged-browse pattern over CARDDAT. The forward path issues
 * {@code EXEC CICS STARTBR} keyed by the current {@code WS-CARD-RID}
 * composite ({@code WS-CARD-RID-CARDNUM PIC X(16) + WS-CARD-RID-ACCT-ID
 * PIC 9(11)} at {@code app/cbl/COCRDLIC.cbl:L137-L141}), then
 * {@code READNEXT} up to {@code WS-MAX-SCREEN-LINES} (7) times, packing
 * each hit into the next slot of {@code WS-SCREEN-ROWS}, and finally
 * {@code ENDBR} to release the browse cursor. The backward path
 * substitutes {@code READPREV} for {@code READNEXT}, decrementing the
 * {@code WS-CA-SCREEN-NUM} counter and seeking from
 * {@code WS-CA-FIRST-CARDKEY} (recorded in the prior page's commarea at
 * {@code app/cbl/COCRDLIC.cbl:L233-L235}) backwards to repopulate the
 * previous page. The Java translation realizes both paths via
 * {@code CardRepository#streamFrom(String)} and stream-pull semantics
 * (one chunk equals one BMS frame); the AIX path is exercised when only
 * the account-id filter is supplied (CARDAIX random read, then forward
 * STARTBR on CARDDAT from the resolved primary key).</p>
 *
 * <h2>Row Selection XCTLs to COCRDSLC and COCRDUPC</h2>
 *
 * <p>The {@code EVALUATE TRUE} dispatch at
 * {@code app/cbl/COCRDLIC.cbl:L418-L583} routes user actions through
 * three sealed-Outcome targets. Pressing ENTER with a row selection
 * character of {@code 'S'} or {@code 's'} ({@code 88-level
 * VIEW-REQUESTED-ON VALUE 'S'} at {@code app/cbl/COCRDLIC.cbl:L78}) on
 * row position {@code I-SELECTED} ({@code DETAIL-WAS-REQUESTED VALUES 1
 * THRU 7} at {@code app/cbl/COCRDLIC.cbl:L94}) emits
 * {@code EXEC CICS XCTL PROGRAM(LIT-CARDDTLPGM)} at
 * {@code app/cbl/COCRDLIC.cbl:L538-L541}, transferring control to
 * {@code COCRDSLC} ({@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} at
 * {@code app/cbl/COCRDLIC.cbl:L195-L196}, transaction {@code CCDL} via
 * {@code LIT-CARDDTLTRANID VALUE 'CCDL'} at
 * {@code app/cbl/COCRDLIC.cbl:L197-L198}, mapset {@code COCRDSL} map
 * {@code CCRDSLA}). Pressing ENTER with selection char {@code 'U'} or
 * {@code 'u'} ({@code UPDATE-REQUESTED-ON VALUE 'U'} at
 * {@code app/cbl/COCRDLIC.cbl:L79}) instead emits the XCTL to
 * {@code COCRDUPC} ({@code LIT-CARDUPDPGM PIC X(8) VALUE 'COCRDUPC'} at
 * {@code app/cbl/COCRDLIC.cbl:L203-L204}, transaction {@code CCUP} via
 * {@code LIT-CARDUPDTRANID VALUE 'CCUP'} at
 * {@code app/cbl/COCRDLIC.cbl:L205-L206}). In both branches the chosen
 * account-id and card-number are propagated through the commarea via
 * {@code MOVE WS-ROW-ACCTNO (I-SELECTED) TO CDEMO-ACCT-ID} /
 * {@code MOVE WS-ROW-CARD-NUM (I-SELECTED) TO CDEMO-CARD-NUM} at
 * {@code app/cbl/COCRDLIC.cbl:L531-L534} / L559-L562, preserving the
 * pseudo-conversational context per AAP &sect;0.4.2.</p>
 *
 * <h2>PF-Key Dispatch (Enter, PF03, PF07, PF08)</h2>
 *
 * <p>The AID-key validity check at {@code app/cbl/COCRDLIC.cbl:L370-L380}
 * accepts only four AID values; any other AID falls through to
 * {@code SET CCARD-AID-ENTER TO TRUE} (i.e. coerced to ENTER) at
 * {@code app/cbl/COCRDLIC.cbl:L378-L380}. The accepted AIDs are:</p>
 * <ul>
 *   <li><strong>ENTER</strong> &mdash; process row selections; if any row
 *       has a {@code 'S'} or {@code 'U'} marker dispatch via XCTL to
 *       COCRDSLC or COCRDUPC; otherwise re-display the current page.</li>
 *   <li><strong>PF03 (Exit)</strong> &mdash; emit
 *       {@code EXEC CICS XCTL PROGRAM(LIT-MENUPGM)} to {@code COMEN01C}
 *       ({@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} at
 *       {@code app/cbl/COCRDLIC.cbl:L187-L188}, transaction {@code CM00}
 *       via {@code LIT-MENUTRANID VALUE 'CM00'} at L189-L190); the
 *       {@code 88-level WS-EXIT-MESSAGE VALUE 'PF03 PRESSED.EXITING'} at
 *       {@code app/cbl/COCRDLIC.cbl:L119-L120} (uppercase, with a period
 *       and no trailing space, preserved per AAP &sect;0.7.1) is fired
 *       just before the XCTL at L396.</li>
 *   <li><strong>PF07 (Page Up)</strong> &mdash; subtract 1 from
 *       {@code WS-CA-SCREEN-NUM} at
 *       {@code app/cbl/COCRDLIC.cbl:L508}, seed
 *       {@code WS-CARD-RID-CARDNUM} from {@code WS-CA-FIRST-CARD-NUM},
 *       invoke {@code 9100-READ-BACKWARDS} at L509-L510, and re-send the
 *       map. On the first page ({@code 88-level CA-FIRST-PAGE VALUE 1} at
 *       {@code app/cbl/COCRDLIC.cbl:L238}) PF07 is a no-op that
 *       re-displays the same page.</li>
 *   <li><strong>PF08 (Page Down)</strong> &mdash; add 1 to
 *       {@code WS-CA-SCREEN-NUM} at
 *       {@code app/cbl/COCRDLIC.cbl:L492}, seed
 *       {@code WS-CARD-RID-CARDNUM} from {@code WS-CA-LAST-CARD-NUM},
 *       invoke {@code 9000-READ-FORWARD} at L493-L494, and re-send the
 *       map. On the last page ({@code 88-level
 *       CA-NEXT-PAGE-NOT-EXISTS VALUE LOW-VALUES} at
 *       {@code app/cbl/COCRDLIC.cbl:L243}) PF08 is a no-op that
 *       re-displays the same page.</li>
 * </ul>
 *
 * <p>The Java translation's {@code Outcome} sealed interface (permits
 * {@code SendMap} and {@code Xctl}) replaces the COBOL CICS verbs
 * {@code EXEC CICS SEND MAP} / {@code EXEC CICS RETURN} and
 * {@code EXEC CICS XCTL} respectively; the exhaustive pattern-matching
 * switch over the sixteen {@code AidKey} permits enforces compile-time
 * completeness per AAP &sect;0.6.7 (no {@code default} branch).</p>
 *
 * <h2>Verbatim Error-Message Catalog (AAP &sect;0.7.1)</h2>
 *
 * <p>Per AAP &sect;0.7.1 (preserve current behavior exactly, including
 * verbatim error messages) the following 88-level message literals MUST
 * be preserved byte-for-byte by the Java translation and appear in the
 * captured fixtures:</p>
 * <ul>
 *   <li>{@code WS-EXIT-MESSAGE VALUE 'PF03 PRESSED.EXITING'} at
 *       {@code app/cbl/COCRDLIC.cbl:L119-L120} &mdash; emitted on PF03
 *       exit.</li>
 *   <li>{@code WS-NO-RECORDS-FOUND VALUE 'NO RECORDS FOUND FOR THIS
 *       SEARCH CONDITION.'} at {@code app/cbl/COCRDLIC.cbl:L121-L122}
 *       &mdash; emitted on an empty paged result.</li>
 *   <li>{@code WS-MORE-THAN-1-ACTION VALUE 'PLEASE SELECT ONLY ONE
 *       RECORD TO VIEW OR UPDATE'} at
 *       {@code app/cbl/COCRDLIC.cbl:L123-L124} &mdash; emitted when
 *       multiple rows are marked with {@code 'S'} / {@code 'U'} in a
 *       single submission (the COBOL paragraph
 *       {@code 2250-EDIT-ARRAY} at L1073-L1119 counts marks via
 *       {@code WS-EDIT-SELECT-COUNTER PIC S9(04) USAGE COMP-3} at
 *       L69-L71; if the counter exceeds 1 the message fires).</li>
 *   <li>{@code WS-INVALID-ACTION-CODE VALUE 'INVALID ACTION CODE'} at
 *       {@code app/cbl/COCRDLIC.cbl:L125-L126} &mdash; emitted when a
 *       row marker is neither {@code 'S'} nor {@code 'U'} nor blank
 *       (i.e. {@code SELECT-OK VALUES 'S', 'U'} at L77 is false and
 *       {@code SELECT-BLANK} at L80-L82 is also false).</li>
 *   <li>{@code WS-INFORM-REC-ACTIONS VALUE 'TYPE S FOR DETAIL, U TO
 *       UPDATE ANY RECORD'} at {@code app/cbl/COCRDLIC.cbl:L115-L116}
 *       &mdash; emitted as the {@code WS-INFO-MSG} prompt on first
 *       successful page load.</li>
 * </ul>
 *
 * <h2>PAN Masking Verification (AAP &sect;0.7.2)</h2>
 *
 * <p>The captured fixtures verify <strong>two separate code paths</strong>
 * with different PAN visibility:</p>
 * <ul>
 *   <li>{@code stdout.txt} &mdash; SLF4J / DISPLAY trace. Per AAP
 *       &sect;0.7.2 ("no card PAN logged in full; mask all but last 4
 *       digits in logs and error messages"), every log line mentioning a
 *       card number must show only the last 4 digits (12 leading mask
 *       characters such as {@code "************1234"}). The Java
 *       translation routes PAN through the {@code maskPan} helper on
 *       {@link com.blitzy.carddemo.application.card.CoCrdLiC} before
 *       SLF4J emission; the captured stdout fixture asserts the masked
 *       form byte-for-byte. The COBOL source itself issues no
 *       {@code DISPLAY} statements (the {@code stdout.txt} placeholder
 *       under {@code src/test/resources/golden/cocrdlic/expected/} is
 *       expected to be empty until the Java translation introduces SLF4J
 *       logging surfaces; nonetheless byte-for-byte parity holds:
 *       empty equals empty).</li>
 *   <li>{@code bms_output.txt} &mdash; serialized {@code CoCrdLiOutput}
 *       screen states (one per submission in the scenario). The BMS
 *       screen displays the <strong>FULL</strong> 16-digit PAN
 *       ({@code CRDNUMnO PIC X(16)} in the BMS symbolic map
 *       {@code app/cpy-bms/COCRDLI.CPY}) because the user is authorized
 *       to view their own card data through the 3270 terminal; the
 *       masking rule applies to logs only, not to authorized screen
 *       display. Storage and logging are different surfaces (per AAP
 *       &sect;0.1.1 surfaced implicit requirement).</li>
 * </ul>
 *
 * <h2>Test Scenario &mdash; Multi-Submit Pseudo-Conversation</h2>
 *
 * <p>The {@code input_scenario.txt} fixture under
 * {@code src/test/resources/golden/cocrdlic/expected/} encodes a sequence
 * of terminal submissions exercising the full state space of the paged
 * card-list transaction:</p>
 * <ol>
 *   <li><strong>Initial list</strong> (first-time invocation,
 *       {@code EIBCALEN = 0}) &mdash; paragraph {@code 0000-MAIN} at
 *       {@code app/cbl/COCRDLIC.cbl:L315-L325} initializes commarea,
 *       sets {@code CA-FIRST-PAGE} and {@code CA-LAST-PAGE-NOT-SHOWN},
 *       and routes through the {@code WHEN OTHER} branch at
 *       {@code app/cbl/COCRDLIC.cbl:L572-L582} which invokes
 *       {@code 9000-READ-FORWARD} from {@code LOW-VALUES} key (i.e.
 *       browse from start) to populate the first 7 rows.</li>
 *   <li><strong>PF08 forward paging</strong> &mdash; advances to the
 *       second page via the dispatch at
 *       {@code app/cbl/COCRDLIC.cbl:L486-L497}; the new
 *       {@code WS-CA-SCREEN-NUM} appears in the BMS {@code PAGENOO}
 *       field set at {@code app/cbl/COCRDLIC.cbl:L667}.</li>
 *   <li><strong>PF07 backward paging</strong> &mdash; returns to the
 *       first page via the dispatch at
 *       {@code app/cbl/COCRDLIC.cbl:L501-L513}; invokes
 *       {@code 9100-READ-BACKWARDS} to re-read the prior page from
 *       {@code WS-CA-FIRST-CARDKEY}.</li>
 *   <li><strong>Row selection {@code 'S'}</strong> on a valid row
 *       &mdash; XCTL to {@code COCRDSLC} per the dispatch at
 *       {@code app/cbl/COCRDLIC.cbl:L517-L541}; the Java
 *       {@code Outcome.Xctl} carries {@code targetProgram = "COCRDSLC"}
 *       and the chosen account-id / card-number propagated via
 *       commarea.</li>
 *   <li><strong>Row selection {@code 'U'}</strong> on a valid row
 *       &mdash; XCTL to {@code COCRDUPC} per the dispatch at
 *       {@code app/cbl/COCRDLIC.cbl:L545-L569}.</li>
 *   <li><strong>Multiple-selection error</strong> &mdash; two rows
 *       marked {@code 'S'} in one submit fires
 *       {@code WS-MORE-THAN-1-ACTION} at L123-L124 via paragraph
 *       {@code 2250-EDIT-ARRAY}; the page re-displays with the error
 *       message overlaid and {@code FLG-PROTECT-SELECT-ROWS-YES} set so
 *       the selection columns are masked (paragraph
 *       {@code 1250-SETUP-ARRAY-ATTRIBS} at L748-L834).</li>
 *   <li><strong>Invalid selection character</strong> &mdash; a marker
 *       other than {@code 'S'} / {@code 'U'} / blank fires
 *       {@code WS-INVALID-ACTION-CODE} at L125-L126.</li>
 *   <li><strong>PF03 back</strong> &mdash; the user presses
 *       {@code PFK03} (Exit); the program emits
 *       {@code WS-EXIT-MESSAGE 'PF03 PRESSED.EXITING'} at L119-L120 and
 *       XCTLs to {@code COMEN01C} per the dispatch at
 *       {@code app/cbl/COCRDLIC.cbl:L384-L406}.</li>
 * </ol>
 *
 * <h2>Auxiliary Input Fixtures</h2>
 *
 * <p>Two ASCII fixtures from {@code app/data/ASCII/} are wired through
 * {@link #auxiliaryInputs()} to back the two read paths:</p>
 * <ol>
 *   <li>{@code carddata.txt} &mdash; CARDFILE (CARDDAT) baseline, 50
 *       card records at 150 bytes each per
 *       {@code app/cpy/CVACT02Y.cpy:&sect;CARD-RECORD}; the primary
 *       {@code STARTBR / READNEXT / READPREV / ENDBR} traversal in
 *       paragraphs {@code 9000-READ-FORWARD} and
 *       {@code 9100-READ-BACKWARDS} pulls rows from this dataset. Backs
 *       the {@link com.blitzy.carddemo.domain.port.CardRepository}
 *       constructor dependency.</li>
 *   <li>{@code cardxref.txt} &mdash; CARDXREF cross-reference, 50
 *       50-byte records per
 *       {@code app/cpy/CVACT03Y.cpy:&sect;CARD-XREF-RECORD}; the
 *       account-id-only filter path opens {@code CARDAIX} via this
 *       cross-reference, recovering the primary-key prefix from the
 *       {@code XREF-ACCT-ID PIC 9(11)} alternate-index key. Backs the
 *       {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
 *       constructor dependency.</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.4.1 and &sect;0.6.11 the fixtures are read DIRECTLY
 * from {@code app/} via the
 * {@link GoldenRecordTest#resolveAppDataPath(String)} helper &mdash; NOT
 * copied into {@code java/carddemo-tests/} (the COBOL source tree
 * remains the single source of truth for fixture data). Because COCRDLIC
 * is strictly read-only (no {@code REWRITE}, no {@code WRITE}, no
 * {@code SYNCPOINT}; only {@code STARTBR / READNEXT / READPREV / ENDBR}
 * browse verbs and {@code EXEC CICS READ} for the AIX path), the
 * post-test state of BOTH fixtures MUST be byte-identical to their
 * pre-test state; any divergence indicates a regression in the Java
 * translation that the harness's
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
 *       only). The COBOL source contains no {@code DISPLAY} verbs so
 *       the captured baseline is currently empty; the Java translation
 *       may add SLF4J emissions in which case the captured form will be
 *       re-generated and committed per
 *       {@code java/MIGRATION_NOTES.md}.</li>
 *   <li>{@code bms_output.txt} &mdash; the serialized
 *       {@link com.blitzy.carddemo.application.card.CoCrdLiC} BMS output
 *       frames (one serialized {@code CoCrdLiOutput} state per
 *       submission in the scenario, concatenated in submission order).
 *       Sequence ordering is preserved exactly because the user-facing
 *       view depends on the strict submit-then-render protocol of the
 *       CICS pseudo-conversational pattern. Each frame must contain
 *       exactly 7 row slots, each populated or LOW-VALUES per the
 *       {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} guards in paragraph
 *       {@code 1200-SCREEN-ARRAY-INIT} at L678-L745.</li>
 * </ol>
 *
 * <p>This test is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP
 * &sect;0.6.11. Any deviation in the 7-row-per-page count, the
 * STARTBR/READNEXT/READPREV/ENDBR sequencing, the XCTL targets on row
 * selection, the verbatim COBOL error messages, the PAN-masking
 * behavior in logs, the read-only fixture invariant, or the screen
 * sequencing breaks parity and blocks the PR.</p>
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
 * under {@code src/test/resources/golden/cocrdlic/expected/}.</p>
 *
 * @see GoldenRecordTest
 * @see com.blitzy.carddemo.application.card.CoCrdLiC
 * @since 25
 */
@DisplayName("COCRDLIC \u2014 Card List Golden-Record Parity (7 rows per page)")
public class CoCrdLiCGoldenTest extends GoldenRecordTest {

    /**
     * Identifier of the per-program fixture directory under
     * {@code src/test/resources/golden/}. Lowercase form of the COBOL
     * {@code PROGRAM-ID COCRDLIC} per the
     * {@link GoldenRecordTest#resolveExpectedOutputPath(String, String)}
     * naming convention shared with all 28 sibling golden tests.
     */
    private static final String PROGRAM_DIR = "cocrdlic";

    /**
     * Name of the synthesized scenario file under
     * {@code src/test/resources/golden/cocrdlic/expected/}. Encodes the
     * eight-submission pseudo-conversation sequence (initial list, PF8
     * forward, PF7 backward, row selection {@code 'S'}, row selection
     * {@code 'U'}, multiple-selection error, invalid selection char, PF3
     * back) consumed by the harness orchestrator to drive the CoCrdLiC
     * online-CICS state machine.
     */
    private static final String INPUT_SCENARIO_TXT = "input_scenario.txt";

    /**
     * Name of the captured COBOL stdout trace under
     * {@code src/test/resources/golden/cocrdlic/expected/}. Records the
     * PAN-masked SLF4J/DISPLAY emissions (currently empty because the
     * COBOL source contains no {@code DISPLAY} verbs; reserved for future
     * Java-translation log surfaces). Per AAP &sect;0.7.2 every PAN
     * reference in this file shows the last 4 digits only (12 leading
     * mask characters).
     */
    private static final String STDOUT_TXT = "stdout.txt";

    /**
     * Name of the captured BMS output trace under
     * {@code src/test/resources/golden/cocrdlic/expected/}. Records the
     * serialized {@code CoCrdLiOutput} screen states (one per
     * submission, concatenated in submission order). Per AAP &sect;0.7.2
     * these records display FULL PAN because the BMS screen is an
     * authorized rendering surface distinct from the SLF4J logging
     * surface. Each frame carries exactly 7 row slots per the
     * {@code WS-MAX-SCREEN-LINES VALUE 7} cap at
     * {@code app/cbl/COCRDLIC.cbl:L177-L178}.
     */
    private static final String BMS_OUTPUT_TXT = "bms_output.txt";

    /**
     * Name of the CARDFILE baseline fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardRepository} used by
     * paragraphs {@code 9000-READ-FORWARD} ({@code STARTBR / READNEXT /
     * ENDBR} forward browse) and {@code 9100-READ-BACKWARDS}
     * ({@code STARTBR / READPREV / ENDBR} backward browse) over the
     * paged 7-row card-list traversal. Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CARDDATA_TXT = "carddata.txt";

    /**
     * Name of the CARDXREF cross-reference fixture under
     * {@code app/data/ASCII/}. Backs the
     * {@link com.blitzy.carddemo.domain.port.CardXrefRepository} used by
     * the optional account-id-only filter path (the {@code CARDAIX}
     * alternate index keyed by {@code XREF-ACCT-ID PIC 9(11)} alone;
     * resolves the primary-key prefix used to seed the subsequent
     * {@code STARTBR} on CARDDAT). Read directly via
     * {@link GoldenRecordTest#resolveAppDataPath(String)} &mdash; NOT
     * copied into this module per AAP &sect;0.4.1 and &sect;0.6.11.
     */
    private static final String CARDXREF_TXT = "cardxref.txt";

    /**
     * {@inheritDoc}
     *
     * <p>Returns
     * {@link com.blitzy.carddemo.application.card.CoCrdLiC}{@code .class}.
     * Referenced via fully-qualified class literal so this file's import
     * block stays minimal and restricted to {@link java.nio.file.Path},
     * {@link java.util.List}, and the JUnit Jupiter API annotations
     * ({@link DisplayName}, {@link Disabled}, {@link Test}). The
     * fully-qualified class literal compiles cleanly because
     * {@code carddemo-tests} declares a test-scope dependency on
     * {@code carddemo-application} (transitively via {@code carddemo-app})
     * in {@code java/carddemo-tests/pom.xml} per AAP &sect;0.5.1.</p>
     */
    @Override
    protected Class<?> programClass() {
        return com.blitzy.carddemo.application.card.CoCrdLiC.class;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the absolute {@link Path} to
     * {@code src/test/resources/golden/cocrdlic/expected/input_scenario.txt}
     * via {@link GoldenRecordTest#resolveExpectedOutputPath(String,
     * String)}. This synthesized scenario file encodes the
     * eight-submission CICS pseudo-conversation that drives the
     * CoCrdLiC online-state machine through the full state space
     * (initial list; PF8 forward; PF7 backward; row selection
     * {@code 'S'} to COCRDSLC; row selection {@code 'U'} to COCRDUPC;
     * multiple-selection error; invalid action code; PF3 back to
     * COMEN01C); it lives alongside the expected outputs under the
     * per-program {@code cocrdlic/} subtree because it is a
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
     * {@code src/test/resources/golden/cocrdlic/expected/stdout.txt},
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
     * <p>Returns an immutable 2-element {@link List} of auxiliary input
     * fixture paths reflecting the COCRDLIC collaborator surface:</p>
     * <ol>
     *   <li>{@code app/data/ASCII/carddata.txt} &mdash; CARDFILE
     *       (CARDDAT) baseline (50 card records at 150 bytes each per
     *       {@code app/cpy/CVACT02Y.cpy}). Paged
     *       {@code STARTBR / READNEXT / READPREV / ENDBR} traversal in
     *       paragraphs {@code 9000-READ-FORWARD} and
     *       {@code 9100-READ-BACKWARDS}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.CardRepository}
     *       constructor dependency.</li>
     *   <li>{@code app/data/ASCII/cardxref.txt} &mdash; CARDXREF
     *       cross-reference (50 50-byte records per
     *       {@code app/cpy/CVACT03Y.cpy}). Random read via the
     *       {@code CARDAIX} alternate index keyed by
     *       {@code XREF-ACCT-ID PIC 9(11)} alone; used when only the
     *       account-id filter is supplied to recover the
     *       composite-primary-key prefix and seed the subsequent
     *       {@code STARTBR}. Backs the
     *       {@link com.blitzy.carddemo.domain.port.CardXrefRepository}
     *       constructor dependency.</li>
     * </ol>
     *
     * <p>The returned list is {@link List#of(Object, Object)} immutable
     * to preserve deterministic ordering. Ordering matters here because
     * the harness's
     * {@link GoldenRecordTest#runProgram(Class, java.nio.file.Path,
     * java.util.List) runProgram(Class, Path, List)} orchestration hook
     * wires the supplied fixtures to the file-based repository adapters
     * in declaration order; reordering would change which adapter binds
     * which fixture and break the paged-browse read path.</p>
     *
     * <p>Because COCRDLIC is strictly read-only (browse verbs only; no
     * {@code REWRITE}, no {@code WRITE}, no {@code SYNCPOINT}), both
     * fixtures MUST be byte-identical to their pre-test state after the
     * run; the harness's runProgram(...) hook copies the inputs into a
     * temp directory before the run so any inadvertent in-place mutation
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
     * <p>Declares the two byte-for-byte parity targets for COCRDLIC.
     * Overriding this method (rather than relying on the base class's
     * single-output default) is the AAP &sect;0.6.11 idiom for
     * multi-output scenarios; the base
     * {@link GoldenRecordTest#byteForByteParity()} iterates this list
     * and asserts byte parity for each entry independently, identifying
     * any mismatched output by name in the AssertJ failure message.</p>
     * <ol>
     *   <li>{@link #STDOUT_TXT} ({@code stdout.txt}) &mdash; the
     *       PAN-masked SLF4J/DISPLAY trace per AAP &sect;0.7.2. Every
     *       PAN reference is masked to its last 4 digits before
     *       emission (12 leading mask characters such as
     *       {@code "************1234"}). The COBOL source contains no
     *       {@code DISPLAY} verbs so the captured baseline is empty;
     *       the Java translation may add SLF4J emissions in which case
     *       the captured form will be regenerated per
     *       {@code java/MIGRATION_NOTES.md}.</li>
     *   <li>{@link #BMS_OUTPUT_TXT} ({@code bms_output.txt}) &mdash;
     *       the serialized {@code CoCrdLiOutput} screen states (one
     *       per submission in the scenario) with FULL PAN. Each frame
     *       carries exactly 7 row slots per the
     *       {@code WS-MAX-SCREEN-LINES VALUE 7} cap at
     *       {@code app/cbl/COCRDLIC.cbl:L177-L178}. The BMS screen is
     *       an authorized rendering surface distinct from the logging
     *       surface; PAN masking does NOT apply to this output per AAP
     *       &sect;0.7.2.</li>
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
     * pending the COBOL COCRDLIC baseline capture per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files
     * marked {@code @Disabled} until COBOL captures are available").
     *
     * <p>The {@code @Disabled} annotation will be removed in the same
     * PR that commits non-placeholder content under
     * {@code src/test/resources/golden/cocrdlic/expected/} per the
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
     * running surefire with {@code -Dtest=CoCrdLiCGoldenTest} produces
     * "Tests run: 0" when {@code @Test} is omitted from the override
     * but "Tests run: 1, Skipped: 1" when re-declared. Without
     * {@code @Test} here, this test class would be silently dropped
     * from the test suite, defeating the AAP &sect;0.6.11 PR-gate
     * purpose of the harness skeleton. This pattern matches sibling
     * {@link CoActVwCGoldenTest}, {@link CoActUpCGoldenTest},
     * {@link CoCrdSlCGoldenTest}, and {@link CoCrdUpCGoldenTest}.</p>
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
        "Awaiting COBOL COCRDLIC baseline capture per AAP \u00a70.6.11. "
            + "See java/MIGRATION_NOTES.md for the regeneration procedure. "
            + "Activation checklist (all 4 must hold before removing "
            + "@Disabled): "
            + "(1) 7 rows per page (NOT 10 like CoTrn00C): every BMS "
            + "frame in bms_output.txt MUST carry exactly seven row "
            + "slots per the WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7 "
            + "cap at app/cbl/COCRDLIC.cbl:L177-L178 and the 196-byte "
            + "WS-ALL-ROWS / WS-SCREEN-ROWS OCCURS 7 TIMES REDEFINES at "
            + "L250-L260 (28 CHARS X 7 ROWS = 196 per the inline COBOL "
            + "comment at L250). Any deviation to a different per-page "
            + "count breaks parity and blocks the PR. "
            + "(2) STARTBR/READNEXT/READPREV/ENDBR sequencing: PF08 "
            + "forward paging invokes paragraph 9000-READ-FORWARD at "
            + "app/cbl/COCRDLIC.cbl:L1123-L1261 (STARTBR + 7-count "
            + "READNEXT loop + ENDBR), seeded from WS-CA-LAST-CARD-NUM "
            + "at L488-L489. PF07 backward paging invokes paragraph "
            + "9100-READ-BACKWARDS at L1264-L1374 (STARTBR + 7-count "
            + "READPREV loop + ENDBR), seeded from "
            + "WS-CA-FIRST-CARD-NUM at L504-L505. Browse cursors are "
            + "always released by the matching ENDBR; no cursor leakage. "
            + "(3) Row selection XCTLs to COCRDSLC (selection char 'S' "
            + "or 's' per 88-level VIEW-REQUESTED-ON VALUE 'S' at L78) "
            + "via the dispatch at app/cbl/COCRDLIC.cbl:L517-L541 "
            + "(EXEC CICS XCTL PROGRAM(LIT-CARDDTLPGM) where "
            + "LIT-CARDDTLPGM = 'COCRDSLC' at L195-L196); and to "
            + "COCRDUPC (selection char 'U' or 'u' per 88-level "
            + "UPDATE-REQUESTED-ON VALUE 'U' at L79) via the dispatch "
            + "at L545-L569 (EXEC CICS XCTL PROGRAM(LIT-CARDUPDPGM) "
            + "where LIT-CARDUPDPGM = 'COCRDUPC' at L203-L204). The "
            + "chosen account-id and card-number are propagated via "
            + "CDEMO-ACCT-ID / CDEMO-CARD-NUM in CARDDEMO-COMMAREA per "
            + "MOVE WS-ROW-ACCTNO(I-SELECTED) / WS-ROW-CARD-NUM("
            + "I-SELECTED) at L531-L534 / L559-L562. PF03 XCTLs to "
            + "COMEN01C (LIT-MENUPGM = 'COMEN01C' at L187-L188) per "
            + "the dispatch at L384-L406; the verbatim "
            + "WS-EXIT-MESSAGE 'PF03 PRESSED.EXITING' at L119-L120 is "
            + "emitted at L396 preserved per AAP \u00a70.7.1. "
            + "(4) PAN masked to the last 4 digits in stdout.txt per "
            + "AAP \u00a70.7.2 (12 leading mask characters such as "
            + "************1234) BUT preserved in full in "
            + "bms_output.txt (the BMS screen is an authorized "
            + "rendering surface distinct from the logging surface). "
            + "Verbatim error-message catalog preserved per AAP "
            + "\u00a70.7.1: WS-NO-RECORDS-FOUND 'NO RECORDS FOUND FOR "
            + "THIS SEARCH CONDITION.' at L121-L122; "
            + "WS-MORE-THAN-1-ACTION 'PLEASE SELECT ONLY ONE RECORD TO "
            + "VIEW OR UPDATE' at L123-L124 (fires when "
            + "WS-EDIT-SELECT-COUNTER > 1 via paragraph 2250-EDIT-ARRAY "
            + "at L1073-L1119); WS-INVALID-ACTION-CODE 'INVALID ACTION "
            + "CODE' at L125-L126; WS-INFORM-REC-ACTIONS 'TYPE S FOR "
            + "DETAIL, U TO UPDATE ANY RECORD' at L115-L116. Read-only "
            + "invariant: COCRDLIC never modifies any record (no "
            + "REWRITE, no WRITE, no SYNCPOINT; only STARTBR / "
            + "READNEXT / READPREV / ENDBR browse verbs and EXEC CICS "
            + "READ for the AIX path), so the two auxiliary fixtures "
            + "(carddata.txt, cardxref.txt) MUST be byte-identical to "
            + "their pre-test state after the run; any divergence "
            + "indicates a regression."
    )
    public void byteForByteParity() throws Exception {
        super.byteForByteParity();
    }
}
