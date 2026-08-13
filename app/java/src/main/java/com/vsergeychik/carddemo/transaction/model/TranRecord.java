package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Objects;

/**
 * The transaction record, {@code 01 TRAN-RECORD}, transcribed from {@code app/cpy/CVTRA05Y.cpy} whose own
 * header comment declares {@code RECLN = 350}.
 *
 * <p>The first pins {@code TRAN-CARD-NUM} to 1-based 263, exactly as tabulated.
 */
public final class TranRecord {
    /**
     * The declared record width in bytes, from the copybook's {@code RECLN = 350} header comment and
     * corroborated by {@code LRECL=350} on every dataset that holds this record
     * ({@code app/jcl/INTCALC.jcl} {@code RECFM=F}, {@code app/jcl/TRANREPT.jcl} {@code RECFM=FB}).
     */
    public static final int RECORD_LENGTH = 350;

    /**
     * The length of the {@code TRANSACT} KSDS primary key, which is {@code TRAN-ID} in full.
     */
    public static final int TRAN_ID_KEY_LENGTH = 16;

    /**
     * {@code TRAN-ID PIC X(16)} - 1-based 1-16, 0-based offset 0.
     */
    public static final int TRAN_ID_OFFSET = 0;
    /**
     * Width of {@code TRAN-ID PIC X(16)}.
     */
    public static final int TRAN_ID_LENGTH = 16;

    /**
     * {@code TRAN-TYPE-CD PIC X(02)} - 1-based 17-18, 0-based offset 16.
     */
    public static final int TRAN_TYPE_CD_OFFSET = 16;
    /**
     * Width of {@code TRAN-TYPE-CD PIC X(02)}.
     */
    public static final int TRAN_TYPE_CD_LENGTH = 2;

    /**
     * {@code TRAN-CAT-CD PIC 9(04)} - 1-based 19-22, 0-based offset 18.
     */
    public static final int TRAN_CAT_CD_OFFSET = 18;
    /**
     * Width of {@code TRAN-CAT-CD PIC 9(04)}.
     */
    public static final int TRAN_CAT_CD_LENGTH = 4;

    /**
     * {@code TRAN-SOURCE PIC X(10)} - 1-based 23-32, 0-based offset 22.
     */
    public static final int TRAN_SOURCE_OFFSET = 22;
    /**
     * Width of {@code TRAN-SOURCE PIC X(10)}.
     */
    public static final int TRAN_SOURCE_LENGTH = 10;

    /**
     * {@code TRAN-DESC PIC X(100)} - 1-based 33-132, 0-based offset 32.
     */
    public static final int TRAN_DESC_OFFSET = 32;
    /**
     * Width of {@code TRAN-DESC PIC X(100)}.
     */
    public static final int TRAN_DESC_LENGTH = 100;

    /**
     * {@code TRAN-AMT PIC S9(09)V99} - 1-based 133-143, 0-based offset 132.
     */
    public static final int TRAN_AMT_OFFSET = 132;
    /**
     * Width of {@code TRAN-AMT PIC S9(09)V99}: nine integer digits plus two fraction digits, with the sign
     * overpunched into the trailing byte rather than occupying a byte of its own.
     */
    public static final int TRAN_AMT_LENGTH = 11;
    /**
     * {@code p} of {@code TRAN-AMT PIC S9(09)V99} - the digits before the implied decimal point.
     */
    public static final int TRAN_AMT_INTEGER_DIGITS = 9;
    /**
     * {@code s} of {@code TRAN-AMT PIC S9(09)V99} - the digits after the implied decimal point.
     */
    public static final int TRAN_AMT_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * {@code TRAN-MERCHANT-ID PIC 9(09)} - 1-based 144-152, 0-based offset 143.
     */
    public static final int TRAN_MERCHANT_ID_OFFSET = 143;
    /**
     * Width of {@code TRAN-MERCHANT-ID PIC 9(09)}.
     */
    public static final int TRAN_MERCHANT_ID_LENGTH = 9;

