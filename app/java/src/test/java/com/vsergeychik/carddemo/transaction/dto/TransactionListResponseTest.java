package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.FieldAttributes;
import com.vsergeychik.carddemo.transaction.dto.TransactionListResponse.TransactionListCursor;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link TransactionListResponse} against {@code app/cpy-bms/COTRN00.CPY},
 * {@code app/bms/COTRN00.bms} and {@code app/cbl/COTRN00C.cbl}.
 *
 * <p>The expectations here are transcribed from those sources rather than read back out of the class
 * under test, which is the whole point: the field names, the widths and the byte totals are all
 * spelled out independently, so a normalised suffix or a mistyped width fails here instead of
 * agreeing with itself.
 */
@DisplayName("TransactionListResponse - the COTRN0AO projection of CICS transaction CT00")
class TransactionListResponseTest {

    /** The code page every test states explicitly; none relies on the platform default. */
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    /** The codec the paragraph reproductions and the image tests use. */
    private static final FixedWidthCodec CODEC = new FixedWidthCodec(ASCII);

    /**
     * The 59 payload item names, in copybook order, transcribed by hand from the {@code xxxO} items
     * of {@code 01 COTRN0AO REDEFINES COTRN0AI} at {@code app/cpy-bms/COTRN00.CPY:373}.
     *
     * <p>The suffix widths differ between columns and are written out here exactly as the copybook
     * spells them: {@code SEL} four digits, {@code TRNID}, {@code TDATE} and {@code TDESC} two, and
     * {@code TAMT} three.
     */
    private static final List<String> EXPECTED_ITEM_NAMES = List.of(
            "TRNNAMEO", "TITLE01O", "CURDATEO", "PGMNAMEO", "TITLE02O", "CURTIMEO", "PAGENUMO",
            "TRNIDINO",
            "SEL0001O", "TRNID01O", "TDATE01O", "TDESC01O", "TAMT001O",
            "SEL0002O", "TRNID02O", "TDATE02O", "TDESC02O", "TAMT002O",
            "SEL0003O", "TRNID03O", "TDATE03O", "TDESC03O", "TAMT003O",
            "SEL0004O", "TRNID04O", "TDATE04O", "TDESC04O", "TAMT004O",
            "SEL0005O", "TRNID05O", "TDATE05O", "TDESC05O", "TAMT005O",
            "SEL0006O", "TRNID06O", "TDATE06O", "TDESC06O", "TAMT006O",
            "SEL0007O", "TRNID07O", "TDATE07O", "TDESC07O", "TAMT007O",
            "SEL0008O", "TRNID08O", "TDATE08O", "TDESC08O", "TAMT008O",
            "SEL0009O", "TRNID09O", "TDATE09O", "TDESC09O", "TAMT009O",
            "SEL0010O", "TRNID10O", "TDATE10O", "TDESC10O", "TAMT010O",
            "ERRMSGO");

    /**
     * The 59 declared widths, in the same order, transcribed from the {@code PIC X(n)} clauses and
     * independently equal to the {@code DFHMDF LENGTH=} of the matching name-labelled field.
     */
    private static final List<Integer> EXPECTED_WIDTHS = List.of(
            4, 40, 8, 8, 40, 8, 8, 16,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            1, 16, 8, 26, 12,
            78);

    /** Supplies {@code (itemName, width)} pairs for the field-by-field parameterised checks. */
    private static List<org.junit.jupiter.params.provider.Arguments> declaredFields() {
        List<org.junit.jupiter.params.provider.Arguments> arguments = new ArrayList<>();
        for (int index = 0; index < EXPECTED_ITEM_NAMES.size(); index++) {
            arguments.add(org.junit.jupiter.params.provider.Arguments.of(
                    EXPECTED_ITEM_NAMES.get(index), EXPECTED_WIDTHS.get(index)));
        }
        return arguments;
    }

    /** A response with every header field populated and every row filled, for the image tests. */
    private static TransactionListResponse fullyPopulated() {
        TransactionListResponse response = new TransactionListResponse();
        response.populateHeaderInfo(DateHeader.of(CODEC, LocalDateTime.of(2022, 7, 18, 3, 4, 5)));
        for (int row = TransactionListResponse.FIRST_ROW;
                row <= TransactionListResponse.LAST_ROW; row++) {
            response.populateTranData(CODEC, row,
                    String.format("%016d", row),
                    "07/18/22",
                    "Transaction description row " + row,
                    String.format("+%08d.99", row));
            response.setRowSelection(row, " ");
        }
        response.movePageNumberToScreen(CODEC, 3);
        response.moveMessageToErrorLine(CODEC, "All rows displayed");
        return response;
    }

    @Nested
    @DisplayName("The field set: exactly 59 named DFHMDF definitions, verbatim")
    class FieldSet {

        @Test
        @DisplayName("59 payload fields, reconciling as 8 header + 10 rows x 5 + 1 error line")
        void fieldCountReconciles() {
            assertThat(TransactionListResponse.FIELD_COUNT).isEqualTo(59);
            assertThat(TransactionListResponse.HEADER_FIELD_COUNT).isEqualTo(8);
            assertThat(TransactionListResponse.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionListResponse.ERROR_FIELD_COUNT).isEqualTo(1);
            assertThat(TransactionListResponse.HEADER_FIELD_COUNT
                    + TransactionListResponse.ROW_COUNT * TransactionListResponse.ROW_FIELD_COUNT
                    + TransactionListResponse.ERROR_FIELD_COUNT).isEqualTo(59);
        }

        @Test
        @DisplayName("the payload item names match the copybook exactly, in copybook order")
        void itemNamesMatchTheCopybook() {
            assertThat(TransactionListResponse.payloadFieldNames())
                    .containsExactlyElementsOf(EXPECTED_ITEM_NAMES);
        }

