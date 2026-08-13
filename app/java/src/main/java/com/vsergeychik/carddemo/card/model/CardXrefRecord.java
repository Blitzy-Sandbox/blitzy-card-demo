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
 * <p>{@code app/jcl/INTCALC.jcl} proves the point by opening the cross-reference twice in one step, as
 * {@code XREFFILE} on the base KSDS and {@code XREFFIL1} on the alternate-index path, which is exactly why
 * both keys are first-class members of this single type.
 */
public final class CardXrefRecord {
    /**
     * The declared record width in bytes: the {@code RECLN 50} of the copybook's own header, and the sum of
     * the four spans declared below.
     */
    public static final int RECORD_LENGTH = 50;

    /**
     * The copybook name of the card number field, verbatim: {@code XREF-CARD-NUM}.
     */
    public static final String XREF_CARD_NUM_NAME = "XREF-CARD-NUM";

    /**
     * Absolute 0-based offset of {@code XREF-CARD-NUM PIC X(16)}: the first field of the record.
     */
    public static final int XREF_CARD_NUM_OFFSET = 0;

    /**
     * Declared width of {@code XREF-CARD-NUM PIC X(16)}, in bytes.
     */
    public static final int XREF_CARD_NUM_LENGTH = 16;

    /**
     * The copybook name of the customer id field, verbatim: {@code XREF-CUST-ID}.
     */
    public static final String XREF_CUST_ID_NAME = "XREF-CUST-ID";

    /**
     * Absolute 0-based offset of {@code XREF-CUST-ID PIC 9(09)}, immediately after the card number.
     */
    public static final int XREF_CUST_ID_OFFSET = 16;

    /**
     * Declared width of {@code XREF-CUST-ID PIC 9(09)}: nine zoned {@code DISPLAY} digits.
     */
    public static final int XREF_CUST_ID_LENGTH = 9;

    /**
     * The copybook name of the account id field, verbatim: {@code XREF-ACCT-ID}.
     */
    public static final String XREF_ACCT_ID_NAME = "XREF-ACCT-ID";

    /**
     * Absolute 0-based offset of {@code XREF-ACCT-ID PIC 9(11)}.
     */
    public static final int XREF_ACCT_ID_OFFSET = 25;

    /**
     * Declared width of {@code XREF-ACCT-ID PIC 9(11)}: eleven zoned {@code DISPLAY} digits.
     */
    public static final int XREF_ACCT_ID_LENGTH = 11;

    /**
     * Absolute 0-based offset of the trailing {@code FILLER PIC X(14)}.
     */
    public static final int FILLER_OFFSET = 36;

    /**
     * Declared width of the trailing {@code FILLER PIC X(14)}, in bytes.
     */
    public static final int FILLER_LENGTH = 14;

    /**
     * {@code XREF-CARD-NUM PIC X(16)} - the base {@code CCXREF} KSDS key.
     */
    public static final FieldSpan XREF_CARD_NUM =
            FieldSpan.alphanumeric(XREF_CARD_NUM_NAME, XREF_CARD_NUM_OFFSET, XREF_CARD_NUM_LENGTH);

    /**
     * {@code XREF-CUST-ID PIC 9(09)} - unsigned zoned {@code DISPLAY}, right justified and zero-filled on
     * the left.
     */
    public static final FieldSpan XREF_CUST_ID =
            FieldSpan.unsignedNumeric(XREF_CUST_ID_NAME, XREF_CUST_ID_OFFSET, XREF_CUST_ID_LENGTH);

    /**
     * {@code XREF-ACCT-ID PIC 9(11)} - the {@code CXACAIX} alternate-index key.
     */
    public static final FieldSpan XREF_ACCT_ID =
            FieldSpan.unsignedNumeric(XREF_ACCT_ID_NAME, XREF_ACCT_ID_OFFSET, XREF_ACCT_ID_LENGTH);

    /**
     * The trailing {@code FILLER PIC X(14)}, declared as a real positioned span rather than left as an
     * implicit gap.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete self-checking layout, spans in copybook declaration order.
     */
    public static final RecordLayout LAYOUT =
            RecordLayout.of(RECORD_LENGTH, XREF_CARD_NUM, XREF_CUST_ID, XREF_ACCT_ID, FILLER);

    /**
     * The largest value {@code XREF-CUST-ID PIC 9(09)} can hold: nine nines.
     */
    public static final int XREF_CUST_ID_MAX_VALUE = (int) maxUnsignedValue(XREF_CUST_ID_LENGTH);

    /**
     * The largest value {@code XREF-ACCT-ID PIC 9(11)} can hold: eleven nines.
     */
    public static final long XREF_ACCT_ID_MAX_VALUE = maxUnsignedValue(XREF_ACCT_ID_LENGTH);

    private final String xrefCardNum;

    private final int xrefCustId;

    private final long xrefAcctId;

