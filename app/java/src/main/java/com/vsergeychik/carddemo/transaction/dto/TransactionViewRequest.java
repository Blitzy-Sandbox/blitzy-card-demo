package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound REST payload of CSD transaction {@code CT02}, program {@code COTRN02C} - a field-for-field
 * projection of the {@code xxxI} items of {@code 01 COTRN2AI} in {@code app/cpy-bms/COTRN02.CPY} and
 * their name-labelled {@code DFHMDF} definitions in {@code app/bms/COTRN02.bms}.
 *
 * <h2>Read this first: the class name contradicts the source (risk R-B)</h2>
 *
 * <strong>This type is named {@code TransactionView...} but the program it projects
 * {@code ADD}s a transaction.</strong> The divergence is real, it is known, and it is recorded here
 * rather than corrected:
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN02C.cbl} line 5 states
 *       {@code * Function    : Add a new Transaction to TRANSACT file}.</li>
 *   <li>{@code app/bms/COTRN02.bms} line 2 states {@code *    CardDemo - Transaction Add}, and its
 *       screen literal at line 79 is {@code 'Add Transaction'}.</li>
 *   <li>{@code README.md} lines 213-231 independently document {@code CT02} as "Transaction Add".</li>
 *   <li>The map carries {@code ACTIDIN X(11)}, {@code CARDNIN X(16)} and {@code CONFIRM X(1)} and has
 *       <strong>no</strong> {@code TRNIDIN} and no {@code TRNID}: the operator keys an account or a
 *       card, types the detail and confirms. Fourteen of the twenty-one fields are {@code UNPROT},
 *       which is a data-entry form. The sibling {@code COTRN01} map is the genuine view screen - it
 *       has {@code TRNIDIN}/{@code TRNID}, no {@code CONFIRM}, and a single {@code UNPROT} field.</li>
 * </ul>
 *
 * The refactoring plan calls this "the highest-risk naming ambiguity in the plan" in its conflict
 * analysis and carries it as risk <strong>R-B</strong> in its open risk register. The binding
 * resolution is its rule <strong>R1</strong>: <em>the name comes from the prompt, the field set and the
 * behaviour come from the paired copybook and program.</em> So the mandated name is honoured verbatim
 * and the twenty-one fields below are exactly the map's, unaltered.
 *
 * <p><strong>Consequences for anyone editing this file.</strong> Do not delete the input fields or
 * {@code CONFIRM} to make the type "look like" a read-only view, and do not add {@code TRNIDIN} or
 * {@code TRNID} to make it look like the view screen - those belong to {@code COTRN01}. Either edit
 * would break the field-for-field projection that the parity gate checks. Read the paired program,
 * never the class name, to learn what this screen does.
 *
 * <h2>Where the field list comes from</h2>
 *
 * There is no design system and no component library in this project, and no design attachments
 * exist. The BMS layer <em>is</em> the presentation contract and it carries the binding force a design
 * system would: every payload member below traces to a name-labelled {@code DFHMDF} definition, and
 * every width traces to a symbolic-map {@code xxxI} {@code PICTURE} clause. Both were verified
 * mechanically rather than transcribed by eye:
 *
 * <ul>
 *   <li>{@code app/bms/COTRN02.bms} declares <strong>61</strong> {@code DFHMDF} entries of which
 *       <strong>21</strong> are name-labelled. Only the labelled ones become members; the other forty
 *       are screen literals such as {@code 'Enter Acct #:'} and zero-length field terminators, which
 *       carry no data.</li>
 *   <li>{@code app/cpy-bms/COTRN02.CPY} declares <strong>21</strong> {@code xxxI} items and
 *       <strong>21</strong> {@code xxxO} items. The name sets agree exactly, and every BMS
 *       {@code LENGTH=} equals its {@code PIC X(n)} - checked field by field, with no exceptions.</li>
 * </ul>
 *
 * <h2>The symbolic map structure, and which parts are payload</h2>
 *
 * {@code 01 COTRN2AI} begins at line 17 of the copybook. Every item is level {@code 02}; only
 * {@code xxxA} is level {@code 03}, because it redefines {@code xxxF}:
 *
 * <pre>
 *  01  COTRN2AI.
 *      02  FILLER PIC X(12).            &lt;- 12-byte TIOAPFX prefix
 *      02  xxxL    COMP  PIC  S9(4).    &lt;- METADATA (length item)
 *      02  xxxF    PICTURE X.           &lt;- METADATA (flag byte)
 *      02  FILLER REDEFINES xxxF.
 *        03 xxxA   PICTURE X.           &lt;- METADATA (attribute view of the SAME byte)
 *      02  FILLER   PICTURE X(4).       &lt;- reserved span
 *      02  xxxI  PIC X(n).              &lt;- PAYLOAD           stride = 7 + n
 * </pre>
 *
 * So each field spends exactly {@value #METADATA_PREFIX_LENGTH} bytes on metadata
 * ({@value #LENGTH_ITEM_LENGTH} + {@value #FLAG_ITEM_LENGTH} + {@value #RESERVED_FILLER_LENGTH}) before
 * its payload bytes. {@code xxxL}, {@code xxxF} and {@code xxxA} are validation and highlight
 * <em>metadata</em>, never JSON payload members - see {@link FieldMetadata}.
 *
 * <h2>Geometry, which is gate-level arithmetic</h2>
 *
 * <table border="1">
 *   <caption>The twenty-one payload fields, in copybook declaration order</caption>
 *   <tr><th>#</th><th>Field</th><th>Width</th><th>Payload offset</th><th>Role</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME}</td><td>{@value #TRNNAME_LENGTH}</td><td>19</td>
 *       <td>header, ASKIP</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01}</td><td>{@value #TITLE01_LENGTH}</td><td>30</td>
 *       <td>header, ASKIP</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE}</td><td>{@value #CURDATE_LENGTH}</td><td>77</td>
 *       <td>header, ASKIP</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME}</td><td>{@value #PGMNAME_LENGTH}</td><td>92</td>
 *       <td>header, ASKIP</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02}</td><td>{@value #TITLE02_LENGTH}</td><td>107</td>
 *       <td>header, ASKIP</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME}</td><td>{@value #CURTIME_LENGTH}</td><td>154</td>
 *       <td>header, ASKIP</td></tr>
 *   <tr><td>7</td><td>{@code ACTIDIN}</td><td>{@value #ACTIDIN_LENGTH}</td><td>169</td>
 *       <td>UNPROT, account key, {@code FUNCTION NUMVAL} at {@code COTRN02C:204}</td></tr>
 *   <tr><td>8</td><td>{@code CARDNIN}</td><td>{@value #CARDNIN_LENGTH}</td><td>187</td>
 *       <td>UNPROT, card key, {@code FUNCTION NUMVAL} at {@code COTRN02C:218}</td></tr>
 *   <tr><td>9</td><td>{@code TTYPCD}</td><td>{@value #TTYPCD_LENGTH}</td><td>210</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>10</td><td>{@code TCATCD}</td><td>{@value #TCATCD_LENGTH}</td><td>219</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>11</td><td>{@code TRNSRC}</td><td>{@value #TRNSRC_LENGTH}</td><td>230</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>12</td><td>{@code TDESC}</td><td>{@value #TDESC_LENGTH}</td><td>247</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>13</td><td>{@code TRNAMT}</td><td>{@value #TRNAMT_LENGTH}</td><td>314</td>
 *       <td>UNPROT, the <em>edited</em> amount - see below</td></tr>
 *   <tr><td>14</td><td>{@code TORIGDT}</td><td>{@value #TORIGDT_LENGTH}</td><td>333</td>
 *       <td>UNPROT, validated through {@code CSUTLDTC} at {@code COTRN02C:393}</td></tr>
 *   <tr><td>15</td><td>{@code TPROCDT}</td><td>{@value #TPROCDT_LENGTH}</td><td>350</td>
 *       <td>UNPROT, validated through {@code CSUTLDTC} at {@code COTRN02C:413}</td></tr>
 *   <tr><td>16</td><td>{@code MID}</td><td>{@value #MID_LENGTH}</td><td>367</td>
 *       <td>UNPROT, merchant id</td></tr>
 *   <tr><td>17</td><td>{@code MNAME}</td><td>{@value #MNAME_LENGTH}</td><td>383</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>18</td><td>{@code MCITY}</td><td>{@value #MCITY_LENGTH}</td><td>420</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>19</td><td>{@code MZIP}</td><td>{@value #MZIP_LENGTH}</td><td>452</td>
 *       <td>UNPROT</td></tr>
 *   <tr><td>20</td><td>{@code CONFIRM}</td><td>{@value #CONFIRM_LENGTH}</td><td>469</td>
 *       <td>UNPROT, the add-confirmation flag</td></tr>
 *   <tr><td>21</td><td>{@code ERRMSG}</td><td>{@value #ERRMSG_LENGTH}</td><td>477</td>
 *       <td>error line, ASKIP BRT RED</td></tr>
 *   <tr><td></td><td><strong>Total width</strong></td>
 *       <td><strong>{@value #PAYLOAD_WIDTH_TOTAL}</strong></td><td></td><td></td></tr>
 * </table>
 *
 * The group image is therefore
 * {@value #TIOAPFX_PREFIX_LENGTH} + {@value #PAYLOAD_FIELD_COUNT} x {@value #METADATA_PREFIX_LENGTH}
 * + {@value #PAYLOAD_WIDTH_TOTAL} = <strong>{@value #AI_GROUP_LENGTH}</strong> bytes. That total is not
 * merely documented, it is <em>enforced</em>: {@link #AI_LAYOUT} declares every span at an absolute
 * offset and hands the literal {@value #AI_GROUP_LENGTH} to {@link RecordLayout}, whose constructor
 * self-check refuses a layout whose spans are not contiguous from zero or do not sum to exactly that.
 * A mistyped width fails at class initialisation, naming the offending span, instead of silently
 * shifting every field after it.
 *
 * <p>Note {@code ACTIDIN} is <strong>{@value #ACTIDIN_LENGTH}</strong> bytes, not 16. It is an account
 * id ({@code PIC 9(11)} in the account record), and only {@code CARDNIN} is sixteen. Conflating the two
 * is the easiest width error to make on this screen.
 *
 * <h2>Every member is a {@code String}, including the amount</h2>
 *
 * There is <strong>no</strong> {@code BigDecimal}, {@code int}, {@code long}, {@code double} or
 * {@code float} among the payload members: a symbolic-map {@code xxxI} item is
 * {@code PIC X(n)} - alphanumeric - and this type reproduces the screen, not the record.
 *
 * <p>{@code TRNAMT} is the case that matters. The screen field is {@code PIC X(12)} while the record
 * field {@code TRAN-AMT} of {@code app/cpy/CVTRA05Y.cpy} is {@code PIC S9(09)V99}, eleven bytes. The
 * screen carries an <em>edited</em> amount under the mask {@code +99999999.99} - one sign character,
 * eight integer digits, a literal decimal point and two fraction digits, which is exactly twelve
 * characters. {@code COTRN02C} declares that mask twice, at {@code :53}
 * ({@code 05 WS-TRAN-AMT PIC +99999999.99.}) and {@code :59}
 * ({@code 05 WS-TRAN-AMT-E PIC +99999999.99 VALUE ZEROS.}), and keeps the numeric value in a separate
 * carrier at {@code :58} ({@code 05 WS-TRAN-AMT-N PIC S9(9)V99 VALUE ZERO.}).
 *
 * <p>The decisive evidence that the field must stay alphanumeric is the positional validation at
 * {@code COTRN02C:340-343}, which indexes into the twelve characters directly - position
 * {@code (1:1)} must be {@code '-'} or {@code '+'}, {@code (2:8)} must be numeric, {@code (10:1)}
 * must be {@code '.'} and {@code (11:2)} must be numeric. Reference-modification like that is only
 * possible on a character field; typing the member numerically would delete that check.
 *
 * <p>The mask holds <strong>eight</strong> integer digits while the record holds
 * <strong>nine</strong>, so the record-to-screen move at {@code COTRN02C:481-485} genuinely
 * left-truncates the ninth digit. That is the legacy behaviour and it is preserved: the field is
 * <strong>not</strong> widened beyond {@value #TRNAMT_LENGTH} to "fix" it.
 *
 * <p>{@code ACTIDIN} and {@code CARDNIN} are likewise plain strings. {@code FUNCTION NUMVAL} at
 * {@code COTRN02C:204} and {@code :218} is the <em>controller's</em> parsing step, not a reason to type
 * these members numerically - and the program's own guard is
 * {@code IF ACTIDINI ... IS NOT NUMERIC}, a test that presupposes a character field. All
 * {@code NUMVAL} and {@code NUMVAL-C} conversion, and all arithmetic, belongs to the controller. This
 * type performs none, contains no floating-point type, and so cannot introduce a rounding or scale
 * divergence.
 *
 * <h2>{@code xxxI} and {@code xxxO} are the same bytes, so round-tripping must be lossless</h2>
 *
 * {@code 01 COTRN2AO REDEFINES COTRN2AI} at copybook line 145, and each view spends exactly
 * {@value #METADATA_PREFIX_LENGTH} prefix bytes per field - the input view as
 * {@code 2 + 1 + 4}, the output view as {@code 3 + 1 + 1 + 1 + 1} - so a field's {@code I} and
 * {@code O} items sit at the <em>identical</em> offset. They are storage aliases, not distinct fields.
 * {@code COTRN02C} writes through both.
 *
 * <p><strong>Never treat an {@code xxxI} item as read-only.</strong> The program stores into the input
 * alias repeatedly: {@code :205-206} moves a normalised account id back into {@code ACTIDINI},
 * {@code :209} moves the cross-referenced card number into {@code CARDNINI}, {@code :386} moves the
 * edited amount into {@code TRNAMTI} and {@code :485} does so again from the record. This is why this
 * request type and its paired response type are field-identical: the split is a directional
 * <em>projection convention</em> over one COBOL buffer, not two different buffers. Accordingly every
 * member here has a setter, and a serialise-then-deserialise cycle returns an equal value with
 * trailing spaces intact.
 *
 * <h2>Statelessness</h2>
 *
 * CICS is pseudo-conversational, so the conversation state travels in the payload and never becomes
 * server-side state. This type carries a {@link NavigationContext} - the
 * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} that {@code COTRN02C}
 * copies at {@code :71} - and a {@link Ct02Info}, the {@value Ct02Info#CT02_INFO_LENGTH}-byte
 * pagination cursor the program appends to it at {@code :72-80}. There is deliberately no
 * {@code HttpSession}, no {@code @SessionAttributes}, no {@code ThreadLocal}, no server-side cache and
 * no static "current request" holder anywhere in this file; a static holder would be a session by
 * another name and would break request isolation.
 *
 * <p>Program transfer is a <em>response</em> concern. {@code COTRN02C:509}
 * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} is resolved by the client from the paired response type, so
 * nothing here forwards, redirects or requires session affinity.
 *
 * <h2>Credentials and identifiers travel in the clear, deliberately</h2>
 *
 * {@code CARDNIN} ({@value #CARDNIN_LENGTH} characters), {@code ACTIDIN}
 * ({@value #ACTIDIN_LENGTH}) and {@code MID} ({@value #MID_LENGTH}) are carried at their full declared
 * width, unmasked, untruncated and unredacted, and {@link #toString()} reports them in full. No
 * accessor is hidden from serialisation. That is the observable behaviour of the legacy screen, which
 * accepts and echoes these values plainly, and this is a like-for-like migration: masking here would
 * change behaviour and would additionally corrupt the field-for-field comparison the parity gate
 * performs. The property is documented so that it stays visible rather than buried.
 *
 * <h2>Validation is never stricter than the COBOL</h2>
 *
 * The only Bean Validation constraint on any member is {@link Size} with the field's declared width as
 * its maximum, derived from the {@code xxxI} {@code PICTURE}. There is deliberately no
 * {@code @NotNull}, no {@code @NotBlank}, no {@code @Pattern} and no {@code @Digits}: {@code COTRN02C}
 * performs its own extensive editing - {@code IS NOT NUMERIC} tests, the positional amount check at
 * {@code :340-343}, the {@code CSUTLDTC} date checks at {@code :393} and {@code :413} with their
 * {@code '0000'}-severity and tolerated-{@code '2513'} rule, and the {@code CONFIRM} evaluation at
 * {@code :169} - and any additional Java rejection would reject an input the COBOL accepts, which is a
 * parity break. A pre-emptive numeric or date-format constraint here would be exactly that.
 *
 * <p>Setters neither trim nor truncate, so a value is preserved precisely as it arrived. Width
 * enforcement happens only where a fixed-width image is produced, and there it is performed by
 * {@link FixedWidthCodec} rather than by this class, so the direction of a truncation is reviewable at
 * the point it happens.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // First entry: an empty screen in ENTER context.
 * TransactionViewRequest request = new TransactionViewRequest();
 *
 * // The operator keys an account, the detail and a confirmation, and the client re-enters.
 * request.setActidin("00000000011");
 * request.setTrnamt("+00000100.00");        // the +99999999.99 edit mask, 12 characters
 * request.setConfirm("Y");
 * request.setNavigationContext(NavigationContext.empty().withPgmReenter());
 *
 * // Position the cursor on a field in error, exactly as MOVE -1 TO TRNAMTL does.
 * request.metadata(TransactionViewRequest.ScreenField.TRNAMT).requestCursor();
 *
 * byte[] image = request.toFixedWidth(StandardCharsets.US_ASCII);   // exactly 555 bytes
 * </pre>
 *
 * <h2>Thread safety</h2>
 *
 * Instances are mutable and are <strong>not</strong> thread safe, which is correct for a per-request
 * payload: one instance belongs to one request. There is no static mutable state - the only static
 * members are immutable constants, immutable {@link FieldSpan} and {@link RecordLayout} descriptors,
 * and one immutable {@link FixedWidthCodec}.
 *
 * @see NavigationContext
 * @see FixedWidthCodec
 *
 * <h2>Members this request tolerates without declaring</h2>
 *
 * <p>The {@code @JsonIgnoreProperties} below names the members the paired response carries that this
 * request does not declare. They are tolerated so a client can send the body it was just handed straight
 * back: rule R6 and gate G37 put the whole conversation in the payload, which makes the next request the
 * previous response. {@code ignoreUnknown} stays at its default of {@code false}, so every <em>other</em>
 * unrecognised name is still refused with the offending field named in the error envelope. Each tolerated
 * member is recomputed by the server on every path, so the value that arrives here is discarded and
 * cannot steer a branch. The names live in {@link com.vsergeychik.carddemo.common.ResponseOnlyMembers},
 * which explains each one.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class TransactionViewRequest {

    // =================================================================================================
    // Identity, taken from app/csd/CARDDEMO.CSD and the mapset.
    // =================================================================================================

    /**
     * The CICS transaction identifier, {@code CT02}. {@code app/csd/CARDDEMO.CSD:439} declares
     * {@code DEFINE TRANSACTION(CT02)} and line 440 binds it to {@code PROGRAM(COTRN02C)}.
     */
    public static final String TRANSACTION_ID = "CT02";

    /** The COBOL program projected here, {@code COTRN02C}: {@code app/csd/CARDDEMO.CSD:271}. */
    public static final String PROGRAM_NAME = "COTRN02C";

    /** The BMS mapset, {@code COTRN02}: {@code app/csd/CARDDEMO.CSD:153}. */
    public static final String MAPSET_NAME = "COTRN02";

    /** The BMS map, {@code COTRN2A}: the {@code DFHMDI} label of {@code app/bms/COTRN02.bms:26}. */
    public static final String MAP_NAME = "COTRN2A";

    /** The symbolic-map input group projected here: {@code 01 COTRN2AI}, copybook line 17. */
    public static final String SYMBOLIC_MAP_INPUT_GROUP = "COTRN2AI";

    /**
     * The symbolic-map output group, {@code 01 COTRN2AO}, declared at copybook line 145 as
     * {@code REDEFINES COTRN2AI}. Named here because it is the same storage, which is why a round trip
     * through this type must be lossless.
     */
    public static final String SYMBOLIC_MAP_OUTPUT_GROUP = "COTRN2AO";

    // =================================================================================================
    // Group geometry. Every one of these numbers is checked, not merely asserted: AI_LAYOUT fails to
    // initialise if the spans it builds from them are not contiguous from zero and do not sum to
    // AI_GROUP_LENGTH.
    // =================================================================================================

    /**
     * The leading {@code 02 FILLER PIC X(12)} of {@code 01 COTRN2AI} at copybook line 18. It exists
     * because the mapset declares {@code TIOAPFX=YES} ({@code app/bms/COTRN02.bms:24}), which reserves
     * a twelve-byte terminal-input/output-area prefix ahead of the first field.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The width of an {@code xxxL} length item, {@code COMP PIC S9(4)}: a signed binary halfword, two
     * bytes.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /** The width of an {@code xxxF} flag item, {@code PICTURE X}, which {@code xxxA} redefines. */
    public static final int FLAG_ITEM_LENGTH = 1;

    /** The width of the {@code 02 FILLER PICTURE X(4)} reserved span that precedes each payload item. */
    public static final int RESERVED_FILLER_LENGTH = 4;

    /**
     * The metadata bytes each field spends before its payload:
     * {@value #LENGTH_ITEM_LENGTH} + {@value #FLAG_ITEM_LENGTH} + {@value #RESERVED_FILLER_LENGTH} =
     * {@value #METADATA_PREFIX_LENGTH}. A field's stride through the group image is therefore
     * {@value #METADATA_PREFIX_LENGTH} plus its declared width.
     */
    public static final int METADATA_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /**
     * The number of payload fields: {@value #PAYLOAD_FIELD_COUNT}. Equal to the name-labelled
     * {@code DFHMDF} count of {@code app/bms/COTRN02.bms}, to the {@code xxxI} count of
     * {@code app/cpy-bms/COTRN02.CPY} and to the {@code xxxO} count of the same copybook.
     */
    public static final int PAYLOAD_FIELD_COUNT = 21;

    /**
     * The sum of the twenty-one declared payload widths: {@value #PAYLOAD_WIDTH_TOTAL}.
     * {@code 4 + 40 + 8 + 8 + 40 + 8 + 11 + 16 + 2 + 4 + 10 + 60 + 12 + 10 + 10 + 9 + 30 + 25 + 10 + 1
     * + 78}.
     */
    public static final int PAYLOAD_WIDTH_TOTAL = 396;

    /**
     * The full width of the {@code 01 COTRN2AI} group image:
     * {@value #TIOAPFX_PREFIX_LENGTH} + {@value #PAYLOAD_FIELD_COUNT} x
     * {@value #METADATA_PREFIX_LENGTH} + {@value #PAYLOAD_WIDTH_TOTAL} =
     * <strong>{@value #AI_GROUP_LENGTH}</strong> bytes.
     */
    public static final int AI_GROUP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + PAYLOAD_FIELD_COUNT * METADATA_PREFIX_LENGTH + PAYLOAD_WIDTH_TOTAL;

    /**
     * The value {@code COTRN02C} moves into an {@code xxxL} item to place the 3270 cursor on that
     * field: {@code MOVE -1 TO <field>L}. The program does this at <strong>35</strong> sites, more than
     * any other program of this package - for example {@code :280}
     * ({@code MOVE -1 TO TRNAMTL OF COTRN2AI}) and {@code :347}. It is why {@link FieldMetadata#length()}
     * is a <em>signed</em> type and is never clamped at zero.
     */
    public static final int CURSOR_REQUEST = -1;

    // =================================================================================================
    // Declared payload widths, one constant per field, verbatim from the xxxI PICTURE clauses. These are
    // the numbers a reviewer diffs against app/cpy-bms/COTRN02.CPY line by line.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}, copybook line 24; {@code TRNNAME DFHMDF LENGTH=4}, mapset line 34. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}, copybook line 30; {@code LENGTH=40}, mapset line 38. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}, copybook line 36; {@code LENGTH=8}, mapset line 47. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, copybook line 42; {@code LENGTH=8}, mapset line 57. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}, copybook line 48; {@code LENGTH=40}, mapset line 61. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEI PIC X(8)}, copybook line 54; {@code LENGTH=8}, mapset line 70. */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACTIDINI PIC X(11)}, copybook line 60; {@code LENGTH=11}, mapset line 85.
     * <strong>Eleven, not sixteen</strong> - this is an account id, and only {@code CARDNIN} is
     * sixteen.
     */
    public static final int ACTIDIN_LENGTH = 11;

    /** {@code CARDNINI PIC X(16)}, copybook line 66; {@code LENGTH=16}, mapset line 104. */
    public static final int CARDNIN_LENGTH = 16;

    /** {@code TTYPCDI PIC X(2)}, copybook line 72; {@code LENGTH=2}, mapset line 122. */
    public static final int TTYPCD_LENGTH = 2;

    /** {@code TCATCDI PIC X(4)}, copybook line 78; {@code LENGTH=4}, mapset line 135. */
    public static final int TCATCD_LENGTH = 4;

    /** {@code TRNSRCI PIC X(10)}, copybook line 84; {@code LENGTH=10}, mapset line 148. */
    public static final int TRNSRC_LENGTH = 10;

    /** {@code TDESCI PIC X(60)}, copybook line 90; {@code LENGTH=60}, mapset line 161. */
    public static final int TDESC_LENGTH = 60;

    /**
     * {@code TRNAMTI PIC X(12)}, copybook line 96; {@code LENGTH=12}, mapset line 174. Twelve because
     * the field carries the edit mask {@code +99999999.99}: sign, eight integer digits, the point and
     * two fraction digits. The mapset prints the operator hint {@code '(-99999999.99)'} beneath it at
     * line 212.
     */
    public static final int TRNAMT_LENGTH = 12;

    /**
     * {@code TORIGDTI PIC X(10)}, copybook line 102; {@code LENGTH=10}, mapset line 187. The mapset
     * prints the hint {@code '(YYYY-MM-DD)'} beneath it at line 217, and {@code COTRN02C} passes the
     * value to {@code CSUTLDTC} at {@code :393} with the format {@code 'YYYY-MM-DD'} declared at
     * {@code :60}.
     */
    public static final int TORIGDT_LENGTH = 10;

    /**
     * {@code TPROCDTI PIC X(10)}, copybook line 108; {@code LENGTH=10}, mapset line 200. Validated
     * through {@code CSUTLDTC} at {@code COTRN02C:413}.
     */
    public static final int TPROCDT_LENGTH = 10;

    /** {@code MIDI PIC X(9)}, copybook line 114; {@code LENGTH=9}, mapset line 228. */
    public static final int MID_LENGTH = 9;

    /** {@code MNAMEI PIC X(30)}, copybook line 120; {@code LENGTH=30}, mapset line 241. */
    public static final int MNAME_LENGTH = 30;

    /** {@code MCITYI PIC X(25)}, copybook line 126; {@code LENGTH=25}, mapset line 254. */
    public static final int MCITY_LENGTH = 25;

    /** {@code MZIPI PIC X(10)}, copybook line 132; {@code LENGTH=10}, mapset line 267. */
    public static final int MZIP_LENGTH = 10;

    /**
     * {@code CONFIRMI PIC X(1)}, copybook line 138; {@code LENGTH=1}, mapset line 281. The
     * add-confirmation flag. {@code COTRN02C:169} evaluates it: {@code 'Y'} or {@code 'y'} adds the
     * transaction; {@code 'N'}, {@code 'n'}, spaces or low-values re-prompt; anything else is rejected
     * as invalid. The mapset prints {@code '(Y/N)'} beside it at line 292.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}, copybook line 144; {@code LENGTH=78}, mapset line 293, declared
     * {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Characters in the {@code EIBAID} token carried by {@link #getAid()}: five.
     *
     * <p><strong>Not a screen field.</strong> It is absent from {@link ScreenField}, from
     * {@link #PAYLOAD_FIELD_COUNT} and from the symbolic-map image, because {@code EIBAID} is not part
     * of {@code 01 COTRN2AI} at all - CICS reports it in the exec interface block, beside the map
     * rather than inside it. It is one of the mandated exceptions to the one-member-per-{@code DFHMDF}
     * rule, along with the communication area and its extension.
     *
     * <p>It has to be here because {@code app/cbl/COTRN02C.cbl:133-152} decides what the transaction
     * does by evaluating it and nothing else - {@code EVALUATE EIBAID} with four named arms and a
     * default:
     *
     * <ul>
     *   <li>{@code WHEN DFHENTER} performs {@code PROCESS-ENTER-KEY}, which validates the fourteen
     *       input fields and adds the transaction;</li>
     *   <li>{@code WHEN DFHPF3} returns to {@code CDEMO-FROM-PROGRAM}, or to {@code 'COMEN01C'} when
     *       that is blank;</li>
     *   <li>{@code WHEN DFHPF4} performs {@code CLEAR-CURRENT-SCREEN};</li>
     *   <li>{@code WHEN DFHPF5} performs {@code COPY-LAST-TRAN-DATA};</li>
     *   <li>{@code WHEN OTHER} raises {@code CCDA-MSG-INVALID-KEY}.</li>
     * </ul>
     *
     * <p>With no member for the key, four of those five arms are unreachable through the API - clear,
     * copy-last and the return would all be dead, and the invalid-key message unprovokable. A
     * server-side record of the last key pressed is the one thing rule R6 forbids, so the key travels
     * in the payload.
     *
     * <p>The width is the module's convention: {@code COTRN02C} copies neither {@code CVCRD01Y} nor
     * {@code CSSTRPFY} and tests the raw {@code EIBAID} byte inline, so five characters is taken from
     * {@code 10 CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy} and from the width
     * {@code common.PfKeyResolver.AID_TOKEN_LENGTH} publishes. The four tokens this screen acts on are
     * {@code ENTER}, {@code PFK03}, {@code PFK04} and {@code PFK05}; anything else, spaces included, is
     * the {@code WHEN OTHER} arm. {@code common.PfKeyResolver.AidKey#token()} space-pads the shorter
     * mnemonics to this width, so a caller must not trim what it produces.
     */
    public static final int AID_LENGTH = 5;

    /** Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field. */
    public static final String AID_FIELD = "EIBAID";

    // =================================================================================================
    // The field table. One constant per name-labelled DFHMDF definition, in copybook declaration order.
    // =================================================================================================

    /**
     * The twenty-one screen fields of mapset {@code COTRN02}, in {@code 01 COTRN2AI} declaration order.
     *
     * <p>This enum <em>is</em> the width table, and it exists so that the copybook can be diffed
     * against Java line by line: each constant states its {@code DFHMDF} label, its declared width and
     * the absolute offset at which its field group begins in the {@value #AI_GROUP_LENGTH}-byte image.
     * The four symbolic-map item names are <em>derived</em> rather than retyped -
     * {@link #inputItem()}, {@link #lengthItem()}, {@link #flagItem()} and {@link #attributeItem()}
     * append the suffix BMS itself appends - so a name cannot drift from its label through a
     * transcription slip.
     *
     * <p>Offsets are stated explicitly rather than accumulated, and are then checked twice over: the
     * static initialiser below verifies each against the running total derived from the declared
     * widths, and {@link RecordLayout} independently verifies that the spans built from them are
     * contiguous from byte zero and sum to exactly {@value #AI_GROUP_LENGTH}.
     *
     * <p>Enumerating the fields also makes the {@code CSSETATY} highlight loop and the parity
     * fingerprint straightforward: both need to walk every field, and neither should carry its own copy
     * of the field list.
     */
    public enum ScreenField {

        /** {@code TRNNAME}, the transaction-name header field. ASKIP, and {@code FSET} so CICS returns it. */
        TRNNAME("TRNNAME", TRNNAME_LENGTH, 12, false),

        /** {@code TITLE01}, the first title header field. ASKIP, {@code FSET}, yellow. */
        TITLE01("TITLE01", TITLE01_LENGTH, 23, false),

        /** {@code CURDATE}, the current-date header field, initialised {@code 'mm/dd/yy'}. */
        CURDATE("CURDATE", CURDATE_LENGTH, 70, false),

        /** {@code PGMNAME}, the program-name header field. ASKIP, {@code FSET}. */
        PGMNAME("PGMNAME", PGMNAME_LENGTH, 85, false),

        /** {@code TITLE02}, the second title header field. ASKIP, {@code FSET}, yellow. */
        TITLE02("TITLE02", TITLE02_LENGTH, 100, false),

        /** {@code CURTIME}, the current-time header field, initialised {@code 'hh:mm:ss'}. */
        CURTIME("CURTIME", CURTIME_LENGTH, 147, false),

        /**
         * {@code ACTIDIN}, the account key. {@code UNPROT}, and the only field declared {@code IC}, so
         * it holds the initial cursor. {@code COTRN02C:204} parses it with {@code FUNCTION NUMVAL} and
         * {@code :205} stores the normalised value straight back into it.
         */
        ACTIDIN("ACTIDIN", ACTIDIN_LENGTH, 162, true),

        /**
         * {@code CARDNIN}, the card key. {@code UNPROT}. {@code COTRN02C:218} parses it with
         * {@code FUNCTION NUMVAL}; {@code :209} fills it from the cross-reference when the operator
         * keyed an account instead.
         */
        CARDNIN("CARDNIN", CARDNIN_LENGTH, 180, true),

        /** {@code TTYPCD}, the transaction type code. {@code UNPROT}. */
        TTYPCD("TTYPCD", TTYPCD_LENGTH, 203, true),

        /** {@code TCATCD}, the transaction category code. {@code UNPROT}. */
        TCATCD("TCATCD", TCATCD_LENGTH, 212, true),

        /** {@code TRNSRC}, the transaction source. {@code UNPROT}. */
        TRNSRC("TRNSRC", TRNSRC_LENGTH, 223, true),

        /** {@code TDESC}, the transaction description. {@code UNPROT}, the widest input field. */
        TDESC("TDESC", TDESC_LENGTH, 240, true),

        /**
         * {@code TRNAMT}, the edited amount under the mask {@code +99999999.99}. {@code UNPROT}.
         * Validated positionally at {@code COTRN02C:340-343}, which is why it stays alphanumeric.
         */
        TRNAMT("TRNAMT", TRNAMT_LENGTH, 307, true),

        /** {@code TORIGDT}, the origination date. {@code UNPROT}, validated via {@code CSUTLDTC} at {@code :393}. */
        TORIGDT("TORIGDT", TORIGDT_LENGTH, 326, true),

        /** {@code TPROCDT}, the processing date. {@code UNPROT}, validated via {@code CSUTLDTC} at {@code :413}. */
        TPROCDT("TPROCDT", TPROCDT_LENGTH, 343, true),

        /** {@code MID}, the merchant id. {@code UNPROT}, carried unmasked. */
        MID("MID", MID_LENGTH, 360, true),

        /** {@code MNAME}, the merchant name. {@code UNPROT}. */
        MNAME("MNAME", MNAME_LENGTH, 376, true),

        /** {@code MCITY}, the merchant city. {@code UNPROT}. */
        MCITY("MCITY", MCITY_LENGTH, 413, true),

        /** {@code MZIP}, the merchant postal code. {@code UNPROT}. */
        MZIP("MZIP", MZIP_LENGTH, 445, true),

        /**
         * {@code CONFIRM}, the add-confirmation flag. {@code UNPROT}, one character, evaluated at
         * {@code COTRN02C:169}.
         */
        CONFIRM("CONFIRM", CONFIRM_LENGTH, 462, true),

        /** {@code ERRMSG}, the error line. ASKIP, {@code BRT}, red. */
        ERRMSG("ERRMSG", ERRMSG_LENGTH, 470, false);

        /**
         * Verifies the declared offsets against the declared widths at class-initialisation time, so a
         * transcription slip in the table above is reported here rather than surfacing later as a
         * quietly misplaced field. The rule is the copybook's own stride: the first group starts after
         * the {@value #TIOAPFX_PREFIX_LENGTH}-byte TIOAPFX prefix, and each subsequent group starts
         * {@value #METADATA_PREFIX_LENGTH} plus the previous width further on.
         */
        static {
            int expected = TIOAPFX_PREFIX_LENGTH;
            int widths = 0;
            for (ScreenField field : values()) {
                if (field.groupOffset != expected) {
                    throw new IllegalStateException("Screen field " + field.name() + " declares group "
                            + "offset " + field.groupOffset + " but the preceding fields end at byte "
                            + expected + "; every byte of the " + AI_GROUP_LENGTH + "-byte COTRN2AI "
                            + "image must be accounted for");
                }
                expected += METADATA_PREFIX_LENGTH + field.length;
                widths += field.length;
            }
            if (values().length != PAYLOAD_FIELD_COUNT) {
                throw new IllegalStateException("Mapset COTRN02 declares " + PAYLOAD_FIELD_COUNT
                        + " name-labelled DFHMDF fields but the table lists " + values().length);
            }
            if (widths != PAYLOAD_WIDTH_TOTAL) {
                throw new IllegalStateException("Declared widths sum to " + widths + " but the copybook "
                        + "sums to " + PAYLOAD_WIDTH_TOTAL);
            }
            if (expected != AI_GROUP_LENGTH) {
                throw new IllegalStateException("The field table spans " + expected + " byte(s) but "
                        + "01 COTRN2AI is " + AI_GROUP_LENGTH + " byte(s) wide");
            }
        }

        /** The {@code DFHMDF} label, verbatim from {@code app/bms/COTRN02.bms}. */
        private final String label;

        /** The declared payload width, verbatim from the {@code xxxI} {@code PICTURE} clause. */
        private final int length;

        /** The absolute 0-based offset at which this field's {@code xxxL} item begins. */
        private final int groupOffset;

        /** Whether the {@code DFHMDF} declares {@code UNPROT}, making the field input-capable. */
        private final boolean unprotected;

        /**
         * Declares one screen field. Offsets are checked against the declared widths by the static
         * initialiser above, so a wrong value here fails at class initialisation.
         *
         * @param label       the {@code DFHMDF} label, verbatim from {@code app/bms/COTRN02.bms}
         * @param length      the declared payload width from the {@code xxxI} {@code PICTURE} clause
         * @param groupOffset the absolute 0-based offset of this field's {@code xxxL} item
         * @param unprotected whether the {@code DFHMDF} declares {@code UNPROT}
         */
        ScreenField(String label, int length, int groupOffset, boolean unprotected) {
            this.label = label;
            this.length = length;
            this.groupOffset = groupOffset;
            this.unprotected = unprotected;
        }

        /**
         * The {@code DFHMDF} label as {@code app/bms/COTRN02.bms} spells it, which is also the stem of
         * all four symbolic-map item names.
         *
         * @return the label, for example {@code TRNAMT}
         */
        public String label() {
            return label;
        }

        /**
         * The declared payload width in bytes, from the {@code xxxI} {@code PICTURE} clause. Equal to
         * the {@code DFHMDF} {@code LENGTH=} of the same field for every one of the twenty-one fields.
         *
         * @return the width, for example {@value #TRNAMT_LENGTH} for {@link #TRNAMT}
         */
        public int length() {
            return length;
        }

        /**
         * The absolute 0-based offset of this field's {@code xxxL} length item, where its
         * {@value #METADATA_PREFIX_LENGTH}-byte metadata prefix begins.
         *
         * @return the group offset within the {@value #AI_GROUP_LENGTH}-byte image
         */
        public int groupOffset() {
            return groupOffset;
        }

        /**
         * The absolute 0-based offset of this field's payload bytes, which is
         * {@link #groupOffset()} plus {@value #METADATA_PREFIX_LENGTH}.
         *
         * @return the payload offset within the {@value #AI_GROUP_LENGTH}-byte image
         */
        public int payloadOffset() {
            return groupOffset + METADATA_PREFIX_LENGTH;
        }

        /**
         * Whether the {@code DFHMDF} declares {@code ATTRB=(...,UNPROT)} and so accepts operator input.
         * Fourteen of the twenty-one fields do, which is what identifies this screen as a data-entry
         * form rather than the read-only view its class name suggests - see the risk R-B discussion on
         * {@link TransactionViewRequest}.
         *
         * @return {@code true} for the fourteen input-capable fields, {@code false} for the six ASKIP
         *         header fields and the ASKIP error line
         */
        public boolean unprotectedField() {
            return unprotected;
        }

        /**
         * The symbolic-map <strong>payload</strong> item name, verbatim: the label with BMS's
         * {@code I} suffix. For {@link #TRNAMT} this is {@code TRNAMTI}, declared
         * {@code 02 TRNAMTI PIC X(12)} at copybook line 96.
         *
         * @return the {@code xxxI} item name, which is also this field's span name in
         *         {@link #AI_LAYOUT} and the name the parity differ compares by
         */
        public String inputItem() {
            return label + "I";
        }

        /**
         * The symbolic-map <strong>output</strong> item name: the label with BMS's {@code O} suffix.
         * Because {@code 01 COTRN2AO REDEFINES COTRN2AI}, this item occupies the very same bytes as
         * {@link #inputItem()} and is named here only to make that aliasing explicit.
         *
         * @return the {@code xxxO} item name
         */
        public String outputItem() {
            return label + "O";
        }

        /**
         * The {@code xxxL} length item name: {@code COMP PIC S9(4)}, metadata, never a payload member.
         *
         * @return the {@code xxxL} item name, for example {@code TRNAMTL}
         */
        public String lengthItem() {
            return label + "L";
        }

        /**
         * The {@code xxxF} flag item name: {@code PICTURE X}, metadata.
         *
         * @return the {@code xxxF} item name, for example {@code TRNAMTF}
         */
        public String flagItem() {
            return label + "F";
        }

        /**
         * The {@code xxxA} attribute item name. It is declared {@code 03 xxxA PICTURE X} inside
         * {@code 02 FILLER REDEFINES xxxF}, so it views the <em>same single byte</em> as
         * {@link #flagItem()} rather than a byte of its own.
         *
         * @return the {@code xxxA} item name, for example {@code TRNAMTA}
         */
        public String attributeItem() {
            return label + "A";
        }

        /**
         * This field's payload span: an alphanumeric {@code PIC X(n)} descriptor named with the
         * verbatim {@code xxxI} item name and anchored at {@link #payloadOffset()}.
         *
         * @return the payload descriptor, the only span of this field that carries data
         */
        public FieldSpan payloadSpan() {
            return FieldSpan.alphanumeric(inputItem(), payloadOffset(), length);
        }

        /**
         * The reserved spans this field's metadata prefix occupies in the group image, in declaration
         * order: the two-byte {@code xxxL} halfword, the one-byte {@code xxxF} flag which {@code xxxA}
         * redefines, and the four-byte {@code FILLER}.
         *
         * <p>They are declared as {@code FILLER} because they are metadata rather than payload, and
         * because {@code xxxL} is a <em>binary</em> halfword rather than character data - it has no
         * faithful character image. Declaring them keeps every byte of the group accounted for, which
         * is what places each payload field at its true copybook offset; the live metadata values
         * travel separately, as {@link FieldMetadata}.
         *
         * @return the three reserved descriptors, never {@code null} and never empty
         */
        public List<FieldSpan> metadataSpans() {
            return List.of(
                    FieldSpan.filler(groupOffset, LENGTH_ITEM_LENGTH),
                    FieldSpan.filler(groupOffset + LENGTH_ITEM_LENGTH, FLAG_ITEM_LENGTH),
                    FieldSpan.filler(groupOffset + LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH,
                            RESERVED_FILLER_LENGTH));
        }
    }

    // =================================================================================================
    // The fixed-width layout of 01 COTRN2AI, and the PICTURE rule engine.
    // =================================================================================================

    /**
     * The {@value #AI_GROUP_LENGTH}-byte layout of {@code 01 COTRN2AI}: the twelve-byte TIOAPFX prefix,
     * then for each of the twenty-one fields its three reserved metadata spans followed by its payload
     * span.
     *
     * <p>{@link RecordLayout} self-checks this on construction. It refuses a layout whose spans leave a
     * gap, overlap, or fail to sum to exactly {@value #AI_GROUP_LENGTH}, so a wrong width or offset
     * fails at class initialisation with the offending span named. That check and the one in
     * {@link ScreenField}'s static initialiser are independent of one another and derive the total by
     * different routes, so agreeing on {@value #AI_GROUP_LENGTH} is meaningful rather than circular.
     *
     * <p>Only the twenty-one payload spans are referable by name; the metadata spans are {@code FILLER}
     * and are consequently skipped by {@link FixedWidthCodec#deserialise(RecordLayout, byte[])}. Each
     * payload span is named with its verbatim {@code xxxI} item name, which is the name the parity
     * differ compares field by field.
     */
    public static final RecordLayout AI_LAYOUT = buildAiLayout();

    /**
     * Assembles {@link #AI_LAYOUT} by walking {@link ScreenField} in declaration order, so the layout
     * and the width table cannot disagree - there is one list of fields in this file, not two.
     *
     * @return the self-checked {@value #AI_GROUP_LENGTH}-byte layout
     */
    private static RecordLayout buildAiLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        for (ScreenField field : ScreenField.values()) {
            spans.addAll(field.metadataSpans());
            spans.add(field.payloadSpan());
        }
        return RecordLayout.of(AI_GROUP_LENGTH, spans.toArray(new FieldSpan[0]));
    }

    /**
     * The single implementation of the {@code PIC X} width rule used by this class, for
     * <em>character-level</em> work only: {@link FixedWidthCodec#movePicX(String, int)} converts nothing
     * to bytes, so the code page this instance was built for takes no part in its result.
     *
     * <p>It is named {@link StandardCharsets#US_ASCII} explicitly and never derived from the platform,
     * and it is the code page of the authoritative fixtures under {@code app/data/ASCII}. Every
     * <em>byte</em> boundary in this class - {@link #toFixedWidth(Charset)},
     * {@link #writeInto(FixedWidthRecord)}, {@link #fromFixedWidth(byte[], Charset)} and
     * {@link #readFrom(FixedWidthRecord)} - instead takes its code page from the caller, because a
     * fixed-width mainframe image is bytes in a specific code page and this type is bound to neither.
     *
     * <p>{@link FixedWidthCodec} is immutable and holds only its {@link Charset}, so one shared
     * instance is safe and this constant is not mutable static state.
     */
    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(StandardCharsets.US_ASCII);

    // =================================================================================================
    // Metadata carriers. Per AAP 0.6.3 the xxxL, xxxF and xxxA items are validation and highlight
    // metadata, never JSON payload members.
    // =================================================================================================

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} metadata of one screen field: the length item CICS
     * reports, and the attribute byte the program writes to highlight a field in error.
     *
     * <p>These are deliberately <strong>not</strong> payload. A payload member must trace to a
     * {@code DFHMDF} field definition, and these three items trace to the symbolic map's plumbing
     * instead - which is why the whole carrier is {@link JsonIgnore}d on the instance that holds it.
     *
     * <h2>{@code xxxA} redefines {@code xxxF}: one byte, two names</h2>
     *
     * The copybook declares, for every field:
     *
     * <pre>
     *  02  xxxF    PICTURE X.
     *  02  FILLER REDEFINES xxxF.
     *    03 xxxA   PICTURE X.
     * </pre>
     *
     * So {@code xxxA} is a second <em>view</em> of the same single byte, not a second byte.
     * {@link #flag()} and {@link #attribute()} therefore read one backing field and
     * {@link #setFlag(char)} and {@link #setAttribute(char)} write it; storing two characters would
     * let the two names disagree, which the storage they model makes impossible.
     *
     * <h2>{@code xxxL} is written, not only read, and so must be signed</h2>
     *
     * {@code COTRN02C} moves {@value #CURSOR_REQUEST} into a length item to place the 3270 cursor on a
     * field, and it does so at <strong>35</strong> sites - more than any other program in this package.
     * Examples: {@code :201} {@code MOVE -1 TO ACTIDINL OF COTRN2AI}, {@code :280}
     * {@code MOVE -1 TO TRNAMTL OF COTRN2AI}, {@code :180}
     * {@code MOVE -1 TO CONFIRML OF COTRN2AI}. Across the program the targets are the fourteen
     * {@code UNPROT} fields, {@code ACTIDINL} alone accounting for eleven sites.
     *
     * <p>The item is {@code COMP PIC S9(4)} - a <em>signed</em> binary halfword - so the carrier is a
     * signed integral type, is never made unsigned and is never clamped at zero. Clamping would silently
     * discard every cursor request in the program.
     */
    public static final class FieldMetadata {

        /**
         * The attribute byte of a field that has neither been transmitted nor highlighted: low-values,
         * which is what CICS leaves in {@code xxxF} for a field the operator did not modify.
         */
        public static final char UNSET_ATTRIBUTE = '\u0000';

        /** The length CICS reports for a field the operator did not enter. */
        public static final int NO_INPUT_LENGTH = 0;

        /**
         * {@code xxxL COMP PIC S9(4)}. Holds {@value TransactionViewRequest#CURSOR_REQUEST} when the
         * program has requested the cursor, otherwise the number of characters received.
         */
        private int length;

        /**
         * The single byte that {@code xxxF} names and {@code xxxA} redefines. One field, because it is
         * one byte.
         */
        private char attributeByte;

        /** Creates metadata for a field that has not been entered and carries no attribute. */
        public FieldMetadata() {
            this.length = NO_INPUT_LENGTH;
            this.attributeByte = UNSET_ATTRIBUTE;
        }

        /**
         * Creates metadata with explicit values.
         *
         * @param length        the {@code xxxL} value, which may be
         *                      {@value TransactionViewRequest#CURSOR_REQUEST}
         * @param attributeByte the {@code xxxF} / {@code xxxA} byte
         * @throws IllegalArgumentException if {@code length} falls outside the signed binary halfword
         *                                  that {@code COMP PIC S9(4)} occupies
         */
        public FieldMetadata(int length, char attributeByte) {
            setLength(length);
            this.attributeByte = attributeByte;
        }

        /**
         * Copy constructor, so a request can be duplicated without sharing metadata with its original.
         *
         * @param other the metadata to copy, never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldMetadata(FieldMetadata other) {
            Objects.requireNonNull(other, "Metadata to copy is required");
            this.length = other.length;
            this.attributeByte = other.attributeByte;
        }

        /**
         * The {@code xxxL} length item.
         *
         * @return {@value TransactionViewRequest#CURSOR_REQUEST} when the cursor has been requested,
         *         {@value #NO_INPUT_LENGTH} when the field was not entered, otherwise the received
         *         character count
         */
        public int length() {
            return length;
        }

        /**
         * Sets the {@code xxxL} length item. Negative values are accepted, because
         * {@value TransactionViewRequest#CURSOR_REQUEST} is the program's own idiom for positioning the
         * cursor.
         *
         * @param newLength the value to store, within the signed binary halfword {@code COMP PIC S9(4)}
         *                  occupies
         * @throws IllegalArgumentException if {@code newLength} does not fit that halfword
         */
        public void setLength(int newLength) {
            if (newLength < Short.MIN_VALUE || newLength > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Length item value " + newLength + " does not fit "
                        + "COMP PIC S9(4), a signed binary halfword holding " + Short.MIN_VALUE
                        + " to " + Short.MAX_VALUE);
            }
            this.length = newLength;
        }

        /**
         * Requests the 3270 cursor on this field, reproducing {@code MOVE -1 TO <field>L}.
         *
         * <p>This is the operation {@code COTRN02C} performs at all thirty-five of its cursor-positioning
         * sites, always alongside setting the error flag and an error message.
         */
        public void requestCursor() {
            this.length = CURSOR_REQUEST;
        }

        /**
         * Whether the cursor has been requested on this field.
         *
         * @return {@code true} when the length item holds
         *         {@value TransactionViewRequest#CURSOR_REQUEST}
         */
        public boolean isCursorRequested() {
            return length == CURSOR_REQUEST;
        }

        /**
         * Whether CICS reported any input for this field, which is the test a length item exists to
         * support.
         *
         * @return {@code true} when the length item is greater than {@value #NO_INPUT_LENGTH}
         */
        public boolean hasInput() {
            return length > NO_INPUT_LENGTH;
        }

        /**
         * The {@code xxxF} flag byte.
         *
         * @return the single attribute byte, {@value #UNSET_ATTRIBUTE} when never set
         */
        public char flag() {
            return attributeByte;
        }

        /**
         * Sets the {@code xxxF} flag byte. Because {@code xxxA} redefines {@code xxxF}, this is
         * indistinguishable from {@link #setAttribute(char)} - as it is in COBOL.
         *
         * @param newFlag the byte to store
         */
        public void setFlag(char newFlag) {
            this.attributeByte = newFlag;
        }

        /**
         * The {@code xxxA} attribute byte. This is the <em>same byte</em> as {@link #flag()}, viewed
         * through the redefining name the program uses when it highlights a field - the
         * {@code CSSETATY} idiom of moving a colour attribute and a {@code '*'} marker onto a field in
         * error while the program is in re-enter state.
         *
         * @return the single attribute byte
         */
        public char attribute() {
            return attributeByte;
        }

        /**
         * Sets the {@code xxxA} attribute byte, which is the same byte {@link #setFlag(char)} sets.
         *
         * @param newAttribute the byte to store
         */
        public void setAttribute(char newAttribute) {
            this.attributeByte = newAttribute;
        }

        /** Restores the not-entered, not-highlighted state of a freshly received map. */
        public void reset() {
            this.length = NO_INPUT_LENGTH;
            this.attributeByte = UNSET_ATTRIBUTE;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldMetadata candidate)) {
                return false;
            }
            return length == candidate.length && attributeByte == candidate.attributeByte;
        }

        @Override
        public int hashCode() {
            return Objects.hash(length, attributeByte);
        }

        @Override
        public String toString() {
            return "FieldMetadata[length=" + length + ", attribute=0x"
                    + Integer.toHexString(attributeByte) + "]";
        }
    }

    // =================================================================================================
    // The program's own commarea extension: CDEMO-CT02-INFO, COTRN02C lines 72-80.
    // =================================================================================================

    /**
     * {@code 05 CDEMO-CT02-INFO}, the {@value #CT02_INFO_LENGTH}-byte pagination cursor that
     * {@code COTRN02C} appends to the shared communication area.
     *
     * <h2>Why this is nested here and not shared</h2>
     *
     * The program copies the shared {@code COCOM01Y} at {@code app/cbl/COTRN02C.cbl:71} and then
     * <em>extends the commarea in place</em> at lines 72-80:
     *
     * <pre>
     *  05 CDEMO-CT02-INFO.
     *     10 CDEMO-CT02-TRNID-FIRST     PIC X(16).
     *     10 CDEMO-CT02-TRNID-LAST      PIC X(16).
     *     10 CDEMO-CT02-PAGE-NUM        PIC 9(08).
     *     10 CDEMO-CT02-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
     *        88 NEXT-PAGE-YES                     VALUE 'Y'.
     *        88 NEXT-PAGE-NO                      VALUE 'N'.
     *     10 CDEMO-CT02-TRN-SEL-FLG     PIC X(01).
     *     10 CDEMO-CT02-TRN-SELECTED    PIC X(16).
     * </pre>
     *
     * {@code 16 + 16 + 8 + 1 + 1 + 16 =} {@value #CT02_INFO_LENGTH}, so the communication area this
     * program passes is {@value NavigationContext#COMMAREA_LENGTH} {@code +}
     * {@value #CT02_INFO_LENGTH} {@code =} {@value #COMMAREA_TOTAL_LENGTH} bytes.
     *
     * <p><strong>{@link NavigationContext} is not widened to absorb this.</strong> That type is fixed at
     * exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and is shared by all seventeen online
     * controllers; growing it for one program's private extension would change the shape every other
     * controller sees.
     *
     * <p><strong>Nor is this hoisted into a shared class.</strong> Three sibling programs declare
     * structurally identical groups under <em>different names</em> - {@code CDEMO-CT00-*},
     * {@code CDEMO-CT01-*} and {@code CDEMO-CT02-*}. Identical shape is not interchangeability: the
     * parity gate compares field by field <em>by name</em>, so collapsing the three would erase the very
     * names it diffs on. Each program keeps its own nested type carrying its own names.
     *
     * <h2>The two {@code 88}-levels are predicates, and are not each other's negation</h2>
     *
     * {@code CDEMO-CT02-NEXT-PAGE-FLG} is {@code PIC X(01)} with the declared default {@code VALUE 'N'},
     * reproduced by {@link #NEXT_PAGE_DEFAULT} and applied by the no-argument constructor. A single
     * character may hold a space - which is what a zero-initialised commarea holds - or any other
     * character, in which case {@link #isNextPageYes()} and {@link #isNextPageNo()} are <em>both</em>
     * false. Writing either as the negation of the other would change behaviour for a blank flag, so
     * each tests the stored character directly and neither is stored as a separate boolean that could
     * drift out of step with the character it summarises.
     */
    public static final class Ct02Info {

        /** {@code CDEMO-CT02-TRNID-FIRST PIC X(16)}, {@code COTRN02C:73}. */
        public static final int TRNID_FIRST_LENGTH = 16;

        /** {@code CDEMO-CT02-TRNID-LAST PIC X(16)}, {@code COTRN02C:74}. */
        public static final int TRNID_LAST_LENGTH = 16;

        /** {@code CDEMO-CT02-PAGE-NUM PIC 9(08)}, {@code COTRN02C:75}. */
        public static final int PAGE_NUM_LENGTH = 8;

        /** {@code CDEMO-CT02-NEXT-PAGE-FLG PIC X(01)}, {@code COTRN02C:76}. */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /** {@code CDEMO-CT02-TRN-SEL-FLG PIC X(01)}, {@code COTRN02C:79}. */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /** {@code CDEMO-CT02-TRN-SELECTED PIC X(16)}, {@code COTRN02C:80}. */
        public static final int TRN_SELECTED_LENGTH = 16;

        /**
         * The full width of {@code CDEMO-CT02-INFO}:
         * {@value #TRNID_FIRST_LENGTH} + {@value #TRNID_LAST_LENGTH} + {@value #PAGE_NUM_LENGTH} +
         * {@value #NEXT_PAGE_FLG_LENGTH} + {@value #TRN_SEL_FLG_LENGTH} +
         * {@value #TRN_SELECTED_LENGTH} = <strong>{@value #CT02_INFO_LENGTH}</strong> bytes.
         */
        public static final int CT02_INFO_LENGTH = TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH
                + PAGE_NUM_LENGTH + NEXT_PAGE_FLG_LENGTH + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * The width of the communication area {@code COTRN02C} actually passes:
         * {@value NavigationContext#COMMAREA_LENGTH} shared bytes plus this group's
         * {@value #CT02_INFO_LENGTH} = <strong>{@value #COMMAREA_TOTAL_LENGTH}</strong>.
         */
        public static final int COMMAREA_TOTAL_LENGTH =
                NavigationContext.COMMAREA_LENGTH + CT02_INFO_LENGTH;

        /** Copybook name of {@link #getTrnidFirst()}, verbatim. */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT02-TRNID-FIRST";

        /** Copybook name of {@link #getTrnidLast()}, verbatim. */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT02-TRNID-LAST";

        /** Copybook name of {@link #getPageNum()}, verbatim. */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT02-PAGE-NUM";

        /** Copybook name of {@link #getNextPageFlg()}, verbatim. */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT02-NEXT-PAGE-FLG";

        /** Copybook name of {@link #getTrnSelFlg()}, verbatim. */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT02-TRN-SEL-FLG";

        /** Copybook name of {@link #getTrnSelected()}, verbatim. */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT02-TRN-SELECTED";

        /** {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code COTRN02C:77}. */
        public static final String NEXT_PAGE_YES = "Y";

        /** {@code 88 NEXT-PAGE-NO VALUE 'N'}, {@code COTRN02C:78}. */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * The declared {@code VALUE 'N'} of {@code CDEMO-CT02-NEXT-PAGE-FLG}, which is the only
         * {@code VALUE} clause in the group and is applied by {@link #Ct02Info()}.
         */
        public static final String NEXT_PAGE_DEFAULT = NEXT_PAGE_NO;

        /** Absolute 0-based offset of {@code CDEMO-CT02-TRNID-FIRST} within the group. */
        public static final int TRNID_FIRST_OFFSET = 0;

        /** Absolute 0-based offset of {@code CDEMO-CT02-TRNID-LAST}. */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;

        /** Absolute 0-based offset of {@code CDEMO-CT02-PAGE-NUM}. */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;

        /** Absolute 0-based offset of {@code CDEMO-CT02-NEXT-PAGE-FLG}. */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;

        /** Absolute 0-based offset of {@code CDEMO-CT02-TRN-SEL-FLG}. */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;

        /** Absolute 0-based offset of {@code CDEMO-CT02-TRN-SELECTED}. */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * The {@value #CT02_INFO_LENGTH}-byte layout of {@code CDEMO-CT02-INFO}, span names verbatim.
         * {@code CDEMO-CT02-PAGE-NUM} is declared {@code unsignedNumeric} because it is {@code PIC 9(08)}
         * and so zero-fills on the <strong>left</strong>, unlike the five character fields around it
         * which space-pad on the right. {@link RecordLayout} refuses this layout unless its spans are
         * contiguous from zero and sum to exactly {@value #CT02_INFO_LENGTH}.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(CT02_INFO_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET, NEXT_PAGE_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET, TRN_SELECTED_LENGTH));

        /** {@code CDEMO-CT02-TRNID-FIRST PIC X(16)}: the first transaction id on the current page. */
        @Size(max = TRNID_FIRST_LENGTH)
        private String trnidFirst;

        /** {@code CDEMO-CT02-TRNID-LAST PIC X(16)}: the last transaction id on the current page. */
        @Size(max = TRNID_LAST_LENGTH)
        private String trnidLast;

        /** {@code CDEMO-CT02-PAGE-NUM PIC 9(08)}: an unsigned, scale-free page counter. */
        private int pageNum;

        /** {@code CDEMO-CT02-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}: whether a further page exists. */
        @Size(max = NEXT_PAGE_FLG_LENGTH)
        private String nextPageFlg;

        /** {@code CDEMO-CT02-TRN-SEL-FLG PIC X(01)}: the selection marker the operator typed. */
        @Size(max = TRN_SEL_FLG_LENGTH)
        private String trnSelFlg;

        /** {@code CDEMO-CT02-TRN-SELECTED PIC X(16)}: the transaction id selected on the list screen. */
        @Size(max = TRN_SELECTED_LENGTH)
        private String trnSelected;

        /**
         * Creates the group in its declared initial state: the two ids, the selection flag and the
         * selected id are spaces, the page number is zero, and {@code CDEMO-CT02-NEXT-PAGE-FLG} carries
         * its declared {@code VALUE 'N'} - so {@link #isNextPageNo()} is true and
         * {@link #isNextPageYes()} is false on a fresh instance, exactly as the copybook specifies.
         */
        public Ct02Info() {
            this.trnidFirst = spaces(TRNID_FIRST_LENGTH);
            this.trnidLast = spaces(TRNID_LAST_LENGTH);
            this.pageNum = 0;
            this.nextPageFlg = NEXT_PAGE_DEFAULT;
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Creates the group with explicit values. Every argument is stored exactly as supplied, without
         * trimming or padding, so a round trip is lossless.
         *
         * @param trnidFirst  {@code CDEMO-CT02-TRNID-FIRST}, may be {@code null}
         * @param trnidLast   {@code CDEMO-CT02-TRNID-LAST}, may be {@code null}
         * @param pageNum     {@code CDEMO-CT02-PAGE-NUM}, {@code 0} to 99999999
         * @param nextPageFlg {@code CDEMO-CT02-NEXT-PAGE-FLG}, may be {@code null}
         * @param trnSelFlg   {@code CDEMO-CT02-TRN-SEL-FLG}, may be {@code null}
         * @param trnSelected {@code CDEMO-CT02-TRN-SELECTED}, may be {@code null}
         * @throws IllegalArgumentException if {@code pageNum} does not fit {@code PIC 9(08)}
         */
        public Ct02Info(String trnidFirst,
                        String trnidLast,
                        int pageNum,
                        String nextPageFlg,
                        String trnSelFlg,
                        String trnSelected) {
            this.trnidFirst = trnidFirst;
            this.trnidLast = trnidLast;
            setPageNum(pageNum);
            this.nextPageFlg = nextPageFlg;
            this.trnSelFlg = trnSelFlg;
            this.trnSelected = trnSelected;
        }

        /**
         * Copy constructor, so a request can be duplicated without sharing its cursor.
         *
         * @param other the group to copy, never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public Ct02Info(Ct02Info other) {
            Objects.requireNonNull(other, "A CDEMO-CT02-INFO group to copy is required");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * {@code CDEMO-CT02-TRNID-FIRST}, exactly as stored.
         *
         * @return the first transaction id of the page, possibly {@code null}
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Sets {@code CDEMO-CT02-TRNID-FIRST} without trimming or truncating.
         *
         * @param trnidFirst the value to store, may be {@code null}
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = trnidFirst;
        }

        /**
         * {@code CDEMO-CT02-TRNID-LAST}, exactly as stored.
         *
         * @return the last transaction id of the page, possibly {@code null}
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Sets {@code CDEMO-CT02-TRNID-LAST} without trimming or truncating.
         *
         * @param trnidLast the value to store, may be {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = trnidLast;
        }

        /**
         * {@code CDEMO-CT02-PAGE-NUM}. An {@code int} rather than a {@code BigDecimal} because
         * {@code PIC 9(08)} is scale-free, and eight digits fit an {@code int} comfortably.
         *
         * @return the page number, {@code 0} to 99999999
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Sets {@code CDEMO-CT02-PAGE-NUM}.
         *
         * @param pageNum the page number to store
         * @throws IllegalArgumentException if {@code pageNum} is negative or exceeds eight digits, since
         *                                  {@code PIC 9(08)} is unsigned and eight digits wide
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), which is "
                        + "unsigned, so it cannot hold " + pageNum);
            }
            if (pageNum > 99_999_999) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), which holds at "
                        + "most 8 digits, so it cannot hold " + pageNum);
            }
            this.pageNum = pageNum;
        }

        /**
         * {@code CDEMO-CT02-NEXT-PAGE-FLG}, exactly as stored.
         *
         * @return the flag character, possibly {@code null} and not necessarily
         *         {@value #NEXT_PAGE_YES} or {@value #NEXT_PAGE_NO}
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Sets {@code CDEMO-CT02-NEXT-PAGE-FLG} to any character, including one that satisfies neither
         * {@code 88}-level - a space, for instance, which is what a zero-initialised commarea holds.
         *
         * @param nextPageFlg the value to store, may be {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg = nextPageFlg;
        }

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, tested against the stored character.
         *
         * <p>Deliberately <strong>not</strong> {@code !isNextPageNo()}: the field is {@code PIC X(01)}
         * and the two conditions are not exhaustive, so a blank or unexpected character makes this and
         * {@link #isNextPageNo()} both false.
         *
         * <p>{@link JsonIgnore}d because it is <em>derived</em> from
         * {@code CDEMO-CT02-NEXT-PAGE-FLG} rather than stored beside it. Emitting it would place a
         * property on the wire that no setter can accept back, and would let a payload assert a
         * next-page state contradicting the flag character travelling with it. It is also not a
         * {@code DFHMDF} field, and only {@code DFHMDF} fields belong in the payload.
         *
         * @return {@code true} only when the flag is exactly {@value #NEXT_PAGE_YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, tested against the stored character. True on a freshly
         * constructed instance, because the copybook declares {@code VALUE 'N'}.
         *
         * <p>Deliberately <strong>not</strong> {@code !isNextPageYes()}, for the reason given on
         * {@link #isNextPageYes()}.
         *
         * <p>{@link JsonIgnore}d for the same reason as {@link #isNextPageYes()}: it is derived, not
         * stored, and it is not a {@code DFHMDF} field.
         *
         * @return {@code true} only when the flag is exactly {@value #NEXT_PAGE_NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /**
         * Asserts {@code 88 NEXT-PAGE-YES}, reproducing {@code SET NEXT-PAGE-YES TO TRUE}.
         */
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /**
         * Asserts {@code 88 NEXT-PAGE-NO}, reproducing {@code SET NEXT-PAGE-NO TO TRUE}.
         */
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * {@code CDEMO-CT02-TRN-SEL-FLG}, exactly as stored.
         *
         * @return the selection marker, possibly {@code null}
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Sets {@code CDEMO-CT02-TRN-SEL-FLG} without trimming or truncating.
         *
         * @param trnSelFlg the value to store, may be {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = trnSelFlg;
        }

        /**
         * {@code CDEMO-CT02-TRN-SELECTED}, exactly as stored.
         *
         * @return the selected transaction id, possibly {@code null}
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Sets {@code CDEMO-CT02-TRN-SELECTED} without trimming or truncating.
         *
         * @param trnSelected the value to store, may be {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = trnSelected;
        }

        /**
         * Renders this group as its {@value #CT02_INFO_LENGTH}-byte fixed-width image.
         *
         * <p>Every pad and truncation is performed by {@link FixedWidthCodec}: the five character fields
         * space-pad on the right and the page number zero-fills on the left. A {@code null} character
         * field is rendered as spaces, which is the value the copybook's own initial state holds.
         *
         * @param charset the code page to encode in, named explicitly by the caller and never derived
         *                from the platform
         * @return exactly {@value #CT02_INFO_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] toFixedWidth(Charset charset) {
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            Map<String, String> images = new LinkedHashMap<>();
            images.put(TRNID_FIRST_FIELD, orSpaces(trnidFirst, TRNID_FIRST_LENGTH));
            images.put(TRNID_LAST_FIELD, orSpaces(trnidLast, TRNID_LAST_LENGTH));
            images.put(PAGE_NUM_FIELD, codec.movePic9(pageNum, PAGE_NUM_LENGTH));
            images.put(NEXT_PAGE_FLG_FIELD, orSpaces(nextPageFlg, NEXT_PAGE_FLG_LENGTH));
            images.put(TRN_SEL_FLG_FIELD, orSpaces(trnSelFlg, TRN_SEL_FLG_LENGTH));
            images.put(TRN_SELECTED_FIELD, orSpaces(trnSelected, TRN_SELECTED_LENGTH));
            return codec.serialise(LAYOUT, images);
        }

        /**
         * Reads a {@value #CT02_INFO_LENGTH}-byte image back into a group, leaving every character field
         * untrimmed so that a round trip returns an equal value.
         *
         * @param image   exactly {@value #CT02_INFO_LENGTH} bytes
         * @param charset the code page the bytes are in, named explicitly by the caller
         * @return the decoded group, never {@code null}
         * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly
         *                                  {@value #CT02_INFO_LENGTH} bytes
         */
        public static Ct02Info fromFixedWidth(byte[] image, Charset charset) {
            Objects.requireNonNull(image, "A CDEMO-CT02-INFO image is required");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            Map<String, String> values = codec.deserialise(LAYOUT, image);
            Ct02Info group = new Ct02Info();
            group.trnidFirst = values.get(TRNID_FIRST_FIELD);
            group.trnidLast = values.get(TRNID_LAST_FIELD);
            group.setPageNum(codec.decodePic9AsInt(values.get(PAGE_NUM_FIELD)));
            group.nextPageFlg = values.get(NEXT_PAGE_FLG_FIELD);
            group.trnSelFlg = values.get(TRN_SEL_FLG_FIELD);
            group.trnSelected = values.get(TRN_SELECTED_FIELD);
            return group;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ct02Info candidate)) {
                return false;
            }
            return pageNum == candidate.pageNum
                    && Objects.equals(trnidFirst, candidate.trnidFirst)
                    && Objects.equals(trnidLast, candidate.trnidLast)
                    && Objects.equals(nextPageFlg, candidate.nextPageFlg)
                    && Objects.equals(trnSelFlg, candidate.trnSelFlg)
                    && Objects.equals(trnSelected, candidate.trnSelected);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg, trnSelected);
        }

        @Override
        public String toString() {
            return "Ct02Info[" + TRNID_FIRST_FIELD + "=" + trnidFirst
                    + ", " + TRNID_LAST_FIELD + "=" + trnidLast
                    + ", " + PAGE_NUM_FIELD + "=" + pageNum
                    + ", " + NEXT_PAGE_FLG_FIELD + "=" + nextPageFlg
                    + ", " + TRN_SEL_FLG_FIELD + "=" + trnSelFlg
                    + ", " + TRN_SELECTED_FIELD + "=" + trnSelected + "]";
        }
    }

    // =================================================================================================
    // The twenty-one payload members. One per name-labelled DFHMDF field, in copybook order, each
    // constrained only by its declared width - never more strictly than the COBOL.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}: the transaction-name header. */
    @Size(max = TRNNAME_LENGTH)
    private String trnname;

    /** {@code TITLE01I PIC X(40)}: the first title header. */
    @Size(max = TITLE01_LENGTH)
    private String title01;

    /** {@code CURDATEI PIC X(8)}: the current-date header, {@code mm/dd/yy}. */
    @Size(max = CURDATE_LENGTH)
    private String curdate;

    /** {@code PGMNAMEI PIC X(8)}: the program-name header. */
    @Size(max = PGMNAME_LENGTH)
    private String pgmname;

    /** {@code TITLE02I PIC X(40)}: the second title header. */
    @Size(max = TITLE02_LENGTH)
    private String title02;

    /** {@code CURTIMEI PIC X(8)}: the current-time header, {@code hh:mm:ss}. */
    @Size(max = CURTIME_LENGTH)
    private String curtime;

    /**
     * {@code ACTIDINI PIC X(11)}: the account key the operator keyed, carried unmasked at its full
     * declared width. Eleven characters, not sixteen.
     */
    @Size(max = ACTIDIN_LENGTH)
    private String actidin;

    /**
     * {@code CARDNINI PIC X(16)}: the card key, carried unmasked and unredacted at its full declared
     * width, exactly as the legacy screen accepts and echoes it.
     */
    @Size(max = CARDNIN_LENGTH)
    private String cardnin;

    /** {@code TTYPCDI PIC X(2)}: the transaction type code. */
    @Size(max = TTYPCD_LENGTH)
    private String ttypcd;

    /** {@code TCATCDI PIC X(4)}: the transaction category code. */
    @Size(max = TCATCD_LENGTH)
    private String tcatcd;

    /** {@code TRNSRCI PIC X(10)}: the transaction source. */
    @Size(max = TRNSRC_LENGTH)
    private String trnsrc;

    /** {@code TDESCI PIC X(60)}: the transaction description. */
    @Size(max = TDESC_LENGTH)
    private String tdesc;

    /**
     * {@code TRNAMTI PIC X(12)}: the amount under the edit mask {@code +99999999.99}, held as characters
     * because {@code COTRN02C:340-343} validates it positionally. No {@code BigDecimal} and certainly no
     * {@code double} appears here; conversion is the controller's.
     */
    @Size(max = TRNAMT_LENGTH)
    private String trnamt;

    /** {@code TORIGDTI PIC X(10)}: the origination date, validated by {@code CSUTLDTC} at {@code :393}. */
    @Size(max = TORIGDT_LENGTH)
    private String torigdt;

    /** {@code TPROCDTI PIC X(10)}: the processing date, validated by {@code CSUTLDTC} at {@code :413}. */
    @Size(max = TPROCDT_LENGTH)
    private String tprocdt;

    /** {@code MIDI PIC X(9)}: the merchant id, carried unmasked at its full declared width. */
    @Size(max = MID_LENGTH)
    private String mid;

    /** {@code MNAMEI PIC X(30)}: the merchant name. */
    @Size(max = MNAME_LENGTH)
    private String mname;

    /** {@code MCITYI PIC X(25)}: the merchant city. */
    @Size(max = MCITY_LENGTH)
    private String mcity;

    /** {@code MZIPI PIC X(10)}: the merchant postal code. */
    @Size(max = MZIP_LENGTH)
    private String mzip;

    /**
     * {@code CONFIRMI PIC X(1)}: the add-confirmation flag evaluated at {@code COTRN02C:169}. The
     * program accepts {@code 'Y'} and {@code 'y'} to add, treats {@code 'N'}, {@code 'n'}, spaces and
     * low-values as "not yet confirmed", and rejects anything else - so no Java constraint restricts the
     * character here, or that final rejection branch would become unreachable.
     */
    @Size(max = CONFIRM_LENGTH)
    private String confirm;

    /** {@code ERRMSGI PIC X(78)}: the error line the program paints in red. */
    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    // =================================================================================================
    // Conversation state, carried in the payload rather than in a session.
    // =================================================================================================

    /**
     * The {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} that
     * {@code COTRN02C} copies at {@code :71}, carried in the payload so that no server-side session is
     * needed. It supplies the from/to transaction and program, the user id and type, and the
     * enter-versus-re-enter context.
     *
     * <p><strong>{@code null} when no communication area was passed.</strong>
     * {@code app/cbl/COTRN02C.cbl:115-125} tests {@code IF EIBCALEN = 0} before anything else and, on
     * that branch, moves {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM} and returns to the previous
     * screen without ever reading a context byte. A freshly initialised area is the other branch - it
     * has a length, so the program copies it and goes on to test {@code CDEMO-PGM-REENTER}.
     * Substituting one for the other made the cold-start branch unreachable through this payload, so
     * absence is now carried as absence and {@link #hasNavigationContext()} is the discriminator.
     */
    private NavigationContext navigationContext;

    /**
     * The {@value Ct02Info#CT02_INFO_LENGTH}-byte {@code CDEMO-CT02-INFO} extension the program appends
     * to the commarea at {@code :72-80}. Marked {@link Valid} so that its own width constraints cascade
     * during request validation.
     */
    @Valid
    private Ct02Info ct02Info;

    /**
     * The resolved {@code EIBAID} key indication as a token, at most {@value #AID_LENGTH} characters.
     *
     * <p>Spaces mean no key has been resolved, which is the {@code WHEN OTHER} arm of
     * {@code app/cbl/COTRN02C.cbl:150}. See {@link #AID_LENGTH} for why this member exists and why it
     * is not a screen field.
     */
    @Size(max = AID_LENGTH)
    private String aid;

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} metadata of all twenty-one fields.
     *
     * <p>{@link JsonIgnore}d, because these items are validation and highlight metadata rather than
     * screen data: a payload member must trace to a {@code DFHMDF} definition and none of these three
     * does. The map is an {@link EnumMap} keyed by {@link ScreenField}, so it is dense, ordered by
     * copybook declaration order, and cannot acquire a key that is not a real screen field.
     *
     * <p>The reference is {@code final} while the {@link FieldMetadata} values it holds are mutable -
     * which is the point, since {@code COTRN02C} writes {@value #CURSOR_REQUEST} into a length item at
     * thirty-five sites. This is per-instance state; there is no static mutable state in this class.
     */
    @JsonIgnore
    private final Map<ScreenField, FieldMetadata> fieldMetadata = new EnumMap<>(ScreenField.class);

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * Creates an empty request: every payload field spaces at its declared width, a freshly initialised
     * {@link NavigationContext} in enter context, a {@link Ct02Info} carrying its declared
     * {@code VALUE 'N'}, and metadata reporting no input and no highlight on every field.
     *
     * <p>This is the state a first entry to the transaction sees, and it is the constructor Jackson uses
     * before applying setters.
     */
    public TransactionViewRequest() {
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.put(field, new FieldMetadata());
            setPayloadValue(field, spaces(field.length()));
        }
        // No communication area: nothing has been passed to a request nobody has filled in yet, which
        // is exactly the EIBCALEN = 0 state COTRN02C.cbl:115 tests for. The CT02 cursor is a different
        // case and does get a fresh instance - the program reads it only on the branch where an area
        // was passed, so it has no absence semantics of its own.
        this.navigationContext = null;
        this.ct02Info = new Ct02Info();
        this.aid = spaces(AID_LENGTH);
    }

    /**
     * Creates a request with every payload field supplied explicitly, in copybook declaration order.
     * Values are stored exactly as given - not trimmed, not padded, not truncated - so a round trip is
     * lossless.
     *
     * @param trnname           {@code TRNNAMEI PIC X(4)}
     * @param title01           {@code TITLE01I PIC X(40)}
     * @param curdate           {@code CURDATEI PIC X(8)}
     * @param pgmname           {@code PGMNAMEI PIC X(8)}
     * @param title02           {@code TITLE02I PIC X(40)}
     * @param curtime           {@code CURTIMEI PIC X(8)}
     * @param actidin           {@code ACTIDINI PIC X(11)}
     * @param cardnin           {@code CARDNINI PIC X(16)}
     * @param ttypcd            {@code TTYPCDI PIC X(2)}
     * @param tcatcd            {@code TCATCDI PIC X(4)}
     * @param trnsrc            {@code TRNSRCI PIC X(10)}
     * @param tdesc             {@code TDESCI PIC X(60)}
     * @param trnamt            {@code TRNAMTI PIC X(12)}, the {@code +99999999.99} edit mask
     * @param torigdt           {@code TORIGDTI PIC X(10)}
     * @param tprocdt           {@code TPROCDTI PIC X(10)}
     * @param mid               {@code MIDI PIC X(9)}
     * @param mname             {@code MNAMEI PIC X(30)}
     * @param mcity             {@code MCITYI PIC X(25)}
     * @param mzip              {@code MZIPI PIC X(10)}
     * @param confirm           {@code CONFIRMI PIC X(1)}
     * @param errmsg            {@code ERRMSGI PIC X(78)}
     * @param navigationContext the shared commarea, or {@code null} for the {@code EIBCALEN = 0} cold
     *                          start - preserved as {@code null}, never completed
     * @param ct02Info          the {@code CDEMO-CT02-INFO} cursor; {@code null} yields a fresh group
     */
    public TransactionViewRequest(String trnname,
                                 String title01,
                                 String curdate,
                                 String pgmname,
                                 String title02,
                                 String curtime,
                                 String actidin,
                                 String cardnin,
                                 String ttypcd,
                                 String tcatcd,
                                 String trnsrc,
                                 String tdesc,
                                 String trnamt,
                                 String torigdt,
                                 String tprocdt,
                                 String mid,
                                 String mname,
                                 String mcity,
                                 String mzip,
                                 String confirm,
                                 String errmsg,
                                 NavigationContext navigationContext,
                                 Ct02Info ct02Info) {
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.put(field, new FieldMetadata());
        }
        this.trnname = trnname;
        this.title01 = title01;
        this.curdate = curdate;
        this.pgmname = pgmname;
        this.title02 = title02;
        this.curtime = curtime;
        this.actidin = actidin;
        this.cardnin = cardnin;
        this.ttypcd = ttypcd;
        this.tcatcd = tcatcd;
        this.trnsrc = trnsrc;
        this.tdesc = tdesc;
        this.trnamt = trnamt;
        this.torigdt = torigdt;
        this.tprocdt = tprocdt;
        this.mid = mid;
        this.mname = mname;
        this.mcity = mcity;
        this.mzip = mzip;
        this.confirm = confirm;
        this.errmsg = errmsg;
        this.navigationContext = navigationContext;
        this.ct02Info = ct02Info == null ? new Ct02Info() : ct02Info;
        // The key is not one of the twenty-one screen values this constructor takes, so it starts at
        // its no-key-resolved state and is supplied through setAid.
        this.aid = spaces(AID_LENGTH);
    }

    /**
     * Copy constructor. The copy shares no mutable state with the original: the
     * {@link Ct02Info} and every {@link FieldMetadata} are copied, and
     * {@link NavigationContext} is an immutable record so it can safely be shared.
     *
     * @param other the request to copy, never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public TransactionViewRequest(TransactionViewRequest other) {
        Objects.requireNonNull(other, "A request to copy is required");
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.put(field, new FieldMetadata(other.metadata(field)));
            setPayloadValue(field, other.payloadValue(field));
        }
        this.navigationContext = other.navigationContext;
        this.ct02Info = new Ct02Info(other.ct02Info);
        this.aid = other.aid;
    }

    // =================================================================================================
    // Enum-keyed access. One switch over the twenty-one fields, so the field-walking callers - the
    // fixed-width image, the highlight loop and the parity fingerprint - need no field list of their own.
    // =================================================================================================

    /**
     * The current value of one payload field, exactly as stored.
     *
     * <p>Takes an argument, so it is not a bean property and adds nothing to the JSON payload.
     *
     * @param field the screen field to read, never {@code null}
     * @return the stored value, which may be {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String payloadValue(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACTIDIN -> actidin;
            case CARDNIN -> cardnin;
            case TTYPCD -> ttypcd;
            case TCATCD -> tcatcd;
            case TRNSRC -> trnsrc;
            case TDESC -> tdesc;
            case TRNAMT -> trnamt;
            case TORIGDT -> torigdt;
            case TPROCDT -> tprocdt;
            case MID -> mid;
            case MNAME -> mname;
            case MCITY -> mcity;
            case MZIP -> mzip;
            case CONFIRM -> confirm;
            case ERRMSG -> errmsg;
        };
    }

    /**
     * Stores one payload field by enum key, without trimming, padding or truncating.
     *
     * @param field the screen field to write, never {@code null}
     * @param value the value to store, which may be {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public void setPayloadValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A screen field is required");
        switch (field) {
            case TRNNAME -> trnname = value;
            case TITLE01 -> title01 = value;
            case CURDATE -> curdate = value;
            case PGMNAME -> pgmname = value;
            case TITLE02 -> title02 = value;
            case CURTIME -> curtime = value;
            case ACTIDIN -> actidin = value;
            case CARDNIN -> cardnin = value;
            case TTYPCD -> ttypcd = value;
            case TCATCD -> tcatcd = value;
            case TRNSRC -> trnsrc = value;
            case TDESC -> tdesc = value;
            case TRNAMT -> trnamt = value;
            case TORIGDT -> torigdt = value;
            case TPROCDT -> tprocdt = value;
            case MID -> mid = value;
            case MNAME -> mname = value;
            case MCITY -> mcity = value;
            case MZIP -> mzip = value;
            case CONFIRM -> confirm = value;
            case ERRMSG -> errmsg = value;
        }
    }

    // =================================================================================================
    // Metadata access. Never serialised; see the fieldMetadata declaration.
    // =================================================================================================

    /**
     * The live {@code xxxL} / {@code xxxF} / {@code xxxA} metadata of one field.
     *
     * <p>The returned carrier is intentionally mutable and intentionally the instance this request
     * holds, because the program writes through it - {@code MOVE -1 TO <field>L} at thirty-five sites,
     * and the {@code CSSETATY} attribute move when highlighting a field in error.
     *
     * @param field the screen field, never {@code null}
     * @return that field's metadata carrier, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public FieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return fieldMetadata.get(field);
    }

    /**
     * Requests the 3270 cursor on one field, reproducing {@code MOVE -1 TO <field>L}.
     *
     * @param field the field to place the cursor on, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public void requestCursor(ScreenField field) {
        metadata(field).requestCursor();
    }

    /**
     * Whether the cursor has been requested on one field.
     *
     * @param field the field to test, never {@code null}
     * @return {@code true} when that field's length item holds {@value #CURSOR_REQUEST}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public boolean isCursorRequested(ScreenField field) {
        return metadata(field).isCursorRequested();
    }

    /**
     * Restores every field's metadata to the not-entered, not-highlighted state of a freshly received
     * map. Payload values are untouched.
     */
    public void resetMetadata() {
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.get(field).reset();
        }
    }

    // =================================================================================================
    // Conversation state.
    // =================================================================================================

    /**
     * The shared {@value NavigationContext#COMMAREA_LENGTH}-byte communication area, or {@code null}
     * when none was passed.
     *
     * @return the context, or {@code null} for the {@code EIBCALEN = 0} cold start of
     *         {@code app/cbl/COTRN02C.cbl:115}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the communication area, or removes it.
     *
     * <p>{@code null} is stored as {@code null}. It is not a missing value to be filled in but a state
     * the program acts on: with {@code EIBCALEN = 0} there is no area, and
     * {@code app/cbl/COTRN02C.cbl:115-117} abandons the transaction for the sign-on screen rather than
     * reading one. Completing it would send the request down the {@code ELSE} branch instead, which is
     * different behaviour rather than a tidier spelling of the same behaviour.
     *
     * @param navigationContext the context to carry, or {@code null} to carry none
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN}
     * being non-zero at {@code app/cbl/COTRN02C.cbl:115}.
     *
     * <p>Not a JSON property: it is derived from {@link #getNavigationContext()}, which is already on
     * the wire as {@code null} or as an object. Emitting it as well would let a payload assert a
     * presence that contradicts the member it travels with.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}:
     * {@value Ct02Info#COMMAREA_TOTAL_LENGTH} when a communication area travelled with this request,
     * and {@code 0} when none did.
     *
     * <p>{@code COTRN02C} passes {@code CARDDEMO-COMMAREA} followed by its own
     * {@value Ct02Info#CT02_INFO_LENGTH}-byte {@code CDEMO-CT02-INFO} extension, so the non-zero case
     * is the sum of the two.
     *
     * @return {@value Ct02Info#COMMAREA_TOTAL_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? Ct02Info.COMMAREA_TOTAL_LENGTH : 0;
    }

    /**
     * The resolved {@code EIBAID} key indication - the key the operator pressed, which
     * {@code app/cbl/COTRN02C.cbl:133} evaluates.
     *
     * @return the token, {@value #AID_LENGTH} characters wide, spaces when no key has been resolved
     */
    public String getAid() {
        return aid;
    }

    /**
     * Replaces the resolved key indication.
     *
     * <p>Pass the token {@code common.PfKeyResolver.AidKey#token()} produces, already space-padded to
     * {@value #AID_LENGTH}. The four tokens this screen acts on are {@code 'ENTER'}, {@code 'PFK03'},
     * {@code 'PFK04'} and {@code 'PFK05'}; every other value, spaces included, is the
     * {@code WHEN OTHER} arm and its {@code CCDA-MSG-INVALID-KEY} message.
     *
     * @param aid the resolved key token; {@code null} becomes spaces, meaning no key resolved
     * @throws IllegalArgumentException if longer than {@value #AID_LENGTH} characters
     */
    public void setAid(String aid) {
        if (aid == null) {
            this.aid = spaces(AID_LENGTH);
            return;
        }
        if (aid.length() > AID_LENGTH) {
            throw new IllegalArgumentException(AID_FIELD + " is carried as a PIC X(" + AID_LENGTH
                    + ") token, matching common.PfKeyResolver.AID_TOKEN_LENGTH, but was given "
                    + aid.length() + " character(s). AidKey.token() already space-pads to that width, "
                    + "so a resolved token never overflows it");
        }
        this.aid = aid;
    }

    /**
     * The {@code CDEMO-CT02-INFO} pagination cursor.
     *
     * @return the cursor, never {@code null}
     */
    public Ct02Info getCt02Info() {
        return ct02Info;
    }

    /**
     * Replaces the pagination cursor. A {@code null} argument is normalised to a freshly initialised
     * group carrying the declared {@code VALUE 'N'}.
     *
     * @param ct02Info the cursor to carry, may be {@code null}
     */
    public void setCt02Info(Ct02Info ct02Info) {
        this.ct02Info = ct02Info == null ? new Ct02Info() : ct02Info;
    }

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0}: first entry, so the program paints the screen and validates
     * nothing.
     *
     * <p>Derived from the carried {@link NavigationContext} rather than stored beside it. A duplicate
     * boolean could drift out of step with the {@code CDEMO-PGM-CONTEXT} byte it summarises, and that
     * byte is the value actually transmitted; delegating makes disagreement impossible. It is
     * {@link JsonIgnore}d for the same reason - emitting it would place a property on the wire that can
     * contradict the context travelling with it.
     *
     * @return {@code true} when {@code CDEMO-PGM-CONTEXT} is
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnterContext() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1}: re-entry, so the program validates what was typed. This is
     * the state that gates the {@code CSSETATY} error highlight, and with fourteen input fields this
     * screen exercises that path heavily.
     *
     * <p>Like {@link #isEnterContext()} this is derived, not stored, and the two are <strong>not</strong>
     * each other's negation: {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold another digit,
     * in which case both are false. Both are false too when no communication area travelled at all:
     * there is then no {@code CDEMO-PGM-CONTEXT} byte to be in either state, and
     * {@link #hasNavigationContext()} is the predicate that distinguishes that case.
     *
     * @return {@code true} when {@code CDEMO-PGM-CONTEXT} is
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenterContext() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    // =================================================================================================
    // The twenty-one payload accessors. Each is a JSON property; none trims, pads or truncates, so a
    // space-padded value survives a round trip byte for byte. None masks its value.
    // =================================================================================================

    /**
     * {@code TRNNAMEI PIC X(4)}.
     *
     * @return the transaction-name header, possibly {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets {@code TRNNAMEI PIC X(4)}.
     *
     * @param trnname the value to store, may be {@code null}
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * {@code TITLE01I PIC X(40)}.
     *
     * @return the first title header, possibly {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets {@code TITLE01I PIC X(40)}.
     *
     * @param title01 the value to store, may be {@code null}
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * {@code CURDATEI PIC X(8)}.
     *
     * @return the current-date header, possibly {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets {@code CURDATEI PIC X(8)}.
     *
     * @param curdate the value to store, may be {@code null}
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * {@code PGMNAMEI PIC X(8)}.
     *
     * @return the program-name header, possibly {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets {@code PGMNAMEI PIC X(8)}.
     *
     * @param pgmname the value to store, may be {@code null}
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * {@code TITLE02I PIC X(40)}.
     *
     * @return the second title header, possibly {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets {@code TITLE02I PIC X(40)}.
     *
     * @param title02 the value to store, may be {@code null}
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * {@code CURTIMEI PIC X(8)}.
     *
     * @return the current-time header, possibly {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets {@code CURTIMEI PIC X(8)}.
     *
     * @param curtime the value to store, may be {@code null}
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * {@code ACTIDINI PIC X(11)}, the account key - eleven characters, carried unmasked.
     *
     * <p>Returned as characters. {@code COTRN02C:204} applies {@code FUNCTION NUMVAL} to it and
     * {@code :203} guards with {@code IS NOT NUMERIC}, both of which presuppose a character field; that
     * parsing is the controller's step, not this type's.
     *
     * @return the account key, possibly {@code null}
     */
    public String getActidin() {
        return actidin;
    }

    /**
     * Sets {@code ACTIDINI PIC X(11)}. Not truncated and not zero-filled: {@code COTRN02C:205} stores a
     * normalised value back into this very field, so the setter must accept whatever the program or the
     * operator produced.
     *
     * @param actidin the value to store, may be {@code null}
     */
    public void setActidin(String actidin) {
        this.actidin = actidin;
    }

    /**
     * {@code CARDNINI PIC X(16)}, the card key at its full sixteen characters, unmasked and unredacted.
     *
     * <p>No masking is applied here and none may be added. The legacy screen accepts and echoes the
     * value plainly, this is a like-for-like migration, and the parity gate compares the field
     * character for character - a masked value would fail it.
     *
     * @return the card key, possibly {@code null}
     */
    public String getCardnin() {
        return cardnin;
    }

    /**
     * Sets {@code CARDNINI PIC X(16)}. {@code COTRN02C:209} fills this field from the card
     * cross-reference when the operator keyed an account instead, so it is written as well as read.
     *
     * @param cardnin the value to store, may be {@code null}
     */
    public void setCardnin(String cardnin) {
        this.cardnin = cardnin;
    }

    /**
     * {@code TTYPCDI PIC X(2)}.
     *
     * @return the transaction type code, possibly {@code null}
     */
    public String getTtypcd() {
        return ttypcd;
    }

    /**
     * Sets {@code TTYPCDI PIC X(2)}.
     *
     * @param ttypcd the value to store, may be {@code null}
     */
    public void setTtypcd(String ttypcd) {
        this.ttypcd = ttypcd;
    }

    /**
     * {@code TCATCDI PIC X(4)}.
     *
     * @return the transaction category code, possibly {@code null}
     */
    public String getTcatcd() {
        return tcatcd;
    }

    /**
     * Sets {@code TCATCDI PIC X(4)}.
     *
     * @param tcatcd the value to store, may be {@code null}
     */
    public void setTcatcd(String tcatcd) {
        this.tcatcd = tcatcd;
    }

    /**
     * {@code TRNSRCI PIC X(10)}.
     *
     * @return the transaction source, possibly {@code null}
     */
    public String getTrnsrc() {
        return trnsrc;
    }

    /**
     * Sets {@code TRNSRCI PIC X(10)}.
     *
     * @param trnsrc the value to store, may be {@code null}
     */
    public void setTrnsrc(String trnsrc) {
        this.trnsrc = trnsrc;
    }

    /**
     * {@code TDESCI PIC X(60)}.
     *
     * @return the transaction description, possibly {@code null}
     */
    public String getTdesc() {
        return tdesc;
    }

    /**
     * Sets {@code TDESCI PIC X(60)}.
     *
     * @param tdesc the value to store, may be {@code null}
     */
    public void setTdesc(String tdesc) {
        this.tdesc = tdesc;
    }

    /**
     * {@code TRNAMTI PIC X(12)}, the amount under the edit mask {@code +99999999.99}.
     *
     * <p>A {@link String}, deliberately. The screen field is alphanumeric and
     * {@code COTRN02C:340-343} validates it by character position - sign, eight digits, the point, two
     * digits - which is only possible on a character field. The record field {@code TRAN-AMT} is
     * {@code PIC S9(09)V99} and holds nine integer digits where this mask holds eight, so the
     * record-to-screen move at {@code :481-485} left-truncates; the field is not widened to hide that.
     *
     * @return the twelve-character edited amount, possibly {@code null}
     */
    public String getTrnamt() {
        return trnamt;
    }

    /**
     * Sets {@code TRNAMTI PIC X(12)}. {@code COTRN02C:386} and {@code :485} both store an edited amount
     * back into this field, so it is written as well as read.
     *
     * @param trnamt the value to store, may be {@code null}
     */
    public void setTrnamt(String trnamt) {
        this.trnamt = trnamt;
    }

    /**
     * {@code TORIGDTI PIC X(10)}, the origination date.
     *
     * <p>Plain characters. {@code COTRN02C:390-407} passes the value to {@code CSUTLDTC} with the format
     * {@code 'YYYY-MM-DD'} and accepts it when the returned severity is {@code '0000'}, or when the
     * message number is the tolerated {@code '2513'}. That rule belongs to the controller; no Java date
     * constraint pre-empts it here.
     *
     * @return the origination date, possibly {@code null}
     */
    public String getTorigdt() {
        return torigdt;
    }

    /**
     * Sets {@code TORIGDTI PIC X(10)}.
     *
     * @param torigdt the value to store, may be {@code null}
     */
    public void setTorigdt(String torigdt) {
        this.torigdt = torigdt;
    }

    /**
     * {@code TPROCDTI PIC X(10)}, the processing date, validated through {@code CSUTLDTC} at
     * {@code COTRN02C:413} under the same severity rule as {@link #getTorigdt()}.
     *
     * @return the processing date, possibly {@code null}
     */
    public String getTprocdt() {
        return tprocdt;
    }

    /**
     * Sets {@code TPROCDTI PIC X(10)}.
     *
     * @param tprocdt the value to store, may be {@code null}
     */
    public void setTprocdt(String tprocdt) {
        this.tprocdt = tprocdt;
    }

    /**
     * {@code MIDI PIC X(9)}, the merchant id at its full nine characters, unmasked.
     *
     * @return the merchant id, possibly {@code null}
     */
    public String getMid() {
        return mid;
    }

    /**
     * Sets {@code MIDI PIC X(9)}.
     *
     * @param mid the value to store, may be {@code null}
     */
    public void setMid(String mid) {
        this.mid = mid;
    }

    /**
     * {@code MNAMEI PIC X(30)}.
     *
     * @return the merchant name, possibly {@code null}
     */
    public String getMname() {
        return mname;
    }

    /**
     * Sets {@code MNAMEI PIC X(30)}.
     *
     * @param mname the value to store, may be {@code null}
     */
    public void setMname(String mname) {
        this.mname = mname;
    }

    /**
     * {@code MCITYI PIC X(25)}.
     *
     * @return the merchant city, possibly {@code null}
     */
    public String getMcity() {
        return mcity;
    }

    /**
     * Sets {@code MCITYI PIC X(25)}.
     *
     * @param mcity the value to store, may be {@code null}
     */
    public void setMcity(String mcity) {
        this.mcity = mcity;
    }

    /**
     * {@code MZIPI PIC X(10)}.
     *
     * @return the merchant postal code, possibly {@code null}
     */
    public String getMzip() {
        return mzip;
    }

    /**
     * Sets {@code MZIPI PIC X(10)}.
     *
     * @param mzip the value to store, may be {@code null}
     */
    public void setMzip(String mzip) {
        this.mzip = mzip;
    }

    /**
     * {@code CONFIRMI PIC X(1)}, the add-confirmation flag.
     *
     * <p>Any single character may be present. {@code COTRN02C:169} evaluates it in source order:
     * {@code 'Y'} and {@code 'y'} add the transaction; {@code 'N'}, {@code 'n'}, spaces and low-values
     * re-prompt with "Confirm to add this transaction..."; every other character is rejected with
     * "Invalid value. Valid values are (Y/N)...". No Java constraint narrows the character, or that last
     * branch could never be reached.
     *
     * @return the confirmation flag, possibly {@code null}
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets {@code CONFIRMI PIC X(1)}.
     *
     * @param confirm the value to store, may be {@code null}
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * {@code ERRMSGI PIC X(78)}, the error line.
     *
     * @return the error message, possibly {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets {@code ERRMSGI PIC X(78)}.
     *
     * @param errmsg the value to store, may be {@code null}
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    // =================================================================================================
    // Figurative constants, reproduced as values of a declared width.
    // =================================================================================================

    /**
     * COBOL {@code SPACES} at a declared width - the value a character field holds after
     * {@code MOVE SPACES}, and the initial state of every payload field of a new request.
     *
     * @param length the declared width, zero or more
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        requireNonNegative(length, "SPACES");
        return " ".repeat(length);
    }

    /**
     * COBOL {@code LOW-VALUES} at a declared width: {@code length} bytes of {@code x'00'}.
     *
     * <p>This is what CICS leaves in an {@code xxxI} item for a field the operator did not transmit,
     * which is why {@code COTRN02C} tests {@code = SPACES OR LOW-VALUES} rather than spaces alone - at
     * {@code :276} for the amount and at the sibling guards for the other input fields. Reproducing the
     * value keeps "not entered" distinguishable from "explicitly blanked".
     *
     * @param length the declared width, zero or more
     * @return a string of exactly {@code length} null characters
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        requireNonNegative(length, "LOW-VALUES");
        return ScreenFieldImage.unpainted(length);
    }

    /**
     * Guards a figurative-constant width.
     *
     * @param length              the requested width
     * @param figurativeConstant  the constant's COBOL name, for the failure message
     * @throws IllegalArgumentException if {@code length} is negative
     */
    private static void requireNonNegative(int length, String figurativeConstant) {
        if (length < 0) {
            throw new IllegalArgumentException("A width of " + length + " is not a width; "
                    + figurativeConstant + " can only fill a field of zero or more bytes");
        }
    }

    /**
     * A field image for a nested-group character field, substituting spaces for {@code null}.
     *
     * <p>Spaces rather than low-values here, because the group this serves is the communication-area
     * extension rather than a received screen field: its declared initial state is spaces, and CICS
     * plays no part in filling it.
     *
     * @param value  the stored value, possibly {@code null}
     * @param length the declared width
     * @return the value, or {@code length} spaces when it is {@code null}
     */
    private static String orSpaces(String value, int length) {
        return value == null ? spaces(length) : value;
    }

    // =================================================================================================
    // The 555-byte fixed-width image of 01 COTRN2AI.
    // =================================================================================================

    /**
     * The twenty-one payload field images keyed by their verbatim {@code xxxI} item names, each already
     * exactly its declared width.
     *
     * <p>This is the map the parity harness fingerprints and the field differ compares field by field,
     * which is why the keys are the copybook's own names rather than Java identifiers. The iteration
     * order is copybook declaration order.
     *
     * @return a fresh, mutable map of {@value #PAYLOAD_FIELD_COUNT} entries, never {@code null}
     */
    public Map<String, String> payloadImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.inputItem(), imageOf(field));
        }
        return images;
    }

    /**
     * One field's image at exactly its declared width.
     *
     * <p>A {@code null} value becomes {@link #lowValues(int)}, reproducing a field CICS did not transmit.
     * Any other value is passed through {@link FixedWidthCodec#movePicX(String, int)}, so a short value
     * space-pads on the right and an over-wide one truncates on the right - the {@code PIC X} rule, and
     * applied by the codec rather than reimplemented here.
     *
     * @param field the screen field, never {@code null}
     * @return the image, of length exactly {@code field.length()}
     */
    private String imageOf(ScreenField field) {
        String value = payloadValue(field);
        if (value == null) {
            return lowValues(field.length());
        }
        return PICTURE_RULES.movePicX(value, field.length());
    }

    /**
     * Renders this request as the {@value #AI_GROUP_LENGTH}-byte image of {@code 01 COTRN2AI}.
     *
     * <p>The twelve-byte TIOAPFX prefix and each field's {@value #METADATA_PREFIX_LENGTH}-byte metadata
     * prefix are emitted as their declared reserved spans, so every payload field lands at its true
     * copybook offset - {@code TRNAMTI} at 314, {@code ERRMSGI} at 477, and so on. The live metadata
     * values travel separately, as {@link FieldMetadata}, because an {@code xxxL} halfword is binary and
     * has no faithful character image.
     *
     * @param charset the code page to encode in, named explicitly by the caller and never derived from
     *                the platform
     * @return exactly {@value #AI_GROUP_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} cannot encode a digit, a sign overpunch
     *                                  character or the space to a single byte
     */
    public byte[] toFixedWidth(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.serialise(AI_LAYOUT, payloadImages());
    }

    /**
     * Writes this request's twenty-one payload fields into an existing {@code 01 COTRN2AI} work area,
     * leaving the prefix and metadata spans as the area already holds them.
     *
     * @param record the work area, exactly {@value #AI_GROUP_LENGTH} bytes wide
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@value #AI_GROUP_LENGTH} bytes wide
     */
    public void writeInto(FixedWidthRecord record) {
        requireGroupWidth(record);
        for (ScreenField field : ScreenField.values()) {
            record.writeSpan(AI_LAYOUT.span(field.inputItem()), imageOf(field));
        }
    }

    /**
     * Reads a {@value #AI_GROUP_LENGTH}-byte {@code 01 COTRN2AI} image back into a request.
     *
     * <p>Every field is returned untrimmed at its full declared width, so a
     * {@link #toFixedWidth(Charset)} followed by this method yields a request equal to the original
     * wherever the original's fields were already exactly their declared widths.
     *
     * @param image   exactly {@value #AI_GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return the decoded request, with a fresh {@link NavigationContext} and {@link Ct02Info} since the
     *         group image carries neither
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #AI_GROUP_LENGTH} bytes
     */
    public static TransactionViewRequest fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "A COTRN2AI image is required");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        Map<String, String> values = codec.deserialise(AI_LAYOUT, image);
        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.setPayloadValue(field, values.get(field.inputItem()));
        }
        return request;
    }

    /**
     * Reads this request's twenty-one payload fields out of an existing {@code 01 COTRN2AI} work area.
     *
     * @param record the work area, exactly {@value #AI_GROUP_LENGTH} bytes wide
     * @return the decoded request, never {@code null}
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@value #AI_GROUP_LENGTH} bytes wide
     */
    public static TransactionViewRequest readFrom(FixedWidthRecord record) {
        requireGroupWidth(record);
        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.setPayloadValue(field, record.readSpan(AI_LAYOUT.span(field.inputItem())));
        }
        return request;
    }

    /**
     * Guards that a work area really is a {@code 01 COTRN2AI} group.
     *
     * @param record the work area to check
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if its width is not {@value #AI_GROUP_LENGTH}
     */
    private static void requireGroupWidth(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A COTRN2AI work area is required");
        if (record.recordLength() != AI_GROUP_LENGTH) {
            throw new IllegalArgumentException("A COTRN2AI work area is " + AI_GROUP_LENGTH
                    + " byte(s) wide but the supplied area is " + record.recordLength()
                    + "; the group is a 12-byte TIOAPFX prefix plus " + PAYLOAD_FIELD_COUNT
                    + " fields of " + METADATA_PREFIX_LENGTH + " metadata bytes each plus "
                    + PAYLOAD_WIDTH_TOTAL + " payload bytes");
        }
    }

    // =================================================================================================
    // Value semantics. All twenty-one fields plus both carried state objects take part, and the metadata
    // does too, because a cursor request is part of what a request means.
    // =================================================================================================

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionViewRequest candidate)) {
            return false;
        }
        for (ScreenField field : ScreenField.values()) {
            if (!Objects.equals(payloadValue(field), candidate.payloadValue(field))) {
                return false;
            }
            if (!Objects.equals(metadata(field), candidate.metadata(field))) {
                return false;
            }
        }
        return Objects.equals(navigationContext, candidate.navigationContext)
                && Objects.equals(ct02Info, candidate.ct02Info)
                && Objects.equals(aid, candidate.aid);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(navigationContext, ct02Info, aid);
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + Objects.hashCode(payloadValue(field));
            result = 31 * result + Objects.hashCode(metadata(field));
        }
        return result;
    }

    /**
     * A diagnostic rendering keyed by the verbatim {@code xxxI} item names.
     *
     * <p>Every value is reported in full. {@code CARDNINI}, {@code ACTIDINI} and {@code MIDI} are
     * <strong>not</strong> masked, abbreviated or omitted: the legacy screen handles them in the clear,
     * this is a like-for-like migration, and a redacted diagnostic would hide exactly the field a parity
     * failure is most likely to concern.
     *
     * @return the rendering, never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendering = new StringBuilder("TransactionViewRequest[")
                .append(TRANSACTION_ID).append('/').append(PROGRAM_NAME)
                .append('/').append(SYMBOLIC_MAP_INPUT_GROUP);
        for (ScreenField field : ScreenField.values()) {
            rendering.append(", ").append(field.inputItem()).append('=')
                    .append(SensitiveDiagnostics.render(disclosureOf(field), payloadValue(field)));
            if (isCursorRequested(field)) {
                rendering.append(" (cursor)");
            }
        }
        return rendering.append(", ").append(AID_FIELD).append('=').append(aid)
                .append(", navigationContext=").append(navigationContext)
                .append(", ct02Info=").append(ct02Info)
                .append(']')
                .toString();
    }

    /**
     * How much of each screen field a diagnostic rendering may disclose.
     *
     * <p>Named per field rather than pattern-matched, because a symbolic map is a closed set taken
     * straight from {@code app/cpy-bms/} and can therefore be enumerated exactly. Anything not named here
     * is screen furniture - a title, a date, a status code, a message - and renders as stored, which is
     * what a parity failure has to be read from.
     *
     * @param field the screen field
     * @return its classification, never {@code null}
     */
    private static SensitiveDiagnostics.Disclosure disclosureOf(ScreenField field) {
        return switch (field) {
            case ACTIDIN -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case CARDNIN -> SensitiveDiagnostics.Disclosure.PAN;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

}