        @Test
        @DisplayName("the field prefixes are the item names without their trailing O")
        void prefixesMatchTheItemNames() {
            List<String> expected = EXPECTED_ITEM_NAMES.stream()
                    .map(name -> name.substring(0, name.length() - 1))
                    .toList();
            assertThat(TransactionListResponse.fieldPrefixes())
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("SEL0001O..SEL0010O carry FOUR digits - never SEL01O")
        void selectorSuffixesAreFourDigits() {
            for (int row = 1; row <= 10; row++) {
                String expected = "SEL" + String.format("%04d", row) + "O";
                assertThat(TransactionListResponse.rowFieldNames(row).get(0)).isEqualTo(expected);
                assertThat(TransactionListResponse.payloadFieldNames()).contains(expected);
            }
            assertThat(TransactionListResponse.payloadFieldNames())
                    .doesNotContain("SEL01O", "SEL1O", "SEL001O", "SEL00010O");
        }

        @Test
        @DisplayName("TRNIDnnO, TDATEnnO and TDESCnnO carry TWO digits")
        void rowSuffixesAreTwoDigits() {
            for (int row = 1; row <= 10; row++) {
                String twoDigits = String.format("%02d", row);
                assertThat(TransactionListResponse.rowFieldNames(row).subList(1, 4))
                        .containsExactly("TRNID" + twoDigits + "O",
                                "TDATE" + twoDigits + "O",
                                "TDESC" + twoDigits + "O");
            }
            assertThat(TransactionListResponse.payloadFieldNames())
                    .doesNotContain("TRNID001O", "TDATE001O", "TDESC001O", "TRNID1O");
        }

        @Test
        @DisplayName("TAMT001O..TAMT010O carry THREE digits - never TAMT01O")
        void amountSuffixesAreThreeDigits() {
            for (int row = 1; row <= 10; row++) {
                String expected = "TAMT" + String.format("%03d", row) + "O";
                assertThat(TransactionListResponse.rowFieldNames(row).get(4)).isEqualTo(expected);
            }
            assertThat(TransactionListResponse.payloadFieldNames())
                    .doesNotContain("TAMT01O", "TAMT1O", "TAMT0001O");
        }

        @ParameterizedTest(name = "{0} is PIC X({1})")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("every declared width equals the xxxO PICTURE and the DFHMDF LENGTH")
        void declaredWidthsMatch(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThat(TransactionListResponse.declaredLength(prefix)).isEqualTo(width);
            assertThat(TransactionListResponse.outputItemName(prefix)).isEqualTo(itemName);
            assertThat(TransactionListResponse.colourItemName(prefix)).isEqualTo(prefix + "C");
        }

        @ParameterizedTest(name = "{0} initialises to {1} spaces and reads back untrimmed")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("every field starts as spaces at its declared width - never null")
        void everyFieldStartsAsSpaces(String itemName, int width) {
            TransactionListResponse response = new TransactionListResponse();
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThat(response.payloadValue(prefix)).isEqualTo(" ".repeat(width));
        }

        @ParameterizedTest(name = "{0} rejects a value one character too wide")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("an over-wide value is rejected, never silently truncated")
        void overWideValuesAreRejected(String itemName, int width) {
            TransactionListResponse response = new TransactionListResponse();
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setPayloadValue(prefix, "x".repeat(width + 1)))
                    .withMessageContaining(itemName)
                    .withMessageContaining("movePicX");
        }

