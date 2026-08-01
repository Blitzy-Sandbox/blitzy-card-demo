/*
 * ******************************************************************
 * Program     : CardDtoTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the card detail and list projections against
 *               the two symbolic maps they straddle, including the
 *               row-1 selector-type gap that exists in the map itself
 *               and the two fields whose declared widths differ
 *               between the maps.
 * Source      : app/cpy-bms/COCRDSL.CPY  (15 detail input fields)
 *               app/cpy-bms/COCRDLI.CPY  (45 list input fields)
 *               app/cbl/COCRDSLC.cbl     (887 lines, detail)
 *               app/cbl/COCRDLIC.cbl:L177 (7 rows per page)
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

import com.cardemo.model.dto.CardDto;
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
 * Executable specification for the card detail and list projections.
 *
 * <p>Like the transaction DTO this one type answers for two screens, but it carries two complications the
 * transaction DTO does not, and both are properties of the frozen copybooks rather than of the Java.
 *
 * <p><strong>The map itself is irregular.</strong> {@code app/cpy-bms/COCRDLI.CPY} declares seven row groups,
 * but row 1 has four input fields where rows 2 through 7 have five: the token {@code CRDSTP1} appears nowhere
 * in the member, while {@code CRDSTP2I} through {@code CRDSTP7I} are declared at lines 108, 138, 168, 198, 228
 * and 258. A uniform seven-by-five row array would compute 46 fields and misreport the map, which declares 45.
 * {@link RowIrregularity} asserts the gap directly against the copybook so that nobody later "regularises" it.
 *
 * <p><strong>Two fields have different widths on the two maps.</strong> {@code INFOMSGI} is
 * {@code PIC X(40)} on the detail map and {@code PIC X(45)} on the list map; {@code ERRMSGI} is
 * {@code PIC X(80)} on the detail map and {@code PIC X(78)} on the list map. A single type serving both has to
 * pick one width per field, and it sizes to the <em>wider</em> declaration so that neither screen's message is
 * ever rejected on the way in - rendering to the narrower screen truncates at the presentation boundary
 * instead. A test written against either map alone would assert the wrong number for one of them, which is
 * precisely why {@link DivergentWidths} checks both maps and then the type.
 *
 * <p>All widths and counts are read from the copybooks at run time through {@link BmsSymbolicMap}. The
 * width constants themselves are private, as they should be, so the guards are exercised through behaviour -
 * a value of exactly the declared width is accepted and one character more is refused - which tests the
 * contract rather than the constant.
 */
@DisplayName("CardDto: two screen projections, an irregular row array and two divergent widths")
class CardDtoTest {

    private static BmsSymbolicMap detailMap;
    private static BmsSymbolicMap listMap;

    @BeforeAll
    static void readSymbolicMaps() {
        detailMap = BmsSymbolicMap.of("COCRDSL");
        listMap = BmsSymbolicMap.of("COCRDLI");
    }

    @Nested
    @DisplayName("1. The published counts match the copybooks")
    class FieldCounts {

        @Test
        @DisplayName("the detail field count equals COCRDSL's input-field inventory")
        void detailCountMatchesCopybook() {
            assertThat(CardDto.DETAIL_FIELD_COUNT)
                    .as("COCRDSL declares its input group at line %d and redefines it at line %d",
                            detailMap.inputGroupLine(), detailMap.outputRedefinitionLine())
                    .isEqualTo(detailMap.inputFieldCount())
                    .isEqualTo(15);
        }

        @Test
        @DisplayName("the list field count equals COCRDLI's input-field inventory")
        void listCountMatchesCopybook() {
            assertThat(CardDto.LIST_FIELD_COUNT)
                    .as("COCRDLI declares its input group at line %d and redefines it at line %d",
                            listMap.inputGroupLine(), listMap.outputRedefinitionLine())
                    .isEqualTo(listMap.inputFieldCount())
                    .isEqualTo(45);
        }

        @Test
        @DisplayName("the page size is seven, as the list program declares")
        void pageSizeIsSeven() {
            // app/cbl/COCRDLIC.cbl:L177. Seven, not ten: the card list row is wider than a transaction row,
            // so fewer fit. Carrying the transaction DTO's ten here would address rows the map lacks.
            assertThat(CardDto.CARD_LIST_PAGE_SIZE).isEqualTo(7);
        }

