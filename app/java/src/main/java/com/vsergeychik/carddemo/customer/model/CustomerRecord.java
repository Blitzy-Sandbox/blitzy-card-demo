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
 * <p>All 50 rows of {@code app/data/ASCII/custdata.txt} measure exactly 500 bytes, so unlike
 * {@code cardxref.txt} - which is 36 bytes where {@code CVACT03Y} declares 50 - no row of this dataset ever
 * needs widening before it is decoded.
 */
public final class CustomerRecord {
    /**
     * The declared record width in bytes, as {@code CVCUS01Y}'s {@code RECLN 500} header states and as
     * {@code app/jcl/CUSTFILE.jcl}'s {@code RECORDSIZE(500 500)} fixes.
     */
    public static final int RECORD_LENGTH = 500;

    /**
     * {@code CUST-ID PIC 9(09)} - the record's primary key, and the nine-byte key at offset 0 that
     * {@code app/jcl/CUSTFILE.jcl} declares with {@code KEYS(9 0)}.
     */
    public static final FieldSpan CUST_ID = FieldSpan.unsignedNumeric("CUST-ID", 0, 9);

    /**
     * {@code CUST-FIRST-NAME PIC X(25)}.
     */
    public static final FieldSpan CUST_FIRST_NAME = FieldSpan.alphanumeric("CUST-FIRST-NAME", 9, 25);

    /**
     * {@code CUST-MIDDLE-NAME PIC X(25)}.
     */
    public static final FieldSpan CUST_MIDDLE_NAME =
            FieldSpan.alphanumeric("CUST-MIDDLE-NAME", 34, 25);

    /**
     * {@code CUST-LAST-NAME PIC X(25)}.
     */
    public static final FieldSpan CUST_LAST_NAME = FieldSpan.alphanumeric("CUST-LAST-NAME", 59, 25);

    /**
     * {@code CUST-ADDR-LINE-1 PIC X(50)}.
     */
    public static final FieldSpan CUST_ADDR_LINE_1 =
            FieldSpan.alphanumeric("CUST-ADDR-LINE-1", 84, 50);

    /**
     * {@code CUST-ADDR-LINE-2 PIC X(50)}.
     */
    public static final FieldSpan CUST_ADDR_LINE_2 =
            FieldSpan.alphanumeric("CUST-ADDR-LINE-2", 134, 50);

    /**
     * {@code CUST-ADDR-LINE-3 PIC X(50)} - carried to the screen's city field by
     * {@code app/cbl/COACTVWC.cbl:513}, which is why the copybook name rather than a screen name is used
     * here.
     */
    public static final FieldSpan CUST_ADDR_LINE_3 =
            FieldSpan.alphanumeric("CUST-ADDR-LINE-3", 184, 50);

    /**
     * {@code CUST-ADDR-STATE-CD PIC X(02)}.
     */
    public static final FieldSpan CUST_ADDR_STATE_CD =
            FieldSpan.alphanumeric("CUST-ADDR-STATE-CD", 234, 2);

    /**
     * {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     */
    public static final FieldSpan CUST_ADDR_COUNTRY_CD =
            FieldSpan.alphanumeric("CUST-ADDR-COUNTRY-CD", 236, 3);

    /**
     * {@code CUST-ADDR-ZIP PIC X(10)}.
     */
    public static final FieldSpan CUST_ADDR_ZIP = FieldSpan.alphanumeric("CUST-ADDR-ZIP", 239, 10);

    /**
     * {@code CUST-PHONE-NUM-1 PIC X(15)} - alphanumeric, holding values such as {@code (908)119-8310} whose
     * parentheses, hyphen and trailing spaces are all content.
     */
    public static final FieldSpan CUST_PHONE_NUM_1 =
            FieldSpan.alphanumeric("CUST-PHONE-NUM-1", 249, 15);

