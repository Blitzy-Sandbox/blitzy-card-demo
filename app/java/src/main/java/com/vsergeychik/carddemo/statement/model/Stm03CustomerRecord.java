package com.vsergeychik.carddemo.statement.model;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code CUSTOMER-RECORD} of {@code app/cpy/CUSTREC.cpy} - the 500-byte {@code CUSTFILE} record as the
 * statement job sees it.
 *
 * <p>Never rename, normalise or hyphenate {@code CUST-DOB-YYYYMMDD}.
 *
 * @param custId the {@code CUST-ID PIC 9(09)} image, the {@code CUSTFILE} KSDS key
 * @param custFirstName the {@code CUST-FIRST-NAME PIC X(25)} image, space-padded, untrimmed
 * @param custMiddleName the {@code CUST-MIDDLE-NAME PIC X(25)} image, space-padded, untrimmed
 * @param custLastName the {@code CUST-LAST-NAME PIC X(25)} image, space-padded, untrimmed
 * @param custAddrLine1 the {@code CUST-ADDR-LINE-1 PIC X(50)} image
 * @param custAddrLine2 the {@code CUST-ADDR-LINE-2 PIC X(50)} image
 * @param custAddrLine3 the {@code CUST-ADDR-LINE-3 PIC X(50)} image
 * @param custAddrStateCd the {@code CUST-ADDR-STATE-CD PIC X(02)} image
 * @param custAddrCountryCd the {@code CUST-ADDR-COUNTRY-CD PIC X(03)} image
 * @param custAddrZip the {@code CUST-ADDR-ZIP PIC X(10)} image
 * @param custPhoneNum1 the {@code CUST-PHONE-NUM-1 PIC X(15)} image
 * @param custPhoneNum2 the {@code CUST-PHONE-NUM-2 PIC X(15)} image
 * @param custSsn the {@code CUST-SSN PIC 9(09)} image
 * @param custGovtIssuedId the {@code CUST-GOVT-ISSUED-ID PIC X(20)} image
 * @param custDobYyyymmdd the {@code CUST-DOB-YYYYMMDD PIC X(10)} image - the field whose name is this
 *     type's entire reason for existing, spelled exactly as {@code CUSTREC.cpy} spells it and never as
 *     {@code CVCUS01Y.cpy} does
 * @param custEftAccountId the {@code CUST-EFT-ACCOUNT-ID PIC X(10)} image
 * @param custPriCardHolderInd the {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)} image
 * @param custFicoCreditScore the {@code CUST-FICO-CREDIT-SCORE PIC 9(03)} image
 */