    /**
     * {@code TRAN-MERCHANT-NAME PIC X(50)} - 1-based 153-202, 0-based offset 152.
     */
    public static final int TRAN_MERCHANT_NAME_OFFSET = 152;
    /**
     * Width of {@code TRAN-MERCHANT-NAME PIC X(50)}.
     */
    public static final int TRAN_MERCHANT_NAME_LENGTH = 50;

    /**
     * {@code TRAN-MERCHANT-CITY PIC X(50)} - 1-based 203-252, 0-based offset 202.
     */
    public static final int TRAN_MERCHANT_CITY_OFFSET = 202;
    /**
     * Width of {@code TRAN-MERCHANT-CITY PIC X(50)}.
     */
    public static final int TRAN_MERCHANT_CITY_LENGTH = 50;

    /**
     * {@code TRAN-MERCHANT-ZIP PIC X(10)} - 1-based 253-262, 0-based offset 252.
     */
    public static final int TRAN_MERCHANT_ZIP_OFFSET = 252;
    /**
     * Width of {@code TRAN-MERCHANT-ZIP PIC X(10)}.
     */
    public static final int TRAN_MERCHANT_ZIP_LENGTH = 10;

    /**
     * {@code TRAN-CARD-NUM PIC X(16)} - 1-based 263-278, 0-based offset 262.
     */
    public static final int TRAN_CARD_NUM_OFFSET = 262;
    /**
     * Width of {@code TRAN-CARD-NUM PIC X(16)}.
     */
    public static final int TRAN_CARD_NUM_LENGTH = 16;

    /**
     * {@code TRAN-ORIG-TS PIC X(26)} - 1-based 279-304, 0-based offset 278.
     */
    public static final int TRAN_ORIG_TS_OFFSET = 278;
    /**
     * Width of {@code TRAN-ORIG-TS PIC X(26)}.
     */
    public static final int TRAN_ORIG_TS_LENGTH = 26;

    /**
     * {@code TRAN-PROC-TS PIC X(26)} - 1-based 305-330, 0-based offset 304.
     */
    public static final int TRAN_PROC_TS_OFFSET = 304;
    /**
     * Width of {@code TRAN-PROC-TS PIC X(26)}.
     */
    public static final int TRAN_PROC_TS_LENGTH = 26;

    /**
     * The SORT key {@code TRAN-PROC-DT} - the first ten bytes of {@code TRAN-PROC-TS}, so the same 0-based
     * offset.
     */
    public static final int TRAN_PROC_DT_OFFSET = 304;
    /**
     * Width of the {@code TRAN-PROC-DT} SORT key, ten characters of {@code yyyy-MM-dd}.
     */
    public static final int TRAN_PROC_DT_LENGTH = 10;

    /**
     * {@code FILLER PIC X(20)} - 1-based 331-350, 0-based offset 330.
     */
    public static final int FILLER_OFFSET = 330;
    /**
     * Width of the trailing {@code FILLER PIC X(20)}.
     */
    public static final int FILLER_LENGTH = 20;

    // Each is an immutable FieldSpan carrying the copybook name verbatim - hyphens included - together with
    // its offset, width and PICTURE category, so a caller never has to pair an offset with a length by
    // hand.

    /**
     * Descriptor for {@code TRAN-ID PIC X(16)}, the {@code TRANSACT} KSDS key.
     */
    public static final FieldSpan TRAN_ID =
            FieldSpan.alphanumeric("TRAN-ID", TRAN_ID_OFFSET, TRAN_ID_LENGTH);

    /**
     * Descriptor for {@code TRAN-TYPE-CD PIC X(02)}.
     */
    public static final FieldSpan TRAN_TYPE_CD =
            FieldSpan.alphanumeric("TRAN-TYPE-CD", TRAN_TYPE_CD_OFFSET, TRAN_TYPE_CD_LENGTH);

    /**
     * Descriptor for {@code TRAN-CAT-CD PIC 9(04)}, unsigned numeric {@code DISPLAY}.
     */
    public static final FieldSpan TRAN_CAT_CD =
            FieldSpan.unsignedNumeric("TRAN-CAT-CD", TRAN_CAT_CD_OFFSET, TRAN_CAT_CD_LENGTH);

