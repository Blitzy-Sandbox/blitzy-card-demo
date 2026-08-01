/*
 * ******************************************************************
 * Program     : TransactionDtoTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the transaction detail and list projections
 *               against the two symbolic maps they are drawn from,
 *               reading the frozen copybooks as the oracle rather
 *               than restating the Java constants under test.
 * Source      : app/cpy-bms/COTRN01.CPY  (21 detail input fields)
 *               app/cpy-bms/COTRN00.CPY  (59 list input fields)
 *               app/cbl/COTRN01C.cbl     (330 lines, detail)
 *               app/cbl/COTRN00C.cbl:L65-L68 (10 rows per page)
 *               app/cpy/CVTRA05Y.cpy     (350-byte record layout)
 *               frozen at commit 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.dto.TransactionDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable specification for the transaction detail and list projections.
 *
 * <p>One record serves two screens, which is the whole reason it needs testing carefully. The detail screen
 * {@code COTRN01} declares 21 input fields and the list screen {@code COTRN00} declares 59, and the same
 * instance has to be able to answer for either without the two shapes contaminating each other.
 *
 * <p>Every width and count asserted here is read from the frozen copybook at run time through
 * {@link BmsSymbolicMap}, never restated as a second literal. That distinction matters: a test that compares
 * {@code TRANSACTION_NAME_LENGTH} against a hard-coded {@code 4} proves only that someone typed four twice,
 * whereas comparing it against {@code app/cpy-bms/COTRN01.CPY}'s {@code TRNNAMEI PIC X(4)} proves the
 * migration contract - that field names, types and lengths come from the symbolic maps exactly. If a width
 * here is ever changed to something the copybook does not declare, these tests fail; if the copybook and the
 * constant are both wrong in the same way, no test could tell, but that is a property of the frozen tree and
 * not something a test can repair.
 *
 * <p>The list projection carries an invariant worth stating separately: it returns exactly
 * {@code LIST_FIELD_COUNT} entries for every instance, whatever the row population, because a 3270 screen has
 * a fixed number of fields whether or not the data fills them. A projection that shrank with the data would
 * misalign every field on the last page of any result set.
 */
@DisplayName("TransactionDto: two screen projections over one record, against the frozen symbolic maps")
class TransactionDtoTest {

    private static BmsSymbolicMap detailMap;
    private static BmsSymbolicMap listMap;

    @BeforeAll
    static void readSymbolicMaps() {
        detailMap = BmsSymbolicMap.of("COTRN01");
        listMap = BmsSymbolicMap.of("COTRN00");
    }

    @Nested
    @DisplayName("1. The published field counts match the copybooks")
    class FieldCounts {

