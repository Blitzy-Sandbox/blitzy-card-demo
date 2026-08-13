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
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.FieldMetadata;
import com.vsergeychik.carddemo.transaction.dto.TransactionListRequest.PaginationCursor;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Parity tests for {@link TransactionListRequest}, the inbound payload of {@code GET /api/transactions} and
 * the Java projection of {@code 01 COTRN0AI} at {@code app/cpy-bms/COTRN00.CPY:17}, whose {@code REDEFINES}
 * alias {@code 01 COTRN0AO} follows at {@code app/cpy-bms/COTRN00.CPY:373} over the identical bytes.
 */
@DisplayName("TransactionListRequest - COTRN0AI projection of COTRN00 / CT00")
class TransactionListRequestTest {
    private static final Charset ASCII = StandardCharsets.US_ASCII;

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

    private static final List<String> INPUT_CAPABLE_FIELDS = List.of(
            "TRNIDIN",
            "SEL0001", "SEL0002", "SEL0003", "SEL0004", "SEL0005",
            "SEL0006", "SEL0007", "SEL0008", "SEL0009", "SEL0010");

    private static final List<String> ACTED_ON_AID_TOKENS =
            List.of("ENTER", "PFK03", "PFK07", "PFK08");

    private static final List<String> SCHEMA_SHAPED_ANNOTATIONS = List.of(
            "Entity", "Table", "Column", "Id", "Version", "GeneratedValue", "Embeddable",
            "MappedSuperclass", "JoinColumn", "SequenceGenerator");

    private static final List<String> FORBIDDEN_ROUNDING_MODES =
            List.of("HALF_UP", "HALF_DOWN", "HALF_EVEN", "CEILING", "FLOOR", "UP");

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    @Nested
    @DisplayName("Field inventory - 59 fields, reconciling as 8 + 10 x 5 + 1 - rule R1, gate G9")
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

    @Nested
    @DisplayName("Suffix spelling - four, two and three digits, never normalised - practice B4")
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

