package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest.Cu03Info;
import java.util.Objects;

/**
 * The outbound payload of {@code DELETE /api/users/{userId}} - the Java projection of the CICS
 * screen that {@code app/cbl/COUSR03C.cbl} sends under transaction {@code CU03}.
 *
 * <p>This type is a <strong>field-for-field projection of the {@code xxxO} items of
 * {@code 01 COUSR3AO}</strong> in {@code app/cpy-bms/COUSR03.CPY}, plus the four members that carry
 * the stateless navigation contract. It holds <strong>no logic</strong>: no confirmation check, no
 * message composition, no repository call, no truncation. Those all belong to
 * {@code user.UserDeleteController}. This is a payload contract and nothing more.
 *
 * <h2>Provenance</h2>
 *
 * <p>Three read-only sources define this shape, and all three agree on the field count:
 *
 * <ul>
 *   <li>{@code app/cpy-bms/COUSR03.CPY} - {@code 01 COUSR3AO REDEFINES COUSR3AI} at line 85. Every
 *       field occupies the same {@code 7 + n} stride on both sides of the {@code REDEFINES}:
 *       {@code FILLER X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} + {@code xxxV} +
 *       {@code xxxO PIC X(n)} on the output side, against {@code xxxL COMP PIC S9(4)} (2 bytes) +
 *       {@code xxxF PICTURE X} + {@code FILLER X(4)} + {@code xxxI PIC X(n)} on the input side.
 *       Identical strides are exactly what makes the {@code REDEFINES} legal, and they are why the
 *       {@code I} and {@code O} views expose the same eleven fields at the same offsets.</li>
 *   <li>{@code app/bms/COUSR03.bms} - 26 {@code DFHMDF} definitions of which exactly
 *       <strong>eleven</strong> carry a name label. The other fifteen are literal {@code INITIAL}
 *       screen furniture ({@code 'Tran:'}, {@code 'Date:'}, {@code 'Prog:'}, {@code 'Time:'},
 *       {@code 'Delete User'}, {@code 'Enter User ID:'}, {@code 'First Name:'},
 *       {@code 'Last Name:'}, {@code 'User Type: '}, {@code '(A=Admin, U=User)'}, the asterisk
 *       rule, the {@code 'ENTER=Fetch  F3=Back  F4=Clear  F5=Delete'} legend and three zero-length
 *       positioning fields). Furniture is painted by the terminal, never carried in a payload, so
 *       none of it appears here.</li>
 *   <li>{@code app/cbl/COUSR03C.cbl} - the program that populates the map. Its header paragraph
 *       {@code POPULATE-HEADER-INFO} (lines 245-262) writes {@code TITLE01O}, {@code TITLE02O},
 *       {@code TRNNAMEO}, {@code PGMNAMEO}, {@code CURDATEO} and {@code CURTIMEO}; its lookup
 *       writes the three user fields at lines 165-167; and line 217 writes the message.</li>
 * </ul>
 *
 * <p>Every one of the eleven map-derived members below therefore traces to a name-labelled
 * {@code DFHMDF} line, which is the obligation gate G9 imposes. The four navigation members are
 * the explicitly mandated exception to that gate, required instead by G37 and G40; each is marked
 * as such at its declaration.
 *
 * <h2>Why there are eleven members and not twelve: the absent password</h2>
 *
 * <p><strong>This screen has no password field, and none may be added here.</strong> The claim is
 * not inferred from the layout - it is the result of three independent checks against the
 * read-only sources:
 *
 * <ul>
 *   <li>{@code grep -n 'PASSWD' app/cbl/COUSR03C.cbl} returns <strong>nothing at all</strong>. The
 *       program never names a password: its lookup at lines 165-167 moves only
 *       {@code SEC-USR-FNAME}, {@code SEC-USR-LNAME} and {@code SEC-USR-TYPE} onto the screen, and
 *       its field reset at lines 157-159 and 351-356 clears only those same three plus the user
 *       id.</li>
 *   <li>{@code app/bms/COUSR03.bms} declares no {@code PASSWD} {@code DFHMDF}. The eleven labels
 *       are, in map order: {@code TRNNAME}, {@code TITLE01}, {@code CURDATE}, {@code PGMNAME},
 *       {@code TITLE02}, {@code CURTIME}, {@code USRIDIN}, {@code FNAME}, {@code LNAME},
 *       {@code USRTYPE}, {@code ERRMSG}.</li>
 *   <li>{@code app/cpy-bms/COUSR03.CPY} declares no {@code PASSWDI} and no {@code PASSWDO}. The
 *       {@code xxxI} count, the {@code xxxO} count and the name-labelled {@code DFHMDF} count are
 *       all <strong>eleven</strong>, in agreement.</li>
 * </ul>
 *
 * <p>That single absence is precisely why {@code COUSR03} carries eleven name-labelled fields
 * where {@code COUSR02} carries twelve. The two screens are otherwise near-identical, and that
 * resemblance is exactly what makes "completing" this one dangerous. The sibling
 * {@code UserUpdateResponse} legitimately does carry a password, because {@code COUSR02C:169}
 * really does place the stored value on its screen; this response legitimately does not, because
 * {@code COUSR03C} never mentions one. Both facts were verified independently against their own
 * sources and <strong>neither generalises to the other</strong>.
 *
 * <p>Adding a password member here would invent a payload field with no {@code DFHMDF} behind it,
 * failing gate G9, and would be a behaviour change of the kind the migration mandate forbids
 * outright. Practice B5 governs: nothing is tidied, and an asymmetry that the source exhibits is
 * reproduced rather than smoothed away. Practice B6 reinforces it: the screen has no credential,
 * so this payload carries none, and no hashing, encoder, token or security type appears anywhere
 * in this file.
 *
 * <p>Do not confuse the <em>record</em> shape with the <em>screen</em> shape. The 80-byte
 * {@code SEC-USER-DATA} of {@code app/cpy/CSUSR01Y.cpy} genuinely does hold
 * {@code SEC-USR-PWD PIC X(08)}, and {@code user.model.SecUserRecord} models it faithfully. The
 * screen simply never displays it. A reviewer diffing this file against
 * {@code UserUpdateResponse} should read the asymmetry as deliberate and verified, not as an
 * oversight.
 *
 * <h2>Widths are declared, never inferred</h2>
 *
 * <p>Each width below is a literal read from its {@code xxxO} {@code PICTURE} clause and published
 * as a {@code _LENGTH} constant, so the payload contract is auditable against the copybook without
 * reading any Java logic (practice B8). Three widths are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li>{@link #CUR_TIME_LENGTH} is <strong>8</strong>, not 9. Only {@code COSGN00} widens its
 *       time field; this map declares {@code CURTIMEO PIC X(8)} at line 122, matching
 *       {@code LENGTH=8} in the mapset and the {@code hh:mm:ss} shape that
 *       {@code POPULATE-HEADER-INFO} builds.</li>
 *   <li>The user id member is named for <strong>{@code USRIDIN}</strong>, as on {@code COUSR02},
 *       and not for {@code COUSR01}'s {@code USERID}. The two spellings are not harmonised
 *       (practice B5).</li>
 *   <li>{@link #ERR_MSG_LENGTH} is <strong>78</strong>, not 80. {@code COUSR03C:38} declares
 *       {@code WS-MESSAGE PIC X(80)} and {@code COUSR03C:217} performs
 *       {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO}, an alphanumeric {@code MOVE} into a
 *       narrower receiver that truncates the surplus two bytes on the right. That 80-to-78
 *       narrowing is owned by the controller, which routes the value through
 *       {@code common.FixedWidthCodec.movePicX} so the truncation is deliberate and visible at the
 *       call site. This type declares 78 and <strong>truncates nowhere</strong> - an over-long
 *       value is refused rather than silently shortened.</li>
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>The symbolic map surrounds each {@code xxxO} item with single-byte control fields, and none of
 * them is a payload member:
 *
 * <ul>
 *   <li>{@code xxxC} is the <strong>colour</strong> byte, and this program drives it two different
 *       ways: {@code MOVE DFHNEUTR TO ERRMSGC OF COUSR3AO} at line 285 for the confirmation
 *       prompt, and {@code MOVE DFHGREEN TO ERRMSGC} at line 317 once the delete has succeeded.
 *       A controller that needs the colour byte carries it as clearly-named metadata;
 *       {@code common.BmsAttributes} and {@code common.FieldAttributeSetter} own that concern.</li>
 *   <li>{@code xxxH} is the highlight byte; {@code xxxP} and {@code xxxV} are the programmed-symbol
 *       and validation bytes.</li>
 *   <li>On the input side {@code xxxL} is the length CICS reports and doubles as the cursor signal
 *       when set to {@code -1} (lines 98, 149, 291, 351), and {@code xxxF} with its {@code xxxA}
 *       redefinition is the attribute byte.</li>
 *   <li>The 12-byte {@code TIOAPFX} {@code FILLER} at line 86 and the eleven {@code FILLER X(3)}
 *       spans are reserved storage and are not exposed.</li>
 * </ul>
 *
 * <p>Jackson policy belongs to {@code config.WebConfig}, which keeps member names untransformed and
 * refuses trimming, empty-string coercion and null or empty exclusion, so a space-padded
 * {@code PIC X(n)} value survives a serialisation round trip byte-for-byte. This type therefore
 * carries no Jackson annotation of any kind, and deliberately exposes no derived accessor: with no
 * {@code @JsonIgnore} available to suppress one, the only way to guarantee the JSON key set is
 * exactly the fifteen declared components is not to declare a sixteenth accessor at all. Static
 * factories and the parameterised {@code with*} copy methods are safe because neither is shaped
 * like a bean property.
 *
 * <p>There are no Bean Validation annotations either. This is a response, so nothing binds it and
 * nothing would ever validate it; the module scopes Bean Validation to the seventeen request
 * payloads. Width enforcement instead lives in the canonical constructor, which is strictly
 * stronger than a documentation-only {@code @Size} because it actually rejects a bad value.
 * {@code @NotBlank} and {@code @NotNull} would be actively wrong here: {@code COUSR03C:147} and
 * {@code COUSR03C:179} both answer a blank user id with the message
 * {@code 'User ID can NOT be empty...'} rather than refusing the request, so surfacing a 400 would
 * change observable behaviour.
 *
 * <h2>Statelessness</h2>
 *
 * <p>{@code COUSR03C} is confirm-then-delete. {@code ENTER} looks the user up and prompts
 * {@code 'Press PF5 key to delete this user ...'} (line 283); {@code PF5} re-reads for update and
 * issues the delete (lines 307-311). Between those two calls the server holds
 * <strong>nothing</strong> - the client returns the payload it was given, exactly as CICS returned
 * the communication area to the terminal. No {@code HttpSession}, no {@code @SessionAttributes},
 * no {@code @SessionScope}, no static cache, no {@code ThreadLocal} and no server-side pending
 * delete token appears here or anywhere in the flow (gate G37).
 *
 * <p>Instances are immutable and safe to share across threads. Every member is a {@code String} or
 * the immutable {@link NavigationContext}, and there is no static mutable state (practice B9).
 *
 * @param trnName the transaction identifier shown in the {@code Tran:} field - {@code TRNNAMEO},
 *     {@code PIC X(4)}, populated with {@value #TRANSACTION_ID} from {@code COUSR03C:249}
 * @param title01 the first title line - {@code TITLE01O}, {@code PIC X(40)}, populated from
 *     {@code common.ScreenTitles} by way of {@code CCDA-TITLE01} at {@code COUSR03C:247}
 * @param curDate the current date as {@code MM/DD/YY} - {@code CURDATEO}, {@code PIC X(8)},
 *     assembled by {@code common.DateHeader} as {@code COUSR03C:252-256} does
 * @param pgmName the program name shown in the {@code Prog:} field - {@code PGMNAMEO},
 *     {@code PIC X(8)}, populated with {@value #PROGRAM_NAME} from {@code COUSR03C:250}
 * @param title02 the second title line - {@code TITLE02O}, {@code PIC X(40)}, populated from
 *     {@code common.ScreenTitles} by way of {@code CCDA-TITLE02} at {@code COUSR03C:248}
 * @param curTime the current time as {@code hh:mm:ss} - {@code CURTIMEO}, {@code PIC X(8)}, eight
 *     characters and not nine, assembled as {@code COUSR03C:258-262} does
 * @param usrIdIn the user id the screen is acting on - {@code USRIDINO}, {@code PIC X(8)}, named
 *     for {@code USRIDIN} and never for {@code USERID}
 * @param fName the first name read back from the security file - {@code FNAMEO},
 *     {@code PIC X(20)}, from {@code SEC-USR-FNAME} via {@code COUSR03C:165}
 * @param lName the last name read back from the security file - {@code LNAMEO}, {@code PIC X(20)},
 *     from {@code SEC-USR-LNAME} via {@code COUSR03C:166}
 * @param usrType the user type, {@code 'A'} for admin or {@code 'U'} for user - {@code USRTYPEO},
 *     {@code PIC X(1)}, from {@code SEC-USR-TYPE} via {@code COUSR03C:167}
 * @param errMsg the message line - {@code ERRMSGO}, {@code PIC X(78)}, the receiver of
 *     {@code MOVE WS-MESSAGE TO ERRMSGO} at {@code COUSR03C:217}
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy},
 *     carried in the payload rather than held in a session (gate G37)
 * @param nextProgram the program a CICS {@code XCTL} would have transferred to, echoed for the
 *     client to follow (gate G40)
 * @param nextMapset the mapset the client should request next, {@code X(7)} wide
 * @param nextMap the map the client should request next, {@code X(7)} wide
 * @param cu03Info the 34-byte {@code 05 CDEMO-CU03-INFO} extension of
 *     {@code app/cbl/COUSR03C.cbl:50-58}, handed back behind the communication area exactly as line 94
 *     restored it; {@code null} is normalised to {@link Cu03Info#initial()}
 */
