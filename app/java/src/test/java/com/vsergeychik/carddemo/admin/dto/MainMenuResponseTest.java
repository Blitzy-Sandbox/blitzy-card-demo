package com.vsergeychik.carddemo.admin.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.admin.model.MenuOptions;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * Tests for {@link MainMenuResponse}, the outbound projection of the {@code xxxO} items of
 * {@code 01 COMEN1AO REDEFINES COMEN1AI} - the response payload of {@code GET /api/menu}, CICS transaction
 * {@code CM00}, program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}.
 */
@DisplayName("MainMenuResponse - the COMEN1AO output projection of the CM00 main menu")
class MainMenuResponseTest {
    private static final Charset FIXTURE_CHARSET = StandardCharsets.US_ASCII;

    private static final FixedWidthCodec CODEC = new FixedWidthCodec(FIXTURE_CHARSET);

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2022-07-19T23:12:33Z"), ZoneOffset.UTC);

    private static final int TRN_NAME_WIDTH = 4;

    private static final int TITLE_WIDTH = 40;

    private static final int CUR_DATE_WIDTH = 8;

    private static final int PGM_NAME_WIDTH = 8;

    private static final int CUR_TIME_WIDTH = 8;

    private static final int OPTION_LINE_WIDTH = 40;

    private static final int OPTION_WIDTH = 2;

    private static final int ERR_MSG_WIDTH = 78;

    private static final int DECLARED_OPTION_SLOTS = 12;

    private static final int POPULATED_OPTION_SLOTS = 10;

    private static final int PAYLOAD_FIELD_COUNT = 20;

    private static final int TIOAPFX_FILLER_WIDTH = 12;

    private static final int ATTRIBUTE_PREFIX_WIDTH = 7;

    private static final int OUTPUT_FIELD_FILLER_WIDTH = 3;

    private static final int OUTPUT_ATTRIBUTE_ITEMS = 4;

    private static final int INPUT_LENGTH_ITEM_WIDTH = 2;

    private static final int INPUT_FLAG_ITEM_WIDTH = 1;

    private static final int INPUT_FIELD_FILLER_WIDTH = 4;

    private static final int MAPSET_FIELD_DEFINITIONS = 28;

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLUMNS = 80;

    private static final int WS_MESSAGE_WIDTH = 80;

    private static final String VALIDATION_MESSAGE = "Please enter a valid option number...";

    private static final String NO_ACCESS_MESSAGE = "No access - Admin Only option... ";

    private static final String COMING_SOON_PREFIX = "This option ";

    private static final String COMING_SOON_SUFFIX = "is coming soon ...";

    private static final List<String> PAYLOAD_MEMBERS = List.of("trnName",
            "title01",
            "curDate",
            "pgmName",
            "title02",
            "curTime",
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
            "errMsg");

    private static final List<String> NON_PAYLOAD_MEMBERS = List.of("navigationContext",
            "nextProgram",
            "nextMapset",
            "nextMap",
            "errMsgColor",
            "resetAllOutputFields");

    private static final List<String> MAP_FIELD_NAMES = List.of("TRNNAME",
            "TITLE01",
            "CURDATE",
            "PGMNAME",
            "TITLE02",
            "CURTIME",
            "OPTN001",
            "OPTN002",
            "OPTN003",
            "OPTN004",
            "OPTN005",
            "OPTN006",
            "OPTN007",
            "OPTN008",
            "OPTN009",
            "OPTN010",
            "OPTN011",
            "OPTN012",
            "OPTION",
            "ERRMSG");

    private static final Map<String, Integer> DECLARED_WIDTHS = declaredWidths();

    private static final List<String> OPTION_TARGET_PROGRAMS = List.of("COACTVWC",
            "COACTUPC",
            "COCRDLIC",
            "COCRDSLC",
            "COCRDUPC",
            "COTRN00C",
            "COTRN01C",
            "COTRN02C",
            "CORPT00C",
            "COBIL00C");

    private static final List<String> OPTION_NAMES = List.of("Account View",
            "Account Update",
            "Credit Card List",
            "Credit Card View",
            "Credit Card Update",
            "Transaction List",
            "Transaction View",
            "Transaction Add",
            "Transaction Reports",
            "Bill Payment");

