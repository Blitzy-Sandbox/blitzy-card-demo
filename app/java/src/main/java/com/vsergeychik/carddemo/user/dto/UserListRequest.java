package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inbound payload of {@code GET /api/users} - CICS transaction {@code CU00}, program
 * {@code app/cbl/COUSR00C.cbl}, mapset {@code COUSR00}, map {@code COUSR0A}.
 *
 * <p>It is a field-for-field projection of the {@code xxxI} items of {@code 01 COUSR0AI} in
 * {@code app/cpy-bms/COUSR00.CPY}, carrying <strong>exactly {@value #MAP_FIELD_COUNT}</strong>
 * screen fields, plus the {@code CU00} paging context and the pseudo-conversational state that
 * replaces the CICS communication area. This type holds <em>no logic</em>: no paging arithmetic, no
 * file browse and no program routing. It is a payload contract.
 *
 * <h2>Why {@code UserList} and not {@code UserMenu}</h2>
 *
 * The migration plan mandates the controller name {@code UserMenuController}, but the source is a
 * paginated <strong>list</strong>, not a menu. Three independent artefacts agree:
 * {@code app/bms/COUSR00.bms} line 2 is captioned {@code CardDemo - List Users}; the screen's own
 * heading literal at lines 75-79 is {@code 'List Users'}; and {@code README.md} lines 213-231
 * document {@code CU00} as the user list. The governing rule is "names from the prompt, behaviour
 * from the source", which is why the mandated controller name is honoured verbatim while these data
 * types are named for what the screen actually does.
 *
 * <h2>The {@value #MAP_FIELD_COUNT} screen fields, and how that number was established</h2>
 *
 * Three measurements were taken independently and all three agree, so any deviation in this class
 * is an error in this class:
 *
 * <ul>
 *   <li>{@code 01 COUSR0AI} of {@code app/cpy-bms/COUSR00.CPY} (lines 17-372) declares
 *       {@value #MAP_FIELD_COUNT} {@code xxxI} items.</li>
 *   <li>{@code 01 COUSR0AO REDEFINES COUSR0AI} (lines 373-728) declares {@value #MAP_FIELD_COUNT}
 *       {@code xxxO} items at the identical stride.</li>
 *   <li>{@code app/bms/COUSR00.bms} contains 89 {@code DFHMDF} definitions of which exactly
 *       {@value #MAP_FIELD_COUNT} are name-labelled. The other 30 are literal {@code INITIAL}
 *       furniture - {@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'},
 *       {@code 'List Users'}, {@code 'Page:'}, {@code 'Search User ID:'}, the column headings and
 *       the function-key legend - and they are <strong>not</strong> fields. A label-by-label
 *       comparison of the two lists is identical in content and in order.</li>
 * </ul>
 *
 * Every {@code LENGTH=} in the mapset equals its counterpart {@code xxxI PIC X(n)} in the symbolic
 * map, with no exceptions, so the width of each member below is over-determined by two sources.
 *
 * <table border="1">
 *   <caption>{@value #HEADER_FIELD_COUNT} + {@value #ROW_COUNT} x {@value #ROW_FIELD_COUNT} +
 *            {@value #TRAILER_FIELD_COUNT} = {@value #MAP_FIELD_COUNT}</caption>
 *   <tr><th>Block</th><th>Map fields</th><th>Widths</th><th>Count</th></tr>
 *   <tr><td>Header and paging</td>
 *       <td>{@code TRNNAME} {@code TITLE01} {@code CURDATE} {@code PGMNAME} {@code TITLE02}
 *           {@code CURTIME} {@code PAGENUM} {@code USRIDIN}</td>
 *       <td>4, 40, 8, 8, 40, 8, 8, 8</td>
 *       <td>{@value #HEADER_FIELD_COUNT}</td></tr>
 *   <tr><td>Ten repeating rows</td>
 *       <td>{@code SEL0001}..{@code SEL0010}, {@code USRID01}..{@code USRID10},
 *           {@code FNAME01}..{@code FNAME10}, {@code LNAME01}..{@code LNAME10},
 *           {@code UTYPE01}..{@code UTYPE10}</td>
 *       <td>1, 8, 20, 20, 1 per row</td>
 *       <td>{@value #ROW_COUNT} x {@value #ROW_FIELD_COUNT} = 50</td></tr>
 *   <tr><td>Trailer</td><td>{@code ERRMSG}</td><td>78</td>
 *       <td>{@value #TRAILER_FIELD_COUNT}</td></tr>
 * </table>
 *
 * {@link #mapFields()} materialises all {@value #MAP_FIELD_COUNT} of them as an ordered map keyed by
 * the map label, and {@link #mapFieldNames()} returns the label list on its own, so the count, the
 * order and every label spelling are assertable programmatically rather than by eye.
 *
 * <h2>Three width traps, each preserved exactly as declared</h2>
 *
 * <ul>
 *   <li><strong>{@code CURTIME} is {@value #CURTIME_LENGTH}, not 9.</strong>
 *       {@code app/bms/COUSR00.bms} lines 70-74 declare {@code LENGTH=8} with
 *       {@code INITIAL='hh:mm:ss'}, and the symbolic map agrees. Only the sign-on screen's
 *       {@code CURTIME} is nine characters wide; harmonising the two would be wrong in one place
 *       whichever value were chosen.</li>
 *   <li><strong>{@code ERRMSG} is {@value #ERRMSG_LENGTH}, not 80.</strong> The screen field is
 *       {@code PIC X(78)} while {@code app/cbl/COUSR00C.cbl} line 38 declares
 *       {@code WS-MESSAGE PIC X(80)}, and line 526 moves the wider field into the narrower one:
 *       {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR0AO}. That is a two-character right-hand
 *       truncation performed by a COBOL alphanumeric {@code MOVE}. This type declares
 *       {@value #ERRMSG_LENGTH} and truncates <strong>nowhere</strong>: the controller performs the
 *       move through {@code common.FixedWidthCodec.movePicX}, which is the single place in the module
 *       where the direction of a truncation is decided.</li>
 *   <li><strong>The row label spellings are asymmetric and stay that way.</strong> The selection
 *       column is {@code SEL0001}..{@code SEL0010} - {@value #SEL_FIELD_DIGITS} digits, zero-padded
 *       - while the other four are {@code USRID01}..{@code UTYPE10} with
 *       {@value #ROW_FIELD_DIGITS}. Verified in both {@code app/bms/COUSR00.bms} and
 *       {@code app/cpy-bms/COUSR00.CPY}. It is never {@code SEL01}. Regularising the spelling would
 *       rename a screen field, and a renamed field cannot be diffed against its COBOL counterpart.
 *       {@link #selFieldName(int)} and {@link #usrIdFieldName(int)} render the two forms.</li>
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 *
 * <ul>
 *   <li><strong>No {@code xxxL}, {@code xxxF}, {@code xxxA}, {@code xxxC}, {@code xxxP},
 *       {@code xxxH} or {@code xxxV} member.</strong> The symbolic map carries one of each per
 *       field, so those are more than 400 items, and none of them is payload. {@code xxxL} is the
 *       length CICS reports for an input field and doubles as the cursor signal - {@code COUSR00C}
 *       writes {@code MOVE -1 TO USRIDINL OF COUSR0AI} at lines 108, 214, 224, 246 and 268 purely to
 *       place the cursor; {@code xxxF} and its {@code xxxA} redefinition are the attribute byte; and
 *       {@code xxxC} is the colour byte. They are presentation metadata, not data, and the field
 *       highlighting they drive belongs to {@code common.FieldAttributeSetter}.</li>
 *   <li><strong>No {@code FILLER}.</strong> {@code 02 FILLER PIC X(12)} at line 18 is the
 *       {@code TIOAPFX=YES} prefix and the {@code 02 FILLER PICTURE X(4)} before every
 *       {@code xxxI} is reserved padding. Both are storage, not fields.</li>
 *   <li><strong>No model of {@code WS-USER-DATA}.</strong> {@code app/cbl/COUSR00C.cbl} lines 56-63
 *       declare {@code USER-REC OCCURS 10 TIMES} with {@code USER-SEL X(01)}, {@code FILLER X(02)},
 *       {@code USER-ID X(08)}, {@code FILLER X(02)}, {@code USER-NAME X(25)}, {@code FILLER X(02)}
 *       and {@code USER-TYPE X(08)}. That is WORKING-STORAGE staging, not the map, and its widths
 *       prove it: {@code USER-NAME} is 25 where {@code FNAME}/{@code LNAME} are
 *       {@value #FNAME_LENGTH}, and {@code USER-TYPE} is 8 where {@code UTYPE} is
 *       {@value #UTYPE_LENGTH}. The payload comes from the symbolic map alone.</li>
 *   <li><strong>No page-size member.</strong> The ten rows are the screen. There is no
 *       {@code pageSize} field, no configurable limit and no settable row count: {@value #ROW_COUNT}
 *       is behaviour, fixed by the ten {@code DFHMDF} row groups and by {@code COUSR00C}'s
 *       {@code PERFORM UNTIL WS-IDX >= 11} at line 300 and {@code MOVE 10 TO WS-IDX} at line
 *       351.</li>
 *   <li><strong>No fixed-width image.</strong> Rendering the symbolic map byte-for-byte would
 *       require emitting the {@code xxxL}, {@code xxxF} and {@code FILLER} spans this type does not
 *       model. JSON is this payload's wire format; the byte-level record images belong to the
 *       copybook model types such as {@code user.model.SecUserRecord}.</li>
 * </ul>
 *
 * <h2>Conversation state travels in the payload - the one sanctioned exception</h2>
 *
 * CICS is pseudo-conversational: {@code COUSR00C} paints the screen, returns, and is re-entered from
 * the top when a key is pressed. Everything it remembers it handed back in its communication area.
 * The migration keeps that shape exactly, so the request carries the conversation state and the
 * server keeps none. Four groups of members below are therefore <em>not</em> screen fields, and each
 * is marked as such where it is declared:
 *
 * <ol>
 *   <li>{@link #navigationContext()} - {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy},
 *       160 bytes, shared by all seventeen online programs. It also supplies the enter-versus-
 *       re-enter context through {@code CDEMO-PGM-CONTEXT}, which {@code COUSR00C} tests at line 118
 *       with {@code IF NOT CDEMO-PGM-REENTER}.</li>
 *   <li>The six {@code CDEMO-CU00-INFO} members - the paging context appended to that same
 *       communication area, described in the next section.</li>
 *   <li>{@link #aid()} - the {@code EIBAID} key indication, which {@code COUSR00C} evaluates at
 *       lines 124-138.</li>
 *   <li>{@link #rows()}, whose selection column is pure user input - see below.</li>
 * </ol>
 *
 * <p>This type is consequently free of {@code HttpSession}, {@code @SessionAttributes},
 * {@code @SessionScope}, {@code ThreadLocal}, any static cache and any static "current request"
 * accessor. A static holder would be a session by another name and would break request isolation.
 * It is immutable for the same reason: every change produces a new instance through a
 * {@code withXxx} method, so a payload already handed to a collaborator cannot shift underneath it.
 *
 * <h2>{@code CDEMO-CU00-INFO} lives here, not in the shared communication area</h2>
 *
 * {@code app/cbl/COUSR00C.cbl} lines 65-75 do something unusual and load-bearing: immediately after
 * {@code COPY COCOM01Y.} the program appends a further {@code 05} group <em>under the same
 * {@code 01 CARDDEMO-COMMAREA}</em>, verbatim:
 *
 * <pre>
 * COPY COCOM01Y.
 *    05 CDEMO-CU00-INFO.
 *       10 CDEMO-CU00-USRID-FIRST     PIC X(08).
 *       10 CDEMO-CU00-USRID-LAST      PIC X(08).
 *       10 CDEMO-CU00-PAGE-NUM        PIC 9(08).
 *       10 CDEMO-CU00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
 *          88 NEXT-PAGE-YES                     VALUE 'Y'.
 *          88 NEXT-PAGE-NO                      VALUE 'N'.
 *       10 CDEMO-CU00-USR-SEL-FLG     PIC X(01).
 *       10 CDEMO-CU00-USR-SELECTED    PIC X(08).
 * </pre>
 *
 * 8 + 8 + 8 + 1 + 1 + 8 = {@value #CU00_INFO_LENGTH} bytes, so <strong>the {@code CU00}
 * communication area is {@value #CU00_COMMAREA_LENGTH} bytes, not 160.</strong>
 *
 * <p>Those six items belong to this screen and only to this screen, which is why they are declared
 * here. {@code common.NavigationContext} is <strong>referenced</strong> as a member and is never
 * widened: it is exactly 160 bytes and is shared by all seventeen controllers, so adding a field to
 * it would change every other screen's byte image. That class enforces its own total, so the mistake
 * could not even be made quietly - but it must not be attempted at all.
 *
 * @param trnName          {@code TRNNAMEI PIC X(4)}: the transaction identifier, {@value #TRANID}
 * @param title01          {@code TITLE01I PIC X(40)}: the first title line
 * @param curDate          {@code CURDATEI PIC X(8)}: the current date, {@code mm/dd/yy}
 * @param pgmName          {@code PGMNAMEI PIC X(8)}: the program name, {@value #PROGRAM}
 * @param title02          {@code TITLE02I PIC X(40)}: the second title line
 * @param curTime          {@code CURTIMEI PIC X(8)}: the current time, {@code hh:mm:ss}
 * @param pageNum          {@code PAGENUMI PIC X(8)}: the <em>displayed</em> page number, a character
 *                         field, distinct from the numeric {@code cu00PageNum}
 * @param usrIdIn          {@code USRIDINI PIC X(8)}: the browse-start user id; blank means "start at
 *                         the beginning of the file"
 * @param rows             the {@value #ROW_COUNT} screen rows, always exactly that many
 * @param errMsg           {@code ERRMSGI PIC X(78)}: the message line
 * @param cu00UsrIdFirst   {@code CDEMO-CU00-USRID-FIRST PIC X(08)}: the first user id on the page
 * @param cu00UsrIdLast    {@code CDEMO-CU00-USRID-LAST PIC X(08)}: the last user id on the page
 * @param cu00PageNum      {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}: the numeric page number, unsigned
 * @param cu00NextPageFlg  {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}: {@value #NEXT_PAGE_YES} or
 *                         {@value #NEXT_PAGE_NO}, defaulting to {@value #NEXT_PAGE_NO}
 * @param cu00UsrSelFlg    {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}: the captured row action
 * @param cu00UsrSelected  {@code CDEMO-CU00-USR-SELECTED PIC X(08)}: the captured row's user id
 * @param navigationContext {@code 01 CARDDEMO-COMMAREA}, never {@code null}
 * @param aid              the {@code EIBAID} key indication as a token, at most
 *                         {@value #AID_LENGTH} characters
 */
public record UserListRequest(

        @Size(max = UserListRequest.TRNNAME_LENGTH) String trnName,
        @Size(max = UserListRequest.TITLE01_LENGTH) String title01,
        @Size(max = UserListRequest.CURDATE_LENGTH) String curDate,
        @Size(max = UserListRequest.PGMNAME_LENGTH) String pgmName,
        @Size(max = UserListRequest.TITLE02_LENGTH) String title02,
        @Size(max = UserListRequest.CURTIME_LENGTH) String curTime,
        @Size(max = UserListRequest.PAGENUM_LENGTH) String pageNum,
        @Size(max = UserListRequest.USRIDIN_LENGTH) String usrIdIn,

        @Valid @Size(min = UserListRequest.ROW_COUNT, max = UserListRequest.ROW_COUNT)
        List<UserListRow> rows,

        @Size(max = UserListRequest.ERRMSG_LENGTH) String errMsg,

        @Size(max = UserListRequest.CU00_USRID_FIRST_LENGTH) String cu00UsrIdFirst,
        @Size(max = UserListRequest.CU00_USRID_LAST_LENGTH) String cu00UsrIdLast,
        int cu00PageNum,
        @Size(max = UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH) String cu00NextPageFlg,
        @Size(max = UserListRequest.CU00_USR_SEL_FLG_LENGTH) String cu00UsrSelFlg,
        @Size(max = UserListRequest.CU00_USR_SELECTED_LENGTH) String cu00UsrSelected,

        NavigationContext navigationContext,
        @Size(max = UserListRequest.AID_LENGTH) String aid) {

    // =============================================================================================
    // Identity of the screen. Both literals come from app/cbl/COUSR00C.cbl lines 36-37:
    //     05 WS-PGMNAME PIC X(08) VALUE 'COUSR00C'.
    //     05 WS-TRANID  PIC X(04) VALUE 'CU00'.
    // The program moves them into PGMNAMEO and TRNNAMEO in POPULATE-HEADER-INFO, so they are the
    // values a faithful payload carries in trnName and pgmName.
    // =============================================================================================

    /** {@code WS-TRANID VALUE 'CU00'} - the CICS transaction that runs this screen. */
    public static final String TRANID = "CU00";

    /** {@code WS-PGMNAME VALUE 'COUSR00C'} - the COBOL program this payload was translated from. */
    public static final String PROGRAM = "COUSR00C";

    /** The BMS mapset, {@code app/bms/COUSR00.bms} line 19: {@code COUSR00 DFHMSD}. */
    public static final String MAPSET = "COUSR00";

    /** The BMS map, {@code app/bms/COUSR00.bms} line 26: {@code COUSR0A DFHMDI}. */
    public static final String MAP = "COUSR0A";

    // =============================================================================================
    // Screen field labels, carried VERBATIM as app/bms/COUSR00.bms labels them and as
    // app/cpy-bms/COUSR00.CPY names them once the trailing I is removed. These are the keys
    // mapFields() emits and the names a field-for-field diff compares, so a "tidied" label would
    // make a real difference invisible.
    // =============================================================================================

    /** Label of {@link #trnName()}: {@code TRNNAME}, mapset line 34, {@code TRNNAMEI}. */
    public static final String TRNNAME_FIELD = "TRNNAME";

    /** Label of {@link #title01()}: {@code TITLE01}, mapset line 38, {@code TITLE01I}. */
    public static final String TITLE01_FIELD = "TITLE01";

    /** Label of {@link #curDate()}: {@code CURDATE}, mapset line 47, {@code CURDATEI}. */
    public static final String CURDATE_FIELD = "CURDATE";

    /** Label of {@link #pgmName()}: {@code PGMNAME}, mapset line 57, {@code PGMNAMEI}. */
    public static final String PGMNAME_FIELD = "PGMNAME";

    /** Label of {@link #title02()}: {@code TITLE02}, mapset line 61, {@code TITLE02I}. */
    public static final String TITLE02_FIELD = "TITLE02";

    /** Label of {@link #curTime()}: {@code CURTIME}, mapset line 70, {@code CURTIMEI}. */
    public static final String CURTIME_FIELD = "CURTIME";

    /** Label of {@link #pageNum()}: {@code PAGENUM}, mapset line 85, {@code PAGENUMI}. */
    public static final String PAGENUM_FIELD = "PAGENUM";

    /** Label of {@link #usrIdIn()}: {@code USRIDIN}, mapset line 95, {@code USRIDINI}. */
    public static final String USRIDIN_FIELD = "USRIDIN";

    /** Label of {@link #errMsg()}: {@code ERRMSG}, the last name-labelled {@code DFHMDF}. */
    public static final String ERRMSG_FIELD = "ERRMSG";

    // ---------------------------------------------------------------------------------------------
    // Row label stems. The selection column is spelled with SEL_FIELD_DIGITS digits and the other
    // four with ROW_FIELD_DIGITS. That asymmetry is in the source and is reproduced, not repaired.
    // ---------------------------------------------------------------------------------------------

    /** Stem of the selection column: {@code SEL0001}..{@code SEL0010}. */
    public static final String SEL_FIELD_STEM = "SEL";

    /** Stem of the user id column: {@code USRID01}..{@code USRID10}. */
    public static final String USRID_FIELD_STEM = "USRID";

    /** Stem of the first name column: {@code FNAME01}..{@code FNAME10}. */
    public static final String FNAME_FIELD_STEM = "FNAME";

    /** Stem of the last name column: {@code LNAME01}..{@code LNAME10}. */
    public static final String LNAME_FIELD_STEM = "LNAME";

    /** Stem of the user type column: {@code UTYPE01}..{@code UTYPE10}. */
    public static final String UTYPE_FIELD_STEM = "UTYPE";

    /** Digits in a selection label: {@code SEL0001} carries four, zero-padded. Never two. */
    public static final int SEL_FIELD_DIGITS = 4;

    /** Digits in the other four row labels: {@code USRID01} carries two. Never four. */
    public static final int ROW_FIELD_DIGITS = 2;

    // =============================================================================================
    // Screen field widths. Every value below is a literal transcribed from the xxxI PICTURE clause
    // of app/cpy-bms/COUSR00.CPY and independently confirmed by the LENGTH= of the matching
    // app/bms/COUSR00.bms DFHMDF. None is computed from another.
    // =============================================================================================

    /** {@code TRNNAMEI PIC X(4)}, mapset {@code LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}, mapset {@code LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}, mapset {@code LENGTH=8}, {@code INITIAL='mm/dd/yy'}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, mapset {@code LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}, mapset {@code LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, mapset {@code LENGTH=8}, {@code INITIAL='hh:mm:ss'}.
     *
     * <p><strong>Eight, not nine.</strong> The sign-on screen declares its own {@code CURTIME} one
     * character wider; this screen does not, and the two are not reconciled.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENUMI PIC X(8)}, mapset {@code LENGTH=8}, {@code INITIAL=' '}.
     *
     * <p>This is the <em>displayed</em> page number and it is a character field. Line 348 of
     * {@code app/cbl/COUSR00C.cbl} renders the numeric {@code CDEMO-CU00-PAGE-NUM} into it with
     * {@code MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI OF COUSR0AI}, and line 376 repeats that on the
     * backward path. The numeric source is {@link #cu00PageNum()}; the two must not be conflated.
     */
    public static final int PAGENUM_LENGTH = 8;

    /**
     * {@code USRIDINI PIC X(8)}, mapset {@code LENGTH=8}, the one unprotected header field
     * ({@code ATTRB=(FSET,NORM,UNPROT)}, {@code HILIGHT=UNDERLINE}).
     *
     * <p>Matches {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy}, which is what
     * {@code COUSR00C} lines 217-222 move it into.
     */
    public static final int USRIDIN_LENGTH = 8;

    /** {@code SEL000nI PIC X(1)}, mapset {@code LENGTH=1}. */
    public static final int SEL_LENGTH = 1;

    /** {@code USRIDnnI PIC X(8)}, mapset {@code LENGTH=8}; matches {@code SEC-USR-ID PIC X(08)}. */
    public static final int USRID_LENGTH = 8;

    /**
     * {@code FNAMEnnI PIC X(20)}, mapset {@code LENGTH=20}; matches
     * {@code SEC-USR-FNAME PIC X(20)}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEnnI PIC X(20)}, mapset {@code LENGTH=20}; matches
     * {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int LNAME_LENGTH = 20;

    /** {@code UTYPEnnI PIC X(1)}, mapset {@code LENGTH=1}; matches {@code SEC-USR-TYPE PIC X(01)}. */
    public static final int UTYPE_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}, mapset {@code LENGTH=78}.
     *
     * <p><strong>Seventy-eight, not eighty.</strong> {@code app/cbl/COUSR00C.cbl} line 38 declares
     * {@code WS-MESSAGE PIC X(80)} and line 526 moves it here, losing the last two characters. The
     * loss belongs to that {@code MOVE}, not to this declaration.
     */
    public static final int ERRMSG_LENGTH = 78;

    // =============================================================================================
    // Field counts. MAP_FIELD_COUNT is the gate-level number for this screen and is stated as a
    // literal so it can be audited by eye and asserted by test; mapFieldNames() builds a list of
    // exactly that many labels from the three blocks below.
    // =============================================================================================

    /** Fields in the header and paging block: the eight above {@code SEL0001}. */
    public static final int HEADER_FIELD_COUNT = 8;

    /**
     * Screen rows, and therefore the page size: exactly ten.
     *
     * <p>This is <strong>behaviour, not configuration</strong>. Ten row groups are declared in
     * {@code app/bms/COUSR00.bms}, and {@code app/cbl/COUSR00C.cbl} hard-codes the bound in four
     * places: {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} at lines 292 and 350,
     * {@code PERFORM UNTIL WS-IDX >= 11} at line 300, and {@code MOVE 10 TO WS-IDX} at line 351.
     * There is deliberately no page-size member, no configurable limit and no row-count setter.
     */
    public static final int ROW_COUNT = 10;

    /** Fields in one row: {@code SEL000n}, {@code USRIDnn}, {@code FNAMEnn}, {@code LNAMEnn}, {@code UTYPEnn}. */
    public static final int ROW_FIELD_COUNT = 5;

    /** Fields in the trailer block: {@code ERRMSG} alone. */
    public static final int TRAILER_FIELD_COUNT = 1;

    /**
     * Screen fields in total: 8 + 10 x 5 + 1 = <strong>59</strong>.
     *
     * <p>Equal to the {@code xxxI} count of {@code 01 COUSR0AI}, to the {@code xxxO} count of
     * {@code 01 COUSR0AO} and to the name-labelled {@code DFHMDF} count of the mapset. This is the
     * largest field count of the five user screens.
     */
    public static final int MAP_FIELD_COUNT = 59;

    // =============================================================================================
    // CDEMO-CU00-INFO: the paging context appended to CARDDEMO-COMMAREA at COUSR00C lines 65-75.
    // Not screen fields - conversation state, carried in the payload because there is no session.
    // =============================================================================================

    /** Copybook name of {@link #cu00UsrIdFirst()}: {@code CDEMO-CU00-USRID-FIRST}, line 67. */
    public static final String CU00_USRID_FIRST_FIELD = "CDEMO-CU00-USRID-FIRST";

    /** Copybook name of {@link #cu00UsrIdLast()}: {@code CDEMO-CU00-USRID-LAST}, line 68. */
    public static final String CU00_USRID_LAST_FIELD = "CDEMO-CU00-USRID-LAST";

    /** Copybook name of {@link #cu00PageNum()}: {@code CDEMO-CU00-PAGE-NUM}, line 69. */
    public static final String CU00_PAGE_NUM_FIELD = "CDEMO-CU00-PAGE-NUM";

    /** Copybook name of {@link #cu00NextPageFlg()}: {@code CDEMO-CU00-NEXT-PAGE-FLG}, line 70. */
    public static final String CU00_NEXT_PAGE_FLG_FIELD = "CDEMO-CU00-NEXT-PAGE-FLG";

    /** Copybook name of {@link #cu00UsrSelFlg()}: {@code CDEMO-CU00-USR-SEL-FLG}, line 73. */
    public static final String CU00_USR_SEL_FLG_FIELD = "CDEMO-CU00-USR-SEL-FLG";

    /** Copybook name of {@link #cu00UsrSelected()}: {@code CDEMO-CU00-USR-SELECTED}, line 74. */
    public static final String CU00_USR_SELECTED_FIELD = "CDEMO-CU00-USR-SELECTED";

    /** {@code CDEMO-CU00-USRID-FIRST PIC X(08)}. */
    public static final int CU00_USRID_FIRST_LENGTH = 8;

    /** {@code CDEMO-CU00-USRID-LAST PIC X(08)}. */
    public static final int CU00_USRID_LAST_LENGTH = 8;

    /**
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} - eight <strong>digits</strong>, not characters.
     *
     * <p>The picture is unsigned and carries no {@code V}, so the Java form is an integral type. A
     * {@code BigDecimal} would imply a scale the picture does not declare, and a {@code double} or
     * {@code float} is never used for a value derived from a COBOL picture anywhere in this module.
     */
    public static final int CU00_PAGE_NUM_LENGTH = 8;

    /**
     * The largest value {@code PIC 9(08)} can hold: eight nines.
     *
     * <p>Stated as a literal rather than computed from {@link #CU00_PAGE_NUM_LENGTH}, so the bound
     * is readable at a glance and cannot drift through an exponent mistake.
     */
    public static final int CU00_PAGE_NUM_MAX = 99_999_999;

    /**
     * The page number a fresh conversation starts on: zero.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} line 227 sets it outright with
     * {@code MOVE 0 TO CDEMO-CU00-PAGE-NUM} before performing the first forward page, and
     * {@code PROCESS-PAGE-FORWARD} then increments it. Zero is therefore a legitimate, expected
     * value and is not rejected.
     */
    public static final int CU00_PAGE_NUM_INITIAL = 0;

    /** {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}. */
    public static final int CU00_NEXT_PAGE_FLG_LENGTH = 1;

    /** {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}. */
    public static final int CU00_USR_SEL_FLG_LENGTH = 1;

    /** {@code CDEMO-CU00-USR-SELECTED PIC X(08)}. */
    public static final int CU00_USR_SELECTED_LENGTH = 8;

    /**
     * Bytes in {@code 05 CDEMO-CU00-INFO}: 8 + 8 + 8 + 1 + 1 + 8 = <strong>34</strong>.
     */
    public static final int CU00_INFO_LENGTH = 34;

    /**
     * Bytes in the {@code CU00} communication area: 160 + {@value #CU00_INFO_LENGTH} =
     * <strong>194</strong>.
     *
     * <p>{@code CARDDEMO-COMMAREA} is 160 bytes on its own -
     * {@code common.NavigationContext.COMMAREA_LENGTH} - and {@code COUSR00C} extends it by
     * {@value #CU00_INFO_LENGTH} for this screen alone. The extension is declared here rather than
     * there precisely so the shared 160-byte structure stays 160 bytes for the other sixteen
     * controllers.
     */
    public static final int CU00_COMMAREA_LENGTH = 194;

    // =============================================================================================
    // The two 88-level condition values declared over CDEMO-CU00-NEXT-PAGE-FLG, and the two row
    // actions the routing EVALUATE recognises. Read-through predicates over the stored members are
    // provided below; none of these values is ever stored as a separate boolean.
    // =============================================================================================

    /** {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COUSR00C.cbl} line 71. */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'}, {@code app/cbl/COUSR00C.cbl} line 72, and the
     * {@code VALUE 'N'} clause on the field itself at line 70.
     *
     * <p>{@code CDEMO-CU00-NEXT-PAGE-FLG} is the only one of the six {@code CDEMO-CU00-INFO} items
     * with a {@code VALUE} clause, so it is the only one whose initial state is not spaces. That is
     * why {@link #empty()} seeds it with this value while seeding every other character member with
     * spaces.
     */
    public static final String NEXT_PAGE_NO = "N";

    /**
     * The row action that selects a user for update: {@value #USR_SEL_UPDATE}.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} lines 190-201 accept it in <strong>either case</strong> -
     * {@code WHEN 'U'} immediately followed by {@code WHEN 'u'}, two arms of one {@code EVALUATE}
     * sharing a body - and transfer to the user update program. {@link #usrSelUpdate()} reproduces
     * that case-insensitivity; the transfer itself is the controller's decision, not this payload's.
     */
    public static final String USR_SEL_UPDATE = "U";

    /**
     * The row action that selects a user for deletion: {@value #USR_SEL_DELETE}.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} lines 202-213 accept {@code WHEN 'D'} and {@code WHEN 'd'}
     * alike and transfer to the user delete program. Anything else falls to {@code WHEN OTHER},
     * which sets the message {@code 'Invalid selection. Valid values are U and D'} and repositions
     * the cursor. Reproducing that arm is the controller's job.
     */
    public static final String USR_SEL_DELETE = "D";

    /**
     * Characters in the {@code EIBAID} token carried by {@link #aid()}: five.
     *
     * <p>{@code COUSR00C} tests the raw {@code EIBAID} byte inline at lines 124-138 -
     * {@code DFHENTER}, {@code DFHPF3}, {@code DFHPF7}, {@code DFHPF8} and {@code WHEN OTHER} - and
     * this screen copies neither {@code CVCRD01Y} nor {@code CSSTRPFY}. The token form is therefore
     * the module's own convention rather than this program's copybook: five characters, matching
     * {@code 10 CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy} and the width that
     * {@code common.PfKeyResolver.AID_TOKEN_LENGTH} publishes. The four tokens this screen acts on
     * are {@code ENTER}, {@code PFK03}, {@code PFK07} and {@code PFK08}; note that
     * {@code common.PfKeyResolver.AidKey#token()} space-pads the shorter mnemonics to this width, so
     * a caller must not trim what it produces.
     */
    public static final int AID_LENGTH = 5;

    /** Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field. */
    public static final String AID_FIELD = "EIBAID";

    /** A single space, the fill character for every unset {@code PIC X} field. */
    private static final String SPACE = " ";

    /** The zero padding that widens a two-digit row number to a four-digit selection label. */
    private static final String SEL_LABEL_PAD = "00";

    // =============================================================================================
    // Construction.
    // =============================================================================================

    /**
     * Canonical constructor, which normalises absent values and rejects values the screen cannot
     * physically hold.
     *
     * <p>Three rules, each derived from the source rather than chosen:
     *
     * <ul>
     *   <li><strong>{@code null} becomes the field's declared width in spaces.</strong> There is no
     *       null in a COBOL record, and a JSON body that omits a field is not a state CICS can
     *       produce: a {@code RECEIVE MAP} always yields a fully formed 24x80 image. The COBOL
     *       equivalent of "nothing has been put here yet" is
     *       {@code MOVE LOW-VALUES TO COUSR0AO}, which {@code app/cbl/COUSR00C.cbl} performs at line
     *       120 on first entry, and every test this program applies to these fields treats spaces
     *       and low values alike - {@code = SPACES OR LOW-VALUES} at lines 217, 239 and 262, and
     *       {@code NOT = SPACES AND LOW-VALUES} in the ten selection arms at lines 152-183. Absent
     *       is therefore normalised to blank rather than rejected, so an omitted field can never
     *       become a server error.</li>
     *   <li><strong>A value longer than its declared width is rejected.</strong> A BMS field is
     *       exactly {@code LENGTH=} bytes wide, so a longer value has no representation on the
     *       screen at all. Rejecting it names the offending field and its width; silently discarding
     *       the surplus would hide the loss at the point where it matters. A caller that
     *       <em>intends</em> to shorten a value asks for it explicitly through
     *       {@code common.FixedWidthCodec.movePicX}, which truncates on the right exactly as a COBOL
     *       alphanumeric {@code MOVE} does - that is how the 80-to-{@value #ERRMSG_LENGTH} narrowing
     *       at line 526 is performed.</li>
     *   <li><strong>A shorter value is accepted unchanged.</strong> It is not padded here, because
     *       padding is half of the {@code MOVE} rule and that rule lives in the codec alone.</li>
     * </ul>
     *
     * <p>{@link #rows()} is defensively copied into an immutable list, {@code null} rows becoming
     * {@link #blankRows()} and a {@code null} element becoming {@link UserListRow#blank()}, and a
     * list of any size other than {@value #ROW_COUNT} is rejected. {@link #navigationContext()}
     * defaults to {@code common.NavigationContext.empty()}, the cold-start communication area.
     *
     * <p>Deliberately absent: no {@code @NotNull} and no {@code @NotBlank} on any member. Blank is
     * <strong>meaningful</strong> on this screen - line 217 reads
     * {@code IF USRIDINI = SPACES OR LOW-VALUES} and starts the browse at the beginning of the file,
     * and line 231 blanks the field again after a successful page. Rejecting a blank would change
     * observable behaviour. There is likewise no lower or upper bound on
     * {@link #cu00PageNum()} beyond what {@code PIC 9(08)} can represent, because line 227 sets it
     * to zero and line 367 decrements it.
     *
     * @throws IllegalArgumentException if a character member exceeds its declared width, if
     *                                 {@link #rows()} is neither {@code null} nor exactly
     *                                 {@value #ROW_COUNT} entries, or if {@link #cu00PageNum()} is
     *                                 negative or needs more than {@value #CU00_PAGE_NUM_LENGTH}
     *                                 digits
     */
    public UserListRequest {
        trnName = requirePicX(trnName, TRNNAME_LENGTH, TRNNAME_FIELD);
        title01 = requirePicX(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requirePicX(curDate, CURDATE_LENGTH, CURDATE_FIELD);
        pgmName = requirePicX(pgmName, PGMNAME_LENGTH, PGMNAME_FIELD);
        title02 = requirePicX(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requirePicX(curTime, CURTIME_LENGTH, CURTIME_FIELD);
        pageNum = requirePicX(pageNum, PAGENUM_LENGTH, PAGENUM_FIELD);
        usrIdIn = requirePicX(usrIdIn, USRIDIN_LENGTH, USRIDIN_FIELD);
        rows = requireRows(rows);
        errMsg = requirePicX(errMsg, ERRMSG_LENGTH, ERRMSG_FIELD);
        cu00UsrIdFirst = requirePicX(cu00UsrIdFirst, CU00_USRID_FIRST_LENGTH, CU00_USRID_FIRST_FIELD);
        cu00UsrIdLast = requirePicX(cu00UsrIdLast, CU00_USRID_LAST_LENGTH, CU00_USRID_LAST_FIELD);
        cu00PageNum = requireCu00PageNum(cu00PageNum);
        cu00NextPageFlg =
                requirePicX(cu00NextPageFlg, CU00_NEXT_PAGE_FLG_LENGTH, CU00_NEXT_PAGE_FLG_FIELD);
        cu00UsrSelFlg = requirePicX(cu00UsrSelFlg, CU00_USR_SEL_FLG_LENGTH, CU00_USR_SEL_FLG_FIELD);
        cu00UsrSelected =
                requirePicX(cu00UsrSelected, CU00_USR_SELECTED_LENGTH, CU00_USR_SELECTED_FIELD);
        navigationContext = requireContext(navigationContext);
        aid = requirePicX(aid, AID_LENGTH, AID_FIELD);
    }

    /**
     * A blank screen: every character field its declared width in spaces, the numeric page number
     * {@value #CU00_PAGE_NUM_INITIAL}, the next-page flag at its {@code VALUE} clause default
     * {@value #NEXT_PAGE_NO}, all {@value #ROW_COUNT} rows blank, and a cold-start communication
     * area.
     *
     * <p>This is the state {@code app/cbl/COUSR00C.cbl} produces on first entry. Line 120 sets the
     * whole map to low values with {@code MOVE LOW-VALUES TO COUSR0AO}, lines 107-108 blank the
     * message with {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COUSR0AO}, and line 227 zeroes the
     * page number before the first forward page. Because the program's own tests treat spaces and
     * low values identically, spaces are the faithful Java rendering of that state.
     *
     * <p>Note the deliberate asymmetry in the seeds: {@link #cu00NextPageFlg()} starts at
     * {@value #NEXT_PAGE_NO} because {@code CDEMO-CU00-NEXT-PAGE-FLG} is declared
     * {@code PIC X(01) VALUE 'N'}, whereas {@link #cu00UsrSelFlg()} - declared without a
     * {@code VALUE} clause - starts blank. Line 106 confirms the intent with
     * {@code SET NEXT-PAGE-NO TO TRUE}.
     *
     * @return a blank {@code CU00} request, never {@code null}
     */
    public static UserListRequest empty() {
        return new UserListRequest(spaces(TRNNAME_LENGTH),
                spaces(TITLE01_LENGTH),
                spaces(CURDATE_LENGTH),
                spaces(PGMNAME_LENGTH),
                spaces(TITLE02_LENGTH),
                spaces(CURTIME_LENGTH),
                spaces(PAGENUM_LENGTH),
                spaces(USRIDIN_LENGTH),
                blankRows(),
                spaces(ERRMSG_LENGTH),
                spaces(CU00_USRID_FIRST_LENGTH),
                spaces(CU00_USRID_LAST_LENGTH),
                CU00_PAGE_NUM_INITIAL,
                NEXT_PAGE_NO,
                spaces(CU00_USR_SEL_FLG_LENGTH),
                spaces(CU00_USR_SELECTED_LENGTH),
                NavigationContext.empty(),
                spaces(AID_LENGTH));
    }

    /**
     * {@value #ROW_COUNT} blank rows, immutable.
     *
     * <p>This is the {@code INITIALIZE-USER-DATA} paragraph of {@code app/cbl/COUSR00C.cbl} lines
     * 446-500, which walks {@code WS-IDX} from 1 to 10 and moves spaces into {@code USRIDnnI},
     * {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI} for each. The paragraph is driven by
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} at lines 292 and 350, before each
     * page is filled, so that a short final page leaves no stale rows on the screen.
     *
     * <p>The selection column is blanked here too. The COBOL paragraph does not touch
     * {@code SEL000nI} - it is the user's own input and the program never writes it - so blanking it
     * on a freshly constructed request is a property of starting from nothing rather than a
     * reproduction of that paragraph.
     *
     * @return exactly {@value #ROW_COUNT} blank rows, never {@code null}, unmodifiable
     */
    public static List<UserListRow> blankRows() {
        List<UserListRow> blanks = new ArrayList<>(ROW_COUNT);
        for (int index = 0; index < ROW_COUNT; index++) {
            blanks.add(UserListRow.blank());
        }
        return List.copyOf(blanks);
    }

    // =============================================================================================
    // One screen row: the five repeating map fields, five times ten of which make up 50 of this
    // payload's 59 screen fields.
    // =============================================================================================

    /**
     * One row of the user list - the five repeating screen fields at a single line of the map.
     *
     * <h2>Ten rows, and the off-by-one that must not happen</h2>
     *
     * The COBOL table is one-based and the Java list is zero-based. {@code POPULATE-USER-DATA} at
     * {@code app/cbl/COUSR00C.cbl} lines 386-441 is an {@code EVALUATE WS-IDX} whose
     * {@code WHEN 1} arm writes {@code USRID01I}, {@code FNAME01I}, {@code LNAME01I} and
     * {@code UTYPE01I}, and whose {@code WHEN 10} arm writes the {@code ..10I} items. The mapping is
     * therefore:
     *
     * <table border="1">
     *   <caption>Index convention, asserted by test</caption>
     *   <tr><th>{@code WS-IDX}</th><th>Map label suffix</th><th>Java list index</th></tr>
     *   <tr><td>1</td><td>{@code SEL0001}, {@code USRID01} ..</td><td>0</td></tr>
     *   <tr><td>{@code n}</td><td>{@code n}</td><td>{@code n - 1}</td></tr>
     *   <tr><td>10</td><td>{@code SEL0010}, {@code USRID10} ..</td><td>9</td></tr>
     * </table>
     *
     * {@link UserListRequest#row(int)} and {@link UserListRequest#withRow(int, UserListRow)} take the
     * <strong>one-based</strong> {@code WS-IDX} number so that translated code reads like the
     * paragraph it came from, and they perform the subtraction in one place. Reaching
     * {@link UserListRequest#rows()} directly is zero-based, as any Java list is.
     *
     * <p>Two arms of {@code POPULATE-USER-DATA} do more than fill the row, and they are the reason
     * the boundary rows matter: {@code WHEN 1} also moves the user id into
     * {@code CDEMO-CU00-USRID-FIRST} (line 389) and {@code WHEN 10} also moves it into
     * {@code CDEMO-CU00-USRID-LAST} (line 435). Those two are the keys the previous-page and
     * next-page browses restart from, so an off-by-one here would page the wrong way.
     *
     * <h2>The selection column is input, never output</h2>
     *
     * {@code POPULATE-USER-DATA} writes {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and
     * {@code UTYPEnnI} and <strong>never</strong> {@code SEL000nI}; neither does
     * {@code INITIALIZE-USER-DATA}. The selection column is purely what the user typed, which is why
     * it belongs on a request. {@code PROCESS-ENTER-KEY} at lines 151-186 reads it back out through
     * an ordered {@code EVALUATE TRUE} over {@code SEL0001I}..{@code SEL0010I}, in which the
     * <em>first</em> non-blank row wins and {@code WHEN OTHER} clears the selection - so when two
     * rows are marked the lower-numbered one takes effect. Reproducing that ordering is the
     * controller's responsibility.
     *
     * <h2>The other four columns are a projection of the security record</h2>
     *
     * Their widths match {@code app/cpy/CSUSR01Y.cpy} exactly: {@code SEC-USR-ID PIC X(08)},
     * {@code SEC-USR-FNAME PIC X(20)}, {@code SEC-USR-LNAME PIC X(20)} and
     * {@code SEC-USR-TYPE PIC X(01)}. The record's {@code SEC-USR-PWD PIC X(08)} has no screen field
     * and is not carried here - the list never displays a password.
     *
     * @param sel   {@code SEL000nI PIC X(1)}: the row action the user typed, blank when untouched
     * @param usrId {@code USRIDnnI PIC X(8)}: the user id, from {@code SEC-USR-ID}
     * @param fname {@code FNAMEnnI PIC X(20)}: the first name, from {@code SEC-USR-FNAME}
     * @param lname {@code LNAMEnnI PIC X(20)}: the last name, from {@code SEC-USR-LNAME}
     * @param utype {@code UTYPEnnI PIC X(1)}: the user type, from {@code SEC-USR-TYPE}
     */
    public record UserListRow(
            @Size(max = UserListRequest.SEL_LENGTH) String sel,
            @Size(max = UserListRequest.USRID_LENGTH) String usrId,
            @Size(max = UserListRequest.FNAME_LENGTH) String fname,
            @Size(max = UserListRequest.LNAME_LENGTH) String lname,
            @Size(max = UserListRequest.UTYPE_LENGTH) String utype) {

        /**
         * Canonical constructor, applying the same two rules as the enclosing type: {@code null}
         * becomes the field's declared width in spaces, and a value wider than the screen field is
         * rejected by name.
         *
         * @throws IllegalArgumentException if any component exceeds its declared width
         */
        public UserListRow {
            sel = requirePicX(sel, SEL_LENGTH, SEL_FIELD_STEM);
            usrId = requirePicX(usrId, USRID_LENGTH, USRID_FIELD_STEM);
            fname = requirePicX(fname, FNAME_LENGTH, FNAME_FIELD_STEM);
            lname = requirePicX(lname, LNAME_LENGTH, LNAME_FIELD_STEM);
            utype = requirePicX(utype, UTYPE_LENGTH, UTYPE_FIELD_STEM);
        }

        /**
         * A blank row: each of the five fields its declared width in spaces.
         *
         * <p>{@code INITIALIZE-USER-DATA} blanks four of the five; the selection column is blanked
         * as well here because a row that has never been rendered has nothing typed in it either.
         *
         * @return a blank row, never {@code null}
         */
        public static UserListRow blank() {
            return new UserListRow(spaces(SEL_LENGTH),
                    spaces(USRID_LENGTH),
                    spaces(FNAME_LENGTH),
                    spaces(LNAME_LENGTH),
                    spaces(UTYPE_LENGTH));
        }

        /**
         * This row with a different selection value.
         *
         * @param newSel the replacement {@code SEL000nI} value, {@code null} meaning blank
         * @return a new row, never {@code null}
         * @throws IllegalArgumentException if {@code newSel} exceeds {@value #SEL_LENGTH} characters
         */
        public UserListRow withSel(String newSel) {
            return new UserListRow(newSel, usrId, fname, lname, utype);
        }

        /**
         * This row with a different user id.
         *
         * @param newUsrId the replacement {@code USRIDnnI} value, {@code null} meaning blank
         * @return a new row, never {@code null}
         * @throws IllegalArgumentException if {@code newUsrId} exceeds {@value #USRID_LENGTH}
         *                                 characters
         */
        public UserListRow withUsrId(String newUsrId) {
            return new UserListRow(sel, newUsrId, fname, lname, utype);
        }

        /**
         * This row with a different first name.
         *
         * @param newFname the replacement {@code FNAMEnnI} value, {@code null} meaning blank
         * @return a new row, never {@code null}
         * @throws IllegalArgumentException if {@code newFname} exceeds {@value #FNAME_LENGTH}
         *                                 characters
         */
        public UserListRow withFname(String newFname) {
            return new UserListRow(sel, usrId, newFname, lname, utype);
        }

        /**
         * This row with a different last name.
         *
         * @param newLname the replacement {@code LNAMEnnI} value, {@code null} meaning blank
         * @return a new row, never {@code null}
         * @throws IllegalArgumentException if {@code newLname} exceeds {@value #LNAME_LENGTH}
         *                                 characters
         */
        public UserListRow withLname(String newLname) {
            return new UserListRow(sel, usrId, fname, newLname, utype);
        }

        /**
         * This row with a different user type.
         *
         * @param newUtype the replacement {@code UTYPEnnI} value, {@code null} meaning blank
         * @return a new row, never {@code null}
         * @throws IllegalArgumentException if {@code newUtype} exceeds {@value #UTYPE_LENGTH}
         *                                 characters
         */
        public UserListRow withUtype(String newUtype) {
            return new UserListRow(sel, usrId, fname, lname, newUtype);
        }
    }

    // =============================================================================================
    // Row access by the one-based COBOL row number, so translated code can keep the WS-IDX values
    // its paragraph used and the zero-based subtraction happens in exactly two places.
    // =============================================================================================

    /**
     * The row at the one-based {@code WS-IDX} position, counting from 1 as
     * {@code POPULATE-USER-DATA} does.
     *
     * <p>{@code row(1)} is the row whose fields are {@code SEL0001}, {@code USRID01},
     * {@code FNAME01}, {@code LNAME01} and {@code UTYPE01}, and it is {@code rows().get(0)}.
     * {@code row(}{@value #ROW_COUNT}{@code )} is the {@code ..10} row and is
     * {@code rows().get(9)}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return that row, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public UserListRow row(int rowNumber) {
        return rows.get(requireRowNumber(rowNumber) - 1);
    }

    /**
     * This request with one row replaced, addressed by its one-based {@code WS-IDX} number.
     *
     * <p>This is the shape of a single {@code POPULATE-USER-DATA} arm: fill row {@code n} and leave
     * the other nine as they are.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @param newRow    the replacement row, {@code null} meaning {@link UserListRow#blank()}
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public UserListRequest withRow(int rowNumber, UserListRow newRow) {
        List<UserListRow> replaced = new ArrayList<>(rows);
        replaced.set(requireRowNumber(rowNumber) - 1, newRow);
        return withRows(replaced);
    }

    // =============================================================================================
    // The two 88-level conditions and the two row actions, as read-through predicates. Each tests a
    // stored member; none is a separate boolean, because a boolean can drift out of step with the
    // field it summarises. None is bean-getter-shaped either, so none of them becomes a JSON
    // property and no serialisation annotation is needed to keep them off the wire.
    // =============================================================================================

    /**
     * Whether {@code 88 NEXT-PAGE-YES VALUE 'Y'} holds - that is, whether
     * {@link #cu00NextPageFlg()} is exactly {@value #NEXT_PAGE_YES}.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} line 271 gates the forward page on it with
     * {@code IF NEXT-PAGE-YES}, {@code PROCESS-PAGE-FORWARD} sets it at line 312 when a read-ahead
     * finds another record, and {@code PROCESS-PF7-KEY} asserts it at line 246 with
     * {@code SET NEXT-PAGE-YES TO TRUE} because paging back always leaves a page ahead.
     *
     * @return {@code true} when the flag is {@value #NEXT_PAGE_YES}
     */
    public boolean nextPageYes() {
        return NEXT_PAGE_YES.equals(cu00NextPageFlg);
    }

    /**
     * Whether {@code 88 NEXT-PAGE-NO VALUE 'N'} holds - that is, whether
     * {@link #cu00NextPageFlg()} is exactly {@value #NEXT_PAGE_NO}.
     *
     * <p>Deliberately <strong>not</strong> written as the negation of {@link #nextPageYes()}. The
     * two conditions are not exhaustive: {@code CDEMO-CU00-NEXT-PAGE-FLG} is {@code PIC X(01)} and
     * can legitimately hold a space, in which case both predicates are false. Defining one as
     * {@code !}the other would report a blank flag as "no more pages", which is a different
     * statement from "the flag has not been set".
     *
     * @return {@code true} when the flag is {@value #NEXT_PAGE_NO}
     */
    public boolean nextPageNo() {
        return NEXT_PAGE_NO.equals(cu00NextPageFlg);
    }

    /**
     * Whether the captured row action selects a user for update - {@link #cu00UsrSelFlg()} equal to
     * {@value #USR_SEL_UPDATE} <strong>in either case</strong>.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} lines 190-191 are {@code WHEN 'U'} followed immediately by
     * {@code WHEN 'u'}, two arms of one {@code EVALUATE} sharing a single body, so the program
     * accepts both. The comparison here is therefore case-insensitive, which is faithful rather than
     * lenient. Deciding what to do about it - the transfer to the user update program - is the
     * controller's job, not this payload's.
     *
     * @return {@code true} when the flag is {@code U} or {@code u}
     */
    public boolean usrSelUpdate() {
        return USR_SEL_UPDATE.equalsIgnoreCase(cu00UsrSelFlg);
    }

    /**
     * Whether the captured row action selects a user for deletion - {@link #cu00UsrSelFlg()} equal
     * to {@value #USR_SEL_DELETE} <strong>in either case</strong>.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} lines 202-203 are {@code WHEN 'D'} followed immediately by
     * {@code WHEN 'd'}. Anything that is neither an update nor a delete action falls to the
     * program's {@code WHEN OTHER} arm at lines 214-215, which reports
     * {@code 'Invalid selection. Valid values are U and D'} and repositions the cursor; that arm is
     * reached in Java when both this predicate and {@link #usrSelUpdate()} are false.
     *
     * @return {@code true} when the flag is {@code D} or {@code d}
     */
    public boolean usrSelDelete() {
        return USR_SEL_DELETE.equalsIgnoreCase(cu00UsrSelFlg);
    }

    // =============================================================================================
    // The screen field labels, rendered per row, and the field-for-field view of the whole screen.
    // =============================================================================================

    /**
     * The selection label for a row: {@code SEL0001} through {@code SEL0010}.
     *
     * <p>{@value #SEL_FIELD_DIGITS} digits, zero-padded - {@code SEL0001}, not {@code SEL01} and not
     * {@code SEL1}. Verified against both {@code app/bms/COUSR00.bms} and
     * {@code app/cpy-bms/COUSR00.CPY}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String selFieldName(int rowNumber) {
        return SEL_FIELD_STEM + SEL_LABEL_PAD + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The user id label for a row: {@code USRID01} through {@code USRID10} -
     * {@value #ROW_FIELD_DIGITS} digits, unlike the selection column's
     * {@value #SEL_FIELD_DIGITS}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String usrIdFieldName(int rowNumber) {
        return USRID_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The first name label for a row: {@code FNAME01} through {@code FNAME10}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String fnameFieldName(int rowNumber) {
        return FNAME_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The last name label for a row: {@code LNAME01} through {@code LNAME10}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String lnameFieldName(int rowNumber) {
        return LNAME_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The user type label for a row: {@code UTYPE01} through {@code UTYPE10}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String utypeFieldName(int rowNumber) {
        return UTYPE_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * All {@value #MAP_FIELD_COUNT} screen field labels, in the order
     * {@code app/cpy-bms/COUSR00.CPY} declares them and {@code app/bms/COUSR00.bms} lays them out:
     * the {@value #HEADER_FIELD_COUNT} header fields, then the {@value #ROW_COUNT} rows of
     * {@value #ROW_FIELD_COUNT}, then {@code ERRMSG}.
     *
     * <p>Row labels appear four-then-one within each row - {@code SEL000n}, {@code USRIDnn},
     * {@code FNAMEnn}, {@code LNAMEnn}, {@code UTYPEnn} - because that is the declaration order of
     * the symbolic map, and the map's order is the order the screen is painted in.
     *
     * @return the label list, never {@code null}, unmodifiable, exactly
     *         {@value #MAP_FIELD_COUNT} entries
     */
    public static List<String> mapFieldNames() {
        List<String> names = new ArrayList<>(MAP_FIELD_COUNT);
        names.add(TRNNAME_FIELD);
        names.add(TITLE01_FIELD);
        names.add(CURDATE_FIELD);
        names.add(PGMNAME_FIELD);
        names.add(TITLE02_FIELD);
        names.add(CURTIME_FIELD);
        names.add(PAGENUM_FIELD);
        names.add(USRIDIN_FIELD);
        for (int rowNumber = 1; rowNumber <= ROW_COUNT; rowNumber++) {
            names.add(selFieldName(rowNumber));
            names.add(usrIdFieldName(rowNumber));
            names.add(fnameFieldName(rowNumber));
            names.add(lnameFieldName(rowNumber));
            names.add(utypeFieldName(rowNumber));
        }
        names.add(ERRMSG_FIELD);
        return List.copyOf(names);
    }

    /**
     * This payload as a field-for-field view of the screen: all {@value #MAP_FIELD_COUNT} screen
     * fields keyed by their map label, in map declaration order.
     *
     * <p>Field-for-field is how the migration's parity check compares a Java result against the
     * COBOL contract, so this method exists to make that comparison possible without reflection and
     * to make the {@value #MAP_FIELD_COUNT} count assertable rather than assumed. It mirrors the
     * {@code fieldImages()} convention of the copybook model types.
     *
     * <p>The six {@code CDEMO-CU00-INFO} members, {@link #navigationContext()} and {@link #aid()}
     * are <strong>not</strong> included: they are conversation state, not screen fields, and every
     * key returned here traces to a name-labelled {@code DFHMDF} in {@code app/bms/COUSR00.bms}.
     * Values are returned exactly as stored - never trimmed, never padded - because trailing spaces
     * are part of a {@code PIC X(n)} value.
     *
     * @return the screen fields, never {@code null}, unmodifiable, iterating in map order with
     *         exactly {@value #MAP_FIELD_COUNT} entries
     */
    public Map<String, String> mapFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TRNNAME_FIELD, trnName);
        fields.put(TITLE01_FIELD, title01);
        fields.put(CURDATE_FIELD, curDate);
        fields.put(PGMNAME_FIELD, pgmName);
        fields.put(TITLE02_FIELD, title02);
        fields.put(CURTIME_FIELD, curTime);
        fields.put(PAGENUM_FIELD, pageNum);
        fields.put(USRIDIN_FIELD, usrIdIn);
        for (int rowNumber = 1; rowNumber <= ROW_COUNT; rowNumber++) {
            UserListRow current = rows.get(rowNumber - 1);
            fields.put(selFieldName(rowNumber), current.sel());
            fields.put(usrIdFieldName(rowNumber), current.usrId());
            fields.put(fnameFieldName(rowNumber), current.fname());
            fields.put(lnameFieldName(rowNumber), current.lname());
            fields.put(utypeFieldName(rowNumber), current.utype());
        }
        fields.put(ERRMSG_FIELD, errMsg);
        return Collections.unmodifiableMap(fields);
    }

    // =============================================================================================
    // Immutable field replacement, one member at a time.
    //
    // These mirror the way the COBOL works. COUSR00C moves values into the map one field at a time -
    // MOVE SEC-USR-ID TO USRID01I OF COUSR0AI at line 388 and forty-nine more like it - and, notably,
    // it writes its OUTPUT into the "I" items rather than the "O" items. That is byte-identical
    // because 01 COUSR0AO REDEFINES COUSR0AI at the same stride, so the two views address the same
    // storage. The consequence is preserved rather than tidied away: a request payload is a
    // legitimate place for the program to write, and so it carries the same replacement operations
    // the paragraph does. Each returns a new instance, because the type is immutable.
    // =============================================================================================

    /**
     * This request with a different {@code TRNNAME}.
     *
     * @param newTrnName the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #TRNNAME_LENGTH} characters
     */
    public UserListRequest withTrnName(String newTrnName) {
        return new UserListRequest(newTrnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code TITLE01}.
     *
     * @param newTitle01 the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #TITLE01_LENGTH} characters
     */
    public UserListRequest withTitle01(String newTitle01) {
        return new UserListRequest(trnName, newTitle01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CURDATE}.
     *
     * @param newCurDate the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CURDATE_LENGTH} characters
     */
    public UserListRequest withCurDate(String newCurDate) {
        return new UserListRequest(trnName, title01, newCurDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code PGMNAME}.
     *
     * @param newPgmName the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #PGMNAME_LENGTH} characters
     */
    public UserListRequest withPgmName(String newPgmName) {
        return new UserListRequest(trnName, title01, curDate, newPgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code TITLE02}.
     *
     * @param newTitle02 the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #TITLE02_LENGTH} characters
     */
    public UserListRequest withTitle02(String newTitle02) {
        return new UserListRequest(trnName, title01, curDate, pgmName, newTitle02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CURTIME}.
     *
     * @param newCurTime the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CURTIME_LENGTH} characters - which is
     *                                 eight on this screen, not nine
     */
    public UserListRequest withCurTime(String newCurTime) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, newCurTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different displayed page number, the character field {@code PAGENUM}.
     *
     * <p>This is the rendering, not the count. {@code app/cbl/COUSR00C.cbl} lines 348 and 376 both
     * do {@code MOVE CDEMO-CU00-PAGE-NUM TO PAGENUMI OF COUSR0AI}, converting the numeric page
     * number into these eight characters; to change the count itself use
     * {@link #withCu00PageNum(int)}.
     *
     * @param newPageNum the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #PAGENUM_LENGTH} characters
     */
    public UserListRequest withPageNum(String newPageNum) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, newPageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different browse-start user id, {@code USRIDIN}.
     *
     * <p>Blank is a legitimate value and means "start the browse at the beginning of the file":
     * {@code app/cbl/COUSR00C.cbl} line 217 tests {@code IF USRIDINI = SPACES OR LOW-VALUES} and
     * moves low values into the key. Line 231 deliberately blanks the field again after a successful
     * forward page.
     *
     * @param newUsrIdIn the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #USRIDIN_LENGTH} characters
     */
    public UserListRequest withUsrIdIn(String newUsrIdIn) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                newUsrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum,
                cu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different set of rows.
     *
     * @param newRows the replacement rows, {@code null} meaning {@link #blankRows()}
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if {@code newRows} is neither {@code null} nor exactly
     *                                 {@value #ROW_COUNT} entries
     */
    public UserListRequest withRows(List<UserListRow> newRows) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, newRows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum,
                cu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different message line, {@code ERRMSG}.
     *
     * <p>The field is {@value #ERRMSG_LENGTH} characters while {@code WS-MESSAGE} is eighty, so a
     * caller holding an eighty-character message must narrow it deliberately through
     * {@code common.FixedWidthCodec.movePicX}, reproducing the truncation
     * {@code app/cbl/COUSR00C.cbl} line 526 performs. This method truncates nothing.
     *
     * @param newErrMsg the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #ERRMSG_LENGTH} characters
     */
    public UserListRequest withErrMsg(String newErrMsg) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, newErrMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum,
                cu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CDEMO-CU00-USRID-FIRST}.
     *
     * <p>{@code POPULATE-USER-DATA}'s {@code WHEN 1} arm sets it at
     * {@code app/cbl/COUSR00C.cbl} line 389, and {@code PROCESS-PF7-KEY} reads it back at line 239
     * as the key to page backward from - treating blank as "start at the beginning".
     *
     * @param newCu00UsrIdFirst the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USRID_FIRST_LENGTH} characters
     */
    public UserListRequest withCu00UsrIdFirst(String newCu00UsrIdFirst) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, newCu00UsrIdFirst, cu00UsrIdLast, cu00PageNum,
                cu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CDEMO-CU00-USRID-LAST}.
     *
     * <p>{@code POPULATE-USER-DATA}'s {@code WHEN 10} arm sets it at
     * {@code app/cbl/COUSR00C.cbl} line 435, and {@code PROCESS-PF8-KEY} reads it back at line 262
     * as the key to page forward from - treating blank as "start at the end", by moving high values
     * into the key rather than low values.
     *
     * @param newCu00UsrIdLast the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USRID_LAST_LENGTH} characters
     */
    public UserListRequest withCu00UsrIdLast(String newCu00UsrIdLast) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, newCu00UsrIdLast, cu00PageNum,
                cu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different numeric page number, {@code CDEMO-CU00-PAGE-NUM}.
     *
     * @param newCu00PageNum the replacement page number, {@value #CU00_PAGE_NUM_INITIAL} being both
     *                       legitimate and the initial value
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if negative or greater than {@value #CU00_PAGE_NUM_MAX}
     */
    public UserListRequest withCu00PageNum(int newCu00PageNum) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, newCu00PageNum,
                cu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CDEMO-CU00-NEXT-PAGE-FLG}.
     *
     * @param newCu00NextPageFlg the replacement flag, normally {@value #NEXT_PAGE_YES} or
     *                           {@value #NEXT_PAGE_NO}; {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_NEXT_PAGE_FLG_LENGTH} character
     */
    public UserListRequest withCu00NextPageFlg(String newCu00NextPageFlg) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum,
                newCu00NextPageFlg, cu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with the next-page flag set, the Java form of
     * {@code SET NEXT-PAGE-YES TO TRUE}.
     *
     * @return a new request whose {@link #nextPageYes()} is {@code true}, never {@code null}
     */
    public UserListRequest withNextPageYes() {
        return withCu00NextPageFlg(NEXT_PAGE_YES);
    }

    /**
     * This request with the next-page flag cleared, the Java form of
     * {@code SET NEXT-PAGE-NO TO TRUE} - which {@code app/cbl/COUSR00C.cbl} performs at line 106,
     * and again at lines 314 and 318 when a read-ahead finds nothing.
     *
     * @return a new request whose {@link #nextPageNo()} is {@code true}, never {@code null}
     */
    public UserListRequest withNextPageNo() {
        return withCu00NextPageFlg(NEXT_PAGE_NO);
    }

    /**
     * This request with a different captured row action, {@code CDEMO-CU00-USR-SEL-FLG}.
     *
     * <p>{@code PROCESS-ENTER-KEY} sets it from the first non-blank selection column at
     * {@code app/cbl/COUSR00C.cbl} lines 152-183, and clears it to spaces in the {@code WHEN OTHER}
     * arm at line 184 when no row was marked.
     *
     * @param newCu00UsrSelFlg the replacement action, normally {@value #USR_SEL_UPDATE} or
     *                         {@value #USR_SEL_DELETE} in either case; {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USR_SEL_FLG_LENGTH} character
     */
    public UserListRequest withCu00UsrSelFlg(String newCu00UsrSelFlg) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                newCu00UsrSelFlg, cu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different selected user id, {@code CDEMO-CU00-USR-SELECTED}.
     *
     * <p>{@code PROCESS-ENTER-KEY} sets it from the {@code USRIDnnI} of whichever row was marked,
     * and the routing at line 188 only acts when both this member and the action flag are non-blank.
     *
     * @param newCu00UsrSelected the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USR_SELECTED_LENGTH} characters
     */
    public UserListRequest withCu00UsrSelected(String newCu00UsrSelected) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, newCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different communication area.
     *
     * @param newNavigationContext the replacement communication area, {@code null} meaning the
     *                             cold-start area
     * @return a new request, never {@code null}
     */
    public UserListRequest withNavigationContext(NavigationContext newNavigationContext) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, newNavigationContext, aid);
    }

    /**
     * This request with a different key indication.
     *
     * @param newAid the replacement {@code EIBAID} token, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #AID_LENGTH} characters
     */
    public UserListRequest withAid(String newAid) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cu00UsrIdFirst, cu00UsrIdLast, cu00PageNum, cu00NextPageFlg,
                cu00UsrSelFlg, cu00UsrSelected, navigationContext, newAid);
    }

    // =============================================================================================
    // Shared guards. Each rule is stated once, so every member is held to the identical standard and
    // every rejection message names the field and the width the source declares for it.
    // =============================================================================================

    /**
     * The COBOL figurative constant {@code SPACES} sized to a field: a run of {@code width} spaces.
     *
     * <p>This is the unconditional fill of {@code MOVE SPACES} and {@code INITIALIZE}, deliberately
     * not the alphanumeric {@code MOVE} rule. That rule - pad a shorter sending value, truncate a
     * longer one - belongs to {@code common.FixedWidthCodec} alone.
     */
    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    /**
     * Normalises an absent character field to blank and rejects one wider than the screen field,
     * returning the value unchanged when it fits.
     */
    private static String requirePicX(String value, int declaredWidth, String mapField) {
        if (value == null) {
            return spaces(declaredWidth);
        }
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Screen field " + mapField + " of mapset " + MAPSET
                    + " is declared PIC X(" + declaredWidth + ") and DFHMDF LENGTH=" + declaredWidth
                    + ", but was given " + value.length() + " character(s): '" + value + "'. A BMS "
                    + "field cannot hold the surplus. To shorten the value deliberately, pass it "
                    + "through FixedWidthCodec.movePicX(value, " + declaredWidth + "), which "
                    + "truncates on the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * Rejects a page number {@code PIC 9(08)} cannot represent, returning it unchanged when it fits.
     *
     * <p>The picture is unsigned and has no sign position, so a negative value has no representation
     * in it at all, and a ninth digit has nowhere to go. Neither bound is a business rule: zero is
     * legitimate and expected, and no upper bound below {@value #CU00_PAGE_NUM_MAX} is imposed,
     * because {@code app/cbl/COUSR00C.cbl} sets the field to zero at line 227, increments it at lines
     * 308 and 319, and decrements it at line 367.
     */
    private static int requireCu00PageNum(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Field " + CU00_PAGE_NUM_FIELD + " is declared PIC 9("
                    + CU00_PAGE_NUM_LENGTH + "), which is unsigned and has no sign position, so it "
                    + "cannot hold the negative value " + value);
        }
        if (value > CU00_PAGE_NUM_MAX) {
            throw new IllegalArgumentException("Field " + CU00_PAGE_NUM_FIELD + " is declared PIC 9("
                    + CU00_PAGE_NUM_LENGTH + ") and cannot hold " + value + ", which needs more than "
                    + CU00_PAGE_NUM_LENGTH + " digits; the largest representable value is "
                    + CU00_PAGE_NUM_MAX);
        }
        return value;
    }

    /**
     * Defensively copies the rows into an immutable list of exactly {@value #ROW_COUNT} entries,
     * substituting blanks for an absent list or an absent element.
     *
     * <p>A list of any other size is rejected rather than padded or truncated, because the row count
     * is the screen's shape and not a page-size setting: ten row groups are declared in the mapset
     * and the program's loop bounds are hard-coded to match.
     */
    private static List<UserListRow> requireRows(List<UserListRow> rows) {
        if (rows == null) {
            return blankRows();
        }
        if (rows.size() != ROW_COUNT) {
            throw new IllegalArgumentException("Mapset " + MAPSET + " declares exactly " + ROW_COUNT
                    + " screen rows, so " + ROW_COUNT + " row(s) are required, but " + rows.size()
                    + " were given. The row count is behaviour fixed by the map and by COUSR00C's "
                    + "loop bounds, not a configurable page size");
        }
        List<UserListRow> copy = new ArrayList<>(ROW_COUNT);
        for (UserListRow current : rows) {
            if (current == null) {
                copy.add(UserListRow.blank());
            } else {
                copy.add(current);
            }
        }
        return List.copyOf(copy);
    }

    /**
     * Substitutes the cold-start communication area for an absent one.
     *
     * <p>{@code app/cbl/COUSR00C.cbl} line 113 tests {@code IF EIBCALEN = 0} - no communication area
     * was passed - and returns to the sign-on screen. A blank area is therefore a representable
     * state, and the shared type already provides it, so an absent context is filled in rather than
     * rejected.
     */
    private static NavigationContext requireContext(NavigationContext context) {
        if (context == null) {
            return NavigationContext.empty();
        }
        return context;
    }

    /**
     * Rejects a row number outside the one-based range the map declares, returning it unchanged when
     * it is in range.
     *
     * <p>One-based, because {@code POPULATE-USER-DATA}'s {@code EVALUATE WS-IDX} runs
     * {@code WHEN 1} through {@code WHEN 10}. The subtraction to a Java list index is performed by
     * the callers, in one line each.
     */
    private static int requireRowNumber(int rowNumber) {
        if (rowNumber < 1 || rowNumber > ROW_COUNT) {
            throw new IllegalArgumentException("Row number must be between 1 and " + ROW_COUNT
                    + " inclusive, counting from 1 as COUSR00C's WS-IDX does, but was " + rowNumber
                    + ". Java list indices are 0 to " + (ROW_COUNT - 1) + "; use rows() directly for "
                    + "those");
        }
        return rowNumber;
    }

    /**
     * Renders a row number as two digits, zero-padded: {@code 01} through {@code 10}.
     *
     * <p>Locale-independent by construction - {@code Integer.toString} always emits ASCII digits -
     * because a screen field label is a fixed identifier and must not vary with the default locale.
     * A formatter honouring the default locale could render other digit shapes and would silently
     * produce a label no {@code DFHMDF} carries. The selection column prefixes a further
     * {@code 00} to reach its {@value #SEL_FIELD_DIGITS} digits.
     *
     * <p>The bound below is the literal ten because it is where a number stops fitting in two
     * digits, which is arithmetic about the label rather than about the number of rows; the two
     * happen to coincide at {@value #ROW_COUNT} and are kept separate deliberately.
     */
    private static String twoDigits(int rowNumber) {
        if (rowNumber < 10) {
            return "0" + rowNumber;
        }
        return Integer.toString(rowNumber);
    }
}
