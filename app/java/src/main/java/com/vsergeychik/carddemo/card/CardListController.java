package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.CardRepository.BrowseDirection;
import com.vsergeychik.carddemo.card.CardRepository.CardBrowse;
import com.vsergeychik.carddemo.card.CardRepository.CardReadResult;
import com.vsergeychik.carddemo.card.dto.CardListRequest;
import com.vsergeychik.carddemo.card.dto.CardListRequest.CardKey;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListRequest.ScreenRowTable;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionErrorFlags;
import com.vsergeychik.carddemo.card.dto.CardListRequest.SelectionFlags;
import com.vsergeychik.carddemo.card.dto.CardListResponse;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.user.model.SecUserRecord;

import jakarta.validation.Valid;

import java.nio.charset.Charset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CCLI} - the paged credit card list screen, translated from
 * {@code app/cbl/COCRDLIC.cbl} (1,459 lines) into a <strong>stateless</strong> {@code @RestController}
 * exposing {@code GET /api/cards}.
 *
 * <p>This is a like-for-like language migration. The layering and the shape of the control flow are
 * modernised - {@code GO TO} becomes {@code if}/{@code while}/{@code return}, paragraphs become
 * methods - but <strong>arithmetic, evaluation order, field widths and the page size are held
 * invariant</strong>. Where the COBOL does something surprising, this class does the same surprising
 * thing and says so.
 *
 * <h2>Authorities</h2>
 * <table>
 *   <caption>The sources this class is transcribed from, and what each fixes</caption>
 *   <tr><th>Source</th><th>What it fixes</th></tr>
 *   <tr><td>{@code app/cbl/COCRDLIC.cbl}</td>
 *       <td>Every decision, in source order. 9 {@code EVALUATE}s, 16 {@code GO TO}s, 38
 *           {@code 88}-levels</td></tr>
 *   <tr><td>{@code app/cpy-bms/COCRDLI.CPY}</td>
 *       <td>The 45 {@code xxxI} request members and the 45 {@code xxxO} response members, and their
 *           widths. Owned by {@link CardListRequest} and {@link CardListResponse}</td></tr>
 *   <tr><td>{@code app/bms/COCRDLI.bms}</td>
 *       <td>45 name-labelled {@code DFHMDF} entries, {@code SIZE=(24,80)}</td></tr>
 *   <tr><td>{@code app/cpy/CVCRD01Y.cpy}</td>
 *       <td>{@code CC-WORK-AREAS}: the AID token, the navigation triple, the two filter fields.
 *           Owned by {@link CardScreenState}</td></tr>
 *   <tr><td>{@code app/cpy/CVACT02Y.cpy}</td>
 *       <td>The 150-byte card record. Owned by {@link CardRecord}</td></tr>
 *   <tr><td>{@code app/csd/CARDDEMO.CSD:357}</td>
 *       <td>{@code DEFINE TRANSACTION(CCLI)}, and the {@code CARDDAT}/{@code CARDAIX} file
 *           definitions reached only through {@link CardRepository}</td></tr>
 * </table>
 *
 * <h2>Page size 7 is behaviour, not configuration</h2>
 * {@code app/cbl/COCRDLIC.cbl:177-178} declares
 * <pre>
 *   05  WS-MAX-SCREEN-LINES   PIC S9(4) COMP
 *                             VALUE 7.
 * </pre>
 * and nothing else in the program parameterises it. It is therefore
 * {@link #WS_MAX_SCREEN_LINES}, a {@code private static final int} holding the literal
 * {@code 7}: <strong>not</strong> a {@code @Value}, <strong>not</strong> an {@code application.yml}
 * key, <strong>not</strong> a constructor parameter and <strong>not</strong> settable by any request
 * parameter. Making it tunable would be a parity violation (gate G39).
 *
 * <p>The two directions are <strong>not mirror images</strong> and both are reproduced exactly:
 * <ul>
 *   <li><strong>Forward</strong> [{@code :1140-1191}] zeroes the row counter, counts <em>up</em>, and
 *       stops when {@code WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES}. It then issues one extra
 *       {@code READNEXT} [{@code :1197}] purely to learn whether a next page exists.</li>
 *   <li><strong>Backward</strong> [{@code :1284-1286}] primes the counter to
 *       {@code WS-MAX-SCREEN-LINES + 1} - that is 8 - <em>discards</em> the first {@code READPREV}
 *       [{@code :1294}] by only decrementing, then counts <em>down</em> [{@code :1307}, {@code :1346}]
 *       and stops when the counter reaches {@code 0} [{@code :1347}].</li>
 * </ul>
 *
 * <h2>Two documented conflicts (practice B4 - document, never silently fix)</h2>
 *
 * <h3>Conflict 1 - the card-select DTO reference</h3>
 * The Agent Action Plan (&sect;0.2.3, &sect;0.4.11, &sect;0.6.4) states that {@code COCRDLIC} copies
 * <em>both</em> {@code COCRDLI} and {@code COCRDSL}, and directs this class to reference the
 * card-select DTO in addition to its own. <strong>The source does not bear that out.</strong>
 * {@code app/cbl/COCRDLIC.cbl:274} reads {@code *COPY COCRDSL.} - <em>commented out</em> - and the
 * only live symbolic-map copy is {@code COPY COCRDLI.} at {@code :276}. The program has no
 * {@code COCRDSL} data area at all.
 *
 * <p><strong>Resolution.</strong> The directive is honoured, scoped to what the source actually does:
 * {@code COCRDLIC} <em>navigates to</em> the card-detail screen by setting the next-program, -mapset
 * and -map triple [{@code :526-529}]. {@link CardSelectRequest} and {@link CardSelectResponse} are
 * therefore referenced <strong>only as navigation targets</strong> - to cross-check this program's own
 * {@code LIT-CARDDTL*} literals and the width of the two values handed over - and <strong>no
 * {@code COCRDSL} symbolic-map field is ever populated here</strong>. Writing one would be new
 * behaviour and a parity violation. See {@link #verifyNavigationContract()}.
 *
 * <h3>Conflict 2 - {@code LIT-CCLISTMAP} in the sibling programs</h3>
 * {@code app/cbl/COCRDSLC.cbl:178} and {@code app/cbl/COCRDUPC.cbl:234} both set their
 * {@code LIT-CCLISTMAP} to {@code 'CCRDSLA'}, although the card-list map is really {@code 'CCRDLIA'}
 * [{@code app/cbl/COCRDLIC.cbl:185}]. That is a defect in those two programs and is preserved there
 * verbatim. This class uses its own correct {@link #LIT_THISMAP} {@code 'CCRDLIA'}: the sibling defect
 * is neither propagated here nor "fixed" there.
 *
 * <h2>Statelessness - the pagination cursor travels in the payload (rule R6, gate G37)</h2>
 * {@code COCRDLIC} keeps its paging state in {@code 01 WS-THIS-PROGCOMMAREA} [{@code :229-260}],
 * appended after the 160-byte {@code CARDDEMO-COMMAREA} inside {@code 01 WS-COMMAREA PIC X(2000)} and
 * returned on {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)}.
 *
 * <p><strong>A level-number reading that is load-bearing:</strong> {@code 05 WS-SCREEN-DATA.} at
 * {@code :252} is level 05 among the level-10 members declared above it, so by COBOL level-number
 * rules it closes that group and becomes a direct subordinate of {@code 01 WS-THIS-PROGCOMMAREA}
 * rather than a record of its own. The communication area is therefore {@code 58 + 196 = 254} bytes,
 * the 196-byte row table <em>does</em> round-trip through {@code DFHCOMMAREA} [{@code :329-331} in,
 * {@code :610-612} out], and {@code INITIALIZE WS-MISC-STORAGE} at {@code :301} does not touch it.
 * That is precisely why the two transfer branches at {@code :531} and {@code :559} can read
 * {@code WS-ROW-ACCTNO(I-SELECTED)} and get a value. {@link CardListRequest#PROG_COMMAREA_LENGTH}
 * records the same reading.
 *
 * <p>Every part of that area travels in the request and response payload. There is <strong>no
 * {@code HttpSession}, no server-side cache, no {@code @SessionAttributes} and no static map</strong>.
 * {@code CA-NEXT-PAGE-NOT-EXISTS} and {@code WS-RETURN-FLAG-OFF} are {@code LOW-VALUES} - binary
 * {@code x'00'}, which is neither a space nor a Java {@code null} - and {@link PageCursor} keeps the
 * three states apart. {@code WS-RETURN-FLAG} itself [{@code :246-248}] is <strong>declared and never
 * read anywhere in the procedure division</strong>; it is carried through untouched rather than
 * dropped (practice B5).
 *
 * <p>{@code EIBCALEN = 0} [{@code :315}] - the cold start - is
 * {@link CardListRequest#hasNavigationContext()} returning {@code false}, which is also what a
 * {@code GET} with no request body produces. A request nobody passed a communication area to has not
 * been passed one.
 *
 * <h2>The 196-byte row table in a stateless projection</h2>
 * {@link CardListRequest#getScreenRowTable()} is deliberately kept off the JSON wire by its owner, so
 * this class rebuilds it from the returned screen: the {@code ACCTNOnI}, {@code CRDNUMnI} and
 * {@code CRDSTSnI} members carry exactly the three values {@code 1200-SCREEN-ARRAY-INIT}
 * [{@code :678-743}] wrote into them, which are exactly the three values the COBOL's carried table
 * holds. A row whose three members are all blank is restored to {@code LOW-VALUES}, because that is
 * the state {@code MOVE LOW-VALUES TO WS-ALL-ROWS} [{@code :1124}, {@code :1266}] left an unfilled row
 * in and what a 3270 returns for a field that was never written. The reconstruction is
 * information-preserving and is the only place the projection differs mechanically from the mainframe.
 * See {@link #restoreScreenRowTable(CardListRequest)}.
 *
 * <h2>The two row-1 asymmetries</h2>
 * <ol>
 *   <li><strong>Field count.</strong> Row 1 of the symbolic map has four members -
 *       {@code CRDSEL1}, {@code ACCTNO1}, {@code CRDNUM1}, {@code CRDSTS1} - while rows 2 to 7 have
 *       five, adding {@code CRDSTPn}. {@code app/cpy-bms/COCRDLI.CPY:78-79} shows {@code CRDSEL1I}
 *       followed directly by {@code ACCTNO1L} with no {@code CRDSTP1} between them, and the 45-field
 *       count only reconciles with the asymmetry: {@code 9 + 4 + 6×5 + 2 = 45}. The DTO pair models it
 *       with two row types; nothing here assumes a uniform grid.</li>
 *   <li><strong>Attribute handling.</strong> {@code 1250-SETUP-ARRAY-ATTRIBS} gives row 1
 *       {@code DFHBMPRF} [{@code :753}] where rows 2 to 7 get {@code DFHBMPRO} [{@code :766}], and on
 *       an error row 1 moves {@code '*'} into {@code CRDSEL1O} when the selection byte is blank
 *       [{@code :757-759}] where rows 2 to 7 instead move {@code -1} into {@code CRDSELnL}
 *       [{@code :770}]. Both are preserved.</li>
 * </ol>
 *
 * <h2>{@code OCCURS} is 1-based; Java is 0-based</h2>
 * {@code WS-SCRN-COUNTER} and {@code I-SELECTED} run {@code 1..7} throughout this class, exactly as
 * they do in the COBOL, and the conversion happens at <strong>exactly one boundary</strong>: inside
 * {@link CardListRequest#javaIndexOf(int)}, which every table accessor on the DTOs routes through.
 * No index arithmetic is open-coded here. The Agent Action Plan names this the top defect risk of the
 * whole migration (&sect;0.7.2, gate G33), so the tests assert both the <em>first</em> and the
 * <em>last</em> element.
 *
 * <h2>The three {@code XCTL} sites become a {@code nextProgram} response field (gate G40)</h2>
 * <table>
 *   <caption>Every transfer of control and the triple it resolves to</caption>
 *   <tr><th>Site</th><th>Program</th><th>Tranid</th><th>Mapset</th><th>Map</th></tr>
 *   <tr><td>{@code :402} {@code XCTL PROGRAM(LIT-MENUPGM)}</td>
 *       <td>{@code COMEN01C}</td><td>{@code CM00}</td><td>{@code COMEN01}</td>
 *       <td>{@code COMEN1A}</td></tr>
 *   <tr><td>{@code :538} {@code XCTL PROGRAM(CCARD-NEXT-PROG)}, triple set at {@code :526-529}</td>
 *       <td>{@code COCRDSLC}</td><td>{@code CCDL}</td><td>{@code COCRDSL}</td>
 *       <td>{@code CCRDSLA}</td></tr>
 *   <tr><td>{@code :566} {@code XCTL PROGRAM(CCARD-NEXT-PROG)}, triple set at {@code :554-557}</td>
 *       <td>{@code COCRDUPC}</td><td>{@code CCUP}</td><td>{@code COCRDUP}</td>
 *       <td>{@code CCRDUPA}</td></tr>
 * </table>
 * The literal widths are {@code PIC X(8)} for a program, {@code X(4)} for a transaction identifier and
 * {@code X(7)} for a mapset and for a map - {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} are
 * {@code X(7)} and are never widened to 8. The response names where to go; <strong>there is no
 * server-side forward, no redirect chain and no session affinity</strong>, and the client issues the
 * follow-up call.
 *
 * <p>One oddity in the menu transfer is preserved verbatim: {@code :395} moves
 * {@code LIT-THISMAP} - not {@code LIT-MENUMAP} - into {@code CCARD-NEXT-MAP}, while {@code :394}
 * moves {@code LIT-MENUMAPSET} into {@code CCARD-NEXT-MAPSET}. The map named on the way to the menu is
 * therefore this screen's own {@code CCRDLIA}. See {@link #transferToMenu}.
 *
 * <h2>Security posture is neither weakened nor unrequestedly strengthened (practice B6)</h2>
 * {@code COCRDLIC} issues {@code SET CDEMO-USRTYP-USER TO TRUE} <strong>unconditionally</strong> on
 * every branch that leaves the program - {@code :320}, {@code :388}, {@code :466}, {@code :522} and
 * {@code :550} - and performs no authorization check of any kind, even though the program header
 * distinguishes an admin from a non-admin user. That is reproduced exactly: no check is added, no role
 * is inferred, and Spring Security is not introduced (it is out of scope). The program's own
 * documented intent is recorded here rather than implemented.
 *
 * <h2>Preserved oddities (practice B5 - dead and odd code is preserved, not cleaned up)</h2>
 * <ul>
 *   <li>{@code :274} {@code *COPY COCRDSL.} and {@code :283} {@code *COPY CSMSG02Y.} are both
 *       commented out, so the program has neither a card-select map area nor an abend-data area. No
 *       {@code SystemMessages.AbendData} equivalent is referenced here.</li>
 *   <li>{@code :436-437}, {@code :452-453}, {@code :480-481} and {@code :580-581} perform
 *       {@code 1000-SEND-MAP THRU 1000-SEND-MAP} - a single-paragraph range that omits
 *       {@code 1000-SEND-MAP-EXIT} - while {@code :495-496} and {@code :511-512} correctly perform
 *       {@code THRU 1000-SEND-MAP-EXIT}. Since the exit paragraph contains only {@code EXIT}, the two
 *       ranges are behaviourally identical. The asymmetry is recorded and not normalised.</li>
 *   <li>{@code :439-440} declares {@code WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE} with an
 *       <em>empty</em> body and {@code :444-445} declares the <em>identical</em> condition again with
 *       the body. Consecutive {@code WHEN} phrases share the statements that follow, so the duplicate
 *       is redundant rather than wrong. Both are written out in
 *       {@link #dispatch}.</li>
 *   <li>{@code :595-596} has the {@code PERFORM 1000-SEND-MAP} of the post-{@code EVALUATE}
 *       input-error block commented out, so that path returns without repainting. Preserved.</li>
 *   <li>{@code :336-343} sets {@code CDEMO-LAST-MAP} but - unlike the cold-start block at
 *       {@code :322-323} - not {@code CDEMO-LAST-MAPSET}. Preserved.</li>
 *   <li>{@code :790} contains a stray {@code I} token in the middle of
 *       {@code 1250-SETUP-ARRAY-ATTRIBS}' row-4 branch. It has no operand and no effect on the
 *       row-4 logic, which is otherwise identical to rows 2, 3 and 5 to 7; it is recorded here
 *       because a reader comparing the two files will find it.</li>
 * </ul>
 *
 * <h2>What is not representable, and is therefore recorded instead</h2>
 * {@code MOVE -1 TO xxxL OF CCRDLIAI} [{@code :770}, {@code :874}, {@code :879}, {@code :885}] places
 * the 3270 cursor. The length item is input-group metadata and never a payload member (gate G9), and
 * {@link CardListRequest.FieldMetadata} rejects a negative length by construction because CICS reports
 * {@code 0} for a field the operator did not touch. The cursor marker therefore has no representable
 * target in the DTO pair, and no payload field is invented for it: the observable half of each of
 * those statements - the {@code DFHRED} colour item and the {@code '*'} marker - is applied, and the
 * cursor placement is documented as not reproduced. See {@link #placeCursor}.
 *
 * <h2>Verification provenance (practice B12, risk R-A)</h2>
 * COBOL cannot be executed in this environment - the Agent Action Plan &sect;0.7.6 records eight
 * independently verified blockers - so every expectation asserted against this class is
 * <strong>statically derived</strong> from {@code COCRDLIC}, its copybooks, its mapset and the
 * {@code app/data/ASCII} fixtures. No captured mainframe baseline exists and none is claimed.
 *
 * <h2>Rules</h2>
 * {@code review_rules} reports that <strong>no user rules were provided</strong> for this project.
 * That is not licence to lower the bar: the Agent Action Plan &sect;0.10.2 enterprise practices
 * <strong>B1-B12</strong> bind in their place, and the ones that bear on this file are cited at the
 * point they apply - B4 above, B5 above, B6 above, B8 (no wildcard import, no dataset literal), B9
 * (constructor injection, no static mutable state), B10 (decision logic reachable without HTTP) and
 * B12 above.
 *
 * <h2>Threading</h2>
 * This bean is a stateless singleton. Its three collaborators are immutable or thread-safe and are
 * constructor-injected; there is no field injection and <strong>no static mutable state</strong>
 * (gate G53). Every value the COBOL holds in {@code WORKING-STORAGE} - {@code WS-SCRN-COUNTER},
 * {@code I-SELECTED}, {@code WS-SCREEN-DATA}, the edit flags and the whole carried communication area -
 * lives in a per-request {@link WorkArea} or in the payload, never on this instance.
 *
 * @see CardListRequest
 * @see CardListResponse
 * @see CardRepository
 */
@RestController
public class CardListController {

    // =================================================================================================
    // THE PAGE SIZE. One constant, one literal, no indirection (gate G39).
    // =================================================================================================

    /**
     * {@code 05 WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7.} -
     * {@code app/cbl/COCRDLIC.cbl:177-178}.
     *
     * <p>The literal {@code 7} appears here and nowhere else. This constant is deliberately
     * <strong>not</strong> externalised: see the class documentation for why a configurable page size
     * would be a parity violation.
     */
    private static final int WS_MAX_SCREEN_LINES = 7;

    /** The first {@code OCCURS} subscript, because COBOL tables are 1-based. */
    private static final int FIRST_ROW = 1;

    /** The last {@code OCCURS} subscript of every seven-element table in this program. */
    private static final int LAST_ROW = WS_MAX_SCREEN_LINES;

    /** {@code MOVE ZERO TO I-SELECTED} - {@code app/cbl/COCRDLIC.cbl:1097}. No row selected. */
    private static final int NO_ROW_SELECTED = 0;

    // =================================================================================================
    // 01 WS-CONSTANTS - app/cbl/COCRDLIC.cbl:176-217, transcribed verbatim at its declared width.
    //
    // Every one of these is this program's OWN literal. Nothing is imported from a sibling program, and
    // in particular the COCRDSLC/COCRDUPC 'CCRDSLA' defect described in the class documentation is not
    // propagated here.
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDLIC'} - {@code :179-180}. */
    static final String LIT_THISPGM = "COCRDLIC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCLI'} - {@code :181-182}, CSD transaction {@code CCLI}. */
    static final String LIT_THISTRANID = "CCLI";

    /** {@code LIT-THISMAPSET PIC X(7) VALUE 'COCRDLI'} - {@code :183-184}. */
    static final String LIT_THISMAPSET = "COCRDLI";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CCRDLIA'} - {@code :185-186}.
     *
     * <p>This is the correct card-list map name. Conflict 2 in the class documentation records that two
     * sibling programs name it {@code 'CCRDSLA'} by mistake; that mistake stays in those programs.
     */
    static final String LIT_THISMAP = "CCRDLIA";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - {@code :187-188}. */
    static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - {@code :189-190}. */
    static final String LIT_MENUTRANID = "CM00";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} - {@code :191-192}. */
    static final String LIT_MENUMAPSET = "COMEN01";

    /**
     * {@code LIT-MENUMAP PIC X(7) VALUE 'COMEN1A'} - {@code :193-194}.
     *
     * <p>Declared by the program and, notably, <strong>never moved into
     * {@code CCARD-NEXT-MAP}</strong>: {@code :395} moves {@code LIT-THISMAP} there instead. Carried
     * here because the declaration is part of the program's contract and because
     * {@link #transferToMenu} documents the substitution against it.
     */
    static final String LIT_MENUMAP = "COMEN1A";

    /** {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} - {@code :195-196}. */
    static final String LIT_CARDDTLPGM = "COCRDSLC";

    /** {@code LIT-CARDDTLTRANID PIC X(4) VALUE 'CCDL'} - {@code :197-198}. */
    static final String LIT_CARDDTLTRANID = "CCDL";

    /** {@code LIT-CARDDTLMAPSET PIC X(7) VALUE 'COCRDSL'} - {@code :199-200}. */
    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    /** {@code LIT-CARDDTLMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :201-202}. */
    static final String LIT_CARDDTLMAP = "CCRDSLA";

    /** {@code LIT-CARDUPDPGM PIC X(8) VALUE 'COCRDUPC'} - {@code :203-204}. */
    static final String LIT_CARDUPDPGM = "COCRDUPC";

    /** {@code LIT-CARDUPDTRANID PIC X(4) VALUE 'CCUP'} - {@code :205-206}. */
    static final String LIT_CARDUPDTRANID = "CCUP";

    /** {@code LIT-CARDUPDMAPSET PIC X(7) VALUE 'COCRDUP'} - {@code :207-208}. */
    static final String LIT_CARDUPDMAPSET = "COCRDUP";

    /** {@code LIT-CARDUPDMAP PIC X(7) VALUE 'CCRDUPA'} - {@code :209-210}. */
    static final String LIT_CARDUPDMAP = "CCRDUPA";

    /**
     * {@code LIT-CARD-FILE PIC X(8) VALUE 'CARDDAT '} - {@code :213-214}.
     *
     * <p>This is the <strong>CICS file name</strong>, which the diagnostic message renders. It is not a
     * dataset name: no {@code AWS.M2.CARDDEMO.*} literal appears anywhere in this class, and the
     * dataset behind the name is resolved from {@code application.yml} by {@link CardRepository}
     * (gate G46). The trailing space is part of the declared width and is preserved.
     */
    static final String LIT_CARD_FILE = CardRepository.BASE_CICS_FILE_NAME;

    /**
     * {@code LIT-CARD-FILE-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} - {@code :215-217}.
     *
     * <p>Declared by the program and never used by it: {@code COCRDLIC} browses the base cluster only,
     * so every {@code STARTBR}, {@code READNEXT}, {@code READPREV} and {@code ENDBR} names
     * {@link #LIT_CARD_FILE}. The alternate-index path is carried here because the declaration is part
     * of the program's contract (practice B5), and it is an access path over the same base cluster
     * rather than a second dataset (gate G45).
     */
    static final String LIT_CARD_FILE_ACCT_PATH = CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME;

    // =================================================================================================
    // MESSAGE LITERALS. Every one is an 88-level VALUE or an inline MOVE literal, transcribed verbatim.
    // They are stored unpadded and padded at the point of the MOVE, because that is where COBOL pads.
    // =================================================================================================

    /** {@code 88 WS-INFORM-REC-ACTIONS} - {@code app/cbl/COCRDLIC.cbl:115-116}, 41 characters. */
    static final String WS_INFORM_REC_ACTIONS = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";

    /** {@code 88 WS-EXIT-MESSAGE} - {@code :119-120}. */
    static final String WS_EXIT_MESSAGE = "PF03 PRESSED.EXITING";

    /** {@code 88 WS-NO-RECORDS-FOUND} - {@code :121-122}. */
    static final String WS_NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    /** {@code 88 WS-MORE-THAN-1-ACTION} - {@code :123-124}. */
    static final String WS_MORE_THAN_1_ACTION = "PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE";

    /** {@code 88 WS-INVALID-ACTION-CODE} - {@code :125-126}. */
    static final String WS_INVALID_ACTION_CODE = "INVALID ACTION CODE";

    /** The inline literal moved at {@code :1021-1023}. */
    static final String MSG_ACCOUNT_FILTER =
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER";

    /** The inline literal moved at {@code :1057-1059}. */
    static final String MSG_CARD_FILTER =
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER";

    /** The inline literal moved at {@code :1219-1220} and {@code :1239}. */
    static final String MSG_NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";

    /** The inline literal moved at {@code :903-904}. */
    static final String MSG_NO_PREVIOUS_PAGES = "NO PREVIOUS PAGES TO DISPLAY";

    /** The inline literal moved at {@code :908-909}. */
    static final String MSG_NO_MORE_PAGES = "NO MORE PAGES TO DISPLAY";

    // =================================================================================================
    // 05 WS-FILE-ERROR-MESSAGE - app/cbl/COCRDLIC.cbl:153-171. Nine spans summing to exactly 80.
    // =================================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error:'} - {@code :154-155}. Eleven characters in a twelve. */
    private static final String FILE_ERROR_PREFIX = "File Error:";

    /** The declared width of that filler: {@code PIC X(12)}. */
    private static final int FILE_ERROR_PREFIX_LENGTH = 12;

    /** {@code ERROR-OPNAME PIC X(8)} - {@code :156-157}. */
    private static final int ERROR_OPNAME_LENGTH = 8;

    /** {@code FILLER PIC X(4) VALUE ' on '} - {@code :158-159}. */
    private static final String FILE_ERROR_ON = " on ";

    /** {@code ERROR-FILE PIC X(9)} - {@code :160-161}. */
    private static final int ERROR_FILE_LENGTH = 9;

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} - {@code :162-164}. */
    private static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code ERROR-RESP PIC X(10)} - {@code :165-166}, and {@code ERROR-RESP2 PIC X(10)} - {@code :169}. */
    private static final int ERROR_RESP_LENGTH = 10;

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} - {@code :167-168}. */
    private static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5)} - {@code :171}. Declared with no {@code VALUE}, so spaces. */
    private static final int FILE_ERROR_TRAILER_LENGTH = 5;

    /**
     * The composed width of {@code WS-FILE-ERROR-MESSAGE}:
     * {@code 12 + 8 + 4 + 9 + 15 + 10 + 7 + 10 + 5 = }<strong>80</strong>.
     */
    static final int FILE_ERROR_MESSAGE_LENGTH = FILE_ERROR_PREFIX_LENGTH + ERROR_OPNAME_LENGTH
            + 4 + ERROR_FILE_LENGTH + 15 + ERROR_RESP_LENGTH + 7 + ERROR_RESP_LENGTH
            + FILE_ERROR_TRAILER_LENGTH;

    /**
     * The digit count of {@code WS-RESP-CD} and {@code WS-REAS-CD}, both
     * {@code PIC S9(09) COMP} - {@code app/cbl/COCRDLIC.cbl:47-50}.
     *
     * <p>{@code MOVE WS-RESP-CD TO ERROR-RESP} moves a nine-digit binary integer into a
     * {@code PIC X(10)} item. That takes the alphanumeric rule over the sender's <em>display</em> form,
     * so response 13 renders as {@code "000000013 "} - nine zero-filled digits left-justified in ten
     * characters - and never as {@code "        13"}.
     */
    private static final int RESP_DIGITS = 9;

    /** {@code MOVE 'READ' TO ERROR-OPNAME} - {@code :1226}, {@code :1250}, {@code :1312}, {@code :1365}. */
    static final String ERROR_OPNAME_READ = CardRepository.READ_OPERATION_NAME;

    // =================================================================================================
    // Declared widths this class moves into, all taken from the copybooks rather than restated.
    // =================================================================================================

    /** {@code WS-ERROR-MSG PIC X(75)} - {@code :117}, the same width as {@code CCARD-ERROR-MSG}. */
    private static final int WS_ERROR_MSG_LENGTH = CardScreenState.CCARD_ERROR_MSG_LENGTH;

    /** {@code WS-INFO-MSG PIC X(45)} - {@code :112}, the same width as {@code INFOMSGO}. */
    private static final int WS_INFO_MSG_LENGTH = CardListResponse.INFOMSGO_LENGTH;

    /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :138}, the browse key. */
    private static final int WS_CARD_RID_CARDNUM_LENGTH = CardRecord.CARD_NUM_LENGTH;

    /** {@code CARD-ACCT-ID PIC 9(11)} rendered into an eleven-character screen or table field. */
    private static final int ACCT_ID_DIGITS = CardRecord.CARD_ACCT_ID_LENGTH;

    /** {@code CC-CARD-NUM-N PIC 9(16)} rendered into a sixteen-character field. */
    private static final int CARD_NUM_DIGITS = CardScreenState.CC_CARD_NUM_LENGTH;

    // =================================================================================================
    // The HTTP surface.
    // =================================================================================================

    /**
     * The route: {@code GET /api/cards}, the REST projection of CSD transaction {@code CCLI}
     * (Agent Action Plan &sect;0.3.9).
     */
    static final String CARD_LIST_PATH = "/api/cards";

    /**
     * The query parameter carrying the terminal's attention identifier, the {@code EIBAID} byte that
     * {@code PERFORM YYYY-STORE-PFKEY} [{@code app/cbl/COCRDLIC.cbl:349-350}] reads.
     *
     * <p>It is a raw AID byte expressed as an unsigned integer {@code 0..255}, compared against the
     * {@link CicsAid} constants and never against a byte re-declared here. When it is absent the
     * request is treated as an {@link CicsAid#DFHENTER}: a CICS terminal always presents some AID, and
     * every AID other than {@code ENTER}, {@code PF3}, {@code PF7} and {@code PF8} is forced to
     * {@code ENTER} anyway by the program's own guard at {@code :378-380}.
     *
     * <p>The name is {@link AidRequestParameter#CANONICAL_NAME} and is not spelled again here, so this
     * route cannot drift from its siblings: a caller that learned the name on one screen uses it on
     * every screen, and a spelling that reached no handler used to be discarded by Spring with the key
     * silently becoming {@code ENTER}.
     */
    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    /**
     * The alternate spelling of {@link #EIBAID_PARAM}, accepted on every online route.
     *
     * <p>Bound so that {@link AidRequestParameter#ALTERNATE_NAME} names the same key here as it does on
     * {@code GET /api/cards/{cardNum}}, where it was the original declared name.
     */
    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    /** The lowest value an unsigned AID byte can take. */
    private static final int AID_MIN = 0;

    /** The highest value an unsigned AID byte can take. */
    private static final int AID_MAX = 255;

    // =================================================================================================
    // Collaborators. Three, all constructor-injected, all immutable or thread-safe (practice B9,
    // gate G53). There is no other state on this class: everything the COBOL keeps in WORKING-STORAGE
    // lives in a per-request WorkArea.
    // =================================================================================================

    /**
     * The card file. Every {@code STARTBR}, {@code READNEXT}, {@code READPREV} and {@code ENDBR} goes
     * through here; this class never touches a {@code JdbcTemplate} and never names a dataset
     * (gate G46).
     */
    private final CardRepository cardRepository;

    /**
     * The fixed-width codec, carrying the dataset code page.
     *
     * <p>It owns the two {@code MOVE} rules, and every cross-width move in this class goes through it:
     * {@link FixedWidthCodec#movePicX} pads and truncates on the <strong>right</strong> for
     * {@code PIC X}, {@link FixedWidthCodec#movePic9} zero-fills and truncates on the
     * <strong>left</strong> for {@code PIC 9}. Plain Java assignment gets the direction wrong, and
     * {@code MOVE} is the dominant parity risk in this codebase - 2,795 sites across the 28 programs
     * (Agent Action Plan &sect;0.7.1).
     */
    private final FixedWidthCodec codec;

    /**
     * The clock {@code FUNCTION CURRENT-DATE} [{@code app/cbl/COCRDLIC.cbl:645}, {@code :652}] is read
     * from.
     *
     * <p>Injected rather than consulted inline so a test can pin it with {@code Clock.fixed(...)} and
     * assert an exact rendered header. {@link Clock} is immutable and thread-safe, so one shared
     * instance serves every concurrent request.
     */
    private final Clock clock;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Wiring constructor, used by the container.
     *
     * <p>It exists so the canonical constructor can take the codec itself. The code page arrives as an
     * explicit argument selected by bean name, because a fixed-width mainframe record is bytes in a
     * specific code page and the platform default is never consulted anywhere in this module
     * (practice B8).
     *
     * @param cardRepository the card file
     * @param datasetCharset the active dataset code page,
     *                       {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}
     * @param clock          the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if a width this screen depends on disagrees with the copybook it
     *                               came from - see {@link #verifyScreenContract()}
     */
    @Autowired
    public CardListController(CardRepository cardRepository,
                              @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                              Charset datasetCharset,
                              Clock clock) {
        this(cardRepository,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: the card list renders fixed-width fields, so "
                                + "the code page is stated explicitly and never taken from the "
                                + "platform")),
                clock);
    }

    /**
     * Canonical constructor. This is the one a unit test calls: it needs no Spring context, no
     * {@code MockMvc} and no backend, only a stubbed {@link CardRepository} (practice B10, gate G51).
     *
     * <p>It verifies the screen's geometry against the copybooks before returning, so a width that has
     * drifted fails at context refresh rather than mid-request.
     *
     * @param cardRepository the card file
     * @param codec          the fixed-width codec, carrying the dataset code page
     * @param clock          the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if a width this screen depends on disagrees with the copybook it
     *                               came from
     */
    public CardListController(CardRepository cardRepository, FixedWidthCodec codec, Clock clock) {
        this.cardRepository = Objects.requireNonNull(cardRepository, "A CardRepository is required: "
                + "the card list reaches CARDDAT only through it, never through a JdbcTemplate and "
                + "never by dataset name");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it owns the "
                + "PIC X and PIC 9 MOVE rules every field of this screen is written with");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read "
                + "from an injected clock so a test can pin the rendered header");
        verifyScreenContract();
        verifyNavigationContract();
    }

    // =================================================================================================
    // Contract verification. Two guards, both run at construction, both failing loudly.
    //
    // The brief's instruction for this file is to map to the DTO pair faithfully and to FAIL LOUDLY if a
    // field width disagrees with app/cpy-bms/COCRDLI.CPY. These are that guard. They are cheap, they run
    // once per context, and each one names the copybook line it defends.
    // =================================================================================================

    /**
     * Checks every width and count this screen's behaviour depends on against the type that owns it.
     *
     * <p>None of these can drift silently: a page size that disagreed would page wrongly, a title width
     * that disagreed would pad the header wrongly, and a message width that disagreed would truncate
     * the wrong number of characters. Each check names its authority.
     *
     * @throws IllegalStateException if any check fails
     */
    static void verifyScreenContract() {
        // The page size, asserted against both halves of the DTO pair. app/cbl/COCRDLIC.cbl:177-178.
        requireAgreement(WS_MAX_SCREEN_LINES, CardListRequest.PAGE_SIZE,
                "WS-MAX-SCREEN-LINES and CardListRequest.PAGE_SIZE");
        requireAgreement(WS_MAX_SCREEN_LINES, CardListResponse.PAGE_SIZE,
                "WS-MAX-SCREEN-LINES and CardListResponse.PAGE_SIZE");
        requireAgreement(LAST_ROW, CardListResponse.LAST_ROW,
                "the last OCCURS subscript and CardListResponse.LAST_ROW");

        // The composed diagnostic. app/cbl/COCRDLIC.cbl:153-171 sums to exactly eighty.
        requireAgreement(80, FILE_ERROR_MESSAGE_LENGTH,
                "the composed width of WS-FILE-ERROR-MESSAGE");

        // COTTL01Y's two titles against the map items they are moved into. COCRDLIC.cbl:647-648.
        requireAgreement(ScreenTitles.TITLE_LENGTH, CardListResponse.TITLE01O_LENGTH,
                "CCDA-TITLE01 and TITLE01O");
        requireAgreement(ScreenTitles.TITLE_LENGTH, CardListResponse.TITLE02O_LENGTH,
                "CCDA-TITLE02 and TITLE02O");

        // CSMSG01Y is COPYed at COCRDLIC.cbl:281 and its two texts are never moved by this program,
        // which uses its own 88-level literals instead. What the copy does fix is the relationship
        // between a standard 50-character message and this map's two message lines: INFOMSGO is
        // narrower, so a standard text would lose its last five characters, and ERRMSGO is wider, so one
        // would be padded. Both relationships are asserted rather than assumed, because a future change
        // to either width would change which of the two happens.
        requireOrdering(CardListResponse.INFOMSGO_LENGTH, SystemMessages.MESSAGE_LENGTH,
                "INFOMSGO must stay narrower than a standard CSMSG01Y message");
        requireOrdering(SystemMessages.MESSAGE_LENGTH, CardListResponse.ERRMSGO_LENGTH,
                "ERRMSGO must stay wider than a standard CSMSG01Y message");

        // CSUSR01Y is COPYed at COCRDLIC.cbl:285 and no USRSEC record is ever read by this program.
        // What the copy fixes is the width of the user-type byte this program writes on every exit:
        // SET CDEMO-USRTYP-USER TO TRUE is a one-byte move, and SEC-USR-TYPE is the one byte it mirrors.
        requireAgreement(SecUserRecord.SEC_USR_TYPE_LENGTH, NavigationContext.USER_TYPE_LENGTH,
                "SEC-USR-TYPE and CDEMO-USER-TYPE");

        // The two filter fields, from CVCRD01Y to the map items they are moved between.
        requireAgreement(CardScreenState.CC_ACCT_ID_LENGTH, CardListResponse.ACCTSIDO_LENGTH,
                "CC-ACCT-ID and ACCTSIDO");
        requireAgreement(CardScreenState.CC_CARD_NUM_LENGTH, CardListResponse.CARDSIDO_LENGTH,
                "CC-CARD-NUM and CARDSIDO");

        // The three values projected out of each 150-byte card record into a 28-byte table row.
        requireAgreement(CardRecord.CARD_NUM_LENGTH, CardListResponse.CRDNUM_LENGTH,
                "CARD-NUM and CRDNUMn");
        requireAgreement(CardRecord.CARD_ACCT_ID_LENGTH, CardListResponse.ACCTNO_LENGTH,
                "CARD-ACCT-ID and ACCTNOn");
        requireAgreement(CardRecord.CARD_ACTIVE_STATUS_LENGTH, CardListResponse.CRDSTS_LENGTH,
                "CARD-ACTIVE-STATUS and CRDSTSn");
    }

    /**
     * Checks the three navigation targets against the types that own them - the Conflict 1 resolution
     * described in the class documentation, made executable.
     *
     * <p>{@link CardSelectResponse} and {@link CardSelectRequest} are referenced here and
     * <strong>nowhere else</strong>, purely as navigation targets: this guard confirms that the
     * {@code LIT-CARDDTL*} literals {@code COCRDLIC} declares for itself name the same screen the
     * card-select types describe, and that the two values handed over at {@code :531-534} fit the input
     * fields the next screen declares for them. Not one {@code COCRDSL} symbolic-map field is written
     * by this class.
     *
     * @throws IllegalStateException if a target or a handover width disagrees
     */
    static void verifyNavigationContract() {
        // COCRDLIC.cbl:526-529 against the card-detail screen's own declared identity.
        requireAgreement(LIT_CARDDTLPGM, CardSelectResponse.THIS_PROGRAM,
                "LIT-CARDDTLPGM and the card-detail program");
        requireAgreement(LIT_CARDDTLTRANID, CardSelectResponse.THIS_TRANID,
                "LIT-CARDDTLTRANID and the card-detail transaction");
        requireAgreement(LIT_CARDDTLMAPSET, CardSelectResponse.THIS_MAPSET,
                "LIT-CARDDTLMAPSET and the card-detail mapset");
        requireAgreement(LIT_CARDDTLMAP, CardSelectResponse.MAP_NAME,
                "LIT-CARDDTLMAP and the card-detail map");

        // COCRDLIC.cbl:531-534 hands the selected row's account id and card number over in the
        // communication area. The next screen receives them into its two filter fields, so the widths
        // must agree - an eleven-digit account id into an eleven-character field, sixteen into sixteen.
        requireAgreement(NavigationContext.ACCT_ID_LENGTH, CardSelectRequest.ACCTSID_LENGTH,
                "CDEMO-ACCT-ID and the card-detail ACCTSID field");
        requireAgreement(NavigationContext.CARD_NUM_LENGTH, CardSelectRequest.CARDSID_LENGTH,
                "CDEMO-CARD-NUM and the card-detail CARDSID field");

        // The navigation triple's widths. CVCRD01Y declares the mapset and the map as PIC X(7) and the
        // program as PIC X(8); widening either of the sevens to eight would be wrong on the wire.
        requireAgreement(8, CardScreenState.CCARD_NEXT_PROG_LENGTH, "CCARD-NEXT-PROG");
        requireAgreement(7, CardScreenState.CCARD_NEXT_MAPSET_LENGTH, "CCARD-NEXT-MAPSET");
        requireAgreement(7, CardScreenState.CCARD_NEXT_MAP_LENGTH, "CCARD-NEXT-MAP");
    }

    /**
     * Fails when two widths or counts that must agree do not.
     *
     * @param expected the authority's value
     * @param actual   the value under test
     * @param subject  what the two are, for the message
     * @throws IllegalStateException if they differ
     */
    static void requireAgreement(int expected, int actual, String subject) {
        if (expected != actual) {
            throw new IllegalStateException(subject + " must agree; app/cbl/COCRDLIC.cbl and its "
                    + "copybooks fix this at " + expected + " but the collaborating type declares "
                    + actual + ". The card list decodes by absolute width, so a disagreement here is "
                    + "silently wrong data rather than a visible failure.");
        }
    }

    /**
     * Fails when two names that must agree do not.
     *
     * @param expected the authority's value
     * @param actual   the value under test
     * @param subject  what the two are, for the message
     * @throws IllegalStateException if they differ
     */
    static void requireAgreement(String expected, String actual, String subject) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(subject + " must agree; app/cbl/COCRDLIC.cbl declares '"
                    + expected + "' but the collaborating type declares '" + actual + "'. The "
                    + "navigation target is what the client calls next, so a disagreement here sends "
                    + "the operator to the wrong screen.");
        }
    }

    /**
     * Fails when a width that must stay strictly below another does not.
     *
     * @param smaller the value that must be the lesser
     * @param larger  the value that must be the greater
     * @param subject what the relationship is, for the message
     * @throws IllegalStateException if the ordering does not hold
     */
    static void requireOrdering(int smaller, int larger, String subject) {
        if (smaller >= larger) {
            throw new IllegalStateException(subject + ", but " + smaller + " is not below " + larger
                    + ". Which of padding and truncation happens on this screen's message lines "
                    + "depends on that ordering.");
        }
    }

    // =================================================================================================
    // THE HTTP SURFACE. Deliberately thin: it binds, it resolves the AID byte, and it delegates. Not one
    // decision is taken here, so every decision below is reachable from a plain JUnit test with no
    // MockMvc in the path (practice B10, gate G51).
    //
    // A NOTE ON VISIBILITY, since it is a deliberate choice and not an oversight. Exactly one method on
    // this class is public - the request mapping - plus the two constructors. Every paragraph of COCRDLIC
    // is a PACKAGE-VISIBLE method, which is what lets a plain JUnit test in this package drive an
    // individual paragraph with a stubbed CardRepository and assert its effect on the work area directly.
    // That matters beyond convenience: three arms of this program are unreachable from 0000-MAIN because
    // the source itself cannot reach them - 1400-SETUP-MESSAGE's WHEN OTHER at :920-921 is the clearest,
    // since :669 makes the arm above it always match - and preserving dead code (practice B5) while still
    // proving it does what the COBOL says is only possible if the paragraph can be called on its own. No
    // decision is made in a private method, and the AAP mandates NO service class for this screen, so
    // none is invented.
    // =================================================================================================

    /**
     * {@code GET /api/cards} - the REST projection of CSD transaction {@code CCLI}.
     *
     * <p><strong>An absent body is the cold start.</strong> {@code app/cbl/COCRDLIC.cbl:315} tests
     * {@code IF EIBCALEN = 0} to distinguish a transaction started fresh from one continuing a
     * pseudo-conversation, and a {@code GET} nobody sent a payload with is exactly that: the parameter
     * is {@code required = false} and arrives {@code null}, which
     * {@link #listCards(CardListRequest, byte)} treats as {@code EIBCALEN = 0}. A continuing request
     * sends the payload it received last time, carrying the communication area, the pagination cursor
     * and the screen the operator was looking at - never a server-side session (gate G37).
     *
     * <p><strong>The reply is the screen plus its metadata.</strong> {@code CCRDLIAO}'s 45 payload members
     * are unwrapped at the top level of the JSON, exactly as before, and the map's 45 attribute quads, the
     * colour of the error line and the field the {@code MOVE -1} aimed the cursor at travel beside them
     * under {@code screenMetadata}. Those items are metadata by the symbolic map's own declaration and are
     * therefore not payload members (gate G9), but a client that cannot see them cannot repaint a field the
     * program turned red or place the cursor where it was asked for - so they are published in the one
     * envelope every online screen in this module uses rather than being dropped.
     *
     * <p><strong>The attention identifier is named the same way on every route.</strong> Both accepted
     * spellings are bound - {@link AidRequestParameter#CANONICAL_NAME} and
     * {@link AidRequestParameter#ALTERNATE_NAME} - and folded by
     * {@link AidRequestParameter#resolve(Integer, Integer)} before anything looks at the value. Binding
     * only one of them is what let a caller's {@code PF3} be discarded by Spring and executed as
     * {@code ENTER}, with nothing in the response saying the key had not been understood.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid  the terminal's attention identifier as an unsigned byte {@code 0..255} under the
     *                canonical parameter name, or {@code null} for {@link CicsAid#DFHENTER}
     * @param eibAid  the same value under the alternate spelling; at most one of the two need be sent
     * @return the {@code CCRDLIAO} projection - or, on a transfer of control, the navigation triple naming
     *         where the client goes next - together with this screen's presentation metadata
     * @throws IllegalArgumentException if the AID is outside {@code 0..255}, or if both spellings are
     *                                  present and disagree
     */
    @GetMapping(path = CARD_LIST_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<CardListResponse> getCards(
            @Valid @RequestBody(required = false) CardListRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        CardListResponse painted =
                listCards(request, resolveEibAid(AidRequestParameter.resolve(eibaid, eibAid)));
        return ScreenResponse.of(painted, painted.screenMetadata());
    }

    /**
     * Resolves the {@code EIBAID} byte the request presented.
     *
     * <p>{@code EIBAID} is one byte of the CICS exec interface block. A raw byte cannot travel in a
     * query string, so it arrives as its unsigned integer value and is narrowed here; the range is
     * checked rather than silently wrapped, because {@code 300} is not an AID and quietly becoming
     * {@code 0x2C} would send the request down a branch the caller did not ask for.
     *
     * @param eibaid the unsigned byte value, or {@code null} when the caller named no key
     * @return the raw AID byte
     * @throws IllegalArgumentException if {@code eibaid} is outside {@code 0..255}
     */
    static byte resolveEibAid(Integer eibaid) {
        if (eibaid == null) {
            // A CICS terminal always presents some AID. ENTER is the one the program itself falls back
            // to for every key it does not handle (app/cbl/COCRDLIC.cbl:378-380), so it is the only
            // default that cannot reach a branch the operator could not have reached.
            return CicsAid.DFHENTER;
        }
        int value = eibaid;
        if (value < AID_MIN || value > AID_MAX) {
            throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
        }
        return (byte) value;
    }

    // =================================================================================================
    // 0000-MAIN - app/cbl/COCRDLIC.cbl:298-602.
    //
    // The paragraph in its source order, with each GO TO restructured into the return it stands for
    // (rule R7). All sixteen GO TOs in this program are benign: fourteen jump to a paragraph's own
    // -EXIT label and two jump to the shared COMMON-RETURN terminal. None forms a loop - CBSTM03A is the
    // only program in the migration that has backward, loop-forming GO TOs.
    // =================================================================================================

    /**
     * Runs the whole transaction and returns the screen, or the navigation triple on a transfer.
     *
     * <p>This is the decision entry point: it takes a payload and an AID byte, needs no HTTP and no
     * Spring context, and is what every behavioural test drives.
     *
     * @param incoming the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid   the raw {@code EIBAID} byte
     * @return the response; never {@code null}
     */
    CardListResponse listCards(CardListRequest incoming, byte eibAid) {
        // :300-302  INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA - a fresh WorkArea IS that
        // statement, and it is created per call so nothing about this bean is stateful (gate G53).
        return listCards(incoming, eibAid, new WorkArea());
    }

    /**
     * The same transaction, run against a caller-supplied work area so that every
     * {@code WORKING-STORAGE} value the COBOL sets is observable afterwards.
     *
     * <p>{@code COCRDLIC} sets values that never reach the screen -
     * {@code SET WS-EXIT-MESSAGE TO TRUE} at {@code :396} is discarded because the transfer at
     * {@code :402} skips {@code 1000-SEND-MAP} entirely - and this overload is how a test asserts them
     * without a payload field being invented to carry them. Production traffic uses
     * {@link #listCards(CardListRequest, byte)}.
     *
     * @param incoming the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid   the raw {@code EIBAID} byte
     * @param ws       the work area to run in, in its {@code INITIALIZE}d state
     * @return the response; never {@code null}
     * @throws NullPointerException if {@code ws} is {@code null}
     */
    CardListResponse listCards(CardListRequest incoming, byte eibAid, WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required; the INITIALIZEd state is new WorkArea()");
        // An absent payload is EIBCALEN = 0. A fresh CardListRequest has no communication area either -
        // its navigationContext is null by construction - so the two arrive at the same test below.
        CardListRequest request = incoming == null ? new CardListRequest() : incoming;
        CardListResponse response = new CardListResponse();

        // :307  MOVE LIT-THISTRANID TO WS-TRANID
        ws.wsTranid = codec.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH);

        // :311  SET WS-ERROR-MSG-OFF TO TRUE
        ws.setWsErrorMsgOff();

        // :315-332  Retrieve the passed data if any; initialise it on a first run.
        ws.eibcalenZero = !request.hasNavigationContext();
        if (ws.eibcalenZero) {
            // INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA, then eight assignments in order.
            ws.commarea = NavigationContext.empty()
                    .withFromTranid(LIT_THISTRANID)     // :318
                    .withFromProgram(LIT_THISPGM)       // :319
                    .withUserTypeUser()                 // :320  SET CDEMO-USRTYP-USER TO TRUE
                    .withPgmEnter()                     // :321  SET CDEMO-PGM-ENTER TO TRUE
                    .withLastMap(LIT_THISMAP)           // :322
                    .withLastMapset(LIT_THISMAPSET);    // :323
            ws.cursor = PageCursor.initialised()
                    .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)          // :324
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);   // :325
            ws.screenRows = initializedScreenRowTable();
        } else {
            // MOVE DFHCOMMAREA(1:160) TO CARDDEMO-COMMAREA, then
            // MOVE DFHCOMMAREA(161:254) TO WS-THIS-PROGCOMMAREA - the cursor and the row table.
            ws.commarea = request.getNavigationContext();
            ws.cursor = request.getPageCursor();
            ws.screenRows = restoreScreenRowTable(request);
        }

        // :336-343  If we are coming in from the menu, forget the past and start afresh.
        if (ws.commarea.isEnter() && !isThisProgram(ws.commarea)) {
            // INITIALIZE WS-THIS-PROGCOMMAREA - both halves of the 254-byte area.
            ws.cursor = PageCursor.initialised();
            ws.screenRows = initializedScreenRowTable();
            ws.commarea = ws.commarea
                    .withPgmEnter()             // :339
                    .withLastMap(LIT_THISMAP);  // :340 - and NOT CDEMO-LAST-MAPSET; see the class docs
            ws.cursor = ws.cursor
                    .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)          // :341
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);   // :342
        }

        // :349-350  PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT - COPY 'CSSTRPFY' at :1416.
        //
        // storePfKey answers what CCARD-AID holds once the paragraph has run, which is not the same
        // question as "which token does this byte map to": the copybook's EVALUATE has no WHEN OTHER and
        // does not clear the field first, so an unrecognised AID leaves the previous token standing. Here
        // the previous token is always spaces, because :300 has just INITIALIZEd CC-WORK-AREA, so an
        // unrecognised byte leaves CCARD-AID matching no condition at all - which the validity guard
        // immediately below then turns into ENTER.
        Optional<AidKey> storedAid = PfKeyResolver.storePfKey(eibAid, ws.ccWorkArea.aidKey());
        storedAid.ifPresent(ws.ccWorkArea::setCcardAidCondition);

        // :357-362  If something was passed and it came from this program, read and edit the inputs.
        if (!ws.eibcalenZero && isThisProgram(ws.commarea)) {
            receiveMap(request, ws);
        }

        // :370-380  Is the mapped key valid at this point? F3 exits, ENTER lists, F8 pages down, F7 pages
        // up. Everything else - including an AID that matched no condition at all - becomes ENTER.
        ws.setPfkInvalid();
        if (ws.ccWorkArea.isCcardAidEnter()
                || ws.ccWorkArea.isCcardAidPfk03()
                || ws.ccWorkArea.isCcardAidPfk07()
                || ws.ccWorkArea.isCcardAidPfk08()) {
            ws.setPfkValid();
        }
        if (ws.isPfkInvalid()) {
            ws.ccWorkArea.setCcardAidCondition(AidKey.ENTER);
        }

        // :384-406  If the user pressed PF3, go back to the main menu.
        if (ws.ccWorkArea.isCcardAidPfk03() && isThisProgram(ws.commarea)) {
            return transferToMenu(ws, response);
        }

        // :410-414  If the user did not press PF8, reset the last-page flag.
        if (ws.ccWorkArea.isCcardAidPfk08()) {
            // CONTINUE - the flag is left exactly as the caller sent it, which is how the "no more pages"
            // message at :905-909 can distinguish a second PF8 on the last page from the first.
            ws.pfk08Continued = true;
        } else {
            ws.cursor = ws.cursor.withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);
        }

        // :418-583  Now we decide what to do. Every arm terminates the program, so the dispatcher always
        // produces the response.
        return dispatch(request, ws, response);
    }

    /**
     * Whether {@code CDEMO-FROM-PROGRAM} names this program -
     * {@code CDEMO-FROM-PROGRAM EQUAL LIT-THISPGM}, tested at {@code app/cbl/COCRDLIC.cbl:337},
     * {@code :358}, {@code :385}, {@code :460}, {@code :519} and {@code :547}.
     *
     * <p>The comparison is made at the declared {@code PIC X(8)} width so a value that arrived shorter
     * than its field compares the way COBOL compares it, padded on the right rather than unequal.
     *
     * @param commarea the carried communication area
     * @return {@code true} when the two eight-character names are equal
     */
    boolean isThisProgram(NavigationContext commarea) {
        return codec.movePicX(commarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH)
                .equals(LIT_THISPGM);
    }

    // =================================================================================================
    // THE MAIN DISPATCHER - EVALUATE TRUE, app/cbl/COCRDLIC.cbl:418-583.
    //
    // EVALUATE is ordered: the first matching WHEN wins and WHEN OTHER is last (gate G30). The arms below
    // are in source order, one for one, and no condition is rewritten, combined or hoisted. Every arm
    // terminates the program - six by GO TO COMMON-RETURN and two by EXEC CICS XCTL - so this method
    // always produces the response.
    // =================================================================================================

    /**
     * Chooses and performs the arm the AID and the edited inputs select.
     *
     * <p><strong>On the code that follows {@code END-EVALUATE}.</strong> {@code :586-601} is a
     * conditional block ending in {@code GO TO COMMON-RETURN} that is <em>unreachable</em>: the six arms
     * that fall through to it do not exist, because every arm either transfers control or jumps to
     * {@code COMMON-RETURN} first. Its statements are not lost. Lines {@code :587-594} are
     * character-for-character the same seven moves as the input-error arm's own preamble at
     * {@code :423-430}, and they live in {@link #applyInputErrorReturnState(WorkArea)}, which that arm
     * calls; {@code :600}'s {@code MOVE LIT-THISPGM TO CCARD-NEXT-PROG} is what the same arm performs at
     * {@code :428}. Nothing is deleted and nothing dead is left behind (practice B5).
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the response under construction
     * @return the finished response; never {@code null}
     */
    CardListResponse dispatch(CardListRequest request, WorkArea ws,
            CardListResponse response) {

        // ---- :419-438  WHEN INPUT-ERROR: ask for corrections to the inputs -------------------------
        if (ws.isInputError()) {
            applyInputErrorReturnState(ws);
            // :431-435  Only re-read when neither filter is the thing that failed. A filter that failed
            // edit would browse on a value the operator has to correct first.
            if (!ws.isFlgAcctfilterNotOk() && !ws.isFlgCardfilterNotOk()) {
                readForward(ws);
            }
            sendMap(request, ws, response);   // :436-437  THRU 1000-SEND-MAP - the short range
            return commonReturn(ws, response);
        }

        // ---- :439-454  WHEN CCARD-AID-PFK07 AND CA-FIRST-PAGE: page up, already on the first page ---
        //
        // :439-440 declares this condition with an EMPTY body and :444-445 declares the IDENTICAL
        // condition again immediately after, this time with the body. Consecutive WHEN phrases share the
        // statements that follow them, so the two sites select ONE arm, not two, and the duplicate is
        // redundant rather than a second decision. It is recorded here and not normalised (practice B5).
        if (ws.ccWorkArea.isCcardAidPfk07() && ws.cursor.isFirstPage()) {
            // :446-447  MOVE WS-CA-FIRST-CARD-NUM TO WS-CARD-RID-CARDNUM.
            // :448-449  the matching MOVE of the account id into WS-CARD-RID-ACCT-ID is commented out,
            //           so the browse is keyed on the card number alone. Preserved.
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            readForward(ws);                  // :450-451
            sendMap(request, ws, response);   // :452-453  THRU 1000-SEND-MAP - the short range
            return commonReturn(ws, response);
        }

        // ---- :458-482  WHEN CCARD-AID-PFK03 / WHEN CDEMO-PGM-REENTER AND not from this program ------
        //
        // Two conditions sharing one body. The first is reachable only when PF3 arrived from somewhere
        // other than this program, because :384-406 has already transferred control otherwise.
        if (ws.ccWorkArea.isCcardAidPfk03()
                || (ws.commarea.isReenter() && !isThisProgram(ws.commarea))) {
            // :462-463  INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA - both halves of the area.
            ws.commarea = NavigationContext.empty()
                    .withFromTranid(LIT_THISTRANID)     // :464
                    .withFromProgram(LIT_THISPGM)       // :465
                    .withUserTypeUser()                 // :466
                    .withPgmEnter()                     // :467
                    .withLastMap(LIT_THISMAP)           // :468
                    .withLastMapset(LIT_THISMAPSET);    // :469
            ws.cursor = PageCursor.initialised()
                    .withScreenNum(CardListRequest.FIRST_PAGE_SCREEN_NUM)          // :470
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN);   // :471
            ws.screenRows = initializedScreenRowTable();
            // :473-474  The key is read AFTER the INITIALIZE, so it is spaces - which positions the
            // browse at the very first record, spaces sorting below every digit in either code page.
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            readForward(ws);                  // :478-479
            sendMap(request, ws, response);   // :480-481  THRU 1000-SEND-MAP - the short range
            return commonReturn(ws, response);
        }

        // ---- :486-497  WHEN CCARD-AID-PFK08 AND CA-NEXT-PAGE-EXISTS: page down ----------------------
        if (ws.ccWorkArea.isCcardAidPfk08() && ws.cursor.isNextPageExists()) {
            // :488-489  the LAST key of the page just shown, so the next page starts after it.
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.lastCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            // :492  ADD +1 TO WS-CA-SCREEN-NUM
            ws.cursor = ws.cursor.withScreenNum(addToScreenNum(ws.cursor.screenNum(), 1));
            readForward(ws);                  // :493-494
            sendMap(request, ws, response);   // :495-496  THRU 1000-SEND-MAP-EXIT - the full range
            return commonReturn(ws, response);
        }

        // ---- :501-513  WHEN CCARD-AID-PFK07 AND NOT CA-FIRST-PAGE: page up --------------------------
        if (ws.ccWorkArea.isCcardAidPfk07() && !ws.cursor.isFirstPage()) {
            // :504-505  the FIRST key of the page just shown, so the browse walks backwards from it.
            ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),
                    WS_CARD_RID_CARDNUM_LENGTH);
            // :508  SUBTRACT 1 FROM WS-CA-SCREEN-NUM
            ws.cursor = ws.cursor.withScreenNum(addToScreenNum(ws.cursor.screenNum(), -1));
            readBackwards(ws);                // :509-510
            sendMap(request, ws, response);   // :511-512  THRU 1000-SEND-MAP-EXIT - the full range
            return commonReturn(ws, response);
        }

        // ---- :517-541  WHEN ENTER AND VIEW-REQUESTED-ON(I-SELECTED) AND from this program -----------
        //                Transfer to the card detail view.
        if (ws.ccWorkArea.isCcardAidEnter()
                && ws.isViewRequestedOnSelected()
                && isThisProgram(ws.commarea)) {
            return transferToCard(ws, response, LIT_CARDDTLPGM, LIT_CARDDTLMAPSET, LIT_CARDDTLMAP);
        }

        // ---- :545-569  WHEN ENTER AND UPDATE-REQUESTED-ON(I-SELECTED) AND from this program ---------
        //                Transfer to the card update program.
        if (ws.ccWorkArea.isCcardAidEnter()
                && ws.isUpdateRequestedOnSelected()
                && isThisProgram(ws.commarea)) {
            return transferToCard(ws, response, LIT_CARDUPDPGM, LIT_CARDUPDMAPSET, LIT_CARDUPDMAP);
        }

        // ---- :572-582  WHEN OTHER: list from the current start key ----------------------------------
        ws.wsCardRidCardnum = codec.movePicX(ws.cursor.firstCardNum(),   // :574-575
                WS_CARD_RID_CARDNUM_LENGTH);
        readForward(ws);                      // :578-579
        sendMap(request, ws, response);       // :580-581  THRU 1000-SEND-MAP - the short range
        return commonReturn(ws, response);
    }

    /**
     * The seven moves the input-error path makes before it repaints -
     * {@code app/cbl/COCRDLIC.cbl:423-430}, repeated verbatim as the unreachable tail at
     * {@code :587-594}.
     *
     * @param ws the work area
     */
    void applyInputErrorReturnState(WorkArea ws) {
        // :423  MOVE WS-ERROR-MSG TO CCARD-ERROR-MSG - X(75) into X(75), so neither padded nor truncated.
        ws.ccWorkArea.setCcardErrorMsg(codec.movePicX(ws.wsErrorMsg,
                CardScreenState.CCARD_ERROR_MSG_LENGTH));
        ws.commarea = ws.commarea
                .withFromProgram(LIT_THISPGM)       // :424
                .withLastMapset(LIT_THISMAPSET)     // :425
                .withLastMap(LIT_THISMAP);          // :426
        ws.ccWorkArea.setCcardNextProg(LIT_THISPGM);         // :428
        ws.ccWorkArea.setCcardNextMapset(LIT_THISMAPSET);    // :429
        ws.ccWorkArea.setCcardNextMap(LIT_THISMAP);          // :430
    }

    /**
     * Stores a value into {@code WS-CA-SCREEN-NUM}, which is {@code PIC 9(1)} -
     * {@code app/cbl/COCRDLIC.cbl:237}.
     *
     * <p>{@code ADD +1} at {@code :492} and {@code SUBTRACT 1} at {@code :508} carry no
     * {@code ON SIZE ERROR}, so COBOL stores the result into a single unsigned digit: high-order digits
     * are truncated and the sign is dropped. Page 9 paged forward therefore becomes page 0, and page 0
     * paged back becomes page 1. Neither is tidied into a clamp or an exception, because both are what
     * the receiving item does.
     *
     * @param current the value the cursor holds
     * @param delta   {@code +1} to page forward, {@code -1} to page back
     * @return the value a {@code PIC 9(1)} item would then hold, {@code 0..9}
     */
    static int addToScreenNum(int current, int delta) {
        return Math.abs(current + delta) % 10;
    }

    // =================================================================================================
    // THE THREE TRANSFER SITES - EXEC CICS XCTL, app/cbl/COCRDLIC.cbl:402, :538 and :566 (gate G40).
    //
    // XCTL does not return, so each of these is the last thing the program does. In the stateless
    // projection the response names where the client goes next and the client issues the follow-up call:
    // there is no server-side forward, no redirect chain and no session affinity (rule R6).
    //
    // None of the three performs 1000-SEND-MAP, so the map stays in the LOW-VALUES state that
    // new CardListResponse() produces. That is exactly what the COBOL leaves in CCRDLIAO, and no field is
    // painted here to make the response look more complete than the program made it.
    // =================================================================================================

    /**
     * {@code :384-406} - PF3 was pressed, so go back to the main menu.
     *
     * <p><strong>Two source facts that a summary of this program gets wrong.</strong> First,
     * {@code :395} moves {@code LIT-THISMAP} - this screen's own {@code CCRDLIA} - into
     * {@code CCARD-NEXT-MAP}, <em>not</em> {@code LIT-MENUMAP}. {@link #LIT_MENUMAP} {@code 'COMEN1A'}
     * is declared at {@code :193-194} and never moved anywhere. Second, {@code CCARD-NEXT-PROG} is never
     * set on this path either: the transfer names {@code LIT-MENUPGM} directly and the program the client
     * must call next is therefore taken from the {@code XCTL} operand, which is also what {@code :392}
     * puts into {@code CDEMO-TO-PROGRAM}. Both are reproduced as the source has them, and the divergence
     * from the plan's summary is recorded rather than silently reconciled (practice B4). By the same
     * reading {@link #LIT_MENUTRANID} {@code 'CM00'} is declared at {@code :189-190} and never moved into
     * {@code CDEMO-TO-TRANID}.
     *
     * @param ws       the work area
     * @param response the response under construction
     * @return the response naming the menu; never {@code null}
     */
    CardListResponse transferToMenu(WorkArea ws, CardListResponse response) {
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THISTRANID)     // :386
                .withFromProgram(LIT_THISPGM)       // :387
                .withUserTypeUser()                 // :388  SET CDEMO-USRTYP-USER TO TRUE - see B6
                .withPgmEnter()                     // :389  and again at :400
                .withLastMapset(LIT_THISMAPSET)     // :390
                .withLastMap(LIT_THISMAP)           // :391
                .withToProgram(LIT_MENUPGM);        // :392

        ws.ccWorkArea.setCcardNextMapset(LIT_MENUMAPSET);   // :394
        ws.ccWorkArea.setCcardNextMap(LIT_THISMAP);         // :395 - LIT-THISMAP, not LIT-MENUMAP

        // :396  SET WS-EXIT-MESSAGE TO TRUE. The text is set and then discarded, because the transfer
        // skips 1000-SEND-MAP: nothing ever moves it into ERRMSGO. It is set anyway, so a caller
        // inspecting the work area sees what the program saw.
        ws.setWsErrorMsg(codec.movePicX(WS_EXIT_MESSAGE, WS_ERROR_MSG_LENGTH));

        // :402  EXEC CICS XCTL PROGRAM(LIT-MENUPGM) COMMAREA(CARDDEMO-COMMAREA)
        response.setNextTarget(LIT_MENUPGM, LIT_MENUMAPSET, ws.ccWorkArea.getCcardNextMap());
        return transferred(ws, response);
    }

    /**
     * {@code :517-541} and {@code :545-569} - a row was selected, so go to the card detail view or to
     * the card update program.
     *
     * <p>The two arms are identical apart from the triple they name, so they share this method; the
     * triple is passed in rather than chosen here, which keeps the two source sites distinguishable at
     * the call site. Both arms also hand the selected row's account id and card number over in the
     * communication area, and both issue {@code SET CDEMO-USRTYP-USER TO TRUE} unconditionally
     * ({@code :522}, {@code :550}) - see practice B6 in the class documentation.
     *
     * <p>The two handed-over values are read from {@code WS-SCREEN-DATA} at the 1-based subscript
     * {@code I-SELECTED}, through {@link ScreenRowTable#row(int)} so the 1-based to 0-based conversion
     * happens in the one place that owns it.
     *
     * @param ws       the work area
     * @param response the response under construction
     * @param program  {@code LIT-CARDDTLPGM} or {@code LIT-CARDUPDPGM}
     * @param mapset   {@code LIT-CARDDTLMAPSET} or {@code LIT-CARDUPDMAPSET}
     * @param map      {@code LIT-CARDDTLMAP} or {@code LIT-CARDUPDMAP}
     * @return the response naming the target; never {@code null}
     */
    CardListResponse transferToCard(WorkArea ws, CardListResponse response,
            String program, String mapset, String map) {

        ws.commarea = ws.commarea
                .withFromTranid(LIT_THISTRANID)     // :520 / :548
                .withFromProgram(LIT_THISPGM)       // :521 / :549
                .withUserTypeUser()                 // :522 / :550
                .withPgmEnter()                     // :523 / :551
                .withLastMapset(LIT_THISMAPSET)     // :524 / :552
                .withLastMap(LIT_THISMAP);          // :525 / :553

        ws.ccWorkArea.setCcardNextProg(program);    // :526 / :554
        ws.ccWorkArea.setCcardNextMapset(mapset);   // :528 / :556
        ws.ccWorkArea.setCcardNextMap(map);         // :529 / :557

        // WS-SCREEN-ROWS(I-SELECTED). Both DTOs declare a nested ScreenRow, so each is named in full at
        // every use: the request's is the 28-byte WS-SCREEN-DATA element, the response's is the map row.
        CardListRequest.ScreenRow selected = ws.screenRows.row(ws.iSelected);
        // :531-532  MOVE WS-ROW-ACCTNO(I-SELECTED) TO CDEMO-ACCT-ID   - X(11) into 9(11)
        ws.commarea = ws.commarea.withAcctId(digitsOf(selected.acctNo(), ACCT_ID_DIGITS));
        // :533-534  MOVE WS-ROW-CARD-NUM(I-SELECTED) TO CDEMO-CARD-NUM - X(16) into 9(16)
        ws.commarea = ws.commarea.withCardNum(digitsOf(selected.cardNum(), CARD_NUM_DIGITS));

        // :538 / :566  EXEC CICS XCTL PROGRAM(CCARD-NEXT-PROG) COMMAREA(CARDDEMO-COMMAREA)
        response.setNextTarget(ws.ccWorkArea.getCcardNextProg(),
                ws.ccWorkArea.getCcardNextMapset(),
                ws.ccWorkArea.getCcardNextMap());
        return transferred(ws, response);
    }

    /**
     * Attaches the two areas an {@code XCTL} makes available to the program it transfers to.
     *
     * <p>{@code COMMAREA(CARDDEMO-COMMAREA)} is what actually crosses the transfer, so it is attached.
     * {@code CC-WORK-AREA} does not cross it - it is {@code WORKING-STORAGE} - but it is attached too,
     * because it is where the navigation triple was just written and a caller has no other way to read
     * the {@code CCARD-NEXT-*} values the transfer was built from. The pagination cursor is attached for
     * the same reason.
     *
     * @param ws       the work area
     * @param response the response under construction
     * @return {@code response}, for use as a return value
     */
    static CardListResponse transferred(WorkArea ws, CardListResponse response) {
        response.setNavigationContext(ws.commarea);
        response.setCardScreenState(ws.ccWorkArea);
        response.setPageCursor(ws.cursor);
        return response;
    }

    // =================================================================================================
    // COMMON-RETURN - app/cbl/COCRDLIC.cbl:604-620, the shared terminal the six non-transferring arms
    // reach by GO TO.
    // =================================================================================================

    /**
     * Restates this program's identity, appends the program communication area behind the shared one and
     * returns.
     *
     * <p>{@code :609-612} moves {@code CARDDEMO-COMMAREA} into the first 160 bytes of
     * {@code WS-COMMAREA} and {@code WS-THIS-PROGCOMMAREA} into the 254 bytes that follow it, and
     * {@code :615-619} returns that 2,000-byte area with {@code TRANSID(LIT-THISTRANID)} so the next
     * keystroke re-enters this program with it. In the stateless projection the response carries the two
     * areas as payload members and the client sends them back - the 58-byte cursor as
     * {@link CardListResponse#getPageCursor()} and the 196-byte row table as the map's own
     * {@code ACCTNOnO}, {@code CRDNUMnO} and {@code CRDSTSnO} members, which
     * {@link #restoreScreenRowTable(CardListRequest)} reads back from their input twins on the next call.
     *
     * @param ws       the work area
     * @param response the response under construction
     * @return {@code response}, for use as a return value
     */
    CardListResponse commonReturn(WorkArea ws, CardListResponse response) {
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THISTRANID)     // :605
                .withFromProgram(LIT_THISPGM)       // :606
                .withLastMapset(LIT_THISMAPSET)     // :607
                .withLastMap(LIT_THISMAP);          // :608
        response.setNavigationContext(ws.commarea);
        response.setCardScreenState(ws.ccWorkArea);
        response.setPageCursor(ws.cursor);
        return response;
    }

    // =================================================================================================
    // 2000-RECEIVE-MAP - app/cbl/COCRDLIC.cbl:951-1121.
    // =================================================================================================

    /**
     * {@code :951-957} - receive the screen, then edit what was typed.
     *
     * @param request the inbound payload, standing for {@code CCRDLIAI}
     * @param ws      the work area
     */
    void receiveMap(CardListRequest request, WorkArea ws) {
        receiveScreen(request, ws);   // :952-953
        editInputs(ws);               // :955-956
    }

    /**
     * {@code 2100-RECEIVE-SCREEN}, {@code :962-979}.
     *
     * <p>{@code EXEC CICS RECEIVE MAP INTO(CCRDLIAI)} is the arrival of the payload itself, so the
     * translation is the nine moves that follow it: the two filter fields and the seven row action
     * codes. Note what is <strong>not</strong> moved - {@code ACCTNOnI}, {@code CRDNUMnI} and
     * {@code CRDSTSnI} are received but never copied into {@code WS-SCREEN-DATA}, because that table
     * arrives in the communication area instead. The same nine moves, and only those nine, happen here.
     *
     * @param request the inbound payload
     * @param ws      the work area
     */
    void receiveScreen(CardListRequest request, WorkArea ws) {
        // :969  MOVE ACCTSIDI OF CCRDLIAI TO CC-ACCT-ID - X(11) into X(11)
        ws.ccWorkArea.setCcAcctId(codec.movePicX(request.getAcctsid(),
                CardScreenState.CC_ACCT_ID_LENGTH));
        // :970  MOVE CARDSIDI OF CCRDLIAI TO CC-CARD-NUM - X(16) into X(16)
        ws.ccWorkArea.setCcCardNum(codec.movePicX(request.getCardsid(),
                CardScreenState.CC_CARD_NUM_LENGTH));

        // :972-978  MOVE CRDSELnI OF CCRDLIAI TO WS-EDIT-SELECT(n), one per row, rows 1 to 7.
        // The subscript is the COBOL one throughout; SelectionFlags.withSelection owns the conversion.
        SelectionFlags flags = ws.selectionFlags;
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            flags = flags.withSelection(row, selectionCharOf(request, row));
        }
        ws.selectionFlags = flags;
    }

    /**
     * The one-character action code the payload carries for a row, as
     * {@code MOVE CRDSELnI OF CCRDLIAI TO WS-EDIT-SELECT(n)} would move it.
     *
     * <p>A {@code PIC X(1)} receiver takes the first character of the sender and, when the sender is
     * shorter than one character, a space - which is the {@code PIC X} pad rule and is also what
     * {@code 88 SELECT-BLANK} treats as "nothing typed".
     *
     * @param request the inbound payload
     * @param row     the COBOL subscript, {@code 1..7}
     * @return the action character
     */
    char selectionCharOf(CardListRequest request, int row) {
        String typed = codec.movePicX(request.row(row).crdSel(), CardListResponse.CRDSEL_LENGTH);
        return typed.charAt(0);
    }

    /**
     * {@code 2200-EDIT-INPUTS}, {@code :985-997}.
     *
     * @param ws the work area
     */
    void editInputs(WorkArea ws) {
        ws.setInputOk();                        // :986
        ws.setFlgProtectSelectRowsNo();         // :987
        editAccount(ws);                        // :989-990
        editCard(ws);                           // :992-993
        editArray(ws);                          // :995-996
    }

    /**
     * {@code 2210-EDIT-ACCOUNT}, {@code :1003-1030} - the account filter's three outcomes.
     *
     * <p>Both {@code GO TO 2210-EDIT-ACCOUNT-EXIT} sites [{@code :1012}, {@code :1025}] are early
     * returns from the paragraph and become {@code return} (rule R7). The message this paragraph moves
     * is moved <strong>unconditionally</strong>, unlike the card filter's, which is guarded - see
     * {@link #editCard(WorkArea)}.
     *
     * @param ws the work area
     */
    void editAccount(WorkArea ws) {
        ws.setFlgAcctfilterBlank();     // :1004

        // :1007-1013  Not supplied: LOW-VALUES, or spaces, or numerically zero.
        if (ws.ccWorkArea.isCcAcctIdLowValues()
                || ws.ccWorkArea.isCcAcctIdSpaces()
                || ws.ccWorkArea.isCcAcctIdNZeros()) {
            ws.setFlgAcctfilterBlank();                     // :1010, stated twice by the source
            ws.commarea = ws.commarea.withAcctId(0L);       // :1011  MOVE ZEROES TO CDEMO-ACCT-ID
            return;                                         // :1012
        }

        // :1017-1029  Not numeric, or not eleven digits.
        if (!ws.ccWorkArea.isCcAcctIdNumeric()) {
            ws.setInputError();                     // :1018
            ws.setFlgAcctfilterNotOk();             // :1019
            ws.setFlgProtectSelectRowsYes();        // :1020
            ws.setWsErrorMsg(codec.movePicX(MSG_ACCOUNT_FILTER, WS_ERROR_MSG_LENGTH));  // :1021-1023
            ws.commarea = ws.commarea.withAcctId(0L);   // :1024  MOVE ZERO TO CDEMO-ACCT-ID
            return;                                     // :1025
        }
        // :1027  MOVE CC-ACCT-ID TO CDEMO-ACCT-ID - X(11) into 9(11), and the digits are known good.
        ws.commarea = ws.commarea.withAcctId(ws.ccWorkArea.getCcAcctIdN());
        ws.setFlgAcctfilterIsValid();               // :1028
    }

    /**
     * {@code 2220-EDIT-CARD}, {@code :1036-1066} - the card filter's three outcomes.
     *
     * <p>Almost the mirror of {@link #editAccount(WorkArea)}, with one deliberate difference that is
     * preserved: this paragraph moves its message only {@code IF WS-ERROR-MSG-OFF} [{@code :1056-1060}],
     * so when both filters fail edit the operator sees the <em>account</em> message and never the card
     * one. The account paragraph has no such guard.
     *
     * @param ws the work area
     */
    void editCard(WorkArea ws) {
        ws.setFlgCardfilterBlank();     // :1039

        // :1042-1048  Not supplied.
        if (ws.ccWorkArea.isCcCardNumLowValues()
                || ws.ccWorkArea.isCcCardNumSpaces()
                || ws.ccWorkArea.isCcCardNumNZeros()) {
            ws.setFlgCardfilterBlank();                     // :1045
            ws.commarea = ws.commarea.withCardNum(0L);      // :1046  MOVE ZEROES TO CDEMO-CARD-NUM
            return;                                         // :1047
        }

        // :1052-1066  Not numeric, or not sixteen digits.
        if (!ws.ccWorkArea.isCcCardNumNumeric()) {
            ws.setInputError();                     // :1053
            ws.setFlgCardfilterNotOk();             // :1054
            ws.setFlgProtectSelectRowsYes();        // :1055
            if (ws.isWsErrorMsgOff()) {             // :1056  the guard the account paragraph lacks
                ws.setWsErrorMsg(codec.movePicX(MSG_CARD_FILTER, WS_ERROR_MSG_LENGTH));  // :1057-1059
            }
            ws.commarea = ws.commarea.withCardNum(0L);  // :1061  MOVE ZERO TO CDEMO-CARD-NUM
            return;                                     // :1062
        }
        // :1064  MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM - 9(16) into 9(16), a numeric move.
        ws.commarea = ws.commarea.withCardNum(ws.ccWorkArea.getCcCardNumN());
        ws.setFlgCardfilterIsValid();               // :1065
    }

    /**
     * {@code 2250-EDIT-ARRAY}, {@code :1073-1117} - the seven action codes, and which row was chosen.
     *
     * <p>{@code INSPECT ... TALLYING I FOR ALL 'S' ALL 'U'} [{@code :1079-1082}] <em>adds to</em>
     * {@code I}, which the declaration at {@code :90-91} starts at zero, so the tally is the number of
     * marked rows. More than one is an error, and the whole seven-byte highlight table is then derived by
     * {@code INSPECT ... REPLACING} [{@code :1088-1093}] rather than row by row.
     *
     * <p>The scan that follows is a three-arm {@code EVALUATE TRUE} inside
     * {@code PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7}, and all three arms are reproduced in order:
     * a marked row records itself in {@code I-SELECTED}, a blank row is skipped, and anything else is an
     * invalid action code. Because the loop keeps assigning, {@code I-SELECTED} ends up holding the
     * <strong>last</strong> marked row, not the first - which only matters when more than one is marked,
     * and that is already an error.
     *
     * @param ws the work area
     */
    void editArray(WorkArea ws) {
        if (ws.isInputError()) {    // :1075-1077  GO TO 2250-EDIT-ARRAY-EXIT
            return;
        }

        // :1079-1082  INSPECT WS-EDIT-SELECT-FLAGS TALLYING I FOR ALL 'S' ALL 'U'
        ws.i = ws.i + ws.selectionFlags.selectedRowCount();

        // :1084-1095  More than one row marked.
        if (ws.i > 1) {
            ws.setInputError();                                                         // :1085
            ws.setWsErrorMsg(codec.movePicX(WS_MORE_THAN_1_ACTION, WS_ERROR_MSG_LENGTH)); // :1086
            // :1088-1093  MOVE WS-EDIT-SELECT-FLAGS TO WS-EDIT-SELECT-ERROR-FLAGS, then
            //             INSPECT REPLACING ALL 'S' BY '1' ALL 'U' BY '1' CHARACTERS BY '0'.
            ws.selectionErrorFlags = SelectionErrorFlags.fromSelectionFlags(ws.selectionFlags);
        }

        ws.iSelected = NO_ROW_SELECTED;     // :1097  MOVE ZERO TO I-SELECTED

        // :1099-1115  PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7
        for (ws.i = FIRST_ROW; ws.i <= LAST_ROW; ws.i++) {
            if (ws.selectionFlags.isSelectOk(ws.i)) {                   // :1101  88 SELECT-OK 'S','U'
                ws.iSelected = ws.i;                                    // :1102
                if (isWsMoreThan1Action(ws)) {                          // :1103
                    ws.selectionErrorFlags = ws.selectionErrorFlags.withRowInError(ws.i);  // :1104
                }
            } else if (ws.selectionFlags.isSelectBlank(ws.i)) {         // :1106  88 SELECT-BLANK
                // :1107  CONTINUE - a row the operator left alone is not an error.
                continue;
            } else {                                                    // :1108  WHEN OTHER
                ws.setInputError();                                     // :1109
                ws.selectionErrorFlags = ws.selectionErrorFlags.withRowInError(ws.i);  // :1110
                if (ws.isWsErrorMsgOff()) {                             // :1111
                    ws.setWsErrorMsg(codec.movePicX(WS_INVALID_ACTION_CODE,
                            WS_ERROR_MSG_LENGTH));                      // :1112
                }
            }
        }
        // The loop leaves I at 8, exactly as PERFORM VARYING ... UNTIL I > 7 does.
    }

    // =================================================================================================
    // 9000-READ-FORWARD and 9100-READ-BACKWARDS - app/cbl/COCRDLIC.cbl:1123-1380.
    //
    // The two directions are NOT mirror images; see the class documentation. Both browse the base cluster
    // through CardRepository, and every outcome is classified by FileStatus (gate G47).
    // =================================================================================================

    /**
     * {@code 9000-READ-FORWARD}, {@code :1123-1263} - fill the page from the current key, counting up.
     *
     * <p>{@code STARTBR ... GTEQ} [{@code :1129-1136}] positions at or after the key, then the
     * {@code PERFORM UNTIL READ-LOOP-EXIT} loop reads forward. A record that survives
     * {@code 9500-FILTER-RECORDS} is placed at the next row and, when it is the first, becomes the
     * page's first key; on the seventh the loop stops and issues <strong>one extra
     * {@code READNEXT}</strong> [{@code :1197-1205}] whose only purpose is to learn whether a next page
     * exists and, if so, what its predecessor key is.
     *
     * <p>{@code STARTBR}'s own response is captured into {@code WS-RESP-CD} at {@code :1134} and then
     * never tested - {@code :1140} immediately moves zeroes into the row counter - so no status is
     * consulted here either. The first read is where the guard chain begins.
     *
     * @param ws the work area
     */
    void readForward(WorkArea ws) {
        // :1124  MOVE LOW-VALUES TO WS-ALL-ROWS
        ws.screenRows = ScreenRowTable.lowValues();

        try (CardBrowse browse = cardRepository.startBrowse(ws.wsCardRidCardnum,
                BrowseDirection.FORWARD)) {                     // :1129-1136
            ws.wsScrnCounter = 0;                               // :1140
            ws.cursor = ws.cursor.withNextPageExists();         // :1141
            ws.setMoreRecordsToRead();                          // :1142

            while (!ws.isReadLoopExit()) {                      // :1144
                CardReadResult read = browse.readNext();        // :1146-1154
                recordResponse(ws, read);

                // :1156-1255  EVALUATE WS-RESP-CD, in source order.
                if (read.isNormal() || read.isDuplicateKey()) {  // :1157-1158 NORMAL, DUPREC
                    CardRecord card = read.requireRecord();
                    filterRecord(ws, card);                     // :1159-1160
                    if (ws.isDonotExcludeThisRecord()) {        // :1162
                        placeRow(ws, card);
                    }
                    // :1191-1231  Max screen size reached?
                    if (ws.wsScrnCounter == WS_MAX_SCREEN_LINES) {
                        ws.setReadLoopExit();                   // :1192
                        // :1194-1195  the seventh record is the page's last key until proven otherwise
                        ws.cursor = ws.cursor.withLastCardKey(keyOf(card));
                        lookAhead(ws, browse);                  // :1197-1231
                    }
                } else if (read.isEndOfFile()) {                // :1233  DFHRESP(ENDFILE)
                    ws.setReadLoopExit();                       // :1234
                    ws.cursor = ws.cursor.withNextPageNotExists();   // :1235
                    // :1236-1237  MOVE CARD-ACCT-ID / CARD-NUM TO WS-CA-LAST-CARD-*.
                    //
                    // A read that ended the file returned no record, so CARD-RECORD still holds the last
                    // record that WAS returned - these two moves therefore store the key of the final
                    // record on the page. When the browse returned nothing at all, CARD-RECORD is the
                    // untouched WORKING-STORAGE area from COPY CVACT02Y, whose PIC X items are binary
                    // zero and whose PIC 9 items are zero: that is CardKey.lowValues().
                    ws.cursor = ws.cursor.withLastCardKey(ws.lastCardRead == null
                            ? CardKey.lowValues()
                            : keyOf(ws.lastCardRead));
                    if (ws.isWsErrorMsgOff()) {                 // :1238
                        ws.setWsErrorMsg(codec.movePicX(MSG_NO_MORE_RECORDS, WS_ERROR_MSG_LENGTH));
                    }
                    // :1241-1245  The empty-result branch: page one and not a single row placed.
                    if (ws.cursor.screenNum() == CardListRequest.FIRST_PAGE_SCREEN_NUM
                            && ws.wsScrnCounter == 0) {
                        ws.setWsErrorMsg(codec.movePicX(WS_NO_RECORDS_FOUND, WS_ERROR_MSG_LENGTH));
                    }
                } else {                                        // :1246  WHEN OTHER
                    ws.setReadLoopExit();                       // :1249
                    ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
                }
            }
        }
        // :1258-1259  EXEC CICS ENDBR FILE(LIT-CARD-FILE) - the try-with-resources close.
    }

    /**
     * {@code :1197-1231} - the extra {@code READNEXT} the forward browse issues once the page is full.
     *
     * <p>Its record is never displayed. All it decides is whether {@code CA-NEXT-PAGE-EXISTS} or
     * {@code CA-NEXT-PAGE-NOT-EXISTS} holds, and on the first of those it also replaces the page's last
     * key with the key of the record just read - which is what makes the next {@code PF8} start in the
     * right place. Note that this inner {@code EVALUATE} has no {@code AND WS-SCRN-COUNTER = 0} arm: the
     * empty-result branch belongs to the outer {@code ENDFILE} arm at {@code :1241-1245} only.
     *
     * @param ws     the work area
     * @param browse the open forward browse
     */
    void lookAhead(WorkArea ws, CardBrowse browse) {
        CardReadResult ahead = browse.readNext();
        recordResponse(ws, ahead);
        if (ahead.isNormal() || ahead.isDuplicateKey()) {           // :1208-1209
            ws.cursor = ws.cursor.withNextPageExists();             // :1210-1211
            ws.cursor = ws.cursor.withLastCardKey(keyOf(ahead.requireRecord()));  // :1212-1214
        } else if (ahead.isEndOfFile()) {                           // :1215
            ws.cursor = ws.cursor.withNextPageNotExists();          // :1216
            if (ws.isWsErrorMsgOff()) {                             // :1218
                ws.setWsErrorMsg(codec.movePicX(MSG_NO_MORE_RECORDS, WS_ERROR_MSG_LENGTH));
            }
        } else {                                                    // :1222  WHEN OTHER
            ws.setReadLoopExit();                                   // :1225
            ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
        }
    }

    /**
     * {@code 9100-READ-BACKWARDS}, {@code :1264-1380} - fill the page walking backwards, counting down.
     *
     * <p>The asymmetry with the forward direction is the whole point of this paragraph and every part of
     * it is reproduced:
     * <ul>
     *   <li>{@code :1268} first copies the page's first key into its last key, so the browse starts from
     *       the top of the page being paged away from;</li>
     *   <li>{@code :1284-1286} primes the row counter to {@code WS-MAX-SCREEN-LINES + 1}, that is
     *       <strong>8</strong>;</li>
     *   <li>{@code :1294-1318} issues one {@code READPREV} whose record is <strong>discarded</strong> -
     *       the arm only decrements the counter to 7 - because that record is the one already at the top
     *       of the current page;</li>
     *   <li>{@code :1320-1371} then fills rows 7 down to 1, placing each record <em>before</em>
     *       decrementing, and stops when the counter reaches {@code 0} [{@code :1347}], at which point
     *       the record just placed becomes the new page's first key.</li>
     * </ul>
     * There is no look-ahead read and no {@code ENDFILE} arm: running off the front of the file lands on
     * {@code WHEN OTHER} at {@code :1361}, exactly as the source has it.
     *
     * @param ws the work area
     */
    void readBackwards(WorkArea ws) {
        // :1266  MOVE LOW-VALUES TO WS-ALL-ROWS
        ws.screenRows = ScreenRowTable.lowValues();
        // :1268  MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY
        ws.cursor = ws.cursor.withLastCardKeyFromFirst();

        try (CardBrowse browse = cardRepository.startBrowse(ws.wsCardRidCardnum,
                BrowseDirection.BACKWARD)) {                    // :1273-1280
            // :1284-1286  COMPUTE WS-SCRN-COUNTER = WS-MAX-SCREEN-LINES + 1
            ws.wsScrnCounter = WS_MAX_SCREEN_LINES + 1;
            ws.cursor = ws.cursor.withNextPageExists();         // :1287
            ws.setMoreRecordsToRead();                          // :1288

            // :1294-1318  The discarded first read.
            CardReadResult first = browse.readPrev();
            recordResponse(ws, first);
            if (first.isNormal() || first.isDuplicateKey()) {   // :1305-1306
                ws.wsScrnCounter--;                             // :1307  SUBTRACT 1, and nothing else
            } else {                                            // :1308  WHEN OTHER
                ws.setReadLoopExit();                           // :1311
                ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
                return;                                         // :1317  GO TO 9100-...-EXIT
            }

            // :1320-1371  PERFORM UNTIL READ-LOOP-EXIT
            while (!ws.isReadLoopExit()) {
                CardReadResult read = browse.readPrev();        // :1322-1330
                recordResponse(ws, read);
                if (read.isNormal() || read.isDuplicateKey()) { // :1333-1334
                    CardRecord card = read.requireRecord();
                    filterRecord(ws, card);                     // :1335-1336
                    if (ws.isDonotExcludeThisRecord()) {        // :1337
                        // :1338-1344  the row is written at the CURRENT counter, then the counter drops.
                        writeRow(ws, ws.wsScrnCounter, card);
                        ws.wsScrnCounter--;                     // :1346
                        if (ws.wsScrnCounter == 0) {            // :1347
                            ws.setReadLoopExit();               // :1348
                            // :1350-1353  the record that filled row 1 is the new page's first key
                            ws.cursor = ws.cursor.withFirstCardKey(keyOf(card));
                        }
                    }
                } else {                                        // :1361  WHEN OTHER
                    ws.setReadLoopExit();                       // :1364
                    ws.setWsErrorMsg(codec.movePicX(fileErrorMessage(ws), WS_ERROR_MSG_LENGTH));
                }
            }
        }
        // :1375-1377  EXEC CICS ENDBR FILE(LIT-CARD-FILE), which the source places in the -EXIT
        // paragraph so that the early return at :1317 reaches it too - which try-with-resources does.
    }

    /**
     * {@code 9500-FILTER-RECORDS}, {@code :1382-1407} - does this record belong on the screen?
     *
     * <p>Two guards, each an early return in the source. A filter that was not supplied excludes
     * nothing, which is why an unfiltered browse shows every card. Both comparisons are made the way
     * COBOL makes them: a numeric item compared against an alphanumeric one is rendered to its display
     * form first, so an eleven-digit account id compares against eleven characters and a sixteen-digit
     * card number against sixteen.
     *
     * @param ws   the work area
     * @param card the record just read
     */
    void filterRecord(WorkArea ws, CardRecord card) {
        ws.setDonotExcludeThisRecord();     // :1383

        if (ws.isFlgAcctfilterIsValid()) {  // :1385
            // :1386  IF CARD-ACCT-ID = CC-ACCT-ID  - 9(11) against X(11)
            String cardAcctId = codec.movePic9(card.cardAcctId(), ACCT_ID_DIGITS);
            if (!cardAcctId.equals(codec.movePicX(ws.ccWorkArea.getCcAcctId(),
                    CardScreenState.CC_ACCT_ID_LENGTH))) {
                ws.setExcludeThisRecord();  // :1389
                return;                     // :1390
            }
        }

        if (ws.isFlgCardfilterIsValid()) {  // :1396
            // :1397  IF CARD-NUM = CC-CARD-NUM-N  - X(16) against 9(16)
            String filterCardNum = codec.movePic9(ws.ccWorkArea.getCcCardNumN(), CARD_NUM_DIGITS);
            if (!codec.movePicX(card.cardNum(), CardRecord.CARD_NUM_LENGTH).equals(filterCardNum)) {
                ws.setExcludeThisRecord();  // :1400
                return;                     // :1401
            }
        }
    }

    /**
     * {@code :1163-1184} - the forward direction's row placement.
     *
     * <p>The counter is incremented <em>first</em> and the record written at the new value, which is the
     * opposite order from the backward direction. When the row that was just filled is row one, the
     * record also becomes the page's first key, and the page number is advanced from zero to one - the
     * guarded {@code ADD +1} at {@code :1177-1181} that gives a cold-started transaction its page
     * number without disturbing a page number the cursor already carries.
     *
     * @param ws   the work area
     * @param card the record to place
     */
    void placeRow(WorkArea ws, CardRecord card) {
        ws.wsScrnCounter++;                             // :1163
        writeRow(ws, ws.wsScrnCounter, card);           // :1165-1171
        if (ws.wsScrnCounter == FIRST_ROW) {            // :1173
            ws.cursor = ws.cursor.withFirstCardKey(keyOf(card));    // :1174-1176
            if (ws.cursor.screenNum() == 0) {                       // :1177
                ws.cursor = ws.cursor.withScreenNum(
                        addToScreenNum(ws.cursor.screenNum(), 1));  // :1178
            }
            // :1179-1181  ELSE CONTINUE - a page number already set is left alone.
        }
        // :1182-1184  ELSE CONTINUE
    }

    /**
     * The three moves that fill one 28-byte {@code WS-SCREEN-ROWS} element - {@code :1165-1171} forward
     * and {@code :1338-1344} backward, which write the same three values in the same order.
     *
     * <p>Only {@code CARD-NUM}, {@code CARD-ACCT-ID} and {@code CARD-ACTIVE-STATUS} are taken from the
     * 150-byte record. The CVV code and the embossed name are <strong>not</strong> projected onto this
     * screen, because the COBOL does not project them and a list screen that leaked a CVV would be a new
     * behaviour as well as a poor idea.
     *
     * @param ws   the work area
     * @param row  the COBOL subscript, {@code 1..7}
     * @param card the record to write
     */
    void writeRow(WorkArea ws, int row, CardRecord card) {
        String acctNo = codec.movePic9(card.cardAcctId(), ACCT_ID_DIGITS);
        String cardNum = codec.movePicX(card.cardNum(), CardRecord.CARD_NUM_LENGTH);
        String status = codec.movePicX(card.cardActiveStatus(), CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        ws.screenRows = ws.screenRows.withRow(row,
                new CardListRequest.ScreenRow(acctNo, cardNum, status));
    }

    /**
     * The pagination key of a record - the {@code WS-CA-*-CARD-NUM} and {@code WS-CA-*-CARD-ACCT-ID}
     * pair, {@code X(16)} then {@code 9(11)}.
     *
     * @param card the record
     * @return the key
     */
    CardKey keyOf(CardRecord card) {
        return new CardKey(codec.movePicX(card.cardNum(), CardRecord.CARD_NUM_LENGTH),
                card.cardAcctId());
    }

    /**
     * Captures {@code RESP} and {@code RESP2} into {@code WS-RESP-CD} and {@code WS-REAS-CD}, which every
     * {@code RESP(WS-RESP-CD) RESP2(WS-REAS-CD)} clause in this program does.
     *
     * <p>They are captured on <em>every</em> read, not only on a failing one, because the source captures
     * them unconditionally and because the diagnostic renders whichever pair was last captured.
     *
     * @param ws   the work area
     * @param read the outcome just returned
     */
    static void recordResponse(WorkArea ws, CardReadResult read) {
        ws.wsRespCd = read.resp();
        ws.wsReasCd = read.resp2();
        ws.lastOutcome = read.outcome();
        read.record().ifPresent(card -> ws.lastCardRead = card);
    }

    // =================================================================================================
    // 1000-SEND-MAP and its six subordinates - app/cbl/COCRDLIC.cbl:624-949.
    //
    // 1500-SEND-SCREEN is EXEC CICS SEND MAP ... FROM(CCRDLIAO), and in this projection the response IS
    // CCRDLIAO: what the six paragraphs above it write into the map is what the client receives.
    // =================================================================================================

    /**
     * {@code 1000-SEND-MAP}, {@code :624-637} - the six paragraphs, in their source order.
     *
     * <p>Four of the six call sites perform {@code THRU 1000-SEND-MAP} and two perform
     * {@code THRU 1000-SEND-MAP-EXIT}; since the exit paragraph holds only {@code EXIT}, both ranges run
     * exactly this sequence. The asymmetry is recorded in the class documentation and at each call site
     * rather than normalised (practice B5).
     *
     * @param request  the inbound payload, which also carries the input-group attribute metadata
     * @param ws       the work area
     * @param response the response under construction
     */
    void sendMap(CardListRequest request, WorkArea ws, CardListResponse response) {
        screenInit(ws, response);                       // :625-626
        screenArrayInit(ws, response);                  // :627-628
        setupArrayAttribs(request, ws, response);       // :629-630
        setupScreenAttrs(request, ws, response);        // :631-632
        setupMessage(ws, response);                     // :633-634
        sendScreen(ws, response);                       // :635-636
    }

    /**
     * {@code 1100-SCREEN-INIT}, {@code :642-672} - clear the map and fill the header band.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} appears <strong>twice</strong>, at
     * {@code :645} and again at {@code :652}, and only the second reading is used: the date and time
     * items are all derived after it. Both readings are performed here, for the same reason - a
     * translation that read the clock once would be reading a different statement. With an injected fixed
     * clock the two agree exactly, which is what makes the rendered header assertable.
     *
     * <p>{@code :669} {@code SET WS-NO-INFO-MESSAGE TO TRUE} selects the <em>first</em> of that condition
     * name's two values, {@code SPACES}, and {@code :671} then darkens the information line with
     * {@code DFHBMDAR} so an empty message shows as nothing rather than as a blank highlighted band.
     *
     * @param ws       the work area
     * @param response the response under construction
     */
    void screenInit(WorkArea ws, CardListResponse response) {
        DateHeader discardedFirstReading = DateHeader.from(codec, clock);    // :645
        ws.wsCurdateData = discardedFirstReading.wsCurdateData();
        DateHeader current = DateHeader.from(codec, clock);                  // :652
        ws.dateHeader = current;

        // applyPageNumberFromCursor reads the response's own cursor [:667], so it is attached first.
        response.setPageCursor(ws.cursor);
        ws.setWsNoInfoMessage();                                            // :669
        // :643 MOVE LOW-VALUES TO CCRDLIAO, :647-648 titles, :649 tranid, :650 program, :658 date,
        // :664 time, :667 page number, :670 info message, :671 DFHBMDAR - in that order.
        response.applyScreenInit(current, ws.wsInfoMsg);
    }

    /**
     * {@code 1200-SCREEN-ARRAY-INIT}, {@code :678-743} - project the seven table rows onto the map.
     *
     * <p>The source writes the seven rows out longhand with an {@code IF ... EQUAL LOW-VALUES CONTINUE
     * ELSE} per row, and its own comment at {@code :679} asks for exactly the loop written here. A row
     * still holding {@code LOW-VALUES} is <strong>skipped</strong>, which leaves the map row at the
     * {@code LOW-VALUES} that {@code :643} put there - that is how an unfilled row shows as nothing.
     *
     * @param ws       the work area
     * @param response the response under construction
     */
    void screenArrayInit(WorkArea ws, CardListResponse response) {
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            CardListRequest.ScreenRow source = ws.screenRows.row(row);
            if (source.isCleared()) {
                continue;   // IF WS-EACH-CARD(n) EQUAL LOW-VALUES -> CONTINUE
            }
            // MOVE WS-EDIT-SELECT(n) TO CRDSELnO
            response.setEditSelect(row, String.valueOf(ws.selectionFlags.at(row)));
            // MOVE WS-ROW-ACCTNO(n) / WS-ROW-CARD-NUM(n) / WS-ROW-CARD-STATUS(n) TO ACCTNOnO /
            // CRDNUMnO / CRDSTSnO - three moves, one per item, in the source's order.
            response.setScreenRow(row, CardListResponse.ScreenRow.of(source.acctNo(),
                    source.cardNum(), source.cardStatus()));
        }
    }

    /**
     * {@code 1250-SETUP-ARRAY-ATTRIBS}, {@code :748-832} - the selection column's attributes.
     *
     * <p>The output-group half - {@code DFHRED} onto the colour item and, for row 1 only, {@code '*'}
     * onto the field itself - belongs to {@link CardListResponse#applyRowSelectHighlight(int, boolean)},
     * which already reproduces both the missing {@code CDEMO-PGM-REENTER} guard and the row-1 asymmetry.
     * What is left here is the input-group half and the row-1 attribute asymmetry:
     * <ul>
     *   <li>a cleared or protected row gets {@code DFHBMPRF} on <strong>row 1</strong> [{@code :753}] and
     *       {@code DFHBMPRO} on rows 2 to 7 [{@code :766}, {@code :777}, {@code :789}, {@code :801},
     *       {@code :812}, {@code :824}] - two different protected attributes for the same situation, in
     *       the source;</li>
     *   <li>any other row gets {@code DFHBMFSE} [{@code :761}, {@code :772}, ...], which is the
     *       unprotected, modified-data-tag-set attribute that lets the operator type in it;</li>
     *   <li>a row in error on rows 2 to 7 additionally aims the cursor at itself with
     *       {@code MOVE -1 TO CRDSELnL} [{@code :770}, {@code :782}, {@code :794}, {@code :805},
     *       {@code :817}, {@code :828}]. Row 1 does not: it moves {@code '*'} instead.</li>
     * </ul>
     *
     * <p>{@code :790} carries a stray {@code I} token in the middle of the row-4 branch. It has no
     * operand, no effect on row 4's logic and no counterpart in any other row, so there is nothing to
     * translate; it is noted so a reader comparing the two files is not left wondering.
     *
     * @param request  the inbound payload, which carries the input-group metadata
     * @param ws       the work area
     * @param response the response under construction
     */
    void setupArrayAttribs(CardListRequest request, WorkArea ws, CardListResponse response) {
        boolean protect = ws.isFlgProtectSelectRowsYes();
        // applyRowSelectHighlight reads its error signal and its blank test from the response, so both
        // work-area tables are attached first - the same storage the COBOL reads them from.
        response.setEditSelectErrorFlags(ws.selectionErrorFlags.flags());

        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            String label = rowSelectionLabel(row);
            if (response.screenRow(row).isLowValues() || protect) {
                putFieldAttribute(request, label,
                        row == FIRST_ROW ? BmsAttributes.DFHBMPRF : BmsAttributes.DFHBMPRO);
                continue;
            }
            response.applyRowSelectHighlight(row, protect);
            if (row != FIRST_ROW && ws.selectionErrorFlags.isRowSelectError(row)) {
                placeCursor(ws, label);
            }
            putFieldAttribute(request, label, BmsAttributes.DFHBMFSE);
        }
    }

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS}, {@code :837-889} - repaint the two filter fields and aim the
     * cursor.
     *
     * <p>Two ordered {@code EVALUATE}s, guarded by a test that skips them entirely on a cold start or on
     * arrival from the menu [{@code :839-842}] - which is what leaves the filter fields empty on a first
     * visit. Each {@code EVALUATE} prefers what the operator typed, falls back to what the communication
     * area carries, and shows nothing when that is zero.
     *
     * <p>The three cursor placements and the two colour changes that follow [{@code :872-886}] are
     * <strong>not</strong> guarded by {@code CDEMO-PGM-REENTER} and do <strong>not</strong> apply the
     * {@code '*'} marker, so they are this program's own inline variant and not {@code CSSETATY}: the
     * validation outcome is still expressed as a {@link FieldValidationState}, which is the vocabulary
     * that shared decision is written in, but the decision applied is the source's.
     *
     * @param request  the inbound payload, which carries the input-group metadata
     * @param ws       the work area
     * @param response the response under construction
     */
    void setupScreenAttrs(CardListRequest request, WorkArea ws, CardListResponse response) {
        String acctLabel = fieldLabel(CardListResponse.ACCTSIDO_ITEM);
        String cardLabel = fieldLabel(CardListResponse.CARDSIDO_ITEM);

        // :839-842  IF EIBCALEN = 0 OR (CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM = LIT-MENUPGM) CONTINUE
        boolean fromMenu = ws.commarea.isEnter()
                && codec.movePicX(ws.commarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH)
                        .equals(LIT_MENUPGM);
        if (!ws.eibcalenZero && !fromMenu) {
            // :844-854  the account filter
            if (ws.isFlgAcctfilterIsValid() || ws.isFlgAcctfilterNotOk()) {     // :845-846
                response.setAcctsido(ws.ccWorkArea.getCcAcctId());              // :847
                putFieldAttribute(request, acctLabel, BmsAttributes.DFHBMFSE);  // :848
            } else if (ws.commarea.acctId() == 0L) {                            // :849
                response.setAcctsido(CardScreenState.lowValues(
                        CardListResponse.ACCTSIDO_LENGTH));                     // :850
            } else {                                                            // :851  WHEN OTHER
                // MOVE CDEMO-ACCT-ID TO ACCTSIDO - 9(11) into X(11), so the eleven-digit display form
                // moves left-justified rather than being right-aligned as a number would be.
                response.setAcctsido(codec.movePic9(ws.commarea.acctId(), ACCT_ID_DIGITS)); // :852
                putFieldAttribute(request, acctLabel, BmsAttributes.DFHBMFSE);              // :853
            }

            // :856-867  the card filter, the same shape
            if (ws.isFlgCardfilterIsValid() || ws.isFlgCardfilterNotOk()) {     // :857-858
                response.setCardsido(ws.ccWorkArea.getCcCardNum());             // :859
                putFieldAttribute(request, cardLabel, BmsAttributes.DFHBMFSE);  // :860
            } else if (ws.commarea.cardNum() == 0L) {                           // :861
                response.setCardsido(CardScreenState.lowValues(
                        CardListResponse.CARDSIDO_LENGTH));                     // :862
            } else {                                                            // :863  WHEN OTHER
                response.setCardsido(codec.movePic9(ws.commarea.cardNum(), CARD_NUM_DIGITS)); // :864
                putFieldAttribute(request, cardLabel, BmsAttributes.DFHBMFSE);                // :866
            }
        }

        // :872-875  IF FLG-ACCTFILTER-NOT-OK -> redden the field and aim the cursor at it.
        if (ws.acctFilterState().notOk()) {
            response.fieldAttributes(acctLabel).setColour(BmsAttributes.DFHRED);
            placeCursor(ws, acctLabel);
        }
        // :877-880  the same for the card filter
        if (ws.cardFilterState().notOk()) {
            response.fieldAttributes(cardLabel).setColour(BmsAttributes.DFHRED);
            placeCursor(ws, cardLabel);
        }
        // :884-886  IF INPUT-OK -> park the cursor on the account filter. Note that this comes AFTER the
        // two blocks above, so on a clean screen the cursor ends up here whatever they did.
        if (ws.isInputOk()) {
            placeCursor(ws, acctLabel);
        }
    }

    /**
     * {@code 1400-SETUP-MESSAGE}, {@code :895-932} - choose the message, then write both lines.
     *
     * <p>A seven-arm ordered {@code EVALUATE} in which the first match wins, so the arms are written in
     * source order and none is reordered or merged. Two of them are worth pointing at:
     * <ul>
     *   <li>{@code :905-909} reports "no more pages" only when the last page has <em>already</em> been
     *       shown, which is how a second {@code PF8} on the final page differs from the first;</li>
     *   <li>{@code :910-916} handles that first {@code PF8}: it shows the row-action prompt and, inside
     *       the arm, flips {@code CA-LAST-PAGE-SHOWN} on so the next {@code PF8} reaches the arm
     *       above.</li>
     * </ul>
     *
     * <p>The tail at {@code :924-930} writes the error line unconditionally and the information line only
     * when there is one to show and the error is not the "no records found" text - a test on the error
     * message's <em>content</em>, because {@code 88 WS-NO-RECORDS-FOUND} is a condition name on
     * {@code WS-ERROR-MSG}.
     *
     * @param ws       the work area
     * @param response the response under construction
     */
    void setupMessage(WorkArea ws, CardListResponse response) {
        if (ws.isFlgAcctfilterNotOk() || ws.isFlgCardfilterNotOk()) {           // :898-899
            // :900  CONTINUE - the filter message already in WS-ERROR-MSG is the one to show.
            ws.messageArm = MessageArm.FILTER_IN_ERROR;
        } else if (ws.ccWorkArea.isCcardAidPfk07() && ws.cursor.isFirstPage()) {  // :901-902
            ws.setWsErrorMsg(codec.movePicX(MSG_NO_PREVIOUS_PAGES, WS_ERROR_MSG_LENGTH));  // :903-904
            ws.messageArm = MessageArm.NO_PREVIOUS_PAGES;
        } else if (ws.ccWorkArea.isCcardAidPfk08()
                && ws.cursor.isNextPageNotExists()
                && ws.cursor.isLastPageShown()) {                                // :905-907
            ws.setWsErrorMsg(codec.movePicX(MSG_NO_MORE_PAGES, WS_ERROR_MSG_LENGTH));      // :908-909
            ws.messageArm = MessageArm.NO_MORE_PAGES;
        } else if (ws.ccWorkArea.isCcardAidPfk08() && ws.cursor.isNextPageNotExists()) {   // :910-911
            ws.setWsInfoMsg(codec.movePicX(WS_INFORM_REC_ACTIONS, WS_INFO_MSG_LENGTH));    // :912
            if (ws.cursor.isLastPageNotShown() && ws.cursor.isNextPageNotExists()) {       // :913-914
                ws.cursor = ws.cursor.withLastPageDisplayed(CardListRequest.LAST_PAGE_SHOWN);  // :915
            }
            ws.messageArm = MessageArm.LAST_PAGE_REACHED;
        } else if (isWsNoInfoMessage(ws) || ws.cursor.isNextPageExists()) {      // :917-918
            ws.setWsInfoMsg(codec.movePicX(WS_INFORM_REC_ACTIONS, WS_INFO_MSG_LENGTH));    // :919
            ws.messageArm = MessageArm.INFORM_REC_ACTIONS;
        } else {                                                                 // :920  WHEN OTHER
            ws.setWsNoInfoMessage();                                                       // :921
            ws.messageArm = MessageArm.NO_INFO_MESSAGE;
        }

        // :924-930  MOVE WS-ERROR-MSG TO ERRMSGO, then the guarded information line and DFHNEUTR.
        // A null second argument is how applyMessages expresses "the guarded block was skipped".
        boolean showInfo = !isWsNoInfoMessage(ws) && !isWsNoRecordsFound(ws);
        response.applyMessages(ws.wsErrorMsg, showInfo ? ws.wsInfoMsg : null);
    }

    /**
     * {@code 1500-SEND-SCREEN}, {@code :938-946} - {@code EXEC CICS SEND MAP(LIT-THISMAP)
     * MAPSET(LIT-THISMAPSET) FROM(CCRDLIAO) CURSOR ERASE RESP(WS-RESP-CD) FREEKB}.
     *
     * <p>In this projection the response <em>is</em> {@code CCRDLIAO}, so there is no transmission to
     * perform: what the five paragraphs above wrote into the map is what the client receives. Three
     * options on the command are 3270 terminal concerns with no data equivalent and are recorded rather
     * than emulated - {@code CURSOR} honours the {@code xxxL} markers, {@code ERASE} clears the physical
     * screen first and {@code FREEKB} unlocks the keyboard.
     *
     * <p>What the statement does have that matters here is {@code RESP(WS-RESP-CD)}: the send overwrites
     * the response code the browse left there. A successful send reports
     * {@link FileStatus#NORMAL}, and since nothing in this program tests the value afterwards, recording
     * it is the whole of the translation.
     *
     * @param ws       the work area
     * @param response the response under construction
     */
    void sendScreen(WorkArea ws, CardListResponse response) {
        ws.wsRespCd = FileStatus.NORMAL;
        ws.wsReasCd = CardRepository.NO_REASON_CODE;
        ws.lastOutcome = Outcome.OK;
        ws.mapSent = true;
        // The map and mapset the send names, so a caller can confirm which screen was painted.
        response.setPageCursor(ws.cursor);
        response.setCardScreenState(ws.ccWorkArea);
        // CURSOR on the SEND statement honours the xxxL markers the two attribute paragraphs left. This
        // is the one point at which the cursor request becomes observable, so it is where the request is
        // handed to the response for publication under screenMetadata (see placeCursor for why the
        // marker cannot travel as a payload field).
        response.setCursorField(ws.cursorField);
    }

    // =================================================================================================
    // WS-FILE-ERROR-MESSAGE - app/cbl/COCRDLIC.cbl:153-171, composed at :1226-1230, :1250-1254,
    // :1312-1316 and :1365-1369. Four sites, identical at all four.
    // =================================================================================================

    /**
     * Composes the {@value #FILE_ERROR_MESSAGE_LENGTH}-character diagnostic, byte for byte.
     *
     * <p>Nine spans in declared order:
     * <pre>
     *   'File Error: '   X(12)   the eleven-character literal in a twelve-character filler
     *   ERROR-OPNAME     X(8)    'READ' at all four sites
     *   ' on '           X(4)
     *   ERROR-FILE       X(9)    'CARDDAT ' - the CICS file name, in an nine-character field
     *   ' returned RESP ' X(15)
     *   ERROR-RESP       X(10)   WS-RESP-CD, nine digits left-justified in ten
     *   ',RESP2 '        X(7)
     *   ERROR-RESP2      X(10)   WS-REAS-CD, the same
     *   FILLER           X(5)    no VALUE clause, so spaces
     * </pre>
     * summing to exactly {@value #FILE_ERROR_MESSAGE_LENGTH}. The receiving
     * {@code WS-ERROR-MSG} is {@code PIC X(75)}, so the caller's move drops the trailing five - which are
     * the filler's spaces, so no text is lost, but the truncation is real and is performed rather than
     * worked around.
     *
     * <p>All four sites move {@code 'READ'} into {@code ERROR-OPNAME} and {@code LIT-CARD-FILE} into
     * {@code ERROR-FILE}, so both are set here; they are recorded on the work area rather than only used,
     * so a caller can read back the operation and file the diagnostic named.
     *
     * @param ws the work area, carrying the response and reason codes the failing read reported
     * @return exactly {@value #FILE_ERROR_MESSAGE_LENGTH} characters
     */
    String fileErrorMessage(WorkArea ws) {
        ws.errorOpname = codec.movePicX(ERROR_OPNAME_READ, ERROR_OPNAME_LENGTH);
        ws.errorFile = codec.movePicX(LIT_CARD_FILE, ERROR_FILE_LENGTH);
        // MOVE WS-RESP-CD TO ERROR-RESP moves a PIC S9(09) COMP item into a PIC X(10) one: the sender's
        // nine-digit display form goes in left-justified, padded on the right - not right-aligned.
        ws.errorResp = codec.movePicX(codec.movePic9(ws.wsRespCd, RESP_DIGITS), ERROR_RESP_LENGTH);
        ws.errorResp2 = codec.movePicX(codec.movePic9(ws.wsReasCd, RESP_DIGITS), ERROR_RESP_LENGTH);
        return codec.movePicX(FILE_ERROR_PREFIX, FILE_ERROR_PREFIX_LENGTH)
                + ws.errorOpname
                + FILE_ERROR_ON
                + ws.errorFile
                + FILE_ERROR_RETURNED_RESP
                + ws.errorResp
                + FILE_ERROR_RESP2
                + ws.errorResp2
                + " ".repeat(FILE_ERROR_TRAILER_LENGTH);
    }

    // =================================================================================================
    // Condition names that need the MOVE rule to be evaluated, so they live here rather than on the work
    // area: an 88-level VALUE literal shorter than its field is compared padded to the field's width.
    // =================================================================================================

    /**
     * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES.} - {@code app/cbl/COCRDLIC.cbl:113-114},
     * tested at {@code :917} and {@code :926}.
     *
     * @param ws the work area
     * @return {@code true} when {@code WS-INFO-MSG} is entirely spaces or entirely {@code LOW-VALUES}
     */
    static boolean isWsNoInfoMessage(WorkArea ws) {
        return isEvery(ws.wsInfoMsg, ' ') || isEvery(ws.wsInfoMsg, WorkArea.LOW_VALUE);
    }

    /**
     * {@code 88 WS-NO-RECORDS-FOUND} - {@code app/cbl/COCRDLIC.cbl:121-122}, set at {@code :1244} and
     * tested at {@code :927}.
     *
     * @param ws the work area
     * @return {@code true} when {@code WS-ERROR-MSG} holds that text at the field's declared width
     */
    boolean isWsNoRecordsFound(WorkArea ws) {
        return codec.movePicX(WS_NO_RECORDS_FOUND, WS_ERROR_MSG_LENGTH).equals(ws.wsErrorMsg);
    }

    /**
     * {@code 88 WS-MORE-THAN-1-ACTION} - {@code app/cbl/COCRDLIC.cbl:123-124}, set at {@code :1086} and
     * tested at {@code :1103}.
     *
     * @param ws the work area
     * @return {@code true} when {@code WS-ERROR-MSG} holds that text at the field's declared width
     */
    boolean isWsMoreThan1Action(WorkArea ws) {
        return codec.movePicX(WS_MORE_THAN_1_ACTION, WS_ERROR_MSG_LENGTH).equals(ws.wsErrorMsg);
    }

    /**
     * Whether every character of a value is one particular character - the form a COBOL comparison
     * against a figurative constant takes.
     *
     * @param value     the value to test; an empty value is vacuously every character
     * @param character the character to test for
     * @return {@code true} when no character differs
     */
    static boolean isEvery(String value, char character) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    // =================================================================================================
    // The two areas the stateless projection has to reconstruct, and the two metadata writes.
    // =================================================================================================

    /**
     * The {@code WS-SCREEN-DATA} state an {@code INITIALIZE WS-THIS-PROGCOMMAREA} leaves -
     * {@code app/cbl/COCRDLIC.cbl:317}, {@code :338} and {@code :463}.
     *
     * <p>{@code INITIALIZE} without {@code REPLACING} sets every alphanumeric item in the group to
     * <strong>spaces</strong>, and ignores {@code VALUE} clauses while doing it. That is
     * <em>not</em> the same as {@link ScreenRowTable#lowValues()}, which is what
     * {@code MOVE LOW-VALUES TO WS-ALL-ROWS} [{@code :1124}, {@code :1266}] produces, and the two are
     * kept apart because {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} [{@code :680} and its six siblings]
     * distinguishes them. In practice every path that reaches that test has run a browse first, and a
     * browse begins by moving {@code LOW-VALUES}; the distinction is preserved anyway, because relying on
     * an unobservable difference is how an observable one eventually appears.
     *
     * @return the space-filled table
     */
    static ScreenRowTable initializedScreenRowTable() {
        List<CardListRequest.ScreenRow> initialised = new ArrayList<>(WS_MAX_SCREEN_LINES);
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            initialised.add(new CardListRequest.ScreenRow(
                    CardScreenState.spaces(CardListRequest.SCREEN_ROW_ACCTNO_LENGTH),
                    CardScreenState.spaces(CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH),
                    CardScreenState.spaces(CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH)));
        }
        return new ScreenRowTable(initialised);
    }

    /**
     * Rebuilds the 196-byte {@code WS-SCREEN-DATA} table a continuing request carried.
     *
     * <p>On the mainframe the table arrives in the communication area, because
     * {@code MOVE DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)}
     * [{@code app/cbl/COCRDLIC.cbl:329-331}] transports all 254 bytes of the area the table is part of.
     * {@link CardListRequest#getScreenRowTable()} is deliberately kept off the JSON wire by its owner, so
     * it is rebuilt here from the three members of each row the payload <em>does</em> carry -
     * {@code ACCTNOnI}, {@code CRDNUMnI} and {@code CRDSTSnI}, which
     * {@code 1200-SCREEN-ARRAY-INIT} wrote from that very table on the previous turn. The reconstruction
     * is therefore information-preserving rather than an approximation.
     *
     * <p>A row whose three members are all blank is restored to {@code LOW-VALUES} rather than to spaces.
     * That is the state an unfilled row was left in by {@code MOVE LOW-VALUES TO WS-ALL-ROWS}, it is what
     * a 3270 returns for a field that was never written, and it is what
     * {@code IF WS-EACH-CARD(n) EQUAL LOW-VALUES} has to see for an empty row to stay empty.
     *
     * @param request the inbound payload
     * @return the reconstructed table
     */
    ScreenRowTable restoreScreenRowTable(CardListRequest request) {
        ScreenRowTable restored = ScreenRowTable.lowValues();
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            String acctNo = codec.movePicX(request.row(row).acctNo(),
                    CardListRequest.SCREEN_ROW_ACCTNO_LENGTH);
            String cardNum = codec.movePicX(request.row(row).crdNum(),
                    CardListRequest.SCREEN_ROW_CARD_NUM_LENGTH);
            String status = codec.movePicX(request.row(row).crdSts(),
                    CardListRequest.SCREEN_ROW_CARD_STATUS_LENGTH);
            if (isBlankOrLowValues(acctNo) && isBlankOrLowValues(cardNum)
                    && isBlankOrLowValues(status)) {
                continue;   // the row was never written, so it stays LOW-VALUES
            }
            restored = restored.withRow(row,
                    new CardListRequest.ScreenRow(acctNo, cardNum, status));
        }
        return restored;
    }

    /**
     * Whether a value carries nothing: entirely spaces, or entirely {@code LOW-VALUES}.
     *
     * @param value the value to test
     * @return {@code true} when it carries no data
     */
    static boolean isBlankOrLowValues(String value) {
        return isEvery(value, ' ') || isEvery(value, WorkArea.LOW_VALUE);
    }

    /**
     * {@code MOVE <attribute> TO xxxA OF CCRDLIAI} - the attribute byte of one input-group field.
     *
     * <p>{@code xxxA} is a {@code REDEFINES} view of the one-byte {@code xxxF} flag item and is
     * validation and highlight <strong>metadata</strong>, never a payload member (gate G9), so it is
     * recorded against the request the way {@link CardListRequest.FieldMetadata} models it. Any input
     * length already reported for the field is carried through unchanged: this statement writes the
     * attribute byte and nothing else.
     *
     * @param request   the inbound payload, standing for {@code CCRDLIAI}
     * @param label     the field's {@code DFHMDF} label
     * @param attribute the attribute byte to store
     */
    static void putFieldAttribute(CardListRequest request, String label, byte attribute) {
        int reportedLength = request.fieldMetadataOf(label)
                .map(CardListRequest.FieldMetadata::inputLength)
                .orElse(0);
        request.putFieldMetadata(new CardListRequest.FieldMetadata(label, reportedLength,
                (char) (attribute & 0xFF)));
    }

    /**
     * {@code MOVE -1 TO xxxL OF CCRDLIAI} - aim the 3270 cursor at a field.
     *
     * <p><strong>This placement is recorded, not reproduced, and the reason is a deliberate constraint of
     * the DTO contract.</strong> The {@code xxxL} length item is input-group metadata rather than a
     * payload member, and {@link CardListRequest.FieldMetadata} rejects a negative length by construction
     * because CICS reports {@code 0} for a field the operator did not touch. There is therefore no
     * representable target for the {@code -1} marker, and no payload field is invented to carry it: the
     * field the cursor was aimed at is recorded on the work area so it can be asserted, and the
     * observable half of each of these statements - the {@code DFHRED} colour item and, on row 1, the
     * {@code '*'} marker - is applied in full.
     *
     * <p>The five sites are {@code app/cbl/COCRDLIC.cbl:770} and its five row siblings, {@code :874},
     * {@code :879} and {@code :885}. The last one runs after the other two, so on a screen that edited
     * cleanly the cursor ends on the account filter whatever came before.
     *
     * @param ws    the work area
     * @param label the field's {@code DFHMDF} label
     */
    static void placeCursor(WorkArea ws, String label) {
        ws.cursorField = label;
    }

    /**
     * The {@code DFHMDF} label of one row's selection field - {@code CRDSEL1} through {@code CRDSEL7}.
     *
     * <p>Derived from the map's own item name rather than composed from a string literal, so a label that
     * does not exist fails where it is asked for instead of silently addressing nothing.
     *
     * @param row the COBOL subscript, {@code 1..7}
     * @return the label
     */
    static String rowSelectionLabel(int row) {
        return fieldLabel(CardListResponse.crdselItem(row));
    }

    /**
     * The {@code DFHMDF} label behind an {@code xxxO} item name - {@code ACCTSIDO} yields
     * {@code ACCTSID}.
     *
     * @param itemName the output item name
     * @return the label
     */
    static String fieldLabel(String itemName) {
        return CardListResponse.mapField(itemName).screenFieldPrefix();
    }

    /**
     * The digits of a fixed-width character image, as a {@code MOVE} of an alphanumeric item into a
     * {@code PIC 9} item would read them.
     *
     * <p>{@code MOVE WS-ROW-ACCTNO(I-SELECTED) TO CDEMO-ACCT-ID} [{@code app/cbl/COCRDLIC.cbl:531-532}]
     * moves eleven characters into eleven digits, and {@code :533-534} moves sixteen into sixteen. When
     * the characters are digits, the digits move. When they are not - which happens when the operator
     * types an action code on a row that holds no card - the COBOL result is undefined: the bytes are
     * stored into a numeric item that then contains no valid number. <strong>Zero is the only defined
     * reading</strong>, it is what the program itself moves whenever it has no value
     * ({@code MOVE ZEROES TO CDEMO-ACCT-ID} at {@code :1011} and {@code :1024}), and it is what this
     * method returns.
     *
     * @param image the character image, at its declared width
     * @param width the declared width, for the message
     * @return the numeric value, or {@code 0} when the image is not all digits
     */
    static long digitsOf(String image, int width) {
        if (image.length() != width) {
            return 0L;
        }
        for (int index = 0; index < width; index++) {
            char digit = image.charAt(index);
            if (digit < '0' || digit > '9') {
                return 0L;
            }
        }
        return Long.parseLong(image);
    }

    // =================================================================================================
    // Which arm of 1400-SETUP-MESSAGE was taken. Not a COBOL construct: EVALUATE leaves no trace of its
    // decision, and this records the one it made so a test can assert the arm rather than only its
    // visible effect - two arms produce the same information message by different routes.
    // =================================================================================================

    /** The seven arms of {@code 1400-SETUP-MESSAGE} [{@code app/cbl/COCRDLIC.cbl:897-922}]. */
    enum MessageArm {

        /** No arm has run yet. */
        NONE,

        /** {@code :898-900} - a filter failed edit, so its own message stands. {@code CONTINUE}. */
        FILTER_IN_ERROR,

        /** {@code :901-904} - {@code PF7} on the first page. */
        NO_PREVIOUS_PAGES,

        /** {@code :905-909} - {@code PF8} when the last page has already been shown. */
        NO_MORE_PAGES,

        /** {@code :910-916} - the first {@code PF8} that finds no next page. */
        LAST_PAGE_REACHED,

        /** {@code :917-919} - nothing to say, or there is a next page: prompt for a row action. */
        INFORM_REC_ACTIONS,

        /** {@code :920-921} - {@code WHEN OTHER}: show no information message. */
        NO_INFO_MESSAGE
    }

    // =================================================================================================
    // THE PER-REQUEST WORK AREA - 01 WS-MISC-STORAGE [app/cbl/COCRDLIC.cbl:41-217], 01 CC-WORK-AREAS
    // [COPY CVCRD01Y at :221], 01 CARDDEMO-COMMAREA [COPY COCOM01Y at :227] and
    // 01 WS-THIS-PROGCOMMAREA [:229-260], together.
    //
    // WHY THIS TYPE EXISTS (practice B9, gate G53). A CICS program's WORKING-STORAGE is private to one
    // task. Translating it into fields on a singleton bean would share it across concurrent requests,
    // which breaks request isolation and makes tests order-dependent; translating it into a long parameter
    // list would make every paragraph's signature depend on every other's. It is therefore one object,
    // created per call by 0000-MAIN's own INITIALIZE statement at :300-302 and reachable from nowhere
    // else. Nothing on the controller is mutable and nothing anywhere is static and mutable.
    //
    // The fields are package-visible rather than encapsulated on purpose: they ARE the storage, a test
    // asserts them directly, and a getter pair per COBOL item would add 40 methods that say nothing. Every
    // 88-level condition name, on the other hand, IS a named method, because a condition name is a name
    // and reproducing it as a bare character comparison at each site is how an ordering gets lost.
    // =================================================================================================

    /**
     * One transaction's {@code WORKING-STORAGE}.
     *
     * <p>Construction is {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA}
     * [{@code app/cbl/COCRDLIC.cbl:300-302}]: alphanumeric items become spaces, numeric items become
     * zero, and {@code VALUE} clauses are ignored, which is what {@code INITIALIZE} does. Note the
     * consequence for {@code WS-EDIT-SELECT-FLAGS}, declared {@code VALUE LOW-VALUES} at {@code :72-73}:
     * after the {@code INITIALIZE} it holds spaces, not {@code LOW-VALUES}. Both satisfy
     * {@code 88 SELECT-BLANK}, so no behaviour turns on it, and the faithful state is the one recorded.
     *
     * <p>This class is not thread-safe and does not need to be: one instance belongs to one request.
     */
    static final class WorkArea {

        /** The COBOL figurative constant {@code LOW-VALUES}: binary zero, whichever code page is in force. */
        static final char LOW_VALUE = '\u0000';

        // ---- 05 WS-CICS-PROCESSNG-VARS, :46-52 -----------------------------------------------------

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code :47-48}. */
        int wsRespCd = FileStatus.NORMAL;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code :49-50}. */
        int wsReasCd = CardRepository.NO_REASON_CODE;

        /** {@code WS-TRANID PIC X(4) VALUE SPACES} - {@code :51-52}. */
        String wsTranid = " ".repeat(NavigationContext.FROM_TRANID_LENGTH);

        /** The classification of the last read, kept beside the two raw values it was derived from. */
        Outcome lastOutcome = Outcome.OK;

        /**
         * The last record a read actually returned, standing for the {@code CARD-RECORD} area
         * {@code INTO(CARD-RECORD)} fills.
         *
         * <p>{@code null} means no read has returned one yet, which on the mainframe is the untouched
         * {@code WORKING-STORAGE} area from {@code COPY CVACT02Y} - binary zeros throughout. The
         * {@code ENDFILE} arm at {@code :1236-1237} reads this area, so the distinction is load-bearing
         * there.
         */
        CardRecord lastCardRead;

        // ---- Input edits, :56-94 -------------------------------------------------------------------

        /** {@code WS-INPUT-FLAG PIC X(1)} - {@code :56}. */
        char wsInputFlag = ' ';

        /** {@code WS-EDIT-ACCT-FLAG PIC X(1)} - {@code :61}. */
        char wsEditAcctFlag = ' ';

        /** {@code WS-EDIT-CARD-FLAG PIC X(1)} - {@code :65}. */
        char wsEditCardFlag = ' ';

        /** {@code WS-EDIT-SELECT-FLAGS PIC X(7)}, redefined as the seven action codes - {@code :72-82}. */
        SelectionFlags selectionFlags = SelectionFlags.spacesFilled();

        /** {@code WS-EDIT-SELECT-ERROR-FLAGS PIC X(7)}, redefined per row - {@code :83-88}. */
        SelectionErrorFlags selectionErrorFlags = SelectionErrorFlags.none();

        /** {@code I PIC S9(4) COMP VALUE 0} - {@code :90-91}, the tally and the loop subscript. */
        int i;

        /** {@code I-SELECTED PIC S9(4) COMP VALUE 0} - {@code :92-93}, the chosen row, 1-based. */
        int iSelected;

        // ---- Output edits, :98-107 -----------------------------------------------------------------

        /** {@code FLG-PROTECT-SELECT-ROWS PIC X(1)} - {@code :105-107}. */
        char flgProtectSelectRows = ' ';

        // ---- Output message construction, :111-132 -------------------------------------------------

        /** {@code WS-INFO-MSG PIC X(45)} - {@code :112}. */
        String wsInfoMsg = " ".repeat(WS_INFO_MSG_LENGTH);

        /** {@code WS-ERROR-MSG PIC X(75)} - {@code :117}. */
        String wsErrorMsg = " ".repeat(WS_ERROR_MSG_LENGTH);

        /** {@code WS-PFK-FLAG PIC X(1)} - {@code :127-129}. */
        char wsPfkFlag = ' ';

        /** Which arm of {@code 1400-SETUP-MESSAGE} ran. Not a COBOL item; see {@link MessageArm}. */
        MessageArm messageArm = MessageArm.NONE;

        // ---- File and data handling, :136-171 ------------------------------------------------------

        /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :138}, the browse key. */
        String wsCardRidCardnum = " ".repeat(WS_CARD_RID_CARDNUM_LENGTH);

        /** {@code WS-SCRN-COUNTER PIC S9(4) COMP VALUE 0} - {@code :145}, 1-based when it addresses a row. */
        int wsScrnCounter;

        /** {@code WS-FILTER-RECORD-FLAG PIC X(1)} - {@code :147-149}. */
        char wsFilterRecordFlag = ' ';

        /** {@code WS-RECORDS-TO-PROCESS-FLAG PIC X(1)} - {@code :150-152}. */
        char wsRecordsToProcessFlag = ' ';

        /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :156-157}. */
        String errorOpname = " ".repeat(ERROR_OPNAME_LENGTH);

        /** {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :160-161}. */
        String errorFile = " ".repeat(ERROR_FILE_LENGTH);

        /** {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :165-166}. */
        String errorResp = " ".repeat(ERROR_RESP_LENGTH);

        /** {@code ERROR-RESP2 PIC X(10) VALUE SPACES} - {@code :169-170}. */
        String errorResp2 = " ".repeat(ERROR_RESP_LENGTH);

        // ---- The three areas, :221-260 -------------------------------------------------------------

        /** {@code 01 CC-WORK-AREAS} - {@code COPY CVCRD01Y} at {@code :221}. */
        final CardScreenState ccWorkArea = new CardScreenState();

        /** {@code 01 CARDDEMO-COMMAREA} - {@code COPY COCOM01Y} at {@code :227}. */
        NavigationContext commarea = NavigationContext.empty();

        /** The 58-byte head of {@code 01 WS-THIS-PROGCOMMAREA} - {@code :230-248}. */
        PageCursor cursor = PageCursor.initialised();

        /** The 196-byte tail of {@code 01 WS-THIS-PROGCOMMAREA} - {@code 05 WS-SCREEN-DATA}, {@code :252-260}. */
        ScreenRowTable screenRows = ScreenRowTable.lowValues();

        // ---- Facts about this run that COBOL leaves implicit ---------------------------------------

        /** Whether {@code EIBCALEN = 0} held at {@code :315} - the cold start. */
        boolean eibcalenZero;

        /** Whether {@code :410-411}'s {@code IF CCARD-AID-PFK08 CONTINUE} arm was the one taken. */
        boolean pfk08Continued;

        /** Whether {@code 1500-SEND-SCREEN} ran, that is whether the map was painted at all. */
        boolean mapSent;

        /**
         * The {@code DFHMDF} label the last {@code MOVE -1 TO xxxL} aimed the cursor at, or {@code null}
         * when none did. See {@link CardListController#placeCursor(WorkArea, String)} for why the marker
         * itself is recorded rather than stored.
         */
        String cursorField;

        /** {@code WS-CURDATE-DATA} as the discarded first {@code FUNCTION CURRENT-DATE} read it - {@code :645}. */
        String wsCurdateData = "";

        /** The date and time the second read produced - {@code :652}, the one every header item derives from. */
        DateHeader dateHeader;

        // ---- 88-level condition names. One method each, in declaration order. ----------------------

        /** {@code 88 INPUT-OK VALUES '0' ' ' LOW-VALUES.} - {@code :57-59}. */
        boolean isInputOk() {
            return wsInputFlag == '0' || wsInputFlag == ' ' || wsInputFlag == LOW_VALUE;
        }

        /** {@code 88 INPUT-ERROR VALUE '1'.} - {@code :60}. */
        boolean isInputError() {
            return wsInputFlag == '1';
        }

        /** {@code SET INPUT-OK TO TRUE} - {@code :986}. Selects the first of the three values, {@code '0'}. */
        void setInputOk() {
            wsInputFlag = '0';
        }

        /** {@code SET INPUT-ERROR TO TRUE} - {@code :1018}, {@code :1053}, {@code :1085}, {@code :1109}. */
        void setInputError() {
            wsInputFlag = '1';
        }

        /** {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'.} - {@code :62}. */
        boolean isFlgAcctfilterNotOk() {
            return wsEditAcctFlag == '0';
        }

        /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'.} - {@code :63}. */
        boolean isFlgAcctfilterIsValid() {
            return wsEditAcctFlag == '1';
        }

        /** {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '.} - {@code :64}. */
        boolean isFlgAcctfilterBlank() {
            return wsEditAcctFlag == ' ';
        }

        /** {@code SET FLG-ACCTFILTER-NOT-OK TO TRUE} - {@code :1019}. */
        void setFlgAcctfilterNotOk() {
            wsEditAcctFlag = '0';
        }

        /** {@code SET FLG-ACCTFILTER-ISVALID TO TRUE} - {@code :1028}. */
        void setFlgAcctfilterIsValid() {
            wsEditAcctFlag = '1';
        }

        /** {@code SET FLG-ACCTFILTER-BLANK TO TRUE} - {@code :1004} and again at {@code :1010}. */
        void setFlgAcctfilterBlank() {
            wsEditAcctFlag = ' ';
        }

        /** {@code 88 FLG-CARDFILTER-NOT-OK VALUE '0'.} - {@code :66}. */
        boolean isFlgCardfilterNotOk() {
            return wsEditCardFlag == '0';
        }

        /** {@code 88 FLG-CARDFILTER-ISVALID VALUE '1'.} - {@code :67}. */
        boolean isFlgCardfilterIsValid() {
            return wsEditCardFlag == '1';
        }

        /** {@code 88 FLG-CARDFILTER-BLANK VALUE ' '.} - {@code :68}. */
        boolean isFlgCardfilterBlank() {
            return wsEditCardFlag == ' ';
        }

        /** {@code SET FLG-CARDFILTER-NOT-OK TO TRUE} - {@code :1054}. */
        void setFlgCardfilterNotOk() {
            wsEditCardFlag = '0';
        }

        /** {@code SET FLG-CARDFILTER-ISVALID TO TRUE} - {@code :1065}. */
        void setFlgCardfilterIsValid() {
            wsEditCardFlag = '1';
        }

        /** {@code SET FLG-CARDFILTER-BLANK TO TRUE} - {@code :1039} and again at {@code :1045}. */
        void setFlgCardfilterBlank() {
            wsEditCardFlag = ' ';
        }

        /**
         * The account filter's outcome in the vocabulary {@code CSSETATY} is written in.
         *
         * <p>{@link FieldValidationState} is the three-state form of the two condition names
         * {@code FLG-<field>-NOT-OK} and {@code FLG-<field>-BLANK}, and it is the vocabulary the shared
         * highlight decision speaks. {@code COCRDLIC} does not copy {@code CSSETATY} - its only include of
         * that family is {@code COPY 'CSSTRPFY'} at {@code :1416} - so the decision applied to this state
         * at {@code :872-875} is the program's own inline one, without the {@code CDEMO-PGM-REENTER} guard
         * and without the {@code '*'} marker. The state is still expressed in the shared vocabulary,
         * because that is what it is.
         *
         * @return the account filter's validation outcome
         */
        FieldValidationState acctFilterState() {
            return FieldValidationState.of(isFlgAcctfilterNotOk(), isFlgAcctfilterBlank());
        }

        /**
         * The card filter's outcome, in the same vocabulary.
         *
         * @return the card filter's validation outcome
         * @see #acctFilterState()
         */
        FieldValidationState cardFilterState() {
            return FieldValidationState.of(isFlgCardfilterNotOk(), isFlgCardfilterBlank());
        }

        /** {@code 88 FLG-PROTECT-SELECT-ROWS-NO VALUE '0'.} - {@code :106}. */
        boolean isFlgProtectSelectRowsNo() {
            return flgProtectSelectRows == '0';
        }

        /** {@code 88 FLG-PROTECT-SELECT-ROWS-YES VALUE '1'.} - {@code :107}. */
        boolean isFlgProtectSelectRowsYes() {
            return flgProtectSelectRows == '1';
        }

        /** {@code SET FLG-PROTECT-SELECT-ROWS-NO TO TRUE} - {@code :987}. */
        void setFlgProtectSelectRowsNo() {
            flgProtectSelectRows = '0';
        }

        /** {@code SET FLG-PROTECT-SELECT-ROWS-YES TO TRUE} - {@code :1020}, {@code :1055}. */
        void setFlgProtectSelectRowsYes() {
            flgProtectSelectRows = '1';
        }

        /** {@code 88 PFK-VALID VALUE '0'.} - {@code :128}. */
        boolean isPfkValid() {
            return wsPfkFlag == '0';
        }

        /** {@code 88 PFK-INVALID VALUE '1'.} - {@code :129}. */
        boolean isPfkInvalid() {
            return wsPfkFlag == '1';
        }

        /** {@code SET PFK-VALID TO TRUE} - {@code :375}. */
        void setPfkValid() {
            wsPfkFlag = '0';
        }

        /** {@code SET PFK-INVALID TO TRUE} - {@code :370}. */
        void setPfkInvalid() {
            wsPfkFlag = '1';
        }

        /** {@code 88 WS-EXCLUDE-THIS-RECORD VALUE '0'.} - {@code :148}. */
        boolean isExcludeThisRecord() {
            return wsFilterRecordFlag == '0';
        }

        /** {@code 88 WS-DONOT-EXCLUDE-THIS-RECORD VALUE '1'.} - {@code :149}. */
        boolean isDonotExcludeThisRecord() {
            return wsFilterRecordFlag == '1';
        }

        /** {@code SET WS-EXCLUDE-THIS-RECORD TO TRUE} - {@code :1389}, {@code :1400}. */
        void setExcludeThisRecord() {
            wsFilterRecordFlag = '0';
        }

        /** {@code SET WS-DONOT-EXCLUDE-THIS-RECORD TO TRUE} - {@code :1383}. */
        void setDonotExcludeThisRecord() {
            wsFilterRecordFlag = '1';
        }

        /** {@code 88 READ-LOOP-EXIT VALUE '0'.} - {@code :151}, the loop's own termination test. */
        boolean isReadLoopExit() {
            return wsRecordsToProcessFlag == '0';
        }

        /** {@code 88 MORE-RECORDS-TO-READ VALUE '1'.} - {@code :152}. */
        boolean isMoreRecordsToRead() {
            return wsRecordsToProcessFlag == '1';
        }

        /** {@code SET READ-LOOP-EXIT TO TRUE} - {@code :1192}, {@code :1225}, {@code :1234} and five more. */
        void setReadLoopExit() {
            wsRecordsToProcessFlag = '0';
        }

        /** {@code SET MORE-RECORDS-TO-READ TO TRUE} - {@code :1142}, {@code :1288}. */
        void setMoreRecordsToRead() {
            wsRecordsToProcessFlag = '1';
        }

        /** {@code 88 WS-ERROR-MSG-OFF VALUE SPACES.} - {@code :118}. */
        boolean isWsErrorMsgOff() {
            return isEvery(wsErrorMsg, ' ');
        }

        /**
         * {@code SET WS-ERROR-MSG-OFF TO TRUE} - {@code :311}. An unconditional space fill of the whole
         * field, which is what a figurative constant does, and not the {@code PIC X} {@code MOVE} rule -
         * that rule lives in {@link FixedWidthCodec} and is applied by the caller when a text is moved.
         */
        void setWsErrorMsgOff() {
            wsErrorMsg = " ".repeat(WS_ERROR_MSG_LENGTH);
        }

        /**
         * Stores a text into {@code WS-ERROR-MSG}, already moved to the field's declared width.
         *
         * @param message the moved text, exactly {@value CardListController#WS_ERROR_MSG_LENGTH}
         *                characters
         */
        void setWsErrorMsg(String message) {
            wsErrorMsg = message;
        }

        /**
         * {@code SET WS-NO-INFO-MESSAGE TO TRUE} - {@code :669}, {@code :921}. Selects the first of that
         * condition name's two values, {@code SPACES}.
         */
        void setWsNoInfoMessage() {
            wsInfoMsg = " ".repeat(WS_INFO_MSG_LENGTH);
        }

        /**
         * Stores a text into {@code WS-INFO-MSG}, already moved to the field's declared width.
         *
         * @param message the moved text, exactly {@value CardListController#WS_INFO_MSG_LENGTH}
         *                characters
         */
        void setWsInfoMsg(String message) {
            wsInfoMsg = message;
        }

        /**
         * {@code VIEW-REQUESTED-ON(I-SELECTED)} - the compound condition at {@code :518}, guarded.
         *
         * <p>{@code I-SELECTED} is zero when no row was marked [{@code :1097}], and
         * {@code VIEW-REQUESTED-ON(0)} is an <strong>out-of-range subscript</strong>: COBOL's behaviour is
         * undefined and an {@code OCCURS} table has no element 0. The program declares the guard for it
         * itself - {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7} at {@code :94} - and that guard is
         * applied here. The only defined reading of "no row was marked" is that no row requests a view,
         * which is also what sends the request to {@code WHEN OTHER}.
         *
         * @return {@code true} when a row in {@code 1..7} is marked {@code 'S'}
         */
        boolean isViewRequestedOnSelected() {
            return SelectionFlags.isDetailRequested(iSelected)
                    && selectionFlags.isViewRequestedOn(iSelected);
        }

        /**
         * {@code UPDATE-REQUESTED-ON(I-SELECTED)} - the compound condition at {@code :546}, guarded the
         * same way and for the same reason.
         *
         * @return {@code true} when a row in {@code 1..7} is marked {@code 'U'}
         */
        boolean isUpdateRequestedOnSelected() {
            return SelectionFlags.isDetailRequested(iSelected)
                    && selectionFlags.isUpdateRequestedOn(iSelected);
        }

        /** {@code 88 DETAIL-WAS-REQUESTED VALUES 1 THRU 7.} - {@code :94}. */
        boolean isDetailWasRequested() {
            return SelectionFlags.isDetailRequested(iSelected);
        }
    }
}
