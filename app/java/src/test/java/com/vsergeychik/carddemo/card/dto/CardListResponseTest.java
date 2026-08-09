package com.vsergeychik.carddemo.card.dto;

import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.card.dto.CardListResponse.FieldAttributes;
import com.vsergeychik.carddemo.card.dto.CardListResponse.MapField;
import com.vsergeychik.carddemo.card.dto.CardListRequest.CardKey;
import com.vsergeychik.carddemo.card.dto.CardListRequest.PageCursor;
import com.vsergeychik.carddemo.card.dto.CardListResponse.ScreenRow;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link CardListResponse}, the Java projection of the {@code CCRDLIAO} output
 * symbolic map [{@code app/cpy-bms/COCRDLI.CPY:289-560}].
 *
 * <p>The suite is organised around the properties of the map and its program that determine the
 * implementation, so a failure names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li><strong>The row-1 asymmetry.</strong> Row 1 has four fields and rows 2 to 7 have five, and the
 *       45-field total only balances with that. Asserted from three directions: the descriptor table,
 *       the accessor surface, and the byte arithmetic.</li>
 *   <li><strong>The byte geometry.</strong> 138 + 29 + 180 + 123 = 470 of data, 12 of {@code TIOAPFX}
 *       prefix and 45 x 7 of per-field prefix, totalling 797.</li>
 *   <li><strong>{@code OCCURS} is 1-based.</strong> Every seven-element structure is asserted at
 *       <em>both</em> index 1 and index 7.</li>
 *   <li><strong>{@code LOW-VALUES} is a third state</strong>, distinct from spaces and from
 *       {@code null}, and the {@code 88}-levels that name it accept nothing else.</li>
 *   <li><strong>Page size 7 is behaviour</strong>, reachable only as a compile-time constant.</li>
 * </ol>
 *
 * <p>Expected values are transcribed from {@code app/cpy-bms/COCRDLI.CPY},
 * {@code app/bms/COCRDLI.bms} and {@code app/cbl/COCRDLIC.cbl} rather than read out of the class under
 * test, so the sources remain the authority.
 */
@DisplayName("CardListResponse - COCRDLI CCRDLIAO output map")
class CardListResponseTest {

    /** The fixtures' code page. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The code page of the datasets under {@code app/data/EBCDIC}. */
    private static final Charset EBCDIC = Charset.forName("IBM037");