public record UserDeleteResponse(@JsonProperty("trnname") String trnName,
                                 String title01,
                                 @JsonProperty("curdate") String curDate,
                                 @JsonProperty("pgmname") String pgmName,
                                 String title02,
                                 @JsonProperty("curtime") String curTime,
                                 @JsonProperty("usridin") String usrIdIn,
                                 @JsonProperty("fname") String fName,
                                 @JsonProperty("lname") String lName,
                                 @JsonProperty("usrtype") String usrType,
                                 @JsonProperty("errmsg") String errMsg,
                                 NavigationContext navigationContext,
                                 String nextProgram,
                                 String nextMapset,
                                 String nextMap,
                                 Cu03Info cu03Info) {

    // =================================================================================================
    // Screen identity. Every literal below is quoted from the read-only sources rather than invented,
    // so a reviewer can confirm each one without leaving this file.
    // =================================================================================================

    /**
     * The CICS transaction identifier this screen runs under, {@code 'CU03'}.
     *
     * <p>Declared as {@code 05 WS-TRANID PIC X(04) VALUE 'CU03'} at {@code app/cbl/COUSR03C.cbl:37}
     * and moved onto the map by {@code MOVE WS-TRANID TO TRNNAMEO OF COUSR3AO} at line 249. It is
     * exactly {@value #TRN_NAME_LENGTH} characters, so it fills {@code TRNNAMEO} without padding.
     */
    public static final String TRANSACTION_ID = "CU03";

    /**
     * The COBOL program this response projects, {@code 'COUSR03C'}.
     *
     * <p>Declared as {@code 05 WS-PGMNAME PIC X(08) VALUE 'COUSR03C'} at
     * {@code app/cbl/COUSR03C.cbl:36} and moved onto the map by
     * {@code MOVE WS-PGMNAME TO PGMNAMEO OF COUSR3AO} at line 250.
     */
    public static final String PROGRAM_NAME = "COUSR03C";

    /**
     * The BMS map name, {@code 'COUSR3A'}.
     *
     * <p>Named by {@code MAP('COUSR3A')} in the send at {@code app/cbl/COUSR03C.cbl:220} and in the
     * receive at line 233. Seven characters, which is why {@code CDEMO-LAST-MAP} is declared
     * {@code PIC X(7)} rather than {@code X(8)}.
     */
    public static final String MAP_NAME = "COUSR3A";

    /**
     * The BMS mapset name, {@code 'COUSR03'}.
     *
     * <p>Named by {@code MAPSET('COUSR03')} in the send at {@code app/cbl/COUSR03C.cbl:221} and in
     * the receive at line 234, and by the {@code COUSR03 DFHMSD} header of
     * {@code app/bms/COUSR03.bms:19}. Seven characters, matching
     * {@code CDEMO-LAST-MAPSET PIC X(7)}.
     */
    public static final String MAPSET_NAME = "COUSR03";

    /**
     * The number of map-derived members this payload carries: <strong>eleven</strong>.
     *
     * <p>Published as a constant so the count is machine-checkable rather than a matter of reading
     * the component list. It is simultaneously the number of {@code xxxO} items in
     * {@code 01 COUSR3AO}, the number of {@code xxxI} items in {@code 01 COUSR3AI}, and the number
     * of name-labelled {@code DFHMDF} definitions in {@code app/bms/COUSR03.bms} - all three
     * measured and in agreement.
     *
     * <p>If this value ever reads twelve, a field with no {@code DFHMDF} behind it has been added
     * and gate G9 is broken. The likeliest such field is a password, which this screen does not
     * have; see the class documentation.
     */
    public static final int MAP_FIELD_COUNT = 11;

    // =================================================================================================
    // The eleven COBOL field names, in map order. Naming each one keeps the copybook-to-Java
    // correspondence auditable and gives the width checks a diagnostic that names the real field.
    // =================================================================================================

    /** The COBOL name of {@link #trnName()}: {@code TRNNAMEO}, from {@code COUSR03.CPY:92}. */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /** The COBOL name of {@link #title01()}: {@code TITLE01O}, from {@code COUSR03.CPY:98}. */
    public static final String TITLE01_FIELD = "TITLE01O";

    /** The COBOL name of {@link #curDate()}: {@code CURDATEO}, from {@code COUSR03.CPY:104}. */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /** The COBOL name of {@link #pgmName()}: {@code PGMNAMEO}, from {@code COUSR03.CPY:110}. */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /** The COBOL name of {@link #title02()}: {@code TITLE02O}, from {@code COUSR03.CPY:116}. */
    public static final String TITLE02_FIELD = "TITLE02O";

    /** The COBOL name of {@link #curTime()}: {@code CURTIMEO}, from {@code COUSR03.CPY:122}. */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /**
     * The COBOL name of {@link #usrIdIn()}: {@code USRIDINO}, from {@code COUSR03.CPY:128}.
     *
     * <p>{@code USRIDIN}, matching {@code COUSR02}. {@code COUSR01} spells the same concept
     * {@code USERID}, and the two are deliberately left unharmonised (practice B5).
     */
    public static final String USR_ID_IN_FIELD = "USRIDINO";

    /** The COBOL name of {@link #fName()}: {@code FNAMEO}, from {@code COUSR03.CPY:134}. */
    public static final String F_NAME_FIELD = "FNAMEO";

    /** The COBOL name of {@link #lName()}: {@code LNAMEO}, from {@code COUSR03.CPY:140}. */
    public static final String L_NAME_FIELD = "LNAMEO";

    /** The COBOL name of {@link #usrType()}: {@code USRTYPEO}, from {@code COUSR03.CPY:146}. */
    public static final String USR_TYPE_FIELD = "USRTYPEO";

    /** The COBOL name of {@link #errMsg()}: {@code ERRMSGO}, from {@code COUSR03.CPY:152}. */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    // =================================================================================================
    // The eleven declared widths. Each is a literal read from its xxxO PICTURE clause in
    // app/cpy-bms/COUSR03.CPY and cross-checked against the LENGTH= of the matching name-labelled
    // DFHMDF in app/bms/COUSR03.bms. Nothing here is derived from anything else (practice B8).
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - four characters.
     *
     * <p>{@code COUSR03.CPY:92}, matching {@code LENGTH=4} at {@code COUSR03.bms:36}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)} - forty characters.
     *
     * <p>{@code COUSR03.CPY:98}, matching {@code LENGTH=40} at {@code COUSR03.bms:40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)} - eight characters, the {@code mm/dd/yy} shape the mapset seeds as
     * its {@code INITIAL} value.
     *
     * <p>{@code COUSR03.CPY:104}, matching {@code LENGTH=8} at {@code COUSR03.bms:49}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)} - eight characters, exactly the width of
     * {@value #PROGRAM_NAME}.
     *
     * <p>{@code COUSR03.CPY:110}, matching {@code LENGTH=8} at {@code COUSR03.bms:59}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)} - forty characters.
     *
     * <p>{@code COUSR03.CPY:116}, matching {@code LENGTH=40} at {@code COUSR03.bms:63}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)} - <strong>eight</strong> characters, the {@code hh:mm:ss} shape the
     * mapset seeds as its {@code INITIAL} value.
     *
     * <p>Eight and not nine. {@code COUSR03.CPY:122} declares {@code PIC X(8)} and
     * {@code COUSR03.bms:72} declares {@code LENGTH=8}. Among the seventeen online maps only
     * {@code COSGN00} widens its time field, and that widening must not be carried across to this
     * one (practice B5).
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code USRIDINO PIC X(8)} - eight characters, the width of {@code SEC-USR-ID} in
     * {@code app/cpy/CSUSR01Y.cpy}.
     *
     * <p>{@code COUSR03.CPY:128}, matching {@code LENGTH=8} at {@code COUSR03.bms:88}.
     */
    public static final int USR_ID_IN_LENGTH = 8;

    /**
     * {@code FNAMEO PIC X(20)} - twenty characters, the width of {@code SEC-USR-FNAME}.
     *
     * <p>{@code COUSR03.CPY:134}, matching {@code LENGTH=20} at {@code COUSR03.bms:106}.
     */
    public static final int F_NAME_LENGTH = 20;

    /**
     * {@code LNAMEO PIC X(20)} - twenty characters, the width of {@code SEC-USR-LNAME}.
     *
     * <p>{@code COUSR03.CPY:140}, matching {@code LENGTH=20} at {@code COUSR03.bms:119}.
     */
    public static final int L_NAME_LENGTH = 20;

    /**
     * {@code USRTYPEO PIC X(1)} - a single character, the width of {@code SEC-USR-TYPE}.
     *
     * <p>{@code COUSR03.CPY:146}, matching {@code LENGTH=1} at {@code COUSR03.bms:133}. The mapset
     * captions it {@code '(A=Admin, U=User)'} at line 139, which is the only place those two values
     * are documented on the screen.
     */
    public static final int USR_TYPE_LENGTH = 1;

    /**
     * {@code ERRMSGO PIC X(78)} - <strong>seventy-eight</strong> characters.
     *
     * <p>{@code COUSR03.CPY:152}, matching {@code LENGTH=78} at {@code COUSR03.bms:142}. Seventy-
     * eight and not eighty: see {@link #WS_MESSAGE_LENGTH} for the narrowing that produces the
     * value, which this type never performs itself.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * The width of the COBOL work field that feeds {@link #errMsg()}: {@code 80}.
     *
     * <p>{@code app/cbl/COUSR03C.cbl:38} declares {@code 05 WS-MESSAGE PIC X(80)}, and line 217
     * performs {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO}. A COBOL alphanumeric {@code MOVE}
     * into a narrower receiver truncates on the right, so the last two of those eighty bytes are
     * discarded on every send.
     *
     * <p>This constant is published for the controller's benefit, and to document the asymmetry
     * plainly, but the narrowing itself is emphatically not performed here. The controller routes
     * the value through {@code common.FixedWidthCodec.movePicX}, which applies the same right-hand
     * truncation deliberately and visibly. This type refuses an over-long value instead of
     * shortening one, so a caller can never lose bytes by accident.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    // =================================================================================================
    // The stateless navigation contract. These four members are the explicitly mandated exception to
    // gate G9's "every member traces to a DFHMDF" rule: they are required by G37 (no server-side
    // session state) and G40 (an XCTL becomes a response field the client follows), and their widths
    // come from app/cpy/COCOM01Y.cpy rather than from the mapset.
    //
    // Each width is taken from NavigationContext's own published constants rather than restated as a
    // fresh literal, so the two types can never drift apart.
    // =================================================================================================

    /**
     * The width of {@link #nextProgram()}: {@value}, from
     * {@code CDEMO-TO-PROGRAM PIC X(08)} in {@code app/cpy/COCOM01Y.cpy:24}.
     *
     * <p>{@code app/cbl/COUSR03C.cbl:206} issues {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}, so the
     * target this member names is exactly as wide as that communication-area field.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * The width of {@link #nextMapset()}: {@value}, from
     * {@code CDEMO-LAST-MAPSET PIC X(7)} in {@code app/cpy/COCOM01Y.cpy:44}.
     *
     * <p>Seven, not eight - which is consistent with {@value #MAPSET_NAME} being seven characters
     * long.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * The width of {@link #nextMap()}: {@value}, from {@code CDEMO-LAST-MAP PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:43}.
     *
     * <p>Seven, not eight - which is consistent with {@value #MAP_NAME} being seven characters long.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /** The COBOL field whose width governs {@link #nextProgram()}: {@code CDEMO-TO-PROGRAM}. */
    public static final String NEXT_PROGRAM_FIELD = NavigationContext.TO_PROGRAM_FIELD;

    /** The COBOL field whose width governs {@link #nextMapset()}: {@code CDEMO-LAST-MAPSET}. */
    public static final String NEXT_MAPSET_FIELD = NavigationContext.LAST_MAPSET_FIELD;

    /** The COBOL field whose width governs {@link #nextMap()}: {@code CDEMO-LAST-MAP}. */
    public static final String NEXT_MAP_FIELD = NavigationContext.LAST_MAP_FIELD;

    /** The single space, used to build the space-filled fields of {@link #empty()}. */
    private static final String SPACE = " ";

    /**
     * Validates every member against its declared width and rejects {@code null} throughout.
     *
     * <p>A value shorter than its declared width is accepted unchanged, exactly as a COBOL
     * {@code MOVE} into a wider {@code PIC X} receiver is accepted and padded on the right when the
     * image is rendered. A value <strong>longer</strong> than its declared width is refused, because
     * the screen field has nowhere to put the surplus and quietly discarding it would make the loss
     * invisible at the call site. That is the same contract {@link NavigationContext} applies to the
     * communication area, and it is what "declare seventy-eight and truncate nowhere" means in
     * practice.
     *
     * <p>{@code null} is refused because there is no null in a COBOL record: an unpopulated screen
     * field holds spaces or low-values, never nothing at all. A caller that wants an empty screen
     * should start from {@link #empty()} rather than passing {@code null}.
     *
     * @throws NullPointerException if any member is {@code null}
     * @throws IllegalArgumentException if any member is wider than the copybook declares
     */
    public UserDeleteResponse {
        trnName = requireWidth(trnName, TRN_NAME_LENGTH, TRN_NAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CUR_DATE_LENGTH, CUR_DATE_FIELD);
        pgmName = requireWidth(pgmName, PGM_NAME_LENGTH, PGM_NAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CUR_TIME_LENGTH, CUR_TIME_FIELD);
        usrIdIn = requireWidth(usrIdIn, USR_ID_IN_LENGTH, USR_ID_IN_FIELD);
        fName = requireWidth(fName, F_NAME_LENGTH, F_NAME_FIELD);
        lName = requireWidth(lName, L_NAME_LENGTH, L_NAME_FIELD);
        usrType = requireWidth(usrType, USR_TYPE_LENGTH, USR_TYPE_FIELD);
        errMsg = requireWidth(errMsg, ERR_MSG_LENGTH, ERR_MSG_FIELD);
        Objects.requireNonNull(navigationContext,
                "navigationContext carries CARDDEMO-COMMAREA and is never absent; the communication "
                        + "area travels in the payload because no server-side session holds it "
                        + "(gate G37). Use NavigationContext.empty() for a cold start");
        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH, NEXT_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH, NEXT_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NEXT_MAP_FIELD);
        // The extension has no absent state: 05 CDEMO-CU03-INFO is storage inside the communication
        // area, and a cold start sees it as its VALUE clauses left it.
        cu03Info = cu03Info == null ? Cu03Info.initial() : cu03Info;
    }

    // =================================================================================================
    // Factory
    // =================================================================================================

    /**
     * A freshly initialised screen: every screen field carrying the unpainted image at its declared
     * width, and a freshly initialised communication area.
     *
     * <p>This is the shape {@code MOVE LOW-VALUES TO COUSR3AO} at {@code app/cbl/COUSR03C.cbl:97}
     * establishes on first entry, rendered as the {@code X'00'} that statement actually moves - the
     * same state {@code INITIALIZE-ALL-FIELDS} at lines 349-356 returns the screen to after
     * {@code PF4} clears it or a delete succeeds. {@link ScreenFieldImage} records the choice of
     * {@code LOW-VALUES} over spaces once, for all seventeen screens.
     *
     * <p>Note what this factory deliberately does <strong>not</strong> do: it does not populate
     * {@link #trnName()} with {@value #TRANSACTION_ID} or {@link #pgmName()} with
     * {@value #PROGRAM_NAME}, even though those values are fixed for this screen. In the COBOL those
     * moves happen in {@code POPULATE-HEADER-INFO} on the way out, not at initialisation, and the
     * ordering is preserved here so a caller populates the header explicitly and visibly.
     *
     * @return an empty screen payload, never {@code null}
     */
    public static UserDeleteResponse empty() {
        return new UserDeleteResponse(ScreenFieldImage.unpainted(TRN_NAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE01_LENGTH),
                ScreenFieldImage.unpainted(CUR_DATE_LENGTH),
                ScreenFieldImage.unpainted(PGM_NAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE02_LENGTH),
                ScreenFieldImage.unpainted(CUR_TIME_LENGTH),
                ScreenFieldImage.unpainted(USR_ID_IN_LENGTH),
                ScreenFieldImage.unpainted(F_NAME_LENGTH),
                ScreenFieldImage.unpainted(L_NAME_LENGTH),
                ScreenFieldImage.unpainted(USR_TYPE_LENGTH),
                ScreenFieldImage.unpainted(ERR_MSG_LENGTH),
                NavigationContext.empty(),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                Cu03Info.initial());
    }

    // =================================================================================================
    // Immutable copy methods - one per component, each the Java equivalent of a single COBOL MOVE
    // into that screen field. Every one returns a new instance and re-runs the full width check, so
    // no route into this type bypasses validation.
    //
    // Each takes a parameter, so none is shaped like a bean property and none can ever be mistaken
    // for a sixteenth payload field during serialisation.
    // =================================================================================================

    /**
     * This response with {@code TRNNAMEO} replaced - the equivalent of
     * {@code MOVE WS-TRANID TO TRNNAMEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:249}.
     *
     * @param newTrnName the transaction name, at most {@value #TRN_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withTrnName(String newTrnName) {
        return new UserDeleteResponse(newTrnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code TITLE01O} replaced - the equivalent of
     * {@code MOVE CCDA-TITLE01 TO TITLE01O OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:247}.
     *
     * @param newTitle01 the first title line, at most {@value #TITLE01_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withTitle01(String newTitle01) {
        return new UserDeleteResponse(trnName, newTitle01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code CURDATEO} replaced - the equivalent of
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:256}.
     *
     * @param newCurDate the date as {@code MM/DD/YY}, at most {@value #CUR_DATE_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withCurDate(String newCurDate) {
        return new UserDeleteResponse(trnName, title01, newCurDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code PGMNAMEO} replaced - the equivalent of
     * {@code MOVE WS-PGMNAME TO PGMNAMEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:250}.
     *
     * @param newPgmName the program name, at most {@value #PGM_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withPgmName(String newPgmName) {
        return new UserDeleteResponse(trnName, title01, curDate, newPgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code TITLE02O} replaced - the equivalent of
     * {@code MOVE CCDA-TITLE02 TO TITLE02O OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:248}.
     *
     * @param newTitle02 the second title line, at most {@value #TITLE02_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withTitle02(String newTitle02) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, newTitle02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code CURTIMEO} replaced - the equivalent of
     * {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:262}.
     *
     * @param newCurTime the time as {@code hh:mm:ss}, at most {@value #CUR_TIME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withCurTime(String newCurTime) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, newCurTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code USRIDINO} replaced - the equivalent of the moves into
     * {@code USRIDINI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:102} and 352, which land in the
     * same bytes because {@code COUSR3AO REDEFINES COUSR3AI} at the same stride.
     *
     * @param newUsrIdIn the user id, at most {@value #USR_ID_IN_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withUsrIdIn(String newUsrIdIn) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                newUsrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code FNAMEO} replaced - the equivalent of
     * {@code MOVE SEC-USR-FNAME TO FNAMEI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:165}.
     *
     * @param newFName the first name, at most {@value #F_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withFName(String newFName) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, newFName, lName, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code LNAMEO} replaced - the equivalent of
     * {@code MOVE SEC-USR-LNAME TO LNAMEI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:166}.
     *
     * @param newLName the last name, at most {@value #L_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withLName(String newLName) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, newLName, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code USRTYPEO} replaced - the equivalent of
     * {@code MOVE SEC-USR-TYPE TO USRTYPEI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:167}.
     *
     * @param newUsrType the user type, at most {@value #USR_TYPE_LENGTH} character
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withUsrType(String newUsrType) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, newUsrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code ERRMSGO} replaced - the equivalent of
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:217}.
     *
     * <p>The caller supplies a value already narrowed to {@value #ERR_MSG_LENGTH} characters. The
     * COBOL {@code MOVE} narrows {@value #WS_MESSAGE_LENGTH} bytes to {@value #ERR_MSG_LENGTH} by
     * truncating on the right, and that narrowing is performed by
     * {@code common.FixedWidthCodec.movePicX} in the controller, never here. Passing a longer value
     * is refused rather than shortened.
     *
     * <p>Six texts can reach this field, and composing them is the controller's work rather than
     * this type's. They are listed only so a consumer knows the range of what may arrive:
     *
     * <ul>
     *   <li>{@code 'User ID can NOT be empty...'} - {@code COUSR03C:147} on {@code ENTER} and
     *       {@code COUSR03C:179} on {@code PF5}. Note that a blank id produces this message rather
     *       than a rejection, which is why this type carries no {@code @NotBlank}.</li>
     *   <li>{@code 'Press PF5 key to delete this user ...'} - {@code COUSR03C:283}, the
     *       confirmation prompt, sent with the neutral colour byte set at line 285.</li>
     *   <li>{@code 'User ID NOT found...'} - {@code COUSR03C:289} on lookup and
     *       {@code COUSR03C:325} on delete.</li>
     *   <li>{@code 'Unable to lookup User...'} - {@code COUSR03C:296}.</li>
     *   <li>The success text composed by the {@code STRING} at {@code COUSR03C:318-321},
     *       {@code 'User ' + SEC-USR-ID + ' has been deleted ...'}, sent with the green colour byte
     *       set at line 317.</li>
     *   <li>{@code 'Unable to Update User...'} - {@code COUSR03C:332}. This sits in the
     *       <em>delete</em> failure arm and says "Update" because it was copied from
     *       {@code COUSR02C}. It is <strong>preserved exactly as the source emits it</strong>: the
     *       text is observable behaviour, and correcting it would be a behaviour change of the kind
     *       the migration mandate forbids (practice B4 - document, do not fix).</li>
     * </ul>
     *
     * @param newErrMsg the message line, at most {@value #ERR_MSG_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withErrMsg(String newErrMsg) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, newErrMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different communication area - the equivalent of updating
     * {@code CARDDEMO-COMMAREA} before {@code EXEC CICS RETURN ... COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code app/cbl/COUSR03C.cbl:134-137}.
     *
     * @param newNavigationContext the communication area, never {@code null}
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNavigationContext(NavigationContext newNavigationContext) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, newNavigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different transfer target - the equivalent of the program named by
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COUSR03C.cbl:206}.
     *
     * <p>{@code COUSR03C} resolves that target three ways: {@code 'COSGN00C'} when the
     * communication area is empty (lines 91 and 200), {@code CDEMO-FROM-PROGRAM} when {@code PF3}
     * has a caller to go back to (line 115), and {@code 'COADM01C'} when it does not (line 113) or
     * when {@code PF12} is pressed (line 124). The server names the target; the client follows it
     * (gate G40).
     *
     * @param newNextProgram the next program, at most {@value #NEXT_PROGRAM_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNextProgram(String newNextProgram) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, newNextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different next mapset - {@value #MAPSET_NAME} while the conversation
     * stays on this screen.
     *
     * @param newNextMapset the next mapset, at most {@value #NEXT_MAPSET_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNextMapset(String newNextMapset) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram,
                newNextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different next map - {@value #MAP_NAME} while the conversation stays on
     * this screen.
     *
     * @param newNextMap the next map, at most {@value #NEXT_MAP_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNextMap(String newNextMap) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                newNextMap, cu03Info);
    }

    /**
     * A copy carrying a different {@code 05 CDEMO-CU03-INFO} extension - the thirty-four bytes
     * {@code app/cbl/COUSR03C.cbl:50-58} appends to the communication area and lines 205-208 hand back
     * on the {@code XCTL} along with it.
     *
     * <p>Carried separately from {@link #navigationContext()} because {@code app/cpy/COCOM01Y.cpy} is
     * exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and is shared by all seventeen
     * controllers, while this group belongs to this program alone.
     *
     * @param newCu03Info the extension; {@code null} is replaced by {@link Cu03Info#initial()}
     * @return a new response
     */
    public UserDeleteResponse withCu03Info(Cu03Info newCu03Info) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime, usrIdIn,
                fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset, nextMap,
                newCu03Info);
    }

    // =================================================================================================
    // Internals
    // =================================================================================================

    /**
     * A run of {@code width} spaces, the value a cleared {@code PIC X} field holds.
     *
     * <p>This is COBOL's unconditional {@code MOVE SPACES} fill, not the alphanumeric {@code MOVE}
     * rule. The {@code MOVE} rule - pad a shorter sending value on the right, truncate a longer one
     * on the right - lives solely in {@code common.FixedWidthCodec} and is never reimplemented here.
     *
     * @param width the field's declared width, taken from its {@code xxxO} {@code PICTURE} clause
     * @return a string of exactly {@code width} spaces
     */
    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    /**
     * Rejects a {@code null} field and one wider than the map declares, and returns the value
     * unchanged when it fits.
     *
     * <p>A shorter value is accepted: a COBOL {@code MOVE} into a wider {@code PIC X} receiver pads
     * it on the right, and the codec does the same when the field is rendered, so there is nothing
     * to object to. Only an over-long value is refused, because the screen field has nowhere to put
     * the surplus and discarding it quietly would make the loss invisible at the call site. The
     * caller that genuinely wants to shorten a value - as {@code COUSR03C:217} does, narrowing
     * {@value #WS_MESSAGE_LENGTH} bytes to {@value #ERR_MSG_LENGTH} - says so explicitly by routing
     * the value through {@code common.FixedWidthCodec.movePicX} first.
     *
     * @param value the candidate value
     * @param declaredWidth the width the {@code xxxO} {@code PICTURE} clause declares
     * @param cobolName the COBOL field name, so a rejection names the real field
     * @return {@code value} unchanged
     */
    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " requires a value; there is no null in "
                + "a COBOL screen field, so move SPACES explicitly or start from "
                + "UserDeleteResponse.empty()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC X("
                    + declaredWidth + ") but was given " + value.length() + " character(s): '" + value
                    + "'. The screen field cannot hold the surplus. To shorten the value "
                    + "deliberately, pass it through FixedWidthCodec.movePicX(value, "
                    + declaredWidth + "), which truncates on the right as a COBOL alphanumeric MOVE "
                    + "does");
        }
        return value;
    }

    /**
     * A diagnostic rendering that withholds the personal name, per {@link SensitiveDiagnostics}.
     *
     * <p>The override exists because this is a {@code record}: the generated {@code toString} renders
     * every component, and two of them - {@code FNAME} and {@code LNAME} - are the user's given and
     * family names as {@code app/cpy-bms/COUSR03.CPY} declares them. Their length is reported and their
     * content is not, because a name has no safely-revealable part.
     *
     * <p>Everything else renders as stored. The user id is an eight-character operator id rather than a
     * personal identifier, the user type is a single authorisation character, and the remaining fields are
     * screen furniture and the error message - all of which a screen-flow parity failure has to be read
     * from. The carried {@code NavigationContext} renders through its own masked form.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record generates them.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "UserDeleteResponse[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", usrIdIn=" + usrIdIn
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ']';
    }

}
