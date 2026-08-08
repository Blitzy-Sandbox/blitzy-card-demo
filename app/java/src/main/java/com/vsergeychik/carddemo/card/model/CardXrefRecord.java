package com.vsergeychik.carddemo.card.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The card cross-reference record: the one Java type for {@code app/cpy/CVACT03Y.cpy}, an immutable
 * fixed-width value of exactly {@value #RECORD_LENGTH} bytes.
 *
 * <p>The copybook is reproduced here in full, because it is short enough that a reviewer can hold it
 * beside the constants below and confirm every offset by eye:
 * <pre>
 *   *****************************************************************
 *   *    Data-structure for card xref (RECLN 50)
 *   *****************************************************************
 *    01 CARD-XREF-RECORD.
 *        05  XREF-CARD-NUM                     PIC X(16).
 *        05  XREF-CUST-ID                      PIC 9(09).
 *        05  XREF-ACCT-ID                      PIC 9(11).
 *        05  FILLER                            PIC X(14).
 * </pre>
 * 16 + 9 + 11 + 14 = 50, which is the {@code RECLN 50} the copybook's own header declares. That
 * arithmetic is not merely asserted in this comment: {@link #LAYOUT} is a
 * {@link RecordLayout}, and a {@code RecordLayout} refuses to be constructed unless its spans are
 * contiguous from offset 0 and sum to exactly the declared record length. Dropping the trailing
 * {@code FILLER} therefore fails class initialisation immediately rather than silently emitting
 * 36-byte records.
 *
 * <h2>The second-most-shared record type in the system</h2>
 * Twelve of the twenty-eight programs {@code COPY CVACT03Y}: {@code CBACT03C}, {@code CBACT04C},
 * {@code CBSTM03A}, {@code CBTRN01C}, {@code CBTRN02C}, {@code CBTRN03C}, {@code COACTUPC},
 * {@code COACTVWC}, {@code COBIL00C}, {@code COCRDSLC}, {@code COCRDUPC} and {@code COTRN02C} -
 * only the account record is copied more widely. Most of those consumers live outside the
 * {@code card} package, so this type is deliberately kept dependency-light: it imports the two
 * fixed-width classes from {@code common} and nothing else from this repository. A single import of
 * another domain package here would close a dependency cycle across five domains at once.
 *
 * <h2>Two keys, at two different offsets</h2>
 * This one record is reached through two distinct VSAM access paths, and confusing them is the
 * defect this class is shaped to prevent:
 * <table border="1">
 *   <caption>Access paths over the cross-reference dataset</caption>
 *   <tr><th>Path</th><th>Key field</th><th>Span</th><th>Accessor</th></tr>
 *   <tr>
 *     <td>{@code CCXREF} - the base KSDS</td>
 *     <td>{@code XREF-CARD-NUM}</td>
 *     <td>{@code [0, 16)}</td>
 *     <td>{@link #cardNumberKey()}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code CXACAIX} - the alternate-index path</td>
 *     <td>{@code XREF-ACCT-ID}</td>
 *     <td>{@code [25, 36)}</td>
 *     <td>{@link #accountIdAlternateIndexKey()}</td>
 *   </tr>
 * </table>
 * Both paths address the same 50 bytes; the alternate index is a second way in, never a second
 * table. {@code app/jcl/INTCALC.jcl} proves the point by opening the cross-reference twice in one
 * step, as {@code XREFFILE} on the base KSDS and {@code XREFFIL1} on the alternate-index path, which
 * is exactly why both keys are first-class members of this single type.
 *
 * <p>The account id is the trap. The sibling {@code CardRecord} of {@code app/cpy/CVACT02Y.cpy} also
 * carries an 11-byte account id, but at offset <strong>16</strong>, not 25, because its card number
 * is followed immediately by the account id with no customer id between them. Reading this record's
 * account id at offset 16 compiles cleanly, type-checks cleanly, and returns the last two digits of
 * the card number followed by the first nine digits of the customer id - a plausible-looking number
 * that no compiler can catch. The two keys are therefore exposed under names that state which access
 * path each belongs to, so a repository binding a keyed read cannot pick the wrong span by accident.
 *
 * <h2>{@code XREF-CARD-NUM} is a {@code String}, never a numeric type</h2>
 * The picture is {@code X(16)} - alphanumeric - and that is decisive even though every card number
 * in {@code app/data/ASCII/cardxref.txt} happens to consist of digits. The first fixture row holds
 * {@code 0500024453765740}, whose <strong>leading zero</strong> is data. Modelling the field as a
 * {@code long} or a {@code BigInteger} would silently reduce it to 500024453765740, breaking every
 * keyed read against the base KSDS and every field-for-field parity diff, while looking entirely
 * reasonable at the call site. A consumer that needs a numeric view declares that {@code REDEFINES}
 * in its own working storage; it is not part of this copybook and is not reproduced here.
 *
 * <h2>The trailing {@code FILLER X(14)} is emitted, never optimised away</h2>
 * {@link #encode(Charset)} always produces all {@value #RECORD_LENGTH} bytes, with
 * {@code [36, 50)} carrying fourteen spaces. The copybook declares no {@code VALUE} on the
 * {@code FILLER}, so spaces are its initialised content. Two reasons this is load-bearing rather
 * than cosmetic:
 * <ul>
 *   <li>In a fixed-width dataset, a record that is 36 bytes instead of 50 shifts every subsequent
 *       byte offset in the entire file.</li>
 *   <li>{@code app/cbl/CBACT03C.cbl:78} and {@code :96} execute {@code DISPLAY CARD-XREF-RECORD} -
 *       the <em>whole</em> record image, trailing spaces included, goes to {@code SYSOUT}. The
 *       emitted width and byte content are therefore directly observable output, and the
 *       {@code CBACT03C} parity cases compare them.</li>
 * </ul>
 * Reserved space that is deliberately reserved stays a real, written span.
 *
 * <h2>Risk R-F: the fixture is 36 bytes wide, and this type does not accommodate that</h2>
 * A measured deviation, recorded here rather than quietly repaired.
 * {@code app/data/ASCII/cardxref.txt} holds 50 records of exactly <strong>36</strong> bytes each,
 * because the fixture omits the trailing {@code FILLER X(14)} that {@code CVACT03Y} declares. The
 * copybook is the contract, so:
 * <ul>
 *   <li>this type decodes against the declared width of {@value #RECORD_LENGTH} and
 *       <strong>rejects</strong> a 36-byte row, in every path;</li>
 *   <li>widening a short row is <em>not</em> this type's responsibility. The single shared
 *       normaliser is {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)}, which the parity
 *       harness applies on the way in. Re-implementing a right-pad here would give the rule two
 *       homes and let them drift apart.</li>
 * </ul>
 * A future reader must not make this type tolerant of a short row. The reference data is the parity
 * oracle and is never rewritten; the deviation is absorbed at the boundary, by the caller, visibly.
 *
 * <h2>No decimal arithmetic, and therefore no rounding policy</h2>
 * {@code CVACT03Y} declares no {@code COMP-3}, no {@code PACKED-DECIMAL}, no signed picture and no
 * {@code V} scale. Both numeric fields are scale-free unsigned zoned {@code DISPLAY} integers, so
 * this class contains no arbitrary-precision decimal type, no rounding mode and no reference to the
 * module's decimal helper - there is simply nothing here to round. Contrast the sibling account
 * record of {@code app/cpy/CVACT01Y.cpy}, whose five {@code PIC S9(10)V99} balances do need that
 * machinery. Following the module's width mapping, the 9-digit customer id is an {@code int} and the
 * 11-digit account id, which exceeds the {@code int} range, is a {@code long}. Neither is ever held
 * in a binary floating-point primitive.
 *
 * <h2>Immutability, and what this type is not</h2>
 * Instances are immutable and compare by value, so a decoded record is safe to share, cache in a
 * caller's own structures and assert against without defensive copying. All twelve consumers read
 * the cross-reference: no {@code WRITE}, {@code REWRITE} or {@code DELETE} is issued against any
 * cross-reference file anywhere in the twenty-eight programs, so no mutator is offered. Constructing
 * a replacement through the canonical constructor is the way to produce a changed record, which is
 * also what the field-by-field comparison of {@code 9300-CHECK-CHANGE-IN-REC} needs.
 *
 * <p>This is a plain value type. It carries no persistence metadata, no Spring stereotype and no
 * serialisation annotation: there is no entity model, no table and no schema behind these bytes, and
 * ordinary accessors already give it a JSON form. The only static members are the immutable layout
 * constants below - no mutable static state, so instances are independent and every test is
 * deterministic.
 *
 * @see FixedWidthCodec
 * @see FixedWidthRecord
 */
public final class CardXrefRecord {

    // =================================================================================================
    // The layout, transcribed from app/cpy/CVACT03Y.cpy. Every offset and every length is a named
    // constant so that no method body below contains a bare number, and so that each constant can be
    // traced line by line back to the copybook item it came from.
    // =================================================================================================

    /**
     * The declared record width in bytes: the {@code RECLN 50} of the copybook's own header, and the
     * sum of the four spans declared below.
     */
    public static final int RECORD_LENGTH = 50;

    /** The copybook name of the card number field, verbatim: {@code XREF-CARD-NUM}. */
    public static final String XREF_CARD_NUM_NAME = "XREF-CARD-NUM";

    /** Absolute 0-based offset of {@code XREF-CARD-NUM PIC X(16)}: the first field of the record. */
    public static final int XREF_CARD_NUM_OFFSET = 0;

    /** Declared width of {@code XREF-CARD-NUM PIC X(16)}, in bytes. */
    public static final int XREF_CARD_NUM_LENGTH = 16;

    /** The copybook name of the customer id field, verbatim: {@code XREF-CUST-ID}. */
    public static final String XREF_CUST_ID_NAME = "XREF-CUST-ID";

    /** Absolute 0-based offset of {@code XREF-CUST-ID PIC 9(09)}, immediately after the card number. */
    public static final int XREF_CUST_ID_OFFSET = 16;

    /** Declared width of {@code XREF-CUST-ID PIC 9(09)}: nine zoned {@code DISPLAY} digits. */
    public static final int XREF_CUST_ID_LENGTH = 9;

    /** The copybook name of the account id field, verbatim: {@code XREF-ACCT-ID}. */
    public static final String XREF_ACCT_ID_NAME = "XREF-ACCT-ID";

    /**
     * Absolute 0-based offset of {@code XREF-ACCT-ID PIC 9(11)}.
     *
     * <p>Twenty-five, not sixteen. The sibling card record of {@code app/cpy/CVACT02Y.cpy} places its
     * own 11-byte account id at offset 16 because it has no customer id in between; here the
     * 9-digit customer id occupies {@code [16, 25)} first.
     */
    public static final int XREF_ACCT_ID_OFFSET = 25;

    /** Declared width of {@code XREF-ACCT-ID PIC 9(11)}: eleven zoned {@code DISPLAY} digits. */
    public static final int XREF_ACCT_ID_LENGTH = 11;

    /**
     * Absolute 0-based offset of the trailing {@code FILLER PIC X(14)}.
     *
     * <p>This is also the width of every row in {@code app/data/ASCII/cardxref.txt}, which is
     * precisely why that fixture is 36 bytes and not 50 - see risk R-F in the class documentation.
     */
    public static final int FILLER_OFFSET = 36;

    /** Declared width of the trailing {@code FILLER PIC X(14)}, in bytes. */
    public static final int FILLER_LENGTH = 14;

    /**
     * {@code XREF-CARD-NUM PIC X(16)} - the base {@code CCXREF} KSDS key. Alphanumeric, so it is left
     * justified and space-padded, and a leading zero is data rather than formatting.
     */
    public static final FieldSpan XREF_CARD_NUM =
            FieldSpan.alphanumeric(XREF_CARD_NUM_NAME, XREF_CARD_NUM_OFFSET, XREF_CARD_NUM_LENGTH);

    /**
     * {@code XREF-CUST-ID PIC 9(09)} - unsigned zoned {@code DISPLAY}, right justified and
     * zero-filled on the left.
     */
    public static final FieldSpan XREF_CUST_ID =
            FieldSpan.unsignedNumeric(XREF_CUST_ID_NAME, XREF_CUST_ID_OFFSET, XREF_CUST_ID_LENGTH);

    /**
     * {@code XREF-ACCT-ID PIC 9(11)} - the {@code CXACAIX} alternate-index key. Unsigned zoned
     * {@code DISPLAY}, right justified and zero-filled on the left.
     */
    public static final FieldSpan XREF_ACCT_ID =
            FieldSpan.unsignedNumeric(XREF_ACCT_ID_NAME, XREF_ACCT_ID_OFFSET, XREF_ACCT_ID_LENGTH);

    /**
     * The trailing {@code FILLER PIC X(14)}, declared as a real positioned span rather than left as
     * an implicit gap. It carries no {@code VALUE} in the copybook, so it initialises to - and is
     * emitted as - fourteen spaces.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete self-checking layout, spans in copybook declaration order.
     *
     * <p>Constructing this constant <em>is</em> the width proof. {@link RecordLayout} verifies at
     * construction that the four spans are contiguous from offset 0 with no gap and no overlap, that
     * no referable name repeats, and that their lengths total exactly {@link #RECORD_LENGTH}. Any
     * transcription error - a dropped {@code FILLER}, a mistyped offset, a wrong picture width -
     * therefore fails while this class is being initialised, naming the offending descriptor, instead
     * of surfacing later as a misaligned dataset.
     *
     * <p>Exposed publicly because the parity differ and the repositories legitimately need the field
     * geometry: {@code FixedWidthCodec.deserialise(LAYOUT, bytes)} yields a field-name-to-image map
     * for field-by-field diffing without this class having to grow a parallel reflection surface.
     * {@code RecordLayout} is an immutable record holding an immutable list, so publishing it cannot
     * leak mutable state.
     */
    public static final RecordLayout LAYOUT =
            RecordLayout.of(RECORD_LENGTH, XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID, FILLER);

    /**
     * The largest value {@code XREF-CUST-ID PIC 9(09)} can hold: nine nines. Derived from the
     * declared digit count so the bound and the width can never disagree.
     */
    public static final int XREF_CUST_ID_MAX_VALUE = (int) maxUnsignedValue(XREF_CUST_ID_LENGTH);

    /**
     * The largest value {@code XREF-ACCT-ID PIC 9(11)} can hold: eleven nines. This exceeds
     * {@link Integer#MAX_VALUE}, which is why the account id is a {@code long} while the customer id
     * is an {@code int}.
     */
    public static final long XREF_ACCT_ID_MAX_VALUE = maxUnsignedValue(XREF_ACCT_ID_LENGTH);

    // =================================================================================================
    // Instance state. Final, per-instance and complete: these three fields plus the fixed-content
    // FILLER account for all 50 bytes.
    // =================================================================================================

    /**
     * {@code XREF-CARD-NUM PIC X(16)}, held exactly as it appears in the record: untrimmed, so a
     * trailing space is preserved as the padding byte it is, and a leading zero is preserved as data.
     */
    private final String xrefCardNum;

    /** {@code XREF-CUST-ID PIC 9(09)}, a scale-free unsigned integer. */
    private final int xrefCustId;

    /** {@code XREF-ACCT-ID PIC 9(11)}, a scale-free unsigned integer wider than the {@code int} range. */
    private final long xrefAcctId;

    /**
     * Creates a cross-reference record from its three named fields.
     *
     * <p>Every argument is validated against its {@code PICTURE} here, at the boundary, so an
     * instance either exists and is representable in 50 bytes or does not exist at all - the same
     * fail-loudly-and-early stance {@link RecordLayout} takes on geometry. In particular this
     * constructor does <strong>not</strong> apply COBOL {@code MOVE} truncation: an over-wide card
     * number or an out-of-range id is a defect in the caller, not a value to be quietly reshaped. A
     * caller translating an actual COBOL {@code MOVE} statement, where truncation <em>is</em> the
     * faithful behaviour, applies {@link FixedWidthCodec#movePicX(String, int)} or
     * {@link FixedWidthCodec#movePic9(long, int)} first, so that the truncation is visible at the
     * site where the COBOL performs it and lives in exactly one implementation.
     *
     * @param xrefCardNum {@code XREF-CARD-NUM}; never {@code null}, and never longer than
     *                    {@value #XREF_CARD_NUM_LENGTH} characters. May be shorter, in which case
     *                    {@link #encode(Charset)} space-pads it on the right exactly as COBOL does,
     *                    and may be empty to denote {@code SPACES}
     * @param xrefCustId  {@code XREF-CUST-ID}; never negative, because {@code PIC 9} is unsigned, and
     *                    never above {@link #XREF_CUST_ID_MAX_VALUE}
     * @param xrefAcctId  {@code XREF-ACCT-ID}; never negative and never above
     *                    {@link #XREF_ACCT_ID_MAX_VALUE}
     * @throws NullPointerException     if {@code xrefCardNum} is {@code null}
     * @throws IllegalArgumentException if {@code xrefCardNum} is too long for its picture, or either
     *                                  id is negative or too large for its picture
     */
    public CardXrefRecord(String xrefCardNum, int xrefCustId, long xrefAcctId) {
        Objects.requireNonNull(xrefCardNum, "XREF-CARD-NUM is required; it is PIC X(16), so pass an "
                + "empty string to denote SPACES rather than null");
        if (xrefCardNum.length() > XREF_CARD_NUM_LENGTH) {
            throw new IllegalArgumentException("XREF-CARD-NUM was given " + xrefCardNum.length()
                    + " character(s) but the picture is PIC X(" + XREF_CARD_NUM_LENGTH + "). A COBOL "
                    + "MOVE would truncate this on the right; if that is the intended semantic, apply "
                    + "FixedWidthCodec.movePicX(value, " + XREF_CARD_NUM_LENGTH + ") at the call site "
                    + "so the truncation is visible there");
        }
        requireStorable(xrefCustId, XREF_CUST_ID_MAX_VALUE, XREF_CUST_ID_NAME, XREF_CUST_ID_LENGTH);
        requireStorable(xrefAcctId, XREF_ACCT_ID_MAX_VALUE, XREF_ACCT_ID_NAME, XREF_ACCT_ID_LENGTH);
        this.xrefCardNum = xrefCardNum;
        this.xrefCustId = xrefCustId;
        this.xrefAcctId = xrefAcctId;
    }

    /**
     * The largest value an unsigned zoned {@code DISPLAY} field of the given digit count can hold,
     * computed from the digit count itself so that a bound can never drift away from the width it
     * bounds. Deliberately branch-free and integral: it uses no floating-point exponentiation,
     * because no value in this migration is ever computed through a binary floating-point primitive.
     */
    private static long maxUnsignedValue(int digits) {
        return Long.parseLong("9".repeat(digits));
    }

    /**
     * Rejects a value that no unsigned zoned {@code DISPLAY} field of the given picture could hold.
     * Negative values are rejected because {@code PIC 9(n)} has no sign position at all - it is not
     * that the sign would be lost, it is that there is nowhere to put it.
     */
    private static void requireStorable(long value, long maximum, String field, int digits) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " is PIC 9(" + digits + "), an unsigned "
                    + "picture with no sign position, so it cannot hold a negative value");
        }
        if (value > maximum) {
            throw new IllegalArgumentException(field + " was given a value needing more than "
                    + digits + " digit(s), which does not fit PIC 9(" + digits + "); the largest "
                    + "storable value is " + maximum);
        }
    }

    // =================================================================================================
    // Decoding. Three entry points onto one implementation - two overloads taking stored bytes, plus
    // decodeSpan for an already-wrapped span. The charset is always an explicit argument or is carried
    // by an explicitly-constructed codec: nothing here consults a platform default, in any path,
    // because the pad and digit bytes themselves differ between IBM037 and US-ASCII.
    // =================================================================================================

    /**
     * Decodes a stored record from its {@value #RECORD_LENGTH} bytes under an explicitly named code
     * page.
     *
     * <p>Convenience form, for a caller decoding a single record - the parity harness reading one
     * fixture row, or a test. A repository decoding many rows should hold one
     * {@link FixedWidthCodec} and use {@link #decode(byte[], FixedWidthCodec)} instead, so the
     * codec's charset validation is performed once rather than per row.
     *
     * @param record  the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param charset the code page of the stored bytes, named explicitly by the caller -
     *                {@code IBM037} for the EBCDIC datasets, {@code US-ASCII} for the text fixtures
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH} bytes,
     *                                  if either numeric span holds a non-digit, or if {@code charset}
     *                                  is not a single-byte code page for the digits and the space
     */
    public static CardXrefRecord decode(byte[] record, Charset charset) {
        return decode(record, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a stored record from its {@value #RECORD_LENGTH} bytes using a codec the caller already
     * holds. This is the form a repository uses, once per row, against a single long-lived codec.
     *
     * <p>A row of any width other than {@value #RECORD_LENGTH} is rejected. That specifically includes
     * the 36-byte rows of {@code app/data/ASCII/cardxref.txt}, which omit the trailing
     * {@code FILLER X(14)}: widen such a row with
     * {@link FixedWidthCodec#padToDeclaredWidth(byte[], int)} before calling this method, which is
     * risk R-F handled visibly at the boundary rather than absorbed silently here.
     *
     * @param record the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param codec  the codec for the code page of the stored bytes
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH} bytes,
     *                                  or either numeric span holds a non-digit
     */
    public static CardXrefRecord decode(byte[] record, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to decode a card cross-reference record; "
                + "the code page of fixed-width data is never assumed");
        Objects.requireNonNull(record, "Stored bytes are required to decode a card cross-reference "
                + "record");
        return decodeSpan(codec.wrap(record, LAYOUT), codec);
    }

    /**
     * Decodes a record already wrapped as a {@link FixedWidthRecord} span, which is the form a
     * repository holds after reading a row.
     *
     * <p>Named distinctly from the {@code decode(byte[], …)} pair on purpose. Overloading {@code decode}
     * on {@code byte[]} versus {@code FixedWidthRecord} would compile, but the two overloads are
     * ambiguous for a caller passing a bare {@code null} first argument, forcing a cast at the call
     * site to say which of two unrelated things is absent. A distinct name removes that ambiguity for
     * every caller and reads more precisely besides: it states that the width check has already been
     * performed by whoever wrapped the bytes.
     *
     * <p>Field-level rules, both taken from the copybook rather than from convention:
     * <ul>
     *   <li>{@code XREF-CARD-NUM} is read <strong>untrimmed</strong>. A {@code PIC X} field is
     *       space-padded to its full declared width and that padding is part of the field's value,
     *       because the parity differ compares the field byte for byte. Trimming is available on the
     *       codec as a separately-named operation, for the places where the COBOL itself trims; this
     *       is not one of them.</li>
     *   <li>Both numeric spans are read as unsigned zoned {@code DISPLAY} digits. No sign overpunch
     *       is interpreted, because neither picture is signed - every numeric span measured across
     *       all fifty fixture rows holds nothing but digits.</li>
     * </ul>
     *
     * @param record the wrapped record span; its declared length must be {@value #RECORD_LENGTH}
     * @param codec  the codec for the code page of the record's bytes
     * @return the decoded record
     * @throws NullPointerException     if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not
     *                                  {@value #RECORD_LENGTH}, or either numeric span holds a
     *                                  non-digit
     */
    public static CardXrefRecord decodeSpan(FixedWidthRecord record, FixedWidthCodec codec) {
        Objects.requireNonNull(record, "A record span is required to decode a card cross-reference "
                + "record");
        Objects.requireNonNull(codec, "A codec is required to decode a card cross-reference record; "
                + "the code page of fixed-width data is never assumed");
        if (record.recordLength() != RECORD_LENGTH) {
            throw new IllegalArgumentException("A card cross-reference record is "
                    + RECORD_LENGTH + " bytes as app/cpy/CVACT03Y.cpy declares, but the supplied "
                    + "span is " + record.recordLength() + ". If the row is "
                    + FILLER_OFFSET + " bytes because the source data omits the trailing FILLER X("
                    + FILLER_LENGTH + "), widen it first with "
                    + "FixedWidthCodec.padToDeclaredWidth(row, " + RECORD_LENGTH + ")");
        }
        return new CardXrefRecord(codec.readPicX(record, XREF_CARD_NUM),
                codec.readPic9AsInt(record, XREF_CUST_ID),
                codec.readPic9(record, XREF_ACCT_ID));
    }

    // =================================================================================================
    // Encoding. Always the full 50-byte image, FILLER included, because CBACT03C DISPLAYs the entire
    // record area and the emitted bytes are therefore observable output that parity compares.
    // =================================================================================================

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image under an explicitly named
     * code page.
     *
     * @param charset the target code page, named explicitly by the caller
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException     if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the
     *                                  digits and the space
     */
    public byte[] encode(Charset charset) {
        return encode(new FixedWidthCodec(charset));
    }

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image using a codec the caller
     * already holds.
     *
     * <p>The image is complete and positionally exact:
     * <ul>
     *   <li>{@code [0, 16)} the card number, left justified and space-padded on the right;</li>
     *   <li>{@code [16, 25)} the customer id, right justified and zero-filled on the left;</li>
     *   <li>{@code [25, 36)} the account id, right justified and zero-filled on the left - so
     *       account 50 is written {@code 00000000050}, exactly as the fixture holds it;</li>
     *   <li>{@code [36, 50)} the trailing {@code FILLER}, fourteen spaces.</li>
     * </ul>
     *
     * @param codec the codec for the target code page
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] encode(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).toByteArray();
    }

    /**
     * Renders this record into a {@link FixedWidthRecord} span, for a caller that wants to keep
     * addressing it by offset - a repository assembling a keyed write, or a test asserting on a
     * particular span.
     *
     * <p>The span is allocated through {@link FixedWidthCodec#newRecord(RecordLayout)}, which
     * initialises every declared span from {@link #LAYOUT} <em>before</em> any field is written. That
     * is what puts the fourteen spaces into the trailing {@code FILLER}: it is emitted by the layout
     * itself, so it cannot be forgotten by an encode path, and it does not depend on this method
     * remembering to write it.
     *
     * @param codec the codec for the target code page
     * @return a freshly allocated span of {@value #RECORD_LENGTH} bytes carrying this record
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to encode a card cross-reference record; "
                + "the code page of fixed-width data is never assumed");
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        codec.writePicX(record, XREF_CARD_NUM, xrefCardNum);
        codec.writePic9(record, XREF_CUST_ID, xrefCustId);
        codec.writePic9(record, XREF_ACCT_ID, xrefAcctId);
        return record;
    }

    // =================================================================================================
    // Field accessors. Plain, unannotated getters: they are the JSON form as well as the Java one.
    // =================================================================================================

    /**
     * {@code XREF-CARD-NUM PIC X(16)}, exactly as held - untrimmed, leading zero intact.
     *
     * @return the card number; never {@code null}, never longer than
     *         {@value #XREF_CARD_NUM_LENGTH} characters
     */
    public String xrefCardNum() {
        return xrefCardNum;
    }

    /**
     * {@code XREF-CUST-ID PIC 9(09)}.
     *
     * @return the customer id; never negative, never above {@link #XREF_CUST_ID_MAX_VALUE}
     */
    public int xrefCustId() {
        return xrefCustId;
    }

    /**
     * {@code XREF-ACCT-ID PIC 9(11)}.
     *
     * @return the account id; never negative, never above {@link #XREF_ACCT_ID_MAX_VALUE}
     */
    public long xrefAcctId() {
        return xrefAcctId;
    }

    // =================================================================================================
    // The two keys, named for their access paths. A repository binds a keyed read through these rather
    // than through a raw offset, so the CVACT02Y-offset-16 confusion cannot be made by accident.
    // =================================================================================================

    /**
     * This record's key on the <strong>base {@code CCXREF} KSDS</strong>: {@code XREF-CARD-NUM}, the
     * {@value #XREF_CARD_NUM_LENGTH} bytes at {@code [0, 16)}.
     *
     * <p>Returned as the raw {@code PIC X(16)} image, untrimmed and with any leading zero intact,
     * because that is the byte sequence VSAM matches on.
     *
     * @return the base-KSDS key; never {@code null}
     */
    public String cardNumberKey() {
        return xrefCardNum;
    }

    /**
     * This record's key on the <strong>{@code CXACAIX} alternate-index path</strong>:
     * {@code XREF-ACCT-ID}, the {@value #XREF_ACCT_ID_LENGTH} bytes at {@code [25, 36)}.
     *
     * <p>Offset 25 - not 16. The alternate index is a second access path over the same base cluster,
     * never a second copy of the data, which is how {@code app/jcl/INTCALC.jcl} is able to open the
     * cross-reference as both {@code XREFFILE} and {@code XREFFIL1} within one step.
     *
     * @return the alternate-index key; never negative
     */
    public long accountIdAlternateIndexKey() {
        return xrefAcctId;
    }

    // =================================================================================================
    // Value semantics. Field-by-field equality is what the parity differ and the COBOL
    // 9300-CHECK-CHANGE-IN-REC comparison both need, and it keeps every assertion deterministic.
    // =================================================================================================

    /**
     * Compares field by field, in copybook order. The trailing {@code FILLER} contributes nothing,
     * because its content is fixed by the layout and identical for every instance.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code CardXrefRecord} whose three fields all match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardXrefRecord that)) {
            return false;
        }
        return xrefCustId == that.xrefCustId
                && xrefAcctId == that.xrefAcctId
                && xrefCardNum.equals(that.xrefCardNum);
    }

    /**
     * A hash consistent with {@link #equals(Object)} over the same three fields.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(xrefCardNum, xrefCustId, xrefAcctId);
    }

    /**
     * A diagnostic rendering that names each field with its copybook name, so a failing assertion
     * reads in the vocabulary of {@code CVACT03Y} rather than of Java, and that withholds the values
     * themselves per {@link SensitiveDiagnostics}.
     *
     * <p>All three of this record's fields are sensitive and there is nothing else in it: the
     * cross-reference exists precisely to link a card number to a customer and an account, so rendering
     * it in full published the association it was built to hold. Each is masked to its last four
     * characters at full stored width, which is enough to correlate two log lines about the same record
     * and not enough to reconstruct any of them.
     *
     * <p>This is a diagnostic only and is never the record's wire form: the
     * {@value #RECORD_LENGTH}-byte image comes from {@link #encode(Charset)}, which returns real bytes
     * because a caller asked for them by name.
     *
     * @return a single-line description of this record, safe to log
     */
    @Override
    public String toString() {
        return "CARD-XREF-RECORD["
                + XREF_CARD_NUM_NAME + "='" + SensitiveDiagnostics.maskPan(xrefCardNum) + '\''
                + ", " + XREF_CUST_ID_NAME + '='
                + SensitiveDiagnostics.maskIdentifier(xrefCustId, XREF_CUST_ID_LENGTH)
                + ", " + XREF_ACCT_ID_NAME + '='
                + SensitiveDiagnostics.maskIdentifier(xrefAcctId, XREF_ACCT_ID_LENGTH)
                + ']';
    }
}
