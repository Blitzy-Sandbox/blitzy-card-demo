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
 * The one Java type for {@code app/cpy/CVACT02Y.cpy}: the CardDemo {@code CARD-RECORD}, a fixed-width value
 * of exactly {@value #RECORD_LENGTH} bytes.
 *
 * <p>Consumer-side numeric views such as {@code COCRDSLC}'s
 * {@code CARD-CARD-NUM-N REDEFINES CARD-CARD-NUM-X PIC 9(16)} live in that program's working storage and
 * are deliberately not reproduced here.
 *
 * @param cardNum {@code CARD-NUM PIC X(16)} at offset {@value #CARD_NUM_OFFSET}: the {@code CARDDAT} base
 *     key, alphanumeric and leading-zero bearing, held space-padded to exactly {@value #CARD_NUM_LENGTH}
 *     characters
 * @param cardAcctId {@code CARD-ACCT-ID PIC 9(11)} at offset {@value #CARD_ACCT_ID_OFFSET}: the
 *     {@code CARDAIX} alternate-index key, an unsigned scale-free integer below
 *     {@value #CARD_ACCT_ID_EXCLUSIVE_LIMIT}
 * @param cardCvvCd {@code CARD-CVV-CD PIC 9(03)} at offset {@value #CARD_CVV_CD_OFFSET}: an unsigned
 *     scale-free integer below {@value #CARD_CVV_CD_EXCLUSIVE_LIMIT}
 * @param cardEmbossedName {@code CARD-EMBOSSED-NAME PIC X(50)} at offset
 *     {@value #CARD_EMBOSSED_NAME_OFFSET}, held space-padded to exactly {@value #CARD_EMBOSSED_NAME_LENGTH}
 *     characters and never trimmed
 * @param cardExpiraionDate {@code CARD-EXPIRAION-DATE PIC X(10)} at offset
 *     {@value #CARD_EXPIRAION_DATE_OFFSET}, an unvalidated {@code YYYY-MM-DD} span of exactly
 *     {@value #CARD_EXPIRAION_DATE_LENGTH} characters
 * @param cardActiveStatus {@code CARD-ACTIVE-STATUS PIC X(01)} at offset
 *     {@value #CARD_ACTIVE_STATUS_OFFSET}: one character, {@code 'Y'} or {@code 'N'} in practice, carried
 *     verbatim
 */
public record CardRecord(String cardNum,
                         long cardAcctId,
                         int cardCvvCd,
                         String cardEmbossedName,
                         String cardExpiraionDate,
                         String cardActiveStatus) {
    /**
     * The declared total width of {@code CARD-RECORD} in bytes: {@code 150}, stated by the copybook's own
     * {@code (RECLN 150)} header comment at {@code app/cpy/CVACT02Y.cpy:2} and confirmed by the fixture,
     * every row of which is exactly this wide.
     */
    public static final int RECORD_LENGTH = 150;

    /**
     * Absolute 0-based offset of {@code CARD-NUM} ({@code app/cpy/CVACT02Y.cpy:5}).
     */
    public static final int CARD_NUM_OFFSET = 0;

    /**
     * Declared width of {@code CARD-NUM PIC X(16)}, in characters.
     */
    public static final int CARD_NUM_LENGTH = 16;

    /**
     * Absolute 0-based offset of {@code CARD-ACCT-ID} ({@code app/cpy/CVACT02Y.cpy:6}).
     */
    public static final int CARD_ACCT_ID_OFFSET = 16;

    /**
     * Declared digit count of {@code CARD-ACCT-ID PIC 9(11)}, one digit per byte.
     */
    public static final int CARD_ACCT_ID_LENGTH = 11;

    /**
     * Absolute 0-based offset of {@code CARD-CVV-CD} ({@code app/cpy/CVACT02Y.cpy:7}).
     */
    public static final int CARD_CVV_CD_OFFSET = 27;

    /**
     * Declared digit count of {@code CARD-CVV-CD PIC 9(03)}, one digit per byte.
     */
    public static final int CARD_CVV_CD_LENGTH = 3;

    /**
     * Absolute 0-based offset of {@code CARD-EMBOSSED-NAME} ({@code app/cpy/CVACT02Y.cpy:8}).
     */
    public static final int CARD_EMBOSSED_NAME_OFFSET = 30;

    /**
     * Declared width of {@code CARD-EMBOSSED-NAME PIC X(50)}, in characters.
     */
    public static final int CARD_EMBOSSED_NAME_LENGTH = 50;

    /**
     * Absolute 0-based offset of {@code CARD-EXPIRAION-DATE} ({@code app/cpy/CVACT02Y.cpy:9}).
     */
    public static final int CARD_EXPIRAION_DATE_OFFSET = 80;

    /**
     * Declared width of {@code CARD-EXPIRAION-DATE PIC X(10)}, in characters.
     */
    public static final int CARD_EXPIRAION_DATE_LENGTH = 10;

    /**
     * Absolute 0-based offset of {@code CARD-ACTIVE-STATUS} ({@code app/cpy/CVACT02Y.cpy:10}).
     */
    public static final int CARD_ACTIVE_STATUS_OFFSET = 90;

    /**
     * Declared width of {@code CARD-ACTIVE-STATUS PIC X(01)}, in characters.
     */
    public static final int CARD_ACTIVE_STATUS_LENGTH = 1;

    /**
     * Absolute 0-based offset of the trailing {@code FILLER} ({@code app/cpy/CVACT02Y.cpy:11}).
     */
    public static final int FILLER_OFFSET = 91;

    /**
     * Declared width of {@code FILLER PIC X(59)}, in characters.
     */
    public static final int FILLER_LENGTH = 59;

    /**
     * One past the largest value {@code CARD-ACCT-ID PIC 9(11)} can hold: {@code 100000000000}.
     */
    public static final long CARD_ACCT_ID_EXCLUSIVE_LIMIT = 100_000_000_000L;

    /**
     * One past the largest value {@code CARD-CVV-CD PIC 9(03)} can hold: {@code 1000}.
     */
    public static final int CARD_CVV_CD_EXCLUSIVE_LIMIT = 1_000;

    /**
     * 0-based begin index of the year within {@code CARD-EXPIRAION-DATE}, translating COBOL's 1-based
     * reference modification {@code CARD-EXPIRAION-DATE(1:4)} ({@code app/cbl/COCRDUPC.cbl:1361}).
     */
    public static final int EXPIRAION_YEAR_BEGIN_INDEX = 0;

    /**
     * 0-based end index, exclusive, of the year within {@code CARD-EXPIRAION-DATE}.
     */
    public static final int EXPIRAION_YEAR_END_INDEX = 4;

    /**
     * 0-based begin index of the month, translating {@code CARD-EXPIRAION-DATE(6:2)}
     * ({@code app/cbl/COCRDUPC.cbl:1363}). 1-based position 5 - 0-based index 4 - is the {@code '-'}
     * separator and is skipped.
     */
    public static final int EXPIRAION_MONTH_BEGIN_INDEX = 5;

    /**
     * 0-based end index, exclusive, of the month within {@code CARD-EXPIRAION-DATE}.
     */
    public static final int EXPIRAION_MONTH_END_INDEX = 7;

    /**
     * 0-based begin index of the day, translating {@code CARD-EXPIRAION-DATE(9:2)}
     * ({@code app/cbl/COCRDUPC.cbl:1365}). 1-based position 8 - 0-based index 7 - is the second {@code '-'}
     * separator and is skipped.
     */
    public static final int EXPIRAION_DAY_BEGIN_INDEX = 8;

    /**
     * 0-based end index, exclusive, of the day within {@code CARD-EXPIRAION-DATE}, which is also the span's
     * full width.
     */
    public static final int EXPIRAION_DAY_END_INDEX = 10;

    private static final String PIC_X_PAD = " ";

    /**
     * {@code CARD-NUM PIC X(16)} - the {@code CARDDAT} base KSDS key, at offset {@value #CARD_NUM_OFFSET}.
     */
    public static final FieldSpan CARD_NUM =
            FieldSpan.alphanumeric("CARD-NUM", CARD_NUM_OFFSET, CARD_NUM_LENGTH);

    /**
     * {@code CARD-ACCT-ID PIC 9(11)} - the {@code CARDAIX} alternate-index key, at offset
     * {@value #CARD_ACCT_ID_OFFSET}.
     */
    public static final FieldSpan CARD_ACCT_ID =
            FieldSpan.unsignedNumeric("CARD-ACCT-ID", CARD_ACCT_ID_OFFSET, CARD_ACCT_ID_LENGTH);

    /**
     * {@code CARD-CVV-CD PIC 9(03)} at offset {@value #CARD_CVV_CD_OFFSET}.
     */
    public static final FieldSpan CARD_CVV_CD =
            FieldSpan.unsignedNumeric("CARD-CVV-CD", CARD_CVV_CD_OFFSET, CARD_CVV_CD_LENGTH);

    /**
     * {@code CARD-EMBOSSED-NAME PIC X(50)} at offset {@value #CARD_EMBOSSED_NAME_OFFSET}.
     */
    public static final FieldSpan CARD_EMBOSSED_NAME = FieldSpan.alphanumeric(
            "CARD-EMBOSSED-NAME", CARD_EMBOSSED_NAME_OFFSET, CARD_EMBOSSED_NAME_LENGTH);

    /**
     * {@code CARD-EXPIRAION-DATE PIC X(10)} at offset {@value #CARD_EXPIRAION_DATE_OFFSET}, carrying the
     * copybook's misspelled name verbatim: the parity differ keys on {@link FieldSpan#name()}, so this
     * string is part of the contract.
     */
    public static final FieldSpan CARD_EXPIRAION_DATE = FieldSpan.alphanumeric(
            "CARD-EXPIRAION-DATE", CARD_EXPIRAION_DATE_OFFSET, CARD_EXPIRAION_DATE_LENGTH);

    /**
     * {@code CARD-ACTIVE-STATUS PIC X(01)} at offset {@value #CARD_ACTIVE_STATUS_OFFSET}.
     */
    public static final FieldSpan CARD_ACTIVE_STATUS = FieldSpan.alphanumeric(
            "CARD-ACTIVE-STATUS", CARD_ACTIVE_STATUS_OFFSET, CARD_ACTIVE_STATUS_LENGTH);

    /**
     * {@code FILLER PIC X(59)} at offset {@value #FILLER_OFFSET} - reserved, unreferenceable from COBOL,
     * and nonetheless emitted on every encode.
     */
    public static final FieldSpan FILLER = FieldSpan.filler(FILLER_OFFSET, FILLER_LENGTH);

    public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
            CARD_NUM,
            CARD_ACCT_ID,
            CARD_CVV_CD,
            CARD_EMBOSSED_NAME,
            CARD_EXPIRAION_DATE,
            CARD_ACTIVE_STATUS,
            FILLER);

    /**
     * Normalises and checks every component, so an instance can never exist in a shape the copybook cannot
     * hold.
     */
    public CardRecord {
        cardNum = alignedAlphanumeric(cardNum, CARD_NUM);
        requireUnsigned(cardAcctId, CARD_ACCT_ID, CARD_ACCT_ID_EXCLUSIVE_LIMIT);
        requireUnsigned(cardCvvCd, CARD_CVV_CD, CARD_CVV_CD_EXCLUSIVE_LIMIT);
        cardEmbossedName = alignedAlphanumeric(cardEmbossedName, CARD_EMBOSSED_NAME);
        cardExpiraionDate = alignedAlphanumeric(cardExpiraionDate, CARD_EXPIRAION_DATE);
        cardActiveStatus = alignedAlphanumeric(cardActiveStatus, CARD_ACTIVE_STATUS);
    }

    private static String alignedAlphanumeric(String value, FieldSpan field) {
        Objects.requireNonNull(value, "A value is required for " + field.describe()
                + "; move an empty string to blank the field, as COBOL MOVE SPACES does");
        int width = field.length();
        if (value.length() > width) {
            throw new IllegalArgumentException("Value of " + value.length() + " character(s) does "
                    + "not fit " + field.describe() + " declared by app/cpy/CVACT02Y.cpy. This "
                    + "constructor never truncates, because COBOL truncates a PIC X receiver on the "
                    + "right and a PIC 9 receiver on the left, so the direction has to be chosen "
                    + "deliberately: use CardRecord.moving(...) for COBOL MOVE semantics");
        }
        if (value.length() == width) {
            return value;
        }
        return value + PIC_X_PAD.repeat(width - value.length());
    }

    private static void requireUnsigned(long value, FieldSpan field, long exclusiveLimit) {
        if (value < 0) {
            throw new IllegalArgumentException("Cannot store " + value + " in " + field.describe()
                    + ": app/cpy/CVACT02Y.cpy declares it PIC 9, an unsigned picture with no sign "
                    + "position, so a negative value has no representation in it");
        }
        if (value >= exclusiveLimit) {
            throw new IllegalArgumentException("Cannot store " + value + " in " + field.describe()
                    + ": app/cpy/CVACT02Y.cpy declares only " + field.length() + " digit(s), so the "
                    + "value must be below " + exclusiveLimit + ". COBOL would keep the low-order "
                    + "digits; use CardRecord.moving(...) if that truncation is what you intend");
        }
    }

    /**
     * Decodes a stored {@value #RECORD_LENGTH}-byte {@code CARD-RECORD}.
     *
     * @param record the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param charset the code page of those bytes, named explicitly by the caller - {@code IBM037} for an
     *     EBCDIC dataset, {@code US-ASCII} for a text fixture
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH} bytes, if
     *     {@code charset} is not single-byte for the digits and the space, or if a numeric span does not hold
     *     digits
     */
    public static CardRecord decode(byte[] record, Charset charset) {
        return decode(record, new FixedWidthCodec(charset));
    }

    /**
     * Decodes a stored {@value #RECORD_LENGTH}-byte {@code CARD-RECORD} with a codec the caller already
     * holds, which is the form a repository uses so the code page is validated once per component rather
     * than once per row.
     *
     * @param record the stored bytes; exactly {@value #RECORD_LENGTH} of them
     * @param codec the codec carrying the code page of those bytes
     * @return the decoded record
     * @throws NullPointerException if {@code record} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not exactly {@value #RECORD_LENGTH} bytes, or a
     *     numeric span does not hold digits
     */
    public static CardRecord decode(byte[] record, FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec carrying the record's code page is required to "
                + "decode a CARD-RECORD; the platform default is never assumed");
        return decode(codec.wrap(record, LAYOUT), codec);
    }

    /**
     * Decodes a {@code CARD-RECORD} from a record area the caller already holds - for instance one lifted
     * out of a larger buffer, or one being inspected span by span.
     *
     * @param area the record area, whose width must match {@link #LAYOUT}
     * @param codec the codec carrying the area's code page
     * @return the decoded record
     * @throws NullPointerException if {@code area} or {@code codec} is {@code null}
     * @throws IllegalArgumentException if a numeric span does not hold digits
     * @throws IndexOutOfBoundsException if {@code area} is narrower than {@value #RECORD_LENGTH} bytes
     */
    public static CardRecord decode(FixedWidthRecord area, FixedWidthCodec codec) {
        Objects.requireNonNull(area, "A record area is required to decode a CARD-RECORD");
        Objects.requireNonNull(codec, "A codec carrying the area's code page is required to decode "
                + "a CARD-RECORD");
        return new CardRecord(
                codec.readPicX(area, CARD_NUM),
                readUnsignedLong(area, codec, CARD_ACCT_ID),
                readUnsignedInt(area, codec, CARD_CVV_CD),
                codec.readPicX(area, CARD_EMBOSSED_NAME),
                codec.readPicX(area, CARD_EXPIRAION_DATE),
                codec.readPicX(area, CARD_ACTIVE_STATUS));
    }

    /**
     * Decodes a {@code CARD-RECORD} from a {@value #RECORD_LENGTH}-character row image, which is the shape
     * a fixed-width text fixture row and a fixed-width {@code CHAR} column both arrive in.
     *
     * @param recordImage the row image; exactly {@value #RECORD_LENGTH} characters under a single-byte code
     *     page
     * @param charset the code page to encode the image with, named explicitly by the caller
     * @return the decoded record
     * @throws NullPointerException if {@code recordImage} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if the image does not encode to exactly {@value #RECORD_LENGTH}
     *     bytes, or a numeric span does not hold digits
     */
    public static CardRecord decodeImage(String recordImage, Charset charset) {
        Objects.requireNonNull(recordImage, "A record image is required to decode a CARD-RECORD");
        Objects.requireNonNull(charset, "A charset is required to encode a CARD-RECORD image into "
                + "the bytes it stands for; the platform default is never assumed");
        return decode(FixedWidthRecord.encodeText(recordImage, charset, "a CARD-RECORD image"),
                charset);
    }

    private static long readUnsignedLong(FixedWidthRecord area, FixedWidthCodec codec,
                                         FieldSpan field) {
        try {
            return codec.readPic9(area, field);
        } catch (IllegalArgumentException notNumeric) {
            throw numericSpanFailure(area, field, notNumeric);
        }
    }

    private static int readUnsignedInt(FixedWidthRecord area, FixedWidthCodec codec,
                                       FieldSpan field) {
        try {
            return codec.readPic9AsInt(area, field);
        } catch (IllegalArgumentException notNumeric) {
            throw numericSpanFailure(area, field, notNumeric);
        }
    }

    private static IllegalArgumentException numericSpanFailure(FixedWidthRecord area,
                                                               FieldSpan field,
                                                               IllegalArgumentException cause) {
        return new IllegalArgumentException("Span " + field.describe() + " of a " + RECORD_LENGTH
                + "-byte CARD-RECORD reads '" + area.readSpan(field) + "', which is not the "
                + "unsigned digits app/cpy/CVACT02Y.cpy declares there. Either the row is "
                + "misaligned - every row of app/data/ASCII/carddata.txt is exactly " + RECORD_LENGTH
                + " bytes - or the code page is wrong for this data", cause);
    }

    /**
     * Builds a record by applying full COBOL {@code MOVE} semantics to every field, including truncation.
     *
     * @param cardNum the sending value for {@code CARD-NUM}
     * @param cardAcctId the sending value for {@code CARD-ACCT-ID}; must not be negative
     * @param cardCvvCd the sending value for {@code CARD-CVV-CD}; must not be negative
     * @param cardEmbossedName the sending value for {@code CARD-EMBOSSED-NAME}
     * @param cardExpiraionDate the sending value for {@code CARD-EXPIRAION-DATE}
     * @param cardActiveStatus the sending value for {@code CARD-ACTIVE-STATUS}
     * @param codec the codec supplying the move helpers
     * @return the record, with every field at its declared width
     * @throws NullPointerException if {@code codec} or any character argument is {@code null}
     * @throws IllegalArgumentException if a numeric argument is negative
     */
    public static CardRecord moving(String cardNum,
                                    long cardAcctId,
                                    int cardCvvCd,
                                    String cardEmbossedName,
                                    String cardExpiraionDate,
                                    String cardActiveStatus,
                                    FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to apply COBOL MOVE semantics; it owns "
                + "the pad and truncate rules for PIC X and PIC 9 so no call site re-implements them");
        return new CardRecord(
                codec.movePicX(cardNum, CARD_NUM_LENGTH),
                codec.decodePic9(codec.movePic9(cardAcctId, CARD_ACCT_ID_LENGTH)),
                codec.decodePic9AsInt(codec.movePic9(cardCvvCd, CARD_CVV_CD_LENGTH)),
                codec.movePicX(cardEmbossedName, CARD_EMBOSSED_NAME_LENGTH),
                codec.movePicX(cardExpiraionDate, CARD_EXPIRAION_DATE_LENGTH),
                codec.movePicX(cardActiveStatus, CARD_ACTIVE_STATUS_LENGTH));
    }

    /**
     * The COBOL {@code INITIALIZE CARD-RECORD} equivalent: every {@code PIC X} field all spaces and every
     * {@code PIC 9} field zero.
     *
     * @return an initialised record: sixteen spaces, account id 0, CVV 0, fifty spaces, ten spaces and one
     *     space
     */
    public static CardRecord initialised() {
        return new CardRecord("", 0L, 0, "", "", "");
    }

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image, the trailing {@code FILLER}
     * included.
     *
     * @param charset the code page to write in, named explicitly by the caller
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and the space
     */
    public byte[] encode(Charset charset) {
        return encode(new FixedWidthCodec(charset));
    }

    /**
     * Serialises this record to its full {@value #RECORD_LENGTH}-byte image using a codec the caller
     * already holds.
     *
     * @param codec the codec carrying the code page to write in
     * @return exactly {@value #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] encode(FixedWidthCodec codec) {
        return toFixedWidthRecord(codec).toByteArray();
    }

    /**
     * Serialises this record to its {@value #RECORD_LENGTH}-character row image - the form a fixed-width
     * text row, a fixed-width {@code CHAR} column and {@code CBACT02C}'s {@code DISPLAY CARD-RECORD} line
     * all take.
     *
     * @param charset the code page to write in, named explicitly by the caller
     * @return the row image; exactly {@value #RECORD_LENGTH} characters under a single-byte code page
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and the space
     */
    public String encodeToImage(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render a CARD-RECORD image; the "
                + "platform default is never assumed");
        return FixedWidthRecord.decodeText(encode(charset), charset, "a CARD-RECORD image");
    }

    /**
     * Writes this record into a freshly allocated record area over {@link #LAYOUT}.
     *
     * @param codec the codec carrying the code page to write in
     * @return a record area of exactly {@value #RECORD_LENGTH} bytes holding this record
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec carrying the target code page is required to "
                + "serialise a CARD-RECORD; the platform default is never assumed");
        FixedWidthRecord area = codec.newRecord(LAYOUT);
        codec.writePicX(area, CARD_NUM, cardNum);
        codec.writePic9(area, CARD_ACCT_ID, cardAcctId);
        codec.writePic9(area, CARD_CVV_CD, cardCvvCd);
        codec.writePicX(area, CARD_EMBOSSED_NAME, cardEmbossedName);
        codec.writePicX(area, CARD_EXPIRAION_DATE, cardExpiraionDate);
        codec.writePicX(area, CARD_ACTIVE_STATUS, cardActiveStatus);
        area.fill(FILLER_OFFSET, FILLER_LENGTH, area.spacePadByte());
        return area;
    }

    // COBOL slices it with 1-based reference modification; these are the 0-based translations, and they are
    // views over the stored X(10) span, never separate state.

    /**
     * The year of the expiry span: {@code CARD-EXPIRAION-DATE(1:4)} in COBOL, characters {@code [0, 4)}
     * here.
     *
     * @return four characters
     */
    public String cardExpiraionDateYear() {
        return cardExpiraionDate.substring(EXPIRAION_YEAR_BEGIN_INDEX, EXPIRAION_YEAR_END_INDEX);
    }

    /**
     * The month of the expiry span: {@code CARD-EXPIRAION-DATE(6:2)} in COBOL, characters {@code [5, 7)}
     * here - {@code "03"} for the first fixture row. 0-based index 4 is the {@code '-'} separator and is
     * skipped; it is a {@code '-'} on all fifty fixture rows.
     *
     * @return two characters
     */
    public String cardExpiraionDateMonth() {
        return cardExpiraionDate.substring(EXPIRAION_MONTH_BEGIN_INDEX, EXPIRAION_MONTH_END_INDEX);
    }

    /**
     * The day of the expiry span: {@code CARD-EXPIRAION-DATE(9:2)} in COBOL, characters {@code [8, 10)}
     * here - {@code "09"} for the first fixture row. 0-based index 7 is the second {@code '-'} separator
     * and is skipped.
     *
     * @return two characters
     */
    public String cardExpiraionDateDay() {
        return cardExpiraionDate.substring(EXPIRAION_DAY_BEGIN_INDEX, EXPIRAION_DAY_END_INDEX);
    }

    /**
     * The {@code CARDAIX} key image: {@link #cardAcctId()} as {@value #CARD_ACCT_ID_LENGTH} zoned digits,
     * zero-filled on the left - account id {@code 50} renders {@code "00000000050"}, exactly as the fixture
     * stores it.
     *
     * <p>Produced by the codec's {@code PIC 9} move helper rather than a format string, so the zero-fill
     * and truncation rules stay in one place.
     *
     * @param codec the codec supplying the move helper
     * @return exactly {@value #CARD_ACCT_ID_LENGTH} digits
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String cardAcctIdImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render the CARD-ACCT-ID key image");
        return codec.movePic9(cardAcctId, CARD_ACCT_ID_LENGTH);
    }

    /**
     * {@link #cardCvvCd()} as {@value #CARD_CVV_CD_LENGTH} zoned digits, zero-filled on the left - CVV
     * {@code 747} renders {@code "747"}.
     *
     * @param codec the codec supplying the move helper
     * @return exactly {@value #CARD_CVV_CD_LENGTH} digits
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String cardCvvCdImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render the CARD-CVV-CD image");
        return codec.movePic9(cardCvvCd, CARD_CVV_CD_LENGTH);
    }

    /**
     * The descriptor of the {@code CARDDAT} base KSDS key: {@code CARD-NUM}, sixteen characters at offset
     * {@value #CARD_NUM_OFFSET}.
     *
     * @return the {@link #CARD_NUM} descriptor
     */
    public static FieldSpan cardDatPrimaryKeySpan() {
        return CARD_NUM;
    }

    /**
     * The descriptor of the {@code CARDAIX} alternate-index key: {@code CARD-ACCT-ID}, eleven digits at
     * offset {@value #CARD_ACCT_ID_OFFSET}.
     *
     * @return the {@link #CARD_ACCT_ID} descriptor
     */
    public static FieldSpan cardAixAlternateKeySpan() {
        return CARD_ACCT_ID;
    }

    /**
     * A copy of this record with {@code CARD-NUM} replaced.
     *
     * @param replacement the new value, no wider than {@value #CARD_NUM_LENGTH} characters
     * @return a new record; this one is unchanged
     * @throws NullPointerException if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardNum(String replacement) {
        return new CardRecord(replacement, cardAcctId, cardCvvCd, cardEmbossedName,
                cardExpiraionDate, cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-ACCT-ID} replaced.
     *
     * @param replacement the new value; not negative and below {@value #CARD_ACCT_ID_EXCLUSIVE_LIMIT}
     * @return a new record; this one is unchanged
     * @throws IllegalArgumentException if {@code replacement} is out of range
     */
    public CardRecord withCardAcctId(long replacement) {
        return new CardRecord(cardNum, replacement, cardCvvCd, cardEmbossedName, cardExpiraionDate,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-CVV-CD} replaced.
     *
     * @param replacement the new value; not negative and below {@value #CARD_CVV_CD_EXCLUSIVE_LIMIT}
     * @return a new record; this one is unchanged
     * @throws IllegalArgumentException if {@code replacement} is out of range
     */
    public CardRecord withCardCvvCd(int replacement) {
        return new CardRecord(cardNum, cardAcctId, replacement, cardEmbossedName, cardExpiraionDate,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-EMBOSSED-NAME} replaced.
     *
     * @param replacement the new value, no wider than {@value #CARD_EMBOSSED_NAME_LENGTH} characters
     * @return a new record; this one is unchanged
     * @throws NullPointerException if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardEmbossedName(String replacement) {
        return new CardRecord(cardNum, cardAcctId, cardCvvCd, replacement, cardExpiraionDate,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-EXPIRAION-DATE} replaced - the misspelling is the copybook's
     * and is intentional.
     *
     * @param replacement the new value, no wider than {@value #CARD_EXPIRAION_DATE_LENGTH} characters
     * @return a new record; this one is unchanged
     * @throws NullPointerException if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardExpiraionDate(String replacement) {
        return new CardRecord(cardNum, cardAcctId, cardCvvCd, cardEmbossedName, replacement,
                cardActiveStatus);
    }

    /**
     * A copy of this record with {@code CARD-ACTIVE-STATUS} replaced.
     *
     * @param replacement the new value, no wider than {@value #CARD_ACTIVE_STATUS_LENGTH} character
     * @return a new record; this one is unchanged
     * @throws NullPointerException if {@code replacement} is {@code null}
     * @throws IllegalArgumentException if {@code replacement} is too wide
     */
    public CardRecord withCardActiveStatus(String replacement) {
        return new CardRecord(cardNum, cardAcctId, cardCvvCd, cardEmbossedName, cardExpiraionDate,
                replacement);
    }

    /**
     * A diagnostic rendering that names each field as its copybook spells it and withholds the cardholder
     * data, per {@link SensitiveDiagnostics}.
     *
     * @return a rendering safe to log, naming all six fields and the reserved span
     */
    @Override
    public String toString() {
        return "CARD-RECORD[" + RECORD_LENGTH + " bytes]{"
                + "CARD-NUM='" + SensitiveDiagnostics.maskPan(cardNum)
                + "', CARD-ACCT-ID="
                + SensitiveDiagnostics.maskIdentifier(cardAcctId, CARD_ACCT_ID_LENGTH)
                + ", CARD-CVV-CD=" + SensitiveDiagnostics.redacted()
                + ", CARD-EMBOSSED-NAME='" + SensitiveDiagnostics.describeText(cardEmbossedName)
                + "', CARD-EXPIRAION-DATE='" + SensitiveDiagnostics.plain(cardExpiraionDate)
                + "', CARD-ACTIVE-STATUS='" + SensitiveDiagnostics.plain(cardActiveStatus)
                + "', FILLER=" + FILLER_LENGTH + " space(s)}";
    }

}
