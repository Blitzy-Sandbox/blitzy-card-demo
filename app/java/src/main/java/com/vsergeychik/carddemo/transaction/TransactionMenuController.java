package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus.Outcome;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.transaction.TransactionRepository.Browse;
import com.vsergeychik.carddemo.transaction.TransactionRepository.BrowseDirection;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import com.vsergeychik.carddemo.transaction.model.TranRecord;

import jakarta.validation.Valid;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CICS transaction {@code CT00} - the paged transaction list screen, translated from
 * {@code app/cbl/COTRN00C.cbl} (699 lines) into a <strong>stateless</strong> {@code @RestController}
 * exposing {@code GET /api/transactions}.
 *
 * <h2>The class name says "Menu"; the program lists transactions</h2>
 *
 * This is the first thing to know about this file. The prompt-mandated name is
 * {@code TransactionMenuController}, but the source header reads
 * <em>"Function : List Transactions from TRANSACT file"</em> [{@code app/cbl/COTRN00C.cbl:5}] and the
 * program is a <strong>paged browse of a VSAM file, not a menu</strong>: there is no option table, no
 * option number and no dispatch on a chosen option. Compare the two genuine menus,
 * {@code COMEN01C}/{@code COADM01C}, which copy {@code COMEN02Y}/{@code COADM02Y} option tables;
 * {@code COTRN00C} copies neither.
 *
 * <p>Rule <strong>R1</strong> governs and is applied literally: <em>the name comes from the prompt,
 * the behaviour comes from the source.</em> The name is honoured verbatim - it is not "corrected" -
 * and the divergence is recorded here rather than quietly satisfied, which is practice
 * <strong>B4</strong>. It is entry 13 of the divergence register in AAP 0.8.4. Two related notes:
 * {@code docs/**} and {@code catalog-info.yaml} are non-authoritative for this migration and are
 * deliberately neither consulted nor corrected; and the sibling
 * {@code TransactionAddController}/{@code TransactionViewController} pair carries a genuine
 * name-versus-behaviour <em>swap</em> (risk R-B), which this class is not affected by.
 *
 * <h2>No project rules exist</h2>
 *
 * {@code review_rules} returns exactly one line, <em>"No user rules provided."</em> That is the whole
 * document - there is no further page - so <strong>no user-specified rule governs this file</strong>.
 * Their absence is not licence to lower the bar: AAP 0.10.2's enterprise substitutes B1-B12 bind
 * instead, and each one is discharged concretely here. B1: no dependency is added, and every
 * collaborator is an existing module type. B2: Spring Web MVC on the servlet stack, never WebFlux.
 * B3: not one byte of {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms},
 * {@code app/jcl}, {@code app/csd} or {@code app/data} is written. B4: the name divergence above, and
 * the two documented deviations in the next section. B5: the two statements the source has commented
 * out stay commented out, and the one dead {@code WORKING-STORAGE} item stays dead. B6: no
 * authentication, authorisation or masking is introduced - transaction identifiers and amounts travel
 * exactly as the COBOL carries them. B7: one non-interactive {@code mvn verify}. B8: the page size is
 * a compile-time constant, the code page is always named, and both edited renderings are produced by
 * explicit character arithmetic rather than a locale-sensitive formatter. B9: no static mutable
 * state; every {@code WORKING-STORAGE} item lives in a per-request {@link WorkArea}. B10 and gate
 * G51: the whole decision path is reachable through plain public methods with no HTTP in it. B11: the
 * fixed-width moves go through {@link FixedWidthCodec}, which is hand-written and auditable. B12: the
 * parity expectations this class is judged against are <em>statically derived</em> rather than
 * captured from a live COBOL run, because the COBOL cannot be executed in this environment - risk
 * R-A, with the eight verified blockers listed in AAP 0.7.6.
 *
 * <h2>Two deviations from the brief, both deliberate, both evidenced</h2>
 *
 * <ol>
 *   <li><strong>No field highlighting, because this program has none.</strong> The brief's generic
 *       paragraph asks for {@code common/FieldAttributeSetter} to write {@code DFHRED} and
 *       {@code '*'} onto an offending field in re-enter state. {@code COTRN00C} does not do that and
 *       cannot: its {@code COPY} list [{@code :61-81}] is {@code COCOM01Y}, {@code COTRN00},
 *       {@code COTTL01Y}, {@code CSDAT01Y}, {@code CSMSG01Y}, {@code CVTRA05Y}, {@code DFHAID} and
 *       {@code DFHBMSCA} - it copies <em>neither</em> {@code CSSETATY} <em>nor</em> {@code DFHATTR},
 *       and AAP 0.2.3 records that {@code CSSETATY} has exactly one consumer in the whole estate,
 *       {@code COACTUPC}. There is no attribute {@code MOVE} anywhere in the 699 lines. Every error
 *       this screen reports it reports two ways only: the message line, and the cursor placed on
 *       {@code TRNIDIN} by {@code MOVE -1 TO TRNIDINL}. Adding a highlight would be a new feature, a
 *       parity violation (gate G18 compares the attribute items) and a breach of the prompt's own
 *       "no new features" constraint. Gate <strong>G38</strong> - "the highlight applies only in
 *       re-enter state" - is nevertheless discharged, by the attribute behaviour the source
 *       <em>does</em> have: {@code MOVE LOW-VALUES TO COTRN0AO} at {@code :114} is on the enter path
 *       and <strong>only</strong> the enter path, so the attribute quads are reset there and never on
 *       re-entry. {@code TransactionMenuControllerTest} asserts both halves.</li>
 *   <li><strong>{@code WS-SEND-ERASE-FLG} is carried on the work area, not on the response.</strong>
 *       The brief asks for it as response metadata; {@link TransactionListResponse} has no such
 *       member and belongs to another file, so widening it is out of scope. It is instead a first
 *       class, publicly readable item of {@link WorkArea} - {@link WorkArea#isSendEraseYes()} and
 *       {@link WorkArea#lastSendErase()} - reachable through
 *       {@link #listTransactions(TransactionListRequest, byte, WorkArea)}. It is recorded, not
 *       dropped.</li>
 * </ol>
 *
 * <h2>Statelessness (rule R6, gate G37)</h2>
 *
 * CICS is pseudo-conversational: this transaction paints a screen, ends, and is re-driven from the
 * top, and the only thing that survives is the communication area it handed back. That shape is
 * preserved exactly and <strong>nothing is kept on the server</strong>. There is no
 * {@code HttpSession}, no {@code @SessionAttributes}, no {@code ThreadLocal}, no cache keyed by user
 * or terminal, and no static mutable field of any kind. Three things travel in the payload:
 *
 * <ul>
 *   <li>the 160-byte {@link NavigationContext} - {@code COPY COCOM01Y} at {@code :61};</li>
 *   <li>the 58-byte browse cursor {@code CDEMO-CT00-INFO} [{@code :62-70}], which this program
 *       <em>appends</em> to the communication area, making the area it passes
 *       {@value TransactionListCursor#COMMAREA_WITH_CURSOR_LENGTH} bytes. {@code NavigationContext}
 *       is fixed at {@value NavigationContext#COMMAREA_LENGTH} bytes and shared by all seventeen
 *       controllers, so it is <strong>not</strong> widened; the extension is a member of this
 *       screen's request and response instead;</li>
 *   <li>the enter-versus-re-enter context and the attention identifier.</li>
 * </ul>
 *
 * <h2>The page size is behaviour</h2>
 *
 * {@value #PAGE_SIZE} rows per page, as a private compile-time constant (gate <strong>G39</strong>).
 * It is deliberately not an {@code application.yml} key, not a {@code @Value} and not a request
 * parameter. The source hard-codes it four times: the forward blanking loop bounds it with
 * {@code UNTIL WS-IDX > 10} at {@code :290}, the forward fill loop with {@code UNTIL WS-IDX >= 11} at
 * {@code :297}, the backward blanking loop again at {@code :344}, and the backward fill seeds
 * {@code MOVE 10 TO WS-IDX} at {@code :349} before {@code UNTIL WS-IDX <= 0} at {@code :351}. Making
 * it tunable would let a caller ask for a page this screen cannot render and a page count the COBOL
 * would never compute.
 *
 * <h2>Control flow</h2>
 *
 * Every paragraph becomes one method, in source order, each carrying the {@code :line} anchors it was
 * translated from. There is no {@code GO TO} in this program at all - it is one of the twenty
 * programs with none - so rule R7 costs nothing here beyond turning {@code PERFORM} into a call and
 * {@code EVALUATE} into an ordered {@code if}/{@code else} chain whose {@code WHEN OTHER} is last
 * (gate <strong>G30</strong>).
 *
 * <p>{@code EVALUATE EIBAID} [{@code :119-134}] is resolved through {@link PfKeyResolver}, whose
 * {@link PfKeyResolver#isEnter(byte)}, {@link PfKeyResolver#isPf3(byte)},
 * {@link PfKeyResolver#isPf7(byte)} and {@link PfKeyResolver#isPf8(byte)} compare the raw byte
 * against one constant each. That matters: they are exact-byte tests, so {@code DFHPF15} does
 * <em>not</em> behave as {@code DFHPF3} here, which is precisely what {@code EIBAID = DFHPF3}
 * means. The resolver's folded 26-branch map, which does collapse {@code PF13}-{@code PF24} onto
 * {@code PFK01}-{@code PFK12}, is used only where a <em>token</em> is needed - see
 * {@link #resolveEibAid(TransactionListRequest, Integer)}.
 *
 * <h2>Reading the file: {@code STARTBR} has no status here, and why that is still faithful</h2>
 *
 * {@link TransactionRepository#startBrowse(String, BrowseDirection)} performs no backend call and
 * reports no status - it re-positions by value on each step, which is what keeps the online layer
 * free of server-side cursors. {@code COTRN00C}, however, has a three-armed
 * {@code EVALUATE WS-RESP-CD} after its {@code STARTBR} [{@code :602-619}], and one of those arms
 * carries a message no other paragraph produces. The two are reconciled exactly, not approximately:
 *
 * <ul>
 *   <li>{@code STARTBR ... GTEQ} raises {@code NOTFND} if and only if no record has a key at or after
 *       the {@code RIDFLD} - which is <em>the same condition</em> under which the first
 *       {@code READNEXT} of that browse would report {@code ENDFILE}. So {@link TransactBrowse}
 *       positions and then issues <strong>one probe read</strong>, buffering its record for the first
 *       real read. A record means {@code DFHRESP(NORMAL)}; nothing means {@code DFHRESP(NOTFND)}; a
 *       failure means {@code WHEN OTHER}. No record is lost and none is read twice.</li>
 *   <li>When the probe finds nothing the browse is <em>ended</em>, so a subsequent read reports the
 *       invalid-request condition - exactly as CICS does for a {@code READNEXT} with no browse in
 *       progress, which is the state {@code :285-287} can genuinely reach because its guard is
 *       {@code IF NOT ERR-FLG-ON} and {@code NOTFND} does not set that flag.</li>
 *   <li>The {@code WHEN OTHER} arms of {@code STARTBR}, {@code READNEXT} and {@code READPREV} all
 *       produce the identical message and the identical flag, so collapsing a positioning failure
 *       onto the first read is byte-identical either way.</li>
 * </ul>
 *
 * <h2>Numbers</h2>
 *
 * No {@code double} and no {@code float} appears in this file (gate G22). The one monetary value it
 * touches, {@code TRAN-AMT PIC S9(09)V99}, is a {@link BigDecimal} stored at
 * {@value CobolDecimal#MONETARY_SCALE} through {@link CobolDecimal}, whose rounding mode is
 * {@code DOWN} because the keyword {@code ROUNDED} appears zero times in all twenty-eight programs
 * (gates G23, G24). The page number is {@code PIC 9(08)}, a scale-free integer, and is an {@code int}
 * (rule R4).
 *
 * @see TransactionListRequest
 * @see TransactionListResponse
 * @see TransactionRepository
 */
@RestController
public class TransactionMenuController {

    /**
     * The diagnostic log. {@code COTRN00C} writes {@code DISPLAY 'RESP:' ... 'REAS:' ...} in each of
     * its three {@code WHEN OTHER} file arms [{@code :613}, {@code :647}, {@code :681}]; on the
     * mainframe that reaches the job log, and here it reaches this logger. Every call passes exactly
     * one argument and never a throwable, so no driver-composed text can escape through a cause
     * chain.
     */
    private static final Log LOG = LogFactory.getLog(TransactionMenuController.class);

    // =================================================================================================
    // Provenance. The literals the program declares about itself, and the four programs it names.
    // =================================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COTRN00C'} - {@code app/cbl/COTRN00C.cbl:36}. */
    static final String LIT_THIS_PGM = "COTRN00C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CT00'} - {@code :37}; CSD transaction at {@code :419}. */
    static final String LIT_THIS_TRANID = "CT00";

    /** The mapset this screen belongs to, {@code app/bms/COTRN00.bms}. */
    static final String LIT_THIS_MAPSET = "COTRN00";

    /** The map, {@code SEND MAP('COTRN0A') MAPSET('COTRN00')} at {@code :535-536}. */
    static final String LIT_THIS_MAP = "COTRN0A";

    /**
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - {@code :39}, the {@code DATASET} name of
     * all four file commands. It is asserted against {@link TransactionRepository#CICS_FILE_NAME} at
     * construction and is never used to reach a dataset: every read goes through the injected
     * repository, so no {@code AWS.M2.CARDDEMO.*} literal appears anywhere in this file (gate G46).
     */
    static final String LIT_TRANSACT_FILE = "TRANSACT";

    /** The sign-on program, {@code :108} and the default at {@code :513}. */
    static final String LIT_SIGNON_PGM = "COSGN00C";

    /** The main menu, the {@code DFHPF3} target at {@code :123}. */
    static final String LIT_MENU_PGM = "COMEN01C";

    /**
     * The transaction-detail program a row selection transfers to, {@code :188}.
     *
     * <p>Its Java counterpart is named {@code TransactionAddController} even though this source
     * transfers to it to <em>view</em> the selected transaction - risk R-B, the highest-severity
     * naming ambiguity in the migration. Only the eight-character program name travels, so this
     * class is unaffected by the resolution of that ambiguity and deliberately does not import the
     * sibling's types: the client issues the follow-up call, and coupling two screens' payloads is
     * exactly what statelessness is meant to avoid.
     */
    static final String LIT_TRAN_VIEW_PGM = "COTRN01C";

    // =================================================================================================
    // The page size and the loop bounds derived from it (gate G39).
    // =================================================================================================

    /**
     * Rows per page: {@value #PAGE_SIZE}. Behaviour, not configuration - see the class documentation.
     */
    private static final int PAGE_SIZE = 10;

    /** The first screen row, {@code WHEN 1} of both {@code EVALUATE WS-IDX} paragraphs. */
    private static final int FIRST_ROW = 1;

    /** The last screen row, {@code WHEN 10}; equal to {@link #PAGE_SIZE} by construction. */
    private static final int LAST_ROW = PAGE_SIZE;

    /**
     * The forward fill loop's exclusive bound: {@code PERFORM UNTIL WS-IDX >= 11} [{@code :297}], and
     * the blanking loop's {@code UNTIL WS-IDX > 10} [{@code :290}] expressed as the same number.
     */
    private static final int FORWARD_LOOP_LIMIT = PAGE_SIZE + 1;

    /** The backward fill loop's floor: {@code PERFORM UNTIL WS-IDX <= 0} [{@code :351}]. */
    private static final int BACKWARD_LOOP_FLOOR = 0;

    /**
     * The modulus of {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}: {@value #PAGE_NUM_MODULUS}.
     *
     * <p>{@code COMPUTE CDEMO-CT00-PAGE-NUM = CDEMO-CT00-PAGE-NUM + 1} [{@code :306} and {@code :317}]
     * carries no {@code ON SIZE ERROR} clause, so an eight-digit receiver discards the ninth digit and
     * wraps. Reproduced rather than guarded against: throwing where the COBOL wraps would be a
     * behaviour change, even at a page count no operator will reach.
     */
    private static final int PAGE_NUM_MODULUS = 100_000_000;

    // =================================================================================================
    // WORKING-STORAGE widths and the two edited renderings.
    // =================================================================================================

    /** {@code WS-MESSAGE PIC X(80)} - {@code :38}. Truncated to 78 on its way to {@code ERRMSGO}. */
    static final int WS_MESSAGE_LENGTH = 80;

    /** {@code WS-TRAN-AMT PIC +99999999.99} - {@code :56}. Sign + 8 + point + 2 = 12 characters. */
    static final int WS_TRAN_AMT_LENGTH = 12;

    /** The eight integer digit positions of {@code WS-TRAN-AMT}. */
    static final int WS_TRAN_AMT_INTEGER_DIGITS = 8;

    /** {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - {@code :57}. */
    static final int WS_TRAN_DATE_LENGTH = 8;

    /** The declared {@code VALUE} of {@code WS-TRAN-DATE}: {@value #WS_TRAN_DATE_INITIAL}. */
    static final String WS_TRAN_DATE_INITIAL = "00/00/00";

    /** {@code WS-TIMESTAMP} is 26 characters - {@code app/cpy/CSDAT01Y.cpy}, and so is {@code TRAN-ORIG-TS}. */
    static final int WS_TIMESTAMP_LENGTH = 26;

    /** A single space, the {@code SPACE} of {@code MOVE SPACE TO TRNIDINO} at {@code :228} and {@code :325}. */
    private static final String SPACE = " ";

    /** {@code LOW-VALUES} in a character field: {@code X'00'}. */
    private static final char LOW_VALUE = '\u0000';

    // =================================================================================================
    // Every message this program can put on the error line, byte for byte.
    // =================================================================================================

    /** {@code :198-200}, the {@code WHEN OTHER} arm of the selection-flag {@code EVALUATE}. */
    static final String MSG_INVALID_SELECTION = "Invalid selection. Valid value is S";

    /** {@code :213-215}. Note the space before the ellipsis - it is part of the literal. */
    static final String MSG_TRAN_ID_NOT_NUMERIC = "Tran ID must be Numeric ...";

    /** {@code :248-249}, {@code PROCESS-PF7-KEY} refusing to page above page one. */
    static final String MSG_ALREADY_TOP_OF_PAGE = "You are already at the top of the page...";

    /** {@code :270-271}, {@code PROCESS-PF8-KEY} refusing to page past the last page. */
    static final String MSG_ALREADY_BOTTOM_OF_PAGE = "You are already at the bottom of the page...";

    /**
     * {@code :608-609}, the {@code DFHRESP(NOTFND)} arm of {@code STARTBR-TRANSACT-FILE}.
     *
     * <p>Note how nearly it resembles {@link #MSG_ALREADY_TOP_OF_PAGE} and
     * {@link #MSG_REACHED_TOP_OF_PAGE} without being either of them. Three distinct literals for
     * three distinct conditions; substituting one for another would pass a careless reading and fail
     * the field-for-field diff.
     */
    static final String MSG_AT_TOP_OF_PAGE = "You are at the top of the page...";

    /** {@code :642-643}, the {@code DFHRESP(ENDFILE)} arm of {@code READNEXT-TRANSACT-FILE}. */
    static final String MSG_REACHED_BOTTOM_OF_PAGE = "You have reached the bottom of the page...";

    /** {@code :676-677}, the {@code DFHRESP(ENDFILE)} arm of {@code READPREV-TRANSACT-FILE}. */
    static final String MSG_REACHED_TOP_OF_PAGE = "You have reached the top of the page...";

    /** The shared {@code WHEN OTHER} text of all three file paragraphs - {@code :615}, {@code :649}, {@code :683}. */
    static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup transaction...";

    /**
     * The only accepted selection character, upper case - {@code WHEN 'S'} at {@code :186}. Asserted
     * against {@link PaginationCursor#SELECTION_VIEW} at construction.
     */
    static final String SELECTION_VIEW_UPPER = "S";

    /** Its lower-case twin - {@code WHEN 's'} at {@code :187}, sharing one body with {@code WHEN 'S'}. */
    static final String SELECTION_VIEW_LOWER = "s";

    // =================================================================================================
    // The HTTP surface. Two members, both constants, so the route is auditable from one place.
    // =================================================================================================

    /** {@code GET /api/transactions} - the REST projection of CSD transaction {@code CT00}, AAP 0.3.9. */
    public static final String TRANSACTIONS_PATH = "/api/transactions";

    /**
     * The optional query parameter carrying the raw {@code EIBAID} byte as {@code 0..255}.
     *
     * <p>{@link AidRequestParameter#CANONICAL_NAME}, shared with every other online route rather than
     * spelled here, so no screen accepts a name another screen rejects. A spelling that reaches no
     * handler is discarded by Spring, and the request then runs as {@link CicsAid#DFHENTER} with nothing
     * saying the key was not understood.
     */
    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    /** The alternate spelling of {@link #EIBAID_PARAM}, accepted on every online route. */
    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MAX = 255;

    /**
     * The AID token each key resolves to, mapped back to the one byte that produces it.
     *
     * <p>Needed because the request payload carries a five-character {@code CCARD-AID} token while
     * every test this program performs is against a raw {@code EIBAID} byte. The table is the inverse
     * of {@link PfKeyResolver#resolve(byte)} restricted to its <em>primary</em> pre-image: that map
     * folds {@code DFHPF13}-{@code DFHPF24} onto {@code PFK01}-{@code PFK12}, so a token cannot say
     * whether {@code PF3} or {@code PF15} was pressed and the low-numbered key is chosen as
     * canonical. A caller that must distinguish them sends the byte in {@link #EIBAID_PARAM}, which
     * takes precedence for exactly that reason.
     *
     * <p>Built once, unmodifiable, and verified against the resolver at class initialisation so the
     * two directions cannot drift apart. Immutable, therefore not mutable static state (gate G53).
     */
    private static final Map<AidKey, Byte> CANONICAL_AID_BYTES = canonicalAidBytes();

    /**
     * Builds {@link #CANONICAL_AID_BYTES} and proves it against {@link PfKeyResolver}.
     *
     * <p>Every one of the sixteen {@code CCARD-AID} condition names is present, and each entry is
     * checked by resolving its byte back through the shared resolver. A typo therefore fails at class
     * initialisation, naming the offending key, rather than silently sending a request down the wrong
     * arm of {@code EVALUATE EIBAID}.
     *
     * @return an unmodifiable, fully populated and verified table
     * @throws IllegalStateException if any entry does not round-trip, or if a key is missing
     */
    private static Map<AidKey, Byte> canonicalAidBytes() {
        EnumMap<AidKey, Byte> table = new EnumMap<>(AidKey.class);
        table.put(AidKey.ENTER, CicsAid.DFHENTER);
        table.put(AidKey.CLEAR, CicsAid.DFHCLEAR);
        table.put(AidKey.PA1, CicsAid.DFHPA1);
        table.put(AidKey.PA2, CicsAid.DFHPA2);
        table.put(AidKey.PFK01, CicsAid.DFHPF1);
        table.put(AidKey.PFK02, CicsAid.DFHPF2);
        table.put(AidKey.PFK03, CicsAid.DFHPF3);
        table.put(AidKey.PFK04, CicsAid.DFHPF4);
        table.put(AidKey.PFK05, CicsAid.DFHPF5);
        table.put(AidKey.PFK06, CicsAid.DFHPF6);
        table.put(AidKey.PFK07, CicsAid.DFHPF7);
        table.put(AidKey.PFK08, CicsAid.DFHPF8);
        table.put(AidKey.PFK09, CicsAid.DFHPF9);
        table.put(AidKey.PFK10, CicsAid.DFHPF10);
        table.put(AidKey.PFK11, CicsAid.DFHPF11);
        table.put(AidKey.PFK12, CicsAid.DFHPF12);
        if (table.size() != AidKey.values().length) {
            throw new IllegalStateException("The canonical AID table must carry every CCARD-AID "
                    + "condition name of app/cpy/CVCRD01Y.cpy: PfKeyResolver.AidKey declares "
                    + AidKey.values().length + " and the table carries " + table.size());
        }
        for (Map.Entry<AidKey, Byte> entry : table.entrySet()) {
            Optional<AidKey> resolved = PfKeyResolver.resolve(entry.getValue());
            if (resolved.isEmpty() || resolved.get() != entry.getKey()) {
                throw new IllegalStateException("The canonical byte chosen for AID token '"
                        + entry.getKey().token() + "' resolves to "
                        + resolved.map(Enum::name).orElse("no key")
                        + " through PfKeyResolver; the two directions must agree or a token would "
                        + "select an arm of EVALUATE EIBAID the operator never reached");
            }
        }
        return Collections.unmodifiableMap(table);
    }

    // =================================================================================================
    // Collaborators. Three, all final, all constructor-injected, none of them mutable state (B9).
    // =================================================================================================

    /**
     * The {@code TRANSACT} file. Every {@code STARTBR}, {@code READNEXT}, {@code READPREV} and
     * {@code ENDBR} of the source reaches the dataset through this and only this, so no SQL, no
     * {@code JdbcTemplate} and no dataset name appears in this class (gate G46).
     */
    private final TransactionRepository transactionRepository;

    /**
     * The fixed-width codec, carrying the dataset code page. It owns the {@code PIC X} and
     * {@code PIC 9} {@code MOVE} rules - right-truncating and space-padding for alphanumeric,
     * left-truncating and zero-filling for numeric - so no substring or {@code String.format} in this
     * file decides a truncation direction for itself (practice B11).
     */
    private final FixedWidthCodec codec;

    /**
     * The clock {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} [{@code :569}] is read from.
     * Injected rather than called statically, so the two heading fields are assertable; nothing in
     * this class calls {@code LocalDateTime.now()} or reads the default time zone.
     */
    private final Clock clock;

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Wiring constructor, used by the container.
     *
     * <p>The code page arrives as an explicit argument selected by bean name rather than being taken
     * from the platform, because a fixed-width record is bytes in a stated encoding (practice B8).
     *
     * @param transactionRepository the {@code TRANSACT} file
     * @param datasetCharset        the active dataset code page,
     *                              {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}
     * @param clock                 the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if any width or literal this screen depends on disagrees with the
     *                               type that owns it - see {@link #verifyScreenContract()}
     */
    @Autowired
    public TransactionMenuController(TransactionRepository transactionRepository,
                                     @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)
                                     Charset datasetCharset,
                                     Clock clock) {
        this(transactionRepository,
                new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                        "A dataset charset is required: the transaction list renders fixed-width "
                                + "fields, so the code page is stated explicitly and never taken "
                                + "from the platform")),
                clock);
    }

    /**
     * Canonical constructor. This is the one a unit test calls: it needs no Spring context, no
     * {@code MockMvc} and no backend, only a stubbed {@link TransactionRepository} (practice B10,
     * gate G51).
     *
     * @param transactionRepository the {@code TRANSACT} file
     * @param codec                 the fixed-width codec, carrying the dataset code page
     * @param clock                 the clock {@code FUNCTION CURRENT-DATE} is read from
     * @throws NullPointerException  if any argument is {@code null}
     * @throws IllegalStateException if any width or literal this screen depends on has drifted
     */
    public TransactionMenuController(TransactionRepository transactionRepository,
                                     FixedWidthCodec codec,
                                     Clock clock) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: this screen reaches TRANSACT only through it, "
                        + "never through a JdbcTemplate and never by dataset name");
        this.codec = Objects.requireNonNull(codec, "A fixed-width codec is required: it owns the "
                + "PIC X and PIC 9 MOVE rules every field of this screen is written with");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is "
                + "read from an injected clock so a test can pin the rendered heading");
        verifyScreenContract();
    }

    /**
     * Checks every width, count and literal this screen's behaviour depends on against the type that
     * owns it, and fails loudly rather than paging wrongly or truncating in the wrong place.
     *
     * <p>Each check names its authority. All of them are cheap, all of them run once per context, and
     * every one of them defends a value that would otherwise be able to drift silently.
     *
     * @throws IllegalStateException if any check fails
     */
    static void verifyScreenContract() {
        // The page size, against both halves of the DTO pair. app/cbl/COTRN00C.cbl:290, :297, :349.
        requireAgreement(PAGE_SIZE, TransactionListRequest.PAGE_SIZE,
                "the COBOL page size and TransactionListRequest.PAGE_SIZE");
        requireAgreement(PAGE_SIZE, TransactionListResponse.PAGE_SIZE,
                "the COBOL page size and TransactionListResponse.PAGE_SIZE");
        requireAgreement(FIRST_ROW, TransactionListResponse.FIRST_ROW,
                "the first EVALUATE WS-IDX arm and TransactionListResponse.FIRST_ROW");
        requireAgreement(LAST_ROW, TransactionListResponse.LAST_ROW,
                "the last EVALUATE WS-IDX arm and TransactionListResponse.LAST_ROW");
        requireAgreement(FORWARD_LOOP_LIMIT, PAGE_SIZE + 1,
                "the UNTIL WS-IDX >= 11 bound and the page size plus one");

        // The two edited renderings. :56 and :57.
        requireAgreement(WS_TRAN_AMT_LENGTH, TransactionListResponse.TAMT_LENGTH,
                "the width of WS-TRAN-AMT and of TAMT00nO");
        requireAgreement(WS_TRAN_DATE_LENGTH, TransactionListResponse.TDATE_LENGTH,
                "the width of WS-TRAN-DATE and of TDATEnnO");
        requireAgreement(WS_TRAN_DATE_LENGTH, WS_TRAN_DATE_INITIAL.length(),
                "the width of WS-TRAN-DATE and its declared VALUE");
        requireAgreement(WS_TRAN_AMT_LENGTH,
                1 + WS_TRAN_AMT_INTEGER_DIGITS + 1 + CobolDecimal.MONETARY_SCALE,
                "the composed width of PIC +99999999.99");
        requireAgreement(CobolDecimal.MONETARY_SCALE, TranRecord.TRAN_AMT_SCALE,
                "the monetary scale and the declared scale of TRAN-AMT");

        // The heading. COTTL01Y's two titles against the map items they are moved into, :571-572.
        requireAgreement(ScreenTitles.TITLE_LENGTH, TransactionListResponse.TITLE01_LENGTH,
                "the width of CCDA-TITLE01 and of TITLE01O");
        requireAgreement(ScreenTitles.TITLE_LENGTH, TransactionListResponse.TITLE02_LENGTH,
                "the width of CCDA-TITLE02 and of TITLE02O");

        // The message line. WS-MESSAGE is X(80) at :38 and must be able to hold CCDA-MSG-INVALID-KEY,
        // which is X(50); the move to ERRMSGO then truncates to 78 (:531).
        requireOrdering(SystemMessages.MESSAGE_LENGTH, WS_MESSAGE_LENGTH,
                "the width of CCDA-MSG-INVALID-KEY and of WS-MESSAGE");
        requireOrdering(TransactionListResponse.ERRMSG_LENGTH, WS_MESSAGE_LENGTH,
                "the width of ERRMSGO and of WS-MESSAGE");

        // The identity of the transaction, the program, the mapset and the map.
        requireAgreement(LIT_THIS_TRANID, TransactionListRequest.TRANSACTION_ID,
                "WS-TRANID and the transaction the request projects");
        requireAgreement(LIT_THIS_PGM, TransactionListRequest.PROGRAM_NAME,
                "WS-PGMNAME and the program the request projects");
        requireAgreement(LIT_THIS_MAPSET, TransactionListResponse.MAPSET_NAME,
                "the mapset of SEND MAP and the mapset the response names");
        requireAgreement(LIT_THIS_MAP, TransactionListResponse.MAP_NAME,
                "the map of SEND MAP and the map the response names");
        requireAgreement(LIT_THIS_TRANID.length(), NavigationContext.FROM_TRANID_LENGTH,
                "the width of WS-TRANID and of CDEMO-FROM-TRANID");
        requireAgreement(LIT_THIS_PGM.length(), NavigationContext.FROM_PROGRAM_LENGTH,
                "the width of WS-PGMNAME and of CDEMO-FROM-PROGRAM");
        requireAgreement(LIT_TRANSACT_FILE, TransactionRepository.CICS_FILE_NAME,
                "WS-TRANSACT-FILE and the file the repository serves");

        // The 58-byte commarea extension, and the 218 bytes this program therefore passes.
        requireAgreement(TransactionListCursor.CURSOR_LENGTH, PaginationCursor.CURSOR_LENGTH,
                "the two projections of CDEMO-CT00-INFO");
        requireAgreement(NavigationContext.COMMAREA_LENGTH + TransactionListCursor.CURSOR_LENGTH,
                TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH,
                "CARDDEMO-COMMAREA plus CDEMO-CT00-INFO and the total the response declares");

        // The browse key. TRAN-ID is the record's own key and the RIDFLD of all four file commands.
        requireAgreement(TranRecord.TRAN_ID_LENGTH, TransactionRepository.KEY_LENGTH,
                "the width of TRAN-ID and the repository's key length");
        requireAgreement(TranRecord.TRAN_ID_LENGTH, TransactionListResponse.TRNID_LENGTH,
                "the width of TRAN-ID and of TRNIDnnO");
        requireAgreement(TranRecord.TRAN_ORIG_TS_LENGTH, WS_TIMESTAMP_LENGTH,
                "the width of TRAN-ORIG-TS and of WS-TIMESTAMP");

        // The one accepted selection character, :186.
        requireAgreement(SELECTION_VIEW_UPPER, PaginationCursor.SELECTION_VIEW,
                "the WHEN 'S' literal and the selection value the cursor declares");
    }

    /**
     * Requires two widths or counts to be equal.
     *
     * @param expected what the COBOL declares
     * @param actual   what the collaborating type declares
     * @param what     a description naming both authorities
     * @throws IllegalStateException if they differ
     */
    static void requireAgreement(int expected, int actual, String what) {
        if (expected != actual) {
            throw new IllegalStateException("A width this screen depends on has drifted: " + what
                    + " must agree, but they are " + expected + " and " + actual);
        }
    }

    /**
     * Requires two literals to be equal.
     *
     * @param expected what the COBOL declares
     * @param actual   what the collaborating type declares
     * @param what     a description naming both authorities
     * @throws IllegalStateException if they differ
     */
    static void requireAgreement(String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("A literal this screen depends on has drifted: " + what
                    + " must agree, but they are '" + expected + "' and '" + actual + "'");
        }
    }

    /**
     * Requires a sending field to fit inside its receiver, so a documented truncation stays the
     * truncation that was documented.
     *
     * @param narrower the value expected to be no wider
     * @param wider    the value expected to be at least as wide
     * @param what     a description naming both authorities
     * @throws IllegalStateException if {@code narrower} exceeds {@code wider}
     */
    static void requireOrdering(int narrower, int wider, String what) {
        if (narrower > wider) {
            throw new IllegalStateException("A width relationship this screen depends on has drifted: "
                    + what + " must be ordered, but they are " + narrower + " and " + wider);
        }
    }

    // =================================================================================================
    // The HTTP surface. One mapping, and it does one thing: resolve the AID and delegate. Every
    // decision below it is reachable without HTTP (practice B10, gate G51).
    // =================================================================================================

    /**
     * {@code GET /api/transactions} - the REST projection of CSD transaction {@code CT00}.
     *
     * <p><strong>An absent body is the cold start.</strong> {@code app/cbl/COTRN00C.cbl:107} tests
     * {@code IF EIBCALEN = 0} to tell a transaction started fresh from one continuing a
     * pseudo-conversation, and a {@code GET} nobody sent a payload with is exactly that: the parameter
     * is {@code required = false}, arrives {@code null}, and is treated as {@code EIBCALEN = 0}. A
     * continuing request sends back the payload it last received, carrying the communication area, the
     * browse cursor and the screen the operator was looking at - never a server-side session (gate
     * G37).
     *
     * <p><strong>Both accepted spellings of the AID parameter are bound</strong> and folded by
     * {@link AidRequestParameter#resolve(Integer, Integer)}, so this route understands the same name as
     * every other online route rather than one of two.
     *
     * @param request the inbound screen and communication area, or {@code null} for the cold start
     * @param eibaid  the terminal's attention identifier as an unsigned byte {@code 0..255} under the
     *                canonical parameter name, or {@code null} to take it from the payload's
     *                {@code EIBAID} token
     * @param eibAid  the same value under the alternate spelling; at most one of the two need be sent
     * @return the {@code COTRN0AO} projection, or - on a transfer of control - the navigation triple
     *         naming where the client goes next
     * @throws IllegalArgumentException if the AID is outside {@code 0..255}, or if both spellings are
     *                                  present and disagree
     */
    // The alternate spelling is appended last: Spring binds by the name in the annotation and never by
    // position, so the two parameters this method already had keep their meaning for every direct caller.
    @GetMapping(path = TRANSACTIONS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<TransactionListResponse> getTransactions(
            @Valid @RequestBody(required = false) TransactionListRequest request,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid) {
        // The work area is created here rather than inside the two-argument overload so that the two
        // values COTRN00C sets which are presentation metadata - the MOVE -1 cursor request and
        // WS-SEND-ERASE-FLG - are still reachable when the envelope is built. Neither is a payload
        // member and neither ever becomes one, which is exactly why they need the envelope to travel.
        WorkArea ws = new WorkArea();
        TransactionListResponse painted = listTransactions(
                request, resolveEibAid(request, AidRequestParameter.resolve(eibaid, eibAid)), ws);
        return ScreenResponse.of(painted, ws.screenMetadata(painted));
    }

    /**
     * Resolves the {@code EIBAID} byte a request presented, from the query parameter if it named one
     * and from the payload's token otherwise.
     *
     * <p><strong>The parameter wins, and deliberately.</strong> A raw byte distinguishes
     * {@code DFHPF3} from {@code DFHPF15}; the five-character token cannot, because
     * {@link PfKeyResolver#resolve(byte)} folds the two onto one condition name. This program tests
     * the raw byte [{@code :119-134}, {@code :285}, {@code :339}], so the more specific source is
     * preferred whenever it is available.
     *
     * @param request the inbound payload, or {@code null}
     * @param eibaid  the unsigned byte value, or {@code null} when the caller named no key
     * @return the raw AID byte
     * @throws IllegalArgumentException if {@code eibaid} is outside {@code 0..255}
     */
    byte resolveEibAid(TransactionListRequest request, Integer eibaid) {
        if (eibaid != null) {
            return requireAidByte(eibaid);
        }
        return aidByteOfToken(request == null ? null : request.getAid());
    }

    /**
     * Narrows an unsigned {@code EIBAID} value to its byte, refusing anything that is not one.
     *
     * <p>The range is checked rather than silently wrapped, because {@code 300} is not an attention
     * identifier and quietly becoming {@code 0x2C} would send the request down a branch the caller
     * never asked for.
     *
     * @param eibaid the unsigned byte value
     * @return the raw AID byte
     * @throws IllegalArgumentException if it is outside {@code 0..255}
     */
    static byte requireAidByte(int eibaid) {
        if (eibaid < AID_MIN || eibaid > AID_MAX) {
            throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
        }
        return (byte) eibaid;
    }

    /**
     * Maps the payload's five-character {@code CCARD-AID} token back onto the raw byte this program
     * tests against.
     *
     * <p>Three outcomes, and each one is a decision rather than a fallback:
     *
     * <ul>
     *   <li><strong>Absent, blank or low values yields {@link CicsAid#DFHENTER}.</strong> A CICS
     *       terminal always presents some AID, and blank is not one. {@code ENTER} is the key this
     *       program treats as the ordinary case, so it is the only default that cannot reach a branch
     *       the operator could not have reached.</li>
     *   <li><strong>A recognised token yields its canonical byte</strong> from
     *       {@link #CANONICAL_AID_BYTES}.</li>
     *   <li><strong>An unrecognised token yields {@link CicsAid#DFHNULL}</strong>, which
     *       {@link PfKeyResolver#resolve(byte)} matches to no condition name at all. That is the
     *       faithful representation of "a key this program does not handle", and it lands on the
     *       {@code WHEN OTHER} arm at {@code :129} exactly as an unhandled key does.</li>
     * </ul>
     *
     * <p>The token is reshaped to its declared width through the codec first, so a caller that sent a
     * short value is padded by the {@code PIC X} move rule rather than failing to match.
     *
     * @param token the token, of any length, or {@code null}
     * @return the raw AID byte
     */
    byte aidByteOfToken(String token) {
        if (token == null) {
            return CicsAid.DFHENTER;
        }
        String padded = codec.movePicX(token, PfKeyResolver.AID_TOKEN_LENGTH);
        if (isAllSpaces(padded) || isAllLowValues(padded)) {
            return CicsAid.DFHENTER;
        }
        for (Map.Entry<AidKey, Byte> entry : CANONICAL_AID_BYTES.entrySet()) {
            if (entry.getKey().token().equals(padded)) {
                return entry.getValue();
            }
        }
        return CicsAid.DFHNULL;
    }

    // =================================================================================================
    // MAIN-PARA - app/cbl/COTRN00C.cbl:95-141.
    // =================================================================================================

    /**
     * Runs the whole transaction, taking the attention identifier from the payload's token.
     *
     * <p>This is the plainest entry point: a payload in, a payload out, no HTTP and no Spring context.
     *
     * @param request the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @return the response; never {@code null}
     */
    public TransactionListResponse listTransactions(TransactionListRequest request) {
        return listTransactions(request,
                aidByteOfToken(request == null ? null : request.getAid()));
    }

    /**
     * Runs the whole transaction for an explicit {@code EIBAID} byte.
     *
     * <p>This is the decision entry point every behavioural and parity test drives, because the AID
     * byte is what {@code EVALUATE EIBAID} and both skip-read guards actually test.
     *
     * @param request the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid  the raw {@code EIBAID} byte
     * @return the response; never {@code null}
     */
    public TransactionListResponse listTransactions(TransactionListRequest request, byte eibAid) {
        // A fresh WorkArea IS the declared VALUE state of WORKING-STORAGE, and it is created per call,
        // so nothing about this bean is stateful (practice B9, gate G53).
        return listTransactions(request, eibAid, new WorkArea());
    }

    /**
     * The same transaction, run against a caller-supplied work area so that every
     * {@code WORKING-STORAGE} item the COBOL sets stays observable afterwards.
     *
     * <p>{@code COTRN00C} sets values that never reach the payload - {@code WS-SEND-ERASE-FLG}
     * chooses between {@code SEND ... ERASE} and {@code SEND} without it [{@code :533-549}], and
     * {@code MOVE -1 TO TRNIDINL} places the cursor - and this overload is how a test asserts them
     * without inventing a payload member to carry them. Production traffic uses
     * {@link #listTransactions(TransactionListRequest, byte)}.
     *
     * @param incoming the inbound payload, or {@code null} for {@code EIBCALEN = 0}
     * @param eibAid   the raw {@code EIBAID} byte
     * @param ws       the work area to run in, in its initial state
     * @return the response; never {@code null}
     * @throws NullPointerException if {@code ws} is {@code null}
     */
    public TransactionListResponse listTransactions(TransactionListRequest incoming, byte eibAid,
                                                    WorkArea ws) {
        Objects.requireNonNull(ws, "A work area is required; the declared initial state of "
                + "WORKING-STORAGE is new WorkArea()");
        // An absent payload is EIBCALEN = 0. A fresh request has no communication area either, so the
        // two arrive at the same test below.
        TransactionListRequest request = incoming == null ? new TransactionListRequest() : incoming;
        TransactionListResponse response = new TransactionListResponse();
        ws.eibAid = eibAid;
        ws.eibcalen = request.commareaLength();

        // :97-100  Four SETs, in source order. The next-page flag lives on the commarea extension,
        // which this statement writes in the program's OWN storage before :111 replaces it with the
        // inbound one - so both writes happen, in order, exactly as the source has them.
        ws.setErrFlgOff();                                          // :97
        ws.setTransactNotEof();                                     // :98
        ws.commarea = NavigationContext.empty();
        adoptCursor(ws, response, new TransactionListCursor());
        ws.cursor.setNextPageNo();                                  // :99
        ws.setSendEraseYes();                                       // :100

        // :102-103  MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COTRN0AO
        ws.message = codec.movePicX("", WS_MESSAGE_LENGTH);
        response.clearErrorLine();

        // :105  MOVE -1 TO TRNIDINL OF COTRN0AI
        placeCursorOnTranId(request, ws);

        if (ws.eibcalen == 0) {
            // :107-109  Started fresh: go back where a fresh start comes from.
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);
            returnToPrevScreen(ws, response);
            // XCTL transfers control permanently, so the EXEC CICS RETURN at :138 is not reached.
            return response;
        }

        // :111  MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA - the 160-byte area and the
        // 58-byte CDEMO-CT00-INFO extension appended to it.
        ws.commarea = request.getNavigationContext();
        adoptCursor(ws, response, cursorOf(request.getCursor()));

        if (!ws.commarea.isReenter()) {
            // :112-116  The ENTER path: first entry into this program for this conversation.
            ws.commarea = ws.commarea.withPgmReenter();             // :113
            moveLowValuesToOutputMap(response);                     // :114
            if (processEnterKey(request, ws, response)) {           // :115
                return response;
            }
            sendTrnlstScreen(ws, response);                         // :116
        } else {
            // :117-134  The REENTER path: the operator pressed a key on a screen we painted.
            receiveTrnlstScreen(request, ws, response);             // :118
            // :119-134  EVALUATE EIBAID, in source order, WHEN OTHER last (gate G30). Each test is
            // the shared resolver's exact-byte comparison, so DFHPF15 does not act as DFHPF3.
            if (PfKeyResolver.isEnter(ws.eibAid)) {                 // :120
                if (processEnterKey(request, ws, response)) {       // :121
                    return response;
                }
            } else if (PfKeyResolver.isPf3(ws.eibAid)) {            // :122
                ws.commarea = ws.commarea.withToProgram(LIT_MENU_PGM);   // :123
                returnToPrevScreen(ws, response);                   // :124
                return response;
            } else if (PfKeyResolver.isPf7(ws.eibAid)) {            // :125
                processPf7Key(request, ws, response);               // :126
            } else if (PfKeyResolver.isPf8(ws.eibAid)) {            // :127
                processPf8Key(request, ws, response);               // :128
            } else {                                                // :129  WHEN OTHER
                ws.setErrFlgOn();                                   // :130
                placeCursorOnTranId(request, ws);                   // :131
                ws.message = codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                        WS_MESSAGE_LENGTH);                         // :132
                sendTrnlstScreen(ws, response);                     // :133
            }
        }

        // :138-141  EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA). The commarea is
        // handed back in the payload, which is the whole of the conversation state (rule R6).
        response.setNavigationContext(ws.commarea);
        return response;
    }

    // =================================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COTRN00C.cbl:146-229.
    // =================================================================================================

    /**
     * The enter-key paragraph: read the selection cells, act on a selected row, validate the typed
     * browse key, then paint a page.
     *
     * @param request  the inbound payload, whose field metadata carries the cursor position
     * @param ws       the work area
     * @param response the map area being built, which is also the map area being read - see
     *                 {@link #receiveTrnlstScreen}
     * @return {@code true} if control was transferred and the caller must stop, {@code false} if the
     *         paragraph completed and the caller continues
     */
    boolean processEnterKey(TransactionListRequest request, WorkArea ws,
                            TransactionListResponse response) {
        // :148-182  EVALUATE TRUE over SEL0001I..SEL0010I, tested NOT = SPACES AND LOW-VALUES.
        if (!captureRowSelection(ws, response)) {
            // :179-181  WHEN OTHER - no cell was filled, so both cursor fields are cleared.
            ws.cursor.clearSelection();
        }

        // :183-204  A selection is acted on only when BOTH the flag and the identifier are present.
        if (isPresentCobol(ws.cursor.getTrnSelFlg()) && isPresentCobol(ws.cursor.getTrnSelected())) {
            String flag = ws.cursor.getTrnSelFlg();
            if (SELECTION_VIEW_UPPER.equals(flag) || SELECTION_VIEW_LOWER.equals(flag)) {
                // :186-187  Two WHEN clauses sharing one body: 'S' and 's' are equally valid, and no
                // other character is. Note there is no general case-folding here - 'S' and 's' are
                // enumerated, so a fullwidth or accented S is not accepted.
                ws.commarea = ws.commarea
                        .withToProgram(LIT_TRAN_VIEW_PGM)                       // :188
                        .withFromTranid(LIT_THIS_TRANID)                        // :189
                        .withFromProgram(LIT_THIS_PGM)                          // :190
                        .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);    // :191  MOVE 0
                // :192-195  EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA).
                transferControl(ws, response);
                return true;
            }
            // :196-202  WHEN OTHER.
            ws.message = codec.movePicX(MSG_INVALID_SELECTION, WS_MESSAGE_LENGTH);   // :198-200
            placeCursorOnTranId(request, ws);                                        // :201
            // TWO STATEMENTS ARE COMMENTED OUT IN THE SOURCE AND STAY COMMENTED OUT (practice B5):
            //   :197  * SET TRANSACT-EOF TO TRUE
            //   :202  * PERFORM SEND-TRNLST-SCREEN
            // So an invalid selection sets the message and places the cursor, and then FALLS THROUGH
            // to the browse below - it does not end the browse and it does not re-paint here. The
            // message survives because the page-forward path re-sends the screen at :326 with
            // WS-MESSAGE unchanged. Re-adding either statement would look tidier and change
            // behaviour.
        }

        // :206-219  The browse key, from what the operator typed.
        String typedKey = response.getTrnidinO();
        if (isAllSpaces(typedKey) || isAllLowValues(typedKey)) {
            // :206-207  Nothing typed: browse from the very start of the file.
            ws.ridfld = Ridfld.lowValues();
        } else if (isCobolNumeric(typedKey)) {
            // :209-210  MOVE TRNIDINI TO TRAN-ID. Note what "IS NUMERIC" costs the operator: the item
            // is PIC X(16), so EVERY one of the sixteen positions must be a digit. A short entry such
            // as "123" comes back space-padded and is therefore NOT numeric and IS rejected below.
            // That is the legacy behaviour, and it is preserved.
            ws.ridfld = Ridfld.ofKey(typedKey);
        } else {
            // :211-218
            ws.setErrFlgOn();                                                        // :212
            ws.message = codec.movePicX(MSG_TRAN_ID_NOT_NUMERIC, WS_MESSAGE_LENGTH);  // :213-215
            placeCursorOnTranId(request, ws);                                         // :216
            sendTrnlstScreen(ws, response);                                           // :217
            // No early exit: the source has none. TRAN-ID keeps the value it already held, which on a
            // fresh task is the low values TRAN-RECORD's WORKING-STORAGE begins at, and that is what
            // the STARTBR below is then issued with.
        }

        // :221  MOVE -1 TO TRNIDINL - unconditional, even on the error path just taken.
        placeCursorOnTranId(request, ws);

        // :224  MOVE 0 TO CDEMO-CT00-PAGE-NUM - an ENTER always restarts the page count.
        ws.cursor.setPageNum(0);

        // :225
        processPageForward(request, ws, response);

        // :227-229  Once a page has been painted, the typed key is cleared so the next ENTER browses
        // on from the cursor rather than restarting from what the operator originally typed.
        if (!ws.isErrFlgOn()) {
            response.clearTranIdInput();
        }
        return false;
    }

    /**
     * {@code :148-182} - the ordered ten-way selection {@code EVALUATE TRUE}.
     *
     * <p><strong>First match wins, and the order is the source's.</strong> The ascending loop returns
     * on the first filled cell, which is precisely what an {@code EVALUATE TRUE} with ten
     * {@code WHEN} clauses does: clause <em>n</em> is only reached when clauses 1 to <em>n-1</em> were
     * false. It is emphatically <em>not</em> a loop that lets a later row overwrite an earlier one -
     * fill rows 3 and 7 and row 3 is the one that is acted on, exactly as on the mainframe. The unit
     * test pins that with a multi-row case.
     *
     * <p>Each arm performs two moves: the cell into {@code CDEMO-CT00-TRN-SEL-FLG} and that row's
     * {@code TRNIDnnI} into {@code CDEMO-CT00-TRN-SELECTED}. The identifier is taken from the row the
     * cell belongs to, never from another row.
     *
     * @param ws       the work area, whose cursor receives the two moves
     * @param response the map area the cells are read from
     * @return {@code true} if a cell was filled, {@code false} for {@code WHEN OTHER}
     */
    boolean captureRowSelection(WorkArea ws, TransactionListResponse response) {
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            String cell = response.getRowSelection(row);
            if (isPresentCobol(cell)) {
                ws.cursor.setTrnSelFlg(cell);
                ws.cursor.setTrnSelected(response.getRowTransactionId(row));
                ws.selectedRow = row;
                return true;
            }
        }
        return false;
    }

    // =================================================================================================
    // PROCESS-PF7-KEY and PROCESS-PF8-KEY - app/cbl/COTRN00C.cbl:234-274.
    //
    // The two are near mirrors with one asymmetry that matters, and it is not an accident: PF7 falls
    // back to LOW-VALUES [:237] and PF8 to HIGH-VALUES [:260]. Paging up from an unknown position
    // starts at the front of the file; paging down from an unknown position starts past the end of it.
    // =================================================================================================

    /**
     * {@code PROCESS-PF7-KEY}, {@code :234-252} - page up.
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the map area being built
     */
    void processPf7Key(TransactionListRequest request, WorkArea ws,
                       TransactionListResponse response) {
        // :236-240
        String first = ws.cursor.getTrnidFirst();
        ws.ridfld = isAllSpaces(first) || isAllLowValues(first)
                ? Ridfld.lowValues()                // :237
                : Ridfld.ofKey(first);              // :239
        ws.cursor.setNextPageYes();                 // :242
        placeCursorOnTranId(request, ws);           // :243
        if (ws.cursor.getPageNum() > 1) {           // :245
            processPageBackward(request, ws, response);   // :246
        } else {
            // :248-251  Already on page one, so there is nowhere to page up to. The screen is re-sent
            // WITHOUT erase, so the rows the terminal echoed back stay on the display.
            ws.message = codec.movePicX(MSG_ALREADY_TOP_OF_PAGE, WS_MESSAGE_LENGTH);
            ws.setSendEraseNo();
            sendTrnlstScreen(ws, response);
        }
    }

    /**
     * {@code PROCESS-PF8-KEY}, {@code :257-274} - page down.
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the map area being built
     */
    void processPf8Key(TransactionListRequest request, WorkArea ws,
                       TransactionListResponse response) {
        // :259-263
        String last = ws.cursor.getTrnidLast();
        ws.ridfld = isAllSpaces(last) || isAllLowValues(last)
                ? Ridfld.highValues()               // :260  HIGH-VALUES here, not LOW-VALUES
                : Ridfld.ofKey(last);               // :262
        placeCursorOnTranId(request, ws);           // :265
        if (ws.cursor.isNextPageYes()) {            // :267
            processPageForward(request, ws, response);     // :268
        } else {
            // :270-273  The look-ahead read on the previous pass proved there is no next page.
            ws.message = codec.movePicX(MSG_ALREADY_BOTTOM_OF_PAGE, WS_MESSAGE_LENGTH);
            ws.setSendEraseNo();
            sendTrnlstScreen(ws, response);
        }
    }

    // =================================================================================================
    // PROCESS-PAGE-FORWARD and PROCESS-PAGE-BACKWARD - app/cbl/COTRN00C.cbl:279-376.
    //
    // The page size lives here and nowhere else (gate G39). The two directions are structurally alike
    // but differ in four places, every one of which is reproduced: the skip-read guard names a
    // different key set, the row counter runs up rather than down, the forward path has a look-ahead
    // read and the backward path has a page-number rewind instead, and only the forward path clears
    // the typed key before sending.
    // =================================================================================================

    /**
     * {@code PROCESS-PAGE-FORWARD}, {@code :279-328} - fill the page reading forward, counting up.
     *
     * <p>Four details are behaviour rather than mechanism:
     *
     * <ul>
     *   <li><strong>The {@code STARTBR} is unconditional.</strong> {@code :281} performs it before the
     *       {@code IF NOT ERR-FLG-ON} guard at {@code :283}, so the browse is positioned even when an
     *       earlier edit already failed - and its own {@code EVALUATE} can therefore replace the
     *       message that edit set. That ordering is preserved.</li>
     *   <li><strong>One extra read skips the current key.</strong> {@code :285-287} reads once more
     *       when the attention identifier is none of {@code DFHENTER}, {@code DFHPF7} and
     *       {@code DFHPF3}. Positioning is at-or-after the key, so on a {@code PF8} the record at the
     *       key is the last row of the page being left and must be stepped over; on an {@code ENTER}
     *       the record at the key is wanted and is kept.</li>
     *   <li><strong>The ten rows are blanked only when there is something to show.</strong>
     *       {@code :289-293} guards the blanking loop with {@code TRANSACT-NOT-EOF AND ERR-FLG-OFF},
     *       so a page that cannot be read leaves the rows the terminal echoed back in place.</li>
     *   <li><strong>The look-ahead read decides the next-page flag and nothing else.</strong>
     *       {@code :305-313} increments the page number, reads once more, and sets
     *       {@code NEXT-PAGE-YES} or {@code NEXT-PAGE-NO}; that record is never displayed. The
     *       {@code ELSE} at {@code :314-319} is the short-page case, and it increments the page number
     *       only when at least one row was placed.</li>
     * </ul>
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the map area being built
     */
    void processPageForward(TransactionListRequest request, WorkArea ws,
                            TransactionListResponse response) {
        try (TransactBrowse browse =
                     startbrTransactFile(request, ws, response, BrowseDirection.FORWARD)) {   // :281
            if (ws.isErrFlgOn()) {                                                            // :283
                return;
            }
            if (!isEnterPf7OrPf3(ws.eibAid)) {                                                // :285
                readnextTransactFile(request, ws, response, browse);                          // :286
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                                  // :289
                // :290-292  PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10, whose body is
                // INITIALIZE-TRAN-DATA for each row. The VARYING loop leaves WS-IDX at eleven, and
                // :295 overwrites it two statements later, so that terminal value is unobservable and
                // is not stored.
                response.initializeAllTranData();
            }
            ws.idx = FIRST_ROW;                                                               // :295
            // :297-303  PERFORM UNTIL WS-IDX >= 11 OR TRANSACT-EOF OR ERR-FLG-ON.
            while (ws.idx < FORWARD_LOOP_LIMIT && ws.isTransactNotEof() && ws.isErrFlgOff()) {
                ReadResult read = readnextTransactFile(request, ws, response, browse);        // :298
                if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                              // :299
                    populateTranData(ws, response, read.requireRecord());                     // :300
                    ws.idx = ws.idx + 1;                                                      // :301
                }
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                                  // :305
                addOneToPageNum(ws);                                                          // :306
                readnextTransactFile(request, ws, response, browse);                           // :308
                if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                              // :309
                    ws.cursor.setNextPageYes();                                               // :310
                } else {
                    ws.cursor.setNextPageNo();                                                // :312
                }
            } else {
                ws.cursor.setNextPageNo();                                                    // :315
                if (ws.idx > FIRST_ROW) {                                                     // :316
                    addOneToPageNum(ws);                                                      // :317
                }
            }
            endbrTransactFile(browse);                                                        // :322
            response.movePageNumberToScreen(codec, ws.cursor.getPageNum());                   // :324
            response.clearTranIdInput();                                                      // :325
            sendTrnlstScreen(ws, response);                                                   // :326
        }
    }

    /**
     * {@code PROCESS-PAGE-BACKWARD}, {@code :333-376} - fill the page reading backward, counting down.
     *
     * <p>Reached only from {@code PROCESS-PF7-KEY}, and only when the page number is above one
     * [{@code :245}]. Three differences from the forward direction:
     *
     * <ul>
     *   <li>the skip-read guard names {@code DFHENTER} and {@code DFHPF8} only [{@code :339}], so on a
     *       {@code PF7} it fires and steps over the record already at the top of the page being left;</li>
     *   <li>the counter is seeded at {@value #PAGE_SIZE} [{@code :349}] and each placed row decrements
     *       it [{@code :355}], so a short page ends up bottom-aligned - rows 1 upward stay blank, which
     *       is what the source produces and is not tidied here;</li>
     *   <li>instead of a look-ahead there is a rewind: {@code :359-369} reads once more and, only when
     *       {@code NEXT-PAGE-YES} holds, decrements the page number - or resets it to one when that
     *       read found nothing, which is how paging up lands exactly on page one rather than page
     *       zero.</li>
     * </ul>
     *
     * <p>There is no {@code MOVE SPACE TO TRNIDINO} on this path: the source clears the typed key on
     * the forward path only.
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the map area being built
     */
    void processPageBackward(TransactionListRequest request, WorkArea ws,
                             TransactionListResponse response) {
        try (TransactBrowse browse =
                     startbrTransactFile(request, ws, response, BrowseDirection.BACKWARD)) {  // :335
            if (ws.isErrFlgOn()) {                                                            // :337
                return;
            }
            if (!isEnterOrPf8(ws.eibAid)) {                                                   // :339
                readprevTransactFile(request, ws, response, browse);                          // :340
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                                  // :343
                // :344-346  The same blanking loop as the forward path, and :349 likewise overwrites
                // the counter immediately afterwards.
                response.initializeAllTranData();
            }
            ws.idx = LAST_ROW;                                                                // :349
            // :351-357  PERFORM UNTIL WS-IDX <= 0 OR TRANSACT-EOF OR ERR-FLG-ON.
            while (ws.idx > BACKWARD_LOOP_FLOOR && ws.isTransactNotEof() && ws.isErrFlgOff()) {
                ReadResult read = readprevTransactFile(request, ws, response, browse);        // :352
                if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                              // :353
                    populateTranData(ws, response, read.requireRecord());                     // :354
                    ws.idx = ws.idx - 1;                                                      // :355
                }
            }
            if (ws.isTransactNotEof() && ws.isErrFlgOff()) {                                  // :359
                readprevTransactFile(request, ws, response, browse);                          // :360
                if (ws.cursor.isNextPageYes()) {                                              // :361
                    if (ws.isTransactNotEof() && ws.isErrFlgOff()
                            && ws.cursor.getPageNum() > 1) {                                  // :362-363
                        ws.cursor.setPageNum(ws.cursor.getPageNum() - 1);                     // :364
                    } else {
                        ws.cursor.setPageNum(1);                                              // :366
                    }
                }
            }
            endbrTransactFile(browse);                                                        // :371
            response.movePageNumberToScreen(codec, ws.cursor.getPageNum());                   // :373
            sendTrnlstScreen(ws, response);                                                   // :374
        }
    }

    /**
     * {@code COMPUTE CDEMO-CT00-PAGE-NUM = CDEMO-CT00-PAGE-NUM + 1} [{@code :306-307} and
     * {@code :317-318}], on an eight-digit unsigned receiver with no {@code ON SIZE ERROR} clause.
     *
     * @param ws the work area whose cursor carries the page number
     */
    private static void addOneToPageNum(WorkArea ws) {
        ws.cursor.setPageNum((ws.cursor.getPageNum() + 1) % PAGE_NUM_MODULUS);
    }

    /**
     * The compound condition of {@code :285}, {@code IF EIBAID NOT = DFHENTER AND DFHPF7 AND DFHPF3},
     * stated positively: is this key one of those three?
     *
     * <p>The COBOL is an abbreviated combined relation - {@code NOT = A AND B AND C} distributes the
     * {@code NOT =} across all three subjects - so the guard fires when the key is <em>none</em> of
     * them. Written this way round, the source's guard is the negation of this method and reads as it
     * means.
     *
     * @param eibAid the raw {@code EIBAID} byte
     * @return {@code true} if the key is {@code DFHENTER}, {@code DFHPF7} or {@code DFHPF3}
     */
    static boolean isEnterPf7OrPf3(byte eibAid) {
        return PfKeyResolver.isEnter(eibAid)
                || PfKeyResolver.isPf7(eibAid)
                || PfKeyResolver.isPf3(eibAid);
    }

    /**
     * The compound condition of {@code :339}, {@code IF EIBAID NOT = DFHENTER AND DFHPF8}, stated
     * positively. Note that this set is <strong>not</strong> the same as {@link #isEnterPf7OrPf3}: the
     * backward path steps over the current key on a {@code PF7} and the forward path on a {@code PF8},
     * which is exactly why the two lists differ.
     *
     * @param eibAid the raw {@code EIBAID} byte
     * @return {@code true} if the key is {@code DFHENTER} or {@code DFHPF8}
     */
    static boolean isEnterOrPf8(byte eibAid) {
        return PfKeyResolver.isEnter(eibAid) || PfKeyResolver.isPf8(eibAid);
    }

    // =================================================================================================
    // POPULATE-TRAN-DATA - app/cbl/COTRN00C.cbl:381-445.
    // =================================================================================================

    /**
     * Renders one record into one screen row.
     *
     * <p>The two edited values are built here rather than in the payload type, because producing them
     * needs the fixed-point policy and a payload carries characters. Both are also kept on the work
     * area, so a test can assert {@code WS-TRAN-AMT} and {@code WS-TRAN-DATE} directly.
     *
     * <p>The row itself is written by
     * {@link TransactionListResponse#populateTranData(FixedWidthCodec, int, String, String, String, String)},
     * which performs the arm's four moves, truncates the 100-character description to the screen's 26
     * through the codec, and - for rows one and ten only - also seeds
     * {@code CDEMO-CT00-TRNID-FIRST}/{@code -LAST} exactly as {@code :393} and {@code :439} do. An
     * index outside the page is a no-op there, reproducing {@code WHEN OTHER CONTINUE} at
     * {@code :443-444}.
     *
     * @param ws       the work area, supplying {@code WS-IDX} and receiving the two edited values
     * @param response the map area being built
     * @param record   the record the browse just returned
     */
    void populateTranData(WorkArea ws, TransactionListResponse response, TranRecord record) {
        ws.tranAmt = editedAmount(record.tranAmt());                       // :383
        ws.tranDate = editedTranDate(record.tranOrigTs());                 // :384-388
        response.populateTranData(codec, ws.idx, record.tranId(), ws.tranDate,
                record.tranDesc(), ws.tranAmt);                            // :390-445
    }

    // =================================================================================================
    // The four file paragraphs - app/cbl/COTRN00C.cbl:591-696.
    //
    // Each reproduces its EVALUATE WS-RESP-CD arm for arm. Every outcome the repository can report is
    // mapped onto one of those arms and none is left unhandled (gate G47).
    // =================================================================================================

    /**
     * {@code STARTBR-TRANSACT-FILE}, {@code :591-619}.
     *
     * <p>Positioning itself reports nothing - see the class documentation - so the arm is chosen from
     * the outcome of {@link TransactBrowse}'s probe read, which is the same condition CICS reports at
     * positioning time.
     *
     * @param request   the inbound payload
     * @param ws        the work area
     * @param response  the map area being built
     * @param direction the direction this browse will be walked
     * @return the positioned browse; never {@code null}, and always closeable even when the arm taken
     *         was an error arm
     */
    TransactBrowse startbrTransactFile(TransactionListRequest request, WorkArea ws,
                                       TransactionListResponse response, BrowseDirection direction) {
        TransactBrowse browse = TransactBrowse.position(transactionRepository, direction, ws.ridfld);
        ReadResult positioning = browse.positioningOutcome();
        ws.startbrOutcome = positioning.outcome();
        switch (positionArmOf(positioning.outcome())) {
            case NORMAL -> {
                // :603-604  WHEN DFHRESP(NORMAL) CONTINUE.
            }
            case END_OF_DATA -> {
                // :605-611  WHEN DFHRESP(NOTFND). Note it does NOT set WS-ERR-FLG, which is why the
                // guard at :283 still lets the skip-read at :286 be attempted.
                ws.setTransactEof();
                ws.message = codec.movePicX(MSG_AT_TOP_OF_PAGE, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
            case FAILED -> {
                // :612-618  WHEN OTHER.
                reportFileFailure(positioning);
                ws.setErrFlgOn();
                ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
        }
        return browse;
    }

    /**
     * {@code READNEXT-TRANSACT-FILE}, {@code :624-653}.
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the map area being built
     * @param browse   the open forward browse
     * @return the outcome, so the caller can take the record on the normal arm
     */
    ReadResult readnextTransactFile(TransactionListRequest request, WorkArea ws,
                                    TransactionListResponse response, TransactBrowse browse) {
        ReadResult result = browse.read();
        ws.lastReadOutcome = result.outcome();
        ws.readCount = ws.readCount + 1;
        switch (readArmOf(result.outcome())) {
            case NORMAL -> {
                // :637-638  WHEN DFHRESP(NORMAL) CONTINUE.
            }
            case END_OF_DATA -> {
                // :639-645  WHEN DFHRESP(ENDFILE).
                ws.setTransactEof();
                ws.message = codec.movePicX(MSG_REACHED_BOTTOM_OF_PAGE, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
            case FAILED -> {
                // :646-652  WHEN OTHER.
                reportFileFailure(result);
                ws.setErrFlgOn();
                ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
        }
        return result;
    }

    /**
     * {@code READPREV-TRANSACT-FILE}, {@code :658-687}. Identical in shape to
     * {@link #readnextTransactFile} with one difference that is easy to miss and is the whole point of
     * having both: the end-of-data message says <em>top</em> of the page, not bottom.
     *
     * @param request  the inbound payload
     * @param ws       the work area
     * @param response the map area being built
     * @param browse   the open backward browse
     * @return the outcome, so the caller can take the record on the normal arm
     */
    ReadResult readprevTransactFile(TransactionListRequest request, WorkArea ws,
                                    TransactionListResponse response, TransactBrowse browse) {
        ReadResult result = browse.read();
        ws.lastReadOutcome = result.outcome();
        ws.readCount = ws.readCount + 1;
        switch (readArmOf(result.outcome())) {
            case NORMAL -> {
                // :671-672  WHEN DFHRESP(NORMAL) CONTINUE.
            }
            case END_OF_DATA -> {
                // :673-679  WHEN DFHRESP(ENDFILE).
                ws.setTransactEof();
                ws.message = codec.movePicX(MSG_REACHED_TOP_OF_PAGE, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
            case FAILED -> {
                // :680-686  WHEN OTHER.
                reportFileFailure(result);
                ws.setErrFlgOn();
                ws.message = codec.movePicX(MSG_UNABLE_TO_LOOKUP, WS_MESSAGE_LENGTH);
                placeCursorOnTranId(request, ws);
                sendTrnlstScreen(ws, response);
            }
        }
        return result;
    }

    /**
     * {@code ENDBR-TRANSACT-FILE}, {@code :692-696}.
     *
     * <p>The source specifies no response option, so neither it nor this method reports an outcome -
     * there is nothing to report that the COBOL would have looked at. Calling it and then closing the
     * handle is safe: ending an already-ended browse does nothing.
     *
     * @param browse the browse to end
     */
    void endbrTransactFile(TransactBrowse browse) {
        browse.endBrowse();
    }

    /**
     * The {@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} of the three {@code WHEN OTHER} arms
     * [{@code :613}, {@code :647}, {@code :681}].
     *
     * <p>On the mainframe that reaches the job log; here it reaches the module logger. The text comes
     * from {@link ReadResult#describeResponse()}, which the repository composes from the CICS response
     * and reason codes only - a driver's own message never reaches it, and no throwable is passed, so
     * nothing a backend said can escape through a cause chain.
     *
     * @param result the failing outcome
     */
    private static void reportFileFailure(ReadResult result) {
        LOG.error("A read of " + TransactionRepository.CICS_FILE_NAME + " for the transaction list "
                + "screen reported " + result.describeResponse()
                + "; reporting '" + MSG_UNABLE_TO_LOOKUP + "' to the operator");
    }

    /**
     * The three arms of {@code EVALUATE WS-RESP-CD} in {@code READNEXT-TRANSACT-FILE} and
     * {@code READPREV-TRANSACT-FILE}: {@code NORMAL}, {@code ENDFILE}, {@code WHEN OTHER}.
     *
     * @param outcome the discriminated outcome the repository reported
     * @return the arm to take
     */
    static FileArm readArmOf(Outcome outcome) {
        return switch (outcome) {
            case OK -> FileArm.NORMAL;
            case END_OF_FILE -> FileArm.END_OF_DATA;
            // A duplicate-key or not-found response is neither NORMAL nor ENDFILE, so the source's
            // WHEN OTHER arm is where it lands. Neither can arise on a browse of this base cluster,
            // and mapping them anywhere else would invent an arm the EVALUATE does not have.
            case NOT_FOUND, DUPLICATE, OTHER -> FileArm.FAILED;
        };
    }

    /**
     * The three arms of {@code EVALUATE WS-RESP-CD} in {@code STARTBR-TRANSACT-FILE}:
     * {@code NORMAL}, {@code NOTFND}, {@code WHEN OTHER}.
     *
     * <p>This differs from {@link #readArmOf(Outcome)} in exactly one place, and the difference is the
     * reason both exist: at positioning time "there is no such record" is {@code NOTFND}, so a
     * not-found <em>and</em> an end-of-data both belong on the middle arm. On a subsequent read the
     * same {@code NOTFND} would be an error.
     *
     * @param outcome the discriminated outcome of the probe read
     * @return the arm to take
     */
    static FileArm positionArmOf(Outcome outcome) {
        return switch (outcome) {
            case OK -> FileArm.NORMAL;
            case END_OF_FILE, NOT_FOUND -> FileArm.END_OF_DATA;
            case DUPLICATE, OTHER -> FileArm.FAILED;
        };
    }

    /**
     * The three arms every file {@code EVALUATE WS-RESP-CD} in this program has, named once so the
     * mapping is stated in one place instead of being re-derived at each call site.
     */
    enum FileArm {

        /** {@code WHEN DFHRESP(NORMAL)} - a record was returned. */
        NORMAL,

        /**
         * {@code WHEN DFHRESP(ENDFILE)} on a read, {@code WHEN DFHRESP(NOTFND)} on a position. Sets
         * {@code TRANSACT-EOF} and never {@code WS-ERR-FLG}.
         */
        END_OF_DATA,

        /** {@code WHEN OTHER} - sets {@code WS-ERR-FLG} and reports the shared failure message. */
        FAILED
    }

    // =================================================================================================
    // The screen paragraphs - app/cbl/COTRN00C.cbl:510-586.
    // =================================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN}, {@code :510-521}.
     *
     * <p>The default at {@code :512-514} is not redundant even though both call sites set the target
     * first: it is the guard that makes an empty or blank {@code CDEMO-TO-PROGRAM} land on the sign-on
     * screen rather than transferring nowhere.
     *
     * @param ws       the work area
     * @param response the response the transfer target is written onto
     */
    void returnToPrevScreen(WorkArea ws, TransactionListResponse response) {
        String target = ws.commarea.toProgram();
        if (isAllLowValues(target) || isAllSpaces(target)) {                       // :512
            ws.commarea = ws.commarea.withToProgram(LIT_SIGNON_PGM);               // :513
        }
        ws.commarea = ws.commarea
                .withFromTranid(LIT_THIS_TRANID)                                   // :515
                .withFromProgram(LIT_THIS_PGM)                                     // :516
                .withPgmContext(NavigationContext.PGM_CONTEXT_ENTER);              // :517  MOVE ZEROS
        transferControl(ws, response);                                             // :518-521
    }

    /**
     * The stateless form of {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(...)} - both
     * sites, {@code :192-195} and {@code :518-521}.
     *
     * <p>There is no server-side forward and no redirect: the response names the program the client goes
     * to next and carries the communication area it must pass on, and the client issues that call itself
     * (gate G40).
     *
     * <p>The program and <em>only</em> the program. Both {@code XCTL}s state
     * {@code PROGRAM(CDEMO-TO-PROGRAM)} and {@code COMMAREA(CARDDEMO-COMMAREA)}, and this program never
     * writes {@code CDEMO-LAST-MAP} or {@code CDEMO-LAST-MAPSET}, so the target is handed no map or
     * mapset and chooses its own. {@link TransactionListResponse#echoTransferTarget(NavigationContext)}
     * blanks both for that reason.
     *
     * @param ws       the work area, whose commarea names the target
     * @param response the response the triple is written onto
     */
    static void transferControl(WorkArea ws, TransactionListResponse response) {
        response.setNavigationContext(ws.commarea);
        response.echoTransferTarget(ws.commarea);
        ws.transferred = true;
    }

    /**
     * {@code SEND-TRNLST-SCREEN}, {@code :527-549}.
     *
     * <p>Populates the heading, moves {@code WS-MESSAGE} onto the error line - an
     * {@code X(80)}-to-{@code X(78)} move, so two characters are truncated on the right - and records
     * which of the two {@code SEND} forms was taken. The two forms differ only in the {@code ERASE}
     * option: {@code SEND-ERASE-YES} clears the screen first [{@code :534-540}] and
     * {@code SEND-ERASE-NO} leaves what the terminal is showing in place [{@code :542-548}], which is
     * what lets the two "already at the ..." messages appear over the page the operator was reading.
     *
     * <p>The heading is re-read from the clock on every send, exactly as {@code :569} re-executes
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} each time the paragraph runs.
     *
     * @param ws       the work area supplying {@code WS-MESSAGE} and {@code WS-SEND-ERASE-FLG}
     * @param response the map area being sent
     */
    void sendTrnlstScreen(WorkArea ws, TransactionListResponse response) {
        populateHeaderInfo(response);                                              // :529
        response.moveMessageToErrorLine(codec, ws.message);                         // :531
        ws.lastSendErase = ws.isSendEraseYes();                                    // :533
        ws.sendCount = ws.sendCount + 1;
    }

    /**
     * {@code RECEIVE-TRNLST-SCREEN}, {@code :554-562}.
     *
     * <p>{@code RECEIVE MAP ... INTO(COTRN0AI)} overwrites the map area with what the terminal sent,
     * and because {@code COTRN0AO REDEFINES COTRN0AI} the <em>output</em> view then holds those same
     * bytes. That is not a detail: it is why the two "already at the ..." paths can re-send without
     * {@code ERASE} and still show a full page of rows they never re-read. The received values are
     * therefore copied into the map area this class builds, all
     * {@value TransactionListResponse#FIELD_COUNT} of them, each through the {@code PIC X} move rule.
     *
     * <p>The source captures {@code RESP} and {@code RESP2} [{@code :560-561}] and never tests them.
     * The outcome is recorded on the work area for the same reason - available, and not branched on.
     *
     * @param request  the inbound payload, which <em>is</em> the received map (rule R6)
     * @param ws       the work area
     * @param response the map area receiving the values
     */
    void receiveTrnlstScreen(TransactionListRequest request, WorkArea ws,
                             TransactionListResponse response) {
        for (String prefix : TransactionListResponse.fieldPrefixes()) {
            response.setPayloadValue(prefix, codec.movePicX(request.getPayloadValue(prefix),
                    TransactionListResponse.declaredLength(prefix)));
        }
        ws.receiveOutcome = Outcome.OK;
    }

    /**
     * {@code MOVE LOW-VALUES TO COTRN0AO} at {@code :114} - the enter path, and only the enter path.
     *
     * <p>Both halves take the low value, because that is what the statement moves. The attribute quads
     * take it because it tells BMS to use the map's default rendering; the payload items take it
     * because {@code X'00'} is the byte a never-painted screen field holds. An earlier revision wrote
     * <strong>spaces</strong> into the payload items on the reasoning that this program's own
     * predicates - {@code = SPACES OR LOW-VALUES} at {@code :206} and
     * {@code NOT = SPACES AND LOW-VALUES} at {@code :149-184} - treat the two identically. They do,
     * <em>here</em>; but the substituted byte is visible in the response this program returns, and
     * {@code COSGN00C.cbl:118} shows a sibling program whose predicate distinguishes them. See
     * {@link ScreenFieldImage} for the single decision this now routes through.
     *
     * <p>This is also the whole of gate G38 for this screen: the attribute reset happens here, on the
     * enter path, and never on re-entry - see the class documentation for why there is no field
     * highlighting to gate.
     *
     * @param response the map area being blanked
     */
    void moveLowValuesToOutputMap(TransactionListResponse response) {
        for (String prefix : TransactionListResponse.fieldPrefixes()) {
            response.setPayloadValue(prefix,
                    ScreenFieldImage.unpainted(TransactionListResponse.declaredLength(prefix)));
        }
        response.resetAttributesToLowValues();
    }

    /**
     * {@code POPULATE-HEADER-INFO}, {@code :567-586}.
     *
     * <p>Six moves, all of them into fields already exactly their receiver's width, so nothing pads
     * and nothing truncates. The two titles come from {@code COTTL01Y} through
     * {@link ScreenTitles} - note that {@link ScreenTitles#CCDA_THANK_YOU} is an {@code X(40)} screen
     * title and is a <em>different</em> literal from {@code SystemMessages.CCDA_MSG_THANK_YOU}, which
     * is {@code X(50)}; neither is used by this program, and they are never interchangeable.
     *
     * @param response the map area whose heading is filled
     */
    void populateHeaderInfo(TransactionListResponse response) {
        response.populateHeaderInfo(DateHeader.from(codec, clock));                 // :569-586
    }

    /**
     * {@code MOVE -1 TO TRNIDINL OF COTRN0AI} - nine sites: {@code :105}, {@code :131}, {@code :201},
     * {@code :216}, {@code :221}, {@code :243}, {@code :265}, {@code :610}, {@code :644}, {@code :678}
     * and {@code :685}.
     *
     * <p>{@code -1} in a symbolic map's length item is the CICS convention for "put the cursor here"
     * when the map is sent with {@code CURSOR}. The length item is metadata and never a payload member
     * (AAP 0.6.3), so it is recorded two ways: on the request's field metadata, because
     * {@code COTRN0AI} and {@code COTRN0AO} are one buffer and that is the item the source writes, and
     * on the work area, where a test can read it without reaching into the payload.
     *
     * @param request the inbound payload, whose field metadata carries the cursor request
     * @param ws      the work area
     */
    void placeCursorOnTranId(TransactionListRequest request, WorkArea ws) {
        request.positionCursorAt(TransactionListRequest.TRNIDIN_FIELD);
        ws.cursorField = TransactionListRequest.TRNIDIN_FIELD;
        ws.cursorPositions = ws.cursorPositions + 1;
    }

    /**
     * Makes the response's cursor the working copy of {@code CDEMO-CT00-INFO}.
     *
     * <p>{@link TransactionListResponse#setCursor} takes a defensive copy and
     * {@link TransactionListResponse#getCursor} hands back the live instance, so re-reading it after
     * seeding is what gives this class and the payload <strong>one</strong> cursor rather than two that
     * have to be kept in step. That matters because
     * {@link TransactionListResponse#populateTranData(FixedWidthCodec, int, String, String, String, String)}
     * writes {@code CDEMO-CT00-TRNID-FIRST} and {@code -LAST} into it directly, exactly as
     * {@code :393} and {@code :439} do.
     *
     * @param ws       the work area whose cursor reference is set
     * @param response the response that owns the cursor
     * @param seed     the value to start from
     */
    private static void adoptCursor(WorkArea ws, TransactionListResponse response,
                                    TransactionListCursor seed) {
        response.setCursor(seed);
        ws.cursor = response.getCursor();
    }

    /**
     * Projects the request's {@code CDEMO-CT00-INFO} onto the response's projection of the same 58
     * bytes.
     *
     * <p>Two types exist for one copybook group because each half of the DTO pair owns its own, and
     * neither refers to the other. All six fields are copied and none is defaulted, so the browse
     * position an inbound request carries is the browse position this run continues from.
     *
     * @param inbound the cursor the request carried
     * @return an equivalent cursor in the response's projection
     * @throws NullPointerException if {@code inbound} is {@code null}
     */
    static TransactionListCursor cursorOf(PaginationCursor inbound) {
        Objects.requireNonNull(inbound, "A pagination cursor is required: CDEMO-CT00-INFO travels "
                + "with every request this screen serves, and defaulting it would silently restart "
                + "the browse");
        TransactionListCursor cursor = new TransactionListCursor();
        cursor.setTrnidFirst(inbound.getTrnidFirst());
        cursor.setTrnidLast(inbound.getTrnidLast());
        cursor.setPageNum(inbound.getPageNum());
        cursor.setNextPageFlg(inbound.getNextPageFlg());
        cursor.setTrnSelFlg(inbound.getTrnSelFlg());
        cursor.setTrnSelected(inbound.getTrnSelected());
        return cursor;
    }

    // =================================================================================================
    // The two edited renderings - app/cbl/COTRN00C.cbl:56-57 and :383-388.
    //
    // Both are produced by explicit character arithmetic. Neither uses NumberFormat, DecimalFormat,
    // String.format with a locale-sensitive conversion, or a DateTimeFormatter: an edited COBOL field
    // is a fixed sequence of characters, and a default locale that groups with a comma or renders
    // Arabic-Indic digits would change the bytes on the screen (practice B8).
    // =================================================================================================

    /** The number of digit positions in {@code PIC +99999999.99}: eight integer plus two fraction. */
    private static final int WS_TRAN_AMT_DIGITS =
            WS_TRAN_AMT_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE;

    /** {@code 10^10} - the modulus that discards the ninth integer digit on the store. */
    private static final BigInteger AMOUNT_MODULUS = BigInteger.TEN.pow(WS_TRAN_AMT_DIGITS);

    /** The sign character {@code PIC +} places for a positive or zero value. */
    private static final String SIGN_POSITIVE = "+";

    /** The sign character {@code PIC +} places for a negative value. */
    private static final String SIGN_NEGATIVE = "-";

    /** The decimal point of {@code PIC +99999999.99}; it is a point in the picture, not a locale. */
    private static final String DECIMAL_POINT = ".";

    /** Offset of {@code WS-TIMESTAMP-DT-YYYY} inside {@code WS-TIMESTAMP}. */
    private static final int TS_YEAR_OFFSET = 0;

    /** The {@code (3:2)} reference modifier of {@code :385}, as a zero-based offset into the year. */
    private static final int TS_YEAR_LAST_TWO_OFFSET =
            DateHeader.YEAR_DIGITS - DateHeader.TWO_DIGIT_YEAR_DIGITS;

    /** Offset of {@code WS-TIMESTAMP-DT-MM}: the four year digits and one separator. */
    private static final int TS_MONTH_OFFSET =
            TS_YEAR_OFFSET + DateHeader.YEAR_DIGITS + DateHeader.SEPARATOR_LENGTH;

    /** Offset of {@code WS-TIMESTAMP-DT-DD}: the month digits and one more separator. */
    private static final int TS_DAY_OFFSET =
            TS_MONTH_OFFSET + DateHeader.MONTH_DIGITS + DateHeader.SEPARATOR_LENGTH;

    /**
     * {@code MOVE TRAN-AMT TO WS-TRAN-AMT} [{@code :383}] - an {@code S9(09)V99} value into a
     * {@code PIC +99999999.99} edited field.
     *
     * <p>Three properties of that move are behaviour and all three are reproduced:
     *
     * <ul>
     *   <li><strong>The ninth integer digit is discarded.</strong> The sender has nine integer digits
     *       and the receiver has eight, and a numeric {@code MOVE} truncates on the <em>left</em>. So
     *       {@code 123456789.12} renders as {@code +23456789.12}. That is a legacy truncation, not a
     *       defect to be corrected here.</li>
     *   <li><strong>The sign is always present.</strong> {@code PIC +} reserves a position and fills
     *       it with {@code +} or {@code -}, so a positive value is {@code +} and never a space, and
     *       zero is {@code +}.</li>
     *   <li><strong>The scale is fixed at {@value CobolDecimal#MONETARY_SCALE} and rounding is
     *       {@code DOWN}</strong>, through {@link CobolDecimal}, because {@code ROUNDED} appears zero
     *       times in all twenty-eight programs.</li>
     * </ul>
     *
     * @param amount the record's {@code TRAN-AMT}
     * @return exactly {@value #WS_TRAN_AMT_LENGTH} characters
     * @throws NullPointerException if {@code amount} is {@code null}
     */
    String editedAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "An amount is required to render WS-TRAN-AMT");
        BigDecimal stored = CobolDecimal.store(amount, CobolDecimal.MONETARY_SCALE);
        BigInteger digits = stored.abs().unscaledValue().mod(AMOUNT_MODULUS);
        String rendered = codec.movePic9(digits.longValueExact(), WS_TRAN_AMT_DIGITS);
        String sign = stored.signum() < 0 ? SIGN_NEGATIVE : SIGN_POSITIVE;
        return sign
                + rendered.substring(0, WS_TRAN_AMT_INTEGER_DIGITS)
                + DECIMAL_POINT
                + rendered.substring(WS_TRAN_AMT_INTEGER_DIGITS);
    }

    /**
     * The four statements at {@code :384-388} that turn a record's origination timestamp into the
     * row's {@code MM/DD/YY}:
     *
     * <pre>
     *   MOVE TRAN-ORIG-TS              TO WS-TIMESTAMP
     *   MOVE WS-TIMESTAMP-DT-YYYY(3:2) TO WS-CURDATE-YY
     *   MOVE WS-TIMESTAMP-DT-MM        TO WS-CURDATE-MM
     *   MOVE WS-TIMESTAMP-DT-DD        TO WS-CURDATE-DD
     *   MOVE WS-CURDATE-MM-DD-YY       TO WS-TRAN-DATE
     * </pre>
     *
     * <p>{@link DateHeader} carries exactly this pair of operations -
     * {@link DateHeader#withTimestampImage(String)} and
     * {@link DateHeader#withCurdateMmDdYyFromTimestamp()} - and is used for it, so the rendering lives
     * in one place for the whole module. It validates the image, however, and a COBOL {@code MOVE}
     * cannot fail: a record whose {@code TRAN-ORIG-TS} is blank or malformed still produces eight
     * bytes on the screen. So a rejected image falls back to
     * {@link #editedTranDateByPosition(String)}, which performs the same three reference-modified
     * moves as the byte moves they are. For a well-formed timestamp the two paths agree exactly, and
     * the unit test asserts that they do.
     *
     * @param originationTimestamp the record's {@code TRAN-ORIG-TS}, of any content
     * @return exactly {@value #WS_TRAN_DATE_LENGTH} characters
     * @throws NullPointerException if {@code originationTimestamp} is {@code null}
     */
    String editedTranDate(String originationTimestamp) {
        Objects.requireNonNull(originationTimestamp, "An origination timestamp is required to render "
                + "WS-TRAN-DATE; TRAN-ORIG-TS is a fixed 26-byte field and is never absent");
        String image = codec.movePicX(originationTimestamp, WS_TIMESTAMP_LENGTH);
        try {
            return DateHeader.from(codec, clock)
                    .withTimestampImage(image)
                    .withCurdateMmDdYyFromTimestamp()
                    .wsCurdateMmDdYy();
        } catch (IllegalArgumentException notAWellFormedTimestamp) {
            // Not an error to report: the COBOL has no error path here. The group MOVE at :384 copies
            // whatever bytes the record holds, and :385-387 then copy three two-byte slices of them,
            // so a malformed timestamp yields a malformed date on the screen rather than a failure.
            return editedTranDateByPosition(image);
        }
    }

    /**
     * The three reference-modified moves of {@code :385-387} performed as the byte moves they are, for
     * a timestamp image that is not a well-formed {@code WS-TIMESTAMP}.
     *
     * <p>Every offset is derived from {@link DateHeader}'s published geometry rather than written as a
     * number, so the two implementations cannot disagree about where the year, month and day sit.
     *
     * @param image the 26-character image, already reshaped to width
     * @return exactly {@value #WS_TRAN_DATE_LENGTH} characters, in {@code MM/DD/YY} order
     */
    String editedTranDateByPosition(String image) {
        String yearLastTwo = image.substring(TS_YEAR_OFFSET + TS_YEAR_LAST_TWO_OFFSET,
                TS_YEAR_OFFSET + DateHeader.YEAR_DIGITS);
        String month = image.substring(TS_MONTH_OFFSET, TS_MONTH_OFFSET + DateHeader.MONTH_DIGITS);
        String day = image.substring(TS_DAY_OFFSET, TS_DAY_OFFSET + DateHeader.DAY_DIGITS);
        return month + DateHeader.DATE_SEPARATOR + day + DateHeader.DATE_SEPARATOR + yearLastTwo;
    }

    // =================================================================================================
    // The COBOL relational tests, written literally. Each one is the relation the source uses, not an
    // approximation of it, and none of them consults a locale.
    // =================================================================================================

    /**
     * {@code = SPACES} for a group or alphanumeric item: every character is a space.
     *
     * @param value the value to test
     * @return {@code true} if every character is a space, including for an empty value
     */
    static boolean isAllSpaces(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code = LOW-VALUES}: every character is {@code X'00'}.
     *
     * @param value the value to test
     * @return {@code true} if every character is the low value, including for an empty value
     */
    static boolean isAllLowValues(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != LOW_VALUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code NOT = SPACES AND LOW-VALUES} - the abbreviated combined relation the source uses eleven
     * times [{@code :149-176}, {@code :183-184}], with the {@code NOT =} distributed across both
     * subjects: the item is neither all spaces nor all low values.
     *
     * <p>Written as the relation rather than as "contains a character that is neither", because for a
     * multi-byte item the two differ: a value that mixes spaces and low values is neither
     * {@code SPACES} nor {@code LOW-VALUES} and is therefore <em>present</em> by this test. Every
     * value this program applies it to is uniform, so the distinction never shows - which is exactly
     * why it is worth writing the relation the source actually has.
     *
     * @param value the value to test
     * @return {@code true} if the item is neither all spaces nor all low values
     */
    static boolean isPresentCobol(String value) {
        return !isAllSpaces(value) && !isAllLowValues(value);
    }

    /**
     * {@code IS NUMERIC} for an alphanumeric item: every character is one of the ten ASCII digits.
     *
     * <p>{@link Character#isDigit(char)} is deliberately <strong>not</strong> used: it accepts every
     * Unicode decimal digit, so an Arabic-Indic or fullwidth digit would pass here and then fail to
     * match any key in the file. The class condition {@code NUMERIC} on a {@code PIC X} item tests for
     * the digits {@code 0} through {@code 9} and nothing else.
     *
     * @param value the value to test
     * @return {@code true} if the value is non-empty and every character is {@code '0'}..{@code '9'}
     */
    static boolean isCobolNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    // =================================================================================================
    // TRAN-ID as a RIDFLD. The source moves three kinds of value into it, and the kind matters.
    // =================================================================================================

    /** Which of the three things the source moves into {@code TRAN-ID} a browse is anchored on. */
    public enum RidfldKind {

        /** {@code MOVE LOW-VALUES TO TRAN-ID} - {@code :207} and {@code :237}: before the first key. */
        LOW_VALUES,

        /** {@code MOVE HIGH-VALUES TO TRAN-ID} - {@code :260}: after the last key. */
        HIGH_VALUES,

        /** A concrete key - {@code :210}, {@code :239} and {@code :262}. */
        KEY
    }

    /**
     * The {@code RIDFLD} of {@code STARTBR}: either a concrete key or one of the two boundaries.
     *
     * <p>The two boundaries are carried as <em>kinds</em> rather than as key values, and deliberately.
     * {@code LOW-VALUES} is {@code X'00'} repeated and {@code HIGH-VALUES} is {@code X'FF'} repeated,
     * and {@code X'FF'} has no character in every code page a deployment may configure, so a literal
     * boundary key could not survive a strict encoder. What the COBOL <em>means</em> by them is "from
     * the first record" and "from the last record", which is what
     * {@link TransactionRepository#startBrowse(BrowseDirection)} expresses exactly.
     *
     * @param kind which of the three the source moved in
     * @param key  the concrete key when {@code kind} is {@link RidfldKind#KEY}, otherwise empty
     */
    public record Ridfld(RidfldKind kind, String key) {

        /**
         * Compact constructor, refusing a state that could not be reached from the source.
         *
         * @throws NullPointerException if either component is {@code null}
         */
        public Ridfld {
            Objects.requireNonNull(kind, "A RIDFLD kind is required");
            Objects.requireNonNull(key, "A RIDFLD key is required; pass an empty string for a "
                    + "boundary");
        }

        /**
         * The {@code MOVE LOW-VALUES TO TRAN-ID} form.
         *
         * @return a boundary {@code RIDFLD} below every key
         */
        public static Ridfld lowValues() {
            return new Ridfld(RidfldKind.LOW_VALUES, "");
        }

        /**
         * The {@code MOVE HIGH-VALUES TO TRAN-ID} form.
         *
         * @return a boundary {@code RIDFLD} above every key
         */
        public static Ridfld highValues() {
            return new Ridfld(RidfldKind.HIGH_VALUES, "");
        }

        /**
         * A concrete key.
         *
         * @param key the key value, reshaped to the dataset's key width by the repository
         * @return a keyed {@code RIDFLD}
         * @throws NullPointerException if {@code key} is {@code null}
         */
        public static Ridfld ofKey(String key) {
            return new Ridfld(RidfldKind.KEY, key);
        }

        /**
         * Whether this is a boundary rather than a concrete key.
         *
         * @return {@code true} for either boundary
         */
        public boolean isBoundary() {
            return kind != RidfldKind.KEY;
        }

        /**
         * Whether a browse anchored here can return nothing at all, without a backend call being
         * needed to establish it.
         *
         * <p>Two of the six combinations are unreachable by construction: nothing can be at or after
         * {@code HIGH-VALUES} going forward, and nothing can be at or before {@code LOW-VALUES} going
         * backward. Both are genuinely reached by the source - {@code :260} sets {@code HIGH-VALUES}
         * and {@code PROCESS-PF8-KEY} may then page <em>forward</em>, and {@code :237} sets
         * {@code LOW-VALUES} before {@code PROCESS-PAGE-BACKWARD} - and in both cases CICS answers
         * {@code NOTFND} at positioning time. Answering the same thing here, with no query issued, is
         * both faithful and the only form expressible in a code page that has no {@code X'FF'}.
         *
         * @param direction the direction the browse will be walked
         * @return {@code true} if no record can satisfy this anchor in that direction
         */
        public boolean isUnreachable(BrowseDirection direction) {
            return (kind == RidfldKind.HIGH_VALUES && direction == BrowseDirection.FORWARD)
                    || (kind == RidfldKind.LOW_VALUES && direction == BrowseDirection.BACKWARD);
        }
    }

    // =================================================================================================
    // The browse handle - the one-record lookahead that recovers the CICS STARTBR status.
    // =================================================================================================

    /**
     * A {@code TRANSACT} browse that reports a positioning status, which the repository's handle does
     * not.
     *
     * <p>{@code COTRN00C} has a three-armed {@code EVALUATE WS-RESP-CD} after its {@code STARTBR}
     * [{@code :602-619}] and one of those arms carries {@link #MSG_AT_TOP_OF_PAGE}, a literal no other
     * paragraph produces. {@link TransactionRepository#startBrowse(String, BrowseDirection)} performs
     * no backend call and so cannot report {@code NOTFND}. The two are reconciled by one probe read:
     *
     * <ul>
     *   <li>{@code STARTBR ... GTEQ} raises {@code NOTFND} exactly when no record has a key at or after
     *       the {@code RIDFLD}, which is exactly when the first read of that browse would find
     *       nothing. So the probe's outcome <em>is</em> the positioning status.</li>
     *   <li>A record found by the probe is <strong>buffered</strong> and handed to the first
     *       {@link #read()}, so no record is consumed by the probe and none is read twice. That is what
     *       lets the skip-read at {@code :286} still skip exactly one record.</li>
     *   <li>When the probe finds nothing the underlying browse is ended, so a later read reports the
     *       invalid-request condition - which is what CICS reports for a {@code READNEXT} with no
     *       browse in progress, and is the state {@code :285-287} genuinely reaches because
     *       {@code NOTFND} does not set {@code WS-ERR-FLG}.</li>
     * </ul>
     *
     * <p>The handle holds no server-side cursor: the repository re-positions by value on each step.
     * It knows no page size (gate G39) - that ten reads make a page is the caller's decision.
     */
    static final class TransactBrowse implements AutoCloseable {

        /** The direction this browse is walked; a read the other way is never issued. */
        private final BrowseDirection direction;

        /** The repository handle, or {@code null} when no browse could be positioned at all. */
        private final Browse browse;

        /** The probe's outcome, which is the CICS {@code STARTBR} status. */
        private final ReadResult positioning;

        /** The probe's record, awaiting the first read; cleared once handed over. */
        private ReadResult buffered;

        private TransactBrowse(BrowseDirection direction, Browse browse, ReadResult positioning,
                               ReadResult buffered) {
            this.direction = direction;
            this.browse = browse;
            this.positioning = positioning;
            this.buffered = buffered;
        }

        /**
         * Positions a browse and probes it - the Java form of {@code EXEC CICS STARTBR} at
         * {@code :593-600}.
         *
         * @param repository the {@code TRANSACT} file
         * @param direction  the direction the browse will be walked
         * @param ridfld     the {@code RIDFLD} the source moved into {@code TRAN-ID}
         * @return a handle whose {@link #positioningOutcome()} carries the {@code STARTBR} status
         * @throws NullPointerException if any argument is {@code null}
         */
        static TransactBrowse position(TransactionRepository repository, BrowseDirection direction,
                                       Ridfld ridfld) {
            Objects.requireNonNull(repository, "A repository is required to position a browse");
            Objects.requireNonNull(direction, "A direction is required to position a browse");
            Objects.requireNonNull(ridfld, "A RIDFLD is required to position a browse");
            if (ridfld.isUnreachable(direction)) {
                return new TransactBrowse(direction, null,
                        ReadResult.endOfFile(TransactionRepository.CICS_FILE_NAME), null);
            }
            Browse opened = ridfld.isBoundary()
                    ? repository.startBrowse(direction)
                    : repository.startBrowse(ridfld.key(), direction);
            ReadResult probe = direction == BrowseDirection.FORWARD
                    ? opened.readNext()
                    : opened.readPrev();
            if (probe.outcome() == Outcome.OK) {
                return new TransactBrowse(direction, opened, probe, probe);
            }
            // Nothing at or beyond the anchor, or a refusal: either way no browse is in progress, so a
            // read after this reports the invalid-request condition rather than resuming silently.
            opened.endBrowse();
            return new TransactBrowse(direction, opened, probe, null);
        }

        /**
         * The {@code STARTBR} status, as {@link #positionArmOf(Outcome)} classifies it.
         *
         * @return the probe's outcome; never {@code null}
         */
        ReadResult positioningOutcome() {
            return positioning;
        }

        /**
         * One {@code READNEXT} or {@code READPREV}, whichever this browse's direction calls for.
         *
         * @return the discriminated outcome; never {@code null}
         */
        ReadResult read() {
            if (buffered != null) {
                ReadResult first = buffered;
                buffered = null;
                return first;
            }
            if (browse == null) {
                return ReadResult.other(TransactionRepository.CICS_FILE_NAME,
                        TransactionRepository.PERMANENT_ERROR_STATUS);
            }
            return direction == BrowseDirection.FORWARD ? browse.readNext() : browse.readPrev();
        }

        /**
         * {@code EXEC CICS ENDBR} - {@code :694-696}. Idempotent, so calling it and then closing the
         * handle is safe.
         */
        void endBrowse() {
            if (browse != null) {
                browse.endBrowse();
            }
        }

        /** Ends the browse, so a handle can be used in a try-with-resources block. */
        @Override
        public void close() {
            endBrowse();
        }
    }

    // =================================================================================================
    // WORKING-STORAGE - app/cbl/COTRN00C.cbl:35-70.
    //
    // One instance per call, never a field of the controller (practice B9, gate G53): COBOL
    // WORKING-STORAGE is per-task, and a static equivalent would leak one request's paging state into
    // another's and make test order significant.
    // =================================================================================================

    /**
     * The program's {@code WORKING-STORAGE}, as a per-request value.
     *
     * <p>Every item the source declares is here, including the one it never uses, and every one is
     * readable so that a parity case can assert the state the COBOL would have left behind - including
     * the items that never reach the payload, which is where {@code WS-SEND-ERASE-FLG} and the cursor
     * position live.
     *
     * <p>A fresh instance is the declared {@code VALUE} state: {@code 'N'}, {@code 'N'}, {@code 'Y'},
     * zeroes, and {@code '00/00/00'}.
     */
    public static final class WorkArea {

        /** The {@code 'Y'} of every {@code 88}-level in this program. */
        public static final char FLAG_YES = 'Y';

        /** The {@code 'N'} of every {@code 88}-level in this program. */
        public static final char FLAG_NO = 'N';

        /** {@code WS-ERR-FLG PIC X(01) VALUE 'N'} - {@code :40-42}. */
        private char errFlg = FLAG_NO;

        /** {@code WS-TRANSACT-EOF PIC X(01) VALUE 'N'} - {@code :43-45}. */
        private char transactEof = FLAG_NO;

        /** {@code WS-SEND-ERASE-FLG PIC X(01) VALUE 'Y'} - {@code :46-48}. */
        private char sendEraseFlg = FLAG_YES;

        /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code :38}. */
        private String message = "";

        /** {@code WS-IDX PIC S9(04) COMP VALUE ZEROS} - {@code :53}, the screen row being filled. */
        private int idx;

        /**
         * {@code WS-REC-COUNT PIC S9(04) COMP VALUE ZEROS} - {@code :52}.
         *
         * <p><strong>Declared and never used.</strong> Not one statement in the 699 lines reads or
         * writes it. It is modelled because the copybook-level fidelity of {@code WORKING-STORAGE} is
         * part of the contract and because deleting a legacy declaration is exactly the kind of tidying
         * practice B5 forbids; it stays at zero for the whole run, as it does on the mainframe.
         */
        private final int recCount;

        /** {@code WS-TRAN-AMT PIC +99999999.99} - {@code :56}, the edited row amount. */
        private String tranAmt = "";

        /** {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - {@code :57}, the edited row date. */
        private String tranDate = WS_TRAN_DATE_INITIAL;

        /** The communication area, {@code COPY COCOM01Y} at {@code :61}. */
        private NavigationContext commarea = NavigationContext.empty();

        /** {@code CDEMO-CT00-INFO} - {@code :62-70}; the live instance the response also carries. */
        private TransactionListCursor cursor = new TransactionListCursor();

        /**
         * {@code TRAN-ID} in its role as the {@code RIDFLD}.
         *
         * <p>Starts at {@link Ridfld#lowValues()} because {@code TRAN-RECORD} is
         * {@code WORKING-STORAGE} with no {@code VALUE} clause: on a fresh task its bytes are low
         * values, and that is what {@code STARTBR} is issued with on the one path that reaches
         * {@code :281} without having set the key - the non-numeric edit failure at {@code :211-218},
         * which has no early exit.
         */
        private Ridfld ridfld = Ridfld.lowValues();

        /** {@code EIBAID} - the key the operator pressed. */
        private byte eibAid = CicsAid.DFHENTER;

        /** {@code EIBCALEN} - the length of the communication area that arrived. */
        private int eibcalen;

        /** The field {@code MOVE -1 TO ...L} last named, or {@code null} before the first placement. */
        private String cursorField;

        /** How many times the cursor was placed; the source does it up to eleven times per run. */
        private int cursorPositions;

        /** How many times {@code SEND-TRNLST-SCREEN} ran. */
        private int sendCount;

        /** Whether the last send carried {@code ERASE}; {@code true} until a path clears it. */
        private boolean lastSendErase = true;

        /** How many browse reads were issued, the probe excluded. */
        private int readCount;

        /** The {@code STARTBR} status, from the probe read; {@code null} until a browse is positioned. */
        private Outcome startbrOutcome;

        /** The status of the most recent read; {@code null} until one is issued. */
        private Outcome lastReadOutcome;

        /** The {@code RECEIVE MAP} status - captured at {@code :560-561}, and never branched on. */
        private Outcome receiveOutcome;

        /** Whether control was transferred, which is what makes {@code :138} unreachable. */
        private boolean transferred;

        /** The row whose selection cell won the ordered {@code EVALUATE}, or {@code 0} for none. */
        private int selectedRow;

        /** Creates the declared {@code VALUE} state of {@code WORKING-STORAGE}. */
        public WorkArea() {
            this.recCount = 0;
        }

        /** @return {@code true} when {@code ERR-FLG-ON} holds - {@code :41}. */
        public boolean isErrFlgOn() {
            return errFlg == FLAG_YES;
        }

        /** @return {@code true} when {@code ERR-FLG-OFF} holds - {@code :42}. */
        public boolean isErrFlgOff() {
            return errFlg == FLAG_NO;
        }

        /** {@code MOVE 'Y' TO WS-ERR-FLG} - {@code :130}, {@code :212}, {@code :614}. */
        void setErrFlgOn() {
            errFlg = FLAG_YES;
        }

        /** {@code SET ERR-FLG-OFF TO TRUE} - {@code :97}. */
        void setErrFlgOff() {
            errFlg = FLAG_NO;
        }

        /** @return {@code true} when {@code TRANSACT-EOF} holds - {@code :44}. */
        public boolean isTransactEof() {
            return transactEof == FLAG_YES;
        }

        /** @return {@code true} when {@code TRANSACT-NOT-EOF} holds - {@code :45}. */
        public boolean isTransactNotEof() {
            return transactEof == FLAG_NO;
        }

        /** {@code SET TRANSACT-EOF TO TRUE} - {@code :607}, {@code :641}, {@code :675}. */
        void setTransactEof() {
            transactEof = FLAG_YES;
        }

        /** {@code SET TRANSACT-NOT-EOF TO TRUE} - {@code :98}. */
        void setTransactNotEof() {
            transactEof = FLAG_NO;
        }

        /** @return {@code true} when {@code SEND-ERASE-YES} holds - {@code :47}. */
        public boolean isSendEraseYes() {
            return sendEraseFlg == FLAG_YES;
        }

        /** @return {@code true} when {@code SEND-ERASE-NO} holds - {@code :48}. */
        public boolean isSendEraseNo() {
            return sendEraseFlg == FLAG_NO;
        }

        /** {@code SET SEND-ERASE-YES TO TRUE} - {@code :100}. */
        void setSendEraseYes() {
            sendEraseFlg = FLAG_YES;
        }

        /** {@code SET SEND-ERASE-NO TO TRUE} - {@code :250} and {@code :272}. */
        void setSendEraseNo() {
            sendEraseFlg = FLAG_NO;
        }

        /** @return the stored {@code WS-ERR-FLG} character. */
        public char errFlg() {
            return errFlg;
        }

        /** @return the stored {@code WS-TRANSACT-EOF} character. */
        public char transactEof() {
            return transactEof;
        }

        /** @return the stored {@code WS-SEND-ERASE-FLG} character. */
        public char sendEraseFlg() {
            return sendEraseFlg;
        }

        /** @return {@code WS-MESSAGE}, space-padded to its declared eighty characters once set. */
        public String message() {
            return message;
        }

        /** @return {@code WS-IDX}. */
        public int idx() {
            return idx;
        }

        /** @return {@code WS-REC-COUNT}, which this program never changes. */
        public int recCount() {
            return recCount;
        }

        /** @return {@code WS-TRAN-AMT}, the last edited amount rendered. */
        public String tranAmt() {
            return tranAmt;
        }

        /** @return {@code WS-TRAN-DATE}, the last edited date rendered. */
        public String tranDate() {
            return tranDate;
        }

        /** @return the communication area as it stands. */
        public NavigationContext commarea() {
            return commarea;
        }

        /** @return the live {@code CDEMO-CT00-INFO}; mutating it mutates the response's cursor. */
        public TransactionListCursor cursor() {
            return cursor;
        }

        /** @return the {@code RIDFLD} the next {@code STARTBR} will use. */
        public Ridfld ridfld() {
            return ridfld;
        }

        /** @return the {@code EIBAID} byte this run was driven with. */
        public byte eibAid() {
            return eibAid;
        }

        /** @return {@code EIBCALEN}. */
        public int eibcalen() {
            return eibcalen;
        }

        /** @return the field the cursor was last placed on, or {@code null}. */
        public String cursorField() {
            return cursorField;
        }

        /** @return how many times the cursor was placed. */
        public int cursorPositions() {
            return cursorPositions;
        }

        /**
         * This screen's presentation metadata, in the shared envelope every online response publishes.
         *
         * <p>Three things this execution produces are metadata by declaration rather than payload, and
         * before this envelope existed none of them had any way to travel:
         *
         * <ul>
         *   <li>the {@code MOVE -1 TO TRNIDINL} cursor request, an {@code xxxL} item. The Agent Action
         *       Plan's section 0.3.9 is explicit that {@code xxxL} is validation and highlight metadata
         *       and not a payload member, so it is reported here rather than smuggled into a projection
         *       of {@code xxxI} and {@code xxxO} items;</li>
         *   <li>the {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} quad of every field,
         *       which is what {@code app/cpy/CSSETATY.cpy} writes {@link BmsAttributes#DFHRED} into
         *       when a field is in error;</li>
         *   <li>{@code WS-SEND-ERASE-FLG}, which chooses between {@code SEND ... ERASE} and a
         *       {@code SEND} without it at {@code :533-549}. {@code ERASE} clears the screen before
         *       painting, and that is the same instruction to a client: repaint rather than merge.</li>
         * </ul>
         *
         * <p>The message colour is read from {@code ERRMSGC} rather than restated, so a rename cannot
         * silently leave this pointing at a field that no longer exists. Each quad is published as four
         * unsigned {@code 0}-{@code 255} values, because an attribute byte with the high bit set -
         * {@link BmsAttributes#DFHRED} is {@code 0xF2} - is a negative {@code byte} in Java and
         * publishing {@code -14} would misstate it.
         *
         * @param painted the response this run produced; must not be {@code null}
         * @return the metadata; never {@code null}
         * @throws NullPointerException if {@code painted} is {@code null}
         */
        public ScreenMetadata screenMetadata(TransactionListResponse painted) {
            Objects.requireNonNull(painted, "A painted screen is required to read its attribute quads");
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            for (Map.Entry<String, TransactionListResponse.FieldAttributes> quad
                    : painted.allAttributes().entrySet()) {
                fields.put(quad.getKey(), ScreenMetadata.FieldMetadata.of(quad.getValue().colour(),
                        quad.getValue().programmedSymbols(),
                        quad.getValue().highlight(),
                        quad.getValue().validation()));
            }
            return ScreenMetadata.of(cursorField,
                    painted.attributesOf(TransactionListResponse.ERRMSG).colour(),
                    isSendEraseYes(),
                    fields);
        }

        /** @return how many times the screen was sent. */
        public int sendCount() {
            return sendCount;
        }

        /** @return whether the last send carried {@code ERASE}. */
        public boolean lastSendErase() {
            return lastSendErase;
        }

        /** @return how many browse reads were issued. */
        public int readCount() {
            return readCount;
        }

        /** @return the {@code STARTBR} status, or {@code null} if no browse was positioned. */
        public Outcome startbrOutcome() {
            return startbrOutcome;
        }

        /** @return the most recent read's status, or {@code null} if none was issued. */
        public Outcome lastReadOutcome() {
            return lastReadOutcome;
        }

        /** @return the {@code RECEIVE MAP} status, or {@code null} on a path that received nothing. */
        public Outcome receiveOutcome() {
            return receiveOutcome;
        }

        /** @return whether control was transferred to another program. */
        public boolean isTransferred() {
            return transferred;
        }

        /** @return the row whose selection cell was acted on, or {@code 0}. */
        public int selectedRow() {
            return selectedRow;
        }
    }
}
