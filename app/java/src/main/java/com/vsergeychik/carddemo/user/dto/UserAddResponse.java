package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import java.util.List;

/**
 * The outbound payload of {@code POST /api/users} - the screen that CICS transaction {@code CU01}
 * paints through program {@code app/cbl/COUSR01C.cbl} and mapset {@code app/bms/COUSR01.bms}.
 *
 * <p>It is a field-for-field projection of the {@code xxxO} items of {@code 01 COUSR1AO} in
 * {@code app/cpy-bms/COUSR01.CPY}, plus the stateless navigation contract that replaces
 * {@code EXEC CICS XCTL}. Nothing else. This is a like-for-like migration of an existing screen, so
 * the field set, the field <em>order</em> and every declared width are transcribed rather than
 * designed.
 *
 * <h2>The twelve map-derived members, in this screen's own order</h2>
 *
 * <p>Every member below traces to one name-labelled {@code DFHMDF} definition in
 * {@code app/bms/COUSR01.bms} and to one {@code xxxO} item in {@code app/cpy-bms/COUSR01.CPY}. The
 * width column is the {@code xxxO} {@code PICTURE} clause, transcribed as a literal - never inferred
 * from a Java type, never derived from a sibling screen:
 *
 * <pre>
 *   #   member          xxxO item     PICTURE  DFHMDF label  POS       LENGTH  COLOR      CPY line
 *   --  --------------  ------------  -------  ------------  --------  ------  ---------  --------
 *    1  trnName         TRNNAMEO      X(4)     TRNNAME       (1,7)          4  BLUE           L98
 *    2  title01         TITLE01O      X(40)    TITLE01       (1,21)        40  YELLOW        L104
 *    3  curDate         CURDATEO      X(8)     CURDATE       (1,71)         8  BLUE          L110
 *    4  pgmName         PGMNAMEO      X(8)     PGMNAME       (2,7)          8  BLUE          L116
 *    5  title02         TITLE02O      X(40)    TITLE02       (2,21)        40  YELLOW        L122
 *    6  curTime         CURTIMEO      X(8)     CURTIME       (2,71)         8  BLUE          L128
 *    7  fName           FNAMEO        X(20)    FNAME         (8,18)        20  GREEN         L134
 *    8  lName           LNAMEO        X(20)    LNAME         (8,56)        20  GREEN         L140
 *    9  userId          USERIDO       X(8)     USERID        (11,15)        8  GREEN         L146
 *   10  passwd          PASSWDO       X(8)     PASSWD        (11,55)        8  GREEN         L152
 *   11  usrType         USRTYPEO      X(1)     USRTYPE       (14,17)        1  GREEN         L158
 *   12  errMsg          ERRMSGO       X(78)    ERRMSG        (23,1)        78  RED           L164
 * </pre>
 *
 * <p>Three independent counts agree on <strong>twelve</strong>, which is what makes the projection
 * provably complete:
 *
 * <ul>
 *   <li>{@code app/bms/COUSR01.bms} declares <strong>28</strong> {@code DFHMDF} fields of which
 *       exactly <strong>12</strong> carry a name label. The other sixteen are literal {@code INITIAL}
 *       screen furniture - {@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'},
 *       {@code 'Add User'}, {@code 'First Name:'}, {@code 'Last Name:'}, {@code 'User ID:'},
 *       {@code '(8 Char)'} twice, {@code 'Password:'}, {@code 'User Type: '},
 *       {@code '(A=Admin, U=User)'}, the {@code ENTER=Add User  F3=Back  F4=Clear  F12=Exit} legend
 *       and two {@code LENGTH=0} spacers. An unlabelled {@code DFHMDF} has no symbolic-map item at
 *       all, so it cannot become a payload member.</li>
 *   <li>The symbolic map declares <strong>12</strong> {@code xxxI} items and <strong>12</strong>
 *       {@code xxxO} items.</li>
 *   <li>{@link #MAP_DERIVED_FIELD_COUNT} states the number once, and
 *       {@link #MAP_DERIVED_FIELD_NAMES} and {@link #DFHMDF_FIELD_NAMES} enumerate both spellings in
 *       declaration order, so the correspondence stays mechanically checkable rather than a claim in
 *       prose.</li>
 * </ul>
 *
 * <h2>Three widths and one name that must not be regularised</h2>
 *
 * <p>Four details of this screen differ from a neighbouring screen that looks almost identical. Each
 * is transcribed from this screen's own source and each is left exactly as declared, because tidying
 * a field name or a width is precisely how a field-for-field diff stops being able to see a real
 * difference:
 *
 * <ol>
 *   <li><strong>The identity member is named for {@code USERID}, not {@code USRIDIN}.</strong>
 *       {@code app/bms/COUSR01.bms} labels the field {@code USERID} at line 111 and the symbolic map
 *       spells the items {@code USERIDL}, {@code USERIDF}, {@code USERIDA}, {@code USERIDI} and
 *       {@code USERIDO}. The user <em>update</em> and user <em>delete</em> screens label the same
 *       logical field {@code USRIDIN}. Both spellings are correct for their own screen and neither is
 *       harmonised towards the other.</li>
 *   <li><strong>{@link #CUR_TIME_LENGTH} is 8, not 9.</strong> {@code CURTIMEO PIC X(8)} carries
 *       {@code hh:mm:ss}. Exactly one mapset in the application - the sign-on screen - declares its
 *       time field nine characters wide, and that width belongs to that screen alone.</li>
 *   <li><strong>{@link #ERR_MSG_LENGTH} is 78, not 80.</strong> See the section below.</li>
 *   <li><strong>The order is this screen's own:</strong> {@code fName}, {@code lName},
 *       {@code userId}, {@code passwd}, {@code usrType}. The user update screen orders the same five
 *       fields {@code USRIDIN}, {@code FNAME}, {@code LNAME}, {@code PASSWD}, {@code USRTYPE} - its
 *       identity field comes first. The two orders are not interchangeable and neither is copied into
 *       the other.</li>
 * </ol>
 *
 * <h2>{@code errMsg} is 78 characters and this class truncates nothing</h2>
 *
 * <p>{@code app/cbl/COUSR01C.cbl:38} declares the working-storage message as
 * {@code WS-MESSAGE PIC X(80)} - see {@link #WS_MESSAGE_LENGTH} - while {@code ERRMSGO} is
 * {@code PIC X(78)}. Line 188 then performs {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR1AO}, and a
 * COBOL alphanumeric move truncates on the <em>right</em>, so the last two characters of an 80-column
 * message are dropped by the language itself.
 *
 * <p>That 80-to-78 narrowing is real observable behaviour and it belongs to the controller, which
 * performs it explicitly through {@code common.FixedWidthCodec.movePicX} so the direction of the
 * truncation is a reviewable decision rather than an accident. This class declares the target width
 * and does not enforce it: it neither pads nor truncates nor validates, and a value handed to it is
 * carried exactly as given. Placing a second, silent truncation here would hide the first.
 *
 * <h2>The {@code passwd} member: declared, and only ever spaces</h2>
 *
 * <p>{@code PASSWD} is one of the twelve name-labelled {@code DFHMDF} fields of this mapset, so the
 * member exists and the projection is 12 of 12. Its <em>content</em>, however, is narrower than the
 * name suggests, and the evidence is exhaustive: {@code grep -n 'PASSWD' app/cbl/COUSR01C.cbl}
 * returns exactly four hits and not one of them writes an outbound password.
 *
 * <pre>
 *   L136  WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES   the blank test, reading input
 *   L140  MOVE -1 TO PASSWDL OF COUSR1AI                    the cursor signal, a length item
 *   L157  MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD           input into the record about to be written
 *   L293  MOVE SPACES TO ... PASSWDI OF COUSR1AI ...        INITIALIZE-ALL-FIELDS blanks the field
 * </pre>
 *
 * <p>{@code grep -n 'PASSWDO' app/cbl/COUSR01C.cbl} returns <strong>nothing at all</strong>: the
 * program never names the output item. The only values that ever reach this field are therefore
 * {@code LOW-VALUES} - line 85's {@code MOVE LOW-VALUES TO COUSR1AO}, which blanks the whole map on
 * first entry - and spaces, from line 293. Writing {@code PASSWDI} is byte-identical to writing
 * {@code PASSWDO} because {@code 01 COUSR1AO REDEFINES COUSR1AI} at the same stride, which is why
 * line 293 is an outbound blank even though it names the input item. The screen reinforces the point
 * independently: {@code app/bms/COUSR01.bms:126} declares {@code PASSWD ATTRB=(DRK,FSET,UNPROT)}, and
 * {@code DRK} is the non-display attribute, so the field would render blank on a 3270 even if
 * something were moved into it.
 *
 * <p><strong>Do not generalise from a neighbouring screen.</strong> Three screens handle the password
 * field three different ways, all verified against source:
 *
 * <ul>
 *   <li>{@code COUSR01C} - this program - never writes an outbound password. The member is declared
 *       and stays blank.</li>
 *   <li>{@code COSGN00C} writes no outbound password either, and its mapset has no password output to
 *       populate, so the sign-on response deliberately carries no password member at all. It also
 *       upper-cases what it reads, at {@code app/cbl/COSGN00C.cbl:135}
 *       ({@code FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI)}).</li>
 *   <li>{@code COUSR02C} genuinely does place a stored password on its screen, at
 *       {@code app/cbl/COUSR02C.cbl:169} ({@code MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI}).</li>
 * </ul>
 *
 * <p>This class mirrors the first of those three. The member is kept because the map declares it;
 * it is never populated from the security file, never hashed, never encoded and never upper-cased,
 * because {@code COUSR01C} does none of those things. Removing the member, or "improving" it by
 * populating or protecting it, would each be a behaviour change in a migration whose whole purpose is
 * to avoid one. The plaintext handling of credentials throughout this application is an inherited
 * property of the legacy design and an explicit non-goal of this work: it is documented here so that
 * it stays visible, rather than quietly corrected in a payload class where no reviewer would look for
 * it.
 *
 * <h2>The navigation contract: there is no server-side session</h2>
 *
 * <p>CICS is pseudo-conversational. A transaction paints a screen, ends, and is re-entered from the
 * top when the user presses a key; the only state that survives is what the program handed back in
 * its communication area. {@code COUSR01C} does exactly that at lines 107 to 110 -
 * {@code EXEC CICS RETURN TRANSID(WS-TRANID) COMMAREA(CARDDEMO-COMMAREA)} - so the migration carries
 * the same state in the payload and keeps the endpoint stateless.
 *
 * <p>{@link #navigationContext} is that communication area, {@code 01 CARDDEMO-COMMAREA} of
 * {@code app/cpy/COCOM01Y.cpy}, exactly {@value NavigationContext#COMMAREA_LENGTH} bytes wide and
 * shared by all seventeen online programs. It is <em>referenced</em>, never widened: a field added to
 * it would change the byte image of every other screen in the application.
 *
 * <p>{@link #nextProgram}, {@link #nextMapset} and {@link #nextMap} turn a program transfer into
 * client-driven navigation. {@code COUSR01C} transfers control at lines 175 to 178,
 * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM) COMMAREA(CARDDEMO-COMMAREA)}, and the target is set
 * beforehand: {@code 'COSGN00C'} when the program is entered with no communication area (line 79),
 * {@code 'COADM01C'} when {@code PF3} is pressed (line 94), and {@code 'COSGN00C'} again as the
 * fallback for a blank target (lines 167 to 169). The response names the target and the client issues
 * the follow-up call; there is no server-side forward, no redirect chain and no session affinity.
 *
 * <p>These four members are the one deliberate exception to the rule that every member traces to a
 * {@code DFHMDF} definition. They exist because statelessness requires them, and they are the reason
 * this response can be served without a session.
 *
 * <p>A note on provenance, recorded rather than resolved. The migration plan enumerates eight
 * {@code XCTL} sites and {@code COUSR01C:176} is not among them, but the source is unambiguous:
 * {@code app/cbl} contains <strong>25</strong> executable {@code XCTL} statements plus four
 * commented-out ones, and this program owns one of the twenty-five. The plan's count is narrower than
 * the source; the source is the oracle, so this response carries the navigation members. The
 * discrepancy is documented here instead of being silently reconciled.
 *
 * <p>Statelessness here is structural rather than a matter of trust, so it can be confirmed
 * mechanically: this class declares nothing beyond the sixteen components and the constants below. It
 * holds no servlet session, no session-scoped or request-scoped attribute, no server-side cache keyed
 * by user or terminal, no thread-bound storage and no static mutable state of any kind - and a search
 * of the file for any such construct, by its type or annotation name, finds none. The names are
 * deliberately not spelled out even to disclaim them, so that the search stays unambiguous. Being an
 * immutable record additionally means nothing here can change underneath a caller that already holds
 * it.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p><strong>No attribute, colour, highlight or length item.</strong> Each field in the symbolic map
 * has six siblings beside its {@code xxxO} data item and not one of them is a payload member:
 *
 * <table border="1">
 *   <caption>Symbolic-map items that are metadata, never payload</caption>
 *   <tr><th>Item</th><th>PICTURE</th><th>What it is</th><th>Why it is not a member</th></tr>
 *   <tr><td>{@code xxxC}</td><td>{@code X}</td><td>Colour</td>
 *       <td>The direct target of {@code MOVE DFHGREEN TO ERRMSGC OF COUSR1AO} at
 *           {@code app/cbl/COUSR01C.cbl:254}, which turns the success message green. Presentation
 *           attributes belong to {@code common.BmsAttributes} and
 *           {@code common.FieldAttributeSetter}; if a client needs the colour it travels as
 *           clearly-named metadata, not as a payload field.</td></tr>
 *   <tr><td>{@code xxxH}</td><td>{@code X}</td><td>Highlight</td>
 *       <td>Presentation only - the mapset's {@code HILIGHT=UNDERLINE} on the five input
 *           fields.</td></tr>
 *   <tr><td>{@code xxxP}</td><td>{@code X}</td><td>Programmed symbols</td><td>Presentation only.</td></tr>
 *   <tr><td>{@code xxxV}</td><td>{@code X}</td><td>Validation byte</td><td>Terminal-level, not data.</td></tr>
 *   <tr><td>{@code xxxL}</td><td>{@code COMP PIC S9(4)}</td><td>Reported input length, and the cursor
 *           signal</td>
 *       <td>An inbound concern on the {@code AI} side. {@code COUSR01C} writes {@code -1} into it to
 *           position the cursor - {@code MOVE -1 TO PASSWDL} at line 140 and
 *           {@code MOVE -1 TO FNAMEL} at lines 86, 100, 122, 149, 272 and 289. A cursor position is
 *           not a value.</td></tr>
 *   <tr><td>{@code xxxF} and {@code xxxA}</td><td>{@code X}</td><td>Flag byte, and the
 *           {@code REDEFINES} attribute view of it</td>
 *       <td>Attribute metadata used when setting field highlighting.</td></tr>
 * </table>
 *
 * <p><strong>No filler.</strong> The symbolic map opens with the twelve-byte {@code TIOAPFX} filler
 * ({@code 02 FILLER PIC X(12)}, line 92 of the {@code AO} group) and prefixes every field with
 * {@code 02 FILLER PICTURE X(3)}. Those spans are reserved storage in a 3270 buffer, not information,
 * and they are not exposed. They are what makes the {@code REDEFINES} balance: the {@code AI} side
 * spends {@code 2 + 1 + 4} bytes before each data item and the {@code AO} side spends
 * {@code 3 + 1 + 1 + 1 + 1}, so both strides are {@code 7 + n} and the two views overlay exactly.
 *
 * <p><strong>No business logic.</strong> This class composes no message, chooses no colour, calls no
 * repository and reaches no verdict. {@code user.UserAddController} owns all of it: the ordered
 * five-message blank chain of {@code app/cbl/COUSR01C.cbl:117-151} ({@code 'First Name can NOT be
 * empty...'}, then last name, then user id, then password, then user type, in that order and with the
 * first match winning), the write to the security file at lines 240 to 248, the success message
 * {@code STRING 'User ' / SEC-USR-ID DELIMITED BY SPACE / ' has been added ...'} at lines 255 to 258,
 * the shared duplicate-key arm yielding {@code 'User ID already exist...'} at lines 260 to 266, and
 * the {@code 'Unable to Add User...'} fallback at lines 267 to 273. This file is the contract those
 * decisions are written onto.
 *
 * <p><strong>No serialisation policy.</strong> {@code config.WebConfig} owns Jackson configuration
 * centrally: property names stay untransformed, and trimming, empty-string-to-null coercion and
 * null-or-empty exclusion are all refused, so a space-padded {@code PIC X(n)} value survives a round
 * trip intact. That matters most for {@link #passwd}, whose only legitimate value is eight spaces and
 * which must arrive as eight spaces rather than as {@code null}, as {@code ""}, or omitted. This class
 * therefore carries no property rename, no naming strategy, no inclusion rule, no ignore marker and no
 * custom serialiser: a second policy stated locally could only contradict the central one. A Java
 * record's components are its JSON properties, so the wire form is exactly the sixteen members below.
 *
 * <p><strong>No validation annotations.</strong> A response is not validated, and a presence
 * constraint here would be actively wrong: {@code app/cbl/COUSR01C.cbl} answers each empty field with
 * a specific message at lines 120, 126, 132, 138 and 144 and re-paints the screen. It never rejects
 * the request. Annotating a member so that a blank value produced a {@code 400} would replace a
 * message the user is meant to read with a failure they are not, which is a change in observable
 * behaviour. The declared widths are published as the constants below instead, where they document the
 * contract without changing it.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // The controller builds the response once, positionally, then derives variants.
 * UserAddResponse response = new UserAddResponse(UserAddResponse.TRANSACTION_ID,
 *                                                ScreenTitles.CCDA_TITLE01,
 *                                                header.currentDateMmDdYy(),
 *                                                UserAddResponse.PROGRAM_NAME,
 *                                                ScreenTitles.CCDA_TITLE02,
 *                                                header.currentTimeHhMmSs(),
 *                                                request.fName(),
 *                                                request.lName(),
 *                                                request.userId(),
 *                                                request.passwd(),
 *                                                request.usrType(),
 *                                                codec.movePicX(message, UserAddResponse.ERR_MSG_LENGTH),
 *                                                context,
 *                                                "", "", "");
 *
 * // PF3 hands control back to the administration menu - COUSR01C:94 and :176.
 * response = response.withNextProgram("COADM01C");
 *
 * // A successful add blanks the five data fields, exactly as INITIALIZE-ALL-FIELDS does.
 * response = response.withFName("").withLName("").withUserId("").withPasswd("").withUsrType("");
 * </pre>
 *
 * @param trnName           {@code TRNNAMEO PIC X(4)}: the transaction identifier, always
 *                          {@value #TRANSACTION_ID}, written by
 *                          {@code app/cbl/COUSR01C.cbl:220} from {@code WS-TRANID}
 * @param title01           {@code TITLE01O PIC X(40)}: the first title line, written at line 218
 *                          from {@code CCDA-TITLE01} of {@code app/cpy/COTTL01Y.cpy} - see
 *                          {@code common.ScreenTitles}
 * @param curDate           {@code CURDATEO PIC X(8)}: the current date as {@code MM/DD/YY},
 *                          composed at lines 223 to 227 - see {@code common.DateHeader}
 * @param pgmName           {@code PGMNAMEO PIC X(8)}: the program identifier, always
 *                          {@value #PROGRAM_NAME}, written at line 221 from {@code WS-PGMNAME}
 * @param title02           {@code TITLE02O PIC X(40)}: the second title line, written at line 219
 *                          from {@code CCDA-TITLE02}
 * @param curTime           {@code CURTIMEO PIC X(8)}: the current time as {@code hh:mm:ss}, composed
 *                          at lines 229 to 233. Eight characters, not nine
 * @param fName             {@code FNAMEO PIC X(20)}: the first name, the same width as
 *                          {@code SEC-USR-FNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy}
 * @param lName             {@code LNAMEO PIC X(20)}: the last name, matching
 *                          {@code SEC-USR-LNAME PIC X(20)}
 * @param userId            {@code USERIDO PIC X(8)}: the user identifier, matching
 *                          {@code SEC-USR-ID PIC X(08)}. Named for {@code USERID}, not
 *                          {@code USRIDIN}
 * @param passwd            {@code PASSWDO PIC X(8)}: the password field, matching
 *                          {@code SEC-USR-PWD PIC X(08)}. {@code COUSR01C} never writes a value into
 *                          it, so in practice it carries only spaces - see the section above before
 *                          removing or repurposing it
 * @param usrType           {@code USRTYPEO PIC X(1)}: the user type, matching
 *                          {@code SEC-USR-TYPE PIC X(01)}: {@code 'A'} for an administrator,
 *                          {@code 'U'} for a regular user
 * @param errMsg            {@code ERRMSGO PIC X(78)}: the message line, written at line 188 from the
 *                          {@code PIC X(80)} {@code WS-MESSAGE}. Seventy-eight characters, not eighty
 * @param navigationContext {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}: the
 *                          conversation state returned at lines 107 to 110. Not a {@code DFHMDF}
 *                          field - it is the carrier that replaces server-side session state
 * @param nextProgram       the program to transfer to, replacing
 *                          {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at line 176:
 *                          {@code 'COSGN00C'} at lines 79 and 168, {@code 'COADM01C'} at line 94. Not
 *                          a {@code DFHMDF} field
 * @param nextMapset        the mapset the client should render next, {@value #MAPSET_NAME} for this
 *                          screen. Not a {@code DFHMDF} field
 * @param nextMap           the map the client should render next, {@value #MAP_NAME} for this screen.
 *                          Not a {@code DFHMDF} field
 */
