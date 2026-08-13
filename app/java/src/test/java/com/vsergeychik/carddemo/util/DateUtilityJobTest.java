package com.vsergeychik.carddemo.util;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.config.CobolCharsetConfig;
import com.vsergeychik.carddemo.util.DateUtilityJob.DateValidationResult;
import com.vsergeychik.carddemo.util.DateUtilityJob.FeedbackToken;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for {@link DateUtilityJob}, the Java form of {@code app/cbl/CSUTLDTC.cbl} (157 lines) - the
 * CardDemo date-validation subprogram that wraps the IBM Language Environment service {@code CEEDAYS}.
 */
@DisplayName("DateUtilityJob - the CSUTLDTC date-validation subprogram")
class DateUtilityJobTest {
    private static final int SEVERITY_OFFSET = 0;

    private static final int SEVERITY_LENGTH = 4;

    private static final int MESG_CODE_FILLER_OFFSET = SEVERITY_OFFSET + SEVERITY_LENGTH;

    private static final int MESG_CODE_FILLER_LENGTH = 11;

    private static final int MSG_NO_OFFSET = MESG_CODE_FILLER_OFFSET + MESG_CODE_FILLER_LENGTH;

    private static final int MSG_NO_LENGTH = 4;

    private static final int FILLER_AFTER_MSG_NO_OFFSET = MSG_NO_OFFSET + MSG_NO_LENGTH;

    private static final int SINGLE_SPACE_FILLER_LENGTH = 1;

    private static final int RESULT_OFFSET =
            FILLER_AFTER_MSG_NO_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    private static final int RESULT_LENGTH = 15;

    private static final int FILLER_AFTER_RESULT_OFFSET = RESULT_OFFSET + RESULT_LENGTH;

    private static final int TST_DATE_FILLER_OFFSET =
            FILLER_AFTER_RESULT_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    private static final int TST_DATE_FILLER_LENGTH = 9;

    private static final int DATE_OFFSET = TST_DATE_FILLER_OFFSET + TST_DATE_FILLER_LENGTH;

    private static final int DATE_LENGTH = 10;

    private static final int FILLER_AFTER_DATE_OFFSET = DATE_OFFSET + DATE_LENGTH;

    private static final int MASK_USED_FILLER_OFFSET =
            FILLER_AFTER_DATE_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    private static final int MASK_USED_FILLER_LENGTH = 10;

    private static final int DATE_FMT_OFFSET = MASK_USED_FILLER_OFFSET + MASK_USED_FILLER_LENGTH;

    private static final int FILLER_AFTER_FMT_OFFSET = DATE_FMT_OFFSET + DATE_LENGTH;

    private static final int TRAILING_FILLER_OFFSET =
            FILLER_AFTER_FMT_OFFSET + SINGLE_SPACE_FILLER_LENGTH;

    private static final int TRAILING_FILLER_LENGTH = 3;

    private static final int MESSAGE_LENGTH = 80;

    private static final String MESG_CODE_LITERAL = "Mesg Code:";

    private static final String TST_DATE_LITERAL = "TstDate:";

    private static final String MASK_USED_LITERAL = "Mask used:";

    private static final String MESG_CODE_FILLER_IMAGE = "Mesg Code: ";

    private static final String TST_DATE_FILLER_IMAGE = "TstDate: ";

    private static final String MASK_USED_FILLER_IMAGE = "Mask used:";

    private static final String ONE_SPACE = " ";

    private static final String THREE_SPACES = "   ";

    private static final String RESULT_DATE_IS_VALID = "Date is valid  ";

    private static final String RESULT_INSUFFICIENT = "Insufficient   ";

    private static final String RESULT_DATEVALUE_ERROR = "Datevalue error";

    private static final String RESULT_INVALID_ERA = "Invalid Era    ";

    private static final String RESULT_UNSUPP_RANGE = "Unsupp. Range  ";

    private static final String RESULT_INVALID_MONTH = "Invalid month  ";

    private static final String RESULT_BAD_PIC_STRING = "Bad Pic String ";

    private static final String RESULT_NONNUMERIC_DATA = "Nonnumeric data";

    private static final String RESULT_YEAR_IN_ERA_ZERO = "YearInEra is 0 ";

    private static final String RESULT_DATE_IS_INVALID = "Date is invalid";

    private static final String TOKEN_FC_INVALID_DATE = "0000000000000000";

    private static final String TOKEN_FC_INSUFFICIENT_DATA = "000309CB59C3C5C5";

    private static final String TOKEN_FC_BAD_DATE_VALUE = "000309CC59C3C5C5";

    private static final String TOKEN_FC_INVALID_ERA = "000309CD59C3C5C5";

    private static final String TOKEN_FC_UNSUPP_RANGE = "000309D159C3C5C5";

    private static final String TOKEN_FC_INVALID_MONTH = "000309D559C3C5C5";

    private static final String TOKEN_FC_BAD_PIC_STRING = "000309D659C3C5C5";

    private static final String TOKEN_FC_NON_NUMERIC_DATA = "000309D859C3C5C5";

    private static final String TOKEN_FC_YEAR_IN_ERA_ZERO = "000309D959C3C5C5";

    private static final String TOKEN_UNENUMERATED_SENTINEL = "0003000059C3C5C5";

    private static final String FACILITY_ID_CEE_EBCDIC = "C3C5C5";

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private static final String FACILITY_ID_TEXT = "CEE";

    private static final int CASE_SEV_CTL_ERROR = 0x59;

    private static final String MASK_HYPHENATED = "YYYY-MM-DD";

    private static final String MASK_COMPACT = "YYYYMMDD";

    private static final String MASK_COMPACT_IMAGE = "YYYYMMDD  ";

    private static final int CALLER_SEV_CD_OFFSET = 0;

    private static final int CALLER_SEV_CD_LENGTH = 4;

    private static final int CALLER_FILLER_OFFSET = CALLER_SEV_CD_OFFSET + CALLER_SEV_CD_LENGTH;

    private static final int CALLER_FILLER_LENGTH = 11;

    private static final int CALLER_MSG_NUM_OFFSET = CALLER_FILLER_OFFSET + CALLER_FILLER_LENGTH;

    private static final int CALLER_MSG_NUM_LENGTH = 4;

    private static final int CALLER_MSG_OFFSET = CALLER_MSG_NUM_OFFSET + CALLER_MSG_NUM_LENGTH;

    private static final int CALLER_MSG_LENGTH = 61;

    private static final String ACCEPTED_SEVERITY_CODE = "0000";

    private static final String TOLERATED_MESSAGE_NUMBER = "2513";

    private static final int ACCEPTED_SEVERITY_NUMBER = 0;

    private static final String ERROR_TEXT_START_DATE = "Start Date - Not a valid date...";

    private static final String ERROR_TEXT_END_DATE = "End Date - Not a valid date...";

    private static final String ERROR_TEXT_ORIG_DATE = "Orig Date - Not a valid date...";

    private static final String ERROR_TEXT_PROC_DATE = "Proc Date - Not a valid date...";

    private static final int VSTRING_LENGTH_BYTES = 2;

    private static final int SURVIVING_TEXT_BYTES = DATE_LENGTH - VSTRING_LENGTH_BYTES;

    private static final int BYTE_MASK = 0xFF;

    private static final String SAMPLE_DATE = "2022-07-18";

    private static final Charset MESSAGE_CHARSET = StandardCharsets.US_ASCII;

    private static DateUtilityJob newService() {
        return new DateUtilityJob(MESSAGE_CHARSET);
    }

    private static int decodeSeverity(String hexToken) {
        return Integer.parseInt(hexToken.substring(0, 4), 16);
    }

    private static int decodeMessageNumber(String hexToken) {
        return Integer.parseInt(hexToken.substring(4, 8), 16);
    }

    private static String asPicNine4(int value) {
        return String.format(Locale.ROOT, "%04d", value);
    }

    private static byte[] expectedGroupMoveImage(String paddedDate) {
        final byte[] image = new byte[DATE_LENGTH];
        image[0] = (byte) ((DATE_LENGTH >>> Byte.SIZE) & BYTE_MASK);
        image[1] = (byte) (DATE_LENGTH & BYTE_MASK);
        final byte[] text = paddedDate.getBytes(MESSAGE_CHARSET);
        System.arraycopy(text, 0, image, VSTRING_LENGTH_BYTES, SURVIVING_TEXT_BYTES);
        return image;
    }

    private static String movePicX(String source, int targetLength) {
        return new FixedWidthCodec(MESSAGE_CHARSET).movePicX(source, targetLength);
    }

