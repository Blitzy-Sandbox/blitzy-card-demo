package com.vsergeychik.carddemo.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link SystemMessages}, the Java form of two COBOL copybooks that the migration
 * deliberately merges into one class: {@code app/cpy/CSMSG01Y.cpy} lines 17-21 - group item
 * {@code CCDA-COMMON-MESSAGES}, two {@code PIC X(50)} message fields copied by all 17 CICS online programs.
 */
@DisplayName("SystemMessages - CSMSG01Y common messages and the CSMSG02Y abend work area")
class SystemMessagesTest {
    private static final String THANK_YOU_SOURCE_LITERAL =
            "Thank you for using CardDemo application..." + "      ";

    private static final String INVALID_KEY_SOURCE_LITERAL =
            "Invalid key pressed. Please see below..." + "         ";

    private static final int SOURCE_LITERAL_LENGTH = 49;

    private static final int DECLARED_MESSAGE_WIDTH = 50;

    private static final String EXPECTED_THANK_YOU = THANK_YOU_SOURCE_LITERAL + " ";

    private static final String EXPECTED_INVALID_KEY = INVALID_KEY_SOURCE_LITERAL + " ";

    private static final String THANK_YOU_VISIBLE_TEXT =
            "Thank you for using CardDemo application...";

    private static final String INVALID_KEY_VISIBLE_TEXT =
            "Invalid key pressed. Please see below...";

    private static final String SCREEN_TITLE_THANK_YOU_WORDING = "CCDA application";

    private static final int SCREEN_TITLE_WIDTH = 40;

    private static final int CODE_WIDTH = 4;

    private static final int CULPRIT_WIDTH = 8;

    private static final int REASON_WIDTH = 50;

    private static final int MSG_WIDTH = 72;

    private static final int EXPECTED_ABEND_DATA_WIDTH =
            CODE_WIDTH + CULPRIT_WIDTH + REASON_WIDTH + MSG_WIDTH;

    private static final int CODE_OFFSET = 0;

    private static final int CULPRIT_OFFSET = CODE_OFFSET + CODE_WIDTH;

    private static final int REASON_OFFSET = CULPRIT_OFFSET + CULPRIT_WIDTH;

    private static final int MSG_OFFSET = REASON_OFFSET + REASON_WIDTH;

    private static final String SAMPLE_CODE = "0001";

    private static final String SAMPLE_CULPRIT = "COACTVWC";

    private static final String SAMPLE_REASON = "Account record not found";

    private static final String SAMPLE_MSG = "Unexpected file status returned by ACCTDAT";

    static Stream<Arguments> commonMessages() {
        return Stream.of(
                Arguments.of("CCDA-MSG-THANK-YOU", SystemMessages.CCDA_MSG_THANK_YOU),
                Arguments.of("CCDA-MSG-INVALID-KEY", SystemMessages.CCDA_MSG_INVALID_KEY));
    }

    static Stream<Arguments> abendFields() {
        return Stream.of(
                Arguments.of("ABEND-CODE", CODE_WIDTH),
                Arguments.of("ABEND-CULPRIT", CULPRIT_WIDTH),
                Arguments.of("ABEND-REASON", REASON_WIDTH),
                Arguments.of("ABEND-MSG", MSG_WIDTH));
    }

    private static String serialise(SystemMessages.AbendData area) {
        SystemMessages.AbendData canonical = area.toDeclaredWidths();
        return canonical.abendCode()
                + canonical.abendCulprit()
                + canonical.abendReason()
                + canonical.abendMsg();
    }

    private static SystemMessages.AbendData sampleArea() {
        return new SystemMessages.AbendData(SAMPLE_CODE, SAMPLE_CULPRIT, SAMPLE_REASON, SAMPLE_MSG);
    }

    @Test
    @DisplayName("the transcribed source literals measure 49 characters, one short of PIC X(50)")
    void sourceLiteralsMeasureFortyNineCharacters() {
        assertThat(THANK_YOU_SOURCE_LITERAL).hasSize(SOURCE_LITERAL_LENGTH);
        assertThat(INVALID_KEY_SOURCE_LITERAL).hasSize(SOURCE_LITERAL_LENGTH);

        assertThat(SOURCE_LITERAL_LENGTH).isEqualTo(DECLARED_MESSAGE_WIDTH - 1);
        assertThat(EXPECTED_THANK_YOU).hasSize(DECLARED_MESSAGE_WIDTH);
        assertThat(EXPECTED_INVALID_KEY).hasSize(DECLARED_MESSAGE_WIDTH);
    }

