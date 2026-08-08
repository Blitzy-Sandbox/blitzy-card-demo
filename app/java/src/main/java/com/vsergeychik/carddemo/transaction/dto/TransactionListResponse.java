package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The outbound payload of {@code GET /api/transactions} - CICS transaction {@code CT00}, program
 * {@code app/cbl/COTRN00C.cbl} (699 lines), map {@code COTRN0A} of mapset {@code COTRN00}.
 *
 * <p>This is a field-for-field projection of the {@code xxxO} items of
 * {@code 01 COTRN0AO REDEFINES COTRN0AI} in {@code app/cpy-bms/COTRN00.CPY} (the group begins at
 * line 373) and of the 59 name-labelled {@code DFHMDF} definitions in {@code app/bms/COTRN00.bms}.
 * It is the largest map in this package: <strong>59 payload fields</strong>, on a paged
 * browse/list screen.
 *
 * <h2>The symbolic map, transcribed</h2>
 *
 * <p>Both views of the buffer spend exactly seven prefix bytes per field - the input view
 * {@code xxxL COMP PIC S9(4)} (2) + {@code xxxF PICTURE X} (1) + {@code FILLER PICTURE X(4)} (4),
 * and the output view {@code FILLER PICTURE X(3)} (3) + {@code xxxC} + {@code xxxP} + {@code xxxH}
 * + {@code xxxV} (1 each) - so every field's {@code xxxI} and {@code xxxO} items sit at the
 * <em>identical</em> offset. They are storage aliases, not distinct fields:
 *
 * <pre>
 *   01 COTRN0AO REDEFINES COTRN0AI.
 *     02 FILLER PIC X(12).          &lt;- the TIOAPFX=YES prefix, once
 *     02 FILLER PICTURE X(3).       &lt;- per field: 3 bytes aliasing xxxL + xxxF
 *     02 xxxC   PICTURE X.          &lt;- metadata: extended COLOR
 *     02 xxxP   PICTURE X.          &lt;- metadata: programmed symbols
 *     02 xxxH   PICTURE X.          &lt;- metadata: extended HILIGHT
 *     02 xxxV   PICTURE X.          &lt;- metadata: VALIDN
 *     02 xxxO   PIC X(n).           &lt;- PAYLOAD, stride 7 + n
 * </pre>
 *
 * <p>{@code COTRN00C} writes through <em>both</em> views and mixes them in adjacent statements -
 * line 325 writes {@code PAGENUMI OF COTRN0AI} for an output-only field, then line 326 writes
 * {@code TRNIDINO OF COTRN0AO} for an input field. The Request/Response split in this package is
 * therefore a <em>directional projection convention</em> over one COBOL buffer, not a read/write
 * partition of it: this type round-trips losslessly and nothing here may assume {@code xxxO} is
 * write-only.
 *
 * <h2>The 59 fields and their byte arithmetic</h2>
 *
 * <pre>
 *   block                fields  width each                        total
 *   -------------------  ------  -------------------------------   -----
 *   header / control          8  4 + 40 + 8 + 8 + 40 + 8 + 8 + 16    132
 *   rows 1..10, 5 each       50  (1 + 16 + 8 + 26 + 12) x 10         630
 *   error line                1  78                                   78
 *   -------------------  ------                                    -----
 *   payload                  59                                      840
 *
 *   COTRN0AO image = 12 (TIOAPFX) + 59 x 7 (per-field prefix) + 840 (payload) = 1265 bytes
 * </pre>
 *
 * <p>Those totals are not merely asserted in prose. {@link #LAYOUT} declares all 355 spans - the
 * 12-byte prefix plus, per field, a 3-byte {@code FILLER}, the four attribute items and the payload
 * item - and {@link RecordLayout} refuses to be constructed unless they are contiguous from offset
 * zero and sum to exactly {@link #RECORD_LENGTH}. A single mistyped width fails at
 * class-initialisation time and names the offending span, rather than silently shifting every byte
 * after it.
 *
 * <h2>Field names are carried verbatim, and the suffixes are genuinely inconsistent</h2>
 *
 * <p>The row-field suffix widths differ between the five columns, and the difference is real - it is
 * how the BMS author labelled the fields, and field-for-field diffing depends on it:
 *
 * <ul>
 *   <li>{@code SEL0001O} .. {@code SEL0010O} - <strong>four</strong> digits</li>
 *   <li>{@code TRNID01O} / {@code TDATE01O} / {@code TDESC01O} .. {@code 10O} -
 *       <strong>two</strong> digits</li>
 *   <li>{@code TAMT001O} .. {@code TAMT010O} - <strong>three</strong> digits, never
 *       {@code TAMT01O}</li>
 * </ul>
 *
 * <p>Every one of the 59 names is declared once, as a literal, in the accessor table this class
 * builds at initialisation; the table then drives {@link #LAYOUT}, the generic accessors, the
 * attribute defaults and the fixed-width image, so no name is ever spelled twice and none is
 * derived by a formatter that could normalise it.
 *
 * <h2>Rows are 1-based on the screen and this API keeps them 1-based</h2>
 *
 * <p>{@code SEL0001O} is row one and {@code SEL0010O} is row ten, matching
 * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10} at {@code COTRN00C:290}. Every
 * row-addressed method on this type therefore takes a <strong>1-based</strong> index in
 * {@link #FIRST_ROW}..{@link #LAST_ROW}; no caller ever converts, and the conversion to a 0-based
 * Java position happens once, privately, inside the row dispatchers. This is the top off-by-one
 * risk in the whole migration, so it is confined to one place and asserted at both ends.
 *
 * <h2>The page size is behaviour, not configuration</h2>
 *
 * <p>{@link #PAGE_SIZE} is {@value #PAGE_SIZE}, a compile-time constant equal to the number of row
 * groups this map declares. {@code COTRN00C} hard-codes it four times - the forward initialise loop
 * at line 290, the forward fill loop {@code UNTIL WS-IDX &gt;= 11} at line 296, and the backward
 * path's {@code MOVE 10 TO WS-IDX} at line 349 with {@code UNTIL WS-IDX &lt;= 0} at line 351. It is
 * deliberately <strong>not</strong> an {@code application.yml} key, not a {@code @Value} and not a
 * payload field: making it tunable would let a deployment change observable behaviour that the
 * COBOL fixes.
 *
 * <h2>Statelessness</h2>
 *
 * <p>Nothing about this conversation lives on the server. The response carries the 160-byte
 * {@link NavigationContext} (the {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}) and the
 * 58-byte {@link TransactionListCursor} ({@code CDEMO-CT00-INFO}, declared in place at
 * {@code COTRN00C:62-70}), and the client sends both back on the next call. There is no
 * {@code HttpSession}, no {@code @SessionAttributes}, no static cache and no server-held browse
 * position. The commarea {@code COTRN00C} passes is 160 + 58 = 218 bytes, which
 * {@link TransactionListCursor#COMMAREA_WITH_CURSOR_LENGTH} declares.
 *
 * <h2>Every payload member is a {@code String}</h2>
 *
 * <p>There is no {@code BigDecimal}, {@code int}, {@code double} or {@code float} payload member
 * here, because every {@code xxxO} item is {@code PIC X(n)}:
 *
 * <ul>
 *   <li>{@code TAMT001O}..{@code TAMT010O} are {@code X(12)} <strong>edited</strong> amounts. The
 *       record field {@code TRAN-AMT} is {@code PIC S9(09)V99} (11 bytes, nine integer digits) in
 *       {@code app/cpy/CVTRA05Y.cpy}, but the screen carries
 *       {@code 05 WS-TRAN-AMT PIC +99999999.99} [{@code COTRN00C:56}] - sign, eight integer digits,
 *       a point and two fraction digits, exactly twelve characters. The mask holds one fewer integer
 *       digit than the record, so a record-to-screen move genuinely left-truncates the ninth digit;
 *       that is preserved, not "fixed" by widening the field. Producing the edited form is the
 *       service's work, so this type accepts it as text and performs no arithmetic - which is also
 *       why it has no floating-point type anywhere and needs no rounding mode.</li>
 *   <li>{@code PAGENUMO} is {@code X(8)} even though the cursor's {@code CDEMO-CT00-PAGE-NUM} is
 *       {@code PIC 9(08)}: {@code COTRN00C:324} moves the numeric item into the alphanumeric screen
 *       item. {@link #movePageNumberToScreen(int)} performs that move through the codec so the
 *       zero-filled eight-digit image is produced by the same rule COBOL uses.</li>
 * </ul>
 *
 * <h2>Rules</h2>
 *
 * <p>{@code review_rules} reports that <strong>no user rules were provided</strong> for this
 * project, so no project rule governs this file. The enterprise-practice substitutes of the Agent
 * Action Plan bind instead, and the ones this file answers to are: no new dependency coordinate and
 * no Lombok or MapStruct (nothing here but Jackson's {@code @JsonIgnore} and the JDK); the COBOL,
 * copybook and BMS sources are read-only; names verbatim; all 59 fields projected even where a code
 * path blanks one; no masking or redaction of any field; no wildcard imports; space padding survives
 * a JSON round trip untrimmed; no locally declared Jackson naming strategy, {@code @JsonInclude},
 * {@code @JsonNaming} or {@code ObjectMapper} - the web configuration owns those module-wide; no
 * static mutable state; constructible and assertable without Spring; and width rendering delegated
 * to {@link FixedWidthCodec}.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Instances are mutable and are <strong>not</strong> thread-safe: one belongs to one request, is
 * populated by that request's handler and is then serialised. That is deliberate - a screen buffer
 * is per-conversation state, and sharing one across requests is exactly the aliasing this migration
 * has to avoid. Everything shared is immutable: the layout, the accessor table and the name lists
 * are deeply immutable constants, and there is no mutable static field anywhere in this class.
 *
 * @see TransactionListCursor for the 58-byte pagination cursor
 * @see NavigationContext for the 160-byte communication area this response echoes
 */
public final class TransactionListResponse {

    // =================================================================================================
    // Identity: the CSD transaction, the program, and the mapset and map names. All four are literals
    // in the sources and are reproduced verbatim.
    // =================================================================================================

    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CT00'} [{@code COTRN00C:37}], which
     * {@code POPULATE-HEADER-INFO} moves into {@code TRNNAMEO} at line 573. This is the transaction
     * {@code app/csd/CARDDEMO.CSD:419-420} binds to {@code PROGRAM(COTRN00C)}.
     */
    public static final String TRANSACTION_ID = "CT00";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COTRN00C'} [{@code COTRN00C:36}], which
     * {@code POPULATE-HEADER-INFO} moves into {@code PGMNAMEO} at line 574.
     */
    public static final String PROGRAM_NAME = "COTRN00C";

    /**
     * The mapset, {@code COTRN00 DFHMSD} in {@code app/bms/COTRN00.bms} and
     * {@code DEFINE MAPSET(COTRN00)} in {@code app/csd/CARDDEMO.CSD:145}. Seven characters, which is
     * exactly the width of {@code CDEMO-LAST-MAPSET PIC X(7)}.
     */
    public static final String MAPSET_NAME = "COTRN00";

    /**
     * The map, {@code COTRN0A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)} in {@code app/bms/COTRN00.bms},
     * named in every {@code SEND MAP('COTRN0A')} and {@code RECEIVE MAP('COTRN0A')}. Seven
     * characters, exactly the width of {@code CDEMO-LAST-MAP PIC X(7)}.
     */
    public static final String MAP_NAME = "COTRN0A";

    /**
     * The symbolic output group this type projects: {@code 01 COTRN0AO REDEFINES COTRN0AI}
     * [{@code app/cpy-bms/COTRN00.CPY:373}]. Carried as a constant because
     * {@link FieldAttributeSetter} records it as the {@code (MAPNAME3)} token of the
     * {@code app/cpy/CSSETATY.cpy} highlight decision.
     */
    public static final String OUTPUT_MAP_GROUP_NAME =
            MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX;

    // =================================================================================================
    // Pagination geometry. PAGE_SIZE is behaviour: it is the number of row groups the map declares and
    // the bound COTRN00C hard-codes, and it is intentionally not configurable.
    // =================================================================================================

    /**
     * The page size, {@value #PAGE_SIZE} - the number of transaction rows {@code COTRN0A} declares and
     * the loop bound {@code COTRN00C} hard-codes at lines 290, 296, 349 and 351.
     *
     * <p>Not configurable, by design: see the class documentation.
     */
    public static final int PAGE_SIZE = 10;

    /**
     * The number of row groups in the map, which <em>is</em> {@link #PAGE_SIZE}. Declared as a second
     * name because the two ideas are distinct in principle - a screen could show fewer rows than it
     * declares - and identical in this map; tying them together in code is what guarantees they cannot
     * drift apart.
     */
    public static final int ROW_COUNT = PAGE_SIZE;

    /** The first screen row, 1. Screen rows are 1-based; see the class documentation. */
    public static final int FIRST_ROW = 1;

    /** The last screen row, {@value #LAST_ROW}. */
    public static final int LAST_ROW = ROW_COUNT;

    // =================================================================================================
    // Field counts and declared widths, taken from the xxxO PICTURE clauses and cross-checked against
    // the BMS LENGTH= of the matching name-labelled DFHMDF. Every one of the 59 was verified equal.
    // =================================================================================================

    /** The header/control block: {@value #HEADER_FIELD_COUNT} fields. */
    public static final int HEADER_FIELD_COUNT = 8;

    /** Fields per transaction row: {@value #ROW_FIELD_COUNT} - selector, id, date, description, amount. */
    public static final int ROW_FIELD_COUNT = 5;

    /** The error line: {@value #ERROR_FIELD_COUNT} field, {@code ERRMSGO}. */
    public static final int ERROR_FIELD_COUNT = 1;

    /**
     * The total payload field count, {@value #FIELD_COUNT} - which is
     * {@link #HEADER_FIELD_COUNT} + {@link #ROW_COUNT} x {@link #ROW_FIELD_COUNT} +
     * {@link #ERROR_FIELD_COUNT}. {@code app/bms/COTRN00.bms} declares 89 {@code DFHMDF} entries of
     * which exactly these 59 carry a name; the other 30 are unlabelled literals and are not payload.
     */
    public static final int FIELD_COUNT =
            HEADER_FIELD_COUNT + ROW_COUNT * ROW_FIELD_COUNT + ERROR_FIELD_COUNT;

    /** {@code TRNNAMEO PIC X(4)}; {@code DFHMDF LENGTH=4 POS=(1,7)}. */
    public static final int TRNNAME_LENGTH = 4;

    /** {@code TITLE01O PIC X(40)}; {@code DFHMDF LENGTH=40 POS=(1,21)}. Equals {@link ScreenTitles#TITLE_LENGTH}. */
    public static final int TITLE01_LENGTH = 40;

    /** {@code CURDATEO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(1,71)}. */
    public static final int CURDATE_LENGTH = 8;

    /** {@code PGMNAMEO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(2,7)}. */
    public static final int PGMNAME_LENGTH = 8;

    /** {@code TITLE02O PIC X(40)}; {@code DFHMDF LENGTH=40 POS=(2,21)}. */
    public static final int TITLE02_LENGTH = 40;

    /** {@code CURTIMEO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(2,71)}. */
    public static final int CURTIME_LENGTH = 8;

    /** {@code PAGENUMO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(4,71)}. Alphanumeric, not numeric. */
    public static final int PAGENUM_LENGTH = 8;

    /** {@code TRNIDINO PIC X(16)}; {@code DFHMDF LENGTH=16 POS=(6,21)}, the browse-start key. */
    public static final int TRNIDIN_LENGTH = 16;

    /** {@code SEL000nO PIC X(1)}; {@code DFHMDF LENGTH=1 POS=(9+n,3)}, the row selector. */
    public static final int SEL_LENGTH = 1;

    /** {@code TRNIDnnO PIC X(16)}; {@code DFHMDF LENGTH=16 POS=(9+n,8)}. Matches {@code TRAN-ID PIC X(16)}. */
    public static final int TRNID_LENGTH = 16;

    /** {@code TDATEnnO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(9+n,27)}. Carries {@code MM/DD/YY}. */
    public static final int TDATE_LENGTH = 8;

    /**
     * {@code TDESCnnO PIC X(26)}; {@code DFHMDF LENGTH=26 POS=(9+n,38)}. The record's
     * {@code TRAN-DESC} is {@code PIC X(100)}, so the move at {@code COTRN00C:395} truncates on the
     * right - the COBOL rule for a {@code PIC X} receiver.
     */
    public static final int TDESC_LENGTH = 26;

    /**
     * {@code TAMT00nO PIC X(12)}; {@code DFHMDF LENGTH=12 POS=(9+n,67)}. Twelve is exactly the width
     * of the {@code PIC +99999999.99} edit mask: sign + 8 + point + 2.
     */
    public static final int TAMT_LENGTH = 12;

    /** {@code ERRMSGO PIC X(78)}; {@code DFHMDF LENGTH=78 POS=(23,1) ATTRB=(ASKIP,BRT,FSET) COLOR=RED}. */
    public static final int ERRMSG_LENGTH = 78;

    // =================================================================================================
    // The image geometry: the TIOAPFX prefix, the per-field attribute prefix, and the resulting totals.
    // =================================================================================================

    /**
     * The leading {@code 02 FILLER PIC X(12)} of both symbolic groups, present because every mapset
     * declares {@code TIOAPFX=YES}. It carries no field and must still be emitted, or every offset
     * after it is wrong.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that opens each field group in the output view, aliasing the
     * input view's {@code xxxL COMP PIC S9(4)} (2 bytes) and {@code xxxF PICTURE X} (1 byte).
     */
    public static final int ATTRIBUTE_FILLER_LENGTH = 3;

    /** Each of {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} is {@code PICTURE X}: 1 byte. */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    /** The attribute quad per field: {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}. */
    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    /**
     * The per-field prefix in the output view: {@value #FIELD_PREFIX_LENGTH} bytes, the
     * {@link #ATTRIBUTE_FILLER_LENGTH}-byte filler plus the {@link #ATTRIBUTE_ITEM_COUNT} attribute
     * items. The input view spends the same seven bytes as 2 + 1 + 4, which is precisely why
     * {@code xxxI} and {@code xxxO} alias.
     */
    public static final int FIELD_PREFIX_LENGTH =
            ATTRIBUTE_FILLER_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    /** The header block's payload width: 4 + 40 + 8 + 8 + 40 + 8 + 8 + 16 = {@value #HEADER_WIDTH_TOTAL}. */
    public static final int HEADER_WIDTH_TOTAL = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + PAGENUM_LENGTH + TRNIDIN_LENGTH;

    /** One row's payload width: 1 + 16 + 8 + 26 + 12 = {@value #ROW_WIDTH}. */
    public static final int ROW_WIDTH =
            SEL_LENGTH + TRNID_LENGTH + TDATE_LENGTH + TDESC_LENGTH + TAMT_LENGTH;

    /** All ten rows' payload width: {@value #ROW_COUNT} x {@value #ROW_WIDTH} = {@value #ROW_BLOCK_WIDTH}. */
    public static final int ROW_BLOCK_WIDTH = ROW_COUNT * ROW_WIDTH;

    /** The payload width of the whole map: 132 + 630 + 78 = {@value #PAYLOAD_WIDTH_TOTAL}. */
    public static final int PAYLOAD_WIDTH_TOTAL = HEADER_WIDTH_TOTAL + ROW_BLOCK_WIDTH + ERRMSG_LENGTH;

    /**
     * The full {@code COTRN0AO} group image:
     * 12 + 59 x 7 + 840 = {@value #RECORD_LENGTH} bytes. {@link #LAYOUT} is declared at this length
     * and validates itself against the sum of its spans.
     */
    public static final int RECORD_LENGTH =
            TIOAPFX_PREFIX_LENGTH + FIELD_COUNT * FIELD_PREFIX_LENGTH + PAYLOAD_WIDTH_TOTAL;

    // =================================================================================================
    // Symbolic-map item suffixes. The colour and output suffixes are taken from FieldAttributeSetter so
    // that this projection and the highlight setter cannot disagree about which item is which.
    // =================================================================================================

    /** The extended-colour item suffix, {@code C}: {@code TRNNAMEC}, {@code SEL0001C}, and so on. */
    public static final String COLOUR_ITEM_SUFFIX = FieldAttributeSetter.COLOUR_ITEM_SUFFIX;

    /** The programmed-symbols item suffix, {@code P}. */
    public static final String PS_ITEM_SUFFIX = "P";

    /** The extended-highlight item suffix, {@code H}. */
    public static final String HIGHLIGHT_ITEM_SUFFIX = "H";

    /** The validation item suffix, {@code V}. */
    public static final String VALIDATION_ITEM_SUFFIX = "V";

    /** The payload item suffix, {@code O}: {@code TRNNAMEO}, {@code TAMT001O}, and so on. */
    public static final String OUTPUT_ITEM_SUFFIX = FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;

    /** The name COBOL gives an unnamed reserved span, and the name {@link FieldSpan#filler(int, int)} uses. */
    private static final String FILLER_NAME = "FILLER";

    /** One space, the {@code PIC X} pad character, used to build each field's initial all-blank run. */
    private static final String SPACE = " ";

    /**
     * COBOL {@code LOW-VALUES} as it appears in a character field: the null character, {@code 0x00}.
     *
     * <p>Distinct from {@link FieldAttributes#LOW_VALUE}, which is the same numeric value typed as the
     * <em>attribute byte</em> it is. This one is the character that {@link #isPresent(String)} tests
     * for, because {@code COTRN00C} writes {@code LOW-VALUES} into character fields - line 114 sets
     * the whole group and lines 149 to 184 test selectors against it.
     */
    private static final char LOW_VALUE_CHARACTER = '\u0000';

    // =================================================================================================
    // The 59 field prefixes, spelled VERBATIM as app/bms/COTRN00.bms labels them and as
    // app/cpy-bms/COTRN00.CPY suffixes them. Each is declared exactly once here; every derived name -
    // the payload item, the colour item and the other three attribute items - is this prefix plus the
    // relevant one-character suffix, so no name is ever typed twice.
    //
    // The suffix widths are DELIBERATELY inconsistent and are reproduced, never normalised:
    //   SEL0001..SEL0010  four digits
    //   TRNID01..TRNID10  two digits      TDATE01..TDATE10  two digits
    //   TDESC01..TDESC10  two digits      TAMT001..TAMT010  three digits
    // =================================================================================================

    /** Header field 1: the transaction identifier display, {@code TRNNAMEO PIC X(4)}. */
    public static final String TRNNAME = "TRNNAME";

    /** Header field 2: the upper title line, {@code TITLE01O PIC X(40)}. */
    public static final String TITLE01 = "TITLE01";

    /** Header field 3: the current date, {@code CURDATEO PIC X(8)}. */
    public static final String CURDATE = "CURDATE";

    /** Header field 4: the program name display, {@code PGMNAMEO PIC X(8)}. */
    public static final String PGMNAME = "PGMNAME";

    /** Header field 5: the lower title line, {@code TITLE02O PIC X(40)}. */
    public static final String TITLE02 = "TITLE02";

    /** Header field 6: the current time, {@code CURTIMEO PIC X(8)}. */
    public static final String CURTIME = "CURTIME";

    /** Header field 7: the displayed page number, {@code PAGENUMO PIC X(8)}. */
    public static final String PAGENUM = "PAGENUM";

    /** Header field 8: the browse-start transaction identifier, {@code TRNIDINO PIC X(16)}. */
    public static final String TRNIDIN = "TRNIDIN";

    /** Row 1 selector - note the <strong>four</strong>-digit suffix. */
    public static final String SEL0001 = "SEL0001";

    /** Row 2 selector. */
    public static final String SEL0002 = "SEL0002";

    /** Row 3 selector. */
    public static final String SEL0003 = "SEL0003";

    /** Row 4 selector. */
    public static final String SEL0004 = "SEL0004";

    /** Row 5 selector. */
    public static final String SEL0005 = "SEL0005";

    /** Row 6 selector. */
    public static final String SEL0006 = "SEL0006";

    /** Row 7 selector. */
    public static final String SEL0007 = "SEL0007";

    /** Row 8 selector. */
    public static final String SEL0008 = "SEL0008";

    /** Row 9 selector. */
    public static final String SEL0009 = "SEL0009";

    /** Row 10 selector. */
    public static final String SEL0010 = "SEL0010";

    /** Row 1 transaction identifier - note the <strong>two</strong>-digit suffix. */
    public static final String TRNID01 = "TRNID01";

    /** Row 2 transaction identifier. */
    public static final String TRNID02 = "TRNID02";

    /** Row 3 transaction identifier. */
    public static final String TRNID03 = "TRNID03";

    /** Row 4 transaction identifier. */
    public static final String TRNID04 = "TRNID04";

    /** Row 5 transaction identifier. */
    public static final String TRNID05 = "TRNID05";

    /** Row 6 transaction identifier. */
    public static final String TRNID06 = "TRNID06";

    /** Row 7 transaction identifier. */
    public static final String TRNID07 = "TRNID07";

    /** Row 8 transaction identifier. */
    public static final String TRNID08 = "TRNID08";

    /** Row 9 transaction identifier. */
    public static final String TRNID09 = "TRNID09";

    /** Row 10 transaction identifier. */
    public static final String TRNID10 = "TRNID10";

    /** Row 1 transaction date - note the <strong>two</strong>-digit suffix. */
    public static final String TDATE01 = "TDATE01";

    /** Row 2 transaction date. */
    public static final String TDATE02 = "TDATE02";

    /** Row 3 transaction date. */
    public static final String TDATE03 = "TDATE03";

    /** Row 4 transaction date. */
    public static final String TDATE04 = "TDATE04";

    /** Row 5 transaction date. */
    public static final String TDATE05 = "TDATE05";

    /** Row 6 transaction date. */
    public static final String TDATE06 = "TDATE06";

    /** Row 7 transaction date. */
    public static final String TDATE07 = "TDATE07";

    /** Row 8 transaction date. */
    public static final String TDATE08 = "TDATE08";

    /** Row 9 transaction date. */
    public static final String TDATE09 = "TDATE09";

    /** Row 10 transaction date. */
    public static final String TDATE10 = "TDATE10";

    /** Row 1 description - note the <strong>two</strong>-digit suffix. */
    public static final String TDESC01 = "TDESC01";

    /** Row 2 description. */
    public static final String TDESC02 = "TDESC02";

    /** Row 3 description. */
    public static final String TDESC03 = "TDESC03";

    /** Row 4 description. */
    public static final String TDESC04 = "TDESC04";

    /** Row 5 description. */
    public static final String TDESC05 = "TDESC05";

    /** Row 6 description. */
    public static final String TDESC06 = "TDESC06";

    /** Row 7 description. */
    public static final String TDESC07 = "TDESC07";

    /** Row 8 description. */
    public static final String TDESC08 = "TDESC08";

    /** Row 9 description. */
    public static final String TDESC09 = "TDESC09";

    /** Row 10 description. */
    public static final String TDESC10 = "TDESC10";

    /** Row 1 edited amount - note the <strong>three</strong>-digit suffix, never {@code TAMT01}. */
    public static final String TAMT001 = "TAMT001";

    /** Row 2 edited amount. */
    public static final String TAMT002 = "TAMT002";

    /** Row 3 edited amount. */
    public static final String TAMT003 = "TAMT003";

    /** Row 4 edited amount. */
    public static final String TAMT004 = "TAMT004";

    /** Row 5 edited amount. */
    public static final String TAMT005 = "TAMT005";

    /** Row 6 edited amount. */
    public static final String TAMT006 = "TAMT006";

    /** Row 7 edited amount. */
    public static final String TAMT007 = "TAMT007";

    /** Row 8 edited amount. */
    public static final String TAMT008 = "TAMT008";

    /** Row 9 edited amount. */
    public static final String TAMT009 = "TAMT009";

    /** Row 10 edited amount. */
    public static final String TAMT010 = "TAMT010";

    /** The error line, {@code ERRMSGO PIC X(78)}. */
    public static final String ERRMSG = "ERRMSG";


    // =================================================================================================
    // The single declaration of the map's 59 fields, in copybook order.
    //
    // One table drives everything downstream - LAYOUT, the generic accessors, the attribute defaults,
    // the name registries and the fixed-width image - so the field set, its order and its widths are
    // stated exactly once. A dispatching switch per operation was rejected deliberately: it would state
    // the same 59 names five more times, and the fifth copy is where a name gets normalised.
    //
    // The table is deeply immutable - an unmodifiable LinkedHashMap of immutable records holding
    // stateless method references - so sharing it as a constant introduces no mutable static state.
    // LinkedHashMap rather than Map.copyOf because the ITERATION ORDER IS THE COPYBOOK ORDER, and both
    // the layout and the field-by-field diff depend on it.
    // =================================================================================================

    /**
     * One field of the map: its verbatim prefix, its declared payload width, and the pair of
     * functions that read and write the flat member behind it.
     *
     * @param fieldPrefix    the verbatim {@code DFHMDF} label, for example {@code TAMT001}
     * @param declaredLength the payload item's {@code PIC X(n)} width, which is also the
     *                       {@code DFHMDF LENGTH=}
     * @param reader         reads the flat member, returning the stored value untrimmed
     * @param writer         writes the flat member, through the same validation the setter applies
     */
    private record PayloadField(String fieldPrefix,
                                int declaredLength,
                                Function<TransactionListResponse, String> reader,
                                BiConsumer<TransactionListResponse, String> writer) {

        /**
         * The payload item's name: the prefix plus {@code O}.
         *
         * @return for example {@code TAMT001O}
         */
        String outputItemName() {
            return fieldPrefix + OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The extended-colour item's name: the prefix plus {@code C}, the item
         * {@code app/cpy/CSSETATY.cpy} moves {@code DFHRED} into.
         *
         * @return for example {@code TAMT001C}
         */
        String colourItemName() {
            return fieldPrefix + COLOUR_ITEM_SUFFIX;
        }
    }

    /**
     * The 59 fields in {@code app/cpy-bms/COTRN00.CPY} declaration order, keyed by verbatim prefix.
     *
     * <p>Order is header (8), then rows 1 to 10 with their five columns each (50), then the error
     * line (1). That is the order the copybook declares, the order {@code app/bms/COTRN00.bms} labels
     * its {@code DFHMDF} entries, and therefore the order {@link #LAYOUT} lays bytes out in.
     */
    private static final Map<String, PayloadField> PAYLOAD_FIELDS = buildPayloadFields();

    /**
     * The 59 verbatim field prefixes in copybook order - {@code TRNNAME}, {@code TITLE01}, ...,
     * {@code SEL0001}, {@code TRNID01}, ..., {@code TAMT010}, {@code ERRMSG}.
     *
     * <p>Immutable, and the key set of {@link #PAYLOAD_FIELDS} in its insertion order.
     */
    private static final List<String> FIELD_PREFIXES = List.copyOf(PAYLOAD_FIELDS.keySet());

    /**
     * The 59 verbatim payload item names in copybook order - {@code TRNNAMEO} through
     * {@code ERRMSGO}. This is the list a field-by-field diff keys on, so it is exposed through
     * {@link #payloadFieldNames()} rather than kept private.
     */
    private static final List<String> PAYLOAD_FIELD_NAMES = PAYLOAD_FIELDS.values().stream()
            .map(PayloadField::outputItemName)
            .toList();

    /**
     * The self-checking descriptor list of {@code 01 COTRN0AO REDEFINES COTRN0AI}: 355 spans - the
     * 12-byte {@code TIOAPFX} filler, then per field a 3-byte filler, the four one-byte attribute
     * items and the payload item - summing to exactly {@value #RECORD_LENGTH} bytes.
     *
     * <p>{@link RecordLayout} validates the geometry at class-initialisation time and refuses to
     * construct unless the spans run contiguously from offset zero with no gap and no overlap and sum
     * to {@link #RECORD_LENGTH}. That check is what turns a mistyped width into an immediate,
     * precisely located failure: widen {@code TDESC05} to 27 and the sum becomes 1266 and this
     * constant cannot be built.
     *
     * <p>Public because the parity harness and the field differ need the geometry in order to compare
     * an image field by field. Deeply immutable, so exposing it introduces no mutable static state.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    /**
     * Builds the field table. Each entry names its prefix once, gives the declared width from the
     * {@code xxxO} {@code PICTURE} clause, and binds the flat member's getter and setter.
     *
     * @return an unmodifiable, insertion-ordered map of the 59 fields
     */
    private static Map<String, PayloadField> buildPayloadFields() {
        Map<String, PayloadField> fields = new LinkedHashMap<>();

        // --- header / control block, 8 fields, copybook lines 376-422 -------------------------------
        put(fields, TRNNAME, TRNNAME_LENGTH,
                TransactionListResponse::getTrnnameO, TransactionListResponse::setTrnnameO);
        put(fields, TITLE01, TITLE01_LENGTH,
                TransactionListResponse::getTitle01O, TransactionListResponse::setTitle01O);
        put(fields, CURDATE, CURDATE_LENGTH,
                TransactionListResponse::getCurdateO, TransactionListResponse::setCurdateO);
        put(fields, PGMNAME, PGMNAME_LENGTH,
                TransactionListResponse::getPgmnameO, TransactionListResponse::setPgmnameO);
        put(fields, TITLE02, TITLE02_LENGTH,
                TransactionListResponse::getTitle02O, TransactionListResponse::setTitle02O);
        put(fields, CURTIME, CURTIME_LENGTH,
                TransactionListResponse::getCurtimeO, TransactionListResponse::setCurtimeO);
        put(fields, PAGENUM, PAGENUM_LENGTH,
                TransactionListResponse::getPagenumO, TransactionListResponse::setPagenumO);
        put(fields, TRNIDIN, TRNIDIN_LENGTH,
                TransactionListResponse::getTrnidinO, TransactionListResponse::setTrnidinO);

        // --- row 1, copybook lines 424-452 ---------------------------------------------------------
        put(fields, SEL0001, SEL_LENGTH,
                TransactionListResponse::getSel0001O, TransactionListResponse::setSel0001O);
        put(fields, TRNID01, TRNID_LENGTH,
                TransactionListResponse::getTrnid01O, TransactionListResponse::setTrnid01O);
        put(fields, TDATE01, TDATE_LENGTH,
                TransactionListResponse::getTdate01O, TransactionListResponse::setTdate01O);
        put(fields, TDESC01, TDESC_LENGTH,
                TransactionListResponse::getTdesc01O, TransactionListResponse::setTdesc01O);
        put(fields, TAMT001, TAMT_LENGTH,
                TransactionListResponse::getTamt001O, TransactionListResponse::setTamt001O);

        // --- row 2 --------------------------------------------------------------------------------
        put(fields, SEL0002, SEL_LENGTH,
                TransactionListResponse::getSel0002O, TransactionListResponse::setSel0002O);
        put(fields, TRNID02, TRNID_LENGTH,
                TransactionListResponse::getTrnid02O, TransactionListResponse::setTrnid02O);
        put(fields, TDATE02, TDATE_LENGTH,
                TransactionListResponse::getTdate02O, TransactionListResponse::setTdate02O);
        put(fields, TDESC02, TDESC_LENGTH,
                TransactionListResponse::getTdesc02O, TransactionListResponse::setTdesc02O);
        put(fields, TAMT002, TAMT_LENGTH,
                TransactionListResponse::getTamt002O, TransactionListResponse::setTamt002O);

        // --- row 3 --------------------------------------------------------------------------------
        put(fields, SEL0003, SEL_LENGTH,
                TransactionListResponse::getSel0003O, TransactionListResponse::setSel0003O);
        put(fields, TRNID03, TRNID_LENGTH,
                TransactionListResponse::getTrnid03O, TransactionListResponse::setTrnid03O);
        put(fields, TDATE03, TDATE_LENGTH,
                TransactionListResponse::getTdate03O, TransactionListResponse::setTdate03O);
        put(fields, TDESC03, TDESC_LENGTH,
                TransactionListResponse::getTdesc03O, TransactionListResponse::setTdesc03O);
        put(fields, TAMT003, TAMT_LENGTH,
                TransactionListResponse::getTamt003O, TransactionListResponse::setTamt003O);

        // --- row 4 --------------------------------------------------------------------------------
        put(fields, SEL0004, SEL_LENGTH,
                TransactionListResponse::getSel0004O, TransactionListResponse::setSel0004O);
        put(fields, TRNID04, TRNID_LENGTH,
                TransactionListResponse::getTrnid04O, TransactionListResponse::setTrnid04O);
        put(fields, TDATE04, TDATE_LENGTH,
                TransactionListResponse::getTdate04O, TransactionListResponse::setTdate04O);
        put(fields, TDESC04, TDESC_LENGTH,
                TransactionListResponse::getTdesc04O, TransactionListResponse::setTdesc04O);
        put(fields, TAMT004, TAMT_LENGTH,
                TransactionListResponse::getTamt004O, TransactionListResponse::setTamt004O);

        // --- row 5 --------------------------------------------------------------------------------
        put(fields, SEL0005, SEL_LENGTH,
                TransactionListResponse::getSel0005O, TransactionListResponse::setSel0005O);
        put(fields, TRNID05, TRNID_LENGTH,
                TransactionListResponse::getTrnid05O, TransactionListResponse::setTrnid05O);
        put(fields, TDATE05, TDATE_LENGTH,
                TransactionListResponse::getTdate05O, TransactionListResponse::setTdate05O);
        put(fields, TDESC05, TDESC_LENGTH,
                TransactionListResponse::getTdesc05O, TransactionListResponse::setTdesc05O);
        put(fields, TAMT005, TAMT_LENGTH,
                TransactionListResponse::getTamt005O, TransactionListResponse::setTamt005O);

        // --- row 6 --------------------------------------------------------------------------------
        put(fields, SEL0006, SEL_LENGTH,
                TransactionListResponse::getSel0006O, TransactionListResponse::setSel0006O);
        put(fields, TRNID06, TRNID_LENGTH,
                TransactionListResponse::getTrnid06O, TransactionListResponse::setTrnid06O);
        put(fields, TDATE06, TDATE_LENGTH,
                TransactionListResponse::getTdate06O, TransactionListResponse::setTdate06O);
        put(fields, TDESC06, TDESC_LENGTH,
                TransactionListResponse::getTdesc06O, TransactionListResponse::setTdesc06O);
        put(fields, TAMT006, TAMT_LENGTH,
                TransactionListResponse::getTamt006O, TransactionListResponse::setTamt006O);

        // --- row 7 --------------------------------------------------------------------------------
        put(fields, SEL0007, SEL_LENGTH,
                TransactionListResponse::getSel0007O, TransactionListResponse::setSel0007O);
        put(fields, TRNID07, TRNID_LENGTH,
                TransactionListResponse::getTrnid07O, TransactionListResponse::setTrnid07O);
        put(fields, TDATE07, TDATE_LENGTH,
                TransactionListResponse::getTdate07O, TransactionListResponse::setTdate07O);
        put(fields, TDESC07, TDESC_LENGTH,
                TransactionListResponse::getTdesc07O, TransactionListResponse::setTdesc07O);
        put(fields, TAMT007, TAMT_LENGTH,
                TransactionListResponse::getTamt007O, TransactionListResponse::setTamt007O);

        // --- row 8 --------------------------------------------------------------------------------
        put(fields, SEL0008, SEL_LENGTH,
                TransactionListResponse::getSel0008O, TransactionListResponse::setSel0008O);
        put(fields, TRNID08, TRNID_LENGTH,
                TransactionListResponse::getTrnid08O, TransactionListResponse::setTrnid08O);
        put(fields, TDATE08, TDATE_LENGTH,
                TransactionListResponse::getTdate08O, TransactionListResponse::setTdate08O);
        put(fields, TDESC08, TDESC_LENGTH,
                TransactionListResponse::getTdesc08O, TransactionListResponse::setTdesc08O);
        put(fields, TAMT008, TAMT_LENGTH,
                TransactionListResponse::getTamt008O, TransactionListResponse::setTamt008O);

        // --- row 9 --------------------------------------------------------------------------------
        put(fields, SEL0009, SEL_LENGTH,
                TransactionListResponse::getSel0009O, TransactionListResponse::setSel0009O);
        put(fields, TRNID09, TRNID_LENGTH,
                TransactionListResponse::getTrnid09O, TransactionListResponse::setTrnid09O);
        put(fields, TDATE09, TDATE_LENGTH,
                TransactionListResponse::getTdate09O, TransactionListResponse::setTdate09O);
        put(fields, TDESC09, TDESC_LENGTH,
                TransactionListResponse::getTdesc09O, TransactionListResponse::setTdesc09O);
        put(fields, TAMT009, TAMT_LENGTH,
                TransactionListResponse::getTamt009O, TransactionListResponse::setTamt009O);

        // --- row 10, copybook lines 928-956 -------------------------------------------------------
        put(fields, SEL0010, SEL_LENGTH,
                TransactionListResponse::getSel0010O, TransactionListResponse::setSel0010O);
        put(fields, TRNID10, TRNID_LENGTH,
                TransactionListResponse::getTrnid10O, TransactionListResponse::setTrnid10O);
        put(fields, TDATE10, TDATE_LENGTH,
                TransactionListResponse::getTdate10O, TransactionListResponse::setTdate10O);
        put(fields, TDESC10, TDESC_LENGTH,
                TransactionListResponse::getTdesc10O, TransactionListResponse::setTdesc10O);
        put(fields, TAMT010, TAMT_LENGTH,
                TransactionListResponse::getTamt010O, TransactionListResponse::setTamt010O);

        // --- error line ---------------------------------------------------------------------------
        put(fields, ERRMSG, ERRMSG_LENGTH,
                TransactionListResponse::getErrmsgO, TransactionListResponse::setErrmsgO);

        if (fields.size() != FIELD_COUNT) {
            throw new IllegalStateException("The COTRN0AO projection declares " + fields.size()
                    + " field(s) but app/bms/COTRN00.bms carries " + FIELD_COUNT
                    + " name-labelled DFHMDF definitions; every one must be projected");
        }
        return Collections.unmodifiableMap(fields);
    }

    /**
     * Adds one field to the table under construction, rejecting a duplicate prefix outright.
     *
     * @param fields         the table being built
     * @param fieldPrefix    the verbatim prefix
     * @param declaredLength the payload item's declared width
     * @param reader         the flat member's getter
     * @param writer         the flat member's setter
     * @throws IllegalStateException if the prefix has already been declared, which would mean two
     *                               fields share a name and one of them is a transcription slip
     */
    private static void put(Map<String, PayloadField> fields,
                            String fieldPrefix,
                            int declaredLength,
                            Function<TransactionListResponse, String> reader,
                            BiConsumer<TransactionListResponse, String> writer) {
        PayloadField previous = fields.put(fieldPrefix,
                new PayloadField(fieldPrefix, declaredLength, reader, writer));
        if (previous != null) {
            throw new IllegalStateException("Field prefix '" + fieldPrefix + "' is declared twice in "
                    + "the COTRN0AO projection; each of the " + FIELD_COUNT + " DFHMDF labels is "
                    + "unique in app/bms/COTRN00.bms");
        }
    }

    /**
     * Builds the record layout by walking the field table in copybook order, emitting the
     * {@code TIOAPFX} filler once and then, per field, the 3-byte filler, the four attribute items
     * and the payload item.
     *
     * @return the validated 1265-byte layout
     */
    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        int cursor = 0;

        // 02 FILLER PIC X(12) - the TIOAPFX=YES prefix, emitted once. Dropping it would shift every
        // field by twelve bytes while still producing a plausible-looking image.
        spans.add(FieldSpan.filler(cursor, TIOAPFX_PREFIX_LENGTH));
        cursor += TIOAPFX_PREFIX_LENGTH;

        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            // 02 FILLER PICTURE X(3) - aliases the input view's xxxL COMP PIC S9(4) plus xxxF.
            spans.add(FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;

            // The attribute quad, named so FieldAttributeSetter can reach xxxC by name.
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), COLOUR_ITEM_SUFFIX);
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), PS_ITEM_SUFFIX);
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), HIGHLIGHT_ITEM_SUFFIX);
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), VALIDATION_ITEM_SUFFIX);

            // 02 xxxO PIC X(n) - the payload.
            spans.add(FieldSpan.alphanumeric(field.outputItemName(), cursor, field.declaredLength()));
            cursor += field.declaredLength();
        }

        return new RecordLayout(RECORD_LENGTH, spans);
    }

    /**
     * Appends one {@code PICTURE X} attribute item and returns the advanced cursor.
     *
     * @param spans  the span list being built
     * @param offset the item's absolute offset
     * @param prefix the field's verbatim prefix
     * @param suffix the item suffix, one of {@code C}, {@code P}, {@code H} or {@code V}
     * @return the offset immediately after the item
     */
    private static int addAttributeItem(List<FieldSpan> spans, int offset, String prefix,
                                        String suffix) {
        spans.add(FieldSpan.alphanumeric(prefix + suffix, offset, ATTRIBUTE_ITEM_LENGTH));
        return offset + ATTRIBUTE_ITEM_LENGTH;
    }


    // =================================================================================================
    // The per-field attribute quad. Metadata, never a JSON payload member.
    // =================================================================================================

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of one screen field: the
     * extended colour, the programmed-symbol set, the extended highlight and the validation byte.
     *
     * <p>Per the design-system determination of the Agent Action Plan these four items are
     * <strong>highlight metadata</strong> and never payload: the presentation contract is the BMS
     * layer, and the 3270 attribute bytes describe how a field is rendered rather than what it holds.
     * They are therefore reachable only through {@link JsonIgnore}-annotated accessors on the
     * enclosing response and never appear in its JSON.
     *
     * <p>Immutable, so an attribute quad handed to a collaborator cannot change underneath it; a
     * change is expressed by replacing the quad, which is what
     * {@link TransactionListResponse#applyHighlight(String, FieldHighlight)} does.
     *
     * <p>The initial value of every item is {@link #LOW_VALUE}, reproducing
     * {@code MOVE LOW-VALUES TO COTRN0AO} at {@code COTRN00C:114}: a low-value attribute byte tells
     * BMS to leave the attribute at the map's default, and it is numerically identical to
     * {@link BmsAttributes#DFHDFCOL}, the default-colour mnemonic.
     *
     * <h2>Why these are {@code byte} and not {@code char}</h2>
     *
     * <p>A 3270 attribute is a <strong>byte</strong>, not a character, and {@link BmsAttributes}
     * types every mnemonic accordingly. {@link BmsAttributes#DFHRED} is {@code 0xF2}, and no
     * single-byte text encoding maps that byte to the character {@code U+00F2}: pushing it through a
     * charset would silently replace it - {@code US-ASCII} substitutes {@code '?'} - and the colour
     * would be lost on the first round trip through the group image. These four items are therefore
     * carried as bytes and are written to and read from the image as <strong>raw bytes</strong>,
     * bypassing character translation entirely, while the payload items - which really are text - go
     * through {@link FixedWidthCodec}'s {@code PIC X} rule.
     *
     * @param colour            the {@code xxxC} extended-colour byte, {@link BmsAttributes#DFHRED}
     *                          once a field is flagged in error
     * @param programmedSymbols the {@code xxxP} programmed-symbol byte
     * @param highlight         the {@code xxxH} extended-highlight byte
     * @param validation        the {@code xxxV} validation byte
     */
    public record FieldAttributes(byte colour,
                                  byte programmedSymbols,
                                  byte highlight,
                                  byte validation) {

        /**
         * The low value {@code MOVE LOW-VALUES TO COTRN0AO} writes into every attribute item, which is
         * also {@link BmsAttributes#DFHDFCOL}, the BMS default-colour mnemonic. Taken from that
         * constant rather than written as a literal, so the two cannot drift apart.
         */
        public static final byte LOW_VALUE = BmsAttributes.DFHDFCOL;

        /**
         * The quad as {@code MOVE LOW-VALUES} leaves it: all four items at {@link #LOW_VALUE}, meaning
         * "take the map's default".
         *
         * @return the initial quad; never {@code null}
         */
        public static FieldAttributes lowValues() {
            return new FieldAttributes(LOW_VALUE, LOW_VALUE, LOW_VALUE, LOW_VALUE);
        }

        /**
         * Returns a copy carrying a new extended colour: the
         * {@code MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O} of {@code app/cpy/CSSETATY.cpy:21-22}.
         *
         * @param newColour the colour byte to store, normally {@link BmsAttributes#DFHRED}
         * @return a new quad; this one is unchanged
         */
        public FieldAttributes withColour(byte newColour) {
            return new FieldAttributes(newColour, programmedSymbols, highlight, validation);
        }

        /**
         * Whether all four items are still {@link #LOW_VALUE} - that is, whether nothing has been
         * moved into this field's attributes since the group was set to low values.
         *
         * @return {@code true} when the quad is untouched
         */
        public boolean isLowValues() {
            return colour == LOW_VALUE
                    && programmedSymbols == LOW_VALUE
                    && highlight == LOW_VALUE
                    && validation == LOW_VALUE;
        }

        /**
         * Whether the extended-colour item holds {@link BmsAttributes#DFHRED} - the state
         * {@code CSSETATY} leaves a field in once it has been flagged in error.
         *
         * @return {@code true} when the colour item is {@code DFHRED}
         */
        public boolean isColourRed() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * The quad as the four consecutive bytes it occupies in the group image, in declaration order
         * {@code C}, {@code P}, {@code H}, {@code V}.
         *
         * @return a fresh four-byte array; never {@code null}
         */
        public byte[] toByteArray() {
            return new byte[] {colour, programmedSymbols, highlight, validation};
        }

        /**
         * A diagnostic rendering naming each item and reporting its byte in hexadecimal, with the
         * colour additionally resolved to its {@code DFHBMSCA} mnemonic.
         *
         * @return a single-line description; never {@code null}
         */
        public String describe() {
            return "C=" + BmsAttributes.toHex(colour)
                    + " (" + BmsAttributes.colourMnemonic(colour) + ")"
                    + " P=" + BmsAttributes.toHex(programmedSymbols)
                    + " H=" + BmsAttributes.toHex(highlight)
                    + " V=" + BmsAttributes.toHex(validation);
        }
    }

    // =================================================================================================
    // The pagination cursor: CDEMO-CT00-INFO, the 58-byte commarea extension COTRN00C declares in place
    // at lines 62-70, immediately after COPY COCOM01Y.
    // =================================================================================================

    /**
     * {@code 05 CDEMO-CT00-INFO}, the browse cursor {@code COTRN00C} appends to the communication
     * area [{@code app/cbl/COTRN00C.cbl:62-70}]:
     *
     * <pre>
     *   05 CDEMO-CT00-INFO.
     *      10 CDEMO-CT00-TRNID-FIRST     PIC X(16).
     *      10 CDEMO-CT00-TRNID-LAST      PIC X(16).
     *      10 CDEMO-CT00-PAGE-NUM        PIC 9(08).
     *      10 CDEMO-CT00-NEXT-PAGE-FLG   PIC X(01) VALUE 'N'.
     *         88 NEXT-PAGE-YES                     VALUE 'Y'.
     *         88 NEXT-PAGE-NO                      VALUE 'N'.
     *      10 CDEMO-CT00-TRN-SEL-FLG     PIC X(01).
     *      10 CDEMO-CT00-TRN-SELECTED    PIC X(16).
     * </pre>
     *
     * <p>16 + 16 + 8 + 1 + 1 + 16 = {@value #CURSOR_LENGTH} bytes, so the commarea
     * {@code COTRN00C} passes on {@code EXEC CICS RETURN} is 160 +
     * {@value #CURSOR_LENGTH} = {@value #COMMAREA_WITH_CURSOR_LENGTH} bytes.
     *
     * <h2>Why this is what makes paging stateless</h2>
     *
     * <p>Forward and backward paging need to know where the previous page started and ended.
     * {@code COTRN00C} keeps that in the commarea and CICS hands it back on the next invocation; the
     * migrated service carries the same six fields in the response, the client sends them back on the
     * next request, and the server holds nothing. There is no {@code HttpSession}, no
     * {@code @SessionAttributes} and no server-side browse position.
     *
     * <h2>Why it is not hoisted into a shared type</h2>
     *
     * <p>The three sibling transaction programs each declare their own extension with their own field
     * names - {@code CDEMO-CT00-*}, {@code CDEMO-CT01-*} and {@code CDEMO-CT02-*}. They are not
     * interchangeable, and a field-by-field diff keys on the distinct names, so collapsing them into
     * one shared class would make a real difference invisible. It also stays out of
     * {@link NavigationContext}, which is fixed at exactly 160 bytes and shared by all seventeen
     * controllers: widening that type is never an option.
     */
    public static final class TransactionListCursor {

        /** {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}: the key of the first row on the page just painted. */
        public static final int TRNID_FIRST_LENGTH = 16;

        /** {@code CDEMO-CT00-TRNID-LAST PIC X(16)}: the key of the last row on the page just painted. */
        public static final int TRNID_LAST_LENGTH = 16;

        /** {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}: unsigned, scale-free, so an {@code int} models it. */
        public static final int PAGE_NUM_LENGTH = 8;

        /** {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}. */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /** {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)}: the character the user typed in a row selector. */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /** {@code CDEMO-CT00-TRN-SELECTED PIC X(16)}: the transaction identifier of the selected row. */
        public static final int TRN_SELECTED_LENGTH = 16;

        /** The cursor's total width: 16 + 16 + 8 + 1 + 1 + 16 = {@value #CURSOR_LENGTH} bytes. */
        public static final int CURSOR_LENGTH = TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH
                + PAGE_NUM_LENGTH + NEXT_PAGE_FLG_LENGTH + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * The commarea {@code COTRN00C} actually passes: the 160-byte {@code CARDDEMO-COMMAREA} plus
         * this {@value #CURSOR_LENGTH}-byte extension = {@value #COMMAREA_WITH_CURSOR_LENGTH} bytes.
         */
        public static final int COMMAREA_WITH_CURSOR_LENGTH =
                NavigationContext.COMMAREA_LENGTH + CURSOR_LENGTH;

        /** {@code 88 NEXT-PAGE-YES VALUE 'Y'}, asserted at {@code COTRN00C:242} and {@code :310}. */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, asserted at {@code COTRN00C:99}, {@code :312} and
         * {@code :315}, and the {@code VALUE 'N'} the field is declared with.
         */
        public static final String NEXT_PAGE_NO = "N";

        /** The verbatim COBOL name of the first-key field. */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT00-TRNID-FIRST";

        /** The verbatim COBOL name of the last-key field. */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT00-TRNID-LAST";

        /** The verbatim COBOL name of the page-number field. */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT00-PAGE-NUM";

        /** The verbatim COBOL name of the next-page flag. */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT00-NEXT-PAGE-FLG";

        /** The verbatim COBOL name of the selection flag. */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT00-TRN-SEL-FLG";

        /** The verbatim COBOL name of the selected-transaction field. */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT00-TRN-SELECTED";

        /** Absolute offset of {@code CDEMO-CT00-TRNID-FIRST} within the extension. */
        public static final int TRNID_FIRST_OFFSET = 0;

        /** Absolute offset of {@code CDEMO-CT00-TRNID-LAST}. */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;

        /** Absolute offset of {@code CDEMO-CT00-PAGE-NUM}. */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;

        /** Absolute offset of {@code CDEMO-CT00-NEXT-PAGE-FLG}. */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;

        /** Absolute offset of {@code CDEMO-CT00-TRN-SEL-FLG}. */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;

        /** Absolute offset of {@code CDEMO-CT00-TRN-SELECTED}. */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * The self-checking descriptor list of the extension: six storage spans, no {@code FILLER} -
         * the COBOL declares none - and no overlay, summing to {@value #CURSOR_LENGTH} bytes.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(CURSOR_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET,
                        NEXT_PAGE_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET,
                        TRN_SELECTED_LENGTH));

        /** {@code CDEMO-CT00-TRNID-FIRST}: spaces until a page is painted. */
        private String trnidFirst = spaces(TRNID_FIRST_LENGTH);

        /** {@code CDEMO-CT00-TRNID-LAST}: spaces until a page is painted. */
        private String trnidLast = spaces(TRNID_LAST_LENGTH);

        /** {@code CDEMO-CT00-PAGE-NUM}: zero, which {@code COTRN00C:224} also moves in explicitly. */
        private int pageNum;

        /** {@code CDEMO-CT00-NEXT-PAGE-FLG}, carrying its declared {@code VALUE 'N'}. */
        private String nextPageFlg = NEXT_PAGE_NO;

        /** {@code CDEMO-CT00-TRN-SEL-FLG}: no declared {@code VALUE}, so a space. */
        private String trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);

        /** {@code CDEMO-CT00-TRN-SELECTED}: no declared {@code VALUE}, so spaces. */
        private String trnSelected = spaces(TRN_SELECTED_LENGTH);

        /**
         * A cursor in its declared initial state: both keys spaces, the page number zero, the next-page
         * flag at its {@code VALUE 'N'} default, and no selection.
         */
        public TransactionListCursor() {
            // Every field carries its declared initial value from its declaration above, so the
            // no-argument form needs no body. It exists explicitly because Jackson needs it and
            // because "the state the COBOL starts in" deserves to be a named, tested thing.
        }

        /**
         * A deep copy. The cursor is mutable, so handing the same instance to a second response would
         * let one request's paging move another's.
         *
         * @param other the cursor to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public TransactionListCursor(TransactionListCursor other) {
            Objects.requireNonNull(other, "A cursor is required to copy CDEMO-CT00-INFO");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * {@code CDEMO-CT00-TRNID-FIRST}, set from row one at {@code COTRN00C:393}.
         *
         * @return the first key on the current page, untrimmed; never {@code null}
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Stores {@code CDEMO-CT00-TRNID-FIRST}.
         *
         * @param trnidFirst at most {@value #TRNID_FIRST_LENGTH} characters
         * @throws NullPointerException     if {@code trnidFirst} is {@code null}
         * @throws IllegalArgumentException if it is longer than its declared width
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = requireWidth(trnidFirst, TRNID_FIRST_LENGTH, TRNID_FIRST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRNID-LAST}, the key {@code PROCESS-PF8-KEY} browses forward from
         * [{@code COTRN00C:259-263}].
         *
         * @return the last key on the current page, untrimmed; never {@code null}
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Stores {@code CDEMO-CT00-TRNID-LAST}.
         *
         * @param trnidLast at most {@value #TRNID_LAST_LENGTH} characters
         * @throws NullPointerException     if {@code trnidLast} is {@code null}
         * @throws IllegalArgumentException if it is longer than its declared width
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = requireWidth(trnidLast, TRNID_LAST_LENGTH, TRNID_LAST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-PAGE-NUM}, incremented at {@code COTRN00C:306} and {@code :317} and
         * decremented at {@code :364}.
         *
         * @return the current page number
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Stores {@code CDEMO-CT00-PAGE-NUM}.
         *
         * @param pageNum a value {@code PIC 9(08)} can hold: not negative, at most eight digits
         * @throws IllegalArgumentException if {@code pageNum} is negative or needs more than
         *                                  {@value #PAGE_NUM_LENGTH} digits. {@code PIC 9(n)} is
         *                                  unsigned and has no sign position at all, and an over-wide
         *                                  value would silently lose its high-order digits
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(" + PAGE_NUM_LENGTH
                        + "), an unsigned picture with no sign position, so it cannot hold "
                        + pageNum);
            }
            if (String.valueOf(pageNum).length() > PAGE_NUM_LENGTH) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(" + PAGE_NUM_LENGTH
                        + ") and cannot hold " + pageNum + ", which needs "
                        + String.valueOf(pageNum).length() + " digits");
            }
            this.pageNum = pageNum;
        }

        /**
         * {@code CDEMO-CT00-NEXT-PAGE-FLG}.
         *
         * @return the flag character as a one-character string; never {@code null}
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Stores {@code CDEMO-CT00-NEXT-PAGE-FLG}. Any single character is accepted, because the
         * picture is {@code PIC X(01)} and constrains nothing: {@link #NEXT_PAGE_YES} and
         * {@link #NEXT_PAGE_NO} are condition <em>values</em>, not a domain. Use
         * {@link #setNextPageYes()} or {@link #setNextPageNo()} to assert one of the two named
         * conditions.
         *
         * @param nextPageFlg at most {@value #NEXT_PAGE_FLG_LENGTH} character
         * @throws NullPointerException     if {@code nextPageFlg} is {@code null}
         * @throws IllegalArgumentException if it is longer than one character
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg =
                    requireWidth(nextPageFlg, NEXT_PAGE_FLG_LENGTH, NEXT_PAGE_FLG_FIELD);
        }

        /**
         * {@code SET NEXT-PAGE-YES TO TRUE} - {@code COTRN00C:242} and {@code :310}.
         */
        @JsonIgnore
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /**
         * {@code SET NEXT-PAGE-NO TO TRUE} - {@code COTRN00C:99}, {@code :312} and {@code :315}.
         */
        @JsonIgnore
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * Whether {@code 88 NEXT-PAGE-YES VALUE 'Y'} holds - the condition {@code PROCESS-PF8-KEY}
         * tests at {@code COTRN00C:267} before browsing forward.
         *
         * <p>Exact and case-sensitive, as a COBOL alphanumeric comparison is. Deliberately
         * <strong>not</strong> written as {@code !isNextPageNo()}: {@code PIC X(01)} can hold any
         * character, and a blank flag - which is what a commarea holds before the first page is
         * painted - satisfies neither condition. Defining either as the negation of the other would
         * report an unpainted screen as having a next page.
         *
         * @return {@code true} only when the flag is exactly {@value #NEXT_PAGE_YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * Whether {@code 88 NEXT-PAGE-NO VALUE 'N'} holds.
         *
         * <p>Deliberately not the negation of {@link #isNextPageYes()}, for the reason given there.
         *
         * @return {@code true} only when the flag is exactly {@value #NEXT_PAGE_NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /**
         * {@code CDEMO-CT00-TRN-SEL-FLG}: the character the user typed into whichever row selector
         * matched first in the ordered {@code EVALUATE} at {@code COTRN00C:148-182}.
         *
         * @return the selection character as a one-character string; never {@code null}
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Stores {@code CDEMO-CT00-TRN-SEL-FLG}. Any single character is accepted: {@code COTRN00C}
         * itself stores whatever was typed and only then tests it for {@code 'S'} or {@code 's'} at
         * lines 186-187, reporting {@code Invalid selection. Valid value is S} for anything else. This
         * type must be able to carry the invalid value, or that path could not be reproduced.
         *
         * @param trnSelFlg at most {@value #TRN_SEL_FLG_LENGTH} character
         * @throws NullPointerException     if {@code trnSelFlg} is {@code null}
         * @throws IllegalArgumentException if it is longer than one character
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = requireWidth(trnSelFlg, TRN_SEL_FLG_LENGTH, TRN_SEL_FLG_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRN-SELECTED}: the transaction identifier of the selected row, handed to
         * {@code COTRN01C} through the commarea at {@code COTRN00C:188-195}.
         *
         * @return the selected transaction identifier, untrimmed; never {@code null}
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Stores {@code CDEMO-CT00-TRN-SELECTED}.
         *
         * @param trnSelected at most {@value #TRN_SELECTED_LENGTH} characters
         * @throws NullPointerException     if {@code trnSelected} is {@code null}
         * @throws IllegalArgumentException if it is longer than its declared width
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = requireWidth(trnSelected, TRN_SELECTED_LENGTH, TRN_SELECTED_FIELD);
        }

        /**
         * Clears the selection, the {@code MOVE SPACES TO CDEMO-CT00-TRN-SEL-FLG} and
         * {@code MOVE SPACES TO CDEMO-CT00-TRN-SELECTED} of the {@code WHEN OTHER} arm at
         * {@code COTRN00C:179-181} - the arm taken when no row selector was typed in.
         */
        @JsonIgnore
        public void clearSelection() {
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Whether a row was selected: the guard at {@code COTRN00C:183-184}, which requires both the
         * flag and the identifier to be neither spaces nor low values before the selection is acted
         * on.
         *
         * @return {@code true} when both fields carry something other than spaces or low values
         */
        @JsonIgnore
        public boolean isRowSelected() {
            return isPresent(trnSelFlg) && isPresent(trnSelected);
        }

        /**
         * Renders the extension as its {@value #CURSOR_LENGTH}-byte image: the two keys and the two
         * flags space-padded on the right, the page number zero-filled on the left.
         *
         * @param codec the codec for the target code page, chosen explicitly by the caller
         * @return exactly {@value #CURSOR_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] toFixedWidth(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render CDEMO-CT00-INFO; the code "
                    + "page must be stated explicitly and is never taken from the platform");
            FixedWidthRecord record = codec.newRecord(LAYOUT);
            writeInto(record, codec);
            return record.toByteArray();
        }

        /**
         * Rebuilds the extension from its image.
         *
         * @param bytes   exactly {@value #CURSOR_LENGTH} bytes
         * @param charset the code page the image is encoded in, named explicitly by the caller
         * @return the cursor the image carries; never {@code null}
         * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code bytes.length} is not {@value #CURSOR_LENGTH}
         */
        public static TransactionListCursor fromFixedWidth(byte[] bytes, Charset charset) {
            Objects.requireNonNull(bytes, "An image is required to rebuild CDEMO-CT00-INFO");
            Objects.requireNonNull(charset, "A charset is required to decode a CDEMO-CT00-INFO "
                    + "image; the code page must be stated explicitly");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            return readFrom(codec.wrap(bytes, LAYOUT), codec);
        }

        /**
         * Writes the six fields into a record area of exactly {@value #CURSOR_LENGTH} bytes.
         *
         * @param record the record area to write into
         * @param codec  the codec for the record's code page
         */
        private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
            codec.writePicX(record, LAYOUT.span(TRNID_FIRST_FIELD), trnidFirst);
            codec.writePicX(record, LAYOUT.span(TRNID_LAST_FIELD), trnidLast);
            codec.writePic9(record, LAYOUT.span(PAGE_NUM_FIELD), pageNum);
            codec.writePicX(record, LAYOUT.span(NEXT_PAGE_FLG_FIELD), nextPageFlg);
            codec.writePicX(record, LAYOUT.span(TRN_SEL_FLG_FIELD), trnSelFlg);
            codec.writePicX(record, LAYOUT.span(TRN_SELECTED_FIELD), trnSelected);
        }

        /**
         * Reads the six fields out of a record area, untrimmed.
         *
         * @param record the record area to read from
         * @param codec  the codec for the record's code page
         * @return the cursor the record carries
         */
        private static TransactionListCursor readFrom(FixedWidthRecord record,
                                                      FixedWidthCodec codec) {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnidFirst(codec.readPicX(record, LAYOUT.span(TRNID_FIRST_FIELD)));
            cursor.setTrnidLast(codec.readPicX(record, LAYOUT.span(TRNID_LAST_FIELD)));
            cursor.setPageNum(codec.readPic9AsInt(record, LAYOUT.span(PAGE_NUM_FIELD)));
            cursor.setNextPageFlg(codec.readPicX(record, LAYOUT.span(NEXT_PAGE_FLG_FIELD)));
            cursor.setTrnSelFlg(codec.readPicX(record, LAYOUT.span(TRN_SEL_FLG_FIELD)));
            cursor.setTrnSelected(codec.readPicX(record, LAYOUT.span(TRN_SELECTED_FIELD)));
            return cursor;
        }

        /**
         * A diagnostic rendering naming each field by its verbatim COBOL name. Values are shown as
         * stored, with their padding intact, because the padding is part of the field.
         *
         * @return a single-line description; never {@code null}
         */
        @Override
        public String toString() {
            return "CDEMO-CT00-INFO[" + TRNID_FIRST_FIELD + "='" + trnidFirst + "', "
                    + TRNID_LAST_FIELD + "='" + trnidLast + "', "
                    + PAGE_NUM_FIELD + "=" + pageNum + ", "
                    + NEXT_PAGE_FLG_FIELD + "='" + nextPageFlg + "', "
                    + TRN_SEL_FLG_FIELD + "='" + trnSelFlg + "', "
                    + TRN_SELECTED_FIELD + "='" + trnSelected + "']";
        }
    }


    // =================================================================================================
    // Instance state. Every payload member starts as spaces at its declared width - never null - so a
    // freshly built response serialises as a blank screen exactly as MOVE SPACES leaves one, and a
    // blanked row serialises as spaces rather than as a JSON null.
    //
    // All state is per instance. There is no static field holding a row, a buffer or a cache, so two
    // requests in flight cannot see each other's screen.
    // =================================================================================================

    /** {@code TRNNAMEO PIC X(4)}: the transaction identifier, {@code CT00}. */
    private String trnnameO = spaces(TRNNAME_LENGTH);

    /** {@code TITLE01O PIC X(40)}: the upper title line, from {@link ScreenTitles#CCDA_TITLE01}. */
    private String title01O = spaces(TITLE01_LENGTH);

    /** {@code CURDATEO PIC X(8)}: the current date as {@code MM/DD/YY}. */
    private String curdateO = spaces(CURDATE_LENGTH);

    /** {@code PGMNAMEO PIC X(8)}: the program name, {@code COTRN00C}. */
    private String pgmnameO = spaces(PGMNAME_LENGTH);

    /** {@code TITLE02O PIC X(40)}: the lower title line, from {@link ScreenTitles#CCDA_TITLE02}. */
    private String title02O = spaces(TITLE02_LENGTH);

    /** {@code CURTIMEO PIC X(8)}: the current time as {@code HH:MM:SS}. */
    private String curtimeO = spaces(CURTIME_LENGTH);

    /** {@code PAGENUMO PIC X(8)}: the displayed page number, alphanumeric on the screen. */
    private String pagenumO = spaces(PAGENUM_LENGTH);

    /** {@code TRNIDINO PIC X(16)}: the browse-start key the user typed, blanked at {@code COTRN00C:325}. */
    private String trnidinO = spaces(TRNIDIN_LENGTH);

    /** {@code SEL0001O PIC X(1)}: row 1 selector. */
    private String sel0001O = spaces(SEL_LENGTH);

    /** {@code TRNID01O PIC X(16)}: row 1 transaction identifier. */
    private String trnid01O = spaces(TRNID_LENGTH);

    /** {@code TDATE01O PIC X(8)}: row 1 transaction date. */
    private String tdate01O = spaces(TDATE_LENGTH);

    /** {@code TDESC01O PIC X(26)}: row 1 description. */
    private String tdesc01O = spaces(TDESC_LENGTH);

    /** {@code TAMT001O PIC X(12)}: row 1 edited amount. */
    private String tamt001O = spaces(TAMT_LENGTH);

    /** {@code SEL0002O PIC X(1)}: row 2 selector. */
    private String sel0002O = spaces(SEL_LENGTH);

    /** {@code TRNID02O PIC X(16)}: row 2 transaction identifier. */
    private String trnid02O = spaces(TRNID_LENGTH);

    /** {@code TDATE02O PIC X(8)}: row 2 transaction date. */
    private String tdate02O = spaces(TDATE_LENGTH);

    /** {@code TDESC02O PIC X(26)}: row 2 description. */
    private String tdesc02O = spaces(TDESC_LENGTH);

    /** {@code TAMT002O PIC X(12)}: row 2 edited amount. */
    private String tamt002O = spaces(TAMT_LENGTH);

    /** {@code SEL0003O PIC X(1)}: row 3 selector. */
    private String sel0003O = spaces(SEL_LENGTH);

    /** {@code TRNID03O PIC X(16)}: row 3 transaction identifier. */
    private String trnid03O = spaces(TRNID_LENGTH);

    /** {@code TDATE03O PIC X(8)}: row 3 transaction date. */
    private String tdate03O = spaces(TDATE_LENGTH);

    /** {@code TDESC03O PIC X(26)}: row 3 description. */
    private String tdesc03O = spaces(TDESC_LENGTH);

    /** {@code TAMT003O PIC X(12)}: row 3 edited amount. */
    private String tamt003O = spaces(TAMT_LENGTH);

    /** {@code SEL0004O PIC X(1)}: row 4 selector. */
    private String sel0004O = spaces(SEL_LENGTH);

    /** {@code TRNID04O PIC X(16)}: row 4 transaction identifier. */
    private String trnid04O = spaces(TRNID_LENGTH);

    /** {@code TDATE04O PIC X(8)}: row 4 transaction date. */
    private String tdate04O = spaces(TDATE_LENGTH);

    /** {@code TDESC04O PIC X(26)}: row 4 description. */
    private String tdesc04O = spaces(TDESC_LENGTH);

    /** {@code TAMT004O PIC X(12)}: row 4 edited amount. */
    private String tamt004O = spaces(TAMT_LENGTH);

    /** {@code SEL0005O PIC X(1)}: row 5 selector. */
    private String sel0005O = spaces(SEL_LENGTH);

    /** {@code TRNID05O PIC X(16)}: row 5 transaction identifier. */
    private String trnid05O = spaces(TRNID_LENGTH);

    /** {@code TDATE05O PIC X(8)}: row 5 transaction date. */
    private String tdate05O = spaces(TDATE_LENGTH);

    /** {@code TDESC05O PIC X(26)}: row 5 description. */
    private String tdesc05O = spaces(TDESC_LENGTH);

    /** {@code TAMT005O PIC X(12)}: row 5 edited amount. */
    private String tamt005O = spaces(TAMT_LENGTH);

    /** {@code SEL0006O PIC X(1)}: row 6 selector. */
    private String sel0006O = spaces(SEL_LENGTH);

    /** {@code TRNID06O PIC X(16)}: row 6 transaction identifier. */
    private String trnid06O = spaces(TRNID_LENGTH);

    /** {@code TDATE06O PIC X(8)}: row 6 transaction date. */
    private String tdate06O = spaces(TDATE_LENGTH);

    /** {@code TDESC06O PIC X(26)}: row 6 description. */
    private String tdesc06O = spaces(TDESC_LENGTH);

    /** {@code TAMT006O PIC X(12)}: row 6 edited amount. */
    private String tamt006O = spaces(TAMT_LENGTH);

    /** {@code SEL0007O PIC X(1)}: row 7 selector. */
    private String sel0007O = spaces(SEL_LENGTH);

    /** {@code TRNID07O PIC X(16)}: row 7 transaction identifier. */
    private String trnid07O = spaces(TRNID_LENGTH);

    /** {@code TDATE07O PIC X(8)}: row 7 transaction date. */
    private String tdate07O = spaces(TDATE_LENGTH);

    /** {@code TDESC07O PIC X(26)}: row 7 description. */
    private String tdesc07O = spaces(TDESC_LENGTH);

    /** {@code TAMT007O PIC X(12)}: row 7 edited amount. */
    private String tamt007O = spaces(TAMT_LENGTH);

    /** {@code SEL0008O PIC X(1)}: row 8 selector. */
    private String sel0008O = spaces(SEL_LENGTH);

    /** {@code TRNID08O PIC X(16)}: row 8 transaction identifier. */
    private String trnid08O = spaces(TRNID_LENGTH);

    /** {@code TDATE08O PIC X(8)}: row 8 transaction date. */
    private String tdate08O = spaces(TDATE_LENGTH);

    /** {@code TDESC08O PIC X(26)}: row 8 description. */
    private String tdesc08O = spaces(TDESC_LENGTH);

    /** {@code TAMT008O PIC X(12)}: row 8 edited amount. */
    private String tamt008O = spaces(TAMT_LENGTH);

    /** {@code SEL0009O PIC X(1)}: row 9 selector. */
    private String sel0009O = spaces(SEL_LENGTH);

    /** {@code TRNID09O PIC X(16)}: row 9 transaction identifier. */
    private String trnid09O = spaces(TRNID_LENGTH);

    /** {@code TDATE09O PIC X(8)}: row 9 transaction date. */
    private String tdate09O = spaces(TDATE_LENGTH);

    /** {@code TDESC09O PIC X(26)}: row 9 description. */
    private String tdesc09O = spaces(TDESC_LENGTH);

    /** {@code TAMT009O PIC X(12)}: row 9 edited amount. */
    private String tamt009O = spaces(TAMT_LENGTH);

    /** {@code SEL0010O PIC X(1)}: row 10 selector. */
    private String sel0010O = spaces(SEL_LENGTH);

    /** {@code TRNID10O PIC X(16)}: row 10 transaction identifier. */
    private String trnid10O = spaces(TRNID_LENGTH);

    /** {@code TDATE10O PIC X(8)}: row 10 transaction date. */
    private String tdate10O = spaces(TDATE_LENGTH);

    /** {@code TDESC10O PIC X(26)}: row 10 description. */
    private String tdesc10O = spaces(TDESC_LENGTH);

    /** {@code TAMT010O PIC X(12)}: row 10 edited amount. */
    private String tamt010O = spaces(TAMT_LENGTH);

    /** {@code ERRMSGO PIC X(78)}: the error line, blanked at {@code COTRN00C:103}. */
    private String errmsgO = spaces(ERRMSG_LENGTH);

    // --- navigation, replacing EXEC CICS XCTL ------------------------------------------------------

    /**
     * The program the client should call next: the value {@code EXEC CICS XCTL PROGRAM(...)} would
     * have transferred to. Width {@code X(8)}, from {@code CDEMO-TO-PROGRAM}.
     */
    private String nextProgram = spaces(NavigationContext.TO_PROGRAM_LENGTH);

    /**
     * The mapset the next screen belongs to, {@link #MAPSET_NAME} by default. Width {@code X(7)}, from
     * {@code CDEMO-LAST-MAPSET} - seven, not eight.
     */
    private String nextMapset = MAPSET_NAME;

    /**
     * The map the next screen uses, {@link #MAP_NAME} by default. Width {@code X(7)}, from
     * {@code CDEMO-LAST-MAP} - seven, not eight.
     */
    private String nextMap = MAP_NAME;

    // --- conversation state, carried in the payload rather than on the server ----------------------

    /** The 160-byte {@code CARDDEMO-COMMAREA}, echoed so the client can send it back unchanged. */
    private NavigationContext navigationContext = NavigationContext.empty();

    /** The 58-byte {@code CDEMO-CT00-INFO} browse cursor, echoed for the same reason. */
    private TransactionListCursor cursor = new TransactionListCursor();

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of each of the 59 fields,
     * keyed by verbatim prefix in copybook order.
     *
     * <p>An instance field, never static: with ten repeated rows a shared mutable attribute table
     * would let one request's error highlight appear on another's screen, and would make test order
     * significant. The map is {@code final} and pre-populated for every field, so a lookup can never
     * miss and no lazy initialisation is needed.
     *
     * <p>Excluded from JSON: it is reachable only through {@link JsonIgnore}-annotated accessors, and
     * the field itself is private with no bean-shaped getter, so Jackson neither serialises nor
     * deserialises it.
     */
    private final Map<String, FieldAttributes> fieldAttributes = new LinkedHashMap<>();

    // =================================================================================================
    // Construction.
    // =================================================================================================

    /**
     * A blank screen: every payload field spaces at its declared width, every attribute quad at low
     * values, the navigation targets defaulted to this map's own mapset and map, an empty
     * communication area and a cursor in its declared initial state.
     *
     * <p>This is the state {@code COTRN00C:114}'s {@code MOVE LOW-VALUES TO COTRN0AO} and
     * {@code COTRN00C:103}'s {@code MOVE SPACES TO ERRMSGO} leave the group in before the first
     * screen is painted - with one deliberate difference: the payload items are spaces rather than
     * low values, because a JSON payload has to carry characters and a blanked field is what every
     * subsequent {@code MOVE SPACES} produces anyway. The attribute items <em>do</em> carry the low
     * value, because that is what tells BMS to use the map's default rendering.
     */
    public TransactionListResponse() {
        for (String fieldPrefix : FIELD_PREFIXES) {
            fieldAttributes.put(fieldPrefix, FieldAttributes.lowValues());
        }
    }

    /**
     * A deep copy: the payload fields, the navigation targets, the attribute quads, the communication
     * area and the cursor.
     *
     * <p>The cursor is copied rather than shared, because it is mutable and sharing one would let
     * paging in one response move the other. {@link NavigationContext} is a record and needs no copy.
     *
     * @param other the response to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public TransactionListResponse(TransactionListResponse other) {
        Objects.requireNonNull(other, "A response is required to copy the COTRN0AO projection");
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            field.writer().accept(this, field.reader().apply(other));
        }
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.navigationContext = other.navigationContext;
        this.cursor = new TransactionListCursor(other.cursor);
        this.fieldAttributes.putAll(other.fieldAttributes);
    }


    // =================================================================================================
    // The 59 payload accessors, one flat pair per xxxO item, in copybook order.
    //
    // Every getter returns the value UNTRIMMED: a PIC X field is space-padded to its declared width and
    // that padding is part of the field, so a 78-character ERRMSGO survives a JSON round trip with its
    // padding intact and a field-by-field diff compares what the COBOL would have sent.
    //
    // Every setter validates through one shared guard: null is rejected, because there is no null in a
    // COBOL record; an over-wide value is rejected rather than quietly shortened, because the direction
    // of a cross-width MOVE is a per-PICTURE decision that must be taken deliberately - a caller who
    // wants COBOL's right truncation asks for it by name through FixedWidthCodec#movePicX, and the
    // paragraph reproductions further down do exactly that. A shorter value is accepted and is padded
    // on the right when the image is produced, exactly as a MOVE into a wider PIC X receiver pads.
    //
    // No @JsonProperty, no @JsonInclude, no @JsonNaming and no ObjectMapper appear here: the web
    // configuration owns the module's JSON policy, and a local override would silently exempt this one
    // payload from it. No @JsonIgnore appears on a data field either - nothing is masked, truncated or
    // redacted, and the response carries every field the screen carries.
    // =================================================================================================

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown in the header, moved from
     * {@code WS-TRANID} at {@code COTRN00C:573}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnnameO() {
        return trnnameO;
    }

    /**
     * Stores {@code TRNNAMEO}.
     *
     * @param trnnameO at most {@value #TRNNAME_LENGTH} characters
     * @throws NullPointerException     if {@code trnnameO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnnameO(String trnnameO) {
        this.trnnameO = requireWidth(trnnameO, TRNNAME_LENGTH, TRNNAME + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the upper title line, moved from {@code CCDA-TITLE01} at
     * {@code COTRN00C:571}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTitle01O() {
        return title01O;
    }

    /**
     * Stores {@code TITLE01O}.
     *
     * @param title01O at most {@value #TITLE01_LENGTH} characters
     * @throws NullPointerException     if {@code title01O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTitle01O(String title01O) {
        this.title01O = requireWidth(title01O, TITLE01_LENGTH, TITLE01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code MM/DD/YY}, moved from
     * {@code WS-CURDATE-MM-DD-YY} at {@code COTRN00C:580}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurdateO() {
        return curdateO;
    }

    /**
     * Stores {@code CURDATEO}.
     *
     * @param curdateO at most {@value #CURDATE_LENGTH} characters
     * @throws NullPointerException     if {@code curdateO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setCurdateO(String curdateO) {
        this.curdateO = requireWidth(curdateO, CURDATE_LENGTH, CURDATE + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown in the header, moved from
     * {@code WS-PGMNAME} at {@code COTRN00C:574}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getPgmnameO() {
        return pgmnameO;
    }

    /**
     * Stores {@code PGMNAMEO}.
     *
     * @param pgmnameO at most {@value #PGMNAME_LENGTH} characters
     * @throws NullPointerException     if {@code pgmnameO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setPgmnameO(String pgmnameO) {
        this.pgmnameO = requireWidth(pgmnameO, PGMNAME_LENGTH, PGMNAME + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the lower title line, moved from {@code CCDA-TITLE02} at
     * {@code COTRN00C:572}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTitle02O() {
        return title02O;
    }

    /**
     * Stores {@code TITLE02O}.
     *
     * @param title02O at most {@value #TITLE02_LENGTH} characters
     * @throws NullPointerException     if {@code title02O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTitle02O(String title02O) {
        this.title02O = requireWidth(title02O, TITLE02_LENGTH, TITLE02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code HH:MM:SS}, moved from
     * {@code WS-CURTIME-HH-MM-SS} at {@code COTRN00C:586}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getCurtimeO() {
        return curtimeO;
    }

    /**
     * Stores {@code CURTIMEO}.
     *
     * @param curtimeO at most {@value #CURTIME_LENGTH} characters
     * @throws NullPointerException     if {@code curtimeO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setCurtimeO(String curtimeO) {
        this.curtimeO = requireWidth(curtimeO, CURTIME_LENGTH, CURTIME + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code PAGENUMO PIC X(8)} - the displayed page number.
     *
     * <p>Alphanumeric on the screen even though {@code CDEMO-CT00-PAGE-NUM} is {@code PIC 9(08)}:
     * {@code COTRN00C:324} moves the numeric item into this alphanumeric one. Use
     * {@link #movePageNumberToScreen(int)} to perform that move through the codec.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getPagenumO() {
        return pagenumO;
    }

    /**
     * Stores {@code PAGENUMO}.
     *
     * @param pagenumO at most {@value #PAGENUM_LENGTH} characters
     * @throws NullPointerException     if {@code pagenumO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setPagenumO(String pagenumO) {
        this.pagenumO = requireWidth(pagenumO, PAGENUM_LENGTH, PAGENUM + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNIDINO PIC X(16)} - the browse-start key.
     *
     * <p>An <em>input</em> field that {@code COTRN00C} nonetheless writes through the output view:
     * {@code MOVE SPACE TO TRNIDINO OF COTRN0AO} at lines 228 and 325 clears it once a page has been
     * painted, so the user is not sent back to the same starting key. {@link #clearTranIdInput()}
     * reproduces that move.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnidinO() {
        return trnidinO;
    }

    /**
     * Stores {@code TRNIDINO}.
     *
     * @param trnidinO at most {@value #TRNIDIN_LENGTH} characters
     * @throws NullPointerException     if {@code trnidinO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnidinO(String trnidinO) {
        this.trnidinO = requireWidth(trnidinO, TRNIDIN_LENGTH, TRNIDIN + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0001O PIC X(1)} - row 1 selector. Note the <strong>four</strong>-digit suffix.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0001O() {
        return sel0001O;
    }

    /**
     * Stores {@code SEL0001O}.
     *
     * @param sel0001O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0001O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0001O(String sel0001O) {
        this.sel0001O = requireWidth(sel0001O, SEL_LENGTH, SEL0001 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID01O PIC X(16)} - row 1 transaction identifier. Note the <strong>two</strong>-digit
     * suffix.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid01O() {
        return trnid01O;
    }

    /**
     * Stores {@code TRNID01O}.
     *
     * @param trnid01O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid01O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid01O(String trnid01O) {
        this.trnid01O = requireWidth(trnid01O, TRNID_LENGTH, TRNID01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE01O PIC X(8)} - row 1 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate01O() {
        return tdate01O;
    }

    /**
     * Stores {@code TDATE01O}.
     *
     * @param tdate01O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate01O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate01O(String tdate01O) {
        this.tdate01O = requireWidth(tdate01O, TDATE_LENGTH, TDATE01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC01O PIC X(26)} - row 1 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc01O() {
        return tdesc01O;
    }

    /**
     * Stores {@code TDESC01O}.
     *
     * @param tdesc01O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc01O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc01O(String tdesc01O) {
        this.tdesc01O = requireWidth(tdesc01O, TDESC_LENGTH, TDESC01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT001O PIC X(12)} - row 1 edited amount. Note the <strong>three</strong>-digit suffix.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt001O() {
        return tamt001O;
    }

    /**
     * Stores {@code TAMT001O}.
     *
     * @param tamt001O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt001O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt001O(String tamt001O) {
        this.tamt001O = requireWidth(tamt001O, TAMT_LENGTH, TAMT001 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0002O PIC X(1)} - row 2 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0002O() {
        return sel0002O;
    }

    /**
     * Stores {@code SEL0002O}.
     *
     * @param sel0002O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0002O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0002O(String sel0002O) {
        this.sel0002O = requireWidth(sel0002O, SEL_LENGTH, SEL0002 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID02O PIC X(16)} - row 2 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid02O() {
        return trnid02O;
    }

    /**
     * Stores {@code TRNID02O}.
     *
     * @param trnid02O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid02O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid02O(String trnid02O) {
        this.trnid02O = requireWidth(trnid02O, TRNID_LENGTH, TRNID02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE02O PIC X(8)} - row 2 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate02O() {
        return tdate02O;
    }

    /**
     * Stores {@code TDATE02O}.
     *
     * @param tdate02O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate02O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate02O(String tdate02O) {
        this.tdate02O = requireWidth(tdate02O, TDATE_LENGTH, TDATE02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC02O PIC X(26)} - row 2 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc02O() {
        return tdesc02O;
    }

    /**
     * Stores {@code TDESC02O}.
     *
     * @param tdesc02O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc02O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc02O(String tdesc02O) {
        this.tdesc02O = requireWidth(tdesc02O, TDESC_LENGTH, TDESC02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT002O PIC X(12)} - row 2 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt002O() {
        return tamt002O;
    }

    /**
     * Stores {@code TAMT002O}.
     *
     * @param tamt002O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt002O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt002O(String tamt002O) {
        this.tamt002O = requireWidth(tamt002O, TAMT_LENGTH, TAMT002 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0003O PIC X(1)} - row 3 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0003O() {
        return sel0003O;
    }

    /**
     * Stores {@code SEL0003O}.
     *
     * @param sel0003O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0003O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0003O(String sel0003O) {
        this.sel0003O = requireWidth(sel0003O, SEL_LENGTH, SEL0003 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID03O PIC X(16)} - row 3 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid03O() {
        return trnid03O;
    }

    /**
     * Stores {@code TRNID03O}.
     *
     * @param trnid03O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid03O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid03O(String trnid03O) {
        this.trnid03O = requireWidth(trnid03O, TRNID_LENGTH, TRNID03 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE03O PIC X(8)} - row 3 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate03O() {
        return tdate03O;
    }

    /**
     * Stores {@code TDATE03O}.
     *
     * @param tdate03O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate03O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate03O(String tdate03O) {
        this.tdate03O = requireWidth(tdate03O, TDATE_LENGTH, TDATE03 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC03O PIC X(26)} - row 3 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc03O() {
        return tdesc03O;
    }

    /**
     * Stores {@code TDESC03O}.
     *
     * @param tdesc03O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc03O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc03O(String tdesc03O) {
        this.tdesc03O = requireWidth(tdesc03O, TDESC_LENGTH, TDESC03 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT003O PIC X(12)} - row 3 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt003O() {
        return tamt003O;
    }

    /**
     * Stores {@code TAMT003O}.
     *
     * @param tamt003O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt003O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt003O(String tamt003O) {
        this.tamt003O = requireWidth(tamt003O, TAMT_LENGTH, TAMT003 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0004O PIC X(1)} - row 4 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0004O() {
        return sel0004O;
    }

    /**
     * Stores {@code SEL0004O}.
     *
     * @param sel0004O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0004O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0004O(String sel0004O) {
        this.sel0004O = requireWidth(sel0004O, SEL_LENGTH, SEL0004 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID04O PIC X(16)} - row 4 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid04O() {
        return trnid04O;
    }

    /**
     * Stores {@code TRNID04O}.
     *
     * @param trnid04O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid04O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid04O(String trnid04O) {
        this.trnid04O = requireWidth(trnid04O, TRNID_LENGTH, TRNID04 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE04O PIC X(8)} - row 4 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate04O() {
        return tdate04O;
    }

    /**
     * Stores {@code TDATE04O}.
     *
     * @param tdate04O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate04O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate04O(String tdate04O) {
        this.tdate04O = requireWidth(tdate04O, TDATE_LENGTH, TDATE04 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC04O PIC X(26)} - row 4 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc04O() {
        return tdesc04O;
    }

    /**
     * Stores {@code TDESC04O}.
     *
     * @param tdesc04O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc04O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc04O(String tdesc04O) {
        this.tdesc04O = requireWidth(tdesc04O, TDESC_LENGTH, TDESC04 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT004O PIC X(12)} - row 4 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt004O() {
        return tamt004O;
    }

    /**
     * Stores {@code TAMT004O}.
     *
     * @param tamt004O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt004O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt004O(String tamt004O) {
        this.tamt004O = requireWidth(tamt004O, TAMT_LENGTH, TAMT004 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0005O PIC X(1)} - row 5 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0005O() {
        return sel0005O;
    }

    /**
     * Stores {@code SEL0005O}.
     *
     * @param sel0005O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0005O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0005O(String sel0005O) {
        this.sel0005O = requireWidth(sel0005O, SEL_LENGTH, SEL0005 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID05O PIC X(16)} - row 5 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid05O() {
        return trnid05O;
    }

    /**
     * Stores {@code TRNID05O}.
     *
     * @param trnid05O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid05O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid05O(String trnid05O) {
        this.trnid05O = requireWidth(trnid05O, TRNID_LENGTH, TRNID05 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE05O PIC X(8)} - row 5 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate05O() {
        return tdate05O;
    }

    /**
     * Stores {@code TDATE05O}.
     *
     * @param tdate05O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate05O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate05O(String tdate05O) {
        this.tdate05O = requireWidth(tdate05O, TDATE_LENGTH, TDATE05 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC05O PIC X(26)} - row 5 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc05O() {
        return tdesc05O;
    }

    /**
     * Stores {@code TDESC05O}.
     *
     * @param tdesc05O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc05O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc05O(String tdesc05O) {
        this.tdesc05O = requireWidth(tdesc05O, TDESC_LENGTH, TDESC05 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT005O PIC X(12)} - row 5 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt005O() {
        return tamt005O;
    }

    /**
     * Stores {@code TAMT005O}.
     *
     * @param tamt005O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt005O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt005O(String tamt005O) {
        this.tamt005O = requireWidth(tamt005O, TAMT_LENGTH, TAMT005 + OUTPUT_ITEM_SUFFIX);
    }


    /**
     * {@code SEL0006O PIC X(1)} - row 6 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0006O() {
        return sel0006O;
    }

    /**
     * Stores {@code SEL0006O}.
     *
     * @param sel0006O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0006O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0006O(String sel0006O) {
        this.sel0006O = requireWidth(sel0006O, SEL_LENGTH, SEL0006 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID06O PIC X(16)} - row 6 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid06O() {
        return trnid06O;
    }

    /**
     * Stores {@code TRNID06O}.
     *
     * @param trnid06O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid06O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid06O(String trnid06O) {
        this.trnid06O = requireWidth(trnid06O, TRNID_LENGTH, TRNID06 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE06O PIC X(8)} - row 6 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate06O() {
        return tdate06O;
    }

    /**
     * Stores {@code TDATE06O}.
     *
     * @param tdate06O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate06O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate06O(String tdate06O) {
        this.tdate06O = requireWidth(tdate06O, TDATE_LENGTH, TDATE06 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC06O PIC X(26)} - row 6 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc06O() {
        return tdesc06O;
    }

    /**
     * Stores {@code TDESC06O}.
     *
     * @param tdesc06O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc06O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc06O(String tdesc06O) {
        this.tdesc06O = requireWidth(tdesc06O, TDESC_LENGTH, TDESC06 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT006O PIC X(12)} - row 6 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt006O() {
        return tamt006O;
    }

    /**
     * Stores {@code TAMT006O}.
     *
     * @param tamt006O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt006O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt006O(String tamt006O) {
        this.tamt006O = requireWidth(tamt006O, TAMT_LENGTH, TAMT006 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0007O PIC X(1)} - row 7 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0007O() {
        return sel0007O;
    }

    /**
     * Stores {@code SEL0007O}.
     *
     * @param sel0007O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0007O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0007O(String sel0007O) {
        this.sel0007O = requireWidth(sel0007O, SEL_LENGTH, SEL0007 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID07O PIC X(16)} - row 7 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid07O() {
        return trnid07O;
    }

    /**
     * Stores {@code TRNID07O}.
     *
     * @param trnid07O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid07O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid07O(String trnid07O) {
        this.trnid07O = requireWidth(trnid07O, TRNID_LENGTH, TRNID07 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE07O PIC X(8)} - row 7 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate07O() {
        return tdate07O;
    }

    /**
     * Stores {@code TDATE07O}.
     *
     * @param tdate07O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate07O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate07O(String tdate07O) {
        this.tdate07O = requireWidth(tdate07O, TDATE_LENGTH, TDATE07 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC07O PIC X(26)} - row 7 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc07O() {
        return tdesc07O;
    }

    /**
     * Stores {@code TDESC07O}.
     *
     * @param tdesc07O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc07O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc07O(String tdesc07O) {
        this.tdesc07O = requireWidth(tdesc07O, TDESC_LENGTH, TDESC07 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT007O PIC X(12)} - row 7 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt007O() {
        return tamt007O;
    }

    /**
     * Stores {@code TAMT007O}.
     *
     * @param tamt007O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt007O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt007O(String tamt007O) {
        this.tamt007O = requireWidth(tamt007O, TAMT_LENGTH, TAMT007 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0008O PIC X(1)} - row 8 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0008O() {
        return sel0008O;
    }

    /**
     * Stores {@code SEL0008O}.
     *
     * @param sel0008O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0008O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0008O(String sel0008O) {
        this.sel0008O = requireWidth(sel0008O, SEL_LENGTH, SEL0008 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID08O PIC X(16)} - row 8 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid08O() {
        return trnid08O;
    }

    /**
     * Stores {@code TRNID08O}.
     *
     * @param trnid08O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid08O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid08O(String trnid08O) {
        this.trnid08O = requireWidth(trnid08O, TRNID_LENGTH, TRNID08 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE08O PIC X(8)} - row 8 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate08O() {
        return tdate08O;
    }

    /**
     * Stores {@code TDATE08O}.
     *
     * @param tdate08O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate08O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate08O(String tdate08O) {
        this.tdate08O = requireWidth(tdate08O, TDATE_LENGTH, TDATE08 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC08O PIC X(26)} - row 8 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc08O() {
        return tdesc08O;
    }

    /**
     * Stores {@code TDESC08O}.
     *
     * @param tdesc08O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc08O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc08O(String tdesc08O) {
        this.tdesc08O = requireWidth(tdesc08O, TDESC_LENGTH, TDESC08 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT008O PIC X(12)} - row 8 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt008O() {
        return tamt008O;
    }

    /**
     * Stores {@code TAMT008O}.
     *
     * @param tamt008O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt008O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt008O(String tamt008O) {
        this.tamt008O = requireWidth(tamt008O, TAMT_LENGTH, TAMT008 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0009O PIC X(1)} - row 9 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0009O() {
        return sel0009O;
    }

    /**
     * Stores {@code SEL0009O}.
     *
     * @param sel0009O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0009O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0009O(String sel0009O) {
        this.sel0009O = requireWidth(sel0009O, SEL_LENGTH, SEL0009 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID09O PIC X(16)} - row 9 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid09O() {
        return trnid09O;
    }

    /**
     * Stores {@code TRNID09O}.
     *
     * @param trnid09O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid09O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid09O(String trnid09O) {
        this.trnid09O = requireWidth(trnid09O, TRNID_LENGTH, TRNID09 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE09O PIC X(8)} - row 9 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate09O() {
        return tdate09O;
    }

    /**
     * Stores {@code TDATE09O}.
     *
     * @param tdate09O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate09O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate09O(String tdate09O) {
        this.tdate09O = requireWidth(tdate09O, TDATE_LENGTH, TDATE09 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC09O PIC X(26)} - row 9 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc09O() {
        return tdesc09O;
    }

    /**
     * Stores {@code TDESC09O}.
     *
     * @param tdesc09O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc09O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc09O(String tdesc09O) {
        this.tdesc09O = requireWidth(tdesc09O, TDESC_LENGTH, TDESC09 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT009O PIC X(12)} - row 9 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt009O() {
        return tamt009O;
    }

    /**
     * Stores {@code TAMT009O}.
     *
     * @param tamt009O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt009O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt009O(String tamt009O) {
        this.tamt009O = requireWidth(tamt009O, TAMT_LENGTH, TAMT009 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0010O PIC X(1)} - row 10 selector, the last row on the page.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getSel0010O() {
        return sel0010O;
    }

    /**
     * Stores {@code SEL0010O}.
     *
     * @param sel0010O at most {@value #SEL_LENGTH} character
     * @throws NullPointerException     if {@code sel0010O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setSel0010O(String sel0010O) {
        this.sel0010O = requireWidth(sel0010O, SEL_LENGTH, SEL0010 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID10O PIC X(16)} - row 10 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTrnid10O() {
        return trnid10O;
    }

    /**
     * Stores {@code TRNID10O}.
     *
     * @param trnid10O at most {@value #TRNID_LENGTH} characters
     * @throws NullPointerException     if {@code trnid10O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTrnid10O(String trnid10O) {
        this.trnid10O = requireWidth(trnid10O, TRNID_LENGTH, TRNID10 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE10O PIC X(8)} - row 10 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdate10O() {
        return tdate10O;
    }

    /**
     * Stores {@code TDATE10O}.
     *
     * @param tdate10O at most {@value #TDATE_LENGTH} characters
     * @throws NullPointerException     if {@code tdate10O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdate10O(String tdate10O) {
        this.tdate10O = requireWidth(tdate10O, TDATE_LENGTH, TDATE10 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC10O PIC X(26)} - row 10 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTdesc10O() {
        return tdesc10O;
    }

    /**
     * Stores {@code TDESC10O}.
     *
     * @param tdesc10O at most {@value #TDESC_LENGTH} characters
     * @throws NullPointerException     if {@code tdesc10O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTdesc10O(String tdesc10O) {
        this.tdesc10O = requireWidth(tdesc10O, TDESC_LENGTH, TDESC10 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT010O PIC X(12)} - row 10 edited amount. The last of the ten
     * <strong>three</strong>-digit amount suffixes.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getTamt010O() {
        return tamt010O;
    }

    /**
     * Stores {@code TAMT010O}.
     *
     * @param tamt010O at most {@value #TAMT_LENGTH} characters
     * @throws NullPointerException     if {@code tamt010O} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setTamt010O(String tamt010O) {
        this.tamt010O = requireWidth(tamt010O, TAMT_LENGTH, TAMT010 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line at screen position {@code (23,1)}, rendered
     * {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     *
     * <p>{@code COTRN00C} blanks it at line 103 and fills it from the 80-character
     * {@code WS-MESSAGE} at line 531, which truncates on the right. Use
     * {@link #moveMessageToErrorLine(String)} to perform that move through the codec.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    public String getErrmsgO() {
        return errmsgO;
    }

    /**
     * Stores {@code ERRMSGO}.
     *
     * @param errmsgO at most {@value #ERRMSG_LENGTH} characters
     * @throws NullPointerException     if {@code errmsgO} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setErrmsgO(String errmsgO) {
        this.errmsgO = requireWidth(errmsgO, ERRMSG_LENGTH, ERRMSG + OUTPUT_ITEM_SUFFIX);
    }


    // =================================================================================================
    // Row addressing. Screen rows are 1-based; the single conversion to a 0-based Java position happens
    // here and nowhere else.
    //
    // The per-row prefix table is built from the literal constants declared above - never by formatting
    // a row number into a name - because the three suffix widths differ and a formatter is exactly where
    // SEL0001 would become SEL01 or TAMT001 would become TAMT01.
    // =================================================================================================

    /** Position of the selector within a row's five fields. */
    private static final int SELECTOR_COLUMN = 0;

    /** Position of the transaction identifier within a row's five fields. */
    private static final int TRANSACTION_ID_COLUMN = 1;

    /** Position of the transaction date within a row's five fields. */
    private static final int DATE_COLUMN = 2;

    /** Position of the description within a row's five fields. */
    private static final int DESCRIPTION_COLUMN = 3;

    /** Position of the edited amount within a row's five fields. */
    private static final int AMOUNT_COLUMN = 4;

    /**
     * The ten rows' field prefixes, each in the copybook's within-row order - selector, identifier,
     * date, description, amount. Index 0 is screen row 1.
     *
     * <p>Deeply immutable, and assembled from the literal prefix constants so that every one of the
     * fifty names is the same string object the flat accessors validate against.
     */
    private static final List<List<String>> ROW_FIELD_PREFIXES = List.of(
            List.of(SEL0001, TRNID01, TDATE01, TDESC01, TAMT001),
            List.of(SEL0002, TRNID02, TDATE02, TDESC02, TAMT002),
            List.of(SEL0003, TRNID03, TDATE03, TDESC03, TAMT003),
            List.of(SEL0004, TRNID04, TDATE04, TDESC04, TAMT004),
            List.of(SEL0005, TRNID05, TDATE05, TDESC05, TAMT005),
            List.of(SEL0006, TRNID06, TDATE06, TDESC06, TAMT006),
            List.of(SEL0007, TRNID07, TDATE07, TDESC07, TAMT007),
            List.of(SEL0008, TRNID08, TDATE08, TDESC08, TAMT008),
            List.of(SEL0009, TRNID09, TDATE09, TDESC09, TAMT009),
            List.of(SEL0010, TRNID10, TDATE10, TDESC10, TAMT010));

    /**
     * The five verbatim field prefixes of one screen row, in copybook order.
     *
     * @param oneBasedRow the screen row, {@value #FIRST_ROW} to {@value #LAST_ROW}
     * @return an immutable list of five prefixes, for example
     *         {@code [SEL0001, TRNID01, TDATE01, TDESC01, TAMT001]} for row 1
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    public static List<String> rowFieldPrefixes(int oneBasedRow) {
        requireRow(oneBasedRow);
        return ROW_FIELD_PREFIXES.get(oneBasedRow - 1);
    }

    /**
     * The five verbatim payload item names of one screen row, in copybook order.
     *
     * @param oneBasedRow the screen row, {@value #FIRST_ROW} to {@value #LAST_ROW}
     * @return an immutable list of five item names, for example
     *         {@code [SEL0010O, TRNID10O, TDATE10O, TDESC10O, TAMT010O]} for row 10
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    public static List<String> rowFieldNames(int oneBasedRow) {
        return rowFieldPrefixes(oneBasedRow).stream()
                .map(TransactionListResponse::outputItemName)
                .toList();
    }

    /**
     * The row selector of one screen row: {@code SEL0001O} for row 1 through {@code SEL0010O} for
     * row 10.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowSelection(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(SELECTOR_COLUMN));
    }

    /**
     * Stores the row selector of one screen row.
     *
     * @param oneBasedRow the screen row
     * @param value       at most {@value #SEL_LENGTH} character
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too
     *                                  wide
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowSelection(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(SELECTOR_COLUMN), value);
    }

    /**
     * The transaction identifier of one screen row: {@code TRNID01O} through {@code TRNID10O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowTransactionId(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(TRANSACTION_ID_COLUMN));
    }

    /**
     * Stores the transaction identifier of one screen row.
     *
     * @param oneBasedRow the screen row
     * @param value       at most {@value #TRNID_LENGTH} characters
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too
     *                                  wide
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowTransactionId(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(TRANSACTION_ID_COLUMN), value);
    }

    /**
     * The transaction date of one screen row: {@code TDATE01O} through {@code TDATE10O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowTransactionDate(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(DATE_COLUMN));
    }

    /**
     * Stores the transaction date of one screen row.
     *
     * @param oneBasedRow the screen row
     * @param value       at most {@value #TDATE_LENGTH} characters
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too
     *                                  wide
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowTransactionDate(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(DATE_COLUMN), value);
    }

    /**
     * The description of one screen row: {@code TDESC01O} through {@code TDESC10O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowDescription(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(DESCRIPTION_COLUMN));
    }

    /**
     * Stores the description of one screen row.
     *
     * @param oneBasedRow the screen row
     * @param value       at most {@value #TDESC_LENGTH} characters
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too
     *                                  wide
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowDescription(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(DESCRIPTION_COLUMN), value);
    }

    /**
     * The edited amount of one screen row: {@code TAMT001O} through {@code TAMT010O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowAmount(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(AMOUNT_COLUMN));
    }

    /**
     * Stores the edited amount of one screen row.
     *
     * <p>The value is the {@code PIC +99999999.99} edit form, not the raw {@code S9(09)V99} record
     * field: producing the edit is the service's work, and this type performs no arithmetic and holds
     * no numeric amount type.
     *
     * @param oneBasedRow the screen row
     * @param value       at most {@value #TAMT_LENGTH} characters
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too
     *                                  wide
     * @throws NullPointerException     if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowAmount(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(AMOUNT_COLUMN), value);
    }

    // =================================================================================================
    // Generic access by verbatim field name. This is what a field-by-field diff and a generic screen
    // renderer need, and it is why the field table exists.
    // =================================================================================================

    /**
     * The payload item name of a field: its prefix plus {@code O}.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @return for example {@code TAMT001O}
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    public static String outputItemName(String fieldPrefix) {
        return requireField(fieldPrefix).outputItemName();
    }

    /**
     * The extended-colour item name of a field: its prefix plus {@code C}. This is the item
     * {@code app/cpy/CSSETATY.cpy} moves {@link BmsAttributes#DFHRED} into.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @return for example {@code TAMT001C}
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    public static String colourItemName(String fieldPrefix) {
        return requireField(fieldPrefix).colourItemName();
    }

    /**
     * A field's declared {@code PIC X(n)} width, which is also its {@code DFHMDF LENGTH=}.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @return the declared width in characters
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    public static int declaredLength(String fieldPrefix) {
        return requireField(fieldPrefix).declaredLength();
    }

    /**
     * The {@value #FIELD_COUNT} verbatim field prefixes in copybook order.
     *
     * @return an immutable list; never {@code null}
     */
    public static List<String> fieldPrefixes() {
        return FIELD_PREFIXES;
    }

    /**
     * The {@value #FIELD_COUNT} verbatim payload item names in copybook order - {@code TRNNAMEO}
     * first, {@code ERRMSGO} last.
     *
     * @return an immutable list; never {@code null}
     */
    public static List<String> payloadFieldNames() {
        return PAYLOAD_FIELD_NAMES;
    }

    /**
     * Reads any payload field by its verbatim prefix, untrimmed.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @return the stored value; never {@code null}
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public String payloadValue(String fieldPrefix) {
        return requireField(fieldPrefix).reader().apply(this);
    }

    /**
     * Writes any payload field by its verbatim prefix, through the same validation the flat setter
     * applies.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @param value       at most the field's declared width
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map, or the value
     *                                  is wider than the field
     */
    @JsonIgnore
    public void setPayloadValue(String fieldPrefix, String value) {
        requireField(fieldPrefix).writer().accept(this, value);
    }

    /**
     * Every payload field keyed by its verbatim item name, in copybook order - the fingerprint a
     * field-by-field diff compares.
     *
     * <p>Values are as stored, untrimmed, because the space padding of a {@code PIC X} field is part
     * of the field and a diff is meant to see it.
     *
     * @return a new insertion-ordered map of {@value #FIELD_COUNT} entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, String> payloadFieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            values.put(field.outputItemName(), field.reader().apply(this));
        }
        return Collections.unmodifiableMap(values);
    }

    // =================================================================================================
    // The attribute quad. Metadata only - reachable here, never in the JSON.
    // =================================================================================================

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of one field.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @return the field's attributes; never {@code null}, because every field is pre-populated
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public FieldAttributes attributesOf(String fieldPrefix) {
        return fieldAttributes.get(requireField(fieldPrefix).fieldPrefix());
    }

    /**
     * Replaces the attribute quad of one field.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @param attributes  the quad to store
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public void putAttributes(String fieldPrefix, FieldAttributes attributes) {
        Objects.requireNonNull(attributes, "An attribute quad is required; call "
                + "FieldAttributes.lowValues() to restore the MOVE LOW-VALUES state");
        fieldAttributes.put(requireField(fieldPrefix).fieldPrefix(), attributes);
    }

    /**
     * Every field's attribute quad, keyed by verbatim prefix in copybook order.
     *
     * @return an unmodifiable view of {@value #FIELD_COUNT} entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, FieldAttributes> allAttributes() {
        return Collections.unmodifiableMap(fieldAttributes);
    }

    /**
     * Restores every field's attribute quad to low values: the
     * {@code MOVE LOW-VALUES TO COTRN0AO} of {@code COTRN00C:114} as it applies to the attribute
     * items. The payload items are left alone, because that statement's effect on them is superseded
     * by the {@code MOVE SPACES} and {@code MOVE} statements that follow it.
     */
    @JsonIgnore
    public void resetAttributesToLowValues() {
        for (String fieldPrefix : FIELD_PREFIXES) {
            fieldAttributes.put(fieldPrefix, FieldAttributes.lowValues());
        }
    }

    /**
     * Applies a {@code CSSETATY} highlight decision to one field.
     *
     * <p>This is the receiving end of {@code app/cpy/CSSETATY.cpy}: when the decision says so, it
     * moves {@link BmsAttributes#DFHRED} into the field's {@code xxxC} colour item, and when the
     * decision says so as well, it moves {@code '*'} into the field's {@code xxxO} payload item.
     * Nothing else happens, and nothing happens at all when the decision is
     * {@link FieldHighlight#untouched()}.
     *
     * <p>The <em>decision</em> is emphatically not made here.
     * {@link FieldAttributeSetter#resolveFromFlags(boolean, boolean, boolean, String, String)} owns
     * it, takes {@code CDEMO-PGM-REENTER} as an explicit boolean, and only ever returns an assigning
     * decision when the program is in re-entry context - so a highlight is reachable on re-entry and
     * unreachable on first entry, exactly as the copybook's outer {@code IF} requires. Splitting the
     * decision from its effect this way is what keeps the two testable independently.
     *
     * <p>The {@code '*'} is written through {@link #setPayloadValue(String, String)}, so a field
     * narrower than one character - there is none - would still be rejected rather than silently
     * overrun.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @param highlight   the decision {@link FieldAttributeSetter} produced for this field
     * @throws NullPointerException     if either argument is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public void applyHighlight(String fieldPrefix, FieldHighlight highlight) {
        PayloadField field = requireField(fieldPrefix);
        Objects.requireNonNull(highlight, "A highlight decision is required; call "
                + "FieldHighlight.none(prefix, map) to express 'change nothing'");
        if (highlight.untouched()) {
            return;
        }
        byte colour = highlight.colourItemValue();
        fieldAttributes.put(field.fieldPrefix(), attributesOf(field.fieldPrefix()).withColour(colour));
        if (highlight.outputItemAssigned()) {
            setPayloadValue(field.fieldPrefix(), highlight.outputItemValue());
        }
    }

    /**
     * Whether a field's colour item currently holds {@link BmsAttributes#DFHRED} - that is, whether it
     * has been flagged in error.
     *
     * @param fieldPrefix one of the {@value #FIELD_COUNT} verbatim prefixes
     * @return {@code true} when the field is highlighted red
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public boolean isFieldHighlighted(String fieldPrefix) {
        return attributesOf(fieldPrefix).isColourRed();
    }

    // =================================================================================================
    // Navigation. EXEC CICS XCTL becomes a response field the client acts on; the server never forwards.
    // =================================================================================================

    /**
     * The program the client should call next - the target of {@code EXEC CICS XCTL PROGRAM(...)}.
     *
     * <p>{@code COTRN00C} has exactly two transfer sites, at lines 193 and 519, and <em>both</em> are
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} - the COMMAREA-driven shape. One field therefore serves
     * both, and modelling two would be modelling a distinction the source does not make. Line 193 is
     * the row-selection path, which sets {@code CDEMO-TO-PROGRAM} to {@code COTRN01C} at line 188;
     * line 519 is {@code RETURN-TO-PREV-SCREEN}, which defaults it to {@code COSGN00C} at line 513
     * and is reached with {@code COMEN01C} from the PF3 arm at line 123.
     *
     * <p>The target is a plain program name. It is deliberately <em>not</em> expressed by importing a
     * sibling request or response type: the client issues the follow-up call, so there is no
     * server-side forward and no redirect chain, and coupling this payload to another screen's payload
     * would create exactly the dependency statelessness is meant to avoid.
     *
     * @return the next program name, untrimmed; never {@code null}
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Stores the next program name.
     *
     * @param nextProgram at most {@value NavigationContext#TO_PROGRAM_LENGTH} characters, the width of
     *                    {@code CDEMO-TO-PROGRAM}
     * @throws NullPointerException     if {@code nextProgram} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram = requireWidth(nextProgram, NavigationContext.TO_PROGRAM_LENGTH,
                "CDEMO-TO-PROGRAM");
    }

    /**
     * The mapset the next screen belongs to. {@code X(7)} - the width of {@code CDEMO-LAST-MAPSET},
     * which is seven and not eight.
     *
     * @return the mapset name; never {@code null}
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Stores the next mapset name.
     *
     * @param nextMapset at most {@value NavigationContext#LAST_MAPSET_LENGTH} characters
     * @throws NullPointerException     if {@code nextMapset} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = requireWidth(nextMapset, NavigationContext.LAST_MAPSET_LENGTH,
                "CDEMO-LAST-MAPSET");
    }

    /**
     * The map the next screen uses. {@code X(7)} - the width of {@code CDEMO-LAST-MAP}, which is
     * seven and not eight. This map's own name, {@link #MAP_NAME}, is seven characters, matching the
     * symbolic groups {@code COTRN0AI} and {@code COTRN0AO}.
     *
     * @return the map name; never {@code null}
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Stores the next map name.
     *
     * @param nextMap at most {@value NavigationContext#LAST_MAP_LENGTH} characters
     * @throws NullPointerException     if {@code nextMap} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setNextMap(String nextMap) {
        this.nextMap = requireWidth(nextMap, NavigationContext.LAST_MAP_LENGTH, "CDEMO-LAST-MAP");
    }

    /**
     * Echoes {@code CDEMO-TO-PROGRAM} out of the communication area as the next program, and names
     * this map and mapset as the screen the client is on - the stateless equivalent of both
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)} sites.
     *
     * @param context the communication area whose {@code CDEMO-TO-PROGRAM} names the target
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @JsonIgnore
    public void echoTransferTarget(NavigationContext context) {
        Objects.requireNonNull(context, "A communication area is required to echo CDEMO-TO-PROGRAM");
        setNextProgram(context.toProgram());
        setNextMapset(MAPSET_NAME);
        setNextMap(MAP_NAME);
    }

    // =================================================================================================
    // Conversation state, carried in the payload.
    // =================================================================================================

    /**
     * The 160-byte {@code CARDDEMO-COMMAREA} this response echoes.
     *
     * @return the communication area; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Stores the communication area to echo. It is a record, so no copy is needed and none is made.
     *
     * @param navigationContext the communication area
     * @throws NullPointerException if {@code navigationContext} is {@code null}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A communication area is required; call NavigationContext.empty() for the initial "
                        + "state. There is no null commarea");
    }

    /**
     * The 58-byte {@code CDEMO-CT00-INFO} browse cursor this response echoes.
     *
     * @return the cursor; never {@code null}
     */
    public TransactionListCursor getCursor() {
        return cursor;
    }

    /**
     * Stores the browse cursor. The instance is copied, because the cursor is mutable and sharing one
     * across two responses would let paging in one move the other.
     *
     * @param cursor the cursor to carry
     * @throws NullPointerException if {@code cursor} is {@code null}
     */
    public void setCursor(TransactionListCursor cursor) {
        Objects.requireNonNull(cursor, "A cursor is required; call new TransactionListCursor() for "
                + "the declared initial state");
        this.cursor = new TransactionListCursor(cursor);
    }


    // =================================================================================================
    // The COBOL paragraphs that write this group, reproduced statement for statement.
    //
    // Each takes the codec where the source performs a cross-width MOVE, so the direction of the
    // truncation is chosen by the same one implementation of the PIC X rule that the rest of the module
    // uses, and the caller states the code page explicitly rather than inheriting the platform's.
    // =================================================================================================

    /**
     * {@code POPULATE-HEADER-INFO} [{@code COTRN00C:567-586}], statement for statement:
     *
     * <pre>
     *   MOVE CCDA-TITLE01        TO TITLE01O   (line 571)
     *   MOVE CCDA-TITLE02        TO TITLE02O   (line 572)
     *   MOVE WS-TRANID           TO TRNNAMEO   (line 573)
     *   MOVE WS-PGMNAME          TO PGMNAMEO   (line 574)
     *   MOVE WS-CURDATE-MM-DD-YY TO CURDATEO   (line 580)
     *   MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO   (line 586)
     * </pre>
     *
     * <p>Every sending field is already exactly its receiver's width - the two titles are
     * {@code PIC X(40)} and so are the two title items, {@code WS-TRANID} is {@code X(04)} and so is
     * {@code TRNNAMEO}, {@code WS-PGMNAME} is {@code X(08)} and so is {@code PGMNAMEO}, and both edited
     * date renderings are eight characters - so nothing pads and nothing truncates, and no codec is
     * needed.
     *
     * @param dateHeader the captured date and time, whose {@code WS-CURDATE-MM-DD-YY} and
     *                   {@code WS-CURTIME-HH-MM-SS} renderings fill the two clock fields
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    @JsonIgnore
    public void populateHeaderInfo(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A date header is required to populate the heading lines; "
                + "COTRN00C:569 takes them from FUNCTION CURRENT-DATE");
        setTitle01O(ScreenTitles.CCDA_TITLE01);
        setTitle02O(ScreenTitles.CCDA_TITLE02);
        setTrnnameO(TRANSACTION_ID);
        setPgmnameO(PROGRAM_NAME);
        setCurdateO(dateHeader.wsCurdateMmDdYy());
        setCurtimeO(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * {@code POPULATE-TRAN-DATA} [{@code COTRN00C:381-445}] for one row: the four moves the
     * {@code EVALUATE WS-IDX} arm performs.
     *
     * <pre>
     *   MOVE TRAN-ID       TO TRNIDnnI   (and, for row 1 only, TO CDEMO-CT00-TRNID-FIRST)
     *   MOVE WS-TRAN-DATE  TO TDATEnnI
     *   MOVE TRAN-DESC     TO TDESCnnI
     *   MOVE WS-TRAN-AMT   TO TAMT00nI
     * </pre>
     *
     * <p>Three details of this paragraph are behaviour and are reproduced exactly:
     *
     * <ul>
     *   <li><strong>An out-of-range row does nothing.</strong> The {@code EVALUATE} ends
     *       {@code WHEN OTHER CONTINUE} at lines 443-444, so an index outside 1..10 is a no-op and not
     *       an error. This method therefore returns silently rather than throwing - which is why it
     *       does not share the {@link #requireRow(int)} guard the row accessors use.</li>
     *   <li><strong>The description truncates on the right.</strong> {@code TRAN-DESC} is
     *       {@code PIC X(100)} and {@code TDESCnnO} is {@code X(26)}, so 74 characters are discarded
     *       from the right. That move is performed through
     *       {@link FixedWidthCodec#movePicX(String, int)} so the direction is the codec's single
     *       implementation of the rule rather than a substring written here.</li>
     *   <li><strong>Row one also seeds the cursor.</strong> Line 393 moves {@code TRAN-ID} into
     *       {@code CDEMO-CT00-TRNID-FIRST} as well, which is what makes backward paging possible.
     *       Only row one does this.</li>
     * </ul>
     *
     * <p>The amount is the {@code PIC +99999999.99} edit form. This type does not produce it: the
     * service does, because producing it needs the fixed-point policy and this payload carries
     * characters.
     *
     * @param codec         the codec whose {@code PIC X} move rule pads and truncates each value
     * @param oneBasedRow   the screen row; outside {@value #FIRST_ROW}..{@value #LAST_ROW} this call
     *                      does nothing, reproducing {@code WHEN OTHER CONTINUE}
     * @param tranId        {@code TRAN-ID}, the raw {@code X(16)} record field
     * @param tranDate      {@code WS-TRAN-DATE}, the {@code MM/DD/YY} rendering built at lines 385-388
     * @param tranDesc      {@code TRAN-DESC}, the raw {@code X(100)} record field
     * @param editedAmount  {@code WS-TRAN-AMT}, the twelve-character edited amount
     * @throws NullPointerException if {@code codec} or any value is {@code null}
     */
    @JsonIgnore
    public void populateTranData(FixedWidthCodec codec,
                                 int oneBasedRow,
                                 String tranId,
                                 String tranDate,
                                 String tranDesc,
                                 String editedAmount) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC X move rule; the code "
                + "page must be stated explicitly and is never taken from the platform");
        if (!isRowOnPage(oneBasedRow)) {
            // EVALUATE WS-IDX ... WHEN OTHER CONTINUE - COTRN00C:443-444.
            return;
        }
        List<String> prefixes = ROW_FIELD_PREFIXES.get(oneBasedRow - 1);
        setPayloadValue(prefixes.get(TRANSACTION_ID_COLUMN),
                codec.movePicX(tranId, TRNID_LENGTH));
        setPayloadValue(prefixes.get(DATE_COLUMN),
                codec.movePicX(tranDate, TDATE_LENGTH));
        setPayloadValue(prefixes.get(DESCRIPTION_COLUMN),
                codec.movePicX(tranDesc, TDESC_LENGTH));
        setPayloadValue(prefixes.get(AMOUNT_COLUMN),
                codec.movePicX(editedAmount, TAMT_LENGTH));
        if (oneBasedRow == FIRST_ROW) {
            // MOVE TRAN-ID TO ... CDEMO-CT00-TRNID-FIRST - COTRN00C:392-393.
            cursor.setTrnidFirst(codec.movePicX(tranId,
                    TransactionListCursor.TRNID_FIRST_LENGTH));
        }
    }

    /**
     * {@code INITIALIZE-TRAN-DATA} [{@code COTRN00C:450-505}] for one row: the four
     * {@code MOVE SPACES} statements the {@code EVALUATE WS-IDX} arm performs.
     *
     * <pre>
     *   MOVE SPACES TO TRNIDnnI
     *   MOVE SPACES TO TDATEnnI
     *   MOVE SPACES TO TDESCnnI
     *   MOVE SPACES TO TAMT00nI
     * </pre>
     *
     * <p><strong>Four fields, not five.</strong> {@code SEL000nO} is deliberately <em>not</em> blanked:
     * check any arm of the paragraph - lines 453-457 for row 1 through 498-502 for row 10 - and the
     * selector is absent from every one of them. The selector carries what the user typed, and the
     * paragraph runs while re-painting a page, so blanking it would discard the input the ordered
     * selection {@code EVALUATE} at lines 148-182 is about to read. Adding a fifth
     * {@code MOVE SPACES} here would look tidier and would change behaviour.
     *
     * <p>As with {@code POPULATE-TRAN-DATA}, an out-of-range row does nothing:
     * {@code WHEN OTHER CONTINUE} at lines 503-504.
     *
     * <p>A blanked field becomes spaces at its declared width - never {@code null}, and never a
     * rendered zero such as {@code 0.00} - so it serialises as the blank field the screen shows.
     *
     * @param oneBasedRow the screen row; outside {@value #FIRST_ROW}..{@value #LAST_ROW} this call does
     *                    nothing
     */
    @JsonIgnore
    public void initializeTranData(int oneBasedRow) {
        if (!isRowOnPage(oneBasedRow)) {
            // EVALUATE WS-IDX ... WHEN OTHER CONTINUE - COTRN00C:503-504.
            return;
        }
        List<String> prefixes = ROW_FIELD_PREFIXES.get(oneBasedRow - 1);
        setPayloadValue(prefixes.get(TRANSACTION_ID_COLUMN), spaces(TRNID_LENGTH));
        setPayloadValue(prefixes.get(DATE_COLUMN), spaces(TDATE_LENGTH));
        setPayloadValue(prefixes.get(DESCRIPTION_COLUMN), spaces(TDESC_LENGTH));
        setPayloadValue(prefixes.get(AMOUNT_COLUMN), spaces(TAMT_LENGTH));
    }

    /**
     * The whole-page form of {@link #initializeTranData(int)}: the
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10} loops at {@code COTRN00C:290-292}
     * and {@code :344-346}, which blank all ten rows before a page is filled.
     *
     * <p>Iterates {@value #FIRST_ROW} to {@value #LAST_ROW} inclusive, which is
     * {@value #PAGE_SIZE} rows - the page size, expressed as the loop bound the COBOL hard-codes.
     */
    @JsonIgnore
    public void initializeAllTranData() {
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            initializeTranData(row);
        }
    }

    /**
     * {@code MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI OF COTRN0AI} [{@code COTRN00C:324} and
     * {@code :373}]: the numeric {@code PIC 9(08)} cursor field moved into the alphanumeric
     * {@code X(8)} screen item.
     *
     * <p>Performed through {@link FixedWidthCodec#movePic9(long, int)}, which zero-fills on the left
     * exactly as a numeric {@code MOVE} does, so page 1 renders as {@code 00000001} rather than as
     * {@code 1} or {@code 1       }. That is the image the screen carries.
     *
     * @param codec   the codec whose {@code PIC 9} move rule renders the digits
     * @param pageNum the page number, from {@link TransactionListCursor#getPageNum()}
     * @throws NullPointerException     if {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code pageNum} is negative, since {@code PIC 9(08)} is
     *                                  unsigned and has no sign position
     */
    @JsonIgnore
    public void movePageNumberToScreen(FixedWidthCodec codec, int pageNum) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC 9 move rule");
        if (pageNum < 0) {
            throw new IllegalArgumentException(TransactionListCursor.PAGE_NUM_FIELD + " is PIC 9("
                    + TransactionListCursor.PAGE_NUM_LENGTH + "), an unsigned picture with no sign "
                    + "position, so " + pageNum + " has no representation in it");
        }
        setPagenumO(codec.movePic9(pageNum, PAGENUM_LENGTH));
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COTRN0AO} [{@code COTRN00C:531}], the first statement of
     * {@code SEND-TRNLST-SCREEN} after the heading is populated.
     *
     * <p>{@code WS-MESSAGE} is {@code PIC X(80)} [{@code COTRN00C:38}] and {@code ERRMSGO} is
     * {@code X(78)}, so this move truncates two characters on the right. It is performed through
     * {@link FixedWidthCodec#movePicX(String, int)} so the direction is the codec's, and a shorter
     * message is padded to the full 78 - which is what makes the error line clear itself when the
     * message is blank.
     *
     * @param codec   the codec whose {@code PIC X} move rule pads and truncates
     * @param message the message, of any length; {@code COTRN00C} passes an 80-character
     *                {@code WS-MESSAGE}
     * @throws NullPointerException if {@code codec} or {@code message} is {@code null}
     */
    @JsonIgnore
    public void moveMessageToErrorLine(FixedWidthCodec codec, String message) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC X move rule");
        setErrmsgO(codec.movePicX(message, ERRMSG_LENGTH));
    }

    /**
     * {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE} [{@code COTRN00C:132}] followed by that
     * message reaching the error line at line 531 - the {@code WHEN OTHER} arm of the
     * {@code EVALUATE EIBAID} dispatch, taken when the key pressed is none of {@code DFHENTER},
     * {@code DFHPF3}, {@code DFHPF7} or {@code DFHPF8}.
     *
     * <p>{@link SystemMessages#CCDA_MSG_INVALID_KEY} is the copybook's {@code PIC X(50)} value, so it
     * is padded on the right to the error line's 78 characters.
     *
     * @param codec the codec whose {@code PIC X} move rule pads the message
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    @JsonIgnore
    public void moveInvalidKeyMessageToErrorLine(FixedWidthCodec codec) {
        moveMessageToErrorLine(codec, SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * {@code MOVE SPACES TO ERRMSGO OF COTRN0AO} [{@code COTRN00C:103}], part of the very first
     * statement of {@code MAIN-PARA}: the error line is cleared before anything else happens.
     */
    @JsonIgnore
    public void clearErrorLine() {
        this.errmsgO = spaces(ERRMSG_LENGTH);
    }

    /**
     * {@code MOVE SPACE TO TRNIDINO OF COTRN0AO} [{@code COTRN00C:228} and {@code :325}]: the
     * browse-start key is cleared once a page has been painted, so the next {@code ENTER} browses on
     * from the cursor rather than restarting from the key the user originally typed.
     *
     * <p>Note the singular {@code SPACE} in the source: moving one space into a {@code PIC X(16)}
     * receiver fills the whole field with spaces, because COBOL pads an alphanumeric receiver on the
     * right. The result is sixteen spaces, which is what this method stores.
     */
    @JsonIgnore
    public void clearTranIdInput() {
        this.trnidinO = spaces(TRNIDIN_LENGTH);
    }

    // =================================================================================================
    // The fixed-width image of the COTRN0AO group.
    //
    // The image covers the group and only the group: the 12-byte TIOAPFX prefix, and per field the
    // 3-byte filler, the four attribute items and the payload item. The navigation targets, the
    // communication area and the browse cursor are NOT part of it - they belong to the commarea and to
    // the response envelope, and each has its own image where one is defined.
    // =================================================================================================

    /**
     * Renders the group as its {@value #RECORD_LENGTH}-byte image: each payload item space-padded to
     * its declared width, each attribute item as its stored character, and every {@code FILLER} span
     * emitted as spaces.
     *
     * <p>{@code FILLER} is emitted rather than skipped. The 12-byte {@code TIOAPFX} prefix and the ten
     * dozen three-byte per-field fillers carry no field, but they carry <em>bytes</em>: dropping any of
     * them would shift every field after it while still producing an image that looks plausible.
     *
     * @param codec the codec for the target code page, chosen explicitly by the caller
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toFixedWidth(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render COTRN0AO; the code page must be "
                + "stated explicitly and is never taken from the platform");
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes the group into an existing record area of exactly {@value #RECORD_LENGTH} bytes, using
     * that record's own code page.
     *
     * @param record the record area to write into
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@value #RECORD_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write COTRN0AO into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Rebuilds a response's screen fields from a group image.
     *
     * <p>The byte count must be exactly {@value #RECORD_LENGTH}; a short or over-long image is
     * rejected rather than tolerated, because accepting one would let every field offset after the
     * discrepancy drift.
     *
     * <p>Only the group is rebuilt. The navigation targets keep their defaults and the communication
     * area and cursor keep their initial states, because the image does not carry them.
     *
     * @param bytes   the image, exactly {@value #RECORD_LENGTH} bytes
     * @param charset the code page the image is encoded in, named explicitly by the caller
     * @return a response carrying the 59 payload fields and 59 attribute quads the image holds
     * @throws NullPointerException     if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@value #RECORD_LENGTH}
     */
    public static TransactionListResponse fromFixedWidth(byte[] bytes, Charset charset) {
        Objects.requireNonNull(bytes, "An image is required to rebuild COTRN0AO");
        Objects.requireNonNull(charset, "A charset is required to decode a COTRN0AO image; the code "
                + "page must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(bytes, LAYOUT), codec);
    }

    /**
     * Reads a response's screen fields out of an existing record area, using that record's own code
     * page.
     *
     * @param record a record area of exactly {@value #RECORD_LENGTH} bytes
     * @return a response carrying the fields the record holds
     * @throws NullPointerException     if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@value #RECORD_LENGTH}
     */
    public static TransactionListResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read COTRN0AO from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * The single write path: one pass over the field table, writing each field's four attribute items
     * and then its payload item.
     *
     * @param record the record area, of exactly {@value #RECORD_LENGTH} bytes
     * @param codec  the codec for the record's code page
     */
    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireGroupWidth(record);
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            FieldAttributes attributes = fieldAttributes.get(field.fieldPrefix());
            writeAttributeItem(record, field.fieldPrefix(), COLOUR_ITEM_SUFFIX,
                    attributes.colour());
            writeAttributeItem(record, field.fieldPrefix(), PS_ITEM_SUFFIX,
                    attributes.programmedSymbols());
            writeAttributeItem(record, field.fieldPrefix(), HIGHLIGHT_ITEM_SUFFIX,
                    attributes.highlight());
            writeAttributeItem(record, field.fieldPrefix(), VALIDATION_ITEM_SUFFIX,
                    attributes.validation());
            codec.writePicX(record, LAYOUT.span(field.outputItemName()),
                    field.reader().apply(this));
        }
    }

    /**
     * The single read path: one pass over the field table, reading each field's four attribute items
     * and then its payload item, all untrimmed.
     *
     * @param record the record area, of exactly {@value #RECORD_LENGTH} bytes
     * @param codec  the codec for the record's code page
     * @return the response the record carries
     */
    private static TransactionListResponse readFrom(FixedWidthRecord record,
                                                    FixedWidthCodec codec) {
        requireGroupWidth(record);
        TransactionListResponse response = new TransactionListResponse();
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            response.putAttributes(field.fieldPrefix(), new FieldAttributes(
                    readAttributeItem(record, field.fieldPrefix(), COLOUR_ITEM_SUFFIX),
                    readAttributeItem(record, field.fieldPrefix(), PS_ITEM_SUFFIX),
                    readAttributeItem(record, field.fieldPrefix(), HIGHLIGHT_ITEM_SUFFIX),
                    readAttributeItem(record, field.fieldPrefix(), VALIDATION_ITEM_SUFFIX)));
            field.writer().accept(response,
                    codec.readPicX(record, LAYOUT.span(field.outputItemName())));
        }
        return response;
    }

    /**
     * Writes one attribute item as a <strong>raw byte</strong>.
     *
     * <p>Deliberately not routed through {@link FixedWidthCodec#writePicX}: a 3270 attribute is a
     * byte and not a character, and {@link BmsAttributes#DFHRED} ({@code 0xF2}) has no character
     * equivalent in a single-byte text encoding - {@code US-ASCII} would substitute {@code '?'} and
     * the colour would be lost. The payload items go through the codec; these four do not.
     *
     * @param record the record area
     * @param prefix the field's verbatim prefix
     * @param suffix the item suffix, one of {@code C}, {@code P}, {@code H} or {@code V}
     * @param value  the attribute byte
     */
    private static void writeAttributeItem(FixedWidthRecord record, String prefix, String suffix,
                                           byte value) {
        record.writeSpanBytes(LAYOUT.span(prefix + suffix), new byte[] {value});
    }

    /**
     * Reads one attribute item as a raw byte, for the reason given on
     * {@link #writeAttributeItem(FixedWidthRecord, String, String, byte)}.
     *
     * @param record the record area
     * @param prefix the field's verbatim prefix
     * @param suffix the item suffix
     * @return the attribute byte
     */
    private static byte readAttributeItem(FixedWidthRecord record, String prefix, String suffix) {
        return record.readSpanBytes(LAYOUT.span(prefix + suffix))[0];
    }

    // =================================================================================================
    // Diagnostics.
    // =================================================================================================

    /**
     * A diagnostic rendering: the header fields, the ten rows and the error line, each named by its
     * verbatim item name, followed by the navigation targets and the browse cursor.
     *
     * <p>Values are shown as stored, with their padding intact, because the padding is part of the
     * field. Nothing is masked or elided - this payload carries no credential, and hiding a field here
     * would make a parity investigation harder for no benefit.
     *
     * @return a multi-line description; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(OUTPUT_MAP_GROUP_NAME).append("[\n");
        for (Map.Entry<String, String> entry : payloadFieldValues().entrySet()) {
            text.append("  ").append(entry.getKey()).append("='").append(entry.getValue())
                    .append("'\n");
        }
        text.append("  nextProgram='").append(nextProgram).append("'\n")
                .append("  nextMapset='").append(nextMapset).append("'\n")
                .append("  nextMap='").append(nextMap).append("'\n")
                .append("  ").append(cursor).append('\n')
                .append(']');
        return text.toString();
    }

    // =================================================================================================
    // Private guards. Each rule is stated once, so all 59 fields are held to the identical standard.
    // =================================================================================================

    /**
     * A run of spaces, the value a {@code PIC X} field holds when it has been blanked.
     *
     * @param count the field's declared width
     * @return exactly {@code count} spaces
     */
    private static String spaces(int count) {
        return SPACE.repeat(count);
    }

    /**
     * Validates a value against its field's declared width.
     *
     * <p>{@code null} is rejected: there is no null in a COBOL record - an unset {@code PIC X} field
     * holds spaces, which is what every field is initialised to.
     *
     * <p>An over-wide value is rejected rather than shortened. COBOL truncates a cross-width
     * {@code MOVE} on the right for {@code PIC X} and on the left for {@code PIC 9}, so the direction
     * is a per-{@code PICTURE} decision that has to be taken deliberately; if this guard chose one, a
     * truncation defect would be indistinguishable from correct behaviour. A caller that wants the
     * {@code PIC X} rule asks for it by name through {@link FixedWidthCodec#movePicX(String, int)},
     * exactly as the paragraph reproductions above do.
     *
     * <p>A shorter value is accepted, and is padded on the right with spaces when the image is
     * produced - exactly as a {@code MOVE} into a wider {@code PIC X} receiver pads.
     *
     * @param value          the value to store
     * @param declaredWidth  the field's declared width
     * @param itemName       the verbatim item name, for the diagnostic
     * @return {@code value}, unchanged
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is longer than {@code declaredWidth}
     */
    private static String requireWidth(String value, int declaredWidth, String itemName) {
        Objects.requireNonNull(value, () -> "A value is required for " + itemName
                + "; there is no null in a COBOL record - move SPACES to blank the field");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException(itemName + " is PIC X(" + declaredWidth + ") and "
                    + "cannot hold " + value.length() + " characters. This type never truncates "
                    + "silently: COBOL truncates a PIC X receiver on the right, so apply that rule "
                    + "deliberately through FixedWidthCodec.movePicX(value, " + declaredWidth + ")");
        }
        return value;
    }

    /**
     * Whether a screen row is on the page - that is, in {@value #FIRST_ROW}..{@value #LAST_ROW}.
     *
     * <p>Used by the two paragraph reproductions, whose {@code EVALUATE WS-IDX} ends
     * {@code WHEN OTHER CONTINUE} and so must treat an out-of-range index as a no-op.
     *
     * @param oneBasedRow the row to test
     * @return {@code true} when the row is on the page
     */
    private static boolean isRowOnPage(int oneBasedRow) {
        return oneBasedRow >= FIRST_ROW && oneBasedRow <= LAST_ROW;
    }

    /**
     * Validates a screen row, for the accessors - which, unlike the paragraph reproductions, have no
     * COBOL {@code WHEN OTHER CONTINUE} to reproduce and so must reject an impossible row rather than
     * silently return the wrong field or nothing at all.
     *
     * @param oneBasedRow the row to validate
     * @throws IllegalArgumentException if the row is outside {@value #FIRST_ROW}..{@value #LAST_ROW}
     */
    private static void requireRow(int oneBasedRow) {
        if (!isRowOnPage(oneBasedRow)) {
            throw new IllegalArgumentException("Screen row " + oneBasedRow + " is not on the page: "
                    + MAP_NAME + " declares " + ROW_COUNT + " transaction rows and they are "
                    + "1-based, so the valid range is " + FIRST_ROW + " to " + LAST_ROW
                    + " inclusive");
        }
    }

    /**
     * Resolves a verbatim field prefix, rejecting anything this map does not declare.
     *
     * <p>Rejecting rather than tolerating is the point: a mistyped prefix such as {@code TAMT01} -
     * the two-digit form that looks right and is wrong - would otherwise read or write nothing at all
     * and leave no trace.
     *
     * @param fieldPrefix the prefix to resolve
     * @return the field it names
     * @throws NullPointerException     if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not one of this map's
     *                                  {@value #FIELD_COUNT} fields
     */
    private static PayloadField requireField(String fieldPrefix) {
        Objects.requireNonNull(fieldPrefix, "A field prefix is required; the " + FIELD_COUNT
                + " valid prefixes are listed by fieldPrefixes()");
        PayloadField field = PAYLOAD_FIELDS.get(fieldPrefix);
        if (field == null) {
            throw new IllegalArgumentException("'" + fieldPrefix + "' is not a field of "
                    + OUTPUT_MAP_GROUP_NAME + ". The suffix widths differ between columns - SEL is "
                    + "four digits, TRNID, TDATE and TDESC are two, and TAMT is three - so check the "
                    + "spelling against app/cpy-bms/COTRN00.CPY; fieldPrefixes() lists all "
                    + FIELD_COUNT);
        }
        return field;
    }

    /**
     * Whether a field carries something other than spaces and low values - the COBOL condition
     * {@code NOT = SPACES AND LOW-VALUES} that {@code COTRN00C} tests at lines 149 to 184 before
     * treating a selector as a selection.
     *
     * <p>No {@code null} check: the only values that reach here are cursor fields, and the canonical
     * guard has already proved every one of them non-{@code null}. A defensive branch for an input
     * that cannot occur would be untestable and would only hide a genuine defect behind a
     * {@code false}, so a {@code null} is left to fail fast.
     *
     * @param value the value to test, never {@code null}
     * @return {@code true} when the value is neither all spaces nor all low values, and not empty
     */
    private static boolean isPresent(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && character != LOW_VALUE_CHARACTER) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rejects a record area that is not exactly the group's width.
     *
     * @param record the record area to check
     * @throws IllegalArgumentException if its declared length is not {@value #RECORD_LENGTH}
     */
    private static void requireGroupWidth(FixedWidthRecord record) {
        if (record.recordLength() != RECORD_LENGTH) {
            throw new IllegalArgumentException("A " + OUTPUT_MAP_GROUP_NAME + " record area is "
                    + RECORD_LENGTH + " bytes - 12 for the TIOAPFX prefix, " + FIELD_COUNT + " x "
                    + FIELD_PREFIX_LENGTH + " for the per-field attribute prefixes and "
                    + PAYLOAD_WIDTH_TOTAL + " of payload - but this one declares "
                    + record.recordLength());
        }
    }

}
