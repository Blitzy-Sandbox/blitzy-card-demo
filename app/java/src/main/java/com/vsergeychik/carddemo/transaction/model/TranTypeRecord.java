package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The {@code TRANTYPE} transaction-type record: an immutable, 60-byte fixed-width value type translated
 * field for field from {@code app/cpy/CVTRA03Y.cpy}.
 *
 * <p>{@code app/cpy/CVTRA03Y.cpy} is 11 lines long and its header comment declares the width outright -
 * "Data-structure for transaction type (RECLN = 60)": which yields exactly one layout, with no ambiguity
 * anywhere in it: 2 + 50 + 8 = 60.
 */
public final class TranTypeRecord {
    /**
     * The declared record width in bytes, from {@code app/cpy/CVTRA03Y.cpy}'s own header comment "RECLN =
     * 60": {@code TRAN-TYPE} 2 + {@code TRAN-TYPE-DESC} 50 + {@code FILLER} 8.
     */
    public static final int RECORD_LENGTH = 60;

    /**
     * The copybook name of the key item, verbatim: {@code TRAN-TYPE} - not {@code TRAN-TYPE-CD}.
     */
    public static final String TRAN_TYPE_FIELD = "TRAN-TYPE";

    /**
     * The copybook name of the description item, verbatim: {@code TRAN-TYPE-DESC}.
     */
    public static final String TRAN_TYPE_DESC_FIELD = "TRAN-TYPE-DESC";

    /**
     * {@code TRAN-TYPE PIC X(02)} begins the record - 1-based position 1, 0-based offset 0.
     */
    public static final int TRAN_TYPE_OFFSET = 0;

    /**
     * {@code TRAN-TYPE PIC X(02)} is 2 bytes wide.
     */
    public static final int TRAN_TYPE_LENGTH = 2;

    /**
     * The width of the {@code TRANTYPE} KSDS key in bytes.
     */
    public static final int TRAN_TYPE_KEY_LENGTH = 2;

    /**
     * {@code TRAN-TYPE-DESC PIC X(50)} - 1-based positions 3 to 52, 0-based offset 2.
     */
    public static final int TRAN_TYPE_DESC_OFFSET = 2;

    /**
     * {@code TRAN-TYPE-DESC PIC X(50)} is 50 bytes wide, and is read at all 50.
     */
    public static final int TRAN_TYPE_DESC_LENGTH = 50;

    /**
     * The trailing {@code FILLER PIC X(08)} - 1-based positions 53 to 60, 0-based offset 52.
     */
    public static final int FILLER_OFFSET = 52;

    /**
     * The trailing {@code FILLER PIC X(08)} is 8 bytes wide, and is always emitted.
     */
    public static final int FILLER_LENGTH = 8;

    /**
     * The width of {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, the receiver of this record's description on
     * the detail report [{@code app/cpy/CVTRA07Y.cpy:22}], as moved by {@code app/cbl/CBTRN03C.cbl:366}.
     */
    public static final int REPORT_TYPE_DESC_LENGTH = 15;

    /**
     * Descriptor for {@code TRAN-TYPE PIC X(02)} at offset 0.
     */
    public static final FieldSpan TRAN_TYPE =
            FieldSpan.alphanumeric(TRAN_TYPE_FIELD, TRAN_TYPE_OFFSET, TRAN_TYPE_LENGTH);

    /**
     * Descriptor for {@code TRAN-TYPE-DESC PIC X(50)} at offset 2.
     */
    public static final FieldSpan TRAN_TYPE_DESC = FieldSpan.alphanumeric(
            TRAN_TYPE_DESC_FIELD, TRAN_TYPE_DESC_OFFSET, TRAN_TYPE_DESC_LENGTH);

