package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The {@code TRANCATG} transaction-category record: 60 bytes with a 6-byte composite key, the single Java
 * type for copybook {@code app/cpy/CVTRA04Y.cpy}, whose header comment reads "Data-structure for
 * transaction category type (RECLN = 60)".
 *
 * <p>The expected values in this class are statically derived: COBOL cannot be executed in this
 * environment, so nothing here was captured from a live run.
 */
public final class TranCategoryRecord {
    /**
     * The declared record width, {@code RECLN = 60} from the copybook header comment and
     * {@code record-length: 60} from the {@code TRANCATG} dataset binding.
     */
    public static final int RECORD_LENGTH = 60;

    /**
     * The COBOL name of the composite key group, carried verbatim.
     */
    public static final String TRAN_CAT_KEY_NAME = "TRAN-CAT-KEY";

    /**
     * The 0-based offset of the {@code TRAN-CAT-KEY} group: 0, the start of the record, matching
     * {@code RECORD KEY IS FD-TRAN-CAT-KEY} at {@code app/cbl/CBTRN03C.cbl:48}.
     */
    public static final int TRAN_CAT_KEY_OFFSET = 0;

    /**
     * The width of the {@code TRAN-CAT-KEY} group: 6 bytes, {@code TRAN-TYPE-CD X(02)} plus
     * {@code TRAN-CAT-CD 9(04)}.
     */
    public static final int TRAN_CAT_KEY_LENGTH = 6;

    /**
     * The 0-based offset of {@code 10 TRAN-TYPE-CD PIC X(02)}: 0 (1-based bytes 1-2).
     */
    public static final int TRAN_TYPE_CD_OFFSET = 0;

    /**
     * The declared width of {@code 10 TRAN-TYPE-CD PIC X(02)}: 2 characters.
     */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /**
     * The 0-based offset of {@code 10 TRAN-CAT-CD PIC 9(04)}: 2 (1-based bytes 3-6).
     */
    public static final int TRAN_CAT_CD_OFFSET = 2;

    /**
     * The declared width of {@code 10 TRAN-CAT-CD PIC 9(04)}: 4 digits.
     */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /**
     * The 0-based offset of {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)}: 6 (1-based bytes 7-56).
     */
    public static final int TRAN_CAT_TYPE_DESC_OFFSET = 6;

    /**
     * The declared width of {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)}: 50 characters.
     */
    public static final int TRAN_CAT_TYPE_DESC_LENGTH = 50;

    /**
     * The 0-based offset of the trailing {@code 05 FILLER PIC X(04)}: 56 (1-based bytes 57-60).
     */
    public static final int FILLER_OFFSET = 56;

    /**
     * The declared width of the trailing {@code 05 FILLER PIC X(04)}: 4 bytes.
     */
    public static final int FILLER_LENGTH = 4;

