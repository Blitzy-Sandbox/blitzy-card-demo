package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The transaction record, {@code 01 TRAN-RECORD}, transcribed from
 * {@code app/cpy/CVTRA05Y.cpy} whose own header comment declares {@code RECLN = 350}.
 *
 * <p>This is the single Java type for that copybook. It is the most widely shared record in the
 * migration's transaction surface: nine COBOL programs {@code COPY CVTRA05Y}, spanning both the
 * transaction and the billing packages. Every one of them addresses the same 350 bytes at the same
 * absolute offsets, so a single wrong offset here is a parity failure in nine places at once.
 *
 * <h2>Consuming programs</h2>
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl} - interest calculator; builds a record in
 *       {@code 1300-B-WRITE-TX} and writes it to {@code TRANSACT}</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl} - daily transaction poster (no JCL invoker; an orphan that
 *       nonetheless migrates as a runnable job)</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} - the {@code POSTTRAN} poster and validator</li>
 *   <li>{@code app/cbl/CBTRN03C.cbl} - transaction detail report</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} - bill payment (billing package)</li>
 *   <li>{@code app/cbl/CORPT00C.cbl} - report request</li>
 *   <li>{@code app/cbl/COTRN00C.cbl} - transaction list</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} - transaction view</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} - transaction add</li>
 * </ul>
 *
 * <h2>Byte layout - 1-based COBOL position to 0-based Java offset</h2>
 * <pre>
 * #   COBOL item             PICTURE       1-based    0-based  Len  Java
 * --  ---------------------  ------------  ---------  -------  ---  ---------------------
 *  1  TRAN-ID                X(16)           1- 16         0   16  String  (KSDS key)
 *  2  TRAN-TYPE-CD           X(02)          17- 18        16    2  String
 *  3  TRAN-CAT-CD            9(04)          19- 22        18    4  int
 *  4  TRAN-SOURCE            X(10)          23- 32        22   10  String
 *  5  TRAN-DESC              X(100)         33-132        32  100  String
 *  6  TRAN-AMT               S9(09)V99     133-143       132   11  BigDecimal scale 2
 *  7  TRAN-MERCHANT-ID       9(09)         144-152       143    9  long
 *  8  TRAN-MERCHANT-NAME     X(50)         153-202       152   50  String
 *  9  TRAN-MERCHANT-CITY     X(50)         203-252       202   50  String
 * 10  TRAN-MERCHANT-ZIP      X(10)         253-262       252   10  String
 * 11  TRAN-CARD-NUM          X(16)         263-278       262   16  String
 * 12  TRAN-ORIG-TS           X(26)         279-304       278   26  String
 * 13  TRAN-PROC-TS           X(26)         305-330       304   26  String
 * 14  FILLER                 X(20)         331-350       330   20  reserved span
 * --  ---------------------  ------------  ---------  -------  ---
 *     16+2+4+10+100+11+9+50+50+10+16+26+26+20                 350
 * </pre>
 *
 * <p>A signed {@code S9(p)V(s)} span occupies {@code p + s} bytes, not {@code p + s + 1}: the
 * operational sign is <em>overpunched into the trailing byte</em> rather than stored separately.
 * {@code TRAN-AMT} is therefore 11 bytes wide - nine integer digits plus two fraction digits - and
 * treating it as 9 or 12 would shift every subsequent field. The copybook declares no
 * {@code SIGN SEPARATE} clause, no {@code USAGE}, and no {@code COMP-3}, so the field is zoned
 * {@code DISPLAY}.
 *
 * <h2>Three independent confirmations of this layout</h2>
 * <ol>
 *   <li><strong>The SORT symbol table.</strong> {@code app/jcl/TRANREPT.jcl:41-42} declares
 *       {@code TRAN-CARD-NUM,263,16,ZD} and {@code TRAN-PROC-DT,305,10,CH} against a
 *       {@code LRECL=350} dataset. The first pins {@code TRAN-CARD-NUM} to 1-based 263, exactly as
 *       tabulated. The second proves {@code TRAN-PROC-DT} is the <em>first ten bytes</em> of
 *       {@code TRAN-PROC-TS}, which begins at 1-based 305.</li>
 *   <li><strong>Two independent FD splits of the same dataset.</strong>
 *       {@code app/cbl/CBTRN03C.cbl:62-65} divides the record as
 *       {@code FD-TRANS-DATA X(304)} + {@code FD-TRAN-PROC-TS X(26)} + {@code FD-FILLER X(20)},
 *       splitting at 1-based 305 and summing to 350.
 *       {@code app/cbl/CBTRN02C.cbl:72-74} divides the same file as
 *       {@code FD-TRANS-ID X(16)} + {@code FD-ACCT-DATA X(334)}, which also sums to 350 and
 *       independently confirms the 16-byte key.</li>
 *   <li><strong>A clean fixture decode.</strong> Applying this table to
 *       {@code app/data/ASCII/dailytran.txt} - 300 rows of exactly 350 bytes, the
 *       {@code CVTRA06Y} twin of this layout - decodes every field of every row cleanly. Row 1
 *       yields {@code TRAN-AMT} image {@code 0000005047G} (= 504.77),
 *       {@code TRAN-MERCHANT-ID} {@code 800000000}, {@code TRAN-MERCHANT-NAME}
 *       {@code Abshire-Lowe}, {@code TRAN-CARD-NUM} {@code 4859452612877065} and
 *       {@code TRAN-ORIG-TS} {@code 2022-06-10 19:27:53.000000}.</li>
 * </ol>
 *
 * <h2>Provenance of the expected values</h2>
 * <p>The legacy COBOL cannot be executed in this environment, so every expectation asserted against
 * this type is <em>statically derived</em>: from the copybook's own {@code PICTURE} clauses, from the
 * JCL {@code LRECL} and SORT symbol declarations, from the consuming programs' {@code FD} splits, and
 * from the real ASCII fixtures. That is a deliberate, documented substitution for a captured
 * execution baseline rather than an oversight, and it is why the three confirmations above are
 * recorded here in the source: they are the audit trail for the offsets.
 *
 * <h2>The negative-zero re-encode hazard</h2>
 * <p>{@code TRAN-AMT}'s trailing byte carries the sign as a zoned overpunch: one character encodes
 * both the final digit and the sign of the whole value. Across the 300 rows of
 * {@code app/data/ASCII/dailytran.txt} that byte takes every legal value -
 * <code>'&#123;'</code> and {@code 'A'}-{@code 'I'} for a positive final digit 0-9 (25 and 225 rows),
 * <code>'&#125;'</code> and {@code 'J'}-{@code 'R'} for a negative final digit 0-9 (6 and 44 rows), so
 * 50 of the 300 amounts are negative.
 *
 * <p>The distinction that matters is between the <em>character</em> and the <em>value</em>. A
 * <code>'&#125;'</code> means "negative, final digit zero"; it does not mean the amount is zero. Every
 * one of the six <code>'&#125;'</code> rows in the fixture holds a substantial negative amount -
 * {@code 0000009190}<code>&#125;</code> is {@code -919.00} - and each one survives a decode and
 * re-encode unchanged, because {@code -919.00} legitimately ends in a negative zero digit.
 *
 * <p>The hazard is the one image where the <em>whole value</em> is a negative zero:
 * {@code 0000000000}<code>&#125;</code>. Java's {@link BigDecimal} has no signed zero, so that image
 * decodes to plain {@code 0.00}, and re-encoding {@code 0.00} yields
 * {@code 0000000000}<code>&#123;</code> - a silent one-byte difference that a comparison of numeric
 * values could never detect but a comparison of record images would fail on. That exact image does not
 * occur in this fixture, which is precisely why it is dangerous: it is representable in the field, it
 * would arrive from a production dataset without warning, and no test built only from the fixture
 * would catch it.
 *
 * <p>The consequence is a hard rule for callers, and the reason this type keeps its backing span:
 * <strong>a verbatim copy of a record image must copy the raw bytes, never round-trip through the
 * decoded fields.</strong> Use {@link #rawImage()}, {@link #encode(Charset)}, {@link #copy()},
 * {@link #displayImage()} or {@link #writeTranAmtImage(String)} for that. Reading
 * {@link #tranAmt()} and writing it back with {@link #moveTranAmt(BigDecimal)} is a numeric
 * round-trip: faithful for every value the fixture contains, but it normalises a whole-value negative
 * zero to a positive one.
 *
 * <h2>Two write paths per character field</h2>
 * <p>COBOL {@code MOVE} and COBOL {@code STRING ... INTO} do different things to the same receiver,
 * and {@code CBACT04C} uses both against this record, so both are offered for every {@code PIC X}
 * field:
 * <ul>
 *   <li>{@code moveXxx(String)} reproduces {@code MOVE}: the receiver is filled from its leftmost
 *       position, padded on the right with spaces when the sender is shorter and truncated on the
 *       right when it is longer.</li>
 *   <li>{@code stringIntoXxx(String...)} reproduces {@code STRING ... DELIMITED BY SIZE INTO}: only
 *       the supplied bytes are written, at the start of the span, and <em>the remainder of the field
 *       keeps whatever it already held</em>. {@code CBACT04C.cbl:485-489} strings 24 characters
 *       ({@code 'Int. for a/c '} plus an 11-digit account id) into {@code TRAN-DESC PIC X(100)} and
 *       bytes 25-100 are deliberately not blanked. A padding setter cannot reproduce that.</li>
 * </ul>
 *
 * <h2>The CVTRA04Y name clash</h2>
 * <p>{@code app/cpy/CVTRA04Y.cpy} - the transaction category record, modelled separately as
 * {@code TranCategoryRecord} - declares {@code TRAN-TYPE-CD PIC X(02)} and
 * {@code TRAN-CAT-CD PIC 9(04)} inside {@code TRAN-CAT-KEY}, reusing both of this record's field
 * names. {@code CBTRN03C} copies both copybooks ({@code :93} and {@code :108}) and must therefore
 * qualify every reference: {@code TRAN-TYPE-CD OF TRAN-RECORD} and {@code TRAN-CAT-CD OF
 * TRAN-RECORD} at {@code :189}, {@code :191}, {@code :193}, {@code :365} and {@code :367}. The clash
 * is documented rather than renamed around, because the COBOL names are the contract that
 * field-for-field comparison is keyed on.
 *
 * <h2>Relationship to DalyTranRecord</h2>
 * <p>{@code app/cpy/CVTRA06Y.cpy} declares an identically shaped 350-byte record whose items all
 * carry a {@code DALYTRAN-} name prefix, modelled separately as {@code DalyTranRecord}. The two are
 * deliberately <em>not</em> unified behind a shared base class, interface or generic: the prefixed
 * names are themselves part of the contract that a field-by-field diff reports on, and collapsing
 * them would erase that distinction.
 *
 * <h2>Threading and state</h2>
 * <p>An instance wraps a mutable 350-byte area, exactly as a COBOL {@code WORKING-STORAGE} record
 * does, and is therefore <strong>not</strong> thread-safe. Confine an instance to one thread, or copy
 * it with {@link #copy()}. The class holds no static mutable state whatsoever: every constant is
 * {@code static final} and deeply immutable, and every byte buffer handed out is a copy. It is a
 * plain data type - not a Spring bean, not a JPA entity, and it declares no schema of any kind.
 *
 * @see FixedWidthRecord
 * @see FixedWidthCodec
 * @see CobolDecimal
 */
public final class TranRecord {

    // =================================================================================================
    // Declared geometry. Every offset and every length is a separately named constant so that each one
    // can be audited line by line against app/cpy/CVTRA05Y.cpy, rather than being buried in an
    // expression. Offsets are absolute and 0-based; the copybook's own positions are 1-based, and the
    // Javadoc above tabulates both so the conversion is visible rather than assumed.
    // =================================================================================================

    /**
     * The declared record width in bytes, from the copybook's {@code RECLN = 350} header comment and
     * corroborated by {@code LRECL=350} on every dataset that holds this record
     * ({@code app/jcl/INTCALC.jcl} {@code RECFM=F}, {@code app/jcl/TRANREPT.jcl} {@code RECFM=FB}).
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * The length of the {@code TRANSACT} KSDS primary key, which is {@code TRAN-ID} in full.
     * Independently confirmed by {@code app/cbl/CBTRN02C.cbl:73}, whose {@code FD} splits the record
     * as {@code FD-TRANS-ID X(16)} plus {@code FD-ACCT-DATA X(334)}.
     */
    public static final int TRAN_ID_KEY_LENGTH = 16;

    /** {@code TRAN-ID PIC X(16)} - 1-based 1-16, 0-based offset 0. */
    public static final int TRAN_ID_OFFSET = 0;
    /** Width of {@code TRAN-ID PIC X(16)}. */
    public static final int TRAN_ID_LENGTH = 16;

    /** {@code TRAN-TYPE-CD PIC X(02)} - 1-based 17-18, 0-based offset 16. */
    public static final int TRAN_TYPE_CD_OFFSET = 16;
    /** Width of {@code TRAN-TYPE-CD PIC X(02)}. */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)} - 1-based 19-22, 0-based offset 18. */
    public static final int TRAN_CAT_CD_OFFSET = 18;
    /** Width of {@code TRAN-CAT-CD PIC 9(04)}. */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /** {@code TRAN-SOURCE PIC X(10)} - 1-based 23-32, 0-based offset 22. */
    public static final int TRAN_SOURCE_OFFSET = 22;
    /** Width of {@code TRAN-SOURCE PIC X(10)}. */
    public static final int TRAN_SOURCE_LENGTH = 10;

    /** {@code TRAN-DESC PIC X(100)} - 1-based 33-132, 0-based offset 32. */
    public static final int TRAN_DESC_OFFSET = 32;
    /** Width of {@code TRAN-DESC PIC X(100)}. */
    public static final int TRAN_DESC_LENGTH = 100;

    /** {@code TRAN-AMT PIC S9(09)V99} - 1-based 133-143, 0-based offset 132. */
    public static final int TRAN_AMT_OFFSET = 132;
    /**
     * Width of {@code TRAN-AMT PIC S9(09)V99}: nine integer digits plus two fraction digits, with the
     * sign overpunched into the trailing byte rather than occupying a byte of its own. Eleven, not
     * nine and not twelve.
     */
    public static final int TRAN_AMT_LENGTH = 11;
    /** {@code p} of {@code TRAN-AMT PIC S9(09)V99} - the digits before the implied decimal point. */
    public static final int TRAN_AMT_INTEGER_DIGITS = 9;
    /**
     * {@code s} of {@code TRAN-AMT PIC S9(09)V99} - the digits after the implied decimal point. Taken
     * from {@link CobolDecimal#MONETARY_SCALE} rather than restated as a literal, because every
     * monetary field in this codebase is scale 2 and the policy belongs in one place.
     */
    public static final int TRAN_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /** {@code TRAN-MERCHANT-ID PIC 9(09)} - 1-based 144-152, 0-based offset 143. */
    public static final int TRAN_MERCHANT_ID_OFFSET = 143;
    /** Width of {@code TRAN-MERCHANT-ID PIC 9(09)}. */
    public static final int TRAN_MERCHANT_ID_LENGTH = 9;

    /** {@code TRAN-MERCHANT-NAME PIC X(50)} - 1-based 153-202, 0-based offset 152. */
    public static final int TRAN_MERCHANT_NAME_OFFSET = 152;
    /** Width of {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int TRAN_MERCHANT_NAME_LENGTH = 50;

    /** {@code TRAN-MERCHANT-CITY PIC X(50)} - 1-based 203-252, 0-based offset 202. */
    public static final int TRAN_MERCHANT_CITY_OFFSET = 202;
    /** Width of {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int TRAN_MERCHANT_CITY_LENGTH = 50;

    /** {@code TRAN-MERCHANT-ZIP PIC X(10)} - 1-based 253-262, 0-based offset 252. */
    public static final int TRAN_MERCHANT_ZIP_OFFSET = 252;
    /** Width of {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int TRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * {@code TRAN-CARD-NUM PIC X(16)} - 1-based 263-278, 0-based offset 262. Confirmed independently
     * by {@code app/jcl/TRANREPT.jcl:41}, whose SORT symbol declares {@code TRAN-CARD-NUM,263,16,ZD}.
     */
    public static final int TRAN_CARD_NUM_OFFSET = 262;
    /** Width of {@code TRAN-CARD-NUM PIC X(16)}. */
    public static final int TRAN_CARD_NUM_LENGTH = 16;

    /** {@code TRAN-ORIG-TS PIC X(26)} - 1-based 279-304, 0-based offset 278. */
    public static final int TRAN_ORIG_TS_OFFSET = 278;
    /** Width of {@code TRAN-ORIG-TS PIC X(26)}. */
    public static final int TRAN_ORIG_TS_LENGTH = 26;

    /** {@code TRAN-PROC-TS PIC X(26)} - 1-based 305-330, 0-based offset 304. */
    public static final int TRAN_PROC_TS_OFFSET = 304;
    /** Width of {@code TRAN-PROC-TS PIC X(26)}. */
    public static final int TRAN_PROC_TS_LENGTH = 26;

    /**
     * The SORT key {@code TRAN-PROC-DT} - the first ten bytes of {@code TRAN-PROC-TS}, so the same
     * 0-based offset. {@code app/jcl/TRANREPT.jcl:42} declares it as {@code TRAN-PROC-DT,305,10,CH}
     * and {@code app/cbl/CBTRN03C.cbl:173-174} references it as {@code TRAN-PROC-TS (1:10)}. COBOL
     * reference modification is 1-based, so {@code (1:10)} is {@code substring(0, 10)} in Java.
     */
    public static final int TRAN_PROC_DT_OFFSET = 304;
    /** Width of the {@code TRAN-PROC-DT} SORT key, ten characters of {@code yyyy-MM-dd}. */
    public static final int TRAN_PROC_DT_LENGTH = 10;

    /**
     * {@code FILLER PIC X(20)} - 1-based 331-350, 0-based offset 330. Reserved, never referable by
     * name in COBOL, and <strong>never optional</strong>: the copybook's twenty trailing bytes are
     * part of the 350-byte record, so they are declared as a first-class span, space-filled on
     * initialisation and emitted on every write. Omitting them would make the record 330 bytes and
     * break every consumer.
     */
    public static final int FILLER_OFFSET = 330;
    /** Width of the trailing {@code FILLER PIC X(20)}. */
    public static final int FILLER_LENGTH = 20;

    // =================================================================================================
    // Field descriptors. Each is an immutable FieldSpan carrying the copybook name verbatim - hyphens
    // included - together with its offset, width and PICTURE category, so a caller never has to pair an
    // offset with a length by hand. FieldSpan is a record over String, int, enum and boolean
    // components, so these constants are deeply immutable.
    // =================================================================================================

    /** Descriptor for {@code TRAN-ID PIC X(16)}, the {@code TRANSACT} KSDS key. */
    public static final FieldSpan TRAN_ID =
            FieldSpan.alphanumeric("TRAN-ID", TRAN_ID_OFFSET, TRAN_ID_LENGTH);

    /**
     * Descriptor for {@code TRAN-TYPE-CD PIC X(02)}. Alphanumeric despite holding values that look
     * numeric ({@code '01'}), because the copybook declares {@code PIC X} - so leading zeros are
     * stored characters, not formatting.
     */
    public static final FieldSpan TRAN_TYPE_CD =
            FieldSpan.alphanumeric("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);

    /** Descriptor for {@code TRAN-CAT-CD PIC 9(04)}, unsigned numeric {@code DISPLAY}. */
    public static final FieldSpan TRAN_CAT_CD =
            FieldSpan.unsignedNumeric("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);

    /** Descriptor for {@code TRAN-SOURCE PIC X(10)}. */
    public static final FieldSpan TRAN_SOURCE =
            FieldSpan.alphanumeric("TRAN-SOURCE", TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH);

    /** Descriptor for {@code TRAN-DESC PIC X(100)}. */
    public static final FieldSpan TRAN_DESC =
            FieldSpan.alphanumeric("TRAN-DESC", TRAN_DESC_OFFSET, TRAN_DESC_LENGTH);

    /**
     * Descriptor for {@code TRAN-AMT PIC S9(09)V99}, signed zoned {@code DISPLAY} of
     * {@code TRAN_AMT_INTEGER_DIGITS + TRAN_AMT_SCALE} = 11 bytes.
     */
    public static final FieldSpan TRAN_AMT = FieldSpan.signedScaled(
            "TRAN-AMT", TRAN_AMT_OFFSET, TRAN_AMT_INTEGER_DIGITS, TRAN_AMT_SCALE);

    /** Descriptor for {@code TRAN-MERCHANT-ID PIC 9(09)}, unsigned numeric {@code DISPLAY}. */
    public static final FieldSpan TRAN_MERCHANT_ID = FieldSpan.unsignedNumeric(
            "TRAN-MERCHANT-ID", TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH);

    /** Descriptor for {@code TRAN-MERCHANT-NAME PIC X(50)}. */
    public static final FieldSpan TRAN_MERCHANT_NAME = FieldSpan.alphanumeric(
            "TRAN-MERCHANT-NAME", TRAN_MERCHANT_NAME_OFFSET, TRAN_MERCHANT_NAME_LENGTH);

    /** Descriptor for {@code TRAN-MERCHANT-CITY PIC X(50)}. */
    public static final FieldSpan TRAN_MERCHANT_CITY = FieldSpan.alphanumeric(
            "TRAN-MERCHANT-CITY", TRAN_MERCHANT_CITY_OFFSET, TRAN_MERCHANT_CITY_LENGTH);

    /** Descriptor for {@code TRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final FieldSpan TRAN_MERCHANT_ZIP = FieldSpan.alphanumeric(
            "TRAN-MERCHANT-ZIP", TRAN_MERCHANT_ZIP_OFFSET, TRAN_MERCHANT_ZIP_LENGTH);

    /** Descriptor for {@code TRAN-CARD-NUM PIC X(16)}. */
    public static final FieldSpan TRAN_CARD_NUM = FieldSpan.alphanumeric(
            "TRAN-CARD-NUM", TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH);

    /** Descriptor for {@code TRAN-ORIG-TS PIC X(26)}. */
    public static final FieldSpan TRAN_ORIG_TS = FieldSpan.alphanumeric(
            "TRAN-ORIG-TS", TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH);

    /** Descriptor for {@code TRAN-PROC-TS PIC X(26)}. */
    public static final FieldSpan TRAN_PROC_TS = FieldSpan.alphanumeric(
            "TRAN-PROC-TS", TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH);

    /** Descriptor for the trailing {@code FILLER PIC X(20)}. */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete record layout, in copybook declaration order.
     *
     * <p>Constructing this is the record's width self-check, and it runs at class initialisation.
     * {@code RecordLayout} verifies that the spans start at offset 0, are contiguous with no gap and
     * no overlap, declare no duplicate referable name, and sum to exactly {@link #RECORD_LENGTH}. A
     * transcription error therefore fails loudly and immediately - the class cannot even load - rather
     * than surfacing later as a plausible-looking record with every field shifted. Note that this only
     * holds because {@code FILLER} is declared and because {@code TRAN-AMT} is 11 bytes; drop either
     * and the sum is no longer 350.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(
            RECORD_LENGTH,
            TRAN_ID,
            TRAN_TYPE_CD,
            TRAN_CAT_CD,
            TRAN_SOURCE,
            TRAN_DESC,
            TRAN_AMT,
            TRAN_MERCHANT_ID,
            TRAN_MERCHANT_NAME,
            TRAN_MERCHANT_CITY,
            TRAN_MERCHANT_ZIP,
            TRAN_CARD_NUM,
            TRAN_ORIG_TS,
            TRAN_PROC_TS,
            FILLER);

    // =================================================================================================
    // Instance state. The backing span is retained deliberately: it is what makes a byte-verbatim copy
    // of a stored record possible, which the negative-zero hazard documented on the class makes
    // mandatory. Both fields are final, and both are bound to one charset that the caller named
    // explicitly - nothing here consults a platform default or a configuration bean.
    // =================================================================================================

    /**
     * The 350-byte record area, preserved exactly as read. All reads and writes address it by absolute
     * offset through {@link #codec}; it is never rebuilt from decoded field values.
     */
    private final FixedWidthRecord area;

    /**
     * The codec bound to {@link #area}'s charset. Every pad, truncate, zoned-sign and
     * {@code STRING ... INTO} decision is delegated to it, so none of those rules is reimplemented
     * here.
     */
    private final FixedWidthCodec codec;

    /**
     * Allocates an initialised record area, the equivalent of declaring
     * {@code 01 TRAN-RECORD} in {@code WORKING-STORAGE} and running {@code INITIALIZE} over it.
     *
     * <p>Character spans and the trailing {@code FILLER} are filled with the charset's space byte;
     * {@code TRAN-CAT-CD}, {@code TRAN-MERCHANT-ID} and {@code TRAN-AMT} are filled with its zero
     * byte, so they read back as {@code 0000}, {@code 000000000} and {@code +0.00} respectively rather
     * than as invalid numeric {@code DISPLAY} data.
     *
     * @param charset the code page of the record's data, supplied explicitly. {@code US-ASCII} for the
     *                text fixtures and {@code IBM037} for EBCDIC datasets; it must encode the digits,
     *                the space and the ten positive and ten negative zoned overpunch characters to one
     *                byte each
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  characters a zoned fixed-width record requires
     */
    public TranRecord(Charset charset) {
        this.codec = new FixedWidthCodec(requireCharset(charset));
        this.area = codec.newRecord(LAYOUT);
    }

    /**
     * Adopts an already-built area and its codec. Private because the two must agree on charset and on
     * record length, which the public entry points guarantee.
     */
    private TranRecord(FixedWidthCodec codec, FixedWidthRecord area) {
        this.codec = codec;
        this.area = area;
    }

    /**
     * Reads a stored record, keeping its bytes verbatim.
     *
     * <p>The image is defensively copied and retained as the backing span, sign overpunches and all,
     * so a subsequent {@link #rawImage()} or {@link #encode(Charset)} reproduces the input byte for
     * byte. Field values are decoded on demand rather than eagerly, which is precisely what keeps a
     * negative-zero amount image intact.
     *
     * @param image   exactly {@link #RECORD_LENGTH} bytes as held on the dataset
     * @param charset the code page the bytes are encoded in, supplied explicitly
     * @return a record over a copy of {@code image}
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH} bytes.
     *                                  A short row is rejected rather than tolerated, because
     *                                  silently accepting one would let every field offset drift; widen
     *                                  it deliberately with
     *                                  {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)} first
     */
    public static TranRecord decode(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "Record bytes are required to decode a TRAN-RECORD; use the "
                + "(Charset) constructor to allocate an empty 350-byte area instead");
        FixedWidthCodec codec = new FixedWidthCodec(requireCharset(charset));
        return new TranRecord(codec, codec.wrap(image, LAYOUT));
    }

    /**
     * Reads a stored record from its text image, for the ASCII fixtures and the parity cases.
     *
     * <p>A convenience over {@link #decode(byte[], Charset)} for the single-byte text fixtures in
     * {@code app/data/ASCII}, whose rows are exactly 350 characters. The charset is still explicit,
     * because the character-to-byte mapping of the zoned sign overpunches depends on it.
     *
     * @param image   the record's text image, exactly {@link #RECORD_LENGTH} characters under
     *                {@code charset}
     * @param charset the code page to encode {@code image} with, supplied explicitly
     * @return a record over the encoded bytes of {@code image}
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes
     */
    public static TranRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A record image is required to decode a TRAN-RECORD");
        return decode(image.getBytes(requireCharset(charset)), charset);
    }

    /**
     * Duplicates this record by copying its backing bytes.
     *
     * <p>This is a byte-verbatim copy, not a field-by-field rebuild, so even a negative-zero
     * {@code TRAN-AMT} image survives it unchanged. Use it to snapshot a record before an update - the
     * pattern the online programs' {@code 9300-CHECK-CHANGE-IN-REC} concurrency check relies on - or
     * to hand a caller a record it may mutate without affecting this one.
     *
     * @return an independent record holding identical bytes under the same charset
     */
    public TranRecord copy() {
        return new TranRecord(codec, codec.wrap(area.toByteArray(), LAYOUT));
    }

    /**
     * The charset this record's bytes are encoded in, as supplied at construction.
     *
     * @return the record's code page; never {@code null}
     */
    public Charset charset() {
        return area.charset();
    }

    /**
     * Recomputes the record width by summing the declared span lengths.
     *
     * <p>Exists so that a test can restate the copybook arithmetic independently of
     * {@link #RECORD_LENGTH} and {@link #LAYOUT}: asserting that this returns 350 proves the fourteen
     * declared spans really do account for every byte, with no span silently mis-sized. The loud
     * failure for a geometry error lives in {@link #LAYOUT}'s own construction, which rejects gaps,
     * overlaps and a wrong total at class-initialisation time.
     *
     * @return the sum of the fourteen declared span lengths, which is {@link #RECORD_LENGTH}
     */
    public static int sumOfDeclaredSpanLengths() {
        int total = 0;
        for (FieldSpan span : LAYOUT.storageSpans()) {
            total += span.length();
        }
        return total;
    }

    // =================================================================================================
    // Verbatim image access. These are the only safe paths for copying a record, because they move
    // bytes rather than values, so they cannot normalise a negative-zero amount into a positive one.
    // =================================================================================================

    /**
     * The complete 350-byte record image, verbatim.
     *
     * <p>This is the byte-for-byte content of the backing span, including the trailing {@code FILLER}
     * and including {@code TRAN-AMT}'s sign overpunch exactly as stored. It is what
     * {@code app/cbl/CBACT04C.cbl:500} writes with {@code WRITE FD-TRANFILE-REC FROM TRAN-RECORD}, and
     * the only correct way to copy an unmodified record onward.
     *
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes; mutating it does not affect this
     *         record
     */
    public byte[] rawImage() {
        return area.toByteArray();
    }

    /**
     * The complete 350-character record image as text.
     *
     * <p>Reproduces {@code DISPLAY TRAN-RECORD} at {@code app/cbl/CBTRN03C.cbl:180}, which writes the
     * whole group item to {@code SYSOUT} as characters - untrimmed, {@code FILLER} included, and with
     * {@code TRAN-AMT} shown as its raw zoned image rather than as a formatted number.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String displayImage() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * Serialises the record to bytes under an explicitly named charset.
     *
     * <p>When {@code charset} is the one this record already holds, the backing bytes are returned
     * verbatim - identical to {@link #rawImage()} - so the common case cannot lose a sign overpunch.
     * When it differs, the record is transcoded span by span at the <em>character</em> level rather
     * than by decoding and re-encoding field values, which means a negative-zero amount image survives
     * a code-page change too.
     *
     * @param charset the target code page, supplied explicitly; it must encode every character
     *                currently held in the record to a single byte
     * @return exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  characters this record holds
     */
    public byte[] encode(Charset charset) {
        if (requireCharset(charset).equals(area.charset())) {
            return area.toByteArray();
        }
        FixedWidthRecord transcoded = new FixedWidthRecord(RECORD_LENGTH, charset);
        for (FieldSpan span : LAYOUT.storageSpans()) {
            transcoded.writeString(span.offset(), span.length(), area.readSpan(span));
        }
        return transcoded.toByteArray();
    }

    /**
     * The raw character image of one declared span, untrimmed.
     *
     * <p>Needed because a COBOL {@code DISPLAY} of a numeric {@code DISPLAY} item prints its stored
     * bytes rather than a formatted value: {@code DISPLAY 'TRAN-AMT ' TRAN-AMT} at
     * {@code app/cbl/CBTRN03C.cbl:198} emits {@code 0000005047G}, sign overpunch and all. Reproducing
     * that {@code SYSOUT} line is impossible from the decoded {@code BigDecimal} alone.
     *
     * @param field one of this class's declared span constants
     * @return the span's stored characters, exactly {@code field.length()} of them and never trimmed
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a span declared by {@link #LAYOUT}; a
     *                                  descriptor borrowed from another copybook would read the wrong
     *                                  bytes and is rejected rather than silently honoured
     */
    public String rawSpan(FieldSpan field) {
        return area.readSpan(requireDeclaredSpan(field));
    }

    /**
     * The raw bytes of one declared span.
     *
     * @param field one of this class's declared span constants
     * @return a fresh array of exactly {@code field.length()} bytes
     * @throws NullPointerException     if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a span declared by {@link #LAYOUT}
     */
    public byte[] rawSpanBytes(FieldSpan field) {
        return area.readSpanBytes(requireDeclaredSpan(field));
    }

    // =================================================================================================
    // Field accessors. Character fields are returned UNTRIMMED, at their full declared width: the
    // fixtures really do hold trailing spaces that are part of the data - TRAN-SOURCE is "POS TERM  "
    // with two of them - and the report columns are aligned by those widths. Trimming here would break
    // both the record width on write-back and the column alignment on the printed report.
    // =================================================================================================

    /**
     * {@code TRAN-ID PIC X(16)} - the {@code TRANSACT} KSDS key, at 0-based offset 0.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:476-480} builds it by stringing a 10-character parameter date
     * onto a 6-digit suffix, which fills the field exactly.
     *
     * @return exactly {@link #TRAN_ID_LENGTH} characters, untrimmed
     */
    public String tranId() {
        return codec.readPicX(area, TRAN_ID);
    }

    /**
     * {@code TRAN-TYPE-CD PIC X(02)} - at 0-based offset 16.
     *
     * <p>A {@code String} rather than a number because the copybook declares {@code PIC X}: the stored
     * {@code '01'} is two characters, and its leading zero is data. {@code app/cbl/CBTRN03C.cbl}
     * qualifies this field as {@code TRAN-TYPE-CD OF TRAN-RECORD} at {@code :189}, {@code :191} and
     * {@code :365} to distinguish it from the identically named item in {@code CVTRA04Y}.
     *
     * @return exactly {@link #TRAN_TYPE_CD_LENGTH} characters, untrimmed
     */
    public String tranTypeCd() {
        return codec.readPicX(area, TRAN_TYPE_CD);
    }

    /**
     * {@code TRAN-CAT-CD PIC 9(04)} - at 0-based offset 18.
     *
     * <p>An {@code int} because the {@code PICTURE} is scale-free {@code 9}, so no decimal alignment
     * and no rounding policy applies; four digits cannot overflow. Qualified as
     * {@code TRAN-CAT-CD OF TRAN-RECORD} at {@code app/cbl/CBTRN03C.cbl:193} and {@code :367}. For the
     * stored digits including leading zeros, use {@link #tranCatCdImage()}.
     *
     * @return the stored value as an integer, 0 to 9999
     * @throws IllegalArgumentException if the span does not hold four digits
     */
    public int tranCatCd() {
        return codec.readPic9AsInt(area, TRAN_CAT_CD);
    }

    /**
     * The stored characters of {@code TRAN-CAT-CD PIC 9(04)}, leading zeros included.
     *
     * @return exactly {@link #TRAN_CAT_CD_LENGTH} characters, for example {@code 0001}
     */
    public String tranCatCdImage() {
        return area.readSpan(TRAN_CAT_CD);
    }

    /**
     * {@code TRAN-SOURCE PIC X(10)} - at 0-based offset 22.
     *
     * <p>Returned untrimmed on purpose. The fixtures hold {@code "POS TERM  "} with two significant
     * trailing spaces, and {@code app/cbl/CBACT04C.cbl:484} moves the literal {@code 'System'} in,
     * leaving four.
     *
     * @return exactly {@link #TRAN_SOURCE_LENGTH} characters, untrimmed
     */
    public String tranSource() {
        return codec.readPicX(area, TRAN_SOURCE);
    }

    /**
     * {@code TRAN-DESC PIC X(100)} - at 0-based offset 32.
     *
     * @return exactly {@link #TRAN_DESC_LENGTH} characters, untrimmed and space-padded as stored
     */
    public String tranDesc() {
        return codec.readPicX(area, TRAN_DESC);
    }

    /**
     * {@code TRAN-AMT PIC S9(09)V99} - at 0-based offset 132, decoded to a {@code BigDecimal} of scale
     * exactly {@link #TRAN_AMT_SCALE}.
     *
     * <p>The trailing byte is a zoned sign overpunch. <code>'&#123;'</code> and {@code 'A'}-{@code 'I'}
     * carry a positive final digit 0-9, <code>'&#125;'</code> and {@code 'J'}-{@code 'R'} a negative one,
     * and a plain digit is read as positive. So the image {@code 0000005047G} decodes to {@code 504.77}, and
     * <code>0000009190&#125;</code> to {@code -919.00}.
     *
     * <p>Never a {@code double} or a {@code float}: those cannot represent a decimal fraction exactly,
     * and a cent of drift is a parity failure. The scale is fixed by the {@code PICTURE} and the
     * rounding policy is truncation, both centralised in {@link CobolDecimal}.
     *
     * <p><strong>One image does not survive a numeric round-trip.</strong> A trailing
     * <code>'&#125;'</code> means "negative, final digit zero", so it usually carries an ordinary
     * negative amount that re-encodes unchanged. But when the entire magnitude is zero -
     * {@code 0000000000}<code>&#125;</code>, a negative zero - this returns plain {@code 0.00}, because
     * {@link BigDecimal} has no signed zero, and writing that back with
     * {@link #moveTranAmt(BigDecimal)} stores {@code 0000000000}<code>&#123;</code>. Copy record images
     * with {@link #rawImage()}, {@link #tranAmtImage()} or {@link #copy()} whenever byte fidelity
     * matters.
     *
     * @return the signed amount at scale 2
     * @throws IllegalArgumentException if the span does not hold a valid zoned numeric image
     */
    public BigDecimal tranAmt() {
        return codec.readMonetary(area, TRAN_AMT);
    }

    /**
     * The stored characters of {@code TRAN-AMT PIC S9(09)V99}, sign overpunch included.
     *
     * <p>This is what {@code DISPLAY 'TRAN-AMT ' TRAN-AMT} at {@code app/cbl/CBTRN03C.cbl:198} writes
     * to {@code SYSOUT} - {@code 0000005047G}, not {@code 504.77} - so reproducing that line requires
     * the raw image rather than the decoded value.
     *
     * @return exactly {@link #TRAN_AMT_LENGTH} characters
     */
    public String tranAmtImage() {
        return area.readSpan(TRAN_AMT);
    }

    /**
     * Whether {@code TRAN-AMT} is numerically zero, whichever sign it carries.
     *
     * <p>Compared with {@link BigDecimal#compareTo(BigDecimal)} and never with
     * {@link BigDecimal#equals(Object)}, because {@code equals} is scale-sensitive: {@code 0.00} and
     * {@code 0} are unequal under {@code equals} yet identical in value. Both a positive-zero and a
     * negative-zero image answer {@code true} here, which is exactly the COBOL numeric comparison and is
     * also why a zero test can never stand in for a byte comparison.
     *
     * @return {@code true} when the decoded amount compares equal to zero
     */
    public boolean hasZeroTranAmt() {
        return tranAmt().compareTo(CobolDecimal.monetaryZero()) == 0;
    }

    /**
     * {@code TRAN-MERCHANT-ID PIC 9(09)} - at 0-based offset 143.
     *
     * <p>A {@code long} because the {@code PICTURE} is scale-free {@code 9}. Nine digits would fit an
     * {@code int}, but every identifier in this model layer is returned as a {@code long} so that the
     * wider keys - an 11-digit account id, a 16-digit card number read numerically - need no different
     * treatment at a call site. For the stored digits including leading zeros, use
     * {@link #tranMerchantIdImage()}.
     *
     * @return the stored value, 0 to 999999999
     * @throws IllegalArgumentException if the span does not hold nine digits
     */
    public long tranMerchantId() {
        return codec.readPic9(area, TRAN_MERCHANT_ID);
    }

    /**
     * The stored characters of {@code TRAN-MERCHANT-ID PIC 9(09)}, leading zeros included.
     *
     * @return exactly {@link #TRAN_MERCHANT_ID_LENGTH} characters, for example {@code 800000000}
     */
    public String tranMerchantIdImage() {
        return area.readSpan(TRAN_MERCHANT_ID);
    }

    /**
     * {@code TRAN-MERCHANT-NAME PIC X(50)} - at 0-based offset 152.
     *
     * @return exactly {@link #TRAN_MERCHANT_NAME_LENGTH} characters, untrimmed
     */
    public String tranMerchantName() {
        return codec.readPicX(area, TRAN_MERCHANT_NAME);
    }

    /**
     * {@code TRAN-MERCHANT-CITY PIC X(50)} - at 0-based offset 202.
     *
     * @return exactly {@link #TRAN_MERCHANT_CITY_LENGTH} characters, untrimmed
     */
    public String tranMerchantCity() {
        return codec.readPicX(area, TRAN_MERCHANT_CITY);
    }

    /**
     * {@code TRAN-MERCHANT-ZIP PIC X(10)} - at 0-based offset 252.
     *
     * @return exactly {@link #TRAN_MERCHANT_ZIP_LENGTH} characters, untrimmed
     */
    public String tranMerchantZip() {
        return codec.readPicX(area, TRAN_MERCHANT_ZIP);
    }

    /**
     * {@code TRAN-CARD-NUM PIC X(16)} - at 0-based offset 262, the full card number as stored.
     *
     * <p>Returned complete and unaltered. The legacy programs handle it in the clear -
     * {@code app/cbl/CBTRN03C.cbl:180} displays the entire record to {@code SYSOUT}, and {@code :181}
     * and {@code :185} compare and carry the number as a control break - so masking, redacting,
     * hashing or truncating it here would change observable behaviour and break the byte-level
     * comparison that verifies this migration. Access control belongs to the deployment, not to this
     * record type.
     *
     * @return exactly {@link #TRAN_CARD_NUM_LENGTH} characters, untrimmed and unmasked
     */
    public String tranCardNum() {
        return codec.readPicX(area, TRAN_CARD_NUM);
    }

    /**
     * {@code TRAN-ORIG-TS PIC X(26)} - at 0-based offset 278.
     *
     * <p>A character timestamp, not a parsed date: {@code app/cbl/CBACT04C.cbl:497} moves a
     * {@code DB2-FORMAT-TS PIC X(26)} straight in, and the fixtures hold
     * {@code 2022-06-10 19:27:53.000000}. Keeping it as text preserves the exact 26 stored bytes,
     * including any that are spaces.
     *
     * @return exactly {@link #TRAN_ORIG_TS_LENGTH} characters, untrimmed
     */
    public String tranOrigTs() {
        return codec.readPicX(area, TRAN_ORIG_TS);
    }

    /**
     * {@code TRAN-PROC-TS PIC X(26)} - at 0-based offset 304.
     *
     * <p>Blank until the transaction is processed: every row of {@code app/data/ASCII/dailytran.txt}
     * holds 26 spaces here, because that fixture is the unprocessed daily-transaction twin of this
     * layout. {@code app/cbl/CBACT04C.cbl:498} fills it when the record is created.
     *
     * @return exactly {@link #TRAN_PROC_TS_LENGTH} characters, untrimmed
     */
    public String tranProcTs() {
        return codec.readPicX(area, TRAN_PROC_TS);
    }

    /**
     * {@code TRAN-PROC-DT} - the first ten characters of {@code TRAN-PROC-TS}, the report's date key.
     *
     * <p>{@code app/cbl/CBTRN03C.cbl:173-174} filters on {@code TRAN-PROC-TS (1:10)} and
     * {@code app/jcl/TRANREPT.jcl:42} sorts on {@code TRAN-PROC-DT,305,10,CH} - the same ten bytes
     * addressed two ways. COBOL reference modification is 1-based, so {@code (1:10)} is
     * {@code substring(0, 10)} in Java; this accessor exists so that conversion is written once and
     * named, rather than repeated at every call site.
     *
     * @return exactly {@link #TRAN_PROC_DT_LENGTH} characters, untrimmed
     */
    public String tranProcDt() {
        return area.readString(TRAN_PROC_DT_OFFSET, TRAN_PROC_DT_LENGTH);
    }

    /**
     * The trailing {@code FILLER PIC X(20)} - at 0-based offset 330.
     *
     * <p>Not referable by name in COBOL, but exposed here so a test can prove the twenty reserved bytes
     * are present and space-filled rather than absent. If they were omitted the record would be 330
     * bytes and {@link #LAYOUT} could not have been constructed at all.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return area.readSpan(FILLER);
    }

    // =================================================================================================
    // Write path 1 of 2 - COBOL MOVE semantics. Every one of these pads and, where necessary, truncates
    // to the receiver's declared width. The direction of truncation is not a detail: COBOL fills a
    // PIC X receiver from its leftmost position and discards what does not fit on the RIGHT, but aligns
    // a PIC 9 receiver on the implied decimal point and discards high-order digits on the LEFT. Both
    // rules live in FixedWidthCodec.movePicX / movePic9, which is why no assignment is ever written
    // directly against the area here.
    // =================================================================================================

    /**
     * {@code MOVE ... TO TRAN-ID} - pads or right-truncates to {@link #TRAN_ID_LENGTH}.
     *
     * @param value the sending value; may be shorter or longer than the receiver
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranId(String value) {
        codec.writePicX(area, TRAN_ID, value);
    }

    /**
     * {@code MOVE ... TO TRAN-TYPE-CD} - pads or right-truncates to {@link #TRAN_TYPE_CD_LENGTH}.
     *
     * <p>Reproduces {@code MOVE '01' TO TRAN-TYPE-CD} at {@code app/cbl/CBACT04C.cbl:482}. Because the
     * receiver is {@code PIC X(02)} this is an ordinary character move, unlike the numerically aligned
     * move performed by {@link #moveTranCatCd(String)} on the very next source line.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranTypeCd(String value) {
        codec.writePicX(area, TRAN_TYPE_CD, value);
    }

    /**
     * {@code MOVE ... TO TRAN-SOURCE} - pads or right-truncates to {@link #TRAN_SOURCE_LENGTH}.
     *
     * <p>Reproduces {@code MOVE 'System' TO TRAN-SOURCE} at {@code app/cbl/CBACT04C.cbl:484}, which
     * stores {@code "System    "}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranSource(String value) {
        codec.writePicX(area, TRAN_SOURCE, value);
    }

    /**
     * {@code MOVE ... TO TRAN-DESC} - pads or right-truncates to {@link #TRAN_DESC_LENGTH}.
     *
     * <p>This blanks the whole 100-byte field before writing, which is what {@code MOVE} does and what
     * {@link #stringIntoTranDesc(String...)} deliberately does not.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranDesc(String value) {
        codec.writePicX(area, TRAN_DESC, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-NAME} - pads or right-truncates to
     * {@link #TRAN_MERCHANT_NAME_LENGTH}.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:492} moves {@code SPACES} here, which this reproduces when passed
     * an empty or blank value.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranMerchantName(String value) {
        codec.writePicX(area, TRAN_MERCHANT_NAME, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-CITY} - pads or right-truncates to
     * {@link #TRAN_MERCHANT_CITY_LENGTH}. {@code app/cbl/CBACT04C.cbl:493} moves {@code SPACES} here.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranMerchantCity(String value) {
        codec.writePicX(area, TRAN_MERCHANT_CITY, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-ZIP} - pads or right-truncates to
     * {@link #TRAN_MERCHANT_ZIP_LENGTH}. {@code app/cbl/CBACT04C.cbl:494} moves {@code SPACES} here.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranMerchantZip(String value) {
        codec.writePicX(area, TRAN_MERCHANT_ZIP, value);
    }

    /**
     * {@code MOVE ... TO TRAN-CARD-NUM} - pads or right-truncates to {@link #TRAN_CARD_NUM_LENGTH}.
     *
     * <p>Reproduces {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM} at {@code app/cbl/CBACT04C.cbl:495},
     * an exact {@code X(16)} to {@code X(16)} move that neither pads nor truncates. The value is stored
     * whole and unmasked.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranCardNum(String value) {
        codec.writePicX(area, TRAN_CARD_NUM, value);
    }

    /**
     * {@code MOVE ... TO TRAN-ORIG-TS} - pads or right-truncates to {@link #TRAN_ORIG_TS_LENGTH}.
     *
     * <p>Reproduces {@code MOVE DB2-FORMAT-TS TO TRAN-ORIG-TS} at {@code app/cbl/CBACT04C.cbl:497};
     * {@code DB2-FORMAT-TS} is {@code PIC X(26)} ({@code :150}), so it fills the field exactly.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranOrigTs(String value) {
        codec.writePicX(area, TRAN_ORIG_TS, value);
    }

    /**
     * {@code MOVE ... TO TRAN-PROC-TS} - pads or right-truncates to {@link #TRAN_PROC_TS_LENGTH}.
     *
     * <p>Reproduces {@code MOVE DB2-FORMAT-TS TO TRAN-PROC-TS} at {@code app/cbl/CBACT04C.cbl:498}.
     * Writing this field also sets {@link #tranProcDt()}, since the date key is its first ten
     * characters.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranProcTs(String value) {
        codec.writePicX(area, TRAN_PROC_TS, value);
    }

    /**
     * {@code MOVE ... TO TRAN-CAT-CD} from a numeric sender - zero-fills on the left to
     * {@link #TRAN_CAT_CD_LENGTH}, discarding high-order digits if the value is too wide.
     *
     * @param value the sending value; must not be negative, because the receiver is unsigned
     *              {@code PIC 9(04)} and has nowhere to record a sign
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveTranCatCd(int value) {
        codec.writePic9(area, TRAN_CAT_CD, value);
    }

    /**
     * {@code MOVE ... TO TRAN-CAT-CD} from an alphanumeric sender - the case
     * {@code app/cbl/CBACT04C.cbl:483} exercises with {@code MOVE '05' TO TRAN-CAT-CD}.
     *
     * <p>This is a genuinely surprising COBOL rule and getting it wrong is a silent hundredfold error.
     * The receiver is {@code PIC 9(04)}, so IBM Enterprise COBOL treats the alphanumeric sender as
     * though it were described as an <em>unsigned integer</em> and then performs a numeric move: the
     * value is aligned on the implied decimal point and zero-filled on the left. {@code '05'} therefore
     * stores {@code 0005}, <strong>not</strong> {@code 0500} as a character move into a left-justified
     * field would give. The same rule requires every character of an alphanumeric literal sender to be
     * a digit, which is why a non-digit here is rejected rather than coerced.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException     if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveTranCatCd(String digits) {
        codec.writePic9(area, TRAN_CAT_CD, digits);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-ID} from a numeric sender - zero-fills on the left to
     * {@link #TRAN_MERCHANT_ID_LENGTH}.
     *
     * <p>Reproduces {@code MOVE 0 TO TRAN-MERCHANT-ID} at {@code app/cbl/CBACT04C.cbl:491}, which
     * stores {@code 000000000} rather than nine spaces.
     *
     * @param value the sending value; must not be negative
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveTranMerchantId(long value) {
        codec.writePic9(area, TRAN_MERCHANT_ID, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-ID} from an alphanumeric sender - treated as an unsigned
     * integer, aligned on the implied decimal point and zero-filled on the left, exactly as described
     * on {@link #moveTranCatCd(String)}.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException     if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveTranMerchantId(String digits) {
        codec.writePic9(area, TRAN_MERCHANT_ID, digits);
    }

    /**
     * {@code MOVE ... TO TRAN-AMT} - stores the amount at scale exactly {@link #TRAN_AMT_SCALE} with a
     * zoned sign overpunch in the trailing byte.
     *
     * <p>Reproduces {@code MOVE WS-MONTHLY-INT TO TRAN-AMT} at {@code app/cbl/CBACT04C.cbl:490}. There
     * the sender is also {@code PIC S9(09)V99} ({@code :168}), so no scale change occurs; the
     * truncation applied here matters when a caller supplies a value carrying more fraction digits than
     * the field can hold.
     *
     * <p>Excess fraction digits are <strong>truncated, never rounded</strong>. COBOL rounds only when a
     * statement says {@code ROUNDED}, and that keyword appears nowhere in any of the programs this
     * migration covers, so the faithful policy is {@link java.math.RoundingMode#DOWN}. The value is put
     * through {@link CobolDecimal#storeMonetary(BigDecimal)} before it reaches the codec so that the
     * scale and the rounding mode are named on the path a caller can actually read.
     *
     * @param value the amount to store; may be negative, and its scale may exceed the field's
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value has more integer digits than
     *                                  {@link #TRAN_AMT_INTEGER_DIGITS}
     */
    public void moveTranAmt(BigDecimal value) {
        Objects.requireNonNull(value, "An amount is required to store into TRAN-AMT; use "
                + "moveTranAmt(CobolDecimal.monetaryZero()) to store a positive zero");
        codec.writeMonetary(area, TRAN_AMT, CobolDecimal.storeMonetary(value));
    }

    /**
     * Stores a pre-built {@code TRAN-AMT} image verbatim, sign overpunch and all.
     *
     * <p>The byte-faithful counterpart to {@link #moveTranAmt(BigDecimal)}, and the only way to carry a
     * whole-value negative zero from one record to another: {@code 0000000000}<code>&#125;</code>
     * decodes to plain {@code 0.00} and a numeric write would store it back as
     * {@code 0000000000}<code>&#123;</code>. Pair this with {@link #tranAmtImage()} to copy the field
     * unchanged whatever it holds.
     *
     * @param image exactly {@link #TRAN_AMT_LENGTH} characters - ten leading digits followed by a digit
     *              or a zoned overpunch character
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #TRAN_AMT_LENGTH}
     *                                  characters
     */
    public void writeTranAmtImage(String image) {
        Objects.requireNonNull(image, "A TRAN-AMT image is required");
        if (image.length() != TRAN_AMT_LENGTH) {
            throw new IllegalArgumentException("TRAN-AMT image '" + image + "' is "
                    + image.length() + " character(s) but PIC S9(09)V99 occupies exactly "
                    + TRAN_AMT_LENGTH + "; the sign is overpunched into the trailing byte rather than "
                    + "stored separately, so the image is never padded or truncated here");
        }
        area.writeSpan(TRAN_AMT, image);
    }

    // =================================================================================================
    // Write path 2 of 2 - COBOL STRING ... DELIMITED BY SIZE INTO semantics. These write only the bytes
    // supplied, starting at the span's first byte, and LEAVE THE REST OF THE FIELD EXACTLY AS IT WAS.
    // That is not an optimisation; it is the observable behaviour of the verb. CBACT04C:485-489 strings
    // 24 characters into TRAN-DESC PIC X(100) and bytes 25-100 keep their prior content, so a padding
    // setter cannot reproduce that record. Offered for every PIC X field, and for no numeric field:
    // STRING requires an alphanumeric receiver, and none of the three numeric spans is one.
    // =================================================================================================

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-ID} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * <p>Reproduces {@code app/cbl/CBACT04C.cbl:476-480}, which strings the 10-character
     * {@code PARM-DATE} onto the 6-digit {@code WS-TRANID-SUFFIX}. Those 16 characters fill
     * {@code X(16)} exactly, so nothing is left over - but the verb, not the arithmetic, is what is
     * being reproduced here.
     *
     * @param operands the sending items in order, each already at its own declared width
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranId(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_ID, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-TYPE-CD} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranTypeCd(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_TYPE_CD, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-SOURCE} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranSource(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_SOURCE, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-DESC} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * <p>The canonical case. {@code app/cbl/CBACT04C.cbl:485-489} strings the 13-character literal
     * {@code 'Int. for a/c '} onto an 11-digit account id - 24 characters into a 100-byte field - and
     * bytes 25 to 100 keep whatever the record area already held. Use {@link #moveTranDesc(String)}
     * instead when the field really should be blanked first.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranDesc(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_DESC, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-MERCHANT-NAME} - overlays from the field's first
     * byte and leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranMerchantName(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_MERCHANT_NAME, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-MERCHANT-CITY} - overlays from the field's first
     * byte and leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranMerchantCity(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_MERCHANT_CITY, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-MERCHANT-ZIP} - overlays from the field's first
     * byte and leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranMerchantZip(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_MERCHANT_ZIP, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-CARD-NUM} - overlays from the field's first byte
     * and leaves the remainder untouched. The value is stored whole and unmasked.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranCardNum(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_CARD_NUM, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-ORIG-TS} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranOrigTs(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_ORIG_TS, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-PROC-TS} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException     if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranProcTs(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_PROC_TS, operands);
    }

    // =================================================================================================
    // Identity and diagnostics.
    // =================================================================================================

    /**
     * Two records are equal when they hold identical bytes under the same code page.
     *
     * <p>Byte equality rather than field-by-field value equality, because that is the COBOL notion of
     * one record being the same as another and because it is strictly the stronger test: the
     * images {@code 0000000000}<code>&#123;</code> and {@code 0000000000}<code>&#125;</code> have equal
     * numeric values but are different records, and treating them as equal is exactly the parity defect
     * this class exists to prevent. The charset participates because the same bytes mean different
     * data under different code pages.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code TranRecord} with the same charset and the
     *         same 350 bytes
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranRecord that)) {
            return false;
        }
        return area.charset().equals(that.area.charset())
                && Arrays.equals(area.toByteArray(), that.area.toByteArray());
    }

    /**
     * Consistent with {@link #equals(Object)}: derived from the charset and the record bytes.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(area.charset(), Arrays.hashCode(area.toByteArray()));
    }

    /**
     * A field-by-field rendering for diagnostics and test failure messages.
     *
     * <p>Every field is shown by its copybook name and at its full stored width, so a difference in
     * trailing spaces is visible rather than hidden. {@code TRAN-AMT} is given both as its raw zoned
     * image and as its decoded value, because the two can differ in the one way that matters - a
     * whole-value negative zero. {@code TRAN-CARD-NUM} is shown in full and unmasked, deliberately:
     * this type models a record whose consuming programs display it in the clear, and abbreviating it
     * here would make a byte-level comparison unreproducible from the diagnostic.
     *
     * <p>For the exact {@code SYSOUT} text of {@code DISPLAY TRAN-RECORD} use {@link #displayImage()}
     * instead; this rendering is annotated and is not a record image.
     *
     * @return a single-line description of every field
     */
    @Override
    public String toString() {
        return "TranRecord["
                + "charset=" + area.charset().name()
                + ", TRAN-ID='" + tranId() + '\''
                + ", TRAN-TYPE-CD='" + tranTypeCd() + '\''
                + ", TRAN-CAT-CD='" + tranCatCdImage() + '\''
                + ", TRAN-SOURCE='" + tranSource() + '\''
                + ", TRAN-DESC='" + tranDesc() + '\''
                + ", TRAN-AMT='" + tranAmtImage() + "'=" + tranAmt()
                + ", TRAN-MERCHANT-ID='" + tranMerchantIdImage() + '\''
                + ", TRAN-MERCHANT-NAME='" + tranMerchantName() + '\''
                + ", TRAN-MERCHANT-CITY='" + tranMerchantCity() + '\''
                + ", TRAN-MERCHANT-ZIP='" + tranMerchantZip() + '\''
                + ", TRAN-CARD-NUM='" + tranCardNum() + '\''
                + ", TRAN-ORIG-TS='" + tranOrigTs() + '\''
                + ", TRAN-PROC-TS='" + tranProcTs() + '\''
                + ", FILLER='" + filler() + '\''
                + ']';
    }

    // =================================================================================================
    // Argument checks. Kept as named helpers so the reason each one exists is stated once.
    // =================================================================================================

    /**
     * Insists a charset was supplied. There is no default: the pad bytes, the digits and the twenty
     * zoned sign overpunch characters are all code-page dependent, so a fixed-width record addressed by
     * absolute byte offset can never be built against an assumed encoding.
     */
    private static Charset requireCharset(Charset charset) {
        return Objects.requireNonNull(charset, "A charset must be supplied explicitly for a "
                + "TRAN-RECORD: US-ASCII for the app/data/ASCII fixtures, IBM037 for EBCDIC datasets. "
                + "The platform default is never used, because the sign overpunch bytes differ between "
                + "code pages");
    }

    /**
     * Insists a span descriptor is one this record declares. A descriptor borrowed from another
     * copybook could name a valid offset and width yet address entirely the wrong bytes, and a silent
     * wrong read is the hardest kind of parity defect to trace back to its cause.
     */
    private static FieldSpan requireDeclaredSpan(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required; use one of TranRecord's "
                + "declared span constants");
        if (!LAYOUT.spans().contains(field)) {
            throw new IllegalArgumentException("Field " + field.describe() + " is not declared by "
                    + "CVTRA05Y's layout. Use one of TranRecord's own span constants, so that the "
                    + "offset read is always one this copybook actually declares");
        }
        return field;
    }
}