public record Stm03CustomerRecord(String custId,
                                  String custFirstName,
                                  String custMiddleName,
                                  String custLastName,
                                  String custAddrLine1,
                                  String custAddrLine2,
                                  String custAddrLine3,
                                  String custAddrStateCd,
                                  String custAddrCountryCd,
                                  String custAddrZip,
                                  String custPhoneNum1,
                                  String custPhoneNum2,
                                  String custSsn,
                                  String custGovtIssuedId,
                                  String custDobYyyymmdd,
                                  String custEftAccountId,
                                  String custPriCardHolderInd,
                                  String custFicoCreditScore) {
    /**
     * The declared record width: {@code 500} bytes, exactly as {@code CUSTREC.cpy}'s header comment states
     * and as {@code CBSTM03B}'s {@code 9 + 491} {@code FD} split corroborates.
     */
    public static final int RECORD_LENGTH = 500;

    /**
     * Offset of {@code CUST-ID PIC 9(09)}: {@code 0}.
     */
    public static final int CUST_ID_OFFSET = 0;

    /**
     * Length of {@code CUST-ID PIC 9(09)}: {@code 9}.
     */
    public static final int CUST_ID_LENGTH = 9;

    /**
     * Offset of {@code CUST-FIRST-NAME PIC X(25)}: {@code 9}.
     */
    public static final int CUST_FIRST_NAME_OFFSET = CUST_ID_OFFSET + CUST_ID_LENGTH;

    /**
     * Length of {@code CUST-FIRST-NAME PIC X(25)}: {@code 25}.
     */
    public static final int CUST_FIRST_NAME_LENGTH = 25;

    /**
     * Offset of {@code CUST-MIDDLE-NAME PIC X(25)}: {@code 34}.
     */
    public static final int CUST_MIDDLE_NAME_OFFSET = CUST_FIRST_NAME_OFFSET + CUST_FIRST_NAME_LENGTH;

    /**
     * Length of {@code CUST-MIDDLE-NAME PIC X(25)}: {@code 25}.
     */
    public static final int CUST_MIDDLE_NAME_LENGTH = 25;

    /**
     * Offset of {@code CUST-LAST-NAME PIC X(25)}: {@code 59}.
     */
    public static final int CUST_LAST_NAME_OFFSET =
            CUST_MIDDLE_NAME_OFFSET + CUST_MIDDLE_NAME_LENGTH;

    /**
     * Length of {@code CUST-LAST-NAME PIC X(25)}: {@code 25}.
     */
    public static final int CUST_LAST_NAME_LENGTH = 25;

    /**
     * Offset of {@code CUST-ADDR-LINE-1 PIC X(50)}: {@code 84}.
     */
    public static final int CUST_ADDR_LINE_1_OFFSET = CUST_LAST_NAME_OFFSET + CUST_LAST_NAME_LENGTH;

    /**
     * Length of {@code CUST-ADDR-LINE-1 PIC X(50)}: {@code 50}.
     */
    public static final int CUST_ADDR_LINE_1_LENGTH = 50;

    /**
     * Offset of {@code CUST-ADDR-LINE-2 PIC X(50)}: {@code 134}.
     */
    public static final int CUST_ADDR_LINE_2_OFFSET =
            CUST_ADDR_LINE_1_OFFSET + CUST_ADDR_LINE_1_LENGTH;

    /**
     * Length of {@code CUST-ADDR-LINE-2 PIC X(50)}: {@code 50}.
     */
    public static final int CUST_ADDR_LINE_2_LENGTH = 50;

    /**
     * Offset of {@code CUST-ADDR-LINE-3 PIC X(50)}: {@code 184}.
     */
    public static final int CUST_ADDR_LINE_3_OFFSET =
            CUST_ADDR_LINE_2_OFFSET + CUST_ADDR_LINE_2_LENGTH;

    /**
     * Length of {@code CUST-ADDR-LINE-3 PIC X(50)}: {@code 50}.
     */
    public static final int CUST_ADDR_LINE_3_LENGTH = 50;

    /**
     * Offset of {@code CUST-ADDR-STATE-CD PIC X(02)}: {@code 234}.
     */
    public static final int CUST_ADDR_STATE_CD_OFFSET =
            CUST_ADDR_LINE_3_OFFSET + CUST_ADDR_LINE_3_LENGTH;

    /**
     * Length of {@code CUST-ADDR-STATE-CD PIC X(02)}: {@code 2}.
     */
    public static final int CUST_ADDR_STATE_CD_LENGTH = 2;

    /**
     * Offset of {@code CUST-ADDR-COUNTRY-CD PIC X(03)}: {@code 236}.
     */
    public static final int CUST_ADDR_COUNTRY_CD_OFFSET =
            CUST_ADDR_STATE_CD_OFFSET + CUST_ADDR_STATE_CD_LENGTH;

    /**
     * Length of {@code CUST-ADDR-COUNTRY-CD PIC X(03)}: {@code 3}.
     */
    public static final int CUST_ADDR_COUNTRY_CD_LENGTH = 3;

    /**
     * Offset of {@code CUST-ADDR-ZIP PIC X(10)}: {@code 239}.
     */
    public static final int CUST_ADDR_ZIP_OFFSET =
            CUST_ADDR_COUNTRY_CD_OFFSET + CUST_ADDR_COUNTRY_CD_LENGTH;

    /**
     * Length of {@code CUST-ADDR-ZIP PIC X(10)}: {@code 10}.
     */
    public static final int CUST_ADDR_ZIP_LENGTH = 10;

    /**
     * Offset of {@code CUST-PHONE-NUM-1 PIC X(15)}: {@code 249}.
     */
    public static final int CUST_PHONE_NUM_1_OFFSET = CUST_ADDR_ZIP_OFFSET + CUST_ADDR_ZIP_LENGTH;

    /**
     * Length of {@code CUST-PHONE-NUM-1 PIC X(15)}: {@code 15}.
     */
    public static final int CUST_PHONE_NUM_1_LENGTH = 15;

    /**
     * Offset of {@code CUST-PHONE-NUM-2 PIC X(15)}: {@code 264}.
     */
    public static final int CUST_PHONE_NUM_2_OFFSET =
            CUST_PHONE_NUM_1_OFFSET + CUST_PHONE_NUM_1_LENGTH;

    /**
     * Length of {@code CUST-PHONE-NUM-2 PIC X(15)}: {@code 15}.
     */
    public static final int CUST_PHONE_NUM_2_LENGTH = 15;

    /**
     * Offset of {@code CUST-SSN PIC 9(09)}: {@code 279}.
     */
    public static final int CUST_SSN_OFFSET = CUST_PHONE_NUM_2_OFFSET + CUST_PHONE_NUM_2_LENGTH;

    /**
     * Length of {@code CUST-SSN PIC 9(09)}: {@code 9}.
     */
    public static final int CUST_SSN_LENGTH = 9;

    /**
     * Offset of {@code CUST-GOVT-ISSUED-ID PIC X(20)}: {@code 288}.
     */
    public static final int CUST_GOVT_ISSUED_ID_OFFSET = CUST_SSN_OFFSET + CUST_SSN_LENGTH;

    /**
     * Length of {@code CUST-GOVT-ISSUED-ID PIC X(20)}: {@code 20}.
     */
    public static final int CUST_GOVT_ISSUED_ID_LENGTH = 20;

    /**
     * Offset of {@code CUST-DOB-YYYYMMDD PIC X(10)}: {@code 308}.
     */
    public static final int CUST_DOB_YYYYMMDD_OFFSET =
            CUST_GOVT_ISSUED_ID_OFFSET + CUST_GOVT_ISSUED_ID_LENGTH;

    /**
     * Length of {@code CUST-DOB-YYYYMMDD PIC X(10)}: {@code 10}.
     */
    public static final int CUST_DOB_YYYYMMDD_LENGTH = 10;

    /**
     * Offset of {@code CUST-EFT-ACCOUNT-ID PIC X(10)}: {@code 318}.
     */
    public static final int CUST_EFT_ACCOUNT_ID_OFFSET =
            CUST_DOB_YYYYMMDD_OFFSET + CUST_DOB_YYYYMMDD_LENGTH;

    /**
     * Length of {@code CUST-EFT-ACCOUNT-ID PIC X(10)}: {@code 10}.
     */
    public static final int CUST_EFT_ACCOUNT_ID_LENGTH = 10;

    /**
     * Offset of {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}: {@code 328}.
     */
    public static final int CUST_PRI_CARD_HOLDER_IND_OFFSET =
            CUST_EFT_ACCOUNT_ID_OFFSET + CUST_EFT_ACCOUNT_ID_LENGTH;

    /**
     * Length of {@code CUST-PRI-CARD-HOLDER-IND PIC X(01)}: {@code 1}.
     */
    public static final int CUST_PRI_CARD_HOLDER_IND_LENGTH = 1;

    /**
     * Offset of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}: {@code 329}.
     */
    public static final int CUST_FICO_CREDIT_SCORE_OFFSET =
            CUST_PRI_CARD_HOLDER_IND_OFFSET + CUST_PRI_CARD_HOLDER_IND_LENGTH;

    /**
     * Length of {@code CUST-FICO-CREDIT-SCORE PIC 9(03)}: {@code 3}.
     */
    public static final int CUST_FICO_CREDIT_SCORE_LENGTH = 3;

    /**
     * Offset of the trailing {@code FILLER PIC X(168)}: {@code 332}.
     */
    public static final int FILLER_OFFSET =
            CUST_FICO_CREDIT_SCORE_OFFSET + CUST_FICO_CREDIT_SCORE_LENGTH;

    /**
     * Length of the trailing {@code FILLER PIC X(168)}: {@code 168}, taking the record to 500.
     */
    public static final int FILLER_LENGTH = 168;

    /**
     * Offset of the {@code CUSTFILE} KSDS key: {@code 0}.
     */
    public static final int KEY_OFFSET = CUST_ID_OFFSET;

    /**
     * Length of the {@code CUSTFILE} KSDS key {@code CUST-ID}: {@code 9}.
     */
    public static final int KEY_LENGTH = CUST_ID_LENGTH;

    // The descriptor table: one FieldSpan per copybook item, in declaration order, built from the constants
    // above so that a name, an offset, a length and a PICTURE category are stated exactly once each. Field
    // names are carried VERBATIM as the copybook spells them, hyphens and all, because parity diffing is
    // keyed by name.

    /**
     * {@code 05 CUST-ID PIC 9(09).} - the {@code CUSTFILE} KSDS key, at offset 0, 9 bytes.
     */
    public static final FieldSpan CUST_ID =
            FieldSpan.unsignedNumeric("CUST-ID", CUST_ID_OFFSET, CUST_ID_LENGTH);

    /**
     * {@code 05 CUST-FIRST-NAME PIC X(25).} - offset 9, 25 bytes.
     */
    public static final FieldSpan CUST_FIRST_NAME =
            FieldSpan.alphanumeric("CUST-FIRST-NAME", CUST_FIRST_NAME_OFFSET, CUST_FIRST_NAME_LENGTH);

    /**
     * {@code 05 CUST-MIDDLE-NAME PIC X(25).} - offset 34, 25 bytes.
     */
    public static final FieldSpan CUST_MIDDLE_NAME = FieldSpan.alphanumeric("CUST-MIDDLE-NAME",
            CUST_MIDDLE_NAME_OFFSET, CUST_MIDDLE_NAME_LENGTH);

    /**
     * {@code 05 CUST-LAST-NAME PIC X(25).} - offset 59, 25 bytes.
     */
    public static final FieldSpan CUST_LAST_NAME =
            FieldSpan.alphanumeric("CUST-LAST-NAME", CUST_LAST_NAME_OFFSET, CUST_LAST_NAME_LENGTH);

    /**
     * {@code 05 CUST-ADDR-LINE-1 PIC X(50).} - offset 84, 50 bytes.
     */
    public static final FieldSpan CUST_ADDR_LINE_1 = FieldSpan.alphanumeric("CUST-ADDR-LINE-1",
            CUST_ADDR_LINE_1_OFFSET, CUST_ADDR_LINE_1_LENGTH);

    /**
     * {@code 05 CUST-ADDR-LINE-2 PIC X(50).} - offset 134, 50 bytes.
     */
    public static final FieldSpan CUST_ADDR_LINE_2 = FieldSpan.alphanumeric("CUST-ADDR-LINE-2",
            CUST_ADDR_LINE_2_OFFSET, CUST_ADDR_LINE_2_LENGTH);

    /**
     * {@code 05 CUST-ADDR-LINE-3 PIC X(50).} - offset 184, 50 bytes.
     */
    public static final FieldSpan CUST_ADDR_LINE_3 = FieldSpan.alphanumeric("CUST-ADDR-LINE-3",
            CUST_ADDR_LINE_3_OFFSET, CUST_ADDR_LINE_3_LENGTH);

    /**
     * {@code 05 CUST-ADDR-STATE-CD PIC X(02).} - offset 234, 2 bytes.
     */
    public static final FieldSpan CUST_ADDR_STATE_CD = FieldSpan.alphanumeric("CUST-ADDR-STATE-CD",
            CUST_ADDR_STATE_CD_OFFSET, CUST_ADDR_STATE_CD_LENGTH);

    /**
     * {@code 05 CUST-ADDR-COUNTRY-CD PIC X(03).} - offset 236, 3 bytes.
     */
    public static final FieldSpan CUST_ADDR_COUNTRY_CD = FieldSpan.alphanumeric(
            "CUST-ADDR-COUNTRY-CD", CUST_ADDR_COUNTRY_CD_OFFSET, CUST_ADDR_COUNTRY_CD_LENGTH);

    /**
     * {@code 05 CUST-ADDR-ZIP PIC X(10).} - offset 239, 10 bytes.
     */
    public static final FieldSpan CUST_ADDR_ZIP =
            FieldSpan.alphanumeric("CUST-ADDR-ZIP", CUST_ADDR_ZIP_OFFSET, CUST_ADDR_ZIP_LENGTH);

    /**
     * {@code 05 CUST-PHONE-NUM-1 PIC X(15).} - offset 249, 15 bytes.
     */
    public static final FieldSpan CUST_PHONE_NUM_1 = FieldSpan.alphanumeric("CUST-PHONE-NUM-1",
            CUST_PHONE_NUM_1_OFFSET, CUST_PHONE_NUM_1_LENGTH);

    /**
     * {@code 05 CUST-PHONE-NUM-2 PIC X(15).} - offset 264, 15 bytes.
     */
    public static final FieldSpan CUST_PHONE_NUM_2 = FieldSpan.alphanumeric("CUST-PHONE-NUM-2",
            CUST_PHONE_NUM_2_OFFSET, CUST_PHONE_NUM_2_LENGTH);

    /**
     * {@code 05 CUST-SSN PIC 9(09).} - offset 279, 9 bytes, unsigned zoned {@code DISPLAY}.
     */
    public static final FieldSpan CUST_SSN =
            FieldSpan.unsignedNumeric("CUST-SSN", CUST_SSN_OFFSET, CUST_SSN_LENGTH);

    /**
     * {@code 05 CUST-GOVT-ISSUED-ID PIC X(20).} - offset 288, 20 bytes.
     */
    public static final FieldSpan CUST_GOVT_ISSUED_ID = FieldSpan.alphanumeric("CUST-GOVT-ISSUED-ID",
            CUST_GOVT_ISSUED_ID_OFFSET, CUST_GOVT_ISSUED_ID_LENGTH);

    /**
     * {@code 05 CUST-DOB-YYYYMMDD PIC X(10).} - offset 308, 10 bytes.
     */
    public static final FieldSpan CUST_DOB_YYYYMMDD = FieldSpan.alphanumeric("CUST-DOB-YYYYMMDD",
            CUST_DOB_YYYYMMDD_OFFSET, CUST_DOB_YYYYMMDD_LENGTH);

    /**
     * {@code 05 CUST-EFT-ACCOUNT-ID PIC X(10).} - offset 318, 10 bytes.
     */
    public static final FieldSpan CUST_EFT_ACCOUNT_ID = FieldSpan.alphanumeric("CUST-EFT-ACCOUNT-ID",
            CUST_EFT_ACCOUNT_ID_OFFSET, CUST_EFT_ACCOUNT_ID_LENGTH);

    /**
     * {@code 05 CUST-PRI-CARD-HOLDER-IND PIC X(01).} - offset 328, 1 byte.
     */
    public static final FieldSpan CUST_PRI_CARD_HOLDER_IND = FieldSpan.alphanumeric(
            "CUST-PRI-CARD-HOLDER-IND", CUST_PRI_CARD_HOLDER_IND_OFFSET,
            CUST_PRI_CARD_HOLDER_IND_LENGTH);

    /**
     * {@code 05 CUST-FICO-CREDIT-SCORE PIC 9(03).} - offset 329, 3 bytes, unsigned zoned.
     */
    public static final FieldSpan CUST_FICO_CREDIT_SCORE = FieldSpan.unsignedNumeric(
            "CUST-FICO-CREDIT-SCORE", CUST_FICO_CREDIT_SCORE_OFFSET, CUST_FICO_CREDIT_SCORE_LENGTH);

    /**
     * {@code 05 FILLER PIC X(168).} - offset 332, 168 bytes, and the span that takes the record to its
     * declared 500.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    /**
     * The complete, self-checking layout of {@code CUSTOMER-RECORD}: nineteen contiguous spans from offset
     * 0 totalling exactly {@link #RECORD_LENGTH} bytes.
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
            CUST_DOB_YYYYMMDD,
            CUST_EFT_ACCOUNT_ID,
            CUST_PRI_CARD_HOLDER_IND,
            CUST_FICO_CREDIT_SCORE,
            FILLER);

    public Stm03CustomerRecord {
        custId = received(custId, CUST_ID);
        custFirstName = received(custFirstName, CUST_FIRST_NAME);
        custMiddleName = received(custMiddleName, CUST_MIDDLE_NAME);
        custLastName = received(custLastName, CUST_LAST_NAME);
        custAddrLine1 = received(custAddrLine1, CUST_ADDR_LINE_1);
        custAddrLine2 = received(custAddrLine2, CUST_ADDR_LINE_2);
        custAddrLine3 = received(custAddrLine3, CUST_ADDR_LINE_3);
        custAddrStateCd = received(custAddrStateCd, CUST_ADDR_STATE_CD);
        custAddrCountryCd = received(custAddrCountryCd, CUST_ADDR_COUNTRY_CD);
        custAddrZip = received(custAddrZip, CUST_ADDR_ZIP);
        custPhoneNum1 = received(custPhoneNum1, CUST_PHONE_NUM_1);
        custPhoneNum2 = received(custPhoneNum2, CUST_PHONE_NUM_2);
        custSsn = received(custSsn, CUST_SSN);
        custGovtIssuedId = received(custGovtIssuedId, CUST_GOVT_ISSUED_ID);
        custDobYyyymmdd = received(custDobYyyymmdd, CUST_DOB_YYYYMMDD);
        custEftAccountId = received(custEftAccountId, CUST_EFT_ACCOUNT_ID);
        custPriCardHolderInd = received(custPriCardHolderInd, CUST_PRI_CARD_HOLDER_IND);
        custFicoCreditScore = received(custFicoCreditScore, CUST_FICO_CREDIT_SCORE);
    }

    private static String received(String image, FieldSpan field) {
        Objects.requireNonNull(image, "The image of " + field.name() + " is required; a "
                + "CUSTOMER-RECORD field is a fixed-width span that always holds bytes, so to blank "
                + "it supply spaces or an empty string rather than null");
        if (field.kind() == PictureKind.UNSIGNED_NUMERIC && isAllDigits(image)) {
            return PICTURE_RULES.movePic9(image, field.length());
        }
        return PICTURE_RULES.movePicX(image, field.length());
    }

    private static boolean isAllDigits(String image) {
        if (image.isEmpty()) {
            return false;
        }
        for (int position = 0; position < image.length(); position++) {
            char character = image.charAt(position);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * Decodes a {@code CUSTOMER-RECORD} from a record image, reading only the leading
     * {@link #RECORD_LENGTH} characters.
     *
     * @param image the record image; may be exactly {@link #RECORD_LENGTH} characters, longer - the
     *     1000-character case above - or shorter, in which case it is space-padded
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the decoded record, its eighteen field images each exactly its declared width
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a single-byte code page for the digits,
     *     the sign overpunch characters and the space
     */
    public static Stm03CustomerRecord decode(String image, Charset charset) {
        Objects.requireNonNull(image, "A record image is required to decode a CUSTOMER-RECORD; call "
                + "blank(Charset) for an initialised, empty record instead");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        String leading = codec.movePicX(image, RECORD_LENGTH);
        return fromArea(codec.wrap(codec.encodeImage(leading, "a CUSTOMER-RECORD image"), LAYOUT));
    }

    /**
     * Decodes a {@code CUSTOMER-RECORD} from record bytes, reading only the leading {@link #RECORD_LENGTH}
     * bytes.
     *
     * @param source the record bytes; may be exactly {@link #RECORD_LENGTH} bytes, longer - the 1000-byte
     *     {@code LK-M03B-FLDT} case - or shorter, in which case it is space-padded
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the decoded record
     * @throws NullPointerException if {@code source} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page
     */
    public static Stm03CustomerRecord decode(byte[] source, Charset charset) {
        Objects.requireNonNull(source, "Record bytes are required to decode a CUSTOMER-RECORD");
        Objects.requireNonNull(charset, "A charset is required to decode CUSTFILE bytes: fixed-width "
                + "mainframe data is bytes in a specific code page, so the code page is stated "
                + "explicitly and never derived from the platform");
        return decode(FixedWidthRecord.decodeText(source, charset, "a CUSTOMER-RECORD image"),
                charset);
    }

    /**
     * An initialised, empty {@code CUSTOMER-RECORD}: every alphanumeric span and the trailing
     * {@code FILLER} space-filled, and the three unsigned numeric spans zero-filled, following the COBOL
     * {@code INITIALIZE} convention.
     *
     * @param charset the code page whose space and zero bytes fill the record
     * @return a record whose field images are the initialised spans
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page
     */
    public static Stm03CustomerRecord blank(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return fromArea(codec.newRecord(LAYOUT));
    }

    /**
     * Reads all eighteen referable spans out of a record area, in copybook order and exactly as they sit in
     * it - no trimming, no numeric parsing, no reformatting.
     *
     * @param area the record image the spans are read from
     */
    private static Stm03CustomerRecord fromArea(FixedWidthRecord area) {
        return new Stm03CustomerRecord(
                area.readSpan(CUST_ID),
                area.readSpan(CUST_FIRST_NAME),
                area.readSpan(CUST_MIDDLE_NAME),
                area.readSpan(CUST_LAST_NAME),
                area.readSpan(CUST_ADDR_LINE_1),
                area.readSpan(CUST_ADDR_LINE_2),
                area.readSpan(CUST_ADDR_LINE_3),
                area.readSpan(CUST_ADDR_STATE_CD),
                area.readSpan(CUST_ADDR_COUNTRY_CD),
                area.readSpan(CUST_ADDR_ZIP),
                area.readSpan(CUST_PHONE_NUM_1),
                area.readSpan(CUST_PHONE_NUM_2),
                area.readSpan(CUST_SSN),
                area.readSpan(CUST_GOVT_ISSUED_ID),
                area.readSpan(CUST_DOB_YYYYMMDD),
                area.readSpan(CUST_EFT_ACCOUNT_ID),
                area.readSpan(CUST_PRI_CARD_HOLDER_IND),
                area.readSpan(CUST_FICO_CREDIT_SCORE));
    }

    /**
     * Serialises this record to exactly {@link #RECORD_LENGTH} bytes.
     *
     * <p>The area starts from {@link FixedWidthCodec#newRecord(RecordLayout)}, so the trailing
     * {@code FILLER PIC X(168)} is already space-filled in the caller's own code page before any field is
     * written and remains so afterwards - it is emitted, never omitted.
     *
     * @param charset the code page to encode into, stated explicitly by the caller
     * @return a fresh array of exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, if a
     *     numeric field image is not all digits, or if an alphanumeric image encodes to more bytes than its
     *     span holds
     */
    public byte[] encode(Charset charset) {
        return area(new FixedWidthCodec(charset)).toByteArray();
    }

    /**
     * This record's whole {@link #RECORD_LENGTH}-byte group image as characters - the
     * {@code 01 CUSTOMER-RECORD} group viewed in one piece.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return exactly {@link #RECORD_LENGTH} characters
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if a
     *     field image cannot be placed in its span
     */
    public String groupImage(Charset charset) {
        return area(new FixedWidthCodec(charset)).readString(0, RECORD_LENGTH);
    }

    /**
     * The characters of one declared span, read out of this record's encoded area.
     *
     * @param field the span to read, normally one of this class's constants
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the span's characters, exactly as they sit in the record and never trimmed
     * @throws NullPointerException if {@code field} or {@code charset} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} reaches past {@link #RECORD_LENGTH}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if a
     *     field image cannot be placed in its span
     */
    public String spanImage(FieldSpan field, Charset charset) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span; pass one "
                + "of this class's FieldSpan constants, such as CUST_DOB_YYYYMMDD");
        return area(new FixedWidthCodec(charset)).readSpan(field);
    }

    /**
     * The raw bytes of one declared span, read out of this record's encoded area.
     *
     * @param field the span to read, normally one of this class's constants
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return a fresh array of exactly {@code field.length()} bytes
     * @throws NullPointerException if {@code field} or {@code charset} is {@code null}
     * @throws IndexOutOfBoundsException if {@code field} reaches past {@link #RECORD_LENGTH}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if a
     *     field image cannot be placed in its span
     */
    public byte[] spanBytes(FieldSpan field, Charset charset) {
        Objects.requireNonNull(field, "A field descriptor is required to read a named span; pass one "
                + "of this class's FieldSpan constants, such as CUST_DOB_YYYYMMDD");
        return area(new FixedWidthCodec(charset)).readSpanBytes(field);
    }

    /**
     * This record decomposed into its named field images, in copybook declaration order.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return an unmodifiable, insertion-ordered map of eighteen copybook field names to their exact span
     *     images
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if a
     *     field image cannot be placed in its span
     */
    public Map<String, String> fieldImages(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return Collections.unmodifiableMap(
                codec.deserialise(LAYOUT, area(codec).toByteArray()));
    }

    /**
     * {@code CUST-ID} as a value: the nine-digit {@code CUSTFILE} KSDS key.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the key, {@code 0} to {@code 999999999}
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if
     *     the {@code CUST-ID} span does not hold nine digits - a blank or corrupted key is reported rather than
     *     silently read as zero
     */
    public int custIdValue(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPic9AsInt(area(codec), CUST_ID);
    }

    /**
     * {@code CUST-SSN} as a value: the nine-digit social security number, exactly as the copybook declares
     * it - {@code PIC 9(09)}, unformatted and unmasked.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the number, {@code 0} to {@code 999999999}
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if
     *     the {@code CUST-SSN} span does not hold nine digits
     */
    public int custSsnValue(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPic9AsInt(area(codec), CUST_SSN);
    }

    /**
     * {@code CUST-FICO-CREDIT-SCORE} as a value: the three-digit score that {@code CBSTM03A.CBL:485} moves
     * onto the statement line with {@code MOVE CUST-FICO-CREDIT-SCORE TO ST-FICO-SCORE}.
     *
     * <p>Three digits cannot overflow an {@code int}, and no range check is applied beyond that:
     * {@code PIC 9(03)} constrains the field to {@code 000} through {@code 999} and the COBOL asserts
     * nothing further, so neither does this.
     *
     * @param charset the code page of the record's data, stated explicitly by the caller
     * @return the score, {@code 0} to {@code 999}
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not a suitable single-byte code page, or if
     *     the {@code CUST-FICO-CREDIT-SCORE} span does not hold three digits
     */
    public int custFicoCreditScoreValue(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.readPic9AsInt(area(codec), CUST_FICO_CREDIT_SCORE);
    }

    /**
     * A diagnostic rendering that names every field as {@code CUSTREC} spells it and withholds the personal
     * data, per {@link SensitiveDiagnostics}.
     *
     * @return a rendering safe to log, naming all 18 fields
     */
    @Override
    public String toString() {
        return "Stm03CustomerRecord{CUST-ID="
                + SensitiveDiagnostics.maskIdentifier(custId)
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
                + ", CUST-DOB-YYYYMMDD=" + SensitiveDiagnostics.describeText(custDobYyyymmdd)
                + ", CUST-EFT-ACCOUNT-ID=" + SensitiveDiagnostics.redacted()
                + ", CUST-PRI-CARD-HOLDER-IND=[" + custPriCardHolderInd
                + "], CUST-FICO-CREDIT-SCORE=" + custFicoCreditScore
                + '}';
    }

    private FixedWidthRecord area(FixedWidthCodec codec) {
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        area.writeSpan(CUST_ID, custId);
        area.writeSpan(CUST_FIRST_NAME, custFirstName);
        area.writeSpan(CUST_MIDDLE_NAME, custMiddleName);
        area.writeSpan(CUST_LAST_NAME, custLastName);
        area.writeSpan(CUST_ADDR_LINE_1, custAddrLine1);
        area.writeSpan(CUST_ADDR_LINE_2, custAddrLine2);
        area.writeSpan(CUST_ADDR_LINE_3, custAddrLine3);
        area.writeSpan(CUST_ADDR_STATE_CD, custAddrStateCd);
        area.writeSpan(CUST_ADDR_COUNTRY_CD, custAddrCountryCd);
        area.writeSpan(CUST_ADDR_ZIP, custAddrZip);
        area.writeSpan(CUST_PHONE_NUM_1, custPhoneNum1);
        area.writeSpan(CUST_PHONE_NUM_2, custPhoneNum2);
        area.writeSpan(CUST_SSN, custSsn);
        area.writeSpan(CUST_GOVT_ISSUED_ID, custGovtIssuedId);
        area.writeSpan(CUST_DOB_YYYYMMDD, custDobYyyymmdd);
        area.writeSpan(CUST_EFT_ACCOUNT_ID, custEftAccountId);
        area.writeSpan(CUST_PRI_CARD_HOLDER_IND, custPriCardHolderInd);
        area.writeSpan(CUST_FICO_CREDIT_SCORE, custFicoCreditScore);
        return area;
    }

}
