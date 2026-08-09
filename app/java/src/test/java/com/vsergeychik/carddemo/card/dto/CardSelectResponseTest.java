package com.vsergeychik.carddemo.card.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.DiagnosticText;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 * name-labelled {@code DFHMDF} inventory, {@code app/cbl/COCRDSLC.cbl} for what the program actually
 * moves into the map, and {@code app/cpy/CSSETATY.cpy} for the error-highlight rule.
 *
 * <p>The widths and offsets in {@link #EXPECTED_LAYOUT} were computed independently of the class under
 * test, directly from the copybook, so this suite is an external oracle rather than a restatement of the
 * implementation's own arithmetic.
 */
@DisplayName("CardSelectResponse - the output projection of 01 CCRDSLAO, 504 bytes, 15 of 31 DFHMDF")
final class CardSelectResponseTest {

    /**
     * The independent oracle. One row per name-labelled {@code DFHMDF}, in copybook declaration order:
     * label, {@code xxxO} item, declared width, {@code COCRDSL.CPY} line, filler offset, colour offset,
     * data offset. Offsets are zero-based within the 504-byte group.
     */
    private static final Object[][] EXPECTED_LAYOUT = {
        {"TRNNAME", "TRNNAMEO", 4, 116, 12, 15, 19},
        {"TITLE01", "TITLE01O", 40, 122, 23, 26, 30},
        {"CURDATE", "CURDATEO", 8, 128, 70, 73, 77},
        {"PGMNAME", "PGMNAMEO", 8, 134, 85, 88, 92},
        {"TITLE02", "TITLE02O", 40, 140, 100, 103, 107},
        {"CURTIME", "CURTIMEO", 8, 146, 147, 150, 154},
        {"ACCTSID", "ACCTSIDO", 11, 152, 162, 165, 169},
        {"CARDSID", "CARDSIDO", 16, 158, 180, 183, 187},
        {"CRDNAME", "CRDNAMEO", 50, 164, 203, 206, 210},
        {"CRDSTCD", "CRDSTCDO", 1, 170, 260, 263, 267},
        {"EXPMON", "EXPMONO", 2, 176, 268, 271, 275},
        {"EXPYEAR", "EXPYEARO", 4, 182, 277, 280, 284},
        {"INFOMSG", "INFOMSGO", 40, 188, 288, 291, 295},
        {"ERRMSG", "ERRMSGO", 80, 194, 335, 338, 342},
        {"FKEYS", "FKEYSO", 75, 200, 422, 425, 429},
    };

    /** The COBOL figurative constant {@code LOW-VALUES} at a given width. */
    private static String lowValues(int length) {
        return "\u0000".repeat(length);
    }

    /** A payload with every member set, so a single-member change is always detectable. */
    private static CardSelectResponse populated() {
        CardSelectResponse response = new CardSelectResponse("CCDL", "t1", "01/02/26", "COCRDSLC",
                "t2", "03:04:05", "00000000011", "1234567890123456", "JOHN DOE", "Y", "07", "2030",
                "info", "error", "keys");
        response.applyThisScreenAsNextTarget();
        return response;
    }

    // =============================================================================================
    // Geometry
    // =============================================================================================

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
            int inputPrefix = 2 + 1 + 4;
            assertThat(CardSelectResponse.FIELD_PREFIX_LENGTH).isEqualTo(inputPrefix);
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

        @Test
        @DisplayName("every constant's name, width, copybook line and offsets match the oracle")
        void matchesTheOracle() {
            ScreenField[] fields = ScreenField.values();
            assertThat(fields).hasSize(EXPECTED_LAYOUT.length).hasSize(15);

            for (int index = 0; index < fields.length; index++) {
                ScreenField field = fields[index];
                Object[] row = EXPECTED_LAYOUT[index];
                int colour = (Integer) row[5];

                assertThat(field.dfhmdfLabel()).as("label of %s", field).isEqualTo(row[0]);
                assertThat(field.cobolName()).as("item of %s", field).isEqualTo(row[1]);
                assertThat(field.length()).as("width of %s", field).isEqualTo(row[2]);
                assertThat(field.copybookLine()).as("CPY line of %s", field).isEqualTo(row[3]);
                assertThat(field.fieldOffset()).as("field offset of %s", field).isEqualTo(row[4]);
                assertThat(field.fillerOffset()).as("filler offset of %s", field).isEqualTo(row[4]);
                assertThat(field.colourOffset()).as("colour offset of %s", field).isEqualTo(colour);
                assertThat(field.psOffset()).as("ps offset of %s", field).isEqualTo(colour + 1);
                assertThat(field.hilightOffset()).as("h offset of %s", field).isEqualTo(colour + 2);
                assertThat(field.validnOffset()).as("v offset of %s", field).isEqualTo(colour + 3);
                assertThat(field.dataOffset()).as("data offset of %s", field).isEqualTo(row[6]);
            }
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
            assertThat(labels).doesNotContain("EXPDAY", "PAGENO");
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

            // A gap: drop the first per-field FILLER, so the following span starts three bytes late.
            List<SpanDescriptor> gapped = new ArrayList<>(real);
            gapped.remove(1);
            assertThatThrownBy(() -> CardSelectResponse.verifyGroupTiling(gapped))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must tile the group with no gap");

            // An overlap: repeat the TIOAPFX prefix, so the next span starts before its predecessor ends.
            List<SpanDescriptor> overlapping = new ArrayList<>(real);
            overlapping.add(1, real.get(0));
            assertThatThrownBy(() -> CardSelectResponse.verifyGroupTiling(overlapping))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no gap");

            // A correct chain that simply does not reach 504.
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

    // =============================================================================================
    // Construction and the PIC X move rule
    // =============================================================================================

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
            // 1100-SCREEN-INIT covers the map area only; CC-WORK-AREA and the commarea sit outside it.
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

            // The 88-level FOUND-CARDS-FOR-ACCOUNT literal of app/cbl/COCRDSLC.cbl:129-130, in the
            // PIC X(40) field that declares it. The padding is derived from the declared width rather
            // than typed out, so the fixture cannot drift from the constant it is meant to match.
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
            CardSelectResponse response = new CardSelectResponse();

            response.setInfomsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
            assertThat(response.getInfomsgo()).hasSize(40)
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY.substring(0, 40));

            response.setErrmsgo(SystemMessages.CCDA_MSG_THANK_YOU);
            assertThat(response.getErrmsgo()).hasSize(80)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU + " ".repeat(30));
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

    // =============================================================================================
    // The populate operations COCRDSLC performs
    // =============================================================================================

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
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
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

    // =============================================================================================
    // CSSETATY - the error highlight, gate G38
    // =============================================================================================

    @Nested
    @DisplayName("CSSETATY - DFHRED into xxxC, '*' into xxxO, and only when CDEMO-PGM-REENTER holds")
    class ErrorHighlight {

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
            // MOVE '*' TO ACCTSIDO - one asterisk, space-padded to PIC X(11).
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

            // An anonymous decision carries no identity, so it may be applied to any field.
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

    // =============================================================================================
    // Views, value semantics and the JSON contract
    // =============================================================================================

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
        @DisplayName("toString names the map and the identifying fields, unmasked")
        void toStringMasksTheIdentifiersItCarries() {
            CardSelectResponse response = populated();

            assertThat(response.toString())
                    .startsWith("CardSelectResponse[map=CCRDSLA")
                    .doesNotContain("00000000011", "1234567890123456")
                    .contains("ACCTSIDO=*******0011")
                    .contains("CARDSIDO=************3456");
        }
    }

    /**
     * Supplies one mutation per member {@link CardSelectResponse#equals(Object)} compares, so each
     * comparison in its {@code &&} chain is exercised in both its true and its false state.
     *
     * @return the named mutations, one per compared member
     */
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

            // The fifteen xxxO items, plus six carriers: CC-WORK-AREA, CARDDEMO-COMMAREA, the
            // next-screen triple, and the twelve-byte WS-THIS-PROGCOMMAREA that COMMON-RETURN appends
            // behind the commarea at app/cbl/COCRDSLC.cbl:398-400.
            assertThat(json.keySet()).containsExactlyInAnyOrder(
                    "trnnameo", "title01o", "curdateo", "pgmnameo", "title02o", "curtimeo",
                    "acctsido", "cardsido", "crdnameo", "crdstcdo", "expmono", "expyearo",
                    "infomsgo", "errmsgo", "fkeyso",
                    "cardScreenState", "navigationContext", "nextProgram", "nextMapset", "nextMap",
                    "thisProgCommarea");
            assertThat(json).hasSize(21);
            // The attribute quads are metadata and are published under the ScreenResponse envelope's
            // screenMetadata member, never as siblings of the fifteen values - so no xxxC item and no
            // accessor-shaped name appears here.
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

            assertThat(json.get("cardsido")).isEqualTo("1234567890123456");
            assertThat(json.get("acctsido")).isEqualTo("00000000011");
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
}