    /**
     * {@code CUST-PHONE-NUM-2 PIC X(15)}.
     */
    public static final FieldSpan CUST_PHONE_NUM_2 =
            FieldSpan.alphanumeric("CUST-PHONE-NUM-2", 264, 15);

    /**
     * {@code CUST-SSN PIC 9(09)} - sliced as nine characters by {@code app/cbl/COACTVWC.cbl:496-504}, so
     * its image matters as much as its value.
     */
    public static final FieldSpan CUST_SSN = FieldSpan.unsignedNumeric("CUST-SSN", 279, 9);

    /**
     * {@code CUST-GOVT-ISSUED-ID PIC X(20)} - alphanumeric despite holding all-digit values such as
     * {@code 00000000000049368437}, whose leading zeros are content and must never be normalised away by
     * reinterpreting the field as a number.
     */
    public static final FieldSpan CUST_GOVT_ISSUED_ID =
            FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID", 288, 20);

    /**
     * {@code CUST-DOB-YYYY-MM-DD PIC X(10)} - ten characters of text, never a parsed date.
     */
    public static final FieldSpan CUST_DOB_YYYY_MM_DD =
            FieldSpan.alphanumeric("CUST-DOB-YYYY-MM-DD", 308, 10);

    /**
     * {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     */
    public static final FieldSpan CUST_EFT_ACCOUNT_ID =
            FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID", 318, 10);

    /**
     * {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} - a single-character indicator, {@code 'Y'} or
     * {@code 'N'}.
     */
    public static final FieldSpan CUST_PRI_CARD_HOLDER_IND =
            FieldSpan.alphanumeric("CUST-PRI-CARD-HOLDER-IND", 328, 1);

    /**
     * {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}.
     */
    public static final FieldSpan CUST_FICO_CREDIT_SCORE =
            FieldSpan.unsignedNumeric("CUST-FICO-CREDIT-SCORE", 329, 3);

    /**
     * {@code FILLER PIC X(168)} - the trailing reserved span, declared explicitly as span 19 and never an
     * implicit gap.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(332, 168);

    /**
     * The complete layout of {@code CVCUS01Y}: 18 named spans plus the trailing {@code FILLER}, in copybook
     * declaration order.
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

    // Strictly per-instance: nothing below is static, because COBOL WORKING-STORAGE must never become
    // shared Java state - that would break row isolation and test determinism alike.

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    private int custId;

    private String custFirstName = blank(CUST_FIRST_NAME);

    private String custMiddleName = blank(CUST_MIDDLE_NAME);

    private String custLastName = blank(CUST_LAST_NAME);

    private String custAddrLine1 = blank(CUST_ADDR_LINE_1);

    private String custAddrLine2 = blank(CUST_ADDR_LINE_2);

    private String custAddrLine3 = blank(CUST_ADDR_LINE_3);

    private String custAddrStateCd = blank(CUST_ADDR_STATE_CD);

    private String custAddrCountryCd = blank(CUST_ADDR_COUNTRY_CD);

    private String custAddrZip = blank(CUST_ADDR_ZIP);

    private String custPhoneNum1 = blank(CUST_PHONE_NUM_1);

    private String custPhoneNum2 = blank(CUST_PHONE_NUM_2);

    private int custSsn;

    private String custGovtIssuedId = blank(CUST_GOVT_ISSUED_ID);

    private String custDobYyyyMmDd = blank(CUST_DOB_YYYY_MM_DD);

    private String custEftAccountId = blank(CUST_EFT_ACCOUNT_ID);

    private String custPriCardHolderInd = blank(CUST_PRI_CARD_HOLDER_IND);

    private int custFicoCreditScore;

    /**
     * Creates a record in its {@code INITIALIZE} state: every alphanumeric field holding its declared width
     * in spaces and every numeric field zero, so an immediate {@link #encode(Charset)} yields spaces in the
     * character spans, zeros in the numeric spans and 168 spaces in the trailing {@code FILLER}.
     */
    public CustomerRecord() {
    }

