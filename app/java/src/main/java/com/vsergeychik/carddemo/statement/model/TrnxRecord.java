package com.vsergeychik.carddemo.statement.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The 350-byte {@code TRNX-RECORD} of {@code app/cpy/COSTM01.CPY}: the reshaped transaction layout
 * that the statement job reads, keyed by card number and transaction id.
 *
 * <h2>Provenance</h2>
 * {@code COSTM01.CPY} describes itself as the <em>"CardDemo - Transaction altered Layout for use in
 * reporting"</em>. It is not an independent record: it is a field-for-field reprojection of
 * {@code app/cpy/CVTRA05Y.cpy}'s {@code TRAN-RECORD}, produced by the {@code SORT} step of
 * {@code app/jcl/CREASTMT.JCL} and loaded into a KSDS whose key is the card number followed by the
 * transaction id. Both records are 350 bytes; only the field order differs, which is why this type
 * is declared separately rather than reusing the transaction model. Note also the casing trap:
 * {@code COSTM01.CPY} is the <strong>only</strong> upper-case member of {@code app/cpy}; its 27
 * siblings use a lower-case {@code .cpy} suffix.
 *
 * <h2>Verified layout</h2>
 * Every offset below was re-derived by summation from the copybook and totals exactly 350. The two
 * {@code 05}-level groups, {@code TRNX-KEY} and {@code TRNX-REST}, are alternative views of the same
 * storage as their {@code 10}-level children and are declared as such in {@link #layout()}.
 * <table border="1">
 *   <caption>{@code app/cpy/COSTM01.CPY}, 0-based offsets</caption>
 *   <tr><th>COBOL item</th><th>PICTURE</th><th>Offset</th><th>Length</th><th>Group</th></tr>
 *   <tr><td>{@code TRNX-KEY}</td><td><em>group</em></td><td>0</td><td>32</td><td>&nbsp;</td></tr>
 *   <tr><td>{@code TRNX-CARD-NUM}</td><td>{@code X(16)}</td><td>0</td><td>16</td><td>{@code TRNX-KEY}</td></tr>
 *   <tr><td>{@code TRNX-ID}</td><td>{@code X(16)}</td><td>16</td><td>16</td><td>{@code TRNX-KEY}</td></tr>
 *   <tr><td>{@code TRNX-REST}</td><td><em>group</em></td><td>32</td><td>318</td><td>&nbsp;</td></tr>
 *   <tr><td>{@code TRNX-TYPE-CD}</td><td>{@code X(02)}</td><td>32</td><td>2</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-CAT-CD}</td><td>{@code 9(04)}</td><td>34</td><td>4</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-SOURCE}</td><td>{@code X(10)}</td><td>38</td><td>10</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-DESC}</td><td>{@code X(100)}</td><td>48</td><td>100</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-AMT}</td><td>{@code S9(09)V99}</td><td>148</td><td>11</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-MERCHANT-ID}</td><td>{@code 9(09)}</td><td>159</td><td>9</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-MERCHANT-NAME}</td><td>{@code X(50)}</td><td>168</td><td>50</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-MERCHANT-CITY}</td><td>{@code X(50)}</td><td>218</td><td>50</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-MERCHANT-ZIP}</td><td>{@code X(10)}</td><td>268</td><td>10</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-ORIG-TS}</td><td>{@code X(26)}</td><td>278</td><td>26</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code TRNX-PROC-TS}</td><td>{@code X(26)}</td><td>304</td><td>26</td><td>{@code TRNX-REST}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>{@code X(20)}</td><td>330</td><td>20</td><td>{@code TRNX-REST}</td></tr>
 * </table>
 *
 * <h2>The 32-byte composite key is corroborated three independent ways</h2>
 * The key is not an inference drawn from the copybook alone. Three artefacts agree exactly:
 * <ol>
 *   <li>the copybook groups {@code TRNX-CARD-NUM X(16)} and {@code TRNX-ID X(16)} under
 *       {@code 05 TRNX-KEY}, which is 32 bytes at offset 0;</li>
 *   <li>{@code app/jcl/CREASTMT.JCL:29-36} defines the cluster with {@code KEYS(32 0)} - literally
 *       32 bytes at offset 0 - and {@code RECORDSIZE(350 350)}, which fixes the record width;</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL:34} declares {@code RECORD KEY IS FD-TRNXS-ID} over an
 *       {@code FD} that splits the record as {@code FD-TRNX-CARD X(16)} plus
 *       {@code FD-TRNX-ID X(16)} plus {@code FD-ACCT-DATA X(318)}, totalling 350
 *       ({@code CBSTM03B.CBL:58-63}).</li>
 * </ol>
 * {@code CREASTMT.JCL:53}'s {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} orders the derived file by
 * card number and then transaction id, which is precisely what makes the control break at
 * {@code app/cbl/CBSTM03A.CBL:819} - {@code IF WS-SAVE-CARD = TRNX-CARD-NUM} - correct. This type
 * does not perform that sort, but it must not disturb the key semantics the sort relies on, which is
 * why {@link #TRNX_KEY} is exposed as one contiguous 32-byte span as well as two 16-byte components.
 *
 * <h2>Why this type is span-backed rather than a plain value object</h2>
 * This is the single most consequential design decision in the statement package, and it is forced
 * by the caller. {@code app/cbl/CBSTM03A.CBL:426-427} moves a whole 318-byte
 * {@code WS-TRAN-REST PIC X(318)} table slot into {@code TRNX-REST}, and then <em>immediately</em>:
 * <pre>
 *   L426-427   MOVE WS-TRAN-REST (CR-JMP, TR-JMP) TO TRNX-REST
 *   L428       PERFORM 6000-WRITE-TRANS            &lt;- reads TRNX-ID, TRNX-DESC, TRNX-AMT (L676-678)
 *   L429       ADD TRNX-AMT TO WS-TOTAL-AMT
 * </pre>
 * In COBOL that is correct because {@code TRNX-AMT} and {@code TRNX-DESC} are <em>views onto the
 * same storage</em> the group move just overwrote. A conventional Java value object holding eagerly
 * decoded fields would compile, satisfy a width check and pass a round-trip test, and then return a
 * <strong>stale</strong> amount and description at those three lines - silently corrupting the
 * statement total with no exception and no failing assertion anywhere near the defect.
 *
 * <p>This class therefore keeps a single {@link FixedWidthRecord} byte area as the one authoritative
 * store, and <em>every</em> typed accessor is named {@code readXxx} and decodes from that area at the
 * moment it is called. No decoded value is ever cached, so there is nothing that can go stale and
 * nothing that has to be invalidated. The {@code readXxx} / {@code writeXxx} naming is deliberate:
 * it makes the read-through nature visible at each call site rather than implied.
 *
 * <h2>A latent defect in the legacy job, reproduced and not corrected</h2>
 * {@code app/jcl/CREASTMT.JCL:54} reshapes each sorted {@code CVTRA05Y} record with:
 * <pre>
 *   OUTREC FIELDS=(1:263,16, 17:1,262, 279:279,50)
 * </pre>
 * Traced against the verified {@code CVTRA05Y} layout, in which {@code TRAN-ORIG-TS} occupies 1-based
 * bytes 279-304 and {@code TRAN-PROC-TS} occupies 305-330, the third clause copies only <em>50</em>
 * of the 52 trailing bytes. So a {@code SORT}-derived record legitimately carries:
 * <ul>
 *   <li>a {@code TRNX-PROC-TS} holding 24 characters followed by two spaces, not 26; and</li>
 *   <li>output bytes 329-350 - which include the whole of {@code FILLER X(20)} - blank-padded by
 *       the sort to {@code LRECL=350}.</li>
 * </ul>
 * That is a genuine off-by-two in the legacy job. Under binding practice B4 it is
 * <strong>reproduced and documented, never corrected</strong>: this is a like-for-like migration, so
 * {@code TRNX-PROC-TS} stays declared as {@code X(26)} at offset 304, the two trailing spaces are
 * never special-cased away, and such a record decodes and re-encodes byte-identically without
 * complaint. {@link #TRNX_PROC_TS_SORT_DERIVED_LENGTH} records the 24 for documentation only and
 * must never be used to narrow or normalise the field. Parity fixtures are expected to show those
 * two trailing blanks; a fixture that shows 26 significant characters has been "helpfully" repaired
 * and is wrong.
 *
 * <h2>Numeric policy</h2>
 * {@code TRNX-AMT} is the only scaled field in the record. It is {@code PIC S9(09)V99} and occupies
 * exactly {@code 9 + 2 = 11} bytes: the sign is overpunched into the trailing byte and consumes no
 * byte of its own, which is the only width at which this copybook sums to 350. It is exposed as a
 * {@link BigDecimal} at scale exactly {@link CobolDecimal#MONETARY_SCALE}, and every store routes
 * through {@link CobolDecimal}, whose rounding mode is {@link CobolDecimal#COBOL_ROUNDING} -
 * {@code RoundingMode.DOWN}. Truncation rather than rounding is not a preference: the keyword
 * {@code ROUNDED} appears zero times in all 28 COBOL programs, so COBOL discards excess fractional
 * digits on store. {@code 1.239} therefore stores as {@code 1.23} and {@code -1.239} as
 * {@code -1.23}, both toward zero. No {@code double} or {@code float} appears anywhere in this class,
 * and no half-up, half-even, ceiling or floor rounding is reachable from it.
 *
 * <p>There is <strong>no</strong> {@code COMP-3} or {@code PACKED-DECIMAL} in this copybook - nor in
 * any of the 28 copybooks under {@code app/cpy} - so every persisted numeric here is zoned
 * {@code DISPLAY}, one digit per byte, and no nibble unpacking exists in this class.
 * {@code app/cbl/CBSTM03A.CBL:64-65} does declare its accumulator
 * {@code WS-TOTAL-AMT PIC S9(9)V99 COMP-3}, but that is {@code WORKING-STORAGE} held in memory by the
 * caller and is never part of this record.
 *
 * <p>{@code TRNX-CARD-NUM} and {@code TRNX-ID} are {@code PIC X} and are exposed as {@link String}.
 * They are never parsed as numbers, because a leading zero is significant in a card number and
 * numeric parsing would destroy it.
 *
 * <h2>Reading and writing the whole record</h2>
 * {@code app/cbl/CBSTM03A.CBL:756} and {@code :839} both execute
 * {@code MOVE WS-M03B-FLDT TO TRNX-RECORD}, where the sending field is the
 * {@code PIC X(1000)} response span of {@code app/cbl/CBSTM03B.CBL:112} and the receiver is 350
 * bytes. {@code CBSTM03B} deliberately hands back raw, space-padded 1000-byte spans and never
 * decodes, so that alphanumeric group move - and its right truncation - lives here, in
 * {@link #decode(byte[], Charset)}. A caller that wants the width checked instead of adjusted should
 * use {@link #wrap(byte[], Charset)}.
 *
 * <h2>Charset is always explicit</h2>
 * A {@link Charset} is a mandatory factory argument and is never derived from the platform. It is
 * held per instance for the reason {@link FixedWidthRecord} documents: the pad bytes themselves are
 * code-page dependent - a space is {@code 0x40} under {@code IBM037} and {@code 0x20} under
 * {@code US-ASCII} - so one immutable charset per record area is what prevents a caller mixing code
 * pages inside a single record, which would be a parity defect that is very hard to localise
 * afterwards.
 *
 * <h2>Thread safety and equality</h2>
 * An instance is a mutable record area, exactly as a COBOL {@code 01} item is, and is not safe for
 * concurrent mutation; confine one to the step or chunk that owns it. There is no mutable static
 * state: the descriptor table and the layout are immutable, and all record state is per instance.
 * {@code equals} and {@code hashCode} are deliberately <strong>not</strong> overridden - value
 * equality on a mutable byte area invites a silently broken hash key - so compare {@link #encode()}
 * images, or compare fields, and compare a {@link BigDecimal} with
 * {@link BigDecimal#compareTo(BigDecimal)} rather than {@code equals}, which is scale sensitive and
 * would make {@code 0.00} unequal to {@code 0.0}.
 *
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 * @see CobolDecimal
 */
public final class TrnxRecord {

    // =================================================================================================
    // Geometry. Every constant is transcribed by hand from app/cpy/COSTM01.CPY so that a reviewer
    // holding the copybook open beside this block can confirm each number by eye. The layout declared
    // further down re-checks the arithmetic at class-initialisation time and refuses to exist if the
    // spans do not sum to exactly RECORD_LENGTH.
    // =================================================================================================

    /**
     * The declared width of {@code TRNX-RECORD} in bytes, independently confirmed by
     * {@code RECORDSIZE(350 350)} in {@code app/jcl/CREASTMT.JCL:32} and by the
     * {@code 16 + 16 + 318} {@code FD} split at {@code app/cbl/CBSTM03B.CBL:58-63}.
     *
     * <p>{@code StatementGenerationJobB} reads this constant by name rather than repeating 350, so no
     * record width is written twice anywhere in the statement package.
     */
    public static final int RECORD_LENGTH = 350;

    /** Absolute 0-based offset of the {@code TRNX-KEY} group: the KSDS key position, {@code KEYS(32 0)}. */
    public static final int TRNX_KEY_OFFSET = 0;

    /** Width of the {@code TRNX-KEY} group in bytes: the KSDS key length, {@code KEYS(32 0)}. */
    public static final int TRNX_KEY_LENGTH = 32;

    /** Absolute 0-based offset of {@code TRNX-CARD-NUM PIC X(16)}, the high-order half of the key. */
    public static final int TRNX_CARD_NUM_OFFSET = 0;

    /** Width of {@code TRNX-CARD-NUM PIC X(16)} in bytes. */
    public static final int TRNX_CARD_NUM_LENGTH = 16;

    /** Absolute 0-based offset of {@code TRNX-ID PIC X(16)}, the low-order half of the key. */
    public static final int TRNX_ID_OFFSET = 16;

    /** Width of {@code TRNX-ID PIC X(16)} in bytes. */
    public static final int TRNX_ID_LENGTH = 16;

    /**
     * Absolute 0-based offset of the {@code TRNX-REST} group, which is also the offset of
     * {@code FD-ACCT-DATA X(318)} in {@code CBSTM03B}'s {@code FD} for this file.
     */
    public static final int TRNX_REST_OFFSET = 32;

    /**
     * Width of the {@code TRNX-REST} group in bytes. This is the width of the
     * {@code WS-TRAN-REST PIC X(318)} table slot at {@code app/cbl/CBSTM03A.CBL:230} that the group
     * is moved into and out of wholesale.
     */
    public static final int TRNX_REST_LENGTH = 318;

    /** Absolute 0-based offset of {@code TRNX-TYPE-CD PIC X(02)}. */
    public static final int TRNX_TYPE_CD_OFFSET = 32;

    /** Width of {@code TRNX-TYPE-CD PIC X(02)} in bytes. */
    public static final int TRNX_TYPE_CD_LENGTH = 2;

    /** Absolute 0-based offset of {@code TRNX-CAT-CD PIC 9(04)}. */
    public static final int TRNX_CAT_CD_OFFSET = 34;

    /** Width of {@code TRNX-CAT-CD PIC 9(04)} in bytes, one zoned {@code DISPLAY} digit per byte. */
    public static final int TRNX_CAT_CD_LENGTH = 4;

    /** Absolute 0-based offset of {@code TRNX-SOURCE PIC X(10)}. */
    public static final int TRNX_SOURCE_OFFSET = 38;

    /** Width of {@code TRNX-SOURCE PIC X(10)} in bytes. */
    public static final int TRNX_SOURCE_LENGTH = 10;

    /**
     * Absolute 0-based offset of {@code TRNX-DESC PIC X(100)}. Read at
     * {@code app/cbl/CBSTM03A.CBL:677}, which moves it into a slot named {@code ST-TRANDT} - a
     * "transaction date" slot receiving a description. That oddity belongs to the statement writer
     * and is likewise preserved rather than corrected; this class simply supplies the field.
     */
    public static final int TRNX_DESC_OFFSET = 48;

    /** Width of {@code TRNX-DESC PIC X(100)} in bytes. */
    public static final int TRNX_DESC_LENGTH = 100;

    /** Absolute 0-based offset of {@code TRNX-AMT PIC S9(09)V99}, the record's only scaled field. */
    public static final int TRNX_AMT_OFFSET = 148;

    /**
     * {@code p} in {@code TRNX-AMT PIC S9(09)V99}: the digit positions left of the implied decimal
     * point.
     */
    public static final int TRNX_AMT_INTEGER_DIGITS = 9;

    /**
     * {@code s} in {@code TRNX-AMT PIC S9(09)V99}: the digit positions right of the implied decimal
     * point. Every scaled field in this system is scale 2, so this is
     * {@link CobolDecimal#MONETARY_SCALE} rather than a locally invented 2.
     */
    public static final int TRNX_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * Width of {@code TRNX-AMT PIC S9(09)V99} in bytes, computed as {@code p + s} rather than written
     * as a literal 11 so that a phantom sign byte cannot be reintroduced by hand. The sign is
     * overpunched into the trailing byte and occupies no byte of its own; reserving one for it would
     * make this record 351 bytes and the layout self-check below would reject it.
     */
    public static final int TRNX_AMT_LENGTH = TRNX_AMT_INTEGER_DIGITS + TRNX_AMT_SCALE;

    /** Absolute 0-based offset of {@code TRNX-MERCHANT-ID PIC 9(09)}. */
    public static final int TRNX_MERCHANT_ID_OFFSET = 159;

    /** Width of {@code TRNX-MERCHANT-ID PIC 9(09)} in bytes, one zoned {@code DISPLAY} digit per byte. */
    public static final int TRNX_MERCHANT_ID_LENGTH = 9;

    /** Absolute 0-based offset of {@code TRNX-MERCHANT-NAME PIC X(50)}. */
    public static final int TRNX_MERCHANT_NAME_OFFSET = 168;

    /** Width of {@code TRNX-MERCHANT-NAME PIC X(50)} in bytes. */
    public static final int TRNX_MERCHANT_NAME_LENGTH = 50;

    /** Absolute 0-based offset of {@code TRNX-MERCHANT-CITY PIC X(50)}. */
    public static final int TRNX_MERCHANT_CITY_OFFSET = 218;

    /** Width of {@code TRNX-MERCHANT-CITY PIC X(50)} in bytes. */
    public static final int TRNX_MERCHANT_CITY_LENGTH = 50;

    /** Absolute 0-based offset of {@code TRNX-MERCHANT-ZIP PIC X(10)}. */
    public static final int TRNX_MERCHANT_ZIP_OFFSET = 268;

    /** Width of {@code TRNX-MERCHANT-ZIP PIC X(10)} in bytes. */
    public static final int TRNX_MERCHANT_ZIP_LENGTH = 10;

    /** Absolute 0-based offset of {@code TRNX-ORIG-TS PIC X(26)}. */
    public static final int TRNX_ORIG_TS_OFFSET = 278;

    /** Width of {@code TRNX-ORIG-TS PIC X(26)} in bytes. */
    public static final int TRNX_ORIG_TS_LENGTH = 26;

    /** Absolute 0-based offset of {@code TRNX-PROC-TS PIC X(26)}. */
    public static final int TRNX_PROC_TS_OFFSET = 304;

    /**
     * Width of {@code TRNX-PROC-TS PIC X(26)} in bytes, as the copybook declares it. This is the
     * field's contract and is unaffected by the sort defect described next.
     */
    public static final int TRNX_PROC_TS_LENGTH = 26;

    /**
     * The number of {@code TRNX-PROC-TS} characters that {@code app/jcl/CREASTMT.JCL:54} actually
     * populates: {@code OUTREC FIELDS=(..., 279:279,50)} copies 50 of the 52 bytes spanned by
     * {@code TRAN-ORIG-TS} and {@code TRAN-PROC-TS} together, so the last two characters of the
     * process timestamp are dropped and arrive as spaces.
     *
     * <p><strong>Documentation only.</strong> This constant exists so the legacy off-by-two is
     * recorded in code rather than only in a comment, and so a parity fixture showing two trailing
     * blanks can be recognised as correct. It must never be used to narrow, widen, validate or
     * normalise the field: {@link #TRNX_PROC_TS_LENGTH} remains 26 and a {@code SORT}-derived record
     * must decode and re-encode byte-identically (binding practice B4).
     */
    public static final int TRNX_PROC_TS_SORT_DERIVED_LENGTH = 24;

    /**
     * Absolute 0-based offset of the trailing {@code FILLER PIC X(20)}.
     *
     * <p>{@code FILLER} is a first-class emitted span here, not an implicit gap. Omitting it would
     * make the record 330 bytes and shift every offset in every downstream dataset; the layout
     * self-check below turns that mistake into an immediate, precisely located failure instead.
     */
    public static final int FILLER_OFFSET = 330;

    /** Width of the trailing {@code FILLER PIC X(20)} in bytes. */
    public static final int FILLER_LENGTH = 20;

    // =================================================================================================
    // The descriptor table. One immutable FieldSpan per copybook item, in copybook declaration order.
    // Names are carried VERBATIM as COBOL spells them, hyphens and all, because the parity differ
    // compares field by field BY NAME - silently tidying a name would make a real difference invisible.
    // =================================================================================================

    /** {@code 10 TRNX-CARD-NUM PIC X(16)} - the high-order 16 bytes of the composite key. */
    public static final FieldSpan TRNX_CARD_NUM =
            FieldSpan.alphanumeric("TRNX-CARD-NUM", TRNX_CARD_NUM_OFFSET, TRNX_CARD_NUM_LENGTH);

    /** {@code 10 TRNX-ID PIC X(16)} - the low-order 16 bytes of the composite key. */
    public static final FieldSpan TRNX_ID =
            FieldSpan.alphanumeric("TRNX-ID", TRNX_ID_OFFSET, TRNX_ID_LENGTH);

    /** {@code 10 TRNX-TYPE-CD PIC X(02)}. */
    public static final FieldSpan TRNX_TYPE_CD =
            FieldSpan.alphanumeric("TRNX-TYPE-CD", TRNX_TYPE_CD_OFFSET, TRNX_TYPE_CD_LENGTH);

    /** {@code 10 TRNX-CAT-CD PIC 9(04)} - unsigned zoned {@code DISPLAY}, so zero-filled on the left. */
    public static final FieldSpan TRNX_CAT_CD =
            FieldSpan.unsignedNumeric("TRNX-CAT-CD", TRNX_CAT_CD_OFFSET, TRNX_CAT_CD_LENGTH);

    /** {@code 10 TRNX-SOURCE PIC X(10)}. */
    public static final FieldSpan TRNX_SOURCE =
            FieldSpan.alphanumeric("TRNX-SOURCE", TRNX_SOURCE_OFFSET, TRNX_SOURCE_LENGTH);

    /** {@code 10 TRNX-DESC PIC X(100)}. */
    public static final FieldSpan TRNX_DESC =
            FieldSpan.alphanumeric("TRNX-DESC", TRNX_DESC_OFFSET, TRNX_DESC_LENGTH);

    /**
     * {@code 10 TRNX-AMT PIC S9(09)V99} - the record's only scaled field.
     *
     * <p>Declared through {@link FieldSpan#signedScaled(String, int, int, int)} with the digit counts
     * rather than a byte width, so its 11 bytes are <em>computed</em> as {@code 9 + 2} and no sign byte
     * can be reserved by accident.
     */
    public static final FieldSpan TRNX_AMT = FieldSpan.signedScaled(
            "TRNX-AMT", TRNX_AMT_OFFSET, TRNX_AMT_INTEGER_DIGITS, TRNX_AMT_SCALE);

    /** {@code 10 TRNX-MERCHANT-ID PIC 9(09)} - unsigned zoned {@code DISPLAY}. */
    public static final FieldSpan TRNX_MERCHANT_ID = FieldSpan.unsignedNumeric(
            "TRNX-MERCHANT-ID", TRNX_MERCHANT_ID_OFFSET, TRNX_MERCHANT_ID_LENGTH);

    /** {@code 10 TRNX-MERCHANT-NAME PIC X(50)}. */
    public static final FieldSpan TRNX_MERCHANT_NAME = FieldSpan.alphanumeric(
            "TRNX-MERCHANT-NAME", TRNX_MERCHANT_NAME_OFFSET, TRNX_MERCHANT_NAME_LENGTH);

    /** {@code 10 TRNX-MERCHANT-CITY PIC X(50)}. */
    public static final FieldSpan TRNX_MERCHANT_CITY = FieldSpan.alphanumeric(
            "TRNX-MERCHANT-CITY", TRNX_MERCHANT_CITY_OFFSET, TRNX_MERCHANT_CITY_LENGTH);

    /** {@code 10 TRNX-MERCHANT-ZIP PIC X(10)}. */
    public static final FieldSpan TRNX_MERCHANT_ZIP = FieldSpan.alphanumeric(
            "TRNX-MERCHANT-ZIP", TRNX_MERCHANT_ZIP_OFFSET, TRNX_MERCHANT_ZIP_LENGTH);

    /** {@code 10 TRNX-ORIG-TS PIC X(26)} - fully populated by the sort. */
    public static final FieldSpan TRNX_ORIG_TS =
            FieldSpan.alphanumeric("TRNX-ORIG-TS", TRNX_ORIG_TS_OFFSET, TRNX_ORIG_TS_LENGTH);

    /**
     * {@code 10 TRNX-PROC-TS PIC X(26)} - declared at its full copybook width of 26 even though
     * {@code app/jcl/CREASTMT.JCL:54} populates only the first
     * {@link #TRNX_PROC_TS_SORT_DERIVED_LENGTH} characters. See the class documentation: the defect is
     * reproduced, not repaired.
     */
    public static final FieldSpan TRNX_PROC_TS =
            FieldSpan.alphanumeric("TRNX-PROC-TS", TRNX_PROC_TS_OFFSET, TRNX_PROC_TS_LENGTH);

    /**
     * {@code 10 FILLER PIC X(20)} - a real, positioned, space-emitting span. It declares no
     * {@code VALUE}, so it initialises to the charset's space byte, and it is what makes the layout
     * sum to 350 rather than 330.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * {@code 05 TRNX-KEY} - the 32-byte composite key as one contiguous span.
     *
     * <p>A COBOL group item is an alternative, group-level view of storage its elementary children
     * already account for, so in a flattened layout it is modelled as a {@code REDEFINES} overlay: it
     * addresses the same bytes as {@link #TRNX_CARD_NUM} and {@link #TRNX_ID}, contributes nothing to
     * the record's total width, and a write through either view is immediately visible through the
     * other - exactly as in COBOL.
     */
    public static final FieldSpan TRNX_KEY = FieldSpan.redefining(
            "TRNX-KEY", TRNX_KEY_OFFSET, TRNX_KEY_LENGTH, PictureKind.ALPHANUMERIC);

    /**
     * {@code 05 TRNX-REST} - the 318-byte remainder as one contiguous span, and the group that
     * {@code app/cbl/CBSTM03A.CBL} moves wholesale in both directions against its
     * {@code WS-TRAN-REST PIC X(318)} table slot: out at {@code :829} and back in at {@code :426-427}.
     *
     * <p>Like {@link #TRNX_KEY} this is a group-level overlay over the twelve elementary spans from
     * {@link #TRNX_TYPE_CD} through {@link #FILLER}. Because it shares their storage rather than
     * copying it, {@link #readTrnxAmt()} called straight after
     * {@link #writeTrnxRest(String)} observes the newly written amount.
     */
    public static final FieldSpan TRNX_REST = FieldSpan.redefining(
            "TRNX-REST", TRNX_REST_OFFSET, TRNX_REST_LENGTH, PictureKind.ALPHANUMERIC);

    /**
     * The complete layout: the fourteen elementary spans in copybook order, followed by the two
     * group-level overlays.
     *
     * <p>Declaration order matters. {@link RecordLayout} requires storage spans to be contiguous from
     * offset 0 and to sum to <strong>exactly</strong> {@link #RECORD_LENGTH}, and requires every
     * overlay to fall inside storage already declared ahead of it - which is why the two groups come
     * last. The check runs during class initialisation, so this layout either exists and is provably
     * consistent with the copybook or the class fails to initialise and names the offending
     * descriptor. That single arithmetic check is what catches a dropped {@code FILLER} (350 would
     * become 330) and a phantom sign byte on {@code TRNX-AMT} (350 would become 351) at the earliest
     * possible moment instead of as corrupted bytes much later.
     */
    private static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            TRNX_CARD_NUM,
            TRNX_ID,
            TRNX_TYPE_CD,
            TRNX_CAT_CD,
            TRNX_SOURCE,
            TRNX_DESC,
            TRNX_AMT,
            TRNX_MERCHANT_ID,
            TRNX_MERCHANT_NAME,
            TRNX_MERCHANT_CITY,
            TRNX_MERCHANT_ZIP,
            TRNX_ORIG_TS,
            TRNX_PROC_TS,
            FILLER,
            TRNX_KEY,
            TRNX_REST);

    /**
     * Every descriptor of this record, elementary spans first in copybook order and then the two group
     * overlays. Immutable, and the backing list of {@link #LAYOUT} is itself an immutable copy, so a
     * caller cannot reach through this to alter the layout.
     */
    private static final List<FieldSpan> FIELD_SPANS = List.copyOf(LAYOUT.spans());

    // =================================================================================================
    // Instance state. Exactly two fields, both final and both per instance: the byte area that IS the
    // record, and the codec that gives its bytes their PICTURE meaning. No decoded field value is held
    // anywhere, which is what makes every accessor read through to the bytes and makes a stale read
    // structurally impossible rather than merely unlikely.
    // =================================================================================================

    /**
     * The one authoritative store. Never handed out, and never shadowed by a cached decode of any part
     * of it.
     */
    private final FixedWidthRecord area;

    /**
     * The PICTURE layer over {@link #area}, constructed with the same charset. Held per instance rather
     * than statically precisely because it carries a charset: one shared static codec would either
     * assume a code page or force one on every record.
     */
    private final FixedWidthCodec codec;

    /**
     * Takes ownership of an already-validated record area and derives the codec from its charset.
     *
     * <p>Private because every public entry point guarantees the area is exactly
     * {@link #RECORD_LENGTH} bytes wide before it gets here - {@link #newRecord(Charset)} allocates it
     * from {@link #layout()}, {@link #decode(byte[], Charset)} moves the source into a correctly sized
     * area, and {@link #wrap(byte[], Charset)} rejects any other width. Keeping the invariant on the
     * factories rather than re-checking it here means there is exactly one place per entry point where a
     * width can be established, and no way to construct a record of the wrong width at all.
     *
     * @param area a record area of exactly {@link #RECORD_LENGTH} bytes, henceforth owned solely by this
     *             instance and never shared with the caller
     */
    private TrnxRecord(FixedWidthRecord area) {
        this.area = area;
        this.codec = new FixedWidthCodec(area.charset());
    }

    // =================================================================================================
    // Factories. Two decoding entry points, deliberately distinguished: decode() reproduces COBOL's
    // alphanumeric group MOVE and adjusts the width, wrap() insists the width is already right.
    // =================================================================================================

    /**
     * Allocates a new, initialised record: every {@code PIC X} span and the trailing {@code FILLER}
     * space-filled, and the two numeric {@code DISPLAY} spans zero-filled, following the COBOL
     * {@code INITIALIZE} convention.
     *
     * <p>The result is a valid 350-byte record image from the outset, so
     * {@link #readTrnxCatCd()} and {@link #readTrnxAmt()} are readable before any field is written -
     * they return {@code 0} and {@code 0.00} respectively rather than failing on blank digits.
     *
     * @param charset the code page of the record's data, stated explicitly and never defaulted
     * @return a newly allocated, initialised record
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode the space and zero characters
     *                                  to exactly one byte each, since a fixed-width area cannot be
     *                                  addressed by absolute offset under a multi-byte code page
     */
    public static TrnxRecord newRecord(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to allocate a TRNX-RECORD area; name "
                + "the code page explicitly - IBM037 for EBCDIC data or US-ASCII for the text "
                + "fixtures - and never rely on the platform default");
        return new TrnxRecord(FixedWidthRecord.forLayout(LAYOUT, charset));
    }

    /**
     * Decodes a record by performing COBOL's alphanumeric group {@code MOVE} into a 350-byte receiver,
     * which means an <strong>oversized</strong> source is accepted and its <strong>leading</strong>
     * 350 bytes are taken.
     *
     * <p>This reproduces {@code MOVE WS-M03B-FLDT TO TRNX-RECORD} at
     * {@code app/cbl/CBSTM03A.CBL:756} and {@code :839}. The sending field there is the
     * {@code PIC X(1000)} response span declared at {@code app/cbl/CBSTM03B.CBL:112}:
     * {@code CBSTM03B} hands back raw, space-padded 1000-byte spans and deliberately never decodes,
     * so the truncation belongs here. COBOL fills a {@code PIC X} receiver from its leftmost position
     * and discards whatever does not fit, so the surviving bytes are the leading ones - the same rule
     * {@link FixedWidthCodec#movePicX(String, int)} implements for a single field, applied at
     * group level and at byte level so no charset round trip can perturb a byte on the way through.
     *
     * <p>A source shorter than 350 bytes is the mirror image of the same rule and is likewise
     * accepted: its bytes land at the left and the remainder is padded with the charset's space byte,
     * exactly as COBOL pads a short alphanumeric sender. Truncation is on the <em>right</em> and
     * padding is on the <em>right</em>; both directions are the {@code PIC X} directions and neither
     * is the {@code PIC 9} direction.
     *
     * <p>Use {@link #wrap(byte[], Charset)} instead wherever the width ought to be exactly 350 and a
     * mismatch is a defect worth failing on.
     *
     * @param source  the sending bytes; may be longer than, shorter than or exactly
     *                {@link #RECORD_LENGTH}, and is never modified
     * @param charset the code page of the record's data, stated explicitly
     * @return a record over exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code source} is empty, or {@code charset} is not a
     *                                  single-byte code page for the space and zero characters
     */
    public static TrnxRecord decode(byte[] source, Charset charset) {
        Objects.requireNonNull(source, "Sending bytes are required to decode a TRNX-RECORD; call "
                + "newRecord(Charset) to allocate an initialised record area instead");
        Objects.requireNonNull(charset, "A charset is required to decode a TRNX-RECORD; name the code "
                + "page explicitly and never rely on the platform default");
        if (source.length == 0) {
            throw new IllegalArgumentException("Cannot decode a TRNX-RECORD from an empty span. A "
                    + "sending field has at least one byte; to obtain a blank record call "
                    + "newRecord(Charset)");
        }

        // Space-filled to its full declared width by the FixedWidthRecord constructor, using the pad
        // byte derived from the supplied charset - 0x40 under IBM037, 0x20 under US-ASCII - so a short
        // sender is padded on the right correctly under either code page rather than with a hard-coded
        // ASCII space.
        FixedWidthRecord target = new FixedWidthRecord(RECORD_LENGTH, charset);
        byte[] moved = source.length > RECORD_LENGTH
                // The PIC X receiver keeps the LEADING bytes and discards the overflow. Stated as an
                // explicit, commented truncation rather than an incidental substring, because the
                // opposite direction is what COBOL would do for a PIC 9 receiver and a silent choice
                // here would be indistinguishable from correct behaviour.
                ? Arrays.copyOf(source, RECORD_LENGTH)
                : source;
        target.writeBytes(0, moved);
        return new TrnxRecord(target);
    }

    /**
     * Wraps stored bytes whose width must already be exactly {@link #RECORD_LENGTH}, taking a
     * defensive copy.
     *
     * <p>This is the strict counterpart to {@link #decode(byte[], Charset)} and the right choice for a
     * repository read or a parity fixture, where a row of the wrong width means the caller's idea of
     * the layout and the dataset's have diverged and should fail loudly. Widening a short row is then a
     * conscious act performed by the caller, through
     * {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)}.
     *
     * @param record  the stored bytes, exactly {@link #RECORD_LENGTH} of them; defensively copied
     * @param charset the code page of the record's data, stated explicitly
     * @return a record over a copy of {@code record}
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} differs from {@link #RECORD_LENGTH}, or
     *                                  {@code charset} is not a single-byte code page for the space and
     *                                  zero characters
     */
    public static TrnxRecord wrap(byte[] record, Charset charset) {
        Objects.requireNonNull(record, "Stored bytes are required to wrap a TRNX-RECORD");
        Objects.requireNonNull(charset, "A charset is required to wrap a TRNX-RECORD; name the code "
                + "page explicitly and never rely on the platform default");
        return new TrnxRecord(new FixedWidthCodec(charset).wrap(record, LAYOUT));
    }

    // =================================================================================================
    // Layout and geometry accessors.
    // =================================================================================================

    /**
     * The self-checked layout of this record: the fourteen elementary spans followed by the
     * {@code TRNX-KEY} and {@code TRNX-REST} group overlays.
     *
     * <p>{@link RecordLayout} is an immutable record holding an immutable span list, so this is safe to
     * hand out. It is what the parity harness passes to
     * {@link FixedWidthCodec#deserialise(RecordLayout, byte[])} to fingerprint a record field by field.
     *
     * @return the record's layout, never {@code null}
     */
    public static RecordLayout layout() {
        return LAYOUT;
    }

    /**
     * Every descriptor of this record, in declaration order: the fourteen elementary spans that sum to
     * {@link #RECORD_LENGTH}, then the two group overlays that contribute nothing to that sum.
     *
     * @return an immutable list of descriptors
     */
    public static List<FieldSpan> fieldSpans() {
        return FIELD_SPANS;
    }

    /**
     * The code page this record's bytes are held in, fixed for the instance's lifetime.
     *
     * @return the charset supplied when this record was created
     */
    public Charset charset() {
        return area.charset();
    }

    /**
     * The record's width in bytes, always {@link #RECORD_LENGTH}.
     *
     * @return {@code 350}
     */
    public int recordLength() {
        return area.recordLength();
    }

    // =================================================================================================
    // Whole-record serialisation.
    // =================================================================================================

    /**
     * The complete 350-byte record image: the serialised form written to a dataset and the form the
     * parity harness fingerprints.
     *
     * <p>A fresh array is returned, never the internal one, so mutating the result cannot change the
     * record behind its owner's back. The trailing {@code FILLER X(20)} is present and space-filled,
     * which is why the returned length is 350 and not 330.
     *
     * @return a copy of all {@link #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        return area.toByteArray();
    }

    /**
     * The complete 350-byte record image, with the destination's code page stated explicitly at the
     * write site.
     *
     * <p>This is a <strong>guard</strong>, not a converter. It returns the same bytes as
     * {@link #encode()} when {@code charset} is the code page this record was built with, and throws
     * otherwise. Deliberately it does <em>not</em> transcode: silently re-encoding a record on its way
     * to a dataset would be a behaviour change, and one whose failures surface as unreadable data
     * rather than as an exception. Naming the expected code page here instead turns "an
     * {@code US-ASCII} record was written to an {@code IBM037} dataset" from an undetectable data
     * defect into an immediate, precisely worded failure.
     *
     * @param charset the code page the caller expects these bytes to be in
     * @return a copy of all {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} differs from {@link #charset()}
     */
    public byte[] encode(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to serialise a TRNX-RECORD; name the "
                + "destination code page explicitly and never rely on the platform default");
        if (!charset.equals(area.charset())) {
            throw new IllegalArgumentException("This TRNX-RECORD holds its bytes in "
                    + area.charset().name() + " but serialisation was requested in " + charset.name()
                    + ". A fixed-width record is not transcoded on the way out, because doing so "
                    + "silently would be a behaviour change; decode the record under "
                    + charset.name() + " in the first place, or call encode() to accept "
                    + area.charset().name());
        }
        return area.toByteArray();
    }

    /**
     * The complete 350-character record image as text, decoded under this record's charset and
     * <strong>untrimmed</strong>.
     *
     * <p>Every space is significant: the trailing {@code FILLER} and the right-hand padding of each
     * {@code PIC X} field are part of the record's value, and the parity differ compares them.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String recordImage() {
        return area.readString(0, RECORD_LENGTH);
    }

    // =================================================================================================
    // Group-level access. TRNX-KEY and TRNX-REST are contiguous spans over the SAME storage as their
    // elementary children, so a group write is immediately visible through every typed accessor over
    // the bytes it covered. That is the property app/cbl/CBSTM03A.CBL:426-429 depends on, and it holds
    // here because nothing is cached.
    // =================================================================================================

    /**
     * The 32-byte {@code TRNX-KEY} group as one contiguous, untrimmed image: the KSDS key, card number
     * followed by transaction id.
     *
     * @return exactly {@link #TRNX_KEY_LENGTH} characters
     */
    public String readTrnxKey() {
        return codec.readPicX(area, TRNX_KEY);
    }

    /**
     * Writes the whole 32-byte {@code TRNX-KEY} group, applying the {@code PIC X} move rule: padded on
     * the right when the value is shorter and truncated on the right when it is longer.
     *
     * <p>Writing the key wholesale is immediately visible through {@link #readTrnxCardNum()} and
     * {@link #readTrnxId()}, because all three views address the same bytes.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxKey(String value) {
        codec.writePicX(area, TRNX_KEY, value);
    }

    /**
     * The 32-byte {@code TRNX-KEY} group as raw bytes, defensively copied.
     *
     * @return a copy of {@link #TRNX_KEY_LENGTH} bytes at offset {@link #TRNX_KEY_OFFSET}
     */
    public byte[] readTrnxKeyBytes() {
        return area.readSpanBytes(TRNX_KEY);
    }

    /**
     * Replaces the 32-byte {@code TRNX-KEY} group from raw bytes, which must be exactly
     * {@link #TRNX_KEY_LENGTH} long.
     *
     * <p>Exact width is required on the byte path, unlike {@link #writeTrnxKey(String)}: a byte-level
     * caller states a span width, and a mismatch means its idea of the layout has diverged from this
     * one rather than that a value needs padding.
     *
     * @param source the replacement bytes, exactly {@link #TRNX_KEY_LENGTH} of them
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source.length} differs from {@link #TRNX_KEY_LENGTH}
     */
    public void writeTrnxKeyBytes(byte[] source) {
        area.writeSpanBytes(TRNX_KEY, source);
    }

    /**
     * The 318-byte {@code TRNX-REST} group as one contiguous, untrimmed image.
     *
     * <p>This reproduces {@code MOVE TRNX-REST TO WS-TRAN-REST (CR-CNT, TR-CNT)} at
     * {@code app/cbl/CBSTM03A.CBL:829}, which lifts the whole group into a
     * {@code WS-TRAN-REST PIC X(318)} slot of the statement job's transaction table.
     *
     * @return exactly {@link #TRNX_REST_LENGTH} characters
     */
    public String readTrnxRest() {
        return codec.readPicX(area, TRNX_REST);
    }

    /**
     * Writes the whole 318-byte {@code TRNX-REST} group, applying the {@code PIC X} move rule: padded
     * on the right when the value is shorter and truncated on the right when it is longer.
     *
     * <p>This reproduces {@code MOVE WS-TRAN-REST (CR-JMP, TR-JMP) TO TRNX-REST} at
     * {@code app/cbl/CBSTM03A.CBL:426-427}, and it is the write that makes read-through indispensable:
     * the very next COBOL statements read {@code TRNX-ID}, {@code TRNX-DESC} and {@code TRNX-AMT} back
     * out of the storage this call just overwrote ({@code :428} into {@code :676-678}, then
     * {@code :429}). Because no decoded value is cached anywhere in this class,
     * {@link #readTrnxAmt()} and {@link #readTrnxDesc()} called immediately after this method observe
     * the new bytes.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxRest(String value) {
        codec.writePicX(area, TRNX_REST, value);
    }

    /**
     * The 318-byte {@code TRNX-REST} group as raw bytes, defensively copied. This is also the span
     * {@code app/cbl/CBSTM03B.CBL:63} calls {@code FD-ACCT-DATA PIC X(318)}.
     *
     * @return a copy of {@link #TRNX_REST_LENGTH} bytes at offset {@link #TRNX_REST_OFFSET}
     */
    public byte[] readTrnxRestBytes() {
        return area.readSpanBytes(TRNX_REST);
    }

    /**
     * Replaces the 318-byte {@code TRNX-REST} group from raw bytes, which must be exactly
     * {@link #TRNX_REST_LENGTH} long.
     *
     * @param source the replacement bytes, exactly {@link #TRNX_REST_LENGTH} of them
     * @throws NullPointerException     if {@code source} is {@code null}
     * @throws IllegalArgumentException if {@code source.length} differs from {@link #TRNX_REST_LENGTH}
     */
    public void writeTrnxRestBytes(byte[] source) {
        area.writeSpanBytes(TRNX_REST, source);
    }

    // =================================================================================================
    // Raw span access, alongside the typed accessors. A caller that needs to see a field's bytes exactly
    // as stored - to fingerprint them, to diff them, or to inspect a span whose contents will not decode
    // - reaches them here without any PICTURE interpretation in the way.
    // =================================================================================================

    /**
     * Any declared span of this record as an untrimmed image, with no PICTURE interpretation applied.
     *
     * <p>Useful where a numeric span cannot be decoded - a span of blanks, for instance - and the raw
     * bytes are what the caller actually needs to see.
     *
     * @param field one of this record's descriptors, elementary or group
     * @return exactly {@code field.length()} characters
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a descriptor of this record's layout
     */
    public String readRawImage(FieldSpan field) {
        return area.readSpan(requireOwnSpan(field));
    }

    /**
     * Any declared span of this record as raw bytes, defensively copied.
     *
     * @param field one of this record's descriptors, elementary or group
     * @return a copy of the span's bytes
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a descriptor of this record's layout
     */
    public byte[] readRawBytes(FieldSpan field) {
        return area.readSpanBytes(requireOwnSpan(field));
    }

    /**
     * Rejects a descriptor that does not belong to this record's layout, so a span borrowed from another
     * copybook cannot silently read a plausible-looking value out of the wrong offset - which is exactly
     * the class of defect that is hardest to trace back from a parity diff.
     *
     * @param field the descriptor to validate against {@link #fieldSpans()}
     * @return {@code field} unchanged, so this can be applied inline at the point of use
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not one of this record's own descriptors
     */
    private static FieldSpan requireOwnSpan(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a span of TRNX-RECORD");
        if (!FIELD_SPANS.contains(field)) {
            throw new IllegalArgumentException("Descriptor " + field.describe() + " is not part of the "
                    + "TRNX-RECORD layout of app/cpy/COSTM01.CPY. Use one of this class's own span "
                    + "constants; reading a foreign descriptor would address the wrong offset and "
                    + "return a plausible but wrong value");
        }
        return field;
    }

    // =================================================================================================
    // Typed field access. EVERY method here decodes from the byte area at the moment it is called - that
    // is what "read through" means and why each is named readXxx rather than getXxx. No value is cached,
    // so none can go stale after a group write.
    //
    // No PIC X read trims. A COBOL alphanumeric field is space-padded to its full declared width and
    // that padding is part of the field's value: the parity differ compares it byte for byte, so
    // trimming here would discard bytes it is meant to compare.
    // =================================================================================================

    /**
     * {@code TRNX-CARD-NUM PIC X(16)}, untrimmed - the high-order half of the composite key.
     *
     * <p>Returned as a {@link String} and never parsed as a number: a leading zero is significant in a
     * card number and numeric parsing would silently destroy it. Read at
     * {@code app/cbl/CBSTM03A.CBL:757}, {@code :819} - the control break - and {@code :827}.
     *
     * @return exactly {@link #TRNX_CARD_NUM_LENGTH} characters, space padding included
     */
    public String readTrnxCardNum() {
        return codec.readPicX(area, TRNX_CARD_NUM);
    }

    /**
     * Writes {@code TRNX-CARD-NUM PIC X(16)}, padded on the right when shorter and truncated on the
     * right when longer. Reproduces {@code app/cbl/CBSTM03A.CBL:421}.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxCardNum(String value) {
        codec.writePicX(area, TRNX_CARD_NUM, value);
    }

    /**
     * {@code TRNX-ID PIC X(16)}, untrimmed - the low-order half of the composite key. Read at
     * {@code app/cbl/CBSTM03A.CBL:676} and {@code :828}.
     *
     * @return exactly {@link #TRNX_ID_LENGTH} characters, space padding included
     */
    public String readTrnxId() {
        return codec.readPicX(area, TRNX_ID);
    }

    /**
     * Writes {@code TRNX-ID PIC X(16)}, padded or truncated on the right. Reproduces
     * {@code app/cbl/CBSTM03A.CBL:424-425}.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxId(String value) {
        codec.writePicX(area, TRNX_ID, value);
    }

    /**
     * {@code TRNX-TYPE-CD PIC X(02)}, untrimmed. Alphanumeric in the copybook, not numeric, so it is a
     * {@link String}.
     *
     * @return exactly {@link #TRNX_TYPE_CD_LENGTH} characters
     */
    public String readTrnxTypeCd() {
        return codec.readPicX(area, TRNX_TYPE_CD);
    }

    /**
     * Writes {@code TRNX-TYPE-CD PIC X(02)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxTypeCd(String value) {
        codec.writePicX(area, TRNX_TYPE_CD, value);
    }

    /**
     * {@code TRNX-CAT-CD PIC 9(04)} as an {@code int}.
     *
     * <p>Four unsigned zoned {@code DISPLAY} digits, so an {@code int} holds every representable value
     * with room to spare, and there is no scale and no sign to preserve. A span holding anything other
     * than digits is reported rather than silently coerced to zero, because a blank or misaligned
     * numeric span is a genuine data or offset defect and a plausible zero would hide it - use
     * {@link #readRawImage(FieldSpan)} with {@link #TRNX_CAT_CD} to inspect such a span.
     *
     * @return the category code the four digits denote
     * @throws IllegalArgumentException if the span does not hold four digits
     */
    public int readTrnxCatCd() {
        return codec.readPic9AsInt(area, TRNX_CAT_CD);
    }

    /**
     * Writes {@code TRNX-CAT-CD PIC 9(04)}, zero-filled on the <strong>left</strong> to four digits -
     * so {@code 7} is stored as {@code 0007}. This is the mirror image of the {@code PIC X} rule, and
     * an over-long value keeps its <strong>low-order</strong> digits because a numeric receiver aligns
     * on its implied decimal point.
     *
     * @param value the category code; must not be negative, since {@code PIC 9} has no sign position
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void writeTrnxCatCd(int value) {
        codec.writePic9(area, TRNX_CAT_CD, value);
    }

    /**
     * {@code TRNX-SOURCE PIC X(10)}, untrimmed.
     *
     * @return exactly {@link #TRNX_SOURCE_LENGTH} characters
     */
    public String readTrnxSource() {
        return codec.readPicX(area, TRNX_SOURCE);
    }

    /**
     * Writes {@code TRNX-SOURCE PIC X(10)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxSource(String value) {
        codec.writePicX(area, TRNX_SOURCE, value);
    }

    /**
     * {@code TRNX-DESC PIC X(100)}, untrimmed.
     *
     * <p>Read at {@code app/cbl/CBSTM03A.CBL:677}, immediately after the wholesale {@code TRNX-REST}
     * write at {@code :426-427} - one of the two reads that make read-through mandatory. The padding is
     * returned because the COBOL move that consumes it is itself a fixed-width move.
     *
     * @return exactly {@link #TRNX_DESC_LENGTH} characters, space padding included
     */
    public String readTrnxDesc() {
        return codec.readPicX(area, TRNX_DESC);
    }

    /**
     * Writes {@code TRNX-DESC PIC X(100)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxDesc(String value) {
        codec.writePicX(area, TRNX_DESC, value);
    }

    /**
     * {@code TRNX-AMT PIC S9(09)V99} as a {@link BigDecimal} of scale exactly
     * {@link #TRNX_AMT_SCALE}.
     *
     * <p>Eleven zoned {@code DISPLAY} bytes with the sign overpunched into the trailing byte. Read at
     * {@code app/cbl/CBSTM03A.CBL:678} and, decisively, at {@code :429} -
     * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} - which runs immediately after the wholesale
     * {@code TRNX-REST} write at {@code :426-427}. Because this method decodes the bytes on every call,
     * that sequence accumulates the newly written amount, as COBOL does. A cached decode would
     * accumulate the previous record's amount and quietly produce the wrong statement total.
     *
     * <p>Compare the result with {@link BigDecimal#compareTo(BigDecimal)}, never with
     * {@code equals}: {@code equals} is scale sensitive and would report {@code 0.00} as unequal to
     * {@code 0.0}.
     *
     * @return the amount at scale exactly {@link #TRNX_AMT_SCALE}, never {@code null}
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image
     */
    public BigDecimal readTrnxAmt() {
        return codec.readMonetary(area, TRNX_AMT);
    }

    /**
     * Writes {@code TRNX-AMT PIC S9(09)V99}.
     *
     * <p>Excess fractional digits are <strong>truncated toward zero</strong>, never rounded, because
     * the keyword {@code ROUNDED} appears zero times in all 28 COBOL programs: the store routes
     * through {@link CobolDecimal}, whose only rounding mode is
     * {@link CobolDecimal#COBOL_ROUNDING}. So {@code 1.239} stores as {@code 1.23} and {@code -1.239}
     * as {@code -1.23}. Integer digits beyond {@link #TRNX_AMT_INTEGER_DIGITS} are likewise discarded,
     * the field keeping its low-order digits and the value's sign, because {@code ON SIZE ERROR}
     * appears nowhere either - so an oversized value is stored, not rejected.
     *
     * <p>The sign is overpunched into the trailing byte and consumes no byte of its own, so the written
     * span is {@link #TRNX_AMT_LENGTH} bytes wide whether the amount is positive, negative or zero.
     *
     * @param value the amount to store; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxAmt(BigDecimal value) {
        codec.writeMonetary(area, TRNX_AMT, value);
    }

    /**
     * The raw eleven-character zoned {@code DISPLAY} image of {@code TRNX-AMT}, exactly as stored, with
     * the sign overpunch still in the trailing character and no decimal point.
     *
     * <p>This is the byte-level view the parity differ needs: {@code 504.77} stored in this field is
     * the image {@code 0000005047G}, and {@code -919.00} is <code>0000009190&#125;</code>. Decoding is
     * {@link #readTrnxAmt()}; this method interprets nothing.
     *
     * @return exactly {@link #TRNX_AMT_LENGTH} characters
     */
    public String readTrnxAmtImage() {
        return area.readSpan(TRNX_AMT);
    }

    /**
     * {@code TRNX-MERCHANT-ID PIC 9(09)} as an {@code int}.
     *
     * <p>Nine unsigned zoned {@code DISPLAY} digits, so {@code int} rather than {@code long}: AAP rule
     * R4 assigns {@code int} to a scale-free {@code PIC 9(n)} up to nine digits. The field's full
     * nine-digit range is used, and an {@code int} covers all of it with room to spare - what a
     * {@code long} would add is not headroom the field can use but eleven digits of Java state the field
     * can never hold.
     *
     * @return the merchant identifier the nine digits denote
     * @throws IllegalArgumentException if the span does not hold nine digits, or denotes a value outside
     *                                  the {@code int} range - which nine digits cannot
     */
    public int readTrnxMerchantId() {
        return codec.readPic9AsInt(area, TRNX_MERCHANT_ID);
    }

    /**
     * Writes {@code TRNX-MERCHANT-ID PIC 9(09)}, zero-filled on the left to nine digits.
     *
     * @param value the merchant identifier; must not be negative, since {@code PIC 9} has no sign
     *              position
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void writeTrnxMerchantId(int value) {
        codec.writePic9(area, TRNX_MERCHANT_ID, value);
    }

    /**
     * {@code TRNX-MERCHANT-NAME PIC X(50)}, untrimmed.
     *
     * @return exactly {@link #TRNX_MERCHANT_NAME_LENGTH} characters
     */
    public String readTrnxMerchantName() {
        return codec.readPicX(area, TRNX_MERCHANT_NAME);
    }

    /**
     * Writes {@code TRNX-MERCHANT-NAME PIC X(50)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxMerchantName(String value) {
        codec.writePicX(area, TRNX_MERCHANT_NAME, value);
    }

    /**
     * {@code TRNX-MERCHANT-CITY PIC X(50)}, untrimmed.
     *
     * @return exactly {@link #TRNX_MERCHANT_CITY_LENGTH} characters
     */
    public String readTrnxMerchantCity() {
        return codec.readPicX(area, TRNX_MERCHANT_CITY);
    }

    /**
     * Writes {@code TRNX-MERCHANT-CITY PIC X(50)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxMerchantCity(String value) {
        codec.writePicX(area, TRNX_MERCHANT_CITY, value);
    }

    /**
     * {@code TRNX-MERCHANT-ZIP PIC X(10)}, untrimmed. Alphanumeric in the copybook, so a ZIP keeps any
     * leading zero and is never parsed as a number.
     *
     * @return exactly {@link #TRNX_MERCHANT_ZIP_LENGTH} characters
     */
    public String readTrnxMerchantZip() {
        return codec.readPicX(area, TRNX_MERCHANT_ZIP);
    }

    /**
     * Writes {@code TRNX-MERCHANT-ZIP PIC X(10)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxMerchantZip(String value) {
        codec.writePicX(area, TRNX_MERCHANT_ZIP, value);
    }

    /**
     * {@code TRNX-ORIG-TS PIC X(26)}, untrimmed. Fully populated by
     * {@code app/jcl/CREASTMT.JCL:54}, unlike {@link #readTrnxProcTs()}.
     *
     * @return exactly {@link #TRNX_ORIG_TS_LENGTH} characters
     */
    public String readTrnxOrigTs() {
        return codec.readPicX(area, TRNX_ORIG_TS);
    }

    /**
     * Writes {@code TRNX-ORIG-TS PIC X(26)}, padded or truncated on the right.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxOrigTs(String value) {
        codec.writePicX(area, TRNX_ORIG_TS, value);
    }

    /**
     * {@code TRNX-PROC-TS PIC X(26)}, untrimmed - and the field the legacy sort under-fills.
     *
     * <p>{@code app/jcl/CREASTMT.JCL:54}'s {@code OUTREC FIELDS=(..., 279:279,50)} copies 50 of the 52
     * bytes spanned by the original and process timestamps together, so a {@code SORT}-derived record
     * carries {@link #TRNX_PROC_TS_SORT_DERIVED_LENGTH} characters here followed by two spaces. Those
     * two trailing blanks are <strong>correct input</strong> and are returned as they are: the field is
     * not narrowed, not validated against 26 significant characters, and not repaired. Preserving the
     * defect is the point of a like-for-like migration.
     *
     * @return exactly {@link #TRNX_PROC_TS_LENGTH} characters, the sort's trailing blanks included
     */
    public String readTrnxProcTs() {
        return codec.readPicX(area, TRNX_PROC_TS);
    }

    /**
     * Writes {@code TRNX-PROC-TS PIC X(26)}, padded or truncated on the right. A 24-character value is
     * padded to 26, which is exactly what the legacy sort produces.
     *
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void writeTrnxProcTs(String value) {
        codec.writePicX(area, TRNX_PROC_TS, value);
    }

    /**
     * The trailing {@code FILLER PIC X(20)}, untrimmed.
     *
     * <p>Exposed for parity fingerprinting and width verification, and read-only by design:
     * {@code FILLER} is unnamed and non-referable in COBOL, so no program can move a value into it. It
     * is space-filled on allocation and preserved verbatim on decode, and a {@code SORT}-derived record
     * has it entirely blank because {@code CREASTMT.JCL:54} leaves output bytes 329-350 untouched.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String readFiller() {
        return area.readSpan(FILLER);
    }

    /**
     * A short, non-throwing description naming the record's key, its raw amount image and its code
     * page. Deliberately built from raw spans rather than typed accessors so that it cannot fail on a
     * record whose numeric spans are blank - a diagnostic that throws while being logged is worse than
     * no diagnostic.
     *
     * <p>{@code TRNX-CARD-NUM} is masked per {@link SensitiveDiagnostics}: it is a primary account
     * number, and this record is written to the customer statement files, so anything that logged it
     * disclosed a payment credential. The transaction id and the raw amount image stay legible - the id
     * is a correlation key rather than personal data, and the amount image is the whole point of a
     * numeric parity diagnostic, including the negative-zero overpunch that only the raw image shows.
     *
     * @return for example
     *         {@code TrnxRecord[TRNX-CARD-NUM=************1111, TRNX-ID=0000000000000001,
     *         TRNX-AMT=0000005047G, charset=US-ASCII]}
     */
    @Override
    public String toString() {
        // The card number is masked to its last four characters and the amount is withheld with its
        // width: a statement row is one cardholder's spending, so the transaction identifier and the
        // code page are what identify the record here and the money is not something a log line needs.
        // readTrnxAmtImage() still returns every digit to a caller that asks for it by name, so a
        // byte-level parity comparison is unaffected.
        return "TrnxRecord[TRNX-CARD-NUM="
                + SensitiveDiagnostics.maskPan(area.readSpan(TRNX_CARD_NUM))
                + ", TRNX-ID=" + DiagnosticText.singleLine(area.readSpan(TRNX_ID))
                + ", TRNX-AMT=" + DiagnosticText.omitted(area.readSpan(TRNX_AMT))
                + ", charset=" + area.charset().name()
                + "]";
    }
}
