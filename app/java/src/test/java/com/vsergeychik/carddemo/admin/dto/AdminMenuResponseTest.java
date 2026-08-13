package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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
 * Tests for {@link AdminMenuResponse}, the outbound projection of {@code COADM1AO} in
 * {@code app/cpy-bms/COADM01.CPY} - the response payload of {@code GET /api/admin/menu}, CICS transaction
 * {@code CA00}, program {@code COADM01C}, mapset {@code COADM01}, map {@code COADM1A}.
 */
@DisplayName("AdminMenuResponse - the COADM1AO output projection of the CA00 admin menu")
class AdminMenuResponseTest {
    private static final List<String> EXPECTED_FIELDS = List.of("TRNNAMEO",
            "TITLE01O",
            "CURDATEO",
            "PGMNAMEO",
            "TITLE02O",
            "CURTIMEO",
            "OPTN001O",
            "OPTN002O",
            "OPTN003O",
            "OPTN004O",
            "OPTN005O",
            "OPTN006O",
            "OPTN007O",
            "OPTN008O",
            "OPTN009O",
            "OPTN010O",
            "OPTN011O",
            "OPTN012O",
            "OPTIONO",
            "ERRMSGO");

    private static final List<Integer> EXPECTED_WIDTHS = List.of(4,
            40,
            8,
            8,
            40,
            8,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            40,
            2,
            78);

    private static final List<String> EXPECTED_JSON_KEYS = List.of("trnname",
            "title01",
            "curdate",
            "pgmname",
            "title02",
            "curtime",
            "optn001",
            "optn002",
            "optn003",
            "optn004",
            "optn005",
            "optn006",
            "optn007",
            "optn008",
            "optn009",
            "optn010",
            "optn011",
            "optn012",
            "option",
            "errmsg",
            "navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap");

    private static final List<String> UNPUBLISHED_MEMBERS =
            List.of("messageColour", "resetAllOutputFields");

    private static final List<String> METADATA_SUFFIXES = List.of("L", "F", "A", "C", "P", "H", "V");

    private static final Charset MAP_CHARSET = StandardCharsets.US_ASCII;

    private static final Instant FIXED_INSTANT = Instant.parse("2022-07-19T23:12:32Z");

    private static final int WS_MESSAGE_LENGTH = 80;

    private static final List<String> ADMIN_OPTION_NAMES = List.of(
            "User List (Security)               ",
            "User Add (Security)                ",
            "User Update (Security)             ",
            "User Delete (Security)             ");

    private static final List<String> ADMIN_OPTION_PROGRAMS =
            List.of("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C");

    private static final int ADMIN_OPTION_COUNT = 4;

    private static final String VALIDATION_MESSAGE = "Please enter a valid option number...";

    private static final String COMING_SOON_MESSAGE = "This option " + "is coming soon ...";

    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    private static FixedWidthCodec codec() {
        return new FixedWidthCodec(MAP_CHARSET);
    }

    private static String adminOptionLine(final int cobolSubscript) {
        final FixedWidthCodec codec = codec();
        final String number = codec.movePic9(cobolSubscript, 2);
        final String stringed = codec.concatenateDelimitedBySize(number,
                ". ",
                ADMIN_OPTION_NAMES.get(cobolSubscript - 1));
        return codec.movePicX(stringed, AdminMenuResponse.OPTION_LINE_LENGTH);
    }

    private static AdminMenuResponse populated() {
        return AdminMenuResponse.builder()
                .trnName("CA00")
                .title01("      AWS Mainframe Modernization       ")
                .curDate("07/19/22")
                .pgmName("COADM01C")
                .title02("              CardDemo                  ")
                .curTime("23:12:32")
                .optn001("01. User List (Security)                ")
                .optn002("02. User Add (Security)                 ")
                .optn003("03. User Update (Security)              ")
                .optn004("04. User Delete (Security)              ")
                .option("01")
                .errMsg(spaces(AdminMenuResponse.ERR_MSG_LENGTH))
                .navigationContext(NavigationContext.empty()
                        .withUserId("ADMIN001")
                        .withUserTypeAdmin()
                        .withPgmReenter()
                        .withFromTranid("CA00")
                        .withFromProgram("COADM01C"))
                .nextProgram("COUSR00C")
                .build();
    }