    public static CustomerRecord decode(byte[] source, Charset charset) {
        return decode(source, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a stored 500-byte record image using an existing codec.
     *
     * @param source exactly {@link #RECORD_LENGTH} bytes, as stored
     * @param codec the codec bound to the stored bytes' code page
     * @return a record carrying the decoded field values
     * @throws NullPointerException if {@code source} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code source} is not exactly {@link #RECORD_LENGTH} bytes, or if
     *     a numeric span does not hold digits
     */
    public static CustomerRecord decode(byte[] source, FixedWidthCodec codec) {
        Objects.requireNonNull(source, "A 500-byte CVCUS01Y record image is required to decode a "
                + "customer record");
        Objects.requireNonNull(codec, "A codec is required to decode a customer record: the stored "
                + "bytes' code page must be stated explicitly and is never derived from the platform");
        FixedWidthRecord area = codec.wrap(source, LAYOUT);
        CustomerRecord decoded = new CustomerRecord();
        // Every PIC X span is read UNTRIMMED, because its padding is part of the field and the parity
        // differ compares it.
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
     * Decodes a stored record image supplied as text, for the case where a fixed-width row arrives from
     * JDBC as a character value rather than as bytes.
     *
     * @param source exactly {@link #RECORD_LENGTH} characters' worth of stored image
     * @param charset the code page under which {@code source} is to be read as bytes
     * @return a record carrying the decoded field values
     * @throws NullPointerException if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code source} does not encode to exactly {@link #RECORD_LENGTH}
     *     bytes
     */
    public static CustomerRecord decode(String source, Charset charset) {
        Objects.requireNonNull(source, "A 500-character CVCUS01Y record image is required to decode "
                + "a customer record");
        Objects.requireNonNull(charset, "A charset is required to read a record image as bytes");
        return decode(FixedWidthRecord.encodeText(source, charset, "a CUSTOMER-RECORD image"),
                charset);
    }

    // Every span is written through FixedWidthCodec, so the PIC X rule (left justified, space-padded and
    // truncated on the RIGHT) and the PIC 9 rule (right justified, zero-filled and truncated on the LEFT)
    // each have exactly one implementation in the system.

    /**
     * Encodes this record as its 500-byte stored image.
     *
     * @param charset the code page to write under, supplied explicitly
     * @return exactly {@link #RECORD_LENGTH} bytes, with the trailing {@code FILLER} space-filled
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the required repertoire
     */
    public byte[] encode(Charset charset) {
        return encode(new FixedWidthCodec(charset));
    }

    /**
     * Encodes this record as its 500-byte stored image using an existing codec.
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
     * {@code CUSTOMER-RECORD} group, from the first byte of {@code CUST-ID} through the last byte of the
     * trailing {@code FILLER}.
     *
     * @param charset the code page to render under, supplied explicitly
     * @return exactly {@link #RECORD_LENGTH} characters
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the required repertoire
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
     */
    public String recordImage(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).readString(0, RECORD_LENGTH);
    }

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

    // COBOL lets a program treat a PIC 9(n) field as n characters, and this codebase does exactly that:
    // app/cbl/COACTVWC.cbl:496-504 slices CUST-SSN with reference modification, and
    // app/cbl/COACTUPC.cbl:710-712 and :742-744 declare the X/9 REDEFINES pairs outright.

    /**
     * Returns {@code CUST-ID} as its 9-character zero-filled image - the {@code PIC X(09)} view of the same
     * nine bytes that {@code app/cbl/CBSTM03B.CBL:72} declares as {@code FD-CUST-ID PIC X(09)} while
     * {@code app/cbl/CBCUS01C.cbl:39} declares them as {@code PIC 9(09)}.
     *
     * @param charset the code page whose digit repertoire the image is validated against
     * @return exactly 9 characters, left zero-filled; a {@code custId} of {@code 1} renders as
     *     {@code 000000001}
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
     */
    public String custIdImage(FixedWidthCodec codec) {
        return numericImage(codec, custId, CUST_ID);
    }

    /**
     * Returns {@code CUST-SSN} as its 9-character zero-filled image, which is the view
     * {@code app/cbl/COACTVWC.cbl:496-504} slices to build the {@code NNN-NN-NNNN} screen value.
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
     */
    public String custSsnImage(FixedWidthCodec codec) {
        return numericImage(codec, custSsn, CUST_SSN);
    }

    /**
     * Returns {@code CUST-FICO-CREDIT-SCORE} as its 3-character zero-filled image.
     *
     * @param charset the code page whose digit repertoire the image is validated against
     * @return exactly 3 characters, left zero-filled
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public String custFicoCreditScoreImage(Charset charset) {
        return custFicoCreditScoreImage(new FixedWidthCodec(charset));
    }

    /**
     * Returns {@code CUST-FICO-CREDIT-SCORE} as its 3-character zero-filled image, using an existing codec.
     *
     * @param codec the codec supplying the {@code PIC 9} left-zero-fill rule
     * @return exactly 3 characters, left zero-filled
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String custFicoCreditScoreImage(FixedWidthCodec codec) {
        return numericImage(codec, custFicoCreditScore, CUST_FICO_CREDIT_SCORE);
    }

    private static String numericImage(FixedWidthCodec codec, int value, FieldSpan field) {
        Objects.requireNonNull(codec, "A codec is required to render a numeric field image: the "
                + "PIC 9 left-zero-fill rule belongs to the codec, not to this model");
        return codec.movePic9(value, field.length());
    }

    private static String receive(String value, FieldSpan field) {
        if (value == null) {
            throw new NullPointerException("Field '" + field.name() + "' cannot be null: COBOL has no "
                    + "null, so an empty PIC X(" + field.length() + ") field holds spaces. Supply an "
                    + "empty string or spaces to blank it");
        }
        return PICTURE_RULES.movePicX(value, field.length());
    }

    private static String blank(FieldSpan field) {
        return PICTURE_RULES.movePicX("", field.length());
    }

    private static int receive(int value, FieldSpan field) {
        if (value < 0) {
            throw new IllegalArgumentException("Field '" + field.name() + "' is PIC 9("
                    + field.length() + "), an unsigned picture with no sign position, so it cannot "
                    + "hold " + value);
        }
        return Integer.parseInt(PICTURE_RULES.movePic9(value, field.length()));
    }

    // PIC X values are returned exactly as held, never trimmed, because the padding is part of the field
    // and the parity differ compares it - and since a setter applies the PIC X move on the way in, "as
    // held" is always exactly the declared width.

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
     * @param custId the new {@code CUST-ID}; must not be negative
     * @throws IllegalArgumentException if {@code custId} is negative
     */
    public void setCustId(int custId) {
        this.custId = receive(custId, CUST_ID);
    }

    /**
     * Reads span 2, {@code CUST-FIRST-NAME PIC X(25)}.
     *
     * @return {@code CUST-FIRST-NAME}, exactly 25 characters and untrimmed - the receiver's own content
     */
    public String getCustFirstName() {
        return custFirstName;
    }

    /**
     * Writes span 2, {@code CUST-FIRST-NAME PIC X(25)}.
     *
     * @param custFirstName the new {@code CUST-FIRST-NAME}; must not be {@code null}
     * @throws NullPointerException if {@code custFirstName} is {@code null}
     */
    public void setCustFirstName(String custFirstName) {
        this.custFirstName = receive(custFirstName, CUST_FIRST_NAME);
    }

    /**
     * Reads span 3, {@code CUST-MIDDLE-NAME PIC X(25)}.
     *
     * @return {@code CUST-MIDDLE-NAME}, exactly 25 characters and untrimmed - the receiver's own content
     */
    public String getCustMiddleName() {
        return custMiddleName;
    }

    /**
     * Writes span 3, {@code CUST-MIDDLE-NAME PIC X(25)}.
     *
     * @param custMiddleName the new {@code CUST-MIDDLE-NAME}; must not be {@code null}
     * @throws NullPointerException if {@code custMiddleName} is {@code null}
     */
    public void setCustMiddleName(String custMiddleName) {
        this.custMiddleName = receive(custMiddleName, CUST_MIDDLE_NAME);
    }

    /**
     * Reads span 4, {@code CUST-LAST-NAME PIC X(25)}.
     *
     * @return {@code CUST-LAST-NAME}, exactly 25 characters and untrimmed - the receiver's own content
     */
    public String getCustLastName() {
        return custLastName;
    }

    /**
     * Writes span 4, {@code CUST-LAST-NAME PIC X(25)}.
     *
     * @param custLastName the new {@code CUST-LAST-NAME}; must not be {@code null}
     * @throws NullPointerException if {@code custLastName} is {@code null}
     */
    public void setCustLastName(String custLastName) {
        this.custLastName = receive(custLastName, CUST_LAST_NAME);
    }

    /**
     * Reads span 5, {@code CUST-ADDR-LINE-1 PIC X(50)}.
     *
     * @return {@code CUST-ADDR-LINE-1}, exactly 50 characters and untrimmed - the receiver's own content
     */
    public String getCustAddrLine1() {
        return custAddrLine1;
    }

    /**
     * Writes span 5, {@code CUST-ADDR-LINE-1 PIC X(50)}.
     *
     * @param custAddrLine1 the new {@code CUST-ADDR-LINE-1}; must not be {@code null}
     * @throws NullPointerException if {@code custAddrLine1} is {@code null}
     */
    public void setCustAddrLine1(String custAddrLine1) {
        this.custAddrLine1 = receive(custAddrLine1, CUST_ADDR_LINE_1);
    }

    /**
     * Reads span 6, {@code CUST-ADDR-LINE-2 PIC X(50)}.
     *
     * @return {@code CUST-ADDR-LINE-2}, exactly 50 characters and untrimmed - the receiver's own content
     */
    public String getCustAddrLine2() {
        return custAddrLine2;
    }

    /**
     * Writes span 6, {@code CUST-ADDR-LINE-2 PIC X(50)}.
     *
     * @param custAddrLine2 the new {@code CUST-ADDR-LINE-2}; must not be {@code null}
     * @throws NullPointerException if {@code custAddrLine2} is {@code null}
     */
    public void setCustAddrLine2(String custAddrLine2) {
        this.custAddrLine2 = receive(custAddrLine2, CUST_ADDR_LINE_2);
    }

    /**
     * Reads span 7, {@code CUST-ADDR-LINE-3 PIC X(50)}.
     *
     * @return {@code CUST-ADDR-LINE-3}, exactly 50 characters and untrimmed - the receiver's own content
     */
    public String getCustAddrLine3() {
        return custAddrLine3;
    }

    /**
     * Writes span 7, {@code CUST-ADDR-LINE-3 PIC X(50)}.
     *
     * @param custAddrLine3 the new {@code CUST-ADDR-LINE-3}; must not be {@code null}
     * @throws NullPointerException if {@code custAddrLine3} is {@code null}
     */
    public void setCustAddrLine3(String custAddrLine3) {
        this.custAddrLine3 = receive(custAddrLine3, CUST_ADDR_LINE_3);
    }

    /**
     * Reads span 8, {@code CUST-ADDR-STATE-CD PIC X(02)}.
     *
     * @return {@code CUST-ADDR-STATE-CD}, exactly 2 characters and untrimmed - the receiver's own content
     */
    public String getCustAddrStateCd() {
        return custAddrStateCd;
    }

    /**
     * Writes span 8, {@code CUST-ADDR-STATE-CD PIC X(02)}.
     *
     * @param custAddrStateCd the new {@code CUST-ADDR-STATE-CD}; must not be {@code null}
     * @throws NullPointerException if {@code custAddrStateCd} is {@code null}
     */
    public void setCustAddrStateCd(String custAddrStateCd) {
        this.custAddrStateCd = receive(custAddrStateCd, CUST_ADDR_STATE_CD);
    }

    /**
     * Reads span 9, {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     *
     * @return {@code CUST-ADDR-COUNTRY-CD}, exactly 3 characters and untrimmed - the receiver's own content
     */
    public String getCustAddrCountryCd() {
        return custAddrCountryCd;
    }

    /**
     * Writes span 9, {@code CUST-ADDR-COUNTRY-CD PIC X(03)}.
     *
     * @param custAddrCountryCd the new {@code CUST-ADDR-COUNTRY-CD}; must not be {@code null}
     * @throws NullPointerException if {@code custAddrCountryCd} is {@code null}
     */
    public void setCustAddrCountryCd(String custAddrCountryCd) {
        this.custAddrCountryCd = receive(custAddrCountryCd, CUST_ADDR_COUNTRY_CD);
    }

    /**
     * Reads span 10, {@code CUST-ADDR-ZIP PIC X(10)}.
     *
     * @return {@code CUST-ADDR-ZIP}, exactly 10 characters and untrimmed - the receiver's own content
     */
    public String getCustAddrZip() {
        return custAddrZip;
    }

    /**
     * Writes span 10, {@code CUST-ADDR-ZIP PIC X(10)}.
     *
     * @param custAddrZip the new {@code CUST-ADDR-ZIP}; must not be {@code null}
     * @throws NullPointerException if {@code custAddrZip} is {@code null}
     */
    public void setCustAddrZip(String custAddrZip) {
        this.custAddrZip = receive(custAddrZip, CUST_ADDR_ZIP);
    }

    /**
     * Reads span 11, {@code CUST-PHONE-NUM-1 PIC X(15)}.
     *
     * @return {@code CUST-PHONE-NUM-1}, exactly 15 characters and untrimmed - the receiver's own content,
     *     with its punctuation intact
     */
    public String getCustPhoneNum1() {
        return custPhoneNum1;
    }

    /**
     * Writes span 11, {@code CUST-PHONE-NUM-1 PIC X(15)}.
     *
     * @param custPhoneNum1 the new {@code CUST-PHONE-NUM-1}; must not be {@code null}
     * @throws NullPointerException if {@code custPhoneNum1} is {@code null}
     */
    public void setCustPhoneNum1(String custPhoneNum1) {
        this.custPhoneNum1 = receive(custPhoneNum1, CUST_PHONE_NUM_1);
    }

    /**
     * Reads span 12, {@code CUST-PHONE-NUM-2 PIC X(15)}.
     *
     * @return {@code CUST-PHONE-NUM-2}, exactly 15 characters and untrimmed - the receiver's own content,
     *     with its punctuation intact
     */
    public String getCustPhoneNum2() {
        return custPhoneNum2;
    }

    /**
     * Writes span 12, {@code CUST-PHONE-NUM-2 PIC X(15)}.
     *
     * @param custPhoneNum2 the new {@code CUST-PHONE-NUM-2}; must not be {@code null}
     * @throws NullPointerException if {@code custPhoneNum2} is {@code null}
     */
    public void setCustPhoneNum2(String custPhoneNum2) {
        this.custPhoneNum2 = receive(custPhoneNum2, CUST_PHONE_NUM_2);
    }

    /**
     * Returns {@code CUST-SSN} as a value.
     *
     * @return {@code CUST-SSN}
     */
    public int getCustSsn() {
        return custSsn;
    }

    /**
     * Writes span 13, {@code CUST-SSN PIC 9(09)}.
     *
     * @param custSsn the new {@code CUST-SSN}; must not be negative
     * @throws IllegalArgumentException if {@code custSsn} is negative
     */
    public void setCustSsn(int custSsn) {
        this.custSsn = receive(custSsn, CUST_SSN);
    }

    /**
     * Reads span 14, {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * @return {@code CUST-GOVT-ISSUED-ID}, exactly 20 characters and untrimmed - the receiver's own
     *     content, with any leading zeros intact
     */
    public String getCustGovtIssuedId() {
        return custGovtIssuedId;
    }

    /**
     * Writes span 14, {@code CUST-GOVT-ISSUED-ID PIC X(20)}.
     *
     * @param custGovtIssuedId the new {@code CUST-GOVT-ISSUED-ID}; must not be {@code null}
     * @throws NullPointerException if {@code custGovtIssuedId} is {@code null}
     */
    public void setCustGovtIssuedId(String custGovtIssuedId) {
        this.custGovtIssuedId = receive(custGovtIssuedId, CUST_GOVT_ISSUED_ID);
    }

    /**
     * Returns {@code CUST-DOB-YYYY-MM-DD} as the ten characters it is.
     *
     * @return {@code CUST-DOB-YYYY-MM-DD}, exactly 10 characters and untrimmed - the receiver's own content
     */
    public String getCustDobYyyyMmDd() {
        return custDobYyyyMmDd;
    }

    /**
     * Writes span 15, {@code CUST-DOB-YYYY-MM-DD PIC X(10)}.
     *
     * @param custDobYyyyMmDd the new {@code CUST-DOB-YYYY-MM-DD}; must not be {@code null}, and is stored
     *     as text without being parsed or reformatted
     * @throws NullPointerException if {@code custDobYyyyMmDd} is {@code null}
     */
    public void setCustDobYyyyMmDd(String custDobYyyyMmDd) {
        this.custDobYyyyMmDd = receive(custDobYyyyMmDd, CUST_DOB_YYYY_MM_DD);
    }

    /**
     * Reads span 16, {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     *
     * @return {@code CUST-EFT-ACCOUNT-ID}, exactly 10 characters and untrimmed - the receiver's own content
     */
    public String getCustEftAccountId() {
        return custEftAccountId;
    }

    /**
     * Writes span 16, {@code CUST-EFT-ACCOUNT-ID PIC X(10)}.
     *
     * @param custEftAccountId the new {@code CUST-EFT-ACCOUNT-ID}; must not be {@code null}
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
     * @param custPriCardHolderInd the new {@code CUST-PRI-CARD-HOLDER-IND}; must not be {@code null}
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
     * @param custFicoCreditScore the new {@code CUST-FICO-CREDIT-SCORE}; must not be negative
     * @throws IllegalArgumentException if {@code custFicoCreditScore} is negative
     */
    public void setCustFicoCreditScore(int custFicoCreditScore) {
        this.custFicoCreditScore =
                receive(custFicoCreditScore, CUST_FICO_CREDIT_SCORE);
    }

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
                + ", CUST-ADDR-STATE-CD=[" + SensitiveDiagnostics.plain(custAddrStateCd)
                + "], CUST-ADDR-COUNTRY-CD=[" + SensitiveDiagnostics.plain(custAddrCountryCd)
                + "], CUST-ADDR-ZIP=" + SensitiveDiagnostics.describeText(custAddrZip)
                + ", CUST-PHONE-NUM-1=" + SensitiveDiagnostics.describeText(custPhoneNum1)
                + ", CUST-PHONE-NUM-2=" + SensitiveDiagnostics.describeText(custPhoneNum2)
                + ", CUST-SSN=" + SensitiveDiagnostics.redacted()
                + ", CUST-GOVT-ISSUED-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-DOB-YYYY-MM-DD=" + SensitiveDiagnostics.describeText(custDobYyyyMmDd)
                + ", CUST-EFT-ACCOUNT-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-PRI-CARD-HOLDER-IND=["
                + SensitiveDiagnostics.plain(custPriCardHolderInd)
                + "], CUST-FICO-CREDIT-SCORE=" + custFicoCreditScore
                + '}';
    }
}
