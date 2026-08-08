package com.vsergeychik.carddemo.user.dto;

import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * The inbound payload of {@code POST /api/signon} - CICS transaction {@code CC00}, program
 * {@code COSGN00C} - as an immutable value.
 *
 * <p>This type is a <strong>field-for-field projection of the {@code xxxI} items of
 * {@code 01 COSGN0AI}</strong> in {@code app/cpy-bms/COSGN00.CPY}. It is a transcription of a screen
 * contract, not an API design: every payload member traces to one name-labelled {@code DFHMDF}
 * definition in {@code app/bms/COSGN00.bms}, and every width is the one the symbolic map declares.
 *
 * <h2>The eleven screen fields, and nothing else</h2>
 *
 * {@code app/bms/COSGN00.bms} contains 37 {@code DFHMDF} definitions of which exactly
 * {@value #MAP_FIELD_COUNT} are name-labelled; the other 26 are literal {@code INITIAL} screen
 * furniture - captions and box characters - and are deliberately absent here. The {@code xxxI} count,
 * the {@code xxxO} count and the name-labelled {@code DFHMDF} count all equal
 * {@value #MAP_FIELD_COUNT}, so the map is internally consistent and any deviation in this class
 * would be an error in this class.
 *
 * <table border="1">
 *   <caption>The screen contract, measured from both authorities and cross-checked</caption>
 *   <tr><th>#</th><th>{@code DFHMDF}</th><th>{@code xxxI} item</th><th>Width</th>
 *       <th>{@code POS}</th><th>Component</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME}</td><td>{@code TRNNAMEI}</td><td>{@code X(4)}</td>
 *       <td>(1,8)</td><td>{@link #trnName()}</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01}</td><td>{@code TITLE01I}</td><td>{@code X(40)}</td>
 *       <td>(1,21)</td><td>{@link #title01()}</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE}</td><td>{@code CURDATEI}</td><td>{@code X(8)}</td>
 *       <td>(1,71)</td><td>{@link #curDate()}</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME}</td><td>{@code PGMNAMEI}</td><td>{@code X(8)}</td>
 *       <td>(2,8)</td><td>{@link #pgmName()}</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02}</td><td>{@code TITLE02I}</td><td>{@code X(40)}</td>
 *       <td>(2,21)</td><td>{@link #title02()}</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME}</td><td>{@code CURTIMEI}</td><td><strong>{@code X(9)}</strong></td>
 *       <td>(2,71)</td><td>{@link #curTime()}</td></tr>
 *   <tr><td>7</td><td>{@code APPLID}</td><td>{@code APPLIDI}</td><td>{@code X(8)}</td>
 *       <td>(3,8)</td><td>{@link #applId()}</td></tr>
 *   <tr><td>8</td><td>{@code SYSID}</td><td>{@code SYSIDI}</td><td>{@code X(8)}</td>
 *       <td>(3,71)</td><td>{@link #sysId()}</td></tr>
 *   <tr><td>9</td><td>{@code USERID}</td><td>{@code USERIDI}</td><td>{@code X(8)}</td>
 *       <td>(19,43)</td><td>{@link #userId()}</td></tr>
 *   <tr><td>10</td><td>{@code PASSWD}</td><td>{@code PASSWDI}</td><td>{@code X(8)}</td>
 *       <td>(20,43)</td><td>{@link #passwd()}</td></tr>
 *   <tr><td>11</td><td>{@code ERRMSG}</td><td>{@code ERRMSGI}</td><td><strong>{@code X(78)}</strong></td>
 *       <td>(23,1)</td><td>{@link #errMsg()}</td></tr>
 * </table>
 *
 * Every width above was read from {@code app/cpy-bms/COSGN00.CPY} and independently from the
 * {@code LENGTH=} operand of the matching {@code DFHMDF} in {@code app/bms/COSGN00.bms}. The two
 * authorities agree field for field, which is why no width here is inferred, defaulted or computed.
 *
 * <h2>Two widths look wrong and are not</h2>
 *
 * <ul>
 *   <li><strong>{@code CURTIME} is nine characters, not eight.</strong>
 *       {@code app/cpy-bms/COSGN00.CPY:54} declares {@code CURTIMEI PIC X(9)} and
 *       {@code app/bms/COSGN00.bms} declares {@code LENGTH=9}. {@code COSGN00} is the only one of the
 *       five user screens whose time field is nine wide; the other four use eight. Normalising this
 *       to eight "for consistency" would silently shorten the rendered time, so the divergence is
 *       preserved exactly as declared.</li>
 *   <li><strong>{@code ERRMSG} is 78 characters, not 80.</strong> {@code COSGN00.CPY:84} declares
 *       {@code ERRMSGI PIC X(78)} while {@code app/cbl/COSGN00C.cbl:38} declares
 *       {@code WS-MESSAGE PIC X(80)}, and {@code COSGN00C.cbl:149} moves the one into the other with
 *       {@code MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO} - an 80-to-78 truncation on the right. That
 *       truncation belongs to the controller and is performed by
 *       {@code common.FixedWidthCodec.movePicX}. <strong>This class only declares the width as
 *       78</strong>; it neither declares 80 nor truncates anything itself.</li>
 * </ul>
 *
 * <h2>What is deliberately not here</h2>
 *
 * The symbolic map surrounds each {@code xxxI} item with metadata, and none of it is a payload
 * member:
 *
 * <ul>
 *   <li>{@code xxxL} is {@code COMP PIC S9(4)}, the input length CICS reports, and it doubles as the
 *       cursor signal - {@code COSGN00C.cbl:121} and {@code :250} do {@code MOVE -1 TO USERIDL} and
 *       {@code :126} and {@code :244} do {@code MOVE -1 TO PASSWDL} to place the cursor on the field
 *       at fault.</li>
 *   <li>{@code xxxF} is the attribute and flag byte, and {@code xxxA} is its {@code REDEFINES}
 *       view.</li>
 *   <li>{@code xxxC} is the colour byte, the target of {@code MOVE DFHRED}, and {@code xxxP},
 *       {@code xxxH} and {@code xxxV} are the remaining output attribute bytes of
 *       {@code 01 COSGN0AO REDEFINES COSGN0AI}.</li>
 *   <li>The 12-byte {@code TIOAPFX} prefix at {@code COSGN00.CPY:18} and every intervening
 *       {@code FILLER X(4)} span are reserved storage and are not exposed.</li>
 * </ul>
 *
 * Field highlighting and cursor placement belong to {@code user.SignOnController} together with
 * {@code common.FieldAttributeSetter}, which is where the attribute bytes are set. Keeping them out
 * of the payload is what stops a client from asserting its own screen attributes.
 *
 * <h2>Conversation state travels in the payload, never in a session</h2>
 *
 * CICS is pseudo-conversational: the transaction ends after painting the screen and is re-entered
 * from the beginning on the next key press, so the only state that survives is what the program
 * handed back. That shape is preserved exactly. Beyond the {@value #MAP_FIELD_COUNT} map fields this
 * payload carries {@link #navigationContext()} - the 160-byte {@code CARDDEMO-COMMAREA} of
 * {@code app/cpy/COCOM01Y.cpy}, which every one of the seventeen online programs copies - and
 * {@link #aid()}, the resolved key indication that {@code COSGN00C} reads from {@code EIBAID}.
 *
 * <p>Those two members are the <em>only</em> ones with no {@code DFHMDF} behind them. They are
 * mandated precisely so that no server-side state is required, and this class is correspondingly free
 * of every mechanism that would reintroduce one: no servlet session handle, no session-scoped
 * attribute or bean, no thread-local carrier, no static cache and no mutable static field. A static
 * holder would be a session by another name and would break request isolation.
 *
 * <p>The enter-versus-re-enter flag is <strong>not</strong> duplicated here. It is
 * {@code CDEMO-PGM-CONTEXT PIC 9(01)} inside the communication area, with
 * {@code 88 CDEMO-PGM-ENTER VALUE 0} and {@code 88 CDEMO-PGM-REENTER VALUE 1} at
 * {@code COCOM01Y.cpy:30-31}; {@link #inEnterState()} and {@link #inReenterState()} read through to
 * it so the flag has exactly one home and cannot drift.
 *
 * <h2>Credentials travel in the clear, deliberately</h2>
 *
 * {@link #passwd()} is a plaintext {@code X(8)} member matching {@code SEC-USR-PWD PIC X(08)} of
 * {@code app/cpy/CSUSR01Y.cpy:21}, because {@code app/cbl/COSGN00C.cbl:223} compares the two
 * directly with {@code IF SEC-USR-PWD = WS-USER-PWD}. No hashing, no password encoder, no encoding
 * and no authentication framework is applied: this is a like-for-like migration, so strengthening
 * the comparison would change observable behaviour, and weakening or removing the field would delete
 * a real screen input. Plaintext credentials are an <strong>inherited property of the legacy
 * design</strong> and an <strong>explicit non-goal</strong> of this migration; the characteristic is
 * documented here so it stays visible rather than buried in generated code. The one concession is
 * diagnostic hygiene: {@link #toString()} never reproduces the value - see its own notes.
 *
 * <p>{@code app/bms/COSGN00.bms} declares {@code PASSWD} with {@code ATTRB=(DRK,FSET,UNPROT)}, and
 * {@code DRK} is terminal non-display. That is how the legacy design masked the field on the 3270,
 * and it is a presentation attribute - metadata - not a reason to alter, encode or omit the payload
 * member.
 *
 * <h2>This class holds no logic</h2>
 *
 * It is a payload contract and nothing more: no validation method, no normalisation, no comparison.
 * All decision logic lives in {@code user.SignOnService}, which owns the ordered blank checks of
 * {@code COSGN00C.cbl:117-130}, the {@code FUNCTION UPPER-CASE} normalisation of both identifier and
 * password at {@code :132-136}, the plaintext compare at {@code :223} and the {@code RESP}
 * 0/13/other split at {@code :221-257}.
 *
 * <p>Consequently the eleven screen fields are carried <strong>exactly as they arrive</strong> -
 * untrimmed, unpadded, not coerced between {@code null} and empty, and not upper-cased. The service
 * has to distinguish {@code SPACES} from {@code LOW-VALUES} at {@code COSGN00C.cbl:118} and
 * {@code :123}, so it must see what the client actually sent; padding a short value to its declared
 * width here would fabricate data and pre-empt that very test.
 *
 * <h2>Validation is bounded by what the program does</h2>
 *
 * Each screen member carries {@link Size} at its symbolic-map width, which is the enforcement point
 * for the field widths CICS used to enforce for free. <strong>Nothing further is asserted.</strong> In
 * particular no presence or non-blank constraint is placed on any screen input, because
 * {@code COSGN00C} <strong>accepts</strong> blank input and answers it with a specific per-field
 * message - {@code 'Please enter User ID ...'} at {@code :120} and
 * {@code 'Please enter Password ...'} at {@code :125}. Rejecting the request with a 400 instead
 * would replace that message with a different observable behaviour, which a like-for-like migration
 * may not do. {@link Size} is satisfied by a {@code null} value, so it constrains width without ever
 * making a field mandatory. No pattern, format or bespoke constraint is applied either: the program
 * performs no such check, so neither does this contract.
 *
 * <h2>Serialisation</h2>
 *
 * The module's web configuration owns Jackson policy - it leaves DTO property names untransformed so
 * each one still traces 1:1 to an {@code xxxI} item, and it declines trimming, empty-string-to-null
 * coercion and null exclusion so that space-padded {@code PIC X(n)} values survive a round trip.
 * This class therefore carries no Jackson annotation at all: no property rename, no naming strategy,
 * no inclusion filter, no ignore and no custom serialiser. A record's components are its JSON
 * properties, and the build compiles with {@code -parameters}, so the canonical constructor binds by
 * component name with nothing further declared.
 *
 * <p>{@link #inEnterState()} and {@link #inReenterState()} are named so that they are not bean
 * getters. That is intentional: they are <em>derived</em> from the communication area rather than
 * stored beside it, so emitting them would put properties on the wire that the canonical constructor
 * cannot accept back, breaking a serialise-then-deserialise round trip.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // A first entry: the client sends the credentials and a freshly initialised communication area.
 * SignOnRequest request = new SignOnRequest(
 *         "CC00", title01, curDate, "COSGN00C", title02, curTime, applId, sysId,
 *         "ADMIN001", keyedPassword, null, NavigationContext.empty(), "ENTER");
 *
 * // The service decides; the payload only carries. Blank is a valid value, not a rejection.
 * if (request.inReenterState()) {
 *     // validate what was typed, and highlight the field at fault
 * }
 * </pre>
 *
 * @param trnName  {@code TRNNAMEI PIC X(4)}, {@code COSGN00.CPY:24}: the transaction identifier,
 *                 {@code 'CC00'} per {@code COSGN00C.cbl:37}
 * @param title01  {@code TITLE01I PIC X(40)}, {@code COSGN00.CPY:30}: the first title line, supplied
 *                 from {@code common.ScreenTitles}
 * @param curDate  {@code CURDATEI PIC X(8)}, {@code COSGN00.CPY:36}: the current date as
 *                 {@code MM/DD/YY}, assembled at {@code COSGN00C.cbl:186-190}
 * @param pgmName  {@code PGMNAMEI PIC X(8)}, {@code COSGN00.CPY:42}: the program name,
 *                 {@code 'COSGN00C'} per {@code COSGN00C.cbl:36}
 * @param title02  {@code TITLE02I PIC X(40)}, {@code COSGN00.CPY:48}: the second title line, supplied
 *                 from {@code common.ScreenTitles}
 * @param curTime  {@code CURTIMEI PIC X(9)}, {@code COSGN00.CPY:54}: the current time as
 *                 {@code HH:MM:SS}, assembled at {@code COSGN00C.cbl:192-196}. Nine wide, not eight
 * @param applId   {@code APPLIDI PIC X(8)}, {@code COSGN00.CPY:60}: the CICS application identifier
 *                 from {@code EXEC CICS ASSIGN APPLID} at {@code COSGN00C.cbl:198-200}. Only this
 *                 screen carries it
 * @param sysId    {@code SYSIDI PIC X(8)}, {@code COSGN00.CPY:66}: the CICS system identifier from
 *                 {@code EXEC CICS ASSIGN SYSID} at {@code COSGN00C.cbl:202-204}. Only this screen
 *                 carries it
 * @param userId   {@code USERIDI PIC X(8)}, {@code COSGN00.CPY:72}: the identifier keyed by the user,
 *                 matching {@code SEC-USR-ID PIC X(08)} of {@code CSUSR01Y.cpy:18}. May be blank -
 *                 {@code COSGN00C.cbl:118} tests for {@code SPACES OR LOW-VALUES} and answers with a
 *                 message
 * @param passwd   {@code PASSWDI PIC X(8)}, {@code COSGN00.CPY:78}: the password keyed by the user,
 *                 matching {@code SEC-USR-PWD PIC X(08)} of {@code CSUSR01Y.cpy:21}. Carried in the
 *                 clear, deliberately - see the class notes. May be blank
 * @param errMsg   {@code ERRMSGI PIC X(78)}, {@code COSGN00.CPY:84}: the error line. 78 wide against
 *                 an 80-byte {@code WS-MESSAGE}; the narrowing move is the controller's
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}.
 *                 <strong>Not a map field</strong> - one of the two mandated exceptions that make a
 *                 server-side session unnecessary. Never widened: it is exactly 160 bytes and is
 *                 shared by all seventeen controllers, so an extra field would break every other
 *                 screen's byte image. A {@code null} is normalised to {@link NavigationContext#empty()}
 * @param aid      the resolved key indication, the {@code EIBAID} that {@code COSGN00C} tests inline,
 *                 as the token produced by {@code common.PfKeyResolver} - {@code 'ENTER'},
 *                 {@code 'PFK03'} and so on. <strong>Not a map field</strong> - the second mandated
 *                 exception. {@code null} means no key has been resolved, mirroring the empty result
 *                 that key resolution returns for an unrecognised {@code EIBAID}
 */
public record SignOnRequest(@Size(max = TRNNAME_LENGTH) String trnName,
                            @Size(max = TITLE01_LENGTH) String title01,
                            @Size(max = CURDATE_LENGTH) String curDate,
                            @Size(max = PGMNAME_LENGTH) String pgmName,
                            @Size(max = TITLE02_LENGTH) String title02,
                            @Size(max = CURTIME_LENGTH) String curTime,
                            @Size(max = APPLID_LENGTH) String applId,
                            @Size(max = SYSID_LENGTH) String sysId,
                            @Size(max = USERID_LENGTH) String userId,
                            @Size(max = PASSWD_LENGTH) String passwd,
                            @Size(max = ERRMSG_LENGTH) String errMsg,
                            NavigationContext navigationContext,
                            @Size(max = AID_LENGTH) String aid) {

    // =================================================================================================
    // The declared widths. Each one is a literal read from the xxxI PICTURE clause of
    // app/cpy-bms/COSGN00.CPY and confirmed against the LENGTH= operand of the matching DFHMDF in
    // app/bms/COSGN00.bms. No width is derived from another, so a mistyped value cannot propagate.
    // They are declared once here and referenced by the @Size annotations above, which keeps the
    // number that a reviewer checks and the number the validator enforces provably the same one.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}, {@code COSGN00.CPY:24}; {@code TRNNAME DFHMDF LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}, {@code COSGN00.CPY:30}; {@code TITLE01 DFHMDF LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}, {@code COSGN00.CPY:36}; {@code CURDATE DFHMDF LENGTH=8}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, {@code COSGN00.CPY:42}; {@code PGMNAME DFHMDF LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}, {@code COSGN00.CPY:48}; {@code TITLE02 DFHMDF LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(9)}, {@code COSGN00.CPY:54}; {@code CURTIME DFHMDF LENGTH=9}.
     *
     * <p><strong>Nine, not eight.</strong> {@code COSGN00} is the only one of the five user screens
     * whose time field is nine characters wide. The value is preserved as declared rather than
     * harmonised with its four siblings.
     */
    public static final int CURTIME_LENGTH = 9;

    /** {@code APPLIDI PIC X(8)}, {@code COSGN00.CPY:60}; {@code APPLID DFHMDF LENGTH=8}. */
    public static final int APPLID_LENGTH = 8;

    /** {@code SYSIDI PIC X(8)}, {@code COSGN00.CPY:66}; {@code SYSID DFHMDF LENGTH=8}. */
    public static final int SYSID_LENGTH = 8;

    /**
     * {@code USERIDI PIC X(8)}, {@code COSGN00.CPY:72}; {@code USERID DFHMDF LENGTH=8}.
     *
     * <p>Equal to {@code SEC-USR-ID PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy:18}, which is what makes
     * the keyed value usable directly as the {@code USRSEC} record key at
     * {@code app/cbl/COSGN00C.cbl:215-216}. The field is named {@code USERID} on this screen;
     * {@code COUSR02} and {@code COUSR03} name their equivalent {@code USRIDIN}, and that difference
     * is left alone rather than harmonised.
     */
    public static final int USERID_LENGTH = 8;

    /**
     * {@code PASSWDI PIC X(8)}, {@code COSGN00.CPY:78}; {@code PASSWD DFHMDF LENGTH=8}.
     *
     * <p>Equal to {@code SEC-USR-PWD PIC X(08)} of {@code app/cpy/CSUSR01Y.cpy:21}, the field that
     * {@code app/cbl/COSGN00C.cbl:223} compares in plaintext.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code COSGN00.CPY:84}; {@code ERRMSG DFHMDF LENGTH=78}.
     *
     * <p><strong>78, not 80.</strong> {@code app/cbl/COSGN00C.cbl:38} declares
     * {@code WS-MESSAGE PIC X(80)} and {@code :149} moves it into the 78-character output item,
     * truncating two characters on the right. This constant is the receiving width; the narrowing
     * move itself is performed by the controller through {@code common.FixedWidthCodec.movePicX}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * The width of the resolved key token, {@code CCARD-AID PIC X(5)} of
     * {@code app/cpy/CVCRD01Y.cpy}.
     *
     * <p>This is the width of the tokens {@code common.PfKeyResolver} produces - {@code 'ENTER'},
     * {@code 'CLEAR'}, {@code 'PFK01'} through {@code 'PFK12'} - not a screen field width, because
     * the key indication is not a screen field.
     */
    public static final int AID_LENGTH = 5;

    /**
     * The number of payload members that project a name-labelled {@code DFHMDF} field:
     * {@value #MAP_FIELD_COUNT}.
     *
     * <p>Stated as a constant so the screen contract is assertable rather than merely described.
     * {@code app/bms/COSGN00.bms} holds 37 {@code DFHMDF} definitions of which exactly this many are
     * name-labelled, and {@code app/cpy-bms/COSGN00.CPY} declares exactly this many {@code xxxI}
     * items and exactly this many {@code xxxO} items. The record has two further components -
     * {@link #navigationContext()} and {@link #aid()} - which carry conversation state and are the
     * only mandated exceptions to the one-member-per-{@code DFHMDF} rule.
     */
    public static final int MAP_FIELD_COUNT = 11;

    /**
     * The fixed replacement {@link #toString()} prints in place of the password.
     *
     * <p>It is a constant rather than a value-derived mask on purpose: because it never varies, a
     * diagnostic string cannot disclose the password, its length, or whether one was supplied at all.
     */
    private static final String PASSWD_REDACTED = "[REDACTED]";

    /**
     * Normalises an absent communication area to the freshly initialised one.
     *
     * <p>A CICS transaction always has a communication area - it is a fixed 160-byte storage area, and
     * {@link NavigationContext#empty()} is precisely its initialised state: spaces, zeros, and
     * therefore already in {@code CDEMO-PGM-ENTER}. There is no {@code null} COMMAREA to model, so a
     * missing one is completed here rather than propagated. That keeps {@link #inEnterState()} and
     * {@link #inReenterState()} total, so neither can throw for a payload that simply omitted the
     * member.
     *
     * <p>The eleven screen fields are deliberately <strong>not</strong> touched. They are carried
     * exactly as they arrive, including {@code null} and including a value shorter than its declared
     * width, because {@code user.SignOnService} has to distinguish {@code SPACES} from
     * {@code LOW-VALUES} at {@code app/cbl/COSGN00C.cbl:118} and {@code :123}. Padding or trimming
     * them here would pre-empt that test and change the message the user sees.
     */
    public SignOnRequest {
        navigationContext = Objects.requireNonNullElseGet(navigationContext, NavigationContext::empty);
    }

    /**
     * Whether this request is a first entry - {@code CDEMO-PGM-CONTEXT} holding
     * {@code 88 CDEMO-PGM-ENTER VALUE 0}, {@code app/cpy/COCOM01Y.cpy:30}. Paint the screen; validate
     * nothing.
     *
     * <p>A read-through to {@link NavigationContext#isEnter()} rather than a second copy of the flag,
     * so the context byte has exactly one home and the two cannot disagree.
     *
     * @return {@code true} when the carried communication area is in the enter state
     */
    public boolean inEnterState() {
        return navigationContext.isEnter();
    }

    /**
     * Whether this request is a re-entry - {@code CDEMO-PGM-CONTEXT} holding
     * {@code 88 CDEMO-PGM-REENTER VALUE 1}, {@code app/cpy/COCOM01Y.cpy:31}. Validate what was typed,
     * and let {@code common.FieldAttributeSetter} apply the error highlight, which is gated on this
     * state.
     *
     * <p>Also a read-through, and deliberately <strong>not</strong> written as the negation of
     * {@link #inEnterState()}: {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold any digit,
     * so for a value such as {@code 9} both predicates are correctly {@code false}. Defining either as
     * the other's complement would invent a state the copybook does not describe.
     *
     * @return {@code true} when the carried communication area is in the re-enter state
     */
    public boolean inReenterState() {
        return navigationContext.isReenter();
    }

    /**
     * A diagnostic rendering that reports every component except the password, which is replaced by
     * {@value #PASSWD_REDACTED}.
     *
     * <p>The override exists solely for that substitution. A record's generated {@code toString}
     * includes every component, so inheriting it would reproduce the plaintext password in any log
     * line, exception message or debugger view that rendered this object. Carrying the credential in
     * the clear is required for parity with {@code app/cbl/COSGN00C.cbl:223}; broadcasting it is not,
     * and the two concerns are separable.
     *
     * <p>{@code equals} and {@code hashCode} remain as the record generates them, including the
     * password: they are value semantics and disclose nothing.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "SignOnRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", applId=" + applId
                + ", sysId=" + sysId
                + ", userId=" + userId
                + ", passwd=" + PASSWD_REDACTED
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ']';
    }
}
