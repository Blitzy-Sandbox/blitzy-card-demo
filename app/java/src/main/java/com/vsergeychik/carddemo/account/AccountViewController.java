package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.dto.AccountViewRequest;
import com.vsergeychik.carddemo.account.dto.AccountViewResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code COACTVWC} - "Accept and process Account View request" - translated paragraph for paragraph
 * from {@code app/cbl/COACTVWC.cbl} (941 lines) and published as CICS transaction {@code CAVW}'s REST
 * projection, {@code GET /api/accounts/{acctId}}.
 *
 * <p>The CSD binds the two together: {@code DEFINE PROGRAM(COACTVWC) DESCRIPTION(VIEW ACCT) ...
 * TRANSID(CAVW)} at {@code app/csd/CARDDEMO.CSD:181-188} and {@code DEFINE TRANSACTION(CAVW) ...
 * PROGRAM(COACTVWC)} at {@code :315-318}.
 *
 * <h2>What this class is, and what it is not</h2>
 *
 * <p>This is a <strong>like-for-like translation</strong>, not a redesign. Every paragraph of the
 * source has a method here, the methods run in the source's order, and where the COBOL does something
 * surprising this class does the same surprising thing and says so in a comment. Four such surprises
 * were found by reading the {@code PROCEDURE DIVISION} rather than the {@code 88}-level declarations,
 * and each is called out at the point it matters:
 *
 * <ol>
 *   <li>{@code 2210-EDIT-ACCOUNT} does <em>not</em> set {@code SEARCHED-ACCT-ZEROES} or
 *       {@code SEARCHED-ACCT-NOT-NUMERIC}. It moves a different literal -
 *       {@value #ACCOUNT_FILTER_NOT_NUMERIC_TEXT} - at {@code :671-673}. Both condition names are
 *       declared and never set.</li>
 *   <li>All three read paragraphs have their {@code SET DID-NOT-FIND-...} statements commented out
 *       ({@code :792}, {@code :842}) or absent ({@code :741-758}), and compose a message with
 *       {@code STRING} instead. So {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} and friends never become
 *       true.</li>
 *   <li>Consequently the guards {@code IF DID-NOT-FIND-ACCT-IN-ACCTDAT} at {@code :704} and
 *       {@code IF DID-NOT-FIND-CUST-IN-CUSTDAT} at {@code :713} never fire, so an account that is
 *       missing from the master file still falls through to the customer read. Only the first guard,
 *       {@code IF FLG-ACCTFILTER-NOT-OK} at {@code :697}, actually leaves the paragraph.</li>
 *   <li>An attention identifier this screen does not support is <em>rewritten to ENTER</em> at
 *       {@code :312-314}, so PF7 behaves exactly like ENTER rather than being rejected.</li>
 * </ol>
 *
 * <h2>Statelessness (rule R6, gate G37)</h2>
 *
 * <p>There is no server-side state of any kind here: no session object, no session-scoped controller
 * attributes, no cache, no session affinity and no static mutable field. The whole of the CICS
 * pseudo-conversation travels in the payload instead: the
 * {@code CARDDEMO-COMMAREA} as {@link NavigationContext}, the {@code CC-WORK-AREA} of
 * {@code app/cpy/CVCRD01Y.cpy} as {@link CardScreenState}, and the attention identifier as a request
 * parameter. Every field this class owns lives in a {@link Conversation} created per call, so two
 * concurrent requests cannot see each other's state.
 *
 * <h2>Control flow (rule R7, gate G30)</h2>
 *
 * <p>The program's nine {@code GO TO}s all target {@code COMMON-RETURN},
 * {@code 2210-EDIT-ACCOUNT-EXIT} or {@code 9000-READ-ACCT-EXIT}; every one is the structured-COBOL
 * "return from paragraph" idiom and becomes a plain {@code return}. The {@code EVALUATE TRUE} at
 * {@code :323-383} keeps its source {@code WHEN} order - {@code CCARD-AID-PFK03}, then
 * {@code CDEMO-PGM-ENTER}, then {@code CDEMO-PGM-REENTER}, then {@code WHEN OTHER} last - in
 * {@link #dispatch0000}.
 *
 * <h2>Testability (practice B10, gate G51)</h2>
 *
 * <p>The migration plan gives {@code COACTVWC} no service class, so rather than bury the decisions in
 * an HTTP handler every paragraph is a package-visible method that a plain JUnit test can call
 * directly: {@link #editAccount2210}, {@link #readAcct9000}, {@link #setupScreenVars1200},
 * {@link #fileErrorMessage} and the rest. {@code MockMvc} is then only needed for the HTTP shape.
 *
 * <h2>Imports, and the five classes deliberately not used</h2>
 *
 * <p>This file imports only from its declared dependencies, so five conveniences other online
 * controllers use are absent, each with a documented substitute:
 *
 * <ul>
 *   <li>{@code ScreenResponse} / {@code ScreenMetadata} - the endpoint returns the payload directly.
 *       This is also what the field contract requires: the {@code xxxC}, {@code xxxP}, {@code xxxH}
 *       and {@code xxxV} attribute items are highlight metadata and never JSON members, and
 *       {@link AccountViewResponse#attributeQuads()} keeps them reachable in process for the parity
 *       harness and for tests.</li>
 *   <li>{@code FieldAttributeSetter} - {@code COACTVWC} does not {@code COPY CSSETATY}; it writes the
 *       highlight inline at {@code :561-565}, and so does {@link #setupScreenAttrs1300}.</li>
 *   <li>{@code AidRequestParameter} - both spellings of the attention-identifier parameter are
 *       accepted here, so the wire contract still matches every other screen.</li>
 *   <li>{@code CobolCharsetConfig} - this class performs only character-level {@code MOVE}s, so it
 *       uses one immutable charset-neutral {@link FixedWidthCodec}; all code-page work belongs to the
 *       repositories, which are the only classes that touch dataset bytes.</li>
 *   <li>{@code DatasetRelation.BackendDiagnostic} - the abend path logs its own single-argument line;
 *       a driver's message never reaches it.</li>
 * </ul>
 *
 * <p>{@link AbendException} <em>is</em> imported. It is the module's single abend mechanism, and
 * {@code config/WebConfig}'s {@code CobolErrorHandler} - a declared dependency of this file - carries
 * the {@code @ExceptionHandler(AbendException.class)} that turns {@code EXEC CICS ABEND ABCODE('9999')}
 * into an HTTP response. A second, private abend type would fork that contract for one screen.
 *
 * @see AccountViewRequest the 37 {@code xxxI} items of {@code app/cpy-bms/COACTVW.CPY}
 * @see AccountViewResponse the 37 {@code xxxO} items, five of them {@code PIC +ZZZ,ZZZ,ZZZ.99}
 */
@RestController
public class AccountViewController {

    /**
     * Commons Logging, matching the rest of the module. Every call here passes <strong>one</strong>
     * argument: {@code common/NoRawBackendDiagnosticTest} scans all main sources and fails any
     * two-argument call, because a throwable handed to a logger prints its cause chain verbatim.
     */
    private static final Log LOG = LogFactory.getLog(AccountViewController.class);

    // =================================================================================================
    // WS-LITERALS - app/cbl/COACTVWC.cbl:142-202. Byte exact, including the trailing space on the two
    // eight-character mapset names, which is the whole reason CDEMO-LAST-MAPSET loses a character when
    // one is moved into it.
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COACTVWC'} - {@code :143-144}. */
    static final String LIT_THISPGM = "COACTVWC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CAVW'} - {@code :145-146}; CSD transaction {@code CAVW}. */
    static final String LIT_THISTRANID = "CAVW";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '} - {@code :147-148}. Eight characters with a
     * trailing space. {@code CDEMO-LAST-MAPSET} and {@code CCARD-NEXT-MAPSET} are both {@code PIC X(7)},
     * so moving this literal into either one discards the space - which is the correct outcome, and is
     * why the move is always made through {@link FixedWidthCodec#movePicX(String, int)}.
     */
    static final String LIT_THISMAPSET = "COACTVW ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CACTVWA'} - {@code :149-150}. */
    static final String LIT_THISMAP = "CACTVWA";

    /** {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} - {@code :151-152}; declared, never used (B5). */
    static final String LIT_CCLISTPGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID PIC X(4) VALUE 'CCLI'} - {@code :153-154}; declared, never used (B5). */
    static final String LIT_CCLISTTRANID = "CCLI";

    /** {@code LIT-CCLISTMAPSET PIC X(7) VALUE 'COCRDLI'} - {@code :155-156}; declared, never used (B5). */
    static final String LIT_CCLISTMAPSET = "COCRDLI";

    /** {@code LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :157-158}; declared, never used (B5). */
    static final String LIT_CCLISTMAP = "CCRDSLA";

    /** {@code LIT-CARDUPDATEPGM PIC X(8) VALUE 'COCRDUPC'} - {@code :159-160}; declared, never used. */
    static final String LIT_CARDUPDATEPGM = "COCRDUPC";

    /**
     * {@code LIT-CARDUDPATETRANID PIC X(4) VALUE 'CCUP'} - {@code :161-162}. The source spells the data
     * name "UDPATE"; the name is carried over as the source has it, because a corrected name would stop
     * matching the copybook this constant documents.
     */
    static final String LIT_CARDUDPATETRANID = "CCUP";

    /** {@code LIT-CARDUPDATEMAPSET PIC X(8) VALUE 'COCRDUP '} - {@code :163-164}; trailing space. */
    static final String LIT_CARDUPDATEMAPSET = "COCRDUP ";

    /** {@code LIT-CARDUPDATEMAP PIC X(7) VALUE 'CCRDUPA'} - {@code :165-166}; declared, never used. */
    static final String LIT_CARDUPDATEMAP = "CCRDUPA";

    /**
     * {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - {@code :168-169}. Read twice: the fresh-entry test
     * at {@code :283} and the {@code CDEMO-TO-PROGRAM} default in the PF3 branch at {@code :336}.
     */
    static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - {@code :170-171}; the PF3 tranid default. */
    static final String LIT_MENUTRANID = "CM00";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} - {@code :172-173}; declared, never used (B5). */
    static final String LIT_MENUMAPSET = "COMEN01";

    /** {@code LIT-MENUMAP PIC X(7) VALUE 'COMEN1A'} - {@code :174-175}; declared, never used (B5). */
    static final String LIT_MENUMAP = "COMEN1A";

    /** {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} - {@code :176-177}; declared, never used (B5). */
    static final String LIT_CARDDTLPGM = "COCRDSLC";

    /** {@code LIT-CARDDTLTRANID PIC X(4) VALUE 'CCDL'} - {@code :178-179}; declared, never used (B5). */
    static final String LIT_CARDDTLTRANID = "CCDL";

    /** {@code LIT-CARDDTLMAPSET PIC X(7) VALUE 'COCRDSL'} - {@code :180-181}; declared, never used. */
    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    /** {@code LIT-CARDDTLMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :182-183}; declared, never used (B5). */
    static final String LIT_CARDDTLMAP = "CCRDSLA";

    /**
     * The declared width of a CICS file name in this program: {@code PIC X(8)}, so every one of the five
     * literals at {@code :184-193} carries a trailing space.
     */
    static final int CICS_FILE_NAME_LENGTH = 8;

    /**
     * The 52 upper- and lower-case letters of {@code LIT-ALL-ALPHA-FROM PIC X(52)} - {@code :194-196}.
     * Declared for an {@code INSPECT CONVERTING} the program never performs; kept because deleting dead
     * declarations is a change to the source (practice B5).
     */
    static final String LIT_ALL_ALPHA_FROM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** {@code LIT-ALL-SPACES-TO PIC X(52) VALUE SPACES} - {@code :197-198}; declared, never used (B5). */
    static final String LIT_ALL_SPACES_TO = CardScreenState.spaces(52);

    /** {@code LIT-UPPER PIC X(26)} - {@code :199-200}; declared, never used (B5). */
    static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** {@code LIT-LOWER PIC X(26)} - {@code :201-202}; declared, never used (B5). */
    static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    // =================================================================================================
    // The PIC X move rule, and nothing else. movePicX, movePic9 and decodePic9 are pure character
    // operations - they left justify, truncate on the right and pad with spaces, exactly as COBOL does -
    // and none of them consults the charset. Dataset bytes and their code page belong to the three
    // repositories; this class never sees a byte, which is why one immutable charset-neutral codec is
    // sufficient here and no Charset is injected (practice B8: the choice is stated, not defaulted).
    // =================================================================================================

    /** The shared, stateless, immutable codec used for every {@code MOVE} in this class. */
    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // Dataset names. These are CICS FILE names, never data set names: the data set name is resolved from
    // the carddemo.datasets.* configuration by the repositories, so no data set name literal appears
    // anywhere in this file (gate G46).
    // Three of the five names below are derived from the repository that owns the access path, so a
    // rename cannot leave this class pointing at a file that no longer exists.
    // =================================================================================================

    /**
     * {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '} - {@code :184-185}; the {@code DATASET} of the
     * read at {@code :776-777} and the {@code ERROR-FILE} of its {@code WHEN OTHER} arm.
     */
    static final String LIT_ACCTFILENAME =
            PIC_X_CODEC.movePicX(AccountRepository.CICS_FILE_NAME, CICS_FILE_NAME_LENGTH);

    /**
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} - {@code :186-187}. Declared and never read:
     * {@code COACTVWC} names five files and reads three. Left as a literal because the card master's
     * repository is not a dependency of this class - it has no reason to be, since this class never
     * reads that file.
     */
    static final String LIT_CARDFILENAME = "CARDDAT ";

    /**
     * {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '} - {@code :188-189}; the {@code DATASET} of the
     * read at {@code :826-827}.
     */
    static final String LIT_CUSTFILENAME =
            PIC_X_CODEC.movePicX(CustomerRepository.CICS_FILE_NAME, CICS_FILE_NAME_LENGTH);

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} - {@code :190-191}. Declared and never
     * read, like {@link #LIT_CARDFILENAME}.
     */
    static final String LIT_CARDFILENAME_ACCT_PATH = "CARDAIX ";

    /**
     * {@code LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '} - {@code :192-193}; the
     * {@code DATASET} of the read at {@code :727-728}. This is the {@code CXACAIX} <em>path over the
     * CCXREF base cluster</em> - one repository with a second finder, never a second table (gate G45).
     */
    static final String LIT_CARDXREFNAME_ACCT_PATH = PIC_X_CODEC.movePicX(
            CardXrefRepository.ALTERNATE_INDEX_DD_NAME, CICS_FILE_NAME_LENGTH);

    // =================================================================================================
    // WS-MISC-STORAGE widths - app/cbl/COACTVWC.cbl:35-138.
    // =================================================================================================

    /** {@code WS-TRANID PIC X(4) VALUE SPACES} - {@code :44-45}; set to {@code 'CAVW'} at {@code :274}. */
    static final int WS_TRANID_LENGTH = 4;

    /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :74}; declared, never used by this program (B5). */
    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    /**
     * {@code WS-CARD-RID-CUST-ID PIC 9(09)} with {@code WS-CARD-RID-CUST-ID-X REDEFINES ... PIC X(09)} -
     * {@code :75-77}. One nine-byte span, two typed views (gate G34); the {@code RIDFLD} at {@code :828}
     * is the character view.
     */
    static final int WS_CARD_RID_CUST_ID_LENGTH = CardScreenState.CC_CUST_ID_LENGTH;

    /**
     * {@code WS-CARD-RID-ACCT-ID PIC 9(11)} with {@code WS-CARD-RID-ACCT-ID-X REDEFINES ... PIC X(11)} -
     * {@code :78-80}. The {@code RIDFLD} and {@code KEYLENGTH} of the reads at {@code :729-730} and
     * {@code :778-779} are both taken from the character view.
     */
    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    /** {@code WS-LONG-MSG PIC X(500)} - {@code :109}; the {@code SEND-LONG-TEXT} buffer. */
    static final int WS_LONG_MSG_LENGTH = 500;

    /**
     * {@code WS-INFO-MSG PIC X(40)} - {@code :110}. Narrower than the {@code INFOMSGO PIC X(45)} it is
     * moved into at {@code :534}, so five spaces are appended on that move.
     */
    static final int WS_INFO_MSG_LENGTH = AccountViewResponse.WS_INFO_MSG_LENGTH;

    /**
     * {@code WS-RETURN-MSG PIC X(75)} - {@code :117}. The same width as {@code CCARD-ERROR-MSG}, and
     * three characters narrower than the {@code ERRMSGO PIC X(78)} it reaches at {@code :532}.
     */
    static final int WS_RETURN_MSG_LENGTH = AccountViewResponse.WS_RETURN_MSG_LENGTH;

    /** {@code WS-COMMAREA PIC X(2000)} - {@code :218}; what {@code COMMON-RETURN} hands back. */
    static final int WS_COMMAREA_LENGTH = 2000;

    /**
     * {@code WS-THIS-PROGCOMMAREA} - {@code :213-216}: {@code CA-FROM-PROGRAM PIC X(08)} followed by
     * {@code CA-FROM-TRANID PIC X(04)}.
     */
    static final int THIS_PROGCOMMAREA_LENGTH =
            ThisProgCommarea.CA_FROM_PROGRAM_LENGTH + ThisProgCommarea.CA_FROM_TRANID_LENGTH;

    /**
     * The length of the area {@code 0000-MAIN} splits at {@code :288-292}: {@code CARDDEMO-COMMAREA}
     * (160) followed by {@code WS-THIS-PROGCOMMAREA} (12).
     */
    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    /** {@code EIBCALEN} when no communication area was passed - the first test at {@code :282}. */
    static final int NO_COMMAREA_LENGTH = 0;

    // =================================================================================================
    // The 88-level values of the four one-byte edit flags - app/cbl/COACTVWC.cbl:50-65. Values, not
    // state: the state itself lives in a per-request Conversation, because WORKING-STORAGE that became a
    // static field would be shared by every concurrent request (practice B9).
    // =================================================================================================

    /** {@code 88 INPUT-OK VALUE '0'} - {@code :51}. */
    static final String INPUT_OK = "0";

    /** {@code 88 INPUT-ERROR VALUE '1'} - {@code :52}; tested at {@code :364} and {@code :387}. */
    static final String INPUT_ERROR = "1";

    /**
     * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - {@code :53}, and again at {@code :57} on
     * {@code WS-PFK-FLAG}. The source declares the same condition name on two different fields; the two
     * are kept apart here as {@link Conversation#inputPending()} and
     * {@link Conversation#pfkeyInputPending()}, since a single Java method could not tell which field a
     * caller meant.
     */
    static final String INPUT_PENDING = "\u0000";

    /** {@code 88 PFK-VALID VALUE '0'} - {@code :55}; set at {@code :309}. */
    static final String PFK_VALID = "0";

    /** {@code 88 PFK-INVALID VALUE '1'} - {@code :56}; set at {@code :306}, tested at {@code :312}. */
    static final String PFK_INVALID = "1";

    /**
     * {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} - {@code :59}, and {@code FLG-CUSTFILTER-NOT-OK} at
     * {@code :63}. One value, two fields.
     */
    static final String FLG_FILTER_NOT_OK = "0";

    /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} - {@code :60}; {@code FLG-CUSTFILTER-ISVALID} {@code :64}. */
    static final String FLG_FILTER_ISVALID = "1";

    /** {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - {@code :61}; {@code FLG-CUSTFILTER-BLANK} {@code :65}. */
    static final String FLG_FILTER_BLANK = " ";

    /** {@code 88 FOUND-ACCT-IN-MASTER VALUE '1'} - {@code :83}; set at {@code :788}. */
    static final String FOUND_IN_MASTER = "1";

    /**
     * The state {@code INITIALIZE WS-MISC-STORAGE} leaves a {@code PIC X(1)} flag in: a space, because
     * {@code INITIALIZE} blanks alphanumeric items. It is <em>not</em> {@code LOW-VALUES}, which matters
     * because {@code FLG-ACCTFILTER-BLANK} is also a space and is therefore true immediately after
     * initialisation - the very state {@code 1200-SETUP-SCREEN-VARS} tests at {@code :465}.
     */
    static final String INITIALIZED_FLAG = " ";

    // =================================================================================================
    // WS-INFO-MSG conditions - app/cbl/COACTVWC.cbl:110-116. Padded to the declared 40 characters on
    // construction, so a comparison against the field is a comparison of equal-width images and cannot
    // succeed or fail on padding alone.
    // =================================================================================================

    /**
     * The first image of {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code :111-112}. A
     * {@code SET WS-NO-INFO-MESSAGE TO TRUE}, as {@code 9000-READ-ACCT} performs at {@code :689}, stores
     * the <strong>first</strong> value of the list, so it stores spaces.
     */
    static final String WS_INFO_MSG_SPACES = CardScreenState.spaces(WS_INFO_MSG_LENGTH);

    /** The second image of {@code WS-NO-INFO-MESSAGE}: forty bytes of binary zero. */
    static final String WS_INFO_MSG_LOW_VALUES = CardScreenState.lowValues(WS_INFO_MSG_LENGTH);

    /** The text of {@code 88 WS-PROMPT-FOR-INPUT} - {@code :113-114}; exactly forty characters. */
    static final String WS_PROMPT_FOR_INPUT_TEXT = "Enter or update id of account to display";

    /**
     * {@code 88 WS-PROMPT-FOR-INPUT} - {@code :113-114}. Set on a cold start at {@code :463} and again
     * whenever the informational line is still blank at {@code :528-530}, which between them make it the
     * only informational text this screen ever displays.
     */
    static final String WS_PROMPT_FOR_INPUT =
            PIC_X_CODEC.movePicX(WS_PROMPT_FOR_INPUT_TEXT, WS_INFO_MSG_LENGTH);

    /** The text of {@code 88 WS-INFORM-OUTPUT} - {@code :115-116}. */
    static final String WS_INFORM_OUTPUT_TEXT = "Displaying details of given Account";

    /**
     * {@code 88 WS-INFORM-OUTPUT} - {@code :115-116}. Declared and <strong>never set</strong>: nothing in
     * the program performs {@code SET WS-INFORM-OUTPUT TO TRUE}, so a successful read still shows
     * {@link #WS_PROMPT_FOR_INPUT}. Transcribed because it is part of the record the copybook defines,
     * and implementing it would be a new feature (practice B5).
     */
    static final String WS_INFORM_OUTPUT =
            PIC_X_CODEC.movePicX(WS_INFORM_OUTPUT_TEXT, WS_INFO_MSG_LENGTH);

    // =================================================================================================
    // WS-RETURN-MSG conditions - app/cbl/COACTVWC.cbl:117-138. Ten condition names over one PIC X(75)
    // field. Two of them share a text and four are never set; both facts are recorded rather than tidied.
    // =================================================================================================

    /** {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - {@code :118}; set at {@code :278}, tested five times. */
    static final String WS_RETURN_MSG_OFF = CardScreenState.spaces(WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 WS-EXIT-MESSAGE} - {@code :119-120}. The literal carries fourteen trailing spaces inside
     * the quotes, which survive here and are then extended to 75 by the move. Declared and never set: the
     * PF3 branch at {@code :324-352} transfers control without leaving a message behind.
     */
    static final String WS_EXIT_MESSAGE =
            PIC_X_CODEC.movePicX("PF03 pressed.Exiting              ", WS_RETURN_MSG_LENGTH);

    /** {@code 88 WS-PROMPT-FOR-ACCT} - {@code :121-122}; set at {@code :658} when nothing was keyed. */
    static final String WS_PROMPT_FOR_ACCT =
            PIC_X_CODEC.movePicX("Account number not provided", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED} - {@code :123-124}; set by the cross-field edit at
     * {@code :640-642}, which overwrites the per-field message set moments earlier at {@code :658}.
     */
    static final String NO_SEARCH_CRITERIA_RECEIVED =
            PIC_X_CODEC.movePicX("No input received", WS_RETURN_MSG_LENGTH);

    /** The text {@code SEARCHED-ACCT-ZEROES} and {@code SEARCHED-ACCT-NOT-NUMERIC} share verbatim. */
    static final String SEARCHED_ACCT_TEXT = "Account number must be a non zero 11 digit number";

    /**
     * {@code 88 SEARCHED-ACCT-ZEROES} - {@code :125-126}. Declared and never set: the paragraph that
     * would set it moves {@link #ACCOUNT_FILTER_NOT_NUMERIC} instead.
     */
    static final String SEARCHED_ACCT_ZEROES =
            PIC_X_CODEC.movePicX(SEARCHED_ACCT_TEXT, WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 SEARCHED-ACCT-NOT-NUMERIC} - {@code :127-128}. A second condition name over the
     * <strong>same</strong> literal as {@link #SEARCHED_ACCT_ZEROES}, so the two are indistinguishable
     * once stored. Kept as its own constant so the two declarations stay visible, and deliberately
     * defined as the same value rather than a copy of the text.
     */
    static final String SEARCHED_ACCT_NOT_NUMERIC = SEARCHED_ACCT_ZEROES;

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} - {@code :129-130}. Declared and never set: the
     * {@code NOTFND} arm at {@code :741-758} composes a message with {@code STRING} instead.
     */
    static final String DID_NOT_FIND_ACCT_IN_CARDXREF = PIC_X_CODEC.movePicX(
            "Did not find this account in account card xref file", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT} - {@code :131-132}. Its {@code SET} is
     * <strong>commented out</strong> at {@code :792}, so the condition is only ever <em>tested</em> - at
     * {@code :704} - and never made true. {@link Conversation#didNotFindAcctInAcctdat()} performs that
     * test faithfully as a comparison against this image.
     */
    static final String DID_NOT_FIND_ACCT_IN_ACCTDAT = PIC_X_CODEC.movePicX(
            "Did not find this account in account master file", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT} - {@code :133-134}. Its {@code SET} is commented out at
     * {@code :842} and the condition is tested at {@code :713}; same shape as
     * {@link #DID_NOT_FIND_ACCT_IN_ACCTDAT}.
     */
    static final String DID_NOT_FIND_CUST_IN_CUSTDAT = PIC_X_CODEC.movePicX(
            "Did not find associated customer in master file", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 XREF-READ-ERROR} - {@code :135-136}. Note the capital {@code F} in "File", which the
     * neighbouring texts do not have. Declared and never set: the {@code WHEN OTHER} arm at
     * {@code :759-766} moves {@code WS-FILE-ERROR-MESSAGE}.
     */
    static final String XREF_READ_ERROR =
            PIC_X_CODEC.movePicX("Error reading account card xref File", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 CODING-TO-BE-DONE} - {@code :137-138}; four full stops, then a space, then "so far".
     * Declared and never set (practice B5).
     */
    static final String CODING_TO_BE_DONE =
            PIC_X_CODEC.movePicX("Looks Good.... so far", WS_RETURN_MSG_LENGTH);

    /**
     * The literal {@code 2210-EDIT-ACCOUNT} actually moves at {@code :671-673} - two spaces after
     * "must", and "non-zero" hyphenated. It is <strong>not</strong> the text of
     * {@link #SEARCHED_ACCT_NOT_NUMERIC}, and the difference is the reason both are transcribed here.
     */
    static final String ACCOUNT_FILTER_NOT_NUMERIC_TEXT =
            "Account Filter must  be a non-zero 11 digit number";

    /** The 75-character image of {@link #ACCOUNT_FILTER_NOT_NUMERIC_TEXT}. */
    static final String ACCOUNT_FILTER_NOT_NUMERIC =
            PIC_X_CODEC.movePicX(ACCOUNT_FILTER_NOT_NUMERIC_TEXT, WS_RETURN_MSG_LENGTH);

    /** {@code MOVE 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG} - {@code :379-380}, the fallback arm. */
    static final String UNEXPECTED_DATA_SCENARIO =
            PIC_X_CODEC.movePicX("UNEXPECTED DATA SCENARIO", WS_RETURN_MSG_LENGTH);

    /** {@code MOVE '0001' TO ABEND-CODE} - {@code :377}; the {@code WHEN OTHER} arm's abend code. */
    static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    /**
     * {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} - {@code :919}, including the full stop.
     *
     * <p>Guarded by {@code IF ABEND-MSG EQUAL LOW-VALUES} at {@code :918}, and
     * <strong>{@code app/cpy/CSMSG02Y.cpy} declares {@code ABEND-MSG PIC X(72) VALUE SPACES}</strong> -
     * spaces, not binary zeros. Nothing in {@code COACTVWC} ever writes {@code ABEND-MSG}, so the field
     * holds its {@code VALUE} when {@code ABEND-ROUTINE} tests it, the test fails, and this default is
     * never stored. The abend therefore reports a blank message. Transcribed because the move is in the
     * source; left unreachable for the same reason (practice B5).
     */
    static final String UNEXPECTED_ABEND_OCCURRED = PIC_X_CODEC.movePicX(
            "UNEXPECTED ABEND OCCURRED.", SystemMessages.ABEND_MSG_LENGTH);

    /** {@code EXEC CICS ABEND ABCODE('9999')} - {@code :934-936}; where {@code ABEND-ROUTINE} ends. */
    static final String ABEND_ROUTINE_ABCODE = "9999";

    /**
     * The single character {@code 1300-SETUP-SCREEN-ATTRS} moves into {@code ACCTSIDO} at {@code :563} to
     * mark a field the operator left blank, and the same character {@code 2200-EDIT-MAP-INPUTS} recognises
     * on the way back in at {@code :628}. Taken from the payload type so the mark written and the mark read
     * cannot drift apart.
     */
    static final String BLANK_FIELD_MARKER = AccountViewResponse.BLANK_FIELD_MARKER;

    // =================================================================================================
    // WS-FILE-ERROR-MESSAGE - app/cbl/COACTVWC.cbl:86-105. Eight items summing to exactly 80 characters,
    // transcribed part by part so a reviewer can add them up against the copybook. The sum is asserted at
    // class initialisation and again on every composition, because a mistranscribed filler would shift
    // every character to its right and the parity differ would report it as a data fault.
    // =================================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} - {@code :87-88}. */
    static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :89-90}; holds {@code 'READ'} in all three arms. */
    static final int ERROR_OPNAME_LENGTH = 8;

    /** {@code FILLER PIC X(4) VALUE ' on '} - {@code :91-92}. */
    static final String FILE_ERROR_ON = " on ";

    /**
     * {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :93-94}. One character wider than the
     * {@code PIC X(8)} file-name literals moved into it, so the ninth character is always a space.
     */
    static final int ERROR_FILE_LENGTH = 9;

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} - {@code :95-97}. */
    static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /**
     * {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :98-99}, and {@code ERROR-RESP2} at
     * {@code :102-103}. Both are ten characters and both receive a nine-digit binary field, so both end
     * with one space.
     */
    static final int ERROR_RESP_LENGTH = 10;

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} - {@code :100-101}. */
    static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5) VALUE SPACES} - {@code :104-105}; the tail that completes 80 characters. */
    static final String FILE_ERROR_TRAILER = "     ";

    /**
     * The digits in {@code WS-RESP-CD PIC S9(09) COMP} - {@code :40-41}. Moving it to a {@code PIC X(10)}
     * item renders nine digits, left justified, and leaves the tenth character a space.
     */
    static final int RESP_CODE_DIGITS = 9;

    /**
     * The composed width of {@code WS-FILE-ERROR-MESSAGE}: 80 characters. Moving it to
     * {@code WS-RETURN-MSG PIC X(75)} at {@code :766}, {@code :816} and {@code :865} therefore discards
     * the last five - which are exactly {@link #FILE_ERROR_TRAILER}, so no information is lost.
     */
    static final int FILE_ERROR_MESSAGE_LENGTH =
            FILE_ERROR_PREFIX.length() + ERROR_OPNAME_LENGTH + FILE_ERROR_ON.length()
                    + ERROR_FILE_LENGTH + FILE_ERROR_RETURNED_RESP.length() + ERROR_RESP_LENGTH
                    + FILE_ERROR_RESP2.length() + ERROR_RESP_LENGTH + FILE_ERROR_TRAILER.length();

    /** {@code MOVE 'READ' TO ERROR-OPNAME} - {@code :762}, {@code :812} and {@code :861}. */
    static final String READ_OPERATION_NAME = "READ";

    // =================================================================================================
    // The three STRING compositions the NOTFND arms perform instead of setting a condition name. Each
    // literal is transcribed with its exact spacing - ' Cross ref file.  Resp:' has two spaces after the
    // full stop, ' Acct Master file.Resp:' has none, and ' in customer master.Resp: ' has a trailing one.
    // Each concatenation overflows WS-RETURN-MSG PIC X(75) and is therefore truncated, which is what
    // COBOL's STRING does when the receiver fills up.
    // =================================================================================================

    /** {@code 'Account:'} - {@code :748} and {@code :797}. */
    static final String STRING_ACCOUNT_PREFIX = "Account:";

    /** {@code ' not found in'} - {@code :750} and {@code :799}. */
    static final String STRING_NOT_FOUND_IN = " not found in";

    /** {@code ' Cross ref file.  Resp:'} - {@code :751}; two spaces before {@code Resp}. */
    static final String STRING_CROSS_REF_FILE = " Cross ref file.  Resp:";

    /** {@code ' Acct Master file.Resp:'} - {@code :800}; no space before {@code Resp}. */
    static final String STRING_ACCT_MASTER_FILE = " Acct Master file.Resp:";

    /** {@code ' Reas:'} - {@code :753} and {@code :802}. */
    static final String STRING_REAS = " Reas:";

    /** {@code 'CustId:'} - {@code :847}. */
    static final String STRING_CUSTID_PREFIX = "CustId:";

    /** {@code ' not found'} - {@code :849}; shorter than the account variant, which adds {@code ' in'}. */
    static final String STRING_NOT_FOUND = " not found";

    /** {@code ' in customer master.Resp: '} - {@code :850}; note the trailing space. */
    static final String STRING_IN_CUSTOMER_MASTER = " in customer master.Resp: ";

    /** {@code ' REAS:'} - {@code :852}; upper case here, mixed case in {@link #STRING_REAS}. */
    static final String STRING_REAS_UPPER = " REAS:";

    // =================================================================================================
    // Request parameter names and bounds. EIBAID and EIBCALEN are EIB fields, not screen fields, so they
    // arrive as query parameters rather than in the payload.
    // =================================================================================================

    /**
     * The canonical spelling of the attention-identifier parameter, matching every other online screen in
     * the module. {@link #EIBAID_PARAM_ALIAS} is accepted too, so a client written against either
     * spelling reaches the same code.
     */
    static final String EIBAID_PARAM = "eibaid";

    /** The accepted alternate spelling of {@link #EIBAID_PARAM}. */
    static final String EIBAID_PARAM_ALIAS = "eibAid";

    /** The name of the query parameter carrying {@code EIBCALEN}. */
    static final String EIBCALEN_PARAM = "eibcalen";

    /**
     * The payload member the URI's account identifier binds, spelled as the client sends it.
     *
     * <p>Lowercase and {@code xxxI}-derived, which is this module's one JSON naming convention, so a
     * refusal names the member the caller can find in its own request body.
     */
    static final String ACCTSID_MEMBER = "acctsid";
    /**
     * The one-character image a screen paints into a key field when no criterion was supplied.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:563} and {@code app/cbl/COCRDSLC.cbl:543,549} move {@code '*'} into
     * the output field, and {@code COACTVWC:628}, {@code COACTUPC:1051} and {@code COCRDSLC:615,622} read
     * {@code = '*'} back as "not supplied". So an asterisk names no record, and a client echoing that
     * painted screen is agreeing with the URI rather than contradicting it.
     */
    static final String NO_CRITERION_IMAGE = "*";


    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MAX = 255;

    static {
        if (FILE_ERROR_MESSAGE_LENGTH != 80) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE is declared as eight items summing to 80 "
                    + "characters at app/cbl/COACTVWC.cbl:86-105, but the transcribed parts sum to "
                    + FILE_ERROR_MESSAGE_LENGTH + "; a filler has been mistranscribed");
        }
        if (PASSED_COMMAREA_LENGTH != 172) {
            throw new AssertionError("The area app/cbl/COACTVWC.cbl:288-292 splits must be exactly 172 "
                    + "characters - CARDDEMO-COMMAREA (160) then WS-THIS-PROGCOMMAREA (12) - but the "
                    + "parts sum to " + PASSED_COMMAREA_LENGTH);
        }
        if (WS_PROMPT_FOR_INPUT_TEXT.length() != WS_INFO_MSG_LENGTH) {
            throw new AssertionError("88 WS-PROMPT-FOR-INPUT fills WS-INFO-MSG PIC X(40) exactly at "
                    + "app/cbl/COACTVWC.cbl:113-114, but the transcribed text is "
                    + WS_PROMPT_FOR_INPUT_TEXT.length() + " characters");
        }
        requireLiteral(LIT_ACCTFILENAME, "ACCTDAT ", "LIT-ACCTFILENAME", 185);
        requireLiteral(LIT_CUSTFILENAME, "CUSTDAT ", "LIT-CUSTFILENAME", 189);
        requireLiteral(LIT_CARDXREFNAME_ACCT_PATH, "CXACAIX ", "LIT-CARDXREFNAME-ACCT-PATH", 193);
        requireLiteral(READ_OPERATION_NAME, "READ", "ERROR-OPNAME's only value", 762);
        requireLiteral(AccountViewResponse.THIS_PROGRAM, LIT_THISPGM, "LIT-THISPGM", 144);
        requireLiteral(AccountViewResponse.THIS_TRANID, LIT_THISTRANID, "LIT-THISTRANID", 146);
        requireLiteral(AccountViewResponse.THIS_MAPSET, LIT_THISMAPSET, "LIT-THISMAPSET", 148);
        requireLiteral(AccountViewResponse.MAP_NAME, LIT_THISMAP, "LIT-THISMAP", 150);
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20-22");
    }

    /**
     * Asserts that a constant this class resolved through another type still equals the literal the COBOL
     * declares, naming the source line so a mismatch is traced in one step.
     *
     * @param actual     the value this class resolved
     * @param expected   the literal {@code app/cbl/COACTVWC.cbl} declares
     * @param cobolName  the COBOL data name, for the message
     * @param cobolLine  the line of {@code app/cbl/COACTVWC.cbl} that declares it
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COACTVWC.cbl:" + cobolLine);
    }

    /**
     * Asserts a transcribed literal, for constants whose source is a copybook rather than the program.
     *
     * @param actual    the value this class resolved
     * @param expected  the declared literal
     * @param cobolName the COBOL data name, for the message
     * @param where     the file and line that declares it
     */
    private static void requireLiteral(String actual, String expected, String cobolName, String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    // =================================================================================================
    // Collaborators. Three repositories for the three files this program reads, and a clock for the two
    // FUNCTION CURRENT-DATE calls. All final, all constructor injected, none static (practice B9, gate
    // G53). The two files COACTVWC names but never reads - CARDDAT and its CARDAIX path - are deliberately
    // absent: injecting a repository this program does not use would misstate its dependencies.
    // =================================================================================================

    /** The {@code ACCTDAT} access path read by {@code 9300-GETACCTDATA-BYACCT} at {@code :776-784}. */
    private final AccountRepository accountRepository;

    /**
     * The {@code CCXREF} cluster, read through its {@code CXACAIX} alternate-index finder by
     * {@code 9200-GETCARDXREF-BYACCT} at {@code :727-735}.
     */
    private final CardXrefRepository cardXrefRepository;

    /** The {@code CUSTDAT} access path read by {@code 9400-GETCUSTDATA-BYCUST} at {@code :826-834}. */
    private final CustomerRepository customerRepository;

    /**
     * The instant {@code 1100-SCREEN-INIT} reads. Injected rather than read inline so a parity case is
     * reproducible: {@code FUNCTION CURRENT-DATE} appears twice, at {@code :434} and {@code :441}, and
     * both reads are reproduced.
     */
    private final Clock clock;

    /**
     * Constructs the controller.
     *
     * @param accountRepository  the account master, {@code ACCTDAT}
     * @param cardXrefRepository the card cross reference and its {@code CXACAIX} path
     * @param customerRepository the customer master, {@code CUSTDAT}
     * @param clock              the clock behind {@code FUNCTION CURRENT-DATE}
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public AccountViewController(AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            CustomerRepository customerRepository,
            Clock clock) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An AccountRepository is required: 9300-GETACCTDATA-BYACCT reads ACCTDAT at "
                        + "app/cbl/COACTVWC.cbl:776-784 and this controller reaches it no other way");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: 9200-GETCARDXREF-BYACCT reads the CXACAIX path at "
                        + "app/cbl/COACTVWC.cbl:727-735, and it is that read which supplies the customer "
                        + "id the third read needs");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "A CustomerRepository is required: 9400-GETCUSTDATA-BYCUST reads CUSTDAT at "
                        + "app/cbl/COACTVWC.cbl:826-834");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 1100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice, and reading a "
                        + "clock inline would make every parity case non-deterministic");
    }

    /**
     * The codec this controller applies the {@code PIC X} and {@code PIC 9} move rules with.
     *
     * <p>Exposed so a test can drive a move through the same instance the flow uses rather than a lookalike.
     *
     * @return the shared charset-neutral codec, never {@code null}
     */
    FixedWidthCodec codec() {
        return PIC_X_CODEC;
    }

    // =================================================================================================
    // The endpoint. CICS transaction CAVW, projected onto GET /api/accounts/{acctId}.
    //
    // The response is always 200 with a painted screen, because that is what the program does: every
    // branch of COACTVWC converges on COMMON-RETURN and sends a map, and a record that could not be found
    // is reported in ERRMSGO rather than by refusing to answer. So the "not found" case returns the
    // account-not-found text in a fully populated body, never an empty one.
    // =================================================================================================

    /**
     * Runs one {@code CAVW} interaction.
     *
     * @param acctId   the account identifier from the URI; the {@code RIDFLD} of the reads at
     *                 {@code :729} and {@code :778}, and the value {@code ACCTSIDI} would have carried
     * @param request  the terminal input area and the carried conversation state, or {@code null} on a
     *                 cold start, which is the {@code EIBCALEN = 0} case of {@code :282}
     * @param eibAid   {@code EIBAID} under its alternate spelling, or {@code null} for {@code DFHENTER}
     * @param eibcalen {@code EIBCALEN}, or {@code null} to derive it from the payload
     * @param eibaid   {@code EIBAID} under its canonical spelling
     * @return the painted {@code CACTVWAO} map area, the navigation context and the work area
     * @throws IllegalArgumentException if the URI, {@code EIBCALEN} or {@code EIBAID} cannot describe a
     *                                  state this program can be entered in
     * @throws AbendException           if the interaction abends, reproducing {@code ABEND-ROUTINE}
     */
    @GetMapping(path = "/api/accounts/{acctId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AccountViewResponse> viewAccount(
            @PathVariable("acctId") String acctId,
            @Valid @RequestBody(required = false) AccountViewRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        Objects.requireNonNull(acctId, "An account identifier is required in the path: it is the RIDFLD "
                + "of the reads at app/cbl/COACTVWC.cbl:729 and :778");
        AccountViewRequest received = bind(acctId, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier = resolveAttentionIdentifier(resolveAidParameter(eibaid, eibAid));
        return ResponseEntity.ok(handle(received, commareaLength, attentionIdentifier));
    }

    /**
     * Resolves the two accepted spellings of the attention-identifier parameter onto one value.
     *
     * <p>Both are declared on the endpoint so a client written against either reaches the same code. If
     * both arrive they must agree: silently preferring one would let a caller believe it had pressed a key
     * it had not.
     *
     * @param canonical the value of {@value #EIBAID_PARAM}, or {@code null}
     * @param alternate the value of {@value #EIBAID_PARAM_ALIAS}, or {@code null}
     * @return the single value the caller supplied, or {@code null} if neither was supplied
     * @throws IllegalArgumentException if both were supplied and they disagree
     */
    static Integer resolveAidParameter(Integer canonical, Integer alternate) {
        if (canonical == null) {
            return alternate;
        }
        if (alternate != null && !canonical.equals(alternate)) {
            throw ScreenInputRejectedException.contradictorySpellings(EIBAID_PARAM,
                    EIBAID_PARAM_ALIAS, "one EIBAID");
        }
        return canonical;
    }

    /**
     * Binds the URI's account identifier into {@code ACCTSIDI} and normalises what arrived.
     *
     * <p>Reproduces two things the terminal would have done. First, {@code ACCTSID} is
     * {@code DFHMDF ... LENGTH=11 PICIN='99999999999'} in {@code app/bms/COACTVW.bms:84-90}, so a value
     * shorter than eleven characters is space padded on the right by the {@code PIC X} move rule - and is
     * consequently <em>not numeric</em>, which is precisely why {@code 2210-EDIT-ACCOUNT} rejects it. A
     * value longer than eleven is rejected outright rather than truncated, because keeping the leading
     * eleven characters would silently address a different account than the URI names.
     *
     * <p>Second, the request is copied rather than mutated in place, so a caller's object is never
     * altered by having been passed here.
     *
     * <h4>The payload's own key field must not contradict the URI</h4>
     * This route states the record's key twice - in the URI and in the screen field the URI binds - and
     * a terminal has only one. The payload's member is therefore required to agree before it is
     * overwritten: absent, blank, {@code LOW-VALUES} or the URI's key is accepted, anything else is
     * refused at the boundary by
     * {@link ScreenInputRejectedException#requireKeyAgreement(String, String, String, int, FixedWidthCodec)}.
     * Overwriting it silently, which is what happened before, discarded the operator's own typed key with
     * no message. A client that echoes a painted screen agrees with the URI and never reaches the
     * refusal.
     *
     * @param acctId  the account identifier from the URI
     * @param request the received payload, or {@code null} on a cold start
     * @return a request whose {@code ACCTSIDI} is the eleven-character image of {@code acctId}
     * @throws IllegalArgumentException     if {@code acctId} is wider than {@code ACCTSIDI}
     * @throws ScreenInputRejectedException if the payload's {@code acctsid} names a different account
     */
    AccountViewRequest bind(String acctId, AccountViewRequest request) {
        if (acctId.length() > AccountViewRequest.ACCTSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(ACCTSID_MEMBER,
                    "ACCTSIDI PIC 9(" + AccountViewRequest.ACCTSID_LENGTH
                            + ") - LENGTH=11 in app/bms/COACTVW.bms:84-90",
                    AccountViewRequest.ACCTSID_LENGTH, acctId.length());
        }
        AccountViewRequest received =
                request == null ? coldStartRequest() : new AccountViewRequest(request);
        ScreenInputRejectedException.requireKeyAgreement(ACCTSID_MEMBER, acctId,
                received.getAcctsid(), AccountViewRequest.ACCTSID_LENGTH, PIC_X_CODEC, NO_CRITERION_IMAGE);
        received.setAcctsid(PIC_X_CODEC.movePicX(acctId, AccountViewRequest.ACCTSID_LENGTH));
        return received;
    }

    /**
     * The state a terminal that has never been written to is in: every one of the 37 input items blank and
     * every length, flag and attribute item reset. This is the {@code EIBCALEN = 0} entry of {@code :282},
     * for which the program initialises both communication areas rather than reading either.
     *
     * @return a blank map area carrying no communication area
     */
    private static AccountViewRequest coldStartRequest() {
        AccountViewRequest cold = new AccountViewRequest();
        cold.initializeMapArea();
        return cold;
    }

    /**
     * Resolves {@code EIBCALEN}.
     *
     * <p>{@code COACTVWC} reads {@code EIBCALEN} exactly twice, at {@code :282} and {@code :462}, and both
     * times only asks whether it is zero. So the accepted values are zero and the lengths of the areas this
     * program addresses: {@value NavigationContext#COMMAREA_LENGTH}, which is what
     * {@code AccountViewRequest.commareaLength()} reports for a payload carrying a context, and
     * {@value #PASSED_COMMAREA_LENGTH}, which is that area followed by {@code WS-THIS-PROGCOMMAREA}. All
     * three are unambiguous; anything else would be a length no area in this program has.
     *
     * <p>A stated value must agree with what arrived. {@code EIBCALEN} describes the area CICS passed, so
     * it cannot contradict the payload - and the contradiction matters, because {@code :282} uses it to
     * decide whether the conversation's state survives the turn.
     *
     * @param eibcalen the stated value, or {@code null} to derive it
     * @param request  the received payload
     * @return zero when no communication area arrived, otherwise a positive length
     * @throws IllegalArgumentException if the stated value is not a length this program addresses, or
     *                                  disagrees with the payload
     */
    static int resolveEibcalen(Integer eibcalen, AccountViewRequest request) {
        boolean carried = request.hasNavigationContext();
        if (eibcalen == null) {
            return carried ? PASSED_COMMAREA_LENGTH : NO_COMMAREA_LENGTH;
        }
        int stated = eibcalen;
        if (stated != NO_COMMAREA_LENGTH
                && stated != NavigationContext.COMMAREA_LENGTH
                && stated != PASSED_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", but CICS sets EIBCALEN to the length of the area it passed - which for this "
                    + "program is " + NO_COMMAREA_LENGTH + ", " + NavigationContext.COMMAREA_LENGTH
                    + " (CARDDEMO-COMMAREA) or " + PASSED_COMMAREA_LENGTH
                    + " (CARDDEMO-COMMAREA plus WS-THIS-PROGCOMMAREA).");
        }
        if ((stated == NO_COMMAREA_LENGTH) == carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried ? "a" : "no")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COACTVWC.cbl:282 uses it to decide whether the conversation's "
                    + "state survives the turn.");
        }
        return stated;
    }

    /**
     * Resolves {@code EIBAID}.
     *
     * <p>An absent parameter is {@link CicsAid#DFHENTER}, which is the key a terminal sends when a screen
     * is submitted with no function key - and the key this screen treats as "show me this account".
     *
     * @param eibAid the raw attention-identifier byte as an unsigned integer, or {@code null}
     * @return the {@code EIBAID} byte
     * @throws IllegalArgumentException if the value cannot be one byte
     */
    static byte resolveAttentionIdentifier(Integer eibAid) {
        if (eibAid == null) {
            return CicsAid.DFHENTER;
        }
        int value = eibAid;
        if (value < AID_MIN || value > AID_MAX) {
            throw ScreenInputRejectedException.outsideRange(EIBAID_PARAM,
                    "one EIBAID byte", AID_MIN, AID_MAX);
        }
        return (byte) value;
    }

    // =================================================================================================
    // 0000-MAIN - app/cbl/COACTVWC.cbl:261-413.
    // =================================================================================================

    /**
     * Runs one interaction, from {@code EXEC CICS HANDLE ABEND} to {@code EXEC CICS RETURN}.
     *
     * <p>The {@code try} reproduces {@code HANDLE ABEND LABEL(ABEND-ROUTINE)} at {@code :264-266}: any
     * failure the flow does not itself handle lands in {@link #abendRoutine}, exactly as any abend on the
     * mainframe would branch to that label. An {@link AbendException} already in flight is rethrown
     * untouched, because {@code ABEND-ROUTINE} cancels the handler at {@code :930-932} before abending and
     * so cannot re-enter itself.
     *
     * @param request  the terminal input area and carried state
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     * @return the painted map area
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws AbendException       if the interaction abends
     */
    AccountViewResponse handle(AccountViewRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COACTVWC is entered with a terminal input "
                + "area, and an absent one is spaces rather than nothing");
        Conversation task = new Conversation();
        try {
            main0000(request, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, abend);
        }
        return task.cactvwao.build();
    }

    /**
     * {@code 0000-MAIN} - {@code :262-393}, statement for statement in source order.
     *
     * <p>The three {@code GO TO COMMON-RETURN}s at {@code :360}, {@code :367} and {@code :373} are
     * early exits from the paragraph, so each arm of {@link #dispatch0000} marks the task returned and the
     * two guards below are skipped - which is what the {@code GO TO} achieves and why neither guard can run
     * twice.
     *
     * @param request  the terminal input area
     * @param task     the conversation being run
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     */
    void main0000(AccountViewRequest request, Conversation task, int eibcalen, byte eibAid) {
        // :268-270 - INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE and WS-COMMAREA. Note which area is absent
        // from that list: WS-THIS-PROGCOMMAREA is initialised only inside the IF at :285-286.
        initializeStorage(request, task, eibcalen, eibAid);
        // :274 - MOVE LIT-THISTRANID TO WS-TRANID.
        task.wsTranid = PIC_X_CODEC.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);
        // :278 - SET WS-RETURN-MSG-OFF TO TRUE, so the message is blank before anything can set it.
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        // :282-293 - store the passed data, if any.
        restoreCommarea(task);
        // :299-300 - PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT.
        storePfKeyYYYY(task);
        // :306-314 - is the key valid here, and if not, pretend ENTER was pressed.
        coerceInvalidAid(task);
        // :323-383 - EVALUATE TRUE.
        dispatch0000(request, task);
        // :387-392 - the guard for an error that slipped through.
        if (!task.returned) {
            trailingInputErrorGuard(request, task);
        }
        // :394-406 - COMMON-RETURN. Reached by falling off the end of the EVALUATE as well as by the three
        // GO TOs, so it runs exactly once however the paragraph got here.
        if (!task.returned) {
            commonReturn(task);
        }
    }

    /**
     * {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} - {@code :268-270}.
     *
     * <p>{@code INITIALIZE} blanks alphanumeric items and zeroes numeric ones; it does not leave them
     * unset. That distinction decides real behaviour here, because a blanked {@code WS-EDIT-ACCT-FLAG} is
     * {@code FLG-ACCTFILTER-BLANK} - the condition {@code 1200-SETUP-SCREEN-VARS} tests at {@code :465} -
     * so on a path that never edits the field, the account number is painted as {@code LOW-VALUES} rather
     * than as a value.
     *
     * <p>{@code WS-THIS-PROGCOMMAREA} is deliberately not touched here: it is absent from the source's
     * {@code INITIALIZE} list and is set by {@link #restoreCommarea} instead.
     *
     * @param request  the terminal input area, which supplies the carried work area and context
     * @param task     the conversation to initialise
     * @param eibcalen {@code EIBCALEN}, kept because two paragraphs read it
     * @param eibAid   {@code EIBAID}, kept because {@code YYYY-STORE-PFKEY} reads it
     */
    void initializeStorage(AccountViewRequest request, Conversation task, int eibcalen, byte eibAid) {
        task.eibcalen = eibcalen;
        task.eibAid = eibAid;
        // CC-WORK-AREA of app/cpy/CVCRD01Y.cpy, copied at :207. The carried work area is copied in first,
        // then INITIALIZE blanks it - which is why the AID the caller sent does not survive: CCARD-AID is
        // set from EIBAID a few statements later, never from the payload.
        task.ccWorkArea = new CardScreenState(request.getCardScreenState());
        task.ccWorkArea.initializeWorkArea();
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
        task.wsTranid = CardScreenState.spaces(WS_TRANID_LENGTH);
        task.wsInputFlag = INITIALIZED_FLAG;
        task.wsPfkFlag = INITIALIZED_FLAG;
        task.wsEditAcctFlag = INITIALIZED_FLAG;
        task.wsEditCustFlag = INITIALIZED_FLAG;
        task.wsCardRidCardnum = CardScreenState.spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_CUST_ID_LENGTH);
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.wsAccountMasterReadFlag = INITIALIZED_FLAG;
        task.wsCustMasterReadFlag = INITIALIZED_FLAG;
        task.errorOpname = CardScreenState.spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = CardScreenState.spaces(ERROR_FILE_LENGTH);
        task.errorResp = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.wsLongMsg = CardScreenState.spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = WS_INFO_MSG_SPACES;
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        // ACCOUNT-RECORD (COPY CVACT01Y at :244) and CUSTOMER-RECORD (COPY CVCUS01Y at :254) are group
        // items in WORKING-STORAGE, so they exist whether or not a read has filled them - and an allocated
        // record holds exactly what COBOL leaves there: spaces in every PIC X field and zero in every
        // numeric one. They are modelled as always-present values rather than as Optionals precisely
        // because 1200-SETUP-SCREEN-VARS can reach ACCOUNT-RECORD without a successful account read; see
        // projectAccountRecord1200.
        task.accountRecord = new AccountRecord(PIC_X_CODEC.charset());
        task.customerRecord = new CustomerRecord();
        // CARD-XREF-RECORD (COPY CVACT03Y at :251) is only ever read inside the arm that filled it, so an
        // absent value can be represented as absent without any path being able to observe it.
        task.cardXrefRecord = Optional.empty();
        // ABEND-DATA of app/cpy/CSMSG02Y.cpy, copied at :238.
        task.abendData = SystemMessages.AbendData.spaces();
        // WS-COMMAREA PIC X(2000) at :218, the third area INITIALIZE blanks.
        task.wsCommarea = CardScreenState.spaces(WS_COMMAREA_LENGTH);
        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();
        task.thisProgCommarea = ThisProgCommarea.initialized();
        // CACTVWAO is working storage too - the map area declared by COPY COACTVW at :229 - so it is
        // created here and painted by the 1000-SEND-MAP family. The builder is the only route into the
        // immutable payload, which makes it the natural stand-in for the group item.
        task.cactvwao = AccountViewResponse.builder();
    }

    /**
     * {@code IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)} -
     * {@code :282-293}.
     *
     * <p>The compound condition is reproduced term for term, including the {@code NOT
     * CDEMO-PGM-REENTER} conjunct that makes arriving from the main menu a fresh start only when the menu
     * has not already been through this screen once.
     *
     * <p>The {@code ELSE} splits {@code DFHCOMMAREA} into its two areas by offset. In the REST projection
     * the first 160 bytes are the payload's {@link NavigationContext}, which has already been copied into
     * the task; the twelve that follow are {@code WS-THIS-PROGCOMMAREA}, which no payload field carries
     * because {@code COACTVWC} never reads it - it splits it out at {@code :290-292} and packs it back at
     * {@code :398-400} without ever looking inside. So the projection carries the initialised image, and
     * the round trip is byte for byte what the program would have returned had CICS passed twelve spaces.
     *
     * @param task the conversation whose two communication areas are being established
     */
    void restoreCommarea(Conversation task) {
        boolean noCommareaPassed = task.eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(PIC_X_CODEC.movePicX(
                task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();
        if (noCommareaPassed || freshEntryFromMenu) {
            // :285-286 - INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA.
            task.carddemoCommarea = NavigationContext.empty();
            task.thisProgCommarea = ThisProgCommarea.initialized();
            return;
        }
        // :288-292 - the two moves out of DFHCOMMAREA, by offset: the first 160 characters into
        // CARDDEMO-COMMAREA and the next 12 into WS-THIS-PROGCOMMAREA. Both areas are rendered to their
        // fixed-width images and read back, which is not a no-op: a group MOVE imposes every field's
        // declared width, so a context carrying a short program name comes back space padded to PIC X(08),
        // exactly as the receiving area would hold it.
        task.carddemoCommarea = NavigationContext.fromFixedWidth(
                PIC_X_CODEC, task.carddemoCommarea.toFixedWidth(PIC_X_CODEC));
        task.thisProgCommarea = ThisProgCommarea.fromImage(task.thisProgCommarea.toImage());
    }

    /**
     * {@code YYYY-STORE-PFKEY} - the {@code COPY 'CSSTRPFY'} at {@code :913}, whose 28-arm
     * {@code EVALUATE TRUE} over {@code EIBAID} lives in {@link PfKeyResolver}.
     *
     * <p>The copybook's {@code EVALUATE} has no {@code WHEN OTHER} and does not clear {@code CCARD-AID}
     * first, so an attention identifier it has no arm for leaves the field holding whatever was already
     * there. {@link PfKeyResolver#storePfKey(byte, Optional)} returns exactly that - the current value
     * unchanged - and this method stores only what it returns. No default is substituted: the very next
     * paragraph relies on an unrecognised key leaving a value its two tests will both reject.
     *
     * @param task the conversation whose work area records the key
     */
    void storePfKeyYYYY(Conversation task) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(task.eibAid, task.ccWorkArea.aidKey());
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    /**
     * {@code SET PFK-INVALID TO TRUE} through {@code IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE} -
     * {@code :306-314}.
     *
     * <p>This screen supports two keys: {@code ENTER} and {@code PF3}. Anything else is marked invalid and
     * then <strong>rewritten to {@code ENTER}</strong>, so pressing PF7 here does not raise an error - it
     * displays the account, exactly as pressing ENTER would. That is the program's behaviour and is
     * preserved deliberately.
     *
     * @param task the conversation whose flag and AID are being settled
     */
    void coerceInvalidAid(Conversation task) {
        task.wsPfkFlag = PFK_INVALID;
        if (task.ccWorkArea.isCcardAidEnter() || task.ccWorkArea.isCcardAidPfk03()) {
            task.wsPfkFlag = PFK_VALID;
        }
        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    /**
     * {@code EVALUATE TRUE} - {@code :323-383}, four arms in source order with {@code WHEN OTHER} last
     * (gate G30).
     *
     * <p>The order is load bearing. {@code CCARD-AID-PFK03} is tested before either program-context arm,
     * so PF3 leaves the screen whatever context it arrived in; and because {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} with only {@code 0} and {@code 1} named, {@code WHEN OTHER} is reachable for any
     * other digit - which is why it is a real branch and not dead code.
     *
     * @param request the terminal input area
     * @param task    the conversation being dispatched
     */
    void dispatch0000(AccountViewRequest request, Conversation task) {
        if (task.ccWorkArea.isCcardAidPfk03()) {
            // :324-352 - XCTL to the calling program or the main menu.
            backNavigationPfk03(task);
            return;
        }
        if (task.carddemoCommarea.isEnter()) {
            // :353-360 - coming from some other context; the selection criteria are still to be gathered.
            sendMap1000(request, task);
            commonReturn(task);
            return;
        }
        if (task.carddemoCommarea.isReenter()) {
            // :361-374 - something was keyed; edit it, and read only if the edits passed.
            reenterProcessInputs(request, task);
            return;
        }
        // :375-382 - WHEN OTHER.
        unexpectedDataScenario(task);
    }

    /**
     * {@code WHEN CCARD-AID-PFK03} - {@code :324-352}.
     *
     * <p>Two identical fallbacks decide where to go: the transaction and program to return to are taken
     * from {@code CDEMO-FROM-TRANID} and {@code CDEMO-FROM-PROGRAM}, and each falls back to the main menu
     * when its field is {@code LOW-VALUES} or {@code SPACES}. This screen then records itself as the
     * program being left, and marks the target's context as {@code ENTER} so the target paints rather than
     * validates.
     *
     * <p>{@code SET CDEMO-USRTYP-USER TO TRUE} at {@code :344} is unconditional: leaving this screen always
     * reports the user type as {@code 'U'}, <strong>even for an administrator</strong>. That is the
     * program's behaviour, it is almost certainly not what its author intended, and correcting it would be a
     * change to a business rule - so it is preserved (practice B5).
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at {@code :349-352}
     * becomes {@code nextProgram} on the response plus the updated context (gate G40). The server performs
     * no forward and issues no redirect; the client makes the next call, which is what keeps the
     * conversation stateless (rule R6).
     *
     * <p>Note what this arm does <em>not</em> do: it neither paints the map nor reaches
     * {@code COMMON-RETURN}, because {@code XCTL} does not return. So no error message is moved to
     * {@code CCARD-ERROR-MSG} and none of the 37 output fields is written - the map area stays as
     * {@code AccountViewResponse.builder()} left it.
     *
     * @param task the conversation being transferred away from
     */
    void backNavigationPfk03(Conversation task) {
        NavigationContext commarea = task.carddemoCommarea;
        // :328-333 - IF CDEMO-FROM-TRANID EQUAL LOW-VALUES OR EQUAL SPACES.
        String toTranid =
                isLowValuesOrSpaces(commarea.fromTranid(), NavigationContext.FROM_TRANID_LENGTH)
                        ? LIT_MENUTRANID
                        : commarea.fromTranid();
        // :334-339 - the same test on CDEMO-FROM-PROGRAM.
        String toProgram =
                isLowValuesOrSpaces(commarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH)
                        ? LIT_MENUPGM
                        : commarea.fromProgram();
        commarea = commarea
                .withToTranid(PIC_X_CODEC.movePicX(toTranid, NavigationContext.TO_TRANID_LENGTH))
                .withToProgram(PIC_X_CODEC.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH))
                // :341-342 - this screen becomes the from-program.
                .withFromTranid(
                        PIC_X_CODEC.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH))
                .withFromProgram(
                        PIC_X_CODEC.movePicX(LIT_THISPGM, NavigationContext.FROM_PROGRAM_LENGTH))
                // :344 - and always as a plain user, admin or not.
                .withUserTypeUser()
                // :345 - the target is entered, not re-entered.
                .withPgmEnter()
                // :346-347 - LIT-THISMAPSET is PIC X(8) and CDEMO-LAST-MAPSET is PIC X(7), so the move
                // discards the literal's trailing space. That is the move COBOL performs, not a defect.
                .withLastMapset(
                        PIC_X_CODEC.movePicX(LIT_THISMAPSET, NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(PIC_X_CODEC.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));
        task.carddemoCommarea = commarea;
        task.cactvwao
                .navigationContext(commarea)
                .cardScreenState(task.ccWorkArea)
                .nextProgram(commarea.toProgram());
        task.returned = true;
    }

    /**
     * {@code WHEN CDEMO-PGM-REENTER} - {@code :361-374}.
     *
     * <p>Edit first, and read only if the edits passed. The {@code ELSE} is what makes the read
     * conditional: a failed edit paints the screen with the message the edit produced and never touches a
     * file.
     *
     * @param request the terminal input area
     * @param task    the conversation being processed
     */
    void reenterProcessInputs(AccountViewRequest request, Conversation task) {
        // :362-363 - PERFORM 2000-PROCESS-INPUTS THRU 2000-PROCESS-INPUTS-EXIT.
        processInputs2000(request, task);
        if (task.inputError()) {
            // :365-367 - send the map and GO TO COMMON-RETURN.
            sendMap1000(request, task);
            commonReturn(task);
            return;
        }
        // :369-373 - read, send, and GO TO COMMON-RETURN.
        readAcct9000(task);
        sendMap1000(request, task);
        commonReturn(task);
    }

    /**
     * {@code WHEN OTHER} - {@code :375-382}: a program context that is neither {@code ENTER} nor
     * {@code REENTER}.
     *
     * <p>Fills in {@code ABEND-DATA} - culprit, code {@value #UNEXPECTED_DATA_ABEND_CODE}, and a blank
     * reason - sets the message, and leaves through {@code SEND-PLAIN-TEXT}. Note that it does
     * <strong>not</strong> abend: {@code SEND-PLAIN-TEXT} performs {@code EXEC CICS SEND TEXT} followed by
     * a plain {@code EXEC CICS RETURN} at {@code :878-886}, so the transaction ends normally with the text
     * on the screen. The abend data is composed and then not used, which is exactly what the source does.
     *
     * @param task the conversation that arrived in an unexpected state
     */
    void unexpectedDataScenario(Conversation task) {
        task.abendData = task.abendData
                // :376 - MOVE LIT-THISPGM TO ABEND-CULPRIT.
                .withAbendCulprit(LIT_THISPGM)
                // :377 - MOVE '0001' TO ABEND-CODE.
                .withAbendCode(UNEXPECTED_DATA_ABEND_CODE)
                // :378 - MOVE SPACES TO ABEND-REASON.
                .withAbendReason(CardScreenState.spaces(SystemMessages.ABEND_REASON_LENGTH));
        // :379-380 - MOVE 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG.
        task.wsReturnMsg = UNEXPECTED_DATA_SCENARIO;
        // :381-382 - PERFORM SEND-PLAIN-TEXT THRU SEND-PLAIN-TEXT-EXIT.
        sendPlainText(task);
    }

    /**
     * {@code IF INPUT-ERROR ... GO TO COMMON-RETURN} - {@code :387-392}, the guard for "an error that
     * slipped through".
     *
     * <p>Unreachable from the two arms that already return, and therefore only reachable from
     * {@code WHEN OTHER} - which sets no error flag - so in practice it never fires. It is translated
     * anyway, because whether it fires depends on {@code WS-INPUT-FLAG} and not on this class's opinion.
     *
     * @param request the terminal input area
     * @param task    the conversation being checked
     */
    void trailingInputErrorGuard(AccountViewRequest request, Conversation task) {
        if (!task.inputError()) {
            return;
        }
        // :388 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG.
        task.ccWorkArea.setCcardErrorMsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        sendMap1000(request, task);
        commonReturn(task);
    }

    /**
     * {@code COMMON-RETURN} - {@code :394-406}.
     *
     * <p>The single assembly point every branch that returns converges on, which is why it is one method:
     * no branch can skip the error message, the repacked communication area or the returned state.
     *
     * <p>{@code MOVE CARDDEMO-COMMAREA TO WS-COMMAREA} followed by
     * {@code MOVE WS-THIS-PROGCOMMAREA TO WS-COMMAREA(161:12)} lays the two areas end to end inside a
     * {@code PIC X(2000)} field whose remaining 1828 characters stay as {@code INITIALIZE} left them -
     * spaces. {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID)} then hands that area back for the next
     * turn; here the two areas travel as the payload's own fields instead, which is the same information
     * without a server-side session (rule R6, gate G37).
     *
     * @param task the conversation being returned from
     */
    void commonReturn(Conversation task) {
        // :395 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG.
        task.ccWorkArea.setCcardErrorMsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        // :397-400 - repack both areas into WS-COMMAREA at their declared offsets: the 160-character
        // context first, then the 12-character trailer, then the 1828 spaces INITIALIZE left behind.
        String commareaImage = PIC_X_CODEC.decodeImage(
                task.carddemoCommarea.toFixedWidth(PIC_X_CODEC), "CARDDEMO-COMMAREA");
        task.wsCommarea = PIC_X_CODEC.movePicX(
                commareaImage + task.thisProgCommarea.toImage(), WS_COMMAREA_LENGTH);
        // :402-406 - EXEC CICS RETURN TRANSID('CAVW') COMMAREA(WS-COMMAREA).
        task.cactvwao
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        task.returned = true;
    }

    // =================================================================================================
    // 1000-SEND-MAP and its four paragraphs - app/cbl/COACTVWC.cbl:416-594.
    // =================================================================================================

    /**
     * {@code 1000-SEND-MAP} - {@code :416-425}: initialise the map area, fill in the variables, set the
     * attributes, send it. Performed from three places, and always in this order.
     *
     * @param request the terminal input area, which receives the cursor and attribute items
     * @param task    the conversation being painted
     */
    void sendMap1000(AccountViewRequest request, Conversation task) {
        screenInit1100(task);
        setupScreenVars1200(task);
        setupScreenAttrs1300(request, task);
        sendScreen1400(task);
    }

    /**
     * {@code 1100-SCREEN-INIT} - {@code :431-455}.
     *
     * <p>{@code MOVE LOW-VALUES TO CACTVWAO} at {@code :432} blanks the whole 955-byte group, which for a
     * BMS output map means "transmit nothing for any field I do not write". A fresh
     * {@link AccountViewResponse.Builder} is in exactly that state, so replacing the builder is the move.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} appears <strong>twice</strong>, at
     * {@code :434} and again at {@code :441}, with the titles moved in between. Both reads are performed
     * here. On a mainframe the two can return different instants and the second one wins; that is why the
     * clock is injected rather than read from a static, so a parity case can pin both.
     *
     * @param task the conversation whose map area is being initialised
     */
    void screenInit1100(Conversation task) {
        // :432 - MOVE LOW-VALUES TO CACTVWAO.
        task.cactvwao = AccountViewResponse.builder();
        // :434 - the first FUNCTION CURRENT-DATE. Its value is superseded below, exactly as in the source.
        task.dateHeader = DateHeader.from(PIC_X_CODEC, clock);
        // :436-437 - MOVE CCDA-TITLE01/CCDA-TITLE02 TO TITLE01O/TITLE02O of app/cpy/COTTL01Y.cpy.
        task.cactvwao.screenTitles();
        // :438-439 - MOVE LIT-THISTRANID TO TRNNAMEO and LIT-THISPGM TO PGMNAMEO.
        task.cactvwao.screenIdentity();
        // :441 - the second FUNCTION CURRENT-DATE, which is the one the screen shows.
        task.dateHeader = DateHeader.from(PIC_X_CODEC, clock);
        // :443-453 - mm/dd/yy into CURDATEO and hh:mm:ss into CURTIMEO, each PIC X(8).
        task.cactvwao.dateHeader(task.dateHeader);
    }

    /**
     * {@code 1200-SETUP-SCREEN-VARS} - {@code :460-535}.
     *
     * <p>On a cold start ({@code EIBCALEN = 0}) nothing is projected and only the prompt is set: there is
     * no account to show yet. Otherwise the account number is echoed, and the two record projections run
     * under their own guards.
     *
     * @param task the conversation being painted
     */
    void setupScreenVars1200(Conversation task) {
        if (task.eibcalen == NO_COMMAREA_LENGTH) {
            // :462-463 - IF EIBCALEN = 0 SET WS-PROMPT-FOR-INPUT TO TRUE.
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        } else {
            // :465-469 - a blank filter paints LOW-VALUES rather than the eleven characters of a value
            // that was never accepted. Note that INITIALIZE leaves WS-EDIT-ACCT-FLAG a space, so
            // FLG-ACCTFILTER-BLANK is true on any path that did not edit the field.
            if (task.flgAcctfilterBlank()) {
                task.cactvwao.acctsid(CardScreenState.lowValues(AccountViewResponse.ACCTSID_LENGTH));
            } else {
                task.cactvwao.acctsid(PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                        AccountViewResponse.ACCTSID_LENGTH));
            }
            // :471-491 - IF FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER.
            if (task.foundAcctInMaster() || task.foundCustInMaster()) {
                projectAccountRecord1200(task);
            }
            // :493-523 - IF FOUND-CUST-IN-MASTER.
            if (task.foundCustInMaster()) {
                projectCustomerRecord1200(task);
            }
        }
        // :528-530 - IF WS-NO-INFO-MESSAGE SET WS-PROMPT-FOR-INPUT TO TRUE. 9000-READ-ACCT blanks the
        // informational line at :689 and nothing sets WS-INFORM-OUTPUT, so this is why the prompt is the
        // only informational text this screen ever shows - even on a successful read.
        if (task.noInfoMessage()) {
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        }
        // :532 - MOVE WS-RETURN-MSG TO ERRMSGO: 75 characters into 78, so three spaces are appended.
        task.cactvwao.errmsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, AccountViewResponse.ERRMSG_LENGTH));
        // :534 - MOVE WS-INFO-MSG TO INFOMSGO: 40 characters into 45, so five spaces are appended.
        task.cactvwao.infomsg(
                PIC_X_CODEC.movePicX(task.wsInfoMsg, AccountViewResponse.INFOMSG_LENGTH));
    }

    /**
     * The ten moves out of {@code ACCOUNT-RECORD} - {@code :473-490}, in source order.
     *
     * <p>Five of the ten receivers are numeric edited, not alphanumeric:
     * {@code ACURBALO}, {@code ACRDLIMO}, {@code ACSHLIMO}, {@code ACRCYCRO} and {@code ACRCYDBO} are each
     * {@code PIC +ZZZ,ZZZ,ZZZ.99} - {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} with {@code LENGTH=15} in
     * {@code app/bms/COACTVW.bms}. So the amount is not copied, it is <em>edited</em>: sign forced,
     * leading zeros suppressed to spaces along with the commas they would have preceded, and the two
     * decimal digits always present because the mask ends {@code .99} and not {@code .ZZ}. The whole of
     * that rendering belongs to {@link AccountViewResponse#editAmount}, which the five
     * {@code ...Amount(BigDecimal)} builder methods route through - so this class states the mapping and
     * never re-implements the mask.
     *
     * <p>This paragraph is guarded by {@code FOUND-ACCT-IN-MASTER OR FOUND-CUST-IN-MASTER}, so it can run
     * when only the <em>customer</em> read succeeded and the account record was never filled. COBOL then
     * moves out of an untouched {@code WORKING-STORAGE} group - spaces and zeros - and so does this method,
     * because {@link Conversation#accountRecord} is an allocated record until a read replaces it. The
     * screen shows a blank status, blank dates and {@code +          .00} amounts, which is the source's
     * behaviour and not a defect to be corrected.
     *
     * @param task the conversation being painted
     */
    void projectAccountRecord1200(Conversation task) {
        AccountRecord account = task.accountRecord;
        // :473 - MOVE ACCT-ACTIVE-STATUS TO ACSTTUSO.
        task.cactvwao.acsttus(PIC_X_CODEC.movePicX(account.getAcctActiveStatus(),
                AccountViewResponse.ACSTTUS_LENGTH));
        // :475 - MOVE ACCT-CURR-BAL TO ACURBALO, PIC +ZZZ,ZZZ,ZZZ.99.
        task.cactvwao.acurbalAmount(account.getAcctCurrBal());
        // :477 - MOVE ACCT-CREDIT-LIMIT TO ACRDLIMO.
        task.cactvwao.acrdlimAmount(account.getAcctCreditLimit());
        // :479-480 - MOVE ACCT-CASH-CREDIT-LIMIT TO ACSHLIMO.
        task.cactvwao.acshlimAmount(account.getAcctCashCreditLimit());
        // :482-483 - MOVE ACCT-CURR-CYC-CREDIT TO ACRCYCRO.
        task.cactvwao.acrcycrAmount(account.getAcctCurrCycCredit());
        // :485 - MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO.
        task.cactvwao.acrcydbAmount(account.getAcctCurrCycDebit());
        // :487 - MOVE ACCT-OPEN-DATE TO ADTOPENO.
        task.cactvwao.adtopen(PIC_X_CODEC.movePicX(account.getAcctOpenDate(),
                AccountViewResponse.ADTOPEN_LENGTH));
        // :488 - MOVE ACCT-EXPIRAION-DATE TO AEXPDTO. The copybook misspells "EXPIRATION" and the Java
        // accessor keeps the misspelling, because a corrected field name would stop matching the copybook
        // the parity differ compares against.
        task.cactvwao.aexpdt(PIC_X_CODEC.movePicX(account.getAcctExpiraionDate(),
                AccountViewResponse.AEXPDT_LENGTH));
        // :489 - MOVE ACCT-REISSUE-DATE TO AREISDTO.
        task.cactvwao.areisdt(PIC_X_CODEC.movePicX(account.getAcctReissueDate(),
                AccountViewResponse.AREISDT_LENGTH));
        // :490 - MOVE ACCT-GROUP-ID TO AADDGRPO.
        task.cactvwao.aaddgrp(PIC_X_CODEC.movePicX(account.getAcctGroupId(),
                AccountViewResponse.AADDGRP_LENGTH));
    }

    /**
     * The eighteen moves out of {@code CUSTOMER-RECORD} - {@code :494-522}, in source order.
     *
     * <p>Three of them are worth reading twice. {@code ACSTSSNO} is not a move at all but the
     * {@code STRING} at {@code :496-504}, which lays {@code CUST-SSN(1:3)}, a hyphen,
     * {@code CUST-SSN(4:2)}, a hyphen and {@code CUST-SSN(6:4)} into an eleven-character prefix of a
     * {@code PIC X(12)} item - and {@code STRING} leaves the twelfth character exactly as it was, which
     * after {@code MOVE LOW-VALUES TO CACTVWAO} means a binary zero.
     * {@link AccountViewResponse.Builder#acstssnFromSsn} reproduces that, tail and all.
     *
     * <p>The other two are silent truncations that follow from the widths, not from any decision here:
     * {@code CUST-ADDR-ZIP} is {@code PIC X(10)} and {@code ACSZIPCO} is {@code PIC X(5)}, so the last five
     * characters are discarded; and {@code CUST-PHONE-NUM-1} and {@code -2} are {@code PIC X(15)} while
     * {@code ACSPHN1O} and {@code ACSPHN2O} are {@code PIC X(13)}, so two characters are discarded from
     * each. The {@code PIC X} move rule keeps the leading characters, which is what
     * {@link FixedWidthCodec#movePicX(String, int)} does.
     *
     * <p>Note also the field the screen labels "City": it is fed from {@code CUST-ADDR-LINE-3}, not from
     * any field named for a city.
     *
     * @param task the conversation being painted
     */
    void projectCustomerRecord1200(Conversation task) {
        CustomerRecord customer = task.customerRecord;
        // :494 - MOVE CUST-ID TO ACSTNUMO, PIC 9(09) into PIC X(9).
        task.cactvwao.acstnum(PIC_X_CODEC.movePic9(customer.getCustId(),
                AccountViewResponse.ACSTNUM_LENGTH));
        // :496-504 - the SSN STRING.
        task.cactvwao.acstssnFromSsn(customer.custSsnImage(PIC_X_CODEC));
        // :505-506 - MOVE CUST-FICO-CREDIT-SCORE TO ACSTFCOO, PIC 9(03) into PIC X(3).
        task.cactvwao.acstfco(PIC_X_CODEC.movePic9(customer.getCustFicoCreditScore(),
                AccountViewResponse.ACSTFCO_LENGTH));
        // :507 - MOVE CUST-DOB-YYYY-MM-DD TO ACSTDOBO.
        task.cactvwao.acstdob(PIC_X_CODEC.movePicX(customer.getCustDobYyyyMmDd(),
                AccountViewResponse.ACSTDOB_LENGTH));
        // :508-510 - the three name fields.
        task.cactvwao.acsfnam(PIC_X_CODEC.movePicX(customer.getCustFirstName(),
                AccountViewResponse.ACSFNAM_LENGTH));
        task.cactvwao.acsmnam(PIC_X_CODEC.movePicX(customer.getCustMiddleName(),
                AccountViewResponse.ACSMNAM_LENGTH));
        task.cactvwao.acslnam(PIC_X_CODEC.movePicX(customer.getCustLastName(),
                AccountViewResponse.ACSLNAM_LENGTH));
        // :511-512 - address lines 1 and 2.
        task.cactvwao.acsadl1(PIC_X_CODEC.movePicX(customer.getCustAddrLine1(),
                AccountViewResponse.ACSADL1_LENGTH));
        task.cactvwao.acsadl2(PIC_X_CODEC.movePicX(customer.getCustAddrLine2(),
                AccountViewResponse.ACSADL2_LENGTH));
        // :513 - MOVE CUST-ADDR-LINE-3 TO ACSCITYO: the third address line is what the screen calls City.
        task.cactvwao.acscity(PIC_X_CODEC.movePicX(customer.getCustAddrLine3(),
                AccountViewResponse.ACSCITY_LENGTH));
        // :514 - MOVE CUST-ADDR-STATE-CD TO ACSSTTEO.
        task.cactvwao.acsstte(PIC_X_CODEC.movePicX(customer.getCustAddrStateCd(),
                AccountViewResponse.ACSSTTE_LENGTH));
        // :515 - MOVE CUST-ADDR-ZIP TO ACSZIPCO: PIC X(10) into PIC X(5), keeping the leading five.
        task.cactvwao.acszipc(PIC_X_CODEC.movePicX(customer.getCustAddrZip(),
                AccountViewResponse.ACSZIPC_LENGTH));
        // :516 - MOVE CUST-ADDR-COUNTRY-CD TO ACSCTRYO.
        task.cactvwao.acsctry(PIC_X_CODEC.movePicX(customer.getCustAddrCountryCd(),
                AccountViewResponse.ACSCTRY_LENGTH));
        // :517-518 - the two telephone numbers, PIC X(15) into PIC X(13) each.
        task.cactvwao.acsphn1(PIC_X_CODEC.movePicX(customer.getCustPhoneNum1(),
                AccountViewResponse.ACSPHN1_LENGTH));
        task.cactvwao.acsphn2(PIC_X_CODEC.movePicX(customer.getCustPhoneNum2(),
                AccountViewResponse.ACSPHN2_LENGTH));
        // :519 - MOVE CUST-GOVT-ISSUED-ID TO ACSGOVTO.
        task.cactvwao.acsgovt(PIC_X_CODEC.movePicX(customer.getCustGovtIssuedId(),
                AccountViewResponse.ACSGOVT_LENGTH));
        // :520 - MOVE CUST-EFT-ACCOUNT-ID TO ACSEFTCO.
        task.cactvwao.acseftc(PIC_X_CODEC.movePicX(customer.getCustEftAccountId(),
                AccountViewResponse.ACSEFTC_LENGTH));
        // :521-522 - MOVE CUST-PRI-CARD-HOLDER-IND TO ACSPFLGO.
        task.cactvwao.acspflg(PIC_X_CODEC.movePicX(customer.getCustPriCardHolderInd(),
                AccountViewResponse.ACSPFLG_LENGTH));
    }

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS} - {@code :541-572}.
     *
     * <p>Writes to both halves of the map. The field attribute and the cursor position are {@code xxxA}
     * and {@code xxxL} items of the <em>input</em> group {@code CACTVWAI}, so they are set on the request's
     * per-field metadata; the colour is the {@code xxxC} item of the <em>output</em> group and is set on the
     * response's attribute quad. Neither is a payload field.
     *
     * <p>{@code COACTVWC} does not {@code COPY CSSETATY}: the highlight at {@code :561-565} is written
     * inline, and so is this. The two moves it makes - {@code DFHRED} to the colour item and {@code '*'} to
     * the output item - happen only when the filter is blank <strong>and</strong> the program is
     * re-entering, so a first-time visitor sees an empty field rather than an asterisk.
     *
     * @param request the terminal input area, whose metadata carries the cursor and the attribute
     * @param task    the conversation being painted
     */
    void setupScreenAttrs1300(AccountViewRequest request, Conversation task) {
        // :543 - MOVE DFHBMFSE TO ACCTSIDA OF CACTVWAI: unprotected, and with the modified-data tag set so
        // the field is always returned even when the operator retypes the same value.
        request.metadata(AccountViewRequest.ScreenField.ACCTSID).setAttribute(BmsAttributes.DFHBMFSE);
        // :546-552 - EVALUATE TRUE / WHEN FLG-ACCTFILTER-NOT-OK / WHEN FLG-ACCTFILTER-BLANK / WHEN OTHER.
        // All three arms move the same -1 to the same ACCTSIDL, so the cursor lands on the account number
        // whatever the flag holds. The two-way shape is kept rather than collapsed so that the source's
        // branch structure stays visible to a reviewer checking gate G30; the duplicated action is the
        // source's, not an oversight.
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            request.metadata(AccountViewRequest.ScreenField.ACCTSID).positionCursorHere();
        } else {
            request.metadata(AccountViewRequest.ScreenField.ACCTSID).positionCursorHere();
        }
        // :555 - MOVE DFHDFCOL TO ACCTSIDC OF CACTVWAO: back to the map's default colour first.
        task.cactvwao.attributes(AccountViewResponse.ScreenField.ACCTSID)
                .setColour(BmsAttributes.DFHDFCOL);
        // :557-559 - IF FLG-ACCTFILTER-NOT-OK MOVE DFHRED.
        if (task.flgAcctfilterNotOk()) {
            task.cactvwao.attributes(AccountViewResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }
        // :561-565 - IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER: mark the field and colour it red.
        if (task.flgAcctfilterBlank() && task.carddemoCommarea.isReenter()) {
            task.cactvwao.value(AccountViewResponse.ScreenField.ACCTSID, PIC_X_CODEC.movePicX(
                    BLANK_FIELD_MARKER, AccountViewResponse.ACCTSID_LENGTH));
            task.cactvwao.attributes(AccountViewResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }
        // :567-571 - the informational line is dark when there is nothing to say and neutral when there is.
        task.cactvwao.attributes(AccountViewResponse.ScreenField.INFOMSG)
                .setColour(task.noInfoMessage() ? BmsAttributes.DFHBMDAR : BmsAttributes.DFHNEUTR);
    }

    /**
     * {@code 1400-SEND-SCREEN} - {@code :577-591}.
     *
     * <p>{@code SET CDEMO-PGM-REENTER TO TRUE} at {@code :581} is what makes the conversation
     * pseudo-conversational: the screen that has just been sent expects its own reply, so the next call
     * arriving with this context takes the {@code REENTER} arm and validates instead of painting. The
     * client returns the context it was given, and no state is kept here (rule R6, gate G37).
     *
     * <p>{@code EXEC CICS SEND MAP ... RESP(WS-RESP-CD)} leaves the response code at {@code DFHRESP(NORMAL)}
     * on a successful send, which is the only outcome the program then acts on - it does not test the code
     * afterwards.
     *
     * @param task the conversation whose screen is being sent
     */
    void sendScreen1400(Conversation task) {
        // :579 - MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET, PIC X(8) into PIC X(7): the trailing space of
        // 'COACTVW ' is discarded, leaving 'COACTVW'.
        task.ccWorkArea.setCcardNextMapset(
                PIC_X_CODEC.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        // :580 - MOVE LIT-THISMAP TO CCARD-NEXT-MAP.
        task.ccWorkArea.setCcardNextMap(
                PIC_X_CODEC.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
        // :581 - SET CDEMO-PGM-REENTER TO TRUE.
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();
        // :583-590 - EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET) FROM(CACTVWAO).
        task.cactvwao
                .nextProgram(PIC_X_CODEC.movePicX(task.ccWorkArea.getCcardNextProg(),
                        AccountViewResponse.NEXT_PROGRAM_LENGTH))
                .nextMapset(task.ccWorkArea.getCcardNextMapset())
                .nextMap(task.ccWorkArea.getCcardNextMap())
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        task.wsRespCd = FileStatus.NORMAL;
    }

    // =================================================================================================
    // 2000-PROCESS-INPUTS and its two paragraphs - app/cbl/COACTVWC.cbl:596-685.
    // =================================================================================================

    /**
     * {@code 2000-PROCESS-INPUTS} - {@code :596-605}.
     *
     * <p>Receive, edit, then record where the conversation is: the message the edits produced, and this
     * program, mapset and map as the next target. The four moves at {@code :601-604} happen whether or not
     * the edits passed, which is what lets the {@code REENTER} arm paint an error screen that still points
     * back here.
     *
     * @param request the terminal input area
     * @param task    the conversation being processed
     */
    void processInputs2000(AccountViewRequest request, Conversation task) {
        receiveMap2100(task);
        editMapInputs2200(request, task);
        // :601 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG.
        task.ccWorkArea.setCcardErrorMsg(
                PIC_X_CODEC.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        // :602 - MOVE LIT-THISPGM TO CCARD-NEXT-PROG.
        task.ccWorkArea.setCcardNextProg(
                PIC_X_CODEC.movePicX(LIT_THISPGM, CardScreenState.CCARD_NEXT_PROG_LENGTH));
        // :603 - MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET, PIC X(8) into PIC X(7).
        task.ccWorkArea.setCcardNextMapset(
                PIC_X_CODEC.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        // :604 - MOVE LIT-THISMAP TO CCARD-NEXT-MAP.
        task.ccWorkArea.setCcardNextMap(
                PIC_X_CODEC.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    /**
     * {@code 2100-RECEIVE-MAP} - {@code :610-617}: {@code EXEC CICS RECEIVE MAP ... INTO(CACTVWAI)}.
     *
     * <p>Over HTTP the map area has already arrived as the request body, so what remains of the
     * {@code RECEIVE} is its effect on the response codes: a successful receive leaves
     * {@code DFHRESP(NORMAL)} in {@code WS-RESP-CD} and zero in {@code WS-REAS-CD}. The program does not
     * test either afterwards, but they are set because the next paragraph to write them is a file read, and
     * a stale value from an earlier turn would be reported as that read's outcome.
     *
     * @param task the conversation receiving the map
     */
    void receiveMap2100(Conversation task) {
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
    }

    /**
     * {@code 2200-EDIT-MAP-INPUTS} - {@code :622-643}.
     *
     * <p>Optimistic start: assume the input is good, then let the field edit and the cross-field edit
     * disagree. The two condition names set at {@code :624-625} are therefore the values the flags hold on
     * any path that reaches a file read.
     *
     * <p>The asterisk test at {@code :628-633} is the other half of the mark {@code 1300} writes: a screen
     * returned with {@code '*'} still in the account field is a screen the operator did not fill in, so the
     * mark is translated back to {@code LOW-VALUES} rather than treated as a value to search for.
     *
     * <p>The cross-field edit at {@code :640-642} overwrites whatever message the field edit had set,
     * which is why a blank field reports {@code 'No input received'} and not the more specific
     * {@code 'Account number not provided'} that {@code 2210-EDIT-ACCOUNT} placed there a moment earlier.
     *
     * @param request the terminal input area
     * @param task    the conversation being edited
     */
    void editMapInputs2200(AccountViewRequest request, Conversation task) {
        // :624-625 - SET INPUT-OK and FLG-ACCTFILTER-ISVALID TO TRUE.
        task.wsInputFlag = INPUT_OK;
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        // :628-633 - replace '*' or SPACES with LOW-VALUES; anything else is taken as keyed.
        String acctsidI = PIC_X_CODEC.movePicX(request.getAcctsid(), AccountViewRequest.ACCTSID_LENGTH);
        if (isAsteriskOrSpaces(acctsidI, AccountViewRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctIdToLowValues();
        } else {
            task.ccWorkArea.setCcAcctId(
                    PIC_X_CODEC.movePicX(acctsidI, CardScreenState.CC_ACCT_ID_LENGTH));
        }
        // :636-637 - PERFORM 2210-EDIT-ACCOUNT THRU 2210-EDIT-ACCOUNT-EXIT.
        editAccount2210(task);
        // :640-642 - the cross-field edit.
        if (task.flgAcctfilterBlank()) {
            task.wsReturnMsg = NO_SEARCH_CRITERIA_RECEIVED;
        }
    }

    /**
     * {@code 2210-EDIT-ACCOUNT} - {@code :649-681}.
     *
     * <p>Two guarded early exits, both of which the source reaches with {@code GO TO
     * 2210-EDIT-ACCOUNT-EXIT} and both of which become a plain {@code return} (rule R7). The order matters:
     * the not-supplied test runs first, so a blank field is reported as blank rather than as non-numeric.
     *
     * <p>Both guards only set the message {@code IF WS-RETURN-MSG-OFF} - if something has already put a
     * message there, it is left alone. That is the "first message wins" convention this program uses
     * throughout, and it is why the read paragraphs test the same condition before composing theirs.
     *
     * <p>The second guard is the one that does not do what its condition names suggest. It tests
     * {@code IF CC-ACCT-ID IS NOT NUMERIC OR CC-ACCT-ID EQUAL ZEROES} - the conditions
     * {@code SEARCHED-ACCT-NOT-NUMERIC} and {@code SEARCHED-ACCT-ZEROES} are named for - and then moves a
     * <strong>different literal</strong> at {@code :671-673}:
     * {@value #ACCOUNT_FILTER_NOT_NUMERIC_TEXT}. Note "Filter", the two spaces after "must", and the
     * hyphen in "non-zero", none of which appear in the condition names' text. Neither condition name is
     * ever set, so {@link #SEARCHED_ACCT_ZEROES} and {@link #SEARCHED_ACCT_NOT_NUMERIC} exist to document
     * the declaration and never to be stored.
     *
     * @param task the conversation whose account filter is being edited
     */
    void editAccount2210(Conversation task) {
        // :650 - SET FLG-ACCTFILTER-NOT-OK TO TRUE, before either test.
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        // :653-662 - not supplied.
        if (task.ccWorkArea.isCcAcctIdLowValues() || task.ccWorkArea.isCcAcctIdSpaces()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_ACCT;
            }
            // :660 - MOVE ZEROES TO CDEMO-ACCT-ID.
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }
        // :666-676 - not numeric, or numerically zero.
        if (!task.ccWorkArea.isCcAcctIdNumeric() || task.ccWorkArea.isCcAcctIdNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = ACCOUNT_FILTER_NOT_NUMERIC;
            }
            // :675 - MOVE ZERO TO CDEMO-ACCT-ID.
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }
        // :678-679 - accepted.
        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(PIC_X_CODEC.decodePic9(task.ccWorkArea.getCcAcctId()));
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    // =================================================================================================
    // 9000-READ-ACCT and its three read paragraphs - app/cbl/COACTVWC.cbl:687-872.
    //
    // Three reads, in this order, because each one supplies the next one's key: the cross reference is
    // read by account number and yields the customer id, the account master is read by the same account
    // number, and the customer master is read by the customer id the cross reference gave up.
    // =================================================================================================

    /**
     * {@code 9000-READ-ACCT} - {@code :687-718}.
     *
     * <p>The three guards between the reads are <strong>not</strong> equivalent, and this is the most
     * consequential finding in the whole translation.
     *
     * <p>The first, {@code IF FLG-ACCTFILTER-NOT-OK} at {@code :697}, tests a flag the cross-reference read
     * really does set, so a cross-reference miss really does stop the sequence. The comment above it -
     * {@code * IF DID-NOT-FIND-ACCT-IN-CARDXREF} at {@code :696} - shows what was intended and what was
     * replaced.
     *
     * <p>The second and third, {@code IF DID-NOT-FIND-ACCT-IN-ACCTDAT} at {@code :704} and
     * {@code IF DID-NOT-FIND-CUST-IN-CUSTDAT} at {@code :713}, test condition names whose {@code SET}
     * statements are commented out at {@code :792} and {@code :842}. Both are therefore comparisons against
     * a literal that {@code WS-RETURN-MSG} never holds, both are always false, and <strong>an account
     * missing from the master file still falls through to the customer read</strong> - with the customer id
     * the cross reference supplied, which is a perfectly readable key. So the screen can show customer
     * details beside a blank account, and {@link #projectAccountRecord1200} explains what that looks like.
     * The guards are translated as the value tests they are rather than as the flag tests they were meant
     * to be, because reproducing the intent instead of the code would change behaviour.
     *
     * @param task the conversation performing the reads
     */
    void readAcct9000(Conversation task) {
        // :689 - SET WS-NO-INFO-MESSAGE TO TRUE stores the FIRST value of the VALUES list, so spaces.
        task.wsInfoMsg = WS_INFO_MSG_SPACES;
        // :691 - MOVE CDEMO-ACCT-ID TO WS-CARD-RID-ACCT-ID, PIC 9(11) to PIC 9(11).
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(
                task.carddemoCommarea.acctId(), WS_CARD_RID_ACCT_ID_LENGTH);
        // :693-694 - PERFORM 9200-GETCARDXREF-BYACCT.
        getCardXrefByAcct9200(task);
        // :697-699 - the one guard that fires.
        if (task.flgAcctfilterNotOk()) {
            return;
        }
        // :701-702 - PERFORM 9300-GETACCTDATA-BYACCT.
        getAcctDataByAcct9300(task);
        // :704-706 - a value test against a literal nothing stores; always false.
        if (task.didNotFindAcctInAcctdat()) {
            return;
        }
        // :708 - MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID, PIC 9(09) to PIC 9(09).
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(
                task.carddemoCommarea.custId(), WS_CARD_RID_CUST_ID_LENGTH);
        // :710-711 - PERFORM 9400-GETCUSTDATA-BYCUST.
        getCustDataByCust9400(task);
        // :713-715 - the second value test; also always false, and the last statement of the paragraph.
        if (task.didNotFindCustInCustdat()) {
            return;
        }
    }

    /**
     * {@code 9200-GETCARDXREF-BYACCT} - {@code :723-770}.
     *
     * <p>{@code EXEC CICS READ DATASET(LIT-CARDXREFNAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID-X)
     * KEYLENGTH(LENGTH OF WS-CARD-RID-ACCT-ID-X)} reads the {@code CXACAIX} <em>path over the CCXREF base
     * cluster</em> by its alternate key. That is one dataset reached two ways, never two datasets, so it is
     * a second finder on the one repository (gate G45). The key is the {@code PIC X(11)} {@code REDEFINES}
     * view of the account number, not the numeric view (gate G34).
     *
     * <p>The {@code NOTFND} arm at {@code :741-758} does not set {@code DID-NOT-FIND-ACCT-IN-CARDXREF}; it
     * composes a message with {@code STRING} that names the account and reports both response codes. The
     * concatenation is 81 characters and {@code WS-RETURN-MSG} is 75, and {@code STRING} stops when its
     * receiver is full - so the message is truncated four characters into {@code ERROR-RESP2}. That is
     * reproduced rather than corrected.
     *
     * @param task the conversation performing the read
     */
    void getCardXrefByAcct9200(Conversation task) {
        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(task.wsCardRidAcctId);
        task.wsRespCd = result.cicsResp();
        task.wsReasCd = result.cicsResp2();
        task.cardXrefRecord = result.record();
        if (result.isFound()) {
            // :738-740 - WHEN DFHRESP(NORMAL).
            CardXrefRecord xref = result.record().orElseThrow(() -> new IllegalStateException(
                    "CardXrefRepository reported a successful read of the " + LIT_CARDXREFNAME_ACCT_PATH
                            + " path without a record. app/cbl/COACTVWC.cbl:739-740 moves XREF-CUST-ID and "
                            + "XREF-CARD-NUM out of the record area straight after DFHRESP(NORMAL), so a "
                            + "successful read without a record is a broken contract rather than a "
                            + "NOTFND."));
            // :739 - MOVE XREF-CUST-ID TO CDEMO-CUST-ID.
            task.carddemoCommarea = task.carddemoCommarea.withCustId(xref.xrefCustId());
            // :740 - MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM: PIC X(16) into PIC 9(16).
            task.carddemoCommarea =
                    task.carddemoCommarea.withCardNum(carriedCardNumber(xref.xrefCardNum()));
            return;
        }
        if (result.isNotFound()) {
            // :741-758 - WHEN DFHRESP(NOTFND).
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                // :745-746 - the two response codes are moved into the message's own fields first.
                task.errorResp = responseCodeImage(task.wsRespCd);
                task.errorResp2 = responseCodeImage(task.wsReasCd);
                // :747-757 - STRING ... DELIMITED BY SIZE INTO WS-RETURN-MSG.
                task.wsReturnMsg = PIC_X_CODEC.movePicX(STRING_ACCOUNT_PREFIX
                        + task.wsCardRidAcctId
                        + STRING_NOT_FOUND_IN
                        + STRING_CROSS_REF_FILE
                        + task.errorResp
                        + STRING_REAS
                        + task.errorResp2, WS_RETURN_MSG_LENGTH);
            }
            return;
        }
        // :759-766 - WHEN OTHER.
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        recordFileError(task, LIT_CARDXREFNAME_ACCT_PATH);
    }

    /**
     * {@code 9300-GETACCTDATA-BYACCT} - {@code :774-820}.
     *
     * <p>{@code EXEC CICS READ DATASET(LIT-ACCTFILENAME) RIDFLD(WS-CARD-RID-ACCT-ID-X)} reads the account
     * master by its primary key. The repository's keyed read takes the key as a number, so the character
     * view is converted back with {@link FixedWidthCodec#decodePic9(String)} - the exact inverse of the
     * {@code movePic9} that produced it. That conversion is always safe here: this paragraph is only
     * reached from {@code 9000-READ-ACCT}, which is only performed when the edits passed, and
     * {@code 2210-EDIT-ACCOUNT} accepts nothing but eleven digits.
     *
     * <p>Only the {@code NORMAL} arm sets {@code FOUND-ACCT-IN-MASTER}; the other two leave the read flag
     * as {@code INITIALIZE} left it, which is why {@code 1200-SETUP-SCREEN-VARS} tests it rather than
     * assuming the record is filled.
     *
     * @param task the conversation performing the read
     */
    void getAcctDataByAcct9300(Conversation task) {
        AccountRepository.ReadResult result =
                accountRepository.readByKey(PIC_X_CODEC.decodePic9(task.wsCardRidAcctId));
        task.wsRespCd = cicsResp(result.cicsResp(), result.status());
        task.wsReasCd = result.cicsResp2();
        if (result.isFound()) {
            // :787-788 - WHEN DFHRESP(NORMAL) SET FOUND-ACCT-IN-MASTER TO TRUE.
            task.accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                    "AccountRepository reported a successful read of " + LIT_ACCTFILENAME
                            + " without a record. app/cbl/COACTVWC.cbl:780 reads INTO(ACCOUNT-RECORD), so "
                            + "a successful read always leaves 300 bytes behind."));
            task.wsAccountMasterReadFlag = FOUND_IN_MASTER;
            return;
        }
        if (result.isNotFound()) {
            // :789-807 - WHEN DFHRESP(NOTFND). The SET DID-NOT-FIND-ACCT-IN-ACCTDAT at :792 is commented
            // out, so the composed message below is the only thing that records the miss - and the guard at
            // :704 that would have stopped the sequence therefore never fires.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                // :794-795 - ERROR-RESP and ERROR-RESP2 first.
                task.errorResp = responseCodeImage(task.wsRespCd);
                task.errorResp2 = responseCodeImage(task.wsReasCd);
                // :796-806 - STRING ... INTO WS-RETURN-MSG; 81 characters into 75.
                task.wsReturnMsg = PIC_X_CODEC.movePicX(STRING_ACCOUNT_PREFIX
                        + task.wsCardRidAcctId
                        + STRING_NOT_FOUND_IN
                        + STRING_ACCT_MASTER_FILE
                        + task.errorResp
                        + STRING_REAS
                        + task.errorResp2, WS_RETURN_MSG_LENGTH);
            }
            return;
        }
        // :809-816 - WHEN OTHER.
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        recordFileError(task, LIT_ACCTFILENAME);
    }

    /**
     * {@code 9400-GETCUSTDATA-BYCUST} - {@code :825-869}.
     *
     * <p>{@code EXEC CICS READ DATASET(LIT-CUSTFILENAME) RIDFLD(WS-CARD-RID-CUST-ID-X) KEYLENGTH(LENGTH OF
     * WS-CARD-RID-CUST-ID-X)} reads the customer master by the nine-character view of the customer id the
     * cross reference supplied.
     *
     * <p>Two details separate this arm from the two above. Its {@code NOTFND} branch moves
     * {@code ERROR-RESP} and {@code ERROR-RESP2} <em>before</em> the {@code IF WS-RETURN-MSG-OFF} guard, at
     * {@code :843-844} rather than inside it, so those two fields are updated even when the message is
     * not - and the order is reproduced. And both failure arms set {@code FLG-CUSTFILTER-NOT-OK}, the
     * <em>customer</em> flag, not the account flag the other two use, so a customer failure does not colour
     * the account field red.
     *
     * @param task the conversation performing the read
     */
    void getCustDataByCust9400(Conversation task) {
        CustomerRepository.ReadResult result = customerRepository.readByKey(task.wsCardRidCustId);
        task.wsRespCd = cicsResp(result.cicsResp(), result.status());
        task.wsReasCd = result.cicsResp2();
        if (result.isFound()) {
            // :837-838 - WHEN DFHRESP(NORMAL) SET FOUND-CUST-IN-MASTER TO TRUE.
            task.customerRecord = result.customer().orElseThrow(() -> new IllegalStateException(
                    "CustomerRepository reported a successful read of " + LIT_CUSTFILENAME
                            + " without a record. app/cbl/COACTVWC.cbl:830 reads INTO(CUSTOMER-RECORD), so "
                            + "a successful read always leaves 500 bytes behind."));
            task.wsCustMasterReadFlag = FOUND_IN_MASTER;
            return;
        }
        if (result.isNotFound()) {
            // :839-857 - WHEN DFHRESP(NOTFND).
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCustFlag = FLG_FILTER_NOT_OK;
            // :843-844 - outside the guard, unlike the other two paragraphs.
            task.errorResp = responseCodeImage(task.wsRespCd);
            task.errorResp2 = responseCodeImage(task.wsReasCd);
            if (task.returnMessageOff()) {
                // :846-856 - STRING ... INTO WS-RETURN-MSG; 78 characters into 75.
                task.wsReturnMsg = PIC_X_CODEC.movePicX(STRING_CUSTID_PREFIX
                        + task.wsCardRidCustId
                        + STRING_NOT_FOUND
                        + STRING_IN_CUSTOMER_MASTER
                        + task.errorResp
                        + STRING_REAS_UPPER
                        + task.errorResp2, WS_RETURN_MSG_LENGTH);
            }
            return;
        }
        // :858-865 - WHEN OTHER.
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCustFlag = FLG_FILTER_NOT_OK;
        recordFileError(task, LIT_CUSTFILENAME);
    }

    // =================================================================================================
    // WS-FILE-ERROR-MESSAGE, composed - app/cbl/COACTVWC.cbl:86-105, filled by the three WHEN OTHER arms.
    // =================================================================================================

    /**
     * The five moves every {@code WHEN OTHER} arm makes - {@code :762-766}, {@code :812-816} and
     * {@code :861-865}.
     *
     * <p>{@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} moves 80 characters into 75, so the last five
     * are discarded. Those five are exactly {@link #FILE_ERROR_TRAILER}, the group's trailing filler, so
     * nothing but padding is lost - which is worth knowing, because it means the truncation is safe and not
     * merely tolerated.
     *
     * @param task     the conversation that failed a read
     * @param fileName the {@code PIC X(8)} CICS file name of the access path that failed
     */
    void recordFileError(Conversation task, String fileName) {
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(fileName, ERROR_FILE_LENGTH);
        task.errorResp = responseCodeImage(task.wsRespCd);
        task.errorResp2 = responseCodeImage(task.wsReasCd);
        task.wsReturnMsg = PIC_X_CODEC.movePicX(fileErrorMessage(task), WS_RETURN_MSG_LENGTH);
    }

    /**
     * Composes {@code WS-FILE-ERROR-MESSAGE} from its eight declared items.
     *
     * <p>Written out part by part, in the copybook's order, and checked against the declared 80 characters
     * on every call - not only at class initialisation - because the two variable-width parts are supplied
     * by the caller and a wrongly padded one would otherwise shift the message silently.
     *
     * @param task the conversation whose error fields are being rendered
     * @return exactly {@link #FILE_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException if the composition is not the declared width
     */
    String fileErrorMessage(Conversation task) {
        String message = FILE_ERROR_PREFIX
                + PIC_X_CODEC.movePicX(task.errorOpname, ERROR_OPNAME_LENGTH)
                + FILE_ERROR_ON
                + PIC_X_CODEC.movePicX(task.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + PIC_X_CODEC.movePicX(task.errorResp, ERROR_RESP_LENGTH)
                + FILE_ERROR_RESP2
                + PIC_X_CODEC.movePicX(task.errorResp2, ERROR_RESP_LENGTH)
                + FILE_ERROR_TRAILER;
        if (message.length() != FILE_ERROR_MESSAGE_LENGTH) {
            throw new IllegalStateException("WS-FILE-ERROR-MESSAGE must be exactly "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters, as app/cbl/COACTVWC.cbl:86-105 declares "
                    + "it, but the composition came to " + message.length());
        }
        return message;
    }

    /**
     * {@code MOVE WS-RESP-CD TO ERROR-RESP} - {@code :745}, {@code :764} and their siblings.
     *
     * <p>{@code WS-RESP-CD} is {@code PIC S9(09) COMP} and {@code ERROR-RESP} is {@code PIC X(10)}. Moving
     * a signed binary field to an alphanumeric one renders its nine digits unsigned, then the
     * {@code PIC X} rule left justifies them, so the tenth character is always a space.
     *
     * <p>A response code the backend did not report is rendered by {@link FileStatus} rather than as a
     * number, so "no code was reported" cannot be mistaken for the code zero - which is
     * {@code DFHRESP(NORMAL)} and would read as success.
     *
     * @param responseCode the CICS response or reason code
     * @return the ten-character image
     */
    static String responseCodeImage(int responseCode) {
        if (!FileStatus.respReported(responseCode)) {
            return PIC_X_CODEC.movePicX(
                    FileStatus.respNotReportedImage(RESP_CODE_DIGITS), ERROR_RESP_LENGTH);
        }
        String digits = PIC_X_CODEC.movePic9(Math.abs((long) responseCode), RESP_CODE_DIGITS);
        return PIC_X_CODEC.movePicX(digits, ERROR_RESP_LENGTH);
    }

    /**
     * Resolves the {@code RESP} a read reported, falling back to the code the batch file status stands for.
     *
     * <p>{@code EXEC CICS READ ... RESP(WS-RESP-CD)} always leaves a code behind on the mainframe. A
     * repository, reaching the dataset over JDBC, reports its outcome as a two-character {@code FILE
     * STATUS} and supplies the equivalent CICS code when it can; when it cannot, the batch status is
     * translated here. Only if neither is available is the code reported as unavailable, and
     * {@link #responseCodeImage(int)} then renders it as such rather than as a digit.
     *
     * @param reported the code the repository reported, possibly absent
     * @param status   the two-character file status it reported alongside
     * @return the CICS response code, or {@link FileStatus#RESP_NOT_REPORTED}
     */
    static int cicsResp(OptionalInt reported, String status) {
        if (reported.isPresent()) {
            return reported.getAsInt();
        }
        return FileStatus.cicsRespOfBatchStatus(status).orElse(FileStatus.RESP_NOT_REPORTED);
    }

    // =================================================================================================
    // The two plain-text exits and ABEND-ROUTINE - app/cbl/COACTVWC.cbl:877-937.
    // =================================================================================================

    /**
     * {@code SEND-PLAIN-TEXT} - {@code :877-887}: {@code EXEC CICS SEND TEXT FROM(WS-RETURN-MSG)} then
     * {@code EXEC CICS RETURN}.
     *
     * <p>The source's own comment at {@code :874-876} reads "Plain text exit - Dont use in production", and
     * the only caller is the {@code WHEN OTHER} arm. A plain {@code SEND TEXT} writes the 75 characters and
     * nothing else - no map, so no field, colour or cursor - and the {@code RETURN} carries no
     * {@code TRANSID} and no communication area, so the conversation ends rather than continuing. All three
     * of those are reproduced: the message reaches {@code ERRMSGO} because that is where a client reads it,
     * no other output field is written, and the context is published as it stands so the caller can see the
     * state the transaction ended in.
     *
     * @param task the conversation being ended
     */
    void sendPlainText(Conversation task) {
        String transmitted = PIC_X_CODEC.movePicX(task.wsReturnMsg, WS_RETURN_MSG_LENGTH);
        task.cactvwao
                .errmsg(PIC_X_CODEC.movePicX(transmitted, AccountViewResponse.ERRMSG_LENGTH))
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        if (LOG.isWarnEnabled()) {
            LOG.warn(LIT_THISPGM + " sent plain text (abend code " + task.abendData.abendCode().trim()
                    + "): " + transmitted.trim());
        }
        task.returned = true;
    }

    /**
     * {@code SEND-LONG-TEXT} - {@code :896-909}: {@code EXEC CICS SEND TEXT FROM(WS-LONG-MSG)}.
     *
     * <p>Declared and never performed. The three {@code WHEN OTHER} arms each carry a commented-out
     * {@code PERFORM SEND-LONG-TEXT} beside a commented-out move into {@code WS-LONG-MSG} - at
     * {@code :767-768}, {@code :817-818} and {@code :866-867} - so the paragraph is reachable only if that
     * debugging aid is switched back on. It is translated because deleting a paragraph is a change to the
     * program, and it is left uncalled for the same reason (practice B5).
     *
     * @param task the conversation being ended
     */
    void sendLongText(Conversation task) {
        String transmitted = PIC_X_CODEC.movePicX(task.wsLongMsg, WS_LONG_MSG_LENGTH);
        task.cactvwao
                .errmsg(PIC_X_CODEC.movePicX(transmitted, AccountViewResponse.ERRMSG_LENGTH))
                .navigationContext(task.carddemoCommarea)
                .cardScreenState(task.ccWorkArea);
        task.returned = true;
    }

    /**
     * {@code ABEND-ROUTINE} - {@code :916-937}, the label {@code EXEC CICS HANDLE ABEND} names at
     * {@code :264-266}.
     *
     * <p>Four steps, in the source's order: default the message if it was never set, name the culprit, send
     * {@code ABEND-DATA} to the terminal, and abend with {@code ABCODE('9999')}. The
     * {@code EXEC CICS HANDLE ABEND CANCEL} between the send and the abend at {@code :930-932} disarms the
     * handler so the abend cannot re-enter this routine, which is why {@link #handle} rethrows an
     * {@link AbendException} rather than wrapping it again.
     *
     * <p>The first of those steps never fires, and it is worth knowing why. {@code IF ABEND-MSG EQUAL
     * LOW-VALUES} at {@code :918} asks whether the message is binary zeros, but
     * {@code app/cpy/CSMSG02Y.cpy} declares {@code ABEND-MSG PIC X(72) VALUE SPACES} - so on a fresh task
     * the field holds spaces, the test is false, and {@link #UNEXPECTED_ABEND_OCCURRED} is not stored. The
     * comparison is reproduced exactly as written rather than relaxed to "blank", because relaxing it would
     * make the abend report a message the program does not report.
     *
     * <p>The exception carries {@link AbendException#RETURN_CODE_IO_ERROR}, the value this module uses for
     * an unrecoverable failure, and the composed {@code ABEND-DATA} image as its source diagnostic, so the
     * 134 bytes the terminal would have shown are still available to whoever handles it. The log line takes
     * one argument: handing a logger the cause would print the driver's own message, which may quote the
     * record it refused.
     *
     * @param task  the conversation that abended, possibly only partly initialised
     * @param cause the failure that reached the handler
     * @return the exception to throw; never {@code null}, and never thrown from inside this method so the
     *         caller's {@code throw} keeps the stack honest
     */
    AbendException abendRoutine(Conversation task, RuntimeException cause) {
        SystemMessages.AbendData abendData =
                task.abendData == null ? SystemMessages.AbendData.spaces() : task.abendData;
        // :918-920 - IF ABEND-MSG EQUAL LOW-VALUES MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG.
        String abendMsg =
                PIC_X_CODEC.movePicX(abendData.abendMsg(), SystemMessages.ABEND_MSG_LENGTH);
        if (CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendMsg)) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }
        // :922 - MOVE LIT-THISPGM TO ABEND-CULPRIT.
        abendData = abendData.withAbendCulprit(LIT_THISPGM);
        task.abendData = abendData;
        // :924-928 - EXEC CICS SEND FROM(ABEND-DATA) NOHANDLE.
        LOG.error(LIT_THISPGM + " abending with ABCODE " + ABEND_ROUTINE_ABCODE + ": "
                + AbendException.ABEND_DISPLAY_TEXT + " " + abendDataImage(abendData));
        if (task.cactvwao != null) {
            task.cactvwao.cardScreenState(
                    task.ccWorkArea == null ? new CardScreenState() : task.ccWorkArea);
        }
        task.returned = true;
        // :930-936 - HANDLE ABEND CANCEL, then ABEND ABCODE('9999').
        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + abendData.abendMsg().trim(), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    /**
     * Renders {@code ABEND-DATA} as the 134 characters {@code EXEC CICS SEND FROM(ABEND-DATA)} would have
     * transmitted: code, culprit, reason and message, each at its declared width and in the copybook's
     * order.
     *
     * @param abendData the abend data to render
     * @return the concatenated image
     */
    static String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }

    // =================================================================================================
    // Comparison helpers. Each one is a COBOL comparison and nothing more, so each imposes the receiver's
    // width on both sides before comparing: a PIC X field's trailing spaces are part of its value, and a
    // comparison that trimmed them would answer a different question than the source asks.
    // =================================================================================================

    /**
     * {@code IF ... EQUAL LOW-VALUES OR EQUAL SPACES} - the fallback tests at {@code :328-329} and
     * {@code :334-335}.
     *
     * @param value  the field's value
     * @param length the field's declared width
     * @return {@code true} if the field holds only binary zeros or only spaces
     */
    static boolean isLowValuesOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return CardScreenState.spaces(length).equals(image)
                || CardScreenState.lowValues(length).equals(image);
    }

    /**
     * {@code IF ACCTSIDI OF CACTVWAI = '*' OR ACCTSIDI OF CACTVWAI = SPACES} - {@code :628-629}.
     *
     * <p>The asterisk test is a comparison against a <em>figurative-length</em> literal: COBOL pads the
     * one-character literal to the field's width with spaces, so it matches an asterisk followed by ten
     * spaces and not an asterisk followed by anything else.
     *
     * @param value  the field's value
     * @param length the field's declared width
     * @return {@code true} if the field holds the blank marker or only spaces
     */
    static boolean isAsteriskOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return PIC_X_CODEC.movePicX(BLANK_FIELD_MARKER, length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    /**
     * {@code MOVE XREF-CARD-NUM TO CDEMO-CARD-NUM} - {@code :740}: {@code PIC X(16)} into {@code PIC 9(16)}.
     *
     * <p>COBOL moves the characters and lets the receiver reinterpret them; it does not validate, and it
     * does not fail. A cross-reference row whose card number is blank or otherwise not sixteen digits
     * therefore yields zero here rather than an exception, which keeps a data fault in one row from ending a
     * transaction that the source would have completed.
     *
     * @param cardNumber the {@code XREF-CARD-NUM} image
     * @return the numeric value, or zero if the image is not all digits
     */
    static long carriedCardNumber(String cardNumber) {
        String trimmed = cardNumber.trim();
        if (trimmed.isEmpty()) {
            return 0L;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (trimmed.charAt(index) < '0' || trimmed.charAt(index) > '9') {
                return 0L;
            }
        }
        return Long.parseLong(trimmed);
    }

    // =================================================================================================
    // The task's storage - app/cbl/COACTVWC.cbl:35-259.
    // =================================================================================================

    /**
     * Everything {@code COACTVWC} declares in {@code WORKING-STORAGE}, for the duration of one interaction.
     *
     * <p>A new instance per call, held only on the stack of the method that created it. That is the whole of
     * this class's answer to gate G37: COBOL {@code WORKING-STORAGE} is per-task on CICS, so translating it
     * to fields on the controller - a singleton - would let two concurrent requests overwrite each other's
     * account number. Nothing here is static and nothing here escapes the call.
     *
     * <p>Fields are package visible rather than private so that a unit test can arrange one state and assert
     * another without going through HTTP (practice B10, gate G51), which is what makes the 28 condition
     * names below drivable in both directions (gate G50).
     *
     * <p>The condition names are methods rather than stored booleans because that is what an {@code 88}
     * level is: a question asked of the field's current value, not a second field kept in step with it. Two
     * of them - {@link #didNotFindAcctInAcctdat()} and {@link #didNotFindCustInCustdat()} - are questions
     * nothing in the program ever makes true, and they are implemented as the value comparisons they are for
     * exactly that reason.
     */
    static final class Conversation {

        /** {@code EIBCALEN}, read at {@code :282} and again at {@code :462}. */
        int eibcalen;

        /** {@code EIBAID}, read by the {@code COPY 'CSSTRPFY'} paragraph at {@code :913}. */
        byte eibAid;

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code :40-41}; the CICS {@code RESP}. */
        int wsRespCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code :42-43}; the CICS {@code RESP2}. */
        int wsReasCd;

        /** {@code WS-TRANID PIC X(4) VALUE SPACES} - {@code :44-45}; set to {@code 'CAVW'} at {@code :274}. */
        String wsTranid;

        /** {@code WS-INPUT-FLAG PIC X(1)} - {@code :50}; carries {@code INPUT-OK} / {@code INPUT-ERROR}. */
        String wsInputFlag;

        /** {@code WS-PFK-FLAG PIC X(1)} - {@code :54}; carries {@code PFK-VALID} / {@code PFK-INVALID}. */
        String wsPfkFlag;

        /** {@code WS-EDIT-ACCT-FLAG PIC X(1)} - {@code :58}; the three {@code FLG-ACCTFILTER-*} names. */
        String wsEditAcctFlag;

        /** {@code WS-EDIT-CUST-FLAG PIC X(1)} - {@code :62}; the three {@code FLG-CUSTFILTER-*} names. */
        String wsEditCustFlag;

        /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :74}; declared, never used by this program (B5). */
        String wsCardRidCardnum;

        /**
         * {@code WS-CARD-RID-CUST-ID PIC 9(09)} - {@code :75}, held as the nine-character image that
         * {@code WS-CARD-RID-CUST-ID-X} redefines. Filled at {@code :708} and used as the {@code RIDFLD} at
         * {@code :828}.
         */
        String wsCardRidCustId;

        /**
         * {@code WS-CARD-RID-ACCT-ID PIC 9(11)} - {@code :78}, held as the eleven-character image that
         * {@code WS-CARD-RID-ACCT-ID-X} redefines. Filled at {@code :691} and used as the {@code RIDFLD} at
         * {@code :729} and {@code :778}.
         */
        String wsCardRidAcctId;

        /** {@code WS-ACCOUNT-MASTER-READ-FLAG PIC X(1)} - {@code :82}; set to {@code '1'} at {@code :788}. */
        String wsAccountMasterReadFlag;

        /** {@code WS-CUST-MASTER-READ-FLAG PIC X(1)} - {@code :84}; set to {@code '1'} at {@code :838}. */
        String wsCustMasterReadFlag;

        /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :89-90}. */
        String errorOpname;

        /** {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :93-94}. */
        String errorFile;

        /** {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :98-99}. */
        String errorResp;

        /** {@code ERROR-RESP2 PIC X(10) VALUE SPACES} - {@code :102-103}. */
        String errorResp2;

        /** {@code WS-LONG-MSG PIC X(500)} - {@code :109}; filled only by the commented-out debugging aid. */
        String wsLongMsg;

        /** {@code WS-INFO-MSG PIC X(40)} - {@code :110}; the source of {@code INFOMSGO}. */
        String wsInfoMsg;

        /** {@code WS-RETURN-MSG PIC X(75)} - {@code :117}; the source of {@code ERRMSGO}. */
        String wsReturnMsg;

        /** {@code CC-WORK-AREA} of {@code app/cpy/CVCRD01Y.cpy}, copied at {@code :207}. */
        CardScreenState ccWorkArea;

        /** {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, copied at {@code :211}. */
        NavigationContext carddemoCommarea;

        /** {@code WS-THIS-PROGCOMMAREA} - {@code :213-216}; twelve characters passed straight through. */
        ThisProgCommarea thisProgCommarea;

        /** {@code WS-COMMAREA PIC X(2000)} - {@code :218}; what {@code COMMON-RETURN} returns. */
        String wsCommarea;

        /**
         * {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy}, copied at {@code :244}. Always present,
         * because a {@code WORKING-STORAGE} group item always is; an allocated record holds the spaces and
         * zeros {@code COBOL} leaves in one that no read has filled.
         */
        AccountRecord accountRecord;

        /** {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy}, copied at {@code :254}. */
        CustomerRecord customerRecord;

        /**
         * {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}, copied at {@code :251}. Read only inside
         * the arm that filled it, so absence is representable here without any path observing it.
         */
        Optional<CardXrefRecord> cardXrefRecord = Optional.empty();

        /** {@code ABEND-DATA} of {@code app/cpy/CSMSG02Y.cpy}, copied at {@code :238}. */
        SystemMessages.AbendData abendData;

        /** The {@code CSDAT01Y} header {@code 1100-SCREEN-INIT} builds from {@code FUNCTION CURRENT-DATE}. */
        DateHeader dateHeader;

        /**
         * {@code CACTVWAO}, the output view of the map area declared by {@code COPY COACTVW} at {@code :229}.
         * A builder rather than a finished payload, because the map area is written field by field across
         * four paragraphs and only transmitted at the end.
         */
        AccountViewResponse.Builder cactvwao;

        /**
         * Whether the task has reached {@code EXEC CICS RETURN} - by {@code COMMON-RETURN},
         * {@code SEND-PLAIN-TEXT} or {@code XCTL}. This is what the three {@code GO TO COMMON-RETURN}s buy:
         * once a branch has returned, neither the trailing guard nor {@code COMMON-RETURN} runs again.
         */
        boolean returned;

        /**
         * {@code WS-CARD-RID-CUST-ID} read through its numeric view rather than the {@code REDEFINES}
         * character one - the second of the two typed accessors gate G34 calls for.
         *
         * @return the customer id as a number
         */
        long wsCardRidCustIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidCustId);
        }

        /**
         * {@code WS-CARD-RID-ACCT-ID} read through its numeric view.
         *
         * @return the account id as a number
         */
        long wsCardRidAcctIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidAcctId);
        }

        /** {@code 88 INPUT-OK VALUE '0'} - {@code :51}; set at {@code :624}. */
        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        /** {@code 88 INPUT-ERROR VALUE '1'} - {@code :52}; tested at {@code :364} and {@code :387}. */
        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /** {@code 88 INPUT-PENDING VALUE LOW-VALUES} on {@code WS-INPUT-FLAG} - {@code :53}; never tested. */
        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        /** {@code 88 PFK-VALID VALUE '0'} - {@code :55}; set at {@code :309}. */
        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        /** {@code 88 PFK-INVALID VALUE '1'} - {@code :56}; set at {@code :306}, tested at {@code :312}. */
        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES} on {@code WS-PFK-FLAG} - {@code :57}. The source declares
         * this condition name twice, on two different fields; the second one is named apart here because a
         * single method could not tell a caller which field it had asked about.
         *
         * @return {@code true} if the PF-key flag is still {@code LOW-VALUES}
         */
        boolean pfkeyInputPending() {
            return INPUT_PENDING.equals(wsPfkFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} - {@code :59}; tested at {@code :547}, {@code :557}. */
        boolean flgAcctfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} - {@code :60}; set at {@code :625} and {@code :679}. */
        boolean flgAcctfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditAcctFlag);
        }

        /**
         * {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - {@code :61}; tested at {@code :465}, {@code :548},
         * {@code :561} and {@code :640}. True immediately after {@code INITIALIZE}, since that leaves the
         * flag a space - which is why an unedited screen paints the account number as {@code LOW-VALUES}.
         */
        boolean flgAcctfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-CUSTFILTER-NOT-OK VALUE '0'} - {@code :63}; set at {@code :841} and {@code :860}. */
        boolean flgCustfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCustFlag);
        }

        /** {@code 88 FLG-CUSTFILTER-ISVALID VALUE '1'} - {@code :64}; declared, never set (B5). */
        boolean flgCustfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCustFlag);
        }

        /** {@code 88 FLG-CUSTFILTER-BLANK VALUE ' '} - {@code :65}; declared, never tested (B5). */
        boolean flgCustfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCustFlag);
        }

        /** {@code 88 FOUND-ACCT-IN-MASTER VALUE '1'} - {@code :83}; tested at {@code :471}. */
        boolean foundAcctInMaster() {
            return FOUND_IN_MASTER.equals(wsAccountMasterReadFlag);
        }

        /** {@code 88 FOUND-CUST-IN-MASTER VALUE '1'} - {@code :85}; tested at {@code :472} and {@code :493}. */
        boolean foundCustInMaster() {
            return FOUND_IN_MASTER.equals(wsCustMasterReadFlag);
        }

        /**
         * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code :111-112}; tested at {@code :528}
         * and {@code :567}. Two values, so two comparisons.
         */
        boolean noInfoMessage() {
            return WS_INFO_MSG_SPACES.equals(wsInfoMsg) || WS_INFO_MSG_LOW_VALUES.equals(wsInfoMsg);
        }

        /** {@code 88 WS-PROMPT-FOR-INPUT} - {@code :113-114}; set at {@code :463} and {@code :529}. */
        boolean promptForInput() {
            return WS_PROMPT_FOR_INPUT.equals(wsInfoMsg);
        }

        /** {@code 88 WS-INFORM-OUTPUT} - {@code :115-116}; declared, never set (B5). */
        boolean informOutput() {
            return WS_INFORM_OUTPUT.equals(wsInfoMsg);
        }

        /**
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - {@code :118}. The "first message wins" test, asked at
         * {@code :657}, {@code :670}, {@code :744}, {@code :793} and {@code :845}.
         */
        boolean returnMessageOff() {
            return WS_RETURN_MSG_OFF.equals(wsReturnMsg);
        }

        /** {@code 88 WS-EXIT-MESSAGE} - {@code :119-120}; declared, never set (B5). */
        boolean exitMessage() {
            return WS_EXIT_MESSAGE.equals(wsReturnMsg);
        }

        /** {@code 88 WS-PROMPT-FOR-ACCT} - {@code :121-122}; set at {@code :658}. */
        boolean promptForAcct() {
            return WS_PROMPT_FOR_ACCT.equals(wsReturnMsg);
        }

        /** {@code 88 NO-SEARCH-CRITERIA-RECEIVED} - {@code :123-124}; set at {@code :641}. */
        boolean noSearchCriteriaReceived() {
            return NO_SEARCH_CRITERIA_RECEIVED.equals(wsReturnMsg);
        }

        /**
         * {@code 88 SEARCHED-ACCT-ZEROES} - {@code :125-126}; declared, never set. The paragraph that would
         * set it moves {@link AccountViewController#ACCOUNT_FILTER_NOT_NUMERIC} instead, at {@code :671-673}.
         */
        boolean searchedAcctZeroes() {
            return SEARCHED_ACCT_ZEROES.equals(wsReturnMsg);
        }

        /**
         * {@code 88 SEARCHED-ACCT-NOT-NUMERIC} - {@code :127-128}; declared, never set, and declared over the
         * same literal as {@link #searchedAcctZeroes()}. The two therefore answer identically, which is a
         * property of the source and not of this translation.
         */
        boolean searchedAcctNotNumeric() {
            return SEARCHED_ACCT_NOT_NUMERIC.equals(wsReturnMsg);
        }

        /**
         * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF} - {@code :129-130}; declared, never set. The
         * commented-out test at {@code :696} shows where it was meant to be asked.
         */
        boolean didNotFindAcctInCardxref() {
            return DID_NOT_FIND_ACCT_IN_CARDXREF.equals(wsReturnMsg);
        }

        /**
         * {@code 88 DID-NOT-FIND-ACCT-IN-ACCTDAT} - {@code :131-132}; <strong>tested</strong> at {@code :704}
         * and never set, because the {@code SET} at {@code :792} is commented out. So this method is a real
         * comparison that is asked a real question and always answers {@code false} on any path the program
         * can take - which is exactly why a missing account does not stop the read sequence.
         */
        boolean didNotFindAcctInAcctdat() {
            return DID_NOT_FIND_ACCT_IN_ACCTDAT.equals(wsReturnMsg);
        }

        /**
         * {@code 88 DID-NOT-FIND-CUST-IN-CUSTDAT} - {@code :133-134}; tested at {@code :713}, and never set
         * because the {@code SET} at {@code :842} is commented out.
         */
        boolean didNotFindCustInCustdat() {
            return DID_NOT_FIND_CUST_IN_CUSTDAT.equals(wsReturnMsg);
        }

        /** {@code 88 XREF-READ-ERROR} - {@code :135-136}; declared, never set (B5). */
        boolean xrefReadError() {
            return XREF_READ_ERROR.equals(wsReturnMsg);
        }

        /** {@code 88 CODING-TO-BE-DONE} - {@code :137-138}; declared, never set (B5). */
        boolean codingToBeDone() {
            return CODING_TO_BE_DONE.equals(wsReturnMsg);
        }
    }

    // =================================================================================================
    // WS-THIS-PROGCOMMAREA - app/cbl/COACTVWC.cbl:213-216.
    // =================================================================================================

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA} - the twelve-character trailer this program appends to the
     * communication area: {@code CA-CALL-CONTEXT} holding {@code CA-FROM-PROGRAM PIC X(08)} and
     * {@code CA-FROM-TRANID PIC X(04)}.
     *
     * <p>Declared locally because it is local: no other program declares this layout and no payload type
     * models it. {@code COACTVWC} never reads either field - it splits the trailer out of
     * {@code DFHCOMMAREA} at {@code :290-292} and packs it back at {@code :398-400} without looking
     * inside - so modelling it as two named fields with a width-exact image is the whole of its
     * behaviour.
     *
     * @param caFromProgram {@code CA-FROM-PROGRAM PIC X(08)}
     * @param caFromTranid  {@code CA-FROM-TRANID PIC X(04)}
     */
    record ThisProgCommarea(String caFromProgram, String caFromTranid) {

        /** {@code CA-FROM-PROGRAM PIC X(08)} - {@code app/cbl/COACTVWC.cbl:215}. */
        static final int CA_FROM_PROGRAM_LENGTH = 8;

        /** {@code CA-FROM-TRANID PIC X(04)} - {@code app/cbl/COACTVWC.cbl:216}. */
        static final int CA_FROM_TRANID_LENGTH = 4;

        /**
         * Validates both widths on construction, so a trailer of the wrong length cannot exist and the
         * repack at {@code :398-400} cannot silently shift what follows it.
         *
         * @throws NullPointerException     if either component is {@code null}
         * @throws IllegalArgumentException if either component is not its declared width
         */
        ThisProgCommarea {
            Objects.requireNonNull(caFromProgram, "CA-FROM-PROGRAM is PIC X(08) and has no absent state; "
                    + "use ThisProgCommarea.initialized() for the state INITIALIZE leaves");
            Objects.requireNonNull(caFromTranid, "CA-FROM-TRANID is PIC X(04) and has no absent state; "
                    + "use ThisProgCommarea.initialized() for the state INITIALIZE leaves");
            if (caFromProgram.length() != CA_FROM_PROGRAM_LENGTH) {
                throw new IllegalArgumentException("CA-FROM-PROGRAM is PIC X(0"
                        + CA_FROM_PROGRAM_LENGTH + ") at app/cbl/COACTVWC.cbl:215, but was given "
                        + caFromProgram.length() + " characters");
            }
            if (caFromTranid.length() != CA_FROM_TRANID_LENGTH) {
                throw new IllegalArgumentException("CA-FROM-TRANID is PIC X(0"
                        + CA_FROM_TRANID_LENGTH + ") at app/cbl/COACTVWC.cbl:216, but was given "
                        + caFromTranid.length() + " characters");
            }
        }

        /**
         * The state {@code INITIALIZE WS-THIS-PROGCOMMAREA} at {@code :285-286} leaves: both alphanumeric
         * fields blank.
         *
         * @return a blank trailer
         */
        static ThisProgCommarea initialized() {
            return new ThisProgCommarea(CardScreenState.spaces(CA_FROM_PROGRAM_LENGTH),
                    CardScreenState.spaces(CA_FROM_TRANID_LENGTH));
        }

        /**
         * Reads a trailer from its twelve-character image, as {@code :290-292} does out of
         * {@code DFHCOMMAREA}.
         *
         * @param image the twelve-character image
         * @return the trailer
         * @throws NullPointerException     if {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not twelve characters
         */
        static ThisProgCommarea fromImage(String image) {
            Objects.requireNonNull(image, "A twelve-character image is required to read "
                    + "WS-THIS-PROGCOMMAREA");
            int declared = CA_FROM_PROGRAM_LENGTH + CA_FROM_TRANID_LENGTH;
            if (image.length() != declared) {
                throw new IllegalArgumentException("WS-THIS-PROGCOMMAREA is " + declared
                        + " characters at app/cbl/COACTVWC.cbl:213-216, but the image is "
                        + image.length());
            }
            return new ThisProgCommarea(image.substring(0, CA_FROM_PROGRAM_LENGTH),
                    image.substring(CA_FROM_PROGRAM_LENGTH, declared));
        }

        /**
         * Renders the trailer as the twelve characters {@code :398-400} packs into
         * {@code WS-COMMAREA(161:12)}.
         *
         * @return the twelve-character image
         */
        String toImage() {
            return caFromProgram + caFromTranid;
        }
    }
}