    @ParameterizedTest(name = "{0} is exactly MESSAGE_LENGTH characters")
    @MethodSource("commonMessages")
    @DisplayName("every common message is exactly MESSAGE_LENGTH characters wide")
    void everyCommonMessageIsDeclaredWidth(String cobolName, String message) {
        assertThat(message)
                .as("CSMSG01Y declares %s as PIC X(50)", cobolName)
                .hasSize(SystemMessages.MESSAGE_LENGTH);
    }

    @ParameterizedTest(name = "{0} keeps its fixed-width padding")
    @MethodSource("commonMessages")
    @DisplayName("every common message is space-padded, not trimmed")
    void everyCommonMessageKeepsItsPadding(String cobolName, String message) {
        assertThat(message)
                .as("%s must retain the trailing spaces COBOL moves onto the screen", cobolName)
                .endsWith(" ")
                .isNotEqualTo(message.trim())
                .isNotBlank();
    }

    @Nested
    @DisplayName("CCDA-COMMON-MESSAGES - the two PIC X(50) message fields")
    class CommonMessages {
        @Test
        @DisplayName("MESSAGE_LENGTH is 50, the declared PIC X(50) width")
        void messageLengthIsFifty() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(DECLARED_MESSAGE_WIDTH);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
        }

        @Test
        @DisplayName("MESSAGE_LENGTH is the field width, not the 49-character literal length")
        void messageLengthIsNotTheLiteralLength() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isNotEqualTo(SOURCE_LITERAL_LENGTH);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU is 50 characters long")
        void thankYouIsFiftyCharacters() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.length())
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU is the 49-character source literal plus one pad space")
        void thankYouIsSourceLiteralPlusOnePadSpace() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isEqualTo(THANK_YOU_SOURCE_LITERAL + " ")
                    .isEqualTo(EXPECTED_THANK_YOU);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU keeps all seven trailing spaces: six typed plus one padded")
        void thankYouKeepsItsTrailingSpaces() {
            String message = SystemMessages.CCDA_MSG_THANK_YOU;

            assertThat(message).endsWith(" ");
            assertThat(message).isNotEqualTo(message.trim());
            assertThat(message.length() - message.stripTrailing().length()).isEqualTo(7);
        }

        @Test
        @DisplayName("CCDA-MSG-THANK-YOU's visible text is pinned independently of its padding")
        void thankYouVisibleText() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.trim()).isEqualTo(THANK_YOU_VISIBLE_TEXT);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.trim())
                    .isEqualTo("Thank you for using CardDemo application...")
                    .hasSize(43);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is 50 characters long")
        void invalidKeyIsFiftyCharacters() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.length())
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY is the 49-character source literal plus one pad space")
        void invalidKeyIsSourceLiteralPlusOnePadSpace() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .isEqualTo(INVALID_KEY_SOURCE_LITERAL + " ")
                    .isEqualTo(EXPECTED_INVALID_KEY);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY keeps all ten trailing spaces: nine typed plus one padded")
        void invalidKeyKeepsItsTrailingSpaces() {
            String message = SystemMessages.CCDA_MSG_INVALID_KEY;

            assertThat(message).endsWith(" ");
            assertThat(message).isNotEqualTo(message.trim());
            assertThat(message.length() - message.stripTrailing().length()).isEqualTo(10);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY's visible text is pinned independently of its padding")
        void invalidKeyVisibleText() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.trim())
                    .isEqualTo(INVALID_KEY_VISIBLE_TEXT);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.trim())
                    .isEqualTo("Invalid key pressed. Please see below...")
                    .hasSize(40);
        }

        @Test
        @DisplayName("the two messages are distinct values")
        void theTwoMessagesAreDistinct() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @ParameterizedTest(name = "the message text contains \"{0}\"")
        @ValueSource(strings = {"Thank you", "CardDemo", "application..."})
        @DisplayName("the thank-you wording is reproduced fragment by fragment")
        void thankYouContainsItsFragments(String fragment) {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains(fragment);
        }

        @ParameterizedTest(name = "the message text contains \"{0}\"")
        @ValueSource(strings = {"Invalid key pressed.", "Please see below", "..."})
        @DisplayName("the invalid-key wording is reproduced fragment by fragment")
        void invalidKeyContainsItsFragments(String fragment) {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).contains(fragment);
        }
    }

    @Nested
    @DisplayName("Not the screen-title thank-you - two similar strings that are not interchangeable")
    class NotTheScreenTitleThankYou {
        @Test
        @DisplayName("this message names the CardDemo application, not the CCDA application")
        void namesCardDemoAndNotCcda() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).contains("CardDemo");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .doesNotContain(SCREEN_TITLE_THANK_YOU_WORDING)
                    .doesNotContain("CCDA application");
        }

        @Test
        @DisplayName("this message is 50 characters wide, not the screen title's 40")
        void isFiftyWideAndNotForty() {
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(DECLARED_MESSAGE_WIDTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU.length())
                    .isNotEqualTo(SCREEN_TITLE_WIDTH);
        }

        @Test
        @DisplayName("this message is not the screen-title string truncated or padded to 50")
        void isNotTheScreenTitleStringReshaped() {
            String screenTitleWidenedToFifty =
                    "Thank you for using CCDA application... " + " ".repeat(10);

            assertThat(screenTitleWidenedToFifty).hasSize(DECLARED_MESSAGE_WIDTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).isNotEqualTo(screenTitleWidenedToFifty);
        }
    }

    @Nested
    @DisplayName("ABEND-DATA - the four declared widths and the 134-byte total")
    class AbendDataWidths {
        @Test
        @DisplayName("ABEND-CODE is PIC X(4)")
        void abendCodeWidth() {
            assertThat(SystemMessages.ABEND_CODE_LENGTH).isEqualTo(CODE_WIDTH).isEqualTo(4);
        }

        @Test
        @DisplayName("ABEND-CULPRIT is PIC X(8), one COBOL program name")
        void abendCulpritWidth() {
            assertThat(SystemMessages.ABEND_CULPRIT_LENGTH).isEqualTo(CULPRIT_WIDTH).isEqualTo(8);
        }

        @Test
        @DisplayName("ABEND-REASON is PIC X(50)")
        void abendReasonWidth() {
            assertThat(SystemMessages.ABEND_REASON_LENGTH).isEqualTo(REASON_WIDTH).isEqualTo(50);
        }

        @Test
        @DisplayName("ABEND-MSG is PIC X(72)")
        void abendMsgWidth() {
            assertThat(SystemMessages.ABEND_MSG_LENGTH).isEqualTo(MSG_WIDTH).isEqualTo(72);
        }

        @Test
        @DisplayName("the group item totals 134 bytes: 4 + 8 + 50 + 72")
        void abendDataTotalsOneHundredAndThirtyFour() {
            assertThat(EXPECTED_ABEND_DATA_WIDTH).isEqualTo(4 + 8 + 50 + 72).isEqualTo(134);
            assertThat(SystemMessages.ABEND_DATA_LENGTH).isEqualTo(EXPECTED_ABEND_DATA_WIDTH);
            assertThat(SystemMessages.ABEND_CODE_LENGTH
                    + SystemMessages.ABEND_CULPRIT_LENGTH
                    + SystemMessages.ABEND_REASON_LENGTH
                    + SystemMessages.ABEND_MSG_LENGTH)
                    .isEqualTo(SystemMessages.ABEND_DATA_LENGTH);
        }

        @ParameterizedTest(name = "{0} is declared {1} bytes wide")
        @MethodSource("com.vsergeychik.carddemo.common.SystemMessagesTest#abendFields")
        @DisplayName("every abend field's declared width is positive and no wider than the group")
        void everyAbendFieldWidthIsWithinTheGroup(String cobolName, int width) {
            assertThat(width)
                    .as("%s declared width", cobolName)
                    .isPositive()
                    .isLessThanOrEqualTo(EXPECTED_ABEND_DATA_WIDTH);
        }

        @Test
        @DisplayName("the field offsets are 0, 4, 12 and 62, ending exactly at 134")
        void fieldOffsetsFollowDeclarationOrder() {
            assertThat(CODE_OFFSET).isZero();
            assertThat(CULPRIT_OFFSET).isEqualTo(4);
            assertThat(REASON_OFFSET).isEqualTo(12);
            assertThat(MSG_OFFSET).isEqualTo(62);
            assertThat(MSG_OFFSET + MSG_WIDTH).isEqualTo(EXPECTED_ABEND_DATA_WIDTH);
        }
    }

    @Nested
    @DisplayName("ABEND-DATA - VALUE SPACES is the declared initial state")
    class AbendDataDefaults {
        @Test
        @DisplayName("spaces() gives every field a run of spaces at its own declared width")
        void spacesGivesEveryFieldItsOwnWidthOfSpaces() {
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces();

            assertThat(area.abendCode()).isEqualTo(" ".repeat(CODE_WIDTH)).hasSize(4);
            assertThat(area.abendCulprit()).isEqualTo(" ".repeat(CULPRIT_WIDTH)).hasSize(8);
            assertThat(area.abendReason()).isEqualTo(" ".repeat(REASON_WIDTH)).hasSize(50);
            assertThat(area.abendMsg()).isEqualTo(" ".repeat(MSG_WIDTH)).hasSize(72);
        }

        @Test
        @DisplayName("the default is spaces - not null, and not the empty string")
        void theDefaultIsSpacesNotNullAndNotEmpty() {
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces();

            assertThat(area.abendCode()).isNotNull().isNotEmpty().isBlank();
            assertThat(area.abendCulprit()).isNotNull().isNotEmpty().isBlank();
            assertThat(area.abendReason()).isNotNull().isNotEmpty().isBlank();
            assertThat(area.abendMsg()).isNotNull().isNotEmpty().isBlank();
        }

        @Test
        @DisplayName("the default area serialises to 134 spaces")
        void theDefaultAreaSerialisesToOneHundredAndThirtyFourSpaces() {
            String image = serialise(SystemMessages.AbendData.spaces());

            assertThat(image).hasSize(EXPECTED_ABEND_DATA_WIDTH);
            assertThat(image).isEqualTo(" ".repeat(EXPECTED_ABEND_DATA_WIDTH));
        }

        @Test
        @DisplayName("spaces() is already at its declared widths, so normalising it changes nothing")
        void normalisingTheDefaultAreaIsIdempotent() {
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces();

            assertThat(area.toDeclaredWidths()).isEqualTo(area);
            assertThat(area.toDeclaredWidths().toDeclaredWidths()).isEqualTo(area);
        }
    }

    @Nested
    @DisplayName("ABEND-DATA - PIC X move semantics: right-pad when short, right-truncate when long")
    class AbendDataMoveSemantics {
        @Test
        @DisplayName("a value that already fills its field is stored unchanged")
        void exactWidthValueIsStoredUnchanged() {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCode(SAMPLE_CODE);

            assertThat(SAMPLE_CODE).hasSize(CODE_WIDTH);
            assertThat(area.abendCode()).isEqualTo("0001").hasSize(CODE_WIDTH);
        }

        @Test
        @DisplayName("an eight-character program name exactly fills ABEND-CULPRIT")
        void exactWidthCulpritIsStoredUnchanged() {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCulprit(SAMPLE_CULPRIT);

            assertThat(SAMPLE_CULPRIT).hasSize(CULPRIT_WIDTH);
            assertThat(area.abendCulprit()).isEqualTo("COACTVWC").hasSize(CULPRIT_WIDTH);
        }

        @Test
        @DisplayName("a short value is padded on the right, never on the left")
        void shortValueIsPaddedOnTheRight() {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCulprit("CBACT01");

            assertThat(area.abendCulprit())
                    .hasSize(CULPRIT_WIDTH)
                    .isEqualTo("CBACT01 ")
                    .startsWith("CBACT01")
                    .isNotEqualTo(" CBACT01");
        }

        @Test
        @DisplayName("a long value is truncated on the right, keeping its leading characters")
        void longValueIsTruncatedOnTheRight() {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCulprit("COACTUPCX");

            assertThat(area.abendCulprit())
                    .hasSize(CULPRIT_WIDTH)
                    .isEqualTo("COACTUPC")
                    .isNotEqualTo("OACTUPCX");
        }

        @ParameterizedTest(name = "moving \"{0}\" into ABEND-CODE stores \"{1}\"")
        @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "0001|0001",
            "1|1   ",
            "12|12  ",
            "123|123 ",
            "12345|1234",
            "123456|1234",
        })
        @DisplayName("ABEND-CODE pads or truncates on the right to exactly four characters")
        void abendCodeIsAlwaysFourCharacters(String moved, String expected) {
            SystemMessages.AbendData area =
                    SystemMessages.AbendData.spaces().withAbendCode(moved);

            assertThat(expected).hasSize(CODE_WIDTH);
            assertThat(area.abendCode()).isEqualTo(expected).hasSize(CODE_WIDTH);
        }

        @Test
        @DisplayName("each with... method changes one field and leaves the other three alone")
        void eachWithMethodChangesExactlyOneField() {
            SystemMessages.AbendData blank = SystemMessages.AbendData.spaces();

            SystemMessages.AbendData withCode = blank.withAbendCode(SAMPLE_CODE);
            assertThat(withCode.abendCode()).isEqualTo(SAMPLE_CODE);
            assertThat(withCode.abendCulprit()).isEqualTo(blank.abendCulprit());
            assertThat(withCode.abendReason()).isEqualTo(blank.abendReason());
            assertThat(withCode.abendMsg()).isEqualTo(blank.abendMsg());

            SystemMessages.AbendData withCulprit = blank.withAbendCulprit(SAMPLE_CULPRIT);
            assertThat(withCulprit.abendCulprit()).isEqualTo(SAMPLE_CULPRIT);
            assertThat(withCulprit.abendCode()).isEqualTo(blank.abendCode());
            assertThat(withCulprit.abendReason()).isEqualTo(blank.abendReason());
            assertThat(withCulprit.abendMsg()).isEqualTo(blank.abendMsg());

            SystemMessages.AbendData withReason = blank.withAbendReason(SAMPLE_REASON);
            assertThat(withReason.abendReason()).startsWith(SAMPLE_REASON).hasSize(REASON_WIDTH);
            assertThat(withReason.abendCode()).isEqualTo(blank.abendCode());
            assertThat(withReason.abendCulprit()).isEqualTo(blank.abendCulprit());
            assertThat(withReason.abendMsg()).isEqualTo(blank.abendMsg());

            SystemMessages.AbendData withMsg = blank.withAbendMsg(SAMPLE_MSG);
            assertThat(withMsg.abendMsg()).startsWith(SAMPLE_MSG).hasSize(MSG_WIDTH);
            assertThat(withMsg.abendCode()).isEqualTo(blank.abendCode());
            assertThat(withMsg.abendCulprit()).isEqualTo(blank.abendCulprit());
            assertThat(withMsg.abendReason()).isEqualTo(blank.abendReason());
        }

        @Test
        @DisplayName("a populated area still serialises to exactly 134 bytes")
        void populatedAreaStillSerialisesToOneHundredAndThirtyFour() {
            String image = serialise(sampleArea());

            assertThat(image).hasSize(EXPECTED_ABEND_DATA_WIDTH);
            assertThat(image).hasSize(SystemMessages.ABEND_DATA_LENGTH);
        }

        @Test
        @DisplayName("the serialised image lays the fields out at offsets 0, 4, 12 and 62")
        void serialisedImageHonoursDeclarationOrder() {
            String image = serialise(sampleArea());

            assertThat(image.substring(CODE_OFFSET, CODE_OFFSET + CODE_WIDTH))
                    .isEqualTo(SAMPLE_CODE);
            assertThat(image.substring(CULPRIT_OFFSET, CULPRIT_OFFSET + CULPRIT_WIDTH))
                    .isEqualTo(SAMPLE_CULPRIT);
            assertThat(image.substring(REASON_OFFSET, REASON_OFFSET + REASON_WIDTH))
                    .startsWith(SAMPLE_REASON)
                    .hasSize(REASON_WIDTH);
            assertThat(image.substring(MSG_OFFSET, MSG_OFFSET + MSG_WIDTH))
                    .startsWith(SAMPLE_MSG)
                    .hasSize(MSG_WIDTH);
        }

        @Test
        @DisplayName("every serialised field is right-space-padded to its own declared width")
        void serialisedFieldsAreRightPaddedToTheirOwnWidth() {
            SystemMessages.AbendData canonical = sampleArea().toDeclaredWidths();

            assertThat(canonical.abendCode()).hasSize(CODE_WIDTH);
            assertThat(canonical.abendCulprit()).hasSize(CULPRIT_WIDTH);
            assertThat(canonical.abendReason())
                    .hasSize(REASON_WIDTH)
                    .isEqualTo(SAMPLE_REASON + " ".repeat(REASON_WIDTH - SAMPLE_REASON.length()));
            assertThat(canonical.abendMsg())
                    .hasSize(MSG_WIDTH)
                    .isEqualTo(SAMPLE_MSG + " ".repeat(MSG_WIDTH - SAMPLE_MSG.length()));
        }

        @Test
        @DisplayName("an over-long value in every field is truncated when normalised")
        void overLongValuesInEveryFieldAreTruncatedOnNormalisation() {
            SystemMessages.AbendData tooWide = new SystemMessages.AbendData(
                    "a".repeat(CODE_WIDTH + 1),
                    "b".repeat(CULPRIT_WIDTH + 1),
                    "c".repeat(REASON_WIDTH + 1),
                    "d".repeat(MSG_WIDTH + 1));

            SystemMessages.AbendData canonical = tooWide.toDeclaredWidths();

            assertThat(canonical.abendCode()).isEqualTo("a".repeat(CODE_WIDTH));
            assertThat(canonical.abendCulprit()).isEqualTo("b".repeat(CULPRIT_WIDTH));
            assertThat(canonical.abendReason()).isEqualTo("c".repeat(REASON_WIDTH));
            assertThat(canonical.abendMsg()).isEqualTo("d".repeat(MSG_WIDTH));
            assertThat(serialise(canonical)).hasSize(EXPECTED_ABEND_DATA_WIDTH);
        }

        @Test
        @DisplayName("a short value in every field is padded when normalised")
        void shortValuesInEveryFieldArePaddedOnNormalisation() {
            SystemMessages.AbendData tooNarrow = new SystemMessages.AbendData(
                    "a".repeat(CODE_WIDTH - 1),
                    "b".repeat(CULPRIT_WIDTH - 1),
                    "c".repeat(REASON_WIDTH - 1),
                    "d".repeat(MSG_WIDTH - 1));

            SystemMessages.AbendData canonical = tooNarrow.toDeclaredWidths();

            assertThat(canonical.abendCode()).isEqualTo("aaa ");
            assertThat(canonical.abendCulprit()).isEqualTo("bbbbbbb ");
            assertThat(canonical.abendReason()).endsWith(" ").hasSize(REASON_WIDTH);
            assertThat(canonical.abendMsg()).endsWith(" ").hasSize(MSG_WIDTH);
            assertThat(serialise(canonical)).hasSize(EXPECTED_ABEND_DATA_WIDTH);
        }

        @Test
        @DisplayName("an empty value is padded to a full field of spaces")
        void emptyValueIsPaddedToAFullFieldOfSpaces() {
            SystemMessages.AbendData area = SystemMessages.AbendData.spaces().withAbendCode("");

            assertThat(area.abendCode()).isEqualTo(" ".repeat(CODE_WIDTH)).hasSize(CODE_WIDTH);
        }
    }

    @Nested
    @DisplayName("ABEND-DATA - null has no COBOL counterpart and is rejected")
    class AbendDataNullGuards {
        @Test
        @DisplayName("a null ABEND-CODE is rejected, naming the COBOL field")
        void nullAbendCodeIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            null, SAMPLE_CULPRIT, SAMPLE_REASON, SAMPLE_MSG))
                    .withMessageContaining("abendCode")
                    .withMessageContaining("ABEND-CODE")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("a null ABEND-CULPRIT is rejected, naming the COBOL field")
        void nullAbendCulpritIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            SAMPLE_CODE, null, SAMPLE_REASON, SAMPLE_MSG))
                    .withMessageContaining("abendCulprit")
                    .withMessageContaining("ABEND-CULPRIT")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("a null ABEND-REASON is rejected, naming the COBOL field")
        void nullAbendReasonIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            SAMPLE_CODE, SAMPLE_CULPRIT, null, SAMPLE_MSG))
                    .withMessageContaining("abendReason")
                    .withMessageContaining("ABEND-REASON")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("a null ABEND-MSG is rejected, naming the COBOL field")
        void nullAbendMsgIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new SystemMessages.AbendData(
                            SAMPLE_CODE, SAMPLE_CULPRIT, SAMPLE_REASON, null))
                    .withMessageContaining("abendMsg")
                    .withMessageContaining("ABEND-MSG")
                    .withMessageContaining("VALUE SPACES");
        }

        @Test
        @DisplayName("four non-null components are accepted, exercising every guard's false side")
        void fourNonNullComponentsAreAccepted() {
            SystemMessages.AbendData area = sampleArea();

            assertThat(area.abendCode()).isEqualTo(SAMPLE_CODE);
            assertThat(area.abendCulprit()).isEqualTo(SAMPLE_CULPRIT);
            assertThat(area.abendReason()).isEqualTo(SAMPLE_REASON);
            assertThat(area.abendMsg()).isEqualTo(SAMPLE_MSG);
        }

        @Test
        @DisplayName("moving null into ABEND-CODE is rejected by the move, not by the constructor")
        void nullMovedIntoAbendCodeIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendCode(null))
                    .withMessageStartingWith("ABEND-CODE must not be null");
        }

        @Test
        @DisplayName("moving null into ABEND-CULPRIT is rejected by the move")
        void nullMovedIntoAbendCulpritIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendCulprit(null))
                    .withMessageStartingWith("ABEND-CULPRIT must not be null");
        }

        @Test
        @DisplayName("moving null into ABEND-REASON is rejected by the move")
        void nullMovedIntoAbendReasonIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendReason(null))
                    .withMessageStartingWith("ABEND-REASON must not be null");
        }

        @Test
        @DisplayName("moving null into ABEND-MSG is rejected by the move")
        void nullMovedIntoAbendMsgIsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> SystemMessages.AbendData.spaces().withAbendMsg(null))
                    .withMessageStartingWith("ABEND-MSG must not be null");
        }

        @Test
        @DisplayName("a rejected move leaves the original area untouched")
        void aRejectedMoveLeavesTheOriginalUntouched() {
            SystemMessages.AbendData original = sampleArea();

            assertThatNullPointerException().isThrownBy(() -> original.withAbendReason(null));

            assertThat(original.abendCode()).isEqualTo(SAMPLE_CODE);
            assertThat(original.abendCulprit()).isEqualTo(SAMPLE_CULPRIT);
            assertThat(original.abendReason()).isEqualTo(SAMPLE_REASON);
            assertThat(original.abendMsg()).isEqualTo(SAMPLE_MSG);
        }
    }

    @Nested
    @DisplayName("Shape and immutability - no mutable state anywhere")
    class ShapeAndImmutability {
        @Test
        @DisplayName("both message constants are public static final Strings")
        void messageConstantsArePublicStaticFinal() throws ReflectiveOperationException {
            for (String name : new String[] {"CCDA_MSG_THANK_YOU", "CCDA_MSG_INVALID_KEY"}) {
                Field field = SystemMessages.class.getDeclaredField(name);
                int modifiers = field.getModifiers();

                assertThat(Modifier.isPublic(modifiers)).as("%s is public", name).isTrue();
                assertThat(Modifier.isStatic(modifiers)).as("%s is static", name).isTrue();
                assertThat(Modifier.isFinal(modifiers)).as("%s is final", name).isTrue();
                assertThat(field.getType()).isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("every declared width constant is a public static final int")
        void widthConstantsArePublicStaticFinalInts() throws ReflectiveOperationException {
            String[] names = {
                "MESSAGE_LENGTH",
                "ABEND_CODE_LENGTH",
                "ABEND_CULPRIT_LENGTH",
                "ABEND_REASON_LENGTH",
                "ABEND_MSG_LENGTH",
                "ABEND_DATA_LENGTH",
            };

            for (String name : names) {
                Field field = SystemMessages.class.getDeclaredField(name);
                int modifiers = field.getModifiers();

                assertThat(Modifier.isPublic(modifiers)).as("%s is public", name).isTrue();
                assertThat(Modifier.isStatic(modifiers)).as("%s is static", name).isTrue();
                assertThat(Modifier.isFinal(modifiers)).as("%s is final", name).isTrue();
                assertThat(field.getType()).isEqualTo(int.class);
            }
        }

        @Test
        @DisplayName("no static field on either type is mutable")
        void noStaticFieldIsMutable() {
            assertNoMutableStaticFieldsOn(SystemMessages.class);
            assertNoMutableStaticFieldsOn(SystemMessages.AbendData.class);
        }

        @Test
        @DisplayName("SystemMessages is final and cannot be instantiated from outside")
        void systemMessagesIsFinalWithASinglePrivateConstructor() {
            assertThat(Modifier.isFinal(SystemMessages.class.getModifiers())).isTrue();

            Constructor<?>[] constructors = SystemMessages.class.getDeclaredConstructors();
            assertThat(constructors).hasSize(1);
            assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
            assertThat(constructors[0].getParameterCount()).isZero();
        }

        @Test
        @DisplayName("AbendData is a record whose four components are in copybook order")
        void abendDataIsARecordInCopybookOrder() {
            assertThat(SystemMessages.AbendData.class.isRecord()).isTrue();

            RecordComponent[] components =
                    SystemMessages.AbendData.class.getRecordComponents();

            assertThat(components).hasSize(4);
            assertThat(components[0].getName()).isEqualTo("abendCode");
            assertThat(components[1].getName()).isEqualTo("abendCulprit");
            assertThat(components[2].getName()).isEqualTo("abendReason");
            assertThat(components[3].getName()).isEqualTo("abendMsg");
            for (RecordComponent component : components) {
                assertThat(component.getType())
                        .as("%s is alphanumeric PIC X, so it maps to String", component.getName())
                        .isEqualTo(String.class);
            }
        }

        @Test
        @DisplayName("AbendData exposes no setter and no mutable instance field")
        void abendDataExposesNoSetter() {
            for (Method method : SystemMessages.AbendData.class.getDeclaredMethods()) {
                assertThat(method.getName())
                        .as("AbendData must expose no setter")
                        .doesNotStartWith("set");
            }
            for (Field field : SystemMessages.AbendData.class.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s is final", field.getName())
                        .isTrue();
                assertThat(Modifier.isPrivate(field.getModifiers()))
                        .as("%s is private", field.getName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a with... copy returns a new instance and leaves the original unchanged")
        void withCopyLeavesTheOriginalUnchanged() {
            SystemMessages.AbendData original = sampleArea();

            SystemMessages.AbendData copy = original.withAbendCode("9999");

            assertThat(copy).isNotSameAs(original).isNotEqualTo(original);
            assertThat(copy.abendCode()).isEqualTo("9999");
            assertThat(original.abendCode()).isEqualTo(SAMPLE_CODE);
        }

        @Test
        @DisplayName("normalising to declared widths returns a new instance, not a mutation")
        void normalisingReturnsANewInstance() {
            SystemMessages.AbendData original = sampleArea();

            SystemMessages.AbendData canonical = original.toDeclaredWidths();

            assertThat(canonical).isNotSameAs(original);
            assertThat(canonical.abendReason()).hasSize(REASON_WIDTH);
            assertThat(original.abendReason()).isEqualTo(SAMPLE_REASON);
        }

        @Test
        @DisplayName("two areas built from the same four components are equal")
        void areasWithEqualComponentsAreEqual() {
            SystemMessages.AbendData first = sampleArea();
            SystemMessages.AbendData second = sampleArea();

            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(SystemMessages.AbendData.spaces());
        }

        @Test
        @DisplayName("spaces() hands out a fresh, equal area every time")
        void spacesHandsOutAFreshEqualAreaEveryTime() {
            assertThat(SystemMessages.AbendData.spaces())
                    .isEqualTo(SystemMessages.AbendData.spaces());
        }

        private void assertNoMutableStaticFieldsOn(Class<?> type) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                        .as("%s.%s must be final: COBOL WORKING-STORAGE must never become mutable"
                                + " static Java state", type.getSimpleName(), field.getName())
                        .isTrue();
            }
        }
    }
}
