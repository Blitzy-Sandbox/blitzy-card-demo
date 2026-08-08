package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;

/**
 * The inbound payload of {@code DELETE /api/users/{userId}} - the REST projection of the
 * <em>input</em> view of the BMS screen driven by CICS transaction {@code CU03}, program
 * {@code app/cbl/COUSR03C.cbl} ("Delete a user from USRSEC file", line 5).
 *
 * <p>Every payload member below is a field-for-field transcription of an {@code xxxI} item of
 * {@code 01 COUSR3AI} in {@code app/cpy-bms/COUSR03.CPY}. This type carries data and nothing else:
 * there is no confirmation check, no message composition, no repository call and no arithmetic in
 * it. Those belong to the controller and the service that reproduce {@code COUSR03C}'s paragraphs.
 *
 * <h2>The screen contract, measured rather than assumed</h2>
 *
 * {@code app/cpy-bms/COUSR03.CPY} declares {@code 01 COUSR3AI.} at line 17. It opens with the
 * twelve-byte {@code 02 FILLER PIC X(12)} that {@code TIOAPFX=YES} demands, then repeats a
 * five-item group once per screen field:
 *
 * <pre>
 *  02  TRNNAMEL    COMP  PIC  S9(4).     &lt;- reported input length, and the cursor signal
 *  02  TRNNAMEF    PICTURE X.            &lt;- the flag byte
 *  02  FILLER REDEFINES TRNNAMEF.
 *    03 TRNNAMEA    PICTURE X.           &lt;- the attribute view of that same byte
 *  02  FILLER   PICTURE X(4).            &lt;- reserved
 *  02  TRNNAMEI  PIC X(4).               &lt;- THE PAYLOAD FIELD
 * </pre>
 *
 * The stride is therefore {@code 7 + n}, and {@code 01 COUSR3AO REDEFINES COUSR3AI.} at line 85
 * lays its {@code FILLER X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} + {@code xxxV} +
 * {@code xxxO} groups over exactly the same storage. The arithmetic closes on both sides:
 *
 * <ul>
 *   <li>Sum of the eleven declared widths: {@code 4 + 40 + 8 + 8 + 40 + 8 + 8 + 20 + 20 + 1 + 78}
 *       = {@value #MAP_FIELDS_WIDTH_TOTAL}.</li>
 *   <li>Input view: {@code 12 + (11 x 7) + }{@value #MAP_FIELDS_WIDTH_TOTAL} =
 *       {@value #SYMBOLIC_MAP_LENGTH} bytes.</li>
 *   <li>Output view: {@code 12 + (11 x (3+1+1+1+1)) + }{@value #MAP_FIELDS_WIDTH_TOTAL} =
 *       {@value #SYMBOLIC_MAP_LENGTH} bytes.</li>
 * </ul>
 *
 * <p>Those two totals being equal is the mechanical reason the "Request takes {@code xxxI},
 * Response takes {@code xxxO}" split is a <strong>naming</strong> convention over a single field
 * set rather than two different shapes. {@code COUSR03C} exploits it directly: the successful
 * lookup writes its results through the <em>input</em> items -
 * {@code MOVE SEC-USR-FNAME TO FNAMEI OF COUSR3AI} at line 165, then {@code LNAMEI} at 166 and
 * {@code USRTYPEI} at 167 - and the subsequent {@code SEND MAP ... FROM(COUSR3AO)} at lines
 * 219-222 transmits those very bytes. Writing the {@code O} items instead would have been
 * byte-identical. That consequence is preserved, not tidied (practice <strong>B5</strong>): this
 * request type and its response twin {@code UserDeleteResponse} therefore carry the identical
 * eleven members under identical names, so a client can hand a response straight back as the next
 * request without translating anything.
 *
 * <h2>Eleven fields, and the twelfth that does not exist</h2>
 *
 * {@code app/bms/COUSR03.bms} contains 26 {@code DFHMDF} definitions of which exactly
 * {@value #MAP_FIELD_COUNT} carry a name label; the other fifteen are literal {@code INITIAL}
 * furniture - {@code 'Tran:'}, {@code 'Date:'}, {@code 'Delete User'}, the row of asterisks,
 * {@code '(A=Admin, U=User)'}, the {@code 'ENTER=Fetch  F3=Back  F4=Clear  F5=Delete'} legend and
 * three zero-length field stoppers - and none of them becomes a member here. The label list is
 * exhaustively:
 *
 * <pre>
 * $ grep -E '^[A-Z0-9]+ +DFHMDF' app/bms/COUSR03.bms | awk '{print $1}'
 * TRNNAME TITLE01 CURDATE PGMNAME TITLE02 CURTIME USRIDIN FNAME LNAME USRTYPE ERRMSG
 * </pre>
 *
 * <p><strong>There is no {@code PASSWD} field on this screen, and none is added here.</strong> The
 * absence is verified in all three authoritative places, not inferred from one:
 *
 * <pre>
 * $ grep -n 'PASSWD' app/cbl/COUSR03C.cbl        # exit 1, no output
 * $ grep -n 'PASSWD' app/bms/COUSR03.bms         # exit 1, no output
 * $ grep -n 'PASSWD' app/cpy-bms/COUSR03.CPY     # exit 1, no output
 * </pre>
 *
 * That single absence is the whole reason this screen has {@value #MAP_FIELD_COUNT} name-labelled
 * fields where {@code COUSR02} - the update screen, whose {@code UserUpdateRequest} a reviewer will
 * naturally read beside this file - has twelve. Run the same grep over the sibling mapsets and the
 * contrast is explicit: {@code COUSR02.bms} yields
 * {@code ... CURTIME USRIDIN FNAME LNAME PASSWD USRTYPE ERRMSG}, and {@code COUSR01.bms} yields
 * {@code ... CURTIME FNAME LNAME USERID PASSWD USRTYPE ERRMSG}. The two screens are otherwise
 * near-identical, which is precisely what makes "completing" this one dangerous. Adding a password
 * member for symmetry would invent a payload field with no {@code DFHMDF} behind it, so it would
 * fail the requirement that every field trace to a map definition, and it would change observable
 * behaviour in a migration whose entire premise is that behaviour does not change.
 *
 * <p>The 80-byte {@code SEC-USER-DATA} record of {@code app/cpy/CSUSR01Y.cpy} <em>does</em> declare
 * {@code SEC-USR-PWD PIC X(08)} at line 21. That is the <strong>record</strong> shape, owned by
 * {@code user.model.SecUserRecord}; this file owns the <strong>screen</strong> shape, and the
 * screen never displays or accepts the password. The two must not be conflated. The record's other
 * widths do line up with the screen's, which is what makes the lookup at lines 160-167 a plain
 * move: {@code SEC-USR-ID X(08)} into {@code USRIDINI PIC X(8)},
 * {@code SEC-USR-FNAME X(20)} into {@code FNAMEI PIC X(20)},
 * {@code SEC-USR-LNAME X(20)} into {@code LNAMEI PIC X(20)} and
 * {@code SEC-USR-TYPE X(01)} into {@code USRTYPEI PIC X(1)}.
 *
 * <h2>Only one of the eleven is actually typed by the user</h2>
 *
 * {@code USRIDIN} is declared {@code ATTRB=(FSET,IC,NORM,UNPROT)} with
 * {@code HILIGHT=UNDERLINE} at {@code app/bms/COUSR03.bms} lines 85-89 and is the sole unprotected
 * field on the map; {@code IC} places the cursor in it. Every other field is {@code ASKIP} -
 * {@code FNAME}, {@code LNAME} and {@code USRTYPE} are display-only and are filled by the lookup,
 * never keyed. They remain members of this request all the same, because the terminal transmits
 * the whole map back and because the field set is shared with the response, as shown above. A
 * client is expected to echo them; the server derives nothing from them.
 *
 * <h2>Two calls, no session: the confirm-then-delete flow</h2>
 *
 * {@code COUSR03C} is a confirm-then-delete transaction and its behaviour turns entirely on which
 * key was pressed, evaluated at lines 108-130:
 *
 * <table border="1">
 *   <caption>{@code EVALUATE EIBAID} in {@code app/cbl/COUSR03C.cbl} lines 108-130</caption>
 *   <tr><th>Key</th><th>Line</th><th>Effect</th></tr>
 *   <tr><td>{@code DFHENTER}</td><td>109-110</td>
 *       <td>{@code PROCESS-ENTER-KEY}: look the user up and answer
 *           {@code 'Press PF5 key to delete this user ...'} (line 283)</td></tr>
 *   <tr><td>{@code DFHPF3}</td><td>111-118</td><td>Return to the calling program</td></tr>
 *   <tr><td>{@code DFHPF4}</td><td>119-120</td><td>{@code CLEAR-CURRENT-SCREEN}</td></tr>
 *   <tr><td><strong>{@code DFHPF5}</strong></td><td>121-122</td>
 *       <td>{@code DELETE-USER-INFO}: re-read for update, then delete</td></tr>
 *   <tr><td>{@code DFHPF12}</td><td>123-125</td><td>Return to {@code COADM01C}</td></tr>
 *   <tr><td>{@code WHEN OTHER}</td><td>126-129</td><td>Invalid-key message, screen resent</td></tr>
 * </table>
 *
 * <p>Neither path is reachable without a faithful key indication on the request, which is why
 * {@link #aid()} is a member. Nothing else bridges the two calls: the client returns the payload it
 * was handed and the server holds no state between them. Accordingly this type is free of
 * {@code HttpSession}, {@code @SessionAttributes}, {@code @SessionScope}, {@code ThreadLocal}, any
 * static cache and any static mutable field of any kind - a static holder would be a session by
 * another name and would break request isolation besides.
 *
 * <h2>Nulls are meaningful, and are deliberately not normalised away</h2>
 *
 * A member may be {@code null}, and that is not an oversight. CICS delivers an untransmitted field
 * as {@code LOW-VALUES} with its {@code xxxL} length item at zero, and {@code COUSR03C} tests for
 * exactly that, twice, with an explicit disjunction:
 * {@code WHEN USRIDINI OF COUSR3AI = SPACES OR LOW-VALUES} at line 145 in the lookup path and
 * again at line 177 in the delete path. Both states genuinely occur and the program answers both
 * identically, so {@code null} is retained as the JSON projection of {@code LOW-VALUES} while a
 * run of spaces projects {@code SPACES}. Nothing here trims, coerces an empty string to
 * {@code null}, substitutes a default or rejects an absent value: doing any of those would erase a
 * distinction the source draws for itself.
 *
 * <p>For the same reason <strong>no member is annotated {@code @NotBlank} or {@code @NotNull}</strong>.
 * A blank user id is <em>valid input</em> to this transaction: lines 146-150 and 178-182 set the
 * error flag, move {@code 'User ID can NOT be empty...'} into the message and resend the screen.
 * Rejecting the request with an HTTP 400 instead would replace an observable answer with a
 * protocol error and change the business rule. {@code @Size(max = n)} <em>is</em> applied, one per
 * member, taken from the {@code xxxI} {@code PICTURE} clause, because a value wider than its field
 * has no representation on the screen at all.
 *
 * <p>Equally, no {@code @Pattern} constrains {@link #usrType()} even though the screen legend reads
 * {@code '(A=Admin, U=User)'}, no case normalisation is applied, and no format check touches
 * {@link #curDate()} or {@link #curTime()}. {@code COUSR03C} performs none of those, so neither
 * does this.
 *
 * <h2>What is deliberately absent from the payload</h2>
 *
 * <ul>
 *   <li>The {@code xxxL} length items, the {@code xxxF} flag bytes and their {@code xxxA} attribute
 *       redefinitions. {@code COUSR03C} uses them as control signals, not data:
 *       {@code MOVE -1 TO USRIDINL} at lines 98, 149, 152, 181, 184, 291, 327 and 351 positions the
 *       cursor, and {@code MOVE -1 TO FNAMEL} at lines 298 and 334 moves it on a lookup failure.</li>
 *   <li>The {@code xxxC} colour bytes of the output view, which the program drives directly -
 *       {@code MOVE DFHNEUTR TO ERRMSGC OF COUSR3AO} at line 285 for the confirmation prompt and
 *       {@code MOVE DFHGREEN TO ERRMSGC} at line 317 on success. Colour and highlighting belong to
 *       {@code common.BmsAttributes} and {@code common.FieldAttributeSetter}.</li>
 *   <li>The {@code xxxP}, {@code xxxH} and {@code xxxV} bytes of the output view.</li>
 *   <li>The twelve-byte {@code TIOAPFX} prefix and the eleven {@code FILLER PICTURE X(4)} spans,
 *       which are reserved storage and are exposed by nothing.</li>
 * </ul>
 *
 * <h2>{@code ERRMSG} is 78 characters, not 80</h2>
 *
 * {@code ERRMSGI} is {@code PIC X(78)} ({@code app/cpy-bms/COUSR03.CPY} line 84) and the matching
 * {@code DFHMDF} declares {@code LENGTH=78} ({@code app/bms/COUSR03.bms} lines 140-143), while the
 * message that fills it is {@code WS-MESSAGE PIC X(80)} ({@code app/cbl/COUSR03C.cbl} line 38). The
 * two-character narrowing happens at line 217, {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO} - a
 * COBOL alphanumeric move, so it truncates on the right. That move belongs to the controller and is
 * performed by {@code common.FixedWidthCodec}, which owns every pad and every truncation in this
 * migration. This type declares the width and truncates nothing.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // A fully spaced payload, the INITIALIZE-ALL-FIELDS shape of lines 349-356.
 * UserDeleteRequest blank = UserDeleteRequest.empty();
 *
 * // First call: the operator keys a user id and presses ENTER to fetch the record. Only the two
 * // members the terminal actually supplies are populated; the rest stay at their blank width.
 * UserDeleteRequest fetch = new UserDeleteRequest(blank.trnName(),
 *         blank.title01(), blank.curDate(), blank.pgmName(), blank.title02(), blank.curTime(),
 *         "USER0001", blank.fName(), blank.lName(), blank.usrType(), blank.errMsg(),
 *         NavigationContext.empty(), "ENTER");
 *
 * // The enter-versus-re-enter context is read THROUGH the navigation context, never duplicated.
 * boolean validateWhatWasTyped = fetch.pgmReenter();
 * </pre>
 *
 * @param trnName           {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COUSR03.CPY} line 24. The
 *                          transaction identifier shown top-left; {@code COUSR03C} line 249 moves
 *                          {@code WS-TRANID} - {@value #TRANSACTION_ID} (line 37) - into it
 * @param title01           {@code TITLE01I PIC X(40)}, line 30. The first title line, supplied from
 *                          {@code common.ScreenTitles}; {@code COUSR03C} line 247 moves
 *                          {@code CCDA-TITLE01} into it
 * @param curDate           {@code CURDATEI PIC X(8)}, line 36. The current date as {@code MM/DD/YY};
 *                          {@code COUSR03C} line 256 moves {@code WS-CURDATE-MM-DD-YY} into it and
 *                          the map declares {@code INITIAL='mm/dd/yy'}
 * @param pgmName           {@code PGMNAMEI PIC X(8)}, line 42. The program identifier;
 *                          {@code COUSR03C} line 250 moves {@code WS-PGMNAME} -
 *                          {@value #PROGRAM_NAME} (line 36) - into it
 * @param title02           {@code TITLE02I PIC X(40)}, line 48. The second title line, supplied from
 *                          {@code common.ScreenTitles}; {@code COUSR03C} line 248 moves
 *                          {@code CCDA-TITLE02} into it
 * @param curTime           {@code CURTIMEI PIC X(8)}, line 54. The current time as {@code HH:MM:SS};
 *                          {@code COUSR03C} line 262 moves {@code WS-CURTIME-HH-MM-SS} into it.
 *                          <strong>Eight characters</strong> - of the seventeen mapsets only
 *                          {@code COSGN00} widens its time field to nine
 * @param usrIdIn           {@code USRIDINI PIC X(8)}, line 60. The user to delete, and the only
 *                          unprotected field on the map. Named for {@code USRIDIN} as {@code COUSR02}
 *                          names it, <strong>not</strong> {@code USERID} as {@code COUSR01} does;
 *                          the divergence between the sibling screens is preserved, not harmonised.
 *                          Matches {@code SEC-USR-ID PIC X(08)}
 * @param fName             {@code FNAMEI PIC X(20)}, line 66. The first name, display-only, filled
 *                          from {@code SEC-USR-FNAME PIC X(20)} at {@code COUSR03C} line 165
 * @param lName             {@code LNAMEI PIC X(20)}, line 72. The last name, display-only, filled
 *                          from {@code SEC-USR-LNAME PIC X(20)} at {@code COUSR03C} line 166
 * @param usrType           {@code USRTYPEI PIC X(1)}, line 78. The user type, display-only, filled
 *                          from {@code SEC-USR-TYPE PIC X(01)} at {@code COUSR03C} line 167.
 *                          {@code 'A'} is an administrator and {@code 'U'} a regular user, per the
 *                          screen legend {@code '(A=Admin, U=User)'} at
 *                          {@code app/bms/COUSR03.bms} line 139 - left unconstrained here, because
 *                          the program constrains it nowhere
 * @param errMsg            {@code ERRMSGI PIC X(78)}, line 84. The message line, 78 characters and
 *                          not the 80 of {@code WS-MESSAGE}
 * @param navigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte
 *                          {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, copied by
 *                          {@code COUSR03C} at line 49 and returned to CICS at line 136. Not a
 *                          {@code DFHMDF} field: it is the conversation state that a stateless
 *                          projection of a pseudo-conversational transaction must carry in the
 *                          payload, and it is referenced here exactly as declared - never widened,
 *                          never re-modelled. {@code null} projects {@code EIBCALEN = 0}, the cold
 *                          start that {@code COUSR03C} handles at lines 90-92 by transferring to
 *                          {@code COSGN00C}
 * @param aid               the resolved AID token, as produced by {@code common.PfKeyResolver} from
 *                          {@code EIBAID}: {@value #AID_LENGTH} characters, the width of
 *                          {@code CCARD-AID PIC X(5)}. Also not a {@code DFHMDF} field, and carried
 *                          for the same reason - the confirm-then-delete flow of lines 108-130 is
 *                          unreachable without it. {@code null} means no key indication was supplied
 */
