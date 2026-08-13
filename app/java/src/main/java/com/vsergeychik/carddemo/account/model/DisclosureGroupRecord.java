package com.vsergeychik.carddemo.account.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;

/**
 * The disclosure group record of {@code app/cpy/CVTRA02Y.cpy}, exactly 50 bytes.
 *
 * <p>{@code app/data/ASCII/discgrp.txt} holds 51 rows of exactly 50 bytes.
 */
public final class DisclosureGroupRecord {
    /**
     * The declared record width in bytes, from the copybook header
     * {@code Data-structure for disclosure group (RECLN = 50)} at {@code app/cpy/CVTRA02Y.cpy:L2}.
     */
    public static final int RECORD_LENGTH = 50;

    /**
     * The {@code DIS-GROUP-KEY} group item name, {@code app/cpy/CVTRA02Y.cpy:L5}.
     */
    public static final String DIS_GROUP_KEY_NAME = "DIS-GROUP-KEY";

    /**
     * {@code DIS-GROUP-KEY} begins the record, {@code app/cpy/CVTRA02Y.cpy:L5}.
     */
    public static final int DIS_GROUP_KEY_OFFSET = 0;

    /**
     * {@code DIS-GROUP-KEY} is 16 bytes: {@code X(10)} + {@code X(02)} + {@code 9(04)},
     * {@code app/cpy/CVTRA02Y.cpy:L6-L8}.
     */
    public static final int DIS_GROUP_KEY_LENGTH = 16;

    /**
     * The {@code DIS-ACCT-GROUP-ID} item name, {@code app/cpy/CVTRA02Y.cpy:L6}.
     */
    public static final String DIS_ACCT_GROUP_ID_NAME = "DIS-ACCT-GROUP-ID";

    /**
     * {@code DIS-ACCT-GROUP-ID} starts at byte 0, {@code app/cpy/CVTRA02Y.cpy:L6}.
     */
    public static final int DIS_ACCT_GROUP_ID_OFFSET = 0;

    /**
     * {@code DIS-ACCT-GROUP-ID PIC X(10)}, {@code app/cpy/CVTRA02Y.cpy:L6}.
     */
    public static final int DIS_ACCT_GROUP_ID_LENGTH = 10;

    /**
     * The {@code DIS-TRAN-TYPE-CD} item name, {@code app/cpy/CVTRA02Y.cpy:L7}.
     */
    public static final String DIS_TRAN_TYPE_CD_NAME = "DIS-TRAN-TYPE-CD";

    /**
     * {@code DIS-TRAN-TYPE-CD} starts at byte 10, {@code app/cpy/CVTRA02Y.cpy:L7}.
     */
    public static final int DIS_TRAN_TYPE_CD_OFFSET = 10;

    /**
     * {@code DIS-TRAN-TYPE-CD PIC X(02)}, {@code app/cpy/CVTRA02Y.cpy:L7}.
     */
    public static final int DIS_TRAN_TYPE_CD_LENGTH = 2;

    /**
     * The {@code DIS-TRAN-CAT-CD} item name, {@code app/cpy/CVTRA02Y.cpy:L8}.
     */
    public static final String DIS_TRAN_CAT_CD_NAME = "DIS-TRAN-CAT-CD";

    /**
     * {@code DIS-TRAN-CAT-CD} starts at byte 12, {@code app/cpy/CVTRA02Y.cpy:L8}.
     */
    public static final int DIS_TRAN_CAT_CD_OFFSET = 12;

    /**
     * {@code DIS-TRAN-CAT-CD PIC 9(04)}, {@code app/cpy/CVTRA02Y.cpy:L8}.
     */
    public static final int DIS_TRAN_CAT_CD_LENGTH = 4;

    /**
     * The {@code DIS-INT-RATE} item name, {@code app/cpy/CVTRA02Y.cpy:L9}.
     */
    public static final String DIS_INT_RATE_NAME = "DIS-INT-RATE";

    /**
     * {@code DIS-INT-RATE} starts at byte 16, {@code app/cpy/CVTRA02Y.cpy:L9} - immediately after the
     * 16-byte key.
     */
    public static final int DIS_INT_RATE_OFFSET = 16;

    /**
     * {@code p} in {@code DIS-INT-RATE PIC S9(04)V99}: four digit positions left of the implied decimal
     * point, {@code app/cpy/CVTRA02Y.cpy:L9}.
     */
    public static final int DIS_INT_RATE_INTEGER_DIGITS = 4;

