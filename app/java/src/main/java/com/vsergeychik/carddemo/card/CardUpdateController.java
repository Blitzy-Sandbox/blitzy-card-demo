package com.vsergeychik.carddemo.card;

import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.FieldMetadata;
import com.vsergeychik.carddemo.card.dto.CardUpdateResponse;
import com.vsergeychik.carddemo.card.model.CardRecord;
import com.vsergeychik.carddemo.common.AbendException;
import com.vsergeychik.carddemo.common.AidRequestParameter;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CicsAid;
import com.vsergeychik.carddemo.common.DatasetRelation.BackendDiagnostic;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
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
import com.vsergeychik.carddemo.customer.model.CustomerRecord;
import com.vsergeychik.carddemo.user.model.SecUserRecord;
import jakarta.validation.Valid;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * {@code PUT /api/cards/{cardNum}} - the credit-card <strong>update</strong> screen, CSD transaction
 * {@code CCUP} ({@code app/csd/CARDDEMO.CSD:367-369}), migrated from {@code app/cbl/COCRDUPC.cbl}
 * (1,560 lines), mapset {@code COCRDUP} ({@code :128}) and map {@code CCRDUPA}. At 1,560 lines it is
 * the second-largest online program in the estate, and with <strong>8 {@code EVALUATE} statements,
 * 21 {@code GO TO}s and 65 {@code 88}-level condition names</strong> (all three counts verified
 * against the source) it carries the densest branch surface in the {@code card} package.
 *
 * <p>This is a like-for-like language migration. The control-flow <em>shape</em> is modernised -
 * {@code GO TO} becomes {@code return}, {@code EVALUATE} becomes an ordered {@code if} chain - but
 * evaluation order, field widths, message text, fill bytes and error paths are held byte-exact.
 * Every paragraph of the original survives as a method named after it so the two can be read side by
 * side, and <strong>all write and concurrency logic lives in {@link CardUpdateService}</strong>: this
 * class owns the state machine, the field edits, the screen projection and the navigation, and never
 * locks or rewrites a record itself.
 *
 * <h2>Rules governing this file</h2>
 *
 * <p><strong>{@code review_rules} returns exactly one line: "No user rules provided."</strong> That
 * single line is the whole document; there is nothing to page through. Per UR4 their absence is
 * <em>not</em> licence to lower the bar, so the migration plan's enterprise best practices
 * <strong>B1-B12</strong> bind in their place and are cited by name at each decision below. The ones
 * that shaped this class are B4 (document conflicts, never silently repair one), B5 (dead and odd
 * code preserved as-is), B6 (security posture neither weakened nor unrequestedly strengthened), B8
 * (explicit over implicit), B9 (no static mutable state), B10 (decision logic reachable without HTTP)
 * and B12 (environmental limits documented, not absorbed).
 *
 * <h2>Two order-dependent traps in {@code 2000-DECIDE-ACTION} ({@code :948-1030}, gate G30)</h2>
 *
 * <ol>
 *   <li><strong>{@code WHEN CCUP-DETAILS-NOT-FETCHED} at {@code :954} has no body of its own and
 *       shares {@code WHEN CCARD-AID-PFK12}'s at {@code :958-966}.</strong> Two consecutive
 *       {@code WHEN} clauses with no statement between them are COBOL's explicit multi-{@code WHEN}
 *       <em>OR-grouping</em> - not implicit fall-through, which COBOL does not have. Read as a no-op,
 *       first entry to this screen would silently do nothing. {@link #decideAction2000} therefore
 *       tests them as one {@code ||} condition.</li>
 *   <li><strong>{@code WHEN CCUP-CHANGES-OK-NOT-CONFIRMED} is tested twice</strong> - qualified with
 *       {@code AND CCARD-AID-PFK05} at {@code :988-989}, then bare at {@code :1006}. Only source
 *       order makes the bare arm mean "confirmation not given"; inverting the two would make
 *       {@code PF5} stop saving. Both are reproduced in place, in order.</li>
 * </ol>
 *
 * <p>The whole screen is driven by {@code CCUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES}
 * ({@code :276-291}), whose nine condition names include three that match <em>several</em> values:
 * {@code CCUP-DETAILS-NOT-FETCHED} is {@code LOW-VALUES} <em>or</em> {@code SPACES},
 * {@code CCUP-CHANGES-MADE} is any of {@code 'E' 'N' 'C' 'L' 'F'}, and {@code CCUP-CHANGES-FAILED}
 * is {@code 'L'} or {@code 'F'}. {@link ChangeAction} models the discriminant and all nine
 * predicates, so binary {@code x'00'}, a space and a Java {@code null} are never conflated.
 *
 * <h2>Conflict 1 - {@code LIT-CCLISTMAP} names the wrong map, and stays wrong (B4, B5)</h2>
 *
 * <p>{@code app/cbl/COCRDUPC.cbl:233-234} declares
 * {@code 05 LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'}. The card list's map is really {@code 'CCRDLIA'}
 * ({@code app/cbl/COCRDLIC.cbl:185}), so the literal names the card <em>detail</em> map where it means
 * to name the list's. {@code app/cbl/COCRDSLC.cbl:178} carries the identical defect.
 * {@link #LIT_CCLISTMAP} reproduces {@code 'CCRDSLA'} unchanged: correcting it would change an
 * observable value and break parity, so it is recorded here rather than repaired.
 *
 * <p>A verified detail that makes the defect harmless in this program: <strong>{@code LIT-CCLISTMAP}
 * is never referenced in the {@code PROCEDURE DIVISION}.</strong> Only {@code LIT-CCLISTMAPSET} is,
 * at {@code :437}, {@code :439}, {@code :459} and {@code :1238}. The literal is dead and is kept
 * because deleting a dead declaration is itself a change (B5) - as are {@link #LIT_CCLISTTRANID},
 * {@link #LIT_MENUMAPSET}, {@link #LIT_MENUMAP}, the four {@code LIT-CARDDTL*} literals and
 * {@link #LIT_CARDFILENAME_ACCT_PATH}, none of which this program uses either.
 *
 * <h2>Conflict 2 - two copybooks the plan lists are not copied (B4)</h2>
 *
 * <p>Plan section 0.2.3 lists {@code CVACT01Y} and {@code CVACT03Y} among this program's copybooks.
 * Read directly, both {@code COPY} statements are <strong>commented out</strong>:
 * {@code app/cbl/COCRDUPC.cbl:350} is {@code *COPY CVACT01Y.} and {@code :356} is
 * {@code *COPY CVACT03Y.} This class therefore imports neither {@code account.model.AccountRecord}
 * nor {@code card.model.CardXrefRecord}; doing so would create coupling the source does not have.
 * The program copies thirteen copybooks and those two are not among them.
 *
 * <p>The two that <em>are</em> live but never referenced - {@code CSUSR01Y} at {@code :346} and
 * {@code CVCUS01Y} at {@code :359} - are a different case: a live {@code COPY} allocates storage, so
 * both are modelled as declared-only areas on {@link Conversation#secUserData} and
 * {@link Conversation#customerRecord}. Note that no field of {@code SEC-USER-DATA} is read here and
 * {@code SEC-USR-PWD} is never compared - this screen performs no authentication, and none is added
 * (practice B6).
 *
 * <h2>Conflict 3 - {@code COCRDUPC} declares no {@code COMP-3} at all (B4)</h2>
 *
 * <p>The migration brief for this file attributes {@code WS-EDIT-ALPHANUM-LENGTH PIC S9(4) COMP-3}
 * and the {@code WS-DIV-BY} / {@code WS-DIVIDEND} / {@code WS-REMAINDER} trio to
 * {@code COCRDUPC.cbl:62}, {@code :152}, {@code :154} and {@code :157}. Those are
 * <strong>{@code COACTUPC}'s</strong> line numbers, as plan section 0.7.1's own table records. An
 * exhaustive search of {@code app/cbl/COCRDUPC.cbl} finds <strong>zero</strong> occurrences of
 * {@code COMP-3} or {@code PACKED-DECIMAL}: {@code :62} is
 * {@code 88 FLG-CARDFILTER-NOT-OK VALUE '0'} and {@code :152} is a {@code FILLER PIC X(5)} inside
 * {@code WS-FILE-ERROR-MESSAGE}. No packed-decimal work item is fabricated here. The de-risking
 * conclusion the brief draws still holds and is stronger than stated: with no {@code COMP-3} in any
 * persisted record <em>and</em> none in this program's working storage, no nibble unpacking arises
 * anywhere on this screen.
 *
 * <h2>The misspelling {@code EXPIRAION}, preserved verbatim (plan I1)</h2>
 *
 * <p>{@code app/cpy/CVACT02Y.cpy:9} declares {@code CARD-EXPIRAION-DATE}, and the misspelling
 * propagates through this program at <strong>sixteen</strong> sites: {@code :115}, {@code :116},
 * {@code :122}, {@code :123}, {@code :297}, {@code :309}, {@code :319}, {@code :1361}, {@code :1363},
 * {@code :1365}, {@code :1473}, {@code :1505}, {@code :1506}, {@code :1507}, {@code :1514},
 * {@code :1515} and {@code :1516}. Every Java name derived from it keeps the spelling, because
 * field-for-field diffing matches on names.
 *
 * <h2>The 1-based reference modification, the highest off-by-one risk here</h2>
 *
 * <p>{@code :1361-1366} splits {@code CARD-EXPIRAION-DATE PIC X(10)} - which is
 * {@code YYYY-MM-DD}, <em>with</em> separators - into three unseparated components using
 * {@code (1:4)}, {@code (6:2)} and {@code (9:2)}. COBOL reference modification is
 * <strong>1-based</strong>, so those become Java {@code substring(0, 4)}, {@code substring(5, 7)} and
 * {@code substring(8, 10)}. Characters 5 and 8 are the {@code '-'} separators and belong to no
 * component. {@link CardRecord#cardExpiraionDateYear()} and its two siblings own the split and are
 * used rather than re-derived here. Note the shape difference this bridges:
 * {@code CCUP-OLD-EXPIRAION-DATE} and {@code CCUP-NEW-EXPIRAION-DATE} are year + month + day with
 * <em>no</em> separators (4 + 2 + 2 = 8), while the record's field is ten bytes with them;
 * {@link CardUpdateService} re-composes the ten from the eight through the
 * {@code STRING ... DELIMITED BY SIZE} of {@code :1467-1474}.
 *
 * <h2>The 329-byte program commarea and statelessness (rule R6, gate G37)</h2>
 *
 * <p>{@code :274-321} declares {@code 01 WS-THIS-PROGCOMMAREA}, which rides behind the 160-byte
 * {@code CARDDEMO-COMMAREA} inside {@code 01 WS-COMMAREA PIC X(2000)}: one byte of
 * {@code CCUP-CHANGE-ACTION}, 89 bytes of {@code CCUP-OLD-DETAILS}, 89 of
 * {@code CCUP-NEW-DETAILS} and <strong>150</strong> of {@code CARD-UPDATE-RECORD} -
 * {@value CommArea#RECORD_LENGTH} in total. {@code CARD-UPDATE-RECORD} is full width because
 * {@code :321} adds {@code FILLER X(59)}, a line easy to miss because it contains no
 * {@code CARD-UPDATE} text; gate G19 asserts the width and gate G21 that the filler is space-filled.
 *
 * <p>Every byte of it travels in the request and comes back in the response, as does
 * {@link CardScreenState} ({@code CC-WORK-AREA}, including {@code CCARD-AID}) and
 * {@link NavigationContext} ({@code CARDDEMO-COMMAREA}, including the {@code CDEMO-PGM-CONTEXT}
 * {@code ENTER}/{@code REENTER} flag). There is no {@code HttpSession}, no
 * {@code @SessionAttributes}, no server-side cache, no {@code ThreadLocal} and no static mutable
 * field anywhere in this class - the only {@code static} members are {@code final} constants and a
 * {@code final} logger (gate G53, practice B9). {@code WS-MISC-STORAGE},
 * {@code CICS-OUTPUT-EDIT-VARS}, {@code WS-CARD-RID} and {@code WS-COMMAREA} are per-request values
 * held on {@link Conversation}, which is constructed fresh on every call and cannot outlive it.
 *
 * <h2>{@code WS-RETURN-MSG-OFF} means SPACES here, not LOW-VALUES (B4)</h2>
 *
 * <p>Two similarly named fields exist and only one of them is this program's:
 *
 * <ul>
 *   <li>{@code app/cbl/COCRDUPC.cbl:173-174} declares {@code 05 WS-RETURN-MSG PIC X(75).} with
 *       {@code 88 WS-RETURN-MSG-OFF VALUE SPACES.} - a <strong>program-local</strong>
 *       {@code WORKING-STORAGE} field whose "off" state is <strong>75 spaces</strong>. This is the
 *       field {@code :384} sets and that all fourteen {@code IF WS-RETURN-MSG-OFF} guards test.</li>
 *   <li>{@code app/cpy/CVCRD01Y.cpy:29-30} declares {@code 10 CCARD-RETURN-MSG PIC X(75).} with
 *       {@code 88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES.} - a different field, whose off state is 75
 *       bytes of binary zero, and which {@code COCRDUPC} <strong>never references</strong>.</li>
 * </ul>
 *
 * <p>So {@link Conversation#returnMessageOff()} compares against spaces - by delegating to
 * {@link CardUpdateService#isReturnMessageOff(String)}, so the controller and the service cannot
 * disagree about it - and {@link CardScreenState#isCcardReturnMsgOff()} is deliberately not used for
 * this guard.
 *
 * <h2>Testability without HTTP (gate G51, practice B10)</h2>
 *
 * <p>The plan mandates exactly one service for this screen, so no additional one is invented. Every
 * decision lives in a package-visible method on this class and the class is directly instantiable:
 * {@code new CardUpdateController(mockRepository, mockUpdateService, fixedClock,
 * StandardCharsets.US_ASCII)} is enough to drive {@link #main0000}, {@link #decideAction2000}, the six
 * edit paragraphs, {@link #getCardByAcctCard9100} and the rest with no {@code MockMvc}, no Spring
 * context and no {@code JobLauncher} in the path. The {@link Clock} is injected for exactly that
 * reason - nothing here reads a clock of its own, so a parity case can fix the instant and get a
 * byte-identical screen.
 *
 * <h2>Where the bytes come from</h2>
 *
 * <p>Every cross-width assignment goes through {@link FixedWidthCodec#movePicX(String, int)} or
 * {@link FixedWidthCodec#movePic9(long, int)} rather than plain Java assignment: COBOL truncates
 * {@code PIC X} on the right and {@code PIC 9} on the left, a plain assignment does neither, and
 * {@code MOVE} is the dominant parity risk in this estate at 2,795 sites. Two consequences are
 * visible in this source and reproduced here - {@code MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET}
 * ({@code :1326}) moves an {@code X(8)} literal into an {@code X(7)} receiver and so drops a byte,
 * and {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} ({@code :1411}) moves 80 characters into
 * 75. Case folding uses the program's own declared translation pairs
 * ({@link #LIT_ALL_ALPHA_FROM}/{@link #LIT_ALL_SPACES_TO} and {@link #LIT_LOWER}/{@link #LIT_UPPER});
 * {@code String.toUpperCase()} appears nowhere, because it is locale-sensitive and folds characters
 * outside the twenty-six the copybook names.
 *
 * <p>No dataset name appears anywhere in this file (gate G46); all data access is through
 * {@link CardRepository} for the display read and {@link CardUpdateService} for the locked rewrite.
 * No {@code double} or {@code float} appears either (gate G22) - {@code CVACT02Y} declares no signed
 * decimal, so no {@link java.math.BigDecimal} and no rounding mode arises (gate G24) - and there is
 * no DDL, no entity annotation and no version column (gate G44): the optimistic-concurrency check is
 * the COBOL's own field comparison in {@code 9300-CHECK-CHANGE-IN-REC} (gate G43).
 *
 * <h2>The {@code FKEYSC} naming trap</h2>
 *
 * <p>{@code FKEYSC} is a genuine {@code DFHMDF} field name of its own -
 * {@code app/bms/COCRDUP.bms:163} - and <strong>not</strong> "{@code FKEYS} plus the colour suffix
 * {@code C}". Its metadata chain is {@code FKEYSCL} / {@code FKEYSCF} / {@code FKEYSCA} and
 * {@code FKEYSCC} / {@code FKEYSCP} / {@code FKEYSCH} / {@code FKEYSCV}, and its payload items are
 * {@code FKEYSCI} / {@code FKEYSCO} ({@code app/cpy-bms/COCRDUP.CPY:115-120} and {@code :216-224}).
 * A suffix-stripping mapper collapses it into {@code FKEYS} and silently deletes an 18-byte field.
 * The confusion is real rather than theoretical, because the output group's own
 * {@code 02 FKEYSC PICTURE X} at {@code :214} <em>is</em> {@code FKEYS}'s colour item. Both fields are
 * present and distinct here, and {@code :1316}'s {@code MOVE DFHBMBRY TO FKEYSCA OF CCRDUPAI} refers
 * to the <em>field</em> {@code FKEYSC}, which is how {@link #setupScreenAttrs3300} treats it.
 *
 * <h2>Parity provenance (practice B12, risk R-A)</h2>
 *
 * <p>The legacy COBOL <strong>cannot be executed in this environment</strong> - the plan documents
 * eight independently verified blockers, among them a disabled indexed-file handler, absent Language
 * Environment {@code CEE*} services and no CICS emulator. The expectations this class is verified
 * against are therefore <em>statically derived</em> from the COBOL paragraphs, the copybook byte
 * layouts, the BMS field definitions and the real {@code app/data/ASCII} fixtures. No captured
 * execution baseline exists and none is claimed.
 *
 * @see CardUpdateService the locked read, the concurrency check and the full-width rewrite
 * @see CardRepository the {@code CARDDAT} base cluster reached by the unlocked display read
 * @see CardUpdateRequest the seventeen {@code xxxI} items of mapset {@code COCRDUP}
 * @see CardUpdateResponse the seventeen {@code xxxO} items and their attribute quads
 */
@RestController
public class CardUpdateController {

    /**
     * Diagnostics for the paths the COBOL could only send to a terminal - the {@code WHEN OTHER} arm
     * of {@link #decideAction2000} and {@code ABEND-ROUTINE}. There is no terminal here, so the text
     * is logged rather than discarded.
     *
     * <p>{@code static final} and immutable, so it is not shared mutable state (practice B9).
     */
    private static final Log LOG = LogFactory.getLog(CardUpdateController.class);

    // =================================================================================================
    // WS-LITERALS - app/cbl/COCRDUPC.cbl:218-263, transcribed at their DECLARED widths.
    //
    // The widths are contract, not decoration: LIT-THISMAPSET is X(8) with a trailing space even though
    // both its receivers - CCARD-NEXT-MAPSET and CDEMO-LAST-MAPSET - are X(7), and that mismatch is what
    // makes the MOVEs at :1326 and :466 drop a byte. Normalising either width would erase a real
    // behaviour (practice B5).
    // =================================================================================================

    /** {@code LIT-THISPGM PIC X(8) VALUE 'COCRDUPC'} - {@code app/cbl/COCRDUPC.cbl:219-220}. */
    static final String LIT_THISPGM = "COCRDUPC";

    /** {@code LIT-THISTRANID PIC X(4) VALUE 'CCUP'} - {@code :221-222}; CSD transaction {@code CCUP}. */
    static final String LIT_THISTRANID = "CCUP";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COCRDUP '} - {@code :223-224}.
     *
     * <p><strong>Eight characters, the eighth a space.</strong> {@code COCRDLIC} declares its own
     * equivalent as {@code X(7)}; this program does not, and the difference is preserved (B5).
     */
    static final String LIT_THISMAPSET = "COCRDUP ";

    /** {@code LIT-THISMAP PIC X(7) VALUE 'CCRDUPA'} - {@code :225-226}; already the receiver's width. */
    static final String LIT_THISMAP = "CCRDUPA";

    /**
     * {@code LIT-CCLISTPGM PIC X(8) VALUE 'COCRDLIC'} - {@code :227-228}.
     *
     * <p>Tested at {@code :483} and {@code :485}: arriving from the card-list screen means the search
     * keys are already validated, so the card is fetched immediately rather than prompted for.
     */
    static final String LIT_CCLISTPGM = "COCRDLIC";

    /** {@code LIT-CCLISTTRANID PIC X(4) VALUE 'CCLI'} - {@code :229-230}; declared, never used (B5). */
    static final String LIT_CCLISTTRANID = "CCLI";

    /**
     * {@code LIT-CCLISTMAPSET PIC X(7) VALUE 'COCRDLI'} - {@code :231-232}.
     *
     * <p>The one card-list literal this program does use, at {@code :437}, {@code :439}, {@code :459}
     * and {@code :1238}: it decides whether finishing an update returns to the list, and whether the
     * two search fields are painted in the default colour.
     */
    static final String LIT_CCLISTMAPSET = "COCRDLI";

    /**
     * {@code LIT-CCLISTMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :233-234}.
     *
     * <p><strong>The preserved defect.</strong> This names the card <em>detail</em> map; the card
     * list's is {@code 'CCRDLIA'} ({@code app/cbl/COCRDLIC.cbl:185}). Reproduced verbatim and never
     * corrected - see the class documentation (practices B4 and B5). Harmless here because the literal
     * is never referenced in the {@code PROCEDURE DIVISION}, and kept because deleting a dead
     * declaration is itself a change.
     */
    static final String LIT_CCLISTMAP = "CCRDSLA";

    /**
     * {@code LIT-MENUPGM PIC X(8) VALUE 'COMEN01C'} - {@code :235-236}.
     *
     * <p>Used three times and each time load-bearing: {@code :389} (a fresh arrival from the menu
     * discards whatever the menu passed), {@code :451} (the default {@code XCTL} target) and
     * {@code :504} (the prompt-for-keys arm).
     */
    static final String LIT_MENUPGM = "COMEN01C";

    /** {@code LIT-MENUTRANID PIC X(4) VALUE 'CM00'} - {@code :237-238}; the default target transaction. */
    static final String LIT_MENUTRANID = "CM00";

    /** {@code LIT-MENUMAPSET PIC X(7) VALUE 'COMEN01'} - {@code :239-240}; declared, never used (B5). */
    static final String LIT_MENUMAPSET = "COMEN01";

    /** {@code LIT-MENUMAP PIC X(7) VALUE 'COMEN1A'} - {@code :241-242}; declared, never used (B5). */
    static final String LIT_MENUMAP = "COMEN1A";

    /** {@code LIT-CARDDTLPGM PIC X(8) VALUE 'COCRDSLC'} - {@code :243-244}; declared, never used (B5). */
    static final String LIT_CARDDTLPGM = "COCRDSLC";

    /** {@code LIT-CARDDTLTRANID PIC X(4) VALUE 'CCDL'} - {@code :245-246}; declared, never used (B5). */
    static final String LIT_CARDDTLTRANID = "CCDL";

    /** {@code LIT-CARDDTLMAPSET PIC X(7) VALUE 'COCRDSL'} - {@code :247-248}; declared, never used (B5). */
    static final String LIT_CARDDTLMAPSET = "COCRDSL";

    /**
     * {@code LIT-CARDDTLMAP PIC X(7) VALUE 'CCRDSLA'} - {@code :249-250}; declared, never used (B5).
     *
     * <p>Note that this holds the <em>same</em> value as {@link #LIT_CCLISTMAP}, which is exactly why
     * that literal's defect is so easy to overlook: {@code 'CCRDSLA'} is genuinely correct here.
     */
    static final String LIT_CARDDTLMAP = "CCRDSLA";

    /**
     * {@code LIT-CARDFILENAME PIC X(8) VALUE 'CARDDAT '} - {@code :251-252}; the CICS {@code FILE}
     * name of the {@code CARDDAT} base cluster, used at {@code :1383}, {@code :1408} and {@code :1428}.
     *
     * <p>Borrowed from {@link CardRepository#BASE_CICS_FILE_NAME} rather than restated, so there is one
     * definition; the class initialiser below pins it to the value the COBOL declares. It is the CICS
     * resource name and <strong>not</strong> a dataset name: the physical {@code DSNAME} the CSD binds it
     * to is resolved from {@code application.yml}, and no dataset-name literal of any kind appears
     * anywhere in this file (gate G46). The prefix itself is deliberately not written out here either, so
     * that a compliance scan for it cannot match even a comment.
     */
    static final String LIT_CARDFILENAME = CardRepository.BASE_CICS_FILE_NAME;

    /**
     * {@code LIT-CARDFILENAME-ACCT-PATH PIC X(8) VALUE 'CARDAIX '} - {@code :253-254}; declared and
     * never used by this program (B5).
     *
     * <p>{@code COCRDUPC} reads only by card number: the {@code MOVE CC-ACCT-ID-N TO
     * WS-CARD-RID-ACCT-ID} that would have prepared an alternate-index read is commented out at both
     * {@code :1379} and {@code :1424}. The literal is kept because the declaration is.
     */
    static final String LIT_CARDFILENAME_ACCT_PATH = CardRepository.ALTERNATE_INDEX_CICS_FILE_NAME;

    /**
     * {@code LIT-ALL-ALPHA-FROM PIC X(52)} - {@code :255-257}, the twenty-six upper-case letters
     * followed by the twenty-six lower-case ones.
     *
     * <p>Together with {@link #LIT_ALL_SPACES_TO} this backs the
     * {@code INSPECT ... CONVERTING} at {@code :824-826} that erases every letter from a candidate
     * card name so that whatever remains can be tested for emptiness.
     */
    static final String LIT_ALL_ALPHA_FROM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** {@code LIT-ALL-SPACES-TO PIC X(52) VALUE SPACES} - {@code :258-259}; the paired replacement set. */
    static final String LIT_ALL_SPACES_TO = " ".repeat(LIT_ALL_ALPHA_FROM.length());

    /**
     * {@code LIT-UPPER PIC X(26) VALUE 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'} - {@code :260-261}.
     *
     * <p>The {@code TO} operand of the {@code INSPECT ... CONVERTING} at {@code :1356-1358} that folds
     * a stored embossed name to upper case before it is compared or displayed. Borrowed from
     * {@link CardUpdateService#LIT_UPPER}, which owns the same pair for {@code 9300}'s fold, so the
     * two paragraphs cannot drift apart.
     */
    static final String LIT_UPPER = CardUpdateService.LIT_UPPER;

    /** {@code LIT-LOWER PIC X(26) VALUE 'abcdefghijklmnopqrstuvwxyz'} - {@code :262-263}; the {@code FROM}. */
    static final String LIT_LOWER = CardUpdateService.LIT_LOWER;

    // =================================================================================================
    // Declared widths. Every one is transcribed from a PICTURE clause or borrowed from the type that
    // owns it, so no width in this file is inferred, defaulted or shared with another screen.
    // =================================================================================================

    /** {@code WS-TRANID PIC X(4) VALUE SPACES} - {@code app/cbl/COCRDUPC.cbl:45-46}. */
    static final int WS_TRANID_LENGTH = 4;

    /** {@code WS-UCTRANS PIC X(4) VALUE SPACES} - {@code :47-48}; declared, never referenced (B5). */
    static final int WS_UCTRANS_LENGTH = 4;

    /** {@code CARD-NAME-CHECK PIC X(50) VALUE LOW-VALUES} - {@code :87-88}; the {@code INSPECT} subject. */
    static final int CARD_NAME_CHECK_LENGTH = 50;

    /** {@code CARD-MONTH-CHECK PIC X(2)} with {@code CARD-MONTH-CHECK-N REDEFINES ... PIC 9(2)} - {@code :92-95}. */
    static final int CARD_MONTH_CHECK_LENGTH = 2;

    /** {@code CARD-YEAR-CHECK PIC X(4)} with {@code CARD-YEAR-CHECK-N REDEFINES ... PIC 9(4)} - {@code :96-99}. */
    static final int CARD_YEAR_CHECK_LENGTH = 4;

    /** {@code WS-LONG-MSG PIC X(500)} - {@code :156}; declared, never referenced by this program (B5). */
    static final int WS_LONG_MSG_LENGTH = 500;

    /** {@code WS-INFO-MSG PIC X(40)} - {@code :157}; also the declared width of {@code INFOMSGO}. */
    static final int WS_INFO_MSG_LENGTH = CardUpdateRequest.INFOMSG_LENGTH;

    /**
     * {@code WS-RETURN-MSG PIC X(75)} - {@code :173}.
     *
     * <p>Borrowed from {@link CardScreenState#CCARD_RETURN_MSG_LENGTH} because
     * {@code app/cpy/CVCRD01Y.cpy:28-29} declares both {@code CCARD-ERROR-MSG} and
     * {@code CCARD-RETURN-MSG} at the same width, and {@code :547} moves this field into the first of
     * them. The two are different fields with the same width, not one field - see the class
     * documentation on {@code WS-RETURN-MSG-OFF}.
     */
    static final int WS_RETURN_MSG_LENGTH = CardScreenState.CCARD_RETURN_MSG_LENGTH;

    /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :129}; the {@code RIDFLD} of the read at {@code :1384}. */
    static final int WS_CARD_RID_CARDNUM_LENGTH = CardScreenState.CC_CARD_NUM_LENGTH;

    /**
     * {@code WS-CARD-RID-ACCT-ID PIC 9(11)} with {@code WS-CARD-RID-ACCT-ID-X REDEFINES ... PIC X(11)}
     * - {@code :130-132}.
     *
     * <p>Never assigned: the only two statements that would have written it, at {@code :1379} and
     * {@code :1424}, are both commented out. So it holds eleven zeros - {@code PIC 9}, so
     * {@code INITIALIZE} gives zeros rather than spaces - for the whole of every task (B5).
     */
    static final int WS_CARD_RID_ACCT_ID_LENGTH = CardScreenState.CC_ACCT_ID_LENGTH;

    /** {@code 01 WS-COMMAREA PIC X(2000)} - {@code :324}; the area {@code COMMON-RETURN} returns. */
    static final int WS_COMMAREA_LENGTH = 2000;

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA} - {@code :274-321}: {@value CommArea#RECORD_LENGTH} bytes.
     *
     * <p>Borrowed from {@link CommArea#RECORD_LENGTH} and pinned below, because this number is the
     * offset arithmetic of {@code :398-400} and {@code :551-552} and a drift in it would silently
     * shift every byte of the returned trailer.
     */
    static final int THIS_PROGCOMMAREA_LENGTH = CommArea.RECORD_LENGTH;

    /**
     * The length CICS reports in {@code EIBCALEN} when a communication area was passed: the 160-byte
     * {@code CARDDEMO-COMMAREA} followed by this program's {@value CommArea#RECORD_LENGTH}-byte
     * trailer, which is exactly what {@code :396-400} reads out of {@code DFHCOMMAREA}.
     */
    static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + THIS_PROGCOMMAREA_LENGTH;

    /** {@code IF EIBCALEN IS EQUAL TO 0} - {@code :388}; the cold start. */
    static final int NO_COMMAREA_LENGTH = 0;

    // =================================================================================================
    // The seven one-character edit flags of WS-MISC-STORAGE - app/cbl/COCRDUPC.cbl:53-86.
    //
    // Each is PIC X(1) carrying three or two condition names. The VALUES are transcribed rather than
    // turned into enums because INITIALIZE sets these items to SPACE, which satisfies the *-BLANK names
    // but none of INPUT-OK / INPUT-ERROR / INPUT-PENDING - a Java enum has no such "in none of my
    // states" value, and that state is load-bearing on entry.
    // =================================================================================================

    /** {@code 88 INPUT-OK VALUE '0'} - {@code :54}. */
    static final String INPUT_OK = "0";

    /** {@code 88 INPUT-ERROR VALUE '1'} - {@code :55}. */
    static final String INPUT_ERROR = "1";

    /**
     * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - {@code :56}; binary {@code x'00'}, not a space.
     *
     * <p>Declared and never tested by this program (B5). It is reachable only if something writes
     * {@code LOW-VALUES} into the flag, and nothing does: {@code INITIALIZE} writes a space.
     */
    static final String INPUT_PENDING = "\u0000";

    /**
     * {@code 88 FLG-*-NOT-OK VALUE '0'} - the shared "rejected" value of all six field flags
     * ({@code :58}, {@code :62}, {@code :66}, {@code :70}, {@code :74}, {@code :78}).
     */
    static final String FLG_FILTER_NOT_OK = "0";

    /** {@code 88 FLG-*-ISVALID VALUE '1'} - {@code :59}, {@code :63}, {@code :67}, {@code :71}, {@code :75}, {@code :79}. */
    static final String FLG_FILTER_ISVALID = "1";

    /**
     * {@code 88 FLG-*-BLANK VALUE ' '} - {@code :60}, {@code :64}, {@code :68}, {@code :72},
     * {@code :76}, {@code :80}.
     *
     * <p>A space, which is also what {@code INITIALIZE} writes - so on entry every one of the six
     * fields reads as blank, and {@link #positionCursor3300} takes its first matching arm accordingly.
     */
    static final String FLG_FILTER_BLANK = " ";

    /** {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES} - {@code :82}; declared, never tested (B5). */
    static final String WS_RETURN_FLAG_OFF = "\u0000";

    /** {@code 88 WS-RETURN-FLAG-ON VALUE '1'} - {@code :83}; declared, never tested (B5). */
    static final String WS_RETURN_FLAG_ON = "1";

    /** {@code 88 PFK-VALID VALUE '0'} - {@code :85}. */
    static final String PFK_VALID = "0";

    /** {@code 88 PFK-INVALID VALUE '1'} - {@code :86}. */
    static final String PFK_INVALID = "1";

    /**
     * {@code FLG-YES-NO-CHECK PIC X(1) VALUE 'N'} - {@code :89-90}.
     *
     * <p>The {@code VALUE} clause matters only until the first {@code INITIALIZE}, which ignores it and
     * writes a space; {@code 1240-EDIT-CARDSTATUS} always assigns the field before testing it, so the
     * initial content is never read. Transcribed anyway (B5).
     */
    static final String FLG_YES_NO_CHECK_INITIAL = "N";

    /** {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} - {@code :91}; the first of the two accepted values. */
    static final String CARD_STATUS_YES = "Y";

    /** {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} - {@code :91}; the second. */
    static final String CARD_STATUS_NO = "N";

    /** {@code 88 VALID-MONTH VALUES 1 THRU 12} - {@code :95}; the inclusive lower bound. */
    static final int VALID_MONTH_MINIMUM = 1;

    /** {@code 88 VALID-MONTH VALUES 1 THRU 12} - {@code :95}; the inclusive upper bound. */
    static final int VALID_MONTH_MAXIMUM = 12;

    /** {@code 88 VALID-YEAR VALUES 1950 THRU 2099} - {@code :99}; the inclusive lower bound. */
    static final int VALID_YEAR_MINIMUM = 1950;

    /** {@code 88 VALID-YEAR VALUES 1950 THRU 2099} - {@code :99}; the inclusive upper bound. */
    static final int VALID_YEAR_MAXIMUM = 2099;

    /**
     * A codec over US-ASCII, used only to pad the literals below to their declared widths at class
     * initialisation.
     *
     * <p>Deliberately <em>not</em> the injected dataset codec: these are program literals from the
     * COBOL source, not dataset bytes, and their padding is pure right-fill with spaces, which is
     * identical under US-ASCII and IBM037. Using the injected one would make a {@code static}
     * initialiser depend on an instance, which is impossible, and would tie a compile-time constant to
     * a deployment-time code page. Immutable and stateless (practice B9).
     */
    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // WS-INFO-MSG and its condition names - app/cbl/COCRDUPC.cbl:157-171.
    //
    // Each is padded to the field's declared X(40) here rather than at the point of use, so a
    // mistranscribed literal is a class-load failure rather than a screen that differs by trailing
    // bytes. SETting an 88-level on an alphanumeric item assigns the literal and space-fills the
    // remainder, which is exactly what movePicX does.
    // =================================================================================================

    /** {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code :158-159}; the SPACES form. */
    static final String WS_INFO_MSG_SPACES = CardScreenState.spaces(WS_INFO_MSG_LENGTH);

    /** {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code :158-159}; the LOW-VALUES form. */
    static final String WS_INFO_MSG_LOW_VALUES = CardScreenState.lowValues(WS_INFO_MSG_LENGTH);

    /** {@code 88 FOUND-CARDS-FOR-ACCOUNT VALUE 'Details of selected card shown above'} - {@code :160-161}. */
    static final String FOUND_CARDS_FOR_ACCOUNT = PIC_X_CODEC.movePicX(
            "Details of selected card shown above", WS_INFO_MSG_LENGTH);

    /** {@code 88 PROMPT-FOR-SEARCH-KEYS VALUE 'Please enter Account and Card Number'} - {@code :162-163}. */
    static final String PROMPT_FOR_SEARCH_KEYS = PIC_X_CODEC.movePicX(
            "Please enter Account and Card Number", WS_INFO_MSG_LENGTH);

    /**
     * {@code 88 PROMPT-FOR-CHANGES VALUE 'Update card details presented above.'} - {@code :164-165}.
     *
     * <p>The trailing full stop is part of the literal.
     */
    static final String PROMPT_FOR_CHANGES = PIC_X_CODEC.movePicX(
            "Update card details presented above.", WS_INFO_MSG_LENGTH);

    /**
     * {@code 88 PROMPT-FOR-CONFIRMATION VALUE 'Changes validated.Press F5 to save'} - {@code :166-167}.
     *
     * <p>No space after the full stop, exactly as the source has it. This is also the one message that
     * brightens the {@code FKEYSC} field at {@code :1315-1317}.
     */
    static final String PROMPT_FOR_CONFIRMATION = PIC_X_CODEC.movePicX(
            "Changes validated.Press F5 to save", WS_INFO_MSG_LENGTH);

    /** {@code 88 CONFIRM-UPDATE-SUCCESS VALUE 'Changes committed to database'} - {@code :168-169}. */
    static final String CONFIRM_UPDATE_SUCCESS = PIC_X_CODEC.movePicX(
            "Changes committed to database", WS_INFO_MSG_LENGTH);

    /** {@code 88 INFORM-FAILURE VALUE 'Changes unsuccessful. Please try again'} - {@code :170-171}. */
    static final String INFORM_FAILURE = PIC_X_CODEC.movePicX(
            "Changes unsuccessful. Please try again", WS_INFO_MSG_LENGTH);

    // =================================================================================================
    // WS-RETURN-MSG and its nineteen condition names - app/cbl/COCRDUPC.cbl:173-214.
    //
    // Padded to X(75) at class initialisation, as SET on an alphanumeric item does. Seven of the
    // nineteen are declared and NEVER SET by this program - verified by searching the whole PROCEDURE
    // DIVISION - and every one of those seven is transcribed regardless, because a dead declaration is
    // still behaviour and deleting it is a change (practice B5). Each is marked below.
    //
    // Note that three of them are also TESTED as conditions rather than only assigned:
    // NO-CHANGES-DETECTED at :685, :973 and :1213, and COULD-NOT-LOCK-FOR-UPDATE /
    // LOCKED-BUT-UPDATE-FAILED / DATA-WAS-CHANGED-BEFORE-UPDATE in the inner EVALUATE at :993-997.
    // Those tests compare the CONTENT of WS-RETURN-MSG, which is why the literals have to be exact.
    // =================================================================================================

    /**
     * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - {@code :174}: seventy-five <strong>spaces</strong>.
     *
     * <p>Not {@code LOW-VALUES}. {@code app/cpy/CVCRD01Y.cpy:30} declares a similarly named
     * {@code CCARD-RETURN-MSG-OFF} whose value <em>is</em> {@code LOW-VALUES}, and this program never
     * references it. See the class documentation.
     */
    static final String WS_RETURN_MSG_OFF = CardUpdateService.RETURN_MESSAGE_OFF;

    /**
     * {@code 88 WS-EXIT-MESSAGE VALUE 'PF03 pressed.Exiting              '} - {@code :175-176};
     * declared and never set (B5).
     *
     * <p>The literal carries fourteen trailing spaces inside the quotes. They are transcribed and then
     * absorbed by the pad to {@code X(75)}, which is what the COBOL {@code SET} would also do.
     */
    static final String WS_EXIT_MESSAGE = PIC_X_CODEC.movePicX(
            "PF03 pressed.Exiting              ", WS_RETURN_MSG_LENGTH);

    /** {@code 88 WS-PROMPT-FOR-ACCT VALUE 'Account number not provided'} - {@code :177-178}; set at {@code :731}. */
    static final String WS_PROMPT_FOR_ACCT = PIC_X_CODEC.movePicX(
            "Account number not provided", WS_RETURN_MSG_LENGTH);

    /** {@code 88 WS-PROMPT-FOR-CARD VALUE 'Card number not provided'} - {@code :179-180}; set at {@code :774}. */
    static final String WS_PROMPT_FOR_CARD = PIC_X_CODEC.movePicX(
            "Card number not provided", WS_RETURN_MSG_LENGTH);

    /** {@code 88 WS-PROMPT-FOR-NAME VALUE 'Card name not provided'} - {@code :181-182}; set at {@code :817}. */
    static final String WS_PROMPT_FOR_NAME = PIC_X_CODEC.movePicX(
            "Card name not provided", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 WS-NAME-MUST-BE-ALPHA VALUE 'Card name can only contain alphabets and spaces'} -
     * {@code :183-184}; set at {@code :834}.
     */
    static final String WS_NAME_MUST_BE_ALPHA = PIC_X_CODEC.movePicX(
            "Card name can only contain alphabets and spaces", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 NO-SEARCH-CRITERIA-RECEIVED VALUE 'No input received'} - {@code :185-186}; set at
     * {@code :658}, when neither search field was supplied.
     */
    static final String NO_SEARCH_CRITERIA_RECEIVED = PIC_X_CODEC.movePicX(
            "No input received", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 NO-CHANGES-DETECTED VALUE 'No change detected with respect to values fetched.'} -
     * {@code :187-188}; set at {@code :682} and <strong>tested</strong> at {@code :685}, {@code :973}
     * and {@code :1213}.
     *
     * <p>The trailing full stop is part of the literal, and the test at {@code :973} is what stops a
     * confirmation being requested for an unchanged record.
     */
    static final String NO_CHANGES_DETECTED = PIC_X_CODEC.movePicX(
            "No change detected with respect to values fetched.", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 SEARCHED-ACCT-ZEROES VALUE 'Account number must be a non zero 11 digit number'} -
     * {@code :189-190}; declared and never set (B5).
     *
     * <p>{@code 1210-EDIT-ACCOUNT} does not use this name. Its not-numeric arm at {@code :744-746}
     * performs a direct {@code MOVE} of a <em>different</em> 52-character literal - see
     * {@link #ACCOUNT_FILTER_MUST_BE_11_DIGITS} - and its blank arm sets
     * {@link #WS_PROMPT_FOR_ACCT}. So neither of the two identical account literals below ever reaches
     * a screen.
     */
    static final String SEARCHED_ACCT_ZEROES = PIC_X_CODEC.movePicX(
            "Account number must be a non zero 11 digit number", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 SEARCHED-ACCT-NOT-NUMERIC VALUE 'Account number must be a non zero 11 digit number'} -
     * {@code :191-192}; declared and never set (B5).
     *
     * <p>Byte-identical to {@link #SEARCHED_ACCT_ZEROES}: two condition names over one value, which is
     * legal and is transcribed as written rather than collapsed into one constant.
     */
    static final String SEARCHED_ACCT_NOT_NUMERIC = SEARCHED_ACCT_ZEROES;

    /**
     * {@code 88 SEARCHED-CARD-NOT-NUMERIC VALUE 'Card number if supplied must be a 16 digit number'} -
     * {@code :193-194}; declared and never set (B5).
     *
     * <p>{@code 1220-EDIT-CARD} uses a direct {@code MOVE} of
     * {@link #CARD_ID_FILTER_MUST_BE_16_DIGITS} instead.
     */
    static final String SEARCHED_CARD_NOT_NUMERIC = PIC_X_CODEC.movePicX(
            "Card number if supplied must be a 16 digit number", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 CARD-STATUS-MUST-BE-YES-NO VALUE 'Card Active Status must be Y or N'} -
     * {@code :195-196}; set at {@code :856} (blank) and {@code :869} (neither Y nor N).
     */
    static final String CARD_STATUS_MUST_BE_YES_NO = PIC_X_CODEC.movePicX(
            "Card Active Status must be Y or N", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 CARD-EXPIRY-MONTH-NOT-VALID VALUE 'Card expiry month must be between 1 and 12'} -
     * {@code :197-198}; set at {@code :889} (blank) and {@code :904} (out of range).
     */
    static final String CARD_EXPIRY_MONTH_NOT_VALID = PIC_X_CODEC.movePicX(
            "Card expiry month must be between 1 and 12", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 CARD-EXPIRY-YEAR-NOT-VALID VALUE 'Invalid card expiry year'} - {@code :199-200}; set at
     * {@code :922} (blank) and {@code :940} (out of range).
     */
    static final String CARD_EXPIRY_YEAR_NOT_VALID = PIC_X_CODEC.movePicX(
            "Invalid card expiry year", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DID-NOT-FIND-ACCT-IN-CARDXREF VALUE 'Did not find this account in cards database'} -
     * {@code :201-202}; declared and never set (B5).
     *
     * <p>This program has no cross-reference paragraph at all - {@code COPY CVACT03Y} is commented out
     * at {@code :356} - so the message it would have carried has no site to be set from. Its sibling
     * {@code COCRDSLC} does set it, from {@code 9150-GETCARD-BYACCT}.
     */
    static final String DID_NOT_FIND_ACCT_IN_CARDXREF = PIC_X_CODEC.movePicX(
            "Did not find this account in cards database", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DID-NOT-FIND-ACCTCARD-COMBO VALUE 'Did not find cards for this search condition'} -
     * {@code :203-204}; set at {@code :1400}, and only when the return message is still off.
     */
    static final String DID_NOT_FIND_ACCTCARD_COMBO = PIC_X_CODEC.movePicX(
            "Did not find cards for this search condition", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 COULD-NOT-LOCK-FOR-UPDATE VALUE 'Could not lock record for update'} -
     * {@code :205-206}; set inside {@code 9200} at {@code :1446} and <strong>tested</strong> at
     * {@code :993}.
     *
     * <p>Borrowed from {@link CardUpdateService#MSG_COULD_NOT_LOCK_FOR_UPDATE} and padded here, because
     * the service owns the assignment and this class owns the test - and if the two spellings ever
     * diverged the first arm of the inner {@code EVALUATE} would silently stop matching.
     */
    static final String COULD_NOT_LOCK_FOR_UPDATE = PIC_X_CODEC.movePicX(
            CardUpdateService.MSG_COULD_NOT_LOCK_FOR_UPDATE, WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE VALUE 'Record changed by some one else. Please review'}
     * - {@code :207-208}; set inside {@code 9300} at {@code :1511} and <strong>tested</strong> at
     * {@code :997} and {@code :1455}. Borrowed from
     * {@link CardUpdateService#MSG_DATA_WAS_CHANGED_BEFORE_UPDATE}.
     */
    static final String DATA_WAS_CHANGED_BEFORE_UPDATE = PIC_X_CODEC.movePicX(
            CardUpdateService.MSG_DATA_WAS_CHANGED_BEFORE_UPDATE, WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 LOCKED-BUT-UPDATE-FAILED VALUE 'Update of record failed'} - {@code :209-210}; set
     * inside {@code 9200} at {@code :1491} and <strong>tested</strong> at {@code :995}. Borrowed from
     * {@link CardUpdateService#MSG_LOCKED_BUT_UPDATE_FAILED}.
     */
    static final String LOCKED_BUT_UPDATE_FAILED = PIC_X_CODEC.movePicX(
            CardUpdateService.MSG_LOCKED_BUT_UPDATE_FAILED, WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 XREF-READ-ERROR VALUE 'Error reading Card Data File'} - {@code :211-212}; declared and
     * never set (B5). The read failure at {@code :1402-1411} composes
     * {@code WS-FILE-ERROR-MESSAGE} instead.
     */
    static final String XREF_READ_ERROR = PIC_X_CODEC.movePicX(
            "Error reading Card Data File", WS_RETURN_MSG_LENGTH);

    /**
     * {@code 88 CODING-TO-BE-DONE VALUE 'Looks Good.... so far'} - {@code :213-214}; declared and never
     * set (B5). A development leftover, kept exactly as found rather than tidied away.
     */
    static final String CODING_TO_BE_DONE = PIC_X_CODEC.movePicX(
            "Looks Good.... so far", WS_RETURN_MSG_LENGTH);

    /**
     * {@code MOVE 'ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER' TO WS-RETURN-MSG} -
     * {@code app/cbl/COCRDUPC.cbl:744-746}.
     *
     * <p>A direct {@code MOVE} of a 52-character literal, <strong>not</strong> a condition name, and
     * not the same text as {@link #SEARCHED_ACCT_NOT_NUMERIC} which was declared for the purpose. No
     * space after the comma, and "a 11" rather than "an 11" - both transcribed as written.
     */
    static final String ACCOUNT_FILTER_MUST_BE_11_DIGITS = PIC_X_CODEC.movePicX(
            "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);

    /**
     * {@code MOVE 'CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER' TO WS-RETURN-MSG} -
     * {@code :788-790}. The same shape, for the card filter.
     */
    static final String CARD_ID_FILTER_MUST_BE_16_DIGITS = PIC_X_CODEC.movePicX(
            "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER", WS_RETURN_MSG_LENGTH);

    // =================================================================================================
    // The abend literals - app/cbl/COCRDUPC.cbl:1019-1026 and :1531-1552, over ABEND-DATA (CSMSG02Y).
    // =================================================================================================

    /**
     * {@code MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG} - {@code :1023-1024}.
     *
     * <p><strong>{@code ABEND-MSG}, not {@code WS-RETURN-MSG}.</strong> The sibling
     * {@code COCRDSLC:377-378} moves the identical text into {@code WS-RETURN-MSG} instead. The two
     * programs genuinely differ and this one is reproduced: the text reaches the terminal through
     * {@code EXEC CICS SEND FROM(ABEND-DATA)} at {@code :1539-1544}, never through the map's
     * {@code ERRMSGO}.
     */
    static final String UNEXPECTED_DATA_SCENARIO = PIC_X_CODEC.movePicX(
            "UNEXPECTED DATA SCENARIO", SystemMessages.ABEND_MSG_LENGTH);

    /** {@code MOVE '0001' TO ABEND-CODE} - {@code :1021}; {@code ABEND-CODE} is {@code PIC X(4)}. */
    static final String UNEXPECTED_DATA_ABEND_CODE = "0001";

    /**
     * {@code MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG} - {@code :1534}.
     *
     * <p>{@code ABEND-ROUTINE}'s default, applied only when {@code ABEND-MSG} is still
     * {@code LOW-VALUES} ({@code :1533}) - so the {@code WHEN OTHER} arm's own text survives, because
     * that arm sets {@code ABEND-MSG} before performing the routine.
     */
    static final String UNEXPECTED_ABEND_OCCURRED = PIC_X_CODEC.movePicX(
            "UNEXPECTED ABEND OCCURRED.", SystemMessages.ABEND_MSG_LENGTH);

    /** {@code EXEC CICS ABEND ABCODE('9999')} - {@code :1550-1552}; four characters, not a return code. */
    static final String ABEND_ROUTINE_ABCODE = "9999";

    /**
     * What {@link #abendRoutine} logs in place of a backend diagnostic when no exception is in flight.
     *
     * <p>Not a COBOL value. {@code 2000-DECIDE-ACTION}'s {@code WHEN OTHER} at {@code :1019-1026}
     * performs {@code ABEND-ROUTINE} on the program's own logic, so that arrival has no throwable to read
     * a {@code SQLSTATE} from. Naming the absence keeps the two arrivals - unexpected data, and a
     * data-access failure - distinguishable in a log rather than collapsing them into an empty bracket.
     */
    static final String NO_TRIGGERING_FAILURE = "no triggering failure";

    // =================================================================================================
    // WS-FILE-ERROR-MESSAGE - app/cbl/COCRDUPC.cbl:133-152. Eight literal or variable spans summing to
    // EXACTLY 80 characters, asserted at class load so a mistranscribed filler cannot pass unnoticed.
    // =================================================================================================

    /** {@code FILLER PIC X(12) VALUE 'File Error: '} - {@code :134-135}; the trailing space is the twelfth. */
    static final String FILE_ERROR_PREFIX = "File Error: ";

    /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :136-137}. */
    static final int ERROR_OPNAME_LENGTH = 8;

    /** {@code FILLER PIC X(4) VALUE ' on '} - {@code :138-139}; a space either side of "on". */
    static final String FILE_ERROR_ON = " on ";

    /** {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :140-141}; nine, one wider than an X(8) file name. */
    static final int ERROR_FILE_LENGTH = 9;

    /** {@code FILLER PIC X(15) VALUE ' returned RESP '} - {@code :142-144}; leading and trailing space. */
    static final String FILE_ERROR_RETURNED_RESP = " returned RESP ";

    /** {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :145-146}; also the width of {@code ERROR-RESP2}. */
    static final int ERROR_RESP_LENGTH = 10;

    /** {@code FILLER PIC X(7) VALUE ',RESP2 '} - {@code :147-148}; no space after the comma. */
    static final String FILE_ERROR_RESP2 = ",RESP2 ";

    /** {@code FILLER PIC X(5) VALUE SPACES} - {@code :151-152}; the trailer that the {@code X(75)} move drops. */
    static final String FILE_ERROR_TRAILER = "     ";

    /**
     * The digit count of {@code WS-RESP-CD PIC S9(09) COMP} - {@code :41-42} - when it is moved into an
     * alphanumeric receiver, which zero-fills it to nine digits before the pad to {@code X(10)}.
     */
    static final int RESP_CODE_DIGITS = 9;

    /**
     * The full width of {@code WS-FILE-ERROR-MESSAGE}: <strong>exactly 80</strong> characters.
     *
     * <p>Computed from the parts - 12 + 8 + 4 + 9 + 15 + 10 + 7 + 10 + 5 - rather than written as
     * {@code 80}, so a mistranscribed span is caught rather than hidden.
     * {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} at {@code :1411} then narrows it to
     * {@value #WS_RETURN_MSG_LENGTH}, discarding the five-space trailer.
     */
    static final int FILE_ERROR_MESSAGE_LENGTH =
            FILE_ERROR_PREFIX.length() + ERROR_OPNAME_LENGTH + FILE_ERROR_ON.length()
                    + ERROR_FILE_LENGTH + FILE_ERROR_RETURNED_RESP.length() + ERROR_RESP_LENGTH
                    + FILE_ERROR_RESP2.length() + ERROR_RESP_LENGTH + FILE_ERROR_TRAILER.length();

    /** {@code MOVE 'READ' TO ERROR-OPNAME} - {@code :1407}; the only operation name this program names. */
    static final String READ_OPERATION_NAME = CardRepository.READ_OPERATION_NAME;

    static {
        if (FILE_ERROR_MESSAGE_LENGTH != 80) {
            throw new AssertionError("WS-FILE-ERROR-MESSAGE must be exactly 80 characters, as "
                    + "app/cbl/COCRDUPC.cbl:133-152 declares it, but the transcribed parts sum to "
                    + FILE_ERROR_MESSAGE_LENGTH + "; a filler has been mistranscribed");
        }
        if (THIS_PROGCOMMAREA_LENGTH != 329) {
            throw new AssertionError("WS-THIS-PROGCOMMAREA must be exactly 329 bytes - "
                    + "CCUP-CHANGE-ACTION (1) + CCUP-OLD-DETAILS (89) + CCUP-NEW-DETAILS (89) + "
                    + "CARD-UPDATE-RECORD (150, the FILLER X(59) at app/cbl/COCRDUPC.cbl:321 "
                    + "included) - but CardUpdateRequest.CommArea reports "
                    + THIS_PROGCOMMAREA_LENGTH);
        }
        if (PASSED_COMMAREA_LENGTH != 489) {
            throw new AssertionError("The passed commarea must be exactly 489 bytes - "
                    + "CARDDEMO-COMMAREA (160) followed by WS-THIS-PROGCOMMAREA (329) - but the parts "
                    + "sum to " + PASSED_COMMAREA_LENGTH);
        }
        // The literals this class borrows rather than restates. Borrowing keeps one definition, and
        // pinning it here means a change on either side is reported at class load rather than silently
        // altering a key, a message or a comparison the inner EVALUATE depends on.
        requireLiteral(LIT_CARDFILENAME, "CARDDAT ", "LIT-CARDFILENAME", 252);
        requireLiteral(LIT_CARDFILENAME_ACCT_PATH, "CARDAIX ", "LIT-CARDFILENAME-ACCT-PATH", 254);
        requireLiteral(READ_OPERATION_NAME, "READ", "ERROR-OPNAME's only value", 1407);
        requireLiteral(LIT_UPPER, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", "LIT-UPPER", 261);
        requireLiteral(LIT_LOWER, "abcdefghijklmnopqrstuvwxyz", "LIT-LOWER", 263);
        requireLiteral(WS_RETURN_MSG_OFF, " ".repeat(75), "WS-RETURN-MSG-OFF (SPACES)", 174);
        // COPY COTTL01Y at :332 -> ScreenTitles, one of the six universal online imports every
        // controller carries. The two headings reach the map through
        // CardUpdateResponse.applyScreenTitles(), which owns the PIC X(40) move, so this class does not
        // restate them - but it does pin them, here, in the file that copies the copybook. That keeps
        // the COPY-to-import correspondence auditable at the point of use (practice B8) and turns a
        // drift in either heading into a class-load failure naming the copybook line rather than a
        // silently shifted screen.
        requireLiteral(ScreenTitles.CCDA_TITLE01, "      AWS Mainframe Modernization       ",
                "CCDA-TITLE01", "app/cpy/COTTL01Y.cpy:18-19");
        requireLiteral(ScreenTitles.CCDA_TITLE02, "              CardDemo                  ",
                "CCDA-TITLE02", "app/cpy/COTTL01Y.cpy:20+22");
    }

    /**
     * Verifies a literal this class borrows from another type against the value
     * {@code app/cbl/COCRDUPC.cbl} declares, so a change on either side cannot pass unnoticed.
     *
     * @param actual    the borrowed value
     * @param expected  the value the COBOL declares
     * @param cobolName the COBOL item's name, for the failure message
     * @param cobolLine the line of {@code app/cbl/COCRDUPC.cbl} that declares it
     * @throws AssertionError if the two disagree
     */
    private static void requireLiteral(String actual, String expected, String cobolName,
            int cobolLine) {
        requireLiteral(actual, expected, cobolName, "app/cbl/COCRDUPC.cbl:" + cobolLine);
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
     * The {@code CARDDAT} base cluster, reached only by the <strong>unlocked display read</strong> of
     * {@code 9100-GETCARD-BYACCTCARD} ({@code app/cbl/COCRDUPC.cbl:1382-1390}, which has no
     * {@code UPDATE} option).
     *
     * <p>This controller never calls {@link CardRepository#readForUpdateByCardNumber(String)} or
     * {@link CardRepository#rewrite(CardRecord)}: the locked read, the concurrency check and the rewrite
     * are {@code 9200}/{@code 9300} and belong to {@link CardUpdateService}, so that a parity case can
     * assert them with no HTTP layer in the path (gate G51).
     */
    private final CardRepository cardRepository;

    /**
     * {@code 9200-WRITE-PROCESSING} and {@code 9300-CHECK-CHANGE-IN-REC} -
     * {@code app/cbl/COCRDUPC.cbl:1420-1523} - the only path in this screen that writes.
     *
     * <p>Its four outcomes are exactly the four arms of the inner {@code EVALUATE} at
     * {@code :992-1001}, and {@link #decideAction2000} maps them in that order.
     */
    private final CardUpdateService cardUpdateService;

    /**
     * The instant {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA} reads
     * ({@code app/cbl/COCRDUPC.cbl:1055} and again at {@code :1062}).
     *
     * <p>Injected rather than read inline so a parity case can fix the instant and obtain a
     * byte-identical screen. {@code Clock} is immutable and thread safe, and a {@code @Bean Clock} is
     * already published by {@code WebConfig}.
     */
    private final Clock clock;

    /**
     * The fixed-width codec, carrying the dataset code page and owning the {@code MOVE} rules.
     *
     * <p>Immutable and stateless. Held rather than created per call because a {@code Charset} lookup is
     * not free and because there must be exactly one place the code page is decided - never the
     * platform default (practice B8).
     */
    private final FixedWidthCodec codec;

    /**
     * Spring's constructor, wiring the repository, the update service, the clock and the active dataset
     * code page.
     *
     * <p>The {@code Charset} is qualified explicitly. {@code CobolCharsetConfig} publishes three -
     * EBCDIC, US-ASCII and the active dataset page - and picking the wrong one would decode every field
     * at the wrong byte, so the choice is stated at the injection point rather than left to type
     * matching.
     *
     * @param cardRepository    the card file's repository, for the unlocked display read; must not be
     *                          {@code null}
     * @param cardUpdateService {@code 9200}/{@code 9300}, the locked rewrite; must not be {@code null}
     * @param clock             the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @param datasetCharset    the active dataset code page, from
     *                          {@code @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME)}; must
     *                          not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CardUpdateController(
            CardRepository cardRepository,
            CardUpdateService cardUpdateService,
            Clock clock,
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset) {
        this.cardRepository = Objects.requireNonNull(cardRepository,
                "A CardRepository is required: 9100-GETCARD-BYACCTCARD reads CARDDAT without the "
                        + "UPDATE option, and this controller reaches it no other way");
        this.cardUpdateService = Objects.requireNonNull(cardUpdateService,
                "A CardUpdateService is required: 9200-WRITE-PROCESSING is the only path that writes, "
                        + "and it is deliberately not implemented here");
        this.clock = Objects.requireNonNull(clock,
                "A Clock is required: 3100-SCREEN-INIT reads FUNCTION CURRENT-DATE twice, and reading "
                        + "a clock inline would make every parity case non-deterministic");
        this.codec = new FixedWidthCodec(Objects.requireNonNull(datasetCharset,
                "A dataset charset is required: a fixed-width mainframe field is bytes in a specific "
                        + "code page, so the code page is always stated and never taken from the "
                        + "platform"));
    }

    /**
     * The codec this controller applies every {@code MOVE} through, carrying the active dataset code
     * page.
     *
     * <p>Exposed package-visibly for two reasons, both about verification rather than convenience. A
     * parity case has to build its expected images with the <em>same</em> code page the controller
     * decodes with - building them with a different one would compare two encodings and call the
     * difference a defect - and a deployment check needs to be able to assert which page was wired
     * without starting the application (residual risk R-E). It is not {@code public}, because a code
     * page is this module's business and no client's.
     *
     * @return the codec; never {@code null}, and immutable
     */
    FixedWidthCodec codec() {
        return codec;
    }

    // =================================================================================================
    // The per-request conversation.
    //
    // Everything COBOL kept in WORKING-STORAGE for the life of one task lives here, and one of these is
    // constructed per call. NOT ONE of these values is a field on the controller: the controller is a
    // singleton, so a WORKING-STORAGE field promoted to an instance field would leak one caller's typed
    // card details into another's screen and make every test order-dependent (practice B9, gate G53).
    //
    // Package-visible, and every member reachable, because the parity tests assert on WS-RETURN-MSG,
    // WS-INFO-MSG, CCUP-CHANGE-ACTION and the six edit flags directly - the COBOL's observable state is
    // these values, not just the rendered screen (gate G51).
    //
    // WS-THIS-PROGCOMMAREA is NOT declared here: it is CardUpdateRequest.CommArea, because it is
    // payload. :396-400 restores it from what the caller passed and :550-552 returns it, so it has to
    // travel in the request and come back in the response, and a carrier declared inside the controller
    // could not be a member of either without the dto package depending on the controller.
    // =================================================================================================

    /**
     * One task's worth of {@code WORKING-STORAGE}: {@code WS-MISC-STORAGE} in full,
     * {@code CC-WORK-AREA}, {@code CARDDEMO-COMMAREA}, {@code WS-THIS-PROGCOMMAREA} and
     * {@code WS-COMMAREA}.
     *
     * <p>Deliberately mutable, because {@code WORKING-STORAGE} is: the program assigns into it
     * throughout processing. An instance is consequently <strong>not</strong> thread safe and is
     * confined to the request that created it, exactly as a CICS task's storage is confined to its task.
     */
    static final class Conversation {

        // ---- WS-CICS-PROCESSNG-VARS, app/cbl/COCRDUPC.cbl:40-48 -------------------------------------

        /** {@code WS-RESP-CD PIC S9(09) COMP VALUE ZEROS} - {@code :41-42}; the CICS {@code RESP}. */
        int wsRespCd;

        /** {@code WS-REAS-CD PIC S9(09) COMP VALUE ZEROS} - {@code :43-44}; the CICS {@code RESP2}. */
        int wsReasCd;

        /** {@code WS-TRANID PIC X(4) VALUE SPACES} - {@code :45-46}; set to {@code 'CCUP'} at {@code :380}. */
        String wsTranid;

        /** {@code WS-UCTRANS PIC X(4) VALUE SPACES} - {@code :47-48}; declared, never referenced (B5). */
        String wsUctrans;

        // ---- The seven edit flags, app/cbl/COCRDUPC.cbl:53-86 ---------------------------------------

        /** {@code WS-INPUT-FLAG PIC X(1)} - {@code :53}; carries {@code INPUT-OK} / {@code INPUT-ERROR}. */
        String wsInputFlag;

        /** {@code WS-EDIT-ACCT-FLAG PIC X(1)} - {@code :57}; the three {@code FLG-ACCTFILTER-*} names. */
        String wsEditAcctFlag;

        /** {@code WS-EDIT-CARD-FLAG PIC X(1)} - {@code :61}; the three {@code FLG-CARDFILTER-*} names. */
        String wsEditCardFlag;

        /** {@code WS-EDIT-CARDNAME-FLAG PIC X(1)} - {@code :65}; the three {@code FLG-CARDNAME-*} names. */
        String wsEditCardnameFlag;

        /** {@code WS-EDIT-CARDSTATUS-FLAG PIC X(1)} - {@code :69}; the three {@code FLG-CARDSTATUS-*} names. */
        String wsEditCardstatusFlag;

        /** {@code WS-EDIT-CARDEXPMON-FLAG PIC X(1)} - {@code :73}; the three {@code FLG-CARDEXPMON-*} names. */
        String wsEditCardexpmonFlag;

        /** {@code WS-EDIT-CARDEXPYEAR-FLAG PIC X(1)} - {@code :77}; the three {@code FLG-CARDEXPYEAR-*} names. */
        String wsEditCardexpyearFlag;

        /** {@code WS-RETURN-FLAG PIC X(1)} - {@code :81}; declared by the program and never tested (B5). */
        String wsReturnFlag;

        /** {@code WS-PFK-FLAG PIC X(1)} - {@code :84}; carries {@code PFK-VALID} / {@code PFK-INVALID}. */
        String wsPfkFlag;

        // ---- The four scratch check items, app/cbl/COCRDUPC.cbl:87-99 -------------------------------

        /**
         * {@code CARD-NAME-CHECK PIC X(50) VALUE LOW-VALUES} - {@code :87-88}.
         *
         * <p>The subject of the {@code INSPECT ... CONVERTING} at {@code :824-826}. Its
         * {@code VALUE LOW-VALUES} is overwritten with spaces by {@code INITIALIZE}, which ignores
         * {@code VALUE} clauses, and is then always assigned before use.
         */
        String cardNameCheck;

        /** {@code FLG-YES-NO-CHECK PIC X(1) VALUE 'N'} - {@code :89-90}; tested by {@code 88 FLG-YES-NO-VALID}. */
        String flgYesNoCheck;

        /**
         * {@code CARD-MONTH-CHECK PIC X(2)} with {@code CARD-MONTH-CHECK-N REDEFINES ... PIC 9(2)} -
         * {@code :92-95}.
         *
         * <p>Held as the alphanumeric view, because that is the one {@code :896} assigns; the numeric
         * view is derived on demand by {@link #cardMonthCheckN()}, which reinterprets the same bytes
         * rather than re-parsing them.
         */
        String cardMonthCheck;

        /**
         * {@code CARD-YEAR-CHECK PIC X(4)} with {@code CARD-YEAR-CHECK-N REDEFINES ... PIC 9(4)} -
         * {@code :96-99}; the same arrangement as the month.
         */
        String cardYearCheck;

        // ---- CICS-OUTPUT-EDIT-VARS, app/cbl/COCRDUPC.cbl:103-123 ------------------------------------
        //
        // Six declared items over which four numeric REDEFINES and one group REDEFINES are laid. Of the
        // six, this program uses three: CARD-NAME-EMBOSSED-X and CARD-STATUS-X receive the fetched
        // record's values at :673-674 and CARD-EXPIRAION-DATE-X's year/month/day sub-group receives them
        // at :675-677. CARD-CVV-CD-X is written at :1464 inside 9200, which is CardUpdateService's
        // paragraph, not this class's. The rest are declared and never referenced, and they are kept
        // because deleting a dead declaration is a change (practice B5).

        /** {@code CARD-ACCT-ID-X PIC X(11)} with {@code CARD-ACCT-ID-N REDEFINES} - {@code :104-106}; unused (B5). */
        String cardAcctIdX;

        /**
         * {@code CARD-CVV-CD-X PIC X(03)} with {@code CARD-CVV-CD-N REDEFINES ... PIC 9(03)} -
         * {@code :107-109}.
         *
         * <p>The pair {@code 9200} uses at {@code :1464-1465} to reinterpret three typed characters as
         * three zoned digits. That paragraph belongs to {@link CardUpdateService}, which owns the
         * reinterpretation through {@link CardUpdateService#redefinedCvvImage(String, FixedWidthCodec)};
         * the item is declared here because the program declares it, and its round trip is asserted by
         * this class's tests (gate G34).
         */
        String cardCvvCdX;

        /** {@code CARD-CARD-NUM-X PIC X(16)} with {@code CARD-CARD-NUM-N REDEFINES} - {@code :110-112}; unused (B5). */
        String cardCardNumX;

        /** {@code CARD-NAME-EMBOSSED-X PIC X(50)} - {@code :113}; receives {@code CCUP-OLD-CRDNAME} at {@code :673}. */
        String cardNameEmbossedX;

        /** {@code CARD-STATUS-X PIC X} - {@code :114}; receives {@code CCUP-OLD-CRDSTCD} at {@code :674}. */
        String cardStatusX;

        /**
         * {@code CARD-EXPIRAION-DATE-X PIC X(10)} - {@code :115}, with <strong>two overlapping</strong>
         * {@code REDEFINES} over the same ten bytes: the group at {@code :116-121}
         * ({@code CARD-EXPIRY-YEAR X(4)} + {@code FILLER X(1)} + {@code CARD-EXPIRY-MONTH X(2)} +
         * {@code FILLER X(1)} + {@code CARD-EXPIRY-DAY X(2)}) and {@code CARD-EXPIRAION-DATE-N PIC
         * 9(10)} at {@code :122-123}. Three typed views over one span.
         *
         * <p>The misspelling is the copybook's ({@code app/cpy/CVACT02Y.cpy:9}) and is preserved (plan
         * I1). {@code 1200-EDIT-MAP-INPUTS} writes the three components individually at
         * {@code :675-677}, never the ten-byte whole, so the two {@code FILLER} bytes keep whatever
         * {@code INITIALIZE} left in them - a detail that is invisible until something reads the
         * ten-byte view, and nothing in this program does.
         */
        String cardExpiraionDateX;

        // ---- WS-CARD-RID, app/cbl/COCRDUPC.cbl:128-132 ----------------------------------------------

        /** {@code WS-CARD-RID-CARDNUM PIC X(16)} - {@code :129}; the {@code RIDFLD} of {@code :1382-1390}. */
        String wsCardRidCardnum;

        /**
         * {@code WS-CARD-RID-ACCT-ID PIC 9(11)} with {@code WS-CARD-RID-ACCT-ID-X REDEFINES ... PIC
         * X(11)} - {@code :130-132}.
         *
         * <p>Never assigned by this program: both statements that would have written it, {@code :1379}
         * and {@code :1424}, are commented out. So it holds the eleven <em>zeros</em>
         * {@code INITIALIZE} gives a {@code PIC 9} item, for the whole of every task (B5).
         */
        String wsCardRidAcctId;

        // ---- WS-FILE-ERROR-MESSAGE's variable spans, app/cbl/COCRDUPC.cbl:136-150 -------------------

        /** {@code ERROR-OPNAME PIC X(8) VALUE SPACES} - {@code :136-137}; {@code 'READ'} at {@code :1407}. */
        String errorOpname;

        /** {@code ERROR-FILE PIC X(9) VALUE SPACES} - {@code :140-141}; {@code LIT-CARDFILENAME} at {@code :1408}. */
        String errorFile;

        /** {@code ERROR-RESP PIC X(10) VALUE SPACES} - {@code :145-146}; {@code WS-RESP-CD} at {@code :1409}. */
        String errorResp;

        /** {@code ERROR-RESP2 PIC X(10) VALUE SPACES} - {@code :149-150}; {@code WS-REAS-CD} at {@code :1410}. */
        String errorResp2;

        // ---- Output message construction, app/cbl/COCRDUPC.cbl:156-214 ------------------------------

        /** {@code WS-LONG-MSG PIC X(500)} - {@code :156}; declared, never referenced (B5). */
        String wsLongMsg;

        /** {@code WS-INFO-MSG PIC X(40)} - {@code :157}; the informational line {@code 3250} chooses. */
        String wsInfoMsg;

        /** {@code WS-RETURN-MSG PIC X(75)} - {@code :173}; the error line, whose "off" state is SPACES. */
        String wsReturnMsg;

        // ---- The copied areas ------------------------------------------------------------------------

        /** {@code CC-WORK-AREA} - {@code COPY CVCRD01Y} at {@code :268}; the AID, the next-screen triple and the ids. */
        CardScreenState ccWorkArea;

        /** {@code CARDDEMO-COMMAREA} - {@code COPY COCOM01Y} at {@code :272}; 160 bytes of shared context. */
        NavigationContext carddemoCommarea;

        /** {@code WS-THIS-PROGCOMMAREA} - {@code :274-321}; {@value CommArea#RECORD_LENGTH} bytes of screen state. */
        CommArea thisProgCommarea;

        /** {@code 01 WS-COMMAREA PIC X(2000)} - {@code :324}; the area {@code :554-558} returns. */
        String wsCommarea;

        /**
         * {@code CARD-RECORD} - {@code COPY CVACT02Y} at {@code :353}; 150 bytes, the {@code INTO} of
         * the read at {@code :1386}.
         *
         * <p>{@link Optional#empty()} until a read returns one, so "no record" is a state rather than a
         * {@code null}.
         */
        Optional<CardRecord> cardRecord;

        /** {@code ABEND-DATA} - {@code COPY CSMSG02Y} at {@code :343}; filled by {@code :1019-1024}. */
        SystemMessages.AbendData abendData;

        /**
         * {@code SEC-USER-DATA} - {@code COPY CSUSR01Y} at {@code :346}; a live {@code COPY} that
         * allocates 80 bytes and that this program never reads.
         *
         * <p>Modelled because the storage exists (practice B5). No field of it is read here and
         * {@code SEC-USR-PWD} is never compared: this screen performs no authentication and none is
         * added (practice B6).
         */
        SecUserRecord secUserData;

        /**
         * {@code CUSTOMER-RECORD} - {@code COPY CVCUS01Y} at {@code :359}; a live {@code COPY} that
         * allocates 500 bytes and that this program never reads. Modelled for the same reason (B5).
         */
        CustomerRecord customerRecord;

        /**
         * {@code WS-CURDATE-DATA} and its views - {@code COPY CSDAT01Y} at {@code :337}, filled by
         * {@code MOVE FUNCTION CURRENT-DATE} at {@code :1055} and again at {@code :1062}.
         *
         * <p>{@code null} until {@code 3100-SCREEN-INIT} runs, which is faithful: the area holds
         * whatever the copybook's {@code VALUE} clauses left until the first {@code MOVE}, and nothing
         * reads it before then.
         */
        DateHeader dateHeader;

        /**
         * Whether control has already left through {@code COMMON-RETURN} or an {@code XCTL}.
         *
         * <p>Not a COBOL field. It stands in for the fact that {@code GO TO COMMON-RETURN} and
         * {@code EXEC CICS XCTL} do not come back, which a Java {@code return} from a nested method
         * cannot express on its own.
         */
        boolean returned;

        /**
         * The {@code DFHMDF} label whose {@code xxxL} item received {@code -1} in
         * {@code 3300-SETUP-SCREEN-ATTRS} - {@code :1211-1235} - or {@code null} before that
         * {@code EVALUATE} has run.
         *
         * <p>Not a COBOL field either: the cursor request lives in the request's {@code xxxL} metadata,
         * and this records which field it landed on so the response envelope can report it without the
         * caller having to scan seventeen metadata entries.
         */
        String cursorField;

        /** Constructs an empty conversation; {@link CardUpdateController#initializeStorage} fills it. */
        Conversation() {
            // Intentionally empty. Every member is assigned by initializeStorage, which reproduces the
            // COBOL INITIALIZE precisely - spaces for alphanumeric items and zeros for numeric ones,
            // never Java defaults, and never null.
        }

        // ---- The 88-level condition names, as predicates ---------------------------------------------
        //
        // One method per condition name, so a test can drive both sides of each of them (gate G50) and so
        // no call site ever compares a flag to a bare literal.

        /** {@code 88 INPUT-OK VALUE '0'} - {@code app/cbl/COCRDUPC.cbl:54}. */
        boolean inputOk() {
            return INPUT_OK.equals(wsInputFlag);
        }

        /** {@code 88 INPUT-ERROR VALUE '1'} - {@code :55}. */
        boolean inputError() {
            return INPUT_ERROR.equals(wsInputFlag);
        }

        /** {@code 88 INPUT-PENDING VALUE LOW-VALUES} - {@code :56}; declared, never tested by the source (B5). */
        boolean inputPending() {
            return INPUT_PENDING.equals(wsInputFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-NOT-OK VALUE '0'} - {@code :58}. */
        boolean flgAcctfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-ISVALID VALUE '1'} - {@code :59}. */
        boolean flgAcctfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-ACCTFILTER-BLANK VALUE ' '} - {@code :60}; also the state {@code INITIALIZE} leaves. */
        boolean flgAcctfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditAcctFlag);
        }

        /** {@code 88 FLG-CARDFILTER-NOT-OK VALUE '0'} - {@code :62}. */
        boolean flgCardfilterNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardFlag);
        }

        /** {@code 88 FLG-CARDFILTER-ISVALID VALUE '1'} - {@code :63}. */
        boolean flgCardfilterIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardFlag);
        }

        /** {@code 88 FLG-CARDFILTER-BLANK VALUE ' '} - {@code :64}. */
        boolean flgCardfilterBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardFlag);
        }

        /** {@code 88 FLG-CARDNAME-NOT-OK VALUE '0'} - {@code :66}. */
        boolean flgCardnameNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardnameFlag);
        }

        /** {@code 88 FLG-CARDNAME-ISVALID VALUE '1'} - {@code :67}. */
        boolean flgCardnameIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardnameFlag);
        }

        /** {@code 88 FLG-CARDNAME-BLANK VALUE ' '} - {@code :68}. */
        boolean flgCardnameBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardnameFlag);
        }

        /** {@code 88 FLG-CARDSTATUS-NOT-OK VALUE '0'} - {@code :70}. */
        boolean flgCardstatusNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardstatusFlag);
        }

        /** {@code 88 FLG-CARDSTATUS-ISVALID VALUE '1'} - {@code :71}. */
        boolean flgCardstatusIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardstatusFlag);
        }

        /** {@code 88 FLG-CARDSTATUS-BLANK VALUE ' '} - {@code :72}. */
        boolean flgCardstatusBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardstatusFlag);
        }

        /** {@code 88 FLG-CARDEXPMON-NOT-OK VALUE '0'} - {@code :74}. */
        boolean flgCardexpmonNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardexpmonFlag);
        }

        /** {@code 88 FLG-CARDEXPMON-ISVALID VALUE '1'} - {@code :75}. */
        boolean flgCardexpmonIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardexpmonFlag);
        }

        /** {@code 88 FLG-CARDEXPMON-BLANK VALUE ' '} - {@code :76}. */
        boolean flgCardexpmonBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardexpmonFlag);
        }

        /** {@code 88 FLG-CARDEXPYEAR-NOT-OK VALUE '0'} - {@code :78}. */
        boolean flgCardexpyearNotOk() {
            return FLG_FILTER_NOT_OK.equals(wsEditCardexpyearFlag);
        }

        /** {@code 88 FLG-CARDEXPYEAR-ISVALID VALUE '1'} - {@code :79}. */
        boolean flgCardexpyearIsvalid() {
            return FLG_FILTER_ISVALID.equals(wsEditCardexpyearFlag);
        }

        /** {@code 88 FLG-CARDEXPYEAR-BLANK VALUE ' '} - {@code :80}. */
        boolean flgCardexpyearBlank() {
            return FLG_FILTER_BLANK.equals(wsEditCardexpyearFlag);
        }

        /** {@code 88 WS-RETURN-FLAG-OFF VALUE LOW-VALUES} - {@code :82}; declared, never tested (B5). */
        boolean wsReturnFlagOff() {
            return WS_RETURN_FLAG_OFF.equals(wsReturnFlag);
        }

        /** {@code 88 WS-RETURN-FLAG-ON VALUE '1'} - {@code :83}; declared, never tested (B5). */
        boolean wsReturnFlagOn() {
            return WS_RETURN_FLAG_ON.equals(wsReturnFlag);
        }

        /** {@code 88 PFK-VALID VALUE '0'} - {@code :85}. */
        boolean pfkValid() {
            return PFK_VALID.equals(wsPfkFlag);
        }

        /** {@code 88 PFK-INVALID VALUE '1'} - {@code :86}. */
        boolean pfkInvalid() {
            return PFK_INVALID.equals(wsPfkFlag);
        }

        /**
         * {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} - {@code :91}; two values, not one.
         *
         * @return whether {@code FLG-YES-NO-CHECK} holds {@code 'Y'} or {@code 'N'}
         */
        boolean flgYesNoValid() {
            return CARD_STATUS_YES.equals(flgYesNoCheck) || CARD_STATUS_NO.equals(flgYesNoCheck);
        }

        /**
         * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES} - {@code :158-159}; again two byte
         * patterns, and a space is not {@code x'00'}.
         *
         * @return whether {@code WS-INFO-MSG} is all spaces or all low values
         */
        boolean noInfoMessage() {
            return WS_INFO_MSG_SPACES.equals(wsInfoMsg) || WS_INFO_MSG_LOW_VALUES.equals(wsInfoMsg);
        }

        /** {@code 88 FOUND-CARDS-FOR-ACCOUNT} - {@code :160-161}; tested at {@code :963}, {@code :1212} and {@code :1352}. */
        boolean foundCardsForAccount() {
            return FOUND_CARDS_FOR_ACCOUNT.equals(wsInfoMsg);
        }

        /** {@code 88 PROMPT-FOR-CONFIRMATION} - {@code :166-167}; tested at {@code :1315}. */
        boolean promptForConfirmation() {
            return PROMPT_FOR_CONFIRMATION.equals(wsInfoMsg);
        }

        /**
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - {@code :174}; <strong>spaces</strong>, and the
         * guard on fourteen conditional message assignments.
         *
         * <p>Delegated to {@link CardUpdateService#isReturnMessageOff(String)} rather than compared
         * here, so the controller's guard and {@code 9200}'s cannot disagree about what "off" means.
         *
         * @return whether {@code WS-RETURN-MSG} is still seventy-five spaces
         */
        boolean returnMessageOff() {
            return CardUpdateService.isReturnMessageOff(wsReturnMsg);
        }

        /** {@code 88 NO-CHANGES-DETECTED} - {@code :187-188}; tested at {@code :685}, {@code :973} and {@code :1213}. */
        boolean noChangesDetected() {
            return NO_CHANGES_DETECTED.equals(wsReturnMsg);
        }

        /** {@code 88 COULD-NOT-LOCK-FOR-UPDATE} - {@code :205-206}; the first arm of the inner {@code EVALUATE}. */
        boolean couldNotLockForUpdate() {
            return COULD_NOT_LOCK_FOR_UPDATE.equals(wsReturnMsg);
        }

        /** {@code 88 LOCKED-BUT-UPDATE-FAILED} - {@code :209-210}; the second arm. */
        boolean lockedButUpdateFailed() {
            return LOCKED_BUT_UPDATE_FAILED.equals(wsReturnMsg);
        }

        /** {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} - {@code :207-208}; the third arm. */
        boolean dataWasChangedBeforeUpdate() {
            return DATA_WAS_CHANGED_BEFORE_UPDATE.equals(wsReturnMsg);
        }

        /**
         * {@code CCUP-CHANGE-ACTION} - {@code :276}, the single character the whole screen turns on.
         *
         * @return the discriminant, with all nine condition names available on it; never {@code null}
         */
        ChangeAction changeAction() {
            return thisProgCommarea.changeAction();
        }

        /**
         * {@code SET <a CCUP-CHANGE-ACTION condition name> TO TRUE}.
         *
         * @param action the new discriminant; must not be {@code null}
         */
        void setChangeAction(ChangeAction action) {
            thisProgCommarea = thisProgCommarea.withChangeAction(
                    Objects.requireNonNull(action, "CCUP-CHANGE-ACTION always holds one byte"));
        }

        /** {@code CCUP-OLD-DETAILS} - {@code :291-301}; the 89-byte snapshot the screen was painted from. */
        CardDetails oldDetails() {
            return thisProgCommarea.oldDetails();
        }

        /**
         * Replaces {@code CCUP-OLD-DETAILS}.
         *
         * @param details the new snapshot, which must be an {@link DetailGroup#OLD} group
         */
        void setOldDetails(CardDetails details) {
            thisProgCommarea = thisProgCommarea.withOldDetails(details);
        }

        /** {@code CCUP-NEW-DETAILS} - {@code :303-313}; the 89 bytes the user typed. */
        CardDetails newDetails() {
            return thisProgCommarea.newDetails();
        }

        /**
         * Replaces {@code CCUP-NEW-DETAILS}.
         *
         * @param details the typed values, which must be a {@link DetailGroup#NEW} group
         */
        void setNewDetails(CardDetails details) {
            thisProgCommarea = thisProgCommarea.withNewDetails(details);
        }

        /**
         * {@code CARD-MONTH-CHECK-N} - the {@code PIC 9(2)} view of {@link #cardMonthCheck}
         * ({@code :93-94}).
         *
         * <p>The codec is a parameter rather than a field because a zoned digit lives in a byte's
         * low-order nibble and which byte a character is depends on the code page. Passing it keeps this
         * carrier free of collaborators while still making the page explicit (practice B8).
         *
         * @param codec the codec carrying the active code page; must not be {@code null}
         * @return the two bytes reinterpreted as zoned digits
         */
        long cardMonthCheckN(FixedWidthCodec codec) {
            return zonedDigitsValue(cardMonthCheck, CARD_MONTH_CHECK_LENGTH, codec);
        }

        /**
         * {@code CARD-YEAR-CHECK-N} - the {@code PIC 9(4)} view of {@link #cardYearCheck}
         * ({@code :97-98}).
         *
         * @param codec the codec carrying the active code page; must not be {@code null}
         * @return the four bytes reinterpreted as zoned digits
         */
        long cardYearCheckN(FixedWidthCodec codec) {
            return zonedDigitsValue(cardYearCheck, CARD_YEAR_CHECK_LENGTH, codec);
        }
    }

    // =================================================================================================
    // The REDEFINES reinterpretation, and the three figurative-constant tests.
    //
    // These are the primitives the edit paragraphs are built from, and each of them is the kind of
    // operation a plain Java idiom gets subtly wrong: Integer.parseInt throws where a numeric REDEFINES
    // view simply reads bytes, String.isBlank() treats a tab as blank where COBOL does not, and
    // String.toUpperCase() is locale-sensitive where FUNCTION UPPER-CASE is not.
    // =================================================================================================

    /**
     * Reads an alphanumeric image through a {@code PIC 9(n)} {@code REDEFINES} view - the
     * {@code CARD-MONTH-CHECK} / {@code CARD-MONTH-CHECK-N} relationship of
     * {@code app/cbl/COCRDUPC.cbl:92-95} and the {@code CARD-YEAR-CHECK} pair at {@code :96-99}.
     *
     * <p><strong>A byte reinterpretation, not a parse.</strong> A zoned decimal digit lives in its
     * byte's low-order nibble, so reading the numeric view of {@code 'A5'} yields {@code 15} rather than
     * throwing, and reading it of {@code '  '} yields {@code 0}. {@link Integer#parseInt(String)} would
     * throw on both, and a caller that caught the exception and substituted a value would be inventing
     * behaviour: the COBOL never fails here, it just compares whatever number the bytes spell against
     * {@code 1 THRU 12} or {@code 1950 THRU 2099}. A nibble above nine is not a digit and contributes
     * zero, which is what a zoned read of a non-numeric byte does.
     *
     * <p>The same rule {@link CardUpdateService#zonedDigitsValue(String, FixedWidthCodec)} applies to
     * {@code CARD-CVV-CD-N}; it is restated here rather than reused because that method is bound to the
     * three-byte CVV span and these views are two and four bytes wide.
     *
     * @param image the alphanumeric view's content
     * @param width the declared width of the item, to which the image is first moved
     * @param codec the codec carrying the active code page; must not be {@code null}
     * @return the value the numeric view reads, never negative
     */
    static long zonedDigitsValue(String image, int width, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to read a zoned span: the digit lives in "
                + "each byte's low-order nibble, so the code page has to be stated and is never "
                + "assumed");
        String moved = codec.movePicX(image == null ? "" : image, width);
        byte[] bytes = moved.getBytes(codec.charset());
        // long, not int, and deliberately. The two call sites in this program read PIC 9(2) and PIC 9(4)
        // spans, which an int holds easily - but the REDEFINES pairs this method exists to serve include
        // CC-CARD-NUM-N PIC 9(16) and CARD-EXPIRAION-DATE-N PIC 9(10) [CVCRD01Y, :121-123], and sixteen
        // digits overflow an int silently and land on a NEGATIVE value. A reader that can quietly return
        // the wrong number for a wider span than today's callers use is a trap, not an economy, and
        // widening it costs nothing at the two present call sites.
        long value = 0;
        for (byte encoded : bytes) {
            int nibble = encoded & ZONED_DIGIT_NIBBLE_MASK;
            int digit = nibble <= MAX_DIGIT_NIBBLE ? nibble : NO_ZONED_DIGIT;
            value = value * DECIMAL_RADIX + digit;
        }
        return value;
    }

    /** The low-order nibble mask that isolates a zoned decimal digit from its byte. */
    private static final int ZONED_DIGIT_NIBBLE_MASK = 0x0F;

    /** The highest nibble value that is a decimal digit. */
    private static final int MAX_DIGIT_NIBBLE = 9;

    /** The digit a nibble above nine contributes: none. */
    private static final int NO_ZONED_DIGIT = 0;

    /** The radix a zoned decimal string is read in. */
    private static final int DECIMAL_RADIX = 10;

    /**
     * Whether an item holds the figurative constant {@code LOW-VALUES} or {@code SPACES} at its declared
     * width - the {@code IF x EQUAL LOW-VALUES OR x EQUAL SPACES} pair that opens five of the six edit
     * paragraphs and guards {@code :442-443}, {@code :449-450} and {@code :1013-1014}.
     *
     * <p>Binary {@code x'00'} and a space are different bytes and are tested separately, exactly as the
     * source's two disjuncts do.
     *
     * @param value  the item's content
     * @param length the item's declared width
     * @return whether every byte is {@code x'00'}, or every byte is a space
     */
    static boolean isLowValuesOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        return CardScreenState.lowValues(length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    /**
     * Whether an alphanumeric item holds the figurative constant {@code ZEROS} at its declared width -
     * the third disjunct of the blank test in {@code 1230-EDIT-NAME} ({@code :813}),
     * {@code 1240-EDIT-CARDSTATUS} ({@code :852}), {@code 1250-EDIT-EXPIRY-MON} ({@code :885}) and
     * {@code 1260-EDIT-EXPIRY-YEAR} ({@code :918}).
     *
     * <p>Compared against an <em>alphanumeric</em> item, {@code ZEROS} is the character {@code '0'}
     * repeated to the item's length - not the number zero, and not a space. So a card name of fifty
     * {@code '0'} characters counts as "not supplied" while a card name of one {@code '0'} does not, and
     * both of those are the source's behaviour rather than an accident of this translation.
     *
     * @param value  the item's content
     * @param length the item's declared width
     * @return whether every byte is the character {@code '0'}
     */
    static boolean isAllZeroCharacters(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        return "0".repeat(length).equals(image);
    }

    /**
     * {@code INSPECT subject CONVERTING from TO to} - the character-by-character translation of
     * {@code app/cbl/COCRDUPC.cbl:824-826} (letters to spaces) and {@code :1356-1358} (lower case to
     * upper case).
     *
     * <p>Deliberately <strong>not</strong> {@link String#toUpperCase()}: that is locale-sensitive - in a
     * Turkish locale it maps {@code 'i'} to {@code 'İ'} - and it folds thousands of characters outside
     * the twenty-six the copybook names. {@code INSPECT CONVERTING} translates only the characters that
     * appear in the {@code FROM} operand, position for position, and leaves everything else alone; the
     * first occurrence wins when a character is listed twice. That is what this reproduces.
     *
     * @param subject the item to translate
     * @param from    the {@code CONVERTING} operand
     * @param to      the {@code TO} operand, which must be the same length as {@code from}
     * @return the translated item, the same length as {@code subject}
     * @throws IllegalArgumentException if the two operands differ in length, which COBOL rejects at
     *                                  compile time
     */
    static String inspectConverting(String subject, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("INSPECT ... CONVERTING requires operands of equal "
                    + "length, but the FROM operand is " + from.length() + " characters and the TO "
                    + "operand is " + to.length());
        }
        StringBuilder translated = new StringBuilder(subject.length());
        for (int index = 0; index < subject.length(); index++) {
            char original = subject.charAt(index);
            int position = from.indexOf(original);
            translated.append(position < 0 ? original : to.charAt(position));
        }
        return translated.toString();
    }

    /**
     * {@code FUNCTION UPPER-CASE(argument)} - {@code app/cbl/COCRDUPC.cbl:680-681}, where the typed
     * card data and the fetched card data are folded before being compared.
     *
     * <p>Implemented over the program's own declared {@link #LIT_LOWER} / {@link #LIT_UPPER} pair rather
     * than through {@link String#toUpperCase()}, for the reasons given on
     * {@link #inspectConverting(String, String, String)}. The result is identical to the intrinsic for
     * every character this estate's data can contain, and unlike the intrinsic it cannot change with the
     * default locale.
     *
     * @param argument the item to fold
     * @return the folded item, the same length as {@code argument}
     */
    static String functionUpperCase(String argument) {
        return inspectConverting(argument, LIT_LOWER, LIT_UPPER);
    }

    /**
     * {@code FUNCTION LENGTH(FUNCTION TRIM(argument)) = 0} - the emptiness test of
     * {@code app/cbl/COCRDUPC.cbl:828}.
     *
     * <p>{@code FUNCTION TRIM} removes leading and trailing <strong>spaces</strong>, and nothing else.
     * {@link String#strip()} removes anything {@link Character#isWhitespace(char)} accepts, which
     * includes a tab and a newline and would therefore call a name containing them alphabetic; and it
     * does <em>not</em> remove {@code x'00'}, which is correct but only by accident. Trimming exactly
     * the space character keeps the test the source's.
     *
     * @param argument the item, already stripped of every letter by the preceding {@code INSPECT}
     * @return whether nothing but spaces remains
     */
    static boolean isTrimmedEmpty(String argument) {
        for (int index = 0; index < argument.length(); index++) {
            if (argument.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }

    // =================================================================================================
    // The HTTP boundary. Three values that CICS supplies rather than the terminal - the path's card
    // number, EIBCALEN and EIBAID - are reconciled here, before 0000-MAIN sees them, because each of
    // them selects an arm and a caller that could state one freely could reach a record or a state the
    // request does not name.
    // =================================================================================================

    /** The lowest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MIN = 0;

    /** The highest value an unsigned {@code EIBAID} byte can carry. */
    private static final int AID_MAX = 255;

    /**
     * The name of the query parameter carrying the raw {@code EIBAID} byte -
     * {@link AidRequestParameter#CANONICAL_NAME}, shared with every other online route rather than
     * spelled here.
     *
     * <p>It matters more on this screen than on most: {@code PF5} is the confirm key and {@code PF12} the
     * cancel key, so a key discarded because it was sent under the wrong spelling would silently become
     * {@link CicsAid#DFHENTER} and the operator's save would turn into a redisplay.
     */
    static final String EIBAID_PARAM = AidRequestParameter.CANONICAL_NAME;

    /**
     * The alternate spelling of {@link #EIBAID_PARAM}, accepted on every online route so that a client
     * written against either name keeps working.
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



    /** The route: {@code PUT /api/cards/{cardNum}}, CSD transaction {@code CCUP}. */
    static final String CARD_UPDATE_PATH = "/api/cards/{cardNum}";

    /**
     * Accepts and processes a credit-card update: {@code PUT /api/cards/{cardNum}}, transaction
     * {@code CCUP}, mapset {@code COCRDUP}, map {@code CCRDUPA}, seventeen fields.
     *
     * <p>{@code PUT} rather than {@code POST} because the operation is idempotent in the way the COBOL
     * makes it: {@code 9300-CHECK-CHANGE-IN-REC} re-reads the record under lock and compares it to the
     * snapshot the screen was painted from, so replaying the same confirmed request against an already
     * updated record is refused with {@link #DATA_WAS_CHANGED_BEFORE_UPDATE} rather than applied twice.
     *
     * <p>Always answers {@code 200 OK}, because {@code COCRDUPC} always ends in {@code EXEC CICS RETURN}
     * or {@code EXEC CICS XCTL}: every path either paints the screen ({@code COMMON-RETURN},
     * {@code app/cbl/COCRDUPC.cbl:546-558}) or transfers control ({@code :473-476}). A rejected field is
     * a message on the screen, not a {@code 4xx} - and an unrecognised function key is not even that,
     * since {@code :422-424} silently coerces it to {@code ENTER}. The only status this endpoint can
     * produce other than {@code 200} comes from {@code ABEND-ROUTINE}, which raises
     * {@link AbendException} and is answered {@code 500} by {@code WebConfig.CobolErrorHandler} - the
     * faithful projection of {@code EXEC CICS ABEND ABCODE('9999')} at {@code :1550-1552}.
     *
     * <p><strong>Nothing is retained server-side</strong> (gate G37): the painted screen, the
     * {@value CommArea#RECORD_LENGTH}-byte program commarea, the 160-byte
     * {@code CARDDEMO-COMMAREA} and {@code CC-WORK-AREA} all travel back in the body, and the next call
     * carries them in again.
     *
     * @param cardNum  {@code CARDSIDI PIC X(16)} - the card number this URI addresses, and the
     *                 {@code CARDDAT} key {@code 9100-GETCARD-BYACCTCARD} reads with. Required, because
     *                 it is the path, and authoritative: it is moved into {@code CARDSID} and, when a
     *                 communication area was passed, into that area's {@code CDEMO-CARD-NUM} as well, so
     *                 that neither of the two arms which read a card number can read a different one
     * @param request  the whole {@code 01 CCRDUPAI} symbolic-map request, validated against the declared
     *                 widths, together with the 160-byte {@code CARDDEMO-COMMAREA} and the
     *                 {@value CommArea#RECORD_LENGTH}-byte program commarea {@code :396-400} restores
     *                 from. {@code null} when no body was sent, which is the {@code EIBCALEN = 0} cold
     *                 start
     * @param eibAid   {@code EIBAID} - the raw attention identifier byte, {@code 0}-{@code 255}, under
     *                 the alternate spelling. Absent means {@link CicsAid#DFHENTER}. It is resolved by
     *                 {@link PfKeyResolver}, so an unrecognised byte reaches the same no-match outcome
     *                 the copybook's {@code EVALUATE} leaves unhandled and is then coerced to
     *                 {@code ENTER} at {@code :422-424}, never refused
     * @param eibcalen {@code EIBCALEN} - the length of the passed commarea, and therefore either
     *                 {@value #NO_COMMAREA_LENGTH} or {@value #PASSED_COMMAREA_LENGTH} and nothing else.
     *                 Absent is derived from the carrier, and a stated value that contradicts the
     *                 carrier is refused rather than believed. The distinction is load-bearing at
     *                 {@code :388}
     * @param eibaid   the same attention identifier under {@link AidRequestParameter#CANONICAL_NAME}. At
     *                 most one of the two spellings need be sent; sending both with different values is
     *                 refused, because a terminal presents one attention identifier
     * @return the painted screen and its metadata: the seventeen fields, the next-screen triple, both
     *         commareas, the work area and the attribute quads, all in the body
     * @throws IllegalArgumentException if {@code cardNum} or a bound field is wider than its
     *                                  {@code PICTURE}, if the AID is outside {@code 0}-{@code 255}, if
     *                                  the two AID spellings disagree, or if {@code eibcalen} is neither
     *                                  of the two lengths or disagrees with the carrier - each answered
     *                                  {@code 400} by {@code WebConfig.CobolErrorHandler} with no value
     *                                  echoed
     */
    @PutMapping(path = CARD_UPDATE_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScreenResponse<CardUpdateResponse>> updateCardDetail(
            @PathVariable("cardNum") String cardNum,
            @Valid @RequestBody(required = false) CardUpdateRequest request,
            @RequestParam(name = EIBAID_PARAM_ALIAS, required = false) Integer eibAid,
            @RequestParam(name = EIBCALEN_PARAM, required = false) Integer eibcalen,
            @RequestParam(name = EIBAID_PARAM, required = false) Integer eibaid) {

        Objects.requireNonNull(cardNum, "A card number is required in the path: it is the RIDFLD of "
                + "the READ at app/cbl/COCRDUPC.cbl:1382-1390 and of the READ ... UPDATE at "
                + ":1427-1436");

        CardUpdateRequest received = bind(cardNum, request);
        int commareaLength = resolveEibcalen(eibcalen, received);
        byte attentionIdentifier =
                resolveAttentionIdentifier(AidRequestParameter.resolve(eibaid, eibAid));

        PaintedScreen painted = handle(received, commareaLength, attentionIdentifier);
        return ResponseEntity.ok(ScreenResponse.of(painted.response(),
                screenMetadataOf(painted.response(), painted.cursorField())));
    }

    /**
     * Reconciles the URI's card number with the bound request, and refuses the two ways a caller could
     * otherwise reach a record the URI does not name.
     *
     * <h4>Why an over-width value is refused rather than moved</h4>
     * A {@code MOVE} to a {@code PIC X(16)} field keeps the leading sixteen characters and discards the
     * rest, so {@code /api/cards/40000000000000019999} would have been truncated to
     * {@code 4000000000000001} and <em>updated</em> that card - a URI writing to a record it does not
     * name, and one no operator could have typed, because a 3270 field physically cannot accept more
     * characters than it declares. The COBOL move is faithful for a value that fits; for one that does
     * not there is nothing faithful to reproduce, so the request is refused at the boundary before any
     * padding, any repository call and any lock.
     *
     * <h4>The path is projected into both carriers of the key</h4>
     * This program reads the card number from two places and which one it reads depends on which arm of
     * {@code :429-543} the request lands on. On the re-entry arm it is the typed field {@code CARDSIDI},
     * moved into {@code CC-CARD-NUM} by {@code :598-605}. On a fresh arrival from the card-list screen
     * it is {@code CDEMO-CARD-NUM} in the communication area, which {@code :491} moves straight into
     * {@code CC-CARD-NUM-N} before reading - the typed field is not consulted at all on that arm. Both
     * carriers are therefore set from the path here, before any arm is selected and before any
     * repository call, so the URI is the only statement of which record is read and written.
     *
     * <p>No disagreement error is raised. {@code COCRDUPC} has no such condition - a terminal has one
     * value, not two - so inventing one would add a failure mode the legacy screen cannot produce. A
     * client that re-sends a screen it painted from this URI agrees with the path and sees no
     * difference; one that names a second card has that value replaced rather than acted on. The
     * source's own {@code 1220-EDIT-CARD} messages stay reachable, because a path value that is blank or
     * not sixteen digits is passed to them unchanged.
     *
     * @param cardNum the path variable; must not be {@code null}
     * @param request the bound body, or {@code null} for a cold start
     * @return the request to execute, with {@code CARDSID} and - when a communication area was passed -
     *         {@code CDEMO-CARD-NUM} both set from the path; never {@code null}
     * @throws IllegalArgumentException if the path value, or the bound {@code ACCTSID}, is wider than
     *                                 its declared width
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
    CardUpdateRequest bind(String cardNum, CardUpdateRequest request) {
        if (cardNum.length() > CardUpdateRequest.CARDSID_LENGTH) {
            throw ScreenInputRejectedException.tooWide(CARDSID_MEMBER,
                    "CARDSIDI PIC X(" + CardUpdateRequest.CARDSID_LENGTH + ")",
                    CardUpdateRequest.CARDSID_LENGTH, cardNum.length());
        }

        CardUpdateRequest received = request == null ? new CardUpdateRequest()
                : new CardUpdateRequest(request);
        ScreenInputRejectedException.requireKeyAgreement(CARDSID_MEMBER, cardNum,
                received.getCardsid(), CardUpdateRequest.CARDSID_LENGTH, codec, NO_CRITERION_IMAGE);
        received.setCardsid(codec.movePicX(cardNum, CardUpdateRequest.CARDSID_LENGTH));

        // The communication area's own card number, the one :491 reads. Projected only when an area was
        // actually passed: a null context is EIBCALEN = 0, which :388 branches on, and fabricating one
        // here would send the request down an arm the caller never reached.
        if (received.hasNavigationContext()) {
            received.setNavigationContext(
                    received.getNavigationContext().withCardNum(carriedCardNumber(cardNum)));
        }

        // Never null: the no-argument constructor fills all seventeen items at their declared widths and
        // setAcctsid normalises a null - including an explicit JSON null, because Jackson binds through
        // the setter rather than the field. A COBOL alphanumeric item has no absent state, so no null
        // guard is written for a state that cannot occur.
        String acctsid = received.getAcctsid();
        if (acctsid.length() > CardUpdateRequest.ACCTSID_LENGTH) {
            throw new IllegalArgumentException("ACCTSID is ACCTSIDI PIC X("
                    + CardUpdateRequest.ACCTSID_LENGTH + ") and was given " + acctsid.length()
                    + " characters. Padding it would keep the leading "
                    + CardUpdateRequest.ACCTSID_LENGTH + " and filter on a different account.");
        }
        received.setAcctsid(codec.movePicX(acctsid, CardUpdateRequest.ACCTSID_LENGTH));
        return received;
    }

    /**
     * The URI's card number as {@code CDEMO-CARD-NUM PIC 9(16)} holds it.
     *
     * <p>{@code CARDSID} is {@code PIC X(16)} and the communication area's carried card number is
     * {@code PIC 9(16)}, so the projection has to cross that boundary. A path value of sixteen digits or
     * fewer is the number it spells, leading zeros and all. Anything a {@code PIC 9(16)} item cannot
     * hold - a blank segment, {@code '*'}, or any value with a non-digit in it - yields zero, which is
     * the area's own unset value and the one {@code :1093} already tests for. Zero rather than a
     * refusal, because zero is the only answer that cannot name a card the URI does not: the arm reads
     * it, finds nothing, and the source's {@code NOTFND} handling paints
     * {@link #DID_NOT_FIND_ACCTCARD_COMBO}, exactly as it does for a card the operator typed that does
     * not exist.
     *
     * <p>Leading and trailing spaces are stripped before the digit test, because a client echoing a
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
     * Resolves {@code EIBCALEN} from the stated value and the carrier, and refuses any statement the
     * carrier does not support.
     *
     * <h4>Why a caller may not simply declare it</h4>
     * {@code EIBCALEN} is not caller data on a real terminal: CICS sets it to the length of the area it
     * actually passed. It selects the first disjunct at {@code app/cbl/COCRDUPC.cbl:388}, which decides
     * whether the operator's typed criteria, the calling program's identity <em>and</em> the whole
     * {@value CommArea#RECORD_LENGTH}-byte screen state survive the turn. A caller that could state it
     * freely could discard state that was sent, or claim state that was not.
     *
     * <h4>Why the two accepted values are 0 and {@value #PASSED_COMMAREA_LENGTH}</h4>
     * {@code :388} tests the value against zero and nothing else, and the only other thing the program
     * does with the passed area is read exactly {@value #PASSED_COMMAREA_LENGTH} bytes out of it -
     * {@code DFHCOMMAREA(1:160)} at {@code :396-397} and {@code DFHCOMMAREA(161:329)} at
     * {@code :398-400}. The projected request carries precisely those two areas, so it is in one of
     * exactly two states: absent, or complete at {@value #PASSED_COMMAREA_LENGTH} bytes. The byte count
     * a real terminal would report is not one number - {@code COCRDLIC} transfers control passing
     * {@code CARDDEMO-COMMAREA} alone while this program's own {@code COMMON-RETURN} passes
     * {@code WS-COMMAREA}, declared {@code PIC X(2000)} at {@code :324} - and since none of those
     * numbers is tested for anything but zero, reproducing the terminal-dependent count would add a
     * distinction the program does not make.
     *
     * @param eibcalen the stated value, or {@code null}
     * @param request  the bound request, whose commarea presence is the carrier
     * @return {@value #NO_COMMAREA_LENGTH} or {@value #PASSED_COMMAREA_LENGTH}
     * @throws IllegalArgumentException if the stated value is neither length, or contradicts the carrier
     */
    static int resolveEibcalen(Integer eibcalen, CardUpdateRequest request) {
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
                    + "because app/cbl/COCRDUPC.cbl:388 uses it to decide whether the conversation's "
                    + "state survives the turn.");
        }
        return stated;
    }

    /**
     * Narrows the stated {@code EIBAID} to the byte {@code :413-424} tests, having first required it to
     * be a byte.
     *
     * <p>An {@code Integer} cast straight to {@code byte} keeps the low eight bits and discards the
     * rest, so {@code 501} would arrive as {@code 0xF5} - {@code DFHPF5}, this screen's <em>confirm and
     * save</em> key - and {@code -11} as {@code 0xF5} too. Neither is a key anyone pressed, and on this
     * screen the consequence of getting it wrong is a record written rather than a screen redisplayed.
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
    // 0000-MAIN - app/cbl/COCRDUPC.cbl:367-544.
    //
    // Note that this paragraph has an EVALUATE TRUE of its OWN, with five arms, and it is NOT the same
    // EVALUATE as 2000-DECIDE-ACTION's eight. The outer one decides WHICH CONVERSATION this turn is; the
    // inner one decides what to do with the data once the turn has been identified as an ordinary
    // re-entry. Only the outer one's last arm reaches the inner one at all.
    // =================================================================================================

    /**
     * {@code 0000-MAIN} with the {@code EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE)} declarative of
     * {@code app/cbl/COCRDUPC.cbl:370-372} in force.
     *
     * <p>This is the seam the parity tests drive: no HTTP, no Spring, no {@code MockMvc}. Instantiate the
     * controller with a mocked {@link CardRepository}, a mocked {@link CardUpdateService} and a fixed
     * {@link Clock}, and call this (gate G51, practice B10).
     *
     * @param request  the seventeen {@code xxxI} items plus the two carried areas; must not be
     *                 {@code null}
     * @param eibcalen {@code EIBCALEN}, the length of the passed commarea
     * @param eibAid   {@code EIBAID}, the raw attention identifier byte
     * <p>Two things sit deliberately outside that declarative. Ahead of it,
     * {@link ScreenInputRejectedException#requireRepresentable} judges the seventeen received values
     * against the screen code page: a character that code page cannot represent is a value no
     * {@code RECEIVE MAP} could have delivered, so it is the caller's mistake rather than a transaction
     * that failed, and sweeping before the flow begins puts the refusal ahead of every read and every
     * write. Inside it, a {@link ScreenInputRejectedException} raised deeper - by the commarea
     * consistency guard in {@link #editMapInputs1200} - is rethrown for the same reason.
     *
     * @return the painted screen; never {@code null}
     * @throws NullPointerException         if {@code request} is {@code null}
     * @throws ScreenInputRejectedException if the payload carries a value a received map could not have
     * @throws AbendException               if the {@code HANDLE ABEND} handler runs to its
     *                                      {@code EXEC CICS ABEND ABCODE('9999')}
     */
    PaintedScreen handle(CardUpdateRequest request, int eibcalen, byte eibAid) {
        Objects.requireNonNull(request, "A request is required: COCRDUPC is entered with a terminal "
                + "input area, and an absent one is spaces rather than nothing");
        ScreenInputRejectedException.requireRepresentable(request.fieldValues(), PIC_X_CODEC);

        CardUpdateResponse response = new CardUpdateResponse();
        Conversation task = new Conversation();

        // :370-372 - EXEC CICS HANDLE ABEND LABEL(ABEND-ROUTINE). Every abend from here on is routed to
        // the handler, exactly as the CICS declarative did, and the handler has the last word.
        try {
            main0000(request, response, task, eibcalen, eibAid);
        } catch (AbendException alreadyAbending) {
            // The handler has already run - :1546-1548 does EXEC CICS HANDLE ABEND CANCEL before
            // abending, so an abend raised BY the handler is not re-handled. Rethrown unchanged.
            throw alreadyAbending;
        } catch (ScreenInputRejectedException callersInput) {
            // Not an abend: a payload describing a conversation this program cannot be in. Answered as
            // the caller's error, the same 400 the screens without a HANDLE ABEND already produce.
            throw callersInput;
        } catch (RuntimeException abend) {
            throw abendRoutine(task, response, abend);
        }
        // The cursor request leaves with the map because MOVE -1 TO <field>L is a property of the SEND,
        // not of the map's content, and the length items themselves are metadata rather than payload.
        return new PaintedScreen(response, task.cursorField);
    }

    /**
     * What one execution of {@code COCRDUPC} produced: the painted map, and the {@code DFHMDF} label the
     * cursor was requested on.
     *
     * <p>The two travel together because {@code EXEC CICS SEND MAP ... CURSOR} at
     * {@code app/cbl/COCRDUPC.cbl:1329-1336} sends them together. The cursor cannot live on
     * {@link CardUpdateResponse}, because in the copybook it is a {@code -1} in an {@code xxxL} length
     * item and those items are metadata, never payload (gate G9).
     *
     * @param response    the painted map, never {@code null}
     * @param cursorField the {@code DFHMDF} label {@code 3300}'s cursor {@code EVALUATE} chose, or
     *                    {@code null} when the paragraph did not run - which happens on the
     *                    {@code XCTL} arm at {@code :435-476}, where no map is sent at all
     */
    record PaintedScreen(CardUpdateResponse response, String cursorField) {

        /**
         * @throws NullPointerException if {@code response} is {@code null}
         */
        PaintedScreen {
            Objects.requireNonNull(response, "A painted map is always produced: even the XCTL arm at "
                    + "app/cbl/COCRDUPC.cbl:435-476 returns the commarea it built");
        }
    }

    /**
     * The body of {@code 0000-MAIN}, with the {@code HANDLE ABEND} declarative already in force.
     *
     * <p>Separate from {@link #handle} so the {@code try} block contains one statement and the seven
     * steps read in a column. Package-visible so a test can drive the flow with the handler bypassed and
     * see the raw failure.
     *
     * <p>The steps, each keyed to its line:
     * <ol>
     *   <li>{@code :374-376} {@code INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA} -
     *       {@link #initializeStorage}. Spaces and zeros, never {@code null}. Note which three groups
     *       are named and, just as importantly, which two are <em>not</em>.</li>
     *   <li>{@code :380} {@code MOVE LIT-THISTRANID TO WS-TRANID}.</li>
     *   <li>{@code :384} {@code SET WS-RETURN-MSG-OFF TO TRUE} - seventy-five spaces.</li>
     *   <li>{@code :388-401} the commarea restore - {@link #restoreCommarea}.</li>
     *   <li>{@code :406-407} {@code PERFORM YYYY-STORE-PFKEY} - {@link #storePfKeyYYYY}.</li>
     *   <li>{@code :413-424} the function-key validity test and the coercion of anything invalid to
     *       {@code ENTER} - {@link #validatePfKey}.</li>
     *   <li>{@code :429-543} the five-arm {@code EVALUATE TRUE}, in source order (gate G30) -
     *       {@link #dispatch0000}.</li>
     * </ol>
     *
     * <p>The final {@code if} reproduces the fall-through from {@code END-EVALUATE} at {@code :543} into
     * the {@code COMMON-RETURN} paragraph that follows it at {@code :546}. Every one of the five arms
     * terminates the task - four end in {@code GO TO COMMON-RETURN} and the first issues
     * {@code EXEC CICS XCTL} - so the fall-through cannot be reached, which is why the guard is written
     * as a condition rather than an unconditional call: reaching {@code COMMON-RETURN} twice would
     * return the commarea twice. It is translated because paragraph juxtaposition is behaviour and
     * deleting it would be a change (practice B5).
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     * @param eibcalen {@code EIBCALEN}
     * @param eibAid   {@code EIBAID}
     */
    void main0000(CardUpdateRequest request, CardUpdateResponse response, Conversation task,
            int eibcalen, byte eibAid) {

        // :374-376 - INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA
        initializeStorage(request, task);

        // :380 - MOVE LIT-THISTRANID TO WS-TRANID
        task.wsTranid = codec.movePicX(LIT_THISTRANID, WS_TRANID_LENGTH);

        // :384 - SET WS-RETURN-MSG-OFF TO TRUE. SPACES, not LOW-VALUES; see the class documentation.
        task.wsReturnMsg = WS_RETURN_MSG_OFF;

        // :388-401 - restore both passed areas, or discard them on a cold start or a fresh menu entry
        restoreCommarea(task, eibcalen);

        // :406-407 - PERFORM YYYY-STORE-PFKEY THRU YYYY-STORE-PFKEY-EXIT  (COPY 'CSSTRPFY' at :1528)
        storePfKeyYYYY(task, eibAid);

        // :413-424 - four keys are valid here, two of them only in a particular state; anything else
        // becomes ENTER
        validatePfKey(task);

        // :429-543 - EVALUATE TRUE. First match wins, and the order is the source's (gate G30).
        dispatch0000(request, response, task);

        // :543 END-EVALUATE, then :546 COMMON-RETURN by paragraph juxtaposition - unreachable, and
        // translated regardless (practice B5). See the method documentation.
        if (!task.returned) {
            commonReturn(response, task);
        }
    }

    /**
     * {@code INITIALIZE CC-WORK-AREA, WS-MISC-STORAGE, WS-COMMAREA} -
     * {@code app/cbl/COCRDUPC.cbl:374-376}.
     *
     * <p>A COBOL {@code INITIALIZE} without {@code REPLACING} sets every alphanumeric item in the named
     * groups to <strong>spaces</strong> and every numeric item to <strong>zeros</strong>, and it
     * <em>ignores</em> {@code VALUE} clauses. Three consequences are relied on downstream and would be
     * lost by initialising to Java defaults:
     *
     * <ul>
     *   <li>{@code WS-INPUT-FLAG} becomes a space, which satisfies <em>none</em> of {@code INPUT-OK},
     *       {@code INPUT-ERROR} or {@code INPUT-PENDING}. The flag is genuinely in none of its three
     *       declared states until an edit runs.</li>
     *   <li>All six {@code WS-EDIT-*-FLAG} items become spaces, which <em>does</em> satisfy the six
     *       {@code FLG-*-BLANK} condition names. So every field reads as blank on entry, and
     *       {@link #positionCursor3300}'s {@code EVALUATE} takes its first matching arm accordingly.</li>
     *   <li>{@code CARD-NAME-CHECK} loses its {@code VALUE LOW-VALUES} and becomes fifty spaces, and
     *       {@code FLG-YES-NO-CHECK} loses its {@code VALUE 'N'} and becomes one space - which is
     *       <em>not</em> a value {@code 88 FLG-YES-NO-VALID} accepts.</li>
     * </ul>
     *
     * <p>{@code WS-CARD-RID-ACCT-ID} is {@code PIC 9(11)}, so it initialises to eleven <em>zeros</em>
     * rather than spaces - and since both statements that would ever have assigned it are commented out,
     * at {@code :1379} and {@code :1424}, eleven zeros is the value it holds for the whole task.
     *
     * <p>The three groups {@code INITIALIZE} names do <strong>not</strong> include
     * {@code CARDDEMO-COMMAREA} or {@code WS-THIS-PROGCOMMAREA}; those are handled separately at
     * {@code :391-400}, and differently on each arm. {@link #restoreCommarea} owns that distinction, so
     * both areas start here at whatever the request delivered.
     *
     * @param request the terminal input area, whose two carried areas seed the commarea and the trailer
     * @param task    this task's storage, filled in place
     */
    void initializeStorage(CardUpdateRequest request, Conversation task) {
        // INITIALIZE CC-WORK-AREA (app/cpy/CVCRD01Y.cpy) - all nine items to spaces at declared widths.
        // The request's own work area is deliberately NOT copied in: the COBOL discards it too, which is
        // why CCARD-AID has to be re-derived from EIBAID on every turn rather than remembered.
        task.ccWorkArea = new CardScreenState();
        task.ccWorkArea.initializeWorkArea();

        // INITIALIZE WS-MISC-STORAGE - :36-214.
        initializeMiscStorage(task);

        // INITIALIZE WS-COMMAREA - PIC X(2000), so two thousand spaces.
        task.wsCommarea = CardScreenState.spaces(WS_COMMAREA_LENGTH);

        // ABEND-DATA (app/cpy/CSMSG02Y.cpy at :343) is not named by the INITIALIZE, but the copybook
        // declares VALUE SPACES throughout, so spaces is its state until an arm writes to it.
        task.abendData = SystemMessages.AbendData.spaces();

        // The two declared-only copybook areas, at their freshly allocated state. Neither is named by the
        // INITIALIZE and neither is ever read, so both simply exist - which is the whole point: a COPY
        // allocates storage whether or not a statement touches it (practice B5).
        task.secUserData = SecUserRecord.blank();
        task.customerRecord = new CustomerRecord();

        // WS-CURDATE-DATA is filled by 3100-SCREEN-INIT, not here.
        task.dateHeader = null;
        task.cursorField = null;
        task.returned = false;

        // Both passed areas start where the request left them; :388-401 decides whether they are kept or
        // discarded. getCommArea() never returns null - the trailer is this program's own storage and
        // always exists - while getNavigationContext() may, which is precisely the EIBCALEN = 0 state.
        task.carddemoCommarea = request.hasNavigationContext()
                ? request.getNavigationContext()
                : NavigationContext.empty();
        task.thisProgCommarea = request.getCommArea();
    }

    /**
     * {@code INITIALIZE WS-MISC-STORAGE} on its own - the whole {@code 01} group at
     * {@code app/cbl/COCRDUPC.cbl:36-214}.
     *
     * <p>Factored out because the group is initialised <strong>twice</strong>: once at {@code :375} as
     * part of entry, and again at {@code :520} on the arm that resets the screen after an update has
     * finished. Reproducing the second one is what wipes {@code WS-RETURN-MSG} and {@code WS-INFO-MSG}
     * back to spaces, so the operator sees a fresh prompt rather than the previous turn's confirmation -
     * and {@code WS-TRANID} goes back to spaces with them, which is faithful even though nothing reads
     * it afterwards.
     *
     * @param task this task's storage, filled in place
     */
    void initializeMiscStorage(Conversation task) {
        task.wsRespCd = 0;
        task.wsReasCd = 0;
        task.wsTranid = CardScreenState.spaces(WS_TRANID_LENGTH);
        task.wsUctrans = CardScreenState.spaces(WS_UCTRANS_LENGTH);

        task.wsInputFlag = FLG_FILTER_BLANK;
        task.wsEditAcctFlag = FLG_FILTER_BLANK;
        task.wsEditCardFlag = FLG_FILTER_BLANK;
        task.wsEditCardnameFlag = FLG_FILTER_BLANK;
        task.wsEditCardstatusFlag = FLG_FILTER_BLANK;
        task.wsEditCardexpmonFlag = FLG_FILTER_BLANK;
        task.wsEditCardexpyearFlag = FLG_FILTER_BLANK;
        task.wsReturnFlag = FLG_FILTER_BLANK;
        task.wsPfkFlag = FLG_FILTER_BLANK;

        task.cardNameCheck = CardScreenState.spaces(CARD_NAME_CHECK_LENGTH);
        task.flgYesNoCheck = FLG_FILTER_BLANK;
        task.cardMonthCheck = CardScreenState.spaces(CARD_MONTH_CHECK_LENGTH);
        task.cardYearCheck = CardScreenState.spaces(CARD_YEAR_CHECK_LENGTH);

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
    }

    /**
     * The commarea restore - {@code app/cbl/COCRDUPC.cbl:388-401}:
     *
     * <pre>
     * IF EIBCALEN IS EQUAL TO 0
     *    OR (CDEMO-FROM-PROGRAM = LIT-MENUPGM AND NOT CDEMO-PGM-REENTER)
     *    INITIALIZE CARDDEMO-COMMAREA WS-THIS-PROGCOMMAREA
     *    SET CDEMO-PGM-ENTER TO TRUE
     *    SET CCUP-DETAILS-NOT-FETCHED TO TRUE
     * ELSE
     *    MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA) TO CARDDEMO-COMMAREA
     *    MOVE DFHCOMMAREA (LENGTH OF CARDDEMO-COMMAREA + 1:
     *                      LENGTH OF WS-THIS-PROGCOMMAREA) TO WS-THIS-PROGCOMMAREA
     * END-IF
     * </pre>
     *
     * <p>The compound condition and its {@code NOT} are load-bearing. A <em>fresh</em> arrival from the
     * main menu - {@code CDEMO-FROM-PROGRAM} is {@value #LIT_MENUPGM} and the context is not
     * {@code REENTER} - discards whatever the menu passed and starts clean, while a <em>re-entry</em>
     * keeps it. Dropping the {@code NOT}, or reading the {@code OR} as an {@code AND}, silently loses
     * every typed field on every turn.
     *
     * <p>Note that this arm sets two things the sibling {@code COCRDSLC} does not:
     * {@code SET CDEMO-PGM-ENTER} at {@code :393} and {@code SET CCUP-DETAILS-NOT-FETCHED} at
     * {@code :394}. Both are essential, because {@code INITIALIZE} would otherwise leave
     * {@code CDEMO-PGM-CONTEXT} at zero - which happens to be {@code ENTER} - and
     * {@code CCUP-CHANGE-ACTION} at a <em>space</em>, which is the second of
     * {@code CCUP-DETAILS-NOT-FETCHED}'s two values rather than the first. {@code SET} on a condition
     * name with several values assigns the <strong>first</strong>, so {@code :394} moves
     * {@code LOW-VALUES} and not a space; both bytes satisfy the condition, and the distinction is
     * visible in the returned commarea, so it is reproduced.
     *
     * <p>The {@code ELSE} arm is a pair of reference modifications, and COBOL reference modification is
     * <strong>1-based</strong>: {@code (1:160)} is the first 160 bytes and
     * {@code (161:}{@value CommArea#RECORD_LENGTH}{@code )} starts at 0-based offset 160. They are
     * reproduced as an actual byte split rather than as two assignments, so the offsets are exercised
     * rather than assumed - if either were wrong, every field after it would shift and the split would
     * be visibly wrong rather than subtly so.
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
            // :391-392 - INITIALIZE CARDDEMO-COMMAREA, WS-THIS-PROGCOMMAREA
            task.carddemoCommarea = NavigationContext.empty();
            task.thisProgCommarea = CommArea.initialised();
            // :393 - SET CDEMO-PGM-ENTER TO TRUE
            task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();
            // :394 - SET CCUP-DETAILS-NOT-FETCHED TO TRUE, which assigns the FIRST of its two values
            task.setChangeAction(ChangeAction.initial());
            return;
        }

        // The 489-byte DFHCOMMAREA as the caller passed it: CARDDEMO-COMMAREA then
        // WS-THIS-PROGCOMMAREA, contiguous.
        byte[] dfhcommarea = new byte[PASSED_COMMAREA_LENGTH];
        byte[] passedCommarea = task.carddemoCommarea.toFixedWidth(codec);
        byte[] passedTrailer = task.thisProgCommarea.encode(codec);
        System.arraycopy(passedCommarea, 0, dfhcommarea, 0, NavigationContext.COMMAREA_LENGTH);
        System.arraycopy(passedTrailer, 0, dfhcommarea, NavigationContext.COMMAREA_LENGTH,
                THIS_PROGCOMMAREA_LENGTH);

        // :396-397 - MOVE DFHCOMMAREA (1:LENGTH OF CARDDEMO-COMMAREA) TO CARDDEMO-COMMAREA
        byte[] commareaSpan = new byte[NavigationContext.COMMAREA_LENGTH];
        System.arraycopy(dfhcommarea, 0, commareaSpan, 0, NavigationContext.COMMAREA_LENGTH);
        task.carddemoCommarea = NavigationContext.fromFixedWidth(codec, commareaSpan);

        // :398-400 - MOVE DFHCOMMAREA (161:329) TO WS-THIS-PROGCOMMAREA
        byte[] trailerSpan = new byte[THIS_PROGCOMMAREA_LENGTH];
        System.arraycopy(dfhcommarea, NavigationContext.COMMAREA_LENGTH, trailerSpan, 0,
                THIS_PROGCOMMAREA_LENGTH);
        task.thisProgCommarea = CommArea.decode(trailerSpan, codec);
    }

    /**
     * {@code YYYY-STORE-PFKEY} - {@code COPY 'CSSTRPFY'} at {@code app/cbl/COCRDUPC.cbl:1528},
     * performed at {@code :406-407}.
     *
     * <p>The copybook is a bare {@code EVALUATE TRUE} over {@code EIBAID} with <strong>no
     * {@code WHEN OTHER}</strong> and no {@code DFHPA3} arm, and it does not clear
     * {@code CCARD-AID} first. So an attention identifier it does not recognise leaves the field exactly
     * as it was - which, because {@code :374} has just {@code INITIALIZE}d {@code CC-WORK-AREA}, is five
     * spaces, satisfying none of the sixteen condition names.
     * {@link PfKeyResolver#storePfKey(byte, Optional)} models that as an empty {@link Optional} and this
     * method honours it with {@code ifPresent} rather than a default, so nothing is invented. The invalid
     * key is then handled by {@link #validatePfKey}, exactly as the source handles it.
     *
     * @param task   this task's storage
     * @param eibAid the raw attention identifier byte
     */
    void storePfKeyYYYY(Conversation task, byte eibAid) {
        Optional<PfKeyResolver.AidKey> stored =
                PfKeyResolver.storePfKey(eibAid, task.ccWorkArea.aidKey());
        // ifPresent, never orElse: on no match nothing is stored, which is exactly what the copybook's
        // missing WHEN OTHER does.
        stored.ifPresent(task.ccWorkArea::setCcardAidCondition);
    }

    /**
     * The function-key validity test and the coercion - {@code app/cbl/COCRDUPC.cbl:413-424}:
     *
     * <pre>
     * SET PFK-INVALID TO TRUE
     * IF CCARD-AID-ENTER OR
     *    CCARD-AID-PFK03 OR
     *    (CCARD-AID-PFK05 AND CCUP-CHANGES-OK-NOT-CONFIRMED) OR
     *    (CCARD-AID-PFK12 AND NOT CCUP-DETAILS-NOT-FETCHED)
     *    SET PFK-VALID TO TRUE
     * END-IF
     * IF PFK-INVALID
     *    SET CCARD-AID-ENTER TO TRUE
     * END-IF
     * </pre>
     *
     * <p>Four keys are accepted and <strong>two of them only in a particular state</strong>, which is
     * what makes this more than a list: {@code PF5} is valid only while changes are validated but
     * unconfirmed, and {@code PF12} only once details have actually been fetched. Press either at the
     * wrong moment and it is not rejected - it becomes {@code ENTER} at {@code :423} and the screen
     * simply redisplays. Answering {@code 400} here, or emitting
     * {@link SystemMessages#CCDA_MSG_INVALID_KEY} as some sibling screens do, would be new behaviour;
     * {@code COCRDUPC} never uses that message.
     *
     * <p>The state-qualified halves are also the reason this test cannot be hoisted above
     * {@link #restoreCommarea}: {@code CCUP-CHANGE-ACTION} has to have been restored from the passed
     * commarea before it can be consulted.
     *
     * @param task this task's storage
     */
    void validatePfKey(Conversation task) {
        // :413 - SET PFK-INVALID TO TRUE. Invalid until proven otherwise.
        task.wsPfkFlag = PFK_INVALID;

        ChangeAction action = task.changeAction();
        boolean accepted = task.ccWorkArea.isCcardAidEnter()
                || task.ccWorkArea.isCcardAidPfk03()
                || (task.ccWorkArea.isCcardAidPfk05() && action.isChangesOkNotConfirmed())
                || (task.ccWorkArea.isCcardAidPfk12() && !action.isDetailsNotFetched());
        if (accepted) {
            // :419 - SET PFK-VALID TO TRUE
            task.wsPfkFlag = PFK_VALID;
        }

        // :422-424 - anything else becomes ENTER
        if (task.pfkInvalid()) {
            task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.ENTER);
        }
    }

    /**
     * The outer {@code EVALUATE TRUE} - {@code app/cbl/COCRDUPC.cbl:429-543}, five arms, first match
     * wins, in source order (gate G30).
     *
     * <p>Three of the five arms are themselves multi-{@code WHEN} OR-groups, so the arm count in the
     * source reads as nine {@code WHEN} clauses over five bodies:
     *
     * <ol>
     *   <li>{@code :435-476} - {@code PF3}, <em>or</em> an update that finished (successfully or not)
     *       having arrived from the card-list screen. Transfers control away.</li>
     *   <li>{@code :482-497} - a first entry from the card list, or {@code PF12} from it. The keys are
     *       already validated, so the card is fetched immediately.</li>
     *   <li>{@code :502-511} - a genuinely fresh entry. Prompt for the keys.</li>
     *   <li>{@code :517-528} - an update that finished having arrived from anywhere else. Reset and
     *       prompt again.</li>
     *   <li>{@code :535-542} - {@code WHEN OTHER}: an ordinary turn. Process the input, decide, paint.
     *       This is the only arm that reaches {@code 2000-DECIDE-ACTION}.</li>
     * </ol>
     *
     * <p>{@code CCUP-CHANGE-ACTION} is re-read at each test rather than captured once, because arms 1
     * and 4 test it and an earlier arm could in principle have changed it - the source re-evaluates the
     * condition names against live storage, and so does this.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void dispatch0000(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {

        // ---- :435-476  WHEN CCARD-AID-PFK03
        //                WHEN (CCUP-CHANGES-OKAYED-AND-DONE AND CDEMO-LAST-MAPSET = LIT-CCLISTMAPSET)
        //                WHEN (CCUP-CHANGES-FAILED       AND CDEMO-LAST-MAPSET = LIT-CCLISTMAPSET) ----
        if (task.ccWorkArea.isCcardAidPfk03()
                || (task.changeAction().isChangesOkayedAndDone() && lastMapsetIsCardList(task))
                || (task.changeAction().isChangesFailed() && lastMapsetIsCardList(task))) {
            backNavigationPfk03(response, task);
            return;
        }

        // ---- :482-497  WHEN CDEMO-PGM-ENTER  AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM
        //                WHEN CCARD-AID-PFK12  AND CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM -------------
        if ((task.carddemoCommarea.isEnter() && fromProgramIsCardList(task))
                || (task.ccWorkArea.isCcardAidPfk12() && fromProgramIsCardList(task))) {
            enterFromCardList(request, response, task);
            return;
        }

        // ---- :502-511  WHEN CCUP-DETAILS-NOT-FETCHED AND CDEMO-PGM-ENTER
        //                WHEN CDEMO-FROM-PROGRAM EQUAL LIT-MENUPGM AND NOT CDEMO-PGM-REENTER ----------
        if ((task.changeAction().isDetailsNotFetched() && task.carddemoCommarea.isEnter())
                || (fromProgramIsMenu(task) && !task.carddemoCommarea.isReenter())) {
            promptForSearchKeys(request, response, task);
            return;
        }

        // ---- :517-528  WHEN CCUP-CHANGES-OKAYED-AND-DONE
        //                WHEN CCUP-CHANGES-FAILED --------------------------------------------------
        if (task.changeAction().isChangesOkayedAndDone() || task.changeAction().isChangesFailed()) {
            resetAfterUpdate(request, response, task);
            return;
        }

        // ---- :535-542  WHEN OTHER ----------------------------------------------------------------
        processDecideAndSend(request, response, task);
    }

    /**
     * {@code CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET} - tested at
     * {@code app/cbl/COCRDUPC.cbl:437}, {@code :439}, {@code :459} and {@code :1238}.
     *
     * <p>Both items are {@code X(7)}, so the comparison is width for width with no truncation. This is
     * the one card-list literal the program uses; {@link #LIT_CCLISTMAP} beside it is the preserved
     * defect and is never compared against anything.
     *
     * @param task this task's storage
     * @return whether the previous screen was the card list
     */
    boolean lastMapsetIsCardList(Conversation task) {
        return lastMapsetIsCardList(task.carddemoCommarea);
    }

    /**
     * The same test against a commarea value directly, for the point in
     * {@link #backNavigationPfk03} where the local copy is mid-update.
     *
     * @param commarea the communication area to test
     * @return whether {@code CDEMO-LAST-MAPSET} names the card list's mapset
     */
    boolean lastMapsetIsCardList(NavigationContext commarea) {
        return LIT_CCLISTMAPSET.equals(
                codec.movePicX(commarea.lastMapset(), NavigationContext.LAST_MAPSET_LENGTH));
    }

    /**
     * {@code CDEMO-FROM-PROGRAM EQUAL LIT-CCLISTPGM} - tested at {@code app/cbl/COCRDUPC.cbl:483} and
     * {@code :485}. Both items are {@code X(8)}.
     *
     * @param task this task's storage
     * @return whether the calling program was the card-list screen
     */
    boolean fromProgramIsCardList(Conversation task) {
        return LIT_CCLISTPGM.equals(codec.movePicX(task.carddemoCommarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH));
    }

    /**
     * {@code CDEMO-FROM-PROGRAM EQUAL LIT-MENUPGM} - tested at {@code app/cbl/COCRDUPC.cbl:389} and
     * again at {@code :504}, and the two tests mean different things because of the different
     * {@code CDEMO-PGM-CONTEXT} qualification each carries.
     *
     * @param task this task's storage
     * @return whether the calling program was the main menu
     */
    boolean fromProgramIsMenu(Conversation task) {
        return LIT_MENUPGM.equals(codec.movePicX(task.carddemoCommarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH));
    }

    /**
     * Arm 1 - {@code app/cbl/COCRDUPC.cbl:440-476}: the operator pressed {@code PF3}, or an update has
     * finished and the conversation came from the card-list screen. Either way control leaves this
     * program.
     *
     * <p>{@code :440} does {@code SET CCARD-AID-PFK03 TO TRUE} <em>first</em>, which matters on the two
     * update-finished entries: the arm is reached with whatever key the operator actually pressed, and it
     * rewrites the AID so the returned work area says {@code PF3}. That is not cosmetic - it is the value
     * the next screen would see in a commarea if it looked, and it is what makes the three entries
     * indistinguishable afterwards.
     *
     * <p>Then the target is chosen: the calling transaction and program if the commarea names them, and
     * the main menu if either is {@code LOW-VALUES} or {@code SPACES} ({@code :442-454}). This screen
     * records itself as the new caller ({@code :456-457}). If the previous screen was the card list the
     * two carried identifiers are zeroed ({@code :459-462}) so the list re-derives its own selection.
     * Finally {@code :464-467} sets the user type, the program context and the last map and mapset, in
     * that order - and note that {@code LIT-THISMAPSET} is {@code X(8)} while
     * {@code CDEMO-LAST-MAPSET} is {@code X(7)}, so the {@code MOVE} at {@code :466} drops the literal's
     * trailing space.
     *
     * <p><strong>{@code SET CDEMO-USRTYP-USER TO TRUE} at {@code :464} is unconditional.</strong> An
     * administrator who reached this screen leaves it recorded as an ordinary user. That is the source's
     * behaviour and it is reproduced rather than corrected: this program performs no authorisation of any
     * kind, and adding one - or preserving an administrator flag the COBOL discards - would change the
     * security posture the migration is required to hold constant (practice B6).
     *
     * <p>{@code EXEC CICS SYNCPOINT} at {@code :469-471} has no counterpart here and needs none: this arm
     * has performed no I/O in this task, and the one paragraph that writes -
     * {@link CardUpdateService#writeProcessing} - opens and commits its own unit of work. There is
     * nothing outstanding for a syncpoint to commit.
     *
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void backNavigationPfk03(CardUpdateResponse response, Conversation task) {
        // :440 - SET CCARD-AID-PFK03 TO TRUE
        task.ccWorkArea.setCcardAidCondition(PfKeyResolver.AidKey.PFK03);

        NavigationContext commarea = task.carddemoCommarea;

        // :442-447 - the target transaction
        String toTranid = isLowValuesOrSpaces(commarea.fromTranid(),
                NavigationContext.FROM_TRANID_LENGTH)
                        ? LIT_MENUTRANID
                        : commarea.fromTranid();

        // :449-454 - the target program
        String toProgram = isLowValuesOrSpaces(commarea.fromProgram(),
                NavigationContext.FROM_PROGRAM_LENGTH)
                        ? LIT_MENUPGM
                        : commarea.fromProgram();

        commarea = commarea
                .withToTranid(codec.movePicX(toTranid, NavigationContext.TO_TRANID_LENGTH))
                .withToProgram(codec.movePicX(toProgram, NavigationContext.TO_PROGRAM_LENGTH))
                // :456-457 - and record that this screen is now the caller
                .withFromTranid(codec.movePicX(LIT_THISTRANID, NavigationContext.FROM_TRANID_LENGTH))
                .withFromProgram(codec.movePicX(LIT_THISPGM, NavigationContext.FROM_PROGRAM_LENGTH));

        // :459-462 - IF CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET MOVE ZEROS TO CDEMO-ACCT-ID,
        //            CDEMO-CARD-NUM. Tested BEFORE :466 overwrites CDEMO-LAST-MAPSET.
        if (lastMapsetIsCardList(commarea)) {
            commarea = commarea.withAcctId(0L).withCardNum(0L);
        }

        commarea = commarea
                // :464 - SET CDEMO-USRTYP-USER TO TRUE, unconditionally (practice B6)
                .withUserTypeUser()
                // :465 - SET CDEMO-PGM-ENTER TO TRUE
                .withPgmEnter()
                // :466-467 - the X(8) literal loses its trailing space in the X(7) receiver
                .withLastMapset(codec.movePicX(LIT_THISMAPSET, NavigationContext.LAST_MAPSET_LENGTH))
                .withLastMap(codec.movePicX(LIT_THISMAP, NavigationContext.LAST_MAP_LENGTH));

        task.carddemoCommarea = commarea;

        // :473-476 - EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)
        //
        // The XCTL passes CARDDEMO-COMMAREA and NOTHING ELSE - not WS-THIS-PROGCOMMAREA, which
        // COMMON-RETURN appends at :550-552 but this arm never reaches. So the trailer is deliberately
        // published at its initialised form rather than as this task left it: the program being
        // transferred to receives 160 bytes, and handing it 329 would give it state the source does not
        // pass (gate G40).
        response.setNavigationContext(commarea);
        response.setCardScreenState(task.ccWorkArea);
        response.setCommArea(CommArea.initialised());
        response.setNextProgram(commarea.toProgram());
        task.returned = true;
    }

    /**
     * Arm 2 - {@code app/cbl/COCRDUPC.cbl:486-497}: the conversation arrived from the card-list screen,
     * which has already validated the selection, so the card is fetched and shown for editing without
     * asking for anything.
     *
     * <p>Two details are easy to lose. First, {@code :490-491} take the identifiers from the
     * <em>communication area</em> and write them through the <em>numeric</em> views
     * {@code CC-ACCT-ID-N} and {@code CC-CARD-NUM-N}, so an eleven- or sixteen-digit value is zero-filled
     * on the left rather than space-padded on the right; the typed screen fields are not consulted at
     * all. Second, {@code :494} does {@code SET CCUP-SHOW-DETAILS TO TRUE}
     * <strong>unconditionally</strong> - unlike {@code 2000-DECIDE-ACTION}'s equivalent arm at
     * {@code :963-965}, which sets it only {@code IF FOUND-CARDS-FOR-ACCOUNT}. So a card that could not
     * be read still leaves this arm in the show-details state, with the read's error message on the
     * screen. That asymmetry between two arms doing ostensibly the same thing is real, it is trivially
     * "tidied away", and it is not tidied here (practice B5).
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void enterFromCardList(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        // :486 - SET CDEMO-PGM-REENTER TO TRUE
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        // :487 - SET INPUT-OK TO TRUE
        task.wsInputFlag = INPUT_OK;

        // :488-489 - both filters are declared valid without being edited
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        task.wsEditCardFlag = FLG_FILTER_ISVALID;

        // :490-491 - MOVE CDEMO-ACCT-ID TO CC-ACCT-ID-N and CDEMO-CARD-NUM TO CC-CARD-NUM-N. Numeric
        // receivers, so zero-filled on the LEFT.
        task.ccWorkArea.setCcAcctIdN(task.carddemoCommarea.acctId());
        task.ccWorkArea.setCcCardNumN(task.carddemoCommarea.cardNum());

        // :492-493 - PERFORM 9000-READ-DATA THRU 9000-READ-DATA-EXIT
        readData9000(task);

        // :494 - SET CCUP-SHOW-DETAILS TO TRUE, unconditionally
        task.setChangeAction(ChangeAction.showDetails());

        // :495-496 - PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT
        sendMap3000(request, response, task);

        // :497 - GO TO COMMON-RETURN
        commonReturn(response, task);
    }

    /**
     * Arm 3 - {@code app/cbl/COCRDUPC.cbl:506-511}: a genuinely fresh entry. Discard any screen state and
     * ask the operator for the two keys.
     *
     * <p><strong>The order of the four statements is load-bearing and looks wrong at first reading.</strong>
     * {@code 3000-SEND-MAP} runs at {@code :507-508}, <em>before</em>
     * {@code SET CDEMO-PGM-REENTER} at {@code :509} and before
     * {@code SET CCUP-DETAILS-NOT-FETCHED} at {@code :510}. Three consequences follow, and all three are
     * visible on the painted screen:
     *
     * <ul>
     *   <li>{@code 3200-SETUP-SCREEN-VARS} tests {@code IF CDEMO-PGM-ENTER} at {@code :1084} and, on this
     *       arm, that is still true - so it does nothing at all and every value field stays at the
     *       {@code LOW-VALUES} {@code 3100} left.</li>
     *   <li>{@code 3250-SETUP-INFOMSG}'s first arm is {@code WHEN CDEMO-PGM-ENTER} ({@code :1141}), so
     *       the informational line is {@link #PROMPT_FOR_SEARCH_KEYS} and not any of the seven arms
     *       below it.</li>
     *   <li>{@code 3300-SETUP-SCREEN-ATTRS}' four {@code '*'} markers are each guarded by
     *       {@code AND CDEMO-PGM-REENTER}, which is false here - so no field is marked, however blank it
     *       is.</li>
     * </ul>
     *
     * <p>Reordering the four statements to read more naturally would change all three. The
     * {@code INITIALIZE} at {@code :506} names {@code WS-THIS-PROGCOMMAREA} only, not
     * {@code WS-MISC-STORAGE}, so the flags and messages survive it - which is the difference between
     * this arm and arm 4.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void promptForSearchKeys(CardUpdateRequest request, CardUpdateResponse response,
            Conversation task) {
        // :506 - INITIALIZE WS-THIS-PROGCOMMAREA. All 329 bytes: the change action to LOW-VALUES and
        // both detail groups and the update record to spaces and zeros.
        task.thisProgCommarea = CommArea.initialised();

        // :507-508 - PERFORM 3000-SEND-MAP, while the context is still ENTER
        sendMap3000(request, response, task);

        // :509 - SET CDEMO-PGM-REENTER TO TRUE, after the screen has been painted
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        // :510 - SET CCUP-DETAILS-NOT-FETCHED TO TRUE, which assigns LOW-VALUES, the first of its two
        // values. INITIALIZE has already left it there, so this statement is redundant in effect and is
        // performed regardless (practice B5).
        task.setChangeAction(ChangeAction.initial());

        // :511 - GO TO COMMON-RETURN
        commonReturn(response, task);
    }

    /**
     * Arm 4 - {@code app/cbl/COCRDUPC.cbl:519-528}: an update has finished, successfully or not, and the
     * conversation did <em>not</em> come from the card-list screen. Reset everything and prompt for fresh
     * keys.
     *
     * <p>The {@code INITIALIZE} at {@code :519-522} names <strong>four</strong> operands, and the second
     * of them is what distinguishes this arm from arm 3: {@code WS-MISC-STORAGE} is wiped too, so
     * {@code WS-RETURN-MSG} and {@code WS-INFO-MSG} both go back to spaces along with all seven edit
     * flags and {@code WS-TRANID}. The operator therefore does <em>not</em> see
     * {@link #CONFIRM_UPDATE_SUCCESS} on this arm - {@code 3250-SETUP-INFOMSG} takes its
     * {@code WHEN CDEMO-PGM-ENTER} arm and paints {@link #PROMPT_FOR_SEARCH_KEYS} instead. The
     * confirmation is only ever seen through {@code 2000-DECIDE-ACTION}'s own turn, one call earlier.
     *
     * <p>The last two operands are the two carried identifiers, zeroed individually rather than as part
     * of the commarea, so the rest of {@code CARDDEMO-COMMAREA} - the calling program, the user identity,
     * the last map - survives untouched.
     *
     * <p>As in arm 3, {@code 3000-SEND-MAP} runs at {@code :524-525} while the context is
     * {@code ENTER} - set deliberately at {@code :523} - and the context becomes {@code REENTER} only
     * afterwards.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void resetAfterUpdate(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        // :519 - INITIALIZE WS-THIS-PROGCOMMAREA
        task.thisProgCommarea = CommArea.initialised();

        // :520 - INITIALIZE WS-MISC-STORAGE, which is what erases the previous turn's messages
        initializeMiscStorage(task);

        // :521-522 - INITIALIZE CDEMO-ACCT-ID, CDEMO-CARD-NUM. Both PIC 9, so zeros.
        task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L).withCardNum(0L);

        // :523 - SET CDEMO-PGM-ENTER TO TRUE
        task.carddemoCommarea = task.carddemoCommarea.withPgmEnter();

        // :524-525 - PERFORM 3000-SEND-MAP, while the context is ENTER
        sendMap3000(request, response, task);

        // :526 - SET CDEMO-PGM-REENTER TO TRUE
        task.carddemoCommarea = task.carddemoCommarea.withPgmReenter();

        // :527 - SET CCUP-DETAILS-NOT-FETCHED TO TRUE
        task.setChangeAction(ChangeAction.initial());

        // :528 - GO TO COMMON-RETURN
        commonReturn(response, task);
    }

    /**
     * Arm 5, {@code WHEN OTHER} - {@code app/cbl/COCRDUPC.cbl:536-542}: an ordinary turn. Read what the
     * operator typed, decide what it means, paint the result.
     *
     * <p>This is the only arm that reaches {@code 1000-PROCESS-INPUTS} and
     * {@code 2000-DECIDE-ACTION}, and therefore the only one from which a record can be written. The
     * three {@code PERFORM}s run in this order and none of them is conditional.
     *
     * @param request  the terminal input area
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void processDecideAndSend(CardUpdateRequest request, CardUpdateResponse response,
            Conversation task) {
        // :536-537 - PERFORM 1000-PROCESS-INPUTS THRU 1000-PROCESS-INPUTS-EXIT
        processInputs1000(request, task);

        // :538-539 - PERFORM 2000-DECIDE-ACTION THRU 2000-DECIDE-ACTION-EXIT
        decideAction2000(task);

        // :540-541 - PERFORM 3000-SEND-MAP THRU 3000-SEND-MAP-EXIT
        sendMap3000(request, response, task);

        // :542 - GO TO COMMON-RETURN
        commonReturn(response, task);
    }

    /**
     * {@code COMMON-RETURN} - {@code app/cbl/COCRDUPC.cbl:546-558}, the shared finalisation four of the
     * five arms transfer to.
     *
     * <pre>
     * MOVE WS-RETURN-MSG     TO CCARD-ERROR-MSG
     * MOVE CARDDEMO-COMMAREA    TO WS-COMMAREA
     * MOVE WS-THIS-PROGCOMMAREA TO
     *       WS-COMMAREA(LENGTH OF CARDDEMO-COMMAREA + 1: LENGTH OF WS-THIS-PROGCOMMAREA)
     * EXEC CICS RETURN TRANSID(LIT-THISTRANID) COMMAREA(WS-COMMAREA) LENGTH(LENGTH OF WS-COMMAREA)
     * </pre>
     *
     * <p>The two {@code MOVE}s into {@code WS-COMMAREA} build one 2,000-byte area out of two: the
     * 160-byte commarea left-justified and space-filled to the full width, then the
     * {@value CommArea#RECORD_LENGTH}-byte trailer overwriting bytes 161 to
     * {@value #PASSED_COMMAREA_LENGTH}. Everything beyond that stays spaces. The image is composed here
     * rather than assumed, because {@code :396-400} reads it back at exactly those offsets on the next
     * turn and an off-by-one would shift every field of the trailer.
     *
     * <p>{@code TRANSID(LIT-THISTRANID)} is what makes the conversation pseudo-conversational: the next
     * terminal input runs {@code CCUP} again, with this area handed back. In REST terms the client holds
     * the area and sends it with the next {@code PUT}, which is why every part of it is published on the
     * response and none of it is retained here (gate G37).
     *
     * @param response the map area being painted
     * @param task     this task's storage
     */
    void commonReturn(CardUpdateResponse response, Conversation task) {
        // :547 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG. Both X(75), so width for width.
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        // :549-552 - build WS-COMMAREA from the two areas at their 1-based offsets
        task.wsCommarea = composeReturnedCommarea(task);

        // :554-558 - EXEC CICS RETURN TRANSID('CCUP') COMMAREA(WS-COMMAREA) LENGTH(2000)
        response.setNavigationContext(task.carddemoCommarea);
        response.setCommArea(task.thisProgCommarea);
        response.setCardScreenState(task.ccWorkArea);
        // The next-screen triple 1000-PROCESS-INPUTS and 3400-SEND-SCREEN wrote into CC-WORK-AREA,
        // republished as the response's own navigation fields so a client does not have to reach into the
        // work area to learn where it is (gate G40). On the arms that never ran either paragraph these
        // are the INITIALIZEd spaces, which is exactly what the work area holds.
        response.transferTo(task.ccWorkArea.getCcardNextProg(),
                task.ccWorkArea.getCcardNextMapset(),
                task.ccWorkArea.getCcardNextMap());
        task.returned = true;
    }

    /**
     * {@code WS-COMMAREA} as {@code COMMON-RETURN} leaves it - {@code app/cbl/COCRDUPC.cbl:549-552}.
     *
     * <p>A {@code MOVE} of a 160-byte group into {@code PIC X(2000)} is left-justified and space-filled,
     * and the reference-modified {@code MOVE} that follows starts at 1-based position 161, which is
     * 0-based offset 160. Composed as an actual byte image so that the offsets are exercised.
     *
     * @param task this task's storage
     * @return the 2,000-character image, decoded back through the active code page
     */
    String composeReturnedCommarea(Conversation task) {
        byte[] area = CardScreenState.spaces(WS_COMMAREA_LENGTH).getBytes(codec.charset());
        byte[] commarea = task.carddemoCommarea.toFixedWidth(codec);
        byte[] trailer = task.thisProgCommarea.encode(codec);
        System.arraycopy(commarea, 0, area, 0, NavigationContext.COMMAREA_LENGTH);
        System.arraycopy(trailer, 0, area, NavigationContext.COMMAREA_LENGTH,
                THIS_PROGCOMMAREA_LENGTH);
        return new String(area, codec.charset());
    }

    // =================================================================================================
    // 1000-PROCESS-INPUTS and its two children - app/cbl/COCRDUPC.cbl:564-945.
    // =================================================================================================

    /**
     * {@code 1000-PROCESS-INPUTS} - {@code app/cbl/COCRDUPC.cbl:564-573}.
     *
     * <p>Receives the map, edits it, and then publishes the error line and the next-screen triple. The
     * order matters: {@code :569} moves {@code WS-RETURN-MSG} into {@code CCARD-ERROR-MSG} <em>after</em>
     * the edits have had their say, and {@code COMMON-RETURN} does the identical move again at
     * {@code :547}. Two identical assignments, and both are made, because the source makes both
     * (practice B5).
     *
     * @param request the terminal input area
     * @param task    this task's storage
     */
    void processInputs1000(CardUpdateRequest request, Conversation task) {
        // :565-566 - PERFORM 1100-RECEIVE-MAP THRU 1100-RECEIVE-MAP-EXIT
        receiveMap1100(request, task);

        // :567-568 - PERFORM 1200-EDIT-MAP-INPUTS THRU 1200-EDIT-MAP-INPUTS-EXIT
        editMapInputs1200(task);

        // :569 - MOVE WS-RETURN-MSG TO CCARD-ERROR-MSG
        task.ccWorkArea.setCcardErrorMsg(
                codec.movePicX(task.wsReturnMsg, CardScreenState.CCARD_ERROR_MSG_LENGTH));

        // :570-572 - the next screen is this screen again
        task.ccWorkArea.setCcardNextProg(
                codec.movePicX(LIT_THISPGM, CardScreenState.CCARD_NEXT_PROG_LENGTH));
        // LIT-THISMAPSET is X(8) and CCARD-NEXT-MAPSET is X(7): the trailing space is dropped.
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        task.ccWorkArea.setCcardNextMap(
                codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));
    }

    /**
     * {@code 1100-RECEIVE-MAP} - {@code app/cbl/COCRDUPC.cbl:578-636}: the typed screen fields become
     * {@code CCUP-NEW-DETAILS} and, for the two keys, {@code CC-WORK-AREA}.
     *
     * <p>{@code EXEC CICS RECEIVE MAP} at {@code :579-584} has no counterpart: the fields arrived in the
     * request body. Its {@code RESP} and {@code RESP2} are captured into
     * {@code WS-RESP-CD}/{@code WS-REAS-CD} by the {@code EXEC} and then never tested, so nothing is
     * recorded for them here either.
     *
     * <p><strong>The {@code '*'}-or-{@code SPACES} rule, applied field by field.</strong> Six of the
     * seven fields are tested for {@code '*'} or {@code SPACES} and, if either, receive
     * {@code LOW-VALUES} rather than what arrived. The {@code '*'} is not user input in normal use - it is
     * the marker {@code 3300-SETUP-SCREEN-ATTRS} painted onto a blank field on the previous turn - so this
     * is how the program stops its own marker from being read back as data. The test is
     * {@code = '*'} against the whole item, which for a {@code PIC X(50)} field means one asterisk
     * followed by forty-nine spaces, not an asterisk anywhere in it.
     *
     * <p><strong>{@code EXPDAY} is the exception, and deliberately.</strong> {@code :621} moves
     * {@code EXPDAYI} into {@code CCUP-NEW-EXPDAY} with <em>no</em> test at all. The field is
     * {@code ATTRB=(DRK,FSET,PROT)} in {@code app/bms/COCRDUP.bms:142} - dark, protected and always
     * transmitted - so the operator cannot type into it and it can never carry a marker;
     * {@code 3300} never marks it either, and {@code 3200} always paints it from
     * {@code CCUP-OLD-EXPDAY}. Adding the test for symmetry would change what an all-spaces day
     * becomes.
     *
     * @param request the terminal input area
     * @param task    this task's storage
     */
    void receiveMap1100(CardUpdateRequest request, Conversation task) {
        // :586 - INITIALIZE CCUP-NEW-DETAILS. All 89 bytes to spaces, before any field is moved in.
        CardDetails typed = CardDetails.initialised(DetailGroup.NEW);

        // :589-596 - ACCTSIDI. The one field whose value goes to two places: CC-ACCT-ID as well as
        // CCUP-NEW-ACCTID, because 1210-EDIT-ACCOUNT reads the work area and not the detail group.
        if (isAsteriskOrSpaces(request.getAcctsid(), CardUpdateRequest.ACCTSID_LENGTH)) {
            task.ccWorkArea.setCcAcctIdToLowValues();
            typed = typed.withAcctid(CardScreenState.lowValues(CardDetails.ACCTID_LENGTH));
        } else {
            task.ccWorkArea.setCcAcctId(
                    codec.movePicX(request.getAcctsid(), CardScreenState.CC_ACCT_ID_LENGTH));
            typed = typed.withAcctid(
                    codec.movePicX(request.getAcctsid(), CardDetails.ACCTID_LENGTH));
        }

        // :598-605 - CARDSIDI, the same shape for the card number
        if (isAsteriskOrSpaces(request.getCardsid(), CardUpdateRequest.CARDSID_LENGTH)) {
            task.ccWorkArea.setCcCardNumToLowValues();
            typed = typed.withCardid(CardScreenState.lowValues(CardDetails.CARDID_LENGTH));
        } else {
            task.ccWorkArea.setCcCardNum(
                    codec.movePicX(request.getCardsid(), CardScreenState.CC_CARD_NUM_LENGTH));
            typed = typed.withCardid(
                    codec.movePicX(request.getCardsid(), CardDetails.CARDID_LENGTH));
        }

        // :607-612 - CRDNAMEI
        typed = typed.withCrdname(
                isAsteriskOrSpaces(request.getCrdname(), CardUpdateRequest.CRDNAME_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.CRDNAME_LENGTH)
                        : codec.movePicX(request.getCrdname(), CardDetails.CRDNAME_LENGTH));

        // :614-619 - CRDSTCDI
        typed = typed.withCrdstcd(
                isAsteriskOrSpaces(request.getCrdstcd(), CardUpdateRequest.CRDSTCD_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.CRDSTCD_LENGTH)
                        : codec.movePicX(request.getCrdstcd(), CardDetails.CRDSTCD_LENGTH));

        // :621 - EXPDAYI, moved UNCONDITIONALLY. See the method documentation.
        typed = typed.withExpday(codec.movePicX(request.getExpday(), CardDetails.EXPDAY_LENGTH));

        // :623-628 - EXPMONI
        typed = typed.withExpmon(
                isAsteriskOrSpaces(request.getExpmon(), CardUpdateRequest.EXPMON_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.EXPMON_LENGTH)
                        : codec.movePicX(request.getExpmon(), CardDetails.EXPMON_LENGTH));

        // :630-635 - EXPYEARI
        typed = typed.withExpyear(
                isAsteriskOrSpaces(request.getExpyear(), CardUpdateRequest.EXPYEAR_LENGTH)
                        ? CardScreenState.lowValues(CardDetails.EXPYEAR_LENGTH)
                        : codec.movePicX(request.getExpyear(), CardDetails.EXPYEAR_LENGTH));

        task.setNewDetails(typed);
    }

    /**
     * {@code IF field = '*' OR field = SPACES} - the six-times-repeated test of
     * {@code app/cbl/COCRDUPC.cbl:589-635}.
     *
     * <p>Compared against a {@code PIC X(n)} item, the one-character literal {@code '*'} is padded on the
     * right with spaces to the item's length, so the condition is true for an asterisk followed by
     * {@code n - 1} spaces and false for an asterisk anywhere else in the field. Reproducing that
     * literally - rather than as "contains an asterisk" or "starts with an asterisk" - is what keeps a
     * card name of {@code "*STAR CARD"} a genuine value rather than a marker.
     *
     * @param value  the field's content
     * @param length the field's declared width
     * @return whether the field holds the marker, or is entirely spaces
     */
    static boolean isAsteriskOrSpaces(String value, int length) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        return PIC_X_CODEC.movePicX(FieldAttributeSetter.ASTERISK, length).equals(image)
                || CardScreenState.spaces(length).equals(image);
    }

    /**
     * Asserts that a commarea key the program itself wrote still holds what the program writes there,
     * before it is moved into a {@code PIC 9} receiver.
     *
     * <p>{@code CCUP-OLD-ACCTID PIC X(11)} and {@code CCUP-OLD-CARDID PIC X(16)} are alphanumeric, and
     * {@code app/cbl/COCRDUPC.cbl:671-672} moves them into {@code CDEMO-ACCT-ID PIC 9(11)} and
     * {@code CDEMO-CARD-NUM PIC 9(16)}. Their only writer is {@code :1006-1007}, which writes the digits
     * the {@code READ} returned, so on a real conversation the receiving numeric items always get digits
     * and the {@code MOVE} cannot fail. A hand-built payload can ask for the processing action while
     * leaving them blank; a numeric item cannot hold blanks, so that payload is refused here as the
     * caller's error rather than allowed to fail inside the {@code HANDLE ABEND} declarative.
     *
     * @param value    the commarea value as supplied
     * @param length   the item's declared {@code PIC X} width
     * @param member   the payload member to name in the answer
     * @param expected what the program writes there, as a shape rather than a value
     * @throws ScreenInputRejectedException if the value is not the declared count of digits
     */
    static void requireFetchedKey(String value, int length, String member, String expected) {
        String image = PIC_X_CODEC.movePicX(value == null ? "" : value, length);
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) < '0' || image.charAt(index) > '9') {
                throw ScreenInputRejectedException.inconsistentCommarea(member, expected);
            }
        }
    }

    /**
     * {@code 1200-EDIT-MAP-INPUTS} - {@code app/cbl/COCRDUPC.cbl:641-715}: two completely different
     * validations behind one paragraph name, chosen by whether details have been fetched yet.
     *
     * <h4>The search-key path, {@code :645-661}</h4>
     * When {@code CCUP-DETAILS-NOT-FETCHED}, only the two keys are edited. Then
     * {@code MOVE LOW-VALUES TO CCUP-NEW-CARDDATA} at {@code :653} wipes the fifty-nine bytes of typed
     * card data - the operator cannot have meant them, because no card has been shown yet - and if
     * <em>both</em> keys came back blank the message becomes {@link #NO_SEARCH_CRITERIA_RECEIVED},
     * replacing whichever of the two per-field prompts the edits had set. {@code GO TO
     * 1200-EDIT-MAP-INPUTS-EXIT} at {@code :661} then skips the whole second half.
     *
     * <h4>The card-data path, {@code :668-714}</h4>
     * Otherwise the keys are declared valid without being re-edited ({@code :669-670}), the fetched
     * snapshot is copied back into the commarea and into {@code CICS-OUTPUT-EDIT-VARS}
     * ({@code :671-677}), and the typed card data is compared with the fetched card data <em>folded to
     * upper case on both sides</em> ({@code :680-681}). Equality sets
     * {@link #NO_CHANGES_DETECTED}.
     *
     * <p>The three-way guard at {@code :685-693} then short-circuits: if nothing changed, or the changes
     * are already validated and awaiting confirmation, or the update is already done, all four card-data
     * flags are declared valid and the four edits are skipped entirely. That is what stops a confirmed
     * turn from re-validating - and it is also why {@code CCUP-CHANGES-OK-NOT-CONFIRMED} can survive into
     * {@code 2000-DECIDE-ACTION}'s fifth arm with no message set.
     *
     * <p>Only if none of the three holds does {@code :696} set {@code CCUP-CHANGES-NOT-OK} - so the state
     * becomes {@code 'E'} <em>before</em> the four field edits run, and is promoted back to {@code 'N'} at
     * {@code :713} only if none of them found an error.
     *
     * @param task this task's storage
     */
    void editMapInputs1200(Conversation task) {
        // :643 - SET INPUT-OK TO TRUE
        task.wsInputFlag = INPUT_OK;

        // :645-665 - IF CCUP-DETAILS-NOT-FETCHED ... ELSE CONTINUE
        if (task.changeAction().isDetailsNotFetched()) {
            // :647-648 - PERFORM 1210-EDIT-ACCOUNT
            editAccount1210(task);

            // :650-651 - PERFORM 1220-EDIT-CARD
            editCard1220(task);

            // :653 - MOVE LOW-VALUES TO CCUP-NEW-CARDDATA. The 59-byte group: name, the three expiry
            // components and the status. The account and card ids are NOT in it and survive.
            task.setNewDetails(lowValueCarddata(task.newDetails()));

            // :656-659 - IF FLG-ACCTFILTER-BLANK AND FLG-CARDFILTER-BLANK
            if (task.flgAcctfilterBlank() && task.flgCardfilterBlank()) {
                task.wsReturnMsg = NO_SEARCH_CRITERIA_RECEIVED;
            }

            // :661 - GO TO 1200-EDIT-MAP-INPUTS-EXIT
            return;
        }

        // :668 - SET FOUND-CARDS-FOR-ACCOUNT TO TRUE. This is WS-INFO-MSG, not WS-RETURN-MSG.
        task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;

        // :669-670 - both keys are valid by assumption: they were edited on the turn that fetched
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
        task.wsEditCardFlag = FLG_FILTER_ISVALID;

        CardDetails fetched = task.oldDetails();

        // Reached only when the change action says a card was already fetched, and the two MOVEs below
        // feed PIC 9 receivers. The only writer of CCUP-OLD-ACCTID and CCUP-OLD-CARDID is this program,
        // at :1006-1007, and it writes the eleven and sixteen digits it read - so on any conversation
        // that actually fetched a card these hold digits. A payload asking for the processing action
        // while leaving them blank describes a screen that was never fetched, and a numeric item cannot
        // hold blanks at all. Refused as the caller's error, ahead of the MOVEs, rather than letting the
        // numeric conversion fail inside the HANDLE ABEND declarative and answer an abend.
        requireFetchedKey(fetched.acctid(), CardDetails.ACCTID_LENGTH, "commArea.oldDetails.acctid",
                "the eleven digits of the fetched account identifier");
        requireFetchedKey(fetched.cardid(), CardDetails.CARDID_LENGTH, "commArea.oldDetails.cardid",
                "the sixteen digits of the fetched card number");

        // :671-672 - the fetched keys go back into the commarea. CDEMO-ACCT-ID is PIC 9(11) and
        // CDEMO-CARD-NUM PIC 9(16), while CCUP-OLD-ACCTID and CCUP-OLD-CARDID are alphanumeric, so each
        // MOVE crosses from X to 9 and the receiver is zero-filled on the left.
        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(codec.decodePic9(codec.movePicX(fetched.acctid(),
                        CardDetails.ACCTID_LENGTH)))
                .withCardNum(codec.decodePic9(codec.movePicX(fetched.cardid(),
                        CardDetails.CARDID_LENGTH)));

        // :673-677 - and into CICS-OUTPUT-EDIT-VARS. Note that the three expiry components are written
        // individually through the group REDEFINES at :117-121, never as the ten-byte whole, so the two
        // separator FILLER bytes keep whatever INITIALIZE left in them.
        task.cardNameEmbossedX =
                codec.movePicX(fetched.crdname(), CardRecord.CARD_EMBOSSED_NAME_LENGTH);
        task.cardStatusX =
                codec.movePicX(fetched.crdstcd(), CardRecord.CARD_ACTIVE_STATUS_LENGTH);
        task.cardExpiraionDateX = writeExpiryComponents(task.cardExpiraionDateX,
                fetched.expyear(), fetched.expmon(), fetched.expday());

        // :680-683 - IF FUNCTION UPPER-CASE(CCUP-NEW-CARDDATA) EQUAL
        //               FUNCTION UPPER-CASE(CCUP-OLD-CARDDATA)
        if (functionUpperCase(task.newDetails().ccupCarddata())
                .equals(functionUpperCase(fetched.ccupCarddata()))) {
            task.wsReturnMsg = NO_CHANGES_DETECTED;
        }

        // :685-693 - the three-way short circuit
        if (task.noChangesDetected()
                || task.changeAction().isChangesOkNotConfirmed()
                || task.changeAction().isChangesOkayedAndDone()) {
            task.wsEditCardnameFlag = FLG_FILTER_ISVALID;
            task.wsEditCardstatusFlag = FLG_FILTER_ISVALID;
            task.wsEditCardexpmonFlag = FLG_FILTER_ISVALID;
            task.wsEditCardexpyearFlag = FLG_FILTER_ISVALID;
            // :692 - GO TO 1200-EDIT-MAP-INPUTS-EXIT
            return;
        }

        // :696 - SET CCUP-CHANGES-NOT-OK TO TRUE, before the four edits run
        task.setChangeAction(ChangeAction.changesNotOk());

        // :698-708 - the four card-data edits, in this order
        editName1230(task);
        editCardStatus1240(task);
        editExpiryMon1250(task);
        editExpiryYear1260(task);

        // :710-714 - IF INPUT-ERROR CONTINUE ELSE SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
        if (!task.inputError()) {
            task.setChangeAction(ChangeAction.changesOkNotConfirmed());
        }
    }

    /**
     * {@code MOVE LOW-VALUES TO CCUP-NEW-CARDDATA} - {@code app/cbl/COCRDUPC.cbl:653}.
     *
     * <p>{@code CCUP-NEW-CARDDATA} is the group at {@code :307-313}: the fifty-byte name, the
     * four-, two- and two-byte expiry components and the one-byte status - fifty-nine bytes in all. The
     * account and card identifiers sit <em>outside</em> it, at {@code :304-306}, and are not touched;
     * that is what lets the search keys survive an edit that wipes the card data.
     *
     * @param details the typed detail group
     * @return the same group with its five card-data items at {@code LOW-VALUES}
     */
    static CardDetails lowValueCarddata(CardDetails details) {
        return details
                .withCrdname(CardScreenState.lowValues(CardDetails.CRDNAME_LENGTH))
                .withExpyear(CardScreenState.lowValues(CardDetails.EXPYEAR_LENGTH))
                .withExpmon(CardScreenState.lowValues(CardDetails.EXPMON_LENGTH))
                .withExpday(CardScreenState.lowValues(CardDetails.EXPDAY_LENGTH))
                .withCrdstcd(CardScreenState.lowValues(CardDetails.CRDSTCD_LENGTH));
    }

    /**
     * Writes the three components of the {@code CARD-EXPIRAION-DATE-X} group {@code REDEFINES} -
     * {@code app/cbl/COCRDUPC.cbl:116-121} - leaving the two {@code FILLER} bytes at whatever they held.
     *
     * <p>The layout is {@code CARD-EXPIRY-YEAR X(4)} + {@code FILLER X(1)} +
     * {@code CARD-EXPIRY-MONTH X(2)} + {@code FILLER X(1)} + {@code CARD-EXPIRY-DAY X(2)}, so the two
     * fillers sit at 0-based offsets 4 and 7 - which is where the {@code '-'} separators would be in a
     * {@code YYYY-MM-DD} value. {@code :675-677} assigns the three components and nothing else, so on a
     * freshly initialised item the separators are <em>spaces</em> rather than hyphens, and the ten-byte
     * view therefore does not read as a date. Nothing in this program reads the ten-byte view, so that is
     * invisible - and reproducing it rather than helpfully inserting hyphens is what keeps it invisible
     * in the same way (practice B5).
     *
     * @param current the item's current ten-byte content, whose filler bytes are preserved
     * @param year    the four-character year
     * @param month   the two-character month
     * @param day     the two-character day
     * @return the ten-character image
     */
    String writeExpiryComponents(String current, String year, String month, String day) {
        char[] image = codec.movePicX(current, CardRecord.CARD_EXPIRAION_DATE_LENGTH).toCharArray();
        char[] yearImage = codec.movePicX(year, CardRecord.EXPIRAION_YEAR_END_INDEX
                - CardRecord.EXPIRAION_YEAR_BEGIN_INDEX).toCharArray();
        char[] monthImage = codec.movePicX(month, CardRecord.EXPIRAION_MONTH_END_INDEX
                - CardRecord.EXPIRAION_MONTH_BEGIN_INDEX).toCharArray();
        char[] dayImage = codec.movePicX(day, CardRecord.EXPIRAION_DAY_END_INDEX
                - CardRecord.EXPIRAION_DAY_BEGIN_INDEX).toCharArray();
        System.arraycopy(yearImage, 0, image, CardRecord.EXPIRAION_YEAR_BEGIN_INDEX,
                yearImage.length);
        System.arraycopy(monthImage, 0, image, CardRecord.EXPIRAION_MONTH_BEGIN_INDEX,
                monthImage.length);
        System.arraycopy(dayImage, 0, image, CardRecord.EXPIRAION_DAY_BEGIN_INDEX, dayImage.length);
        return new String(image);
    }

    // =================================================================================================
    // The six edit paragraphs - app/cbl/COCRDUPC.cbl:721-947.
    //
    // All six share one shape: assume rejected, test for "not supplied" and leave if so, test for
    // well-formedness and leave if not, and only then declare valid. Each rejection sets INPUT-ERROR
    // unconditionally and its own message ONLY IF the return message is still off, so the FIRST field to
    // fail is the one the operator is told about while every failing field is still flagged and
    // highlighted. Getting that nesting wrong shows the last error instead of the first.
    //
    // The two key edits and the four card-data edits are never both performed on one turn: 1200 chooses
    // one path or the other.
    // =================================================================================================

    /**
     * {@code 1210-EDIT-ACCOUNT} - {@code app/cbl/COCRDUPC.cbl:721-756}.
     *
     * <p>Three outcomes: not supplied, not numeric, or valid. "Not supplied" is three disjuncts -
     * {@code LOW-VALUES}, {@code SPACES} or the numeric view reading as zeros - and the third is what
     * makes an account number of eleven {@code '0'} characters count as absent rather than as account
     * zero.
     *
     * <p>The not-numeric arm moves a <strong>direct literal</strong> into {@code WS-RETURN-MSG}
     * ({@code :744-746}) rather than setting {@link #SEARCHED_ACCT_NOT_NUMERIC}, which was declared for
     * exactly this purpose and is left unused. Both texts are transcribed; only the one the code uses is
     * ever assigned (practice B5).
     *
     * <p>Note the asymmetry with {@link #editCard1220}: this paragraph's blank arm clears
     * {@code CCUP-NEW-ACCTID} to {@code LOW-VALUES} ({@code :734}) while the card's clears its own field
     * to the character {@code '0'} eleven or sixteen times over. And this paragraph's valid arm moves the
     * <em>alphanumeric</em> {@code CC-ACCT-ID} into both receivers ({@code :752-753}) while the card's
     * moves the numeric view into one and the alphanumeric into the other. Neither difference is
     * cosmetic - each changes bytes in the returned commarea - and both are reproduced.
     *
     * @param task this task's storage
     */
    void editAccount1210(Conversation task) {
        // :722 - SET FLG-ACCTFILTER-NOT-OK TO TRUE
        task.wsEditAcctFlag = FLG_FILTER_NOT_OK;

        // :725-736 - not supplied
        if (task.ccWorkArea.isCcAcctIdLowValues()
                || task.ccWorkArea.isCcAcctIdSpaces()
                || task.ccWorkArea.isCcAcctIdNZeros()) {
            // :728 - SET INPUT-ERROR TO TRUE, unconditionally
            task.wsInputFlag = INPUT_ERROR;
            // :729 - SET FLG-ACCTFILTER-BLANK TO TRUE
            task.wsEditAcctFlag = FLG_FILTER_BLANK;
            // :730-732 - the message, ONLY if none has been set yet
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_ACCT;
            }
            // :733 - MOVE ZEROES TO CDEMO-ACCT-ID
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            // :734 - MOVE LOW-VALUES TO CCUP-NEW-ACCTID
            task.setNewDetails(task.newDetails()
                    .withAcctid(CardScreenState.lowValues(CardDetails.ACCTID_LENGTH)));
            // :735 - GO TO 1210-EDIT-ACCOUNT-EXIT
            return;
        }

        // :740-750 - IF CC-ACCT-ID IS NOT NUMERIC
        if (!task.ccWorkArea.isCcAcctIdNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            // :742 - SET FLG-ACCTFILTER-NOT-OK TO TRUE, redundantly: :722 already did (practice B5)
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                // :744-746 - a direct MOVE, not the condition name declared at :191-192
                task.wsReturnMsg = ACCOUNT_FILTER_MUST_BE_11_DIGITS;
            }
            // :748-749
            task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L);
            task.setNewDetails(task.newDetails()
                    .withAcctid(CardScreenState.lowValues(CardDetails.ACCTID_LENGTH)));
            // :750 - GO TO 1210-EDIT-ACCOUNT-EXIT
            return;
        }

        // :751-755 - the ELSE. MOVE CC-ACCT-ID TO CDEMO-ACCT-ID CCUP-NEW-ACCTID: one alphanumeric sender
        // into a PIC 9(11) receiver and a PIC X(11) receiver.
        task.carddemoCommarea = task.carddemoCommarea
                .withAcctId(codec.decodePic9(task.ccWorkArea.getCcAcctId()));
        task.setNewDetails(task.newDetails()
                .withAcctid(codec.movePicX(task.ccWorkArea.getCcAcctId(),
                        CardDetails.ACCTID_LENGTH)));
        // :754 - SET FLG-ACCTFILTER-ISVALID TO TRUE
        task.wsEditAcctFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 1220-EDIT-CARD} - {@code app/cbl/COCRDUPC.cbl:762-800}.
     *
     * <p>The same three outcomes as {@link #editAccount1210} and two deliberate differences from it:
     *
     * <ul>
     *   <li>{@code :777-778} is one {@code MOVE ZEROES} into <strong>both</strong>
     *       {@code CDEMO-CARD-NUM} and {@code CCUP-NEW-CARDID}. The first is {@code PIC 9(16)} so it
     *       becomes the number zero; the second is {@code PIC X(16)} so it becomes
     *       <em>sixteen {@code '0'} characters</em>. The account's equivalent arm uses
     *       {@code LOW-VALUES} for its alphanumeric receiver, so the two blank arms leave genuinely
     *       different bytes in the commarea.</li>
     *   <li>{@code :796-797} moves the <em>numeric</em> view {@code CC-CARD-NUM-N} into
     *       {@code CDEMO-CARD-NUM} and the alphanumeric {@code CC-CARD-NUM} into
     *       {@code CCUP-NEW-CARDID}, as two statements. The account's valid arm uses one statement with
     *       two receivers.</li>
     * </ul>
     *
     * <p>Both are the kind of difference a translator "harmonises" without noticing, and neither is
     * harmonised here.
     *
     * @param task this task's storage
     */
    void editCard1220(Conversation task) {
        // :765 - SET FLG-CARDFILTER-NOT-OK TO TRUE
        task.wsEditCardFlag = FLG_FILTER_NOT_OK;

        // :768-780 - not supplied
        if (task.ccWorkArea.isCcCardNumLowValues()
                || task.ccWorkArea.isCcCardNumSpaces()
                || task.ccWorkArea.isCcCardNumNZeros()) {
            // :771 - SET INPUT-ERROR TO TRUE
            task.wsInputFlag = INPUT_ERROR;
            // :772 - SET FLG-CARDFILTER-BLANK TO TRUE
            task.wsEditCardFlag = FLG_FILTER_BLANK;
            // :773-775 - the message, ONLY if none has been set yet
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_CARD;
            }
            // :777-778 - MOVE ZEROES TO CDEMO-CARD-NUM, CCUP-NEW-CARDID. Sixteen '0' characters in the
            // alphanumeric receiver, NOT low values.
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            task.setNewDetails(task.newDetails()
                    .withCardid("0".repeat(CardDetails.CARDID_LENGTH)));
            // :779 - GO TO 1220-EDIT-CARD-EXIT
            return;
        }

        // :784-794 - IF CC-CARD-NUM IS NOT NUMERIC
        if (!task.ccWorkArea.isCcCardNumNumeric()) {
            task.wsInputFlag = INPUT_ERROR;
            // :786 - redundant with :765 (practice B5)
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                // :788-790 - a direct MOVE, not the condition name declared at :193-194
                task.wsReturnMsg = CARD_ID_FILTER_MUST_BE_16_DIGITS;
            }
            // :792 - MOVE ZERO TO CDEMO-CARD-NUM
            task.carddemoCommarea = task.carddemoCommarea.withCardNum(0L);
            // :793 - MOVE LOW-VALUES TO CCUP-NEW-CARDID. Note: LOW-VALUES here, '0' characters above.
            task.setNewDetails(task.newDetails()
                    .withCardid(CardScreenState.lowValues(CardDetails.CARDID_LENGTH)));
            // :794 - GO TO 1220-EDIT-CARD-EXIT
            return;
        }

        // :795-799 - the ELSE, as two separate MOVEs from two different views
        task.carddemoCommarea =
                task.carddemoCommarea.withCardNum(task.ccWorkArea.getCcCardNumN());
        task.setNewDetails(task.newDetails()
                .withCardid(codec.movePicX(task.ccWorkArea.getCcCardNum(),
                        CardDetails.CARDID_LENGTH)));
        // :798 - SET FLG-CARDFILTER-ISVALID TO TRUE
        task.wsEditCardFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 1230-EDIT-NAME} - {@code app/cbl/COCRDUPC.cbl:806-840}: the embossed name must be supplied
     * and must contain nothing but letters and spaces.
     *
     * <p>The alphabetic test is not a character-class test. {@code :823-826} copies the name into
     * {@code CARD-NAME-CHECK} and then erases every letter from it with
     * {@code INSPECT ... CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO} - fifty-two letters to
     * fifty-two spaces - and {@code :828} asks whether trimming what is left yields nothing. So a name
     * passes exactly when every character was a letter or already a space, and it is the
     * <em>program's own declared translation table</em> that decides which characters count as letters,
     * not a locale and not {@link Character#isLetter(char)}.
     *
     * <p>Note that a name containing {@code LOW-VALUES} in the middle - {@code "AB"} followed by
     * forty-eight {@code x'00'} bytes - passes the not-supplied test and then fails here, because
     * {@code x'00'} is neither a letter nor a space and {@code FUNCTION TRIM} does not remove it. That is
     * the source's behaviour and it is reachable, so it is tested.
     *
     * @param task this task's storage
     */
    void editName1230(Conversation task) {
        // :808 - SET FLG-CARDNAME-NOT-OK TO TRUE
        task.wsEditCardnameFlag = FLG_FILTER_NOT_OK;

        String typed = task.newDetails().crdname();

        // :811-820 - not supplied: LOW-VALUES, SPACES or fifty '0' characters
        if (isLowValuesOrSpaces(typed, CardDetails.CRDNAME_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.CRDNAME_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            // :815 - SET FLG-CARDNAME-BLANK TO TRUE
            task.wsEditCardnameFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_PROMPT_FOR_NAME;
            }
            // :819 - GO TO 1230-EDIT-NAME-EXIT
            return;
        }

        // :823 - MOVE CCUP-NEW-CRDNAME TO CARD-NAME-CHECK. Both X(50), so width for width.
        task.cardNameCheck = codec.movePicX(typed, CARD_NAME_CHECK_LENGTH);
        // :824-826 - INSPECT CARD-NAME-CHECK CONVERTING LIT-ALL-ALPHA-FROM TO LIT-ALL-SPACES-TO
        task.cardNameCheck =
                inspectConverting(task.cardNameCheck, LIT_ALL_ALPHA_FROM, LIT_ALL_SPACES_TO);

        // :828-837 - IF FUNCTION LENGTH(FUNCTION TRIM(CARD-NAME-CHECK)) = 0 CONTINUE ELSE ...
        if (!isTrimmedEmpty(task.cardNameCheck)) {
            task.wsInputFlag = INPUT_ERROR;
            // :832 - SET FLG-CARDNAME-NOT-OK TO TRUE, redundantly with :808 (practice B5)
            task.wsEditCardnameFlag = FLG_FILTER_NOT_OK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = WS_NAME_MUST_BE_ALPHA;
            }
            // :836 - GO TO 1230-EDIT-NAME-EXIT
            return;
        }

        // :839 - SET FLG-CARDNAME-ISVALID TO TRUE
        task.wsEditCardnameFlag = FLG_FILTER_ISVALID;
    }

    /**
     * {@code 1240-EDIT-CARDSTATUS} - {@code app/cbl/COCRDUPC.cbl:845-874}: the active-status flag must be
     * {@code 'Y'} or {@code 'N'}.
     *
     * <p>The test goes through {@code FLG-YES-NO-CHECK} and its {@code 88 FLG-YES-NO-VALID VALUES 'Y',
     * 'N'} ({@code :89-91}) rather than comparing the field directly, and the distinction shows: the
     * condition name lists exactly two values, so lower-case {@code 'y'} is rejected. No case folding is
     * applied to this field anywhere in the program - unlike the embossed name, which
     * {@code 9000-READ-DATA} folds - so {@code 'y'} really does fail (practice B5).
     *
     * <p>Both rejection arms set {@link #CARD_STATUS_MUST_BE_YES_NO}: the same message for "not supplied"
     * and for "supplied but wrong", which is unusual enough to look like a mistake and is exactly what
     * {@code :856} and {@code :869} both do.
     *
     * @param task this task's storage
     */
    void editCardStatus1240(Conversation task) {
        // :847 - SET FLG-CARDSTATUS-NOT-OK TO TRUE
        task.wsEditCardstatusFlag = FLG_FILTER_NOT_OK;

        String typed = task.newDetails().crdstcd();

        // :850-859 - not supplied
        if (isLowValuesOrSpaces(typed, CardDetails.CRDSTCD_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.CRDSTCD_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            // :854 - SET FLG-CARDSTATUS-BLANK TO TRUE
            task.wsEditCardstatusFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_STATUS_MUST_BE_YES_NO;
            }
            // :858 - GO TO 1240-EDIT-CARDSTATUS-EXIT
            return;
        }

        // :861 - MOVE CCUP-NEW-CRDSTCD TO FLG-YES-NO-CHECK
        task.flgYesNoCheck = codec.movePicX(typed, CardDetails.CRDSTCD_LENGTH);

        // :863-872 - IF FLG-YES-NO-VALID ... ELSE ...
        if (task.flgYesNoValid()) {
            // :864 - SET FLG-CARDSTATUS-ISVALID TO TRUE
            task.wsEditCardstatusFlag = FLG_FILTER_ISVALID;
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        // :867 - redundant with :847 (practice B5)
        task.wsEditCardstatusFlag = FLG_FILTER_NOT_OK;
        if (task.returnMessageOff()) {
            task.wsReturnMsg = CARD_STATUS_MUST_BE_YES_NO;
        }
        // :871 - GO TO 1240-EDIT-CARDSTATUS-EXIT
    }

    /**
     * {@code 1250-EDIT-EXPIRY-MON} - {@code app/cbl/COCRDUPC.cbl:877-910}: the expiry month must be one
     * to twelve.
     *
     * <p>The range test reads {@code CARD-MONTH-CHECK} through its numeric {@code REDEFINES} view
     * ({@code :92-95}), which is a byte reinterpretation rather than a parse - see
     * {@link #zonedDigitsValue(String, int, FixedWidthCodec)}. So {@code '1A'} reads as 11 and passes,
     * and {@code '  '} reads as 0 and fails. Neither throws, and neither is what
     * {@link Integer#parseInt(String)} would have done.
     *
     * <p>The source comment above the range test says "Must be numeric" - and no numeric test is
     * performed. The comment is aspirational and the code is what it is (practice B5).
     *
     * @param task this task's storage
     */
    void editExpiryMon1250(Conversation task) {
        // :880 - SET FLG-CARDEXPMON-NOT-OK TO TRUE
        task.wsEditCardexpmonFlag = FLG_FILTER_NOT_OK;

        String typed = task.newDetails().expmon();

        // :883-892 - not supplied
        if (isLowValuesOrSpaces(typed, CardDetails.EXPMON_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.EXPMON_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            // :887 - SET FLG-CARDEXPMON-BLANK TO TRUE
            task.wsEditCardexpmonFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_EXPIRY_MONTH_NOT_VALID;
            }
            // :891 - GO TO 1250-EDIT-EXPIRY-MON-EXIT
            return;
        }

        // :896 - MOVE CCUP-NEW-EXPMON TO CARD-MONTH-CHECK
        task.cardMonthCheck = codec.movePicX(typed, CARD_MONTH_CHECK_LENGTH);

        // :898-907 - IF VALID-MONTH (88 VALUES 1 THRU 12) ... ELSE ...
        long month = task.cardMonthCheckN(codec);
        if (month >= VALID_MONTH_MINIMUM && month <= VALID_MONTH_MAXIMUM) {
            // :899 - SET FLG-CARDEXPMON-ISVALID TO TRUE
            task.wsEditCardexpmonFlag = FLG_FILTER_ISVALID;
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        // :902 - redundant with :880 (practice B5)
        task.wsEditCardexpmonFlag = FLG_FILTER_NOT_OK;
        if (task.returnMessageOff()) {
            task.wsReturnMsg = CARD_EXPIRY_MONTH_NOT_VALID;
        }
        // :906 - GO TO 1250-EDIT-EXPIRY-MON-EXIT
    }

    /**
     * {@code 1260-EDIT-EXPIRY-YEAR} - {@code app/cbl/COCRDUPC.cbl:913-945}: the expiry year must be 1950
     * to 2099.
     *
     * <p><strong>The paragraph opens differently from its five siblings.</strong> The other five begin
     * with {@code SET FLG-*-NOT-OK TO TRUE}; this one begins with the not-supplied test at {@code :916}
     * and only reaches {@code SET FLG-CARDEXPYEAR-NOT-OK TO TRUE} at {@code :930}, after that test has
     * been passed. The observable outcome happens to be the same, because the blank arm assigns
     * {@code FLG-CARDEXPYEAR-BLANK} which would have overwritten {@code NOT-OK} anyway - but the
     * statement order is the source's and is kept, so a reader comparing the two files finds them
     * aligned (practice B5).
     *
     * @param task this task's storage
     */
    void editExpiryYear1260(Conversation task) {
        String typed = task.newDetails().expyear();

        // :916-925 - not supplied. Note that NO flag has been set before this test, unlike the other five
        // edit paragraphs.
        if (isLowValuesOrSpaces(typed, CardDetails.EXPYEAR_LENGTH)
                || isAllZeroCharacters(typed, CardDetails.EXPYEAR_LENGTH)) {
            task.wsInputFlag = INPUT_ERROR;
            // :920 - SET FLG-CARDEXPYEAR-BLANK TO TRUE
            task.wsEditCardexpyearFlag = FLG_FILTER_BLANK;
            if (task.returnMessageOff()) {
                task.wsReturnMsg = CARD_EXPIRY_YEAR_NOT_VALID;
            }
            // :924 - GO TO 1260-EDIT-EXPIRY-YEAR-EXIT
            return;
        }

        // :930 - SET FLG-CARDEXPYEAR-NOT-OK TO TRUE, only now
        task.wsEditCardexpyearFlag = FLG_FILTER_NOT_OK;

        // :932 - MOVE CCUP-NEW-EXPYEAR TO CARD-YEAR-CHECK
        task.cardYearCheck = codec.movePicX(typed, CARD_YEAR_CHECK_LENGTH);

        // :934-943 - IF VALID-YEAR (88 VALUES 1950 THRU 2099) ... ELSE ...
        long year = task.cardYearCheckN(codec);
        if (year >= VALID_YEAR_MINIMUM && year <= VALID_YEAR_MAXIMUM) {
            // :935 - SET FLG-CARDEXPYEAR-ISVALID TO TRUE
            task.wsEditCardexpyearFlag = FLG_FILTER_ISVALID;
            return;
        }
        task.wsInputFlag = INPUT_ERROR;
        // :938 - redundant with :930 (practice B5)
        task.wsEditCardexpyearFlag = FLG_FILTER_NOT_OK;
        if (task.returnMessageOff()) {
            task.wsReturnMsg = CARD_EXPIRY_YEAR_NOT_VALID;
        }
        // :942 - GO TO 1260-EDIT-EXPIRY-YEAR-EXIT
    }

    // =================================================================================================
    // 2000-DECIDE-ACTION - app/cbl/COCRDUPC.cbl:948-1030. The state machine, and the densest
    // order-dependent surface in the program.
    // =================================================================================================

    /**
     * {@code 2000-DECIDE-ACTION} - the eight-arm {@code EVALUATE TRUE} at
     * {@code app/cbl/COCRDUPC.cbl:949-1027}, in source order, first match wins (gate G30).
     *
     * <p>Reached only from the outer dispatcher's {@code WHEN OTHER} arm, and only after
     * {@code 1000-PROCESS-INPUTS} has edited the input. Its job is to move
     * {@code CCUP-CHANGE-ACTION} on by one step; every screen the operator sees afterwards is decided by
     * the value this paragraph leaves.
     *
     * <ol>
     *   <li><strong>{@code :954} {@code WHEN CCUP-DETAILS-NOT-FETCHED} - no body of its own</strong>, and
     *       <strong>{@code :958} {@code WHEN CCARD-AID-PFK12}</strong> carries the body they share
     *       ({@code :959-966}). Two consecutive {@code WHEN} clauses with no statement between them are
     *       an OR-group; COBOL has no implicit fall-through, so this is not one. Treating arm 1 as a
     *       no-op would make the very first {@code ENTER} on a freshly prompted screen do nothing at all,
     *       leaving the operator's keys typed and unfetched. Written here as a single {@code ||}.
     *       <p>The body itself is doubly guarded: the read happens only if <em>both</em> key flags are
     *       valid, and {@code CCUP-SHOW-DETAILS} is set only if the read found a card - which is the
     *       asymmetry with {@link #enterFromCardList}, where the same {@code SET} is
     *       unconditional.</li>
     *   <li>{@code :971-977} {@code WHEN CCUP-SHOW-DETAILS} - details are on the screen, so the typed
     *       changes are ready to be confirmed unless the edits rejected something or nothing actually
     *       changed. The {@code IF ... CONTINUE ELSE ...} is reproduced as a guarded assignment.</li>
     *   <li>{@code :982-983} {@code WHEN CCUP-CHANGES-NOT-OK} - {@code CONTINUE}. The state stays
     *       {@code 'E'} and {@code 3250} paints {@link #PROMPT_FOR_CHANGES}. An empty arm, and a real
     *       one: without it, control would fall to {@code WHEN OTHER} and abend.</li>
     *   <li>{@code :988-1001} {@code WHEN CCUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05} - the
     *       confirmation. This is the only path that writes. See {@link #writeProcessing9200}.</li>
     *   <li>{@code :1006-1007} {@code WHEN CCUP-CHANGES-OK-NOT-CONFIRMED} <strong>again, bare</strong> -
     *       {@code CONTINUE}. Reachable only because arm 4 was tested first, and it is what makes
     *       "validated but the operator has not pressed {@code PF5}" a state rather than an error.
     *       Inverting arms 4 and 5 would make {@code PF5} stop saving, silently.</li>
     *   <li>{@code :1011-1018} {@code WHEN CCUP-CHANGES-OKAYED-AND-DONE} - the update has just
     *       succeeded. Go back to showing details, and if there is no calling transaction to return to,
     *       clear the carried identifiers. <strong>{@code ZEROES} for the two identifiers and
     *       {@code LOW-VALUES} for the account status</strong> ({@code :1015-1017}) - two different fill
     *       bytes in adjacent statements, and both are reproduced.</li>
     *   <li>{@code :1019-1026} {@code WHEN OTHER} - an unreachable state, which abends. See
     *       {@link #unexpectedDataScenario}.</li>
     * </ol>
     *
     * <p>Arms 3 and 5 are deliberately written as empty {@code if} bodies with a comment rather than
     * collapsed away. An empty {@code CONTINUE} arm is not the same as an absent arm - it stops control
     * reaching {@code WHEN OTHER} - and writing it out is what makes that visible.
     *
     * @param task this task's storage
     * @throws AbendException from {@code WHEN OTHER}, through {@code ABEND-ROUTINE}
     */
    void decideAction2000(Conversation task) {
        ChangeAction action = task.changeAction();

        // ---- :954 WHEN CCUP-DETAILS-NOT-FETCHED (no body)
        //      :958 WHEN CCARD-AID-PFK12          (the shared body, :959-966) ------------------------
        if (action.isDetailsNotFetched() || task.ccWorkArea.isCcardAidPfk12()) {
            // :959-960 - IF FLG-ACCTFILTER-ISVALID AND FLG-CARDFILTER-ISVALID
            if (task.flgAcctfilterIsvalid() && task.flgCardfilterIsvalid()) {
                // :961-962 - PERFORM 9000-READ-DATA THRU 9000-READ-DATA-EXIT
                readData9000(task);
                // :963-965 - IF FOUND-CARDS-FOR-ACCOUNT SET CCUP-SHOW-DETAILS TO TRUE
                if (task.foundCardsForAccount()) {
                    task.setChangeAction(ChangeAction.showDetails());
                }
            }
            return;
        }

        // ---- :971-977 WHEN CCUP-SHOW-DETAILS ------------------------------------------------------
        if (action.isShowDetails()) {
            // :972-977 - IF INPUT-ERROR OR NO-CHANGES-DETECTED CONTINUE
            //            ELSE SET CCUP-CHANGES-OK-NOT-CONFIRMED TO TRUE
            if (!task.inputError() && !task.noChangesDetected()) {
                task.setChangeAction(ChangeAction.changesOkNotConfirmed());
            }
            return;
        }

        // ---- :982-983 WHEN CCUP-CHANGES-NOT-OK ---------------------------------------------------
        if (action.isChangesNotOk()) {
            // :983 - CONTINUE. The state stays 'E' and 3250 paints PROMPT-FOR-CHANGES. Deliberately
            // empty: an absent arm would fall through to WHEN OTHER and abend.
            return;
        }

        // ---- :988-1001 WHEN CCUP-CHANGES-OK-NOT-CONFIRMED AND CCARD-AID-PFK05 --------------------
        if (action.isChangesOkNotConfirmed() && task.ccWorkArea.isCcardAidPfk05()) {
            writeProcessing9200(task);
            return;
        }

        // ---- :1006-1007 WHEN CCUP-CHANGES-OK-NOT-CONFIRMED (bare, the SECOND test) ---------------
        if (action.isChangesOkNotConfirmed()) {
            // :1007 - CONTINUE. Reachable only because the qualified arm above was tested first.
            return;
        }

        // ---- :1011-1018 WHEN CCUP-CHANGES-OKAYED-AND-DONE ---------------------------------------
        if (action.isChangesOkayedAndDone()) {
            // :1012 - SET CCUP-SHOW-DETAILS TO TRUE
            task.setChangeAction(ChangeAction.showDetails());
            // :1013-1018 - IF CDEMO-FROM-TRANID EQUAL LOW-VALUES OR EQUAL SPACES
            if (isLowValuesOrSpaces(task.carddemoCommarea.fromTranid(),
                    NavigationContext.FROM_TRANID_LENGTH)) {
                // :1015-1016 - MOVE ZEROES TO CDEMO-ACCT-ID, CDEMO-CARD-NUM
                task.carddemoCommarea = task.carddemoCommarea.withAcctId(0L).withCardNum(0L);
                // :1017 - MOVE LOW-VALUES TO CDEMO-ACCT-STATUS. A DIFFERENT fill byte from the two
                // statements above it, and one character wide.
                task.carddemoCommarea = task.carddemoCommarea.withAcctStatus(
                        CardScreenState.lowValues(NavigationContext.ACCT_STATUS_LENGTH));
            }
            return;
        }

        // ---- :1019-1026 WHEN OTHER --------------------------------------------------------------
        unexpectedDataScenario(task);
    }

    /**
     * {@code PERFORM 9200-WRITE-PROCESSING} and the inner {@code EVALUATE TRUE} that reads its outcome -
     * {@code app/cbl/COCRDUPC.cbl:990-1001}.
     *
     * <pre>
     * PERFORM 9200-WRITE-PROCESSING THRU 9200-WRITE-PROCESSING-EXIT
     * EVALUATE TRUE
     *    WHEN COULD-NOT-LOCK-FOR-UPDATE      SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE
     *    WHEN LOCKED-BUT-UPDATE-FAILED       SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE
     *    WHEN DATA-WAS-CHANGED-BEFORE-UPDATE SET CCUP-SHOW-DETAILS              TO TRUE
     *    WHEN OTHER                          SET CCUP-CHANGES-OKAYED-AND-DONE   TO TRUE
     * END-EVALUATE
     * </pre>
     *
     * <p><strong>The four arms test the CONTENT of {@code WS-RETURN-MSG}, not a return code.</strong>
     * {@code COULD-NOT-LOCK-FOR-UPDATE}, {@code LOCKED-BUT-UPDATE-FAILED} and
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} are three of the nineteen {@code 88}-levels declared over
     * that one field, so this {@code EVALUATE} is asking "which message did {@code 9200} leave?". That is
     * reproduced literally, by taking the message the service returns and testing it against the three
     * padded literals in the source's order - and it is <em>not</em> reproduced by switching on
     * {@link CardUpdateService.WriteOutcome}, because the two are not equivalent.
     *
     * <p>They diverge in one reachable case, and it is a latent defect in the COBOL rather than in this
     * translation. {@code 9200}'s lock-failure arm sets its message <em>only</em>
     * {@code IF WS-RETURN-MSG-OFF} ({@code :1445-1447}). So if an earlier paragraph had already put a
     * different message in the field, a genuine lock failure leaves that other message in place, none of
     * the first three arms matches, and {@code WHEN OTHER} declares the update
     * <strong>done</strong> - {@link #CONFIRM_UPDATE_SUCCESS} on the screen with nothing written. The
     * service models the guard faithfully by returning the pre-existing message, so testing the message
     * here reproduces the defect exactly. Switching on the outcome enum would silently repair it, which
     * practice <strong>B5</strong> forbids: a defect is behaviour.
     *
     * <p>{@code 9200} also does {@code SET INPUT-ERROR TO TRUE} unconditionally on that same arm
     * ({@code :1444}), which {@link CardUpdateService.WriteResult#inputError()} reports and which is
     * applied here before the message is tested.
     *
     * @param task this task's storage
     */
    void writeProcessing9200(Conversation task) {
        // :990-991 - PERFORM 9200-WRITE-PROCESSING THRU 9200-WRITE-PROCESSING-EXIT
        CardUpdateService.WriteResult result = cardUpdateService.writeProcessing(
                task.ccWorkArea, task.oldDetails(), task.newDetails(), task.wsReturnMsg, codec);

        // :1444 - SET INPUT-ERROR TO TRUE, on the lock-failure arm only
        if (result.inputError()) {
            task.wsInputFlag = INPUT_ERROR;
        }

        // WS-RETURN-MSG as 9200 left it, at its declared X(75) so the condition names can be compared.
        task.wsReturnMsg = codec.movePicX(result.returnMessage(), WS_RETURN_MSG_LENGTH);

        // 9300 refreshes CCUP-OLD-DETAILS from the stored record when it detects a change (:1512-1517),
        // and returns the snapshot unchanged otherwise. Either way the service's value is authoritative.
        task.setOldDetails(result.oldDetails());

        // CARD-UPDATE-RECORD as :1461-1475 staged it, when the paragraph got that far. Held so the
        // returned commarea carries the 150 bytes the source's WS-THIS-PROGCOMMAREA carries.
        result.cardUpdateRecord().ifPresent(staged ->
                task.thisProgCommarea = task.thisProgCommarea.withCardUpdateRecord(staged));

        // CARD-CVV-CD-X, written at :1464 as the alphanumeric half of the CVV REDEFINES pair.
        result.cardUpdateCvvCdImage().ifPresent(image ->
                task.cardCvvCdX = codec.movePicX(image, CardRecord.CARD_CVV_CD_LENGTH));

        // The failing operation name, for the diagnostic the COBOL could only have logged to a terminal.
        result.failedOperation().ifPresent(operation -> {
            task.errorOpname = codec.movePicX(operation, ERROR_OPNAME_LENGTH);
            task.wsRespCd = result.resp();
            task.wsReasCd = result.resp2();
        });

        // :992-1001 - the inner EVALUATE, on the CONTENT of WS-RETURN-MSG, in source order
        if (task.couldNotLockForUpdate()) {
            // :994 - SET CCUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE ('L')
            task.setChangeAction(ChangeAction.changesOkayedLockError());
            return;
        }
        if (task.lockedButUpdateFailed()) {
            // :996 - SET CCUP-CHANGES-OKAYED-BUT-FAILED TO TRUE ('F')
            task.setChangeAction(ChangeAction.changesOkayedButFailed());
            return;
        }
        if (task.dataWasChangedBeforeUpdate()) {
            // :998 - SET CCUP-SHOW-DETAILS TO TRUE ('S'), so the refreshed values are painted and the
            // operator can review what actually won
            task.setChangeAction(ChangeAction.showDetails());
            return;
        }
        // :1000 - WHEN OTHER: SET CCUP-CHANGES-OKAYED-AND-DONE TO TRUE ('C')
        task.setChangeAction(ChangeAction.changesOkayedAndDone());
    }

    /**
     * {@code WHEN OTHER} of {@code 2000-DECIDE-ACTION} - {@code app/cbl/COCRDUPC.cbl:1019-1026}.
     *
     * <pre>
     * MOVE LIT-THISPGM TO ABEND-CULPRIT
     * MOVE '0001'      TO ABEND-CODE
     * MOVE SPACES      TO ABEND-REASON
     * MOVE 'UNEXPECTED DATA SCENARIO' TO ABEND-MSG
     * PERFORM ABEND-ROUTINE THRU ABEND-ROUTINE-EXIT
     * </pre>
     *
     * <p><strong>The text goes to {@code ABEND-MSG}</strong>, which is what distinguishes this arm from
     * the sibling {@code COCRDSLC:373-380}, where the identical text goes to {@code WS-RETURN-MSG} and so
     * reaches the map's error line instead. Here it reaches the terminal only through
     * {@code EXEC CICS SEND FROM(ABEND-DATA)} and the screen is never painted at all. The two programs
     * genuinely differ and this one is reproduced.
     *
     * <p>Reaching this arm means {@code CCUP-CHANGE-ACTION} held a byte that satisfies none of its
     * condition names - not {@code x'00'}, not a space, and not one of {@code 'S' 'E' 'N' 'C' 'L' 'F'} -
     * which the program itself cannot produce. It is reachable in this migration through a caller-supplied
     * commarea, so it is not dead code here even though it is dead in CICS, and it is tested.
     *
     * @param task this task's storage
     * @throws AbendException always
     */
    void unexpectedDataScenario(Conversation task) {
        // :1020-1024 - fill ABEND-DATA. ABEND-REASON is explicitly spaced, and ABEND-MSG - not
        // WS-RETURN-MSG - takes the text.
        task.abendData = task.abendData
                .withAbendCulprit(codec.movePicX(LIT_THISPGM, SystemMessages.ABEND_CULPRIT_LENGTH))
                .withAbendCode(UNEXPECTED_DATA_ABEND_CODE)
                .withAbendReason(CardScreenState.spaces(SystemMessages.ABEND_REASON_LENGTH))
                .withAbendMsg(UNEXPECTED_DATA_SCENARIO);

        // :1025-1026 - PERFORM ABEND-ROUTINE THRU ABEND-ROUTINE-EXIT. The routine ends in
        // EXEC CICS ABEND, so it does not come back; the exception is thrown rather than returned so that
        // no statement after this one can execute, exactly as the abend guarantees.
        throw abendRoutine(task, null, null);
    }

    // =================================================================================================
    // 9000-READ-DATA and 9100-GETCARD-BYACCTCARD - app/cbl/COCRDUPC.cbl:1343-1417. The display read.
    // =================================================================================================

    /**
     * {@code 9000-READ-DATA} - {@code app/cbl/COCRDUPC.cbl:1343-1370}.
     *
     * <pre>
     * INITIALIZE CCUP-OLD-DETAILS
     * MOVE CC-ACCT-ID  TO CCUP-OLD-ACCTID
     * MOVE CC-CARD-NUM TO CCUP-OLD-CARDID
     * PERFORM 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT
     * IF FOUND-CARDS-FOR-ACCOUNT ... END-IF
     * </pre>
     *
     * <p>Takes the snapshot the whole screen is compared against. Everything downstream - which values
     * {@code 3200} paints, whether {@code 1200} finds a change, what {@code 9300} compares the stored
     * record to - reads {@code CCUP-OLD-DETAILS}, so this paragraph is where the operator's idea of
     * "before" is fixed.
     *
     * <p>{@code INITIALIZE} at {@code :1345} sets the group to its category defaults: spaces for every
     * one of the nine {@code PIC X} items, <em>not</em> {@code LOW-VALUES}. The two keys are then moved
     * in unconditionally, before the read - so a failed read still leaves the keys the operator supplied
     * in the snapshot, which is what lets {@code 3200} echo them back on an error screen.
     *
     * <p>The eight moves under {@code IF FOUND-CARDS-FOR-ACCOUNT} run only on a successful read
     * ({@code :1352-1369}), so on {@code NOTFND} the six non-key items stay spaces.
     *
     * <p>{@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER} at {@code :1356-1358}
     * folds the name <strong>in the record area, in place</strong>, before the move at {@code :1360}. The
     * record held in {@link Conversation#cardRecord} is replaced with the folded one so that the in-place
     * effect is reproduced rather than only its result being copied out; nothing in this program reads the
     * field again, but the storage is what the source leaves.
     *
     * <p>The expiry split at {@code :1361-1366} uses reference modification
     * {@code (1:4)}, {@code (6:2)}, {@code (9:2)} - <strong>1-based</strong>, over the {@code X(10)}
     * {@code YYYY-MM-DD}. Characters 5 and 8 are the two hyphens and belong to no component. Delegated to
     * {@link CardRecord#cardExpiraionDateYear()} and its siblings, which own the 0-based
     * {@code substring(0,4)} / {@code (5,7)} / {@code (8,10)} conversion once for every consumer, so the
     * off-by-one has exactly one place to be got right.
     *
     * @param task this task's storage
     */
    void readData9000(Conversation task) {
        // :1345 - INITIALIZE CCUP-OLD-DETAILS. Category defaults: SPACES for PIC X, not LOW-VALUES.
        CardDetails snapshot = CardDetails.initialised(DetailGroup.OLD);

        // :1346-1347 - the two keys, moved BEFORE the read so an error screen can still echo them.
        // CC-ACCT-ID is X(11) into CCUP-OLD-ACCTID X(11), CC-CARD-NUM is X(16) into CCUP-OLD-CARDID
        // X(16): equal widths, so no truncation, but routed through the codec regardless because a MOVE
        // is a MOVE.
        snapshot = snapshot
                .withAcctid(codec.movePicX(task.ccWorkArea.getCcAcctId(), CardDetails.ACCTID_LENGTH))
                .withCardid(codec.movePicX(task.ccWorkArea.getCcCardNum(), CardDetails.CARDID_LENGTH));
        task.setOldDetails(snapshot);

        // :1349-1350 - PERFORM 9100-GETCARD-BYACCTCARD THRU 9100-GETCARD-BYACCTCARD-EXIT
        getCardByAcctCard9100(task);

        // :1352 - IF FOUND-CARDS-FOR-ACCOUNT
        if (!task.foundCardsForAccount()) {
            return;
        }
        CardRecord record = task.cardRecord.orElseThrow(() -> new IllegalStateException(
                "app/cbl/COCRDUPC.cbl:1352 reads CARD-RECORD under IF FOUND-CARDS-FOR-ACCOUNT, which "
                        + "9100 sets only on DFHRESP(NORMAL) - the arm that populated the INTO area"));

        // :1356-1358 - INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER, in place in the
        // record area. Character-by-character over the declared 26 pairs, never String.toUpperCase().
        String foldedName = inspectConverting(record.cardEmbossedName(), LIT_LOWER, LIT_UPPER);
        record = record.withCardEmbossedName(foldedName);
        task.cardRecord = Optional.of(record);

        // :1354, :1360-1367 - the eight moves out of the record and into the snapshot.
        task.setOldDetails(task.oldDetails()
                // :1354 - MOVE CARD-CVV-CD TO CCUP-OLD-CVV-CD. The source is PIC 9(03), the receiver
                // PIC X(3), so this is the numeric-to-alphanumeric move that zero-fills to three digits.
                .withCvvCd(codec.movePicX(record.cardCvvCdImage(codec), CardDetails.CVV_CD_LENGTH))
                // :1360 - MOVE CARD-EMBOSSED-NAME TO CCUP-OLD-CRDNAME; X(50) to X(50).
                .withCrdname(codec.movePicX(foldedName, CardDetails.CRDNAME_LENGTH))
                // :1361-1362 - MOVE CARD-EXPIRAION-DATE(1:4) TO CCUP-OLD-EXPYEAR
                .withExpyear(codec.movePicX(record.cardExpiraionDateYear(), CardDetails.EXPYEAR_LENGTH))
                // :1363-1364 - MOVE CARD-EXPIRAION-DATE(6:2) TO CCUP-OLD-EXPMON, skipping the hyphen
                // at character 5
                .withExpmon(codec.movePicX(record.cardExpiraionDateMonth(), CardDetails.EXPMON_LENGTH))
                // :1365-1366 - MOVE CARD-EXPIRAION-DATE(9:2) TO CCUP-OLD-EXPDAY, skipping the hyphen
                // at character 8. The day is carried even though COCRDUP's EXPDAY field is display-only
                // and darkened at :1285.
                .withExpday(codec.movePicX(record.cardExpiraionDateDay(), CardDetails.EXPDAY_LENGTH))
                // :1367 - MOVE CARD-ACTIVE-STATUS TO CCUP-OLD-CRDSTCD; X(1) to X(1).
                .withCrdstcd(codec.movePicX(record.cardActiveStatus(), CardDetails.CRDSTCD_LENGTH)));
    }

    /**
     * {@code 9100-GETCARD-BYACCTCARD} - {@code app/cbl/COCRDUPC.cbl:1376-1417}.
     *
     * <pre>
     * *    MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID          &lt;-- commented out at :1379
     *      MOVE CC-CARD-NUM  TO WS-CARD-RID-CARDNUM
     *      EXEC CICS READ FILE(LIT-CARDFILENAME) RIDFLD(WS-CARD-RID-CARDNUM)
     *           KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM) INTO(CARD-RECORD)
     *           LENGTH(LENGTH OF CARD-RECORD) RESP(WS-RESP-CD) RESP2(WS-REAS-CD)
     *      END-EXEC
     * </pre>
     *
     * <p>The read is on the <strong>base cluster {@code CARDDAT} by card number</strong>, and it has
     * <strong>no {@code UPDATE} option</strong> - it is the display read, so it takes no lock and the
     * record it returns may be stale by the time {@code PF5} is pressed. That is precisely why
     * {@code 9300-CHECK-CHANGE-IN-REC} exists, and why the read-for-update lives in
     * {@link CardUpdateService} rather than here. This controller never calls
     * {@link CardRepository#readForUpdateByCardNumber} or a rewrite (gate G51).
     *
     * <p>The commented-out {@code MOVE} at {@code :1379} would have populated
     * {@code WS-CARD-RID-ACCT-ID}, the second half of {@code WS-CARD-RID}. It stays absent (practice
     * B5): the account number is <em>not</em> part of the key used here, so despite the paragraph's name
     * the read is by card number alone and the account number the operator typed is only ever validated
     * against the record after it is fetched.
     *
     * <p>{@code EVALUATE WS-RESP-CD} at {@code :1392-1412}, in source order (gate G30):
     * <ul>
     *   <li>{@code DFHRESP(NORMAL)} - {@code SET FOUND-CARDS-FOR-ACCOUNT TO TRUE} and nothing else.</li>
     *   <li>{@code DFHRESP(NOTFND)} - {@code INPUT-ERROR} and <em>both</em> key flags to
     *       {@code NOT-OK} unconditionally, then {@link #DID_NOT_FIND_ACCTCARD_COMBO}
     *       <strong>only {@code IF WS-RETURN-MSG-OFF}</strong> ({@code :1399-1401}). The flags are set
     *       whatever happened earlier; only the message defers to a message already placed. Collapsing
     *       the guard would overwrite an earlier, more specific message.</li>
     *   <li>{@code WHEN OTHER} - {@code INPUT-ERROR} unconditionally, then
     *       <strong>{@code FLG-ACCTFILTER-NOT-OK} under the same guard</strong>
     *       ({@code :1404-1406}). Here the guard wraps a <em>flag</em>, not a message, which is unlike
     *       every other {@code WS-RETURN-MSG-OFF} test in the program - so on a genuine I/O failure that
     *       follows an earlier message, the account field is left unhighlighted and the cursor lands
     *       elsewhere. Then the four {@code ERROR-*} items are filled and
     *       {@code WS-FILE-ERROR-MESSAGE} overwrites {@code WS-RETURN-MSG}
     *       <strong>unguarded</strong> ({@code :1407-1411}) - the message the guard just protected is
     *       replaced two statements later. Reproduced exactly as written.</li>
     * </ul>
     *
     * @param task this task's storage
     */
    void getCardByAcctCard9100(Conversation task) {
        // :1379 - MOVE CC-ACCT-ID-N TO WS-CARD-RID-ACCT-ID is COMMENTED OUT in the source and stays
        // absent (B5). WS-CARD-RID-ACCT-ID therefore holds whatever INITIALIZE left it.
        // :1380 - MOVE CC-CARD-NUM TO WS-CARD-RID-CARDNUM
        task.wsCardRidCardnum = codec.movePicX(task.ccWorkArea.getCcCardNum(), WS_CARD_RID_CARDNUM_LENGTH);

        // :1382-1390 - EXEC CICS READ, no UPDATE. KEYLENGTH is LENGTH OF WS-CARD-RID-CARDNUM, i.e. the
        // full 16, so this is a fully-qualified keyed read and never a generic browse.
        CardRepository.CardReadResult result = cardRepository.readByCardNumber(task.wsCardRidCardnum);
        task.wsRespCd = result.resp();
        task.wsReasCd = result.resp2();

        // :1392-1412 - EVALUATE WS-RESP-CD, in source order
        if (result.isNormal()) {
            // :1393-1394 - WHEN DFHRESP(NORMAL). The INTO area now holds the record.
            //
            // SET FOUND-CARDS-FOR-ACCOUNT TO TRUE writes into WS-INFO-MSG, not into a return code:
            // FOUND-CARDS-FOR-ACCOUNT is an 88-level over WS-INFO-MSG PIC X(40) at :157-161. So the
            // successful-read flag and the informational message the operator reads are the same forty
            // bytes, which is why 3250-SETUP-INFOMSG can re-assert it at :1146 without changing meaning.
            task.cardRecord = Optional.of(result.requireRecord());
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
            return;
        }
        if (result.isNotFound()) {
            // :1395-1401 - WHEN DFHRESP(NOTFND)
            task.wsInputFlag = INPUT_ERROR;
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
            task.wsEditCardFlag = FLG_FILTER_NOT_OK;
            // :1399-1401 - the message, and ONLY the message, defers to one already placed
            if (task.returnMessageOff()) {
                task.wsReturnMsg = DID_NOT_FIND_ACCTCARD_COMBO;
            }
            return;
        }

        // :1402-1411 - WHEN OTHER
        task.wsInputFlag = INPUT_ERROR;
        // :1404-1406 - the guard wraps a FLAG here, not a message. Faithful, and odd.
        if (task.returnMessageOff()) {
            task.wsEditAcctFlag = FLG_FILTER_NOT_OK;
        }
        // :1407-1411 - and then the composed message overwrites WS-RETURN-MSG unguarded, undoing what
        // the guard above was protecting.
        recordFileError(task, READ_OPERATION_NAME, LIT_CARDFILENAME);
    }

    /**
     * {@code MOVE ... TO ERROR-OPNAME / ERROR-FILE / ERROR-RESP / ERROR-RESP2} then
     * {@code MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG} - {@code app/cbl/COCRDUPC.cbl:1407-1411}.
     *
     * <p>Fills the four variable spans of {@link #fileErrorMessage} and copies the composed 80
     * characters into the {@code X(75)} return message, which discards the five-space trailer. The
     * assignment is <strong>unguarded</strong> in the source, so it replaces any message already present.
     *
     * @param task      this task's storage; {@link Conversation#wsRespCd} and
     *                  {@link Conversation#wsReasCd} must already hold the response codes
     * @param operation the value for {@code ERROR-OPNAME}, padded to {@value #ERROR_OPNAME_LENGTH}
     * @param fileName  the value for {@code ERROR-FILE}, padded to {@value #ERROR_FILE_LENGTH}
     */
    void recordFileError(Conversation task, String operation, String fileName) {
        // :1407 - MOVE 'READ' TO ERROR-OPNAME
        task.errorOpname = codec.movePicX(operation, ERROR_OPNAME_LENGTH);
        // :1408 - MOVE LIT-CARDFILENAME TO ERROR-FILE. The literal is X(8) and the receiver X(9), so the
        // move space-pads on the right by one - which is why the composed message has two spaces before
        // "returned".
        task.errorFile = codec.movePicX(fileName, ERROR_FILE_LENGTH);
        // :1409-1410 - MOVE WS-RESP-CD TO ERROR-RESP and MOVE WS-REAS-CD TO ERROR-RESP2
        task.errorResp = responseCodeImage(task.wsRespCd);
        task.errorResp2 = responseCodeImage(task.wsReasCd);
        // :1411 - MOVE WS-FILE-ERROR-MESSAGE TO WS-RETURN-MSG; 80 into X(75), dropping the trailer.
        task.wsReturnMsg = codec.movePicX(fileErrorMessage(task), WS_RETURN_MSG_LENGTH);
    }

    /**
     * {@code WS-FILE-ERROR-MESSAGE} - {@code app/cbl/COCRDUPC.cbl:133-152} - composed from its eight
     * spans, in declaration order, to <strong>exactly {@value #FILE_ERROR_MESSAGE_LENGTH}</strong>
     * characters.
     *
     * <p>A group item in COBOL is the concatenation of its elementary items, so the composition is the
     * concatenation and nothing else - no separator, no formatting, no trimming. The total is asserted
     * rather than assumed, because a group whose width has drifted is a defect that no other check in
     * this class would notice and that the parity differ would report as a message mismatch with no
     * indication of the cause.
     *
     * @param task this task's storage, supplying the four variable spans
     * @return the composed message, exactly {@value #FILE_ERROR_MESSAGE_LENGTH} characters
     * @throws IllegalStateException if the composition is not exactly that wide
     */
    String fileErrorMessage(Conversation task) {
        String composed = FILE_ERROR_PREFIX
                + codec.movePicX(task.errorOpname, ERROR_OPNAME_LENGTH)
                + FILE_ERROR_ON
                + codec.movePicX(task.errorFile, ERROR_FILE_LENGTH)
                + FILE_ERROR_RETURNED_RESP
                + codec.movePicX(task.errorResp, ERROR_RESP_LENGTH)
                + FILE_ERROR_RESP2
                + codec.movePicX(task.errorResp2, ERROR_RESP_LENGTH)
                + FILE_ERROR_TRAILER;
        if (composed.length() != FILE_ERROR_MESSAGE_LENGTH) {
            throw new IllegalStateException("WS-FILE-ERROR-MESSAGE at app/cbl/COCRDUPC.cbl:133-152 is "
                    + FILE_ERROR_MESSAGE_LENGTH + " characters - 12+8+4+9+15+10+7+10+5 - but this "
                    + "composition is " + composed.length());
        }
        return composed;
    }

    /**
     * {@code MOVE WS-RESP-CD TO ERROR-RESP} - {@code app/cbl/COCRDUPC.cbl:1409}.
     *
     * <p>A binary-to-alphanumeric move, not a formatting call. {@code WS-RESP-CD} is
     * {@code PIC S9(09) COMP} and {@code ERROR-RESP} is {@code PIC X(10)}, so the value is rendered as
     * <strong>{@value #RESP_CODE_DIGITS} zero-filled digits</strong> and then space-padded on the right
     * to ten - which is why the message shows {@code 000000013} rather than {@code 13}.
     *
     * <p>The sign is dropped, because an alphanumeric receiver has nowhere to put it: a
     * {@code MOVE} of a signed numeric to {@code PIC X} moves the absolute digits. A CICS {@code RESP} is
     * never negative in practice, but {@link Math#abs} is applied so the rendering is defined for every
     * input rather than only the expected ones.
     *
     * @param responseCode the {@code RESP} or {@code RESP2} value
     * @return the ten-character image
     */
    String responseCodeImage(int responseCode) {
        String digits = codec.movePic9(Math.abs((long) responseCode), RESP_CODE_DIGITS);
        return codec.movePicX(digits, ERROR_RESP_LENGTH);
    }

    // =================================================================================================
    // 3000-SEND-MAP and its five children - app/cbl/COCRDUPC.cbl:1035-1340. Screen construction.
    // =================================================================================================

    /**
     * {@code 3000-SEND-MAP} - {@code app/cbl/COCRDUPC.cbl:1035-1046}. Five {@code PERFORM ... THRU}s in
     * a fixed order, and the order is load-bearing at three points:
     *
     * <ol>
     *   <li>{@code 3100} does {@code MOVE LOW-VALUES TO CCRDUPAO} first, so anything written before it
     *       would be erased. Nothing is.</li>
     *   <li>{@code 3250} decides {@code WS-INFO-MSG} and only then copies it to {@code INFOMSGO}, and
     *       {@code 3300} afterwards tests {@code WS-NO-INFO-MESSAGE} and
     *       {@code PROMPT-FOR-CONFIRMATION} to choose two brightness attributes - so it reads what
     *       {@code 3250} decided, not what the caller left.</li>
     *   <li>{@code 3300} writes {@code '*'} into four {@code xxxO} items for blank fields, after
     *       {@code 3200} has already filled them - so the marker overwrites the value rather than the
     *       other way round.</li>
     * </ol>
     *
     * @param request  the inbound map, whose {@code xxxA} and {@code xxxL} metadata items {@code 3300}
     *                 writes into
     * @param response the outbound map being built
     * @param task     this task's storage
     */
    void sendMap3000(CardUpdateRequest request, CardUpdateResponse response, Conversation task) {
        // :1036-1037
        screenInit3100(response, task);
        // :1038-1039
        setupScreenVars3200(response, task);
        // :1040-1041
        setupInfomsg3250(response, task);
        // :1042-1043
        setupScreenAttrs3300(request, response, task);
        // :1044-1045
        sendScreen3400(response, task);
    }

    /**
     * {@code 3100-SCREEN-INIT} - {@code app/cbl/COCRDUPC.cbl:1052-1076}.
     *
     * <pre>
     * MOVE LOW-VALUES            TO CCRDUPAO
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
     * MOVE CCDA-TITLE01/02, LIT-THISTRANID, LIT-THISPGM  TO the four heading items
     * MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA          &lt;-- a SECOND time, :1062
     * ... compose WS-CURDATE-MM-DD-YY and WS-CURTIME-HH-MM-SS, and move them out
     * </pre>
     *
     * <p><strong>{@code FUNCTION CURRENT-DATE} is evaluated twice</strong> - once at {@code :1055} and
     * again at {@code :1062} - into the same area. The first reading is therefore dead: nothing between
     * the two statements reads {@code WS-CURDATE-DATA}, and the second overwrites all 21 bytes. It is
     * reproduced rather than optimised away (practice B5), because the two readings can differ - the
     * clock advances between them, and on a second-boundary the date and time the operator sees come
     * from the <em>second</em> reading. Both calls are made, and the second wins.
     *
     * <p>The clock is {@link Clock}, constructor-injected, so a test fixes the reading rather than
     * racing it. {@code LocalDateTime.now()} is never called inline.
     *
     * <p>{@code MOVE LOW-VALUES TO CCRDUPAO} covers the whole group - all 17 payload items and every
     * attribute byte - which {@link CardUpdateResponse#screenInit} performs before it moves the four
     * constants and the two clock values, in the source's order.
     *
     * @param response the outbound map being built
     * @param task     this task's storage; receives the captured date and time
     */
    void screenInit3100(CardUpdateResponse response, Conversation task) {
        // :1055 - MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA. The first of two readings, whose
        // value nothing reads. Taken anyway: the call itself is observable behaviour.
        DateHeader firstReading = DateHeader.from(codec, clock).withCurdateMmDdYyFromTimestamp();

        // :1062 - MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, a second time. This reading is the one
        // that reaches CURDATEO and CURTIMEO, so it is the one held.
        DateHeader secondReading = DateHeader.from(codec, clock).withCurdateMmDdYyFromTimestamp();
        task.dateHeader = secondReading;

        if (LOG.isTraceEnabled() && !firstReading.wsTimestamp().equals(secondReading.wsTimestamp())) {
            // The clock crossed a boundary between :1055 and :1062, so the source's duplicate MOVE is
            // observable. Recorded at trace because it is expected, not a fault.
            LOG.trace("app/cbl/COCRDUPC.cbl:1055 and :1062 both evaluate FUNCTION CURRENT-DATE and the "
                    + "readings differ; the second is used, as the source does");
        }

        // :1053, :1057-1060, :1068, :1075 - the group reset then the six moves, in source order.
        response.screenInit(secondReading);
    }

    /**
     * {@code 3200-SETUP-SCREEN-VARS} - {@code app/cbl/COCRDUPC.cbl:1082-1134}.
     *
     * <p>The whole paragraph sits under {@code IF CDEMO-PGM-ENTER CONTINUE ELSE ...}
     * ({@code :1084-1086}), so on <strong>first entry the screen is left exactly as {@code 3100} made
     * it - {@code LOW-VALUES} in every field</strong>. That is what produces the empty prompt screen; if
     * this guard were dropped, first entry would echo whatever the caller happened to send.
     *
     * <p>The two key fields ({@code :1087-1097}) test the <em>numeric</em> redefine and write
     * {@code LOW-VALUES} when it is zero, so an unset key paints as nothing rather than as
     * {@code 00000000000}. {@code CC-ACCT-ID-N} is the {@code PIC 9(11)} view of the same eleven bytes
     * as {@code CC-ACCT-ID PIC X(11)} - a byte reinterpretation, not a parse (gate G34), which
     * {@link CardScreenState#isCcAcctIdNZeros()} owns.
     *
     * <p>Then {@code EVALUATE TRUE} over the change action ({@code :1099-1130}), in source order:
     * <ul>
     *   <li>{@code CCUP-DETAILS-NOT-FETCHED} - {@code LOW-VALUES} into the five detail items. The
     *       source names {@code CRDNAMEO} <strong>twice</strong> in the one {@code MOVE}
     *       ({@code :1101-1102}), which is legal and idempotent; the duplicate is noted and has no
     *       effect.</li>
     *   <li>{@code CCUP-SHOW-DETAILS} - the five {@code CCUP-OLD-*} values, the stored record as
     *       fetched.</li>
     *   <li>{@code CCUP-CHANGES-MADE} - a <strong>grouping</strong> condition matching all five of
     *       {@code 'E' 'N' 'C' 'L' 'F'}, so it covers "rejected", "awaiting confirmation", "done" and
     *       both failure states with one arm. Four {@code CCUP-NEW-*} values are echoed so the operator
     *       sees what they typed - <strong>but {@code EXPDAY} comes from
     *       {@code CCUP-OLD-EXPDAY}</strong> ({@code :1123}), because {@code MOVE CCUP-NEW-EXPDAY} at
     *       {@code :1122} is commented out with the note that the day is not user-changeable
     *       ({@code :1118-1121}). The day therefore always shows the stored value even on a screen full
     *       of typed changes. Preserved (B5).</li>
     *   <li>{@code WHEN OTHER} - the five {@code CCUP-OLD-*} values. Reached when the action byte
     *       satisfies none of the three arms above, which {@code 2000} would have abended on; it is
     *       reachable here only on a repaint that follows a state {@code 2000} did not vet.</li>
     * </ul>
     *
     * @param response the outbound map being built
     * @param task     this task's storage
     */
    void setupScreenVars3200(CardUpdateResponse response, Conversation task) {
        // :1084-1086 - IF CDEMO-PGM-ENTER CONTINUE. First entry paints nothing over LOW-VALUES.
        if (task.carddemoCommarea.isEnter()) {
            return;
        }

        // :1087-1091 - the account key, LOW-VALUES when the numeric view is zero
        if (task.ccWorkArea.isCcAcctIdNZeros()) {
            response.setOutputItem(CardUpdateResponse.ACCTSID,
                    CardScreenState.lowValues(CardUpdateResponse.ACCTSIDO_LENGTH));
        } else {
            response.setOutputItem(CardUpdateResponse.ACCTSID, task.ccWorkArea.getCcAcctId());
        }

        // :1093-1097 - the card key, the same shape
        if (task.ccWorkArea.isCcCardNumNZeros()) {
            response.setOutputItem(CardUpdateResponse.CARDSID,
                    CardScreenState.lowValues(CardUpdateResponse.CARDSIDO_LENGTH));
        } else {
            response.setOutputItem(CardUpdateResponse.CARDSID, task.ccWorkArea.getCcCardNum());
        }

        // :1099-1130 - EVALUATE TRUE over CCUP-CHANGE-ACTION, in source order
        ChangeAction action = task.changeAction();
        if (action.isDetailsNotFetched()) {
            // :1100-1106 - MOVE LOW-VALUES TO the five detail items. CRDNAMEO is named twice at
            // :1101-1102; the repetition is idempotent and is not reproduced as a second assignment
            // because there is nothing for one to do.
            response.setOutputItem(CardUpdateResponse.CRDNAME,
                    CardScreenState.lowValues(CardUpdateResponse.CRDNAMEO_LENGTH));
            response.setOutputItem(CardUpdateResponse.CRDSTCD,
                    CardScreenState.lowValues(CardUpdateResponse.CRDSTCDO_LENGTH));
            response.setOutputItem(CardUpdateResponse.EXPDAY,
                    CardScreenState.lowValues(CardUpdateResponse.EXPDAYO_LENGTH));
            response.setOutputItem(CardUpdateResponse.EXPMON,
                    CardScreenState.lowValues(CardUpdateResponse.EXPMONO_LENGTH));
            response.setOutputItem(CardUpdateResponse.EXPYEAR,
                    CardScreenState.lowValues(CardUpdateResponse.EXPYEARO_LENGTH));
            return;
        }
        if (action.isShowDetails()) {
            // :1107-1112 - the stored values
            paintDetailItems3200(response, task.oldDetails(), task.oldDetails().expday());
            return;
        }
        if (action.isChangesMade()) {
            // :1113-1123 - the typed values, EXCEPT the day, which comes from the OLD group because
            // :1122 is commented out (:1118-1121 explains why) and :1123 replaces it.
            paintDetailItems3200(response, task.newDetails(), task.oldDetails().expday());
            return;
        }
        // :1124-1129 - WHEN OTHER: the stored values
        paintDetailItems3200(response, task.oldDetails(), task.oldDetails().expday());
    }

    /**
     * The five {@code MOVE}s that three of {@code 3200}'s four arms share
     * ({@code app/cbl/COCRDUPC.cbl:1107-1112}, {@code :1113-1123} and {@code :1124-1129}), factored so
     * the one asymmetry between them is visible as an argument rather than buried in duplicated code.
     *
     * <p>{@code expday} is passed separately precisely because the {@code CCUP-CHANGES-MADE} arm takes
     * it from the <em>other</em> group: four items from {@code CCUP-NEW-*} and the day from
     * {@code CCUP-OLD-*}.
     *
     * @param response the outbound map being built
     * @param details  the group supplying name, status, month and year
     * @param expday   the value for {@code EXPDAYO}, which is not always {@code details.expday()}
     */
    private void paintDetailItems3200(CardUpdateResponse response, CardDetails details, String expday) {
        response.setOutputItem(CardUpdateResponse.CRDNAME, details.crdname());
        response.setOutputItem(CardUpdateResponse.CRDSTCD, details.crdstcd());
        response.setOutputItem(CardUpdateResponse.EXPDAY, expday);
        response.setOutputItem(CardUpdateResponse.EXPMON, details.expmon());
        response.setOutputItem(CardUpdateResponse.EXPYEAR, details.expyear());
    }

    /**
     * {@code 3250-SETUP-INFOMSG} - {@code app/cbl/COCRDUPC.cbl:1138-1164}.
     *
     * <p>A nine-arm {@code EVALUATE TRUE} that decides {@code WS-INFO-MSG}, then two unconditional
     * moves that copy it and {@code WS-RETURN-MSG} onto the map. Every {@code SET} here is an
     * {@code 88}-level over {@code WS-INFO-MSG PIC X(40)}, so each arm assigns forty bytes of literal
     * text - the message is the state.
     *
     * <p>In source order ({@code :1141-1158}); the order matters at the first arm and at the last:
     * <ul>
     *   <li>{@code CDEMO-PGM-ENTER} is tested <strong>first</strong>, so on first entry the prompt wins
     *       regardless of what the change action says.</li>
     *   <li>{@code CCUP-DETAILS-NOT-FETCHED} - the same prompt.</li>
     *   <li>{@code CCUP-SHOW-DETAILS} - {@link #FOUND_CARDS_FOR_ACCOUNT}, re-asserting what
     *       {@code 9100:1394} already set. Harmless and deliberate: this arm also covers the repaint
     *       after {@code 9300} refused an update, where {@code 9100} did not run.</li>
     *   <li>{@code CCUP-CHANGES-NOT-OK} - {@link #PROMPT_FOR_CHANGES}.</li>
     *   <li>{@code CCUP-CHANGES-OK-NOT-CONFIRMED} - {@link #PROMPT_FOR_CONFIRMATION}, which {@code 3300}
     *       then tests to brighten the {@code FKEYSC} field.</li>
     *   <li>{@code CCUP-CHANGES-OKAYED-AND-DONE} - {@link #CONFIRM_UPDATE_SUCCESS}.</li>
     *   <li>{@code CCUP-CHANGES-OKAYED-LOCK-ERROR} and {@code CCUP-CHANGES-OKAYED-BUT-FAILED} - two
     *       separate arms setting the <strong>same</strong> {@link #INFORM_FAILURE} text
     *       ({@code :1153-1156}). They are not merged, because the grouping condition
     *       {@code CCUP-CHANGES-FAILED} that covers both exists and was not used here; writing them as
     *       the source does keeps the two states distinguishable in a trace.</li>
     *   <li>{@code WS-NO-INFO-MESSAGE} - the prompt again, as a floor. <strong>There is no
     *       {@code WHEN OTHER}</strong>, so a state that matches none of the nine leaves
     *       {@code WS-INFO-MSG} exactly as it arrived - which is a real outcome and not a defect,
     *       because the two moves that follow copy it out whatever it holds.</li>
     * </ul>
     *
     * @param response the outbound map being built
     * @param task     this task's storage
     */
    void setupInfomsg3250(CardUpdateResponse response, Conversation task) {
        ChangeAction action = task.changeAction();

        // :1140-1159 - EVALUATE TRUE, in source order, no WHEN OTHER
        if (task.carddemoCommarea.isEnter()) {
            // :1141-1142
            task.wsInfoMsg = PROMPT_FOR_SEARCH_KEYS;
        } else if (action.isDetailsNotFetched()) {
            // :1143-1144
            task.wsInfoMsg = PROMPT_FOR_SEARCH_KEYS;
        } else if (action.isShowDetails()) {
            // :1145-1146
            task.wsInfoMsg = FOUND_CARDS_FOR_ACCOUNT;
        } else if (action.isChangesNotOk()) {
            // :1147-1148
            task.wsInfoMsg = PROMPT_FOR_CHANGES;
        } else if (action.isChangesOkNotConfirmed()) {
            // :1149-1150
            task.wsInfoMsg = PROMPT_FOR_CONFIRMATION;
        } else if (action.isChangesOkayedAndDone()) {
            // :1151-1152
            task.wsInfoMsg = CONFIRM_UPDATE_SUCCESS;
        } else if (action.isChangesOkayedLockError()) {
            // :1153-1154
            task.wsInfoMsg = INFORM_FAILURE;
        } else if (action.isChangesOkayedButFailed()) {
            // :1155-1156 - a separate arm carrying the same text as the one above it
            task.wsInfoMsg = INFORM_FAILURE;
        } else if (task.noInfoMessage()) {
            // :1157-1158
            task.wsInfoMsg = PROMPT_FOR_SEARCH_KEYS;
        }
        // No WHEN OTHER: an unmatched state leaves WS-INFO-MSG untouched, and the moves below still run.

        // :1161 - MOVE WS-INFO-MSG TO INFOMSGO; X(40) into X(40)
        response.setOutputItem(CardUpdateResponse.INFOMSG, task.wsInfoMsg);
        // :1163 - MOVE WS-RETURN-MSG TO ERRMSGO; X(75) into X(80), so it space-pads by five on the right
        response.setOutputItem(CardUpdateResponse.ERRMSG, task.wsReturnMsg);
    }

    /**
     * {@code 3300-SETUP-SCREEN-ATTRS} - {@code app/cbl/COCRDUPC.cbl:1168-1318}, the longest paragraph in
     * the program. Four sections, in the source's order, each commented in the source itself:
     * protect-or-unprotect, position-cursor, set-colour, and the two message attributes.
     *
     * <p><strong>This program does not copy {@code CSSETATY}</strong> - the thirteen active
     * {@code COPY} statements are at {@code :268}-{@code :359} plus {@code :1528} and none is
     * {@code CSSETATY}, whose only consumer in the estate is {@code COACTUPC}. So the highlight rules
     * here are the program's own inline {@code IF}s, and they are <em>not</em> the copybook's rule:
     * <ul>
     *   <li>the {@code NOT-OK} arms are <strong>unguarded</strong> ({@code :1243}, {@code :1253}) or
     *       guarded by {@code CCUP-CHANGES-NOT-OK} rather than by {@code CDEMO-PGM-REENTER}
     *       ({@code :1263}, {@code :1274}, {@code :1287}, {@code :1298});</li>
     *   <li>{@code CSSETATY} guards <em>both</em> its arms with {@code CDEMO-PGM-REENTER}.</li>
     * </ul>
     * Routing the unguarded arms through {@link FieldAttributeSetter} would therefore add a guard the
     * source does not have and suppress the red on a first-pass rejection. The shared helper is used for
     * the four {@code BLANK} arms whose guard genuinely is {@code CDEMO-PGM-REENTER}
     * ({@code :1247-1251}, {@code :1257-1261}), and the rest are written out as the source writes them.
     *
     * @param request  the inbound map, receiving the {@code xxxA} attribute items and the {@code -1}
     *                 cursor length items
     * @param response the outbound map, receiving the {@code xxxC} colour items and the {@code '*'}
     *                 markers
     * @param task     this task's storage
     */
    void setupScreenAttrs3300(CardUpdateRequest request, CardUpdateResponse response,
            Conversation task) {
        protectOrUnprotect3300(request, task);
        positionCursor3300(request, task);
        setupColour3300(response, task);
        messageAttributes3300(request, task);
    }

    /**
     * {@code * PROTECT OR UNPROTECT BASED ON CONTEXT} - {@code app/cbl/COCRDUPC.cbl:1171-1208}.
     *
     * <p>A four-arm {@code EVALUATE TRUE} writing the basic 3270 attribute byte into the {@code xxxA}
     * items of the <strong>input</strong> group {@code CCRDUPAI}. {@code DFHBMFSE} is
     * unprotected-and-FSET, so the operator may type into the field; {@code DFHBMPRF} is
     * protected-and-FSET, so they may not but the field is still transmitted back.
     *
     * <table border="1">
     *   <caption>Which fields are typeable in each state</caption>
     *   <tr><th>Arm</th><th>{@code ACCTSID}, {@code CARDSID}</th>
     *       <th>{@code CRDNAME}, {@code CRDSTCD}, {@code EXPMON}, {@code EXPYEAR}</th></tr>
     *   <tr><td>{@code DETAILS-NOT-FETCHED} ({@code :1173})</td><td>{@code DFHBMFSE}</td>
     *       <td>{@code DFHBMPRF}</td></tr>
     *   <tr><td>{@code SHOW-DETAILS} or {@code CHANGES-NOT-OK} ({@code :1181-1182})</td>
     *       <td>{@code DFHBMPRF}</td><td>{@code DFHBMFSE}</td></tr>
     *   <tr><td>{@code CHANGES-OK-NOT-CONFIRMED} or {@code CHANGES-OKAYED-AND-DONE}
     *       ({@code :1191-1192})</td><td>{@code DFHBMPRF}</td><td>{@code DFHBMPRF}</td></tr>
     *   <tr><td>{@code WHEN OTHER} ({@code :1200})</td><td>{@code DFHBMFSE}</td>
     *       <td>{@code DFHBMPRF}</td></tr>
     * </table>
     *
     * <p>So the keys are typeable only before details are fetched, the details only once they are on the
     * screen, and <strong>nothing</strong> once the operator is being asked to confirm - which is what
     * makes the confirmation a confirmation of what was validated rather than of what is on the glass.
     *
     * <p>Two multi-{@code WHEN} OR-groups here ({@code :1181-1182} and {@code :1191-1192}), and
     * {@code WHEN OTHER} repeats arm 1's assignment exactly rather than sharing it - the source
     * duplicates those six moves at {@code :1201-1207} and they are duplicated here, because a shared
     * helper would hide that the two arms are separately maintained.
     *
     * <p><strong>{@code EXPDAYA} is commented out on all four arms</strong> ({@code :1178},
     * {@code :1185}, {@code :1197}, {@code :1205}), so the day field's attribute item is never assigned
     * and it keeps whatever the mapset's own {@code ATTRB} gave it. Absent here too (B5).
     *
     * @param request the inbound map, receiving the {@code xxxA} items
     * @param task    this task's storage
     */
    void protectOrUnprotect3300(CardUpdateRequest request, Conversation task) {
        ChangeAction action = task.changeAction();

        if (action.isDetailsNotFetched()) {
            // :1173-1180
            putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMPRF);
            // :1178 - EXPDAYA is commented out and stays absent
            putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMPRF);
            return;
        }
        if (action.isShowDetails() || action.isChangesNotOk()) {
            // :1181-1190 - an OR-group of two WHENs sharing one body
            putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMPRF);
            // :1185 - EXPDAYA commented out
            putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMFSE);
            putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMFSE);
            return;
        }
        if (action.isChangesOkNotConfirmed() || action.isChangesOkayedAndDone()) {
            // :1191-1199 - the second OR-group. Everything protected: nothing is typeable while the
            // operator confirms, and nothing is typeable after the update has been applied.
            putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMPRF);
            // :1197 - EXPDAYA commented out
            putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMPRF);
            putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMPRF);
            return;
        }
        // :1200-1207 - WHEN OTHER. Byte for byte what arm 1 assigns, and duplicated in the source
        // rather than shared; duplicated here for the same reason.
        putFieldAttribute(request, CardUpdateRequest.ACCTSID_FIELD, BmsAttributes.DFHBMFSE);
        putFieldAttribute(request, CardUpdateRequest.CARDSID_FIELD, BmsAttributes.DFHBMFSE);
        putFieldAttribute(request, CardUpdateRequest.CRDNAME_FIELD, BmsAttributes.DFHBMPRF);
        putFieldAttribute(request, CardUpdateRequest.CRDSTCD_FIELD, BmsAttributes.DFHBMPRF);
        // :1205 - EXPDAYA commented out
        putFieldAttribute(request, CardUpdateRequest.EXPMON_FIELD, BmsAttributes.DFHBMPRF);
        putFieldAttribute(request, CardUpdateRequest.EXPYEAR_FIELD, BmsAttributes.DFHBMPRF);
    }

    /**
     * {@code * POSITION CURSOR} - {@code app/cbl/COCRDUPC.cbl:1210-1235}.
     *
     * <p>An eight-arm {@code EVALUATE TRUE} whose every arm is {@code MOVE -1 TO <field>L}. In BMS,
     * {@code -1} in a length item is the request "put the cursor here", honoured by the
     * {@code CURSOR} option of the {@code SEND MAP} at {@code :1332}.
     *
     * <p>First match wins, and the ordering is the whole content of the paragraph:
     * <ol>
     *   <li>{@code FOUND-CARDS-FOR-ACCOUNT} or {@code NO-CHANGES-DETECTED} ({@code :1212-1213}, an
     *       OR-group) - the cursor goes to {@code CRDNAME}, the first typeable detail field. These two
     *       are tested <strong>before</strong> any field flag, so on a successful fetch the cursor lands
     *       ready to edit even though the untouched key flags would otherwise claim it.</li>
     *   <li>the six field flags in screen order, each an OR-group of that field's {@code NOT-OK} and
     *       {@code BLANK} conditions: {@code ACCTSID}, {@code CARDSID}, {@code CRDNAME},
     *       {@code CRDSTCD}, {@code EXPMON}, {@code EXPYEAR} ({@code :1215-1232}). So when two fields
     *       are both wrong the cursor goes to the earlier one on the screen.</li>
     *   <li>{@code WHEN OTHER} - {@code ACCTSID} ({@code :1233-1234}), the same field as arm 2, which
     *       makes the account number the default landing place.</li>
     * </ol>
     *
     * <p>Note that arm 1 tests {@code WS-INFO-MSG} and {@code WS-RETURN-MSG} content while arms 2 to 7
     * test the one-character flags - three different fields feeding one decision, which is why the
     * predicate names here do not all read alike.
     *
     * <p>Both the {@code xxxL} item on the request and {@link Conversation#cursorField} are written: the
     * first because {@code MOVE -1 TO ...L} is what the source does, the second because a REST response
     * carries the request as {@link ScreenMetadata#cursorField()} rather than as a negative number
     * buried in a length field.
     *
     * @param request the inbound map, receiving the {@code -1} length item
     * @param task    this task's storage, receiving the cursor label
     */
    void positionCursor3300(CardUpdateRequest request, Conversation task) {
        if (task.foundCardsForAccount() || task.noChangesDetected()) {
            // :1212-1214
            placeCursor3300(request, task, CardUpdateResponse.CRDNAME);
            return;
        }
        if (task.flgAcctfilterNotOk() || task.flgAcctfilterBlank()) {
            // :1215-1217
            placeCursor3300(request, task, CardUpdateResponse.ACCTSID);
            return;
        }
        if (task.flgCardfilterNotOk() || task.flgCardfilterBlank()) {
            // :1218-1220
            placeCursor3300(request, task, CardUpdateResponse.CARDSID);
            return;
        }
        if (task.flgCardnameNotOk() || task.flgCardnameBlank()) {
            // :1221-1223
            placeCursor3300(request, task, CardUpdateResponse.CRDNAME);
            return;
        }
        if (task.flgCardstatusNotOk() || task.flgCardstatusBlank()) {
            // :1224-1226
            placeCursor3300(request, task, CardUpdateResponse.CRDSTCD);
            return;
        }
        if (task.flgCardexpmonNotOk() || task.flgCardexpmonBlank()) {
            // :1227-1229
            placeCursor3300(request, task, CardUpdateResponse.EXPMON);
            return;
        }
        if (task.flgCardexpyearNotOk() || task.flgCardexpyearBlank()) {
            // :1230-1232
            placeCursor3300(request, task, CardUpdateResponse.EXPYEAR);
            return;
        }
        // :1233-1234 - WHEN OTHER, the same target as the account arm
        placeCursor3300(request, task, CardUpdateResponse.ACCTSID);
    }

    /**
     * {@code MOVE -1 TO <field>L OF CCRDUPAI} - the single statement every arm of the cursor
     * {@code EVALUATE} performs ({@code app/cbl/COCRDUPC.cbl:1214}-{@code :1234}).
     *
     * @param request the inbound map, whose {@code xxxL} item receives
     *                {@value CardUpdateRequest.FieldMetadata#CURSOR_LENGTH_ITEM}
     * @param task    this task's storage, whose cursor label is set
     * @param label   the {@code DFHMDF} label to place the cursor on
     */
    void placeCursor3300(CardUpdateRequest request, Conversation task, String label) {
        request.putFieldMetadata(request.metadataFor(label)
                .withLengthItem(CardUpdateRequest.FieldMetadata.CURSOR_LENGTH_ITEM));
        task.cursorField = label;
    }

    /**
     * {@code * SETUP COLOR} - {@code app/cbl/COCRDUPC.cbl:1237-1307}.
     *
     * <p>Fourteen unconditional-or-guarded {@code MOVE}s into {@code xxxC} colour items and four
     * {@code '*'} markers into {@code xxxO} payload items, in source order. Later statements overwrite
     * earlier ones, so order decides the final colour of a field that satisfies more than one test.
     *
     * <ol>
     *   <li>{@code IF CDEMO-LAST-MAPSET EQUAL LIT-CCLISTMAPSET} ({@code :1238-1241}) -
     *       {@code DFHDFCOL} onto both key fields, that is "back to the default colour", because the
     *       keys came from the card list rather than from the operator and are not an error. Tested
     *       <strong>first</strong>, so any red assigned below still wins.</li>
     *   <li>the two key fields: {@code NOT-OK} gives red <strong>unguarded</strong>
     *       ({@code :1243-1245}, {@code :1253-1255}); {@code BLANK} gives {@code '*'} and red but only
     *       {@code AND CDEMO-PGM-REENTER} ({@code :1247-1251}, {@code :1257-1261}). Only these two
     *       {@code BLANK} arms match {@code CSSETATY}'s rule, so only these two are routed through
     *       {@link FieldAttributeSetter} - which is also what exercises both the {@code ENTER} and
     *       {@code REENTER} paths of gate G38.</li>
     *   <li>the four detail fields: both arms guarded by {@code AND CCUP-CHANGES-NOT-OK}
     *       ({@code :1263-1307}), not by {@code CDEMO-PGM-REENTER}. So a detail field is only ever
     *       reddened while the screen is in the {@code 'E'} state, and never on the confirmation
     *       screen.</li>
     *   <li>{@code MOVE DFHBMDAR TO EXPDAYC} ({@code :1285}) - <strong>unconditional</strong>, and the
     *       only statement in the paragraph that is. {@code DFHBMDAR} is dark, so the expiry day is
     *       always invisible: it is fetched at {@code :1365}, carried in both detail groups, painted at
     *       {@code :1123}, and then darkened here. That is why {@code EXPDAY} exists in this mapset and
     *       in no other, yet is never seen. Preserved (B5).</li>
     * </ol>
     *
     * @param response the outbound map, receiving the colour items and the {@code '*'} markers
     * @param task     this task's storage
     */
    void setupColour3300(CardUpdateResponse response, Conversation task) {
        // :1238-1241 - the keys arrived from the card list, so put them back to the default colour
        if (lastMapsetIsCardList(task)) {
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHDFCOL);
            response.setColour(CardUpdateResponse.CARDSID, BmsAttributes.DFHDFCOL);
        }

        boolean reenter = task.carddemoCommarea.isReenter();
        boolean changesNotOk = task.changeAction().isChangesNotOk();

        // :1243-1245 - ACCTSID NOT-OK, with NO reenter guard. CSSETATY would have guarded it.
        if (task.flgAcctfilterNotOk()) {
            response.setColour(CardUpdateResponse.ACCTSID, BmsAttributes.DFHRED);
        }
        // :1247-1251 - ACCTSID BLANK AND CDEMO-PGM-REENTER: '*' then red. This IS CSSETATY's rule, so
        // the shared helper makes the decision and applies both items.
        if (task.flgAcctfilterBlank()) {
            response.applyHighlight(CardUpdateResponse.ACCTSID, FieldValidationState.BLANK, reenter);
        }

        // :1253-1255 - CARDSID NOT-OK, unguarded
        if (task.flgCardfilterNotOk()) {
            response.setColour(CardUpdateResponse.CARDSID, BmsAttributes.DFHRED);
        }
        // :1257-1261 - CARDSID BLANK AND CDEMO-PGM-REENTER
        if (task.flgCardfilterBlank()) {
            response.applyHighlight(CardUpdateResponse.CARDSID, FieldValidationState.BLANK, reenter);
        }

        // :1263-1266 - CRDNAME NOT-OK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardnameNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.CRDNAME, BmsAttributes.DFHRED);
        }
        // :1268-1272 - CRDNAME BLANK AND CCUP-CHANGES-NOT-OK: '*' then red. The guard is the change
        // action, not the program context, so the shared helper is given that boolean instead.
        if (task.flgCardnameBlank()) {
            response.applyHighlight(CardUpdateResponse.CRDNAME, FieldValidationState.BLANK,
                    changesNotOk);
        }

        // :1274-1277 - CRDSTCD NOT-OK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardstatusNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.CRDSTCD, BmsAttributes.DFHRED);
        }
        // :1279-1283 - CRDSTCD BLANK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardstatusBlank()) {
            response.applyHighlight(CardUpdateResponse.CRDSTCD, FieldValidationState.BLANK,
                    changesNotOk);
        }

        // :1285 - MOVE DFHBMDAR TO EXPDAYC, unconditionally. The day is always dark.
        response.setColour(CardUpdateResponse.EXPDAY, BmsAttributes.DFHBMDAR);

        // :1287-1290 - EXPMON NOT-OK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardexpmonNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.EXPMON, BmsAttributes.DFHRED);
        }
        // :1292-1296 - EXPMON BLANK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardexpmonBlank()) {
            response.applyHighlight(CardUpdateResponse.EXPMON, FieldValidationState.BLANK,
                    changesNotOk);
        }

        // :1298-1301 - EXPYEAR NOT-OK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardexpyearNotOk() && changesNotOk) {
            response.setColour(CardUpdateResponse.EXPYEAR, BmsAttributes.DFHRED);
        }
        // :1303-1307 - EXPYEAR BLANK AND CCUP-CHANGES-NOT-OK
        if (task.flgCardexpyearBlank()) {
            response.applyHighlight(CardUpdateResponse.EXPYEAR, FieldValidationState.BLANK,
                    changesNotOk);
        }
    }

    /**
     * The last two statements of {@code 3300-SETUP-SCREEN-ATTRS} -
     * {@code app/cbl/COCRDUPC.cbl:1309-1317}.
     *
     * <pre>
     * IF WS-NO-INFO-MESSAGE  MOVE DFHBMDAR TO INFOMSGA  ELSE  MOVE DFHBMBRY TO INFOMSGA  END-IF
     * IF PROMPT-FOR-CONFIRMATION  MOVE DFHBMBRY TO FKEYSCA  END-IF
     * </pre>
     *
     * <p>Both write {@code xxxA} items of the input group, so they are basic attribute bytes and not
     * colours: {@code DFHBMDAR} is dark, {@code DFHBMBRY} is bright. An absent informational message is
     * hidden rather than shown as forty blanks, and the second block of function-key text is brightened
     * only while the operator is being asked to confirm - the one moment {@code PF5} means something.
     *
     * <p><strong>{@code FKEYSCA} is the attribute item of the field named {@code FKEYSC}</strong>, an
     * {@code X(18)} field in its own right at {@code app/cpy-bms/COCRDUP.CPY:115-120}, and not the
     * colour item of {@code FKEYS}. The colour item of {@code FKEYS} is spelled {@code FKEYSC} too, at
     * {@code :214} - the same eight characters naming two different bytes. A mapper that stripped the
     * trailing {@code C} as a suffix would silently delete an 18-byte field, so the two are kept
     * distinct and both are asserted present.
     *
     * @param request the inbound map, receiving the two {@code xxxA} items
     * @param task    this task's storage
     */
    void messageAttributes3300(CardUpdateRequest request, Conversation task) {
        // :1309-1313 - hide the message line when there is no message, brighten it when there is
        putFieldAttribute(request, CardUpdateRequest.INFOMSG_FIELD,
                task.noInfoMessage() ? BmsAttributes.DFHBMDAR : BmsAttributes.DFHBMBRY);

        // :1315-1317 - brighten the FKEYSC field only while confirmation is being requested
        if (task.promptForConfirmation()) {
            putFieldAttribute(request, CardUpdateRequest.FKEYSC_FIELD, BmsAttributes.DFHBMBRY);
        }
    }

    /**
     * {@code MOVE <attribute> TO <field>A OF CCRDUPAI} - the {@code xxxA} item, which
     * {@code REDEFINES} the {@code xxxF} flag byte of the input group.
     *
     * <p>{@code xxxA} is metadata, never a JSON payload member (gate G9), so it lives on the request's
     * {@link CardUpdateRequest.FieldMetadata} alongside the {@code xxxL} and {@code xxxF} items rather
     * than among the {@value CardUpdateResponse#NAMED_FIELD_COUNT} payload fields. The byte is widened
     * through {@code & 0xFF} before the {@code char} cast so that an attribute above {@code 0x7F} - and
     * {@code DFHBMPRF}, {@code DFHBMFSE} and {@code DFHBMDAR} all are - does not sign-extend into a
     * different code point.
     *
     * @param request   the inbound map
     * @param label     the {@code DFHMDF} label
     * @param attribute the attribute byte to move
     */
    void putFieldAttribute(CardUpdateRequest request, String label, byte attribute) {
        request.putFieldMetadata(request.metadataFor(label)
                .withAttributeItem(String.valueOf((char) (attribute & 0xFF))));
    }

    /**
     * {@code 3400-SEND-SCREEN} - {@code app/cbl/COCRDUPC.cbl:1324-1337}.
     *
     * <pre>
     * MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET
     * MOVE LIT-THISMAP    TO CCARD-NEXT-MAP
     * EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET) FROM(CCRDUPAO)
     *                CURSOR ERASE FREEKB RESP(WS-RESP-CD)
     * </pre>
     *
     * <p>The {@code SEND} names the map through {@code CCARD-NEXT-MAP} rather than through a literal,
     * so the two {@code MOVE}s above it are not bookkeeping - they are what selects the map, and the
     * name is carried into the response for the client to render.
     *
     * <p><strong>A width truncation, reproduced.</strong> {@code LIT-THISMAPSET} is declared
     * {@code PIC X(8) VALUE 'COCRDUP '} at {@code :225} while {@code CCARD-NEXT-MAPSET} is
     * {@code PIC X(7)} in {@code app/cpy/CVCRD01Y.cpy}. A {@code PIC X} move truncates on the
     * <strong>right</strong>, so the trailing space is discarded and the receiver holds {@code COCRDUP}
     * - which is the correct mapset name, and correct by accident of the padding. The eighth byte is
     * dropped by the move and not by this translation; {@link FixedWidthCodec#movePicX} performs the
     * same truncation for the same reason.
     *
     * <p>{@code CURSOR} honours the {@code -1} that {@link #positionCursor3300} placed;
     * {@code ERASE} is why {@code 3100}'s {@code MOVE LOW-VALUES} is not enough on its own; and
     * {@code FREEKB} unlocks the keyboard. All three are carried as
     * {@link ScreenMetadata#resetAllOutputFields()} and the cursor field rather than as flags this
     * server acts on, because there is no 3270 here to act on them.
     *
     * @param response the outbound map, receiving the mapset and map names
     * @param task     this task's storage
     */
    void sendScreen3400(CardUpdateResponse response, Conversation task) {
        // :1326 - MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET; X(8) into X(7), dropping the trailing space
        task.ccWorkArea.setCcardNextMapset(
                codec.movePicX(LIT_THISMAPSET, CardScreenState.CCARD_NEXT_MAPSET_LENGTH));
        // :1327 - MOVE LIT-THISMAP TO CCARD-NEXT-MAP; X(7) into X(7)
        task.ccWorkArea.setCcardNextMap(codec.movePicX(LIT_THISMAP, CardScreenState.CCARD_NEXT_MAP_LENGTH));

        // :1329-1336 - EXEC CICS SEND MAP(CCARD-NEXT-MAP) MAPSET(CCARD-NEXT-MAPSET). The map is named
        // by the work-area field, so the response carries what the field holds - never a literal.
        response.setNextMapset(task.ccWorkArea.getCcardNextMapset());
        response.setNextMap(task.ccWorkArea.getCcardNextMap());

        // The SEND itself has no server-side counterpart: the map IS the response body. RESP is set to
        // the normal condition because serialising a response cannot fail the way a terminal write can,
        // and leaving WS-RESP-CD holding a stale file-read RESP would misreport what happened last.
        task.wsRespCd = FileStatus.NORMAL;
    }

    // =================================================================================================
    // ABEND-ROUTINE and the response metadata projection.
    // =================================================================================================

    /**
     * The {@code xxxL}, {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} metadata of the
     * outbound map, projected for the client.
     *
     * <p>These items are <strong>not</strong> payload fields (AAP §0.6.3, gate G9): the seventeen
     * {@code xxxO} items are the payload, and the attribute quads plus the cursor request are metadata
     * that travels beside them. {@link CardUpdateResponse} has no {@code screenMetadata()} of its own -
     * it holds the quads and lets the program that painted them decide how to publish them, which is
     * the same division {@code UserMenuController} and {@code TransactionMenuController} use.
     *
     * <p>The message colour reported is {@code ERRMSG}'s, because that is the field the operator's eye
     * goes to and the one {@code 3250:1163} always writes. {@code resetAllOutputFields} is always
     * {@code true}, because {@code 3100:1053} always does {@code MOVE LOW-VALUES TO CCRDUPAO} and
     * {@code 3400:1333} always sends {@code ERASE} - this screen has no partial-repaint path.
     *
     * @param response    the outbound map whose quads are read
     * @param cursorField the {@code DFHMDF} label the cursor was requested on, or {@code null}
     * @return the metadata; never {@code null}
     */
    ScreenMetadata screenMetadataOf(CardUpdateResponse response, String cursorField) {
        Map<String, ScreenMetadata.FieldMetadata> quads = new LinkedHashMap<>();
        for (CardUpdateResponse.ScreenField field : CardUpdateResponse.namedFields()) {
            CardUpdateResponse.FieldAttributes attributes = response.attributesOf(field.name());
            quads.put(field.name(), ScreenMetadata.FieldMetadata.of(attributes.getColour(),
                    attributes.getPs(), attributes.getHilight(), attributes.getValidn()));
        }
        return ScreenMetadata.of(cursorField,
                response.colourOf(CardUpdateResponse.ERRMSG),
                true,
                quads);
    }

    /**
     * {@code ABEND-ROUTINE} - {@code app/cbl/COCRDUPC.cbl:1531-1553}.
     *
     * <pre>
     * IF ABEND-MSG EQUAL LOW-VALUES  MOVE 'UNEXPECTED ABEND OCCURRED.' TO ABEND-MSG  END-IF
     * MOVE LIT-THISPGM TO ABEND-CULPRIT
     * EXEC CICS SEND FROM(ABEND-DATA) LENGTH(LENGTH OF ABEND-DATA) ERASE NOHANDLE END-EXEC
     * EXEC CICS HANDLE ABEND CANCEL END-EXEC
     * EXEC CICS ABEND ABCODE('9999') NODUMP END-EXEC
     * </pre>
     *
     * <p>Three things about it are load-bearing:
     * <ul>
     *   <li><strong>The default is applied only if {@code ABEND-MSG} is still {@code LOW-VALUES}</strong>
     *       ({@code :1533-1535}) - binary {@code x'00'} across all 78 bytes, not spaces. So
     *       {@code 2000}'s {@code WHEN OTHER}, which sets its own text before performing this routine,
     *       keeps it; and the {@code HANDLE ABEND} path at {@code :370}, which arrives with the field
     *       untouched, gets the generic text.</li>
     *   <li>{@code MOVE LIT-THISPGM TO ABEND-CULPRIT} is <strong>unconditional</strong>
     *       ({@code :1537}), so it overwrites whatever a caller put there - which is why {@code 2000}
     *       setting it at {@code :1020} makes no difference. Reproduced, including its
     *       redundancy.</li>
     *   <li>{@code EXEC CICS SEND FROM(ABEND-DATA)} writes the raw structure to the terminal, with no
     *       map - so the operator sees the four fields concatenated and no screen. There is no map to
     *       build here, which is why this method builds none.</li>
     * </ul>
     *
     * <p>The diagnostic is logged rather than sent, since there is no terminal, and it is logged through
     * {@link BackendDiagnostic} with the cause's <em>description</em> rather than the
     * throwable, so nothing a caller supplied can forge a log line (CWE-117).
     *
     * <p>Returns the exception instead of throwing it so that every caller's control flow reads
     * {@code throw abendRoutine(...)} and the compiler can see that the path terminates. The COBOL's
     * {@code EXEC CICS ABEND} does not return either.
     *
     * @param task     this task's storage
     * @param response the outbound map, if one had been started; may be {@code null}, since this routine
     *                 sends no map
     * @param cause    the failure that led here, or {@code null} when the program abended on its own
     *                 logic rather than on an exception
     * @return the exception to throw
     */
    AbendException abendRoutine(Conversation task, CardUpdateResponse response, RuntimeException cause) {
        SystemMessages.AbendData abendData =
                task.abendData == null ? SystemMessages.AbendData.spaces() : task.abendData;

        // :1533-1535 - the default, only when ABEND-MSG is still LOW-VALUES
        if (CardScreenState.lowValues(SystemMessages.ABEND_MSG_LENGTH).equals(abendData.abendMsg())) {
            abendData = abendData.withAbendMsg(UNEXPECTED_ABEND_OCCURRED);
        }
        // :1537 - MOVE LIT-THISPGM TO ABEND-CULPRIT, unconditionally
        abendData = abendData
                .withAbendCulprit(codec.movePicX(LIT_THISPGM, SystemMessages.ABEND_CULPRIT_LENGTH))
                .toDeclaredWidths();
        task.abendData = abendData;

        // :1539-1544 - EXEC CICS SEND FROM(ABEND-DATA) ERASE NOHANDLE. No terminal here, so the four
        // fields are logged as one image.
        //
        // The cause is DESCRIBED, never handed to the logger. Handing a throwable to Commons Logging
        // emits its message and its whole cause chain verbatim, and for a JDBC failure that text is
        // composed by the driver around the record it refused - so a record image could reach a log line,
        // and an embedded CR or LF in it could forge a second entry (CWE-117). BackendDiagnostic carries
        // the SQLSTATE, the vendor code and the exception type and has no component for the driver's
        // message at all. The throwable itself is not discarded: it travels as the cause of the
        // AbendException returned below.
        //
        // And the cause is OPTIONAL, which is not defensiveness - it is this program's shape. Unlike its
        // sibling COCRDSLC, whose WHEN OTHER at :373-380 sets WS-RETURN-MSG and repaints the screen,
        // COCRDUPC's WHEN OTHER at :1019-1026 performs ABEND-ROUTINE directly. That path abends on the
        // program's own logic with no exception in flight, so there is nothing to read a diagnostic from;
        // BackendDiagnostic.of rejects a null argument, correctly, because a diagnostic of nothing is not
        // a diagnostic. The two arrivals are reported distinctly so a log reader can tell an
        // unexpected-data abend from a data-access one.
        LOG.error("app/cbl/COCRDUPC.cbl:1531 ABEND-ROUTINE: " + abendDataImage(abendData)
                + " ABCODE " + ABEND_ROUTINE_ABCODE + " ["
                + (cause == null ? NO_TRIGGERING_FAILURE : BackendDiagnostic.of(cause).describe())
                + ']');

        // The response, when one exists, is left carrying whatever had been painted; the COBOL's ERASE
        // clears the screen and sends the structure instead, so no map reaches the operator either way.
        if (response != null) {
            response.setErrmsgo(codec.movePicX(abendData.abendMsg(),
                    CardUpdateResponse.ERRMSGO_LENGTH));
        }
        task.returned = true;

        // :1546-1552 - HANDLE ABEND CANCEL then ABEND ABCODE('9999') NODUMP. The four-character ABCODE
        // is not a return code, so it is carried in the message and the exception's own code is the
        // I/O-error status the batch convention uses for an unrecoverable task.
        return AbendException.withoutAbendParameters(LIT_THISPGM,
                        AbendException.RETURN_CODE_IO_ERROR,
                        "ABCODE " + ABEND_ROUTINE_ABCODE + ": " + abendData.abendMsg().trim(), cause)
                .withSourceDiagnostic(abendDataImage(abendData));
    }

    /**
     * {@code ABEND-DATA} rendered as the bytes {@code EXEC CICS SEND FROM(ABEND-DATA)} would have put on
     * the terminal ({@code app/cbl/COCRDUPC.cbl:1539-1544}): the four items concatenated at their
     * declared widths, in {@code app/cpy/CSMSG02Y.cpy} order, with no separator.
     *
     * @param abendData the structure to render
     * @return the concatenation
     */
    String abendDataImage(SystemMessages.AbendData abendData) {
        SystemMessages.AbendData atWidth = abendData.toDeclaredWidths();
        return atWidth.abendCode() + atWidth.abendCulprit() + atWidth.abendReason()
                + atWidth.abendMsg();
    }
}
