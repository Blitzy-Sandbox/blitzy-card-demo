package com.vsergeychik.carddemo.common;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The CICS communication area of the CardDemo online application - {@code 01 CARDDEMO-COMMAREA} of
 * {@code app/cpy/COCOM01Y.cpy} - as an immutable value carried in REST request and response bodies.
 * Exactly {@value #COMMAREA_LENGTH} bytes wide.
 *
 * <h2>The source, reproduced verbatim</h2>
 *
 * {@code app/cpy/COCOM01Y.cpy} lines 19-44 declare one group item, five subordinate groups, sixteen
 * elementary items and four {@code 88}-level condition names:
 *
 * <pre>
 *  01 CARDDEMO-COMMAREA.
 *     05 CDEMO-GENERAL-INFO.
 *        10 CDEMO-FROM-TRANID             PIC X(04).
 *        10 CDEMO-FROM-PROGRAM            PIC X(08).
 *        10 CDEMO-TO-TRANID               PIC X(04).
 *        10 CDEMO-TO-PROGRAM              PIC X(08).
 *        10 CDEMO-USER-ID                 PIC X(08).
 *        10 CDEMO-USER-TYPE               PIC X(01).
 *           88 CDEMO-USRTYP-ADMIN         VALUE 'A'.
 *           88 CDEMO-USRTYP-USER          VALUE 'U'.
 *        10 CDEMO-PGM-CONTEXT             PIC 9(01).
 *           88 CDEMO-PGM-ENTER            VALUE 0.
 *           88 CDEMO-PGM-REENTER          VALUE 1.
 *     05 CDEMO-CUSTOMER-INFO.
 *        10 CDEMO-CUST-ID                 PIC 9(09).
 *        10 CDEMO-CUST-FNAME              PIC X(25).
 *        10 CDEMO-CUST-MNAME              PIC X(25).
 *        10 CDEMO-CUST-LNAME              PIC X(25).
 *     05 CDEMO-ACCOUNT-INFO.
 *        10 CDEMO-ACCT-ID                 PIC 9(11).
 *        10 CDEMO-ACCT-STATUS             PIC X(01).
 *     05 CDEMO-CARD-INFO.
 *        10 CDEMO-CARD-NUM                PIC 9(16).
 *     05 CDEMO-MORE-INFO.
 *        10  CDEMO-LAST-MAP               PIC X(7).
 *        10  CDEMO-LAST-MAPSET            PIC X(7).
 * </pre>
 *
 * This copybook is copied by <strong>all seventeen</strong> CICS online programs of
 * {@code app/cbl}, which is what makes it the single most widely shared structure in the online
 * application and why exactly one Java type models it.
 *
 * <h2>The width table - the total is {@value #COMMAREA_LENGTH} and that number is gate-level</h2>
 *
 * <table border="1">
 *   <caption>Group geometry of {@code CARDDEMO-COMMAREA}</caption>
 *   <tr><th>Group</th><th>Elementary items</th><th>Bytes</th><th>Offset (0-based)</th></tr>
 *   <tr><td>{@code CDEMO-GENERAL-INFO}</td>
 *       <td>{@code X(04)} + {@code X(08)} + {@code X(04)} + {@code X(08)} + {@code X(08)}
 *           + {@code X(01)} + {@code 9(01)}</td>
 *       <td>{@value #GENERAL_INFO_LENGTH}</td><td>{@value #GENERAL_INFO_OFFSET}</td></tr>
 *   <tr><td>{@code CDEMO-CUSTOMER-INFO}</td>
 *       <td>{@code 9(09)} + {@code X(25)} times 3</td>
 *       <td>{@value #CUSTOMER_INFO_LENGTH}</td><td>{@value #CUSTOMER_INFO_OFFSET}</td></tr>
 *   <tr><td>{@code CDEMO-ACCOUNT-INFO}</td><td>{@code 9(11)} + {@code X(01)}</td>
 *       <td>{@value #ACCOUNT_INFO_LENGTH}</td><td>{@value #ACCOUNT_INFO_OFFSET}</td></tr>
 *   <tr><td>{@code CDEMO-CARD-INFO}</td><td>{@code 9(16)}</td>
 *       <td>{@value #CARD_INFO_LENGTH}</td><td>{@value #CARD_INFO_OFFSET}</td></tr>
 *   <tr><td>{@code CDEMO-MORE-INFO}</td><td>{@code X(7)} + {@code X(7)}</td>
 *       <td>{@value #MORE_INFO_LENGTH}</td><td>{@value #MORE_INFO_OFFSET}</td></tr>
 *   <tr><td><strong>Total</strong></td><td></td>
 *       <td><strong>{@value #COMMAREA_LENGTH}</strong></td><td></td></tr>
 * </table>
 *
 * 34 + 84 + 12 + 16 + 14 = {@value #COMMAREA_LENGTH}. That arithmetic is not merely documented, it is
 * <em>enforced</em>: {@link #LAYOUT} declares every span at an absolute offset derived from the
 * declared widths and hands the literal {@value #COMMAREA_LENGTH} to
 * {@link FixedWidthRecord.RecordLayout}, whose constructor self-check refuses to build a layout
 * whose storage spans do not sum to exactly that. A mistyped width therefore fails at class
 * initialisation, naming the offending descriptor, rather than silently shifting every field after
 * it. Each group's <em>internal</em> arithmetic is checked the same way, because the first field of
 * every group is anchored to that group's own offset while the rest follow from their predecessors -
 * so an error inside one group surfaces as a gap or an overlap at the next group boundary.
 *
 * <h2>{@code CDEMO-LAST-MAP} and {@code CDEMO-LAST-MAPSET} are {@code X(7)}, not {@code X(08)}</h2>
 *
 * The copybook declares the two map fields at lines 43-44 as {@code PIC X(7)} while
 * {@code CDEMO-FROM-PROGRAM} and {@code CDEMO-TO-PROGRAM} at lines 22 and 24 are {@code PIC X(08)}.
 * That asymmetry is deliberate and is preserved here exactly as declared. <strong>Program</strong>
 * names in this application really are eight characters - {@code COSGN00C}, {@code COADM01C},
 * {@code COMEN01C} - while <strong>map</strong> names really are seven, because a BMS symbolic-map
 * group item is formed as a seven-character map name plus a one-character direction suffix:
 * {@code COSGN0AI} for the input view and {@code COSGN0AO} for the output view. The eighth character
 * belongs to the suffix, so the map name itself cannot occupy it.
 *
 * <p>{@code app/cpy/CVCRD01Y.cpy} corroborates this independently: its {@code CCARD-NEXT-PROG} is
 * {@code PIC X(8)} while {@code CCARD-NEXT-MAPSET} and {@code CCARD-NEXT-MAP} are both
 * {@code PIC X(7)}.
 *
 * <p>Widening the two map fields to eight characters "for consistency" would make
 * {@code CDEMO-MORE-INFO} sixteen bytes and the record 162, which is why that change cannot be made
 * quietly: {@link #LAYOUT} would fail to initialise. The divergence is recorded here rather than
 * regularised, because this migration documents conflicts instead of fixing them.
 *
 * <h2>Why this type exists: there is no server-side session</h2>
 *
 * CICS is pseudo-conversational. A transaction paints a screen, ends, and is re-entered from the
 * beginning when the user presses a key; the only state that survives is what the program handed
 * back in its communication area. The migration preserves that shape exactly: conversation state -
 * this COMMAREA, the {@code EIBAID} key indication and the enter-versus-re-enter context - travels
 * in the request and response payloads and <strong>never</strong> becomes server-side state.
 *
 * <p>Consequently this type is a plain data carrier. It is deliberately free of
 * {@code HttpSession}, {@code @SessionAttributes}, {@code @SessionScope}, {@code @RequestScope},
 * {@code ThreadLocal}, any server-side cache keyed by user or terminal, and any static "current
 * context" accessor - a static holder would be a session by another name and would additionally
 * break request isolation. The client holds the value between calls, exactly as CICS handed the
 * COMMAREA back and forth. Seventeen controllers are stateless <em>because</em> their conversation
 * state is this object, passed in and handed back.
 *
 * <p>The same reasoning makes the type immutable, with no setter and no mutable field. Every
 * modification produces a new instance through a {@code withXxx} method, so a context that has been
 * handed to a collaborator cannot be changed underneath it.
 *
 * <h2>The four {@code 88}-level conditions, and why they are predicates rather than booleans</h2>
 *
 * <table border="1">
 *   <caption>Condition names declared over the two single-character fields</caption>
 *   <tr><th>Condition</th><th>Field</th><th>Value</th><th>Predicate</th><th>What depends on it</th></tr>
 *   <tr><td>{@code CDEMO-USRTYP-ADMIN} (L27)</td><td>{@code CDEMO-USER-TYPE}</td>
 *       <td>{@code 'A'}</td><td>{@link #isAdmin()}</td>
 *       <td>Role routing: {@code app/cbl/COSGN00C.cbl:230} tests it and transfers to
 *           {@code COADM01C} at line 232. It also filters the ten main-menu options of
 *           {@code COMEN01C} through {@code COMEN02Y}'s {@code X(01)} authorisation column</td></tr>
 *   <tr><td>{@code CDEMO-USRTYP-USER} (L28)</td><td>{@code CDEMO-USER-TYPE}</td>
 *       <td>{@code 'U'}</td><td>{@link #isUser()}</td>
 *       <td>{@code app/cbl/COSGN00C.cbl:237} transfers a regular user to {@code COMEN01C}.
 *           {@code app/cbl/COCRDLIC.cbl} asserts it with {@code SET CDEMO-USRTYP-USER TO TRUE} at
 *           lines 320, 388, 466, 522 and 550</td></tr>
 *   <tr><td>{@code CDEMO-PGM-ENTER} (L30)</td><td>{@code CDEMO-PGM-CONTEXT}</td>
 *       <td>{@code 0}</td><td>{@link #isEnter()}</td>
 *       <td>First entry - paint the screen, validate nothing. Sixteen sites reset it with
 *           {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}, among them
 *           {@code app/cbl/COSGN00C.cbl:228}</td></tr>
 *   <tr><td>{@code CDEMO-PGM-REENTER} (L31)</td><td>{@code CDEMO-PGM-CONTEXT}</td>
 *       <td>{@code 1}</td><td>{@link #isReenter()}</td>
 *       <td>Re-entry - validate what was typed. It is the conjunct in {@code app/cpy/CSSETATY.cpy}
 *           that gates the {@code DFHRED} colour and the {@code '*'} marker, so
 *           {@link FieldAttributeSetter} consumes it</td></tr>
 * </table>
 *
 * Each predicate tests the stored field directly. None is stored as a separate boolean, because a
 * boolean can drift out of step with the field it summarises, and none is defined as the negation of
 * its sibling, because <strong>the two conditions of each pair are not exhaustive</strong>.
 * {@code CDEMO-USER-TYPE} is {@code PIC X(01)} and may legitimately hold a space - which is exactly
 * what a freshly initialised COMMAREA holds - or any other character, in which case
 * {@link #isAdmin()} and {@link #isUser()} are <em>both</em> false. {@code CDEMO-PGM-CONTEXT} is
 * {@code PIC 9(01)} and may hold any digit, so {@link #isEnter()} and {@link #isReenter()} are both
 * false for, say, {@code 9}. Writing {@code isUser()} as {@code !isAdmin()} would change behaviour
 * for a blank user type by routing an uninitialised COMMAREA as a regular user.
 *
 * <p>The distinction matters when reading {@code COSGN00C}: its routing is
 * {@code IF CDEMO-USRTYP-ADMIN ... ELSE ...}, so the {@code ELSE} branch covers "not administrator",
 * which is a wider set than "is a regular user". A controller reproducing that program must
 * therefore branch on {@link #isAdmin()} with an else, and must not substitute {@link #isUser()}
 * for the else condition.
 *
 * <h2>Program transfer becomes response data</h2>
 *
 * The eight {@code EXEC CICS XCTL} sites of the online programs are resolved by the client, not by a
 * server-side forward, so no redirect chain and no session affinity is introduced.
 * {@link #toProgram()} and {@link #toTranid()} supply the {@code nextProgram} of the four
 * COMMAREA-driven transfers - {@code COACTUPC:957}, {@code COACTVWC:350}, {@code COCRDSLC:332} and
 * {@code COCRDUPC:474}, all {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} - while {@link #lastMap()} and
 * {@link #lastMapset()} record the screen the transferring program was displaying, as
 * {@code app/cbl/COCRDLIC.cbl} does at lines 322-323, 390-391, 425-426 and 468-469. The two
 * hard-coded sign-on routes become a role on the sign-on response, and
 * {@code COCRDLIC:403}'s literal target and {@code COCRDLIC:539} and {@code :567}'s
 * {@code CCARD-NEXT-PROG} target are likewise carried as response data.
 *
 * <h2>Two wire formats, both supported</h2>
 *
 * <ul>
 *   <li><strong>JSON</strong> is the REST wire format. A Java record's components are its JSON
 *       properties, so the payload is exactly the sixteen copybook fields; a round trip preserves
 *       trailing spaces because no component is trimmed. Exactly one component carries a custom
 *       serialiser: {@link #cardNum()} travels as a <strong>{@value #CARD_NUM_LENGTH}-digit decimal
 *       string</strong> rather than as a JSON number, for the reason set out under
 *       {@link CardNumberSerializer}. The four condition predicates are marked {@link JsonIgnore}
 *       because
 *       they are <em>derived</em> from {@code CDEMO-USER-TYPE} and {@code CDEMO-PGM-CONTEXT} rather
 *       than stored beside them. Emitting them would put four properties on the wire that the
 *       canonical constructor cannot accept back - making a serialise-then-deserialise round trip
 *       fail outright - and would additionally let a payload assert a role that contradicts the
 *       user-type byte it travels with.</li>
 *   <li><strong>The {@value #COMMAREA_LENGTH}-byte fixed-width image</strong> is what the parity
 *       harness fingerprints and the field differ compares, field by field. It is produced by
 *       {@link #toFixedWidth(FixedWidthCodec)} and read back by
 *       {@link #fromFixedWidth(FixedWidthCodec, byte[])}.</li>
 * </ul>
 *
 * Every pad and every truncation in that image is performed by {@link FixedWidthCodec} and by
 * nothing else: character fields pad and truncate on the right, numeric {@code DISPLAY} fields on
 * the left, and this class implements neither rule itself. Keeping the move rules in a single seam
 * is what makes the direction of a truncation reviewable instead of accidental.
 *
 * <h2>Credentials travel in the clear, deliberately - but diagnostics do not</h2>
 *
 * {@code CDEMO-USER-ID} and {@code CDEMO-USER-TYPE} are <strong>carried</strong> exactly as the
 * COMMAREA carries them: unmasked, unhashed, unsigned and unencrypted. That is the observable
 * behaviour of the legacy system, and this is a like-for-like migration - adding protection to the
 * carried value would change behaviour and would pull in an authentication framework that is
 * explicitly out of scope. The property is documented rather than hidden so it stays visible.
 *
 * <p><strong>Carrying a value and rendering it are different acts, and only the first is
 * behaviour.</strong> {@link #toString()} is a diagnostic: no COBOL program produces it, no parity
 * case compares it, and nothing serialises through it. It therefore masks the account and card
 * identifiers and withholds the carried customer name, per the single policy in
 * {@link DiagnosticText} - which changes no byte that any caller, dataset or parity case can observe.
 * The user id and user type are still rendered in full, because a user id is the thing a log is read
 * to correlate on and it is not a credential; the password never reaches this record at all.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // First entry: a freshly initialised COMMAREA, spaces and zeros, already in ENTER state.
 * NavigationContext context = NavigationContext.empty();
 *
 * // Sign-on populates it, exactly as app/cbl/COSGN00C.cbl lines 224-228 do.
 * context = context.withFromTranid("CC00")
 *                  .withFromProgram("COSGN00C")
 *                  .withUserId("ADMIN001")
 *                  .withUserTypeAdmin()      // SET CDEMO-USRTYP-ADMIN TO TRUE
 *                  .withPgmEnter();          // MOVE ZEROS TO CDEMO-PGM-CONTEXT
 *
 * // Role routing, reproducing COSGN00C:230 with its ELSE.
 * String next = context.isAdmin() ? "COADM01C" : "COMEN01C";
 *
 * // A value wider than its field is rejected, so shorten it deliberately through the codec.
 * FixedWidthCodec codec = new FixedWidthCodec(Charset.forName("US-ASCII"));
 * context = context.withLastMap(codec.movePicX("COACTUPX", NavigationContext.LAST_MAP_LENGTH));
 *
 * byte[] image = context.toFixedWidth(codec);   // exactly 160 bytes
 * </pre>
 *
 * @param fromTranid  {@code CDEMO-FROM-TRANID PIC X(04)}: the transaction the caller ran
 * @param fromProgram {@code CDEMO-FROM-PROGRAM PIC X(08)}: the program the caller ran
 * @param toTranid    {@code CDEMO-TO-TRANID PIC X(04)}: the transaction to run next
 * @param toProgram   {@code CDEMO-TO-PROGRAM PIC X(08)}: the program to transfer to next, the
 *                    target of the four COMMAREA-driven {@code XCTL} sites
 * @param userId      {@code CDEMO-USER-ID PIC X(08)}: the signed-on user, carried in the clear
 * @param userType    {@code CDEMO-USER-TYPE PIC X(01)}: {@value #USER_TYPE_ADMIN} for an
 *                    administrator, {@value #USER_TYPE_USER} for a regular user, and possibly
 *                    neither - a space before sign-on
 * @param pgmContext  {@code CDEMO-PGM-CONTEXT PIC 9(01)}: {@value #PGM_CONTEXT_ENTER} on first
 *                    entry, {@value #PGM_CONTEXT_REENTER} on re-entry, and possibly neither
 * @param custId      {@code CDEMO-CUST-ID PIC 9(09)}: the carried customer id
 * @param custFname   {@code CDEMO-CUST-FNAME PIC X(25)}: the carried customer first name
 * @param custMname   {@code CDEMO-CUST-MNAME PIC X(25)}: the carried customer middle name
 * @param custLname   {@code CDEMO-CUST-LNAME PIC X(25)}: the carried customer last name
 * @param acctId      {@code CDEMO-ACCT-ID PIC 9(11)}: the carried account id
 * @param acctStatus  {@code CDEMO-ACCT-STATUS PIC X(01)}: the carried account status
 * @param cardNum     {@code CDEMO-CARD-NUM PIC 9(16)}: the carried card number, sixteen digits
 * @param lastMap     {@code CDEMO-LAST-MAP PIC X(7)}: the last map displayed - seven characters,
 *                    not eight
 * @param lastMapset  {@code CDEMO-LAST-MAPSET PIC X(7)}: the last mapset displayed - seven
 *                    characters, not eight
 */
public record NavigationContext(String fromTranid,
                                String fromProgram,
                                String toTranid,
                                String toProgram,
                                String userId,
                                String userType,
                                int pgmContext,
                                int custId,
                                String custFname,
                                String custMname,
                                String custLname,
                                long acctId,
                                String acctStatus,
                                @JsonSerialize(using = CardNumberSerializer.class)
                                @JsonDeserialize(using = CardNumberDeserializer.class)
                                long cardNum,
                                String lastMap,
                                String lastMapset) {

    // =================================================================================================
    // Field names, carried VERBATIM as the copybook spells them, hyphens and all. These are the names
    // FixedWidthCodec keys its images by and the names the parity differ compares field by field, so a
    // "tidied up" name would make a real difference invisible.
    // =================================================================================================

    /** Copybook name of {@link #fromTranid()}: {@code CDEMO-FROM-TRANID}, line 21. */
    public static final String FROM_TRANID_FIELD = "CDEMO-FROM-TRANID";

    /** Copybook name of {@link #fromProgram()}: {@code CDEMO-FROM-PROGRAM}, line 22. */
    public static final String FROM_PROGRAM_FIELD = "CDEMO-FROM-PROGRAM";

    /** Copybook name of {@link #toTranid()}: {@code CDEMO-TO-TRANID}, line 23. */
    public static final String TO_TRANID_FIELD = "CDEMO-TO-TRANID";

    /** Copybook name of {@link #toProgram()}: {@code CDEMO-TO-PROGRAM}, line 24. */
    public static final String TO_PROGRAM_FIELD = "CDEMO-TO-PROGRAM";

    /** Copybook name of {@link #userId()}: {@code CDEMO-USER-ID}, line 25. */
    public static final String USER_ID_FIELD = "CDEMO-USER-ID";

    /** Copybook name of {@link #userType()}: {@code CDEMO-USER-TYPE}, line 26. */
    public static final String USER_TYPE_FIELD = "CDEMO-USER-TYPE";

    /** Copybook name of {@link #pgmContext()}: {@code CDEMO-PGM-CONTEXT}, line 29. */
    public static final String PGM_CONTEXT_FIELD = "CDEMO-PGM-CONTEXT";

    /** Copybook name of {@link #custId()}: {@code CDEMO-CUST-ID}, line 33. */
    public static final String CUST_ID_FIELD = "CDEMO-CUST-ID";

    /** Copybook name of {@link #custFname()}: {@code CDEMO-CUST-FNAME}, line 34. */
    public static final String CUST_FNAME_FIELD = "CDEMO-CUST-FNAME";

    /** Copybook name of {@link #custMname()}: {@code CDEMO-CUST-MNAME}, line 35. */
    public static final String CUST_MNAME_FIELD = "CDEMO-CUST-MNAME";

    /** Copybook name of {@link #custLname()}: {@code CDEMO-CUST-LNAME}, line 36. */
    public static final String CUST_LNAME_FIELD = "CDEMO-CUST-LNAME";

    /** Copybook name of {@link #acctId()}: {@code CDEMO-ACCT-ID}, line 38. */
    public static final String ACCT_ID_FIELD = "CDEMO-ACCT-ID";

    /** Copybook name of {@link #acctStatus()}: {@code CDEMO-ACCT-STATUS}, line 39. */
    public static final String ACCT_STATUS_FIELD = "CDEMO-ACCT-STATUS";

    /** Copybook name of {@link #cardNum()}: {@code CDEMO-CARD-NUM}, line 41. */
    public static final String CARD_NUM_FIELD = "CDEMO-CARD-NUM";

    /** Copybook name of {@link #lastMap()}: {@code CDEMO-LAST-MAP}, line 43. */
    public static final String LAST_MAP_FIELD = "CDEMO-LAST-MAP";

    /** Copybook name of {@link #lastMapset()}: {@code CDEMO-LAST-MAPSET}, line 44. */
    public static final String LAST_MAPSET_FIELD = "CDEMO-LAST-MAPSET";

    // =================================================================================================
    // Declared widths, one named constant per elementary item. Every guard, every layout span and every
    // caller that needs to shorten a value states its width through one of these rather than repeating
    // a number.
    // =================================================================================================

    /** Declared width of {@code CDEMO-FROM-TRANID PIC X(04)}. */
    public static final int FROM_TRANID_LENGTH = 4;

    /** Declared width of {@code CDEMO-FROM-PROGRAM PIC X(08)} - a program name is eight characters. */
    public static final int FROM_PROGRAM_LENGTH = 8;

    /** Declared width of {@code CDEMO-TO-TRANID PIC X(04)}. */
    public static final int TO_TRANID_LENGTH = 4;

    /** Declared width of {@code CDEMO-TO-PROGRAM PIC X(08)} - a program name is eight characters. */
    public static final int TO_PROGRAM_LENGTH = 8;

    /** Declared width of {@code CDEMO-USER-ID PIC X(08)}. */
    public static final int USER_ID_LENGTH = 8;

    /** Declared width of {@code CDEMO-USER-TYPE PIC X(01)}. */
    public static final int USER_TYPE_LENGTH = 1;

    /** Declared digit count of {@code CDEMO-PGM-CONTEXT PIC 9(01)}. */
    public static final int PGM_CONTEXT_LENGTH = 1;

    /** Declared digit count of {@code CDEMO-CUST-ID PIC 9(09)}. */
    public static final int CUST_ID_LENGTH = 9;

    /** Declared width of {@code CDEMO-CUST-FNAME PIC X(25)}. */
    public static final int CUST_FNAME_LENGTH = 25;

    /** Declared width of {@code CDEMO-CUST-MNAME PIC X(25)}. */
    public static final int CUST_MNAME_LENGTH = 25;

    /** Declared width of {@code CDEMO-CUST-LNAME PIC X(25)}. */
    public static final int CUST_LNAME_LENGTH = 25;

    /** Declared digit count of {@code CDEMO-ACCT-ID PIC 9(11)}. */
    public static final int ACCT_ID_LENGTH = 11;

    /** Declared width of {@code CDEMO-ACCT-STATUS PIC X(01)}. */
    public static final int ACCT_STATUS_LENGTH = 1;

    /** Declared digit count of {@code CDEMO-CARD-NUM PIC 9(16)}. */
    public static final int CARD_NUM_LENGTH = 16;

    /**
     * Declared width of {@code CDEMO-LAST-MAP PIC X(7)} - <strong>seven</strong>, not eight. A BMS map
     * name is seven characters because the symbolic-map group item appends a one-character direction
     * suffix. Widening this to eight makes the record 162 bytes and {@link #LAYOUT} fails to build.
     */
    public static final int LAST_MAP_LENGTH = 7;

    /**
     * Declared width of {@code CDEMO-LAST-MAPSET PIC X(7)} - <strong>seven</strong>, not eight, for the
     * same reason as {@link #LAST_MAP_LENGTH}.
     */
    public static final int LAST_MAPSET_LENGTH = 7;

    // =================================================================================================
    // Group geometry. Each group's offset follows from the previous group's offset and width, and each
    // group's declared width is the sum of its own elementary items - stated as a literal here and
    // proven by the layout self-check, since the first field of every group is anchored to the group's
    // offset while the rest follow from their predecessors.
    // =================================================================================================

    /** Offset of {@code CDEMO-GENERAL-INFO}: the record starts here. */
    public static final int GENERAL_INFO_OFFSET = 0;

    /**
     * Width of {@code CDEMO-GENERAL-INFO}: {@code 4 + 8 + 4 + 8 + 8 + 1 + 1}.
     */
    public static final int GENERAL_INFO_LENGTH = 34;

    /** Offset of {@code CDEMO-CUSTOMER-INFO}, immediately after {@code CDEMO-GENERAL-INFO}. */
    public static final int CUSTOMER_INFO_OFFSET = GENERAL_INFO_OFFSET + GENERAL_INFO_LENGTH;

    /** Width of {@code CDEMO-CUSTOMER-INFO}: {@code 9 + 25 + 25 + 25}. */
    public static final int CUSTOMER_INFO_LENGTH = 84;

    /** Offset of {@code CDEMO-ACCOUNT-INFO}, immediately after {@code CDEMO-CUSTOMER-INFO}. */
    public static final int ACCOUNT_INFO_OFFSET = CUSTOMER_INFO_OFFSET + CUSTOMER_INFO_LENGTH;

    /** Width of {@code CDEMO-ACCOUNT-INFO}: {@code 11 + 1}. */
    public static final int ACCOUNT_INFO_LENGTH = 12;

    /** Offset of {@code CDEMO-CARD-INFO}, immediately after {@code CDEMO-ACCOUNT-INFO}. */
    public static final int CARD_INFO_OFFSET = ACCOUNT_INFO_OFFSET + ACCOUNT_INFO_LENGTH;

    /** Width of {@code CDEMO-CARD-INFO}: the sixteen digits of {@code CDEMO-CARD-NUM}. */
    public static final int CARD_INFO_LENGTH = 16;

    /** Offset of {@code CDEMO-MORE-INFO}, immediately after {@code CDEMO-CARD-INFO}. */
    public static final int MORE_INFO_OFFSET = CARD_INFO_OFFSET + CARD_INFO_LENGTH;

    /**
     * Width of {@code CDEMO-MORE-INFO}: {@code 7 + 7}. Fourteen, not sixteen - see
     * {@link #LAST_MAP_LENGTH}.
     */
    public static final int MORE_INFO_LENGTH = 14;

    /**
     * The declared width of {@code 01 CARDDEMO-COMMAREA} in bytes:
     * {@code 34 + 84 + 12 + 16 + 14}. Stated as a literal rather than derived from the group widths
     * precisely so that it has teeth - {@link #LAYOUT} hands this number to
     * {@link FixedWidthRecord.RecordLayout}, which refuses to build a layout whose spans sum to
     * anything else.
     */
    public static final int COMMAREA_LENGTH = 160;

    // =================================================================================================
    // Absolute 0-based offsets. Within a group each offset is the previous offset plus the previous
    // width, so the arithmetic is stated once and cannot drift; the first field of each group is
    // anchored to that group's offset so that a mistake inside one group is caught at the next
    // boundary rather than absorbed.
    // =================================================================================================

    /** Offset of {@code CDEMO-FROM-TRANID}. */
    public static final int FROM_TRANID_OFFSET = GENERAL_INFO_OFFSET;

    /** Offset of {@code CDEMO-FROM-PROGRAM}. */
    public static final int FROM_PROGRAM_OFFSET = FROM_TRANID_OFFSET + FROM_TRANID_LENGTH;

    /** Offset of {@code CDEMO-TO-TRANID}. */
    public static final int TO_TRANID_OFFSET = FROM_PROGRAM_OFFSET + FROM_PROGRAM_LENGTH;

    /** Offset of {@code CDEMO-TO-PROGRAM}. */
    public static final int TO_PROGRAM_OFFSET = TO_TRANID_OFFSET + TO_TRANID_LENGTH;

    /** Offset of {@code CDEMO-USER-ID}. */
    public static final int USER_ID_OFFSET = TO_PROGRAM_OFFSET + TO_PROGRAM_LENGTH;

    /** Offset of {@code CDEMO-USER-TYPE}. */
    public static final int USER_TYPE_OFFSET = USER_ID_OFFSET + USER_ID_LENGTH;

    /** Offset of {@code CDEMO-PGM-CONTEXT}, the last item of {@code CDEMO-GENERAL-INFO}. */
    public static final int PGM_CONTEXT_OFFSET = USER_TYPE_OFFSET + USER_TYPE_LENGTH;

    /** Offset of {@code CDEMO-CUST-ID}, anchored to the start of {@code CDEMO-CUSTOMER-INFO}. */
    public static final int CUST_ID_OFFSET = CUSTOMER_INFO_OFFSET;

    /** Offset of {@code CDEMO-CUST-FNAME}. */
    public static final int CUST_FNAME_OFFSET = CUST_ID_OFFSET + CUST_ID_LENGTH;

    /** Offset of {@code CDEMO-CUST-MNAME}. */
    public static final int CUST_MNAME_OFFSET = CUST_FNAME_OFFSET + CUST_FNAME_LENGTH;

    /** Offset of {@code CDEMO-CUST-LNAME}, the last item of {@code CDEMO-CUSTOMER-INFO}. */
    public static final int CUST_LNAME_OFFSET = CUST_MNAME_OFFSET + CUST_MNAME_LENGTH;

    /** Offset of {@code CDEMO-ACCT-ID}, anchored to the start of {@code CDEMO-ACCOUNT-INFO}. */
    public static final int ACCT_ID_OFFSET = ACCOUNT_INFO_OFFSET;

    /** Offset of {@code CDEMO-ACCT-STATUS}, the last item of {@code CDEMO-ACCOUNT-INFO}. */
    public static final int ACCT_STATUS_OFFSET = ACCT_ID_OFFSET + ACCT_ID_LENGTH;

    /** Offset of {@code CDEMO-CARD-NUM}, the only item of {@code CDEMO-CARD-INFO}. */
    public static final int CARD_NUM_OFFSET = CARD_INFO_OFFSET;

    /** Offset of {@code CDEMO-LAST-MAP}, anchored to the start of {@code CDEMO-MORE-INFO}. */
    public static final int LAST_MAP_OFFSET = MORE_INFO_OFFSET;

    /** Offset of {@code CDEMO-LAST-MAPSET}, the last item of the record. */
    public static final int LAST_MAPSET_OFFSET = LAST_MAP_OFFSET + LAST_MAP_LENGTH;

    // =================================================================================================
    // The four 88-level values, as named constants rather than literals sprinkled through seventeen
    // controllers.
    // =================================================================================================

    /**
     * The {@code CDEMO-USER-TYPE} value that satisfies {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'},
     * declared at {@code app/cpy/COCOM01Y.cpy} line 27. Tested by {@link #isAdmin()}.
     */
    public static final String USER_TYPE_ADMIN = "A";

    /**
     * The {@code CDEMO-USER-TYPE} value that satisfies {@code 88 CDEMO-USRTYP-USER VALUE 'U'},
     * declared at {@code app/cpy/COCOM01Y.cpy} line 28. Tested by {@link #isUser()}.
     */
    public static final String USER_TYPE_USER = "U";

    /**
     * The {@code CDEMO-PGM-CONTEXT} value that satisfies {@code 88 CDEMO-PGM-ENTER VALUE 0},
     * declared at {@code app/cpy/COCOM01Y.cpy} line 30. Tested by {@link #isEnter()}.
     */
    public static final int PGM_CONTEXT_ENTER = 0;

    /**
     * The {@code CDEMO-PGM-CONTEXT} value that satisfies {@code 88 CDEMO-PGM-REENTER VALUE 1},
     * declared at {@code app/cpy/COCOM01Y.cpy} line 31. Tested by {@link #isReenter()}.
     */
    public static final int PGM_CONTEXT_REENTER = 1;

    /**
     * The COBOL figurative constant {@code SPACES}, one character wide, from which
     * {@link #empty()} builds each character field's initial run.
     */
    private static final String SPACE = " ";

    /**
     * What {@link #toString()} prints in place of an identifying value, and what the two guards below
     * print in place of a rejected one.
     *
     * <p>The same marker the sign-on payload already uses for its password field, so one convention
     * covers every redacted diagnostic in this module. It is deliberately a fixed literal and carries
     * no length, no prefix and no last-four digits: any of those would be a partial disclosure, and a
     * diagnostic has no need of them.
     */
    public static final String REDACTED = "[REDACTED]";

    /**
     * The single character {@code '0'}, from which {@link #cardNumberImage(long)} builds the
     * zero-fill that {@code PIC 9(16)} implies on the left of a shorter value.
     */
    private static final String ZERO = "0";

    /**
     * The ordered, self-checking descriptor list of {@code 01 CARDDEMO-COMMAREA}: sixteen storage
     * spans in copybook declaration order, no {@code FILLER} - the copybook declares none - and no
     * {@code REDEFINES} overlay.
     *
     * <p>{@link FixedWidthRecord.RecordLayout} validates this at class-initialisation time and will
     * refuse to construct it unless the spans are contiguous from offset 0 with no gap and no overlap
     * and sum to exactly {@value #COMMAREA_LENGTH} bytes. That single check is what turns a width
     * typo into an immediate, precisely located failure instead of a silently misaligned record: widen
     * the two {@code X(7)} map fields to eight and the sum becomes 162 and this constant cannot be
     * built.
     *
     * <p>The layout is deeply immutable - a record whose span list is defensively copied through
     * {@code List.copyOf} and whose elements are themselves immutable records - so exposing it as a
     * shared constant introduces no mutable static state. It is public because the parity harness and
     * the field differ need the geometry in order to compare a fingerprint field by field.
     */
    public static final FixedWidthRecord.RecordLayout LAYOUT = FixedWidthRecord.RecordLayout.of(
            COMMAREA_LENGTH,
            FixedWidthRecord.FieldSpan.alphanumeric(
                    FROM_TRANID_FIELD, FROM_TRANID_OFFSET, FROM_TRANID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    FROM_PROGRAM_FIELD, FROM_PROGRAM_OFFSET, FROM_PROGRAM_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    TO_TRANID_FIELD, TO_TRANID_OFFSET, TO_TRANID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    TO_PROGRAM_FIELD, TO_PROGRAM_OFFSET, TO_PROGRAM_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    USER_ID_FIELD, USER_ID_OFFSET, USER_ID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    USER_TYPE_FIELD, USER_TYPE_OFFSET, USER_TYPE_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    PGM_CONTEXT_FIELD, PGM_CONTEXT_OFFSET, PGM_CONTEXT_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    CUST_ID_FIELD, CUST_ID_OFFSET, CUST_ID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    CUST_FNAME_FIELD, CUST_FNAME_OFFSET, CUST_FNAME_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    CUST_MNAME_FIELD, CUST_MNAME_OFFSET, CUST_MNAME_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    CUST_LNAME_FIELD, CUST_LNAME_OFFSET, CUST_LNAME_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    ACCT_ID_FIELD, ACCT_ID_OFFSET, ACCT_ID_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    ACCT_STATUS_FIELD, ACCT_STATUS_OFFSET, ACCT_STATUS_LENGTH),
            FixedWidthRecord.FieldSpan.unsignedNumeric(
                    CARD_NUM_FIELD, CARD_NUM_OFFSET, CARD_NUM_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    LAST_MAP_FIELD, LAST_MAP_OFFSET, LAST_MAP_LENGTH),
            FixedWidthRecord.FieldSpan.alphanumeric(
                    LAST_MAPSET_FIELD, LAST_MAPSET_OFFSET, LAST_MAPSET_LENGTH));

    /**
     * Validates every component at construction time, so an instance either exists and fits the
     * {@value #COMMAREA_LENGTH}-byte communication area or does not exist at all.
     *
     * <p>Two invariants are enforced, and both are enforced <em>here</em> rather than at serialisation
     * time, because a value that cannot be stored is a defect at the point it was assembled and the
     * stack trace is only useful there:
     *
     * <ul>
     *   <li><strong>No character component may exceed its declared width.</strong> This type models
     *       {@value #COMMAREA_LENGTH} bytes of storage, and storage cannot hold more than it is wide,
     *       so an over-long value is rejected rather than quietly shortened. Rejecting keeps
     *       {@link #toFixedWidth(FixedWidthCodec)} a lossless projection: what goes in comes out. A
     *       caller that genuinely wants COBOL's alphanumeric {@code MOVE} behaviour asks for it
     *       explicitly through {@link FixedWidthCodec#movePicX(String, int)}, which truncates on the
     *       right, so the direction of the loss is chosen deliberately and is visible at the call
     *       site. A <em>shorter</em> value is accepted and is padded on the right with spaces by the
     *       codec when the image is produced, exactly as a COBOL {@code MOVE} into a wider
     *       {@code PIC X} receiver pads.</li>
     *   <li><strong>No numeric component may be negative or need more digits than it declares.</strong>
     *       {@code PIC 9(n)} is an <em>unsigned</em> picture with no sign position, so a negative value
     *       has no representation in it at all. An over-wide value is rejected rather than silently
     *       losing its high-order digits, which is what a numeric {@code MOVE} would do: a
     *       seventeen-digit card number stored into {@code PIC 9(16)} would come back missing its
     *       leading digit and would still look entirely plausible. A caller that wants that behaviour
     *       asks for it through {@link FixedWidthCodec#movePic9(long, int)}.</li>
     * </ul>
     *
     * <p>Both checks are delegated to a single shared guard each, so the rule is stated once and every
     * one of the sixteen components is held to the identical standard.
     *
     * @throws NullPointerException     if any character component is {@code null}. There is no null in
     *                                  a COBOL record: an unset {@code PIC X} field holds spaces, and
     *                                  {@link #empty()} produces exactly that
     * @throws IllegalArgumentException if a character component is longer than its declared width, or a
     *                                  numeric component is negative or needs more digits than its
     *                                  declared width
     */
    public NavigationContext {
        fromTranid = requireWidth(fromTranid, FROM_TRANID_LENGTH, FROM_TRANID_FIELD);
        fromProgram = requireWidth(fromProgram, FROM_PROGRAM_LENGTH, FROM_PROGRAM_FIELD);
        toTranid = requireWidth(toTranid, TO_TRANID_LENGTH, TO_TRANID_FIELD);
        toProgram = requireWidth(toProgram, TO_PROGRAM_LENGTH, TO_PROGRAM_FIELD);
        userId = requireWidth(userId, USER_ID_LENGTH, USER_ID_FIELD);
        userType = requireWidth(userType, USER_TYPE_LENGTH, USER_TYPE_FIELD);
        pgmContext = requireUnsignedDigits(pgmContext, PGM_CONTEXT_LENGTH, PGM_CONTEXT_FIELD);
        custId = requireUnsignedDigits(custId, CUST_ID_LENGTH, CUST_ID_FIELD);
        custFname = requireWidth(custFname, CUST_FNAME_LENGTH, CUST_FNAME_FIELD);
        custMname = requireWidth(custMname, CUST_MNAME_LENGTH, CUST_MNAME_FIELD);
        custLname = requireWidth(custLname, CUST_LNAME_LENGTH, CUST_LNAME_FIELD);
        acctId = requireUnsignedDigits(acctId, ACCT_ID_LENGTH, ACCT_ID_FIELD);
        acctStatus = requireWidth(acctStatus, ACCT_STATUS_LENGTH, ACCT_STATUS_FIELD);
        cardNum = requireUnsignedDigits(cardNum, CARD_NUM_LENGTH, CARD_NUM_FIELD);
        lastMap = requireWidth(lastMap, LAST_MAP_LENGTH, LAST_MAP_FIELD);
        lastMapset = requireWidth(lastMapset, LAST_MAPSET_LENGTH, LAST_MAPSET_FIELD);
    }

    // =================================================================================================
    // The initial communication area.
    // =================================================================================================

    /**
     * A freshly initialised communication area: every {@code PIC X} field a run of spaces of its
     * declared width, every {@code PIC 9} field zero.
     *
     * <p>This is the COBOL {@code INITIALIZE CARDDEMO-COMMAREA} shape, and the state a CICS
     * transaction sees on a cold start before any program has populated the area. Note two
     * consequences that follow from the copybook rather than from any choice made here:
     *
     * <ul>
     *   <li>{@link #pgmContext()} is {@value #PGM_CONTEXT_ENTER}, so the instance is already in
     *       {@code CDEMO-PGM-ENTER} state - {@link #isEnter()} is true. That is faithful to the
     *       sixteen {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} sites, among them
     *       {@code app/cbl/COSGN00C.cbl:228}, which is how the online programs assert first entry.</li>
     *   <li>{@link #userType()} is a single space, so {@link #isAdmin()} and {@link #isUser()} are
     *       <strong>both</strong> false. No role is implied before sign-on.</li>
     * </ul>
     *
     * <p>Each character field is filled by repeating the {@code SPACES} figurative constant to the
     * field's declared width. That is COBOL's unconditional space fill, not the alphanumeric
     * {@code MOVE} rule - the {@code MOVE} rule, which pads a shorter sending value and truncates a
     * longer one, lives solely in {@link FixedWidthCodec} and is never reimplemented here.
     *
     * <p>The image this instance produces is exactly {@value #COMMAREA_LENGTH} bytes and is
     * byte-identical to what {@link FixedWidthCodec#newRecord(FixedWidthRecord.RecordLayout)} writes
     * for {@link #LAYOUT}: spaces in the character spans, zeros in the numeric ones.
     *
     * @return the initial communication area, never {@code null}
     */
    public static NavigationContext empty() {
        return new NavigationContext(spaces(FROM_TRANID_LENGTH),
                spaces(FROM_PROGRAM_LENGTH),
                spaces(TO_TRANID_LENGTH),
                spaces(TO_PROGRAM_LENGTH),
                spaces(USER_ID_LENGTH),
                spaces(USER_TYPE_LENGTH),
                PGM_CONTEXT_ENTER,
                0,
                spaces(CUST_FNAME_LENGTH),
                spaces(CUST_MNAME_LENGTH),
                spaces(CUST_LNAME_LENGTH),
                0L,
                spaces(ACCT_STATUS_LENGTH),
                0L,
                spaces(LAST_MAP_LENGTH),
                spaces(LAST_MAPSET_LENGTH));
    }

    // =================================================================================================
    // The four 88-level conditions. Each tests the stored field directly; none is the negation of
    // another, because neither pair is exhaustive.
    // =================================================================================================

    /**
     * Whether {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} holds - that is, whether
     * {@link #userType()} is exactly {@value #USER_TYPE_ADMIN}.
     *
     * <p>This is the condition {@code app/cbl/COSGN00C.cbl:230} tests before transferring to
     * {@code COADM01C} at line 232, and the condition that admits an option from the ten-row menu
     * table of {@code app/cpy/COMEN02Y.cpy} whose {@code X(01)} authorisation column restricts it to
     * administrators.
     *
     * <p>The comparison is exact and case-sensitive, as a COBOL alphanumeric comparison is: a
     * lower-case {@code 'a'} does not satisfy the condition. It is emphatically <strong>not</strong>
     * the negation of {@link #isUser()} - see the class documentation.
     *
     * <p>Marked {@link JsonIgnore}: this condition is derived from the stored field, not stored
     * beside it, so it is not part of the JSON payload. The wire format carries the sixteen
     * copybook fields and nothing else.
     *
     * @return {@code true} only when the user type is exactly {@value #USER_TYPE_ADMIN}
     */
    @JsonIgnore
    public boolean isAdmin() {
        return USER_TYPE_ADMIN.equals(userType);
    }

    /**
     * Whether {@code 88 CDEMO-USRTYP-USER VALUE 'U'} holds - that is, whether {@link #userType()} is
     * exactly {@value #USER_TYPE_USER}.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl} asserts this condition with
     * {@code SET CDEMO-USRTYP-USER TO TRUE} at lines 320, 388, 466, 522 and 550, which
     * {@link #withUserTypeUser()} reproduces.
     *
     * <p>Deliberately <strong>not</strong> written as {@code !isAdmin()}. A blank user type, which is
     * what {@link #empty()} carries and what a COMMAREA holds before sign-on, satisfies neither
     * condition; defining this predicate as a negation would report an unidentified user as a regular
     * user and would change routing behaviour.
     *
     * <p>Marked {@link JsonIgnore}: this condition is derived from the stored field, not stored
     * beside it, so it is not part of the JSON payload. The wire format carries the sixteen
     * copybook fields and nothing else.
     *
     * @return {@code true} only when the user type is exactly {@value #USER_TYPE_USER}
     */
    @JsonIgnore
    public boolean isUser() {
        return USER_TYPE_USER.equals(userType);
    }

    /**
     * Whether {@code 88 CDEMO-PGM-ENTER VALUE 0} holds - that is, whether {@link #pgmContext()} is
     * {@value #PGM_CONTEXT_ENTER}.
     *
     * <p>First entry into a transaction: the program paints its screen and validates nothing.
     * Sixteen sites assert it with {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT}, and
     * {@code app/cbl/COCRDLIC.cbl} asserts it as a condition name with
     * {@code SET CDEMO-PGM-ENTER TO TRUE} at lines 321, 339, 389, 400, 467, 523 and 551.
     *
     * <p>Marked {@link JsonIgnore}: this condition is derived from the stored field, not stored
     * beside it, so it is not part of the JSON payload. The wire format carries the sixteen
     * copybook fields and nothing else.
     *
     * @return {@code true} only when the program context is exactly {@value #PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return pgmContext == PGM_CONTEXT_ENTER;
    }

    /**
     * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds - that is, whether {@link #pgmContext()} is
     * {@value #PGM_CONTEXT_REENTER}.
     *
     * <p>Re-entry into a transaction: the program validates what the user typed. This is the conjunct
     * {@code app/cpy/CSSETATY.cpy} tests -
     * {@code IF (FLG-x-NOT-OK OR FLG-x-BLANK) AND CDEMO-PGM-REENTER} - before moving {@code DFHRED}
     * onto the offending field's colour item and {@code '*'} onto a blank field, so it is the value
     * {@link FieldAttributeSetter} is given as its {@code reenter} argument.
     *
     * <p>Deliberately <strong>not</strong> written as {@code !isEnter()}: {@code CDEMO-PGM-CONTEXT} is
     * {@code PIC 9(01)} and can hold any digit, so a context of, say, {@code 9} satisfies neither
     * condition.
     *
     * <p>Marked {@link JsonIgnore}: this condition is derived from the stored field, not stored
     * beside it, so it is not part of the JSON payload. The wire format carries the sixteen
     * copybook fields and nothing else.
     *
     * @return {@code true} only when the program context is exactly {@value #PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return pgmContext == PGM_CONTEXT_REENTER;
    }

    // =================================================================================================
    // Copy methods, one per field. These replace COBOL's MOVE INTO the communication area. There is no
    // setter and no mutable field: a context already handed to a collaborator can never change
    // underneath it, which is what keeps a request's state isolated from every other request's.
    //
    // Every one of them validates through the canonical constructor, so a value too wide for its field
    // is rejected here just as it is on construction.
    // =================================================================================================

    /**
     * Returns a copy carrying a new {@code CDEMO-FROM-TRANID}, the
     * {@code MOVE WS-TRANID TO CDEMO-FROM-TRANID} of {@code app/cbl/COSGN00C.cbl:224}.
     *
     * @param newFromTranid the calling transaction identifier, at most
     *                      {@value #FROM_TRANID_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newFromTranid} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #FROM_TRANID_LENGTH}
     */
    public NavigationContext withFromTranid(String newFromTranid) {
        return new NavigationContext(newFromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-FROM-PROGRAM}, the
     * {@code MOVE WS-PGMNAME TO CDEMO-FROM-PROGRAM} of {@code app/cbl/COSGN00C.cbl:225} and the
     * {@code MOVE LIT-THISPGM TO CDEMO-FROM-PROGRAM} of {@code app/cbl/COCRDLIC.cbl:319}.
     *
     * @param newFromProgram the calling program name, at most {@value #FROM_PROGRAM_LENGTH}
     *                       characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newFromProgram} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #FROM_PROGRAM_LENGTH}
     */
    public NavigationContext withFromProgram(String newFromProgram) {
        return new NavigationContext(fromTranid, newFromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-TO-TRANID}: the transaction the client should run
     * next.
     *
     * @param newToTranid the target transaction identifier, at most {@value #TO_TRANID_LENGTH}
     *                    characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newToTranid} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #TO_TRANID_LENGTH}
     */
    public NavigationContext withToTranid(String newToTranid) {
        return new NavigationContext(fromTranid, fromProgram, newToTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-TO-PROGRAM}: the program the client should call next.
     *
     * <p>This is the field the four COMMAREA-driven transfers read -
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code COACTUPC:957},
     * {@code COACTVWC:350}, {@code COCRDSLC:332} and {@code COCRDUPC:474} - and the field
     * {@code app/cbl/COCRDLIC.cbl:392} populates with {@code MOVE LIT-MENUPGM TO CDEMO-TO-PROGRAM}.
     * In the migrated system it is carried out on the response and the client performs the transfer,
     * so no server-side forward takes place.
     *
     * @param newToProgram the target program name, at most {@value #TO_PROGRAM_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newToProgram} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #TO_PROGRAM_LENGTH}
     */
    public NavigationContext withToProgram(String newToProgram) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, newToProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-USER-ID}, the
     * {@code MOVE WS-USER-ID TO CDEMO-USER-ID} of {@code app/cbl/COSGN00C.cbl:226}. The value is
     * stored, carried and reported in the clear, exactly as the COMMAREA carries it.
     *
     * @param newUserId the signed-on user identifier, at most {@value #USER_ID_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newUserId} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #USER_ID_LENGTH}
     */
    public NavigationContext withUserId(String newUserId) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, newUserId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-USER-TYPE}, the
     * {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} of {@code app/cbl/COSGN00C.cbl:227}, which moves
     * the {@code SEC-USR-TYPE} byte of the security record straight into the communication area.
     *
     * <p>Any single character is accepted, because {@code CDEMO-USER-TYPE} is {@code PIC X(01)} and
     * the copybook constrains nothing: {@value #USER_TYPE_ADMIN} and {@value #USER_TYPE_USER} are
     * condition <em>values</em>, not a domain. Use {@link #withUserTypeAdmin()} or
     * {@link #withUserTypeUser()} to assert one of the two named conditions.
     *
     * @param newUserType the user type character, at most {@value #USER_TYPE_LENGTH} character
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newUserType} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #USER_TYPE_LENGTH}
     */
    public NavigationContext withUserType(String newUserType) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                newUserType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy whose {@code CDEMO-USER-TYPE} is {@value #USER_TYPE_ADMIN}: the equivalent of
     * COBOL's {@code SET CDEMO-USRTYP-ADMIN TO TRUE}, which stores the condition's declared value
     * into the field it is declared over.
     *
     * @return a new instance for which {@link #isAdmin()} is true and {@link #isUser()} is false
     */
    public NavigationContext withUserTypeAdmin() {
        return withUserType(USER_TYPE_ADMIN);
    }

    /**
     * Returns a copy whose {@code CDEMO-USER-TYPE} is {@value #USER_TYPE_USER}: the equivalent of
     * {@code SET CDEMO-USRTYP-USER TO TRUE}, which {@code app/cbl/COCRDLIC.cbl} performs at lines
     * 320, 388, 466, 522 and 550.
     *
     * @return a new instance for which {@link #isUser()} is true and {@link #isAdmin()} is false
     */
    public NavigationContext withUserTypeUser() {
        return withUserType(USER_TYPE_USER);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-PGM-CONTEXT}.
     *
     * <p>Any single digit is accepted, because the field is {@code PIC 9(01)} and only two of its ten
     * values are named by a condition. Prefer {@link #withPgmEnter()} and {@link #withPgmReenter()},
     * which say which condition is being asserted.
     *
     * @param newPgmContext the program context digit, from {@code 0} to {@code 9}
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than
     *                                  {@value #PGM_CONTEXT_LENGTH} digit
     */
    public NavigationContext withPgmContext(int newPgmContext) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, newPgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy in first-entry state, {@code CDEMO-PGM-CONTEXT} set to
     * {@value #PGM_CONTEXT_ENTER}: the equivalent of {@code SET CDEMO-PGM-ENTER TO TRUE}
     * ({@code app/cbl/COCRDLIC.cbl:321} and six sibling sites) and of the
     * {@code MOVE ZEROS TO CDEMO-PGM-CONTEXT} that sixteen programs perform, among them
     * {@code app/cbl/COSGN00C.cbl:228}.
     *
     * @return a new instance for which {@link #isEnter()} is true and {@link #isReenter()} is false
     */
    public NavigationContext withPgmEnter() {
        return withPgmContext(PGM_CONTEXT_ENTER);
    }

    /**
     * Returns a copy in re-entry state, {@code CDEMO-PGM-CONTEXT} set to
     * {@value #PGM_CONTEXT_REENTER}: the equivalent of {@code SET CDEMO-PGM-REENTER TO TRUE}. This is
     * the state in which {@code app/cpy/CSSETATY.cpy} allows the {@code DFHRED} error highlight, so
     * {@link FieldAttributeSetter} highlights a failed field only for a context in this state.
     *
     * @return a new instance for which {@link #isReenter()} is true and {@link #isEnter()} is false
     */
    public NavigationContext withPgmReenter() {
        return withPgmContext(PGM_CONTEXT_REENTER);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-ID}.
     *
     * @param newCustId the customer identifier, at most {@value #CUST_ID_LENGTH} digits and never
     *                  negative
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than
     *                                  {@value #CUST_ID_LENGTH} digits
     */
    public NavigationContext withCustId(int newCustId) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, newCustId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-FNAME}.
     *
     * @param newCustFname the customer first name, at most {@value #CUST_FNAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newCustFname} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #CUST_FNAME_LENGTH}
     */
    public NavigationContext withCustFname(String newCustFname) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, newCustFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-MNAME}.
     *
     * @param newCustMname the customer middle name, at most {@value #CUST_MNAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newCustMname} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #CUST_MNAME_LENGTH}
     */
    public NavigationContext withCustMname(String newCustMname) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, newCustMname, custLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CUST-LNAME}.
     *
     * @param newCustLname the customer last name, at most {@value #CUST_LNAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newCustLname} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #CUST_LNAME_LENGTH}
     */
    public NavigationContext withCustLname(String newCustLname) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, newCustLname, acctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-ACCT-ID}.
     *
     * <p>{@code app/cbl/COCRDLIC.cbl:1027} performs {@code MOVE CC-ACCT-ID TO CDEMO-ACCT-ID}, moving
     * an {@code X(11)} alphanumeric item into this {@code 9(11)} numeric one; and lines 1011 and 1024
     * clear it with {@code MOVE ZEROES}. A caller holding the value as characters converts it
     * deliberately through {@link FixedWidthCodec#decodePic9(String)} rather than parsing it here, so
     * that a non-numeric image fails at one known seam.
     *
     * @param newAcctId the account identifier, at most {@value #ACCT_ID_LENGTH} digits and never
     *                  negative
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than
     *                                  {@value #ACCT_ID_LENGTH} digits
     */
    public NavigationContext withAcctId(long newAcctId) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, newAcctId, acctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-ACCT-STATUS}.
     *
     * @param newAcctStatus the account status character, at most {@value #ACCT_STATUS_LENGTH}
     *                      character
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newAcctStatus} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #ACCT_STATUS_LENGTH}
     */
    public NavigationContext withAcctStatus(String newAcctStatus) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, newAcctStatus,
                cardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-CARD-NUM}, the
     * {@code MOVE CC-CARD-NUM-N TO CDEMO-CARD-NUM} of {@code app/cbl/COCRDLIC.cbl:1064}.
     *
     * <p>Sixteen digits fit a {@code long} with room to spare, which is why the component is a
     * {@code long} and never a floating-point type: a card number must round-trip to the exact digit.
     *
     * @param newCardNum the card number, at most {@value #CARD_NUM_LENGTH} digits and never negative
     * @return a new instance; this one is unchanged
     * @throws IllegalArgumentException if the value is negative or needs more than
     *                                  {@value #CARD_NUM_LENGTH} digits
     */
    public NavigationContext withCardNum(long newCardNum) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                newCardNum, lastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-LAST-MAP}, the
     * {@code MOVE LIT-THISMAP TO CDEMO-LAST-MAP} of {@code app/cbl/COCRDLIC.cbl:322} and its four
     * sibling sites.
     *
     * @param newLastMap the map name, at most {@value #LAST_MAP_LENGTH} characters - seven, not
     *                   eight; an eight-character value is rejected rather than silently shortened
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newLastMap} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #LAST_MAP_LENGTH}
     */
    public NavigationContext withLastMap(String newLastMap) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, newLastMap, lastMapset);
    }

    /**
     * Returns a copy carrying a new {@code CDEMO-LAST-MAPSET}, the
     * {@code MOVE LIT-THISMAPSET TO CDEMO-LAST-MAPSET} of {@code app/cbl/COCRDLIC.cbl:323} and its
     * four sibling sites.
     *
     * @param newLastMapset the mapset name, at most {@value #LAST_MAPSET_LENGTH} characters - seven,
     *                      not eight
     * @return a new instance; this one is unchanged
     * @throws NullPointerException     if {@code newLastMapset} is {@code null}
     * @throws IllegalArgumentException if it is longer than {@value #LAST_MAPSET_LENGTH}
     */
    public NavigationContext withLastMapset(String newLastMapset) {
        return new NavigationContext(fromTranid, fromProgram, toTranid, toProgram, userId,
                userType, pgmContext, custId, custFname, custMname, custLname, acctId, acctStatus,
                cardNum, lastMap, newLastMapset);
    }

    // =================================================================================================
    // The fixed-width image. JSON is the REST wire format and needs nothing from this class; the
    // 160-byte image is what the parity harness fingerprints and the field differ compares.
    // =================================================================================================

    /**
     * Renders this context as the {@value #COMMAREA_LENGTH}-byte image of
     * {@code 01 CARDDEMO-COMMAREA}, in the code page of the supplied codec.
     *
     * <p>Every field is handed to {@link FixedWidthCodec} as its raw value and the codec applies the
     * move rule for the field's picture: a character field is padded on the right with spaces to its
     * declared width, and a numeric {@code DISPLAY} field is zero-filled on the left to its declared
     * digit count. <strong>This method implements neither rule itself</strong> - it assembles the field
     * images and delegates, so the pad and truncate semantics of the whole system live in exactly one
     * reviewable place. Because the canonical constructor has already rejected any value too wide for
     * its field, no truncation can occur here and the rendering is lossless.
     *
     * <p>The result is guaranteed to be exactly {@value #COMMAREA_LENGTH} bytes: {@link #LAYOUT}
     * cannot exist unless its spans sum to that, and the codec allocates the record from the layout.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly - {@code US-ASCII} for the
     *              text fixtures, {@code IBM037} for EBCDIC data. The charset is never defaulted
     * @return exactly {@value #COMMAREA_LENGTH} bytes, in the codec's code page
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toFixedWidth(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render CARDDEMO-COMMAREA: the "
                + "code page of a fixed-width image must be stated explicitly and is never derived "
                + "from the platform");
        Map<String, String> images = new LinkedHashMap<>();
        images.put(FROM_TRANID_FIELD, fromTranid);
        images.put(FROM_PROGRAM_FIELD, fromProgram);
        images.put(TO_TRANID_FIELD, toTranid);
        images.put(TO_PROGRAM_FIELD, toProgram);
        images.put(USER_ID_FIELD, userId);
        images.put(USER_TYPE_FIELD, userType);
        images.put(PGM_CONTEXT_FIELD, Integer.toString(pgmContext));
        images.put(CUST_ID_FIELD, Integer.toString(custId));
        images.put(CUST_FNAME_FIELD, custFname);
        images.put(CUST_MNAME_FIELD, custMname);
        images.put(CUST_LNAME_FIELD, custLname);
        images.put(ACCT_ID_FIELD, Long.toString(acctId));
        images.put(ACCT_STATUS_FIELD, acctStatus);
        images.put(CARD_NUM_FIELD, Long.toString(cardNum));
        images.put(LAST_MAP_FIELD, lastMap);
        images.put(LAST_MAPSET_FIELD, lastMapset);
        return codec.serialise(LAYOUT, images);
    }

    /**
     * Reads a {@value #COMMAREA_LENGTH}-byte image of {@code 01 CARDDEMO-COMMAREA} back into a
     * context.
     *
     * <p>Character fields are taken <strong>raw and untrimmed</strong>, so each one comes back at
     * exactly its declared width with its trailing spaces intact. That is what makes the round trip
     * byte-identical: rendering the result of this method reproduces the image it was read from,
     * because nothing was silently dropped on the way in. Numeric fields are decoded through the
     * codec, which accepts digits only - a non-digit in a numeric span is a genuine data or alignment
     * defect and fails here, where the field that is wrong can be named, instead of becoming a
     * plausible-looking zero.
     *
     * @param codec the fixed-width codec, carrying the code page explicitly
     * @param image exactly {@value #COMMAREA_LENGTH} bytes
     * @return the context the image denotes, never {@code null}
     * @throws NullPointerException     if {@code codec} or {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #COMMAREA_LENGTH} bytes
     *                                  long, or a numeric span does not hold digits
     */
    public static NavigationContext fromFixedWidth(FixedWidthCodec codec, byte[] image) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read CARDDEMO-COMMAREA: the "
                + "code page of a fixed-width image must be stated explicitly and is never derived "
                + "from the platform");
        Objects.requireNonNull(image, "A " + COMMAREA_LENGTH + "-byte image is required to read "
                + "CARDDEMO-COMMAREA; call empty() for a freshly initialised communication area");
        Map<String, String> images = codec.deserialise(LAYOUT, image);
        return new NavigationContext(images.get(FROM_TRANID_FIELD),
                images.get(FROM_PROGRAM_FIELD),
                images.get(TO_TRANID_FIELD),
                images.get(TO_PROGRAM_FIELD),
                images.get(USER_ID_FIELD),
                images.get(USER_TYPE_FIELD),
                codec.decodePic9AsInt(images.get(PGM_CONTEXT_FIELD)),
                codec.decodePic9AsInt(images.get(CUST_ID_FIELD)),
                images.get(CUST_FNAME_FIELD),
                images.get(CUST_MNAME_FIELD),
                images.get(CUST_LNAME_FIELD),
                codec.decodePic9(images.get(ACCT_ID_FIELD)),
                images.get(ACCT_STATUS_FIELD),
                codec.decodePic9(images.get(CARD_NUM_FIELD)),
                images.get(LAST_MAP_FIELD),
                images.get(LAST_MAPSET_FIELD));
    }

    // =================================================================================================
    // Private guards. Each rule is stated once and shared by every field it governs, so that all
    // sixteen components are held to an identical standard and every guard is individually reachable
    // from a test.
    // =================================================================================================

    /**
     * The COBOL figurative constant {@code SPACES} sized to a field: a run of {@code width} spaces.
     *
     * <p>This is an unconditional fill, the {@code MOVE SPACES} / {@code INITIALIZE} shape, and is
     * deliberately not the alphanumeric {@code MOVE} rule - that rule, which pads a shorter sending
     * value and truncates a longer one, belongs to {@link FixedWidthCodec} alone.
     */
    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    /**
     * Rejects a {@code null} character field and one wider than the copybook declares, and returns the
     * value unchanged when it fits.
     *
     * <p>A shorter value is accepted: the codec pads it on the right when the image is rendered,
     * exactly as a COBOL {@code MOVE} into a wider {@code PIC X} receiver pads. Only an over-long
     * value is refused, because the communication area has nowhere to put the surplus and quietly
     * discarding it would make the loss invisible at the call site.
     */
    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " requires a value; there is no null in a "
                + "COBOL record, so move SPACES explicitly or start from NavigationContext.empty()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC X("
                    + declaredWidth + ") but was given " + value.length() + " character(s), value "
                    + REDACTED + ". CARDDEMO-COMMAREA is " + COMMAREA_LENGTH + " bytes and cannot "
                    + "hold the surplus. To shorten the value deliberately, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + declaredWidth + "), which truncates on the "
                    + "right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * Rejects a negative value and one needing more digits than the copybook declares, and returns the
     * value unchanged when it fits.
     *
     * <p>{@code PIC 9(n)} is unsigned and has no sign position at all, so a negative value has no
     * representation in it. An over-wide value is refused rather than losing its high-order digits the
     * way a numeric {@code MOVE} would, because a truncated identifier still looks entirely plausible
     * and is correspondingly hard to trace.
     */
    private static long requireUnsignedDigits(long value, int declaredDigits, String cobolName) {
        if (value < 0) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC 9("
                    + declaredDigits + "), an unsigned picture with no sign position, so it cannot "
                    + "hold a negative value (" + REDACTED + "). A signed value belongs in a PIC S9 "
                    + "field");
        }
        String digits = Long.toString(value);
        if (digits.length() > declaredDigits) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC 9("
                    + declaredDigits + ") but the value given (" + REDACTED + ") needs "
                    + digits.length() + " digit(s). Storing it would silently drop the high-order "
                    + "digit(s); to do that deliberately, pass the value through "
                    + "FixedWidthCodec.movePic9(value, " + declaredDigits + ")");
        }
        return value;
    }

    /**
     * The {@code int} overload of the unsigned-digit guard, for the {@code PIC 9(n)} fields with
     * {@code n} of nine or fewer that this record models as an {@code int}. It delegates so that the
     * rule itself exists in exactly one place.
     */
    private static int requireUnsignedDigits(int value, int declaredDigits, String cobolName) {
        return (int) requireUnsignedDigits((long) value, declaredDigits, cobolName);
    }

    /**
     * A diagnostic rendering that discloses the navigation state and withholds the cardholder data, per
     * {@link SensitiveDiagnostics}.
     *
     * <p>This override matters more than any other in the module. {@code COCOM01Y} is copied by all
     * <strong>seventeen</strong> online programs, so this record is a component of every request and
     * response DTO in the system - and a record's generated {@code toString} renders every component.
     * Inheriting it published the carried customer's full name, the customer and account identifiers and
     * the sixteen-digit card number from any log line, exception message or debugger view that rendered
     * any of those DTOs, including the ones whose own fields are entirely innocuous. Overriding it here
     * closes that path for all seventeen at once.
     *
     * <p>What stays legible is the navigation state itself - the from and to transaction and program
     * names, the user id and type, the program context and the last map and mapset. That is precisely
     * what a pseudo-conversational flow defect is diagnosed from: rule R6 moves this whole area into the
     * request and response payloads, so when navigation goes wrong these are the fields that say why.
     * The user id is an eight-character operator id, not a personal identifier, and
     * {@code app/cbl/COSGN00C.cbl} treats it as the routing key it is.
     *
     * <p>{@code equals} and {@code hashCode} remain exactly as the record generates them, over every
     * component. They are value semantics and disclose nothing, and the parity harness depends on them
     * comparing the whole area.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "NavigationContext[fromTranid=" + fromTranid
                + ", fromProgram=" + fromProgram
                + ", toTranid=" + toTranid
                + ", toProgram=" + toProgram
                + ", userId=" + userId
                + ", userType=" + userType
                + ", pgmContext=" + pgmContext
                + ", custId=" + SensitiveDiagnostics.maskIdentifier(custId, CUST_ID_LENGTH)
                + ", custFname=" + SensitiveDiagnostics.describeText(custFname)
                + ", custMname=" + SensitiveDiagnostics.describeText(custMname)
                + ", custLname=" + SensitiveDiagnostics.describeText(custLname)
                + ", acctId=" + SensitiveDiagnostics.maskIdentifier(acctId, ACCT_ID_LENGTH)
                + ", acctStatus=" + acctStatus
                + ", cardNum=" + SensitiveDiagnostics.maskPan(cardNum, CARD_NUM_LENGTH)
                + ", lastMap=" + lastMap
                + ", lastMapset=" + lastMapset
                + ']';
    }

    // =================================================================================================
    // The JSON representation of CDEMO-CARD-NUM. Sixteen digits do not fit in the exact-integer range
    // of every JSON client, so the wire form is a string - and the conversion lives here, at the wire
    // boundary, rather than anywhere a value is computed.
    // =================================================================================================

    /**
     * The {@value #CARD_NUM_LENGTH}-digit decimal image of {@link #cardNum()}, zero-filled on the left
     * exactly as {@code PIC 9(16)} stores it.
     *
     * <p>This is a rendering of an already-valid value, not a {@code MOVE}: the canonical constructor
     * has already refused anything negative or wider than {@value #CARD_NUM_LENGTH} digits, so no
     * truncation is reachable from here and none is implemented. The padding and truncating
     * {@code MOVE} rules stay where they belong, in {@link FixedWidthCodec}.
     *
     * @param cardNumber the value of {@code CDEMO-CARD-NUM}; must be zero or positive and at most
     *                   {@value #CARD_NUM_LENGTH} digits
     * @return exactly {@value #CARD_NUM_LENGTH} decimal digits
     */
    public static String cardNumberImage(long cardNumber) {
        String digits = Long.toString(requireUnsignedDigits(cardNumber, CARD_NUM_LENGTH,
                CARD_NUM_FIELD));
        return ZERO.repeat(CARD_NUM_LENGTH - digits.length()) + digits;
    }

    /**
     * Reads a {@code CDEMO-CARD-NUM} wire value: a string of at most {@value #CARD_NUM_LENGTH} decimal
     * digits, with or without its leading zeros.
     *
     * @param image the digits as they arrived on the wire; must not be {@code null}
     * @return the value the digits denote
     * @throws IllegalArgumentException if {@code image} is empty, longer than
     *                                  {@value #CARD_NUM_LENGTH} characters, or holds anything other
     *                                  than {@code '0'} through {@code '9'} - a sign, a space, a
     *                                  separator or a decimal point included. The message names the
     *                                  field and the length and never the value
     */
    public static long cardNumberOfImage(String image) {
        Objects.requireNonNull(image, "Field " + CARD_NUM_FIELD + " requires a value; send the "
                + CARD_NUM_LENGTH + "-digit string, or 0 for the initialised state");
        if (image.isEmpty() || image.length() > CARD_NUM_LENGTH) {
            throw new IllegalArgumentException("Field " + CARD_NUM_FIELD + " is declared PIC 9("
                    + CARD_NUM_LENGTH + ") and is carried as a decimal string of 1 to "
                    + CARD_NUM_LENGTH + " digits, but " + image.length() + " character(s) arrived "
                    + "(value " + REDACTED + ")");
        }
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("Field " + CARD_NUM_FIELD + " is declared PIC 9("
                        + CARD_NUM_LENGTH + ") and accepts only the digits 0 to 9, but the character "
                        + "at position " + (index + 1) + " is neither (value " + REDACTED + ")");
            }
        }
        return Long.parseLong(image);
    }

    /**
     * Writes {@code CDEMO-CARD-NUM} to JSON as a {@value NavigationContext#CARD_NUM_LENGTH}-digit
     * decimal <strong>string</strong>.
     *
     * <h2>Why a string and not a number</h2>
     * {@code CDEMO-CARD-NUM} is {@code PIC 9(16)} - {@code app/cpy/COCOM01Y.cpy} line 41 - so a
     * populated value has sixteen significant digits and can exceed 9,007,199,254,740,991, the largest
     * integer a IEEE-754 double can represent exactly. JSON does not bound the precision of a number,
     * but a great many clients parse every number into a double, so a sixteen-digit card number sent as
     * a JSON number can arrive with a different final digit and no error anywhere. A card number whose
     * digits change in transit is not a rounding inconvenience: it identifies a different card. The
     * string form has no such failure mode, and the leading zeros the {@code PICTURE} clause implies
     * survive it as well.
     *
     * <p>The numeric form is not lost, and this is the important half of the design: the component
     * stays a {@code long}, every internal read and every arithmetic use is unchanged, and
     * {@link NavigationContext#toFixedWidth(FixedWidthCodec)} still writes the sixteen zoned bytes
     * through the codec. The conversion happens at the wire boundary and nowhere else.
     */
    public static final class CardNumberSerializer extends JsonSerializer<Long> {

        /** Creates the serializer. Stateless, so Jackson may share one instance. */
        public CardNumberSerializer() {
            // Intentionally empty: the conversion is a pure function of its argument.
        }

        /**
         * Writes the value as its {@value NavigationContext#CARD_NUM_LENGTH}-digit string.
         *
         * @param value      the card number; never {@code null} for a primitive component
         * @param generator  the JSON generator to write to
         * @param serializers the provider, unused
         * @throws IOException if the generator cannot be written to
         */
        @Override
        public void serialize(Long value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeString(cardNumberImage(value));
        }
    }

    /**
     * Reads {@code CDEMO-CARD-NUM} from JSON, accepting <strong>only</strong> a string of decimal
     * digits.
     *
     * <p>A JSON number is refused rather than accepted leniently, and that refusal is the point of the
     * design: by the time a sixteen-digit card number has been parsed into a client's double it may
     * already be a different card number, and accepting it would preserve exactly the defect the string
     * form exists to remove. The refusal is a {@code 400} through
     * {@code config.WebConfig.CobolErrorHandler}, which reports neither the value nor the parser's own
     * text.
     *
     * <p>An explicit JSON {@code null} reads as {@code 0}: {@code PIC 9(16)} has no absent state, and
     * zero is what {@code INITIALIZE CARDDEMO-COMMAREA} leaves in the field.
     */
    public static final class CardNumberDeserializer extends JsonDeserializer<Long> {

        /** Creates the deserializer. Stateless, so Jackson may share one instance. */
        public CardNumberDeserializer() {
            // Intentionally empty: the conversion is a pure function of its argument.
        }

        /**
         * Reads the digits and returns the value they denote.
         *
         * @param parser  the parser positioned on the value
         * @param context the deserialization context, unused
         * @return the card number
         * @throws IOException              if the parser cannot be read
         * @throws IllegalArgumentException if the token is not a string, or the string is not 1 to
         *                                  {@value NavigationContext#CARD_NUM_LENGTH} decimal digits
         */
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw new IllegalArgumentException("Field " + CARD_NUM_FIELD + " is declared PIC 9("
                        + CARD_NUM_LENGTH + ") and must be sent as a decimal string of up to "
                        + CARD_NUM_LENGTH + " digits, because sixteen digits exceed the exact-integer "
                        + "range of a JSON number in many clients");
            }
            return cardNumberOfImage(parser.getText());
        }

        /**
         * The value an explicit JSON {@code null} reads as: {@code 0}, the initialised state of a
         * {@code PIC 9} field.
         *
         * @param context the deserialization context, unused
         * @return {@code 0}
         */
        @Override
        public Long getNullValue(DeserializationContext context) {
            return 0L;
        }
    }
}
