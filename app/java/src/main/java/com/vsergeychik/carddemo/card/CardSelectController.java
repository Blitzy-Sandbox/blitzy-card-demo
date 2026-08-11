package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest;
import com.vsergeychik.carddemo.card.dto.CardSelectRequest.ThisProgCommarea;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AidRequestParameter;
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
import com.vsergeychik.carddemo.common.ScreenResponse;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;

import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/cards/{cardNum}} - the credit-card <strong>detail</strong> screen, CSD transaction
 * {@code CCDL}, migrated from {@code app/cbl/COCRDSLC.cbl} (887 lines) mapset {@code COCRDSL} /
 * map {@code CCRDSLA}.
 *
 * <p>This is a like-for-like language migration. The layering and the control-flow <em>shape</em> are
 * modernised - {@code GO TO} becomes {@code return}, {@code EVALUATE} becomes an ordered {@code if}
 * chain - but evaluation order, field widths, message text and error paths are held byte-exact. Every
 * paragraph of the original survives as a method named after it, so a reader can put the two side by
 * side.
 *
 * <h2>Rules governing this file</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line: "No user rules provided."</strong> That
 * single line is the whole document. Per UR4 their absence is <em>not</em> licence to lower the bar, so
 * the migration plan's enterprise best practices <strong>B1-B12</strong> bind in their place and are
 * cited by name at each decision below. The ones that shaped this class are B4 (document conflicts,
 * never silently repair one), B5 (dead and odd code preserved as-is), B6 (security posture neither
 * weakened nor unrequestedly strengthened), B8 (explicit over implicit), B9 (no static mutable state),
 * B10 (tests are a first-class deliverable) and B12 (environmental limits documented, not absorbed).
 *
 * <h2>Conflict 1 - the class name says Select, the program is a View (R1, plan section 0.8.4)</h2>
 *
 * <p>The name {@code CardSelectController} is mandated by the build prompt and is kept
 * <strong>verbatim</strong>. It does not describe what the program does. Three independent readings
 * agree that this screen is a detail view, not a selector:
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDSLC.cbl:4} - the program header reads
 *       {@code Function: Accept and process credit card detail request};</li>
 *   <li>{@code README.md:213-231} documents transaction {@code CCDL} as "Credit Card View";</li>
 *   <li>the code itself reads exactly one record and projects it onto fifteen fields. It selects
 *       nothing and offers no list.</li>
 * </ul>
 *
 * <p>Rule <strong>R1</strong> resolves this: <em>the name comes from the prompt, the behaviour comes
 * from the source</em>. So the class is not renamed and the behaviour is not adjusted to fit the name.
 * This is one of the sixteen entries in the plan's class-name divergence register.
 *
 * <h2>Conflict 2 - {@code LIT-CCLISTMAP} names the wrong map, and stays wrong (B4, B5)</h2>
 *
 * <p>{@code app/cbl/COCRDSLC.cbl:177-178} declares
 * {@code 05 LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'}. The card list's map is really {@code 'CCRDLIA'}
 * ({@code app/cbl/COCRDLIC.cbl:185}), so the literal names <em>this</em> screen's map where it means to
 * name the list's. {@code app/cbl/COCRDUPC.cbl:234} carries the identical defect.
 *
 * <p>{@link #LIT_CCLISTMAP} reproduces {@code 'CCRDSLA'} unchanged. Correcting it to {@code 'CCRDLIA'}
 * would change an observable value and break parity, so it is recorded here rather than repaired -
 * practice <strong>B5</strong> and plan section 0.8.3.
 *
 * <p>A verified detail the plan does not mention makes the defect harmless in this program:
 * <strong>{@code LIT-CCLISTMAP} is never referenced in the {@code PROCEDURE DIVISION} at all.</strong>
 * Only {@code LIT-CCLISTMAPSET} is, at {@code :505} and {@code :527}. The literal is dead, and it is
 * kept because deleting dead declarations is itself a change (B5). {@link #LIT_CCLISTTRANID},
 * {@link #LIT_MENUMAPSET} and {@link #LIT_MENUMAP} are dead for the same reason and kept for the same
 * reason.
 *
 * <h2>Conflict 3 - two copybooks the plan lists are not copied (B4)</h2>
 *
 * <p>Plan section 0.2.3 lists {@code CVACT01Y} and {@code CVACT03Y} among this program's copybooks.
 * Read directly, both {@code COPY} statements are <strong>commented out</strong>:
 * {@code app/cbl/COCRDSLC.cbl:231} is {@code *COPY CVACT01Y.} and {@code :237} is
 * {@code *COPY CVACT03Y.} This class therefore imports neither {@code account.model.AccountRecord} nor
 * {@code card.model.CardXrefRecord}; doing so would create coupling the source does not have. The
 * program copies thirteen copybooks, and those two are not among them.
 *
 * <p>The two copybooks that <em>are</em> live but never referenced - {@code CSUSR01Y} at {@code :227}
 * and {@code CVCUS01Y} at {@code :240} - are a different case and are treated differently. A live
 * {@code COPY} allocates storage, so both are modelled as declared-only areas on
 * {@link Conversation#secUserData} and {@link Conversation#customerRecord}. Deleting an allocated area
 * because nothing reads it would be as much a change as deleting the {@code WS-LONG-MSG} buffer or the
 * nine unused {@code CICS-OUTPUT-EDIT-VARS} items, neither of which is deleted either (practice
 * <strong>B5</strong>). Note in particular that no field of {@code SEC-USER-DATA} is read here and
 * {@code SEC-USR-PWD} is never compared: this screen performs no authentication, and none is added
 * (practice <strong>B6</strong>).
 *
 * <h2>Four behaviours that are easy to lose in translation</h2>
 *
 * <ol>
 *   <li><strong>An invalid AID is silently coerced to {@code ENTER}, never rejected.</strong>
 *       {@code app/cbl/COCRDSLC.cbl:291-299} sets {@code PFK-INVALID}, promotes it to
 *       {@code PFK-VALID} only for {@code ENTER} or {@code PFK03}, and then - if it is still invalid -
 *       does {@code SET CCARD-AID-ENTER TO TRUE}. So pressing {@code PF12}, {@code CLEAR} or
 *       {@code PA1} simply redisplays the screen. Answering {@code 400} here, or emitting
 *       {@link SystemMessages#CCDA_MSG_INVALID_KEY} as sibling screens do, would be new behaviour.
 *       {@code COCRDSLC} never uses that message. See {@link #coerceInvalidAid}.</li>
 *   <li><strong>{@code WHEN CDEMO-PGM-ENTER} appears twice, and only order distinguishes them.</strong>
 *       {@code :339-340} qualifies it with {@code AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM}; the bare
 *       {@code :349} follows. First-match-wins is what makes the bare arm mean "arrived from somewhere
 *       other than the card list". Inverting the two would silently break the card-list entry path, so
 *       {@link #main0000} tests them in source order (gate G30).</li>
 *   <li><strong>The {@code WS-RETURN-MSG-OFF} guard is present in {@code 9100} and absent in
 *       {@code 9150}.</strong> {@code :759-761} sets {@code DID-NOT-FIND-ACCTCARD-COMBO} only
 *       {@code IF WS-RETURN-MSG-OFF}, while {@code :796-799} sets
 *       {@code DID-NOT-FIND-ACCT-IN-CARDXREF} unconditionally. The asymmetry is real, it decides which
 *       message the operator sees, and it is trivially "tidied away". It is not tidied here.</li>
 *   <li><strong>{@code WHEN OTHER} writes to {@code WS-RETURN-MSG}, not {@code ABEND-MSG}.</strong>
 *       {@code :373-380} moves {@code 'UNEXPECTED DATA SCENARIO'} into the return message while filling
 *       {@code ABEND-CULPRIT}, {@code ABEND-CODE} and {@code ABEND-REASON}. {@code COCRDUPC}'s
 *       equivalent arm moves it into {@code ABEND-MSG}. The two programs genuinely differ, and this one
 *       is reproduced.</li>
 * </ol>
 *
 * <h2>A fifth: {@code WS-RETURN-MSG-OFF} means SPACES here, not LOW-VALUES (B4)</h2>
 *
 * <p>This is the single most invisible trap in the program, and it is worth being explicit about because
 * two similarly named fields exist:
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDSLC.cbl:134-135} declares {@code 05 WS-RETURN-MSG PIC X(75).} with
 *       {@code 88 WS-RETURN-MSG-OFF VALUE SPACES.} - a <strong>program-local</strong>
 *       {@code WORKING-STORAGE} field whose "off" state is <strong>75 spaces</strong>. This is the field
 *       {@code :264} sets and the field all six {@code IF WS-RETURN-MSG-OFF} guards test.</li>
 *   <li>{@code app/cpy/CVCRD01Y.cpy:29-30} declares {@code 10 CCARD-RETURN-MSG PIC X(75).} with
 *       {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} - a different field, whose off state is 75
 *       bytes of binary zero, and which {@code COCRDSLC} <strong>never references</strong>.</li>
 * </ul>
 *
 * <p>So {@link #returnMessageOff} compares against spaces and {@link CardScreenState#isCcardReturnMsgOff()}
 * is deliberately <em>not</em> used for this guard. Binary {@code x'00'}, a space and a Java
 * {@code null} are three different things and are never conflated: {@link CardScreenState#lowValues(int)}
 * and {@link CardScreenState#spaces(int)} name the first two, and nothing here is ever {@code null}.
 *
 * <h2>Statelessness (rule R6, gate G37)</h2>
 *
 * <p>CICS is pseudo-conversational, so every scrap of conversation state travels in the payload:
 * {@link NavigationContext} carries {@code CARDDEMO-COMMAREA}, {@link CardScreenState} carries
 * {@code CC-WORK-AREA} including {@code CCARD-AID}, and {@code CDEMO-PGM-CONTEXT} carries the
 * {@code ENTER}/{@code REENTER} distinction. There is no {@code HttpSession}, no
 * {@code @SessionAttributes}, no server-side cache, no {@code ThreadLocal} and no static mutable field
 * anywhere in this class - the only {@code static} members are {@code final} constants and a
 * {@code final} logger (gate G53, practice B9). {@code WS-MISC-STORAGE}, {@code CC-WORK-AREA} and
 * {@code WS-COMMAREA} are per-request values held on {@link Conversation}, which is constructed fresh
 * on every call and cannot outlive it.
 *
 * <h2>Testability without HTTP (gate G51, practice B10)</h2>
 *
 * <p>The plan mandates <strong>no service class</strong> for the detail screen, so none is invented.
 * Instead every decision lives in a package-visible method on this class, and the class is directly
 * instantiable: {@code new CardSelectController(mockRepository, fixedClock, StandardCharsets.US_ASCII)}
 * is enough to drive {@link #main0000}, {@link #coerceInvalidAid}, {@link #editAccount2210},
 * {@link #getCardByAcctCard9100} and the rest with no {@code MockMvc}, no Spring context and no
 * {@code JobLauncher} in the path. The {@link Clock} is injected for exactly this reason - nothing here
 * reads a clock of its own, so a parity case can fix the instant and get a byte-identical screen.
 *
 * <h2>Where the bytes come from</h2>
 *
 * <p>Every cross-width assignment goes through {@link FixedWidthCodec#movePicX(String, int)} or
 * {@link FixedWidthCodec#movePic9(long, int)} rather than plain Java assignment. COBOL truncates
 * {@code PIC X} on the right and {@code PIC 9} on the left, a plain assignment does neither, and
 * {@code MOVE} is the dominant parity risk in this estate. Two consequences are visible in the source
 * and reproduced here: {@code MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET} ({@code :565}) moves an
 * {@code X(8)} literal into an {@code X(7)} receiver and so drops a byte, and
 * {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} ({@code :771}) moves 80 characters into 75.
 *
 * <p>No dataset name appears anywhere in this file (gate G46); all data access is through
 * {@link CardRepository}. No {@code double} or {@code float} appears either (gate G22) -
 * {@code CVACT02Y} declares no signed decimal, so no {@link java.math.BigDecimal} arises; the account id
 * is {@code PIC 9(11)} and travels as a {@code long}. There is no DDL, no entity annotation and no
 * version column (gate G44).
 *
 * <h2>Parity provenance (practice B12, risk R-A)</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong> - the plan documents eight
 * independently verified blockers, among them a disabled indexed-file handler, absent Language
 * Environment {@code CEE*} services and no CICS emulator. The expectations this class is verified
 * against are therefore <em>statically derived</em> from the COBOL paragraphs, the copybook byte
 * layouts, the BMS field definitions and the real {@code app/data/ASCII} fixtures. No captured
 * execution baseline exists and none is claimed.
 *
 * @see CardRepository the {@code CARDDAT} base cluster and its {@code CARDAIX} path
 * @see CardSelectRequest the fifteen {@code xxxI} items of mapset {@code COCRDSL}
 * @see CardSelectResponse the fifteen {@code xxxO} items and their attribute quads
 */
@RestController
public class CardSelectController {

    /**
     * Diagnostics for the paths the COBOL could only {@code SEND TEXT} to a terminal - the
     * {@code WHEN OTHER} arm of the main dispatcher and the {@code ABEND-ROUTINE}. There is no terminal
     * here, so the text is logged rather than discarded.
     *
     * <p>{@code static final} and immutable, so it is not shared mutable state (practice B9).
     */
    private static final Log LOG = LogFactory.getLog(CardSelectController.class);

    // =================================================================================================
    // WS-LITERALS - app/cbl/COCRDSLC.cbl:162-190, transcribed at their DECLARED widths.
    //
    // The widths are part of the contract, not decoration: LIT-THISMAPSET is X(8) with a trailing space
    // even though its receiver CCARD-NEXT-MAPSET is X(7), and that mismatch is what makes the MOVE at
    // :565 drop a byte. Normalising either width would erase a real behaviour (practice B5).
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDSLC'} - {@code app/cbl/COCRDSLC.cbl:163-164}. */
    static final String LIT_THISPGM = "COCRDSLC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCDL'} - {@code :165-166}; CSD transaction {@code CCDL}. */
    static final String LIT_THISTRANID = "CCDL";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDSL '} - {@code :167-168}.
     *
     * <p><strong>Eight characters, the eighth a space.</strong> {@code COCRDLIC} declares its own
     * equivalent as {@code X(7)}; this program does not, and the difference is preserved (B5). Because
     * {@code CCARD-NEXT-MAPSET} and {@code CDEMO-LAST-MAPSET} are both {@code X(7)}, every {@code MOVE}
     * of this literal loses the trailing space - see {@link #sendScreen1400}.
     */
    static final String LIT_THISMAPSET = "COCRDSL ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :169-170}. */
    static final String LIT_THISMAP = "CCRDSLA";

    /** {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} - {@code :171-172}; the card-list program. */
    static final String LIT_CCLISTPGM = "COCRDLIC";

    /**
     * {@code LIT-CCLISTTRANID PIC X(4) VALUE 'CCLI'} - {@code :173-174}.
     *
     * <p>Declared and <strong>never referenced</strong> in the {@code PROCEDURE DIVISION}. Kept because
     * removing a dead declaration is a change (practice B5).
     */
    static final String LIT_CCLISTTRANID = "CCLI";

    /** {@code LIT-CCLISTMAPSET PIC X(7) VALUE 'COCRDLI'} - {@code :175-176}; tested at {@code :505}, {@code :527}. */
    static final String LIT_CCLISTMAPSET = "COCRDLI";

    /**
     * {@code LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :177-178}.
     *
     * <p><strong>A source defect, preserved deliberately.</strong> The card list's map is
     * {@code 'CCRDLIA'} ({@code app/cbl/COCRDLIC.cbl:185}), so this literal names this screen's own map
     * instead. {@code app/cbl/COCRDUPC.cbl:234} repeats the mistake. Correcting it to {@code 'CCRDLIA'}
     * would change an observable value and break parity, so it is recorded and left (B4, B5).
     *
     * <p>It is also <strong>never referenced</strong> in the {@code PROCEDURE DIVISION}, which is why
     * the defect has no visible effect in this program - and why it must not be "fixed" on the
     * assumption that it does.
     */
    static final String LIT_CCLISTMAP = "CCRDSLA";

    /** {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - {@code :179-180}; the main menu. */
    static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - {@code :181-182}. */
    static final String LIT_MENUTRANID = "CM00";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} - {@code :183-184}; declared, never referenced (B5). */
    static final String LIT_MENUMAPSET = "COMEN01";

    /** {@code LIT-MENUMAP PIC X(7) VALUE 'COMEN1A'} - {@code :185-186}; declared, never referenced (B5). */
    static final String LIT_MENUMAP = "COMEN1A";

    /**
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} - {@code :187-188}.
     *
     * <p>A CICS <em>file name</em>, which is a logical key, not a dataset name. It reaches no statement:
     * it is used only to fill {@code ERROR-FILE} in the failure arms. The dataset it resolves to is
     * configuration that {@link CardRepository} owns (gate G46). Its value is asserted against
     * {@link CardRepository#BASE_CICS_FILE_NAME} so the two cannot drift apart.
     */
    static final String LIT_CARDFILENAME = CardRepository.BASE_CICS_FILE_NAME;

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} - {@code :189-190}.
     *
     * <p>The {@code CARDAIX} alternate-index <em>path</em> over the same base cluster - one more access
     * route, never a second dataset. Used only to fill {@code ERROR-FILE} in
     * {@link #getCardByAcct9150}.
     */
    static final String LIT_CARDFILENAME_ACCT_PATH = CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME;

    // =================================================================================================
    // Declared widths of the WORKING-STORAGE items this class reproduces.
    // =================================================================================================

    /** {@code WS-TRANID PIC X(4)} - {@code app/cbl/COCRDSLC.cbl:45-46}. */
    static final int WS_TRANID_LENGTH = 4;

    /** {@code WS-LONG-MSG PIC X(500)} - {@code :125}; the {@code SEND-LONG-TEXT} buffer. */
    static final int WS_LONG_MSG_LENGTH = 500;

    /** {@code WS-INFO-MSG PIC X(40)} - {@code :126}. Matches {@code INFOMSGO PIC X(40)} exactly. */
    static final int WS_INFO_MSG_LENGTH = 40;

    /**
     * {@code WS-RETURN-MSG PIC X(75)} - {@code :134}.
     *
     * <p>Seventy-five, not eighty. {@code ERRMSGO} is {@code X(80)}, so
     * {@code MOVE WS-RETURN-MSG TO ERRMSGO} pads five spaces on the right, and
     * {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} truncates five from an 80-character
     * composition. Both directions are reproduced.
     */
    static final int WS_RETURN_MSG_LENGTH = CardScreenState.CCARD_RETURN_MSG_LENGTH;

    /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :98}; the {@code CARDDAT} primary key. */
    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    /** {@code WS-CARD-RID-ACCT-ID PIC 9(11)} - {@code :99}; the {@code CARDAIX} alternate key. */
    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    /**
     * {@code WS-COMMAREA PIC X(2000)} - {@code app/cbl/COCRDSLC.cbl:205}.
     *
     * <p>{@code COMMON-RETURN} returns this whole 2000-byte area, of which only the leading
     * {@value #PASSED_COMMAREA_LENGTH} bytes are ever written.
     */
    static final int WS_COMMAREA_LENGTH = 2000;

    /**
     * {@code WS-THIS-PROGCOMMAREA} - {@code app/cbl/COCRDSLC.cbl:200-203}:
     * {@code CA-FROM-PROGRAM PIC X(08)} followed by {@code CA-FROM-TRANID PIC X(04)}, so twelve bytes.
     */
    static final int THIS_PROGCOMMAREA_LENGTH =
            ThisProgCommarea.CA_FROM_PROGRAM_LENGTH + ThisProgCommarea.CA_FROM_TRANID_LENGTH;

    /**
     * The length of the commarea a caller actually passes: {@code CARDDEMO-COMMAREA} followed by
     * {@code WS-THIS-PROGCOMMAREA}, which is {@value NavigationContext#COMMAREA_LENGTH} +
     * {@value #THIS_PROGCOMMAREA_LENGTH} = 172 bytes.
     *
     * <p>This is the {@code EIBCALEN} a re-entry carries, and the value
     * {@link #restoreCommarea(Conversation, int)} splits at the two 1-based reference-modification
     * offsets of {@code :274-278}.
     */
    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    /** {@code EIBCALEN} when no commarea was passed at all - the first conjunct of {@code :268}. */
    static final int NO_COMMAREA_LENGTH = 0;

    // =================================================================================================
    // WS-INPUT-FLAG, WS-EDIT-ACCT-FLAG, WS-EDIT-CARD-FLAG, WS-RETURN-FLAG, WS-PFK-FLAG.
    //
    // Every one is PIC X(1), so every 88-level is a one-character comparison and the flag is stored as
    // the character the COBOL stores. Storing an enum instead would lose the state INITIALIZE leaves
    // behind: a space, which satisfies none of WS-INPUT-FLAG's three condition names and satisfies
    // FLG-ACCTFILTER-BLANK on the two edit flags. That asymmetry is real and is relied on.
    // =================================================================================================

    /** {@code 88 INPUT-OK VALUE '0'} - {@code app/cbl/COCRDSLC.cbl:52}. */
    static final String INPUT_OK = "0";

    /** {@code 88 INPUT-ERROR VALUE '1'} - {@code :53}. */
    static final String INPUT_ERROR = "1";

    /**
     * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - {@code :54}; one byte of binary zero.
     *
     * <p>Declared and never tested by this program, and deliberately <em>not</em> the state
     * {@code INITIALIZE} produces - that is a space. Both states exist and they are different.
     */
    static final String INPUT_PENDING = "\u0000";

    /** {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} - {@code :56}. Also {@code FLG-CARDFILTER-NOT-OK}, {@code :60}. */
    static final String FLG_FILTER_NOT_OK = "0";

    /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} - {@code :57}. Also {@code FLG-CARDFILTER-ISVALID}, {@code :61}. */
    static final String FLG_FILTER_ISVALID = "1";

    /**
     * {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - {@code :58}. Also {@code FLG-CARDFILTER-BLANK},
     * {@code :62}.
     *
     * <p>A <strong>space</strong>, which is exactly what {@code INITIALIZE WS-MISC-STORAGE} leaves in
     * both edit flags. So on entry both filters read as blank before any edit has run, and
     * {@link #setupScreenAttrs1300}'s cursor {@code EVALUATE} takes its first arm.
     */
    static final String FLG_FILTER_BLANK = " ";

    /** {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES} - {@code :64}; declared, never tested (B5). */
    static final String WS_RETURN_FLAG_OFF = "\u0000";

    /** {@code 88 WS-RETURN-FLAG-ON VALUE '1'} - {@code :65}; declared, never tested (B5). */
    static final String WS_RETURN_FLAG_ON = "1";

    /** {@code 88 PFK-VALID VALUE '0'} - {@code :67}. */
    static final String PFK_VALID = "0";

    /** {@code 88 PFK-INVALID VALUE '1'} - {@code :68}. */
    static final String PFK_INVALID = "1";

    // =================================================================================================
    // The PIC X move applied to a compile-time constant.
    //
    // A COBOL 88-level on an alphanumeric field is a comparison against the LITERAL SPACE-PADDED TO THE
    // FIELD'S WIDTH, and SET ... TO TRUE stores exactly that padded image. So each message constant below
    // is built by moving its literal into the receiver's declared width, and the test for the condition
    // name is then plain equality. Writing the padded literals out by hand would put the widths in two
    // places; deriving them puts the width in one place - the field's own length constant.
    //
    // The codec is stateless and immutable, and movePicX is a pure String operation that never consults
    // the charset, so US-ASCII here selects nothing (practice B9: static final and immutable is not
    // shared mutable state).
    // =================================================================================================

    /** The {@code PIC X} move rule, applied to the constants below. Stateless, immutable, charset-neutral. */
    private static final FixedWidthCodec PIC_X_CODEC =
            new FixedWidthCodec(java.nio.charset.StandardCharsets.US_ASCII);

    // =================================================================================================
    // WS-INFO-MSG condition names - app/cbl/COCRDSLC.cbl:126-132. Receiver width 40.
    // =================================================================================================

    /**
     * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code app/cbl/COCRDSLC.cbl:127-128}.
     *
     * <p>A {@code VALUES} list, so <strong>two</strong> images satisfy it: forty spaces and forty bytes
     * of binary zero. {@link #noInfoMessage} tests both, because {@code INITIALIZE} produces the first
     * and a {@code MOVE LOW-VALUES} would produce the second, and collapsing them to one would make the
     * condition false in a state the COBOL calls true.
     */
    static final String WS_INFO_MSG_SPACES = CardScreenState.spaces(WS_INFO_MSG_LENGTH);

    /** The second image of {@code WS-NO-INFO-MESSAGE}: forty bytes of binary zero, {@code LOW-VALUES}. */
    static final String WS_INFO_MSG_LOW_VALUES = CardScreenState.lowValues(WS_INFO_MSG_LENGTH);

    /**
     * {@code 88 FOUND-CARDS-FOR-ACCOUNT VALUE '   Displaying requested details'} - {@code :129-130}.
     *
     * <p>Thirty-one characters, the first three of them spaces, padded to forty. This condition name is
     * both a message and a <em>flag</em>: {@code :474} tests {@code IF FOUND-CARDS-FOR-ACCOUNT} to
     * decide whether a card record was read, which is why {@link #setupScreenVars1200} keys the whole
     * card projection on it rather than on the record being present.
     */
    static final String FOUND_CARDS_FOR_ACCOUNT =
            PIC_X_CODEC.movePicX("   Displaying requested details", WS_INFO_MSG_LENGTH);

    /**
     * {@code 88 WS-PROMPT-FOR-INPUT VALUE 'Please enter Account and Card Number'} - {@code :131-132}.
     *
     * <p>Thirty-six characters padded to forty.
     */
    static final String WS_PROMPT_FOR_INPUT =
            PIC_X_CODEC.movePicX("Please enter Account and Card Number", WS_INFO_MSG_LENGTH);

    // =================================================================================================
    // WS-RETURN-MSG condition names - app/cbl/COCRDSLC.cbl:134-158. Receiver width 75.
    //
    // Twelve names are declared; six are set by this program and six are dead. The dead ones are kept
    // (practice B5) and are marked as such, because a reader comparing the two files needs to see that
    // nothing was dropped.
    // =================================================================================================

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - {@code app/cbl/COCRDSLC.cbl:135}.
     *
     * <p><strong>SPACES, not {@code LOW-VALUES}.</strong> See the class documentation: this is the
     * program-local {@code WS-RETURN-MSG}, not {@code CVCRD01Y}'s {@code CCARD-RETURN-MSG}, whose
     * {@code 88} is {@code LOW-VALUES} and which this program never touches. Every
     * {@code IF WS-RETURN-MSG-OFF} guard - {@code :656}, {@code :668}, {@code :696}, {@code :709},
     * {@code :759}, {@code :764} - compares against these seventy-five spaces.
     */
    static final String WS_RETURN_MSG_OFF = CardScreenState.spaces(WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting              '} - {@code :136-137}.
     *
     * <p>Thirty-four characters: twenty of text and fourteen trailing spaces that are part of the
     * literal. Declared and <strong>never set</strong> by this program - the {@code PFK03} arm transfers
     * control without leaving a message. Kept per practice B5.
     */
    static final String WS_EXIT_MESSAGE =
            PIC_X_CODEC.movePicX("PF03 pressed.Exiting              ", WS_RETURN_MSG_LENGTH);

    /** {@code 88 WS-PROMPT-FOR-ACCT VALUE 'Account number not provided'} - {@code :138-139}; set at {@code :657}. */
    static final String WS_PROMPT_FOR_ACCT =
            PIC_X_CODEC.movePicX("Account number not provided", WS_RETURN_MSG_LENGTH);

    /** {@code 88 WS-PROMPT-FOR-CARD VALUE 'Card number not provided'} - {@code :140-141}; set at {@code :697}. */
    static final String WS_PROMPT_FOR_CARD =
            PIC_X_CODEC.movePicX("Card number not provided", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED VALUE 'No input received'} - {@code :142-143}; set at
     * {@code :639}.
     *
     * <p>Set by the cross-field edit, which runs <em>after</em> both individual edits, so it
     * <strong>overwrites</strong> whichever of {@code WS-PROMPT-FOR-ACCT} or {@code WS-PROMPT-FOR-CARD}
     * they left behind. That overwrite is unguarded - {@code :637-640} has no
     * {@code IF WS-RETURN-MSG-OFF} - and it is reproduced exactly.
     */
    static final String NO_SEARCH_CRITERIA_RECEIVED =
            PIC_X_CODEC.movePicX("No input received", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 SEARCHED-ACCT-ZEROES VALUE 'Account number must be a non zero 11 digit number'} -
     * {@code :144-145}. Declared and never set (B5).
     *
     * <p>{@code 88 SEARCHED-ACCT-NOT-NUMERIC} at {@code :146-147} declares the <strong>same
     * literal</strong>, so the two condition names are indistinguishable once stored. Both are dead
     * here, and one constant faithfully represents both because their images are byte-identical.
     */
    static final String SEARCHED_ACCT_MESSAGE = PIC_X_CODEC.movePicX(
            "Account number must be a non zero 11 digit number", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 SEARCHED-CARD-NOT-NUMERIC VALUE 'Card number if supplied must be a 16 digit number'} -
     * {@code :148-149}. Declared and never set (B5).
     */
    static final String SEARCHED_CARD_NOT_NUMERIC = PIC_X_CODEC.movePicX(
            "Card number if supplied must be a 16 digit number", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF VALUE 'Did not find this account in cards database'} -
     * {@code :151-152}.
     *
     * <p>Set by {@link #getCardByAcct9150} at {@code :799} - and set there
     * <strong>unconditionally</strong>, with no {@code IF WS-RETURN-MSG-OFF} guard, unlike its sibling
     * in {@link #getCardByAcctCard9100}. That asymmetry is the third of the four traps listed in the
     * class documentation.
     */
    static final String DID_NOT_FIND_ACCT_IN_CARDXREF = PIC_X_CODEC.movePicX(
            "Did not find this account in cards database", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DID-NOT-FIND-ACCTCARD-COMBO VALUE 'Did not find cards for this search condition'} -
     * {@code :153-154}; set at {@code :760}, but only {@code IF WS-RETURN-MSG-OFF}.
     */
    static final String DID_NOT_FIND_ACCTCARD_COMBO = PIC_X_CODEC.movePicX(
            "Did not find cards for this search condition", WS_RETURN_MSG_LENGTH);

    /** {@code 88 XREF-READ-ERROR VALUE 'Error reading Card Data File'} - {@code :155-156}; never set (B5). */
    static final String XREF_READ_ERROR =
            PIC_X_CODEC.movePicX("Error reading Card Data File", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 CODING-TO-BE-DONE VALUE 'Looks Good.... so far'} - {@code :157-158}; never set.
     *
     * <p>A development leftover, kept because practice B5 forbids tidying dead declarations away.
     */
    static final String CODING_TO_BE_DONE =
            PIC_X_CODEC.movePicX("Looks Good.... so far", WS_RETURN_MSG_LENGTH);

    /**
     * {@code MOVE 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG} - {@code app/cbl/COCRDSLC.cbl:377-378}.
     *
     * <p>The {@code WHEN OTHER} arm's text. Note the receiver: {@code WS-RETURN-MSG}, not
     * {@code ABEND-MSG}. {@code COCRDUPC}'s equivalent arm chooses {@code ABEND-MSG}; this program does
     * not, and the difference is preserved.
     */
    static final String UNEXPECTED_DATA_SCENARIO =
            PIC_X_CODEC.movePicX("UNEXPECTED DATA SCENARIO", WS_RETURN_MSG_LENGTH);

    /** {@code MOVE '0001' TO ABEND-CODE} - {@code :375}; the {@code WHEN OTHER} arm's abend code. */
    static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    /**
     * {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} - {@code :860}.
     *
     * <p>The {@code ABEND-ROUTINE} default, applied only {@code IF ABEND-MSG EQUAL LOW-VALUES}. Padded
     * to {@code ABEND-MSG}'s declared {@code PIC X(72)}.
     */
    static final String UNEXPECTED_ABEND_OCCURRED = PIC_X_CODEC.movePicX(
            "UNEXPECTED ABEND OCCURRED.", SystemMessages.ABEND_MSG_LENGTH);

    /** {@code EXEC CICS ABEND ABCODE('9999')} - {@code :875-877}; the abend code the routine ends on. */
    static final String ABEND_ROUTINE_ABCODE = "9999";

    // =================================================================================================
    // WS-FILE-ERROR-MESSAGE - app/cbl/COCRDSLC.cbl:102-121.
    //
    // Eight fixed fillers interleaved with four variable items, summing to exactly 80 characters. The
    // structure is spelled out because getting it wrong is silent: the message is still 80 characters if
    // a filler is one space out, it is simply the wrong 80 characters. FILE_ERROR_MESSAGE_LENGTH is
    // COMPUTED from the parts rather than written down, and requireExactly80 refuses to load the class
    // if the parts do not sum to 80.
    // =================================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} - {@code app/cbl/COCRDSLC.cbl:103-104}. */
    static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :105-106}. Holds {@code 'READ'} in both failure arms. */
    static final int ERROR_OPNAME_LENGTH = 8;

    /** {@code FILLER PIC X(4) VALUE ' on '} - {@code :107-108}. */
    static final String FILE_ERROR_ON = " on ";

    /**
     * {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :109-110}.
     *
     * <p>Nine characters receiving an eight-character CICS file name, so the {@code MOVE} pads one
     * trailing space: {@code 'CARDDAT '} arrives as {@code 'CARDDAT  '}.
     */
    static final int ERROR_FILE_LENGTH = 9;

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} - {@code :111-113}. */
    static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /**
     * {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :114-115}. Also {@code ERROR-RESP2},
     * {@code :118-119}.
     *
     * <p>{@code MOVE WS-RESP-CD TO ERROR-RESP} moves a {@code PIC S9(09) COMP} sender into an
     * alphanumeric receiver. COBOL renders the sender as its nine unsigned digits and then applies the
     * {@code PIC X} rule, so a response of 13 arrives as {@code "000000013"} left-justified in ten
     * characters - {@code "000000013 "}, with one trailing space. See
     * {@link #responseCodeImage(int)}.
     */
    static final int ERROR_RESP_LENGTH = 10;

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} - {@code :116-117}. */
    static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5) VALUE SPACES} - {@code :120-121}; the trailing filler that completes 80. */
    static final String FILE_ERROR_TRAILER = "     ";

    /**
     * The digit count of {@code WS-RESP-CD} and {@code WS-REAS-CD}, both {@code PIC S9(09) COMP} -
     * {@code app/cbl/COCRDSLC.cbl:41-44}.
     *
     * <p>Nine, and the alphanumeric receiver is ten wide, which is exactly why the rendered image
     * carries a trailing space.
     */
    static final int RESP_CODE_DIGITS = 9;

    /**
     * The full width of {@code WS-FILE-ERROR-MESSAGE}: <strong>exactly 80</strong> characters.
     *
     * <p>Computed from the parts - 12 + 8 + 4 + 9 + 15 + 10 + 7 + 10 + 5 - so a mistranscribed filler
     * cannot pass unnoticed. {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} then narrows it to
     * {@value #WS_RETURN_MSG_LENGTH}, discarding the five-space trailer.
     */
    static final int FILE_ERROR_MESSAGE_LENGTH =
            FILE_ERROR_PREFIX.length() + ERROR_OPNAME_LENGTH + FILE_ERROR_ON.length()
                    + ERROR_FILE_LENGTH + FILE_ERROR_RETURNED_RESP.length() + ERROR_RESP_LENGTH
                    + FILE_ERROR_RESP2.length() + ERROR_RESP_LENGTH + FILE_ERROR_TRAILER.length();

    /** {@code MOVE 'READ' TO ERROR-OPNAME} - {@code :767} and {@code :803}; the only operation name used. */
    static final String READ_OPERATION_NAME = CardRepository.READ_OPERATION_NAME;

    static {
        if (FILE_ERROR_MESSAGE_LENGTH != 80) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE must be exactly 80 characters, as "
                    + "app/cbl/COCRDSLC.cbl:102-121 declares it, but the transcribed parts sum to "
                    + FILE_ERROR_MESSAGE_LENGTH + "; a filler has been mistranscribed");
        }
        if (PASSED_COMMAREA_LENGTH != 172) {
            throw new AssertionError("The passed commarea must be exactly 172 characters - "
                    + "CARDDEMO-COMMAREA (160) followed by WS-THIS-PROGCOMMAREA (12) - but the parts "
                    + "sum to " + PASSED_COMMAREA_LENGTH);
        }
        // The three literals this class takes from CardRepository rather than restating. Borrowing them
        // keeps one definition, and checking them here means a change on either side is reported at class
        // load rather than silently altering a key or a message.
        requireLiteral(LIT_CARDFILENAME, "CARDDAT ", "LIT-CARDFILENAME", 187);
        requireLiteral(LIT_CARDFILENAME_ACCT_PATH, "CARDAIX ", "LIT-CARDFILENAME-ACCT-PATH", 189);
        requireLiteral(READ_OPERATION_NAME, "READ", "ERROR-OPNAME's only value", 767);
        // COPY COTTL01Y at :213 -> ScreenTitles, one of the six universal online imports every
        // controller carries (AAP 0.5.7). The two headings reach the map through
        // CardSelectResponse.applyScreenTitles(), which owns the PIC X(40) move, so this class does not
        // restate them - but it does pin them, here, in the file that copies the copybook. That is what
        // makes the COPY-to-import correspondence auditable at the point of use (practice B8) and turns a
        // drift in either heading into a class-load failure naming the copybook line, rather than a
        // silently shifted screen. Both are asserted at their full declared width, spaces included.
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        // Note :21 of the copybook holds an abandoned earlier wording, commented out. :22 is the live
        // value and the only one transcribed (practice B5).
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20+22");
    }

    /**
     * Verifies a literal this class borrows from another type against the value
     * {@code app/cbl/COCRDSLC.cbl} declares, so a change on either side cannot pass unnoticed.
     *
     * @param actual     the borrowed value
     * @param expected   the value the COBOL declares
     * @param cobolName  the COBOL item's name, for the failure message
     * @param cobolLine  the line of {@code app/cbl/COCRDSLC.cbl} that declares it
     * @throws AssertionError if the two disagree
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COCRDSLC.cbl:" + cobolLine);
    }

    /**
     * The same check for an item this program does not declare itself but pulls in through a
     * {@code COPY}, so the failure message can name the copybook rather than the {@code COPY} line.
     *
     * <p>Delegated to by the line-numbered form above rather than duplicated, so there is exactly one
     * comparison in this class and a passing build is not paying for a second untaken branch.
     *
     * @param actual    the borrowed value
     * @param expected  the value the COBOL declares
     * @param cobolName the COBOL item's name, for the failure message
     * @param where     the file and line that declare it, for the failure message
     * @throws AssertionError if the two disagree
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            String where) {
        if (!expected.equals(actual)) {
            throw new AssertionError(cobolName + " is declared as '" + expected + "' at " + where
                    + ", but this class resolved it to '" + actual + "'");
        }
    }

    // =================================================================================================
    // Collaborators.
    //
    // Constructor-injected, every one final, and not one of them mutable state (practice B9, gate G53).
    // PfKeyResolver and FieldAttributeSetter are deliberately ABSENT from this list: both are final
    // utility classes with private constructors and static-only APIs, so there is no instance to inject.
    // The migration brief asks for them to be "constructor-injected"; they cannot be, and inventing an
    // instance wrapper purely to satisfy the wording would add a type the plan does not name. They are
    // used statically instead, and this note records the divergence (practice B4).
    // =================================================================================================

    /**
     * The {@code CARDDAT} base cluster and its {@code CARDAIX} alternate-index path - the only route to
     * data this class has.
     *
     * <p>No {@code JdbcTemplate} and no dataset name appears anywhere in this file (gate G46); the
     * repository resolves both names from {@code application.yml}.
     */
    private final CardRepository cardRepository;

    /**
     * The instant {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} reads
     * ({@code app/cbl/COCRDSLC.cbl:430} and again at {@code :437}).
     *
     * <p>Injected rather than read inline so a parity case can fix the instant and obtain a
     * byte-identical screen. {@code Clock} is immutable and thread safe, and a {@code @Bean Clock} is
     * already published by {@code WebConfig}.
     */
    private final Clock clock;

    /**
     * The fixed-width codec, carrying the dataset code page and owning the {@code MOVE} rules.
     *
     * <p>Immutable and stateless. It is held rather than created per call because a {@code Charset}
     * lookup is not free and because there must be exactly one place the code page is decided - never
     * the platform default (practice B8).
     */
    private final FixedWidthCodec codec;

    /**
     * Spring's constructor, wiring the repository, the clock and the active dataset code page.
     *
     * <p>The {@code Charset} is qualified explicitly. {@code CobolCharsetConfig} publishes three -
     * EBCDIC, US-ASCII and the active dataset page - and picking the wrong one would decode every field
     * at the wrong byte, so the choice is stated at the injection point rather than left to type
     * matching.
     *
     * @param cardRepository the card file's repository; must not be {@code null}
     * @param clock          the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param datasetCharset the active dataset code page, from
     *                       {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must not
     *                       be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardSelectController(
            CardRepository cardRepository,
            Clock clock,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "A CardRepository is required: COCRDSLC reads CARDDAT and its CARDAIX path, and this "
                        + "controller reaches neither any other way");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 1100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice, and reading a "
                        + "clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: a fixed-width mainframe field is bytes in a specific "
                        + "code page, so the code page is always stated and never taken from the "
                        + "platform"));
    }

    /**
     * The codec this controller applies every {@code MOVE} through, carrying the active dataset code page.
     *
     * <p>Exposed package-visibly for two reasons, both about verification rather than convenience. A
     * parity case has to build its expected images with the <em>same</em> code page the controller
     * decodes with - building them with a different one would compare two encodings and call the
     * difference a defect - and a deployment check needs to be able to assert which page was wired
     * without starting the application (residual risk R-E). It is not {@code public}, because a code page
     * is this module's business and no client's.
     *
     * @return the codec; never {@code null}, and immutable
     */
    FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // WS-THIS-PROGCOMMAREA - app/cbl/COCRDSLC.cbl:200-203.
    //
    // The twelve-byte trailer this program appends to CARDDEMO-COMMAREA when it returns is declared as
    // CardSelectRequest.ThisProgCommarea, not here. It belongs with the payload types because it is
    // payload: :274-278 restores it from what the caller passed and :398-400 returns it, so it has to
    // travel in the request and come back in the response, and a carrier declared inside the controller
    // could not be a member of either without the dto package depending on the controller. The paired
    // request owns it and the paired response imports it, which is exactly how the CT01 screen carries
    // CDEMO-CT01-INFO (TransactionAddRequest.Ct01Info).
    // =================================================================================================

    // =================================================================================================
    // The per-request conversation.
    //
    // Everything COBOL kept in WORKING-STORAGE for the life of one task lives here, and one of these is
    // constructed per call. NOT ONE of these values is a field on the controller: the controller is a
    // singleton, so a WORKING-STORAGE field promoted to an instance field would leak one caller's search
    // criteria into another's screen and make every test order-dependent (practice B9, gate G53).
    //
    // Package-visible, and every member reachable, because the parity tests assert on WS-RETURN-MSG,
    // WS-INFO-MSG and the four edit flags directly - the COBOL's observable state is these values, not
    // just the rendered screen (gate G51).
    // =================================================================================================

    /**
     * One task's worth of {@code WORKING-STORAGE}: {@code WS-MISC-STORAGE}, {@code CC-WORK-AREA},
     * {@code CARDDEMO-COMMAREA}, {@code WS-THIS-PROGCOMMAREA} and {@code WS-COMMAREA}.
     *
     * <p>Deliberately mutable, because {@code WORKING-STORAGE} is: the program assigns into it throughout
     * processing. An instance is consequently <strong>not</strong> thread safe and is confined to the
     * request that created it, exactly as a CICS task's storage is confined to its task.
     */
    static final class Conversation {

        // ---- WS-CICS-PROCESSNG-VARS, app/cbl/COCRDSLC.cbl:40-46 -------------------------------------

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code :41-42}; the CICS {@code RESP}. */
        int wsRespCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code :43-44}; the CICS {@code RESP2}. */
        int wsReasCd;

        /** {@code WS-TRANID PIC X(4) VALUE SPACES} - {@code :45-46}; set to {@code 'CCDL'} at {@code :260}. */
        String wsTranid;

        // ---- Input edit flags, app/cbl/COCRDSLC.cbl:51-68 -------------------------------------------

        /** {@code WS-INPUT-FLAG PIC X(1)} - {@code :51}; carries {@code INPUT-OK} / {@code INPUT-ERROR}. */
        String wsInputFlag;

        /** {@code WS-EDIT-ACCT-FLAG PIC X(1)} - {@code :55}; carries the three {@code FLG-ACCTFILTER-*} names. */
        String wsEditAcctFlag;

        /** {@code WS-EDIT-CARD-FLAG PIC X(1)} - {@code :59}; carries the three {@code FLG-CARDFILTER-*} names. */
        String wsEditCardFlag;

        /** {@code WS-RETURN-FLAG PIC X(1)} - {@code :63}; declared by the program and never tested (B5). */
        String wsReturnFlag;

        /** {@code WS-PFK-FLAG PIC X(1)} - {@code :66}; carries {@code PFK-VALID} / {@code PFK-INVALID}. */
        String wsPfkFlag;

        // ---- CICS-OUTPUT-EDIT-VARS, app/cbl/COCRDSLC.cbl:72-92 --------------------------------------
        //
        // Of these ten items the program uses exactly one: CARD-EXPIRAION-DATE-X, which receives
        // CARD-EXPIRAION-DATE at :477-478 so that the FILLER REDEFINES at :85-90 can split it into year,
        // month and day. The other nine are declared and never referenced, and they are kept because
        // deleting a dead declaration is a change (practice B5).

        /** {@code CARD-ACCT-ID-X PIC X(11)} with {@code CARD-ACCT-ID-N REDEFINES} - {@code :73-75}; unused (B5). */
        String cardAcctIdX;

        /** {@code CARD-CVV-CD-X PIC X(03)} with {@code CARD-CVV-CD-N REDEFINES} - {@code :76-78}; unused (B5). */
        String cardCvvCdX;

        /** {@code CARD-CARD-NUM-X PIC X(16)} with {@code CARD-CARD-NUM-N REDEFINES} - {@code :79-81}; unused (B5). */
        String cardCardNumX;

        /** {@code CARD-NAME-EMBOSSED-X PIC X(50)} - {@code :82}; unused (B5). */
        String cardNameEmbossedX;

        /** {@code CARD-STATUS-X PIC X} - {@code :83}; unused (B5). */
        String cardStatusX;

        /**
         * {@code CARD-EXPIRAION-DATE-X PIC X(10)} - {@code app/cbl/COCRDSLC.cbl:84}, with the
         * {@code FILLER REDEFINES} at {@code :85-90} splitting it into
         * {@code CARD-EXPIRY-YEAR X(4)}, a one-character {@code FILLER},
         * {@code CARD-EXPIRY-MONTH X(2)}, another {@code FILLER} and {@code CARD-EXPIRY-DAY X(2)}.
         *
         * <p>The misspelling is the copybook's - {@code app/cpy/CVACT02Y.cpy:9} declares
         * {@code CARD-EXPIRAION-DATE}, not {@code CARD-EXPIRATION-DATE} - and it is carried verbatim. A
         * corrected name would silently break the parity differ's field-for-field comparison.
         *
         * <p>{@code CARD-EXPIRAION-DATE-N REDEFINES} at {@code :91-92} views the same ten bytes as
         * {@code PIC 9(10)} and is never referenced (B5).
         */
        String cardExpiraionDateX;

        // ---- File and data handling, app/cbl/COCRDSLC.cbl:97-121 ------------------------------------

        /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :98}; the {@code CARDDAT} key, filled at {@code :740}. */
        String wsCardRidCardnum;

        /**
         * {@code WS-CARD-RID-ACCT-ID PIC 9(11)} - {@code app/cbl/COCRDSLC.cbl:99}, with
         * {@code WS-CARD-RID-ACCT-ID-X REDEFINES} at {@code :100-101} viewing the same eleven bytes as
         * {@code PIC X(11)}.
         *
         * <p>The {@code CARDAIX} key. It is read by {@link CardSelectController#getCardByAcct9150} but
         * <strong>never assigned</strong>: the one statement that would have filled it,
         * {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID}, is commented out at {@code :739}. That
         * comment stays absent (practice B5), so the field holds whatever {@code INITIALIZE} left -
         * eleven zeros, since it is {@code PIC 9}.
         */
        String wsCardRidAcctId;

        /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :105-106}. */
        String errorOpname;

        /** {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :109-110}. */
        String errorFile;

        /** {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :114-115}. */
        String errorResp;

        /** {@code ERROR-RESP2 PIC X(10) VALUE SPACES} - {@code :118-119}. */
        String errorResp2;

        // ---- Output message construction, app/cbl/COCRDSLC.cbl:125-158 ------------------------------

        /**
         * {@code WS-LONG-MSG PIC X(500)} - {@code :125}; the {@code SEND-LONG-TEXT} buffer.
         *
         * <p>Declared, and never written by any paragraph, because {@code SEND-LONG-TEXT} itself is never
         * performed - see {@link CardSelectController#sendLongText}. Kept per practice B5.
         */
        String wsLongMsg;

        /** {@code WS-INFO-MSG PIC X(40)} - {@code :126}; the informational line, {@code INFOMSGO}'s source. */
        String wsInfoMsg;

        /** {@code WS-RETURN-MSG PIC X(75)} - {@code :134}; the error line, {@code ERRMSGO}'s source. */
        String wsReturnMsg;

        // ---- Carried conversation state -------------------------------------------------------------

        /** {@code CC-WORK-AREA} from {@code app/cpy/CVCRD01Y.cpy}, copied at {@code app/cbl/COCRDSLC.cbl:194}. */
        CardScreenState ccWorkArea;

        /** {@code CARDDEMO-COMMAREA} from {@code app/cpy/COCOM01Y.cpy}, copied at {@code :198}. */
        NavigationContext carddemoCommarea;

        /** {@code WS-THIS-PROGCOMMAREA} - {@code :200-203}; twelve bytes passed straight through. */
        ThisProgCommarea thisProgCommarea;

        /** {@code WS-COMMAREA PIC X(2000)} - {@code :205}; what {@code COMMON-RETURN} returns. */
        String wsCommarea;

        // ---- Record and diagnostic areas ------------------------------------------------------------

        /**
         * {@code CARD-RECORD} from {@code app/cpy/CVACT02Y.cpy}, copied at {@code :234} - the 150-byte
         * area {@code EXEC CICS READ ... INTO(CARD-RECORD)} fills.
         *
         * <p>Empty until a read succeeds. The program does not test emptiness; it tests
         * {@code IF FOUND-CARDS-FOR-ACCOUNT}, which is a condition name on {@code WS-INFO-MSG}, and that
         * indirection is reproduced rather than replaced with a null check.
         */
        Optional<CardRecord> cardRecord = Optional.empty();

        /** {@code ABEND-DATA} from {@code app/cpy/CSMSG02Y.cpy}, copied at {@code :224}. */
        SystemMessages.AbendData abendData;

        /**
         * {@code SEC-USER-DATA} from {@code app/cpy/CSUSR01Y.cpy}, copied at
         * {@code app/cbl/COCRDSLC.cbl:227} under the heading "Signed on user data".
         *
         * <p><strong>A declared-only area.</strong> {@code COCRDSLC} copies the eighty-byte record and
         * then references not one of its six fields - it neither reads {@code USRSEC} nor tests
         * {@code SEC-USR-TYPE}. Authorisation on this screen is decided entirely by the commarea's
         * {@code CDEMO-USER-TYPE}, and even that is only <em>written</em> (see
         * {@link CardSelectController#backNavigationPfk03}), never tested.
         *
         * <p>Modelled regardless, for the same reason {@link #wsLongMsg} and the nine unused
         * {@code CICS-OUTPUT-EDIT-VARS} items are: a {@code COPY} statement allocates storage, allocated
         * storage is part of the program, and deleting a declaration because nothing happens to read it
         * is a change (practice <strong>B5</strong>). Nothing here introduces authentication the COBOL
         * does not perform - practice <strong>B6</strong> forbids strengthening the posture as firmly as
         * it forbids weakening it - and in particular {@code SEC-USR-PWD} is never compared here.
         */
        SecUserRecord secUserData;

        /**
         * {@code CUSTOMER-RECORD} from {@code app/cpy/CVCUS01Y.cpy}, copied at
         * {@code app/cbl/COCRDSLC.cbl:240} under the heading "CUSTOMER LAYOUT".
         *
         * <p>A declared-only area on the same footing as {@link #secUserData}: the five-hundred-byte
         * record is allocated and no field of it is ever referenced. {@code COCRDSLC} reads the card file
         * and nothing else, so no customer is fetched - which is precisely why the two account-and-card
         * layouts sitting beside it, {@code CVACT01Y} at {@code :231} and {@code CVACT03Y} at
         * {@code :237}, are <strong>commented out</strong> while this one is not. That distinction is a
         * verified source fact and is reproduced rather than tidied into consistency.
         */
        CustomerRecord customerRecord;

        /** The {@code CSDAT01Y} header {@code 1100-SCREEN-INIT} builds from {@code FUNCTION CURRENT-DATE}. */
        DateHeader dateHeader;

        /**
         * Set once the flow has reached a terminal paragraph - {@code COMMON-RETURN},
         * {@code SEND-PLAIN-TEXT} or {@code SEND-LONG-TEXT}, each of which ends in
         * {@code EXEC CICS RETURN}.
         *
         * <p>This is how the nine {@code GO TO}s are restructured without changing the outcome: a
         * {@code GO TO COMMON-RETURN} becomes "run the terminal paragraph, mark the task returned, and
         * stop", so the trailing {@code IF INPUT-ERROR} guard at {@code :386-391} is skipped exactly when
         * the COBOL skipped it - which is whenever an earlier {@code GO TO} fired (rule R7).
         */
        boolean returned;

        // ---- 88-level predicates --------------------------------------------------------------------
        //
        // One method per condition name, so a caller reads what the COBOL reads. Each is a comparison
        // against the stored character, never a null check: after INITIALIZE the flags hold a SPACE,
        // which satisfies none of WS-INPUT-FLAG's three names and satisfies FLG-*-BLANK on both edit
        // flags. That difference is behaviour and is preserved.

        /** {@code 88 INPUT-OK VALUE '0'}, {@code app/cbl/COCRDSLC.cbl:52}. */
        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        /** {@code 88 INPUT-ERROR VALUE '1'}, {@code :53} - tested at {@code :360}, {@code :386}. */
        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /** {@code 88 INPUT-PENDING VALUE LOW-VALUES}, {@code :54} - declared, never tested (B5). */
        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'}, {@code :56} - tested at {@code :516}, {@code :533}. */
        boolean flgAcctfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'}, {@code :57} - set at {@code :612}, {@code :677}. */
        boolean flgAcctfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '}, {@code :58} - tested at {@code :517}, {@code :541}, {@code :637}. */
        boolean flgAcctfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-CARDFILTER-NOT-OK VALUE '0'}, {@code :60} - tested at {@code :519}, {@code :537}. */
        boolean flgCardfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardFlag);
        }

        /** {@code 88 FLG-CARDFILTER-ISVALID VALUE '1'}, {@code :61} - set at {@code :611}, {@code :718}. */
        boolean flgCardfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardFlag);
        }

        /** {@code 88 FLG-CARDFILTER-BLANK VALUE ' '}, {@code :62} - tested at {@code :520}, {@code :547}, {@code :638}. */
        boolean flgCardfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardFlag);
        }

        /** {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES}, {@code :64} - declared, never tested (B5). */
        boolean wsReturnFlagOff() {
            return WS_RETURN_FLAG_OFF.equals(wsReturnFlag);
        }

        /** {@code 88 WS-RETURN-FLAG-ON VALUE '1'}, {@code :65} - declared, never tested (B5). */
        boolean wsReturnFlagOn() {
            return WS_RETURN_FLAG_ON.equals(wsReturnFlag);
        }

        /** {@code 88 PFK-VALID VALUE '0'}, {@code :67}. */
        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        /** {@code 88 PFK-INVALID VALUE '1'}, {@code :68} - tested at {@code :297}. */
        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        /**
         * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES}, {@code :127-128} - tested at
         * {@code :490} and {@code :553}.
         *
         * <p>A {@code VALUES} list of two, so both images answer true.
         */
        boolean noInfoMessage() {
            return WS_INFO_MSG_SPACES.equals(wsInfoMsg) || WS_INFO_MSG_LOW_VALUES.equals(wsInfoMsg);
        }

        /**
         * {@code 88 FOUND-CARDS-FOR-ACCOUNT}, {@code :129-130} - tested at {@code :474}, set at
         * {@code :754} and {@code :795}.
         */
        boolean foundCardsForAccount() {
            return FOUND_CARDS_FOR_ACCOUNT.equals(wsInfoMsg);
        }

        /** {@code 88 WS-PROMPT-FOR-INPUT}, {@code :131-132} - set at {@code :460} and {@code :491}. */
        boolean promptForInput() {
            return WS_PROMPT_FOR_INPUT.equals(wsInfoMsg);
        }

        /**
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES}, {@code app/cbl/COCRDSLC.cbl:135}.
         *
         * <p>Seventy-five <strong>spaces</strong>. See the class documentation for why this is not
         * {@code LOW-VALUES} and not {@link CardScreenState#isCcardReturnMsgOff()}.
         */
        boolean returnMessageOff() {
            return WS_RETURN_MSG_OFF.equals(wsReturnMsg);
        }
    }

    // =================================================================================================
    // The HTTP surface - GET /api/cards/{cardNum}, CSD transaction CCDL.
    //
    // One route, because the plan names one (section 0.3.9), and it binds the WHOLE symbolic-map request.
    //
    // It used to bind a path variable and twelve ad-hoc query parameters instead - acctId, eibAid,
    // eibcalen, pgmContext, fromTranid, fromProgram, userId, userType, cdemoAcctId, cdemoCardNum,
    // lastMapset, lastMap - and built a CardSelectRequest from them internally. Three things were wrong
    // with that, and all three are corrected here:
    //
    //   * THE CONTRACT WAS NOT THE SYMBOLIC MAP. CardSelectRequest is derived field-for-field from
    //     app/cpy-bms/COCRDSL.CPY and is the published request shape, but nothing bound it, so thirteen
    //     of its fifteen fields could not be sent at all and the two that could arrived under invented
    //     names - acctId and cardNum rather than the DFHMDF labels ACCTSID and CARDSID.
    //   * THE 12-BYTE TRAILER HAD NOWHERE TO TRAVEL. COMMON-RETURN returns CARDDEMO-COMMAREA followed by
    //     WS-THIS-PROGCOMMAREA (app/cbl/COCRDSLC.cbl:397-400), 160 + 12 = 172 bytes. A query string of
    //     commarea fields had no room for the trailer, so :274-278's restore had nothing to restore from.
    //   * NOTHING WAS VALIDATED. An over-long card number was silently truncated to PIC X(16) - which is
    //     one URI addressing a DIFFERENT card - an EIBAID was narrowed straight to byte, and EIBCALEN was
    //     whatever the caller said it was, including negative.
    //
    // So the request DTO is bound with @Valid, as a body: one call is one execution of COCRDSLC, and the
    // fifteen screen fields, the 160-byte commarea and the 12-byte trailer are a state document rather
    // than a query string. GET keeps the plan's route verbatim (section 0.3.9 names GET
    // /api/cards/{cardNum}) and the body is optional, because a cold start has no state to send - which is
    // EIBCALEN = 0, the arm at :268. The in-package precedent is DELETE /api/users/{userId}, which binds
    // @RequestBody(required = false) for exactly the same reason.
    //
    // {cardNum} stays the only identity in the URI and is authoritative: it is reconciled with CARDSID
    // rather than competing with it.
    //
    // This method contains NO decision logic beyond that reconciliation and the three boundary guards.
    // Every branch of the program lives below, reachable from a plain JUnit test with a mocked repository
    // (gate G51).
    // =================================================================================================

    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MAX = 255;

    /**
     * The name of the query parameter carrying the raw {@code EIBAID} byte.
     *
     * <p>{@link AidRequestParameter#CANONICAL_NAME}, shared with every other online route rather than
     * spelled here. <strong>This route is the one the sharing matters most on:</strong>
     * {@link CardSelectRequest} declares no {@code CCARD-AID} member, so the query parameter is the only
     * channel a caller has for the key. While this screen accepted one spelling and its sibling card
     * list accepted the other, a {@code PF3} sent with the sibling's spelling was discarded by Spring
     * and the request ran as {@link CicsAid#DFHENTER} - the operator's exit silently became a validation
     * screen. Both spellings are now bound here and everywhere; see {@link #EIBAID_PARAM_ALIAS}.
     */
    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    /**
     * The alternate spelling of {@link #EIBAID_PARAM}, and the name this route originally declared.
     *
     * <p>It keeps working, on this route and on all the others: withdrawing it would have turned every
     * call that already used it into a silently ignored key, which is the defect being closed rather
     * than a fix for it.
     */
    static final String EIBAID_PARAM_ALIAS = AidRequestParameter.ALTERNATE_NAME;

    /** The name of the query parameter carrying {@code EIBCALEN}. */
    static final String EIBCALEN_PARAM = "eibcalen";

    /**
     * The payload member the URI's card number binds, spelled as the client sends it.
     *
     * <p>Lowercase and {@code xxxI}-derived, which is this module's one JSON naming convention, so a
     * refusal names the member the caller can find in its own request body.
     */
    static final String CARDSID_MEMBER = "cardsid";
    /**
     * The one-character image a screen paints into a key field when no criterion was supplied.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:563} and {@code app/cbl/COCRDSLC.cbl:543,549} move {@code '*'} into
     * the output field, and {@code COACTVWC:628}, {@code COACTUPC:1051} and {@code COCRDSLC:615,622} read
     * {@code = '*'} back as "not supplied". So an asterisk names no record, and a client echoing that
     * painted screen is agreeing with the URI rather than contradicting it.
     */
    static final String NO_CRITERION_IMAGE = "*";



    /**
     * Displays one credit card's detail screen: {@code GET /api/cards/{cardNum}}, transaction
     * {@code CCDL}, mapset {@code COCRDSL}, map {@code CCRDSLA}, fifteen fields.
     *
     * <p>Despite the class name this is a <strong>detail view</strong> - see the class documentation for
     * the {@code CardSelectController} / {@code COCRDSLC} naming divergence and its resolution under rule
     * R1.
     *
     * <p>Always answers {@code 200 OK}, because {@code COCRDSLC} always ends in {@code EXEC CICS RETURN}:
     * every path either paints the screen ({@code COMMON-RETURN}, {@code app/cbl/COCRDSLC.cbl:402}) or
     * sends plain text and returns ({@code SEND-PLAIN-TEXT}, {@code :846}). A rejected search filter is a
     * message on the screen, not a {@code 4xx} - and an unrecognised function key is not even that, since
     * {@code :297-299} silently coerces it to {@code ENTER}. The only status this endpoint can produce
     * other than {@code 200} comes from {@code ABEND-ROUTINE}, which raises {@link AbendException} and is
     * answered {@code 500} by {@code WebConfig.CobolErrorHandler} - the faithful projection of
     * {@code EXEC CICS ABEND ABCODE('9999')} at {@code :875-877}.
     *
     * @param cardNum  {@code CARDSIDI PIC X(16)} - the card number this URI addresses, and the
     *                 {@code CARDDAT} key {@code 9100-GETCARD-BYACCTCARD} reads with. Required, because
     *                 it is the path, and authoritative: it is moved into {@code CARDSID} and, when a
     *                 communication area was passed, into that area's {@code CDEMO-CARD-NUM} as well, so
     *                 that neither of the two arms that read a card number can read a different one. A
     *                 body stating either carrier has its value replaced rather than refused
     * @param request  the whole {@code 01 CCRDSLAI} symbolic-map request, validated against the declared
     *                 widths, together with the 160-byte {@code CARDDEMO-COMMAREA} and the 12-byte
     *                 {@code WS-THIS-PROGCOMMAREA} trailer {@code :274-278} restores from.
     *                 {@code null} when no body was sent, which is the {@code EIBCALEN = 0} cold start
     * @param eibAid   {@code EIBAID} - the raw attention identifier byte, {@code 0}-{@code 255}, under
     *                 the alternate spelling this route originally declared. Absent means
     *                 {@link CicsAid#DFHENTER}. It is resolved by {@link PfKeyResolver}, so an
     *                 unrecognised byte reaches the same no-match arm the COBOL's {@code EVALUATE}
     *                 leaves unhandled - it is coerced to {@code ENTER} at {@code :299-308}, never
     *                 refused
     * @param eibaid   the same value under {@link AidRequestParameter#CANONICAL_NAME}, the spelling
     *                 every online route shares. At most one of the two need be sent; sending both with
     *                 different values is refused, because a terminal presents one attention identifier
     * @param eibcalen {@code EIBCALEN} - the length of the passed commarea, and therefore either
     *                 {@value #NO_COMMAREA_LENGTH} or {@value #PASSED_COMMAREA_LENGTH} and nothing else.
     *                 Absent is derived from the carrier, and a stated value that contradicts the
     *                 carrier is refused rather than believed. The distinction is load-bearing at
     *                 {@code :268}
     * @return the painted screen and its metadata: the fifteen fields, the next-screen triple, the
     *         commarea, the 12-byte trailer and the attribute quads, all in the body so nothing is
     *         retained server-side
     * @throws IllegalArgumentException if {@code cardNum} or a bound field is wider than its
     *                                  {@code PICTURE}, if the AID is outside {@code 0}-{@code 255}, if
     *                                  the two AID spellings disagree, or if {@code eibcalen} is neither
     *                                  of the two lengths or disagrees with the carrier - each answered
     *                                  {@code 400} by {@code WebConfig.CobolErrorHandler} with no value
     *                                  echoed
     */
    // The canonical AID spelling is the LAST parameter rather than sitting beside its alternate, and
    // deliberately: Spring binds a query parameter by the name in its annotation and never by position,
    // while the four parameters this method already had are positional to every direct caller - the
    // parity tests call it with no HTTP in the path. Appending leaves each of those four meaning exactly
    // what it meant, instead of quietly turning a commarea length into an attention identifier.
    @GetMapping(path = "/api/cards/{cardNum}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<CardSelectResponse>> viewCardDetail(
            @PathVariable("cardNum") String cardNum,
            @Valid @RequestBody(required = false) CardSelectRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {

        Objects.requireNonNull(cardNum, "A card number is required in the path: it is the RIDFLD of the "
                + "READ at app/cbl/COCRDSLC.cbl:806-813");

        CardSelectRequest received = bind(cardNum, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier =
                resolveAttentionIdentifier(AidRequestParameter.resolve(eibaid, eibAid));

        CardSelectResponse painted = handle(received, commareaLength, attentionIdentifier);
        return ResponseEntity.ok(ScreenResponse.of(painted, painted.screenMetadata()));
    }

    /**
     * Reconciles the URI's card number with the bound request, and refuses the two ways a caller could
     * otherwise reach a record the URI does not name.
     *
     * <h4>Why an over-width value is refused rather than moved</h4>
     * {@code MOVE} to a {@code PIC X(16)} field keeps the leading sixteen characters and discards the
     * rest, so {@code /api/cards/40000000000000019999} would have been truncated to
     * {@code 4000000000000001} and read <em>that</em> card - a URI addressing a record it does not
     * name, and one no operator could have typed, because a 3270 field physically cannot accept more
     * characters than it declares. The COBOL move is faithful for a value that fits; for one that does
     * not, there is nothing faithful to reproduce, so the request is refused at the boundary before any
     * padding, any repository call and any lock.
     *
     * <h4>The path is projected into both carriers of the key</h4>
     * This program reads the card number from two places, and which one it reads depends on which arm of
     * {@code :268-371} the request lands on. On re-entry it is the typed field {@code CARDSIDI}, edited by
     * {@code :622-627} into {@code CC-CARD-NUM}. On a fresh arrival from the card-list screen it is
     * {@code CDEMO-CARD-NUM} in the communication area, which {@code :343} moves straight into
     * {@code CC-CARD-NUM-N} before reading - the typed field is not consulted at all on that arm. Both
     * carriers are therefore set from the path here, before any arm is selected and before any repository
     * call, so the URI is the only statement of which record is read.
     *
     * <p>Leaving the communication area as the caller sent it would leave a second, independently
     * client-controlled identity, and {@code GET /api/cards/A} with a context naming card B would return
     * B's card number, account, embossed name, status and expiry - a URI answering with a record it does
     * not name. {@code 9100-GETCARD-BYACCTCARD} keys the read on the card number alone (its
     * {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID} is commented out in the source), so projecting the
     * card number is what closes it; {@code CDEMO-ACCT-ID} stays as the payload delivered it, because it
     * is a painted echo rather than part of the key.
     *
     * <p>No disagreement error is raised. {@code COCRDSLC} has no such condition - a terminal has one
     * value, not two - so inventing one would add a failure mode the legacy screen cannot produce. A
     * client that re-sends a screen it painted from this URI agrees with the path and sees no difference;
     * one that names a second card has that value replaced rather than acted on. The source's own
     * "no criterion" handling at {@code :622-626} and its {@code 2220-EDIT-CARD} messages stay reachable,
     * because a path value that is blank or not sixteen digits is passed to them unchanged.
     *
     * @param cardNum the path variable; must not be {@code null}
     * @param request the bound body, or {@code null} for a cold start
     * @return the request to execute, with {@code CARDSID} and - when a communication area was passed -
     *         {@code CDEMO-CARD-NUM} both set from the path; never {@code null}
     * @throws IllegalArgumentException if the path value is wider than {@code CARDSID}
     *
     * <h4>The payload's own key field must not contradict the URI</h4>
     * This route states the record's key twice - in the URI and in {@code CARDSIDI}, the field the URI
     * binds - and a terminal has only one. The payload's member is therefore required to agree before it
     * is overwritten: absent, blank, {@code LOW-VALUES} or the URI's key is accepted, anything else is
     * refused at the boundary by
     * {@link ScreenInputRejectedException#requireKeyAgreement(String, String, String, int, FixedWidthCodec)}.
     * Overwriting it silently, which is what happened before, discarded the operator's own typed card
     * number with no message. A client that echoes a painted screen agrees with the URI and never reaches
     * the refusal.
     */
    CardSelectRequest bind(String cardNum, CardSelectRequest request) {
        if (cardNum.length() > CardSelectRequest.CARDSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(CARDSID_MEMBER,
                    "CARDSIDI PIC X(" + CardSelectRequest.CARDSID_LENGTH + ")",
                    CardSelectRequest.CARDSID_LENGTH, cardNum.length());
        }

        CardSelectRequest received = request == null ? coldStartRequest() : new CardSelectRequest(request);
        ScreenInputRejectedException.requireKeyAgreement(CARDSID_MEMBER, cardNum,
                received.getCardsid(), CardSelectRequest.CARDSID_LENGTH, codec, NO_CRITERION_IMAGE);
        received.setCardsid(codec.movePicX(cardNum, CardSelectRequest.CARDSID_LENGTH));

        // The communication area's own card number, the one :343 reads. Projected only when an area was
        // actually passed: a null context is EIBCALEN = 0, which :268 branches on, and fabricating one
        // here would send the request down an arm the caller never reached.
        if (received.hasNavigationContext()) {
            received.setNavigationContext(
                    received.getNavigationContext().withCardNum(carriedCardNumber(cardNum)));
        }

        // Never null: initializeState() fills all fifteen items at their declared widths, setAcctsid
        // normalises a null - including an explicit JSON null, because Jackson binds through the setter
        // rather than the field - and the copy constructor copies a value that is already there. A COBOL
        // alphanumeric item has no absent state, and this one has none either, so no null guard is
        // written for a state that cannot occur.
        String acctsid = received.getAcctsid();
        if (acctsid.length() > CardSelectRequest.ACCTSID_LENGTH) {
            throw new IllegalArgumentException("ACCTSID is ACCTSIDI PIC X("
                    + CardSelectRequest.ACCTSID_LENGTH + ") and was given " + acctsid.length()
                    + " characters. Padding it would keep the leading "
                    + CardSelectRequest.ACCTSID_LENGTH + " and filter on a different account.");
        }
        received.setAcctsid(codec.movePicX(acctsid, CardSelectRequest.ACCTSID_LENGTH));
        return received;
    }

    /**
     * The URI's card number as {@code CDEMO-CARD-NUM PIC 9(16)} holds it.
     *
     * <p>{@code CARDSID} is {@code PIC X(16)} and the communication area's carried card number is
     * {@code PIC 9(16)} - alphanumeric on the screen, numeric in the commarea - so the projection has to
     * cross that boundary. A path value of sixteen digits or fewer is the number it spells, leading zeros
     * and all, since {@code :343} writes through the numeric {@code REDEFINES} view and zero-fills on the
     * left. Anything a {@code PIC 9(16)} item cannot hold - a blank segment, {@code '*'}, or any value
     * with a non-digit in it - yields zero, which is the area's own unset value and the one
     * {@code 1000-SEND-MAP} already tests for. Zero rather than a refusal, because zero is the only answer
     * that cannot name a card the URI does not: the arm reads it, finds nothing, and the source's
     * {@code NOTFND} handling paints its own message, exactly as it does for a card the operator typed
     * that does not exist.
     *
     * <p>Trailing and leading spaces are stripped before the digit test, because a client echoing a
     * painted screen sends {@code CARDSID} space-padded to its declared width and a padded number is the
     * same number.
     *
     * @param cardNum the path variable, already known to fit {@code CARDSID}
     * @return the number for {@code CDEMO-CARD-NUM}, or {@code 0} when the path states none a
     *         {@code PIC 9(16)} item could hold
     */
    static long carriedCardNumber(String cardNum) {
        String trimmed = cardNum.trim();
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

    /**
     * The request a bodiless call executes: an initialised map area, no commarea and an initialised
     * trailer - which is what {@code EIBCALEN = 0} means at {@code app/cbl/COCRDSLC.cbl:268}.
     *
     * @return a fresh request; never {@code null}
     */
    private static CardSelectRequest coldStartRequest() {
        CardSelectRequest cold = new CardSelectRequest();
        cold.initializeMapArea();
        return cold;
    }

    /**
     * Resolves {@code EIBCALEN} from the stated value and the carrier, and refuses any statement the
     * carrier does not support.
     *
     * <h4>Why a caller may not simply declare it</h4>
     * {@code EIBCALEN} is not caller data on a real terminal: CICS sets it to the length of the area it
     * actually passed. It selects the arm at {@code :268} that decides whether the operator's typed
     * criteria and the calling program's identity survive the turn, so a caller that could state it
     * freely could discard state that was sent, or claim state that was not.
     *
     * <h4>Why the two accepted values are 0 and {@value #PASSED_COMMAREA_LENGTH}</h4>
     * {@code :268} tests the value against zero and nothing else, and the only other thing the program
     * does with the passed area is read exactly {@value #PASSED_COMMAREA_LENGTH} bytes out of it -
     * {@code DFHCOMMAREA(1:160)} at {@code :274-275} and {@code DFHCOMMAREA(161:12)} at {@code :276-278}.
     * The projected request carries precisely those two areas, so it is in one of exactly two states:
     * absent, or complete at {@value #PASSED_COMMAREA_LENGTH} bytes. The byte count a real terminal would
     * report is not one number - {@code COCRDLIC} transfers control passing {@code CARDDEMO-COMMAREA}
     * alone [{@code app/cbl/COCRDLIC.cbl:538-540}] while this program's own {@code COMMON-RETURN} passes
     * {@code WS-COMMAREA}, declared {@code PIC X(2000)} [{@code app/cbl/COCRDSLC.cbl:205}] - and since
     * none of those numbers is tested for anything but zero, reproducing the terminal-dependent count
     * would add a distinction the program does not make. The parameter states the presence of the area
     * the program reads, at the length it reads.
     *
     * <p>So: absent is derived from the carrier, a stated value must be one of the two lengths, and it
     * must agree with what actually arrived.
     *
     * @param eibcalen the stated value, or {@code null}
     * @param request  the bound request, whose commarea presence is the carrier
     * @return {@value #NO_COMMAREA_LENGTH} or {@value #PASSED_COMMAREA_LENGTH}
     * @throws IllegalArgumentException if the stated value is neither length, or contradicts the carrier
     */
    static int resolveEibcalen(Integer eibcalen, CardSelectRequest request) {
        int carried = request.hasNavigationContext() ? PASSED_COMMAREA_LENGTH : NO_COMMAREA_LENGTH;
        if (eibcalen == null) {
            return carried;
        }
        int stated = eibcalen;
        if (stated != NO_COMMAREA_LENGTH && stated != PASSED_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter is " + stated
                    + ", but CICS sets EIBCALEN to the length of the area it passed - which for this "
                    + "program is either " + NO_COMMAREA_LENGTH + " or " + PASSED_COMMAREA_LENGTH
                    + ", CARDDEMO-COMMAREA plus WS-THIS-PROGCOMMAREA.");
        }
        if (stated != carried) {
            throw new IllegalArgumentException("The " + EIBCALEN_PARAM + " parameter says " + stated
                    + " but the payload carries " + (carried == NO_COMMAREA_LENGTH ? "no" : "a")
                    + " communication area. EIBCALEN describes what arrived; it cannot contradict it, "
                    + "because app/cbl/COCRDSLC.cbl:268 uses it to decide whether the conversation's "
                    + "state survives the turn.");
        }
        return stated;
    }

    /**
     * Narrows the stated {@code EIBAID} to the byte {@code :291-299} tests, having first required it to
     * be a byte.
     *
     * <p>An {@code Integer} cast straight to {@code byte} keeps the low eight bits and discards the
     * rest, so {@code 499} would arrive as {@code 0xF3} - {@code DFHPF3}, the one key besides
     * {@code ENTER} this screen acts on - and {@code -14} as {@code 0xF2}. Neither is a key anyone
     * pressed. This is the guard {@code UserAddController}, {@code UserMenuController} and
     * {@code TransactionMenuController} already apply, written the same way for the same reason.
     *
     * @param eibAid the stated value, or {@code null} for {@link CicsAid#DFHENTER}
     * @return the raw attention-identifier byte
     * @throws IllegalArgumentException if {@code eibAid} is outside {@code 0}-{@code 255}
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
    // 0000-MAIN - app/cbl/COCRDSLC.cbl:248-392.
    // =================================================================================================

    /**
     * {@code 0000-MAIN} - the whole transaction, in the source's order.
     *
     * <p>This is the seam the parity tests drive: no HTTP, no Spring, no {@code MockMvc}. Instantiate the
     * controller with a mocked {@link CardRepository} and a fixed {@link Clock} and call this (gate G51,
     * practice B10).
     *
     * <p>The ten steps, each keyed to its line:
     * <ol>
     *   <li>{@code :250-252} {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} - the {@code try} below.
     *       Note that this program uses {@code HANDLE ABEND} and <strong>not</strong>
     *       {@code CALL 'CEE3ABD'}: none of the estate's nine {@code CEE3ABD} sites is in this package, so
     *       {@link AbendException} is raised by {@link #abendRoutine} as the last act of the handler
     *       rather than used as the primary error vehicle.</li>
     *   <li>{@code :254-256} {@code INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA} - see
     *       {@link #initializeStorage}. Spaces and zeros, never {@code null}.</li>
     *   <li>{@code :260} {@code MOVE LIT-THISTRANID TO WS-TRANID}.</li>
     *   <li>{@code :264} {@code SET WS-RETURN-MSG-OFF TO TRUE} - seventy-five spaces.</li>
     *   <li>{@code :268-279} the commarea restore - {@link #restoreCommarea}.</li>
     *   <li>{@code :284-285} {@code PERFORM YYYY-STORE-PFKEY} - {@link #storePfKeyYYYY}.</li>
     *   <li>{@code :291-299} the invalid-AID coercion - {@link #coerceInvalidAid}.</li>
     *   <li>{@code :304-381} the five-arm {@code EVALUATE TRUE}, in source order (gate G30).</li>
     *   <li>{@code :386-391} the trailing {@code IF INPUT-ERROR} guard, reached only when no arm
     *       transferred control.</li>
     *   <li>{@code :394-406} {@code COMMON-RETURN}.</li>
     * </ol>
     *
     * @param request  the fifteen {@code xxxI} items plus the carried commarea; must not be {@code null}
     * @param eibcalen {@code EIBCALEN}, the length of the passed commarea
     * @param eibAid   {@code EIBAID}, the raw attention identifier byte
     * @return the painted screen; never {@code null}
     * @throws NullPointerException if {@code request} is {@code null}
     * @throws AbendException       if the {@code HANDLE ABEND} handler runs to its
     *                              {@code EXEC CICS ABEND ABCODE('9999')}
     */
    CardSelectResponse handle(CardSelectRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COCRDSLC is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");

        CardSelectResponse response = new CardSelectResponse();
        Conversation task = new Conversation();

        // :250-252 - EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE). Every abend from here on is routed to
        // the handler, exactly as the CICS declarative did, and the handler has the last word.
        try {
            main0000(request, response, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            // The handler has already run - :871-873 does EXEC CICS HANDLE ABEND CANCEL before abending,
            // so an abend raised BY the handler is not re-handled. Rethrown unchanged.
            throw alreadyAbending;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, response, abend);
        }
        return response;
    }

    /**
     * The body of {@code 0000-MAIN}, with the {@code HANDLE ABEND} declarative already in force.
     *
     * <p>Separate from {@link #handle} so the {@code try} block contains one statement and the ten steps
     * read in a column. Package-visible so a test can drive the flow with the handler bypassed and see the
     * raw failure.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     */
    void main0000(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen, byte eibAid) {

        // :254-256 - INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA
        initializeStorage(request, task);

        // :260 - MOVE LIT-THISTRANID TO WS-TRANID
        task.wsTranid = codec.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);

        // :264 - SET WS-RETURN-MSG-OFF TO TRUE. SPACES, not LOW-VALUES; see the class documentation.
        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        // :268-279 - restore the passed commarea, or discard it on a fresh entry from the menu
        restoreCommarea(task, eibcalen);

        // :284-285 - PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT  (COPY 'CSSTRPFY' at :855)
        storePfKeyYYYY(task, eibAid);

        // :291-299 - only ENTER and PF3 are valid here, and anything else becomes ENTER
        coerceInvalidAid(task);

        // :304-381 - EVALUATE TRUE. First match wins, and the order is the source's (gate G30).
        dispatch0000(request, response, task, eibcalen);

        // :386-391 and :392 - the trailing guard, then the fall-through into COMMON-RETURN.
        //
        // Both are DEFENSIVE DEAD CODE IN THE SOURCE, and it is worth being precise about why rather
        // than leaving a reader to wonder: every one of the five EVALUATE arms terminates the task.
        // The PF3 arm issues EXEC CICS XCTL (:331), which transfers control and does not come back;
        // the three data arms each end in GO TO COMMON-RETURN (:348, :356, :363/:369); and WHEN OTHER
        // performs SEND-PLAIN-TEXT, which ends in EXEC CICS RETURN (:846). So :386 cannot be reached
        // on any path, which is exactly what the source's own comment - "If we had an error setup
        // error message that slipped through" - anticipates.
        //
        // Translated regardless (practice B5): a guard that is unreachable today is still behaviour,
        // and deleting it would be a change. trailingInputErrorGuard is package-visible so its own
        // branch is proven by a direct test rather than left unexercised.
        if (!task.returned) {
            trailingInputErrorGuard(request, response, task, eibcalen);
        }
        if (!task.returned) {
            commonReturn(response, task);
        }
    }

    /**
     * The trailing guard - {@code app/cbl/COCRDSLC.cbl:386-391}:
     *
     * <pre>
     * * If we had an error setup error message that slipped through
     * * Display and return
     *   IF INPUT-ERROR
     *      MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
     *      PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP-EXIT
     *      GO TO COMMON-RETURN
     *   END-IF
     * </pre>
     *
     * <p>Unreachable from {@link #main0000}, because every {@code EVALUATE} arm terminates the task - see
     * the note at the call site. It is translated because practice <strong>B5</strong> preserves dead code,
     * and it is package-visible so a test can drive both sides of its {@code IF} and prove the translation
     * is faithful, rather than leaving an unexercised method in the file.
     *
     * <p>Note the redundancy the source contains and this method keeps: {@code MOVE WS-RETURN-MSG TO
     * CCARD-ERROR-MSG} is performed here <em>and</em> again as the first statement of
     * {@link #commonReturn}. Two identical assignments three statements apart, and both are made.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}
     */
    void trailingInputErrorGuard(CardSelectRequest request, CardSelectResponse response,
            Conversation task, int eibcalen) {
        if (!task.inputError()) {
            // :391 - END-IF, and control falls into COMMON-RETURN
            return;
        }
        // :387 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));
        // :388-389 - PERFORM 1000-SEND-MAP THRU 1000-SEND-MAP-EXIT
        sendMap1000(request, response, task, eibcalen);
        // :390 - GO TO COMMON-RETURN
        commonReturn(response, task);
    }

    /**
     * {@code INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA} -
     * {@code app/cbl/COCRDSLC.cbl:254-256}.
     *
     * <p>A COBOL {@code INITIALIZE} without {@code REPLACING} sets every alphanumeric item in the named
     * groups to <strong>spaces</strong> and every numeric item to <strong>zeros</strong>, and it
     * <em>ignores</em> {@code VALUE} clauses. Two consequences are relied on downstream and would be lost
     * by initialising to Java defaults:
     *
     * <ul>
     *   <li>{@code WS-INPUT-FLAG} becomes a space, which satisfies <em>none</em> of {@code INPUT-OK},
     *       {@code INPUT-ERROR} or {@code INPUT-PENDING}. The flag is genuinely in none of its three
     *       declared states until an edit runs.</li>
     *   <li>{@code WS-EDIT-ACCT-FLAG} and {@code WS-EDIT-CARD-FLAG} become spaces, which
     *       <em>does</em> satisfy {@code FLG-ACCTFILTER-BLANK} and {@code FLG-CARDFILTER-BLANK}. So both
     *       filters read as blank on entry and {@link #setupScreenAttrs1300}'s cursor {@code EVALUATE}
     *       takes its first arm.</li>
     * </ul>
     *
     * <p>{@code WS-CARD-RID-ACCT-ID} is {@code PIC 9(11)}, so it initialises to eleven <em>zeros</em>
     * rather than spaces - and since the only statement that would ever have assigned it is commented out
     * at {@code :739}, eleven zeros is the value {@link #getCardByAcct9150} actually reads.
     *
     * <p>The three groups {@code INITIALIZE} names do <strong>not</strong> include
     * {@code CARDDEMO-COMMAREA} or {@code WS-THIS-PROGCOMMAREA}; those are handled separately at
     * {@code :271-272}, and only on one arm. {@link #restoreCommarea} owns that distinction.
     *
     * @param request the terminal input area, whose carried work area seeds {@code CC-WORK-AREA}
     * @param task    this task's storage, filled in place
     */
    void initializeStorage(CardSelectRequest request, Conversation task) {
        // INITIALIZE CC-WORK-AREA (app/cpy/CVCRD01Y.cpy) - all nine items to spaces at declared widths.
        task.ccWorkArea = new CardScreenState();
        task.ccWorkArea.initializeWorkArea();

        // INITIALIZE WS-MISC-STORAGE - :36-158.
        task.wsRespCd = 0;
        task.wsReasCd = 0;
        task.wsTranid = CardScreenState.spaces(WS_TRANID_LENGTH);
        task.wsInputFlag = FLG_FILTER_BLANK;
        task.wsEditAcctFlag = FLG_FILTER_BLANK;
        task.wsEditCardFlag = FLG_FILTER_BLANK;
        task.wsReturnFlag = FLG_FILTER_BLANK;
        task.wsPfkFlag = FLG_FILTER_BLANK;
        task.cardAcctIdX = CardScreenState.spaces(CardScreenState.CC_ACCT_ID_LENGTH);
        task.cardCvvCdX = CardScreenState.spaces(CardRecord.CARD_CVV_CD_LENGTH);
        task.cardCardNumX = CardScreenState.spaces(CardScreenState.CC_CARD_NUM_LENGTH);
        task.cardNameEmbossedX = CardScreenState.spaces(CardRecord.CARD_EMBOSSED_NAME_LENGTH);
        task.cardStatusX = CardScreenState.spaces(CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        task.cardExpiraionDateX = CardScreenState.spaces(CardRecord.CARD_EXPIRAION_DATE_LENGTH);
        task.wsCardRidCardnum = CardScreenState.spaces(WS_CARD_RID_CARDNUM_LENGTH);
        // PIC 9(11): zeros, not spaces.
        task.wsCardRidAcctId = codec.movePic9(0L, WS_CARD_RID_ACCT_ID_LENGTH);
        task.errorOpname = CardScreenState.spaces(ERROR_OPNAME_LENGTH);
        task.errorFile = CardScreenState.spaces(ERROR_FILE_LENGTH);
        task.errorResp = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.errorResp2 = CardScreenState.spaces(ERROR_RESP_LENGTH);
        task.wsLongMsg = CardScreenState.spaces(WS_LONG_MSG_LENGTH);
        task.wsInfoMsg = WS_INFO_MSG_SPACES;
        task.wsReturnMsg = WS_RETURN_MSG_OFF;
        task.cardRecord = Optional.empty();

        // INITIALIZE WS-COMMAREA - PIC X(2000), so two thousand spaces.
        task.wsCommarea = CardScreenState.spaces(WS_COMMAREA_LENGTH);

        // ABEND-DATA (app/cpy/CSMSG02Y.cpy at :224) is not named by the INITIALIZE, but the copybook
        // declares VALUE SPACES throughout, so spaces is its state until an arm writes to it.
        task.abendData = SystemMessages.AbendData.spaces();

        // The two declared-only copybook areas, at their freshly allocated state. Neither is named by the
        // INITIALIZE and neither is ever read, so both simply exist - which is the whole point: a COPY
        // allocates storage whether or not a statement touches it (practice B5). See the field
        // documentation on Conversation for why CSUSR01Y and CVCUS01Y are copied here while CVACT01Y and
        // CVACT03Y beside them are commented out.
        task.secUserData = SecUserRecord.blank();
        task.customerRecord = new CustomerRecord();

        // The commarea and this program's own trailer start where the request left them; :268-279 decides
        // whether they are kept or discarded. CC-WORK-AREA has just been INITIALIZEd above, which is why
        // the request's own work area is NOT copied in: the COBOL discards it too.
        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();
        // The trailer arrives with the request, exactly as the commarea does. It used to be initialised
        // here unconditionally, which made :276-278 restore twelve spaces however many bytes the caller
        // had actually passed - the pass-through at :398-400 then returned spaces too, and the next
        // program in the chain lost the calling program and transaction it should have received.
        task.thisProgCommarea = request.getThisProgCommarea();
    }

    /**
     * The commarea restore - {@code app/cbl/COCRDSLC.cbl:268-279}:
     *
     * <pre>
     * IF EIBCALEN IS EQUAL TO 0
     *    OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)
     *    INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA
     * ELSE
     *    MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA) TO CARDDEMO-COMMAREA
     *    MOVE DFHCOMMAREA (LENGTH OF CARDDEMO-COMMAREA + 1:
     *                      LENGTH OF WS-THIS-PROGCOMMAREA) TO WS-THIS-PROGCOMMAREA
     * END-IF
     * </pre>
     *
     * <p>The compound condition and its {@code NOT} are load-bearing. A <em>fresh</em> arrival from the
     * main menu - {@code CDEMO-FROM-PROGRAM} is {@value #LIT_MENUPGM} and the context is
     * {@code ENTER} - discards whatever the menu passed and starts clean, while a <em>re-entry</em> from
     * the menu keeps it. Dropping the {@code NOT}, or reading the {@code OR} as an {@code AND}, silently
     * loses the operator's typed criteria on every re-entry.
     *
     * <p>The {@code ELSE} arm is a pair of reference modifications, and COBOL reference modification is
     * <strong>1-based</strong>: {@code (1:160)} is the first 160 bytes, and {@code (161:12)} starts at
     * 0-based offset 160. They are reproduced as an actual byte split rather than as two assignments, so
     * the offsets are exercised rather than assumed - if either were wrong, every field after it would
     * shift and the split would be visibly wrong rather than subtly so.
     *
     * @param task     this task's storage; its commarea and trailer are replaced in place
     * @param eibcalen {@code EIBCALEN}
     */
    void restoreCommarea(Conversation task, int eibcalen) {
        boolean noCommareaPassed = eibcalen == NO_COMMAREA_LENGTH;
        boolean freshEntryFromMenu = LIT_MENUPGM.equals(
                codec.movePicX(task.carddemoCommarea.fromProgram(),
                        NavigationContext.FROM_PROGRAM_LENGTH))
                && !task.carddemoCommarea.isReenter();

        if (noCommareaPassed || freshEntryFromMenu) {
            // :271-272 - INITIALIZE CARDDEMO-COMMAREA, WS-THIS-PROGCOMMAREA
            task.carddemoCommarea = NavigationContext.empty();
            task.thisProgCommarea = ThisProgCommarea.initialized();
            return;
        }

        // The 172-byte DFHCOMMAREA as the caller passed it: CARDDEMO-COMMAREA then
        // WS-THIS-PROGCOMMAREA, contiguous.
        byte[] dfhcommarea = new byte[PASSED_COMMAREA_LENGTH];
        byte[] passedCommarea = task.carddemoCommarea.toFixedWidth(codec);
        System.arraycopy(passedCommarea, 0, dfhcommarea, 0, NavigationContext.COMMAREA_LENGTH);
        byte[] passedTrailer =
                codec.encodeImage(task.thisProgCommarea.toImage(codec), "WS-THIS-PROGCOMMAREA");
        System.arraycopy(passedTrailer, 0, dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                THIS_PROGCOMMAREA_LENGTH);

        // :274-275 - DFHCOMMAREA(1:160), which is 0-based [0, 160)
        task.carddemoCommarea = NavigationContext.fromFixedWidth(codec,
                Arrays.copyOfRange(dfhcommarea, 0, NavigationContext.COMMAREA_LENGTH));

        // :276-278 - DFHCOMMAREA(161:12), which is 0-based [160, 172)
        task.thisProgCommarea = ThisProgCommarea.fromImage(codec.decodeImage(
                Arrays.copyOfRange(dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                        PASSED_COMMAREA_LENGTH),
                "WS-THIS-PROGCOMMAREA"));
    }

    /**
     * {@code PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT} -
     * {@code app/cbl/COCRDSLC.cbl:284-285}, the paragraph {@code COPY 'CSSTRPFY'} brings in at
     * {@code :855}.
     *
     * <p>Delegated wholesale to {@link PfKeyResolver#storePfKey(byte, Optional)}, which is used
     * <strong>statically</strong>: {@code PfKeyResolver} is a final class with a private constructor and a
     * static-only API, so there is no instance to constructor-inject. The migration brief asks for
     * injection; it is not possible, and wrapping the class purely to satisfy the wording would introduce
     * a type the plan does not name. The divergence is recorded here (practice B4).
     *
     * <p>The property that matters is the <strong>no-match</strong> outcome. The copybook's
     * {@code EVALUATE} has no {@code WHEN OTHER} and no {@code DFHPA3} branch, and it does not clear
     * {@code CCARD-AID} first, so an unrecognised {@code EIBAID} leaves the field holding whatever was
     * there before. Here that is five spaces, because {@code INITIALIZE CC-WORK-AREA} has just run - and
     * five spaces satisfies neither {@code CCARD-AID-ENTER} nor {@code CCARD-AID-PFK03}, so
     * {@link #coerceInvalidAid} then coerces it to {@code ENTER}. That two-step is the source's, and it is
     * why an unknown key redisplays the screen instead of failing.
     *
     * @param task   this task's storage; {@code CC-WORK-AREA}'s {@code CCARD-AID} is updated in place
     * @param eibAid {@code EIBAID}, the raw attention identifier byte
     */
    void storePfKeyYYYY(Conversation task, byte eibAid) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(eibAid, task.ccWorkArea.aidKey());
        // ifPresent, never orElse: on no match nothing is stored, which is exactly what the copybook's
        // missing WHEN OTHER does.
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    /**
     * The invalid-AID coercion - {@code app/cbl/COCRDSLC.cbl:291-299}:
     *
     * <pre>
     * SET PFK-INVALID TO TRUE
     * IF CCARD-AID-ENTER OR CCARD-AID-PFK03
     *    SET PFK-VALID TO TRUE
     * END-IF
     * IF PFK-INVALID
     *    SET CCARD-AID-ENTER TO TRUE
     * END-IF
     * </pre>
     *
     * <p><strong>An unrecognised or unwanted key is silently rewritten to {@code ENTER}. It is never
     * rejected.</strong> Only {@code ENTER} and {@code PF3} are valid on this screen; press {@code PF12},
     * {@code CLEAR}, {@code PA1} or an unmapped byte and the screen is simply painted again. This is the
     * least obvious behaviour in the program and the easiest to "improve": answering {@code 400}, or
     * emitting {@link SystemMessages#CCDA_MSG_INVALID_KEY} as sibling screens do, would both be new
     * behaviour. {@code COCRDSLC} never references that message.
     *
     * <p>Note that {@code WS-PFK-FLAG} is <em>left</em> at {@code PFK-INVALID} in the coerced case - the
     * second {@code IF} rewrites the AID, not the flag. Nothing later reads the flag, but the state is
     * observable and is reproduced rather than tidied.
     *
     * @param task this task's storage; {@code WS-PFK-FLAG} and possibly {@code CCARD-AID} are updated
     */
    void coerceInvalidAid(Conversation task) {
        // :291 - SET PFK-INVALID TO TRUE. Invalid until proven otherwise.
        task.wsPfkFlag = PFK_INVALID;

        // :292-295 - the only two keys this screen accepts
        if (task.ccWorkArea.isCcardAidEnter() || task.ccWorkArea.isCcardAidPfk03()) {
            task.wsPfkFlag = PFK_VALID;
        }

        // :297-299 - anything else becomes ENTER
        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    /**
     * {@code EVALUATE TRUE} - {@code app/cbl/COCRDSLC.cbl:304-381}, five arms, first match wins.
     *
     * <p><strong>The order is behaviour, not style (gate G30).</strong> Two of the arms test
     * {@code CDEMO-PGM-ENTER}: {@code :339-340} qualifies it with
     * {@code AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM}, and the bare {@code :349} follows it. Only
     * because the qualified arm is tested first does the bare arm mean "arrived from somewhere other than
     * the card list". Swapping them would send every card-list arrival down the empty-screen path and lose
     * the criteria the list had already validated - a defect that would show up as "the detail screen is
     * blank", far from its cause.
     *
     * <p>Written as an ordered {@code if}/{@code else if} chain rather than a {@code switch}, because
     * {@code EVALUATE TRUE} tests independent conditions in sequence and that is exactly what an
     * {@code else if} chain does. {@code GO TO COMMON-RETURN} becomes "run {@code COMMON-RETURN}, mark the
     * task returned, return" (rule R7), which preserves both the outcome and the skipping of the trailing
     * guard at {@code :386}.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}, which {@link #setupScreenVars1200} tests again at {@code :459}
     */
    void dispatch0000(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen) {

        // ---- :305-334  WHEN CCARD-AID-PFK03 - XCTL to the caller, or to the main menu -------------
        if (task.ccWorkArea.isCcardAidPfk03()) {
            backNavigationPfk03(response, task);
            return;
        }

        // ---- :339-348  WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM ------------
        //      Arrived from the card list, which has already validated the selection criteria.
        if (task.carddemoCommarea.isEnter()
                && LIT_CCLISTPGM.equals(codec.movePicX(task.carddemoCommarea.fromProgram(),
                        NavigationContext.FROM_PROGRAM_LENGTH))) {
            enterFromCardList(request, response, task, eibcalen);
            return;
        }

        // ---- :349-356  WHEN CDEMO-PGM-ENTER (bare) -----------------------------------------------
        //      Arrived from some other context, so the criteria are still to be gathered. Reachable ONLY
        //      because the more specific arm above was tested first.
        if (task.carddemoCommarea.isEnter()) {
            sendMap1000(request, response, task, eibcalen);
            commonReturn(response, task);
            return;
        }

        // ---- :357-371  WHEN CDEMO-PGM-REENTER ----------------------------------------------------
        if (task.carddemoCommarea.isReenter()) {
            reenterProcessInputs(request, response, task, eibcalen);
            return;
        }

        // ---- :373-380  WHEN OTHER ----------------------------------------------------------------
        unexpectedDataScenario(response, task);
    }

    /**
     * {@code WHEN CCARD-AID-PFK03} - {@code app/cbl/COCRDSLC.cbl:305-334}: transfer control back to
     * whoever called, or to the main menu when nobody did.
     *
     * <p>Both "did anybody call?" tests compare against <strong>two distinct byte patterns</strong>:
     *
     * <pre>
     * IF CDEMO-FROM-TRANID EQUAL LOW-VALUES OR CDEMO-FROM-TRANID EQUAL SPACES
     * IF CDEMO-FROM-PROGRAM EQUAL LOW-VALUES OR CDEMO-FROM-PROGRAM EQUAL SPACES
     * </pre>
     *
     * <p>{@code LOW-VALUES} is binary zero and {@code SPACES} is {@code 0x20}; they are different values
     * and both mean "empty" here. Testing only one would send a caller that used the other pattern to its
     * own transaction instead of to the menu. {@link #isLowValuesOrSpaces} does both.
     *
     * <p>{@code SET CDEMO-USRTYP-USER TO TRUE} at {@code :326} is <strong>unconditional</strong>: whoever
     * presses {@code PF3} leaves this screen recorded as a regular user, even if they arrived as an
     * administrator. That is the source's behaviour. No authorisation check is added, because the COBOL
     * performs none, and no Spring Security is introduced - practice <strong>B6</strong> forbids both
     * weakening and unrequestedly strengthening the posture.
     *
     * <p>{@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)} at {@code :331-334}
     * becomes {@link CardSelectResponse#setNextProgram(String)} (gate G40): the server forwards nothing and
     * the client issues the follow-up call, so no session affinity is created. The map is not sent on this
     * arm - the COBOL transfers control instead - so the fifteen items stay as
     * {@code MOVE LOW-VALUES TO CCRDSLAO} left them.
     *
     * @param response the response, whose {@code nextProgram} carries the transfer target
     * @param task     this task's storage
     */
    void backNavigationPfk03(CardSelectResponse response, Conversation task) {
        NavigationContext commarea = task.carddemoCommarea;

        // :309-314 - the target transaction
        String toTranid = isLowValuesOrSpaces(commarea.fromTranid(),
                NavigationContext.FROM_TRANID_LENGTH)
                        ? LIT_MENUTRANID
                        : commarea.fromTranid();

        // :316-321 - the target program
        String toProgram = isLowValuesOrSpaces(commarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH)
                        ? LIT_MENUPGM
                        : commarea.fromProgram();

        commarea = commarea
                .withToTranid(codec.movePicX(toTranid, NavigationContext.TO_TRANID_LENGTH))
                .withToProgram(codec.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH))
                // :323-324 - and record that this screen is now the caller
                .withFromTranid(codec.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH))
                .withFromProgram(codec.movePicX(LIT_THISPGM, NavigationContext.FROM_PROGRAM_LENGTH))
                // :326 - SET CDEMO-USRTYP-USER TO TRUE, unconditionally (practice B6)
                .withUserTypeUser()
                // :327 - SET CDEMO-PGM-ENTER TO TRUE
                .withPgmEnter()
                // :328-329 - LIT-THISMAPSET is X(8) and CDEMO-LAST-MAPSET is X(7), so the MOVE drops the
                // literal's trailing space. LIT-THISMAP is already X(7).
                .withLastMapset(codec.movePicX(LIT_THISMAPSET, NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(codec.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));

        task.carddemoCommarea = commarea;

        // :331-334 - EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        //
        // The XCTL passes CARDDEMO-COMMAREA and NOTHING ELSE - not WS-THIS-PROGCOMMAREA, which
        // COMMON-RETURN appends at :398-400 but this arm never touches. So the trailer is deliberately
        // NOT published here: the program being transferred to receives 160 bytes, and adding the twelve
        // would hand it state the source does not pass. It stays at its initialised twelve spaces.
        response.setNavigationContext(commarea);
        response.setCardScreenState(task.ccWorkArea);
        response.setNextProgram(commarea.toProgram());
        task.returned = true;
    }

    /**
     * Whether an alphanumeric field is empty in either of the two senses the COBOL tests:
     * {@code EQUAL LOW-VALUES} or {@code EQUAL SPACES} - {@code app/cbl/COCRDSLC.cbl:309-310} and
     * {@code :316-317}.
     *
     * <p>The comparison is made at the field's <em>declared</em> width, because that is what COBOL
     * compares: a shorter value is padded before the test. A Java {@code isBlank()} would answer true for
     * both patterns and also for a tab, and an {@code isEmpty()} would answer false for both - neither is
     * the COBOL's test.
     *
     * @param value  the field's value
     * @param length the field's declared width
     * @return {@code true} when every character is a space, or every character is {@code U+0000}
     */
    static boolean isLowValuesOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return CardScreenState.spaces(length).equals(image)
                || CardScreenState.lowValues(length).equals(image);
    }

    /**
     * {@code WHEN CDEMO-PGM-ENTER AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM} -
     * {@code app/cbl/COCRDSLC.cbl:339-348}: the operator picked a card on the list screen, so the criteria
     * are already valid and the record can be read straight away.
     *
     * <p>Both targets of the two {@code MOVE}s are the <strong>numeric {@code REDEFINES} views</strong> of
     * {@code CVCRD01Y}'s alphanumeric fields - {@code CC-ACCT-ID-N PIC 9(11)} over
     * {@code CC-ACCT-ID PIC X(11)}, and {@code CC-CARD-NUM-N PIC 9(16)} over
     * {@code CC-CARD-NUM PIC X(16)} (gate G34). Writing through the numeric view zero-fills on the
     * <em>left</em>, so account 42 becomes {@code "00000000042"} and is then visible through the
     * alphanumeric view as those same eleven characters. Writing through the X view instead would produce
     * {@code "42         "} and the subsequent keyed read would miss.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}
     */
    void enterFromCardList(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen) {
        // :341 - SET INPUT-OK TO TRUE
        task.wsInputFlag = INPUT_OK;

        // :342 - MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N   (the PIC 9(11) view)
        task.ccWorkArea.setCcAcctIdN(task.carddemoCommarea.acctId());

        // :343 - MOVE CDEMO-CARD-NUM TO CC-CARD-NUM-N (the PIC 9(16) view)
        task.ccWorkArea.setCcCardNumN(task.carddemoCommarea.cardNum());

        // :344-345 - PERFORM 9000-READ-DATA
        readData9000(task);

        // :346-347 - PERFORM 1000-SEND-MAP
        sendMap1000(request, response, task, eibcalen);

        // :348 - GO TO COMMON-RETURN
        commonReturn(response, task);
    }

    /**
     * {@code WHEN CDEMO-PGM-REENTER} - {@code app/cbl/COCRDSLC.cbl:357-371}: the operator typed something,
     * so validate it and then either redisplay with a message or read the record.
     *
     * <pre>
     * PERFORM 2000-PROCESS-INPUTS
     * IF INPUT-ERROR
     *    PERFORM 1000-SEND-MAP
     *    GO TO COMMON-RETURN
     * ELSE
     *    PERFORM 9000-READ-DATA
     *    PERFORM 1000-SEND-MAP
     *    GO TO COMMON-RETURN
     * END-IF
     * </pre>
     *
     * <p>Both arms send the map and both return, so the only difference is whether the file is touched.
     * That is worth being literal about: a rejected filter must not reach the repository, because a read
     * with a blank or non-numeric key would either miss or throw, and either would change the message the
     * operator sees.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}
     */
    void reenterProcessInputs(CardSelectRequest request, CardSelectResponse response,
            Conversation task, int eibcalen) {
        // :358-359 - PERFORM 2000-PROCESS-INPUTS
        processInputs2000(request, task);

        if (task.inputError()) {
            // :361-363
            sendMap1000(request, response, task, eibcalen);
            commonReturn(response, task);
            return;
        }
        // :365-369
        readData9000(task);
        sendMap1000(request, response, task, eibcalen);
        commonReturn(response, task);
    }

    /**
     * {@code WHEN OTHER} - {@code app/cbl/COCRDSLC.cbl:373-380}: the context flag held neither
     * {@code ENTER} nor {@code REENTER}, which the program treats as a state that should not arise.
     *
     * <pre>
     * MOVE LIT-THISPGM TO ABEND-CULPRIT
     * MOVE '0001'      TO ABEND-CODE
     * MOVE SPACES      TO ABEND-REASON
     * MOVE 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG
     * PERFORM SEND-PLAIN-TEXT THRU SEND-PLAIN-TEXT-EXIT
     * </pre>
     *
     * <p><strong>The text goes to {@code WS-RETURN-MSG}, not to {@code ABEND-MSG}.</strong>
     * {@code COCRDUPC}'s equivalent arm moves it into {@code ABEND-MSG}; this program does not, and the
     * difference is real. So {@code ABEND-DATA} ends up carrying the culprit, the code {@code '0001'} and
     * a blank reason, while the operator-facing text sits in the return message - which is what
     * {@code SEND-PLAIN-TEXT} then transmits.
     *
     * <p>Note also what this arm does <em>not</em> do: it does not abend. {@code SEND-PLAIN-TEXT} ends in
     * a plain {@code EXEC CICS RETURN} ({@code :846-847}), so the transaction completes normally and the
     * endpoint answers {@code 200}. Raising a {@code 500} here would be a behaviour change.
     *
     * @param response the response, which carries the transmitted text on its error line
     * @param task     this task's storage
     */
    void unexpectedDataScenario(CardSelectResponse response, Conversation task) {
        // :374-376 - ABEND-DATA is filled, but ABEND-MSG is deliberately left as it was
        task.abendData = task.abendData
                .withAbendCulprit(LIT_THISPGM)
                .withAbendCode(UNEXPECTED_DATA_ABEND_CODE)
                .withAbendReason(CardScreenState.spaces(SystemMessages.ABEND_REASON_LENGTH));

        // :377-378 - MOVE 'UNEXPECTED DATA SCENARIO' TO WS-RETURN-MSG
        task.wsReturnMsg = UNEXPECTED_DATA_SCENARIO;

        // :379-380 - PERFORM SEND-PLAIN-TEXT THRU SEND-PLAIN-TEXT-EXIT
        sendPlainText(response, task);
    }

    // =================================================================================================
    // COMMON-RETURN - app/cbl/COCRDSLC.cbl:394-406.
    // =================================================================================================

    /**
     * {@code COMMON-RETURN} - {@code app/cbl/COCRDSLC.cbl:394-406}, the shared terminal paragraph that
     * every data arm reaches.
     *
     * <pre>
     * MOVE WS-RETURN-MSG     TO CCARD-ERROR-MSG
     * MOVE CARDDEMO-COMMAREA TO WS-COMMAREA
     * MOVE WS-THIS-PROGCOMMAREA TO
     *      WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)
     * EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA)
     *      LENGTH(LENGTH OF WS-COMMAREA)
     * </pre>
     *
     * <p>The first {@code MOVE} is <strong>redundant</strong> on the path through {@code :386-391}, which
     * has already made exactly the same assignment three statements earlier. Performing it twice is
     * harmless and it is what the source does, so it is performed twice here too rather than hoisted -
     * practice <strong>B5</strong> preserves odd code as well as dead code, and "harmless today" is not a
     * licence to restructure.
     *
     * <p>The second and third {@code MOVE}s assemble the returned commarea: {@code CARDDEMO-COMMAREA} into
     * the front of the 2000-byte {@code WS-COMMAREA}, then {@code WS-THIS-PROGCOMMAREA} at 1-based offset
     * 161. The remaining 1828 bytes stay as {@code INITIALIZE} left them - spaces - and the
     * {@code EXEC CICS RETURN} sends the whole 2000, not the 172 that were written. That length is
     * reproduced on {@link Conversation#wsCommarea} so a parity case can assert it.
     *
     * @param response the response, which carries the reassembled commarea back to the client
     * @param task     this task's storage
     */
    void commonReturn(CardSelectResponse response, Conversation task) {
        // :395 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG (redundant on one path; preserved - B5)
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        // :397-400 - assemble WS-COMMAREA: the commarea, then this program's trailer at offset 161
        String commareaImage = codec.decodeImage(task.carddemoCommarea.toFixedWidth(codec),
                "CARDDEMO-COMMAREA");
        String trailerImage = task.thisProgCommarea.toImage(codec);
        task.wsCommarea = codec.movePicX(commareaImage + trailerImage, WS_COMMAREA_LENGTH);

        // :402-406 - EXEC CICS RETURN TRANSID('CCDL') COMMAREA(WS-COMMAREA) LENGTH(2000)
        //
        // Both halves of the area travel, because both halves are what the RETURN passes: the 160-byte
        // CARDDEMO-COMMAREA and the 12-byte WS-THIS-PROGCOMMAREA composed at :397-400. The trailer used
        // to be assembled into task.wsCommarea above and then dropped, so the next turn received 160
        // bytes where the program had returned 172.
        response.setNavigationContext(task.carddemoCommarea);
        response.setThisProgCommarea(task.thisProgCommarea);
        response.setCardScreenState(task.ccWorkArea);
        task.returned = true;
    }

    // =================================================================================================
    // 1000-SEND-MAP and its four sub-paragraphs - app/cbl/COCRDSLC.cbl:412-580.
    // =================================================================================================

    /**
     * {@code 1000-SEND-MAP} - {@code app/cbl/COCRDSLC.cbl:412-421}: the four paragraphs that paint and
     * send the screen, in order.
     *
     * <p>Order matters between all four. {@code 1100} blanks the whole group, so it must precede
     * {@code 1200}, which writes data into it; {@code 1300} then sets attributes and may overwrite
     * {@code ACCTSIDO} with an asterisk, so it must follow {@code 1200}; and {@code 1400} names the map
     * and flips the context to {@code REENTER} last of all.
     *
     * @param request  the terminal input area, whose {@code xxxL} and {@code xxxA} items {@code 1300} sets
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}, which {@code 1200} tests at {@code :459}
     */
    void sendMap1000(CardSelectRequest request, CardSelectResponse response, Conversation task,
            int eibcalen) {
        screenInit1100(response, task);
        setupScreenVars1200(response, task, eibcalen);
        setupScreenAttrs1300(request, response, task);
        sendScreen1400(response, task);
    }

    /**
     * {@code 1100-SCREEN-INIT} - {@code app/cbl/COCRDSLC.cbl:427-451}: blank the group, then write the
     * four header fields and the date and time.
     *
     * <p>{@code MOVE LOW-VALUES TO CCRDSLAO} at {@code :428} is {@code LOW-VALUES}, not spaces - binary
     * zero means "no data, default attribute" to a 3270, and space-filling instead would put 168 spaces on
     * the wire the program never sent. {@link CardSelectResponse#initializeGroup()} does exactly that.
     *
     * <p>{@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} appears <strong>twice</strong>, at
     * {@code :430} and again at {@code :437}, with the four header moves in between. The second read is
     * redundant - nothing between them consumes the first - and it is preserved (practice B5) rather than
     * hoisted, because "redundant" is a judgement about today's code and a second clock read is
     * observable in principle. Both reads take the same {@link Clock}, so both see the same instant, which
     * is what a real second-resolution {@code FUNCTION CURRENT-DATE} would almost always have produced too.
     *
     * <p>{@code :439-449} then reformats: {@code MOVE WS-CURDATE-YEAR(3:2) TO WS-CURDATE-YY} takes the
     * last two digits of the year via a 1-based reference modification, producing {@code mm/dd/yy}, and the
     * time becomes {@code hh:mm:ss}. Both compositions belong to {@link DateHeader}, so they are not
     * restated here - there is one place in the system where the header's bytes are formed.
     *
     * @param response the map area being painted
     * @param task     this task's storage; {@code dateHeader} is filled
     */
    void screenInit1100(CardSelectResponse response, Conversation task) {
        // :428 - MOVE LOW-VALUES TO CCRDSLAO
        response.initializeGroup();

        // :430 - MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
        task.dateHeader = DateHeader.from(codec, clock);

        // :432-433 - MOVE CCDA-TITLE01/CCDA-TITLE02 TO TITLE01O/TITLE02O
        response.applyScreenTitles();

        // :434-435 - MOVE LIT-THISTRANID TO TRNNAMEO, MOVE LIT-THISPGM TO PGMNAMEO
        response.applyScreenIdentity();

        // :437 - MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, a second time (preserved - B5)
        task.dateHeader = DateHeader.from(codec, clock);

        // :439-449 - WS-CURDATE-MM-DD-YY TO CURDATEO, WS-CURTIME-HH-MM-SS TO CURTIMEO
        response.applyDateHeader(task.dateHeader);
    }

    /**
     * {@code 1200-SETUP-SCREEN-VARS} - {@code app/cbl/COCRDSLC.cbl:457-497}: project the search criteria
     * and, if a record was read, the card itself onto the map.
     *
     * <p>Three things about this paragraph are worth stating because each is a plausible place to go wrong.
     *
     * <p><strong>The outer test is {@code EIBCALEN}, not the context flag.</strong> {@code :459} branches
     * on {@code IF EIBCALEN = 0}: with no commarea at all the screen shows only the prompt, and
     * <em>none</em> of the criteria or card fields is written. So {@code ACCTSIDO} and {@code CARDSIDO}
     * stay {@code LOW-VALUES} on a cold start, which is not the same as showing them blank.
     *
     * <p><strong>The emptiness tests read the commarea, but the values written come from the work
     * area.</strong> {@code :462} tests {@code IF CDEMO-ACCT-ID = 0} and {@code :465} moves
     * {@code CC-ACCT-ID}; likewise {@code :468} tests {@code CDEMO-CARD-NUM} and {@code :471} moves
     * {@code CC-CARD-NUM}. Those are different fields, and reading the tested one instead of the moved one
     * would work by accident on the card-list path - where {@code :342-343} has just copied one into the
     * other - and fail on the re-entry path, where the work area holds what the operator typed and the
     * commarea holds what the previous turn stored.
     *
     * <p><strong>The card projection is keyed on a message, not on a record.</strong> {@code :474} tests
     * {@code IF FOUND-CARDS-FOR-ACCOUNT}, a condition name on {@code WS-INFO-MSG}, which the read
     * paragraphs set. Testing "is a record present" instead would differ whenever a later statement
     * overwrote the message, so the indirection is kept.
     *
     * <p>The expiry split follows the {@code FILLER REDEFINES} at {@code :85-90}:
     * {@code CARD-EXPIRAION-DATE} is {@code PIC X(10)} in {@code YYYY-MM-DD} form, so the year is
     * characters 1-4 and the month is characters 6-7 - 0-based {@code [0,4)} and {@code [5,7)}. There is
     * <strong>no {@code EXPDAY} field</strong> on this map; only {@code COCRDUP} declares one, and one is
     * not added for symmetry (practice B5). The field's misspelled name is the copybook's
     * ({@code app/cpy/CVACT02Y.cpy:9}) and is carried verbatim.
     *
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}
     */
    void setupScreenVars1200(CardSelectResponse response, Conversation task, int eibcalen) {
        if (eibcalen == NO_COMMAREA_LENGTH) {
            // :460 - SET WS-PROMPT-FOR-INPUT TO TRUE, and nothing else is written
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        } else {
            // :462-466 - the account filter
            if (task.carddemoCommarea.acctId() == 0L) {
                response.setAcctsido(
                        CardScreenState.lowValues(CardSelectResponse.ACCTSIDO_LENGTH));
            } else {
                response.setAcctsido(codec.movePicX(task.ccWorkArea.getCcAcctId(),
                        CardSelectResponse.ACCTSIDO_LENGTH));
            }

            // :468-472 - the card filter
            if (task.carddemoCommarea.cardNum() == 0L) {
                response.setCardsido(
                        CardScreenState.lowValues(CardSelectResponse.CARDSIDO_LENGTH));
            } else {
                response.setCardsido(codec.movePicX(task.ccWorkArea.getCcCardNum(),
                        CardSelectResponse.CARDSIDO_LENGTH));
            }

            // :474-485 - the card itself, but only if a read reported one
            if (task.foundCardsForAccount()) {
                projectCardRecord1200(response, task);
            }
        }

        // :490-492 - IF WS-NO-INFO-MESSAGE SET WS-PROMPT-FOR-INPUT TO TRUE.
        // Runs on BOTH arms above, so a cold start sets the prompt twice - harmless, and preserved.
        if (task.noInfoMessage()) {
            task.wsInfoMsg = WS_PROMPT_FOR_INPUT;
        }

        // :494 - MOVE WS-RETURN-MSG TO ERRMSGO. 75 characters into an 80-character receiver, so five
        // spaces are padded on the right.
        response.setErrmsgo(codec.movePicX(task.wsReturnMsg, CardSelectResponse.ERRMSGO_LENGTH));

        // :496 - MOVE WS-INFO-MSG TO INFOMSGO. Both are 40, so this is a straight copy.
        response.setInfomsgo(codec.movePicX(task.wsInfoMsg, CardSelectResponse.INFOMSGO_LENGTH));
    }

    /**
     * The card projection inside {@code 1200-SETUP-SCREEN-VARS} - {@code app/cbl/COCRDSLC.cbl:474-485}.
     *
     * <p>Split out so the {@code IF FOUND-CARDS-FOR-ACCOUNT} arm is separately callable by a test, and so
     * the four target fields are visible in one place. If the flag is set but no record is present - which
     * the COBOL cannot distinguish, because a {@code CARD-RECORD} area always exists and holds whatever
     * the last read left - the fields are left as {@code 1100} blanked them. That is the closest Java can
     * come to reading an unfilled {@code WORKING-STORAGE} area without inventing a value.
     *
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void projectCardRecord1200(CardSelectResponse response, Conversation task) {
        if (task.cardRecord.isEmpty()) {
            return;
        }
        CardRecord card = task.cardRecord.get();

        // :475-476 - MOVE CARD-EMBOSSED-NAME TO CRDNAMEO. Both X(50).
        response.setCrdnameo(codec.movePicX(card.cardEmbossedName(),
                CardSelectResponse.CRDNAMEO_LENGTH));

        // :477-478 - MOVE CARD-EXPIRAION-DATE TO CARD-EXPIRAION-DATE-X, so the REDEFINES can split it
        task.cardExpiraionDateX =
                codec.movePicX(card.cardExpiraionDate(), CardRecord.CARD_EXPIRAION_DATE_LENGTH);

        // :480 - MOVE CARD-EXPIRY-MONTH TO EXPMONO. Characters 6-7 of YYYY-MM-DD, 0-based [5,7).
        response.setExpmono(codec.movePicX(card.cardExpiraionDateMonth(),
                CardSelectResponse.EXPMONO_LENGTH));

        // :482 - MOVE CARD-EXPIRY-YEAR TO EXPYEARO. Characters 1-4, 0-based [0,4).
        response.setExpyearo(codec.movePicX(card.cardExpiraionDateYear(),
                CardSelectResponse.EXPYEARO_LENGTH));

        // :484 - MOVE CARD-ACTIVE-STATUS TO CRDSTCDO. Both X(1).
        response.setCrdstcdo(codec.movePicX(card.cardActiveStatus(),
                CardSelectResponse.CRDSTCDO_LENGTH));
    }

    /**
     * {@code 1300-SETUP-SCREEN-ATTRS} - {@code app/cbl/COCRDSLC.cbl:502-558}: field protection, cursor
     * position and colour.
     *
     * <p>Note which group each move targets, because the paragraph writes to <em>both</em>:
     * {@code ACCTSIDA}, {@code CARDSIDA}, {@code ACCTSIDL} and {@code CARDSIDL} are qualified
     * {@code OF CCRDSLAI} - the <strong>input</strong> group, so they land on
     * {@link CardSelectRequest#metadata} - while {@code ACCTSIDC}, {@code CARDSIDC}, {@code INFOMSGC},
     * {@code ACCTSIDO} and {@code CARDSIDO} are qualified {@code OF CCRDSLAO} - the
     * <strong>output</strong> group, so they land on the response.
     *
     * <h3>This is not {@code CSSETATY}, and the difference is material (practice B4)</h3>
     *
     * <p>{@code COCRDSLC} does <strong>not</strong> copy {@code CSSETATY} - only {@code COACTUPC} does -
     * and its inline highlight logic at {@code :533-551} is a different shape from the copybook's:
     *
     * <table border="1">
     *   <caption>Where the two disagree</caption>
     *   <tr><th>flags</th><th>{@code CSSETATY}</th><th>{@code COCRDSLC} inline</th></tr>
     *   <tr><td>{@code NOT-OK}, not re-entry</td><td>nothing</td><td><strong>red</strong></td></tr>
     *   <tr><td>{@code NOT-OK}, re-entry</td><td>red</td><td>red</td></tr>
     *   <tr><td>{@code BLANK}, not re-entry</td><td>nothing</td><td>nothing</td></tr>
     *   <tr><td>{@code BLANK}, re-entry</td><td>red + {@code '*'}</td><td>red + {@code '*'}</td></tr>
     * </table>
     *
     * <p>So the {@code NOT-OK} colour is applied <em>unconditionally</em> here, with no
     * {@code CDEMO-PGM-REENTER} guard, and only the {@code BLANK} case carries the guard. This method
     * therefore uses {@link FieldAttributeSetter} for exactly the rows where the two agree - the
     * {@code BLANK} decision, which is CSSETATY's shape verbatim - and applies the unguarded
     * {@code NOT-OK} colour directly. Routing the {@code NOT-OK} case through the copybook's resolver
     * would silently stop colouring a rejected filter on first entry.
     *
     * <p>Gate <strong>G38</strong> is satisfied by the asterisk, which is the only effect that depends on
     * re-entry: on {@code ENTER} the field is left showing its value, on {@code REENTER} a blank field is
     * marked. Both paths are separately reachable and separately asserted.
     *
     * @param request  the terminal input area, whose {@code xxxL} and {@code xxxA} items are written here
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void setupScreenAttrs1300(CardSelectRequest request, CardSelectResponse response,
            Conversation task) {

        boolean arrivedFromCardList = LIT_CCLISTMAPSET.equals(codec.movePicX(
                task.carddemoCommarea.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH))
                && LIT_CCLISTPGM.equals(codec.movePicX(
                        task.carddemoCommarea.fromProgram(), NavigationContext.FROM_PROGRAM_LENGTH));

        // :504-512 - PROTECT OR UNPROTECT BASED ON CONTEXT.
        // DFHBMPRF is protected + FSET, so a criterion the list already chose cannot be retyped;
        // DFHBMFSE is unprotected + FSET, so it can.
        byte fieldAttribute = arrivedFromCardList ? BmsAttributes.DFHBMPRF : BmsAttributes.DFHBMFSE;
        request.metadata(CardSelectRequest.ScreenField.ACCTSID).setAttribute(fieldAttribute);
        request.metadata(CardSelectRequest.ScreenField.CARDSID).setAttribute(fieldAttribute);

        // :514-524 - POSITION CURSOR. An ordered EVALUATE TRUE whose first two WHENs share one action and
        // whose last two share another, so consecutive WHENs are an OR - not a fall-through.
        // The cursor is recorded on the RESPONSE as well as on the request's xxxL metadata, and both are
        // needed. The metadata carries the -1 the source moves; the response carries it out to the client,
        // because the request this method edits is the controller's own defensive copy and the caller's
        // instance never sees the move. The SEND MAP at :569-576 carries CURSOR, so where the -1 landed is
        // observable behaviour, and R6 requires it to travel in the payload rather than in server state.
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            // :516-518
            request.metadata(CardSelectRequest.ScreenField.ACCTSID).positionCursorHere();
            response.setCursorField(CardSelectRequest.ScreenField.ACCTSID.label());
        } else if (task.flgCardfilterNotOk() || task.flgCardfilterBlank()) {
            // :519-521
            request.metadata(CardSelectRequest.ScreenField.CARDSID).positionCursorHere();
            response.setCursorField(CardSelectRequest.ScreenField.CARDSID.label());
        } else {
            // :522-523 - WHEN OTHER. Same action as the first arm, and still a distinct arm.
            request.metadata(CardSelectRequest.ScreenField.ACCTSID).positionCursorHere();
            response.setCursorField(CardSelectRequest.ScreenField.ACCTSID.label());
        }

        // :526-531 - SETUP COLOR. Note there is no ELSE: arriving from anywhere else leaves both colour
        // items at whatever MOVE LOW-VALUES left, which is 0x00.
        if (arrivedFromCardList) {
            response.attributes(CardSelectResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHDFCOL);
            response.attributes(CardSelectResponse.ScreenField.CARDSID)
                    .setColour(BmsAttributes.DFHDFCOL);
        }

        // :533-535 - IF FLG-ACCTFILTER-NOT-OK -> DFHRED. UNGUARDED by re-entry; see the table above.
        if (task.flgAcctfilterNotOk()) {
            response.attributes(CardSelectResponse.ScreenField.ACCTSID)
                    .setColour(BmsAttributes.DFHRED);
        }

        // :537-539 - IF FLG-CARDFILTER-NOT-OK -> DFHRED. Also unguarded.
        if (task.flgCardfilterNotOk()) {
            response.attributes(CardSelectResponse.ScreenField.CARDSID)
                    .setColour(BmsAttributes.DFHRED);
        }

        boolean reenter = task.carddemoCommarea.isReenter();

        // :541-545 - IF FLG-ACCTFILTER-BLANK AND CDEMO-PGM-REENTER -> '*' and DFHRED.
        // This IS CSSETATY's shape, so the copybook's resolver decides it. The two moves are applied
        // colour-then-content rather than the source's content-then-colour; they target different items,
        // so the resulting state is identical either way.
        response.applyHighlight(CardSelectResponse.ScreenField.ACCTSID,
                FieldAttributeSetter.resolveFromFlags(false, task.flgAcctfilterBlank(), reenter,
                        CardSelectResponse.ScreenField.ACCTSID.dfhmdfLabel(),
                        CardSelectResponse.MAP_NAME));

        // :547-551 - IF FLG-CARDFILTER-BLANK AND CDEMO-PGM-REENTER -> '*' and DFHRED.
        response.applyHighlight(CardSelectResponse.ScreenField.CARDSID,
                FieldAttributeSetter.resolveFromFlags(false, task.flgCardfilterBlank(), reenter,
                        CardSelectResponse.ScreenField.CARDSID.dfhmdfLabel(),
                        CardSelectResponse.MAP_NAME));

        // :553-557 - the information line is dark when it has nothing to say, neutral when it has
        if (task.noInfoMessage()) {
            response.attributes(CardSelectResponse.ScreenField.INFOMSG)
                    .setColour(BmsAttributes.DFHBMDAR);
        } else {
            response.attributes(CardSelectResponse.ScreenField.INFOMSG)
                    .setColour(BmsAttributes.DFHNEUTR);
        }
    }

    /**
     * {@code 1400-SEND-SCREEN} - {@code app/cbl/COCRDSLC.cbl:563-577}.
     *
     * <pre>
     * MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
     * MOVE LIT-THISMAP    TO CCARD-NEXT-MAP
     * SET  CDEMO-PGM-REENTER TO TRUE
     * EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET) FROM(CCRDSLAO)
     *      CURSOR ERASE FREEKB RESP(WS-RESP-CD)
     * </pre>
     *
     * <p>The first {@code MOVE} loses a byte, and it is meant to: {@code LIT-THISMAPSET} is
     * {@code PIC X(8)} holding {@code 'COCRDSL '} while {@code CCARD-NEXT-MAPSET} is {@code PIC X(7)}, so
     * the {@code PIC X} rule keeps the leading seven characters and discards the trailing space. The
     * discarded byte is a space, so the effect is benign - but the truncation is real, it is applied
     * explicitly through the codec rather than left to a setter, and the {@code X(8)}/{@code X(7)}
     * mismatch is not "corrected" (practice B5).
     *
     * <p>{@code SET CDEMO-PGM-REENTER TO TRUE} is what makes the conversation work: the screen goes out
     * flagged so that the operator's reply arrives at the {@code WHEN CDEMO-PGM-REENTER} arm. Since the
     * flag travels in the payload rather than in server state, the client must send it back (rule R6).
     *
     * <p>{@code SEND MAP} names {@code CCARD-NEXT-MAP} and {@code CCARD-NEXT-MAPSET} - the work-area
     * fields just assigned - and <strong>not</strong> the literals. That indirection is preserved: the
     * response's next-screen triple is read from {@link CardScreenState}, so a caller that altered the work
     * area would change where the screen is sent, exactly as it would on the mainframe.
     *
     * <p>{@code RESP(WS-RESP-CD)} is captured and never tested, so the response code is recorded as
     * {@link FileStatus#NORMAL} and no branch depends on it.
     *
     * @param response the map area being sent
     * @param task     this task's storage
     */
    void sendScreen1400(CardSelectResponse response, Conversation task) {
        // :565 - X(8) into X(7): the trailing space is discarded
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));

        // :566 - X(7) into X(7)
        task.ccWorkArea.setCcardNextMap(
                codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));

        // :567 - SET CDEMO-PGM-REENTER TO TRUE
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        // :569-576 - EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET) FROM(CCRDSLAO)
        //
        // The whole next-screen triple is published, not two thirds of it. :588-590 assigns
        // CCARD-NEXT-PROG, CCARD-NEXT-MAPSET and CCARD-NEXT-MAP in three consecutive MOVEs, so a response
        // that carried the last two on its own members and left the first to be dug out of the work area
        // would split one COBOL group across two shapes - and a client reading nextProgram would see
        // nothing where the program had named itself. On the arms that never reach 2000-PROCESS-INPUTS the
        // work area still holds the spaces INITIALIZE CC-WORK-AREA left, which is what the member already
        // starts as, so nothing is invented for those paths either.
        response.setNextProgram(task.ccWorkArea.getCcardNextProg());
        response.setNextMapset(task.ccWorkArea.getCcardNextMapset());
        response.setNextMap(task.ccWorkArea.getCcardNextMap());
        response.setNavigationContext(task.carddemoCommarea);
        response.setThisProgCommarea(task.thisProgCommarea);
        response.setCardScreenState(task.ccWorkArea);
        task.wsRespCd = FileStatus.NORMAL;
    }

    // =================================================================================================
    // 2000-PROCESS-INPUTS and its sub-paragraphs - app/cbl/COCRDSLC.cbl:582-724.
    // =================================================================================================

    /**
     * {@code 2000-PROCESS-INPUTS} - {@code app/cbl/COCRDSLC.cbl:582-591}: receive the map, edit it, and
     * record where the next turn should go.
     *
     * <pre>
     * PERFORM 2100-RECEIVE-MAP
     * PERFORM 2200-EDIT-MAP-INPUTS
     * MOVE WS-RETURN-MSG  TO CCARD-ERROR-MSG
     * MOVE LIT-THISPGM    TO CCARD-NEXT-PROG
     * MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
     * MOVE LIT-THISMAP    TO CCARD-NEXT-MAP
     * </pre>
     *
     * <p>The four trailing moves run <strong>whether or not the edits found a problem</strong>, so this
     * screen always nominates itself as the next target. That is what makes a rejected filter redisplay
     * here rather than navigate away, and it is why the assignment is not made conditional.
     *
     * <p>{@code CCARD-NEXT-MAPSET} receives the same {@code X(8)}-into-{@code X(7)} truncation as in
     * {@link #sendScreen1400}, at {@code :589}.
     *
     * @param request the terminal input area
     * @param task    this task's storage
     */
    void processInputs2000(CardSelectRequest request, Conversation task) {
        // :583-584 - PERFORM 2100-RECEIVE-MAP
        receiveMap2100(task);

        // :585-586 - PERFORM 2200-EDIT-MAP-INPUTS
        editMapInputs2200(request, task);

        // :587 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        // :588-590 - this screen is its own next target
        task.ccWorkArea.setCcardNextProg(
                codec.movePicX(LIT_THISPGM, CardScreenState.CCARD_NEXT_PROG_LENGTH));
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(
                codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    /**
     * {@code 2100-RECEIVE-MAP} - {@code app/cbl/COCRDSLC.cbl:596-603}:
     * {@code EXEC CICS RECEIVE MAP(LIT-THISMAP) MAPSET(LIT-THISMAPSET) INTO(CCRDSLAI) RESP RESP2}.
     *
     * <p>There is no terminal to receive from here: the fifteen {@code xxxI} items <em>are</em> the request
     * payload, so the transfer that {@code RECEIVE MAP} performs has already happened by the time this
     * method runs. What survives translation is the response pair, which the statement captures and which
     * the program then ignores - it tests neither {@code WS-RESP-CD} nor {@code WS-REAS-CD} afterwards, and
     * has no {@code MAPFAIL} handling at all. Recording {@link FileStatus#NORMAL} is therefore the faithful
     * outcome, and the method exists so the paragraph is not silently missing from the translation.
     *
     * @param task this task's storage; the response pair is recorded
     */
    void receiveMap2100(Conversation task) {
        task.wsRespCd = FileStatus.NORMAL;
        task.wsReasCd = CardRepository.NO_REASON_CODE;
    }

    /**
     * {@code 2200-EDIT-MAP-INPUTS} - {@code app/cbl/COCRDSLC.cbl:608-641}: normalise the two typed fields,
     * edit each, then apply the cross-field rule.
     *
     * <p><strong>Three values mean "nothing typed", and all three must be accepted.</strong>
     * {@code :615-620} tests {@code IF ACCTSIDI = '*' OR ACCTSIDI = SPACES} and moves {@code LOW-VALUES}
     * into {@code CC-ACCT-ID} in either case; {@code :622-627} does the same for {@code CARDSIDI}. The
     * asterisk is there because {@link #setupScreenAttrs1300} <em>put</em> it there on the previous turn -
     * so the screen's own error marker must not be mistaken for input on the next one. Treating {@code '*'}
     * as a value would make the second submission of an empty field fail differently from the first.
     *
     * <p>The three optimistic {@code SET}s at {@code :610-612} run first and are then contradicted field by
     * field: each edit paragraph opens by setting its own flag to {@code NOT-OK} and only restores
     * {@code ISVALID} on success. Reversing that - validating and then setting - would leave the flag in
     * the wrong state on every early exit.
     *
     * <p>The cross-field rule at {@code :637-640} is <strong>unguarded</strong>: it has no
     * {@code IF WS-RETURN-MSG-OFF}, so when both filters are blank it <em>overwrites</em> whichever of
     * {@code WS-PROMPT-FOR-ACCT} or {@code WS-PROMPT-FOR-CARD} the edits left with the shorter
     * {@code 'No input received'}. That overwrite is the visible behaviour and is reproduced.
     *
     * @param request the terminal input area
     * @param task    this task's storage
     */
    void editMapInputs2200(CardSelectRequest request, Conversation task) {
        // :610-612 - optimistic, and contradicted below field by field
        task.wsInputFlag = INPUT_OK;
        task.wsEditCardFlag = FLG_FILTER_ISVALID;
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;

        // :614-620 - REPLACE * WITH LOW-VALUES, for the account filter
        String acctsidI = codec.movePicX(request.getAcctsid(), CardSelectRequest.ACCTSID_LENGTH);
        if (isAsteriskOrSpaces(acctsidI, CardSelectRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctIdToLowValues();
        } else {
            task.ccWorkArea.setCcAcctId(
                    codec.movePicX(acctsidI, CardScreenState.CC_ACCT_ID_LENGTH));
        }

        // :622-627 - and for the card filter
        String cardsidI = codec.movePicX(request.getCardsid(), CardSelectRequest.CARDSID_LENGTH);
        if (isAsteriskOrSpaces(cardsidI, CardSelectRequest.CARDSID_LENGTH)) {
            task.ccWorkArea.setCcCardNumToLowValues();
        } else {
            task.ccWorkArea.setCcCardNum(
                    codec.movePicX(cardsidI, CardScreenState.CC_CARD_NUM_LENGTH));
        }

        // :629-634 - INDIVIDUAL FIELD EDITS, account first
        editAccount2210(task);
        editCard2220(task);

        // :636-640 - CROSS FIELD EDITS. Unguarded, so it overwrites whatever the edits said.
        if (task.flgAcctfilterBlank() && task.flgCardfilterBlank()) {
            task.wsReturnMsg = NO_SEARCH_CRITERIA_RECEIVED;
        }
    }

    /**
     * Whether a typed field holds the error marker or nothing at all -
     * {@code IF ACCTSIDI OF CCRDSLAI = '*' OR ACCTSIDI OF CCRDSLAI = SPACES},
     * {@code app/cbl/COCRDSLC.cbl:615-616}.
     *
     * <p>The {@code '*'} comparison is against a <strong>space-padded</strong> asterisk, because that is
     * what COBOL compares: a one-character literal is padded to the field's width before the test, so an
     * asterisk followed by ten spaces matches and {@code "*1"} does not. Getting that wrong in the lenient
     * direction - {@code startsWith("*")} - would silently swallow a card number a user prefixed with an
     * asterisk.
     *
     * @param value  the field's value
     * @param length the field's declared width
     * @return {@code true} when the field holds a padded asterisk, or is entirely spaces
     */
    static boolean isAsteriskOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value, length);
        return PIC_X_CODEC.movePicX(FieldAttributeSetter.ASTERISK, length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    /**
     * {@code 2210-EDIT-ACCOUNT} - {@code app/cbl/COCRDSLC.cbl:647-679}: the account filter's edit, in two
     * guarded stages.
     *
     * <p>Stage one, {@code :651-661} - not supplied. Three conditions, {@code OR}ed:
     * {@code CC-ACCT-ID EQUAL LOW-VALUES}, {@code EQUAL SPACES}, or {@code CC-ACCT-ID-N EQUAL ZEROS}. The
     * third reads the same eleven bytes through the {@code PIC 9(11)} redefinition, so it catches
     * {@code "00000000000"} - a supplied but meaningless account. All three set {@code INPUT-ERROR} and
     * {@code FLG-ACCTFILTER-BLANK}, zero {@code CDEMO-ACCT-ID}, and exit.
     *
     * <p>Stage two, {@code :665-678} - not numeric. On failure it sets {@code FLG-ACCTFILTER-NOT-OK}, which
     * is a <em>different</em> flag state from stage one's {@code BLANK}, and that difference decides
     * whether {@link #setupScreenAttrs1300} writes an asterisk or only a colour. On success it stores the
     * value and restores {@code ISVALID}.
     *
     * <p>Two details that are easy to lose:
     * <ul>
     *   <li>The message assignments are guarded by {@code IF WS-RETURN-MSG-OFF} ({@code :656},
     *       {@code :668}) but the {@code SET INPUT-ERROR} and the flag are <strong>not</strong>. So an
     *       earlier message survives while the failure is still recorded. Flattening the guard would let a
     *       later field's complaint replace an earlier one.</li>
     *   <li>{@code :676} moves {@code CC-ACCT-ID} - the <strong>alphanumeric</strong> view - into the
     *       numeric {@code CDEMO-ACCT-ID}. {@link #editCard2220} moves the <em>numeric</em> view instead.
     *       The asymmetry is in the source and is preserved.</li>
     * </ul>
     *
     * @param task this task's storage
     */
    void editAccount2210(Conversation task) {
        // :648 - SET FLG-ACCTFILTER-NOT-OK TO TRUE. Guilty until proven otherwise.
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;

        // :651-661 - Not supplied: LOW-VALUES, SPACES, or eleven zeros through the PIC 9 view
        if (task.ccWorkArea.isCcAcctIdLowValues()
                || task.ccWorkArea.isCcAcctIdSpaces()
                || task.ccWorkArea.isCcAcctIdNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_ACCT;
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            // :660 - GO TO 2210-EDIT-ACCOUNT-EXIT
            return;
        }

        // :665-678 - Not numeric, and therefore not an 11-digit number
        if (!task.ccWorkArea.isCcAcctIdNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = PIC_X_CODEC.movePicX(
                        "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);
            }
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            // :674 - GO TO 2210-EDIT-ACCOUNT-EXIT
            return;
        }

        // :676-677 - MOVE CC-ACCT-ID (the X view) TO CDEMO-ACCT-ID; SET FLG-ACCTFILTER-ISVALID
        task.carddemoCommarea =
                task.carddemoCommarea.withAcctId(codec.decodePic9(task.ccWorkArea.getCcAcctId()));
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 2220-EDIT-CARD} - {@code app/cbl/COCRDSLC.cbl:685-720}: the card filter's edit, the same two
     * stages against sixteen digits.
     *
     * <p>Structurally a mirror of {@link #editAccount2210}, with one genuine difference that is worth
     * stating rather than smoothing over: {@code :717} moves {@code CC-CARD-NUM-N} - the
     * <strong>numeric</strong> {@code PIC 9(16)} redefinition - into {@code CDEMO-CARD-NUM}, whereas the
     * account edit moves the <em>alphanumeric</em> view. Both reach a numeric receiver and both work, so
     * the difference has no effect on a valid value; it is preserved because it is what the source says and
     * because inventing consistency where the source has none is exactly the kind of tidying practice
     * <strong>B5</strong> forbids.
     *
     * <p>The two message assignments carry the same {@code IF WS-RETURN-MSG-OFF} guard ({@code :696},
     * {@code :709}) as the account edit - which matters here in particular, because the account edit runs
     * <em>first</em>. A blank account followed by a blank card leaves
     * {@code 'Account number not provided'} standing, not {@code 'Card number not provided'} - until the
     * unguarded cross-field rule replaces both with {@code 'No input received'}.
     *
     * @param task this task's storage
     */
    void editCard2220(Conversation task) {
        // :688 - SET FLG-CARDFILTER-NOT-OK TO TRUE
        task.wsEditCardFlag = FLG_FILTER_NOT_OK;

        // :691-702 - Not supplied
        if (task.ccWorkArea.isCcCardNumLowValues()
                || task.ccWorkArea.isCcCardNumSpaces()
                || task.ccWorkArea.isCcCardNumNZeros()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_CARD;
            }
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            // :701 - GO TO 2220-EDIT-CARD-EXIT
            return;
        }

        // :706-719 - Not numeric, and therefore not a 16-digit number
        if (!task.ccWorkArea.isCcCardNumNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = PIC_X_CODEC.movePicX(
                        "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);
            }
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            // :715 - GO TO 2220-EDIT-CARD-EXIT
            return;
        }

        // :717-718 - MOVE CC-CARD-NUM-N (the PIC 9 view) TO CDEMO-CARD-NUM; SET FLG-CARDFILTER-ISVALID
        task.carddemoCommarea = task.carddemoCommarea.withCardNum(task.ccWorkArea.getCcCardNumN());
        task.wsEditCardFlag = FLG_FILTER_ISVALID;
    }

    // =================================================================================================
    // 9000-READ-DATA and the two read paragraphs - app/cbl/COCRDSLC.cbl:726-812.
    // =================================================================================================

    /**
     * {@code 9000-READ-DATA} - {@code app/cbl/COCRDSLC.cbl:726-730}.
     *
     * <p>It performs <strong>one</strong> paragraph, not two:
     *
     * <pre>
     * 9000-READ-DATA.
     *     PERFORM 9100-GETCARD-BYACCTCARD
     *        THRU 9100-GETCARD-BYACCTCARD-EXIT
     *     .
     * </pre>
     *
     * <p>The migration plan describes this paragraph as dispatching to two, which is worth correcting
     * explicitly (practice B4): {@code 9150-GETCARD-BYACCT} is <strong>never performed from
     * anywhere</strong> in the program. A search of {@code app/cbl/COCRDSLC.cbl} for {@code 9150} finds
     * only its own label at {@code :779} and its exit label at {@code :810}. It is dead code, it is
     * translated anyway in {@link #getCardByAcct9150} because deleting it would be a change (practice
     * <strong>B5</strong>), and it is not wired in here because wiring it in would be a much bigger one.
     *
     * @param task this task's storage
     */
    void readData9000(Conversation task) {
        getCardByAcctCard9100(task);
    }

    /**
     * {@code 9100-GETCARD-BYACCTCARD} - {@code app/cbl/COCRDSLC.cbl:736-773}: read the card by card number
     * from the {@code CARDDAT} base cluster.
     *
     * <pre>
     * MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM
     * EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
     *      KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)
     *      LENGTH(LENGTH OF CARD-RECORD) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
     * </pre>
     *
     * <p>The sibling statement {@code MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID} sits directly above at
     * {@code :739}, <strong>commented out</strong>. It stays absent (practice B5): reinstating it would fill
     * the alternate key that {@link #getCardByAcct9150} reads and change that paragraph's behaviour from
     * "always searches for account zero" to "searches for the requested account". The account filter
     * therefore takes part in the edits and in the screen, but <em>not</em> in the key - the read is by card
     * number alone, despite the paragraph's name.
     *
     * <h3>The guard chain, and its nesting</h3>
     *
     * <p>{@code :752-772} is an {@code EVALUATE WS-RESP-CD} with three arms in this order:
     *
     * <ul>
     *   <li>{@code WHEN DFHRESP(NORMAL)} - {@code SET FOUND-CARDS-FOR-ACCOUNT TO TRUE}, which is a
     *       {@code MOVE} of a message into {@code WS-INFO-MSG} and doubles as the flag
     *       {@link #setupScreenVars1200} tests.</li>
     *   <li>{@code WHEN DFHRESP(NOTFND)} - {@code INPUT-ERROR} and <em>both</em> filter flags set to
     *       {@code NOT-OK} unconditionally, then the message
     *       <strong>only {@code IF WS-RETURN-MSG-OFF}</strong>.</li>
     *   <li>{@code WHEN OTHER} - {@code INPUT-ERROR} unconditionally, then
     *       {@code FLG-ACCTFILTER-NOT-OK} <strong>only {@code IF WS-RETURN-MSG-OFF}</strong> - note that
     *       here the guard wraps a <em>flag</em>, not a message - and then the four
     *       {@code ERROR-*} items and the composed 80-character file-error message, all
     *       <em>unguarded</em>.</li>
     * </ul>
     *
     * <p>That last arm is the subtlest thing in the paragraph: the guard covers the flag but not the
     * message, so a failure that follows an earlier message overwrites the message and leaves the account
     * flag alone. Flattening either guard changes which message the operator sees, and the plan flags this
     * nesting as critical for exactly that reason.
     *
     * <p>Gate <strong>G47</strong>: all four {@link FileStatus} outcomes are reachable at this call site.
     * {@link FileStatus#OK} takes the first arm, {@link FileStatus#NOT_FOUND} the second, and
     * {@link FileStatus#END_OF_FILE} and {@link FileStatus#DUPLICATE} both fall to {@code WHEN OTHER} -
     * which is correct, because the COBOL enumerates only {@code NORMAL} and {@code NOTFND}.
     *
     * @param task this task's storage
     */
    void getCardByAcctCard9100(Conversation task) {
        // :740 - MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM.
        // (:739's MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID is commented out and stays absent - B5.)
        task.wsCardRidCardnum =
                codec.movePicX(task.ccWorkArea.getCcCardNum(), WS_CARD_RID_CARDNUM_LENGTH);

        // :742-750 - EXEC CICS READ ... INTO(CARD-RECORD) RESP RESP2
        CardRepository.CardReadResult result = cardRepository.readByCardNumber(task.wsCardRidCardnum);
        task.wsRespCd = result.resp();
        task.wsReasCd = result.resp2();
        task.cardRecord = result.record();

        // :752-772 - EVALUATE WS-RESP-CD, in the source's arm order
        if (result.isNormal()) {
            // :753-754
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
        } else if (result.isNotFound()) {
            // :755-761
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = DID_NOT_FIND_ACCTCARD_COMBO;
            }
        } else {
            // :762-771 - WHEN OTHER
            task.wsInputFlag = INPUT_ERROR;
            // The guard wraps the FLAG here, not the message. That is the source's nesting.
            if (task.returnMessageOff()) {
                task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            }
            recordFileError(task, LIT_CARDFILENAME);
        }
    }

    /**
     * {@code 9150-GETCARD-BYACCT} - {@code app/cbl/COCRDSLC.cbl:779-809}: read the card by account id
     * through the {@code CARDAIX} alternate-index path. The source comment reads "Read the Card file.
     * Access via alternate index ACCTID".
     *
     * <p><strong>Dead code, translated deliberately.</strong> No paragraph performs it - see
     * {@link #readData9000}. It is here because practice <strong>B5</strong> preserves dead code, and it is
     * package-visible so a parity case can still drive all three of its arms and prove the translation is
     * right, rather than leaving an untested method in the file.
     *
     * <p>Two things distinguish it from {@link #getCardByAcctCard9100}, and both are real:
     *
     * <ol>
     *   <li>It reads {@code FILE(LIT-CARDFILENAME-ACCT-PATH)} with {@code RIDFLD(WS-CARD-RID-ACCT-ID)} -
     *       one more access path over the same base cluster, never a second dataset (gate G45). And because
     *       the only statement that would have filled that key is commented out at {@code :739}, the key
     *       holds what {@code INITIALIZE} left: eleven zeros. So this paragraph, as written, would always
     *       search for account zero. That is preserved, not corrected.</li>
     *   <li>Its {@code NOTFND} arm sets {@code DID-NOT-FIND-ACCT-IN-CARDXREF}
     *       <strong>unconditionally</strong> - {@code :796-799} has <em>no</em>
     *       {@code IF WS-RETURN-MSG-OFF} - whereas {@code 9100}'s equivalent is guarded. Its
     *       {@code WHEN OTHER} arm likewise sets {@code FLG-ACCTFILTER-NOT-OK} unguarded at {@code :802},
     *       where {@code 9100} guards it. The asymmetry is easy to mistake for an oversight and is
     *       reproduced exactly.</li>
     * </ol>
     *
     * @param task this task's storage
     */
    void getCardByAcct9150(Conversation task) {
        // :783-791 - EXEC CICS READ FILE(LIT-CARDFILENAME-ACCT-PATH) RIDFLD(WS-CARD-RID-ACCT-ID)
        CardRepository.CardReadResult result =
                cardRepository.readByAccountIdViaAltIndex(task.wsCardRidAcctId);
        task.wsRespCd = result.resp();
        task.wsReasCd = result.resp2();
        task.cardRecord = result.record();

        // :793-808 - EVALUATE WS-RESP-CD
        if (result.isNormal()) {
            // :794-795
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
        } else if (result.isNotFound()) {
            // :796-799 - NO WS-RETURN-MSG-OFF guard anywhere in this arm
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            task.wsReturnMsg = DID_NOT_FIND_ACCT_IN_CARDXREF;
        } else {
            // :800-807 - WHEN OTHER, also unguarded
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            recordFileError(task, LIT_CARDFILENAME_ACCT_PATH);
        }
    }

    /**
     * The four {@code ERROR-*} assignments and the composed message that close both {@code WHEN OTHER}
     * arms - {@code app/cbl/COCRDSLC.cbl:767-771} and {@code :803-807}.
     *
     * <pre>
     * MOVE 'READ'                TO ERROR-OPNAME
     * MOVE &lt;the file name&gt;        TO ERROR-FILE
     * MOVE WS-RESP-CD            TO ERROR-RESP
     * MOVE WS-REAS-CD            TO ERROR-RESP2
     * MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG
     * </pre>
     *
     * <p>Shared because the two arms are character-identical apart from the file name, and because the
     * 80-character composition deserves exactly one home.
     *
     * <p>The final {@code MOVE} is the one to watch: {@code WS-FILE-ERROR-MESSAGE} is
     * {@value #FILE_ERROR_MESSAGE_LENGTH} characters and {@code WS-RETURN-MSG} is
     * {@value #WS_RETURN_MSG_LENGTH}, so the {@code PIC X} rule discards the last five - which are the
     * trailing filler's spaces, so nothing legible is lost, but the truncation is real and is applied
     * rather than assumed away.
     *
     * @param task     this task's storage
     * @param fileName the eight-character CICS file name the failing read named
     */
    void recordFileError(Conversation task, String fileName) {
        task.errorOpname = codec.movePicX(READ_OPERATION_NAME, ERROR_OPNAME_LENGTH);
        task.errorFile = codec.movePicX(fileName, ERROR_FILE_LENGTH);
        task.errorResp = responseCodeImage(task.wsRespCd);
        task.errorResp2 = responseCodeImage(task.wsReasCd);
        task.wsReturnMsg = codec.movePicX(fileErrorMessage(task), WS_RETURN_MSG_LENGTH);
    }

    /**
     * {@code MOVE WS-RESP-CD TO ERROR-RESP} - a {@code PIC S9(09) COMP} sender into a {@code PIC X(10)}
     * receiver, {@code app/cbl/COCRDSLC.cbl:769}.
     *
     * <p>COBOL performs this in two steps and both matter. First the binary sender is rendered as its
     * {@value #RESP_CODE_DIGITS} unsigned digits, zero-filled on the left - the sign is not moved, because
     * an alphanumeric receiver has nowhere to put one. Then the {@code PIC X} rule applies, which
     * left-justifies those nine characters in a ten-character receiver and pads one space on the right. So
     * a {@code NOTFND} response of {@value FileStatus#NOTFND} renders as {@code "000000013 "} - nine digits
     * and a trailing space, not ten digits and not a right-justified number.
     *
     * <p>Getting this wrong produces a message that looks plausible and is off by a character, which is
     * exactly the class of defect the parity harness exists to catch, so the composition is spelled out
     * here rather than left to a format string.
     *
     * @param responseCode the CICS {@code RESP} or {@code RESP2} value
     * @return exactly {@value #ERROR_RESP_LENGTH} characters
     */
    static String responseCodeImage(int responseCode) {
        // Math.abs on a widened long, so Integer.MIN_VALUE cannot wrap. A CICS response is never
        // negative in practice; the guard is here because movePic9 rejects a negative sender and a
        // corrupted response code must still produce a message rather than an exception.
        String digits = PIC_X_CODEC.movePic9(Math.abs((long) responseCode), RESP_CODE_DIGITS);
        return PIC_X_CODEC.movePicX(digits, ERROR_RESP_LENGTH);
    }

    /**
     * {@code WS-FILE-ERROR-MESSAGE} as one string - {@code app/cbl/COCRDSLC.cbl:102-121}, exactly
     * {@value #FILE_ERROR_MESSAGE_LENGTH} characters.
     *
     * <pre>
     * 'File Error: '  (12) + ERROR-OPNAME (8) + ' on '            (4) + ERROR-FILE  (9)
     * ' returned RESP '(15) + ERROR-RESP  (10) + ',RESP2 '        (7) + ERROR-RESP2 (10)
     * SPACES           (5)
     * </pre>
     *
     * <p>A COBOL group item is the concatenation of its members at their declared widths, and that is all
     * this method is - with the {@code PIC X} move applied to each variable member so a short or over-long
     * value cannot shift the ones after it. The postcondition is checked rather than trusted, because a
     * message of the wrong length is the one failure mode that would corrupt every field after it.
     *
     * @param task this task's storage, holding the four {@code ERROR-*} items
     * @return exactly {@value #FILE_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException if the composition does not come to
     *                               {@value #FILE_ERROR_MESSAGE_LENGTH} characters
     */
    String fileErrorMessage(Conversation task) {
        String message = FILE_ERROR_PREFIX
                + codec.movePicX(task.errorOpname, ERROR_OPNAME_LENGTH)
                + FILE_ERROR_ON
                + codec.movePicX(task.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + codec.movePicX(task.errorResp, ERROR_RESP_LENGTH)
                + FILE_ERROR_RESP2
                + codec.movePicX(task.errorResp2, ERROR_RESP_LENGTH)
                + FILE_ERROR_TRAILER;
        if (message.length() != FILE_ERROR_MESSAGE_LENGTH) {
            throw new IllegalStateException("WS-FILE-ERROR-MESSAGE must be exactly "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters, as app/cbl/COCRDSLC.cbl:102-121 declares "
                    + "it, but the composition came to " + message.length());
        }
        return message;
    }

    // =================================================================================================
    // The three terminal paragraphs - app/cbl/COCRDSLC.cbl:820-878.
    // =================================================================================================

    /**
     * {@code SEND-LONG-TEXT} - {@code app/cbl/COCRDSLC.cbl:820-830}:
     * {@code EXEC CICS SEND TEXT FROM(WS-LONG-MSG) LENGTH(500) ERASE FREEKB} followed by
     * {@code EXEC CICS RETURN}.
     *
     * <p>The source's own comment is unambiguous about it: "This is primarily for debugging and should not
     * be used in regular course". It is also <strong>never performed</strong> - a search of
     * {@code app/cbl/COCRDSLC.cbl} for {@code SEND-LONG-TEXT} finds only its label at {@code :820} and its
     * exit label at {@code :831} - and {@code WS-LONG-MSG} is never assigned, so it would transmit five
     * hundred spaces.
     *
     * <p>Translated anyway, because practice <strong>B5</strong> preserves dead code, and package-visible so
     * a test can prove the translation rather than leave it unexercised. Like its sibling it maps to an
     * error response ({@code SEND TEXT} has no map, so there is nothing else the 500 characters could reach)
     * and it ends the task, which is why {@code returned} is set: the trailing
     * {@code IF INPUT-ERROR} guard must not run after an {@code EXEC CICS RETURN}.
     *
     * @param response the response, whose error line carries the transmitted text
     * @param task     this task's storage
     */
    void sendLongText(CardSelectResponse response, Conversation task) {
        // :821-826 - EXEC CICS SEND TEXT FROM(WS-LONG-MSG) LENGTH(LENGTH OF WS-LONG-MSG) ERASE FREEKB
        String transmitted = codec.movePicX(task.wsLongMsg, WS_LONG_MSG_LENGTH);
        response.setErrmsgo(codec.movePicX(transmitted, CardSelectResponse.ERRMSGO_LENGTH));
        response.setNavigationContext(task.carddemoCommarea);
        response.setCardScreenState(task.ccWorkArea);

        // :828-829 - EXEC CICS RETURN. No TRANSID and no COMMAREA: the conversation ends here.
        task.returned = true;
    }

    /**
     * {@code SEND-PLAIN-TEXT} - {@code app/cbl/COCRDSLC.cbl:838-848}:
     * {@code EXEC CICS SEND TEXT FROM(WS-RETURN-MSG) LENGTH(75) ERASE FREEKB} followed by
     * {@code EXEC CICS RETURN}.
     *
     * <p>Performed from exactly one place - the {@code WHEN OTHER} arm at {@code :379-380} - and the source
     * comments it "Plain text exit - Dont use in production".
     *
     * <p>Three properties are carried across, and each is a decision worth defending:
     *
     * <ul>
     *   <li><strong>The map is not sent.</strong> {@code SEND TEXT} erases the screen and writes 75
     *       characters; the fifteen items are left exactly as {@code MOVE LOW-VALUES TO CCRDSLAO} left
     *       them. So the response's data items stay {@code LOW-VALUES} and only the error line carries
     *       anything. Painting the screen here would be new behaviour.</li>
     *   <li><strong>The transaction ends normally.</strong> This is {@code EXEC CICS RETURN}, not
     *       {@code EXEC CICS ABEND}, so the endpoint answers {@code 200}. Returning a {@code 5xx} would
     *       claim a failure the program never reported.</li>
     *   <li><strong>{@code COMMON-RETURN} is not reached.</strong> The {@code RETURN} here carries no
     *       {@code TRANSID} and no {@code COMMAREA}, so the conversation is not continued and the commarea
     *       is not reassembled. {@code returned} records that, and the trailing
     *       {@code IF INPUT-ERROR} guard is skipped exactly as it is on the mainframe.</li>
     * </ul>
     *
     * <p>The text is logged as well as returned, because on the mainframe it reached a human at a terminal
     * and there is no terminal here - an unexpected data scenario that left no trace would be strictly
     * worse than the original.
     *
     * @param response the response, whose error line carries the transmitted text
     * @param task     this task's storage
     */
    void sendPlainText(CardSelectResponse response, Conversation task) {
        // :839-844 - EXEC CICS SEND TEXT FROM(WS-RETURN-MSG) LENGTH(LENGTH OF WS-RETURN-MSG)
        String transmitted = codec.movePicX(task.wsReturnMsg, WS_RETURN_MSG_LENGTH);
        response.setErrmsgo(codec.movePicX(transmitted, CardSelectResponse.ERRMSGO_LENGTH));
        response.setNavigationContext(task.carddemoCommarea);
        response.setCardScreenState(task.ccWorkArea);

        if (LOG.isWarnEnabled()) {
            LOG.warn(LIT_THISPGM + " sent plain text (abend code "
                    + task.abendData.abendCode().trim() + "): " + transmitted.trim());
        }

        // :846-847 - EXEC CICS RETURN. No TRANSID and no COMMAREA.
        task.returned = true;
    }

    /**
     * {@code ABEND-ROUTINE} - {@code app/cbl/COCRDSLC.cbl:857-878}, the label named by
     * {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} at {@code :250-252}.
     *
     * <pre>
     * IF ABEND-MSG EQUAL LOW-VALUES
     *    MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG
     * END-IF
     * MOVE LIT-THISPGM TO ABEND-CULPRIT
     * EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA) NOHANDLE END-EXEC
     * EXEC CICS HANDLE ABEND CANCEL END-EXEC
     * EXEC CICS ABEND ABCODE('9999') END-EXEC
     * </pre>
     *
     * <p>The guard is {@code EQUAL LOW-VALUES}, so the default text is supplied only when
     * {@code ABEND-MSG} is untouched binary zero. A message already placed there survives - which is how a
     * caller's diagnosis reaches the terminal instead of being replaced by a generic one. Note that
     * {@code CSMSG02Y} declares {@code VALUE SPACES}, so a freshly initialised {@code ABEND-DATA} is
     * <em>spaces</em> and the guard does <strong>not</strong> fire; the default appears only if something
     * explicitly blanked the field with {@code LOW-VALUES}. Spaces and binary zero are different values and
     * this guard distinguishes them, so both cases are reproduced rather than merged.
     *
     * <p>{@code HANDLE ABEND CANCEL} at {@code :871-873} cancels the handler before abending, which is why
     * the {@code catch} in {@link #handle} rethrows an {@link AbendException} untouched instead of routing
     * it here a second time. Without that, an abend inside the handler would recurse.
     *
     * <p>{@code EXEC CICS ABEND ABCODE('9999')} is the last statement, and it does not return - so this
     * method returns the exception for the caller to throw rather than pretending to have a normal
     * outcome. {@code WebConfig.CobolErrorHandler} answers {@link AbendException} with {@code 500} and a
     * constant body, so no program name, return code or driver detail reaches a client.
     *
     * @param task     this task's storage
     * @param response the response; not painted, since {@code EXEC CICS SEND} sends {@code ABEND-DATA}
     * @param cause    the failure that triggered the handler
     * @return the abend to throw; never {@code null}
     */
    AbendException abendRoutine(Conversation task, CardSelectResponse response, RuntimeException cause) {
        // ABEND-DATA is spaces before any arm writes to it, and INITIALIZE may not have run at all if the
        // failure came very early, so it is defaulted here rather than assumed present.
        SystemMessages.AbendData abendData =
                task.abendData == null ? SystemMessages.AbendData.spaces() : task.abendData;

        // :859-861 - IF ABEND-MSG EQUAL LOW-VALUES. LOW-VALUES, not spaces.
        String abendMsg = PIC_X_CODEC.movePicX(abendData.abendMsg(), SystemMessages.ABEND_MSG_LENGTH);
        if (CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendMsg)) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }

        // :863 - MOVE LIT-THISPGM TO ABEND-CULPRIT
        abendData = abendData.withAbendCulprit(LIT_THISPGM);
        task.abendData = abendData;

        // :865-869 - EXEC CICS SEND FROM(ABEND-DATA) ... NOHANDLE. There is no terminal, so the area is
        // logged. NOHANDLE means a failure here is ignored, which a log call already honours.
        //
        // The triggering failure is rendered through BackendDiagnostic rather than handed to the logger,
        // and both halves of that matter. Handing a throwable to Commons Logging emits its message and
        // its whole cause chain verbatim, which for a JDBC failure is text the driver composed around
        // the record it refused - so a record image could reach a log line, and an embedded newline in
        // it could forge a second entry (CWE-117). BackendDiagnostic carries the SQLSTATE, the vendor
        // code and the exception type and deliberately has no component for the driver's message, which
        // is the module's established way to keep a failure diagnosable without quoting the backend. The
        // throwable itself is not discarded: it travels as the cause of the AbendException returned
        // below, where the error handler decides what a client may see.
        LOG.error(LIT_THISPGM + " abending with ABCODE " + ABEND_ROUTINE_ABCODE + ": "
                + AbendException.ABEND_DISPLAY_TEXT + " " + abendData.toDeclaredWidths()
                + " - " + BackendDiagnostic.of(cause).describe());

        // The response is left exactly as the failing path left it: EXEC CICS SEND sends ABEND-DATA, not
        // the map, so nothing further is painted. Reading it keeps the parameter honest rather than unused.
        response.setCardScreenState(task.ccWorkArea == null ? new CardScreenState() : task.ccWorkArea);
        task.returned = true;

        // :871-877 - HANDLE ABEND CANCEL, then EXEC CICS ABEND ABCODE('9999').
        //
        // withoutAbendParameters, deliberately, NOT standard(...). AbendException models
        // CALL 'CEE3ABD', whose eight standard sites move 999 into ABCODE and 0 into TIMING.
        // COCRDSLC calls no CEE3ABD at all - it issues a CICS ABEND with the four-character code
        // '9999' - so reporting a CEE3ABD ABCODE of 999 here would fabricate a value this program
        // never produces, and reporting 9999 would claim the CEE3ABD field held it. Both parameters
        // are therefore absent, which is the truth, and the CICS code travels in the reason text and
        // the log line above where it can be read without being mistaken for a CEE3ABD argument
        // (practice B4).
        //
        // RETURN_CODE_IO_ERROR (12) is the estate's convention for an abend arising from a
        // data-access failure, which every path into this handler is.
        //
        // withSourceDiagnostic carries the 134 bytes :865-869 actually transmits, so the error handler
        // can publish what the operator would have read instead of replacing it with a constant. Every
        // one of the four fields is source-authored - a copybook literal, this PROGRAM-ID, or spaces -
        // and the triggering failure is deliberately NOT among them: it travels as the cause, which the
        // handler never renders.
        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + abendData.abendMsg().trim(), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    /**
     * {@code ABEND-DATA} as the {@code EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA)}
     * at {@code app/cbl/COCRDSLC.cbl:865-869} puts it on the wire: the four fields concatenated at their
     * declared widths, {@value SystemMessages#ABEND_DATA_LENGTH} characters in total.
     *
     * <p>{@code LENGTH OF ABEND-DATA} is the group's length, so the transmission is the whole 4 + 8 + 50
     * + 72 with no separator and no trimming - which is why the components are brought to their declared
     * widths first and then joined. A trimmed rendering would be a different number of bytes than the
     * terminal received.
     *
     * @param abendData the area as the routine leaves it; must not be {@code null}
     * @return the transmitted image, exactly {@value SystemMessages#ABEND_DATA_LENGTH} characters
     */
    static String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }
}