    /**
     * The 45 name-labelled {@code DFHMDF} entries of {@code app/bms/COCRDLI.bms}, in mapset order,
     * transcribed by hand from the mapset. Note what is absent: {@code CRDSTP1} and {@code FKEYS}.
     */
    private static final List<String> EXPECTED_LABELS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "PAGENO", "ACCTSID",
            "CARDSID",
            "CRDSEL1", "ACCTNO1", "CRDNUM1", "CRDSTS1",
            "CRDSEL2", "CRDSTP2", "ACCTNO2", "CRDNUM2", "CRDSTS2",
            "CRDSEL3", "CRDSTP3", "ACCTNO3", "CRDNUM3", "CRDSTS3",
            "CRDSEL4", "CRDSTP4", "ACCTNO4", "CRDNUM4", "CRDSTS4",
            "CRDSEL5", "CRDSTP5", "ACCTNO5", "CRDNUM5", "CRDSTS5",
            "CRDSEL6", "CRDSTP6", "ACCTNO6", "CRDNUM6", "CRDSTS6",
            "CRDSEL7", "CRDSTP7", "ACCTNO7", "CRDNUM7", "CRDSTS7",
            "INFOMSG", "ERRMSG");

    /** The declared {@code LENGTH=} of each of the 45 fields, in the same order. */
    private static final List<Integer> EXPECTED_LENGTHS = List.of(
            4, 40, 8, 8, 40, 8, 3, 11, 16,
            1, 11, 16, 1,
            1, 1, 11, 16, 1,
            1, 1, 11, 16, 1,
            1, 1, 11, 16, 1,
            1, 1, 11, 16, 1,
            1, 1, 11, 16, 1,
            1, 1, 11, 16, 1,
            45, 78);

    /** {@code LOW-VALUES} of the given width, as {@link CardScreenState} renders it. */
    private static String low(int length) {
        return CardScreenState.lowValues(length);
    }

    /** {@code SPACES} of the given width. */
    private static String sp(int length) {
        return CardScreenState.spaces(length);
    }

    // =================================================================================================

    @Nested
    @DisplayName("Group geometry - the 797-byte CCRDLIAO image")
    class GroupGeometry {

        @Test
        @DisplayName("declares exactly 45 payload members, the mapset's name-labelled DFHMDF count")
        void payloadFieldCountIs45() {
            assertThat(CardListResponse.PAYLOAD_FIELD_COUNT).isEqualTo(45);
            assertThat(CardListResponse.MAP_FIELDS).hasSize(45);
            assertThat(new CardListResponse().payloadFieldCount()).isEqualTo(45);
        }

        @Test
        @DisplayName("reconciles 9 header + 4 row-1 + 6x5 rows-2-to-7 + 2 footer = 45")
        void fieldCountReconcilesOnlyWithTheAsymmetry() {
            assertThat(CardListResponse.HEADER_FIELD_COUNT).isEqualTo(9);
            assertThat(CardListResponse.ROW_1_FIELD_COUNT).isEqualTo(4);
            assertThat(CardListResponse.ROW_N_FIELD_COUNT).isEqualTo(5);
            assertThat(CardListResponse.FOOTER_FIELD_COUNT).isEqualTo(2);
            assertThat(9 + 4 + (6 * 5) + 2).isEqualTo(CardListResponse.PAYLOAD_FIELD_COUNT);
        }

        @Test
        @DisplayName("sums the four blocks to 138 + 29 + 180 + 123 = 470 bytes of data")
        void payloadLengthIs470() {
            int header = 4 + 40 + 8 + 8 + 40 + 8 + 3 + 11 + 16;
            int rowOne = 1 + 11 + 16 + 1;
            int rowsTwoToSeven = 6 * (1 + 1 + 11 + 16 + 1);
            int footer = 45 + 78;
            assertThat(header).isEqualTo(138);
            assertThat(rowOne).isEqualTo(29);
            assertThat(rowsTwoToSeven).isEqualTo(180);
            assertThat(footer).isEqualTo(123);
            assertThat(header + rowOne + rowsTwoToSeven + footer).isEqualTo(470);
            assertThat(CardListResponse.PAYLOAD_LENGTH).isEqualTo(470);
        }

        @Test
        @DisplayName("spends 7 bytes per field on prefix - FILLER X(3) plus the four attribute items")
        void fieldPrefixIsSevenBytes() {
            assertThat(CardListResponse.RESERVED_SPAN_LENGTH).isEqualTo(3);
            assertThat(CardListResponse.ATTRIBUTE_ITEM_COUNT).isEqualTo(4);
            assertThat(CardListResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(CardListResponse.FIELD_PREFIX_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("totals 12 + 45x7 + 470 = 797 bytes")
        void groupLengthIs797() {
            assertThat(CardListResponse.TIOAPFX_LENGTH).isEqualTo(12);
            assertThat(12 + (45 * 7) + 470).isEqualTo(797);
            assertThat(CardListResponse.GROUP_LENGTH).isEqualTo(797);
            assertThat(CardListResponse.GROUP_LAYOUT.recordLength()).isEqualTo(797);
        }

        @Test
        @DisplayName("expands into 1 + 45x6 = 271 contiguous spans with no gap and no overlap")
        void groupLayoutHas271Spans() {
            assertThat(CardListResponse.GROUP_LAYOUT.spans()).hasSize(1 + (45 * 6));
            int cursor = 0;
            for (FixedWidthRecord.FieldSpan span : CardListResponse.GROUP_LAYOUT.spans()) {
                assertThat(span.offset()).as("span %s starts where the previous ended",
                        span.name()).isEqualTo(cursor);
                cursor = span.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(797);
        }

        @Test
        @DisplayName("lays the descriptor table out contiguously from byte 12")
        void descriptorOffsetsAreContiguousAfterTheTioapfxPrefix() {
            int cursor = CardListResponse.TIOAPFX_LENGTH;
            for (MapField field : CardListResponse.MAP_FIELDS) {
                assertThat(field.prefixOffset()).as("prefix of %s", field.itemName())
                        .isEqualTo(cursor);
                assertThat(field.dataOffset()).isEqualTo(cursor + 7);
                cursor = field.endOffsetExclusive();
            }
            assertThat(cursor).isEqualTo(CardListResponse.GROUP_LENGTH);
        }

        @Test
        @DisplayName("keeps WS-ALL-ROWS at 28 x 7 = 196 and the selection groups at 7")
        void workingStorageWidths() {
            assertThat(CardListResponse.SCREEN_ROW_LENGTH).isEqualTo(28);
            assertThat(CardListResponse.ROW_COUNT).isEqualTo(7);
            assertThat(CardListResponse.SCREEN_ARRAY_LENGTH).isEqualTo(196);
            assertThat(CardListResponse.SELECT_FLAGS_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("keeps the navigation triple at 8, 7 and 7 - map names are seven characters")
        void navigationWidths() {
            assertThat(CardListResponse.NEXT_PROGRAM_LENGTH).isEqualTo(8);
            assertThat(CardListResponse.NEXT_MAPSET_LENGTH).isEqualTo(7);
            assertThat(CardListResponse.NEXT_MAP_LENGTH).isEqualTo(7);
            assertThat(CardListResponse.LIT_THISMAP).hasSize(7);
            assertThat(CardListResponse.LIT_THISMAPSET).hasSize(7);
            assertThat(CardListResponse.LIT_THISPGM).hasSize(8);
            assertThat(CardListResponse.LIT_MENUPGM).hasSize(8);
            assertThat(CardListResponse.LIT_THISTRANID).hasSize(4);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The row-1 asymmetry - the top parity trap")
    class RowOneAsymmetry {

        @Test
        @DisplayName("declares no CRDSTP1O anywhere in the descriptor table")
        void noCrdstp1Descriptor() {
            assertThat(CardListResponse.MAP_FIELDS)
                    .noneMatch(field -> field.itemName().equals("CRDSTP1O"))
                    .noneMatch(field -> field.screenFieldPrefix().equals("CRDSTP1"));
        }

        @Test
        @DisplayName("rejects CRDSTP1O and CRDSTP1 by name")
        void crdstp1IsNotAddressable() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardListResponse.mapField("CRDSTP1O"))
                    .withMessageContaining("CCRDLIAO");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardListResponse.mapFieldByPrefix("CRDSTP1"))
                    .withMessageContaining("CRDSTP1");
        }

        @Test
        @DisplayName("rejects crdstpItem(1) with a message that cites the copybook lines")
        void crdstpItemRejectsRowOne() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdstpItem(1))
                    .withMessageContaining("350")
                    .withMessageContaining("356");
        }

        @ParameterizedTest(name = "row {0} has a CRDSTP item")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("provides CRDSTP2O through CRDSTP7O")
        void rowsTwoToSevenHaveCrdstp(int row) {
            assertThat(CardListResponse.hasCrdstpItem(row)).isTrue();
            assertThat(CardListResponse.crdstpItem(row)).isEqualTo("CRDSTP" + row + "O");
        }

        @Test
        @DisplayName("reports four members for row 1 and five for rows 2 to 7")
        void rowFieldCounts() {
            assertThat(CardListResponse.rowFieldCount(1)).isEqualTo(4);
            assertThat(CardListResponse.hasCrdstpItem(1)).isFalse();
            for (int row = 2; row <= 7; row++) {
                assertThat(CardListResponse.rowFieldCount(row)).isEqualTo(5);
            }
        }

        @ParameterizedTest(name = "CRDSTP{0}O sits immediately after CRDSEL{0}O")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("places CRDSTPnO second in the row, not last")
        void crdstpIsSecondInTheRow(int row) {
            List<String> names = CardListResponse.MAP_FIELDS.stream().map(MapField::itemName)
                    .toList();
            int selectionIndex = names.indexOf("CRDSEL" + row + "O");
            assertThat(names.get(selectionIndex + 1)).isEqualTo("CRDSTP" + row + "O");
            assertThat(names.get(selectionIndex + 2)).isEqualTo("ACCTNO" + row + "O");
            assertThat(names.get(selectionIndex + 3)).isEqualTo("CRDNUM" + row + "O");
            assertThat(names.get(selectionIndex + 4)).isEqualTo("CRDSTS" + row + "O");
        }

        @Test
        @DisplayName("runs CRDSEL1O straight into ACCTNO1O, as COCRDLI.CPY:350 and :356 do")
        void rowOneRunsSelectionStraightIntoAccountNumber() {
            List<String> names = CardListResponse.MAP_FIELDS.stream().map(MapField::itemName)
                    .toList();
            int selectionIndex = names.indexOf("CRDSEL1O");
            assertThat(names.get(selectionIndex + 1)).isEqualTo("ACCTNO1O");
            assertThat(names.get(selectionIndex + 2)).isEqualTo("CRDNUM1O");
            assertThat(names.get(selectionIndex + 3)).isEqualTo("CRDSTS1O");
            assertThat(names.get(selectionIndex + 4)).isEqualTo("CRDSEL2O");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Mapset cross-check - every payload field traces to a DFHMDF (gate G9)")
    class MapsetCrossCheck {

        @Test
        @DisplayName("matches the mapset's 45 name-labelled DFHMDF entries in order")
        void labelsMatchTheMapsetExactly() {
            assertThat(CardListResponse.MAP_FIELDS.stream().map(MapField::screenFieldPrefix).toList())
                    .containsExactlyElementsOf(EXPECTED_LABELS);
        }

        @Test
        @DisplayName("matches every DFHMDF LENGTH= to its xxxO PICTURE width")
        void lengthsMatchTheMapsetExactly() {
            assertThat(CardListResponse.MAP_FIELDS.stream().map(MapField::length).toList())
                    .containsExactlyElementsOf(EXPECTED_LENGTHS);
        }

        @Test
        @DisplayName("names every item as its DFHMDF label plus the O suffix")
        void itemNamesAreLabelPlusSuffix() {
            for (int i = 0; i < EXPECTED_LABELS.size(); i++) {
                MapField field = CardListResponse.MAP_FIELDS.get(i);
                assertThat(field.itemName()).isEqualTo(EXPECTED_LABELS.get(i) + "O");
                assertThat(field.colourItemName()).isEqualTo(EXPECTED_LABELS.get(i) + "C");
                assertThat(field.psItemName()).isEqualTo(EXPECTED_LABELS.get(i) + "P");
                assertThat(field.highlightItemName()).isEqualTo(EXPECTED_LABELS.get(i) + "H");
                assertThat(field.validnItemName()).isEqualTo(EXPECTED_LABELS.get(i) + "V");
            }
        }

        @Test
        @DisplayName("has no FKEYS field - the F-key legend is an unnamed literal DFHMDF")
        void noFkeysField() {
            assertThat(CardListResponse.MAP_FIELDS)
                    .noneMatch(field -> field.screenFieldPrefix().equals("FKEYS"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardListResponse.mapFieldByPrefix("FKEYS"));
        }

        @Test
        @DisplayName("keeps INFOMSG at 45 and ERRMSG at 78, not the siblings' 40 and 80")
        void messageWidthsAreNotUnifiedAcrossTheCardMaps() {
            assertThat(CardListResponse.INFOMSGO_LENGTH).isEqualTo(45).isNotEqualTo(40);
            assertThat(CardListResponse.ERRMSGO_LENGTH).isEqualTo(78).isNotEqualTo(80);
        }

        @Test
        @DisplayName("keeps PAGENOO seventh in the header, between CURTIMEO and ACCTSIDO")
        void pageNumberKeepsItsPosition() {
            List<String> names = CardListResponse.MAP_FIELDS.stream().map(MapField::itemName)
                    .toList();
            assertThat(names.indexOf("PAGENOO")).isEqualTo(6);
            assertThat(names.get(5)).isEqualTo("CURTIMEO");
            assertThat(names.get(7)).isEqualTo("ACCTSIDO");
            assertThat(CardListResponse.PAGENOO_LENGTH).isEqualTo(3);
        }

        @Test
        @DisplayName("cites a plausible COCRDLI.CPY line for every item, in ascending order")
        void copybookLinesAscendInStepsOfSix() {
            int previous = 0;
            for (MapField field : CardListResponse.MAP_FIELDS) {
                assertThat(field.copybookLine()).as("line of %s", field.itemName())
                        .isGreaterThan(previous);
                previous = field.copybookLine();
            }
            assertThat(CardListResponse.MAP_FIELDS.get(0).copybookLine()).isEqualTo(296);
            assertThat(CardListResponse.MAP_FIELDS.get(44).copybookLine()).isEqualTo(560);
        }

        @Test
        @DisplayName("renders a descriptor the way the copybook reads")
        void describeNamesThePictureAndTheBytes() {
            MapField field = CardListResponse.mapField(CardListResponse.CRDSTP2O_ITEM);
            assertThat(field.describe()).contains("CRDSTP2O", "PIC X(1)", "@380")
                    .contains(String.valueOf(field.dataOffset()));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Initial state - MOVE LOW-VALUES TO CCRDLIAO (COCRDLIC.cbl:643)")
    class InitialState {

        @Test
        @DisplayName("fills every payload member with LOW-VALUES at its declared width")
        void everyPayloadMemberStartsAtLowValues() {
            CardListResponse response = new CardListResponse();
            for (MapField field : CardListResponse.MAP_FIELDS) {
                assertThat(response.field(field.itemName())).as("initial %s", field.itemName())
                        .isEqualTo(low(field.length()))
                        .hasSize(field.length())
                        .isNotEqualTo(sp(field.length()));
            }
        }

        @Test
        @DisplayName("sets every attribute byte to 0x00, the map default for colour and highlight")
        void everyAttributeQuadStartsAtZero() {
            CardListResponse response = new CardListResponse();
            for (MapField field : CardListResponse.MAP_FIELDS) {
                FieldAttributes quad = response.fieldAttributes(field.screenFieldPrefix());
                assertThat(quad.colour()).isEqualTo(BmsAttributes.DFHDFCOL).isEqualTo((byte) 0x00);
                assertThat(quad.highlight()).isEqualTo(BmsAttributes.DFHDFHI);
                assertThat(quad.ps()).isEqualTo((byte) 0x00);
                assertThat(quad.validn()).isEqualTo((byte) 0x00);
                assertThat(quad.toByteArray()).containsExactly(0x00, 0x00, 0x00, 0x00);
            }
        }

        @Test
        @DisplayName("renders a fresh instance as 797 zero bytes")
        void freshImageIsAllZeroBytes() {
            byte[] image = new CardListResponse().toFixedWidth(ASCII);
            assertThat(image).hasSize(797);
            assertThat(image).containsOnly((byte) 0x00);
        }

        @Test
        @DisplayName("starts the selection array as seven LOW-VALUES, so SELECT-BLANK holds on every row")
        void selectionArrayStartsAtLowValues() {
            CardListResponse response = new CardListResponse();
            assertThat(response.editSelectFlags()).isEqualTo(low(7));
            for (int row = 1; row <= 7; row++) {
                assertThat(response.editSelect(row)).isEqualTo(CardListResponse.LOW_VALUE);
                assertThat(response.isSelectBlank(row)).isTrue();
                assertThat(response.isSelectOk(row)).isFalse();
            }
        }

        @Test
        @DisplayName("starts the row-error array as seven LOW-VALUES, so no row is in error")
        void errorArrayStartsAtLowValues() {
            CardListResponse response = new CardListResponse();
            assertThat(response.editSelectErrorFlags()).isEqualTo(low(7));
            for (int row = 1; row <= 7; row++) {
                assertThat(response.isWsRowSelectError(row)).isFalse();
            }
        }

        @Test
        @DisplayName("starts the navigation triple, cursor, work area and commarea at their defaults")
        void carriersStartAtTheirDefaults() {
            CardListResponse response = new CardListResponse();
            assertThat(response.getNextProgram()).isEqualTo(sp(8));
            assertThat(response.getNextMapset()).isEqualTo(sp(7));
            assertThat(response.getNextMap()).isEqualTo(sp(7));
            assertThat(response.getPageCursor()).isEqualTo(PageCursor.initialised());
            assertThat(response.getCardScreenState()).isEqualTo(new CardScreenState());
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
        }

        @Test
        @DisplayName("restores the map to LOW-VALUES without touching the carried areas")
        void moveLowValuesToMapWipesOnlyTheMap() {
            CardListResponse response = new CardListResponse();
            response.setTrnnameo("CCLI");
            response.fieldAttributes("ACCTSID").setColour(BmsAttributes.DFHRED);
            response.setNextProgram(CardListResponse.LIT_MENUPGM);
            response.setPageCursor(PageCursor.initialised().withScreenNum(3));

            response.moveLowValuesToMap();

            assertThat(response.getTrnnameo()).isEqualTo(low(4));
            assertThat(response.fieldAttributes("ACCTSID").colour()).isEqualTo((byte) 0x00);
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(response.getPageCursor().screenNum()).isEqualTo(3);
        }
    }

    // =================================================================================================

    /**
     * The 45 typed accessor pairs, each paired with the item name it must address. Written out rather
     * than reflected over, so a renamed or missing accessor fails to compile here.
     *
     * @return one argument triple per payload member
     */
    static Stream<Arguments> typedAccessors() {
        List<Arguments> rows = new ArrayList<>();
        rows.add(accessor(CardListResponse.TRNNAMEO_ITEM, CardListResponse::getTrnnameo,
                CardListResponse::setTrnnameo));
        rows.add(accessor(CardListResponse.TITLE01O_ITEM, CardListResponse::getTitle01o,
                CardListResponse::setTitle01o));
        rows.add(accessor(CardListResponse.CURDATEO_ITEM, CardListResponse::getCurdateo,
                CardListResponse::setCurdateo));
        rows.add(accessor(CardListResponse.PGMNAMEO_ITEM, CardListResponse::getPgmnameo,
                CardListResponse::setPgmnameo));
        rows.add(accessor(CardListResponse.TITLE02O_ITEM, CardListResponse::getTitle02o,
                CardListResponse::setTitle02o));
        rows.add(accessor(CardListResponse.CURTIMEO_ITEM, CardListResponse::getCurtimeo,
                CardListResponse::setCurtimeo));
        rows.add(accessor(CardListResponse.PAGENOO_ITEM, CardListResponse::getPagenoo,
                CardListResponse::setPagenoo));
        rows.add(accessor(CardListResponse.ACCTSIDO_ITEM, CardListResponse::getAcctsido,
                CardListResponse::setAcctsido));
        rows.add(accessor(CardListResponse.CARDSIDO_ITEM, CardListResponse::getCardsido,
                CardListResponse::setCardsido));
        rows.add(accessor(CardListResponse.CRDSEL1O_ITEM, CardListResponse::getCrdsel1o,
                CardListResponse::setCrdsel1o));
        rows.add(accessor(CardListResponse.ACCTNO1O_ITEM, CardListResponse::getAcctno1o,
                CardListResponse::setAcctno1o));
        rows.add(accessor(CardListResponse.CRDNUM1O_ITEM, CardListResponse::getCrdnum1o,
                CardListResponse::setCrdnum1o));
        rows.add(accessor(CardListResponse.CRDSTS1O_ITEM, CardListResponse::getCrdsts1o,
                CardListResponse::setCrdsts1o));
        rows.add(accessor(CardListResponse.CRDSEL2O_ITEM, CardListResponse::getCrdsel2o,
                CardListResponse::setCrdsel2o));
        rows.add(accessor(CardListResponse.CRDSTP2O_ITEM, CardListResponse::getCrdstp2o,
                CardListResponse::setCrdstp2o));
        rows.add(accessor(CardListResponse.ACCTNO2O_ITEM, CardListResponse::getAcctno2o,
                CardListResponse::setAcctno2o));
        rows.add(accessor(CardListResponse.CRDNUM2O_ITEM, CardListResponse::getCrdnum2o,
                CardListResponse::setCrdnum2o));
        rows.add(accessor(CardListResponse.CRDSTS2O_ITEM, CardListResponse::getCrdsts2o,
                CardListResponse::setCrdsts2o));
        rows.add(accessor(CardListResponse.CRDSEL3O_ITEM, CardListResponse::getCrdsel3o,
                CardListResponse::setCrdsel3o));
        rows.add(accessor(CardListResponse.CRDSTP3O_ITEM, CardListResponse::getCrdstp3o,
                CardListResponse::setCrdstp3o));
        rows.add(accessor(CardListResponse.ACCTNO3O_ITEM, CardListResponse::getAcctno3o,
                CardListResponse::setAcctno3o));
        rows.add(accessor(CardListResponse.CRDNUM3O_ITEM, CardListResponse::getCrdnum3o,
                CardListResponse::setCrdnum3o));
        rows.add(accessor(CardListResponse.CRDSTS3O_ITEM, CardListResponse::getCrdsts3o,
                CardListResponse::setCrdsts3o));
        rows.add(accessor(CardListResponse.CRDSEL4O_ITEM, CardListResponse::getCrdsel4o,
                CardListResponse::setCrdsel4o));
        rows.add(accessor(CardListResponse.CRDSTP4O_ITEM, CardListResponse::getCrdstp4o,
                CardListResponse::setCrdstp4o));
        rows.add(accessor(CardListResponse.ACCTNO4O_ITEM, CardListResponse::getAcctno4o,
                CardListResponse::setAcctno4o));
        rows.add(accessor(CardListResponse.CRDNUM4O_ITEM, CardListResponse::getCrdnum4o,
                CardListResponse::setCrdnum4o));
        rows.add(accessor(CardListResponse.CRDSTS4O_ITEM, CardListResponse::getCrdsts4o,
                CardListResponse::setCrdsts4o));
        rows.add(accessor(CardListResponse.CRDSEL5O_ITEM, CardListResponse::getCrdsel5o,
                CardListResponse::setCrdsel5o));
        rows.add(accessor(CardListResponse.CRDSTP5O_ITEM, CardListResponse::getCrdstp5o,
                CardListResponse::setCrdstp5o));
        rows.add(accessor(CardListResponse.ACCTNO5O_ITEM, CardListResponse::getAcctno5o,
                CardListResponse::setAcctno5o));
        rows.add(accessor(CardListResponse.CRDNUM5O_ITEM, CardListResponse::getCrdnum5o,
                CardListResponse::setCrdnum5o));
        rows.add(accessor(CardListResponse.CRDSTS5O_ITEM, CardListResponse::getCrdsts5o,
                CardListResponse::setCrdsts5o));
        rows.add(accessor(CardListResponse.CRDSEL6O_ITEM, CardListResponse::getCrdsel6o,
                CardListResponse::setCrdsel6o));
        rows.add(accessor(CardListResponse.CRDSTP6O_ITEM, CardListResponse::getCrdstp6o,
                CardListResponse::setCrdstp6o));
        rows.add(accessor(CardListResponse.ACCTNO6O_ITEM, CardListResponse::getAcctno6o,
                CardListResponse::setAcctno6o));
        rows.add(accessor(CardListResponse.CRDNUM6O_ITEM, CardListResponse::getCrdnum6o,
                CardListResponse::setCrdnum6o));
        rows.add(accessor(CardListResponse.CRDSTS6O_ITEM, CardListResponse::getCrdsts6o,
                CardListResponse::setCrdsts6o));
        rows.add(accessor(CardListResponse.CRDSEL7O_ITEM, CardListResponse::getCrdsel7o,
                CardListResponse::setCrdsel7o));
        rows.add(accessor(CardListResponse.CRDSTP7O_ITEM, CardListResponse::getCrdstp7o,
                CardListResponse::setCrdstp7o));
        rows.add(accessor(CardListResponse.ACCTNO7O_ITEM, CardListResponse::getAcctno7o,
                CardListResponse::setAcctno7o));
        rows.add(accessor(CardListResponse.CRDNUM7O_ITEM, CardListResponse::getCrdnum7o,
                CardListResponse::setCrdnum7o));
        rows.add(accessor(CardListResponse.CRDSTS7O_ITEM, CardListResponse::getCrdsts7o,
                CardListResponse::setCrdsts7o));
        rows.add(accessor(CardListResponse.INFOMSGO_ITEM, CardListResponse::getInfomsgo,
                CardListResponse::setInfomsgo));
        rows.add(accessor(CardListResponse.ERRMSGO_ITEM, CardListResponse::getErrmsgo,
                CardListResponse::setErrmsgo));
        return rows.stream();
    }

    private static Arguments accessor(String itemName, Function<CardListResponse, String> getter,
            BiConsumer<CardListResponse, String> setter) {
        return Arguments.of(itemName, getter, setter);
    }

    @Nested
    @DisplayName("Typed accessors - one pair per xxxO item, all 45")
    class TypedAccessors {

        @Test
        @DisplayName("declares exactly 45 accessor pairs, one per descriptor and no more")
        void thereAreExactly45AccessorPairs() {
            Set<String> addressed = new LinkedHashSet<>();
            typedAccessors().forEach(argument -> addressed.add((String) argument.get()[0]));
            assertThat(addressed).hasSize(45).containsExactlyElementsOf(
                    CardListResponse.MAP_FIELDS.stream().map(MapField::itemName).toList());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListResponseTest#typedAccessors")
        @DisplayName("addresses its own item and no other")
        @SuppressWarnings("unchecked")
        void accessorAddressesItsOwnItem(String itemName, Object getter, Object setter) {
            Function<CardListResponse, String> read = (Function<CardListResponse, String>) getter;
            BiConsumer<CardListResponse, String> write =
                    (BiConsumer<CardListResponse, String>) setter;
            int width = CardListResponse.mapField(itemName).length();

            CardListResponse response = new CardListResponse();
            write.accept(response, "X".repeat(width));

            assertThat(read.apply(response)).isEqualTo("X".repeat(width));
            assertThat(response.field(itemName)).isEqualTo("X".repeat(width));
            for (MapField other : CardListResponse.MAP_FIELDS) {
                if (!other.itemName().equals(itemName)) {
                    assertThat(response.field(other.itemName())).as("%s must be untouched",
                            other.itemName()).isEqualTo(low(other.length()));
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vsergeychik.carddemo.card.dto.CardListResponseTest#typedAccessors")
        @DisplayName("applies the PIC X move rule - right pad when short, right truncate when long")
        @SuppressWarnings("unchecked")
        void accessorAppliesThePicXMoveRule(String itemName, Object getter, Object setter) {
            Function<CardListResponse, String> read = (Function<CardListResponse, String>) getter;
            BiConsumer<CardListResponse, String> write =
                    (BiConsumer<CardListResponse, String>) setter;
            int width = CardListResponse.mapField(itemName).length();
            CardListResponse response = new CardListResponse();

            write.accept(response, "A");
            assertThat(read.apply(response)).hasSize(width)
                    .isEqualTo("A" + " ".repeat(width - 1));

            String tooLong = "0123456789".repeat(9).substring(0, width + 1);
            write.accept(response, tooLong);
            assertThat(read.apply(response)).hasSize(width)
                    .isEqualTo(tooLong.substring(0, width));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Name-addressed access")
    class NameAddressedAccess {

        @Test
        @DisplayName("rejects an item name the copybook does not declare")
        void unknownItemNameIsRejectedRatherThanIgnored() {
            CardListResponse response = new CardListResponse();
            assertThatIllegalArgumentException().isThrownBy(() -> response.field("NOSUCHO"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setField("NOSUCHO", "x"));
            assertThatNullPointerException().isThrownBy(() -> CardListResponse.mapField(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardListResponse.mapFieldByPrefix(null));
        }

        @Test
        @DisplayName("rejects null as a sending value - null is not a COBOL state")
        void nullSendingValueIsRejected() {
            CardListResponse response = new CardListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setField(CardListResponse.TRNNAMEO_ITEM, null))
                    .withMessageContaining("TRNNAMEO");
        }

        @Test
        @DisplayName("exposes an unmodifiable, insertion-ordered snapshot of all 45 items")
        void fieldImagesIsAnUnmodifiableSnapshot() {
            CardListResponse response = new CardListResponse();
            Map<String, String> images = response.fieldImages();
            assertThat(images).hasSize(45);
            assertThat(images.keySet()).containsExactlyElementsOf(
                    CardListResponse.MAP_FIELDS.stream().map(MapField::itemName).toList());
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> images.put("TRNNAMEO", "ZZZZ"));

            response.setTrnnameo("CCLI");
            assertThat(images.get("TRNNAMEO")).as("the snapshot must not follow later writes")
                    .isEqualTo(low(4));
        }

        @Test
        @DisplayName("resolves a descriptor by DFHMDF label as well as by item name")
        void descriptorsAreAddressableBothWays() {
            MapField byItem = CardListResponse.mapField("ACCTSIDO");
            MapField byLabel = CardListResponse.mapFieldByPrefix("ACCTSID");
            assertThat(byItem).isEqualTo(byLabel);
            assertThat(byItem.length()).isEqualTo(11);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("OCCURS indexing - 1-based in COBOL, 0-based in Java (gate G33)")
    class OccursIndexing {

        @Test
        @DisplayName("maps subscript 1 to index 0 and subscript 7 to index 6")
        void firstAndLastSubscriptsConvert() {
            assertThat(CardListResponse.FIRST_ROW).isEqualTo(1);
            assertThat(CardListResponse.LAST_ROW).isEqualTo(7);
            assertThat(CardListResponse.toJavaIndex(1)).isZero();
            assertThat(CardListResponse.toJavaIndex(7)).isEqualTo(6);
            assertThat(CardListResponse.toCobolSubscript(0)).isEqualTo(1);
            assertThat(CardListResponse.toCobolSubscript(6)).isEqualTo(7);
        }

        @ParameterizedTest(name = "subscript {0} is out of range")
        @ValueSource(ints = {-1, 0, 8, 99})
        @DisplayName("rejects a subscript outside 1..7, including zero")
        void outOfRangeSubscriptsAreRejected(int subscript) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.toJavaIndex(subscript));
        }

        @ParameterizedTest(name = "index {0} is out of range")
        @ValueSource(ints = {-1, 7, 99})
        @DisplayName("rejects a Java index outside 0..6")
        void outOfRangeIndicesAreRejected(int index) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.toCobolSubscript(index));
        }

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {1, 7})
        @DisplayName("addresses the first and the last row correctly")
        void firstAndLastRowsAreAddressable(int row) {
            CardListResponse response = new CardListResponse();
            ScreenRow written = ScreenRow.of("0000000000" + row, "4" + "1".repeat(14) + row, "Y");
            response.setScreenRow(row, written);

            assertThat(response.screenRow(row)).isEqualTo(written);
            assertThat(response.field(CardListResponse.acctnoItem(row)))
                    .isEqualTo(written.rowAcctno());
            assertThat(response.field(CardListResponse.crdnumItem(row)))
                    .isEqualTo(written.rowCardNum());
            assertThat(response.field(CardListResponse.crdstsItem(row)))
                    .isEqualTo(written.rowCardStatus());
        }

        @Test
        @DisplayName("does not shift row data by one - row 1 and row 7 stay distinct")
        void noOffByOneBetweenTheFirstAndLastRow() {
            CardListResponse response = new CardListResponse();
            response.setScreenRow(1, ScreenRow.of("11111111111", "1111111111111111", "1"));
            response.setScreenRow(7, ScreenRow.of("77777777777", "7777777777777777", "7"));

            assertThat(response.getAcctno1o()).isEqualTo("11111111111");
            assertThat(response.getAcctno7o()).isEqualTo("77777777777");
            assertThat(response.screenRows().get(0)).isEqualTo(response.screenRow(1));
            assertThat(response.screenRows().get(6)).isEqualTo(response.screenRow(7));
            for (int row = 2; row <= 6; row++) {
                assertThat(response.screenRow(row).isLowValues()).as("row %d untouched", row)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("exposes seven rows as an unmodifiable list")
        void screenRowsIsUnmodifiable() {
            List<ScreenRow> rows = new CardListResponse().screenRows();
            assertThat(rows).hasSize(7);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(ScreenRow.spaces()));
        }

        @Test
        @DisplayName("round-trips the 196-byte WS-ALL-ROWS image with every row in its own slice")
        void allRowsImageRoundTrips() {
            CardListResponse response = new CardListResponse();
            for (int row = 1; row <= 7; row++) {
                response.setScreenRow(row, ScreenRow.of(String.valueOf(row).repeat(11),
                        String.valueOf(row).repeat(16), String.valueOf(row)));
            }
            String image = response.allRowsImage();
            assertThat(image).hasSize(196);
            assertThat(image).startsWith("1".repeat(28)).endsWith("7".repeat(28));

            CardListResponse rebuilt = new CardListResponse();
            rebuilt.setAllRowsImage(image);
            assertThat(rebuilt.allRowsImage()).isEqualTo(image);
            assertThat(rebuilt.screenRows()).isEqualTo(response.screenRows());
        }

        @Test
        @DisplayName("writes all seven rows at once and refuses a partial list")
        void setScreenRowsRequiresExactlySeven() {
            CardListResponse response = new CardListResponse();
            List<ScreenRow> seven = new ArrayList<>();
            for (int row = 1; row <= 7; row++) {
                seven.add(ScreenRow.of(String.valueOf(row).repeat(11), "4".repeat(16), "Y"));
            }
            response.setScreenRows(seven);
            assertThat(response.screenRows()).containsExactlyElementsOf(seven);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setScreenRows(seven.subList(0, 6)))
                    .withMessageContaining("OCCURS 7 TIMES");
            assertThatNullPointerException().isThrownBy(() -> response.setScreenRows(null));
            assertThatNullPointerException().isThrownBy(() -> response.setScreenRow(1, null));
        }

        @Test
        @DisplayName("rejects a WS-ALL-ROWS image of the wrong width")
        void allRowsImageWidthIsEnforced() {
            CardListResponse response = new CardListResponse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setAllRowsImage("short"))
                    .withMessageContaining("WS-ALL-ROWS");
            assertThatNullPointerException().isThrownBy(() -> response.setAllRowsImage(null));
        }

        @Test
        @DisplayName("resolves every per-column item name for rows 1 through 7")
        void perColumnItemNamesResolveForEveryRow() {
            for (int row = 1; row <= 7; row++) {
                assertThat(CardListResponse.crdselItem(row)).isEqualTo("CRDSEL" + row + "O");
                assertThat(CardListResponse.acctnoItem(row)).isEqualTo("ACCTNO" + row + "O");
                assertThat(CardListResponse.crdnumItem(row)).isEqualTo("CRDNUM" + row + "O");
                assertThat(CardListResponse.crdstsItem(row)).isEqualTo("CRDSTS" + row + "O");
            }
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdselItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.acctnoItem(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdnumItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdstsItem(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.crdstpItem(8));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.rowFieldCount(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> CardListResponse.hasCrdstpItem(8));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("WS-EDIT-SELECT - the four 88-levels (COCRDLIC.cbl:77-82)")
    class SelectionFlags {

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {1, 7})
        @DisplayName("holds SELECT-OK and VIEW-REQUESTED-ON for 'S' at the first and last row")
        void viewRequested(int row) {
            CardListResponse response = new CardListResponse();
            response.setEditSelect(row, CardListResponse.SELECT_VIEW);
            assertThat(response.isSelectOk(row)).isTrue();
            assertThat(response.isViewRequestedOn(row)).isTrue();
            assertThat(response.isUpdateRequestedOn(row)).isFalse();
            assertThat(response.isSelectBlank(row)).isFalse();
            assertThat(response.field(CardListResponse.crdselItem(row))).isEqualTo("S");
        }

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {1, 7})
        @DisplayName("holds SELECT-OK and UPDATE-REQUESTED-ON for 'U' at the first and last row")
        void updateRequested(int row) {
            CardListResponse response = new CardListResponse();
            response.setEditSelect(row, CardListResponse.SELECT_UPDATE);
            assertThat(response.isSelectOk(row)).isTrue();
            assertThat(response.isUpdateRequestedOn(row)).isTrue();
            assertThat(response.isViewRequestedOn(row)).isFalse();
            assertThat(response.isSelectBlank(row)).isFalse();
        }

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {1, 4, 7})
        @DisplayName("holds SELECT-BLANK for BOTH a space and LOW-VALUES, as the 88-level lists both")
        void selectBlankAcceptsSpaceAndLowValues(int row) {
            CardListResponse response = new CardListResponse();

            response.setEditSelect(row, CardListResponse.SPACE);
            assertThat(response.isSelectBlank(row)).isTrue();
            assertThat(response.isSelectOk(row)).isFalse();

            response.setEditSelect(row, CardListResponse.LOW_VALUE);
            assertThat(response.isSelectBlank(row)).isTrue();
            assertThat(response.isSelectOk(row)).isFalse();
        }

        @Test
        @DisplayName("holds none of the four for an invalid character - the WHEN OTHER branch")
        void invalidSelectionSatisfiesNoConditionName() {
            CardListResponse response = new CardListResponse();
            response.setEditSelect(3, "X");
            assertThat(response.isSelectOk(3)).isFalse();
            assertThat(response.isViewRequestedOn(3)).isFalse();
            assertThat(response.isUpdateRequestedOn(3)).isFalse();
            assertThat(response.isSelectBlank(3)).isFalse();
        }

        @Test
        @DisplayName("renders and parses the WS-EDIT-SELECT-FLAGS group image")
        void groupImageRoundTrips() {
            CardListResponse response = new CardListResponse();
            response.setEditSelectFlags("S U   X");
            assertThat(response.editSelectFlags()).isEqualTo("S U   X");
            assertThat(response.isViewRequestedOn(1)).isTrue();
            assertThat(response.isSelectBlank(2)).isTrue();
            assertThat(response.isUpdateRequestedOn(3)).isTrue();
            assertThat(response.isSelectBlank(6)).isTrue();
            assertThat(response.isSelectOk(7)).isFalse();
            assertThat(response.getCrdsel1o()).isEqualTo("S");
            assertThat(response.getCrdsel7o()).isEqualTo("X");
        }

        @Test
        @DisplayName("rejects a group image of the wrong width and a null")
        void groupImageWidthIsEnforced() {
            CardListResponse response = new CardListResponse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setEditSelectFlags("SU"))
                    .withMessageContaining("WS-EDIT-SELECT-FLAGS");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setEditSelectFlags(null));
        }

        @ParameterizedTest(name = "subscript {0}")
        @ValueSource(ints = {0, 8})
        @DisplayName("rejects an out-of-range subscript on every selection accessor")
        void selectionAccessorsCheckBounds(int row) {
            CardListResponse response = new CardListResponse();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.editSelect(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.setEditSelect(row, "S"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.isSelectOk(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.isViewRequestedOn(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.isUpdateRequestedOn(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.isSelectBlank(row));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("WS-EDIT-SELECT-ERRORS - row-error metadata (COCRDLIC.cbl:83-88)")
    class RowErrorFlags {

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {1, 7})
        @DisplayName("marks and reads the first and last row independently")
        void firstAndLastRowAreIndependent(int row) {
            CardListResponse response = new CardListResponse();
            response.setWsRowCrdselectError(row, CardListResponse.ROW_SELECT_ERROR);

            assertThat(response.isWsRowSelectError(row)).isTrue();
            assertThat(response.wsRowCrdselectError(row)).isEqualTo("1");
            for (int other = 1; other <= 7; other++) {
                if (other != row) {
                    assertThat(response.isWsRowSelectError(other)).as("row %d", other).isFalse();
                }
            }
        }

        @Test
        @DisplayName("holds WS-ROW-SELECT-ERROR only for '1' - not for '0', a space or LOW-VALUES")
        void onlyTheDigitOneSatisfiesTheConditionName() {
            CardListResponse response = new CardListResponse();
            for (String value : List.of("0", " ", CardListResponse.LOW_VALUE, "X")) {
                response.setWsRowCrdselectError(4, value);
                assertThat(response.isWsRowSelectError(4)).as("value '%s'", value).isFalse();
            }
            response.setWsRowCrdselectError(4, "1");
            assertThat(response.isWsRowSelectError(4)).isTrue();
        }

        @Test
        @DisplayName("rewrites the whole group, as the INSPECT REPLACING at L1088-1093 does")
        void groupImageIsRewritableWhole() {
            CardListResponse response = new CardListResponse();
            response.setEditSelectErrorFlags("1010000");
            assertThat(response.editSelectErrorFlags()).isEqualTo("1010000");
            assertThat(response.isWsRowSelectError(1)).isTrue();
            assertThat(response.isWsRowSelectError(2)).isFalse();
            assertThat(response.isWsRowSelectError(3)).isTrue();
            assertThat(response.isWsRowSelectError(7)).isFalse();
        }

        @Test
        @DisplayName("enforces widths and rejects nulls")
        void widthsAreEnforced() {
            CardListResponse response = new CardListResponse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setEditSelectErrorFlags("11"))
                    .withMessageContaining("WS-EDIT-SELECT-ERROR-FLAGS");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setEditSelectErrorFlags(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setWsRowCrdselectError(1, "12"))
                    .withMessageContaining("WS-ROW-CRDSELECT-ERROR");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setWsRowCrdselectError(1, null));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.wsRowCrdselectError(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.setWsRowCrdselectError(8, "1"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> response.isWsRowSelectError(8));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Highlighting - CSSETATY and 1250-SETUP-ARRAY-ATTRIBS (gate G38)")
    class Highlighting {

        @Test
        @DisplayName("moves DFHRED into xxxC and nothing into xxxO for a NOT-OK field in REENTER")
        void notOkInReenterReddensTheColourItemOnly() {
            CardListResponse response = new CardListResponse();
            FieldHighlight applied = response.applyHighlight(FieldValidationState.NOT_OK, true,
                    "ACCTSID");

            assertThat(applied.colourItemAssigned()).isTrue();
            assertThat(applied.outputItemAssigned()).isFalse();
            assertThat(applied.colourItemName()).isEqualTo("ACCTSIDC");
            assertThat(applied.outputMapGroupName()).isEqualTo("CCRDLIAO");
            assertThat(response.fieldAttributes("ACCTSID").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getAcctsido()).isEqualTo(low(11));
        }

        @Test
        @DisplayName("moves DFHRED into xxxC and '*' into xxxO for a BLANK field in REENTER")
        void blankInReenterReddensAndStarsTheField() {
            CardListResponse response = new CardListResponse();
            FieldHighlight applied = response.applyHighlight(FieldValidationState.BLANK, true,
                    "CARDSID");

            assertThat(applied.colourItemAssigned()).isTrue();
            assertThat(applied.outputItemAssigned()).isTrue();
            assertThat(applied.outputItemName()).isEqualTo("CARDSIDO");
            assertThat(response.fieldAttributes("CARDSID").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getCardsido())
                    .isEqualTo(FieldAttributeSetter.ASTERISK + " ".repeat(15));
        }

        @ParameterizedTest
        @EnumSource(FieldValidationState.class)
        @DisplayName("changes nothing on first entry, whatever the validation state")
        void firstEntryLeavesTheFieldAlone(FieldValidationState state) {
            CardListResponse response = new CardListResponse();
            FieldHighlight applied = response.applyHighlight(state, false, "ACCTSID");

            assertThat(applied.untouched()).isTrue();
            assertThat(response.fieldAttributes("ACCTSID").colour()).isEqualTo((byte) 0x00);
            assertThat(response.getAcctsido()).isEqualTo(low(11));
        }

        @Test
        @DisplayName("changes nothing for a valid field even in REENTER")
        void validFieldIsLeftAlone() {
            CardListResponse response = new CardListResponse();
            FieldHighlight applied = response.applyHighlight(FieldValidationState.OK, true,
                    "ACCTSID");
            assertThat(applied.untouched()).isTrue();
            assertThat(response.fieldAttributes("ACCTSID").colour()).isEqualTo((byte) 0x00);
        }

        @Test
        @DisplayName("applies a decision resolved elsewhere, and ignores an untouched one")
        void appliesAnExternallyResolvedDecision() {
            CardListResponse response = new CardListResponse();
            response.applyHighlight(FieldAttributeSetter.resolveFromFlags(false, true, true,
                    "INFOMSG", CardListResponse.LIT_THISMAP));
            assertThat(response.fieldAttributes("INFOMSG").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getInfomsgo())
                    .isEqualTo(FieldAttributeSetter.ASTERISK + " ".repeat(44));

            CardListResponse untouched = new CardListResponse();
            untouched.applyHighlight(FieldHighlight.none("INFOMSG", CardListResponse.LIT_THISMAP));
            assertThat(untouched).isEqualTo(new CardListResponse());
        }

        @Test
        @DisplayName("refuses an assigning decision that names no field, and rejects null")
        void anonymousDecisionCannotBeApplied() {
            CardListResponse response = new CardListResponse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.applyHighlight(
                            FieldAttributeSetter.resolve(FieldValidationState.NOT_OK, true)))
                    .withMessageContaining("no field prefix");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight((FieldHighlight) null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.applyHighlight(FieldValidationState.NOT_OK, true,
                            "NOSUCH"));
        }

        @Test
        @DisplayName("reddens row 1 and stars it when its selection is blank - the row-1-only branch")
        void rowOneGetsTheAsteriskWhenBlank() {
            CardListResponse response = new CardListResponse();
            response.setScreenRow(1, ScreenRow.of("00000000011", "4".repeat(16), "Y"));
            response.setWsRowCrdselectError(1, CardListResponse.ROW_SELECT_ERROR);

            assertThat(response.applyRowSelectHighlight(1, false)).isTrue();
            assertThat(response.fieldAttributes("CRDSEL1").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getCrdsel1o()).isEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("reddens row 1 without starring it when a selection character is present")
        void rowOneKeepsItsSelectionCharacter() {
            CardListResponse response = new CardListResponse();
            response.setScreenRow(1, ScreenRow.of("00000000011", "4".repeat(16), "Y"));
            response.setEditSelect(1, CardListResponse.SELECT_VIEW);
            response.setWsRowCrdselectError(1, CardListResponse.ROW_SELECT_ERROR);

            assertThat(response.applyRowSelectHighlight(1, false)).isTrue();
            assertThat(response.fieldAttributes("CRDSEL1").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.getCrdsel1o()).isEqualTo(CardListResponse.SELECT_VIEW);
        }

        @ParameterizedTest(name = "row {0}")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("reddens rows 2 to 7 but never stars them - the second row-1 asymmetry")
        void laterRowsAreNeverStarred(int row) {
            CardListResponse response = new CardListResponse();
            response.setScreenRow(row, ScreenRow.of("0000000007" + row, "4".repeat(16), "Y"));
            response.setWsRowCrdselectError(row, CardListResponse.ROW_SELECT_ERROR);

            assertThat(response.applyRowSelectHighlight(row, false)).isTrue();
            assertThat(response.fieldAttributes("CRDSEL" + row).colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.field(CardListResponse.crdselItem(row)))
                    .isEqualTo(CardListResponse.LOW_VALUE)
                    .isNotEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @Test
        @DisplayName("changes nothing for an empty row, a protected screen or a row with no error")
        void nonHighlightingBranches() {
            CardListResponse empty = new CardListResponse();
            empty.setWsRowCrdselectError(1, CardListResponse.ROW_SELECT_ERROR);
            assertThat(empty.applyRowSelectHighlight(1, false))
                    .as("WS-EACH-CARD(1) is LOW-VALUES").isFalse();
            assertThat(empty.fieldAttributes("CRDSEL1").colour()).isEqualTo((byte) 0x00);

            CardListResponse protectedScreen = new CardListResponse();
            protectedScreen.setScreenRow(2, ScreenRow.of("00000000022", "4".repeat(16), "Y"));
            protectedScreen.setWsRowCrdselectError(2, CardListResponse.ROW_SELECT_ERROR);
            assertThat(protectedScreen.applyRowSelectHighlight(2, true))
                    .as("FLG-PROTECT-SELECT-ROWS-YES").isFalse();
            assertThat(protectedScreen.fieldAttributes("CRDSEL2").colour()).isEqualTo((byte) 0x00);

            CardListResponse noError = new CardListResponse();
            noError.setScreenRow(3, ScreenRow.of("00000000033", "4".repeat(16), "Y"));
            assertThat(noError.applyRowSelectHighlight(3, false)).isFalse();
            assertThat(noError.fieldAttributes("CRDSEL3").colour()).isEqualTo((byte) 0x00);

            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> noError.applyRowSelectHighlight(8, false));
        }

        @Test
        @DisplayName("highlights the seven rows independently of each other")
        void rowsAreHighlightedIndependently() {
            CardListResponse response = new CardListResponse();
            for (int row = 1; row <= 7; row++) {
                response.setScreenRow(row, ScreenRow.of(String.valueOf(row).repeat(11),
                        "4".repeat(16), "Y"));
            }
            response.setWsRowCrdselectError(1, CardListResponse.ROW_SELECT_ERROR);
            response.setWsRowCrdselectError(7, CardListResponse.ROW_SELECT_ERROR);

            for (int row = 1; row <= 7; row++) {
                response.applyRowSelectHighlight(row, false);
            }

            assertThat(response.fieldAttributes("CRDSEL1").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.fieldAttributes("CRDSEL7").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            for (int row = 2; row <= 6; row++) {
                assertThat(response.fieldAttributes("CRDSEL" + row).colour())
                        .as("row %d", row).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("exposes the live quad for writing and copies for inspection")
        void quadAccessIsLiveForWritingAndCopiedForReading() {
            CardListResponse response = new CardListResponse();
            response.fieldAttributes("ERRMSG").setColour(BmsAttributes.DFHRED);
            response.fieldAttributes("ERRMSG").setHighlight(BmsAttributes.DFHUNDLN);
            response.fieldAttributes("ERRMSG").setPs((byte) 0x0A);
            response.fieldAttributes("ERRMSG").setValidn((byte) 0x0B);

            assertThat(response.fieldAttributes("ERRMSG").colour())
                    .isEqualTo(BmsAttributes.DFHRED);

            Map<String, FieldAttributes> snapshot = response.fieldAttributesSnapshot();
            assertThat(snapshot).hasSize(45);
            assertThat(snapshot.get("ERRMSG").colour()).isEqualTo(BmsAttributes.DFHRED);
            snapshot.get("ERRMSG").setColour(BmsAttributes.DFHGREEN);
            assertThat(response.fieldAttributes("ERRMSG").colour())
                    .as("the snapshot must be copies").isEqualTo(BmsAttributes.DFHRED);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> snapshot.put("ERRMSG", new FieldAttributes()));

            assertThatNullPointerException()
                    .isThrownBy(() -> response.fieldAttributes(null));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("FieldAttributes - the xxxC/xxxP/xxxH/xxxV quad")
    class Quad {

        @Test
        @DisplayName("round-trips through its four-byte image and resets to zero")
        void byteImageRoundTrips() {
            FieldAttributes quad = new FieldAttributes();
            quad.fromByteArray(new byte[] {BmsAttributes.DFHRED, 0x01, BmsAttributes.DFHUNDLN, 0x03});
            assertThat(quad.colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(quad.ps()).isEqualTo((byte) 0x01);
            assertThat(quad.highlight()).isEqualTo(BmsAttributes.DFHUNDLN);
            assertThat(quad.validn()).isEqualTo((byte) 0x03);
            assertThat(quad.toByteArray())
                    .containsExactly(BmsAttributes.DFHRED, 0x01, BmsAttributes.DFHUNDLN, 0x03);

            quad.reset();
            assertThat(quad.toByteArray()).containsOnly((byte) 0x00);
        }

        @Test
        @DisplayName("copies rather than shares, and never hands out a view of its own bytes")
        void copySemantics() {
            FieldAttributes original = new FieldAttributes();
            original.setColour(BmsAttributes.DFHRED);
            FieldAttributes copy = new FieldAttributes(original);
            copy.setColour(BmsAttributes.DFHGREEN);

            assertThat(original.colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(copy.colour()).isEqualTo(BmsAttributes.DFHGREEN);

            byte[] image = original.toByteArray();
            image[0] = BmsAttributes.DFHBLUE;
            assertThat(original.colour()).isEqualTo(BmsAttributes.DFHRED);

            assertThatNullPointerException().isThrownBy(() -> new FieldAttributes(null));
            assertThatNullPointerException().isThrownBy(() -> original.fromByteArray(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> original.fromByteArray(new byte[] {0x00, 0x00}))
                    .withMessageContaining("4");
        }

        @Test
        @DisplayName("compares by value and renders the BMS mnemonics its bytes stand for")
        void valueSemanticsAndDiagnostics() {
            FieldAttributes first = new FieldAttributes();
            FieldAttributes second = new FieldAttributes();
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isEqualTo(first);
            assertThat(first).isNotEqualTo("not a quad");
            assertThat(first).isNotEqualTo(null);

            second.setColour(BmsAttributes.DFHRED);
            assertThat(first).isNotEqualTo(second);
            assertThat(second.toString()).contains("DFHRED", "DFHDFHI");
        }

        @Test
        @DisplayName("distinguishes a difference in each of the four bytes independently")
        void everyByteParticipatesInEquality() {
            List<java.util.function.Consumer<FieldAttributes>> mutations = List.of(
                    quad -> quad.setColour(BmsAttributes.DFHRED),
                    quad -> quad.setPs((byte) 0x0A),
                    quad -> quad.setHighlight(BmsAttributes.DFHUNDLN),
                    quad -> quad.setValidn((byte) 0x0B));
            for (java.util.function.Consumer<FieldAttributes> mutation : mutations) {
                FieldAttributes mutated = new FieldAttributes();
                mutation.accept(mutated);
                assertThat(mutated).isNotEqualTo(new FieldAttributes());
                assertThat(new FieldAttributes()).isNotEqualTo(mutated);
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("ScreenRow - WS-SCREEN-ROWS(n), 28 bytes")
    class Row {

        @Test
        @DisplayName("declares 11 + 16 + 1 = 28 bytes and round-trips its image")
        void widthsAndImage() {
            ScreenRow row = ScreenRow.of("00000000011", "4111111111111111", "Y");
            assertThat(row.rowAcctno()).hasSize(11);
            assertThat(row.rowCardNum()).hasSize(16);
            assertThat(row.rowCardStatus()).hasSize(1);
            assertThat(row.image()).hasSize(28).isEqualTo("000000000114111111111111111Y");
            assertThat(ScreenRow.fromImage(row.image())).isEqualTo(row);
        }

        @Test
        @DisplayName("distinguishes LOW-VALUES from spaces")
        void lowValuesIsNotSpaces() {
            assertThat(ScreenRow.lowValues().isLowValues()).isTrue();
            assertThat(ScreenRow.spaces().isLowValues()).isFalse();
            assertThat(ScreenRow.lowValues()).isNotEqualTo(ScreenRow.spaces());
            assertThat(ScreenRow.lowValues().image()).isEqualTo(low(28));
            assertThat(ScreenRow.spaces().image()).isEqualTo(sp(28));
            assertThat(ScreenRow.of("1", "1", "1").isLowValues()).isFalse();
        }

        @Test
        @DisplayName("applies the PIC X move rule in of() and enforces exact widths in the constructor")
        void moveRuleAndValidation() {
            ScreenRow padded = ScreenRow.of("1", "2", "3");
            assertThat(padded.rowAcctno()).isEqualTo("1" + " ".repeat(10));
            assertThat(padded.rowCardNum()).isEqualTo("2" + " ".repeat(15));
            assertThat(padded.rowCardStatus()).isEqualTo("3");

            ScreenRow truncated = ScreenRow.of("0".repeat(20), "4".repeat(20), "AB");
            assertThat(truncated.rowAcctno()).isEqualTo("0".repeat(11));
            assertThat(truncated.rowCardNum()).isEqualTo("4".repeat(16));
            assertThat(truncated.rowCardStatus()).isEqualTo("A");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ScreenRow("short", sp(16), "Y"))
                    .withMessageContaining("WS-ROW-ACCTNO");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ScreenRow(sp(11), "short", "Y"))
                    .withMessageContaining("WS-ROW-CARD-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new ScreenRow(sp(11), sp(16), "YY"))
                    .withMessageContaining("WS-ROW-CARD-STATUS");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ScreenRow(null, sp(16), "Y"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> ScreenRow.fromImage("too short"))
                    .withMessageContaining("WS-SCREEN-ROWS");
            assertThatNullPointerException().isThrownBy(() -> ScreenRow.fromImage(null));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("MapField - the descriptor's own contract")
    class Descriptor {

        @Test
        @DisplayName("derives every span from the item name, the width and the prefix offset")
        void spansAreDerived() {
            MapField field = CardListResponse.mapField(CardListResponse.PAGENOO_ITEM);
            assertThat(field.reservedSpan().length()).isEqualTo(3);
            assertThat(field.reservedSpan().offset()).isEqualTo(field.prefixOffset());
            assertThat(field.colourSpan().offset()).isEqualTo(field.prefixOffset() + 3);
            assertThat(field.psSpan().offset()).isEqualTo(field.prefixOffset() + 4);
            assertThat(field.highlightSpan().offset()).isEqualTo(field.prefixOffset() + 5);
            assertThat(field.validnSpan().offset()).isEqualTo(field.prefixOffset() + 6);
            assertThat(field.dataSpan().offset()).isEqualTo(field.dataOffset());
            assertThat(field.dataSpan().length()).isEqualTo(3);
            assertThat(field.dataSpan().name()).isEqualTo("PAGENOO");
            assertThat(field.colourOffset()).isEqualTo(field.prefixOffset() + 3);
            assertThat(field.endOffsetExclusive()).isEqualTo(field.dataOffset() + 3);
        }

        @Test
        @DisplayName("rejects a descriptor that could not come from this copybook")
        void constructorValidation() {
            assertThatNullPointerException().isThrownBy(() -> new MapField(null, 1, 1, 12));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MapField("TRNNAMEI", 4, 296, 12))
                    .withMessageContaining("does not end in");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MapField("O", 4, 296, 12))
                    .withMessageContaining("no DFHMDF label");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MapField("TRNNAMEO", 0, 296, 12))
                    .withMessageContaining("at least 1 byte");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MapField("TRNNAMEO", 4, 0, 12))
                    .withMessageContaining("1-based");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new MapField("TRNNAMEO", 4, 296, 11))
                    .withMessageContaining("TIOAPFX");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Page size - behaviour, not configuration (gate G39)")
    class PageSizeIsBehaviour {

        @Test
        @DisplayName("is exactly 7, from WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7")
        void pageSizeIsSeven() {
            assertThat(CardListResponse.PAGE_SIZE).isEqualTo(7);
            assertThat(CardListResponse.PAGE_SIZE).isEqualTo(CardListResponse.ROW_COUNT);
        }

        @Test
        @DisplayName("is a compile-time constant with no configuration path")
        void pageSizeIsACompileTimeConstant() throws ReflectiveOperationException {
            java.lang.reflect.Field field = CardListResponse.class.getField("PAGE_SIZE");
            assertThat(java.lang.reflect.Modifier.isStatic(field.getModifiers())).isTrue();
            assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue();
            assertThat(field.getType()).isEqualTo(int.class);
            assertThat(field.getAnnotations()).isEmpty();
        }

        @Test
        @DisplayName("holds no non-final static field anywhere, so no request can see another's state")
        void noMutableStaticState() {
            List<Class<?>> types = List.of(CardListResponse.class, MapField.class, ScreenRow.class,
                    PageCursor.class, FieldAttributes.class);
            for (Class<?> type : types) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                                .as("%s.%s must be final", type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Navigation - the three XCTL sites become opaque response fields (gate G40)")
    class Navigation {

        @Test
        @DisplayName("carries the literal target of the XCTL at COCRDLIC.cbl:402")
        void literalMenuTarget() {
            CardListResponse response = new CardListResponse();
            response.setNextTarget(CardListResponse.LIT_MENUPGM, "COMEN01", "COMEN1A");
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(response.getNextMapset()).isEqualTo("COMEN01");
            assertThat(response.getNextMap()).isEqualTo("COMEN1A");
        }

        @Test
        @DisplayName("carries the field target of the XCTLs at COCRDLIC.cbl:538 and :566")
        void fieldTargets() {
            CardListResponse detail = new CardListResponse();
            detail.setNextTarget("COCRDSLC", "COCRDSL", "CCRDSLA");
            assertThat(detail.getNextProgram()).isEqualTo("COCRDSLC");

            CardListResponse update = new CardListResponse();
            update.setNextTarget("COCRDUPC", "COCRDUP", "CCRDUPA");
            assertThat(update.getNextProgram()).isEqualTo("COCRDUPC");
        }

        @ParameterizedTest(name = "\"{0}\" is stored verbatim")
        @ValueSource(strings = {"cocrdslc", "  COMEN0", "CCRDSLA ", "12345678"})
        @DisplayName("stores a program token verbatim - no validation, no case folding, no trimming")
        void programTokensAreOpaque(String token) {
            CardListResponse response = new CardListResponse();
            response.setNextProgram(token);
            assertThat(response.getNextProgram()).isEqualTo(token).hasSize(8);
        }

        @Test
        @DisplayName("preserves COCRDSLC's 'CCRDSLA' map defect rather than correcting it")
        void mapDefectIsPreserved() {
            CardListResponse response = new CardListResponse();
            response.setNextMap("CCRDSLA");
            assertThat(response.getNextMap()).isEqualTo("CCRDSLA")
                    .isNotEqualTo(CardListResponse.LIT_THISMAP);
        }

        @Test
        @DisplayName("applies the PIC X move rule to a short or over-long token")
        void tokensObeyTheirDeclaredWidths() {
            CardListResponse response = new CardListResponse();
            response.setNextProgram("CO");
            assertThat(response.getNextProgram()).isEqualTo("CO      ");
            response.setNextProgram("COCRDSLCXX");
            assertThat(response.getNextProgram()).isEqualTo("COCRDSLC");

            response.setNextMapset("CO");
            assertThat(response.getNextMapset()).isEqualTo("CO     ");
            response.setNextMapset("COCRDSLXX");
            assertThat(response.getNextMapset()).isEqualTo("COCRDSL");

            response.setNextMap("CC");
            assertThat(response.getNextMap()).isEqualTo("CC     ");
            response.setNextMap("CCRDLIAXX");
            assertThat(response.getNextMap()).isEqualTo("CCRDLIA");
        }

        @Test
        @DisplayName("rejects a null token and a null carried area")
        void nullsAreRejected() {
            CardListResponse response = new CardListResponse();
            assertThatNullPointerException().isThrownBy(() -> response.setNextProgram(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMapset(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
            assertThatNullPointerException().isThrownBy(() -> response.setPageCursor(null));
            assertThatNullPointerException().isThrownBy(() -> response.setCardScreenState(null));
            assertThatNullPointerException().isThrownBy(() -> response.setNavigationContext(null));
        }

        @Test
        @DisplayName("carries the work area and the commarea, because COCRDLIC copies both")
        void carriedAreasAreReplaceable() {
            CardListResponse response = new CardListResponse();
            CardScreenState state = new CardScreenState();
            state.setCcAcctId("00000000011");
            NavigationContext context = NavigationContext.empty()
                    .withFromProgram(CardListResponse.LIT_THISPGM).withPgmReenter();

            response.setCardScreenState(state);
            response.setNavigationContext(context);

            assertThat(response.getCardScreenState().getCcAcctId()).isEqualTo("00000000011");
            assertThat(response.getNavigationContext().isReenter()).isTrue();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("PageCursor - the shared WS-THIS-PROGCOMMAREA carrier, 58 bytes (COCRDLIC.cbl:229-248)")
    class Cursor {

        @Test
        @DisplayName("the response declares no cursor type of its own - it references the shared one")
        void theResponseDeclaresNoCursorOfItsOwn() {
            assertThat(PageCursor.class.getEnclosingClass())
                    .as("01 WS-THIS-PROGCOMMAREA is one area, so one type models it; the response "
                            + "previously declared a second, flat, eight-component version that could "
                            + "not round-trip against the request's")
                    .isEqualTo(CardListRequest.class);
            assertThat(CardListResponse.class.getDeclaredClasses())
                    .noneMatch(nested -> nested.getSimpleName().equals("PageCursor"));
        }

        @Test
        @DisplayName("declares 27 + 27 + 1 + 1 + 1 + 1 = 58 bytes at fixed offsets")
        void geometry() {
            assertThat(16 + 11 + 16 + 11 + 1 + 1 + 1 + 1).isEqualTo(58);
            assertThat(CardListRequest.CURSOR_LENGTH).isEqualTo(58);
            assertThat(CardListRequest.CURSOR_CARD_NUM_LENGTH).isEqualTo(16);
            assertThat(CardListRequest.CURSOR_ACCT_ID_LENGTH).isEqualTo(11);
            assertThat(CardListRequest.SCREEN_NUM_LENGTH).isEqualTo(1);
            assertThat(CardListRequest.NEXT_PAGE_IND_LENGTH).isEqualTo(1);
            assertThat(CardListRequest.CARD_KEY_LENGTH).isEqualTo(27);
            assertThat(PageCursor.LAST_CARD_NUM_SPAN.offset()).isZero();
            assertThat(PageCursor.LAST_CARD_ACCT_ID_SPAN.offset()).isEqualTo(16);
            assertThat(PageCursor.FIRST_CARD_NUM_SPAN.offset()).isEqualTo(27);
            assertThat(PageCursor.FIRST_CARD_ACCT_ID_SPAN.offset()).isEqualTo(43);
            assertThat(PageCursor.SCREEN_NUM_SPAN.offset()).isEqualTo(54);
            assertThat(PageCursor.LAST_PAGE_DISPLAYED_SPAN.offset()).isEqualTo(55);
            assertThat(PageCursor.NEXT_PAGE_IND_SPAN.offset()).isEqualTo(56);
            assertThat(PageCursor.RETURN_FLAG_SPAN.offset()).isEqualTo(57);
            assertThat(PageCursor.LAYOUT.recordLength()).isEqualTo(58);
            assertThat(PageCursor.LAYOUT.spans()).hasSize(8);
        }

        @Test
        @DisplayName("the -OFF condition names hold for LOW-VALUES, which is the state the 88s declare")
        void theOffConditionNamesHoldForLowValues() {
            PageCursor cursor = lowValueCursor();

            assertThat(cursor.isNextPageNotExists()).isTrue();
            assertThat(cursor.isNextPageExists()).isFalse();
            assertThat(cursor.isReturnFlagOff()).isTrue();
            assertThat(cursor.isReturnFlagOn()).isFalse();
            assertThat(cursor.isFirstPage()).isFalse();
            assertThat(cursor.isLastPageShown()).isTrue();
            assertThat(cursor.isLastPageNotShown()).isFalse();
            assertThat(cursor.lastCardNum()).isEqualTo(low(16));
            assertThat(cursor.firstCardNum()).isEqualTo(low(16));
            assertThat(cursor.lastCardAcctId()).isZero();
            assertThat(cursor.firstCardAcctId()).isZero();
        }

        @Test
        @DisplayName("holds the -OFF names only for LOW-VALUES, never for a space")
        void aSpaceIsNotLowValues() {
            PageCursor spaced = PageCursor.initialised();

            assertThat(spaced.isNextPageNotExists())
                    .as("INITIALIZE leaves a SPACE and the 88-level tests LOW-VALUES, so the name "
                            + "does not hold - COCRDLIC.cbl:243 and :247")
                    .isFalse();
            assertThat(spaced.isReturnFlagOff()).isFalse();
        }

        @Test
        @DisplayName("firstPage() reproduces the after-INITIALIZE state of COCRDLIC.cbl:338, 341, 342")
        void firstPageReproducesTheInitializeState() {
            PageCursor cursor = PageCursor.firstPage();

            assertThat(cursor.lastCardNum()).isEqualTo(sp(16));
            assertThat(cursor.firstCardNum()).isEqualTo(sp(16));
            assertThat(cursor.lastCardAcctId()).isZero();
            assertThat(cursor.isFirstPage()).isTrue();
            assertThat(cursor.isLastPageNotShown()).isTrue();
            assertThat(cursor.isLastPageShown()).isFalse();
            assertThat(cursor.isNextPageNotExists()).as("INITIALIZE leaves a SPACE, not LOW-VALUES")
                    .isFalse();
            assertThat(cursor.isReturnFlagOff()).isFalse();
            assertThat(cursor).isNotEqualTo(lowValueCursor());
        }

        @Test
        @DisplayName("transitions through every withX method without disturbing the other items")
        void transitions() {
            PageCursor cursor = PageCursor.initialised()
                    .withLastCardKey(new CardKey("4111111111111111", 11L))
                    .withFirstCardKey(new CardKey("4000000000000000", 1L))
                    .withScreenNum(3)
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN)
                    .withNextPageExists()
                    .withReturnFlagOn();

            assertThat(cursor.lastCardNum()).isEqualTo("4111111111111111");
            assertThat(cursor.lastCardAcctId()).isEqualTo(11L);
            assertThat(cursor.firstCardNum()).isEqualTo("4000000000000000");
            assertThat(cursor.firstCardAcctId()).isEqualTo(1L);
            assertThat(cursor.screenNum()).isEqualTo(3);
            assertThat(cursor.isLastPageNotShown()).isTrue();
            assertThat(cursor.isNextPageExists()).isTrue();
            assertThat(cursor.isReturnFlagOn()).isTrue();
            assertThat(cursor.lastCardKey().declaredLength()).isEqualTo(27);

            assertThat(cursor.withLastPageDisplayed(CardListRequest.LAST_PAGE_SHOWN)
                    .isLastPageShown()).isTrue();
            assertThat(cursor.withNextPageNotExists().isNextPageNotExists()).isTrue();
            assertThat(cursor.withReturnFlagOff().isReturnFlagOff()).isTrue();
            assertThat(cursor.withScreenNum(1).isFirstPage()).isTrue();
            assertThat(cursor.withLastCardKeyFromFirst().lastCardKey())
                    .as("MOVE WS-CA-FIRST-CARDKEY TO WS-CA-LAST-CARDKEY, COCRDLIC.cbl:1268, is a "
                            + "wholesale group move")
                    .isEqualTo(cursor.firstCardKey());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"US-ASCII", "IBM037"})
        @DisplayName("round-trips its 58-byte image in both code pages")
        void imageRoundTrips(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            PageCursor cursor = PageCursor.initialised()
                    .withLastCardKey(new CardKey("4111111111111111", 99_999_999_999L))
                    .withFirstCardKey(new CardKey("4000000000000000", 0L))
                    .withScreenNum(9)
                    .withLastPageDisplayed(CardListRequest.LAST_PAGE_NOT_SHOWN)
                    .withNextPageExists()
                    .withReturnFlagOn();

            byte[] image = cursor.toFixedWidth(charset);

            assertThat(image).hasSize(58);
            assertThat(PageCursor.fromFixedWidth(image, charset)).isEqualTo(cursor);
        }

        @Test
        @DisplayName("zero-fills the PIC 9 spans on the left rather than rendering them as text")
        void numericSpansAreZeroFilled() {
            byte[] image = PageCursor.initialised()
                    .withLastCardKey(new CardKey(sp(16), 11L))
                    .toFixedWidth(ASCII);
            String text = new String(image, ASCII);

            assertThat(text.substring(16, 27)).isEqualTo("00000000011");
            assertThat(text.substring(43, 54)).isEqualTo("00000000000");
            assertThat(text.substring(54, 55)).isEqualTo("0");
            assertThat(text.substring(55, 56)).isEqualTo("0");
        }

        @Test
        @DisplayName("round-trips the initialised cursor, whose zeros are encoded as digits")
        void initialisedCursorRoundTrips() {
            byte[] image = PageCursor.initialised().toFixedWidth(ASCII);

            assertThat(PageCursor.fromFixedWidth(image, ASCII))
                    .isEqualTo(PageCursor.initialised());
        }

        @Test
        @DisplayName("the image the response writes is the image a request reads back")
        void theImageCrossesThePair() {
            CardListResponse response = populated();
            PageCursor written = response.getPageCursor();

            byte[] image = written.toFixedWidth(ASCII);
            CardListRequest request = new CardListRequest();
            request.setPageCursor(PageCursor.fromFixedWidth(image, ASCII));

            assertThat(request.getPageCursor())
                    .as("COCRDLIC.cbl:610-612 returns the area and :330-331 reads it back, so the "
                            + "bytes must survive the crossing unchanged")
                    .isEqualTo(written);
        }

        @Test
        @DisplayName("the JSON member crosses the pair unchanged, under the same name and shape")
        void theJsonMemberCrossesThePair() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            CardListResponse response = populated();

            String cursorJson = mapper.writeValueAsString(response.getPageCursor());
            PageCursor decoded = mapper.readValue(cursorJson, PageCursor.class);

            CardListRequest request = new CardListRequest();
            request.setPageCursor(decoded);

            assertThat(request.getPageCursor()).isEqualTo(response.getPageCursor());
            assertThat(mapper.valueToTree(response).get("pageCursor"))
                    .isEqualTo(mapper.valueToTree(request).get("pageCursor"));
        }

        @Test
        @DisplayName("rejects a component that cannot exist in the declared PICTURE")
        void validation() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(CardKey.lowValues(), CardKey.lowValues(), 0, 0,
                            "YY", "\u0000"))
                    .withMessageContaining("WS-CA-NEXT-PAGE-IND");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(CardKey.lowValues(), CardKey.lowValues(), 0, 0,
                            "Y", "11"))
                    .withMessageContaining("WS-RETURN-FLAG");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardKey(low(16), -1L))
                    .withMessageContaining("negative");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new CardKey(low(16), CardKey.MAX_ACCT_ID + 1))
                    .withMessageContaining("more than 11 digits");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(CardKey.lowValues(), CardKey.lowValues(), 10, 0,
                            "Y", "1"))
                    .withMessageContaining("WS-CA-SCREEN-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new PageCursor(CardKey.lowValues(), CardKey.lowValues(), 0, -1,
                            "Y", "1"))
                    .withMessageContaining("WS-CA-LAST-PAGE-DISPLAYED");
            assertThatNullPointerException()
                    .isThrownBy(() -> new PageCursor(null, CardKey.lowValues(), 0, 0, "Y", "1"));
            assertThatNullPointerException()
                    .isThrownBy(() -> new CardKey(null, 0L));
            assertThatNullPointerException()
                    .isThrownBy(() -> PageCursor.initialised().toFixedWidth(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> PageCursor.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> PageCursor.fromFixedWidth(new byte[58], null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PageCursor.fromFixedWidth(new byte[57], ASCII));
        }

        @Test
        @DisplayName("an over-width key is refused at the byte boundary, not silently truncated")
        void anOverWidthKeyIsRefusedAtTheByteBoundary() {
            PageCursor tooWide = PageCursor.initialised()
                    .withLastCardKey(new CardKey("4".repeat(20), 1L));

            assertThatIllegalArgumentException()
                    .as("writePicX refuses a surplus rather than dropping four digits of a card "
                            + "number into a 58-byte parity image")
                    .isThrownBy(() -> tooWide.toFixedWidth(ASCII));
        }

        @Test
        @DisplayName("a short key is stored verbatim and padded only by the explicit image step")
        void aShortKeyIsStoredVerbatimAndPaddedOnlyByTheImage() {
            PageCursor cursor = PageCursor.initialised()
                    .withLastCardKey(new CardKey("4", 2L));

            assertThat(cursor.lastCardNum())
                    .as("binding and carrying do not pad; the MOVE belongs to the image step")
                    .isEqualTo("4");
            assertThat(new String(cursor.toFixedWidth(ASCII), ASCII).substring(0, 16))
                    .isEqualTo("4" + " ".repeat(15));
        }

        /** The LOW-VALUES state - reachable by MOVE LOW-VALUES, and what the -OFF 88-levels test. */
        private PageCursor lowValueCursor() {
            return new PageCursor(CardKey.lowValues(), CardKey.lowValues(), 0,
                    CardListRequest.LAST_PAGE_SHOWN,
                    CardScreenState.lowValues(CardListRequest.NEXT_PAGE_IND_LENGTH),
                    CardScreenState.lowValues(CardListRequest.RETURN_FLAG_LENGTH));
        }
    }

    @Nested
    @DisplayName("Header population - 1100-SCREEN-INIT and 1400-SETUP-MESSAGE")
    class HeaderPopulation {

        @Test
        @DisplayName("moves the two 40-character titles verbatim, with their significant spaces")
        void titles() {
            CardListResponse response = new CardListResponse();
            response.applyScreenTitles();
            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
        }

        @Test
        @DisplayName("moves this program's own transaction identifier and program name")
        void programIdentity() {
            CardListResponse response = new CardListResponse();
            response.applyProgramIdentity();
            assertThat(response.getTrnnameo()).isEqualTo("CCLI");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDLIC");
        }

        @Test
        @DisplayName("moves the eight-character mm/dd/yy date and hh:mm:ss time")
        void dateAndTime() {
            DateHeader header = DateHeader.of(new FixedWidthCodec(ASCII),
                    LocalDateTime.of(2022, 7, 19, 23, 12, 33));
            CardListResponse response = new CardListResponse();
            response.applyDateHeader(header);
            assertThat(response.getCurdateo()).isEqualTo("07/19/22").hasSize(8);
            assertThat(response.getCurtimeo()).isEqualTo("23:12:33").hasSize(8);
            assertThatNullPointerException().isThrownBy(() -> response.applyDateHeader(null));
        }

        @ParameterizedTest(name = "page {0} renders as \"{1}\"")
        @CsvSource({"0,'0  '", "1,'1  '", "9,'9  '"})
        @DisplayName("left-justifies the page digit in PIC X(3) - the alphanumeric rule, not PIC 9")
        void pageNumberIsLeftJustified(int screenNum, String expected) {
            CardListResponse response = new CardListResponse();
            response.setPagenooFromScreenNum(screenNum);
            assertThat(response.getPagenoo()).isEqualTo(expected).hasSize(3);
            assertThat(response.getPagenoo()).isNotEqualTo("00" + screenNum);
        }

        @Test
        @DisplayName("takes the page number from the carried cursor")
        void pageNumberFollowsTheCursor() {
            CardListResponse response = new CardListResponse();
            response.setPageCursor(PageCursor.initialised().withScreenNum(4));
            response.applyPageNumberFromCursor();
            assertThat(response.getPagenoo()).isEqualTo("4  ");
        }

        @Test
        @DisplayName("rejects a page number a PIC 9(1) item cannot hold")
        void pageNumberRange() {
            CardListResponse response = new CardListResponse();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setPagenooFromScreenNum(10))
                    .withMessageContaining("WS-CA-SCREEN-NUM");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.setPagenooFromScreenNum(-1))
                    .withMessageContaining("no sign position");
        }

        @Test
        @DisplayName("truncates the PIC X(50) thank-you into INFOMSGO X(45) on the right")
        void thankYouIsTruncatedToFortyFive() {
            CardListResponse response = new CardListResponse();
            response.setInfomsgoThankYou();
            assertThat(response.getInfomsgo()).hasSize(45)
                    .isEqualTo(SystemMessages.CCDA_MSG_THANK_YOU.substring(0, 45))
                    .startsWith("Thank you for using CardDemo application...");
        }

        @Test
        @DisplayName("pads the PIC X(50) invalid-key message into ERRMSGO X(78) on the right")
        void invalidKeyIsPaddedToSeventyEight() {
            CardListResponse response = new CardListResponse();
            response.setErrmsgoInvalidKey();
            assertThat(response.getErrmsgo()).hasSize(78)
                    .isEqualTo(SystemMessages.CCDA_MSG_INVALID_KEY + " ".repeat(28))
                    .startsWith("Invalid key pressed. Please see below...");
        }

        @Test
        @DisplayName("reproduces 1100-SCREEN-INIT in source order, ending with DFHBMDAR on INFOMSGC")
        void screenInit() {
            DateHeader header = DateHeader.of(new FixedWidthCodec(ASCII),
                    LocalDateTime.of(2022, 7, 19, 23, 12, 33));
            CardListResponse response = new CardListResponse();
            response.setPageCursor(PageCursor.initialised().withScreenNum(1));
            response.setErrmsgo("stale error text");

            response.applyScreenInit(header, sp(45));

            assertThat(response.getTitle01o()).isEqualTo(ScreenTitles.CCDA_TITLE01);
            assertThat(response.getTitle02o()).isEqualTo(ScreenTitles.CCDA_TITLE02);
            assertThat(response.getTrnnameo()).isEqualTo("CCLI");
            assertThat(response.getPgmnameo()).isEqualTo("COCRDLIC");
            assertThat(response.getCurdateo()).isEqualTo("07/19/22");
            assertThat(response.getCurtimeo()).isEqualTo("23:12:33");
            assertThat(response.getPagenoo()).isEqualTo("1  ");
            assertThat(response.getInfomsgo()).isEqualTo(sp(45));
            assertThat(response.getErrmsgo()).as("L643 wipes the whole group first")
                    .isEqualTo(low(78));
            assertThat(response.fieldAttributes("INFOMSG").colour())
                    .isEqualTo(BmsAttributes.DFHBMDAR);

            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyScreenInit(null, sp(45)));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyScreenInit(header, null));
        }

        @Test
        @DisplayName("writes the error line unconditionally and lights INFOMSGC only when there is a message")
        void messages() {
            CardListResponse withInfo = new CardListResponse();
            withInfo.applyMessages("boom", "records found");
            assertThat(withInfo.getErrmsgo()).isEqualTo("boom" + " ".repeat(74));
            assertThat(withInfo.getInfomsgo()).isEqualTo("records found" + " ".repeat(32));
            assertThat(withInfo.fieldAttributes("INFOMSG").colour())
                    .isEqualTo(BmsAttributes.DFHNEUTR);

            CardListResponse withoutInfo = new CardListResponse();
            withoutInfo.applyMessages(sp(78), null);
            assertThat(withoutInfo.getErrmsgo()).isEqualTo(sp(78));
            assertThat(withoutInfo.getInfomsgo()).as("the guarded block at L926-930 is skipped")
                    .isEqualTo(low(45));
            assertThat(withoutInfo.fieldAttributes("INFOMSG").colour()).isEqualTo((byte) 0x00);

            assertThatNullPointerException()
                    .isThrownBy(() -> withoutInfo.applyMessages(null, null));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The 797-byte group image - SEND MAP FROM(CCRDLIAO)")
    class GroupImage {

        @ParameterizedTest(name = "{0}")
        @CsvSource({"US-ASCII", "IBM037"})
        @DisplayName("round-trips all 45 items and all 45 quads in both code pages")
        void roundTrips(String charsetName) {
            Charset charset = Charset.forName(charsetName);
            CardListResponse response = populated();

            byte[] image = response.toFixedWidth(charset);
            assertThat(image).hasSize(797);

            CardListResponse rebuilt = CardListResponse.fromFixedWidth(image, charset);
            assertThat(rebuilt.fieldImages()).isEqualTo(response.fieldImages());
            assertThat(rebuilt.fieldAttributesSnapshot())
                    .isEqualTo(response.fieldAttributesSnapshot());
            assertThat(rebuilt.toFixedWidth(charset)).isEqualTo(image);
        }

        @Test
        @DisplayName("keeps the TIOAPFX prefix and every FILLER X(3) at LOW-VALUES")
        void fillerSpansStayLowValues() {
            byte[] image = populated().toFixedWidth(ASCII);
            for (int i = 0; i < CardListResponse.TIOAPFX_LENGTH; i++) {
                assertThat(image[i]).as("TIOAPFX byte %d", i).isEqualTo((byte) 0x00);
            }
            for (MapField field : CardListResponse.MAP_FIELDS) {
                for (int i = 0; i < CardListResponse.RESERVED_SPAN_LENGTH; i++) {
                    assertThat(image[field.prefixOffset() + i])
                            .as("FILLER byte %d of %s", i, field.itemName())
                            .isEqualTo((byte) 0x00);
                }
            }
        }

        @Test
        @DisplayName("writes an attribute byte byte-exact, so DFHRED stays 0xF2 in either code page")
        void attributeBytesSurviveByteExact() {
            CardListResponse response = new CardListResponse();
            response.fieldAttributes("ACCTSID").setColour(BmsAttributes.DFHRED);
            response.fieldAttributes("ACCTSID").setHighlight(BmsAttributes.DFHUNDLN);
            MapField field = CardListResponse.mapFieldByPrefix("ACCTSID");

            for (Charset charset : List.of(ASCII, EBCDIC)) {
                byte[] image = response.toFixedWidth(charset);
                assertThat(image[field.colourOffset()]).isEqualTo(BmsAttributes.DFHRED)
                        .isEqualTo((byte) 0xF2);
                assertThat(image[field.highlightOffset()]).isEqualTo(BmsAttributes.DFHUNDLN);
                assertThat(image[field.psOffset()]).isEqualTo((byte) 0x00);
                assertThat(image[field.validnOffset()]).isEqualTo((byte) 0x00);
            }
        }

        @Test
        @DisplayName("places every payload item at its declared offset and width")
        void payloadItemsSitAtTheirDeclaredOffsets() {
            CardListResponse response = new CardListResponse();
            response.setTrnnameo("CCLI");
            response.setErrmsgo("E");
            byte[] image = response.toFixedWidth(ASCII);
            String text = new String(image, ASCII);

            MapField tranid = CardListResponse.mapField(CardListResponse.TRNNAMEO_ITEM);
            assertThat(text.substring(tranid.dataOffset(), tranid.endOffsetExclusive()))
                    .isEqualTo("CCLI");
            MapField error = CardListResponse.mapField(CardListResponse.ERRMSGO_ITEM);
            assertThat(error.endOffsetExclusive()).isEqualTo(797);
            assertThat(text.substring(error.dataOffset(), error.endOffsetExclusive()))
                    .isEqualTo("E" + " ".repeat(77));
        }

        @Test
        @DisplayName("writes into a caller's record area, wiping it to LOW-VALUES first")
        void writeIntoWipesFirst() {
            FixedWidthRecord record = new FixedWidthRecord(797, ASCII);
            record.fill(0, 797, (byte) 'Z');
            CardListResponse response = new CardListResponse();
            response.setTrnnameo("CCLI");

            response.writeInto(record);

            byte[] image = record.toByteArray();
            assertThat(image[0]).isEqualTo((byte) 0x00);
            assertThat(CardListResponse.readFrom(record).getTrnnameo()).isEqualTo("CCLI");
        }

        @Test
        @DisplayName("rejects an area or an image of the wrong width, and a null charset")
        void widthAndNullChecks() {
            CardListResponse response = new CardListResponse();
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> response.writeInto(new FixedWidthRecord(796, ASCII)))
                    .withMessageContaining("797");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardListResponse.readFrom(new FixedWidthRecord(10, ASCII)))
                    .withMessageContaining("TIOAPFX");
            assertThatNullPointerException()
                    .isThrownBy(() -> CardListResponse.readFrom(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardListResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> CardListResponse.fromFixedWidth(new byte[797], null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> CardListResponse.fromFixedWidth(new byte[796], ASCII));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Value semantics and diagnostics")
    class ValueSemantics {

        @Test
        @DisplayName("compares every part and distinguishes a difference in any one of them")
        void equality() {
            assertThat(new CardListResponse()).isEqualTo(new CardListResponse())
                    .hasSameHashCodeAs(new CardListResponse());
            CardListResponse response = new CardListResponse();
            assertThat(response).isEqualTo(response);
            assertThat(response).isNotEqualTo("not a response");
            assertThat(response).isNotEqualTo(null);

            List<java.util.function.Consumer<CardListResponse>> mutations = List.of(
                    r -> r.setTrnnameo("CCLI"),
                    r -> r.fieldAttributes("ACCTSID").setColour(BmsAttributes.DFHRED),
                    r -> r.setWsRowCrdselectError(1, "1"),
                    r -> r.setNextProgram("COMEN01C"),
                    r -> r.setNextMapset("COMEN01"),
                    r -> r.setNextMap("COMEN1A"),
                    r -> r.setPageCursor(PageCursor.firstPage()),
                    r -> r.setCardScreenState(differentState()),
                    r -> r.setNavigationContext(NavigationContext.empty().withPgmReenter()));
            for (java.util.function.Consumer<CardListResponse> mutation : mutations) {
                CardListResponse mutated = new CardListResponse();
                mutation.accept(mutated);
                assertThat(mutated).isNotEqualTo(new CardListResponse());
            }
        }

        private CardScreenState differentState() {
            CardScreenState state = new CardScreenState();
            state.setCcAcctId("00000000099");
            return state;
        }

        @Test
        @DisplayName("copies deeply, so a write through one response is invisible through the other")
        void deepCopyIsIndependent() {
            CardListResponse original = populated();
            CardListResponse copy = new CardListResponse(original);
            assertThat(copy).isEqualTo(original);

            copy.setTrnnameo("XXXX");
            copy.fieldAttributes("CRDSEL1").setColour(BmsAttributes.DFHGREEN);
            copy.setWsRowCrdselectError(7, "1");
            copy.getCardScreenState().setCcAcctId("00000000042");

            assertThat(original.getTrnnameo()).isEqualTo("CCLI");
            assertThat(original.fieldAttributes("CRDSEL1").colour())
                    .isEqualTo(BmsAttributes.DFHRED);
            assertThat(original.isWsRowSelectError(7)).isFalse();
            assertThat(original.getCardScreenState().getCcAcctId()).isNotEqualTo("00000000042");

            assertThatNullPointerException().isThrownBy(() -> new CardListResponse(null));
        }

        @Test
        @DisplayName("the disclosure policy names all 14 row fields and both filters")
        void theDisclosurePolicyNamesEveryCardField() {
            // COCRDLI is the densest concentration of payment data on any screen: 7 card numbers and 7
            // account numbers per page. The stems come from the mapset itself, so a new field would need
            // a new stem and could not silently match.
            for (int row = 1; row <= 7; row++) {
                assertThat(CardListResponse.disclosureOf("CRDNUM" + row + "O"))
                        .isEqualTo(SensitiveDiagnostics.Disclosure.PAN);
                assertThat(CardListResponse.disclosureOf("ACCTNO" + row + "O"))
                        .isEqualTo(SensitiveDiagnostics.Disclosure.IDENTIFIER);
                assertThat(CardListResponse.disclosureOf("CRDSTS" + row + "O"))
                        .as("a status column identifies nobody and stays legible")
                        .isEqualTo(SensitiveDiagnostics.Disclosure.PLAIN);
            }
            assertThat(CardListResponse.disclosureOf("CARDSIDO"))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.PAN);
            assertThat(CardListResponse.disclosureOf("ACCTSIDO"))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.IDENTIFIER);
            assertThat(CardListResponse.disclosureOf("TITLE01O"))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.PLAIN);
        }

        @Test
        @DisplayName("an unnamed field is withheld, not published")
        void anUnnamedFieldIsWithheld() {
            assertThat(CardListResponse.disclosureOf(null))
                    .isEqualTo(SensitiveDiagnostics.Disclosure.REDACTED_VALUE);
        }

        @Test
        @DisplayName("renders every field verbatim, masking nothing - not even a card number")
        void toStringIsVerbatim() {
            CardListResponse response = populated();
            String text = response.toString();

            assertThat(text).startsWith("CardListResponse[").endsWith("]");
            for (MapField field : CardListResponse.MAP_FIELDS) {
                assertThat(text).as("names %s", field.itemName()).contains(field.itemName() + "=");
            }
            assertThat(text)
                    .as("COCRDLI carries 7 card numbers and 7 account numbers per page; none of the "
                            + "14 may reach a log line (CWE-532)")
                    .doesNotContain("4111111111111111")
                    .contains("************1111")
                    .contains("WS-EDIT-SELECT-ERROR-FLAGS='")
                    .contains("nextProgram='COMEN01C")
                    .contains("pageCursor=")
                    .contains("TRNNAMEO='CCLI'");
        }

        @Test
        @DisplayName("redaction is confined to toString - the JSON body and the image are untouched")
        void redactionIsConfinedToTheDiagnostic() throws Exception {
            CardListResponse response = populated();

            JsonNode body = new ObjectMapper().valueToTree(response);
            String image = new String(response.toFixedWidth(ASCII), ASCII);

            assertThat(body.get("crdnum1o").asText())
                    .as("the 3270 shows the number in the clear and the payload must too")
                    .isEqualTo("4111111111111111");
            assertThat(body.get("cardsido").asText()).isEqualTo("4111111111111111");
            assertThat(image).contains("4111111111111111");
            assertThat(body.toString()).doesNotContain("REDACTED");
            assertThat(response.getCrdnum1o()).isEqualTo("4111111111111111");
            assertThat(response.getCardsido()).isEqualTo("4111111111111111");
        }
    }

    // =================================================================================================

    /**
     * The presentation metadata this response publishes, which is the whole of finding F3 for this
     * screen: the 45 attribute quads and the {@code MOVE -1} cursor request exist in the COBOL, are
     * written by three paragraphs, and had no way at all to reach a client.
     *
     * <p>They are not payload members - {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} are
     * metadata by {@code app/cpy-bms/COCRDLI.CPY}'s own declaration, and the {@code -1} marker is moved
     * into the <em>input</em> group {@code CCRDLIAI} - so they travel in the shared
     * {@link ScreenMetadata} envelope beside the screen rather than as siblings of the 45 values.
     */
    @Nested
    @DisplayName("ScreenMetadata - the 45 quads and the cursor request, published beside the screen")
    class Metadata {

        @Test
        @DisplayName("every one of the 45 quads is projected, keyed by DFHMDF label in copybook order")
        void allFortyFiveQuadsAreProjected() {
            CardListResponse response = new CardListResponse();

            ScreenMetadata metadata = response.screenMetadata();

            assertThat(metadata.fields()).hasSize(CardListResponse.PAYLOAD_FIELD_COUNT);
            assertThat(metadata.fields().keySet())
                    .containsExactlyElementsOf(response.fieldAttributesSnapshot().keySet());
            assertThat(metadata.fields()).containsKeys("ACCTSID", "CARDSID", "ERRMSG", "CRDSEL1",
                    "CRDSTP2");
            // CRDSTP1 does not exist on this map - row one has no hidden selection-type field - so it
            // must not appear here either.
            assertThat(metadata.fields()).doesNotContainKey("CRDSTP1");
        }

        @Test
        @DisplayName("an initialised response projects LOW-VALUES, which is 0 in every quad")
        void theInitialisedStateIsAllZeroes() {
            ScreenMetadata metadata = new CardListResponse().screenMetadata();

            assertThat(metadata.fields().values())
                    .allSatisfy(quad -> assertThat(quad)
                            .isEqualTo(new ScreenMetadata.FieldMetadata(0, 0, 0, 0)));
            assertThat(metadata.messageColour()).isZero();
            assertThat(metadata.cursorField()).isNull();
            assertThat(metadata.resetAllOutputFields()).isFalse();
        }

        @Test
        @DisplayName("a quad the COBOL wrote is projected as its UNSIGNED byte value")
        void aWrittenQuadIsProjectedUnsigned() {
            CardListResponse response = new CardListResponse();
            response.fieldAttributes("ACCTSID").setColour(BmsAttributes.DFHRED);
            response.fieldAttributes("ACCTSID").setPs(BmsAttributes.DFHBMPRO);
            response.fieldAttributes("ERRMSG").setColour(BmsAttributes.DFHRED);

            ScreenMetadata metadata = response.screenMetadata();

            // DFHRED is 0xF2, which is -14 as a signed Java byte. 242 is the value a client can act on.
            assertThat(metadata.fields().get("ACCTSID").colour()).isEqualTo(242);
            assertThat(metadata.fields().get("ACCTSID").protection())
                    .isEqualTo(BmsAttributes.unsigned(BmsAttributes.DFHBMPRO));
            // messageColour is ERRMSGC read from the quads, not a second copy of it.
            assertThat(metadata.messageColour()).isEqualTo(242);
        }

        @Test
        @DisplayName("the MOVE -1 cursor request travels, because no payload field can carry it")
        void theCursorRequestTravels() {
            CardListResponse response = new CardListResponse();
            assertThat(response.getCursorField()).isNull();

            response.setCursorField("ACCTSID");

            assertThat(response.getCursorField()).isEqualTo("ACCTSID");
            assertThat(response.screenMetadata().cursorField()).isEqualTo("ACCTSID");
        }

        @Test
        @DisplayName("the cursor request is copied with the response, so two responses never share it")
        void theCursorRequestIsCopied() {
            CardListResponse painted = new CardListResponse();
            painted.setCursorField("CARDSID");

            CardListResponse copy = new CardListResponse(painted);
            painted.setCursorField("ACCTSID");

            assertThat(copy.getCursorField()).isEqualTo("CARDSID");
            assertThat(painted.getCursorField()).isEqualTo("ACCTSID");
        }

        @Test
        @DisplayName("the projection is a snapshot: writing to the response afterwards changes nothing")
        void theProjectionIsASnapshot() {
            CardListResponse response = new CardListResponse();
            ScreenMetadata before = response.screenMetadata();

            response.fieldAttributes("ERRMSG").setColour(BmsAttributes.DFHRED);

            assertThat(before.messageColour()).isZero();
            assertThat(response.screenMetadata().messageColour()).isEqualTo(242);
        }

        @Test
        @DisplayName("MOVE LOW-VALUES TO CCRDLIAO clears the quads, and the projection follows")
        void clearingTheMapClearsTheQuads() {
            CardListResponse response = new CardListResponse();
            response.fieldAttributes("ERRMSG").setColour(BmsAttributes.DFHRED);

            response.moveLowValuesToMap();

            assertThat(response.screenMetadata().messageColour()).isZero();
        }

        @Test
        @DisplayName("no metadata member reaches the payload JSON - the envelope is the only route")
        void noQuadIsAPayloadMember() throws Exception {
            CardListResponse response = new CardListResponse();
            response.setCursorField("ACCTSID");
            response.fieldAttributes("ACCTSID").setColour(BmsAttributes.DFHRED);

            JsonNode json = new ObjectMapper().valueToTree(response);

            assertThat(json.has("cursorField")).isFalse();
            assertThat(json.has("screenMetadata")).isFalse();
            assertThat(json.has("acctsidc")).isFalse();
            assertThat(json.has("fieldAttributesSnapshot")).isFalse();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("JSON projection - 45 payload members plus the named carriers, and no metadata")
    class JsonProjection {

        @Test
        @DisplayName("serialises exactly the 45 payload members and the six carriers")
        void wireShape() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> json = mapper.convertValue(populated(), Map.class);

            Set<String> expected = new LinkedHashSet<>();
            for (MapField field : CardListResponse.MAP_FIELDS) {
                expected.add(field.itemName().toLowerCase(java.util.Locale.ROOT));
            }
            expected.addAll(List.of("nextProgram", "nextMapset", "nextMap", "pageCursor",
                    "cardScreenState", "navigationContext"));

            assertThat(json.keySet()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(json).hasSize(45 + 6);
        }

        @Test
        @DisplayName("keeps the attribute quads, the row-error flags and every derived view off the wire")
        void metadataIsExcluded() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(populated());
            assertThat(json).doesNotContain("attributes", "editSelectErrorFlags", "fieldImages",
                    "fieldAttributesSnapshot", "screenRows", "allRowsImage", "payloadFieldCount",
                    "editSelectFlags", "lowValues", "caFirstPage", "caNextPageExists",
                    "wsReturnFlagOff");
        }

        @Test
        @DisplayName("carries a full card number in the clear, exactly as the symbolic map does")
        void cardNumbersAreNotMasked() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(populated());
            assertThat(json).contains("\"crdnum1o\":\"4111111111111111\"")
                    .contains("\"acctno1o\":\"00000000011\"");
        }
    }

    /**
     * A response with something in every part, so that a round trip or a copy has something to lose.
     *
     * @return the populated response; never {@code null}
     */
    private static CardListResponse populated() {
        CardListResponse response = new CardListResponse();
        response.applyProgramIdentity();
        response.applyScreenTitles();
        response.setCurdateo("07/19/22");
        response.setCurtimeo("23:12:33");
        response.setPagenooFromScreenNum(1);
        response.setAcctsido("00000000011");
        response.setCardsido("4111111111111111");
        for (int row = 1; row <= 7; row++) {
            response.setScreenRow(row, ScreenRow.of("0000000001" + row,
                    "411111111111111" + row, "Y"));
            response.setEditSelect(row, CardListResponse.SPACE);
        }
        response.setScreenRow(1, ScreenRow.of("00000000011", "4111111111111111", "Y"));
        response.setEditSelect(1, CardListResponse.SELECT_VIEW);
        response.setInfomsgoThankYou();
        response.setErrmsgoInvalidKey();
        response.fieldAttributes("CRDSEL1").setColour(BmsAttributes.DFHRED);
        response.fieldAttributes("INFOMSG").setHighlight(BmsAttributes.DFHUNDLN);
        response.setWsRowCrdselectError(1, CardListResponse.ROW_SELECT_ERROR);
        response.setNextTarget(CardListResponse.LIT_MENUPGM, "COMEN01", "COMEN1A");
        response.setPageCursor(PageCursor.initialised().withScreenNum(1).withNextPageExists());
        return response;
    }
}