    /**
     * {@code s} in {@code DIS-INT-RATE PIC S9(04)V99}: two digit positions right of the implied decimal
     * point, {@code app/cpy/CVTRA02Y.cpy:L9}.
     */
    public static final int DIS_INT_RATE_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code DIS-INT-RATE} occupies {@code p + s} = 6 bytes and reserves no byte for its sign, which is
     * overpunched into the trailing byte.
     */
    public static final int DIS_INT_RATE_LENGTH = DIS_INT_RATE_INTEGER_DIGITS + DIS_INT_RATE_SCALE;

    /**
     * The reserved COBOL name of the trailing unnamed span, {@code app/cpy/CVTRA02Y.cpy:L10}.
     */
    public static final String FILLER_NAME = "FILLER";

    /**
     * {@code FILLER} starts at byte 22, {@code app/cpy/CVTRA02Y.cpy:L10}.
     */
    public static final int FILLER_OFFSET = 22;

    /**
     * {@code FILLER PIC X(28)}, {@code app/cpy/CVTRA02Y.cpy:L10}.
     */
    public static final int FILLER_LENGTH = 28;

    /**
     * The literal the interest calculator falls back to when a disclosure group is not found:
     * {@code MOVE 'DEFAULT' TO FD-DIS-ACCT-GROUP-ID} at {@code app/cbl/CBACT04C.cbl:L437}, reached only
     * when the first read returns file status {@code '23'} at {@code L436}.
     */
    public static final String DEFAULT_ACCT_GROUP_ID = "DEFAULT";

    /**
     * {@code DIS-ACCT-GROUP-ID PIC X(10)} at byte 0, {@code app/cpy/CVTRA02Y.cpy:L6}.
     */
    public static final FieldSpan DIS_ACCT_GROUP_ID_SPAN = FieldSpan.alphanumeric(
            DIS_ACCT_GROUP_ID_NAME, DIS_ACCT_GROUP_ID_OFFSET, DIS_ACCT_GROUP_ID_LENGTH);

    /**
     * {@code DIS-TRAN-TYPE-CD PIC X(02)} at byte 10, {@code app/cpy/CVTRA02Y.cpy:L7}.
     */
    public static final FieldSpan DIS_TRAN_TYPE_CD_SPAN = FieldSpan.alphanumeric(
            DIS_TRAN_TYPE_CD_NAME, DIS_TRAN_TYPE_CD_OFFSET, DIS_TRAN_TYPE_CD_LENGTH);

    /**
     * {@code DIS-TRAN-CAT-CD PIC 9(04)} at byte 12, {@code app/cpy/CVTRA02Y.cpy:L8}.
     */
    public static final FieldSpan DIS_TRAN_CAT_CD_SPAN = FieldSpan.unsignedNumeric(
            DIS_TRAN_CAT_CD_NAME, DIS_TRAN_CAT_CD_OFFSET, DIS_TRAN_CAT_CD_LENGTH);

    /**
     * {@code DIS-GROUP-KEY} at byte 0, {@code app/cpy/CVTRA02Y.cpy:L5}, as a 16-byte group overlay over the
     * same backing storage as its three elementary members.
     */
    public static final FieldSpan DIS_GROUP_KEY_SPAN = FieldSpan.redefining(
            DIS_GROUP_KEY_NAME, DIS_GROUP_KEY_OFFSET, DIS_GROUP_KEY_LENGTH,
            PictureKind.ALPHANUMERIC);

    /**
     * {@code DIS-INT-RATE PIC S9(04)V99} at byte 16, {@code app/cpy/CVTRA02Y.cpy:L9}.
     */
    public static final FieldSpan DIS_INT_RATE_SPAN = FieldSpan.signedScaled(
            DIS_INT_RATE_NAME, DIS_INT_RATE_OFFSET, DIS_INT_RATE_INTEGER_DIGITS,
            DIS_INT_RATE_SCALE);

    /**
     * {@code FILLER PIC X(28)} at byte 22, {@code app/cpy/CVTRA02Y.cpy:L10}.
     */
    public static final FieldSpan FILLER_SPAN = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    private static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            DIS_ACCT_GROUP_ID_SPAN,
            DIS_TRAN_TYPE_CD_SPAN,
            DIS_TRAN_CAT_CD_SPAN,
            DIS_GROUP_KEY_SPAN,
            DIS_INT_RATE_SPAN,
            FILLER_SPAN);

    // Both fields are final; the codec is immutable; the record area is mutable exactly as a COBOL record
    // area is, but is per-instance and is never handed out.

    private final FixedWidthCodec codec;

    private final FixedWidthRecord area;

    private DisclosureGroupRecord(FixedWidthCodec codec, FixedWidthRecord area) {
        this.codec = codec;
        this.area = area;
    }

    /**
     * Allocates a fresh, initialised record: the two character items and the {@code FILLER} are
     * space-filled and the two numeric items are zero-filled, which is the COBOL {@code INITIALIZE}
     * convention for the declared pictures.
     *
     * <p>No COBOL program writes this record, so neither image is parity-observable; the distinction is
     * documented only so a fixture round trip is predictable.
     *
     * @param charset the code page of the record's bytes, stated explicitly - the ASCII code page for
     *     {@code app/data/ASCII} text fixtures, the EBCDIC one for binary datasets
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode each digit, each sign overpunch
     *     character and the space to exactly one byte
     */
    public DisclosureGroupRecord(Charset charset) {
        this.codec = new FixedWidthCodec(charset);
        this.area = this.codec.newRecord(LAYOUT);
    }

    /**
     * Decodes a stored 50-byte image, which is the {@code READ DISCGRP-FILE INTO DIS-GROUP-RECORD} of
     * {@code app/cbl/CBACT04C.cbl:L416} and {@code L444}.
     *
     * @param record the stored bytes, exactly {@link #RECORD_LENGTH} of them
     * @param charset the code page of those bytes, stated explicitly
     * @return a record over a copy of {@code record}
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@link #RECORD_LENGTH} bytes, or
     *     {@code charset} is not a usable single-byte code page
     */
    public static DisclosureGroupRecord decode(byte[] record, Charset charset) {
        Objects.requireNonNull(record, "Stored bytes are required to decode a DIS-GROUP-RECORD; "
                + "call the (Charset) constructor to allocate a fresh, initialised record instead");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return new DisclosureGroupRecord(codec, codec.wrap(record, LAYOUT));
    }

    /**
     * Decodes a stored image supplied as text, for a caller that has read the row as a line of
     * {@code app/data/ASCII/discgrp.txt}.
     *
     * @param image the row image, exactly {@link #RECORD_LENGTH} characters under {@code charset}
     * @param charset the code page of the row, stated explicitly
     * @return a record over the encoded image
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if the image does not encode to exactly {@link #RECORD_LENGTH}
     *     bytes, or {@code charset} is not a usable single-byte code page
     */
    public static DisclosureGroupRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A row image is required to decode a disclosure group record");
        Objects.requireNonNull(charset, "A charset is required: a row image is characters and the "
                + "record is bytes, so the code page that maps between them must be stated "
                + "explicitly and is never derived from the platform");
        return decode(FixedWidthRecord.encodeText(image, charset, "a DIS-GROUP-RECORD image"),
                charset);
    }

    /**
     * The record's self-checking layout, for a repository or a field-by-field differ that needs to address
     * spans by descriptor or enumerate them by name.
     *
     * @return the immutable layout; its span list is unmodifiable
     */
    public static RecordLayout layout() {
        return LAYOUT;
    }

    /**
     * Reads {@code DIS-ACCT-GROUP-ID}, untrimmed: exactly {@value #DIS_ACCT_GROUP_ID_LENGTH} characters,
     * trailing spaces included.
     *
     * @return the field's {@value #DIS_ACCT_GROUP_ID_LENGTH} characters
     */
    public String disAcctGroupId() {
        return codec.readPicX(area, DIS_ACCT_GROUP_ID_SPAN);
    }

    /**
     * Writes {@code DIS-ACCT-GROUP-ID} with COBOL alphanumeric {@code MOVE} semantics: shorter values are
     * padded on the right with spaces and longer ones are truncated on the right, because a {@code PIC X}
     * receiver is filled from its leftmost position.
     *
     * @param value the sending value; may be shorter or longer than {@value #DIS_ACCT_GROUP_ID_LENGTH}, and
     *     may be empty
     * @throws NullPointerException if {@code value} is {@code null}; move an empty string to blank the
     *     field
     */
    public void disAcctGroupId(String value) {
        codec.writePicX(area, DIS_ACCT_GROUP_ID_SPAN, value);
    }

    /**
     * Reads {@code DIS-TRAN-TYPE-CD}, untrimmed: exactly {@value #DIS_TRAN_TYPE_CD_LENGTH} characters.
     *
     * @return the field's {@value #DIS_TRAN_TYPE_CD_LENGTH} characters
     */
    public String disTranTypeCd() {
        return codec.readPicX(area, DIS_TRAN_TYPE_CD_SPAN);
    }

    /**
     * Writes {@code DIS-TRAN-TYPE-CD} with COBOL alphanumeric {@code MOVE} semantics - right-padded with
     * spaces, truncated on the right - reproducing {@code MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD} at
     * {@code app/cbl/CBACT04C.cbl:L212}, an {@code X(02)} to {@code X(02)} move where sender and receiver
     * are the same width.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void disTranTypeCd(String value) {
        codec.writePicX(area, DIS_TRAN_TYPE_CD_SPAN, value);
    }

    /**
     * Reads {@code DIS-TRAN-CAT-CD} as an {@code int}.
     *
     * @return the value the field's four digits denote; {@code 1} for the fixture's {@code "0001"}
     * @throws IllegalArgumentException if the span does not hold four digits, which means the record is
     *     misaligned rather than merely unexpected
     */
    public int disTranCatCd() {
        return codec.readPic9AsInt(area, DIS_TRAN_CAT_CD_SPAN);
    }

    /**
     * The raw {@value #DIS_TRAN_CAT_CD_LENGTH}-character zero-filled image of {@code DIS-TRAN-CAT-CD}, for
     * building the composite key byte-exactly and for a differ that compares stored images rather than
     * decoded values.
     *
     * @return the field's four characters, for example {@code "0001"}
     */
    public String disTranCatCdImage() {
        return area.readSpan(DIS_TRAN_CAT_CD_SPAN);
    }

    /**
     * Writes {@code DIS-TRAN-CAT-CD} with COBOL numeric {@code MOVE} semantics: zero-filled on the left to
     * four digits, and truncated on the left when the value has more, because a numeric receiver is aligned
     * on its implied decimal point and so keeps its low-order digits.
     *
     * @param value the sending value; must not be negative, because {@code PIC 9} is an unsigned picture
     *     with no sign position
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void disTranCatCd(int value) {
        codec.writePic9(area, DIS_TRAN_CAT_CD_SPAN, value);
    }

    /**
     * Writes {@code DIS-TRAN-CAT-CD} from a digit string, which is the shape of a COBOL
     * {@code MOVE '05' TO} a numeric receiver: an alphanumeric literal into a numeric field, zero-filled
     * and truncated on the left exactly as {@link #disTranCatCd(int)} is.
     *
     * @param digits the sending digits; must be non-empty and contain only {@code '0'} to {@code '9'}
     * @throws NullPointerException if {@code digits} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is empty or holds a non-digit
     */
    public void disTranCatCd(String digits) {
        codec.writePic9(area, DIS_TRAN_CAT_CD_SPAN, digits);
    }

    /**
     * The composite {@code DIS-GROUP-KEY} as its {@value #DIS_GROUP_KEY_LENGTH} stored characters,
     * untrimmed - the concatenation of {@code DIS-ACCT-GROUP-ID}, {@code DIS-TRAN-TYPE-CD} and
     * {@code DIS-TRAN-CAT-CD}, read from the same backing bytes those three accessors address.
     *
     * @return the key's {@value #DIS_GROUP_KEY_LENGTH} characters, for example
     *     {@code "A00000000001" + "0001"}
     */
    public String disGroupKey() {
        return area.readSpan(DIS_GROUP_KEY_SPAN);
    }

    /**
     * The composite {@code DIS-GROUP-KEY} as a fresh {@value #DIS_GROUP_KEY_LENGTH}-byte array, for a
     * repository issuing a keyed read.
     *
     * @return a copy of the key's bytes
     */
    public byte[] disGroupKeyBytes() {
        return area.readSpanBytes(DIS_GROUP_KEY_SPAN);
    }

    /**
     * Reads {@code DIS-INT-RATE} as a {@link BigDecimal} whose {@link BigDecimal#scale()} is exactly
     * {@link #DIS_INT_RATE_SCALE}.
     *
     * @return the rate at scale {@link #DIS_INT_RATE_SCALE}
     * @throws IllegalArgumentException if the span does not hold a valid signed zoned image, which means
     *     the record is misaligned
     */
    public BigDecimal disIntRate() {
        return codec.readSignedScaled(area, DIS_INT_RATE_SPAN, DIS_INT_RATE_SCALE);
    }

    /**
     * The raw {@link #DIS_INT_RATE_LENGTH}-character zoned image of {@code DIS-INT-RATE}, sign overpunch
     * included - {@code "00150&#123;"} for {@code +15.00}.
     *
     * @return the field's {@link #DIS_INT_RATE_LENGTH} characters
     */
    public String disIntRateImage() {
        return area.readSpan(DIS_INT_RATE_SPAN);
    }

    /**
     * Writes {@code DIS-INT-RATE} into its declared {@code PIC S9(04)V99} receiver.
     *
     * @param value the rate to store
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void disIntRate(BigDecimal value) {
        codec.writeSignedScaled(area, DIS_INT_RATE_SPAN, value, DIS_INT_RATE_SCALE);
    }

    /**
     * Whether {@code DIS-INT-RATE} is zero by value.
     *
     * @return {@code true} when the rate is zero at any scale
     */
    public boolean disIntRateIsZero() {
        return disIntRate().compareTo(BigDecimal.ZERO) == 0;
    }

    /**
     * Whether {@code DIS-INT-RATE} is non-zero by value - the exact condition of
     * {@code IF DIS-INT-RATE NOT = 0} at {@code app/cbl/CBACT04C.cbl:L214}, which gates
     * {@code PERFORM 1300-COMPUTE-INTEREST} at {@code L215} and {@code PERFORM 1400-COMPUTE-FEES} at
     * {@code L216}.
     *
     * <p>Exposed alongside {@link #disIntRateIsZero()} so the one consumer reads like its COBOL and cannot
     * reintroduce the {@code equals}-on-zero defect.
     *
     * @return {@code true} when the rate is not zero
     */
    public boolean disIntRateIsNotZero() {
        return !disIntRateIsZero();
    }

    /**
     * The {@code FILLER} span as its {@value #FILLER_LENGTH} stored characters.
     *
     * <p>{@code FILLER} is non-referable in COBOL and is never read by any program, but it occupies
     * declared bytes and is therefore a first-class span here.
     *
     * @return the span's {@value #FILLER_LENGTH} characters
     */
    public String filler() {
        return area.readSpan(FILLER_SPAN);
    }

    /**
     * The {@code FILLER} span as a fresh {@value #FILLER_LENGTH}-byte array.
     *
     * @return a copy of the span's bytes
     */
    public byte[] fillerBytes() {
        return area.readSpanBytes(FILLER_SPAN);
    }

    /**
     * The complete {@value #RECORD_LENGTH}-byte image, as a fresh array.
     *
     * @return a copy of all {@value #RECORD_LENGTH} bytes
     */
    public byte[] encode() {
        return area.toByteArray();
    }

    /**
     * The complete {@value #RECORD_LENGTH}-character image, decoded under this record's charset.
     *
     * @return the record's {@value #RECORD_LENGTH} characters
     */
    public String encodeToString() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * The declared record width, always {@value #RECORD_LENGTH}.
     *
     * @return {@value #RECORD_LENGTH}
     */
    public int recordLength() {
        return area.recordLength();
    }

    /**
     * The code page this record's bytes are held in, as supplied at construction and never derived from the
     * platform.
     *
     * @return the charset
     */
    public Charset charset() {
        return area.charset();
    }

    private List<Object> items() {
        return List.of(disAcctGroupId(), disTranTypeCd(), disTranCatCd(), disIntRate(), filler());
    }

    /**
     * Value equality over all five declared items of the copybook, {@code FILLER} included, so no declared
     * byte is silently excluded from identity.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a disclosure group record with equal items
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DisclosureGroupRecord that)) {
            return false;
        }
        return items().equals(that.items());
    }

    /**
     * Hashes the same five items {@link #equals(Object)} compares, so the two are consistent.
     *
     * <p>The backing area is mutable, exactly as a COBOL record area is, so an instance must not be used as
     * a hash-map key while it is still being populated.
     *
     * @return the tuple's hash code
     */
    @Override
    public int hashCode() {
        return items().hashCode();
    }

    /**
     * A diagnostic rendering naming every item as the copybook spells it, with the character items quoted
     * so their trailing padding is visible and the rate shown both decoded and as its stored zoned image.
     *
     * @return the record's items, never {@code null}
     */
    @Override
    public String toString() {
        return "DisclosureGroupRecord["
                + DIS_ACCT_GROUP_ID_NAME + "='" + disAcctGroupId() + "', "
                + DIS_TRAN_TYPE_CD_NAME + "='" + disTranTypeCd() + "', "
                + DIS_TRAN_CAT_CD_NAME + "=" + disTranCatCdImage() + " (" + disTranCatCd() + "), "
                + DIS_INT_RATE_NAME + "=" + disIntRate() + " (image '" + disIntRateImage() + "'), "
                + FILLER_NAME + "='" + filler() + "', charset=" + charset().name() + ']';
    }
}