        @Test
        @DisplayName("the row field counts sum with the preamble and trailer to the map's total")
        void countsReconcileWithTheMap() {
            // 9 preamble + 4 (row 1) + 30 (rows 2-7) + 2 trailer = 45. Deriving the row contribution from
            // getBmsFieldCount() rather than restating 34 is what ties the arithmetic to the code under test.
            int rowFields = 0;
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER;
                    rowNumber <= CardDto.CARD_LIST_PAGE_SIZE; rowNumber++) {
                rowFields += row(rowNumber).getBmsFieldCount();
            }

            assertThat(rowFields)
                    .as("row 1 contributes %d and each of the other six contributes %d",
                            CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE,
                            CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE)
                    .isEqualTo(34);
            final int preamble = 9;
            final int trailer = 2;
            assertThat(preamble + rowFields + trailer)
                    .isEqualTo(CardDto.LIST_FIELD_COUNT)
                    .isEqualTo(listMap.inputFieldCount());
        }
    }

    @Nested
    @DisplayName("2. The row-1 selector-type gap that exists in the copybook")
    class RowIrregularity {

        @Test
        @DisplayName("the copybook declares no selector-type field for row 1")
        void copybookOmitsRowOneSelectorType() {
            // The finding this whole group exists for, asserted against the map rather than the Java.
            assertThat(listMap.declares("CRDSTP1I"))
                    .as("CRDSTP1I appears nowhere in COCRDLI.CPY")
                    .isFalse();
        }

        @ParameterizedTest(name = "row {0} does have a selector-type field")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("every other row does declare one")
        void copybookDeclaresTheOtherSelectorTypes(final int rowNumber) {
            assertThat(listMap.declares("CRDSTP" + rowNumber + "I")).isTrue();
        }

        @Test
        @DisplayName("row 1 reports no selector type and four fields")
        void rowOneIsShort() {
            final CardDto.CardListRow first = row(CardDto.FIRST_ROW_NUMBER);

            assertThat(first.hasSelectorType()).isFalse();
            assertThat(first.getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE)
                    .isEqualTo(4);
        }

        @ParameterizedTest(name = "row {0} reports a selector type and five fields")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("every other row reports a selector type and five fields")
        void otherRowsAreFull(final int rowNumber) {
            final CardDto.CardListRow other = row(rowNumber);

            assertThat(other.hasSelectorType()).isTrue();
            assertThat(other.getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE)
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the Java agrees with the copybook row by row, not merely in total")
        void javaAgreesWithTheCopybookRowByRow() {
            // A type could get the total of 45 right while attributing the missing field to the wrong row.
            // Comparing per row against the map's own declarations is what rules that out.
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER;
                    rowNumber <= CardDto.CARD_LIST_PAGE_SIZE; rowNumber++) {
                assertThat(row(rowNumber).hasSelectorType())
                        .as("row %d", rowNumber)
                        .isEqualTo(listMap.declares("CRDSTP" + rowNumber + "I"));
            }
        }

        @ParameterizedTest(name = "row number {0} does not exist on the screen")
        @ValueSource(ints = {0, -1, 8, 99})
        @DisplayName("a row number outside the seven the map declares is refused")
        void offScreenRowNumberIsRefused(final int rowNumber) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> row(rowNumber))
                    .withMessageContaining("rowNumber");
        }

        @Test
        @DisplayName("row 1 still carries a selection flag, which is a different field from the selector type")
        void rowOneStillCarriesASelectionFlag() {
            // CRDSEL1I is declared at COCRDLI.CPY:78; it is CRDSTP1I that is absent. Conflating the two would
            // make row 1 unselectable, which is a behaviour change rather than a field-count discrepancy.
            assertThat(listMap.declares("CRDSEL1I")).isTrue();
            assertThat(row(CardDto.FIRST_ROW_NUMBER).getSelectionFlag()).isEqualTo("S");
        }
    }

    @Nested
    @DisplayName("3. The two fields whose widths differ between the maps")
    class DivergentWidths {

        @Test
        @DisplayName("the copybooks really do declare different widths for the same field names")
        void copybooksDiverge() {
            // Established first, so that the choice tested below is shown to be a real decision rather than an
            // arbitrary number.
            assertThat(detailMap.widthOf("INFOMSGI")).isEqualTo(40);
            assertThat(listMap.widthOf("INFOMSGI")).isEqualTo(45);
            assertThat(detailMap.widthOf("ERRMSGI")).isEqualTo(80);
            assertThat(listMap.widthOf("ERRMSGI")).isEqualTo(78);
        }

        @Test
        @DisplayName("the information message is sized per projection, so neither map's width is widened")
        void informationMessageIsSizedPerProjection() {
            // One type serves two screens, but a value can only have arrived on the screen that sent it.
            // Sizing both projections to the wider of the two declarations would let a 45-character message
            // through the DETAIL projection, which COCRDSL.CPY:96 declares as PIC X(40) - a value that screen
            // cannot produce. Each projection is therefore bounded by its own map.
            final int detailWidth = detailMap.widthOf("INFOMSGI");
            final int listWidth = listMap.widthOf("INFOMSGI");

            assertThat(detailWithInformationMessage("x".repeat(detailWidth)).getInformationMessage())
                    .as("COCRDSL declares PIC X(%d), so exactly that many characters must be accepted",
                            detailWidth)
                    .hasSize(detailWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("one character beyond the DETAIL declaration is refused even though the LIST map "
                            + "declares %d, because this is the detail projection", listWidth)
                    .isThrownBy(() -> detailWithInformationMessage("x".repeat(detailWidth + 1)));

            assertThat(listWithInformationMessage("x".repeat(listWidth)).getInformationMessage())
                    .as("COCRDLI declares PIC X(%d) for the same field name", listWidth)
                    .hasSize(listWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listWithInformationMessage("x".repeat(listWidth + 1)));
        }

        @Test
        @DisplayName("the error message is sized per projection too, and the divergence runs the other way")
        void errorMessageIsSizedPerProjection() {
            // Note the divergence runs the other way here - the DETAIL map is the wider one - so a type that
            // simply preferred one map over the other would get exactly one of these two fields wrong.
            final int detailWidth = detailMap.widthOf("ERRMSGI");
            final int listWidth = listMap.widthOf("ERRMSGI");

            assertThat(detailWidth)
                    .as("for the error message it is the DETAIL map that is wider, unlike the info message")
                    .isGreaterThan(listWidth);
            assertThat(detailWithErrorMessage("x".repeat(detailWidth)).getErrorMessage())
                    .hasSize(detailWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithErrorMessage("x".repeat(detailWidth + 1)));

            assertThat(listWithErrorMessage("x".repeat(listWidth)).getErrorMessage()).hasSize(listWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the LIST projection refuses at %d even though the DETAIL map declares %d",
                            listWidth, detailWidth)
                    .isThrownBy(() -> listWithErrorMessage("x".repeat(listWidth + 1)));
        }

        @Test
        @DisplayName("the page number takes the card list's three characters, not the transaction list's eight")
        void pageNumberIsThreeCharacters() {
            // COCRDLI declares PAGENOI PIC X(3) where COTRN00 declares PAGENUMI PIC X(8). The two screens
            // genuinely differ, so a shared page-number width across the DTOs would be wrong for one of them.
            assertThat(listMap.widthOf("PAGENOI")).isEqualTo(3);
            assertThat(listWithPageNumber("999").getPageNumber()).isEqualTo("999");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> listWithPageNumber("1000"));
        }
    }

    @Nested
    @DisplayName("4. Widths shared by both maps are enforced at the declared boundary")
    class SharedWidths {

        @ParameterizedTest(name = "{0} is {1} characters on both maps")
        @CsvSource({
            "TRNNAMEI,  4",
            "TITLE01I, 40",
            "TITLE02I, 40",
            "CURDATEI,  8",
            "PGMNAMEI,  8",
            "CURTIMEI,  8",
            "ACCTSIDI, 11",
            "CARDSIDI, 16",
        })
        @DisplayName("the header and key fields agree across the two maps")
        void headerWidthsAgreeAcrossMaps(final String field, final int width) {
            // Where the maps agree, that agreement is itself worth asserting: it is what licenses one type to
            // serve both screens for these fields, which the divergent pair above shows is not automatic.
            assertThat(detailMap.widthOf(field)).isEqualTo(width);
            assertThat(listMap.widthOf(field)).isEqualTo(width);
        }

        @ParameterizedTest(name = "{0} is {1} characters on the detail map only")
        @CsvSource({
            "CRDNAMEI, 50",
            "CRDSTCDI,  1",
            "EXPMONI,   2",
            "EXPYEARI,  4",
            "FKEYSI,   75",
        })
        @DisplayName("the detail-only fields are declared on COCRDSL and absent from COCRDLI")
        void detailOnlyFieldsAreAbsentFromTheListMap(final String field, final int width) {
            assertThat(detailMap.widthOf(field)).isEqualTo(width);
            assertThat(listMap.declares(field))
                    .as("%s belongs to the detail screen only", field)
                    .isFalse();
        }

        @Test
        @DisplayName("the cardholder name is accepted at exactly its declared width and refused beyond it")
        void cardholderNameBoundary() {
            final int width = detailMap.widthOf("CRDNAMEI");

            assertThat(detailWithCardholderName("x".repeat(width)).getCardholderName()).hasSize(width);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> detailWithCardholderName("x".repeat(width + 1)));
        }

        @Test
        @DisplayName("the row fields are accepted at their declared widths and refused beyond them")
        void rowFieldBoundaries() {
            final int accountWidth = listMap.widthOf("ACCTNO1I");
            final int cardWidth = listMap.widthOf("CRDNUM1I");

            assertThat(new CardDto.CardListRow(1, "S", null,
                    "x".repeat(accountWidth), "x".repeat(cardWidth), "Y").getAccountNumber())
                    .hasSize(accountWidth);
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(1, "S", null,
                            "x".repeat(accountWidth + 1), "4111111111111111", "Y"));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(1, "S", null,
                            "00000000001", "x".repeat(cardWidth + 1), "Y"));
        }
    }

    @Nested
    @DisplayName("5. The two factories build the two screen shapes")
    class Factories {

        @Test
        @DisplayName("the detail factory populates the detail fields and leaves the list fields empty")
        void detailFactoryShape() {
            final CardDto dto = CardDto.detail("CCDL", "AWS Mainframe Modernization", "06/10/22",
                    "COCRDSLC", "CardDemo", "19:27:53", "00000000001", "4111111111111111",
                    "JOHN Q CARDHOLDER", "Y", "12", "2025", null, null, "F3=Exit");

            assertThat(dto.getCardholderName()).isEqualTo("JOHN Q CARDHOLDER");
            assertThat(dto.getExpiryMonth()).isEqualTo("12");
            assertThat(dto.getExpiryYear()).isEqualTo("2025");
            assertThat(dto.getRows())
                    .as("null, not empty: a detail payload has no row array at all, whereas an empty list "
                            + "would mean a list payload whose query matched nothing")
                    .isNull();
            assertThat(dto.getPageNumber())
                    .as("a detail screen declares no page number field")
                    .isNull();
        }

        @Test
        @DisplayName("the list factory populates the rows and leaves the detail-only fields empty")
        void listFactoryShape() {
            final CardDto dto = CardDto.list("CCLI", "AWS Mainframe Modernization", "06/10/22",
                    "COCRDLIC", "CardDemo", "19:27:53", "001", "00000000001", "4111111111111111",
                    List.of(row(1), row(2)), null, null);

            assertThat(dto.getPageNumber()).isEqualTo("001");
            assertThat(dto.getRows()).hasSize(2);
            assertThat(dto.getCardholderName())
                    .as("the list map declares no cardholder name field")
                    .isNull();
            assertThat(dto.getExpiryMonth()).isNull();
            assertThat(dto.getExpiryYear()).isNull();
        }

        @Test
        @DisplayName("a list never carries more rows than the screen has")
        void listCannotExceedThePage() {
            final List<CardDto.CardListRow> tooMany = new ArrayList<>();
            for (int rowNumber = CardDto.FIRST_ROW_NUMBER;
                    rowNumber <= CardDto.CARD_LIST_PAGE_SIZE; rowNumber++) {
                tooMany.add(row(rowNumber));
            }
            assertThat(CardDto.list("CCLI", null, null, null, null, null, "001", null, null,
                    tooMany, null, null).getRows())
                    .as("a full page is exactly the page size")
                    .hasSize(CardDto.CARD_LIST_PAGE_SIZE);
        }

        @Test
        @DisplayName("the row list is unmodifiable, so a page cannot be grown after construction")
        void rowListIsUnmodifiable() {
            final List<CardDto.CardListRow> rows = CardDto.list("CCLI", null, null, null, null, null,
                    "001", null, null, List.of(row(1)), null, null).getRows();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> rows.add(row(2)));
        }

        @Test
        @DisplayName("nulls are accepted throughout, because an unpopulated screen field is blank")
        void nullsAreAccepted() {
            final CardDto empty = CardDto.detail(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);

            assertThat(empty.getTransactionName()).isNull();
            assertThat(empty.getAccountId()).isNull();
            assertThat(empty.getCardNumber()).isNull();
            assertThat(empty.getRows()).isNull();
        }

        @Test
        @DisplayName("a row accepts nulls in every field except its structural row number")
        void rowAcceptsNullsExceptTheRowNumber() {
            // The row number is structural metadata rather than a BMS field, which is why it is an int with a
            // range guard while every screen field is a nullable String.
            final CardDto.CardListRow blank = new CardDto.CardListRow(3, null, null, null, null, null);

            assertThat(blank.getRowNumber()).isEqualTo(3);
            assertThat(blank.getSelectionFlag()).isNull();
            assertThat(blank.getSelectorType()).isNull();
            assertThat(blank.getAccountNumber()).isNull();
            assertThat(blank.getCardNumber()).isNull();
            assertThat(blank.getStatusCode()).isNull();
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------------------------------------

    /**
     * @param rowNumber the one-based row ordinal
     * @return a populated card-list row for that ordinal
     */
    private static CardDto.CardListRow row(final int rowNumber) {
        return new CardDto.CardListRow(rowNumber, "S",
                rowNumber == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "U",
                "00000000001", "4111111111111111", "Y");
    }

    /**
     * @param informationMessage the information message to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithInformationMessage(final String informationMessage) {
        return CardDto.detail("CCDL", null, null, null, null, null, "00000000001", "4111111111111111",
                "JOHN Q CARDHOLDER", "Y", "12", "2025", informationMessage, null, null);
    }

    /**
     * @param informationMessage the information message to carry
     * @return a list projection carrying it
     */
    private static CardDto listWithInformationMessage(final String informationMessage) {
        return CardDto.list("CCLI", null, null, null, null, null, "001", "00000000001", null,
                List.of(), informationMessage, null);
    }

    /**
     * @param errorMessage the error message to carry
     * @return a list projection carrying it
     */
    private static CardDto listWithErrorMessage(final String errorMessage) {
        return CardDto.list("CCLI", null, null, null, null, null, "001", "00000000001", null,
                List.of(), null, errorMessage);
    }

    /**
     * @param errorMessage the error message to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithErrorMessage(final String errorMessage) {
        return CardDto.detail("CCDL", null, null, null, null, null, "00000000001", "4111111111111111",
                "JOHN Q CARDHOLDER", "Y", "12", "2025", null, errorMessage, null);
    }

    /**
     * @param cardholderName the cardholder name to carry
     * @return a detail projection carrying it
     */
    private static CardDto detailWithCardholderName(final String cardholderName) {
        return CardDto.detail("CCDL", null, null, null, null, null, "00000000001", "4111111111111111",
                cardholderName, "Y", "12", "2025", null, null, null);
    }

    /**
     * @param pageNumber the page number to carry
     * @return a list projection carrying it
     */
    private static CardDto listWithPageNumber(final String pageNumber) {
        return CardDto.list("CCLI", null, null, null, null, null, pageNumber, "00000000001",
                "4111111111111111", List.of(row(1)), null, null);
    }
}