    @Nested
    @DisplayName("Byte geometry - widths sum to 840, the image is 1265 - rule R5, gates G9, G21, G34")
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
        @DisplayName("each field's 7-byte prefix is 2 + 1 + 4, positioned at k, k+2 and k+3 - gate G34")
        void everyFieldPrefixDecomposes() {
            int lengthItemBytes = FieldMetadata.LENGTH_ITEM_BYTES;
            int flagItemBytes = FieldMetadata.FLAG_ITEM_BYTES;
            int attributeAliasBytes = 0;
            int reservedFillerBytes = 4;
            assertThat(lengthItemBytes).isEqualTo(2);
            assertThat(flagItemBytes).isEqualTo(1);
            assertThat(lengthItemBytes + flagItemBytes + attributeAliasBytes + reservedFillerBytes)
                    .isEqualTo(TransactionListRequest.FIELD_PREFIX_LENGTH)
                    .isEqualTo(7);

            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String baseFieldName = COPYBOOK_FIELDS.get(i);
                FieldSpan payload = TransactionListRequest.LAYOUT
                        .span(baseFieldName + "I");
                int k = payload.offset() - TransactionListRequest.FIELD_PREFIX_LENGTH;

                FieldSpan prefix = spanAt(k);
                assertThat(prefix.kind()).as("prefix kind at %s", baseFieldName)
                        .isEqualTo(PictureKind.FILLER);
                assertThat(prefix.length()).as("prefix width at %s", baseFieldName).isEqualTo(7);
                assertThat(prefix.endOffsetExclusive())
                        .as("the prefix ends exactly where %sI begins", baseFieldName)
                        .isEqualTo(payload.offset());

                assertThat(k + lengthItemBytes).as("xxxF of %s sits at k+2", baseFieldName)
                        .isEqualTo(k + 2);
                assertThat(k + lengthItemBytes + flagItemBytes)
                        .as("the reserved FILLER of %s starts at k+3", baseFieldName)
                        .isEqualTo(k + 3);
                assertThat(k + lengthItemBytes + flagItemBytes + reservedFillerBytes)
                        .as("%sI starts at k+7", baseFieldName)
                        .isEqualTo(payload.offset());
                assertThat(payload.endOffsetExclusive())
                        .as("%sI ends at k+7+n", baseFieldName)
                        .isEqualTo(k + 7 + COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("dropping the reserved FILLER would lose 236 bytes - gate G21")
        void theReservedFillerIsLoadBearing() {
            int reservedPerField = 4;
            int reservedTotal = TransactionListRequest.FIELD_COUNT * reservedPerField;
            assertThat(reservedTotal).isEqualTo(236);
            assertThat(TransactionListRequest.SYMBOLIC_MAP_LENGTH - reservedTotal)
                    .as("the group would be 1029 bytes, not 1265")
                    .isEqualTo(1029);

            byte[] image = new TransactionListRequest().toFixedWidth(ASCII);
            String text = new String(image, ASCII);
            for (String baseFieldName : COPYBOOK_FIELDS) {
                int k = TransactionListRequest.LAYOUT.span(baseFieldName + "I").offset()
                        - TransactionListRequest.FIELD_PREFIX_LENGTH;
                assertThat(text.substring(k, k + TransactionListRequest.FIELD_PREFIX_LENGTH))
                        .as("the whole prefix of %s is space-filled", baseFieldName)
                        .isEqualTo(spaces(7));
                assertThat(text.substring(k + 3, k + 7))
                        .as("the reserved FILLER X(4) of %s is four spaces", baseFieldName)
                        .isEqualTo(spaces(4));
            }
        }

        private FieldSpan spanAt(int offset) {
            for (FieldSpan span : TransactionListRequest.LAYOUT.storageSpans()) {
                if (span.offset() == offset) {
                    return span;
                }
            }
            throw new AssertionError("No layout span begins at offset " + offset
                    + "; the COTRN0AI group image declares every byte, so an offset with no span "
                    + "means a prefix or payload width was transcribed wrongly");
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

    @Nested
    @DisplayName("Page size - exactly 10, behaviour rather than configuration - gate G39")
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

    @Nested
    @DisplayName("Row addressing - the 1-based COBOL row maps at both ends - gate G33")
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

        @ParameterizedTest(name = "row {0} is outside 1..10 and is refused")
        @ValueSource(ints = {-1, 0, 11, 12, Integer.MIN_VALUE, Integer.MAX_VALUE})
        @DisplayName("an out-of-range row is rejected rather than wrapped or clamped - gate G33")
        void outOfRangeRowsAreRejected(int row) {
            TransactionListRequest request = new TransactionListRequest();
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.getSelection(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.setTransactionDescription(row, "X"));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> request.clearRow(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.rowImageOffset(row));
            assertThatExceptionOfType(IndexOutOfBoundsException.class)
                    .isThrownBy(() -> TransactionListRequest.transactionIdFieldName(row));

            assertThat(request.getPayloadValues())
                    .containsEntry("TRNID01", spaces(16))
                    .containsEntry("TDESC01", spaces(26))
                    .containsEntry("TDESC10", spaces(26));
        }

        @Test
        @DisplayName("only row 1 sets TRNID-FIRST and only row 10 sets TRNID-LAST - gate G33")
        void theCursorAsymmetryIsAtBothEnds() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                request.setTransactionId(row, String.format("%016d", row));
                if (row == 1) {
                    request.getCursor().setTrnidFirst(request.getTransactionId(row));
                }
                if (row == TransactionListRequest.ROW_COUNT) {
                    request.getCursor().setTrnidLast(request.getTransactionId(row));
                }
            }

            assertThat(TransactionListRequest.transactionIdFieldName(1)).isEqualTo("TRNID01");
            assertThat(TransactionListRequest.selectionFieldName(1)).isEqualTo("SEL0001");
            assertThat(TransactionListRequest.transactionAmountFieldName(1)).isEqualTo("TAMT001");
            assertThat(request.getCursor().getTrnidFirst())
                    .isEqualTo(request.getTrnid01())
                    .isEqualTo("0000000000000001");

            assertThat(TransactionListRequest.transactionIdFieldName(10)).isEqualTo("TRNID10");
            assertThat(TransactionListRequest.selectionFieldName(10)).isEqualTo("SEL0010");
            assertThat(TransactionListRequest.transactionAmountFieldName(10)).isEqualTo("TAMT010");
            assertThat(request.getCursor().getTrnidLast())
                    .isEqualTo(request.getTrnid10())
                    .isEqualTo("0000000000000010");

            assertThat(request.getCursor().getTrnidFirst())
                    .isNotEqualTo(request.getCursor().getTrnidLast());

            assertThat(TransactionListRequest.rowImageOffset(1))
                    .isEqualTo(TransactionListRequest.ROW_BLOCK_OFFSET);
            for (int row = 2; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(TransactionListRequest.rowImageOffset(row)
                        - TransactionListRequest.rowImageOffset(row - 1))
                        .as("row %d advances by one row image", row)
                        .isEqualTo(TransactionListRequest.ROW_IMAGE_LENGTH);
            }
        }

        @Test
        @DisplayName("INITIALIZE-TRAN-DATA blanks four items per row, and SEL000n is not one - gate G33")
        void theParagraphBlanksFourItemsAndNotTheSelector() {
            TransactionListRequest request = new TransactionListRequest();
            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                request.setSelection(row, "S");
                request.setTransactionId(row, String.format("%016d", row));
                request.setTransactionDate(row, "01/01/24");
                request.setTransactionDescription(row, "ROW " + row);
                request.setTransactionAmount(row, "+00000001.00");
            }

            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                List<String> blankedByTheParagraph = List.of(
                        TransactionListRequest.transactionIdFieldName(row),
                        TransactionListRequest.transactionDateFieldName(row),
                        TransactionListRequest.transactionDescriptionFieldName(row),
                        TransactionListRequest.transactionAmountFieldName(row));
                assertThat(blankedByTheParagraph).as("row %d", row)
                        .hasSize(4)
                        .doesNotContain(TransactionListRequest.selectionFieldName(row));
                for (int i = 0; i < blankedByTheParagraph.size(); i++) {
                    String field = blankedByTheParagraph.get(i);
                    request.setPayloadValue(field,
                            spaces(COPYBOOK_WIDTHS.get(COPYBOOK_FIELDS.indexOf(field))));
                }
            }

            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(request.getTransactionId(row)).as("TRNID%02d", row).isEqualTo(spaces(16));
                assertThat(request.getTransactionDate(row)).as("TDATE%02d", row).isEqualTo(spaces(8));
                assertThat(request.getTransactionDescription(row)).as("TDESC%02d", row)
                        .isEqualTo(spaces(26));
                assertThat(request.getTransactionAmount(row)).as("TAMT%03d", row)
                        .isEqualTo(spaces(12));
                assertThat(request.getSelection(row))
                        .as("SEL%04d is not among the paragraph's four moves", row)
                        .isEqualTo("S");
            }

            assertThat(request.getTransactionDescription(1))
                    .isNotNull()
                    .isNotEmpty()
                    .isBlank()
                    .hasSize(TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("clearRow blanks all five, and the LOW-VALUES pre-clear is why that is faithful")
        void clearRowIsADocumentedSupersetOfTheParagraph() {
            TransactionListRequest request = new TransactionListRequest();
            request.setSelection(3, "S");
            request.setTransactionId(3, "0000000000000003");
            request.setTransactionDate(3, "03/03/24");
            request.setTransactionDescription(3, "ROW THREE");
            request.setTransactionAmount(3, "+00000003.00");

            request.clearRow(3);

            assertThat(request.getSel0003()).isEqualTo(" ").hasSize(1);
            assertThat(request.getTrnid03()).isEqualTo(spaces(16));
            assertThat(request.getTdate03()).isEqualTo(spaces(8));
            assertThat(request.getTdesc03()).isEqualTo(spaces(26));
            assertThat(request.getTamt003()).isEqualTo(spaces(12));

            request.setTransactionId(4, "0000000000000004");
            request.clearRow(3);
            assertThat(request.getTrnid04()).isEqualTo("0000000000000004");
        }
    }

    @Nested
    @DisplayName("The PIC X width rule - pad right, truncate right, never trim - rule R5, gate G21")
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

            assertThat(request.getErrmsg())
                    .isEqualTo("Invalid selection. Valid value is S")
                    .hasSize(35);

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

            assertThatThrownBy(() -> request.setTdesc01("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TDESC01")
                    .hasMessageContaining("PIC X(26)")
                    .hasMessageContaining("36 character(s)");
            assertThatThrownBy(() -> request.setTrnname("TOOLONG"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TRNNAME")
                    .hasMessageContaining("7 character(s)");

            assertThat(request.getTdesc01()).isEqualTo(spaces(26));
            assertThat(request.getTrnname()).isEqualTo(spaces(4));

            request.setTdesc01(new FixedWidthCodec(ASCII)
                    .movePicX("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 26));
            assertThat(request.getTdesc01()).isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        }

        @Test
        @DisplayName("TRAN-DESC X(100) reaches TDESCnn X(26) truncated on the RIGHT - rule R5")
        void theDescriptionMoveTruncatesOnTheRight() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            TransactionListRequest request = new TransactionListRequest();

            String hundred = "0123456789".repeat(10);
            assertThat(hundred).hasSize(100);
            String moved = codec.movePicX(hundred, TransactionListRequest.TRANSACTION_DESCRIPTION_LENGTH);
            assertThat(moved).hasSize(26).isEqualTo("01234567890123456789012345");
            request.setTransactionDescription(1, moved);
            assertThat(request.getTdesc01()).isEqualTo("01234567890123456789012345");

            String thirty = "PURCHASE AT MERCHANT XYZ 12345";
            assertThat(thirty).hasSize(30);
            String movedThirty = codec.movePicX(thirty, 26);
            assertThat(movedThirty).hasSize(26)
                    .isEqualTo("PURCHASE AT MERCHANT XYZ 1")
                    .isEqualTo(thirty.substring(0, 26));
            request.setTransactionDescription(10, movedThirty);
            assertThat(request.getTdesc10()).isEqualTo("PURCHASE AT MERCHANT XYZ 1");

            assertThat(codec.movePicX("A".repeat(26), 26)).isEqualTo("A".repeat(26)).hasSize(26);
            assertThat(codec.movePicX("A".repeat(27), 26)).isEqualTo("A".repeat(26)).hasSize(26);
        }

        @Test
        @DisplayName("TRAN-ID X(16) reaches TRNIDnn X(16) with no truncation at all - rule R5")
        void theIdentifierMoveIsExact() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            String tranId = "0000000000000042";
            assertThat(tranId).hasSize(TransactionListRequest.TRANSACTION_ID_LENGTH).hasSize(16);
            assertThat(codec.movePicX(tranId, 16)).isEqualTo(tranId);

            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(1, tranId);
            request.getCursor().setTrnidFirst(request.getTransactionId(1));
            assertThat(request.getCursor().getTrnidFirst()).isEqualTo(tranId).hasSize(16);
        }

        @Test
        @DisplayName("a short value is right-space-padded at the byte boundary, never left short")
        void shortValuesArePaddedToTheDeclaredWidth() {
            FixedWidthCodec codec = new FixedWidthCodec(ASCII);
            assertThat(codec.movePicX("ABC", 26)).isEqualTo("ABC" + spaces(23)).hasSize(26);
            assertThat(codec.movePicX("", 12)).isEqualTo(spaces(12)).hasSize(12);

            TransactionListRequest request = new TransactionListRequest();
            request.setTransactionId(5, "ID5");
            request.setTransactionDate(5, "01/02");
            request.setTransactionDescription(5, "SHORT");
            request.setTransactionAmount(5, "+1.00");

            String text = new String(request.toFixedWidth(ASCII), ASCII);
            assertThat(spanText(text, "TRNID05I")).isEqualTo("ID5" + spaces(13)).hasSize(16);
            assertThat(spanText(text, "TDATE05I")).isEqualTo("01/02" + spaces(3)).hasSize(8);
            assertThat(spanText(text, "TDESC05I")).isEqualTo("SHORT" + spaces(21)).hasSize(26);
            assertThat(spanText(text, "TAMT005I")).isEqualTo("+1.00" + spaces(7)).hasSize(12);

            assertThat("00/00/00").hasSize(TransactionListRequest.TRANSACTION_DATE_LENGTH).hasSize(8);
        }

        private String spanText(String image, String inputItemName) {
            FieldSpan span = TransactionListRequest.LAYOUT.span(inputItemName);
            return image.substring(span.offset(), span.endOffsetExclusive());
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

            assertThat(request.getTrnname()).isEqualTo("CT00");
            assertThat(request.getTitle01()).isEqualTo("T1");
            assertThat(request.getCurdate()).isEqualTo("08/08/26");
            assertThat(request.getPgmname()).isEqualTo("COTRN00C");
            assertThat(request.getTitle02()).isEqualTo("T2");
            assertThat(request.getCurtime()).isEqualTo("12:34:56");
            assertThat(request.getPagenum()).isEqualTo("00000001");
            assertThat(request.getTrnidin()).isEqualTo("0000000000000001");
            assertThat(request.getErrmsg()).isEqualTo("E");

            byte[] image = request.toFixedWidth(ASCII);
            assertThat(image).hasSize(TransactionListRequest.SYMBOLIC_MAP_LENGTH);
            TransactionListRequest widened = TransactionListRequest.fromFixedWidth(image, ASCII);
            assertThat(widened.getTitle01()).hasSize(40).startsWith("T1").endsWith(" ");
            assertThat(widened.getErrmsg()).hasSize(78).startsWith("E");
        }
    }

    @Nested
    @DisplayName("Explicit row accessors - all fifty, under their verbatim names - practice B4")
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

    @Nested
    @DisplayName("The name-keyed view - what field-for-field diffing consumes - gates G9, G17")
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

    @Nested
    @DisplayName("Field metadata - signed xxxL, and xxxF / xxxA over one byte - gate G34")
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
        @DisplayName("-1 survives as -1, not as 65535 - xxxL is COMP PIC S9(4), signed")
        void theLengthItemIsSignedNotUnsigned() {
            FieldMetadata metadata = new FieldMetadata("TRNIDIN");
            metadata.setLengthItem((short) -1);
            assertThat(metadata.getLengthItem()).isEqualTo((short) -1).isNegative();

            assertThat((int) metadata.getLengthItem()).isEqualTo(-1).isNotEqualTo(65_535);
            assertThat(Short.toUnsignedInt(metadata.getLengthItem()))
                    .as("the same bits read unsigned would be 65535, which is why signedness matters")
                    .isEqualTo(65_535);

            metadata.setLengthItem(Short.MIN_VALUE);
            assertThat(metadata.getLengthItem()).isEqualTo(Short.MIN_VALUE).isEqualTo((short) -32_768);
            metadata.setLengthItem(Short.MAX_VALUE);
            assertThat(metadata.getLengthItem()).isEqualTo(Short.MAX_VALUE).isEqualTo((short) 32_767);
        }

        @Test
        @DisplayName("the length item and the payload are independent spans - gate G34")
        void metadataAndPayloadDoNotDisturbEachOther() {
            TransactionListRequest request = new TransactionListRequest();
            request.setTrnidin("0000000000000123");

            request.positionCursorAt("TRNIDIN");
            assertThat(request.getTrnidin())
                    .as("the cursor request left the typed value alone")
                    .isEqualTo("0000000000000123");

            request.getMetadata("TRNIDIN").setFlag("Q");
            assertThat(request.getTrnidin()).isEqualTo("0000000000000123");

            request.setTrnidin("0000000000000456");
            assertThat(request.getMetadata("TRNIDIN").getLengthItem())
                    .as("writing the payload did not discard the pending cursor request")
                    .isEqualTo(TransactionListRequest.CURSOR_POSITION_REQUEST);
            assertThat(request.getMetadata("TRNIDIN").getFlag()).isEqualTo("Q");
            assertThat(request.getCursorPositionField()).isEqualTo("TRNIDIN");

            assertThat(request.getMetadata("SEL0001").getLengthItem()).isZero();
            assertThat(request.getMetadata("SEL0001").isFlagLowValues()).isTrue();
            assertThat(request.getSel0001()).isEqualTo(" ");
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

    @Nested
    @DisplayName("The pagination cursor - 58 bytes, both 88-levels, commarea 218 - rule R6, gate G37")
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

    @Nested
    @DisplayName("Statelessness - the COMMAREA travels in the payload - rule R6, gates G37, G53")
    class Statelessness {
        @Test
        @DisplayName("a fresh request carries an empty context and a fresh cursor")
        void freshCarriedState() {
            TransactionListRequest request = new TransactionListRequest();

            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.isEnter()).isFalse();
            assertThat(request.isReenter()).isFalse();

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

            request.setNavigationContext(null);
            assertThat(request.getNavigationContext()).isNull();
            assertThat(request.hasNavigationContext()).isFalse();
            assertThat(request.commareaLength()).isZero();

            request.setNavigationContext(NavigationContext.empty());
            assertThat(request.hasNavigationContext()).isTrue();
            assertThat(request.commareaLength())
                    .isEqualTo(PaginationCursor.COMMAREA_LENGTH)
                    .isEqualTo(NavigationContext.COMMAREA_LENGTH + PaginationCursor.CURSOR_LENGTH)
                    .isEqualTo(218);

            assertThatNullPointerException().isThrownBy(() -> request.setCursor(null));
        }

        @Test
        @DisplayName("a cold start reports the enter context digit but is neither ENTER nor REENTER")
        void aColdStartReportsTheEnterDigitWithoutClaimingTheState() {
            TransactionListRequest cold = new TransactionListRequest();

            assertThat(cold.getPgmContext()).isEqualTo(NavigationContext.PGM_CONTEXT_ENTER);
            assertThat(cold.isEnter()).isFalse();
            assertThat(cold.isReenter()).isFalse();
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
            for (Field field : TransactionListRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("static field %s must be final", field.getName())
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("Serialisation - padding survives a JSON round trip untrimmed - rule R5, gate G9")
    class Serialisation {
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private static Map<String, Object> tree(String json) throws Exception {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() { });
        }

        @Test
        @DisplayName("the JSON property names are the copybook base names lower-cased")
        void propertyNamesMatchCopybook() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(new TransactionListRequest()));
            for (String field : COPYBOOK_FIELDS) {
                assertThat(tree).as("property for %s", field)
                        .containsKey(field.toLowerCase(Locale.ROOT));
            }
            assertThat(tree).containsKeys("sel0001", "sel0010", "trnid01", "trnid10",
                    "tamt001", "tamt010", "tdate01", "tdesc10", "errmsg", "pagenum", "trnidin");
        }

        @Test
        @DisplayName("none of the 177 metadata item names reaches the wire - gate G9")
        void metadataNotSerialised() throws Exception {
            String json = MAPPER.writeValueAsString(populated());
            Map<String, Object> tree = tree(json);

            for (String baseFieldName : COPYBOOK_FIELDS) {
                assertThat(tree).as("payload key for %s", baseFieldName)
                        .containsKey(baseFieldName.toLowerCase(Locale.ROOT));

                for (String suffix : List.of("L", "F", "A")) {
                    String item = baseFieldName + suffix;
                    assertThat(tree).as("metadata item %s must not be a payload key", item)
                            .doesNotContainKey(item)
                            .doesNotContainKey(item.toLowerCase(Locale.ROOT));
                    assertThat(json).as("metadata item %s must not appear in the JSON text", item)
                            .doesNotContain("\"" + item + "\"")
                            .doesNotContain("\"" + item.toLowerCase(Locale.ROOT) + "\"");
                }
            }

            assertThat(tree).doesNotContainKeys("fieldMetadata", "payloadValues",
                    "cursorPositionField", "enter", "reenter", "pgmContext", "commareaLength");
        }

        @Test
        @DisplayName("all 59 payload keys are on the wire, at their declared widths - gate G9")
        void everyPayloadKeyIsSerialised() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(populated()));
            for (int i = 0; i < COPYBOOK_FIELDS.size(); i++) {
                String key = COPYBOOK_FIELDS.get(i).toLowerCase(Locale.ROOT);
                assertThat(tree).containsKey(key);
                assertThat((String) tree.get(key)).as("%s on the wire", key)
                        .isNotNull()
                        .hasSize(COPYBOOK_WIDTHS.get(i));
            }
        }

        @Test
        @DisplayName("the payload carries exactly the 59 fields plus the two carried structures")
        void payloadShape() throws Exception {
            Map<String, Object> tree = tree(MAPPER.writeValueAsString(new TransactionListRequest()));
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

            assertThat(tree.get("tamt001")).isEqualTo(spaces(12));
            assertThat(tree.get("errmsg")).isEqualTo(spaces(78));
            for (String fieldName : TransactionListRequest.FIELD_NAMES) {
                assertThat(tree.get(fieldName.toLowerCase(Locale.ROOT)))
                        .as("%s is spaces, never null", fieldName)
                        .isNotNull();
            }

            assertThat(tree.get("navigationContext")).isNull();
            assertThat(json.indexOf("null")).isEqualTo(json.lastIndexOf("null"));
        }
    }

    @Nested
    @DisplayName("The fixed-width group image - 1265 bytes, lossless both ways - rule R5, gate G21")
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

    @Nested
    @DisplayName("Copying and value semantics - practice B9, gate G53")
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

    @Nested
    @DisplayName("Screen-flow shapes taken from COTRN00C - rule R7, gate G39")
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

    @Nested
    @DisplayName("The AID and the width contract - rule R6, gates G37 and G49")
    class KeyIndicationAndWidthContract {
        @Test
        @DisplayName("every one of the 59 constraints states a maximum only, never an exact width")
        void constraintsAreMaximumOnly() throws Exception {
            int checked = 0;
            for (String baseFieldName : TransactionListRequest.FIELD_NAMES) {
                String member = baseFieldName.toLowerCase(Locale.ROOT);
                Size size = TransactionListRequest.class.getDeclaredField(member)
                        .getAnnotation(Size.class);
                assertThat(size).as("@Size on %s", member).isNotNull();
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

            assertThatThrownBy(() -> new TransactionListRequest().setTitle01("X".repeat(41)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TITLE01")
                    .hasMessageContaining("41 character(s)");
        }

        @ParameterizedTest(name = "{0} PIC X({1}) reports a violation at {1} + 1 characters")
        @CsvSource({
            "TRNNAME, 4",
            "CURDATE, 8",
            "TAMT001, 12",
            "TRNIDIN, 16",
            "TDESC01, 26",
            "TITLE01, 40",
            "ERRMSG, 78",
            "SEL0001, 1",
        })
        @DisplayName("each constrained width is a real check, valid and invalid - gate G49")
        void everyConstrainedWidthIsEnforcedBothWays(String baseFieldName, int declaredWidth)
                throws Exception {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                String member = baseFieldName.toLowerCase(Locale.ROOT);

                TransactionListRequest atWidth = new TransactionListRequest();
                atWidth.setPayloadValue(baseFieldName, "X".repeat(declaredWidth));
                assertThat(validator.validate(atWidth))
                        .as("%s at its declared width is valid", baseFieldName).isEmpty();
                if (declaredWidth > 1) {
                    TransactionListRequest shorter = new TransactionListRequest();
                    shorter.setPayloadValue(baseFieldName, "X".repeat(declaredWidth - 1));
                    assertThat(validator.validate(shorter))
                            .as("%s below its declared width is valid", baseFieldName).isEmpty();
                }

                TransactionListRequest overLong = new TransactionListRequest();
                Field declaredMember = TransactionListRequest.class.getDeclaredField(member);
                declaredMember.setAccessible(true);
                declaredMember.set(overLong, "X".repeat(declaredWidth + 1));

                Set<ConstraintViolation<TransactionListRequest>> violations =
                        validator.validate(overLong);
                assertThat(violations).as("%s at %d characters", baseFieldName, declaredWidth + 1)
                        .hasSize(1);
                ConstraintViolation<TransactionListRequest> violation = violations.iterator().next();
                assertThat(violation.getPropertyPath().toString()).isEqualTo(member);
                assertThat(violation.getInvalidValue()).isEqualTo("X".repeat(declaredWidth + 1));
                assertThat(TransactionListRequest.class.getDeclaredField(member)
                        .getAnnotation(Size.class).max()).isEqualTo(declaredWidth);
            }
        }

        @Test
        @DisplayName("the AID token is five characters and starts at no key resolved")
        void theAidTokenIsFiveCharacters() {
            assertThat(TransactionListRequest.AID_LENGTH).isEqualTo(5);
            for (String token : ACTED_ON_AID_TOKENS) {
                assertThat(token).as("AID token %s", token).hasSize(5);
            }
            assertThat(TransactionListRequest.AID_FIELD).isEqualTo("EIBAID");
            assertThat(new TransactionListRequest().getAid()).isEqualTo(spaces(5));
        }

        @Test
        @DisplayName("every AID this screen acts on is carried, WHEN OTHER included - R6, gate G37")
        void everyArmIsSelectable() {
            TransactionListRequest request = new TransactionListRequest();

            for (String token : ACTED_ON_AID_TOKENS) {
                request.setAid(token);
                assertThat(request.getAid()).isEqualTo(token)
                        .hasSize(TransactionListRequest.AID_LENGTH);
            }

            request.setAid(null);
            assertThat(request.getAid()).isEqualTo(spaces(5));
        }

        @Test
        @DisplayName("the AID is on the wire, in value semantics, and outside the 59-field projection")
        void theAidIsCarriedAndCounted() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            TransactionListRequest before = new TransactionListRequest();
            before.setAid("PFK08");

            TransactionListRequest after = mapper.readValue(mapper.writeValueAsString(before),
                    TransactionListRequest.class);
            assertThat(after.getAid()).isEqualTo("PFK08");
            assertThat(after).isEqualTo(before);

            TransactionListRequest paging = new TransactionListRequest();
            paging.setAid("PFK07");
            assertThat(paging).isNotEqualTo(before);

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

    @Nested
    @DisplayName("The negative contract - gates G22, G24, G37, G44 and G53")
    class NegativeContract {
        private List<Class<?>> declaredTypes() {
            return List.of(TransactionListRequest.class, FieldMetadata.class, PaginationCursor.class);
        }

        @Test
        @DisplayName("all 59 payload members are String - rule R4, gate G22")
        void everyPayloadMemberIsAString() throws Exception {
            for (String baseFieldName : COPYBOOK_FIELDS) {
                Field member = TransactionListRequest.class
                        .getDeclaredField(baseFieldName.toLowerCase(Locale.ROOT));
                assertThat(member.getType()).as("declared type of %s", baseFieldName)
                        .isEqualTo(String.class);
            }

            assertThat(TransactionListRequest.class.getDeclaredField("tamt001").getType())
                    .isEqualTo(String.class);
            assertThat("+99999999.99")
                    .hasSize(TransactionListRequest.TRANSACTION_AMOUNT_LENGTH);

            assertThat(TransactionListRequest.class.getDeclaredField("pagenum").getType())
                    .isEqualTo(String.class);
            assertThat(PaginationCursor.class.getDeclaredMethod("getPageNum").getReturnType())
                    .as("a scale-free PIC 9(08) is an int, which rule R4 permits")
                    .isEqualTo(int.class);
        }

        @Test
        @DisplayName("no member is floating point anywhere in the type - rule R4, gate G22")
        void nothingIsFloatingPoint() {
            List<Class<?>> forbidden = List.of(double.class, float.class, Double.class, Float.class);
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("return of %s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(forbidden);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as("parameter of %s.%s", type.getSimpleName(), method.getName())
                                .isNotIn(forbidden);
                    }
                }
            }
        }

        @Test
        @DisplayName("no rounding decision exists to get wrong - rule R2, gate G24")
        void noRoundingDecisionExists() {
            List<Class<?>> forbidden = List.of(java.math.BigDecimal.class,
                    java.math.RoundingMode.class, java.math.MathContext.class);
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getType()).as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(forbidden);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getReturnType())
                            .as("return of %s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(forbidden);
                    for (Class<?> parameter : method.getParameterTypes()) {
                        assertThat(parameter)
                                .as("parameter of %s.%s", type.getSimpleName(), method.getName())
                                .isNotIn(forbidden);
                    }
                }
            }

            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    assertThat(field.getName().toUpperCase(Locale.ROOT))
                            .as("%s.%s", type.getSimpleName(), field.getName())
                            .isNotIn(FORBIDDEN_ROUNDING_MODES);
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertThat(method.getName().toUpperCase(Locale.ROOT))
                            .as("%s.%s", type.getSimpleName(), method.getName())
                            .isNotIn(FORBIDDEN_ROUNDING_MODES);
                }
            }
        }

        @Test
        @DisplayName("nothing schema-shaped is declared - gate G44")
        void nothingSchemaShaped() {
            for (Class<?> type : declaredTypes()) {
                assertAnnotationsAreNotSchemaShaped(type.getSimpleName(),
                        type.getDeclaredAnnotations());
                for (Field field : type.getDeclaredFields()) {
                    assertAnnotationsAreNotSchemaShaped(
                            type.getSimpleName() + "." + field.getName(),
                            field.getDeclaredAnnotations());
                }
                for (Method method : type.getDeclaredMethods()) {
                    assertAnnotationsAreNotSchemaShaped(
                            type.getSimpleName() + "." + method.getName(),
                            method.getDeclaredAnnotations());
                }
            }
        }

        private void assertAnnotationsAreNotSchemaShaped(String subject, Annotation[] annotations) {
            for (Annotation annotation : annotations) {
                assertThat(annotation.annotationType().getSimpleName()).as("annotation on %s", subject)
                        .isNotIn(SCHEMA_SHAPED_ANNOTATIONS);
            }
        }

        @Test
        @DisplayName("no server-side state is reachable - rule R6, gates G37 and G53")
        void noServerSideStateIsReachable() {
            for (Class<?> type : declaredTypes()) {
                for (Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    assertThat(fieldType).as("%s.%s", type.getSimpleName(), field.getName())
                            .doesNotContain("HttpSession")
                            .doesNotContain("ThreadLocal")
                            .doesNotContain("jakarta.servlet")
                            .doesNotContain("javax.servlet")
                            .doesNotContain("HttpServletRequest");

                    if (Modifier.isStatic(field.getModifiers())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("static %s.%s must be final",
                                        type.getSimpleName(), field.getName())
                                .isTrue();
                    }
                }
            }

            TransactionListRequest first = new TransactionListRequest();
            TransactionListRequest second = new TransactionListRequest();
            first.getCursor().setPageNum(3);
            assertThat(second.getCursor().getPageNum())
                    .as("one request's cursor is not another's")
                    .isZero();
        }

        @Test
        @DisplayName("the input-capable set is exactly TRNIDIN plus the ten selectors - gate G9")
        void theInputCapableSetIsElevenFields() {
            assertThat(INPUT_CAPABLE_FIELDS).hasSize(11);
            assertThat(TransactionListRequest.FIELD_NAMES)
                    .containsAll(INPUT_CAPABLE_FIELDS)
                    .hasSize(59);
            assertThat(INPUT_CAPABLE_FIELDS.get(0)).isEqualTo(TransactionListRequest.TRNIDIN_FIELD);
            for (int row = 1; row <= TransactionListRequest.ROW_COUNT; row++) {
                assertThat(INPUT_CAPABLE_FIELDS)
                        .contains(TransactionListRequest.selectionFieldName(row));
            }

            List<String> outputOnly = new ArrayList<>(TransactionListRequest.FIELD_NAMES);
            outputOnly.removeAll(INPUT_CAPABLE_FIELDS);
            assertThat(outputOnly).hasSize(48)
                    .contains("TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
                            "PAGENUM", "ERRMSG", "TRNID01", "TDATE01", "TDESC01", "TAMT001",
                            "TRNID10", "TDATE10", "TDESC10", "TAMT010")
                    .doesNotContain("TRNIDIN", "SEL0001", "SEL0010");

            TransactionListRequest request = new TransactionListRequest();
            for (String field : INPUT_CAPABLE_FIELDS) {
                assertThat(request.getMetadata(field)).as("metadata for %s", field).isNotNull();
            }
        }

        @Test
        @DisplayName("the grid is fifty named members, never a collection - gate G33")
        void theGridIsNotACollection() {
            int payloadMembers = 0;
            int carriedStructures = 0;
            for (Field field : TransactionListRequest.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                assertThat(field.getType().isArray())
                        .as("%s must not be an array", field.getName()).isFalse();
                assertThat(List.class.isAssignableFrom(field.getType()))
                        .as("%s must not be a List", field.getName()).isFalse();
                if (field.getType() == String.class) {
                    payloadMembers++;
                } else {
                    carriedStructures++;
                    assertThat(field.getType())
                            .as("non-String member %s", field.getName())
                            .isIn(NavigationContext.class, PaginationCursor.class, Map.class);
                    if (Map.class.isAssignableFrom(field.getType())) {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("the metadata sidecar %s must be final", field.getName())
                                .isTrue();
                    }
                }
            }

            assertThat(payloadMembers).isEqualTo(TransactionListRequest.FIELD_COUNT + 1).isEqualTo(60);
            assertThat(carriedStructures)
                    .as("navigation context, pagination cursor and metadata sidecar")
                    .isEqualTo(3);
        }
    }

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