public record UserDeleteRequest(

        // TRNNAMEI  PIC X(4)  - COUSR03.CPY:24 ; TRNNAME DFHMDF LENGTH=4  POS=(1,7)   ASKIP FSET
        @Size(max = TRNNAME_LENGTH) String trnName,

        // TITLE01I  PIC X(40) - COUSR03.CPY:30 ; TITLE01 DFHMDF LENGTH=40 POS=(1,21)  ASKIP FSET
        @Size(max = TITLE01_LENGTH) String title01,

        // CURDATEI  PIC X(8)  - COUSR03.CPY:36 ; CURDATE DFHMDF LENGTH=8  POS=(1,71)  ASKIP FSET
        @Size(max = CURDATE_LENGTH) String curDate,

        // PGMNAMEI  PIC X(8)  - COUSR03.CPY:42 ; PGMNAME DFHMDF LENGTH=8  POS=(2,7)   ASKIP FSET
        @Size(max = PGMNAME_LENGTH) String pgmName,

        // TITLE02I  PIC X(40) - COUSR03.CPY:48 ; TITLE02 DFHMDF LENGTH=40 POS=(2,21)  ASKIP FSET
        @Size(max = TITLE02_LENGTH) String title02,

        // CURTIMEI  PIC X(8)  - COUSR03.CPY:54 ; CURTIME DFHMDF LENGTH=8  POS=(2,71)  ASKIP FSET
        @Size(max = CURTIME_LENGTH) String curTime,

        // USRIDINI  PIC X(8)  - COUSR03.CPY:60 ; USRIDIN DFHMDF LENGTH=8  POS=(6,21)  UNPROT IC
        @Size(max = USRIDIN_LENGTH) String usrIdIn,

        // FNAMEI    PIC X(20) - COUSR03.CPY:66 ; FNAME   DFHMDF LENGTH=20 POS=(11,18) ASKIP FSET
        @Size(max = FNAME_LENGTH) String fName,

        // LNAMEI    PIC X(20) - COUSR03.CPY:72 ; LNAME   DFHMDF LENGTH=20 POS=(13,18) ASKIP FSET
        @Size(max = LNAME_LENGTH) String lName,

        // USRTYPEI  PIC X(1)  - COUSR03.CPY:78 ; USRTYPE DFHMDF LENGTH=1  POS=(15,17) ASKIP FSET
        @Size(max = USRTYPE_LENGTH) String usrType,

        // ERRMSGI   PIC X(78) - COUSR03.CPY:84 ; ERRMSG  DFHMDF LENGTH=78 POS=(23,1)  ASKIP BRT
        @Size(max = ERRMSG_LENGTH) String errMsg,

        // Not a DFHMDF field: CARDDEMO-COMMAREA, carried in the payload because there is no session.
        NavigationContext navigationContext,

        // Not a DFHMDF field: the resolved EIBAID token, CCARD-AID PIC X(5).
        @Size(max = AID_LENGTH) String aid) {

    // =================================================================================================
    // The screen this payload projects. Every value below was measured from the sources named beside
    // it, never assumed, because each one is a number a reviewer will want to re-derive.
    // =================================================================================================

    /**
     * The CICS transaction that drives this screen: {@code WS-TRANID PIC X(04) VALUE 'CU03'},
     * {@code app/cbl/COUSR03C.cbl} line 37. {@code COUSR03C} moves it into {@link #trnName()} at
     * line 249 and hands it back to CICS as the next {@code TRANSID} at line 135.
     */
    public static final String TRANSACTION_ID = "CU03";

    /**
     * The COBOL program this payload's screen belongs to:
     * {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'}, {@code app/cbl/COUSR03C.cbl} line 36. Moved
     * into {@link #pgmName()} at line 250. Eight characters, as every program name in this
     * application is.
     */
    public static final String PROGRAM_NAME = "COUSR03C";

    /**
     * The BMS map: {@code COUSR3A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)},
     * {@code app/bms/COUSR03.bms} lines 26-28. Seven characters, which is why the symbolic-map group
     * items can suffix a direction byte and still fit eight - {@code COUSR3AI} for the input view
     * this type projects and {@code COUSR3AO} for the output view.
     */
    public static final String MAP_NAME = "COUSR3A";

    /**
     * The BMS mapset: {@code COUSR03 DFHMSD CTRL=(ALARM,FREEKB), EXTATT=YES, LANG=COBOL,
     * MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES}, {@code app/bms/COUSR03.bms} lines 19-25. Named in
     * {@code COUSR03C}'s {@code SEND MAP} at line 221 and its {@code RECEIVE MAP} at line 234.
     */
    public static final String MAPSET_NAME = "COUSR03";

    /**
     * The number of map-derived payload members: {@value}. This is the count of name-labelled
     * {@code DFHMDF} definitions in {@code app/bms/COUSR03.bms}, of {@code xxxI} items in
     * {@code 01 COUSR3AI} and of {@code xxxO} items in {@code 01 COUSR3AO} - all three measured
     * independently and all three in agreement.
     *
     * <p>The mapset holds 26 {@code DFHMDF} definitions in total; the other fifteen are unlabelled
     * literal furniture and are not payload. <strong>A twelfth member would mean a password field
     * had been invented</strong> - see the class documentation for the greps that prove this screen
     * has none, and for the contrast with {@code COUSR02}, which does and therefore has twelve.
     */
    public static final int MAP_FIELD_COUNT = 11;

    // =================================================================================================
    // The COBOL item names, spelled exactly as app/cpy-bms/COUSR03.CPY spells them - the "I" suffix
    // included. These are the names the parity differ compares field by field, so a "tidied" name
    // would make a real difference invisible.
    // =================================================================================================

    /** Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEI}, line 24. */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /** Symbolic-map item behind {@link #title01()}: {@code TITLE01I}, line 30. */
    public static final String TITLE01_FIELD = "TITLE01I";

    /** Symbolic-map item behind {@link #curDate()}: {@code CURDATEI}, line 36. */
    public static final String CURDATE_FIELD = "CURDATEI";

    /** Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEI}, line 42. */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /** Symbolic-map item behind {@link #title02()}: {@code TITLE02I}, line 48. */
    public static final String TITLE02_FIELD = "TITLE02I";

    /** Symbolic-map item behind {@link #curTime()}: {@code CURTIMEI}, line 54. */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /**
     * Symbolic-map item behind {@link #usrIdIn()}: {@code USRIDINI}, line 60.
     *
     * <p>{@code USRIDIN}, matching {@code COUSR02}. {@code COUSR01} calls its equivalent
     * {@code USERID} and places it after the name fields; that divergence between three sibling
     * screens is preserved verbatim rather than harmonised.
     */
    public static final String USRIDIN_FIELD = "USRIDINI";

    /** Symbolic-map item behind {@link #fName()}: {@code FNAMEI}, line 66. */
    public static final String FNAME_FIELD = "FNAMEI";

    /** Symbolic-map item behind {@link #lName()}: {@code LNAMEI}, line 72. */
    public static final String LNAME_FIELD = "LNAMEI";

    /** Symbolic-map item behind {@link #usrType()}: {@code USRTYPEI}, line 78. */
    public static final String USRTYPE_FIELD = "USRTYPEI";

    /** Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGI}, line 84. */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    // =================================================================================================
    // Declared widths, one named constant per member, each taken from the xxxI PICTURE clause of
    // app/cpy-bms/COUSR03.CPY and cross-checked against the LENGTH= of the matching DFHMDF in
    // app/bms/COUSR03.bms. The two sources agree on all eleven. Every @Size(max = ...) above states
    // its bound through one of these rather than repeating a number.
    // =================================================================================================

    /** Width of {@code TRNNAMEI PIC X(4)}, line 24; {@code TRNNAME DFHMDF LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** Width of {@code TITLE01I PIC X(40)}, line 30; {@code TITLE01 DFHMDF LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** Width of {@code CURDATEI PIC X(8)}, line 36; {@code CURDATE DFHMDF LENGTH=8}. */
    public static final int CURDATE_LENGTH = 8;

    /** Width of {@code PGMNAMEI PIC X(8)}, line 42; {@code PGMNAME DFHMDF LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** Width of {@code TITLE02I PIC X(40)}, line 48; {@code TITLE02 DFHMDF LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)}, line 54; {@code CURTIME DFHMDF LENGTH=8}.
     *
     * <p><strong>Eight, not nine.</strong> {@code COSGN00} is the one mapset of the seventeen whose
     * time field is nine characters wide; every other screen, this one included, declares eight and
     * fills it with {@code WS-CURTIME-HH-MM-SS}. The difference is not regularised.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code USRIDINI PIC X(8)}, line 60; {@code USRIDIN DFHMDF LENGTH=8}. Equal to
     * {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy} line 18, which is what makes
     * {@code MOVE USRIDINI OF COUSR3AI TO SEC-USR-ID} at {@code COUSR03C} lines 160 and 189 an
     * exact move rather than a truncation.
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * Width of {@code FNAMEI PIC X(20)}, line 66; {@code FNAME DFHMDF LENGTH=20}. Equal to
     * {@code SEC-USR-FNAME PIC X(20)}, moved in at {@code COUSR03C} line 165.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * Width of {@code LNAMEI PIC X(20)}, line 72; {@code LNAME DFHMDF LENGTH=20}. Equal to
     * {@code SEC-USR-LNAME PIC X(20)}, moved in at {@code COUSR03C} line 166.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * Width of {@code USRTYPEI PIC X(1)}, line 78; {@code USRTYPE DFHMDF LENGTH=1}. Equal to
     * {@code SEC-USR-TYPE PIC X(01)}, moved in at {@code COUSR03C} line 167.
     */
    public static final int USRTYPE_LENGTH = 1;

    /**
     * Width of {@code ERRMSGI PIC X(78)}, line 84; {@code ERRMSG DFHMDF LENGTH=78}.
     *
     * <p><strong>Seventy-eight, not eighty.</strong> {@code WS-MESSAGE} is {@code PIC X(80)}
     * ({@code app/cbl/COUSR03C.cbl} line 38) and {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO} at
     * line 217 narrows it by two characters, truncating on the right as a COBOL alphanumeric move
     * does. That truncation is the controller's, performed through
     * {@code common.FixedWidthCodec}; this type only declares the destination width.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Width of the AID token carried by {@link #aid()}: {@value}, the width of
     * {@code CCARD-AID PIC X(5)} in {@code app/cpy/CVCRD01Y.cpy}.
     *
     * <p>{@code common.PfKeyResolver} produces the token and space-pads it to this width, so
     * {@code 'PA1  '} and {@code 'PA2  '} really do carry two trailing spaces while {@code 'ENTER'},
     * {@code 'CLEAR'} and {@code 'PFK01'} through {@code 'PFK12'} fill it exactly. Those trailing
     * spaces are part of the value: a client that trimmed them would send four characters where the
     * field holds five.
     */
    public static final int AID_LENGTH = 5;

    // =================================================================================================
    // Symbolic-map geometry, derived from the widths declared immediately above rather than restated as
    // literals. Each total is written as a sum of named parts, so a mistyped width cannot leave a total
    // silently right, and the identity that makes COUSR3AO REDEFINES COUSR3AI valid is expressed by the
    // code instead of merely asserted in prose.
    // =================================================================================================

    /**
     * The sum of the {@value #MAP_FIELD_COUNT} declared field widths:
     * {@code 4 + 40 + 8 + 8 + 40 + 8 + 8 + 20 + 20 + 1 + 78} = {@value}.
     */
    public static final int MAP_FIELDS_WIDTH_TOTAL = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + USRIDIN_LENGTH + FNAME_LENGTH
            + LNAME_LENGTH + USRTYPE_LENGTH + ERRMSG_LENGTH;

    /**
     * Width of the leading {@code 02 FILLER PIC X(12)} of both symbolic-map views,
     * {@code app/cpy-bms/COUSR03.CPY} lines 18 and 86: {@value}. It exists because the mapset
     * declares {@code TIOAPFX=YES}, and it is reserved storage that this payload does not expose.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The per-field overhead of the input view: {@value} bytes, being
     * {@code xxxL COMP PIC S9(4)} (2) + {@code xxxF PICTURE X} (1) + {@code FILLER PICTURE X(4)}
     * (4). The {@code 03 xxxA PICTURE X} item adds nothing because it {@code REDEFINES xxxF}. Every
     * field of {@code 01 COUSR3AI} therefore occupies {@code 7 + n} bytes.
     *
     * <p>The output view's overhead is the same {@value} bytes -
     * {@code FILLER PICTURE X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} + {@code xxxV} - which
     * is exactly why {@code 01 COUSR3AO REDEFINES COUSR3AI} lines up field for field.
     */
    public static final int FIELD_OVERHEAD_LENGTH = 7;

    /**
     * The total width of either symbolic-map view in bytes:
     * {@value #TIOAPFX_PREFIX_LENGTH} + ({@value #MAP_FIELD_COUNT} x
     * {@value #FIELD_OVERHEAD_LENGTH}) + {@value #MAP_FIELDS_WIDTH_TOTAL} = {@value}.
     *
     * <p>Substitute the output view's own overhead - {@code 3 + 1 + 1 + 1 + 1}, also
     * {@value #FIELD_OVERHEAD_LENGTH} - and the same total falls out, which is the mechanical reason
     * the input and output views describe one field set rather than two.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + (MAP_FIELD_COUNT * FIELD_OVERHEAD_LENGTH) + MAP_FIELDS_WIDTH_TOTAL;

    // =================================================================================================
    // The blank payload.
    // =================================================================================================

    /**
     * A payload whose {@value #MAP_FIELD_COUNT} map-derived members are each a run of spaces of that
     * member's declared width, whose navigation context is
     * {@link NavigationContext#empty()} and whose AID token is
     * {@value #AID_LENGTH} spaces.
     *
     * <p>This is the {@code MOVE SPACES} shape that {@code INITIALIZE-ALL-FIELDS} applies at
     * {@code app/cbl/COUSR03C.cbl} lines 349-356 - the paragraph {@code CLEAR-CURRENT-SCREEN}
     * performs on {@code PF4} (line 120) and the delete path performs on success (line 315) -
     * extended to all eleven members so the result is a complete, valid payload rather than a
     * partly populated one. The context it returns is already in {@code CDEMO-PGM-ENTER} state,
     * because {@link NavigationContext#empty()} zeroes {@code CDEMO-PGM-CONTEXT}, so
     * {@link #pgmEnter()} is true and {@link #pgmReenter()} is false.
     *
     * <p>Note that this is <em>not</em> the {@code MOVE LOW-VALUES TO COUSR3AO} shape of line 97.
     * {@code LOW-VALUES} is projected by {@code null} members, which the canonical constructor
     * accepts and preserves, because {@code COUSR03C} distinguishes the two states in source -
     * {@code = SPACES OR LOW-VALUES} at lines 145 and 177 - even though it answers them alike.
     *
     * <p>Each field is filled by repeating a space to the field's declared width. That is COBOL's
     * unconditional {@code SPACES} fill, not the alphanumeric {@code MOVE} rule; the move rule,
     * which pads a shorter sending value and truncates a longer one, lives in
     * {@code common.FixedWidthCodec} and is not reimplemented here.
     *
     * @return a blank request payload, never {@code null} and containing no {@code null} member
     */
    public static UserDeleteRequest empty() {
        return new UserDeleteRequest(spaces(TRNNAME_LENGTH),
                spaces(TITLE01_LENGTH),
                spaces(CURDATE_LENGTH),
                spaces(PGMNAME_LENGTH),
                spaces(TITLE02_LENGTH),
                spaces(CURTIME_LENGTH),
                spaces(USRIDIN_LENGTH),
                spaces(FNAME_LENGTH),
                spaces(LNAME_LENGTH),
                spaces(USRTYPE_LENGTH),
                spaces(ERRMSG_LENGTH),
                NavigationContext.empty(),
                spaces(AID_LENGTH));
    }

    /**
     * A run of {@code width} spaces - the COBOL {@code SPACES} figurative constant materialised at a
     * field's declared width.
     *
     * @param width the field width in characters, always one of this type's {@code _LENGTH} constants
     *              and therefore always positive
     * @return a string of exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return " ".repeat(width);
    }

    // =================================================================================================
    // The enter-versus-re-enter context, READ THROUGH the navigation context rather than duplicated.
    //
    // CDEMO-PGM-CONTEXT PIC 9(01) and its two condition names - 88 CDEMO-PGM-ENTER VALUE 0 and
    // 88 CDEMO-PGM-REENTER VALUE 1, app/cpy/COCOM01Y.cpy lines 29-31 - belong to CARDDEMO-COMMAREA
    // and are stored there, once. This payload holds no second copy of the flag: a duplicate could
    // drift out of step with the context it travels beside, and the two would then disagree about
    // whether the screen is being painted or validated.
    //
    // Neither method is named getXxx or isXxx, and that is deliberate rather than stylistic. Jackson
    // promotes any public no-argument is-getter on a record to a JSON property: a method named
    // isReenter() would emit a "reenter" key that the canonical constructor cannot accept back, so a
    // serialise-then-deserialise round trip would fail outright with UnrecognizedPropertyException.
    // NavigationContext solves that for its own four predicates with @JsonIgnore; this type may not,
    // because the annotation policy for these DTOs forbids Jackson annotations here and keeps all
    // mapping decisions in config/WebConfig. Naming the methods after the COBOL condition names with
    // the CDEMO-PGM- prefix removed satisfies both constraints at once, and matches how
    // common.PfKeyResolver names its AID constants after CCARD-AID's condition names.
    // =================================================================================================

    /**
     * Whether {@code 88 CDEMO-PGM-ENTER VALUE 0} holds for the carried navigation context - first
     * entry, on which {@code COUSR03C} paints the screen rather than validating it.
     *
     * <p>Delegates to {@link NavigationContext#isEnter()} and stores nothing of its own.
     * {@code COUSR03C} tests the negation of the sibling condition at line 95,
     * {@code IF NOT CDEMO-PGM-REENTER}, and on taking that branch immediately asserts re-entry for
     * the next turn at line 96, {@code SET CDEMO-PGM-REENTER TO TRUE}.
     *
     * @return {@code true} when a navigation context is present and its {@code CDEMO-PGM-CONTEXT} is
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}; {@code false} when it is not, and
     *         {@code false} when no context was supplied at all. An absent context projects
     *         {@code EIBCALEN = 0}, which {@code COUSR03C} answers at lines 90-92 by transferring to
     *         {@code COSGN00C} without ever reaching the context test - so neither condition holds,
     *         exactly as neither holds for a {@code CDEMO-PGM-CONTEXT} of any digit but 0 or 1
     */
    public boolean pgmEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds for the carried navigation context -
     * re-entry, on which {@code COUSR03C} evaluates {@code EIBAID} (lines 108-130) and validates
     * what was typed.
     *
     * <p>Delegates to {@link NavigationContext#isReenter()} and stores nothing of its own. This is
     * the condition {@code app/cpy/CSSETATY.cpy} requires before it moves {@code DFHRED} and
     * {@code '*'} onto a field in error, which is why {@code common.FieldAttributeSetter} consumes
     * it rather than deciding for itself.
     *
     * <p>It is <strong>not</strong> the negation of {@link #pgmEnter()}. {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and can hold any digit, and the context may be absent altogether, so both
     * predicates are false for those inputs. Writing one as the negation of the other would route a
     * cold start down the validation path.
     *
     * @return {@code true} when a navigation context is present and its {@code CDEMO-PGM-CONTEXT} is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}; {@code false} otherwise, including
     *         when no context was supplied
     */
    public boolean pgmReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }
}
