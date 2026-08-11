package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The outbound payload of {@code GET /api/menu} - the main menu screen of CICS transaction
 * {@code CM00}, program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}.
 *
 * <h2>What this type is, exactly</h2>
 * A 1:1 projection of the <strong>{@code xxxO} items</strong> of
 * {@code 01 COMEN1AO REDEFINES COMEN1AI}, which opens at line 139 of
 * {@code app/cpy-bms/COMEN01.CPY}. The {@code xxxO} items are the twenty values the COBOL
 * <em>sends</em>; the matching {@code xxxI} items are what it <em>receives</em> and belong to the
 * request type, not here.
 *
 * <p>The screen shape is corroborated from two independent directions, and the two agree:
 * <ul>
 *   <li>{@code app/cpy-bms/COMEN01.CPY} declares exactly <strong>20</strong> {@code xxxO} items.</li>
 *   <li>{@code app/bms/COMEN01.bms} contains <strong>28</strong> {@code DFHMDF} field definitions of
 *       which exactly <strong>20 carry a name label</strong>. The other eight are unnamed screen
 *       furniture and are deliberately absent from this payload: the literals {@code 'Tran:'} at
 *       {@code POS=(1,1)}, {@code 'Date:'} at {@code (1,65)}, {@code 'Prog:'} at {@code (2,1)},
 *       {@code 'Time:'} at {@code (2,65)}, the {@code LENGTH=9 INITIAL='Main Menu'} heading at
 *       {@code (4,35)}, {@code 'Please select an option :'} at {@code (20,15)}, a {@code LENGTH=0}
 *       stopper at {@code (20,44)}, and {@code 'ENTER=Continue  F3=Exit'} at {@code (24,1)}. A
 *       literal the terminal paints from the mapset is not data the server sends.</li>
 * </ul>
 *
 * <h2>The symbolic map is 820 bytes, and the arithmetic proves the field list is complete</h2>
 * The {@code AO} group repeats a fixed six-item pattern per field: {@code FILLER PICTURE X(3)}, then
 * the colour byte {@code xxxC}, the programmed-symbol byte {@code xxxP}, the highlight byte
 * {@code xxxH}, the validation byte {@code xxxV}, and finally the payload item {@code xxxO PIC X(n)}.
 * The stride is therefore {@code 7 + n}, which is byte-for-byte the same stride as the {@code AI}
 * group's {@code xxxL COMP PIC S9(4)} (2) plus {@code xxxF PICTURE X} (1) plus {@code FILLER
 * PICTURE X(4)} (4) - and that identity is precisely why {@code AO} is able to
 * {@code REDEFINES COMEN1AI} at all.
 *
 * <p>Adding it up: a 12-byte {@code TIOAPFX} {@code FILLER}, plus twenty 7-byte attribute prefixes,
 * plus the payload widths, gives {@code 12 + 20 * 7 + 668 = 820} bytes. The
 * {@link #PAYLOAD_BYTES} total of {@value #PAYLOAD_BYTES} is the sum of the twenty
 * {@code PICTURE} widths declared below, so if a field were missing or mis-sized the total would not
 * reconcile. {@link #SYMBOLIC_MAP_LENGTH} records the whole-image figure.
 *
 * <h2>Twelve option lines, of which only ten are ever populated - and all twelve stay</h2>
 * This is the sharpest preservation trap on this screen, so the evidence is recorded in full rather
 * than summarised:
 * <ul>
 *   <li>{@code app/bms/COMEN01.bms} declares all twelve fields, {@code OPTN001} through
 *       {@code OPTN012}, at {@code POS=(6,20)} through {@code POS=(17,20)}.</li>
 *   <li>{@code app/cpy/COMEN02Y.cpy} declares the menu table as {@code CDEMO-MENU-OPT OCCURS 12
 *       TIMES} at line 88, while line 21 sets {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10}. Table
 *       size and active count are two separate facts and must never be conflated: twelve slots
 *       exist, ten hold data.</li>
 *   <li>{@code BUILD-MENU-OPTIONS} in {@code app/cbl/COMEN01C.cbl} loops
 *       {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > CDEMO-MENU-OPT-COUNT} at lines
 *       238-239. Its {@code EVALUATE WS-IDX} does provide arms for all twelve slots - {@code WHEN 11}
 *       writes {@code OPTN011O} at line 270 and {@code WHEN 12} writes {@code OPTN012O} at line 272,
 *       ahead of {@code WHEN OTHER CONTINUE} - but the loop bound of ten means {@code WS-IDX} never
 *       reaches 11 or 12, so those two arms are unreachable and
 *       <strong>{@code OPTN011O} and {@code OPTN012O} are never written by the program at all.</strong>
 *       Both menu programs behave this way.</li>
 * </ul>
 * Unreachable is not the same as absent. The mapset defines the fields, so the payload defines the
 * members: {@link #optn011()} and {@link #optn012()} exist, are addressable, and round-trip like any
 * other. Shrinking this response to the active count of {@value #ACTIVE_OPTION_LINE_COUNT} would be
 * a behaviour change dressed up as tidying.
 *
 * <h2>Why an almost identical response type also exists for the admin menu</h2>
 * A reviewer meeting these two types for the first time will suspect copy-paste. They are duplicates,
 * the duplication is deliberate, and it is inherited rather than introduced. {@code COMEN01.CPY} and
 * {@code COADM01.CPY} are byte-identical apart from their two group names: a line-level diff reports
 * differences at <strong>line 17 and line 139 only</strong> ({@code COMEN1AI}/{@code COMEN1AO} versus
 * {@code COADM1AI}/{@code COADM1AO}). The two mapsets likewise differ only in mapset name, map name
 * and one unnamed heading literal - {@code LENGTH=9 INITIAL='Main Menu'} here against
 * {@code LENGTH=10 INITIAL='Admin Menu'} there.
 *
 * <p>So the member set and the twenty widths are provably identical, and this type is still
 * deliberately independent: it does not extend, implement, wrap, delegate to or share a base type
 * with its admin counterpart. Folding them together would couple two CICS screens that the legacy
 * system keeps separate, and the next divergence in either mapset would then have to be unpicked. The
 * two screens already differ behaviourally, which is the clearest argument against merging them: this
 * one carries the {@code 'No access - Admin Only option... '} message produced by the user-type filter
 * at {@code app/cbl/COMEN01C.cbl:136-143}, and its coming-soon text includes the selected option's
 * name, whereas the admin program's equivalent {@code STRING} has the option-name lines commented out.
 *
 * <h2>Statelessness</h2>
 * Every scrap of conversation state travels in the payload. {@link #navigationContext()} carries the
 * echoed 160-byte {@code CARDDEMO-COMMAREA}, and {@link #nextProgram()}, {@link #nextMapset()} and
 * {@link #nextMap()} carry what {@code EXEC CICS XCTL} used to do, so the client performs the
 * transfer. This type touches no servlet session, no session-scoped bean, no server-side conversation
 * store and no static cache.
 *
 * <h2>What this type deliberately does not do</h2>
 * <ul>
 *   <li><strong>It does not truncate, pad, trim or coerce.</strong> Values are stored exactly as
 *       handed over. The real {@code PIC X(80)} to {@code PIC X(78)} narrowing at
 *       {@code app/cbl/COMEN01C.cbl:187} - {@code MOVE WS-MESSAGE TO ERRMSGO} - is fixed-width work
 *       and belongs to the controller and its codec, not to a payload record. {@link #errMsg} is
 *       simply declared at its true width of {@value #ERR_MSG_LENGTH}, and the {@code @Size}
 *       annotations state each width declaratively for Bean Validation instead of silently enforcing
 *       it. That is also what keeps a JSON round-trip exact: an all-spaces option line stays
 *       all spaces, and a zero-filled {@code "01"} stays {@code "01"}.</li>
 *   <li><strong>It renders no fixed-width image.</strong> No 820-byte serialisation lives here.</li>
 *   <li><strong>It holds no business logic.</strong> No menu building, no option-line composition, no
 *       option-number validation, no user-type authorisation filter, no {@code 'DUMMY'} prefix test,
 *       no colour decision and no message composition. The service owns all of it and the menu option
 *       table is its own type, which this file does not import. Keeping decisions out of the payload
 *       is what lets the branch-coverage gate be met by testing services directly, with no
 *       {@code MockMvc} and no {@code JobLauncher} in the path.</li>
 *   <li><strong>It exposes no attribute metadata as payload.</strong> The four bytes per field -
 *       {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV} - and the input side's {@code xxxL},
 *       {@code xxxF} and {@code xxxA} items are presentation metadata, never JSON members. That
 *       includes {@link #errMsgColor()}, which is the {@code ERRMSGC} byte and is {@code @JsonIgnore}d:
 *       it is a component of this record, so it is reachable in Java where the service sets it, but it
 *       is not a property of the document. It was once an ordinary member on the strength of having
 *       been renamed away from {@code ERRMSGC}, which does not help - a record component is a JSON
 *       property whatever it is called, and a client had no way to tell it from the twenty fields that
 *       do trace to a {@code DFHMDF} definition.</li>
 *   <li><strong>It carries nothing security-related.</strong> No credential, no password, no token, no
 *       role and no authorisation annotation. Only the finished message text of an authorisation
 *       decision ever reaches this type.</li>
 * </ul>
 *
 * <h2>Governing rules</h2>
 * <strong>No user-specified rules were provided for this project</strong> - the rules document
 * consists of the single line "No user rules provided." Their absence is not a licence to lower the
 * bar, so enterprise-standard practice governs instead, and the practices that bite here are: exact
 * verified dependencies, so validation annotations come from the {@code jakarta.validation.constraints}
 * package of Bean Validation 3.0 and never from the superseded pre-Jakarta namespace;
 * reference COBOL sources treated as immutable; document rather than silently fix, which is why the
 * duplication note above exists; preserve dead and unreachable code, which is why all twelve option
 * lines exist; leave the security posture untouched; explicit over implicit, hence named length
 * constants and no wildcard imports; no static mutable state; and hand-written, reviewable code with
 * no third-party copybook parser.
 *
 * @param trnName              {@code TRNNAMEO PIC X(4)}, CPY line 146. Map field {@code TRNNAME}.
 *                             Written by {@code MOVE WS-TRANID TO TRNNAMEO} at
 *                             {@code app/cbl/COMEN01C.cbl:218}, so its value is
 *                             {@value #TRANSACTION_ID}
 * @param title01              {@code TITLE01O PIC X(40)}, CPY line 152. Map field {@code TITLE01}.
 *                             Written by {@code MOVE CCDA-TITLE01 TO TITLE01O} at
 *                             {@code app/cbl/COMEN01C.cbl:216} from {@code app/cpy/COTTL01Y.cpy}
 * @param curDate              {@code CURDATEO PIC X(8)}, CPY line 158. Map field {@code CURDATE}.
 *                             Written by {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} at
 *                             {@code app/cbl/COMEN01C.cbl:225}; the group is
 *                             {@code 9(02) '/' 9(02) '/' 9(02)}, so the rendering is
 *                             {@code MM/DD/YY} and is exactly 8 bytes
 * @param pgmName              {@code PGMNAMEO PIC X(8)}, CPY line 164. Map field {@code PGMNAME}.
 *                             Written by {@code MOVE WS-PGMNAME TO PGMNAMEO} at
 *                             {@code app/cbl/COMEN01C.cbl:219}, so its value is
 *                             {@value #PROGRAM_NAME}
 * @param title02              {@code TITLE02O PIC X(40)}, CPY line 170. Map field {@code TITLE02}.
 *                             Written by {@code MOVE CCDA-TITLE02 TO TITLE02O} at
 *                             {@code app/cbl/COMEN01C.cbl:217} from {@code app/cpy/COTTL01Y.cpy}
 * @param curTime              {@code CURTIMEO PIC X(8)}, CPY line 176. Map field {@code CURTIME}.
 *                             Written by {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO} at
 *                             {@code app/cbl/COMEN01C.cbl:231}; the group is
 *                             {@code 9(02) ':' 9(02) ':' 9(02)}, so the rendering is
 *                             {@code HH:MM:SS} and is exactly 8 bytes
 * @param optn001              {@code OPTN001O PIC X(40)}, CPY line 182. Map field {@code OPTN001} at
 *                             {@code POS=(6,20)}. Written by {@code BUILD-MENU-OPTIONS} slot 1
 *                             ({@code app/cbl/COMEN01C.cbl:250})
 * @param optn002              {@code OPTN002O PIC X(40)}, CPY line 188. Map field {@code OPTN002} at
 *                             {@code POS=(7,20)}. Written by slot 2 ({@code :252})
 * @param optn003              {@code OPTN003O PIC X(40)}, CPY line 194. Map field {@code OPTN003} at
 *                             {@code POS=(8,20)}. Written by slot 3 ({@code :254})
 * @param optn004              {@code OPTN004O PIC X(40)}, CPY line 200. Map field {@code OPTN004} at
 *                             {@code POS=(9,20)}. Written by slot 4 ({@code :256})
 * @param optn005              {@code OPTN005O PIC X(40)}, CPY line 206. Map field {@code OPTN005} at
 *                             {@code POS=(10,20)}. Written by slot 5 ({@code :258})
 * @param optn006              {@code OPTN006O PIC X(40)}, CPY line 212. Map field {@code OPTN006} at
 *                             {@code POS=(11,20)}. Written by slot 6 ({@code :260})
 * @param optn007              {@code OPTN007O PIC X(40)}, CPY line 218. Map field {@code OPTN007} at
 *                             {@code POS=(12,20)}. Written by slot 7 ({@code :262})
 * @param optn008              {@code OPTN008O PIC X(40)}, CPY line 224. Map field {@code OPTN008} at
 *                             {@code POS=(13,20)}. Written by slot 8 ({@code :264})
 * @param optn009              {@code OPTN009O PIC X(40)}, CPY line 230. Map field {@code OPTN009} at
 *                             {@code POS=(14,20)}. Written by slot 9 ({@code :266})
 * @param optn010              {@code OPTN010O PIC X(40)}, CPY line 236. Map field {@code OPTN010} at
 *                             {@code POS=(15,20)}. Written by slot 10 ({@code :268}) - the last slot
 *                             the loop bound of {@value #ACTIVE_OPTION_LINE_COUNT} allows
 * @param optn011              {@code OPTN011O PIC X(40)}, CPY line 242. Map field {@code OPTN011} at
 *                             {@code POS=(16,20)}. <strong>Never written:</strong> the
 *                             {@code WHEN 11} arm at {@code app/cbl/COMEN01C.cbl:270} is unreachable
 *                             because the loop stops at {@code CDEMO-MENU-OPT-COUNT}. Preserved
 *                             regardless
 * @param optn012              {@code OPTN012O PIC X(40)}, CPY line 248. Map field {@code OPTN012} at
 *                             {@code POS=(17,20)}. <strong>Never written:</strong> the
 *                             {@code WHEN 12} arm at {@code app/cbl/COMEN01C.cbl:272} is likewise
 *                             unreachable. Preserved regardless
 * @param option               {@code OPTIONO PIC X(2)}, CPY line 254. Map field {@code OPTION} at
 *                             {@code POS=(20,41)}. Written by {@code MOVE WS-OPTION TO OPTIONO} at
 *                             {@code app/cbl/COMEN01C.cbl:125}. A two-character {@code String} and
 *                             never an {@code int}: the sending field is {@code WS-OPTION PIC 9(02)},
 *                             so the value arrives <strong>zero-filled</strong> - option 1 renders
 *                             {@code "01"} and option 10 renders {@code "10"} - and the mapset
 *                             independently confirms it with {@code JUSTIFY=(RIGHT,ZERO)}. An
 *                             {@code int} would lose the leading zero and could not express the
 *                             all-spaces initial state
 * @param errMsg               {@code ERRMSGO PIC X(78)}, CPY line 260. Map field {@code ERRMSG} at
 *                             {@code POS=(23,1)}. Written by {@code MOVE WS-MESSAGE TO ERRMSGO} at
 *                             {@code app/cbl/COMEN01C.cbl:187}. Exactly
 *                             {@value #ERR_MSG_LENGTH} - never 80 and never 50
 * @param navigationContext    the echoed {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy},
 *                             160 bytes and returned unaltered
 * @param nextProgram          the {@code EXEC CICS XCTL} target the client should call next
 * @param nextMapset           the mapset owning the next map; {@value #MAPSET_NAME} for this screen
 * @param nextMap              the next map to render; {@value #MAP_NAME} for this screen
 * @param errMsgColor          <strong>not a JSON property</strong>: the {@code ERRMSGC} attribute
 *                             byte, which {@code app/cpy-bms/COMEN01.CPY:256} declares as metadata
 *                             alongside {@code ERRMSGP}, {@code ERRMSGH} and {@code ERRMSGV}. Only
 *                             {@code ERRMSGO PIC X(78)} at line 260 is payload. The extended-colour
 *                             byte for the message line, defaulting to
 *                             {@code DFHRED}; presentation metadata, not screen text
 * @param resetAllOutputFields <strong>not a JSON property</strong>: it names an action and
 *                             corresponds to no copybook item. Whether the client should clear every
 *                             output field before painting,
 *                             mirroring {@code MOVE LOW-VALUES TO COMEN1AO} at
 *                             {@code app/cbl/COMEN01C.cbl:89}
 */
public record MainMenuResponse(
        @Size(max = TRN_NAME_LENGTH) @JsonProperty("trnname") String trnName,
        @Size(max = TITLE_LENGTH) String title01,
        @Size(max = CUR_DATE_LENGTH) @JsonProperty("curdate") String curDate,
        @Size(max = PGM_NAME_LENGTH) @JsonProperty("pgmname") String pgmName,
        @Size(max = TITLE_LENGTH) String title02,
        @Size(max = CUR_TIME_LENGTH) @JsonProperty("curtime") String curTime,
        @Size(max = OPTION_LINE_LENGTH) String optn001,
        @Size(max = OPTION_LINE_LENGTH) String optn002,
        @Size(max = OPTION_LINE_LENGTH) String optn003,
        @Size(max = OPTION_LINE_LENGTH) String optn004,
        @Size(max = OPTION_LINE_LENGTH) String optn005,
        @Size(max = OPTION_LINE_LENGTH) String optn006,
        @Size(max = OPTION_LINE_LENGTH) String optn007,
        @Size(max = OPTION_LINE_LENGTH) String optn008,
        @Size(max = OPTION_LINE_LENGTH) String optn009,
        @Size(max = OPTION_LINE_LENGTH) String optn010,
        @Size(max = OPTION_LINE_LENGTH) String optn011,
        @Size(max = OPTION_LINE_LENGTH) String optn012,
        @Size(max = OPTION_LENGTH) String option,
        @Size(max = ERR_MSG_LENGTH) @JsonProperty("errmsg") String errMsg,
        NavigationContext navigationContext,
        @Size(max = NEXT_PROGRAM_LENGTH) String nextProgram,
        @Size(max = NEXT_MAPSET_LENGTH) String nextMapset,
        @Size(max = NEXT_MAP_LENGTH) String nextMap,
        @JsonIgnore byte errMsgColor,
        @JsonIgnore boolean resetAllOutputFields) {

    // =================================================================================================
    // Screen identity. These four values identify the CICS artefacts this payload belongs to, and are
    // fixed by app/csd/CARDDEMO.CSD and app/bms/COMEN01.bms rather than chosen here.
    // =================================================================================================

    /**
     * The transaction that reaches this screen: {@code DEFINE TRANSACTION(CM00)} at
     * {@code app/csd/CARDDEMO.CSD:399}, whose {@code PROGRAM(COMEN01C)} is on line 400. Also the value
     * of {@code WS-TRANID PIC X(04) VALUE 'CM00'} at {@code app/cbl/COMEN01C.cbl:37}, which
     * {@link #trnName()} receives.
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * The COBOL program this payload is the output of: {@code DEFINE PROGRAM(COMEN01C)} at
     * {@code app/csd/CARDDEMO.CSD:235}. Also the value of
     * {@code WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} at {@code app/cbl/COMEN01C.cbl:36}, which
     * {@link #pgmName()} receives.
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * The mapset: {@code COMEN01 DFHMSD} at {@code app/bms/COMEN01.bms:19}, registered as
     * {@code DEFINE MAPSET(COMEN01)} at {@code app/csd/CARDDEMO.CSD:133}. This is the default
     * {@link #nextMapset()} for this screen, and it is 7 characters, matching
     * {@code CDEMO-LAST-MAPSET PIC X(7)}.
     */
    public static final String MAPSET_NAME = "COMEN01";

    /**
     * The map within the mapset: {@code COMEN1A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)} at
     * {@code app/bms/COMEN01.bms:26}, and the map named by {@code EXEC CICS SEND MAP('COMEN1A')} at
     * {@code app/cbl/COMEN01C.cbl:190}. This is the default {@link #nextMap()} for this screen, and it
     * is 7 characters, matching {@code CDEMO-LAST-MAP PIC X(7)}.
     */
    public static final String MAP_NAME = "COMEN1A";

    /**
     * The sign-on program this screen falls back to when the user presses {@code PF3} or arrives with
     * no communication area.
     *
     * <p>{@code RETURN-TO-SIGNON-SCREEN} defaults {@code CDEMO-TO-PROGRAM} to {@code 'COSGN00C'} at
     * {@code app/cbl/COMEN01C.cbl:173} when it is {@code LOW-VALUES} or spaces, then transfers with
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at lines 175-177. It is exposed as a constant so the
     * service naming this route, and any test asserting it, reference one spelling.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    // =================================================================================================
    // Field widths. Every one is the declared width of an xxxO PICTURE in app/cpy-bms/COMEN01.CPY, cited
    // with its line number, so each constant is checkable against the copybook in one step. No payload
    // width is ever written as a bare integer literal anywhere in this file.
    // =================================================================================================

    /** Width of {@link #trnName()}: {@code TRNNAMEO PIC X(4)}, CPY line 146. */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@link #title01()} and {@link #title02()}: {@code TITLE01O PIC X(40)} at CPY line 152
     * and {@code TITLE02O PIC X(40)} at CPY line 170. One constant because both titles are 40, which
     * also matches the {@code CCDA-TITLE01} and {@code CCDA-TITLE02} {@code PIC X(40)} items in
     * {@code app/cpy/COTTL01Y.cpy} that supply them.
     */
    public static final int TITLE_LENGTH = 40;

    /** Width of {@link #curDate()}: {@code CURDATEO PIC X(8)}, CPY line 158. */
    public static final int CUR_DATE_LENGTH = 8;

    /** Width of {@link #pgmName()}: {@code PGMNAMEO PIC X(8)}, CPY line 164. */
    public static final int PGM_NAME_LENGTH = 8;

    /** Width of {@link #curTime()}: {@code CURTIMEO PIC X(8)}, CPY line 176. */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of each option line: {@code OPTN001O} through {@code OPTN012O}, every one
     * {@code PIC X(40)}, at CPY lines 182, 188, 194, 200, 206, 212, 218, 224, 230, 236, 242 and 248.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * How many option lines the screen has: twelve. This is the number of {@code OPTN} fields declared
     * in {@code app/bms/COMEN01.bms} at {@code POS=(6,20)} through {@code POS=(17,20)}, and it matches
     * {@code CDEMO-MENU-OPT OCCURS 12 TIMES} at {@code app/cpy/COMEN02Y.cpy:88}.
     *
     * <p>Not to be confused with {@link #ACTIVE_OPTION_LINE_COUNT}. This is the number of slots that
     * exist; that is the number the program fills.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * Width of {@link #option()}: {@code OPTIONO PIC X(2)}, CPY line 254. Two characters, because the
     * sending {@code WS-OPTION PIC 9(02)} renders zero-filled.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * Width of {@link #errMsg()}: {@code ERRMSGO PIC X(78)}, CPY line 260.
     *
     * <p>Seventy-eight, and the two neighbouring widths it is easy to confuse it with are both wrong.
     * The composing field is {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COMEN01C.cbl:38}, so
     * {@code MOVE WS-MESSAGE TO ERRMSGO} at line 187 truncates two bytes off the right - genuine
     * fixed-width work, and the controller's to perform, not this record's. And
     * {@code CCDA-MSG-INVALID-KEY} in {@code app/cpy/CSMSG01Y.cpy} is {@code PIC X(50)}, one of the
     * four texts that can land here, sitting inside that 80 rather than defining it.
     *
     * <p>The four texts, all composed into {@code WS-MESSAGE} by the service before arriving:
     * {@code 'Please enter a valid option number...'} (line 131),
     * {@code 'No access - Admin Only option... '} (line 140), the coming-soon text built at lines
     * 159-163, and {@code CCDA-MSG-INVALID-KEY} (moved at line 101).
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Width of {@link #nextProgram()}, taken from {@code CDEMO-TO-PROGRAM PIC X(08)} in
     * {@code app/cpy/COCOM01Y.cpy} - the very field {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} reads at
     * {@code app/cbl/COMEN01C.cbl:176}. Sourced from the communication area's own constant so the two
     * cannot drift apart.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@link #nextMapset()}, taken from {@code CDEMO-LAST-MAPSET PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:44}. Seven, not eight - a distinction worth stating because every
     * other name-like field in that copybook is eight.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Width of {@link #nextMap()}, taken from {@code CDEMO-LAST-MAP PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:43}. Seven, for the same reason as {@link #NEXT_MAPSET_LENGTH}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    // =================================================================================================
    // Symbolic map geometry. Documentary constants that let a test reconcile the field list against the
    // copybook arithmetically rather than by eye. If a field were dropped, renamed or mis-sized, the
    // reconciliation below would stop adding up.
    // =================================================================================================

    /**
     * How many payload fields this screen has: twenty. Simultaneously the number of {@code xxxO} items
     * in the {@code COMEN1AO} group of {@code app/cpy-bms/COMEN01.CPY} and the number of
     * <em>name-labelled</em> {@code DFHMDF} definitions in {@code app/bms/COMEN01.bms}, out of
     * {@value #MAPSET_FIELD_DEFINITION_COUNT} definitions in total.
     */
    public static final int SYMBOLIC_MAP_FIELD_COUNT = 20;

    /**
     * How many {@code DFHMDF} definitions {@code app/bms/COMEN01.bms} contains: twenty-eight. The
     * eight beyond {@value #SYMBOLIC_MAP_FIELD_COUNT} carry no name label, so BMS generates no
     * symbolic-map items for them and they are not payload. They are listed in this type's
     * class documentation.
     */
    public static final int MAPSET_FIELD_DEFINITION_COUNT = 28;

    /**
     * The leading {@code FILLER PIC X(12)} of the {@code COMEN1AO} group at
     * {@code app/cpy-bms/COMEN01.CPY:140}. This is the {@code TIOAPFX=YES} prefix declared by
     * {@code DFHMSD} at {@code app/bms/COMEN01.bms:24}, and it is part of the image even though it is
     * never a payload field.
     */
    public static final int TIOAPFX_FILLER_LENGTH = 12;

    /**
     * The per-field attribute prefix that precedes every {@code xxxO} item: {@code FILLER PICTURE X(3)}
     * plus the colour, programmed-symbol, highlight and validation bytes {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV}. Seven bytes, exactly matching the {@code AI} group's
     * {@code xxxL} (2) plus {@code xxxF} (1) plus {@code FILLER PICTURE X(4)} - which is what makes
     * {@code COMEN1AO REDEFINES COMEN1AI} valid.
     */
    public static final int ATTRIBUTE_PREFIX_LENGTH = 7;

    /**
     * The sum of the twenty {@code xxxO} widths: {@code 4 + 40 + 8 + 8 + 40 + 8 + (12 * 40) + 2 + 78},
     * which is {@value #PAYLOAD_BYTES}.
     */
    public static final int PAYLOAD_BYTES = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + (OPTION_LINE_COUNT * OPTION_LINE_LENGTH)
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * The whole {@code COMEN1AO} image: {@code 12 + 20 * 7 + 668}, which is
     * {@value #SYMBOLIC_MAP_LENGTH} bytes. Recorded for reconciliation only - this type renders no
     * fixed-width image, and if one is ever needed it belongs behind the shared codec so that
     * {@code FILLER} spans are emitted rather than skipped.
     */
    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_FILLER_LENGTH
            + (SYMBOLIC_MAP_FIELD_COUNT * ATTRIBUTE_PREFIX_LENGTH)
            + PAYLOAD_BYTES;

    /**
     * How many option lines the program actually fills: ten, from
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21}. It is the
     * bound of the {@code BUILD-MENU-OPTIONS} loop at {@code app/cbl/COMEN01C.cbl:238-239}, which is
     * why slots 11 and 12 are never reached.
     *
     * <p>Recorded so the gap between {@value #OPTION_LINE_COUNT} declared slots and
     * {@value #ACTIVE_OPTION_LINE_COUNT} filled ones is visible in the code rather than discovered
     * later. It is documentation, not a limit: nothing in this type refuses to carry slots 11 and 12.
     */
    public static final int ACTIVE_OPTION_LINE_COUNT = 10;

    /**
     * The lowest option-line slot number, and the reason it is stated at all: COBOL {@code OCCURS}
     * tables are <strong>1-based</strong> while Java arrays and lists are 0-based, which makes an
     * off-by-one the most likely defect anywhere near this table. {@link #optionLine(int)} takes the
     * COBOL numbering so that a slot number read from the copybook can be used unchanged.
     */
    public static final int FIRST_OPTION_LINE_SLOT = 1;

    /**
     * The highest option-line slot number, {@value #OPTION_LINE_COUNT}, inclusive. Slots
     * {@value #FIRST_OPTION_LINE_SLOT} through here are all addressable, including the two the program
     * never reaches.
     */
    public static final int LAST_OPTION_LINE_SLOT = OPTION_LINE_COUNT;

    // =================================================================================================
    // Construction. The canonical constructor above is deliberately transparent: it stores all
    // twenty-six components exactly as given, with no padding, no truncation, no trimming and no
    // null-to-default rewriting. Anything else would either be the fixed-width work this type must not
    // do, or a silent transformation that would make a JSON round-trip lossy.
    //
    // The screen's declared defaults - the message colour the mapset asks for, and this screen's own
    // mapset and map - are therefore applied by the two entry points below rather than hidden inside
    // the constructor, so a caller can always see where a default came from.
    // =================================================================================================

    /**
     * A freshly initialised response carrying this screen's declared defaults and nothing else.
     *
     * <p>Specifically: {@link #errMsgColor()} is {@code DFHRED}, because {@code ERRMSG} is declared
     * {@code COLOR=RED} at {@code app/bms/COMEN01.bms:154-157}; {@link #nextMapset()} is
     * {@value #MAPSET_NAME} and {@link #nextMap()} is {@value #MAP_NAME}, this screen's own targets;
     * {@link #navigationContext()} is an initial 160-byte communication area; and
     * {@link #resetAllOutputFields()} is {@code true}, mirroring
     * {@code MOVE LOW-VALUES TO COMEN1AO} at {@code app/cbl/COMEN01C.cbl:89} - the repaint the program
     * performs on first entry, before {@code SEND-MENU-SCREEN} populates anything.
     *
     * <p>The twenty screen-text members carry the unpainted image at their declared widths, which is
     * the honest representation of a map area that has been cleared and not yet written to - {@code
     * X'00'}, the byte line 89 moves, and not spaces. {@code POPULATE-HEADER-INFO} and
     * {@code BUILD-MENU-OPTIONS} fill them, and both live in the service.
     *
     * @return the first-entry response, never {@code null}
     */
    public static MainMenuResponse initial() {
        return builder().resetAllOutputFields(true).build();
    }

    /**
     * A builder seeded with this screen's declared defaults: {@code DFHRED} for the message colour,
     * {@value #MAPSET_NAME} and {@value #MAP_NAME} for the navigation targets, and an initial
     * communication area.
     *
     * <p>Preferred over the twenty-six-argument canonical constructor for readability: a positional
     * call listing eighteen consecutive {@code String}s, twelve of which are interchangeable option
     * lines, is easy to get subtly wrong and hard to review.
     *
     * @return a new builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder pre-loaded with every component of this response, for deriving a modified copy.
     *
     * <p>This is how the {@code with...} methods below are implemented, and it is available directly
     * for the cases where several components change at once.
     *
     * @return a new builder holding this response's values, never {@code null}
     */
    public Builder toBuilder() {
        Builder builder = new Builder()
                .trnName(trnName)
                .title01(title01)
                .curDate(curDate)
                .pgmName(pgmName)
                .title02(title02)
                .curTime(curTime)
                .option(option)
                .errMsg(errMsg)
                .navigationContext(navigationContext)
                .nextProgram(nextProgram)
                .nextMapset(nextMapset)
                .nextMap(nextMap)
                .errMsgColor(errMsgColor)
                .resetAllOutputFields(resetAllOutputFields);
        for (int slot = FIRST_OPTION_LINE_SLOT; slot <= LAST_OPTION_LINE_SLOT; slot++) {
            builder.optionLine(slot, optionLine(slot));
        }
        return builder;
    }

    // =================================================================================================
    // Reading the option lines. The twelve members are individually named components, exactly as the
    // mapset names them; these two accessors are convenience views over them and are excluded from JSON
    // so the serialised form stays precisely the twenty-six declared members.
    // =================================================================================================

    /**
     * The twelve option lines in slot order, slot {@value #FIRST_OPTION_LINE_SLOT} first, as an
     * unmodifiable list.
     *
     * <p>The list is always {@value #OPTION_LINE_COUNT} long - never shortened to
     * {@value #ACTIVE_OPTION_LINE_COUNT} - and its elements may be {@code null} where the program has
     * written nothing, which is always the case for the last two. Every mutating operation, including
     * {@code set}, throws {@link UnsupportedOperationException}, so a caller cannot reach back into
     * this response through the returned view.
     *
     * <p>Note the index shift: this list is 0-based like any Java list, whereas the COBOL table is
     * 1-based. Use {@link #optionLine(int)} to stay in the copybook's numbering.
     *
     * @return an unmodifiable, {@value #OPTION_LINE_COUNT}-element view, never {@code null}
     */
    @JsonIgnore
    public List<String> optionLines() {
        // Arrays.asList rather than List.of: List.of rejects null elements, and a null option line is
        // the normal state of slots 11 and 12. Collections.unmodifiableList then also blocks the
        // fixed-size list's set(int, E), which Arrays.asList would otherwise allow.
        return Collections.unmodifiableList(Arrays.asList(optn001,
                optn002,
                optn003,
                optn004,
                optn005,
                optn006,
                optn007,
                optn008,
                optn009,
                optn010,
                optn011,
                optn012));
    }

    /**
     * One option line, addressed by its <strong>1-based</strong> COBOL slot number.
     *
     * <p>The numbering deliberately matches {@code CDEMO-MENU-OPT(WS-IDX)} in
     * {@code app/cpy/COMEN02Y.cpy} and the {@code EVALUATE WS-IDX} arms of {@code BUILD-MENU-OPTIONS},
     * so a slot number taken from the COBOL is used here unchanged. Slot 1 is {@link #optn001()} and
     * slot {@value #OPTION_LINE_COUNT} is {@link #optn012()}.
     *
     * <p>Slots {@value #ACTIVE_OPTION_LINE_COUNT} + 1 and above are valid arguments and return
     * {@code null} in practice, because the program never writes them. They are addressable on
     * purpose; an out-of-range slot is a programming error and is rejected rather than silently
     * clamped, which is what would hide an off-by-one.
     *
     * @param slot the 1-based slot number, from {@value #FIRST_OPTION_LINE_SLOT} to
     *             {@value #OPTION_LINE_COUNT} inclusive
     * @return the option line held in that slot, or {@code null} if nothing has been written to it
     * @throws IllegalArgumentException if {@code slot} is outside
     *                                  {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    @JsonIgnore
    public String optionLine(int slot) {
        return switch (requireValidSlot(slot)) {
            case 1 -> optn001;
            case 2 -> optn002;
            case 3 -> optn003;
            case 4 -> optn004;
            case 5 -> optn005;
            case 6 -> optn006;
            case 7 -> optn007;
            case 8 -> optn008;
            case 9 -> optn009;
            case 10 -> optn010;
            case 11 -> optn011;
            // requireValidSlot has already rejected everything outside 1..12, so the only value left
            // is 12. An arm rather than a default keeps the mapping exhaustive and reviewable.
            default -> optn012;
        };
    }

    /**
     * Whether a slot is one the program can actually fill - that is, whether it is within
     * {@value #ACTIVE_OPTION_LINE_COUNT}.
     *
     * <p>This reports the {@code BUILD-MENU-OPTIONS} loop bound
     * ({@code app/cbl/COMEN01C.cbl:238-239}) and nothing more. It does not gate anything in this type:
     * slots 11 and 12 remain fully readable and writable. It exists so a caller that wants to render
     * only the populated lines can say so explicitly instead of hard-coding ten.
     *
     * @param slot the 1-based slot number, from {@value #FIRST_OPTION_LINE_SLOT} to
     *             {@value #OPTION_LINE_COUNT} inclusive
     * @return {@code true} if the COBOL loop reaches this slot
     * @throws IllegalArgumentException if {@code slot} is outside
     *                                  {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    @JsonIgnore
    public boolean isPopulatedByProgram(int slot) {
        return requireValidSlot(slot) <= ACTIVE_OPTION_LINE_COUNT;
    }

    /**
     * Rejects an option-line slot outside the declared range, returning it unchanged when valid.
     *
     * @param slot the 1-based slot number to check
     * @return {@code slot}, when it is in range
     * @throws IllegalArgumentException if {@code slot} is outside
     *                                  {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    private static int requireValidSlot(int slot) {
        if (slot < FIRST_OPTION_LINE_SLOT || slot > LAST_OPTION_LINE_SLOT) {
            throw new IllegalArgumentException("option line slot must be "
                    + FIRST_OPTION_LINE_SLOT + ".." + LAST_OPTION_LINE_SLOT + " (1-based, as the COBOL "
                    + "OCCURS table numbers them) but was " + slot);
        }
        return slot;
    }

    // =================================================================================================
    // Derived copies. Each of these corresponds to one COBOL MOVE or XCTL the service performs, and each
    // returns a new instance - this record is immutable, so nothing is ever updated in place. Naming
    // them after the statements they stand in for keeps the correspondence traceable during review.
    // =================================================================================================

    /**
     * A copy carrying a different message line, standing in for
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO} at {@code app/cbl/COMEN01C.cbl:187}.
     *
     * <p>The value is stored as supplied. The {@code PIC X(80)} to {@code PIC X(78)} narrowing that
     * {@code MOVE} performs is fixed-width work and stays with the controller and its codec; this
     * method neither truncates nor pads.
     *
     * @param newErrMsg the message text, at most {@value #ERR_MSG_LENGTH} characters
     * @return a new response differing only in {@link #errMsg()}, never {@code null}
     */
    public MainMenuResponse withErrMsg(String newErrMsg) {
        return toBuilder().errMsg(newErrMsg).build();
    }

    /**
     * A copy carrying a different message colour, standing in for
     * {@code MOVE DFHGREEN TO ERRMSGC OF COMEN1AO} at {@code app/cbl/COMEN01C.cbl:158}.
     *
     * <p>That single line is the only colour override on this screen: the mapset declares
     * {@code ERRMSG ... COLOR=RED}, and the program switches it to green on the coming-soon path where
     * the text is informational rather than an error. Which path applies is the service's decision, so
     * this method makes the override expressible without making it here.
     *
     * @param newErrMsgColor the extended-colour byte, normally {@code BmsAttributes.DFHRED} or
     *                       {@code BmsAttributes.DFHGREEN}
     * @return a new response differing only in {@link #errMsgColor()}, never {@code null}
     */
    public MainMenuResponse withErrMsgColor(byte newErrMsgColor) {
        return toBuilder().errMsgColor(newErrMsgColor).build();
    }

    /**
     * A copy naming a different transfer target, standing in for both {@code EXEC CICS XCTL} sites.
     *
     * <p>Those two sites are {@code XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))} at
     * {@code app/cbl/COMEN01C.cbl:152-155}, whose target is one of the ten programs in
     * {@code app/cpy/COMEN02Y.cpy} - {@code COACTVWC}, {@code COACTUPC}, {@code COCRDLIC},
     * {@code COCRDSLC}, {@code COCRDUPC}, {@code COTRN00C}, {@code COTRN01C}, {@code COTRN02C},
     * {@code CORPT00C}, {@code COBIL00C} - and {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at lines
     * 175-177, whose target is {@value #SIGNON_PROGRAM}. Any of the eleven is a valid argument; the
     * choice belongs to the service.
     *
     * @param newNextProgram the program the client should call next, at most
     *                       {@value #NEXT_PROGRAM_LENGTH} characters
     * @return a new response differing only in {@link #nextProgram()}, never {@code null}
     */
    public MainMenuResponse withNextProgram(String newNextProgram) {
        return toBuilder().nextProgram(newNextProgram).build();
    }

    /**
     * A copy carrying a different communication area.
     *
     * <p>The supplied context is stored by reference and returned by {@link #navigationContext()}
     * unchanged - no field of it is defaulted, normalised or rewritten. That matters most for
     * {@code CDEMO-USER-TYPE}: {@code COMEN01C} only ever <em>reads</em> the user type, since both
     * assignments to it are commented out at {@code app/cbl/COMEN01C.cbl:149-150}, so whatever arrives
     * must be echoed back byte-identically.
     *
     * @param newNavigationContext the communication area to echo
     * @return a new response differing only in {@link #navigationContext()}, never {@code null}
     */
    public MainMenuResponse withNavigationContext(NavigationContext newNavigationContext) {
        return toBuilder().navigationContext(newNavigationContext).build();
    }

    /**
     * A copy with one option line replaced, standing in for one {@code EVALUATE WS-IDX} arm of
     * {@code BUILD-MENU-OPTIONS} ({@code app/cbl/COMEN01C.cbl:248-275}).
     *
     * @param slot the 1-based slot number, from {@value #FIRST_OPTION_LINE_SLOT} to
     *             {@value #OPTION_LINE_COUNT} inclusive
     * @param text the option line, at most {@value #OPTION_LINE_LENGTH} characters
     * @return a new response differing only in that option line, never {@code null}
     * @throws IllegalArgumentException if {@code slot} is outside
     *                                  {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    public MainMenuResponse withOptionLine(int slot, String text) {
        return toBuilder().optionLine(slot, text).build();
    }

    // =================================================================================================
    // The builder.
    // =================================================================================================

    /**
     * A mutable builder for {@link MainMenuResponse}.
     *
     * <p>Every field here is an instance field of a per-use builder object. Nothing is static and
     * mutable: {@code WORKING-STORAGE} does not become shared state, because that would break request
     * isolation between concurrent callers and make test outcomes depend on execution order. The
     * builder itself is not thread-safe and is not meant to be shared; the {@link MainMenuResponse} it
     * produces is immutable and freely shareable.
     *
     * <p>Setters store what they are given. None pads, trims, truncates or coerces, for the reasons
     * set out on the enclosing type.
     */
    public static final class Builder {

        /**
         * The twelve option lines, held 0-indexed internally while every public entry point speaks the
         * COBOL's 1-based slot numbers. Converting in exactly one place - {@link #optionLine(int,
         * String)} - is what keeps the 1-based-to-0-based shift from being repeated twelve times.
         */
        // Pre-filled with the unpainted image rather than left as a null-filled array, for the same
        // reason the eight scalar screen members are: OPTN005O through OPTN012O are declared in the map
        // and cleared by MOVE LOW-VALUES TO COMEN1AO even though COMEN01C never writes them, so they
        // are addressable and carry X'00' - not absent, and not spaces. See ScreenFieldImage.
        private final String[] optionLines = newUnpaintedOptionLines();

        // The plain pass-through carriers. Each is a direct stand-in for its record component and
        // starts out unset, which represents a map field the program has not written to yet; the map
        // field, PICTURE and originating COBOL line for every one of them are documented on the
        // matching setter below and on the enclosing record's component list. The fields that are NOT
        // plain - the ones carrying a screen-declared default or an index conversion - are documented
        // individually, because for those the default is the thing worth explaining.
        // The eight screen members default to the unpainted image at their declared width rather than
        // to null: MOVE LOW-VALUES TO COMEN1AO (app/cbl/COMEN01C.cbl:89) is what clears this map, and a
        // fixed-width screen field always has a width and therefore always has an image. A null here
        // would serialise as JSON null, which says "there is no such field" - never true of a DFHMDF
        // definition. See ScreenFieldImage.
        private String trnName = ScreenFieldImage.unpainted(TRN_NAME_LENGTH);
        private String title01 = ScreenFieldImage.unpainted(TITLE_LENGTH);
        private String curDate = ScreenFieldImage.unpainted(CUR_DATE_LENGTH);
        private String pgmName = ScreenFieldImage.unpainted(PGM_NAME_LENGTH);
        private String title02 = ScreenFieldImage.unpainted(TITLE_LENGTH);
        private String curTime = ScreenFieldImage.unpainted(CUR_TIME_LENGTH);
        private String option = ScreenFieldImage.unpainted(OPTION_LENGTH);
        private String errMsg = ScreenFieldImage.unpainted(ERR_MSG_LENGTH);

        /** Defaults to an initial 160-byte communication area rather than {@code null}. */
        private NavigationContext navigationContext = NavigationContext.empty();

        private String nextProgram;

        /** Defaults to this screen's own mapset, {@value MainMenuResponse#MAPSET_NAME}. */
        private String nextMapset = MAPSET_NAME;

        /** Defaults to this screen's own map, {@value MainMenuResponse#MAP_NAME}. */
        private String nextMap = MAP_NAME;

        /**
         * Defaults to {@code DFHRED}, the colour {@code ERRMSG} is declared with at
         * {@code app/bms/COMEN01.bms:154-157}.
         *
         * <p>The default is set here rather than relying on the {@code byte} zero value, because
         * {@code DFHDFCOL} - the terminal's default colour - is itself {@code 0x00}, so an unset
         * {@code byte} would silently mean "default colour" instead of "red".
         */
        private byte errMsgColor = BmsAttributes.DFHRED;

        private boolean resetAllOutputFields;

        private Builder() {
            // Instantiated only through MainMenuResponse.builder() and toBuilder().
        }

        /**
         * Sets {@code TRNNAMEO PIC X(4)}, CPY line 146.
         *
         * @param value the transaction identifier, normally {@value MainMenuResponse#TRANSACTION_ID}
         * @return this builder
         */
        public Builder trnName(String value) {
            this.trnName = value;
            return this;
        }

        /**
         * Sets {@code TITLE01O PIC X(40)}, CPY line 152.
         *
         * @param value the first title line, from {@code CCDA-TITLE01}
         * @return this builder
         */
        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets {@code CURDATEO PIC X(8)}, CPY line 158.
         *
         * @param value the current date rendered {@code MM/DD/YY}
         * @return this builder
         */
        public Builder curDate(String value) {
            this.curDate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEO PIC X(8)}, CPY line 164.
         *
         * @param value the program name, normally {@value MainMenuResponse#PROGRAM_NAME}
         * @return this builder
         */
        public Builder pgmName(String value) {
            this.pgmName = value;
            return this;
        }

        /**
         * Sets {@code TITLE02O PIC X(40)}, CPY line 170.
         *
         * @param value the second title line, from {@code CCDA-TITLE02}
         * @return this builder
         */
        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEO PIC X(8)}, CPY line 176.
         *
         * @param value the current time rendered {@code HH:MM:SS}
         * @return this builder
         */
        public Builder curTime(String value) {
            this.curTime = value;
            return this;
        }

        /**
         * Sets one option line by its <strong>1-based</strong> COBOL slot number.
         *
         * <p>All {@value MainMenuResponse#OPTION_LINE_COUNT} slots are writable, including 11 and 12
         * which the program itself never reaches. Refusing them would make this type narrower than the
         * mapset it projects.
         *
         * @param slot  the 1-based slot number, {@value MainMenuResponse#FIRST_OPTION_LINE_SLOT} to
         *              {@value MainMenuResponse#OPTION_LINE_COUNT} inclusive
         * @param value the option line text
         * @return this builder
         * @throws IllegalArgumentException if {@code slot} is out of range
         */
        public Builder optionLine(int slot, String value) {
            this.optionLines[requireValidSlot(slot) - FIRST_OPTION_LINE_SLOT] = value;
            return this;
        }

        /**
         * Sets {@code OPTIONO PIC X(2)}, CPY line 254.
         *
         * @param value the selected option, zero-filled to two characters, for example {@code "01"} or
         *              {@code "10"}
         * @return this builder
         */
        public Builder option(String value) {
            this.option = value;
            return this;
        }

        /**
         * Sets {@code ERRMSGO PIC X(78)}, CPY line 260.
         *
         * @param value the message text, stored exactly as given
         * @return this builder
         */
        public Builder errMsg(String value) {
            this.errMsg = value;
            return this;
        }

        /**
         * Sets the communication area to echo, stored by reference and never altered.
         *
         * @param value the communication area; {@code null} restores an initial 160-byte area, since a
         *              response with no conversation state at all would not be a faithful stand-in for
         *              a CICS {@code COMMAREA}
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value == null ? NavigationContext.empty() : value;
            return this;
        }

        /**
         * Sets the {@code XCTL} target the client should call next.
         *
         * @param value the program name, at most {@value MainMenuResponse#NEXT_PROGRAM_LENGTH}
         *              characters
         * @return this builder
         */
        public Builder nextProgram(String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the mapset owning the next map.
         *
         * @param value the mapset name, at most {@value MainMenuResponse#NEXT_MAPSET_LENGTH} characters
         * @return this builder
         */
        public Builder nextMapset(String value) {
            this.nextMapset = value;
            return this;
        }

        /**
         * Sets the next map to render.
         *
         * @param value the map name, at most {@value MainMenuResponse#NEXT_MAP_LENGTH} characters
         * @return this builder
         */
        public Builder nextMap(String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the message line's extended-colour byte.
         *
         * @param value the colour byte, normally {@code BmsAttributes.DFHRED} or
         *              {@code BmsAttributes.DFHGREEN}
         * @return this builder
         */
        public Builder errMsgColor(byte value) {
            this.errMsgColor = value;
            return this;
        }

        /**
         * Sets whether the client should clear every output field before painting.
         *
         * @param value {@code true} to mirror {@code MOVE LOW-VALUES TO COMEN1AO}
         *              ({@code app/cbl/COMEN01C.cbl:89})
         * @return this builder
         */
        public Builder resetAllOutputFields(boolean value) {
            this.resetAllOutputFields = value;
            return this;
        }

        /**
         * Builds the response.
         *
         * <p>The option lines are passed positionally in slot order, so the twelve components are
         * populated from the twelve array entries with no scope for a transposition.
         *
         * @return a new immutable response, never {@code null}
         */
        public MainMenuResponse build() {
            return new MainMenuResponse(trnName,
                    title01,
                    curDate,
                    pgmName,
                    title02,
                    curTime,
                    optionLines[0],
                    optionLines[1],
                    optionLines[2],
                    optionLines[3],
                    optionLines[4],
                    optionLines[5],
                    optionLines[6],
                    optionLines[7],
                    optionLines[8],
                    optionLines[9],
                    optionLines[10],
                    optionLines[11],
                    option,
                    errMsg,
                    navigationContext,
                    nextProgram,
                    nextMapset,
                    nextMap,
                    errMsgColor,
                    resetAllOutputFields);
        }

        /**
         * The twelve {@code OPTN00nO} lines as {@code MOVE LOW-VALUES TO COMEN1AO}
         * ({@code app/cbl/COMEN01C.cbl:89}) leaves them: the unpainted image at
         * {@value MainMenuResponse#OPTION_LINE_LENGTH} characters each.
         *
         * @return a new array of {@value MainMenuResponse#OPTION_LINE_COUNT} unpainted lines
         */
        private static String[] newUnpaintedOptionLines() {
            String[] lines = new String[OPTION_LINE_COUNT];
            Arrays.fill(lines, ScreenFieldImage.unpainted(OPTION_LINE_LENGTH));
            return lines;
        }
    }
}
