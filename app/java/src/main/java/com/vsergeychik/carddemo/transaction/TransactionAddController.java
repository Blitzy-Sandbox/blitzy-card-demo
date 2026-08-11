package com.vsergeychik.carddemo.transaction;

import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.PfKeyResolver.AidKey;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.DatasetUnitOfWork;
import com.vsergeychik.carddemo.transaction.TransactionRepository.ReadResult;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse;
import com.vsergeychik.carddemo.transaction.dto.TransactionAddResponse.ScreenField;
import com.vsergeychik.carddemo.transaction.model.TranRecord;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code app/cbl/COTRN01C.cbl} - CSD transaction {@code CT01}, mapset {@code COTRN01}, map
 * {@code COTRN1A} - translated to a stateless Spring Web controller.
 *
 * <h2>RISK R-B: THIS CLASS IS NAMED "Add" AND IT VIEWS. READ THIS BEFORE CHANGING ANYTHING.</h2>
 *
 * <p>The class name is <strong>mandated by the build prompt</strong> and the behaviour is
 * <strong>taken from the source</strong>. They disagree, deliberately and visibly. Four independent
 * artefacts say this program views a transaction:
 *
 * <ol>
 *   <li>the source header, {@code app/cbl/COTRN01C.cbl:2} and {@code :5}, verbatim:
 *       <pre>
 *       * Program     : COTRN01C.CBL
 *       * Function    : View a Transaction from TRANSACT file
 *       </pre></li>
 *   <li>{@code README.md:213-231}, the online application inventory, whose {@code CT01} row reads
 *       {@code | | CT01 | COTRN01 | COTRN01C | Transaction View |} and whose {@code CT02} row -
 *       the next one down - reads {@code Transaction Add};</li>
 *   <li>{@code app/bms/COTRN01.bms:2}, the mapset banner: {@code * CardDemo - Transaction View},
 *       with the screen literal {@code 'View Transaction'} at {@code POS=(4,30)} and the operator
 *       footer {@code 'ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran.'};</li>
 *   <li>the source contains <strong>no write of any kind</strong>. Verified mechanically:
 *       {@code grep -n "WRITE\|REWRITE\|DELETE" app/cbl/COTRN01C.cbl} returns
 *       <strong>no matches</strong> (exit status 1) over all 330 lines. The one file operation in
 *       the program is the keyed {@code EXEC CICS READ} at {@code :269-278}.</li>
 * </ol>
 *
 * <p><strong>Resolution, binding.</strong> AAP rule R1 - "names from the prompt, behavior from the
 * source" - governs, and AAP practice B4 forbids correcting the conflict silently. So the name
 * {@code TransactionAddController} stands verbatim and is not to be renamed, and this class
 * <strong>reads and displays a transaction and never inserts, replaces or removes one</strong>. It
 * holds no reference to any write method: no {@code write}, no {@code rewrite}, no {@code delete}.
 * The add behaviour belongs to {@code TransactionViewController} ({@code COTRN02C}), this class's
 * mirror image, whose name is inverted in exactly the same way. The divergence is registered as
 * <strong>risk R-B</strong> in AAP &sect;0.9.12 and catalogued in AAP &sect;0.8.4 as the single
 * highest-risk naming ambiguity in the migration plan, and it is flagged there for explicit user
 * confirmation.
 *
 * <p><strong>Neither the name nor the behaviour may be inverted to "fix" this.</strong> Renaming the
 * class would breach the prompt; implementing an insert here would breach the source and the
 * like-for-like mandate. If you arrived here intending to do either, the answer is: do neither, and
 * read AAP &sect;0.8.4.
 *
 * <h2>What the program does, in source order</h2>
 *
 * <p>{@code MAIN-PARA} ({@code :86-139}) clears the error and modification flags, blanks the
 * message, then branches three ways: no communication area at all goes to the sign-on program; a
 * first entry paints the screen - and immediately performs the lookup when the list screen handed a
 * selected transaction id across; a re-entry receives the map and dispatches on the attention
 * identifier. {@code PROCESS-ENTER-KEY} ({@code :144-192}) rejects an empty transaction id, then
 * reads {@code TRANSACT} by key and paints the fourteen detail fields.
 *
 * <h2>Statelessness - gate G37, rule R6</h2>
 *
 * <p>There is no {@code HttpSession}, no {@code @SessionAttributes} and no server-side conversation
 * state anywhere in this class. Every scrap of CICS pseudo-conversational state travels in the
 * payload: the 160-byte {@code CARDDEMO-COMMAREA} as {@link NavigationContext}, the 58-byte
 * {@code CDEMO-CT01-INFO} extension as {@link Ct01Info} - the two together being the 218-byte area
 * the program passes - the attention identifier as {@link TransactionAddRequest#getAid()}, and the
 * screen's own field values as the 21 payload members. {@code EXEC CICS XCTL} at {@code :206}
 * becomes {@link TransactionAddResponse#getNextProgram()} and the client issues the follow-up call
 * itself, so there is no server-side forward and no redirect chain (gate G40).
 *
 * <p>Consequently every {@code WORKING-STORAGE} item is a per-request local carried on
 * {@link ProgramState} - {@code WS-ERR-FLG}, {@code WS-USR-MODIFIED}, {@code WS-MESSAGE},
 * {@code WS-TRAN-AMT}, {@code WS-RESP-CD} and {@code WS-REAS-CD} among them. Nothing mutable is
 * static (gate G53) and both collaborators are constructor-injected.
 *
 * <h2>Testability - gate G51</h2>
 *
 * <p>This package has no {@code *Service} class, so the program body is exposed here as public,
 * HTTP-independent methods: {@link #mainPara(TransactionAddRequest)} and one method per COBOL
 * paragraph, each taking and returning the DTOs and the state. The
 * {@linkplain #viewTransaction(TransactionAddRequest) mapping method} is a thin adapter that binds,
 * delegates and projects. A parity case calls the plain methods with no servlet container, no
 * {@code MockMvc} and no {@code JobLauncher} in the path; {@code MockMvc} covers only the adapter.
 *
 * <h2>Practices this class is held to</h2>
 *
 * <p>{@code review_rules} reports <strong>"No user rules provided"</strong> - that one line is the
 * entire rules document and there are no project rules. Their absence is not a licence to lower the
 * bar, so AAP &sect;0.10.2's substitutes bind instead. Governing here:
 *
 * <ul>
 *   <li><strong>B2</strong> - Spring Web (servlet) MVC only, on the pinned Spring Boot 3.x line.
 *       Never WebFlux, and no dependency outside the closed set.</li>
 *   <li><strong>B3</strong> - the COBOL, copybook, BMS, JCL and CSD inputs are read-only. This class
 *       cites them; it never edits them (gate G5).</li>
 *   <li><strong>B4</strong> - the R-B swap above, and the {@code READ ... UPDATE} discrepancy noted
 *       on {@link #readTransactFile(ProgramState)}, are surfaced rather than resolved by
 *       omission.</li>
 *   <li><strong>B6</strong> - no authentication, no authorization, no hashing and no masking. There
 *       is no security annotation in this file (gate G41); {@code COTRN01C} performs no credential
 *       check of its own.</li>
 *   <li><strong>B8</strong> - explicit over implicit: every import is named individually and no
 *       wildcard import appears (gate G52); no dataset name is written down here, the repository
 *       resolves it from configuration (gate G46); the amount goes through
 *       {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} at
 *       {@link CobolDecimal#COBOL_ROUNDING} and is rendered by an explicit, locale-independent
 *       digit-string build - never {@code double}, never {@code float} (gates G22, G23, G24).</li>
 *   <li><strong>B9</strong> - no static mutable state (gate G53); constructor injection only.</li>
 *   <li><strong>B12</strong> - the parity expectations for this program are <em>statically
 *       derived</em>: the 28 legacy programs cannot be executed in this environment (eight verified
 *       blockers, AAP &sect;0.7.6), so the 20 {@code COTRN01C} cases are read out of the source, the
 *       copybooks and the {@code app/data/ASCII} fixtures rather than captured from a live run. That
 *       is risk <strong>R-A</strong>, and it is why every message literal, every width and every
 *       truncation direction in this file carries the line it came from.</li>
 * </ul>
 *
 * @see TransactionAddRequest the inbound 21-field projection of {@code COTRN1AI}
 * @see TransactionAddResponse the outbound 21-field projection of {@code COTRN1AO}
 * @see TransactionRepository the {@code TRANSACT} dataset, this program's only file
 */
@RestController
public final class TransactionAddController {

    /**
     * Carries the program's two {@code DISPLAY}-equivalent diagnostics to the container log.
     *
     * <p>{@code static final} and immutable: a logger is not program state, and the module's other
     * controllers declare theirs identically. Practice B9 forbids static <em>mutable</em> state, and
     * this reference is never reassigned.
     */
    private static final Log LOG = LogFactory.getLog(TransactionAddController.class);

    // =============================================================================================
    // Program identity - app/cbl/COTRN01C.cbl:36-39 and app/csd/CARDDEMO.CSD:149, :264, :429-430.
    // =============================================================================================

    /** {@code WS-PGMNAME PIC X(08) VALUE 'COTRN01C'} - {@code :36}. */
    public static final String PROGRAM_NAME = "COTRN01C";

    /** {@code WS-TRANID PIC X(04) VALUE 'CT01'} - {@code :37}; CSD transaction at {@code :429}. */
    public static final String TRANSACTION_ID = "CT01";

    /** {@code MAPSET('COTRN01')} - {@code :221}; CSD mapset definition at {@code :149}. */
    public static final String MAPSET_NAME = "COTRN01";

    /** {@code MAP('COTRN1A')} - {@code :220}, the single {@code DFHMDI} of the mapset. */
    public static final String MAP_NAME = "COTRN1A";

    /**
     * {@code WS-TRANSACT-FILE PIC X(08) VALUE 'TRANSACT'} - {@code :39}, the CICS file name the
     * {@code READ} at {@code :270} names.
     *
     * <p>This is the eight-character <em>CICS file name</em>, not a dataset name. The
     * {@code AWS.M2.CARDDEMO.*} data set name behind it is resolved by {@link TransactionRepository}
     * from {@code application.yml} and appears nowhere in Java source (gate G46).
     */
    public static final String TRANSACT_FILE_NAME = TransactionRepository.CICS_FILE_NAME;

    /**
     * The path this screen is reached at: {@code GET /api/transactions/{tranId}}.
     *
     * <h4>A read, expressed as a read</h4>
     * {@code COTRN01C} looks a transaction up and paints it. It writes nothing: its only file
     * operation is {@code EXEC CICS READ} at {@code :217-224}, and there is no {@code WRITE},
     * {@code REWRITE} or {@code DELETE} anywhere in the program. The route therefore has to be a
     * {@code GET} on the transaction being read. An earlier revision exposed it as
     * {@code POST /api/transactions/view}, which asserted two things that are not true of this
     * program - that the call changes something, and that {@code view} is a resource - and made the
     * public contract a command rather than the record it reads.
     *
     * <h4>Why the identity is in the URI and the screen is in the body</h4>
     * {@code TRNIDIN} is this map's one input-capable field and the {@code RIDFLD} of the read, so it
     * is the resource's identity and belongs in the path. Everything else - the 218-byte
     * communication area, the attention identifier and the twenty output items a client echoes back -
     * is conversation state, and it travels in the body, which is what keeps this screen free of
     * server-side session state (rule R6, gate G37). The same shape is already the module's
     * convention for a detail read: {@code GET /api/cards/{cardNum}} binds
     * {@code CardSelectRequest} the identical way.
     *
     * <h4>No collision</h4>
     * {@code CT00} owns {@code GET /api/transactions} - a different path, because a template variable
     * is a segment - and {@code CT02} owns {@code POST /api/transactions}, a different method. This
     * route shares a method-and-path pair with neither.
     *
     * <p>The Agent Action Plan leaves the {@code CT01} and {@code CT02} paths unstated in section
     * 0.3.9, naming only the pair and the swap caveat, so the path is derived here from the
     * conventions the plan does fix for its siblings rather than invented.
     */
    public static final String TRANSACTION_DETAIL_PATH = "/api/transactions/{tranId}";

    /** The path variable that carries the transaction id: the {@code RIDFLD} of the read. */
    public static final String TRAN_ID_VARIABLE = "tranId";

    // =============================================================================================
    // Navigation targets - the three program names the source moves into CDEMO-TO-PROGRAM.
    // =============================================================================================

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} - {@code :95} on {@code EIBCALEN = 0}, and again
     * at {@code :200} as {@code RETURN-TO-PREV-SCREEN}'s own default.
     */
    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    /**
     * {@code MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM} - {@code :117}, the PF3 target when
     * {@code CDEMO-FROM-PROGRAM} carries no caller.
     */
    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * {@code MOVE 'COTRN00C' TO CDEMO-TO-PROGRAM} - {@code :126}, the PF5 target. The mapset's
     * operator footer names this key {@code F5=Browse Tran.}, and {@code COTRN00C} is the
     * transaction list screen.
     */
    public static final String TRANSACTION_LIST_PROGRAM = "COTRN00C";

    // =============================================================================================
    // WORKING-STORAGE geometry - app/cbl/COTRN01C.cbl:38-50. Every width is the PICTURE clause's
    // own, so a move that truncates does so in the direction and at the position COBOL truncates.
    // =============================================================================================

    /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code :38}. */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The highest code point an attention identifier can hold: {@code U+00FF}.
     *
     * <p>{@code EIBAID} is one byte, so the whole AID space is {@code U+0000} to {@code U+00FF} and a
     * character above it is not an AID at all. Stated as a constant so
     * {@link #eibAidOf(String)} and its tests read the same bound.
     */
    public static final char MAX_AID_CODE_POINT = 0x00FF;

    /**
     * The character count of {@code WS-TRAN-AMT PIC +99999999.99} - {@code :49}.
     *
     * <p>One fixed sign position, eight unsuppressed digit positions, one actual decimal point and
     * two more digit positions: 1 + 8 + 1 + 2 = {@value #WS_TRAN_AMT_LENGTH}, which is exactly the
     * {@code TRNAMTI PIC X(12)} the value is then moved into at {@code :183}.
     */
    public static final int WS_TRAN_AMT_LENGTH = 12;

    /**
     * The integer digit positions of {@code WS-TRAN-AMT} - <strong>eight</strong>, and this is the
     * detail that makes the amount a parity hazard rather than a formatting chore.
     *
     * <p>{@code TRAN-AMT} is {@code PIC S9(09)V99} ({@code app/cpy/CVTRA05Y.cpy:10}) and carries
     * <em>nine</em> integer digits. {@code MOVE TRAN-AMT TO WS-TRAN-AMT} at {@code :177} therefore
     * moves a nine-digit sender into an eight-digit receiver. COBOL aligns the two on the decimal
     * point and discards the high-order digit that does not fit; no {@code ON SIZE ERROR} phrase
     * appears anywhere in the program, so nothing is raised and the loss is silent. An amount of
     * {@code 123456789.12} is displayed as {@code +23456789.12}.
     */
    public static final int WS_TRAN_AMT_INTEGER_DIGITS = 8;

    /**
     * The fraction digit positions of {@code WS-TRAN-AMT} - two, matching
     * {@link CobolDecimal#MONETARY_SCALE} and every signed {@code PICTURE} in the estate.
     */
    public static final int WS_TRAN_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * Total digit positions of the edited amount: {@value #WS_TRAN_AMT_INTEGER_DIGITS} integer plus
     * {@value #WS_TRAN_AMT_SCALE} fraction = {@value #WS_TRAN_AMT_DIGIT_COUNT}, excluding the sign
     * and the decimal point.
     */
    public static final int WS_TRAN_AMT_DIGIT_COUNT =
            WS_TRAN_AMT_INTEGER_DIGITS + WS_TRAN_AMT_SCALE;

    /**
     * The character a fixed {@code +} insertion emits for a value that is zero or positive.
     *
     * <p>Fixed rather than floating: the {@code PICTURE} is {@code +99999999.99}, so the sign
     * occupies position one always and is never suppressed. A zero amount renders
     * {@code +00000000.00}, including for the negative-zero zoned image {@code 0000000000}
     * <code>&#125;</code> the record may hold, because a fixed {@code +} insertion tests the
     * <em>value</em> and zero is not negative.
     */
    public static final char EDITED_SIGN_POSITIVE = '+';

    /** The character a fixed {@code +} insertion emits for a negative value. */
    public static final char EDITED_SIGN_NEGATIVE = '-';

    /** The actual decimal point of {@code PIC +99999999.99} - a character position, not a scale. */
    public static final char EDITED_DECIMAL_POINT = '.';

    /**
     * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - {@code :50}.
     *
     * <p><strong>Declared and never referenced.</strong> No statement in the 330 lines of
     * {@code COTRN01C} reads or writes it. It is published here rather than dropped because AAP
     * practice B5 preserves dead declarations as they stand: tidying one away is an unrequested
     * change to the program's data division, and a later reader comparing this class against the
     * copybook would find an item missing with no record of why.
     */
    public static final String WS_TRAN_DATE_INITIAL = "00/00/00";

    /** The digit positions {@code WS-RESP-CD} and {@code WS-REAS-CD} display - {@code :43-44}. */
    public static final int WS_RESP_CD_DIGITS = 9;

    /**
     * What the unit of work {@link #mainPara(TransactionAddRequest)} opens is doing, used only to name
     * the boundary in a failure. It names the paragraph and the program, because that is what a reader
     * comparing the two systems will be holding.
     */
    private static final String UNIT_OF_WORK_DESCRIPTION =
            "MAIN-PARA (app/cbl/COTRN01C.cbl:86-139)";

    // =============================================================================================
    // Message literals, byte for byte as the source writes them. Each is moved into WS-MESSAGE
    // PIC X(80) and from there into ERRMSGO PIC X(78), which truncates on the right; none of these
    // is long enough to lose a character, and the move rule is applied regardless.
    // =============================================================================================

    /** {@code MOVE 'Tran ID can NOT be empty...' TO WS-MESSAGE} - {@code :149-150}. */
    public static final String MSG_TRAN_ID_EMPTY = "Tran ID can NOT be empty...";

    /** {@code MOVE 'Transaction ID NOT found...' TO WS-MESSAGE} - {@code :285-286}. */
    public static final String MSG_TRAN_ID_NOT_FOUND = "Transaction ID NOT found...";

    /** {@code MOVE 'Unable to lookup Transaction...' TO WS-MESSAGE} - {@code :292-293}. */
    public static final String MSG_UNABLE_TO_LOOKUP = "Unable to lookup Transaction...";

    /** The literal that opens the {@code DISPLAY} at {@code :290}. */
    public static final String DISPLAY_RESP_PREFIX = "RESP:";

    /** The literal that separates the two codes in the {@code DISPLAY} at {@code :290}. */
    public static final String DISPLAY_REAS_PREFIX = "REAS:";

    // =============================================================================================
    // Figurative constants. SPACES and LOW-VALUES are distinct byte values and the program relies on
    // the distinction, so neither is spelled as the other.
    // =============================================================================================

    /** COBOL {@code SPACE}: {@code X'40'} on the mainframe, {@code 0x20} in this module's code page. */
    public static final char SPACE = ' ';

    /**
     * COBOL {@code LOW-VALUE}: {@code X'00'} in every code page.
     *
     * <p>{@code MOVE LOW-VALUES TO COTRN1AO} at {@code :101} fills all 575 bytes of the output map
     * with this byte, and BMS then omits a null field from the outbound datastream rather than
     * erasing it. Rendering it as a space instead would be a different byte image and a different
     * screen instruction, so it is reproduced as a null.
     */
    public static final char LOW_VALUE = '\u0000';

    /**
     * The code page this program's {@code WORKING-STORAGE} images are rendered in.
     *
     * <p>Named explicitly and never left to the platform default (practice B8). US-ASCII is the code
     * page of the authoritative fixtures under {@code app/data/ASCII}, which is what the parity cases
     * are seeded from. The constructor overload
     * {@link #TransactionAddController(TransactionRepository, Clock, Charset)} exists so a caller can
     * state a different one - IBM037, for an EBCDIC deployment - without this class ever inferring
     * it.
     */
    public static final Charset DEFAULT_WORKING_STORAGE_CHARSET = StandardCharsets.US_ASCII;

    // =============================================================================================
    // Collaborators. Two, both constructor-injected, both immutable references (practice B9).
    // =============================================================================================

    /**
     * The {@code TRANSACT} dataset - {@code EXEC CICS READ} at {@code :269-278}, and the only file
     * this program touches.
     *
     * <p>Declared as the concrete repository rather than an interface because the module declares no
     * repository interface; the migration plan's data-access shape is one {@code @Repository} per
     * dataset. Only its keyed-read method is ever called from here.
     */
    private final TransactionRepository transactionRepository;

    /**
     * {@code FUNCTION CURRENT-DATE} - {@code :245}, read through this and never through
     * {@code LocalDateTime.now()}, so a parity case can pin the instant and compare the header bytes.
     */
    private final Clock clock;

    /** The {@code PICTURE} move rules and the code page for this program's images. */
    private final FixedWidthCodec codec;

    /**
     * The CICS task boundary, made explicit. {@code :269-278} reads the transaction record with the
     * {@code UPDATE} option, which takes the record lock {@code UPDATEMODEL(LOCKING)} declares for
     * {@value #TRANSACT_FILE_NAME}, and in CICS that lock is held until the task's implicit syncpoint
     * at {@code EXEC CICS RETURN}. {@link #mainPara(TransactionAddRequest)} therefore runs inside one
     * unit of work, which is what a task is, and {@link #readTransactFile(ProgramState)} takes its lock
     * inside that.
     */
    private final DatasetUnitOfWork unitOfWork;

    /**
     * Wires the program's three collaborators, rendering images in
     * {@link #DEFAULT_WORKING_STORAGE_CHARSET}.
     *
     * @param transactionRepository the {@code TRANSACT} dataset; must not be {@code null}
     * @param clock                 the clock {@code FUNCTION CURRENT-DATE} reads; must not be
     *                              {@code null}
     * @param unitOfWork            the CICS task boundary one execution runs inside; must not be
     *                              {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    @Autowired
    public TransactionAddController(TransactionRepository transactionRepository,
                                    Clock clock,
                                    DatasetUnitOfWork unitOfWork) {
        this(transactionRepository, clock, unitOfWork, DEFAULT_WORKING_STORAGE_CHARSET);
    }

    /**
     * Wires the program's three collaborators with an explicit code page.
     *
     * @param transactionRepository the {@code TRANSACT} dataset; must not be {@code null}
     * @param clock                 the clock {@code FUNCTION CURRENT-DATE} reads; must not be
     *                              {@code null}
     * @param unitOfWork            the CICS task boundary one execution runs inside; must not be
     *                              {@code null}
     * @param workingStorageCharset the code page for this program's images; must not be {@code null},
     *                              and never the platform default
     * @throws NullPointerException if any argument is {@code null}
     */
    public TransactionAddController(TransactionRepository transactionRepository,
                                    Clock clock,
                                    DatasetUnitOfWork unitOfWork,
                                    Charset workingStorageCharset) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "A TransactionRepository is required: app/cbl/COTRN01C.cbl:269-278 reads the "
                + TRANSACT_FILE_NAME + " file by TRAN-ID, and that keyed read is the only file "
                + "operation in the program - there is no write, rewrite or delete anywhere in it");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is "
                + "read from it at app/cbl/COTRN01C.cbl:245 to build the screen's date and time "
                + "header, and never from the wall clock, so a parity case can pin the instant");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "A unit of work is required: "
                + "app/cbl/COTRN01C.cbl:275 states the UPDATE option, so the read takes a record lock, "
                + "and a lock outside a unit of work is released before the task that asked for it can "
                + "rely on it - which is why TransactionRepository refuses to issue one there");
        Objects.requireNonNull(workingStorageCharset, "A code page is required; it is never the "
                + "platform default");
        this.codec = new FixedWidthCodec(workingStorageCharset);
    }

    // =============================================================================================
    // The HTTP adapter. Thin by design: it binds, delegates and projects. Every decision this screen
    // makes lives in mainPara and the paragraph methods below, so a parity case exercises the program
    // with no servlet container and no MockMvc in the path (gate G51).
    // =============================================================================================

    /**
     * {@code GET /api/transactions/}<code>{tranId}</code> - CSD transaction
     * {@value #TRANSACTION_ID}, program {@value #PROGRAM_NAME}.
     *
     * <p>One request is one execution of {@code COTRN01C}: the transaction id arrives in the path, the
     * 218-byte communication area, the key pressed and the twenty output items arrive in the body, and
     * the response carries the screen to paint, the program to go to next and the presentation
     * metadata. Nothing is retained between calls - no session, no server-side conversation, no
     * redirect (gate G37).
     *
     * <p><strong>This handler reads. It does not add.</strong> See the R-B block on this class: the
     * method name follows the source's function, the class name follows the build prompt, and the two
     * are allowed to disagree in public rather than be quietly reconciled.
     *
     * <p>An absent body is the {@code EIBCALEN = 0} state at {@code :94}: no communication area
     * travelled, so the program cannot know who called it and transfers to
     * {@value #SIGN_ON_PROGRAM}. That arm is reachable over HTTP for the same reason it is reachable
     * on a terminal.
     *
     * @param tranId  the transaction id being viewed - the {@code RIDFLD} of the read at
     *                {@code :217-224} and the value {@code TRNIDIN} carries; must not be {@code null}
     * @param request the inbound screen, or {@code null} for a cold start; validated against the
     *                symbolic map's declared widths
     * @return the outbound screen and its presentation metadata, never {@code null}
     * @throws NullPointerException     if {@code tranId} is {@code null}
     * @throws IllegalArgumentException if {@code tranId} is wider than {@code TRNIDIN}, or the body's
     *                                  {@code TRNIDIN} names a different transaction - each answered
     *                                  {@code 400} by {@code WebConfig.CobolErrorHandler} with no
     *                                  value echoed
     */
    @GetMapping(path = TRANSACTION_DETAIL_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScreenResponse<TransactionAddResponse> viewTransaction(
            @PathVariable(TRAN_ID_VARIABLE) String tranId,
            @Valid @RequestBody(required = false) TransactionAddRequest request) {

        Objects.requireNonNull(tranId, "A transaction id is required in the path: it is the RIDFLD of "
                + "the READ at app/cbl/COTRN01C.cbl:217-224 and the value TRNIDIN carries");

        ProgramState state = mainPara(bind(tranId, request));
        return ScreenResponse.of(state.response(), state.screenMetadata());
    }

    /**
     * Reconciles the URI's transaction id with the bound request, and refuses the two ways a caller
     * could otherwise reach a record the URI does not name.
     *
     * <h4>Why an over-width value is refused rather than moved</h4>
     * {@code MOVE} to a {@code PIC X(16)} field keeps the leading sixteen characters and discards the
     * rest, so a seventeen-character path id would have been truncated and <em>that</em> transaction
     * read - a URI addressing a record it does not name, and one no operator could have typed, because
     * a 3270 field physically cannot accept more characters than it declares. The COBOL move is
     * faithful for a value that fits; for one that does not there is nothing faithful to reproduce, so
     * the request is refused at the boundary before any padding and any repository call.
     *
     * <h4>The path is projected into both carriers of the key</h4>
     * This program reads the transaction id from two places, and which one it reads depends on the turn:
     * {@code CDEMO-CT01-TRN-SELECTED} on first entry [{@code :103-106}], the id the transaction-list
     * screen hands over when the operator marks a row, and {@code TRNIDINI} on re-entry [{@code :147},
     * {@code :217-224}]. Both are set from the path here, before a single line of source logic runs, so
     * the URI is the only statement of which record is read.
     *
     * <p>Leaving either carrier as the caller sent it would leave a second, independently
     * client-controlled identity. That is not hypothetical: with the extension naming one transaction and
     * the path naming another, first entry would read the extension's - a URI returning a record it does
     * not name - and with the extension blank, the path-driven immediate lookup the URI asks for would not
     * happen at all. Projecting both closes both.
     *
     * <p>No disagreement error is raised. {@code COTRN01C} has no such condition, so inventing one would
     * add a failure mode the legacy screen cannot produce; a client that echoes a painted screen agrees
     * with the path and sees no difference, and one that names a second transaction has that value
     * replaced rather than acted on. The blank-field branch at {@code :147} stays reachable, because a
     * path segment of percent-encoded spaces is a blank identifier and {@link #mainPara} is callable
     * directly with any buffer at all.
     *
     * @param tranId  the path variable; must not be {@code null}
     * @param request the bound body, or {@code null} for a cold start
     * @return the request to execute, with {@code TRNIDIN} and {@code CDEMO-CT01-TRN-SELECTED} both set
     *         from the path; never {@code null}
     * @throws IllegalArgumentException if the path value is wider than {@code TRNIDIN}
     */
    TransactionAddRequest bind(String tranId, TransactionAddRequest request) {
        if (tranId.length() > TransactionAddRequest.TRNIDIN_LENGTH) {
            throw new IllegalArgumentException("The transaction id in the path is " + tranId.length()
                    + " characters, but TRNIDIN is TRNIDINI PIC X("
                    + TransactionAddRequest.TRNIDIN_LENGTH + ") and TRAN-ID is PIC X("
                    + TransactionAddRequest.TRNIDIN_LENGTH + "). Padding it would keep the leading "
                    + TransactionAddRequest.TRNIDIN_LENGTH + " characters and read a different "
                    + "transaction than the one the URI names.");
        }

        // A cold start has no body at all, which is exactly EIBCALEN = 0: a fresh request carries no
        // communication area, so mainPara takes the :94 arm and transfers to the sign-on program.
        TransactionAddRequest received = request == null
                ? new TransactionAddRequest()
                : new TransactionAddRequest(request);

        // The identity, at TRNIDIN's declared PIC X(16) width - which is what the field holds on a
        // terminal and what a client echoing the painted screen sends back. The path has already been
        // required to fit, so this MOVE only pads.
        String identity = codec.movePicX(tranId, TransactionAddRequest.TRNIDIN_LENGTH);

        // :147, :217-224 read TRNIDINI on re-entry.
        received.setTrnidin(identity);

        // :103-106 read CDEMO-CT01-TRN-SELECTED on first entry. Written at Ct01Info's own declared width
        // for the same reason, and the extension's other items are left exactly as they arrived.
        received.getCt01Info().setTrnSelected(
                codec.movePicX(tranId, Ct01Info.TRN_SELECTED_LENGTH));
        return received;
    }

    // =============================================================================================
    // MAIN-PARA - app/cbl/COTRN01C.cbl:86-139.
    // =============================================================================================

    /**
     * {@code MAIN-PARA} - the program's entry point, lines 86 to 139.
     *
     * <p>Returns the terminal {@link ProgramState} rather than only the response, because three
     * observable things this program produces have no home in the 21-field payload: the
     * {@code MOVE -1 TO TRNIDINL} cursor request, which is {@code xxxL} metadata and must not be
     * smuggled into a projection of {@code xxxI} and {@code xxxO} items alone; the {@code DISPLAY} at
     * {@code :290}; and the working-storage flags a parity case asserts on. All three are reported on
     * the state, and {@link #viewTransaction(TransactionAddRequest)} projects
     * {@link ProgramState#response()}.
     *
     * <p>The branch structure is the source's, arm for arm and in the source's order:
     *
     * <ol>
     *   <li>{@code :94} {@code IF EIBCALEN = 0} - nothing was passed, so the program cannot know who
     *       called it and hands control to {@value #SIGN_ON_PROGRAM}. This arm transfers and never
     *       reaches the {@code EXEC CICS RETURN} at {@code :136}.</li>
     *   <li>{@code :99} {@code IF NOT CDEMO-PGM-REENTER} - a first entry. The output map is set to
     *       {@code LOW-VALUES}, the cursor is placed in the lookup field, and <strong>if the list
     *       screen handed a selected transaction id across, the lookup is performed
     *       immediately</strong> ({@code :103-108}); either way the screen is then sent
     *       ({@code :109}).</li>
     *   <li>{@code :110} otherwise a re-entry: receive the map ({@code :111}) and dispatch on
     *       {@code EIBAID} ({@code :112-132}) with {@code WHEN OTHER} last (gate G30).</li>
     * </ol>
     *
     * <p>The two sub-branches of the first-entry arm are both real and behave differently - arriving
     * from the transaction list with a selection performs the read and paints the detail, arriving
     * without one paints an empty screen - so both are covered by tests (gate G38).
     *
     * <p><strong>One call is one CICS task, and therefore one unit of work.</strong> The whole body
     * runs inside {@link DatasetUnitOfWork#execute(String, java.util.function.Supplier)}, which joins a
     * caller's boundary rather than nesting a second one. That is what lets {@code :275}'s
     * {@code UPDATE} option take a real record lock - {@link TransactionRepository} refuses to issue
     * {@code FOR UPDATE} outside a unit of work - and the commit on the way out is the task's implicit
     * syncpoint at {@code EXEC CICS RETURN}. The program contains no write of any kind, so there is
     * nothing for a rollback to back out and no arm of the source asks for one.
     *
     * @param request the inbound screen; must not be {@code null}
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     */
    public ProgramState mainPara(TransactionAddRequest request) {
        Objects.requireNonNull(request, "A request is required: COTRN01C is driven entirely by its "
                + "communication area, the EIBAID and the received map, all of which travel in it");

        // The argument is checked before the boundary opens: an argument defect is the caller's, not
        // the dataset's, and opening a transaction to reject one would take a connection from the pool
        // to accomplish nothing.
        return unitOfWork.execute(UNIT_OF_WORK_DESCRIPTION, () -> mainParaUnderLock(request));
    }

    /**
     * The body of {@code MAIN-PARA}, run inside the unit of work
     * {@link #mainPara(TransactionAddRequest)} opens.
     *
     * <p>Separate from the public method for one reason: {@link #readTransactFile(ProgramState)} takes
     * a record lock and refuses to run outside a unit of work, so the boundary has to be open before
     * the first statement of this method executes - and on the first-entry path with a selection
     * carried across, the read happens before the screen is ever sent. Keeping the boundary in the
     * caller also keeps this method a plain translation of lines 86 to 139, with no transaction
     * handling interleaved with the arms.
     *
     * @param request the inbound screen, already checked for {@code null}
     * @return the state at the moment the task returned to CICS or transferred, never {@code null}
     */
    private ProgramState mainParaUnderLock(TransactionAddRequest request) {
        ProgramState state = new ProgramState(codec);

        state.setErrFlagOff();                                                            // L88
        state.setUsrModifiedNo();                                                         // L89
        state.setMessage(spaces(WS_MESSAGE_LENGTH));                                      // L91
        state.response().setErrmsgo(spaces(ScreenField.ERRMSGO.payloadLength()));         // L92

        // L94 IF EIBCALEN = 0. No communication area travelled, so the screen cannot be painted for
        // anybody and the program abandons the transaction for the sign-on program.
        if (!request.hasNavigationContext()) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));           // L95
            returnToPrevScreen(state);                                                    // L96
            return state;
        }

        // L98 MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA. The 58-byte CDEMO-CT01-INFO
        // extension is copied with it, because in this program it is part of the same 01 group -
        // which is what makes the passed area 218 bytes rather than 160.
        state.setCommarea(request.getNavigationContext());
        state.setCt01Info(new Ct01Info(request.getCt01Info()));

        // L99 IF NOT CDEMO-PGM-REENTER - first entry.
        if (!state.commarea().isReenter()) {
            state.setCommarea(state.commarea().withPgmReenter());                         // L100
            moveLowValuesToOutputMap(state.response());                                   // L101
            state.moveMinusOneTo(ScreenField.TRNIDINO);                                   // L102

            // L103-L104 IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES.
            if (!isSpacesOrLowValues(state.ct01Info().getTrnSelected())) {
                // L105-L106 MOVE CDEMO-CT01-TRN-SELECTED TO TRNIDINI OF COTRN1AI. TRNIDINI and
                // TRNIDINO occupy the same bytes of the redefined map, so this is that move.
                state.response().setTrnidino(state.ct01Info().getTrnSelected());
                processEnterKey(state);                                                   // L107
            }

            sendTrnviewScreen(state);                                                     // L109
            returnToCics(state);                                                          // L136-139
            return state;
        }

        // L111 PERFORM RECEIVE-TRNVIEW-SCREEN.
        receiveTrnviewScreen(state, request);

        // L112 EVALUATE EIBAID - four named values then WHEN OTHER, in the source's order. The tests
        // are raw-byte equalities, exactly as the source's EVALUATE compares them: PF15 is a distinct
        // AID from PF3 here and takes the invalid-key arm, which is what the COBOL does.
        byte eibAid = eibAidOf(request.getAid());
        state.setResolvedAid(PfKeyResolver.resolve(eibAid));

        if (PfKeyResolver.isEnter(eibAid)) {                                              // L113
            processEnterKey(state);                                                       // L114
        } else if (PfKeyResolver.isPf3(eibAid)) {                                         // L115
            // L116 IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES.
            if (isSpacesOrLowValues(state.commarea().fromProgram())) {
                state.setCommarea(state.commarea().withToProgram(MAIN_MENU_PROGRAM));      // L117
            } else {
                state.setCommarea(state.commarea()
                        .withToProgram(state.commarea().fromProgram()));                   // L119-120
            }
            returnToPrevScreen(state);                                                     // L122
            return state;
        } else if (PfKeyResolver.isPf4(eibAid)) {                                          // L123
            clearCurrentScreen(state);                                                     // L124
        } else if (PfKeyResolver.isPf5(eibAid)) {                                          // L125
            state.setCommarea(state.commarea().withToProgram(TRANSACTION_LIST_PROGRAM));    // L126
            returnToPrevScreen(state);                                                     // L127
            return state;
        } else {                                                                           // L128
            state.setErrFlagOn();                                                          // L129
            state.setMessage(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    WS_MESSAGE_LENGTH));                                                   // L130
            sendTrnviewScreen(state);                                                      // L131
        }

        returnToCics(state);                                                               // L136-139
        return state;
    }

    // =============================================================================================
    // PROCESS-ENTER-KEY - app/cbl/COTRN01C.cbl:144-192.
    // =============================================================================================

    /**
     * {@code PROCESS-ENTER-KEY} - lines 144 to 192: validate the entered transaction id, read the
     * record, paint the detail.
     *
     * <p>The paragraph is three consecutive blocks and the shape matters:
     *
     * <ol>
     *   <li>{@code :146-156} an {@code EVALUATE TRUE} whose only named arm rejects a blank lookup
     *       key. Both arms place the cursor in {@code TRNIDIN} - the rejecting arm at {@code :151}
     *       and the {@code WHEN OTHER} arm at {@code :154} - so the cursor lands there whatever
     *       happens, which is consistent with {@code TRNIDIN} being the field declared {@code IC} in
     *       {@code app/bms/COTRN01.bms:85}.</li>
     *   <li>{@code :158-174} {@code IF NOT ERR-FLG-ON}: blank the thirteen detail fields, move the
     *       entered id into the record key and read.</li>
     *   <li>{@code :176-192} {@code IF NOT ERR-FLG-ON} <strong>again</strong>. This is a second,
     *       separate test and not an {@code ELSE}, which is exactly what makes the paragraph correct
     *       after the read has already rejected: a {@code NOTFND} read raises the flag and sends the
     *       rejection, and this block then does not paint stale detail over it.</li>
     * </ol>
     *
     * <p>{@code TRNIDINI} is read from the response, not the request, because
     * {@code 01 COTRN1AO REDEFINES COTRN1AI} makes the two views one buffer: the {@code xxxI} item
     * and the {@code xxxO} item of every field sit at the same offset, verified field by field
     * against {@code app/cpy-bms/COTRN01.CPY}. That single fact is what lets the first-entry arm at
     * {@code :105} write a value the enter-key processing then reads.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void processEnterKey(ProgramState state) {
        requireState(state);

        // L146 EVALUATE TRUE - first match wins, WHEN OTHER last.
        if (isSpacesOrLowValues(state.response().getTrnidino())) {                          // L147
            state.setErrFlagOn();                                                           // L148
            state.setMessage(codec.movePicX(MSG_TRAN_ID_EMPTY, WS_MESSAGE_LENGTH));         // L149-150
            state.moveMinusOneTo(ScreenField.TRNIDINO);                                     // L151
            sendTrnviewScreen(state);                                                       // L152
        } else {                                                                            // L153
            state.moveMinusOneTo(ScreenField.TRNIDINO);                                     // L154
            // L155 CONTINUE - the arm exists to place the cursor and nothing else.
        }

        // L158 IF NOT ERR-FLG-ON.
        if (!state.errFlagOn()) {
            blankDetailFields(state.response());                                            // L159-171
            // L172 MOVE TRNIDINI OF COTRN1AI TO TRAN-ID. TRAN-ID is PIC X(16) and TRNIDINI is
            // PIC X(16), so this is a same-width move; the rule is applied anyway, because a payload
            // that arrived short must not shorten the key.
            state.setTranId(codec.movePicX(state.response().getTrnidino(),
                    TranRecord.TRAN_ID_LENGTH));
            readTransactFile(state);                                                        // L173
        }

        // L176 IF NOT ERR-FLG-ON - a second test, not an ELSE.
        if (!state.errFlagOn()) {
            TranRecord record = requireRecordRead(state.tranRecord());

            // L177 MOVE TRAN-AMT TO WS-TRAN-AMT - nine integer digits into eight, high-order digit
            // discarded, no ON SIZE ERROR. See WS_TRAN_AMT_INTEGER_DIGITS.
            state.setTranAmtEdited(editedTranAmt(record.tranAmt()));

            state.response().setTrnido(record.tranId());                                    // L178
            state.response().setCardnumo(record.tranCardNum());                              // L179
            state.response().setTtypcdo(record.tranTypeCd());                                // L180
            // L181 TRAN-CAT-CD is PIC 9(04): the stored digits move, leading zeros and all.
            state.response().setTcatcdo(record.tranCatCdImage());
            state.response().setTrnsrco(record.tranSource());                                // L182
            state.response().setTrnamto(state.tranAmtEdited());                               // L183
            // L184 TRAN-DESC is PIC X(100) and TDESCO is PIC X(60): 40 characters are lost on the
            // RIGHT, which is the alphanumeric move rule and is applied by the setter.
            state.response().setTdesco(record.tranDesc());
            // L185-L186 the two timestamps are PIC X(26) into PIC X(10): the leading date survives
            // and the time is truncated away, again on the right.
            state.response().setTorigdto(record.tranOrigTs());
            state.response().setTprocdto(record.tranProcTs());
            // L187 TRAN-MERCHANT-ID is PIC 9(09): the stored digits move.
            state.response().setMido(record.tranMerchantIdImage());
            state.response().setMnameo(record.tranMerchantName());                           // L188
            state.response().setMcityo(record.tranMerchantCity());                            // L189
            state.response().setMzipo(record.tranMerchantZip());                              // L190

            sendTrnviewScreen(state);                                                        // L191
        }
    }

    /**
     * The record the read at {@code :173} returned, for the block at {@code :176-192}.
     *
     * <p>Reaching {@code :176} with {@code ERR-FLG-OFF} implies the read succeeded, because the only
     * route past {@code :158} without a read is one that has already raised the flag, and every
     * non-{@code NORMAL} arm of {@code READ-TRANSACT-FILE} raises it. The absent case is therefore
     * unreachable through {@link #mainPara(TransactionAddRequest)} - and it is still checked, and
     * checked through a parameter rather than by reading the state directly, so that a test can
     * demonstrate the failure instead of taking it on trust.
     *
     * @param record the record the read reported; must not be {@code null}
     * @return the record
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if it is empty, naming what that would mean
     */
    public static TranRecord requireRecordRead(Optional<TranRecord> record) {
        Objects.requireNonNull(record, "An absent record is an empty Optional, never null");
        return record.orElseThrow(() -> new IllegalStateException(
                "app/cbl/COTRN01C.cbl:176 was reached with ERR-FLG-OFF and no TRAN-RECORD in hand. "
                + "That combination cannot arise from the program: :158 only skips the read when the "
                + "flag is already on, and every non-NORMAL arm of READ-TRANSACT-FILE (:280-296) "
                + "turns it on. Reaching it means the read outcome and the error flag have been set "
                + "independently of one another"));
    }

    // =============================================================================================
    // READ-TRANSACT-FILE - app/cbl/COTRN01C.cbl:267-296.
    // =============================================================================================

    /**
     * {@code READ-TRANSACT-FILE} - lines 267 to 296: the keyed read of {@value #TRANSACT_FILE_NAME}
     * and the three-armed evaluation of its response.
     *
     * <pre>{@code
     *  EXEC CICS READ
     *       DATASET   (WS-TRANSACT-FILE)
     *       INTO      (TRAN-RECORD)
     *       LENGTH    (LENGTH OF TRAN-RECORD)
     *       RIDFLD    (TRAN-ID)
     *       KEYLENGTH (LENGTH OF TRAN-ID)
     *       UPDATE
     *       RESP      (WS-RESP-CD)
     *       RESP2     (WS-REAS-CD)
     *  END-EXEC.
     * }</pre>
     *
     * <p><strong>The source specifies {@code UPDATE}, so this takes the lock.</strong>
     * {@link TransactionRepository#readForUpdateByTranId(String)} is called, not the plain read:
     * {@code UPDATE} at {@code :275} requests the record under the lock
     * {@code UPDATEMODEL(LOCKING)} declares for this file, and which read a program issues is
     * observable behaviour rather than an optimisation - a locking read serialises against a concurrent
     * updater of the same record and a plain read does not. Reproducing the option the source states is
     * the whole of the instruction; deciding that the lock is unnecessary because this program never
     * writes would be substituting a judgement for the source's, and would leave the two systems
     * behaving differently under exactly the concurrency the {@code UPDATE} option exists to handle.
     *
     * <p>The lock needs a unit of work to be held in, and it has one:
     * {@link #mainPara(TransactionAddRequest)} opens the boundary around the whole execution, which is
     * what a CICS task is. A caller reaching this method directly - a parity case, for instance - must
     * do the same, because the repository refuses to issue {@code FOR UPDATE} with nothing to hold the
     * lock.
     *
     * <p>The three arms are the source's, in the source's order (gate G30, gate G47):
     * {@code DFHRESP(NORMAL)} continues; {@code DFHRESP(NOTFND)} reports
     * {@value #MSG_TRAN_ID_NOT_FOUND}; {@code WHEN OTHER} displays the two response codes and reports
     * {@value #MSG_UNABLE_TO_LOOKUP}. A duplicate and an end-of-file both reach {@code WHEN OTHER},
     * because the source enumerates neither - and an unexpected condition belongs in the arm the
     * source wrote for unexpected conditions.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException  if {@code state} is {@code null}
     * @throws IllegalStateException if no unit of work is open, because {@code :275}'s {@code UPDATE}
     *                               option cannot take a lock that nothing would hold
     */
    public void readTransactFile(ProgramState state) {
        requireState(state);

        // L269-278, UPDATE included: the locking read, inside the unit of work mainPara opened.
        ReadResult result = transactionRepository.readForUpdateByTranId(state.tranId());
        state.setReadResult(result);
        // RESP(WS-RESP-CD). Where the repository reports no CICS condition - an artefact of the JDBC
        // substitution, never of CICS itself - the sentinel is stored rather than the item being left
        // at the zero its VALUE clause gave it. Leaving it at zero would be indistinguishable from a
        // reported DFHRESP(NORMAL), so the DISPLAY below would render RESP: 000000000 for a read that
        // did not work. FileStatus.RESP_NOT_REPORTED cannot collide with any DFHRESP value, and the
        // renderer shows it as asterisks rather than as a number it is not.
        state.setRespCd(result.cicsResp().orElse(FileStatus.RESP_NOT_REPORTED));
        state.setReasCd(result.cicsResp2());

        // L280 EVALUATE WS-RESP-CD.
        if (result.isFound()) {                                                              // L281
            return;                                                                          // L282
        }
        if (result.isNotFound()) {                                                           // L283
            rejectAndSend(state, MSG_TRAN_ID_NOT_FOUND);                                     // L284-288
            return;
        }
        // L289 WHEN OTHER.
        display(state, DISPLAY_RESP_PREFIX + respImage(state.respCd())
                + DISPLAY_REAS_PREFIX + respImage(state.reasCd()));                          // L290
        rejectAndSend(state, MSG_UNABLE_TO_LOOKUP);                                          // L291-295
    }

    /**
     * A {@code PIC S9(09) COMP} response or reason code as {@code DISPLAY} renders it - or, where none
     * was reported, as {@value FileStatus#RESP_NOT_REPORTED}'s width-preserving image.
     *
     * <p>{@code DISPLAY 'RESP:' WS-RESP-CD 'REAS:' WS-REAS-CD} ({@code :290}) concatenates its operands
     * at their declared widths, so both branches here are exactly
     * {@value #WS_RESP_CD_DIGITS} characters and the line's shape is the source's either way.
     *
     * <p>The unreported case cannot go through {@link FixedWidthCodec#movePic9(long, int)} at all:
     * {@link FileStatus#RESP_NOT_REPORTED} is negative and a {@code PIC 9} receiver has no image for a
     * negative value, so the codec refuses it. That refusal is the reason this method exists rather than
     * the two operands being rendered inline - the alternative would be storing zero, which is
     * {@link FileStatus#NORMAL} and would report a failed command as a successful one.
     *
     * @param code the value held in {@code WS-RESP-CD} or {@code WS-REAS-CD}
     * @return exactly {@value #WS_RESP_CD_DIGITS} characters
     */
    private String respImage(int code) {
        return FileStatus.respReported(code)
                ? codec.movePic9(code, WS_RESP_CD_DIGITS)
                : FileStatus.respNotReportedImage(WS_RESP_CD_DIGITS);
    }

    /**
     * The four statements the rejecting arms share - {@code :148-152}, {@code :284-288} and
     * {@code :291-295} - in the source's order: raise the flag, set the message, place the cursor in
     * the lookup field, send the screen.
     *
     * <p>Named once because the three sites are identical apart from the text, and because the order
     * is part of the behaviour: the message must be in {@code WS-MESSAGE} before
     * {@link #sendTrnviewScreen(ProgramState)} copies it into {@code ERRMSGO}.
     *
     * @param state   the per-request working storage; must not be {@code null}
     * @param message the literal the source moves into {@code WS-MESSAGE}; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void rejectAndSend(ProgramState state, String message) {
        requireState(state);
        Objects.requireNonNull(message, "A rejection carries the message text the source moves");

        state.setErrFlagOn();
        state.setMessage(codec.movePicX(message, WS_MESSAGE_LENGTH));
        state.moveMinusOneTo(ScreenField.TRNIDINO);
        sendTrnviewScreen(state);
    }

    // =============================================================================================
    // The screen and navigation paragraphs - app/cbl/COTRN01C.cbl:197-262 and :301-326.
    // =============================================================================================

    /**
     * {@code RETURN-TO-PREV-SCREEN} - lines 197 to 208: hand control to another program.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code :205-208} transfers and never comes back, which is why every caller of this method
     * returns immediately afterwards and why the {@code EXEC CICS RETURN} at {@code :136} is
     * unreachable from these arms. In Java the response names the program to go to and the client
     * issues the follow-up call, so there is no server-side forward and no redirect (gate G40, gate
     * G37).
     *
     * <p>Three moves precede the transfer and all three are part of the contract the target program
     * reads: {@code CDEMO-FROM-TRANID} becomes {@value #TRANSACTION_ID} and
     * {@code CDEMO-FROM-PROGRAM} becomes {@value #PROGRAM_NAME} so the target knows who sent it, and
     * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} at {@code :204} puts the area back into
     * {@code CDEMO-PGM-ENTER} so the target treats its own first call as a first entry.
     *
     * <p>{@code :199} defaults an empty target to {@value #SIGN_ON_PROGRAM}. That is a second,
     * independent default from the one at {@code :95}: this one catches a re-entry whose
     * {@code CDEMO-TO-PROGRAM} arrived blank, and it is reachable from the PF3 arm when
     * {@code CDEMO-FROM-PROGRAM} carried a caller whose name was itself blank.
     *
     * <p>No map is named on this path. The source sends no screen before transferring - the target
     * paints its own - so the response's mapset and map are blanked rather than left naming this
     * screen, which a client would otherwise repaint. {@code COTRN01C} never writes
     * {@code CDEMO-LAST-MAP} or {@code CDEMO-LAST-MAPSET} either, so those two communication-area
     * items are echoed exactly as they arrived.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToPrevScreen(ProgramState state) {
        requireState(state);

        // L199-L201 IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES.
        if (isSpacesOrLowValues(state.commarea().toProgram())) {
            state.setCommarea(state.commarea().withToProgram(SIGN_ON_PROGRAM));               // L200
        }
        state.setCommarea(state.commarea()
                .withFromTranid(TRANSACTION_ID)                                              // L202
                .withFromProgram(PROGRAM_NAME)                                               // L203
                .withPgmEnter());                                                            // L204

        // L205-L208 EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA).
        state.response().setNextProgram(state.commarea().toProgram());
        state.response().setNextMapset(spaces(TransactionAddResponse.NEXT_MAPSET_LENGTH));
        state.response().setNextMap(spaces(TransactionAddResponse.NEXT_MAP_LENGTH));
        echoPassedCommarea(state);
        state.markTransferred();
    }

    /**
     * {@code SEND-TRNVIEW-SCREEN} - lines 213 to 225: paint the screen.
     *
     * <p><strong>This paragraph is not terminal in this program.</strong> Unlike its counterpart in
     * some sibling screens it ends with no {@code GO TO}, so control returns to the statement after
     * the {@code PERFORM}. That is what makes the first-entry arm send the screen twice - once inside
     * {@link #processEnterKey(ProgramState)} and again at {@code :109} - and both sends are counted
     * on the state rather than collapsed, because the source really issues two. They are idempotent:
     * the header is rebuilt from the same pinned clock and the same message is copied again.
     *
     * <p>{@code MOVE WS-MESSAGE TO ERRMSGO} at {@code :217} moves {@code PIC X(80)} into
     * {@code PIC X(78)}, so the last two characters are lost on the right. None of this program's
     * three literals, nor {@code CCDA-MSG-INVALID-KEY} at {@code PIC X(50)}, is long enough to lose a
     * character - and the rule is applied by the setter regardless, so a longer message could never
     * shift the field.
     *
     * <p>{@code SEND MAP('COTRN1A') MAPSET('COTRN01') FROM(COTRN1AO) ERASE CURSOR} names the map the
     * response reports, and {@code CURSOR} is what gives the {@code MOVE -1 TO TRNIDINL} sites their
     * meaning: the cursor lands in the field whose length item holds {@code -1}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void sendTrnviewScreen(ProgramState state) {
        requireState(state);

        populateHeaderInfo(state);                                                            // L215
        state.response().setErrmsgo(state.message());                                         // L217

        // The CSSETATY decision for the lookup field. See lookupFieldHighlight: this program does
        // not copy CSSETATY, the decision is "touch nothing" in every state, and applying it writes
        // no byte - which is the point of resolving it rather than assuming it (gate G38).
        state.setLookupFieldHighlight(lookupFieldHighlight(state.commarea().isReenter()));
        state.response().applyHighlight(ScreenField.TRNIDINO, state.lookupFieldHighlight());

        // L219-L225 EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01') ... ERASE CURSOR.
        state.response().setNextMapset(MAPSET_NAME);
        state.response().setNextMap(MAP_NAME);
        state.recordScreenSent();
    }

    /**
     * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - lines 136 to 139.
     *
     * <p>{@code TRANSID('CT01')} routes the operator's next input straight back to this transaction,
     * and {@code app/csd/CARDDEMO.CSD:429-430} binds {@code CT01} to {@code COTRN01C} - so the
     * response's next program is this program, not a different one. The communication area travels
     * back in the payload with {@code CDEMO-PGM-CONTEXT} already at re-enter, which is how the next
     * call knows to read the map rather than paint it. No session, no redirect (gate G37).
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void returnToCics(ProgramState state) {
        requireState(state);

        state.response().setNextProgram(PROGRAM_NAME);
        echoPassedCommarea(state);
        state.markReturned();
    }

    /**
     * {@code COMMAREA(CARDDEMO-COMMAREA)} - the 218 bytes both the {@code XCTL} at {@code :207} and
     * the {@code RETURN} at {@code :138} pass: the 160-byte {@code CARDDEMO-COMMAREA} plus the
     * 58-byte {@code CDEMO-CT01-INFO} extension this program appends at {@code :53-61}.
     *
     * <p>The extension is copied into the response rather than shared with the request, so a client
     * that keeps the object it sent cannot observe it changing underneath. {@link NavigationContext}
     * needs no copy: it is an immutable record.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void echoPassedCommarea(ProgramState state) {
        requireState(state);

        state.response().setNavigationContext(state.commarea());
        state.response().setCt01Info(new Ct01Info(state.ct01Info()));
    }

    /**
     * {@code RECEIVE-TRNVIEW-SCREEN} - lines 230 to 238.
     *
     * <p>{@code EXEC CICS RECEIVE MAP('COTRN1A') MAPSET('COTRN01') INTO(COTRN1AI)} fills the symbolic
     * map from the inbound datastream. All 21 received values are copied into the
     * <em>response</em>, because {@code 01 COTRN1AO REDEFINES COTRN1AI} makes the two views one
     * buffer: each field's {@code xxxI} item and {@code xxxO} item occupy the same bytes, verified
     * offset by offset against {@code app/cpy-bms/COTRN01.CPY}. That is what lets this program read
     * {@code TRNIDINI} and write {@code TRNIDO} with no copying between two objects.
     *
     * <p>{@link TransactionAddRequest#toFieldImages(FixedWidthCodec)} supplies the values already
     * reshaped to their declared widths, so a payload that arrived short or absent becomes the blank
     * field COBOL would hold rather than a {@code null}.
     *
     * <p>The command captures {@code RESP} and {@code RESP2} and the program never tests either, so
     * no branch is invented here: the two codes are recorded as a successful receive, because the
     * payload has already arrived.
     *
     * @param state   the per-request working storage; must not be {@code null}
     * @param request the inbound screen; must not be {@code null}
     * @throws NullPointerException  if either argument is {@code null}
     * @throws IllegalStateException if the request and response projections of the symbolic map
     *                               disagree about how many fields it has
     */
    public void receiveTrnviewScreen(ProgramState state, TransactionAddRequest request) {
        requireState(state);
        Objects.requireNonNull(request, "A request is required: it carries the received map");

        Map<String, String> received = request.toFieldImages(codec);
        List<String> names = TransactionAddRequest.PAYLOAD_FIELD_NAMES;
        ScreenField[] fields = ScreenField.values();
        requireMatchingProjections(names.size(), fields.length);
        for (int field = 0; field < fields.length; field++) {
            state.response().setPayload(fields[field], received.get(names.get(field)));
        }

        state.setRespCd(FileStatus.NORMAL);
        state.setReasCd(FileStatus.NO_REASON_CODE);
    }

    /**
     * Verifies that the two projections of {@code app/cpy-bms/COTRN01.CPY} agree about how many
     * fields the symbolic map has, before the received values are copied across by ordinal.
     *
     * <p>The copybook declares 21 {@code xxxI} items and 21 {@code xxxO} items, so the two counts are
     * the same number twice. If they ever differ, one projection has drifted and copying by ordinal
     * would put a value into the wrong field rather than fail - which is a silent parity defect of
     * exactly the kind the harness exists to catch. Extracted so the rejecting path is reachable from
     * a test rather than merely asserted.
     *
     * @param requestFieldCount  how many field names the request projects
     * @param responseFieldCount how many payload fields the response projects
     * @throws IllegalStateException if the two counts differ
     */
    public static void requireMatchingProjections(int requestFieldCount, int responseFieldCount) {
        if (requestFieldCount != responseFieldCount) {
            throw new IllegalStateException("The request projects " + requestFieldCount
                    + " field(s) of the symbolic map and the response projects " + responseFieldCount
                    + "; both are app/cpy-bms/COTRN01.CPY, which declares "
                    + TransactionAddRequest.PAYLOAD_FIELD_COUNT + " xxxI items and the same number of "
                    + "xxxO items, so one projection has drifted");
        }
    }

    /**
     * {@code POPULATE-HEADER-INFO} - lines 243 to 262: the six header fields every CardDemo screen
     * carries.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} at {@code :245} is read from the
     * injected {@link Clock} and never from the wall clock, so a parity case can pin the instant and
     * compare the two header images byte for byte.
     *
     * <p>{@code CCDA-TITLE01} and {@code CCDA-TITLE02} come from {@link ScreenTitles} at their
     * declared {@code PIC X(40)}. Note that {@link ScreenTitles#CCDA_THANK_YOU} - also
     * {@code PIC X(40)} - is a different literal from {@link SystemMessages#CCDA_MSG_THANK_YOU} at
     * {@code PIC X(50)}, and neither is used by this program; they are named here only so that a
     * later reader does not substitute one for the other.
     *
     * <p>The date is composed as {@code MM/DD/YY} from three separate moves at {@code :252-254},
     * where {@code WS-CURDATE-YEAR(3:2)} takes the last two digits of the four-digit year, and the
     * time as {@code HH:MM:SS} at {@code :258-260}. Both group items are assembled by
     * {@link DateHeader}, which owns the separators the copybook declares.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void populateHeaderInfo(ProgramState state) {
        requireState(state);

        DateHeader header = DateHeader.from(codec, clock);                                    // L245
        state.setDateHeader(header);

        state.response().setTitle01o(ScreenTitles.CCDA_TITLE01);                              // L247
        state.response().setTitle02o(ScreenTitles.CCDA_TITLE02);                              // L248
        state.response().setTrnnameo(TRANSACTION_ID);                                         // L249
        state.response().setPgmnameo(PROGRAM_NAME);                                           // L250
        state.response().setCurdateo(header.wsCurdateMmDdYy());                                // L252-256
        state.response().setCurtimeo(header.wsCurtimeHhMmSs());                                // L258-262
    }

    /**
     * {@code CLEAR-CURRENT-SCREEN} - lines 301 to 304, the PF4 arm: blank everything and repaint.
     *
     * <p>Two performs and nothing else. The error flag is deliberately left alone, so a screen
     * cleared after a rejection keeps {@code ERR-FLG-OFF} - which it already had, because the PF4 arm
     * is only reached from a re-entry that began with {@code SET ERR-FLG-OFF} at {@code :88}.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void clearCurrentScreen(ProgramState state) {
        requireState(state);

        initializeAllFields(state);                                                           // L303
        sendTrnviewScreen(state);                                                             // L304
    }

    /**
     * {@code INITIALIZE-ALL-FIELDS} - lines 309 to 326.
     *
     * <p>The cursor goes to {@code TRNIDIN} ({@code :311}), then one {@code MOVE SPACES} blanks
     * fifteen receivers in a single statement ({@code :312-326}): the lookup field, the thirteen
     * detail fields, and {@code WS-MESSAGE}. {@code ERRMSGI} is <strong>not</strong> among them - the
     * message is cleared at its source instead, and {@link #sendTrnviewScreen(ProgramState)} then
     * copies the now-blank {@code WS-MESSAGE} into {@code ERRMSGO}, which is what actually clears the
     * error line.
     *
     * @param state the per-request working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void initializeAllFields(ProgramState state) {
        requireState(state);

        state.moveMinusOneTo(ScreenField.TRNIDINO);                                           // L311
        state.response().setTrnidino(spaces(ScreenField.TRNIDINO.payloadLength()));            // L312
        blankDetailFields(state.response());                                                  // L313-325
        state.setMessage(spaces(WS_MESSAGE_LENGTH));                                          // L326
    }

    // =============================================================================================
    // The thirteen detail fields, and the two group moves that act on them.
    // =============================================================================================

    /**
     * The thirteen fields the source blanks in one statement at {@code :159-171} and again at
     * {@code :313-325}, in the source's listed order.
     *
     * <p>{@code TRNIDIN} is deliberately absent: the lookup field is <em>not</em> blanked at
     * {@code :159-171}, because the value just typed into it is the key about to be read, and blanking
     * it there would erase the key before the read. {@code :312} blanks it separately, on the PF4
     * path, where erasing it is the intent. {@code ERRMSG} is absent for the same kind of reason - the
     * message is cleared at its source, {@code WS-MESSAGE}, and copied from there.
     *
     * <p>Immutable, so publishing it introduces no mutable static state (practice B9, gate G53).
     */
    public static final List<ScreenField> DETAIL_FIELDS = List.of(
            ScreenField.TRNIDO, ScreenField.CARDNUMO, ScreenField.TTYPCDO, ScreenField.TCATCDO,
            ScreenField.TRNSRCO, ScreenField.TRNAMTO, ScreenField.TDESCO, ScreenField.TORIGDTO,
            ScreenField.TPROCDTO, ScreenField.MIDO, ScreenField.MNAMEO, ScreenField.MCITYO,
            ScreenField.MZIPO);

    /**
     * {@code MOVE SPACES TO} the {@linkplain #DETAIL_FIELDS thirteen detail fields} - {@code :159-171}
     * and {@code :313-325}.
     *
     * <p>This is the unconditional figurative-constant fill, not the alphanumeric move rule: each
     * field becomes exactly its declared width in spaces.
     *
     * @param response the output map; must not be {@code null}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static void blankDetailFields(TransactionAddResponse response) {
        Objects.requireNonNull(response, "An output map is required to blank its detail fields");
        for (ScreenField field : DETAIL_FIELDS) {
            response.setPayload(field, spaces(field.payloadLength()));
        }
    }

    /**
     * {@code MOVE LOW-VALUES TO COTRN1AO} - {@code :101}, the first-entry reset of the whole 575-byte
     * output map.
     *
     * <p>Every one of the 21 payload items becomes its declared width in {@link #LOW_VALUE} bytes, and
     * every attribute quad returns to
     * {@link TransactionAddResponse.AttributeQuad#defaults() its no-change default} - whose four items
     * are {@code DFHDFCOL}, itself {@code X'00'}, so the default quad <em>is</em> the low-values
     * state of the four attribute bytes.
     *
     * <p><strong>Nulls, not spaces.</strong> BMS omits a null field from the outbound datastream and
     * erases a field that holds spaces, so the two produce different screens as well as different
     * bytes. The header fields and the message are written over these nulls immediately afterwards;
     * the fourteen detail and lookup fields keep them on a first entry with no selection, which is
     * why that screen shows the map's own {@code INITIAL} values.
     *
     * <p>The three navigation items are untouched, because they are not part of {@code COTRN1AO} -
     * they are this class's projection of the {@code XCTL} and the {@code RETURN}.
     *
     * @param response the output map; must not be {@code null}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static void moveLowValuesToOutputMap(TransactionAddResponse response) {
        Objects.requireNonNull(response, "An output map is required to move LOW-VALUES into it");
        for (ScreenField field : ScreenField.values()) {
            response.setPayload(field, lowValues(field.payloadLength()));
            response.setAttributes(field, TransactionAddResponse.AttributeQuad.defaults());
        }
    }

    // =============================================================================================
    // Figurative constants and the four comparisons against them.
    // =============================================================================================

    /**
     * The COBOL figurative constant {@code SPACES} sized to a field.
     *
     * @param width the field width in characters, zero or more
     * @return a string of exactly {@code width} spaces
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String spaces(int width) {
        return String.valueOf(SPACE).repeat(width);
    }

    /**
     * The COBOL figurative constant {@code LOW-VALUES} sized to a field.
     *
     * <p>Kept beside {@link #spaces(int)} on purpose: the two are different bytes, this program
     * depends on the difference at {@code :101} and {@code :312}, and a reader comparing the pair
     * should not have to look in two places to see that.
     *
     * @param width the field width in characters, zero or more
     * @return a string of exactly {@code width} {@link #LOW_VALUE} characters
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String lowValues(int width) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        return ScreenFieldImage.unpainted(width);
    }

    /**
     * {@code IF <item> = SPACES OR LOW-VALUES} - the one predicate behind all four figurative-constant
     * comparisons in the program: {@code :116} on {@code CDEMO-FROM-PROGRAM}, {@code :147} on
     * {@code TRNIDINI}, {@code :199} on {@code CDEMO-TO-PROGRAM}, and - negated - {@code :103} on
     * {@code CDEMO-CT01-TRN-SELECTED}, whose {@code NOT = SPACES AND LOW-VALUES} is this condition's
     * exact complement.
     *
     * <p>The COBOL relation is two whole-item comparisons joined by {@code OR}: the item equals
     * {@code SPACES} - every byte a space - or it equals {@code LOW-VALUES} - every byte a null. It is
     * <strong>not</strong> "every byte is either a space or a null". A field holding eight spaces
     * followed by eight nulls equals neither figurative constant, so the COBOL treats it as a real
     * value; this method returns {@code false} for it, as the COBOL does.
     *
     * <p><strong>That is why {@link Ct01Info#hasSelection()} is not called at {@code :103}.</strong>
     * The DTO's helper answers a related but looser question - "does any byte differ from both a space
     * and a null" - and the two answers agree everywhere except that mixed case, where the DTO reports
     * no selection and the COBOL sees one. The difference is recorded here rather than absorbed
     * silently (practice B4); it is the exact relation that governs.
     *
     * <p>An absent or empty value is treated as {@code SPACES}: a COBOL alphanumeric item has no
     * absent state, and a blank field is the state a missing payload member denotes.
     *
     * @param value the item to test, possibly {@code null}
     * @return {@code true} when the item is entirely spaces or entirely low-values
     */
    public static boolean isSpacesOrLowValues(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != SPACE) {
                allSpaces = false;
            }
            if (character != LOW_VALUE) {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    // =============================================================================================
    // EIBAID, the edited amount, and the CSSETATY decision.
    // =============================================================================================

    /**
     * The raw {@code EIBAID} byte the {@code EVALUATE} at {@code :112} compares against.
     *
     * <p>{@link TransactionAddRequest#getAid()} carries one character, because that is what
     * {@code EIBAID} is and what every {@code DFHAID} token holds; its numeric value <em>is</em> the
     * attention-identifier byte, so {@code DFHENTER} travels as {@code U+007D} and {@code DFHPF3} as
     * {@code U+00F3}. Nothing is resolved, folded or defaulted on the way through: this is a cast, and
     * the four tests that follow it are byte equalities exactly as the source's {@code EVALUATE}
     * compares them.
     *
     * <p>An absent or empty value becomes {@link CicsAid#DFHNULL}, the AID CICS reports when no key
     * raised the interrupt. {@code DFHNULL} matches none of the program's four named values and so
     * takes the {@code WHEN OTHER} arm, which is where an unrecognised key belongs.
     *
     * <h4>Why a character above {@code U+00FF} is refused</h4>
     * {@code EIBAID} is one byte, and every {@code DFHAID} token is a character whose code point is
     * that byte - so the whole of the AID space is {@code U+0000} to {@code U+00FF}. A narrowing cast
     * of anything wider keeps only the low eight bits, which means a character that is not an AID at
     * all would be read as one: {@code U+01F3} and {@code U+00F3} both narrow to {@code 0xF3}, and
     * {@code 0xF3} is {@code DFHPF3}, the key this program transfers on at {@code :115}. A caller
     * sending the first could reach the PF3 arm without pressing PF3. The value is therefore refused
     * rather than folded, which is the same rule the sibling screens apply to their numeric AID
     * parameters over the range {@code 0}-{@code 255}.
     *
     * <p>A value longer than one character is likewise refused: {@code aid} projects a one-byte item,
     * and a surrogate pair - the only way a Java {@code String} carries a code point above
     * {@code U+FFFF} - is two characters of which the first alone is meaningless.
     *
     * @param aid the one-character attention identifier from the payload, possibly {@code null}
     * @return the raw {@code EIBAID} byte
     * @throws IllegalArgumentException if {@code aid} is longer than
     *                                  {@value TransactionAddRequest#AID_LENGTH} character or its
     *                                  character is above {@code U+00FF}
     */
    public static byte eibAidOf(String aid) {
        if (aid == null || aid.isEmpty()) {
            return CicsAid.DFHNULL;
        }
        if (aid.length() > TransactionAddRequest.AID_LENGTH) {
            throw new IllegalArgumentException("The attention identifier is " + aid.length()
                    + " characters, but EIBAID is one byte and every DFHAID token holds exactly one "
                    + "character. Send a single character whose code point is the AID byte.");
        }
        char aidCharacter = aid.charAt(0);
        if (aidCharacter > MAX_AID_CODE_POINT) {
            throw new IllegalArgumentException("The attention identifier is a character above "
                    + "U+00FF, but EIBAID is one byte, so the whole of the AID space is U+0000 to "
                    + "U+00FF. Narrowing it would keep only the low eight bits and could match a "
                    + "named key the terminal never presented.");
        }
        return (byte) aidCharacter;
    }

    /**
     * {@code MOVE TRAN-AMT TO WS-TRAN-AMT} followed by
     * {@code MOVE WS-TRAN-AMT TO TRNAMTI} - {@code :177} and {@code :183}, rendered as the twelve
     * characters {@code PIC +99999999.99} produces.
     *
     * <p>Two COBOL rules apply in this order and both are visible in the result:
     *
     * <ol>
     *   <li>the sending {@code PIC S9(09)V99} has nine integer digits and the receiver has
     *       {@value #WS_TRAN_AMT_INTEGER_DIGITS}, so the two are aligned on the decimal point and the
     *       high-order digit that does not fit is discarded. There is no {@code ON SIZE ERROR} phrase
     *       anywhere in the program, so nothing is raised;</li>
     *   <li>the edited picture then prints a fixed sign, {@value #WS_TRAN_AMT_INTEGER_DIGITS}
     *       unsuppressed integer digits, an actual decimal point and {@value #WS_TRAN_AMT_SCALE} more
     *       digits - twelve characters, never fewer and never more.</li>
     * </ol>
     *
     * <p>Worked examples: {@code 504.77} renders {@code +00000504.77}; {@code -50.00} renders
     * {@code -00000050.00}; zero renders {@code +00000000.00}; and {@code 123456789.12} renders
     * {@code +23456789.12}, having lost its leading {@code 1}.
     *
     * <p>Built digit by digit from a {@link BigInteger}, never through a locale-sensitive formatter
     * and never through {@code double} or {@code float} (gates G22, G23, G24). Scale and rounding come
     * from {@link CobolDecimal}, whose {@link CobolDecimal#COBOL_ROUNDING} is
     * {@code RoundingMode.DOWN} because the keyword {@code ROUNDED} appears nowhere in the 28
     * programs.
     *
     * <p>A negative-zero zoned image renders {@code +00000000.00}: a fixed {@code +} insertion tests
     * the value, and zero is not negative.
     *
     * @param tranAmt the record's {@code TRAN-AMT}; must not be {@code null}
     * @return exactly {@value #WS_TRAN_AMT_LENGTH} characters
     * @throws NullPointerException if {@code tranAmt} is {@code null}
     */
    public static String editedTranAmt(BigDecimal tranAmt) {
        Objects.requireNonNull(tranAmt, "An amount is required to edit it; TRAN-AMT is PIC S9(09)V99 "
                + "and a COBOL numeric item has no absent state");

        BigDecimal stored = CobolDecimal.storeAtPicture(tranAmt, WS_TRAN_AMT_INTEGER_DIGITS,
                WS_TRAN_AMT_SCALE);
        char sign = stored.signum() < 0 ? EDITED_SIGN_NEGATIVE : EDITED_SIGN_POSITIVE;
        // The unscaled value of a scale-2 BigDecimal is the amount in hundredths, which is precisely
        // the digit string an unsuppressed 9-position picture prints - integer and fraction together,
        // with no separator to remove and no floating-point step anywhere in the conversion.
        BigInteger unscaledHundredths = stored.abs().unscaledValue();
        String digits = requireEditedDigits(unscaledHundredths.toString());

        return sign + digits.substring(0, WS_TRAN_AMT_INTEGER_DIGITS) + EDITED_DECIMAL_POINT
                + digits.substring(WS_TRAN_AMT_INTEGER_DIGITS);
    }

    /**
     * Left-zero-fills the amount's digits to the {@value #WS_TRAN_AMT_DIGIT_COUNT} positions
     * {@code PIC +99999999.99} declares, and refuses a digit string that cannot fit.
     *
     * <p>Taken as a parameter rather than read from the value inside
     * {@link #editedTranAmt(BigDecimal)} so that both outcomes are reachable from a test.
     * {@link CobolDecimal#storeAtPicture(BigDecimal, int, int)} has already bounded the magnitude
     * below {@code 10^}{@value #WS_TRAN_AMT_INTEGER_DIGITS} at scale
     * {@value #WS_TRAN_AMT_SCALE}, so an over-long string cannot arise from that route - and an
     * unsuppressed {@code 9} position is never blank, so silently dropping a digit here would produce
     * a plausible amount that is wrong by a factor of ten.
     *
     * @param digits the unscaled digits of the stored amount, without sign or decimal point; must not
     *               be {@code null}
     * @return exactly {@value #WS_TRAN_AMT_DIGIT_COUNT} digit characters
     * @throws NullPointerException     if {@code digits} is {@code null}
     * @throws IllegalStateException    if {@code digits} holds more than
     *                                  {@value #WS_TRAN_AMT_DIGIT_COUNT} characters
     */
    public static String requireEditedDigits(String digits) {
        Objects.requireNonNull(digits, "A digit string is required to fill it to the declared width");
        if (digits.length() > WS_TRAN_AMT_DIGIT_COUNT) {
            throw new IllegalStateException("WS-TRAN-AMT PIC +99999999.99 has "
                    + WS_TRAN_AMT_DIGIT_COUNT + " digit positions - "
                    + WS_TRAN_AMT_INTEGER_DIGITS + " integer and " + WS_TRAN_AMT_SCALE
                    + " fraction - but '" + digits + "' has " + digits.length()
                    + "; CobolDecimal.storeAtPicture should already have reduced the amount to fit");
        }
        return "0".repeat(WS_TRAN_AMT_DIGIT_COUNT - digits.length()) + digits;
    }

    /**
     * {@code FLG-<field>-NOT-OK} for the lookup field: always {@code false}.
     *
     * <p>{@code COTRN01C} declares no field-validation flags at all. Its {@code WORKING-STORAGE}
     * ({@code :35-50}) holds {@code WS-ERR-FLG} and {@code WS-USR-MODIFIED} and nothing of the
     * {@code FLG-...} family, and it does not copy {@code CSSETATY} - its eight {@code COPY}
     * statements are {@code COCOM01Y}, {@code COTRN01}, {@code COTTL01Y}, {@code CSDAT01Y},
     * {@code CSMSG01Y}, {@code CVTRA05Y}, {@code DFHAID} and {@code DFHBMSCA}.
     */
    public static final boolean LOOKUP_FIELD_NOT_OK = false;

    /** {@code FLG-<field>-BLANK} for the lookup field: always {@code false}, for the same reason. */
    public static final boolean LOOKUP_FIELD_BLANK = false;

    /**
     * The {@code CSSETATY} decision for the lookup field - which, for this program, is always "touch
     * nothing".
     *
     * <p>Gate G38 requires that field highlighting apply only in {@code REENTER} state.
     * {@code COTRN01C} satisfies it in the strongest possible way: it applies none in <em>either</em>
     * state, because it does not copy {@code CSSETATY} and declares no
     * {@code FLG-<field>-NOT-OK}/{@code -BLANK} pair for the resolver to act on. So both flags are
     * {@code false}, {@link FieldAttributeSetter} returns
     * {@link FieldHighlight#none(String, String)}, and
     * {@link TransactionAddResponse#applyHighlight(ScreenField, FieldHighlight)} writes no byte -
     * neither {@link BmsAttributes#DFHRED} into {@code TRNIDINC} nor {@code '*'} into
     * {@code TRNIDINO}.
     *
     * <p>The decision is <em>resolved and applied</em> rather than assumed absent, so that the claim
     * "this screen never highlights" is demonstrated by the same mechanism the screens that do
     * highlight use, and a test can assert it in both states. Because the decision can only ever be
     * {@code none}, doing so cannot change a byte of the screen.
     *
     * <p>Note that the {@code reenter} argument is true at send time on <em>both</em> paths, because
     * {@code :100} sets {@code CDEMO-PGM-REENTER} before the first-entry screen is sent. That is the
     * source's sequence and is reported as it stands.
     *
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds
     * @return the decision, always {@linkplain FieldHighlight#untouched() untouched}; never
     *         {@code null}
     */
    public static FieldHighlight lookupFieldHighlight(boolean reenter) {
        return FieldAttributeSetter.resolveFromFlags(LOOKUP_FIELD_NOT_OK, LOOKUP_FIELD_BLANK, reenter,
                ScreenField.TRNIDINO.baseName(), MAP_NAME);
    }

    /**
     * Whether the lookup field carries the {@code CSSETATY} error colour.
     *
     * <p>Published so a test can state the negative directly: this screen never recolours a field, so
     * {@code TRNIDINC} must never hold {@link BmsAttributes#DFHRED}, on a first entry, on a re-entry,
     * after an empty-key rejection and after a not-found rejection alike.
     *
     * @param response the output map; must not be {@code null}
     * @return {@code true} when {@code TRNIDINC} holds {@link BmsAttributes#DFHRED}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static boolean isErrorColoured(TransactionAddResponse response) {
        Objects.requireNonNull(response, "An output map is required to read its colour item");
        return response.attributes(ScreenField.TRNIDINO).colour() == BmsAttributes.DFHRED;
    }

    // =============================================================================================
    // Diagnostics and guards.
    // =============================================================================================

    /**
     * The {@code DISPLAY} at {@code :290}, the program's only console output.
     *
     * <p>Recorded on the state so a parity case can assert it, and logged so a running system shows
     * it. The statement emits two fixed literals and two response codes and nothing else, so no record
     * content and no operator input can reach the log through it.
     *
     * @param state the per-request working storage
     * @param text  the composed text the source displays
     */
    private void display(ProgramState state, String text) {
        state.recordDisplay(text);
        LOG.info(text);
    }

    /**
     * @param state the per-request working storage
     * @throws NullPointerException if {@code state} is {@code null}
     */
    private static void requireState(ProgramState state) {
        Objects.requireNonNull(state, "A ProgramState is required: every WORKING-STORAGE item of "
                + "COTRN01C lives in it, so that two concurrent requests cannot see each other's "
                + "screen");
    }

    // =============================================================================================
    // ProgramState - the WORKING-STORAGE of app/cbl/COTRN01C.cbl:35-61, per request.
    // =============================================================================================

    /**
     * One execution's working storage: everything {@code COTRN01C} declares between {@code :35} and
     * {@code :61}, plus the observable effects that have no home in the 21-field payload.
     *
     * <p><strong>Per request, never shared.</strong> {@code WORKING-STORAGE} in a CICS program is
     * per-task storage, and reproducing it as instance fields on the controller - a Spring singleton -
     * would let two concurrent requests overwrite each other's screen. One of these is created inside
     * {@link TransactionAddController#mainPara(TransactionAddRequest)} and is reachable from nowhere
     * else, which is what makes the controller itself stateless (practice B9, gate G53).
     *
     * <p>It is also the parity fingerprint. A case asserts the response's 21 fields <em>and</em> the
     * things the screen cannot show: which field the cursor was requested in, whether the error flag
     * ended on, how many times the screen was sent, whether the task returned to CICS or transferred
     * away, the two response codes, and the text of the {@code DISPLAY}.
     */
    public static final class ProgramState {

        /** {@code 88 ERR-FLG-ON VALUE 'Y'} - {@code :41}. */
        public static final String ERR_FLG_ON = "Y";

        /** {@code 88 ERR-FLG-OFF VALUE 'N'} - {@code :42}, and the item's own {@code VALUE}. */
        public static final String ERR_FLG_OFF = "N";

        /**
         * {@code 88 USR-MODIFIED-YES VALUE 'Y'} - {@code :46}.
         *
         * <p><strong>Never set by this program.</strong> {@code :89} sets
         * {@code USR-MODIFIED-NO} and no statement in the remaining 240 lines changes the item, so
         * this condition name is dead. It is preserved rather than dropped (practice B5): the
         * declaration is part of the program's data division, and a reader comparing this class with
         * the source would otherwise find a condition name missing with no record of why.
         */
        public static final String USR_MODIFIED_YES = "Y";

        /** {@code 88 USR-MODIFIED-NO VALUE 'N'} - {@code :47}, and the item's own {@code VALUE}. */
        public static final String USR_MODIFIED_NO = "N";

        /**
         * The output map - {@code 01 COTRN1AO REDEFINES COTRN1AI}, both views of one buffer.
         *
         * <p>Created space-filled at every declared width, which is the state
         * {@code INITIALIZE-ALL-FIELDS} leaves the map in; {@code :101} then moves {@code LOW-VALUES}
         * over it on a first entry.
         */
        private final TransactionAddResponse response = new TransactionAddResponse();

        /** The {@code PICTURE} move rules, so this state can hold every item at its declared width. */
        private final FixedWidthCodec codec;

        /** {@code WS-MESSAGE PIC X(80) VALUE SPACES} - {@code :38}. */
        private String message;

        /** {@code WS-ERR-FLG PIC X(01) VALUE 'N'} - {@code :40}. */
        private String errFlg = ERR_FLG_OFF;

        /** {@code WS-USR-MODIFIED PIC X(01) VALUE 'N'} - {@code :45}. */
        private String usrModified = USR_MODIFIED_NO;

        /**
         * {@code WS-TRAN-AMT PIC +99999999.99} - {@code :49}.
         *
         * <p>The item has no {@code VALUE} clause, so its initial content is undefined in COBOL.
         * Spaces is the deterministic choice, and nothing reads it before {@code :177} writes it.
         */
        private String tranAmtEdited;

        /**
         * {@code WS-TRAN-DATE PIC X(08) VALUE '00/00/00'} - {@code :50}, declared and never
         * referenced. {@code final}, because no statement in the program can change it.
         */
        private final String tranDate = WS_TRAN_DATE_INITIAL;

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code :43}. */
        private int respCd = FileStatus.NORMAL;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code :44}. */
        private int reasCd = FileStatus.NO_REASON_CODE;

        /** {@code CARDDEMO-COMMAREA} - the 160 bytes of {@code app/cpy/COCOM01Y.cpy}. */
        private NavigationContext commarea = NavigationContext.empty();

        /** {@code CDEMO-CT01-INFO} - the 58-byte extension declared at {@code :53-61}. */
        private Ct01Info ct01Info = new Ct01Info();

        /** {@code TRAN-ID} of {@code TRAN-RECORD} - the read key, moved at {@code :172}. */
        private String tranId;

        /** The outcome of the read at {@code :269-278}, absent until it has been issued. */
        private ReadResult readResult;

        /** {@code WS-DATE-TIME}, filled from {@code FUNCTION CURRENT-DATE} at {@code :245}. */
        private DateHeader dateHeader;

        /**
         * The field the last {@code MOVE -1 TO <field>L} named - always {@code TRNIDIN} in this
         * program, at {@code :102}, {@code :151}, {@code :154}, {@code :287}, {@code :294} and
         * {@code :311}.
         *
         * <p>{@code xxxL} metadata, deliberately held here and not on the payload: a projection of
         * {@code xxxI} and {@code xxxO} items has no room for a length item, and smuggling one in
         * would break the 1:1 field correspondence gate G9 requires.
         */
        private ScreenField cursorField;

        /** The {@code CSSTRPFY} resolution of {@code EIBAID}, empty when no branch of it matched. */
        private Optional<AidKey> resolvedAid = Optional.empty();

        /** The {@code CSSETATY} decision the last send applied - always untouched here. */
        private FieldHighlight lookupFieldHighlight;

        /** The text of every {@code DISPLAY} the execution reached, in order. */
        private final List<String> displays = new ArrayList<>();

        /** How many times {@code EXEC CICS SEND MAP} was issued - the first-entry arm issues two. */
        private int screensSent;

        /** Whether {@code EXEC CICS RETURN} at {@code :136} was reached. */
        private boolean returned;

        /** Whether {@code EXEC CICS XCTL} at {@code :205} was reached. */
        private boolean transferred;

        /**
         * Creates the working storage in the state the {@code VALUE} clauses describe.
         *
         * @param codec the move rules and code page for this execution's images; must not be
         *              {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public ProgramState(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every item "
                    + "held here is kept at its declared PICTURE width, and the code page of a "
                    + "fixed-width image is always stated explicitly");
            this.message = spaces(WS_MESSAGE_LENGTH);
            this.tranAmtEdited = spaces(WS_TRAN_AMT_LENGTH);
            this.tranId = spaces(TranRecord.TRAN_ID_LENGTH);
            this.lookupFieldHighlight =
                    FieldHighlight.none(ScreenField.TRNIDINO.baseName(), MAP_NAME);
        }

        /**
         * The screen this execution produced.
         *
         * @return the output map, never {@code null} and never replaced
         */
        public TransactionAddResponse response() {
            return response;
        }

        /**
         * {@code WS-MESSAGE}.
         *
         * @return exactly {@value TransactionAddController#WS_MESSAGE_LENGTH} characters
         */
        public String message() {
            return message;
        }

        /**
         * {@code MOVE <text> TO WS-MESSAGE}, at the item's declared width.
         *
         * @param value the text to store; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMessage(String value) {
            Objects.requireNonNull(value, "A message is required; move spaces explicitly to clear it");
            this.message = codec.movePicX(value, WS_MESSAGE_LENGTH);
        }

        /**
         * {@code WS-ERR-FLG}, as the byte it holds.
         *
         * @return {@value #ERR_FLG_ON} or {@value #ERR_FLG_OFF}
         */
        public String errFlg() {
            return errFlg;
        }

        /**
         * {@code 88 ERR-FLG-ON}.
         *
         * @return {@code true} when the item holds {@value #ERR_FLG_ON}
         */
        public boolean errFlagOn() {
            return ERR_FLG_ON.equals(errFlg);
        }

        /**
         * {@code 88 ERR-FLG-OFF}. Deliberately not the negation of {@link #errFlagOn()}: the item is
         * {@code PIC X(01)} and could in principle hold some third byte, for which both condition
         * names are correctly false.
         *
         * @return {@code true} when the item holds {@value #ERR_FLG_OFF}
         */
        public boolean errFlagOff() {
            return ERR_FLG_OFF.equals(errFlg);
        }

        /** {@code MOVE 'Y' TO WS-ERR-FLG} - {@code :129}, {@code :148}, {@code :284}, {@code :291}. */
        public void setErrFlagOn() {
            this.errFlg = ERR_FLG_ON;
        }

        /** {@code SET ERR-FLG-OFF TO TRUE} - {@code :88}. */
        public void setErrFlagOff() {
            this.errFlg = ERR_FLG_OFF;
        }

        /**
         * {@code WS-USR-MODIFIED}, as the byte it holds.
         *
         * @return {@value #USR_MODIFIED_YES} or {@value #USR_MODIFIED_NO}
         */
        public String usrModified() {
            return usrModified;
        }

        /**
         * {@code 88 USR-MODIFIED-YES} - never true in this program; see {@link #USR_MODIFIED_YES}.
         *
         * @return {@code true} when the item holds {@value #USR_MODIFIED_YES}
         */
        public boolean usrModifiedYes() {
            return USR_MODIFIED_YES.equals(usrModified);
        }

        /**
         * {@code 88 USR-MODIFIED-NO}.
         *
         * @return {@code true} when the item holds {@value #USR_MODIFIED_NO}
         */
        public boolean usrModifiedNo() {
            return USR_MODIFIED_NO.equals(usrModified);
        }

        /** {@code SET USR-MODIFIED-NO TO TRUE} - {@code :89}, the only site that writes the item. */
        public void setUsrModifiedNo() {
            this.usrModified = USR_MODIFIED_NO;
        }

        /**
         * {@code WS-TRAN-AMT} - the twelve-character edited amount.
         *
         * @return exactly {@value TransactionAddController#WS_TRAN_AMT_LENGTH} characters
         */
        public String tranAmtEdited() {
            return tranAmtEdited;
        }

        /**
         * {@code MOVE TRAN-AMT TO WS-TRAN-AMT} - {@code :177}, already edited by
         * {@link TransactionAddController#editedTranAmt(BigDecimal)}.
         *
         * @param value the edited image; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setTranAmtEdited(String value) {
            Objects.requireNonNull(value, "An edited amount is required");
            this.tranAmtEdited = codec.movePicX(value, WS_TRAN_AMT_LENGTH);
        }

        /**
         * {@code WS-TRAN-DATE} - the declared-and-unused item, at its {@code VALUE}.
         *
         * @return {@value TransactionAddController#WS_TRAN_DATE_INITIAL}, always
         */
        public String tranDate() {
            return tranDate;
        }

        /**
         * {@code WS-RESP-CD}.
         *
         * @return the CICS {@code RESP} the last command reported, or zero when none did
         */
        public int respCd() {
            return respCd;
        }

        /**
         * {@code MOVE <resp> TO WS-RESP-CD}, as the {@code RESP} option of a command performs it.
         *
         * @param value the response code
         */
        public void setRespCd(int value) {
            this.respCd = value;
        }

        /**
         * {@code WS-REAS-CD}.
         *
         * @return the CICS {@code RESP2} the last command reported
         */
        public int reasCd() {
            return reasCd;
        }

        /**
         * {@code MOVE <resp2> TO WS-REAS-CD}, as the {@code RESP2} option of a command performs it.
         *
         * @param value the reason code
         */
        public void setReasCd(int value) {
            this.reasCd = value;
        }

        /**
         * {@code CARDDEMO-COMMAREA}.
         *
         * @return the 160-byte communication area, never {@code null}
         */
        public NavigationContext commarea() {
            return commarea;
        }

        /**
         * Replaces the communication area, as each {@code MOVE ... TO CDEMO-...} does.
         *
         * @param value the area to hold; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCommarea(NavigationContext value) {
            this.commarea = Objects.requireNonNull(value, "A communication area is required; the "
                    + "EIBCALEN = 0 case is distinguished by TransactionAddRequest, not by a null "
                    + "here");
        }

        /**
         * {@code CDEMO-CT01-INFO}.
         *
         * @return the 58-byte extension, never {@code null}
         */
        public Ct01Info ct01Info() {
            return ct01Info;
        }

        /**
         * Replaces the communication-area extension.
         *
         * @param value the extension to hold; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCt01Info(Ct01Info value) {
            this.ct01Info = Objects.requireNonNull(value, "A CDEMO-CT01-INFO extension is required; "
                    + "the request always carries one, initialised if the client sent none");
        }

        /**
         * {@code TRAN-ID} of {@code TRAN-RECORD} - the key the read at {@code :273} rides on.
         *
         * @return exactly {@value TranRecord#TRAN_ID_LENGTH} characters
         */
        public String tranId() {
            return tranId;
        }

        /**
         * {@code MOVE TRNIDINI OF COTRN1AI TO TRAN-ID} - {@code :172}.
         *
         * @param value the key to store; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setTranId(String value) {
            Objects.requireNonNull(value, "A read key is required");
            this.tranId = codec.movePicX(value, TranRecord.TRAN_ID_LENGTH);
        }

        /**
         * The outcome of the read at {@code :269-278}.
         *
         * @return the outcome, or empty when the read was never issued - which is the case whenever
         *         the lookup key was rejected as blank at {@code :147}
         */
        public Optional<ReadResult> readResult() {
            return Optional.ofNullable(readResult);
        }

        /**
         * Records the read outcome, as the {@code INTO} and {@code RESP} options do together.
         *
         * @param value the outcome; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setReadResult(ReadResult value) {
            this.readResult = Objects.requireNonNull(value, "A read outcome is required; an absent "
                    + "record is reported by the outcome, never by a null outcome");
        }

        /**
         * {@code TRAN-RECORD} - the 350 bytes the read moved in.
         *
         * @return the record, or empty when no read has returned one
         */
        public Optional<TranRecord> tranRecord() {
            if (readResult == null) {
                return Optional.empty();
            }
            return readResult.record();
        }

        /**
         * {@code WS-DATE-TIME} as {@code POPULATE-HEADER-INFO} filled it.
         *
         * @return the header, or empty when no screen has been sent
         */
        public Optional<DateHeader> dateHeader() {
            return Optional.ofNullable(dateHeader);
        }

        /**
         * Records {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} - {@code :245}.
         *
         * @param value the captured date and time; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setDateHeader(DateHeader value) {
            this.dateHeader = Objects.requireNonNull(value, "A captured date and time is required");
        }

        /**
         * The field the cursor was requested in.
         *
         * @return the field, or {@code null} when no {@code MOVE -1 TO <field>L} was reached - which
         *         is the case on the {@code EIBCALEN = 0} arm and on both transferring arms
         */
        public ScreenField cursorField() {
            return cursorField;
        }

        /**
         * Whether any {@code MOVE -1 TO <field>L} was reached.
         *
         * @return {@code true} when the cursor was placed
         */
        public boolean cursorRequested() {
            return cursorField != null;
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
         *       and not a payload member, so it is reported here and not smuggled into a projection of
         *       {@code xxxI} and {@code xxxO} items;</li>
         *   <li>the {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} quad of each of the
         *       twenty-one fields, which is what {@code app/cpy/CSSETATY.cpy} writes
         *       {@link BmsAttributes#DFHRED} into when a field is in error;</li>
         *   <li>the colour of the message line, read from {@code ERRMSGC} rather than restated, so a
         *       rename cannot silently leave this pointing at a field that no longer exists.</li>
         * </ul>
         *
         * <p>Each quad is published as four unsigned {@code 0}-{@code 255} values, because an attribute
         * byte with the high bit set - {@link BmsAttributes#DFHRED} is {@code 0xF2} - is a negative
         * {@code byte} in Java and publishing {@code -14} would misstate it. The map is keyed by the
         * {@code DFHMDF} label, which is the name the mapset gives the field.
         *
         * <p>{@code resetAllOutputFields} is {@code false}: {@code MOVE LOW-VALUES TO COTRN1AO} at
         * {@code :101} has already been applied to the response being published, so the client is not
         * being asked to clear anything a second time.
         *
         * @return the metadata; never {@code null}
         */
        public ScreenMetadata screenMetadata() {
            Map<ScreenField, TransactionAddResponse.AttributeQuad> quads = response.attributeItems();
            Map<String, ScreenMetadata.FieldMetadata> fields = new LinkedHashMap<>();
            for (Map.Entry<ScreenField, TransactionAddResponse.AttributeQuad> quad : quads.entrySet()) {
                fields.put(quad.getKey().baseName(), ScreenMetadata.FieldMetadata.of(
                        quad.getValue().colour(),
                        quad.getValue().programmedSymbols(),
                        quad.getValue().highlight(),
                        quad.getValue().validation()));
            }
            return ScreenMetadata.of(cursorField == null ? null : cursorField.baseName(),
                    response.attributes(ScreenField.ERRMSGO).colour(),
                    false,
                    fields);
        }

        /**
         * {@code MOVE -1 TO <field>L} - the cursor request.
         *
         * @param field the field to place the cursor in; must not be {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public void moveMinusOneTo(ScreenField field) {
            this.cursorField = Objects.requireNonNull(field, "A field is required to place the "
                    + "cursor in it");
        }

        /**
         * The {@code CSSTRPFY} resolution of {@code EIBAID}.
         *
         * @return the resolved key, or empty when the byte matched no branch of the resolver - which
         *         is faithful, because the copybook's {@code EVALUATE} has no {@code WHEN OTHER} and
         *         leaves the previous value standing rather than defaulting
         */
        public Optional<AidKey> resolvedAid() {
            return resolvedAid;
        }

        /**
         * Records the resolved key.
         *
         * @param value the resolution; must not be {@code null}, and empty rather than {@code null}
         *              when nothing matched
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setResolvedAid(Optional<AidKey> value) {
            this.resolvedAid = Objects.requireNonNull(value, "An unmatched AID is an empty Optional, "
                    + "never null");
        }

        /**
         * The {@code CSSETATY} decision the last send applied.
         *
         * @return the decision, never {@code null} and - for this program - always
         *         {@linkplain FieldHighlight#untouched() untouched}
         */
        public FieldHighlight lookupFieldHighlight() {
            return lookupFieldHighlight;
        }

        /**
         * Records the decision.
         *
         * @param value the decision; must not be {@code null}
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setLookupFieldHighlight(FieldHighlight value) {
            this.lookupFieldHighlight = Objects.requireNonNull(value, "FieldAttributeSetter returns "
                    + "FieldHighlight.none(..) rather than null when nothing is to be done");
        }

        /**
         * Every {@code DISPLAY} the execution reached.
         *
         * @return an unmodifiable view in statement order; empty for every path but the read's
         *         {@code WHEN OTHER} arm
         */
        public List<String> displays() {
            return Collections.unmodifiableList(displays);
        }

        /**
         * Records one {@code DISPLAY}.
         *
         * @param text the composed text; must not be {@code null}
         * @throws NullPointerException if {@code text} is {@code null}
         */
        public void recordDisplay(String text) {
            displays.add(Objects.requireNonNull(text, "A DISPLAY carries text"));
        }

        /**
         * How many times the screen was sent.
         *
         * @return the count; two on the first-entry arm that performs a lookup, one on every other
         *         re-displaying arm, and zero on the three arms that transfer or reject before any
         *         send
         */
        public int screensSent() {
            return screensSent;
        }

        /**
         * Whether the screen was sent at all.
         *
         * @return {@code true} when at least one {@code EXEC CICS SEND MAP} was issued
         */
        public boolean screenSent() {
            return screensSent > 0;
        }

        /** Records one {@code EXEC CICS SEND MAP ... ERASE CURSOR} - {@code :219-225}. */
        public void recordScreenSent() {
            this.screensSent++;
        }

        /**
         * Whether the task returned to CICS.
         *
         * @return {@code true} when {@code EXEC CICS RETURN TRANSID('CT01')} at {@code :136} was
         *         reached
         */
        public boolean returned() {
            return returned;
        }

        /** Records {@code EXEC CICS RETURN} - {@code :136-139}. */
        public void markReturned() {
            this.returned = true;
        }

        /**
         * Whether the task transferred to another program.
         *
         * @return {@code true} when {@code EXEC CICS XCTL} at {@code :205} was reached, in which case
         *         {@link #returned()} is false: the transfer never comes back
         */
        public boolean transferred() {
            return transferred;
        }

        /** Records {@code EXEC CICS XCTL} - {@code :205-208}. */
        public void markTransferred() {
            this.transferred = true;
        }
    }
}