    private static boolean programCallerAccepts(DateValidationResult result) {
        return ACCEPTED_SEVERITY_CODE.equals(result.severityCode())
                || TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber());
    }

    private static boolean copybookCallerAccepts(DateValidationResult result) {
        return result.returnCode() == ACCEPTED_SEVERITY_NUMBER;
    }

    /**
     * One arm of the {@code EVALUATE TRUE} at {@code app/cbl/CSUTLDTC.cbl} L128-L149.
     *
     * @param armNumber the arm's 1-based position in source order, 1 through 10
     * @param cobolName the {@code 88}-level condition name, or the {@code WHEN OTHER} marker
     * @param hexToken the raw hexadecimal {@code VALUE} literal, from which the expected severity and
     *     message number are decoded rather than duplicated
     * @param inputDate an {@code LS-DATE} that drives the validator down this arm
     * @param pictureMask the {@code LS-DATE-FORMAT} accompanying {@code inputDate}
     * @param expectedResult the fifteen-character {@code WS-RESULT} image this arm stores, trailing spaces
     *     included
     */
    private record EvaluateArm(int armNumber,
                               String cobolName,
                               String hexToken,
                               String inputDate,
                               String pictureMask,
                               String expectedResult) {
        String expectedSeverityCode() {
            return asPicNine4(decodeSeverity(hexToken));
        }

        String expectedMessageNumber() {
            return asPicNine4(decodeMessageNumber(hexToken));
        }

        int expectedReturnCode() {
            return decodeSeverity(hexToken);
        }

        boolean drivenByInput() {
            return inputDate != null;
        }

        String resultThroughTheMappingSeam() {
            return DateUtilityJob.resultTextOfFeedbackToken(FeedbackToken.UNENUMERATED);
        }

        @Override
        public String toString() {
            return "arm " + armNumber + " " + cobolName + " -> '" + expectedResult + "'";
        }
    }

    private static Stream<EvaluateArm> evaluateArmsInSourceOrder() {
        return Stream.of(
                new EvaluateArm(1, "FC-INVALID-DATE", TOKEN_FC_INVALID_DATE,
                        SAMPLE_DATE, MASK_HYPHENATED, RESULT_DATE_IS_VALID),

                new EvaluateArm(2, "FC-INSUFFICIENT-DATA", TOKEN_FC_INSUFFICIENT_DATA,
                        SAMPLE_DATE, "YYYY      ", RESULT_INSUFFICIENT),

                new EvaluateArm(3, "FC-BAD-DATE-VALUE", TOKEN_FC_BAD_DATE_VALUE,
                        "2022-02-30", MASK_HYPHENATED, RESULT_DATEVALUE_ERROR),

                new EvaluateArm(4, "FC-INVALID-ERA", TOKEN_FC_INVALID_ERA,
                        "ZZ220718  ", "<CC>YYMMDD", RESULT_INVALID_ERA),

                new EvaluateArm(5, "FC-UNSUPP-RANGE", TOKEN_FC_UNSUPP_RANGE,
                        "1500-07-18", MASK_HYPHENATED, RESULT_UNSUPP_RANGE),

                new EvaluateArm(6, "FC-INVALID-MONTH", TOKEN_FC_INVALID_MONTH,
                        "2022-13-18", MASK_HYPHENATED, RESULT_INVALID_MONTH),

                new EvaluateArm(7, "FC-BAD-PIC-STRING", TOKEN_FC_BAD_PIC_STRING,
                        SAMPLE_DATE, "QQQQ-MM-DD", RESULT_BAD_PIC_STRING),

                new EvaluateArm(8, "FC-NON-NUMERIC-DATA", TOKEN_FC_NON_NUMERIC_DATA,
                        "20XX-07-18", MASK_HYPHENATED, RESULT_NONNUMERIC_DATA),

                new EvaluateArm(9, "FC-YEAR-IN-ERA-ZERO", TOKEN_FC_YEAR_IN_ERA_ZERO,
                        "0000-07-18", MASK_HYPHENATED, RESULT_YEAR_IN_ERA_ZERO),

                new EvaluateArm(10, "WHEN OTHER", TOKEN_UNENUMERATED_SENTINEL,
                        null, null, RESULT_DATE_IS_INVALID));
    }

    private static void assertFillersAreIntact(String message) {
        assertThat(message).as("the message must be exactly LS-RESULT PIC X(80)")
                .hasSize(MESSAGE_LENGTH);

        assertThat(message.substring(MESG_CODE_FILLER_OFFSET,
                MESG_CODE_FILLER_OFFSET + MESG_CODE_FILLER_LENGTH))
                .as("L45 FILLER PIC X(11) VALUE 'Mesg Code:' - literal plus one pad space")
                .isEqualTo(MESG_CODE_FILLER_IMAGE)
                .startsWith(MESG_CODE_LITERAL)
                .hasSize(MESG_CODE_FILLER_LENGTH);
        assertThat(message.substring(TST_DATE_FILLER_OFFSET,
                TST_DATE_FILLER_OFFSET + TST_DATE_FILLER_LENGTH))
                .as("L51 FILLER PIC X(09) VALUE 'TstDate:' - literal plus one pad space")
                .isEqualTo(TST_DATE_FILLER_IMAGE)
                .startsWith(TST_DATE_LITERAL)
                .hasSize(TST_DATE_FILLER_LENGTH);
        assertThat(message.substring(MASK_USED_FILLER_OFFSET,
                MASK_USED_FILLER_OFFSET + MASK_USED_FILLER_LENGTH))
                .as("L54 FILLER PIC X(10) VALUE 'Mask used:' - an exact fit, so no pad byte")
                .isEqualTo(MASK_USED_FILLER_IMAGE)
                .isEqualTo(MASK_USED_LITERAL)
                .hasSize(MASK_USED_FILLER_LENGTH);

        assertThat(message.substring(FILLER_AFTER_MSG_NO_OFFSET,
                FILLER_AFTER_MSG_NO_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L48 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(FILLER_AFTER_RESULT_OFFSET,
                FILLER_AFTER_RESULT_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L50 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(FILLER_AFTER_DATE_OFFSET,
                FILLER_AFTER_DATE_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L53 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(FILLER_AFTER_FMT_OFFSET,
                FILLER_AFTER_FMT_OFFSET + SINGLE_SPACE_FILLER_LENGTH))
                .as("L56 FILLER PIC X(01) VALUE SPACE").isEqualTo(ONE_SPACE);
        assertThat(message.substring(TRAILING_FILLER_OFFSET,
                TRAILING_FILLER_OFFSET + TRAILING_FILLER_LENGTH))
                .as("L57 FILLER PIC X(03) VALUE SPACES").isEqualTo(THREE_SPACES);
    }

    @Nested
    @DisplayName("WS-MESSAGE geometry - app/cbl/CSUTLDTC.cbl L42-L57")
    class RecordGeometry {
        @Test
        @DisplayName("the thirteen declared spans sum to exactly eighty bytes")
        void thirteenDeclaredSpansSumToExactlyEighty() {
            final int computedTotal = SEVERITY_LENGTH
                    + MESG_CODE_FILLER_LENGTH
                    + MSG_NO_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + RESULT_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + TST_DATE_FILLER_LENGTH
                    + DATE_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + MASK_USED_FILLER_LENGTH
                    + DATE_LENGTH
                    + SINGLE_SPACE_FILLER_LENGTH
                    + TRAILING_FILLER_LENGTH;

            assertThat(computedTotal)
                    .as("the thirteen WS-MESSAGE spans must fill LS-RESULT PIC X(80) exactly")
                    .isEqualTo(MESSAGE_LENGTH)
                    .isEqualTo(DateUtilityJob.LS_RESULT_LENGTH);
        }

        @Test
        @DisplayName("the caller's four-span projection also sums to exactly eighty bytes")
        void callerProjectionSumsToExactlyEighty() {
            final int computedTotal = CALLER_SEV_CD_LENGTH
                    + CALLER_FILLER_LENGTH
                    + CALLER_MSG_NUM_LENGTH
                    + CALLER_MSG_LENGTH;

            assertThat(computedTotal)
                    .as("the caller's CSUTLDTC-RESULT must also be exactly eighty bytes")
                    .isEqualTo(MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("each span sits at the absolute offset the copybook declares")
        void eachSpanSitsAtItsDeclaredAbsoluteOffset() {
            assertThat(SEVERITY_OFFSET).as("L43 WS-SEVERITY").isZero();
            assertThat(MESG_CODE_FILLER_OFFSET).as("L45 'Mesg Code:' filler").isEqualTo(4);
            assertThat(MSG_NO_OFFSET).as("L46 WS-MSG-NO").isEqualTo(15);
            assertThat(FILLER_AFTER_MSG_NO_OFFSET).as("L48 space filler").isEqualTo(19);
            assertThat(RESULT_OFFSET).as("L49 WS-RESULT").isEqualTo(20);
            assertThat(FILLER_AFTER_RESULT_OFFSET).as("L50 space filler").isEqualTo(35);
            assertThat(TST_DATE_FILLER_OFFSET).as("L51 'TstDate:' filler").isEqualTo(36);
            assertThat(DATE_OFFSET).as("L52 WS-DATE").isEqualTo(45);
            assertThat(FILLER_AFTER_DATE_OFFSET).as("L53 space filler").isEqualTo(55);
            assertThat(MASK_USED_FILLER_OFFSET).as("L54 'Mask used:' filler").isEqualTo(56);
            assertThat(DATE_FMT_OFFSET).as("L55 WS-DATE-FMT").isEqualTo(66);
            assertThat(FILLER_AFTER_FMT_OFFSET).as("L56 space filler").isEqualTo(76);
            assertThat(TRAILING_FILLER_OFFSET).as("L57 trailing filler").isEqualTo(77);
            assertThat(TRAILING_FILLER_OFFSET + TRAILING_FILLER_LENGTH)
                    .as("the last span must close the record at exactly eighty bytes")
                    .isEqualTo(MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("an independent transcription of the layout is contiguous and eighty bytes wide")
        void anIndependentTranscriptionOfTheLayoutIsContiguousAndEightyWide() {
            final FieldSpan severity =
                    FieldSpan.alphanumeric("WS-SEVERITY", SEVERITY_OFFSET, SEVERITY_LENGTH);
            final FieldSpan msgNo =
                    FieldSpan.alphanumeric("WS-MSG-NO", MSG_NO_OFFSET, MSG_NO_LENGTH);

            final RecordLayout layout = RecordLayout.of(MESSAGE_LENGTH,
                    severity,
                    severity.redefinedAs("WS-SEVERITY-N", PictureKind.UNSIGNED_NUMERIC),
                    FieldSpan.filler(MESG_CODE_FILLER_OFFSET, MESG_CODE_FILLER_LENGTH,
                            MESG_CODE_LITERAL),
                    msgNo,
                    msgNo.redefinedAs("WS-MSG-NO-N", PictureKind.UNSIGNED_NUMERIC),
                    FieldSpan.filler(FILLER_AFTER_MSG_NO_OFFSET, SINGLE_SPACE_FILLER_LENGTH,
                            ONE_SPACE),
                    FieldSpan.alphanumeric("WS-RESULT", RESULT_OFFSET, RESULT_LENGTH),
                    FieldSpan.filler(FILLER_AFTER_RESULT_OFFSET, SINGLE_SPACE_FILLER_LENGTH,
                            ONE_SPACE),
                    FieldSpan.filler(TST_DATE_FILLER_OFFSET, TST_DATE_FILLER_LENGTH,
                            TST_DATE_LITERAL),
                    FieldSpan.alphanumeric("WS-DATE", DATE_OFFSET, DATE_LENGTH),
                    FieldSpan.filler(FILLER_AFTER_DATE_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE),
                    FieldSpan.filler(MASK_USED_FILLER_OFFSET, MASK_USED_FILLER_LENGTH,
                            MASK_USED_LITERAL),
                    FieldSpan.alphanumeric("WS-DATE-FMT", DATE_FMT_OFFSET, DATE_LENGTH),
                    FieldSpan.filler(FILLER_AFTER_FMT_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE),
                    FieldSpan.filler(TRAILING_FILLER_OFFSET, TRAILING_FILLER_LENGTH, THREE_SPACES));

            assertThat(layout.recordLength()).isEqualTo(MESSAGE_LENGTH);
            assertThat(layout.hasSpan("WS-SEVERITY")).isTrue();
            assertThat(layout.hasSpan("WS-SEVERITY-N")).isTrue();
            assertThat(layout.hasSpan("WS-MSG-NO")).isTrue();
            assertThat(layout.hasSpan("WS-MSG-NO-N")).isTrue();
            assertThat(layout.hasSpan("WS-RESULT")).isTrue();
            assertThat(layout.hasSpan("WS-DATE")).isTrue();
            assertThat(layout.hasSpan("WS-DATE-FMT")).isTrue();

            assertThat(layout.span("WS-SEVERITY-N").offset())
                    .isEqualTo(layout.span("WS-SEVERITY").offset());
            assertThat(layout.span("WS-SEVERITY-N").length())
                    .isEqualTo(layout.span("WS-SEVERITY").length());
            assertThat(layout.span("WS-SEVERITY-N").redefinition()).isTrue();
            assertThat(layout.span("WS-MSG-NO-N").offset())
                    .isEqualTo(layout.span("WS-MSG-NO").offset());
            assertThat(layout.span("WS-MSG-NO-N").redefinition()).isTrue();

            assertThat(layout.span("WS-RESULT").endOffsetExclusive())
                    .isEqualTo(FILLER_AFTER_RESULT_OFFSET);
            assertThat(layout.span("WS-DATE-FMT").endOffsetExclusive())
                    .isEqualTo(FILLER_AFTER_FMT_OFFSET);
        }

        @Test
        @DisplayName("the declared linkage widths match PROCEDURE DIVISION USING at L83-L88")
        void declaredLinkageWidthsMatchTheProcedureDivisionUsingClause() {
            assertThat(DateUtilityJob.LS_DATE_LENGTH).as("LS-DATE PIC X(10)").isEqualTo(DATE_LENGTH);
            assertThat(DateUtilityJob.LS_DATE_FORMAT_LENGTH).as("LS-DATE-FORMAT PIC X(10)")
                    .isEqualTo(DATE_LENGTH);
            assertThat(DateUtilityJob.LS_RESULT_LENGTH).as("LS-RESULT PIC X(80)")
                    .isEqualTo(MESSAGE_LENGTH);
        }
    }

    @Nested
    @DisplayName("EVALUATE TRUE - all ten arms, L128-L149")
    class EvaluateArms {
        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("each arm yields its own severity, message number, result text and return code")
        void eachArmYieldsItsOwnObservableOutcome(EvaluateArm arm) {
            if (!arm.drivenByInput()) {
                assertThat(arm.resultThroughTheMappingSeam())
                        .as("WS-RESULT for %s", arm.cobolName())
                        .isEqualTo(arm.expectedResult())
                        .hasSize(RESULT_LENGTH);
                assertThat(arm.expectedSeverityCode()).isEqualTo(asPicNine4(3));
                assertThat(arm.expectedMessageNumber()).isEqualTo(asPicNine4(0));
                return;
            }
            final DateValidationResult result =
                    newService().validateDate(arm.inputDate(), arm.pictureMask());

            assertThat(result.severityCode())
                    .as("WS-SEVERITY at [0,4) for %s", arm.cobolName())
                    .isEqualTo(arm.expectedSeverityCode())
                    .hasSize(SEVERITY_LENGTH)
                    .containsOnlyDigits();

            assertThat(result.messageNumber())
                    .as("WS-MSG-NO at [15,19) for %s", arm.cobolName())
                    .isEqualTo(arm.expectedMessageNumber())
                    .hasSize(MSG_NO_LENGTH)
                    .containsOnlyDigits();

            assertThat(result.result())
                    .as("WS-RESULT at [20,35) for %s - trailing spaces are significant",
                            arm.cobolName())
                    .isEqualTo(arm.expectedResult())
                    .hasSize(RESULT_LENGTH);

            assertThat(result.returnCode())
                    .as("RETURN-CODE for %s", arm.cobolName())
                    .isEqualTo(arm.expectedReturnCode());

            assertThat(result.message()).hasSize(MESSAGE_LENGTH);
            assertThat(result.messageBytes()).hasSize(MESSAGE_LENGTH);

            assertThat(result.message().substring(RESULT_OFFSET, RESULT_OFFSET + RESULT_LENGTH))
                    .isEqualTo(arm.expectedResult());
            assertThat(result.message().substring(SEVERITY_OFFSET,
                    SEVERITY_OFFSET + SEVERITY_LENGTH)).isEqualTo(arm.expectedSeverityCode());
            assertThat(result.message().substring(MSG_NO_OFFSET, MSG_NO_OFFSET + MSG_NO_LENGTH))
                    .isEqualTo(arm.expectedMessageNumber());

            assertFillersAreIntact(result.message());

            assertThat(result.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .as("WS-DATE-FMT at [66,76) holds the clean mask on every arm")
                    .isEqualTo(movePicX(arm.pictureMask(), DATE_LENGTH));

            final byte[] dateSpan = new byte[DATE_LENGTH];
            System.arraycopy(result.messageBytes(), DATE_OFFSET, dateSpan, 0, DATE_LENGTH);
            assertThat(dateSpan)
                    .as("WS-DATE at [45,55) after the L122 group move for %s", arm.cobolName())
                    .isEqualTo(expectedGroupMoveImage(movePicX(arm.inputDate(), DATE_LENGTH)));
        }

        @Test
        @DisplayName("the table transcribes exactly ten arms, matching L128-L149")
        void theTableTranscribesExactlyTenArms() {
            final List<EvaluateArm> arms = evaluateArmsInSourceOrder().toList();

            assertThat(arms).hasSize(10);
            assertThat(arms).extracting(EvaluateArm::armNumber)
                    .as("arms must be listed in source order, 1 through 10")
                    .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
            assertThat(arms.get(arms.size() - 1).cobolName())
                    .as("WHEN OTHER must be last - it is the default, not a match")
                    .isEqualTo("WHEN OTHER");
        }

        @Test
        @DisplayName("every transcribed hex token decodes to a CEE facility and the stated severity")
        void everyTranscribedHexTokenDecodesToACeeFacility() {
            final List<EvaluateArm> namedArms = evaluateArmsInSourceOrder()
                    .filter(arm -> !"WHEN OTHER".equals(arm.cobolName()))
                    .toList();
            assertThat(namedArms).as("L62-L70 declares nine named 88-levels").hasSize(9);

            for (EvaluateArm arm : namedArms) {
                assertThat(arm.hexToken())
                        .as("%s must be sixteen hex digits - eight bytes", arm.cobolName())
                        .hasSize(16);
                if (TOKEN_FC_INVALID_DATE.equals(arm.hexToken())) {
                    assertThat(decodeSeverity(arm.hexToken())).isZero();
                    assertThat(decodeMessageNumber(arm.hexToken())).isZero();
                    continue;
                }
                assertThat(decodeSeverity(arm.hexToken()))
                        .as("%s severity", arm.cobolName()).isEqualTo(3);
                assertThat(decodeMessageNumber(arm.hexToken()))
                        .as("%s message number", arm.cobolName()).isGreaterThanOrEqualTo(2507);
                assertThat(Integer.parseInt(arm.hexToken().substring(8, 10), 16))
                        .as("%s CASE-SEV-CTL", arm.cobolName()).isEqualTo(CASE_SEV_CTL_ERROR);
                assertThat(arm.hexToken().substring(10, 16))
                        .as("%s FACILITY-ID", arm.cobolName()).isEqualTo(FACILITY_ID_CEE_EBCDIC);
            }

            final byte[] facility = {(byte) 0xC3, (byte) 0xC5, (byte) 0xC5};
            assertThat(new String(facility, EBCDIC)).isEqualTo(FACILITY_ID_TEXT);
        }

        @Test
        @DisplayName("the nine declared token values are mutually exclusive")
        void theNineDeclaredTokenValuesAreMutuallyExclusive() {
            final Set<String> distinct = new LinkedHashSet<>(List.of(
                    TOKEN_FC_INVALID_DATE, TOKEN_FC_INSUFFICIENT_DATA, TOKEN_FC_BAD_DATE_VALUE,
                    TOKEN_FC_INVALID_ERA, TOKEN_FC_UNSUPP_RANGE, TOKEN_FC_INVALID_MONTH,
                    TOKEN_FC_BAD_PIC_STRING, TOKEN_FC_NON_NUMERIC_DATA, TOKEN_FC_YEAR_IN_ERA_ZERO));
            assertThat(distinct).hasSize(9);

            assertThat(distinct).doesNotContain(TOKEN_UNENUMERATED_SENTINEL);
            assertThat(decodeSeverity(TOKEN_UNENUMERATED_SENTINEL)).isEqualTo(3);
            assertThat(decodeMessageNumber(TOKEN_UNENUMERATED_SENTINEL)).isZero();
        }
    }

    @Nested
    @DisplayName("EVALUATE ordering and 88-level truth states")
    class EvaluateOrdering {
        @Test
        @DisplayName("the ten arms yield ten distinct result texts, so no arm shadows another")
        void theTenArmsYieldTenDistinctResultTexts() {
            final DateUtilityJob service = newService();
            final Set<String> observed = new LinkedHashSet<>();
            final Set<String> expected = new LinkedHashSet<>();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                observed.add(arm.drivenByInput()
                        ? service.validateDate(arm.inputDate(), arm.pictureMask()).result()
                        : arm.resultThroughTheMappingSeam());
                expected.add(arm.expectedResult());
            }

            assertThat(expected).as("L128-L148 declares ten distinct texts").hasSize(10);
            assertThat(observed).as("each arm must produce its own text, in source order")
                    .hasSize(10)
                    .containsExactlyElementsOf(expected);
        }

        @ParameterizedTest(name = "[{index}] {0} must not answer with another arm''s text")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("G50 - every 88-level is driven both true and false")
        void every88LevelIsDrivenBothTrueAndFalse(EvaluateArm arm) {
            final DateUtilityJob service = newService();

            assertThat(arm.drivenByInput()
                    ? service.validateDate(arm.inputDate(), arm.pictureMask()).result()
                    : arm.resultThroughTheMappingSeam())
                    .as("%s must be TRUE for its own token", arm.cobolName())
                    .isEqualTo(arm.expectedResult());

            for (EvaluateArm other : evaluateArmsInSourceOrder().toList()) {
                if (other.armNumber() == arm.armNumber() || !other.drivenByInput()) {
                    continue;
                }
                assertThat(service.validateDate(other.inputDate(), other.pictureMask()).result())
                        .as("%s must be FALSE for the input that drives %s",
                                arm.cobolName(), other.cobolName())
                        .isNotEqualTo(arm.expectedResult())
                        .isEqualTo(other.expectedResult());
            }
        }

        @Test
        @DisplayName("WHEN OTHER is the default: an unenumerated token yields 'Date is invalid' only")
        void whenOtherIsTheDefaultAndYieldsDateIsInvalidOnly() {
            final String whenOther =
                    DateUtilityJob.resultTextOfFeedbackToken(FeedbackToken.UNENUMERATED);

            assertThat(whenOther)
                    .as("the WHEN OTHER arm at L147-L148")
                    .isEqualTo(RESULT_DATE_IS_INVALID)
                    .hasSize(RESULT_LENGTH);

            assertThat(whenOther).isNotIn(
                    RESULT_DATE_IS_VALID, RESULT_INSUFFICIENT, RESULT_DATEVALUE_ERROR,
                    RESULT_INVALID_ERA, RESULT_UNSUPP_RANGE, RESULT_INVALID_MONTH,
                    RESULT_BAD_PIC_STRING, RESULT_NONNUMERIC_DATA, RESULT_YEAR_IN_ERA_ZERO);

            for (FeedbackToken named : FeedbackToken.values()) {
                if (named == FeedbackToken.UNENUMERATED) {
                    continue;
                }
                assertThat(DateUtilityJob.resultTextOfFeedbackToken(named))
                        .as("%s must not fall through to WHEN OTHER", named)
                        .isNotEqualTo(RESULT_DATE_IS_INVALID);
            }
        }

        @Test
        @DisplayName("no input can reach WHEN OTHER: every shape failure is a documented code")
        void noInputCanReachWhenOther() {
            final DateUtilityJob service = newService();

            assertThat(service.validateDate("2022/07/18", MASK_HYPHENATED).result())
                    .as("a delimiter VARIANT is accepted where the picture declares a delimiter")
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(service.validateDate("AD22071899", "<CC>YYMMDD").result())
                    .as("characters beyond everything the picture described are ignored")
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(service.validateDate("2022-07-1", MASK_HYPHENATED).result())
                    .as("an omitted leading zero is accepted, as CEEDAYS' own '6/2/88' example shows")
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(service.validateDate("20A2-07-18", MASK_HYPHENATED).result())
                    .as("a letter where the picture located digits IS a shape failure: CEE2520")
                    .isEqualTo(RESULT_NONNUMERIC_DATA);

            for (String candidate : List.of("2022/07/18", "AD22071899", "2022-07-1", "20A2-07-18",
                    "          ", "", "2022-07-18")) {
                final DateValidationResult observed = service.validateDate(candidate,
                        candidate.length() == 10 && candidate.startsWith("AD")
                                ? "<CC>YYMMDD" : MASK_HYPHENATED);
                assertThat(observed.severityCode() + observed.messageNumber())
                        .as("'%s' must not report the impossible 0003/0000 pair", candidate)
                        .isNotEqualTo(asPicNine4(3) + asPicNine4(0));
                assertFillersAreIntact(observed.message());
            }
        }

        @Test
        @DisplayName("the success arm is FC-INVALID-DATE - the inverted name is preserved, not flipped")
        void theSuccessArmIsTheInvertedlyNamedFcInvalidDate() {
            assertThat(decodeSeverity(TOKEN_FC_INVALID_DATE))
                    .as("FC-INVALID-DATE carries severity 0 - CEE000, a SUCCESSFUL conversion")
                    .isZero();
            assertThat(decodeMessageNumber(TOKEN_FC_INVALID_DATE)).isZero();

            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(result.result())
                    .as("the arm named 'INVALID' is the one that reports the date VALID")
                    .isEqualTo(RESULT_DATE_IS_VALID)
                    .isNotEqualTo(RESULT_DATE_IS_INVALID);
            assertThat(result.returnCode()).isZero();
        }
    }

    @Nested
    @DisplayName("REDEFINES overlays - L43/L44 and L46/L47")
    class RedefinesRoundTrips {
        @Test
        @DisplayName("a write through the numeric view is visible through the character view")
        void aWriteThroughTheNumericViewIsVisibleThroughTheCharacterView() {
            final FixedWidthCodec codec = new FixedWidthCodec(MESSAGE_CHARSET);
            final FieldSpan character =
                    FieldSpan.alphanumeric("WS-SEVERITY", SEVERITY_OFFSET, SEVERITY_LENGTH);
            final FieldSpan numeric =
                    character.redefinedAs("WS-SEVERITY-N", PictureKind.UNSIGNED_NUMERIC);
            final FixedWidthRecord record =
                    FixedWidthRecord.forLayout(RecordLayout.of(SEVERITY_LENGTH, character, numeric),
                            MESSAGE_CHARSET);

            assertThat(numeric.offset()).isEqualTo(character.offset());
            assertThat(numeric.length()).isEqualTo(character.length());
            assertThat(numeric.redefinition()).isTrue();
            assertThat(character.redefinition()).isFalse();

            for (int value : new int[] {0, 3, 2507, 2508, 2509, 2513, 2517, 2518, 2520, 2521}) {
                codec.writePic9(record, numeric, value);

                final String characterImage = codec.readPicX(record, character);
                assertThat(characterImage)
                        .as("PIC 9(4) left-zero-fills, so %d must render as four digits", value)
                        .hasSize(SEVERITY_LENGTH)
                        .isEqualTo(asPicNine4(value));

                assertThat(codec.readPic9AsInt(record, numeric)).isEqualTo(value);
                assertThat(Integer.parseInt(characterImage)).isEqualTo(value);
            }
        }

        @Test
        @DisplayName("PIC 9(4) zero-fills rather than space-padding, so 3 becomes '0003'")
        void picNineFourZeroFillsRatherThanSpacePadding() {
            assertThat(asPicNine4(3)).isEqualTo("0003").isNotEqualTo("3").isNotEqualTo("   3");
            assertThat(asPicNine4(0)).isEqualTo("0000");
            assertThat(asPicNine4(2513)).isEqualTo("2513");

            final DateValidationResult error = newService().validateDate("2022-13-18", MASK_HYPHENATED);
            assertThat(error.severityCode()).isEqualTo("0003");
            assertThat(error.messageNumber()).isEqualTo("2517");

            final DateValidationResult success = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(success.severityCode()).isEqualTo("0000");
            assertThat(success.messageNumber()).isEqualTo("0000");
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("both overlay views agree on every arm the service can produce")
        void bothOverlayViewsAgreeOnEveryArm(EvaluateArm arm) {
            if (!arm.drivenByInput()) {
                assertThat(Integer.parseInt(arm.expectedSeverityCode()))
                        .isEqualTo(arm.expectedReturnCode());
                return;
            }
            final DateValidationResult result =
                    newService().validateDate(arm.inputDate(), arm.pictureMask());

            assertThat(Integer.parseInt(result.severityCode()))
                    .as("WS-SEVERITY parsed numerically must equal WS-SEVERITY-N")
                    .isEqualTo(result.returnCode());
            assertThat(asPicNine4(result.returnCode())).isEqualTo(result.severityCode());

            assertThat(Integer.parseInt(result.messageNumber()))
                    .isEqualTo(decodeMessageNumber(arm.hexToken()));
        }
    }

    @Nested
    @DisplayName("RETURN-CODE at L98, and the absence of an abend")
    class ReturnCodeAndAbend {
        @Test
        @DisplayName("the return code is the severity: 0 for the valid token, 3 for all eight errors")
        void theReturnCodeIsTheSeverity() {
            final DateUtilityJob service = newService();

            assertThat(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).returnCode())
                    .as("FC-INVALID-DATE is the SUCCESS token, so RETURN-CODE is 0")
                    .isZero();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (arm.armNumber() == 1) {
                    continue;
                }
                if (!arm.drivenByInput()) {
                    assertThat(arm.expectedReturnCode())
                            .as("the %s sentinel declares severity 3", arm.cobolName())
                            .isEqualTo(3);
                    continue;
                }
                assertThat(service.validateDate(arm.inputDate(), arm.pictureMask()).returnCode())
                        .as("%s carries severity 3", arm.cobolName())
                        .isEqualTo(3);
            }
        }

        @Test
        @DisplayName("return code 3 sits outside the batch {0,4,8,12} set - documented, not remapped")
        void returnCodeThreeSitsOutsideTheBatchAbendCodeSet() {
            final DateValidationResult error = newService().validateDate("2022-13-18", MASK_HYPHENATED);

            assertThat(error.returnCode())
                    .as("CSUTLDTC moves the SEVERITY, not an APPL-RESULT constant")
                    .isEqualTo(3)
                    .isNotIn(0, 4, 8, 12);

            final Set<Integer> observed = new LinkedHashSet<>();
            final DateUtilityJob service = newService();
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (!arm.drivenByInput()) {
                    continue;
                }
                observed.add(service.validateDate(arm.inputDate(), arm.pictureMask()).returnCode());
            }
            assertThat(observed).containsExactlyInAnyOrder(0, 3);
        }

        @Test
        @DisplayName("G35 is N/A: no AbendException is ever raised, for any of the ten arms")
        void noAbendIsEverRaisedForAnyEvaluateArm() {
            final DateUtilityJob service = newService();
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (!arm.drivenByInput()) {
                    continue;
                }
                assertThatCode(() -> service.validateDate(arm.inputDate(), arm.pictureMask()))
                        .as("%s must return normally - CSUTLDTC never abends", arm.cobolName())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("the L122 group-move defect, reproduced deliberately")
    class GroupMoveTrap {
        @Test
        @DisplayName("WS-DATE holds a big-endian halfword then only the first eight date characters")
        void wsDateHoldsAHalfwordThenOnlyTheFirstEightDateCharacters() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final byte[] message = result.messageBytes();

            final byte[] actual = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_OFFSET, actual, 0, DATE_LENGTH);

            assertThat(actual)
                    .as("[45,55) = 00 0A '2' '0' '2' '2' '-' '0' '7' '-'")
                    .isEqualTo(expectedGroupMoveImage(SAMPLE_DATE));

            assertThat(actual[0]).as("halfword high-order byte - BIG-endian").isEqualTo((byte) 0x00);
            assertThat(actual[1]).as("halfword low-order byte - decimal 10").isEqualTo((byte) 0x0A);
            assertThat(new String(actual, VSTRING_LENGTH_BYTES, SURVIVING_TEXT_BYTES, MESSAGE_CHARSET))
                    .as("only the first eight characters of '2022-07-18' survive")
                    .isEqualTo("2022-07-")
                    .hasSize(SURVIVING_TEXT_BYTES);

            assertThat(new String(actual, MESSAGE_CHARSET))
                    .as("the final '18' of the input date is lost, not relocated")
                    .doesNotEndWith("18");
        }

        @Test
        @DisplayName("a little-endian halfword would be 0x0A 0x00 and is explicitly not what is stored")
        void theHalfwordIsBigEndianNotLittleEndian() {
            final byte[] image = expectedGroupMoveImage(SAMPLE_DATE);

            assertThat(new byte[] {image[0], image[1]}).isEqualTo(new byte[] {0x00, 0x0A});
            assertThat(new byte[] {image[0], image[1]}).isNotEqualTo(new byte[] {0x0A, 0x00});
        }

        @ParameterizedTest(name = "[{index}] date=''{0}''")
        @CsvSource({
            "2022-07-18",
            "1999-01-01",
            "2000-02-29",
            "0000-07-18",
            "20XX-07-18",
            "2022/07/18",
        })
        @DisplayName("the halfword prefix is invariant for every input, valid or not")
        void theHalfwordPrefixIsInvariantForEveryInput(String inputDate) {
            final byte[] message = newService().validateDate(inputDate, MASK_HYPHENATED).messageBytes();

            assertThat(message[DATE_OFFSET]).isEqualTo((byte) 0x00);
            assertThat(message[DATE_OFFSET + 1]).isEqualTo((byte) 0x0A);

            final byte[] span = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_OFFSET, span, 0, DATE_LENGTH);
            assertThat(span).isEqualTo(expectedGroupMoveImage(movePicX(inputDate, DATE_LENGTH)));
        }

        @Test
        @DisplayName("the date span is always corrupted while the mask span never is")
        void theDateSpanIsAlwaysCorruptedWhileTheMaskSpanNeverIs() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final byte[] message = result.messageBytes();

            final byte[] dateSpan = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_OFFSET, dateSpan, 0, DATE_LENGTH);
            final byte[] maskSpan = new byte[DATE_LENGTH];
            System.arraycopy(message, DATE_FMT_OFFSET, maskSpan, 0, DATE_LENGTH);

            assertThat(dateSpan)
                    .as("WS-DATE is overwritten by the L122 group move")
                    .isNotEqualTo(SAMPLE_DATE.getBytes(MESSAGE_CHARSET))
                    .isEqualTo(expectedGroupMoveImage(SAMPLE_DATE));

            assertThat(maskSpan)
                    .as("WS-DATE-FMT is never group-overwritten")
                    .isEqualTo(MASK_HYPHENATED.getBytes(MESSAGE_CHARSET));
            assertThat(result.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED);

            assertThat(dateSpan).hasSameSizeAs(maskSpan);
            assertThat(dateSpan).isNotEqualTo(maskSpan);
        }

        @Test
        @DisplayName("OUTPUT-LILLIAN never leaks into the eighty bytes")
        void outputLillianNeverLeaksIntoTheEightyBytes() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);

            final FixedWidthRecord expected =
                    new FixedWidthRecord(MESSAGE_LENGTH, MESSAGE_CHARSET);
            expected.writeString(SEVERITY_OFFSET, SEVERITY_LENGTH, asPicNine4(0));
            expected.writeString(MESG_CODE_FILLER_OFFSET, MESG_CODE_FILLER_LENGTH,
                    MESG_CODE_FILLER_IMAGE);
            expected.writeString(MSG_NO_OFFSET, MSG_NO_LENGTH, asPicNine4(0));
            expected.writeString(FILLER_AFTER_MSG_NO_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(RESULT_OFFSET, RESULT_LENGTH, RESULT_DATE_IS_VALID);
            expected.writeString(FILLER_AFTER_RESULT_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(TST_DATE_FILLER_OFFSET, TST_DATE_FILLER_LENGTH,
                    TST_DATE_FILLER_IMAGE);
            expected.writeBytes(DATE_OFFSET, expectedGroupMoveImage(SAMPLE_DATE));
            expected.writeString(FILLER_AFTER_DATE_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(MASK_USED_FILLER_OFFSET, MASK_USED_FILLER_LENGTH,
                    MASK_USED_FILLER_IMAGE);
            expected.writeString(DATE_FMT_OFFSET, DATE_LENGTH, MASK_HYPHENATED);
            expected.writeString(FILLER_AFTER_FMT_OFFSET, SINGLE_SPACE_FILLER_LENGTH, ONE_SPACE);
            expected.writeString(TRAILING_FILLER_OFFSET, TRAILING_FILLER_LENGTH, THREE_SPACES);

            assertThat(result.messageBytes())
                    .as("every one of the eighty bytes is accounted for by the thirteen spans, so "
                            + "nothing - least of all OUTPUT-LILLIAN - can have leaked in")
                    .isEqualTo(expected.toByteArray());

            final byte[] actual = result.messageBytes();
            for (int offset = 0; offset < actual.length; offset++) {
                if (offset == DATE_OFFSET || offset == DATE_OFFSET + 1) {
                    continue;
                }
                assertThat(actual[offset])
                        .as("byte %d must be a printable single-byte character", offset)
                        .isGreaterThanOrEqualTo((byte) 0x20);
            }
        }
    }

    @Nested
    @DisplayName("both 80-byte projections and the five call sites' acceptance rules")
    class CallerProjections {
        @Test
        @DisplayName("the thirteen-field producer projection decodes every span at its own offset")
        void theThirteenFieldProducerProjectionDecodesEverySpan() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final String message = result.message();

            assertThat(message.substring(SEVERITY_OFFSET, SEVERITY_OFFSET + SEVERITY_LENGTH))
                    .isEqualTo("0000");
            assertThat(message.substring(MSG_NO_OFFSET, MSG_NO_OFFSET + MSG_NO_LENGTH))
                    .isEqualTo("0000");
            assertThat(message.substring(RESULT_OFFSET, RESULT_OFFSET + RESULT_LENGTH))
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(message.substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED);
            assertFillersAreIntact(message);

            assertThat(result.severityCode())
                    .isEqualTo(message.substring(SEVERITY_OFFSET, SEVERITY_OFFSET + SEVERITY_LENGTH));
            assertThat(result.messageNumber())
                    .isEqualTo(message.substring(MSG_NO_OFFSET, MSG_NO_OFFSET + MSG_NO_LENGTH));
            assertThat(result.result())
                    .isEqualTo(message.substring(RESULT_OFFSET, RESULT_OFFSET + RESULT_LENGTH));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @MethodSource("com.vsergeychik.carddemo.util.DateUtilityJobTest#evaluateArmsInSourceOrder")
        @DisplayName("the four-field caller projection reads the same severity and message number")
        void theFourFieldCallerProjectionReadsTheSameValues(EvaluateArm arm) {
            if (!arm.drivenByInput()) {
                return;
            }
            final DateValidationResult result =
                    newService().validateDate(arm.inputDate(), arm.pictureMask());
            final String message = result.message();

            final String callerSevCd = message.substring(CALLER_SEV_CD_OFFSET,
                    CALLER_SEV_CD_OFFSET + CALLER_SEV_CD_LENGTH);
            final String callerFiller = message.substring(CALLER_FILLER_OFFSET,
                    CALLER_FILLER_OFFSET + CALLER_FILLER_LENGTH);
            final String callerMsgNum = message.substring(CALLER_MSG_NUM_OFFSET,
                    CALLER_MSG_NUM_OFFSET + CALLER_MSG_NUM_LENGTH);
            final String callerMsg = message.substring(CALLER_MSG_OFFSET,
                    CALLER_MSG_OFFSET + CALLER_MSG_LENGTH);

            assertThat(callerSevCd).isEqualTo(arm.expectedSeverityCode());
            assertThat(callerMsgNum).isEqualTo(arm.expectedMessageNumber());

            assertThat(callerFiller).isEqualTo(MESG_CODE_FILLER_IMAGE);

            assertThat(CALLER_MSG_OFFSET).isEqualTo(19);
            assertThat(CALLER_MSG_OFFSET + CALLER_MSG_LENGTH).isEqualTo(MESSAGE_LENGTH);
            assertThat(callerMsg).hasSize(CALLER_MSG_LENGTH);
            assertThat(callerMsg).startsWith(ONE_SPACE + arm.expectedResult());
            assertThat(callerMsg).endsWith(THREE_SPACES);

            assertThat(callerSevCd).isEqualTo(result.severityCode());
            assertThat(callerMsgNum).isEqualTo(result.messageNumber());
        }

        @Test
        @DisplayName("the service never special-cases 2513 - accept/reject policy belongs to the caller")
        void theServiceNeverSpecialCasesMessage2513() {
            final DateValidationResult unsuppRange =
                    newService().validateDate("1500-07-18", MASK_HYPHENATED);
            final DateValidationResult otherError =
                    newService().validateDate("2022-13-18", MASK_HYPHENATED);

            assertThat(unsuppRange.messageNumber()).isEqualTo(TOLERATED_MESSAGE_NUMBER);
            assertThat(unsuppRange.severityCode())
                    .as("2513 carries severity 3 exactly like every other error")
                    .isEqualTo(otherError.severityCode());
            assertThat(unsuppRange.returnCode())
                    .as("2513 yields the same RETURN-CODE as every other error")
                    .isEqualTo(otherError.returnCode());
            assertThat(unsuppRange.message()).hasSameSizeAs(otherError.message());
            assertFillersAreIntact(unsuppRange.message());

            assertThat(ACCEPTED_SEVERITY_CODE.equals(unsuppRange.severityCode()))
                    .as("'0000'-equality is FALSE for 2513").isFalse();
            assertThat(TOLERATED_MESSAGE_NUMBER.equals(unsuppRange.messageNumber()))
                    .as("'2513'-equality is TRUE for 2513").isTrue();
        }

        @Test
        @DisplayName("the program callers tolerate 2513 while the copybook caller does not")
        void theProgramCallersTolerate2513WhileTheCopybookCallerDoesNot() {
            final DateValidationResult unsuppRange =
                    newService().validateDate("1500-07-18", MASK_HYPHENATED);

            assertThat(programCallerAccepts(unsuppRange))
                    .as("CORPT00C and COTRN02C accept 2513 in spite of severity 3")
                    .isTrue();

            assertThat(copybookCallerAccepts(unsuppRange))
                    .as("CSUTLDPY rejects 2513, because its test is on the severity alone")
                    .isFalse();

            final DateUtilityJob service = newService();
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (!arm.drivenByInput()) {
                    continue;
                }
                final DateValidationResult result =
                        service.validateDate(arm.inputDate(), arm.pictureMask());
                final boolean disagreement =
                        programCallerAccepts(result) != copybookCallerAccepts(result);
                assertThat(disagreement)
                        .as("the two acceptance rules may differ ONLY on message 2513, not on %s",
                                arm.cobolName())
                        .isEqualTo(TOLERATED_MESSAGE_NUMBER.equals(result.messageNumber()));
            }
        }

        @Test
        @DisplayName("both callers accept the valid token and reject every non-2513 error")
        void bothCallersAcceptTheValidTokenAndRejectEveryNon2513Error() {
            final DateUtilityJob service = newService();

            final DateValidationResult valid = service.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(programCallerAccepts(valid)).isTrue();
            assertThat(copybookCallerAccepts(valid)).isTrue();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (arm.armNumber() == 1 || arm.armNumber() == 5 || !arm.drivenByInput()) {
                    continue;
                }
                final DateValidationResult error =
                        service.validateDate(arm.inputDate(), arm.pictureMask());
                assertThat(programCallerAccepts(error))
                        .as("%s must be rejected by the program callers", arm.cobolName()).isFalse();
                assertThat(copybookCallerAccepts(error))
                        .as("%s must be rejected by the copybook caller", arm.cobolName()).isFalse();
            }
        }

        @Test
        @DisplayName("the four caller rejection texts are verbatim")
        void theFourCallerRejectionTextsAreVerbatim() {
            assertThat(ERROR_TEXT_START_DATE).isEqualTo("Start Date - Not a valid date...");
            assertThat(ERROR_TEXT_END_DATE).isEqualTo("End Date - Not a valid date...");
            assertThat(ERROR_TEXT_ORIG_DATE).isEqualTo("Orig Date - Not a valid date...");
            assertThat(ERROR_TEXT_PROC_DATE).isEqualTo("Proc Date - Not a valid date...");

            final List<String> texts = List.of(ERROR_TEXT_START_DATE, ERROR_TEXT_END_DATE,
                    ERROR_TEXT_ORIG_DATE, ERROR_TEXT_PROC_DATE);
            assertThat(texts).allSatisfy(text -> assertThat(text).endsWith(" - Not a valid date..."));
            assertThat(new LinkedHashSet<>(texts)).as("all four must be distinct").hasSize(4);
        }

        @Test
        @DisplayName("both real masks render at their declared ten-byte width")
        void bothRealMasksRenderAtTheirDeclaredTenByteWidth() {
            final DateValidationResult hyphenated =
                    newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(hyphenated.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED)
                    .hasSize(DATE_LENGTH);

            final DateValidationResult compact = newService().validateDate("20220718  ", MASK_COMPACT);
            assertThat(compact.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_COMPACT_IMAGE)
                    .hasSize(DATE_LENGTH);
            assertThat(MASK_COMPACT_IMAGE).isEqualTo(movePicX(MASK_COMPACT, DATE_LENGTH));
            assertThat(MASK_COMPACT).hasSize(8);

            assertThat(compact.severityCode()).isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(compact.result()).isEqualTo(RESULT_DATE_IS_VALID);
        }
    }

    @Nested
    @DisplayName("statelessness - three collaborators share one singleton")
    class Statelessness {
        @Test
        @DisplayName("repeated calls on one instance depend only on their own arguments")
        void repeatedCallsOnOneInstanceDependOnlyOnTheirOwnArguments() {
            final DateUtilityJob shared = newService();
            final List<String> firstPass = new ArrayList<>();
            final List<String> secondPass = new ArrayList<>();

            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (arm.drivenByInput()) {
                    firstPass.add(shared.validateDate(arm.inputDate(), arm.pictureMask()).message());
                }
            }
            for (EvaluateArm arm : evaluateArmsInSourceOrder().toList()) {
                if (arm.drivenByInput()) {
                    secondPass.add(shared.validateDate(arm.inputDate(), arm.pictureMask()).message());
                }
            }

            assertThat(secondPass)
                    .as("a second pass over one instance must reproduce the first exactly")
                    .containsExactlyElementsOf(firstPass);
        }

        @Test
        @DisplayName("interleaved calls never bleed a stale result, severity or mask through")
        void interleavedCallsNeverBleedThrough() {
            final DateUtilityJob shared = newService();

            final DateValidationResult a1 = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final DateValidationResult b1 = shared.validateDate("20220718  ", MASK_COMPACT);
            final DateValidationResult c1 = shared.validateDate("2022-13-18", MASK_HYPHENATED);
            final DateValidationResult a2 = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final DateValidationResult b2 = shared.validateDate("20220718  ", MASK_COMPACT);
            final DateValidationResult c2 = shared.validateDate("2022-13-18", MASK_HYPHENATED);

            assertThat(a2.message()).isEqualTo(a1.message());
            assertThat(b2.message()).isEqualTo(b1.message());
            assertThat(c2.message()).isEqualTo(c1.message());

            assertThat(a2.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_HYPHENATED);
            assertThat(b2.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(MASK_COMPACT_IMAGE);

            assertThat(a2.result()).isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(c2.result()).isEqualTo(RESULT_INVALID_MONTH);
            assertThat(a2.returnCode()).isZero();
            assertThat(c2.returnCode()).isEqualTo(3);

            assertThat(c1.severityCode()).isNotEqualTo(a1.severityCode());
            assertThat(a2.severityCode()).isEqualTo(ACCEPTED_SEVERITY_CODE);
        }

        @Test
        @DisplayName("the class declares no mutable instance field and no static mutable field")
        void theClassDeclaresNoMutableOrStaticMutableField() {
            for (Field field : DateUtilityJob.class.getDeclaredFields()) {
                final int modifiers = field.getModifiers();

                assertThat(Modifier.isFinal(modifiers))
                        .as("field '%s' must be final - no mutable state may exist here",
                                field.getName())
                        .isTrue();

                if (Modifier.isStatic(modifiers)) {
                    assertThat(Modifier.isFinal(modifiers))
                            .as("static field '%s' must be final", field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isPublic(modifiers))
                            .as("instance field '%s' must not be public", field.getName())
                            .isFalse();
                }
            }

            for (Field field : DateValidationResult.class.getDeclaredFields()) {
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("result field '%s' must be final", field.getName()).isTrue();
                assertThat(Modifier.isPublic(field.getModifiers()))
                        .as("result field '%s' must not be public", field.getName()).isFalse();
            }
        }

        @Test
        @DisplayName("each call returns a fresh result whose bytes cannot be mutated by a caller")
        void eachCallReturnsAFreshDefensivelyCopiedResult() {
            final DateUtilityJob shared = newService();

            final DateValidationResult first = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            final DateValidationResult second = shared.validateDate(SAMPLE_DATE, MASK_HYPHENATED);
            assertThat(first).isNotSameAs(second);
            assertThat(first.message()).isEqualTo(second.message());

            final byte[] borrowed = first.messageBytes();
            borrowed[SEVERITY_OFFSET] = (byte) 'X';
            assertThat(first.messageBytes())
                    .as("messageBytes() must hand back a defensive copy")
                    .isNotEqualTo(borrowed)
                    .isEqualTo(second.messageBytes());
        }
    }

    @Nested
    @DisplayName("the public three-parameter contract - L83-L88")
    class PublicContract {
        @Test
        @DisplayName("a null date or mask is rejected rather than silently treated as blanks")
        void aNullDateOrMaskIsRejected() {
            final DateUtilityJob service = newService();

            assertThatNullPointerException()
                    .isThrownBy(() -> service.validateDate(null, MASK_HYPHENATED));
            assertThatNullPointerException()
                    .isThrownBy(() -> service.validateDate(SAMPLE_DATE, null));
        }

        @Test
        @DisplayName("the code page is named explicitly and is never the platform default")
        void theCodePageIsNamedExplicitly() {
            assertThat(DateUtilityJob.DEFAULT_MESSAGE_CHARSET)
                    .as("the default must be a named single-byte code page")
                    .isEqualTo(StandardCharsets.US_ASCII);

            assertThat(new DateUtilityJob().validateDate(SAMPLE_DATE, MASK_HYPHENATED).charset())
                    .isEqualTo(DateUtilityJob.DEFAULT_MESSAGE_CHARSET);
            assertThat(newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED).charset())
                    .isEqualTo(MESSAGE_CHARSET);

            assertThat(new DateUtilityJob().validateDate(SAMPLE_DATE, MASK_HYPHENATED).messageBytes())
                    .isEqualTo(newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED).messageBytes());
        }

        @Test
        @DisplayName("a multi-byte code page is refused, because absolute offsets require one byte each")
        void aMultiByteCodePageIsRefused() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new DateUtilityJob(StandardCharsets.UTF_16));
            assertThatNullPointerException().isThrownBy(() -> new DateUtilityJob(null));
        }

        @Test
        @DisplayName("a short input is right-padded and a long one right-truncated by the PIC X move")
        void aShortInputIsRightPaddedAndALongOneRightTruncated() {
            final DateUtilityJob service = newService();

            final DateValidationResult padded = service.validateDate("2022-07-1", MASK_HYPHENATED);
            assertThat(movePicX("2022-07-1", DATE_LENGTH)).isEqualTo("2022-07-1 ");
            assertThat(padded.result()).isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(padded.message()).hasSize(MESSAGE_LENGTH);

            final DateValidationResult noDigits = service.validateDate("2022-07-", MASK_HYPHENATED);
            assertThat(movePicX("2022-07-", DATE_LENGTH)).isEqualTo("2022-07-  ");
            assertThat(noDigits.result()).isEqualTo(RESULT_NONNUMERIC_DATA);

            final DateValidationResult truncated =
                    service.validateDate(SAMPLE_DATE + "XYZ", MASK_HYPHENATED);
            assertThat(movePicX(SAMPLE_DATE + "XYZ", DATE_LENGTH)).isEqualTo(SAMPLE_DATE);
            assertThat(truncated.messageBytes())
                    .as("the excess characters are discarded, so this equals the untruncated call")
                    .isEqualTo(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).messageBytes());

            final DateValidationResult blank = service.validateDate("", "");
            assertThat(blank.message()).hasSize(MESSAGE_LENGTH);
            assertFillersAreIntact(blank.message());
        }

        @Test
        @DisplayName("toString reports the discrete fields and omits the non-printable image")
        void toStringReportsTheDiscreteFieldsOnly() {
            final DateValidationResult result = newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED);

            assertThat(result.toString())
                    .contains("0000")
                    .contains(RESULT_DATE_IS_VALID)
                    .doesNotContain("\n")
                    .doesNotContain("\u0000");
        }
    }

    @Nested
    @DisplayName("the CEEDAYS substitute's guard chain")
    class ValidatorBranchSurface {
        @ParameterizedTest(name = "[{index}] ''{0}'' / ''{1}'' -> {2} ({3})")
        @CsvSource(delimiter = '|', value = {
            "2022-07-18 | YYYY-MM-DD | 0000 | 0000 | YYYY MM DD, the mask both programs pass",
            "20220718   | YYYYMMDD   | 0000 | 0000 | the compact mask the copybook passes",
            "49-07-18   | YY-MM-DD   | 0000 | 0000 | YY at the century-window pivot, 2049",
            "50-07-18   | YY-MM-DD   | 0000 | 0000 | YY just past the pivot, 1950",
            "00-07-18   | YY-MM-DD   | 0000 | 0000 | YY zero maps to 2000, never to year zero",
            "99-07-18   | YY-MM-DD   | 0000 | 0000 | YY at the top of the window, 1999",
            "01JAN2022  | DDMMMYYYY  | 0000 | 0000 | MMM, the first month abbreviation",
            "01DEC2022  | DDMMMYYYY  | 0000 | 0000 | MMM, the last month abbreviation",
            "01jun2022  | DDMMMYYYY  | 0000 | 0000 | MMM folded to upper case against Locale.ROOT",
            "2021-365   | YYYY-DDD   | 0000 | 0000 | DDD, the last day of a common year",
            "2020-366   | YYYY-DDD   | 0000 | 0000 | DDD, the leap day of a leap year",
            "AD220718   | <CC>YYMMDD | 0000 | 0000 | an era field naming the common era",
            "ad220718   | <CC>YYMMDD | 0000 | 0000 | an era name folded to upper case",
            "2022{07{18 | YYYY{MM{DD | 0000 | 0000 | '{' sorts above 'z' yet is a literal, not a letter",
            "2022~07~18 | YYYY~MM~DD | 0000 | 0000 | '~' likewise, at the top of printable ASCII",
            "2020-02-29 | YYYY-MM-DD | 0000 | 0000 | divisible by 4, a leap year",
            "2000-02-29 | YYYY-MM-DD | 0000 | 0000 | divisible by 400, a leap year",
            "1900-02-29 | YYYY-MM-DD | 0003 | 2508 | divisible by 100 not 400, NOT a leap year",
            "2021-02-29 | YYYY-MM-DD | 0003 | 2508 | a common year has no 29 February",
            "2021-02-28 | YYYY-MM-DD | 0000 | 0000 | 28 February is always valid",
            "2022-01-31 | YYYY-MM-DD | 0000 | 0000 | January has 31 days",
            "2022-12-31 | YYYY-MM-DD | 0000 | 0000 | December has 31 days",
            "2022-04-30 | YYYY-MM-DD | 0000 | 0000 | April has 30 days",
            "2022-04-31 | YYYY-MM-DD | 0003 | 2508 | April has no 31st",
            "2022-06-31 | YYYY-MM-DD | 0003 | 2508 | June has no 31st - IBM's own illustration",
            "2022-09-31 | YYYY-MM-DD | 0003 | 2508 | September has no 31st",
            "2022-11-31 | YYYY-MM-DD | 0003 | 2508 | November has no 31st",
            "2022-07-00 | YYYY-MM-DD | 0003 | 2508 | day zero is not a valid day of the month",
            "2021-366   | YYYY-DDD   | 0003 | 2508 | day 366 of a common year",
            "2021-000   | YYYY-DDD   | 0003 | 2508 | day zero of the year",
            "2021-367   | YYYY-DDD   | 0003 | 2508 | beyond the length of any year",
            "2022-00-15 | YYYY-MM-DD | 0003 | 2517 | month zero is below the range",
            "2022-13-15 | YYYY-MM-DD | 0003 | 2517 | month 13 is above the range",
            "01ZZZ2022  | DDMMMYYYY  | 0003 | 2517 | an abbreviation that is not a month",
            "0000-07-18 | YYYY-MM-DD | 0003 | 2521 | a four-digit year of zero",
            "20A2-07-18 | YYYY-MM-DD | 0003 | 2520 | a letter in the year field",
            "2022-A7-18 | YYYY-MM-DD | 0003 | 2520 | a letter in the month field",
            "2022-07-A8 | YYYY-MM-DD | 0003 | 2520 | a letter in the day field",
            "2022-A00   | YYYY-DDD   | 0003 | 2520 | a letter in the day-of-year field",
            "AB-07-18   | YY-MM-DD   | 0003 | 2520 | a letter in a two-digit year field",
            "2022-07-   | YYYY-MM-DD | 0003 | 2520 | a numeric field supplying no digit at all",
            "2022--7-18 | YYYY-MM-DD | 0003 | 2520 | a delimiter where the month's digits belong",
            "1582-10-15 | YYYY-MM-DD | 0000 | 0000 | the Lillian epoch itself is in range",
            "1582-10-14 | YYYY-MM-DD | 0003 | 2513 | a day the Gregorian reform skipped",
            "1582-09-30 | YYYY-MM-DD | 0003 | 2513 | the month before the epoch",
            "1581-12-31 | YYYY-MM-DD | 0003 | 2513 | the year before the epoch",
            "9999-12-31 | YYYY-MM-DD | 0000 | 0000 | the top of the four-digit year range",
            "BC220718   | <CC>YYMMDD | 0003 | 2513 | every date before the common era is out of range",
            "ZZ220718   | <CC>YYMMDD | 0003 | 2509 | an era name that is neither AD nor BC",
            "2022-07-18 | QQQQ-MM-DD | 0003 | 2518 | a letter that begins no token",
            "2022-07-18 | YYYY-MM-DZ | 0003 | 2518 | a lone letter where a token was expected",
            "2022-07-18 | YYYYYYYYYY | 0003 | 2518 | the year role declared twice",
            "2022-07-18 | YYYY-MM-MM | 0003 | 2518 | the month role declared twice",
            "AD22-07-18 | <CC YYMMDD | 0003 | 2518 | an era field with no closing delimiter",
            "2022-07018 | YYYY-DDDMM | 0003 | 2518 | a day of the year AND a month contradict",
            "20220718   | YYYYDDDDD  | 0003 | 2518 | a day of the year AND a day of the month clash",
            "2022-07-18 | yyyy-MM-DD | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case YYYY",
            "2022-07-18 | YYYY-mm-DD | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case MM",
            "2022-07-18 | YYYY-MM-dd | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case DD",
            "01jan2022  | DDmmmYYYY  | 0003 | 2518 | picture tokens are case-SENSITIVE: lower-case MMM",
            "2022       | YYYY       | 0003 | 2507 | a year alone cannot yield a Lillian value",
            "2022-07    | YYYY-MM    | 0003 | 2507 | a year and month with no day",
            "2022-07-18 | MM-DD      | 0003 | 2507 | a month and day with no year",
            "2022/07/18 | YYYY-MM-DD | 0000 | 0000 | a delimiter variant where the picture declares one",
            "2022-07.18 | YYYY-MM-DD | 0000 | 0000 | a variant at the second delimiter only",
            "2022-07-18 | YYYY{MM{DD | 0000 | 0000 | the picture's delimiter is above 'z'; '-' still separates",
            "AD22071899 | <CC>YYMMDD | 0000 | 0000 | characters beyond the picture's description are ignored",
            "2022-07-1  | YYYY-MM-DD | 0000 | 0000 | an omitted leading zero in the day, as '6/2/88' shows",
            "2022-7-18  | YYYY-MM-DD | 0000 | 0000 | an omitted leading zero in the month",
            "  22-07-18 | YY-MM-DD   | 0000 | 0000 | parsing begins at the first non-blank character",
            "2022-07    | YYYY-MM-DD | 0003 | 2520 | the input runs out before the picture does",
            "2022-07-AB | YYYY-MM-DD | 0003 | 2520 | letters where the day's digits belong",
        })
        @DisplayName("each guard-chain condition reports its documented severity and message number")
        void eachGuardChainConditionReportsItsDocumentedOutcome(String inputDate,
                                                               String pictureMask,
                                                               String expectedSeverityCode,
                                                               String expectedMessageNumber,
                                                               String rationale) {
            final DateValidationResult result = newService().validateDate(inputDate, pictureMask);

            assertThat(result.severityCode())
                    .as("WS-SEVERITY for '%s' / '%s' - %s", inputDate, pictureMask, rationale)
                    .isEqualTo(expectedSeverityCode);
            assertThat(result.messageNumber())
                    .as("WS-MSG-NO for '%s' / '%s' - %s", inputDate, pictureMask, rationale)
                    .isEqualTo(expectedMessageNumber);

            assertThat(result.message()).hasSize(MESSAGE_LENGTH);
            assertThat(result.result()).hasSize(RESULT_LENGTH);
            assertFillersAreIntact(result.message());

            assertThat(asPicNine4(result.returnCode())).isEqualTo(result.severityCode());
            assertThat(result.message().substring(DATE_FMT_OFFSET, DATE_FMT_OFFSET + DATE_LENGTH))
                    .isEqualTo(movePicX(pictureMask, DATE_LENGTH));
        }

        @ParameterizedTest(name = "[{index}] month {0} -> ''{1}''")
        @CsvSource({
            "JAN, 2022-01-31", "FEB, 2022-02-28", "MAR, 2022-03-31", "APR, 2022-04-30",
            "MAY, 2022-05-31", "JUN, 2022-06-30", "JUL, 2022-07-31", "AUG, 2022-08-31",
            "SEP, 2022-09-30", "OCT, 2022-10-31", "NOV, 2022-11-30", "DEC, 2022-12-31",
        })
        @DisplayName("all twelve month abbreviations resolve, and each month's last day is valid")
        void allTwelveMonthAbbreviationsResolveToTheirOwnMonth(String abbreviation,
                                                              String equivalentNumericDate) {
            final DateUtilityJob service = newService();

            final String lastDay = equivalentNumericDate.substring(8, 10);
            final DateValidationResult byName =
                    service.validateDate(lastDay + abbreviation + "2022 ", "DDMMMYYYY ");
            final DateValidationResult byNumber =
                    service.validateDate(equivalentNumericDate, MASK_HYPHENATED);

            assertThat(byName.severityCode())
                    .as("%s must resolve to a month", abbreviation)
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(byNumber.severityCode())
                    .as("the last day of %s must be valid", abbreviation)
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(byName.result()).isEqualTo(byNumber.result()).isEqualTo(RESULT_DATE_IS_VALID);
        }

        @Test
        @DisplayName("picture tokens are case-sensitive while input month and era names are folded")
        void pictureTokensAreCaseSensitiveWhileInputNamesAreFolded() {
            final DateUtilityJob service = newService();

            assertThat(service.validateDate(SAMPLE_DATE, "yyyy-MM-DD").result())
                    .as("a lower-case picture token is not recognised")
                    .isEqualTo(RESULT_BAD_PIC_STRING);
            assertThat(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).result())
                    .as("the same picture in upper case is accepted")
                    .isEqualTo(RESULT_DATE_IS_VALID);

            final DateValidationResult upperMonth = service.validateDate("01JUN2022 ", "DDMMMYYYY ");
            final DateValidationResult lowerMonth = service.validateDate("01jun2022 ", "DDMMMYYYY ");
            assertThat(lowerMonth.severityCode()).isEqualTo(upperMonth.severityCode())
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(lowerMonth.messageNumber()).isEqualTo(upperMonth.messageNumber());
            assertThat(lowerMonth.result()).isEqualTo(upperMonth.result())
                    .isEqualTo(RESULT_DATE_IS_VALID);
            assertThat(lowerMonth.returnCode()).isEqualTo(upperMonth.returnCode()).isZero();

            final DateValidationResult upperEra = service.validateDate("AD220718  ", "<CC>YYMMDD");
            final DateValidationResult lowerEra = service.validateDate("ad220718  ", "<CC>YYMMDD");
            assertThat(lowerEra.severityCode()).isEqualTo(upperEra.severityCode())
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(lowerEra.result()).isEqualTo(upperEra.result()).isEqualTo(RESULT_DATE_IS_VALID);

            assertThat(lowerMonth.messageBytes())
                    .as("the echoed input keeps its own case, so the images are not byte-identical")
                    .isNotEqualTo(upperMonth.messageBytes());

            final byte[] lower = lowerMonth.messageBytes();
            final byte[] upper = upperMonth.messageBytes();
            for (int offset = 0; offset < MESSAGE_LENGTH; offset++) {
                if (offset >= DATE_OFFSET && offset < DATE_OFFSET + DATE_LENGTH) {
                    continue;
                }
                assertThat(lower[offset])
                        .as("byte %d lies outside the echoed-input span and must match exactly", offset)
                        .isEqualTo(upper[offset]);
            }

            assertThat(new String(lower, DATE_OFFSET, DATE_LENGTH, MESSAGE_CHARSET)
                    .toUpperCase(Locale.ROOT))
                    .isEqualTo(new String(upper, DATE_OFFSET, DATE_LENGTH, MESSAGE_CHARSET)
                            .toUpperCase(Locale.ROOT));
        }

        @Test
        @DisplayName("the two structurally unreachable guards are analysed, not overlooked")
        void theTwoStructurallyUnreachableGuardsAreAnalysedNotOverlooked() {
            assertThat(movePicX("", DATE_LENGTH)).hasSize(DATE_LENGTH);
            assertThat(movePicX("2022-07-18XYZ", DATE_LENGTH)).hasSize(DATE_LENGTH);
            assertThat(movePicX(SAMPLE_DATE, DATE_LENGTH)).hasSize(DATE_LENGTH);

            final DateValidationResult eraResult =
                    newService().validateDate("AD220718  ", "<CC>YYMMDD");
            assertThat("<CC>YYMMDD").hasSize(DATE_LENGTH);
            assertThat(eraResult.severityCode())
                    .as("the era picture leaves two input positions undescribed, which must be blank")
                    .isEqualTo(ACCEPTED_SEVERITY_CODE);

            final DateUtilityJob service = newService();
            assertThat(service.validateDate(SAMPLE_DATE, MASK_HYPHENATED).severityCode())
                    .as("YEAR_4, MONTH_2, DAY_2 and LITERAL").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.validateDate("49-07-18  ", "YY-MM-DD  ").severityCode())
                    .as("YEAR_2").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.validateDate("01JAN2022 ", "DDMMMYYYY ").severityCode())
                    .as("MONTH_NAME_3").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(service.validateDate("2021-365  ", "YYYY-DDD  ").severityCode())
                    .as("JULIAN_DAY_3").isEqualTo(ACCEPTED_SEVERITY_CODE);
            assertThat(eraResult.severityCode()).as("ERA").isEqualTo(ACCEPTED_SEVERITY_CODE);
        }
    }

    @Nested
    @DisplayName("container wiring - the eighty bytes follow the CONFIGURED code page, not US-ASCII")
    class ContainerWiring {
        private static final String EBCDIC_NAME = "IBM037";

        private static final String ASCII_NAME = "US-ASCII";

        private ApplicationContextRunner containerWith(String datasetCharsetName) {
            return new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(CobolCharsetConfig.class, DateUtilityJob.class)
                    .withPropertyValues(
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + EBCDIC_NAME,
                            CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + ASCII_NAME,
                            CobolCharsetConfig.DATASET_CHARSET_PROPERTY + "="
                                    + datasetCharsetName);
        }

        @Test
        @DisplayName("under IBM037 the container's service emits IBM037 bytes, not ASCII ones")
        void underIbm037TheContainersServiceEmitsEbcdicBytes() {
            containerWith(EBCDIC_NAME).run(context -> {
                DateUtilityJob wired = context.getBean(DateUtilityJob.class);
                DateValidationResult result = wired.validateDate(SAMPLE_DATE, MASK_HYPHENATED);

                assertThat(result.charset()).isEqualTo(Charset.forName(EBCDIC_NAME));
                assertThat(result.messageBytes()).hasSize(MESSAGE_LENGTH);

                byte[] expectedLabel = "Mesg Code:".getBytes(Charset.forName(EBCDIC_NAME));
                byte[] actualLabel = java.util.Arrays.copyOfRange(result.messageBytes(), 4,
                        4 + expectedLabel.length);
                assertThat(actualLabel).isEqualTo(expectedLabel);
                assertThat(actualLabel[0]).isEqualTo((byte) 0xD4);
                assertThat(actualLabel).isNotEqualTo("Mesg Code:".getBytes(MESSAGE_CHARSET));

                assertThat(java.util.Arrays.copyOfRange(result.messageBytes(), 0, 4))
                        .containsExactly((byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0);
                assertThat(result.severityCode()).isEqualTo(ACCEPTED_SEVERITY_CODE);
            });
        }

        @Test
        @DisplayName("under US-ASCII the container's service emits the ASCII bytes this suite asserts")
        void underAsciiTheContainersServiceEmitsAsciiBytes() {
            containerWith(ASCII_NAME).run(context -> {
                DateValidationResult result = context.getBean(DateUtilityJob.class)
                        .validateDate(SAMPLE_DATE, MASK_HYPHENATED);

                assertThat(result.charset()).isEqualTo(MESSAGE_CHARSET);
                assertThat(result.messageBytes())
                        .isEqualTo(newService().validateDate(SAMPLE_DATE, MASK_HYPHENATED)
                                .messageBytes());
            });
        }

        @Test
        @DisplayName("with the dataset key unset the context refuses to start rather than guessing")
        void withTheDatasetKeyUnsetTheContextRefusesToStart() {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            PropertyPlaceholderAutoConfiguration.class))
                    .withUserConfiguration(CobolCharsetConfig.class, DateUtilityJob.class)
                    .withPropertyValues(
                            CobolCharsetConfig.EBCDIC_CHARSET_PROPERTY + "=" + EBCDIC_NAME,
                            CobolCharsetConfig.ASCII_CHARSET_PROPERTY + "=" + ASCII_NAME)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context).getFailure().rootCause()
                                .hasMessageContaining("Could not resolve placeholder")
                                .hasMessageContaining(
                                        CobolCharsetConfig.DATASET_CHARSET_PROPERTY);
                    });
        }

        @Test
        @DisplayName("with the dataset key set to the ASCII code page the service emits ASCII bytes")
        void withTheDatasetKeySetToAsciiTheServiceEmitsAsciiBytes() {
            containerWith(ASCII_NAME).run(context -> assertThat(
                    context.getBean(DateUtilityJob.class)
                            .validateDate(SAMPLE_DATE, MASK_HYPHENATED).charset())
                    .isEqualTo(MESSAGE_CHARSET));
        }

        @Test
        @DisplayName("the container picks the Charset constructor, not the no-argument one")
        void theContainerPicksTheCharsetConstructor() {
            assertThat(DateUtilityJob.class.getDeclaredConstructors())
                    .filteredOn(candidate -> candidate.isAnnotationPresent(
                            org.springframework.beans.factory.annotation.Autowired.class))
                    .singleElement()
                    .satisfies(annotated -> assertThat(annotated.getParameterTypes())
                            .containsExactly(Charset.class));

            try (AnnotationConfigApplicationContext context =
                         new AnnotationConfigApplicationContext()) {
                context.register(DateUtilityJob.class);
                context.registerBean("carddemoDatasetCharset", Charset.class,
                        () -> Charset.forName(EBCDIC_NAME));
                context.refresh();

                assertThat(context.getBean(DateUtilityJob.class)
                        .validateDate(SAMPLE_DATE, MASK_HYPHENATED).charset())
                        .isEqualTo(Charset.forName(EBCDIC_NAME));
            }
        }
    }
}
