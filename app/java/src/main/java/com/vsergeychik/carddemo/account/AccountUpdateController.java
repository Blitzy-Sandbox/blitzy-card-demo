package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest;
import com.vsergeychik.carddemo.account.dto.AccountUpdateResponse;
import com.vsergeychik.carddemo.account.model.AccountRecord;
import com.vsergeychik.carddemo.card.CardXrefRepository;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.model.CardXrefRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FileStatus;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import com.vsergeychik.carddemo.common.ScreenInputRejectedException;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.customer.CustomerRepository;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code COACTUPC} - "Accept and process ACCOUNT UPDATE" - projected onto
 * {@code PUT /api/accounts/{acctId}}.
 *
 * <p>The source is {@code app/cbl/COACTUPC.cbl}, 4,236 lines and the largest program in the migration.
 * It is CICS transaction {@code CAUP}: {@code DEFINE PROGRAM(COACTUPC)
 * DESCRIPTION(CREDIT CARD DEMO ACCOUNT UPDATE)} at {@code app/csd/CARDDEMO.CSD:173-174} and
 * {@code DEFINE TRANSACTION(CAUP) ... PROGRAM(COACTUPC)} at {@code :306-308}. Its screen is mapset
 * {@code COACTUP}, map {@code CACTUPA}, whose 54 named {@code DFHMDF} fields
 * ({@code app/bms/COACTUP.bms}) and their symbolic-map {@code PICTURE} clauses
 * ({@code app/cpy-bms/COACTUP.CPY}) are the presentation contract this controller must reproduce
 * field for field.
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 *
 * <p>Everything from {@code 0000-MAIN} at {@code :859} to {@code ABEND-ROUTINE} at {@code :4203} except
 * the write path. {@code 9600-WRITE-PROCESSING} ({@code :3889-4106}) and
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code :4109-4193}) belong to {@link AccountUpdateService}, and so
 * do the program's five and only {@code COMPUTE} statements at {@code :1079}, {@code :1093},
 * {@code :1107}, {@code :1121} and {@code :1135} - reachable here as
 * {@link AccountUpdateService#computeCreditLimit}, {@link AccountUpdateService#computeCashCreditLimit},
 * {@link AccountUpdateService#computeCurrBal}, {@link AccountUpdateService#computeCurrCycCredit} and
 * {@link AccountUpdateService#computeCurrCycDebit}. This class calls them; it re-implements none of
 * them. Keeping the arithmetic and the optimistic-concurrency check in the service is what lets a plain
 * JUnit test drive them with no HTTP layer in the path.
 *
 * <p>A naming trap worth stating once. The Agent Action Plan calls the concurrency paragraph
 * {@code 9300-CHECK-CHANGE-IN-REC}; in <em>this</em> program that label is
 * {@code 9700-CHECK-CHANGE-IN-REC} at {@code :4109}, while {@code 9300-GETACCTDATA-BYACCT} at
 * {@code :3701} is an unrelated read paragraph reproduced by {@link #getAcctDataByAcct9300}.
 * {@code 9300-CHECK-CHANGE-IN-REC} is {@code COCRDUPC}'s label, not this program's.
 *
 * <h2>Statelessness</h2>
 *
 * <p>{@code CAUP} is pseudo-conversational: {@code COMMON-RETURN} at {@code :1007} issues
 * {@code EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)} and the task ends. The whole
 * of that 2,000-byte area travels in the payload - {@code CARDDEMO-COMMAREA} as
 * {@link NavigationContext}, {@code WS-THIS-PROGCOMMAREA} as
 * {@link AccountUpdateRequest.CommArea}, and the {@code CVCRD01Y} work area as
 * {@link CardScreenState}. There is no {@code HttpSession}, no {@code @SessionAttributes} and no
 * server-side cache anywhere in this class, and every field of every intermediate is per request: the
 * whole of {@code COACTUPC}'s enormous {@code WORKING-STORAGE} lives on one {@link Conversation}
 * instance created inside the request method. The only {@code static} members are immutable constants.
 *
 * <h2>Control flow</h2>
 *
 * <p>{@code COACTUPC} contains 51 {@code GO TO}s, more than any other program in the migration, and
 * every one of them is benign. They fall into three shapes: a jump to the paragraph's own
 * {@code -EXIT} label, which is a Java {@code return}; a jump to the shared {@code COMMON-RETURN}
 * terminal, which is a {@code return} after the response has been assembled; and a forward jump to the
 * next stage of the telephone edit - {@code EDIT-US-PHONE-PREFIX} four times and
 * {@code EDIT-US-PHONE-LINENUM} three times - which becomes sequential guarded blocks. None is
 * backward and none forms a loop, so no restructuring changes an iteration count. The two
 * {@code PERFORM ... THRU} sites at {@code :969} and {@code :985} each collapse to one call of
 * {@link #sendMap3000}.
 *
 * <p>Every {@code EVALUATE} preserves its source {@code WHEN} order, because {@code EVALUATE} is
 * first-match-wins and a reordering silently changes behaviour. The order-sensitive ones are the
 * four-arm dispatch in {@code 0000-MAIN}, the eight-arm state machine in {@code 2000-DECIDE-ACTION}
 * and its nested four-arm write-outcome test, the four-arm paint selection in
 * {@code 3200-SETUP-SCREEN-VARS}, the nine-arm information-message selection in
 * {@code 3250-SETUP-INFOMSG}, and the two in {@code 3300-SETUP-SCREEN-ATTRS} - a four-arm protection
 * test and a 44-arm cursor test.
 *
 * <h2>Legacy behaviour preserved rather than repaired</h2>
 *
 * <ul>
 *   <li><strong>{@code 1260-EDIT-US-PHONE-NUM}'s all-blank guard is wrong in the source.</strong>
 *       {@code app/cbl/COACTUPC.cbl:2216-2218} reads
 *       {@code AND (WS-EDIT-US-PHONE-NUMA EQUAL SPACES OR WS-EDIT-US-PHONE-NUMC EQUAL LOW-VALUES)} -
 *       the third clause plainly means to test {@code NUMC} against {@code SPACES} and tests
 *       {@code NUMA} instead. Reproduced verbatim in {@link #editUsPhoneNum1260}; see the comment
 *       there.</li>
 *   <li><strong>The cursor {@code EVALUATE} gives {@code MIDDLE-NAME} no {@code BLANK} arm.</strong>
 *       All 43 other fields get a {@code NOT-OK} arm and a {@code BLANK} arm; {@code :3110-3111} gives
 *       middle name only the {@code NOT-OK} one. Reproduced in {@link #positionCursor3300}.</li>
 *   <li><strong>{@code 9400}'s {@code NOTFND} arm fills {@code ERROR-RESP} and {@code ERROR-RESP2}
 *       outside the {@code WS-RETURN-MSG-OFF} guard</strong> ({@code :3768-3769}), where {@code 9200}
 *       and {@code 9300} fill them inside it. Reproduced in {@link #getCustDataByCust9400}.</li>
 *   <li><strong>{@code 9000-READ-ACCT}'s second guard cannot fire.</strong> {@code :3626} tests
 *       {@code DID-NOT-FIND-ACCT-IN-ACCTDAT}, which is an {@code 88}-level on
 *       {@code WS-RETURN-MSG}; the {@code SET} that would raise it is commented out at
 *       {@code :3719}. The guard is written anyway, because the message it tests for can in principle
 *       be present.</li>
 *   <li><strong>{@code 1200-EDIT-MAP-INPUTS} never edits address line 2.</strong> The
 *       {@code MOVE 'Address Line 2'} at {@code :1611} is commented out, so the field is received,
 *       compared and painted but never validated - and it still gets a {@code CSSETATY} site, whose
 *       flag can therefore never be anything but valid.</li>
 *   <li><strong>{@code LIT-CARDFILENAME} and {@code LIT-CARDFILENAME-ACCT-PATH} are declared and never
 *       used.</strong> {@code COACTUPC} names {@code CARDDAT} and its {@code CARDAIX} path at
 *       {@code :577-580} but no statement reads either - the only three
 *       {@code EXEC CICS READ DATASET} operands are {@code CXACAIX}, {@code ACCTDAT} and
 *       {@code CUSTDAT}, at {@code :3655}, {@code :3704} and {@code :3754}. The constants are
 *       transcribed because the declaration is part of the source; no card repository is injected,
 *       because injecting one would misstate this program's dependencies.</li>
 * </ul>
 *
 * <h2>Numeric handling</h2>
 *
 * <p>Every monetary value is a {@link BigDecimal} at the scale its {@code PICTURE} declares. No
 * {@code double} and no {@code float} appears in this class, and no rounding mode other than the
 * {@code RoundingMode.DOWN} that {@code CobolDecimal} applies on the caller's behalf: the keyword
 * {@code ROUNDED} occurs zero times in all 28 programs, so COBOL truncates and so must this.
 * {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} at {@code :62} and
 * {@code WS-DIV-BY}/{@code WS-DIVIDEND}/{@code WS-REMAINDER} at {@code :152}, {@code :154} and
 * {@code :157} are packed decimal in {@code WORKING-STORAGE} only - no persisted record in this system
 * uses {@code COMP-3} - so they are plain {@code int}s here and no nibble unpacking is involved.
 *
 * <h2>The two attribute destinations</h2>
 *
 * <p>{@code CACTUPAO REDEFINES CACTUPAI}, so the symbolic map has one set of bytes and two views.
 * {@code 3310-PROTECT-ALL-ATTRS} and {@code 3320-UNPROTECT-FEW-ATTRS} write the field attribute byte
 * into {@code xxxA OF CACTUPAI} and the cursor {@code EVALUATE} writes {@code -1} into
 * {@code xxxL OF CACTUPAI}; the colour goes into {@code xxxC OF CACTUPAO} and {@code CSSETATY}'s
 * {@code '*'} into {@code xxxO OF CACTUPAO}. The first pair therefore lands on
 * {@link AccountUpdateRequest.FieldMetadata} and the second on
 * {@link AccountUpdateResponse.FieldAttributes}, and both are projected onto
 * {@link ScreenMetadata} rather than into the JSON body, because neither traces to a {@code DFHMDF}
 * data item.
 *
 * @see AccountUpdateService for {@code 9600-WRITE-PROCESSING}, {@code 9700-CHECK-CHANGE-IN-REC} and
 *      the five {@code COMPUTE}s
 * @see AccountDateValidator for {@code CSUTLDPY} and {@code CSUTLDWY}, copied at {@code :166}
 * @see AreaCodeLookup for {@code CSLKPCDY}, copied at {@code :602}
 */
@RestController
public class AccountUpdateController {

    /** Where {@code ABEND-ROUTINE}'s {@code EXEC CICS SEND FROM(ABEND-DATA)} goes when no terminal exists. */
    private static final Log LOG = LogFactory.getLog(AccountUpdateController.class);

    // =================================================================================================
    // WS-LITERALS - app/cbl/COACTUPC.cbl:531-580. Every one is transcribed at its declared width,
    // including the trailing space that makes the eight-character file and mapset names eight
    // characters, because a comparison against one of them is a fixed-width comparison.
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COACTUPC'} ({@code :532-533}). */
    static final String LIT_THISPGM = "COACTUPC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CAUP'} ({@code :534-535}) - CSD transaction {@code CAUP}. */
    static final String LIT_THISTRANID = "CAUP";

    /** {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '} ({@code :536-537}) - note the trailing space. */
    static final String LIT_THISMAPSET = "COACTUP ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CACTUPA'} ({@code :538-539}). */
    static final String LIT_THISMAP = "CACTUPA";

    /** {@code LIT-CARDUPDATE-PGM PIC X(8) VALUE 'COCRDUPC'} ({@code :541-542}) - declared, never read. */
    static final String LIT_CARDUPDATE_PGM = "COCRDUPC";

    /** {@code LIT-CARDUPDATE-TRANID PIC X(4) VALUE 'CCUP'} ({@code :543-544}) - declared, never read. */
    static final String LIT_CARDUPDATE_TRANID = "CCUP";

    /** {@code LIT-CARDUPDATE-MAPSET PIC X(8) VALUE 'COCRDUP '} ({@code :545-546}) - declared, never read. */
    static final String LIT_CARDUPDATE_MAPSET = "COCRDUP ";

    /** {@code LIT-CARDUPDATE-MAP PIC X(7) VALUE 'CCRDUPA'} ({@code :547-548}) - declared, never read. */
    static final String LIT_CARDUPDATE_MAP = "CCRDUPA";

    /** {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} ({@code :549-550}) - declared, never read. */
    static final String LIT_CCLISTPGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID PIC X(4) VALUE 'CCLI'} ({@code :551-552}) - declared, never read. */
    static final String LIT_CCLISTTRANID = "CCLI";

    /**
     * {@code LIT-CCLISTMAPSET PIC X(7) VALUE 'COCRDLI'} ({@code :553-554}).
     *
     * <p>Seven characters, not eight - unlike {@code LIT-THISMAPSET} - which matters because
     * {@code :3171} compares it against {@code CDEMO-LAST-MAPSET PIC X(7)}. That comparison is the
     * one place a literal from another screen is read.
     */
    static final String LIT_CCLISTMAPSET = "COCRDLI";

    /** {@code LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} ({@code :555-556}) - declared, never read. */
    static final String LIT_CCLISTMAP = "CCRDSLA";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} ({@code :557-558}) - read at {@code :881} and {@code :966}. */
    static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} ({@code :559-560}) - read at {@code :932}. */
    static final String LIT_MENUTRANID = "CM00";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} ({@code :561-562}) - declared, never read. */
    static final String LIT_MENUMAPSET = "COMEN01";

    /** {@code LIT-MENUMAP PIC X(7) VALUE 'COMEN1A'} ({@code :563-564}) - declared, never read. */
    static final String LIT_MENUMAP = "COMEN1A";

    /** {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} ({@code :565-566}) - declared, never read. */
    static final String LIT_CARDDTLPGM = "COCRDSLC";

    /** {@code LIT-CARDDTLTRANID PIC X(4) VALUE 'CCDL'} ({@code :567-568}) - declared, never read. */
    static final String LIT_CARDDTLTRANID = "CCDL";

    /** {@code LIT-CARDDTLMAPSET PIC X(7) VALUE 'COCRDSL'} ({@code :569-570}) - declared, never read. */
    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    /** {@code LIT-CARDDTLMAP PIC X(7) VALUE 'CCRDSLA'} ({@code :571-572}) - declared, never read. */
    static final String LIT_CARDDTLMAP = "CCRDSLA";

    /**
     * {@code LIT-ACCTFILENAME PIC X(8) VALUE 'ACCTDAT '} ({@code :573-574}).
     *
     * <p>A CICS <em>file name</em>, which is not a dataset name: the {@code DSNAME} behind it lives in
     * {@code application.yml} under {@code carddemo.datasets}, and {@link AccountRepository} is the
     * only thing that resolves it. No {@code AWS.M2.CARDDEMO.*} literal appears in this class.
     */
    static final String LIT_ACCTFILENAME = "ACCTDAT ";

    /** {@code LIT-CUSTFILENAME PIC X(8) VALUE 'CUSTDAT '} ({@code :575-576}) - a CICS file name. */
    static final String LIT_CUSTFILENAME = "CUSTDAT ";

    /**
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} ({@code :577-578}).
     *
     * <p><strong>Declared and never read.</strong> No statement in {@code COACTUPC} names it; see the
     * class documentation.
     */
    static final String LIT_CARDFILENAME = "CARDDAT ";

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} ({@code :579-580}).
     *
     * <p><strong>Declared and never read</strong>, exactly like {@link #LIT_CARDFILENAME}.
     */
    static final String LIT_CARDFILENAME_ACCT_PATH = "CARDAIX ";

    /**
     * {@code LIT-CARDXREFNAME-ACCT-PATH PIC X(8) VALUE 'CXACAIX '} ({@code :581-582}).
     *
     * <p>The {@code CXACAIX} alternate-index <em>path</em> over the {@code CCXREF} base cluster, which
     * is how {@code 9200-GETCARDXREF-BYACCT} reads the cross reference by account identifier rather
     * than by card number. It is an additional access path on one repository, never a second table:
     * {@link CardXrefRepository#readByAccountIdViaAltIndex(String)}.
     */
    static final String LIT_CARDXREFNAME_ACCT_PATH = "CXACAIX ";

    /** {@code LIT-UPPER PIC X(26)} ({@code :587-588}) - the {@code INSPECT CONVERTING} source half. */
    static final String LIT_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** {@code LIT-LOWER PIC X(26)} ({@code :589-590}) - the other half of {@code LIT-ALL-ALPHA-FROM-X}. */
    static final String LIT_LOWER = "abcdefghijklmnopqrstuvwxyz";

    /** {@code LIT-NUMBERS PIC X(10)} ({@code :591-592}), which extends the alphabet to alphanumeric. */
    static final String LIT_NUMBERS = "0123456789";

    /** {@code LIT-ALL-ALPHA-FROM-X} ({@code :586}) - the 52 characters {@code 1225}/{@code 1235} accept. */
    static final String LIT_ALL_ALPHA_FROM_X = LIT_UPPER + LIT_LOWER;

    /** {@code LIT-ALL-ALPHANUM-FROM-X} ({@code :585}) - the 62 characters {@code 1230}/{@code 1240} accept. */
    static final String LIT_ALL_ALPHANUM_FROM_X = LIT_ALL_ALPHA_FROM_X + LIT_NUMBERS;

    // =================================================================================================
    // Declared widths. Each names the PICTURE it comes from, so a reviewer can check a pad or a
    // truncate against the source without leaving this file.
    // =================================================================================================

    /** {@code WS-TRANID PIC X(4)} ({@code :44-45}). */
    static final int WS_TRANID_LENGTH = 4;

    /** {@code WS-EDIT-VARIABLE-NAME PIC X(25)} ({@code :53}) - what {@code FUNCTION TRIM} renders. */
    static final int WS_EDIT_VARIABLE_NAME_LENGTH = 25;

    /** {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} ({@code :55}). */
    static final int WS_EDIT_SIGNED_NUMBER_LENGTH = 15;

    /** {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} ({@code :61}). */
    static final int WS_EDIT_ALPHANUM_ONLY_LENGTH = 256;

    /** {@code WS-EDIT-US-PHONE-NUM PIC X(15)} ({@code :82}) - the {@code (999)999-9999} staging item. */
    static final int WS_EDIT_US_PHONE_NUM_LENGTH = 15;

    /** {@code WS-CURR-DATE PIC X(21)} ({@code :159-160}) - {@code FUNCTION CURRENT-DATE}'s width. */
    static final int WS_CURR_DATE_LENGTH = 21;

    /** {@code WS-EDIT-CURRENCY-9-2 PIC X(15)} ({@code :372}) and the mask that fills it. */
    static final int WS_EDIT_CURRENCY_LENGTH = 15;

    // -------------------------------------------------------------------------------------------------
    // CICS-OUTPUT-EDIT-VARS' three REDEFINES overlays - app/cbl/COACTUPC.cbl:357-369.
    //
    // Preserved, not implemented. CUST-ACCT-ID-X, CUST-ACCT-ID-N and every field of the WS-EDIT-DATE-X
    // group appear exactly once in COACTUPC beyond their own declarations: nothing sets them and nothing
    // reads them. Their two live siblings in the same group, WS-EDIT-CURRENCY-9-2 and its edited form,
    // are used twenty-two and twenty-one times and are modelled above.
    //
    // The overlays are modelled as widths and offsets rather than as fields because a REDEFINES is a view
    // of storage, and the thing gate G34 asks about a view is that both accessors address the same bytes.
    // AccountUpdateRedefinesCensusTest round-trips them at those offsets in both directions. Declaring
    // mutable staging fields for them instead would be inventing state the program does not keep.
    // -------------------------------------------------------------------------------------------------

    /** {@code CUST-ACCT-ID-X PIC X(11)} ({@code :358}) and {@code CUST-ACCT-ID-N PIC 9(11)} over it. */
    static final int CUST_ACCT_ID_LENGTH = 11;

    /**
     * {@code WS-EDIT-DATE-X PIC X(10)} ({@code :361}), overlaid twice.
     *
     * <p>Once by {@code FILLER REDEFINES WS-EDIT-DATE-X} ({@code :362-367}), which splits the ten bytes
     * into {@code X(4)} year, a one-byte separator, {@code X(2)} month, another separator and
     * {@code X(2)} day - the {@code YYYY-MM-DD} shape. And once by
     * {@code WS-EDIT-DATE-X REDEFINES WS-EDIT-DATE-X PIC 9(10)} ({@code :368-369}), which redefines the
     * item <em>by its own name</em>. That is not a transcription slip: the source says exactly that, and
     * it is why the ten bytes have three names and one of them is used for two of the views.
     */
    static final int WS_EDIT_DATE_X_LENGTH = 10;

    /** {@code WS-EDIT-DATE-X-YEAR PIC X(4)} ({@code :363}) - offset 0 of the ten. */
    static final int WS_EDIT_DATE_YEAR_OFFSET = 0;

    /** {@code WS-EDIT-DATE-X-YEAR PIC X(4)} ({@code :363}). */
    static final int WS_EDIT_DATE_YEAR_LENGTH = 4;

    /** {@code WS-EDIT-DATE-MONTH PIC X(2)} ({@code :365}) - offset 5, after the first separator. */
    static final int WS_EDIT_DATE_MONTH_OFFSET = 5;

    /** {@code WS-EDIT-DATE-MONTH PIC X(2)} ({@code :365}). */
    static final int WS_EDIT_DATE_MONTH_LENGTH = 2;

    /** {@code WS-EDIT-DATE-DAY PIC X(2)} ({@code :367}) - offset 8, after the second separator. */
    static final int WS_EDIT_DATE_DAY_OFFSET = 8;

    /** {@code WS-EDIT-DATE-DAY PIC X(2)} ({@code :367}). */
    static final int WS_EDIT_DATE_DAY_LENGTH = 2;

    /** {@code WS-LONG-MSG PIC X(500)} ({@code :465}) - declared for {@code SEND-LONG-TEXT}, never sent. */
    static final int WS_LONG_MSG_LENGTH = 500;

    /** {@code WS-INFO-MSG PIC X(40)} ({@code :466}). Note the map's {@code INFOMSGO} is {@code X(45)}. */
    static final int WS_INFO_MSG_LENGTH = 40;

    /** {@code WS-RETURN-MSG PIC X(75)} ({@code :479}), which is also {@code CCARD-ERROR-MSG}'s width. */
    static final int WS_RETURN_MSG_LENGTH = AccountUpdateService.RETURN_MESSAGE_LENGTH;

    /** {@code WS-CARD-RID-CARDNUM PIC X(16)} ({@code :379}). Declared; this program never keys on it. */
    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    /** {@code WS-CARD-RID-CUST-ID PIC 9(09)} / {@code -X PIC X(09)} ({@code :380-382}). */
    static final int WS_CARD_RID_CUST_ID_LENGTH = CardScreenState.CC_CUST_ID_LENGTH;

    /** {@code WS-CARD-RID-ACCT-ID PIC 9(11)} / {@code -X PIC X(11)} ({@code :383-385}). */
    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    /** {@code ERROR-OPNAME PIC X(8)} ({@code :390-391}). */
    static final int ERROR_OPNAME_LENGTH = 8;

    /** {@code ERROR-FILE PIC X(9)} ({@code :394-395}) - one wider than the eight-character file names. */
    static final int ERROR_FILE_LENGTH = 9;

    /** {@code ERROR-RESP} and {@code ERROR-RESP2}, both {@code PIC X(10)} ({@code :399-400}, {@code :404-405}). */
    static final int ERROR_RESP_LENGTH = 10;

    /** How many digits a response code is rendered with before it is moved into its {@code X(10)} field. */
    static final int RESP_CODE_DIGITS = 9;

    /** {@code WS-COMMAREA PIC X(2000)} ({@code :851}), the area {@code COMMON-RETURN} returns. */
    static final int WS_COMMAREA_LENGTH = AccountUpdateRequest.COMMAREA_CAPACITY;

    /** {@code LENGTH OF WS-THIS-PROGCOMMAREA} - {@code ACUP-CHANGE-ACTION} plus both detail groups. */
    static final int THIS_PROGCOMMAREA_LENGTH = AccountUpdateRequest.CommArea.RECORD_LENGTH;

    /**
     * {@code EIBCALEN} when the caller passed both areas.
     *
     * <p>{@code :888-892} moves {@code DFHCOMMAREA(1:LENGTH OF CARDDEMO-COMMAREA)} and then
     * {@code DFHCOMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)}, so the area
     * this program is entered with is the sum of the two.
     */
    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    /** {@code EIBCALEN} on a cold start - the {@code IF EIBCALEN IS EQUAL TO 0} arm of {@code :880}. */
    static final int NO_COMMAREA_LENGTH = 0;

    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    static final int AID_MAX = 255;

    // =================================================================================================
    // Flag values. COBOL condition names are values of a PIC X(1) item, so each is transcribed as the
    // single character the 88-level declares. The three-way ISVALID / NOT-OK / BLANK shape recurs
    // throughout WS-NON-KEY-FLAGS and is declared once here.
    // =================================================================================================

    /** {@code INPUT-OK VALUE '0'} ({@code :172}). */
    static final String INPUT_OK = "0";

    /** {@code INPUT-ERROR VALUE '1'} ({@code :173}). */
    static final String INPUT_ERROR = "1";

    /** {@code INPUT-PENDING VALUE LOW-VALUES} ({@code :174}) - the state {@code INITIALIZE} leaves. */
    static final String INPUT_PENDING = "\u0000";

    /** {@code PFK-VALID VALUE '0'} ({@code :179}). */
    static final String PFK_VALID = "0";

    /** {@code PFK-INVALID VALUE '1'} ({@code :180}). */
    static final String PFK_INVALID = "1";

    /** {@code NO-CHANGES-FOUND VALUE '0'} ({@code :169}) - {@code WS-DATACHANGED-FLAG}. */
    static final String NO_CHANGES_FOUND = "0";

    /** {@code CHANGE-HAS-OCCURRED VALUE '1'} ({@code :170}). */
    static final String CHANGE_HAS_OCCURRED = "1";

    /** {@code FLG-ACCTFILTER-ISVALID} and {@code FLG-CUSTFILTER-ISVALID}, both {@code VALUE '1'}. */
    static final String FLG_FILTER_ISVALID = "1";

    /** {@code FLG-ACCTFILTER-NOT-OK} and {@code FLG-CUSTFILTER-NOT-OK}, both {@code VALUE '0'}. */
    static final String FLG_FILTER_NOT_OK = "0";

    /**
     * {@code FLG-ACCTFILTER-BLANK} and {@code FLG-CUSTFILTER-BLANK}, both {@code VALUE ' '}.
     *
     * <p>A space, not {@code LOW-VALUES} - which is why these two flags differ from every flag in
     * {@code WS-NON-KEY-FLAGS}, whose blank state is {@code 'B'} and whose valid state is
     * {@code LOW-VALUES}. {@code INITIALIZE WS-MISC-STORAGE} at {@code :866} sets both filter flags to
     * a space, so a freshly initialised filter flag reads as {@code BLANK} rather than as unset.
     */
    static final String FLG_FILTER_BLANK = " ";

    /** The {@code ISVALID} state of every {@code WS-NON-KEY-FLAGS} item: {@code VALUE LOW-VALUES}. */
    static final String FLG_ISVALID = "\u0000";

    /** The {@code NOT-OK} state of every {@code WS-NON-KEY-FLAGS} item: {@code VALUE '0'}. */
    static final String FLG_NOT_OK = "0";

    /** The {@code BLANK} state of every {@code WS-NON-KEY-FLAGS} item: {@code VALUE 'B'}. */
    static final String FLG_BLANK = "B";

    /** {@code FLG-YES-NO-ISVALID VALUES 'Y', 'N'} ({@code :78}) - the affirmative half. */
    static final String YES = "Y";

    /** {@code FLG-YES-NO-ISVALID VALUES 'Y', 'N'} ({@code :78}) - the negative half. */
    static final String NO = "N";

    /** {@code FOUND-ACCT-IN-MASTER VALUE '1'} ({@code :387}) and {@code FOUND-CUST-IN-MASTER} ({@code :389}). */
    static final String FOUND_IN_MASTER = "1";

    /** What {@code INITIALIZE} leaves in a {@code PIC X(1)} flag that has no {@code LOW-VALUES} condition. */
    static final String INITIALIZED_FLAG = " ";

    // =================================================================================================
    // WS-INFO-MSG's 88-level literals - app/cbl/COACTUPC.cbl:466-477. Byte exact, including the full
    // stops that some of them have and others do not; INFOMSGO is what the operator reads.
    // =================================================================================================

    /** {@code FOUND-ACCOUNT-DATA} ({@code :468-469}). */
    static final String INFO_FOUND_ACCOUNT_DATA = "Details of selected account shown above";

    /** {@code PROMPT-FOR-SEARCH-KEYS} ({@code :470-471}). */
    static final String INFO_PROMPT_FOR_SEARCH_KEYS = "Enter or update id of account to update";

    /** {@code PROMPT-FOR-CHANGES} ({@code :470-471}) - the only one of the seven ending in a full stop. */
    static final String INFO_PROMPT_FOR_CHANGES = "Update account details presented above.";

    /** {@code PROMPT-FOR-CONFIRMATION} ({@code :472-473}) - no space after the full stop, as declared. */
    static final String INFO_PROMPT_FOR_CONFIRMATION = "Changes validated.Press F5 to save";

    /** {@code CONFIRM-UPDATE-SUCCESS} ({@code :474-475}). */
    static final String INFO_CONFIRM_UPDATE_SUCCESS = "Changes committed to database";

    /** {@code INFORM-FAILURE} ({@code :476-477}) - both failure outcomes share this one text. */
    static final String INFO_INFORM_FAILURE = "Changes unsuccessful. Please try again";

    // =================================================================================================
    // WS-RETURN-MSG's 88-level literals and the STRING compositions - :479-529 and the edit paragraphs.
    // Every one of these reaches ERRMSGO, so every one is transcribed character for character.
    // =================================================================================================

    /** {@code WS-RETURN-MSG-OFF VALUE SPACES} ({@code :480}) at its declared width. */
    static final String WS_RETURN_MSG_OFF = AccountUpdateService.RETURN_MESSAGE_OFF;

    /** {@code WS-PROMPT-FOR-ACCT} ({@code :483-484}), set by {@code 1210-EDIT-ACCOUNT}'s blank arm. */
    static final String MSG_PROMPT_FOR_ACCT = "Account number not provided";

    /** {@code NO-SEARCH-CRITERIA-RECEIVED} ({@code :489-490}), set by {@code 1200} at {@code :1442}. */
    static final String MSG_NO_SEARCH_CRITERIA_RECEIVED = "No input received";

    /**
     * {@code NO-CHANGES-DETECTED} ({@code :491-492}).
     *
     * <p>Double duty, and that is not an accident: {@code 1205-COMPARE-OLD-NEW} <em>sets</em> it as the
     * message at {@code :1770} and {@code 2000-DECIDE-ACTION} <em>tests</em> it at {@code :2583} to
     * decide whether the confirmation prompt may be offered. The message is the state.
     */
    static final String MSG_NO_CHANGES_DETECTED =
            "No change detected with respect to values fetched.";

    /**
     * {@code WS-PROMPT-FOR-LASTNAME} ({@code :485-486}) - <strong>declared and never referenced</strong>.
     *
     * <p>One of four {@code WS-RETURN-MSG} condition names {@code COACTUPC} declares without ever setting
     * or testing: this one, {@link #MSG_ACCT_STATUS_MUST_BE_YES_NO},
     * {@link #MSG_THIS_MONTH_NOT_VALID} and {@link #MSG_THIS_YEAR_NOT_VALID}. Each appears exactly once in
     * the file, at its own declaration. They are transcribed rather than dropped because the copybook and
     * the program are the parity contract and a declared literal is part of it - dead code is preserved,
     * not tidied away. Their text is asserted by {@code AccountUpdateConditionCensusTest} so a transcription
     * error cannot hide behind their disuse.
     *
     * <p>The last two are not dead everywhere: {@code COCRDUPC} declares the same two card-expiry texts
     * and does reach them, which is why {@code CardUpdateController} carries live copies.
     */
    static final String MSG_PROMPT_FOR_LASTNAME = "Last name not provided";

    /** {@code ACCT-STATUS-MUST-BE-YES-NO} ({@code :503-504}) - declared, never referenced. */
    static final String MSG_ACCT_STATUS_MUST_BE_YES_NO = "Account Active Status must be Y or N";

    /** {@code THIS-MONTH-NOT-VALID} ({@code :509-510}) - declared, never referenced. */
    static final String MSG_THIS_MONTH_NOT_VALID = "Card expiry month must be between 1 and 12";

    /** {@code THIS-YEAR-NOT-VALID} ({@code :511-512}) - declared, never referenced. */
    static final String MSG_THIS_YEAR_NOT_VALID = "Invalid card expiry year";

    /** {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} ({@code :499-500}) - tested at {@code :3626}, never set. */
    static final String MSG_DID_NOT_FIND_ACCT_IN_ACCTDAT =
            "Did not find this account in account master file";

    /** {@code DID-NOT-FIND-CUST-IN-CUSTDAT} ({@code :501-502}) - tested at {@code :3634}, never set. */
    static final String MSG_DID_NOT_FIND_CUST_IN_CUSTDAT =
            "Did not find associated customer in master file";

    /** The first half of {@code 1210-EDIT-ACCOUNT}'s {@code STRING} at {@code :1809-1810}. */
    static final String MSG_ACCT_NUMBER_11_DIGIT_A =
            "Account Number if supplied must be a 11 digit";

    /** The second half of that {@code STRING}, at {@code :1811}. Concatenated {@code DELIMITED BY SIZE}. */
    static final String MSG_ACCT_NUMBER_11_DIGIT_B = " Non-Zero Number";

    /** {@code ' must be supplied.'} - {@code 1215}, {@code 1220}, {@code 1225}, {@code 1230}, {@code 1245}, {@code 1250}. */
    static final String MSG_MUST_BE_SUPPLIED = " must be supplied.";

    /** {@code ' must be Y or N.'} - {@code 1220-EDIT-YESNO} at {@code :1885}. */
    static final String MSG_MUST_BE_Y_OR_N = " must be Y or N.";

    /** {@code ' can have alphabets only.'} - {@code 1225} at {@code :1941} and {@code 1235} at {@code :2046}. */
    static final String MSG_ALPHABETS_ONLY = " can have alphabets only.";

    /** {@code ' can have numbers or alphabets only.'} - {@code 1230} at {@code :1998} and {@code 1240} at {@code :2095}. */
    static final String MSG_NUMBERS_OR_ALPHABETS_ONLY = " can have numbers or alphabets only.";

    /** {@code ' must be all numeric.'} - {@code 1245-EDIT-NUM-REQD} at {@code :2141}. */
    static final String MSG_MUST_BE_ALL_NUMERIC = " must be all numeric.";

    /** {@code ' must not be zero.'} - {@code 1245-EDIT-NUM-REQD} at {@code :2158}. */
    static final String MSG_MUST_NOT_BE_ZERO = " must not be zero.";

    /** {@code ' is not valid'} - {@code 1250-EDIT-SIGNED-9V2} at {@code :2213}. No full stop. */
    static final String MSG_IS_NOT_VALID = " is not valid";

    /** {@code ': Area code must be supplied.'} - {@code EDIT-AREA-CODE} at {@code :2254}. */
    static final String MSG_AREA_CODE_MUST_BE_SUPPLIED = ": Area code must be supplied.";

    /** {@code ': Area code must be A 3 digit number.'} ({@code :2270}) - the capital A is the source's. */
    static final String MSG_AREA_CODE_3_DIGITS = ": Area code must be A 3 digit number.";

    /** {@code ': Area code cannot be zero'} ({@code :2284}). No full stop. */
    static final String MSG_AREA_CODE_CANNOT_BE_ZERO = ": Area code cannot be zero";

    /** {@code ': Not valid North America general purpose area code'} ({@code :2306}). */
    static final String MSG_NOT_VALID_AREA_CODE =
            ": Not valid North America general purpose area code";

    /** {@code ': Prefix code must be supplied.'} - {@code EDIT-US-PHONE-PREFIX} at {@code :2325}. */
    static final String MSG_PREFIX_MUST_BE_SUPPLIED = ": Prefix code must be supplied.";

    /** {@code ': Prefix code must be A 3 digit number.'} ({@code :2342}). */
    static final String MSG_PREFIX_3_DIGITS = ": Prefix code must be A 3 digit number.";

    /** {@code ': Prefix code cannot be zero'} ({@code :2356}). */
    static final String MSG_PREFIX_CANNOT_BE_ZERO = ": Prefix code cannot be zero";

    /** {@code ': Line number code must be supplied.'} - {@code EDIT-US-PHONE-LINENUM} at {@code :2379}. */
    static final String MSG_LINENUM_MUST_BE_SUPPLIED = ": Line number code must be supplied.";

    /** {@code ': Line number code must be A 4 digit number.'} ({@code :2396}). */
    static final String MSG_LINENUM_4_DIGITS = ": Line number code must be A 4 digit number.";

    /** {@code ': Line number code cannot be zero'} ({@code :2410}). */
    static final String MSG_LINENUM_CANNOT_BE_ZERO = ": Line number code cannot be zero";

    /** {@code ': should not be 000, 666, or between 900 and 999'} - {@code 1265} at {@code :2461}. */
    static final String MSG_SSN_PART1_RANGE = ": should not be 000, 666, or between 900 and 999";

    /** {@code ': is not a valid state code'} - {@code 1270-EDIT-US-STATE-CD} at {@code :2503}. */
    static final String MSG_NOT_A_VALID_STATE_CODE = ": is not a valid state code";

    /** {@code ': should be between 300 and 850'} - {@code 1275-EDIT-FICO-SCORE} at {@code :2524}. */
    static final String MSG_FICO_RANGE = ": should be between 300 and 850";

    /** {@code 'Invalid zip code for state'} - {@code 1280} at {@code :2551}. Not prefixed by a field name. */
    static final String MSG_INVALID_ZIP_FOR_STATE = "Invalid zip code for state";

    // =================================================================================================
    // The three read paragraphs' NOTFND messages, split exactly as the source's STRING operands are,
    // because DELIMITED BY SIZE concatenates each operand at its full declared width and the embedded
    // double space in the cross-reference text is therefore part of the message.
    // =================================================================================================

    /** {@code 'Account:'} - {@code 9200} at {@code :3676} and {@code 9300} at {@code :3725}. */
    static final String STRING_ACCOUNT_PREFIX = "Account:";

    /** {@code ' not found in'} - the same two paragraphs. */
    static final String STRING_NOT_FOUND_IN = " not found in";

    /** {@code ' Cross ref file.  Resp:'} ({@code :3679}) - two spaces before {@code Resp}, as declared. */
    static final String STRING_CROSS_REF_FILE = " Cross ref file.  Resp:";

    /** {@code ' Acct Master file.Resp:'} ({@code :3728}) - no space after the full stop, as declared. */
    static final String STRING_ACCT_MASTER_FILE = " Acct Master file.Resp:";

    /** {@code ' Reas:'} ({@code :3681}, {@code :3730}) - lower case, unlike {@code 9400}'s. */
    static final String STRING_REAS = " Reas:";

    /** {@code 'CustId:'} - {@code 9400} at {@code :3773}. */
    static final String STRING_CUSTID_PREFIX = "CustId:";

    /** {@code ' not found'} ({@code :3775}) - shorter than the other two paragraphs' operand. */
    static final String STRING_NOT_FOUND = " not found";

    /** {@code ' in customer master.Resp: '} ({@code :3776}) - with the trailing space, as declared. */
    static final String STRING_IN_CUSTOMER_MASTER = " in customer master.Resp: ";

    /** {@code ' REAS:'} ({@code :3778}) - upper case here and lower case in the other two paragraphs. */
    static final String STRING_REAS_UPPER = " REAS:";

    // =================================================================================================
    // WS-FILE-ERROR-MESSAGE - app/cbl/COACTUPC.cbl:390-411. Eighty characters exactly, moved whole into
    // the 75-character WS-RETURN-MSG by all three WHEN OTHER arms, which truncates the trailing five.
    // =================================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} ({@code :390-391}). */
    static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code FILLER PIC X(4) VALUE ' on '} ({@code :392-393}). */
    static final String FILE_ERROR_ON = " on ";

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} ({@code :396-398}). */
    static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} ({@code :401-402}). */
    static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5) VALUE SPACES} ({@code :406-407}) - the five characters truncation removes. */
    static final String FILE_ERROR_TRAILER = "     ";

    /** {@code 'READ'}, the only value {@code ERROR-OPNAME} is ever given ({@code :3691}, {@code :3740}, {@code :3787}). */
    static final String READ_OPERATION_NAME = "READ";

    /** The composed width of {@code WS-FILE-ERROR-MESSAGE}: {@code 12+8+4+9+15+10+7+10+5}. */
    static final int FILE_ERROR_MESSAGE_LENGTH = 80;

    // =================================================================================================
    // ABEND-DATA's literals - :2646-2649 (2000-DECIDE-ACTION's WHEN OTHER) and :4207 (ABEND-ROUTINE).
    // =================================================================================================

    /** {@code MOVE '0001' TO ABEND-CODE} ({@code :2647}) - the unexpected-state abend. */
    static final String ABEND_CODE_UNEXPECTED_DATA = "0001";

    /** {@code MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG} ({@code :2649-2650}). */
    static final String ABEND_MSG_UNEXPECTED_DATA_SCENARIO = "UNEXPECTED DATA SCENARIO";

    /** {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} ({@code :4206}) - the default. */
    static final String UNEXPECTED_ABEND_OCCURRED = "UNEXPECTED ABEND OCCURRED.";

    /** {@code EXEC CICS ABEND ABCODE('9999')} ({@code :4223-4224}). Four characters, not a return code. */
    static final String ABEND_ROUTINE_ABCODE = "9999";

    /** What the abend log records in place of a triggering failure when the program abends on its own logic. */
    static final String NO_TRIGGERING_FAILURE = "no triggering failure";

    // =================================================================================================
    // Request-parameter names for the three EXEC interface block fields this program reads.
    // =================================================================================================

    /** The canonical spelling of the {@code EIBAID} query parameter. */
    static final String EIBAID_PARAM = "eibaid";

    /** The alternate spelling, accepted so a client written against either reaches the same code. */
    static final String EIBAID_PARAM_ALIAS = "eibAid";

    /** The name of the query parameter carrying {@code EIBCALEN}. */
    static final String EIBCALEN_PARAM = "eibcalen";

    /**
     * The payload member the URI's account identifier binds, spelled as the client sends it.
     *
     * <p>Lowercase and {@code xxxI}-derived, which is this module's one JSON naming convention, so a
     * refusal names the member the caller can find in its own 54-field request body.
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


    /** The route: {@code PUT /api/accounts/{acctId}}, CSD transaction {@code CAUP}. */
    static final String ACCOUNT_UPDATE_PATH = "/api/accounts/{acctId}";

    // =================================================================================================
    // The WS-EDIT-VARIABLE-NAME literals - app/cbl/COACTUPC.cbl:1471-1668. FUNCTION TRIM renders each
    // into the error text, so each is transcribed exactly and used at exactly one edit site.
    // =================================================================================================

    /** {@code MOVE 'Account Status' TO WS-EDIT-VARIABLE-NAME} ({@code :1472}). */
    static final String NAME_ACCOUNT_STATUS = "Account Status";

    /** {@code :1477}. Drives {@code EDIT-DATE-CCYYMMDD} over {@code ACUP-NEW-OPEN-DATE}. */
    static final String NAME_OPEN_DATE = "Open Date";

    /** {@code :1484}. */
    static final String NAME_CREDIT_LIMIT = "Credit Limit";

    /** {@code :1490}. */
    static final String NAME_EXPIRY_DATE = "Expiry Date";

    /** {@code :1496}. */
    static final String NAME_CASH_CREDIT_LIMIT = "Cash Credit Limit";

    /** {@code :1503}. */
    static final String NAME_REISSUE_DATE = "Reissue Date";

    /** {@code :1509}. */
    static final String NAME_CURRENT_BALANCE = "Current Balance";

    /** {@code :1515}. Twenty-six characters, which is one more than {@code PIC X(25)} holds. */
    static final String NAME_CURRENT_CYCLE_CREDIT_LIMIT = "Current Cycle Credit Limit";

    /** {@code :1522}. Twenty-five characters exactly. */
    static final String NAME_CURRENT_CYCLE_DEBIT_LIMIT = "Current Cycle Debit Limit";

    /** {@code :1528}. Set before {@code 1265-EDIT-US-SSN}, which immediately overwrites it three times. */
    static final String NAME_SSN = "SSN";

    /** {@code :1532}. */
    static final String NAME_DATE_OF_BIRTH = "Date of Birth";

    /** {@code :1543}. */
    static final String NAME_FICO_SCORE = "FICO Score";

    /** {@code :1559}. */
    static final String NAME_FIRST_NAME = "First Name";

    /** {@code :1567}. */
    static final String NAME_MIDDLE_NAME = "Middle Name";

    /** {@code :1575}. */
    static final String NAME_LAST_NAME = "Last Name";

    /** {@code :1583}. */
    static final String NAME_ADDRESS_LINE_1 = "Address Line 1";

    /** {@code :1591}. */
    static final String NAME_STATE = "State";

    /** {@code :1604}. */
    static final String NAME_ZIP = "Zip";

    /**
     * {@code :1612}.
     *
     * <p>The city edit reads {@code ACUP-NEW-CUST-ADDR-LINE-3}, and the commented-out
     * {@code MOVE 'Address Line 2'} directly above it at {@code :1611} records what the author
     * abandoned: address line 2 is never edited at all.
     */
    static final String NAME_CITY = "City";

    /** {@code :1619}. */
    static final String NAME_COUNTRY = "Country";

    /** {@code :1627}. */
    static final String NAME_PHONE_NUMBER_1 = "Phone Number 1";

    /** {@code :1634}. */
    static final String NAME_PHONE_NUMBER_2 = "Phone Number 2";

    /** {@code :1641}. */
    static final String NAME_EFT_ACCOUNT_ID = "EFT Account Id";

    /** {@code :1649}. */
    static final String NAME_PRIMARY_CARD_HOLDER = "Primary Card Holder";

    /** {@code MOVE 'SSN: First 3 chars' TO WS-EDIT-VARIABLE-NAME} ({@code :2444}), inside {@code 1265}. */
    static final String NAME_SSN_FIRST_3 = "SSN: First 3 chars";

    /** {@code :2470}. The ampersand is the source's. */
    static final String NAME_SSN_4TH_5TH = "SSN 4th & 5th chars";

    /** {@code :2481}. */
    static final String NAME_SSN_LAST_4 = "SSN Last 4 chars";

    // =================================================================================================
    // The lengths 1200-EDIT-MAP-INPUTS moves into WS-EDIT-ALPHANUM-LENGTH before each generic edit.
    // They are the widths of the ACUP-NEW-CUST items, not of the screen fields, and the generic edits
    // examine WS-EDIT-ALPHANUM-ONLY(1:this) rather than the whole 256-character staging item.
    // =================================================================================================

    /** {@code MOVE 3 TO WS-EDIT-ALPHANUM-LENGTH} for the FICO score ({@code :1546}) and SSN part 1 ({@code :2446}). */
    static final int EDIT_LENGTH_3 = 3;

    /** {@code MOVE 2} for the state code ({@code :1593}) and SSN part 2 ({@code :2472}). */
    static final int EDIT_LENGTH_2 = 2;

    /** {@code MOVE 4} for SSN part 3 ({@code :2483}). */
    static final int EDIT_LENGTH_4 = 4;

    /** {@code MOVE 5} for the zip code ({@code :1606}). */
    static final int EDIT_LENGTH_5 = 5;

    /** {@code MOVE 10} for the EFT account identifier ({@code :1644}). */
    static final int EDIT_LENGTH_10 = 10;

    /** {@code MOVE 25} for the three name fields ({@code :1561}, {@code :1569}, {@code :1577}). */
    static final int EDIT_LENGTH_25 = 25;

    /** {@code MOVE 50} for address line 1 ({@code :1585}) and the city ({@code :1614}). */
    static final int EDIT_LENGTH_50 = 50;

    /** {@code INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999} ({@code :121-123}) - the first excluded value. */
    static final int SSN_PART1_EXCLUDED_ZERO = 0;

    /** The second excluded value of {@code INVALID-SSN-PART1}. */
    static final int SSN_PART1_EXCLUDED_666 = 666;

    /** The low bound of {@code INVALID-SSN-PART1}'s excluded range. */
    static final int SSN_PART1_EXCLUDED_RANGE_LOW = 900;

    /** The high bound of {@code INVALID-SSN-PART1}'s excluded range. */
    static final int SSN_PART1_EXCLUDED_RANGE_HIGH = 999;

    /** {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}, the two characters {@code 1280} pairs with the state code. */
    static final int ZIP_PREFIX_LENGTH = 2;

    /**
     * The low bound of {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} -
     * {@code app/cbl/COACTUPC.cbl:848-849}.
     */
    static final int FICO_RANGE_LOW = 300;

    /** The high bound of the same condition name. */
    static final int FICO_RANGE_HIGH = 850;

    // =================================================================================================
    // The codec, and the one PICTURE this class has to render itself.
    // =================================================================================================

    /**
     * The {@code PICTURE}-rule codec for this class's {@code static} members - and for nothing that
     * becomes bytes.
     *
     * <p>It exists because a Java assignment is <strong>not</strong> a COBOL {@code MOVE}.
     * {@code COACTUPC} contains 502 {@code MOVE} statements and many of them cross a width: for
     * {@code PIC X} COBOL keeps the leading characters and space pads on the right, and for
     * {@code PIC 9} it keeps the trailing digits and zero pads on the left. Getting the direction wrong
     * is silent, so no cross-width move in this class is written as an assignment.
     *
     * <p><strong>Its scope is deliberately narrow, and the boundary is the code page.</strong>
     * {@link FixedWidthCodec#movePicX(String, int)}, {@link FixedWidthCodec#movePic9(String, int)},
     * {@link FixedWidthCodec#decodePic9(String)} and
     * {@link FixedWidthCodec#encodeSignedScaled(java.math.BigDecimal, int, int)} count and place
     * <em>characters</em>: pad, truncate, zero-fill and overpunch produce the same characters under
     * {@code US-ASCII} and under {@code IBM037}, so which code page this codec carries cannot affect
     * their result. Every operation that does depend on the code page - constructing a record area,
     * encoding or decoding a whole image, and the codec handed to
     * {@link AccountUpdateService#writeProcessing} - uses the injected {@link #codec} instead, because
     * that one carries the page the datasets are actually stored in.
     *
     * <p>An earlier revision used this codec for those operations too, and it was the persistence
     * defect it looks like: {@code IBM037} is what {@code application.yml} binds in production, the
     * staged 300-byte and 500-byte images were decoded as {@code US-ASCII}, and the repositories wrote
     * the resulting bytes verbatim. The {@code US-ASCII} test profile made the two agree, so no test
     * could see it. {@code AccountUpdateService} now refuses a codec that is not the datasets' own.
     *
     * <p>It is a {@code static} constant because a {@code static} initialiser cannot reach an instance
     * field, and the literals below are padded at class-load time. It is a shared immutable value, so
     * it is safe as a constant and is not mutable static state (practice B9).
     */
    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * {@code WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99} ({@code app/cbl/COACTUPC.cbl:373}) - the
     * integer half of the mask, without the fixed sign and without the {@code .99}.
     *
     * <p>Held as a template so {@link #editCurrency92} can walk it left to right and apply the zero
     * suppression rule to the two commas as well as to the nine digit positions, which is what COBOL
     * does: a simple insertion character within or to the left of a suppressed position is itself
     * suppressed.
     */
    private static final String CURRENCY_INTEGER_TEMPLATE = "ZZZ,ZZZ,ZZZ";

    /** The zero-suppression character of {@link #CURRENCY_INTEGER_TEMPLATE}. */
    private static final char SUPPRESSION_CHARACTER = 'Z';

    /** How many digit positions {@link #CURRENCY_INTEGER_TEMPLATE} has - nine, not ten. */
    private static final int CURRENCY_INTEGER_DIGITS = 9;

    /** {@code PIC S9(10)V99}'s integer digit count, which is one more than the mask can show. */
    private static final int MONETARY_INTEGER_DIGITS = 10;

    /** {@code PIC S9(10)V99}'s fraction digit count. */
    private static final int MONETARY_FRACTION_DIGITS = 2;

    /** The character a positive or zero value puts in the mask's fixed sign position. */
    private static final char PLUS_SIGN = '+';

    /** The character a negative value puts there. */
    private static final char MINUS_SIGN = '-';

    /** The mask's decimal point, an insertion character that zero suppression never reaches. */
    private static final String CURRENCY_DECIMAL_POINT = ".";

    /** A single space, for the suppression replacement and for the {@code IS NUMERIC} guards. */
    private static final char SPACE = ' ';

    /** {@code LOW-VALUE}: the byte with every bit clear, which is what an untransmitted field carries. */
    private static final char LOW_VALUE = '\u0000';

    /** {@code '0'}, tested by {@code 1220-EDIT-YESNO}'s {@code EQUAL ZEROS} guard at {@code :1866}. */
    private static final char ZERO_DIGIT = '0';

    /** One, so the SSN and date slice arithmetic reads as slicing rather than as counting. */
    private static final int ONE = 1;

    /** {@code MOVE -1 TO xxxL}: the length item value that asks CICS to place the cursor on a field. */
    private static final int CURSOR_HERE = AccountUpdateRequest.FieldMetadata.CURSOR_HERE;

    /**
     * The 42 receivers of {@code 3201-SHOW-INITIAL-VALUES}' single {@code MOVE LOW-VALUES} -
     * {@code app/cbl/COACTUPC.cbl:2732-2780}, in source order.
     *
     * <p>Twelve of the mapset's 54 named fields are deliberately absent: the six header items and the
     * three function-key legends, which {@code 3100-SCREEN-INIT} owns; the two message lines, which
     * {@code 3250-SETUP-INFOMSG} owns; and {@code ACCTSID}, which {@code 3200-SETUP-SCREEN-VARS} has set
     * immediately before this paragraph runs. Clearing the filter here would erase the value the operator
     * just typed, which is why it is not in the list.
     */
    static final List<AccountUpdateResponse.ScreenField> INITIAL_VALUE_FIELDS = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.AADDGRP,
            AccountUpdateResponse.ScreenField.ACSTNUM,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSGOVT,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG);

    /** The verified receiver count of {@code 3201}'s {@code MOVE LOW-VALUES}. */
    static final int INITIAL_VALUE_FIELD_COUNT = 42;

    /**
     * The 44 receivers of {@code 3310-PROTECT-ALL-ATTRS}' single {@code MOVE DFHBMPRF} -
     * {@code app/cbl/COACTUPC.cbl:3442-3492}, in source order.
     *
     * <p>Every input-capable field plus {@code INFOMSG}: {@code ACCTSID} is included here, unlike in
     * {@link #INITIAL_VALUE_FIELDS}, because protecting the filter and then selectively unprotecting it is
     * exactly how {@code 3300-SETUP-SCREEN-ATTRS} decides whether the operator may retype it. The header
     * items and the three legends have no {@code xxxA} write in this paragraph.
     */
    static final List<AccountUpdateResponse.ScreenField> PROTECTABLE_FIELDS = List.of(
            AccountUpdateResponse.ScreenField.ACCTSID,
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.AADDGRP,
            AccountUpdateResponse.ScreenField.ACSTNUM,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSGOVT,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG,
            AccountUpdateResponse.ScreenField.INFOMSG);

    /**
     * The fields {@code 3320-UNPROTECT-FEW-ATTRS} makes writable with {@code DFHBMFSE} -
     * {@code app/cbl/COACTUPC.cbl:3500-3562}, in source order.
     *
     * <p>Four fields the previous paragraph protected stay protected, and each for a stated reason:
     * {@code ACCTSID} because the account being updated must not change under the operator's hands,
     * {@code ACSTNUM} because the customer identifier is not editable ({@code :3536}),
     * {@code ACSCTRY} because - in the source's own words at {@code :3555} - "most of the edits are USA
     * specific", and {@code INFOMSG} because it is an output line.
     */
    static final List<AccountUpdateResponse.ScreenField> UNPROTECTED_FIELDS = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.AADDGRP,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSGOVT,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG);

    /**
     * The cursor-positioning arms of {@code 3300-SETUP-SCREEN-ATTRS}' second {@code EVALUATE TRUE} -
     * {@code app/cbl/COACTUPC.cbl:3010-3167}, in source order, as pairs of the field whose verdict is
     * tested and the field the cursor moves to.
     *
     * <p>Order is the whole behaviour: the first field whose verdict is {@code NOT-OK} or {@code BLANK}
     * gets the cursor and no later arm is evaluated, so the operator is always taken to the topmost
     * problem on the screen. The source comments the order as "based on screen location", and one arm
     * proves it: {@code STATE} is tested before {@code ZIPCODE} and {@code CITY} because on the map it
     * sits next to address line 2, above both.
     *
     * <p>Two arms cannot be expressed as a pair and are handled outside this list - the leading
     * {@code FOUND-ACCOUNT-DATA} / {@code NO-CHANGES-DETECTED} arm, the {@code FLG-ACCTFILTER} arm, and the
     * trailing {@code WHEN OTHER} - and one entry is deliberately irregular: {@code ACSMNAM} is tested for
     * {@code NOT-OK} <strong>only</strong>, with no {@code BLANK} arm ({@code :3122-3123}), because a blank
     * middle name is valid. See {@link #cursorArmMatches}.
     */
    static final List<AccountUpdateResponse.ScreenField> CURSOR_ORDER = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSEFTC,
            AccountUpdateResponse.ScreenField.ACSPFLG);

    /**
     * The 39 {@code COPY CSSETATY} expansion sites of {@code 3300-SETUP-SCREEN-ATTRS} -
     * {@code app/cbl/COACTUPC.cbl:3208-3439}, in source order.
     *
     * <p>Verified count: {@code grep -c 'COPY CSSETATY' app/cbl/COACTUPC.cbl} returns 39. The list is the
     * {@code (SCRNVAR2)} operand of each expansion; the {@code (TESTVAR1)} operand names the same field's
     * flag group, which in this translation is the same map of verdicts.
     *
     * <p>Eight of the 47 fields {@code 3310} protects are absent, and each absence is meaningful:
     * {@code ACCTSID} is highlighted by hand at {@code :3178-3184} because its rule differs - the colour
     * is set even outside REENTER; {@code ACSTNUM}, {@code AADDGRP} and {@code ACSGOVT} are never edited,
     * so they have no verdict to show; and {@code INFOMSG}, {@code ERRMSG}, {@code FKEYS} and the two
     * legends are output lines.
     */
    static final List<AccountUpdateResponse.ScreenField> HIGHLIGHT_ORDER = List.of(
            AccountUpdateResponse.ScreenField.ACSTTUS,
            AccountUpdateResponse.ScreenField.OPNYEAR,
            AccountUpdateResponse.ScreenField.OPNMON,
            AccountUpdateResponse.ScreenField.OPNDAY,
            AccountUpdateResponse.ScreenField.ACRDLIM,
            AccountUpdateResponse.ScreenField.EXPYEAR,
            AccountUpdateResponse.ScreenField.EXPMON,
            AccountUpdateResponse.ScreenField.EXPDAY,
            AccountUpdateResponse.ScreenField.ACSHLIM,
            AccountUpdateResponse.ScreenField.RISYEAR,
            AccountUpdateResponse.ScreenField.RISMON,
            AccountUpdateResponse.ScreenField.RISDAY,
            AccountUpdateResponse.ScreenField.ACURBAL,
            AccountUpdateResponse.ScreenField.ACRCYCR,
            AccountUpdateResponse.ScreenField.ACRCYDB,
            AccountUpdateResponse.ScreenField.ACTSSN1,
            AccountUpdateResponse.ScreenField.ACTSSN2,
            AccountUpdateResponse.ScreenField.ACTSSN3,
            AccountUpdateResponse.ScreenField.DOBYEAR,
            AccountUpdateResponse.ScreenField.DOBMON,
            AccountUpdateResponse.ScreenField.DOBDAY,
            AccountUpdateResponse.ScreenField.ACSTFCO,
            AccountUpdateResponse.ScreenField.ACSFNAM,
            AccountUpdateResponse.ScreenField.ACSMNAM,
            AccountUpdateResponse.ScreenField.ACSLNAM,
            AccountUpdateResponse.ScreenField.ACSADL1,
            AccountUpdateResponse.ScreenField.ACSSTTE,
            AccountUpdateResponse.ScreenField.ACSADL2,
            AccountUpdateResponse.ScreenField.ACSZIPC,
            AccountUpdateResponse.ScreenField.ACSCITY,
            AccountUpdateResponse.ScreenField.ACSCTRY,
            AccountUpdateResponse.ScreenField.ACSPH1A,
            AccountUpdateResponse.ScreenField.ACSPH1B,
            AccountUpdateResponse.ScreenField.ACSPH1C,
            AccountUpdateResponse.ScreenField.ACSPH2A,
            AccountUpdateResponse.ScreenField.ACSPH2B,
            AccountUpdateResponse.ScreenField.ACSPH2C,
            AccountUpdateResponse.ScreenField.ACSPFLG,
            AccountUpdateResponse.ScreenField.ACSEFTC);

    /** The verified {@code COPY CSSETATY} site count in {@code COACTUPC}. */
    static final int HIGHLIGHT_SITE_COUNT = 39;

    /** The verified receiver count of {@code 3310}'s {@code MOVE DFHBMPRF}. */
    static final int PROTECTABLE_FIELD_COUNT = 44;

    /** The verified {@code DFHBMFSE} receiver count in {@code 3320}. */
    static final int UNPROTECTED_FIELD_COUNT = 40;

    /**
     * The verified count of field-verdict arms in {@code 3300}'s cursor {@code EVALUATE}.
     *
     * <p>The paragraph contains 41 {@code MOVE -1} statements; three of them are not field verdicts - the
     * leading {@code FOUND-ACCOUNT-DATA} / {@code NO-CHANGES-DETECTED} arm, the {@code FLG-ACCTFILTER} arm
     * and the trailing {@code WHEN OTHER} - which leaves 38.
     */
    static final int CURSOR_ARM_COUNT = 38;

    /**
     * The declared width of a date as the two master records store it - {@code PIC X(10)}, formatted
     * {@code YYYY-MM-DD}, which is why {@code 9500-STORE-FETCHED-DATA} slices it rather than moving it
     * whole into the eight-character screen span.
     */
    static final int RECORD_DATE_LENGTH = 10;

    /** {@code (1:4)} - the year, zero based. */
    static final int RECORD_DATE_YEAR_OFFSET = 0;

    /** {@code (6:2)} - the month, zero based, skipping the first hyphen. */
    static final int RECORD_DATE_MONTH_OFFSET = 5;

    /** {@code (9:2)} - the day, zero based, skipping the second hyphen. */
    static final int RECORD_DATE_DAY_OFFSET = 8;

    static {
        // The literals are asserted rather than trusted. Every one of them reaches the operator, is
        // compared against a payload value, or names a CICS resource, so a transcription slip is a
        // parity failure - and one that a diff of a painted screen reports as a mystery rather than as
        // a typo. Asserting them at class-initialisation time turns that into a startup failure that
        // names the source line.
        requireLiteral(LIT_THISPGM, "COACTUPC", "LIT-THISPGM", 533);
        requireLiteral(LIT_THISTRANID, "CAUP", "LIT-THISTRANID", 535);
        requireLiteral(LIT_THISMAPSET, "COACTUP ", "LIT-THISMAPSET", 537);
        requireLiteral(LIT_THISMAP, "CACTUPA", "LIT-THISMAP", 539);
        requireLiteral(LIT_CARDUPDATE_PGM, "COCRDUPC", "LIT-CARDUPDATE-PGM", 542);
        requireLiteral(LIT_CARDUPDATE_TRANID, "CCUP", "LIT-CARDUPDATE-TRANID", 544);
        requireLiteral(LIT_CARDUPDATE_MAPSET, "COCRDUP ", "LIT-CARDUPDATE-MAPSET", 546);
        requireLiteral(LIT_CARDUPDATE_MAP, "CCRDUPA", "LIT-CARDUPDATE-MAP", 548);
        requireLiteral(LIT_CCLISTPGM, "COCRDLIC", "LIT-CCLISTPGM", 550);
        requireLiteral(LIT_CCLISTTRANID, "CCLI", "LIT-CCLISTTRANID", 552);
        requireLiteral(LIT_CCLISTMAPSET, "COCRDLI", "LIT-CCLISTMAPSET", 554);
        requireLiteral(LIT_CCLISTMAP, "CCRDSLA", "LIT-CCLISTMAP", 556);
        requireLiteral(LIT_MENUPGM, "COMEN01C", "LIT-MENUPGM", 558);
        requireLiteral(LIT_MENUTRANID, "CM00", "LIT-MENUTRANID", 560);
        requireLiteral(LIT_MENUMAPSET, "COMEN01", "LIT-MENUMAPSET", 562);
        requireLiteral(LIT_MENUMAP, "COMEN1A", "LIT-MENUMAP", 564);
        requireLiteral(LIT_CARDDTLPGM, "COCRDSLC", "LIT-CARDDTLPGM", 566);
        requireLiteral(LIT_CARDDTLTRANID, "CCDL", "LIT-CARDDTLTRANID", 568);
        requireLiteral(LIT_CARDDTLMAPSET, "COCRDSL", "LIT-CARDDTLMAPSET", 570);
        requireLiteral(LIT_CARDDTLMAP, "CCRDSLA", "LIT-CARDDTLMAP", 572);
        requireLiteral(LIT_ACCTFILENAME, "ACCTDAT ", "LIT-ACCTFILENAME", 574);
        requireLiteral(LIT_CUSTFILENAME, "CUSTDAT ", "LIT-CUSTFILENAME", 576);
        requireLiteral(LIT_CARDFILENAME, "CARDDAT ", "LIT-CARDFILENAME", 578);
        requireLiteral(LIT_CARDFILENAME_ACCT_PATH, "CARDAIX ", "LIT-CARDFILENAME-ACCT-PATH", 580);
        requireLiteral(LIT_CARDXREFNAME_ACCT_PATH, "CXACAIX ", "LIT-CARDXREFNAME-ACCT-PATH", 582);
        requireLiteral(LIT_UPPER, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "LIT-UPPER", 588);
        requireLiteral(LIT_LOWER, "abcdefghijklmnopqrstuvwxyz", "LIT-LOWER", 590);
        requireLiteral(LIT_NUMBERS, "0123456789", "LIT-NUMBERS", 592);

        // The DTO pair must agree with this class about which program, transaction, mapset and map this
        // is; the payload carries all four and a disagreement would produce a screen that names the
        // wrong program.
        requireLiteral(AccountUpdateRequest.PROGRAM_NAME, LIT_THISPGM, "COACTUP.CPY's program", 533);
        requireLiteral(AccountUpdateRequest.TRANSACTION_ID, LIT_THISTRANID,
                "COACTUP.CPY's transaction", 535);
        requireLiteral(AccountUpdateRequest.MAP_NAME, LIT_THISMAP, "COACTUP.CPY's map", 539);
        requireLiteral(AccountUpdateResponse.THIS_PROGRAM, LIT_THISPGM, "LIT-THISPGM", 533);
        requireLiteral(AccountUpdateResponse.MAP_NAME, LIT_THISMAP, "LIT-THISMAP", 539);

        // The two screen titles, which 3100-SCREEN-INIT moves out of COTTL01Y at :2673-2674.
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20-22");

        // The composed file-error message has to be exactly eighty characters wide before it is
        // truncated into WS-RETURN-MSG, and the eight FILLER literals are what make it so.
        int composed = FILE_ERROR_PREFIX.length() + ERROR_OPNAME_LENGTH + FILE_ERROR_ON.length()
                + ERROR_FILE_LENGTH + FILE_ERROR_RETURNED_RESP.length() + ERROR_RESP_LENGTH
                + FILE_ERROR_RESP2.length() + ERROR_RESP_LENGTH + FILE_ERROR_TRAILER.length();
        if (composed != FILE_ERROR_MESSAGE_LENGTH) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE is declared "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters wide by app/cbl/COACTUPC.cbl:390-411, "
                    + "but this class's literals compose to " + composed);
        }

        // The five field lists are transcriptions of five COBOL statement groups, and a missing or
        // duplicated entry in any of them is a silent parity failure: a field that is never cleared, never
        // protected, never highlighted, or that steals the cursor from the field above it. Their verified
        // sizes are asserted here so a transcription slip is a startup failure.
        requireListSize(INITIAL_VALUE_FIELDS.size(), INITIAL_VALUE_FIELD_COUNT,
                "3201-SHOW-INITIAL-VALUES' MOVE LOW-VALUES", "2732-2780");
        requireListSize(PROTECTABLE_FIELDS.size(), PROTECTABLE_FIELD_COUNT,
                "3310-PROTECT-ALL-ATTRS' MOVE DFHBMPRF", "3442-3492");
        requireListSize(UNPROTECTED_FIELDS.size(), UNPROTECTED_FIELD_COUNT,
                "3320-UNPROTECT-FEW-ATTRS' MOVE DFHBMFSE", "3500-3562");
        requireListSize(CURSOR_ORDER.size(), CURSOR_ARM_COUNT,
                "3300-SETUP-SCREEN-ATTRS' cursor EVALUATE", "3010-3164");
        requireListSize(HIGHLIGHT_ORDER.size(), HIGHLIGHT_SITE_COUNT,
                "3300-SETUP-SCREEN-ATTRS' COPY CSSETATY sites", "3208-3439");

        // The mask has to be fifteen characters, because WS-EDIT-CURRENCY-9-2-F is moved whole into
        // five PIC X(15) screen fields and a shorter or longer rendering would shift every one.
        int mask = 1 + CURRENCY_INTEGER_TEMPLATE.length() + CURRENCY_DECIMAL_POINT.length()
                + MONETARY_FRACTION_DIGITS;
        if (mask != WS_EDIT_CURRENCY_LENGTH) {
            throw new AssertionError("WS-EDIT-CURRENCY-9-2-F is PIC +ZZZ,ZZZ,ZZZ.99 - "
                    + WS_EDIT_CURRENCY_LENGTH + " characters - at app/cbl/COACTUPC.cbl:373, but this "
                    + "class's template composes to " + mask);
        }
    }

    /**
     * Asserts that a transcribed field list still has as many entries as the COBOL statement group it
     * represents.
     *
     * @param actual    the size this class carries
     * @param expected  the verified count
     * @param cobolWhat the statement group, for the message
     * @param cobolLines the lines of {@code app/cbl/COACTUPC.cbl} that contain it
     * @throws AssertionError if they differ
     */
    private static void requireListSize(int actual, int expected, String cobolWhat,
            String cobolLines) {
        if (actual != expected) {
            throw new AssertionError(cobolWhat + " has " + expected + " receivers at "
                    + "app/cbl/COACTUPC.cbl:" + cobolLines + ", but this class transcribes " + actual);
        }
    }

    /**
     * Asserts that a transcribed literal still equals what {@code app/cbl/COACTUPC.cbl} declares.
     *
     * @param actual    the value this class carries
     * @param expected  the literal the source declares
     * @param cobolName the COBOL data name, for the message
     * @param cobolLine the line of {@code app/cbl/COACTUPC.cbl} that declares it
     * @throws AssertionError if they differ
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COACTUPC.cbl:" + cobolLine);
    }

    /**
     * Asserts a transcribed literal whose source is a copybook rather than the program.
     *
     * @param actual    the value this class carries
     * @param expected  the declared literal
     * @param cobolName the COBOL data name, for the message
     * @param where     the file and line that declares it
     * @throws AssertionError if they differ
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    // =================================================================================================
    // Collaborators. Seven, all final, all constructor injected, none static and none mutable: a
    // partially wired instance cannot exist and a test can supply seven doubles without a Spring
    // context. COACTUPC's WORKING-STORAGE never becomes a field here - it becomes a Conversation,
    // created per request inside the request method.
    //
    // CardRepository is deliberately absent. COACTUPC declares LIT-CARDFILENAME and
    // LIT-CARDFILENAME-ACCT-PATH at :577-580 but no statement reads CARDDAT or its CARDAIX path, so
    // injecting a repository for it would state a dependency this program does not have.
    // =================================================================================================

    /**
     * The {@code ACCTDAT} access path read by {@code 9300-GETACCTDATA-BYACCT} at {@code :3702-3711}.
     *
     * <p>Injected here as well as into {@link AccountUpdateService} because the display read and the
     * read-for-update are different operations on the same file: this class performs the first and the
     * service performs the second, under its own unit of work.
     */
    private final AccountRepository accountRepository;

    /**
     * The {@code CCXREF} cluster, reached through its {@code CXACAIX} alternate-index finder by
     * {@code 9200-GETCARDXREF-BYACCT} at {@code :3653-3663}. One repository, two access paths.
     */
    private final CardXrefRepository cardXrefRepository;

    /** The {@code CUSTDAT} access path read by {@code 9400-GETCUSTDATA-BYCUST} at {@code :3752-3761}. */
    private final CustomerRepository customerRepository;

    /**
     * {@code 9600-WRITE-PROCESSING} ({@code :3889-4106}), {@code 9700-CHECK-CHANGE-IN-REC}
     * ({@code :4109-4193}) and the five {@code COMPUTE}s of {@code 1100-RECEIVE-MAP}.
     *
     * <p>Every one of those lives in the service and none of them is duplicated here, which is what
     * lets a parity case assert the arithmetic and the optimistic-concurrency check with no HTTP layer
     * and no {@code JobLauncher} in the path.
     */
    private final AccountUpdateService accountUpdateService;

    /**
     * {@code CSUTLDPY} and {@code CSUTLDWY}, the date-edit engine {@code COPY 'CSUTLDWY'} at
     * {@code :166} brings into {@code WORKING-STORAGE} and {@code COPY CSUTLDPY} at {@code :4232}
     * brings into the procedure division.
     *
     * <p>Driven three times by {@code 1200-EDIT-MAP-INPUTS} - for the open date at {@code :1478-1481},
     * the expiry date at {@code :1490-1493} and the reissue date at {@code :1503-1506} - and a fourth
     * time for the date of birth at {@code :1533-1536}, which then also runs
     * {@code EDIT-DATE-OF-BIRTH}. Reaching it also reaches {@code CSUTLDTC} transitively, through
     * {@code CSUTLDPY:293}: a fifth {@code CSUTLDTC} call site that a program-level count of the
     * {@code CALL} statements does not show.
     */
    private final AccountDateValidator accountDateValidator;

    /**
     * {@code CSLKPCDY}, the 1,318-line North American area-code and state table copied at
     * {@code :602}.
     *
     * <p>Probed at three sites: the area code inside {@code EDIT-AREA-CODE} at {@code :2297-2298}, the
     * state code in {@code 1270-EDIT-US-STATE-CD} at {@code :2494-2495}, and the state-plus-zip
     * combination in {@code 1280-EDIT-US-STATE-ZIP-CD} at {@code :2540-2542}.
     */
    private final AreaCodeLookup areaCodeLookup;

    /**
     * The instant behind {@code FUNCTION CURRENT-DATE}.
     *
     * <p>{@code 3100-SCREEN-INIT} reads it twice, at {@code :2671} and {@code :2678}, and
     * {@code EDIT-DATE-OF-BIRTH} reads it again to reject a future date. Injected rather than read
     * inline so a parity case is reproducible.
     */
    private final Clock clock;

    /**
     * The codec carrying the <em>active dataset</em> code page - the one every byte this transaction
     * produces or consumes is measured in.
     *
     * <p>Three things in this class turn characters into bytes or bytes into characters, and all three
     * use this codec rather than {@link #PIC_X_CODEC}: the {@code ACCOUNT-RECORD} work area
     * ({@code :3388}, {@code :3656}), the {@code DFHCOMMAREA} round trips at {@code :889-893} and
     * {@code :1010-1013}, and the codec handed to
     * {@link AccountUpdateService#writeProcessing(String, NavigationContext,
     * AccountUpdateService.AccountUpdateDetails, AccountUpdateService.AccountUpdateDetails, String,
     * FixedWidthCodec)}, which decodes the two staged images into the records it rewrites.
     *
     * <p>The {@code Charset} is qualified explicitly at the constructor rather than defaulted, because
     * {@code CobolCharsetConfig} publishes more than one and the platform default is never consulted
     * (practice <strong>B8</strong>). It is held rather than rebuilt per call because a codec is
     * immutable and validates its code page once at construction.
     */
    private final FixedWidthCodec codec;

    /**
     * Constructs the controller.
     *
     * @param accountRepository     the account master, {@code ACCTDAT}
     * @param cardXrefRepository    the card cross reference and its {@code CXACAIX} path
     * @param customerRepository    the customer master, {@code CUSTDAT}
     * @param accountUpdateService  the write path and the five {@code COMPUTE}s
     * @param accountDateValidator  {@code CSUTLDPY} plus {@code CSUTLDWY}
     * @param areaCodeLookup        {@code CSLKPCDY}
     * @param clock                 the clock behind {@code FUNCTION CURRENT-DATE}
     * @param datasetCharset        the active dataset code page, from
     *                              {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}. It
     *                              is what the two record images this transaction rewrites are encoded
     *                              in, so it must be the page both repositories use - never the
     *                              platform default and never a hard-coded one
     * @throws NullPointerException if any collaborator is {@code null}
     */
    public AccountUpdateController(AccountRepository accountRepository,
            CardXrefRepository cardXrefRepository,
            CustomerRepository customerRepository,
            AccountUpdateService accountUpdateService,
            AccountDateValidator accountDateValidator,
            AreaCodeLookup areaCodeLookup,
            Clock clock,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.accountRepository = Objects.requireNonNull(accountRepository,
                "An AccountRepository is required: 9300-GETACCTDATA-BYACCT reads ACCTDAT at "
                        + "app/cbl/COACTUPC.cbl:3702-3711 and this controller reaches it no other way");
        this.cardXrefRepository = Objects.requireNonNull(cardXrefRepository,
                "A CardXrefRepository is required: 9200-GETCARDXREF-BYACCT reads the CXACAIX path at "
                        + "app/cbl/COACTUPC.cbl:3653-3663, and it is that read which supplies the "
                        + "customer id the third read needs");
        this.customerRepository = Objects.requireNonNull(customerRepository,
                "A CustomerRepository is required: 9400-GETCUSTDATA-BYCUST reads CUSTDAT at "
                        + "app/cbl/COACTUPC.cbl:3752-3761");
        this.accountUpdateService = Objects.requireNonNull(accountUpdateService,
                "An AccountUpdateService is required: 2000-DECIDE-ACTION performs "
                        + "9600-WRITE-PROCESSING at app/cbl/COACTUPC.cbl:2601-2602 and this controller "
                        + "must not re-implement it");
        this.accountDateValidator = Objects.requireNonNull(accountDateValidator,
                "An AccountDateValidator is required: 1200-EDIT-MAP-INPUTS performs "
                        + "EDIT-DATE-CCYYMMDD four times, at app/cbl/COACTUPC.cbl:1479, :1491, :1504 "
                        + "and :1534");
        this.areaCodeLookup = Objects.requireNonNull(areaCodeLookup,
                "An AreaCodeLookup is required: COPY CSLKPCDY at app/cbl/COACTUPC.cbl:602 supplies the "
                        + "three lookups the phone, state and zip edits test against");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 3100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice and "
                        + "EDIT-DATE-OF-BIRTH reads it again; reading a clock inline would make every "
                        + "parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "The active dataset "
                + "code page is required: 9600-WRITE-PROCESSING rewrites a 300-byte ACCOUNT-RECORD and "
                + "a 500-byte CUSTOMER-RECORD at app/cbl/COACTUPC.cbl:4065-4091, and those bytes are "
                + "only right in the page the datasets are stored in; it is never the platform "
                + "default"));
    }

    /**
     * The codec this controller applies the {@code PIC X} and {@code PIC 9} move rules with, carrying
     * the active dataset code page.
     *
     * <p>Exposed so a test can drive a move through the same instance the flow uses rather than through
     * a lookalike, and so a test can assert <em>which</em> code page is in force on the write path.
     *
     * @return the injected dataset codec, never {@code null} and immutable
     */
    FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // The endpoint. CICS transaction CAUP, projected onto PUT /api/accounts/{acctId}.
    //
    // Always 200 with a painted screen, because that is what the program does: every arm of 0000-MAIN
    // converges on COMMON-RETURN and sends a map, and a record that could not be found is reported in
    // ERRMSGO rather than by refusing to answer. PUT rather than POST because the request is idempotent
    // in the sense that matters here - the same payload twice reaches the same 2000-DECIDE-ACTION arm,
    // and the second pass finds the record already equal to what it would write.
    // =================================================================================================

    /**
     * Runs one {@code CAUP} interaction.
     *
     * @param acctId   the account identifier from the URI; what {@code ACCTSIDI} would have carried, and
     *                 the {@code RIDFLD} of the reads at {@code :3656} and {@code :3705}
     * @param request  the terminal input area and the carried conversation state, or {@code null} on a
     *                 cold start - which is the {@code EIBCALEN IS EQUAL TO 0} arm of {@code :880}
     * @param eibAid   {@code EIBAID} under its alternate spelling, or {@code null} for {@code DFHENTER}
     * @param eibcalen {@code EIBCALEN}, or {@code null} to derive it from the payload
     * @param eibaid   {@code EIBAID} under its canonical spelling
     * @return the painted {@code CACTUPAO} map area with its screen metadata, the navigation context,
     *         the work area and both detail groups
     * @throws IllegalArgumentException if the URI, {@code EIBCALEN} or {@code EIBAID} cannot describe a
     *                                  state this program can be entered in
     * @throws AbendException           if the interaction abends, reproducing {@code ABEND-ROUTINE}
     */
    @PutMapping(path = ACCOUNT_UPDATE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<AccountUpdateResponse>> updateAccount(
            @PathVariable("acctId") String acctId,
            @Valid @RequestBody(required = false) AccountUpdateRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {
        Objects.requireNonNull(acctId, "An account identifier is required in the path: it is what "
                + "ACCTSIDI carries into 1100-RECEIVE-MAP at app/cbl/COACTUPC.cbl:1050 and the RIDFLD "
                + "of the reads at :3656 and :3705");

        AccountUpdateRequest received = bind(acctId, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier = resolveAttentionIdentifier(resolveAidParameter(eibaid, eibAid));

        PaintedScreen painted = handle(received, commareaLength, attentionIdentifier);
        return ResponseEntity.ok(ScreenResponse.of(painted.response(),
                screenMetadataOf(painted.response(), painted.request(), painted.cursorField())));
    }

    /**
     * What one interaction produced: the map area, the input area whose metadata carries the field
     * attributes and the cursor, and the name of the field the cursor landed on.
     *
     * <p>Three values rather than one because the symbolic map is one set of bytes with two views:
     * {@code CACTUPAO}'s values and colours are on the response, and {@code CACTUPAI}'s length and
     * attribute items are on the request, exactly as {@code 3300-SETUP-SCREEN-ATTRS} writes them.
     *
     * @param response    the painted {@code CACTUPAO}
     * @param request     the input area after {@code 3300}, whose metadata holds every {@code xxxA} and
     *                    {@code xxxL}
     * @param cursorField the {@code DFHMDF} label of the field {@code MOVE -1 TO xxxL} selected, or
     *                    {@code null} when {@code 3300} never ran
     */
    record PaintedScreen(AccountUpdateResponse response, AccountUpdateRequest request,
            String cursorField) {

        /**
         * Rejects an incomplete pair.
         *
         * @throws NullPointerException if either area is {@code null}
         */
        PaintedScreen {
            Objects.requireNonNull(response, "A painted screen carries the CACTUPAO map area");
            Objects.requireNonNull(request, "A painted screen carries the CACTUPAI input area, "
                    + "because 3310-PROTECT-ALL-ATTRS and the cursor EVALUATE write into it");
        }
    }

    /**
     * Resolves the two accepted spellings of the attention-identifier parameter onto one value.
     *
     * <p>If both arrive they must agree: silently preferring one would let a caller believe it had
     * pressed a key it had not, and on this screen PF5 saves and PF12 cancels.
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
     * <p>Reproduces what the terminal would have done. {@code ACCTSID} is
     * {@code DFHMDF ... LENGTH=11} in {@code app/bms/COACTUP.bms}, so a value shorter than eleven
     * characters is space padded on the right by the {@code PIC X} move rule - and is consequently
     * <em>not numeric</em>, which is exactly why {@code 1210-EDIT-ACCOUNT} rejects it at {@code :1806}.
     * A value longer than eleven is rejected outright rather than truncated, because keeping the leading
     * eleven characters would silently address a different account than the URI names.
     *
     * <p>The request is copied rather than mutated, so a caller's object is never altered by having been
     * passed here - which is also what makes concurrent requests independent.
     *
     * <h4>The URI seeds a first entry and is ignored on a re-entry</h4>
     * A 3270 screen has one key field and no URI, and {@code COACTUPC} reads {@code ACCTSIDI} in exactly
     * one place: {@code 1100-RECEIVE-MAP} at {@code :1039-1058}, reached only from
     * {@code 1000-PROCESS-INPUTS}, which the {@code EVALUATE} at {@code :925-1005} performs on the
     * re-entry arms alone. The entry arm paints the screen from the key it was handed, and that is what
     * the path variable projects.
     *
     * <p>So the path value is written into {@code ACCTSIDI} on a first entry - no communication area, or
     * one whose context is not re-entry - and on a re-entry the received field is left <strong>exactly as
     * it arrived</strong> and processed by {@code 1200-EDIT-MAP-INPUTS} and {@code 9000-READ-ACCT}.
     * Typing another account number over a painted screen is a valid action of this screen: it is the
     * whole of how an operator moves from one account to the next, and it is neither overwritten nor
     * refused here.
     *
     * @param acctId  the account identifier from the URI
     * @param request the received payload, or {@code null} on a cold start
     * @return on a first entry, a request whose {@code ACCTSIDI} is the eleven-character image of
     *         {@code acctId}; on a re-entry, the request as it arrived
     * @throws IllegalArgumentException if {@code acctId} is wider than {@code ACCTSIDI}
     */
    AccountUpdateRequest bind(String acctId, AccountUpdateRequest request) {
        if (acctId.length() > AccountUpdateRequest.ACCTSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(ACCTSID_MEMBER,
                    "ACCTSIDI PIC X(" + AccountUpdateRequest.ACCTSID_LENGTH
                            + ") in app/cpy-bms/COACTUP.CPY",
                    AccountUpdateRequest.ACCTSID_LENGTH, acctId.length());
        }
        AccountUpdateRequest received = request == null ? AccountUpdateRequest.initial() : request;
        if (isReentry(received)) {
            return received;
        }
        return received.withValue(AccountUpdateRequest.ScreenField.ACCTSID,
                PIC_X_CODEC.movePicX(acctId, AccountUpdateRequest.ACCTSID_LENGTH));
    }

    /**
     * Whether this turn is one on which the source performs {@code 1100-RECEIVE-MAP} and reads the
     * operator's own typed key.
     *
     * <p>{@code app/cbl/COACTUPC.cbl:880-893} treats an absent communication area as no conversation -
     * it initialises both areas and sets {@code CDEMO-PGM-ENTER} - and the {@code EVALUATE} at
     * {@code :925-1005} reaches {@code 1000-PROCESS-INPUTS} only from an arm that requires
     * {@code CDEMO-PGM-REENTER}. A payload carrying no area, or one whose context is not
     * {@value NavigationContext#PGM_CONTEXT_REENTER}, is therefore a turn on which nothing was received.
     *
     * @param received the payload as it arrived
     * @return {@code true} when the source would read the map's own key on this turn
     */
    private static boolean isReentry(AccountUpdateRequest received) {
        return received.isReenter();
    }

    /**
     * Resolves {@code EIBCALEN} - the length of the area that arrived, tested for zero and nothing else.
     *
     * <h4>Zero versus non-zero is the whole of what the source asks</h4>
     * {@code app/cbl/COACTUPC.cbl:880} tests {@code EIBCALEN} against zero and never against any other
     * value: zero means the transaction was typed at a clear screen and there is no conversation, and
     * anything else means an area arrived and its first 160 bytes are {@code CARDDEMO-COMMAREA}.
     * So this method preserves the length that arrived and branches on zero versus non-zero, exactly as
     * the source does.
     *
     * <h4>Why no set of accepted lengths is enumerated</h4>
     * Because the real lengths are several and all of them are legitimate. COMEN01C transfers control passing
     * {@code CARDDEMO-COMMAREA} alone, and
     * this program's own {@code COMMON-RETURN} passes {@code WS-COMMAREA}, declared {@code PIC X(2000)} - so a
     * client continuing the pseudo-conversation faithfully reports 2000 while one
     * arriving from the menu reports 160. An earlier revision accepted only zero and one
     * synthetic length and answered {@code 400} to both of those real values, which refused the very
     * payload this API's own response tells a client to send back. Any non-negative length is therefore
     * accepted and carried through unchanged; only the zero test is acted on, because only the zero test
     * exists in the source.
     *
     * <p>A stated value must still agree with what actually arrived: {@code EIBCALEN} describes the area
     * CICS passed, so a payload carrying a communication area cannot report zero and a payload carrying
     * none cannot report a length. That is not an invented rule but the one relation the parameter has to
     * the body, and {@code :880} branches on it.
     *
     * @param eibcalen the stated value, or {@code null} to derive it from the carrier
     * @param request  the bound request, whose commarea presence is the carrier
     * @return zero when no communication area arrived, otherwise the length that arrived
     * @throws IllegalArgumentException if the stated value is negative, or contradicts the carrier
     */
    static int resolveEibcalen(Integer eibcalen, AccountUpdateRequest request) {
        boolean carried = request.hasNavigationContext();
        if (eibcalen == null) {
            return carried ? PASSED_COMMAREA_LENGTH : NO_COMMAREA_LENGTH;
        }
        int stated = eibcalen;
        if (stated < NO_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", and EIBCALEN is the length of the area CICS passed, which cannot be negative.");
        }
        if ((stated == NO_COMMAREA_LENGTH) == carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried ? "a" : "no")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COACTUPC.cbl:880 uses it to decide whether the conversation's "
                    + "state survives the turn.");
        }
        return stated;
    }

    /**
     * Resolves {@code EIBAID}.
     *
     * <p>An absent parameter is {@link CicsAid#DFHENTER}, the key a terminal sends when a screen is
     * submitted with no function key - and the key {@code :898-901} coerces every unsupported key to.
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
    // ACUP-OLD-ACCT-DATA and ACUP-NEW-ACCT-DATA - app/cbl/COACTUPC.cbl:669-708 and :758-797.
    //
    // One mutable class for both groups, because the two are declared with identical shapes and every
    // paragraph that touches one touches the other the same way. Each field is one COBOL item at its
    // declared width; the -N REDEFINES views are accessor pairs over the same backing characters
    // rather than separate fields, which is what makes them a redefinition rather than a copy.
    // =================================================================================================

    /**
     * The account half of a detail group: eleven characters items, five of which have a
     * {@code PIC S9(10)V99} redefinition and three of which have a year, month and day redefinition.
     */
    static final class AcctDataArea {

        /** {@code ACUP-xxx-ACCT-ID-X PIC X(11)}, redefined as {@code PIC 9(11)}. */
        String acctIdX;

        /** {@code ACUP-xxx-ACTIVE-STATUS PIC X(01)}. */
        String activeStatus;

        /** {@code ACUP-xxx-CURR-BAL PIC X(12)}, redefined as {@code PIC S9(10)V99}. */
        String currBal;

        /** {@code ACUP-xxx-CREDIT-LIMIT PIC X(12)}, redefined as {@code PIC S9(10)V99}. */
        String creditLimit;

        /** {@code ACUP-xxx-CASH-CREDIT-LIMIT PIC X(12)}, redefined as {@code PIC S9(10)V99}. */
        String cashCreditLimit;

        /** {@code ACUP-xxx-OPEN-DATE PIC X(08)}, redefined as year, month and day. */
        String openDate;

        /** {@code ACUP-xxx-EXPIRAION-DATE PIC X(08)} - the misspelling is the copybook's, and is kept. */
        String expiraionDate;

        /** {@code ACUP-xxx-REISSUE-DATE PIC X(08)}, redefined as year, month and day. */
        String reissueDate;

        /** {@code ACUP-xxx-CURR-CYC-CREDIT PIC X(12)}, redefined as {@code PIC S9(10)V99}. */
        String currCycCredit;

        /** {@code ACUP-xxx-CURR-CYC-DEBIT PIC X(12)}, redefined as {@code PIC S9(10)V99}. */
        String currCycDebit;

        /** {@code ACUP-xxx-GROUP-ID PIC X(10)}. */
        String groupId;

        /** Creates the group in the state {@code INITIALIZE} leaves: every alphanumeric item spaces. */
        AcctDataArea() {
            initialize();
        }

        /**
         * {@code INITIALIZE ACUP-xxx-DETAILS}.
         *
         * <p>Spaces, not zeros and not {@code LOW-VALUES}: every item in the group is declared
         * {@code PIC X}, the numeric views are {@code REDEFINES} which {@code INITIALIZE} ignores, and
         * {@code INITIALIZE} sets an alphanumeric item to spaces.
         */
        void initialize() {
            acctIdX = spaces(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
            activeStatus = spaces(AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);
            currBal = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            creditLimit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            cashCreditLimit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            openDate = spaces(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            expiraionDate = spaces(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            reissueDate = spaces(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            currCycCredit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            currCycDebit = spaces(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            groupId = spaces(AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
        }

        /**
         * {@code MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA} - {@code app/cbl/COACTUPC.cbl:1444}.
         *
         * <p>A group {@code MOVE} of a figurative constant fills every byte, which for this group is a
         * different state from {@code INITIALIZE}'s spaces. {@code 1200-EDIT-MAP-INPUTS} does it to the
         * old account data alone - not to the customer half - immediately after
         * {@code 1210-EDIT-ACCOUNT}.
         */
        void moveLowValues() {
            acctIdX = lowValues(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
            activeStatus = lowValues(AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);
            currBal = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            creditLimit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            cashCreditLimit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            openDate = lowValues(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            expiraionDate = lowValues(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            reissueDate = lowValues(AccountUpdateRequest.AcctSnapshot.DATE_LENGTH);
            currCycCredit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            currCycDebit = lowValues(AccountUpdateRequest.AcctSnapshot.MONEY_LENGTH);
            groupId = lowValues(AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);
        }

        /**
         * The group as the wire form of {@code WS-THIS-PROGCOMMAREA} carries it.
         *
         * @return the eleven items, never {@code null}
         */
        AccountUpdateRequest.AcctSnapshot toSnapshot() {
            return new AccountUpdateRequest.AcctSnapshot(acctIdX, activeStatus, currBal, creditLimit,
                    cashCreditLimit, openDate, expiraionDate, reissueDate, currCycCredit, currCycDebit,
                    groupId);
        }

        /**
         * Restores the group from the wire form, which is what {@code :888-892} does when a
         * communication area arrived.
         *
         * @param snapshot the carried group; must not be {@code null}
         */
        void fromSnapshot(AccountUpdateRequest.AcctSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "A carried account group is required");
            acctIdX = snapshot.acctIdX();
            activeStatus = snapshot.activeStatus();
            currBal = snapshot.currBal();
            creditLimit = snapshot.creditLimit();
            cashCreditLimit = snapshot.cashCreditLimit();
            openDate = snapshot.openDate();
            expiraionDate = snapshot.expiraionDate();
            reissueDate = snapshot.reissueDate();
            currCycCredit = snapshot.currCycCredit();
            currCycDebit = snapshot.currCycDebit();
            groupId = snapshot.groupId();
        }

        /** @return {@code ACUP-xxx-ACCT-ID PIC 9(11)}, the numeric view of the same eleven characters. */
        long acctIdN() {
            return toSnapshot().acctId();
        }

        /**
         * Projects the group into the service's decoded view of it.
         *
         * <p>The service owns {@code 9600-WRITE-PROCESSING} and {@code 9700-CHECK-CHANGE-IN-REC}, and its
         * model of a detail group is decoded - {@code long} for the identifier and {@link BigDecimal} at
         * scale 2 for the five monetary items - because those are the forms the {@code MOVE}s into
         * {@code ACCOUNT-RECORD} and the field-by-field comparison need. Decoding here is safe because
         * that path is reached only from {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, which requires every edit
         * to have passed, which requires every monetary span to have been computed.
         *
         * @return the group as {@link AccountUpdateService.AccountData}
         */
        AccountUpdateService.AccountData toAccountData() {
            AccountUpdateRequest.AcctSnapshot snapshot = toSnapshot();
            return new AccountUpdateService.AccountData(snapshot.acctId(),
                    activeStatus,
                    snapshot.currBalN(),
                    snapshot.creditLimitN(),
                    snapshot.cashCreditLimitN(),
                    openYear(), openMon(), openDay(),
                    expYear(), expMon(), expDay(),
                    reissueYear(), reissueMon(), reissueDay(),
                    snapshot.currCycCreditN(),
                    snapshot.currCycDebitN(),
                    groupId);
        }

        /**
         * Writes through the {@code PIC 9(11)} view of {@code ACCT-ID}, which is the receiver
         * {@code MOVE ZEROES TO ACUP-NEW-ACCT-ID} at {@code app/cbl/COACTUPC.cbl:1795} names.
         *
         * <p>A numeric receiver is zero filled on the left, so a store of zero leaves eleven
         * {@code '0'} characters in the backing span rather than eleven spaces - a distinction
         * {@code 1205-COMPARE-OLD-NEW} can see, because it compares the character view.
         *
         * @param value the value to store
         */
        void setAcctIdN(long value) {
            acctIdX = PIC_X_CODEC.movePic9(Long.toString(value),
                    AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
        }

        /** @return {@code ACUP-xxx-CURR-BAL-N PIC S9(10)V99}, at scale 2. */
        BigDecimal currBalN() {
            return toSnapshot().currBalN();
        }

        /** @return {@code ACUP-xxx-CREDIT-LIMIT-N PIC S9(10)V99}, at scale 2. */
        BigDecimal creditLimitN() {
            return toSnapshot().creditLimitN();
        }

        /** @return {@code ACUP-xxx-CASH-CREDIT-LIMIT-N PIC S9(10)V99}, at scale 2. */
        BigDecimal cashCreditLimitN() {
            return toSnapshot().cashCreditLimitN();
        }

        /** @return {@code ACUP-xxx-CURR-CYC-CREDIT-N PIC S9(10)V99}, at scale 2. */
        BigDecimal currCycCreditN() {
            return toSnapshot().currCycCreditN();
        }

        /** @return {@code ACUP-xxx-CURR-CYC-DEBIT-N PIC S9(10)V99}, at scale 2. */
        BigDecimal currCycDebitN() {
            return toSnapshot().currCycDebitN();
        }

        /**
         * Writes through the {@code PIC S9(10)V99} view of {@code CURR-BAL}.
         *
         * @param value the value to store; the twelve backing characters become its zoned image
         */
        void setCurrBalN(BigDecimal value) {
            currBal = monetaryImage(value);
        }

        /**
         * Writes through the {@code PIC S9(10)V99} view of {@code CREDIT-LIMIT}.
         *
         * @param value the value to store
         */
        void setCreditLimitN(BigDecimal value) {
            creditLimit = monetaryImage(value);
        }

        /**
         * Writes through the {@code PIC S9(10)V99} view of {@code CASH-CREDIT-LIMIT}.
         *
         * @param value the value to store
         */
        void setCashCreditLimitN(BigDecimal value) {
            cashCreditLimit = monetaryImage(value);
        }

        /**
         * Writes through the {@code PIC S9(10)V99} view of {@code CURR-CYC-CREDIT}.
         *
         * @param value the value to store
         */
        void setCurrCycCreditN(BigDecimal value) {
            currCycCredit = monetaryImage(value);
        }

        /**
         * Writes through the {@code PIC S9(10)V99} view of {@code CURR-CYC-DEBIT}.
         *
         * @param value the value to store
         */
        void setCurrCycDebitN(BigDecimal value) {
            currCycDebit = monetaryImage(value);
        }

        /** @return {@code ACUP-xxx-OPEN-YEAR PIC X(4)}, the first four of the eight. */
        String openYear() {
            return yearOf(openDate);
        }

        /** @return {@code ACUP-xxx-OPEN-MON PIC X(2)}. */
        String openMon() {
            return monthOf(openDate);
        }

        /** @return {@code ACUP-xxx-OPEN-DAY PIC X(2)}. */
        String openDay() {
            return dayOf(openDate);
        }

        /** @return {@code ACUP-xxx-EXP-YEAR PIC X(4)}. */
        String expYear() {
            return yearOf(expiraionDate);
        }

        /** @return {@code ACUP-xxx-EXP-MON PIC X(2)}. */
        String expMon() {
            return monthOf(expiraionDate);
        }

        /** @return {@code ACUP-xxx-EXP-DAY PIC X(2)}. */
        String expDay() {
            return dayOf(expiraionDate);
        }

        /** @return {@code ACUP-xxx-REISSUE-YEAR PIC X(4)}. */
        String reissueYear() {
            return yearOf(reissueDate);
        }

        /** @return {@code ACUP-xxx-REISSUE-MON PIC X(2)}. */
        String reissueMon() {
            return monthOf(reissueDate);
        }

        /** @return {@code ACUP-xxx-REISSUE-DAY PIC X(2)}. */
        String reissueDay() {
            return dayOf(reissueDate);
        }

        /**
         * Writes through the year part of {@code OPEN-DATE}.
         *
         * @param value four characters
         */
        void setOpenYear(String value) {
            openDate = withYear(openDate, value);
        }

        /**
         * Writes through the month part of {@code OPEN-DATE}.
         *
         * @param value two characters
         */
        void setOpenMon(String value) {
            openDate = withMonth(openDate, value);
        }

        /**
         * Writes through the day part of {@code OPEN-DATE}.
         *
         * @param value two characters
         */
        void setOpenDay(String value) {
            openDate = withDay(openDate, value);
        }

        /**
         * Writes through the year part of {@code EXPIRAION-DATE}.
         *
         * @param value four characters
         */
        void setExpYear(String value) {
            expiraionDate = withYear(expiraionDate, value);
        }

        /**
         * Writes through the month part of {@code EXPIRAION-DATE}.
         *
         * @param value two characters
         */
        void setExpMon(String value) {
            expiraionDate = withMonth(expiraionDate, value);
        }

        /**
         * Writes through the day part of {@code EXPIRAION-DATE}.
         *
         * @param value two characters
         */
        void setExpDay(String value) {
            expiraionDate = withDay(expiraionDate, value);
        }

        /**
         * Writes through the year part of {@code REISSUE-DATE}.
         *
         * @param value four characters
         */
        void setReissueYear(String value) {
            reissueDate = withYear(reissueDate, value);
        }

        /**
         * Writes through the month part of {@code REISSUE-DATE}.
         *
         * @param value two characters
         */
        void setReissueMon(String value) {
            reissueDate = withMonth(reissueDate, value);
        }

        /**
         * Writes through the day part of {@code REISSUE-DATE}.
         *
         * @param value two characters
         */
        void setReissueDay(String value) {
            reissueDate = withDay(reissueDate, value);
        }
    }

    // =================================================================================================
    // ACUP-OLD-CUST-DATA and ACUP-NEW-CUST-DATA - app/cbl/COACTUPC.cbl:709-757 and :798-855.
    //
    // Almost the same shape twice, with one difference worth naming: the old group declares
    // ACUP-OLD-CUST-SSN-X PIC X(09) as one item and the new group declares ACUP-NEW-CUST-SSN-X as three
    // subordinate items of 3, 2 and 4 characters. Same nine bytes either way, so one class carries both
    // and exposes the whole and the three parts as views over it.
    // =================================================================================================

    /** The customer half of a detail group: eighteen character items over 330 bytes. */
    static final class CustDataArea {

        /** {@code ACUP-xxx-CUST-ID-X PIC X(09)}, redefined as {@code PIC 9(09)}. */
        String custIdX;

        /** {@code ACUP-xxx-CUST-FIRST-NAME PIC X(25)}. */
        String firstName;

        /** {@code ACUP-xxx-CUST-MIDDLE-NAME PIC X(25)}. */
        String middleName;

        /** {@code ACUP-xxx-CUST-LAST-NAME PIC X(25)}. */
        String lastName;

        /** {@code ACUP-xxx-CUST-ADDR-LINE-1 PIC X(50)}. */
        String addrLine1;

        /** {@code ACUP-xxx-CUST-ADDR-LINE-2 PIC X(50)} - received and painted, but never edited. */
        String addrLine2;

        /** {@code ACUP-xxx-CUST-ADDR-LINE-3 PIC X(50)}, which the screen calls the city. */
        String addrLine3;

        /** {@code ACUP-xxx-CUST-ADDR-STATE-CD PIC X(02)}. */
        String addrStateCd;

        /** {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD PIC X(03)}. */
        String addrCountryCd;

        /** {@code ACUP-xxx-CUST-ADDR-ZIP PIC X(10)}; the screen field behind it is only five wide. */
        String addrZip;

        /** {@code ACUP-xxx-CUST-PHONE-NUM-1 PIC X(15)}, redefined as {@code (999)999-9999}. */
        String phoneNum1;

        /** {@code ACUP-xxx-CUST-PHONE-NUM-2 PIC X(15)}, redefined the same way. */
        String phoneNum2;

        /** {@code ACUP-xxx-CUST-SSN-X PIC X(09)}, redefined as {@code PIC 9(09)} and as three parts. */
        String ssnX;

        /** {@code ACUP-xxx-CUST-GOVT-ISSUED-ID PIC X(20)}. */
        String govtIssuedId;

        /** {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD PIC X(08)} - eight characters, so no separators. */
        String dobYyyyMmDd;

        /** {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID PIC X(10)}. */
        String eftAccountId;

        /** {@code ACUP-xxx-CUST-PRI-HOLDER-IND PIC X(01)}. */
        String priHolderInd;

        /** {@code ACUP-xxx-CUST-FICO-SCORE-X PIC X(03)}, redefined as {@code PIC 9(03)}. */
        String ficoScoreX;

        /** Creates the group in the state {@code INITIALIZE} leaves: every item spaces. */
        CustDataArea() {
            initialize();
        }

        /** {@code INITIALIZE ACUP-xxx-DETAILS}, the customer half: every alphanumeric item spaces. */
        void initialize() {
            custIdX = spaces(AccountUpdateRequest.CustSnapshot.CUST_ID_LENGTH);
            firstName = spaces(AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            middleName = spaces(AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            lastName = spaces(AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
            addrLine1 = spaces(AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            addrLine2 = spaces(AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            addrLine3 = spaces(AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
            addrStateCd = spaces(AccountUpdateRequest.CustSnapshot.ADDR_STATE_CD_LENGTH);
            addrCountryCd = spaces(AccountUpdateRequest.CustSnapshot.ADDR_COUNTRY_CD_LENGTH);
            addrZip = spaces(AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH);
            phoneNum1 = spaces(AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
            phoneNum2 = spaces(AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
            ssnX = spaces(AccountUpdateRequest.CustSnapshot.SSN_LENGTH);
            govtIssuedId = spaces(AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);
            dobYyyyMmDd = spaces(AccountUpdateRequest.CustSnapshot.DOB_LENGTH);
            eftAccountId = spaces(AccountUpdateRequest.CustSnapshot.EFT_ACCOUNT_ID_LENGTH);
            priHolderInd = spaces(AccountUpdateRequest.CustSnapshot.PRI_HOLDER_IND_LENGTH);
            ficoScoreX = spaces(AccountUpdateRequest.CustSnapshot.FICO_SCORE_LENGTH);
        }

        /**
         * The group as the wire form of {@code WS-THIS-PROGCOMMAREA} carries it.
         *
         * @return the eighteen items, never {@code null}
         */
        AccountUpdateRequest.CustSnapshot toSnapshot() {
            return new AccountUpdateRequest.CustSnapshot(custIdX, firstName, middleName, lastName,
                    addrLine1, addrLine2, addrLine3, addrStateCd, addrCountryCd, addrZip, phoneNum1,
                    phoneNum2, ssnX, govtIssuedId, dobYyyyMmDd, eftAccountId, priHolderInd, ficoScoreX);
        }

        /**
         * Restores the group from the wire form.
         *
         * @param snapshot the carried group; must not be {@code null}
         */
        void fromSnapshot(AccountUpdateRequest.CustSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "A carried customer group is required");
            custIdX = snapshot.custIdX();
            firstName = snapshot.firstName();
            middleName = snapshot.middleName();
            lastName = snapshot.lastName();
            addrLine1 = snapshot.addrLine1();
            addrLine2 = snapshot.addrLine2();
            addrLine3 = snapshot.addrLine3();
            addrStateCd = snapshot.addrStateCd();
            addrCountryCd = snapshot.addrCountryCd();
            addrZip = snapshot.addrZip();
            phoneNum1 = snapshot.phoneNum1();
            phoneNum2 = snapshot.phoneNum2();
            ssnX = snapshot.ssnX();
            govtIssuedId = snapshot.govtIssuedId();
            dobYyyyMmDd = snapshot.dobYyyyMmDd();
            eftAccountId = snapshot.eftAccountId();
            priHolderInd = snapshot.priHolderInd();
            ficoScoreX = snapshot.ficoScoreX();
        }

        /** @return {@code ACUP-xxx-CUST-ID PIC 9(09)}, the numeric view of the nine characters. */
        int custIdN() {
            return (int) toSnapshot().custId();
        }

        /** @return {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} - {@code SSN-X(1:3)}. */
        String ssn1() {
            return slice(ssnX, 0, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH);
        }

        /** @return {@code ACUP-NEW-CUST-SSN-2 PIC X(02)} - {@code SSN-X(4:2)}. */
        String ssn2() {
            return slice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH);
        }

        /** @return {@code ACUP-NEW-CUST-SSN-3 PIC X(04)} - {@code SSN-X(6:4)}. */
        String ssn3() {
            return slice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH
                            + AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH);
        }

        /**
         * Writes through {@code ACUP-NEW-CUST-SSN-1}.
         *
         * @param value three characters
         */
        void setSsn1(String value) {
            ssnX = splice(ssnX, 0, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH, value);
        }

        /**
         * Writes through {@code ACUP-NEW-CUST-SSN-2}.
         *
         * @param value two characters
         */
        void setSsn2(String value) {
            ssnX = splice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH, value);
        }

        /**
         * Writes through {@code ACUP-NEW-CUST-SSN-3}.
         *
         * @param value four characters
         */
        void setSsn3(String value) {
            ssnX = splice(ssnX, AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH
                            + AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH,
                    AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH, value);
        }

        /** @return {@code ACUP-xxx-CUST-PHONE-NUM-1A PIC X(3)} - the area code, at {@code (2:3)}. */
        String phoneNum1A() {
            return phoneArea(phoneNum1);
        }

        /** @return {@code ACUP-xxx-CUST-PHONE-NUM-1B PIC X(3)} - the prefix, at {@code (6:3)}. */
        String phoneNum1B() {
            return phonePrefix(phoneNum1);
        }

        /** @return {@code ACUP-xxx-CUST-PHONE-NUM-1C PIC X(4)} - the line number, at {@code (10:4)}. */
        String phoneNum1C() {
            return phoneLine(phoneNum1);
        }

        /** @return {@code ACUP-xxx-CUST-PHONE-NUM-2A PIC X(3)}. */
        String phoneNum2A() {
            return phoneArea(phoneNum2);
        }

        /** @return {@code ACUP-xxx-CUST-PHONE-NUM-2B PIC X(3)}. */
        String phoneNum2B() {
            return phonePrefix(phoneNum2);
        }

        /** @return {@code ACUP-xxx-CUST-PHONE-NUM-2C PIC X(4)}. */
        String phoneNum2C() {
            return phoneLine(phoneNum2);
        }

        /**
         * Writes through the area-code part of the first telephone number.
         *
         * @param value three characters
         */
        void setPhoneNum1A(String value) {
            phoneNum1 = withPhoneArea(phoneNum1, value);
        }

        /**
         * Writes through the prefix part of the first telephone number.
         *
         * @param value three characters
         */
        void setPhoneNum1B(String value) {
            phoneNum1 = withPhonePrefix(phoneNum1, value);
        }

        /**
         * Writes through the line-number part of the first telephone number.
         *
         * @param value four characters
         */
        void setPhoneNum1C(String value) {
            phoneNum1 = withPhoneLine(phoneNum1, value);
        }

        /**
         * Writes through the area-code part of the second telephone number.
         *
         * @param value three characters
         */
        void setPhoneNum2A(String value) {
            phoneNum2 = withPhoneArea(phoneNum2, value);
        }

        /**
         * Writes through the prefix part of the second telephone number.
         *
         * @param value three characters
         */
        void setPhoneNum2B(String value) {
            phoneNum2 = withPhonePrefix(phoneNum2, value);
        }

        /**
         * Writes through the line-number part of the second telephone number.
         *
         * @param value four characters
         */
        void setPhoneNum2C(String value) {
            phoneNum2 = withPhoneLine(phoneNum2, value);
        }

        /**
         * Projects the group into the service's decoded view of it, for the same reason
         * {@link AcctDataArea#toAccountData()} does.
         *
         * <p>{@code CUST-ID} is {@code PIC 9(09)} and the SSN {@code PIC 9(09)}, both of which fit an
         * {@code int}; the narrowing casts are safe by construction and are written explicitly rather
         * than relying on the reader to check the picture clauses.
         *
         * @return the group as {@link AccountUpdateService.CustomerData}
         */
        AccountUpdateService.CustomerData toCustomerData() {
            AccountUpdateRequest.CustSnapshot snapshot = toSnapshot();
            return new AccountUpdateService.CustomerData((int) snapshot.custId(),
                    firstName, middleName, lastName,
                    addrLine1, addrLine2, addrLine3,
                    addrStateCd, addrCountryCd, addrZip,
                    phoneNum1, phoneNum2,
                    (int) snapshot.ssn(),
                    govtIssuedId,
                    dobYear(), dobMon(), dobDay(),
                    eftAccountId, priHolderInd,
                    snapshot.ficoScore());
        }

        /**
         * {@code ACUP-xxx-CUST-FICO-SCORE REDEFINES ACUP-xxx-CUST-FICO-SCORE-X PIC 9(03)} -
         * {@code app/cbl/COACTUPC.cbl:845-849}, the numeric view {@code 88 FICO-RANGE-IS-VALID VALUES
         * 300 THROUGH 850} hangs off.
         *
         * @return the score as a number; zero for a span that holds spaces or {@code LOW-VALUES}
         */
        int ficoScoreN() {
            return toSnapshot().ficoScore();
        }

        /** @return {@code ACUP-xxx-CUST-DOB-YEAR PIC X(4)}, the first four of the eight. */
        String dobYear() {
            return yearOf(dobYyyyMmDd);
        }

        /** @return {@code ACUP-xxx-CUST-DOB-MON PIC X(2)}. */
        String dobMon() {
            return monthOf(dobYyyyMmDd);
        }

        /** @return {@code ACUP-xxx-CUST-DOB-DAY PIC X(2)}. */
        String dobDay() {
            return dayOf(dobYyyyMmDd);
        }

        /**
         * Writes through the year part of the date of birth.
         *
         * @param value four characters
         */
        void setDobYear(String value) {
            dobYyyyMmDd = withYear(dobYyyyMmDd, value);
        }

        /**
         * Writes through the month part of the date of birth.
         *
         * @param value two characters
         */
        void setDobMon(String value) {
            dobYyyyMmDd = withMonth(dobYyyyMmDd, value);
        }

        /**
         * Writes through the day part of the date of birth.
         *
         * @param value two characters
         */
        void setDobDay(String value) {
            dobYyyyMmDd = withDay(dobYyyyMmDd, value);
        }

        /**
         * {@code ACUP-NEW-CUST-FICO-SCORE PIC 9(03)} and its {@code FICO-RANGE-IS-VALID} condition
         * ({@code app/cbl/COACTUPC.cbl:846-849}).
         *
         * @return whether the numeric view is between 300 and 850 inclusive; a non-numeric character
         *         view cannot satisfy a numeric range condition and reports {@code false}
         */
        boolean ficoRangeIsValid() {
            return toSnapshot().ficoRangeIsValid();
        }
    }

    // =================================================================================================
    // Reference-modification and figurative-constant primitives. Each is one COBOL construct, named
    // after it, so a reviewer can check a slice against the source's (offset:length) without reading
    // the paragraph around it. COBOL offsets are one-based; the conversion happens once, here.
    // =================================================================================================

    /**
     * The figurative constant {@code SPACES} at a declared width.
     *
     * @param length the receiving item's width
     * @return exactly {@code length} spaces
     */
    static String spaces(int length) {
        return AccountUpdateResponse.spaces(length);
    }

    /**
     * The figurative constant {@code LOW-VALUES} at a declared width.
     *
     * @param length the receiving item's width
     * @return exactly {@code length} {@code LOW-VALUE} characters
     */
    static String lowValues(int length) {
        return AccountUpdateResponse.lowValues(length);
    }

    /**
     * A reference modification, with the one-based to zero-based conversion done for the caller.
     *
     * @param value       the sending item, taken at whatever width it holds
     * @param zeroOffset  the zero-based start, which is the COBOL offset less one
     * @param partLength  the slice width
     * @return exactly {@code partLength} characters, space padded if {@code value} is short
     */
    static String slice(String value, int zeroOffset, int partLength) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, zeroOffset + partLength);
        return image.substring(zeroOffset, zeroOffset + partLength);
    }

    /**
     * Writes a part back into the span it was sliced from, which is what a {@code REDEFINES} write does:
     * the whole and the part share one set of bytes.
     *
     * @param span        the backing item
     * @param zeroOffset  the zero-based start of the part
     * @param partLength  the part's declared width
     * @param value       the value to store, moved to {@code partLength} by the {@code PIC X} rule
     * @return the span with those {@code partLength} characters replaced
     */
    static String splice(String span, int zeroOffset, int partLength, String value) {
        int spanWidth = Math.max(span == null ? 0 : span.length(), zeroOffset + partLength);
        String image = PIC_X_CODEC.movePicX(span == null ? "" : span, spanWidth);
        String part = PIC_X_CODEC.movePicX(value == null ? "" : value, partLength);
        return image.substring(0, zeroOffset) + part + image.substring(zeroOffset + partLength);
    }

    /**
     * {@code xxx-DATE(1:4)} - the year of an eight-character {@code CCYYMMDD} item.
     *
     * @param date the eight-character item
     * @return four characters
     */
    static String yearOf(String date) {
        return slice(date, 0, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH);
    }

    /**
     * {@code xxx-DATE(5:2)} - the month.
     *
     * @param date the eight-character item
     * @return two characters
     */
    static String monthOf(String date) {
        return slice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH);
    }

    /**
     * {@code xxx-DATE(7:2)} - the day.
     *
     * @param date the eight-character item
     * @return two characters
     */
    static String dayOf(String date) {
        return slice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH
                        + AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH);
    }

    /**
     * Writes the year part of an eight-character date item.
     *
     * @param date  the item
     * @param value four characters
     * @return the item with its year replaced
     */
    static String withYear(String date, String value) {
        return splice(date, 0, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH, value);
    }

    /**
     * Writes the month part of an eight-character date item.
     *
     * @param date  the item
     * @param value two characters
     * @return the item with its month replaced
     */
    static String withMonth(String date, String value) {
        return splice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH, value);
    }

    /**
     * Writes the day part of an eight-character date item.
     *
     * @param date  the item
     * @param value two characters
     * @return the item with its day replaced
     */
    static String withDay(String date, String value) {
        return splice(date, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH
                        + AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH, value);
    }

    /**
     * {@code CUST-PHONE-NUM-n(2:3)} - the area code inside {@code (999)999-9999}.
     *
     * @param phone the fifteen-character item
     * @return three characters
     */
    static String phoneArea(String phone) {
        return slice(phone, AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH);
    }

    /**
     * {@code CUST-PHONE-NUM-n(6:3)} - the prefix.
     *
     * @param phone the fifteen-character item
     * @return three characters
     */
    static String phonePrefix(String phone) {
        return slice(phone, AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH);
    }

    /**
     * {@code CUST-PHONE-NUM-n(10:4)} - the line number.
     *
     * @param phone the fifteen-character item
     * @return four characters
     */
    static String phoneLine(String phone) {
        return slice(phone, AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH);
    }

    /**
     * Writes the area code back into a telephone span.
     *
     * @param phone the item
     * @param value three characters
     * @return the item with its area code replaced
     */
    static String withPhoneArea(String phone, String value) {
        return splice(atPhoneWidth(phone),
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH, value);
    }

    /**
     * Writes the prefix back into a telephone span.
     *
     * @param phone the item
     * @param value three characters
     * @return the item with its prefix replaced
     */
    static String withPhonePrefix(String phone, String value) {
        return splice(atPhoneWidth(phone),
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH, value);
    }

    /**
     * Writes the line number back into a telephone span.
     *
     * @param phone the item
     * @param value four characters
     * @return the item with its line number replaced
     */
    static String withPhoneLine(String phone, String value) {
        return splice(atPhoneWidth(phone),
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH, value);
    }

    /**
     * A telephone span at its declared fifteen characters, so a splice never shortens it.
     *
     * @param phone the item, possibly short or {@code null}
     * @return exactly {@value AccountUpdateRequest.CustSnapshot#PHONE_NUM_LENGTH} characters
     */
    private static String atPhoneWidth(String phone) {
        return PIC_X_CODEC.movePicX(phone == null ? "" : phone,
                AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
    }

    /**
     * The zoned image a {@code PIC S9(10)V99} span holds after a value is stored into it.
     *
     * <p>Twelve characters, the sign carried as an overpunch in the last of them, and the value stored
     * at scale exactly 2 with {@code RoundingMode.DOWN} - which is what COBOL does, because the keyword
     * {@code ROUNDED} appears zero times in all 28 programs and a store therefore truncates. A
     * {@code null} is the freshly initialised span, which reads as zero.
     *
     * @param value the value to store
     * @return exactly {@value AccountUpdateRequest.AcctSnapshot#MONEY_LENGTH} characters
     */
    static String monetaryImage(BigDecimal value) {
        return PIC_X_CODEC.encodeSignedScaled(value == null ? BigDecimal.ZERO : value,
                MONETARY_INTEGER_DIGITS, MONETARY_FRACTION_DIGITS);
    }

    /**
     * {@code WS-EDIT-CURRENCY-9-2-F PIC +ZZZ,ZZZ,ZZZ.99} - {@code app/cbl/COACTUPC.cbl:373}.
     *
     * <p>The mask that {@code 3202-SHOW-ORIGINAL-VALUES} and {@code 3203-SHOW-UPDATED-VALUES} render
     * all five monetary fields through, and the one {@code PICTURE} in this program that this class has
     * to implement rather than delegate. Fifteen characters, in four parts:
     *
     * <ol>
     *   <li>a <strong>fixed</strong> sign position - a single leading {@code +} is an insertion
     *       character, so it prints {@code '+'} for a positive or zero value and {@code '-'} for a
     *       negative one, and never moves;</li>
     *   <li>{@code ZZZ,ZZZ,ZZZ} - <strong>nine</strong> digit positions with zero suppression. The
     *       sending item has ten integer digits, so a {@code MOVE} into this mask truncates the
     *       high-order one, keeping the low-order nine as numeric alignment requires;</li>
     *   <li>{@code .} - an insertion character that suppression never reaches, because suppression
     *       stops at the decimal point;</li>
     *   <li>{@code 99} - two digit positions that are never suppressed, which is why a zero value
     *       renders as eleven spaces followed by {@code .00} rather than as fifteen spaces.</li>
     * </ol>
     *
     * <p>The suppression rule is applied by walking the template left to right: a {@code Z} whose digit
     * is zero while suppression is still in effect becomes a space, the first non-zero digit ends
     * suppression, and a comma <em>within or to the left of</em> the suppressed positions is itself
     * suppressed - which is why {@code 1234.56} renders as {@code "+      1,234.56"} with one comma and
     * not two.
     *
     * @param value the sending {@code PIC S9(10)V99} item; {@code null} is read as zero
     * @return exactly {@value #WS_EDIT_CURRENCY_LENGTH} characters
     */
    static String editCurrency92(BigDecimal value) {
        BigDecimal stored = storeMonetary(value);
        boolean negative = stored.signum() < 0;

        // The unscaled digits of the magnitude, twelve of them: ten integer and two fraction. Rendered
        // through the PIC 9 rule so that a value too wide for the sending item loses its high-order
        // digits rather than overflowing, which is what a COBOL numeric MOVE does.
        String allDigits = PIC_X_CODEC.movePic9(stored.abs().unscaledValue().toString(),
                MONETARY_INTEGER_DIGITS + MONETARY_FRACTION_DIGITS);
        String integerDigits = allDigits.substring(0, MONETARY_INTEGER_DIGITS);
        String fractionDigits = allDigits.substring(MONETARY_INTEGER_DIGITS);

        // Ten integer digits into nine positions: keep the low-order nine.
        String shown = integerDigits.substring(MONETARY_INTEGER_DIGITS - CURRENCY_INTEGER_DIGITS);

        StringBuilder rendered = new StringBuilder(WS_EDIT_CURRENCY_LENGTH);
        rendered.append(negative ? MINUS_SIGN : PLUS_SIGN);
        boolean suppressing = true;
        int digitIndex = 0;
        for (int index = 0; index < CURRENCY_INTEGER_TEMPLATE.length(); index++) {
            char position = CURRENCY_INTEGER_TEMPLATE.charAt(index);
            if (position == SUPPRESSION_CHARACTER) {
                char digit = shown.charAt(digitIndex);
                digitIndex++;
                if (digit != ZERO_DIGIT) {
                    suppressing = false;
                }
                rendered.append(suppressing ? SPACE : digit);
            } else {
                // A simple insertion character inside the suppressed region is suppressed with it.
                rendered.append(suppressing ? SPACE : position);
            }
        }
        rendered.append(CURRENCY_DECIMAL_POINT).append(fractionDigits);
        return rendered.toString();
    }

    /**
     * A COBOL store into a {@code PIC S9(p)V99} receiver: scale exactly 2, excess fractional digits
     * truncated.
     *
     * <p>The rounding mode is {@link AccountUpdateResponse#MONETARY_ROUNDING}, which resolves to
     * {@code RoundingMode.DOWN}, because the keyword {@code ROUNDED} appears zero times in all 28
     * programs and COBOL therefore truncates on store. Naming the constant rather than the mode keeps
     * the policy in one place for the whole module.
     *
     * <p>Note that a COBOL {@code S9} item can hold a negative zero and a {@link BigDecimal} cannot, so
     * a stored {@code -0} arrives here as zero and renders with the mask's {@code '+'}. That is the same
     * normalisation {@link AccountUpdateRequest.AcctSnapshot} performs when it decodes the span, so the
     * two agree.
     *
     * @param value the value to store; {@code null} is the freshly initialised span, which is zero
     * @return the value at scale 2, never {@code null}
     */
    static BigDecimal storeMonetary(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(AccountUpdateResponse.MONETARY_SCALE,
                        AccountUpdateResponse.MONETARY_ROUNDING);
    }

    // =================================================================================================
    // COACTUPC's WORKING-STORAGE, as one per-request object.
    //
    // This is the whole reason there is no mutable static state in this class. COACTUPC has 4,236 lines
    // and a correspondingly large WORKING-STORAGE, and in COBOL that storage is per task; turning any of
    // it into a Java field would share it between concurrent requests and destroy both request isolation
    // and test determinism. One Conversation is created inside the request method, is reachable from
    // nowhere else, and dies with the response.
    //
    // Field names are the COBOL data names, lower camel cased, so a reviewer can grep the source for any
    // of them. Every one is a package-visible field rather than a property, because a COBOL item has no
    // encapsulation and pretending otherwise would add forty getters that say nothing.
    // =================================================================================================

    /** One {@code CAUP} task's storage. */
    static final class Conversation {

        /** {@code EIBCALEN}, read once at {@code :880}. */
        int eibcalen;

        /** {@code EIBAID}, read by {@code YYYY-STORE-PFKEY}. */
        byte eibAid;

        /** {@code WS-RESP-CD PIC S9(09) COMP} ({@code :40-41}). */
        int wsRespCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP} ({@code :42-43}). */
        int wsReasCd;

        /** {@code WS-TRANID PIC X(4)} ({@code :44-45}). */
        String wsTranid;

        /** {@code WS-UCTRANS PIC X(4)} ({@code :46-47}) - declared, never read by any statement. */
        String wsUctrans;

        /** {@code WS-EDIT-VARIABLE-NAME PIC X(25)} ({@code :53}), the name the error text is built on. */
        String wsEditVariableName;

        /** {@code WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15)} ({@code :55}) - {@code 1250}'s argument. */
        String wsEditSignedNumber9v2X;

        /** {@code WS-FLG-SIGNED-NUMBER-EDIT PIC X(1)} ({@code :56}) - {@code 1250}'s verdict. */
        String wsFlgSignedNumberEdit;

        /** {@code WS-EDIT-ALPHANUM-ONLY PIC X(256)} ({@code :61}) - the generic edits' argument. */
        String wsEditAlphanumOnly;

        /**
         * {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3} ({@code :62}).
         *
         * <p>Packed decimal in {@code WORKING-STORAGE} only - no persisted record in this system uses
         * {@code COMP-3} - so it is an {@code int} here and the fixed-width codec needs no nibble
         * unpacking. It is how much of the 256-character staging item each generic edit examines.
         */
        int wsEditAlphanumLength;

        /** {@code WS-EDIT-ALPHA-ONLY-FLAGS PIC X(1)} ({@code :64}) - {@code 1225}/{@code 1235}'s verdict. */
        String wsEditAlphaOnlyFlags;

        /** {@code WS-EDIT-ALPHANUM-ONLY-FLAGS PIC X(1)} ({@code :68}) - {@code 1230}/{@code 1240}/{@code 1245}'s. */
        String wsEditAlphanumOnlyFlags;

        /** {@code WS-EDIT-MANDATORY-FLAGS PIC X(1)} ({@code :72}) - {@code 1215}'s verdict. */
        String wsEditMandatoryFlags;

        /**
         * {@code WS-EDIT-YES-NO PIC X(1) VALUE 'N'} ({@code :76-77}).
         *
         * <p>Both the argument and the verdict of {@code 1220-EDIT-YESNO}: the caller moves the field
         * into it, the paragraph tests it, and the caller moves it back out into the field's own flag.
         * Its {@code VALUE 'N'} initialiser is irrelevant, because {@code INITIALIZE WS-MISC-STORAGE} at
         * {@code :866} overwrites it with a space before any pass reads it.
         */
        String wsEditYesNo;

        /** {@code WS-EDIT-US-PHONE-NUM PIC X(15)} ({@code :82}) - {@code (999)999-9999}. */
        String wsEditUsPhoneNum;

        /** {@code WS-EDIT-US-PHONEA-FLG PIC X(01)} ({@code :104}) - the area code's verdict. */
        String wsEditUsPhoneaFlg;

        /** {@code WS-EDIT-EDIT-US-PHONEB PIC X(01)} ({@code :108}) - the prefix's verdict. */
        String wsEditEditUsPhoneb;

        /** {@code WS-EDIT-EDIT-PHONEC PIC X(01)} ({@code :112}) - the line number's verdict. */
        String wsEditEditPhonec;

        /** {@code WS-EDIT-US-SSN-PART1-FLGS PIC X(01)} ({@code :135}). */
        String wsEditUsSsnPart1Flgs;

        /** {@code WS-EDIT-US-SSN-PART2-FLGS PIC X(01)} ({@code :139}). */
        String wsEditUsSsnPart2Flgs;

        /** {@code WS-EDIT-US-SSN-PART3-FLGS PIC X(01)} ({@code :143}). */
        String wsEditUsSsnPart3Flgs;

        /** {@code WS-CURR-DATE PIC X(21)} ({@code :159-160}) - declared, never read. */
        String wsCurrDate;

        /** {@code WS-DATACHANGED-FLAG PIC X(1)} ({@code :168}) - {@code 1205}'s verdict. */
        String wsDatachangedFlag;

        /** {@code WS-INPUT-FLAG PIC X(1)} ({@code :171}) - the flag every edit failure raises. */
        String wsInputFlag;

        /** {@code WS-RETURN-FLAG PIC X(1)} ({@code :175}) - declared, never read. */
        String wsReturnFlag;

        /** {@code WS-PFK-FLAG PIC X(1)} ({@code :178}) - whether the pressed key is valid here. */
        String wsPfkFlag;

        /** {@code WS-EDIT-ACCT-FLAG PIC X(1)} ({@code :183}) - the account filter's verdict. */
        String wsEditAcctFlag;

        /** {@code WS-EDIT-CUST-FLAG PIC X(1)} ({@code :187}) - the customer filter's verdict. */
        String wsEditCustFlag;

        /**
         * {@code WS-NON-KEY-FLAGS} ({@code :191-373}) - one entry per validated screen field.
         *
         * <p>A map keyed by the field rather than 44 named fields, because the group is moved whole
         * twice - {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS} at {@code :1468} and {@code :2789} - and
         * because {@code 3300-SETUP-SCREEN-ATTRS} reads all 44 in one ordered sweep. Keying it by
         * {@link AccountUpdateResponse.ScreenField} is what lets the 39 {@code CSSETATY} sites and the
         * 44-arm cursor {@code EVALUATE} be written as ordered lists rather than as 83 hand-copied
         * blocks - and the key is the screen field precisely because that is what the two consumers
         * name.
         *
         * <p>{@code ADDRESS-LINE-2}'s entry is present and can only ever hold the valid state, because
         * {@code 1200-EDIT-MAP-INPUTS} never edits that field: the {@code MOVE 'Address Line 2'} at
         * {@code :1611} is commented out.
         */
        final Map<AccountUpdateResponse.ScreenField, String> wsNonKeyFlags =
                new EnumMap<>(AccountUpdateResponse.ScreenField.class);

        /** {@code WS-CARD-RID-CARDNUM PIC X(16)} ({@code :379}) - declared, never used as a key here. */
        String wsCardRidCardnum;

        /** {@code WS-CARD-RID-CUST-ID PIC 9(09)} ({@code :380}), read as its {@code -X} redefinition. */
        String wsCardRidCustId;

        /** {@code WS-CARD-RID-ACCT-ID PIC 9(11)} ({@code :383}), read as its {@code -X} redefinition. */
        String wsCardRidAcctId;

        /** {@code WS-ACCOUNT-MASTER-READ-FLAG PIC X(1)} ({@code :386}). */
        String wsAccountMasterReadFlag;

        /** {@code WS-CUST-MASTER-READ-FLAG PIC X(1)} ({@code :388}). */
        String wsCustMasterReadFlag;

        /** {@code ERROR-OPNAME PIC X(8)} ({@code :390-391}) - only ever {@code 'READ'}. */
        String errorOpname;

        /** {@code ERROR-FILE PIC X(9)} ({@code :394-395}). */
        String errorFile;

        /** {@code ERROR-RESP PIC X(10)} ({@code :399-400}). */
        String errorResp;

        /** {@code ERROR-RESP2 PIC X(10)} ({@code :404-405}). */
        String errorResp2;

        /** {@code ACUP-NEW-CREDIT-LIMIT-X PIC X(15)} ({@code :412}) - the screen staging copy. */
        String acupNewCreditLimitX;

        /** {@code ACUP-NEW-CASH-CREDIT-LIMIT-X PIC X(15)} ({@code :413}). */
        String acupNewCashCreditLimitX;

        /** {@code ACUP-NEW-CURR-BAL-X PIC X(15)} ({@code :414}). */
        String acupNewCurrBalX;

        /** {@code ACUP-NEW-CURR-CYC-CREDIT-X PIC X(15)} ({@code :415}). */
        String acupNewCurrCycCreditX;

        /** {@code ACUP-NEW-CURR-CYC-DEBIT-X PIC X(15)} ({@code :416}). */
        String acupNewCurrCycDebitX;

        /** {@code WS-LONG-MSG PIC X(500)} ({@code :465}) - declared for a long-text send that never happens. */
        String wsLongMsg;

        /** {@code WS-INFO-MSG PIC X(40)} ({@code :466}) - what {@code INFOMSGO} shows. */
        String wsInfoMsg;

        /** {@code WS-RETURN-MSG PIC X(75)} ({@code :479}) - what {@code ERRMSGO} shows. */
        String wsReturnMsg;

        /** {@code CC-WORK-AREA} of {@code app/cpy/CVCRD01Y.cpy}, copied at {@code :599}. */
        CardScreenState ccWorkArea;

        /** {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, copied at {@code :650}. */
        NavigationContext carddemoCommarea;

        /** {@code ACUP-CHANGE-ACTION PIC X(1)} ({@code :654-655}) - the conversation's state byte. */
        AccountUpdateRequest.ChangeAction acupChangeAction;

        /** {@code ACUP-OLD-ACCT-DATA} ({@code :669-708}) - the snapshot the screen was painted from. */
        final AcctDataArea acupOldAcct = new AcctDataArea();

        /** {@code ACUP-OLD-CUST-DATA} ({@code :709-757}). */
        final CustDataArea acupOldCust = new CustDataArea();

        /** {@code ACUP-NEW-ACCT-DATA} ({@code :758-797}) - what the operator typed. */
        final AcctDataArea acupNewAcct = new AcctDataArea();

        /** {@code ACUP-NEW-CUST-DATA} ({@code :798-855}). */
        final CustDataArea acupNewCust = new CustDataArea();

        /**
         * {@code ACCOUNT-RECORD} of {@code app/cpy/CVACT01Y.cpy}, copied at {@code :640}.
         *
         * <p><strong>A record area, not an optional record.</strong> The copybook declares an
         * {@code 01}-level item, so the 300 bytes exist for the whole task whether or not a read has ever
         * filled them, and they hold what COBOL leaves there: spaces in every {@code PIC X} field and zero
         * in every numeric one. A failed {@code EXEC CICS READ ... INTO(ACCOUNT-RECORD)} leaves the area
         * exactly as it was, and {@code 9500-STORE-FETCHED-DATA} then copies that unchanged image into
         * {@code ACUP-OLD-ACCT-DATA} and the communication area - a defect of the original, reachable
         * because {@code :3720} comments out {@code SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE} and so
         * leaves {@code 9000}'s guard ineffective. It is preserved (practice <strong>B5</strong>).
         *
         * <p>So the field is <strong>never {@code null}</strong>, and {@code null} is never used to mean
         * "unchanged": {@link Conversation#Conversation(Charset)} establishes the starting image at the
         * moment storage exists, and only a successful read replaces it. It is not an {@code Optional} for
         * the same reason - {@code 3202-SHOW-ORIGINAL-VALUES} reaches the area through the
         * {@code FOUND-ACCT-IN-MASTER} flag, not through the presence of a record.
         */
        AccountRecord accountRecord;

        /**
         * {@code CUSTOMER-RECORD} of {@code app/cpy/CVCUS01Y.cpy}, copied at {@code :646}.
         *
         * <p>The same in every respect as {@link #accountRecord}: an {@code 01}-level area that exists for
         * the whole task, is never {@code null}, and is left unchanged by an unsuccessful read - with
         * {@code :3769} commenting out {@code SET DID-NOT-FIND-CUST-IN-CUSTDAT TO TRUE} for the customer
         * half of the same defect.
         */
        CustomerRecord customerRecord;

        /**
         * {@code CARD-XREF-RECORD} of {@code app/cpy/CVACT03Y.cpy}, copied at {@code :643}.
         *
         * <p>Modelled as optional, unlike the other two records, because it is only ever read inside the
         * arm of {@code 9200} that filled it - so no path can observe it absent.
         */
        Optional<CardXrefRecord> cardXrefRecord = Optional.empty();

        /**
         * {@code ABEND-DATA} of {@code app/cpy/CSMSG02Y.cpy}, copied at {@code :631}.
         *
         * <p>Four spans, every one declared {@code VALUE SPACES}, which is why the state it starts in is
         * {@link SystemMessages.AbendData#spaces()} and not {@code LOW-VALUES}. That distinction decides
         * whether {@code ABEND-ROUTINE}'s default message can ever be applied; see
         * {@link AccountUpdateController#abendRoutine(Conversation, RuntimeException)}.
         */
        SystemMessages.AbendData abendData = SystemMessages.AbendData.spaces();

        /** {@code WS-CURDATE-DATA} of {@code app/cpy/CSDAT01Y.cpy}, copied at {@code :625}. */
        DateHeader dateHeader;

        /** {@code WS-COMMAREA PIC X(2000)} ({@code :851}) - the area {@code COMMON-RETURN} returns. */
        String wsCommarea;

        /**
         * {@code CACTUPAO} - the output half of the symbolic map, which is {@code WORKING-STORAGE} too
         * ({@code COPY COACTUP} at {@code :623}).
         *
         * <p>Reassigned rather than mutated, because {@link AccountUpdateResponse} is immutable in its
         * values. Its attribute quads are mutable and survive every reassignment, which is what lets
         * {@code 3300}'s colour writes and {@code CSSETATY}'s {@code '*'} writes interleave.
         */
        AccountUpdateResponse cactupao;

        /**
         * {@code CACTUPAI} - the input half, whose {@code xxxL} and {@code xxxA} items
         * {@code 3300-SETUP-SCREEN-ATTRS} writes into.
         */
        AccountUpdateRequest cactupai;

        /** The {@code DFHMDF} label the cursor {@code EVALUATE} selected, or {@code null} if it never ran. */
        String cursorField;

        /** Whether {@code COMMON-RETURN} has run, so it cannot run twice however the flow reached it. */
        boolean returned;

        /**
         * Creates one interaction's storage with both record areas at their COBOL starting image.
         *
         * <p>{@link #accountRecord} and {@link #customerRecord} are established here rather than in
         * {@link AccountUpdateController#initializeStorage} because they are {@code 01}-level items in
         * their own right - {@code COPY CVACT01Y} at {@code :640} and {@code COPY CVCUS01Y} at
         * {@code :646}, both outside {@code 01 WS-MISC-STORAGE} at {@code :35} - so neither
         * {@code INITIALIZE WS-MISC-STORAGE} at {@code :867} nor the one at {@code :983} reaches them.
         * Storage coming into existence is the only event that gives them a value COBOL agrees with.
         *
         * <p>The code page is a constructor argument because {@link AccountRecord} is byte-backed: it
         * holds 300 bytes and hands them back unchanged, so an area is only meaningful in a stated page.
         * The caller passes the active dataset page, which is the page a read would have filled the area
         * in - so the image a read leaves alone is measured exactly as the image a read writes.
         * {@link CustomerRecord} needs no page: it is a record of {@code String} fields that acquires one
         * only when its repository encodes it.
         *
         * @param datasetCharset the active dataset code page; must not be {@code null}
         * @throws NullPointerException if {@code datasetCharset} is {@code null}
         */
        Conversation(Charset datasetCharset) {
            Objects.requireNonNull(datasetCharset,
                    "ACCOUNT-RECORD is 300 bytes of storage and needs the code page they are written in");
            this.accountRecord = new AccountRecord(datasetCharset);
            this.customerRecord = new CustomerRecord();
        }

        /** @return {@code WS-CARD-RID-ACCT-ID PIC 9(11)}'s numeric value. */
        long wsCardRidAcctIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidAcctId);
        }

        /** @return {@code WS-CARD-RID-CUST-ID PIC 9(09)}'s numeric value. */
        long wsCardRidCustIdN() {
            return PIC_X_CODEC.decodePic9(wsCardRidCustId);
        }

        /** @return {@code INPUT-OK}. */
        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        /** @return {@code INPUT-ERROR}. */
        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /** @return {@code INPUT-PENDING}, the state {@code INITIALIZE} leaves before {@code 1200} runs. */
        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        /** @return {@code PFK-VALID}. */
        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        /** @return {@code PFK-INVALID}. */
        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        /** @return {@code NO-CHANGES-FOUND} - {@code 1205} found the two groups equal. */
        boolean noChangesFound() {
            return NO_CHANGES_FOUND.equals(wsDatachangedFlag);
        }

        /** @return {@code CHANGE-HAS-OCCURRED} - {@code 1205} found a difference. */
        boolean changeHasOccurred() {
            return CHANGE_HAS_OCCURRED.equals(wsDatachangedFlag);
        }

        /** @return {@code FLG-ACCTFILTER-ISVALID}. */
        boolean flgAcctfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditAcctFlag);
        }

        /** @return {@code FLG-ACCTFILTER-NOT-OK}. */
        boolean flgAcctfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditAcctFlag);
        }

        /** @return {@code FLG-ACCTFILTER-BLANK} - a space, which is also what {@code INITIALIZE} leaves. */
        boolean flgAcctfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditAcctFlag);
        }

        /** @return {@code FLG-CUSTFILTER-ISVALID}. */
        boolean flgCustfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCustFlag);
        }

        /** @return {@code FLG-CUSTFILTER-NOT-OK}. */
        boolean flgCustfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCustFlag);
        }

        /** @return {@code FOUND-ACCT-IN-MASTER}. */
        boolean foundAcctInMaster() {
            return FOUND_IN_MASTER.equals(wsAccountMasterReadFlag);
        }

        /** @return {@code FOUND-CUST-IN-MASTER}. */
        boolean foundCustInMaster() {
            return FOUND_IN_MASTER.equals(wsCustMasterReadFlag);
        }

        /** @return {@code WS-RETURN-MSG-OFF} - the message is still spaces, so a new one may be set. */
        boolean returnMsgOff() {
            return AccountUpdateService.isReturnMessageOff(wsReturnMsg);
        }

        /**
         * @return {@code WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} ({@code :467-468}) - two
         *         figurative constants, either of which satisfies it
         */
        boolean wsNoInfoMessage() {
            return spaces(WS_INFO_MSG_LENGTH).equals(wsInfoMsg)
                    || lowValues(WS_INFO_MSG_LENGTH).equals(wsInfoMsg);
        }

        /**
         * @return {@code FOUND-ACCOUNT-DATA} ({@code :469-470}) - an 88 on {@code WS-INFO-MSG}, tested by
         *         the first arm of {@code 3300}'s cursor {@code EVALUATE} at {@code :3011}
         */
        boolean foundAccountData() {
            return atInfoWidth(INFO_FOUND_ACCOUNT_DATA).equals(wsInfoMsg);
        }

        /** @return {@code PROMPT-FOR-CONFIRMATION} - tested by {@code 3390} at {@code :3577}. */
        boolean promptForConfirmation() {
            return atInfoWidth(INFO_PROMPT_FOR_CONFIRMATION).equals(wsInfoMsg);
        }

        /** @return {@code NO-CHANGES-DETECTED} - the {@code WS-RETURN-MSG} value {@code 2000} tests. */
        boolean noChangesDetected() {
            return atReturnWidth(MSG_NO_CHANGES_DETECTED).equals(wsReturnMsg);
        }

        /** @return {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} - tested at {@code :3626}; the {@code SET} is commented out. */
        boolean didNotFindAcctInAcctdat() {
            return atReturnWidth(MSG_DID_NOT_FIND_ACCT_IN_ACCTDAT).equals(wsReturnMsg);
        }

        /** @return {@code DID-NOT-FIND-CUST-IN-CUSTDAT} - tested at {@code :3634}; likewise never set. */
        boolean didNotFindCustInCustdat() {
            return atReturnWidth(MSG_DID_NOT_FIND_CUST_IN_CUSTDAT).equals(wsReturnMsg);
        }

        /**
         * Reads one entry of {@code WS-NON-KEY-FLAGS}.
         *
         * @param field the screen field the flag belongs to
         * @return the flag character, or the valid state for a field the group does not cover
         */
        String flag(AccountUpdateResponse.ScreenField field) {
            return wsNonKeyFlags.getOrDefault(field, FLG_ISVALID);
        }

        /**
         * Writes one entry of {@code WS-NON-KEY-FLAGS}.
         *
         * @param field the screen field the flag belongs to
         * @param value the flag character
         */
        void setFlag(AccountUpdateResponse.ScreenField field, String value) {
            wsNonKeyFlags.put(field, value);
        }

        /**
         * @param field the screen field
         * @return whether that field's flag is {@code FLG-xxx-NOT-OK}
         */
        boolean flagNotOk(AccountUpdateResponse.ScreenField field) {
            return FLG_NOT_OK.equals(flag(field));
        }

        /**
         * @param field the screen field
         * @return whether that field's flag is {@code FLG-xxx-BLANK}
         */
        boolean flagBlank(AccountUpdateResponse.ScreenField field) {
            return FLG_BLANK.equals(flag(field));
        }

        /**
         * @param field the screen field
         * @return whether that field's flag is {@code FLG-xxx-ISVALID}
         */
        boolean flagIsvalid(AccountUpdateResponse.ScreenField field) {
            return FLG_ISVALID.equals(flag(field));
        }

        /**
         * {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS} - {@code :1468} and {@code :2789}.
         *
         * <p>A group {@code MOVE} of {@code LOW-VALUES}, which puts every one of the 44 flags into its
         * {@code ISVALID} state - because that state is itself {@code VALUE LOW-VALUES}. Clearing the
         * map achieves the same thing, since {@link #flag} reports {@link #FLG_ISVALID} for an absent
         * key, and it does so without inventing 44 writes the source does not make.
         */
        void clearNonKeyFlags() {
            wsNonKeyFlags.clear();
        }

        /**
         * The two detail groups as {@code WS-THIS-PROGCOMMAREA} carries them.
         *
         * @return the change action and both groups, ready to travel in the payload
         */
        AccountUpdateRequest.CommArea toCommArea() {
            return new AccountUpdateRequest.CommArea(acupChangeAction,
                    new AccountUpdateRequest.Details(AccountUpdateRequest.DetailGroup.OLD,
                            acupOldAcct.toSnapshot(), acupOldCust.toSnapshot()),
                    new AccountUpdateRequest.Details(AccountUpdateRequest.DetailGroup.NEW,
                            acupNewAcct.toSnapshot(), acupNewCust.toSnapshot()));
        }
    }

    /**
     * {@code WS-INFO-MSG}'s declared width, applied to a literal before it is compared or shown.
     *
     * <p>{@code 88}-level comparisons in COBOL are fixed-width comparisons: the literal is space extended
     * to the item's width first. Doing that here is what makes {@link Conversation#promptForConfirmation}
     * agree with the {@code SET} that produced the value.
     *
     * @param message the literal
     * @return exactly {@value #WS_INFO_MSG_LENGTH} characters
     */
    static String atInfoWidth(String message) {
        return PIC_X_CODEC.movePicX(message, WS_INFO_MSG_LENGTH);
    }

    /**
     * {@code WS-RETURN-MSG}'s declared width, applied to a literal for the same reason.
     *
     * @param message the literal
     * @return exactly {@value #WS_RETURN_MSG_LENGTH} characters
     */
    static String atReturnWidth(String message) {
        return PIC_X_CODEC.movePicX(message, WS_RETURN_MSG_LENGTH);
    }

    // =================================================================================================
    // 0000-MAIN - app/cbl/COACTUPC.cbl:859-1005, and COMMON-RETURN at :1007-1019.
    // =================================================================================================

    /**
     * Runs one interaction, from {@code EXEC CICS HANDLE ABEND} to {@code EXEC CICS RETURN}.
     *
     * <p>The {@code try} reproduces {@code HANDLE ABEND LABEL(ABEND-ROUTINE)} at {@code :862-864}: any
     * failure the flow does not itself handle lands in {@link #abendRoutine}, exactly as any abend on
     * the mainframe would branch to that label. An {@link AbendException} already in flight is rethrown
     * untouched, because {@code ABEND-ROUTINE} cancels the handler at {@code :4219-4221} before abending
     * and so cannot re-enter itself.
     *
     * <p>One thing is deliberately outside that handler: a {@link ScreenInputRejectedException} raised
     * deeper in the flow is rethrown rather than handled, because {@code ABEND-ROUTINE} is for a unit of
     * work that genuinely did not complete, not for a payload describing a conversation this program
     * cannot be in.
     *
     * <p><strong>No code-page sweep stands ahead of the flow.</strong> Whether a value could have been
     * delivered by a terminal at the configured code page is a transport judgement, and it is made once
     * for every string of every request body by {@code config.WebConfig.ScreenTextDeserializer} at the JSON
     * boundary. Making it here as well ran it ahead of {@code :880-893}, where the program decides
     * whether it has a conversation at all, and ahead of the {@code EVALUATE} at {@code :1025-1062} that
     * decides whether {@code 1100-RECEIVE-MAP} runs - so a field this program was about to ignore could
     * be refused, and it was refused against a hard-coded {@code US-ASCII} rather than the page actually
     * in force.
     *
     * @param request  the terminal input area and carried state
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     * @return the painted screen, its input area and the cursor field
     * @throws ScreenInputRejectedException if the payload carries a value a received map could not have
     * @throws AbendException               if the interaction abends
     */
    PaintedScreen handle(AccountUpdateRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COACTUPC is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");
        Conversation task = new Conversation(codec.charset());
        try {
            main0000(request, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            throw alreadyAbending;
        } catch (ScreenInputRejectedException callersInput) {
            throw callersInput;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, abend);
        }
        return new PaintedScreen(task.cactupao, task.cactupai, task.cursorField);
    }

    /**
     * {@code 0000-MAIN} - {@code app/cbl/COACTUPC.cbl:859-1005}.
     *
     * <p>Seven steps in the source's order: handle abend, initialise three of the four storage areas,
     * stamp the transaction identifier, clear the message, restore the passed areas, map the attention
     * identifier, decide whether the pressed key is allowed here, and dispatch.
     *
     * @param request  the terminal input area
     * @param task     this interaction's storage
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     */
    void main0000(AccountUpdateRequest request, Conversation task, int eibcalen, byte eibAid) {
        // :866-868 - INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE and WS-COMMAREA. Note which area is NOT in
        // that list: WS-THIS-PROGCOMMAREA, which is initialised only inside the IF at :884-885.
        initializeStorage(request, task, eibcalen, eibAid);
        // :872 - MOVE LIT-THISTRANID TO WS-TRANID.
        task.wsTranid = PIC_X_CODEC.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);
        // :876 - SET WS-RETURN-MSG-OFF TO TRUE, so the message is blank before anything can set it.
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        // :880-893 - store the passed data, if any.
        restoreCommarea(task);
        // :897-898 - PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT.
        storePfKeyYYYY(task);
        // :905-915 - is the key valid here, and if not, pretend ENTER was pressed.
        coerceInvalidAid(task);
        // :919-1004 - EVALUATE TRUE, four arms.
        dispatch0000(task);
    }

    /**
     * {@code INITIALIZE CC-WORK-AREA WS-MISC-STORAGE WS-COMMAREA} - {@code :866-868}.
     *
     * <p>Three areas, and the omission of the fourth is load bearing:
     * {@code WS-THIS-PROGCOMMAREA} is <em>not</em> initialised here, which is why a carried
     * {@code ACUP-CHANGE-ACTION} survives into the dispatch and decides which arm runs.
     *
     * <p>{@code INITIALIZE} sets an alphanumeric item to spaces and a numeric one to zero, so every
     * flag whose {@code ISVALID} condition is {@code VALUE LOW-VALUES} starts out in <em>none</em> of
     * its declared states - which is exactly why {@link Conversation#inputPending} exists.
     *
     * @param request  the terminal input area, whose carried areas are copied in before they are blanked
     * @param task     this interaction's storage
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     */
    void initializeStorage(AccountUpdateRequest request, Conversation task, int eibcalen, byte eibAid) {
        task.eibcalen = eibcalen;
        task.eibAid = eibAid;

        // CC-WORK-AREA. The carried work area is copied in first and then blanked, which is why the AID
        // the caller sent does not survive: CCARD-AID is set from EIBAID a few statements later, never
        // from the payload.
        task.ccWorkArea = new CardScreenState(request.getCardScreenState());
        task.ccWorkArea.initializeWorkArea();

        // WS-MISC-STORAGE, in declaration order.
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
        task.wsTranid = spaces(WS_TRANID_LENGTH);
        task.wsUctrans = spaces(WS_TRANID_LENGTH);
        task.wsEditVariableName = spaces(WS_EDIT_VARIABLE_NAME_LENGTH);
        task.wsEditSignedNumber9v2X = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsFlgSignedNumberEdit = INITIALIZED_FLAG;
        task.wsEditAlphanumOnly = spaces(WS_EDIT_ALPHANUM_ONLY_LENGTH);
        task.wsEditAlphanumLength = 0;
        task.wsEditAlphaOnlyFlags = INITIALIZED_FLAG;
        task.wsEditAlphanumOnlyFlags = INITIALIZED_FLAG;
        task.wsEditMandatoryFlags = INITIALIZED_FLAG;
        task.wsEditYesNo = INITIALIZED_FLAG;
        task.wsEditUsPhoneNum = spaces(WS_EDIT_US_PHONE_NUM_LENGTH);
        task.wsEditUsPhoneaFlg = INITIALIZED_FLAG;
        task.wsEditEditUsPhoneb = INITIALIZED_FLAG;
        task.wsEditEditPhonec = INITIALIZED_FLAG;
        task.wsEditUsSsnPart1Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart2Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart3Flgs = INITIALIZED_FLAG;
        task.wsCurrDate = spaces(WS_CURR_DATE_LENGTH);
        task.wsDatachangedFlag = INITIALIZED_FLAG;
        task.wsInputFlag = INITIALIZED_FLAG;
        task.wsReturnFlag = INITIALIZED_FLAG;
        task.wsPfkFlag = INITIALIZED_FLAG;
        task.wsEditAcctFlag = INITIALIZED_FLAG;
        task.wsEditCustFlag = INITIALIZED_FLAG;
        // WS-NON-KEY-FLAGS: every flag to a space, which is none of its three declared states. An empty
        // map is not that state, so the 44 entries are written explicitly.
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
            task.wsNonKeyFlags.put(field, INITIALIZED_FLAG);
        }
        task.wsCardRidCardnum = spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_CUST_ID_LENGTH);
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.wsAccountMasterReadFlag = INITIALIZED_FLAG;
        task.wsCustMasterReadFlag = INITIALIZED_FLAG;
        task.errorOpname = spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = spaces(ERROR_FILE_LENGTH);
        task.errorResp = spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = spaces(ERROR_RESP_LENGTH);
        task.acupNewCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCashCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrBalX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycCreditX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycDebitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsLongMsg = spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = spaces(WS_INFO_MSG_LENGTH);
        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        // WS-COMMAREA PIC X(2000), the third area INITIALIZE blanks.
        task.wsCommarea = spaces(WS_COMMAREA_LENGTH);

        // ACCOUNT-RECORD and CUSTOMER-RECORD are deliberately NOT reset. The INITIALIZE at :867 names
        // CC-WORK-AREA, WS-MISC-STORAGE and WS-COMMAREA, and the two copybooks at :640 and :646 are
        // 01-level items outside all three - so each area keeps whatever it holds, which for a fresh task
        // is the starting image Conversation's constructor gave it.
        task.cardXrefRecord = Optional.empty();
        task.abendData = SystemMessages.AbendData.spaces();

        // WS-THIS-PROGCOMMAREA - deliberately NOT initialised here. It is restored from the payload, or
        // blanked by restoreCommarea's first arm, and nowhere else.
        AccountUpdateRequest.CommArea carried = request.getCommArea();
        task.acupChangeAction = carried.changeAction();
        task.acupOldAcct.fromSnapshot(carried.oldDetails().acct());
        task.acupOldCust.fromSnapshot(carried.oldDetails().cust());
        task.acupNewAcct.fromSnapshot(carried.newDetails().acct());
        task.acupNewCust.fromSnapshot(carried.newDetails().cust());

        // CARDDEMO-COMMAREA, likewise restored rather than initialised.
        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();

        // CACTUPAI and CACTUPAO. The input half is what arrived; the output half is created here and
        // painted by the 3000-SEND-MAP family, which starts by moving LOW-VALUES over all of it.
        task.cactupai = request;
        task.cactupao = AccountUpdateResponse.initial();
        task.cursorField = null;
        task.returned = false;
    }

    /**
     * {@code IF EIBCALEN IS EQUAL TO 0 OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)}
     * - {@code app/cbl/COACTUPC.cbl:880-893}.
     *
     * <p>Two conditions, either of which means "this is a fresh entry, so discard whatever was carried":
     * no communication area at all, or an arrival from the main menu that is not a re-entry. Both arms
     * blank {@code CARDDEMO-COMMAREA} <em>and</em> {@code WS-THIS-PROGCOMMAREA}, and then set
     * {@code CDEMO-PGM-ENTER} and {@code ACUP-DETAILS-NOT-FETCHED} - which is what steers the dispatch
     * into its second arm.
     *
     * <p>The {@code ELSE} moves both areas out of {@code DFHCOMMAREA} by offset. That is not a no-op
     * even when the payload already carries them: a group {@code MOVE} imposes every field's declared
     * width, so a context carrying a short program name comes back space padded to {@code PIC X(08)},
     * exactly as the receiving area would hold it.
     *
     * @param task this interaction's storage
     */
    void restoreCommarea(Conversation task) {
        boolean noCommareaPassed = task.eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(PIC_X_CODEC.movePicX(
                task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();
        if (noCommareaPassed || freshEntryFromMenu) {
            // :884-885 - INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA.
            task.carddemoCommarea = NavigationContext.empty();
            task.acupOldAcct.initialize();
            task.acupOldCust.initialize();
            task.acupNewAcct.initialize();
            task.acupNewCust.initialize();
            // :886 - SET CDEMO-PGM-ENTER TO TRUE.
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            // :887 - SET ACUP-DETAILS-NOT-FETCHED TO TRUE. INITIALIZE leaves the byte a space, which
            // already satisfies the condition, and the SET makes it LOW-VALUES - a different byte with
            // the same meaning. The source's choice is reproduced, because the byte travels in the
            // payload and a diff compares bytes.
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            return;
        }
        // :889-893 - the two moves out of DFHCOMMAREA, by offset.
        task.carddemoCommarea = NavigationContext.fromFixedWidth(
                codec, task.carddemoCommarea.toFixedWidth(codec));
        AccountUpdateRequest.CommArea atWidth =
                AccountUpdateRequest.CommArea.decode(task.toCommArea().encode(codec), codec);
        task.acupChangeAction = atWidth.changeAction();
        task.acupOldAcct.fromSnapshot(atWidth.oldDetails().acct());
        task.acupOldCust.fromSnapshot(atWidth.oldDetails().cust());
        task.acupNewAcct.fromSnapshot(atWidth.newDetails().acct());
        task.acupNewCust.fromSnapshot(atWidth.newDetails().cust());
    }

    /**
     * {@code YYYY-STORE-PFKEY} - {@code app/cpy/CSSTRPFY.cpy}, copied at {@code app/cbl/COACTUPC.cbl:4198}.
     *
     * <p>A 28-arm {@code EVALUATE} over {@code EIBAID} with <strong>no {@code WHEN OTHER}</strong>, no
     * {@code DFHPA3} arm and no pre-clear of {@code CCARD-AID}. So an unrecognised key leaves the field
     * exactly as it was - which after {@code INITIALIZE CC-WORK-AREA} is {@code LOW-VALUES}, satisfying
     * none of the fifteen {@code CCARD-AID-xxx} conditions. {@link PfKeyResolver#storePfKey} reproduces
     * that no-match outcome by returning the current value rather than a default, and the value is only
     * written when it changed.
     *
     * @param task this interaction's storage
     */
    void storePfKeyYYYY(Conversation task) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(task.eibAid, task.ccWorkArea.aidKey());
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    /**
     * {@code SET PFK-INVALID ... IF PFK-INVALID SET CCARD-AID-ENTER TO TRUE} - {@code :905-915}.
     *
     * <p>Four keys are valid on this screen, and two of them are valid only in a particular state:
     * {@code ENTER} always, {@code PF03} always, {@code PF05} <em>only</em> when the changes have been
     * validated and are awaiting confirmation, and {@code PF12} <em>only</em> when details have been
     * fetched. Anything else - including {@code PF05} pressed too early and {@code PF12} pressed on the
     * search screen - is rewritten to {@code ENTER}, so the operator's key press is silently reinterpreted
     * rather than rejected. That is the program's behaviour and it is reproduced exactly.
     *
     * @param task this interaction's storage
     */
    void coerceInvalidAid(Conversation task) {
        task.wsPfkFlag = PFK_INVALID;
        if (task.ccWorkArea.isCcardAidEnter()
                || task.ccWorkArea.isCcardAidPfk03()
                || (task.ccWorkArea.isCcardAidPfk05()
                        && task.acupChangeAction.isChangesOkNotConfirmed())
                || (task.ccWorkArea.isCcardAidPfk12()
                        && !task.acupChangeAction.isDetailsNotFetched())) {
            task.wsPfkFlag = PFK_VALID;
        }
        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    /**
     * {@code EVALUATE TRUE} - {@code app/cbl/COACTUPC.cbl:919-1004}. Four arms, and the order is the
     * contract.
     *
     * <ol>
     *   <li><strong>{@code WHEN CCARD-AID-PFK03}</strong> ({@code :926-957}) - the operator asked to
     *       leave. Resolve where to go back to, stamp this program as the origin, syncpoint, and
     *       {@code XCTL}. No map is sent.</li>
     *   <li><strong>{@code WHEN ACUP-DETAILS-NOT-FETCHED AND CDEMO-PGM-ENTER}</strong> /
     *       <strong>{@code WHEN CDEMO-FROM-PROGRAM EQUAL LIT-MENUPGM AND NOT CDEMO-PGM-REENTER}</strong>
     *       ({@code :963-972}) - two conditions sharing one body: blank this program's own area, paint
     *       the empty search screen, and mark the conversation re-entrant.</li>
     *   <li><strong>{@code WHEN ACUP-CHANGES-OKAYED-AND-DONE}</strong> /
     *       <strong>{@code WHEN ACUP-CHANGES-FAILED}</strong> ({@code :979-988}) - the previous pass
     *       finished, successfully or not. Reset the search keys and paint the empty search screen
     *       again. Note that this arm also blanks {@code WS-MISC-STORAGE} and {@code CDEMO-ACCT-ID},
     *       which the second arm does not.</li>
     *   <li><strong>{@code WHEN OTHER}</strong> ({@code :996-1003}) - the ordinary turn: read the
     *       inputs, decide, paint.</li>
     * </ol>
     *
     * <p>Three of the four arms end in {@code GO TO COMMON-RETURN} and the first ends in {@code XCTL},
     * so every arm is terminal and the {@code EVALUATE} cannot fall through to anything.
     *
     * @param task this interaction's storage
     */
    void dispatch0000(Conversation task) {
        if (task.ccWorkArea.isCcardAidPfk03()) {
            transferControl0000(task);
            return;
        }
        boolean detailsNotFetchedOnEnter = task.acupChangeAction.isDetailsNotFetched()
                && task.carddemoCommarea.isEnter();
        boolean enteredFromMenu = LIT_MENUPGM.equals(PIC_X_CODEC.movePicX(
                task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();
        if (detailsNotFetchedOnEnter || enteredFromMenu) {
            // :967 - INITIALIZE WS-THIS-PROGCOMMAREA.
            initializeThisProgCommarea(task);
            // :968-969 - PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT. One of the program's two
            // PERFORM ... THRU sites, and one method call.
            sendMap3000(task);
            // :970-971.
            task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            commonReturn(task);
            return;
        }
        if (task.acupChangeAction.isChangesOkayedAndDone() || task.acupChangeAction.isChangesFailed()) {
            // :982-984 - INITIALIZE WS-THIS-PROGCOMMAREA, WS-MISC-STORAGE and CDEMO-ACCT-ID. The second
            // and third are what make this arm different from the one above: every flag and every work
            // item goes back to its initialised state, and the carried account identifier goes to zero.
            initializeThisProgCommarea(task);
            initializeMiscStorage(task);
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            // :985 - SET CDEMO-PGM-ENTER TO TRUE.
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            // :986-987 - the second PERFORM ... THRU site, and the same one method call.
            sendMap3000(task);
            // :988-989.
            task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.initial();
            commonReturn(task);
            return;
        }
        // :996-1003 - WHEN OTHER.
        processInputs1000(task);
        decideAction2000(task);
        sendMap3000(task);
        commonReturn(task);
    }

    /**
     * {@code INITIALIZE WS-THIS-PROGCOMMAREA} - {@code :967} and {@code :982}.
     *
     * <p>Both detail groups back to spaces, and the change action with them: {@code ACUP-CHANGE-ACTION}
     * is the group's first item, and {@code INITIALIZE} sets it to a space - which is one of the two
     * values {@code ACUP-DETAILS-NOT-FETCHED} accepts.
     *
     * @param task this interaction's storage
     */
    void initializeThisProgCommarea(Conversation task) {
        task.acupChangeAction = AccountUpdateRequest.ChangeAction.spacesState();
        task.acupOldAcct.initialize();
        task.acupOldCust.initialize();
        task.acupNewAcct.initialize();
        task.acupNewCust.initialize();
    }

    /**
     * {@code INITIALIZE WS-MISC-STORAGE} - {@code :983}, reached only by the third dispatch arm.
     *
     * <p>Every flag, every edit work item, every record identification field and both messages back to
     * their initialised states. It does <em>not</em> touch {@code CC-WORK-AREA},
     * {@code CARDDEMO-COMMAREA}, {@code WS-THIS-PROGCOMMAREA} or the map areas.
     *
     * @param task this interaction's storage
     */
    void initializeMiscStorage(Conversation task) {
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = FileStatus.NO_REASON_CODE;
        task.wsTranid = spaces(WS_TRANID_LENGTH);
        task.wsUctrans = spaces(WS_TRANID_LENGTH);
        task.wsEditVariableName = spaces(WS_EDIT_VARIABLE_NAME_LENGTH);
        task.wsEditSignedNumber9v2X = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsFlgSignedNumberEdit = INITIALIZED_FLAG;
        task.wsEditAlphanumOnly = spaces(WS_EDIT_ALPHANUM_ONLY_LENGTH);
        task.wsEditAlphanumLength = 0;
        task.wsEditAlphaOnlyFlags = INITIALIZED_FLAG;
        task.wsEditAlphanumOnlyFlags = INITIALIZED_FLAG;
        task.wsEditMandatoryFlags = INITIALIZED_FLAG;
        task.wsEditYesNo = INITIALIZED_FLAG;
        task.wsEditUsPhoneNum = spaces(WS_EDIT_US_PHONE_NUM_LENGTH);
        task.wsEditUsPhoneaFlg = INITIALIZED_FLAG;
        task.wsEditEditUsPhoneb = INITIALIZED_FLAG;
        task.wsEditEditPhonec = INITIALIZED_FLAG;
        task.wsEditUsSsnPart1Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart2Flgs = INITIALIZED_FLAG;
        task.wsEditUsSsnPart3Flgs = INITIALIZED_FLAG;
        task.wsCurrDate = spaces(WS_CURR_DATE_LENGTH);
        task.wsDatachangedFlag = INITIALIZED_FLAG;
        task.wsInputFlag = INITIALIZED_FLAG;
        task.wsReturnFlag = INITIALIZED_FLAG;
        task.wsPfkFlag = INITIALIZED_FLAG;
        task.wsEditAcctFlag = INITIALIZED_FLAG;
        task.wsEditCustFlag = INITIALIZED_FLAG;
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.FIELDS) {
            task.wsNonKeyFlags.put(field, INITIALIZED_FLAG);
        }
        task.wsCardRidCardnum = spaces(WS_CARD_RID_CARDNUM_LENGTH);
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_CUST_ID_LENGTH);
        task.wsCardRidAcctId = PIC_X_CODEC.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.wsAccountMasterReadFlag = INITIALIZED_FLAG;
        task.wsCustMasterReadFlag = INITIALIZED_FLAG;
        task.errorOpname = spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = spaces(ERROR_FILE_LENGTH);
        task.errorResp = spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = spaces(ERROR_RESP_LENGTH);
        task.acupNewCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCashCreditLimitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrBalX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycCreditX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.acupNewCurrCycDebitX = spaces(WS_EDIT_SIGNED_NUMBER_LENGTH);
        task.wsLongMsg = spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = spaces(WS_INFO_MSG_LENGTH);
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        // As at :867, the two record areas are NOT reset: INITIALIZE WS-MISC-STORAGE names one 01-level
        // item, and the copybooks at :640 and :646 are two others.
        task.cardXrefRecord = Optional.empty();
        task.abendData = SystemMessages.AbendData.spaces();
    }

    /**
     * {@code WHEN CCARD-AID-PFK03} - {@code app/cbl/COACTUPC.cbl:926-957}.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at {@code :955-957}
     * becomes a {@code nextProgram} field on the response and nothing more: the client issues the
     * follow-up call, so the server stays stateless and there is no forward, no redirect chain and no
     * session affinity.
     *
     * <p>Where to go is resolved from the carried context, with the main menu as the fallback for both
     * halves independently - the transaction identifier and the program name are tested separately, so a
     * context carrying one and not the other keeps the one it has. Then this program stamps itself as the
     * origin so the target can come back, sets {@code CDEMO-USRTYP-USER} unconditionally - which
     * <em>downgrades an administrator</em>, and is reproduced because the byte travels in the payload -
     * and records this screen as the last map.
     *
     * <p>{@code EXEC CICS SYNCPOINT} at {@code :951-953} commits nothing here, because this arm has
     * performed no dataset work: the update path commits inside {@link AccountUpdateService}'s own unit
     * of work, and this arm is reached instead of it, never after it.
     *
     * @param task this interaction's storage
     */
    void transferControl0000(Conversation task) {
        // :927 - SET CCARD-AID-PFK03 TO TRUE. Already true, since that is the arm's own condition; the
        // source sets it again and the re-set is harmless and reproduced.
        task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.PFK03);

        // :929-934 - the transaction identifier to return to.
        String fromTranid = PIC_X_CODEC.movePicX(task.carddemoCommarea.fromTranid(),
                NavigationContext.FROM_TRANID_LENGTH);
        boolean fromTranidUnset = lowValues(NavigationContext.FROM_TRANID_LENGTH).equals(fromTranid)
                || spaces(NavigationContext.FROM_TRANID_LENGTH).equals(fromTranid);
        task.carddemoCommarea = task.carddemoCommarea
                .withToTranid(fromTranidUnset ? LIT_MENUTRANID : fromTranid);

        // :936-941 - the program to return to.
        String fromProgram = PIC_X_CODEC.movePicX(task.carddemoCommarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH);
        boolean fromProgramUnset = lowValues(NavigationContext.FROM_PROGRAM_LENGTH).equals(fromProgram)
                || spaces(NavigationContext.FROM_PROGRAM_LENGTH).equals(fromProgram);
        task.carddemoCommarea = task.carddemoCommarea
                .withToProgram(fromProgramUnset ? LIT_MENUPGM : fromProgram);

        // :943-944 - this program becomes the origin.
        task.carddemoCommarea = task.carddemoCommarea
                .withFromTranid(LIT_THISTRANID)
                .withFromProgram(LIT_THISPGM);

        // :946 - SET CDEMO-USRTYP-USER TO TRUE, unconditionally.
        task.carddemoCommarea = task.carddemoCommarea.withUserTypeUser();
        // :947 - SET CDEMO-PGM-ENTER TO TRUE, so the target treats the arrival as a first entry.
        task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
        // :948-949 - MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET and LIT-THISMAP TO CDEMO-LAST-MAP. The
        // mapset literal is PIC X(8) and CDEMO-LAST-MAPSET is PIC X(7), so the move drops the trailing
        // space - which is the right-hand truncation a PIC X move performs, and is why the carried value
        // is 'COACTUP' rather than 'COACTUP '.
        task.carddemoCommarea = task.carddemoCommarea
                .withLastMapset(PIC_X_CODEC.movePicX(LIT_THISMAPSET,
                        NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(PIC_X_CODEC.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));

        // :955-957 - the transfer itself, as a response field. The map area is whatever it was, which on
        // this path is the unpainted initial state: no paragraph of the 3000 family has run, because the
        // operator is about to see the target program's screen and not this one's.
        task.cactupao = task.cactupao
                .withNextTarget(task.carddemoCommarea.toProgram(),
                        task.ccWorkArea.getCcardNextMapset(),
                        task.ccWorkArea.getCcardNextMap())
                .withNavigationContext(task.carddemoCommarea)
                .withCardScreenState(task.ccWorkArea)
                .withCommArea(task.toCommArea());
        task.returned = true;
    }

    /**
     * {@code COMMON-RETURN} - {@code app/cbl/COACTUPC.cbl:1007-1019}.
     *
     * <p>The single response-assembly terminal, reached by all three of the {@code GO TO COMMON-RETURN}
     * statements and by falling off the end of the fourth dispatch arm. Three steps: publish the message
     * into the work area, lay both communication areas out in {@code WS-COMMAREA} back to back, and
     * return with the transaction identifier so the next key press re-enters this program.
     *
     * <p>Guarded so it cannot run twice however the flow reached it.
     *
     * @param task this interaction's storage
     */
    void commonReturn(Conversation task) {
        if (task.returned) {
            return;
        }
        // :1008 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG. Same width both sides.
        task.ccWorkArea.setCcardErrorMsg(PIC_X_CODEC.movePicX(task.wsReturnMsg,
                CardScreenState.CCARD_ERROR_MSG_LENGTH));

        // :1010-1013 - CARDDEMO-COMMAREA then WS-THIS-PROGCOMMAREA, at offset 161. The composed image is
        // built because its total width is what EXEC CICS RETURN LENGTH reports, and a payload that
        // cannot fit it is a payload the next turn could not restore.
        AccountUpdateRequest.CommArea thisProg = task.toCommArea();
        task.wsCommarea = codec.padToDeclaredWidth(
                codec.decodeImage(task.carddemoCommarea.toFixedWidth(codec),
                        "CARDDEMO-COMMAREA")
                        + codec.decodeImage(thisProg.encode(codec),
                                "WS-THIS-PROGCOMMAREA"),
                WS_COMMAREA_LENGTH);

        // :1015-1019 - EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA). The whole area
        // travels in the payload, which is what keeps the next turn stateless.
        //
        // The next target is CCARD-NEXT-PROG, CCARD-NEXT-MAPSET and CCARD-NEXT-MAP exactly as the work
        // area holds them, republished as response fields so a client does not have to reach into the
        // carried work area to learn where it is (gate G40). Two paragraphs write them:
        // 1000-PROCESS-INPUTS sets all three to this program's literals at :1032-1034, and
        // 3400-SEND-SCREEN sets the last two again at :3591-3592.
        //
        // So the triple is NOT uniform across the arms, and that asymmetry is the source's. On the
        // ordinary input arm all three name this screen. On the two fresh-entry arms, which reach
        // 3000-SEND-MAP without going through 1000-PROCESS-INPUTS, the mapset and map name this screen but
        // CCARD-NEXT-PROG is still the LOW-VALUES that INITIALIZE CC-WORK-AREA left at :866. Publishing
        // the work area as it stands rather than substituting this program is what keeps the payload a
        // faithful projection of it. The transaction the next key press reaches is decided by
        // EXEC CICS RETURN TRANSID(LIT-THISTRANID) regardless, and that is this transaction on every path
        // through this terminal. The PF03 arm does not reach here; it publishes CDEMO-TO-PROGRAM instead.
        task.cactupao = task.cactupao
                .withNextTarget(task.ccWorkArea.getCcardNextProg(),
                        task.ccWorkArea.getCcardNextMapset(),
                        task.ccWorkArea.getCcardNextMap())
                .withNavigationContext(task.carddemoCommarea)
                .withCardScreenState(task.ccWorkArea)
                .withCommArea(thisProg);
        task.returned = true;
    }

    // =================================================================================================
    // 1000-PROCESS-INPUTS and 1100-RECEIVE-MAP - app/cbl/COACTUPC.cbl:1025-1427.
    // =================================================================================================

    /**
     * {@code 1000-PROCESS-INPUTS} - {@code app/cbl/COACTUPC.cbl:1025-1035}.
     *
     * <p>Receive, edit, then publish four values into the work area: the message the edits produced and
     * this program's own program, mapset and map names, so the client's follow-up call comes back here.
     *
     * @param task this interaction's storage
     */
    void processInputs1000(Conversation task) {
        // :1026-1027 - PERFORM 1100-RECEIVE-MAP THRU 1100-RECEIVE-MAP-EXIT.
        receiveMap1100(task);
        // :1028-1029 - PERFORM 1200-EDIT-MAP-INPUTS THRU 1200-EDIT-MAP-INPUTS-EXIT.
        editMapInputs1200(task);
        // :1031 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG. Done here and again in COMMON-RETURN.
        task.ccWorkArea.setCcardErrorMsg(PIC_X_CODEC.movePicX(task.wsReturnMsg,
                CardScreenState.CCARD_ERROR_MSG_LENGTH));
        // :1032-1034 - the three next-target fields. LIT-THISMAPSET is PIC X(8) and CCARD-NEXT-MAPSET is
        // PIC X(7), so the trailing space is truncated away.
        task.ccWorkArea.setCcardNextProg(PIC_X_CODEC.movePicX(LIT_THISPGM,
                CardScreenState.CCARD_NEXT_PROG_LENGTH));
        task.ccWorkArea.setCcardNextMapset(PIC_X_CODEC.movePicX(LIT_THISMAPSET,
                CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(PIC_X_CODEC.movePicX(LIT_THISMAP,
                CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    /**
     * {@code 1100-RECEIVE-MAP} - {@code app/cbl/COACTUPC.cbl:1039-1426}.
     *
     * <p>{@code EXEC CICS RECEIVE MAP INTO(CACTUPAI)} and then 42 all-but-identical blocks, one per
     * editable field. Each has the same shape and the shape is the contract:
     *
     * <pre>
     *     IF  &lt;field&gt;I OF CACTUPAI = '*' OR &lt;field&gt;I OF CACTUPAI = SPACES
     *         MOVE LOW-VALUES TO ACUP-NEW-&lt;item&gt;
     *     ELSE
     *         MOVE &lt;field&gt;I OF CACTUPAI TO ACUP-NEW-&lt;item&gt;
     *     END-IF
     * </pre>
     *
     * <p>The {@code '*'} test matters: {@code CSSETATY} writes an asterisk into a blank field's output
     * item on the previous pass, so on the next pass the terminal sends that asterisk back and it must be
     * read as "still not supplied" rather than as a value. The comparison is against a wide item, so
     * COBOL space extends the one-character literal to the field's width first - which is why
     * {@code '*'} followed by padding matches and a literal asterisk anywhere else does not.
     *
     * <p>Two structural points. {@code INITIALIZE ACUP-NEW-DETAILS} at {@code :1047} clears the whole new
     * group first, so a field the terminal never sent ends the paragraph as spaces rather than as
     * whatever the previous turn left. And the early exit at {@code :1059-1061} - {@code IF
     * ACUP-DETAILS-NOT-FETCHED GO TO 1100-RECEIVE-MAP-EXIT} - means that on the search screen only the
     * account identifier is received at all; the other 41 fields keep the spaces
     * {@code INITIALIZE} left.
     *
     * @param task this interaction's storage
     */
    void receiveMap1100(Conversation task) {
        AccountUpdateRequest received = task.cactupai;

        // :1047 - INITIALIZE ACUP-NEW-DETAILS. Both halves, spaces throughout.
        task.acupNewAcct.initialize();
        task.acupNewCust.initialize();

        // :1051-1058 - the account identifier, which goes to two places: the work area's CC-ACCT-ID and
        // the new group's own eleven-character item.
        String acctsid = received.value(AccountUpdateRequest.ScreenField.ACCTSID);
        if (notSupplied(acctsid, AccountUpdateRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctId(lowValues(CardScreenState.CC_ACCT_ID_LENGTH));
            task.acupNewAcct.acctIdX = lowValues(AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
        } else {
            task.ccWorkArea.setCcAcctId(PIC_X_CODEC.movePicX(acctsid,
                    CardScreenState.CC_ACCT_ID_LENGTH));
            task.acupNewAcct.acctIdX = PIC_X_CODEC.movePicX(acctsid,
                    AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);
        }

        // :1059-1061 - GO TO 1100-RECEIVE-MAP-EXIT. On the search screen nothing else is received.
        if (task.acupChangeAction.isDetailsNotFetched()) {
            return;
        }

        // :1064-1069 - the active status.
        task.acupNewAcct.activeStatus = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSTTUS,
                AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);

        // :1072-1083, :1086-1098, :1101-1112, :1115-1127, :1130-1142 - the five monetary fields. Each
        // stages the screen text into a PIC X(15) work item and, when the text conforms, computes the
        // twelve-character span's numeric view. Both halves of that are the service's, because the
        // COMPUTE is the service's; this paragraph only records what came back.
        //
        // The prior value handed to the service is null at every site, and the -N span is assigned only
        // when the edit reports a COMPUTE. That pairing is a parity requirement, not a convenience:
        //
        //   * the staging item ACUP-NEW-CREDIT-LIMIT-X PIC X(15) (:412) and the detail span
        //     ACUP-NEW-CREDIT-LIMIT PIC X(12) / -N PIC S9(10)V99 (:766-768) are SEPARATE storage - the
        //     -X items live in ALPHA-VARS-FOR-DATA-EDITING and the span lives in ACUP-NEW-DETAILS;
        //   * INITIALIZE ACUP-NEW-DETAILS at :1047 has just set that twelve-byte span to SPACES;
        //   * on the not-supplied arm and on the TEST-NUMVAL-C failure arm the source reaches CONTINUE,
        //     so no COMPUTE runs and the span is STILL twelve spaces afterwards.
        //
        // 1205-COMPARE-OLD-NEW compares the CHARACTER views (ACUP-NEW-CREDIT-LIMIT = ACUP-OLD-
        // CREDIT-LIMIT, :1690-1697), so writing a zoned zero into an unsupplied span would make a
        // blanked field compare equal to a fetched value of zero and report NO-CHANGES-DETECTED where
        // the source reports CHANGE-HAS-OCCURRED. Leaving the span at spaces is the faithful outcome.
        AccountUpdateService.MonetaryEdit creditLimit = AccountUpdateService.computeCreditLimit(
                received.value(AccountUpdateRequest.ScreenField.ACRDLIM), null, PIC_X_CODEC);
        task.acupNewCreditLimitX = creditLimit.stagingImage();
        if (creditLimit.computed()) {
            task.acupNewAcct.setCreditLimitN(creditLimit.value());
        }

        AccountUpdateService.MonetaryEdit cashCreditLimit = AccountUpdateService.computeCashCreditLimit(
                received.value(AccountUpdateRequest.ScreenField.ACSHLIM), null, PIC_X_CODEC);
        task.acupNewCashCreditLimitX = cashCreditLimit.stagingImage();
        if (cashCreditLimit.computed()) {
            task.acupNewAcct.setCashCreditLimitN(cashCreditLimit.value());
        }

        // The current balance is the one site whose NUMVAL-C operand is the staging copy rather than the
        // map field (:1107-1108). The service carries that asymmetry; nothing here has to know it.
        AccountUpdateService.MonetaryEdit currBal = AccountUpdateService.computeCurrBal(
                received.value(AccountUpdateRequest.ScreenField.ACURBAL), null, PIC_X_CODEC);
        task.acupNewCurrBalX = currBal.stagingImage();
        if (currBal.computed()) {
            task.acupNewAcct.setCurrBalN(currBal.value());
        }

        AccountUpdateService.MonetaryEdit currCycCredit = AccountUpdateService.computeCurrCycCredit(
                received.value(AccountUpdateRequest.ScreenField.ACRCYCR), null, PIC_X_CODEC);
        task.acupNewCurrCycCreditX = currCycCredit.stagingImage();
        if (currCycCredit.computed()) {
            task.acupNewAcct.setCurrCycCreditN(currCycCredit.value());
        }

        AccountUpdateService.MonetaryEdit currCycDebit = AccountUpdateService.computeCurrCycDebit(
                received.value(AccountUpdateRequest.ScreenField.ACRCYDB), null, PIC_X_CODEC);
        task.acupNewCurrCycDebitX = currCycDebit.stagingImage();
        if (currCycDebit.computed()) {
            task.acupNewAcct.setCurrCycDebitN(currCycDebit.value());
        }

        // :1146-1168 - the open date, as three separate screen fields. They stay split on the wire, and
        // the eight-character span they write into is the redefinition that joins them.
        task.acupNewAcct.setOpenYear(receiveField(received, AccountUpdateRequest.ScreenField.OPNYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewAcct.setOpenMon(receiveField(received, AccountUpdateRequest.ScreenField.OPNMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewAcct.setOpenDay(receiveField(received, AccountUpdateRequest.ScreenField.OPNDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        // :1171-1193 - the expiry date.
        task.acupNewAcct.setExpYear(receiveField(received, AccountUpdateRequest.ScreenField.EXPYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewAcct.setExpMon(receiveField(received, AccountUpdateRequest.ScreenField.EXPMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewAcct.setExpDay(receiveField(received, AccountUpdateRequest.ScreenField.EXPDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        // :1196-1218 - the reissue date.
        task.acupNewAcct.setReissueYear(receiveField(received,
                AccountUpdateRequest.ScreenField.RISYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewAcct.setReissueMon(receiveField(received, AccountUpdateRequest.ScreenField.RISMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewAcct.setReissueDay(receiveField(received, AccountUpdateRequest.ScreenField.RISDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        // :1221-1228 - the account group identifier.
        task.acupNewAcct.groupId = receiveField(received, AccountUpdateRequest.ScreenField.AADDGRP,
                AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);

        // :1234-1240 - the customer identifier, which the screen protects and the program still receives.
        task.acupNewCust.custIdX = receiveField(received, AccountUpdateRequest.ScreenField.ACSTNUM,
                AccountUpdateRequest.CustSnapshot.CUST_ID_LENGTH);

        // :1244-1266 - the three parts of the social security number.
        task.acupNewCust.setSsn1(receiveField(received, AccountUpdateRequest.ScreenField.ACTSSN1,
                AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH));
        task.acupNewCust.setSsn2(receiveField(received, AccountUpdateRequest.ScreenField.ACTSSN2,
                AccountUpdateRequest.CustSnapshot.SSN_PART_2_LENGTH));
        task.acupNewCust.setSsn3(receiveField(received, AccountUpdateRequest.ScreenField.ACTSSN3,
                AccountUpdateRequest.CustSnapshot.SSN_PART_3_LENGTH));

        // :1270-1292 - the date of birth.
        task.acupNewCust.setDobYear(receiveField(received, AccountUpdateRequest.ScreenField.DOBYEAR,
                AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH));
        task.acupNewCust.setDobMon(receiveField(received, AccountUpdateRequest.ScreenField.DOBMON,
                AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH));
        task.acupNewCust.setDobDay(receiveField(received, AccountUpdateRequest.ScreenField.DOBDAY,
                AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH));

        // :1296-1302 - the FICO score, as characters. Its numeric view is a redefinition of these three.
        task.acupNewCust.ficoScoreX = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSTFCO,
                AccountUpdateRequest.CustSnapshot.FICO_SCORE_LENGTH);

        // :1306-1330 - the three names.
        task.acupNewCust.firstName = receiveField(received, AccountUpdateRequest.ScreenField.ACSFNAM,
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        task.acupNewCust.middleName = receiveField(received, AccountUpdateRequest.ScreenField.ACSMNAM,
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        task.acupNewCust.lastName = receiveField(received, AccountUpdateRequest.ScreenField.ACSLNAM,
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);

        // :1334-1382 - the address. Note that the city screen field feeds ADDR-LINE-3, not ADDR-LINE-2:
        // the map's ACSCITY is the third address line and ACSADL2 is the second, which is why the city
        // edit at :1612-1618 names ACUP-NEW-CUST-ADDR-LINE-3.
        task.acupNewCust.addrLine1 = receiveField(received, AccountUpdateRequest.ScreenField.ACSADL1,
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        task.acupNewCust.addrLine2 = receiveField(received, AccountUpdateRequest.ScreenField.ACSADL2,
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        task.acupNewCust.addrLine3 = receiveField(received, AccountUpdateRequest.ScreenField.ACSCITY,
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        task.acupNewCust.addrStateCd = receiveField(received, AccountUpdateRequest.ScreenField.ACSSTTE,
                AccountUpdateRequest.CustSnapshot.ADDR_STATE_CD_LENGTH);
        task.acupNewCust.addrCountryCd = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSCTRY,
                AccountUpdateRequest.CustSnapshot.ADDR_COUNTRY_CD_LENGTH);
        // The zip screen field is five characters and the work item is ten, so the move space pads on the
        // right - which is what makes the zip edit's five-character window meaningful.
        task.acupNewCust.addrZip = receiveField(received, AccountUpdateRequest.ScreenField.ACSZIPC,
                AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH);

        // :1386-1426 - the six telephone parts, three per number, each writing through the redefinition.
        task.acupNewCust.setPhoneNum1A(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH1A,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH));
        task.acupNewCust.setPhoneNum1B(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH1B,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH));
        task.acupNewCust.setPhoneNum1C(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH1C,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH));
        task.acupNewCust.setPhoneNum2A(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH2A,
                AccountUpdateRequest.CustSnapshot.PHONE_AREA_CODE_LENGTH));
        task.acupNewCust.setPhoneNum2B(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH2B,
                AccountUpdateRequest.CustSnapshot.PHONE_PREFIX_LENGTH));
        task.acupNewCust.setPhoneNum2C(receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPH2C,
                AccountUpdateRequest.CustSnapshot.PHONE_LINE_NUMBER_LENGTH));

        // :1400-1406 - the government-issued identifier.
        task.acupNewCust.govtIssuedId = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSGOVT,
                AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);

        // :1410-1416 - the electronic funds transfer account identifier.
        task.acupNewCust.eftAccountId = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSEFTC,
                AccountUpdateRequest.CustSnapshot.EFT_ACCOUNT_ID_LENGTH);

        // :1420-1426 - the primary card holder indicator.
        task.acupNewCust.priHolderInd = receiveField(received,
                AccountUpdateRequest.ScreenField.ACSPFLG,
                AccountUpdateRequest.CustSnapshot.PRI_HOLDER_IND_LENGTH);
    }

    /**
     * One of {@code 1100-RECEIVE-MAP}'s 42 identical blocks.
     *
     * @param received     the input map area
     * @param field        the screen field to read
     * @param targetLength the receiving work item's declared width
     * @return {@code LOW-VALUES} at {@code targetLength} when the field is {@code '*'} or spaces,
     *         otherwise the field moved to {@code targetLength} by the {@code PIC X} rule
     */
    String receiveField(AccountUpdateRequest received, AccountUpdateRequest.ScreenField field,
            int targetLength) {
        String value = received.value(field);
        if (notSupplied(value, field.length())) {
            return lowValues(targetLength);
        }
        return PIC_X_CODEC.movePicX(value, targetLength);
    }

    /**
     * {@code IF <field>I OF CACTUPAI = '*' OR <field>I OF CACTUPAI = SPACES}.
     *
     * <p>Both comparisons are against the field at its declared width, so the one-character
     * {@code '*'} literal is space extended before the comparison - which is exactly the value
     * {@code CSSETATY} wrote there on the previous pass, an asterisk followed by padding.
     *
     * @param value        the field as received; {@code null} is an omitted payload member, which
     *                     reaches the program as spaces
     * @param fieldLength  the field's declared width
     * @return whether the field carries nothing the program should treat as a value
     */
    static boolean notSupplied(String value, int fieldLength) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, fieldLength);
        return image.equals(PIC_X_CODEC.movePicX(AccountUpdateService.NOT_SUPPLIED_MARKER, fieldLength))
                || image.equals(spaces(fieldLength));
    }

    // =================================================================================================
    // 1200-EDIT-MAP-INPUTS - app/cbl/COACTUPC.cbl:1429-1676. The edit driver, and the paragraph whose
    // ORDER is most load bearing: WS-RETURN-MSG is set only when it is still off, so the FIRST edit to
    // fail is the one whose message the operator reads. Reordering these calls changes the message.
    // =================================================================================================

    /**
     * {@code 1200-EDIT-MAP-INPUTS} - {@code app/cbl/COACTUPC.cbl:1429-1676}.
     *
     * <p>Two entirely different jobs behind one label, chosen by {@code ACUP-DETAILS-NOT-FETCHED}:
     *
     * <ul>
     *   <li><strong>Details not fetched</strong> ({@code :1433-1446}) - only the account identifier is
     *       validated. Then {@code MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA} discards any carried snapshot,
     *       a blank filter is reported as {@code NO-SEARCH-CRITERIA-RECEIVED}, and the paragraph exits.
     *       Note that the low-values move covers the <em>account</em> half only, not the customer
     *       half.</li>
     *   <li><strong>Details fetched</strong> ({@code :1451-1675}) - five conditions are asserted true
     *       without being tested, the two groups are compared, and if anything changed all 24 field
     *       edits run in source order followed by one cross-field edit.</li>
     * </ul>
     *
     * <p>The five unconditional {@code SET}s at {@code :1452-1458} deserve naming: reaching this point
     * <em>means</em> the data was fetched, so the program asserts {@code FOUND-ACCOUNT-DATA},
     * {@code FOUND-ACCT-IN-MASTER}, {@code FLG-ACCTFILTER-ISVALID}, {@code FOUND-CUST-IN-MASTER} and
     * {@code FLG-CUSTFILTER-ISVALID} rather than re-deriving them. {@code FOUND-ACCOUNT-DATA} is an
     * {@code 88}-level on {@code WS-INFO-MSG}, so that first {@code SET} also writes the information
     * message - which {@code 3250-SETUP-INFOMSG} may then overwrite.
     *
     * @param task this interaction's storage
     */
    void editMapInputs1200(Conversation task) {
        // :1431 - SET INPUT-OK TO TRUE.
        task.wsInputFlag = INPUT_OK;

        if (task.acupChangeAction.isDetailsNotFetched()) {
            // :1435-1436 - validate the search key.
            editAccount1210(task);
            // :1444 - MOVE LOW-VALUES TO ACUP-OLD-ACCT-DATA. The account half only.
            task.acupOldAcct.moveLowValues();
            // :1441-1443 - a blank filter gets its own message, which overwrites 1210's.
            if (task.flgAcctfilterBlank()) {
                task.wsReturnMsg = atReturnWidth(MSG_NO_SEARCH_CRITERIA_RECEIVED);
            }
            // :1446 - GO TO 1200-EDIT-MAP-INPUTS-EXIT.
            return;
        }

        // :1452-1458 - the five assertions.
        task.wsInfoMsg = atInfoWidth(INFO_FOUND_ACCOUNT_DATA);
        task.wsAccountMasterReadFlag = FOUND_IN_MASTER;
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        task.wsCustMasterReadFlag = FOUND_IN_MASTER;
        task.wsEditCustFlag = FLG_FILTER_ISVALID;

        // :1461-1462 - PERFORM 1205-COMPARE-OLD-NEW THRU 1205-COMPARE-OLD-NEW-EXIT.
        compareOldNew1205(task);

        // :1464-1469 - three ways to skip every edit. NO-CHANGES-FOUND means nothing was typed;
        // CHANGES-OK-NOT-CONFIRMED and CHANGES-OKAYED-AND-DONE mean the edits already ran on a previous
        // pass and their verdicts must not be recomputed. All three clear WS-NON-KEY-FLAGS first, so the
        // screen comes back with no highlights.
        if (task.noChangesFound()
                || task.acupChangeAction.isChangesOkNotConfirmed()
                || task.acupChangeAction.isChangesOkayedAndDone()) {
            task.clearNonKeyFlags();
            return;
        }

        // :1471 - SET ACUP-CHANGES-NOT-OK TO TRUE. Pessimistic: the state is "bad" until the last
        // statement of the paragraph proves otherwise.
        task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesNotOk();

        // :1473-1477 - the active status, through the yes/no edit.
        task.wsEditVariableName = editVariableName(NAME_ACCOUNT_STATUS);
        task.wsEditYesNo = PIC_X_CODEC.movePicX(task.acupNewAcct.activeStatus, 1);
        editYesno1220(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSTTUS, task.wsEditYesNo);

        // :1479-1483 - the open date, through CSUTLDPY's EDIT-DATE-CCYYMMDD. Its three-character flag
        // group is moved whole into WS-EDIT-OPEN-DATE-FLGS, which is why the year, month and day flags
        // arrive together.
        editDate(task, NAME_OPEN_DATE, task.acupNewAcct.openDate,
                AccountUpdateResponse.ScreenField.OPNYEAR, AccountUpdateResponse.ScreenField.OPNMON,
                AccountUpdateResponse.ScreenField.OPNDAY, false);

        // :1485-1489 - the credit limit, through the signed-number edit. Its argument is the PIC X(15)
        // staging copy 1100-RECEIVE-MAP left, not the twelve-character span.
        task.wsEditVariableName = editVariableName(NAME_CREDIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCreditLimitX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACRDLIM, task.wsFlgSignedNumberEdit);

        // :1491-1495 - the expiry date.
        editDate(task, NAME_EXPIRY_DATE, task.acupNewAcct.expiraionDate,
                AccountUpdateResponse.ScreenField.EXPYEAR, AccountUpdateResponse.ScreenField.EXPMON,
                AccountUpdateResponse.ScreenField.EXPDAY, false);

        // :1497-1502 - the cash credit limit.
        task.wsEditVariableName = editVariableName(NAME_CASH_CREDIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCashCreditLimitX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSHLIM, task.wsFlgSignedNumberEdit);

        // :1504-1508 - the reissue date.
        editDate(task, NAME_REISSUE_DATE, task.acupNewAcct.reissueDate,
                AccountUpdateResponse.ScreenField.RISYEAR, AccountUpdateResponse.ScreenField.RISMON,
                AccountUpdateResponse.ScreenField.RISDAY, false);

        // :1510-1514 - the current balance.
        task.wsEditVariableName = editVariableName(NAME_CURRENT_BALANCE);
        task.wsEditSignedNumber9v2X = task.acupNewCurrBalX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACURBAL, task.wsFlgSignedNumberEdit);

        // :1516-1521 - the current cycle credit limit. Its name is twenty-six characters and
        // WS-EDIT-VARIABLE-NAME is PIC X(25), so the MOVE truncates the final 't': the operator reads
        // "Current Cycle Credit Limi must be supplied." That is the program's output and it is preserved.
        task.wsEditVariableName = editVariableName(NAME_CURRENT_CYCLE_CREDIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCurrCycCreditX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACRCYCR, task.wsFlgSignedNumberEdit);

        // :1523-1528 - the current cycle debit limit, whose name is exactly twenty-five characters.
        task.wsEditVariableName = editVariableName(NAME_CURRENT_CYCLE_DEBIT_LIMIT);
        task.wsEditSignedNumber9v2X = task.acupNewCurrCycDebitX;
        editSigned9v2At1250(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACRCYDB, task.wsFlgSignedNumberEdit);

        // :1530-1532 - the social security number. The name set here is immediately overwritten three
        // times inside the paragraph, so 'SSN' never reaches a message; the MOVE is reproduced anyway.
        task.wsEditVariableName = editVariableName(NAME_SSN);
        editUsSsn1265(task);
        // The three parts of WS-EDIT-US-SSN-FLGS (app/cbl/COACTUPC.cbl:132-146) published into
        // WS-NON-KEY-FLAGS, exactly as the two telephone numbers publish theirs below. 3280-SETUP-ATTRS
        // copies CSSETATY three times for these fields - EDIT-US-SSN-PART1, -PART2 and -PART3 at
        // :3296-3313 - and 3390-SETUP-CURSOR tests FLG-EDIT-US-SSN-PARTn-NOT-OK and -BLANK at :3077-3088,
        // so a rejected part must redden ACTSSN1, ACTSSN2 or ACTSSN3 and claim the cursor. Without this
        // publication the flags were written and never read, and a non-numeric SSN part was rejected into
        // WS-RETURN-MSG while the field itself stayed at its default colour.
        task.setFlag(AccountUpdateResponse.ScreenField.ACTSSN1, task.wsEditUsSsnPart1Flgs);
        task.setFlag(AccountUpdateResponse.ScreenField.ACTSSN2, task.wsEditUsSsnPart2Flgs);
        task.setFlag(AccountUpdateResponse.ScreenField.ACTSSN3, task.wsEditUsSsnPart3Flgs);

        // :1534-1542 - the date of birth: the generic date edit, and then, only if it passed, the
        // additional not-in-the-future check. The second call overwrites the flag group.
        editDate(task, NAME_DATE_OF_BIRTH, task.acupNewCust.dobYyyyMmDd,
                AccountUpdateResponse.ScreenField.DOBYEAR, AccountUpdateResponse.ScreenField.DOBMON,
                AccountUpdateResponse.ScreenField.DOBDAY, true);

        // :1544-1557 - the FICO score: the numeric edit at three characters, and then, only if it passed,
        // the 300-to-850 range check.
        task.wsEditVariableName = editVariableName(NAME_FICO_SCORE);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ficoScoreX);
        task.wsEditAlphanumLength = EDIT_LENGTH_3;
        editNumReqd1245(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSTFCO, task.wsEditAlphanumOnlyFlags);
        if (task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSTFCO)) {
            editFicoScore1275(task);
        }

        // :1559-1565 - the first name, mandatory and alphabetic.
        task.wsEditVariableName = editVariableName(NAME_FIRST_NAME);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.firstName);
        task.wsEditAlphanumLength = EDIT_LENGTH_25;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSFNAM, task.wsEditAlphaOnlyFlags);

        // :1567-1573 - the middle name, OPTIONAL and alphabetic. This is the only field that uses 1235.
        task.wsEditVariableName = editVariableName(NAME_MIDDLE_NAME);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.middleName);
        task.wsEditAlphanumLength = EDIT_LENGTH_25;
        editAlphaOpt1235(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSMNAM, task.wsEditAlphaOnlyFlags);

        // :1575-1581 - the last name.
        task.wsEditVariableName = editVariableName(NAME_LAST_NAME);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.lastName);
        task.wsEditAlphanumLength = EDIT_LENGTH_25;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSLNAM, task.wsEditAlphaOnlyFlags);

        // :1583-1589 - address line 1, through the mandatory-only edit: present is enough, and any
        // character is allowed. This is the only field that uses 1215.
        task.wsEditVariableName = editVariableName(NAME_ADDRESS_LINE_1);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrLine1);
        task.wsEditAlphanumLength = EDIT_LENGTH_50;
        editMandatory1215(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSADL1, task.wsEditMandatoryFlags);

        // :1591-1602 - the state code: alphabetic first, and then, only if that passed, the lookup.
        // The guard reads FLG-ALPHA-ISVALID - the generic flag - rather than the state's own copy, which
        // is the same value at this point because the MOVE above it just made it so.
        task.wsEditVariableName = editVariableName(NAME_STATE);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrStateCd);
        task.wsEditAlphanumLength = EDIT_LENGTH_2;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSSTTE, task.wsEditAlphaOnlyFlags);
        if (FLG_ISVALID.equals(task.wsEditAlphaOnlyFlags)) {
            editUsStateCd1270(task);
        }

        // :1604-1610 - the zip code, numeric at five characters.
        task.wsEditVariableName = editVariableName(NAME_ZIP);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrZip);
        task.wsEditAlphanumLength = EDIT_LENGTH_5;
        editNumReqd1245(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSZIPC, task.wsEditAlphanumOnlyFlags);

        // :1611-1618 - the city, which is ADDR-LINE-3. The commented-out MOVE 'Address Line 2' directly
        // above records that address line 2 is never edited at all.
        task.wsEditVariableName = editVariableName(NAME_CITY);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrLine3);
        task.wsEditAlphanumLength = EDIT_LENGTH_50;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSCITY, task.wsEditAlphaOnlyFlags);

        // :1620-1626 - the country code.
        task.wsEditVariableName = editVariableName(NAME_COUNTRY);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.addrCountryCd);
        task.wsEditAlphanumLength = EDIT_LENGTH_3;
        editAlphaReqd1225(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSCTRY, task.wsEditAlphaOnlyFlags);

        // :1628-1633 - the first telephone number. The three-character flag group comes back whole.
        task.wsEditVariableName = editVariableName(NAME_PHONE_NUMBER_1);
        task.wsEditUsPhoneNum = PIC_X_CODEC.movePicX(task.acupNewCust.phoneNum1,
                WS_EDIT_US_PHONE_NUM_LENGTH);
        editUsPhoneNum1260(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH1A, task.wsEditUsPhoneaFlg);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH1B, task.wsEditEditUsPhoneb);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH1C, task.wsEditEditPhonec);

        // :1635-1640 - the second telephone number, through the same paragraph.
        task.wsEditVariableName = editVariableName(NAME_PHONE_NUMBER_2);
        task.wsEditUsPhoneNum = PIC_X_CODEC.movePicX(task.acupNewCust.phoneNum2,
                WS_EDIT_US_PHONE_NUM_LENGTH);
        editUsPhoneNum1260(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH2A, task.wsEditUsPhoneaFlg);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH2B, task.wsEditEditUsPhoneb);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPH2C, task.wsEditEditPhonec);

        // :1642-1648 - the electronic funds transfer account identifier, numeric at ten characters.
        task.wsEditVariableName = editVariableName(NAME_EFT_ACCOUNT_ID);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.eftAccountId);
        task.wsEditAlphanumLength = EDIT_LENGTH_10;
        editNumReqd1245(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSEFTC, task.wsEditAlphanumOnlyFlags);

        // :1650-1655 - the primary card holder indicator, through the yes/no edit.
        task.wsEditVariableName = editVariableName(NAME_PRIMARY_CARD_HOLDER);
        task.wsEditYesNo = PIC_X_CODEC.movePicX(task.acupNewCust.priHolderInd, 1);
        editYesno1220(task);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSPFLG, task.wsEditYesNo);

        // :1658-1662 - the one cross-field edit, and its guard is why it comes last: the state and the
        // zip must each be individually valid before the pair is meaningful.
        if (task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSSTTE)
                && task.flagIsvalid(AccountUpdateResponse.ScreenField.ACSZIPC)) {
            editUsStateZipCd1280(task);
        }

        // :1664-1668 - IF INPUT-ERROR CONTINUE ELSE SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE. The
        // pessimistic state set at :1471 stands unless every edit passed.
        if (!task.inputError()) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
        }
    }

    // =================================================================================================
    // The edit paragraphs - app/cbl/COACTUPC.cbl:1681-2559.
    //
    // Sixteen paragraphs, translated one to one and kept in source order so a reviewer can read the two
    // side by side. Every one is package visible with no HTTP and no repository in its path, which is
    // what makes the branch bar reachable from a plain JUnit test (practice B10, gates G49 and G51).
    //
    // Three COBOL mechanisms recur and are worth stating once:
    //
    //   * "GO TO <paragraph>-EXIT" is a return. All 51 GO TOs in this program are an -EXIT early exit,
    //     the shared COMMON-RETURN terminal, or one of the seven forward stage jumps inside
    //     1260-EDIT-US-PHONE-NUM; none is loop forming (rule R7).
    //   * "IF WS-RETURN-MSG-OFF / STRING ... INTO WS-RETURN-MSG" means first message wins: once any edit
    //     has put text in the 75-character item, every later edit still sets its flag but leaves the
    //     text alone. That is why the operator sees exactly one message however many fields are wrong.
    //   * A flag item and its 88-levels are one character. ISVALID is LOW-VALUES, NOT-OK is '0' and
    //     BLANK is 'B' throughout, so a flag is compared against a constant rather than an enum - the
    //     byte is what CSSETATY tests and what the response carries.
    // =================================================================================================

    /**
     * {@code IF WS-RETURN-MSG-OFF / STRING ... DELIMITED BY SIZE INTO WS-RETURN-MSG} - the guarded
     * message assignment that appears at 30 sites across the edit paragraphs.
     *
     * <p>Two things make a plain assignment wrong here. The guard is {@code WS-RETURN-MSG-OFF}, so only
     * the first edit to fail supplies the text. And the receiver is {@code PIC X(75)}: a COBOL
     * {@code STRING ... INTO} leaves the positions it does not reach unchanged, which - because the item
     * is all spaces whenever the guard passes - is the same result as a space-padded move, so the move
     * is written and the identity is documented rather than assumed.
     *
     * @param task    this interaction's storage
     * @param message the concatenation of the source's {@code STRING} operands
     */
    void stringIntoReturnMsgIfOff(Conversation task, String message) {
        if (task.returnMsgOff()) {
            task.wsReturnMsg = atReturnWidth(message);
        }
    }

    /**
     * {@code WS-EDIT-ALPHANUM-ONLY(1:WS-EDIT-ALPHANUM-LENGTH)} - the reference-modified window the five
     * generic edits work on.
     *
     * <p>The staging item is {@code PIC X(256)} and the length is set by the caller immediately before
     * the {@code PERFORM}, so the window is always well defined; padding the staging item to 256 in
     * {@link #stagingItem} is what guarantees that.
     *
     * @param task this interaction's storage
     * @return the first {@code WS-EDIT-ALPHANUM-LENGTH} characters of the staging item
     */
    static String editWindow(Conversation task) {
        return task.wsEditAlphanumOnly.substring(0, task.wsEditAlphanumLength);
    }

    /**
     * The three-way "not supplied" test the five generic edits share, verbatim:
     * {@code EQUAL LOW-VALUES OR EQUAL SPACES OR FUNCTION LENGTH(FUNCTION TRIM(...)) = 0}.
     *
     * <p>The third arm is not redundant with the second in general - it also catches a window that is
     * neither uniformly {@code LOW-VALUES} nor uniformly spaces but still trims away - and it is
     * evaluated because the source evaluates it.
     *
     * @param window the reference-modified window
     * @return {@code true} when the source takes its not-supplied branch
     */
    static boolean windowNotSupplied(String window) {
        return isAll(window, LOW_VALUE) || isAll(window, SPACE) || trimmedLength(window) == 0;
    }

    /**
     * A comparison of an alphanumeric item against a figurative constant, which COBOL extends to the
     * item's length.
     *
     * @param value     the item
     * @param character the figurative constant's character
     * @return {@code true} when every character matches; {@code true} for a zero-length item, which is
     *         what comparing an empty item against any figurative constant yields
     */
    static boolean isAll(String value, char character) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code FUNCTION LENGTH(FUNCTION TRIM(<item>))}.
     *
     * <p>{@code FUNCTION TRIM} removes leading and trailing <em>spaces</em> only - a {@code LOW-VALUE}
     * is not a space and survives the trim, which is exactly why the not-supplied test needs its
     * {@code LOW-VALUES} arm as well as this one.
     *
     * @param value the item
     * @return the trimmed length, zero for an all-space item
     */
    static int trimmedLength(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return end - start;
    }

    /**
     * {@code INSPECT <window> CONVERTING <from> TO <all spaces>}.
     *
     * <p>The {@code TO} operand is {@code LIT-ALPHA-SPACES-TO} or {@code LIT-ALPHANUM-SPACES-TO}, both
     * declared {@code VALUE SPACES} and never assigned, so every character present in {@code from}
     * becomes a space. What is left is precisely the characters the edit disallows, which is why the
     * following {@code TRIM} length of zero means "acceptable".
     *
     * @param window the window to convert; the source mutates it in place
     * @param from   {@code LIT-ALL-ALPHA-FROM} or {@code LIT-ALL-ALPHANUM-FROM}
     * @return the converted window, the same length as the input
     */
    static String inspectConverting(String window, String from) {
        StringBuilder converted = new StringBuilder(window.length());
        for (int index = 0; index < window.length(); index++) {
            char character = window.charAt(index);
            converted.append(from.indexOf(character) >= 0 ? SPACE : character);
        }
        return converted.toString();
    }

    /**
     * {@code IF <alphanumeric item> IS NUMERIC}.
     *
     * <p>For an unsigned {@code PIC X} item the class condition is true only when every character is a
     * digit. An empty item is not numeric, and neither is one containing a space, a sign or a decimal
     * point - which is what makes this test stricter than {@code TEST-NUMVAL}.
     *
     * @param value the item
     * @return {@code true} when the class condition holds
     */
    static boolean isNumericPicX(String value) {
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

    /**
     * {@code IF FUNCTION NUMVAL(<item>) = 0}, evaluated only on a path where the class condition has
     * already proved the item is all digits.
     *
     * <p>Uses {@link BigDecimal} rather than a {@code long} so an item wider than eighteen digits cannot
     * overflow: {@code WS-EDIT-ALPHANUM-ONLY} is {@code PIC X(256)} and nothing in the source bounds the
     * length the caller may set.
     *
     * @param digits an all-digit item
     * @return {@code true} when its value is zero
     */
    static boolean numvalIsZero(String digits) {
        return new BigDecimal(digits).signum() == 0;
    }

    /**
     * {@code 1205-COMPARE-OLD-NEW} - {@code app/cbl/COACTUPC.cbl:1681-1780}.
     *
     * <p>Sets {@code WS-DATACHANGED-FLAG} by comparing the fetched copy of the record against the copy
     * the screen produced. Two guarded blocks, the account group then the customer group; either one
     * finding a difference sets {@code CHANGE-HAS-OCCURRED} and returns, and only reaching the end of
     * the second sets {@code NO-CHANGES-DETECTED}.
     *
     * <p>The comparison is deliberately inconsistent between fields and the inconsistency is behaviour:
     *
     * <ul>
     *   <li>the account identifier, the three dates, the five monetary spans, the six telephone parts,
     *       the SSN, the date of birth, the EFT identifier and the FICO score are compared
     *       <strong>raw</strong> - byte for byte at their declared width;</li>
     *   <li>the active status is compared {@code UPPER-CASE} but <strong>not</strong> trimmed;</li>
     *   <li>the group identifier, the customer identifier, the three names, the three address lines, the
     *       state, the country, the zip, the government identifier and the primary-holder indicator are
     *       compared {@code UPPER-CASE(TRIM(...))} - so leading and trailing space, and letter case, are
     *       not changes for those fields but are changes for the others.</li>
     * </ul>
     *
     * <p>Because the monetary spans are compared raw, a field the operator blanked - whose span
     * {@code 1100-RECEIVE-MAP} left at spaces - differs from a fetched zero, and the source reports a
     * change. See the note in {@link #receiveMap1100}.
     *
     * @param task this interaction's storage
     */
    void compareOldNew1205(Conversation task) {
        // :1682 - SET NO-CHANGES-FOUND TO TRUE. Optimistic, and reversed by either block below.
        task.wsDatachangedFlag = NO_CHANGES_FOUND;

        AcctDataArea newAcct = task.acupNewAcct;
        AcctDataArea oldAcct = task.acupOldAcct;

        // :1684-1706 - the account group.
        boolean acctSame = newAcct.acctIdX.equals(oldAcct.acctIdX)
                && upperCase(newAcct.activeStatus).equals(upperCase(oldAcct.activeStatus))
                && newAcct.currBal.equals(oldAcct.currBal)
                && newAcct.creditLimit.equals(oldAcct.creditLimit)
                && newAcct.cashCreditLimit.equals(oldAcct.cashCreditLimit)
                && newAcct.openDate.equals(oldAcct.openDate)
                && newAcct.expiraionDate.equals(oldAcct.expiraionDate)
                && newAcct.reissueDate.equals(oldAcct.reissueDate)
                && newAcct.currCycCredit.equals(oldAcct.currCycCredit)
                && newAcct.currCycDebit.equals(oldAcct.currCycDebit)
                && upperTrim(newAcct.groupId).equals(upperTrim(oldAcct.groupId));
        if (!acctSame) {
            // :1706-1708 - SET CHANGE-HAS-OCCURRED, GO TO 1205-COMPARE-OLD-NEW-EXIT.
            task.wsDatachangedFlag = CHANGE_HAS_OCCURRED;
            return;
        }

        CustDataArea newCust = task.acupNewCust;
        CustDataArea oldCust = task.acupOldCust;

        // :1712-1770 - the customer group.
        boolean custSame = upperTrim(newCust.custIdX).equals(upperTrim(oldCust.custIdX))
                && upperTrim(newCust.firstName).equals(upperTrim(oldCust.firstName))
                && upperTrim(newCust.middleName).equals(upperTrim(oldCust.middleName))
                && upperTrim(newCust.lastName).equals(upperTrim(oldCust.lastName))
                && upperTrim(newCust.addrLine1).equals(upperTrim(oldCust.addrLine1))
                && upperTrim(newCust.addrLine2).equals(upperTrim(oldCust.addrLine2))
                && upperTrim(newCust.addrLine3).equals(upperTrim(oldCust.addrLine3))
                && upperTrim(newCust.addrStateCd).equals(upperTrim(oldCust.addrStateCd))
                && upperTrim(newCust.addrCountryCd).equals(upperTrim(oldCust.addrCountryCd))
                && upperTrim(newCust.addrZip).equals(upperTrim(oldCust.addrZip))
                && newCust.phoneNum1A().equals(oldCust.phoneNum1A())
                && newCust.phoneNum1B().equals(oldCust.phoneNum1B())
                && newCust.phoneNum1C().equals(oldCust.phoneNum1C())
                && newCust.phoneNum2A().equals(oldCust.phoneNum2A())
                && newCust.phoneNum2B().equals(oldCust.phoneNum2B())
                && newCust.phoneNum2C().equals(oldCust.phoneNum2C())
                && newCust.ssnX.equals(oldCust.ssnX)
                && upperTrim(newCust.govtIssuedId).equals(upperTrim(oldCust.govtIssuedId))
                && newCust.dobYyyyMmDd.equals(oldCust.dobYyyyMmDd)
                && newCust.eftAccountId.equals(oldCust.eftAccountId)
                && upperTrim(newCust.priHolderInd).equals(upperTrim(oldCust.priHolderInd))
                && newCust.ficoScoreX.equals(oldCust.ficoScoreX);
        if (custSame) {
            // :1770 - SET NO-CHANGES-DETECTED TO TRUE. This is an 88 on WS-RETURN-MSG, so it puts the
            // literal text in the message item; it is not a flag.
            task.wsReturnMsg = atReturnWidth(MSG_NO_CHANGES_DETECTED);
        } else {
            // :1772-1774 - SET CHANGE-HAS-OCCURRED, GO TO 1205-COMPARE-OLD-NEW-EXIT.
            task.wsDatachangedFlag = CHANGE_HAS_OCCURRED;
        }
    }

    /**
     * {@code FUNCTION UPPER-CASE(<item>)} at the item's own width.
     *
     * <p>{@link String#toUpperCase()} with an explicit {@link java.util.Locale#ROOT} rather than the
     * default locale, so a Turkish default cannot map {@code 'i'} to a dotted capital and turn a
     * comparison of two identical fields into a difference.
     *
     * @param value the item
     * @return the item upper cased
     */
    static String upperCase(String value) {
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * {@code FUNCTION UPPER-CASE(FUNCTION TRIM(<item>))}.
     *
     * @param value the item
     * @return the item trimmed of leading and trailing spaces, then upper cased
     */
    static String upperTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return upperCase(value.substring(start, end));
    }

    /**
     * {@code 1210-EDIT-ACCOUNT} - {@code app/cbl/COACTUPC.cbl:1783-1820}.
     *
     * <p>The search-key edit, and the only edit that runs on the entry screen. Three outcomes:
     *
     * <ol>
     *   <li>blank - {@code FLG-ACCTFILTER-BLANK}, the prompt message, and <strong>zeroes</strong> moved
     *       to both {@code CDEMO-ACCT-ID} and {@code ACUP-NEW-ACCT-ID}. The receiver named is the
     *       {@code PIC 9(11)} redefinition, so the eleven characters become {@code "00000000000"};</li>
     *   <li>present but not an eleven-digit non-zero number - {@code INPUT-ERROR} with the flag left at
     *       {@code NOT-OK} from {@code :1784}, the two-part message, and zeroes moved to
     *       {@code CDEMO-ACCT-ID} only - {@code ACUP-NEW-ACCT-ID} keeps the characters moved at
     *       {@code :1800};</li>
     *   <li>valid - the identifier is copied to {@code CDEMO-ACCT-ID} and the flag becomes
     *       {@code ISVALID}.</li>
     * </ol>
     *
     * <p>The {@code MOVE} at {@code :1800} happens <strong>before</strong> the numeric test, so an
     * invalid identifier still reaches the new group and still comes back on the screen - which is what
     * lets the operator correct a typo rather than retype the field.
     *
     * @param task this interaction's storage
     */
    void editAccount1210(Conversation task) {
        // :1784 - SET FLG-ACCTFILTER-NOT-OK TO TRUE.
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;

        // :1787-1797 - not supplied.
        if (task.ccWorkArea.isCcAcctIdLowValues() || task.ccWorkArea.isCcAcctIdSpaces()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            stringIntoReturnMsgIfOff(task, MSG_PROMPT_FOR_ACCT);
            // :1794-1795 - MOVE ZEROES TO CDEMO-ACCT-ID, ACUP-NEW-ACCT-ID. Both receivers are numeric.
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            task.acupNewAcct.setAcctIdN(0L);
            return;
        }

        // :1800 - MOVE CC-ACCT-ID TO ACUP-NEW-ACCT-ID. Same width, so the characters pass through.
        task.acupNewAcct.acctIdX = PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                AccountUpdateRequest.AcctSnapshot.ACCT_ID_LENGTH);

        // :1801-1802 - not numeric, or numerically zero.
        if (!isNumericPicX(task.ccWorkArea.getCcAcctId()) || task.ccWorkArea.isCcAcctIdNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            stringIntoReturnMsgIfOff(task,
                    MSG_ACCT_NUMBER_11_DIGIT_A + MSG_ACCT_NUMBER_11_DIGIT_B);
            // :1812 - MOVE ZEROES TO CDEMO-ACCT-ID. ACUP-NEW-ACCT-ID is deliberately NOT reset.
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            return;
        }

        // :1815-1816 - valid.
        task.carddemoCommarea = task.carddemoCommarea.withAcctId(task.ccWorkArea.getCcAcctIdN());
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 1215-EDIT-MANDATORY} - {@code app/cbl/COACTUPC.cbl:1824-1853}.
     *
     * <p>Presence only: any character at all is acceptable. Used by exactly one field, address line 1,
     * because it is the only field on the screen that is required but unconstrained.
     *
     * @param task this interaction's storage
     */
    void editMandatory1215(Conversation task) {
        // :1826 - SET FLG-MANDATORY-NOT-OK TO TRUE.
        task.wsEditMandatoryFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            // :1836-1848.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditMandatoryFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        // :1851 - SET FLG-MANDATORY-ISVALID TO TRUE.
        task.wsEditMandatoryFlags = FLG_ISVALID;
    }

    /**
     * {@code FUNCTION TRIM(<item>)}.
     *
     * @param value the item
     * @return the item without leading or trailing spaces
     */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * {@code 1220-EDIT-YESNO} - {@code app/cbl/COACTUPC.cbl:1856-1895}.
     *
     * <p>{@code WS-EDIT-YES-NO} is one character that is both the value and the flag: its 88-levels are
     * {@code ISVALID VALUES 'Y','N'}, {@code NOT-OK VALUE '0'} and {@code BLANK VALUE 'B'}
     * ({@code :76-80}). That is why the caller moves the field in, performs this paragraph, and then
     * moves the same item out into the field's own flag.
     *
     * <p>{@code SET FLG-YES-NO-NOT-OK TO TRUE} at {@code :1858} is <strong>commented out</strong> in the
     * source, so on entry the item still holds the value the caller moved in rather than being reset -
     * which is exactly what the {@code IF FLG-YES-NO-ISVALID} test at {@code :1878} needs. Restoring the
     * commented-out initialisation would make every value fail. It stays absent.
     *
     * @param task this interaction's storage
     */
    void editYesno1220(Conversation task) {
        // :1861-1863 - not supplied. ZEROS is tested as well as SPACES and LOW-VALUES here.
        if (isAll(task.wsEditYesNo, LOW_VALUE)
                || isAll(task.wsEditYesNo, SPACE)
                || isAll(task.wsEditYesNo, ZERO_DIGIT)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditYesNo = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        // :1878-1890 - the class test, against the 88 VALUES 'Y', 'N'.
        if (YES.equals(task.wsEditYesNo) || NO.equals(task.wsEditYesNo)) {
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditYesNo = FLG_NOT_OK;
        stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_Y_OR_N);
    }

    /**
     * {@code 1225-EDIT-ALPHA-REQD} - {@code app/cbl/COACTUPC.cbl:1898-1952}.
     *
     * <p>Mandatory and letters-or-spaces only. Used by the first name, the last name, the state code,
     * the city and the country code - five of the six fields that share the flag item
     * {@code WS-EDIT-ALPHA-ONLY-FLAGS}.
     *
     * <p>The class test is an {@code INSPECT CONVERTING} that blanks every letter and then asks whether
     * anything is left. Because the {@code CONVERTING} mutates the window in place, the mutated window
     * is written back into the staging item: the source's storage really does change, and the caller
     * always re-moves the field before the next edit, so nothing leaks - but the state is reproduced
     * rather than approximated.
     *
     * @param task this interaction's storage
     */
    void editAlphaReqd1225(Conversation task) {
        // :1900 - SET FLG-ALPHA-NOT-OK TO TRUE.
        task.wsEditAlphaOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            // :1909-1922.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphaOnlyFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        // :1925-1928 - MOVE LIT-ALL-ALPHA-FROM-X TO LIT-ALL-ALPHA-FROM, then INSPECT CONVERTING.
        String converted = inspectConverting(window, LIT_ALL_ALPHA_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        // :1930-1948.
        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphaOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_ALPHABETS_ONLY);
            return;
        }

        // :1950 - SET FLG-ALPHA-ISVALID TO TRUE.
        task.wsEditAlphaOnlyFlags = FLG_ISVALID;
    }

    /**
     * {@code 1230-EDIT-ALPHANUM-REQD} - {@code app/cbl/COACTUPC.cbl:1955-2009}.
     *
     * <p>Mandatory and letters, digits or spaces only - the same shape as {@link #editAlphaReqd1225}
     * with the digit set added to the {@code CONVERTING} operand and a different message.
     *
     * <p>No field on this screen reaches it: every mandatory alphanumeric field on {@code COACTUP} is
     * either strictly alphabetic (1225), strictly numeric (1245) or unconstrained (1215). The paragraph
     * is translated because it exists in the program being migrated and removing it would be a
     * behavioural change to the program's shape, not because a caller is expected (practice B5).
     *
     * @param task this interaction's storage
     */
    void editAlphanumReqd1230(Conversation task) {
        // :1957 - SET FLG-ALPHNANUM-NOT-OK TO TRUE. The misspelling is the source's.
        task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            // :1966-1979.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        // :1982-1986.
        String converted = inspectConverting(window, LIT_ALL_ALPHANUM_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        // :1988-2005.
        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_NUMBERS_OR_ALPHABETS_ONLY);
            return;
        }

        // :2007 - SET FLG-ALPHNANUM-ISVALID TO TRUE.
        task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
    }

    /**
     * {@code 1235-EDIT-ALPHA-OPT} - {@code app/cbl/COACTUPC.cbl:2012-2058}.
     *
     * <p>Optional and letters-or-spaces only. The middle name is the one field that uses it, and the
     * difference from {@link #editAlphaReqd1225} is a single branch: an absent value sets
     * {@code FLG-ALPHA-ISVALID} and returns instead of raising {@code INPUT-ERROR}.
     *
     * @param task this interaction's storage
     */
    void editAlphaOpt1235(Conversation task) {
        // :2014 - SET FLG-ALPHA-NOT-OK TO TRUE.
        task.wsEditAlphaOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            // :2023-2024 - SET FLG-ALPHA-ISVALID TO TRUE, GO TO 1235-EDIT-ALPHA-OPT-EXIT. No message
            // and no INPUT-ERROR: that is the whole of "optional".
            task.wsEditAlphaOnlyFlags = FLG_ISVALID;
            return;
        }

        // :2031-2034.
        String converted = inspectConverting(window, LIT_ALL_ALPHA_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        // :2036-2054.
        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphaOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_ALPHABETS_ONLY);
            return;
        }

        // :2056 - SET FLG-ALPHA-ISVALID TO TRUE.
        task.wsEditAlphaOnlyFlags = FLG_ISVALID;
    }

    /**
     * {@code 1240-EDIT-ALPHANUM-OPT} - {@code app/cbl/COACTUPC.cbl:2061-2106}.
     *
     * <p>Optional and letters, digits or spaces only. Like {@link #editAlphanumReqd1230} it has no caller
     * on this screen and is translated for the same reason.
     *
     * @param task this interaction's storage
     */
    void editAlphanumOpt1240(Conversation task) {
        // :2063 - SET FLG-ALPHNANUM-NOT-OK TO TRUE.
        task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            // :2072-2073.
            task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
            return;
        }

        // :2079-2082.
        String converted = inspectConverting(window, LIT_ALL_ALPHANUM_FROM_X);
        task.wsEditAlphanumOnly = splice(task.wsEditAlphanumOnly, 0, task.wsEditAlphanumLength,
                converted);

        // :2084-2102.
        if (trimmedLength(converted) != 0) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_NUMBERS_OR_ALPHABETS_ONLY);
            return;
        }

        // :2104 - SET FLG-ALPHNANUM-ISVALID TO TRUE.
        task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
    }

    /**
     * {@code 1245-EDIT-NUM-REQD} - {@code app/cbl/COACTUPC.cbl:2109-2177}.
     *
     * <p>Mandatory, all digits, and not zero - three tests in that order, each with its own message.
     * Used by the FICO score, the zip code, the EFT account identifier and, through
     * {@link #editUsSsn1265}, each of the three SSN parts. It writes the shared flag item
     * {@code WS-EDIT-ALPHANUM-ONLY-FLAGS}, which is why every caller copies that item into the field's
     * own flag immediately afterwards.
     *
     * <p>Unlike 1225 and 1230 this paragraph performs no {@code INSPECT}, so the staging item is not
     * mutated: the class condition {@code IS NUMERIC} reads it and leaves it alone.
     *
     * @param task this interaction's storage
     */
    void editNumReqd1245(Conversation task) {
        // :2111 - SET FLG-ALPHNANUM-NOT-OK TO TRUE.
        task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;

        String window = editWindow(task);
        if (windowNotSupplied(window)) {
            // :2120-2133.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        // :2137-2152 - IF <window> IS NUMERIC.
        if (!isNumericPicX(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_ALL_NUMERIC);
            return;
        }

        // :2155-2169 - IF FUNCTION NUMVAL(<window>) = 0.
        if (numvalIsZero(window)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAlphanumOnlyFlags = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_NOT_BE_ZERO);
            return;
        }

        // :2175 - SET FLG-ALPHNANUM-ISVALID TO TRUE.
        task.wsEditAlphanumOnlyFlags = FLG_ISVALID;
    }

    /**
     * {@code 1250-EDIT-SIGNED-9V2} - {@code app/cbl/COACTUPC.cbl:2180-2222}.
     *
     * <p>The gate for the five monetary screen fields. Its operand is the {@code PIC X(15)} staging item
     * {@code 1100-RECEIVE-MAP} filled, not the twelve-character detail span, which is why a field the
     * operator blanked reaches this paragraph as {@code LOW-VALUES} and fails the first test.
     *
     * <p>Two tests only - presence, then {@code FUNCTION TEST-NUMVAL-C = 0}. There is no range check and
     * no scale check: the value's scale was fixed by the {@code COMPUTE} in {@code 1100-RECEIVE-MAP},
     * and a value with more than two fraction digits is accepted here and truncated there.
     *
     * @param task this interaction's storage
     */
    void editSigned9v2At1250(Conversation task) {
        // :2181 - SET FLG-SIGNED-NUMBER-NOT-OK TO TRUE.
        task.wsFlgSignedNumberEdit = FLG_NOT_OK;

        // :2184-2196 - not supplied. The operand is compared against two figurative constants, both
        // extended to PIC X(15).
        if (isAll(task.wsEditSignedNumber9v2X, LOW_VALUE)
                || isAll(task.wsEditSignedNumber9v2X, SPACE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsFlgSignedNumberEdit = FLG_BLANK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_MUST_BE_SUPPLIED);
            return;
        }

        // :2201-2214 - the conformance test. Delegated to the service, which owns every numeric
        // intrinsic in this program so the five COMPUTE sites and this gate cannot disagree.
        if (AccountUpdateService.testNumvalC(task.wsEditSignedNumber9v2X)
                != AccountUpdateService.NUMVAL_CONFORMS) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsFlgSignedNumberEdit = FLG_NOT_OK;
            // The source's STRING has no END-STRING here, unlike its siblings; the statement is
            // terminated by the following END-IF and behaves identically.
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_IS_NOT_VALID);
            return;
        }

        // :2219 - SET FLG-SIGNED-NUMBER-ISVALID TO TRUE.
        task.wsFlgSignedNumberEdit = FLG_ISVALID;
    }

    /**
     * {@code 1260-EDIT-US-PHONE-NUM} - {@code app/cbl/COACTUPC.cbl:2225-2428}, together with its three
     * stages {@code EDIT-AREA-CODE} ({@code :2246}), {@code EDIT-US-PHONE-PREFIX} ({@code :2316}) and
     * {@code EDIT-US-PHONE-LINENUM} ({@code :2370}).
     *
     * <p>This is the one place in the program where {@code GO TO} is not simply an early exit: seven of
     * them jump <strong>forward</strong> to the next stage's label. They are still not loop forming, and
     * because each stage's label is reached both by a jump and by falling off the end of the stage above
     * it, the whole construct is exactly three sequential steps in which a failing test skips the rest
     * of its own step. That is how it is written here - three private methods called in order, each
     * returning early - so evaluation order and every fall-through outcome are preserved (rule R7).
     *
     * <p>A phone number is optional as a whole but mandatory part by part once any part is present, and
     * the all-blank guard that decides which of those applies contains a <strong>defect</strong>: its
     * third condition tests {@code WS-EDIT-US-PHONE-NUMA EQUAL SPACES} where the pattern of the first two
     * plainly intends {@code NUMC} ({@code :2249-2251}). The consequence is real - a number whose area
     * code is spaces and whose line number is present but not {@code LOW-VALUES} is treated as entirely
     * absent and passes without any part being checked. The defect is reproduced exactly (practice B5).
     *
     * @param task this interaction's storage
     */
    void editUsPhoneNum1260(Conversation task) {
        // :2237 - SET WS-EDIT-US-PHONE-IS-INVALID TO TRUE. The 88 is on the three-character group, so
        // this writes '0' into all three part flags at once.
        task.wsEditUsPhoneaFlg = FLG_NOT_OK;
        task.wsEditEditUsPhoneb = FLG_NOT_OK;
        task.wsEditEditPhonec = FLG_NOT_OK;

        String numa = phoneArea(task.wsEditUsPhoneNum);
        String numb = phonePrefix(task.wsEditUsPhoneNum);
        String numc = phoneLine(task.wsEditUsPhoneNum);

        // :2239-2251 - the all-blank guard, transcribed literally including the NUMA-for-NUMC defect in
        // its third condition.
        boolean firstBlank = isAll(numa, SPACE) || isAll(numa, LOW_VALUE);
        boolean secondBlank = isAll(numb, SPACE) || isAll(numb, LOW_VALUE);
        boolean thirdBlank = isAll(numa, SPACE) || isAll(numc, LOW_VALUE);
        if (firstBlank && secondBlank && thirdBlank) {
            // :2252-2253 - SET WS-EDIT-US-PHONE-IS-VALID TO TRUE, GO TO EDIT-US-PHONE-EXIT.
            task.wsEditUsPhoneaFlg = FLG_ISVALID;
            task.wsEditEditUsPhoneb = FLG_ISVALID;
            task.wsEditEditPhonec = FLG_ISVALID;
            return;
        }

        editAreaCode(task, numa);
        editUsPhonePrefix(task, numb);
        editUsPhoneLinenum(task, numc);
    }

    /**
     * {@code EDIT-AREA-CODE} - {@code app/cbl/COACTUPC.cbl:2246-2314}.
     *
     * <p>Four tests, each failing to {@code GO TO EDIT-US-PHONE-PREFIX}: present, all digits, non-zero,
     * and a member of the North American general-purpose set. The fourth is the only lookup on this
     * screen that reads {@code CSLKPCDY}'s 490-entry area-code table, and it probes the
     * <strong>general purpose</strong> subset rather than the full table, so an easily-recognisable code
     * such as {@code 800} is rejected here.
     *
     * @param task this interaction's storage
     * @param numa {@code WS-EDIT-US-PHONE-NUMA PIC X(3)}
     */
    void editAreaCode(Conversation task, String numa) {
        // :2247-2259.
        if (isAll(numa, SPACE) || isAll(numa, LOW_VALUE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_BLANK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_AREA_CODE_MUST_BE_SUPPLIED);
            return;
        }

        // :2263-2277.
        if (!isNumericPicX(numa)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_AREA_CODE_3_DIGITS);
            return;
        }

        // :2280-2292 - IF WS-EDIT-US-PHONE-NUMA-N = 0, through the PIC 9(3) redefinition.
        if (numvalIsZero(numa)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_AREA_CODE_CANNOT_BE_ZERO);
            return;
        }

        // :2296-2310 - MOVE FUNCTION TRIM(NUMA) TO WS-US-PHONE-AREA-CODE-TO-EDIT, then the 88 test.
        if (!areaCodeLookup.isValidGeneralPurposeCode(trim(numa))) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditUsPhoneaFlg = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_NOT_VALID_AREA_CODE);
            return;
        }

        // :2312 - SET FLG-EDIT-US-PHONEA-ISVALID TO TRUE.
        task.wsEditUsPhoneaFlg = FLG_ISVALID;
    }

    /**
     * {@code EDIT-US-PHONE-PREFIX} - {@code app/cbl/COACTUPC.cbl:2316-2368}.
     *
     * <p>Three tests - present, all digits, non-zero - each failing to
     * {@code GO TO EDIT-US-PHONE-LINENUM}. There is no table lookup for the prefix.
     *
     * @param task this interaction's storage
     * @param numb {@code WS-EDIT-US-PHONE-NUMB PIC X(3)}
     */
    void editUsPhonePrefix(Conversation task, String numb) {
        // :2318-2330.
        if (isAll(numb, SPACE) || isAll(numb, LOW_VALUE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditUsPhoneb = FLG_BLANK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_PREFIX_MUST_BE_SUPPLIED);
            return;
        }

        // :2334-2348.
        if (!isNumericPicX(numb)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditUsPhoneb = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_PREFIX_3_DIGITS);
            return;
        }

        // :2351-2363.
        if (numvalIsZero(numb)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditUsPhoneb = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_PREFIX_CANNOT_BE_ZERO);
            return;
        }

        // :2366 - SET FLG-EDIT-US-PHONEB-ISVALID TO TRUE.
        task.wsEditEditUsPhoneb = FLG_ISVALID;
    }

    /**
     * {@code EDIT-US-PHONE-LINENUM} - {@code app/cbl/COACTUPC.cbl:2370-2422}.
     *
     * <p>Three tests - present, all digits, non-zero - each failing to {@code GO TO EDIT-US-PHONE-EXIT},
     * which is the end of the construct.
     *
     * @param task this interaction's storage
     * @param numc {@code WS-EDIT-US-PHONE-NUMC PIC X(4)}
     */
    void editUsPhoneLinenum(Conversation task, String numc) {
        // :2371-2383.
        if (isAll(numc, SPACE) || isAll(numc, LOW_VALUE)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditPhonec = FLG_BLANK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_LINENUM_MUST_BE_SUPPLIED);
            return;
        }

        // :2387-2401.
        if (!isNumericPicX(numc)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditPhonec = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_LINENUM_4_DIGITS);
            return;
        }

        // :2404-2416.
        if (numvalIsZero(numc)) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditEditPhonec = FLG_NOT_OK;
            stringIntoReturnMsgIfOff(task,
                    trim(task.wsEditVariableName) + MSG_LINENUM_CANNOT_BE_ZERO);
            return;
        }

        // :2420 - SET FLG-EDIT-US-PHONEC-ISVALID TO TRUE.
        task.wsEditEditPhonec = FLG_ISVALID;
    }

    /**
     * {@code 1265-EDIT-US-SSN} - {@code app/cbl/COACTUPC.cbl:2431-2490}.
     *
     * <p>Three parts, each through {@link #editNumReqd1245} at its own length, each name set immediately
     * before its own call - which is why the {@code MOVE 'SSN'} the caller performs at {@code :1530}
     * never reaches a message.
     *
     * <p>Only part 1 has an additional rule, and its structure has two source properties worth naming:
     *
     * <ul>
     *   <li>the {@code IF INVALID-SSN-PART1} block is <strong>not</strong> followed by
     *       {@code GO TO ...-EXIT}, so parts 2 and 3 are still edited after part 1 is rejected;</li>
     *   <li>the guard is {@code IF FLG-EDIT-US-SSN-PART1-ISVALID}, testing the copy just made rather
     *       than the shared item - equivalent here, and written the way the source writes it.</li>
     * </ul>
     *
     * <p>{@code 88 INVALID-SSN-PART1 VALUES 0, 666, 900 THRU 999} ({@code :107-109}) is evaluated on the
     * {@code PIC 9(3)} redefinition, so it is a numeric test on three digits.
     *
     * @param task this interaction's storage
     */
    void editUsSsn1265(Conversation task) {
        // :2439-2445 - part 1.
        task.wsEditVariableName = editVariableName(NAME_SSN_FIRST_3);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ssn1());
        task.wsEditAlphanumLength = EDIT_LENGTH_3;
        editNumReqd1245(task);
        task.wsEditUsSsnPart1Flgs = task.wsEditAlphanumOnlyFlags;

        // :2448-2463 - the exclusion list, only when the digit test passed.
        if (FLG_ISVALID.equals(task.wsEditUsSsnPart1Flgs)) {
            // MOVE ACUP-NEW-CUST-SSN-1 TO WS-EDIT-US-SSN-PART1, then IF INVALID-SSN-PART1.
            int part1 = Integer.parseInt(PIC_X_CODEC.movePic9(task.acupNewCust.ssn1(),
                    AccountUpdateRequest.CustSnapshot.SSN_PART_1_LENGTH));
            if (part1 == SSN_PART1_EXCLUDED_ZERO
                    || part1 == SSN_PART1_EXCLUDED_666
                    || (part1 >= SSN_PART1_EXCLUDED_RANGE_LOW
                        && part1 <= SSN_PART1_EXCLUDED_RANGE_HIGH)) {
                task.wsInputFlag = INPUT_ERROR;
                task.wsEditUsSsnPart1Flgs = FLG_NOT_OK;
                stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_SSN_PART1_RANGE);
            }
        }

        // :2469-2475 - part 2.
        task.wsEditVariableName = editVariableName(NAME_SSN_4TH_5TH);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ssn2());
        task.wsEditAlphanumLength = EDIT_LENGTH_2;
        editNumReqd1245(task);
        task.wsEditUsSsnPart2Flgs = task.wsEditAlphanumOnlyFlags;

        // :2481-2487 - part 3.
        task.wsEditVariableName = editVariableName(NAME_SSN_LAST_4);
        task.wsEditAlphanumOnly = stagingItem(task.acupNewCust.ssn3());
        task.wsEditAlphanumLength = EDIT_LENGTH_4;
        editNumReqd1245(task);
        task.wsEditUsSsnPart3Flgs = task.wsEditAlphanumOnlyFlags;
    }

    /**
     * {@code 1270-EDIT-US-STATE-CD} - {@code app/cbl/COACTUPC.cbl:2493-2511}.
     *
     * <p>{@code MOVE ACUP-NEW-CUST-ADDR-STATE-CD TO US-STATE-CODE-TO-EDIT} then
     * {@code IF VALID-US-STATE-CODE} - a probe of {@code CSLKPCDY}'s 56-entry table, which includes the
     * District of Columbia and the territories as well as the fifty states.
     *
     * <p>The paragraph sets {@code FLG-STATE-NOT-OK} on failure and sets <strong>nothing</strong> on
     * success: the {@code ISVALID} it relies on was already written by {@link #editAlphaReqd1225} at the
     * caller's preceding step. Adding a success assignment would be indistinguishable in outcome here
     * and is still not done, because the source does not do it.
     *
     * @param task this interaction's storage
     */
    void editUsStateCd1270(Conversation task) {
        // :2494-2495.
        if (areaCodeLookup.isValidUsStateCode(task.acupNewCust.addrStateCd)) {
            return;
        }
        // :2497-2509.
        task.wsInputFlag = INPUT_ERROR;
        task.setFlag(AccountUpdateResponse.ScreenField.ACSSTTE, FLG_NOT_OK);
        stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_NOT_A_VALID_STATE_CODE);
    }

    /**
     * {@code 1275-EDIT-FICO-SCORE} - {@code app/cbl/COACTUPC.cbl:2514-2533}.
     *
     * <p>{@code IF FICO-RANGE-IS-VALID} - the 88 on the {@code PIC 9(03)} redefinition, declared
     * {@code VALUES 300 THROUGH 850} at {@code :848-849}. Reached only when the numeric edit passed, so
     * the three characters are digits and the numeric view is well defined.
     *
     * @param task this interaction's storage
     */
    void editFicoScore1275(Conversation task) {
        int score = task.acupNewCust.ficoScoreN();
        // :2515-2516.
        if (score >= FICO_RANGE_LOW && score <= FICO_RANGE_HIGH) {
            return;
        }
        // :2518-2530.
        task.wsInputFlag = INPUT_ERROR;
        task.setFlag(AccountUpdateResponse.ScreenField.ACSTFCO, FLG_NOT_OK);
        stringIntoReturnMsgIfOff(task, trim(task.wsEditVariableName) + MSG_FICO_RANGE);
    }

    /**
     * {@code 1280-EDIT-US-STATE-ZIP-CD} - {@code app/cbl/COACTUPC.cbl:2536-2559}, described in the source
     * as "a crude zip code edit based on data from USPS web site".
     *
     * <p>The only cross-field edit on the screen, and the reason it runs last: it needs the state code
     * and the zip code to have each passed individually before the pair means anything.
     *
     * <p>Three properties of the failure branch are behaviour:
     *
     * <ul>
     *   <li>it sets <strong>both</strong> {@code FLG-STATE-NOT-OK} and {@code FLG-ZIPCODE-NOT-OK}, so
     *       the screen highlights two fields for one error;</li>
     *   <li>its message is a bare literal with no {@code WS-EDIT-VARIABLE-NAME} prefix - the only edit
     *       message in the program that is not built from the field name;</li>
     *   <li>the probe is {@code STRING <state> <zip(1:2)> INTO US-STATE-AND-FIRST-ZIP2}, four characters
     *       from a two-character state and the first two of a ten-character zip.</li>
     * </ul>
     *
     * @param task this interaction's storage
     */
    void editUsStateZipCd1280(Conversation task) {
        // :2537-2540 - the STRING, reproduced by the lookup's own composer so the widths are stated once.
        String probe = areaCodeLookup.composeStateAndFirstZip2(task.acupNewCust.addrStateCd,
                task.acupNewCust.addrZip);

        // :2542-2543.
        if (areaCodeLookup.isValidStateZip2Combo(probe)) {
            return;
        }

        // :2545-2557.
        task.wsInputFlag = INPUT_ERROR;
        task.setFlag(AccountUpdateResponse.ScreenField.ACSSTTE, FLG_NOT_OK);
        task.setFlag(AccountUpdateResponse.ScreenField.ACSZIPC, FLG_NOT_OK);
        stringIntoReturnMsgIfOff(task, MSG_INVALID_ZIP_FOR_STATE);
    }

    /**
     * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT}, the {@code CSUTLDPY} range the
     * caller performs for the open date ({@code :1478-1482}), the expiry date ({@code :1490-1494}), the
     * reissue date ({@code :1503-1507}) and the date of birth ({@code :1533-1543}).
     *
     * <p>{@code CSUTLDWY} declares the date-edit storage and {@code CSUTLDPY} the procedure; in Java the
     * two are {@link AccountDateValidator.EditDateState} and {@link AccountDateValidator}. Three items
     * are <strong>shared</strong> between the caller and the copybook rather than passed - the field
     * name, the return message and the input flag - so each is copied in before the call and copied back
     * after it. Without that, a date error would not raise {@code INPUT-ERROR} in the caller and its
     * message would be lost.
     *
     * <p>The message item is seeded through {@code STRING ... INTO} rather than a setter because the
     * copybook's contract is first-message-wins in exactly the same way the caller's is: seeding a
     * non-blank message is what stops the date edit from overwriting an earlier field's text.
     *
     * <p>Only the date of birth takes the second step. {@code IF WS-EDIT-DT-OF-BIRTH-ISVALID} tests the
     * three-character group against {@code LOW-VALUES}, and only then is
     * {@code EDIT-DATE-OF-BIRTH} performed and the flag group re-copied - so a malformed date of birth
     * is never additionally reported as being in the future.
     *
     * @param task        this interaction's storage
     * @param name        the literal moved to {@code WS-EDIT-VARIABLE-NAME}
     * @param date        the eight-character {@code CCYYMMDD} span
     * @param yearField   the screen field carrying the year flag
     * @param monthField  the screen field carrying the month flag
     * @param dayField    the screen field carrying the day flag
     * @param dateOfBirth {@code true} for the one caller that follows up with the not-in-the-future check
     */
    void editDate(Conversation task, String name, String date,
                  AccountUpdateResponse.ScreenField yearField,
                  AccountUpdateResponse.ScreenField monthField,
                  AccountUpdateResponse.ScreenField dayField,
                  boolean dateOfBirth) {
        task.wsEditVariableName = editVariableName(name);

        AccountDateValidator.EditDateState state = accountDateValidator.newState();
        state.setEditVariableName(task.wsEditVariableName);
        state.setReturnMsgOff();
        if (!task.returnMsgOff()) {
            state.stringIntoReturnMessage(task.wsReturnMsg);
        }
        if (INPUT_ERROR.equals(task.wsInputFlag)) {
            state.setInputError();
        } else if (INPUT_OK.equals(task.wsInputFlag)) {
            state.setInputOk();
        } else {
            state.setInputPending();
        }
        state.setEditDateCcyymmdd(PIC_X_CODEC.movePicX(date,
                AccountUpdateRequest.AcctSnapshot.DATE_LENGTH));

        accountDateValidator.editDateCcyymmddThruExit(state);
        copyDateFlags(task, state, yearField, monthField, dayField);

        if (dateOfBirth && state.wsEditDateIsValid()) {
            // :1540-1542 - the second range, then MOVE WS-EDIT-DATE-FLGS again. The clock is passed
            // explicitly so FUNCTION CURRENT-DATE is this request's, not the validator's default.
            accountDateValidator.editDateOfBirth(state, clock);
            copyDateFlags(task, state, yearField, monthField, dayField);
        }

        // The shared items travel back. The message only replaces the caller's when the caller had none,
        // which is the same first-message-wins rule the caller applies to itself.
        if (task.returnMsgOff()) {
            task.wsReturnMsg = atReturnWidth(state.returnMessage());
        }
        if (state.inputError()) {
            task.wsInputFlag = INPUT_ERROR;
        }
    }

    /**
     * {@code MOVE WS-EDIT-DATE-FLGS TO WS-EDIT-<field>-FLGS} - one three-character group move that lands
     * the year, month and day verdicts in the three screen fields the date was split across.
     *
     * @param task       this interaction's storage
     * @param state      the copybook's storage after the edit
     * @param yearField  the screen field carrying the year flag
     * @param monthField the screen field carrying the month flag
     * @param dayField   the screen field carrying the day flag
     */
    void copyDateFlags(Conversation task, AccountDateValidator.EditDateState state,
                       AccountUpdateResponse.ScreenField yearField,
                       AccountUpdateResponse.ScreenField monthField,
                       AccountUpdateResponse.ScreenField dayField) {
        String flags = state.flagsImage();
        task.setFlag(yearField, flags.substring(0, ONE));
        task.setFlag(monthField, flags.substring(ONE, ONE + ONE));
        task.setFlag(dayField, flags.substring(ONE + ONE, ONE + ONE + ONE));
    }

    /**
     * {@code MOVE '<name>' TO WS-EDIT-VARIABLE-NAME}, at the item's declared {@code PIC X(25)}.
     *
     * <p>Routed through the codec rather than assigned, because one of the 24 names is twenty-six
     * characters and the move therefore truncates it on the right. Doing that here means the truncation
     * happens once, deliberately, and shows up in every message that name reaches.
     *
     * @param name the literal the source moves
     * @return exactly {@value #WS_EDIT_VARIABLE_NAME_LENGTH} characters
     */
    static String editVariableName(String name) {
        return PIC_X_CODEC.movePicX(name, WS_EDIT_VARIABLE_NAME_LENGTH);
    }

    /**
     * {@code MOVE <field> TO WS-EDIT-ALPHANUM-ONLY}, at the item's declared {@code PIC X(256)}.
     *
     * <p>Space padded to 256, which is what makes the generic edits' {@code (1:WS-EDIT-ALPHANUM-LENGTH)}
     * window well defined however short the sending field was.
     *
     * @param value the sending field
     * @return exactly {@value #WS_EDIT_ALPHANUM_ONLY_LENGTH} characters
     */
    static String stagingItem(String value) {
        return PIC_X_CODEC.movePicX(value == null ? "" : value, WS_EDIT_ALPHANUM_ONLY_LENGTH);
    }

    // =================================================================================================
    // 2000-DECIDE-ACTION - app/cbl/COACTUPC.cbl:2562-2641.
    // =================================================================================================

    /**
     * {@code 2000-DECIDE-ACTION} - {@code app/cbl/COACTUPC.cbl:2562-2641}.
     *
     * <p>The state machine. One {@code EVALUATE TRUE} with <strong>eight</strong> {@code WHEN}s, and
     * because {@code EVALUATE} takes the first match, their order is the whole of the behaviour
     * (gate <strong>G30</strong>). Two properties are easy to get wrong and are called out:
     *
     * <ul>
     *   <li>the first two {@code WHEN}s - {@code ACUP-DETAILS-NOT-FETCHED} and {@code CCARD-AID-PFK12} -
     *       have <strong>no body between them</strong>, so they share the one that follows. Arriving on
     *       the entry screen and cancelling a set of changes therefore do exactly the same thing: read
     *       the account afresh and show it;</li>
     *   <li>{@code ACUP-CHANGES-OK-NOT-CONFIRMED} appears <strong>twice</strong>, at {@code :2601} with
     *       {@code AND CCARD-AID-PFK05} and again bare at {@code :2622}. The guarded one must be tested
     *       first or the save would never happen - which is exactly why {@code EVALUATE} order is not an
     *       implementation detail here.</li>
     * </ul>
     *
     * <p>The write itself is <strong>not</strong> in this file: {@code 9600-WRITE-PROCESSING} and
     * {@code 9700-CHECK-CHANGE-IN-REC} belong to {@link AccountUpdateService}, and this paragraph only
     * dispatches on the outcome it reports (gates <strong>G43</strong> and <strong>G51</strong>).
     *
     * @param task this interaction's storage
     */
    void decideAction2000(Conversation task) {
        // :2568 and :2571 - the two shared-body arms.
        if (task.acupChangeAction.isDetailsNotFetched() || task.ccWorkArea.isCcardAidPfk12()) {
            // :2572-2579.
            if (task.flgAcctfilterIsvalid()) {
                // :2573 - SET WS-RETURN-MSG-OFF TO TRUE. Any message the edits produced is discarded,
                // because the record is about to be read again and its verdicts recomputed.
                task.wsReturnMsg = WS_RETURN_MSG_OFF;
                readAcct9000(task);
                if (task.foundCustInMaster()) {
                    task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
                }
            }
            return;
        }

        // :2585-2592 - details on the screen, edits already run.
        if (task.acupChangeAction.isShowDetails()) {
            if (task.inputError() || task.noChangesDetected()) {
                // :2587-2588 - CONTINUE. The screen comes back with the message the edit produced.
                return;
            }
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkNotConfirmed();
            return;
        }

        // :2598-2599 - WHEN ACUP-CHANGES-NOT-OK / CONTINUE. Explicit, because reaching the WHEN OTHER
        // arm instead would abend.
        if (task.acupChangeAction.isChangesNotOk()) {
            return;
        }

        // :2601-2618 - the save. Guarded arm first.
        if (task.acupChangeAction.isChangesOkNotConfirmed() && task.ccWorkArea.isCcardAidPfk05()) {
            writeProcessing9600(task);
            return;
        }

        // :2622-2623 - the same state without PF5: CONTINUE, and 3250 will prompt for confirmation again.
        if (task.acupChangeAction.isChangesOkNotConfirmed()) {
            return;
        }

        // :2628-2635 - the update committed on a previous turn.
        if (task.acupChangeAction.isChangesOkayedAndDone()) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
            // :2630-2635 - only when this program was entered directly rather than from another
            // transaction: forget the account so the operator starts from the search screen.
            String fromTranid = task.carddemoCommarea.fromTranid();
            if (isAll(fromTranid, LOW_VALUE) || isAll(fromTranid, SPACE)) {
                task.carddemoCommarea = task.carddemoCommarea
                        .withAcctId(0L)
                        .withCardNum(0L)
                        .withAcctStatus(lowValues(NavigationContext.ACCT_STATUS_LENGTH));
            }
            return;
        }

        // :2636-2641 - WHEN OTHER. An unreachable state is a program error, not a user error.
        throw abendRoutine(task, ABEND_CODE_UNEXPECTED_DATA, ABEND_MSG_UNEXPECTED_DATA_SCENARIO);
    }

    /**
     * The {@code PERFORM 9600-WRITE-PROCESSING} arm of {@code 2000-DECIDE-ACTION}, and the inner
     * {@code EVALUATE TRUE} at {@code :2606-2617} that dispatches on its outcome.
     *
     * <p>Four arms in order: could not lock the account, locked but the update failed, the record changed
     * under us, and {@code WHEN OTHER} - success. {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} is
     * <strong>not</strong> one of them: the source tests only the account lock, so a customer-lock
     * failure falls to {@code WHEN OTHER} and is reported as a success. That is a defect in the original
     * and it is preserved (practice B5); the service still distinguishes the two outcomes, so the
     * mapping is visible here rather than hidden.
     *
     * @param task this interaction's storage
     */
    void writeProcessing9600(Conversation task) {
        AccountUpdateService.WriteResult result = accountUpdateService.writeProcessing(
                task.ccWorkArea.getCcAcctId(),
                task.carddemoCommarea,
                new AccountUpdateService.AccountUpdateDetails(
                        AccountUpdateService.DetailGroup.OLD,
                        task.acupOldAcct.toAccountData(),
                        task.acupOldCust.toCustomerData()),
                new AccountUpdateService.AccountUpdateDetails(
                        AccountUpdateService.DetailGroup.NEW,
                        task.acupNewAcct.toAccountData(),
                        task.acupNewCust.toCustomerData()),
                task.wsReturnMsg,
                codec);

        // WS-RETURN-MSG and WS-INPUT-FLAG are the paragraph's own shared items; they come back changed.
        task.wsReturnMsg = atReturnWidth(result.returnMessage());
        if (result.inputError()) {
            task.wsInputFlag = INPUT_ERROR;
        }

        // :2606-2617 - the four arms, in source order with WHEN OTHER last.
        if (result.outcome() == AccountUpdateService.WriteOutcome.COULD_NOT_LOCK_ACCT_FOR_UPDATE) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedLockError();
        } else if (result.outcome() == AccountUpdateService.WriteOutcome.LOCKED_BUT_UPDATE_FAILED) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedButFailed();
        } else if (result.outcome()
                == AccountUpdateService.WriteOutcome.DATA_WAS_CHANGED_BEFORE_UPDATE) {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.showDetails();
        } else {
            task.acupChangeAction = AccountUpdateRequest.ChangeAction.changesOkayedAndDone();
        }
    }

    // =================================================================================================
    // 3000-SEND-MAP and its six subordinates - app/cbl/COACTUPC.cbl:2646-3605.
    //
    // The paint. Values go to the output map area (CACTUPAO), protection bytes and the cursor go to the
    // INPUT map area (CACTUPAI) - that asymmetry is the symbolic map's, because xxxA is a REDEFINES of
    // the input group's flag byte while xxxC hangs off the output group. Both halves reach the client as
    // ScreenMetadata, per AAP 0.6.3: they are metadata, never JSON payload members.
    // =================================================================================================

    /**
     * {@code 3000-SEND-MAP} - {@code app/cbl/COACTUPC.cbl:2646-2662}.
     *
     * <p>Six {@code PERFORM}s in a fixed order, and the order matters twice: {@code 3200} decides the
     * values before {@code 3250} overwrites the two message fields, and {@code 3300} sets the field
     * attributes before {@code 3390} adjusts the information line and the function-key legends.
     *
     * @param task this interaction's storage
     */
    void sendMap3000(Conversation task) {
        screenInit3100(task);
        setupScreenVars3200(task);
        setupInfomsg3250(task);
        setupScreenAttrs3300(task);
        setupInfomsgAttrs3390(task);
        sendScreen3400(task);
    }

    /**
     * {@code 3100-SCREEN-INIT} - {@code app/cbl/COACTUPC.cbl:2668-2694}.
     *
     * <p>{@code MOVE LOW-VALUES TO CACTUPAO} clears every output item, then the four header literals and
     * the date and time are laid in. The function-key legends are set here too: the mapset declares them
     * as {@code INITIAL} values, and clearing the map area to {@code LOW-VALUES} would otherwise erase
     * them, so restoring them is what {@code LOW-VALUES} means for a field CICS re-sends from the mapset.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} appears <strong>twice</strong>, at
     * {@code :2670} and {@code :2678}, with only unrelated literal moves between them. The second is
     * redundant. It is reproduced as a second capture from the same clock, which is what the source does
     * and what a parity test of the rendered header can observe if the two ever straddle a second
     * boundary.
     *
     * @param task this interaction's storage
     */
    void screenInit3100(Conversation task) {
        // :2669 - MOVE LOW-VALUES TO CACTUPAO.
        task.cactupao = AccountUpdateResponse.initial();
        task.cactupao.resetAttributeQuads();

        // :2670 - the first, discarded capture.
        DateHeader.from(PIC_X_CODEC, clock);

        // :2672-2676 - CCDA-TITLE01, CCDA-TITLE02, LIT-THISTRANID, LIT-THISPGM.
        task.cactupao = task.cactupao.withScreenTitles().withFunctionKeyLegends();

        // :2678-2690 - the second capture, and the one whose renderings reach the screen.
        task.cactupao = task.cactupao.withDateTimeHeader(DateHeader.from(PIC_X_CODEC, clock));
    }

    /**
     * {@code 3200-SETUP-SCREEN-VARS} - {@code app/cbl/COACTUPC.cbl:2698-2727}.
     *
     * <p>The whole paragraph is inside {@code IF CDEMO-PGM-ENTER CONTINUE ELSE ...}, so on a first entry
     * <strong>nothing at all</strong> is painted beyond {@code 3100}'s header - the operator sees an empty
     * screen with the search prompt, and the account filter is not even echoed.
     *
     * <p>Otherwise the filter is echoed, unless it is numerically zero <em>and</em> the filter edit
     * passed - a combination {@code 1210-EDIT-ACCOUNT} cannot produce, since it rejects a zero
     * identifier, so the {@code LOW-VALUES} arm is unreachable in practice and is reproduced anyway.
     *
     * <p>Then one of the three paint modes runs, chosen by an {@code EVALUATE TRUE} whose arms are, in
     * order: not fetched <em>or</em> a zero identifier gives the empty paint; details being shown gives
     * the fetched values; changes having been made gives the typed values; and {@code WHEN OTHER} gives
     * the fetched values again. Which one runs is observable output, so the selection is transcribed
     * rather than simplified.
     *
     * <h4>Both zero tests read a span that has not been class-tested, so neither may decode it</h4>
     * {@code IF CC-ACCT-ID-N = 0} at {@code :2704} and {@code WHEN CC-ACCT-ID-N = 0} at {@code :2711}
     * are reached on <strong>every</strong> re-entry, including the one where
     * {@code 1210-EDIT-ACCOUNT} has just rejected the filter as {@code NOT NUMERIC} at {@code :1801}.
     * {@code CC-ACCT-ID-N} is the {@code PIC 9(11)} redefinition of eleven characters that may hold
     * anything the operator typed, and COBOL answers {@code EQUAL ZEROS} over it by reading the digit
     * positions of the bytes that are there - it does not fail, and it certainly does not abend.
     *
     * <p>So both tests are asked through {@link CardScreenState#isCcAcctIdNZeros()}, the total
     * predicate that exists for precisely this position in the flow, and <strong>not</strong> through
     * {@code getCcAcctIdN() == 0}: the numeric view's decoder refuses a span that is neither digits nor
     * a zero-valued state, by deliberate policy, so asking it here turned an ordinary rejected filter -
     * {@code /api/accounts/ABCDEFGHIJK} - into {@code ABEND-ROUTINE} and an HTTP 500, and made the
     * {@code INPUT-ERROR} arm at {@code :1806} unreachable for every non-numeric identifier. The screen
     * the fixed path paints is the one the source paints: the filter echoed by {@code :2707}, the other
     * 41 fields left {@code LOW-VALUES} by {@code 3201}, and the message
     * {@code 1210-EDIT-ACCOUNT} composed.
     *
     * @param task this interaction's storage
     */
    void setupScreenVars3200(Conversation task) {
        // :2700-2702 - IF CDEMO-PGM-ENTER CONTINUE.
        if (task.carddemoCommarea.isEnter()) {
            return;
        }

        // :2704-2708. The zero test, not a decode: see this method's note on the two class-test-free
        // reads of CC-ACCT-ID-N.
        if (task.ccWorkArea.isCcAcctIdNZeros() && task.flgAcctfilterIsvalid()) {
            task.cactupao = task.cactupao.withValue(AccountUpdateResponse.ScreenField.ACCTSID,
                    lowValues(AccountUpdateResponse.declaredLength(
                            AccountUpdateResponse.ScreenField.ACCTSID)));
        } else {
            task.cactupao = task.cactupao.withValue(AccountUpdateResponse.ScreenField.ACCTSID,
                    PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                            AccountUpdateResponse.declaredLength(
                                    AccountUpdateResponse.ScreenField.ACCTSID)));
        }

        // :2710-2725 - the paint-mode selection, in source order with WHEN OTHER last. The second arm is
        // the same class-test-free zero test as :2704, asked the same total way.
        if (task.acupChangeAction.isDetailsNotFetched() || task.ccWorkArea.isCcAcctIdNZeros()) {
            showInitialValues3201(task);
        } else if (task.acupChangeAction.isShowDetails()) {
            showOriginalValues3202(task);
        } else if (task.acupChangeAction.isChangesMade()) {
            showUpdatedValues3203(task);
        } else {
            showOriginalValues3202(task);
        }
    }

    /**
     * {@code 3201-SHOW-INITIAL-VALUES} - {@code app/cbl/COACTUPC.cbl:2731-2782}.
     *
     * <p>One {@code MOVE LOW-VALUES} with 41 receivers: every data field on the screen except the account
     * filter, which {@code 3200} has just set, and except the header, the two message lines and the three
     * function-key legends. The account filter's absence from the list is the point of the paragraph.
     *
     * @param task this interaction's storage
     */
    void showInitialValues3201(Conversation task) {
        for (AccountUpdateResponse.ScreenField field : INITIAL_VALUE_FIELDS) {
            task.cactupao = task.cactupao.withValue(field,
                    lowValues(AccountUpdateResponse.declaredLength(field)));
        }
    }

    /**
     * {@code 3202-SHOW-ORIGINAL-VALUES} - {@code app/cbl/COACTUPC.cbl:2787-2866}.
     *
     * <p>Paints the fetched record. Three things happen before any field is painted:
     * {@code MOVE LOW-VALUES TO WS-NON-KEY-FLAGS} clears every field verdict, so no highlight survives
     * into this paint, and {@code SET PROMPT-FOR-CHANGES TO TRUE} sets the information line - which
     * {@code 3250} may then overwrite.
     *
     * <p>The paint is split by two guards whose asymmetry is behaviour: the account half runs when
     * <strong>either</strong> master was found, so a customer-read failure still shows the account; the
     * customer half runs only when the customer was found.
     *
     * <p>The five monetary fields go through the {@code PIC +ZZZ,ZZZ,ZZZ.99} mask. The SSN and the two
     * telephone numbers are painted from reference-modified slices of the stored spans -
     * {@code (1:3)(4:2)(6:4)} for the SSN and {@code (2:3)(6:3)(10:4)} for a telephone number - which is
     * how the composite fields reach the screen as separate items.
     *
     * @param task this interaction's storage
     */
    void showOriginalValues3202(Conversation task) {
        // :2789 - MOVE LOW-VALUES TO WS-NON-KEY-FLAGS.
        task.clearNonKeyFlags();
        // :2791 - SET PROMPT-FOR-CHANGES TO TRUE, an 88 on WS-INFO-MSG.
        task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CHANGES);

        AcctDataArea oldAcct = task.acupOldAcct;
        CustDataArea oldCust = task.acupOldCust;
        AccountUpdateRequest.AcctSnapshot acct = oldAcct.toSnapshot();

        // :2793-2831 - the account half.
        if (task.foundAcctInMaster() || task.foundCustInMaster()) {
            paint(task, AccountUpdateResponse.ScreenField.ACSTTUS, oldAcct.activeStatus);
            paint(task, AccountUpdateResponse.ScreenField.ACURBAL,
                    editCurrency92(acct.currBalN()));
            paint(task, AccountUpdateResponse.ScreenField.ACRDLIM,
                    editCurrency92(acct.creditLimitN()));
            paint(task, AccountUpdateResponse.ScreenField.ACSHLIM,
                    editCurrency92(acct.cashCreditLimitN()));
            paint(task, AccountUpdateResponse.ScreenField.ACRCYCR,
                    editCurrency92(acct.currCycCreditN()));
            paint(task, AccountUpdateResponse.ScreenField.ACRCYDB,
                    editCurrency92(acct.currCycDebitN()));
            paint(task, AccountUpdateResponse.ScreenField.OPNYEAR, oldAcct.openYear());
            paint(task, AccountUpdateResponse.ScreenField.OPNMON, oldAcct.openMon());
            paint(task, AccountUpdateResponse.ScreenField.OPNDAY, oldAcct.openDay());
            paint(task, AccountUpdateResponse.ScreenField.EXPYEAR, oldAcct.expYear());
            paint(task, AccountUpdateResponse.ScreenField.EXPMON, oldAcct.expMon());
            paint(task, AccountUpdateResponse.ScreenField.EXPDAY, oldAcct.expDay());
            paint(task, AccountUpdateResponse.ScreenField.RISYEAR, oldAcct.reissueYear());
            paint(task, AccountUpdateResponse.ScreenField.RISMON, oldAcct.reissueMon());
            paint(task, AccountUpdateResponse.ScreenField.RISDAY, oldAcct.reissueDay());
            paint(task, AccountUpdateResponse.ScreenField.AADDGRP, oldAcct.groupId);
        }

        // :2833-2864 - the customer half.
        if (task.foundCustInMaster()) {
            paint(task, AccountUpdateResponse.ScreenField.ACSTNUM, oldCust.custIdX);
            paint(task, AccountUpdateResponse.ScreenField.ACTSSN1, oldCust.ssn1());
            paint(task, AccountUpdateResponse.ScreenField.ACTSSN2, oldCust.ssn2());
            paint(task, AccountUpdateResponse.ScreenField.ACTSSN3, oldCust.ssn3());
            paint(task, AccountUpdateResponse.ScreenField.ACSTFCO, oldCust.ficoScoreX);
            paint(task, AccountUpdateResponse.ScreenField.DOBYEAR, oldCust.dobYear());
            paint(task, AccountUpdateResponse.ScreenField.DOBMON, oldCust.dobMon());
            paint(task, AccountUpdateResponse.ScreenField.DOBDAY, oldCust.dobDay());
            paint(task, AccountUpdateResponse.ScreenField.ACSFNAM, oldCust.firstName);
            paint(task, AccountUpdateResponse.ScreenField.ACSMNAM, oldCust.middleName);
            paint(task, AccountUpdateResponse.ScreenField.ACSLNAM, oldCust.lastName);
            paint(task, AccountUpdateResponse.ScreenField.ACSADL1, oldCust.addrLine1);
            paint(task, AccountUpdateResponse.ScreenField.ACSADL2, oldCust.addrLine2);
            // The city screen field carries address line 3, as it does on the way in.
            paint(task, AccountUpdateResponse.ScreenField.ACSCITY, oldCust.addrLine3);
            paint(task, AccountUpdateResponse.ScreenField.ACSSTTE, oldCust.addrStateCd);
            paint(task, AccountUpdateResponse.ScreenField.ACSZIPC, oldCust.addrZip);
            paint(task, AccountUpdateResponse.ScreenField.ACSCTRY, oldCust.addrCountryCd);
            paint(task, AccountUpdateResponse.ScreenField.ACSPH1A, oldCust.phoneNum1A());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH1B, oldCust.phoneNum1B());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH1C, oldCust.phoneNum1C());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH2A, oldCust.phoneNum2A());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH2B, oldCust.phoneNum2B());
            paint(task, AccountUpdateResponse.ScreenField.ACSPH2C, oldCust.phoneNum2C());
            paint(task, AccountUpdateResponse.ScreenField.ACSGOVT, oldCust.govtIssuedId);
            paint(task, AccountUpdateResponse.ScreenField.ACSEFTC, oldCust.eftAccountId);
            paint(task, AccountUpdateResponse.ScreenField.ACSPFLG, oldCust.priHolderInd);
        }
    }

    /**
     * {@code 3203-SHOW-UPDATED-VALUES} - {@code app/cbl/COACTUPC.cbl:2870-2951}.
     *
     * <p>Paints what the operator typed. Unlike {@code 3202} it is unguarded - there is no
     * {@code FOUND-...-IN-MASTER} test, because the values come from the screen and not from a file - and
     * it clears no flags, because the highlights the edits produced are exactly what has to survive.
     *
     * <p>Each of the five monetary fields is painted two different ways depending on its own verdict: the
     * masked numeric view when the edit passed, and the raw {@code PIC X(15)} staging item when it did
     * not. That is what lets the operator see the invalid text they typed rather than a formatted zero,
     * and it is the reason the staging items are carried in the conversation at all.
     *
     * @param task this interaction's storage
     */
    void showUpdatedValues3203(Conversation task) {
        AcctDataArea newAcct = task.acupNewAcct;
        CustDataArea newCust = task.acupNewCust;
        AccountUpdateRequest.AcctSnapshot acct = newAcct.toSnapshot();

        // :2872.
        paint(task, AccountUpdateResponse.ScreenField.ACSTTUS, newAcct.activeStatus);

        // :2874-2910 - the five conditional monetary paints, in the source's order: credit limit, cash
        // credit limit, current balance, current cycle credit, current cycle debit.
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACRDLIM, acct.creditLimitN(),
                task.acupNewCreditLimitX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACSHLIM, acct.cashCreditLimitN(),
                task.acupNewCashCreditLimitX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACURBAL, acct.currBalN(),
                task.acupNewCurrBalX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACRCYCR, acct.currCycCreditN(),
                task.acupNewCurrCycCreditX);
        paintMonetary(task, AccountUpdateResponse.ScreenField.ACRCYDB, acct.currCycDebitN(),
                task.acupNewCurrCycDebitX);

        // :2912-2951 - everything else, raw.
        paint(task, AccountUpdateResponse.ScreenField.OPNYEAR, newAcct.openYear());
        paint(task, AccountUpdateResponse.ScreenField.OPNMON, newAcct.openMon());
        paint(task, AccountUpdateResponse.ScreenField.OPNDAY, newAcct.openDay());
        paint(task, AccountUpdateResponse.ScreenField.EXPYEAR, newAcct.expYear());
        paint(task, AccountUpdateResponse.ScreenField.EXPMON, newAcct.expMon());
        paint(task, AccountUpdateResponse.ScreenField.EXPDAY, newAcct.expDay());
        paint(task, AccountUpdateResponse.ScreenField.RISYEAR, newAcct.reissueYear());
        paint(task, AccountUpdateResponse.ScreenField.RISMON, newAcct.reissueMon());
        paint(task, AccountUpdateResponse.ScreenField.RISDAY, newAcct.reissueDay());
        paint(task, AccountUpdateResponse.ScreenField.AADDGRP, newAcct.groupId);
        paint(task, AccountUpdateResponse.ScreenField.ACSTNUM, newCust.custIdX);
        paint(task, AccountUpdateResponse.ScreenField.ACTSSN1, newCust.ssn1());
        paint(task, AccountUpdateResponse.ScreenField.ACTSSN2, newCust.ssn2());
        paint(task, AccountUpdateResponse.ScreenField.ACTSSN3, newCust.ssn3());
        paint(task, AccountUpdateResponse.ScreenField.ACSTFCO, newCust.ficoScoreX);
        paint(task, AccountUpdateResponse.ScreenField.DOBYEAR, newCust.dobYear());
        paint(task, AccountUpdateResponse.ScreenField.DOBMON, newCust.dobMon());
        paint(task, AccountUpdateResponse.ScreenField.DOBDAY, newCust.dobDay());
        paint(task, AccountUpdateResponse.ScreenField.ACSFNAM, newCust.firstName);
        paint(task, AccountUpdateResponse.ScreenField.ACSMNAM, newCust.middleName);
        paint(task, AccountUpdateResponse.ScreenField.ACSLNAM, newCust.lastName);
        paint(task, AccountUpdateResponse.ScreenField.ACSADL1, newCust.addrLine1);
        paint(task, AccountUpdateResponse.ScreenField.ACSADL2, newCust.addrLine2);
        paint(task, AccountUpdateResponse.ScreenField.ACSCITY, newCust.addrLine3);
        paint(task, AccountUpdateResponse.ScreenField.ACSSTTE, newCust.addrStateCd);
        paint(task, AccountUpdateResponse.ScreenField.ACSZIPC, newCust.addrZip);
        paint(task, AccountUpdateResponse.ScreenField.ACSCTRY, newCust.addrCountryCd);
        paint(task, AccountUpdateResponse.ScreenField.ACSPH1A, newCust.phoneNum1A());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH1B, newCust.phoneNum1B());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH1C, newCust.phoneNum1C());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH2A, newCust.phoneNum2A());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH2B, newCust.phoneNum2B());
        paint(task, AccountUpdateResponse.ScreenField.ACSPH2C, newCust.phoneNum2C());
        paint(task, AccountUpdateResponse.ScreenField.ACSGOVT, newCust.govtIssuedId);
        paint(task, AccountUpdateResponse.ScreenField.ACSEFTC, newCust.eftAccountId);
        paint(task, AccountUpdateResponse.ScreenField.ACSPFLG, newCust.priHolderInd);
    }

    /**
     * {@code MOVE <item> TO <field>O OF CACTUPAO} at the output item's declared width.
     *
     * <p>Routed through the codec because most of these moves change width - a 25-character name into a
     * {@code PIC X(25)} item does not, but a 10-character zip into a {@code PIC X(5)} item does, and a
     * {@code PIC X} move truncates on the right.
     *
     * @param task  this interaction's storage
     * @param field the output field
     * @param value the sending item
     */
    void paint(Conversation task, AccountUpdateResponse.ScreenField field, String value) {
        task.cactupao = task.cactupao.withValue(field,
                PIC_X_CODEC.movePicX(value == null ? "" : value,
                        AccountUpdateResponse.declaredLength(field)));
    }

    /**
     * One of {@code 3203-SHOW-UPDATED-VALUES}' five conditional monetary paints.
     *
     * <p>{@code IF FLG-xxx-ISVALID} paints the masked numeric view; the {@code ELSE} paints the raw
     * {@code PIC X(15)} staging item, so text the operator typed that failed {@code TEST-NUMVAL-C} comes
     * back verbatim for them to correct.
     *
     * @param task    this interaction's storage
     * @param field   the output field
     * @param value   the numeric view of the twelve-character span
     * @param staging the {@code ACUP-NEW-xxx-X PIC X(15)} staging item
     */
    void paintMonetary(Conversation task, AccountUpdateResponse.ScreenField field,
                       BigDecimal value, String staging) {
        if (task.flagIsvalid(field)) {
            paint(task, field, editCurrency92(value));
        } else {
            paint(task, field, staging);
        }
    }

    /**
     * {@code 3250-SETUP-INFOMSG} - {@code app/cbl/COACTUPC.cbl:2955-2982}.
     *
     * <p>An {@code EVALUATE TRUE} with nine {@code WHEN}s and <strong>no {@code WHEN OTHER}</strong>, so a
     * state matching none of them leaves {@code WS-INFO-MSG} exactly as it was - which is how a message
     * set by {@code 3202} survives. The absence of the default arm is load-bearing and is reproduced by
     * simply not writing an {@code else}.
     *
     * <p>Two of the arms produce the same text from different states, and two more share
     * {@code INFORM-FAILURE}; they are still written out separately, because collapsing them would lose
     * the source's order and its reachability.
     *
     * <p>The last two statements are unconditional: the information line and the error line are copied
     * into the map area whatever the {@code EVALUATE} decided.
     *
     * @param task this interaction's storage
     */
    void setupInfomsg3250(Conversation task) {
        if (task.carddemoCommarea.isEnter()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_SEARCH_KEYS);
        } else if (task.acupChangeAction.isDetailsNotFetched()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_SEARCH_KEYS);
        } else if (task.acupChangeAction.isShowDetails()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CHANGES);
        } else if (task.acupChangeAction.isChangesNotOk()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CHANGES);
        } else if (task.acupChangeAction.isChangesOkNotConfirmed()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_CONFIRMATION);
        } else if (task.acupChangeAction.isChangesOkayedAndDone()) {
            task.wsInfoMsg = atInfoWidth(INFO_CONFIRM_UPDATE_SUCCESS);
        } else if (task.acupChangeAction.isChangesOkayedLockError()) {
            task.wsInfoMsg = atInfoWidth(INFO_INFORM_FAILURE);
        } else if (task.acupChangeAction.isChangesOkayedButFailed()) {
            task.wsInfoMsg = atInfoWidth(INFO_INFORM_FAILURE);
        } else if (task.wsNoInfoMessage()) {
            task.wsInfoMsg = atInfoWidth(INFO_PROMPT_FOR_SEARCH_KEYS);
        }
        // No WHEN OTHER: WS-INFO-MSG keeps whatever it held.

        // :2979-2981.
        paint(task, AccountUpdateResponse.ScreenField.INFOMSG, task.wsInfoMsg);
        paint(task, AccountUpdateResponse.ScreenField.ERRMSG, task.wsReturnMsg);
    }

    /**
     * {@code 3300-SETUP-SCREEN-ATTRS} - {@code app/cbl/COACTUPC.cbl:2986-3439}.
     *
     * <p>The longest paragraph in the program, and five distinct steps in a fixed order:
     *
     * <ol>
     *   <li>{@code PERFORM 3310-PROTECT-ALL-ATTRS} - protect everything;</li>
     *   <li>an {@code EVALUATE TRUE} that selectively unprotects, by context;</li>
     *   <li>an {@code EVALUATE TRUE} that positions the cursor - first match wins, and the arms are in
     *       screen order;</li>
     *   <li>three unguarded {@code IF}s that colour the account filter, one of which also writes an
     *       asterisk into its value;</li>
     *   <li>a {@code GO TO ...-EXIT} that skips the rest whenever the filter itself is the problem, and
     *       then the 39 {@code CSSETATY} expansions.</li>
     * </ol>
     *
     * <p>Step 5's early exit is why a bad account number never highlights the detail fields: there is
     * nothing meaningful to highlight, because no record was read.
     *
     * @param task this interaction's storage
     */
    void setupScreenAttrs3300(Conversation task) {
        // Step 1 - :2989-2990.
        protectAllAttrs3310(task);

        // Step 2 - :2993-3006.
        unprotectByContext3300(task);

        // Step 3 - :3009-3167. First match wins.
        positionCursor3300(task);

        // Step 4 - :3170-3184.
        // :3171-3173 - arriving from the card list restores the filter's default colour.
        if (task.carddemoCommarea.lastMapset()
                .equals(PIC_X_CODEC.movePicX(LIT_CCLISTMAPSET, NavigationContext.LAST_MAPSET_LENGTH))) {
            task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHDFCOL);
        }
        // :3176-3178 - unlike every CSSETATY site, this one is NOT guarded by REENTER.
        if (task.flgAcctfilterNotOk()) {
            task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }
        // :3180-3184 - the blank case does carry the REENTER guard, and writes the asterisk as well.
        if (task.flgAcctfilterBlank() && task.carddemoCommarea.isReenter()) {
            task.cactupao = task.cactupao.withValue(AccountUpdateResponse.ScreenField.ACCTSID,
                    PIC_X_CODEC.movePicX(FieldAttributeSetter.ASTERISK,
                            AccountUpdateResponse.declaredLength(
                                    AccountUpdateResponse.ScreenField.ACCTSID)));
            task.cactupao.attributes(AccountUpdateResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }

        // Step 5 - :3186-3191, GO TO 3300-SETUP-SCREEN-ATTRS-EXIT.
        if (task.acupChangeAction.isDetailsNotFetched()
                || task.flgAcctfilterBlank()
                || task.flgAcctfilterNotOk()) {
            return;
        }

        // :3208-3439 - the 39 CSSETATY expansions, in source order.
        for (AccountUpdateResponse.ScreenField field : HIGHLIGHT_ORDER) {
            applyHighlight(task, field);
        }
    }

    /**
     * The context {@code EVALUATE TRUE} of {@code 3300-SETUP-SCREEN-ATTRS} -
     * {@code app/cbl/COACTUPC.cbl:2993-3006}, which selectively reverses what {@code 3310} just did.
     *
     * <p>Five {@code WHEN}s over four arms, in source order:
     *
     * <ul>
     *   <li>{@code ACUP-DETAILS-NOT-FETCHED} makes the account filter - and nothing else - writable, which
     *       is the entry screen;</li>
     *   <li>{@code ACUP-SHOW-DETAILS} and {@code ACUP-CHANGES-NOT-OK} share one body and open the detail
     *       fields;</li>
     *   <li>{@code ACUP-CHANGES-OK-NOT-CONFIRMED} and {@code ACUP-CHANGES-OKAYED-AND-DONE} share a
     *       {@code CONTINUE}: everything stays protected while the operator confirms and after the update
     *       has committed, so a stray keystroke cannot change a value that is already validated or already
     *       written;</li>
     *   <li>{@code WHEN OTHER} does what the first arm does.</li>
     * </ul>
     *
     * <p>The third arm is written out rather than folded into the fourth, because they do opposite things:
     * that one leaves the filter protected and this one makes it writable. Omitting it would silently give
     * a confirming operator an editable account number.
     *
     * @param task this interaction's storage
     */
    void unprotectByContext3300(Conversation task) {
        // :2994-2996.
        if (task.acupChangeAction.isDetailsNotFetched()) {
            protect(task, AccountUpdateResponse.ScreenField.ACCTSID, BmsAttributes.DFHBMFSE);
            return;
        }
        // :2997-2999 - two WHENs, one body.
        if (task.acupChangeAction.isShowDetails() || task.acupChangeAction.isChangesNotOk()) {
            unprotectFewAttrs3320(task);
            return;
        }
        // :3000-3003 - two WHENs, one CONTINUE. Deliberately empty: an absent arm would fall through to
        // WHEN OTHER and unprotect the filter.
        if (task.acupChangeAction.isChangesOkNotConfirmed()
                || task.acupChangeAction.isChangesOkayedAndDone()) {
            return;
        }
        // :3004-3005 - WHEN OTHER, the same as the first arm.
        protect(task, AccountUpdateResponse.ScreenField.ACCTSID, BmsAttributes.DFHBMFSE);
    }

    /**
     * One {@code COPY CSSETATY REPLACING} expansion.
     *
     * <p>The decision is {@link FieldAttributeSetter}'s and the two destinations are the response's, so
     * this method carries neither: it reads the field's verdict out of {@code WS-NON-KEY-FLAGS}, converts
     * it to the copybook's own vocabulary, and hands both that and the REENTER state to
     * {@link AccountUpdateResponse#applyHighlight}. Passing REENTER explicitly is gate
     * <strong>G38</strong> - {@code FieldAttributeSetter} deliberately does not import
     * {@link NavigationContext}, so the caller must supply it, which is what makes the guard visible at
     * all 39 sites instead of hidden in one.
     *
     * @param task  this interaction's storage
     * @param field the field being highlighted
     */
    void applyHighlight(Conversation task, AccountUpdateResponse.ScreenField field) {
        FieldAttributeSetter.FieldValidationState state =
                FieldAttributeSetter.FieldValidationState.of(task.flagNotOk(field),
                        task.flagBlank(field));
        task.cactupao = task.cactupao.applyHighlight(field, state,
                task.carddemoCommarea.isReenter());
    }

    /**
     * {@code 3310-PROTECT-ALL-ATTRS} - {@code app/cbl/COACTUPC.cbl:3441-3496}.
     *
     * <p>One {@code MOVE DFHBMPRF} with 49 receivers. {@code DFHBMPRF} is protected plus modified-data-tag
     * set, so the field is read-only but its content is still transmitted back - which is precisely why
     * {@code 1100-RECEIVE-MAP} can receive fields the operator cannot type into, including the customer
     * identifier.
     *
     * @param task this interaction's storage
     */
    void protectAllAttrs3310(Conversation task) {
        for (AccountUpdateResponse.ScreenField field : PROTECTABLE_FIELDS) {
            protect(task, field, BmsAttributes.DFHBMPRF);
        }
    }

    /**
     * {@code 3320-UNPROTECT-FEW-ATTRS} - {@code app/cbl/COACTUPC.cbl:3500-3562}.
     *
     * <p>Runs immediately after {@code 3310} and reverses it for the {@value #UNPROTECTED_FIELD_COUNT}
     * fields the operator may edit, leaving four of the {@value #PROTECTABLE_FIELD_COUNT} protected. The source achieves that with seven separate {@code MOVE}s that alternate
     * between {@code DFHBMFSE} and {@code DFHBMPRF}; because {@code 3310} has already written
     * {@code DFHBMPRF} everywhere, the three {@code DFHBMPRF} moves here are re-assertions of a value
     * already in place, and only the {@code DFHBMFSE} ones change anything. The list therefore captures
     * the whole effect.
     *
     * @param task this interaction's storage
     */
    void unprotectFewAttrs3320(Conversation task) {
        for (AccountUpdateResponse.ScreenField field : UNPROTECTED_FIELDS) {
            protect(task, field, BmsAttributes.DFHBMFSE);
        }
        // :3536, :3555, :3567 - the three re-assertions, written out so the paragraph's own text is
        // traceable and so a reader is not left wondering whether they were missed.
        protect(task, AccountUpdateResponse.ScreenField.ACSTNUM, BmsAttributes.DFHBMPRF);
        protect(task, AccountUpdateResponse.ScreenField.ACSCTRY, BmsAttributes.DFHBMPRF);
        protect(task, AccountUpdateResponse.ScreenField.INFOMSG, BmsAttributes.DFHBMPRF);
    }

    /**
     * {@code MOVE <attribute> TO <field>A OF CACTUPAI}.
     *
     * <p>The receiver is in the <strong>input</strong> map area, because {@code xxxA} is a
     * {@code REDEFINES} of the input group's {@code xxxF} flag byte. It reaches the client as
     * {@link ScreenMetadata} rather than as a payload member, per AAP 0.6.3.
     *
     * @param task      this interaction's storage
     * @param field     the field whose attribute byte is written
     * @param attribute the {@code DFHBMSCA} constant moved
     */
    void protect(Conversation task, AccountUpdateResponse.ScreenField field, byte attribute) {
        task.cactupai = task.cactupai.withAttribute(
                AccountUpdateRequest.ScreenField.valueOf(field.name()), attribute);
    }

    /**
     * The cursor-positioning {@code EVALUATE TRUE} of {@code 3300-SETUP-SCREEN-ATTRS} -
     * {@code app/cbl/COACTUPC.cbl:3009-3167}.
     *
     * <p>{@code MOVE -1 TO xxxL} is how a BMS program says "put the cursor here". The {@code EVALUATE}
     * takes the first matching arm, so exactly one field gets it, and the arms are ordered by position on
     * the screen - which means the operator is taken to the topmost problem.
     *
     * <p>The two leading arms are not field verdicts. {@code FOUND-ACCOUNT-DATA} and
     * {@code NO-CHANGES-DETECTED} are both 88-levels on message items, and both send the cursor to the
     * account-status field - the first editable field on the screen - because in both cases the record is
     * displayed and there is nothing wrong with it.
     *
     * @param task this interaction's storage
     */
    void positionCursor3300(Conversation task) {
        // :3011-3014 - the record is on the screen and healthy.
        if (task.foundAccountData() || task.noChangesDetected()) {
            placeCursor(task, AccountUpdateResponse.ScreenField.ACSTTUS);
            return;
        }
        // :3015-3017 - the filter itself is wrong.
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            placeCursor(task, AccountUpdateResponse.ScreenField.ACCTSID);
            return;
        }
        // :3019-3164 - every field, in screen order.
        for (AccountUpdateResponse.ScreenField field : CURSOR_ORDER) {
            if (cursorArmMatches(task, field)) {
                placeCursor(task, field);
                return;
            }
        }
        // :3165-3166 - WHEN OTHER.
        placeCursor(task, AccountUpdateResponse.ScreenField.ACCTSID);
    }

    /**
     * Whether one arm of the cursor {@code EVALUATE} matches.
     *
     * <p>Every arm but one is the pair {@code WHEN FLG-xxx-NOT-OK / WHEN FLG-xxx-BLANK}. The exception is
     * the middle name at {@code app/cbl/COACTUPC.cbl:3122-3123}, which has a {@code NOT-OK} arm and
     * <strong>no</strong> {@code BLANK} arm - correctly, because {@code 1235-EDIT-ALPHA-OPT} treats a
     * blank middle name as valid and never sets its blank flag, so an arm for it would be dead code. The
     * asymmetry is honoured rather than smoothed over (practice B5).
     *
     * @param task  this interaction's storage
     * @param field the field whose arm is being evaluated
     * @return {@code true} when the source's arm for this field would match
     */
    boolean cursorArmMatches(Conversation task, AccountUpdateResponse.ScreenField field) {
        if (field == AccountUpdateResponse.ScreenField.ACSMNAM) {
            return task.flagNotOk(field);
        }
        return task.flagNotOk(field) || task.flagBlank(field);
    }

    /**
     * {@code MOVE -1 TO <field>L OF CACTUPAI}.
     *
     * <p>Records the choice twice: as the length item's value on the request's field metadata, which is
     * what the map area actually holds, and as the {@code DFHMDF} label on the conversation, which is what
     * {@link ScreenMetadata} carries to the client so it can focus the field.
     *
     * @param task  this interaction's storage
     * @param field the field the cursor moves to
     */
    void placeCursor(Conversation task, AccountUpdateResponse.ScreenField field) {
        AccountUpdateRequest.ScreenField inputField =
                AccountUpdateRequest.ScreenField.valueOf(field.name());
        task.cactupai = task.cactupai.withMetadata(inputField,
                task.cactupai.metadata(inputField).withLengthItem(CURSOR_HERE));
        task.cursorField = field.label();
    }

    /**
     * {@code 3390-SETUP-INFOMSG-ATTRS} - {@code app/cbl/COACTUPC.cbl:3566-3585}.
     *
     * <p>Three independent {@code IF}s, none of them exclusive:
     *
     * <ul>
     *   <li>the information line is dark when there is no message and bright when there is - which is how
     *       an empty line is hidden rather than shown as blanks;</li>
     *   <li>{@code F12=Cancel} is revealed whenever changes have been made and the update has not already
     *       been committed;</li>
     *   <li>{@code F5=Save} <em>and</em> {@code F12=Cancel} are both revealed while confirmation is
     *       pending - the second write is redundant when the previous {@code IF} already fired, and the
     *       source performs it anyway.</li>
     * </ul>
     *
     * @param task this interaction's storage
     */
    void setupInfomsgAttrs3390(Conversation task) {
        // :3567-3571.
        task.cactupao.applyInfoMessageVisibility(!task.wsNoInfoMessage());

        // :3573-3576.
        if (task.acupChangeAction.isChangesMade()
                && !task.acupChangeAction.isChangesOkayedAndDone()) {
            task.cactupao.revealCancelLegend();
        }

        // :3578-3581 - PROMPT-FOR-CONFIRMATION is an 88 on WS-INFO-MSG, so the test is on the text.
        if (task.promptForConfirmation()) {
            task.cactupao.revealSaveLegend();
            task.cactupao.revealCancelLegend();
        }
    }

    /**
     * {@code 3400-SEND-SCREEN} - {@code app/cbl/COACTUPC.cbl:3589-3605}.
     *
     * <p>Publishes this program's own mapset and map as the next target, then sends. The
     * {@code EXEC CICS SEND MAP ... CURSOR ERASE FREEKB} becomes the response itself: {@code CURSOR}
     * honours the {@code MOVE -1} of the previous paragraph and is carried as
     * {@link ScreenMetadata#cursorField()}, {@code ERASE} as
     * {@link ScreenMetadata#resetAllOutputFields()}, and {@code FREEKB} needs no representation because
     * an HTTP response does not lock a keyboard.
     *
     * @param task this interaction's storage
     */
    void sendScreen3400(Conversation task) {
        // :3591-3592.
        task.ccWorkArea.setCcardNextMapset(PIC_X_CODEC.movePicX(LIT_THISMAPSET,
                CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(PIC_X_CODEC.movePicX(LIT_THISMAP,
                CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    // =================================================================================================
    // The read path - app/cbl/COACTUPC.cbl:3608-3886.
    //
    // Three keyed reads and one store, and the whole of this file's data access. The datasets are named
    // by the CICS file literals LIT-CARDXREFNAME-ACCT-PATH, LIT-ACCTFILENAME and LIT-CUSTFILENAME; those
    // are file NAMES, not dataset names, and the repositories resolve the DSN from carddemo.datasets.*
    // configuration, so no AWS.M2.CARDDEMO literal appears anywhere in this class (gate G46).
    // =================================================================================================

    /**
     * {@code 9000-READ-ACCT} - {@code app/cbl/COACTUPC.cbl:3608-3648}.
     *
     * <p>A guard chain: cross-reference, then account master, then customer master, then store. Each step
     * has a {@code GO TO 9000-READ-ACCT-EXIT} after it, so a failure stops the chain.
     *
     * <p>Two of those three guards <strong>cannot fire</strong>. {@code IF DID-NOT-FIND-ACCT-IN-ACCTDAT}
     * at {@code :3628} and {@code IF DID-NOT-FIND-CUST-IN-CUSTDAT} at {@code :3636} test 88-levels on
     * {@code WS-RETURN-MSG} whose {@code SET} statements are commented out in {@code 9300} ({@code :3720})
     * and {@code 9400} ({@code :3768}) - the paragraphs set {@code FLG-ACCTFILTER-NOT-OK} and
     * {@code FLG-CUSTFILTER-NOT-OK} instead and compose a different message. The guards are therefore
     * ineffective, and the chain runs on to {@code 9500} after a failed account or customer read. They are
     * reproduced exactly, because removing them would change nothing today and would erase the evidence
     * of the defect (practice B5).
     *
     * <p>In practice the account-read failure is still caught, one step later and by a different flag:
     * {@code 9300}'s {@code FLG-ACCTFILTER-NOT-OK} is what {@code 2000-DECIDE-ACTION} sees through
     * {@code FOUND-CUST-IN-MASTER} being unset.
     *
     * @param task this interaction's storage
     */
    void readAcct9000(Conversation task) {
        // :3610 - INITIALIZE ACUP-OLD-DETAILS. Both halves.
        task.acupOldAcct.initialize();
        task.acupOldCust.initialize();

        // :3612 - SET WS-NO-INFO-MESSAGE TO TRUE, an 88 on WS-INFO-MSG with VALUE SPACES.
        task.wsInfoMsg = atInfoWidth("");

        // :3614-3615 - one source, two receivers. ACUP-OLD-ACCT-ID is the PIC 9(11) redefinition.
        task.acupOldAcct.setAcctIdN(task.ccWorkArea.getCcAcctIdN());
        task.wsCardRidAcctId = PIC_X_CODEC.movePicX(task.ccWorkArea.getCcAcctId(),
                WS_CARD_RID_ACCT_ID_LENGTH);

        // :3617-3618.
        getCardXrefByAcct9200(task);
        // :3620-3622.
        if (task.flgAcctfilterNotOk()) {
            return;
        }

        // :3624-3625.
        getAcctDataByAcct9300(task);
        // :3627-3629 - the first ineffective guard.
        if (task.didNotFindAcctInAcctdat()) {
            return;
        }

        // :3631 - MOVE CDEMO-CUST-ID TO WS-CARD-RID-CUST-ID, the PIC 9(9) receiver.
        task.wsCardRidCustId = PIC_X_CODEC.movePic9(Long.toString(task.carddemoCommarea.custId()),
                WS_CARD_RID_CUST_ID_LENGTH);

        // :3633-3634.
        getCustDataByCust9400(task);
        // :3636-3638 - the second ineffective guard.
        if (task.didNotFindCustInCustdat()) {
            return;
        }

        // :3643-3644.
        storeFetchedData9500(task);
    }

    /**
     * {@code 9200-GETCARDXREF-BYACCT} - {@code app/cbl/COACTUPC.cbl:3650-3699}.
     *
     * <p>{@code EXEC CICS READ DATASET(LIT-CARDXREFNAME-ACCT-PATH)} - the {@code CXACAIX} <strong>path over
     * the CCXREF base</strong>, so this is a keyed read on the alternate key and not a second dataset
     * (gate <strong>G45</strong>). {@link CardXrefRepository} exposes it as an additional finder on the
     * same repository.
     *
     * <p>Three arms. {@code NORMAL} publishes the customer identifier and the card number into the
     * communication area, which is what makes the following two reads possible. {@code NOTFND} raises
     * {@code INPUT-ERROR} and {@code FLG-ACCTFILTER-NOT-OK} and composes a message - guarded, so an
     * earlier edit's text wins. {@code WHEN OTHER} composes the generic file-error text and does so
     * <strong>unguarded</strong>: an unexpected response overwrites whatever message was there. That
     * asymmetry between the {@code NOTFND} and {@code OTHER} arms is in all three read paragraphs and is
     * preserved.
     *
     * @param task this interaction's storage
     */
    void getCardXrefByAcct9200(Conversation task) {
        CardXrefRepository.ReadResult result =
                cardXrefRepository.readByAccountIdViaAltIndex(task.wsCardRidAcctId);
        task.errorResp = respImage(result.cicsResp());
        task.errorResp2 = respImage(result.cicsResp2());

        if (result.isFound()) {
            // :3667-3669.
            CardXrefRecord xref = result.record().orElseThrow(() -> new IllegalStateException(
                    "9200-GETCARDXREF-BYACCT reached its DFHRESP(NORMAL) arm without a record; "
                    + "EXEC CICS READ with RESP(NORMAL) always fills INTO(CARD-XREF-RECORD)"));
            task.carddemoCommarea = task.carddemoCommarea
                    .withCustId(xref.xrefCustId())
                    .withCardNum(Long.parseLong(PIC_X_CODEC.movePic9(xref.xrefCardNum(),
                            NavigationContext.CARD_NUM_LENGTH)));
            return;
        }

        if (result.isNotFound()) {
            // :3671-3686.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            stringIntoReturnMsgIfOff(task, STRING_ACCOUNT_PREFIX
                    + task.wsCardRidAcctId
                    + STRING_NOT_FOUND_IN
                    + STRING_CROSS_REF_FILE
                    + task.errorResp
                    + STRING_REAS
                    + task.errorResp2);
            return;
        }

        // :3688-3696 - WHEN OTHER, unguarded.
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(LIT_CARDXREFNAME_ACCT_PATH, ERROR_FILE_LENGTH);
        task.wsReturnMsg = atReturnWidth(fileErrorMessage(task));
    }

    /**
     * {@code 9300-GETACCTDATA-BYACCT} - {@code app/cbl/COACTUPC.cbl:3701-3750}.
     *
     * <p>{@code EXEC CICS READ DATASET(LIT-ACCTFILENAME)} on the base {@code ACCTDAT} key. {@code NORMAL}
     * sets {@code FOUND-ACCT-IN-MASTER}, which {@code 3202-SHOW-ORIGINAL-VALUES} needs before it will
     * paint the account half.
     *
     * <p>Note {@code :3720}: {@code SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE} is commented out, which is
     * what makes {@code 9000}'s following guard ineffective. See {@link #readAcct9000}.
     *
     * @param task this interaction's storage
     */
    void getAcctDataByAcct9300(Conversation task) {
        AccountRepository.ReadResult result =
                accountRepository.readByKey(task.wsCardRidAcctIdN());
        task.errorResp = respImage(result.cicsResp());
        task.errorResp2 = respImage(result.cicsResp2());

        if (result.isFound()) {
            // :3714.
            task.accountRecord = result.account().orElseThrow(() -> new IllegalStateException(
                    "9300-GETACCTDATA-BYACCT reached its DFHRESP(NORMAL) arm without a record; "
                    + "EXEC CICS READ with RESP(NORMAL) always fills INTO(ACCOUNT-RECORD)"));
            task.wsAccountMasterReadFlag = FOUND_IN_MASTER;
            return;
        }

        if (result.isNotFound()) {
            // :3716-3735.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            stringIntoReturnMsgIfOff(task, STRING_ACCOUNT_PREFIX
                    + task.wsCardRidAcctId
                    + STRING_NOT_FOUND_IN
                    + STRING_ACCT_MASTER_FILE
                    + task.errorResp
                    + STRING_REAS
                    + task.errorResp2);
            return;
        }

        // :3738-3747 - WHEN OTHER, unguarded.
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(LIT_ACCTFILENAME, ERROR_FILE_LENGTH);
        task.wsReturnMsg = atReturnWidth(fileErrorMessage(task));
    }

    /**
     * {@code 9400-GETCUSTDATA-BYCUST} - {@code app/cbl/COACTUPC.cbl:3752-3799}.
     *
     * <p>{@code EXEC CICS READ DATASET(LIT-CUSTFILENAME)} on the customer identifier the cross-reference
     * supplied. {@code NORMAL} sets {@code FOUND-CUST-IN-MASTER}, which is both what
     * {@code 3202-SHOW-ORIGINAL-VALUES} needs for the customer half and what
     * {@code 2000-DECIDE-ACTION} tests to decide the read succeeded overall.
     *
     * <p>One difference from {@code 9200} and {@code 9300}: the two {@code MOVE}s that stage the response
     * codes sit <strong>outside</strong> the {@code IF WS-RETURN-MSG-OFF} guard here ({@code :3769-3770})
     * where the siblings have them inside. It has no effect on the message - the guard still decides
     * whether the text is composed - but it does mean {@code ERROR-RESP} and {@code ERROR-RESP2} are
     * updated even when the message is discarded. Reproduced: both are assigned before the guard.
     *
     * @param task this interaction's storage
     */
    void getCustDataByCust9400(Conversation task) {
        CustomerRepository.ReadResult result = customerRepository.readByKey(task.wsCardRidCustId);
        // :3769-3770 - outside the guard, unlike the sibling paragraphs.
        task.errorResp = respImage(result.cicsResp());
        task.errorResp2 = respImage(result.cicsResp2());

        if (result.isFound()) {
            // :3765.
            task.customerRecord = result.customer().orElseThrow(() -> new IllegalStateException(
                    "9400-GETCUSTDATA-BYCUST reached its DFHRESP(NORMAL) arm without a record; "
                    + "EXEC CICS READ with RESP(NORMAL) always fills INTO(CUSTOMER-RECORD)"));
            task.wsCustMasterReadFlag = FOUND_IN_MASTER;
            return;
        }

        if (result.isNotFound()) {
            // :3766-3784.
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCustFlag = FLG_FILTER_NOT_OK;
            stringIntoReturnMsgIfOff(task, STRING_CUSTID_PREFIX
                    + task.wsCardRidCustId
                    + STRING_NOT_FOUND
                    + STRING_IN_CUSTOMER_MASTER
                    + task.errorResp
                    + STRING_REAS_UPPER
                    + task.errorResp2);
            return;
        }

        // :3786-3795 - WHEN OTHER, unguarded.
        task.wsInputFlag = INPUT_ERROR;
        task.wsEditCustFlag = FLG_FILTER_NOT_OK;
        task.errorOpname = PIC_X_CODEC.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = PIC_X_CODEC.movePicX(LIT_CUSTFILENAME, ERROR_FILE_LENGTH);
        task.wsReturnMsg = atReturnWidth(fileErrorMessage(task));
    }

    /**
     * {@code MOVE WS-RESP-CD TO ERROR-RESP} - the {@code PIC 9(09)} staging of a CICS response code inside
     * {@code WS-FILE-ERROR-MESSAGE} ({@code app/cbl/COACTUPC.cbl:389-404}).
     *
     * <p>The receiver is numeric and nine digits wide, so the value is zero filled on the left. An absent
     * response - which is what a repository reports for an outcome CICS never produced - stages as nine
     * zeros, the same image {@code MOVE ZEROES} would leave.
     *
     * <p>The receiver is {@code ERROR-RESP PIC X(10)} while {@code WS-RESP-CD} is {@code PIC S9(09) COMP},
     * so the move is numeric-to-alphanumeric: the sender becomes its nine-digit unsigned display form and
     * that is then moved alphanumerically - left justified, so the tenth character is a space. Both steps
     * are performed, because collapsing them would drop either a leading zero or the trailing space.
     *
     * @param resp the response code, possibly absent
     * @return exactly {@value #ERROR_RESP_LENGTH} characters
     */
    static String respImage(OptionalInt resp) {
        return respImage(resp.orElse(0));
    }

    /**
     * {@code MOVE WS-RESP-CD TO ERROR-RESP} where the repository reports the code as a plain
     * {@code int} rather than an {@link OptionalInt}.
     *
     * @param resp the response code
     * @return exactly {@value #ERROR_RESP_LENGTH} characters
     */
    static String respImage(int resp) {
        return PIC_X_CODEC.movePicX(
                PIC_X_CODEC.movePic9(Integer.toString(resp), RESP_CODE_DIGITS), ERROR_RESP_LENGTH);
    }

    /**
     * {@code WS-FILE-ERROR-MESSAGE} - {@code app/cbl/COACTUPC.cbl:389-404}.
     *
     * <p>A group item of literals and staged values, not a {@code STRING}: the {@code WHEN OTHER} arms
     * {@code MOVE} the whole group into {@code WS-RETURN-MSG} in one statement, so its layout is fixed and
     * its total width is 80 - wider than the {@value #WS_RETURN_MSG_LENGTH}-character receiver, which
     * means the trailing filler is truncated on the way in. Composing it at its declared width and letting
     * {@link #atReturnWidth} truncate reproduces that.
     *
     * @param task this interaction's storage
     * @return exactly {@value #FILE_ERROR_MESSAGE_LENGTH} characters
     */
    String fileErrorMessage(Conversation task) {
        return PIC_X_CODEC.movePicX(FILE_ERROR_PREFIX
                + task.errorOpname
                + FILE_ERROR_ON
                + task.errorFile
                + FILE_ERROR_RETURNED_RESP
                + task.errorResp
                + FILE_ERROR_RESP2
                + task.errorResp2
                + FILE_ERROR_TRAILER, FILE_ERROR_MESSAGE_LENGTH);
    }

    /**
     * {@code 9500-STORE-FETCHED-DATA} - {@code app/cbl/COACTUPC.cbl:3801-3886}.
     *
     * <p>Two halves. The first publishes six values into the communication area so a subsequent
     * transaction inherits the context. The second fills {@code ACUP-OLD-DETAILS} from the two records,
     * and that group is what {@code 1205-COMPARE-OLD-NEW} compares the screen against and what
     * {@code 9700-CHECK-CHANGE-IN-REC} compares the file against - so its fidelity is the whole basis of
     * the optimistic-concurrency check.
     *
     * <p>{@code INITIALIZE ACUP-OLD-DETAILS} appears at {@code :3813} even though {@code 9000-READ-ACCT}
     * already did it at {@code :3610} and nothing has written the group since. The second one is
     * redundant and is reproduced.
     *
     * <p><strong>It can be reached without a record having been read.</strong> {@code 9000-READ-ACCT}
     * guards this call with {@code DID-NOT-FIND-ACCT-IN-ACCTDAT} and
     * {@code DID-NOT-FIND-CUST-IN-CUSTDAT}, but the two statements that would set those conditions are
     * commented out at {@code :3720} and {@code :3769}, so neither guard ever fires. A {@code NOTFND} read
     * therefore arrives here with its {@code WORKING-STORAGE} area unchanged, and this paragraph stores
     * that image - zero identifiers, spaces - into {@code ACUP-OLD-DETAILS} and the communication area.
     * The two areas are read as they stand, with no assertion that a read filled them: the wrong value is
     * the original's and is preserved (practice <strong>B5</strong>).
     *
     * <p>The dates arrive as {@code PIC X(10)} {@code YYYY-MM-DD} in both records and are split
     * {@code (1:4)}, {@code (6:2)}, {@code (9:2)} - skipping the two hyphens - into the eight-character
     * spans. The commented-out whole-field {@code MOVE}s above each split ({@code :3831}, {@code :3838},
     * {@code :3845}, {@code :3856}) record that the author replaced a ten-into-eight move, which would
     * have kept the hyphens and dropped the day.
     *
     * @param task this interaction's storage
     */
    void storeFetchedData9500(Conversation task) {
        // Both areas are read exactly as they stand, with no test that a read filled them - because this
        // paragraph is reached after a failed read as well as a successful one. :3720 and :3769 comment
        // out the two SET DID-NOT-FIND-* statements, so 9000's guards never fire, and what gets stored is
        // then the area's unchanged image: account id zero, customer id zero, spaces. That wrong value
        // going into ACUP-OLD-DETAILS and the commarea is the original's defect and is preserved
        // (practice B5). Demanding a record here instead raised an abend the source cannot produce.
        AccountRecord account = task.accountRecord;
        CustomerRecord customer = task.customerRecord;

        // :3805-3811 - the communication area. CDEMO-CARD-NUM comes from the cross-reference, which is
        // why the card number the operator sees survives a customer-record change.
        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(account.getAcctId())
                .withCustId(customer.getCustId())
                .withCustFname(PIC_X_CODEC.movePicX(customer.getCustFirstName(),
                        NavigationContext.CUST_FNAME_LENGTH))
                .withCustMname(PIC_X_CODEC.movePicX(customer.getCustMiddleName(),
                        NavigationContext.CUST_MNAME_LENGTH))
                .withCustLname(PIC_X_CODEC.movePicX(customer.getCustLastName(),
                        NavigationContext.CUST_LNAME_LENGTH))
                .withAcctStatus(PIC_X_CODEC.movePicX(account.getAcctActiveStatus(),
                        NavigationContext.ACCT_STATUS_LENGTH))
                .withCardNum(task.carddemoCommarea.cardNum());

        // :3813 - the redundant second INITIALIZE.
        task.acupOldAcct.initialize();
        task.acupOldCust.initialize();

        // :3817-3849 - the account master half.
        AcctDataArea oldAcct = task.acupOldAcct;
        oldAcct.setAcctIdN(account.getAcctId());
        oldAcct.activeStatus = PIC_X_CODEC.movePicX(account.getAcctActiveStatus(),
                AccountUpdateRequest.AcctSnapshot.ACTIVE_STATUS_LENGTH);
        oldAcct.setCurrBalN(account.getAcctCurrBal());
        oldAcct.setCreditLimitN(account.getAcctCreditLimit());
        oldAcct.setCashCreditLimitN(account.getAcctCashCreditLimit());
        oldAcct.setCurrCycCreditN(account.getAcctCurrCycCredit());
        oldAcct.setCurrCycDebitN(account.getAcctCurrCycDebit());
        oldAcct.setOpenYear(datePartYear(account.getAcctOpenDate()));
        oldAcct.setOpenMon(datePartMonth(account.getAcctOpenDate()));
        oldAcct.setOpenDay(datePartDay(account.getAcctOpenDate()));
        oldAcct.setExpYear(datePartYear(account.getAcctExpiraionDate()));
        oldAcct.setExpMon(datePartMonth(account.getAcctExpiraionDate()));
        oldAcct.setExpDay(datePartDay(account.getAcctExpiraionDate()));
        oldAcct.setReissueYear(datePartYear(account.getAcctReissueDate()));
        oldAcct.setReissueMon(datePartMonth(account.getAcctReissueDate()));
        oldAcct.setReissueDay(datePartDay(account.getAcctReissueDate()));
        oldAcct.groupId = PIC_X_CODEC.movePicX(account.getAcctGroupId(),
                AccountUpdateRequest.AcctSnapshot.GROUP_ID_LENGTH);

        // :3853-3886 - the customer master half.
        CustDataArea oldCust = task.acupOldCust;
        oldCust.custIdX = PIC_X_CODEC.movePic9(Integer.toString(customer.getCustId()),
                AccountUpdateRequest.CustSnapshot.CUST_ID_LENGTH);
        oldCust.ssnX = PIC_X_CODEC.movePic9(Integer.toString(customer.getCustSsn()),
                AccountUpdateRequest.CustSnapshot.SSN_LENGTH);
        oldCust.setDobYear(datePartYear(customer.getCustDobYyyyMmDd()));
        oldCust.setDobMon(datePartMonth(customer.getCustDobYyyyMmDd()));
        oldCust.setDobDay(datePartDay(customer.getCustDobYyyyMmDd()));
        oldCust.ficoScoreX = PIC_X_CODEC.movePic9(
                Integer.toString(customer.getCustFicoCreditScore()),
                AccountUpdateRequest.CustSnapshot.FICO_SCORE_LENGTH);
        oldCust.firstName = PIC_X_CODEC.movePicX(customer.getCustFirstName(),
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        oldCust.middleName = PIC_X_CODEC.movePicX(customer.getCustMiddleName(),
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        oldCust.lastName = PIC_X_CODEC.movePicX(customer.getCustLastName(),
                AccountUpdateRequest.CustSnapshot.NAME_LENGTH);
        oldCust.addrLine1 = PIC_X_CODEC.movePicX(customer.getCustAddrLine1(),
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        oldCust.addrLine2 = PIC_X_CODEC.movePicX(customer.getCustAddrLine2(),
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        oldCust.addrLine3 = PIC_X_CODEC.movePicX(customer.getCustAddrLine3(),
                AccountUpdateRequest.CustSnapshot.ADDR_LINE_LENGTH);
        oldCust.addrStateCd = PIC_X_CODEC.movePicX(customer.getCustAddrStateCd(),
                AccountUpdateRequest.CustSnapshot.ADDR_STATE_CD_LENGTH);
        oldCust.addrCountryCd = PIC_X_CODEC.movePicX(customer.getCustAddrCountryCd(),
                AccountUpdateRequest.CustSnapshot.ADDR_COUNTRY_CD_LENGTH);
        oldCust.addrZip = PIC_X_CODEC.movePicX(customer.getCustAddrZip(),
                AccountUpdateRequest.CustSnapshot.ADDR_ZIP_LENGTH);
        oldCust.phoneNum1 = PIC_X_CODEC.movePicX(customer.getCustPhoneNum1(),
                AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
        oldCust.phoneNum2 = PIC_X_CODEC.movePicX(customer.getCustPhoneNum2(),
                AccountUpdateRequest.CustSnapshot.PHONE_NUM_LENGTH);
        oldCust.govtIssuedId = PIC_X_CODEC.movePicX(customer.getCustGovtIssuedId(),
                AccountUpdateRequest.CustSnapshot.GOVT_ISSUED_ID_LENGTH);
        oldCust.eftAccountId = PIC_X_CODEC.movePicX(customer.getCustEftAccountId(),
                AccountUpdateRequest.CustSnapshot.EFT_ACCOUNT_ID_LENGTH);
        oldCust.priHolderInd = PIC_X_CODEC.movePicX(customer.getCustPriCardHolderInd(),
                AccountUpdateRequest.CustSnapshot.PRI_HOLDER_IND_LENGTH);
    }

    /**
     * {@code <record date>(1:4)} - the year of a stored {@code PIC X(10)} {@code YYYY-MM-DD} date.
     *
     * @param recordDate the stored date
     * @return four characters
     */
    static String datePartYear(String recordDate) {
        return slice(PIC_X_CODEC.movePicX(recordDate, RECORD_DATE_LENGTH),
                RECORD_DATE_YEAR_OFFSET, AccountUpdateRequest.AcctSnapshot.DATE_YEAR_LENGTH);
    }

    /**
     * {@code <record date>(6:2)} - the month, skipping the first hyphen.
     *
     * @param recordDate the stored date
     * @return two characters
     */
    static String datePartMonth(String recordDate) {
        return slice(PIC_X_CODEC.movePicX(recordDate, RECORD_DATE_LENGTH),
                RECORD_DATE_MONTH_OFFSET, AccountUpdateRequest.AcctSnapshot.DATE_MONTH_LENGTH);
    }

    /**
     * {@code <record date>(9:2)} - the day, skipping the second hyphen.
     *
     * @param recordDate the stored date
     * @return two characters
     */
    static String datePartDay(String recordDate) {
        return slice(PIC_X_CODEC.movePicX(recordDate, RECORD_DATE_LENGTH),
                RECORD_DATE_DAY_OFFSET, AccountUpdateRequest.AcctSnapshot.DATE_DAY_LENGTH);
    }

    // =================================================================================================
    // ABEND-ROUTINE - app/cbl/COACTUPC.cbl:4203-4227 - and the response projection.
    // =================================================================================================

    /**
     * {@code ABEND-ROUTINE} - {@code app/cbl/COACTUPC.cbl:4203-4227}, reached from
     * {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER}.
     *
     * <p>Four steps: default the message if it is unset, name the culprit, send {@code ABEND-DATA} to the
     * terminal, cancel the abend handler so this routine cannot re-enter itself, and abend with
     * {@code ABCODE('9999')}.
     *
     * <p>{@code CSMSG02Y}'s {@code ABEND-DATA} group is what the operator sees, and
     * {@link SystemMessages} owns its layout. The {@code HANDLE ABEND CANCEL} is why
     * {@link #handle} rethrows an {@link AbendException} untouched instead of wrapping it.
     *
     * @param task      this interaction's storage
     * @param abendCode the value moved to {@code ABEND-CODE}
     * @param abendMsg  the value moved to {@code ABEND-MSG}
     * @return the exception to throw, so the caller's {@code throw} is visible at the call site
     */
    AbendException abendRoutine(Conversation task, String abendCode, String abendMsg) {
        // :2646-2649 - the WHEN OTHER arm's four moves, which reach this routine through WS-ABEND-DATA.
        task.abendData = task.abendData
                .withAbendCode(abendCode)
                .withAbendReason(spaces(SystemMessages.ABEND_REASON_LENGTH))
                .withAbendMsg(abendMsg);
        return abendRoutine(task, (RuntimeException) null);
    }

    /**
     * The {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} path at {@code :862-864}: any failure the
     * flow did not itself handle arrives here, and also the tail of the overload above.
     *
     * <p>{@code ABEND-DATA} is {@code CSMSG02Y}'s group and {@link SystemMessages.AbendData} owns its
     * four spans; {@code :4205-4207} defaults the message only when it is still {@code LOW-VALUES}, and
     * {@code :4209} names the culprit unconditionally.
     *
     * <p>The triggering exception is <strong>described</strong> to the log, never handed to it. Passing a
     * throwable to Commons Logging emits its message and its whole cause chain verbatim, and a JDBC
     * driver composes that text around the record it refused - so a record image could reach a log line,
     * and an embedded carriage return or newline in it could forge a second entry (CWE-117).
     * {@link BackendDiagnostic} carries the SQLSTATE, the vendor code and the exception type and has no
     * component for the driver's message at all. Nothing is discarded: the throwable travels as the cause
     * of the returned {@link AbendException}.
     *
     * @param task  this interaction's storage
     * @param cause the failure that reached the handler, or {@code null} when the program abended on its
     *              own logic and there is no exception to describe
     * @return the exception to throw, so the caller's {@code throw} is visible at the call site
     */
    AbendException abendRoutine(Conversation task, RuntimeException cause) {
        SystemMessages.AbendData abendData = task.abendData;

        // :4205-4207 - IF ABEND-MSG EQUAL LOW-VALUES MOVE 'UNEXPECTED ABEND OCCURRED.'
        //
        // This guard can never fire, and the reason is in the copybook: CSMSG02Y declares
        // ABEND-MSG PIC X(72) VALUE SPACES, so the item starts as spaces and the only statement that
        // ever writes it - 2000-DECIDE-ACTION's WHEN OTHER at :2637-2638 - writes a real literal. It is
        // therefore SPACES or a message, never LOW-VALUES, and 'UNEXPECTED ABEND OCCURRED.' is dead
        // code. Reproduced exactly, including its unreachability: an abend that arrives through
        // HANDLE ABEND logs a blank message here, which is what the operator's terminal would have
        // shown (practice B5).
        if (lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendData.abendMsg())) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }
        // :4209 - MOVE LIT-THISPGM TO ABEND-CULPRIT, unconditionally.
        abendData = abendData
                .withAbendCulprit(PIC_X_CODEC.movePicX(LIT_THISPGM,
                        SystemMessages.ABEND_CULPRIT_LENGTH))
                .toDeclaredWidths();
        task.abendData = abendData;

        // :4211-4217 - EXEC CICS SEND FROM(ABEND-DATA) ERASE NOHANDLE. There is no terminal here, so the
        // four spans are logged as the one image the operator would have seen.
        LOG.error("app/cbl/COACTUPC.cbl:4203 ABEND-ROUTINE: " + abendDataImage(abendData)
                + " ABCODE " + ABEND_ROUTINE_ABCODE + " ["
                + (cause == null ? NO_TRIGGERING_FAILURE : BackendDiagnostic.of(cause).describe())
                + ']');

        // ERASE clears the screen and sends the structure instead, so no map reaches the operator; the
        // response is left carrying whatever had been painted and the interaction is over.
        task.returned = true;

        // :4219-4227 - HANDLE ABEND CANCEL then ABEND ABCODE('9999'). The four-character ABCODE is not a
        // return code, so it travels in the reason and the exception's own code is the I/O-error status
        // the batch convention uses for an unrecoverable task.
        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + trim(abendData.abendMsg()), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    /**
     * The four spans of {@code ABEND-DATA} concatenated at their declared widths, which is the image
     * {@code EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA)} would have transmitted.
     *
     * @param abendData the group
     * @return exactly {@link SystemMessages#ABEND_DATA_LENGTH} characters
     */
    String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }

    /**
     * Projects the attribute layer both map areas carry into the transport form the client receives.
     *
     * <p>Per AAP 0.6.3 the symbolic map's {@code xxxL}, {@code xxxA}, {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV} items are validation and highlight <strong>metadata</strong> and never
     * JSON payload members. This is where they leave: one {@link ScreenMetadata.FieldMetadata} quad per
     * named field, keyed by its {@code DFHMDF} label.
     *
     * <p>The protection byte comes from the <em>request</em> area, because {@code xxxA} redefines the input
     * group's flag byte, while colour, highlight and validation come from the <em>response</em> area,
     * because {@code xxxC}, {@code xxxH} and {@code xxxV} hang off the output group. Merging the two here
     * is what lets the client render one field with one description.
     *
     * <p>{@code resetAllOutputFields} is {@code true} unconditionally: {@code 3400-SEND-SCREEN} issues
     * {@code SEND MAP ... ERASE} on every path.
     *
     * @param response the painted output area
     * @param request  the input area carrying the protection bytes and the cursor
     * @param cursorField the {@code DFHMDF} label the {@code MOVE -1} selected, or {@code null}
     * @return the metadata envelope
     */
    ScreenMetadata screenMetadataOf(AccountUpdateResponse response, AccountUpdateRequest request,
                                    String cursorField) {
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (AccountUpdateResponse.ScreenField field : AccountUpdateResponse.ScreenField.values()) {
            AccountUpdateResponse.FieldAttributes attributes = response.attributes(field);
            byte protection = request.metadata(AccountUpdateRequest.ScreenField.valueOf(field.name()))
                    .attribute();
            quads.put(field.label(), ScreenMetadata.FieldMetadata.of(attributes.getColour(),
                    protection, attributes.getHilight(), attributes.getValidn()));
        }
        return ScreenMetadata.of(cursorField,
                response.attributes(AccountUpdateResponse.ScreenField.ERRMSG).getColour(),
                true,
                quads);
    }
}
