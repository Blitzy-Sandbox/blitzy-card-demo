package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse.FieldAttributes;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse.ScreenField;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse.SpanDescriptor;
import com.vsergeychik.carddemo.card.dto.CardSelectResponse.SpanKind;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link CardSelectResponse} against the four artefacts that define it:
 * {@code app/cpy-bms/COCRDSL.CPY} for the item names and widths, {@code app/bms/COCRDSL.bms} for the
 * name-labelled {@code DFHMDF} inventory, {@code app/cbl/COCRDSLC.cbl} for what the program actually moves
 * into the map, and {@code app/cpy/CSSETATY.cpy} for the error-highlight rule.
 */
@DisplayName("CardSelectResponse - the output projection of 01 CCRDSLAO, 504 bytes, 15 of 31 DFHMDF")
final class CardSelectResponseTest {
    /**
     * One name-labelled {@code DFHMDF} of mapset {@code COCRDSL}, transcribed from the two source artefacts
     * by hand.
     *
     * @param dfhmdfLabel the field name on the {@code DFHMDF} in {@code app/bms/COCRDSL.bms}
     * @param cobolName the {@code xxxO} item name in {@code app/cpy-bms/COCRDSL.CPY}
     * @param width the {@code xxxO} {@code PICTURE} width, which is also the {@code DFHMDF} {@code LENGTH}
     *     - the two agree for all fifteen fields, and this suite proves it
     * @param copybookLine the {@code COCRDSL.CPY} line carrying the {@code xxxO} declaration
     * @param mapsetLine the {@code COCRDSL.bms} line carrying the name-labelled {@code DFHMDF}
     * @param bmsRow the {@code POS} row, one-based, within {@code SIZE=(24,80)}
     * @param bmsColumn the {@code POS} column, one-based, within {@code SIZE=(24,80)}
     * @param fillerOffset zero-based offset of the field's three-byte {@code FILLER} in the group
     * @param colourOffset zero-based offset of the field's {@code xxxC} item in the group
     * @param dataOffset zero-based offset of the field's {@code xxxO} item in the group
     */
    private record FieldOracle(String dfhmdfLabel,
            String cobolName,
            int width,
            int copybookLine,
            int mapsetLine,
            int bmsRow,
            int bmsColumn,
            int fillerOffset,
            int colourOffset,
            int dataOffset) {
    }

    private static List<FieldOracle> fieldOracle() {
        return List.of(
                new FieldOracle("TRNNAME", "TRNNAMEO", 4, 116, 34, 1, 7, 12, 15, 19),
                new FieldOracle("TITLE01", "TITLE01O", 40, 122, 38, 1, 21, 23, 26, 30),
                new FieldOracle("CURDATE", "CURDATEO", 8, 128, 47, 1, 71, 70, 73, 77),
                new FieldOracle("PGMNAME", "PGMNAMEO", 8, 134, 57, 2, 7, 85, 88, 92),
                new FieldOracle("TITLE02", "TITLE02O", 40, 140, 61, 2, 21, 100, 103, 107),
                new FieldOracle("CURTIME", "CURTIMEO", 8, 146, 70, 2, 71, 147, 150, 154),
                new FieldOracle("ACCTSID", "ACCTSIDO", 11, 152, 84, 7, 45, 162, 165, 169),
                new FieldOracle("CARDSID", "CARDSIDO", 16, 158, 96, 8, 45, 180, 183, 187),
                new FieldOracle("CRDNAME", "CRDNAMEO", 50, 164, 107, 11, 25, 203, 206, 210),
                new FieldOracle("CRDSTCD", "CRDSTCDO", 1, 170, 116, 13, 25, 260, 263, 267),
                new FieldOracle("EXPMON", "EXPMONO", 2, 176, 126, 15, 25, 268, 271, 275),
                new FieldOracle("EXPYEAR", "EXPYEARO", 4, 182, 133, 15, 30, 277, 280, 284),
                new FieldOracle("INFOMSG", "INFOMSGO", 40, 188, 139, 20, 25, 288, 291, 295),
                new FieldOracle("ERRMSG", "ERRMSGO", 80, 194, 144, 23, 1, 335, 338, 342),
                new FieldOracle("FKEYS", "FKEYSO", 75, 200, 148, 24, 1, 422, 425, 429));
    }

    static List<Arguments> screenFieldsWithOracle() {
        List<FieldOracle> oracle = fieldOracle();
        ScreenField[] fields = ScreenField.values();
        if (oracle.size() != fields.length) {
            throw new IllegalStateException("app/cpy-bms/COCRDSL.CPY declares " + oracle.size()
                    + " output items but ScreenField has " + fields.length + " constants");
        }
        List<Arguments> pairs = new ArrayList<>();
        for (int index = 0; index < fields.length; index++) {
            pairs.add(Arguments.of(fields[index], oracle.get(index)));
        }
        return pairs;
    }

    static List<Arguments> highlightTruthTable() {
        return List.of(
                Arguments.of(FieldValidationState.OK, false, false, false),
                Arguments.of(FieldValidationState.NOT_OK, false, false, false),
                Arguments.of(FieldValidationState.BLANK, false, false, false),
                Arguments.of(FieldValidationState.OK, true, false, false),
                Arguments.of(FieldValidationState.NOT_OK, true, true, false),
                Arguments.of(FieldValidationState.BLANK, true, true, true));
    }

    private static String lowValues(int length) {
        return "\u0000".repeat(length);
    }

    private static FixedWidthCodec picXCodec() {
        Charset codePage = StandardCharsets.US_ASCII;
        return new FixedWidthCodec(codePage);
    }

    private static CardSelectResponse populated() {
        CardSelectResponse response = new CardSelectResponse("CCDL", "t1", "01/02/26", "COCRDSLC",
                "t2", "03:04:05", "00000000011", "1234567890123456", "JOHN DOE", "Y", "07", "2030",
                "info", "error", "keys");
        response.applyThisScreenAsNextTarget();
        return response;
    }

