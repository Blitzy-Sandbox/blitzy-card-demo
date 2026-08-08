package com.vsergeychik.carddemo.customer.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The single Java type for the COBOL copybook {@code app/cpy/CVCUS01Y.cpy} - the 500-byte
 * {@code CUSTOMER-RECORD} of the CardDemo customer master.
 *
 * <h2>The copybook is the contract</h2>
 * The copybook, not any program that copies it, defines this record's shape. Every one of its 19
 * declared spans is reproduced here at its exact absolute offset, under its exact copybook name,
 * including the trailing {@code FILLER PIC X(168)} that no program ever references. The field names
 * are carried verbatim because the parity differ compares field by field <em>by name</em>: renaming
 * one - however tempting - would make a genuine difference invisible.
 *
 * <h2>The 500-byte width is over-determined, and verified five ways</h2>
 * If the layout self-check below ever reports a width other than 500, the descriptor table is wrong,
 * not the sources. The width is independently attested by:
 * <ol>
 *   <li>the copybook's own {@code PICTURE} widths, which sum to 500;</li>
 *   <li>{@code app/cbl/CBCUS01C.cbl:39-40}, which splits the file as {@code FD-CUST-ID PIC 9(09)}
 *       plus {@code FD-CUST-DATA PIC X(491)};</li>
 *   <li>{@code app/cbl/CBSTM03B.CBL:72-73}, which splits the same file as
 *       {@code FD-CUST-ID PIC X(09)} plus {@code FD-CUST-DATA PIC X(491)} - the identical nine bytes
 *       viewed as characters rather than digits, which is exactly the duality the image accessors
 *       below exist to serve;</li>
 *   <li>{@code README.md}'s dataset table: {@code CVCUS01Y | FB | 500 | custdata.txt};</li>
 *   <li>{@code app/jcl/CUSTFILE.jcl}, which defines the cluster {@code KEYS(9 0)}
 *       {@code RECORDSIZE(500 500)} {@code INDEXED} - a nine-byte key at offset 0 and a record fixed
 *       at 500, notwithstanding the CSD's {@code RECORDFORMAT(V)}.</li>
 * </ol>
 * All 50 rows of {@code app/data/ASCII/custdata.txt} measure exactly 500 bytes, so unlike
 * {@code cardxref.txt} - which is 36 bytes where {@code CVACT03Y} declares 50 - no row of this
 * dataset ever needs widening before it is decoded.
 *
 * <h2>{@code FILLER} is data</h2>
 * The trailing 168 bytes are part of every stored record and are space-filled on all 50 measured
 * fixture rows. {@link #LAYOUT} declares the span explicitly, so
 * {@link RecordLayout}'s geometry self-check - which runs when this class initialises - fails
 * immediately and names the offending descriptor if the span is ever dropped or mis-sized. That
 * self-check, not a comment, is what evidences the declared width.
 *
 * <h2>Only exact integral types here, and that is correct rather than an omission</h2>
 * {@code CVCUS01Y} declares no signed and no {@code V}-scaled picture anywhere: its complete
 * {@code PICTURE} census is {@code 9(03)} once, {@code 9(09)} twice and the rest {@code PIC X}. There
 * is no {@code COMP-3} and no {@code PACKED-DECIMAL}. Its three numeric fields are consequently
 * scale-free and, being three and nine digits wide, all map to {@code int} - AAP rule R4 assigns
 * {@code int} to a scale-free {@code PIC 9(n)} for {@code n} up to nine and reserves {@code long} for
 * wider ones, and a wider Java type would accept values the field itself cannot hold. This is the one
 * persisted model
 * type in the migration carrying no monetary field at all, so the module's shared fixed-point decimal
 * helper has no subject here and is deliberately not imported - it must not be added for symmetry
 * with the sibling model types, every one of which genuinely does carry scaled money fields. Every
 * value derived from a {@code PICTURE} is held in an exact integral type; no inexact binary
 * real-number primitive appears anywhere in this class, as none ever may.
 *
 * <h2>A numeric field keeps its character image as well as its value</h2>
 * {@code app/cbl/COACTVWC.cbl:496-504} applies COBOL reference modification directly to
 * {@code CUST-SSN PIC 9(09)}:
 * <pre>
 *   STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)
 *       DELIMITED BY SIZE INTO ACSTSSNO OF CACTVWAO
 * </pre>
 * It slices the field's <em>nine character bytes</em>, not its numeric value, and the plain
 * {@code MOVE} on the commented-out line 495 was deliberately replaced by it. The distinction is not
 * academic: record 1 of the fixture holds {@code '020973888'}, which slices to {@code 020-97-3888},
 * whereas slicing the integer {@code 20973888} would yield {@code 209-73-888} - a silent and
 * catastrophic divergence. {@link #custIdImage(Charset)}, {@link #custSsnImage(Charset)} and
 * {@link #custFicoCreditScoreImage(Charset)} therefore expose the zero-filled fixed-width image
 * beside the typed value, which is precisely the two-views-over-one-span relationship the codebase
 * declares for itself at {@code app/cbl/COACTUPC.cbl:710-712} ({@code ACUP-OLD-CUST-ID-X PIC X(09)}
 * redefined as {@code ACUP-OLD-CUST-ID PIC 9(09)}) and again for the SSN at {@code L742-744}. The
 * images are produced by the codec's {@code PIC 9} left-zero-fill rather than by hand-rolled string
 * formatting, so there is exactly one implementation of that rule in the system.
 *
 * <p>Left zero-filling is load-bearing, not cosmetic: across the 50 fixture rows, all 50
 * {@code CUST-ID} values, 6 {@code CUST-SSN} values and 7 {@code CUST-FICO-CREDIT-SCORE} values carry
 * a leading zero. Dropping the fill would break the round trip on every single row.
 *
 * <h2>Text stays text</h2>
 * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} is an alphanumeric field that happens to look like a date;
 * {@code app/cbl/COACTVWC.cbl:507} moves it straight to a screen field as ten characters. It is not
 * parsed, reformatted or validated here. Likewise {@code CUST-GOVT-ISSUED-ID PIC X(20)} holds values
 * such as {@code 00000000000049368437} whose leading zeros are content, and
 * {@code CUST-PHONE-NUM-1/2 PIC X(15)} hold values such as {@code (908)119-8310  } whose punctuation
 * and trailing spaces are content. No {@code PIC X} value is trimmed on read, because the padding is
 * part of the field and the differ compares it.
 *
 * <h2>The charset is always supplied by the caller</h2>
 * Every conversion entry point takes a {@link Charset}, or a {@link FixedWidthCodec} already bound to
 * one. Nothing here consults the platform default, and the configuration package that resolves the
 * code pages is deliberately not imported, so the dependency edge runs one way only:
 * {@code customer.model} depends on {@code common} and on nothing else in this repository. Each
 * {@code Charset} entry point has a {@link FixedWidthCodec} counterpart because a codec validates its
 * single-byte repertoire on construction; a repository walking thousands of rows should build one
 * codec and reuse it rather than pay that check per row.
 *
 * <h2>Not to be confused with the statement package's customer layout</h2>
 * A second customer copybook under {@code app/cpy} - the one the statement job reads - declares the
 * same 19 spans at the same widths, the same 500-byte total and, measured character by character, the
 * same COBOL group name {@code 01 CUSTOMER-RECORD}. Exactly one thing differs between the two: its
 * date-of-birth item spells the name without the hyphens used here. Because even the group names are
 * identical, the Java class and package name are the only thing keeping the two layouts apart, which
 * is what makes the separation load-bearing rather than cosmetic - field-for-field diffing matches on
 * the field name, so collapsing the two would lose a real distinction. That copybook is modelled by
 * its own type in the statement package; this class never merges with it, subclasses it, shares a
 * descriptor table with it, or extracts a common base class or interface for the pair.
 *
 * <h2>A record layout, not an ORM mapping</h2>
 * There is no persistence annotation, no generated column, no version field and no schema artefact of
 * any kind here, because the migration changes no schema. No dataset name appears either: those are
 * resolved from configuration by the repository that owns the binding. This is a plain data type
 * instantiated by its callers, not a Spring bean.
 *
 * <h2>Plaintext fields stay plaintext</h2>
 * {@code CUST-SSN} and {@code CUST-GOVT-ISSUED-ID} are stored in the clear by the legacy design. That
 * is an inherited property of the system being migrated, recorded here so it stays visible. It is
 * neither weakened nor unrequestedly strengthened: no hashing, no encryption, no masking and no
 * redaction is applied, and {@link #toString()} obscures nothing, because any of those would change
 * observable behaviour and defeat field-for-field diffing.
 *
 * <h2>Thread safety</h2>
 * Instances are mutable, because a COBOL record area is. There is no mutable static state: the
 * descriptor constants and {@link #LAYOUT} are immutable and shared safely, while every field value
 * is strictly per-instance. An instance is not safe for concurrent mutation; confine one to the row,
 * request or step that owns it.
 *
 * @see #LAYOUT
 * @see #decode(byte[], Charset)
 * @see #encode(Charset)
 */
public final class CustomerRecord {

    /**
     * The declared record width in bytes, as {@code CVCUS01Y}'s {@code RECLN 500} header states and
     * as {@code app/jcl/CUSTFILE.jcl}'s {@code RECORDSIZE(500 500)} fixes.
     */
    public static final int RECORD_LENGTH = 500;

    // =================================================================================================
    // The authoritative byte map: all 19 spans of CVCUS01Y, in copybook declaration order, each at its
    // absolute 0-based offset. Read straight down this column beside the copybook to audit it.
    //
    //   #   COBOL field                 PICTURE   1-based    0-based  length
    //   1   CUST-ID                     9(09)       1-9         0        9
    //   2   CUST-FIRST-NAME             X(25)      10-34         9       25
    //   3   CUST-MIDDLE-NAME            X(25)      35-59        34       25
    //   4   CUST-LAST-NAME              X(25)      60-84        59       25
    //   5   CUST-ADDR-LINE-1            X(50)      85-134       84       50
    //   6   CUST-ADDR-LINE-2            X(50)     135-184      134       50
    //   7   CUST-ADDR-LINE-3            X(50)     185-234      184       50
    //   8   CUST-ADDR-STATE-CD          X(02)     235-236      234        2
    //   9   CUST-ADDR-COUNTRY-CD        X(03)     237-239      236        3
    //  10   CUST-ADDR-ZIP               X(10)     240-249      239       10
    //  11   CUST-PHONE-NUM-1            X(15)     250-264      249       15
    //  12   CUST-PHONE-NUM-2            X(15)     265-279      264       15
    //  13   CUST-SSN                    9(09)     280-288      279        9
    //  14   CUST-GOVT-ISSUED-ID         X(20)     289-308      288       20
    //  15   CUST-DOB-YYYY-MM-DD         X(10)     309-318      308       10
    //  16   CUST-EFT-ACCOUNT-ID         X(10)     319-328      318       10
    //  17   CUST-PRI-CARD-HOLDER-IND    X(01)     329          328        1
    //  18   CUST-FICO-CREDIT-SCORE      9(03)     330-332      329        3
    //  19   FILLER                      X(168)    333-500      332      168
    // =================================================================================================

    /**
     * {@code CUST-ID PIC 9(09)} - the record's primary key, and the nine-byte key at offset 0 that
     * {@code app/jcl/CUSTFILE.jcl} declares with {@code KEYS(9 0)}.
     */
    public static final FieldSpan CUST_ID = FieldSpan.unsignedNumeric("CUST-ID", 0, 9);

    /** {@code CUST-FIRST-NAME PIC X(25)}. */
    public static final FieldSpan CUST_FIRST_NAME = FieldSpan.alphanumeric("CUST-FIRST-NAME", 9, 25);

    /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
    public static final FieldSpan CUST_MIDDLE_NAME =
            FieldSpan.alphanumeric("CUST-MIDDLE-NAME", 34, 25);

    /** {@code CUST-LAST-NAME PIC X(25)}. */
    public static final FieldSpan CUST_LAST_NAME = FieldSpan.alphanumeric("CUST-LAST-NAME", 59, 25);

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    public static final FieldSpan CUST_ADDR_LINE_1 =
            FieldSpan.alphanumeric("CUST-ADDR-LINE-1", 84, 50);

    /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    public static final FieldSpan CUST_ADDR_LINE_2 =
            FieldSpan.alphanumeric("CUST-ADDR-LINE-2", 134, 50);

    /**
     * {@code CUST-ADDR-LINE-3 PIC X(50)} - carried to the screen's city field by
     * {@code app/cbl/COACTVWC.cbl:513}, which is why the copybook name rather than a screen name is
     * used here.
     */
    public static final FieldSpan CUST_ADDR_LINE_3 =
            FieldSpan.alphanumeric("CUST-ADDR-LINE-3", 184, 50);

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    public static final FieldSpan CUST_ADDR_STATE_CD =
            FieldSpan.alphanumeric("CUST-ADDR-STATE-CD", 234, 2);

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    public static final FieldSpan CUST_ADDR_COUNTRY_CD =
            FieldSpan.alphanumeric("CUST-ADDR-COUNTRY-CD", 236, 3);

    /** {@code CUST-ADDR-ZIP PIC X(10)}. */
    public static final FieldSpan CUST_ADDR_ZIP = FieldSpan.alphanumeric("CUST-ADDR-ZIP", 239, 10);

    /**
     * {@code CUST-PHONE-NUM-1 PIC X(15)} - alphanumeric, holding values such as
     * {@code (908)119-8310  } whose parentheses, hyphen and trailing spaces are all content.
     */
    public static final FieldSpan CUST_PHONE_NUM_1 =
            FieldSpan.alphanumeric("CUST-PHONE-NUM-1", 249, 15);

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    public static final FieldSpan CUST_PHONE_NUM_2 =
            FieldSpan.alphanumeric("CUST-PHONE-NUM-2", 264, 15);

    /**
     * {@code CUST-SSN PIC 9(09)} - sliced as nine characters by
     * {@code app/cbl/COACTVWC.cbl:496-504}, so its image matters as much as its value. See
     * {@link #custSsnImage(Charset)}.
     */
    public static final FieldSpan CUST_SSN = FieldSpan.unsignedNumeric("CUST-SSN", 279, 9);

    /**
     * {@code CUST-GOVT-ISSUED-ID PIC X(20)} - alphanumeric despite holding all-digit values such as
     * {@code 00000000000049368437}, whose leading zeros are content and must never be normalised
     * away by reinterpreting the field as a number.
     */
    public static final FieldSpan CUST_GOVT_ISSUED_ID =
            FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID", 288, 20);

    /**
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} - ten characters of text, never a parsed date. The
     * hyphens in this name are part of the contract: the statement package's near-identical customer
     * copybook spells the same field without them, and that one difference is the only thing
     * distinguishing the two 500-byte layouts.
     */
    public static final FieldSpan CUST_DOB_YYYY_MM_DD =
            FieldSpan.alphanumeric("CUST-DOB-YYYY-MM-DD", 308, 10);

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    public static final FieldSpan CUST_EFT_ACCOUNT_ID =
            FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID", 318, 10);

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} - a single-character indicator, {@code 'Y'} or {@code 'N'}. */
    public static final FieldSpan CUST_PRI_CARD_HOLDER_IND =
            FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 1);

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}. */
    public static final FieldSpan CUST_FICO_CREDIT_SCORE =
            FieldSpan.unsignedNumeric("CUST-FICO-CREDIT-SCORE", 329, 3);

    /**
     * {@code FILLER PIC X(168)} - the trailing reserved span, declared explicitly as span 19 and
     * never an implicit gap. It is space-filled on every one of the 50 measured fixture rows, and
     * omitting it would both falsify the record width and shift every offset of any record laid out
     * after it.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(332, 168);

    /**
     * The complete layout of {@code CVCUS01Y}: 18 named spans plus the trailing {@code FILLER}, in
     * copybook declaration order.
     *
     * <p>Constructing this constant <strong>is</strong> the total-width self-check.
     * {@link RecordLayout}'s canonical constructor verifies that the spans are contiguous from offset
     * 0, that no span overlaps or leaves a gap, that no referable name is declared twice, and that
     * their lengths sum to exactly {@link #RECORD_LENGTH}. Because the check runs during class
     * initialisation, a mis-transcribed offset or a dropped {@code FILLER} fails fast and names the
     * offending descriptor rather than silently producing records of the wrong width.
     *
     * <p>Only the 19 copybook spans are declared. No synthetic {@code REDEFINES} overlay is added,
     * so {@code FixedWidthCodec.deserialise(LAYOUT, row)} yields exactly the 18 referable copybook
     * field names and nothing else - which is what the parity differ expects to compare. The
     * character-image views of the three numeric fields are exposed as accessors instead.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            CUST_ID,
            CUST_FIRST_NAME,
            CUST_MIDDLE_NAME,
            CUST_LAST_NAME,
            CUST_ADDR_LINE_1,
            CUST_ADDR_LINE_2,
            CUST_ADDR_LINE_3,
            CUST_ADDR_STATE_CD,
            CUST_ADDR_COUNTRY_CD,
            CUST_ADDR_ZIP,
            CUST_PHONE_NUM_1,
            CUST_PHONE_NUM_2,
            CUST_SSN,
            CUST_GOVT_ISSUED_ID,
            CUST_DOB_YYYY_MM_DD,
            CUST_EFT_ACCOUNT_ID,
            CUST_PRI_CARD_HOLDER_IND,
            CUST_FICO_CREDIT_SCORE,
            FILLER);

    // =================================================================================================
    // Field values. Strictly per-instance: nothing below is static, because COBOL WORKING-STORAGE must
    // never become shared Java state - that would break row isolation and test determinism alike.
    //
    // The initial state mirrors a COBOL INITIALIZE of this record: every alphanumeric field starts as
    // its declared width in SPACES - not as an empty string - and every numeric field starts at zero,
    // so it images as its declared width in zeros. No field is ever null.
    //
    // Holding the padded content rather than an empty string is the point of F11's correction: a field
    // is its declared width from the moment it exists, so a getter, an equals and an encode can never
    // disagree about what the record contains. Nothing here is static except the immutable, code-page-
    // free PICTURE_RULES: COBOL WORKING-STORAGE must never become shared Java state, because that
    // would break row isolation and test determinism alike.
    // =================================================================================================

    /**
     * The {@code PICTURE} move rules, applied when a value <em>enters</em> a receiver.
     *
     * <p>Static and shared, which is safe and correct here for a reason worth stating: a
     * {@link FixedWidthCodec} is immutable, and the two rules used below -
     * {@link FixedWidthCodec#movePicX(String, int)} and {@link FixedWidthCodec#movePic9(long, int)}
     * (reached with an {@code int}, which widens) -
     * are pure character and digit work. They count characters and digits; they render no byte and
     * consult no code page, so the answer is identical under {@code IBM037} and {@code US-ASCII} alike.
     * The code page still matters when the record is <em>encoded</em>, and there it is supplied
     * explicitly by the caller, as every {@code encode} and {@code decode} overload shows.
     *
     * <p>This is what makes normalising at setter time possible at all. Were the move rules code-page
     * dependent, a receiver could not be filled until the target charset were known, and the class
     * would be forced back into holding an unbounded value and normalising late - which is exactly the
     * defect this addresses.
     */
    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /** {@code CUST-ID PIC 9(09)} - scale-free and nine digits, so {@code int} (AAP rule R4). */
    private int custId;

    /** {@code CUST-FIRST-NAME PIC X(25)}. */
    private String custFirstName = blank(CUST_FIRST_NAME);

    /** {@code CUST-MIDDLE-NAME PIC X(25)}. */
    private String custMiddleName = blank(CUST_MIDDLE_NAME);

    /** {@code CUST-LAST-NAME PIC X(25)}. */
    private String custLastName = blank(CUST_LAST_NAME);

    /** {@code CUST-ADDR-LINE-1 PIC X(50)}. */
    private String custAddrLine1 = blank(CUST_ADDR_LINE_1);

    /** {@code CUST-ADDR-LINE-2 PIC X(50)}. */
    private String custAddrLine2 = blank(CUST_ADDR_LINE_2);

    /** {@code CUST-ADDR-LINE-3 PIC X(50)}. */
    private String custAddrLine3 = blank(CUST_ADDR_LINE_3);

    /** {@code CUST-ADDR-STATE-CD PIC X(02)}. */
    private String custAddrStateCd = blank(CUST_ADDR_STATE_CD);

    /** {@code CUST-ADDR-COUNTRY-CD PIC X(03)}. */
    private String custAddrCountryCd = blank(CUST_ADDR_COUNTRY_CD);

    /** {@code CUST-ADDR-ZIP PIC X(10)}. */
    private String custAddrZip = blank(CUST_ADDR_ZIP);

    /** {@code CUST-PHONE-NUM-1 PIC X(15)}. */
    private String custPhoneNum1 = blank(CUST_PHONE_NUM_1);

    /** {@code CUST-PHONE-NUM-2 PIC X(15)}. */
    private String custPhoneNum2 = blank(CUST_PHONE_NUM_2);

    /**
     * {@code CUST-SSN PIC 9(09)} - held in the clear, exactly as the legacy design stores it.
     *
     * <p>Nine digits and scale-free, so {@code int} (AAP rule R4). Hashing it would be a behaviour
     * change and Spring Security is out of scope, so the plaintext storage is inherited deliberately.
     */
    private int custSsn;

    /** {@code CUST-GOVT-ISSUED-ID PIC X(20)} - alphanumeric, held in the clear. */
    private String custGovtIssuedId = blank(CUST_GOVT_ISSUED_ID);

    /** {@code CUST-DOB-YYYY-MM-DD PIC X(10)} - ten characters of text, never a parsed date. */
    private String custDobYyyyMmDd = blank(CUST_DOB_YYYY_MM_DD);

    /** {@code CUST-EFT-ACCOUNT-ID PIC X(10)}. */
    private String custEftAccountId = blank(CUST_EFT_ACCOUNT_ID);

    /** {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}. */
    private String custPriCardHolderInd = blank(CUST_PRI_CARD_HOLDER_IND);

    /** {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} - scale-free, so {@code int}. */
    private int custFicoCreditScore;

    /**
     * Creates a record in its {@code INITIALIZE} state: every alphanumeric field holding its declared
     * width in spaces and every numeric field zero, so an immediate {@link #encode(Charset)} yields
     * spaces in the character spans, zeros in the numeric spans and 168 spaces in the trailing
     * {@code FILLER}.
     *
     * <p>{@link #getCustFirstName()} on a fresh record therefore returns twenty-five spaces rather than
     * an empty string. That is what the field contains, and a getter that said otherwise would be
     * describing a state {@code PIC X(25)} cannot hold.
     */
    public CustomerRecord() {
        // Field initialisers above establish the complete initial state; nothing further is required.
    }

    // =================================================================================================
    // Decoding: a stored 500-byte image becomes field values.
    //
    // app/cbl/CBCUS01C.cbl:93 reads READ CUSTFILE-FILE INTO CUSTOMER-RECORD - a group-level read, so
    // the 500 bytes land verbatim with no field-level reformatting. These methods interpret nothing
    // beyond the copybook's own PICTURE categories.
    // =================================================================================================

    /**
     * Decodes a stored 500-byte record image.
     *
     * @param source  exactly {@link #RECORD_LENGTH} bytes, as stored
     * @param charset the code page of the stored bytes, supplied explicitly and never assumed -
     *                {@code IBM037} for the EBCDIC datasets, {@code US-ASCII} for the text fixtures
     * @return a record carrying the decoded field values
     * @throws NullPointerException     if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code source} is not exactly {@link #RECORD_LENGTH} bytes,
     *                                  if {@code charset} is not single-byte for the required
     *                                  repertoire, or if a numeric span does not hold digits
     */
    public static CustomerRecord decode(byte[] source, Charset charset) {
        return decode(source, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a stored 500-byte record image using an existing codec. Prefer this form in a row loop:
     * a codec validates its single-byte repertoire once on construction, so reusing one avoids
     * repeating that check for every row of the dataset.
     *
     * @param source exactly {@link #RECORD_LENGTH} bytes, as stored
     * @param codec  the codec bound to the stored bytes' code page
     * @return a record carrying the decoded field values
     * @throws NullPointerException     if {@code source} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code source} is not exactly {@link #RECORD_LENGTH} bytes,
     *                                  or if a numeric span does not hold digits
     */
    public static CustomerRecord decode(byte[] source, FixedWidthCodec codec) {
        Objects.requireNonNull(source, "A 500-byte CVCUS01Y record image is required to decode a "
                + "customer record");
        Objects.requireNonNull(codec, "A codec is required to decode a customer record: the stored "
                + "bytes' code page must be stated explicitly and is never derived from the platform");
        FixedWidthRecord area = codec.wrap(source, LAYOUT);
        CustomerRecord decoded = new CustomerRecord();
        // Assigned directly rather than through the setters: a decode is a faithful load of bytes the
        // codec has already validated, so re-validating here would only obscure that. Every PIC X span
        // is read UNTRIMMED, because its padding is part of the field and the parity differ compares it.
        decoded.custId = codec.readPic9AsInt(area, CUST_ID);
        decoded.custFirstName = codec.readPicX(area, CUST_FIRST_NAME);
        decoded.custMiddleName = codec.readPicX(area, CUST_MIDDLE_NAME);
        decoded.custLastName = codec.readPicX(area, CUST_LAST_NAME);
        decoded.custAddrLine1 = codec.readPicX(area, CUST_ADDR_LINE_1);
        decoded.custAddrLine2 = codec.readPicX(area, CUST_ADDR_LINE_2);
        decoded.custAddrLine3 = codec.readPicX(area, CUST_ADDR_LINE_3);
        decoded.custAddrStateCd = codec.readPicX(area, CUST_ADDR_STATE_CD);
        decoded.custAddrCountryCd = codec.readPicX(area, CUST_ADDR_COUNTRY_CD);
        decoded.custAddrZip = codec.readPicX(area, CUST_ADDR_ZIP);
        decoded.custPhoneNum1 = codec.readPicX(area, CUST_PHONE_NUM_1);
        decoded.custPhoneNum2 = codec.readPicX(area, CUST_PHONE_NUM_2);
        decoded.custSsn = codec.readPic9AsInt(area, CUST_SSN);
        decoded.custGovtIssuedId = codec.readPicX(area, CUST_GOVT_ISSUED_ID);
        decoded.custDobYyyyMmDd = codec.readPicX(area, CUST_DOB_YYYY_MM_DD);
        decoded.custEftAccountId = codec.readPicX(area, CUST_EFT_ACCOUNT_ID);
        decoded.custPriCardHolderInd = codec.readPicX(area, CUST_PRI_CARD_HOLDER_IND);
        decoded.custFicoCreditScore = codec.readPic9AsInt(area, CUST_FICO_CREDIT_SCORE);
        return decoded;
    }

    /**
     * Decodes a stored record image supplied as text, for the case where a fixed-width row arrives
     * from JDBC as a character value rather than as bytes.
     *
     * @param source  exactly {@link #RECORD_LENGTH} characters' worth of stored image
     * @param charset the code page under which {@code source} is to be read as bytes
     * @return a record carrying the decoded field values
     * @throws NullPointerException     if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code source} does not encode to exactly
     *                                  {@link #RECORD_LENGTH} bytes
     */
    public static CustomerRecord decode(String source, Charset charset) {
        Objects.requireNonNull(source, "A 500-character CVCUS01Y record image is required to decode "
                + "a customer record");
        Objects.requireNonNull(charset, "A charset is required to read a record image as bytes");
        return decode(FixedWidthRecord.encodeText(source, charset, "a CUSTOMER-RECORD image"),
                charset);
    }

    // =================================================================================================
    // Encoding: field values become a 500-byte image.
    //
    // Every span is written through FixedWidthCodec, so the PIC X rule (left justified, space-padded
    // and truncated on the RIGHT) and the PIC 9 rule (right justified, zero-filled and truncated on the
    // LEFT) each have exactly one implementation in the system. No padding is hand-rolled here.
    //
    // The left zero-fill is not optional: 63 of the numeric values across the 50 fixture rows carry a
    // leading zero, so omitting it would break the round trip on every row.
    // =================================================================================================

    /**
     * Encodes this record as its 500-byte stored image.
     *
     * @param charset the code page to write under, supplied explicitly
     * @return exactly {@link #RECORD_LENGTH} bytes, with the trailing {@code FILLER} space-filled
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the required
     *                                  repertoire
     */
    public byte[] encode(Charset charset) {
        return encode(new FixedWidthCodec(charset));
    }

    /**
     * Encodes this record as its 500-byte stored image using an existing codec. Prefer this form in a
     * row loop, for the same reason as {@link #decode(byte[], FixedWidthCodec)}.
     *
     * @param codec the codec bound to the target code page
     * @return exactly {@link #RECORD_LENGTH} bytes, with the trailing {@code FILLER} space-filled
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] encode(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).toByteArray();
    }

    /**
     * Returns this record's whole 500-character group image - the raw view of the entire
     * {@code CUSTOMER-RECORD} group, from the first byte of {@code CUST-ID} through the last byte of
     * the trailing {@code FILLER}.
     *
     * <p>This is what {@code DISPLAY CUSTOMER-RECORD} needs. {@code app/cbl/CBCUS01C.cbl} displays the
     * raw group twice for every record it reads - once at {@code L96}, inside
     * {@code 1000-CUSTFILE-GET-NEXT} on a {@code '00'} status, and again at {@code L78} in the main
     * {@code PERFORM UNTIL} loop. That duplication is a genuine defect of the original program and is
     * deliberately preserved, which is why 50 fixture records produce 100 record lines rather than 50.
     * Reproducing it byte for byte is only possible with a raw group-level accessor, so one is provided
     * rather than left to callers to assemble from individual fields.
     *
     * <p>The image is always derived from the current field values, never cached from a decode. A
     * retained copy would silently disagree with the fields after any setter call, which is exactly the
     * class of divergence this migration must not introduce.
     *
     * @param charset the code page to render under, supplied explicitly
     * @return exactly {@link #RECORD_LENGTH} characters
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the required
     *                                  repertoire
     */
    public String recordImage(Charset charset) {
        return recordImage(new FixedWidthCodec(charset));
    }

    /**
     * Returns this record's whole 500-character group image using an existing codec.
     *
     * @param codec the codec bound to the target code page
     * @return exactly {@link #RECORD_LENGTH} characters
     * @throws NullPointerException if {@code codec} is {@code null}
     * @see #recordImage(Charset)
     */
    public String recordImage(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).readString(0, RECORD_LENGTH);
    }

    /**
     * Builds a fully populated record area over {@link #LAYOUT}.
     *
     * <p>The area is allocated through the codec, which initialises it from the layout first: that is
     * what leaves the trailing {@code FILLER} holding its 168 spaces without this method having to
     * write it. {@code FILLER} is deliberately never written here - it carries no value to write - yet
     * it is always correct in the result, which is the whole point of declaring it as a first-class
     * span.
     *
     * @param codec the codec bound to the target code page
     * @return a record area holding this record's 500 bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    private FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render a customer record: the target "
                + "code page must be stated explicitly and is never derived from the platform");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePic9(area, CUST_ID, custId);
        codec.writePicX(area, CUST_FIRST_NAME, custFirstName);
        codec.writePicX(area, CUST_MIDDLE_NAME, custMiddleName);
        codec.writePicX(area, CUST_LAST_NAME, custLastName);
        codec.writePicX(area, CUST_ADDR_LINE_1, custAddrLine1);
        codec.writePicX(area, CUST_ADDR_LINE_2, custAddrLine2);
        codec.writePicX(area, CUST_ADDR_LINE_3, custAddrLine3);
        codec.writePicX(area, CUST_ADDR_STATE_CD, custAddrStateCd);
        codec.writePicX(area, CUST_ADDR_COUNTRY_CD, custAddrCountryCd);
        codec.writePicX(area, CUST_ADDR_ZIP, custAddrZip);
        codec.writePicX(area, CUST_PHONE_NUM_1, custPhoneNum1);
        codec.writePicX(area, CUST_PHONE_NUM_2, custPhoneNum2);
        codec.writePic9(area, CUST_SSN, custSsn);
        codec.writePicX(area, CUST_GOVT_ISSUED_ID, custGovtIssuedId);
        codec.writePicX(area, CUST_DOB_YYYY_MM_DD, custDobYyyyMmDd);
        codec.writePicX(area, CUST_EFT_ACCOUNT_ID, custEftAccountId);
        codec.writePicX(area, CUST_PRI_CARD_HOLDER_IND, custPriCardHolderInd);
        codec.writePic9(area, CUST_FICO_CREDIT_SCORE, custFicoCreditScore);
        return area;
    }

    // =================================================================================================
    // The dual view over the three numeric spans.
    //
    // COBOL lets a program treat a PIC 9(n) field as n characters, and this codebase does exactly that:
    // app/cbl/COACTVWC.cbl:496-504 slices CUST-SSN with reference modification, and
    // app/cbl/COACTUPC.cbl:710-712 and :742-744 declare the X/9 REDEFINES pairs outright. So both views
    // are exposed: the typed value for arithmetic and comparison, and the zero-filled fixed-width image
    // for positional slicing. A caller that slices the image gets COBOL's answer; a caller that slices
    // String.valueOf(the value) does not, and the difference is silent.
    // =================================================================================================

    /**
     * Returns {@code CUST-ID} as its 9-character zero-filled image - the {@code PIC X(09)} view of the
     * same nine bytes that {@code app/cbl/CBSTM03B.CBL:72} declares as {@code FD-CUST-ID PIC X(09)}
     * while {@code app/cbl/CBCUS01C.cbl:39} declares them as {@code PIC 9(09)}.
     *
     * @param charset the code page whose digit repertoire the image is validated against
     * @return exactly 9 characters, left zero-filled; a {@code custId} of {@code 1} renders as
     *         {@code 000000001}
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public String custIdImage(Charset charset) {
        return custIdImage(new FixedWidthCodec(charset));
    }

    /**
     * Returns {@code CUST-ID} as its 9-character zero-filled image, using an existing codec.
     *
     * @param codec the codec supplying the {@code PIC 9} left-zero-fill rule
     * @return exactly 9 characters, left zero-filled
     * @throws NullPointerException if {@code codec} is {@code null}
     * @see #custIdImage(Charset)
     */
    public String custIdImage(FixedWidthCodec codec) {
        return numericImage(codec, custId, CUST_ID);
    }

    /**
     * Returns {@code CUST-SSN} as its 9-character zero-filled image, which is the view
     * {@code app/cbl/COACTVWC.cbl:496-504} slices to build the {@code NNN-NN-NNNN} screen value.
     *
     * <p>Slice this image, never the numeric value. For fixture record 1 the image is
     * {@code 020973888}, whose {@code (1:3)}, {@code (4:2)} and {@code (6:4)} slices are {@code 020},
     * {@code 97} and {@code 3888}, giving {@code 020-97-3888}. The same slices taken over
     * {@code String.valueOf(20973888)} would give {@code 209-73-888} instead - a wrong answer that no
     * type system would catch.
     *
     * @param charset the code page whose digit repertoire the image is validated against
     * @return exactly 9 characters, left zero-filled
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public String custSsnImage(Charset charset) {
        return custSsnImage(new FixedWidthCodec(charset));
    }

    /**
     * Returns {@code CUST-SSN} as its 9-character zero-filled image, using an existing codec.
     *
     * @param codec the codec supplying the {@code PIC 9} left-zero-fill rule
     * @return exactly 9 characters, left zero-filled
     * @throws NullPointerException if {@code codec} is {@code null}
     * @see #custSsnImage(Charset)
     */
    public String custSsnImage(FixedWidthCodec codec) {
        return numericImage(codec, custSsn, CUST_SSN);
    }

    /**
     * Returns {@code CUST-FICO-CREDIT-SCORE} as its 3-character zero-filled image. Seven of the 50
     * fixture rows carry a leading zero in this field, so the fill is what makes the image faithful.
     *
     * @param charset the code page whose digit repertoire the image is validated against
     * @return exactly 3 characters, left zero-filled
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public String custFicoCreditScoreImage(Charset charset) {
        return custFicoCreditScoreImage(new FixedWidthCodec(charset));
    }

    /**
     * Returns {@code CUST-FICO-CREDIT-SCORE} as its 3-character zero-filled image, using an existing
     * codec.
     *
     * @param codec the codec supplying the {@code PIC 9} left-zero-fill rule
     * @return exactly 3 characters, left zero-filled
     * @throws NullPointerException if {@code codec} is {@code null}
     * @see #custFicoCreditScoreImage(Charset)
     */
    public String custFicoCreditScoreImage(FixedWidthCodec codec) {
        return numericImage(codec, custFicoCreditScore, CUST_FICO_CREDIT_SCORE);
    }

    /**
     * Renders one unsigned numeric field as its declared-width image through the codec's {@code PIC 9}
     * move rule, so the left zero-fill has a single implementation shared by all three fields rather
     * than three hand-written format strings.
     *
     * @param codec the codec supplying the {@code PIC 9} left-zero-fill rule
     * @param value the field's current value
     * @param field the descriptor whose declared length fixes the image width
     * @return the value as exactly {@code field.length()} digits, left zero-filled
     */
    private static String numericImage(FixedWidthCodec codec, int value, FieldSpan field) {
        Objects.requireNonNull(codec, "A codec is required to render a numeric field image: the "
                + "PIC 9 left-zero-fill rule belongs to the codec, not to this model");
        return codec.movePic9(value, field.length());
    }

    // =================================================================================================
    // Validation helpers. Each guard lives in exactly one place, so the rule is stated once and every
    // caller reports it identically.
    // =================================================================================================

    /**
     * Receives a value into an alphanumeric field, applying the {@code PIC X} move rule.
     *
     * <p><strong>This is the receiver, not a validator.</strong> A COBOL {@code MOVE} into
     * {@code PIC X(n)} left-justifies and either right-space-pads to {@code n} or truncates on the
     * right at {@code n}; the field afterwards holds exactly {@code n} characters and no other state is
     * reachable. Applying that here rather than at encode time is what keeps a single state observable:
     * every getter, every {@code equals}, every comparison and every serialisation sees the same
     * {@code n} characters, and a value the copybook's field can never contain cannot be held even
     * transiently.
     *
     * <p>Deferring it to encode - which is what this class used to do - let a caller set a
     * thirty-character first name, read thirty characters back, compare two records as unequal on that
     * basis, and only then encode twenty-five. Three of those four observations describe a record that
     * cannot exist.
     *
     * <p>{@code null} is still refused rather than normalised. COBOL has no null: an empty
     * {@code PIC X} field holds spaces, so a caller wanting to blank a field supplies spaces or an
     * empty string, and both arrive here and leave as {@code n} spaces.
     *
     * @param value the candidate field value
     * @param field the descriptor whose name and width the diagnostic quotes and the rule uses
     * @return exactly {@code field.length()} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String receive(String value, FieldSpan field) {
        if (value == null) {
            throw new NullPointerException("Field '" + field.name() + "' cannot be null: COBOL has no "
                    + "null, so an empty PIC X(" + field.length() + ") field holds spaces. Supply an "
                    + "empty string or spaces to blank it");
        }
        return PICTURE_RULES.movePicX(value, field.length());
    }

    /**
     * The {@code INITIALIZE} content of an alphanumeric field: its declared width in spaces.
     *
     * @param field the descriptor whose width fixes the length
     * @return exactly {@code field.length()} spaces
     */
    private static String blank(FieldSpan field) {
        return PICTURE_RULES.movePicX("", field.length());
    }

    /**
     * Receives a value into an unsigned numeric field, applying the {@code PIC 9} move rule.
     *
     * <p>A negative value is refused rather than normalised: {@code PIC 9(n)} declares no sign position,
     * so a negative value has no stored representation at all and is a caller error, not something to
     * reshape.
     *
     * <p>An over-wide value is <strong>truncated on the left, not refused</strong>, and that asymmetry
     * is COBOL's rather than a choice made here: a numeric {@code MOVE} discards high-order digits
     * silently unless the program asks for {@code ON SIZE ERROR}, and no program in this codebase does.
     * What changes is only <em>when</em> the truncation happens - now, as the value enters the field,
     * rather than later during encode - so the stored value and its image can no longer disagree.
     *
     * @param value the candidate field value
     * @param field the descriptor whose name and width the diagnostic quotes and the rule uses
     * @return the value reduced to at most {@code field.length()} digits
     * @throws IllegalArgumentException if {@code value} is negative
     */
    private static int receive(int value, FieldSpan field) {
        if (value < 0) {
            throw new IllegalArgumentException("Field '" + field.name() + "' is PIC 9("
                    + field.length() + "), an unsigned picture with no sign position, so it cannot "
                    + "hold " + value);
        }
        return Integer.parseInt(PICTURE_RULES.movePic9(value, field.length()));
    }

    // =================================================================================================
    // Accessors. One pair per named span, in copybook order. PIC X values are returned exactly as held,
    // never trimmed, because the padding is part of the field and the parity differ compares it - and
    // since a setter applies the PIC X move on the way in, "as held" is always exactly the declared
    // width. A numeric getter likewise returns the value the field holds, already reduced to at most
    // its declared number of digits.
    // =================================================================================================

    /**
     * Reads span 1, {@code CUST-ID PIC 9(09)}.
     *
     * @return {@code CUST-ID}, the record's primary key
     */
    public int getCustId() {
        return custId;
    }

    /**
     * Writes span 1, {@code CUST-ID PIC 9(09)}.
     *
     * @param custId the new {@code CUST-ID}; must not be negative. It is received through
     *        the {@code PIC 9(09)} move rule, so a value of more than 9 digits loses its
     *        high-order excess exactly as a COBOL numeric {@code MOVE} does
     * @throws IllegalArgumentException if {@code custId} is negative
     */
    public void setCustId(int custId) {
        this.custId = receive(custId, CUST_ID);
    }

    /**
     * Reads span 2, {@code CUST-FIRST-NAME PIC X(25)}.
     *
     * @return {@code CUST-FIRST-NAME}, exactly 25 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Writes span 2, {@code CUST-FIRST-NAME PIC X(25)}.
     *
     * @param custFirstName the new {@code CUST-FIRST-NAME}; must not be {@code null}. It is received
     *        through the {@code PIC X(25)} move rule, so a shorter value is right-space-padded
     *        to 25 characters and a longer one truncated on the right at 25 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custFirstName} is {@code null}
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = receive(custFirstName, CUST_FIRST_NAME);
    }

    /**
     * Reads span 3, {@code CUST-MIDDLE-NAME PIC X(25)}.
     *
     * @return {@code CUST-MIDDLE-NAME}, exactly 25 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Writes span 3, {@code CUST-MIDDLE-NAME PIC X(25)}.
     *
     * @param custMiddleName the new {@code CUST-MIDDLE-NAME}; must not be {@code null}. It is received
     *        through the {@code PIC X(25)} move rule, so a shorter value is right-space-padded
     *        to 25 characters and a longer one truncated on the right at 25 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custMiddleName} is {@code null}
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = receive(custMiddleName, CUST_MIDDLE_NAME);
    }

    /**
     * Reads span 4, {@code CUST-LAST-NAME PIC X(25)}.
     *
     * @return {@code CUST-LAST-NAME}, exactly 25 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Writes span 4, {@code CUST-LAST-NAME PIC X(25)}.
     *
     * @param custLastName the new {@code CUST-LAST-NAME}; must not be {@code null}. It is received
     *        through the {@code PIC X(25)} move rule, so a shorter value is right-space-padded
     *        to 25 characters and a longer one truncated on the right at 25 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custLastName} is {@code null}
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = receive(custLastName, CUST_LAST_NAME);
    }

    /**
     * Reads span 5, {@code CUST-ADDR-LINE-1 PIC X(50)}.
     *
     * @return {@code CUST-ADDR-LINE-1}, exactly 50 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * Writes span 5, {@code CUST-ADDR-LINE-1 PIC X(50)}.
     *
     * @param custAddrLine1 the new {@code CUST-ADDR-LINE-1}; must not be {@code null}. It is received
     *        through the {@code PIC X(50)} move rule, so a shorter value is right-space-padded
     *        to 50 characters and a longer one truncated on the right at 50 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custAddrLine1} is {@code null}
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = receive(custAddrLine1, CUST_ADDR_LINE_1);
    }

    /**
     * Reads span 6, {@code CUST-ADDR-LINE-2 PIC X(50)}.
     *
     * @return {@code CUST-ADDR-LINE-2}, exactly 50 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * Writes span 6, {@code CUST-ADDR-LINE-2 PIC X(50)}.
     *
     * @param custAddrLine2 the new {@code CUST-ADDR-LINE-2}; must not be {@code null}. It is received
     *        through the {@code PIC X(50)} move rule, so a shorter value is right-space-padded
     *        to 50 characters and a longer one truncated on the right at 50 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custAddrLine2} is {@code null}
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = receive(custAddrLine2, CUST_ADDR_LINE_2);
    }

    /**
     * Reads span 7, {@code CUST-ADDR-LINE-3 PIC X(50)}.
     *
     * @return {@code CUST-ADDR-LINE-3}, exactly 50 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * Writes span 7, {@code CUST-ADDR-LINE-3 PIC X(50)}.
     *
     * @param custAddrLine3 the new {@code CUST-ADDR-LINE-3}; must not be {@code null}. It is received
     *        through the {@code PIC X(50)} move rule, so a shorter value is right-space-padded
     *        to 50 characters and a longer one truncated on the right at 50 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custAddrLine3} is {@code null}
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = receive(custAddrLine3, CUST_ADDR_LINE_3);
    }

    /**
     * Reads span 8, {@code CUST-ADDR-STATE-CD PIC X(02)}.
     *
     * @return {@code CUST-ADDR-STATE-CD}, exactly 2 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * Writes span 8, {@code CUST-ADDR-STATE-CD PIC X(02)}.
     *
     * @param custAddrStateCd the new {@code CUST-ADDR-STATE-CD}; must not be {@code null}. It is received
     *        through the {@code PIC X(02)} move rule, so a shorter value is right-space-padded
     *        to 2 characters and a longer one truncated on the right at 2 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custAddrStateCd} is {@code null}
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = receive(custAddrStateCd, CUST_ADDR_STATE_CD);
    }

    /**
     * Reads span 9, {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     *
     * @return {@code CUST-ADDR-COUNTRY-CD}, exactly 3 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * Writes span 9, {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     *
     * @param custAddrCountryCd the new {@code CUST-ADDR-COUNTRY-CD}; must not be {@code null}. It is received
     *        through the {@code PIC X(03)} move rule, so a shorter value is right-space-padded
     *        to 3 characters and a longer one truncated on the right at 3 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custAddrCountryCd} is {@code null}
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = receive(custAddrCountryCd, CUST_ADDR_COUNTRY_CD);
    }

    /**
     * Reads span 10, {@code CUST-ADDR-ZIP PIC X(10)}.
     *
     * @return {@code CUST-ADDR-ZIP}, exactly 10 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * Writes span 10, {@code CUST-ADDR-ZIP PIC X(10)}.
     *
     * @param custAddrZip the new {@code CUST-ADDR-ZIP}; must not be {@code null}. It is received
     *        through the {@code PIC X(10)} move rule, so a shorter value is right-space-padded
     *        to 10 characters and a longer one truncated on the right at 10 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custAddrZip} is {@code null}
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = receive(custAddrZip, CUST_ADDR_ZIP);
    }

    /**
     * Reads span 11, {@code CUST-PHONE-NUM-1 PIC X(15)}.
     *
     * @return {@code CUST-PHONE-NUM-1}, exactly 15 characters and untrimmed - the receiver's
     *         own content, with its punctuation intact
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * Writes span 11, {@code CUST-PHONE-NUM-1 PIC X(15)}.
     *
     * @param custPhoneNum1 the new {@code CUST-PHONE-NUM-1}; must not be {@code null}. It is received
     *        through the {@code PIC X(15)} move rule, so a shorter value is right-space-padded
     *        to 15 characters and a longer one truncated on the right at 15 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custPhoneNum1} is {@code null}
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = receive(custPhoneNum1, CUST_PHONE_NUM_1);
    }

    /**
     * Reads span 12, {@code CUST-PHONE-NUM-2 PIC X(15)}.
     *
     * @return {@code CUST-PHONE-NUM-2}, exactly 15 characters and untrimmed - the receiver's
     *         own content, with its punctuation intact
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * Writes span 12, {@code CUST-PHONE-NUM-2 PIC X(15)}.
     *
     * @param custPhoneNum2 the new {@code CUST-PHONE-NUM-2}; must not be {@code null}. It is received
     *        through the {@code PIC X(15)} move rule, so a shorter value is right-space-padded
     *        to 15 characters and a longer one truncated on the right at 15 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custPhoneNum2} is {@code null}
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = receive(custPhoneNum2, CUST_PHONE_NUM_2);
    }

    /**
     * Returns {@code CUST-SSN} as a value. To render or slice it, use
     * {@link #custSsnImage(Charset)} - the legacy screen logic slices the field's nine characters,
     * not its numeric value.
     *
     * @return {@code CUST-SSN}
     */
    public int getCustSsn() {
        return custSsn;
    }

    /**
     * Writes span 13, {@code CUST-SSN PIC 9(09)}.
     *
     * @param custSsn the new {@code CUST-SSN}; must not be negative. It is received through
     *        the {@code PIC 9(09)} move rule, so a value of more than 9 digits loses its
     *        high-order excess exactly as a COBOL numeric {@code MOVE} does
     * @throws IllegalArgumentException if {@code custSsn} is negative
     */
    public void setCustSsn(int custSsn) {
        this.custSsn = receive(custSsn, CUST_SSN);
    }

    /**
     * Reads span 14, {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * @return {@code CUST-GOVT-ISSUED-ID}, exactly 20 characters and untrimmed - the receiver's
     *         own content, with any leading zeros intact
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Writes span 14, {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * @param custGovtIssuedId the new {@code CUST-GOVT-ISSUED-ID}; must not be {@code null}. It is received
     *        through the {@code PIC X(20)} move rule, so a shorter value is right-space-padded
     *        to 20 characters and a longer one truncated on the right at 20 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custGovtIssuedId} is {@code null}
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = receive(custGovtIssuedId, CUST_GOVT_ISSUED_ID);
    }

    /**
     * Returns {@code CUST-DOB-YYYY-MM-DD} as the ten characters it is. It is deliberately not a
     * {@code LocalDate}: the legacy program moves it straight to a screen field, and parsing it here
     * would impose validation the COBOL does not perform.
     *
     * @return {@code CUST-DOB-YYYY-MM-DD}, exactly 10 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * Writes span 15, {@code CUST-DOB-YYYY-MM-DD PIC X(10)}.
     *
     * @param custDobYyyyMmDd the new {@code CUST-DOB-YYYY-MM-DD}; must not be {@code null}, and is
     *                        stored as text without being parsed or reformatted
     * @throws NullPointerException if {@code custDobYyyyMmDd} is {@code null}
     */
    public void setCustDobYyyyMmDd(String custDobYyyyMmDd) {
        this.custDobYyyyMmDd = receive(custDobYyyyMmDd, CUST_DOB_YYYY_MM_DD);
    }

    /**
     * Reads span 16, {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     *
     * @return {@code CUST-EFT-ACCOUNT-ID}, exactly 10 characters and untrimmed - the receiver's
     *         own content
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * Writes span 16, {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     *
     * @param custEftAccountId the new {@code CUST-EFT-ACCOUNT-ID}; must not be {@code null}. It is received
     *        through the {@code PIC X(10)} move rule, so a shorter value is right-space-padded
     *        to 10 characters and a longer one truncated on the right at 10 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custEftAccountId} is {@code null}
     */
    public void setCustEftAccountId(String custEftAccountId) {
        this.custEftAccountId = receive(custEftAccountId, CUST_EFT_ACCOUNT_ID);
    }

    /**
     * Reads span 17, {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     *
     * @return {@code CUST-PRI-CARD-HOLDER-IND}, the single-character indicator
     */
    public String getCustPriCardHolderInd() {
        return custPriCardHolderInd;
    }

    /**
     * Writes span 17, {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}.
     *
     * @param custPriCardHolderInd the new {@code CUST-PRI-CARD-HOLDER-IND}; must not be {@code null}. It is received
     *        through the {@code PIC X(01)} move rule, so a shorter value is right-space-padded
     *        to 1 characters and a longer one truncated on the right at 1 - the field holds the
     *        result, not the argument
     * @throws NullPointerException if {@code custPriCardHolderInd} is {@code null}
     */
    public void setCustPriCardHolderInd(String custPriCardHolderInd) {
        this.custPriCardHolderInd = receive(custPriCardHolderInd, CUST_PRI_CARD_HOLDER_IND);
    }

    /**
     * Reads span 18, {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     *
     * @return {@code CUST-FICO-CREDIT-SCORE}
     */
    public int getCustFicoCreditScore() {
        return custFicoCreditScore;
    }

    /**
     * Writes span 18, {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     *
     * @param custFicoCreditScore the new {@code CUST-FICO-CREDIT-SCORE}; must not be negative. It is received through
     *        the {@code PIC 9(03)} move rule, so a value of more than 3 digits loses its
     *        high-order excess exactly as a COBOL numeric {@code MOVE} does
     * @throws IllegalArgumentException if {@code custFicoCreditScore} is negative
     */
    public void setCustFicoCreditScore(int custFicoCreditScore) {
        this.custFicoCreditScore =
                receive(custFicoCreditScore, CUST_FICO_CREDIT_SCORE);
    }

    // =================================================================================================
    // Value semantics. Equality is over all 18 named field values, in copybook order. FILLER carries no
    // value and so takes no part: it is reserved storage, verified by the layout's width self-check
    // rather than compared as data.
    // =================================================================================================

    /**
     * Compares all 18 named field values. This supports the re-read-and-compare-before-rewrite pattern
     * that {@code 9300-CHECK-CHANGE-IN-REC} performs in the update programs, without introducing a
     * version column - which would be a schema change, and is forbidden.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code CustomerRecord} whose every named field
     *         equals this one's
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CustomerRecord that)) {
            return false;
        }
        return Arrays.equals(fieldValues(), that.fieldValues());
    }

    /**
     * @return a hash consistent with {@link #equals(Object)}, over the same 18 named field values
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(fieldValues());
    }

    /**
     * The 18 named field values in copybook order, shared by {@link #equals(Object)} and
     * {@link #hashCode()} so the two can never drift apart.
     *
     * @return a fresh array of the 18 named field values, in copybook declaration order
     */
    private Object[] fieldValues() {
        return new Object[] {
                custId,
                custFirstName,
                custMiddleName,
                custLastName,
                custAddrLine1,
                custAddrLine2,
                custAddrLine3,
                custAddrStateCd,
                custAddrCountryCd,
                custAddrZip,
                custPhoneNum1,
                custPhoneNum2,
                custSsn,
                custGovtIssuedId,
                custDobYyyyMmDd,
                custEftAccountId,
                custPriCardHolderInd,
                custFicoCreditScore,
        };
    }

    /**
     * Names every field under its exact copybook name, and withholds the personal data per
     * {@link SensitiveDiagnostics}.
     *
     * <p>This is the most sensitive record in the module. {@code CVCUS01Y} is a complete identity
     * dossier: legal name, three address lines, two telephone numbers, date of birth, social security
     * number, government-issued identifier and an electronic funds transfer account. Rendering it in
     * full - which this method previously did - meant one log statement or one failed assertion
     * disclosed everything needed to impersonate the customer.
     *
     * <p>Three categories, and the treatment differs because the risk does. {@code CUST-SSN},
     * {@code CUST-GOVT-ISSUED-ID} and {@code CUST-EFT-ACCOUNT-ID} are withheld outright, with no partial
     * value and no length, because they are credentials rather than descriptors. The name, address,
     * telephone, zip and date-of-birth fields report their length and nothing else, because a name has no
     * safely-revealable part - the last four characters of a surname are still the surname.
     * {@code CUST-ID} is masked to its last four digits at full stored width, enough to correlate two log
     * lines about one customer and not enough to reconstruct the key. State code, country code, the
     * primary-card-holder indicator and the FICO score stay legible: none identifies a person once the
     * identifier is masked, and they are what an account-update validation parity failure is read from.
     *
     * <p>The earlier rationale here was that hiding these fields would be a behaviour change and would
     * defeat field-for-field diffing. Neither holds. It is not a behaviour change, because COBOL has no
     * {@code toString} and nothing the legacy program or the parity harness can observe is altered - the
     * accessors, {@link #encode(Charset)} and {@link #fieldImages(Charset)} all still return the real
     * values. And it does not defeat diffing, because the differ compares
     * {@link #fieldImages(Charset)} rather than this string: a caller that asks for the values by name
     * still gets them, while a caller that merely renders the object no longer does.
     *
     * @return a rendering safe to log, naming all 18 fields
     */
    @Override
    public String toString() {
        return "CustomerRecord{CUST-ID="
                + SensitiveDiagnostics.maskIdentifier(custId, CUST_ID.length())
                + ", CUST-FIRST-NAME=" + SensitiveDiagnostics.describeText(custFirstName)
                + ", CUST-MIDDLE-NAME=" + SensitiveDiagnostics.describeText(custMiddleName)
                + ", CUST-LAST-NAME=" + SensitiveDiagnostics.describeText(custLastName)
                + ", CUST-ADDR-LINE-1=" + SensitiveDiagnostics.describeText(custAddrLine1)
                + ", CUST-ADDR-LINE-2=" + SensitiveDiagnostics.describeText(custAddrLine2)
                + ", CUST-ADDR-LINE-3=" + SensitiveDiagnostics.describeText(custAddrLine3)
                + ", CUST-ADDR-STATE-CD=[" + custAddrStateCd
                + "], CUST-ADDR-COUNTRY-CD=[" + custAddrCountryCd
                + "], CUST-ADDR-ZIP=" + SensitiveDiagnostics.describeText(custAddrZip)
                + ", CUST-PHONE-NUM-1=" + SensitiveDiagnostics.describeText(custPhoneNum1)
                + ", CUST-PHONE-NUM-2=" + SensitiveDiagnostics.describeText(custPhoneNum2)
                + ", CUST-SSN=" + SensitiveDiagnostics.redacted()
                + ", CUST-GOVT-ISSUED-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-DOB-YYYY-MM-DD=" + SensitiveDiagnostics.describeText(custDobYyyyMmDd)
                + ", CUST-EFT-ACCOUNT-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-PRI-CARD-HOLDER-IND=[" + custPriCardHolderInd
                + "], CUST-FICO-CREDIT-SCORE=" + custFicoCreditScore
                + '}';
    }
}