    /**
     * Descriptor for the trailing {@code FILLER PIC X(08)} at offset 52.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    public static final RecordLayout LAYOUT =
            RecordLayout.of(RECORD_LENGTH, TRAN_TYPE, TRAN_TYPE_DESC, FILLER);

    static {
        verifyDeclaredWidth(LAYOUT);
    }

    private final byte[] image;

    private final FixedWidthCodec codec;

    private TranTypeRecord(byte[] image, FixedWidthCodec codec) {
        this.image = image;
        this.codec = codec;
    }

    /**
     * Sums the layout's storage spans and confirms the total is exactly {@link #RECORD_LENGTH} - the
     * total-width self-check, expressed so that it can be exercised rather than merely asserted.
     *
     * @param layout the layout to measure; normally {@link #LAYOUT}
     * @return the measured total, always {@link #RECORD_LENGTH} when it returns at all
     * @throws NullPointerException if {@code layout} is {@code null}
     * @throws IllegalStateException if the storage spans do not sum to {@link #RECORD_LENGTH}, naming both
     *     the measured total and the shortfall or excess
     */
    public static int verifyDeclaredWidth(RecordLayout layout) {
        Objects.requireNonNull(layout, "A record layout is required to verify the declared width of "
                + "TRAN-TYPE-RECORD (CVTRA03Y, RECLN = 60)");
        int total = 0;
        for (FieldSpan span : layout.storageSpans()) {
            total += span.length();
        }
        if (total != RECORD_LENGTH) {
            throw new IllegalStateException("CVTRA03Y declares TRAN-TYPE-RECORD as "
                    + RECORD_LENGTH + " byte(s) - TRAN-TYPE 2 + TRAN-TYPE-DESC 50 + FILLER 8 - but "
                    + "the supplied layout's storage spans total " + total + " byte(s), which is "
                    + Math.abs(RECORD_LENGTH - total) + " byte(s) "
                    + (total < RECORD_LENGTH ? "short. A dropped trailing FILLER X(08) is the usual "
                            + "cause" : "too many"));
        }
        return total;
    }

    /**
     * Confirms this type's own {@link #LAYOUT} sums to {@link #RECORD_LENGTH}.
     *
     * @return {@link #RECORD_LENGTH}, always 60
     * @throws IllegalStateException if the layout has been changed to describe any other width
     */
    public static int verifyDeclaredWidth() {
        return verifyDeclaredWidth(LAYOUT);
    }

    /**
     * The validated {@code CVTRA03Y} layout, for a repository or parity harness that serialises,
     * deserialises or diffs this record field by field.
     *
     * @return {@link #LAYOUT}; {@link RecordLayout} is immutable and copies its span list, so this is safe
     *     to hand out
     */
    public static RecordLayout layout() {
        return LAYOUT;
    }

