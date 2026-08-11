package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Inbound REST payload for CSD transaction {@code CT01}, program {@code COTRN01C}, mapset
 * {@code COTRN01}, map {@code COTRN1A}.
 *
 * <p>Every payload member below is a 1:1 projection of one {@code xxxI} item of
 * {@code 01 COTRN1AI} in {@code app/cpy-bms/COTRN01.CPY} (the group opens at line 17), and each of
 * those items pairs with exactly one name-labelled {@code DFHMDF} definition in
 * {@code app/bms/COTRN01.bms}. There are {@value #PAYLOAD_FIELD_COUNT} of them, no more and no
 * fewer.
 *
 * <h2>&#9888; The class name contradicts the program it models - read this before changing anything</h2>
 *
 * <p>This type is named {@code TransactionAdd…}, but {@code COTRN01C} <strong>views</strong> a
 * transaction. It does not add one. Three independent sources agree:
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN01C.cbl} line 5 states {@code Function : View a Transaction from
 *       TRANSACT file}.</li>
 *   <li>{@code app/bms/COTRN01.bms} line 2 is titled {@code CardDemo - Transaction View}.</li>
 *   <li>{@code README.md} lines 213-231 document {@code CT01} as "Transaction View" and {@code CT02}
 *       as "Transaction Add" - the opposite of the class names assigned to them.</li>
 * </ul>
 *
 * <p>The field shape settles it beyond argument. Of the {@value #PAYLOAD_FIELD_COUNT} fields on this
 * map, <strong>exactly one</strong> is input-capable: {@code TRNIDIN}, declared
 * {@code ATTRB=(FSET,IC,NORM,UNPROT)} at {@code app/bms/COTRN01.bms} line 85. The other twenty are
 * output-only. A screen with a single unprotected key field and twenty display fields is a
 * lookup-then-display screen. For contrast, the sibling {@code COTRN02} map carries
 * {@code ACTIDIN}, {@code CARDNIN} and {@code CONFIRM} across fourteen unprotected fields, which is
 * what a genuine data-entry screen looks like.
 *
 * <p>The Agent Action Plan calls this "the highest-risk naming ambiguity in the plan"
 * (&sect;0.1.8, Conflict Set 1) and tracks it as risk <strong>R-B</strong> (&sect;0.9.12). The
 * binding resolution is transformation rule <strong>R1</strong>: <em>the name comes from the
 * specification, the field set and the behaviour come from the paired copybook and program.</em>
 * Practice B4 requires the conflict be documented rather than silently corrected, and B12 requires
 * it be surfaced rather than absorbed - hence this notice.
 *
 * <p><strong>Consequently: do not add "add"-style fields to make the field set agree with the class
 * name.</strong> There is no confirm field here, no {@code ACTIDIN} and no {@code CARDNIN}; those
 * belong to the {@code COTRN02} map and to {@code TransactionViewResponse}'s pair. Adding one would
 * break the field-for-field parity gate G9 and would fabricate a screen that does not exist.
 *
 * <h2>Why all twenty-one fields belong on an <em>inbound</em> payload</h2>
 *
 * <p>Twenty of the twenty-one fields are output-only, so it is reasonable to ask why an inbound
 * request carries them. Two reasons, and both are mechanical rather than stylistic:
 *
 * <ol>
 *   <li><strong>{@code FSET} returns them.</strong> The header fields carry {@code FSET} in their
 *       {@code ATTRB} list, so CICS includes them in the inbound datastream on
 *       {@code RECEIVE MAP}. That is precisely why the symbolic map declares an {@code xxxI} item
 *       for every field rather than only for the unprotected one.</li>
 *   <li><strong>{@code xxxI} and {@code xxxO} are the same bytes.</strong>
 *       {@code 01 COTRN1AO REDEFINES COTRN1AI} at line 145 of the copybook, and both views spend
 *       exactly {@value #FIELD_METADATA_LENGTH} prefix bytes per field - the {@code AI} view as
 *       {@code 2 + 1 + 4} and the {@code AO} view as {@code 3 + 1 + 1 + 1 + 1} - so each field's
 *       {@code I} item and {@code O} item sit at an <em>identical</em> offset. They are storage
 *       aliases, not distinct fields. {@code COTRN01C} writes through both: fifty references go via
 *       {@code COTRN1AI} and ten via {@code COTRN1AO}, and it is the header and error fields that
 *       travel through the {@code O} alias (lines 217 and 247-262) while the transaction detail
 *       fields travel through the {@code I} alias (lines 178-190).</li>
 * </ol>
 *
 * <p>So the Request/Response split is a <em>directional projection convention</em> layered over one
 * COBOL buffer, not two different buffers. This type and {@code TransactionAddResponse} are
 * therefore field-identical by construction, and round-tripping between them must be lossless.
 * Never treat an {@code xxxI} item as read-only.
 *
 * <h2>The width table - the totals are gate-level, not decorative</h2>
 *
 * <p>Every BMS {@code LENGTH=} was compared against its {@code xxxI} {@code PICTURE} and all
 * {@value #PAYLOAD_FIELD_COUNT} agree. The declared widths sum to
 * {@value #PAYLOAD_TOTAL_LENGTH}, and the whole {@code COTRN1AI} storage image is
 * {@value #TIOA_PREFIX_LENGTH} + {@value #PAYLOAD_FIELD_COUNT}&nbsp;&times;&nbsp;{@value
 * #FIELD_METADATA_LENGTH} + {@value #PAYLOAD_TOTAL_LENGTH} = {@value #SYMBOLIC_MAP_LENGTH} bytes.
 * Those two numbers are not merely documented here: a static initialiser sums the individual width
 * constants and re-derives the image length from the last field's offset, so a single mistyped width
 * fails at class-load rather than surfacing later as a one-byte parity diff.
 *
 * <table border="1">
 *   <caption>The {@value #PAYLOAD_FIELD_COUNT} payload fields in copybook declaration order</caption>
 *   <tr><th>#</th><th>{@code DFHMDF} label</th><th>{@code xxxI} item</th><th>Width</th>
 *       <th>Offset</th><th>Role</th></tr>
 *   <tr><td>1</td><td>{@code TRNNAME}</td><td>{@code TRNNAMEI}</td>
 *       <td>{@value #TRNNAME_LENGTH}</td><td>{@value #TRNNAME_OFFSET}</td><td>header</td></tr>
 *   <tr><td>2</td><td>{@code TITLE01}</td><td>{@code TITLE01I}</td>
 *       <td>{@value #TITLE01_LENGTH}</td><td>{@value #TITLE01_OFFSET}</td><td>header</td></tr>
 *   <tr><td>3</td><td>{@code CURDATE}</td><td>{@code CURDATEI}</td>
 *       <td>{@value #CURDATE_LENGTH}</td><td>{@value #CURDATE_OFFSET}</td><td>header</td></tr>
 *   <tr><td>4</td><td>{@code PGMNAME}</td><td>{@code PGMNAMEI}</td>
 *       <td>{@value #PGMNAME_LENGTH}</td><td>{@value #PGMNAME_OFFSET}</td><td>header</td></tr>
 *   <tr><td>5</td><td>{@code TITLE02}</td><td>{@code TITLE02I}</td>
 *       <td>{@value #TITLE02_LENGTH}</td><td>{@value #TITLE02_OFFSET}</td><td>header</td></tr>
 *   <tr><td>6</td><td>{@code CURTIME}</td><td>{@code CURTIMEI}</td>
 *       <td>{@value #CURTIME_LENGTH}</td><td>{@value #CURTIME_OFFSET}</td><td>header</td></tr>
 *   <tr><td>7</td><td>{@code TRNIDIN}</td><td>{@code TRNIDINI}</td>
 *       <td>{@value #TRNIDIN_LENGTH}</td><td>{@value #TRNIDIN_OFFSET}</td>
 *       <td><strong>the only {@code UNPROT} field - the lookup key</strong></td></tr>
 *   <tr><td>8</td><td>{@code TRNID}</td><td>{@code TRNIDI}</td>
 *       <td>{@value #TRNID_LENGTH}</td><td>{@value #TRNID_OFFSET}</td>
 *       <td>displayed transaction id</td></tr>
 *   <tr><td>9</td><td>{@code CARDNUM}</td><td>{@code CARDNUMI}</td>
 *       <td>{@value #CARDNUM_LENGTH}</td><td>{@value #CARDNUM_OFFSET}</td>
 *       <td>never masked - see below</td></tr>
 *   <tr><td>10</td><td>{@code TTYPCD}</td><td>{@code TTYPCDI}</td>
 *       <td>{@value #TTYPCD_LENGTH}</td><td>{@value #TTYPCD_OFFSET}</td><td>type code</td></tr>
 *   <tr><td>11</td><td>{@code TCATCD}</td><td>{@code TCATCDI}</td>
 *       <td>{@value #TCATCD_LENGTH}</td><td>{@value #TCATCD_OFFSET}</td><td>category code</td></tr>
 *   <tr><td>12</td><td>{@code TRNSRC}</td><td>{@code TRNSRCI}</td>
 *       <td>{@value #TRNSRC_LENGTH}</td><td>{@value #TRNSRC_OFFSET}</td><td>source</td></tr>
 *   <tr><td>13</td><td>{@code TDESC}</td><td>{@code TDESCI}</td>
 *       <td>{@value #TDESC_LENGTH}</td><td>{@value #TDESC_OFFSET}</td><td>description</td></tr>
 *   <tr><td>14</td><td>{@code TRNAMT}</td><td>{@code TRNAMTI}</td>
 *       <td>{@value #TRNAMT_LENGTH}</td><td>{@value #TRNAMT_OFFSET}</td>
 *       <td><strong>edited amount - see the numeric ruling below</strong></td></tr>
 *   <tr><td>15</td><td>{@code TORIGDT}</td><td>{@code TORIGDTI}</td>
 *       <td>{@value #TORIGDT_LENGTH}</td><td>{@value #TORIGDT_OFFSET}</td><td>origin timestamp</td></tr>
 *   <tr><td>16</td><td>{@code TPROCDT}</td><td>{@code TPROCDTI}</td>
 *       <td>{@value #TPROCDT_LENGTH}</td><td>{@value #TPROCDT_OFFSET}</td><td>process timestamp</td></tr>
 *   <tr><td>17</td><td>{@code MID}</td><td>{@code MIDI}</td>
 *       <td>{@value #MID_LENGTH}</td><td>{@value #MID_OFFSET}</td>
 *       <td>merchant id - never masked</td></tr>
 *   <tr><td>18</td><td>{@code MNAME}</td><td>{@code MNAMEI}</td>
 *       <td>{@value #MNAME_LENGTH}</td><td>{@value #MNAME_OFFSET}</td><td>merchant name</td></tr>
 *   <tr><td>19</td><td>{@code MCITY}</td><td>{@code MCITYI}</td>
 *       <td>{@value #MCITY_LENGTH}</td><td>{@value #MCITY_OFFSET}</td><td>merchant city</td></tr>
 *   <tr><td>20</td><td>{@code MZIP}</td><td>{@code MZIPI}</td>
 *       <td>{@value #MZIP_LENGTH}</td><td>{@value #MZIP_OFFSET}</td><td>merchant zip</td></tr>
 *   <tr><td>21</td><td>{@code ERRMSG}</td><td>{@code ERRMSGI}</td>
 *       <td>{@value #ERRMSG_LENGTH}</td><td>{@value #ERRMSG_OFFSET}</td><td>error line</td></tr>
 *   <tr><td colspan="3"><strong>total payload width</strong></td>
 *       <td><strong>{@value #PAYLOAD_TOTAL_LENGTH}</strong></td><td></td><td></td></tr>
 * </table>
 *
 * <h2>Numeric typing: every payload member is a {@code String}</h2>
 *
 * <p>There is no {@code BigDecimal}, no {@code int}, and above all no {@code double} or
 * {@code float} among the payload members, and this class performs no arithmetic at all. That is
 * what satisfies gates G22, G23 and G24 here.
 *
 * <p>{@code TRNAMT} is the field that invites a mistake. The stored record field
 * {@code TRAN-AMT} in {@code app/cpy/CVTRA05Y.cpy} line 10 is {@code PIC S9(09)V99}, eleven bytes
 * of zoned decimal - but the <em>screen</em> field is {@code PIC X(12)}, and it carries an
 * <strong>edited</strong> amount rather than the raw record form. The evidence is direct:
 * {@code COTRN01C} line 49 declares {@code 05 WS-TRAN-AMT PIC +99999999.99}, which is one sign
 * position plus eight integer digits plus a decimal point plus two fraction digits - exactly twelve
 * characters - and line 183 moves that field straight into {@code TRNAMTI OF COTRN1AI}. So the
 * twelve-character alphanumeric image <em>is</em> the contract, and gate G9 requires the member's
 * length to trace to the {@code xxxI} {@code PICTURE}, which is alphanumeric.
 *
 * <p>No numeric convenience accessor is offered. Parsing the edit mask into a scaled decimal is the
 * controller's and the service's job, not this payload's: a second member with no {@code DFHMDF}
 * origin would break gate G9, and the fixed-point policy belongs in exactly one place elsewhere in
 * the module.
 *
 * <h2>Metadata is carried but never serialised</h2>
 *
 * <p>Per AAP &sect;0.6.3 the {@code xxxL}, {@code xxxF} and {@code xxxA} items are validation and
 * highlight <em>metadata</em>, not payload. They are modelled here as {@link ScreenFieldMetadata}
 * carriers reachable through {@link #getMetadata(String)} and excluded from the JSON payload.
 *
 * <p>The {@code xxxL} length item is <strong>written, not merely read</strong>.
 * {@code COTRN01C} issues {@code MOVE -1 TO TRNIDINL OF COTRN1AI} at six sites - lines 102, 151,
 * 154, 287, 294 and 311 - which is the CICS idiom for "place the cursor in this field". It is no
 * accident that all six target {@code TRNIDIN}: that is the one field declared {@code IC}. The
 * carrier is therefore a <em>signed</em> {@code short}, because {@code COMP PIC S9(4)} is a signed
 * binary halfword and {@code -1} is a value it must be able to hold. It is never unsigned and never
 * clamped at zero, and {@link #requestCursorAt(String)} names the idiom.
 *
 * <h2>Security posture is inherited, not improved</h2>
 *
 * <p>{@code CARDNUM} and {@code MID} are exposed at their full declared widths with no masking, no
 * truncation, no redaction and no {@code @JsonIgnore}. The legacy screen displays both in the clear,
 * and practice B6 forbids changing the security posture in either direction: masking them would be
 * a behaviour change, which is exactly what gate G41 and the like-for-like migration constraint
 * rule out. The plaintext exposure is an inherited property of the legacy design, recorded here so
 * it stays visible rather than buried.
 *
 * <h2>Statelessness</h2>
 *
 * <p>Rule R6 and gate G37: there is no {@code HttpSession}, no {@code @SessionAttributes}, no
 * server-side conversation state and no static cache anywhere in this type. The whole CICS
 * pseudo-conversation travels in the payload - the {@value NavigationContext#COMMAREA_LENGTH}-byte
 * {@link NavigationContext} communication area, the {@value Ct01Info#RECORD_LENGTH}-byte
 * {@link Ct01Info} extension that {@code COTRN01C} appends to it, and the {@code EIBAID} attention
 * identifier. The passed communication area is therefore
 * {@value NavigationContext#COMMAREA_LENGTH} + {@value Ct01Info#RECORD_LENGTH} =
 * {@value #COMMAREA_TOTAL_LENGTH} bytes.
 *
 * <p>{@code EXEC CICS XCTL} is deliberately <em>not</em> represented here. {@code COTRN01C} line
 * 206 transfers with {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)}, and under gate G40 that becomes a
 * field on the response naming the next target, resolved client-side. It is not a request concern.
 *
 * <h2>Thread safety</h2>
 *
 * <p>This type is a mutable request-scoped carrier and is <strong>not</strong> thread-safe, which is
 * the correct shape for a payload deserialised per request and never shared. It holds no static
 * mutable state (practice B9, gate G53): every static member is {@code final} and every static
 * value is deeply immutable. Instances are freely constructible without Spring (practice B10), so
 * every branch below is reachable from a plain unit test.
 *
 * @see NavigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte {@code COCOM01Y}
 *      communication area, shared by all seventeen online programs and never widened
 * @see FixedWidthCodec the single seam through which every declared width is rendered
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
public final class TransactionAddRequest {

    // =================================================================================================
    // Screen identity. Taken from app/csd/CARDDEMO.CSD - MAPSET(COTRN01) at line 149,
    // PROGRAM(COTRN01C) at line 264, TRANSACTION(CT01) -> PROGRAM(COTRN01C) at lines 429-430 - and
    // from the EXEC CICS SEND/RECEIVE MAP literals in COTRN01C at lines 220-221 and 233-234.
    // =================================================================================================

    /** CSD transaction identifier that reaches {@code COTRN01C}: {@code CT01}. */
    public static final String TRANSACTION_ID = "CT01";

    /** The COBOL program this payload models: {@code COTRN01C}. */
    public static final String PROGRAM_NAME = "COTRN01C";

    /** BMS mapset name, as {@code MAPSET('COTRN01')} at {@code COTRN01C} line 221. */
    public static final String MAPSET_NAME = "COTRN01";

    /** BMS map name, as {@code MAP('COTRN1A')} at {@code COTRN01C} line 220. */
    public static final String MAP_NAME = "COTRN1A";

    /** Symbolic map group projected by this type: {@code 01 COTRN1AI}, copybook line 17. */
    public static final String SYMBOLIC_MAP_GROUP = "COTRN1AI";

    /**
     * The aliasing group that redefines {@link #SYMBOLIC_MAP_GROUP}:
     * {@code 01 COTRN1AO REDEFINES COTRN1AI}, copybook line 145. Recorded because the two groups are
     * the same storage, which is why this request and its paired response are field-identical.
     */
    public static final String SYMBOLIC_MAP_OUTPUT_GROUP = "COTRN1AO";

    // =================================================================================================
    // Symbolic map geometry. Each field occupies FIELD_METADATA_LENGTH bytes of metadata followed by
    // its declared payload width, so the stride is 7 + n. Nothing below is a magic number: every
    // offset is derived from the field before it, exactly as the copybook lays them out, so the chain
    // can be read straight down against app/cpy-bms/COTRN01.CPY.
    // =================================================================================================

    /** {@code 02 FILLER PIC X(12)}, the {@code TIOAPFX=YES} prefix that opens the group. */
    public static final int TIOA_PREFIX_LENGTH = 12;

    /** {@code 02 xxxL COMP PIC S9(4)} - a signed binary halfword, two bytes. */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /** {@code 02 xxxF PICTURE X} - the flag byte, one byte, redefined by {@code xxxA}. */
    public static final int FLAG_ITEM_LENGTH = 1;

    /** {@code 02 FILLER PICTURE X(4)} - the reserved span between the flag byte and the value. */
    public static final int RESERVED_FILLER_LENGTH = 4;

    /**
     * Metadata bytes ahead of every field's value: {@value #LENGTH_ITEM_LENGTH} +
     * {@value #FLAG_ITEM_LENGTH} + {@value #RESERVED_FILLER_LENGTH} =
     * {@value #FIELD_METADATA_LENGTH}.
     *
     * <p>The {@code xxxA} attribute item consumes no byte of its own: it {@code REDEFINES} the flag
     * byte and is an overlay on it. The {@code COTRN1AO} view spends the same seven bytes as
     * {@code 3 + 1 + 1 + 1 + 1}, which is why each field's {@code I} and {@code O} items land on the
     * identical offset.
     */
    public static final int FIELD_METADATA_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /** Number of payload fields: name-labelled {@code DFHMDF} entries and {@code xxxI} items alike. */
    public static final int PAYLOAD_FIELD_COUNT = 21;

    /** Value a length item takes to request the cursor, as {@code MOVE -1 TO TRNIDINL}. */
    public static final short CURSOR_REQUEST = -1;

    /** {@code EIBAID} is a single-byte attention identifier; the {@code DFHAID} tokens are one byte. */
    public static final int AID_LENGTH = 1;

    // =================================================================================================
    // Field names, carried VERBATIM as the symbolic map spells them. These are the keys the parity
    // differ compares field by field and the keys the metadata map is addressed by, so a "tidied up"
    // name would make a real difference invisible. The DFHMDF label is the name without the trailing
    // I - that label is what app/bms/COTRN01.bms declares, and ScreenFieldMetadata derives the
    // companion L, F and A item names from it.
    // =================================================================================================

    /** {@code TRNNAMEI}, copybook line 24; {@code DFHMDF TRNNAME} at BMS line 34, {@code POS=(1,7)}. */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /** {@code TITLE01I}, copybook line 30; {@code DFHMDF TITLE01} at BMS line 38, {@code POS=(1,21)}. */
    public static final String TITLE01_FIELD = "TITLE01I";

    /** {@code CURDATEI}, copybook line 36; {@code DFHMDF CURDATE} at BMS line 47, {@code POS=(1,71)}. */
    public static final String CURDATE_FIELD = "CURDATEI";

    /** {@code PGMNAMEI}, copybook line 42; {@code DFHMDF PGMNAME} at BMS line 57, {@code POS=(2,7)}. */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /** {@code TITLE02I}, copybook line 48; {@code DFHMDF TITLE02} at BMS line 61, {@code POS=(2,21)}. */
    public static final String TITLE02_FIELD = "TITLE02I";

    /** {@code CURTIMEI}, copybook line 54; {@code DFHMDF CURTIME} at BMS line 70, {@code POS=(2,71)}. */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /**
     * {@code TRNIDINI}, copybook line 60; {@code DFHMDF TRNIDIN} at BMS line 85,
     * {@code ATTRB=(FSET,IC,NORM,UNPROT)}, {@code POS=(6,21)}.
     *
     * <p>The only input-capable field on the map, and the only field any of the six
     * {@code MOVE -1} cursor-positioning statements targets.
     */
    public static final String TRNIDIN_FIELD = "TRNIDINI";

    /** {@code TRNIDI}, copybook line 66; {@code DFHMDF TRNID} at BMS line 105, {@code POS=(10,22)}. */
    public static final String TRNID_FIELD = "TRNIDI";

    /**
     * {@code CARDNUMI}, copybook line 72; {@code DFHMDF CARDNUM} at BMS line 118,
     * {@code POS=(10,58)}. Populated from {@code TRAN-CARD-NUM} at {@code COTRN01C} line 179 and
     * exposed unmasked, per practice B6 and gate G41.
     */
    public static final String CARDNUM_FIELD = "CARDNUMI";

    /** {@code TTYPCDI}, copybook line 78; {@code DFHMDF TTYPCD} at BMS line 132, {@code POS=(12,15)}. */
    public static final String TTYPCD_FIELD = "TTYPCDI";

    /** {@code TCATCDI}, copybook line 84; {@code DFHMDF TCATCD}. */
    public static final String TCATCD_FIELD = "TCATCDI";

    /** {@code TRNSRCI}, copybook line 90; {@code DFHMDF TRNSRC}. */
    public static final String TRNSRC_FIELD = "TRNSRCI";

    /** {@code TDESCI}, copybook line 96; {@code DFHMDF TDESC}. */
    public static final String TDESC_FIELD = "TDESCI";

    /**
     * {@code TRNAMTI}, copybook line 102; {@code DFHMDF TRNAMT}.
     *
     * <p>Carries the edited image {@code +99999999.99} declared at {@code COTRN01C} line 49 and moved
     * in at line 183 - twelve characters, not the eleven-byte {@code PIC S9(09)V99} record form.
     */
    public static final String TRNAMT_FIELD = "TRNAMTI";

    /** {@code TORIGDTI}, copybook line 108; {@code DFHMDF TORIGDT}. */
    public static final String TORIGDT_FIELD = "TORIGDTI";

    /** {@code TPROCDTI}, copybook line 114; {@code DFHMDF TPROCDT}. */
    public static final String TPROCDT_FIELD = "TPROCDTI";

    /**
     * {@code MIDI}, copybook line 120; {@code DFHMDF MID}. Merchant id, populated from
     * {@code TRAN-MERCHANT-ID} at {@code COTRN01C} line 187 and exposed unmasked, per B6 and G41.
     */
    public static final String MID_FIELD = "MIDI";

    /** {@code MNAMEI}, copybook line 126; {@code DFHMDF MNAME}. */
    public static final String MNAME_FIELD = "MNAMEI";

    /** {@code MCITYI}, copybook line 132; {@code DFHMDF MCITY}. */
    public static final String MCITY_FIELD = "MCITYI";

    /** {@code MZIPI}, copybook line 138; {@code DFHMDF MZIP}. */
    public static final String MZIP_FIELD = "MZIPI";

    /**
     * {@code ERRMSGI}, copybook line 144; {@code DFHMDF ERRMSG}. Written through the {@code AO} alias
     * as {@code MOVE WS-MESSAGE TO ERRMSGO OF COTRN1AO} at {@code COTRN01C} line 217, and blanked at
     * line 92 - the same bytes either way.
     */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    // =================================================================================================
    // Declared widths. Each is the xxxI PICTURE width, and each was machine-compared against its BMS
    // LENGTH= - all twenty-one agree.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}; {@code LENGTH=4}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}; {@code LENGTH=40}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}; {@code LENGTH=8}, {@code INITIAL='mm/dd/yy'}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}; {@code LENGTH=8}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}; {@code LENGTH=40}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEI PIC X(8)}; {@code LENGTH=8}, {@code INITIAL='hh:mm:ss'}. */
    public static final int CURTIME_LENGTH = 8;

    /** {@code TRNIDINI PIC X(16)}; {@code LENGTH=16}. */
    public static final int TRNIDIN_LENGTH = 16;

    /** {@code TRNIDI PIC X(16)}; {@code LENGTH=16}. */
    public static final int TRNID_LENGTH = 16;

    /** {@code CARDNUMI PIC X(16)}; {@code LENGTH=16}. Never narrowed and never masked. */
    public static final int CARDNUM_LENGTH = 16;

    /** {@code TTYPCDI PIC X(2)}; {@code LENGTH=2}. */
    public static final int TTYPCD_LENGTH = 2;

    /** {@code TCATCDI PIC X(4)}; {@code LENGTH=4}. */
    public static final int TCATCD_LENGTH = 4;

    /** {@code TRNSRCI PIC X(10)}; {@code LENGTH=10}. */
    public static final int TRNSRC_LENGTH = 10;

    /** {@code TDESCI PIC X(60)}; {@code LENGTH=60}. */
    public static final int TDESC_LENGTH = 60;

    /**
     * {@code TRNAMTI PIC X(12)}; {@code LENGTH=12}.
     *
     * <p>Twelve, not eleven. The record field is eleven bytes of {@code PIC S9(09)V99}; the screen
     * field is the twelve-character edit mask {@code +99999999.99}.
     */
    public static final int TRNAMT_LENGTH = 12;

    /** {@code TORIGDTI PIC X(10)}; {@code LENGTH=10}. */
    public static final int TORIGDT_LENGTH = 10;

    /** {@code TPROCDTI PIC X(10)}; {@code LENGTH=10}. */
    public static final int TPROCDT_LENGTH = 10;

    /** {@code MIDI PIC X(9)}; {@code LENGTH=9}. Never narrowed and never masked. */
    public static final int MID_LENGTH = 9;

    /** {@code MNAMEI PIC X(30)}; {@code LENGTH=30}. */
    public static final int MNAME_LENGTH = 30;

    /** {@code MCITYI PIC X(25)}; {@code LENGTH=25}. */
    public static final int MCITY_LENGTH = 25;

    /** {@code MZIPI PIC X(10)}; {@code LENGTH=10}. */
    public static final int MZIP_LENGTH = 10;

    /** {@code ERRMSGI PIC X(78)}; {@code LENGTH=78}. */
    public static final int ERRMSG_LENGTH = 78;

    // =================================================================================================
    // Absolute offsets within the COTRN1AI storage image. Two per field: the offset of its metadata
    // group, and the offset of its value FIELD_METADATA_LENGTH bytes later. Every one is derived from
    // the previous field, so the chain reads straight down against the copybook and no offset can
    // drift independently of the width above it.
    // =================================================================================================

    /** Offset of {@code TRNNAMEL}, immediately after the {@value #TIOA_PREFIX_LENGTH}-byte prefix. */
    public static final int TRNNAME_GROUP_OFFSET = TIOA_PREFIX_LENGTH;
    /** Offset of {@code TRNNAMEI}. */
    public static final int TRNNAME_OFFSET = TRNNAME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TITLE01L}. */
    public static final int TITLE01_GROUP_OFFSET = TRNNAME_OFFSET + TRNNAME_LENGTH;
    /** Offset of {@code TITLE01I}. */
    public static final int TITLE01_OFFSET = TITLE01_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code CURDATEL}. */
    public static final int CURDATE_GROUP_OFFSET = TITLE01_OFFSET + TITLE01_LENGTH;
    /** Offset of {@code CURDATEI}. */
    public static final int CURDATE_OFFSET = CURDATE_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code PGMNAMEL}. */
    public static final int PGMNAME_GROUP_OFFSET = CURDATE_OFFSET + CURDATE_LENGTH;
    /** Offset of {@code PGMNAMEI}. */
    public static final int PGMNAME_OFFSET = PGMNAME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TITLE02L}. */
    public static final int TITLE02_GROUP_OFFSET = PGMNAME_OFFSET + PGMNAME_LENGTH;
    /** Offset of {@code TITLE02I}. */
    public static final int TITLE02_OFFSET = TITLE02_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code CURTIMEL}. */
    public static final int CURTIME_GROUP_OFFSET = TITLE02_OFFSET + TITLE02_LENGTH;
    /** Offset of {@code CURTIMEI}. */
    public static final int CURTIME_OFFSET = CURTIME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TRNIDINL} - the halfword all six {@code MOVE -1} statements write. */
    public static final int TRNIDIN_GROUP_OFFSET = CURTIME_OFFSET + CURTIME_LENGTH;
    /** Offset of {@code TRNIDINI}. */
    public static final int TRNIDIN_OFFSET = TRNIDIN_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TRNIDL}. */
    public static final int TRNID_GROUP_OFFSET = TRNIDIN_OFFSET + TRNIDIN_LENGTH;
    /** Offset of {@code TRNIDI}. */
    public static final int TRNID_OFFSET = TRNID_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code CARDNUML}. */
    public static final int CARDNUM_GROUP_OFFSET = TRNID_OFFSET + TRNID_LENGTH;
    /** Offset of {@code CARDNUMI}. */
    public static final int CARDNUM_OFFSET = CARDNUM_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TTYPCDL}. */
    public static final int TTYPCD_GROUP_OFFSET = CARDNUM_OFFSET + CARDNUM_LENGTH;
    /** Offset of {@code TTYPCDI}. */
    public static final int TTYPCD_OFFSET = TTYPCD_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TCATCDL}. */
    public static final int TCATCD_GROUP_OFFSET = TTYPCD_OFFSET + TTYPCD_LENGTH;
    /** Offset of {@code TCATCDI}. */
    public static final int TCATCD_OFFSET = TCATCD_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TRNSRCL}. */
    public static final int TRNSRC_GROUP_OFFSET = TCATCD_OFFSET + TCATCD_LENGTH;
    /** Offset of {@code TRNSRCI}. */
    public static final int TRNSRC_OFFSET = TRNSRC_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TDESCL}. */
    public static final int TDESC_GROUP_OFFSET = TRNSRC_OFFSET + TRNSRC_LENGTH;
    /** Offset of {@code TDESCI}. */
    public static final int TDESC_OFFSET = TDESC_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TRNAMTL}. */
    public static final int TRNAMT_GROUP_OFFSET = TDESC_OFFSET + TDESC_LENGTH;
    /** Offset of {@code TRNAMTI}. */
    public static final int TRNAMT_OFFSET = TRNAMT_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TORIGDTL}. */
    public static final int TORIGDT_GROUP_OFFSET = TRNAMT_OFFSET + TRNAMT_LENGTH;
    /** Offset of {@code TORIGDTI}. */
    public static final int TORIGDT_OFFSET = TORIGDT_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code TPROCDTL}. */
    public static final int TPROCDT_GROUP_OFFSET = TORIGDT_OFFSET + TORIGDT_LENGTH;
    /** Offset of {@code TPROCDTI}. */
    public static final int TPROCDT_OFFSET = TPROCDT_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code MIDL}. */
    public static final int MID_GROUP_OFFSET = TPROCDT_OFFSET + TPROCDT_LENGTH;
    /** Offset of {@code MIDI}. */
    public static final int MID_OFFSET = MID_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code MNAMEL}. */
    public static final int MNAME_GROUP_OFFSET = MID_OFFSET + MID_LENGTH;
    /** Offset of {@code MNAMEI}. */
    public static final int MNAME_OFFSET = MNAME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code MCITYL}. */
    public static final int MCITY_GROUP_OFFSET = MNAME_OFFSET + MNAME_LENGTH;
    /** Offset of {@code MCITYI}. */
    public static final int MCITY_OFFSET = MCITY_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code MZIPL}. */
    public static final int MZIP_GROUP_OFFSET = MCITY_OFFSET + MCITY_LENGTH;
    /** Offset of {@code MZIPI}. */
    public static final int MZIP_OFFSET = MZIP_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    /** Offset of {@code ERRMSGL}. */
    public static final int ERRMSG_GROUP_OFFSET = MZIP_OFFSET + MZIP_LENGTH;
    /** Offset of {@code ERRMSGI}, the last field in the group. */
    public static final int ERRMSG_OFFSET = ERRMSG_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    // =================================================================================================
    // The two gate-level totals, and the byte width of the communication area COTRN01C actually
    // passes. Both totals are re-derived and cross-checked in the static initialiser below.
    // =================================================================================================

    /** Sum of the {@value #PAYLOAD_FIELD_COUNT} declared widths: {@value #PAYLOAD_TOTAL_LENGTH}. */
    public static final int PAYLOAD_TOTAL_LENGTH = 416;

    /**
     * Full width of the {@code 01 COTRN1AI} storage image:
     * {@value #TIOA_PREFIX_LENGTH} + {@value #PAYLOAD_FIELD_COUNT}&nbsp;&times;&nbsp;{@value
     * #FIELD_METADATA_LENGTH} + {@value #PAYLOAD_TOTAL_LENGTH} = {@value #SYMBOLIC_MAP_LENGTH}.
     */
    public static final int SYMBOLIC_MAP_LENGTH = 575;

    /**
     * Width of the communication area {@code COTRN01C} passes:
     * {@value NavigationContext#COMMAREA_LENGTH} bytes of {@code CARDDEMO-COMMAREA} plus the
     * {@value Ct01Info#RECORD_LENGTH}-byte {@code CDEMO-CT01-INFO} extension the program appends at
     * lines 53-61 = {@value #COMMAREA_TOTAL_LENGTH}.
     */
    public static final int COMMAREA_TOTAL_LENGTH =
            NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH;

    /**
     * The {@value #PAYLOAD_FIELD_COUNT} field names in copybook declaration order.
     *
     * <p>Immutable, so publishing it introduces no mutable static state. It is public because the
     * parity harness iterates it to diff a fingerprint field by field, and because declaration order
     * is itself part of the contract.
     */
    public static final List<String> PAYLOAD_FIELD_NAMES = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, TRNIDIN_FIELD, TRNID_FIELD, CARDNUM_FIELD, TTYPCD_FIELD,
            TCATCD_FIELD, TRNSRC_FIELD, TDESC_FIELD, TRNAMT_FIELD, TORIGDT_FIELD,
            TPROCDT_FIELD, MID_FIELD, MNAME_FIELD, MCITY_FIELD, MZIP_FIELD, ERRMSG_FIELD);

    /** Declared width of each field in {@link #PAYLOAD_FIELD_NAMES}, positionally aligned with it. */
    private static final List<Integer> PAYLOAD_FIELD_WIDTHS = List.of(
            TRNNAME_LENGTH, TITLE01_LENGTH, CURDATE_LENGTH, PGMNAME_LENGTH, TITLE02_LENGTH,
            CURTIME_LENGTH, TRNIDIN_LENGTH, TRNID_LENGTH, CARDNUM_LENGTH, TTYPCD_LENGTH,
            TCATCD_LENGTH, TRNSRC_LENGTH, TDESC_LENGTH, TRNAMT_LENGTH, TORIGDT_LENGTH,
            TPROCDT_LENGTH, MID_LENGTH, MNAME_LENGTH, MCITY_LENGTH, MZIP_LENGTH, ERRMSG_LENGTH);

    /** Declared width by field name, immutable, for {@link #declaredLengthOf(String)}. */
    private static final Map<String, Integer> DECLARED_LENGTHS = buildDeclaredLengths();

    /**
     * Verifies the geometry the moment the class loads.
     *
     * <p>Three independent cross-checks, each of which catches a different mistake. The field name
     * list and the width list must be the same length and both must hold exactly
     * {@value #PAYLOAD_FIELD_COUNT} entries, so a field added to one list and forgotten in the other
     * fails here. The individual widths must sum to {@link #PAYLOAD_TOTAL_LENGTH}, so a single
     * mistyped width fails here. And the image length must be re-derivable two ways - from the last
     * field's offset plus its width, and from the prefix plus the per-field metadata plus the payload
     * total - so a broken offset chain fails here too.
     *
     * <p>Failing at class-load is the point. The alternative is a one-byte discrepancy that surfaces
     * much later as an unexplained parity diff, at which stage the offending constant is far harder
     * to identify than it is right here.
     */
    static {
        verifyGeometry(PAYLOAD_FIELD_NAMES.size(),
                PAYLOAD_FIELD_WIDTHS.size(),
                sumOf(PAYLOAD_FIELD_WIDTHS),
                ERRMSG_OFFSET + ERRMSG_LENGTH,
                TIOA_PREFIX_LENGTH + PAYLOAD_FIELD_COUNT * FIELD_METADATA_LENGTH
                        + PAYLOAD_TOTAL_LENGTH);
    }

    /**
     * Adds up declared widths.
     *
     * @param widths the widths to total, not {@code null} and containing no {@code null}
     * @return their sum
     * @throws NullPointerException if {@code widths} is {@code null} or holds a {@code null}
     */
    static int sumOf(List<Integer> widths) {
        Objects.requireNonNull(widths, "A width list is required to total it");
        int total = 0;
        for (Integer width : widths) {
            total += Objects.requireNonNull(width, "A declared width cannot be null");
        }
        return total;
    }

    /**
     * The geometry self-check, taking its inputs as parameters rather than reading the constants
     * directly.
     *
     * <p>That indirection is deliberate and is the difference between an invariant that is
     * <em>enforced</em> and one that is also <em>verified</em>. Reading the constants inline would
     * make each guard below structurally unreachable in a correct build - permanently uncovered
     * branches that no test could ever drive, and an assertion nobody could prove actually fires.
     * Passing the values in lets the static initialiser above supply the real ones while a unit test
     * supplies deliberately wrong ones, so every branch is exercised and each failure message is
     * demonstrated rather than assumed.
     *
     * <p>Three independent cross-checks, each catching a different mistake. The name list and the
     * width list must be the same length and both must hold exactly {@value #PAYLOAD_FIELD_COUNT}
     * entries, so a field added to one list and forgotten in the other fails here. The widths must
     * sum to {@link #PAYLOAD_TOTAL_LENGTH}, so a single mistyped width fails here. And the image
     * length must be re-derivable two independent ways - from the last field's offset plus its width,
     * and from the prefix plus the per-field metadata plus the payload total - so a broken offset
     * chain fails here too.
     *
     * @param nameCount      how many field names are declared
     * @param widthCount     how many declared widths are listed
     * @param widthSum       the total of those widths
     * @param offsetChainEnd where the offset chain ends: last field offset plus its width
     * @param componentSum   the image length re-derived from prefix, metadata and payload total
     * @throws IllegalStateException if any cross-check fails, naming the disagreement
     */
    static void verifyGeometry(int nameCount, int widthCount, int widthSum, int offsetChainEnd,
                               int componentSum) {
        if (nameCount != PAYLOAD_FIELD_COUNT || widthCount != PAYLOAD_FIELD_COUNT) {
            throw new IllegalStateException(SYMBOLIC_MAP_GROUP + " declares "
                    + PAYLOAD_FIELD_COUNT + " payload fields, but this type lists " + nameCount
                    + " name(s) and " + widthCount + " width(s); the two lists are positionally "
                    + "aligned and must both match app/cpy-bms/COTRN01.CPY");
        }
        if (widthSum != PAYLOAD_TOTAL_LENGTH) {
            throw new IllegalStateException("The " + PAYLOAD_FIELD_COUNT + " declared widths of "
                    + SYMBOLIC_MAP_GROUP + " sum to " + widthSum + ", not " + PAYLOAD_TOTAL_LENGTH
                    + "; one width disagrees with its PICTURE clause in app/cpy-bms/COTRN01.CPY or "
                    + "its LENGTH= in app/bms/COTRN01.bms");
        }
        if (offsetChainEnd != SYMBOLIC_MAP_LENGTH || componentSum != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalStateException(SYMBOLIC_MAP_GROUP + " must occupy "
                    + SYMBOLIC_MAP_LENGTH + " bytes, but the offset chain ends at " + offsetChainEnd
                    + " and the component sum is " + componentSum + "; the offset chain and the "
                    + "width table have diverged");
        }
    }

    /**
     * Builds the immutable name-to-width index backing {@link #declaredLengthOf(String)}.
     *
     * <p>Reads the two published lists rather than restating the widths a third time. Both are
     * {@code static final} and are declared above this call site, so both are fully initialised by
     * the time it runs, and there is exactly one place a width is written down.
     *
     * @return an unmodifiable name-to-width map covering every payload field
     */
    private static Map<String, Integer> buildDeclaredLengths() {
        Map<String, Integer> lengths = new LinkedHashMap<>();
        for (int i = 0; i < PAYLOAD_FIELD_NAMES.size(); i++) {
            lengths.put(PAYLOAD_FIELD_NAMES.get(i), PAYLOAD_FIELD_WIDTHS.get(i));
        }
        return Collections.unmodifiableMap(lengths);
    }

    /**
     * The COBOL figurative constant {@code SPACES} sized to a field: a run of {@code width} spaces.
     *
     * <p>This is the unconditional {@code MOVE SPACES} shape and deliberately <em>not</em> the
     * alphanumeric {@code MOVE} rule. That rule - pad a shorter sending value, truncate a longer one -
     * belongs to {@link FixedWidthCodec#movePicX(String, int)} alone, so that the direction of any
     * loss is always chosen explicitly at the call site.
     *
     * @param width the field width in characters, zero or more
     * @return a string of exactly {@code width} spaces
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String spaces(int width) {
        if (width < 0) {
            throw new IllegalArgumentException("A field cannot be " + width + " characters wide");
        }
        return " ".repeat(width);
    }

    /**
     * The declared width of a payload field, by its verbatim {@code xxxI} name.
     *
     * @param fieldName one of {@link #PAYLOAD_FIELD_NAMES}, for example {@code "CARDNUMI"}
     * @return the declared {@code PICTURE} width
     * @throws NullPointerException     if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not one of the
     *                                  {@value #PAYLOAD_FIELD_COUNT} fields of this map. An unknown
     *                                  name is rejected rather than defaulted, because a misspelled
     *                                  name that silently returned a plausible width is the hardest
     *                                  kind of parity defect to trace
     */
    public static int declaredLengthOf(String fieldName) {
        Objects.requireNonNull(fieldName, "A field name is required to look up a declared width");
        Integer declared = DECLARED_LENGTHS.get(fieldName);
        if (declared == null) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a field of "
                    + SYMBOLIC_MAP_GROUP + "; the " + PAYLOAD_FIELD_COUNT + " field names are "
                    + PAYLOAD_FIELD_NAMES);
        }
        return declared;
    }

    /**
     * Treats an absent value as COBOL's blank field: a {@code PIC X} item is never null.
     *
     * @param value the value to normalise, possibly {@code null}
     * @return {@code value}, or the empty string when it is {@code null}
     */
    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    // =================================================================================================
    // The 21 payload members. Names are the DFHMDF labels - the xxxI item name less its directional I
    // suffix - because the label is what app/bms/COTRN01.bms declares and what gate G9 traces to. The
    // verbatim xxxI name of each is published as the matching *_FIELD constant above.
    //
    // Every one is a String at its declared width. There is no BigDecimal, int, double or float here:
    // see the numeric ruling in the class Javadoc.
    //
    // Bean Validation is @Size(max=...) and nothing else, deliberately. @Size(max) rejects only an
    // OVER-long value, which BMS physically cannot deliver for a field of declared LENGTH=n, so it can
    // never reject something the mainframe would have accepted. @NotNull, @NotBlank or @Pattern would
    // be STRICTER than the COBOL - COTRN01C performs its own editing and reports its own messages
    // through ERRMSG - and an extra Java rejection is a parity break, not a safety net.
    // =================================================================================================

    @Size(max = TRNNAME_LENGTH)
    private String trnname = spaces(TRNNAME_LENGTH);

    @Size(max = TITLE01_LENGTH)
    private String title01 = spaces(TITLE01_LENGTH);

    @Size(max = CURDATE_LENGTH)
    private String curdate = spaces(CURDATE_LENGTH);

    @Size(max = PGMNAME_LENGTH)
    private String pgmname = spaces(PGMNAME_LENGTH);

    @Size(max = TITLE02_LENGTH)
    private String title02 = spaces(TITLE02_LENGTH);

    @Size(max = CURTIME_LENGTH)
    private String curtime = spaces(CURTIME_LENGTH);

    @Size(max = TRNIDIN_LENGTH)
    private String trnidin = spaces(TRNIDIN_LENGTH);

    @Size(max = TRNID_LENGTH)
    private String trnid = spaces(TRNID_LENGTH);

    @Size(max = CARDNUM_LENGTH)
    private String cardnum = spaces(CARDNUM_LENGTH);

    @Size(max = TTYPCD_LENGTH)
    private String ttypcd = spaces(TTYPCD_LENGTH);

    @Size(max = TCATCD_LENGTH)
    private String tcatcd = spaces(TCATCD_LENGTH);

    @Size(max = TRNSRC_LENGTH)
    private String trnsrc = spaces(TRNSRC_LENGTH);

    @Size(max = TDESC_LENGTH)
    private String tdesc = spaces(TDESC_LENGTH);

    @Size(max = TRNAMT_LENGTH)
    private String trnamt = spaces(TRNAMT_LENGTH);

    @Size(max = TORIGDT_LENGTH)
    private String torigdt = spaces(TORIGDT_LENGTH);

    @Size(max = TPROCDT_LENGTH)
    private String tprocdt = spaces(TPROCDT_LENGTH);

    @Size(max = MID_LENGTH)
    private String mid = spaces(MID_LENGTH);

    @Size(max = MNAME_LENGTH)
    private String mname = spaces(MNAME_LENGTH);

    @Size(max = MCITY_LENGTH)
    private String mcity = spaces(MCITY_LENGTH);

    @Size(max = MZIP_LENGTH)
    private String mzip = spaces(MZIP_LENGTH);

    @Size(max = ERRMSG_LENGTH)
    private String errmsg = spaces(ERRMSG_LENGTH);

    // =================================================================================================
    // CICS conversation state. These are NOT screen fields and are deliberately kept apart from the
    // 21-field DFHMDF projection above so that gate G9's "every payload field traces to a DFHMDF
    // definition" stays literally true of that projection.
    //
    // They are nonetheless part of the payload, because rule R6 and gate G37 require the COMMAREA, the
    // EIBAID-derived key and the ENTER-versus-REENTER context to travel in the request rather than in
    // server-side state. There is no HttpSession here and no static cache.
    // =================================================================================================

    /**
     * {@code 01 CARDDEMO-COMMAREA} from {@code COCOM01Y}, copied by {@code COTRN01C} at line 52.
     * Exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and never widened.
     *
     * <p><strong>{@code null} when no communication area was passed</strong>, and deliberately
     * {@code null} on a freshly constructed request. {@code app/cbl/COTRN01C.cbl:94-104} makes the
     * distinction its first act:
     *
     * <pre>{@code
     * IF EIBCALEN = 0
     *     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
     *     PERFORM RETURN-TO-PREV-SCREEN
     * ELSE
     *     MOVE DFHCOMMAREA(1:EIBCALEN) TO CARDDEMO-COMMAREA
     * }</pre>
     *
     * <p>{@code EIBCALEN = 0} means nothing was passed, and the program abandons the transaction for
     * the sign-on screen without ever reading a context byte. A freshly initialised area is the other
     * branch: it has a length, so the program copies it and goes on to test
     * {@code CDEMO-PGM-REENTER}. Defaulting this field to {@link NavigationContext#empty()} - as it
     * once did - made the first branch unreachable through the API, because every request then
     * carried an area whether or not one was passed. Absence is now carried as absence, and
     * {@link #hasNavigationContext()} is the discriminator.
     */
    private NavigationContext navigationContext;

    /**
     * The {@value Ct01Info#RECORD_LENGTH}-byte {@code CDEMO-CT01-INFO} extension that
     * {@code COTRN01C} appends to the communication area at lines 53-61.
     */
    @Valid
    private Ct01Info ct01Info = new Ct01Info();

    /**
     * The {@code EIBAID} attention identifier the terminal raised.
     *
     * <p>{@code COTRN01C} evaluates it at line 112 and acts on {@code DFHENTER} (line 113),
     * {@code DFHPF3} (115), {@code DFHPF4} (123) and {@code DFHPF5} (125). One byte, because that is
     * what {@code EIBAID} is and what each {@code DFHAID} token holds. Carried as a plain
     * {@code String} token rather than an enum so this payload stays free of any dependency the
     * migration plan does not grant it.
     */
    @Size(max = AID_LENGTH)
    private String aid = spaces(AID_LENGTH);

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} items, one carrier per screen field, keyed by
     * the verbatim {@code xxxI} field name in copybook declaration order.
     *
     * <p>Never serialised: metadata is not payload. The map is created once per instance and is never
     * structurally modified afterwards, so lookups always succeed for every one of the
     * {@value #PAYLOAD_FIELD_COUNT} field names.
     */
    @JsonIgnore
    private final Map<String, ScreenFieldMetadata> metadata;

    // =================================================================================================
    // Construction
    // =================================================================================================

    /**
     * Creates a request in the state a freshly initialised COBOL work area would hold.
     *
     * <p>All {@value #PAYLOAD_FIELD_COUNT} payload fields are blank <em>at their declared widths</em>,
     * not empty and not null: COBOL has no null, and an unset {@code PIC X} field holds spaces. That
     * is also what makes the JSON round trip natural - a field that was never assigned still comes
     * back at its declared width with its padding intact.
     *
     * <p>The metadata carriers are created here too, one per field, so
     * {@link #getMetadata(String)} never returns {@code null} for a valid field name.
     */
    public TransactionAddRequest() {
        Map<String, ScreenFieldMetadata> carriers = new LinkedHashMap<>();
        for (String fieldName : PAYLOAD_FIELD_NAMES) {
            carriers.put(fieldName, new ScreenFieldMetadata(
                    screenNameOf(fieldName), declaredLengthOf(fieldName)));
        }
        this.metadata = carriers;
    }

    /**
     * Deep copy constructor.
     *
     * <p>Copies the nested extension and every metadata carrier rather than sharing them, so a copy
     * can be mutated without disturbing the original. {@link NavigationContext} is an immutable
     * record and is shared safely by reference.
     *
     * @param other the request to copy, not {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public TransactionAddRequest(TransactionAddRequest other) {
        Objects.requireNonNull(other, "A source TransactionAddRequest is required to copy one");
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.trnidin = other.trnidin;
        this.trnid = other.trnid;
        this.cardnum = other.cardnum;
        this.ttypcd = other.ttypcd;
        this.tcatcd = other.tcatcd;
        this.trnsrc = other.trnsrc;
        this.tdesc = other.tdesc;
        this.trnamt = other.trnamt;
        this.torigdt = other.torigdt;
        this.tprocdt = other.tprocdt;
        this.mid = other.mid;
        this.mname = other.mname;
        this.mcity = other.mcity;
        this.mzip = other.mzip;
        this.errmsg = other.errmsg;
        this.navigationContext = other.navigationContext;
        this.ct01Info = new Ct01Info(other.ct01Info);
        this.aid = other.aid;
        Map<String, ScreenFieldMetadata> carriers = new LinkedHashMap<>();
        for (Map.Entry<String, ScreenFieldMetadata> entry : other.metadata.entrySet()) {
            carriers.put(entry.getKey(), new ScreenFieldMetadata(entry.getValue()));
        }
        this.metadata = carriers;
    }

    /**
     * Strips the trailing directional {@code I} from an {@code xxxI} item name to recover the
     * {@code DFHMDF} label - {@code TRNNAMEI} becomes {@code TRNNAME}.
     *
     * <p>Every one of the {@value #PAYLOAD_FIELD_COUNT} names ends in {@code I} by construction, so
     * the guard below can only fire if a name constant is edited into an inconsistent state, and it
     * says so rather than producing a silently wrong label. Package-private for the same reason
     * {@link #verifyGeometry(int, int, int, int, int)} is: a guard that no test can reach is a guard
     * nobody can prove works.
     *
     * @param fieldName a symbolic map value item name, expected to end in {@code I}
     * @return the {@code DFHMDF} label
     * @throws IllegalStateException if {@code fieldName} does not end in {@code I}
     */
    static String screenNameOf(String fieldName) {
        if (!fieldName.endsWith("I")) {
            throw new IllegalStateException("Symbolic map value item '" + fieldName + "' does not "
                    + "end in the directional 'I' suffix, so no DFHMDF label can be derived from it; "
                    + "the field name constants and app/cpy-bms/COTRN01.CPY have diverged");
        }
        return fieldName.substring(0, fieldName.length() - 1);
    }

    // =================================================================================================
    // The 21 payload accessors, in copybook declaration order.
    //
    // Every setter stores what it is given, unchanged. It does not pad, does not truncate and does not
    // reject: COBOL's alphanumeric MOVE rule belongs to FixedWidthCodec.movePicX, which is applied
    // where the declared-width image is rendered, so the direction of any loss is chosen explicitly at
    // that point. @Size(max=...) reports an over-long value as a validation error instead - and if a
    // setter threw on the same input, @Size could never fire and would be dead validation.
    //
    // null is preserved rather than normalised, so a round trip is lossless; the render path treats it
    // as the blank field COBOL would hold.
    // =================================================================================================

    /**
     * The {@code TRNNAMEI} field.
     *
     * @return {@code TRNNAMEI} - the transaction id header, {@value #TRNNAME_LENGTH} wide.
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Stores {@code TRNNAMEI}, unchanged.
     *
     * @param trnname the value to store, unchanged.
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * The {@code TITLE01I} field.
     *
     * @return {@code TITLE01I} - the first title line, {@value #TITLE01_LENGTH} wide.
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Stores {@code TITLE01I}, unchanged.
     *
     * @param title01 the value to store, unchanged.
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * The {@code CURDATEI} field.
     *
     * @return {@code CURDATEI} - current date, {@value #CURDATE_LENGTH} wide, {@code mm/dd/yy}.
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Stores {@code CURDATEI}, unchanged.
     *
     * @param curdate the value to store, unchanged.
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * The {@code PGMNAMEI} field.
     *
     * @return {@code PGMNAMEI} - the program name header, {@value #PGMNAME_LENGTH} wide.
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Stores {@code PGMNAMEI}, unchanged.
     *
     * @param pgmname the value to store, unchanged.
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * The {@code TITLE02I} field.
     *
     * @return {@code TITLE02I} - the second title line, {@value #TITLE02_LENGTH} wide.
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Stores {@code TITLE02I}, unchanged.
     *
     * @param title02 the value to store, unchanged.
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * The {@code CURTIMEI} field.
     *
     * @return {@code CURTIMEI} - current time, {@value #CURTIME_LENGTH} wide, {@code hh:mm:ss}.
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Stores {@code CURTIMEI}, unchanged.
     *
     * @param curtime the value to store, unchanged.
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * The {@code TRNIDINI} field - the lookup key.
     *
     * @return {@code TRNIDINI} - the transaction id to look up, {@value #TRNIDIN_LENGTH} wide.
     *         The only input-capable field on this map, and the field the cursor is placed in
     */
    public String getTrnidin() {
        return trnidin;
    }

    /**
     * Stores {@code TRNIDINI}, unchanged.
     *
     * @param trnidin the lookup key to store, unchanged.
     */
    public void setTrnidin(String trnidin) {
        this.trnidin = trnidin;
    }

    /**
     * The {@code TRNIDI} field.
     *
     * @return {@code TRNIDI} - the transaction id as displayed, {@value #TRNID_LENGTH} wide. Filled
     *         from {@code TRAN-ID} at {@code COTRN01C} line 178 and blanked at line 159
     */
    public String getTrnid() {
        return trnid;
    }

    /**
     * Stores {@code TRNIDI}, unchanged.
     *
     * @param trnid the value to store, unchanged.
     */
    public void setTrnid(String trnid) {
        this.trnid = trnid;
    }

    /**
     * The {@code CARDNUMI} field.
     *
     * @return {@code CARDNUMI} - the card number, {@value #CARDNUM_LENGTH} wide, at full width and
     *         <strong>unmasked</strong>. Filled from {@code TRAN-CARD-NUM} at {@code COTRN01C} line
     *         179. Masking it here would be a behaviour change that practice B6 and gate G41 forbid
     */
    public String getCardnum() {
        return cardnum;
    }

    /**
     * Stores {@code CARDNUMI}, unchanged.
     *
     * @param cardnum the value to store, unchanged and unmasked.
     */
    public void setCardnum(String cardnum) {
        this.cardnum = cardnum;
    }

    /**
     * The {@code TTYPCDI} field.
     *
     * @return {@code TTYPCDI} - transaction type code, {@value #TTYPCD_LENGTH} wide.
     */
    public String getTtypcd() {
        return ttypcd;
    }

    /**
     * Stores {@code TTYPCDI}, unchanged.
     *
     * @param ttypcd the value to store, unchanged.
     */
    public void setTtypcd(String ttypcd) {
        this.ttypcd = ttypcd;
    }

    /**
     * The {@code TCATCDI} field.
     *
     * @return {@code TCATCDI} - transaction category code, {@value #TCATCD_LENGTH} wide.
     */
    public String getTcatcd() {
        return tcatcd;
    }

    /**
     * Stores {@code TCATCDI}, unchanged.
     *
     * @param tcatcd the value to store, unchanged.
     */
    public void setTcatcd(String tcatcd) {
        this.tcatcd = tcatcd;
    }

    /**
     * The {@code TRNSRCI} field.
     *
     * @return {@code TRNSRCI} - transaction source, {@value #TRNSRC_LENGTH} wide.
     */
    public String getTrnsrc() {
        return trnsrc;
    }

    /**
     * Stores {@code TRNSRCI}, unchanged.
     *
     * @param trnsrc the value to store, unchanged.
     */
    public void setTrnsrc(String trnsrc) {
        this.trnsrc = trnsrc;
    }

    /**
     * The {@code TDESCI} field.
     *
     * @return {@code TDESCI} - transaction description, {@value #TDESC_LENGTH} wide.
     */
    public String getTdesc() {
        return tdesc;
    }

    /**
     * Stores {@code TDESCI}, unchanged.
     *
     * @param tdesc the value to store, unchanged.
     */
    public void setTdesc(String tdesc) {
        this.tdesc = tdesc;
    }

    /**
     * The {@code TRNAMTI} field - the edited amount image.
     *
     * @return {@code TRNAMTI} - the transaction amount as an <strong>edited</strong>
     *         {@value #TRNAMT_LENGTH}-character image in the mask {@code +99999999.99}.
     *
     *         <p>A {@code String}, never a {@code BigDecimal} and never a {@code double}: the screen
     *         field is {@code PIC X(12)} while the record field is {@code PIC S9(09)V99}, and gate G9
     *         binds this member's length to the {@code xxxI} {@code PICTURE}. Parsing the mask into a
     *         scaled decimal is the service's job, not this payload's
     */
    public String getTrnamt() {
        return trnamt;
    }

    /**
     * Stores {@code TRNAMTI}, unchanged.
     *
     * @param trnamt the edited {@value #TRNAMT_LENGTH}-character amount image to store, unchanged.
     */
    public void setTrnamt(String trnamt) {
        this.trnamt = trnamt;
    }

    /**
     * The {@code TORIGDTI} field.
     *
     * @return {@code TORIGDTI} - origination timestamp, {@value #TORIGDT_LENGTH} wide.
     */
    public String getTorigdt() {
        return torigdt;
    }

    /**
     * Stores {@code TORIGDTI}, unchanged.
     *
     * @param torigdt the value to store, unchanged.
     */
    public void setTorigdt(String torigdt) {
        this.torigdt = torigdt;
    }

    /**
     * The {@code TPROCDTI} field.
     *
     * @return {@code TPROCDTI} - processing timestamp, {@value #TPROCDT_LENGTH} wide.
     */
    public String getTprocdt() {
        return tprocdt;
    }

    /**
     * Stores {@code TPROCDTI}, unchanged.
     *
     * @param tprocdt the value to store, unchanged.
     */
    public void setTprocdt(String tprocdt) {
        this.tprocdt = tprocdt;
    }

    /**
     * The {@code MIDI} field.
     *
     * @return {@code MIDI} - the merchant id, {@value #MID_LENGTH} wide, at full width and
     *         <strong>unmasked</strong>, per practice B6 and gate G41
     */
    public String getMid() {
        return mid;
    }

    /**
     * Stores {@code MIDI}, unchanged.
     *
     * @param mid the value to store, unchanged and unmasked.
     */
    public void setMid(String mid) {
        this.mid = mid;
    }

    /**
     * The {@code MNAMEI} field.
     *
     * @return {@code MNAMEI} - merchant name, {@value #MNAME_LENGTH} wide.
     */
    public String getMname() {
        return mname;
    }

    /**
     * Stores {@code MNAMEI}, unchanged.
     *
     * @param mname the value to store, unchanged.
     */
    public void setMname(String mname) {
        this.mname = mname;
    }

    /**
     * The {@code MCITYI} field.
     *
     * @return {@code MCITYI} - merchant city, {@value #MCITY_LENGTH} wide.
     */
    public String getMcity() {
        return mcity;
    }

    /**
     * Stores {@code MCITYI}, unchanged.
     *
     * @param mcity the value to store, unchanged.
     */
    public void setMcity(String mcity) {
        this.mcity = mcity;
    }

    /**
     * The {@code MZIPI} field.
     *
     * @return {@code MZIPI} - merchant postal code, {@value #MZIP_LENGTH} wide.
     */
    public String getMzip() {
        return mzip;
    }

    /**
     * Stores {@code MZIPI}, unchanged.
     *
     * @param mzip the value to store, unchanged.
     */
    public void setMzip(String mzip) {
        this.mzip = mzip;
    }

    /**
     * The {@code ERRMSGI} field.
     *
     * @return {@code ERRMSGI} - the error line, {@value #ERRMSG_LENGTH} wide. Written through the
     *         {@code AO} alias as {@code ERRMSGO} at {@code COTRN01C} lines 92 and 217 - the same
     *         bytes as this field
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Stores {@code ERRMSGI}, unchanged.
     *
     * @param errmsg the value to store, unchanged.
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    // =================================================================================================
    // Conversation state accessors
    // =================================================================================================

    /**
     * The communication area carried by this request, or {@code null} when none was passed.
     *
     * @return the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area, or {@code null}
     *         for the {@code EIBCALEN = 0} cold start of {@code app/cbl/COTRN01C.cbl:94}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the communication area, or removes it.
     *
     * <p>{@code null} is stored as {@code null} rather than normalised. It is not a missing value but
     * a state the program acts on: {@code EIBCALEN = 0}, on which
     * {@code app/cbl/COTRN01C.cbl:94-96} moves {@code 'COSGN00C'} into {@code CDEMO-TO-PROGRAM} and
     * returns to the previous screen without reading a context byte at all. Substituting an
     * initialised area would send the request down the {@code ELSE} branch instead, which is a
     * different behaviour and not a tidier spelling of the same one.
     *
     * @param navigationContext the area to carry, or {@code null} to carry none
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN}
     * being non-zero at {@code app/cbl/COTRN01C.cbl:94}.
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
     * The length CICS would report in {@code EIBCALEN}: {@value #COMMAREA_TOTAL_LENGTH} when a
     * communication area travelled with this request, and {@code 0} when none did.
     *
     * <p>{@code COTRN01C} passes {@code CARDDEMO-COMMAREA} followed by its own
     * {@value Ct01Info#RECORD_LENGTH}-byte {@code CDEMO-CT01-INFO} extension, so the non-zero case is
     * the sum of the two, which is what {@link #COMMAREA_TOTAL_LENGTH} states.
     *
     * @return {@value #COMMAREA_TOTAL_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? COMMAREA_TOTAL_LENGTH : 0;
    }

    /**
     * The ct01Info carried by this request.
     *
     * @return the {@value Ct01Info#RECORD_LENGTH}-byte {@code CDEMO-CT01-INFO} extension, never null.
     */
    public Ct01Info getCt01Info() {
        return ct01Info;
    }

    /**
     * Replaces the communication area extension.
     *
     * @param ct01Info the extension to carry; {@code null} is normalised to a freshly initialised
     *                 extension, whose next-page flag is {@code "N"} as the copybook's
     *                 {@code VALUE 'N'} clause specifies
     */
    public void setCt01Info(Ct01Info ct01Info) {
        this.ct01Info = ct01Info == null ? new Ct01Info() : ct01Info;
    }

    /**
     * The aid carried by this request.
     *
     * @return the {@code EIBAID} attention identifier, {@value #AID_LENGTH} byte wide.
     */
    public String getAid() {
        return aid;
    }

    /**
     * Replaces the aid carried by this request.
     *
     * @param aid the attention identifier to store, unchanged.
     */
    public void setAid(String aid) {
        this.aid = aid;
    }

    /**
     * Whether this request is a first entry.
     *
     * @return {@code true} when this is a first entry - {@code CDEMO-PGM-CONTEXT} holds
     *         {@value NavigationContext#PGM_CONTEXT_ENTER}, condition name {@code CDEMO-PGM-ENTER}.
     *         {@code COTRN01C} line 99 branches on exactly this to decide whether to paint the screen
     *         or validate what was typed
     *
     *         <p>Derived from {@link #getNavigationContext()} rather than stored separately. Two
     *         copies of one piece of state can disagree, and a request whose flag contradicted its own
     *         communication area would be a genuine defect with no correct interpretation
     *
     *         <p>{@code false} when no communication area travelled at all. That is not the same
     *         statement as "not a first entry": with {@code EIBCALEN = 0} there is no
     *         {@code CDEMO-PGM-CONTEXT} byte to be in either state, and line 94 never reads one - it
     *         takes the cold-start path. {@link #hasNavigationContext()} is the predicate that
     *         distinguishes that case, and a controller reproducing the program must test it first
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * Whether this request is a re-entry.
     *
     * @return {@code true} when this is a re-entry - {@code CDEMO-PGM-CONTEXT} holds
     *         {@value NavigationContext#PGM_CONTEXT_REENTER}, condition name
     *         {@code CDEMO-PGM-REENTER}. This is the state in which field highlighting applies
     *
     *         <p>Deliberately <strong>not</strong> the negation of {@link #isEnter()}.
     *         {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold any digit, so for a value
     *         such as {@code 9} both predicates are correctly {@code false}; and with no communication
     *         area at all both are {@code false} too. Defining either as the other's complement would
     *         invent a state the copybook does not describe
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    // =================================================================================================
    // Metadata accessors - never serialised
    // =================================================================================================

    /**
     * The metadata carrier for one screen field.
     *
     * @param fieldName one of {@link #PAYLOAD_FIELD_NAMES}, for example {@code "TRNIDINI"}
     * @return that field's {@code xxxL}, {@code xxxF} and {@code xxxA} items, never {@code null}
     * @throws NullPointerException     if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not a field of this map
     */
    @JsonIgnore
    public ScreenFieldMetadata getMetadata(String fieldName) {
        Objects.requireNonNull(fieldName, "A field name is required to look up its metadata");
        ScreenFieldMetadata carrier = metadata.get(fieldName);
        if (carrier == null) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a field of "
                    + SYMBOLIC_MAP_GROUP + "; the " + PAYLOAD_FIELD_COUNT + " field names are "
                    + PAYLOAD_FIELD_NAMES);
        }
        return carrier;
    }

    /**
     * Every metadata carrier, keyed by verbatim {@code xxxI} field name in declaration order.
     *
     * @return an unmodifiable view; the carriers themselves stay mutable, because
     *         {@code MOVE -1 TO TRNIDINL} has to be expressible
     */
    @JsonIgnore
    public Map<String, ScreenFieldMetadata> getAllMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * Places the cursor in one field: {@code MOVE -1 TO <field>L}.
     *
     * <p>{@code COTRN01C} issues this at six sites - lines 102, 151, 154, 287, 294 and 311 - and every
     * one of them targets {@code TRNIDIN}, the field declared {@code IC} in the mapset. Naming the
     * idiom keeps that intent legible; a bare {@code setLengthItem((short) -1)} would not.
     *
     * @param fieldName the field to place the cursor in, one of {@link #PAYLOAD_FIELD_NAMES}
     * @throws NullPointerException     if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not a field of this map
     */
    public void requestCursorAt(String fieldName) {
        getMetadata(fieldName).requestCursor();
    }

    /**
     * The field holding the cursor request, if any.
     *
     * @return the field currently requesting the cursor, or {@code null} when none is. Scans in
     *         declaration order and returns the first, mirroring the fact that a 3270 datastream
     *         carries a single cursor position
     */
    @JsonIgnore
    public String cursorField() {
        for (Map.Entry<String, ScreenFieldMetadata> entry : metadata.entrySet()) {
            if (entry.getValue().isCursorRequested()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Returns every metadata carrier to its neutral state: length item zero, flag byte a space. */
    public void resetMetadata() {
        for (ScreenFieldMetadata carrier : metadata.values()) {
            carrier.reset();
        }
    }

    // =================================================================================================
    // Fixed-width rendering. Every width goes through FixedWidthCodec (practice B11) so that each one
    // is explicit at the point it is applied and diffable against the copybook.
    // =================================================================================================

    /**
     * Renders the {@value #PAYLOAD_FIELD_COUNT} payload fields as declared-width images, keyed by
     * verbatim {@code xxxI} name in declaration order.
     *
     * <p>This is the field-for-field input the parity differ compares: each value is exactly its
     * declared width, space-padded on the right by {@link FixedWidthCodec#movePicX(String, int)}, and
     * a {@code null} field renders as the blank field COBOL would hold. Comparing per field rather
     * than as one concatenated string is what makes a diff name the field that is wrong.
     *
     * @param codec the codec, carrying the code page explicitly - never the platform default
     * @return a mutable, insertion-ordered map of {@value #PAYLOAD_FIELD_COUNT} entries
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public Map<String, String> toFieldImages(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render " + SYMBOLIC_MAP_GROUP
                + ": the code page of a fixed-width image is always stated explicitly and never "
                + "derived from the platform");
        Map<String, String> images = new LinkedHashMap<>();
        images.put(TRNNAME_FIELD, codec.movePicX(orBlank(trnname), TRNNAME_LENGTH));
        images.put(TITLE01_FIELD, codec.movePicX(orBlank(title01), TITLE01_LENGTH));
        images.put(CURDATE_FIELD, codec.movePicX(orBlank(curdate), CURDATE_LENGTH));
        images.put(PGMNAME_FIELD, codec.movePicX(orBlank(pgmname), PGMNAME_LENGTH));
        images.put(TITLE02_FIELD, codec.movePicX(orBlank(title02), TITLE02_LENGTH));
        images.put(CURTIME_FIELD, codec.movePicX(orBlank(curtime), CURTIME_LENGTH));
        images.put(TRNIDIN_FIELD, codec.movePicX(orBlank(trnidin), TRNIDIN_LENGTH));
        images.put(TRNID_FIELD, codec.movePicX(orBlank(trnid), TRNID_LENGTH));
        images.put(CARDNUM_FIELD, codec.movePicX(orBlank(cardnum), CARDNUM_LENGTH));
        images.put(TTYPCD_FIELD, codec.movePicX(orBlank(ttypcd), TTYPCD_LENGTH));
        images.put(TCATCD_FIELD, codec.movePicX(orBlank(tcatcd), TCATCD_LENGTH));
        images.put(TRNSRC_FIELD, codec.movePicX(orBlank(trnsrc), TRNSRC_LENGTH));
        images.put(TDESC_FIELD, codec.movePicX(orBlank(tdesc), TDESC_LENGTH));
        images.put(TRNAMT_FIELD, codec.movePicX(orBlank(trnamt), TRNAMT_LENGTH));
        images.put(TORIGDT_FIELD, codec.movePicX(orBlank(torigdt), TORIGDT_LENGTH));
        images.put(TPROCDT_FIELD, codec.movePicX(orBlank(tprocdt), TPROCDT_LENGTH));
        images.put(MID_FIELD, codec.movePicX(orBlank(mid), MID_LENGTH));
        images.put(MNAME_FIELD, codec.movePicX(orBlank(mname), MNAME_LENGTH));
        images.put(MCITY_FIELD, codec.movePicX(orBlank(mcity), MCITY_LENGTH));
        images.put(MZIP_FIELD, codec.movePicX(orBlank(mzip), MZIP_LENGTH));
        images.put(ERRMSG_FIELD, codec.movePicX(orBlank(errmsg), ERRMSG_LENGTH));
        return images;
    }

    /**
     * Concatenates the {@value #PAYLOAD_FIELD_COUNT} declared-width field images in declaration
     * order.
     *
     * <p>The result is always exactly {@value #PAYLOAD_TOTAL_LENGTH} characters, because every field
     * is rendered at its declared width and those widths are verified to sum to that at class-load.
     * This is the payload portion of the symbolic map only: the {@value #TIOA_PREFIX_LENGTH}-byte
     * prefix and the {@value #FIELD_METADATA_LENGTH} metadata bytes per field are not included, which
     * is why it is {@value #PAYLOAD_TOTAL_LENGTH} rather than {@value #SYMBOLIC_MAP_LENGTH}.
     *
     * @param codec the codec, carrying the code page explicitly
     * @return exactly {@value #PAYLOAD_TOTAL_LENGTH} characters
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String toPayloadImage(FixedWidthCodec codec) {
        StringBuilder image = new StringBuilder(PAYLOAD_TOTAL_LENGTH);
        for (String fieldImage : toFieldImages(codec).values()) {
            image.append(fieldImage);
        }
        return image.toString();
    }

    /**
     * Renders the communication area {@code COTRN01C} actually passes: the standard
     * {@value NavigationContext#COMMAREA_LENGTH}-byte area followed by the
     * {@value Ct01Info#RECORD_LENGTH}-byte {@code CDEMO-CT01-INFO} extension.
     *
     * <p>There has to <em>be</em> a communication area to render. When none travelled with the request
     * - {@link #hasNavigationContext()} is {@code false}, {@link #commareaLength()} is zero - there is
     * no {@value #COMMAREA_TOTAL_LENGTH}-byte area to produce and no defensible substitute for one:
     * emitting an initialised area would be inventing the very bytes whose absence
     * {@code app/cbl/COTRN01C.cbl:94} branches on. The call is refused instead.
     *
     * @param codec the codec, carrying the code page explicitly
     * @return exactly {@value #COMMAREA_TOTAL_LENGTH} bytes
     * @throws NullPointerException  if {@code codec} is {@code null}
     * @throws IllegalStateException if no communication area travelled with this request
     */
    public byte[] toCommareaImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the "
                + COMMAREA_TOTAL_LENGTH + "-byte communication area");
        if (!hasNavigationContext()) {
            throw new IllegalStateException("No communication area travelled with this request, so "
                    + "there are no " + COMMAREA_TOTAL_LENGTH + " bytes to render: EIBCALEN is 0, "
                    + "which is the cold start COTRN01C.cbl:94 tests for and answers by transferring "
                    + "to COSGN00C. Test hasNavigationContext() first, or set one with "
                    + "setNavigationContext");
        }
        byte[] standard = navigationContext.toFixedWidth(codec);
        byte[] extension = ct01Info.toFixedWidth(codec);
        byte[] combined = new byte[standard.length + extension.length];
        System.arraycopy(standard, 0, combined, 0, standard.length);
        System.arraycopy(extension, 0, combined, standard.length, extension.length);
        return combined;
    }

    // =================================================================================================
    // Equality, hashing and diagnostics
    // =================================================================================================

    /**
     * Every piece of state, in one canonical order, backing {@link #equals(Object)} and
     * {@link #hashCode()}.
     *
     * <p>Deliberately a list rather than a hand-written chain of {@code &&} comparisons. With
     * {@value #PAYLOAD_FIELD_COUNT} payload fields plus the conversation state that chain would run to
     * around fifty branches, and - far worse - a field omitted from it would produce an
     * {@code equals} that silently ignored a real difference. Listing the values once makes the
     * omission impossible to hide and keeps the method trivially verifiable.
     *
     * <p>{@link Arrays#asList} rather than {@code List.of}, because these values may legitimately be
     * {@code null} and {@code List.of} rejects nulls.
     *
     * @return every piece of state, in a fixed order, nulls permitted
     */
    private List<Object> stateValues() {
        return Arrays.asList(trnname, title01, curdate, pgmname, title02, curtime,
                trnidin, trnid, cardnum, ttypcd, tcatcd, trnsrc, tdesc, trnamt, torigdt, tprocdt,
                mid, mname, mcity, mzip, errmsg, navigationContext, ct01Info, aid, metadata);
    }

    /**
     * Value equality across all {@value #PAYLOAD_FIELD_COUNT} payload fields, the communication area,
     * the {@code CDEMO-CT01-INFO} extension, the attention identifier and every metadata carrier.
     *
     * <p>Field values are compared <strong>exactly</strong>, padding included: two requests whose
     * {@code ERRMSG} differs only in trailing spaces are not equal, because in a fixed-width world
     * those spaces are content. That is precisely the property a lossless round trip needs.
     *
     * @param other the object to compare against
     * @return {@code true} when every piece of state matches
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionAddRequest that)) {
            return false;
        }
        return stateValues().equals(that.stateValues());
    }

    @Override
    public int hashCode() {
        return stateValues().hashCode();
    }

    /**
     * A short diagnostic summary naming the screen and the identifying fields.
     *
     * <p>A summary, not the payload projection: {@link #toFieldImages(FixedWidthCodec)} is the
     * complete, authoritative rendering. Nothing is redacted anywhere in this class - practice B6
     * forbids changing the legacy security posture in either direction - and every field is available
     * in full through its accessor. The fields chosen here are simply the ones that identify a
     * request in a log line.
     */
    @Override
    public String toString() {
        return "TransactionAddRequest[" + TRANSACTION_ID + "/" + PROGRAM_NAME
                + ", map=" + MAPSET_NAME + "." + MAP_NAME
                + ", " + TRNIDIN_FIELD + "='" + trnidin + "'"
                + ", " + TRNID_FIELD + "='" + trnid + "'"
                + ", aid='" + aid + "'"
                + ", pgmContext=" + (hasNavigationContext()
                        ? String.valueOf(navigationContext.pgmContext())
                        : "none (EIBCALEN=0)")
                + ", " + Ct01Info.TRN_SELECTED_FIELD + "='" + ct01Info.getTrnSelected() + "']";
    }

    // =================================================================================================
    // NESTED TYPE: per-field screen metadata. Deliberately not a payload member.
    // =================================================================================================

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} items belonging to one screen field.
     *
     * <p>AAP &sect;0.6.3 is explicit that these three are validation and highlight <em>metadata</em>
     * and never JSON payload members: only the {@code xxxI} item supplies a payload field. They are
     * still modelled, because {@code COTRN01C} genuinely reads and writes them, and dropping them
     * would lose behaviour.
     *
     * <h2>The length item is signed, and that matters</h2>
     *
     * <p>{@code xxxL} is declared {@code COMP PIC S9(4)} - a <em>signed</em> binary halfword - and is
     * held here as a {@code short} for exactly that reason. {@code COTRN01C} writes
     * {@code MOVE -1 TO TRNIDINL OF COTRN1AI} at six sites (lines 102, 151, 154, 287, 294 and 311),
     * which is the CICS convention for "put the cursor here". A carrier that was unsigned, or that
     * clamped at zero, could not express it. On input CICS reports the number of characters the
     * terminal actually sent, which is what makes the item useful for presence checks.
     *
     * <h2>The flag byte and the attribute byte are one byte, not two</h2>
     *
     * <p>The copybook declares {@code 02 xxxF PICTURE X.} and then
     * {@code 02 FILLER REDEFINES xxxF. 03 xxxA PICTURE X.} - so {@code xxxA} is a
     * {@code REDEFINES} overlay on {@code xxxF} and the two names address <strong>the same
     * byte</strong>. This class therefore stores that byte once and exposes two named accessors over
     * it, which is the rule AAP &sect;0.3.7 states for {@code REDEFINES} and what gate G34 checks.
     * Storing them as two independent fields would let them disagree, and a disagreement between two
     * names for one byte is not a state the mainframe can ever be in.
     */
    public static final class ScreenFieldMetadata {

        /** Neutral content of a one-byte {@code PICTURE X} item: a single space. */
        public static final String UNSET_BYTE = " ";

        /** The {@code DFHMDF} label, for example {@code TRNNAME} - the {@code xxxI} name less its I. */
        private final String screenName;

        /** The declared width of the companion {@code xxxI} item. */
        private final int declaredLength;

        /** {@code xxxL COMP PIC S9(4)} - signed, so {@link #CURSOR_REQUEST} is representable. */
        private short lengthItem;

        /**
         * The single byte addressed by both {@code xxxF} and its {@code xxxA} {@code REDEFINES}
         * overlay. One field, because it is one byte.
         */
        private String flagByte = UNSET_BYTE;

        /**
         * Creates the metadata carrier for one screen field.
         *
         * @param screenName     the {@code DFHMDF} label, not blank
         * @param declaredLength the companion {@code xxxI} item's declared width, at least 1
         * @throws NullPointerException     if {@code screenName} is {@code null}
         * @throws IllegalArgumentException if {@code screenName} is blank or {@code declaredLength}
         *                                  is below 1
         */
        public ScreenFieldMetadata(String screenName, int declaredLength) {
            Objects.requireNonNull(screenName, "A screen field's DFHMDF label is required");
            if (screenName.isBlank()) {
                throw new IllegalArgumentException("A screen field's DFHMDF label cannot be blank; "
                        + "it names the field the L, F and A items belong to");
            }
            if (declaredLength < 1) {
                throw new IllegalArgumentException("Screen field '" + screenName + "' cannot declare "
                        + "a width of " + declaredLength + "; a DFHMDF field occupies at least "
                        + "1 byte");
            }
            this.screenName = screenName;
            this.declaredLength = declaredLength;
        }

        /**
         * Copy constructor, so a request can be defensively copied without sharing metadata.
         *
         * @param other the carrier to copy, not {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public ScreenFieldMetadata(ScreenFieldMetadata other) {
            Objects.requireNonNull(other, "A source ScreenFieldMetadata is required to copy one");
            this.screenName = other.screenName;
            this.declaredLength = other.declaredLength;
            this.lengthItem = other.lengthItem;
            this.flagByte = other.flagByte;
        }

        /**
         * The screenName carried by this request.
         *
         * @return the {@code DFHMDF} label, for example {@code TRNNAME}.
         */
        public String getScreenName() {
            return screenName;
        }

        /**
         * The declaredLength carried by this request.
         *
         * @return the declared width of the companion {@code xxxI} item.
         */
        public int getDeclaredLength() {
            return declaredLength;
        }

        /**
         * Name of the {@code xxxL} length item.
         *
         * @return the verbatim symbolic-map name, for example {@code TRNNAMEL}
         */
        public String lengthItemName() {
            return screenName + "L";
        }

        /**
         * Name of the {@code xxxF} flag item.
         *
         * @return the verbatim symbolic-map name, for example {@code TRNNAMEF}
         */
        public String flagItemName() {
            return screenName + "F";
        }

        /**
         * Name of the {@code xxxA} attribute item.
         *
         * @return the verbatim symbolic-map name of the attribute item, for example
         *         {@code TRNNAMEA}. It overlays {@link #flagItemName()} and shares its byte.
         */
        public String attributeItemName() {
            return screenName + "A";
        }

        /**
         * Name of the {@code xxxI} value item - the payload field.
         *
         * @return the verbatim symbolic-map name, for example {@code TRNNAMEI}
         */
        public String inputItemName() {
            return screenName + "I";
        }

        /**
         * The lengthItem carried by this request.
         *
         * @return the {@code xxxL} halfword; negative means a cursor request, never a length.
         */
        public short getLengthItem() {
            return lengthItem;
        }

        /**
         * Sets the {@code xxxL} halfword.
         *
         * <p>Any {@code short} is accepted, negatives included, because {@code MOVE -1} is a
         * legitimate and frequently used value. No clamping and no validation against
         * {@link #getDeclaredLength()}: CICS is free to report whatever the terminal sent, and
         * second-guessing it here would diverge from the mainframe.
         *
         * @param lengthItem the value to store
         */
        public void setLengthItem(short lengthItem) {
            this.lengthItem = lengthItem;
        }

        /** Sets the length item to {@link #CURSOR_REQUEST}, the {@code MOVE -1} idiom. */
        public void requestCursor() {
            this.lengthItem = CURSOR_REQUEST;
        }

        /**
         * Whether this field is requesting the cursor.
         *
         * @return {@code true} when the length item holds {@link #CURSOR_REQUEST}, that is when this
         *         field is the one asking for the cursor
         */
        public boolean isCursorRequested() {
            return lengthItem == CURSOR_REQUEST;
        }

        /**
         * Whether the terminal sent input for this field.
         *
         * @return {@code true} when the terminal sent at least one character for this field. A
         *         negative length item is a cursor request rather than a length, so it does not count
         *         as input
         */
        public boolean hasInput() {
            return lengthItem > 0;
        }

        /**
         * The flagItem carried by this request.
         *
         * @return the {@code xxxF} flag byte.
         */
        public String getFlagItem() {
            return flagByte;
        }

        /**
         * Sets the {@code xxxF} flag byte, and therefore the {@code xxxA} attribute byte with it.
         *
         * @param flagItem the one-character value; {@code null} is normalised to a space, because a
         *                 COBOL {@code PICTURE X} item has no null state
         * @throws IllegalArgumentException if more than one character is supplied - the item is one
         *                                  byte wide and silently dropping the surplus would hide a
         *                                  defect
         */
        public void setFlagItem(String flagItem) {
            this.flagByte = requireSingleByte(flagItem, flagItemName());
        }

        /**
         * The {@code xxxA} attribute byte.
         *
         * @return the {@code xxxA} attribute byte. Identical to {@link #getFlagItem()} by
         *         construction: {@code xxxA} {@code REDEFINES} {@code xxxF}
         */
        public String getAttributeItem() {
            return flagByte;
        }

        /**
         * Sets the {@code xxxA} attribute byte, and therefore the {@code xxxF} flag byte with it.
         *
         * <p>This is the accessor the field-highlighting path writes through when a validation error
         * puts {@code DFHRED} and an asterisk on the offending field.
         *
         * @param attributeItem the one-character attribute; {@code null} is normalised to a space
         * @throws IllegalArgumentException if more than one character is supplied
         */
        public void setAttributeItem(String attributeItem) {
            this.flagByte = requireSingleByte(attributeItem, attributeItemName());
        }

        /** Restores the neutral state: length item zero, flag and attribute byte a space. */
        public void reset() {
            this.lengthItem = 0;
            this.flagByte = UNSET_BYTE;
        }

        /**
         * Rejects anything wider than the single byte a {@code PICTURE X} item holds.
         *
         * @param value    the candidate byte, possibly {@code null} or empty
         * @param itemName the item being written, used to name the offender in the message
         * @return the value, with {@code null} and empty normalised to a single space
         * @throws IllegalArgumentException if {@code value} is more than one character
         */
        private static String requireSingleByte(String value, String itemName) {
            if (value == null) {
                return UNSET_BYTE;
            }
            if (value.length() > FLAG_ITEM_LENGTH) {
                throw new IllegalArgumentException("Item " + itemName + " is PICTURE X, one byte "
                        + "wide, but was given " + value.length() + " character(s): '" + value
                        + "'. It cannot hold the surplus");
            }
            return value.isEmpty() ? UNSET_BYTE : value;
        }

        /**
         * Every piece of state in one canonical order; see the enclosing type's {@code stateValues}.
         *
         * @return every piece of state, in a fixed order, nulls permitted
         */
        private List<Object> stateValues() {
            return Arrays.asList(screenName, declaredLength, lengthItem, flagByte);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScreenFieldMetadata that)) {
                return false;
            }
            return stateValues().equals(that.stateValues());
        }

        @Override
        public int hashCode() {
            return stateValues().hashCode();
        }

        @Override
        public String toString() {
            return "ScreenFieldMetadata[" + screenName + ", declaredLength=" + declaredLength
                    + ", " + lengthItemName() + "=" + lengthItem
                    + ", " + flagItemName() + "='" + flagByte + "']";
        }
    }

    // =================================================================================================
    // NESTED TYPE: the 58-byte commarea extension. Nested on purpose - see the class Javadoc.
    // =================================================================================================

    /**
     * The {@code 05 CDEMO-CT01-INFO} group that {@code COTRN01C} appends to the communication area.
     *
     * <p>{@code COTRN01C} copies {@code COCOM01Y} at line 52 and then <em>extends the copied group in
     * place</em> at lines 53-61, so the communication area it passes is the
     * {@value NavigationContext#COMMAREA_LENGTH}-byte standard area plus these
     * {@value #RECORD_LENGTH} bytes = {@value TransactionAddRequest#COMMAREA_TOTAL_LENGTH} in total:
     *
     * <pre>
     * 05 CDEMO-CT01-INFO.
     *    10 CDEMO-CT01-TRNID-FIRST     PIC X(16).
     *    10 CDEMO-CT01-TRNID-LAST      PIC X(16).
     *    10 CDEMO-CT01-PAGE-NUM        PIC 9(08).
     *    10 CDEMO-CT01-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
     *       88 NEXT-PAGE-YES                     VALUE 'Y'.
     *       88 NEXT-PAGE-NO                      VALUE 'N'.
     *    10 CDEMO-CT01-TRN-SEL-FLG     PIC X(01).
     *    10 CDEMO-CT01-TRN-SELECTED    PIC X(16).
     * </pre>
     *
     * <h2>Why this is nested here and not shared</h2>
     *
     * <p>{@link NavigationContext} models {@code CARDDEMO-COMMAREA} at exactly
     * {@value NavigationContext#COMMAREA_LENGTH} bytes and is copied by all seventeen online
     * programs. Widening it to accommodate this extension would corrupt every one of those sixteen
     * other contracts, so the extension lives here, alongside the one program that declares it.
     *
     * <p>Nor is it hoisted into a type shared with its siblings. {@code COTRN00C}, {@code COTRN01C}
     * and {@code COTRN02C} each declare a structurally identical group under a <em>different</em>
     * name - {@code CDEMO-CT00-*}, {@code CDEMO-CT01-*}, {@code CDEMO-CT02-*}. Identical shape is not
     * interchangeability: field-for-field diffing compares by name, so collapsing the three would
     * make a genuine cross-program difference invisible.
     *
     * <h2>The list-to-detail handoff</h2>
     *
     * <p>{@link #getTrnSelected()} is how a user arrives here from the transaction list.
     * {@code COTRN01C} lines 103-107 test {@code IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND
     * LOW-VALUES} and, when it is populated, move it into {@code TRNIDINI} and run the lookup
     * immediately - so a request carrying a selection needs no separately supplied lookup key.
     */
    public static final class Ct01Info {

        /** {@code CDEMO-CT01-TRNID-FIRST}, {@code COTRN01C} line 54. */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT01-TRNID-FIRST";

        /** {@code CDEMO-CT01-TRNID-LAST}, {@code COTRN01C} line 55. */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT01-TRNID-LAST";

        /** {@code CDEMO-CT01-PAGE-NUM}, {@code COTRN01C} line 56. */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT01-PAGE-NUM";

        /** {@code CDEMO-CT01-NEXT-PAGE-FLG}, {@code COTRN01C} line 57. */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT01-NEXT-PAGE-FLG";

        /** {@code CDEMO-CT01-TRN-SEL-FLG}, {@code COTRN01C} line 60. */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT01-TRN-SEL-FLG";

        /** {@code CDEMO-CT01-TRN-SELECTED}, {@code COTRN01C} line 61. */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT01-TRN-SELECTED";

        /** {@code PIC X(16)}. */
        public static final int TRNID_FIRST_LENGTH = 16;

        /** {@code PIC X(16)}. */
        public static final int TRNID_LAST_LENGTH = 16;

        /** {@code PIC 9(08)} - eight unsigned digits, so no sign position and no scale. */
        public static final int PAGE_NUM_LENGTH = 8;

        /** {@code PIC X(01)}. */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /** {@code PIC X(01)}. */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /** {@code PIC X(16)}. */
        public static final int TRN_SELECTED_LENGTH = 16;

        /** Offset of {@code CDEMO-CT01-TRNID-FIRST} within the extension. */
        public static final int TRNID_FIRST_OFFSET = 0;
        /** Offset of {@code CDEMO-CT01-TRNID-LAST}. */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;
        /** Offset of {@code CDEMO-CT01-PAGE-NUM}. */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;
        /** Offset of {@code CDEMO-CT01-NEXT-PAGE-FLG}. */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;
        /** Offset of {@code CDEMO-CT01-TRN-SEL-FLG}. */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;
        /** Offset of {@code CDEMO-CT01-TRN-SELECTED}. */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * Total width: 16 + 16 + 8 + 1 + 1 + 16 = {@value #RECORD_LENGTH} bytes.
         *
         * <p>{@link #LAYOUT} cannot be constructed unless its spans sum to exactly this, so the
         * number is enforced rather than asserted.
         */
        public static final int RECORD_LENGTH = 58;

        /** The largest value {@code PIC 9(08)} can hold: eight nines. */
        public static final int PAGE_NUM_MAX = 99_999_999;

        /** {@code 88 NEXT-PAGE-YES VALUE 'Y'}. */
        public static final String NEXT_PAGE_YES = "Y";

        /** {@code 88 NEXT-PAGE-NO VALUE 'N'} - and the group's {@code VALUE 'N'} initial state. */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * Byte geometry of the extension, every span named and contiguous from offset zero.
         *
         * <p>Unlike the symbolic map, this group maps cleanly onto the codec's picture kinds: five
         * alphanumeric spans and one unsigned zoned numeric, no binary items and no overlays. The
         * layout is deeply immutable, so publishing it adds no mutable static state, and it is public
         * because the parity differ needs the geometry to compare field by field.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(
                        NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET, NEXT_PAGE_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(
                        TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET, TRN_SELECTED_LENGTH));

        @Size(max = TRNID_FIRST_LENGTH)
        private String trnidFirst = spaces(TRNID_FIRST_LENGTH);

        @Size(max = TRNID_LAST_LENGTH)
        private String trnidLast = spaces(TRNID_LAST_LENGTH);

        @Min(0)
        @Max(PAGE_NUM_MAX)
        private int pageNum;

        /** Initialised to {@code "N"}, reproducing the copybook's {@code VALUE 'N'} clause. */
        @Size(max = NEXT_PAGE_FLG_LENGTH)
        private String nextPageFlg = NEXT_PAGE_NO;

        @Size(max = TRN_SEL_FLG_LENGTH)
        private String trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);

        @Size(max = TRN_SELECTED_LENGTH)
        private String trnSelected = spaces(TRN_SELECTED_LENGTH);

        /**
         * Creates the extension in its declared initial state: character fields blank at their
         * declared widths, page number zero, and the next-page flag {@code "N"} exactly as
         * {@code PIC X(01) VALUE 'N'} specifies.
         */
        public Ct01Info() {
            // Field initialisers above carry the declared VALUE clauses; see each declaration.
        }

        /**
         * Copy constructor, so an enclosing request can be deep-copied.
         *
         * @param other the extension to copy, not {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public Ct01Info(Ct01Info other) {
            Objects.requireNonNull(other, "A source Ct01Info is required to copy one");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * The {@code CDEMO-CT01-TRNID-FIRST} field.
         *
         * @return {@code CDEMO-CT01-TRNID-FIRST}, the first transaction id on the current page.
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Stores {@code CDEMO-CT01-TRNID-FIRST}, unchanged.
         *
         * @param trnidFirst the value to store; {@code null} is kept as {@code null} and rendered blank.
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = trnidFirst;
        }

        /**
         * The {@code CDEMO-CT01-TRNID-LAST} field.
         *
         * @return {@code CDEMO-CT01-TRNID-LAST}, the last transaction id on the current page.
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Stores {@code CDEMO-CT01-TRNID-LAST}, unchanged.
         *
         * @param trnidLast the value to store; {@code null} is kept as {@code null}.
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = trnidLast;
        }

        /**
         * The {@code CDEMO-CT01-PAGE-NUM} field.
         *
         * @return {@code CDEMO-CT01-PAGE-NUM}. An {@code int}: {@code PIC 9(08)} carries no scale.
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Sets the page number.
         *
         * @param pageNum the page number, {@code 0} to {@value #PAGE_NUM_MAX}
         * @throws IllegalArgumentException if negative or wider than eight digits. {@code PIC 9(08)}
         *                                  is unsigned and eight digits wide, so neither has a
         *                                  representation, and a numeric {@code MOVE} would quietly
         *                                  drop the high-order digits instead
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(08), an unsigned "
                        + "picture with no sign position, so it cannot hold " + pageNum);
            }
            if (pageNum > PAGE_NUM_MAX) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(08) and holds at most "
                        + PAGE_NUM_MAX + ", so it cannot hold " + pageNum
                        + "; storing it would silently lose the high-order digit(s)");
            }
            this.pageNum = pageNum;
        }

        /**
         * The {@code CDEMO-CT01-NEXT-PAGE-FLG} field.
         *
         * @return {@code CDEMO-CT01-NEXT-PAGE-FLG}; {@code "N"} until something sets it otherwise.
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Stores {@code CDEMO-CT01-NEXT-PAGE-FLG}, unchanged.
         *
         * @param nextPageFlg the flag to store; {@code null} is kept as {@code null}.
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg = nextPageFlg;
        }

        /**
         * Condition name {@code NEXT-PAGE-YES}.
         *
         * @return {@code true} when the flag holds {@code 'Y'} - condition name {@code NEXT-PAGE-YES}.
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * Condition name {@code NEXT-PAGE-NO}.
         *
         * @return {@code true} when the flag holds {@code 'N'} - condition name {@code NEXT-PAGE-NO}.
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /** {@code SET NEXT-PAGE-YES TO TRUE} - stores {@code 'Y'}. */
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /** {@code SET NEXT-PAGE-NO TO TRUE} - stores {@code 'N'}. */
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * The {@code CDEMO-CT01-TRN-SEL-FLG} field.
         *
         * @return {@code CDEMO-CT01-TRN-SEL-FLG}, the selection indicator carried from the list.
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Stores {@code CDEMO-CT01-TRN-SEL-FLG}, unchanged.
         *
         * @param trnSelFlg the value to store; {@code null} is kept as {@code null}.
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = trnSelFlg;
        }

        /**
         * The {@code CDEMO-CT01-TRN-SELECTED} item.
         *
         * @return {@code CDEMO-CT01-TRN-SELECTED}, the transaction id chosen on the list screen. When
         *         populated, {@code COTRN01C} lines 103-107 copy it into the lookup key and view it
         *         straight away
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Stores {@code CDEMO-CT01-TRN-SELECTED}, unchanged.
         *
         * @param trnSelected the value to store; {@code null} is kept as {@code null}.
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = trnSelected;
        }

        /**
         * Whether a transaction was selected on the list screen.
         *
         * @return {@code true} when a selection was carried in, mirroring {@code COTRN01C} line 103's
         *         {@code IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES}. A {@code null},
         *         empty, all-blank or all-{@code LOW-VALUES} field is no selection
         */
        @JsonIgnore
        public boolean hasSelection() {
            if (trnSelected == null || trnSelected.isEmpty()) {
                return false;
            }
            for (int i = 0; i < trnSelected.length(); i++) {
                char character = trnSelected.charAt(i);
                if (character != ' ' && character != '\0') {
                    return true;
                }
            }
            return false;
        }

        /**
         * Renders the extension as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly - never the platform default
         * @return exactly {@value #RECORD_LENGTH} bytes in the codec's code page
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] toFixedWidth(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to render "
                    + "CDEMO-CT01-INFO: the code page of a fixed-width image is always stated "
                    + "explicitly and never derived from the platform");
            Map<String, String> images = new LinkedHashMap<>();
            images.put(TRNID_FIRST_FIELD, codec.movePicX(orBlank(trnidFirst), TRNID_FIRST_LENGTH));
            images.put(TRNID_LAST_FIELD, codec.movePicX(orBlank(trnidLast), TRNID_LAST_LENGTH));
            images.put(PAGE_NUM_FIELD, codec.movePic9(pageNum, PAGE_NUM_LENGTH));
            images.put(NEXT_PAGE_FLG_FIELD,
                    codec.movePicX(orBlank(nextPageFlg), NEXT_PAGE_FLG_LENGTH));
            images.put(TRN_SEL_FLG_FIELD, codec.movePicX(orBlank(trnSelFlg), TRN_SEL_FLG_LENGTH));
            images.put(TRN_SELECTED_FIELD,
                    codec.movePicX(orBlank(trnSelected), TRN_SELECTED_LENGTH));
            return codec.serialise(LAYOUT, images);
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into an extension.
         *
         * <p>Character fields come back <strong>raw and untrimmed</strong> at their declared widths,
         * which is what makes the round trip byte-identical: rendering the result reproduces the
         * image it was read from because nothing was silently dropped on the way in.
         *
         * @param codec the codec, carrying the code page explicitly
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @return the extension the image denotes, never {@code null}
         * @throws NullPointerException     if {@code codec} or {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@value #RECORD_LENGTH}
         *                                  bytes, or the page-number span does not hold digits
         */
        public static Ct01Info fromFixedWidth(FixedWidthCodec codec, byte[] image) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to read CDEMO-CT01-INFO: "
                    + "the code page of a fixed-width image is always stated explicitly");
            Objects.requireNonNull(image, "A " + RECORD_LENGTH + "-byte image is required to read "
                    + "CDEMO-CT01-INFO; use the no-argument constructor for a fresh extension");
            Map<String, String> images = codec.deserialise(LAYOUT, image);
            Ct01Info info = new Ct01Info();
            info.trnidFirst = images.get(TRNID_FIRST_FIELD);
            info.trnidLast = images.get(TRNID_LAST_FIELD);
            info.pageNum = codec.decodePic9AsInt(images.get(PAGE_NUM_FIELD));
            info.nextPageFlg = images.get(NEXT_PAGE_FLG_FIELD);
            info.trnSelFlg = images.get(TRN_SEL_FLG_FIELD);
            info.trnSelected = images.get(TRN_SELECTED_FIELD);
            return info;
        }

        /**
         * Every piece of state in one canonical order; see the enclosing type's {@code stateValues}.
         *
         * @return every piece of state, in a fixed order, nulls permitted
         */
        private List<Object> stateValues() {
            return Arrays.asList(
                    trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg, trnSelected);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ct01Info that)) {
                return false;
            }
            return stateValues().equals(that.stateValues());
        }

        @Override
        public int hashCode() {
            return stateValues().hashCode();
        }

        @Override
        public String toString() {
            return "Ct01Info[trnidFirst='" + trnidFirst + "', trnidLast='" + trnidLast
                    + "', pageNum=" + pageNum + ", nextPageFlg='" + nextPageFlg
                    + "', trnSelFlg='" + trnSelFlg + "', trnSelected='" + trnSelected + "']";
        }
    }
}