    /**
     * {@code 10 TRAN-TYPE-CD PIC X(02)} - the transaction type code, the first half of the composite key.
     */
    public static final FieldSpan TRAN_TYPE_CD =
            FieldSpan.alphanumeric("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);

    /**
     * {@code 10 TRAN-CAT-CD PIC 9(04)} - the transaction category code, the second half of the composite
     * key.
     */
    public static final FieldSpan TRAN_CAT_CD =
            FieldSpan.unsignedNumeric("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);

    /**
     * {@code 05 TRAN-CAT-TYPE-DESC PIC X(50)} - the category description, read untrimmed.
     */
    public static final FieldSpan TRAN_CAT_TYPE_DESC = FieldSpan.alphanumeric(
            "TRAN-CAT-TYPE-DESC", TRAN_CAT_TYPE_DESC_OFFSET, TRAN_CAT_TYPE_DESC_LENGTH);

    /**
     * {@code 05 FILLER PIC X(04)} - the trailing reserved span, declared with no {@code VALUE} clause.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete layout in copybook declaration order: four storage spans, no overlay, no gap.
     */
    public static final RecordLayout LAYOUT = RecordLayout.of(
            RECORD_LENGTH, TRAN_TYPE_CD, TRAN_CAT_CD, TRAN_CAT_TYPE_DESC, FILLER);

    static {
        verifyDeclaredGeometry();
    }

    /**
     * The sum of the declared storage span widths, computed from {@link #LAYOUT} rather than restated as a
     * literal, so it cannot drift away from the spans it is meant to describe.
     *
     * @return 60 for a correctly declared layout
     */
    public static int sumOfDeclaredSpanWidths() {
        int total = 0;
        for (FieldSpan span : LAYOUT.storageSpans()) {
            total += span.length();
        }
        return total;
    }

    /**
     * Proves this class's declared geometry, and in particular that the composite key is 6 bytes rather
     * than {@code CVTRA01Y}'s 17.
     *
     * @throws IllegalStateException if any declared offset or width is inconsistent, naming the
     *     {@code CVTRA01Y} 17-byte trap where that is the likely cause
     */
    public static void verifyDeclaredGeometry() {
        verifyGeometry(TRAN_TYPE_CD_LENGTH, TRAN_CAT_CD_LENGTH, TRAN_CAT_KEY_LENGTH,
                TRAN_CAT_TYPE_DESC_OFFSET, sumOfDeclaredSpanWidths());
    }

    static void verifyGeometry(int typeCdLength,
                               int catCdLength,
                               int keyLength,
                               int descOffset,
                               int declaredTotal) {
        int keyItemsWidth = typeCdLength + catCdLength;
        if (keyItemsWidth != keyLength) {
            throw new IllegalStateException("CVTRA04Y's TRAN-CAT-KEY is declared as "
                    + keyLength + " byte(s) but its two elementary items, TRAN-TYPE-CD "
                    + "X(02) and TRAN-CAT-CD 9(04), occupy " + keyItemsWidth
                    + ". The key is a sub-span over exactly those two items and nothing else");
        }
        if (keyLength != 6) {
            throw new IllegalStateException("CVTRA04Y's TRAN-CAT-KEY must be 6 bytes - "
                    + "TRAN-TYPE-CD X(02) plus TRAN-CAT-CD 9(04) - but is declared as "
                    + keyLength + ". BEWARE THE NAMESAKE: app/cpy/CVTRA01Y.cpy declares a "
                    + "group with the IDENTICAL COBOL name TRAN-CAT-KEY that is 17 bytes wide "
                    + "(TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04)) and "
                    + "belongs to the TCATBALF dataset, not TRANCATG. If this width has become 17, "
                    + "the two records have been conflated");
        }
        if (descOffset != keyLength) {
            throw new IllegalStateException("TRAN-CAT-TYPE-DESC must begin at offset "
                    + keyLength + ", immediately after the 6-byte TRAN-CAT-KEY, but is "
                    + "declared at offset " + descOffset + ". An offset of 17 here "
                    + "means CVTRA01Y's 17-byte TRAN-CAT-KEY has been substituted for CVTRA04Y's "
                    + "6-byte one");
        }
        if (declaredTotal != RECORD_LENGTH) {
            throw new IllegalStateException("CVTRA04Y declares RECLN = " + RECORD_LENGTH
                    + " but the layout's storage spans sum to " + declaredTotal
                    + ". The record is TRAN-CAT-KEY 6 + TRAN-CAT-TYPE-DESC 50 + FILLER 4; a total of "
                    + "56 means the trailing FILLER X(04) has been dropped");
        }
    }

    private final byte[] image;

    private final Charset charset;

    private final String tranTypeCd;

    private final int tranCatCd;

    private final String tranCatTypeDesc;

    private final String filler;

    private final String tranCatKeyImage;

    private TranCategoryRecord(byte[] image,
                               Charset charset,
                               String tranTypeCd,
                               int tranCatCd,
                               String tranCatTypeDesc,
                               String filler,
                               String tranCatKeyImage) {
        this.image = image;
        this.charset = charset;
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
        this.tranCatTypeDesc = tranCatTypeDesc;
        this.filler = filler;
        this.tranCatKeyImage = tranCatKeyImage;
    }

    /**
     * Decodes a stored {@code TRANCATG} record - the Java equivalent of
     * {@code READ TRANCATG-FILE INTO TRAN-CAT-RECORD} at {@code app/cbl/CBTRN03C.cbl:505}.
     *
     * @param record the stored row, exactly {@link #RECORD_LENGTH} bytes; defensively copied
     * @param charset the code page of the stored bytes, stated explicitly
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@link #RECORD_LENGTH} bytes, if
     *     {@code charset} is not a single-byte code page for the digits and the space, or if
     *     {@code TRAN-CAT-CD} does not hold four digits
     */
    public static TranCategoryRecord decode(byte[] record, Charset charset) {
        Objects.requireNonNull(record, "TRANCATG record bytes are required; use "
                + "of(String, int, String, Charset) to build a record from field values instead");
        Objects.requireNonNull(charset, "A charset must be supplied explicitly: fixed-width "
                + "mainframe data is bytes in a specific code page, never a platform default");
        if (record.length != RECORD_LENGTH) {
            throw new IllegalArgumentException("Supplied " + record.length + " byte(s) for a "
                    + "TRANCATG record, which app/cpy/CVTRA04Y.cpy declares as exactly "
                    + RECORD_LENGTH + " (TRAN-CAT-KEY 6 + TRAN-CAT-TYPE-DESC 50 + FILLER 4). Every "
                    + "row of app/data/ASCII/trancatg.txt is 60 bytes, so widen or reject the row "
                    + "deliberately - with FixedWidthCodec.padToDeclaredWidth - before decoding it");
        }
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return fromArea(codec, codec.wrap(record, LAYOUT));
    }

    /**
     * Builds a {@code TRANCATG} record from field values, applying COBOL {@code MOVE} semantics to each.
     *
     * @param tranTypeCd {@code TRAN-TYPE-CD}, the 2-character type code; {@code "01"} and not 1
     * @param tranCatCd {@code TRAN-CAT-CD}, the category code; must not be negative
     * @param tranCatTypeDesc {@code TRAN-CAT-TYPE-DESC}, up to 50 characters
     * @param charset the code page to encode into, stated explicitly
     * @return the assembled record, exactly {@link #RECORD_LENGTH} bytes wide
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative, or if {@code charset} is not a
     *     single-byte code page for the digits and the space
     */
    public static TranCategoryRecord of(String tranTypeCd,
                                        int tranCatCd,
                                        String tranCatTypeDesc,
                                        Charset charset) {
        Objects.requireNonNull(tranTypeCd, "TRAN-TYPE-CD is required; it is PIC X(02), so pass the "
                + "2-character code such as \"01\", or SPACES as \"  \" - never null");
        Objects.requireNonNull(tranCatTypeDesc, "TRAN-CAT-TYPE-DESC is required; it is PIC X(50), so "
                + "pass an empty string for a blank description rather than null");
        Objects.requireNonNull(charset, "A charset must be supplied explicitly to encode a "
                + "fixed-width record; it is never derived from the platform");
        requireUnsignedCategoryCode(tranCatCd);

        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, TRAN_TYPE_CD, tranTypeCd);
        codec.writePic9(area, TRAN_CAT_CD, tranCatCd);
        codec.writePicX(area, TRAN_CAT_TYPE_DESC, tranCatTypeDesc);
        return fromArea(codec, area);
    }

    private static TranCategoryRecord fromArea(FixedWidthCodec codec, FixedWidthRecord area) {
        return new TranCategoryRecord(
                area.toByteArray(),
                codec.charset(),
                codec.readPicX(area, TRAN_TYPE_CD),
                codec.readPic9AsInt(area, TRAN_CAT_CD),
                codec.readPicX(area, TRAN_CAT_TYPE_DESC),
                codec.readPicX(area, FILLER),
                area.readString(TRAN_CAT_KEY_OFFSET, TRAN_CAT_KEY_LENGTH));
    }

    /**
     * Builds the 6-byte {@code TRAN-CAT-KEY} image for a keyed read, without needing a record.
     *
     * @param tranTypeCd the 2-character transaction type code
     * @param tranCatCd the category code; must not be negative
     * @param charset the code page the key will be encoded in, stated explicitly so the 6 characters are
     *     provably 6 bytes
     * @return the key image, exactly {@link #TRAN_CAT_KEY_LENGTH} characters
     * @throws NullPointerException if {@code tranTypeCd} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative, or if {@code charset} is not a
     *     single-byte code page for the digits and the space
     */
    public static String tranCatKeyImage(String tranTypeCd, int tranCatCd, Charset charset) {
        Objects.requireNonNull(tranTypeCd, "TRAN-TYPE-CD is required to build a TRAN-CAT-KEY; it is "
                + "PIC X(02) and forms the key's first two bytes");
        Objects.requireNonNull(charset, "A charset must be supplied explicitly: a 6-character key "
                + "image is only a 6-byte key under a single-byte code page");
        requireUnsignedCategoryCode(tranCatCd);
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.movePicX(tranTypeCd, TRAN_TYPE_CD_LENGTH)
                + codec.movePic9(tranCatCd, TRAN_CAT_CD_LENGTH);
    }

    /**
     * Builds the 6-byte {@code TRAN-CAT-KEY} as bytes, for a repository issuing a keyed read against the
     * {@code TRANCATG} KSDS.
     *
     * @param tranTypeCd the 2-character transaction type code
     * @param tranCatCd the category code; must not be negative
     * @param charset the code page to encode in, stated explicitly
     * @return the key image encoded, exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
     * @throws NullPointerException if {@code tranTypeCd} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code tranCatCd} is negative, or {@code charset} is not a
     *     single-byte code page for the digits and the space
     */
    public static byte[] tranCatKeyBytes(String tranTypeCd, int tranCatCd, Charset charset) {
        return FixedWidthRecord.encodeText(tranCatKeyImage(tranTypeCd, tranCatCd, charset), charset,
                "a TRAN-CAT-KEY image");
    }

    private static void requireUnsignedCategoryCode(int tranCatCd) {
        if (tranCatCd < 0) {
            throw new IllegalArgumentException("TRAN-CAT-CD is " + tranCatCd + ", but CVTRA04Y "
                    + "declares it PIC 9(04) - an unsigned picture with no sign position, so a "
                    + "negative category code cannot be represented");
        }
    }

    /**
     * {@code TRAN-TYPE-CD} - the 2-character transaction type code, the key's first half.
     *
     * @return exactly {@link #TRAN_TYPE_CD_LENGTH} characters
     */
    public String tranTypeCd() {
        return tranTypeCd;
    }

    /**
     * {@code TRAN-CAT-CD} - the transaction category code, the key's second half, decoded from its 4-digit
     * zoned {@code DISPLAY} image.
     *
     * @return the category code, never negative
     */
    public int tranCatCd() {
        return tranCatCd;
    }

    /**
     * {@code TRAN-CAT-TYPE-DESC} - the category description, untrimmed: all
     * {@link #TRAN_CAT_TYPE_DESC_LENGTH} characters, trailing spaces included.
     *
     * <p>A caller that needs the report form applies the receiver's own width at the point of use, exactly
     * as {@code app/cbl/CBTRN03C.cbl:368} does into a {@code PIC X(29)} field: which keeps the leading 29
     * characters, because COBOL truncates an alphanumeric {@code MOVE} on the right.
     *
     * @return exactly {@link #TRAN_CAT_TYPE_DESC_LENGTH} characters, never trimmed
     */
    public String tranCatTypeDesc() {
        return tranCatTypeDesc;
    }

    /**
     * The trailing {@code FILLER} span's characters, verbatim.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return filler;
    }

    public byte[] fillerBytes() {
        return Arrays.copyOfRange(image, FILLER_OFFSET, FILLER_OFFSET + FILLER_LENGTH);
    }

    /**
     * The 6-byte {@code TRAN-CAT-KEY} image of this record, as the raw six characters.
     *
     * @return exactly {@link #TRAN_CAT_KEY_LENGTH} characters, for example {@code "010001"}
     */
    public String tranCatKeyImage() {
        return tranCatKeyImage;
    }

    /**
     * The 6-byte {@code TRAN-CAT-KEY} of this record, as a copy of its bytes.
     *
     * @return a fresh array of exactly {@link #TRAN_CAT_KEY_LENGTH} bytes
     */
    public byte[] tranCatKeyBytes() {
        return Arrays.copyOfRange(image, TRAN_CAT_KEY_OFFSET,
                TRAN_CAT_KEY_OFFSET + TRAN_CAT_KEY_LENGTH);
    }

    /**
     * The complete serialised record: all {@link #RECORD_LENGTH} bytes, as a copy.
     *
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     */
    public byte[] toByteArray() {
        return image.clone();
    }

    /**
     * The complete record decoded as text: all {@link #RECORD_LENGTH} characters, untrimmed.
     *
     * @return the whole record as characters, exactly {@link #RECORD_LENGTH} of them
     */
    public String toImage() {
        return FixedWidthRecord.decodeText(image, charset, "a TRAN-CAT-RECORD image");
    }

    /**
     * Reads any declared span of this record as text, untrimmed - the per-field raw access the parity
     * differ needs in order to compare field by field rather than as whole strings.
     *
     * @param field the span to read, normally one of {@link #TRAN_TYPE_CD}, {@link #TRAN_CAT_CD},
     *     {@link #TRAN_CAT_TYPE_DESC} or {@link #FILLER}
     * @return the span's characters, exactly {@code field.length()} of them
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside this 60-byte record
     */
    public String fieldImage(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span of a "
                + "TRANCATG record");
        return toRecordArea().readSpan(field);
    }