    public static TranTypeRecord decode(byte[] record, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to decode a TRANTYPE row: the code page of "
                + "fixed-width mainframe data is always stated explicitly, never assumed");
        Objects.requireNonNull(record, "Stored bytes are required to decode a TRANTYPE row; call "
                + "of(String, String, Charset) to build a record from field values instead");
        requireDeclaredWidth(record.length, "row");
        return new TranTypeRecord(codec.wrap(record, LAYOUT).toByteArray(), codec);
    }

    /**
     * Decodes a stored {@code TRANTYPE} row under an explicitly named code page.
     *
     * @param record the stored 60 bytes
     * @param charset the code page of the bytes, named by the caller and never defaulted
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record.length} is not {@link #RECORD_LENGTH}, or if
     *     {@code charset} is not a single-byte code page for the digits, the sign overpunch characters and the
     *     space
     */
    public static TranTypeRecord decode(byte[] record, Charset charset) {
        return decode(record, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a {@code TRANTYPE} row that has already been read as text - one 60-character line of
     * {@code app/data/ASCII/trantype.txt}, for instance.
     *
     * @param row the 60-character row image, trailing padding included and nothing trimmed
     * @param codec the codec whose charset the row is encoded under
     * @return the decoded record
     * @throws NullPointerException if {@code row} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code row} is not {@link #RECORD_LENGTH} characters long, or
     *     does not encode to exactly that many bytes
     */
    public static TranTypeRecord decode(String row, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to decode a TRANTYPE row image");
        Objects.requireNonNull(row, "A row image is required; a null row cannot be distinguished "
                + "from a 60-byte record of spaces, and the two mean different things");
        requireDeclaredWidth(row.length(), "row image");
        return decode(codec.encodeImage(row, "a TRANTYPE row image"), codec);
    }

    /**
     * Decodes a {@code TRANTYPE} row image under an explicitly named code page.
     *
     * @param row the 60-character row image
     * @param charset the code page of the row, named by the caller and never defaulted
     * @return the decoded record
     * @throws NullPointerException if {@code row} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code row} is not {@link #RECORD_LENGTH} characters long
     */
    public static TranTypeRecord decode(String row, Charset charset) {
        return decode(row, new FixedWidthCodec(charset));
    }

    public static TranTypeRecord of(String tranType, String tranTypeDesc, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to build a TRANTYPE record");
        Objects.requireNonNull(tranType, "TRAN-TYPE is required; move SPACES explicitly to build a "
                + "record with a blank key");
        Objects.requireNonNull(tranTypeDesc, "TRAN-TYPE-DESC is required; move SPACES explicitly to "
                + "build a record with a blank description");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, TRAN_TYPE, tranType);
        codec.writePicX(area, TRAN_TYPE_DESC, tranTypeDesc);
        return new TranTypeRecord(area.toByteArray(), codec);
    }

    /**
     * Builds a {@code TRANTYPE} record from field values under an explicitly named code page.
     *
     * @param tranType the two-character type code
     * @param tranTypeDesc the description, padded to 50 characters here
     * @param charset the code page of the record, named by the caller and never defaulted
     * @return a record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if any argument is {@code null}
     */
    public static TranTypeRecord of(String tranType, String tranTypeDesc, Charset charset) {
        return of(tranType, tranTypeDesc, new FixedWidthCodec(charset));
    }

    /**
     * An initialised, empty {@code TRANTYPE} record: 60 bytes, every one of them the charset's space byte,
     * with the {@code FILLER} span present and space-filled.
     *
     * @param codec the codec whose charset the record is encoded under
     * @return a space-filled record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public static TranTypeRecord empty(FixedWidthCodec codec) {
        return of("", "", codec);
    }

    /**
     * An initialised, empty {@code TRANTYPE} record under an explicitly named code page.
     *
     * @param charset the code page of the record, named by the caller and never defaulted
     * @return a space-filled record of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public static TranTypeRecord empty(Charset charset) {
        return empty(new FixedWidthCodec(charset));
    }

    private static void requireDeclaredWidth(int actual, String what) {
        if (actual != RECORD_LENGTH) {
            throw new IllegalArgumentException("A TRANTYPE " + what + " is " + actual
                    + " unit(s) wide but CVTRA03Y declares TRAN-TYPE-RECORD as " + RECORD_LENGTH
                    + " (TRAN-TYPE 2 + TRAN-TYPE-DESC 50 + FILLER 8), and CBTRN03C:73-75 splits it as "
                    + "2 + 58. A fixed-width record is exactly its declared width: neither pad nor "
                    + "truncate it here, because a differently sized row means the layout and the "
                    + "data disagree");
        }
    }

    /**
     * {@code TRAN-TYPE PIC X(02)} - the transaction type code, exactly 2 characters and untrimmed.
     *
     * @return the 2-character type code, {@code 01} through {@code 07} in the shipped data
     */
    public String tranType() {
        return codec.readPicX(area(), TRAN_TYPE);
    }

    /**
     * The {@code TRANTYPE} KSDS key, which is {@code TRAN-TYPE} viewed in its role as the record key.
     *
     * @return the 2-character key image, always {@link #TRAN_TYPE_KEY_LENGTH} characters
     */
    public String tranTypeKey() {
        return tranType();
    }

    /**
     * {@code TRAN-TYPE-DESC PIC X(50)} - the description, untrimmed: all 50 characters, trailing space
     * padding included.
     *
     * @return exactly {@link #TRAN_TYPE_DESC_LENGTH} characters
     */
    public String tranTypeDesc() {
        return codec.readPicX(area(), TRAN_TYPE_DESC);
    }

    /**
     * Performs a COBOL alphanumeric {@code MOVE} of {@code TRAN-TYPE-DESC} into a receiver of the given
     * width: padded on the right when the receiver is wider, and truncated on the right when it is
     * narrower.
     *
     * @param receiverLength the receiving field's declared width in characters; at least 1
     * @return an image of exactly {@code receiverLength} characters
     * @throws IllegalArgumentException if {@code receiverLength} is below 1
     */
    public String tranTypeDescMovedTo(int receiverLength) {
        return codec.movePicX(tranTypeDesc(), receiverLength);
    }

    /**
     * The trailing {@code FILLER PIC X(08)} as text, exactly as this record carries it.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return codec.readPicX(area(), FILLER);
    }

    /**
     * The raw bytes of {@code TRAN-TYPE}, for a caller reproducing a {@code DISPLAY} or diffing byte for
     * byte.
     *
     * @return a copy of the {@link #TRAN_TYPE_LENGTH} bytes at offset {@link #TRAN_TYPE_OFFSET}
     */
    public byte[] tranTypeBytes() {
        return area().readSpanBytes(TRAN_TYPE);
    }

    /**
     * The raw bytes of {@code TRAN-TYPE-DESC}, all {@link #TRAN_TYPE_DESC_LENGTH} of them.
     *
     * @return a copy of the 50 bytes at offset {@link #TRAN_TYPE_DESC_OFFSET}
     */
    public byte[] tranTypeDescBytes() {
        return area().readSpanBytes(TRAN_TYPE_DESC);
    }

    /**
     * The raw bytes of the trailing {@code FILLER}, carried verbatim from wherever this record came from.
     *
     * @return a copy of the {@link #FILLER_LENGTH} bytes at offset {@link #FILLER_OFFSET}
     */
    public byte[] fillerBytes() {
        return area().readSpanBytes(FILLER);
    }

    /**
     * The complete record as it would be written to the dataset: exactly {@link #RECORD_LENGTH} bytes,
     * {@code FILLER} included and unaltered.
     *
     * @return a fresh copy of all 60 bytes; the internal array is never handed out
     */
    public byte[] toByteArray() {
        return image.clone();
    }

    /**
     * The complete record as a 60-character image, for reproducing a COBOL {@code DISPLAY} of
     * {@code TRAN-TYPE-RECORD} exactly.
     *
     * @return exactly {@link #RECORD_LENGTH} characters, untrimmed
     */
    public String image() {
        return area().readString(0, RECORD_LENGTH);
    }

    /**
     * The record width in bytes, always {@link #RECORD_LENGTH}.
     *
     * @return 60
     */
    public int recordLength() {
        return image.length;
    }

    /**
     * The code page this record's bytes are encoded in, as supplied by the caller that created it.
     *
     * @return the charset, never {@code null} and never a platform default
     */
    public Charset charset() {
        return codec.charset();
    }

    private FixedWidthRecord area() {
        return FixedWidthRecord.copyOf(image, RECORD_LENGTH, codec.charset());
    }

    /**
     * Two records are equal when their 60 bytes and their code page are equal.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code TranTypeRecord} with the same bytes and charset
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranTypeRecord that)) {
            return false;
        }
        return Arrays.equals(image, that.image) && codec.charset().equals(that.codec.charset());
    }

    /**
     * A hash consistent with {@link #equals(Object)}, over the whole image and the code page.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(image) + codec.charset().hashCode();
    }

    /**
     * A diagnostic rendering naming the record, its key, its description trimmed of trailing padding for
     * legibility, its reserved bytes and its charset.
     *
     * @return for example
     *     {@code TRAN-TYPE-RECORD[TRAN-TYPE=04, TRAN-TYPE-DESC='Authorization' (50 bytes), FILLER='00000000', 60 bytes, US-ASCII]}
     */
    @Override
    public String toString() {
        return "TRAN-TYPE-RECORD[TRAN-TYPE=" + tranType()
                + ", TRAN-TYPE-DESC='" + codec.readPicXTrimmed(area(), TRAN_TYPE_DESC)
                + "' (" + TRAN_TYPE_DESC_LENGTH + " bytes), FILLER='" + filler()
                + "', " + recordLength() + " bytes, " + charset().name() + "]";
    }
}