    private static Map<String, Integer> declaredWidths() {
        Map<String, Integer> widths = new LinkedHashMap<>();
        widths.put("trnName", TRN_NAME_WIDTH);
        widths.put("title01", TITLE_WIDTH);
        widths.put("curDate", CUR_DATE_WIDTH);
        widths.put("pgmName", PGM_NAME_WIDTH);
        widths.put("title02", TITLE_WIDTH);
        widths.put("curTime", CUR_TIME_WIDTH);
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            widths.put(String.format("optn%03d", slot), OPTION_LINE_WIDTH);
        }
        widths.put("option", OPTION_WIDTH);
        widths.put("errMsg", ERR_MSG_WIDTH);
        return widths;
    }

    private static String optionName(int cobolSubscript) {
        return CODEC.movePicX(OPTION_NAMES.get(cobolSubscript - 1), MenuOptions.OPT_NAME_LENGTH);
    }

    private static String delimitedBySpace(String sendingItem) {
        int firstSpace = sendingItem.indexOf(' ');
        return firstSpace < 0 ? sendingItem : sendingItem.substring(0, firstSpace);
    }

    private static String wsMessage(String text) {
        return CODEC.movePicX(text, WS_MESSAGE_WIDTH);
    }

    private static String errMsgImage(String text) {
        return CODEC.movePicX(wsMessage(text), ERR_MSG_WIDTH);
    }

    private static String comingSoonWsMessage(int cobolSubscript) {
        return wsMessage(CODEC.concatenateDelimitedBySize(COMING_SOON_PREFIX,
                delimitedBySpace(optionName(cobolSubscript)),
                COMING_SOON_SUFFIX));
    }

    private static String optionLineImage(int cobolSubscript) {
        String composed = CODEC.concatenateDelimitedBySize(
                CODEC.movePic9(cobolSubscript, MenuOptions.OPT_NUM_LENGTH),
                ". ",
                optionName(cobolSubscript));
        return CODEC.movePicX(composed, OPTION_LINE_WIDTH);
    }

    private static String positionalProbe(int length) {
        StringBuilder probe = new StringBuilder(length);
        for (int position = 1; position <= length; position++) {
            probe.append((char) ('A' + ((position - 1) % 26)));
        }
        return probe.toString();
    }

    private static String spaces(int count) {
        return CODEC.movePicX("", count);
    }

    private static MainMenuResponse spaceFilledToDeclaredWidths() {
        MainMenuResponse.Builder builder = MainMenuResponse.builder()
                .trnName(spaces(TRN_NAME_WIDTH))
                .title01(spaces(TITLE_WIDTH))
                .curDate(spaces(CUR_DATE_WIDTH))
                .pgmName(spaces(PGM_NAME_WIDTH))
                .title02(spaces(TITLE_WIDTH))
                .curTime(spaces(CUR_TIME_WIDTH))
                .option(spaces(OPTION_WIDTH))
                .errMsg(spaces(ERR_MSG_WIDTH));
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            builder.optionLine(slot, spaces(OPTION_LINE_WIDTH));
        }
        return builder.build();
    }

    private static MainMenuResponse afterSendMenuScreen(int optionEntered, String messageText) {
        DateHeader header = DateHeader.from(CODEC, FIXED_CLOCK);
        MainMenuResponse.Builder builder = MainMenuResponse.builder()
                .trnName(MainMenuResponse.TRANSACTION_ID)
                .title01(ScreenTitles.CCDA_TITLE01)
                .curDate(header.wsCurdateMmDdYy())
                .pgmName(MainMenuResponse.PROGRAM_NAME)
                .title02(ScreenTitles.CCDA_TITLE02)
                .curTime(header.wsCurtimeHhMmSs())
                .option(CODEC.movePic9(optionEntered, OPTION_WIDTH))
                .errMsg(errMsgImage(messageText));
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            builder.optionLine(slot, slot <= POPULATED_OPTION_SLOTS
                    ? optionLineImage(slot)
                    : spaces(OPTION_LINE_WIDTH));
        }
        return builder.build();
    }

    private static String payloadMember(MainMenuResponse response, String member) {
        try {
            return (String) MainMenuResponse.class.getMethod(member).invoke(response);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("MainMenuResponse must expose an accessor named '" + member
                    + "', because app/cpy-bms/COMEN01.CPY declares the matching xxxO item", failure);
        }
    }

    private static FixedWidthRecord.RecordLayout outputGroupLayout() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        int offset = 0;
        spans.add(FixedWidthRecord.FieldSpan.filler(offset, TIOAPFX_FILLER_WIDTH));
        offset += TIOAPFX_FILLER_WIDTH;
        for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
            String mapField = MAP_FIELD_NAMES.get(index);
            int width = DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index));
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, OUTPUT_FIELD_FILLER_WIDTH));
            offset += OUTPUT_FIELD_FILLER_WIDTH;
            for (String attribute : List.of("C", "P", "H", "V")) {
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + attribute, offset, 1));
                offset++;
            }
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "O", offset, width));
            offset += width;
        }
        return FixedWidthRecord.RecordLayout.of(offset,
                spans.toArray(new FixedWidthRecord.FieldSpan[0]));
    }

    private static FixedWidthRecord.RecordLayout inputGroupWithOutputOverlay() {
        List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
        int offset = 0;
        spans.add(FixedWidthRecord.FieldSpan.filler(offset, TIOAPFX_FILLER_WIDTH));
        offset += TIOAPFX_FILLER_WIDTH;
        for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
            String mapField = MAP_FIELD_NAMES.get(index);
            int width = DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index));
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, INPUT_LENGTH_ITEM_WIDTH));
            offset += INPUT_LENGTH_ITEM_WIDTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "F", offset,
                    INPUT_FLAG_ITEM_WIDTH));
            offset += INPUT_FLAG_ITEM_WIDTH;
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, INPUT_FIELD_FILLER_WIDTH));
            offset += INPUT_FIELD_FILLER_WIDTH;
            spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "I", offset, width));
            offset += width;
        }
        spans.add(FixedWidthRecord.FieldSpan.redefining("COMEN1AO", 0, offset,
                FixedWidthRecord.PictureKind.ALPHANUMERIC));
        return FixedWidthRecord.RecordLayout.of(offset,
                spans.toArray(new FixedWidthRecord.FieldSpan[0]));
    }

    @Nested
    @DisplayName("COMEN1AO geometry - 20 of 28 DFHMDF fields, 668 payload bytes in an 820-byte image")
    class Geometry {
        @Test
        @DisplayName("exactly 20 map-derived members, in the copybook's declaration order")
        void twentyPayloadMembersInMapOrder() {
            List<String> components = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components).hasSize(PAYLOAD_FIELD_COUNT + NON_PAYLOAD_MEMBERS.size());
            assertThat(components.subList(0, PAYLOAD_FIELD_COUNT))
                    .as("the twenty xxxO items of app/cpy-bms/COMEN01.CPY:146 to :260, in order")
                    .containsExactlyElementsOf(PAYLOAD_MEMBERS);
            assertThat(components.subList(PAYLOAD_FIELD_COUNT, components.size()))
                    .containsExactlyElementsOf(NON_PAYLOAD_MEMBERS);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT)
                    .isEqualTo(PAYLOAD_FIELD_COUNT)
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("every one of the twenty names is present, and none is missing or misspelled")
        void everyPayloadMemberIsReachableByName() {
            MainMenuResponse response = spaceFilledToDeclaredWidths();

            for (String member : PAYLOAD_MEMBERS) {
                assertThat(payloadMember(response, member))
                        .as("accessor " + member + "() must exist and return its span")
                        .isNotNull();
            }
            assertThat(PAYLOAD_MEMBERS).hasSize(PAYLOAD_FIELD_COUNT).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("28 DFHMDF definitions in the mapset, of which 20 are named and 8 are literals")
        void mapsetFieldCount() {
            assertThat(MainMenuResponse.MAPSET_FIELD_DEFINITION_COUNT)
                    .isEqualTo(MAPSET_FIELD_DEFINITIONS)
                    .isEqualTo(28);
            assertThat(MainMenuResponse.MAPSET_FIELD_DEFINITION_COUNT
                    - MainMenuResponse.SYMBOLIC_MAP_FIELD_COUNT).isEqualTo(8);
        }

        @Test
        @DisplayName("the declared widths are 4, 40, 8, 8, 40, 8, forty times twelve, 2 and 78")
        void declaredWidthsMatchTheCopybook() {
            assertThat(MainMenuResponse.TRN_NAME_LENGTH).isEqualTo(TRN_NAME_WIDTH).isEqualTo(4);
            assertThat(MainMenuResponse.TITLE_LENGTH).isEqualTo(TITLE_WIDTH).isEqualTo(40);
            assertThat(MainMenuResponse.CUR_DATE_LENGTH).isEqualTo(CUR_DATE_WIDTH).isEqualTo(8);
            assertThat(MainMenuResponse.PGM_NAME_LENGTH).isEqualTo(PGM_NAME_WIDTH).isEqualTo(8);
            assertThat(MainMenuResponse.CUR_TIME_LENGTH).isEqualTo(CUR_TIME_WIDTH).isEqualTo(8);
            assertThat(MainMenuResponse.OPTION_LINE_LENGTH)
                    .isEqualTo(OPTION_LINE_WIDTH).isEqualTo(40);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isEqualTo(DECLARED_OPTION_SLOTS).isEqualTo(12);
            assertThat(MainMenuResponse.OPTION_LENGTH).isEqualTo(OPTION_WIDTH).isEqualTo(2);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(ERR_MSG_WIDTH).isEqualTo(78);
        }

        @Test
        @DisplayName("ERRMSGO is 78 - never the 80 of WS-MESSAGE, never the 50 of the invalid-key text")
        void errMsgWidthIsNeitherOfTheTwoNeighbouringWidths() {
            assertThat(MainMenuResponse.ERR_MSG_LENGTH).isEqualTo(78);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH)
                    .as("app/cbl/COMEN01C.cbl:38 declares WS-MESSAGE PIC X(80); the receiver is 78")
                    .isNotEqualTo(WS_MESSAGE_WIDTH);
            assertThat(MainMenuResponse.ERR_MSG_LENGTH)
                    .as("app/cpy/CSMSG01Y.cpy declares its messages PIC X(50); the receiver is 78")
                    .isNotEqualTo(SystemMessages.MESSAGE_LENGTH);
            assertThat(WS_MESSAGE_WIDTH - MainMenuResponse.ERR_MSG_LENGTH)
                    .as("exactly two bytes are discarded by the MOVE at app/cbl/COMEN01C.cbl:187")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the twenty COMEN1AO widths sum to 668")
        void widthsSumToThePayloadLength() {
            int handSummed = TRN_NAME_WIDTH
                    + TITLE_WIDTH
                    + CUR_DATE_WIDTH
                    + PGM_NAME_WIDTH
                    + TITLE_WIDTH
                    + CUR_TIME_WIDTH
                    + DECLARED_OPTION_SLOTS * OPTION_LINE_WIDTH
                    + OPTION_WIDTH
                    + ERR_MSG_WIDTH;

            assertThat(handSummed).isEqualTo(668);
            assertThat(DECLARED_WIDTHS.values().stream().mapToInt(Integer::intValue).sum())
                    .isEqualTo(handSummed);
            assertThat(MainMenuResponse.PAYLOAD_BYTES).isEqualTo(handSummed).isEqualTo(668);
        }

        @Test
        @DisplayName("the whole COMEN1AO image is 12 + 20 times 7 + 668 = 820 bytes")
        void theOverlayIsEightHundredAndTwentyBytes() {
            assertThat(MainMenuResponse.TIOAPFX_FILLER_LENGTH)
                    .isEqualTo(TIOAPFX_FILLER_WIDTH).isEqualTo(12);
            assertThat(MainMenuResponse.ATTRIBUTE_PREFIX_LENGTH)
                    .isEqualTo(ATTRIBUTE_PREFIX_WIDTH).isEqualTo(7);
            assertThat(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(TIOAPFX_FILLER_WIDTH
                            + PAYLOAD_FIELD_COUNT * ATTRIBUTE_PREFIX_WIDTH
                            + MainMenuResponse.PAYLOAD_BYTES)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
        }

        @Test
        @DisplayName("the COMEN1AO stride equals the COMEN1AI stride, which is why the REDEFINES is legal")
        void bothStridesAreSevenBytes() {
            assertThat(OUTPUT_FIELD_FILLER_WIDTH + OUTPUT_ATTRIBUTE_ITEMS)
                    .isEqualTo(ATTRIBUTE_PREFIX_WIDTH)
                    .isEqualTo(7);
            assertThat(INPUT_LENGTH_ITEM_WIDTH + INPUT_FLAG_ITEM_WIDTH + INPUT_FIELD_FILLER_WIDTH)
                    .isEqualTo(ATTRIBUTE_PREFIX_WIDTH)
                    .isEqualTo(7);
            assertThat(3 + 4).isEqualTo(2 + 1 + 4);
        }

        @Test
        @DisplayName("composing all twenty output spans really does produce an 820-byte layout")
        void theOutputLayoutSelfCheckAgrees() {
            FixedWidthRecord.RecordLayout layout = outputGroupLayout();

            assertThat(layout.recordLength()).isEqualTo(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(820);
            assertThat(layout.spans()).hasSize(1 + PAYLOAD_FIELD_COUNT * 6).hasSize(121);
            assertThat(layout.storageSpans()).hasSameSizeAs(layout.spans());
            assertThat(layout.redefinitions()).isEmpty();
            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String item = MAP_FIELD_NAMES.get(index) + "O";
                assertThat(layout.hasSpan(item)).as(item + " must be addressable").isTrue();
                assertThat(layout.span(item).length())
                        .isEqualTo(DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index)));
            }
            assertThat(layout.span("ERRMSGO").endOffsetExclusive()).isEqualTo(820);
            assertThat(layout.span("ERRMSGO").offset()).isEqualTo(820 - ERR_MSG_WIDTH).isEqualTo(742);
        }

        @Test
        @DisplayName("COMEN1AO REDEFINES COMEN1AI: one 820-byte span, two typed views (gate G34)")
        void theRedefinesPairIsOneSpanSeenTwice() {
            FixedWidthRecord.RecordLayout input = inputGroupWithOutputOverlay();
            FixedWidthRecord.RecordLayout output = outputGroupLayout();

            assertThat(input.recordLength()).isEqualTo(output.recordLength()).isEqualTo(820);
            assertThat(input.redefinitions()).hasSize(1);
            FixedWidthRecord.FieldSpan overlay = input.span("COMEN1AO");
            assertThat(overlay.redefinition()).isTrue();
            assertThat(overlay.offset()).isZero();
            assertThat(overlay.length()).isEqualTo(820);
            assertThat(overlay.endOffsetExclusive()).isEqualTo(input.recordLength());
            for (String mapField : MAP_FIELD_NAMES) {
                assertThat(input.span(mapField + "I").offset())
                        .as(mapField + "I and " + mapField + "O must start at the same byte")
                        .isEqualTo(output.span(mapField + "O").offset());
                assertThat(input.span(mapField + "I").length())
                        .isEqualTo(output.span(mapField + "O").length());
            }
        }

        @Test
        @DisplayName("dropping a single FILLER span fails the layout immediately (gate G21)")
        void omittingAFillerIsRejected() {
            List<FixedWidthRecord.FieldSpan> spans = new ArrayList<>();
            int offset = 0;
            spans.add(FixedWidthRecord.FieldSpan.filler(offset, TIOAPFX_FILLER_WIDTH));
            offset += TIOAPFX_FILLER_WIDTH;
            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String mapField = MAP_FIELD_NAMES.get(index);
                int width = DECLARED_WIDTHS.get(PAYLOAD_MEMBERS.get(index));
                boolean skipThisFiller = "ERRMSG".equals(mapField);
                if (!skipThisFiller) {
                    spans.add(FixedWidthRecord.FieldSpan.filler(offset, OUTPUT_FIELD_FILLER_WIDTH));
                    offset += OUTPUT_FIELD_FILLER_WIDTH;
                }
                for (String attribute : List.of("C", "P", "H", "V")) {
                    spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + attribute, offset, 1));
                    offset++;
                }
                spans.add(FixedWidthRecord.FieldSpan.alphanumeric(mapField + "O", offset, width));
                offset += width;
            }
            FixedWidthRecord.FieldSpan[] shortened =
                    spans.toArray(new FixedWidthRecord.FieldSpan[0]);

            assertThat(offset).isEqualTo(820 - OUTPUT_FIELD_FILLER_WIDTH).isEqualTo(817);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> FixedWidthRecord.RecordLayout.of(820, shortened))
                    .withMessageContaining("byte(s) short");
        }

        @Test
        @DisplayName("the twenty payload values fill the twenty spans and nothing else")
        void writingThePayloadFillsExactlyItsOwnBytes() {
            FixedWidthRecord.RecordLayout layout = outputGroupLayout();
            FixedWidthRecord image = CODEC.newRecord(layout);
            MainMenuResponse response = afterSendMenuScreen(1, VALIDATION_MESSAGE);

            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String member = PAYLOAD_MEMBERS.get(index);
                FixedWidthRecord.FieldSpan span = layout.span(MAP_FIELD_NAMES.get(index) + "O");
                CODEC.writePicX(image, span, payloadMember(response, member));
            }

            assertThat(image.recordLength()).isEqualTo(820);
            assertThat(image.toByteArray()).hasSize(820);
            for (int index = 0; index < PAYLOAD_FIELD_COUNT; index++) {
                String member = PAYLOAD_MEMBERS.get(index);
                FixedWidthRecord.FieldSpan span = layout.span(MAP_FIELD_NAMES.get(index) + "O");
                assertThat(CODEC.readPicX(image, span))
                        .as(member + " must round-trip through its own span untouched")
                        .isEqualTo(payloadMember(response, member))
                        .hasSize(DECLARED_WIDTHS.get(member));
            }
        }

        @Test
        @DisplayName("the COMEN1A screen is 24 by 80, as its single DFHMDI declares")
        void theScreenGeometryIsTheStandardTerminal() {
            assertThat(SCREEN_ROWS).isEqualTo(24);
            assertThat(SCREEN_COLUMNS).isEqualTo(80);
            assertThat(ERR_MSG_WIDTH).isLessThan(SCREEN_COLUMNS);
            assertThat(SCREEN_COLUMNS - ERR_MSG_WIDTH).isEqualTo(2);
        }

        @Test
        @DisplayName("the screen identity is CM00 / COMEN01C / COMEN01 / COMEN1A")
        void screenIdentityMatchesTheCsdAndTheMapset() {
            assertThat(MainMenuResponse.TRANSACTION_ID).isEqualTo("CM00").hasSize(TRN_NAME_WIDTH);
            assertThat(MainMenuResponse.PROGRAM_NAME).isEqualTo("COMEN01C").hasSize(PGM_NAME_WIDTH);
            assertThat(MainMenuResponse.MAPSET_NAME).isEqualTo("COMEN01")
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(MainMenuResponse.MAP_NAME).isEqualTo("COMEN1A")
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C")
                    .hasSize(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("@Size(max) on every character member carries that member's copybook width")
        void beanValidationCarriesTheDeclaredWidths() throws ReflectiveOperationException {
            Map<String, Integer> annotated = new LinkedHashMap<>(DECLARED_WIDTHS);
            annotated.put("nextProgram", MainMenuResponse.NEXT_PROGRAM_LENGTH);
            annotated.put("nextMapset", MainMenuResponse.NEXT_MAPSET_LENGTH);
            annotated.put("nextMap", MainMenuResponse.NEXT_MAP_LENGTH);

            int found = 0;
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                String name = component.getName();
                Size onField = MainMenuResponse.class.getDeclaredField(name).getAnnotation(Size.class);
                Size onAccessor = MainMenuResponse.class.getMethod(name).getAnnotation(Size.class);
                if (annotated.containsKey(name)) {
                    assertThat(onField).as("@Size on field " + name).isNotNull();
                    assertThat(onField.max()).as("@Size max on " + name)
                            .isEqualTo(annotated.get(name));
                    assertThat(onAccessor).as("@Size on accessor " + name).isNotNull();
                    assertThat(onAccessor.max()).isEqualTo(annotated.get(name));
                    found++;
                } else {
                    assertThat(onField).as("no @Size on non-character member " + name).isNull();
                    assertThat(onAccessor).isNull();
                }
            }
            assertThat(found).isEqualTo(annotated.size()).isEqualTo(23);
        }

        @Test
        @DisplayName("@Size reaches the canonical constructor parameters too")
        void beanValidationReachesTheConstructor() {
            Parameter[] parameters = MainMenuResponse.class.getDeclaredConstructors()[0].getParameters();

            assertThat(parameters).hasSize(PAYLOAD_FIELD_COUNT + NON_PAYLOAD_MEMBERS.size());
            assertThat(parameters[0].getAnnotation(Size.class)).isNotNull();
            assertThat(parameters[0].getAnnotation(Size.class).max()).isEqualTo(TRN_NAME_WIDTH);
            assertThat(parameters[PAYLOAD_FIELD_COUNT - 1].getAnnotation(Size.class).max())
                    .as("the twentieth parameter is errMsg, at 78")
                    .isEqualTo(ERR_MSG_WIDTH);
            assertThat(parameters[PAYLOAD_FIELD_COUNT].getAnnotation(Size.class)).isNull();
        }

        @Test
        @DisplayName("every member is space-fillable to exactly its own declared width")
        void everyMemberHoldsItsDeclaredWidth() {
            MainMenuResponse response = spaceFilledToDeclaredWidths();

            for (Map.Entry<String, Integer> declared : DECLARED_WIDTHS.entrySet()) {
                assertThat(payloadMember(response, declared.getKey()))
                        .as(declared.getKey() + " must hold exactly " + declared.getValue()
                                + " characters")
                        .hasSize(declared.getValue())
                        .isBlank();
            }
        }
    }

    @Nested
    @DisplayName("Attribute items are metadata - no xxxC, xxxP, xxxH or xxxV is ever payload")
    class MetadataIsNotPayload {
        @Test
        @DisplayName("errMsgC, optionP, title01H and curDateV do not exist, by name")
        void theFourNamedMetadataItemsAreAbsent() {
            for (String forbidden : List.of("errMsgC", "optionP", "title01H", "curDateV")) {
                assertThat(recordComponentNames())
                        .as(forbidden + " is an attribute item, not an xxxO projection")
                        .doesNotContain(forbidden);
                assertThat(declaredFieldNames()).doesNotContain(forbidden);
                assertThat(accessorNames()).doesNotContain(forbidden);
            }
        }

        @Test
        @DisplayName("none of the eighty attribute items leaks in as a member, a field or an accessor")
        void noAttributeItemLeaksAnywhere() {
            List<String> components = recordComponentNames();
            List<String> fields = declaredFieldNames();
            List<String> accessors = accessorNames();

            int swept = 0;
            for (String mapField : MAP_FIELD_NAMES) {
                for (String attribute : List.of("C", "P", "H", "V")) {
                    String javaName = PAYLOAD_MEMBERS.get(MAP_FIELD_NAMES.indexOf(mapField))
                            + attribute;
                    assertThat(components).doesNotContain(javaName);
                    assertThat(fields).doesNotContain(javaName);
                    assertThat(accessors).doesNotContain(javaName);
                    assertThat(components).doesNotContain(mapField + attribute);
                    assertThat(fields).doesNotContain(mapField + attribute);
                    swept++;
                }
            }
            assertThat(swept).as("four attribute items for each of twenty fields").isEqualTo(80);
        }

        @Test
        @DisplayName("no xxxL length item and no xxxF or xxxA flag item leaks in either")
        void noInputSideMetadataLeaksIn() {
            for (String mapField : MAP_FIELD_NAMES) {
                String member = PAYLOAD_MEMBERS.get(MAP_FIELD_NAMES.indexOf(mapField));
                for (String suffix : List.of("L", "F", "A")) {
                    assertThat(recordComponentNames()).doesNotContain(member + suffix);
                    assertThat(recordComponentNames()).doesNotContain(mapField + suffix);
                }
            }
        }

        @Test
        @DisplayName("the serialised payload publishes the twenty-six members and no attribute item")
        void theSerialisedFormCarriesNoAttributeItem() throws Exception {
            String json = new ObjectMapper().writeValueAsString(afterSendMenuScreen(1,
                    VALIDATION_MESSAGE));

            for (String mapField : MAP_FIELD_NAMES) {
                String member = PAYLOAD_MEMBERS.get(MAP_FIELD_NAMES.indexOf(mapField));
                for (String suffix : List.of("C", "P", "H", "V", "L", "F", "A")) {
                    assertThat(json)
                            .as("no attribute or length item may be serialised: " + member + suffix)
                            .doesNotContain("\"" + member + suffix + "\"")
                            .doesNotContain("\"" + mapField + suffix + "\"");
                }
                assertThat(json).as(mapField + " is payload and must be serialised")
                        .contains("\"" + mapField.toLowerCase(Locale.ROOT) + "\"");
            }
        }

        @Test
        @DisplayName("the message colour is metadata: carried as errMsgColor, never as errMsgC")
        void theColourIsMetadataUnderItsOwnName() throws Exception {
            MainMenuResponse response = afterSendMenuScreen(1, VALIDATION_MESSAGE);
            String json = new ObjectMapper().writeValueAsString(response);

            assertThat(recordComponentNames()).contains("errMsgColor");
            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(json).doesNotContain("errMsgColor").doesNotContain("errMsgC");
            assertThat(json).doesNotContain("resetAllOutputFields");
        }

        private List<String> recordComponentNames() {
            return Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
        }

        private List<String> declaredFieldNames() {
            return Stream.of(MainMenuResponse.class.getDeclaredFields())
                    .filter(field -> !field.isSynthetic() && !field.getName().startsWith("$"))
                    .map(Field::getName)
                    .toList();
        }

        private List<String> accessorNames() {
            return Stream.of(MainMenuResponse.class.getDeclaredMethods())
                    .filter(method -> method.getParameterCount() == 0 && !method.isSynthetic())
                    .map(Method::getName)
                    .toList();
        }
    }

    @Nested
    @DisplayName("COMEN1AO ERRMSGO - an X(80) message narrowed to X(78) by RIGHT truncation")
    class MessageLine {
        @Test
        @DisplayName("a distinguishable 80-character probe loses WS-MESSAGE positions 79 and 80, not 1 and 2")
        void theAlphabeticProbeProvesTheDirection() {
            String probe = "A".repeat(ERR_MSG_WIDTH) + "YZ";
            assertThat(probe).hasSize(WS_MESSAGE_WIDTH);

            String narrowed = CODEC.movePicX(probe, ERR_MSG_WIDTH);

            assertThat(narrowed).hasSize(ERR_MSG_WIDTH).isEqualTo("A".repeat(ERR_MSG_WIDTH));
            assertThat(narrowed).doesNotContain("Y").doesNotContain("Z");
            assertThat(narrowed.charAt(ERR_MSG_WIDTH - 1)).isEqualTo('A');
            assertThat(MainMenuResponse.builder().errMsg(narrowed).build().errMsg())
                    .hasSize(ERR_MSG_WIDTH);
        }

        @Test
        @DisplayName("a positional probe survives as positions 1 to 78, ending in 'Z' not \"AB\"")
        void thePositionalProbeProvesTheDirectionAgain() {
            String probe = positionalProbe(WS_MESSAGE_WIDTH);
            assertThat(probe).hasSize(80);
            assertThat(probe.charAt(0)).isEqualTo('A');
            assertThat(probe.charAt(ERR_MSG_WIDTH - 1)).isEqualTo('Z');
            assertThat(probe).endsWith("AB");

            String narrowed = CODEC.movePicX(probe, ERR_MSG_WIDTH);

            assertThat(narrowed).hasSize(ERR_MSG_WIDTH)
                    .isEqualTo(probe.substring(0, ERR_MSG_WIDTH))
                    .startsWith("A")
                    .endsWith("Z");
            assertThat(narrowed).doesNotEndWith("AB");
            assertThat(narrowed.charAt(0)).isEqualTo(probe.charAt(0));
            assertThat(narrowed.charAt(ERR_MSG_WIDTH - 1)).isEqualTo(probe.charAt(ERR_MSG_WIDTH - 1));
        }

        @Test
        @DisplayName("PIC X truncates on the right, PIC 9 on the left - opposite, and both proved here")
        void theTwoMoveDirectionsAreOpposite() {
            assertThat(CODEC.movePicX("123", OPTION_WIDTH))
                    .as("PIC X keeps the LEADING characters")
                    .isEqualTo("12");
            assertThat(CODEC.movePic9("123", OPTION_WIDTH))
                    .as("PIC 9 keeps the LOW-ORDER digits")
                    .isEqualTo("23");
            assertThat(CODEC.movePicX("123", OPTION_WIDTH))
                    .isNotEqualTo(CODEC.movePic9("123", OPTION_WIDTH));
        }

        @ParameterizedTest(name = "\"{0}\" arrives right-padded to 78")
        @MethodSource(
                "com.vsergeychik.carddemo.admin.dto.MainMenuResponseTest#everyMessagePath")
        @DisplayName("all four COMEN01C message paths land as exactly 78 characters")
        void everyMessagePathIsExactlySeventyEight(String description, String messageText) {
            String image = errMsgImage(messageText);

            assertThat(image).as(description + " must be exactly " + ERR_MSG_WIDTH + " characters")
                    .hasSize(ERR_MSG_WIDTH)
                    .startsWith(messageText)
                    .isEqualTo(messageText + " ".repeat(ERR_MSG_WIDTH - messageText.length()));
            assertThat(MainMenuResponse.builder().errMsg(image).build().errMsg())
                    .hasSize(ERR_MSG_WIDTH);
        }

        @Test
        @DisplayName("path 1: the option-validation message of COMEN01C:131-132")
        void validationMessagePath() {
            assertThat(VALIDATION_MESSAGE)
                    .isEqualTo("Please enter a valid option number...")
                    .hasSize(37);

            String image = errMsgImage(VALIDATION_MESSAGE);

            assertThat(image).hasSize(ERR_MSG_WIDTH);
            assertThat(image.strip()).isEqualTo(VALIDATION_MESSAGE);
            assertThat(afterSendMenuScreen(0, VALIDATION_MESSAGE).errMsg()).isEqualTo(image);
        }

        @Test
        @DisplayName("path 2: the user-type authorization refusal of COMEN01C:140-141, trailing space kept")
        void noAccessMessagePath() {
            assertThat(NO_ACCESS_MESSAGE)
                    .isEqualTo("No access - Admin Only option... ")
                    .hasSize(33)
                    .endsWith(" ")
                    .endsWith("... ");
            assertThat(NO_ACCESS_MESSAGE.strip()).hasSize(32);

            String image = errMsgImage(NO_ACCESS_MESSAGE);

            assertThat(image).hasSize(ERR_MSG_WIDTH).startsWith(NO_ACCESS_MESSAGE);
            assertThat(image).isEqualTo(NO_ACCESS_MESSAGE
                    + " ".repeat(ERR_MSG_WIDTH - NO_ACCESS_MESSAGE.length()));
            assertThat(image.charAt(32)).isEqualTo(' ');
        }

        @Test
        @DisplayName("path 3: CCDA-MSG-INVALID-KEY on the WHEN OTHER AID path of COMEN01C:101")
        void invalidKeyMessagePath() {
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .startsWith("Invalid key pressed. Please see below...");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY.strip()).hasSize(40);

            String image = errMsgImage(SystemMessages.CCDA_MSG_INVALID_KEY);

            assertThat(image).hasSize(ERR_MSG_WIDTH);
            assertThat(image).startsWith(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(image.strip()).isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.strip());
        }

        @Test
        @DisplayName("path 4: the coming-soon text, in full in ComingSoonDefect below")
        void comingSoonMessagePath() {
            String image = CODEC.movePicX(comingSoonWsMessage(1), ERR_MSG_WIDTH);

            assertThat(image).hasSize(ERR_MSG_WIDTH)
                    .startsWith("This option Accountis coming soon ...");
        }

        @Test
        @DisplayName("with no message at all the field is 78 spaces - not null, not empty")
        void theNoMessageCaseIsSeventyEightSpaces() {
            String cleared = errMsgImage("");

            assertThat(cleared).hasSize(ERR_MSG_WIDTH).isNotNull().isNotEmpty().isBlank();
            assertThat(cleared).isEqualTo(" ".repeat(ERR_MSG_WIDTH));
            assertThat(spaces(ERR_MSG_WIDTH)).isEqualTo(cleared);
            assertThat(MainMenuResponse.builder().errMsg(cleared).build().errMsg())
                    .hasSize(ERR_MSG_WIDTH)
                    .isBlank();
        }

        @Test
        @DisplayName("an exactly-78-character message is neither padded nor truncated")
        void anExactlyWidthMessageIsUntouched() {
            String exact = positionalProbe(ERR_MSG_WIDTH);

            assertThat(exact).hasSize(ERR_MSG_WIDTH);
            assertThat(CODEC.movePicX(exact, ERR_MSG_WIDTH)).isSameAs(exact);
        }

        @Test
        @DisplayName("the charset is stated explicitly and is never the platform default")
        void theCodecCarriesAnExplicitCharset() {
            assertThat(CODEC.charset()).isEqualTo(StandardCharsets.US_ASCII)
                    .isEqualTo(FIXTURE_CHARSET);
            assertThat(CODEC.charset().name()).isEqualTo("US-ASCII");
        }
    }

    @Nested
    @DisplayName("The preserved legacy defect - \"This option Accountis coming soon ...\"")
    class ComingSoonDefect {
        @Test
        @DisplayName("option 1 yields \"This option Accountis coming soon ...\" - no space before \"is\"")
        void optionOneKeepsTheMissingSpace() {
            String expected = "This option Accountis coming soon ...";

            String message = comingSoonWsMessage(1);

            assertThat(message).hasSize(WS_MESSAGE_WIDTH).startsWith(expected);
            assertThat(message.strip()).isEqualTo(expected);
            assertThat(expected.charAt(COMING_SOON_PREFIX.length() + "Account".length() - 1))
                    .isEqualTo('t');
            assertThat(expected.charAt(COMING_SOON_PREFIX.length() + "Account".length()))
                    .isEqualTo('i');
            assertThat(expected.charAt(18)).isEqualTo('t');
            assertThat(expected.charAt(19)).isEqualTo('i');
            assertThat(expected).contains("Accountis").doesNotContain("Account is");
            assertThat(expected).hasSize(COMING_SOON_PREFIX.length() + "Account".length()
                    + COMING_SOON_SUFFIX.length()).hasSize(37);
        }

        @Test
        @DisplayName("the defect survives the narrowing to ERRMSGO, right-padded to 78")
        void theDefectReachesTheScreenField() {
            String image = CODEC.movePicX(comingSoonWsMessage(1), ERR_MSG_WIDTH);

            assertThat(image).hasSize(ERR_MSG_WIDTH)
                    .isEqualTo("This option Accountis coming soon ..."
                            + " ".repeat(ERR_MSG_WIDTH - 37));
            assertThat(MainMenuResponse.builder().errMsg(image).build().errMsg())
                    .isEqualTo(image)
                    .hasSize(ERR_MSG_WIDTH);
        }

        @Test
        @DisplayName("option 10 yields \"This option Billis coming soon ...\" - data-driven, not hard-coded")
        void optionTenProvesTheDelimiterIsDataDriven() {
            String expected = "This option Billis coming soon ...";

            String message = comingSoonWsMessage(10);

            assertThat(message.strip()).isEqualTo(expected);
            assertThat(expected).contains("Billis").doesNotContain("Bill is");
            assertThat(expected).hasSize(COMING_SOON_PREFIX.length() + "Bill".length()
                    + COMING_SOON_SUFFIX.length()).hasSize(34);
            assertThat(CODEC.movePicX(message, ERR_MSG_WIDTH)).hasSize(ERR_MSG_WIDTH)
                    .startsWith(expected);
        }

        @ParameterizedTest(name = "option {0} contributes \"{1}\"")
        @CsvSource({"1,Account", "2,Account", "3,Credit", "4,Credit", "5,Credit", "6,Transaction",
                    "7,Transaction", "8,Transaction", "9,Transaction", "10,Bill"})
        @DisplayName("every one of the ten options contributes only its first word")
        void everyOptionDelimitsAtItsFirstSpace(int cobolSubscript, String contribution) {
            assertThat(delimitedBySpace(optionName(cobolSubscript))).isEqualTo(contribution);

            String message = comingSoonWsMessage(cobolSubscript).strip();

            assertThat(message)
                    .isEqualTo(COMING_SOON_PREFIX + contribution + COMING_SOON_SUFFIX)
                    .startsWith("This option " + contribution + "is");
            assertThat(message).doesNotContain(contribution + " is");
        }

        @Test
        @DisplayName("the three STRING operands are transcribed byte-for-byte from :159, :160 and :162")
        void theOperandsAreTheCopybookAndSourceLiterals() {
            assertThat(COMING_SOON_PREFIX).isEqualTo("This option ").hasSize(12).endsWith(" ");
            assertThat(COMING_SOON_SUFFIX).isEqualTo("is coming soon ...").hasSize(18)
                    .startsWith("is");
            assertThat(COMING_SOON_SUFFIX).doesNotStartWith(" ");
            assertThat(optionName(1)).isEqualTo("Account View                       ")
                    .hasSize(MenuOptions.OPT_NAME_LENGTH)
                    .hasSize(35);
        }

        @Test
        @DisplayName("with the option name absent the text WOULD be correctly spaced - the admin case")
        void theAdminShapeIsRecordedButNotProduced() {
            String withoutTheOptionName =
                    CODEC.concatenateDelimitedBySize(COMING_SOON_PREFIX, COMING_SOON_SUFFIX);

            assertThat(withoutTheOptionName).isEqualTo("This option is coming soon ...")
                    .contains("option is");
            assertThat(comingSoonWsMessage(1).strip()).isNotEqualTo(withoutTheOptionName);
        }

        @Test
        @DisplayName("the coming-soon path is the one that turns the message green")
        void theComingSoonPathOverridesTheColour() {
            MainMenuResponse response = MainMenuResponse.builder()
                    .errMsg(CODEC.movePicX(comingSoonWsMessage(1), ERR_MSG_WIDTH))
                    .errMsgColor(BmsAttributes.DFHGREEN)
                    .build();

            assertThat(response.errMsg()).startsWith("This option Accountis coming soon ...");
            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
        }
    }

    @Nested
    @DisplayName("COMEN1AO OPTIONO - the two-digit, LEFT zero-filled image of WS-OPTION PIC 9(02)")
    class OptionEcho {
        @ParameterizedTest(name = "option {0} renders \"{1}\"")
        @CsvSource({"0,00", "1,01", "2,02", "3,03", "9,09", "10,10", "11,11", "12,12", "99,99"})
        @DisplayName("a PIC 9(02) store is zero-filled on the LEFT")
        void theOptionIsZeroFilledOnTheLeft(int entered, String expected) {
            String image = CODEC.movePic9(entered, OPTION_WIDTH);

            assertThat(image).isEqualTo(expected).hasSize(OPTION_WIDTH).hasSize(2);
            assertThat(MainMenuResponse.builder().option(image).build().option())
                    .isEqualTo(expected)
                    .hasSize(OPTION_WIDTH);
        }

        @Test
        @DisplayName("zero is reachable, because the validation at :127-129 still sends the screen")
        void zeroIsAReachableValue() {
            MainMenuResponse response = afterSendMenuScreen(0, VALIDATION_MESSAGE);

            assertThat(response.option()).isEqualTo("00").hasSize(OPTION_WIDTH);
            assertThat(response.errMsg()).startsWith(VALIDATION_MESSAGE);
        }

        @Test
        @DisplayName("\"01\" and \"1\" stay distinct, which an int could not express")
        void theEchoIsTextualNotNumeric() {
            assertThat(MainMenuResponse.builder().option("01").build().option()).isEqualTo("01");
            assertThat(MainMenuResponse.builder().option("1").build().option()).isEqualTo("1");
            assertThat(MainMenuResponse.builder().option("01").build())
                    .isNotEqualTo(MainMenuResponse.builder().option("1").build());
        }

        @Test
        @DisplayName("\"nothing entered yet\" is two spaces - a third state no integer has")
        void theUnenteredStateIsSpaces() {
            MainMenuResponse response = MainMenuResponse.builder()
                    .option(spaces(OPTION_WIDTH))
                    .build();

            assertThat(response.option()).isEqualTo("  ").hasSize(OPTION_WIDTH).isBlank();
            assertThat(response.option()).isNotEqualTo("00");
        }

        @Test
        @DisplayName("the option component is declared as a String, and no member is floating point")
        void theOptionIsDeclaredTextual() throws ReflectiveOperationException {
            assertThat(MainMenuResponse.class.getMethod("option").getReturnType())
                    .isEqualTo(String.class);
            for (RecordComponent component : MainMenuResponse.class.getRecordComponents()) {
                assertThat(component.getType())
                        .as(component.getName() + " must not be a floating-point type")
                        .isNotEqualTo(double.class)
                        .isNotEqualTo(float.class)
                        .isNotEqualTo(Double.class)
                        .isNotEqualTo(Float.class);
            }
        }

        @Test
        @DisplayName("an over-wide option loses its HIGH-order digit, the opposite of the message field")
        void anOverWideOptionLosesItsLeadingDigit() {
            assertThat(CODEC.movePic9(123, OPTION_WIDTH)).isEqualTo("23");
            assertThat(CODEC.movePicX("123", OPTION_WIDTH)).isEqualTo("12");
        }

        @Test
        @DisplayName("PIC 9 is unsigned, so a negative option has no image and is refused")
        void aNegativeOptionIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> CODEC.movePic9(-1, OPTION_WIDTH))
                    .withMessageContaining("unsigned PIC 9");
        }
    }

    @Nested
    @DisplayName("OPTN001O to OPTN012O - twelve declared, ten composed, none pruned")
    class TwelveOptionSlots {
        @ParameterizedTest(name = "slot {0} exists and is addressable")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        @DisplayName("all twelve slots exist as real members, each PIC X(40)")
        void allTwelveSlotsExist(int slot) {
            String member = String.format("optn%03d", slot);

            assertThat(PAYLOAD_MEMBERS).contains(member);
            assertThat(DECLARED_WIDTHS.get(member)).isEqualTo(OPTION_LINE_WIDTH).isEqualTo(40);
            assertThat(MainMenuResponse.builder().optionLine(slot, "x").build().optionLine(slot))
                    .isEqualTo("x");
            assertThat(MainMenuResponse.builder().build().optionLines()).hasSize(12);
        }

        @ParameterizedTest(name = "slot {0} carries its COMEN02Y option line")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("slots 1 to 10 carry the 40-character option-line image")
        void theTenPopulatedSlotsCarryTheirImage(int slot) {
            MainMenuResponse response = afterSendMenuScreen(slot, "");

            String expected = CODEC.movePic9(slot, MenuOptions.OPT_NUM_LENGTH)
                    + ". "
                    + optionName(slot)
                    + " ";
            assertThat(response.optionLine(slot))
                    .as("slot " + slot + " of BUILD-MENU-OPTIONS")
                    .isEqualTo(expected)
                    .hasSize(OPTION_LINE_WIDTH)
                    .hasSize(40);
            assertThat(response.isPopulatedByProgram(slot)).isTrue();
        }

        @Test
        @DisplayName("the ten images are exactly the ten COMEN02Y labels, numbered and padded")
        void theTenImagesMatchTheCopybookLabels() {
            List<String> expected = List.of(
                    "01. Account View                        ",
                    "02. Account Update                      ",
                    "03. Credit Card List                    ",
                    "04. Credit Card View                    ",
                    "05. Credit Card Update                  ",
                    "06. Transaction List                    ",
                    "07. Transaction View                    ",
                    "08. Transaction Add                     ",
                    "09. Transaction Reports                 ",
                    "10. Bill Payment                        ");

            MainMenuResponse response = afterSendMenuScreen(1, "");

            for (int slot = 1; slot <= POPULATED_OPTION_SLOTS; slot++) {
                assertThat(expected.get(slot - 1)).hasSize(OPTION_LINE_WIDTH);
                assertThat(response.optionLine(slot))
                        .as("slot " + slot + " must match app/cpy/COMEN02Y.cpy byte for byte")
                        .isEqualTo(expected.get(slot - 1));
            }
            assertThat(expected).hasSize(POPULATED_OPTION_SLOTS).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("slot 8 takes the LIVE line 70, never the commented-out variant on line 69")
        void slotEightUsesTheLiveLabel() {
            MainMenuResponse response = afterSendMenuScreen(8, "");

            assertThat(optionName(8)).isEqualTo("Transaction Add                    ").hasSize(35);
            assertThat(response.optionLine(8)).isEqualTo("08. Transaction Add                     ");
            assertThat(response.optionLine(8)).doesNotContain("Admin Only");
            assertThat(MenuOptions.optionBySubscript(8)).isPresent();
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptName())
                    .isEqualTo(optionName(8));
            assertThat(MenuOptions.optionBySubscript(8).orElseThrow().menuOptUsrType()).isEqualTo("U");
        }

        @Test
        @DisplayName("the ten images agree with admin.model.MenuOptions, which reads the same copybook")
        void theImagesAgreeWithTheSharedMenuTable() {
            List<MenuOptions.MenuOption> table = MenuOptions.activeOptions();
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(table).hasSize(POPULATED_OPTION_SLOTS);
            assertThat(MenuOptions.menuOptCount()).isEqualTo(POPULATED_OPTION_SLOTS).isEqualTo(10);
            assertThat(MenuOptions.TABLE_SIZE).isEqualTo(DECLARED_OPTION_SLOTS).isEqualTo(12);
            for (int slot = 1; slot <= POPULATED_OPTION_SLOTS; slot++) {
                MenuOptions.MenuOption entry = table.get(slot - 1);
                String fromTheTable = CODEC.movePicX(CODEC.concatenateDelimitedBySize(
                        entry.menuOptNumImage(), ". ", entry.menuOptName()), OPTION_LINE_WIDTH);
                assertThat(response.optionLine(slot)).isEqualTo(fromTheTable);
                assertThat(entry.menuOptNum()).isEqualTo(slot);
            }
        }

        @ParameterizedTest(name = "slot {0} is 40 spaces, because the loop never reaches it")
        @ValueSource(ints = {11, 12})
        @DisplayName("slots 11 and 12 are 40 spaces - not null, not absent, not pruned")
        void theTwoUnreachableSlotsAreSpaceFilled(int slot) {
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(response.optionLine(slot))
                    .as("slot " + slot + " is declared by the mapset and cleared by MOVE LOW-VALUES")
                    .isNotNull()
                    .isEqualTo(" ".repeat(OPTION_LINE_WIDTH))
                    .hasSize(OPTION_LINE_WIDTH)
                    .isBlank();
            assertThat(response.isPopulatedByProgram(slot))
                    .as("the loop bound at app/cbl/COMEN01C.cbl:238-239 stops at ten")
                    .isFalse();
        }

        @Test
        @DisplayName("the two unreachable slots are still writable, so the type is not narrower than the map")
        void theTwoUnreachableSlotsRemainWritable() {
            MainMenuResponse response = MainMenuResponse.builder()
                    .optionLine(11, "eleven")
                    .optionLine(12, "twelve")
                    .build();

            assertThat(response.optn011()).isEqualTo("eleven");
            assertThat(response.optn012()).isEqualTo("twelve");
            assertThat(response.optionLine(11)).isEqualTo("eleven");
            assertThat(response.optionLine(12)).isEqualTo("twelve");
            assertThat(response.withOptionLine(12, "replaced").optn012()).isEqualTo("replaced");
        }

        @Test
        @DisplayName("table size 12 and populated count 10 are separate, independently asserted facts")
        void theTwoCountsAreDistinct() {
            assertThat(MainMenuResponse.OPTION_LINE_COUNT).isEqualTo(12);
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(10);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isGreaterThan(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT
                    - MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(2);
            assertThat(MainMenuResponse.FIRST_OPTION_LINE_SLOT).isEqualTo(1);
            assertThat(MainMenuResponse.LAST_OPTION_LINE_SLOT).isEqualTo(12);
        }

        @ParameterizedTest(name = "slot {0} is populated by the program: {1}")
        @MethodSource("com.vsergeychik.carddemo.admin.dto.MainMenuResponseTest#everySlotAndWhetherWritten")
        @DisplayName("the loop bound is reported for all twelve slots, both sides of the boundary")
        void populationFollowsTheLoopBound(int slot, boolean populated) {
            assertThat(MainMenuResponse.builder().build().isPopulatedByProgram(slot))
                    .isEqualTo(populated);
        }

        @ParameterizedTest(name = "slot {0} is rejected rather than clamped")
        @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 13, 14, Integer.MAX_VALUE})
        @DisplayName("a subscript outside 1..12 is refused on both sides, never silently clamped")
        void outOfRangeSubscriptsAreRefused(int slot) {
            MainMenuResponse response = MainMenuResponse.builder().build();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.optionLine(slot))
                    .withMessageContaining("1..12");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.isPopulatedByProgram(slot));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> MainMenuResponse.builder().optionLine(slot, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.withOptionLine(slot, "x"));
        }

        @Test
        @DisplayName("the 1-based accessor and the 0-based list view address the same twelve slots")
        void theOneBasedAndZeroBasedViewsAgree() {
            MainMenuResponse response = afterSendMenuScreen(1, "");

            List<String> view = response.optionLines();
            assertThat(view).hasSize(DECLARED_OPTION_SLOTS);
            for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
                assertThat(view.get(slot - 1))
                        .as("optionLines().get(" + (slot - 1) + ") is optionLine(" + slot + ")")
                        .isEqualTo(response.optionLine(slot));
            }
            assertThat(view.get(0)).isEqualTo(response.optn001());
            assertThat(view.get(DECLARED_OPTION_SLOTS - 1)).isEqualTo(response.optn012());
        }

        @Test
        @DisplayName("each named optn0nn setter of COMEN1AO writes only its own slot")
        void eachSetterIsIndependent() {
            for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
                MainMenuResponse response = MainMenuResponse.builder()
                        .optionLine(slot, "written")
                        .build();

                for (int other = 1; other <= DECLARED_OPTION_SLOTS; other++) {
                    if (other == slot) {
                        assertThat(response.optionLine(other)).isEqualTo("written");
                    } else {
                        assertThat(response.optionLine(other))
                                .as("writing slot " + slot + " must not touch slot " + other)
                                .isEqualTo(ScreenFieldImage.unpainted(
                                        MainMenuResponse.OPTION_LINE_LENGTH));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("Navigation - EXEC CICS XCTL becomes three response members, and no server state")
    class StatelessNavigation {
        @Test
        @DisplayName("the response names the next program, mapset and map")
        void theThreeNavigationMembersExist() {
            List<String> components = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(components).contains("nextProgram", "nextMapset", "nextMap");
        }

        @Test
        @DisplayName("this screen's own targets are COMEN01 and COMEN1A")
        void theDefaultTargetsAreThisScreen() {
            MainMenuResponse response = MainMenuResponse.builder().build();

            assertThat(response.nextMapset()).isEqualTo("COMEN01")
                    .isEqualTo(MainMenuResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(response.nextMap()).isEqualTo("COMEN1A")
                    .isEqualTo(MainMenuResponse.MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(MainMenuResponse.initial().nextMapset()).isEqualTo("COMEN01");
            assertThat(MainMenuResponse.initial().nextMap()).isEqualTo("COMEN1A");
        }

        @ParameterizedTest(name = "nextProgram can carry {0}")
        @ValueSource(strings = {"COACTVWC", "COACTUPC", "COCRDLIC", "COCRDSLC", "COCRDUPC",
                                "COTRN00C", "COTRN01C", "COTRN02C", "CORPT00C", "COBIL00C",
                                "COSGN00C"})
        @DisplayName("all ten option targets and the sign-on return are expressible")
        void everyTransferTargetIsExpressible(String target) {
            MainMenuResponse response = MainMenuResponse.builder().nextProgram(target).build();

            assertThat(response.nextProgram()).isEqualTo(target)
                    .hasSize(MainMenuResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.withNextProgram("COSGN00C").nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the ten targets are the ten CDEMO-MENU-OPT-PGMNAME values, in subscript order")
        void theTenTargetsAreTheMenuTablesPrograms() {
            assertThat(OPTION_TARGET_PROGRAMS).hasSize(POPULATED_OPTION_SLOTS).doesNotHaveDuplicates();
            for (int slot = 1; slot <= POPULATED_OPTION_SLOTS; slot++) {
                assertThat(MenuOptions.optionBySubscript(slot).orElseThrow().menuOptPgmName())
                        .as("subscript " + slot + " of the shared menu table")
                        .isEqualTo(CODEC.movePicX(OPTION_TARGET_PROGRAMS.get(slot - 1),
                                MenuOptions.OPT_PGMNAME_LENGTH));
                assertThat(OPTION_TARGET_PROGRAMS.get(slot - 1))
                        .hasSize(MainMenuResponse.NEXT_PROGRAM_LENGTH);
            }
        }

        @Test
        @DisplayName("the sign-on route is COSGN00C, and this endpoint is where the 'U' role lands")
        void theSignOnRouteIsRecorded() {
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo("COSGN00C");
            assertThat(MainMenuResponse.builder().build().nextProgram())
                    .as("the sign-on program is a documented target, never a silent default")
                    .isNull();
            assertThat(MainMenuResponse.builder()
                    .nextProgram(MainMenuResponse.SIGNON_PROGRAM)
                    .build()
                    .nextProgram()).isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the navigation widths come from the communication area: a program is 8, a map is 7")
        void theNavigationWidthsTrackTheCommarea() {
            assertThat(MainMenuResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(NavigationContext.TO_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(MainMenuResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
            assertThat(MainMenuResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAP_LENGTH)
                    .as("CDEMO-LAST-MAP is X(7); assuming X(8) would shift the last two bytes")
                    .isNotEqualTo(NavigationContext.TO_PROGRAM_LENGTH);
        }

        @Test
        @DisplayName("the echoed communication area is a payload member, and it is exactly 160 bytes")
        void theCommunicationAreaIsOneHundredAndSixtyBytes() throws Exception {
            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withUserTypeUser())
                    .build();

            assertThat(Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList()).contains("navigationContext");
            assertThat(new ObjectMapper().writeValueAsString(response))
                    .contains("\"navigationContext\"")
                    .contains("\"userType\":\"U\"");

            assertThat(NavigationContext.GENERAL_INFO_LENGTH
                    + NavigationContext.CUSTOMER_INFO_LENGTH
                    + NavigationContext.ACCOUNT_INFO_LENGTH
                    + NavigationContext.CARD_INFO_LENGTH
                    + NavigationContext.MORE_INFO_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH)
                    .isEqualTo(34 + 84 + 12 + 16 + 14)
                    .isEqualTo(160);
            assertThat(response.navigationContext().toFixedWidth(CODEC)).hasSize(160);
        }

        @Test
        @DisplayName("CDEMO-USER-TYPE is echoed unaltered, because COMEN01C only ever reads it")
        void theUserTypeIsEchoedUnaltered() {
            NavigationContext arriving = NavigationContext.empty()
                    .withUserId("USER0001")
                    .withUserTypeUser()
                    .withPgmReenter();

            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(arriving)
                    .build();

            assertThat(response.navigationContext()).isSameAs(arriving).isEqualTo(arriving);
            assertThat(response.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_USER).isEqualTo("U");
            assertThat(response.navigationContext().isUser()).isTrue();
            assertThat(response.navigationContext().isAdmin()).isFalse();
            assertThat(response.navigationContext().userId()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("an administrator's context is echoed just as unaltered as a user's")
        void anAdminContextIsEchoedToo() {
            NavigationContext arriving = NavigationContext.empty().withUserTypeAdmin();

            MainMenuResponse response = MainMenuResponse.builder()
                    .navigationContext(arriving)
                    .build();

            assertThat(response.navigationContext().userType())
                    .isEqualTo(NavigationContext.USER_TYPE_ADMIN).isEqualTo("A");
            assertThat(response.navigationContext().isAdmin()).isTrue();
            assertThat(response.navigationContext().isUser()).isFalse();
        }

        @Test
        @DisplayName("both program contexts survive the echo: ENTER 0 and REENTER 1")
        void bothProgramContextsSurvive() {
            MainMenuResponse onEnter = MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmEnter())
                    .build();
            MainMenuResponse onReenter = MainMenuResponse.builder()
                    .navigationContext(NavigationContext.empty().withPgmReenter())
                    .build();

            assertThat(onEnter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_ENTER).isZero();
            assertThat(onEnter.navigationContext().isEnter()).isTrue();
            assertThat(onReenter.navigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER).isEqualTo(1);
            assertThat(onReenter.navigationContext().isReenter()).isTrue();
        }

        @Test
        @DisplayName("an explicit null communication area is kept, because EIBCALEN = 0 is a real state")
        void anExplicitlyAbsentContextIsKept() {
            MainMenuResponse transferred = MainMenuResponse.builder()
                    .navigationContext(null)
                    .build();

            assertThat(transferred.navigationContext())
                    .describedAs("null means 'no communication area travelled', and it survives")
                    .isNull();
            assertThat(transferred.withNavigationContext(null).navigationContext())
                    .describedAs("and it survives a derived copy, because toBuilder passes it through")
                    .isNull();

            MainMenuResponse response = MainMenuResponse.builder().build();
            assertThat(response.navigationContext()).isNotNull()
                    .isEqualTo(NavigationContext.empty());
            assertThat(response.navigationContext().toFixedWidth(CODEC)).hasSize(160);
            assertThat(MainMenuResponse.initial().navigationContext())
                    .isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("no server-side state: no session, no cache, no static mutable field (gates G37, G53)")
        void nothingIsHeldOnTheServer() {
            for (Class<?> type : List.of(MainMenuResponse.class,
                    MainMenuResponse.Builder.class,
                    MainMenuResponseTest.class)) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.isSynthetic() || field.getName().startsWith("$")) {
                        continue;
                    }
                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as(type.getSimpleName() + "." + field.getName()
                                        + " is static and must therefore be final")
                                .isTrue();
                    }
                }
            }
            assertThat(Stream.of(MainMenuResponse.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList())
                    .doesNotContain("getSession", "session", "cache");
        }

        @Test
        @DisplayName("two responses built from one context do not share mutable state")
        void responsesAreIndependentOfEachOther() {
            NavigationContext context = NavigationContext.empty().withUserTypeUser();
            MainMenuResponse first = MainMenuResponse.builder()
                    .navigationContext(context)
                    .nextProgram("COACTVWC")
                    .build();

            MainMenuResponse second = first.withNextProgram("COBIL00C");

            assertThat(first.nextProgram()).isEqualTo("COACTVWC");
            assertThat(second.nextProgram()).isEqualTo("COBIL00C");
            assertThat(second.navigationContext()).isSameAs(first.navigationContext());
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("Colour and header literals - DFHRED by declaration, and the COTTL01Y:21 decoy untouched")
    class ColourAndHeaderLiterals {
        @Test
        @DisplayName("the message colour defaults to DFHRED, as the mapset's COLOR=RED declares")
        void theColourDefaultsToRed() {
            assertThat(MainMenuResponse.builder().build().errMsgColor())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(MainMenuResponse.initial().errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(BmsAttributes.DFHRED).isEqualTo((byte) 0xF2);
        }

        @Test
        @DisplayName("the coming-soon path overrides it to DFHGREEN, exactly as COMEN01C:158 does")
        void theColourIsOverridableToGreen() {
            MainMenuResponse response =
                    MainMenuResponse.builder().build().withErrMsgColor(BmsAttributes.DFHGREEN);

            assertThat(response.errMsgColor()).isEqualTo(BmsAttributes.DFHGREEN);
            assertThat(BmsAttributes.DFHGREEN).isEqualTo((byte) 0xF4);
            assertThat(BmsAttributes.DFHGREEN).isNotEqualTo(BmsAttributes.DFHRED);
        }

        @Test
        @DisplayName("the default is red, never the terminal's own default colour")
        void theDefaultIsNotTheTerminalDefault() {
            assertThat(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(MainMenuResponse.builder().build().errMsgColor())
                    .isNotEqualTo(BmsAttributes.DFHDFCOL);
        }

        @Test
        @DisplayName("title01 is the 40-character CCDA-TITLE01 of COTTL01Y:18-19")
        void titleOneIsTheHeadingLiteral() {
            assertThat(ScreenTitles.CCDA_TITLE01)
                    .isEqualTo("      AWS Mainframe Modernization       ")
                    .hasSize(TITLE_WIDTH)
                    .hasSize(40)
                    .startsWith("      ")
                    .endsWith("       ");
            assertThat(ScreenTitles.CCDA_TITLE01.strip()).isEqualTo("AWS Mainframe Modernization")
                    .hasSize(27);
            assertThat(6 + 27 + 7).isEqualTo(TITLE_WIDTH);
            assertThat(afterSendMenuScreen(1, "").title01())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .hasSize(MainMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("title02 is the ACTIVE value on COTTL01Y:22, and the :21 decoy is never asserted")
        void titleTwoIsTheActiveLiteral() {
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .isEqualTo("              CardDemo                  ")
                    .hasSize(TITLE_WIDTH)
                    .hasSize(40);
            assertThat(ScreenTitles.CCDA_TITLE02.strip()).isEqualTo("CardDemo").hasSize(8);
            assertThat(14 + 8 + 18).isEqualTo(TITLE_WIDTH);
            assertThat(ScreenTitles.CCDA_TITLE02)
                    .as("the live value on :22, not the commented-out wording on :21")
                    .doesNotContain("Credit Card Demo Application")
                    .doesNotContain("CCDA)");
            assertThat(afterSendMenuScreen(1, "").title02())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .hasSize(MainMenuResponse.TITLE_LENGTH);
        }

        @Test
        @DisplayName("the two titles are different fields with the same width, and neither is the other")
        void theTwoTitlesAreDistinct() {
            assertThat(ScreenTitles.CCDA_TITLE01).isNotEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSameSizeAs(ScreenTitles.CCDA_TITLE02);
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(MainMenuResponse.TITLE_LENGTH)
                    .isEqualTo(40);
            MainMenuResponse response = afterSendMenuScreen(1, "");
            assertThat(response.title01()).isNotEqualTo(response.title02());
        }

        @Test
        @DisplayName("the three near-identical sign-off and key messages are three different constants")
        void theThreeMessageConstantsAreNotInterchangeable() {
            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .as("app/cpy/COTTL01Y.cpy:23-24, PIC X(40), names the CCDA application")
                    .isEqualTo("Thank you for using CCDA application... ")
                    .hasSize(ScreenTitles.TITLE_LENGTH)
                    .hasSize(40)
                    .contains("CCDA application");
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .as("app/cpy/CSMSG01Y.cpy:18-19, PIC X(50), names the CardDemo application")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .contains("CardDemo application");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .as("app/cpy/CSMSG01Y.cpy:20-21, PIC X(50)")
                    .hasSize(SystemMessages.MESSAGE_LENGTH)
                    .hasSize(50)
                    .startsWith("Invalid key pressed.");

            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(ScreenTitles.CCDA_THANK_YOU.length())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.length());
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(40);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(ScreenTitles.CCDA_THANK_YOU.strip())
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.strip());
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSameSizeAs(SystemMessages.CCDA_MSG_INVALID_KEY);
        }

        @Test
        @DisplayName("trnName and pgmName are the program's own two working-storage literals")
        void theHeaderIdentifiersComeFromWorkingStorage() {
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(response.trnName()).isEqualTo("CM00").hasSize(MainMenuResponse.TRN_NAME_LENGTH);
            assertThat(response.pgmName()).isEqualTo("COMEN01C")
                    .hasSize(MainMenuResponse.PGM_NAME_LENGTH);
        }

        @Test
        @DisplayName("curDate is mm/dd/yy and curTime is hh:mm:ss, both exactly 8 characters")
        void theDateAndTimeShapesComeFromCsdat01y() {
            DateHeader header = DateHeader.from(CODEC, FIXED_CLOCK);
            MainMenuResponse response = afterSendMenuScreen(1, "");

            assertThat(response.curDate()).isEqualTo(header.wsCurdateMmDdYy())
                    .isEqualTo("07/19/22")
                    .hasSize(MainMenuResponse.CUR_DATE_LENGTH)
                    .hasSize(8)
                    .matches("\\d{2}/\\d{2}/\\d{2}");
            assertThat(response.curTime()).isEqualTo(header.wsCurtimeHhMmSs())
                    .isEqualTo("23:12:33")
                    .hasSize(MainMenuResponse.CUR_TIME_LENGTH)
                    .hasSize(8)
                    .matches("\\d{2}:\\d{2}:\\d{2}");
            assertThat(response.curDate().charAt(2)).isEqualTo('/');
            assertThat(response.curDate().charAt(5)).isEqualTo('/');
            assertThat(response.curTime().charAt(2)).isEqualTo(':');
            assertThat(response.curTime().charAt(5)).isEqualTo(':');
        }

        @Test
        @DisplayName("the same fixed clock always renders the same header, so the suite is deterministic")
        void theFixedClockIsDeterministic() {
            assertThat(DateHeader.from(CODEC, FIXED_CLOCK).wsCurdateMmDdYy())
                    .isEqualTo(DateHeader.from(CODEC, FIXED_CLOCK).wsCurdateMmDdYy());
            assertThat(DateHeader.from(CODEC, FIXED_CLOCK).wsCurtimeHhMmSs())
                    .isEqualTo(DateHeader.from(CODEC, FIXED_CLOCK).wsCurtimeHhMmSs());
            Clock other = Clock.fixed(Instant.parse("2001-02-03T04:05:06Z"), ZoneOffset.UTC);
            assertThat(DateHeader.from(CODEC, other).wsCurdateMmDdYy()).isEqualTo("02/03/01");
            assertThat(DateHeader.from(CODEC, other).wsCurtimeHhMmSs()).isEqualTo("04:05:06");
        }
    }

    @Nested
    @DisplayName("The admin twin is a DISTINCT Java type - the duplication is documented, not deduplicated")
    class AdminTwinIsADistinctType {
        @Test
        @DisplayName("AdminMenuResponse and MainMenuResponse are different Java types")
        void theTwoResponseTypesAreDistinct() {
            assertThat(AdminMenuResponse.class).isNotEqualTo(MainMenuResponse.class);
            assertThat(MainMenuResponse.class).isNotEqualTo(AdminMenuResponse.class);
            assertThat(AdminMenuResponse.class.getName()).isNotEqualTo(MainMenuResponse.class.getName());
        }

        @Test
        @DisplayName("neither is assignable to the other, so nothing can be substituted for the other")
        void neitherIsAssignableToTheOther() {
            assertThat(AdminMenuResponse.class.isAssignableFrom(MainMenuResponse.class)).isFalse();
            assertThat(MainMenuResponse.class.isAssignableFrom(AdminMenuResponse.class)).isFalse();
        }

        @Test
        @DisplayName("neither shares a supertype or an interface with the other beyond Record")
        void theyShareNothingButRecord() {
            assertThat(MainMenuResponse.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(AdminMenuResponse.class.getSuperclass()).isEqualTo(Record.class);
            assertThat(MainMenuResponse.class.getInterfaces()).isEmpty();
            assertThat(AdminMenuResponse.class.getInterfaces()).isEmpty();
            assertThat(MainMenuResponse.class.isRecord()).isTrue();
        }

        @Test
        @DisplayName("the four screen identities differ in every one of the four values")
        void theScreenIdentitiesDiffer() {
            assertThat(MainMenuResponse.TRANSACTION_ID).isEqualTo("CM00")
                    .isNotEqualTo(AdminMenuResponse.TRANSACTION_ID);
            assertThat(MainMenuResponse.PROGRAM_NAME).isEqualTo("COMEN01C")
                    .isNotEqualTo(AdminMenuResponse.PROGRAM_NAME);
            assertThat(MainMenuResponse.MAPSET_NAME).isEqualTo("COMEN01")
                    .isNotEqualTo(AdminMenuResponse.MAPSET_NAME);
            assertThat(MainMenuResponse.MAP_NAME).isEqualTo("COMEN1A")
                    .isNotEqualTo(AdminMenuResponse.MAP_NAME);
            assertThat(MainMenuResponse.SIGNON_PROGRAM).isEqualTo(AdminMenuResponse.SIGNON_PROGRAM)
                    .isEqualTo("COSGN00C");
        }

        @Test
        @DisplayName("the two records name their colour member differently, so no helper could span them")
        void evenTheColourMembersAreNamedDifferently() {
            List<String> main = Stream.of(MainMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            List<String> admin = Stream.of(AdminMenuResponse.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(main).contains("errMsgColor").doesNotContain("messageColour");
            assertThat(admin).contains("messageColour").doesNotContain("errMsgColor");
            assertThat(main).isNotEqualTo(admin);
        }

        @Test
        @DisplayName("the identical geometry is asserted independently on each side, not shared")
        void theGeometryAgreesWithoutBeingShared() {
            assertThat(MainMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(AdminMenuResponse.SYMBOLIC_MAP_LENGTH)
                    .isEqualTo(12 + 20 * 7 + 668)
                    .isEqualTo(820);
            assertThat(MainMenuResponse.OPTION_LINE_COUNT)
                    .isEqualTo(AdminMenuResponse.OPTION_LINE_COUNT)
                    .isEqualTo(DECLARED_OPTION_SLOTS);
        }

        @Test
        @DisplayName("the two screens fill a different number of option slots - ten here, four there")
        void thePopulatedCountsDiffer() {
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT).isEqualTo(10);
            assertThat(MenuOptions.ACTIVE_OPTION_COUNT).isEqualTo(10);
            assertThat(MainMenuResponse.ACTIVE_OPTION_LINE_COUNT)
                    .isLessThan(MainMenuResponse.OPTION_LINE_COUNT);
        }

        @Test
        @DisplayName("this file declares no shared abstraction of its own")
        void thisTestDeclaresNoSharedAbstraction() {
            assertThat(MainMenuResponseTest.class.getSuperclass()).isEqualTo(Object.class);
            assertThat(MainMenuResponseTest.class.getInterfaces()).isEmpty();
            for (Class<?> nested : MainMenuResponseTest.class.getDeclaredClasses()) {
                assertThat(nested.getSuperclass())
                        .as(nested.getSimpleName() + " must not extend a shared fixture")
                        .isEqualTo(Object.class);
                assertThat(nested.getInterfaces()).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("Pure payload - the DTO stores what it is given, and the codec does the narrowing")
    class PurePayloadSemantics {
        @Test
        @DisplayName("an over-width message is stored verbatim: the DTO never truncates")
        void theDtoDoesNotTruncate() {
            String tooWide = positionalProbe(WS_MESSAGE_WIDTH);

            MainMenuResponse response = MainMenuResponse.builder().errMsg(tooWide).build();

            assertThat(response.errMsg()).isEqualTo(tooWide).hasSize(WS_MESSAGE_WIDTH);
            assertThat(response.errMsg()).isNotEqualTo(CODEC.movePicX(tooWide, ERR_MSG_WIDTH));
        }

        @Test
        @DisplayName("a short value is stored verbatim: the DTO never pads")
        void theDtoDoesNotPad() {
            MainMenuResponse response = MainMenuResponse.builder()
                    .errMsg("short")
                    .option("1")
                    .optionLine(1, "x")
                    .build();

            assertThat(response.errMsg()).isEqualTo("short").hasSize(5);
            assertThat(response.option()).isEqualTo("1").hasSize(1);
            assertThat(response.optionLine(1)).isEqualTo("x").hasSize(1);
        }

        @Test
        @DisplayName("an all-spaces value is preserved exactly: the DTO never trims")
        void theDtoDoesNotTrim() {
            String allSpaces = spaces(OPTION_LINE_WIDTH);

            MainMenuResponse response = MainMenuResponse.builder()
                    .optionLine(6, allSpaces)
                    .errMsg(spaces(ERR_MSG_WIDTH))
                    .build();

            assertThat(response.optionLine(6)).isEqualTo(allSpaces).hasSize(OPTION_LINE_WIDTH)
                    .isNotEmpty();
            assertThat(response.errMsg()).hasSize(ERR_MSG_WIDTH).isBlank();
        }

        @Test
        @DisplayName("an unset member stays unpainted, which is how \"never written\" is expressed")
        void anUnsetMemberStaysUnpainted() {
            MainMenuResponse blank = MainMenuResponse.builder().build();

            for (String member : PAYLOAD_MEMBERS) {
                assertThat(ScreenFieldImage.isUnpainted(payloadMember(blank, member)))
                        .as(member + " has not been written and must not be invented")
                        .isTrue();
            }
            assertThat(blank.optionLines())
                    .hasSize(DECLARED_OPTION_SLOTS)
                    .doesNotContainNull()
                    .allSatisfy(line -> assertThat(ScreenFieldImage.isUnpainted(line)).isTrue());
        }

        @Test
        @DisplayName("the option-line view is unmodifiable and freshly built for each caller")
        void theOptionLineViewIsSafeToHandOut() {
            MainMenuResponse response = afterSendMenuScreen(1, "");
            List<String> view = response.optionLines();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.set(0, "tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.add("tampered"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> view.remove(0));
            assertThat(response.optionLines()).isNotSameAs(view).isEqualTo(view);
        }

        @Test
        @DisplayName("every with-method returns a new instance and leaves the original untouched")
        void theWithMethodsNeverMutate() {
            MainMenuResponse original = afterSendMenuScreen(1, VALIDATION_MESSAGE);

            assertThat(original.withErrMsg("other")).isNotSameAs(original);
            assertThat(original.withErrMsgColor(BmsAttributes.DFHGREEN)).isNotSameAs(original);
            assertThat(original.withNextProgram("COBIL00C")).isNotSameAs(original);
            assertThat(original.withNavigationContext(NavigationContext.empty().withUserTypeAdmin()))
                    .isNotSameAs(original);
            assertThat(original.withOptionLine(11, "eleven")).isNotSameAs(original);

            assertThat(original).isEqualTo(afterSendMenuScreen(1, VALIDATION_MESSAGE));
            assertThat(original.errMsg()).isEqualTo(errMsgImage(VALIDATION_MESSAGE));
            assertThat(original.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.optionLine(11)).isEqualTo(spaces(OPTION_LINE_WIDTH));
        }

        @Test
        @DisplayName("toBuilder round-trips all twenty-six members")
        void toBuilderRoundTripsEverything() {
            MainMenuResponse original = afterSendMenuScreen(7, NO_ACCESS_MESSAGE)
                    .withNextProgram("COTRN01C")
                    .withErrMsgColor(BmsAttributes.DFHGREEN);

            assertThat(original.toBuilder().build()).isEqualTo(original);
            assertThat(original.toBuilder().build()).isNotSameAs(original);
            assertThat(original.toBuilder().build().hashCode()).isEqualTo(original.hashCode());
        }

        @Test
        @DisplayName("value semantics: equality follows from the members, one member at a time")
        void equalityFollowsEveryMember() {
            MainMenuResponse base = afterSendMenuScreen(1, VALIDATION_MESSAGE);

            assertThat(base).isEqualTo(afterSendMenuScreen(1, VALIDATION_MESSAGE));
            assertThat(base).isNotEqualTo(afterSendMenuScreen(2, VALIDATION_MESSAGE));
            assertThat(base).isNotEqualTo(afterSendMenuScreen(1, NO_ACCESS_MESSAGE));
            assertThat(base).isNotEqualTo(base.withErrMsgColor(BmsAttributes.DFHGREEN));
            assertThat(base).isNotEqualTo(base.withNextProgram("COBIL00C"));
            assertThat(base).isNotEqualTo(base.withOptionLine(11, "eleven"));
            assertThat(base).isNotEqualTo(null);
            assertThat(base).isNotEqualTo("not a response");
            assertThat(base.toString()).contains("COMEN01C").contains("CM00");
        }

        @Test
        @DisplayName("the canonical constructor takes all twenty-six members positionally")
        void theCanonicalConstructorIsUsable() {
            MainMenuResponse response = new MainMenuResponse("CM00",
                    ScreenTitles.CCDA_TITLE01,
                    "07/19/22",
                    "COMEN01C",
                    ScreenTitles.CCDA_TITLE02,
                    "23:12:33",
                    optionLineImage(1),
                    optionLineImage(2),
                    optionLineImage(3),
                    optionLineImage(4),
                    optionLineImage(5),
                    optionLineImage(6),
                    optionLineImage(7),
                    optionLineImage(8),
                    optionLineImage(9),
                    optionLineImage(10),
                    spaces(OPTION_LINE_WIDTH),
                    spaces(OPTION_LINE_WIDTH),
                    "01",
                    errMsgImage(VALIDATION_MESSAGE),
                    NavigationContext.empty(),
                    "COACTVWC",
                    "COMEN01",
                    "COMEN1A",
                    BmsAttributes.DFHRED,
                    false);

            assertThat(response.trnName()).isEqualTo("CM00");
            assertThat(response.errMsg()).hasSize(ERR_MSG_WIDTH);
            assertThat(response.optionLine(1)).isEqualTo("01. Account View                        ");
            assertThat(response.optionLine(12)).isBlank().hasSize(OPTION_LINE_WIDTH);
            assertThat(response).isEqualTo(afterSendMenuScreen(1, VALIDATION_MESSAGE)
                    .withNextProgram("COACTVWC"));
        }

        @Test
        @DisplayName("initial() is the cleared map area of MOVE LOW-VALUES TO COMEN1AO")
        void initialIsTheFirstEntryRepaint() {
            MainMenuResponse initial = MainMenuResponse.initial();

            assertThat(initial.resetAllOutputFields())
                    .as("MOVE LOW-VALUES TO COMEN1AO at app/cbl/COMEN01C.cbl:89")
                    .isTrue();
            assertThat(MainMenuResponse.builder().build().resetAllOutputFields()).isFalse();
            assertThat(initial.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(initial.navigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(initial.nextMapset()).isEqualTo("COMEN01");
            assertThat(initial.nextMap()).isEqualTo("COMEN1A");
            for (String member : PAYLOAD_MEMBERS) {
                assertThat(ScreenFieldImage.isUnpainted(payloadMember(initial, member)))
                        .as(member + " is unwritten on first entry, so it carries the LOW-VALUES image")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a JSON round trip preserves all twenty payload fields byte for byte")
        void theJsonRoundTripIsLossless() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            MainMenuResponse original = afterSendMenuScreen(10, NO_ACCESS_MESSAGE);

            MainMenuResponse revived =
                    mapper.readValue(mapper.writeValueAsString(original), MainMenuResponse.class);

            for (String member : PAYLOAD_MEMBERS) {
                assertThat(payloadMember(revived, member))
                        .as(member + " must survive serialisation unchanged")
                        .isEqualTo(payloadMember(original, member));
            }
            assertThat(revived.option()).isEqualTo("10").hasSize(OPTION_WIDTH);
            assertThat(revived.errMsg()).hasSize(ERR_MSG_WIDTH).startsWith(NO_ACCESS_MESSAGE);
            assertThat(revived.optionLine(12)).isBlank().hasSize(OPTION_LINE_WIDTH);
            assertThat(revived.navigationContext()).isEqualTo(original.navigationContext());
            assertThat(revived.nextMapset()).isEqualTo("COMEN01");
            assertThat(revived.errMsgColor()).isEqualTo(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
            assertThat(revived.errMsgColor()).isNotEqualTo(original.errMsgColor());
            assertThat(original.errMsgColor()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(revived.resetAllOutputFields()).isFalse();
        }
    }

    static Stream<Arguments> everySlotAndWhetherWritten() {
        List<Arguments> cases = new ArrayList<>();
        for (int slot = 1; slot <= DECLARED_OPTION_SLOTS; slot++) {
            cases.add(Arguments.of(slot, slot <= POPULATED_OPTION_SLOTS));
        }
        return cases.stream();
    }

    static Stream<Arguments> everyMessagePath() {
        return Stream.of(
                Arguments.of("COMEN01C:131-132 option validation", VALIDATION_MESSAGE),
                Arguments.of("COMEN01C:140-141 admin-only refusal", NO_ACCESS_MESSAGE),
                Arguments.of("COMEN01C:101 CCDA-MSG-INVALID-KEY", SystemMessages.CCDA_MSG_INVALID_KEY),
                Arguments.of("COMEN01C:159-163 coming soon, option 1",
                        CODEC.concatenateDelimitedBySize(COMING_SOON_PREFIX,
                                delimitedBySpace(optionName(1)),
                                COMING_SOON_SUFFIX)));
    }
}