    @Nested
    @DisplayName("Declared geometry - the 504-byte group of app/cpy-bms/COCRDSL.CPY:L109-L200")
    class Geometry {
        @Test
        @DisplayName("15 fields, 387 data bytes, 12 + 15x7 + 387 = 504")
        void widthArithmetic() {
            assertThat(CardSelectResponse.FIELD_COUNT).isEqualTo(15);
            assertThat(CardSelectResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(CardSelectResponse.FILLER_LENGTH).isEqualTo(3);
            assertThat(CardSelectResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(CardSelectResponse.ATTRIBUTE_ITEMS_PER_FIELD).isEqualTo(4);
            assertThat(CardSelectResponse.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(CardSelectResponse.DATA_LENGTH).isEqualTo(387);
            assertThat(CardSelectResponse.GROUP_LENGTH).isEqualTo(504);
            assertThat(12 + (15 * 7) + 387).isEqualTo(CardSelectResponse.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the output stride 3+4+n equals the input stride 2+1+4+n, as REDEFINES requires")
        void strideMatchesTheInputView() {
            int outputPrefix = CardSelectResponse.FILLER_LENGTH
                    + (CardSelectResponse.ATTRIBUTE_ITEMS_PER_FIELD
                            * CardSelectResponse.ATTRIBUTE_ITEM_LENGTH);
            assertThat(outputPrefix).isEqualTo(3 + 4).isEqualTo(7);
            assertThat(CardSelectResponse.FIELD_PREFIX_LENGTH).isEqualTo(outputPrefix);

            int inputPrefix = CardSelectRequest.LENGTH_ITEM_LENGTH
                    + CardSelectRequest.FLAG_ITEM_LENGTH
                    + CardSelectRequest.EXTENDED_ATTRIBUTE_ITEM_LENGTH;
            assertThat(inputPrefix).isEqualTo(2 + 1 + 4).isEqualTo(7);
            assertThat(CardSelectRequest.FIELD_OVERHEAD).isEqualTo(inputPrefix);

            assertThat(outputPrefix).isEqualTo(inputPrefix);
        }

        @Test
        @DisplayName("REDEFINES forces one width on both views: the request and the response are both 504")
        void requestAndResponseAreTheSameWidth() {
            assertThat(CardSelectResponse.GROUP_LENGTH)
                    .as("CCRDSLAO and CCRDSLAI are one storage area")
                    .isEqualTo(CardSelectRequest.GROUP_LENGTH)
                    .isEqualTo(504);
            assertThat(CardSelectResponse.DATA_LENGTH)
                    .isEqualTo(CardSelectRequest.PAYLOAD_LENGTH)
                    .isEqualTo(387);
            assertThat(CardSelectResponse.FIELD_COUNT)
                    .isEqualTo(CardSelectRequest.FIELD_COUNT)
                    .isEqualTo(15);
            assertThat(CardSelectResponse.TIOAPFX_LENGTH)
                    .isEqualTo(CardSelectRequest.TIOAPFX_LENGTH)
                    .isEqualTo(12);
            assertThat(CardSelectResponse.FIELD_PREFIX_LENGTH)
                    .isEqualTo(CardSelectRequest.FIELD_OVERHEAD)
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("carrier widths come from CVCRD01Y: program 8, mapset 7, map 7")
        void carrierWidths() {
            assertThat(CardSelectResponse.NEXT_PROGRAM_LENGTH)
                    .isEqualTo(CardScreenState.CCARD_NEXT_PROG_LENGTH).isEqualTo(8);
            assertThat(CardSelectResponse.NEXT_MAPSET_LENGTH)
                    .isEqualTo(CardScreenState.CCARD_NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardSelectResponse.NEXT_MAP_LENGTH)
                    .isEqualTo(CardScreenState.CCARD_NEXT_MAP_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("screen identity matches COCRDSLC WS-LITERALS at L163-L170")
        void screenIdentity() {
            assertThat(CardSelectResponse.THIS_PROGRAM).isEqualTo("COCRDSLC").hasSize(8);
            assertThat(CardSelectResponse.THIS_TRANID).isEqualTo("CCDL").hasSize(4);
            assertThat(CardSelectResponse.THIS_MAPSET).isEqualTo("COCRDSL").hasSize(7);
            assertThat(CardSelectResponse.MAP_NAME).isEqualTo("CCRDSLA").hasSize(7);
            assertThat(CardSelectResponse.FILLER_ITEM_NAME).isEqualTo("FILLER");
        }

        @Test
        @DisplayName("a CSMSG01Y X(50) message loses 10 into INFOMSGO and gains 30 into ERRMSGO")
        void standardMessageDeltas() {
            assertThat(CardSelectResponse.STANDARD_MESSAGE_LENGTH)
                    .isEqualTo(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(CardSelectResponse.INFOMSGO_STANDARD_MESSAGE_TRUNCATION).isEqualTo(10);
            assertThat(CardSelectResponse.ERRMSGO_STANDARD_MESSAGE_PADDING).isEqualTo(30);
        }

        @Test
        @DisplayName("BMS_INITIAL_FKEYS is the mapset literal of COCRDSL.bms:152 at its declared 75")
        void bmsInitialFkeys() {
            assertThat(CardSelectResponse.BMS_INITIAL_FKEYS)
                    .hasSize(75)
                    .isEqualTo("ENTER=Search Cards  F3=Exit" + " ".repeat(48));
        }
    }

    @Nested
    @DisplayName("ScreenField - the 15 name-labelled DFHMDF entries, checked against the oracle")
    class ScreenFieldVocabulary {
        @ParameterizedTest(name = "{1}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardSelectResponseTest"
                + "#screenFieldsWithOracle")
        @DisplayName("each field's declared width IS its xxxO PICTURE width AND its DFHMDF LENGTH")
        void widthAgreesWithBothArtefacts(ScreenField field, FieldOracle oracle) {
            assertThat(field.dfhmdfLabel()).isEqualTo(oracle.dfhmdfLabel());
            assertThat(field.cobolName()).isEqualTo(oracle.cobolName());
            assertThat(field.length()).as("%s PIC X(n) and DFHMDF LENGTH", oracle.cobolName())
                    .isEqualTo(oracle.width());
            assertThat(field.copybookLine()).isEqualTo(oracle.copybookLine());
            assertThat(field.mapsetLine()).isEqualTo(oracle.mapsetLine());

            assertThat(oracle.bmsRow()).as("POS row of %s", oracle.dfhmdfLabel()).isBetween(1, 24);
            assertThat(oracle.bmsColumn()).as("POS column of %s", oracle.dfhmdfLabel())
                    .isBetween(1, 80);
            assertThat(oracle.bmsColumn() + oracle.width() - 1)
                    .as("%s must fit on its 80-column row", oracle.dfhmdfLabel())
                    .isLessThanOrEqualTo(80);

            assertThat(field.fieldOffset()).isEqualTo(oracle.fillerOffset());
            assertThat(field.fillerOffset()).isEqualTo(oracle.fillerOffset());
            assertThat(field.colourOffset()).isEqualTo(oracle.colourOffset());
            assertThat(field.psOffset()).isEqualTo(oracle.colourOffset() + 1);
            assertThat(field.hilightOffset()).isEqualTo(oracle.colourOffset() + 2);
            assertThat(field.validnOffset()).isEqualTo(oracle.colourOffset() + 3);
            assertThat(field.dataOffset()).isEqualTo(oracle.dataOffset());
            assertThat(field.colourOffset() - field.fillerOffset())
                    .as("the FILLER before %s is three bytes", oracle.cobolName())
                    .isEqualTo(CardSelectResponse.FILLER_LENGTH);
            assertThat(field.dataOffset() - field.colourOffset())
                    .as("four one-byte attribute items precede %s", oracle.cobolName())
                    .isEqualTo(CardSelectResponse.ATTRIBUTE_ITEMS_PER_FIELD);
        }

        @Test
        @DisplayName("the fifteen DFHMDF positions are distinct, so no two fields share a screen cell")
        void positionsAreDistinct() {
            Set<String> positions = new LinkedHashSet<>();
            for (FieldOracle oracle : fieldOracle()) {
                positions.add(oracle.bmsRow() + "," + oracle.bmsColumn());
            }
            assertThat(positions).hasSize(15);
            assertThat(positions).contains("15,25", "15,30");
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("item names are the label plus the copybook's O, C, P, H and V suffixes")
        void itemNaming(ScreenField field) {
            String label = field.dfhmdfLabel();
            assertThat(field.cobolName()).isEqualTo(label + "O");
            assertThat(field.colourItemName()).isEqualTo(label + "C");
            assertThat(field.psItemName()).isEqualTo(label + "P");
            assertThat(field.hilightItemName()).isEqualTo(label + "H");
            assertThat(field.validnItemName()).isEqualTo(label + "V");
            assertThat(field.mapsetLine()).isPositive();
            assertThat(field.describe())
                    .contains(field.cobolName(), "COCRDSL.CPY:" + field.copybookLine(),
                            "COCRDSL.bms:" + field.mapsetLine(), "of CCRDSLAO");
        }

        @Test
        @DisplayName("the label list is exactly the 15 named DFHMDF of COCRDSL.bms - no EXPDAY, no PAGENO")
        void labelInventory() {
            Set<String> labels = new LinkedHashSet<>();
            for (ScreenField field : ScreenField.values()) {
                labels.add(field.dfhmdfLabel());
            }
            assertThat(labels).containsExactly("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02",
                    "CURTIME", "ACCTSID", "CARDSID", "CRDNAME", "CRDSTCD", "EXPMON", "EXPYEAR",
                    "INFOMSG", "ERRMSG", "FKEYS");
            assertThat(labels).hasSize(CardSelectResponse.FIELD_COUNT).hasSize(15);
            assertThat(labels).doesNotContain("EXPDAY", "PAGENO");

            Set<String> items = new LinkedHashSet<>();
            for (ScreenField field : ScreenField.values()) {
                items.add(field.cobolName());
            }
            assertThat(items).hasSize(15).doesNotContain("EXPDAYO", "PAGENOO", "EXPDAYI", "PAGENOI");
            assertThat(new CardSelectResponse().fieldImages().keySet())
                    .hasSize(15)
                    .doesNotContain("EXPDAYO", "PAGENOO");
        }

        @Test
        @DisplayName("the data widths sum to 387 and the last item ends exactly at 504")
        void widthsSumToTheGroup() {
            int total = 0;
            for (ScreenField field : ScreenField.values()) {
                total += field.length();
            }
            assertThat(total).isEqualTo(CardSelectResponse.DATA_LENGTH).isEqualTo(387);

            ScreenField last = ScreenField.FKEYS;
            assertThat(last.dataOffset() + last.length())
                    .isEqualTo(CardSelectResponse.GROUP_LENGTH);
        }
    }

    @Nested
    @DisplayName("Group tiling - the guarantee that an omitted FILLER cannot pass unnoticed")
    class GroupTiling {
        @Test
        @DisplayName("groupGeometry is 91 spans that tile [0,504) with no gap and no overlap")
        void tilesTheGroup() {
            List<SpanDescriptor> spans = CardSelectResponse.groupGeometry();
            assertThat(spans).hasSize(1 + (15 * 6)).hasSize(91);

            int expectedOffset = 0;
            for (SpanDescriptor span : spans) {
                assertThat(span.offset()).as("start of %s", span.cobolName())
                        .isEqualTo(expectedOffset);
                assertThat(span.length()).isPositive();
                assertThat(span.endOffset()).isEqualTo(span.offset() + span.length());
                expectedOffset = span.endOffset();
            }
            assertThat(expectedOffset).isEqualTo(CardSelectResponse.GROUP_LENGTH);
        }

        @Test
        @DisplayName("the first span is the 12-byte TIOAPFX prefix of COCRDSL.CPY:L110")
        void tioapfxPrefixComesFirst() {
            SpanDescriptor first = CardSelectResponse.groupGeometry().get(0);
            assertThat(first.kind()).isEqualTo(SpanKind.TIOAPFX_PREFIX);
            assertThat(first.offset()).isZero();
            assertThat(first.length()).isEqualTo(12);
            assertThat(first.cobolName()).isEqualTo(CardSelectResponse.FILLER_ITEM_NAME);
        }

        @Test
        @DisplayName("each span kind appears 15 times and the data spans total 387 bytes")
        void spanKindCensus() {
            List<SpanDescriptor> spans = CardSelectResponse.groupGeometry();
            for (SpanKind kind : List.of(SpanKind.FILLER, SpanKind.COLOUR, SpanKind.PS,
                    SpanKind.HILIGHT, SpanKind.VALIDN, SpanKind.DATA)) {
                assertThat(spans).filteredOn(span -> span.kind() == kind).as("%s spans", kind)
                        .hasSize(15);
            }
            assertThat(spans).filteredOn(span -> span.kind() == SpanKind.TIOAPFX_PREFIX).hasSize(1);
            assertThat(SpanKind.values()).hasSize(7);

            int dataBytes = 0;
            int fillerBytes = 0;
            for (SpanDescriptor span : spans) {
                if (span.kind() == SpanKind.DATA) {
                    dataBytes += span.length();
                } else if (span.kind() == SpanKind.FILLER
                        || span.kind() == SpanKind.TIOAPFX_PREFIX) {
                    fillerBytes += span.length();
                }
            }
            assertThat(dataBytes).isEqualTo(387);
            assertThat(fillerBytes).isEqualTo(12 + (15 * 3)).isEqualTo(57);
        }

        @Test
        @DisplayName("the returned list is the same unmodifiable instance every time")
        void geometryIsImmutableAndShared() {
            List<SpanDescriptor> spans = CardSelectResponse.groupGeometry();
            assertThat(CardSelectResponse.groupGeometry()).isSameAs(spans);
            assertThatThrownBy(() -> spans.add(spans.get(0)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("verifyGroupTiling accepts the real geometry and returns it unchanged")
        void verifyAcceptsTheRealGeometry() {
            List<SpanDescriptor> spans = CardSelectResponse.groupGeometry();
            assertThat(CardSelectResponse.verifyGroupTiling(spans)).isSameAs(spans);
        }

        @Test
        @DisplayName("verifyGroupTiling rejects a gap, an overlap, a short total and a null")
        void verifyRejectsBrokenLayouts() {
            List<SpanDescriptor> real = CardSelectResponse.groupGeometry();

            List<SpanDescriptor> gapped = new ArrayList<>(real);
            gapped.remove(1);
            assertThatThrownBy(() -> CardSelectResponse.verifyGroupTiling(gapped))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must tile the group with no gap");

            List<SpanDescriptor> overlapping = new ArrayList<>(real);
            overlapping.add(1, real.get(0));
            assertThatThrownBy(() -> CardSelectResponse.verifyGroupTiling(overlapping))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no gap");

            List<SpanDescriptor> tooShort =
                    List.of(new SpanDescriptor("FILLER", SpanKind.TIOAPFX_PREFIX, 0, 12));
            assertThatThrownBy(() -> CardSelectResponse.verifyGroupTiling(tooShort))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("total 12 bytes but the group is declared 504");

            assertThrows(NullPointerException.class,
                    () -> CardSelectResponse.verifyGroupTiling(null));

            List<SpanDescriptor> withNull = new ArrayList<>();
            withNull.add(null);
            assertThrows(NullPointerException.class,
                    () -> CardSelectResponse.verifyGroupTiling(withNull));
        }

        @Test
        @DisplayName("SpanDescriptor rejects a span that could not exist in a fixed-width group")
        void spanDescriptorValidation() {
            assertThrows(NullPointerException.class,
                    () -> new SpanDescriptor(null, SpanKind.DATA, 0, 1));
            assertThrows(NullPointerException.class, () -> new SpanDescriptor("X", null, 0, 1));
            assertThatThrownBy(() -> new SpanDescriptor("X", SpanKind.DATA, -1, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative offset");
            assertThatThrownBy(() -> new SpanDescriptor("X", SpanKind.DATA, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PICTURE clause always declares at least one");

            SpanDescriptor span = new SpanDescriptor("ACCTSIDO", SpanKind.DATA, 169, 11);
            assertThat(span.endOffset()).isEqualTo(180);
            assertThat(span.cobolName()).isEqualTo("ACCTSIDO");
            assertThat(span.kind()).isEqualTo(SpanKind.DATA);
        }
    }

    @Nested
    @DisplayName("Construction - MOVE LOW-VALUES TO CCRDSLAO, app/cbl/COCRDSLC.cbl:428")
    class Construction {
        @Test
        @DisplayName("a fresh payload is LOW-VALUES at every declared width, not spaces")
        void defaultsToLowValues() {
            CardSelectResponse response = new CardSelectResponse();
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.get(field)).as("%s", field.cobolName())
                        .hasSize(field.length())
                        .isEqualTo(lowValues(field.length()))
                        .isNotEqualTo(" ".repeat(field.length()));
            }
        }

        @Test
        @DisplayName("all sixty attribute bytes start at 0x00, which is also DFHDFCOL and DFHDFHI")
        void attributesStartAtLowValues() {
            CardSelectResponse response = new CardSelectResponse();
            for (ScreenField field : ScreenField.values()) {
                FieldAttributes quad = response.attributes(field);
                assertThat(quad.getColour()).isZero();
                assertThat(quad.getPs()).isZero();
                assertThat(quad.getHilight()).isZero();
                assertThat(quad.getValidn()).isZero();
                assertThat(quad.isDefaultColour()).isTrue();
                assertThat(quad.isRedHighlighted()).isFalse();
            }
            assertThat(BmsAttributes.DFHDFCOL).isZero();
            assertThat(BmsAttributes.DFHDFHI).isZero();
        }

        @Test
        @DisplayName("the carriers start empty: a fresh work area, an empty commarea, spaces for the triple")
        void carriersStartEmpty() {
            CardSelectResponse response = new CardSelectResponse();
            assertThat(response.getCardScreenState()).isNotNull();
            assertThat(response.getCardScreenState().getCcAcctId()).isEqualTo(" ".repeat(11));
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(response.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(response.getNextMapset()).isEqualTo(" ".repeat(7));
            assertThat(response.getNextMap()).isEqualTo(" ".repeat(7));
        }

        @Test
        @DisplayName("the fifteen-argument constructor stores in copybook order at exact widths")
        void fifteenArgumentConstructor() {
            CardSelectResponse response = populated();
            assertThat(response.getTrnnameo()).isEqualTo("CCDL");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDSLC");
            assertThat(response.getCurdateo()).isEqualTo("01/02/26");
            assertThat(response.getCurtimeo()).isEqualTo("03:04:05");
            assertThat(response.getAcctsido()).isEqualTo("00000000011");
            assertThat(response.getCardsido()).isEqualTo("1234567890123456");
            assertThat(response.getCrdstcdo()).isEqualTo("Y");
            assertThat(response.getExpmono()).isEqualTo("07");
            assertThat(response.getExpyearo()).isEqualTo("2030");
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.get(field)).as("%s", field.cobolName()).hasSize(field.length());
            }
        }

        @Test
        @DisplayName("initializeGroup restores LOW-VALUES and clears the quads, leaving the carriers alone")
        void initializeGroupIsRepeatable() {
            CardSelectResponse response = populated();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);

            response.initializeGroup();

            for (ScreenField field : ScreenField.values()) {
                assertThat(response.get(field)).isEqualTo(lowValues(field.length()));
                assertThat(response.attributes(field).getColour()).isZero();
            }
            assertThat(response.getNextProgram()).isEqualTo("COCRDSLC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDSL");
            assertThat(response.getNextMap()).isEqualTo("CCRDSLA");
        }

        @Test
        @DisplayName("the copy constructor deep-copies the quads and the mutable work area")
        void copyConstructorIsDeep() {
            CardSelectResponse original = populated();
            original.attributes(ScreenField.CARDSID).setColour(BmsAttributes.DFHRED);
            original.getCardScreenState().setCcAcctId("00000000011");
            original.setNavigationContext(NavigationContext.empty().withFromProgram("COCRDSLC"));

            CardSelectResponse copy = new CardSelectResponse(original);
            assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(copy.fieldImages()).isEqualTo(original.fieldImages());
            assertThat(copy.getCardScreenState()).isNotSameAs(original.getCardScreenState());
            assertThat(copy.getCardScreenState().getCcAcctId()).isEqualTo("00000000011");
            assertThat(copy.getNavigationContext()).isSameAs(original.getNavigationContext());

            copy.attributes(ScreenField.CARDSID).setColour(BmsAttributes.DFHGREEN);
            assertThat(original.attributes(ScreenField.CARDSID).isRedHighlighted()).isTrue();
            assertThat(copy).isNotEqualTo(original);

            assertThrows(NullPointerException.class, () -> new CardSelectResponse(null));
        }
    }

    @Nested
    @DisplayName("The PIC X move rule - pad on the right, truncate on the RIGHT")
    class PicXMoveRule {
        @Test
        @DisplayName("a short value is space-padded and a long one loses its TAIL, never its head")
        void padAndTruncateDirection() {
            CardSelectResponse response = new CardSelectResponse();

            response.setTrnnameo("A");
            assertThat(response.getTrnnameo()).isEqualTo("A   ");

            response.setTrnnameo("ABCDEF");
            assertThat(response.getTrnnameo()).isEqualTo("ABCD").isNotEqualTo("CDEF");

            response.setTrnnameo("");
            assertThat(response.getTrnnameo()).isEqualTo("    ");

            response.setCrdstcdo("YN");
            assertThat(response.getCrdstcdo()).isEqualTo("Y");
        }

        @Test
        @DisplayName("LIT-THISMAPSET PIC X(8) 'COCRDSL ' right-truncates into CCARD-NEXT-MAPSET PIC X(7)")
        void mapsetRightTruncation() {
            CardSelectResponse response = new CardSelectResponse();
            response.setNextMapset("COCRDSL ");
            assertThat(response.getNextMapset()).isEqualTo("COCRDSL").hasSize(7);
        }

        @Test
        @DisplayName("WS-RETURN-MSG PIC X(75) widens into ERRMSGO PIC X(80) with five trailing spaces")
        void returnMessageWidens() {
            CardSelectResponse response = new CardSelectResponse();
            String text = "Did not find cards for this search condition";
            String wsReturnMsg = text + " ".repeat(75 - text.length());
            assertThat(wsReturnMsg).hasSize(75);

            response.setErrmsgo(wsReturnMsg);
            assertThat(response.getErrmsgo()).hasSize(80).isEqualTo(wsReturnMsg + "     ");
        }

        @Test
        @DisplayName("WS-INFO-MSG PIC X(40) into INFOMSGO PIC X(40) is a same-width move")
        void infoMessageSameWidth() {
            CardSelectResponse response = new CardSelectResponse();

            String literal = "   Displaying requested details";
            String wsInfoMsg =
                    literal + " ".repeat(CardSelectResponse.INFOMSGO_LENGTH - literal.length());
            assertThat(wsInfoMsg).hasSize(40);

            response.setInfomsgo(wsInfoMsg);
            assertThat(response.getInfomsgo()).isEqualTo(wsInfoMsg).hasSize(40);
        }

        @Test
        @DisplayName("a CSMSG01Y X(50) message truncates into INFOMSGO and widens into ERRMSGO")
        void standardMessageMoves() {
            FixedWidthCodec codec = picXCodec();
            CardSelectResponse response = new CardSelectResponse();

            response.setInfomsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(response.getInfomsgo()).hasSize(CardSelectResponse.INFOMSGO_LENGTH)
                    .isEqualTo(codec.movePicX(SystemMessages.CCDA_MSG_INVALID_KEY,
                            CardSelectResponse.INFOMSGO_LENGTH));

            response.setErrmsgo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getErrmsgo()).hasSize(CardSelectResponse.ERRMSGO_LENGTH)
                    .isEqualTo(codec.movePicX(SystemMessages.CCDA_MSG_THANK_YOU,
                            CardSelectResponse.ERRMSGO_LENGTH));
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("set(field, value) stores at the declared width for every one of the 15 items")
        void genericSetterHonoursEveryWidth(ScreenField field) {
            CardSelectResponse response = new CardSelectResponse();
            response.set(field, "Z".repeat(200));
            assertThat(response.get(field)).hasSize(field.length())
                    .isEqualTo("Z".repeat(field.length()));

            response.set(field, "");
            assertThat(response.get(field)).isEqualTo(" ".repeat(field.length()));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardSelectResponseTest"
                + "#screenFieldsWithOracle")
        @DisplayName("every field pads right and truncates right, exactly as FixedWidthCodec does")
        void everyFieldFollowsTheCodecMoveRule(ScreenField field, FieldOracle oracle) {
            FixedWidthCodec codec = picXCodec();
            CardSelectResponse response = new CardSelectResponse();

            String shortValue = "A";
            response.set(field, shortValue);
            assertThat(response.get(field)).as("%s short move", oracle.cobolName())
                    .isEqualTo(codec.movePicX(shortValue, oracle.width()))
                    .hasSize(oracle.width())
                    .startsWith(oracle.width() >= 1 ? "A" : "");

            String longValue = "0123456789".repeat(12);
            assertThat(longValue.length()).isGreaterThan(oracle.width());
            response.set(field, longValue);
            assertThat(response.get(field)).as("%s long move", oracle.cobolName())
                    .isEqualTo(codec.movePicX(longValue, oracle.width()))
                    .isEqualTo(longValue.substring(0, oracle.width()))
                    .hasSize(oracle.width());
        }

        @Test
        @DisplayName("every named getter agrees with get(ScreenField)")
        void namedAndGenericAccessorsAgree() {
            CardSelectResponse response = populated();
            assertThat(response.getTrnnameo()).isEqualTo(response.get(ScreenField.TRNNAME));
            assertThat(response.getTitle01o()).isEqualTo(response.get(ScreenField.TITLE01));
            assertThat(response.getCurdateo()).isEqualTo(response.get(ScreenField.CURDATE));
            assertThat(response.getPgmnameo()).isEqualTo(response.get(ScreenField.PGMNAME));
            assertThat(response.getTitle02o()).isEqualTo(response.get(ScreenField.TITLE02));
            assertThat(response.getCurtimeo()).isEqualTo(response.get(ScreenField.CURTIME));
            assertThat(response.getAcctsido()).isEqualTo(response.get(ScreenField.ACCTSID));
            assertThat(response.getCardsido()).isEqualTo(response.get(ScreenField.CARDSID));
            assertThat(response.getCrdnameo()).isEqualTo(response.get(ScreenField.CRDNAME));
            assertThat(response.getCrdstcdo()).isEqualTo(response.get(ScreenField.CRDSTCD));
            assertThat(response.getExpmono()).isEqualTo(response.get(ScreenField.EXPMON));
            assertThat(response.getExpyearo()).isEqualTo(response.get(ScreenField.EXPYEAR));
            assertThat(response.getInfomsgo()).isEqualTo(response.get(ScreenField.INFOMSG));
            assertThat(response.getErrmsgo()).isEqualTo(response.get(ScreenField.ERRMSG));
            assertThat(response.getFkeyso()).isEqualTo(response.get(ScreenField.FKEYS));
        }

        @Test
        @DisplayName("null is rejected everywhere, and the message names the receiving item")
        void nullIsRejectedEverywhere() {
            CardSelectResponse response = new CardSelectResponse();

            assertThatThrownBy(() -> response.setAcctsido(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ACCTSIDO")
                    .hasMessageContaining("PIC X(11)");
            assertThatThrownBy(() -> response.setNextMapset(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("CCARD-NEXT-MAPSET");

            List<Consumer<CardSelectResponse>> nullStores = List.of(
                    target -> target.setTrnnameo(null),
                    target -> target.setTitle01o(null),
                    target -> target.setCurdateo(null),
                    target -> target.setPgmnameo(null),
                    target -> target.setTitle02o(null),
                    target -> target.setCurtimeo(null),
                    target -> target.setCardsido(null),
                    target -> target.setCrdnameo(null),
                    target -> target.setCrdstcdo(null),
                    target -> target.setExpmono(null),
                    target -> target.setExpyearo(null),
                    target -> target.setInfomsgo(null),
                    target -> target.setErrmsgo(null),
                    target -> target.setFkeyso(null),
                    target -> target.setNextProgram(null),
                    target -> target.setNextMap(null),
                    target -> target.setCardScreenState(null),
                    target -> target.setNavigationContext(null),
                    target -> target.get(null),
                    target -> target.set(null, "x"),
                    target -> target.set(ScreenField.TRNNAME, null),
                    target -> target.attributes(null),
                    target -> target.applyDateHeader(null));
            for (Consumer<CardSelectResponse> store : nullStores) {
                assertThrows(NullPointerException.class, () -> store.accept(response));
            }
        }
    }

    @Nested
    @DisplayName("Populate operations - the named MOVEs of 1100-SCREEN-INIT and 2000-PROCESS-INPUTS")
    class PopulateOperations {
        @Test
        @DisplayName("applyScreenTitles reproduces COCRDSLC:432-433 byte for byte from COTTL01Y")
        void applyScreenTitles() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyScreenTitles();
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTitle01o()).contains("AWS Mainframe Modernization");
            assertThat(response.getTitle02o()).contains("CardDemo");
        }

        @Test
        @DisplayName("applyScreenIdentity reproduces COCRDSLC:434-435")
        void applyScreenIdentity() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyScreenIdentity();
            assertThat(response.getTrnnameo()).isEqualTo("CCDL");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDSLC");
        }

        @Test
        @DisplayName("applyDateHeader reproduces COCRDSLC:443 and :449 from a fixed instant")
        void applyDateHeader() {
            FixedWidthCodec codec = picXCodec();
            DateHeader header = DateHeader.of(codec, LocalDateTime.of(2022, 7, 19, 23, 15, 58));

            CardSelectResponse response = new CardSelectResponse();
            response.applyDateHeader(header);

            assertThat(response.getCurdateo()).isEqualTo(header.wsCurdateMmDdYy())
                    .isEqualTo("07/19/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo(header.wsCurtimeHhMmSs())
                    .isEqualTo("23:15:58").hasSize(8);
        }

        @Test
        @DisplayName("FKEYSO is never populated implicitly, because COCRDSLC never writes it")
        void fkeysIsNotPopulatedImplicitly() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyScreenTitles();
            response.applyScreenIdentity();
            assertThat(response.getFkeyso()).isEqualTo(lowValues(75));

            response.setFkeyso(CardSelectResponse.BMS_INITIAL_FKEYS);
            assertThat(response.getFkeyso()).isEqualTo(CardSelectResponse.BMS_INITIAL_FKEYS);
        }
    }

    @Nested
    @DisplayName("Titles X(40), messages X(50), and an eight-byte date header from a fixed Clock")
    class TitlesMessagesAndDateHeader {
        @Test
        @DisplayName("TITLE01O and TITLE02O are 40 wide and the COTTL01Y literals fit exactly")
        void titlesFitTheirItemsExactly() {
            assertThat(ScreenTitles.TITLE_LENGTH).isEqualTo(40);
            assertThat(CardSelectResponse.TITLE01O_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(CardSelectResponse.TITLE02O_LENGTH).isEqualTo(ScreenTitles.TITLE_LENGTH);
            assertThat(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(ScreenTitles.CCDA_THANK_YOU).hasSize(40);

            FixedWidthCodec codec = picXCodec();
            CardSelectResponse response = new CardSelectResponse();
            response.applyScreenTitles();

            assertThat(response.getTitle01o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE01)
                    .isEqualTo(codec.movePicX(ScreenTitles.CCDA_TITLE01,
                            CardSelectResponse.TITLE01O_LENGTH))
                    .hasSize(40);
            assertThat(response.getTitle02o())
                    .isEqualTo(ScreenTitles.CCDA_TITLE02)
                    .isEqualTo(codec.movePicX(ScreenTitles.CCDA_TITLE02,
                            CardSelectResponse.TITLE02O_LENGTH))
                    .hasSize(40);
        }

        @Test
        @DisplayName("an X(50) CSMSG01Y message does not belong in a 40-byte title item")
        void fiftyByteMessagesAreNotTitles() {
            assertThat(SystemMessages.MESSAGE_LENGTH).isEqualTo(50);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(50);
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);

            assertThat(SystemMessages.MESSAGE_LENGTH - ScreenTitles.TITLE_LENGTH)
                    .isEqualTo(CardSelectResponse.INFOMSGO_STANDARD_MESSAGE_TRUNCATION)
                    .isEqualTo(10);
            assertThat(CardSelectResponse.ERRMSGO_LENGTH - SystemMessages.MESSAGE_LENGTH)
                    .isEqualTo(CardSelectResponse.ERRMSGO_STANDARD_MESSAGE_PADDING)
                    .isEqualTo(30);

            assertThat(ScreenTitles.CCDA_THANK_YOU)
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU)
                    .hasSize(ScreenTitles.TITLE_LENGTH);
            assertThat(SystemMessages.CCDA_MSG_THANK_YOU).hasSize(SystemMessages.MESSAGE_LENGTH);

            FixedWidthCodec codec = picXCodec();
            CardSelectResponse response = new CardSelectResponse();
            response.setInfomsgo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getInfomsgo())
                    .hasSize(40)
                    .isEqualTo(codec.movePicX(SystemMessages.CCDA_MSG_THANK_YOU,
                            CardSelectResponse.INFOMSGO_LENGTH))
                    .isNotEqualTo(SystemMessages.CCDA_MSG_THANK_YOU);
        }

        @Test
        @DisplayName("CURDATEO and CURTIMEO are 8 each and take mm/dd/yy and hh:mm:ss from a fixed Clock")
        void dateHeaderComesFromAFixedClock() {
            Clock fixedClock = Clock.fixed(
                    Instant.parse("2022-07-19T23:15:58Z"), ZoneOffset.UTC);
            DateHeader header = DateHeader.from(picXCodec(), fixedClock);

            CardSelectResponse response = new CardSelectResponse();
            response.applyDateHeader(header);

            assertThat(CardSelectResponse.CURDATEO_LENGTH)
                    .isEqualTo(DateHeader.WS_CURDATE_MM_DD_YY_LENGTH).isEqualTo(8);
            assertThat(CardSelectResponse.CURTIMEO_LENGTH)
                    .isEqualTo(DateHeader.WS_CURTIME_HH_MM_SS_LENGTH).isEqualTo(8);

            assertThat(response.getCurdateo()).isEqualTo("07/19/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo("23:15:58").hasSize(8);
            assertThat(response.getCurdateo()).isEqualTo(header.wsCurdateMmDdYy());
            assertThat(response.getCurtimeo()).isEqualTo(header.wsCurtimeHhMmSs());
            assertThat(response.getCurdateo().charAt(2)).isEqualTo(DateHeader.DATE_SEPARATOR);
            assertThat(response.getCurtimeo().charAt(2)).isEqualTo(DateHeader.TIME_SEPARATOR);

            DateHeader again = DateHeader.from(picXCodec(), fixedClock);
            assertThat(again.wsCurdateMmDdYy()).isEqualTo(header.wsCurdateMmDdYy());
            assertThat(again.wsCurtimeHhMmSs()).isEqualTo(header.wsCurtimeHhMmSs());
        }

        @Test
        @DisplayName("FKEYSO is 75 wide and the BMS legend keeps its internal double space")
        void fkeysLegendIsPaddedAndUnaltered() {
            String literal = "ENTER=Search Cards  F3=Exit";
            assertThat(literal).hasSize(27);
            assertThat(literal).contains("Cards  F3").doesNotContain("Cards F3");
            assertThat(literal.indexOf("  ")).isEqualTo("ENTER=Search Cards".length());

            assertThat(CardSelectResponse.FKEYSO_LENGTH).isEqualTo(75);
            assertThat(CardSelectResponse.BMS_INITIAL_FKEYS)
                    .hasSize(75)
                    .startsWith(literal)
                    .isEqualTo(picXCodec().movePicX(literal, CardSelectResponse.FKEYSO_LENGTH))
                    .isEqualTo(literal + " ".repeat(75 - literal.length()));

            assertThat(CardSelectResponse.BMS_INITIAL_FKEYS.substring(literal.length()))
                    .isEqualTo(" ".repeat(48));

            CardSelectResponse response = new CardSelectResponse();
            response.setFkeyso(literal);
            assertThat(response.getFkeyso())
                    .isEqualTo(CardSelectResponse.BMS_INITIAL_FKEYS)
                    .contains("Cards  F3")
                    .hasSize(75);
        }
    }

    @Nested
    @DisplayName("Navigation - XCTL becomes response fields, and the tokens stay opaque")
    class Navigation {
        @Test
        @DisplayName("applyThisScreenAsNextTarget reproduces COCRDSLC:588-590")
        void thisScreenAsNextTarget() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyThisScreenAsNextTarget();
            assertThat(response.getNextProgram()).isEqualTo("COCRDSLC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDSL");
            assertThat(response.getNextMap()).isEqualTo("CCRDSLA");
        }

        @Test
        @DisplayName("the LIT-CCLISTMAP defect of COCRDSLC:177-178 survives: 'CCRDSLA', not 'CCRDLIA'")
        void preservesTheCardListMapDefect() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyNextTarget("COCRDLIC", "COCRDLI", "CCRDSLA");
            assertThat(response.getNextProgram()).isEqualTo("COCRDLIC");
            assertThat(response.getNextMapset()).isEqualTo("COCRDLI");
            assertThat(response.getNextMap()).isEqualTo("CCRDSLA").isNotEqualTo("CCRDLIA");
        }

        @Test
        @DisplayName("nextProgram is PIC X(8) and takes COCRDSLC, COCRDLIC and COMEN01C unaltered")
        void programTokenIsEightWide() {
            CardSelectResponse response = new CardSelectResponse();
            assertThat(CardSelectResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);

            for (String program : List.of("COCRDSLC", "COCRDLIC", "COMEN01C")) {
                response.setNextProgram(program);
                assertThat(response.getNextProgram()).as("%s survives the X(8) store", program)
                        .isEqualTo(program)
                        .hasSize(8);
            }
        }

        @Test
        @DisplayName("mapset and map are PIC X(7): 7 characters are not widened to 8, and a "
                + "PIC X(8) program is not truncated to 7")
        void mapsetAndMapAreSevenWide() {
            CardSelectResponse response = new CardSelectResponse();
            assertThat(CardSelectResponse.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardSelectResponse.NEXT_MAP_LENGTH).isEqualTo(7);

            for (String[] pair : List.of(new String[] {"COCRDSL", "CCRDSLA"},
                    new String[] {"COMEN01", "COMEN1A"})) {
                response.setNextMapset(pair[0]);
                response.setNextMap(pair[1]);
                assertThat(response.getNextMapset()).isEqualTo(pair[0]).hasSize(7);
                assertThat(response.getNextMap()).isEqualTo(pair[1]).hasSize(7);
                assertThat(response.getNextMapset()).isNotEqualTo(pair[0] + " ")
                        .doesNotEndWith(" ");
            }

            response.setNextProgram("COCRDSLC");
            assertThat(response.getNextProgram()).hasSize(8).isEqualTo("COCRDSLC")
                    .isNotEqualTo("COCRDSL");
            assertThat(response.getNextProgram()).hasSize(CardSelectResponse.NEXT_PROGRAM_LENGTH);
            assertThat(response.getNextMapset()).hasSize(CardSelectResponse.NEXT_MAPSET_LENGTH);
            assertThat(response.getNextProgram().length())
                    .isGreaterThan(response.getNextMapset().length());
        }

        @Test
        @DisplayName("both carriers ride in the payload, replacing XCTL PROGRAM(CDEMO-TO-PROGRAM)")
        void bothCarriersRideInThePayload() {
            CardSelectResponse response = new CardSelectResponse();
            response.setNavigationContext(NavigationContext.empty()
                    .withFromProgram("COCRDSLC")
                    .withToProgram("COMEN01C"));
            response.getCardScreenState().setCcardNextProg("COMEN01C");
            response.applyNextTarget("COMEN01C", "COMEN01", "COMEN1A");

            assertThat(response.getCardScreenState()).isNotNull();
            assertThat(response.getNavigationContext()).isNotNull();
            assertThat(response.getNavigationContext().toProgram()).isEqualTo("COMEN01C");
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(response.getNextMapset()).isEqualTo("COMEN01");
            assertThat(response.getNextMap()).isEqualTo("COMEN1A");
            assertThat(response.getCardScreenState().getCcardNextProg()).isEqualTo("COMEN01C");
        }

        @Test
        @DisplayName("two independently built payloads share nothing - no static holder, no ambient state")
        void payloadsAreFullyIndependent() {
            CardSelectResponse first = new CardSelectResponse();
            CardSelectResponse second = new CardSelectResponse();

            first.setCardsido("1234567890123456");
            first.applyThisScreenAsNextTarget();
            first.attributes(ScreenField.CARDSID).setColour(BmsAttributes.DFHRED);
            first.getCardScreenState().setCcAcctId("00000000011");
            first.setNavigationContext(NavigationContext.empty().withUserId("ADMIN001"));

            assertThat(second.getCardsido()).isEqualTo(lowValues(16));
            assertThat(second.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(second.getNextMapset()).isEqualTo(" ".repeat(7));
            assertThat(second.getNextMap()).isEqualTo(" ".repeat(7));
            assertThat(second.attributes(ScreenField.CARDSID).isDefaultColour()).isTrue();
            assertThat(second.getCardScreenState().getCcAcctId()).isEqualTo(" ".repeat(11));
            assertThat(second.getNavigationContext()).isEqualTo(NavigationContext.empty());
            assertThat(second.attributes(ScreenField.CARDSID))
                    .isNotSameAs(first.attributes(ScreenField.CARDSID));
            assertThat(second.getCardScreenState()).isNotSameAs(first.getCardScreenState());
            assertThat(second).isNotEqualTo(first);

            second.setCardsido("6543210987654321");
            assertThat(first.getCardsido()).isEqualTo("1234567890123456");
        }

        @Test
        @DisplayName("no whitelist, no case normalisation and no trimming is applied to the triple")
        void tokensAreOpaque() {
            CardSelectResponse response = new CardSelectResponse();

            response.setNextProgram("cocrdlic");
            assertThat(response.getNextProgram()).isEqualTo("cocrdlic");

            response.setNextProgram("NOTAPROG");
            assertThat(response.getNextProgram()).isEqualTo("NOTAPROG");

            response.setNextMap(" ZZZZZ ");
            assertThat(response.getNextMap()).isEqualTo(" ZZZZZ ").hasSize(7);

            response.setNextMapset("  x    ");
            assertThat(response.getNextMapset()).isEqualTo("  x    ").hasSize(7);
        }

        @Test
        @DisplayName("the conversation carriers round-trip, and no server-side state is used")
        void carriersRoundTrip() {
            CardSelectResponse response = new CardSelectResponse();

            CardScreenState state = new CardScreenState();
            state.setCcAcctId("00000000011");
            state.setCcardNextProg("COCRDLIC");
            response.setCardScreenState(state);
            assertThat(response.getCardScreenState()).isSameAs(state);
            assertThat(response.getCardScreenState().getCcardNextProg()).isEqualTo("COCRDLIC");

            NavigationContext context = NavigationContext.empty()
                    .withFromProgram("COCRDSLC")
                    .withToProgram("COCRDLIC")
                    .withPgmReenter();
            response.setNavigationContext(context);
            assertThat(response.getNavigationContext()).isSameAs(context);
            assertThat(response.getNavigationContext().isReenter()).isTrue();
            assertThat(response.getNavigationContext().pgmContext())
                    .isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
        }
    }

    @Nested
    @DisplayName("CSSETATY - DFHRED into xxxC, '*' into xxxO, and only when CDEMO-PGM-REENTER holds")
    class ErrorHighlight {
        @ParameterizedTest(name = "{0} + {1} -> colour={2}, asterisk={3}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardSelectResponseTest"
                + "#highlightTruthTable")
        @DisplayName("all six cells of state x context, and exactly one of them writes the asterisk")
        void theWholeTruthTable(FieldValidationState state,
                boolean reenter,
                boolean expectColour,
                boolean expectAsterisk) {
            CardSelectResponse response = new CardSelectResponse();
            response.setAcctsido("00000000011");
            String before = response.getAcctsido();

            FieldHighlight decision = response.applyHighlight(ScreenField.ACCTSID, state, reenter);

            assertThat(decision.colourItemAssigned())
                    .as("MOVE DFHRED TO ACCTSIDC for %s / reenter=%s", state, reenter)
                    .isEqualTo(expectColour);
            assertThat(decision.outputItemAssigned())
                    .as("MOVE '*' TO ACCTSIDO for %s / reenter=%s", state, reenter)
                    .isEqualTo(expectAsterisk);
            assertThat(decision.untouched()).isEqualTo(!expectColour && !expectAsterisk);

            FieldAttributes quad = response.attributes(ScreenField.ACCTSID);
            if (expectColour) {
                assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHRED);
                assertThat(quad.isRedHighlighted()).isTrue();
            } else {
                assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHDFCOL);
                assertThat(quad.isRedHighlighted()).isFalse();
                assertThat(quad.isDefaultColour()).isTrue();
            }

            if (expectAsterisk) {
                assertThat(response.getAcctsido())
                        .isEqualTo(FieldAttributeSetter.ASTERISK
                                + " ".repeat(CardSelectResponse.ACCTSIDO_LENGTH - 1));
                assertThat(decision.outputItemValue()).isEqualTo(FieldAttributeSetter.ASTERISK);
            } else {
                assertThat(response.getAcctsido()).isEqualTo(before);
            }

            assertThat(response.getAcctsido()).hasSize(CardSelectResponse.ACCTSIDO_LENGTH);
            assertThat(ScreenField.ACCTSID.length()).isEqualTo(11);
            assertThat(ScreenField.ACCTSID.dataOffset()).isEqualTo(169);
            assertThat(CardSelectResponse.groupGeometry()).hasSize(91);
            for (ScreenField field : ScreenField.values()) {
                assertThat(response.get(field)).as("%s width", field.cobolName())
                        .hasSize(field.length());
            }
        }

        @Test
        @DisplayName("the asterisk is written in exactly one of the six cells, and only into xxxO")
        void theAsteriskAppearsOnceInTheTable() {
            int cellsWritingTheAsterisk = 0;
            for (Arguments cell : highlightTruthTable()) {
                Object[] row = cell.get();
                FieldValidationState state = (FieldValidationState) row[0];
                boolean reenter = (Boolean) row[1];

                CardSelectResponse response = new CardSelectResponse();
                FieldHighlight decision =
                        response.applyHighlight(ScreenField.CARDSID, state, reenter);
                if (decision.outputItemAssigned()) {
                    cellsWritingTheAsterisk++;
                    assertThat(state).isEqualTo(FieldValidationState.BLANK);
                    assertThat(reenter).isTrue();
                    assertThat(response.getCardsido()).startsWith(FieldAttributeSetter.ASTERISK);
                    assertThat(response.attributes(ScreenField.CARDSID).getColour())
                            .isEqualTo(BmsAttributes.DFHRED)
                            .isNotEqualTo((byte) FieldAttributeSetter.ASTERISK.charAt(0));
                }
            }
            assertThat(cellsWritingTheAsterisk)
                    .as("'*' on BLANK only, and only on re-entry")
                    .isOne();
        }

        @Test
        @DisplayName("ERRMSGO is 80 wide and a shorter message is right-space-padded to 80")
        void errorLineIsEightyWide() {
            FixedWidthCodec codec = picXCodec();
            CardSelectResponse response = new CardSelectResponse();
            String message = "Account Filter is not a valid number";

            response.setErrmsgo(message);

            assertThat(CardSelectResponse.ERRMSGO_LENGTH).isEqualTo(80);
            assertThat(ScreenField.ERRMSG.length()).isEqualTo(80);
            assertThat(response.getErrmsgo())
                    .hasSize(80)
                    .isEqualTo(message + " ".repeat(80 - message.length()))
                    .isEqualTo(codec.movePicX(message, CardSelectResponse.ERRMSGO_LENGTH));

            assertThat(response.attributes(ScreenField.ERRMSG).isDefaultColour()).isTrue();
        }

        @Test
        @DisplayName("on FIRST ENTRY a failing field is neither reddened nor marked")
        void suppressedOnFirstEntry() {
            CardSelectResponse response = new CardSelectResponse();
            String before = response.getAcctsido();

            FieldHighlight decision =
                    response.applyHighlight(ScreenField.ACCTSID, FieldValidationState.BLANK, false);

            assertThat(decision.untouched()).isTrue();
            assertThat(decision.colourItemAssigned()).isFalse();
            assertThat(decision.outputItemAssigned()).isFalse();
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            assertThat(response.attributes(ScreenField.ACCTSID).isDefaultColour()).isTrue();
            assertThat(response.getAcctsido()).isEqualTo(before);
        }

        @Test
        @DisplayName("on RE-ENTRY a BLANK field gets DFHRED in ACCTSIDC and '*' in ACCTSIDO")
        void blankOnReenterIsReddenedAndMarked() {
            CardSelectResponse response = new CardSelectResponse();

            FieldHighlight decision =
                    response.applyHighlight(ScreenField.ACCTSID, FieldValidationState.BLANK, true);

            assertThat(decision.colourItemAssigned()).isTrue();
            assertThat(decision.outputItemAssigned()).isTrue();
            assertThat(decision.colourItemName()).isEqualTo("ACCTSIDC");
            assertThat(decision.outputItemName()).isEqualTo("ACCTSIDO");
            assertThat(decision.outputMapGroupName()).isEqualTo("CCRDSLAO");

            assertThat(response.attributes(ScreenField.ACCTSID).getColour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();
            assertThat(response.attributes(ScreenField.ACCTSID).isDefaultColour()).isFalse();
            assertThat(response.getAcctsido()).isEqualTo("*" + " ".repeat(10)).hasSize(11);
        }

        @Test
        @DisplayName("on RE-ENTRY a NOT-OK field is reddened but NOT marked - the '*' move is nested")
        void notOkOnReenterIsReddenedOnly() {
            CardSelectResponse response = new CardSelectResponse();
            response.setCardsido("1234567890123456");

            FieldHighlight decision =
                    response.applyHighlight(ScreenField.CARDSID, FieldValidationState.NOT_OK, true);

            assertThat(decision.colourItemAssigned()).isTrue();
            assertThat(decision.outputItemAssigned()).isFalse();
            assertThat(response.attributes(ScreenField.CARDSID).isRedHighlighted()).isTrue();
            assertThat(response.getCardsido()).isEqualTo("1234567890123456");
        }

        @Test
        @DisplayName("an OK field is never repainted, in either context")
        void okFieldIsNeverRepainted() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyHighlight(ScreenField.ACCTSID, FieldValidationState.OK, true);
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
            response.applyHighlight(ScreenField.ACCTSID, FieldValidationState.OK, false);
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ScreenField.class)
        @DisplayName("the rule is addressable for every one of the 15 fields")
        void addressableForEveryField(ScreenField field) {
            CardSelectResponse response = new CardSelectResponse();
            response.applyHighlight(field, FieldValidationState.BLANK, true);
            assertThat(response.attributes(field).isRedHighlighted()).isTrue();
            assertThat(response.get(field)).startsWith("*").hasSize(field.length());
        }

        @Test
        @DisplayName("a pre-resolved decision applies, and cannot be carried across fields")
        void preResolvedDecision() {
            CardSelectResponse response = new CardSelectResponse();

            FieldHighlight forAcctsid = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    "ACCTSID", CardSelectResponse.MAP_NAME);
            response.applyHighlight(ScreenField.ACCTSID, forAcctsid);
            assertThat(response.attributes(ScreenField.ACCTSID).isRedHighlighted()).isTrue();

            assertThatThrownBy(() -> response.applyHighlight(ScreenField.CARDSID, forAcctsid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ACCTSID")
                    .hasMessageContaining("CARDSID");

            FieldHighlight anonymous = FieldAttributeSetter.resolveFromFlags(true, false, true);
            response.applyHighlight(ScreenField.CARDSID, anonymous);
            assertThat(response.attributes(ScreenField.CARDSID).isRedHighlighted()).isTrue();

            FieldHighlight untouched = FieldHighlight.none("ERRMSG", CardSelectResponse.MAP_NAME);
            response.applyHighlight(ScreenField.ERRMSG, untouched);
            assertThat(response.attributes(ScreenField.ERRMSG).isDefaultColour()).isTrue();

            assertThrows(NullPointerException.class,
                    () -> response.applyHighlight(ScreenField.ACCTSID, (FieldHighlight) null));
            assertThrows(NullPointerException.class,
                    () -> response.applyHighlight((ScreenField) null, anonymous));
            assertThrows(NullPointerException.class, () -> response.applyHighlight(
                    ScreenField.ACCTSID, (FieldValidationState) null, true));
            assertThrows(NullPointerException.class,
                    () -> response.applyHighlight(null, FieldValidationState.BLANK, true));
        }

        @Test
        @DisplayName("the three colour items COCRDSLC writes at L529-L556 are all reachable")
        void programWrittenColourItems() {
            CardSelectResponse response = new CardSelectResponse();

            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHDFCOL);
            response.attributes(ScreenField.CARDSID).setColour(BmsAttributes.DFHDFCOL);
            assertThat(response.attributes(ScreenField.ACCTSID).isDefaultColour()).isTrue();
            assertThat(response.attributes(ScreenField.CARDSID).isDefaultColour()).isTrue();

            response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR);
            assertThat(response.attributes(ScreenField.INFOMSG).getColour())
                    .isEqualTo(BmsAttributes.DFHBMDAR);
            assertThat(response.attributes(ScreenField.INFOMSG).isDefaultColour()).isFalse();

            response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHNEUTR);
            assertThat(response.attributes(ScreenField.INFOMSG).describe()).contains("DFHNEUTR");
        }
    }

    @Nested
    @DisplayName("FieldAttributes - the C/P/H/V quad of DSATTS=(COLOR,HILIGHT,PS,VALIDN)")
    class AttributeQuad {
        @Test
        @DisplayName("all four items round-trip independently")
        void roundTrip() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            quad.setPs((byte) 0x11);
            quad.setHilight(BmsAttributes.DFHUNDLN);
            quad.setValidn((byte) 0x22);

            assertThat(quad.getColour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.getPs()).isEqualTo((byte) 0x11);
            assertThat(quad.getHilight()).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(quad.getValidn()).isEqualTo((byte) 0x22);
            assertThat(quad.isRedHighlighted()).isTrue();
            assertThat(quad.isDefaultColour()).isFalse();
        }

        @Test
        @DisplayName("describe uses the DFHBMSCA mnemonics, falling back to X'hh'")
        void describeUsesMnemonics() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            quad.setPs((byte) 0x11);
            quad.setHilight(BmsAttributes.DFHUNDLN);
            quad.setValidn((byte) 0x22);
            assertThat(quad.describe()).contains("DFHRED", "DFHUNDLN", "X'11'", "X'22'");
            assertThat(quad.toString()).startsWith("FieldAttributes[").contains("DFHRED");
        }

        @Test
        @DisplayName("resetToLowValues restores 0x00 in all four items")
        void resetToLowValues() {
            FieldAttributes quad = new FieldAttributes();
            quad.setColour(BmsAttributes.DFHRED);
            quad.setPs((byte) 1);
            quad.setHilight((byte) 2);
            quad.setValidn((byte) 3);

            quad.resetToLowValues();

            assertThat(quad.getColour()).isZero();
            assertThat(quad.getPs()).isZero();
            assertThat(quad.getHilight()).isZero();
            assertThat(quad.getValidn()).isZero();
            assertThat(quad.isDefaultColour()).isTrue();
            assertThat(quad.isRedHighlighted()).isFalse();
        }

        @Test
        @DisplayName("equality distinguishes each of the four items on its own")
        void equalityIsPerItem() {
            FieldAttributes base = new FieldAttributes();
            assertThat(base).isEqualTo(base).isEqualTo(new FieldAttributes())
                    .hasSameHashCodeAs(new FieldAttributes());
            assertThat(base).isNotEqualTo("not a quad").isNotEqualTo(null);

            FieldAttributes colourDiffers = new FieldAttributes();
            colourDiffers.setColour(BmsAttributes.DFHRED);
            assertThat(colourDiffers).isNotEqualTo(base);

            FieldAttributes psDiffers = new FieldAttributes();
            psDiffers.setPs((byte) 1);
            assertThat(psDiffers).isNotEqualTo(base);

            FieldAttributes hilightDiffers = new FieldAttributes();
            hilightDiffers.setHilight((byte) 1);
            assertThat(hilightDiffers).isNotEqualTo(base);

            FieldAttributes validnDiffers = new FieldAttributes();
            validnDiffers.setValidn((byte) 1);
            assertThat(validnDiffers).isNotEqualTo(base);
        }

        @Test
        @DisplayName("the copy constructor produces an independent, equal quad")
        void copyConstructor() {
            FieldAttributes source = new FieldAttributes();
            source.setColour(BmsAttributes.DFHRED);

            FieldAttributes copy = new FieldAttributes(source);
            assertThat(copy).isEqualTo(source).hasSameHashCodeAs(source);

            source.resetToLowValues();
            assertThat(copy.isRedHighlighted()).isTrue();
            assertThat(copy).isNotEqualTo(source);

            assertThrows(NullPointerException.class, () -> new FieldAttributes(null));
        }
    }

    @Nested
    @DisplayName("The quad is metadata, never payload - DSATTS declares four, so there are four")
    class AttributeQuadIsMetadataNotPayload {
        @Test
        @DisplayName("four attribute items per field, one for each name in DSATTS=(COLOR,HILIGHT,PS,VALIDN)")
        void fourItemsBecauseDsattsNamesFour() {
            List<String> dsatts = List.of("COLOR", "HILIGHT", "PS", "VALIDN");
            assertThat(dsatts).hasSize(CardSelectResponse.ATTRIBUTE_ITEMS_PER_FIELD).hasSize(4);
            assertThat(CardSelectResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);

            for (ScreenField field : ScreenField.values()) {
                String label = field.dfhmdfLabel();
                assertThat(List.of(field.colourItemName(), field.psItemName(),
                                field.hilightItemName(), field.validnItemName()))
                        .as("the quad of %s", label)
                        .containsExactly(label + "C", label + "P", label + "H", label + "V")
                        .hasSize(dsatts.size());
            }

            assertThat(new CardSelectResponse().attributeItems())
                    .hasSize(CardSelectResponse.FIELD_COUNT
                            * CardSelectResponse.ATTRIBUTE_ITEMS_PER_FIELD)
                    .hasSize(60);
        }

        @Test
        @DisplayName("the JSON carries all 15 display members and not one of the 60 attribute items")
        void jsonCarriesDataAndNotMetadata() throws Exception {
            CardSelectResponse response = populated();
            response.applyScreenTitles();
            for (ScreenField field : ScreenField.values()) {
                FieldAttributes quad = response.attributes(field);
                quad.setColour(BmsAttributes.DFHRED);
                quad.setPs(BmsAttributes.DFHDFCOL);
                quad.setHilight(BmsAttributes.DFHUNDLN);
                quad.setValidn(BmsAttributes.DFHDFHI);
            }

            JsonNode json = new ObjectMapper().valueToTree(response);

            for (FieldOracle oracle : fieldOracle()) {
                String member = withoutDirectionSuffix(oracle.cobolName());
                assertThat(json.has(member)).as("JSON member for %s", oracle.cobolName()).isTrue();
                assertThat(json.get(member).asText()).as("%s image", oracle.cobolName())
                        .hasSize(oracle.width());
            }

            List<String> forbidden = new ArrayList<>();
            for (ScreenField field : ScreenField.values()) {
                for (String item : List.of(field.colourItemName(), field.psItemName(),
                        field.hilightItemName(), field.validnItemName())) {
                    forbidden.add(item);
                    forbidden.add(item.toLowerCase(Locale.ROOT));
                }
            }
            assertThat(forbidden).hasSize(120);
            for (String item : forbidden) {
                assertThat(json.has(item)).as("%s must not be a JSON member", item).isFalse();
            }
        }

        @Test
        @DisplayName("the FILLER spans are absent from the JSON yet still counted in the 504-byte image")
        void fillerIsNotAMemberButIsStillStorage() throws Exception {
            JsonNode json = new ObjectMapper().valueToTree(populated());

            for (String spelling : List.of("FILLER", "filler", "tioapfx", "TIOAPFX")) {
                assertThat(json.has(spelling)).as("%s must not be a JSON member", spelling).isFalse();
            }

            int fillerBytes = CardSelectResponse.TIOAPFX_LENGTH
                    + (CardSelectResponse.FIELD_COUNT * CardSelectResponse.FILLER_LENGTH);
            int attributeBytes = CardSelectResponse.FIELD_COUNT
                    * CardSelectResponse.ATTRIBUTE_ITEMS_PER_FIELD
                    * CardSelectResponse.ATTRIBUTE_ITEM_LENGTH;
            assertThat(fillerBytes).isEqualTo(57);
            assertThat(attributeBytes).isEqualTo(60);
            assertThat(fillerBytes + attributeBytes + CardSelectResponse.DATA_LENGTH)
                    .as("57 filler + 60 attribute + 387 data")
                    .isEqualTo(CardSelectResponse.GROUP_LENGTH)
                    .isEqualTo(504);
        }

        @Test
        @DisplayName("the quads reach the client as screenMetadata, so a red field can still be repainted")
        void quadsTravelAsMetadata() {
            CardSelectResponse response = new CardSelectResponse();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);

            assertThat(response.screenMetadata().fields()).hasSize(15)
                    .containsKeys("ACCTSID", "CARDSID", "ERRMSG");
            assertThat(response.screenMetadata().fields().get("ACCTSID").colour())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHRED));
            assertThat(response.screenMetadata().messageColour())
                    .isEqualTo(BmsAttributes.unsigned(
                            response.attributes(ScreenField.ERRMSG).getColour()));
        }
    }

    @Nested
    @DisplayName("Images - the fingerprints a parity differ compares field by field")
    class Images {
        @Test
        @DisplayName("fieldImages is the 15 xxxO items, in copybook order, untrimmed and unmodifiable")
        void fieldImages() {
            CardSelectResponse response = new CardSelectResponse();
            response.applyScreenIdentity();

            Map<String, String> images = response.fieldImages();
            assertThat(images).hasSize(15);
            assertThat(new ArrayList<>(images.keySet())).containsExactly("TRNNAMEO", "TITLE01O",
                    "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO", "ACCTSIDO", "CARDSIDO",
                    "CRDNAMEO", "CRDSTCDO", "EXPMONO", "EXPYEARO", "INFOMSGO", "ERRMSGO", "FKEYSO");
            assertThat(images.get("TRNNAMEO")).isEqualTo("CCDL");
            assertThat(images.get("PGMNAMEO")).isEqualTo("COCRDSLC");
            for (ScreenField field : ScreenField.values()) {
                assertThat(images.get(field.cobolName())).as("%s", field.cobolName())
                        .hasSize(field.length());
            }
            assertThatThrownBy(() -> images.put("X", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("attributeItems is the 60 C/P/H/V items in offset order and unmodifiable")
        void attributeItems() {
            CardSelectResponse response = new CardSelectResponse();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);

            Map<String, Byte> items = response.attributeItems();
            assertThat(items).hasSize(60);
            List<String> names = new ArrayList<>(items.keySet());
            assertThat(names.subList(0, 4))
                    .containsExactly("TRNNAMEC", "TRNNAMEP", "TRNNAMEH", "TRNNAMEV");
            assertThat(names.subList(56, 60))
                    .containsExactly("FKEYSC", "FKEYSP", "FKEYSH", "FKEYSV");
            assertThat(items.get("ACCTSIDC")).isEqualTo(BmsAttributes.DFHRED);
            assertThat(items.get("CARDSIDC")).isEqualTo((byte) 0x00);
            assertThatThrownBy(() -> items.put("X", (byte) 1))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("attributeQuads is a live, unmodifiable view of exactly 15 quads")
        void attributeQuads() {
            CardSelectResponse response = new CardSelectResponse();
            Map<ScreenField, FieldAttributes> quads = response.attributeQuads();
            assertThat(quads).hasSize(15);

            response.attributes(ScreenField.ERRMSG).setColour(BmsAttributes.DFHRED);
            assertThat(quads.get(ScreenField.ERRMSG).isRedHighlighted()).isTrue();

            assertThatThrownBy(() -> quads.put(ScreenField.ERRMSG, new FieldAttributes()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("describe names the group, the map and every field's provenance")
        void describe() {
            CardSelectResponse response = populated();
            assertThat(response.describe())
                    .contains("CCRDSLAO (504 bytes, 15 named DFHMDF fields of 31, map CCRDSLA")
                    .contains("program COCRDSLC", "transaction CCDL")
                    .contains("ACCTSID: ACCTSIDO PIC X(11) at COCRDSL.CPY:152")
                    .contains("FKEYS: FKEYSO PIC X(75) at COCRDSL.CPY:200")
                    .contains("next: program=[COCRDSLC]");
        }

        @Test
        @DisplayName("ACCTSIDO, CARDSIDO and CRDNAMEO travel in the clear; only the diagnostics redact")
        void identifiersTravelInTheClearAndOnlyDiagnosticsRedact() throws Exception {
            String accountId = "00000000011";
            String cardNumber = "1234567890123456";
            String embossedName = "JOHN DOE";
            CardSelectResponse response = populated();

            assertThat(response.getAcctsido()).isEqualTo(accountId);
            assertThat(response.getCardsido()).isEqualTo(cardNumber).hasSize(16)
                    .containsOnlyDigits();
            assertThat(response.getCrdnameo()).startsWith(embossedName)
                    .hasSize(CardSelectResponse.CRDNAMEO_LENGTH);

            assertThat(response.get(ScreenField.ACCTSID)).isEqualTo(accountId);
            assertThat(response.get(ScreenField.CARDSID)).isEqualTo(cardNumber);

            assertThat(response.fieldImages())
                    .containsEntry("ACCTSIDO", accountId)
                    .containsEntry("CARDSIDO", cardNumber);

            JsonNode json = new ObjectMapper().valueToTree(response);
            assertThat(json.get("acctsid").asText()).isEqualTo(accountId);
            assertThat(json.get("cardsid").asText()).isEqualTo(cardNumber);
            assertThat(json.get("crdname").asText()).startsWith(embossedName);

            assertThat(response.toString())
                    .startsWith("CardSelectResponse[map=CCRDSLA")
                    .contains("ACCTSIDO=*******0011")
                    .contains("CARDSIDO=************3456")
                    .doesNotContain(accountId, cardNumber);
            assertThat(response.describe()).doesNotContain(accountId, cardNumber, embossedName);

            assertThat(response.toString()).doesNotContain(
                    Integer.toHexString(cardNumber.hashCode()));
            assertThat(response.getCardsido()).isEqualTo(cardNumber);
        }
    }

    static List<Arguments> singleMemberMutations() {
        List<Arguments> mutations = new ArrayList<>();
        record Mutation(String name, Consumer<CardSelectResponse> apply) { }
        List<Mutation> all = List.of(
                new Mutation("TRNNAMEO", target -> target.setTrnnameo("XXXX")),
                new Mutation("TITLE01O", target -> target.setTitle01o("changed")),
                new Mutation("CURDATEO", target -> target.setCurdateo("99/99/99")),
                new Mutation("PGMNAMEO", target -> target.setPgmnameo("XXXXXXXX")),
                new Mutation("TITLE02O", target -> target.setTitle02o("changed")),
                new Mutation("CURTIMEO", target -> target.setCurtimeo("99:99:99")),
                new Mutation("ACCTSIDO", target -> target.setAcctsido("99999999999")),
                new Mutation("CARDSIDO", target -> target.setCardsido("9999999999999999")),
                new Mutation("CRDNAMEO", target -> target.setCrdnameo("JANE ROE")),
                new Mutation("CRDSTCDO", target -> target.setCrdstcdo("N")),
                new Mutation("EXPMONO", target -> target.setExpmono("12")),
                new Mutation("EXPYEARO", target -> target.setExpyearo("2099")),
                new Mutation("INFOMSGO", target -> target.setInfomsgo("other info")),
                new Mutation("ERRMSGO", target -> target.setErrmsgo("other error")),
                new Mutation("FKEYSO", target -> target.setFkeyso("other keys")),
                new Mutation("attributes",
                        target -> target.attributes(ScreenField.ERRMSG)
                                .setColour(BmsAttributes.DFHRED)),
                new Mutation("cardScreenState",
                        target -> target.getCardScreenState().setCcCustId("999999999")),
                new Mutation("navigationContext",
                        target -> target.setNavigationContext(
                                NavigationContext.empty().withUserId("ADMIN001"))),
                new Mutation("nextProgram", target -> target.setNextProgram("COMEN01C")),
                new Mutation("nextMapset", target -> target.setNextMapset("COMEN01")),
                new Mutation("nextMap", target -> target.setNextMap("COMEN1A")));
        for (Mutation mutation : all) {
            mutations.add(Arguments.of(mutation.name(), mutation.apply()));
        }
        return mutations;
    }

    @Nested
    @DisplayName("Value semantics - equality over all 15 items, the 15 quads and the carriers")
    class ValueSemantics {
        @Test
        @DisplayName("two identically built payloads are equal and hash alike")
        void identicalPayloadsAreEqual() {
            assertThat(populated()).isEqualTo(populated()).hasSameHashCodeAs(populated());
            assertThat(new CardSelectResponse()).isEqualTo(new CardSelectResponse());
        }

        @Test
        @DisplayName("equality is reflexive and rejects a null and a foreign type")
        void reflexiveAndTypeChecked() {
            CardSelectResponse response = populated();
            assertThat(response).isEqualTo(response);
            assertThat(response).isNotEqualTo(null).isNotEqualTo("not a response");
        }

        @Test
        @DisplayName("a ONE-BYTE difference in any data item is enough to break equality")
        void oneByteDifferenceBreaksEquality() {
            CardSelectResponse baseline = populated();
            CardSelectResponse oneByteOff = populated();
            assertThat(oneByteOff).isEqualTo(baseline).hasSameHashCodeAs(baseline);

            oneByteOff.setCardsido("1234567890123457");

            assertThat(oneByteOff.getCardsido())
                    .hasSameSizeAs(baseline.getCardsido())
                    .isNotEqualTo(baseline.getCardsido());
            assertThat(oneByteOff).isNotEqualTo(baseline);
            assertThat(oneByteOff.fieldImages()).isNotEqualTo(baseline.fieldImages());

            CardSelectResponse spaceOff = populated();
            spaceOff.setCrdnameo(" JOHN DOE");
            assertThat(spaceOff.getCrdnameo()).hasSize(50);
            assertThat(spaceOff).isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("a difference in highlight metadata ALONE also breaks equality - the declared contract")
        void metadataOnlyDifferenceAlsoBreaksEquality() {
            CardSelectResponse baseline = populated();
            CardSelectResponse reddened = populated();
            assertThat(reddened).isEqualTo(baseline).hasSameHashCodeAs(baseline);

            reddened.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);

            assertThat(reddened.fieldImages()).isEqualTo(baseline.fieldImages());
            assertThat(reddened).isNotEqualTo(baseline);
            assertThat(reddened.attributeItems()).isNotEqualTo(baseline.attributeItems());

            for (Consumer<FieldAttributes> mutation : List.<Consumer<FieldAttributes>>of(
                    quad -> quad.setPs(BmsAttributes.DFHDFHI == 0 ? (byte) 0x01 : (byte) 0x02),
                    quad -> quad.setHilight(BmsAttributes.DFHUNDLN),
                    quad -> quad.setValidn((byte) 0x08))) {
                CardSelectResponse mutated = populated();
                mutation.accept(mutated.attributes(ScreenField.CARDSID));
                assertThat(mutated).isNotEqualTo(baseline);
                assertThat(mutated.fieldImages()).isEqualTo(baseline.fieldImages());
            }
        }

        @ParameterizedTest(name = "differing in {0} alone")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardSelectResponseTest"
                + "#singleMemberMutations")
        @DisplayName("changing any single compared member breaks equality")
        void singleMemberDifferenceBreaksEquality(String member,
                Consumer<CardSelectResponse> mutation) {
            CardSelectResponse baseline = populated();
            CardSelectResponse mutated = populated();
            assertThat(mutated).as("baseline must match before the change").isEqualTo(baseline);

            mutation.accept(mutated);

            assertThat(mutated).as("payloads differing in %s must not be equal", member)
                    .isNotEqualTo(baseline);
        }
    }

    @Nested
    @DisplayName("The JSON wire - exactly the 15 data members plus the six carriers")
    class JsonContract {
        @Test
        @DisplayName("serialisation emits 21 properties and no attribute item")
        void serialisedShape() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectResponse response = populated();
            response.applyScreenTitles();
            response.attributes(ScreenField.ACCTSID).setColour(BmsAttributes.DFHRED);

            @SuppressWarnings("unchecked")
            Map<String, Object> json =
                    mapper.readValue(mapper.writeValueAsString(response), Map.class);

            assertThat(json.keySet()).containsExactlyInAnyOrder(
                    "trnname", "title01", "curdate", "pgmname", "title02", "curtime",
                    "acctsid", "cardsid", "crdname", "crdstcd", "expmon", "expyear",
                    "infomsg", "errmsg", "fkeys",
                    "cardScreenState", "navigationContext", "nextProgram", "nextMapset", "nextMap",
                    "thisProgCommarea");
            assertThat(json).hasSize(21);
            assertThat(json.keySet()).doesNotContain("attributeQuads", "attributeItems",
                    "fieldImages", "describe", "groupGeometry", "attributes", "acctsidc",
                    "ACCTSIDC", "expday", "pageno", "screenMetadata");
        }

        @Test
        @DisplayName("the returned trailer has no absent state either")
        void theTrailerIsNeverNull() {
            CardSelectResponse response = new CardSelectResponse();

            response.setThisProgCommarea(null);

            assertThat(response.getThisProgCommarea())
                    .isEqualTo(CardSelectRequest.ThisProgCommarea.initialized());

            response.setThisProgCommarea(
                    new CardSelectRequest.ThisProgCommarea("COCRDLIC", "CCLI"));
            assertThat(response.getThisProgCommarea().caFromTranid()).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("the card number and account id travel unmasked, as the symbolic map carries them")
        void identifiersAreNotMasked() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectResponse response = populated();

            @SuppressWarnings("unchecked")
            Map<String, Object> json =
                    mapper.readValue(mapper.writeValueAsString(response), Map.class);

            assertThat(json.get("cardsid")).isEqualTo("1234567890123456");
            assertThat(json.get("acctsid")).isEqualTo("00000000011");
            assertThat(json.get("nextMapset")).isEqualTo("COCRDSL");
        }

        @Test
        @DisplayName("a payload survives a round trip through JSON")
        void roundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardSelectResponse response = populated();
            response.applyScreenTitles();

            CardSelectResponse revived = mapper.readValue(
                    mapper.writeValueAsString(response), CardSelectResponse.class);

            assertThat(revived.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(revived.getNextProgram()).isEqualTo("COCRDSLC");
            assertThat(revived.getNextMapset()).isEqualTo("COCRDSL");
            assertThat(revived.getNextMap()).isEqualTo("CCRDSLA");
            for (ScreenField field : ScreenField.values()) {
                assertThat(revived.get(field)).as("%s", field.cobolName())
                        .hasSize(field.length());
            }
        }
    }

    private static String withoutDirectionSuffix(String itemName) {
        return itemName.substring(0, itemName.length() - 1).toLowerCase(Locale.ROOT);
    }
}