    /**
     * Reads any declared span of this record as bytes, as a copy.
     *
     * @param field the span to read
     * @return a fresh array of exactly {@code field.length()} bytes
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IndexOutOfBoundsException if the span falls outside this 60-byte record
     */
    public byte[] fieldBytes(FieldSpan field) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span of a "
                + "TRANCATG record");
        return toRecordArea().readSpanBytes(field);
    }

    /**
     * A fresh, independent record area over a copy of this record's bytes - the mutable working storage
     * form, for a caller that needs to address the record by absolute offset.
     *
     * @return a new {@link FixedWidthRecord} of {@link #RECORD_LENGTH} bytes in this record's charset
     */
    public FixedWidthRecord toRecordArea() {
        return FixedWidthRecord.copyOf(image, RECORD_LENGTH, charset);
    }

    public Charset charset() {
        return charset;
    }

    /**
     * Value equality over the record's bytes and its code page.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code TranCategoryRecord} with identical bytes and
     *     charset
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranCategoryRecord that)) {
            return false;
        }
        return charset.equals(that.charset) && Arrays.equals(image, that.image);
    }

    /**
     * A hash consistent with {@link #equals(Object)}, over the record's bytes and its code page.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return 31 * charset.hashCode() + Arrays.hashCode(image);
    }

    /**
     * A diagnostic rendering naming each COBOL item and quoting the two character fields so their padding
     * stays visible - the {@code FILLER} in particular, which is zeros in this dataset and spaces in a
     * built record.
     *
     * @return for example
     *     {@code TranCategoryRecord[TRAN-CAT-KEY='010001', TRAN-TYPE-CD='01', TRAN-CAT-CD=1, TRAN-CAT-TYPE-DESC='Regular Sales Draft', FILLER='0000', charset=US-ASCII]}
     */
    @Override
    public String toString() {
        return "TranCategoryRecord[" + TRAN_CAT_KEY_NAME + "='" + tranCatKeyImage
                + "', TRAN-TYPE-CD='" + tranTypeCd
                + "', TRAN-CAT-CD=" + tranCatCd
                + ", TRAN-CAT-TYPE-DESC='" + tranCatTypeDesc
                + "', FILLER='" + filler
                + "', charset=" + charset.name() + ']';
    }
}
