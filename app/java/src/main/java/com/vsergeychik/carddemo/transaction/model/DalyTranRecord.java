package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The daily transaction record, {@code 01 DALYTRAN-RECORD}, transcribed from
 * {@code app/cpy/CVTRA06Y.cpy} whose own header comment declares
 * {@code Data-structure for DALYTRANsaction record (RECLN = 350)}.
 *
 * <p>This is the single Java type for that copybook. It models the unprocessed input side of
 * transaction posting: the {@code DALYTRAN} dataset is read, each record is validated, and each is
 * then either posted to the transaction master or copied verbatim onto the rejects file.
 *
 * <h2>Consuming programs</h2>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} - the {@code POSTTRAN} poster and validator. {@code COPY
 *       CVTRA06Y} at {@code :102}. It reads this record, runs the four-stage validation cascade over
 *       it, and on failure copies its whole 350-byte image onto the rejects file at {@code :447}.</li>
 *   <li>{@code app/cbl/CBTRN01C.cbl} - the daily transaction poster that no JCL invokes. {@code COPY
 *       CVTRA06Y} at {@code :99}. It is an orphan by inspection - no {@code EXEC PGM=CBTRN01C}
 *       exists anywhere in {@code app/jcl/} or {@code app/proc/} - and it migrates as a fully
 *       runnable job with no trigger rather than being deleted.</li>
 * </ul>
 *
 * <p>Both programs open the dataset the same way: {@code ORGANIZATION IS SEQUENTIAL} and
 * {@code ACCESS MODE IS SEQUENTIAL} ({@code CBTRN02C.cbl:29-32}, {@code CBTRN01C.cbl:29-32}), and
 * {@code app/jcl/POSTTRAN.jcl:30-31} binds the DD to {@code AWS.M2.CARDDEMO.DALYTRAN.PS}. It is a
 * sequential physical-sequential dataset, <strong>not</strong> a KSDS: there is no record key, no
 * keyed read, and no rewrite. This type therefore declares no key constant, even though its first
 * sixteen bytes happen to align with the {@code TRANSACT} KSDS key that the posted record carries.
 *
 * <h2>Byte layout - 1-based COBOL position to 0-based Java offset</h2>
 * <pre>
 * #   COBOL item                 PICTURE       1-based    0-based  Len  Java
 * --  -------------------------  ------------  ---------  -------  ---  ---------------------
 *  1  DALYTRAN-ID                X(16)           1- 16         0   16  String
 *  2  DALYTRAN-TYPE-CD           X(02)          17- 18        16    2  String
 *  3  DALYTRAN-CAT-CD            9(04)          19- 22        18    4  int
 *  4  DALYTRAN-SOURCE            X(10)          23- 32        22   10  String
 *  5  DALYTRAN-DESC              X(100)         33-132        32  100  String
 *  6  DALYTRAN-AMT               S9(09)V99     133-143       132   11  BigDecimal scale 2
 *  7  DALYTRAN-MERCHANT-ID       9(09)         144-152       143    9  int
 *  8  DALYTRAN-MERCHANT-NAME     X(50)         153-202       152   50  String
 *  9  DALYTRAN-MERCHANT-CITY     X(50)         203-252       202   50  String
 * 10  DALYTRAN-MERCHANT-ZIP      X(10)         253-262       252   10  String
 * 11  DALYTRAN-CARD-NUM          X(16)         263-278       262   16  String
 * 12  DALYTRAN-ORIG-TS           X(26)         279-304       278   26  String
 * 13  DALYTRAN-PROC-TS           X(26)         305-330       304   26  String
 * 14  FILLER                     X(20)         331-350       330   20  reserved span
 * --  -------------------------  ------------  ---------  -------  ---
 *     16+2+4+10+100+11+9+50+50+10+16+26+26+20                     350
 * </pre>
 *
 * <p>A signed {@code S9(p)V(s)} span occupies {@code p + s} bytes, not {@code p + s + 1}: the
 * operational sign is <em>overpunched into the trailing byte</em> rather than stored separately.
 * {@code DALYTRAN-AMT} is therefore 11 bytes wide - nine integer digits plus two fraction digits -
 * and treating it as 9 or 12 would shift every field after it. The copybook declares no
 * {@code SIGN SEPARATE} clause, no {@code USAGE} and no {@code COMP-3}, so the field is zoned
 * {@code DISPLAY}.
 *
 * <h2>The one asymmetry in the naming</h2>
 * <p>Thirteen of the fourteen items carry a {@code DALYTRAN-} prefix. The fourteenth does not: it is
 * declared as plain {@code FILLER PIC X(20)}, <strong>not</strong> {@code DALYTRAN-FILLER}. That is
 * reproduced verbatim rather than regularised, because {@code FILLER} is the name the span is
 * reported under and a field-by-field comparison is keyed on names.
 *
 * <h2>Three independent confirmations of this layout</h2>
 * <ol>
 *   <li><strong>The consuming program's own FD split.</strong> {@code app/cbl/CBTRN02C.cbl:66-69}
 *       divides this dataset's record as {@code FD-TRAN-ID PIC X(16)} plus
 *       {@code FD-CUST-DATA PIC X(334)}, which sums to 350 and independently confirms both the
 *       total width and that the first field is sixteen bytes.</li>
 *   <li><strong>The rejects record it is copied into.</strong> {@code app/cbl/CBTRN02C.cbl:176-178}
 *       declares {@code REJECT-TRAN-DATA PIC X(350)} plus {@code VALIDATION-TRAILER PIC X(80)}, and
 *       {@code :447} moves the whole {@code DALYTRAN-RECORD} group into the first of those. The
 *       receiver is exactly 350 bytes wide, and {@code app/jcl/POSTTRAN.jcl:34-38} declares the
 *       dataset it is written to as {@code DCB=(RECFM=F,LRECL=430)} - 350 + 80. A record of any
 *       other width would be truncated or padded by that move.</li>
 *   <li><strong>A clean fixture decode.</strong> {@code app/data/ASCII/dailytran.txt} holds exactly
 *       300 rows of exactly 350 bytes, and this table decodes every field of every row cleanly. Row
 *       1 yields {@code DALYTRAN-ID} {@code 0000000000683580}, {@code DALYTRAN-TYPE-CD} {@code 01},
 *       {@code DALYTRAN-CAT-CD} {@code 0001}, {@code DALYTRAN-SOURCE} {@code "POS TERM  "},
 *       {@code DALYTRAN-DESC} {@code Purchase at Abshire-Lowe} then spaces, {@code DALYTRAN-AMT}
 *       image {@code 0000005047G} (= 504.77), {@code DALYTRAN-MERCHANT-ID} {@code 800000000},
 *       {@code DALYTRAN-MERCHANT-NAME} {@code Abshire-Lowe}, {@code DALYTRAN-MERCHANT-CITY}
 *       {@code North Enoshaven}, {@code DALYTRAN-MERCHANT-ZIP} {@code "72112     "},
 *       {@code DALYTRAN-CARD-NUM} {@code 4859452612877065}, {@code DALYTRAN-ORIG-TS}
 *       {@code 2022-06-10 19:27:53.000000}, {@code DALYTRAN-PROC-TS} twenty-six spaces - the daily
 *       file is unprocessed - and {@code FILLER} twenty spaces.</li>
 * </ol>
 *
 * <h2>Provenance of the expected values</h2>
 * <p>The legacy COBOL cannot be executed in this environment, so every expectation asserted against
 * this type is <em>statically derived</em>: from the copybook's own {@code PICTURE} clauses, from the
 * JCL {@code DCB} and {@code DSN} declarations, from the consuming programs' {@code FD} splits and
 * {@code WORKING-STORAGE} receivers, and from the real ASCII fixture. That is a deliberate,
 * documented substitution for a captured execution baseline rather than an oversight, and it is why
 * the three confirmations above are recorded here in the source: they are the audit trail for the
 * offsets.
 *
 * <h2>Why this type duplicates another one, and must keep doing so</h2>
 * <p>{@code app/cpy/CVTRA05Y.cpy} declares {@code 01 TRAN-RECORD}: the same fourteen items, in the
 * same order, with the same {@code PICTURE} clauses and the same 350-byte total, differing only in
 * that thirteen of its names carry a {@code TRAN-} prefix where these carry {@code DALYTRAN-}. It is
 * modelled separately as {@code TranRecord}, and {@code CBTRN02C} copies <em>both</em> copybooks
 * ({@code :102} and {@code :107}) precisely so it can hold a record of each at once and move field by
 * field between them ({@code :425-436}).
 *
 * <p>The duplication between the two Java types is therefore <strong>deliberate and must not be
 * removed.</strong> This class does not extend, wrap, alias or genericise the other, and the two
 * share no base class, no interface and no name table. Practice B5 of the migration plan - dead and
 * duplicated structure is preserved rather than tidied up - is the governing rule, and the concrete
 * reason is the verification gate: a field-by-field differ reports findings <em>by field name</em>,
 * so a shared implementation would report a difference against {@code TRAN-AMT} where the expected
 * value is recorded under {@code DALYTRAN-AMT}. Every such comparison would count as a difference,
 * and the requirement that the difference count reach zero before a module is accepted could never
 * be met. Unifying these two types would trade a little repetition for an unverifiable migration.
 *
 * <h2>The negative-zero re-encode hazard</h2>
 * <p>{@code DALYTRAN-AMT}'s trailing byte carries the sign as a zoned overpunch: one character
 * encodes both the final digit and the sign of the whole value. Across the 300 rows of
 * {@code app/data/ASCII/dailytran.txt} that byte takes every legal value -
 * <code>'&#123;'</code> and {@code 'A'}-{@code 'I'} for a positive final digit 0-9 (25 and 225 rows),
 * <code>'&#125;'</code> and {@code 'J'}-{@code 'R'} for a negative final digit 0-9 (6 and 44 rows),
 * so 50 of the 300 amounts are negative.
 *
 * <p>The distinction that matters is between the <em>character</em> and the <em>value</em>. A
 * <code>'&#125;'</code> means "negative, final digit zero"; it does not mean the amount is zero.
 * Every one of the six <code>'&#125;'</code> rows in the fixture holds a substantial negative amount
 * - {@code 0000009190}<code>&#125;</code> is {@code -919.00} - and each survives a decode and
 * re-encode unchanged, because {@code -919.00} legitimately ends in a negative zero digit.
 *
 * <p>The hazard is the one image where the <em>whole value</em> is a negative zero:
 * {@code 0000000000}<code>&#125;</code>. Java's {@link BigDecimal} has no signed zero, so that image
 * decodes to plain {@code 0.00}, and re-encoding {@code 0.00} yields
 * {@code 0000000000}<code>&#123;</code> - a silent one-byte difference that a comparison of numeric
 * values could never detect but a comparison of record images would fail on. That exact image does
 * not occur in this fixture, which is precisely why it is dangerous: it is representable in the
 * field, it would arrive from a production dataset without warning, and no test built only from the
 * fixture would catch it.
 *
 * <p>The consequence is a hard rule for callers, and the reason this type keeps its backing span:
 * <strong>a verbatim copy of a record image must copy the raw bytes, never round-trip through the
 * decoded fields.</strong> That rule is not hypothetical here - it is exactly what
 * {@code CBTRN02C.cbl:447}'s {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} does, and what
 * {@code CBTRN01C.cbl:168}'s {@code DISPLAY DALYTRAN-RECORD} writes to {@code SYSOUT}. Use
 * {@link #rawImage()}, {@link #displayImage()}, {@link #encode(Charset)}, {@link #copy()} or
 * {@link #writeDalytranAmtImage(String)} for those. Reading {@link #dalytranAmt()} and writing it
 * back with {@link #moveDalytranAmt(BigDecimal)} is a numeric round-trip: faithful for every value
 * the fixture contains, but it normalises a whole-value negative zero to a positive one.
 *
 * <h2>One write path per character field, not two</h2>
 * <p>Each {@code PIC X} field offers a {@code moveXxx(String)} setter reproducing COBOL {@code MOVE}
 * - filled from the receiver's leftmost position, space-padded on the right when the sender is
 * shorter, truncated on the right when it is longer. No {@code STRING ... DELIMITED BY SIZE INTO}
 * path is offered, and that is a deliberate reading of the source rather than an omission: neither
 * consuming program contains a {@code STRING} statement targeting any {@code DALYTRAN-} item.
 * {@code DALYTRAN} is opened {@code INPUT} and read with {@code READ ... INTO} in both, so nothing
 * in the legacy code partially overlays one of these fields. The setters exist for the record's other
 * legitimate producer - a test or parity case assembling an input row - and adding an unused verb
 * path would be scope the source does not justify.
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
public final class DalyTranRecord {

    // =================================================================================================
    // Declared geometry. Every offset and every length is a separately named constant so that each one
    // can be audited line by line against app/cpy/CVTRA06Y.cpy, rather than being buried in an
    // expression. Offsets are absolute and 0-based; the copybook's own positions are 1-based, and the
    // Javadoc above tabulates both so the conversion is visible rather than assumed.
    // =================================================================================================

    /**
     * The declared record width in bytes, from the copybook's {@code RECLN = 350} header comment.
     *
     * <p>Corroborated twice over: {@code app/cbl/CBTRN02C.cbl:66-69} splits the record as
     * {@code X(16)} plus {@code X(334)}, and {@code :176-178} declares the
     * {@code REJECT-TRAN-DATA PIC X(350)} receiver that {@code :447} moves the whole group into.
     */
    public static final int RECORD_LENGTH = 350;

    /** {@code DALYTRAN-ID PIC X(16)} - 1-based 1-16, 0-based offset 0. */
    public static final int DALYTRAN_ID_OFFSET = 0;
    /** Width of {@code DALYTRAN-ID PIC X(16)}. */
    public static final int DALYTRAN_ID_LENGTH = 16;

    /** {@code DALYTRAN-TYPE-CD PIC X(02)} - 1-based 17-18, 0-based offset 16. */
    public static final int DALYTRAN_TYPE_CD_OFFSET = 16;
    /** Width of {@code DALYTRAN-TYPE-CD PIC X(02)}. */
    public static final int DALYTRAN_TYPE_CD_LENGTH = 2;

    /** {@code DALYTRAN-CAT-CD PIC 9(04)} - 1-based 19-22, 0-based offset 18. */
    public static final int DALYTRAN_CAT_CD_OFFSET = 18;
    /** Width of {@code DALYTRAN-CAT-CD PIC 9(04)}. */
    public static final int DALYTRAN_CAT_CD_LENGTH = 4;

    /** {@code DALYTRAN-SOURCE PIC X(10)} - 1-based 23-32, 0-based offset 22. */
    public static final int DALYTRAN_SOURCE_OFFSET = 22;
    /** Width of {@code DALYTRAN-SOURCE PIC X(10)}. */
    public static final int DALYTRAN_SOURCE_LENGTH = 10;

    /** {@code DALYTRAN-DESC PIC X(100)} - 1-based 33-132, 0-based offset 32. */
    public static final int DALYTRAN_DESC_OFFSET = 32;
    /** Width of {@code DALYTRAN-DESC PIC X(100)}. */
    public static final int DALYTRAN_DESC_LENGTH = 100;

    /** {@code DALYTRAN-AMT PIC S9(09)V99} - 1-based 133-143, 0-based offset 132. */
    public static final int DALYTRAN_AMT_OFFSET = 132;
    /**
     * Width of {@code DALYTRAN-AMT PIC S9(09)V99}: nine integer digits plus two fraction digits, with
     * the sign overpunched into the trailing byte rather than occupying a byte of its own. Eleven, not
     * nine and not twelve.
     */
    public static final int DALYTRAN_AMT_LENGTH = 11;
    /** {@code p} of {@code DALYTRAN-AMT PIC S9(09)V99} - the digits before the implied point. */
    public static final int DALYTRAN_AMT_INTEGER_DIGITS = 9;
    /**
     * {@code s} of {@code DALYTRAN-AMT PIC S9(09)V99} - the digits after the implied decimal point.
     * Taken from {@link CobolDecimal#MONETARY_SCALE} rather than restated as a literal, because every
     * monetary field in this codebase is scale 2 and the policy belongs in one place.
     */
    public static final int DALYTRAN_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /** {@code DALYTRAN-MERCHANT-ID PIC 9(09)} - 1-based 144-152, 0-based offset 143. */
    public static final int DALYTRAN_MERCHANT_ID_OFFSET = 143;
    /** Width of {@code DALYTRAN-MERCHANT-ID PIC 9(09)}. */
    public static final int DALYTRAN_MERCHANT_ID_LENGTH = 9;

    /** {@code DALYTRAN-MERCHANT-NAME PIC X(50)} - 1-based 153-202, 0-based offset 152. */
    public static final int DALYTRAN_MERCHANT_NAME_OFFSET = 152;
    /** Width of {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    public static final int DALYTRAN_MERCHANT_NAME_LENGTH = 50;

    /** {@code DALYTRAN-MERCHANT-CITY PIC X(50)} - 1-based 203-252, 0-based offset 202. */
    public static final int DALYTRAN_MERCHANT_CITY_OFFSET = 202;
    /** Width of {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    public static final int DALYTRAN_MERCHANT_CITY_LENGTH = 50;

    /** {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} - 1-based 253-262, 0-based offset 252. */
    public static final int DALYTRAN_MERCHANT_ZIP_OFFSET = 252;
    /** Width of {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final int DALYTRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * {@code DALYTRAN-CARD-NUM PIC X(16)} - 1-based 263-278, 0-based offset 262.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:382} moves this field into {@code FD-XREF-CARD-NUM PIC X(16)}
     * and {@code app/cbl/CBTRN01C.cbl:171} into {@code XREF-CARD-NUM}, both exact same-width moves
     * that confirm the sixteen-byte span.
     */
    public static final int DALYTRAN_CARD_NUM_OFFSET = 262;
    /** Width of {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    public static final int DALYTRAN_CARD_NUM_LENGTH = 16;

    /** {@code DALYTRAN-ORIG-TS PIC X(26)} - 1-based 279-304, 0-based offset 278. */
    public static final int DALYTRAN_ORIG_TS_OFFSET = 278;
    /** Width of {@code DALYTRAN-ORIG-TS PIC X(26)}. */
    public static final int DALYTRAN_ORIG_TS_LENGTH = 26;

    /**
     * The reference-modified slice {@code DALYTRAN-ORIG-TS (1:10)} - the first ten characters of the
     * originating timestamp, which are its date - so the same 0-based offset as the field itself.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:414} compares it against {@code ACCT-EXPIRAION-DATE}, which is
     * {@code PIC X(10)}, to decide whether a transaction arrived after the account expired. COBOL
     * reference modification is 1-based, so {@code (1:10)} is {@code substring(0, 10)} in Java.
     */
    public static final int DALYTRAN_ORIG_DT_OFFSET = 278;
    /** Width of the {@code DALYTRAN-ORIG-TS (1:10)} slice, ten characters of {@code yyyy-MM-dd}. */
    public static final int DALYTRAN_ORIG_DT_LENGTH = 10;

    /** {@code DALYTRAN-PROC-TS PIC X(26)} - 1-based 305-330, 0-based offset 304. */
    public static final int DALYTRAN_PROC_TS_OFFSET = 304;
    /** Width of {@code DALYTRAN-PROC-TS PIC X(26)}. */
    public static final int DALYTRAN_PROC_TS_LENGTH = 26;

    /**
     * {@code FILLER PIC X(20)} - 1-based 331-350, 0-based offset 330. Note the name: the copybook
     * declares plain {@code FILLER} here, not {@code DALYTRAN-FILLER}, and that is reproduced as
     * declared.
     *
     * <p>Reserved, never referable by name in COBOL, and <strong>never optional</strong>: the
     * copybook's twenty trailing bytes are part of the 350-byte record, so they are declared as a
     * first-class span, space-filled on initialisation and emitted on every write. Omit them and the
     * record is 330 bytes, the whole-group move at {@code app/cbl/CBTRN02C.cbl:447} pads the rejects
     * image differently, and every consumer breaks.
     */
    public static final int FILLER_OFFSET = 330;
    /** Width of the trailing {@code FILLER PIC X(20)}. */
    public static final int FILLER_LENGTH = 20;

    // =================================================================================================
    // Field descriptors. Each is an immutable FieldSpan carrying the copybook name verbatim - hyphens
    // and the DALYTRAN- prefix included, and the trailing span named plain FILLER exactly as declared -
    // together with its offset, width and PICTURE category, so a caller never has to pair an offset with
    // a length by hand. FieldSpan is a record over String, int, enum and boolean components, so these
    // constants are deeply immutable.
    // =================================================================================================

    /** Descriptor for {@code DALYTRAN-ID PIC X(16)}. */
    public static final FieldSpan DALYTRAN_ID =
            FieldSpan.alphanumeric("DALYTRAN-ID", DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-TYPE-CD PIC X(02)}. Alphanumeric despite holding values that look
     * numeric ({@code '01'}), because the copybook declares {@code PIC X} - so leading zeros are stored
     * characters, not formatting.
     */
    public static final FieldSpan DALYTRAN_TYPE_CD = FieldSpan.alphanumeric(
            "DALYTRAN-TYPE-CD", DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH);

    /** Descriptor for {@code DALYTRAN-CAT-CD PIC 9(04)}, unsigned numeric {@code DISPLAY}. */
    public static final FieldSpan DALYTRAN_CAT_CD = FieldSpan.unsignedNumeric(
            "DALYTRAN-CAT-CD", DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH);

    /** Descriptor for {@code DALYTRAN-SOURCE PIC X(10)}. */
    public static final FieldSpan DALYTRAN_SOURCE = FieldSpan.alphanumeric(
            "DALYTRAN-SOURCE", DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH);

    /** Descriptor for {@code DALYTRAN-DESC PIC X(100)}. */
    public static final FieldSpan DALYTRAN_DESC = FieldSpan.alphanumeric(
            "DALYTRAN-DESC", DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-AMT PIC S9(09)V99}, signed zoned {@code DISPLAY} of
     * {@code DALYTRAN_AMT_INTEGER_DIGITS + DALYTRAN_AMT_SCALE} = 11 bytes.
     */
    public static final FieldSpan DALYTRAN_AMT = FieldSpan.signedScaled(
            "DALYTRAN-AMT", DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_INTEGER_DIGITS, DALYTRAN_AMT_SCALE);

    /** Descriptor for {@code DALYTRAN-MERCHANT-ID PIC 9(09)}, unsigned numeric {@code DISPLAY}. */
    public static final FieldSpan DALYTRAN_MERCHANT_ID = FieldSpan.unsignedNumeric(
            "DALYTRAN-MERCHANT-ID", DALYTRAN_MERCHANT_ID_OFFSET, DALYTRAN_MERCHANT_ID_LENGTH);

    /** Descriptor for {@code DALYTRAN-MERCHANT-NAME PIC X(50)}. */
    public static final FieldSpan DALYTRAN_MERCHANT_NAME = FieldSpan.alphanumeric(
            "DALYTRAN-MERCHANT-NAME", DALYTRAN_MERCHANT_NAME_OFFSET, DALYTRAN_MERCHANT_NAME_LENGTH);

    /** Descriptor for {@code DALYTRAN-MERCHANT-CITY PIC X(50)}. */
    public static final FieldSpan DALYTRAN_MERCHANT_CITY = FieldSpan.alphanumeric(
            "DALYTRAN-MERCHANT-CITY", DALYTRAN_MERCHANT_CITY_OFFSET, DALYTRAN_MERCHANT_CITY_LENGTH);

    /** Descriptor for {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}. */
    public static final FieldSpan DALYTRAN_MERCHANT_ZIP = FieldSpan.alphanumeric(
            "DALYTRAN-MERCHANT-ZIP", DALYTRAN_MERCHANT_ZIP_OFFSET, DALYTRAN_MERCHANT_ZIP_LENGTH);

    /** Descriptor for {@code DALYTRAN-CARD-NUM PIC X(16)}. */
    public static final FieldSpan DALYTRAN_CARD_NUM = FieldSpan.alphanumeric(
            "DALYTRAN-CARD-NUM", DALYTRAN_CARD_NUM_OFFSET, DALYTRAN_CARD_NUM_LENGTH);

    /** Descriptor for {@code DALYTRAN-ORIG-TS PIC X(26)}. */
    public static final FieldSpan DALYTRAN_ORIG_TS = FieldSpan.alphanumeric(
            "DALYTRAN-ORIG-TS", DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH);

    /** Descriptor for {@code DALYTRAN-PROC-TS PIC X(26)}. */
    public static final FieldSpan DALYTRAN_PROC_TS = FieldSpan.alphanumeric(
            "DALYTRAN-PROC-TS", DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH);

    /**
     * Descriptor for the trailing {@code FILLER PIC X(20)}. Declared through the dedicated
     * {@code filler} factory, which names the span {@code FILLER} - the copybook's own name for it -
     * and marks it as pad so it initialises to the charset's space byte.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete record layout, in copybook declaration order.
     *
     * <p>Constructing this is the record's width self-check, and it runs at class initialisation.
     * {@code RecordLayout} verifies that the spans start at offset 0, are contiguous with no gap and no
     * overlap, declare no duplicate referable name, and sum to exactly {@link #RECORD_LENGTH}. A
     * transcription error therefore fails loudly and immediately - the class cannot even load - rather
     * than surfacing later as a plausible-looking record with every field shifted. Note that this only
     * holds because {@code FILLER} is declared and because {@code DALYTRAN-AMT} is 11 bytes; drop
     * either and the sum is no longer 350.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(
            RECORD_LENGTH,
            DALYTRAN_ID,
            DALYTRAN_TYPE_CD,
            DALYTRAN_CAT_CD,
            DALYTRAN_SOURCE,
            DALYTRAN_DESC,
            DALYTRAN_AMT,
            DALYTRAN_MERCHANT_ID,
            DALYTRAN_MERCHANT_NAME,
            DALYTRAN_MERCHANT_CITY,
            DALYTRAN_MERCHANT_ZIP,
            DALYTRAN_CARD_NUM,
            DALYTRAN_ORIG_TS,
            DALYTRAN_PROC_TS,
            FILLER);

    // =================================================================================================
    // Instance state. The backing span is retained deliberately: it is what makes a byte-verbatim copy
    // of a stored record possible, which CBTRN02C:447's whole-group MOVE onto the rejects file and
    // CBTRN01C:168's DISPLAY of the whole record both require, and which the negative-zero hazard
    // documented on the class makes mandatory. Both fields are final, and both are bound to one charset
    // that the caller named explicitly - nothing here consults a platform default or a configuration
    // bean.
    // =================================================================================================

    /**
     * The 350-byte record area, preserved exactly as read. All reads and writes address it by absolute
     * offset through {@link #codec}; it is never rebuilt from decoded field values.
     */
    private final FixedWidthRecord area;

    /**
     * The codec bound to {@link #area}'s charset. Every pad, truncate and zoned-sign decision is
     * delegated to it, so none of those rules is reimplemented here.
     */
    private final FixedWidthCodec codec;

    /**
     * Allocates an initialised record area, the equivalent of declaring {@code 01 DALYTRAN-RECORD} in
     * {@code WORKING-STORAGE} and running {@code INITIALIZE} over it.
     *
     * <p>Character spans and the trailing {@code FILLER} are filled with the charset's space byte;
     * {@code DALYTRAN-CAT-CD}, {@code DALYTRAN-MERCHANT-ID} and {@code DALYTRAN-AMT} are filled with
     * its zero byte, so they read back as {@code 0000}, {@code 000000000} and {@code +0.00}
     * respectively rather than as invalid numeric {@code DISPLAY} data.
     *
     * @param charset the code page of the record's data, supplied explicitly. {@code US-ASCII} for the
     *                text fixtures and {@code IBM037} for EBCDIC datasets; it must encode the digits,
     *                the space and the ten positive and ten negative zoned overpunch characters to one
     *                byte each
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  characters a zoned fixed-width record requires
     */
    public DalyTranRecord(Charset charset) {
        this.codec = new FixedWidthCodec(requireCharset(charset));
        this.area = codec.newRecord(LAYOUT);
    }

    /**
     * Adopts an already-built area and its codec. Private because the two must agree on charset and on
     * record length, which the public entry points guarantee.
     */
    private DalyTranRecord(FixedWidthCodec codec, FixedWidthRecord area) {
        this.codec = codec;
        this.area = area;
    }

    /**
     * Reads a stored record, keeping its bytes verbatim.
     *
     * <p>The image is defensively copied and retained as the backing span, sign overpunches and all, so
     * a subsequent {@link #rawImage()} or {@link #encode(Charset)} reproduces the input byte for byte.
     * Field values are decoded on demand rather than eagerly, which is precisely what keeps a
     * negative-zero amount image intact all the way onto the rejects file.
     *
     * @param image   exactly {@link #RECORD_LENGTH} bytes as held on the {@code DALYTRAN} dataset
     * @param charset the code page the bytes are encoded in, supplied explicitly
     * @return a record over a copy of {@code image}
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH} bytes. A
     *                                  short row is rejected rather than tolerated, because silently
     *                                  accepting one would let every field offset drift; widen it
     *                                  deliberately with
     *                                  {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)} first
     */
    public static DalyTranRecord decode(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "Record bytes are required to decode a DALYTRAN-RECORD; use the "
                + "(Charset) constructor to allocate an empty 350-byte area instead");
        FixedWidthCodec codec = new FixedWidthCodec(requireCharset(charset));
        return new DalyTranRecord(codec, codec.wrap(image, LAYOUT));
    }

    /**
     * Reads a stored record from its text image, for the ASCII fixture and the parity cases.
     *
     * <p>A convenience over {@link #decode(byte[], Charset)} for {@code app/data/ASCII/dailytran.txt},
     * whose 300 rows are each exactly 350 characters. The charset is still explicit, because the
     * character-to-byte mapping of the zoned sign overpunches depends on it.
     *
     * @param image   the record's text image, exactly {@link #RECORD_LENGTH} characters under
     *                {@code charset}
     * @param charset the code page to encode {@code image} with, supplied explicitly
     * @return a record over the encoded bytes of {@code image}
     * @throws NullPointerException     if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes
     */
    public static DalyTranRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A record image is required to decode a DALYTRAN-RECORD");
        return decode(FixedWidthRecord.encodeText(image, requireCharset(charset),
                "a DALYTRAN-RECORD image"), charset);
    }

    /**
     * Duplicates this record by copying its backing bytes.
     *
     * <p>This is a byte-verbatim copy, not a field-by-field rebuild, so even a negative-zero
     * {@code DALYTRAN-AMT} image survives it unchanged. It is the faithful Java form of
     * {@code MOVE DALYTRAN-RECORD TO ...} for a 350-byte receiver, which is what
     * {@code app/cbl/CBTRN02C.cbl:447} performs when a transaction is rejected.
     *
     * @return an independent record holding identical bytes under the same charset
     */
    public DalyTranRecord copy() {
        return new DalyTranRecord(codec, codec.wrap(area.toByteArray(), LAYOUT));
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
     * declared spans really do account for every byte, with no span silently mis-sized and the trailing
     * {@code FILLER} genuinely present. The loud failure for a geometry error lives in
     * {@link #LAYOUT}'s own construction, which rejects gaps, overlaps and a wrong total at
     * class-initialisation time.
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
    // Verbatim image access. These are the only safe paths for copying a record, because they move bytes
    // rather than values, so they cannot normalise a negative-zero amount into a positive one. Both of
    // this record's whole-group consumers need them: the rejects copy and the SYSOUT display.
    // =================================================================================================

    /**
     * The complete 350-byte record image, verbatim.
     *
     * <p>This is the byte-for-byte content of the backing span, including the trailing {@code FILLER}
     * and including {@code DALYTRAN-AMT}'s sign overpunch exactly as stored.
     *
     * <p>It is the only correct source for the rejects copy. {@code app/cbl/CBTRN02C.cbl:447} performs
     * {@code MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA} - a whole-group move into a
     * {@code PIC X(350)} receiver - and {@code :451} then writes the 430-byte
     * {@code REJECT-RECORD} ({@code 350 + 80}) declared at {@code :176-178}. Rebuilding those 350 bytes
     * from decoded field values instead would risk a one-byte difference in the amount's sign
     * overpunch, and the rejects file is compared byte for byte.
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
     * <p>Reproduces {@code DISPLAY DALYTRAN-RECORD} at {@code app/cbl/CBTRN01C.cbl:168}, which writes
     * the whole group item to {@code SYSOUT} as characters - untrimmed, {@code FILLER} included, and
     * with {@code DALYTRAN-AMT} shown as its raw zoned image rather than as a formatted number. That
     * statement is active in {@code CBTRN01C}; the equivalent lines in {@code CBTRN02C} ({@code :207}
     * and {@code :349}) are commented out in the source and so emit nothing.
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
     * When it differs, the record is transcoded span by span at the <em>character</em> level rather than
     * by decoding and re-encoding field values, which means a negative-zero amount image survives a code
     * page change too.
     *
     * @param charset the target code page, supplied explicitly; it must encode every character currently
     *                held in the record to a single byte
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
     * bytes rather than a formatted value: the commented-out diagnostic at
     * {@code app/cbl/CBTRN02C.cbl:402}, {@code DISPLAY 'TRAN-AMT         :' DALYTRAN-AMT}, would emit
     * {@code 0000005047G} - sign overpunch and all - and reproducing such a {@code SYSOUT} line is
     * impossible from the decoded {@link BigDecimal} alone.
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
    // fixture really does hold trailing spaces that are part of the data - DALYTRAN-SOURCE is
    // "POS TERM  " with two of them, DALYTRAN-PROC-TS is twenty-six spaces on every row because the
    // daily file is unprocessed - and the rejects image is compared at full width. Trimming here would
    // break both the record width on write-back and that comparison.
    // =================================================================================================

    /**
     * {@code DALYTRAN-ID PIC X(16)} - at 0-based offset 0.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:425} moves it into {@code TRAN-ID}, the {@code TRANSACT} KSDS key,
     * and {@code app/cbl/CBTRN01C.cbl:183} displays it when a card cannot be verified. Sixteen bytes
     * either way, so neither move pads nor truncates.
     *
     * @return exactly {@link #DALYTRAN_ID_LENGTH} characters, untrimmed
     */
    public String dalytranId() {
        return codec.readPicX(area, DALYTRAN_ID);
    }

    /**
     * {@code DALYTRAN-TYPE-CD PIC X(02)} - at 0-based offset 16.
     *
     * <p>A {@code String} rather than a number because the copybook declares {@code PIC X}: the stored
     * {@code '01'} is two characters and its leading zero is data. {@code app/cbl/CBTRN02C.cbl:470}
     * moves it into {@code FD-TRANCAT-TYPE-CD} and {@code :506} into {@code TRANCAT-TYPE-CD}, both
     * {@code PIC X(02)} members of the transaction-category balance key, so the types on both sides must
     * agree exactly for that key to be built byte-identically.
     *
     * @return exactly {@link #DALYTRAN_TYPE_CD_LENGTH} characters, untrimmed
     */
    public String dalytranTypeCd() {
        return codec.readPicX(area, DALYTRAN_TYPE_CD);
    }

    /**
     * {@code DALYTRAN-CAT-CD PIC 9(04)} - at 0-based offset 18.
     *
     * <p>An {@code int} because the {@code PICTURE} is scale-free {@code 9}, so no decimal alignment and
     * no rounding policy applies; four digits cannot overflow. {@code app/cbl/CBTRN02C.cbl:471} moves it
     * into {@code FD-TRANCAT-CD} and {@code :507} into {@code TRANCAT-CD}, both {@code PIC 9(04)}, so an
     * integral type on both sides is what keeps the composite key aligned. For the stored digits
     * including leading zeros, use {@link #dalytranCatCdImage()}.
     *
     * @return the stored value as an integer, 0 to 9999
     * @throws IllegalArgumentException if the span does not hold four digits
     */
    public int dalytranCatCd() {
        return codec.readPic9AsInt(area, DALYTRAN_CAT_CD);
    }

    /**
     * The stored characters of {@code DALYTRAN-CAT-CD PIC 9(04)}, leading zeros included.
     *
     * @return exactly {@link #DALYTRAN_CAT_CD_LENGTH} characters, for example {@code 0001}
     */
    public String dalytranCatCdImage() {
        return area.readSpan(DALYTRAN_CAT_CD);
    }

    /**
     * {@code DALYTRAN-SOURCE PIC X(10)} - at 0-based offset 22.
     *
     * <p>Returned untrimmed on purpose: the fixture holds {@code "POS TERM  "} with two significant
     * trailing spaces, and {@code app/cbl/CBTRN02C.cbl:428} moves the field whole into
     * {@code TRAN-SOURCE PIC X(10)}, spaces included.
     *
     * @return exactly {@link #DALYTRAN_SOURCE_LENGTH} characters, untrimmed
     */
    public String dalytranSource() {
        return codec.readPicX(area, DALYTRAN_SOURCE);
    }

    /**
     * {@code DALYTRAN-DESC PIC X(100)} - at 0-based offset 32.
     *
     * @return exactly {@link #DALYTRAN_DESC_LENGTH} characters, untrimmed and space-padded as stored
     */
    public String dalytranDesc() {
        return codec.readPicX(area, DALYTRAN_DESC);
    }

    /**
     * {@code DALYTRAN-AMT PIC S9(09)V99} - at 0-based offset 132, decoded to a {@code BigDecimal} of
     * scale exactly {@link #DALYTRAN_AMT_SCALE}.
     *
     * <p>The trailing byte is a zoned sign overpunch. <code>'&#123;'</code> and {@code 'A'}-{@code 'I'}
     * carry a positive final digit 0-9, <code>'&#125;'</code> and {@code 'J'}-{@code 'R'} a negative
     * one, and a plain digit is read as positive. So the image {@code 0000005047G} decodes to
     * {@code 504.77}, and {@code 0000009190}<code>&#125;</code> to {@code -919.00}.
     *
     * <p>Never a {@code double} or a {@code float}: those cannot represent a decimal fraction exactly,
     * and a cent of drift is a parity failure. The scale is fixed by the {@code PICTURE} and the
     * rounding policy is truncation, both centralised in {@link CobolDecimal}.
     *
     * <p>This is the value three consuming statements need. {@code app/cbl/CBTRN02C.cbl:404-406}
     * computes {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT};
     * {@code :508} and {@code :527} do {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}; and {@code :547-551}
     * add it to {@code ACCT-CURR-BAL} and then test {@code IF DALYTRAN-AMT >= 0} to choose between the
     * cycle credit and cycle debit accumulator. Every one of those receivers is itself scale 2, so no
     * scale change occurs. The arithmetic belongs to the job that performs it, not to this record.
     *
     * <p><strong>One image does not survive a numeric round-trip.</strong> A trailing
     * <code>'&#125;'</code> means "negative, final digit zero", so it usually carries an ordinary
     * negative amount that re-encodes unchanged - all six such rows in the fixture do. But when the
     * entire magnitude is zero - {@code 0000000000}<code>&#125;</code>, a negative zero - this returns
     * plain {@code 0.00}, because {@link BigDecimal} has no signed zero, and writing that back with
     * {@link #moveDalytranAmt(BigDecimal)} stores {@code 0000000000}<code>&#123;</code>. Copy record
     * images with {@link #rawImage()}, {@link #dalytranAmtImage()} or {@link #copy()} whenever byte
     * fidelity matters.
     *
     * @return the signed amount at scale 2
     * @throws IllegalArgumentException if the span does not hold a valid zoned numeric image
     */
    public BigDecimal dalytranAmt() {
        return codec.readMonetary(area, DALYTRAN_AMT);
    }

    /**
     * The stored characters of {@code DALYTRAN-AMT PIC S9(09)V99}, sign overpunch included.
     *
     * <p>{@code 0000005047G} rather than {@code 504.77}. Pair it with
     * {@link #writeDalytranAmtImage(String)} to carry the field from one record to another with no
     * numeric round-trip at all.
     *
     * @return exactly {@link #DALYTRAN_AMT_LENGTH} characters
     */
    public String dalytranAmtImage() {
        return area.readSpan(DALYTRAN_AMT);
    }

    /**
     * Whether {@code DALYTRAN-AMT} is numerically zero, whichever sign it carries.
     *
     * <p>Compared with {@link BigDecimal#compareTo(BigDecimal)} and never with
     * {@link BigDecimal#equals(Object)}, because {@code equals} is scale-sensitive: {@code 0.00} and
     * {@code 0} are unequal under {@code equals} yet identical in value. Both a positive-zero and a
     * negative-zero image answer {@code true} here, which is exactly the COBOL numeric comparison and is
     * also why a zero test can never stand in for a byte comparison.
     *
     * @return {@code true} when the decoded amount compares equal to zero
     */
    public boolean hasZeroDalytranAmt() {
        return dalytranAmt().compareTo(CobolDecimal.monetaryZero()) == 0;
    }

    /**
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)} - at 0-based offset 143.
     *
     * <p>An {@code int}, because the {@code PICTURE} is scale-free and nine digits wide: AAP rule R4
     * assigns {@code int} to a scale-free {@code PIC 9(n)} up to nine digits and reserves {@code long}
     * for wider ones. {@code app/cbl/CBTRN02C.cbl:431} moves it into
     * {@code TRAN-MERCHANT-ID PIC 9(09)}, the same width. For the stored digits including leading
     * zeros, use {@link #dalytranMerchantIdImage()}.
     *
     * @return the stored value, 0 to 999999999
     * @throws IllegalArgumentException if the span does not hold nine digits, or denotes a value outside
     *                                  the {@code int} range - which nine digits cannot
     */
    public int dalytranMerchantId() {
        return codec.readPic9AsInt(area, DALYTRAN_MERCHANT_ID);
    }

    /**
     * The stored characters of {@code DALYTRAN-MERCHANT-ID PIC 9(09)}, leading zeros included.
     *
     * @return exactly {@link #DALYTRAN_MERCHANT_ID_LENGTH} characters, for example {@code 800000000}
     */
    public String dalytranMerchantIdImage() {
        return area.readSpan(DALYTRAN_MERCHANT_ID);
    }

    /**
     * {@code DALYTRAN-MERCHANT-NAME PIC X(50)} - at 0-based offset 152.
     *
     * @return exactly {@link #DALYTRAN_MERCHANT_NAME_LENGTH} characters, untrimmed
     */
    public String dalytranMerchantName() {
        return codec.readPicX(area, DALYTRAN_MERCHANT_NAME);
    }

    /**
     * {@code DALYTRAN-MERCHANT-CITY PIC X(50)} - at 0-based offset 202.
     *
     * @return exactly {@link #DALYTRAN_MERCHANT_CITY_LENGTH} characters, untrimmed
     */
    public String dalytranMerchantCity() {
        return codec.readPicX(area, DALYTRAN_MERCHANT_CITY);
    }

    /**
     * {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} - at 0-based offset 252.
     *
     * @return exactly {@link #DALYTRAN_MERCHANT_ZIP_LENGTH} characters, untrimmed
     */
    public String dalytranMerchantZip() {
        return codec.readPicX(area, DALYTRAN_MERCHANT_ZIP);
    }

    /**
     * {@code DALYTRAN-CARD-NUM PIC X(16)} - at 0-based offset 262, the full card number as stored.
     *
     * <p>Returned complete and unaltered. The legacy programs handle it in the clear:
     * {@code app/cbl/CBTRN01C.cbl:168} displays the entire record - this field among its 350 bytes - to
     * {@code SYSOUT}, and {@code :181} displays the number on its own in the message
     * {@code 'CARD NUMBER ' DALYTRAN-CARD-NUM ' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'}.
     * {@code app/cbl/CBTRN02C.cbl:382} moves it whole into the cross-reference key. Masking, redacting,
     * hashing or truncating it here would change observable behaviour, break that {@code SYSOUT}
     * fingerprint and break the byte-level comparison that verifies this migration. Access control
     * belongs to the deployment, not to this record type.
     *
     * @return exactly {@link #DALYTRAN_CARD_NUM_LENGTH} characters, untrimmed and unmasked
     */
    public String dalytranCardNum() {
        return codec.readPicX(area, DALYTRAN_CARD_NUM);
    }

    /**
     * {@code DALYTRAN-ORIG-TS PIC X(26)} - at 0-based offset 278.
     *
     * <p>A character timestamp, not a parsed date: the fixture holds
     * {@code 2022-06-10 19:27:53.000000}, and {@code app/cbl/CBTRN02C.cbl:436} moves all twenty-six
     * bytes into {@code TRAN-ORIG-TS PIC X(26)}. Keeping it as text preserves the exact stored bytes,
     * including any that are spaces.
     *
     * @return exactly {@link #DALYTRAN_ORIG_TS_LENGTH} characters, untrimmed
     */
    public String dalytranOrigTs() {
        return codec.readPicX(area, DALYTRAN_ORIG_TS);
    }

    /**
     * The reference-modified slice {@code DALYTRAN-ORIG-TS (1:10)} - the first ten characters of the
     * originating timestamp, which are its date.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:414} evaluates
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} and, when it does not hold, rejects the
     * transaction with reason 103, {@code 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'}. COBOL reference
     * modification is 1-based, so {@code (1:10)} is {@code substring(0, 10)} in Java; this accessor
     * exists so that conversion is written once and named, rather than repeated at every call site.
     *
     * <p>Deliberately <em>not</em> parsed into a date type. The COBOL comparison is a plain character
     * comparison against {@code ACCT-EXPIRAION-DATE}, which is itself {@code PIC X(10)} - note that the
     * copybook really does misspell it that way - and both sides hold {@code yyyy-MM-dd}, for which
     * character order and chronological order coincide. Parsing would introduce a way to fail on data
     * that COBOL compares happily, such as a blank or partial date.
     *
     * @return exactly {@link #DALYTRAN_ORIG_DT_LENGTH} characters, untrimmed
     */
    public String dalytranOrigDt() {
        return area.readString(DALYTRAN_ORIG_DT_OFFSET, DALYTRAN_ORIG_DT_LENGTH);
    }

    /**
     * {@code DALYTRAN-PROC-TS PIC X(26)} - at 0-based offset 304.
     *
     * <p>Blank on the daily file: every one of the 300 rows of {@code app/data/ASCII/dailytran.txt}
     * holds twenty-six spaces here, because a daily transaction has not been processed yet. Posting is
     * what fills the equivalent field on the transaction master -
     * {@code app/cbl/CBTRN02C.cbl:437-438} performs {@code Z-GET-DB2-FORMAT-TIMESTAMP} and moves the
     * result into {@code TRAN-PROC-TS}, deliberately <em>not</em> back into this record.
     *
     * @return exactly {@link #DALYTRAN_PROC_TS_LENGTH} characters, untrimmed
     */
    public String dalytranProcTs() {
        return codec.readPicX(area, DALYTRAN_PROC_TS);
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
    // The write path - COBOL MOVE semantics. Neither consuming program writes to this record: DALYTRAN is
    // OPEN INPUT and READ INTO in both, so these setters serve the record's other legitimate producer, a
    // test or a parity case assembling a 350-byte input row. They are still held to exact MOVE semantics,
    // because a case that seeded a field by simple assignment would be asserting against an input row the
    // legacy system could never have held.
    //
    // The direction of truncation is not a detail: COBOL fills a PIC X receiver from its leftmost position
    // and discards what does not fit on the RIGHT, but aligns a PIC 9 receiver on the implied decimal
    // point and discards high-order digits on the LEFT. Both rules live in FixedWidthCodec.movePicX /
    // movePic9, which is why no assignment is ever written directly against the area here.
    // =================================================================================================

    /**
     * {@code MOVE ... TO DALYTRAN-ID} - pads or right-truncates to {@link #DALYTRAN_ID_LENGTH}.
     *
     * @param value the sending value; may be shorter or longer than the receiver
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranId(String value) {
        codec.writePicX(area, DALYTRAN_ID, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-TYPE-CD} - pads or right-truncates to
     * {@link #DALYTRAN_TYPE_CD_LENGTH}.
     *
     * <p>An ordinary character move, because the receiver is {@code PIC X(02)} - unlike
     * {@link #moveDalytranCatCd(String)}, whose receiver is numeric and which therefore aligns on the
     * implied decimal point instead.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranTypeCd(String value) {
        codec.writePicX(area, DALYTRAN_TYPE_CD, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-SOURCE} - pads or right-truncates to
     * {@link #DALYTRAN_SOURCE_LENGTH}. Passing {@code "POS TERM"} stores {@code "POS TERM  "}, which is
     * what every row of the fixture holds.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranSource(String value) {
        codec.writePicX(area, DALYTRAN_SOURCE, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-DESC} - pads or right-truncates to {@link #DALYTRAN_DESC_LENGTH},
     * blanking the whole 100-byte field first, which is what {@code MOVE} does.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranDesc(String value) {
        codec.writePicX(area, DALYTRAN_DESC, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-MERCHANT-NAME} - pads or right-truncates to
     * {@link #DALYTRAN_MERCHANT_NAME_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranMerchantName(String value) {
        codec.writePicX(area, DALYTRAN_MERCHANT_NAME, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-MERCHANT-CITY} - pads or right-truncates to
     * {@link #DALYTRAN_MERCHANT_CITY_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranMerchantCity(String value) {
        codec.writePicX(area, DALYTRAN_MERCHANT_CITY, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-MERCHANT-ZIP} - pads or right-truncates to
     * {@link #DALYTRAN_MERCHANT_ZIP_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranMerchantZip(String value) {
        codec.writePicX(area, DALYTRAN_MERCHANT_ZIP, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-CARD-NUM} - pads or right-truncates to
     * {@link #DALYTRAN_CARD_NUM_LENGTH}. The value is stored whole and unmasked; a sixteen-character
     * sender fills the field exactly, as every fixture row does.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranCardNum(String value) {
        codec.writePicX(area, DALYTRAN_CARD_NUM, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-ORIG-TS} - pads or right-truncates to
     * {@link #DALYTRAN_ORIG_TS_LENGTH}.
     *
     * <p>Writing this field also sets {@link #dalytranOrigDt()}, since the date the expiry check at
     * {@code app/cbl/CBTRN02C.cbl:414} compares is its first ten characters.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranOrigTs(String value) {
        codec.writePicX(area, DALYTRAN_ORIG_TS, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-PROC-TS} - pads or right-truncates to
     * {@link #DALYTRAN_PROC_TS_LENGTH}. Passing an empty value restores the twenty-six spaces an
     * unprocessed daily transaction carries.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranProcTs(String value) {
        codec.writePicX(area, DALYTRAN_PROC_TS, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-CAT-CD} from a numeric sender - zero-fills on the left to
     * {@link #DALYTRAN_CAT_CD_LENGTH}, discarding high-order digits if the value is too wide.
     *
     * @param value the sending value; must not be negative, because the receiver is unsigned
     *              {@code PIC 9(04)} and has nowhere to record a sign
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveDalytranCatCd(int value) {
        codec.writePic9(area, DALYTRAN_CAT_CD, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-CAT-CD} from an alphanumeric sender.
     *
     * <p>This is a genuinely surprising COBOL rule and getting it wrong is a silent hundredfold error.
     * The receiver is {@code PIC 9(04)}, so IBM Enterprise COBOL treats an alphanumeric sender as though
     * it were described as an <em>unsigned integer</em> and then performs a numeric move: the value is
     * aligned on the implied decimal point and zero-filled on the left. {@code "05"} therefore stores
     * {@code 0005}, <strong>not</strong> {@code 0500} as a character move into a left-justified field
     * would give. The same rule requires every character of an alphanumeric sender to be a digit, which
     * is why a non-digit here is rejected rather than coerced.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException     if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveDalytranCatCd(String digits) {
        codec.writePic9(area, DALYTRAN_CAT_CD, digits);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-MERCHANT-ID} from a numeric sender - zero-fills on the left to
     * {@link #DALYTRAN_MERCHANT_ID_LENGTH}, so zero stores {@code 000000000} rather than nine spaces.
     *
     * @param value the sending value; must not be negative
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveDalytranMerchantId(long value) {
        codec.writePic9(area, DALYTRAN_MERCHANT_ID, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-MERCHANT-ID} from an alphanumeric sender - treated as an unsigned
     * integer, aligned on the implied decimal point and zero-filled on the left, exactly as described on
     * {@link #moveDalytranCatCd(String)}.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException     if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveDalytranMerchantId(String digits) {
        codec.writePic9(area, DALYTRAN_MERCHANT_ID, digits);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-AMT} - stores the amount at scale exactly
     * {@link #DALYTRAN_AMT_SCALE} with a zoned sign overpunch in the trailing byte.
     *
     * <p>Excess fraction digits are <strong>truncated, never rounded</strong>. COBOL rounds only when a
     * statement says {@code ROUNDED}, and that keyword appears nowhere in any of the twenty-eight
     * programs this migration covers, so the faithful policy is {@link java.math.RoundingMode#DOWN}. The
     * value is put through {@link CobolDecimal#storeMonetary(BigDecimal)} before it reaches the codec so
     * that the scale and the rounding mode are named on the path a caller can actually read.
     *
     * <p>A negative value is stored with a negative overpunch, which matters here: fifty of the three
     * hundred fixture rows are negative, and {@code app/cbl/CBTRN02C.cbl:548} branches on the sign to
     * choose between the cycle credit and cycle debit accumulator.
     *
     * @param value the amount to store; may be negative, and its scale may exceed the field's
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value has more integer digits than
     *                                  {@link #DALYTRAN_AMT_INTEGER_DIGITS}
     */
    public void moveDalytranAmt(BigDecimal value) {
        Objects.requireNonNull(value, "An amount is required to store into DALYTRAN-AMT; use "
                + "moveDalytranAmt(CobolDecimal.monetaryZero()) to store a positive zero");
        codec.writeMonetary(area, DALYTRAN_AMT, CobolDecimal.storeMonetary(value));
    }

    /**
     * Stores a pre-built {@code DALYTRAN-AMT} image verbatim, sign overpunch and all.
     *
     * <p>The byte-faithful counterpart to {@link #moveDalytranAmt(BigDecimal)}, and the only way to
     * carry a whole-value negative zero into this field: {@code 0000000000}<code>&#125;</code> decodes to
     * plain {@code 0.00} and a numeric write would store it back as
     * {@code 0000000000}<code>&#123;</code>. Pair this with {@link #dalytranAmtImage()} to copy the field
     * unchanged whatever it holds.
     *
     * @param image exactly {@link #DALYTRAN_AMT_LENGTH} characters - ten leading digits followed by a
     *              digit or a zoned overpunch character
     * @throws NullPointerException     if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #DALYTRAN_AMT_LENGTH}
     *                                  characters
     */
    public void writeDalytranAmtImage(String image) {
        Objects.requireNonNull(image, "A DALYTRAN-AMT image is required");
        if (image.length() != DALYTRAN_AMT_LENGTH) {
            throw new IllegalArgumentException("A DALYTRAN-AMT image of " + image.length()
                    + " character(s) was supplied but PIC S9(09)V99 occupies exactly "
                    + DALYTRAN_AMT_LENGTH + "; the sign is overpunched into the trailing byte rather "
                    + "than stored separately, so the image is never padded or truncated here");
        }
        area.writeSpan(DALYTRAN_AMT, image);
    }

    // =================================================================================================
    // Identity and diagnostics.
    // =================================================================================================

    /**
     * Two records are equal when they hold identical bytes under the same code page.
     *
     * <p>Byte equality rather than field-by-field value equality, because that is the COBOL notion of one
     * record being the same as another and because it is strictly the stronger test: the images
     * {@code 0000000000}<code>&#123;</code> and {@code 0000000000}<code>&#125;</code> have equal numeric
     * values but are different records, and treating them as equal is exactly the parity defect this
     * class exists to prevent. The charset participates because the same bytes mean different data under
     * different code pages.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code DalyTranRecord} with the same charset and the
     *         same 350 bytes
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DalyTranRecord that)) {
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
     * trailing spaces is visible rather than hidden. {@code DALYTRAN-AMT} is given both as its raw zoned
     * image and as its decoded value, because the two can differ in the one way that matters - a
     * whole-value negative zero.
     *
     * <p>{@code DALYTRAN-CARD-NUM} is masked here, per {@link SensitiveDiagnostics#maskPan(String)},
     * and that is worth stating carefully because the legacy programs do handle the number in the clear:
     * {@code app/cbl/CBTRN01C.cbl:168} puts the entire 350-byte image on {@code SYSOUT},
     * {@code :181} names the number explicitly, and the rejected-record image written at
     * {@code app/cbl/CBTRN02C.cbl:447} carries it too.
     *
     * <p>Those two facts do not conflict, because <strong>this method has no COBOL counterpart.</strong>
     * Nothing in {@code CVTRA06Y} or either consuming program produces an annotated, comma-separated
     * rendering of a record; a {@code DISPLAY} produces the record's <em>bytes</em>. So the observable
     * behaviour this migration must preserve lives entirely in the byte-exact paths, and every one of
     * them still carries all sixteen digits: {@link #displayImage()} for the {@code SYSOUT} line,
     * {@link #rawImage()} and {@link #encode(Charset)} for the rejects image,
     * {@link #dalytranCardNum()} for the field itself, and {@link #rawSpan(FieldSpan)} for its stored
     * span. A field-by-field comparison reads those, by name. Masking one Java diagnostic that no COBOL
     * program has therefore costs the parity contract nothing, while publishing a full card number into
     * whatever log or assertion message happens to consume {@code toString} would be a disclosure this
     * module already decided against for every other record of this shape.
     *
     * <p>The mask preserves the field's sixteen-character width, so the rendering still reports the
     * record's shape correctly, and it escapes control characters in the four digits it reveals.
     *
     * <p>The merchant fields stay legible. A merchant name, city and zip identify a business rather than
     * a cardholder, and they are what a transaction-posting parity failure is diagnosed from.
     *
     * <p>For the exact {@code SYSOUT} text of {@code DISPLAY DALYTRAN-RECORD} use
     * {@link #displayImage()} instead; this rendering is annotated and is not a record image.
     *
     * @return a single-line description of every field
     */
    /**
     * A diagnostic rendering that describes the transaction without disclosing the activity.
     *
     * <p>The card number was already masked, and the rest was not: the transaction identifier, the
     * description, the amount in both its raw zoned image and its decoded form, the merchant identifier,
     * name, city and postcode, and both timestamps were printed verbatim. Together those describe who
     * spent how much where and when - so a log holding them holds the financial activity the record exists
     * to carry (CWE-532) - and every one of them is a {@code PIC X} span that can hold any byte the
     * upstream file put there, so a CR or LF among them forges a second log line (CWE-117).
     *
     * <p>The transaction and merchant identifiers are masked to their last four characters, which still
     * distinguishes one record from another while diagnosing a parity failure. The amount is withheld in
     * both forms - the decoded {@code BigDecimal} was the more revealing of the two and had no protection
     * at all. The description, merchant name, city and postcode are described by length only, which is
     * what a width or padding investigation needs. The type, category, source and timestamp fields carry
     * no personal data and are retained, escaped to a single line.
     *
     * <p><strong>The parity surface is untouched.</strong> {@link #displayImage()} still renders the
     * record byte for byte, and every field accessor still returns exactly what the span holds - those are
     * what a parity case reads. This rendering has no COBOL counterpart at all, so withholding from it
     * costs no observable behaviour.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        return "DalyTranRecord["
                + "charset=" + area.charset().name()
                + ", DALYTRAN-ID=" + SensitiveDiagnostics.maskIdentifier(dalytranId())
                + ", DALYTRAN-TYPE-CD=" + DiagnosticText.singleLine(dalytranTypeCd())
                + ", DALYTRAN-CAT-CD=" + DiagnosticText.singleLine(dalytranCatCdImage())
                + ", DALYTRAN-SOURCE=" + DiagnosticText.singleLine(dalytranSource())
                + ", DALYTRAN-DESC=" + SensitiveDiagnostics.describeText(dalytranDesc())
                + ", DALYTRAN-AMT=" + DiagnosticText.omitted(dalytranAmtImage())
                + ", DALYTRAN-MERCHANT-ID=" + SensitiveDiagnostics.maskIdentifier(
                        dalytranMerchantIdImage())
                + ", DALYTRAN-MERCHANT-NAME=" + SensitiveDiagnostics.describeText(
                        dalytranMerchantName())
                + ", DALYTRAN-MERCHANT-CITY=" + SensitiveDiagnostics.describeText(
                        dalytranMerchantCity())
                + ", DALYTRAN-MERCHANT-ZIP=" + SensitiveDiagnostics.describeText(dalytranMerchantZip())
                + ", DALYTRAN-CARD-NUM=" + SensitiveDiagnostics.maskPan(dalytranCardNum())
                + ", DALYTRAN-ORIG-TS=" + DiagnosticText.singleLine(dalytranOrigTs())
                + ", DALYTRAN-PROC-TS=" + DiagnosticText.singleLine(dalytranProcTs())
                // FILLER carries no field semantics - it is pad - so its width is the only thing worth
                // reporting about it, and printing twenty spaces would only pad the line.
                + ", FILLER.length=" + filler().length()
                + ']';
    }

    // =================================================================================================
    // Argument checks. Kept as named helpers so the reason each one exists is stated once.
    // =================================================================================================

    /**
     * Insists a charset was supplied. There is no default: the pad bytes, the digits and the twenty zoned
     * sign overpunch characters are all code-page dependent, so a fixed-width record addressed by
     * absolute byte offset can never be built against an assumed encoding.
     */
    private static Charset requireCharset(Charset charset) {
        return Objects.requireNonNull(charset, "A charset must be supplied explicitly for a "
                + "DALYTRAN-RECORD: US-ASCII for the app/data/ASCII fixtures, IBM037 for EBCDIC "
                + "datasets. The platform default is never used, because the sign overpunch bytes "
                + "differ between code pages");
    }

    /**
     * Insists a span descriptor is one this record declares. A descriptor borrowed from another copybook
     * could name a valid offset and width yet address entirely the wrong bytes, and a silent wrong read
     * is the hardest kind of parity defect to trace back to its cause. That risk is unusually concrete
     * here: {@code CVTRA05Y} declares a byte-for-byte identical geometry under different names, and
     * {@code CBTRN02C} holds a record of each at the same time.
     */
    private static FieldSpan requireDeclaredSpan(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required; use one of DalyTranRecord's "
                + "declared span constants");
        if (!LAYOUT.spans().contains(field)) {
            throw new IllegalArgumentException("Field " + field.describe() + " is not declared by "
                    + "CVTRA06Y's layout. Use one of DalyTranRecord's own span constants, so that the "
                    + "offset read is always one this copybook actually declares");
        }
        return field;
    }
}
