package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import jakarta.validation.constraints.Size;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound REST payload of {@code GET /api/transactions} - CSD transaction {@code CT00}, program
 * {@code app/cbl/COTRN00C.cbl} (699 lines) - as a field-for-field projection of the
 * {@code xxxI} items of {@code 01 COTRN0AI} in {@code app/cpy-bms/COTRN00.CPY}.
 *
 * <p>This is the largest map in the {@code transaction} package: {@value #FIELD_COUNT} payload
 * fields, reconciling as {@value #HEADER_FIELD_COUNT} header/control fields, {@value #ROW_COUNT}
 * rows of {@value #ROW_FIELD_COUNT} fields each, and {@value #ERROR_FIELD_COUNT} error line. It is a
 * paged browse/list screen.
 *
 * <h2>Provenance, and why the field names are spelled the way they are</h2>
 *
 * {@code app/bms/COTRN00.bms} declares 89 {@code DFHMDF} entries of which exactly
 * {@value #FIELD_COUNT} carry a name label; only the labelled ones have a symbolic-map item and
 * therefore only they become payload members. The 30 unlabelled entries are screen literals -
 * {@code 'Tran:'}, column headings, the PF-key legend - and they are not data. The counts agree three
 * independent ways: named {@code DFHMDF} = {@value #FIELD_COUNT}, {@code xxxI} items =
 * {@value #FIELD_COUNT}, {@code xxxO} items = {@value #FIELD_COUNT}. Every BMS {@code LENGTH=} equals
 * its copybook {@code PIC X(n)}, with no exceptions.
 *
 * <p><strong>The row-field suffixes are genuinely inconsistent in the source and are reproduced
 * exactly as spelled.</strong> This is the sharpest constraint in this file. The selector carries a
 * <em>four</em>-digit suffix, the identifier, date and description carry <em>two</em>, and the amount
 * carries <em>three</em>:
 *
 * <pre>
 *   SEL0001I  SEL0002I  ...  SEL0010I     four digits
 *   TRNID01I  TRNID02I  ...  TRNID10I     two digits
 *   TDATE01I  TDATE02I  ...  TDATE10I     two digits
 *   TDESC01I  TDESC02I  ...  TDESC10I     two digits
 *   TAMT001I  TAMT002I  ...  TAMT010I     THREE digits - not TAMT01I
 * </pre>
 *
 * Normalising any of these to a common width would break field-for-field diffing, which compares by
 * name. The names are therefore carried verbatim, and the four differing suffix widths are produced
 * by four separate, individually-named helpers - {@link #selectionFieldName(int)},
 * {@link #transactionIdFieldName(int)}, {@link #transactionDateFieldName(int)},
 * {@link #transactionDescriptionFieldName(int)} and {@link #transactionAmountFieldName(int)} - so no
 * single shared format string can quietly regularise them.
 *
 * <h2>The row block is regular - do not invent an exception</h2>
 *
 * All {@value #ROW_COUNT} rows carry the same {@value #ROW_FIELD_COUNT} fields in the same order.
 * Verified directly against both the copybook and the mapset. The sibling
 * {@code card/dto/CardListRequest} projects a map whose first row genuinely lacks a field; this map
 * has no such asymmetry, and asserting one here would be a fabrication.
 *
 * <h2>The byte geometry, which is machine-checked rather than merely documented</h2>
 *
 * Each symbolic-map field spends exactly {@value #FIELD_PREFIX_LENGTH} bytes of metadata before its
 * payload item - {@code xxxL} as {@code COMP PIC S9(4)} (2), {@code xxxF} as {@code PICTURE X} (1),
 * and a reserved {@code 02 FILLER PICTURE X(4)} (4) - after a single leading
 * {@value #TIOAPFX_PREFIX_LENGTH}-byte {@code TIOAPFX} prefix:
 *
 * <pre>
 *  01  COTRN0AI.
 *      02  FILLER PIC X(12).            &lt;- TIOAPFX prefix
 *      02  xxxL    COMP  PIC  S9(4).    &lt;- METADATA, length item
 *      02  xxxF    PICTURE X.           &lt;- METADATA, flag byte
 *      02  FILLER REDEFINES xxxF.
 *        03 xxxA   PICTURE X.           &lt;- METADATA, attribute view of the SAME byte
 *      02  FILLER   PICTURE X(4).       &lt;- reserved span
 *      02  xxxI  PIC X(n).              &lt;- PAYLOAD, stride = 7 + n
 * </pre>
 *
 * <table border="1">
 *   <caption>Width reconciliation of the {@code COTRN0AI} group</caption>
 *   <tr><th>Block</th><th>Payload bytes</th><th>Image bytes</th></tr>
 *   <tr><td>{@code TIOAPFX} prefix</td><td>0</td>
 *       <td>{@value #TIOAPFX_PREFIX_LENGTH}</td></tr>
 *   <tr><td>Header/control, {@value #HEADER_FIELD_COUNT} fields</td>
 *       <td>{@value #HEADER_PAYLOAD_LENGTH}</td><td>{@value #HEADER_IMAGE_LENGTH}</td></tr>
 *   <tr><td>Rows, {@value #ROW_COUNT} x {@value #ROW_FIELD_COUNT}</td>
 *       <td>{@value #ROW_BLOCK_PAYLOAD_LENGTH}</td>
 *       <td>{@value #ROW_BLOCK_IMAGE_LENGTH}</td></tr>
 *   <tr><td>Error line</td><td>{@value #ERRMSG_LENGTH}</td>
 *       <td>{@value #ERRMSG_IMAGE_LENGTH}</td></tr>
 *   <tr><td><strong>Total</strong></td>
 *       <td><strong>{@value #PAYLOAD_LENGTH}</strong></td>
 *       <td><strong>{@value #SYMBOLIC_MAP_LENGTH}</strong></td></tr>
 * </table>
 *
 * 132 + 630 + 78 = {@value #PAYLOAD_LENGTH}, and
 * {@value #TIOAPFX_PREFIX_LENGTH} + {@value #FIELD_COUNT} x {@value #FIELD_PREFIX_LENGTH} +
 * {@value #PAYLOAD_LENGTH} = {@value #SYMBOLIC_MAP_LENGTH}. That arithmetic is <em>enforced</em>:
 * {@link #LAYOUT} declares every span at an absolute offset and hands the literal
 * {@value #SYMBOLIC_MAP_LENGTH} to {@link RecordLayout}, whose constructor refuses to build a layout
 * whose storage spans leave a gap, overlap, or fail to sum to exactly that total. A mistyped width
 * therefore fails at class initialisation, naming the offending descriptor, instead of silently
 * shifting every field after it.
 *
 * <h2>{@code xxxI} and {@code xxxO} are the same bytes</h2>
 *
 * {@code 01 COTRN0AO REDEFINES COTRN0AI} at line 373 of the copybook, and both views spend exactly
 * {@value #FIELD_PREFIX_LENGTH} prefix bytes per field - the input view as {@code 2 + 1 + 4}, the
 * output view as {@code 3 + 1 + 1 + 1 + 1} - so each field's {@code I} and {@code O} items sit at the
 * <em>identical</em> offset. They are storage aliases, not distinct fields.
 * {@code app/cbl/COTRN00C.cbl} writes through both and mixes them freely: line 324 writes
 * {@code PAGENUMI OF COTRN0AI} for an output-only field, and line 325 immediately writes
 * {@code TRNIDINO OF COTRN0AO} for an input field. That is why this request and its paired response
 * are field-identical - the split is a directional projection <em>convention</em> over one COBOL
 * buffer, not a difference in the buffer. Round-tripping through this type must therefore be
 * lossless, and no {@code xxxI} item may be treated as read-only.
 *
 * <h2>All {@value #FIELD_COUNT} fields are projected, including the output-only ones</h2>
 *
 * Exactly 11 of the {@value #FIELD_COUNT} are {@code UNPROT} - {@code TRNIDIN} plus
 * {@code SEL0001} through {@code SEL0010}. The other 48 are {@code ASKIP}, that is output-only. All
 * {@value #FIELD_COUNT} nevertheless carry {@code FSET}, so CICS returns every one of them in the
 * inbound datastream on {@code RECEIVE MAP}, which is precisely why the symbolic map declares an
 * {@code xxxI} item for each. Every field is projected, and a field a given program path leaves blank
 * is projected too.
 *
 * <h2>Every field is a {@link String} at its declared width</h2>
 *
 * There is no {@code BigDecimal}, {@code double} or {@code float} anywhere in this type, and it
 * performs no arithmetic. Two cases deserve naming:
 *
 * <ul>
 *   <li>{@code TAMT001I} through {@code TAMT010I} are {@code PIC X(12)} <strong>edited</strong>
 *       amounts, not raw record fields. {@code app/cpy/CVTRA05Y.cpy} declares
 *       {@code TRAN-AMT PIC S9(09)V99}, eleven bytes, but the screen carries the edit form:
 *       {@code app/cbl/COTRN00C.cbl:56} declares {@code 05 WS-TRAN-AMT PIC +99999999.99} - a sign,
 *       eight integer digits, a decimal point and two fraction digits, exactly 12 characters - and
 *       lines 396 to 442 move that into {@code TAMT00nI}. A blank row holds
 *       <strong>spaces</strong>, because lines 457 to 502 do {@code MOVE SPACES TO TAMT00nI}; it is
 *       neither {@code "0.00"} nor {@code null}.</li>
 *   <li>{@code PAGENUMI} is {@code PIC X(8)} and therefore a {@link String}, even though the
 *       pagination cursor's {@code CDEMO-CT00-PAGE-NUM} is {@code PIC 9(08)}.
 *       {@code app/cbl/COTRN00C.cbl:324} moves the numeric item into the alphanumeric screen item,
 *       and the screen item's {@code PICTURE} is what this member's type follows.</li>
 * </ul>
 *
 * Converting an edited amount back to a number, and all page arithmetic, belong to the controller.
 *
 * <h2>There is no server-side session</h2>
 *
 * CICS is pseudo-conversational: the transaction paints a screen, ends, and is re-entered from the
 * beginning, and the only state that survives is what it handed back in its communication area. That
 * shape is preserved exactly. The {@link NavigationContext} COMMAREA, the {@link PaginationCursor}
 * browse position and the enter-versus-re-enter context all travel in this payload. This type is
 * deliberately free of {@code HttpSession}, {@code @SessionAttributes}, {@code ThreadLocal}, any
 * server-side cache and any static "current request" holder - a static holder would be a session by
 * another name and would additionally break request isolation.
 *
 * <h2>Serialisation</h2>
 *
 * Member names are chosen so that Jackson's <em>default</em> property derivation already yields the
 * copybook base name lower-cased: {@code getTamt001()} yields {@code tamt001}, {@code getSel0010()}
 * yields {@code sel0010}. That is the same wire name the paired {@code TransactionListResponse} pins
 * with {@code @JsonProperty}, so the two sides name the same {@code xxxI} items and a response can be
 * sent back as the next request. No naming strategy, {@code @JsonInclude}, {@code @JsonNaming} or
 * {@code ObjectMapper} is declared here, because {@code config/WebConfig} owns that module-wide and
 * two competing declarations is how a payload silently changes shape.
 *
 * <p>Space padding is data. Every setter stores its value at exactly the declared width through the
 * {@code PIC X} move rule, so a 78-character space-padded {@code ERRMSG} survives a JSON round trip
 * untrimmed and compares equal.
 *
 * @see TransactionListRequest.PaginationCursor
 * @see TransactionListRequest.FieldMetadata
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
public final class TransactionListRequest {

    // =================================================================================================
    // Provenance. Recorded as constants rather than prose so a test can assert the binding between this
    // type and the CICS resources it projects.
    // =================================================================================================

    /** CSD transaction identifier, {@code app/csd/CARDDEMO.CSD:419}; {@code WS-TRANID} at L37. */
    public static final String TRANSACTION_ID = "CT00";

    /** Backing program, {@code app/csd/CARDDEMO.CSD:257}; {@code WS-PGMNAME} at L36. */
    public static final String PROGRAM_NAME = "COTRN00C";

    /** BMS mapset, {@code app/bms/COTRN00.bms} {@code DFHMSD}; {@code app/csd/CARDDEMO.CSD:145}. */
    public static final String MAPSET_NAME = "COTRN00";

    /** BMS map, the {@code DFHMDI} label; {@code SIZE=(24,80)}, {@code COLUMN=1}, {@code LINE=1}. */
    public static final String MAP_NAME = "COTRN0A";

    /** The projected symbolic-map group, {@code app/cpy-bms/COTRN00.CPY:17}. */
    public static final String SYMBOLIC_MAP_INPUT_GROUP = "COTRN0AI";

    /** Its {@code REDEFINES} alias, {@code app/cpy-bms/COTRN00.CPY:373} - the same bytes. */
    public static final String SYMBOLIC_MAP_OUTPUT_GROUP = "COTRN0AO";

    // =================================================================================================
    // Field counts and the page size.
    // =================================================================================================

    /**
     * The page size: exactly {@value #PAGE_SIZE} transactions per screen.
     *
     * <p><strong>This is behaviour, not configuration.</strong> It is a compile-time constant, and it
     * is deliberately not an {@code application.yml} key, not a {@code @Value} injection and not a
     * request parameter. {@code app/cbl/COTRN00C.cbl} hard-codes it in four places: the forward
     * initialise loop bounds it with {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10} at
     * line 290, the forward read loop with {@code PERFORM UNTIL WS-IDX >= 11} at line 297, and the
     * backward path seeds {@code MOVE 10 TO WS-IDX} at line 349 before running
     * {@code UNTIL WS-IDX <= 0} at line 351. Making it tunable would let a caller produce a page this
     * screen cannot render and a page count the COBOL would never compute.
     *
     * <p>It equals {@link #ROW_COUNT} <em>by construction</em>: the row count is defined from it, so
     * the two cannot drift apart.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * The number of transaction rows the map declares, {@value #ROW_COUNT} - defined from
     * {@link #PAGE_SIZE} so that the modelled rows and the page size are necessarily the same number.
     */
    public static final int ROW_COUNT = PAGE_SIZE;

    /** Fields in the header/control block: {@value #HEADER_FIELD_COUNT}. */
    public static final int HEADER_FIELD_COUNT = 8;

    /** Fields in one transaction row: {@value #ROW_FIELD_COUNT}. */
    public static final int ROW_FIELD_COUNT = 5;

    /** Fields in the error line: {@value #ERROR_FIELD_COUNT}. */
    public static final int ERROR_FIELD_COUNT = 1;

    /**
     * Total payload fields: {@value #FIELD_COUNT}, reconciling as
     * {@value #HEADER_FIELD_COUNT} + {@value #ROW_COUNT} x {@value #ROW_FIELD_COUNT} +
     * {@value #ERROR_FIELD_COUNT}. Equal to the named {@code DFHMDF} count of
     * {@code app/bms/COTRN00.bms} and to the {@code xxxI} count of {@code app/cpy-bms/COTRN00.CPY}.
     */
    public static final int FIELD_COUNT =
            HEADER_FIELD_COUNT + ROW_COUNT * ROW_FIELD_COUNT + ERROR_FIELD_COUNT;

    /**
     * The value {@code MOVE -1 TO xxxL} places in a length item to ask CICS to position the cursor at
     * that field. {@code app/cbl/COTRN00C.cbl} does this at 13 sites, all on {@code TRNIDINL}: lines
     * 105, 131, 201, 216, 221, 243, 265, 610, 617, 644, 651, 678 and 685. It is why
     * {@link FieldMetadata#getLengthItem()} is a <em>signed</em> type.
     */
    public static final short CURSOR_POSITION_REQUEST = -1;

    // =================================================================================================
    // Declared widths, taken from the xxxI PICTURE clauses of app/cpy-bms/COTRN00.CPY. Each equals its
    // BMS LENGTH= in app/bms/COTRN00.bms; the two were reconciled field by field with zero mismatches.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)}, {@code DFHMDF POS=(1,7)}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01I PIC X(40)}, {@code DFHMDF POS=(1,21)}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEI PIC X(8)}, {@code DFHMDF POS=(1,71) INITIAL='mm/dd/yy'}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEI PIC X(8)}, {@code DFHMDF POS=(2,7)}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02I PIC X(40)}, {@code DFHMDF POS=(2,21)}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEI PIC X(8)}, {@code DFHMDF POS=(2,71) INITIAL='hh:mm:ss'}. */
    public static final int CURTIME_LENGTH = 8;

    /** {@code PAGENUMI PIC X(8)}, {@code DFHMDF POS=(4,71)} - alphanumeric, see the class notes. */
    public static final int PAGENUM_LENGTH = 8;

    /** {@code TRNIDINI PIC X(16)}, {@code DFHMDF POS=(6,21) ATTRB=(FSET,NORM,UNPROT)}. */
    public static final int TRNIDIN_LENGTH = 16;

    /** {@code SEL000nI PIC X(1)}, column 3 - the row selector, {@code UNPROT}. */
    public static final int SELECTION_LENGTH = 1;

    /** {@code TRNIDnnI PIC X(16)}, column 8 - matches {@code TRAN-ID PIC X(16)} exactly. */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /** {@code TDATEnnI PIC X(8)}, column 27 - {@code WS-TRAN-DATE}, {@code mm/dd/yy}. */
    public static final int TRANSACTION_DATE_LENGTH = 8;

    /** {@code TDESCnnI PIC X(26)}, column 38 - {@code TRAN-DESC PIC X(100)} truncated on the right. */
    public static final int TRANSACTION_DESCRIPTION_LENGTH = 26;

    /** {@code TAMT00nI PIC X(12)}, column 67 - the {@code +99999999.99} edit form. */
    public static final int TRANSACTION_AMOUNT_LENGTH = 12;

    /** {@code ERRMSGI PIC X(78)}, {@code DFHMDF POS=(23,1) ATTRB=(ASKIP,BRT,FSET) COLOR=RED}. */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Characters in the {@code EIBAID} token carried by {@link #getAid()}: five.
     *
     * <p><strong>Not a screen field.</strong> It is absent from {@link #FIELD_NAMES}, from
     * {@link #FIELD_COUNT} and from the {@value #SYMBOLIC_MAP_LENGTH}-byte image, because
     * {@code EIBAID} is not part of {@code 01 COTRN0AI} at all - CICS reports it in the exec interface
     * block, beside the map rather than inside it. It is one of the mandated exceptions to the
     * one-member-per-{@code DFHMDF} rule, along with the communication area and its cursor.
     *
     * <p>It has to be here because {@code app/cbl/COTRN00C.cbl:119-134} decides what the transaction
     * does by evaluating it and nothing else - {@code EVALUATE EIBAID} with four named arms and a
     * default:
     *
     * <ul>
     *   <li>{@code WHEN DFHENTER} performs {@code PROCESS-ENTER-KEY}, which acts on whichever row the
     *       operator selected;</li>
     *   <li>{@code WHEN DFHPF3} moves {@code 'COMEN01C'} into {@code CDEMO-TO-PROGRAM} and returns to
     *       the previous screen;</li>
     *   <li>{@code WHEN DFHPF7} performs {@code PROCESS-PF7-KEY} - page backwards;</li>
     *   <li>{@code WHEN DFHPF8} performs {@code PROCESS-PF8-KEY} - page forwards;</li>
     *   <li>{@code WHEN OTHER} raises {@code CCDA-MSG-INVALID-KEY} and repositions the cursor.</li>
     * </ul>
     *
     * <p>Paging is the whole point of this screen, and both directions live entirely on this member:
     * without it {@code PROCESS-PF7-KEY} and {@code PROCESS-PF8-KEY} are unreachable through the API,
     * and the {@value PaginationCursor#CURSOR_LENGTH}-byte cursor this payload carries could never be
     * advanced or rewound. A server-side record of the last key pressed is the one thing rule R6
     * forbids, so the key travels in the payload.
     *
     * <p>The width is the module's convention: {@code COTRN00C} copies neither {@code CVCRD01Y} nor
     * {@code CSSTRPFY} and tests the raw {@code EIBAID} byte inline, so five characters is taken from
     * {@code 10 CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy} and from the width
     * {@code common.PfKeyResolver.AID_TOKEN_LENGTH} publishes. The four tokens this screen acts on are
     * {@code ENTER}, {@code PFK03}, {@code PFK07} and {@code PFK08}; anything else, spaces included, is
     * the {@code WHEN OTHER} arm. {@code common.PfKeyResolver.AidKey#token()} space-pads the shorter
     * mnemonics to this width, so a caller must not trim what it produces.
     */
    public static final int AID_LENGTH = 5;

    /** Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field. */
    public static final String AID_FIELD = "EIBAID";

    // =================================================================================================
    // Derived geometry. Every one of these is arithmetic over the widths above, never a re-typed
    // literal, so a corrected width propagates instead of leaving a stale total behind.
    // =================================================================================================

    /** The leading {@code 02 FILLER PIC X(12)} of the group - the {@code TIOAPFX=YES} prefix. */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * Metadata bytes preceding each payload item: {@value #FIELD_PREFIX_LENGTH}. In the input view
     * that is {@code xxxL} (2, {@code COMP PIC S9(4)}) + {@code xxxF} (1) + {@code FILLER X(4)}; in
     * the output view it is {@code FILLER X(3)} + {@code xxxC} + {@code xxxP} + {@code xxxH} +
     * {@code xxxV}. The two are equal, which is what makes the {@code I} and {@code O} items aliases.
     */
    public static final int FIELD_PREFIX_LENGTH = 7;

    /** Payload bytes of the header/control block: {@value #HEADER_PAYLOAD_LENGTH}. */
    public static final int HEADER_PAYLOAD_LENGTH =
            TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH + PGMNAME_LENGTH
                    + TITLE02_LENGTH + CURTIME_LENGTH + PAGENUM_LENGTH + TRNIDIN_LENGTH;

    /** Image bytes of the header/control block: {@value #HEADER_IMAGE_LENGTH}. */
    public static final int HEADER_IMAGE_LENGTH =
            HEADER_PAYLOAD_LENGTH + HEADER_FIELD_COUNT * FIELD_PREFIX_LENGTH;

    /** Payload bytes of one transaction row: {@value #ROW_PAYLOAD_LENGTH}. */
    public static final int ROW_PAYLOAD_LENGTH =
            SELECTION_LENGTH + TRANSACTION_ID_LENGTH + TRANSACTION_DATE_LENGTH
                    + TRANSACTION_DESCRIPTION_LENGTH + TRANSACTION_AMOUNT_LENGTH;

    /**
     * Image bytes of one transaction row: {@value #ROW_IMAGE_LENGTH}. This, not
     * {@value #ROW_PAYLOAD_LENGTH}, is the stride between one row and the next in the group image,
     * because each of the row's {@value #ROW_FIELD_COUNT} fields carries its own
     * {@value #FIELD_PREFIX_LENGTH}-byte prefix.
     */
    public static final int ROW_IMAGE_LENGTH =
            ROW_PAYLOAD_LENGTH + ROW_FIELD_COUNT * FIELD_PREFIX_LENGTH;

    /** Payload bytes of the whole row block: {@value #ROW_BLOCK_PAYLOAD_LENGTH}. */
    public static final int ROW_BLOCK_PAYLOAD_LENGTH = ROW_COUNT * ROW_PAYLOAD_LENGTH;

    /** Image bytes of the whole row block: {@value #ROW_BLOCK_IMAGE_LENGTH}. */
    public static final int ROW_BLOCK_IMAGE_LENGTH = ROW_COUNT * ROW_IMAGE_LENGTH;

    /** Image bytes of the error line: {@value #ERRMSG_IMAGE_LENGTH}. */
    public static final int ERRMSG_IMAGE_LENGTH = ERRMSG_LENGTH + FIELD_PREFIX_LENGTH;

    /**
     * Total payload bytes across all {@value #FIELD_COUNT} fields: {@value #PAYLOAD_LENGTH}, being
     * {@value #HEADER_PAYLOAD_LENGTH} + {@value #ROW_BLOCK_PAYLOAD_LENGTH} +
     * {@value #ERRMSG_LENGTH}.
     */
    public static final int PAYLOAD_LENGTH =
            HEADER_PAYLOAD_LENGTH + ROW_BLOCK_PAYLOAD_LENGTH + ERRMSG_LENGTH;

    /**
     * Total bytes of the {@code COTRN0AI} group image: {@value #SYMBOLIC_MAP_LENGTH}, being
     * {@value #TIOAPFX_PREFIX_LENGTH} + {@value #FIELD_COUNT} x {@value #FIELD_PREFIX_LENGTH} +
     * {@value #PAYLOAD_LENGTH}. {@link #LAYOUT} is declared against this figure and fails to
     * initialise if the spans do not account for it exactly.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + FIELD_COUNT * FIELD_PREFIX_LENGTH + PAYLOAD_LENGTH;

    // =================================================================================================
    // Field names, carried VERBATIM as the mapset labels them and the copybook spells them. These are
    // the names the parity differ compares by, so a "tidied up" name would make a real difference
    // invisible. The base name is the DFHMDF label; the symbolic-map items append I, L, F and A to it.
    // =================================================================================================

    /** Base name of the transaction-identifier header field: {@code TRNNAME}. */
    public static final String TRNNAME_FIELD = "TRNNAME";

    /** Base name of the first title line: {@code TITLE01}. */
    public static final String TITLE01_FIELD = "TITLE01";

    /** Base name of the current-date header field: {@code CURDATE}. */
    public static final String CURDATE_FIELD = "CURDATE";

    /** Base name of the program-name header field: {@code PGMNAME}. */
    public static final String PGMNAME_FIELD = "PGMNAME";

    /** Base name of the second title line: {@code TITLE02}. */
    public static final String TITLE02_FIELD = "TITLE02";

    /** Base name of the current-time header field: {@code CURTIME}. */
    public static final String CURTIME_FIELD = "CURTIME";

    /** Base name of the displayed page number: {@code PAGENUM}. */
    public static final String PAGENUM_FIELD = "PAGENUM";

    /** Base name of the browse-start key: {@code TRNIDIN} - one of the 11 {@code UNPROT} fields. */
    public static final String TRNIDIN_FIELD = "TRNIDIN";

    /** Base name of the error line: {@code ERRMSG}. */
    public static final String ERRMSG_FIELD = "ERRMSG";

    /**
     * Prefix of the row selector fields. Completed with a <strong>four</strong>-digit, zero-padded
     * row number by {@link #selectionFieldName(int)}, giving {@code SEL0001} through {@code SEL0010}.
     */
    public static final String SELECTION_FIELD_PREFIX = "SEL";

    /**
     * Prefix of the row transaction-identifier fields. Completed with a <strong>two</strong>-digit
     * row number by {@link #transactionIdFieldName(int)}, giving {@code TRNID01} through
     * {@code TRNID10}.
     */
    public static final String TRANSACTION_ID_FIELD_PREFIX = "TRNID";

    /**
     * Prefix of the row date fields. Completed with a <strong>two</strong>-digit row number by
     * {@link #transactionDateFieldName(int)}, giving {@code TDATE01} through {@code TDATE10}.
     */
    public static final String TRANSACTION_DATE_FIELD_PREFIX = "TDATE";

    /**
     * Prefix of the row description fields. Completed with a <strong>two</strong>-digit row number by
     * {@link #transactionDescriptionFieldName(int)}, giving {@code TDESC01} through {@code TDESC10}.
     */
    public static final String TRANSACTION_DESCRIPTION_FIELD_PREFIX = "TDESC";

    /**
     * Prefix of the row amount fields. Completed with a <strong>three</strong>-digit row number by
     * {@link #transactionAmountFieldName(int)}, giving {@code TAMT001} through {@code TAMT010} - and
     * emphatically not {@code TAMT01}.
     */
    public static final String TRANSACTION_AMOUNT_FIELD_PREFIX = "TAMT";

    /** Suffix that forms a symbolic-map input (payload) item from a base name: {@code I}. */
    public static final String INPUT_ITEM_SUFFIX = "I";

    /** Suffix that forms the length metadata item from a base name: {@code L}. */
    public static final String LENGTH_ITEM_SUFFIX = "L";

    /** Suffix that forms the flag metadata item from a base name: {@code F}. */
    public static final String FLAG_ITEM_SUFFIX = "F";

    /** Suffix that forms the attribute metadata item from a base name: {@code A}. */
    public static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    /**
     * The {@value #FIELD_COUNT} base field names in copybook and mapset declaration order: the eight
     * header/control fields, then {@value #ROW_COUNT} rows of {@value #ROW_FIELD_COUNT}, then the
     * error line. Immutable, and the order is the order the layout and the screen use.
     */
    public static final List<String> FIELD_NAMES = buildFieldNames();

    /**
     * The {@value #FIELD_COUNT} symbolic-map payload item names - {@link #FIELD_NAMES} with
     * {@value #INPUT_ITEM_SUFFIX} appended - in declaration order. Immutable. These are the names
     * {@link #LAYOUT} declares its payload spans under.
     */
    public static final List<String> INPUT_ITEM_NAMES = buildInputItemNames();

    // =================================================================================================
    // Name construction. Four separate helpers rather than one shared format string, precisely because
    // the four suffix widths differ: a single helper is how the difference would get regularised away.
    // =================================================================================================

    /**
     * The verbatim name of a row's selector field, with a <strong>four</strong>-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return {@code SEL0001} for row 1 through {@code SEL0010} for row {@value #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public static String selectionFieldName(int oneBasedRow) {
        return SELECTION_FIELD_PREFIX + fourDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's transaction-identifier field, with a <strong>two</strong>-digit
     * suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return {@code TRNID01} for row 1 through {@code TRNID10} for row {@value #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public static String transactionIdFieldName(int oneBasedRow) {
        return TRANSACTION_ID_FIELD_PREFIX + twoDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's date field, with a <strong>two</strong>-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return {@code TDATE01} for row 1 through {@code TDATE10} for row {@value #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public static String transactionDateFieldName(int oneBasedRow) {
        return TRANSACTION_DATE_FIELD_PREFIX + twoDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's description field, with a <strong>two</strong>-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return {@code TDESC01} for row 1 through {@code TDESC10} for row {@value #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public static String transactionDescriptionFieldName(int oneBasedRow) {
        return TRANSACTION_DESCRIPTION_FIELD_PREFIX + twoDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's amount field, with a <strong>three</strong>-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return {@code TAMT001} for row 1 through {@code TAMT010} for row {@value #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public static String transactionAmountFieldName(int oneBasedRow) {
        return TRANSACTION_AMOUNT_FIELD_PREFIX + threeDigitRow(oneBasedRow);
    }

    /**
     * The symbolic-map payload item name of a base field name, that is the {@code xxxI} item.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code TAMT001}
     * @return the base name with {@value #INPUT_ITEM_SUFFIX} appended, for example {@code TAMT001I}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String inputItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + INPUT_ITEM_SUFFIX;
    }

    /**
     * The length metadata item name of a base field name, that is the {@code xxxL} item declared
     * {@code COMP PIC S9(4)}.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code TRNIDIN}
     * @return the base name with {@value #LENGTH_ITEM_SUFFIX} appended, for example {@code TRNIDINL}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String lengthItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + LENGTH_ITEM_SUFFIX;
    }

    /**
     * The flag metadata item name of a base field name, that is the {@code xxxF} item declared
     * {@code PICTURE X}.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code ERRMSG}
     * @return the base name with {@value #FLAG_ITEM_SUFFIX} appended, for example {@code ERRMSGF}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String flagItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + FLAG_ITEM_SUFFIX;
    }

    /**
     * The attribute metadata item name of a base field name, that is the {@code xxxA} item which
     * {@code REDEFINES} the flag byte and therefore shares its storage.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code ERRMSG}
     * @return the base name with {@value #ATTRIBUTE_ITEM_SUFFIX} appended, for example
     *         {@code ERRMSGA}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String attributeItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + ATTRIBUTE_ITEM_SUFFIX;
    }

    /**
     * Validates a COBOL screen row subscript.
     *
     * <p>COBOL screen rows are <strong>1-based</strong>: {@code SEL0001} is row 1 and there is no row
     * 0. Java lists are 0-based. That one-element shift is the single most common defect in a
     * migration of this kind, so every row-addressed operation on this type takes the COBOL subscript
     * and converts it in exactly one place - here and in
     * {@link FixedWidthRecord#occursElementOffsetOneBased(int, int, int, int)}.
     *
     * @param oneBasedRow the COBOL screen row
     * @return {@code oneBasedRow}, unchanged, so this reads naturally inline
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is below 1 or above
     *                                   {@value #ROW_COUNT}
     */
    public static int requireValidRow(int oneBasedRow) {
        if (oneBasedRow < 1 || oneBasedRow > ROW_COUNT) {
            throw new IndexOutOfBoundsException("Screen row " + oneBasedRow + " is outside 1.."
                    + ROW_COUNT + "; COBOL screen rows are 1-based, so the first row is row 1 and "
                    + "there is no row 0");
        }
        return oneBasedRow;
    }

    private static String twoDigitRow(int oneBasedRow) {
        return padRowNumber(requireValidRow(oneBasedRow), 2);
    }

    private static String threeDigitRow(int oneBasedRow) {
        return padRowNumber(requireValidRow(oneBasedRow), 3);
    }

    private static String fourDigitRow(int oneBasedRow) {
        return padRowNumber(requireValidRow(oneBasedRow), 4);
    }

    /**
     * Renders a validated row number zero-padded on the left to the requested suffix width. Written
     * without {@code String.format} so the padding is visible and no locale can influence a digit.
     */
    private static String padRowNumber(int oneBasedRow, int suffixWidth) {
        String digits = Integer.toString(oneBasedRow);
        StringBuilder padded = new StringBuilder(suffixWidth);
        for (int i = digits.length(); i < suffixWidth; i++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    private static String requireFieldName(String baseFieldName) {
        Objects.requireNonNull(baseFieldName, "A base field name is required; pass one of "
                + "FIELD_NAMES, or a name built by selectionFieldName, transactionIdFieldName, "
                + "transactionDateFieldName, transactionDescriptionFieldName or "
                + "transactionAmountFieldName");
        return baseFieldName;
    }

    private static List<String> buildFieldNames() {
        List<String> names = new ArrayList<>(FIELD_COUNT);
        names.add(TRNNAME_FIELD);
        names.add(TITLE01_FIELD);
        names.add(CURDATE_FIELD);
        names.add(PGMNAME_FIELD);
        names.add(TITLE02_FIELD);
        names.add(CURTIME_FIELD);
        names.add(PAGENUM_FIELD);
        names.add(TRNIDIN_FIELD);
        for (int row = 1; row <= ROW_COUNT; row++) {
            names.add(selectionFieldName(row));
            names.add(transactionIdFieldName(row));
            names.add(transactionDateFieldName(row));
            names.add(transactionDescriptionFieldName(row));
            names.add(transactionAmountFieldName(row));
        }
        names.add(ERRMSG_FIELD);
        if (names.size() != FIELD_COUNT) {
            throw new IllegalStateException("Built " + names.size() + " field name(s) for a map "
                    + "that declares " + FIELD_COUNT + "; the header, row and error blocks must "
                    + "reconcile as " + HEADER_FIELD_COUNT + " + " + ROW_COUNT + " x "
                    + ROW_FIELD_COUNT + " + " + ERROR_FIELD_COUNT);
        }
        return List.copyOf(names);
    }

    private static List<String> buildInputItemNames() {
        List<String> items = new ArrayList<>(FIELD_COUNT);
        for (String baseName : FIELD_NAMES) {
            items.add(inputItemName(baseName));
        }
        return List.copyOf(items);
    }

    // =================================================================================================
    // Absolute 0-based offsets of the payload items within the COTRN0AI group image. Each is arithmetic
    // over its predecessor plus that field's FIELD_PREFIX_LENGTH, never a re-typed literal, so the
    // chain cannot go stale. The first header field sits at TIOAPFX_PREFIX_LENGTH + FIELD_PREFIX_LENGTH.
    // =================================================================================================

    /** Offset of {@code TRNNAMEI}: {@value #TRNNAME_OFFSET}. */
    public static final int TRNNAME_OFFSET = TIOAPFX_PREFIX_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code TITLE01I}: {@value #TITLE01_OFFSET}. */
    public static final int TITLE01_OFFSET = TRNNAME_OFFSET + TRNNAME_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code CURDATEI}: {@value #CURDATE_OFFSET}. */
    public static final int CURDATE_OFFSET = TITLE01_OFFSET + TITLE01_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code PGMNAMEI}: {@value #PGMNAME_OFFSET}. */
    public static final int PGMNAME_OFFSET = CURDATE_OFFSET + CURDATE_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code TITLE02I}: {@value #TITLE02_OFFSET}. */
    public static final int TITLE02_OFFSET = PGMNAME_OFFSET + PGMNAME_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code CURTIMEI}: {@value #CURTIME_OFFSET}. */
    public static final int CURTIME_OFFSET = TITLE02_OFFSET + TITLE02_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code PAGENUMI}: {@value #PAGENUM_OFFSET}. */
    public static final int PAGENUM_OFFSET = CURTIME_OFFSET + CURTIME_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code TRNIDINI}: {@value #TRNIDIN_OFFSET}. */
    public static final int TRNIDIN_OFFSET = PAGENUM_OFFSET + PAGENUM_LENGTH + FIELD_PREFIX_LENGTH;

    /**
     * Offset at which the row block begins - the first byte of row 1's metadata prefix, not of its
     * first payload item: {@value #ROW_BLOCK_OFFSET}. Row offsets are derived from it through
     * {@link FixedWidthRecord#occursElementOffsetOneBased(int, int, int, int)}, which is the one place
     * the 1-based-to-0-based conversion happens.
     */
    public static final int ROW_BLOCK_OFFSET = TRNIDIN_OFFSET + TRNIDIN_LENGTH;

    /** Offset of {@code SEL000nI} within its row image: {@value #SELECTION_ROW_OFFSET}. */
    public static final int SELECTION_ROW_OFFSET = FIELD_PREFIX_LENGTH;

    /** Offset of {@code TRNIDnnI} within its row image: {@value #TRANSACTION_ID_ROW_OFFSET}. */
    public static final int TRANSACTION_ID_ROW_OFFSET =
            SELECTION_ROW_OFFSET + SELECTION_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code TDATEnnI} within its row image: {@value #TRANSACTION_DATE_ROW_OFFSET}. */
    public static final int TRANSACTION_DATE_ROW_OFFSET =
            TRANSACTION_ID_ROW_OFFSET + TRANSACTION_ID_LENGTH + FIELD_PREFIX_LENGTH;

    /**
     * Offset of {@code TDESCnnI} within its row image:
     * {@value #TRANSACTION_DESCRIPTION_ROW_OFFSET}.
     */
    public static final int TRANSACTION_DESCRIPTION_ROW_OFFSET =
            TRANSACTION_DATE_ROW_OFFSET + TRANSACTION_DATE_LENGTH + FIELD_PREFIX_LENGTH;

    /** Offset of {@code TAMT00nI} within its row image: {@value #TRANSACTION_AMOUNT_ROW_OFFSET}. */
    public static final int TRANSACTION_AMOUNT_ROW_OFFSET =
            TRANSACTION_DESCRIPTION_ROW_OFFSET + TRANSACTION_DESCRIPTION_LENGTH
                    + FIELD_PREFIX_LENGTH;

    /** Offset of {@code ERRMSGI}: {@value #ERRMSG_OFFSET}, the last payload item in the group. */
    public static final int ERRMSG_OFFSET =
            ROW_BLOCK_OFFSET + ROW_BLOCK_IMAGE_LENGTH + FIELD_PREFIX_LENGTH;

    // =================================================================================================
    // The layout of the COTRN0AI group image.
    // =================================================================================================

    /**
     * Every span of the {@code COTRN0AI} group image, in copybook declaration order, totalling
     * {@value #SYMBOLIC_MAP_LENGTH} bytes.
     *
     * <p>Declaring it does real work rather than documenting: {@link RecordLayout}'s constructor
     * rejects a layout that leaves a gap, that overlaps, or whose storage spans do not sum to exactly
     * the declared length. So the assertion
     * {@value #TIOAPFX_PREFIX_LENGTH} + {@value #FIELD_COUNT} x {@value #FIELD_PREFIX_LENGTH} +
     * {@value #PAYLOAD_LENGTH} = {@value #SYMBOLIC_MAP_LENGTH} is checked at class initialisation, and
     * a single mistyped width fails the build with the offending descriptor named.
     *
     * <h4>Why each field's {@value #FIELD_PREFIX_LENGTH}-byte prefix is declared as one
     * {@code FILLER}</h4>
     *
     * The three metadata items in that prefix are {@code xxxL}, declared {@code COMP PIC S9(4)} - a
     * two-byte <em>binary</em> halfword - {@code xxxF}, declared {@code PICTURE X}, and a reserved
     * {@code FILLER X(4)}. {@link FixedWidthRecord.PictureKind} deliberately offers no binary
     * category: its four kinds describe character data, zoned {@code DISPLAY} digits and reserved
     * space. Declaring {@code xxxL} as a signed-scaled span would assert zoned-decimal storage with a
     * trailing sign overpunch, which is simply not how a {@code COMP} halfword is encoded, and a
     * later reader could then decode it with the wrong routine. Stating what the span actually is -
     * bytes this type reserves and never decodes - is the honest declaration, and the three items keep
     * their real semantics where those semantics belong: in {@link FieldMetadata}, which types the
     * length item as a signed 16-bit value and models the flag and attribute bytes as the aliases they
     * are.
     *
     * <p>The {@value #FIELD_COUNT} <em>payload</em> spans carry the verbatim {@code xxxI} item names,
     * which are the names field-for-field diffing compares by.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    /**
     * The one {@code PICTURE}-rule implementation this type reaches for outside a byte boundary.
     *
     * <p>Only {@link FixedWidthCodec#movePic9(long, int)} is taken from it now, to render
     * {@code CDEMO-CT00-PAGE-NUM} as its eight zero-filled digits. The {@code PIC X} setters do
     * <strong>not</strong> use it: they validate through {@link #requirePicX(String, int, String)} and
     * store what they are given, and the alphanumeric {@code MOVE} is applied once at the byte
     * boundary instead - see that method for why.
     *
     * <p>{@link FixedWidthCodec#movePic9(long, int)} is a <em>character-level</em> operation that
     * converts nothing to bytes, so the code page this instance carries takes no part in the result.
     * It is named {@link StandardCharsets#US_ASCII} explicitly and never derived from the platform,
     * and it is the code page of the authoritative fixtures under {@code app/data/ASCII}. Every actual
     * byte boundary - {@link #toFixedWidth(Charset)}, {@link #writeInto(FixedWidthRecord)},
     * {@link #fromFixedWidth(byte[], Charset)} and {@link #readFrom(FixedWidthRecord)} - takes its
     * code page from the caller instead.
     *
     * <p>{@code FixedWidthCodec} is immutable and holds only its {@link Charset}, so one shared
     * instance is thread safe and is a constant rather than static mutable state. Holding it here,
     * rather than reimplementing pad-and-truncate, keeps one reviewable implementation of the rule.
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * Builds {@link #LAYOUT}: the leading {@code TIOAPFX} filler, then for each of the
     * {@value #FIELD_COUNT} fields in declaration order its {@value #FIELD_PREFIX_LENGTH}-byte
     * metadata prefix followed by its payload span.
     */
    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + 2 * FIELD_COUNT);
        spans.add(FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        appendField(spans, TRNNAME_FIELD, TRNNAME_OFFSET, TRNNAME_LENGTH);
        appendField(spans, TITLE01_FIELD, TITLE01_OFFSET, TITLE01_LENGTH);
        appendField(spans, CURDATE_FIELD, CURDATE_OFFSET, CURDATE_LENGTH);
        appendField(spans, PGMNAME_FIELD, PGMNAME_OFFSET, PGMNAME_LENGTH);
        appendField(spans, TITLE02_FIELD, TITLE02_OFFSET, TITLE02_LENGTH);
        appendField(spans, CURTIME_FIELD, CURTIME_OFFSET, CURTIME_LENGTH);
        appendField(spans, PAGENUM_FIELD, PAGENUM_OFFSET, PAGENUM_LENGTH);
        appendField(spans, TRNIDIN_FIELD, TRNIDIN_OFFSET, TRNIDIN_LENGTH);
        for (int row = 1; row <= ROW_COUNT; row++) {
            int rowImageOffset = rowImageOffset(row);
            appendField(spans, selectionFieldName(row),
                    rowImageOffset + SELECTION_ROW_OFFSET, SELECTION_LENGTH);
            appendField(spans, transactionIdFieldName(row),
                    rowImageOffset + TRANSACTION_ID_ROW_OFFSET, TRANSACTION_ID_LENGTH);
            appendField(spans, transactionDateFieldName(row),
                    rowImageOffset + TRANSACTION_DATE_ROW_OFFSET, TRANSACTION_DATE_LENGTH);
            appendField(spans, transactionDescriptionFieldName(row),
                    rowImageOffset + TRANSACTION_DESCRIPTION_ROW_OFFSET,
                    TRANSACTION_DESCRIPTION_LENGTH);
            appendField(spans, transactionAmountFieldName(row),
                    rowImageOffset + TRANSACTION_AMOUNT_ROW_OFFSET, TRANSACTION_AMOUNT_LENGTH);
        }
        appendField(spans, ERRMSG_FIELD, ERRMSG_OFFSET, ERRMSG_LENGTH);
        return new RecordLayout(SYMBOLIC_MAP_LENGTH, spans);
    }

    /**
     * Appends one field's metadata prefix and payload span. The prefix is positioned by subtracting
     * {@value #FIELD_PREFIX_LENGTH} from the payload offset, so the two can never disagree.
     */
    private static void appendField(List<FieldSpan> spans, String baseFieldName,
                                    int payloadOffset, int payloadLength) {
        spans.add(FieldSpan.filler(payloadOffset - FIELD_PREFIX_LENGTH, FIELD_PREFIX_LENGTH));
        spans.add(FieldSpan.alphanumeric(inputItemName(baseFieldName), payloadOffset, payloadLength));
    }

    /**
     * The absolute 0-based offset of a row's <em>image</em> - the first byte of its selector's
     * metadata prefix.
     *
     * <p>The conversion from the 1-based COBOL screen row to a 0-based byte offset is delegated to
     * {@link FixedWidthRecord#occursElementOffsetOneBased(int, int, int, int)} rather than written
     * inline, because that method names the convention it implements and rejects row 0 and row
     * {@value #ROW_COUNT} + 1 outright.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return {@value #ROW_BLOCK_OFFSET} for row 1, advancing by {@value #ROW_IMAGE_LENGTH} per row
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public static int rowImageOffset(int oneBasedRow) {
        return FixedWidthRecord.occursElementOffsetOneBased(ROW_BLOCK_OFFSET, ROW_IMAGE_LENGTH,
                ROW_COUNT, oneBasedRow);
    }

    // =================================================================================================
    // Nested type: the per-field symbolic-map metadata.
    // =================================================================================================

    /**
     * The three symbolic-map metadata items of one screen field - {@code xxxL}, {@code xxxF} and
     * {@code xxxA} - as validation and highlight metadata that is deliberately <strong>not</strong>
     * part of the JSON payload.
     *
     * <p>Per the migration's presentation contract, only the {@code xxxI} items are payload; the
     * length, flag and attribute items are metadata used for presence and length validation and for
     * field highlighting. Every one of the {@value TransactionListRequest#FIELD_COUNT} fields has an
     * instance, row fields included.
     *
     * <h2>The length item is signed, and that matters</h2>
     *
     * {@code xxxL} is declared {@code COMP PIC S9(4)}: a <em>signed</em> two-byte binary halfword. It
     * is not merely read - {@code app/cbl/COTRN00C.cbl} <em>writes</em> it, with
     * {@code MOVE -1 TO TRNIDINL OF COTRN0AI} at 13 sites (lines 105, 131, 201, 216, 221, 243, 265,
     * 610, 617, 644, 651, 678 and 685), which is the CICS idiom for "put the cursor here". The carrier
     * is therefore a {@code short} - exactly the declared signed 16-bit width - and it is neither
     * unsigned nor clamped at zero. Clamping would discard the cursor request outright.
     *
     * <h2>The flag and attribute items are one byte, not two</h2>
     *
     * The copybook declares them as a {@code REDEFINES} pair:
     *
     * <pre>
     *  02  xxxF    PICTURE X.
     *  02  FILLER REDEFINES xxxF.
     *    03 xxxA   PICTURE X.
     * </pre>
     *
     * They are storage aliases over a single byte, so this type holds one character and exposes two
     * typed views of it. Writing through {@link #setFlag(String)} is observable through
     * {@link #getAttribute()} and the reverse, exactly as it is in COBOL. Modelling them as two
     * independent fields would let them disagree, which the storage cannot.
     *
     * <p>The attribute byte's <em>values</em> come from the IBM-supplied {@code DFHBMSCA} and
     * {@code DFHATTR} copybooks and are owned elsewhere in the module; this type carries the byte
     * without interpreting it.
     */
    public static final class FieldMetadata {

        /** Declared width of {@code xxxL}, a {@code COMP PIC S9(4)} binary halfword: 2 bytes. */
        public static final int LENGTH_ITEM_BYTES = 2;

        /** Declared width of the {@code xxxF} / {@code xxxA} aliased byte: 1 byte. */
        public static final int FLAG_ITEM_BYTES = 1;

        /** The length item's value when no input length has been reported and no cursor requested. */
        public static final short LENGTH_ITEM_NONE = 0;

        /**
         * The one-character {@code LOW-VALUES} figurative constant, {@code X'00'} - the state of the
         * flag byte after {@code MOVE LOW-VALUES TO COTRN0AO} at {@code app/cbl/COTRN00C.cbl:114},
         * which is what the program does before painting a fresh screen. Distinct from a space.
         */
        public static final String FLAG_ITEM_LOW_VALUES = "\u0000";

        /** The base field name this metadata belongs to, verbatim - for example {@code TAMT001}. */
        private final String baseFieldName;

        /** {@code xxxL COMP PIC S9(4)} - signed, and able to hold {@code -1}. */
        private short lengthItem;

        /**
         * The single byte that {@code xxxF} and {@code xxxA} both address. Exactly one character, so
         * the two views can never disagree.
         */
        private String flagByte;

        /**
         * Creates metadata for one field in its initial state: no reported length and a
         * {@code LOW-VALUES} flag byte.
         *
         * @param baseFieldName the field's base name, verbatim
         * @throws NullPointerException if {@code baseFieldName} is {@code null}
         */
        public FieldMetadata(String baseFieldName) {
            this.baseFieldName = requireFieldName(baseFieldName);
            this.lengthItem = LENGTH_ITEM_NONE;
            this.flagByte = FLAG_ITEM_LOW_VALUES;
        }

        /**
         * Copies existing metadata, so a controller can echo what it received without mutating the
         * request's own instance.
         *
         * @param other the metadata to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldMetadata(FieldMetadata other) {
            Objects.requireNonNull(other, "Source metadata is required to copy it");
            this.baseFieldName = other.baseFieldName;
            this.lengthItem = other.lengthItem;
            this.flagByte = other.flagByte;
        }

        /**
         * The base field name this metadata belongs to.
         *
         * @return the verbatim base name, never {@code null}
         */
        public String getBaseFieldName() {
            return baseFieldName;
        }

        /**
         * The {@code xxxL} length item.
         *
         * @return the reported input length, {@link #LENGTH_ITEM_NONE}, or
         *         {@link TransactionListRequest#CURSOR_POSITION_REQUEST}
         */
        public short getLengthItem() {
            return lengthItem;
        }

        /**
         * Sets the {@code xxxL} length item. Negative values are accepted deliberately: {@code -1} is
         * the cursor-positioning request the program issues at 13 sites.
         *
         * @param lengthItem the value to store, which may be negative
         */
        public void setLengthItem(short lengthItem) {
            this.lengthItem = lengthItem;
        }

        /**
         * Reproduces {@code MOVE -1 TO xxxL}: asks CICS to place the cursor at this field.
         */
        public void requestCursorPosition() {
            this.lengthItem = CURSOR_POSITION_REQUEST;
        }

        /**
         * Whether this field currently carries the cursor-positioning request.
         *
         * @return {@code true} when the length item holds
         *         {@link TransactionListRequest#CURSOR_POSITION_REQUEST}
         */
        public boolean isCursorPositionRequested() {
            return lengthItem == CURSOR_POSITION_REQUEST;
        }

        /**
         * Whether the terminal reported any input for this field, that is a length item above zero. A
         * cursor request is not input, so {@code -1} answers {@code false}.
         *
         * @return {@code true} only when the length item is strictly positive
         */
        public boolean hasReportedInput() {
            return lengthItem > 0;
        }

        /**
         * The {@code xxxF} flag byte.
         *
         * @return exactly one character; {@link #FLAG_ITEM_LOW_VALUES} when untouched
         */
        public String getFlag() {
            return flagByte;
        }

        /**
         * Sets the {@code xxxF} flag byte. Because {@code xxxA} redefines the same storage, this is
         * equally observable through {@link #getAttribute()}.
         *
         * @param flag the byte to store, taken at exactly {@link #FLAG_ITEM_BYTES} character
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setFlag(String flag) {
            this.flagByte = requireFlagByte(flag, flagItemName(baseFieldName));
        }

        /**
         * The {@code xxxA} attribute byte - the same storage {@link #getFlag()} returns, read through
         * the {@code REDEFINES} view the program uses when setting field highlighting.
         *
         * @return exactly one character
         */
        public String getAttribute() {
            return flagByte;
        }

        /**
         * Sets the {@code xxxA} attribute byte. Because it redefines {@code xxxF}, this is equally
         * observable through {@link #getFlag()}.
         *
         * @param attribute the byte to store, taken at exactly {@link #FLAG_ITEM_BYTES} character
         * @throws NullPointerException if {@code attribute} is {@code null}
         */
        public void setAttribute(String attribute) {
            this.flagByte = requireFlagByte(attribute, attributeItemName(baseFieldName));
        }

        /**
         * Normalises an {@code xxxF} / {@code xxxA} value to exactly one character.
         *
         * <p>Unlike a payload field, this item is genuinely one byte wide and the class relies on it:
         * {@link #toString()} reads {@code charAt(0)} to report the code point, so an empty value would
         * be a lurking {@link StringIndexOutOfBoundsException} rather than a shorter field. An empty
         * string is therefore filled to a single space, which is what the byte holds when CICS reports
         * no attribute.
         *
         * <p>A value of more than one character is <strong>refused</strong>, not shortened. It cannot
         * come from a terminal - CICS reports one attribute byte per field - so it is a caller defect,
         * and silently keeping the first character would hide it.
         *
         * @param value    the byte offered for the item
         * @param itemName the {@code xxxF} or {@code xxxA} name, for the failure message
         * @return exactly one character
         * @throws NullPointerException     if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is longer than one character
         */
        private static String requireFlagByte(String value, String itemName) {
            Objects.requireNonNull(value, "A value is required for " + itemName
                    + ": COBOL has no null, so pass a space to clear the byte explicitly");
            if (value.length() > FLAG_ITEM_BYTES) {
                throw new IllegalArgumentException("Item " + itemName + " is declared PICTURE X - one "
                        + "byte, the single attribute byte CICS reports for a field - but was given "
                        + value.length() + " character(s)");
            }
            return value.isEmpty() ? " " : value;
        }

        /**
         * Whether the flag byte still holds {@code LOW-VALUES}.
         *
         * @return {@code true} when the byte is {@link #FLAG_ITEM_LOW_VALUES}
         */
        public boolean isFlagLowValues() {
            return FLAG_ITEM_LOW_VALUES.equals(flagByte);
        }

        /**
         * Restores the initial state: no reported length, {@code LOW-VALUES} flag byte.
         */
        public void reset() {
            this.lengthItem = LENGTH_ITEM_NONE;
            this.flagByte = FLAG_ITEM_LOW_VALUES;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldMetadata that)) {
                return false;
            }
            return lengthItem == that.lengthItem
                    && baseFieldName.equals(that.baseFieldName)
                    && flagByte.equals(that.flagByte);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseFieldName, lengthItem, flagByte);
        }

        /**
         * A diagnostic summary. The flag byte is reported as a hexadecimal code point rather than as a
         * character, because it is routinely {@code X'00'} and would otherwise be invisible in a log.
         *
         * @return for example {@code FieldMetadata[TRNIDIN, length=-1, flag=X'00']}
         */
        @Override
        public String toString() {
            return "FieldMetadata[" + baseFieldName + ", length=" + lengthItem + ", flag=X'"
                    + String.format("%02X", (int) flagByte.charAt(0)) + "']";
        }
    }

    // =================================================================================================
    // Nested type: the pagination cursor - COTRN00C's in-place extension of the CARDDEMO-COMMAREA.
    // =================================================================================================

    /**
     * {@code 05 CDEMO-CT00-INFO} - the {@value #CURSOR_LENGTH}-byte browse cursor that
     * {@code app/cbl/COTRN00C.cbl} appends to the shared communication area at lines 62 to 70,
     * immediately after {@code COPY COCOM01Y} at line 61.
     *
     * <pre>
     *  05 CDEMO-CT00-INFO.
     *     10 CDEMO-CT00-TRNID-FIRST     PIC X(16).
     *     10 CDEMO-CT00-TRNID-LAST      PIC X(16).
     *     10 CDEMO-CT00-PAGE-NUM        PIC 9(08).
     *     10 CDEMO-CT00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
     *        88 NEXT-PAGE-YES                     VALUE 'Y'.
     *        88 NEXT-PAGE-NO                      VALUE 'N'.
     *     10 CDEMO-CT00-TRN-SEL-FLG     PIC X(01).
     *     10 CDEMO-CT00-TRN-SELECTED    PIC X(16).
     * </pre>
     *
     * 16 + 16 + 8 + 1 + 1 + 16 = {@value #CURSOR_LENGTH}, so the communication area
     * {@code COTRN00C} actually passes is {@value NavigationContext#COMMAREA_LENGTH} +
     * {@value #CURSOR_LENGTH} = {@value #COMMAREA_LENGTH} bytes. {@link #LAYOUT} enforces the 58.
     *
     * <h2>This cursor is the whole reason the browse needs no server state</h2>
     *
     * Paging forwards and backwards works entirely from these six values. {@code PROCESS-PF7-KEY}
     * restarts the browse from {@code TRNID-FIRST} (line 239) and {@code PROCESS-PF8-KEY} from
     * {@code TRNID-LAST} (line 262); the page number is incremented at lines 306 and 317, decremented
     * at line 364 and floored at line 366. Because the client hands all of it back on the next call,
     * the server holds no browse position of its own.
     *
     * <h2>It is deliberately <em>not</em> hoisted into a shared class</h2>
     *
     * {@code COTRN01C} and {@code COTRN02C} extend the same communication area with structurally
     * identical groups named {@code CDEMO-CT01-*} and {@code CDEMO-CT02-*}. Identical shape is not
     * interchangeability: field-for-field diffing compares by name, so collapsing the three into one
     * shared type would erase the distinct names the comparison depends on. Each screen's cursor
     * therefore lives nested inside that screen's own payload type.
     *
     * <p>Equally, {@link NavigationContext} is <strong>not</strong> widened to carry these fields. It
     * is fixed at exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and shared by all seventeen
     * online programs; adding a screen-specific extension to it would change the shape every other
     * controller sees.
     */
    public static final class PaginationCursor {

        /** {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}. */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT00-TRNID-FIRST";

        /** {@code CDEMO-CT00-TRNID-LAST PIC X(16)}. */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT00-TRNID-LAST";

        /** {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}. */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT00-PAGE-NUM";

        /** {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}. */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT00-NEXT-PAGE-FLG";

        /** {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)}. */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT00-TRN-SEL-FLG";

        /** {@code CDEMO-CT00-TRN-SELECTED PIC X(16)}. */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT00-TRN-SELECTED";

        /** Declared width of {@code CDEMO-CT00-TRNID-FIRST}: {@value #TRNID_FIRST_LENGTH}. */
        public static final int TRNID_FIRST_LENGTH = 16;

        /** Declared width of {@code CDEMO-CT00-TRNID-LAST}: {@value #TRNID_LAST_LENGTH}. */
        public static final int TRNID_LAST_LENGTH = 16;

        /** Declared width of {@code CDEMO-CT00-PAGE-NUM}: {@value #PAGE_NUM_LENGTH} digits. */
        public static final int PAGE_NUM_LENGTH = 8;

        /** Declared width of {@code CDEMO-CT00-NEXT-PAGE-FLG}: {@value #NEXT_PAGE_FLG_LENGTH}. */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /** Declared width of {@code CDEMO-CT00-TRN-SEL-FLG}: {@value #TRN_SEL_FLG_LENGTH}. */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /** Declared width of {@code CDEMO-CT00-TRN-SELECTED}: {@value #TRN_SELECTED_LENGTH}. */
        public static final int TRN_SELECTED_LENGTH = 16;

        /** Offset of {@code CDEMO-CT00-TRNID-FIRST}: {@value #TRNID_FIRST_OFFSET}. */
        public static final int TRNID_FIRST_OFFSET = 0;

        /** Offset of {@code CDEMO-CT00-TRNID-LAST}: {@value #TRNID_LAST_OFFSET}. */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;

        /** Offset of {@code CDEMO-CT00-PAGE-NUM}: {@value #PAGE_NUM_OFFSET}. */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;

        /** Offset of {@code CDEMO-CT00-NEXT-PAGE-FLG}: {@value #NEXT_PAGE_FLG_OFFSET}. */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;

        /** Offset of {@code CDEMO-CT00-TRN-SEL-FLG}: {@value #TRN_SEL_FLG_OFFSET}. */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;

        /** Offset of {@code CDEMO-CT00-TRN-SELECTED}: {@value #TRN_SELECTED_OFFSET}. */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * Total width of {@code CDEMO-CT00-INFO}: {@value #CURSOR_LENGTH} bytes, being
         * 16 + 16 + 8 + 1 + 1 + 16.
         */
        public static final int CURSOR_LENGTH =
                TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH + PAGE_NUM_LENGTH + NEXT_PAGE_FLG_LENGTH
                        + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * The communication area {@code COTRN00C} passes on {@code XCTL}:
         * {@value NavigationContext#COMMAREA_LENGTH} bytes of {@code CARDDEMO-COMMAREA} plus this
         * {@value #CURSOR_LENGTH}-byte extension, that is {@value #COMMAREA_LENGTH} bytes.
         */
        public static final int COMMAREA_LENGTH = NavigationContext.COMMAREA_LENGTH + CURSOR_LENGTH;

        /** {@code 88 NEXT-PAGE-YES VALUE 'Y'} - a further page exists beyond the one displayed. */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'} - and the field's declared {@code VALUE 'N'}, so this is
         * also the initial state. {@code app/cbl/COTRN00C.cbl:99} asserts it again with
         * {@code SET NEXT-PAGE-NO TO TRUE} as the first act of {@code MAIN-PARA}.
         */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * The selector value that means "view this transaction".
         * {@code app/cbl/COTRN00C.cbl:186-188} accepts {@code 'S'} and {@code 's'} and transfers to
         * {@code COTRN01C}; anything else is rejected with
         * {@code 'Invalid selection. Valid value is S'} at lines 198 to 200. Matching is the
         * controller's work; this type carries the character the user typed.
         */
        public static final String SELECTION_VIEW = "S";

        /**
         * Layout of {@code CDEMO-CT00-INFO}. Declaring it proves the {@value #CURSOR_LENGTH}-byte
         * total at class initialisation, and the {@code VALUE 'N'} literal is carried on the
         * next-page span so initialising a record from this layout reproduces the copybook's declared
         * initial state.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(CURSOR_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET,
                        NEXT_PAGE_FLG_LENGTH).withInitialValue(NEXT_PAGE_NO),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET,
                        TRN_SELECTED_LENGTH));

        /** The highest page number {@code PIC 9(08)} can hold: {@value #PAGE_NUM_MAX}. */
        public static final int PAGE_NUM_MAX = 99_999_999;

        private String trnidFirst;
        private String trnidLast;
        private int pageNum;
        private String nextPageFlg;
        private String trnSelFlg;
        private String trnSelected;

        /**
         * Creates a cursor in its declared initial state: both browse keys spaces, page number zero,
         * next-page flag {@value #NEXT_PAGE_NO} from the copybook's {@code VALUE 'N'}, and both
         * selection fields spaces.
         *
         * <p>{@code app/cbl/COTRN00C.cbl:224} moves zero into the page number before the first
         * forward page is built, so page zero is the genuine pre-browse state rather than a
         * placeholder.
         */
        public PaginationCursor() {
            this.trnidFirst = spaces(TRNID_FIRST_LENGTH);
            this.trnidLast = spaces(TRNID_LAST_LENGTH);
            this.pageNum = 0;
            this.nextPageFlg = NEXT_PAGE_NO;
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Copies an existing cursor, so a controller can echo the position it received into the
         * response without mutating the request's instance.
         *
         * @param other the cursor to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public PaginationCursor(PaginationCursor other) {
            Objects.requireNonNull(other, "A source cursor is required to copy one");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * {@code CDEMO-CT00-TRNID-FIRST} - the key of the first transaction on the displayed page,
         * set from row 1 at {@code app/cbl/COTRN00C.cbl:393} and used to restart a backward browse.
         *
         * @return exactly {@value #TRNID_FIRST_LENGTH} characters
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Sets {@code CDEMO-CT00-TRNID-FIRST}, stored at exactly its declared width.
         *
         * @param trnidFirst the key; pass spaces to clear it
         * @throws NullPointerException if {@code trnidFirst} is {@code null}
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = requirePicX(trnidFirst, TRNID_FIRST_LENGTH, TRNID_FIRST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRNID-LAST} - the key of the last transaction on the displayed page, set
         * from row {@value TransactionListRequest#ROW_COUNT} at
         * {@code app/cbl/COTRN00C.cbl:439} and used to restart a forward browse.
         *
         * @return exactly {@value #TRNID_LAST_LENGTH} characters
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Sets {@code CDEMO-CT00-TRNID-LAST}, stored at exactly its declared width.
         *
         * @param trnidLast the key; pass spaces to clear it
         * @throws NullPointerException if {@code trnidLast} is {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = requirePicX(trnidLast, TRNID_LAST_LENGTH, TRNID_LAST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} - the current page number.
         *
         * <p>Typed as an {@code int} because {@code PIC 9(08)} is scale-free: the migration types
         * scale-free {@code PIC 9} items as integers and reserves {@code BigDecimal} for
         * {@code PIC 9...V...} items, of which this is not one. The program does integer arithmetic on
         * it - {@code COMPUTE ... + 1} at lines 306 and 317, {@code SUBTRACT 1 FROM} at line 364 - so
         * an integer is also what the arithmetic requires. Nothing here is floating point.
         *
         * <p>The <em>screen</em> field {@code PAGENUM} is a separate matter: it is
         * {@code PIC X(8)} and is therefore a {@link String}, because
         * {@code app/cbl/COTRN00C.cbl:324} moves this numeric item <em>into</em> that alphanumeric
         * one. See {@link TransactionListRequest#getPagenum()}.
         *
         * @return the page number, 0 before the first page is built
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Sets {@code CDEMO-CT00-PAGE-NUM}.
         *
         * @param pageNum the page number; must fit the declared {@value #PAGE_NUM_LENGTH} digits
         * @throws IllegalArgumentException if {@code pageNum} is negative or above
         *                                  {@value #PAGE_NUM_MAX}, since {@code PIC 9(08)} is
         *                                  unsigned and eight digits wide
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException("Page number " + pageNum + " is negative but "
                        + PAGE_NUM_FIELD + " is declared PIC 9(08), which is unsigned");
            }
            if (pageNum > PAGE_NUM_MAX) {
                throw new IllegalArgumentException("Page number " + pageNum + " exceeds "
                        + PAGE_NUM_MAX + ", the largest value " + PAGE_NUM_FIELD
                        + " can hold in its declared " + PAGE_NUM_LENGTH + " digits");
            }
            this.pageNum = pageNum;
        }

        /**
         * The {@code PIC 9(08)} storage image of the page number - eight digits, zero-filled on the
         * left, exactly as the field is stored and exactly what
         * {@code MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI} sends to the screen.
         *
         * <p>Excluded from the payload: it is a rendering of {@link #getPageNum()}, not a second
         * field, and publishing both would put the same COBOL item on the wire twice.
         *
         * @return exactly {@value #PAGE_NUM_LENGTH} digits
         */
        @JsonIgnore
        public String getPageNumImage() {
            return PICTURE_RULES.movePic9(pageNum, PAGE_NUM_LENGTH);
        }

        /**
         * {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)}.
         *
         * @return {@value #NEXT_PAGE_YES}, {@value #NEXT_PAGE_NO}, or whatever single character the
         *         payload carried
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Sets {@code CDEMO-CT00-NEXT-PAGE-FLG}, stored at exactly one character.
         *
         * @param nextPageFlg the flag byte
         * @throws NullPointerException if {@code nextPageFlg} is {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg = requirePicX(nextPageFlg, NEXT_PAGE_FLG_LENGTH, NEXT_PAGE_FLG_FIELD);
        }

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, as a named predicate.
         *
         * <p>Reached from {@code app/cbl/COTRN00C.cbl:242} and {@code :310}, and tested at
         * {@code :267} and {@code :361}.
         *
         * @return {@code true} when the flag is {@value #NEXT_PAGE_YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, as a named predicate.
         *
         * <p>Reached from {@code app/cbl/COTRN00C.cbl:99}, {@code :312} and {@code :315}. Both this
         * and {@link #isNextPageYes()} are genuinely reachable, because the program sets each of them
         * on distinct paths - which is why neither is dead.
         *
         * @return {@code true} when the flag is {@value #NEXT_PAGE_NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /** Reproduces {@code SET NEXT-PAGE-YES TO TRUE}. */
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /** Reproduces {@code SET NEXT-PAGE-NO TO TRUE}. */
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)} - the selector character the user typed into the
         * chosen row, copied from the winning {@code SEL000nI} at
         * {@code app/cbl/COTRN00C.cbl:150-180}.
         *
         * @return exactly one character
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Sets {@code CDEMO-CT00-TRN-SEL-FLG}, stored at exactly one character.
         *
         * @param trnSelFlg the selector character; pass a space to clear it
         * @throws NullPointerException if {@code trnSelFlg} is {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = requirePicX(trnSelFlg, TRN_SEL_FLG_LENGTH, TRN_SEL_FLG_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRN-SELECTED PIC X(16)} - the transaction identifier of the chosen row,
         * copied from that row's {@code TRNIDnnI}.
         *
         * @return exactly {@value #TRN_SELECTED_LENGTH} characters
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Sets {@code CDEMO-CT00-TRN-SELECTED}, stored at exactly its declared width.
         *
         * @param trnSelected the chosen transaction identifier; pass spaces to clear it
         * @throws NullPointerException if {@code trnSelected} is {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = requirePicX(trnSelected, TRN_SELECTED_LENGTH, TRN_SELECTED_FIELD);
        }

        /**
         * Reproduces the two {@code MOVE SPACES} statements of the {@code WHEN OTHER} branch at
         * {@code app/cbl/COTRN00C.cbl:180-181}, which clear the selection when no row was marked.
         */
        public void clearSelection() {
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Whether a row selection is present, that is whether <em>both</em> selection fields hold
         * something other than spaces.
         *
         * <p>Mirrors the compound guard at {@code app/cbl/COTRN00C.cbl:183-184}, which requires both
         * fields to differ from {@code SPACES} and {@code LOW-VALUES} before the selector is
         * evaluated at all. Deciding whether the selector is <em>valid</em> remains the controller's
         * work.
         *
         * @return {@code true} when both selection fields carry a value
         */
        @JsonIgnore
        public boolean isSelectionPresent() {
            return isPresent(trnSelFlg) && isPresent(trnSelected);
        }

        /**
         * Whether a field holds anything other than spaces and {@code LOW-VALUES} - the sense in which
         * {@code app/cbl/COTRN00C.cbl:183-184} tests for a value.
         */
        private static boolean isPresent(String value) {
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character != ' ' && character != '\u0000') {
                    return true;
                }
            }
            return false;
        }

        /**
         * Renders this cursor as its {@value #CURSOR_LENGTH}-byte fixed-width image.
         *
         * @param charset the code page to encode into, named explicitly by the caller
         * @return a fresh array of exactly {@value #CURSOR_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] toFixedWidth(Charset charset) {
            Objects.requireNonNull(charset, "A charset is required to render CDEMO-CT00-INFO as "
                    + "bytes; a fixed-width image is bytes in a specific code page, so the code page "
                    + "is stated explicitly and never taken from the platform");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord record = codec.newRecord(LAYOUT);
            record.writeSpan(LAYOUT.span(TRNID_FIRST_FIELD), trnidFirst);
            record.writeSpan(LAYOUT.span(TRNID_LAST_FIELD), trnidLast);
            codec.writePic9(record, LAYOUT.span(PAGE_NUM_FIELD), pageNum);
            record.writeSpan(LAYOUT.span(NEXT_PAGE_FLG_FIELD), nextPageFlg);
            record.writeSpan(LAYOUT.span(TRN_SEL_FLG_FIELD), trnSelFlg);
            record.writeSpan(LAYOUT.span(TRN_SELECTED_FIELD), trnSelected);
            return record.toByteArray();
        }

        /**
         * Rebuilds a cursor from its fixed-width image. A short or over-long image is rejected rather
         * than tolerated, because accepting one would let every offset after the discrepancy drift.
         *
         * @param image   exactly {@value #CURSOR_LENGTH} bytes
         * @param charset the code page the image is encoded in, named explicitly by the caller
         * @return the cursor the image carries
         * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@value #CURSOR_LENGTH}
         *                                  bytes
         */
        public static PaginationCursor fromFixedWidth(byte[] image, Charset charset) {
            Objects.requireNonNull(image, "An image is required to rebuild CDEMO-CT00-INFO");
            Objects.requireNonNull(charset, "A charset is required to decode a CDEMO-CT00-INFO "
                    + "image; the code page is stated explicitly and never taken from the platform");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord record = codec.wrap(image, LAYOUT);
            PaginationCursor cursor = new PaginationCursor();
            cursor.setTrnidFirst(record.readSpan(LAYOUT.span(TRNID_FIRST_FIELD)));
            cursor.setTrnidLast(record.readSpan(LAYOUT.span(TRNID_LAST_FIELD)));
            cursor.setPageNum(codec.readPic9AsInt(record, LAYOUT.span(PAGE_NUM_FIELD)));
            cursor.setNextPageFlg(record.readSpan(LAYOUT.span(NEXT_PAGE_FLG_FIELD)));
            cursor.setTrnSelFlg(record.readSpan(LAYOUT.span(TRN_SEL_FLG_FIELD)));
            cursor.setTrnSelected(record.readSpan(LAYOUT.span(TRN_SELECTED_FIELD)));
            return cursor;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PaginationCursor that)) {
                return false;
            }
            return pageNum == that.pageNum
                    && trnidFirst.equals(that.trnidFirst)
                    && trnidLast.equals(that.trnidLast)
                    && nextPageFlg.equals(that.nextPageFlg)
                    && trnSelFlg.equals(that.trnSelFlg)
                    && trnSelected.equals(that.trnSelected);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg,
                    trnSelected);
        }

        /**
         * A diagnostic summary of the browse position. The two browse keys and the selected identifier
         * are transaction identifiers, and a transaction identifier is a {@code TRANSACT} key that opens a
         * record carrying {@code TRAN-CARD-NUM} - so they are masked to their last
         * {@value SensitiveDiagnostics#REVEALED_TRAILING_DIGITS} characters by
         * {@link SensitiveDiagnostics#maskIdentifier(String)} rather than reported in full. A paging
         * defect stays exactly as diagnosable: this system's identifiers are zero-filled sequentials, so
         * the trailing digits are the only part that ever differs between two of them. The page number
         * and the more-pages flag carry no personal data and are reported as they are.
         *
         * @return for example
         *         {@code PaginationCursor[page=2, first=************0011, last=************0020,
         *         nextPage=Y]}
         */
        @Override
        public String toString() {
            return "PaginationCursor[page=" + pageNum
                    + ", first=" + SensitiveDiagnostics.maskIdentifier(trnidFirst).strip()
                    + ", last=" + SensitiveDiagnostics.maskIdentifier(trnidLast).strip()
                    + ", nextPage=" + nextPageFlg.strip() + "]";
        }
    }

    // =================================================================================================
    // The 8 header/control payload fields. Bean Validation states a MAXIMUM width and no minimum:
    // @Size(max = n), never @Size(min = n, max = n).
    //
    // The exact form was self-defeating. Every setter used to apply the PIC X move rule before the
    // constraint was ever evaluated, so the value had already been padded or truncated to exactly n by
    // the time it was measured - the constraint could not fail, and an over-long value was silently
    // shortened instead of reported. Now the setter refuses a surplus outright and keeps a short value
    // as it arrived, so the constraint is a real check on real input.
    //
    // A minimum would also be wrong on its own terms: a short value is legitimate. The program tests
    // several of these fields against SPACES OR LOW-VALUES, and a caller that sends "1" for an eight-
    // character field means "1" - the width is imposed at the byte boundary, where it belongs, not
    // demanded of the caller. The program does its own editing, and an extra Java rejection would be a
    // parity break.
    // =================================================================================================

    /** {@code TRNNAMEI PIC X(4)} - the transaction identifier in the screen header. */
    @Size(max = TRNNAME_LENGTH)
    private String trnname;

    /** {@code TITLE01I PIC X(40)} - the first title line, from {@code COTTL01Y}. */
    @Size(max = TITLE01_LENGTH)
    private String title01;

    /** {@code CURDATEI PIC X(8)} - the current date, {@code mm/dd/yy}. */
    @Size(max = CURDATE_LENGTH)
    private String curdate;

    /** {@code PGMNAMEI PIC X(8)} - the program name in the screen header. */
    @Size(max = PGMNAME_LENGTH)
    private String pgmname;

    /** {@code TITLE02I PIC X(40)} - the second title line, from {@code COTTL01Y}. */
    @Size(max = TITLE02_LENGTH)
    private String title02;

    /** {@code CURTIMEI PIC X(8)} - the current time, {@code hh:mm:ss}. */
    @Size(max = CURTIME_LENGTH)
    private String curtime;

    /** {@code PAGENUMI PIC X(8)} - the displayed page number, alphanumeric on the screen. */
    @Size(max = PAGENUM_LENGTH)
    private String pagenum;

    /** {@code TRNIDINI PIC X(16)} - the browse-start key; {@code UNPROT}, so genuine user input. */
    @Size(max = TRNIDIN_LENGTH)
    private String trnidin;

    // =================================================================================================
    // The 10 x 5 row payload fields. Declared individually, in screen order, under their verbatim names
    // - four-digit SEL, two-digit TRNID/TDATE/TDESC, three-digit TAMT - so the JSON property names are
    // the copybook names and no shared suffix format can regularise the difference away.
    // =================================================================================================

    /** {@code SEL0001I PIC X(1)} - row 1 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0001;

    /** {@code TRNID01I PIC X(16)} - row 1 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid01;

    /** {@code TDATE01I PIC X(8)} - row 1 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate01;

    /** {@code TDESC01I PIC X(26)} - row 1 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc01;

    /** {@code TAMT001I PIC X(12)} - row 1 edited amount, {@code +99999999.99}. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt001;

    /** {@code SEL0002I PIC X(1)} - row 2 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0002;

    /** {@code TRNID02I PIC X(16)} - row 2 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid02;

    /** {@code TDATE02I PIC X(8)} - row 2 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate02;

    /** {@code TDESC02I PIC X(26)} - row 2 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc02;

    /** {@code TAMT002I PIC X(12)} - row 2 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt002;

    /** {@code SEL0003I PIC X(1)} - row 3 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0003;

    /** {@code TRNID03I PIC X(16)} - row 3 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid03;

    /** {@code TDATE03I PIC X(8)} - row 3 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate03;

    /** {@code TDESC03I PIC X(26)} - row 3 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc03;

    /** {@code TAMT003I PIC X(12)} - row 3 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt003;

    /** {@code SEL0004I PIC X(1)} - row 4 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0004;

    /** {@code TRNID04I PIC X(16)} - row 4 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid04;

    /** {@code TDATE04I PIC X(8)} - row 4 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate04;

    /** {@code TDESC04I PIC X(26)} - row 4 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc04;

    /** {@code TAMT004I PIC X(12)} - row 4 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt004;

    /** {@code SEL0005I PIC X(1)} - row 5 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0005;

    /** {@code TRNID05I PIC X(16)} - row 5 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid05;

    /** {@code TDATE05I PIC X(8)} - row 5 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate05;

    /** {@code TDESC05I PIC X(26)} - row 5 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc05;

    /** {@code TAMT005I PIC X(12)} - row 5 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt005;

    /** {@code SEL0006I PIC X(1)} - row 6 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0006;

    /** {@code TRNID06I PIC X(16)} - row 6 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid06;

    /** {@code TDATE06I PIC X(8)} - row 6 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate06;

    /** {@code TDESC06I PIC X(26)} - row 6 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc06;

    /** {@code TAMT006I PIC X(12)} - row 6 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt006;

    /** {@code SEL0007I PIC X(1)} - row 7 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0007;

    /** {@code TRNID07I PIC X(16)} - row 7 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid07;

    /** {@code TDATE07I PIC X(8)} - row 7 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate07;

    /** {@code TDESC07I PIC X(26)} - row 7 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc07;

    /** {@code TAMT007I PIC X(12)} - row 7 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt007;

    /** {@code SEL0008I PIC X(1)} - row 8 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0008;

    /** {@code TRNID08I PIC X(16)} - row 8 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid08;

    /** {@code TDATE08I PIC X(8)} - row 8 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate08;

    /** {@code TDESC08I PIC X(26)} - row 8 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc08;

    /** {@code TAMT008I PIC X(12)} - row 8 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt008;

    /** {@code SEL0009I PIC X(1)} - row 9 selector, {@code UNPROT}. */
    @Size(max = SELECTION_LENGTH)
    private String sel0009;

    /** {@code TRNID09I PIC X(16)} - row 9 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid09;

    /** {@code TDATE09I PIC X(8)} - row 9 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate09;

    /** {@code TDESC09I PIC X(26)} - row 9 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc09;

    /** {@code TAMT009I PIC X(12)} - row 9 edited amount. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt009;

    /** {@code SEL0010I PIC X(1)} - row 10 selector, {@code UNPROT}. Four-digit suffix. */
    @Size(max = SELECTION_LENGTH)
    private String sel0010;

    /** {@code TRNID10I PIC X(16)} - row 10 transaction identifier. */
    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid10;

    /** {@code TDATE10I PIC X(8)} - row 10 date. */
    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate10;

    /** {@code TDESC10I PIC X(26)} - row 10 description. */
    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc10;

    /** {@code TAMT010I PIC X(12)} - row 10 edited amount. Three-digit suffix, not {@code TAMT10}. */
    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt010;

    // =================================================================================================
    // The error line, and the two carried state structures.
    // =================================================================================================

    /** {@code ERRMSGI PIC X(78)} - the error line at screen row 23. */
    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    /**
     * The resolved {@code EIBAID} key indication as a token, at most {@value #AID_LENGTH} characters.
     *
     * <p>Spaces mean no key has been resolved, which selects the {@code WHEN OTHER} arm of
     * {@code app/cbl/COTRN00C.cbl:129}. See {@link #AID_LENGTH} for why this member exists and why it
     * is not a screen field.
     */
    @Size(max = AID_LENGTH)
    private String aid;

    /**
     * {@code CARDDEMO-COMMAREA} - the {@value NavigationContext#COMMAREA_LENGTH}-byte shared
     * communication area {@code COTRN00C} copies at line 61, carried in the payload rather than held
     * in a session.
     *
     * <p><strong>{@code null} when no communication area was passed</strong>, and {@code null} on a
     * freshly constructed request. {@code app/cbl/COTRN00C.cbl:107-117} tests
     * {@code IF EIBCALEN = 0} before anything else and, on that branch, moves {@code 'COSGN00C'} into
     * {@code CDEMO-TO-PROGRAM} and returns to the previous screen without ever reading a context byte.
     * A freshly initialised area is the other branch - it has a length, so the program copies it and
     * goes on to test {@code CDEMO-PGM-REENTER} at line 112. The two are different states, and
     * substituting one for the other left the first with no representation in this payload.
     * {@link #hasNavigationContext()} is the discriminator.
     */
    private NavigationContext navigationContext;

    /**
     * {@code CDEMO-CT00-INFO} - the {@value PaginationCursor#CURSOR_LENGTH}-byte browse cursor
     * {@code COTRN00C} appends at lines 62 to 70.
     */
    private PaginationCursor cursor;

    /**
     * The {@value #FIELD_COUNT} per-field metadata carriers, keyed by verbatim base field name in
     * declaration order.
     *
     * <p>Instance state, not static: a static table would be shared across concurrent requests and
     * would destroy both request isolation and test determinism. The map is {@code final} and its
     * entries are created once in the constructor, so no key can appear or disappear later.
     */
    private final Map<String, FieldMetadata> fieldMetadata;

    // =================================================================================================
    // Construction. No Spring context, no builder and no framework is involved, so a unit or parity
    // test constructs an instance directly.
    // =================================================================================================

    /**
     * Creates a request in the state the program holds after {@code MOVE LOW-VALUES TO COTRN0AO} and
     * its subsequent {@code INITIALIZE-TRAN-DATA} loop: every one of the
     * {@value #FIELD_COUNT} fields space-filled to its declared width, an empty
     * {@link NavigationContext}, a fresh {@link PaginationCursor}, and one
     * {@link FieldMetadata} per field.
     *
     * <p>Spaces rather than {@code null} is the right initial state and is not a convenience:
     * {@code app/cbl/COTRN00C.cbl:450-505} blanks a row with {@code MOVE SPACES}, so an unpopulated
     * row genuinely holds spaces. A {@code null} would serialise as JSON {@code null} and compare
     * unequal to the spaces the COBOL produces.
     */
    public TransactionListRequest() {
        this.fieldMetadata = buildFieldMetadata();
        clearAllFields();
        // Absence, not an initialised area: EIBCALEN = 0 is what COTRN00C.cbl:107 tests for, and a
        // request nobody has filled in has had nothing passed to it.
        this.navigationContext = null;
        this.aid = spaces(AID_LENGTH);
        this.cursor = new PaginationCursor();
    }

    /**
     * Copies an existing request field for field, including its carried state and every metadata
     * carrier.
     *
     * <p>Provided because a controller echoes the request it received into the response it returns,
     * and a copy keeps the inbound payload's instance from being mutated while the response is
     * assembled. {@link NavigationContext} is immutable and so is shared rather than cloned; the
     * cursor and the metadata carriers are mutable and are therefore deep-copied.
     *
     * @param other the request to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public TransactionListRequest(TransactionListRequest other) {
        Objects.requireNonNull(other, "A source request is required to copy one");
        this.fieldMetadata = new LinkedHashMap<>();
        for (Map.Entry<String, FieldMetadata> entry : other.fieldMetadata.entrySet()) {
            this.fieldMetadata.put(entry.getKey(), new FieldMetadata(entry.getValue()));
        }
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.pagenum = other.pagenum;
        this.trnidin = other.trnidin;
        for (int row = 1; row <= ROW_COUNT; row++) {
            setSelection(row, other.getSelection(row));
            setTransactionId(row, other.getTransactionId(row));
            setTransactionDate(row, other.getTransactionDate(row));
            setTransactionDescription(row, other.getTransactionDescription(row));
            setTransactionAmount(row, other.getTransactionAmount(row));
        }
        this.errmsg = other.errmsg;
        this.navigationContext = other.navigationContext;
        this.aid = other.aid;
        this.cursor = new PaginationCursor(other.cursor);
    }

    /**
     * Reproduces the effect of {@code MOVE LOW-VALUES TO COTRN0AO} followed by the
     * {@code INITIALIZE-TRAN-DATA} loop: every one of the {@value #FIELD_COUNT} payload fields
     * returns to spaces at its declared width, and every metadata carrier returns to its initial
     * state.
     *
     * <p>{@code app/cbl/COTRN00C.cbl:114} issues the {@code LOW-VALUES} move on the enter path and
     * lines 290 to 292 then run {@code INITIALIZE-TRAN-DATA} across all {@value #ROW_COUNT} rows,
     * which blanks the four data fields of each. The selector is deliberately blanked here too: the
     * program never leaves a stale selector on a freshly painted screen, because the whole group was
     * set to {@code LOW-VALUES} first.
     */
    public void clearAllFields() {
        this.trnname = spaces(TRNNAME_LENGTH);
        this.title01 = spaces(TITLE01_LENGTH);
        this.curdate = spaces(CURDATE_LENGTH);
        this.pgmname = spaces(PGMNAME_LENGTH);
        this.title02 = spaces(TITLE02_LENGTH);
        this.curtime = spaces(CURTIME_LENGTH);
        this.pagenum = spaces(PAGENUM_LENGTH);
        this.trnidin = spaces(TRNIDIN_LENGTH);
        for (int row = 1; row <= ROW_COUNT; row++) {
            clearRow(row);
        }
        this.errmsg = spaces(ERRMSG_LENGTH);
        for (FieldMetadata metadata : fieldMetadata.values()) {
            metadata.reset();
        }
    }

    /**
     * Reproduces one {@code WHEN} branch of {@code INITIALIZE-TRAN-DATA}
     * ({@code app/cbl/COTRN00C.cbl:450-505}): blanks a row's four data fields to spaces.
     *
     * <p>The selector is blanked as well, which the paragraph itself does not do - the paragraph is
     * only ever reached after {@code MOVE LOW-VALUES TO COTRN0AO} at line 114 has already cleared the
     * whole group, so no COBOL path can display a row whose data was blanked while its selector was
     * not.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public void clearRow(int oneBasedRow) {
        requireValidRow(oneBasedRow);
        setSelection(oneBasedRow, spaces(SELECTION_LENGTH));
        setTransactionId(oneBasedRow, spaces(TRANSACTION_ID_LENGTH));
        setTransactionDate(oneBasedRow, spaces(TRANSACTION_DATE_LENGTH));
        setTransactionDescription(oneBasedRow, spaces(TRANSACTION_DESCRIPTION_LENGTH));
        setTransactionAmount(oneBasedRow, spaces(TRANSACTION_AMOUNT_LENGTH));
    }

    private static Map<String, FieldMetadata> buildFieldMetadata() {
        Map<String, FieldMetadata> metadata = new LinkedHashMap<>();
        for (String baseFieldName : FIELD_NAMES) {
            metadata.put(baseFieldName, new FieldMetadata(baseFieldName));
        }
        return metadata;
    }

    // =================================================================================================
    // The one shared PIC X primitive. It validates and does not transform: the COBOL MOVE itself is
    // applied once, at the byte boundary, and nowhere else.
    // =================================================================================================

    /**
     * Stores a value that fits a receiver's declared width, exactly as supplied, and refuses one that
     * does not.
     *
     * <h2>Why this validates instead of moving</h2>
     * This primitive used to apply the alphanumeric {@code MOVE} rule - pad a short value on the
     * right, truncate a long one - to every value that reached any of the fifty-nine setters. Two
     * things followed from that, and both were wrong:
     *
     * <ul>
     *   <li><strong>An over-long value was accepted.</strong> It was shortened first and then measured,
     *       so it always fitted and the {@code @Size} constraint it was checked against could never
     *       fail. A caller sending twenty characters for a {@code PIC X(16)} field got no error and no
     *       indication that four characters had gone: silent data loss at the API boundary, in a
     *       migration whose whole purpose is byte-level fidelity.</li>
     *   <li><strong>A short value was changed on the way in.</strong> The payload no longer held what
     *       the caller sent, so a JSON round trip was not the identity, and a field the program tests
     *       against {@code SPACES OR LOW-VALUES} arrived pre-padded rather than as it was typed.</li>
     * </ul>
     *
     * <p>So the rule is now: a value narrower than the field is kept <strong>unchanged</strong>, and a
     * value wider than the field is <strong>refused by name</strong>. Nothing is padded and nothing is
     * truncated here. The padding still happens - {@link #writeInto(FixedWidthRecord)} writes every
     * field through {@link FixedWidthRecord#writeSpan}, which fills the span to its declared width -
     * but it happens once, at the point where the value becomes bytes, which is the only place a
     * fixed-width width is actually required. That layer refuses a surplus too rather than truncating,
     * so a truncation can only ever be asked for explicitly through
     * {@link FixedWidthCodec#movePicX(String, int)}, where the direction of the loss is visible at the
     * call site.
     *
     * <p>Refusing is not stricter than the COBOL in any reachable sense: a 3270 {@code RECEIVE MAP}
     * cannot deliver more bytes than a field is wide, so {@code COTRN00C} never sees the case at all.
     *
     * <p>{@code null} is still rejected rather than coerced. COBOL has no absent state, so a caller
     * that has nothing to store must say which figurative constant it means - and for this map that is
     * almost always {@code SPACES}, since every blanking path in the program uses
     * {@code MOVE SPACES}.
     *
     * @param value     the value offered for the field
     * @param length    the width the field's {@code PICTURE} clause declares
     * @param cobolName the field's COBOL name, used to identify it in any failure
     * @return {@code value} unchanged
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is wider than {@code length}
     */
    private static String requirePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no "
                + "null, so pass spaces(" + length + ") to blank the field explicitly");
        if (value.length() > length) {
            throw new IllegalArgumentException("Field " + cobolName + " of "
                    + SYMBOLIC_MAP_INPUT_GROUP + " is declared PIC X(" + length + ") but was given "
                    + value.length() + " character(s). This payload never truncates, so that the loss "
                    + "of a character is always a deliberate act rather than a silent one. To shorten "
                    + "the value, pass it through FixedWidthCodec.movePicX(value, " + length + "), "
                    + "which truncates on the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * The {@code SPACES} figurative constant at a given width.
     *
     * @param length the receiver's declared width
     * @return a string of exactly {@code length} spaces
     */
    private static String spaces(int length) {
        return " ".repeat(length);
    }

    // =================================================================================================
    // Header / control accessors. Every setter stores through the PIC X rule, so a field read back is
    // always exactly its declared width and its trailing spaces are preserved as the data they are.
    // =================================================================================================

    /**
     * {@code TRNNAMEI PIC X(4)}.
     *
     * @return exactly {@value #TRNNAME_LENGTH} characters
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets {@code TRNNAMEI}.
     *
     * @param trnname the value; kept unchanged if it fits, refused if it is too long
     * @throws NullPointerException if {@code trnname} is {@code null}
     */
    public void setTrnname(String trnname) {
        this.trnname = requirePicX(trnname, TRNNAME_LENGTH, TRNNAME_FIELD);
    }

    /**
     * {@code TITLE01I PIC X(40)}.
     *
     * @return exactly {@value #TITLE01_LENGTH} characters
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets {@code TITLE01I}.
     *
     * @param title01 the value
     * @throws NullPointerException if {@code title01} is {@code null}
     */
    public void setTitle01(String title01) {
        this.title01 = requirePicX(title01, TITLE01_LENGTH, TITLE01_FIELD);
    }

    /**
     * {@code CURDATEI PIC X(8)}.
     *
     * @return exactly {@value #CURDATE_LENGTH} characters
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets {@code CURDATEI}.
     *
     * @param curdate the value
     * @throws NullPointerException if {@code curdate} is {@code null}
     */
    public void setCurdate(String curdate) {
        this.curdate = requirePicX(curdate, CURDATE_LENGTH, CURDATE_FIELD);
    }

    /**
     * {@code PGMNAMEI PIC X(8)}.
     *
     * @return exactly {@value #PGMNAME_LENGTH} characters
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets {@code PGMNAMEI}.
     *
     * @param pgmname the value
     * @throws NullPointerException if {@code pgmname} is {@code null}
     */
    public void setPgmname(String pgmname) {
        this.pgmname = requirePicX(pgmname, PGMNAME_LENGTH, PGMNAME_FIELD);
    }

    /**
     * {@code TITLE02I PIC X(40)}.
     *
     * @return exactly {@value #TITLE02_LENGTH} characters
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets {@code TITLE02I}.
     *
     * @param title02 the value
     * @throws NullPointerException if {@code title02} is {@code null}
     */
    public void setTitle02(String title02) {
        this.title02 = requirePicX(title02, TITLE02_LENGTH, TITLE02_FIELD);
    }

    /**
     * {@code CURTIMEI PIC X(8)}.
     *
     * @return exactly {@value #CURTIME_LENGTH} characters
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets {@code CURTIMEI}.
     *
     * @param curtime the value
     * @throws NullPointerException if {@code curtime} is {@code null}
     */
    public void setCurtime(String curtime) {
        this.curtime = requirePicX(curtime, CURTIME_LENGTH, CURTIME_FIELD);
    }

    /**
     * {@code PAGENUMI PIC X(8)} - the displayed page number.
     *
     * <p>A {@link String}, not a number. {@code app/cbl/COTRN00C.cbl:324} and {@code :373} do
     * {@code MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI OF COTRN0AI}, moving the cursor's
     * {@code PIC 9(08)} item into this alphanumeric screen item, and it is the screen item's
     * {@code PICTURE} that fixes this member's type. The numeric page number lives on the cursor as
     * {@link PaginationCursor#getPageNum()}.
     *
     * @return exactly {@value #PAGENUM_LENGTH} characters
     */
    public String getPagenum() {
        return pagenum;
    }

    /**
     * Sets {@code PAGENUMI}.
     *
     * @param pagenum the value
     * @throws NullPointerException if {@code pagenum} is {@code null}
     */
    public void setPagenum(String pagenum) {
        this.pagenum = requirePicX(pagenum, PAGENUM_LENGTH, PAGENUM_FIELD);
    }

    /**
     * {@code TRNIDINI PIC X(16)} - the browse-start key typed by the user.
     *
     * <p>One of the eleven {@code UNPROT} fields, and the only one outside the row block.
     * {@code app/cbl/COTRN00C.cbl:206-219} treats spaces or {@code LOW-VALUES} as "start at the
     * beginning", accepts a numeric value as the starting key, and rejects anything else with
     * {@code 'Tran ID must be Numeric ...'}. That editing belongs to the controller, so this member
     * carries whatever the user typed without pre-judging it.
     *
     * @return exactly {@value #TRNIDIN_LENGTH} characters
     */
    public String getTrnidin() {
        return trnidin;
    }

    /**
     * Sets {@code TRNIDINI}.
     *
     * @param trnidin the value
     * @throws NullPointerException if {@code trnidin} is {@code null}
     */
    public void setTrnidin(String trnidin) {
        this.trnidin = requirePicX(trnidin, TRNIDIN_LENGTH, TRNIDIN_FIELD);
    }

    /**
     * {@code ERRMSGI PIC X(78)} - the error line.
     *
     * <p>Space-padded to the full 78, and the padding is data: the response echoes it and the parity
     * differ compares it byte for byte, so it is never trimmed here.
     *
     * @return exactly {@value #ERRMSG_LENGTH} characters
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets {@code ERRMSGI}.
     *
     * @param errmsg the value
     * @throws NullPointerException if {@code errmsg} is {@code null}
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = requirePicX(errmsg, ERRMSG_LENGTH, ERRMSG_FIELD);
    }

    // =================================================================================================
    // The carried state: the shared communication area and this screen's browse cursor.
    // =================================================================================================

    /**
     * {@code CARDDEMO-COMMAREA} - the {@value NavigationContext#COMMAREA_LENGTH}-byte communication
     * area, carried in the payload, or {@code null} when none was passed.
     *
     * @return the context, or {@code null} for the {@code EIBCALEN = 0} cold start of
     *         {@code app/cbl/COTRN00C.cbl:107}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the communication area, or removes it.
     *
     * <p>{@code null} is stored as {@code null}, and this setter used to reject it outright with the
     * advice to pass {@link NavigationContext#empty()} instead. That advice was wrong: an initialised
     * area is not {@code EIBCALEN = 0}, it is the opposite case. {@code EIBCALEN} counts the bytes CICS
     * was actually handed, so an initialised area reports
     * {@value NavigationContext#COMMAREA_LENGTH} and sends {@code COTRN00C} down its {@code ELSE}
     * branch - copy the area, test {@code CDEMO-PGM-REENTER}, paint or validate. Only a genuine absence
     * reaches line 108 and the transfer to {@code COSGN00C}. Rejecting {@code null} therefore removed
     * the one spelling that branch had.
     *
     * @param navigationContext the context to carry, or {@code null} to carry none
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN}
     * being non-zero at {@code app/cbl/COTRN00C.cbl:107}.
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
     * {@value PaginationCursor#COMMAREA_LENGTH} when a communication area travelled with this request,
     * and {@code 0} when none did.
     *
     * <p>{@code COTRN00C} passes {@code CARDDEMO-COMMAREA} followed by its own
     * {@value PaginationCursor#CURSOR_LENGTH}-byte {@code CDEMO-CT00-INFO} extension, so the non-zero
     * case is the sum of the two.
     *
     * @return {@value PaginationCursor#COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? PaginationCursor.COMMAREA_LENGTH : 0;
    }

    /**
     * The resolved {@code EIBAID} key indication - the key the operator pressed, which
     * {@code app/cbl/COTRN00C.cbl:119} evaluates.
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
     * {@code 'PFK07'} and {@code 'PFK08'}; every other value, spaces included, is the
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
        this.aid = requirePicX(aid, AID_LENGTH, AID_FIELD);
    }

    /**
     * {@code CDEMO-CT00-INFO} - this screen's {@value PaginationCursor#CURSOR_LENGTH}-byte browse
     * cursor.
     *
     * @return the cursor, never {@code null}
     */
    public PaginationCursor getCursor() {
        return cursor;
    }

    /**
     * Sets the browse cursor.
     *
     * @param cursor the cursor; pass {@code new PaginationCursor()} for a fresh browse rather than
     *               {@code null}
     * @throws NullPointerException if {@code cursor} is {@code null}
     */
    public void setCursor(PaginationCursor cursor) {
        this.cursor = Objects.requireNonNull(cursor, "A pagination cursor is required; pass a fresh "
                + "PaginationCursor() to start a browse rather than null");
    }

    // =================================================================================================
    // ENTER versus REENTER. Exposed as named predicates that delegate to the carried communication
    // area, deliberately NOT duplicated as a field of this type: COBOL has exactly one
    // CDEMO-PGM-CONTEXT, and a second copy could disagree with the COMMAREA the client hands back.
    // =================================================================================================

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry, so the screen is painted and nothing is
     * validated.
     *
     * <p>{@code app/cbl/COTRN00C.cbl:112} branches on this with {@code IF NOT CDEMO-PGM-REENTER} and
     * line 113 immediately flips it, which is what makes the very next call a re-entry.
     *
     * @return {@code true} when the carried context is in the enter state
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1} - re-entry, so what the user typed is received and
     * validated, and a failing field is highlighted.
     *
     * @return {@code true} when the carried context is in the re-enter state
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} as carried by the communication area.
     *
     * <p>Excluded from the payload because it is already on the wire inside
     * {@link #getNavigationContext()}; publishing it twice is how the two copies would start to
     * disagree.
     *
     * <p>{@value NavigationContext#PGM_CONTEXT_ENTER} is also reported when no communication area
     * travelled, because a cold start is a first entry in every sense the program acts on - line 108
     * paints and validates nothing. Use {@link #hasNavigationContext()} where the two must be told
     * apart; {@link #isEnter()} keeps them apart by reporting {@code false} for the cold start.
     *
     * @return {@value NavigationContext#PGM_CONTEXT_ENTER} on first entry and when no area travelled,
     *         {@value NavigationContext#PGM_CONTEXT_REENTER} on re-entry
     */
    @JsonIgnore
    public int getPgmContext() {
        return hasNavigationContext()
                ? navigationContext.pgmContext()
                : NavigationContext.PGM_CONTEXT_ENTER;
    }

    // =================================================================================================
    // The 50 row accessors, declared one per field under the verbatim copybook name. Jackson's default
    // derivation turns getSel0001 into "sel0001" and getTamt001 into "tamt001", so the JSON property
    // names are the copybook names lower-cased and the four differing suffix widths survive onto the
    // wire. The indexed accessors below are for callers that iterate; these are the payload contract.
    // =================================================================================================

    /** @return {@code SEL0001I PIC X(1)}, row 1 selector. */
    public String getSel0001() {
        return sel0001;
    }

    /**
     * Sets {@code SEL0001I}.
     *
     * @param sel0001 the row 1 selector character
     * @throws NullPointerException if {@code sel0001} is {@code null}
     */
    public void setSel0001(String sel0001) {
        this.sel0001 = requirePicX(sel0001, SELECTION_LENGTH, selectionFieldName(1));
    }

    /** @return {@code TRNID01I PIC X(16)}, row 1 transaction identifier. */
    public String getTrnid01() {
        return trnid01;
    }

    /**
     * Sets {@code TRNID01I}.
     *
     * @param trnid01 the row 1 transaction identifier
     * @throws NullPointerException if {@code trnid01} is {@code null}
     */
    public void setTrnid01(String trnid01) {
        this.trnid01 = requirePicX(trnid01, TRANSACTION_ID_LENGTH, transactionIdFieldName(1));
    }

    /** @return {@code TDATE01I PIC X(8)}, row 1 date. */
    public String getTdate01() {
        return tdate01;
    }

    /**
     * Sets {@code TDATE01I}.
     *
     * @param tdate01 the row 1 date
     * @throws NullPointerException if {@code tdate01} is {@code null}
     */
    public void setTdate01(String tdate01) {
        this.tdate01 = requirePicX(tdate01, TRANSACTION_DATE_LENGTH, transactionDateFieldName(1));
    }

    /** @return {@code TDESC01I PIC X(26)}, row 1 description. */
    public String getTdesc01() {
        return tdesc01;
    }

    /**
     * Sets {@code TDESC01I}.
     *
     * @param tdesc01 the row 1 description
     * @throws NullPointerException if {@code tdesc01} is {@code null}
     */
    public void setTdesc01(String tdesc01) {
        this.tdesc01 = requirePicX(tdesc01, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(1));
    }

    /** @return {@code TAMT001I PIC X(12)}, row 1 edited amount. */
    public String getTamt001() {
        return tamt001;
    }

    /**
     * Sets {@code TAMT001I}.
     *
     * @param tamt001 the row 1 edited amount, in the {@code +99999999.99} form; spaces for a blank row
     * @throws NullPointerException if {@code tamt001} is {@code null}
     */
    public void setTamt001(String tamt001) {
        this.tamt001 = requirePicX(tamt001, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(1));
    }

    /** @return {@code SEL0002I PIC X(1)}, row 2 selector. */
    public String getSel0002() {
        return sel0002;
    }

    /**
     * Sets {@code SEL0002I}.
     *
     * @param sel0002 the row 2 selector character
     * @throws NullPointerException if {@code sel0002} is {@code null}
     */
    public void setSel0002(String sel0002) {
        this.sel0002 = requirePicX(sel0002, SELECTION_LENGTH, selectionFieldName(2));
    }

    /** @return {@code TRNID02I PIC X(16)}, row 2 transaction identifier. */
    public String getTrnid02() {
        return trnid02;
    }

    /**
     * Sets {@code TRNID02I}.
     *
     * @param trnid02 the row 2 transaction identifier
     * @throws NullPointerException if {@code trnid02} is {@code null}
     */
    public void setTrnid02(String trnid02) {
        this.trnid02 = requirePicX(trnid02, TRANSACTION_ID_LENGTH, transactionIdFieldName(2));
    }

    /** @return {@code TDATE02I PIC X(8)}, row 2 date. */
    public String getTdate02() {
        return tdate02;
    }

    /**
     * Sets {@code TDATE02I}.
     *
     * @param tdate02 the row 2 date
     * @throws NullPointerException if {@code tdate02} is {@code null}
     */
    public void setTdate02(String tdate02) {
        this.tdate02 = requirePicX(tdate02, TRANSACTION_DATE_LENGTH, transactionDateFieldName(2));
    }

    /** @return {@code TDESC02I PIC X(26)}, row 2 description. */
    public String getTdesc02() {
        return tdesc02;
    }

    /**
     * Sets {@code TDESC02I}.
     *
     * @param tdesc02 the row 2 description
     * @throws NullPointerException if {@code tdesc02} is {@code null}
     */
    public void setTdesc02(String tdesc02) {
        this.tdesc02 = requirePicX(tdesc02, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(2));
    }

    /** @return {@code TAMT002I PIC X(12)}, row 2 edited amount. */
    public String getTamt002() {
        return tamt002;
    }

    /**
     * Sets {@code TAMT002I}.
     *
     * @param tamt002 the row 2 edited amount
     * @throws NullPointerException if {@code tamt002} is {@code null}
     */
    public void setTamt002(String tamt002) {
        this.tamt002 = requirePicX(tamt002, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(2));
    }

    /** @return {@code SEL0003I PIC X(1)}, row 3 selector. */
    public String getSel0003() {
        return sel0003;
    }

    /**
     * Sets {@code SEL0003I}.
     *
     * @param sel0003 the row 3 selector character
     * @throws NullPointerException if {@code sel0003} is {@code null}
     */
    public void setSel0003(String sel0003) {
        this.sel0003 = requirePicX(sel0003, SELECTION_LENGTH, selectionFieldName(3));
    }

    /** @return {@code TRNID03I PIC X(16)}, row 3 transaction identifier. */
    public String getTrnid03() {
        return trnid03;
    }

    /**
     * Sets {@code TRNID03I}.
     *
     * @param trnid03 the row 3 transaction identifier
     * @throws NullPointerException if {@code trnid03} is {@code null}
     */
    public void setTrnid03(String trnid03) {
        this.trnid03 = requirePicX(trnid03, TRANSACTION_ID_LENGTH, transactionIdFieldName(3));
    }

    /** @return {@code TDATE03I PIC X(8)}, row 3 date. */
    public String getTdate03() {
        return tdate03;
    }

    /**
     * Sets {@code TDATE03I}.
     *
     * @param tdate03 the row 3 date
     * @throws NullPointerException if {@code tdate03} is {@code null}
     */
    public void setTdate03(String tdate03) {
        this.tdate03 = requirePicX(tdate03, TRANSACTION_DATE_LENGTH, transactionDateFieldName(3));
    }

    /** @return {@code TDESC03I PIC X(26)}, row 3 description. */
    public String getTdesc03() {
        return tdesc03;
    }

    /**
     * Sets {@code TDESC03I}.
     *
     * @param tdesc03 the row 3 description
     * @throws NullPointerException if {@code tdesc03} is {@code null}
     */
    public void setTdesc03(String tdesc03) {
        this.tdesc03 = requirePicX(tdesc03, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(3));
    }

    /** @return {@code TAMT003I PIC X(12)}, row 3 edited amount. */
    public String getTamt003() {
        return tamt003;
    }

    /**
     * Sets {@code TAMT003I}.
     *
     * @param tamt003 the row 3 edited amount
     * @throws NullPointerException if {@code tamt003} is {@code null}
     */
    public void setTamt003(String tamt003) {
        this.tamt003 = requirePicX(tamt003, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(3));
    }

    /** @return {@code SEL0004I PIC X(1)}, row 4 selector. */
    public String getSel0004() {
        return sel0004;
    }

    /**
     * Sets {@code SEL0004I}.
     *
     * @param sel0004 the row 4 selector character
     * @throws NullPointerException if {@code sel0004} is {@code null}
     */
    public void setSel0004(String sel0004) {
        this.sel0004 = requirePicX(sel0004, SELECTION_LENGTH, selectionFieldName(4));
    }

    /** @return {@code TRNID04I PIC X(16)}, row 4 transaction identifier. */
    public String getTrnid04() {
        return trnid04;
    }

    /**
     * Sets {@code TRNID04I}.
     *
     * @param trnid04 the row 4 transaction identifier
     * @throws NullPointerException if {@code trnid04} is {@code null}
     */
    public void setTrnid04(String trnid04) {
        this.trnid04 = requirePicX(trnid04, TRANSACTION_ID_LENGTH, transactionIdFieldName(4));
    }

    /** @return {@code TDATE04I PIC X(8)}, row 4 date. */
    public String getTdate04() {
        return tdate04;
    }

    /**
     * Sets {@code TDATE04I}.
     *
     * @param tdate04 the row 4 date
     * @throws NullPointerException if {@code tdate04} is {@code null}
     */
    public void setTdate04(String tdate04) {
        this.tdate04 = requirePicX(tdate04, TRANSACTION_DATE_LENGTH, transactionDateFieldName(4));
    }

    /** @return {@code TDESC04I PIC X(26)}, row 4 description. */
    public String getTdesc04() {
        return tdesc04;
    }

    /**
     * Sets {@code TDESC04I}.
     *
     * @param tdesc04 the row 4 description
     * @throws NullPointerException if {@code tdesc04} is {@code null}
     */
    public void setTdesc04(String tdesc04) {
        this.tdesc04 = requirePicX(tdesc04, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(4));
    }

    /** @return {@code TAMT004I PIC X(12)}, row 4 edited amount. */
    public String getTamt004() {
        return tamt004;
    }

    /**
     * Sets {@code TAMT004I}.
     *
     * @param tamt004 the row 4 edited amount
     * @throws NullPointerException if {@code tamt004} is {@code null}
     */
    public void setTamt004(String tamt004) {
        this.tamt004 = requirePicX(tamt004, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(4));
    }

    /** @return {@code SEL0005I PIC X(1)}, row 5 selector. */
    public String getSel0005() {
        return sel0005;
    }

    /**
     * Sets {@code SEL0005I}.
     *
     * @param sel0005 the row 5 selector character
     * @throws NullPointerException if {@code sel0005} is {@code null}
     */
    public void setSel0005(String sel0005) {
        this.sel0005 = requirePicX(sel0005, SELECTION_LENGTH, selectionFieldName(5));
    }

    /** @return {@code TRNID05I PIC X(16)}, row 5 transaction identifier. */
    public String getTrnid05() {
        return trnid05;
    }

    /**
     * Sets {@code TRNID05I}.
     *
     * @param trnid05 the row 5 transaction identifier
     * @throws NullPointerException if {@code trnid05} is {@code null}
     */
    public void setTrnid05(String trnid05) {
        this.trnid05 = requirePicX(trnid05, TRANSACTION_ID_LENGTH, transactionIdFieldName(5));
    }

    /** @return {@code TDATE05I PIC X(8)}, row 5 date. */
    public String getTdate05() {
        return tdate05;
    }

    /**
     * Sets {@code TDATE05I}.
     *
     * @param tdate05 the row 5 date
     * @throws NullPointerException if {@code tdate05} is {@code null}
     */
    public void setTdate05(String tdate05) {
        this.tdate05 = requirePicX(tdate05, TRANSACTION_DATE_LENGTH, transactionDateFieldName(5));
    }

    /** @return {@code TDESC05I PIC X(26)}, row 5 description. */
    public String getTdesc05() {
        return tdesc05;
    }

    /**
     * Sets {@code TDESC05I}.
     *
     * @param tdesc05 the row 5 description
     * @throws NullPointerException if {@code tdesc05} is {@code null}
     */
    public void setTdesc05(String tdesc05) {
        this.tdesc05 = requirePicX(tdesc05, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(5));
    }

    /** @return {@code TAMT005I PIC X(12)}, row 5 edited amount. */
    public String getTamt005() {
        return tamt005;
    }

    /**
     * Sets {@code TAMT005I}.
     *
     * @param tamt005 the row 5 edited amount
     * @throws NullPointerException if {@code tamt005} is {@code null}
     */
    public void setTamt005(String tamt005) {
        this.tamt005 = requirePicX(tamt005, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(5));
    }

    /** @return {@code SEL0006I PIC X(1)}, row 6 selector. */
    public String getSel0006() {
        return sel0006;
    }

    /**
     * Sets {@code SEL0006I}.
     *
     * @param sel0006 the row 6 selector character
     * @throws NullPointerException if {@code sel0006} is {@code null}
     */
    public void setSel0006(String sel0006) {
        this.sel0006 = requirePicX(sel0006, SELECTION_LENGTH, selectionFieldName(6));
    }

    /** @return {@code TRNID06I PIC X(16)}, row 6 transaction identifier. */
    public String getTrnid06() {
        return trnid06;
    }

    /**
     * Sets {@code TRNID06I}.
     *
     * @param trnid06 the row 6 transaction identifier
     * @throws NullPointerException if {@code trnid06} is {@code null}
     */
    public void setTrnid06(String trnid06) {
        this.trnid06 = requirePicX(trnid06, TRANSACTION_ID_LENGTH, transactionIdFieldName(6));
    }

    /** @return {@code TDATE06I PIC X(8)}, row 6 date. */
    public String getTdate06() {
        return tdate06;
    }

    /**
     * Sets {@code TDATE06I}.
     *
     * @param tdate06 the row 6 date
     * @throws NullPointerException if {@code tdate06} is {@code null}
     */
    public void setTdate06(String tdate06) {
        this.tdate06 = requirePicX(tdate06, TRANSACTION_DATE_LENGTH, transactionDateFieldName(6));
    }

    /** @return {@code TDESC06I PIC X(26)}, row 6 description. */
    public String getTdesc06() {
        return tdesc06;
    }

    /**
     * Sets {@code TDESC06I}.
     *
     * @param tdesc06 the row 6 description
     * @throws NullPointerException if {@code tdesc06} is {@code null}
     */
    public void setTdesc06(String tdesc06) {
        this.tdesc06 = requirePicX(tdesc06, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(6));
    }

    /** @return {@code TAMT006I PIC X(12)}, row 6 edited amount. */
    public String getTamt006() {
        return tamt006;
    }

    /**
     * Sets {@code TAMT006I}.
     *
     * @param tamt006 the row 6 edited amount
     * @throws NullPointerException if {@code tamt006} is {@code null}
     */
    public void setTamt006(String tamt006) {
        this.tamt006 = requirePicX(tamt006, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(6));
    }

    /** @return {@code SEL0007I PIC X(1)}, row 7 selector. */
    public String getSel0007() {
        return sel0007;
    }

    /**
     * Sets {@code SEL0007I}.
     *
     * @param sel0007 the row 7 selector character
     * @throws NullPointerException if {@code sel0007} is {@code null}
     */
    public void setSel0007(String sel0007) {
        this.sel0007 = requirePicX(sel0007, SELECTION_LENGTH, selectionFieldName(7));
    }

    /** @return {@code TRNID07I PIC X(16)}, row 7 transaction identifier. */
    public String getTrnid07() {
        return trnid07;
    }

    /**
     * Sets {@code TRNID07I}.
     *
     * @param trnid07 the row 7 transaction identifier
     * @throws NullPointerException if {@code trnid07} is {@code null}
     */
    public void setTrnid07(String trnid07) {
        this.trnid07 = requirePicX(trnid07, TRANSACTION_ID_LENGTH, transactionIdFieldName(7));
    }

    /** @return {@code TDATE07I PIC X(8)}, row 7 date. */
    public String getTdate07() {
        return tdate07;
    }

    /**
     * Sets {@code TDATE07I}.
     *
     * @param tdate07 the row 7 date
     * @throws NullPointerException if {@code tdate07} is {@code null}
     */
    public void setTdate07(String tdate07) {
        this.tdate07 = requirePicX(tdate07, TRANSACTION_DATE_LENGTH, transactionDateFieldName(7));
    }

    /** @return {@code TDESC07I PIC X(26)}, row 7 description. */
    public String getTdesc07() {
        return tdesc07;
    }

    /**
     * Sets {@code TDESC07I}.
     *
     * @param tdesc07 the row 7 description
     * @throws NullPointerException if {@code tdesc07} is {@code null}
     */
    public void setTdesc07(String tdesc07) {
        this.tdesc07 = requirePicX(tdesc07, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(7));
    }

    /** @return {@code TAMT007I PIC X(12)}, row 7 edited amount. */
    public String getTamt007() {
        return tamt007;
    }

    /**
     * Sets {@code TAMT007I}.
     *
     * @param tamt007 the row 7 edited amount
     * @throws NullPointerException if {@code tamt007} is {@code null}
     */
    public void setTamt007(String tamt007) {
        this.tamt007 = requirePicX(tamt007, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(7));
    }

    /** @return {@code SEL0008I PIC X(1)}, row 8 selector. */
    public String getSel0008() {
        return sel0008;
    }

    /**
     * Sets {@code SEL0008I}.
     *
     * @param sel0008 the row 8 selector character
     * @throws NullPointerException if {@code sel0008} is {@code null}
     */
    public void setSel0008(String sel0008) {
        this.sel0008 = requirePicX(sel0008, SELECTION_LENGTH, selectionFieldName(8));
    }

    /** @return {@code TRNID08I PIC X(16)}, row 8 transaction identifier. */
    public String getTrnid08() {
        return trnid08;
    }

    /**
     * Sets {@code TRNID08I}.
     *
     * @param trnid08 the row 8 transaction identifier
     * @throws NullPointerException if {@code trnid08} is {@code null}
     */
    public void setTrnid08(String trnid08) {
        this.trnid08 = requirePicX(trnid08, TRANSACTION_ID_LENGTH, transactionIdFieldName(8));
    }

    /** @return {@code TDATE08I PIC X(8)}, row 8 date. */
    public String getTdate08() {
        return tdate08;
    }

    /**
     * Sets {@code TDATE08I}.
     *
     * @param tdate08 the row 8 date
     * @throws NullPointerException if {@code tdate08} is {@code null}
     */
    public void setTdate08(String tdate08) {
        this.tdate08 = requirePicX(tdate08, TRANSACTION_DATE_LENGTH, transactionDateFieldName(8));
    }

    /** @return {@code TDESC08I PIC X(26)}, row 8 description. */
    public String getTdesc08() {
        return tdesc08;
    }

    /**
     * Sets {@code TDESC08I}.
     *
     * @param tdesc08 the row 8 description
     * @throws NullPointerException if {@code tdesc08} is {@code null}
     */
    public void setTdesc08(String tdesc08) {
        this.tdesc08 = requirePicX(tdesc08, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(8));
    }

    /** @return {@code TAMT008I PIC X(12)}, row 8 edited amount. */
    public String getTamt008() {
        return tamt008;
    }

    /**
     * Sets {@code TAMT008I}.
     *
     * @param tamt008 the row 8 edited amount
     * @throws NullPointerException if {@code tamt008} is {@code null}
     */
    public void setTamt008(String tamt008) {
        this.tamt008 = requirePicX(tamt008, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(8));
    }

    /** @return {@code SEL0009I PIC X(1)}, row 9 selector. */
    public String getSel0009() {
        return sel0009;
    }

    /**
     * Sets {@code SEL0009I}.
     *
     * @param sel0009 the row 9 selector character
     * @throws NullPointerException if {@code sel0009} is {@code null}
     */
    public void setSel0009(String sel0009) {
        this.sel0009 = requirePicX(sel0009, SELECTION_LENGTH, selectionFieldName(9));
    }

    /** @return {@code TRNID09I PIC X(16)}, row 9 transaction identifier. */
    public String getTrnid09() {
        return trnid09;
    }

    /**
     * Sets {@code TRNID09I}.
     *
     * @param trnid09 the row 9 transaction identifier
     * @throws NullPointerException if {@code trnid09} is {@code null}
     */
    public void setTrnid09(String trnid09) {
        this.trnid09 = requirePicX(trnid09, TRANSACTION_ID_LENGTH, transactionIdFieldName(9));
    }

    /** @return {@code TDATE09I PIC X(8)}, row 9 date. */
    public String getTdate09() {
        return tdate09;
    }

    /**
     * Sets {@code TDATE09I}.
     *
     * @param tdate09 the row 9 date
     * @throws NullPointerException if {@code tdate09} is {@code null}
     */
    public void setTdate09(String tdate09) {
        this.tdate09 = requirePicX(tdate09, TRANSACTION_DATE_LENGTH, transactionDateFieldName(9));
    }

    /** @return {@code TDESC09I PIC X(26)}, row 9 description. */
    public String getTdesc09() {
        return tdesc09;
    }

    /**
     * Sets {@code TDESC09I}.
     *
     * @param tdesc09 the row 9 description
     * @throws NullPointerException if {@code tdesc09} is {@code null}
     */
    public void setTdesc09(String tdesc09) {
        this.tdesc09 = requirePicX(tdesc09, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(9));
    }

    /** @return {@code TAMT009I PIC X(12)}, row 9 edited amount. */
    public String getTamt009() {
        return tamt009;
    }

    /**
     * Sets {@code TAMT009I}.
     *
     * @param tamt009 the row 9 edited amount
     * @throws NullPointerException if {@code tamt009} is {@code null}
     */
    public void setTamt009(String tamt009) {
        this.tamt009 = requirePicX(tamt009, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(9));
    }

    /** @return {@code SEL0010I PIC X(1)}, row 10 selector - four-digit suffix. */
    public String getSel0010() {
        return sel0010;
    }

    /**
     * Sets {@code SEL0010I}.
     *
     * @param sel0010 the row 10 selector character
     * @throws NullPointerException if {@code sel0010} is {@code null}
     */
    public void setSel0010(String sel0010) {
        this.sel0010 = requirePicX(sel0010, SELECTION_LENGTH, selectionFieldName(ROW_COUNT));
    }

    /** @return {@code TRNID10I PIC X(16)}, row 10 transaction identifier. */
    public String getTrnid10() {
        return trnid10;
    }

    /**
     * Sets {@code TRNID10I}.
     *
     * @param trnid10 the row 10 transaction identifier
     * @throws NullPointerException if {@code trnid10} is {@code null}
     */
    public void setTrnid10(String trnid10) {
        this.trnid10 = requirePicX(trnid10, TRANSACTION_ID_LENGTH, transactionIdFieldName(ROW_COUNT));
    }

    /** @return {@code TDATE10I PIC X(8)}, row 10 date. */
    public String getTdate10() {
        return tdate10;
    }

    /**
     * Sets {@code TDATE10I}.
     *
     * @param tdate10 the row 10 date
     * @throws NullPointerException if {@code tdate10} is {@code null}
     */
    public void setTdate10(String tdate10) {
        this.tdate10 = requirePicX(tdate10, TRANSACTION_DATE_LENGTH,
                transactionDateFieldName(ROW_COUNT));
    }

    /** @return {@code TDESC10I PIC X(26)}, row 10 description. */
    public String getTdesc10() {
        return tdesc10;
    }

    /**
     * Sets {@code TDESC10I}.
     *
     * @param tdesc10 the row 10 description
     * @throws NullPointerException if {@code tdesc10} is {@code null}
     */
    public void setTdesc10(String tdesc10) {
        this.tdesc10 = requirePicX(tdesc10, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(ROW_COUNT));
    }

    /** @return {@code TAMT010I PIC X(12)}, row 10 edited amount - three-digit suffix. */
    public String getTamt010() {
        return tamt010;
    }

    /**
     * Sets {@code TAMT010I}.
     *
     * @param tamt010 the row 10 edited amount
     * @throws NullPointerException if {@code tamt010} is {@code null}
     */
    public void setTamt010(String tamt010) {
        this.tamt010 = requirePicX(tamt010, TRANSACTION_AMOUNT_LENGTH,
                transactionAmountFieldName(ROW_COUNT));
    }

    // =================================================================================================
    // Row access by the 1-based COBOL screen row.
    //
    // COTRN00C addresses rows through WS-IDX, running 1..10 forwards and 10..1 backwards, and dispatches
    // on it with EVALUATE WS-IDX WHEN 1 ... WHEN 10 (lines 390-445 and 452-505). These accessors are the
    // Java equivalent of that dispatch, and they take the COBOL subscript unchanged so no call site ever
    // performs the 1-based-to-0-based shift itself. Row 1 reaches SEL0001/TRNID01/TAMT001 and row 10
    // reaches SEL0010/TRNID10/TAMT010; both ends are asserted by the accompanying tests.
    // =================================================================================================

    /**
     * A row's selector field, {@code SEL000nI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return exactly {@value #SELECTION_LENGTH} character
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public String getSelection(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> sel0001;
            case 2 -> sel0002;
            case 3 -> sel0003;
            case 4 -> sel0004;
            case 5 -> sel0005;
            case 6 -> sel0006;
            case 7 -> sel0007;
            case 8 -> sel0008;
            case 9 -> sel0009;
            default -> sel0010;
        };
    }

    /**
     * Sets a row's selector field, {@code SEL000nI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @param value       the selector character
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     * @throws NullPointerException      if {@code value} is {@code null}
     */
    public void setSelection(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setSel0001(value);
            case 2 -> setSel0002(value);
            case 3 -> setSel0003(value);
            case 4 -> setSel0004(value);
            case 5 -> setSel0005(value);
            case 6 -> setSel0006(value);
            case 7 -> setSel0007(value);
            case 8 -> setSel0008(value);
            case 9 -> setSel0009(value);
            default -> setSel0010(value);
        }
    }

    /**
     * A row's transaction identifier, {@code TRNIDnnI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return exactly {@value #TRANSACTION_ID_LENGTH} characters
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public String getTransactionId(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> trnid01;
            case 2 -> trnid02;
            case 3 -> trnid03;
            case 4 -> trnid04;
            case 5 -> trnid05;
            case 6 -> trnid06;
            case 7 -> trnid07;
            case 8 -> trnid08;
            case 9 -> trnid09;
            default -> trnid10;
        };
    }

    /**
     * Sets a row's transaction identifier, {@code TRNIDnnI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @param value       the transaction identifier
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     * @throws NullPointerException      if {@code value} is {@code null}
     */
    public void setTransactionId(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTrnid01(value);
            case 2 -> setTrnid02(value);
            case 3 -> setTrnid03(value);
            case 4 -> setTrnid04(value);
            case 5 -> setTrnid05(value);
            case 6 -> setTrnid06(value);
            case 7 -> setTrnid07(value);
            case 8 -> setTrnid08(value);
            case 9 -> setTrnid09(value);
            default -> setTrnid10(value);
        }
    }

    /**
     * A row's date, {@code TDATEnnI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return exactly {@value #TRANSACTION_DATE_LENGTH} characters
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public String getTransactionDate(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> tdate01;
            case 2 -> tdate02;
            case 3 -> tdate03;
            case 4 -> tdate04;
            case 5 -> tdate05;
            case 6 -> tdate06;
            case 7 -> tdate07;
            case 8 -> tdate08;
            case 9 -> tdate09;
            default -> tdate10;
        };
    }

    /**
     * Sets a row's date, {@code TDATEnnI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @param value       the date
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     * @throws NullPointerException      if {@code value} is {@code null}
     */
    public void setTransactionDate(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTdate01(value);
            case 2 -> setTdate02(value);
            case 3 -> setTdate03(value);
            case 4 -> setTdate04(value);
            case 5 -> setTdate05(value);
            case 6 -> setTdate06(value);
            case 7 -> setTdate07(value);
            case 8 -> setTdate08(value);
            case 9 -> setTdate09(value);
            default -> setTdate10(value);
        }
    }

    /**
     * A row's description, {@code TDESCnnI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return exactly {@value #TRANSACTION_DESCRIPTION_LENGTH} characters
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public String getTransactionDescription(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> tdesc01;
            case 2 -> tdesc02;
            case 3 -> tdesc03;
            case 4 -> tdesc04;
            case 5 -> tdesc05;
            case 6 -> tdesc06;
            case 7 -> tdesc07;
            case 8 -> tdesc08;
            case 9 -> tdesc09;
            default -> tdesc10;
        };
    }

    /**
     * Sets a row's description, {@code TDESCnnI}.
     *
     * <p>{@code TRAN-DESC} is {@code PIC X(100)} in the record and this field is {@code PIC X(26)}, so
     * a full description is truncated on the right - the COBOL alphanumeric {@code MOVE} rule, applied
     * here by the same codec the rest of the module uses.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @param value       the description
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     * @throws NullPointerException      if {@code value} is {@code null}
     */
    public void setTransactionDescription(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTdesc01(value);
            case 2 -> setTdesc02(value);
            case 3 -> setTdesc03(value);
            case 4 -> setTdesc04(value);
            case 5 -> setTdesc05(value);
            case 6 -> setTdesc06(value);
            case 7 -> setTdesc07(value);
            case 8 -> setTdesc08(value);
            case 9 -> setTdesc09(value);
            default -> setTdesc10(value);
        }
    }

    /**
     * A row's edited amount, {@code TAMT00nI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @return exactly {@value #TRANSACTION_AMOUNT_LENGTH} characters; spaces for a blank row
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     */
    public String getTransactionAmount(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> tamt001;
            case 2 -> tamt002;
            case 3 -> tamt003;
            case 4 -> tamt004;
            case 5 -> tamt005;
            case 6 -> tamt006;
            case 7 -> tamt007;
            case 8 -> tamt008;
            case 9 -> tamt009;
            default -> tamt010;
        };
    }

    /**
     * Sets a row's edited amount, {@code TAMT00nI}.
     *
     * <p>The value is the {@code +99999999.99} edit form produced by
     * {@code MOVE TRAN-AMT TO WS-TRAN-AMT}, not the raw {@code PIC S9(09)V99} record field. Producing
     * that form is the controller's work; a blank row is spaces.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@value #ROW_COUNT} inclusive
     * @param value       the edited amount
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@value #ROW_COUNT}
     * @throws NullPointerException      if {@code value} is {@code null}
     */
    public void setTransactionAmount(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTamt001(value);
            case 2 -> setTamt002(value);
            case 3 -> setTamt003(value);
            case 4 -> setTamt004(value);
            case 5 -> setTamt005(value);
            case 6 -> setTamt006(value);
            case 7 -> setTamt007(value);
            case 8 -> setTamt008(value);
            case 9 -> setTamt009(value);
            default -> setTamt010(value);
        }
    }

    // =================================================================================================
    // The name-keyed view. This is what field-for-field diffing consumes: values reached by the verbatim
    // copybook name, in declaration order, with nothing trimmed.
    // =================================================================================================

    /**
     * The payload value of a field, addressed by its verbatim base name.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}, for example {@code TAMT001}
     * @return the value at exactly that field's declared width
     * @throws NullPointerException     if {@code baseFieldName} is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the
     *                                  {@value #FIELD_COUNT} fields of this map
     */
    @JsonIgnore
    public String getPayloadValue(String baseFieldName) {
        String name = requireFieldName(baseFieldName);
        if (TRNNAME_FIELD.equals(name)) {
            return trnname;
        }
        if (TITLE01_FIELD.equals(name)) {
            return title01;
        }
        if (CURDATE_FIELD.equals(name)) {
            return curdate;
        }
        if (PGMNAME_FIELD.equals(name)) {
            return pgmname;
        }
        if (TITLE02_FIELD.equals(name)) {
            return title02;
        }
        if (CURTIME_FIELD.equals(name)) {
            return curtime;
        }
        if (PAGENUM_FIELD.equals(name)) {
            return pagenum;
        }
        if (TRNIDIN_FIELD.equals(name)) {
            return trnidin;
        }
        if (ERRMSG_FIELD.equals(name)) {
            return errmsg;
        }
        for (int row = 1; row <= ROW_COUNT; row++) {
            if (selectionFieldName(row).equals(name)) {
                return getSelection(row);
            }
            if (transactionIdFieldName(row).equals(name)) {
                return getTransactionId(row);
            }
            if (transactionDateFieldName(row).equals(name)) {
                return getTransactionDate(row);
            }
            if (transactionDescriptionFieldName(row).equals(name)) {
                return getTransactionDescription(row);
            }
            if (transactionAmountFieldName(row).equals(name)) {
                return getTransactionAmount(row);
            }
        }
        throw new IllegalArgumentException(unknownFieldMessage(name));
    }

    /**
     * Sets the payload value of a field, addressed by its verbatim base name. The value is stored
     * through that field's own setter, so the {@code PIC X} width rule still applies.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}
     * @param value         the value to store
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the
     *                                  {@value #FIELD_COUNT} fields of this map
     */
    public void setPayloadValue(String baseFieldName, String value) {
        String name = requireFieldName(baseFieldName);
        if (TRNNAME_FIELD.equals(name)) {
            setTrnname(value);
            return;
        }
        if (TITLE01_FIELD.equals(name)) {
            setTitle01(value);
            return;
        }
        if (CURDATE_FIELD.equals(name)) {
            setCurdate(value);
            return;
        }
        if (PGMNAME_FIELD.equals(name)) {
            setPgmname(value);
            return;
        }
        if (TITLE02_FIELD.equals(name)) {
            setTitle02(value);
            return;
        }
        if (CURTIME_FIELD.equals(name)) {
            setCurtime(value);
            return;
        }
        if (PAGENUM_FIELD.equals(name)) {
            setPagenum(value);
            return;
        }
        if (TRNIDIN_FIELD.equals(name)) {
            setTrnidin(value);
            return;
        }
        if (ERRMSG_FIELD.equals(name)) {
            setErrmsg(value);
            return;
        }
        for (int row = 1; row <= ROW_COUNT; row++) {
            if (selectionFieldName(row).equals(name)) {
                setSelection(row, value);
                return;
            }
            if (transactionIdFieldName(row).equals(name)) {
                setTransactionId(row, value);
                return;
            }
            if (transactionDateFieldName(row).equals(name)) {
                setTransactionDate(row, value);
                return;
            }
            if (transactionDescriptionFieldName(row).equals(name)) {
                setTransactionDescription(row, value);
                return;
            }
            if (transactionAmountFieldName(row).equals(name)) {
                setTransactionAmount(row, value);
                return;
            }
        }
        throw new IllegalArgumentException(unknownFieldMessage(name));
    }

    /**
     * All {@value #FIELD_COUNT} payload values keyed by verbatim base field name, in copybook
     * declaration order.
     *
     * <p>This is the shape field-for-field diffing wants: named fields rather than one concatenated
     * string, so a difference is reported against the field that carries it. Nothing is trimmed.
     *
     * <p>Declaration order is part of the contract, which is why the returned map is an unmodifiable
     * view over a {@link LinkedHashMap} rather than a {@code Map.copyOf} of one: {@code Map.copyOf}
     * makes no ordering guarantee and in practice returns a hash-ordered map, which would report
     * differences in an arbitrary sequence instead of screen order. The backing map is created fresh
     * here and never published anywhere else, so wrapping it is safe.
     *
     * @return an unmodifiable, declaration-ordered map of {@value #FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, String> getPayloadValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String baseFieldName : FIELD_NAMES) {
            values.put(baseFieldName, getPayloadValue(baseFieldName));
        }
        return Collections.unmodifiableMap(values);
    }

    private static String unknownFieldMessage(String name) {
        return "'" + name + "' is not one of the " + FIELD_COUNT + " fields of map " + MAP_NAME
                + "; the row suffixes are deliberately inconsistent, so check the spelling - the "
                + "selector takes four digits (SEL0001), the identifier, date and description take "
                + "two (TRNID01), and the amount takes three (TAMT001)";
    }

    // =================================================================================================
    // Metadata access. Never a JSON payload member: xxxL, xxxF and xxxA are validation and highlight
    // metadata, and only the xxxI items are payload.
    // =================================================================================================

    /**
     * The metadata carrier of one field.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}
     * @return that field's live carrier, so a change through it is visible on this request
     * @throws NullPointerException     if {@code baseFieldName} is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the
     *                                  {@value #FIELD_COUNT} fields of this map
     */
    @JsonIgnore
    public FieldMetadata getMetadata(String baseFieldName) {
        FieldMetadata metadata = fieldMetadata.get(requireFieldName(baseFieldName));
        if (metadata == null) {
            throw new IllegalArgumentException(unknownFieldMessage(baseFieldName));
        }
        return metadata;
    }

    /**
     * Every metadata carrier, keyed by verbatim base field name in declaration order.
     *
     * <p>The map itself is unmodifiable, so no key can be added or removed; the carriers it holds
     * remain live, because a controller legitimately mutates them when it positions the cursor or sets
     * a highlight.
     *
     * @return an unmodifiable view of {@value #FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, FieldMetadata> getFieldMetadata() {
        return Collections.unmodifiableMap(fieldMetadata);
    }

    /**
     * Reproduces {@code MOVE -1 TO xxxL}: asks CICS to place the cursor at the named field.
     *
     * <p>{@code app/cbl/COTRN00C.cbl} issues this at 13 sites, every one of them on
     * {@code TRNIDINL OF COTRN0AI}, so {@code positionCursorAt(TRNIDIN_FIELD)} is the common case.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}
     * @throws NullPointerException     if {@code baseFieldName} is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the
     *                                  {@value #FIELD_COUNT} fields of this map
     */
    public void positionCursorAt(String baseFieldName) {
        getMetadata(baseFieldName).requestCursorPosition();
    }

    /**
     * The field currently carrying the cursor-positioning request, if any.
     *
     * <p>Only one field can hold the cursor, so the first in declaration order wins - which matches
     * CICS, where the last {@code MOVE -1} before the {@code SEND} decides and no {@code SEND} in this
     * program ever has two.
     *
     * @return the verbatim base name of the field whose length item is
     *         {@value #CURSOR_POSITION_REQUEST}, or {@code null} when no field carries it
     */
    @JsonIgnore
    public String getCursorPositionField() {
        for (Map.Entry<String, FieldMetadata> entry : fieldMetadata.entrySet()) {
            if (entry.getValue().isCursorPositionRequested()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // =================================================================================================
    // The fixed-width symbolic-map image. The REST wire format is JSON; this is the mainframe view of
    // the same payload, provided so a parity case can be expressed in the bytes the COBOL would have
    // held and so the SYMBOLIC_MAP_LENGTH total is exercised rather than merely asserted.
    // =================================================================================================

    /**
     * Renders the {@value #FIELD_COUNT} payload fields as the {@value #SYMBOLIC_MAP_LENGTH}-byte
     * {@code COTRN0AI} group image.
     *
     * <p>The {@value #TIOAPFX_PREFIX_LENGTH}-byte {@code TIOAPFX} prefix and every field's
     * {@value #FIELD_PREFIX_LENGTH}-byte metadata prefix are emitted as spaces, which is what
     * {@link FixedWidthRecord#initialise(RecordLayout)} does for a declared {@code FILLER}. Omitting
     * them is not an option: every offset after an omitted span would shift and the image would be the
     * wrong length, which is exactly the failure the declared layout prevents.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return a fresh array of exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode the space to exactly one
     *                                  byte, since a fixed-width span is addressed by absolute byte
     *                                  offset
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render " + SYMBOLIC_MAP_INPUT_GROUP
                + " as bytes; a fixed-width image is bytes in a specific code page, so the code page "
                + "is stated explicitly and never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes the {@value #FIELD_COUNT} payload fields into an existing record area, using that
     * record's own code page.
     *
     * @param record a record area of exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@value #SYMBOLIC_MAP_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write "
                + SYMBOLIC_MAP_INPUT_GROUP + " into");
        if (record.recordLength() != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("Record area is " + record.recordLength()
                    + " byte(s) but " + SYMBOLIC_MAP_INPUT_GROUP + " is " + SYMBOLIC_MAP_LENGTH);
        }
        for (String baseFieldName : FIELD_NAMES) {
            record.writeSpan(LAYOUT.span(inputItemName(baseFieldName)),
                    getPayloadValue(baseFieldName));
        }
    }

    /**
     * Rebuilds a request's {@value #FIELD_COUNT} payload fields from a
     * {@value #SYMBOLIC_MAP_LENGTH}-byte group image. The carried communication area and cursor are
     * not part of that image, so they are returned in their initial state.
     *
     * @param image   exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @param charset the code page the image is encoded in, named explicitly by the caller
     * @return a request holding the {@value #FIELD_COUNT} fields the image carries
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@value #SYMBOLIC_MAP_LENGTH}
     *                                  bytes
     */
    public static TransactionListRequest fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "An image is required to rebuild "
                + SYMBOLIC_MAP_INPUT_GROUP);
        Objects.requireNonNull(charset, "A charset is required to decode a "
                + SYMBOLIC_MAP_INPUT_GROUP + " image; the code page is stated explicitly and never "
                + "taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(image, LAYOUT));
    }

    /**
     * Reads the {@value #FIELD_COUNT} payload fields out of an existing record area, using that
     * record's own code page.
     *
     * <p>Values are taken <strong>untrimmed</strong>: a {@code PIC X} field is space-padded to its
     * declared width and that padding is part of the value the differ compares.
     *
     * @param record a record area of exactly {@value #SYMBOLIC_MAP_LENGTH} bytes
     * @return a request holding the {@value #FIELD_COUNT} fields the record carries
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@value #SYMBOLIC_MAP_LENGTH}
     */
    public static TransactionListRequest readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read "
                + SYMBOLIC_MAP_INPUT_GROUP + " from");
        if (record.recordLength() != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("Record area is " + record.recordLength()
                    + " byte(s) but " + SYMBOLIC_MAP_INPUT_GROUP + " is " + SYMBOLIC_MAP_LENGTH);
        }
        TransactionListRequest request = new TransactionListRequest();
        for (String baseFieldName : FIELD_NAMES) {
            request.setPayloadValue(baseFieldName,
                    record.readSpan(LAYOUT.span(inputItemName(baseFieldName))));
        }
        return request;
    }

    /**
     * Renders the communication area {@code COTRN00C} actually passes on {@code XCTL}: the
     * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} immediately followed by
     * this screen's {@value PaginationCursor#CURSOR_LENGTH}-byte {@code CDEMO-CT00-INFO} extension,
     * that is {@value PaginationCursor#COMMAREA_LENGTH} bytes in total.
     *
     * <p>The two structures are rendered by their own owners and concatenated, which is precisely how
     * the COBOL lays them out - the extension is declared at the same {@code 05} level as the
     * copybook's own groups, immediately after {@code COPY COCOM01Y}.
     *
     * <p>There has to <em>be</em> an area to render. When none travelled with the request -
     * {@link #hasNavigationContext()} is {@code false}, {@link #commareaLength()} is zero - there are
     * no {@value PaginationCursor#COMMAREA_LENGTH} bytes to produce and no defensible substitute:
     * emitting an initialised area would invent the very bytes whose absence
     * {@code app/cbl/COTRN00C.cbl:107} branches on. The call is refused instead.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return a fresh array of exactly {@value PaginationCursor#COMMAREA_LENGTH} bytes
     * @throws NullPointerException  if {@code charset} is {@code null}
     * @throws IllegalStateException if no communication area travelled with this request
     */
    public byte[] toCommareaImage(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render the communication area; the "
                + "code page is stated explicitly and never taken from the platform");
        if (!hasNavigationContext()) {
            throw new IllegalStateException("No communication area travelled with this request, so "
                    + "there are no " + PaginationCursor.COMMAREA_LENGTH + " bytes to render: "
                    + "EIBCALEN is 0, which is the cold start COTRN00C.cbl:107 tests for and answers "
                    + "by transferring to COSGN00C. Test hasNavigationContext() first, or set an area "
                    + "with setNavigationContext");
        }
        byte[] contextImage = navigationContext.toFixedWidth(new FixedWidthCodec(charset));
        byte[] cursorImage = cursor.toFixedWidth(charset);
        byte[] commarea = new byte[PaginationCursor.COMMAREA_LENGTH];
        System.arraycopy(contextImage, 0, commarea, 0, contextImage.length);
        System.arraycopy(cursorImage, 0, commarea, contextImage.length, cursorImage.length);
        return commarea;
    }

    // =================================================================================================
    // Value semantics, so a test can compare two requests directly and a parity case can assert on one.
    // =================================================================================================

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionListRequest that)) {
            return false;
        }
        return getPayloadValues().equals(that.getPayloadValues())
                && Objects.equals(navigationContext, that.navigationContext)
                && Objects.equals(aid, that.aid)
                && cursor.equals(that.cursor)
                && fieldMetadata.equals(that.fieldMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getPayloadValues(), navigationContext, cursor, fieldMetadata, aid);
    }

    /**
     * A diagnostic summary naming the screen, the browse position and the cursor field.
     *
     * <p>The {@value #FIELD_COUNT} field <em>values</em> are deliberately excluded. They carry
     * transaction descriptions and amounts, and a value that appears in a log by default is a value
     * nobody chose to log.
     *
     * @return for example
     *         {@code TransactionListRequest[CT00/COTRN00C, map=COTRN0A, fields=59,
     *         PaginationCursor[page=1, first=, last=, nextPage=N], context=ENTER]}
     */
    @Override
    public String toString() {
        return "TransactionListRequest[" + TRANSACTION_ID + "/" + PROGRAM_NAME
                + ", map=" + MAP_NAME
                + ", fields=" + FIELD_COUNT
                + ", " + cursor
                + ", " + AID_FIELD + "='" + aid + "'"
                // Three states, not two: a cold start is neither ENTER nor REENTER, and printing it as
                // ENTER would hide exactly the distinction this payload was corrected to preserve.
                + ", context=" + (hasNavigationContext()
                        ? (isReenter() ? "REENTER" : "ENTER")
                        : "none (EIBCALEN=0)")
                + "]";
    }
}