public record UserAddResponse(String trnName,
                              String title01,
                              String curDate,
                              String pgmName,
                              String title02,
                              String curTime,
                              String fName,
                              String lName,
                              String userId,
                              String passwd,
                              String usrType,
                              String errMsg,
                              NavigationContext navigationContext,
                              String nextProgram,
                              String nextMapset,
                              String nextMap) {

    // =================================================================================================
    // The COBOL names, carried VERBATIM as app/cpy-bms/COUSR01.CPY spells them. These are the names a
    // field-for-field differ keys its comparison by, so a "tidied" spelling would make a real
    // difference invisible. Each is the xxxO output item; the paired xxxI input item differs only in
    // its final letter, and the two overlay the same storage because 01 COUSR1AO REDEFINES COUSR1AI.
    // =================================================================================================

    /** Symbolic-map name of {@link #trnName()}: {@code TRNNAMEO}, {@code COUSR01.CPY} line 98. */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /** Symbolic-map name of {@link #title01()}: {@code TITLE01O}, {@code COUSR01.CPY} line 104. */
    public static final String TITLE01_FIELD = "TITLE01O";

    /** Symbolic-map name of {@link #curDate()}: {@code CURDATEO}, {@code COUSR01.CPY} line 110. */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /** Symbolic-map name of {@link #pgmName()}: {@code PGMNAMEO}, {@code COUSR01.CPY} line 116. */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /** Symbolic-map name of {@link #title02()}: {@code TITLE02O}, {@code COUSR01.CPY} line 122. */
    public static final String TITLE02_FIELD = "TITLE02O";

    /** Symbolic-map name of {@link #curTime()}: {@code CURTIMEO}, {@code COUSR01.CPY} line 128. */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /** Symbolic-map name of {@link #fName()}: {@code FNAMEO}, {@code COUSR01.CPY} line 134. */
    public static final String F_NAME_FIELD = "FNAMEO";

    /** Symbolic-map name of {@link #lName()}: {@code LNAMEO}, {@code COUSR01.CPY} line 140. */
    public static final String L_NAME_FIELD = "LNAMEO";

    /**
     * Symbolic-map name of {@link #userId()}: {@code USERIDO}, {@code COUSR01.CPY} line 146.
     *
     * <p>{@code USERID}, not {@code USRIDIN}. The user update and user delete mapsets label their
     * equivalent field {@code USRIDIN}; this screen labels it {@code USERID} at
     * {@code app/bms/COUSR01.bms:111}, and the two spellings are not harmonised.
     */
    public static final String USER_ID_FIELD = "USERIDO";

    /**
     * Symbolic-map name of {@link #passwd()}: {@code PASSWDO}, {@code COUSR01.CPY} line 152.
     *
     * <p>{@code app/cbl/COUSR01C.cbl} never names this item - the four {@code PASSWD} hits in that
     * program are at lines 136, 140, 157 and 293 and every one of them concerns {@code PASSWDI} or
     * {@code PASSWDL}. The field is declared here because the mapset declares it.
     */
    public static final String PASSWD_FIELD = "PASSWDO";

    /** Symbolic-map name of {@link #usrType()}: {@code USRTYPEO}, {@code COUSR01.CPY} line 158. */
    public static final String USR_TYPE_FIELD = "USRTYPEO";

    /** Symbolic-map name of {@link #errMsg()}: {@code ERRMSGO}, {@code COUSR01.CPY} line 164. */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    // =================================================================================================
    // Declared widths, one named constant per xxxO item, each transcribed as a literal from its
    // PICTURE clause. Every caller that needs to shorten a value to a field width states that width
    // through one of these rather than repeating a number, and no width is ever inferred from a Java
    // type or borrowed from a neighbouring screen.
    // =================================================================================================

    /** Declared width of {@code TRNNAMEO PIC X(4)}; {@code DFHMDF LENGTH=4} at {@code POS=(1,7)}. */
    public static final int TRN_NAME_LENGTH = 4;

    /** Declared width of {@code TITLE01O PIC X(40)}; {@code DFHMDF LENGTH=40} at {@code POS=(1,21)}. */
    public static final int TITLE01_LENGTH = 40;

    /** Declared width of {@code CURDATEO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(1,71)}. */
    public static final int CUR_DATE_LENGTH = 8;

    /** Declared width of {@code PGMNAMEO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(2,7)}. */
    public static final int PGM_NAME_LENGTH = 8;

    /** Declared width of {@code TITLE02O PIC X(40)}; {@code DFHMDF LENGTH=40} at {@code POS=(2,21)}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Declared width of {@code CURTIMEO PIC X(8)} - <strong>eight</strong>, not nine;
     * {@code DFHMDF LENGTH=8} at {@code POS=(2,71)} with {@code INITIAL='hh:mm:ss'}.
     *
     * <p>The sign-on mapset is the one screen in the application whose time field is nine characters
     * wide. That width belongs to that screen and must not be carried over to this one.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Declared width of {@code FNAMEO PIC X(20)}; {@code DFHMDF LENGTH=20} at {@code POS=(8,18)}.
     * It matches {@code SEC-USR-FNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy}, so the screen field
     * and the stored field are the same width and the move between them loses nothing.
     */
    public static final int F_NAME_LENGTH = 20;

    /**
     * Declared width of {@code LNAMEO PIC X(20)}; {@code DFHMDF LENGTH=20} at {@code POS=(8,56)}.
     * It matches {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int L_NAME_LENGTH = 20;

    /**
     * Declared width of {@code USERIDO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(11,15)}.
     * It matches {@code SEC-USR-ID PIC X(08)}, which is also the key length the write at
     * {@code app/cbl/COUSR01C.cbl:245} passes as {@code KEYLENGTH(LENGTH OF SEC-USR-ID)}.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Declared width of {@code PASSWDO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(11,55)},
     * declared {@code ATTRB=(DRK,FSET,UNPROT)} - {@code DRK} being the non-display attribute.
     * It matches {@code SEC-USR-PWD PIC X(08)}, which the legacy design stores in plaintext.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * Declared width of {@code USRTYPEO PIC X(1)}; {@code DFHMDF LENGTH=1} at {@code POS=(14,17)}.
     * It matches {@code SEC-USR-TYPE PIC X(01)}, whose two documented values the screen legend gives
     * as {@code '(A=Admin, U=User)'}.
     */
    public static final int USR_TYPE_LENGTH = 1;

    /**
     * Declared width of {@code ERRMSGO PIC X(78)} - <strong>seventy-eight</strong>, not eighty;
     * {@code DFHMDF LENGTH=78} at {@code POS=(23,1)}, one full line of an eighty-column screen less
     * the two columns the map reserves.
     *
     * <p>{@code app/cbl/COUSR01C.cbl:188} moves the {@value #WS_MESSAGE_LENGTH}-character
     * {@code WS-MESSAGE} into this field, and a COBOL alphanumeric move truncates on the right, so the
     * final two characters of a full-width message are discarded. The controller performs that
     * narrowing explicitly through {@code common.FixedWidthCodec.movePicX}; this class only publishes
     * the target width.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Declared width of {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR01C.cbl:38} - the
     * <em>source</em> of {@link #errMsg()}, published so the two-character narrowing at line 188 is
     * stated rather than left to be rediscovered. This is not a field of this payload.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    // =================================================================================================
    // Completeness of the projection, stated as data so it can be asserted rather than believed.
    // =================================================================================================

    /**
     * The number of members that derive from a screen field: <strong>12</strong>.
     *
     * <p>{@code app/bms/COUSR01.bms} declares 28 {@code DFHMDF} fields of which exactly 12 carry a
     * name label, and {@code app/cpy-bms/COUSR01.CPY} declares 12 {@code xxxI} items and 12
     * {@code xxxO} items. All three counts agree. The four navigation members are additional and
     * deliberate, and are not counted here.
     */
    public static final int MAP_DERIVED_FIELD_COUNT = 12;

    /**
     * The twelve {@code xxxO} item names of {@code 01 COUSR1AO}, in symbolic-map declaration order,
     * which is also the declaration order of the members of this record.
     *
     * <p>Immutable, and the counterpart of {@link #DFHMDF_FIELD_NAMES}: the two lists are the same
     * length and the entry at each index describes the same screen field under its two spellings.
     */
    public static final List<String> MAP_DERIVED_FIELD_NAMES = List.of(TRN_NAME_FIELD,
                                                                       TITLE01_FIELD,
                                                                       CUR_DATE_FIELD,
                                                                       PGM_NAME_FIELD,
                                                                       TITLE02_FIELD,
                                                                       CUR_TIME_FIELD,
                                                                       F_NAME_FIELD,
                                                                       L_NAME_FIELD,
                                                                       USER_ID_FIELD,
                                                                       PASSWD_FIELD,
                                                                       USR_TYPE_FIELD,
                                                                       ERR_MSG_FIELD);

    /**
     * The twelve name-labelled {@code DFHMDF} labels of {@code app/bms/COUSR01.bms}, in mapset
     * declaration order.
     *
     * <p>This is the list that
     * {@code grep -E '^[A-Z0-9]+ +DFHMDF' app/bms/COUSR01.bms | awk '{print $1}'} prints, transcribed
     * so the traceability of every payload member back to a screen field is data in this file rather
     * than a claim about a file somewhere else. Note {@code USERID} at index 8: this mapset does not
     * spell it {@code USRIDIN}.
     */
    public static final List<String> DFHMDF_FIELD_NAMES = List.of("TRNNAME",
                                                                  "TITLE01",
                                                                  "CURDATE",
                                                                  "PGMNAME",
                                                                  "TITLE02",
                                                                  "CURTIME",
                                                                  "FNAME",
                                                                  "LNAME",
                                                                  "USERID",
                                                                  "PASSWD",
                                                                  "USRTYPE",
                                                                  "ERRMSG");

    /**
     * The declared widths of the twelve map-derived members, in the same order as
     * {@link #MAP_DERIVED_FIELD_NAMES} and {@link #DFHMDF_FIELD_NAMES}.
     *
     * <p>Every entry is the {@code xxxO} {@code PICTURE} width and equals the paired {@code DFHMDF}
     * {@code LENGTH}, which is the mapset and the symbolic map agreeing with each other.
     */
    public static final List<Integer> MAP_DERIVED_FIELD_LENGTHS = List.of(TRN_NAME_LENGTH,
                                                                         TITLE01_LENGTH,
                                                                         CUR_DATE_LENGTH,
                                                                         PGM_NAME_LENGTH,
                                                                         TITLE02_LENGTH,
                                                                         CUR_TIME_LENGTH,
                                                                         F_NAME_LENGTH,
                                                                         L_NAME_LENGTH,
                                                                         USER_ID_LENGTH,
                                                                         PASSWD_LENGTH,
                                                                         USR_TYPE_LENGTH,
                                                                         ERR_MSG_LENGTH);

    // =================================================================================================
    // Screen identity. Four literals the program moves into the header on every send, transcribed
    // from their WORKING-STORAGE and EXEC CICS declarations so no controller has to retype them.
    // =================================================================================================

    /**
     * The CICS transaction identifier, {@code 'CU01'}: {@code WS-TRANID PIC X(04) VALUE 'CU01'} at
     * {@code app/cbl/COUSR01C.cbl:37}, moved into {@code TRNNAMEO} at line 220 and returned as the
     * next transaction at line 108.
     *
     * <p>Exactly {@value #TRN_NAME_LENGTH} characters, matching {@link #TRN_NAME_LENGTH}.
     */
    public static final String TRANSACTION_ID = "CU01";

    /**
     * The program identifier, {@code 'COUSR01C'}: {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} at
     * {@code app/cbl/COUSR01C.cbl:36}, moved into {@code PGMNAMEO} at line 221 and into
     * {@code CDEMO-FROM-PROGRAM} at line 171.
     *
     * <p>Exactly {@value #PGM_NAME_LENGTH} characters, matching {@link #PGM_NAME_LENGTH}: a COBOL
     * program name in this application is eight characters.
     */
    public static final String PROGRAM_NAME = "COUSR01C";

    /**
     * The BMS map, {@code 'COUSR1A'}: the {@code MAP('COUSR1A')} operand of the send at
     * {@code app/cbl/COUSR01C.cbl:191} and of the receive at line 204, declared as
     * {@code COUSR1A DFHMDI} in {@code app/bms/COUSR01.bms:26}.
     *
     * <p>Seven characters, which is why {@link #NEXT_MAP_LENGTH} is 7 and not 8: the symbolic-map
     * group items are formed by appending a one-character direction suffix, giving {@code COUSR1AI}
     * for the input view and {@code COUSR1AO} for the output view. The eighth character belongs to the
     * suffix, so a map name cannot occupy it.
     */
    public static final String MAP_NAME = "COUSR1A";

    /**
     * The BMS mapset, {@code 'COUSR01'}: the {@code MAPSET('COUSR01')} operand at
     * {@code app/cbl/COUSR01C.cbl:192} and line 205, declared as {@code COUSR01 DFHMSD} in
     * {@code app/bms/COUSR01.bms:19}.
     *
     * <p>Seven characters, for the same reason as {@link #MAP_NAME}.
     */
    public static final String MAPSET_NAME = "COUSR01";

    // =================================================================================================
    // Widths of the three navigation members. Each delegates to the constant NavigationContext already
    // publishes for the corresponding COMMAREA field, so the two classes cannot drift apart and the
    // program-versus-map asymmetry - 8 against 7 - is stated in exactly one place.
    // =================================================================================================

    /**
     * Declared width of {@link #nextProgram()}: {@value #NEXT_PROGRAM_LENGTH}, taken from
     * {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:24} - the very field
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COUSR01C.cbl:176} transfers through.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Declared width of {@link #nextMapset()}: {@value #NEXT_MAPSET_LENGTH}, taken from
     * {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:44}. Seven, not eight - see
     * {@link #MAP_NAME}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Declared width of {@link #nextMap()}: {@value #NEXT_MAP_LENGTH}, taken from
     * {@code CDEMO-LAST-MAP PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:43}. Seven, not eight - see
     * {@link #MAP_NAME}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * What {@link #toString()} prints in place of {@link #passwd()}.
     *
     * <p>A diagnostic rendering is not part of the COBOL contract - there is no {@code toString} in
     * {@code COUSR01C} to be faithful to - so masking here changes no observable behaviour of the
     * migrated program. It is a deliberate, narrow guard against the one place a payload class can
     * leak a credential by accident: a log line, an assertion message or a stack trace that
     * interpolates the whole object. The value itself is never altered, never hashed and never
     * encoded; {@link #passwd()} returns exactly what was supplied, and the JSON form is untouched.
     */
    private static final String PASSWD_NOT_RENDERED = "<not rendered>";

    // =================================================================================================
    // Derivation. Sixteen positional components are easy to transpose, and two members of the header
    // and two of the five data fields are the same width as a neighbour, so a transposition would not
    // even fail to compile - it would produce a wrong screen. Each method below returns a new instance
    // with exactly one member replaced, naming the member it changes.
    //
    // These are the whole of the behaviour of this class. Not one of them inspects a value, tests a
    // condition, pads, truncates, trims, normalises case, substitutes a default or invents a blank:
    // a value handed in is the value carried, and every one of those transformations belongs to the
    // controller or to common.FixedWidthCodec, where it is reviewable. There is deliberately no
    // "blank" or "empty" factory either, because COUSR01C blanks this map two different ways -
    // MOVE LOW-VALUES TO COUSR1AO at line 85 on first entry, and MOVE SPACES to the five data items at
    // lines 290 to 294 in INITIALIZE-ALL-FIELDS - so any single factory would have to choose one of
    // them and would thereby take a decision that belongs to the caller.
    //
    // Immutability is what makes this safe: a response already handed to a collaborator cannot be
    // changed underneath it, and none of these methods mutates anything.
    // =================================================================================================

    /**
     * Returns a copy with {@code TRNNAMEO} replaced.
     *
     * @param trnName the transaction identifier, {@value #TRN_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withTrnName(String trnName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code TITLE01O} replaced.
     *
     * @param title01 the first title line, {@value #TITLE01_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withTitle01(String title01) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code CURDATEO} replaced.
     *
     * @param curDate the current date as {@code MM/DD/YY}, {@value #CUR_DATE_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withCurDate(String curDate) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code PGMNAMEO} replaced.
     *
     * @param pgmName the program identifier, {@value #PGM_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withPgmName(String pgmName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code TITLE02O} replaced.
     *
     * @param title02 the second title line, {@value #TITLE02_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withTitle02(String title02) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code CURTIMEO} replaced.
     *
     * @param curTime the current time as {@code hh:mm:ss}, {@value #CUR_TIME_LENGTH} characters - not
     *                nine
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withCurTime(String curTime) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code FNAMEO} replaced.
     *
     * @param fName the first name, {@value #F_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withFName(String fName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code LNAMEO} replaced.
     *
     * @param lName the last name, {@value #L_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withLName(String lName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code USERIDO} replaced.
     *
     * @param userId the user identifier, {@value #USER_ID_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withUserId(String userId) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code PASSWDO} replaced.
     *
     * <p>Present for completeness of the twelve-field projection, and to let a caller blank the field
     * as {@code INITIALIZE-ALL-FIELDS} does at {@code app/cbl/COUSR01C.cbl:293}. It is emphatically
     * <strong>not</strong> an invitation to echo a stored password: {@code COUSR01C} never writes one,
     * and doing so here would be a behaviour change. The value is stored exactly as supplied - never
     * hashed, never encoded, never upper-cased.
     *
     * @param passwd the password field, {@value #PASSWD_LENGTH} characters; in practice spaces
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withPasswd(String passwd) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code USRTYPEO} replaced.
     *
     * @param usrType the user type, {@value #USR_TYPE_LENGTH} character: {@code 'A'} or {@code 'U'}
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withUsrType(String usrType) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with {@code ERRMSGO} replaced.
     *
     * <p>The message is stored exactly as supplied. A value longer than {@value #ERR_MSG_LENGTH}
     * characters is <strong>not</strong> shortened here: the 80-to-78 narrowing of
     * {@code app/cbl/COUSR01C.cbl:188} belongs to the controller and is performed there through
     * {@code common.FixedWidthCodec.movePicX}, so that the truncation appears once, deliberately, and
     * in a reviewable place.
     *
     * @param errMsg the message line, {@value #ERR_MSG_LENGTH} characters - not eighty
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withErrMsg(String errMsg) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the communication area replaced.
     *
     * @param navigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte
     *                          {@code CARDDEMO-COMMAREA} handed back to the client
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNavigationContext(NavigationContext navigationContext) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the transfer target replaced - the stateless form of
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COUSR01C.cbl:176}.
     *
     * @param nextProgram the program the client should call next, {@value #NEXT_PROGRAM_LENGTH}
     *                    characters: {@code 'COSGN00C'} for the sign-on screen or {@code 'COADM01C'}
     *                    for the administration menu
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNextProgram(String nextProgram) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the target mapset replaced.
     *
     * @param nextMapset the mapset the client should render next, {@value #NEXT_MAPSET_LENGTH}
     *                   characters - seven, not eight
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNextMapset(String nextMapset) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the target map replaced.
     *
     * @param nextMap the map the client should render next, {@value #NEXT_MAP_LENGTH} characters -
     *                seven, not eight
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNextMap(String nextMap) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * A diagnostic rendering of every member except {@link #passwd()}, which is replaced by
     * a fixed placeholder.
     *
     * <p>The record's generated {@code toString} would interpolate the password, and a payload object
     * reaches log lines, assertion failures and stack traces, so this override exists solely to keep a
     * credential out of them. It is a diagnostics decision, not a change to the data: the value is
     * stored and returned untouched, {@link #equals(Object)} and {@link #hashCode()} keep their
     * generated behaviour and still consider it, and the JSON form still carries it.
     *
     * @return a rendering safe to log
     */
    @Override
    public String toString() {
        return "UserAddResponse[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", fName=" + fName
                + ", lName=" + lName
                + ", userId=" + userId
                + ", passwd=" + PASSWD_NOT_RENDERED
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + "]";
    }
}
