package com.vsergeychik.carddemo.transaction.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.PfKeyResolver;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import java.nio.charset.StandardCharsets;
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
 * Parity tests for {@link TransactionListRequest}, the projection of {@code 01 COTRN0AI} in
 * {@code app/cpy-bms/COTRN00.CPY}.
 *
 * <p>Every expected value below is transcribed from the copybook, the mapset or
 * {@code app/cbl/COTRN00C.cbl} - never read back out of the class under test - so the COBOL sources
 * stay the authority and a translation that drifted would fail here rather than agree with itself.
 *
 * <p>The suite is organised around the properties that determine the implementation, so a failure
 * names a translation decision rather than merely a value:
 *
 * <ol>
 *   <li>Exactly <strong>59</strong> payload fields, reconciling as 8 + 10 x 5 + 1.</li>
 *   <li>The row suffixes are <strong>deliberately inconsistent</strong> - four digits, two digits and
 *       three digits - and must stay that way.</li>
 *   <li>The widths sum to <strong>840</strong> and the group image is <strong>1265</strong> bytes.</li>
 *   <li>The page size is <strong>10</strong> and is not configurable.</li>
 *   <li>The 1-based COBOL row maps to the right field at <strong>both</strong> ends.</li>
 *   <li>Space padding survives a JSON round trip untrimmed, and a blank row is spaces.</li>
 *   <li>The cursor is <strong>58</strong> bytes with both {@code 88}-levels reachable.</li>
 * </ol>
 */
@DisplayName("TransactionListRequest - COTRN0AI projection of COTRN00 / CT00")
class TransactionListRequestTest {

    /** The code page of the authoritative fixtures under {@code app/data/ASCII}. */
    private static final java.nio.charset.Charset ASCII = StandardCharsets.US_ASCII;