    /**
     * Descriptor for {@code TRAN-SOURCE PIC X(10)}.
     */
    public static final FieldSpan TRAN_SOURCE =
            FieldSpan.alphanumeric("TRAN-SOURCE", TRAN_SOURCE_OFFSET, TRAN_SOURCE_LENGTH);

    /**
     * Descriptor for {@code TRAN-DESC PIC X(100)}.
     */
    public static final FieldSpan TRAN_DESC =
            FieldSpan.alphanumeric("TRAN-DESC", TRAN_DESC_OFFSET, TRAN_DESC_LENGTH);

    /**
     * Descriptor for {@code TRAN-AMT PIC S9(09)V99}, signed zoned {@code DISPLAY} of
     * {@code TRAN_AMT_INTEGER_DIGITS + TRAN_AMT_SCALE} = 11 bytes.
     */
    public static final FieldSpan TRAN_AMT = FieldSpan.signedScaled(
            "TRAN-AMT", TRAN_AMT_OFFSET, TRAN_AMT_INTEGER_DIGITS, TRAN_AMT_SCALE);

    /**
     * Descriptor for {@code TRAN-MERCHANT-ID PIC 9(09)}, unsigned numeric {@code DISPLAY}.
     */
    public static final FieldSpan TRAN_MERCHANT_ID = FieldSpan.unsignedNumeric(
            "TRAN-MERCHANT-ID", TRAN_MERCHANT_ID_OFFSET, TRAN_MERCHANT_ID_LENGTH);

    /**
     * Descriptor for {@code TRAN-MERCHANT-NAME PIC X(50)}.
     */
    public static final FieldSpan TRAN_MERCHANT_NAME = FieldSpan.alphanumeric(
            "TRAN-MERCHANT-NAME", TRAN_MERCHANT_NAME_OFFSET, TRAN_MERCHANT_NAME_LENGTH);

    /**
     * Descriptor for {@code TRAN-MERCHANT-CITY PIC X(50)}.
     */
    public static final FieldSpan TRAN_MERCHANT_CITY = FieldSpan.alphanumeric(
            "TRAN-MERCHANT-CITY", TRAN_MERCHANT_CITY_OFFSET, TRAN_MERCHANT_CITY_LENGTH);

    /**
     * Descriptor for {@code TRAN-MERCHANT-ZIP PIC X(10)}.
     */
    public static final FieldSpan TRAN_MERCHANT_ZIP = FieldSpan.alphanumeric(
            "TRAN-MERCHANT-ZIP", TRAN_MERCHANT_ZIP_OFFSET, TRAN_MERCHANT_ZIP_LENGTH);

    /**
     * Descriptor for {@code TRAN-CARD-NUM PIC X(16)}.
     */
    public static final FieldSpan TRAN_CARD_NUM = FieldSpan.alphanumeric(
            "TRAN-CARD-NUM", TRAN_CARD_NUM_OFFSET, TRAN_CARD_NUM_LENGTH);

    /**
     * Descriptor for {@code TRAN-ORIG-TS PIC X(26)}.
     */
    public static final FieldSpan TRAN_ORIG_TS = FieldSpan.alphanumeric(
            "TRAN-ORIG-TS", TRAN_ORIG_TS_OFFSET, TRAN_ORIG_TS_LENGTH);

    /**
     * Descriptor for {@code TRAN-PROC-TS PIC X(26)}.
     */
    public static final FieldSpan TRAN_PROC_TS = FieldSpan.alphanumeric(
            "TRAN-PROC-TS", TRAN_PROC_TS_OFFSET, TRAN_PROC_TS_LENGTH);

    /**
     * Descriptor for the trailing {@code FILLER PIC X(20)}.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete record layout, in copybook declaration order.
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

    private final FixedWidthRecord area;

    private final FixedWidthCodec codec;

    /**
     * Allocates an initialised record area, the equivalent of declaring {@code 01 TRAN-RECORD} in
     * {@code WORKING-STORAGE} and running {@code INITIALIZE} over it.
     *
     * @param charset the code page of the record's data, supplied explicitly
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the characters
     *     a zoned fixed-width record requires
     */
    public TranRecord(Charset charset) {
        this.codec = new FixedWidthCodec(requireCharset(charset));
        this.area = codec.newRecord(LAYOUT);
    }

