package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The inbound payload of {@code POST /api/users} - CICS transaction {@code CU01}, program
 * {@code app/cbl/COUSR01C.cbl}, the Add User screen.
 *
 * <p>This type is a <strong>field-for-field projection of the {@code xxxI} items of
 * {@code 01 COUSR1AI}</strong> in {@code app/cpy-bms/COUSR01.CPY}. It is a transcription of a screen
 * contract, not a designed API: nothing here was chosen for elegance, and every width, every name and
 * the field order itself are taken from the legacy source exactly as declared.
 *
 * <h2>The source, transcribed</h2>
 *
 * {@code app/cpy-bms/COUSR01.CPY} opens the input view at line 17 with a twelve-byte
 * {@code TIOAPFX} prefix and then repeats one five-item group per screen field, so each field
 * occupies {@code 7 + n} bytes:
 *
 * <pre>
 *  01  COUSR1AI.
 *      02  FILLER PIC X(12).            &lt;- TIOAPFX prefix, reserved, never exposed
 *      02  TRNNAMEL    COMP  PIC  S9(4). &lt;- reported input length + cursor signal (metadata)
 *      02  TRNNAMEF    PICTURE X.        &lt;- attribute/flag byte             (metadata)
 *      02  FILLER REDEFINES TRNNAMEF.
 *        03 TRNNAMEA    PICTURE X.       &lt;- attribute view                  (metadata)
 *      02  FILLER   PICTURE X(4).        &lt;- reserved span, never exposed
 *      02  TRNNAMEI  PIC X(4).           &lt;- THE PAYLOAD FIELD
 *      ... eleven more groups in the same shape ...
 *  01  COUSR1AO REDEFINES COUSR1AI.      &lt;- line 91: the output view, xxxC/xxxP/xxxH/xxxV/xxxO
 * </pre>
 *
 * Three independent counts agree, which is what makes the field list of this class verifiable rather
 * than asserted:
 *
 * <ul>
 *   <li>{@code app/bms/COUSR01.bms} declares <strong>28</strong> {@code DFHMDF} fields of which
 *       <strong>exactly 12 are name-labelled</strong>. The other sixteen are literal {@code INITIAL}
 *       screen furniture - {@code 'Tran:'}, {@code 'Date:'}, {@code 'Add User'}, {@code '(8 Char)'},
 *       {@code '(A=Admin, U=User)'}, the {@code F}-key legend - and are <strong>not</strong> fields.</li>
 *   <li>The symbolic map declares <strong>12</strong> {@code xxxI} items.</li>
 *   <li>The symbolic map declares <strong>12</strong> {@code xxxO} items.</li>
 * </ul>
 *
 * <h2>The twelve payload fields, in symbolic-map order</h2>
 *
 * The order below is the {@code .CPY} order, so this class can be eye-diffed against the copybook a
 * line at a time. Each width is stated twice in the sources and the two statements agree: the
 * {@code xxxI} {@code PICTURE} clause and the {@code LENGTH=} of the matching {@code DFHMDF}.
 *
 * <table border="1">
 *   <caption>Screen field to record component</caption>
 *   <tr><th>#</th><th>{@code DFHMDF}</th><th>{@code xxxI} item</th><th>Width</th>
 *       <th>Component</th><th>Note</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME} (bms L34)</td><td>{@code TRNNAMEI} (cpy L24)</td>
 *       <td>{@value #TRNNAME_LENGTH}</td><td>{@link #trnName()}</td>
 *       <td>{@value #TRANSACTION_ID}, from {@code COUSR01C:37}</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01} (bms L38)</td><td>{@code TITLE01I} (cpy L30)</td>
 *       <td>{@value #TITLE01_LENGTH}</td><td>{@link #title01()}</td>
 *       <td>{@code CCDA-TITLE01}, moved at {@code COUSR01C:218}</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE} (bms L47)</td><td>{@code CURDATEI} (cpy L36)</td>
 *       <td>{@value #CURDATE_LENGTH}</td><td>{@link #curDate()}</td>
 *       <td>{@code mm/dd/yy}, composed at {@code COUSR01C:223-227}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME} (bms L57)</td><td>{@code PGMNAMEI} (cpy L42)</td>
 *       <td>{@value #PGMNAME_LENGTH}</td><td>{@link #pgmName()}</td>
 *       <td>{@value #PROGRAM_NAME}, from {@code COUSR01C:36}</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02} (bms L61)</td><td>{@code TITLE02I} (cpy L48)</td>
 *       <td>{@value #TITLE02_LENGTH}</td><td>{@link #title02()}</td>
 *       <td>{@code CCDA-TITLE02}, moved at {@code COUSR01C:219}</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME} (bms L70)</td><td>{@code CURTIMEI} (cpy L54)</td>
 *       <td>{@value #CURTIME_LENGTH}</td><td>{@link #curTime()}</td>
 *       <td>{@code hh:mm:ss}. <strong>Eight here</strong>; only {@code COSGN00} uses nine</td></tr>
 *   <tr><td>7</td><td>{@code FNAME} (bms L84)</td><td>{@code FNAMEI} (cpy L60)</td>
 *       <td>{@value #FNAME_LENGTH}</td><td>{@link #fName()}</td>
 *       <td>Matches {@code SEC-USR-FNAME PIC X(20)}</td></tr>
 *   <tr><td>8</td><td>{@code LNAME} (bms L97)</td><td>{@code LNAMEI} (cpy L66)</td>
 *       <td>{@value #LNAME_LENGTH}</td><td>{@link #lName()}</td>
 *       <td>Matches {@code SEC-USR-LNAME PIC X(20)}</td></tr>
 *   <tr><td>9</td><td>{@code USERID} (bms L111)</td><td>{@code USERIDI} (cpy L72)</td>
 *       <td>{@value #USERID_LENGTH}</td><td>{@link #userId()}</td>
 *       <td><strong>{@code USERID}, not {@code USRIDIN}</strong> - see below</td></tr>
 *   <tr><td>10</td><td>{@code PASSWD} (bms L126)</td><td>{@code PASSWDI} (cpy L78)</td>
 *       <td>{@value #PASSWD_LENGTH}</td><td>{@link #passwd()}</td>
 *       <td>Matches {@code SEC-USR-PWD PIC X(08)}; plaintext - see below</td></tr>
 *   <tr><td>11</td><td>{@code USRTYPE} (bms L141)</td><td>{@code USRTYPEI} (cpy L84)</td>
 *       <td>{@value #USRTYPE_LENGTH}</td><td>{@link #usrType()}</td>
 *       <td>Matches {@code SEC-USR-TYPE PIC X(01)}; {@code 'A'} admin, {@code 'U'} user</td></tr>
 *   <tr><td>12</td><td>{@code ERRMSG} (bms L151)</td><td>{@code ERRMSGI} (cpy L90)</td>
 *       <td>{@value #ERRMSG_LENGTH}</td><td>{@link #errMsg()}</td>
 *       <td><strong>78, not 80</strong> - see below</td></tr>
 * </table>
 *
 * All twelve are {@code PIC X(n)}, so all twelve are {@link String}. There is no numeric field on this
 * screen at all, and consequently no {@code double}, no {@code float} and no {@code BigDecimal}.
 *
 * <h2>Three divergences that are preserved, not tidied</h2>
 *
 * This migration documents inconsistencies instead of regularising them, because a "harmonised" name
 * or width would make a real difference invisible to a field-by-field diff:
 *
 * <ul>
 *   <li><strong>The identifier field is {@code USERID}, not {@code USRIDIN}.</strong>
 *       {@code app/cpy-bms/COUSR02.CPY} and {@code app/cpy-bms/COUSR03.CPY} really do call theirs
 *       {@code USRIDIN}; this screen does not, and {@code COUSR01C} names {@code USERIDI} at lines
 *       130, 154 and 290 and {@code USERIDL} at lines 134 and 265. Renaming it here for consistency
 *       with its two sibling screens would silently rewrite a screen contract.</li>
 *   <li><strong>{@code CURTIME} is eight characters wide.</strong> The {@code hh:mm:ss} value composed
 *       at {@code COUSR01C:229-233} needs exactly eight. Only {@code app/cpy-bms/COSGN00.CPY}
 *       declares a nine-character time field.</li>
 *   <li><strong>{@code ERRMSG} is 78 characters while the message that feeds it is 80.</strong>
 *       {@code COUSR01C:38} declares {@code WS-MESSAGE PIC X(80)} and {@code COUSR01C:188} does
 *       {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR1AO}, an alphanumeric {@code MOVE} into a narrower
 *       receiver, so COBOL discards the two rightmost characters. The declared width here is
 *       <strong>78</strong>, matching the field. The 80-to-78 truncation itself belongs to the
 *       controller and is performed by {@code common.FixedWidthCodec.movePicX}, which truncates on the
 *       right exactly as COBOL does. <strong>Nothing is truncated in this class.</strong></li>
 * </ul>
 *
 * <h2>What is deliberately absent: the {@code xxxL}, {@code xxxF} and {@code xxxA} items</h2>
 *
 * Every payload component of this record traces to a name-labelled {@code DFHMDF} line of
 * {@code app/bms/COUSR01.bms}. The remaining symbolic-map items are presentation and terminal
 * mechanics, never payload:
 *
 * <ul>
 *   <li>{@code xxxL} is the length CICS reports for the field and doubles as the cursor signal - a
 *       {@code MOVE -1} positions the cursor there. {@code COUSR01C} uses it that way at lines 86,
 *       100, 122, 128, 134, 140, 146, 149, 265, 272 and 289, always to steer the terminal and never
 *       as data.</li>
 *   <li>{@code xxxF}, and {@code xxxA} which redefines it, carry the attribute byte.</li>
 *   <li>{@code xxxC} on the output view carries the <em>colour</em>: {@code COUSR01C:254} does
 *       {@code MOVE DFHGREEN TO ERRMSGC OF COUSR1AO} to turn a success message green.
 *       {@code xxxP}, {@code xxxH} and {@code xxxV} are the remaining output attribute bytes.</li>
 *   <li>The twelve-byte {@code TIOAPFX} {@code FILLER} and each {@code FILLER PICTURE X(4)} span are
 *       reserved storage.</li>
 * </ul>
 *
 * Those concerns belong to {@code common.BmsAttributes} and {@code common.FieldAttributeSetter}, so
 * none of them appears here - not as a component, and not as an accessor that a JSON serialiser could
 * discover.
 *
 * <h2>Why there is no {@code @NotBlank} anywhere in this class</h2>
 *
 * This screen carries the clearest evidence in the application that Bean Validation must not be used
 * to reject empty input. {@code COUSR01C:117-151} is a single {@code EVALUATE TRUE} whose first
 * matching arm wins, and it <strong>accepts</strong> a blank value on all five input fields, replying
 * with a field-specific message and re-painting the screen:
 *
 * <table border="1">
 *   <caption>The ordered blank-field chain of {@code PROCESS-ENTER-KEY}</caption>
 *   <tr><th>Order</th><th>Tested item</th><th>Line</th><th>Message</th></tr>
 *   <tr><td>1</td><td>{@code FNAMEI}</td><td>{@code COUSR01C:120}</td>
 *       <td>{@code 'First Name can NOT be empty...'}</td></tr>
 *   <tr><td>2</td><td>{@code LNAMEI}</td><td>{@code COUSR01C:126}</td>
 *       <td>{@code 'Last Name can NOT be empty...'}</td></tr>
 *   <tr><td>3</td><td>{@code USERIDI}</td><td>{@code COUSR01C:132}</td>
 *       <td>{@code 'User ID can NOT be empty...'}</td></tr>
 *   <tr><td>4</td><td>{@code PASSWDI}</td><td>{@code COUSR01C:138}</td>
 *       <td>{@code 'Password can NOT be empty...'}</td></tr>
 *   <tr><td>5</td><td>{@code USRTYPEI}</td><td>{@code COUSR01C:144}</td>
 *       <td>{@code 'User Type can NOT be empty...'}</td></tr>
 * </table>
 *
 * A {@code @NotBlank} or {@code @NotNull} component would turn each of those five answers into a
 * framework-level rejection before the controller ever ran, losing the message, losing the ordering
 * and losing the cursor placement. That is a changed business rule, which this migration forbids. The
 * chain stays where the COBOL puts it: in the controller.
 *
 * <p>Note also that the COBOL tests {@code = SPACES OR LOW-VALUES}, so a run of spaces is a
 * <em>legitimate</em> value that reaches the program. {@code @Size(max = n)} is therefore the only
 * constraint used here - it accepts {@code null}, accepts the empty string and accepts a space-padded
 * value, and refuses only a value too wide for the field to hold. Nothing else is asserted: no
 * {@code @Pattern} on {@code USRTYPE}, no character-set check, no case rule. In particular
 * {@code COUSR01C} performs <strong>no</strong> case normalisation - unlike the sign-on program,
 * which upper-cases what it receives - and that asymmetry is preserved by holding this class free of
 * any transformation whatsoever.
 *
 * <h2>Conversation state travels in the payload; there is no session</h2>
 *
 * CICS is pseudo-conversational: {@code COUSR01C} ends after painting a screen and is re-entered from
 * the top on the next keystroke, so the only state that survives is what it handed back in its
 * communication area. Three members reproduce that, and they are the sole deliberate exception to the
 * "every field traces to a {@code DFHMDF}" rule above:
 *
 * <ul>
 *   <li>{@link #navigationContext()} - the 160-byte {@code CARDDEMO-COMMAREA} of
 *       {@code app/cpy/COCOM01Y.cpy}, referenced through the one shared type and never widened.</li>
 *   <li>{@link #aid()} - the resolved AID token, which is what {@code COUSR01C:90-103} branches on
 *       when it evaluates {@code EIBAID} against {@code DFHENTER}, {@code DFHPF3} and
 *       {@code DFHPF4}. This program tests {@code EIBAID} inline rather than through
 *       {@code CSSTRPFY}, and the token form is produced by {@code common.PfKeyResolver}.</li>
 *   <li>The enter-versus-re-enter context, readable through {@link #pgmEnter()} and
 *       {@link #pgmReenter()}. It is <em>not</em> a member: {@code CDEMO-PGM-CONTEXT PIC 9(01)} with
 *       its {@code 88 CDEMO-PGM-ENTER VALUE 0} and {@code 88 CDEMO-PGM-REENTER VALUE 1} lives inside
 *       the communication area, so those two accessors read through to it rather than duplicating
 *       it.</li>
 * </ul>
 *
 * Consequently this class is free of {@code HttpSession}, {@code @SessionAttributes},
 * {@code @SessionScope}, {@code ThreadLocal}, any static cache and any static mutable field. The
 * client holds this payload between calls exactly as CICS handed the communication area back and
 * forth.
 *
 * <h2>Credentials travel in the clear, deliberately</h2>
 *
 * {@link #passwd()} is a plaintext eight-character value because that is precisely what the legacy
 * program does with it: {@code COUSR01C:157} is {@code MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD}, so
 * the typed characters are stored verbatim into {@code SEC-USR-PWD PIC X(08)} of the 80-byte
 * {@code USRSEC} record defined by {@code app/cpy/CSUSR01Y.cpy}. No hash, no encoder, no encoding and
 * no authentication framework is applied here, because any of those would change observable behaviour
 * and would pull in a framework this migration excludes; equally, the field is not removed. Plaintext
 * credential storage is an <strong>inherited property of the legacy design and an explicit non-goal of
 * this migration</strong>, recorded here so the characteristic stays visible instead of being buried
 * in generated code.
 *
 * <p>The one protection applied is a diagnostic one that changes no behaviour: {@link #toString()}
 * renders {@link #PASSWORD_MASK} in place of the value, unconditionally, so the password cannot
 * escape through a log line, an exception message or a debugger dump. Nothing in this class logs.
 *
 * <h2>Serialisation</h2>
 *
 * A record's components are its JSON properties, so this class needs no Jackson annotation at all and
 * carries none: no rename, no naming strategy, no inclusion rule, no ignore, no custom serialiser.
 * Field names reach the wire untransformed, and a serialise-then-deserialise round trip returns an
 * equal value with every trailing space intact, because no component is trimmed, coerced from an empty
 * string to {@code null}, or excluded when blank. The two read-through accessors are deliberately
 * named without a {@code get} or {@code is} prefix so that a serialiser cannot mistake them for
 * properties and put a derived field on the wire.
 *
 * <h2>What this class does not do</h2>
 *
 * It holds no logic. There is no validation method, no message composition, no normalisation and no
 * repository call: the ordered blank chain, the {@code USRSEC} write, the success message with its
 * green attribute, the {@code DUPKEY}/{@code DUPREC} arm yielding {@code 'User ID already exist...'}
 * and the {@code 'Unable to Add User...'} fallback at {@code COUSR01C:270} all belong to the
 * controller. This is a payload contract and nothing more.
 *
 * <p>It also performs no rejection of its own. The canonical constructor is the implicit one, so every
 * component is stored exactly as given, {@code null} included. That matters for parity: a
 * <em>missing</em> communication area is a legitimate, handled input - {@code COUSR01C:78} tests
 * {@code IF EIBCALEN = 0} and transfers to {@code COSGN00C} - so a {@code null}
 * {@link #navigationContext()} must be representable for the controller to reproduce that branch.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // A first-entry request: an empty screen, no key pressed yet, a fresh communication area.
 * UserAddRequest request = new UserAddRequest(
 *         UserAddRequest.TRANSACTION_ID, title01, curDate, UserAddRequest.PROGRAM_NAME,
 *         title02, curTime,
 *         "", "", "", "", "", "",
 *         NavigationContext.empty(), "");
 *
 * if (request.pgmReenter()) {
 *     // Re-entry: validate what was typed, exactly as COUSR01C:117-151 orders it.
 * }
 * </pre>
 *
 * @param trnName  {@code TRNNAMEI PIC X(4)} of {@code app/cpy-bms/COUSR01.CPY:24}: the transaction
 *                 identifier shown in the header, {@value #TRANSACTION_ID}
 * @param title01  {@code TITLE01I PIC X(40)} of line 30: the first title line
 * @param curDate  {@code CURDATEI PIC X(8)} of line 36: the current date as {@code mm/dd/yy}
 * @param pgmName  {@code PGMNAMEI PIC X(8)} of line 42: the program name, {@value #PROGRAM_NAME}
 * @param title02  {@code TITLE02I PIC X(40)} of line 48: the second title line
 * @param curTime  {@code CURTIMEI PIC X(8)} of line 54: the current time as {@code hh:mm:ss}
 * @param fName    {@code FNAMEI PIC X(20)} of line 60: the new user's first name, moved to
 *                 {@code SEC-USR-FNAME} at {@code COUSR01C:155}
 * @param lName    {@code LNAMEI PIC X(20)} of line 66: the new user's last name, moved to
 *                 {@code SEC-USR-LNAME} at {@code COUSR01C:156}
 * @param userId   {@code USERIDI PIC X(8)} of line 72: the new user's identifier, moved to
 *                 {@code SEC-USR-ID} at {@code COUSR01C:154} and used as the {@code USRSEC} key
 * @param passwd   {@code PASSWDI PIC X(8)} of line 78: the new user's password, stored in the clear
 *                 into {@code SEC-USR-PWD} at {@code COUSR01C:157}
 * @param usrType  {@code USRTYPEI PIC X(1)} of line 84: {@code 'A'} for an administrator,
 *                 {@code 'U'} for a regular user, moved to {@code SEC-USR-TYPE} at
 *                 {@code COUSR01C:158}
 * @param errMsg   {@code ERRMSGI PIC X(78)} of line 90: the message line, 78 characters and not the
 *                 80 of {@code WS-MESSAGE}
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy};
 *                 {@code null} represents the {@code EIBCALEN = 0} case of {@code COUSR01C:78}
 * @param aid      the resolved AID token, at most {@value #AID_LENGTH} characters, carrying which key
 *                 the terminal sent for {@code COUSR01C:90-103} to branch on
 */
public record UserAddRequest(
        @Size(max = UserAddRequest.TRNNAME_LENGTH) String trnName,
        @Size(max = UserAddRequest.TITLE01_LENGTH) String title01,
        @Size(max = UserAddRequest.CURDATE_LENGTH) String curDate,
        @Size(max = UserAddRequest.PGMNAME_LENGTH) String pgmName,
        @Size(max = UserAddRequest.TITLE02_LENGTH) String title02,
        @Size(max = UserAddRequest.CURTIME_LENGTH) String curTime,
        @Size(max = UserAddRequest.FNAME_LENGTH) String fName,
        @Size(max = UserAddRequest.LNAME_LENGTH) String lName,
        @Size(max = UserAddRequest.USERID_LENGTH) String userId,
        @Size(max = UserAddRequest.PASSWD_LENGTH) String passwd,
        @Size(max = UserAddRequest.USRTYPE_LENGTH) String usrType,
        @Size(max = UserAddRequest.ERRMSG_LENGTH) String errMsg,
        NavigationContext navigationContext,
        @Size(max = UserAddRequest.AID_LENGTH) String aid) {

    // =================================================================================================
    // The COBOL names of the twelve payload fields, spelled exactly as app/cpy-bms/COUSR01.CPY spells
    // them. These are what a field-by-field diff keys on, so a "tidied" spelling here would hide a real
    // difference. Note USERIDI: this screen does NOT use the USRIDIN of COUSR02 and COUSR03.
    // =================================================================================================

    /** Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEI}, {@code COUSR01.CPY:24}. */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /** Symbolic-map item behind {@link #title01()}: {@code TITLE01I}, {@code COUSR01.CPY:30}. */
    public static final String TITLE01_FIELD = "TITLE01I";

    /** Symbolic-map item behind {@link #curDate()}: {@code CURDATEI}, {@code COUSR01.CPY:36}. */
    public static final String CURDATE_FIELD = "CURDATEI";

    /** Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEI}, {@code COUSR01.CPY:42}. */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /** Symbolic-map item behind {@link #title02()}: {@code TITLE02I}, {@code COUSR01.CPY:48}. */
    public static final String TITLE02_FIELD = "TITLE02I";

    /** Symbolic-map item behind {@link #curTime()}: {@code CURTIMEI}, {@code COUSR01.CPY:54}. */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /** Symbolic-map item behind {@link #fName()}: {@code FNAMEI}, {@code COUSR01.CPY:60}. */
    public static final String FNAME_FIELD = "FNAMEI";

    /** Symbolic-map item behind {@link #lName()}: {@code LNAMEI}, {@code COUSR01.CPY:66}. */
    public static final String LNAME_FIELD = "LNAMEI";

    /**
     * Symbolic-map item behind {@link #userId()}: {@code USERIDI}, {@code COUSR01.CPY:72}.
     *
     * <p>Spelled {@code USERIDI} and not {@code USRIDIN} - the Update User and Delete User screens use
     * the latter, this one does not, and the divergence is preserved rather than harmonised.
     */
    public static final String USERID_FIELD = "USERIDI";

    /** Symbolic-map item behind {@link #passwd()}: {@code PASSWDI}, {@code COUSR01.CPY:78}. */
    public static final String PASSWD_FIELD = "PASSWDI";

    /** Symbolic-map item behind {@link #usrType()}: {@code USRTYPEI}, {@code COUSR01.CPY:84}. */
    public static final String USRTYPE_FIELD = "USRTYPEI";

    /** Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGI}, {@code COUSR01.CPY:90}. */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    // =================================================================================================
    // The declared widths. Every value is the literal from the xxxI PICTURE clause, corroborated by the
    // LENGTH= of the matching name-labelled DFHMDF in app/bms/COUSR01.bms. Not one of them is inferred
    // from a sibling screen, from the USRSEC record or from anything else.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}; {@code DFHMDF TRNNAME LENGTH=4} at {@code COUSR01.bms:36}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}; {@code DFHMDF TITLE01 LENGTH=40} at {@code COUSR01.bms:40}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}; {@code DFHMDF CURDATE LENGTH=8} at {@code COUSR01.bms:49}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}; {@code DFHMDF PGMNAME LENGTH=8} at {@code COUSR01.bms:59}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}; {@code DFHMDF TITLE02 LENGTH=40} at {@code COUSR01.bms:63}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}; {@code DFHMDF CURTIME LENGTH=8} at {@code COUSR01.bms:72}.
     *
     * <p><strong>Eight, not nine.</strong> {@code hh:mm:ss} is eight characters and this map declares
     * eight. Only {@code app/cpy-bms/COSGN00.CPY} declares a nine-character time field, and that width
     * must not be carried across to this screen.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code FNAMEI PIC X(20)}; {@code DFHMDF FNAME LENGTH=20} at {@code COUSR01.bms:87}.
     *
     * <p>Equal to {@code SEC-USR-FNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy:19}, so the
     * {@code MOVE} at {@code COUSR01C:155} is width-for-width and loses nothing.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEI PIC X(20)}; {@code DFHMDF LNAME LENGTH=20} at {@code COUSR01.bms:100}.
     *
     * <p>Equal to {@code SEC-USR-LNAME PIC X(20)} of {@code app/cpy/CSUSR01Y.cpy:20}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code USERIDI PIC X(8)}; {@code DFHMDF USERID LENGTH=8} at {@code COUSR01.bms:114}.
     *
     * <p>Equal to {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy:18}, which is also the
     * {@code USRSEC} key length used by the {@code WRITE} at {@code COUSR01C:240-248}. The screen even
     * says so: the literal {@code '(8 Char)'} sits beside the field at {@code COUSR01.bms:120}.
     */
    public static final int USERID_LENGTH = 8;

    /**
     * {@code PASSWDI PIC X(8)}; {@code DFHMDF PASSWD LENGTH=8} at {@code COUSR01.bms:129}.
     *
     * <p>Equal to {@code SEC-USR-PWD PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy:21}, the field the
     * {@code MOVE} at {@code COUSR01C:157} fills verbatim. The screen field is declared
     * {@code ATTRB=(DRK,FSET,UNPROT)} with {@code HILIGHT=UNDERLINE}: {@code DRK} is terminal
     * non-display, which is how the legacy design kept the typed password off the glass. That is a
     * presentation attribute and is no reason to alter, mask or widen the value carried here.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code USRTYPEI PIC X(1)}; {@code DFHMDF USRTYPE LENGTH=1} at {@code COUSR01.bms:144}.
     *
     * <p>Equal to {@code SEC-USR-TYPE PIC X(01)} of {@code app/cpy/CSUSR01Y.cpy:22}. The screen
     * legend {@code '(A=Admin, U=User)'} at {@code COUSR01.bms:150} names the two values the
     * application acts on, but the program applies no {@code @Pattern}-style check to the byte and
     * neither does this class.
     */
    public static final int USRTYPE_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}; {@code DFHMDF ERRMSG LENGTH=78} at {@code COUSR01.bms:153}.
     *
     * <p><strong>78, not 80.</strong> {@code COUSR01C:38} declares {@code WS-MESSAGE PIC X(80)} and
     * {@code COUSR01C:188} moves it into this narrower field, so COBOL drops the two rightmost
     * characters. This constant is the receiver width; a caller performing that shortening deliberately
     * passes the message through {@code common.FixedWidthCodec.movePicX(message, ERRMSG_LENGTH)}, which
     * truncates on the right as an alphanumeric {@code MOVE} does. This class truncates nothing.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * The declared width of {@link #aid()}, matching {@code common.PfKeyResolver}'s AID token length
     * and {@code CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy}, the copybook that gives the
     * token its canonical five-character form.
     */
    public static final int AID_LENGTH = 5;

    // =================================================================================================
    // Screen identity. Byte-exact literals from the program and its map, useful to a caller populating
    // the header or recording the last screen shown, and not payload in themselves.
    // =================================================================================================

    /** {@code WS-TRANID VALUE 'CU01'} of {@code COUSR01C:37}, the CSD transaction for this screen. */
    public static final String TRANSACTION_ID = "CU01";

    /** {@code WS-PGMNAME VALUE 'COUSR01C'} of {@code COUSR01C:36}. */
    public static final String PROGRAM_NAME = "COUSR01C";

    /**
     * The BMS map, {@code MAP('COUSR1A')} of {@code COUSR01C:191}.
     *
     * <p>Seven characters, which is exactly what {@code CDEMO-LAST-MAP PIC X(7)} of
     * {@code app/cpy/COCOM01Y.cpy:43} holds.
     */
    public static final String MAP_NAME = "COUSR1A";

    /**
     * The BMS mapset, {@code MAPSET('COUSR01')} of {@code COUSR01C:192}.
     *
     * <p>Seven characters, which is exactly what {@code CDEMO-LAST-MAPSET PIC X(7)} of
     * {@code app/cpy/COCOM01Y.cpy:44} holds.
     */
    public static final String MAPSET_NAME = "COUSR01";

    /**
     * What {@link #toString()} renders in place of {@link #passwd()}, always and regardless of the
     * value held - eight characters, matching {@value #PASSWD_LENGTH}, so the shape of the field
     * remains legible while the value never appears in a log line, an exception message or a dump.
     */
    public static final String PASSWORD_MASK = SensitiveDiagnostics.REDACTED;

    /**
     * The twelve payload field names in symbolic-map order, immutable.
     *
     * <p>This list is the machine-readable form of the traceability obligation: its size is the number
     * of name-labelled {@code DFHMDF} definitions in {@code app/bms/COUSR01.bms} and the number of
     * {@code xxxI} items in {@code app/cpy-bms/COUSR01.CPY}, all three of which are twelve, and its
     * order is the copybook's own. {@link #navigationContext()} and {@link #aid()} are deliberately
     * absent: they are conversation state rather than screen fields.
     */
    public static final List<String> MAP_FIELD_NAMES = List.of(TRNNAME_FIELD,
            TITLE01_FIELD,
            CURDATE_FIELD,
            PGMNAME_FIELD,
            TITLE02_FIELD,
            CURTIME_FIELD,
            FNAME_FIELD,
            LNAME_FIELD,
            USERID_FIELD,
            PASSWD_FIELD,
            USRTYPE_FIELD,
            ERRMSG_FIELD);

    // =================================================================================================
    // The enter-versus-re-enter context, read THROUGH the communication area rather than duplicated
    // beside it. Neither method is named with a get or is prefix, deliberately: an accessor shaped like
    // a bean getter would be discovered by a JSON serialiser and would put a derived property on the
    // wire that the canonical constructor cannot accept back, breaking a round trip outright.
    // =================================================================================================

    /**
     * Whether the carried communication area asserts {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry,
     * on which {@code COUSR01C} paints the screen and validates nothing.
     *
     * <p>The value is read from {@link NavigationContext#isEnter()} on every call and is stored nowhere
     * here, so it cannot drift out of step with the {@code CDEMO-PGM-CONTEXT} byte it summarises.
     *
     * <p>A {@code null} communication area yields {@code false}: with no area there is no
     * {@code CDEMO-PGM-CONTEXT} to test, and no context is asserted. This is the {@code EIBCALEN = 0}
     * case that {@code COUSR01C:78} handles ahead of any context test by transferring to
     * {@code COSGN00C}, so a controller reproducing that program tests for the missing area first and
     * never reaches this question. Note also that {@link #pgmEnter()} and {@link #pgmReenter()} are not
     * negations of one another: {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold any digit,
     * so both can be {@code false}.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *         {@code CDEMO-PGM-ENTER} value
     */
    public boolean pgmEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether the carried communication area asserts {@code 88 CDEMO-PGM-REENTER VALUE 1} - re-entry,
     * on which {@code COUSR01C:88-103} receives the map and evaluates {@code EIBAID}, and on which the
     * ordered blank-field chain of {@code COUSR01C:117-151} runs.
     *
     * <p>This is the condition {@code COUSR01C:83} tests as {@code IF NOT CDEMO-PGM-REENTER}. As with
     * {@link #pgmEnter()}, the value is read through to {@link NavigationContext#isReenter()}, a
     * {@code null} area yields {@code false}, and the two predicates are not complements.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *         {@code CDEMO-PGM-REENTER} value
     */
    public boolean pgmReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    /**
     * A diagnostic rendering that reproduces the shape a record's generated {@code toString()} would
     * produce, with one difference: {@link #passwd()} is replaced by {@link #PASSWORD_MASK}.
     *
     * <p>The substitution is unconditional. It does not inspect the value, does not branch on whether
     * one is present and does not reveal its length, so no execution path can put the password into a
     * log line, an exception message, a test report or a debugger dump. Every other component is
     * rendered exactly as held, trailing spaces and {@code null} included, because a diagnostic that
     * quietly trims what it prints is worse than useless when the subject is a fixed-width screen
     * field.
     *
     * <p>This override exists solely because the generated implementation would print the password. It
     * changes no behaviour: {@link #equals(Object)} and {@link #hashCode()} remain the generated
     * component-wise implementations and still consider the real value.
     *
     * @return the masked rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "UserAddRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", userId=" + userId
                + ", passwd=" + PASSWORD_MASK
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ']';
    }
}