    /**
     * The 59 base field names in copybook declaration order, transcribed from
     * {@code app/cpy-bms/COTRN00.CPY} lines 17 to 372 rather than derived from the class under test.
     */
    private static final List<String> COPYBOOK_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME", "PAGENUM", "TRNIDIN",
            "SEL0001", "TRNID01", "TDATE01", "TDESC01", "TAMT001",
            "SEL0002", "TRNID02", "TDATE02", "TDESC02", "TAMT002",
            "SEL0003", "TRNID03", "TDATE03", "TDESC03", "TAMT003",
            "SEL0004", "TRNID04", "TDATE04", "TDESC04", "TAMT004",
            "SEL0005", "TRNID05", "TDATE05", "TDESC05", "TAMT005",
            "SEL0006", "TRNID06", "TDATE06", "TDESC06", "TAMT006",
            "SEL0007", "TRNID07", "TDATE07", "TDESC07", "TAMT007",
            "SEL0008", "TRNID08", "TDATE08", "TDESC08", "TAMT008",
            "SEL0009", "TRNID09", "TDATE09", "TDESC09", "TAMT009",
            "SEL0010", "TRNID10", "TDATE10", "TDESC10", "TAMT010",
            "ERRMSG");

    /** The 59 declared widths, in the same order, from the {@code xxxI PIC X(n)} clauses. */
    private static final List<Integer> COPYBOOK_WIDTHS = List.of(
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

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    // =================================================================================================

    @Nested
    @DisplayName("Field inventory - 59 fields, reconciling as 8 + 10 x 5 + 1")
    class Inventory {

        @Test
        @DisplayName("the map declares 59 payload fields")
        void fieldCountIs59() {
            assertThat(TransactionListRequest.FIELD_COUNT).isEqualTo(59);
            assertThat(TransactionListRequest.FIELD_NAMES).hasSize(59);
        }

        @Test
        @DisplayName("59 = 8 header + 10 rows x 5 + 1 error line")
        void fieldCountReconciles() {
            assertThat(TransactionListRequest.HEADER_FIELD_COUNT).isEqualTo(8);
            assertThat(TransactionListRequest.ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionListRequest.ERROR_FIELD_COUNT).isEqualTo(1);
            assertThat(TransactionListRequest.HEADER_FIELD_COUNT
                    + TransactionListRequest.ROW_COUNT * TransactionListRequest.ROW_FIELD_COUNT
                    + TransactionListRequest.ERROR_FIELD_COUNT).isEqualTo(59);
        }

        @Test
        @DisplayName("the field names equal the copybook xxxI base names, in declaration order")
        void namesMatchCopybookExactly() {
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .containsExactlyElementsOf(COPYBOOK_FIELDS);
        }

        @Test
        @DisplayName("the symbolic-map item names are the base names with I appended")
        void inputItemNamesAppendI() {
            List<String> expected = new ArrayList<>();
            for (String base : COPYBOOK_FIELDS) {
                expected.add(base + "I");
            }
            assertThat(TransactionListRequest.INPUT_ITEM_NAMES).containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("the metadata item names append L, F and A")
        void metadataItemNames() {
            assertThat(TransactionListRequest.lengthItemName("TRNIDIN")).isEqualTo("TRNIDINL");
            assertThat(TransactionListRequest.flagItemName("TRNIDIN")).isEqualTo("TRNIDINF");
            assertThat(TransactionListRequest.attributeItemName("TRNIDIN")).isEqualTo("TRNIDINA");
            assertThat(TransactionListRequest.inputItemName("TAMT001")).isEqualTo("TAMT001I");
        }

        @Test
        @DisplayName("a null base name is rejected by every name helper")
        void nullBaseNameRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.inputItemName(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.lengthItemName(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.flagItemName(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.attributeItemName(null));
        }

        @Test
        @DisplayName("provenance names the CSD transaction, program, mapset and map")
        void provenance() {
            assertThat(TransactionListRequest.TRANSACTION_ID).isEqualTo("CT00");
            assertThat(TransactionListRequest.PROGRAM_NAME).isEqualTo("COTRN00C");
            assertThat(TransactionListRequest.MAPSET_NAME).isEqualTo("COTRN00");
            assertThat(TransactionListRequest.MAP_NAME).isEqualTo("COTRN0A");
            assertThat(TransactionListRequest.SYMBOLIC_MAP_INPUT_GROUP).isEqualTo("COTRN0AI");
            assertThat(TransactionListRequest.SYMBOLIC_MAP_OUTPUT_GROUP).isEqualTo("COTRN0AO");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Suffix spelling - four digits, two digits and three digits, never normalised")
    class SuffixSpelling {

        @ParameterizedTest(name = "row {0} selector is {1}")
        @CsvSource({"1,SEL0001", "2,SEL0002", "9,SEL0009", "10,SEL0010"})
        @DisplayName("the selector suffix is FOUR digits")
        void selectorSuffixIsFourDigits(int row, String expected) {
            assertThat(TransactionListRequest.selectionFieldName(row)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} identifier is {1}")
        @CsvSource({"1,TRNID01", "2,TRNID02", "9,TRNID09", "10,TRNID10"})
        @DisplayName("the identifier suffix is TWO digits")
        void identifierSuffixIsTwoDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionIdFieldName(row)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} date is {1}")
        @CsvSource({"1,TDATE01", "10,TDATE10"})
        @DisplayName("the date suffix is TWO digits")
        void dateSuffixIsTwoDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionDateFieldName(row)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} description is {1}")
        @CsvSource({"1,TDESC01", "10,TDESC10"})
        @DisplayName("the description suffix is TWO digits")
        void descriptionSuffixIsTwoDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionDescriptionFieldName(row))
                    .isEqualTo(expected);
        }

        @ParameterizedTest(name = "row {0} amount is {1}")
        @CsvSource({"1,TAMT001", "2,TAMT002", "9,TAMT009", "10,TAMT010"})
        @DisplayName("the amount suffix is THREE digits - not TAMT01")
        void amountSuffixIsThreeDigits(int row, String expected) {
            assertThat(TransactionListRequest.transactionAmountFieldName(row)).isEqualTo(expected);
            assertThat(expected).isNotEqualTo("TAMT" + (row < 10 ? "0" + row : row));
        }

        @Test
        @DisplayName("all four suffix widths coexist, so no shared format string could produce them")
        void allFourWidthsCoexist() {
            assertThat(TransactionListRequest.selectionFieldName(1)).hasSize(7).endsWith("0001");
            assertThat(TransactionListRequest.transactionIdFieldName(1)).hasSize(7).endsWith("01");
            assertThat(TransactionListRequest.transactionAmountFieldName(1)).hasSize(7)
                    .endsWith("001");
        }

        @ParameterizedTest(name = "row {0} is rejected")
        @ValueSource(ints = {-1, 0, 11, 12, Integer.MAX_VALUE})
        @DisplayName("a row outside 1..10 is rejected - COBOL rows are 1-based and there is no row 0")
        void invalidRowRejected(int row) {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.requireValidRow(row))
                    .withMessageContaining("1-based");
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.selectionFieldName(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.transactionAmountFieldName(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.transactionIdFieldName(row));
        }

        @Test
        @DisplayName("a valid row is returned unchanged so the guard reads naturally inline")
        void validRowReturnedUnchanged() {
            assertThat(TransactionListRequest.requireValidRow(1)).isEqualTo(1);
            assertThat(TransactionListRequest.requireValidRow(10)).isEqualTo(10);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Byte geometry - widths sum to 840 and the group image is 1265")
    class Geometry {

        @Test
        @DisplayName("each declared width equals its copybook PIC X(n) and its BMS LENGTH")
        void widthsMatchCopybook() {
            TransactionListRequest request = new TransactionListRequest();
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                assertThat(request.getPayloadValue(COPYBOOK_FIELDS.get(i)))
                        .as("width of %s", COPYBOOK_FIELDS.get(i))
                        .hasSize(COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("the header widths are 4, 40, 8, 8, 40, 8, 8 and 16, summing to 132")
        void headerWidths() {
            assertThat(TransactionListRequest.TRNNAME_LENGTH).isEqualTo(4);
            assertThat(TransactionListRequest.TITLE01_LENGTH).isEqualTo(40);
            assertThat(TransactionListRequest.CURDATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.PGMNAME_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.TITLE02_LENGTH).isEqualTo(40);
            assertThat(TransactionListRequest.CURTIME_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.PAGENUM_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.TRNIDIN_LENGTH).isEqualTo(16);
            assertThat(TransactionListRequest.HEADER_PAYLOAD_LENGTH).isEqualTo(132);
        }

        @Test
        @DisplayName("the row widths are 1, 16, 8, 26 and 12, summing to 63 and 630 for ten rows")
        void rowWidths() {
            assertThat(TransactionListRequest.SELECTION_LENGTH).isEqualTo(1);
            assertThat(TransactionListRequest.TRANSACTION_ID_LENGTH).isEqualTo(16);
            assertThat(TransactionListRequest.TRANSACTION_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH).isEqualTo(26);
            assertThat(TransactionListRequest.TRANSACTION_AMOUNT_LENGTH).isEqualTo(12);
            assertThat(TransactionListRequest.ROW_PAYLOAD_LENGTH).isEqualTo(63);
            assertThat(TransactionListRequest.ROW_BLOCK_PAYLOAD_LENGTH).isEqualTo(630);
        }

        @Test
        @DisplayName("the error line is 78 and 132 + 630 + 78 = 840")
        void payloadTotal() {
            assertThat(TransactionListRequest.ERRMSG_LENGTH).isEqualTo(78);
            assertThat(TransactionListRequest.PAYLOAD_LENGTH).isEqualTo(840);
            assertThat(COPYBOOK_WIDTHS.stream().mapToInt(Integer::intValue).sum()).isEqualTo(840);
        }

        @Test
        @DisplayName("12 + 59 x 7 + 840 = 1265, the COTRN0AI group image")
        void groupImageTotal() {
            assertThat(TransactionListRequest.TIOAPFX_PREFIX_LENGTH).isEqualTo(12);
            assertThat(TransactionListRequest.FIELD_PREFIX_LENGTH).isEqualTo(7);
            assertThat(TransactionListRequest.SYMBOLIC_MAP_LENGTH).isEqualTo(1265);
            assertThat(12 + 59 * 7 + 840).isEqualTo(1265);
        }

        @Test
        @DisplayName("a row image is 98 bytes - 63 of payload plus five 7-byte prefixes")
        void rowImageStride() {
            assertThat(TransactionListRequest.ROW_IMAGE_LENGTH).isEqualTo(98);
            assertThat(TransactionListRequest.HEADER_IMAGE_LENGTH).isEqualTo(188);
            assertThat(TransactionListRequest.ROW_BLOCK_IMAGE_LENGTH).isEqualTo(980);
            assertThat(TransactionListRequest.ERRMSG_IMAGE_LENGTH).isEqualTo(85);
            assertThat(12 + 188 + 980 + 85).isEqualTo(1265);
        }

        @Test
        @DisplayName("the layout declares 1265 bytes across 119 spans - one filler plus 59 pairs")
        void layoutGeometry() {
            assertThat(TransactionListRequest.LAYOUT.recordLength()).isEqualTo(1265);
            assertThat(TransactionListRequest.LAYOUT.spans()).hasSize(1 + 2 * 59);
            assertThat(TransactionListRequest.LAYOUT.storageSpans()).hasSize(1 + 2 * 59);
            assertThat(TransactionListRequest.LAYOUT.redefinitions()).isEmpty();
        }

        @Test
        @DisplayName("the storage spans, and only they, sum to the declared record length")
        void spansSumToRecordLength() {
            int total = 0;
            for (FieldSpan span : TransactionListRequest.LAYOUT.storageSpans()) {
                total += span.length();
            }
            assertThat(total).isEqualTo(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("every payload span carries its verbatim xxxI name at the right width")
        void payloadSpansNamedVerbatim() {
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String item = COPYBOOK_FIELDS.get(i) + "I";
                assertThat(TransactionListRequest.LAYOUT.hasSpan(item)).as("span %s", item).isTrue();
                assertThat(TransactionListRequest.LAYOUT.span(item).length())
                        .as("width of %s", item).isEqualTo(COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("the header offsets are 19, 30, 77, 92, 107, 154, 169 and 184")
        void headerOffsets() {
            assertThat(TransactionListRequest.TRNNAME_OFFSET).isEqualTo(19);
            assertThat(TransactionListRequest.TITLE01_OFFSET).isEqualTo(30);
            assertThat(TransactionListRequest.CURDATE_OFFSET).isEqualTo(77);
            assertThat(TransactionListRequest.PGMNAME_OFFSET).isEqualTo(92);
            assertThat(TransactionListRequest.TITLE02_OFFSET).isEqualTo(107);
            assertThat(TransactionListRequest.CURTIME_OFFSET).isEqualTo(154);
            assertThat(TransactionListRequest.PAGENUM_OFFSET).isEqualTo(169);
            assertThat(TransactionListRequest.TRNIDIN_OFFSET).isEqualTo(184);
        }

        @Test
        @DisplayName("the row block starts at 200 and the error line payload at 1187")
        void blockOffsets() {
            assertThat(TransactionListRequest.ROW_BLOCK_OFFSET).isEqualTo(200);
            assertThat(TransactionListRequest.ERRMSG_OFFSET).isEqualTo(1187);
            assertThat(TransactionListRequest.ERRMSG_OFFSET + 78).isEqualTo(1265);
        }

        @Test
        @DisplayName("the within-row offsets are 7, 15, 38, 53 and 86, and 86 + 12 = 98")
        void withinRowOffsets() {
            assertThat(TransactionListRequest.SELECTION_ROW_OFFSET).isEqualTo(7);
            assertThat(TransactionListRequest.TRANSACTION_ID_ROW_OFFSET).isEqualTo(15);
            assertThat(TransactionListRequest.TRANSACTION_DATE_ROW_OFFSET).isEqualTo(38);
            assertThat(TransactionListRequest.TRANSACTION_DESCRIPTION_ROW_OFFSET).isEqualTo(53);
            assertThat(TransactionListRequest.TRANSACTION_AMOUNT_ROW_OFFSET).isEqualTo(86);
            assertThat(TransactionListRequest.TRANSACTION_AMOUNT_ROW_OFFSET + 12).isEqualTo(98);
        }

        @ParameterizedTest(name = "row {0} image begins at {1}")
        @CsvSource({"1,200", "2,298", "5,592", "9,984", "10,1082"})
        @DisplayName("row image offsets advance by 98 from 200, with row 1 first and row 10 last")
        void rowImageOffsets(int row, int expected) {
            assertThat(TransactionListRequest.rowImageOffset(row)).isEqualTo(expected);
        }

        @Test
        @DisplayName("a row outside 1..10 has no image offset")
        void rowImageOffsetRejectsInvalidRow() {
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.rowImageOffset(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.rowImageOffset(11));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Page size - exactly 10, behaviour rather than configuration")
    class PageSize {

        @Test
        @DisplayName("the page size is 10, as COTRN00C hard-codes at lines 290, 297, 349 and 351")
        void pageSizeIsTen() {
            assertThat(TransactionListRequest.PAGE_SIZE).isEqualTo(10);
        }

        @Test
        @DisplayName("the modelled row count equals the page size by construction")
        void rowCountEqualsPageSize() {
            assertThat(TransactionListRequest.ROW_COUNT)
                    .isEqualTo(TransactionListRequest.PAGE_SIZE)
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("exactly ten rows are addressable, and an eleventh is not")
        void tenRowsAddressable() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= 10; row++) {
                assertThat(request.getSelection(row)).hasSize(1);
            }
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getSelection(11));
        }

        @Test
        @DisplayName("ten selector and ten amount fields exist, one per page slot")
        void tenOfEachRowField() {
            assertThat(TransactionListRequest.FIELD_NAMES.stream()
                    .filter(name -> name.startsWith("SEL")).count()).isEqualTo(10);
            assertThat(TransactionListRequest.FIELD_NAMES.stream()
                    .filter(name -> name.startsWith("TAMT")).count()).isEqualTo(10);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Row addressing - the 1-based COBOL row maps correctly at both ends")
    class RowAddressing {

        @Test
        @DisplayName("row 1 reaches SEL0001, TRNID01, TDATE01, TDESC01 and TAMT001")
        void rowOneMapsToFirstFields() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSelection(1, "S");
            request.setTransactionId(1, "0000000000000001");
            request.setTransactionDate(1, "01/02/03");
            request.setTransactionDescription(1, "FIRST ROW");
            request.setTransactionAmount(1, "+00000001.00");

            assertThat(request.getSel0001()).isEqualTo("S");
            assertThat(request.getTrnid01()).isEqualTo("0000000000000001");
            assertThat(request.getTdate01()).isEqualTo("01/02/03");
            assertThat(request.getTdesc01()).isEqualTo("FIRST ROW");
            assertThat(request.getTamt001()).isEqualTo("+00000001.00");
        }

        @Test
        @DisplayName("row 10 reaches SEL0010, TRNID10, TDATE10, TDESC10 and TAMT010")
        void rowTenMapsToLastFields() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSelection(10, "s");
            request.setTransactionId(10, "0000000000000010");
            request.setTransactionDate(10, "10/11/12");
            request.setTransactionDescription(10, "LAST ROW");
            request.setTransactionAmount(10, "-00000010.99");

            assertThat(request.getSel0010()).isEqualTo("s");
            assertThat(request.getTrnid10()).isEqualTo("0000000000000010");
            assertThat(request.getTdate10()).isEqualTo("10/11/12");
            assertThat(request.getTdesc10()).isEqualTo("LAST ROW");
            assertThat(request.getTamt010()).isEqualTo("-00000010.99");
        }

        @Test
        @DisplayName("writing row 1 leaves row 10 untouched, so there is no off-by-one bleed")
        void rowsAreIndependent() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(1, "AAAAAAAAAAAAAAAA");
            assertThat(request.getTrnid10()).isEqualTo(spaces(16));
            assertThat(request.getTransactionId(10)).isEqualTo(spaces(16));
        }

        @Test
        @DisplayName("every row round-trips through the indexed and the explicit accessor alike")
        void indexedAndExplicitAgree() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= 10; row++) {
                request.setSelection(row, String.valueOf(row % 10));
                request.setTransactionId(row, "ID" + row);
                request.setTransactionDate(row, String.format("%02d/01/24", row));
                request.setTransactionDescription(row, "DESC " + row);
                request.setTransactionAmount(row, String.format("+%08d.00", row));
            }
            for (int row = 1; row <= 10; row++) {
                String selectionField = TransactionListRequest.selectionFieldName(row);
                String idField = TransactionListRequest.transactionIdFieldName(row);
                String dateField = TransactionListRequest.transactionDateFieldName(row);
                String descField = TransactionListRequest.transactionDescriptionFieldName(row);
                String amountField = TransactionListRequest.transactionAmountFieldName(row);
                assertThat(request.getPayloadValue(selectionField))
                        .isEqualTo(request.getSelection(row));
                assertThat(request.getPayloadValue(idField))
                        .isEqualTo(request.getTransactionId(row));
                assertThat(request.getPayloadValue(dateField))
                        .isEqualTo(request.getTransactionDate(row));
                assertThat(request.getPayloadValue(descField))
                        .isEqualTo(request.getTransactionDescription(row));
                assertThat(request.getPayloadValue(amountField))
                        .isEqualTo(request.getTransactionAmount(row));
            }
        }

        @Test
        @DisplayName("the indexed setters reject a row outside 1..10")
        void indexedSettersRejectInvalidRow() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setSelection(0, "S"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionId(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionDate(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionDescription(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionAmount(11, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionId(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionDate(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionDescription(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getTransactionAmount(0));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.clearRow(0));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The PIC X width rule - pad right with spaces, truncate right, never trim")
    class WidthRule {

        @Test
        @DisplayName("a fresh request holds spaces at every declared width, never null")
        void freshRequestIsSpaceFilled() {
            TransactionListRequest request = new TransactionListRequest();
            Map<String, String> values = request.getPayloadValues();
            assertThat(values).hasSize(59);
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String field = COPYBOOK_FIELDS.get(i);
                assertThat(values.get(field)).as("%s", field)
                        .isNotNull()
                        .isEqualTo(spaces(COPYBOOK_WIDTHS.get(i)));
            }
        }

        @Test
        @DisplayName("a blank amount is twelve spaces - not \"0.00\" and not null")
        void blankAmountIsSpaces() {
            TransactionListRequest request = new TransactionListRequest();
            assertThat(request.getTamt001()).isEqualTo(spaces(12)).isNotEqualTo("0.00");
            request.setTransactionAmount(1, "+00000042.50");
            request.clearRow(1);
            assertThat(request.getTamt001()).isEqualTo(spaces(12));
            assertThat(request.getTrnid01()).isEqualTo(spaces(16));
            assertThat(request.getTdate01()).isEqualTo(spaces(8));
            assertThat(request.getTdesc01()).isEqualTo(spaces(26));
            assertThat(request.getSel0001()).isEqualTo(" ");
        }

        @Test
        @DisplayName("the edited amount form is exactly twelve characters, as WS-TRAN-AMT declares")
        void editedAmountIsTwelveCharacters() {
            assertThat("+99999999.99").hasSize(TransactionListRequest.TRANSACTION_AMOUNT_LENGTH);
            TransactionListRequest request = new TransactionListRequest();
            request.setTamt001("+12345678.90");
            assertThat(request.getTamt001()).isEqualTo("+12345678.90").hasSize(12);
        }

        @Test
        @DisplayName("a short value is padded on the right")
        void shortValueIsStoredUnchanged() {
            TransactionListRequest request = new TransactionListRequest();
            request.setErrmsg("Invalid selection. Valid value is S");

            // Stored exactly as it arrived - not padded. The payload holds what the caller sent, so a
            // JSON round trip is the identity and a field the program tests against SPACES OR
            // LOW-VALUES arrives as it was typed.
            assertThat(request.getErrmsg())
                    .isEqualTo("Invalid selection. Valid value is S")
                    .hasSize(35);

            // The declared width is imposed once, at the byte boundary, where it is actually needed.
            byte[] image = request.toFixedWidth(ASCII);
            String errmsgSpan = new String(image, ASCII).substring(
                    TransactionListRequest.LAYOUT.span("ERRMSGI").offset(), image.length);
            assertThat(errmsgSpan).hasSize(78)
                    .startsWith("Invalid selection. Valid value is S")
                    .endsWith(" ");
        }

        @Test
        @DisplayName("an over-long value is REFUSED by name, never silently shortened")
        void longValueIsRefused() {
            TransactionListRequest request = new TransactionListRequest();

            // The setter used to shorten the value and then measure it, so the @Size constraint it was
            // checked against could never fail and four characters could vanish with no error. Now the
            // surplus is reported, naming the field and both widths.
            assertThatThrownBy(() -> request.setTdesc01("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TDESC01")
                    .hasMessageContaining("PIC X(26)")
                    .hasMessageContaining("36 character(s)");
            assertThatThrownBy(() -> request.setTrnname("TOOLONG"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRNNAME")
                    .hasMessageContaining("7 character(s)");

            // Nothing was stored by either refused call.
            assertThat(request.getTdesc01()).isEqualTo(spaces(26));
            assertThat(request.getTrnname()).isEqualTo(spaces(4));

            // A deliberate truncation is still available, and says so at the call site.
            request.setTdesc01(new FixedWidthCodec(ASCII)
                    .movePicX("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 26));
            assertThat(request.getTdesc01()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        }

        @Test
        @DisplayName("every setter rejects null - COBOL has no absent state")
        void settersRejectNull() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatNullPointerException().isThrownBy(() -> request.setTrnname(null));
            assertThatNullPointerException().isThrownBy(() -> request.setTitle01(null));
            assertThatNullPointerException().isThrownBy(() -> request.setCurdate(null));
            assertThatNullPointerException().isThrownBy(() -> request.setPgmname(null));
            assertThatNullPointerException().isThrownBy(() -> request.setTitle02(null));
            assertThatNullPointerException().isThrownBy(() -> request.setCurtime(null));
            assertThatNullPointerException().isThrownBy(() -> request.setPagenum(null));
            assertThatNullPointerException().isThrownBy(() -> request.setTrnidin(null));
            assertThatNullPointerException().isThrownBy(() -> request.setErrmsg(null));
            assertThatNullPointerException().isThrownBy(() -> request.setSelection(1, null));
        }

        @Test
        @DisplayName("all 59 header, row and error accessors store exactly what they are given")
        void everyAccessorStoresWhatItIsGiven() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTrnname("CT00");
            request.setTitle01("T1");
            request.setCurdate("08/08/26");
            request.setPgmname("COTRN00C");
            request.setTitle02("T2");
            request.setCurtime("12:34:56");
            request.setPagenum("00000001");
            request.setTrnidin("0000000000000001");
            request.setErrmsg("E");

            // Identity, not width. Each accessor returns the value it was given - "T1" is two
            // characters and stays two. Widening is the byte boundary's job, and is asserted there.
            assertThat(request.getTrnname()).isEqualTo("CT00");
            assertThat(request.getTitle01()).isEqualTo("T1");
            assertThat(request.getCurdate()).isEqualTo("08/08/26");
            assertThat(request.getPgmname()).isEqualTo("COTRN00C");
            assertThat(request.getTitle02()).isEqualTo("T2");
            assertThat(request.getCurtime()).isEqualTo("12:34:56");
            assertThat(request.getPagenum()).isEqualTo("00000001");
            assertThat(request.getTrnidin()).isEqualTo("0000000000000001");
            assertThat(request.getErrmsg()).isEqualTo("E");

            // ...and every one of them still lands at its declared width in the image.
            byte[] image = request.toFixedWidth(ASCII);
            assertThat(image).hasSize(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
            TransactionListRequest widened = TransactionListRequest.fromFixedWidth(image, ASCII);
            assertThat(widened.getTitle01()).hasSize(40).startsWith("T1").endsWith(" ");
            assertThat(widened.getErrmsg()).hasSize(78).startsWith("E");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Explicit row accessors - all fifty, under their verbatim names")
    class ExplicitRowAccessors {

        @Test
        @DisplayName("each of the fifty explicit setters writes the field its name spells")
        void allFiftyExplicitAccessors() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSel0001("1");
            request.setSel0002("2");
            request.setSel0003("3");
            request.setSel0004("4");
            request.setSel0005("5");
            request.setSel0006("6");
            request.setSel0007("7");
            request.setSel0008("8");
            request.setSel0009("9");
            request.setSel0010("0");
            request.setTrnid01("A1");
            request.setTrnid02("A2");
            request.setTrnid03("A3");
            request.setTrnid04("A4");
            request.setTrnid05("A5");
            request.setTrnid06("A6");
            request.setTrnid07("A7");
            request.setTrnid08("A8");
            request.setTrnid09("A9");
            request.setTrnid10("A0");
            request.setTdate01("B1");
            request.setTdate02("B2");
            request.setTdate03("B3");
            request.setTdate04("B4");
            request.setTdate05("B5");
            request.setTdate06("B6");
            request.setTdate07("B7");
            request.setTdate08("B8");
            request.setTdate09("B9");
            request.setTdate10("B0");
            request.setTdesc01("C1");
            request.setTdesc02("C2");
            request.setTdesc03("C3");
            request.setTdesc04("C4");
            request.setTdesc05("C5");
            request.setTdesc06("C6");
            request.setTdesc07("C7");
            request.setTdesc08("C8");
            request.setTdesc09("C9");
            request.setTdesc10("C0");
            request.setTamt001("D1");
            request.setTamt002("D2");
            request.setTamt003("D3");
            request.setTamt004("D4");
            request.setTamt005("D5");
            request.setTamt006("D6");
            request.setTamt007("D7");
            request.setTamt008("D8");
            request.setTamt009("D9");
            request.setTamt010("D0");

            assertThat(request.getSel0001()).isEqualTo("1");
            assertThat(request.getSel0002()).isEqualTo("2");
            assertThat(request.getSel0003()).isEqualTo("3");
            assertThat(request.getSel0004()).isEqualTo("4");
            assertThat(request.getSel0005()).isEqualTo("5");
            assertThat(request.getSel0006()).isEqualTo("6");
            assertThat(request.getSel0007()).isEqualTo("7");
            assertThat(request.getSel0008()).isEqualTo("8");
            assertThat(request.getSel0009()).isEqualTo("9");
            assertThat(request.getSel0010()).isEqualTo("0");
            assertThat(request.getTrnid01()).startsWith("A1");
            assertThat(request.getTrnid02()).startsWith("A2");
            assertThat(request.getTrnid03()).startsWith("A3");
            assertThat(request.getTrnid04()).startsWith("A4");
            assertThat(request.getTrnid05()).startsWith("A5");
            assertThat(request.getTrnid06()).startsWith("A6");
            assertThat(request.getTrnid07()).startsWith("A7");
            assertThat(request.getTrnid08()).startsWith("A8");
            assertThat(request.getTrnid09()).startsWith("A9");
            assertThat(request.getTrnid10()).startsWith("A0");
            assertThat(request.getTdate01()).startsWith("B1");
            assertThat(request.getTdate02()).startsWith("B2");
            assertThat(request.getTdate03()).startsWith("B3");
            assertThat(request.getTdate04()).startsWith("B4");
            assertThat(request.getTdate05()).startsWith("B5");
            assertThat(request.getTdate06()).startsWith("B6");
            assertThat(request.getTdate07()).startsWith("B7");
            assertThat(request.getTdate08()).startsWith("B8");
            assertThat(request.getTdate09()).startsWith("B9");
            assertThat(request.getTdate10()).startsWith("B0");
            assertThat(request.getTdesc01()).startsWith("C1");
            assertThat(request.getTdesc02()).startsWith("C2");
            assertThat(request.getTdesc03()).startsWith("C3");
            assertThat(request.getTdesc04()).startsWith("C4");
            assertThat(request.getTdesc05()).startsWith("C5");
            assertThat(request.getTdesc06()).startsWith("C6");
            assertThat(request.getTdesc07()).startsWith("C7");
            assertThat(request.getTdesc08()).startsWith("C8");
            assertThat(request.getTdesc09()).startsWith("C9");
            assertThat(request.getTdesc10()).startsWith("C0");
            assertThat(request.getTamt001()).startsWith("D1");
            assertThat(request.getTamt002()).startsWith("D2");
            assertThat(request.getTamt003()).startsWith("D3");
            assertThat(request.getTamt004()).startsWith("D4");
            assertThat(request.getTamt005()).startsWith("D5");
            assertThat(request.getTamt006()).startsWith("D6");
            assertThat(request.getTamt007()).startsWith("D7");
            assertThat(request.getTamt008()).startsWith("D8");
            assertThat(request.getTamt009()).startsWith("D9");
            assertThat(request.getTamt010()).startsWith("D0");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The name-keyed view - what field-for-field diffing consumes")
    class NameKeyedView {

        @Test
        @DisplayName("every one of the 59 fields is reachable by its verbatim name, in order")
        void allFieldsReachableByName() {
            TransactionListRequest request = new TransactionListRequest();
            for (String field : COPYBOOK_FIELDS) {
                request.setPayloadValue(field, "Z");
            }
            assertThat(request.getPayloadValues().keySet())
                    .containsExactlyElementsOf(COPYBOOK_FIELDS);
            for (String field : COPYBOOK_FIELDS) {
                assertThat(request.getPayloadValue(field)).as("%s", field).startsWith("Z");
            }
        }

        @Test
        @DisplayName("setting by name and by accessor are the same operation")
        void setByNameEqualsSetByAccessor() {
            TransactionListRequest byName = new TransactionListRequest();
            TransactionListRequest byAccessor = new TransactionListRequest();
            byName.setPayloadValue("TAMT010", "+00000009.99");
            byAccessor.setTamt010("+00000009.99");
            byName.setPayloadValue("SEL0010", "S");
            byAccessor.setSel0010("S");
            byName.setPayloadValue("ERRMSG", "boom");
            byAccessor.setErrmsg("boom");
            byName.setPayloadValue("TRNNAME", "CT00");
            byAccessor.setTrnname("CT00");
            byName.setPayloadValue("TITLE01", "one");
            byAccessor.setTitle01("one");
            byName.setPayloadValue("CURDATE", "08/08/26");
            byAccessor.setCurdate("08/08/26");
            byName.setPayloadValue("PGMNAME", "COTRN00C");
            byAccessor.setPgmname("COTRN00C");
            byName.setPayloadValue("TITLE02", "two");
            byAccessor.setTitle02("two");
            byName.setPayloadValue("CURTIME", "01:02:03");
            byAccessor.setCurtime("01:02:03");
            byName.setPayloadValue("PAGENUM", "00000002");
            byAccessor.setPagenum("00000002");
            byName.setPayloadValue("TRNIDIN", "0000000000000005");
            byAccessor.setTrnidin("0000000000000005");
            assertThat(byName.getPayloadValues()).isEqualTo(byAccessor.getPayloadValues());
        }

        @Test
        @DisplayName("an unknown field name is rejected, and the message names the suffix trap")
        void unknownFieldRejected() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.getPayloadValue("TAMT01"))
                    .withMessageContaining("TAMT001");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setPayloadValue("SEL01", "S"))
                    .withMessageContaining("SEL0001");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.getPayloadValue("NOPE"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.setPayloadValue("NOPE", "x"));
            assertThatNullPointerException().isThrownBy(() -> request.getPayloadValue(null));
            assertThatNullPointerException().isThrownBy(() -> request.setPayloadValue(null, "x"));
        }

        @Test
        @DisplayName("the returned map is immutable, so a caller cannot mutate the payload through it")
        void payloadValuesImmutable() {
            Map<String, String> values = new TransactionListRequest().getPayloadValues();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> values.put("TRNNAME", "x"));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Field metadata - signed length item, and xxxF / xxxA over one byte")
    class Metadata {

        @Test
        @DisplayName("every one of the 59 fields has a metadata carrier, keyed in declaration order")
        void metadataForEveryField() {
            TransactionListRequest request = new TransactionListRequest();
            assertThat(request.getFieldMetadata()).hasSize(59);
            assertThat(request.getFieldMetadata().keySet())
                    .containsExactlyElementsOf(COPYBOOK_FIELDS);
            for (String field : COPYBOOK_FIELDS) {
                assertThat(request.getMetadata(field).getBaseFieldName()).isEqualTo(field);
            }
        }

        @Test
        @DisplayName("the length item is signed and holds -1, the CICS cursor request")
        void lengthItemHoldsMinusOne() {
            TransactionListRequest request = new TransactionListRequest();
            FieldMetadata metadata = request.getMetadata("TRNIDIN");
            assertThat(metadata.getLengthItem()).isZero();
            assertThat(metadata.isCursorPositionRequested()).isFalse();

            request.positionCursorAt("TRNIDIN");
            assertThat(metadata.getLengthItem()).isEqualTo((short) -1);
            assertThat(TransactionListRequest.CURSOR_POSITION_REQUEST).isEqualTo((short) -1);
            assertThat(metadata.isCursorPositionRequested()).isTrue();
            assertThat(request.getCursorPositionField()).isEqualTo("TRNIDIN");
        }

        @Test
        @DisplayName("no field carries the cursor until one is asked to")
        void noCursorFieldInitially() {
            assertThat(new TransactionListRequest().getCursorPositionField()).isNull();
        }

        @Test
        @DisplayName("a reported input length is positive; zero and -1 are not input")
        void hasReportedInput() {
            FieldMetadata metadata = new FieldMetadata("TRNIDIN");
            assertThat(metadata.hasReportedInput()).isFalse();
            metadata.setLengthItem((short) 16);
            assertThat(metadata.hasReportedInput()).isTrue();
            assertThat(metadata.isCursorPositionRequested()).isFalse();
            metadata.requestCursorPosition();
            assertThat(metadata.hasReportedInput()).isFalse();
            assertThat(metadata.isCursorPositionRequested()).isTrue();
        }

        @Test
        @DisplayName("xxxF and xxxA are one byte - writing either is visible through the other")
        void flagAndAttributeAreAliases() {
            FieldMetadata metadata = new FieldMetadata("ERRMSG");
            assertThat(metadata.isFlagLowValues()).isTrue();
            assertThat(metadata.getFlag()).isEqualTo(FieldMetadata.FLAG_ITEM_LOW_VALUES);
            assertThat(metadata.getAttribute()).isEqualTo(metadata.getFlag());

            metadata.setFlag("X");
            assertThat(metadata.getFlag()).isEqualTo("X");
            assertThat(metadata.getAttribute()).isEqualTo("X");
            assertThat(metadata.isFlagLowValues()).isFalse();

            metadata.setAttribute("Z");
            assertThat(metadata.getAttribute()).isEqualTo("Z");
            assertThat(metadata.getFlag()).isEqualTo("Z");
        }

        @Test
        @DisplayName("the flag byte is exactly one character, padded or truncated as PIC X requires")
        void flagByteIsOneCharacter() {
            FieldMetadata metadata = new FieldMetadata("ERRMSG");
            // CICS reports one attribute byte per field, so three characters is a caller defect rather
            // than data to be shortened.
            assertThatThrownBy(() -> metadata.setFlag("ABC"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ERRMSGF")
                    .hasMessageContaining("3 character(s)");
            metadata.setFlag("A");
            assertThat(metadata.getFlag()).isEqualTo("A").hasSize(1);
            metadata.setAttribute("");
            assertThat(metadata.getAttribute()).isEqualTo(" ").hasSize(1);
            assertThat(FieldMetadata.FLAG_ITEM_BYTES).isEqualTo(1);
            assertThat(FieldMetadata.LENGTH_ITEM_BYTES).isEqualTo(2);
            assertThat(FieldMetadata.LENGTH_ITEM_NONE).isZero();
        }

        @Test
        @DisplayName("reset restores the initial state")
        void resetRestoresInitialState() {
            FieldMetadata metadata = new FieldMetadata("TRNIDIN");
            metadata.requestCursorPosition();
            metadata.setFlag("Q");
            metadata.reset();
            assertThat(metadata.getLengthItem()).isZero();
            assertThat(metadata.isFlagLowValues()).isTrue();
        }

        @Test
        @DisplayName("clearAllFields resets every metadata carrier too")
        void clearAllFieldsResetsMetadata() {
            TransactionListRequest request = new TransactionListRequest();
            request.positionCursorAt("TRNIDIN");
            request.getMetadata("ERRMSG").setFlag("Q");
            request.clearAllFields();
            assertThat(request.getCursorPositionField()).isNull();
            assertThat(request.getMetadata("ERRMSG").isFlagLowValues()).isTrue();
        }

        @Test
        @DisplayName("metadata is rejected for an unknown or null field name")
        void metadataRejectsUnknownField() {
            TransactionListRequest request = new TransactionListRequest();
            assertThatIllegalArgumentException().isThrownBy(() -> request.getMetadata("TAMT01"));
            assertThatNullPointerException().isThrownBy(() -> request.getMetadata(null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> request.positionCursorAt("NOPE"));
            assertThatNullPointerException().isThrownBy(() -> new FieldMetadata((String) null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new FieldMetadata((FieldMetadata) null));
            assertThatNullPointerException().isThrownBy(() -> request.getMetadata("ERRMSG").setFlag(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> request.getMetadata("ERRMSG").setAttribute(null));
        }

        @Test
        @DisplayName("the metadata map is unmodifiable, though its carriers stay live")
        void metadataMapUnmodifiable() {
            TransactionListRequest request = new TransactionListRequest();
            Map<String, FieldMetadata> map = request.getFieldMetadata();
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> map.remove("TRNNAME"));
            map.get("TRNNAME").setFlag("L");
            assertThat(request.getMetadata("TRNNAME").getFlag()).isEqualTo("L");
        }

        @Test
        @DisplayName("metadata carries value semantics and a diagnostic summary")
        void metadataValueSemantics() {
            FieldMetadata first = new FieldMetadata("TRNIDIN");
            FieldMetadata second = new FieldMetadata("TRNIDIN");
            FieldMetadata other = new FieldMetadata("ERRMSG");
            assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(other).isNotEqualTo(null).isNotEqualTo("TRNIDIN");

            second.requestCursorPosition();
            assertThat(first).isNotEqualTo(second);
            FieldMetadata third = new FieldMetadata("TRNIDIN");
            third.setFlag("A");
            assertThat(first).isNotEqualTo(third);

            FieldMetadata copy = new FieldMetadata(second);
            assertThat(copy).isEqualTo(second);
            assertThat(second.toString()).contains("TRNIDIN").contains("-1").contains("X'00'");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The pagination cursor - 58 bytes, both 88-levels, commarea 218")
    class Cursor {

        @Test
        @DisplayName("16 + 16 + 8 + 1 + 1 + 16 = 58, and 160 + 58 = 218")
        void cursorGeometry() {
            assertThat(PaginationCursor.TRNID_FIRST_LENGTH).isEqualTo(16);
            assertThat(PaginationCursor.TRNID_LAST_LENGTH).isEqualTo(16);
            assertThat(PaginationCursor.PAGE_NUM_LENGTH).isEqualTo(8);
            assertThat(PaginationCursor.NEXT_PAGE_FLG_LENGTH).isEqualTo(1);
            assertThat(PaginationCursor.TRN_SEL_FLG_LENGTH).isEqualTo(1);
            assertThat(PaginationCursor.TRN_SELECTED_LENGTH).isEqualTo(16);
            assertThat(PaginationCursor.CURSOR_LENGTH).isEqualTo(58);
            assertThat(NavigationContext.COMMAREA_LENGTH).isEqualTo(160);
            assertThat(PaginationCursor.COMMAREA_LENGTH).isEqualTo(218);
            assertThat(PaginationCursor.LAYOUT.recordLength()).isEqualTo(58);
            assertThat(PaginationCursor.LAYOUT.storageSpans()).hasSize(6);
        }

        @Test
        @DisplayName("the offsets are 0, 16, 32, 40, 41 and 42")
        void cursorOffsets() {
            assertThat(PaginationCursor.TRNID_FIRST_OFFSET).isZero();
            assertThat(PaginationCursor.TRNID_LAST_OFFSET).isEqualTo(16);
            assertThat(PaginationCursor.PAGE_NUM_OFFSET).isEqualTo(32);
            assertThat(PaginationCursor.NEXT_PAGE_FLG_OFFSET).isEqualTo(40);
            assertThat(PaginationCursor.TRN_SEL_FLG_OFFSET).isEqualTo(41);
            assertThat(PaginationCursor.TRN_SELECTED_OFFSET).isEqualTo(42);
            assertThat(PaginationCursor.TRN_SELECTED_OFFSET + 16).isEqualTo(58);
        }

        @Test
        @DisplayName("the spans carry their verbatim CDEMO-CT00 names")
        void cursorSpanNames() {
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRNID-FIRST")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRNID-LAST")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-PAGE-NUM")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-NEXT-PAGE-FLG")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRN-SEL-FLG")).isTrue();
            assertThat(PaginationCursor.LAYOUT.hasSpan("CDEMO-CT00-TRN-SELECTED")).isTrue();
            assertThat(PaginationCursor.TRNID_FIRST_FIELD).isEqualTo("CDEMO-CT00-TRNID-FIRST");
            assertThat(PaginationCursor.PAGE_NUM_FIELD).isEqualTo("CDEMO-CT00-PAGE-NUM");
        }

        @Test
        @DisplayName("the next-page flag defaults to 'N', reproducing the copybook VALUE clause")
        void nextPageDefaultsToNo() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.getNextPageFlg()).isEqualTo("N");
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(PaginationCursor.LAYOUT.span("CDEMO-CT00-NEXT-PAGE-FLG").initialValue())
                    .isEqualTo("N");
        }

        @Test
        @DisplayName("both 88-levels are reachable, as COTRN00C sets each on distinct paths")
        void bothConditionNamesReachable() {
            PaginationCursor cursor = new PaginationCursor();
            cursor.setNextPageYes();
            assertThat(cursor.isNextPageYes()).isTrue();
            assertThat(cursor.isNextPageNo()).isFalse();
            assertThat(cursor.getNextPageFlg()).isEqualTo(PaginationCursor.NEXT_PAGE_YES);

            cursor.setNextPageNo();
            assertThat(cursor.isNextPageNo()).isTrue();
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.getNextPageFlg()).isEqualTo(PaginationCursor.NEXT_PAGE_NO);

            cursor.setNextPageFlg("?");
            assertThat(cursor.isNextPageYes()).isFalse();
            assertThat(cursor.isNextPageNo()).isFalse();
        }

        @Test
        @DisplayName("the page number is a scale-free integer, rendered as eight zero-filled digits")
        void pageNumberIsInteger() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.getPageNum()).isZero();
            assertThat(cursor.getPageNumImage()).isEqualTo("00000000").hasSize(8);
            cursor.setPageNum(42);
            assertThat(cursor.getPageNum()).isEqualTo(42);
            assertThat(cursor.getPageNumImage()).isEqualTo("00000042");
            cursor.setPageNum(PaginationCursor.PAGE_NUM_MAX);
            assertThat(cursor.getPageNumImage()).isEqualTo("99999999");
        }

        @Test
        @DisplayName("PIC 9(08) is unsigned and eight digits wide, so both bounds are enforced")
        void pageNumberBounds() {
            PaginationCursor cursor = new PaginationCursor();
            assertThatIllegalArgumentException().isThrownBy(() -> cursor.setPageNum(-1))
                    .withMessageContaining("unsigned");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> cursor.setPageNum(PaginationCursor.PAGE_NUM_MAX + 1))
                    .withMessageContaining("digits");
        }

        @Test
        @DisplayName("the browse keys and the selection are stored at their declared widths")
        void browseKeysAndSelection() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.getTrnidFirst()).isEqualTo(spaces(16));
            assertThat(cursor.getTrnidLast()).isEqualTo(spaces(16));
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(spaces(16));

            cursor.setTrnidFirst("0000000000000011");
            cursor.setTrnidLast("0000000000000020");
            cursor.setTrnSelFlg(PaginationCursor.SELECTION_VIEW);
            cursor.setTrnSelected("0000000000000015");
            assertThat(cursor.getTrnidFirst()).isEqualTo("0000000000000011");
            assertThat(cursor.getTrnidLast()).isEqualTo("0000000000000020");
            assertThat(cursor.getTrnSelFlg()).isEqualTo("S");
            assertThat(cursor.getTrnSelected()).isEqualTo("0000000000000015");
        }

        @Test
        @DisplayName("a selection needs BOTH fields present, mirroring the compound guard at L183")
        void selectionPresenceNeedsBothFields() {
            PaginationCursor cursor = new PaginationCursor();
            assertThat(cursor.isSelectionPresent()).isFalse();

            cursor.setTrnSelFlg("S");
            assertThat(cursor.isSelectionPresent()).isFalse();

            cursor.setTrnSelected("0000000000000015");
            assertThat(cursor.isSelectionPresent()).isTrue();

            cursor.clearSelection();
            assertThat(cursor.isSelectionPresent()).isFalse();
            assertThat(cursor.getTrnSelFlg()).isEqualTo(" ");
            assertThat(cursor.getTrnSelected()).isEqualTo(spaces(16));
        }

        @Test
        @DisplayName("LOW-VALUES counts as absent, exactly as the COBOL guard treats it")
        void lowValuesCountsAsAbsent() {
            PaginationCursor cursor = new PaginationCursor();
            cursor.setTrnSelFlg("\u0000");
            cursor.setTrnSelected("\u0000".repeat(16));
            assertThat(cursor.isSelectionPresent()).isFalse();
        }

        @Test
        @DisplayName("the cursor round-trips through its 58-byte image")
        void cursorRoundTrip() {
            PaginationCursor cursor = new PaginationCursor();
            cursor.setTrnidFirst("0000000000000011");
            cursor.setTrnidLast("0000000000000020");
            cursor.setPageNum(7);
            cursor.setNextPageYes();
            cursor.setTrnSelFlg("S");
            cursor.setTrnSelected("0000000000000015");

            byte[] image = cursor.toFixedWidth(ASCII);
            assertThat(image).hasSize(58);
            PaginationCursor restored = PaginationCursor.fromFixedWidth(image, ASCII);
            assertThat(restored).isEqualTo(cursor).hasSameHashCodeAs(cursor);
            assertThat(restored.getPageNum()).isEqualTo(7);
            assertThat(restored.isNextPageYes()).isTrue();
            assertThat(new String(image, 32, 8, ASCII)).isEqualTo("00000007");
        }

        @Test
        @DisplayName("the cursor rejects null arguments and a wrong-length image")
        void cursorRejectsBadInput() {
            PaginationCursor cursor = new PaginationCursor();
            assertThatNullPointerException().isThrownBy(() -> cursor.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidFirst(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnidLast(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setNextPageFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelFlg(null));
            assertThatNullPointerException().isThrownBy(() -> cursor.setTrnSelected(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> PaginationCursor.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> PaginationCursor.fromFixedWidth(new byte[58], null));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PaginationCursor.fromFixedWidth(new byte[57], ASCII));
            assertThatNullPointerException().isThrownBy(() -> new PaginationCursor(null));
        }

        @Test
        @DisplayName("the cursor carries value semantics and a diagnostic summary")
        void cursorValueSemantics() {
            PaginationCursor first = new PaginationCursor();
            PaginationCursor second = new PaginationCursor();
            assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(null).isNotEqualTo("cursor");

            PaginationCursor copy = new PaginationCursor(first);
            assertThat(copy).isEqualTo(first);

            second.setPageNum(3);
            assertThat(first).isNotEqualTo(second);

            PaginationCursor differing = new PaginationCursor();
            differing.setTrnidFirst("A");
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setTrnidLast("A");
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setNextPageYes();
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setTrnSelFlg("S");
            assertThat(first).isNotEqualTo(differing);
            differing = new PaginationCursor();
            differing.setTrnSelected("A");
            assertThat(first).isNotEqualTo(differing);

            assertThat(second.toString()).contains("page=3").contains("nextPage=N");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Statelessness - the COMMAREA travels in the payload, never in a session")
    class Statelessness {

        @Test
        @DisplayName("a fresh request carries an empty context and a fresh cursor")
        void freshCarriedState() {
            TransactionListRequest request = new TransactionListRequest();

            // No area on a fresh request: nothing has been passed to it, which is EIBCALEN = 0.
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();

            // The cursor, which has no absence semantics, does get its fresh default.
            assertThat(request.getCursor()).isEqualTo(new PaginationCursor());
        }

        @Test
        @DisplayName("ENTER and REENTER both delegate to the single carried CDEMO-PGM-CONTEXT")
        void enterAndReenterDelegate() {
            TransactionListRequest request = new TransactionListRequest();
            request.setNavigationContext(NavigationContext.empty().withPgmEnter());
            assertThat(request.isEnter()).isTrue();
            assertThat(request.isReenter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(request.toString()).contains("context=ENTER");

            request.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(request.isReenter()).isTrue();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(request.toString()).contains("context=REENTER");
        }

        @Test
        @DisplayName("the carried state is required, so absence is stated explicitly")
        void carriedStateRequired() {
            TransactionListRequest request = new TransactionListRequest();

            // The commarea accepts null, because null IS a state: EIBCALEN = 0, which COTRN00C.cbl:107
            // tests for and answers by transferring to COSGN00C. This setter used to reject null and
            // advise passing NavigationContext.empty() instead, but an initialised area reports 160
            // bytes and takes the opposite branch, so that advice removed the only spelling the cold
            // start had.
            request.setNavigationContext(null);
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();

            // ...and when an area IS carried, EIBCALEN is the commarea plus this screen's own cursor.
            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.commareaLength())
                    .isEqualTo(PaginationCursor.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + PaginationCursor.CURSOR_LENGTH)
                    .isEqualTo(218);

            // The cursor is a different case: it has no absence semantics, so it is still required.
            assertThatNullPointerException().isThrownBy(() -> request.setCursor(null));
        }

        @Test
        @DisplayName("a cold start reports the enter context digit but is neither ENTER nor REENTER")
        void aColdStartReportsTheEnterDigitWithoutClaimingTheState() {
            TransactionListRequest cold = new TransactionListRequest();

            // getPgmContext answers with the byte the program would act as though it had - a cold start
            // paints and validates nothing, exactly as first entry does...
            assertThat(cold.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            // ...but isEnter() still refuses to claim the state, because there is no context byte.
            assertThat(cold.isEnter()).isFalse();
            assertThat(cold.isReenter()).isFalse();
            // And the diagnostic says which of the three states it is.
            assertThat(cold.toString()).contains("context=none (EIBCALEN=0)");

            TransactionListRequest warm = new TransactionListRequest();
            warm.setNavigationContext(NavigationContext.empty());
            assertThat(warm.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(warm.isEnter()).isTrue();
            assertThat(warm.toString()).contains("context=ENTER").doesNotContain("EIBCALEN");

            warm.setNavigationContext(NavigationContext.empty().withPgmReenter());
            assertThat(warm.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_REENTER);
            assertThat(warm.toString()).contains("context=REENTER");
        }

        @Test
        @DisplayName("with no area there are no commarea bytes to render, and none are invented")
        void aColdStartHasNoCommareaImage() {
            TransactionListRequest request = new TransactionListRequest();

            assertThatThrownBy(() -> request.toCommareaImage(ASCII))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EIBCALEN is 0")
                    .hasMessageContaining("hasNavigationContext");
        }

        @Test
        @DisplayName("the communication area is 160 + 58 = 218 bytes, context then cursor")
        void commareaImage() {
            TransactionListRequest request = new TransactionListRequest();
            request.setNavigationContext(NavigationContext.empty().withFromTranid("CT00"));
            request.getCursor().setPageNum(4);

            byte[] commarea = request.toCommareaImage(ASCII);
            assertThat(commarea).hasSize(218);
            assertThat(new String(commarea, 0, 4, ASCII)).isEqualTo("CT00");
            assertThat(new String(commarea, 160 + 32, 8, ASCII)).isEqualTo("00000004");
            assertThatNullPointerException().isThrownBy(() -> request.toCommareaImage(null));
        }

        @Test
        @DisplayName("the class declares no session or static mutable state")
        void noSessionOrStaticMutableState() {
            for (java.lang.reflect.Field field : TransactionListRequest.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Serialisation - padding survives a JSON round trip untrimmed")
    class Serialisation {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        /** Reads a payload back as a name-keyed tree, so property names can be asserted directly. */
        private static Map<String, Object> tree(String json) throws Exception {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        }

        @Test
        @DisplayName("the JSON property names are the copybook base names lower-cased")
        void propertyNamesMatchCopybook() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(new TransactionListRequest()));
            for (String field : COPYBOOK_FIELDS) {
                assertThat(tree).as("property for %s", field)
                        .containsKey(field.toLowerCase(java.util.Locale.ROOT));
            }
            assertThat(tree).containsKeys("sel0001", "sel0010", "trnid01", "trnid10",
                    "tamt001", "tamt010", "tdate01", "tdesc10", "errmsg", "pagenum", "trnidin");
        }

        @Test
        @DisplayName("metadata is never a payload member")
        void metadataNotSerialised() throws Exception {
            String json = MAPPER.writeValueAsString(new TransactionListRequest());
            assertThat(tree(json)).doesNotContainKeys("fieldMetadata", "payloadValues",
                    "cursorPositionField", "enter", "reenter", "pgmContext");
            assertThat(json).doesNotContain("trnidinL").doesNotContain("TRNIDINL");
        }

        @Test
        @DisplayName("the payload carries exactly the 59 fields plus the two carried structures")
        void payloadShape() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(new TransactionListRequest()));
            // 59 screen fields plus the three members that are not screen fields: the commarea, its
            // cursor, and the resolved EIBAID token COTRN00C.cbl:119 branches on.
            assertThat(tree).hasSize(59 + 3);
            assertThat(tree).containsKeys("navigationContext", "cursor", "aid");
        }

        @Test
        @DisplayName("space padding survives serialise and deserialise, untrimmed and equal")
        void paddingSurvivesRoundTrip() throws Exception {
            TransactionListRequest request = new TransactionListRequest();
            request.setErrmsg("Tran ID must be Numeric ...");
            request.setTdesc10("PAYMENT");
            request.setTamt010("+00000012.34");
            request.setTrnidin("0000000000000001");
            request.getCursor().setPageNum(2);
            request.getCursor().setNextPageYes();

            String json = MAPPER.writeValueAsString(request);
            TransactionListRequest restored = MAPPER.readValue(json, TransactionListRequest.class);

            // A JSON round trip is the identity now, whatever width the value happens to be: the
            // payload neither pads on the way in nor trims on the way out.
            assertThat(restored.getErrmsg()).isEqualTo(request.getErrmsg())
                    .isEqualTo("Tran ID must be Numeric ...");
            assertThat(restored.getTdesc10()).isEqualTo(request.getTdesc10()).isEqualTo("PAYMENT");
            assertThat(restored.getTamt010()).hasSize(12).isEqualTo(request.getTamt010());
            assertThat(restored.getPayloadValues()).isEqualTo(request.getPayloadValues());
            assertThat(restored.getCursor().getPageNum()).isEqualTo(2);
            assertThat(restored.getCursor().isNextPageYes()).isTrue();
        }

        @Test
        @DisplayName("a blank row serialises as spaces, never as null")
        void blankRowSerialisesAsSpaces() throws Exception {
            String json = MAPPER.writeValueAsString(new TransactionListRequest());
            Map<String, Object> tree = tree(json);

            // Every screen field is its declared width in spaces - the constructor performs COBOL's
            // unconditional MOVE SPACES, which is a different rule from the alphanumeric MOVE and is
            // unaffected by the setters no longer padding.
            assertThat(tree.get("tamt001")).isEqualTo(spaces(12));
            assertThat(tree.get("errmsg")).isEqualTo(spaces(78));
            for (String fieldName : TransactionListRequest.FIELD_NAMES) {
                assertThat(tree.get(fieldName.toLowerCase(java.util.Locale.ROOT)))
                        .as("%s is spaces, never null", fieldName)
                        .isNotNull();
            }

            // The one null in the document is the communication area, and it is deliberate: it is the
            // EIBCALEN = 0 cold start, which has no other spelling.
            assertThat(tree.get("navigationContext")).isNull();
            assertThat(json.indexOf("null")).isEqualTo(json.lastIndexOf("null"));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("The fixed-width group image - 1265 bytes, lossless both ways")
    class FixedWidthImage {

        @Test
        @DisplayName("the image is 1265 bytes and round-trips field for field")
        void imageRoundTrip() {
            TransactionListRequest request = populated();
            byte[] image = request.toFixedWidth(ASCII);
            assertThat(image).hasSize(1265);

            TransactionListRequest restored = TransactionListRequest.fromFixedWidth(image, ASCII);
            assertThat(restored.getPayloadValues()).isEqualTo(request.getPayloadValues());
        }

        @Test
        @DisplayName("each field lands at its declared offset in the image")
        void fieldsLandAtDeclaredOffsets() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTrnname("CT00");
            request.setErrmsg("E");
            request.setSel0001("S");
            request.setTamt010("+00000001.23");
            byte[] image = request.toFixedWidth(ASCII);

            assertThat(new String(image, TransactionListRequest.TRNNAME_OFFSET, 4, ASCII))
                    .isEqualTo("CT00");
            assertThat(new String(image, TransactionListRequest.ERRMSG_OFFSET, 1, ASCII))
                    .isEqualTo("E");
            assertThat(new String(image,
                    TransactionListRequest.rowImageOffset(1)
                            + TransactionListRequest.SELECTION_ROW_OFFSET, 1, ASCII))
                    .isEqualTo("S");
            assertThat(new String(image,
                    TransactionListRequest.rowImageOffset(10)
                            + TransactionListRequest.TRANSACTION_AMOUNT_ROW_OFFSET, 12, ASCII))
                    .isEqualTo("+00000001.23");
        }

        @Test
        @DisplayName("the prefixes are emitted as spaces, so no offset after them can shift")
        void prefixesAreEmitted() {
            byte[] image = new TransactionListRequest().toFixedWidth(ASCII);
            assertThat(new String(image, 0, TransactionListRequest.TIOAPFX_PREFIX_LENGTH, ASCII))
                    .isEqualTo(spaces(12));
            assertThat(new String(image,
                    TransactionListRequest.TRNNAME_OFFSET
                            - TransactionListRequest.FIELD_PREFIX_LENGTH, 7, ASCII))
                    .isEqualTo(spaces(7));
        }

        @Test
        @DisplayName("writeInto and readFrom work over a caller-owned record area")
        void writeIntoAndReadFrom() {
            TransactionListRequest request = populated();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord record = codec.newRecord(TransactionListRequest.LAYOUT);
            request.writeInto(record);
            TransactionListRequest restored = TransactionListRequest.readFrom(record);
            assertThat(restored.getPayloadValues()).isEqualTo(request.getPayloadValues());
        }

        @Test
        @DisplayName("a wrong-length record area or image is rejected rather than tolerated")
        void wrongLengthRejected() {
            TransactionListRequest request = new TransactionListRequest();
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            FixedWidthRecord tooShort = new FixedWidthRecord(100, ASCII);

            assertThatIllegalArgumentException().isThrownBy(() -> request.writeInto(tooShort))
                    .withMessageContaining("1265");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionListRequest.readFrom(tooShort))
                    .withMessageContaining("1265");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> TransactionListRequest.fromFixedWidth(new byte[1264], ASCII));
            assertThatNullPointerException().isThrownBy(() -> request.toFixedWidth(null));
            assertThatNullPointerException().isThrownBy(() -> request.writeInto(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.readFrom(null));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.fromFixedWidth(null, ASCII));
            assertThatNullPointerException()
                    .isThrownBy(() -> TransactionListRequest.fromFixedWidth(new byte[1265], null));
            assertThat(codec.charset()).isEqualTo(ASCII);
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Copying and value semantics")
    class CopyingAndEquality {

        @Test
        @DisplayName("a copy is equal, independent, and deep-copies the mutable cursor")
        void copyIsEqualAndIndependent() {
            TransactionListRequest original = populated();
            original.positionCursorAt("TRNIDIN");
            TransactionListRequest copy = new TransactionListRequest(original);

            assertThat(copy).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(copy.getCursorPositionField()).isEqualTo("TRNIDIN");

            copy.setTamt010("+00000000.01");
            copy.getCursor().setPageNum(99);
            assertThat(original.getTamt010()).isNotEqualTo("+00000000.01");
            assertThat(original.getCursor().getPageNum()).isNotEqualTo(99);
            assertThat(copy).isNotEqualTo(original);
        }

        @Test
        @DisplayName("a null source cannot be copied")
        void nullSourceRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new TransactionListRequest(null));
        }

        @Test
        @DisplayName("equality covers the payload, the context, the cursor and the metadata")
        void equalityCoversEveryComponent() {
            TransactionListRequest first = new TransactionListRequest();
            TransactionListRequest second = new TransactionListRequest();
            assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
            assertThat(first).isNotEqualTo(null).isNotEqualTo("request");

            TransactionListRequest differentPayload = new TransactionListRequest();
            differentPayload.setErrmsg("x");
            assertThat(first).isNotEqualTo(differentPayload);

            TransactionListRequest differentContext = new TransactionListRequest();
            differentContext.setNavigationContext(
                    NavigationContext.empty().withFromTranid("CT00"));
            assertThat(first).isNotEqualTo(differentContext);

            TransactionListRequest differentCursor = new TransactionListRequest();
            differentCursor.getCursor().setPageNum(1);
            assertThat(first).isNotEqualTo(differentCursor);

            TransactionListRequest differentMetadata = new TransactionListRequest();
            differentMetadata.positionCursorAt("ERRMSG");
            assertThat(first).isNotEqualTo(differentMetadata);
        }

        @Test
        @DisplayName("toString names the screen and the cursor but never the field values")
        void toStringExcludesValues() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTdesc01("SENSITIVE MERCHANT NAME");
            request.setTamt001("+99999999.99");
            String rendered = request.toString();
            assertThat(rendered)
                    .contains("CT00")
                    .contains("COTRN00C")
                    .contains("COTRN0A")
                    .contains("fields=59")
                    .contains("PaginationCursor");
            assertThat(rendered)
                    .doesNotContain("SENSITIVE MERCHANT NAME")
                    .doesNotContain("+99999999.99");
        }

        @Test
        @DisplayName("clearAllFields returns a populated request to its initial state")
        void clearAllFieldsRestoresInitialState() {
            TransactionListRequest request = populated();
            request.clearAllFields();
            assertThat(request.getPayloadValues())
                    .isEqualTo(new TransactionListRequest().getPayloadValues());
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Screen-flow shapes taken from COTRN00C")
    class ScreenFlow {

        static List<String> selectorRows() {
            List<String> rows = new ArrayList<>();
            for (int row = 1; row <= 10; row++) {
                rows.add(String.valueOf(row));
            }
            return rows;
        }

        @ParameterizedTest(name = "a selector on row {0} is found in declaration order")
        @MethodSource("selectorRows")
        @DisplayName("the ordered first-match-wins scan finds a selector on any of the ten rows")
        void orderedSelectorScan(String rowText) {
            int row = Integer.parseInt(rowText);
            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(row, "000000000000000" + (row % 10));
            request.setSelection(row, "S");

            String found = null;
            String selected = null;
            for (int candidate = 1; candidate <= TransactionListRequest.ROW_COUNT; candidate++) {
                String selector = request.getSelection(candidate);
                if (!selector.isBlank()) {
                    found = selector;
                    selected = request.getTransactionId(candidate);
                    break;
                }
            }
            assertThat(found).isEqualTo("S");
            assertThat(selected).isEqualTo("000000000000000" + (row % 10));
        }

        @Test
        @DisplayName("a full page of ten rows populates every slot, first and last included")
        void fullPagePopulated() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= TransactionListRequest.PAGE_SIZE; row++) {
                request.setTransactionId(row, String.format("%016d", row));
                request.setTransactionDate(row, "01/01/24");
                request.setTransactionDescription(row, "TRANSACTION " + row);
                request.setTransactionAmount(row, String.format("+%08d.00", row));
            }
            request.getCursor().setTrnidFirst(request.getTransactionId(1));
            request.getCursor().setTrnidLast(
                    request.getTransactionId(TransactionListRequest.PAGE_SIZE));

            assertThat(request.getCursor().getTrnidFirst()).isEqualTo("0000000000000001");
            assertThat(request.getCursor().getTrnidLast()).isEqualTo("0000000000000010");
            assertThat(request.getTamt001()).isEqualTo("+00000001.00");
            assertThat(request.getTamt010()).isEqualTo("+00000010.00");
        }

        @Test
        @DisplayName("a partial page leaves the unused rows as spaces")
        void partialPageLeavesSpaces() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= 3; row++) {
                request.setTransactionId(row, String.format("%016d", row));
                request.setTransactionAmount(row, String.format("+%08d.00", row));
            }
            for (int row = 4; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(request.getTransactionId(row)).isEqualTo(spaces(16));
                assertThat(request.getTransactionAmount(row)).isEqualTo(spaces(12));
            }
        }
    }

    /** A request with every one of the 59 fields distinctly populated. */
    /**
     * A fully populated request, every field at exactly its declared width.
     *
     * <p>Declared width deliberately: that is what a 3270 {@code RECEIVE MAP} delivers, so it is the
     * shape the fixed-width round trip must reproduce byte for byte. The setters no longer pad, so a
     * fixture holding short values would round-trip to the padded form rather than to itself - which is
     * correct behaviour but a different property, and it is asserted on its own in
     * {@code shortValueIsStoredUnchanged} rather than smuggled into every round-trip test here.
     */
    @Nested
    @DisplayName("The AID and the width contract - gate G37 and the maximum-only validation shape")
    class KeyIndicationAndWidthContract {

        @Test
        @DisplayName("every one of the 59 constraints states a maximum only, never an exact width")
        void constraintsAreMaximumOnly() throws Exception {
            int checked = 0;
            for (String baseFieldName : TransactionListRequest.FIELD_NAMES) {
                String member = baseFieldName.toLowerCase(java.util.Locale.ROOT);
                Size size = TransactionListRequest.class.getDeclaredField(member)
                        .getAnnotation(Size.class);
                assertThat(size).as("@Size on %s", member).isNotNull();
                // A minimum would make a short value invalid, and a short value is legitimate: several
                // of these fields are tested against SPACES OR LOW-VALUES by the program itself.
                assertThat(size.min()).as("@Size(min) on %s must be the default 0", member).isZero();
                assertThat(size.max()).as("@Size(max) on %s", member).isPositive();
                checked++;
            }
            assertThat(checked).isEqualTo(TransactionListRequest.FIELD_COUNT).isEqualTo(59);
        }

        @Test
        @DisplayName("a short value is valid, and an over-long one cannot even be stored")
        void theConstraintIsAnActualCheck() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();

                TransactionListRequest shortValues = new TransactionListRequest();
                shortValues.setTitle01("T1");
                shortValues.setErrmsg("E");
                assertThat(validator.validate(shortValues))
                        .as("a value narrower than its field is legitimate")
                        .isEmpty();
            }

            // And the surplus case never reaches Bean Validation at all: the setter refuses it, so it
            // cannot be shortened into validity the way it used to be.
            assertThatThrownBy(() -> new TransactionListRequest().setTitle01("X".repeat(41)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TITLE01")
                    .hasMessageContaining("41 character(s)");
        }

        @Test
        @DisplayName("the AID token is five characters and starts at no key resolved")
        void theAidTokenIsFiveCharacters() {
            assertThat(TransactionListRequest.AID_LENGTH)
                    .isEqualTo(PfKeyResolver.AID_TOKEN_LENGTH);
            assertThat(TransactionListRequest.AID_FIELD).isEqualTo("EIBAID");
            assertThat(new TransactionListRequest().getAid()).isEqualTo(spaces(5));
        }

        @Test
        @DisplayName("all five arms of EVALUATE EIBAID are selectable, paging included")
        void everyArmIsSelectable() {
            TransactionListRequest request = new TransactionListRequest();

            // ENTER acts on the selected row; PF3 returns; PF7 and PF8 are the two paging directions,
            // which are the whole purpose of this screen and live entirely on this member.
            for (PfKeyResolver.AidKey key : List.of(PfKeyResolver.AidKey.ENTER,
                    PfKeyResolver.AidKey.PFK03, PfKeyResolver.AidKey.PFK07,
                    PfKeyResolver.AidKey.PFK08)) {
                request.setAid(key.token());
                assertThat(request.getAid()).isEqualTo(key.token())
                        .hasSize(TransactionListRequest.AID_LENGTH);
            }

            // ...and WHEN OTHER, which spaces select.
            request.setAid(null);
            assertThat(request.getAid()).isEqualTo(spaces(5));
        }

        @Test
        @DisplayName("the AID is on the wire, in value semantics, and outside the 59-field projection")
        void theAidIsCarriedAndCounted() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionListRequest before = new TransactionListRequest();
            before.setAid(PfKeyResolver.AidKey.PFK08.token());

            TransactionListRequest after = mapper.readValue(mapper.writeValueAsString(before),
                    TransactionListRequest.class);
            assertThat(after.getAid()).isEqualTo(PfKeyResolver.AidKey.PFK08.token());
            assertThat(after).isEqualTo(before);

            // Two requests differing only in the key pressed are different requests - PF7 pages back
            // where PF8 pages forward.
            TransactionListRequest paging = new TransactionListRequest();
            paging.setAid(PfKeyResolver.AidKey.PFK07.token());
            assertThat(paging).isNotEqualTo(before);

            // It is not a screen field: not in FIELD_NAMES, and the image width is unchanged.
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .doesNotContain(TransactionListRequest.AID_FIELD);
            assertThat(before.toFixedWidth(ASCII))
                    .hasSize(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
        }

        @Test
        @DisplayName("an over-long token is refused by name")
        void anOverLongTokenIsRefused() {
            assertThatThrownBy(() -> new TransactionListRequest().setAid("PFK012"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(TransactionListRequest.AID_FIELD)
                    .hasMessageContaining("6 character(s)");
        }
    }

    // =================================================================================================

    /** A value space-padded to a declared width - what a RECEIVE MAP would have delivered. */
    private static String atWidth(String value, int width) {
        return value + " ".repeat(width - value.length());
    }

    private static TransactionListRequest populated() {
        TransactionListRequest request = new TransactionListRequest();
        request.setTrnname("CT00");
        request.setTitle01(atWidth("AWS Mainframe Modernization",
                TransactionListRequest.TITLE01_LENGTH));
        request.setCurdate("08/08/26");
        request.setPgmname("COTRN00C");
        request.setTitle02(atWidth("CardDemo", TransactionListRequest.TITLE02_LENGTH));
        request.setCurtime("09:10:11");
        request.setPagenum("00000001");
        request.setTrnidin("0000000000000001");
        request.setErrmsg(atWidth("Invalid selection. Valid value is S",
                TransactionListRequest.ERRMSG_LENGTH));
        for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
            request.setSelection(row, row == 1 ? "S" : " ");
            request.setTransactionId(row, String.format("%016d", row));
            request.setTransactionDate(row, "0" + (row % 10) + "/02/24");
            request.setTransactionDescription(row, atWidth("DESCRIPTION FOR ROW " + row,
                    TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH));
            request.setTransactionAmount(row, String.format("+%08d.99", row));
        }
        request.setNavigationContext(NavigationContext.empty()
                .withFromTranid("CT00")
                .withFromProgram("COTRN00C")
                .withPgmReenter());
        request.getCursor().setTrnidFirst("0000000000000001");
        request.getCursor().setTrnidLast("0000000000000010");
        request.getCursor().setPageNum(1);
        request.getCursor().setNextPageYes();
        return request;
    }
}