    /**
     * Creates a cross-reference record from its three named fields.
     *
     * <p>In particular this constructor does not apply COBOL {@code MOVE} truncation: an over-wide card
     * number or an out-of-range id is a defect in the caller, not a value to be quietly reshaped.
     *
     * @param xrefCardNum {@code XREF-CARD-NUM}; never {@code null}, and never longer than
     *     {@value #XREF_CARD_NUM_LENGTH} characters
     * @param xrefCustId {@code XREF-CUST-ID}; never negative, because {@code PIC 9} is unsigned, and never
     *     above {@link #XREF_CUST_ID_MAX_VALUE}
     * @param xrefAcctId {@code XREF-ACCT-ID}; never negative and never above
     *     {@link #XREF_ACCT_ID_MAX_VALUE}
     * @throws NullPointerException if {@code xrefCardNum} is {@code null}
     * @throws IllegalArgumentException if {@code xrefCardNum} is too long for its picture, or either id is
     *     negative or too large for its picture
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
     * The largest value an unsigned zoned {@code DISPLAY} field of the given digit count can hold, computed
     * from the digit count itself so that a bound can never drift away from the width it bounds.
     *
     * @param digits the digit count the receiving {@code PIC 9} item declares
     */
    private static long maxUnsignedValue(int digits) {
        return Long.parseLong("9".repeat(digits));
    }

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

    // The charset is always an explicit argument or is carried by an explicitly-constructed codec: nothing
    // here consults a platform default, in any path, because the pad and digit bytes themselves differ
    // between IBM037 and US-ASCII.

    /**
     * Decodes a stored record from its {@value #RECORD_LENGTH} bytes under an explicitly named code page.
     *
     * @param record the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param charset the code page of the stored bytes, named explicitly by the caller - {@code IBM037} for
     *     the EBCDIC datasets, {@code US-ASCII} for the text fixtures
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH} bytes, if
     *     either numeric span holds a non-digit, or if {@code charset} is not a single-byte code page for the
     *     digits and the space
     */
    public static CardXrefRecord decode(byte[] record, Charset charset) {
        return decode(record, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a stored record from its {@value #RECORD_LENGTH} bytes using a codec the caller already
     * holds.
     *
     * @param record the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param codec the codec for the code page of the stored bytes
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH} bytes, or
     *     either numeric span holds a non-digit
     */
    public static CardXrefRecord decode(byte[] record, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to decode a card cross-reference record; "
                + "the code page of fixed-width data is never assumed");
        Objects.requireNonNull(record, "Stored bytes are required to decode a card cross-reference "
                + "record");
        return decodeSpan(codec.wrap(record, LAYOUT), codec);
    }

    /**
     * Decodes a record already wrapped as a {@link FixedWidthRecord} span, which is the form a repository
     * holds after reading a row.
     *
     * <p>A {@code PIC X} field is space-padded to its full declared width and that padding is part of the
     * field's value, because the parity differ compares the field byte for byte.
     *
     * @param record the wrapped record span; its declared length must be {@value #RECORD_LENGTH}
     * @param codec the codec for the code page of the record's bytes
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@value #RECORD_LENGTH}, or
     *     either numeric span holds a non-digit
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

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image under an explicitly named code
     * page.
     *
     * @param charset the target code page, named explicitly by the caller
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the digits and
     *     the space
     */
    public byte[] encode(Charset charset) {
        return encode(new FixedWidthCodec(charset));
    }

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image using a codec the caller
     * already holds.
     *
     * @param codec the codec for the target code page
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] encode(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).toByteArray();
    }

    /**
     * Renders this record into a {@link FixedWidthRecord} span, for a caller that wants to keep addressing
     * it by offset - a repository assembling a keyed write, or a test asserting on a particular span.
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

    /**
     * {@code XREF-CARD-NUM PIC X(16)}, exactly as held - untrimmed, leading zero intact.
     *
     * @return the card number; never {@code null}, never longer than {@value #XREF_CARD_NUM_LENGTH}
     *     characters
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

    /**
     * This record's key on the base {@code CCXREF} KSDS: {@code XREF-CARD-NUM}, the
     * {@value #XREF_CARD_NUM_LENGTH} bytes at {@code [0, 16)}.
     *
     * @return the base-KSDS key; never {@code null}
     */
    public String cardNumberKey() {
        return xrefCardNum;
    }

    /**
     * This record's key on the {@code CXACAIX} alternate-index path: {@code XREF-ACCT-ID}, the
     * {@value #XREF_ACCT_ID_LENGTH} bytes at {@code [25, 36)}.
     *
     * <p>The alternate index is a second access path over the same base cluster, never a second copy of the
     * data, which is how {@code app/jcl/INTCALC.jcl} is able to open the cross-reference as both
     * {@code XREFFILE} and {@code XREFFIL1} within one step.
     *
     * @return the alternate-index key; never negative
     */
    public long accountIdAlternateIndexKey() {
        return xrefAcctId;
    }

    // Field-by-field equality is what the parity differ and the COBOL 9300-CHECK-CHANGE-IN-REC comparison
    // both need, and it keeps every assertion deterministic.

    /**
     * Compares field by field, in copybook order.
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
     * A diagnostic rendering that names each field with its copybook name, so a failing assertion reads in
     * the vocabulary of {@code CVACT03Y} rather than of Java, and that withholds the values themselves per
     * {@link SensitiveDiagnostics}.
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
