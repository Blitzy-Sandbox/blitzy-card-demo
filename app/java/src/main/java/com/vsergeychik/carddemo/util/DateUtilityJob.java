package com.vsergeychik.carddemo.util;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The Java translation of {@code app/cbl/CSUTLDTC.cbl} - the CardDemo date-validation subprogram that wraps
 * the IBM Language Environment service {@code CEEDAYS}.
 *
 * <p>{@code WS-MESSAGE} (L42-L57) is a positional record whose declared spans sum to exactly 80 bytes.
 *
 * <p>Five runtime calls reach it, and they do not agree on what they accept. The four direct program calls -
 * {@code app/cbl/CORPT00C.cbl:392} and {@code :412}, {@code app/cbl/COTRN02C.cbl:393} and {@code :413} -
 * tolerate message {@code 2513} in spite of its severity 3, each guarding the result with
 * {@code IF CSUTLDTC-RESULT-MSG-NUM NOT = '2513'}. The fifth call, {@code app/cpy/CSUTLDPY.cpy:293} reached
 * through {@code AccountDateValidator}, tests {@code IF WS-SEVERITY-N = 0} and therefore rejects every
 * non-zero severity, {@code 2513} included. The severity and the message number both have to survive this
 * translation unchanged for either rule to hold.
 */
@Service
public class DateUtilityJob {
    /**
     * {@code 01 LS-DATE PIC X(10)} - L84.
     */
    public static final int LS_DATE_LENGTH = 10;

    /**
     * {@code 01 LS-DATE-FORMAT PIC X(10)} - L85.
     */
    public static final int LS_DATE_FORMAT_LENGTH = 10;

    /**
     * {@code 01 LS-RESULT PIC X(80)} - L86, and the declared width of {@code WS-MESSAGE}.
     */
    public static final int LS_RESULT_LENGTH = 80;

    private static final int WS_SEVERITY_OFFSET = 0;

    private static final int SEVERITY_AND_MSG_NO_LENGTH = 4;

    private static final int FILLER_MESG_CODE_OFFSET = 4;

    private static final int FILLER_MESG_CODE_LENGTH = 11;

    private static final int WS_MSG_NO_OFFSET = 15;

    private static final int FILLER_AFTER_MSG_NO_OFFSET = 19;

    private static final int WS_RESULT_OFFSET = 20;

    private static final int WS_RESULT_LENGTH = 15;

    private static final int FILLER_AFTER_RESULT_OFFSET = 35;

    private static final int FILLER_TST_DATE_OFFSET = 36;

    private static final int FILLER_TST_DATE_LENGTH = 9;

    private static final int WS_DATE_OFFSET = 45;

    private static final int FILLER_AFTER_DATE_OFFSET = 55;

    private static final int FILLER_MASK_USED_OFFSET = 56;

    private static final int FILLER_MASK_USED_LENGTH = 10;

    private static final int WS_DATE_FMT_OFFSET = 66;

    private static final int FILLER_AFTER_FMT_OFFSET = 76;

    private static final int FILLER_TRAILING_OFFSET = 77;

    private static final int FILLER_TRAILING_LENGTH = 3;

    private static final int SINGLE_BYTE_FILLER_LENGTH = 1;

    private static final String LITERAL_MESG_CODE = "Mesg Code:";

    private static final String LITERAL_TST_DATE = "TstDate:";

    private static final String LITERAL_MASK_USED = "Mask used:";

    private static final String ONE_SPACE = " ";

    private static final String THREE_SPACES = "   ";

    private static final String TEN_SPACES = "          ";

    private static final FieldSpan WS_SEVERITY = FieldSpan.alphanumeric(
            "WS-SEVERITY", WS_SEVERITY_OFFSET, SEVERITY_AND_MSG_NO_LENGTH);