        @Test
        @DisplayName("the detail field count equals COTRN01's input-field inventory")
        void detailCountMatchesCopybook() {
            assertThat(TransactionDto.DETAIL_FIELD_COUNT)
                    .as("COTRN01 declares its input group at line %d and redefines it at line %d",
                            detailMap.inputGroupLine(), detailMap.outputRedefinitionLine())
                    .isEqualTo(detailMap.inputFieldCount())
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("the list field count equals COTRN00's input-field inventory")
        void listCountMatchesCopybook() {
            assertThat(TransactionDto.LIST_FIELD_COUNT)
                    .as("COTRN00 declares its input group at line %d and redefines it at line %d",
                            listMap.inputGroupLine(), listMap.outputRedefinitionLine())
                    .isEqualTo(listMap.inputFieldCount())
                    .isEqualTo(59);
        }

        @Test
        @DisplayName("the list count decomposes as preamble plus ten rows plus trailer")
        void listCountDecomposes() {
            // The constant is computed from its parts rather than written as a literal, so this asserts the
            // decomposition the screen actually has: 8 + (10 x 5) + 1. Were the row count or the row width
            // wrong, the total would no longer agree with the copybook, which is what makes the arithmetic
            // load-bearing rather than decorative.
            assertThat(TransactionDto.LIST_PREAMBLE_FIELD_COUNT
                    + (TransactionDto.PAGE_SIZE * TransactionDto.LIST_ROW_FIELD_COUNT)
                    + TransactionDto.LIST_TRAILER_FIELD_COUNT)
                    .isEqualTo(TransactionDto.LIST_FIELD_COUNT)
                    .isEqualTo(listMap.inputFieldCount());
        }

        @Test
        @DisplayName("the page size is ten, as the list program declares")
        void pageSizeIsTen() {
            // app/cbl/COTRN00C.cbl:L65-L68. The ten-row table is also why COTRN00 has 59 input fields where
            // the detail map has 21.
            assertThat(TransactionDto.PAGE_SIZE).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("2. Every published width matches the copybook that declares it")
    class Widths {

        @ParameterizedTest(name = "{1} in COTRN01 is {2} characters")
        @CsvSource({
            "TRANSACTION_NAME_LENGTH, TRNNAMEI,  4",
            "TITLE_LENGTH,            TITLE01I, 40",
            "TITLE_LENGTH,            TITLE02I, 40",
            "CURRENT_DATE_LENGTH,     CURDATEI,  8",
            "PROGRAM_NAME_LENGTH,     PGMNAMEI,  8",
            "CURRENT_TIME_LENGTH,     CURTIMEI,  8",
            "TRANSACTION_ID_LENGTH,   TRNIDINI, 16",
            "TRANSACTION_ID_LENGTH,   TRNIDI,   16",
            "CARD_NUMBER_LENGTH,      CARDNUMI, 16",
            "TYPE_CODE_LENGTH,        TTYPCDI,   2",
            "CATEGORY_CODE_LENGTH,    TCATCDI,   4",
            "SOURCE_LENGTH,           TRNSRCI,  10",
            "DESCRIPTION_LENGTH,      TDESCI,   60",
            "AMOUNT_DISPLAY_LENGTH,   TRNAMTI,  12",
            "DETAIL_DATE_LENGTH,      TORIGDTI, 10",
            "DETAIL_DATE_LENGTH,      TPROCDTI, 10",
            "MERCHANT_ID_LENGTH,      MIDI,      9",
            "MERCHANT_NAME_LENGTH,    MNAMEI,   30",
            "MERCHANT_CITY_LENGTH,    MCITYI,   25",
            "MERCHANT_ZIP_LENGTH,     MZIPI,    10",
            "ERROR_MESSAGE_LENGTH,    ERRMSGI,  78",
        })
        @DisplayName("the detail widths come from COTRN01")
        void detailWidthsComeFromTheCopybook(final String constantName, final String field,
                final int declaredWidth) {
            // Both sides are checked: the copybook really does declare this width, and the constant really
            // does carry it. Naming the constant in the case label is what makes a failure legible.
            assertThat(detailMap.widthOf(field))
                    .as("%s is declared PIC X(%d) in COTRN01", field, declaredWidth)
                    .isEqualTo(declaredWidth);
            assertThat(constantValue(constantName))
                    .as("%s must carry the width COTRN01 declares for %s", constantName, field)
                    .isEqualTo(declaredWidth);
        }

        @Test
        @DisplayName("the list row's narrower description and date widths come from COTRN00")
        void listRowWidthsComeFromTheCopybook() {
            // The list row is not simply a narrower rendering of the detail record - it declares genuinely
            // different widths, 26 against 60 for the description and 8 against 10 for the date, because the
            // row has to fit ten to a screen. Taking the detail widths for the row would overflow the map.
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .isEqualTo(listMap.widthOf("TDESC01I")).isEqualTo(26);
            assertThat(TransactionDto.ROW_DATE_LENGTH)
                    .isEqualTo(listMap.widthOf("TDATE01I")).isEqualTo(8);
            assertThat(TransactionDto.SELECTION_FLAG_LENGTH)
                    .isEqualTo(listMap.widthOf("SEL0001I")).isEqualTo(1);
            assertThat(TransactionDto.PAGE_NUMBER_LENGTH)
                    .isEqualTo(listMap.widthOf("PAGENUMI")).isEqualTo(8);
        }

        @Test
        @DisplayName("the row amount keeps the detail amount's width, unlike the description and date")
        void rowAmountKeepsTheDetailWidth() {
            // Worth asserting precisely because the neighbouring row fields do narrow: the description drops
            // 60 to 26 and the date 10 to 8, but the amount stays at 12. A blanket "narrow every row field"
            // rule would be wrong on exactly this one.
            assertThat(listMap.widthOf("TAMT001I"))
                    .isEqualTo(detailMap.widthOf("TRNAMTI"))
                    .isEqualTo(TransactionDto.AMOUNT_DISPLAY_LENGTH)
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the eight preamble fields are exactly the eight the list projection emits, in order")
        void preambleOrderMatchesTheProjection() {
            // COTRN00's first eight input fields are TRNNAMEI, TITLE01I, CURDATEI, PGMNAMEI, TITLE02I,
            // CURTIMEI, PAGENUMI and TRNIDINI, and listProjection() adds its preamble in precisely that
            // sequence. Order is as load-bearing as width on a fixed-field screen: transposing two fields of
            // equal width would corrupt both while every width assertion still passed.
            assertThat(listMap.fieldNames().subList(0, TransactionDto.LIST_PREAMBLE_FIELD_COUNT))
                    .containsExactly("TRNNAMEI", "TITLE01I", "CURDATEI", "PGMNAMEI",
                            "TITLE02I", "CURTIMEI", "PAGENUMI", "TRNIDINI");

            final List<String> projection = listWith(0).listProjection();
            assertThat(projection.subList(0, TransactionDto.LIST_PREAMBLE_FIELD_COUNT))
                    .containsExactly("CT01", "AWS Mainframe Modernization", "06/10/22", "COTRN01C",
                            "CardDemo", "19:27:53", "00000001", "TXN0000000000001");
        }

        @Test
        @DisplayName("the twelve-byte terminal header is not counted as an input field")
        void terminalHeaderIsNotAnInputField() {
            // Every symbolic map opens with a twelve-byte terminal I/O area FILLER. It is storage, not a
            // screen field, and counting it would report 60 fields where the map declares 59 - which is
            // exactly the sort of off-by-one that would then be "fixed" in the wrong place.
            assertThat(listMap.declares("FILLER"))
                    .as("the terminal I/O header must be excluded from the field inventory")
                    .isFalse();
            assertThat(listMap.inputFieldCount()).isEqualTo(59);
        }

        @Test
        @DisplayName("the persisted widths differ from the screen widths, and are not taken from the map")
        void persistedWidthsAreNotScreenWidths() {
            // CVTRA05Y declares the stored description as PIC X(100) and the timestamps as PIC X(26), neither
            // of which any screen shows in full. Conflating the stored width with the displayed one is a
            // standard way to truncate data on the way in, so the two are asserted to be different.
            assertThat(TransactionDto.DESCRIPTION_PERSISTED_LENGTH)
                    .as("the record stores 100 where the detail screen shows 60")
                    .isEqualTo(100)
                    .isNotEqualTo(TransactionDto.DESCRIPTION_LENGTH);
            assertThat(TransactionDto.PERSISTED_TIMESTAMP_LENGTH)
                    .as("the record stores a 26-character timestamp where the screen shows a 10-character date")
                    .isEqualTo(26)
                    .isNotEqualTo(TransactionDto.DETAIL_DATE_LENGTH);
        }

        @Test
        @DisplayName("the amount precision decomposes into its integer digits and scale")
        void amountPrecisionDecomposes() {
            // S9(09)V99: nine integer digits and two decimals, so eleven digits of precision. Asserting the
            // decomposition rather than the total is what would catch a scale changed to 3.
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS).isEqualTo(9);
            assertThat(TransactionDto.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionDto.AMOUNT_PRECISION)
                    .isEqualTo(TransactionDto.AMOUNT_INTEGER_DIGITS + TransactionDto.AMOUNT_SCALE)
                    .isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("3. The list projection has a fixed shape regardless of row population")
    class ListProjectionShape {

        @ParameterizedTest(name = "with {0} populated rows the projection is still 59 entries")
        @ValueSource(ints = {0, 1, 3, 9, 10})
        @DisplayName("the projection width never varies with the data")
        void projectionWidthIsFixed(final int populatedRows) {
            // The screen has 59 input fields whether or not the result set fills them. A projection that
            // shrank with the data would misalign every field on the final page of any query.
            final TransactionDto dto = listWith(populatedRows);

            assertThat(dto.listProjection())
                    .hasSize(TransactionDto.LIST_FIELD_COUNT)
                    .hasSize(listMap.inputFieldCount());
        }

        @Test
        @DisplayName("unpopulated slots project as nulls, mirroring the blank screen rows")
        void unpopulatedSlotsProjectAsNull() {
            final TransactionDto dto = listWith(2);
            final List<String> projection = dto.listProjection();

            // Rows 1 and 2 are populated; slots 3 through 10 are blank. The first row's transaction id sits
            // immediately after the 8 preamble fields, at the second position of the first row group.
            assertThat(projection.get(TransactionDto.LIST_PREAMBLE_FIELD_COUNT + 1)).isEqualTo("TXN0000000000001");
            final int thirdRowStart =
                    TransactionDto.LIST_PREAMBLE_FIELD_COUNT + (2 * TransactionDto.LIST_ROW_FIELD_COUNT);
            assertThat(projection.subList(thirdRowStart, thirdRowStart + TransactionDto.LIST_ROW_FIELD_COUNT))
                    .containsOnlyNulls();
        }

        @Test
        @DisplayName("the projection is unmodifiable, so a caller cannot reshape the screen")
        void projectionIsUnmodifiable() {
            final List<String> projection = listWith(1).listProjection();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> projection.add("extra"));
        }

        @Test
        @DisplayName("the detail projection carries exactly the 21 fields COTRN01 declares")
        void detailProjectionWidthIsFixed() {
            assertThat(detail().detailProjection())
                    .hasSize(TransactionDto.DETAIL_FIELD_COUNT)
                    .hasSize(detailMap.inputFieldCount());
        }

        @Test
        @DisplayName("the detail projection is unmodifiable too")
        void detailProjectionIsUnmodifiable() {
            final List<String> projection = detail().detailProjection();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(projection::clear);
        }
    }

    @Nested
    @DisplayName("4. Row addressing is bounded by the screen, not by the data")
    class RowAddressing {

        @ParameterizedTest(name = "slot {0} is addressable")
        @ValueSource(ints = {0, 1, 5, 9})
        @DisplayName("every slot the screen has is addressable")
        void everyScreenSlotIsAddressable(final int slot) {
            assertThat(listWith(10).rowAt(slot)).isNotNull();
        }

        @Test
        @DisplayName("an unpopulated slot yields null rather than an exception")
        void unpopulatedSlotYieldsNull() {
            // A final page commonly fills fewer than ten slots, so this is an ordinary state; making the
            // caller bounds-check against the data would push screen geometry into every call site.
            assertThat(listWith(3).rowAt(5)).isNull();
        }

        @ParameterizedTest(name = "slot {0} does not exist on the screen")
        @ValueSource(ints = {-1, 10, 11, 100})
        @DisplayName("a slot the screen does not have is refused")
        void nonexistentSlotIsRefused(final int slot) {
            // Off-screen is a programming error rather than a data state, so it is refused rather than
            // returning null - the two conditions must stay distinguishable.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listWith(10).rowAt(slot));
        }

        @Test
        @DisplayName("a detail instance carries no row array at all, which is not the same as an empty one")
        void detailCarriesNoRowArray() {
            // The record documents null and empty as different answers: null means "not a list projection",
            // empty means "a list projection that matched nothing". Coercing either into the other would
            // force callers to carry a second flag to tell the two apart.
            assertThat(detail().rows())
                    .as("a detail response has no row array")
                    .isNull();
            assertThat(detail().rowAt(0)).isNull();
        }

        @Test
        @DisplayName("an empty list projection is distinguishable from a detail projection")
        void emptyListIsNotTheSameAsNoList() {
            assertThat(listWith(0).rows())
                    .as("a list projection that matched nothing is empty, not null")
                    .isEmpty();
            assertThat(detail().rows())
                    .as("a detail projection is null, not empty")
                    .isNull();
        }

        @Test
        @DisplayName("the row list is unmodifiable, so the page cannot be grown after construction")
        void rowListIsUnmodifiable() {
            final List<TransactionDto.TransactionListRow> rows = listWith(2).rows();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(row(99)));
        }
    }

    @Nested
    @DisplayName("5. Values are carried verbatim and over-width values are refused")
    class ValueHandling {

        @Test
        @DisplayName("every detail value round-trips exactly as supplied")
        void detailValuesRoundTrip() {
            final TransactionDto dto = detail();

            assertThat(dto.transactionName()).isEqualTo("CT01");
            assertThat(dto.transactionId()).isEqualTo("TXN0000000000001");
            assertThat(dto.cardNumber()).isEqualTo("4111111111111111");
            assertThat(dto.typeCode()).isEqualTo("01");
            assertThat(dto.categoryCode()).isEqualTo("0001");
            assertThat(dto.amount()).isEqualTo("+00000194.00");
            assertThat(dto.merchantId()).isEqualTo("000000001");
        }

        @Test
        @DisplayName("a negative amount is carried unaltered, because the fixtures contain them")
        void negativeAmountIsCarried() {
            // dailytran.txt carries both positive and negative overpunch signs, so negative amounts are real
            // data exercising the cycle-debit branch. Normalising the sign anywhere on this path would break
            // the over-limit arithmetic downstream.
            final TransactionDto dto = detailWithAmount("-00000194.00", new BigDecimal("-194.00"));

            assertThat(dto.amount()).isEqualTo("-00000194.00");
            assertThat(dto.amountValue()).isEqualByComparingTo(new BigDecimal("-194.00"));
            assertThat(dto.amountValue().signum()).isNegative();
        }

        @Test
        @DisplayName("a null amount value is accepted, because the list screen supplies none")
        void nullAmountValueIsAccepted() {
            assertThat(listWith(1).amountValue()).isNull();
        }

        @Test
        @DisplayName("an over-width value is refused rather than silently truncated")
        void overWidthValueIsRefused() {
            // Truncating here would corrupt the field on the way to a fixed-width boundary, and would do so
            // invisibly. The width comes from the copybook, so the guard and the map cannot drift apart.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithTypeCode("x".repeat(detailMap.widthOf("TTYPCDI") + 1)));
        }

        @Test
        @DisplayName("a value of exactly the declared width is accepted")
        void exactWidthValueIsAccepted() {
            // The boundary itself, not just a value comfortably inside it: an off-by-one guard would reject
            // the widest legitimate value while passing every shorter test.
            assertThat(detailWithTypeCode("x".repeat(detailMap.widthOf("TTYPCDI"))).typeCode())
                    .hasSize(detailMap.widthOf("TTYPCDI"));
        }

        @Test
        @DisplayName("a row refuses an over-width description and accepts one of exact width")
        void rowWidthsAreEnforced() {
            final int width = listMap.widthOf("TDESC01I");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionDto.TransactionListRow(
                            "S", "TXN0000000000001", "06/10/22", "x".repeat(width + 1), "+00000194.00"));
            assertThat(new TransactionDto.TransactionListRow(
                    "S", "TXN0000000000001", "06/10/22", "x".repeat(width), "+00000194.00").description())
                    .hasSize(width);
        }

        @Test
        @DisplayName("a row accepts nulls throughout, because an unpopulated screen row is blank")
        void rowAcceptsNulls() {
            final TransactionDto.TransactionListRow blank =
                    new TransactionDto.TransactionListRow(null, null, null, null, null);

            assertThat(blank.selectionFlag()).isNull();
            assertThat(blank.transactionId()).isNull();
            assertThat(blank.transactionDate()).isNull();
            assertThat(blank.description()).isNull();
            assertThat(blank.amount()).isNull();
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Fixtures. Built from the copybook-declared widths so that no fixture can accidentally exceed one.
    // ----------------------------------------------------------------------------------------------------

    /**
     * Returns the value of a named {@code public static final int} on {@link TransactionDto}.
     *
     * <p>Reflection is used rather than a switch so that the parameterised case labels can name the constant
     * directly and a failure says which constant disagreed with the copybook.
     *
     * @param constantName the constant's name
     * @return its value
     */
    private static int constantValue(final String constantName) {
        try {
            return TransactionDto.class.getField(constantName).getInt(null);
        } catch (final NoSuchFieldException | IllegalAccessException absent) {
            throw new AssertionError("TransactionDto declares no constant " + constantName, absent);
        }
    }

    /**
     * Returns a fully populated detail projection.
     *
     * <p>The row array is {@code null} rather than empty, because that is what a detail response is: the
     * record documents the two as different answers and refuses to coerce either into the other.
     *
     * @return a detail projection carrying no row array
     */
    private static TransactionDto detail() {
        return build("01", "+00000194.00", new BigDecimal("194.00"), null);
    }

    /**
     * @param amount the display-masked amount
     * @param amountValue the decimal amount
     * @return a detail projection carrying the given amount
     */
    private static TransactionDto detailWithAmount(final String amount, final BigDecimal amountValue) {
        return build("01", amount, amountValue, null);
    }

    /**
     * @param typeCode the type code to carry
     * @return a detail projection carrying the given type code
     */
    private static TransactionDto detailWithTypeCode(final String typeCode) {
        return build(typeCode, "+00000194.00", new BigDecimal("194.00"), null);
    }

    /**
     * @param populatedRows how many of the ten screen slots to fill
     * @return a list projection with that many rows populated
     */
    private static TransactionDto listWith(final int populatedRows) {
        final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
        for (int index = 1; index <= populatedRows; index++) {
            rows.add(row(index));
        }
        return build("01", null, null, rows);
    }

    /**
     * @param ordinal the one-based row ordinal
     * @return a populated list row
     */
    private static TransactionDto.TransactionListRow row(final int ordinal) {
        return new TransactionDto.TransactionListRow("S",
                String.format("TXN%013d", Integer.valueOf(ordinal)), "06/10/22",
                "PURCHASE " + ordinal, "+00000194.00");
    }

    private static TransactionDto build(final String typeCode, final String amount,
            final BigDecimal amountValue, final List<TransactionDto.TransactionListRow> rows) {
        return new TransactionDto("CT01", "AWS Mainframe Modernization", "06/10/22", "COTRN01C",
                "CardDemo", "19:27:53", "TXN0000000000001", "TXN0000000000001", "4111111111111111",
                typeCode, "0001", "POS", "PURCHASE", amount, "2022-06-10", "2022-06-10", "000000001",
                "MERCHANT ONE", "SEATTLE", "98101", null, "00000001", rows, amountValue);
    }
}