    @Nested
    @DisplayName("Geometry - 20 of 28 DFHMDF fields, 668 payload bytes inside an 820-byte image")
    class Geometry {
        @Test
        @DisplayName("exactly 20 payload fields, in map order, named as the copybook names them")
        void payloadFieldsMatchTheCopybookExactly() {
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS)
                    .as("the xxxO items of COADM1AO, in map order")
                    .containsExactlyElementsOf(EXPECTED_FIELDS)
                    .hasSize(20);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_COUNT).isEqualTo(20);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT).isEqualTo(20);
        }

        @Test
        @DisplayName("28 DFHMDF definitions in the mapset, 20 named and 8 unnamed literals")
        void mapsetFieldCountsSplitTwentyEight() {
            assertThat(AdminMenuResponse.MAPSET_FIELD_COUNT).isEqualTo(28);
            assertThat(AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT).isEqualTo(8);
            assertThat(AdminMenuResponse.NAMED_MAP_FIELD_COUNT
                    + AdminMenuResponse.UNNAMED_MAP_FIELD_COUNT)
                    .isEqualTo(AdminMenuResponse.MAPSET_FIELD_COUNT);
        }

        @Test
        @DisplayName("the declared widths are 4, 40, 8, 8, 40, 8, forty times twelve, 2, 78")
        void declaredWidthsMatchThePictureClauses() {
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS)
                    .containsExactlyElementsOf(EXPECTED_WIDTHS);
            assertThat(AdminMenuResponse.TRN_NAME_LENGTH).isEqualTo(4);
            assertThat(AdminMenuResponse.TITLE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuResponse.CUR_DATE_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.PGM_NAME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.CUR_TIME_LENGTH).isEqualTo(8);
            assertThat(AdminMenuResponse.OPTION_LINE_LENGTH).isEqualTo(40);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(AdminMenuResponse.OPTION_LENGTH).isEqualTo(2);
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("ERRMSGO is 78 - never the 80 of WS-MESSAGE and never the 50 of the invalid-key text")
        void errMsgIsSeventyEightNotEightyNorFifty() {
            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78).isNotEqualTo(80).isNotEqualTo(50);
        }

        @Test
        @DisplayName("the twenty widths sum to 668")
        void payloadWidthsSumToSixHundredAndSixtyEight() {
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(668);
            assertThat(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
        }

        @Test
        @DisplayName("the whole image is 12 + 20 times 7 + 668 = 820 bytes")
        void symbolicMapImageIsEightHundredAndTwenty() {
            assertThat(AdminMenuResponse.TIOAPFX_FILLER_LENGTH).isEqualTo(12);
            assertThat(AdminMenuResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .as("FILLER X(3) plus the four attribute bytes xxxC, xxxP, xxxH, xxxV")
                    .isEqualTo(7);
            assertThat(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("the output stride equals the input stride, which is why the REDEFINES is legal")
        void theOutputStrideEqualsTheInputStride() {
            final int inputStride = 2 + 1 + 4;
            final int outputStride = AdminMenuResponse.ATTRIBUTE_FILLER_LENGTH + 4;

            assertThat(outputStride)
                    .as("FILLER X(3) plus xxxC, xxxP, xxxH, xxxV")
                    .isEqualTo(AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .isEqualTo(inputStride)
                    .isEqualTo(7);

            int inputOffset = AdminMenuResponse.TIOAPFX_FILLER_LENGTH;
            int outputOffset = AdminMenuResponse.TIOAPFX_FILLER_LENGTH;
            for (int index = 0; index < EXPECTED_WIDTHS.size(); index++) {
                final int width = EXPECTED_WIDTHS.get(index);
                assertThat(outputOffset + outputStride)
                        .as("%s begins at the same byte in both views", EXPECTED_FIELDS.get(index))
                        .isEqualTo(inputOffset + inputStride);
                inputOffset += inputStride + width;
                outputOffset += outputStride + width;
            }

            assertThat(outputOffset)
                    .as("both views end at the same byte")
                    .isEqualTo(inputOffset)
                    .isEqualTo(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("composing the twenty fields really does produce 668 bytes, and omitting one fails it")
        void composingTheTwentyFieldsProducesSixHundredSixtyEightBytes() {
            final FixedWidthCodec codec = codec();
            assertThat(codec.charset()).isEqualTo(MAP_CHARSET).isEqualTo(StandardCharsets.US_ASCII);

            final List<String> values = populated().payloadValues();
            final StringBuilder image = new StringBuilder();
            for (int index = 0; index < values.size(); index++) {
                image.append(codec.movePicX(values.get(index), EXPECTED_WIDTHS.get(index)));
            }

            assertThat(image.length()).isEqualTo(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
            assertThat(codec.encodeImage(image.toString(), "COADM1AO screen data")).hasSize(668);

            assertThat(image.length() - AdminMenuResponse.OPTION_LINE_LENGTH)
                    .as("omitting one forty-byte option line must not still total 668")
                    .isNotEqualTo(AdminMenuResponse.PAYLOAD_DATA_LENGTH);

            final FixedWidthRecord area =
                    new FixedWidthRecord(AdminMenuResponse.SYMBOLIC_MAP_LENGTH, MAP_CHARSET);
            assertThat(area.recordLength()).isEqualTo(820);
            assertThat(area.recordLength()
                    - AdminMenuResponse.TIOAPFX_FILLER_LENGTH
                    - AdminMenuResponse.PAYLOAD_FIELD_COUNT * AdminMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .as("what remains after the prefix and the twenty strides is the screen data")
                    .isEqualTo(668);
        }

        @Test
        @DisplayName("the screen is 24 by 80, as the single DFHMDI declares")
        void screenIsTwentyFourByEighty() {
            assertThat(AdminMenuResponse.SCREEN_ROWS).isEqualTo(24);
            assertThat(AdminMenuResponse.SCREEN_COLUMNS).isEqualTo(80);
        }

        @Test
        @DisplayName("names, widths and values stay positionally aligned across all three views")
        void namesWidthsAndValuesAreAligned() {
            final AdminMenuResponse response = populated();
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_LENGTHS).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(response.payloadValues()).hasSameSizeAs(EXPECTED_FIELDS);
            assertThat(response.payloadValues().get(0)).isEqualTo(response.trnName());
            assertThat(response.payloadValues().get(6)).isEqualTo(response.optn001());
            assertThat(response.payloadValues().get(17)).isEqualTo(response.optn012());
            assertThat(response.payloadValues().get(18)).isEqualTo(response.option());
            assertThat(response.payloadValues().get(19)).isEqualTo(response.errMsg());
        }

        @Test
        @DisplayName("the screen identity is CA00 / COADM01C / COADM01 / COADM1A")
        void screenIdentityMatchesTheCsdTheProgramAndTheMapset() {
            assertThat(AdminMenuResponse.TRANSACTION_ID).isEqualTo("CA00");
            assertThat(AdminMenuResponse.PROGRAM_NAME).isEqualTo("COADM01C");
            assertThat(AdminMenuResponse.MAPSET_NAME).isEqualTo("COADM01");
            assertThat(AdminMenuResponse.MAP_NAME).isEqualTo("COADM1A");
            assertThat(AdminMenuResponse.TRANSACTION_ID).hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(AdminMenuResponse.PROGRAM_NAME).hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
            assertThat(AdminMenuResponse.MAPSET_NAME)
                    .as("a mapset name is X(7), because the eighth character is the direction suffix")
                    .hasSize(7);
            assertThat(AdminMenuResponse.MAP_NAME).hasSize(7);
        }
    }

    @Nested
    @DisplayName("Twelve option slots - four filled, ten reachable, twelve declared")
    class TwelveSlots {
        @Test
        @DisplayName("all twelve slots exist and are addressable on a blank response")
        void allTwelveSlotsAreAddressableWhenUnset() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThat(blank.optionLines())
                    .hasSize(12)
                    .allSatisfy(line -> assertThat(line)
                            .isNotNull()
                            .hasSize(AdminMenuResponse.OPTION_LINE_LENGTH)
                            .isEqualTo(ScreenFieldImage.unpainted(
                                    AdminMenuResponse.OPTION_LINE_LENGTH)));
        }

        @Test
        @DisplayName("slots 5 to 12 are addressable even though the program never fills them")
        void slotsFiveToTwelveAreAddressableThoughNeverFilled() {
            final AdminMenuResponse response = populated();
            final String blankLine =
                    ScreenFieldImage.unpainted(AdminMenuResponse.OPTION_LINE_LENGTH);
            assertThat(response.optn005()).isEqualTo(blankLine);
            assertThat(response.optn006()).isEqualTo(blankLine);
            assertThat(response.optn007()).isEqualTo(blankLine);
            assertThat(response.optn008()).isEqualTo(blankLine);
            assertThat(response.optn009()).isEqualTo(blankLine);
            assertThat(response.optn010()).isEqualTo(blankLine);
            assertThat(response.optn011())
                    .as("OPTN011O has no assignment anywhere in COADM01C, yet the mapset declares it")
                    .isEqualTo(blankLine);
            assertThat(response.optn012())
                    .as("OPTN012O likewise")
                    .isEqualTo(blankLine);
        }

        @ParameterizedTest(name = "WS-IDX = {0} addresses OPTN0{0}O")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("optionLine accepts every COBOL subscript from 1 to 12")
        void optionLineAcceptsEveryCobolSubscript(final int cobolSubscript) {
            final String marker = "line " + cobolSubscript;
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .optionLine(cobolSubscript, marker)
                    .build();
            assertThat(response.optionLine(cobolSubscript)).isEqualTo(marker);
            assertThat(response.optionLines().get(cobolSubscript - 1))
                    .as("a one-based COBOL subscript is a zero-based Java index")
                    .isEqualTo(marker);
        }

        @ParameterizedTest(name = "subscript {0} is rejected")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 99, Integer.MAX_VALUE})
        @DisplayName("optionLine rejects a subscript outside the declared twelve, on both sides")
        void optionLineRejectsOutOfRangeSubscripts(final int cobolSubscript) {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> blank.optionLine(cobolSubscript))
                    .withMessageContaining("1 to 12")
                    .withMessageContaining("COADM01");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> AdminMenuResponse.builder().optionLine(cobolSubscript, "x"))
                    .withMessageContaining("1 to 12");
        }

        @Test
        @DisplayName("the twelve slot names are OPTN001O to OPTN012O in order")
        void slotNamesRunFromOptn001oToOptn012o() {
            assertThat(AdminMenuResponse.OPTION_LINE_FIELDS)
                    .containsExactly("OPTN001O",
                            "OPTN002O",
                            "OPTN003O",
                            "OPTN004O",
                            "OPTN005O",
                            "OPTN006O",
                            "OPTN007O",
                            "OPTN008O",
                            "OPTN009O",
                            "OPTN010O",
                            "OPTN011O",
                            "OPTN012O");
        }

        @Test
        @DisplayName("the slot count is twelve - not the four the program fills, not the ten it can reach")
        void slotCountIsTwelveNotFourAndNotTen() {
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12).isNotEqualTo(4).isNotEqualTo(10);
            assertThat(AdminMenuResponse.OPTION_LINE_FIELDS).hasSize(12);
            assertThat(AdminMenuResponse.empty().optionLines()).hasSize(12);
        }

        @Test
        @DisplayName("each named slot setter writes only its own slot")
        void eachNamedSetterWritesOnlyItsOwnSlot() {
            final List<UnaryOperator<AdminMenuResponse.Builder>> setters = List.of(
                    builder -> builder.optn001("v"),
                    builder -> builder.optn002("v"),
                    builder -> builder.optn003("v"),
                    builder -> builder.optn004("v"),
                    builder -> builder.optn005("v"),
                    builder -> builder.optn006("v"),
                    builder -> builder.optn007("v"),
                    builder -> builder.optn008("v"),
                    builder -> builder.optn009("v"),
                    builder -> builder.optn010("v"),
                    builder -> builder.optn011("v"),
                    builder -> builder.optn012("v"));

            for (int slot = 1; slot <= setters.size(); slot++) {
                final AdminMenuResponse response =
                        setters.get(slot - 1).apply(AdminMenuResponse.builder()).build();
                assertThat(response.optionLine(slot)).as("slot %d written", slot).isEqualTo("v");
                assertThat(response.optionLines())
                        .as("only slot %d written", slot)
                        .filteredOn("v"::equals)
                        .hasSize(1);
            }
        }
    }

    @Nested
    @DisplayName("The initial state - MOVE LOW-VALUES TO COADM1AO before the first SEND")
    class InitialState {
        @Test
        @DisplayName("every text field is space-filled to its own declared width")
        void everyTextFieldIsUnpaintedToItsDeclaredWidth() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            final List<String> values = blank.payloadValues();
            for (int i = 0; i < values.size(); i++) {
                assertThat(values.get(i))
                        .as("%s is LOW-VALUES at its declared width", EXPECTED_FIELDS.get(i))
                        .isEqualTo(ScreenFieldImage.unpainted(EXPECTED_WIDTHS.get(i)));
            }
        }

        @Test
        @DisplayName("the communication area is the initial 160-byte COMMAREA")
        void communicationAreaIsTheInitialCommarea() {
            assertThat(AdminMenuResponse.empty().navigationContext())
                    .isEqualTo(NavigationContext.empty());
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
        }

        @Test
        @DisplayName("the screen to render is this screen, and the next program is left undecided")
        void nextScreenIsThisScreenAndNextProgramIsUndecided() {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            assertThat(blank.nextMapset()).isEqualTo("COADM01");
            assertThat(blank.nextMap()).isEqualTo("COADM1A");
            assertThat(blank.nextProgram())
                    .as("choosing an XCTL target is a decision, and decisions belong to the service")
                    .isEqualTo(spaces(AdminMenuResponse.NEXT_PROGRAM_LENGTH))
                    .isBlank();
        }

        @Test
        @DisplayName("the repaint signal is off on a blank instance")
        void repaintSignalIsOffOnABlankInstance() {
            assertThat(AdminMenuResponse.empty().resetAllOutputFields()).isFalse();
            assertThat(AdminMenuResponse.builder().resetAllOutputFields(true).build()
                    .resetAllOutputFields()).isTrue();
        }

        @Test
        @DisplayName("builder() and empty() cannot drift apart")
        void builderAndEmptyAgree() {
            assertThat(AdminMenuResponse.builder().build()).isEqualTo(AdminMenuResponse.empty());
        }
    }

    @Nested
    @DisplayName("Absent members become their COBOL-initial values, because COBOL has no null")
    class NullNormalisation {
        @Test
        @DisplayName("an absent character member becomes spaces at its declared width")
        void absentCharacterMembersBecomeSpaces() {
            final AdminMenuResponse response = new AdminMenuResponse(null,
                    null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    BmsAttributes.DFHRED, false);

            assertThat(response.trnName()).isEqualTo(ScreenFieldImage.unpainted(4));
            assertThat(response.title01()).isEqualTo(ScreenFieldImage.unpainted(40));
            assertThat(response.curDate()).isEqualTo(ScreenFieldImage.unpainted(8));
            assertThat(response.pgmName()).isEqualTo(ScreenFieldImage.unpainted(8));
            assertThat(response.title02()).isEqualTo(ScreenFieldImage.unpainted(40));
            assertThat(response.curTime()).isEqualTo(ScreenFieldImage.unpainted(8));
            assertThat(response.optionLines())
                    .allSatisfy(line -> assertThat(line).isEqualTo(ScreenFieldImage.unpainted(40)));
            assertThat(response.option()).isEqualTo(ScreenFieldImage.unpainted(2));
            assertThat(response.errMsg()).isEqualTo(ScreenFieldImage.unpainted(78));
            assertThat(response.nextProgram()).isEqualTo(spaces(8));
            assertThat(response.nextMapset()).isEqualTo(spaces(7));
            assertThat(response.nextMap()).isEqualTo(spaces(7));
        }

        @Test
        @DisplayName("an explicit null communication area is kept, because EIBCALEN = 0 is a real state")
        void anExplicitlyAbsentCommunicationAreaIsKept() {
            final AdminMenuResponse transferred = AdminMenuResponse.builder()
                    .navigationContext(null)
                    .build();
            assertThat(transferred.navigationContext())
                    .describedAs("null means 'no communication area travelled', and it survives")
                    .isNull();

            assertThat(AdminMenuResponse.builder().build().navigationContext())
                    .isNotNull()
                    .isEqualTo(NavigationContext.empty());
            assertThat(AdminMenuResponse.empty().navigationContext())
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("a supplied value is stored verbatim - never padded, never trimmed, never truncated")
        void suppliedValuesAreStoredVerbatim() {
            final String shorterThanDeclared = "1";
            final String longerThanDeclared = "x".repeat(200);
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .option(shorterThanDeclared)
                    .errMsg(longerThanDeclared)
                    .navigationContext(NavigationContext.empty().withUserId("USER0001"))
                    .build();

            assertThat(response.option())
                    .as("padding is FixedWidthCodec's MOVE rule, not this type's")
                    .isEqualTo("1");
            assertThat(response.errMsg())
                    .as("narrowing X(80) to X(78) is the controller's work, not this type's")
                    .isEqualTo(longerThanDeclared);
            assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
        }
    }

    @Nested
    @DisplayName("OPTIONO is textual and zero-filled - MOVE WS-OPTION PIC 9(02) TO OPTIONO PIC X(2)")
    class OptionEcho {
        @ParameterizedTest(name = "WS-OPTION {0} renders as \"{1}\"")
        @CsvSource({"1,01", "2,02", "3,03", "4,04", "9,09", "10,10", "12,12", "99,99"})
        @DisplayName("a single-digit option keeps its leading zero")
        void singleDigitOptionsKeepTheirLeadingZero(final String entered, final String rendered) {
            final AdminMenuResponse response = AdminMenuResponse.builder().option(rendered).build();
            assertThat(response.option())
                    .as("WS-OPTION %s moved into a PIC X(2) receiver", entered)
                    .isEqualTo(rendered)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
        }

        @Test
        @DisplayName("\"nothing entered yet\" is LOW-VALUES - a third state no integer has")
        void nothingEnteredYetIsUnpainted() {
            assertThat(AdminMenuResponse.empty().option())
                    .isEqualTo(ScreenFieldImage.unpainted(AdminMenuResponse.OPTION_LENGTH))
                    .isNotEqualTo("00")
                    .isNotEqualTo("  ");
        }

        @Test
        @DisplayName("the echo is a String, so \"01\" and \"1\" stay distinct")
        void theEchoIsTextualSoLeadingZerosAreSignificant() {
            assertThat(AdminMenuResponse.builder().option("01").build().option())
                    .isNotEqualTo(AdminMenuResponse.builder().option("1").build().option());
        }
    }

    @Nested
    @DisplayName("Navigation - EXEC CICS XCTL becomes three response members")
    class Navigation {
        @ParameterizedTest(name = "nextProgram can carry {0}")
        @ValueSource(strings = {"COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C", "COSGN00C"})
        @DisplayName("both XCTL sites are expressible: the four option targets and the sign-on return")
        void bothXctlSitesAreExpressible(final String target) {
            final AdminMenuResponse response =
                    AdminMenuResponse.builder().nextProgram(target).build();
            assertThat(response.nextProgram())
                    .isEqualTo(target)
                    .hasSizeLessThanOrEqualTo(AdminMenuResponse.NEXT_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the sign-on literal of COADM01C is recorded, but is not a default")
        void signonProgramIsRecordedButNotDefaulted() {
            assertThat(AdminMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(AdminMenuResponse.empty().nextProgram())
                    .isNotEqualTo(AdminMenuResponse.SIGNON_PROGRAM);
        }

        @Test
        @DisplayName("the navigation widths come from the COMMAREA: a program is 8, a map is 7")
        void navigationWidthsComeFromTheCommarea() {
            assertThat(AdminMenuResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(8)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(AdminMenuResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(7)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH);
        }

        @Test
        @DisplayName("the screen to render is overridable, so the client can be sent elsewhere")
        void theScreenToRenderIsOverridable() {
            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .nextMapset("COUSR00")
                    .nextMap("COUSR0A")
                    .build();
            assertThat(response.nextMapset()).isEqualTo("COUSR00");
            assertThat(response.nextMap()).isEqualTo("COUSR0A");
        }
    }

    @Nested
    @DisplayName("The message colour - DFHRED by declaration, DFHGREEN by override, metadata either way")
    class MessageColour {
        @Test
        @DisplayName("a freshly built response carries DFHRED, as the mapset's COLOR=RED declares")
        void freshResponseCarriesDfhred() {
            assertThat(AdminMenuResponse.empty().messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(AdminMenuResponse.builder().build().messageColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(populated().messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
        }

        @Test
        @DisplayName("the coming-soon path can move DFHGREEN in, exactly as COADM01C:148 does")
        void comingSoonPathCanMoveDfhgreenIn() {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .errMsg("This option is coming soon ...")
                    .build();
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(response.errMsg())
                    .as("the admin text carries no option name - COADM01C:150-151 are commented out")
                    .isEqualTo("This option is coming soon ...")
                    .doesNotContain("User List");
        }

        @Test
        @DisplayName("the colour renders as its DFHBMSCA mnemonic and as unsigned hex")
        void colourRendersAsMnemonicAndHex() {
            assertThat(AdminMenuResponse.empty().messageColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHRED));
            assertThat(AdminMenuResponse.empty().messageColourHex())
                    .isEqualTo(BmsAttributes.toHex(BmsAttributes.DFHRED));
            assertThat(AdminMenuResponse.builder().messageColour(BmsAttributes.DFHGREEN).build()
                    .messageColourMnemonic())
                    .isEqualTo(BmsAttributes.colourMnemonic(BmsAttributes.DFHGREEN));
        }

        @Test
        @DisplayName("the default is never the terminal default colour")
        void defaultIsNeverDfhdfcol() {
            assertThat(AdminMenuResponse.empty().messageColour())
                    .as("a builder whose primitive defaulted would give DFHDFCOL")
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("the colour is metadata: carried, but never under the ERRMSGC field name")
        void theColourIsMetadataNotAnErrMsgCPayloadMember() throws Exception {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .build();

            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.screenMetadata().messageColour())
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHGREEN));

            final List<String> components = Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertThat(components)
                    .as("ERRMSGC is metadata; the colour rides as messageColour instead")
                    .doesNotContain("errMsgC", "errmsgc", "ERRMSGC")
                    .contains("messageColour");

            assertThat(AdminMenuResponse.class
                    .getRecordComponents()[components.indexOf("messageColour")]
                    .getAccessor()
                    .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class))
                    .isTrue();
            assertThat(new ObjectMapper().writeValueAsString(response))
                    .doesNotContain("errMsgC")
                    .doesNotContain("messageColour");
        }
    }

    @Nested
    @DisplayName("JSON - twenty screen fields plus five carriers, and no attribute metadata")
    class Json {
        private final ObjectMapper mapper = new ObjectMapper();

        private Map<String, Object> serialize(final AdminMenuResponse response) throws Exception {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = mapper.readValue(mapper.writeValueAsString(response),
                    LinkedHashMap.class);
            return map;
        }

        @Test
        @DisplayName("the published keys are exactly the twenty-six declared members")
        void publishedKeysAreExactlyTheDeclaredMembers() throws Exception {
            assertThat(serialize(populated()).keySet())
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_JSON_KEYS);
        }

        @Test
        @DisplayName("a non-default colour and reset signal do not survive a round trip, by design")
        void theColourDoesNotTravel() throws Exception {
            final AdminMenuResponse coloured = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build();

            final AdminMenuResponse after =
                    mapper.readValue(mapper.writeValueAsString(coloured), AdminMenuResponse.class);

            assertThat(after.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(after.resetAllOutputFields()).isFalse();
            assertThat(after.payloadValues()).containsExactlyElementsOf(coloured.payloadValues());
            assertThat(after.navigationContext()).isEqualTo(coloured.navigationContext());
        }

        @Test
        @DisplayName("the ERRMSGC colour byte and the reset signal are not JSON properties")
        void theTwoMetadataMembersAreNotPublished() throws Exception {
            final AdminMenuResponse response = populated().toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build();

            assertThat(serialize(response).keySet()).doesNotContainAnyElementsOf(UNPUBLISHED_MEMBERS);

            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(response.resetAllOutputFields()).isTrue();
            assertThat(Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList())
                    .containsAll(UNPUBLISHED_MEMBERS);
        }

        @Test
        @DisplayName("no xxxL, xxxF, xxxA, xxxC, xxxP, xxxH or xxxV item leaks into the payload")
        void noAttributeMetadataLeaksIntoThePayload() throws Exception {
            final List<String> keys = new ArrayList<>(serialize(populated()).keySet());
            final List<String> forbidden = new ArrayList<>();
            for (final String base : EXPECTED_JSON_KEYS.subList(0, 20)) {
                for (final String suffix : METADATA_SUFFIXES) {
                    forbidden.add(base + suffix);
                }
            }
            for (final String base : EXPECTED_FIELDS) {
                final String stem = base.substring(0, base.length() - 1);
                for (final String suffix : METADATA_SUFFIXES) {
                    forbidden.add(stem + suffix);
                }
            }
            assertThat(keys).doesNotContainAnyElementsOf(forbidden);

            assertThat(keys)
                    .as("the four attribute bytes are metadata; the colour is carried separately")
                    .doesNotContain("errMsgC", "ERRMSGC", "errMsgA", "trnNameL", "optn001C")
                    .doesNotContain("optionP", "OPTIONP")
                    .doesNotContain("title01H", "TITLE01H")
                    .doesNotContain("curDateV", "CURDATEV");

            final List<String> components = Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertThat(components)
                    .doesNotContain("errMsgC", "optionP", "title01H", "curDateV")
                    .contains("messageColour");
        }

        @Test
        @DisplayName("no derived view is published")
        void noDerivedViewIsPublished() throws Exception {
            assertThat(serialize(populated()).keySet())
                    .doesNotContain("optionLines",
                            "payloadValues",
                            "messageColourMnemonic",
                            "messageColourHex");
        }

        @Test
        @DisplayName("a round trip preserves all twenty fields, spaces and leading zeros included")
        void roundTripPreservesEveryField() throws Exception {
            final AdminMenuResponse original = populated();
            final String json = mapper.writeValueAsString(original);
            final AdminMenuResponse restored = mapper.readValue(json, AdminMenuResponse.class);

            assertThat(restored.payloadValues()).containsExactlyElementsOf(original.payloadValues());
            assertThat(restored.option()).isEqualTo("01");
            assertThat(restored.optn012())
                    .as("an all-LOW-VALUES value must not be trimmed away")
                    .isEqualTo(ScreenFieldImage.unpainted(AdminMenuResponse.OPTION_LINE_LENGTH));
            assertThat(restored.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(restored.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("a blank response round-trips too, and absent keys normalise rather than fail")
        void blankResponseRoundTrips() throws Exception {
            final AdminMenuResponse blank = AdminMenuResponse.empty();
            final AdminMenuResponse afterBlank =
                    mapper.readValue(mapper.writeValueAsString(blank), AdminMenuResponse.class);

            assertThat(afterBlank.payloadValues()).containsExactlyElementsOf(blank.payloadValues());
            assertThat(afterBlank.navigationContext()).isEqualTo(blank.navigationContext());
            assertThat(afterBlank.nextMapset()).isEqualTo(blank.nextMapset());
            assertThat(afterBlank.nextMap()).isEqualTo(blank.nextMap());

            assertThat(afterBlank.messageColour()).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(afterBlank.resetAllOutputFields()).isFalse();
            assertThat(mapper.readValue("{}", AdminMenuResponse.class).optionLines())
                    .hasSize(12)
                    .allSatisfy(line -> assertThat(line)
                            .isEqualTo(ScreenFieldImage.unpainted(40)));
        }
    }

    @Nested
    @DisplayName("Immutability - a handed-out response cannot change underneath its holder")
    class Immutability {
        @Test
        @DisplayName("the twelve option lines are handed back unmodifiable")
        void optionLinesAreUnmodifiable() {
            final List<String> lines = AdminMenuResponse.empty().optionLines();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.set(0, "mutated"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> lines.add("appended"));
            assertThat(AdminMenuResponse.empty().optionLines().get(0))
                    .isEqualTo(ScreenFieldImage.unpainted(40));
        }

        @Test
        @DisplayName("the twenty payload values are handed back unmodifiable")
        void payloadValuesAreUnmodifiable() {
            final List<String> values = populated().payloadValues();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.set(0, "mutated"));
            assertThat(populated().trnName()).isEqualTo("CA00");
        }

        @Test
        @DisplayName("the static name and width tables are unmodifiable")
        void staticTablesAreUnmodifiable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.PAYLOAD_FIELDS.set(0, "mutated"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.OPTION_LINE_FIELDS.clear());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> AdminMenuResponse.PAYLOAD_FIELD_LENGTHS.set(0, 99));
            assertThat(AdminMenuResponse.PAYLOAD_FIELDS).containsExactlyElementsOf(EXPECTED_FIELDS);
        }

        @Test
        @DisplayName("toBuilder produces a copy, leaving the original untouched")
        void toBuilderProducesACopy() {
            final AdminMenuResponse original = populated();
            final AdminMenuResponse modified = original.toBuilder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .optn001("changed")
                    .nextProgram("COSGN00C")
                    .build();

            assertThat(original.messageColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.optn001()).isEqualTo("01. User List (Security)                ");
            assertThat(original.nextProgram()).isEqualTo("COUSR00C");
            assertThat(modified.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(modified.optn001()).isEqualTo("changed");
            assertThat(modified.nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("reusing a builder does not disturb a value it already produced")
        void reusingABuilderDoesNotDisturbAnAlreadyBuiltValue() {
            final AdminMenuResponse.Builder builder = AdminMenuResponse.builder().optn001("first");
            final AdminMenuResponse first = builder.build();
            builder.optn001("second").optn002("also second");
            final AdminMenuResponse second = builder.build();

            assertThat(first.optn001()).isEqualTo("first");
            assertThat(first.optn002()).isEqualTo(ScreenFieldImage.unpainted(40));
            assertThat(second.optn001()).isEqualTo("second");
            assertThat(second.optn002()).isEqualTo("also second");
        }
    }

    @Nested
    @DisplayName("Statelessness - the conversation travels in the body, not on the server")
    class Statelessness {
        @Test
        @DisplayName("the echoed COMMAREA carries the user type and the program context back")
        void echoedCommareaCarriesUserTypeAndProgramContext() {
            final AdminMenuResponse response = populated();
            assertThat(response.navigationContext().isAdmin()).isTrue();
            assertThat(response.navigationContext().isUser()).isFalse();
            assertThat(response.navigationContext().isReenter()).isTrue();
            assertThat(response.navigationContext().isEnter()).isFalse();
            assertThat(response.navigationContext().userId()).isEqualTo("ADMIN001");
        }

        @Test
        @DisplayName("the first-entry repaint is a payload flag, not a server-side screen buffer")
        void firstEntryRepaintIsAPayloadFlag() {
            final AdminMenuResponse firstEntry = AdminMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .resetAllOutputFields(true)
                    .build();
            assertThat(firstEntry.resetAllOutputFields()).isTrue();
            assertThat(firstEntry.navigationContext().isReenter()).isTrue();
            assertThat(firstEntry.payloadValues())
                    .as("LOW-VALUES leaves every output field at X'00', which is not Java-blank")
                    .allSatisfy(value -> assertThat(ScreenFieldImage.isUnpainted(value)).isTrue());
        }

        @Test
        @DisplayName("there is no server-side session state of any kind")
        void thereIsNoServerSideSessionState() {
            final List<Field> declaredState = Stream.of(AdminMenuResponse.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic())
                    .toList();

            assertThat(declaredState).as("a record declares one field per component").isNotEmpty();
            for (final Field field : declaredState) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final - no static mutable state",
                                    field.getName())
                            .isTrue();
                } else {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("instance field %s must be final", field.getName())
                            .isTrue();
                    assertThat(Modifier.isPrivate(field.getModifiers()))
                            .as("instance field %s must be private", field.getName())
                            .isTrue();
                }
            }

            assertThat(AdminMenuResponse.class.isRecord()).isTrue();

            assertThat(Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .distinct()
                    .toList())
                    .containsExactlyInAnyOrder("java.lang.String",
                            "byte",
                            "boolean",
                            NavigationContext.class.getName());
        }

        @Test
        @DisplayName("the carried COMMAREA really is 160 bytes, at the code page stated explicitly")
        void theCarriedCommareaIsOneHundredSixtyBytes() {
            assertThat(NavigationContext.GENERAL_INFO_LENGTH).isEqualTo(34);
            assertThat(NavigationContext.CUSTOMER_INFO_LENGTH).isEqualTo(84);
            assertThat(NavigationContext.ACCOUNT_INFO_LENGTH).isEqualTo(12);
            assertThat(NavigationContext.CARD_INFO_LENGTH).isEqualTo(16);
            assertThat(NavigationContext.MORE_INFO_LENGTH).isEqualTo(14);
            assertThat(34 + 84 + 12 + 16 + 14)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(160);

            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(AdminMenuResponse.MAP_NAME).hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(AdminMenuResponse.MAPSET_NAME).hasSize(NavigationContext.LAST_MAPSET_LENGTH);

            final FixedWidthCodec codec = codec();
            assertThat(codec.charset()).isEqualTo(MAP_CHARSET);
            assertThat(populated().navigationContext().toFixedWidth(codec)).hasSize(160);
            assertThat(AdminMenuResponse.empty().navigationContext().toFixedWidth(codec)).hasSize(160);
        }
    }

    @Nested
    @DisplayName("Value semantics - every one of the twenty-six members participates in equality")
    class ValueSemantics {
        static Stream<Arguments> oneChangePerMember() {
            return Stream.of(
                    Arguments.of("trnName", op(builder -> builder.trnName("ZZZZ"))),
                    Arguments.of("title01", op(builder -> builder.title01("changed"))),
                    Arguments.of("curDate", op(builder -> builder.curDate("01/01/00"))),
                    Arguments.of("pgmName", op(builder -> builder.pgmName("ZZZZZZZZ"))),
                    Arguments.of("title02", op(builder -> builder.title02("changed"))),
                    Arguments.of("curTime", op(builder -> builder.curTime("00:00:00"))),
                    Arguments.of("optn001", op(builder -> builder.optn001("changed"))),
                    Arguments.of("optn002", op(builder -> builder.optn002("changed"))),
                    Arguments.of("optn003", op(builder -> builder.optn003("changed"))),
                    Arguments.of("optn004", op(builder -> builder.optn004("changed"))),
                    Arguments.of("optn005", op(builder -> builder.optn005("changed"))),
                    Arguments.of("optn006", op(builder -> builder.optn006("changed"))),
                    Arguments.of("optn007", op(builder -> builder.optn007("changed"))),
                    Arguments.of("optn008", op(builder -> builder.optn008("changed"))),
                    Arguments.of("optn009", op(builder -> builder.optn009("changed"))),
                    Arguments.of("optn010", op(builder -> builder.optn010("changed"))),
                    Arguments.of("optn011", op(builder -> builder.optn011("changed"))),
                    Arguments.of("optn012", op(builder -> builder.optn012("changed"))),
                    Arguments.of("option", op(builder -> builder.option("99"))),
                    Arguments.of("errMsg", op(builder -> builder.errMsg("changed"))),
                    Arguments.of("navigationContext",
                            op(builder -> builder.navigationContext(
                                    NavigationContext.empty().withUserId("OTHER001")))),
                    Arguments.of("nextProgram", op(builder -> builder.nextProgram("COSGN00C"))),
                    Arguments.of("nextMapset", op(builder -> builder.nextMapset("COMEN01"))),
                    Arguments.of("nextMap", op(builder -> builder.nextMap("COMEN1A"))),
                    Arguments.of("messageColour",
                            op(builder -> builder.messageColour(BmsAttributes.DFHGREEN))),
                    Arguments.of("resetAllOutputFields",
                            op(builder -> builder.resetAllOutputFields(true))));
        }

        private static UnaryOperator<AdminMenuResponse.Builder> op(
                final UnaryOperator<AdminMenuResponse.Builder> operator) {
            return operator;
        }

        @ParameterizedTest(name = "changing {0} breaks equality")
        @MethodSource("oneChangePerMember")
        @DisplayName("a difference in any single member is observable")
        void aDifferenceInAnySingleMemberIsObservable(
                final String member, final UnaryOperator<AdminMenuResponse.Builder> change) {
            final AdminMenuResponse original = populated();
            final AdminMenuResponse changed = change.apply(original.toBuilder()).build();

            assertThat(changed).as("%s must participate in equals", member).isNotEqualTo(original);
            assertThat(changed.hashCode())
                    .as("%s must participate in hashCode", member)
                    .isNotEqualTo(original.hashCode());
        }

        @Test
        @DisplayName("two identically built responses are equal and share a hash code")
        void identicallyBuiltResponsesAreEqual() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(AdminMenuResponse.empty()).isEqualTo(AdminMenuResponse.empty());
        }

        @Test
        @DisplayName("a response is not equal to null or to an unrelated type")
        void aResponseIsNotEqualToNullOrToAnUnrelatedType() {
            final AdminMenuResponse response = populated();
            assertThat(response).isNotEqualTo(null).isNotEqualTo("CA00").isEqualTo(response);
        }

        @Test
        @DisplayName("toString names the type and carries the screen fields")
        void toStringNamesTheTypeAndCarriesTheScreenFields() {
            assertThat(populated().toString())
                    .contains("AdminMenuResponse")
                    .contains("trnName=CA00")
                    .contains("option=01");
        }
    }

    @Nested
    @DisplayName("screenMetadata - the two values no JSON member of this record carries")
    class TheMetadataEnvelope {
        @Test
        @DisplayName("it publishes the message colour and the repaint signal, and nothing else")
        void itPublishesTheTwoMetadataValues() {
            final ScreenMetadata metadata = AdminMenuResponse.builder()
                    .messageColour(BmsAttributes.DFHGREEN)
                    .resetAllOutputFields(true)
                    .build()
                    .screenMetadata();

            assertThat(metadata.messageColour())
                    .as("published unsigned: DFHGREEN is 0xF4, which as a byte would read -12")
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHGREEN));
            assertThat(metadata.resetAllOutputFields()).isTrue();
        }

        @Test
        @DisplayName("the field map is empty, because COADM01 declares no per-field attribute quads")
        void theFieldMapIsAccuratelyEmpty() {
            assertThat(AdminMenuResponse.empty().screenMetadata().fields()).isEmpty();
        }

        @Test
        @DisplayName("no cursor is named: COADM01C contains no MOVE -1 TO <field>L at all")
        void noCursorIsNamed() {
            assertThat(AdminMenuResponse.empty().screenMetadata().cursorField()).isNull();
            assertThat(populated().screenMetadata().cursorField()).isNull();
        }

        @Test
        @DisplayName("the default state is DFHRED and no repaint, matching the mapset's COLOR=RED")
        void theDefaultStateIsTheMapsetDeclaration() {
            final ScreenMetadata metadata = AdminMenuResponse.empty().screenMetadata();

            assertThat(metadata.messageColour())
                    .isEqualTo(Byte.toUnsignedInt(BmsAttributes.DFHRED));
            assertThat(metadata.resetAllOutputFields()).isFalse();
        }

        @Test
        @DisplayName("the projection is itself @JsonIgnore, so it adds no member to this document")
        void theProjectionIsNotAJsonMember() throws Exception {
            assertThat(AdminMenuResponse.class.getMethod("screenMetadata")
                    .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class)).isTrue();
            assertThat(new ObjectMapper().writeValueAsString(populated()))
                    .doesNotContain("screenMetadata")
                    .doesNotContain("messageColour")
                    .doesNotContain("resetAllOutputFields");
        }
    }

    @Nested
    @DisplayName("ERRMSGO - an X(80) message narrowed to X(78) by RIGHT truncation")
    class ErrMsgNarrowing {
        @Test
        @DisplayName("a distinguishable 80-character probe loses positions 79 and 80, not 1 and 2")
        void aDistinguishableProbeProvesTruncationIsOnTheRight() {
            final StringBuilder probe = new StringBuilder();
            for (int position = 1; position <= WS_MESSAGE_LENGTH; position++) {
                probe.append(position % 10 == 0 ? Character.forDigit(position / 10, 16) : '.');
            }
            assertThat(probe.length()).isEqualTo(80);

            final FixedWidthCodec codec = codec();
            assertThat(codec.charset()).isEqualTo(MAP_CHARSET);

            final String narrowed = codec.movePicX(probe.toString(), AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(narrowed).hasSize(78).isEqualTo(probe.substring(0, 78));
            assertThat(narrowed.charAt(0))
                    .as("position 1 must survive - a left truncation would have discarded it")
                    .isEqualTo(probe.charAt(0));
            assertThat(narrowed.charAt(69))
                    .as("the decade marker at position 70 must still be at position 70")
                    .isEqualTo('7');
            assertThat(narrowed)
                    .as("the position-80 marker is the byte that must be gone")
                    .doesNotContain("8");

            final String tagged = "A".repeat(78) + "YZ";
            assertThat(tagged).hasSize(80);
            assertThat(codec.movePicX(tagged, AdminMenuResponse.ERR_MSG_LENGTH))
                    .hasSize(78)
                    .isEqualTo("A".repeat(78))
                    .doesNotContain("Y")
                    .doesNotContain("Z");

            assertThat(AdminMenuResponse.builder().errMsg(narrowed).build().errMsg())
                    .hasSize(AdminMenuResponse.ERR_MSG_LENGTH)
                    .hasSize(78);
            assertThat(WS_MESSAGE_LENGTH - AdminMenuResponse.ERR_MSG_LENGTH)
                    .as("exactly two bytes are lost")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("a message shorter than the field is padded on the right, to exactly 78")
        void aShortMessageIsRightPaddedToSeventyEight() {
            final FixedWidthCodec codec = codec();
            final String padded = codec.movePicX(VALIDATION_MESSAGE, AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(VALIDATION_MESSAGE).hasSize(37);
            assertThat(padded)
                    .hasSize(78)
                    .startsWith(VALIDATION_MESSAGE)
                    .endsWith(spaces(78 - 37));
            assertThat(AdminMenuResponse.builder().errMsg(padded).build().errMsg())
                    .isEqualTo(padded)
                    .hasSize(78);
        }

        @Test
        @DisplayName("no message at all is 78 spaces - never null, never empty")
        void noMessageIsSeventyEightSpaces() {
            assertThat(AdminMenuResponse.empty().errMsg())
                    .isNotNull()
                    .isNotEmpty()
                    .hasSize(78)
                    .isEqualTo(ScreenFieldImage.unpainted(AdminMenuResponse.ERR_MSG_LENGTH));
            assertThat(codec().movePicX("", AdminMenuResponse.ERR_MSG_LENGTH))
                    .isEqualTo(spaces(78))
                    .hasSize(78);
        }

        @ParameterizedTest(name = "{0} narrows to exactly 78 characters")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.AdminMenuResponseTest#everyMessagePathOfCoadm01c")
        @DisplayName("every message path of COADM01C reaches ERRMSGO at exactly 78 characters")
        void everyMessagePathReachesSeventyEightCharacters(final String path, final String message) {
            final String narrowed = codec().movePicX(message, AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(narrowed).as("%s", path).hasSize(78).hasSize(AdminMenuResponse.ERR_MSG_LENGTH);
            assertThat(narrowed.stripTrailing())
                    .as("%s keeps its text, only its padding differs", path)
                    .isEqualTo(message.stripTrailing());
            assertThat(AdminMenuResponse.builder().errMsg(narrowed).build().errMsg()).hasSize(78);
        }

        @Test
        @DisplayName("the coming-soon text keeps the space before \"is\", unlike COMEN01C's")
        void theComingSoonTextKeepsTheSpaceBeforeIs() {
            assertThat(COMING_SOON_MESSAGE)
                    .isEqualTo("This option is coming soon ...")
                    .contains("option is coming")
                    .doesNotContain("optionis")
                    .hasSize(30);

            final String narrowed = codec().movePicX(COMING_SOON_MESSAGE,
                    AdminMenuResponse.ERR_MSG_LENGTH);
            final AdminMenuResponse response = populated().toBuilder()
                    .errMsg(narrowed)
                    .messageColour(BmsAttributes.DFHGREEN)
                    .build();

            assertThat(response.errMsg())
                    .hasSize(78)
                    .startsWith("This option is coming soon ...")
                    .as("no option name is interpolated on the admin screen")
                    .doesNotContain("User List")
                    .doesNotContain("Security");
            assertThat(response.messageColour()).isEqualTo(BmsAttributes.DFHGREEN);
        }

        @Test
        @DisplayName("the invalid-key path carries CSMSG01Y's X(50) text, narrowed to 78")
        void theInvalidKeyPathCarriesTheFiftyByteText() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);

            final String narrowed = codec().movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                    AdminMenuResponse.ERR_MSG_LENGTH);

            assertThat(narrowed)
                    .hasSize(78)
                    .startsWith("Invalid key pressed. Please see below...")
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY + spaces(78 - 50));
            assertThat(AdminMenuResponse.builder().errMsg(narrowed).build().errMsg()).hasSize(78);
        }

        @Test
        @DisplayName("PIC X truncates right where PIC 9 truncates left - the helpers cannot be swapped")
        void picXAndPic9TruncateInOppositeDirections() {
            final FixedWidthCodec codec = codec();

            assertThat(codec.movePicX("ABCDEF", 4)).isEqualTo("ABCD").isNotEqualTo("CDEF");
            assertThat(codec.movePic9("123456", 4)).isEqualTo("3456").isNotEqualTo("1234");

            assertThat(AdminMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(AdminMenuResponse.OPTION_LENGTH).isEqualTo(2);
        }
    }

    static Stream<Arguments> everyMessagePathOfCoadm01c() {
        return Stream.of(
                Arguments.of("MOVE SPACES TO WS-MESSAGE (COADM01C:79)", ""),
                Arguments.of("'Please enter a valid option number...' (COADM01C:131-132)",
                        VALIDATION_MESSAGE),
                Arguments.of("MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE (COADM01C:101)",
                        SystemMessages.CCDA_MSG_INVALID_KEY),
                Arguments.of("STRING 'This option ' 'is coming soon ...' (COADM01C:149-153)",
                        COMING_SOON_MESSAGE));
    }

    @Nested
    @DisplayName("OPTIONO - the two-digit, LEFT zero-filled image of WS-OPTION PIC 9(02)")
    class OptionZeroFill {
        @ParameterizedTest(name = "WS-OPTION = {0} renders as \"{1}\"")
        @CsvSource({"0,00", "1,01", "3,03", "4,04", "9,09", "10,10", "12,12", "99,99"})
        @DisplayName("the zero-fill happens on the left, through the codec's PIC 9 path")
        void theZeroFillHappensOnTheLeft(final int wsOption, final String expected) {
            final FixedWidthCodec codec = codec();
            final String rendered = codec.movePic9(wsOption, AdminMenuResponse.OPTION_LENGTH);

            assertThat(rendered)
                    .isEqualTo(expected)
                    .hasSize(2)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
            assertThat(AdminMenuResponse.builder().option(rendered).build().option())
                    .isEqualTo(expected)
                    .hasSize(AdminMenuResponse.OPTION_LENGTH);
        }

        @Test
        @DisplayName("zero renders as \"00\", which the validation path really does reach")
        void zeroRendersAsDoubleZero() {
            final String rendered = codec().movePic9(0, AdminMenuResponse.OPTION_LENGTH);

            assertThat(rendered).isEqualTo("00").hasSize(2);
            assertThat(AdminMenuResponse.builder().option(rendered).build().option())
                    .isEqualTo("00")
                    .isNotEqualTo("  ")
                    .isNotEqualTo("0")
                    .hasSize(2);
            assertThat(AdminMenuResponse.empty().option())
                    .as("nothing entered yet is LOW-VALUES; entering zero is \"00\"")
                    .isEqualTo(ScreenFieldImage.unpainted(AdminMenuResponse.OPTION_LENGTH))
                    .isNotEqualTo(rendered);
        }

        @Test
        @DisplayName("every option the admin menu accepts renders at exactly two characters")
        void everyAcceptedOptionRendersAtTwoCharacters() {
            final FixedWidthCodec codec = codec();
            for (int option = 1; option <= ADMIN_OPTION_COUNT; option++) {
                assertThat(codec.movePic9(option, AdminMenuResponse.OPTION_LENGTH))
                        .as("option %d", option)
                        .hasSize(2)
                        .isEqualTo("0" + option);
            }
            assertThat(ADMIN_OPTION_COUNT).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("OPTN001O to OPTN012O - four composed, twelve declared, none pruned")
    class OptionLineImages {
        @ParameterizedTest(name = "slot {0} is the 40-character option line")
        @ValueSource(ints = {1, 2, 3, 4})
        @DisplayName("slots 1 to 4 carry num + '. ' + name, 39 characters padded to 40")
        void slotsOneToFourCarryTheComposedOptionLine(final int slot) {
            final String expected = adminOptionLine(slot);

            assertThat(expected)
                    .as("slot %d is exactly the declared width", slot)
                    .hasSize(40)
                    .hasSize(AdminMenuResponse.OPTION_LINE_LENGTH)
                    .endsWith(" ");
            assertThat(expected.stripTrailing()).hasSizeLessThanOrEqualTo(39);
            assertThat(expected.substring(0, 4)).isEqualTo("0" + slot + ". ");
            assertThat(expected.substring(4)).isEqualTo(ADMIN_OPTION_NAMES.get(slot - 1) + " ");

            assertThat(populated().optionLine(slot))
                    .as("the response carries the composed line verbatim")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the four lines are exactly COADM02Y's four labels, in COADM02Y's order")
        void theFourLinesAreTheFourLabelsInOrder() {
            assertThat(ADMIN_OPTION_NAMES)
                    .hasSize(4)
                    .allSatisfy(name -> assertThat(name)
                            .as("CDEMO-ADMIN-OPT-NAME is PIC X(35)")
                            .hasSize(35));

            assertThat(populated().optionLines().subList(0, 4)).containsExactly(
                    "01. User List (Security)                ",
                    "02. User Add (Security)                 ",
                    "03. User Update (Security)              ",
                    "04. User Delete (Security)              ");

            assertThat(ADMIN_OPTION_NAMES.get(0)).startsWith("User List (Security)");
            assertThat(ADMIN_OPTION_NAMES.get(1)).startsWith("User Add (Security)");
            assertThat(ADMIN_OPTION_NAMES.get(2)).startsWith("User Update (Security)");
            assertThat(ADMIN_OPTION_NAMES.get(3)).startsWith("User Delete (Security)");
        }

        @Test
        @DisplayName("slots 5 to 12 are forty spaces on a populated screen, and are not pruned")
        void slotsFiveToTwelveAreFortySpacesAndSurvive() {
            final AdminMenuResponse response = populated();
            final String blankLine =
                    ScreenFieldImage.unpainted(AdminMenuResponse.OPTION_LINE_LENGTH);

            for (int slot = ADMIN_OPTION_COUNT + 1; slot <= AdminMenuResponse.OPTION_LINE_COUNT; slot++) {
                assertThat(response.optionLine(slot))
                        .as("slot %d is unwritten, and unwritten means forty spaces", slot)
                        .isNotNull()
                        .isEqualTo(blankLine)
                        .hasSize(40);
            }

            assertThat(response.optn011())
                    .as("OPTN011O: no WHEN 11 arm exists in COADM01C:238-261")
                    .isEqualTo(blankLine);
            assertThat(response.optn012())
                    .as("OPTN012O: no WHEN 12 arm either")
                    .isEqualTo(blankLine);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT)
                    .as("twelve declared - not the four filled, not the ten reachable")
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the four XCTL targets of the option table are the four COUSR programs")
        void theFourXctlTargetsAreTheFourCousrPrograms() {
            assertThat(ADMIN_OPTION_PROGRAMS)
                    .containsExactly("COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C")
                    .allSatisfy(program -> assertThat(program)
                            .hasSize(AdminMenuResponse.NEXT_PROGRAM_LENGTH)
                            .hasSize(8));

            for (int slot = 1; slot <= ADMIN_OPTION_COUNT; slot++) {
                assertThat(populated().toBuilder()
                        .nextProgram(ADMIN_OPTION_PROGRAMS.get(slot - 1))
                        .build()
                        .nextProgram())
                        .as("selecting option %d names its own target", slot)
                        .isEqualTo(ADMIN_OPTION_PROGRAMS.get(slot - 1));
            }
        }
    }

    @Nested
    @DisplayName("Header literals - byte-exact titles, and the COTTL01Y:21 decoy left untouched")
    class HeaderLiterals {
        @Test
        @DisplayName("TITLE01O is CCDA-TITLE01: six leading and seven trailing spaces, 40 in all")
        void titleOneIsCcdaTitle01() {
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .isEqualTo("      AWS Mainframe Modernization       ")
                    .hasSize(40)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(AdminMenuResponse.TITLE_LENGTH)
                    .startsWith(spaces(6) + "AWS")
                    .endsWith("Modernization" + spaces(7));

            assertThat(populated().title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(AdminMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("TITLE02O is the ACTIVE CCDA-TITLE02 value from line 22, not the line 21 decoy")
        void titleTwoIsTheActiveCcdaTitle02Value() {
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .isEqualTo("              CardDemo                  ")
                    .hasSize(40)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CardDemo")
                    .as("the line 21 wording must never reach the screen")
                    .doesNotContain("Credit Card Demo Application")
                    .doesNotContain("(CCDA)");

            assertThat(populated().title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(AdminMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("the three thank-you and invalid-key constants are distinct in value and width")
        void theThreeNearIdenticalConstantsAreDistinct() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("COTTL01Y.cpy:23-24, PIC X(40), names the CCDA application")
                    .isEqualTo("Thank you for using CCDA application... ")
                    .hasSize(40)
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .contains("CCDA application")
                    .doesNotContain("CardDemo application");

            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("CSMSG01Y.cpy:18-19, PIC X(50), names the CardDemo application")
                    .startsWith("Thank you for using CardDemo application...")
                    .hasSize(50)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .contains("CardDemo application")
                    .doesNotContain("CCDA application");

            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("CSMSG01Y.cpy:20-21, PIC X(50)")
                    .startsWith("Invalid key pressed. Please see below...")
                    .hasSize(50)
                    .hasSize(SystemMessages.MESSAGE_LENGTH);

            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("the X(40) title and the X(50) message are not interchangeable")
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.length());
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("CURDATEO is mm/dd/yy and CURTIMEO is hh:mm:ss, both exactly 8, from a fixed Clock")
        void theDateAndTimeHeadersAreEightCharactersEach() {
            final DateHeader header =
                    DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

            assertThat(header.wsCurdateMmDdYy())
                    .isEqualTo("07/19/22")
                    .hasSize(8)
                    .hasSize(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH)
                    .hasSize(AdminMenuResponse.CUR_DATE_LENGTH)
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(header.wsCurtimeHhMmSs())
                    .isEqualTo("23:12:32")
                    .hasSize(8)
                    .hasSize(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH)
                    .hasSize(AdminMenuResponse.CUR_TIME_LENGTH)
                    .matches("\\d{2}:\\d{2}:\\d{2}");

            final AdminMenuResponse response = AdminMenuResponse.builder()
                    .curDate(header.wsCurdateMmDdYy())
                    .curTime(header.wsCurtimeHhMmSs())
                    .build();

            assertThat(response.curDate()).isEqualTo("07/19/22").hasSize(8);
            assertThat(response.curTime()).isEqualTo("23:12:32").hasSize(8);

            assertThat(DateHeader.from(codec(), Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC))
                    .wsCurtimeHhMmSs())
                    .isEqualTo(header.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("TRNNAMEO and PGMNAMEO are the transaction and program literals of this program")
        void theTransactionAndProgramHeadersAreThisProgramsLiterals() {
            assertThat(populated().trnName())
                    .isEqualTo(AdminMenuResponse.TRANSACTION_ID)
                    .isEqualTo("CA00")
                    .hasSize(AdminMenuResponse.TRN_NAME_LENGTH);
            assertThat(populated().pgmName())
                    .isEqualTo(AdminMenuResponse.PROGRAM_NAME)
                    .isEqualTo("COADM01C")
                    .hasSize(AdminMenuResponse.PGM_NAME_LENGTH);
        }
    }

    @Nested
    @DisplayName("The main-menu duplication is documented, not deduplicated (practice B4)")
    class DuplicationIsDocumentedNotRemoved {
        @Test
        @DisplayName("AdminMenuResponse and MainMenuResponse are distinct Java types")
        void adminAndMainMenuResponsesAreDistinctTypes() {
            assertThat(AdminMenuResponse.class)
                    .as("two screens, two types - see this class's documentation for why")
                    .isNotEqualTo(MainMenuResponse.class);
            assertThat(AdminMenuResponse.class.getName()).isNotEqualTo(MainMenuResponse.class.getName());

            assertThat(MainMenuResponse.class.isAssignableFrom(AdminMenuResponse.class)).isFalse();
            assertThat(AdminMenuResponse.class.isAssignableFrom(MainMenuResponse.class)).isFalse();
            assertThat(AdminMenuResponse.class.getSuperclass())
                    .as("a record extends java.lang.Record and nothing else - no shared base class")
                    .isEqualTo(Record.class);
            assertThat(AdminMenuResponse.class.getInterfaces())
                    .as("and no shared interface either")
                    .isEmpty();
        }

        @Test
        @DisplayName("the identical geometry is asserted independently for this screen, not shared")
        void theIdenticalGeometryIsAssertedIndependently() {
            assertThat(AdminMenuResponse.SYMBOLIC_MAP_LENGTH).isEqualTo(820);
            assertThat(AdminMenuResponse.PAYLOAD_DATA_LENGTH).isEqualTo(668);
            assertThat(AdminMenuResponse.PAYLOAD_FIELD_COUNT).isEqualTo(20);
            assertThat(AdminMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);

            assertThat(AdminMenuResponse.MAPSET_NAME).isEqualTo("COADM01").isNotEqualTo("COMEN01");
            assertThat(AdminMenuResponse.MAP_NAME).isEqualTo("COADM1A").isNotEqualTo("COMEN1A");
            assertThat(AdminMenuResponse.TRANSACTION_ID)
                    .as("CA00 is the admin menu; CM00 is the main menu")
                    .isEqualTo("CA00")
                    .isNotEqualTo("CM00");
            assertThat(AdminMenuResponse.PROGRAM_NAME).isEqualTo("COADM01C").isNotEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("the admin option table is four entries, where the main menu's is ten")
        void theAdminOptionTableIsFourEntries() {
            assertThat(ADMIN_OPTION_COUNT).isEqualTo(4).isNotEqualTo(10);
            assertThat(ADMIN_OPTION_NAMES).hasSize(4);
            assertThat(ADMIN_OPTION_PROGRAMS).hasSize(4);
            assertThat(ADMIN_OPTION_COUNT)
                    .as("four filled, but twelve slots declared - the count is not the slot count")
                    .isLessThan(AdminMenuResponse.OPTION_LINE_COUNT);
        }
    }
}