    private static final FieldSpan WS_SEVERITY_N =
            WS_SEVERITY.redefinedAs("WS-SEVERITY-N", PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan FILLER_MESG_CODE = FieldSpan.filler(
            FILLER_MESG_CODE_OFFSET, FILLER_MESG_CODE_LENGTH, LITERAL_MESG_CODE);

    private static final FieldSpan WS_MSG_NO = FieldSpan.alphanumeric(
            "WS-MSG-NO", WS_MSG_NO_OFFSET, SEVERITY_AND_MSG_NO_LENGTH);

    private static final FieldSpan WS_MSG_NO_N =
            WS_MSG_NO.redefinedAs("WS-MSG-NO-N", PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan FILLER_AFTER_MSG_NO = FieldSpan.filler(
            FILLER_AFTER_MSG_NO_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    private static final FieldSpan WS_RESULT = FieldSpan.alphanumeric(
            "WS-RESULT", WS_RESULT_OFFSET, WS_RESULT_LENGTH);

    private static final FieldSpan FILLER_AFTER_RESULT = FieldSpan.filler(
            FILLER_AFTER_RESULT_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    private static final FieldSpan FILLER_TST_DATE = FieldSpan.filler(
            FILLER_TST_DATE_OFFSET, FILLER_TST_DATE_LENGTH, LITERAL_TST_DATE);

    private static final FieldSpan WS_DATE = FieldSpan
            .alphanumeric("WS-DATE", WS_DATE_OFFSET, LS_DATE_LENGTH)
            .withInitialValue(TEN_SPACES);

    private static final FieldSpan FILLER_AFTER_DATE = FieldSpan.filler(
            FILLER_AFTER_DATE_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    private static final FieldSpan FILLER_MASK_USED = FieldSpan.filler(
            FILLER_MASK_USED_OFFSET, FILLER_MASK_USED_LENGTH, LITERAL_MASK_USED);

    private static final FieldSpan WS_DATE_FMT = FieldSpan.alphanumeric(
            "WS-DATE-FMT", WS_DATE_FMT_OFFSET, LS_DATE_FORMAT_LENGTH);

    private static final FieldSpan FILLER_AFTER_FMT = FieldSpan.filler(
            FILLER_AFTER_FMT_OFFSET, SINGLE_BYTE_FILLER_LENGTH, ONE_SPACE);

    private static final FieldSpan FILLER_TRAILING = FieldSpan.filler(
            FILLER_TRAILING_OFFSET, FILLER_TRAILING_LENGTH, THREE_SPACES);

    private static final RecordLayout WS_MESSAGE_LAYOUT = RecordLayout.of(LS_RESULT_LENGTH,
            WS_SEVERITY,
            WS_SEVERITY_N,
            FILLER_MESG_CODE,
            WS_MSG_NO,
            WS_MSG_NO_N,
            FILLER_AFTER_MSG_NO,
            WS_RESULT,
            FILLER_AFTER_RESULT,
            FILLER_TST_DATE,
            WS_DATE,
            FILLER_AFTER_DATE,
            FILLER_MASK_USED,
            WS_DATE_FMT,
            FILLER_AFTER_FMT,
            FILLER_TRAILING);

    // Eight of the ten literals already carry their own significant trailing spaces inside the quotes; only
    // the first two are shorter than fifteen and are padded on the right by the codec's PIC X move, exactly
    // as a COBOL MOVE to a PIC X(15) receiver pads.

    private static final String RESULT_DATE_IS_VALID = "Date is valid";

    private static final String RESULT_INSUFFICIENT = "Insufficient";

    private static final String RESULT_DATEVALUE_ERROR = "Datevalue error";

    private static final String RESULT_INVALID_ERA = "Invalid Era    ";

    private static final String RESULT_UNSUPP_RANGE = "Unsupp. Range  ";

    private static final String RESULT_INVALID_MONTH = "Invalid month  ";

    private static final String RESULT_BAD_PIC_STRING = "Bad Pic String ";

    private static final String RESULT_NONNUMERIC_DATA = "Nonnumeric data";

    private static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    private static final String RESULT_DATE_IS_INVALID = "Date is invalid";

    private static final int SEVERITY_SUCCESS = 0;

    private static final int SEVERITY_SEVERE = 3;

    private static final int MESSAGE_NUMBER_NONE = 0;

    private static final int LILLIAN_NOT_CALCULATED = 0;

    /**
     * The nine {@code 88}-level feedback tokens declared on {@code FEEDBACK-TOKEN-VALUE} at
     * {@code app/cbl/CSUTLDTC.cbl} L62-L70, plus one sentinel for the {@code WHEN OTHER} path.
     *
     * <p>The COBOL names are kept exactly as declared, including the inverted {@link #FC_INVALID_DATE},
     * whose all-zeros value is the successful outcome.
     */
    enum FeedbackToken {
        FC_INVALID_DATE(SEVERITY_SUCCESS, MESSAGE_NUMBER_NONE),

        FC_INSUFFICIENT_DATA(SEVERITY_SEVERE, 2507),

        FC_BAD_DATE_VALUE(SEVERITY_SEVERE, 2508),

        FC_INVALID_ERA(SEVERITY_SEVERE, 2509),

        FC_UNSUPP_RANGE(SEVERITY_SEVERE, 2513),

        FC_INVALID_MONTH(SEVERITY_SEVERE, 2517),

        FC_BAD_PIC_STRING(SEVERITY_SEVERE, 2518),

        FC_NON_NUMERIC_DATA(SEVERITY_SEVERE, 2520),

        FC_YEAR_IN_ERA_ZERO(SEVERITY_SEVERE, 2521),

        UNENUMERATED(SEVERITY_SEVERE, MESSAGE_NUMBER_NONE);

        private final int severity;

        private final int messageNumber;

        FeedbackToken(int severity, int messageNumber) {
            this.severity = severity;
            this.messageNumber = messageNumber;
        }

        int severity() {
            return severity;
        }

        int messageNumber() {
            return messageNumber;
        }
    }

    /**
     * What the {@code CEEDAYS} substitute produces: the feedback token, and the Lillian day count it would
     * have written into {@code OUTPUT-LILLIAN}.
     *
     * <p>{@code 01 OUTPUT-LILLIAN PIC S9(9) USAGE IS BINARY} (L41) is pre-set to zero at L114, filled by
     * the call at L116-L120, and then never read again by {@code CSUTLDTC}: it is not moved into
     * {@code WS-MESSAGE}, not returned through {@code LS-RESULT} and not exposed to any caller.
     *
     * @param feedbackCode the {@code FEEDBACK-CODE} the call returned, which drives L123, L124 and the
     *     {@code EVALUATE} at L128-L149
     * @param outputLillian the Lillian day count, or {@link #LILLIAN_NOT_CALCULATED} when the conversion
     *     failed
     */
    private record CeedaysOutcome(FeedbackToken feedbackCode, int outputLillian) {
        static CeedaysOutcome accepted(int outputLillian) {
            return new CeedaysOutcome(FeedbackToken.FC_INVALID_DATE, outputLillian);
        }

        static CeedaysOutcome rejected(FeedbackToken feedbackCode) {
            return new CeedaysOutcome(feedbackCode, LILLIAN_NOT_CALCULATED);
        }
    }

    private enum PictureRole {
        YEAR,

        MONTH,

        DAY_OF_MONTH,

        JULIAN_DAY,

        ERA,

        NONE
    }

    /**
     * The picture tokens this substitute recognises, and the width each one consumes.
     */
    private enum PictureItemKind {
        YEAR_4(4, 4, PictureRole.YEAR),

        YEAR_2(2, 2, PictureRole.YEAR),

        MONTH_NAME_3(3, 3, PictureRole.MONTH),

        MONTH_2(2, 2, PictureRole.MONTH),

        JULIAN_DAY_3(3, 3, PictureRole.JULIAN_DAY),

        DAY_2(2, 2, PictureRole.DAY_OF_MONTH),

        ERA(0, 2, PictureRole.ERA),

        LITERAL(1, 1, PictureRole.NONE);

        private final int pictureTokenLength;

        private final int inputWidth;

        private final PictureRole role;

        PictureItemKind(int pictureTokenLength, int inputWidth, PictureRole role) {
            this.pictureTokenLength = pictureTokenLength;
            this.inputWidth = inputWidth;
            this.role = role;
        }

        int pictureTokenLength() {
            return pictureTokenLength;
        }

        int inputWidth() {
            return inputWidth;
        }

        PictureRole role() {
            return role;
        }
    }

    private record PictureItem(PictureItemKind kind, char literal) {
        private static final char NOT_A_LITERAL = '\0';

        static PictureItem field(PictureItemKind kind) {
            return new PictureItem(kind, NOT_A_LITERAL);
        }

        static PictureItem literal(char literal) {
            return new PictureItem(PictureItemKind.LITERAL, literal);
        }
    }

    /**
     * A parsed picture string, or the reason it was refused.
     *
     * @param items the elements in picture order, empty when the picture was refused
     * @param inputWidth the total number of input-date characters the elements consume
     * @param julianDay {@code true} when the picture locates the day of the year rather than the month and
     *     day of the month
     * @param rejection the feedback token to report, or {@code null} when the picture is usable
     */
    private record ParsedPicture(List<PictureItem> items,
                                 int inputWidth,
                                 boolean julianDay,
                                 FeedbackToken rejection) {
        private static final int NO_INPUT_CONSUMED = 0;

        static ParsedPicture rejected(FeedbackToken rejection) {
            return new ParsedPicture(List.of(), NO_INPUT_CONSUMED, false, rejection);
        }

        static ParsedPicture accepted(List<PictureItem> items, int inputWidth, boolean julianDay) {
            return new ParsedPicture(List.copyOf(items), inputWidth, julianDay, null);
        }
    }

    private static final String TOKEN_YEAR_4 = "YYYY";

    private static final String TOKEN_YEAR_2 = "YY";

    private static final String TOKEN_MONTH_NAME = "MMM";

    private static final String TOKEN_MONTH = "MM";

    private static final String TOKEN_JULIAN_DAY = "DDD";

    private static final String TOKEN_DAY = "DD";

    private static final char ERA_FIELD_OPEN = '<';

    private static final char ERA_FIELD_CLOSE = '>';

    private static final String ERA_COMMON = "AD";

    private static final String ERA_BEFORE_COMMON = "BC";

    private static final List<String> MONTH_ABBREVIATIONS = List.of(
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    private static final List<String> ERA_NAMES = List.of(ERA_COMMON, ERA_BEFORE_COMMON);

    private static final int NOT_FOUND = -1;

    private static final int TWO_DIGIT_YEAR_WINDOW_PIVOT = 49;

    private static final int TWENTY_FIRST_CENTURY_BASE = 2000;

    private static final int TWENTIETH_CENTURY_BASE = 1900;

    private static final int YEAR_WITHIN_ERA_ZERO = 0;

    private static final int FIRST_MONTH = 1;

    private static final int LAST_MONTH = 12;

    private static final int JANUARY = 1;

    private static final int FEBRUARY = 2;

    private static final int APRIL = 4;

    private static final int JUNE = 6;

    private static final int SEPTEMBER = 9;

    private static final int NOVEMBER = 11;

    private static final int FIRST_DAY_OF_MONTH = 1;

    private static final int FIRST_DAY_OF_YEAR = 1;

    private static final int DAYS_IN_LONG_MONTH = 31;

    private static final int DAYS_IN_SHORT_MONTH = 30;

    private static final int DAYS_IN_LEAP_FEBRUARY = 29;

    private static final int DAYS_IN_COMMON_FEBRUARY = 28;

    private static final int DAYS_IN_LEAP_YEAR = 366;

    private static final int DAYS_IN_COMMON_YEAR = 365;

    private static final int LEAP_YEAR_DIVISOR = 4;

    private static final int CENTURY_DIVISOR = 100;

    private static final int LEAP_CYCLE_DIVISOR = 400;

    private static final int MARCH_ALIGNED_MONTH_SHIFT = 9;

    private static final int MONTHS_IN_YEAR = 12;

    private static final int SHIFTED_YEAR_ROLLOVER_DIVISOR = 10;

    private static final int MONTH_LENGTH_NUMERATOR = 306;

    private static final int MONTH_LENGTH_BIAS = 5;

    private static final int MONTH_LENGTH_DENOMINATOR = 10;

    private static final int LILLIAN_EPOCH_EVE_YEAR = 1582;

    private static final int LILLIAN_EPOCH_EVE_MONTH = 10;

    private static final int LILLIAN_EPOCH_EVE_DAY = 14;

    private static final int FIRST_LILLIAN_DAY = 1;

    private static final int LILLIAN_EPOCH_EVE_DAY_NUMBER = gregorianDayNumber(
            LILLIAN_EPOCH_EVE_YEAR, LILLIAN_EPOCH_EVE_MONTH, LILLIAN_EPOCH_EVE_DAY);

    private static final char DIGIT_ZERO = '0';

    private static final char DIGIT_NINE = '9';

    private static final char SPACE_CHARACTER = ' ';

    private static final char UPPER_CASE_A = 'A';

    private static final char UPPER_CASE_Z = 'Z';

    private static final char LOWER_CASE_A = 'a';

    private static final char LOWER_CASE_Z = 'z';

    private static final int DECIMAL_RADIX = 10;

    private static final int VSTRING_LENGTH_BYTES = 2;

    private static final int GROUP_MOVE_SURVIVING_TEXT_BYTES = LS_DATE_LENGTH - VSTRING_LENGTH_BYTES;

    private static final int BYTE_MASK = 0xFF;

    /**
     * The code page the 80-byte result is rendered in when no other is supplied.
     *
     * <p>{@code US-ASCII} is the code page of the ASCII fixtures this module is verified against, and it is
     * a single-byte encoding, which a fixed-width record area addressed by absolute offset requires.
     */
    public static final Charset DEFAULT_MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    private final FixedWidthCodec codec;

    /**
     * Creates the service against {@link #DEFAULT_MESSAGE_CHARSET}.
     */
    public DateUtilityJob() {
        this(DEFAULT_MESSAGE_CHARSET);
    }

    /**
     * Creates the service against an explicitly supplied code page, and is the constructor the Spring
     * container selects.
     *
     * @param messageCharset the code page the 80-byte result is rendered in
     * @throws IllegalArgumentException if the code page is not single-byte over that repertoire
     * @throws NullPointerException if {@code messageCharset} is {@code null}
     */
    @Autowired
    public DateUtilityJob(
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset messageCharset) {
        this.codec = new FixedWidthCodec(messageCharset);
    }

    /**
     * Validates a date against a picture string, exactly as {@code CSUTLDTC} does.
     *
     * @param lsDate {@code LS-DATE PIC X(10)} - the date to test
     * @param lsDateFormat {@code LS-DATE-FORMAT PIC X(10)} - the picture string describing where each field
     *     sits in {@code lsDate}
     * @return the 80-byte outcome: the four-character severity and message-number text the callers compare,
     *     the fifteen-character result text
     * @throws NullPointerException if either argument is {@code null}, which the fixed-width move reports
     *     rather than silently treating as blanks
     */
    public DateValidationResult validateDate(String lsDate, String lsDateFormat) {
        final FixedWidthRecord wsMessage = codec.newRecord(WS_MESSAGE_LAYOUT);
        codec.writePicX(wsMessage, WS_DATE, TEN_SPACES);

        a000Main(wsMessage, lsDate, lsDateFormat);

        return new DateValidationResult(
                codec.readPicX(wsMessage, WS_SEVERITY),
                codec.readPicX(wsMessage, WS_MSG_NO),
                codec.readPicX(wsMessage, WS_RESULT),
                codec.readPic9AsInt(wsMessage, WS_SEVERITY_N),
                wsMessage.readString(WS_SEVERITY_OFFSET, wsMessage.recordLength()),
                wsMessage.toByteArray(),
                codec.charset());
    }

    private void a000Main(FixedWidthRecord wsMessage, String lsDate, String lsDateFormat) {
        // LENGTH OF returns the DECLARED width of PIC X(10), so the varying-string length is always 10
        // whatever the content - which is precisely what makes the L122 defect deterministic.
        final String wsDateToTestText = codec.movePicX(lsDate, LS_DATE_LENGTH);
        codec.writePicX(wsMessage, WS_DATE, wsDateToTestText);

        // L111-L113 is again a multi-receiver MOVE, and WS-DATE-FMT is never corrupted afterwards.
        final String wsDateFormatText = codec.movePicX(lsDateFormat, LS_DATE_FORMAT_LENGTH);
        codec.writePicX(wsMessage, WS_DATE_FMT, wsDateFormatText);

        final CeedaysOutcome outcome = ceedays(wsDateToTestText, wsDateFormatText);
        final FeedbackToken feedbackCode = outcome.feedbackCode();

        // L122 MOVE WS-DATE-TO-TEST TO WS-DATE - the group move, reproduced deliberately.
        wsMessage.writeSpanBytes(WS_DATE, wsDateToTestGroupImage(wsDateToTestText));

        codec.writePic9(wsMessage, WS_SEVERITY_N, feedbackCode.severity());
        codec.writePic9(wsMessage, WS_MSG_NO_N, feedbackCode.messageNumber());

        final String wsResult = resultTextOfFeedbackToken(feedbackCode);
        codec.writePicX(wsMessage, WS_RESULT, wsResult);
    }

    static String resultTextOfFeedbackToken(FeedbackToken feedbackCode) {
        return switch (feedbackCode) {
            case FC_INVALID_DATE -> RESULT_DATE_IS_VALID;
            case FC_INSUFFICIENT_DATA -> RESULT_INSUFFICIENT;
            case FC_BAD_DATE_VALUE -> RESULT_DATEVALUE_ERROR;
            case FC_INVALID_ERA -> RESULT_INVALID_ERA;
            case FC_UNSUPP_RANGE -> RESULT_UNSUPP_RANGE;
            case FC_INVALID_MONTH -> RESULT_INVALID_MONTH;
            case FC_BAD_PIC_STRING -> RESULT_BAD_PIC_STRING;
            case FC_NON_NUMERIC_DATA -> RESULT_NONNUMERIC_DATA;
            case FC_YEAR_IN_ERA_ZERO -> RESULT_YEAR_IN_ERA_ZERO;
            default -> RESULT_DATE_IS_INVALID;
        };
    }

    private byte[] wsDateToTestGroupImage(String vstringText) {
        final byte[] groupImage = new byte[LS_DATE_LENGTH];
        groupImage[0] = (byte) ((LS_DATE_LENGTH >> Byte.SIZE) & BYTE_MASK);
        groupImage[1] = (byte) (LS_DATE_LENGTH & BYTE_MASK);
        System.arraycopy(codec.encodeImage(vstringText, "VSTRING-TEXT OF WS-DATE-TO-TEST"), 0,
                groupImage, VSTRING_LENGTH_BYTES, GROUP_MOVE_SURVIVING_TEXT_BYTES);
        return groupImage;
    }

    private static CeedaysOutcome ceedays(String inputCharDate, String pictureString) {
        final ParsedPicture picture = parsePictureString(pictureString);
        if (picture.rejection() != null) {
            return CeedaysOutcome.rejected(picture.rejection());
        }
        return scanInputDate(inputCharDate, picture);
    }

    private static ParsedPicture parsePictureString(String pictureString) {
        final List<PictureItem> items = new ArrayList<>();
        final boolean[] declaredRoles = new boolean[PictureRole.values().length];
        int inputWidth = 0;
        int cursor = 0;
        while (cursor < pictureString.length()) {
            final PictureItemKind kind = matchPictureToken(pictureString, cursor);
            if (kind == null) {
                final char delimiter = pictureString.charAt(cursor);
                if (isAsciiLetter(delimiter)) {
                    return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
                }
                items.add(PictureItem.literal(delimiter));
                inputWidth += PictureItemKind.LITERAL.inputWidth();
                cursor += PictureItemKind.LITERAL.pictureTokenLength();
                continue;
            }
            if (declaredRoles[kind.role().ordinal()]) {
                return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
            }
            if (kind == PictureItemKind.ERA) {
                final int closingDelimiter = pictureString.indexOf(ERA_FIELD_CLOSE, cursor);
                if (closingDelimiter == NOT_FOUND) {
                    return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
                }
                cursor = closingDelimiter + 1;
            } else {
                cursor += kind.pictureTokenLength();
            }
            declaredRoles[kind.role().ordinal()] = true;
            items.add(PictureItem.field(kind));
            inputWidth += kind.inputWidth();
        }

        final boolean hasYear = declaredRoles[PictureRole.YEAR.ordinal()];
        final boolean hasMonth = declaredRoles[PictureRole.MONTH.ordinal()];
        final boolean hasDayOfMonth = declaredRoles[PictureRole.DAY_OF_MONTH.ordinal()];
        final boolean hasDayOfYear = declaredRoles[PictureRole.JULIAN_DAY.ordinal()];
        if (hasDayOfYear && (hasMonth || hasDayOfMonth)) {
            return ParsedPicture.rejected(FeedbackToken.FC_BAD_PIC_STRING);
        }
        if (!hasYear || !(hasDayOfYear || (hasMonth && hasDayOfMonth))) {
            return ParsedPicture.rejected(FeedbackToken.FC_INSUFFICIENT_DATA);
        }
        return ParsedPicture.accepted(items, inputWidth, hasDayOfYear);
    }

    private static PictureItemKind matchPictureToken(String pictureString, int cursor) {
        if (pictureString.startsWith(TOKEN_YEAR_4, cursor)) {
            return PictureItemKind.YEAR_4;
        }
        if (pictureString.startsWith(TOKEN_YEAR_2, cursor)) {
            return PictureItemKind.YEAR_2;
        }
        if (pictureString.startsWith(TOKEN_MONTH_NAME, cursor)) {
            return PictureItemKind.MONTH_NAME_3;
        }
        if (pictureString.startsWith(TOKEN_MONTH, cursor)) {
            return PictureItemKind.MONTH_2;
        }
        if (pictureString.startsWith(TOKEN_JULIAN_DAY, cursor)) {
            return PictureItemKind.JULIAN_DAY_3;
        }
        if (pictureString.startsWith(TOKEN_DAY, cursor)) {
            return PictureItemKind.DAY_2;
        }
        if (pictureString.charAt(cursor) == ERA_FIELD_OPEN) {
            return PictureItemKind.ERA;
        }
        return null;
    }

    private static CeedaysOutcome scanInputDate(String inputCharDate, ParsedPicture picture) {
        int yearWithinEra = YEAR_WITHIN_ERA_ZERO;
        int month = JANUARY;
        int dayOfMonth = FIRST_DAY_OF_MONTH;
        int dayOfYear = FIRST_DAY_OF_YEAR;
        boolean beforeCommonEra = false;

        final int pictureLeadingBlanks = leadingBlankLiteralCount(picture.items());
        int itemIndex = pictureLeadingBlanks;
        int cursor = pictureLeadingBlanks > 0
                ? pictureLeadingBlanks
                : firstNonBlankIndex(inputCharDate);

        final List<PictureItem> items = picture.items();
        while (itemIndex < items.size()) {
            final PictureItem item = items.get(itemIndex);
            itemIndex++;
            final PictureItemKind kind = item.kind();

            if (kind == PictureItemKind.LITERAL) {
                if (cursor >= inputCharDate.length()) {
                    return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                }
                final char supplied = inputCharDate.charAt(cursor);
                if (!isDelimiterCharacter(supplied)) {
                    return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                }
                cursor++;
                continue;
            }

            if (kind == PictureItemKind.MONTH_NAME_3 || kind == PictureItemKind.ERA) {
                if (cursor + kind.inputWidth() > inputCharDate.length()) {
                    return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
                }
                final String field = inputCharDate.substring(cursor, cursor + kind.inputWidth());
                cursor += kind.inputWidth();
                if (kind == PictureItemKind.MONTH_NAME_3) {
                    month = monthFromAbbreviation(field);
                    if (month < FIRST_MONTH) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_INVALID_MONTH);
                    }
                } else {
                    final String eraName = field.toUpperCase(Locale.ROOT);
                    if (!ERA_NAMES.contains(eraName)) {
                        return CeedaysOutcome.rejected(FeedbackToken.FC_INVALID_ERA);
                    }
                    beforeCommonEra = ERA_BEFORE_COMMON.equals(eraName);
                }
                continue;
            }

            final int digitsAvailable = digitRunLength(inputCharDate, cursor, kind.inputWidth());
            if (digitsAvailable == 0) {
                return CeedaysOutcome.rejected(FeedbackToken.FC_NON_NUMERIC_DATA);
            }
            final String field = inputCharDate.substring(cursor, cursor + digitsAvailable);
            cursor += digitsAvailable;
            switch (kind) {
                case YEAR_4 -> yearWithinEra = digitsToInt(field);
                case YEAR_2 -> yearWithinEra = resolveTwoDigitYear(digitsToInt(field));
                case MONTH_2 -> month = digitsToInt(field);
                case JULIAN_DAY_3 -> dayOfYear = digitsToInt(field);
                case DAY_2 -> dayOfMonth = digitsToInt(field);
                default -> throw new IllegalStateException("Unhandled numeric picture token " + kind
                        + "; every PictureItemKind is either LITERAL, a name field or one of the five "
                        + "numeric fields, so reaching here means a token was added without a scan "
                        + "rule");
            }
        }

        if (yearWithinEra == YEAR_WITHIN_ERA_ZERO) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_YEAR_IN_ERA_ZERO);
        }
        if (month < FIRST_MONTH || month > LAST_MONTH) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_INVALID_MONTH);
        }
        if (picture.julianDay()) {
            if (dayOfYear < FIRST_DAY_OF_YEAR || dayOfYear > daysInYear(yearWithinEra)) {
                return CeedaysOutcome.rejected(FeedbackToken.FC_BAD_DATE_VALUE);
            }
        } else if (dayOfMonth < FIRST_DAY_OF_MONTH
                || dayOfMonth > daysInMonth(yearWithinEra, month)) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_BAD_DATE_VALUE);
        }
        if (beforeCommonEra) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_UNSUPP_RANGE);
        }
        final int outputLillian = picture.julianDay()
                ? lillianDay(yearWithinEra, JANUARY, FIRST_DAY_OF_MONTH) + dayOfYear - FIRST_DAY_OF_YEAR
                : lillianDay(yearWithinEra, month, dayOfMonth);
        if (outputLillian < FIRST_LILLIAN_DAY) {
            return CeedaysOutcome.rejected(FeedbackToken.FC_UNSUPP_RANGE);
        }
        return CeedaysOutcome.accepted(outputLillian);
    }

    private static boolean isSingleByteDigit(char character) {
        return character >= DIGIT_ZERO && character <= DIGIT_NINE;
    }

    private static int digitsToInt(String digits) {
        int value = 0;
        for (int index = 0; index < digits.length(); index++) {
            value = value * DECIMAL_RADIX + (digits.charAt(index) - DIGIT_ZERO);
        }
        return value;
    }

    private static int firstNonBlankIndex(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != SPACE_CHARACTER) {
                return index;
            }
        }
        return text.length();
    }

    private static int leadingBlankLiteralCount(List<PictureItem> items) {
        int count = 0;
        while (count < items.size()) {
            final PictureItem item = items.get(count);
            if (item.kind() != PictureItemKind.LITERAL || item.literal() != SPACE_CHARACTER) {
                return count;
            }
            count++;
        }
        return count;
    }

    private static int digitRunLength(String text, int from, int maxWidth) {
        int length = 0;
        while (length < maxWidth && from + length < text.length()
                && isSingleByteDigit(text.charAt(from + length))) {
            length++;
        }
        return length;
    }

    private static boolean isDelimiterCharacter(char character) {
        return !isSingleByteDigit(character) && !isAsciiLetter(character);
    }

    private static boolean isAsciiLetter(char character) {
        return (character >= UPPER_CASE_A && character <= UPPER_CASE_Z)
                || (character >= LOWER_CASE_A && character <= LOWER_CASE_Z);
    }

    private static int resolveTwoDigitYear(int twoDigitYear) {
        return twoDigitYear <= TWO_DIGIT_YEAR_WINDOW_PIVOT
                ? TWENTY_FIRST_CENTURY_BASE + twoDigitYear
                : TWENTIETH_CENTURY_BASE + twoDigitYear;
    }

    private static int monthFromAbbreviation(String abbreviation) {
        return MONTH_ABBREVIATIONS.indexOf(abbreviation.toUpperCase(Locale.ROOT)) + FIRST_MONTH;
    }

    private static boolean isLeapYear(int year) {
        return (year % LEAP_YEAR_DIVISOR == 0 && year % CENTURY_DIVISOR != 0)
                || year % LEAP_CYCLE_DIVISOR == 0;
    }

    private static int daysInYear(int year) {
        return isLeapYear(year) ? DAYS_IN_LEAP_YEAR : DAYS_IN_COMMON_YEAR;
    }

    private static int daysInMonth(int year, int month) {
        return switch (month) {
            case FEBRUARY -> isLeapYear(year) ? DAYS_IN_LEAP_FEBRUARY : DAYS_IN_COMMON_FEBRUARY;
            case APRIL, JUNE, SEPTEMBER, NOVEMBER -> DAYS_IN_SHORT_MONTH;
            default -> DAYS_IN_LONG_MONTH;
        };
    }

    private static int gregorianDayNumber(int year, int month, int day) {
        final int marchAlignedMonth = (month + MARCH_ALIGNED_MONTH_SHIFT) % MONTHS_IN_YEAR;
        final int marchAlignedYear = year - marchAlignedMonth / SHIFTED_YEAR_ROLLOVER_DIVISOR;
        return DAYS_IN_COMMON_YEAR * marchAlignedYear
                + marchAlignedYear / LEAP_YEAR_DIVISOR
                - marchAlignedYear / CENTURY_DIVISOR
                + marchAlignedYear / LEAP_CYCLE_DIVISOR
                + (marchAlignedMonth * MONTH_LENGTH_NUMERATOR + MONTH_LENGTH_BIAS)
                        / MONTH_LENGTH_DENOMINATOR
                + (day - FIRST_DAY_OF_MONTH);
    }

    private static int lillianDay(int year, int month, int day) {
        return gregorianDayNumber(year, month, day) - LILLIAN_EPOCH_EVE_DAY_NUMBER;
    }

    /**
     * {@code LS-RESULT PIC X(80)} together with the {@code RETURN-CODE} the program sets - the whole
     * observable output of {@code CSUTLDTC}.
     */
    public static final class DateValidationResult {
        private final String severityCode;

        private final String messageNumber;

        private final String result;

        private final int returnCode;

        private final String message;

        private final byte[] messageBytes;

        private final Charset charset;

        private DateValidationResult(String severityCode,
                                     String messageNumber,
                                     String result,
                                     int returnCode,
                                     String message,
                                     byte[] messageBytes,
                                     Charset charset) {
            this.severityCode = severityCode;
            this.messageNumber = messageNumber;
            this.result = result;
            this.returnCode = returnCode;
            this.message = message;
            this.messageBytes = messageBytes;
            this.charset = charset;
        }

        /**
         * {@code CSUTLDTC-RESULT-SEV-CD} - the severity as the four-character text {@code CORPT00C:396} and
         * {@code COTRN02C:397} compare against the literal {@code '0000'}.
         *
         * @return {@code "0000"} when the date converted, otherwise {@code "0003"}
         */
        public String severityCode() {
            return severityCode;
        }

        /**
         * {@code CSUTLDTC-RESULT-MSG-NUM} - the message number as the four-character text {@code CORPT00C}
         * and {@code COTRN02C} compare against the literal {@code '2513'}, which they alone tolerate.
         *
         * @return {@code "0000"} for a successful conversion or for an unenumerated feedback token,
         *     otherwise the four digits of the Language Environment message number
         */
        public String messageNumber() {
            return messageNumber;
        }

        /**
         * {@code WS-RESULT} - the fifteen-character description the {@code EVALUATE} at L128-L149 selected,
         * right-padded to its declared width.
         *
         * @return exactly fifteen characters, trailing spaces included
         */
        public String result() {
            return result;
        }

        /**
         * The value {@code MOVE WS-SEVERITY-N TO RETURN-CODE} (L98) leaves in the COBOL return code.
         *
         * @return 0 for a successful conversion, 3 for every failure
         */
        public int returnCode() {
            return returnCode;
        }

        /**
         * The complete {@code WS-MESSAGE} image that L97 moves into {@code LS-RESULT}.
         *
         * @return exactly {@link DateUtilityJob#LS_RESULT_LENGTH} characters, including the two
         *     non-printable characters the L122 group move plants at positions 45 and 46
         */
        public String message() {
            return message;
        }

        /**
         * The complete {@code WS-MESSAGE} image as bytes, copied so the result stays immutable.
         *
         * @return a fresh array of exactly {@link DateUtilityJob#LS_RESULT_LENGTH} bytes encoded in
         *     {@link #charset()}
         */
        public byte[] messageBytes() {
            return messageBytes.clone();
        }

        /**
         * The code page {@link #messageBytes()} is encoded in, stated explicitly so no reader has to assume
         * a platform default was used.
         *
         * @return the code page the service was constructed with
         */
        public Charset charset() {
            return charset;
        }

        /**
         * A diagnostic rendering of the discrete fields.
         *
         * @return the severity, message number, result text and return code
         */
        @Override
        public String toString() {
            return "DateValidationResult[severityCode=" + severityCode
                    + ", messageNumber=" + messageNumber
                    + ", result='" + result + '\''
                    + ", returnCode=" + returnCode + ']';
        }
    }
}
