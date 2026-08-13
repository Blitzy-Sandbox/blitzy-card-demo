package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NumericIntrinsics;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.util.DateUtilityJob;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The Java translation of CardDemo's generic {@code CCYYMMDD} date-edit engine: the procedure copybook
 * {@code app/cpy/CSUTLDPY.cpy} (375 lines) together with its working-storage copybook
 * {@code app/cpy/CSUTLDWY.cpy} (89 lines).
 *
 * <p>A non-numeric character view makes the numeric view meaningless, which is precisely why the COBOL
 * checks numeric-ness at all - and why the numeric view here is a documented zoned interpretation that
 * never throws ({@link #zonedValueOf(byte[])}).
 */
@Component
public final class AccountDateValidator {
    /**
     * {@code 10 WS-EDIT-DATE-CCYYMMDD} - CSUTLDWY L4: CC + YY + MM + DD, eight character bytes.
     */
    public static final int WS_EDIT_DATE_CCYYMMDD_LENGTH = 8;

    /**
     * Width of {@code WS-EDIT-DATE-CC}, {@code -YY}, {@code -MM} and {@code -DD} - CSUTLDWY L6-L27.
     */
    public static final int WS_EDIT_DATE_PART_LENGTH = 2;

    /**
     * {@code 20 WS-EDIT-DATE-CCYY} - CSUTLDWY L5: the century and year together.
     */
    public static final int WS_EDIT_DATE_CCYY_LENGTH = 4;

    /**
     * {@code 10 WS-EDIT-DATE-FLGS} - CSUTLDWY L43: three one-byte flags, moved out as a group.
     */
    public static final int WS_EDIT_DATE_FLGS_LENGTH = 3;

    /**
     * {@code 10 WS-DATE-FORMAT PIC X(08)} - CSUTLDWY L58.
     */
    public static final int WS_DATE_FORMAT_LENGTH = 8;

    /**
     * {@code VALUE 'YYYYMMDD'} - CSUTLDWY L59, and the mask {@code EDIT-DATE-LE} moves in at L291.
     */
    public static final String WS_DATE_FORMAT_VALUE = "YYYYMMDD";

    /**
     * {@code 10 WS-DATE-VALIDATION-RESULT} - CSUTLDWY L60-L85, exactly 80 bytes.
     */
    public static final int WS_DATE_VALIDATION_RESULT_LENGTH = 80;

    /**
     * {@code 10 WS-EDIT-VARIABLE-NAME PIC X(25)} - app/cbl/COACTUPC.cbl L53.
     */
    public static final int WS_EDIT_VARIABLE_NAME_LENGTH = 25;

    /**
     * {@code 05 WS-RETURN-MSG PIC X(75)} - app/cbl/COACTUPC.cbl L479.
     */
    public static final int WS_RETURN_MSG_LENGTH = 75;

    /**
     * {@code 88 THIS-CENTURY VALUE 20} - CSUTLDWY L9.
     */
    public static final int THIS_CENTURY = 20;

    /**
     * {@code 88 LAST-CENTURY VALUE 19} - CSUTLDWY L10.
     */
    public static final int LAST_CENTURY = 19;

    /**
     * Low bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12} - CSUTLDWY L19-L20.
     */
    public static final int WS_VALID_MONTH_LOW = 1;

    /**
     * High bound of {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12} - CSUTLDWY L19-L20.
     */
    public static final int WS_VALID_MONTH_HIGH = 12;

    /**
     * {@code 88 WS-FEBRUARY VALUE 2} - CSUTLDWY L24.
     */
    public static final int WS_FEBRUARY = 2;

    /**
     * Low bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31} - CSUTLDWY L28-L29.
     */
    public static final int WS_VALID_DAY_LOW = 1;

    /**
     * High bound of {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31} - CSUTLDWY L28-L29.
     */
    public static final int WS_VALID_DAY_HIGH = 31;

    /**
     * {@code 88 WS-DAY-31 VALUE 31} - CSUTLDWY L30.
     */
    public static final int WS_DAY_31 = 31;

    /**
     * {@code 88 WS-DAY-30 VALUE 30} - CSUTLDWY L31.
     */
    public static final int WS_DAY_30 = 30;

    /**
     * {@code 88 WS-DAY-29 VALUE 29} - CSUTLDWY L32.
     */
    public static final int WS_DAY_29 = 29;

    /**
     * Low bound of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28} - CSUTLDWY L33-L34.
     */
    public static final int WS_VALID_FEB_DAY_LOW = 1;

    /**
     * High bound of {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28} - CSUTLDWY L33-L34.
     */
    public static final int WS_VALID_FEB_DAY_HIGH = 28;

    private static final int[] WS_31_DAY_MONTH_VALUES = {1, 3, 5, 7, 8, 10, 12};

    /**
     * {@code MOVE 400 TO WS-DIV-BY} - CSUTLDPY L246, taken when {@code WS-EDIT-DATE-YY-N = 0}, that is for
     * a century year such as 1900 or 2000.
     */
    public static final int LEAP_DIVISOR_CENTURY = 400;

    /**
     * {@code MOVE 4 TO WS-DIV-BY} - CSUTLDPY L248, and the {@code VALUE 4} the consumer declares on
     * {@code WS-DIV-BY} at {@code COACTUPC:L152-L153}.
     */
    public static final int LEAP_DIVISOR_ORDINARY = 4;

    private static final int CENTURY_YEAR_YY = 0;

    private static final int LEAP_REMAINDER_OK = 0;

    private static final int SEVERITY_OK = 0;

    // These are appended to FUNCTION TRIM(WS-EDIT-VARIABLE-NAME), so the leading space or its absence is
    // significant: two of the thirteen deliberately abut the field name with no space, and three of them
    // place the colon differently.

    public static final String MSG_YEAR_MUST_BE_SUPPLIED = " : Year must be supplied.";

    public static final String MSG_YEAR_MUST_BE_4_DIGITS = " must be 4 digit number.";

    public static final String MSG_CENTURY_NOT_VALID = " : Century is not valid.";

    public static final String MSG_MONTH_MUST_BE_SUPPLIED = " : Month must be supplied.";

    public static final String MSG_MONTH_MUST_BE_1_TO_12 =
            ": Month must be a number between 1 and 12.";

    public static final String MSG_DAY_MUST_BE_SUPPLIED = " : Day must be supplied.";

    public static final String MSG_DAY_MUST_BE_1_TO_31 = ":day must be a number between 1 and 31.";

    public static final String MSG_CANNOT_HAVE_31_DAYS = ":Cannot have 31 days in this month.";

    public static final String MSG_CANNOT_HAVE_30_DAYS = ":Cannot have 30 days in this month.";

    public static final String MSG_NOT_A_LEAP_YEAR =
            ":Not a leap year.Cannot have 29 days in this month.";

    public static final String MSG_VALIDATION_ERROR_SEV_CODE = " validation error Sev code: ";

    public static final String MSG_MESSAGE_CODE = " Message code: ";

    public static final String MSG_CANNOT_BE_IN_THE_FUTURE = ":cannot be in the future ";

    /**
     * The constant that turns a Java epoch day into a COBOL integer date.
     */
    public static final int INTEGER_OF_DATE_EPOCH_OFFSET = 134775;

    /**
     * Lowest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 1601-01-01 as {@code 9(8)}.
     */
    public static final int INTEGER_OF_DATE_LOWEST_ARGUMENT = 16010101;

    /**
     * Highest argument {@code FUNCTION INTEGER-OF-DATE} accepts: 9999-12-31 as {@code 9(8)}.
     */
    public static final int INTEGER_OF_DATE_HIGHEST_ARGUMENT = 99991231;

    /**
     * The value {@link #integerOfDate(int)} yields for an argument that is not a standard date.
     */
    public static final int INTEGER_OF_DATE_UNDEFINED = 0;

    private static final int STANDARD_DATE_YEAR_DIVISOR = 10000;

    private static final int STANDARD_DATE_MONTH_DIVISOR = 100;

    private static final int STANDARD_DATE_COMPONENT_MODULUS = 100;

    /**
     * The declared width of {@code FUNCTION CURRENT-DATE}: {@code YYYYMMDDhhmmsshh} then the sign of the
     * offset from Greenwich and {@code HHMM}, that is 8 + 8 + 1 + 4 = 21 characters.
     */
    public static final int CURRENT_DATE_INTRINSIC_LENGTH = 21;

    private static final int TIME_COMPONENT_LENGTH = 2;

    private static final int NANOS_PER_HUNDREDTH = 10_000_000;

    private static final int SECONDS_PER_MINUTE = 60;

    private static final int MINUTES_PER_HOUR = 60;

    private static final char OFFSET_SIGN_AHEAD = '+';

    private static final char OFFSET_SIGN_BEHIND = '-';

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private static final int ZONED_DIGIT_MASK = 0x0F;

    private static final int DECIMAL_RADIX = 10;

    private static final Charset DEFAULT_CHARSET = StandardCharsets.US_ASCII;

    /**
     * The three states each of {@code WS-EDIT-YEAR-FLG}, {@code WS-EDIT-MONTH} and {@code WS-EDIT-DAY} can
     * hold - CSUTLDWY L46-L57.
     */
    public enum EditFlag {
        /**
         * {@code 88 FLG-YEAR-ISVALID VALUE LOW-VALUES} and its month and day counterparts - L47, L51, L55.
         */
        ISVALID(LOW_VALUE),

        /**
         * {@code 88 FLG-YEAR-NOT-OK VALUE '0'} and its counterparts - L48, L52, L56.
         */
        NOT_OK('0'),

        /**
         * {@code 88 FLG-YEAR-BLANK VALUE 'B'} and its counterparts - L49, L53, L57.
         */
        BLANK('B');

        private final char flagByte;

        EditFlag(char flagByte) {
            this.flagByte = flagByte;
        }

        /**
         * The byte this state occupies in {@code WS-EDIT-DATE-FLGS}.
         *
         * @return {@code LOW-VALUE}, {@code '0'} or {@code 'B'}
         */
        public char flagByte() {
            return flagByte;
        }

        /**
         * Recovers the state a flag byte denotes, which is what reading a flag group back in from a
         * per-field holder such as {@code WS-EDIT-OPEN-DATE-FLGS} amounts to.
         *
         * @param flagByte the byte to interpret
         * @return the matching state
         * @throws IllegalArgumentException if the byte is none of the three declared values, because a
         *     fourth value has no meaning in the copybook and guessing one would hide a corrupted flag group
         */
        public static EditFlag ofByte(char flagByte) {
            for (EditFlag candidate : values()) {
                if (candidate.flagByte == flagByte) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Flag byte 0x"
                    + Integer.toHexString(flagByte) + " is none of the three values CSUTLDWY declares "
                    + "(LOW-VALUE for ISVALID, '0' for NOT-OK, 'B' for BLANK)");
        }
    }

    /**
     * The three states of {@code WS-INPUT-FLAG} - {@code app/cbl/COACTUPC.cbl} L170-L174.
     */
    public enum InputFlag {
        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - COACTUPC L174, and the initial state.
         */
        PENDING(LOW_VALUE),

        /**
         * {@code 88 INPUT-OK VALUE '0'} - COACTUPC L172.
         */
        OK('0'),

        /**
         * {@code 88 INPUT-ERROR VALUE '1'} - COACTUPC L173.
         */
        ERROR('1');

        private final char flagByte;

        InputFlag(char flagByte) {
            this.flagByte = flagByte;
        }

        /**
         * The byte this state occupies in {@code WS-INPUT-FLAG}.
         *
         * @return {@code LOW-VALUE}, {@code '0'} or {@code '1'}
         */
        public char flagByte() {
            return flagByte;
        }

        public static InputFlag ofByte(char flagByte) {
            for (InputFlag candidate : values()) {
                if (candidate.flagByte == flagByte) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("Input flag byte 0x"
                    + Integer.toHexString(flagByte) + " is none of the three values COACTUPC declares "
                    + "(LOW-VALUE for INPUT-PENDING, '0' for INPUT-OK, '1' for INPUT-ERROR)");
        }
    }

    private static final FieldSpan WS_EDIT_DATE_CC =
            FieldSpan.alphanumeric("WS-EDIT-DATE-CC", 0, WS_EDIT_DATE_PART_LENGTH);

    private static final FieldSpan WS_EDIT_DATE_CC_N = FieldSpan.redefining(
            "WS-EDIT-DATE-CC-N", 0, WS_EDIT_DATE_PART_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan WS_EDIT_DATE_YY = FieldSpan.alphanumeric(
            "WS-EDIT-DATE-YY", WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH);

    private static final FieldSpan WS_EDIT_DATE_YY_N = FieldSpan.redefining("WS-EDIT-DATE-YY-N",
            WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan WS_EDIT_DATE_CCYY = FieldSpan.redefining(
            "WS-EDIT-DATE-CCYY", 0, WS_EDIT_DATE_CCYY_LENGTH, PictureKind.ALPHANUMERIC);

    private static final FieldSpan WS_EDIT_DATE_CCYY_N = FieldSpan.redefining(
            "WS-EDIT-DATE-CCYY-N", 0, WS_EDIT_DATE_CCYY_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan WS_EDIT_DATE_MM = FieldSpan.alphanumeric(
            "WS-EDIT-DATE-MM", WS_EDIT_DATE_CCYY_LENGTH, WS_EDIT_DATE_PART_LENGTH);

    private static final FieldSpan WS_EDIT_DATE_MM_N = FieldSpan.redefining("WS-EDIT-DATE-MM-N",
            WS_EDIT_DATE_CCYY_LENGTH, WS_EDIT_DATE_PART_LENGTH, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan WS_EDIT_DATE_DD = FieldSpan.alphanumeric("WS-EDIT-DATE-DD",
            WS_EDIT_DATE_CCYY_LENGTH + WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH);

    private static final FieldSpan WS_EDIT_DATE_DD_N = FieldSpan.redefining("WS-EDIT-DATE-DD-N",
            WS_EDIT_DATE_CCYY_LENGTH + WS_EDIT_DATE_PART_LENGTH, WS_EDIT_DATE_PART_LENGTH,
            PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan WS_EDIT_DATE_CCYYMMDD_N = FieldSpan.redefining(
            "WS-EDIT-DATE-CCYYMMDD-N", 0, WS_EDIT_DATE_CCYYMMDD_LENGTH,
            PictureKind.UNSIGNED_NUMERIC);

    private static final RecordLayout WS_EDIT_DATE_CCYYMMDD_LAYOUT = RecordLayout.of(
            WS_EDIT_DATE_CCYYMMDD_LENGTH,
            WS_EDIT_DATE_CC, WS_EDIT_DATE_CC_N,
            WS_EDIT_DATE_YY, WS_EDIT_DATE_YY_N,
            WS_EDIT_DATE_CCYY, WS_EDIT_DATE_CCYY_N,
            WS_EDIT_DATE_MM, WS_EDIT_DATE_MM_N,
            WS_EDIT_DATE_DD, WS_EDIT_DATE_DD_N,
            WS_EDIT_DATE_CCYYMMDD_N);

    private static final FieldSpan WS_SEVERITY = FieldSpan.alphanumeric("WS-SEVERITY", 0, 4);

    private static final FieldSpan WS_SEVERITY_N =
            FieldSpan.redefining("WS-SEVERITY-N", 0, 4, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan FILLER_MESG_CODE = FieldSpan.filler(4, 11, "Mesg Code:");

    private static final FieldSpan WS_MSG_NO = FieldSpan.alphanumeric("WS-MSG-NO", 15, 4);

    private static final FieldSpan WS_MSG_NO_N =
            FieldSpan.redefining("WS-MSG-NO-N", 15, 4, PictureKind.UNSIGNED_NUMERIC);

    private static final FieldSpan FILLER_AFTER_MSG_NO = FieldSpan.filler(19, 1, " ");

    private static final FieldSpan WS_RESULT = FieldSpan.alphanumeric("WS-RESULT", 20, 15);

    private static final FieldSpan FILLER_AFTER_RESULT = FieldSpan.filler(35, 1, " ");

    private static final FieldSpan FILLER_TST_DATE = FieldSpan.filler(36, 9, "TstDate:");

    private static final FieldSpan WS_DATE = FieldSpan.alphanumeric("WS-DATE", 45, 10);

    private static final FieldSpan FILLER_AFTER_DATE = FieldSpan.filler(55, 1, " ");

    private static final FieldSpan FILLER_MASK_USED = FieldSpan.filler(56, 10, "Mask used:");

    private static final FieldSpan WS_DATE_FMT = FieldSpan.alphanumeric("WS-DATE-FMT", 66, 10);

    private static final FieldSpan FILLER_AFTER_FMT = FieldSpan.filler(76, 1, " ");

    private static final FieldSpan FILLER_TRAILING = FieldSpan.filler(77, 3, "   ");

    private static final RecordLayout WS_DATE_VALIDATION_RESULT_LAYOUT = RecordLayout.of(
            WS_DATE_VALIDATION_RESULT_LENGTH,
            WS_SEVERITY, WS_SEVERITY_N, FILLER_MESG_CODE,
            WS_MSG_NO, WS_MSG_NO_N, FILLER_AFTER_MSG_NO,
            WS_RESULT, FILLER_AFTER_RESULT, FILLER_TST_DATE,
            WS_DATE, FILLER_AFTER_DATE, FILLER_MASK_USED,
            WS_DATE_FMT, FILLER_AFTER_FMT, FILLER_TRAILING);

    // DELIMITED BY SIZE INTO WS-RETURN-MSG statements can go through the codec's own STRING primitive,
    // which leaves the untouched remainder of the receiver alone exactly as COBOL's STRING does.

    private static final FieldSpan WS_RETURN_MSG =
            FieldSpan.alphanumeric("WS-RETURN-MSG", 0, WS_RETURN_MSG_LENGTH);

    private static final RecordLayout WS_RETURN_MSG_LAYOUT =
            RecordLayout.of(WS_RETURN_MSG_LENGTH, WS_RETURN_MSG);

    private static final FieldSpan WS_CURRENT_DATE_YYYYMMDD = FieldSpan.alphanumeric(
            "WS-CURRENT-DATE-YYYYMMDD", 0, WS_EDIT_DATE_CCYYMMDD_LENGTH);

    private static final FieldSpan WS_CURRENT_DATE_YYYYMMDD_N = FieldSpan.redefining(
            "WS-CURRENT-DATE-YYYYMMDD-N", 0, WS_EDIT_DATE_CCYYMMDD_LENGTH,
            PictureKind.UNSIGNED_NUMERIC);

    private static final RecordLayout WS_CURRENT_DATE_LAYOUT = RecordLayout.of(
            WS_EDIT_DATE_CCYYMMDD_LENGTH, WS_CURRENT_DATE_YYYYMMDD, WS_CURRENT_DATE_YYYYMMDD_N);

    /**
     * Every working-storage item the date-edit engine reads or writes, for one validation.
     *
     * <p>COBOL working storage is a single shared area for a single-threaded run; a Java service handling
     * concurrent requests cannot share one, so the caller creates a state, drives the validation, reads the
     * outcome and discards it.
     */
    public static final class EditDateState {
        private final FixedWidthCodec codec;

        private final FixedWidthRecord editDateArea;

        private final FixedWidthRecord dateValidationArea;

        private final FixedWidthRecord returnMsgArea;

        private final FixedWidthRecord currentDateArea;

        private EditFlag yearFlag = EditFlag.ISVALID;

        private EditFlag monthFlag = EditFlag.ISVALID;

        private EditFlag dayFlag = EditFlag.ISVALID;

        private InputFlag inputFlag = InputFlag.PENDING;

        private String editVariableName;

        private String dateFormat = WS_DATE_FORMAT_VALUE;

        private int editDateBinary;

        private int currentDateBinary;

        private int divBy = LEAP_DIVISOR_ORDINARY;

        private int dividend;

        private int remainder;

        /**
         * Creates a state whose {@code PICTURE} rules are applied by the supplied codec.
         *
         * @param codec the codec to apply; must not be {@code null}
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public EditDateState(FixedWidthCodec codec) {
            this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every field "
                    + "of CSUTLDWY is positioned by absolute byte offset and moved under a PICTURE "
                    + "rule, and applying those rules is the codec's responsibility");
            this.editDateArea = codec.newRecord(WS_EDIT_DATE_CCYYMMDD_LAYOUT);
            this.dateValidationArea = codec.newRecord(WS_DATE_VALIDATION_RESULT_LAYOUT);
            this.returnMsgArea = codec.newRecord(WS_RETURN_MSG_LAYOUT);
            this.currentDateArea = codec.newRecord(WS_CURRENT_DATE_LAYOUT);
            this.editVariableName = codec.movePicX("", WS_EDIT_VARIABLE_NAME_LENGTH);
        }

        /**
         * Creates a state against {@link StandardCharsets#US_ASCII}, the code page the ASCII fixtures under
         * {@code app/data/ASCII} are held in.
         */
        public EditDateState() {
            this(new FixedWidthCodec(DEFAULT_CHARSET));
        }

        /**
         * {@code WS-EDIT-DATE-CCYYMMDD} - CSUTLDWY L4.
         *
         * @return exactly eight characters, never trimmed
         */
        public String editDateCcyymmdd() {
            return editDateArea.readString(0, WS_EDIT_DATE_CCYYMMDD_LENGTH);
        }

        /**
         * {@code MOVE <source> TO WS-EDIT-DATE-CCYYMMDD} - the move every one of the four {@code COACTUPC}
         * call sites performs (L1479, L1491, L1504, L1534-L1535).
         *
         * <p>The receiver is an eight-byte alphanumeric group, so the {@code PIC X} rule applies: a shorter
         * value is padded on the right with spaces and a longer one is truncated on the right.
         *
         * @param source the sending value; may be any length, and may be empty
         * @throws NullPointerException if {@code source} is {@code null}
         */
        public void setEditDateCcyymmdd(String source) {
            editDateArea.writeString(0, WS_EDIT_DATE_CCYYMMDD_LENGTH,
                    codec.movePicX(source, WS_EDIT_DATE_CCYYMMDD_LENGTH));
        }

        /**
         * {@code WS-EDIT-DATE-CC PIC X(2)} - CSUTLDWY L6.
         *
         * @return the two century characters
         */
        public String cc() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_CC);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-CC}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCc(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_CC, value);
        }

        /**
         * {@code WS-EDIT-DATE-YY PIC X(2)} - CSUTLDWY L11.
         *
         * @return the two year-within-century characters
         */
        public String yy() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_YY);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-YY}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setYy(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_YY, value);
        }

        /**
         * {@code WS-EDIT-DATE-CCYY} - CSUTLDWY L5.
         *
         * @return the four century-and-year characters
         */
        public String ccyy() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_CCYY);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-CCYY}.
         *
         * @param value the sending value, padded or truncated on the right to four characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setCcyy(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_CCYY, value);
        }

        /**
         * {@code WS-EDIT-DATE-MM PIC X(2)} - CSUTLDWY L16.
         *
         * @return the two month characters
         */
        public String mm() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_MM);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-MM}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setMm(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_MM, value);
        }

        /**
         * {@code WS-EDIT-DATE-DD PIC X(2)} - CSUTLDWY L25.
         *
         * @return the two day characters
         */
        public String dd() {
            return codec.readPicX(editDateArea, WS_EDIT_DATE_DD);
        }

        /**
         * {@code MOVE <value> TO WS-EDIT-DATE-DD}.
         *
         * @param value the sending value, padded or truncated on the right to two characters
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public void setDd(String value) {
            codec.writePicX(editDateArea, WS_EDIT_DATE_DD, value);
        }

        // It reads the same bytes through the zoned DISPLAY interpretation and never throws, because a
        // lettered or blank screen field has to arrive at a flag, not at an exception.

        /**
         * {@code WS-EDIT-DATE-CC-N PIC 9(2)} - CSUTLDWY L7-L8.
         *
         * @return the zoned value of the two century bytes
         */
        public int ccN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_CC_N));
        }

        /**
         * {@code WS-EDIT-DATE-YY-N PIC 9(2)} - CSUTLDWY L12-L13.
         *
         * @return the zoned value of the two year bytes
         */
        public int yyN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_YY_N));
        }

        /**
         * {@code WS-EDIT-DATE-CCYY-N PIC 9(4)} - CSUTLDWY L14-L15.
         *
         * @return the zoned value of the four century-and-year bytes
         */
        public int ccyyN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_CCYY_N));
        }

        /**
         * {@code WS-EDIT-DATE-MM-N PIC 9(2)} - CSUTLDWY L17-L18.
         *
         * @return the zoned value of the two month bytes
         */
        public int mmN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_MM_N));
        }

        /**
         * {@code COMPUTE WS-EDIT-DATE-MM-N = ...} - CSUTLDPY L127-L129, the normalising store that rewrites
         * the two month bytes as zero-filled digits.
         *
         * @param value the value to store; must not be negative, because {@code PIC 9} has no sign position
         *     - the caller removes the sign first, exactly as a COBOL store into an unsigned receiver does
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public void setMmN(long value) {
            codec.writePic9(editDateArea, WS_EDIT_DATE_MM_N, value);
        }

        /**
         * {@code WS-EDIT-DATE-DD-N PIC 9(2)} - CSUTLDWY L26-L27.
         *
         * @return the zoned value of the two day bytes
         */
        public int ddN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_DD_N));
        }

        /**
         * {@code COMPUTE WS-EDIT-DATE-DD-N = ...} - CSUTLDPY L171-L173, the normalising store that rewrites
         * the two day bytes as zero-filled digits.
         *
         * @param value the value to store; must not be negative
         * @throws IllegalArgumentException if {@code value} is negative
         */
        public void setDdN(long value) {
            codec.writePic9(editDateArea, WS_EDIT_DATE_DD_N, value);
        }

        /**
         * {@code WS-EDIT-DATE-CCYYMMDD-N PIC 9(8)} - CSUTLDWY L35-L36.
         *
         * @return the zoned value of all eight bytes, the standard-date argument of
         *     {@code FUNCTION INTEGER-OF-DATE} at L346
         */
        public int ccyymmddN() {
            return (int) zonedValueOf(editDateArea.readSpanBytes(WS_EDIT_DATE_CCYYMMDD_N));
        }

        /**
         * {@code 88 THIS-CENTURY VALUE 20} - CSUTLDWY L9.
         *
         * @return whether the century bytes read as 20
         */
        public boolean thisCentury() {
            return ccN() == THIS_CENTURY;
        }

        /**
         * {@code 88 LAST-CENTURY VALUE 19} - CSUTLDWY L10.
         *
         * @return whether the century bytes read as 19
         */
        public boolean lastCentury() {
            return ccN() == LAST_CENTURY;
        }

        /**
         * {@code 88 WS-VALID-MONTH VALUES 1 THROUGH 12} - CSUTLDWY L19-L20.
         *
         * @return whether the month bytes read as 1 through 12 inclusive
         */
        public boolean wsValidMonth() {
            int month = mmN();
            return month >= WS_VALID_MONTH_LOW && month <= WS_VALID_MONTH_HIGH;
        }

        /**
         * {@code 88 WS-31-DAY-MONTH VALUES 1, 3, 5, 7, 8, 10, 12} - CSUTLDWY L21-L23.
         *
         * @return whether the month bytes read as one of the seven months with thirty-one days
         */
        public boolean ws31DayMonth() {
            int month = mmN();
            for (int candidate : WS_31_DAY_MONTH_VALUES) {
                if (candidate == month) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@code 88 WS-FEBRUARY VALUE 2} - CSUTLDWY L24.
         *
         * @return whether the month bytes read as 2
         */
        public boolean wsFebruary() {
            return mmN() == WS_FEBRUARY;
        }

        /**
         * {@code 88 WS-VALID-DAY VALUES 1 THROUGH 31} - CSUTLDWY L28-L29.
         *
         * @return whether the day bytes read as 1 through 31 inclusive
         */
        public boolean wsValidDay() {
            int day = ddN();
            return day >= WS_VALID_DAY_LOW && day <= WS_VALID_DAY_HIGH;
        }

        /**
         * {@code 88 WS-DAY-31 VALUE 31} - CSUTLDWY L30.
         *
         * @return whether the day bytes read as 31
         */
        public boolean wsDay31() {
            return ddN() == WS_DAY_31;
        }

        /**
         * {@code 88 WS-DAY-30 VALUE 30} - CSUTLDWY L31.
         *
         * @return whether the day bytes read as 30
         */
        public boolean wsDay30() {
            return ddN() == WS_DAY_30;
        }

        /**
         * {@code 88 WS-DAY-29 VALUE 29} - CSUTLDWY L32.
         *
         * @return whether the day bytes read as 29
         */
        public boolean wsDay29() {
            return ddN() == WS_DAY_29;
        }

        /**
         * {@code 88 WS-VALID-FEB-DAY VALUES 1 THROUGH 28} - CSUTLDWY L33-L34.
         *
         * @return whether the day bytes read as 1 through 28 inclusive
         */
        public boolean wsValidFebDay() {
            int day = ddN();
            return day >= WS_VALID_FEB_DAY_LOW && day <= WS_VALID_FEB_DAY_HIGH;
        }

        /**
         * {@code WS-EDIT-YEAR-FLG} - CSUTLDWY L46.
         *
         * @return the year flag's state
         */
        public EditFlag yearFlag() {
            return yearFlag;
        }

        /**
         * {@code WS-EDIT-MONTH} - CSUTLDWY L50.
         *
         * @return the month flag's state
         */
        public EditFlag monthFlag() {
            return monthFlag;
        }

        /**
         * {@code WS-EDIT-DAY} - CSUTLDWY L54.
         *
         * @return the day flag's state
         */
        public EditFlag dayFlag() {
            return dayFlag;
        }

        /**
         * {@code SET FLG-YEAR-ISVALID | FLG-YEAR-NOT-OK | FLG-YEAR-BLANK TO TRUE}.
         *
         * @param flag the state to set; must not be {@code null}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setYearFlag(EditFlag flag) {
            this.yearFlag = Objects.requireNonNull(flag, "A year flag state is required");
        }

        /**
         * {@code SET FLG-MONTH-ISVALID | FLG-MONTH-NOT-OK | FLG-MONTH-BLANK TO TRUE}.
         *
         * @param flag the state to set; must not be {@code null}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setMonthFlag(EditFlag flag) {
            this.monthFlag = Objects.requireNonNull(flag, "A month flag state is required");
        }

        /**
         * {@code SET FLG-DAY-ISVALID | FLG-DAY-NOT-OK | FLG-DAY-BLANK TO TRUE}.
         *
         * @param flag the state to set; must not be {@code null}
         * @throws NullPointerException if {@code flag} is {@code null}
         */
        public void setDayFlag(EditFlag flag) {
            this.dayFlag = Objects.requireNonNull(flag, "A day flag state is required");
        }

        /**
         * {@code 88 FLG-YEAR-ISVALID VALUE LOW-VALUES} - CSUTLDWY L47.
         *
         * @return whether the year flag is {@link EditFlag#ISVALID}
         */
        public boolean flgYearIsvalid() {
            return yearFlag == EditFlag.ISVALID;
        }

        /**
         * {@code 88 FLG-YEAR-NOT-OK VALUE '0'} - CSUTLDWY L48.
         *
         * @return whether the year flag is {@link EditFlag#NOT_OK}
         */
        public boolean flgYearNotOk() {
            return yearFlag == EditFlag.NOT_OK;
        }

        /**
         * {@code 88 FLG-YEAR-BLANK VALUE 'B'} - CSUTLDWY L49.
         *
         * @return whether the year flag is {@link EditFlag#BLANK}
         */
        public boolean flgYearBlank() {
            return yearFlag == EditFlag.BLANK;
        }

        /**
         * {@code 88 FLG-MONTH-ISVALID VALUE LOW-VALUES} - CSUTLDWY L51.
         *
         * @return whether the month flag is {@link EditFlag#ISVALID}
         */
        public boolean flgMonthIsvalid() {
            return monthFlag == EditFlag.ISVALID;
        }

        /**
         * {@code 88 FLG-MONTH-NOT-OK VALUE '0'} - CSUTLDWY L52.
         *
         * @return whether the month flag is {@link EditFlag#NOT_OK}
         */
        public boolean flgMonthNotOk() {
            return monthFlag == EditFlag.NOT_OK;
        }

        /**
         * {@code 88 FLG-MONTH-BLANK VALUE 'B'} - CSUTLDWY L53.
         *
         * @return whether the month flag is {@link EditFlag#BLANK}
         */
        public boolean flgMonthBlank() {
            return monthFlag == EditFlag.BLANK;
        }

        /**
         * {@code 88 FLG-DAY-ISVALID VALUE LOW-VALUES} - CSUTLDWY L55.
         *
         * @return whether the day flag is {@link EditFlag#ISVALID}
         */
        public boolean flgDayIsvalid() {
            return dayFlag == EditFlag.ISVALID;
        }

        /**
         * {@code 88 FLG-DAY-NOT-OK VALUE '0'} - CSUTLDWY L56.
         *
         * @return whether the day flag is {@link EditFlag#NOT_OK}
         */
        public boolean flgDayNotOk() {
            return dayFlag == EditFlag.NOT_OK;
        }

        /**
         * {@code 88 FLG-DAY-BLANK VALUE 'B'} - CSUTLDWY L57.
         *
         * @return whether the day flag is {@link EditFlag#BLANK}
         */
        public boolean flgDayBlank() {
            return dayFlag == EditFlag.BLANK;
        }

        /**
         * {@code WS-EDIT-DATE-FLGS} - CSUTLDWY L43, as the three bytes a group move transfers.
         *
         * @return exactly three characters, in year, month, day order
         */
        public String flagsImage() {
            return String.valueOf(new char[] {
                    yearFlag.flagByte(), monthFlag.flagByte(), dayFlag.flagByte()});
        }

        /**
         * {@code 88 WS-EDIT-DATE-IS-VALID VALUE LOW-VALUES} - CSUTLDWY L44.
         *
         * @return whether all three flags are {@link EditFlag#ISVALID}
         */
        public boolean wsEditDateIsValid() {
            return flgYearIsvalid() && flgMonthIsvalid() && flgDayIsvalid();
        }

        /**
         * {@code 88 WS-EDIT-DATE-IS-INVALID VALUE '000'} - CSUTLDWY L45.
         *
         * @return whether all three flags are {@link EditFlag#NOT_OK}
         */
        public boolean wsEditDateIsInvalid() {
            return flgYearNotOk() && flgMonthNotOk() && flgDayNotOk();
        }

        /**
         * {@code SET WS-EDIT-DATE-IS-VALID TO TRUE} - CSUTLDPY L327, the group set that clears all three
         * flags at once.
         *
         * <p>Setting a group condition name assigns the group, so this is not shorthand for three
         * individual sets: it overwrites whatever the three flags held, which is exactly why L327 discards
         * the {@code NOT-OK} states the error path at L301-L304 had just established.
         */
        public void setWsEditDateIsValid() {
            yearFlag = EditFlag.ISVALID;
            monthFlag = EditFlag.ISVALID;
            dayFlag = EditFlag.ISVALID;
        }

        /**
         * {@code SET WS-EDIT-DATE-IS-INVALID TO TRUE} - CSUTLDPY L19, the first statement of the range,
         * which starts every validation from "all three fields are bad".
         */
        public void setWsEditDateIsInvalid() {
            yearFlag = EditFlag.NOT_OK;
            monthFlag = EditFlag.NOT_OK;
            dayFlag = EditFlag.NOT_OK;
        }

        /**
         * {@code WS-INPUT-FLAG} - COACTUPC L170.
         *
         * @return the shared input flag's state
         */
        public InputFlag inputFlag() {
            return inputFlag;
        }

        /**
         * {@code 88 INPUT-ERROR VALUE '1'} - COACTUPC L173.
         *
         * @return whether the shared input flag records an error, from this field or an earlier one
         */
        public boolean inputError() {
            return inputFlag == InputFlag.ERROR;
        }

        /**
         * {@code 88 INPUT-OK VALUE '0'} - COACTUPC L172.
         *
         * @return whether the shared input flag records success
         */
        public boolean inputOk() {
            return inputFlag == InputFlag.OK;
        }

        /**
         * {@code 88 INPUT-PENDING VALUE LOW-VALUES} - COACTUPC L174.
         *
         * @return whether the shared input flag is still unset
         */
        public boolean inputPending() {
            return inputFlag == InputFlag.PENDING;
        }

        /**
         * {@code SET INPUT-ERROR TO TRUE} - the eleven error paths of CSUTLDPY.
         */
        public void setInputError() {
            inputFlag = InputFlag.ERROR;
        }

        /**
         * {@code SET INPUT-OK TO TRUE}, for a caller resetting the shared flag between screens.
         */
        public void setInputOk() {
            inputFlag = InputFlag.OK;
        }

        /**
         * {@code SET INPUT-PENDING TO TRUE}, the {@code LOW-VALUES} state the flag starts in.
         */
        public void setInputPending() {
            inputFlag = InputFlag.PENDING;
        }

        /**
         * {@code WS-EDIT-VARIABLE-NAME PIC X(25)} - COACTUPC L53.
         *
         * @return exactly twenty-five characters, space-padded, as {@code FUNCTION TRIM} receives it
         */
        public String editVariableName() {
            return editVariableName;
        }

        /**
         * {@code MOVE '<name>' TO WS-EDIT-VARIABLE-NAME} - COACTUPC L1478, L1490, L1503 and L1533, which
         * supply {@code 'Open Date'}, {@code 'Expiry Date'}, {@code 'Reissue Date'} and
         * {@code 'Date of Birth'} respectively.
         *
         * @param name the field name; padded or truncated on the right to twenty-five characters
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public void setEditVariableName(String name) {
            this.editVariableName = codec.movePicX(name, WS_EDIT_VARIABLE_NAME_LENGTH);
        }

        /**
         * {@code WS-RETURN-MSG PIC X(75)} - COACTUPC L479.
         *
         * @return exactly seventy-five characters, never trimmed
         */
        public String returnMessage() {
            return codec.readPicX(returnMsgArea, WS_RETURN_MSG);
        }

        /**
         * {@code 88 WS-RETURN-MSG-OFF VALUE SPACES} - COACTUPC L480.
         *
         * @return whether the message slot is still all spaces
         */
        public boolean returnMsgOff() {
            return returnMessage().equals(codec.movePicX("", WS_RETURN_MSG_LENGTH));
        }

        /**
         * {@code SET WS-RETURN-MSG-OFF TO TRUE} - COACTUPC L876: blank the whole seventy-five.
         */
        public void setReturnMsgOff() {
            codec.writePicX(returnMsgArea, WS_RETURN_MSG, "");
        }

        /**
         * {@code STRING <operands> DELIMITED BY SIZE INTO WS-RETURN-MSG} - the thirteen message sites.
         *
         * <p>Delegated to the codec's {@code STRING} primitive, which transfers from the left and leaves
         * the rest of the receiver untouched, exactly as COBOL's {@code STRING} does - it is {@code MOVE},
         * not {@code STRING}, that space-fills a short sending item.
         *
         * @param operands the sending items, each contributing its full width; at least one, none
         *     {@code null}
         * @throws NullPointerException if {@code operands} or any operand is {@code null}
         * @throws IllegalArgumentException if no operand is supplied
         */
        public void stringIntoReturnMessage(String... operands) {
            codec.stringIntoDelimitedBySize(returnMsgArea, WS_RETURN_MSG, operands);
        }

        /**
         * {@code WS-DATE-FORMAT PIC X(08)} - CSUTLDWY L58.
         *
         * @return exactly eight characters; {@code 'YYYYMMDD'} unless a caller changed it
         */
        public String dateFormat() {
            return dateFormat;
        }

        /**
         * {@code MOVE 'YYYYMMDD' TO WS-DATE-FORMAT} - CSUTLDPY L291.
         *
         * @param format the mask; padded or truncated on the right to eight characters
         * @throws NullPointerException if {@code format} is {@code null}
         */
        public void setDateFormat(String format) {
            this.dateFormat = codec.movePicX(format, WS_DATE_FORMAT_LENGTH);
        }

        /**
         * {@code INITIALIZE WS-DATE-VALIDATION-RESULT} - CSUTLDPY L290.
         */
        public void initializeDateValidationResult() {
            dateValidationArea.initialize(WS_DATE_VALIDATION_RESULT_LAYOUT,
                    FixedWidthRecord.FillerHandling.WITHOUT_FILLER,
                    FixedWidthRecord.ValueHandling.CATEGORY_DEFAULTS);
        }

        /**
         * Receives what {@code CSUTLDTC} wrote into {@code WS-DATE-VALIDATION-RESULT}.
         *
         * @param eightyBytes the result the service produced; must be exactly
         *     {@link AccountDateValidator#WS_DATE_VALIDATION_RESULT_LENGTH} bytes
         * @throws NullPointerException if {@code eightyBytes} is {@code null}
         * @throws IllegalArgumentException if the length is not eighty, which would mean this class's
         *     transcription of the area and {@code DateUtilityJob}'s have diverged
         */
        public void acceptDateValidationResult(byte[] eightyBytes) {
            Objects.requireNonNull(eightyBytes, "The eighty-byte CSUTLDTC result is required");
            if (eightyBytes.length != WS_DATE_VALIDATION_RESULT_LENGTH) {
                throw new IllegalArgumentException("CSUTLDTC returned " + eightyBytes.length
                        + " byte(s) but LS-RESULT is declared PIC X("
                        + WS_DATE_VALIDATION_RESULT_LENGTH + "); WS-DATE-VALIDATION-RESULT "
                        + "(app/cpy/CSUTLDWY.cpy L60-L85) and WS-MESSAGE (app/cbl/CSUTLDTC.cbl "
                        + "L42-L57) are one area declared twice and must agree byte for byte");
            }
            dateValidationArea.writeBytes(0, eightyBytes);
        }

        /**
         * {@code WS-DATE-VALIDATION-RESULT} - CSUTLDWY L60.
         *
         * @return exactly eighty characters
         */
        public String dateValidationResult() {
            return dateValidationArea.readString(0, WS_DATE_VALIDATION_RESULT_LENGTH);
        }

        /**
         * The eighty bytes of {@code WS-DATE-VALIDATION-RESULT}, for a caller comparing them byte for byte
         * rather than character for character.
         *
         * @return a fresh array of exactly eighty bytes
         */
        public byte[] dateValidationResultBytes() {
            return dateValidationArea.toByteArray();
        }

        /**
         * {@code WS-SEVERITY PIC X(04)} - CSUTLDWY L61.
         *
         * @return exactly four characters
         */
        public String wsSeverity() {
            return codec.readPicX(dateValidationArea, WS_SEVERITY);
        }

        /**
         * {@code WS-SEVERITY-N PIC 9(4)} - CSUTLDWY L62-L63.
         *
         * @return the zoned value of the four severity bytes; zero when the service reported success
         */
        public int wsSeverityN() {
            return (int) zonedValueOf(dateValidationArea.readSpanBytes(WS_SEVERITY_N));
        }

        /**
         * {@code WS-MSG-NO PIC X(04)} - CSUTLDWY L66.
         *
         * @return exactly four characters
         */
        public String wsMsgNo() {
            return codec.readPicX(dateValidationArea, WS_MSG_NO);
        }

        /**
         * {@code WS-MSG-NO-N Pic 9(4)} - CSUTLDWY L67-L68.
         *
         * @return the zoned value of the four message-number bytes
         */
        public int wsMsgNoN() {
            return (int) zonedValueOf(dateValidationArea.readSpanBytes(WS_MSG_NO_N));
        }

        /**
         * {@code WS-RESULT PIC X(15)} - CSUTLDWY L71: one of the ten verdict texts
         * {@code CSUTLDTC:L128-L149} selects.
         *
         * @return exactly fifteen characters, trailing spaces included
         */
        public String wsResult() {
            return codec.readPicX(dateValidationArea, WS_RESULT);
        }

        /**
         * {@code WS-DATE PIC X(10)} - CSUTLDWY L76, the {@code TstDate:} span.
         *
         * @return exactly ten characters as {@code CSUTLDTC} left them, including the two halfword bytes
         *     its own group move at {@code CSUTLDTC:L122} places in front of the date
         */
        public String wsDate() {
            return codec.readPicX(dateValidationArea, WS_DATE);
        }

        /**
         * {@code WS-DATE-FMT PIC X(10)} - CSUTLDWY L81, the {@code Mask used:} span.
         *
         * @return exactly ten characters
         */
        public String wsDateFmt() {
            return codec.readPicX(dateValidationArea, WS_DATE_FMT);
        }

        /**
         * {@code WS-CURRENT-DATE-YYYYMMDD PIC X(8)} - CSUTLDWY L39.
         *
         * @return exactly eight characters
         */
        public String currentDateYyyymmdd() {
            return codec.readPicX(currentDateArea, WS_CURRENT_DATE_YYYYMMDD);
        }

        /**
         * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURRENT-DATE-YYYYMMDD} - CSUTLDPY L343.
         *
         * <p>The intrinsic returns twenty-one characters and the receiver holds eight, so the {@code PIC X}
         * rule truncates on the right: the date survives and the time and Greenwich offset are discarded.
         *
         * @param intrinsicValue the sending value, typically the twenty-one characters
         *     {@link AccountDateValidator#currentDateIntrinsic(Clock)} produces
         * @throws NullPointerException if {@code intrinsicValue} is {@code null}
         */
        public void setCurrentDateYyyymmdd(String intrinsicValue) {
            codec.writePicX(currentDateArea, WS_CURRENT_DATE_YYYYMMDD, intrinsicValue);
        }

        /**
         * {@code WS-CURRENT-DATE-YYYYMMDD-N PIC 9(8)} - CSUTLDWY L40-L41.
         *
         * @return the zoned value of the eight current-date bytes, the argument
         *     {@code FUNCTION INTEGER-OF-DATE} receives at L348
         */
        public int currentDateYyyymmddN() {
            return (int) zonedValueOf(currentDateArea.readSpanBytes(WS_CURRENT_DATE_YYYYMMDD_N));
        }

        /**
         * {@code WS-EDIT-DATE-BINARY PIC S9(9) BINARY} - CSUTLDWY L37, set by the {@code COMPUTE} at
         * L345-L346.
         *
         * @return the integer date of the value being edited
         */
        public int editDateBinary() {
            return editDateBinary;
        }

        /**
         * {@code COMPUTE WS-EDIT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (...)} - CSUTLDPY L345-L346.
         *
         * @param integerDate the integer date to store; {@code PIC S9(9)} is signed and holds
         *     &plusmn;999,999,999, which the 1601-9999 range of the intrinsic cannot exceed
         */
        public void setEditDateBinary(int integerDate) {
            this.editDateBinary = integerDate;
        }

        /**
         * {@code WS-CURRENT-DATE-BINARY PIC S9(9) BINARY} - CSUTLDWY L42, set by the {@code COMPUTE} at
         * L347-L348.
         *
         * @return the integer date of today
         */
        public int currentDateBinary() {
            return currentDateBinary;
        }

        /**
         * {@code COMPUTE WS-CURRENT-DATE-BINARY = FUNCTION INTEGER-OF-DATE (...)} - CSUTLDPY L347-L348.
         *
         * @param integerDate the integer date to store
         */
        public void setCurrentDateBinary(int integerDate) {
            this.currentDateBinary = integerDate;
        }

        /**
         * {@code WS-DIV-BY PIC S9(4) COMP-3} - COACTUPC L152, the leap-year divisor chosen at L246 or L248.
         *
         * @return 400 for a century year, 4 otherwise
         */
        public int divBy() {
            return divBy;
        }

        /**
         * {@code MOVE 400 | 4 TO WS-DIV-BY} - CSUTLDPY L246 and L248.
         *
         * @param divisor the divisor to store
         */
        public void setDivBy(int divisor) {
            this.divBy = divisor;
        }

        /**
         * {@code WS-DIVIDEND PIC S9(4) COMP-3} - COACTUPC L154, the quotient of the leap-year division at
         * L251-L254.
         *
         * @return the quotient of the last leap-year division
         */
        public int dividend() {
            return dividend;
        }

        /**
         * {@code WS-REMAINDER PIC S9(4) COMP-3} - COACTUPC L157, the remainder tested at L256.
         *
         * @return the remainder of the last leap-year division
         */
        public int remainder() {
            return remainder;
        }

        /**
         * {@code DIVIDE WS-EDIT-DATE-CCYY-N BY WS-DIV-BY GIVING WS-DIVIDEND REMAINDER WS-REMAINDER} -
         * CSUTLDPY L251-L254.
         *
         * @param quotient the value {@code GIVING} stores
         * @param remainder the value {@code REMAINDER} stores
         */
        public void setDivisionResult(int quotient, int remainder) {
            this.dividend = quotient;
            this.remainder = remainder;
        }

        /**
         * A diagnostic rendering, for a failing assertion to print.
         *
         * @return the edit bytes, the flag group, the input flag and the message slot
         */
        @Override
        public String toString() {
            StringBuilder flags = new StringBuilder();
            for (char flagByte : flagsImage().toCharArray()) {
                flags.append(flagByte == LOW_VALUE ? "<LOW>" : String.valueOf(flagByte));
            }
            return "EditDateState[date='" + editDateCcyymmdd() + "', flags=" + flags
                    + ", inputFlag=" + inputFlag + ", name='" + editVariableName.trim()
                    + "', returnMsg='" + returnMessage().trim() + "']";
        }
    }

    private final FixedWidthCodec codec;

    private final DateUtilityJob dateUtility;

    private final Clock clock;

    /**
     * Creates the validator the Spring container wires: a codec over the configured dataset code page and
     * the system clock.
     *
     * @param datasetCharset the active dataset code page, selected by qualifier because
     *     {@code CobolCharsetConfig} declares no primary {@link Charset} bean
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if the code page is not single-byte over the digits, the space and
     *     the sign overpunch characters
     */
    @Autowired
    public AccountDateValidator(
            @Qualifier(CobolCharsetConfig.DATASET_CHARSET_BEAN_NAME) Charset datasetCharset,
            DateUtilityJob dateUtility) {
        this(new FixedWidthCodec(Objects.requireNonNull(datasetCharset, "A dataset Charset is "
                + "required and must be selected by qualifier: config/CobolCharsetConfig publishes "
                + "three Charset beans and declares no primary, so an unqualified injection point is "
                + "ambiguous by design")), dateUtility, Clock.systemDefaultZone());
    }

    /**
     * Creates the validator against {@link StandardCharsets#US_ASCII} and the system clock.
     *
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @throws NullPointerException if {@code dateUtility} is {@code null}
     */
    public AccountDateValidator(DateUtilityJob dateUtility) {
        this(new FixedWidthCodec(DEFAULT_CHARSET), dateUtility, Clock.systemDefaultZone());
    }

    /**
     * Creates the validator against an explicitly supplied codec and the system clock.
     *
     * @param codec the codec whose code page positions every byte of the eighty-byte result; must not be
     *     {@code null}
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountDateValidator(FixedWidthCodec codec, DateUtilityJob dateUtility) {
        this(codec, dateUtility, Clock.systemDefaultZone());
    }

    /**
     * Creates the validator against an explicitly supplied codec and clock.
     *
     * @param codec the codec to apply; must not be {@code null}
     * @param dateUtility the {@code CSUTLDTC} service; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public AccountDateValidator(FixedWidthCodec codec, DateUtilityJob dateUtility, Clock clock) {
        this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: CSUTLDWY is "
                + "positioned by absolute byte offset and every store obeys a PICTURE rule");
        this.dateUtility = Objects.requireNonNull(dateUtility, "A DateUtilityJob is required: "
                + "EDIT-DATE-LE (app/cpy/CSUTLDPY.cpy L293-L296) calls CSUTLDTC, and without it the "
                + "final validation stage of every date edit cannot run");
        this.clock = Objects.requireNonNull(clock, "A Clock is required: this class never calls a "
                + "no-argument clock reader, so that FUNCTION CURRENT-DATE (L343) is deterministic");
    }

    /**
     * Creates a fresh {@link EditDateState} sharing this validator's codec, and therefore its code page.
     *
     * @return a state in the condition {@code COACTUPC} presents on entry
     */
    public EditDateState newState() {
        return new EditDateState(codec);
    }

    /**
     * The clock {@code FUNCTION CURRENT-DATE} reads when no clock is supplied per call.
     *
     * @return this validator's clock, never {@code null}
     */
    public Clock clock() {
        return clock;
    }

    /**
     * {@code PERFORM EDIT-DATE-CCYYMMDD THRU EDIT-DATE-CCYYMMDD-EXIT} - the whole paragraph range, as
     * performed at {@code COACTUPC:L1480-L1481}, {@code L1492-L1493}, {@code L1505-L1506} and
     * {@code L1536-L1537}.
     *
     * <p>On return the caller reads {@link EditDateState#flagsImage()} - the
     * {@code MOVE WS-EDIT-DATE-FLGS TO ...} every call site performs - and, for a birth date, gates
     * {@link #editDateOfBirth(EditDateState)} on {@link EditDateState#wsEditDateIsValid()} exactly as
     * {@code COACTUPC:L1539} does.
     *
     * @param state the per-invocation working storage, with the eight edit bytes and the field name already
     *     moved in; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDateCcyymmddThruExit(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required: it is the working storage of "
                + "app/cpy/CSUTLDWY.cpy, which the caller owns for the duration of one validation");

        editDateCcyymmdd(state);
        editYearCcyy(state);
        editMonth(state);
        editDay(state);
        if (!editDayMonthYear(state)) {
            return;
        }

        editDateLe(state);

        state.setWsEditDateIsValid();

    }

    /**
     * {@code EDIT-DATE-CCYYMMDD} - CSUTLDPY L18-L20, one statement.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDateCcyymmdd(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");
        state.setWsEditDateIsInvalid();
    }

    /**
     * {@code EDIT-YEAR-CCYY} - CSUTLDPY L25-L87, with {@code GO TO EDIT-YEAR-CCYY-EXIT} as a
     * {@code return}.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editYearCcyy(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.setYearFlag(EditFlag.NOT_OK);

        String ccyy = state.ccyy();
        if (isAllLowValues(ccyy) || isAllSpaces(ccyy)) {
            state.setInputError();
            state.setYearFlag(EditFlag.BLANK);
            stringErrorMessage(state, MSG_YEAR_MUST_BE_SUPPLIED);
            return;
        }

        if (!isNumericClass(ccyy)) {
            state.setInputError();
            state.setYearFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_YEAR_MUST_BE_4_DIGITS);
            return;
        }

        if (!(state.thisCentury() || state.lastCentury())) {
            state.setInputError();
            state.setYearFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_CENTURY_NOT_VALID);
            return;
        }

        state.setYearFlag(EditFlag.ISVALID);
    }

    /**
     * {@code EDIT-MONTH} - CSUTLDPY L91-L144, with {@code GO TO EDIT-MONTH-EXIT} as a {@code return}.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editMonth(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.setMonthFlag(EditFlag.NOT_OK);

        String mm = state.mm();
        if (isAllLowValues(mm) || isAllSpaces(mm)) {
            state.setInputError();
            state.setMonthFlag(EditFlag.BLANK);
            stringErrorMessage(state, MSG_MONTH_MUST_BE_SUPPLIED);
            return;
        }

        if (!state.wsValidMonth()) {
            state.setInputError();
            state.setMonthFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_MONTH_MUST_BE_1_TO_12);
            return;
        }

        NumericIntrinsics.Scan scan = NumericIntrinsics.scanNumval(mm);
        if (scan.conforms()) {
            state.setMmN(storeIntoUnsigned(scan.value()));
        } else {
            state.setInputError();
            state.setMonthFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_MONTH_MUST_BE_1_TO_12);
            return;
        }

        state.setMonthFlag(EditFlag.ISVALID);
    }

    /**
     * {@code EDIT-DAY} - CSUTLDPY L150-L204, with {@code GO TO EDIT-DAY-EXIT} as a {@code return}.
     *
     * <p>Two asymmetries with the year and month edits, both deliberate and both preserved: the paragraph
     * opens with {@code SET FLG-DAY-ISVALID TO TRUE} (L152) where the other two open with {@code NOT-OK}.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDay(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.setDayFlag(EditFlag.ISVALID);

        String dd = state.dd();
        if (isAllLowValues(dd) || isAllSpaces(dd)) {
            state.setInputError();
            state.setDayFlag(EditFlag.BLANK);
            stringErrorMessage(state, MSG_DAY_MUST_BE_SUPPLIED);
            return;
        }

        NumericIntrinsics.Scan scan = NumericIntrinsics.scanNumval(dd);
        if (scan.conforms()) {
            state.setDdN(storeIntoUnsigned(scan.value()));
        } else {
            state.setInputError();
            state.setDayFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_DAY_MUST_BE_1_TO_31);
            return;
        }

        if (!state.wsValidDay()) {
            state.setInputError();
            state.setDayFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_DAY_MUST_BE_1_TO_31);
            return;
        }

        state.setDayFlag(EditFlag.ISVALID);
    }

    /**
     * {@code EDIT-DAY-MONTH-YEAR} - CSUTLDPY L209-L279: the combination checks no single-field edit can
     * make.
     *
     * @param state the working storage; must not be {@code null}
     * @return {@code true} if control fell through to {@code EDIT-DATE-LE}, {@code false} if the paragraph
     *     took one of its four {@code GO TO EDIT-DATE-CCYYMMDD-EXIT} branches
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public boolean editDayMonthYear(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        if (!state.ws31DayMonth() && state.wsDay31()) {
            state.setInputError();
            state.setDayFlag(EditFlag.NOT_OK);
            state.setMonthFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_CANNOT_HAVE_31_DAYS);
            return false;
        }

        if (state.wsFebruary() && state.wsDay30()) {
            state.setInputError();
            state.setDayFlag(EditFlag.NOT_OK);
            state.setMonthFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_CANNOT_HAVE_30_DAYS);
            return false;
        }

        if (state.wsFebruary() && state.wsDay29()) {
            if (state.yyN() == CENTURY_YEAR_YY) {
                state.setDivBy(LEAP_DIVISOR_CENTURY);
            } else {
                state.setDivBy(LEAP_DIVISOR_ORDINARY);
            }

            int year = state.ccyyN();
            int divisor = state.divBy();
            state.setDivisionResult(year / divisor, year % divisor);

            if (state.remainder() != LEAP_REMAINDER_OK) {
                state.setInputError();
                state.setDayFlag(EditFlag.NOT_OK);
                state.setMonthFlag(EditFlag.NOT_OK);
                state.setYearFlag(EditFlag.NOT_OK);
                stringErrorMessage(state, MSG_NOT_A_LEAP_YEAR);
                return false;
            }
        }

        if (!state.wsEditDateIsValid()) {
            return false;
        }

        return true;
    }

    /**
     * {@code EDIT-DATE-LE} - CSUTLDPY L284-L321: the last resort, "in case some one managed to enter a bad
     * date that passsed all the edits above" (L286-L287, the source's own spelling).
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalArgumentException if the service returns other than eighty bytes
     */
    public void editDateLe(EditDateState state) {
        Objects.requireNonNull(state, "An EditDateState is required");

        state.initializeDateValidationResult();
        state.setDateFormat(WS_DATE_FORMAT_VALUE);

        DateValidationResult result = dateUtility.validateDate(
                codec.movePicX(state.editDateCcyymmdd(), DateUtilityJob.LS_DATE_LENGTH),
                codec.movePicX(state.dateFormat(), DateUtilityJob.LS_DATE_FORMAT_LENGTH));
        state.acceptDateValidationResult(result.messageBytes());

        if (state.wsSeverityN() != SEVERITY_OK) {
            state.setInputError();
            state.setDayFlag(EditFlag.NOT_OK);
            state.setMonthFlag(EditFlag.NOT_OK);
            state.setYearFlag(EditFlag.NOT_OK);
            if (state.returnMsgOff()) {
                state.stringIntoReturnMessage(
                        trim(state.editVariableName()),
                        MSG_VALIDATION_ERROR_SEV_CODE,
                        state.wsSeverity(),
                        MSG_MESSAGE_CODE,
                        state.wsMsgNo());
            }
            return;
        }

        if (!state.inputError()) {
            state.setDayFlag(EditFlag.ISVALID);
        }
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH} - CSUTLDPY L341-L368, reading this validator's own clock.
     *
     * @param state the working storage; must not be {@code null}
     * @throws NullPointerException if {@code state} is {@code null}
     */
    public void editDateOfBirth(EditDateState state) {
        editDateOfBirth(state, clock);
    }

    /**
     * {@code EDIT-DATE-OF-BIRTH} - CSUTLDPY L341-L368, against an explicit clock.
     *
     * @param state the working storage, holding the eight date bytes to test; must not be {@code null}
     * @param clock the clock {@code FUNCTION CURRENT-DATE} reads; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void editDateOfBirth(EditDateState state, Clock clock) {
        Objects.requireNonNull(state, "An EditDateState is required");
        Objects.requireNonNull(clock, "A Clock is required to evaluate FUNCTION CURRENT-DATE");

        state.setCurrentDateYyyymmdd(currentDateIntrinsic(clock));

        state.setEditDateBinary(integerOfDate(state.ccyymmddN()));
        state.setCurrentDateBinary(integerOfDate(state.currentDateYyyymmddN()));

        if (state.currentDateBinary() <= state.editDateBinary()) {
            state.setInputError();
            state.setDayFlag(EditFlag.NOT_OK);
            state.setMonthFlag(EditFlag.NOT_OK);
            state.setYearFlag(EditFlag.NOT_OK);
            stringErrorMessage(state, MSG_CANNOT_BE_IN_THE_FUTURE);
            return;
        }

    }

    /**
     * {@code FUNCTION INTEGER-OF-DATE} - CSUTLDPY L346 and L348.
     *
     * @param standardDate the date as {@code 9(8)}, that is {@code year * 10000 + month * 100 + day}
     * @return the integer date, or {@link #INTEGER_OF_DATE_UNDEFINED} if the argument is not a standard
     *     date in the supported range
     */
    public static int integerOfDate(int standardDate) {
        if (standardDate < INTEGER_OF_DATE_LOWEST_ARGUMENT
                || standardDate > INTEGER_OF_DATE_HIGHEST_ARGUMENT) {
            return INTEGER_OF_DATE_UNDEFINED;
        }
        int year = standardDate / STANDARD_DATE_YEAR_DIVISOR;
        int month = standardDate / STANDARD_DATE_MONTH_DIVISOR % STANDARD_DATE_COMPONENT_MODULUS;
        int day = standardDate % STANDARD_DATE_COMPONENT_MODULUS;
        try {
            return (int) (LocalDate.of(year, month, day).toEpochDay())
                    + INTEGER_OF_DATE_EPOCH_OFFSET;
        } catch (DateTimeException notAStandardDate) {
            return INTEGER_OF_DATE_UNDEFINED;
        }
    }

    /**
     * {@code FUNCTION CURRENT-DATE} - CSUTLDPY L343.
     *
     * <p>The caller moves the result into {@code PIC X(8)}, which discards the last thirteen; they are
     * produced anyway so the truncation is a real move rather than an assumption, and so a test can assert
     * the width.
     *
     * @param clock the clock to read, once; must not be {@code null}
     * @return exactly {@link #CURRENT_DATE_INTRINSIC_LENGTH} characters
     * @throws NullPointerException if {@code clock} is {@code null}
     */
    public String currentDateIntrinsic(Clock clock) {
        Objects.requireNonNull(clock, "A Clock is required: FUNCTION CURRENT-DATE is read from a "
                + "supplied clock exactly once so that its value is deterministic");
        ZonedDateTime now = ZonedDateTime.now(clock);
        int offsetMinutes = now.getOffset().getTotalSeconds() / SECONDS_PER_MINUTE;
        char offsetSign = offsetMinutes < 0 ? OFFSET_SIGN_BEHIND : OFFSET_SIGN_AHEAD;
        int absoluteOffsetMinutes = Math.abs(offsetMinutes);
        return codec.concatenateDelimitedBySize(
                codec.movePic9(now.getYear() * STANDARD_DATE_YEAR_DIVISOR
                                + now.getMonthValue() * STANDARD_DATE_MONTH_DIVISOR
                                + now.getDayOfMonth(),
                        WS_EDIT_DATE_CCYYMMDD_LENGTH),
                codec.movePic9(now.getHour(), TIME_COMPONENT_LENGTH),
                codec.movePic9(now.getMinute(), TIME_COMPONENT_LENGTH),
                codec.movePic9(now.getSecond(), TIME_COMPONENT_LENGTH),
                codec.movePic9(now.getNano() / NANOS_PER_HUNDREDTH, TIME_COMPONENT_LENGTH),
                String.valueOf(offsetSign),
                codec.movePic9(absoluteOffsetMinutes / MINUTES_PER_HOUR, TIME_COMPONENT_LENGTH),
                codec.movePic9(absoluteOffsetMinutes % MINUTES_PER_HOUR, TIME_COMPONENT_LENGTH));
    }

    /**
     * {@code FUNCTION TRIM} - CSUTLDPY L36, L53, L78, L100, L118, L135, L160, L179, L194, L220, L235, L265,
     * L307 and L362.
     *
     * @param value the sending item; must not be {@code null}
     * @return {@code value} without its leading or trailing spaces, possibly empty
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String trim(String value) {
        Objects.requireNonNull(value, "FUNCTION TRIM requires an argument");
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == SPACE) {
            start++;
        }
        while (end > start && value.charAt(end - 1) == SPACE) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * {@code FUNCTION TEST-NUMVAL} - CSUTLDPY L126 and L170.
     *
     * @param image the argument to test; must not be {@code null}
     * @return zero if the argument is a valid operand of {@link #numval(String)}; otherwise the one-based
     *     position of the first character in error
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static int testNumval(String image) {
        return NumericIntrinsics.testNumval(image);
    }

    public static BigDecimal numval(String image) {
        return NumericIntrinsics.numval(image);
    }

    /**
     * Tests the COBOL class condition {@code IS NUMERIC} on an alphanumeric item - CSUTLDPY L48, negated.
     *
     * @param image the item's characters; must not be {@code null}
     * @return whether every character is {@code '0'} through {@code '9'} and there is at least one
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isNumericClass(String image) {
        Objects.requireNonNull(image, "A class condition requires an item to test");
        if (image.isEmpty()) {
            return false;
        }
        for (int index = 0; index < image.length(); index++) {
            if (!isDigit(image.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests a field against the figurative constant {@code SPACES} - CSUTLDPY L31, L95 and L155.
     *
     * @param image the field's characters; must not be {@code null}
     * @return whether every character is a space, and there is at least one
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isAllSpaces(String image) {
        return isEveryCharacter(image, SPACE);
    }

    /**
     * Tests a field against the figurative constant {@code LOW-VALUES} - CSUTLDPY L30, L94 and L154.
     *
     * <p>{@code LOW-VALUE} is the byte with every bit clear, which is a distinct state from a space and is
     * exactly what a CICS map delivers for a field the terminal never transmitted.
     *
     * @param image the field's characters; must not be {@code null}
     * @return whether every character is {@code LOW-VALUE}, and there is at least one
     * @throws NullPointerException if {@code image} is {@code null}
     */
    public static boolean isAllLowValues(String image) {
        return isEveryCharacter(image, LOW_VALUE);
    }

    private static boolean isEveryCharacter(String image, char character) {
        Objects.requireNonNull(image, "A figurative-constant comparison requires a field to test");
        if (image.isEmpty()) {
            return false;
        }
        for (int index = 0; index < image.length(); index++) {
            if (image.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    private static long zonedValueOf(byte[] span) {
        long value = 0;
        for (byte zonedByte : span) {
            value = value * DECIMAL_RADIX + (zonedByte & ZONED_DIGIT_MASK);
        }
        return value;
    }

    private static long storeIntoUnsigned(BigDecimal value) {
        return value.setScale(0, RoundingMode.DOWN).abs().longValue();
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private void stringErrorMessage(EditDateState state, String literal) {
        if (state.returnMsgOff()) {
            state.stringIntoReturnMessage(trim(state.editVariableName()), literal);
        }
    }
}
