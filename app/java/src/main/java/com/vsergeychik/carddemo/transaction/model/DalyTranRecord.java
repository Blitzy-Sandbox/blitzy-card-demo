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
 * The daily transaction record, {@code 01 DALYTRAN-RECORD}, transcribed from {@code app/cpy/CVTRA06Y.cpy}
 * whose own header comment declares {@code Data-structure for DALYTRANsaction record (RECLN = 350)}.
 *
 * <p>The receiver is exactly 350 bytes wide, and {@code app/jcl/POSTTRAN.jcl:34-38} declares the dataset it
 * is written to as {@code DCB=(RECFM=F,LRECL=430)} - 350 + 80.
 */
public final class DalyTranRecord {
    /**
     * The declared record width in bytes, from the copybook's {@code RECLN = 350} header comment.
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * {@code DALYTRAN-ID PIC X(16)} - 1-based 1-16, 0-based offset 0.
     */
    public static final int DALYTRAN_ID_OFFSET = 0;
    /**
     * Width of {@code DALYTRAN-ID PIC X(16)}.
     */
    public static final int DALYTRAN_ID_LENGTH = 16;

    /**
     * {@code DALYTRAN-TYPE-CD PIC X(02)} - 1-based 17-18, 0-based offset 16.
     */
    public static final int DALYTRAN_TYPE_CD_OFFSET = 16;
    /**
     * Width of {@code DALYTRAN-TYPE-CD PIC X(02)}.
     */
    public static final int DALYTRAN_TYPE_CD_LENGTH = 2;

    /**
     * {@code DALYTRAN-CAT-CD PIC 9(04)} - 1-based 19-22, 0-based offset 18.
     */
    public static final int DALYTRAN_CAT_CD_OFFSET = 18;
    /**
     * Width of {@code DALYTRAN-CAT-CD PIC 9(04)}.
     */
    public static final int DALYTRAN_CAT_CD_LENGTH = 4;

    /**
     * {@code DALYTRAN-SOURCE PIC X(10)} - 1-based 23-32, 0-based offset 22.
     */
    public static final int DALYTRAN_SOURCE_OFFSET = 22;
    /**
     * Width of {@code DALYTRAN-SOURCE PIC X(10)}.
     */
    public static final int DALYTRAN_SOURCE_LENGTH = 10;

    /**
     * {@code DALYTRAN-DESC PIC X(100)} - 1-based 33-132, 0-based offset 32.
     */
    public static final int DALYTRAN_DESC_OFFSET = 32;
    /**
     * Width of {@code DALYTRAN-DESC PIC X(100)}.
     */
    public static final int DALYTRAN_DESC_LENGTH = 100;