        @Test
        @DisplayName("a null value is rejected: there is no null in a COBOL record")
        void nullValuesAreRejected() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setErrmsgO(null))
                    .withMessageContaining("ERRMSGO");
        }

        @Test
        @DisplayName("an unknown prefix is rejected, including the plausible-looking TAMT01")
        void unknownPrefixesAreRejected() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.payloadValue("TAMT01"))
                    .withMessageContaining("not a field of COTRN0AO");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.payloadValue(null));
        }

        @Test
        @DisplayName("the flat accessors and the generic accessors see the same storage")
        void flatAndGenericAccessorsAgree() {
            TransactionListResponse response = new TransactionListResponse();
            response.setTamt001O("+00000001.23");
            assertThat(response.payloadValue("TAMT001")).isEqualTo("+00000001.23");
            response.setPayloadValue("TAMT001", "-00000009.99");
            assertThat(response.getTamt001O()).isEqualTo("-00000009.99");
            assertThat(response.payloadFieldValues())
                    .containsEntry("TAMT001O", "-00000009.99")
                    .hasSize(59);
            assertThat(response.payloadFieldValues().keySet())
                    .containsExactlyElementsOf(EXPECTED_ITEM_NAMES);
        }
    }

    @Nested
    @DisplayName("Byte arithmetic: the group image is 1265 bytes and the layout proves it")
    class ByteArithmetic {

        @Test
        @DisplayName("the payload widths sum to 840 = 132 + 630 + 78")
        void payloadWidthsSum() {
            assertThat(EXPECTED_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(840);
            assertThat(TransactionListResponse.HEADER_WIDTH_TOTAL).isEqualTo(132);
            assertThat(TransactionListResponse.ROW_WIDTH).isEqualTo(63);
            assertThat(TransactionListResponse.ROW_BLOCK_WIDTH).isEqualTo(630);
            assertThat(TransactionListResponse.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(TransactionListResponse.PAYLOAD_WIDTH_TOTAL).isEqualTo(840);
        }

        @Test
        @DisplayName("the group image is 12 + 59 x 7 + 840 = 1265 bytes")
        void groupImageWidth() {
            assertThat(TransactionListResponse.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionListResponse.ATTRIBUTE_FILLER_LENGTH).isEqualTo(3);
            assertThat(TransactionListResponse.ATTRIBUTE_ITEM_COUNT).isEqualTo(4);
            assertThat(TransactionListResponse.ATTRIBUTE_ITEM_LENGTH).isEqualTo(1);
            assertThat(TransactionListResponse.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(TransactionListResponse.RECORD_LENGTH).isEqualTo(1265);
            assertThat(12 + 59 * 7 + 840).isEqualTo(1265);
        }

        @Test
        @DisplayName("the layout declares 355 spans and self-checks to 1265 bytes")
        void layoutGeometry() {
            assertThat(TransactionListResponse.LAYOUT.recordLength()).isEqualTo(1265);
            assertThat(TransactionListResponse.LAYOUT.spans()).hasSize(1 + 59 * 6);
            assertThat(TransactionListResponse.LAYOUT.storageSpans()
                    .stream().mapToInt(FixedWidthRecord.FieldSpan::length).sum()).isEqualTo(1265);
            assertThat(TransactionListResponse.LAYOUT.redefinitions()).isEmpty();
        }

        @ParameterizedTest(name = "{0} and its four attribute items are spans of the layout")
        @MethodSource("com.vsergeychik.carddemo.transaction.dto."
                + "TransactionListResponseTest#declaredFields")
        @DisplayName("the layout carries every payload item and every attribute item by name")
        void layoutCarriesEveryItem(String itemName, int width) {
            String prefix = itemName.substring(0, itemName.length() - 1);
            assertThat(TransactionListResponse.LAYOUT.hasSpan(itemName)).isTrue();
            assertThat(TransactionListResponse.LAYOUT.span(itemName).length()).isEqualTo(width);
            for (String suffix : List.of("C", "P", "H", "V")) {
                assertThat(TransactionListResponse.LAYOUT.hasSpan(prefix + suffix)).isTrue();
                assertThat(TransactionListResponse.LAYOUT.span(prefix + suffix).length())
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("the first payload item starts at 12 + 7 = 19, after the TIOAPFX prefix")
        void firstPayloadOffset() {
            assertThat(TransactionListResponse.LAYOUT.span("TRNNAMEO").offset()).isEqualTo(19);
            assertThat(TransactionListResponse.LAYOUT.span("TRNNAMEC").offset()).isEqualTo(15);
        }

        @Test
        @DisplayName("the last payload item ends exactly at 1265")
        void lastPayloadOffset() {
            FixedWidthRecord.FieldSpan errmsg = TransactionListResponse.LAYOUT.span("ERRMSGO");
            assertThat(errmsg.endOffsetExclusive()).isEqualTo(1265);
        }

        @Test
        @DisplayName("the cursor is 58 bytes and the commarea it extends is 160 + 58 = 218")
        void cursorArithmetic() {
            assertThat(TransactionListCursor.CURSOR_LENGTH).isEqualTo(58);
            assertThat(16 + 16 + 8 + 1 + 1 + 16).isEqualTo(58);
            assertThat(TransactionListCursor.COMMAREA_WITH_CURSOR_LENGTH).isEqualTo(218);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(TransactionListCursor.LAYOUT.recordLength()).isEqualTo(58);
            assertThat(TransactionListCursor.LAYOUT.spans()).hasSize(6);
        }
    }

    @Nested
    @DisplayName("Identity: transaction CT00, program COTRN00C, mapset COTRN00, map COTRN0A")
    class Identity {

        @Test
        @DisplayName("the four names are the source literals")
        void names() {
            assertThat(TransactionListResponse.TRANSACTION_ID).isEqualTo("CT00");
            assertThat(TransactionListResponse.PROGRAM_NAME).isEqualTo("COTRN00C");
            assertThat(TransactionListResponse.MAPSET_NAME).isEqualTo("COTRN00");
            assertThat(TransactionListResponse.MAP_NAME).isEqualTo("COTRN0A");
            assertThat(TransactionListResponse.OUTPUT_MAP_GROUP_NAME).isEqualTo("COTRN0AO");
        }

        @Test
        @DisplayName("the mapset and map names fit CDEMO-LAST-MAPSET and CDEMO-LAST-MAP, both X(7)")
        void mapNamesFitTheirCommareaFields() {
            assertThat(TransactionListResponse.MAPSET_NAME)
                    .hasSize(NavigationContext.LAST_MAPSET_LENGTH);
            assertThat(TransactionListResponse.MAP_NAME)
                    .hasSize(NavigationContext.LAST_MAP_LENGTH);
            assertThat(NavigationContext.LAST_MAP_LENGTH).isEqualTo(7);
            assertThat(NavigationContext.LAST_MAPSET_LENGTH).isEqualTo(7);
        }

        @Test
        @DisplayName("the item suffixes come from FieldAttributeSetter so the two cannot disagree")
        void suffixesAreShared() {
            assertThat(TransactionListResponse.COLOUR_ITEM_SUFFIX)
                    .isEqualTo(FieldAttributeSetter.COLOUR_ITEM_SUFFIX).isEqualTo("C");
            assertThat(TransactionListResponse.OUTPUT_ITEM_SUFFIX)
                    .isEqualTo(FieldAttributeSetter.OUTPUT_ITEM_SUFFIX).isEqualTo("O");
            assertThat(TransactionListResponse.PS_ITEM_SUFFIX).isEqualTo("P");
            assertThat(TransactionListResponse.HIGHLIGHT_ITEM_SUFFIX).isEqualTo("H");
            assertThat(TransactionListResponse.VALIDATION_ITEM_SUFFIX).isEqualTo("V");
        }
    }

    @Nested
    @DisplayName("The page size is behaviour: exactly 10, tied to the row groups")
    class PageSize {

        @Test
        @DisplayName("PAGE_SIZE is 10 and equals the number of modelled rows")
        void pageSizeIsTen() {
            assertThat(TransactionListResponse.PAGE_SIZE).isEqualTo(10);
            assertThat(TransactionListResponse.ROW_COUNT)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE);
            assertThat(TransactionListResponse.FIRST_ROW).isEqualTo(1);
            assertThat(TransactionListResponse.LAST_ROW).isEqualTo(10);
            assertThat(TransactionListResponse.LAST_ROW - TransactionListResponse.FIRST_ROW + 1)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE);
        }

        @Test
        @DisplayName("the row block width is exactly PAGE_SIZE rows wide")
        void rowBlockIsPageSizeRows() {
            assertThat(TransactionListResponse.ROW_BLOCK_WIDTH)
                    .isEqualTo(TransactionListResponse.PAGE_SIZE
                            * TransactionListResponse.ROW_WIDTH);
        }

        @Test
        @DisplayName("no field of the payload carries the page size, so it cannot be configured")
        void pageSizeIsNotAPayloadField() {
            assertThat(TransactionListResponse.payloadFieldNames())
                    .noneMatch(name -> name.toUpperCase().contains("PAGESIZE"));
        }
    }

    @Nested
    @DisplayName("Rows are 1-based: rows 1 and 10 address the right fields")
    class RowAddressing {

        @ParameterizedTest(name = "row {0} addresses the five fields the copybook declares for it")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
        @DisplayName("every row's five prefixes are in copybook within-row order")
        void rowPrefixes(int row) {
            List<String> names = TransactionListResponse.rowFieldNames(row);
            assertThat(names).hasSize(5);
            int firstIndex = 8 + (row - 1) * 5;
            assertThat(names).containsExactlyElementsOf(
                    EXPECTED_ITEM_NAMES.subList(firstIndex, firstIndex + 5));
            assertThat(TransactionListResponse.rowFieldPrefixes(row)).hasSize(5);
        }

        @Test
        @DisplayName("row 1 is SEL0001O and row 10 is SEL0010O - the first and last element")
        void firstAndLastRow() {
            assertThat(TransactionListResponse.rowFieldNames(1))
                    .containsExactly("SEL0001O", "TRNID01O", "TDATE01O", "TDESC01O", "TAMT001O");
            assertThat(TransactionListResponse.rowFieldNames(10))
                    .containsExactly("SEL0010O", "TRNID10O", "TDATE10O", "TDESC10O", "TAMT010O");
        }

        @ParameterizedTest(name = "row {0} is off the page")
        @ValueSource(ints = {-1, 0, 11, 99, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("an accessor rejects a row outside 1..10 rather than guessing")
        void offPageRowsAreRejected(int row) {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListResponse.rowFieldNames(row))
                    .withMessageContaining("1-based");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowSelection(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowTransactionId(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowTransactionDate(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowDescription(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.getRowAmount(row));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowSelection(row, " "));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowTransactionId(row, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowTransactionDate(row, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowDescription(row, "x"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setRowAmount(row, "x"));
        }

        @ParameterizedTest(name = "row {0} round-trips through the row accessors")
        @ValueSource(ints = {1, 5, 10})
        @DisplayName("the row accessors read back what they wrote, untrimmed")
        void rowAccessorsRoundTrip(int row) {
            TransactionListResponse response = new TransactionListResponse();
            response.setRowSelection(row, "S");
            response.setRowTransactionId(row, "0000000000000042");
            response.setRowTransactionDate(row, "07/18/22");
            response.setRowDescription(row, "twenty six characters here");
            response.setRowAmount(row, "+00000042.00");
            assertThat(response.getRowSelection(row)).isEqualTo("S");
            assertThat(response.getRowTransactionId(row)).isEqualTo("0000000000000042");
            assertThat(response.getRowTransactionDate(row)).isEqualTo("07/18/22");
            assertThat(response.getRowDescription(row)).isEqualTo("twenty six characters here")
                    .hasSize(26);
            assertThat(response.getRowAmount(row)).isEqualTo("+00000042.00").hasSize(12);
        }
    }

    @Nested
    @DisplayName("POPULATE-TRAN-DATA and INITIALIZE-TRAN-DATA, statement for statement")
    class ParagraphReproductions {

        @Test
        @DisplayName("POPULATE-HEADER-INFO fills the six heading fields from the source moves")
        void populateHeaderInfo() {
            TransactionListResponse response = new TransactionListResponse();
            DateHeader header = DateHeader.of(CODEC, LocalDateTime.of(2022, 12, 25, 3, 4, 5));
            response.populateHeaderInfo(header);
            assertThat(response.getTitle01O()).isEqualTo(ScreenTitles.CCDA_TITLE01).hasSize(40);
            assertThat(response.getTitle02O()).isEqualTo(ScreenTitles.CCDA_TITLE02).hasSize(40);
            assertThat(response.getTrnnameO()).isEqualTo("CT00");
            assertThat(response.getPgmnameO()).isEqualTo("COTRN00C");
            assertThat(response.getCurdateO()).isEqualTo("12/25/22").hasSize(8);
            assertThat(response.getCurtimeO()).isEqualTo("03:04:05").hasSize(8);
        }

        @Test
        @DisplayName("POPULATE-HEADER-INFO requires a date header")
        void populateHeaderInfoRejectsNull() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException().isThrownBy(() -> response.populateHeaderInfo(null));
        }

        @Test
        @DisplayName("TRAN-DESC X(100) truncates on the RIGHT into TDESCnnO X(26)")
        void descriptionTruncatesOnTheRight() {
            TransactionListResponse response = new TransactionListResponse();
            String hundred = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(4).substring(0, 100);
            response.populateTranData(CODEC, 1, "0000000000000001", "07/18/22", hundred,
                    "+00000001.00");
            assertThat(response.getTdesc01O()).isEqualTo(hundred.substring(0, 26)).hasSize(26);
            assertThat(response.getTdesc01O()).isNotEqualTo(hundred.substring(74));
        }

        @Test
        @DisplayName("a short value is padded on the right to the field's declared width")
        void shortValuesArePadded() {
            TransactionListResponse response = new TransactionListResponse();
            response.populateTranData(CODEC, 2, "42", "1/1/22", "short", "+1.00");
            assertThat(response.getTrnid02O()).isEqualTo("42              ").hasSize(16);
            assertThat(response.getTdate02O()).isEqualTo("1/1/22  ").hasSize(8);
            assertThat(response.getTdesc02O()).isEqualTo("short                     ").hasSize(26);
            assertThat(response.getTamt002O()).isEqualTo("+1.00       ").hasSize(12);
        }

        @Test
        @DisplayName("row 1 also seeds CDEMO-CT00-TRNID-FIRST; no other row does")
        void rowOneSeedsTheCursor() {
            TransactionListResponse response = new TransactionListResponse();
            response.populateTranData(CODEC, 2, "0000000000000002", "07/18/22", "row two",
                    "+00000002.00");
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(" ".repeat(16));
            response.populateTranData(CODEC, 1, "0000000000000001", "07/18/22", "row one",
                    "+00000001.00");
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo("0000000000000001");
        }

        @ParameterizedTest(name = "populateTranData on row {0} is a no-op - WHEN OTHER CONTINUE")
        @ValueSource(ints = {-5, 0, 11, 12, 1000})
        @DisplayName("an out-of-range row is a silent no-op, not an error")
        void populateOffPageIsANoOp(int row) {
            TransactionListResponse response = new TransactionListResponse();
            Map<String, String> before = response.payloadFieldValues();
            response.populateTranData(CODEC, row, "id", "date", "desc", "amt");
            response.initializeTranData(row);
            assertThat(response.payloadFieldValues()).isEqualTo(before);
            assertThat(response.getCursor().getTrnidFirst()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("populateTranData requires a codec, so the code page is always explicit")
        void populateRequiresACodec() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException().isThrownBy(
                    () -> response.populateTranData(null, 1, "a", "b", "c", "d"));
        }

        @ParameterizedTest(name = "INITIALIZE-TRAN-DATA blanks four of row {0}'s five fields")
        @ValueSource(ints = {1, 5, 10})
        @DisplayName("INITIALIZE-TRAN-DATA blanks the four data fields and LEAVES the selector")
        void initializeLeavesTheSelectorAlone(int row) {
            TransactionListResponse response = new TransactionListResponse();
            response.setRowSelection(row, "S");
            response.populateTranData(CODEC, row, "0000000000000009", "07/18/22", "filled",
                    "+00000009.00");

            response.initializeTranData(row);

            assertThat(response.getRowTransactionId(row)).isEqualTo(" ".repeat(16));
            assertThat(response.getRowTransactionDate(row)).isEqualTo(" ".repeat(8));
            assertThat(response.getRowDescription(row)).isEqualTo(" ".repeat(26));
            assertThat(response.getRowAmount(row)).isEqualTo(" ".repeat(12));
            assertThat(response.getRowSelection(row))
                    .as("SEL000nO is absent from every arm of INITIALIZE-TRAN-DATA")
                    .isEqualTo("S");
        }

        @Test
        @DisplayName("a blanked amount is spaces - never null and never a rendered 0.00")
        void blankedAmountIsSpaces() {
            TransactionListResponse response = new TransactionListResponse();
            response.populateTranData(CODEC, 3, "1", "1/1/22", "d", "+00000001.00");
            response.initializeTranData(3);
            assertThat(response.getTamt003O()).isEqualTo("            ").isNotNull();
            assertThat(response.getTamt003O()).isNotEqualTo("0.00").doesNotContain("0");
        }

        @Test
        @DisplayName("initializeAllTranData blanks all ten rows - the PERFORM VARYING loop")
        void initializeAllRows() {
            TransactionListResponse response = fullyPopulated();
            response.initializeAllTranData();
            for (int row = 1; row <= 10; row++) {
                assertThat(response.getRowTransactionId(row)).isBlank();
                assertThat(response.getRowDescription(row)).isBlank();
                assertThat(response.getRowAmount(row)).isBlank();
            }
            assertThat(response.getTitle01O())
                    .as("the heading is not part of the row block")
                    .isEqualTo(ScreenTitles.CCDA_TITLE01);
        }

        @ParameterizedTest(name = "page {0} renders as the zero-filled image {1}")
        @CsvSource({"0,00000000", "1,00000001", "3,00000003", "99,00000099", "99999999,99999999"})
        @DisplayName("PAGENUMO is the numeric page number zero-filled into X(8)")
        void pageNumberMovesIntoTheAlphanumericItem(int pageNum, String expected) {
            TransactionListResponse response = new TransactionListResponse();
            response.movePageNumberToScreen(CODEC, pageNum);
            assertThat(response.getPagenumO()).isEqualTo(expected).hasSize(8);
        }

        @Test
        @DisplayName("a negative page number has no representation in PIC 9(08)")
        void negativePageNumberRejected() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.movePageNumberToScreen(CODEC, -1))
                    .withMessageContaining("unsigned");
            assertThatNullPointerException()
                    .isThrownBy(() -> response.movePageNumberToScreen(null, 1));
        }

        @Test
        @DisplayName("WS-MESSAGE X(80) truncates on the right into ERRMSGO X(78)")
        void errorLineTruncatesTwoCharacters() {
            TransactionListResponse response = new TransactionListResponse();
            String eighty = "M".repeat(78) + "XY";
            response.moveMessageToErrorLine(CODEC, eighty);
            assertThat(response.getErrmsgO()).isEqualTo("M".repeat(78)).hasSize(78);
        }

        @Test
        @DisplayName("CCDA-MSG-INVALID-KEY X(50) is padded into ERRMSGO X(78)")
        void invalidKeyMessage() {
            TransactionListResponse response = new TransactionListResponse();
            response.moveInvalidKeyMessageToErrorLine(CODEC);
            assertThat(response.getErrmsgO()).hasSize(78)
                    .startsWith(SystemMessages.CCDA_MSG_INVALID_KEY)
                    .endsWith(" ");
            assertThat(SystemMessages.CCDA_MSG_INVALID_KEY).hasSize(50);
        }

        @Test
        @DisplayName("clearErrorLine and clearTranIdInput reproduce their MOVE statements")
        void clearingStatements() {
            TransactionListResponse response = new TransactionListResponse();
            response.moveMessageToErrorLine(CODEC, "something went wrong");
            response.setTrnidinO("0000000000000007");

            response.clearErrorLine();
            response.clearTranIdInput();

            assertThat(response.getErrmsgO()).isEqualTo(" ".repeat(78));
            assertThat(response.getTrnidinO())
                    .as("MOVE SPACE into a PIC X(16) receiver fills the whole field")
                    .isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("moveMessageToErrorLine requires a codec and a message")
        void errorLineGuards() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.moveMessageToErrorLine(null, "x"));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.moveMessageToErrorLine(CODEC, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.moveInvalidKeyMessageToErrorLine(null));
        }
    }

    @Nested
    @DisplayName("The attribute quad: metadata, and the CSSETATY highlight it receives")
    class Attributes {

        @Test
        @DisplayName("every field starts at low values, which is DFHDFCOL")
        void everyFieldStartsAtLowValues() {
            TransactionListResponse response = new TransactionListResponse();
            assertThat(FieldAttributes.LOW_VALUE).isEqualTo(BmsAttributes.DFHDFCOL);
            assertThat(response.allAttributes()).hasSize(59);
            for (String prefix : TransactionListResponse.fieldPrefixes()) {
                FieldAttributes attributes = response.attributesOf(prefix);
                assertThat(attributes.isLowValues()).isTrue();
                assertThat(attributes.isColourRed()).isFalse();
                assertThat(attributes.toByteArray()).hasSize(4);
                assertThat(attributes.describe()).contains("C=X'00'", "DFHDFCOL");
                assertThat(response.isFieldHighlighted(prefix)).isFalse();
            }
            assertThat(FieldAttributes.lowValues()).isEqualTo(new FieldAttributes(
                    FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE,
                    FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE));
        }

        @Test
        @DisplayName("isLowValues reports false when any one of the four items has been moved into")
        void isLowValuesTestsAllFourItems() {
            byte red = BmsAttributes.DFHRED;
            assertThat(FieldAttributes.lowValues().withColour(red).isLowValues()).isFalse();
            assertThat(new FieldAttributes(FieldAttributes.LOW_VALUE, (byte) 'P',
                    FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE).isLowValues()).isFalse();
            assertThat(new FieldAttributes(FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE,
                    (byte) 'H', FieldAttributes.LOW_VALUE).isLowValues()).isFalse();
            assertThat(new FieldAttributes(FieldAttributes.LOW_VALUE, FieldAttributes.LOW_VALUE,
                    FieldAttributes.LOW_VALUE, (byte) 'V').isLowValues()).isFalse();
            assertThat(FieldAttributes.lowValues().withColour(red).isColourRed()).isTrue();
            assertThat(FieldAttributes.lowValues().withColour(red).describe())
                    .contains("C=X'F2'", "DFHRED");
        }

        @ParameterizedTest(name = "{0}: a NOT-OK field on RE-ENTRY turns DFHRED and keeps its text")
        @ValueSource(strings = {"TRNIDIN", "SEL0001", "SEL0010", "TAMT010", "ERRMSG"})
        @DisplayName("a CSSETATY highlight moves DFHRED into xxxC on re-entry")
        void highlightOnReenter(String prefix) {
            TransactionListResponse response = new TransactionListResponse();
            response.setPayloadValue(prefix, "");
            String before = response.payloadValue(prefix);

            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(true, false, true,
                    prefix, TransactionListResponse.OUTPUT_MAP_GROUP_NAME);
            response.applyHighlight(prefix, highlight);

            assertThat(response.isFieldHighlighted(prefix)).isTrue();
            assertThat(response.attributesOf(prefix).colour()).isEqualTo(BmsAttributes.DFHRED);
            assertThat(response.payloadValue(prefix))
                    .as("only a BLANK field also receives the asterisk")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("a BLANK field on re-entry also receives the '*' in its xxxO item")
        void blankFieldReceivesAnAsterisk() {
            TransactionListResponse response = new TransactionListResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, true, true,
                    "TRNIDIN", TransactionListResponse.OUTPUT_MAP_GROUP_NAME);
            response.applyHighlight("TRNIDIN", highlight);
            assertThat(response.isFieldHighlighted("TRNIDIN")).isTrue();
            assertThat(response.getTrnidinO()).isEqualTo(FieldAttributeSetter.ASTERISK);
        }

        @ParameterizedTest(name = "notOk={0} blank={1} on FIRST ENTRY changes nothing")
        @CsvSource({"true,false", "false,true", "true,true", "false,false"})
        @DisplayName("no highlight is reachable on first entry - the CSSETATY outer IF fails")
        void noHighlightOnFirstEntry(boolean notOk, boolean blank) {
            TransactionListResponse response = new TransactionListResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, false,
                    "SEL0005", TransactionListResponse.OUTPUT_MAP_GROUP_NAME);
            response.applyHighlight("SEL0005", highlight);
            assertThat(highlight.untouched()).isTrue();
            assertThat(response.isFieldHighlighted("SEL0005")).isFalse();
            assertThat(response.attributesOf("SEL0005").isLowValues()).isTrue();
            assertThat(response.getSel0005O()).isEqualTo(" ");
        }

        @Test
        @DisplayName("a valid field on re-entry is left alone too")
        void noHighlightWhenValid() {
            TransactionListResponse response = new TransactionListResponse();
            FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(false, false, true,
                    "TAMT001", TransactionListResponse.OUTPUT_MAP_GROUP_NAME);
            response.applyHighlight("TAMT001", highlight);
            assertThat(response.isFieldHighlighted("TAMT001")).isFalse();
        }

        @Test
        @DisplayName("putAttributes replaces a quad, and resetAttributesToLowValues restores all 59")
        void attributesCanBeReplacedAndReset() {
            TransactionListResponse response = new TransactionListResponse();
            response.putAttributes("PAGENUM",
                    new FieldAttributes((byte) '1', (byte) '2', (byte) '3', (byte) '4'));
            assertThat(response.attributesOf("PAGENUM").toByteArray())
                    .containsExactly((byte) '1', (byte) '2', (byte) '3', (byte) '4');

            response.resetAttributesToLowValues();

            assertThat(response.allAttributes().values())
                    .allMatch(FieldAttributes::isLowValues)
                    .hasSize(59);
        }

        @Test
        @DisplayName("the attribute accessors reject a null quad, a null prefix and an unknown one")
        void attributeGuards() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatNullPointerException()
                    .isThrownBy(() -> response.putAttributes("PAGENUM", null));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.attributesOf("NOPE"));
            assertThatNullPointerException()
                    .isThrownBy(() -> response.applyHighlight("PAGENUM", null));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.allAttributes().clear());
        }
    }

    @Nested
    @DisplayName("Navigation: one XCTL target for both transfer sites, resolved by the client")
    class Navigation {

        @Test
        @DisplayName("the mapset and map default to this screen's own names")
        void navigationDefaults() {
            TransactionListResponse response = new TransactionListResponse();
            assertThat(response.getNextProgram()).isEqualTo(" ".repeat(8));
            assertThat(response.getNextMapset()).isEqualTo("COTRN00");
            assertThat(response.getNextMap()).isEqualTo("COTRN0A");
        }

        @Test
        @DisplayName("the row-selection path carries COTRN01C as a plain program name")
        void rowSelectionTarget() {
            TransactionListResponse response = new TransactionListResponse();
            response.setNextProgram("COTRN01C");
            assertThat(response.getNextProgram()).isEqualTo("COTRN01C").hasSize(8);
        }

        @Test
        @DisplayName("echoTransferTarget takes CDEMO-TO-PROGRAM out of the commarea")
        void echoTransferTarget() {
            TransactionListResponse response = new TransactionListResponse();
            NavigationContext context = NavigationContext.empty().withToProgram("COMEN01C");
            response.echoTransferTarget(context);
            assertThat(response.getNextProgram()).isEqualTo("COMEN01C");
            assertThat(response.getNextMapset()).isEqualTo("COTRN00");
            assertThat(response.getNextMap()).isEqualTo("COTRN0A");
            assertThatNullPointerException().isThrownBy(() -> response.echoTransferTarget(null));
        }

        @Test
        @DisplayName("the navigation widths are the commarea's: 8, 7 and 7")
        void navigationWidthsAreEnforced() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setNextProgram("TOOLONGPROGRAM"))
                    .withMessageContaining("CDEMO-TO-PROGRAM");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setNextMapset("EIGHTCHR"))
                    .withMessageContaining("CDEMO-LAST-MAPSET");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.setNextMap("EIGHTCHR"))
                    .withMessageContaining("CDEMO-LAST-MAP");
            assertThatNullPointerException().isThrownBy(() -> response.setNextMap(null));
        }
    }

    @Nested
    @DisplayName("Statelessness: the commarea and the 58-byte cursor travel in the payload")
    class Statelessness {

        @Test
        @DisplayName("the commarea starts empty at exactly 160 bytes and is echoed unchanged")
        void commareaIsEchoed() {
            TransactionListResponse response = new TransactionListResponse();
            assertThat(response.getNavigationContext()).isEqualTo(NavigationContext.empty());
            NavigationContext context = NavigationContext.empty()
                    .withUserId("ADMIN001").withToProgram("COTRN01C");
            response.setNavigationContext(context);
            assertThat(response.getNavigationContext()).isSameAs(context);
            assertThat(context.toFixedWidth(CODEC)).hasSize(160);
            assertThatNullPointerException()
                    .isThrownBy(() -> response.setNavigationContext(null));
        }

        @Test
        @DisplayName("the cursor starts in its declared state, with NEXT-PAGE-FLG at VALUE 'N'")
        void cursorStartsInItsDeclaredState() {
            TransactionListCursor cursor = new TransactionListCursor();
            assertThat(cursor.getTrnidFirst()).isEqualTo(" ".repeat(16));
            assertThat(cursor.getTrnidLast()).isEqualTo(" ".repeat(16));
            assertThat(cursor.getPageNum()).isZero();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(" ".repeat(16));
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isRowSelected()).isFalse();
        }

        @Test
        @DisplayName("both 88-levels are reachable, and neither is the other's negation")
        void bothConditionNamesAreReachable() {
            TransactionListCursor cursor = new TransactionListCursor();

            cursor.setNextPageYes();
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageNo();
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();

            cursor.setNextPageFlg(" ");
            assertThat(cursor.isNextPageYes())
                    .as("a blank flag satisfies neither condition")
                    .isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();

            cursor.setNextPageFlg("y");
            assertThat(cursor.isNextPageYes())
                    .as("a COBOL alphanumeric comparison is case-sensitive")
                    .isFalse();
        }

        @Test
        @DisplayName("a selection needs both the flag and the identifier to be present")
        void rowSelectionRequiresBothFields() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnSelFlg("S");
            assertThat(cursor.isRowSelected())
                    .as("the flag alone is not a selection")
                    .isFalse();
            cursor.setTrnSelected("0000000000000001");
            assertThat(cursor.isRowSelected()).isTrue();

            cursor.setTrnSelFlg("\u0000");
            assertThat(cursor.isRowSelected())
                    .as("a low value is not present either")
                    .isFalse();

            cursor.setTrnSelFlg("");
            assertThat(cursor.isRowSelected())
                    .as("an empty flag is not present either")
                    .isFalse();

            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("");
            assertThat(cursor.isRowSelected())
                    .as("the second conjunct of the COTRN00C:183-184 guard is tested too")
                    .isFalse();

            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000001");
            cursor.clearSelection();
            assertThat(cursor.isRowSelected()).isFalse();
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the cursor carries any selection character, valid or not")
        void anySelectionCharacterIsCarried() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnSelFlg("Z");
            assertThat(cursor.getTrnSelFlg())
                    .as("COTRN00C stores what was typed and only then rejects it")
                    .isEqualTo("Z");
        }

        @Test
        @DisplayName("CDEMO-CT00-PAGE-NUM is PIC 9(08): unsigned and at most eight digits")
        void pageNumberIsValidated() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setPageNum(99999999);
            assertThat(cursor.getPageNum()).isEqualTo(99999999);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setPageNum(100000000))
                    .withMessageContaining("9 digits");
        }

        @Test
        @DisplayName("the cursor fields reject over-wide values and nulls")
        void cursorWidthsAreEnforced() {
            TransactionListCursor cursor = new TransactionListCursor();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnidFirst("x".repeat(17)))
                    .withMessageContaining("CDEMO-CT00-TRNID-FIRST");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnidLast("x".repeat(17)));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setNextPageFlg("YY"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnSelFlg("SS"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cursor.setTrnSelected("x".repeat(17)));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidFirst(null));
        }

        @Test
        @DisplayName("the cursor round-trips through its 58-byte image")
        void cursorImageRoundTrips() {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnidFirst("0000000000000001");
            cursor.setTrnidLast("0000000000000010");
            cursor.setPageNum(7);
            cursor.setNextPageYes();
            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000005");

            byte[] image = cursor.toFixedWidth(CODEC);
            assertThat(image).hasSize(58);
            assertThat(new String(image, ASCII))
                    .startsWith("00000000000000010000000000000010" + "00000007" + "YS");

            TransactionListCursor back = TransactionListCursor.fromFixedWidth(image, ASCII);
            assertThat(back.getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(back.getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(back.getPageNum()).isEqualTo(7);
            assertThat(back.isNextPageYes()).isTrue();
            assertThat(back.getTrnSelFlg()).isEqualTo("S");
            assertThat(back.getTrnSelected()).isEqualTo("0000000000000005");
            assertThat(back.toString()).contains("CDEMO-CT00-INFO", "CDEMO-CT00-PAGE-NUM=7");
        }

        @Test
        @DisplayName("the cursor image rejects a wrong width and a null argument")
        void cursorImageGuards() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListCursor.fromFixedWidth(new byte[57], ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListCursor.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListCursor.fromFixedWidth(new byte[58], null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionListCursor().toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> new TransactionListCursor(null));
        }

        @Test
        @DisplayName("setCursor copies, so two responses cannot page each other")
        void cursorIsCopiedIn() {
            TransactionListResponse response = new TransactionListResponse();
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setPageNum(4);
            response.setCursor(cursor);

            cursor.setPageNum(9);

            assertThat(response.getCursor().getPageNum()).isEqualTo(4);
            assertThat(response.getCursor()).isNotSameAs(cursor);
            assertThatNullPointerException().isThrownBy(() -> response.setCursor(null));
        }
    }

    @Nested
    @DisplayName("The 1265-byte COTRN0AO image")
    class FixedWidthImage {

        @Test
        @DisplayName("a blank response renders 1265 bytes with every FILLER emitted as spaces")
        void blankImage() {
            byte[] image = new TransactionListResponse().toFixedWidth(CODEC);
            assertThat(image).hasSize(1265);
            String text = new String(image, ASCII);
            assertThat(text.substring(0, 12))
                    .as("the TIOAPFX prefix is emitted, not skipped")
                    .isEqualTo(" ".repeat(12));
            assertThat(text.substring(12, 15))
                    .as("the per-field 3-byte FILLER is emitted")
                    .isEqualTo(" ".repeat(3));
            assertThat(image[15])
                    .as("TRNNAMEC holds the low value MOVE LOW-VALUES left")
                    .isEqualTo(FieldAttributes.LOW_VALUE);
        }

        @Test
        @DisplayName("a populated response round-trips through its image without loss")
        void imageRoundTrips() {
            TransactionListResponse response = fullyPopulated();
            response.putAttributes("TRNIDIN",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));

            byte[] image = response.toFixedWidth(CODEC);
            assertThat(image).hasSize(1265);
            assertThat(image[TransactionListResponse.LAYOUT.span("TRNIDINC").offset()])
                    .as("the attribute byte is written raw, so 0xF2 survives a US-ASCII image")
                    .isEqualTo(BmsAttributes.DFHRED);

            TransactionListResponse back = TransactionListResponse.fromFixedWidth(image, ASCII);
            assertThat(back.payloadFieldValues()).isEqualTo(response.payloadFieldValues());
            assertThat(back.isFieldHighlighted("TRNIDIN")).isTrue();
            assertThat(back.attributesOf("SEL0001").isLowValues()).isTrue();
        }

        @Test
        @DisplayName("each payload item lands at its layout offset")
        void payloadItemsLandAtTheirOffsets() {
            TransactionListResponse response = fullyPopulated();
            String text = new String(response.toFixedWidth(CODEC), ASCII);
            for (String itemName : EXPECTED_ITEM_NAMES) {
                FixedWidthRecord.FieldSpan span = TransactionListResponse.LAYOUT.span(itemName);
                String prefix = itemName.substring(0, itemName.length() - 1);
                assertThat(text.substring(span.offset(), span.endOffsetExclusive()))
                        .as(itemName)
                        .isEqualTo(response.payloadValue(prefix));
            }
        }

        @Test
        @DisplayName("writeInto and readFrom use the record's own code page")
        void writeIntoAndReadFrom() {
            TransactionListResponse response = fullyPopulated();
            FixedWidthRecord record = new FixedWidthRecord(1265, ASCII);
            response.writeInto(record);
            TransactionListResponse back = TransactionListResponse.readFrom(record);
            assertThat(back.payloadFieldValues()).isEqualTo(response.payloadFieldValues());
        }

        @Test
        @DisplayName("a record of the wrong width is rejected, and so are null arguments")
        void imageGuards() {
            TransactionListResponse response = new TransactionListResponse();
            FixedWidthRecord wrongWidth = new FixedWidthRecord(100, ASCII);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> response.writeInto(wrongWidth))
                    .withMessageContaining("1265");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListResponse.readFrom(wrongWidth));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> TransactionListResponse.fromFixedWidth(new byte[1264],
                            ASCII));
            assertThatNullPointerException().isThrownBy(() -> response.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> response.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListResponse.readFrom(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListResponse.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListResponse.fromFixedWidth(new byte[1265], null));
        }
    }

    @Nested
    @DisplayName("JSON: the space padding of a PIC X field survives a round trip untrimmed")
    class JsonRoundTrip {

        /** A plain mapper with no configuration, since the module's policy lives in the web layer. */
        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("a 78-character error line, a 26-character description and a 12-character "
                + "amount all survive untrimmed")
        void paddingSurvives() throws Exception {
            TransactionListResponse response = new TransactionListResponse();
            response.setErrmsgO("error" + " ".repeat(73));
            response.setTdesc05O("desc" + " ".repeat(22));
            response.setTamt005O("+1.00" + " ".repeat(7));

            String json = mapper.writeValueAsString(response);
            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);

            assertThat(back.getErrmsgO()).isEqualTo("error" + " ".repeat(73)).hasSize(78);
            assertThat(back.getTdesc05O()).isEqualTo("desc" + " ".repeat(22)).hasSize(26);
            assertThat(back.getTamt005O()).isEqualTo("+1.00" + " ".repeat(7)).hasSize(12);
        }

        @Test
        @DisplayName("all 59 fields, the navigation targets and the cursor round-trip through JSON")
        void everyFieldRoundTrips() throws Exception {
            TransactionListResponse response = fullyPopulated();
            response.setNextProgram("COTRN01C");
            response.getCursor().setNextPageYes();
            response.getCursor().setPageNum(3);
            response.setNavigationContext(NavigationContext.empty().withUserId("USER0001"));

            String json = mapper.writeValueAsString(response);
            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);

            assertThat(back.payloadFieldValues()).isEqualTo(response.payloadFieldValues());
            assertThat(back.getNextProgram()).isEqualTo("COTRN01C");
            assertThat(back.getNextMapset()).isEqualTo("COTRN00");
            assertThat(back.getNextMap()).isEqualTo("COTRN0A");
            assertThat(back.getCursor().getPageNum()).isEqualTo(3);
            assertThat(back.getCursor().isNextPageYes()).isTrue();
            assertThat(back.getNavigationContext().userId()).isEqualTo("USER0001");
        }

        @Test
        @DisplayName("a blanked row serialises as spaces, not as null")
        void blankedRowSerialisesAsSpaces() throws Exception {
            TransactionListResponse response = fullyPopulated();
            response.initializeTranData(4);

            String json = mapper.writeValueAsString(response);
            assertThat(json).doesNotContain("null");
            assertThat(json).contains("\"tamt004O\":\"            \"");

            TransactionListResponse back = mapper.readValue(json, TransactionListResponse.class);
            assertThat(back.getTamt004O()).isEqualTo(" ".repeat(12));
            assertThat(back.getTrnid04O()).isEqualTo(" ".repeat(16));
        }

        @Test
        @DisplayName("the attribute metadata never appears in the payload")
        void attributesAreNotSerialised() throws Exception {
            TransactionListResponse response = new TransactionListResponse();
            response.putAttributes("PAGENUM",
                    new FieldAttributes((byte) '1', (byte) '2', (byte) '3', (byte) '4'));
            String json = mapper.writeValueAsString(response);
            assertThat(json)
                    .doesNotContain("fieldAttributes")
                    .doesNotContain("attributes")
                    .doesNotContain("PAGENUMC")
                    .doesNotContain("payloadFieldValues")
                    .doesNotContain("rowSelection");
        }

        @Test
        @DisplayName("the JSON carries a member for every one of the 59 fields")
        void everyFieldIsAMember() throws Exception {
            String json = mapper.writeValueAsString(new TransactionListResponse());
            @SuppressWarnings("unchecked")
            Map<String, Object> members = mapper.readValue(json, Map.class);
            for (String itemName : EXPECTED_ITEM_NAMES) {
                String member = itemName.substring(0, itemName.length() - 1).toLowerCase()
                        + itemName.charAt(itemName.length() - 1);
                assertThat(members).as(itemName).containsKey(member);
            }
            assertThat(members).hasSize(59 + 3 + 2);
        }
    }

    @Nested
    @DisplayName("Copying and diagnostics")
    class CopyingAndDiagnostics {

        @Test
        @DisplayName("the copy constructor copies the payload, the navigation and the attributes")
        void copyConstructor() {
            TransactionListResponse original = fullyPopulated();
            original.setNextProgram("COTRN01C");
            original.putAttributes("SEL0003",
                    FieldAttributes.lowValues().withColour(BmsAttributes.DFHRED));
            original.getCursor().setPageNum(2);

            TransactionListResponse copy = new TransactionListResponse(original);

            assertThat(copy.payloadFieldValues()).isEqualTo(original.payloadFieldValues());
            assertThat(copy.getNextProgram()).isEqualTo("COTRN01C");
            assertThat(copy.getNextMapset()).isEqualTo("COTRN00");
            assertThat(copy.getNextMap()).isEqualTo("COTRN0A");
            assertThat(copy.isFieldHighlighted("SEL0003")).isTrue();
            assertThat(copy.getCursor().getPageNum()).isEqualTo(2);
            assertThat(copy.getCursor()).isNotSameAs(original.getCursor());

            original.setTamt001O("+99999999.99");
            assertThat(copy.getTamt001O()).isNotEqualTo("+99999999.99");

            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionListResponse(null));
        }

        @Test
        @DisplayName("toString names every field by its verbatim item name and hides nothing")
        void toStringIsComplete() {
            String text = fullyPopulated().toString();
            assertThat(text).startsWith("COTRN0AO[");
            for (String itemName : EXPECTED_ITEM_NAMES) {
                assertThat(text).as(itemName).contains(itemName + "='");
            }
            assertThat(text).contains("nextProgram=", "nextMapset=", "nextMap=",
                    "CDEMO-CT00-INFO[");
        }

        @Test
        @DisplayName("payloadFieldValues and the name lists are unmodifiable")
        void registriesAreImmutable() {
            TransactionListResponse response = new TransactionListResponse();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> response.payloadFieldValues().put("X", "Y"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionListResponse.payloadFieldNames().add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionListResponse.fieldPrefixes().add("X"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> TransactionListResponse.rowFieldPrefixes(1).add("X"));
        }
    }
}