    private TranRecord(FixedWidthCodec codec, FixedWidthRecord area) {
        this.codec = codec;
        this.area = area;
    }

    /**
     * Reads a stored record, keeping its bytes verbatim.
     *
     * @param image exactly {@link #RECORD_LENGTH} bytes as held on the dataset
     * @param charset the code page the bytes are encoded in, supplied explicitly
     * @return a record over a copy of {@code image}
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #RECORD_LENGTH} bytes
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
     * {@code app/data/ASCII}, whose rows are exactly 350 characters.
     *
     * @param image the record's text image, exactly {@link #RECORD_LENGTH} characters under {@code charset}
     * @param charset the code page to encode {@code image} with, supplied explicitly
     * @return a record over the encoded bytes of {@code image}
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} does not encode to exactly {@link #RECORD_LENGTH}
     *     bytes
     */
    public static TranRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A record image is required to decode a TRAN-RECORD");
        return decode(FixedWidthRecord.encodeText(image, requireCharset(charset),
                "a TRAN-RECORD image"), charset);
    }

    /**
     * Duplicates this record by copying its backing bytes.
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
     * {@code TRAN-ID PIC X(16)} - the {@code TRANSACT} KSDS key, at 0-based offset 0.
     *
     * <p>{@code app/cbl/CBACT04C.cbl:476-480} builds it by stringing a 10-character parameter date onto a
     * 6-digit suffix, which fills the field exactly.
     *
     * @return exactly {@link #TRAN_ID_LENGTH} characters, untrimmed
     */
    public String tranId() {
        return codec.readPicX(area, TRAN_ID);
    }

    /**
     * {@code TRAN-TYPE-CD PIC X(02)} - at 0-based offset 16.
     *
     * @return exactly {@link #TRAN_TYPE_CD_LENGTH} characters, untrimmed
     */
    public String tranTypeCd() {
        return codec.readPicX(area, TRAN_TYPE_CD);
    }

    /**
     * {@code TRAN-CAT-CD PIC 9(04)} - at 0-based offset 18.
     *
     * <p>An {@code int} because the {@code PICTURE} is scale-free {@code 9}, so no decimal alignment and no
     * rounding policy applies; four digits cannot overflow.
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
     * <p>The scale is fixed by the {@code PICTURE} and the rounding policy is truncation, both centralised
     * in {@link CobolDecimal}.
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
     * @return exactly {@link #TRAN_AMT_LENGTH} characters
     */
    public String tranAmtImage() {
        return area.readSpan(TRAN_AMT);
    }

    /**
     * Whether {@code TRAN-AMT} is numerically zero, whichever sign it carries.
     *
     * @return {@code true} when the decoded amount compares equal to zero
     */
    public boolean hasZeroTranAmt() {
        return tranAmt().compareTo(CobolDecimal.monetaryZero()) == 0;
    }

    /**
     * {@code TRAN-MERCHANT-ID PIC 9(09)} - at 0-based offset 143.
     *
     * @return the stored value, 0 to 999999999
     * @throws IllegalArgumentException if the span does not hold nine digits, or denotes a value outside
     *     the {@code int} range - which nine digits cannot
     */
    public int tranMerchantId() {
        return codec.readPic9AsInt(area, TRAN_MERCHANT_ID);
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
     * @return exactly {@link #TRAN_CARD_NUM_LENGTH} characters, untrimmed and unmasked
     */
    public String tranCardNum() {
        return codec.readPicX(area, TRAN_CARD_NUM);
    }

    /**
     * {@code TRAN-ORIG-TS PIC X(26)} - at 0-based offset 278.
     *
     * @return exactly {@link #TRAN_ORIG_TS_LENGTH} characters, untrimmed
     */
    public String tranOrigTs() {
        return codec.readPicX(area, TRAN_ORIG_TS);
    }

    /**
     * {@code TRAN-PROC-TS PIC X(26)} - at 0-based offset 304.
     *
     * @return exactly {@link #TRAN_PROC_TS_LENGTH} characters, untrimmed
     */
    public String tranProcTs() {
        return codec.readPicX(area, TRAN_PROC_TS);
    }

    /**
     * {@code TRAN-PROC-DT} - the first ten characters of {@code TRAN-PROC-TS}, the report's date key.
     *
     * @return exactly {@link #TRAN_PROC_DT_LENGTH} characters, untrimmed
     */
    public String tranProcDt() {
        return area.readString(TRAN_PROC_DT_OFFSET, TRAN_PROC_DT_LENGTH);
    }

    /**
     * The trailing {@code FILLER PIC X(20)} - at 0-based offset 330.
     *
     * @return exactly {@link #FILLER_LENGTH} characters
     */
    public String filler() {
        return area.readSpan(FILLER);
    }

    /**
     * {@code INITIALIZE TRAN-RECORD} - the statement, with its {@code FILLER} rule intact.
     */
    public void initialize() {
        moveTranId("");
        moveTranTypeCd("");
        moveTranSource("");
        moveTranDesc("");
        moveTranMerchantName("");
        moveTranMerchantCity("");
        moveTranMerchantZip("");
        moveTranCardNum("");
        moveTranOrigTs("");
        moveTranProcTs("");

        moveTranCatCd(0);
        moveTranMerchantId(0L);
        moveTranAmt(BigDecimal.ZERO.setScale(TRAN_AMT_SCALE, RoundingMode.DOWN));

    }

    // The direction of truncation is not a detail: COBOL fills a PIC X receiver from its leftmost position
    // and discards what does not fit on the RIGHT, but aligns a PIC 9 receiver on the implied decimal point
    // and discards high-order digits on the LEFT.

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
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranTypeCd(String value) {
        codec.writePicX(area, TRAN_TYPE_CD, value);
    }

    /**
     * {@code MOVE ... TO TRAN-SOURCE} - pads or right-truncates to {@link #TRAN_SOURCE_LENGTH}.
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
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranMerchantName(String value) {
        codec.writePicX(area, TRAN_MERCHANT_NAME, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-CITY} - pads or right-truncates to
     * {@link #TRAN_MERCHANT_CITY_LENGTH}.
     *
     * @param value the sending value
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void moveTranMerchantCity(String value) {
        codec.writePicX(area, TRAN_MERCHANT_CITY, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-ZIP} - pads or right-truncates to {@link #TRAN_MERCHANT_ZIP_LENGTH}.
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
     * <p>Reproduces {@code MOVE XREF-CARD-NUM TO TRAN-CARD-NUM} at {@code app/cbl/CBACT04C.cbl:495}, an
     * exact {@code X(16)} to {@code X(16)} move that neither pads nor truncates.
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
     *     {@code PIC 9(04)} and has nowhere to record a sign
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveTranCatCd(int value) {
        codec.writePic9(area, TRAN_CAT_CD, value);
    }

    /**
     * {@code MOVE ... TO TRAN-CAT-CD} from an alphanumeric sender - the case
     * {@code app/cbl/CBACT04C.cbl:483} exercises with {@code MOVE '05' TO TRAN-CAT-CD}.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveTranCatCd(String digits) {
        codec.writePic9(area, TRAN_CAT_CD, digits);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-ID} from a numeric sender - zero-fills on the left to
     * {@link #TRAN_MERCHANT_ID_LENGTH}.
     *
     * @param value the sending value; must not be negative
     * @throws IllegalArgumentException if {@code value} is negative
     */
    public void moveTranMerchantId(long value) {
        codec.writePic9(area, TRAN_MERCHANT_ID, value);
    }

    /**
     * {@code MOVE ... TO TRAN-MERCHANT-ID} from an alphanumeric sender - treated as an unsigned integer,
     * aligned on the implied decimal point and zero-filled on the left, exactly as described on
     * {@link #moveTranCatCd(String)}.
     *
     * @param digits the sending value's characters; every one must be a digit
     * @throws NullPointerException if {@code digits} is {@code null}
     * @throws IllegalArgumentException if any character is not a digit
     */
    public void moveTranMerchantId(String digits) {
        codec.writePic9(area, TRAN_MERCHANT_ID, digits);
    }

    /**
     * {@code MOVE ... TO TRAN-AMT} - stores the amount at scale exactly {@link #TRAN_AMT_SCALE} with a
     * zoned sign overpunch in the trailing byte.
     *
     * @param value the amount to store; may be negative, and its scale may exceed the field's
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value has more integer digits than
     *     {@link #TRAN_AMT_INTEGER_DIGITS}
     */
    public void moveTranAmt(BigDecimal value) {
        Objects.requireNonNull(value, "An amount is required to store into TRAN-AMT; use "
                + "moveTranAmt(CobolDecimal.monetaryZero()) to store a positive zero");
        codec.writeMonetary(area, TRAN_AMT, CobolDecimal.storeMonetary(value));
    }

    /**
     * Stores a pre-built {@code TRAN-AMT} image verbatim, sign overpunch and all.
     *
     * @param image exactly {@link #TRAN_AMT_LENGTH} characters - ten leading digits followed by a digit or
     *     a zoned overpunch character
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #TRAN_AMT_LENGTH} characters
     */
    public void writeTranAmtImage(String image) {
        Objects.requireNonNull(image, "A TRAN-AMT image is required");
        if (image.length() != TRAN_AMT_LENGTH) {
            throw new IllegalArgumentException("A TRAN-AMT image of " + image.length()
                    + " character(s) was supplied but PIC S9(09)V99 occupies exactly "
                    + TRAN_AMT_LENGTH + "; the sign is overpunched into the trailing byte rather than "
                    + "stored separately, so the image is never padded or truncated here");
        }
        area.writeSpan(TRAN_AMT, image);
    }

    // CBACT04C:485-489 strings 24 characters into TRAN-DESC PIC X(100) and bytes 25-100 keep their prior
    // content, so a padding setter cannot reproduce that record. Offered for every PIC X field, and for no
    // numeric field: STRING requires an alphanumeric receiver, and none of the three numeric spans is one.

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-ID} - overlays from the field's first byte and leaves
     * the remainder untouched.
     *
     * @param operands the sending items in order, each already at its own declared width
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
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
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
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
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranSource(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_SOURCE, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-DESC} - overlays from the field's first byte and leaves
     * the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranDesc(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_DESC, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-MERCHANT-NAME} - overlays from the field's first byte
     * and leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranMerchantName(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_MERCHANT_NAME, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-MERCHANT-CITY} - overlays from the field's first byte
     * and leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranMerchantCity(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_MERCHANT_CITY, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-MERCHANT-ZIP} - overlays from the field's first byte
     * and leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranMerchantZip(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_MERCHANT_ZIP, operands);
    }

    /**
     * {@code STRING ... DELIMITED BY SIZE INTO TRAN-CARD-NUM} - overlays from the field's first byte and
     * leaves the remainder untouched.
     *
     * @param operands the sending items in order
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
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
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
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
     * @throws NullPointerException if {@code operands} or any operand is {@code null}
     * @throws IllegalArgumentException if no operand is supplied
     */
    public void stringIntoTranProcTs(String... operands) {
        codec.stringIntoDelimitedBySize(area, TRAN_PROC_TS, operands);
    }

    /**
     * Two records are equal when they hold identical bytes under the same code page.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code TranRecord} with the same charset and the same
     *     350 bytes
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
                + ", TRAN-CARD-NUM='" + SensitiveDiagnostics.maskPan(tranCardNum()) + '\''
                + ", TRAN-ORIG-TS='" + tranOrigTs() + '\''
                + ", TRAN-PROC-TS='" + tranProcTs() + '\''
                + ", FILLER.length=" + filler().length()
                + ']';
    }

    private static Charset requireCharset(Charset charset) {
        return Objects.requireNonNull(charset, "A charset must be supplied explicitly for a "
                + "TRAN-RECORD: US-ASCII for the app/data/ASCII fixtures, IBM037 for EBCDIC datasets. "
                + "The platform default is never used, because the sign overpunch bytes differ between "
                + "code pages");
    }

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