    /**
     * {@code DALYTRAN-AMT PIC S9(09)V99} - 1-based 133-143, 0-based offset 132.
     */
    public static final int DALYTRAN_AMT_OFFSET = 132;
    /**
     * Width of {@code DALYTRAN-AMT PIC S9(09)V99}: nine integer digits plus two fraction digits, with the
     * sign overpunched into the trailing byte rather than occupying a byte of its own.
     */
    public static final int DALYTRAN_AMT_LENGTH = 11;
    /**
     * {@code p} of {@code DALYTRAN-AMT PIC S9(09)V99} - the digits before the implied point.
     */
    public static final int DALYTRAN_AMT_INTEGER_DIGITS = 9;
    /**
     * {@code s} of {@code DALYTRAN-AMT PIC S9(09)V99} - the digits after the implied decimal point.
     */
    public static final int DALYTRAN_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)} - 1-based 144-152, 0-based offset 143.
     */
    public static final int DALYTRAN_MERCHANT_ID_OFFSET = 143;
    /**
     * Width of {@code DALYTRAN-MERCHANT-ID PIC 9(09)}.
     */
    public static final int DALYTRAN_MERCHANT_ID_LENGTH = 9;

    /**
     * {@code DALYTRAN-MERCHANT-NAME PIC X(50)} - 1-based 153-202, 0-based offset 152.
     */
    public static final int DALYTRAN_MERCHANT_NAME_OFFSET = 152;
    /**
     * Width of {@code DALYTRAN-MERCHANT-NAME PIC X(50)}.
     */
    public static final int DALYTRAN_MERCHANT_NAME_LENGTH = 50;

    /**
     * {@code DALYTRAN-MERCHANT-CITY PIC X(50)} - 1-based 203-252, 0-based offset 202.
     */
    public static final int DALYTRAN_MERCHANT_CITY_OFFSET = 202;
    /**
     * Width of {@code DALYTRAN-MERCHANT-CITY PIC X(50)}.
     */
    public static final int DALYTRAN_MERCHANT_CITY_LENGTH = 50;

    /**
     * {@code DALYTRAN-MERCHANT-ZIP PIC X(10)} - 1-based 253-262, 0-based offset 252.
     */
    public static final int DALYTRAN_MERCHANT_ZIP_OFFSET = 252;
    /**
     * Width of {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}.
     */
    public static final int DALYTRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * {@code DALYTRAN-CARD-NUM PIC X(16)} - 1-based 263-278, 0-based offset 262.
     */
    public static final int DALYTRAN_CARD_NUM_OFFSET = 262;
    /**
     * Width of {@code DALYTRAN-CARD-NUM PIC X(16)}.
     */
    public static final int DALYTRAN_CARD_NUM_LENGTH = 16;

    /**
     * {@code DALYTRAN-ORIG-TS PIC X(26)} - 1-based 279-304, 0-based offset 278.
     */
    public static final int DALYTRAN_ORIG_TS_OFFSET = 278;
    /**
     * Width of {@code DALYTRAN-ORIG-TS PIC X(26)}.
     */
    public static final int DALYTRAN_ORIG_TS_LENGTH = 26;

    /**
     * The reference-modified slice {@code DALYTRAN-ORIG-TS (1:10)} - the first ten characters of the
     * originating timestamp, which are its date - so the same 0-based offset as the field itself.
     */
    public static final int DALYTRAN_ORIG_DT_OFFSET = 278;
    /**
     * Width of the {@code DALYTRAN-ORIG-TS (1:10)} slice, ten characters of {@code yyyy-MM-dd}.
     */
    public static final int DALYTRAN_ORIG_DT_LENGTH = 10;

    /**
     * {@code DALYTRAN-PROC-TS PIC X(26)} - 1-based 305-330, 0-based offset 304.
     */
    public static final int DALYTRAN_PROC_TS_OFFSET = 304;
    /**
     * Width of {@code DALYTRAN-PROC-TS PIC X(26)}.
     */
    public static final int DALYTRAN_PROC_TS_LENGTH = 26;

    /**
     * {@code FILLER PIC X(20)} - 1-based 331-350, 0-based offset 330.
     */
    public static final int FILLER_OFFSET = 330;
    /**
     * Width of the trailing {@code FILLER PIC X(20)}.
     */
    public static final int FILLER_LENGTH = 20;

    // Each is an immutable FieldSpan carrying the copybook name verbatim - hyphens and the DALYTRAN- prefix
    // included, and the trailing span named plain FILLER exactly as declared - together with its offset,
    // width and PICTURE category, so a caller never has to pair an offset with a length by hand.

    /**
     * Descriptor for {@code DALYTRAN-ID PIC X(16)}.
     */
    public static final FieldSpan DALYTRAN_ID =
            FieldSpan.alphanumeric("DALYTRAN-ID", DALYTRAN_ID_OFFSET, DALYTRAN_ID_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-TYPE-CD PIC X(02)}.
     */
    public static final FieldSpan DALYTRAN_TYPE_CD = FieldSpan.alphanumeric(
            "DALYTRAN-TYPE-CD", DALYTRAN_TYPE_CD_OFFSET, DALYTRAN_TYPE_CD_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-CAT-CD PIC 9(04)}, unsigned numeric {@code DISPLAY}.
     */
    public static final FieldSpan DALYTRAN_CAT_CD = FieldSpan.unsignedNumeric(
            "DALYTRAN-CAT-CD", DALYTRAN_CAT_CD_OFFSET, DALYTRAN_CAT_CD_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-SOURCE PIC X(10)}.
     */
    public static final FieldSpan DALYTRAN_SOURCE = FieldSpan.alphanumeric(
            "DALYTRAN-SOURCE", DALYTRAN_SOURCE_OFFSET, DALYTRAN_SOURCE_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-DESC PIC X(100)}.
     */
    public static final FieldSpan DALYTRAN_DESC = FieldSpan.alphanumeric(
            "DALYTRAN-DESC", DALYTRAN_DESC_OFFSET, DALYTRAN_DESC_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-AMT PIC S9(09)V99}, signed zoned {@code DISPLAY} of
     * {@code DALYTRAN_AMT_INTEGER_DIGITS + DALYTRAN_AMT_SCALE} = 11 bytes.
     */
    public static final FieldSpan DALYTRAN_AMT = FieldSpan.signedScaled(
            "DALYTRAN-AMT", DALYTRAN_AMT_OFFSET, DALYTRAN_AMT_INTEGER_DIGITS, DALYTRAN_AMT_SCALE);

    /**
     * Descriptor for {@code DALYTRAN-MERCHANT-ID PIC 9(09)}, unsigned numeric {@code DISPLAY}.
     */
    public static final FieldSpan DALYTRAN_MERCHANT_ID = FieldSpan.unsignedNumeric(
            "DALYTRAN-MERCHANT-ID", DALYTRAN_MERCHANT_ID_OFFSET, DALYTRAN_MERCHANT_ID_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-MERCHANT-NAME PIC X(50)}.
     */
    public static final FieldSpan DALYTRAN_MERCHANT_NAME = FieldSpan.alphanumeric(
            "DALYTRAN-MERCHANT-NAME", DALYTRAN_MERCHANT_NAME_OFFSET, DALYTRAN_MERCHANT_NAME_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-MERCHANT-CITY PIC X(50)}.
     */
    public static final FieldSpan DALYTRAN_MERCHANT_CITY = FieldSpan.alphanumeric(
            "DALYTRAN-MERCHANT-CITY", DALYTRAN_MERCHANT_CITY_OFFSET, DALYTRAN_MERCHANT_CITY_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-MERCHANT-ZIP PIC X(10)}.
     */
    public static final FieldSpan DALYTRAN_MERCHANT_ZIP = FieldSpan.alphanumeric(
            "DALYTRAN-MERCHANT-ZIP", DALYTRAN_MERCHANT_ZIP_OFFSET, DALYTRAN_MERCHANT_ZIP_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-CARD-NUM PIC X(16)}.
     */
    public static final FieldSpan DALYTRAN_CARD_NUM = FieldSpan.alphanumeric(
            "DALYTRAN-CARD-NUM", DALYTRAN_CARD_NUM_OFFSET, DALYTRAN_CARD_NUM_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-ORIG-TS PIC X(26)}.
     */
    public static final FieldSpan DALYTRAN_ORIG_TS = FieldSpan.alphanumeric(
            "DALYTRAN-ORIG-TS", DALYTRAN_ORIG_TS_OFFSET, DALYTRAN_ORIG_TS_LENGTH);

    /**
     * Descriptor for {@code DALYTRAN-PROC-TS PIC X(26)}.
     */
    public static final FieldSpan DALYTRAN_PROC_TS = FieldSpan.alphanumeric(
            "DALYTRAN-PROC-TS", DALYTRAN_PROC_TS_OFFSET, DALYTRAN_PROC_TS_LENGTH);

    /**
     * Descriptor for the trailing {@code FILLER PIC X(20)}.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete record layout, in copybook declaration order.
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

    private final FixedWidthRecord area;

    private final FixedWidthCodec codec;

    /**
     * Allocates an initialised record area, the equivalent of declaring {@code 01 DALYTRAN-RECORD} in
     * {@code WORKING-STORAGE} and running {@code INITIALIZE} over it.
     *
     * @param charset the code page of the record's data, supplied explicitly
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the characters
     *     a zoned fixed-width record requires
     */
    public DalyTranRecord(Charset charset) {
        this.codec = new FixedWidthCodec(requireCharset(charset));
        this.area = codec.newRecord(LAYOUT);
    }

    private DalyTranRecord(FixedWidthCodec codec, FixedWidthRecord area) {
        this.codec = codec;
        this.area = area;
    }

    /**
     * Reads a stored record, keeping its bytes verbatim.
     *
     * @param image exactly {@link #RECORD_LENGTH} bytes as held on the {@code DALYTRAN} dataset
     * @param charset the code page the bytes are encoded in, supplied explicitly
     * @return a record over a copy of {@code image}
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH} bytes
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
     * whose 300 rows are each exactly 350 characters.
     *
     * @param image the record's text image, exactly {@link #RECORD_LENGTH} characters under {@code charset}
     * @param charset the code page to encode {@code image} with, supplied explicitly
     * @return a record over the encoded bytes of {@code image}
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} does not encode to exactly {@link #RECORD_LENGTH}
     *     bytes
     */
    public static DalyTranRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A record image is required to decode a DALYTRAN-RECORD");
        return decode(FixedWidthRecord.encodeText(image, requireCharset(charset),
                "a DALYTRAN-RECORD image"), charset);
    }

    /**
     * Duplicates this record by copying its backing bytes.
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
     * @return the sum of the fourteen declared span lengths, which is {@link #RECORD_LENGTH}
     */
    public static int sumOfDeclaredSpanLengths() {
        int total = 0;
        for (FieldSpan span : LAYOUT.storageSpans()) {
            total += span.length();
        }
        return total;
    }

    /**
     * The complete 350-byte record image, verbatim.
     *
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes; mutating it does not affect this
     *     record
     */
    public byte[] rawImage() {
        return area.toByteArray();
    }

    /**
     * The complete 350-character record image as text.
     *
     * @return exactly {@link #RECORD_LENGTH} characters
     */
    public String displayImage() {
        return area.readString(0, RECORD_LENGTH);
    }

    /**
     * Serialises the record to bytes under an explicitly named charset.
     *
     * @param charset the target code page, supplied explicitly; it must encode every character currently
     *     held in the record to a single byte
     * @return exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the characters
     *     this record holds
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
     * @param field one of this class's declared span constants
     * @return the span's stored characters, exactly {@code field.length()} of them and never trimmed
     * @throws NullPointerException if {@code field} is {@code null}
     * @throws IllegalArgumentException if {@code field} is not a span declared by {@link #LAYOUT}; a
     *     descriptor borrowed from another copybook would read the wrong bytes and is rejected rather than
     *     silently honoured
     */
    public String rawSpan(FieldSpan field) {
        return area.readSpan(requireDeclaredSpan(field));
    }

    public byte[] rawSpanBytes(FieldSpan field) {
        return area.readSpanBytes(requireDeclaredSpan(field));
    }

    /**
     * {@code DALYTRAN-ID PIC X(16)} - at 0-based offset 0.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:425} moves it into {@code TRAN-ID}, the {@code TRANSACT} KSDS key, and
     * {@code app/cbl/CBTRN01C.cbl:183} displays it when a card cannot be verified.
     *
     * @return exactly {@link #DALYTRAN_ID_LENGTH} characters, untrimmed
     */
    public String dalytranId() {
        return codec.readPicX(area, DALYTRAN_ID);
    }

    /**
     * {@code DALYTRAN-TYPE-CD PIC X(02)} - at 0-based offset 16.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:470} moves it into {@code FD-TRANCAT-TYPE-CD} and {@code :506} into
     * {@code TRANCAT-TYPE-CD}, both {@code PIC X(02)} members of the transaction-category balance key, so
     * the types on both sides must agree exactly for that key to be built byte-identically.
     *
     * @return exactly {@link #DALYTRAN_TYPE_CD_LENGTH} characters, untrimmed
     */
    public String dalytranTypeCd() {
        return codec.readPicX(area, DALYTRAN_TYPE_CD);
    }

    /**
     * {@code DALYTRAN-CAT-CD PIC 9(04)} - at 0-based offset 18.
     *
     * <p>An {@code int} because the {@code PICTURE} is scale-free {@code 9}, so no decimal alignment and no
     * rounding policy applies; four digits cannot overflow.
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
     * {@code DALYTRAN-AMT PIC S9(09)V99} - at 0-based offset 132, decoded to a {@code BigDecimal} of scale
     * exactly {@link #DALYTRAN_AMT_SCALE}.
     *
     * <p>The scale is fixed by the {@code PICTURE} and the rounding policy is truncation, both centralised
     * in {@link CobolDecimal}.
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
     * @return exactly {@link #DALYTRAN_AMT_LENGTH} characters
     */
    public String dalytranAmtImage() {
        return area.readSpan(DALYTRAN_AMT);
    }

    /**
     * Whether {@code DALYTRAN-AMT} is numerically zero, whichever sign it carries.
     *
     * @return {@code true} when the decoded amount compares equal to zero
     */
    public boolean hasZeroDalytranAmt() {
        return dalytranAmt().compareTo(CobolDecimal.monetaryZero()) == 0;
    }

    /**
     * {@code DALYTRAN-MERCHANT-ID PIC 9(09)} - at 0-based offset 143.
     *
     * @return the stored value, 0 to 999999999
     * @throws IllegalArgumentException if the span does not hold nine digits, or denotes a value outside
     *     the {@code int} range - which nine digits cannot
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
     * @return exactly {@link #DALYTRAN_CARD_NUM_LENGTH} characters, untrimmed and unmasked
     */
    public String dalytranCardNum() {
        return codec.readPicX(area, DALYTRAN_CARD_NUM);
    }

    /**
     * {@code DALYTRAN-ORIG-TS PIC X(26)} - at 0-based offset 278.
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
     * @return exactly {@link #DALYTRAN_ORIG_DT_LENGTH} characters, untrimmed
     */
    public String dalytranOrigDt() {
        return area.readString(DALYTRAN_ORIG_DT_OFFSET, DALYTRAN_ORIG_DT_LENGTH);
    }

    /**
     * {@code DALYTRAN-PROC-TS PIC X(26)} - at 0-based offset 304.
     *
     * <p>Posting is what fills the equivalent field on the transaction master -
     * {@code app/cbl/CBTRN02C.cbl:437-438} performs {@code Z-GET-DB2-FORMAT-TIMESTAMP} and moves the result
     * into {@code TRAN-PROC-TS}, deliberately not back into this record.
     *
     * @return exactly {@link #DALYTRAN_PROC_TS_LENGTH} characters, untrimmed
     */
    public String dalytranProcTs() {
        return codec.readPicX(area, DALYTRAN_PROC_TS);
    }

    /**
     * The trailing {@code FILLER PIC X(20)} - at 0-based offset 330.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return area.readSpan(FILLER);
    }

    // The direction of truncation is not a detail: COBOL fills a PIC X receiver from its leftmost position
    // and discards what does not fit on the RIGHT, but aligns a PIC 9 receiver on the implied decimal point
    // and discards high-order digits on the LEFT.

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
     * {@code MOVE ... TO DALYTRAN-TYPE-CD} - pads or right-truncates to {@link #DALYTRAN_TYPE_CD_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranTypeCd(String value) {
        codec.writePicX(area, DALYTRAN_TYPE_CD, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-SOURCE} - pads or right-truncates to {@link #DALYTRAN_SOURCE_LENGTH}.
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
     * {@code MOVE ... TO DALYTRAN-CARD-NUM} - pads or right-truncates to {@link #DALYTRAN_CARD_NUM_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranCardNum(String value) {
        codec.writePicX(area, DALYTRAN_CARD_NUM, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-ORIG-TS} - pads or right-truncates to {@link #DALYTRAN_ORIG_TS_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveDalytranOrigTs(String value) {
        codec.writePicX(area, DALYTRAN_ORIG_TS, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-PROC-TS} - pads or right-truncates to {@link #DALYTRAN_PROC_TS_LENGTH}.
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
     *     {@code PIC 9(04)} and has nowhere to record a sign
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveDalytranCatCd(int value) {
        codec.writePic9(area, DALYTRAN_CAT_CD, value);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-CAT-CD} from an alphanumeric sender.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException if {@code digits} is {@code null}
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
     * @throws NullPointerException if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveDalytranMerchantId(String digits) {
        codec.writePic9(area, DALYTRAN_MERCHANT_ID, digits);
    }

    /**
     * {@code MOVE ... TO DALYTRAN-AMT} - stores the amount at scale exactly {@link #DALYTRAN_AMT_SCALE}
     * with a zoned sign overpunch in the trailing byte.
     *
     * @param value the amount to store; may be negative, and its scale may exceed the field's
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value has more integer digits than
     *     {@link #DALYTRAN_AMT_INTEGER_DIGITS}
     */
    public void moveDalytranAmt(BigDecimal value) {
        Objects.requireNonNull(value, "An amount is required to store into DALYTRAN-AMT; use "
                + "moveDalytranAmt(CobolDecimal.monetaryZero()) to store a positive zero");
        codec.writeMonetary(area, DALYTRAN_AMT, CobolDecimal.storeMonetary(value));
    }

    /**
     * Stores a pre-built {@code DALYTRAN-AMT} image verbatim, sign overpunch and all.
     *
     * @param image exactly {@link #DALYTRAN_AMT_LENGTH} characters - ten leading digits followed by a digit
     *     or a zoned overpunch character
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #DALYTRAN_AMT_LENGTH}
     *     characters
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

    /**
     * Two records are equal when they hold identical bytes under the same code page.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code DalyTranRecord} with the same charset and the
     *     same 350 bytes
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
     * A diagnostic rendering that describes the transaction without disclosing the activity.
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
                + ", FILLER.length=" + filler().length()
                + ']';
    }

    private static Charset requireCharset(Charset charset) {
        return Objects.requireNonNull(charset, "A charset must be supplied explicitly for a "
                + "DALYTRAN-RECORD: US-ASCII for the app/data/ASCII fixtures, IBM037 for EBCDIC "
                + "datasets. The platform default is never used, because the sign overpunch bytes "
                + "differ between code pages");
    }

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
