/*
 * ******************************************************************
 * Program     : TransactionDtoApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the two projections this type serves - the
 *               twenty-one field detail screen and the fifty-nine
 *               field paginated list - and in particular that the
 *               BigDecimal arithmetic companion never reaches the
 *               wire. Neither COTRN01 nor COTRN00 declares a numeric
 *               amount field, so emitting one would invent a
 *               twenty-fourth property and offer a second,
 *               differently formatted rendering of the same money.
 * Source      : app/cpy-bms/COTRN01.CPY (21 input fields, group
 *               COTRN1AI) + app/cpy-bms/COTRN00.CPY (59 input
 *               fields) + app/cbl/COTRN00C.cbl:65-68 (page size 10)
 *               + app/cpy/CVTRA05Y.cpy:10 (TRAN-AMT PIC S9(09)V99)
 *               @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.dto.TransactionDto;
import com.cardemo.model.dto.TransactionDto.TransactionListRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Unit contract for {@link TransactionDto} and its nested row type.
 *
 * <p>One type serves two screens here, which is why the projection assertions matter: a detail response
 * carries twenty-one fields and no rows, a list response carries eight preamble fields, ten five-field rows
 * and one trailer, and the two must not blur into each other. The heaviest emphasis, though, falls on the
 * {@code amountValue} companion. It exists so that a caller can do arithmetic without re-parsing the edited
 * display mask, and it must stay strictly in-process: the twenty-three wire fields are exactly what the two
 * symbolic maps declare, and a twenty-fourth would be an invention.
 */
@DisplayName("TransactionDto - app/cpy-bms/COTRN01.CPY + COTRN00.CPY, one type serving two projections")
class TransactionDtoApiContractTest {

    /** The twenty-three serialized component names, in declaration order. */
    private static final List<String> WIRE_COMPONENT_ORDER = List.of("transactionName", "title01",
            "currentDate", "programName", "title02", "currentTime", "transactionIdInput", "transactionId",
            "cardNumber", "typeCode", "categoryCode", "source", "description", "amount", "originatingDate",
            "processingDate", "merchantId", "merchantName", "merchantCity", "merchantZip", "errorMessage",
            "pageNumber", "rows");

    /** A synthetic primary account number, distinctive so that a rendering leak is unambiguous. */
    private static final String SYNTHETIC_CARD_NUMBER = "4111999988887777";

    /** A synthetic transaction identifier at the declared width. */
    private static final String SYNTHETIC_TRANSACTION_ID = "0000000000000042";

    /** A synthetic merchant name, distinctive for the same reason as the card number. */
    private static final String SYNTHETIC_MERCHANT_NAME = "ZZQQ-MERCHANT-LEAK-CANARY";

    /** The amount as the edited screen mask renders it. */
    private static final String SYNTHETIC_AMOUNT_IMAGE = "+00076543.21";

    /** The same amount as an arithmetic value; the two representations must stay distinguishable. */
    private static final BigDecimal SYNTHETIC_AMOUNT_VALUE = new BigDecimal("76543.21");

    /**
     * The twenty-one detail field contracts of {@code COTRN1AI}, in copybook declaration order.
     *
     * @return {@code componentName}, {@code cobolItem}, {@code sourceLine}, {@code width} tuples
     */
    static Stream<Arguments> detailFieldContracts() {
        return Stream.of(
                Arguments.of("transactionName", "TRNNAMEI", 24, TransactionDto.TRANSACTION_NAME_LENGTH),
                Arguments.of("title01", "TITLE01I", 30, TransactionDto.TITLE_LENGTH),
                Arguments.of("currentDate", "CURDATEI", 36, TransactionDto.CURRENT_DATE_LENGTH),
                Arguments.of("programName", "PGMNAMEI", 42, TransactionDto.PROGRAM_NAME_LENGTH),
                Arguments.of("title02", "TITLE02I", 48, TransactionDto.TITLE_LENGTH),
                Arguments.of("currentTime", "CURTIMEI", 54, TransactionDto.CURRENT_TIME_LENGTH),
                Arguments.of("transactionIdInput", "TRNIDINI", 60, TransactionDto.TRANSACTION_ID_LENGTH),
                Arguments.of("transactionId", "TRNIDI", 66, TransactionDto.TRANSACTION_ID_LENGTH),
                Arguments.of("cardNumber", "CARDNUMI", 72, TransactionDto.CARD_NUMBER_LENGTH),
                Arguments.of("typeCode", "TTYPCDI", 78, TransactionDto.TYPE_CODE_LENGTH),
                Arguments.of("categoryCode", "TCATCDI", 84, TransactionDto.CATEGORY_CODE_LENGTH),
                Arguments.of("source", "TRNSRCI", 90, TransactionDto.SOURCE_LENGTH),
                Arguments.of("description", "TDESCI", 96, TransactionDto.DESCRIPTION_LENGTH),
                Arguments.of("amount", "TRNAMTI", 102, TransactionDto.AMOUNT_DISPLAY_LENGTH),
                Arguments.of("originatingDate", "TORIGDTI", 108, TransactionDto.DETAIL_DATE_LENGTH),
                Arguments.of("processingDate", "TPROCDTI", 114, TransactionDto.DETAIL_DATE_LENGTH),
                Arguments.of("merchantId", "MIDI", 120, TransactionDto.MERCHANT_ID_LENGTH),
                Arguments.of("merchantName", "MNAMEI", 126, TransactionDto.MERCHANT_NAME_LENGTH),
                Arguments.of("merchantCity", "MCITYI", 132, TransactionDto.MERCHANT_CITY_LENGTH),
                Arguments.of("merchantZip", "MZIPI", 138, TransactionDto.MERCHANT_ZIP_LENGTH),
                Arguments.of("errorMessage", "ERRMSGI", 144, TransactionDto.ERROR_MESSAGE_LENGTH));
    }

    /**
     * The five row field contracts of the transaction list screen.
     *
     * @return {@code componentName}, {@code width} pairs
     */
    static Stream<Arguments> rowFieldContracts() {
        return Stream.of(
                Arguments.of("selectionFlag", TransactionDto.SELECTION_FLAG_LENGTH),
                Arguments.of("transactionId", TransactionDto.TRANSACTION_ID_LENGTH),
                Arguments.of("transactionDate", TransactionDto.ROW_DATE_LENGTH),
                Arguments.of("description", TransactionDto.ROW_DESCRIPTION_LENGTH),
                Arguments.of("amount", TransactionDto.AMOUNT_DISPLAY_LENGTH));
    }

    /** The detail projection's component values, positionally aligned with the twenty-one map fields. */
    private static final List<String> DETAIL_VALUES = List.of("CT01", "CardDemo", "07/24/26", "COTRN01C",
            "View Transaction", "14:30:00", SYNTHETIC_TRANSACTION_ID, SYNTHETIC_TRANSACTION_ID,
            SYNTHETIC_CARD_NUMBER, "01", "0001", "POS", "SYNTHETIC PURCHASE", SYNTHETIC_AMOUNT_IMAGE,
            "2026-07-24", "2026-07-24", "000000123", SYNTHETIC_MERCHANT_NAME, "SYNTHETIC CITY",
            "0000012345", "");

    /**
     * A fully populated detail response: twenty-one fields, no page number and no rows.
     *
     * @return a valid detail projection carrying the arithmetic companion
     */
    private static TransactionDto detail() {
        return buildDetail(DETAIL_VALUES, null, null, SYNTHETIC_AMOUNT_VALUE);
    }

    /**
     * A fully populated list response: the preamble, a page number and one populated row.
     *
     * @return a valid list projection
     */
    private static TransactionDto list() {
        return buildDetail(DETAIL_VALUES, "00000001",
                List.of(new TransactionListRow(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26", "SYNTHETIC",
                        SYNTHETIC_AMOUNT_IMAGE)),
                SYNTHETIC_AMOUNT_VALUE);
    }

    /**
     * Builds a response from twenty-one positional detail values plus the three trailing components.
     *
     * @param values      exactly twenty-one detail values in copybook order
     * @param pageNumber  the list projection's page number, or {@code null} for a detail response
     * @param rows        the list projection's rows, or {@code null} for a detail response
     * @param amountValue the arithmetic companion, which may be {@code null}
     * @return the response the arguments describe
     */
    private static TransactionDto buildDetail(final List<String> values, final String pageNumber,
            final List<TransactionListRow> rows, final BigDecimal amountValue) {
        if (values.size() != TransactionDto.DETAIL_FIELD_COUNT) {
            throw new AssertionError("expected " + TransactionDto.DETAIL_FIELD_COUNT + " values, got "
                    + values.size());
        }

        return new TransactionDto(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4),
                values.get(5), values.get(6), values.get(7), values.get(8), values.get(9), values.get(10),
                values.get(11), values.get(12), values.get(13), values.get(14), values.get(15),
                values.get(16), values.get(17), values.get(18), values.get(19), values.get(20), pageNumber,
                rows, amountValue);
    }

    /**
     * Builds the detail response with exactly one of the twenty-one map fields replaced.
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the detail response carrying {@code value} in {@code componentName}
     */
    private static TransactionDto withComponent(final String componentName, final String value) {
        final int index = WIRE_COMPONENT_ORDER.indexOf(componentName);

        if (index < 0 || index >= TransactionDto.DETAIL_FIELD_COUNT) {
            throw new AssertionError("TransactionDto declares no detail component " + componentName);
        }
        final List<String> values = new ArrayList<>(DETAIL_VALUES);
        values.set(index, value);

        return buildDetail(values, null, null, SYNTHETIC_AMOUNT_VALUE);
    }

    /**
     * Builds a row with exactly one component replaced.
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the row carrying {@code value} in {@code componentName}
     */
    private static TransactionListRow rowWith(final String componentName, final String value) {
        final List<String> values = new ArrayList<>(
                List.of(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26", "SYNTHETIC", SYNTHETIC_AMOUNT_IMAGE));
        final List<String> names = Arrays.stream(TransactionListRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        final int index = names.indexOf(componentName);

        if (index < 0) {
            throw new AssertionError("TransactionListRow declares no component " + componentName);
        }
        values.set(index, value);

        return new TransactionListRow(values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4));
    }

    @Nested
    @DisplayName("1. HIGH: the arithmetic companion never reaches the wire")
    class ArithmeticCompanionIsNotAWireField {

        @Test
        @DisplayName("is absent from the serialized property census")
        void isAbsentFromTheSerializedPropertyCensus() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final Map<String, Object> emitted = mapper.readValue(mapper.writeValueAsString(detail()),
                    new TypeReference<LinkedHashMap<String, Object>>() { });

            assertThat(emitted.keySet())
                    .as("COTRN01 declares twenty-one fields and COTRN00 declares fifty-nine, and neither "
                            + "declares a numeric amount. Emitting the companion would invent a "
                            + "twenty-fourth property that no screen ever had")
                    .doesNotContain("amountValue")
                    .containsExactlyInAnyOrderElementsOf(WIRE_COMPONENT_ORDER);
        }

        @Test
        @DisplayName("its name appears nowhere in the serialized document")
        void itsNameAppearsNowhereInTheSerializedDocument() throws Exception {
            assertThat(new ObjectMapper().writeValueAsString(detail()))
                    .as("a raw string check catches a leak that a key census would miss if the property "
                            + "were nested rather than top level")
                    .doesNotContain("amountValue");
        }

        @Test
        @DisplayName("does not publish a second differently formatted rendering of the same money")
        void doesNotPublishASecondRenderingOfTheSameMoney() throws Exception {
            final String json = new ObjectMapper().writeValueAsString(detail());

            assertThat(json)
                    .as("the edited mask carries eight integer digits while the numeric field carries "
                            + "nine, so the two representations of one amount are not interchangeable. "
                            + "Publishing both would leave a caller to guess which is authoritative")
                    .contains("\"amount\":\"" + SYNTHETIC_AMOUNT_IMAGE + "\"")
                    .doesNotContain(SYNTHETIC_AMOUNT_VALUE.toPlainString() + ",")
                    .doesNotContain(":" + SYNTHETIC_AMOUNT_VALUE.toPlainString());
        }

        @Test
        @DisplayName("remains readable in process through its accessor")
        void remainsReadableInProcessThroughItsAccessor() {
            assertThat(detail().amountValue())
                    .as("the companion exists so a caller can do arithmetic without re-parsing the edited "
                            + "mask; suppressing it on the wire must not suppress it in the JVM")
                    .isEqualByComparingTo(SYNTHETIC_AMOUNT_VALUE);
        }

        @Test
        @DisplayName("is still accepted by the canonical constructor, which is how the service sets it")
        void isStillAcceptedByTheCanonicalConstructor() {
            assertThat(buildDetail(DETAIL_VALUES, null, null, new BigDecimal("-1.50")).amountValue())
                    .as("the constructor parameter is unaffected by the wire suppression")
                    .isEqualByComparingTo(new BigDecimal("-1.50"));
        }

        @Test
        @DisplayName("is dropped rather than bound when a caller tries to smuggle it inbound")
        void isDroppedRatherThanBoundWhenSmuggledInbound() throws Exception {
            final TransactionDto bound = new ObjectMapper().readValue(
                    "{\"transactionId\":\"" + SYNTHETIC_TRANSACTION_ID + "\",\"amountValue\":\"999.99\"}",
                    TransactionDto.class);

            assertThat(bound.amountValue())
                    .as("the companion is derived from the persisted amount by the service, so a caller "
                            + "supplied value has no standing; it is ignored rather than trusted")
                    .isNull();
            assertThat(bound.transactionId())
                    .as("the declared components around it must still bind normally")
                    .isEqualTo(SYNTHETIC_TRANSACTION_ID);
        }

        @Test
        @DisplayName("survives a round trip as null, since the wire cannot carry it")
        void survivesARoundTripAsNull() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final TransactionDto bound =
                    mapper.readValue(mapper.writeValueAsString(detail()), TransactionDto.class);

            assertThat(bound.amountValue())
                    .as("a round trip through JSON is not an identity for this component by design, and "
                            + "that is the observable consequence of it not being a wire field")
                    .isNull();
            assertThat(bound.amount())
                    .as("the edited mask, which IS a wire field, round-trips verbatim")
                    .isEqualTo(SYNTHETIC_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("is normalised to the persisted scale rather than stored as supplied")
        void isNormalisedToThePersistedScale() {
            assertThat(buildDetail(DETAIL_VALUES, null, null, new BigDecimal("5")).amountValue())
                    .as("TRAN-AMT at app/cpy/CVTRA05Y.cpy:10 is PIC S9(09)V99, so the companion carries "
                            + "scale two whatever scale the caller's literal happened to have")
                    .isEqualTo(new BigDecimal("5.00"));
            assertThat(buildDetail(DETAIL_VALUES, null, null, null).amountValue())
                    .as("null must stay null rather than becoming zero, so an absent amount stays "
                            + "distinguishable from a genuine zero")
                    .isNull();
        }

        @ParameterizedTest(name = "{0} rounds half-even to {1}")
        @CsvSource({"1.005, 1.00", "1.015, 1.02", "1.025, 1.02", "-1.015, -1.02"})
        @DisplayName("rounds half to even, never half up")
        void roundsHalfToEven(final String supplied, final String expected) {
            assertThat(buildDetail(DETAIL_VALUES, null, null, new BigDecimal(supplied)).amountValue())
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("refuses a magnitude beyond the nine integer digits the PIC clause permits")
        void refusesAMagnitudeBeyondNineIntegerDigits() {
            assertThat(buildDetail(DETAIL_VALUES, null, null, new BigDecimal("999999999.99")).amountValue())
                    .isEqualByComparingTo(new BigDecimal("999999999.99"));

            assertThatThrownBy(
                    () -> buildDetail(DETAIL_VALUES, null, null, new BigDecimal("1000000000.00")))
                    .as("one more than the ceiling cannot be represented in the persisted field")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                    () -> buildDetail(DETAIL_VALUES, null, null, new BigDecimal("-1000000000.00")))
                    .as("the sign is overpunched rather than occupying a byte, so the negative range "
                            + "mirrors the positive one and must be bounded alike")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("preserves a negative companion rather than normalising its sign away")
        void preservesANegativeCompanion() {
            assertThat(buildDetail(DETAIL_VALUES, null, null, new BigDecimal("-42.50")).amountValue())
                    .as("no absolute-value normalisation is permitted anywhere on a money path")
                    .isNegative()
                    .isEqualByComparingTo(new BigDecimal("-42.50"));
        }

        @Test
        @DisplayName("carries no floating-point type in any component")
        void carriesNoFloatingPointType() {
            assertThat(Arrays.stream(TransactionDto.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .filter(name -> name.contains("Float") || name.contains("Double")
                            || "float".equals(name) || "double".equals(name))
                    .toList())
                    .as("the companion is the only numeric component and it is a BigDecimal; the security "
                            + "gate asserts the absence of float and double across every financial field")
                    .isEmpty();
            assertThat(Arrays.stream(TransactionDto.class.getRecordComponents())
                    .filter(component -> "amountValue".equals(component.getName()))
                    .map(RecordComponent::getType)
                    .findFirst()
                    .orElseThrow())
                    .isEqualTo(BigDecimal.class);
        }
    }

    @Nested
    @DisplayName("2. Detail projection - the twenty-one fields of COTRN01.CPY group COTRN1AI")
    class DetailProjection {

        @Test
        @DisplayName("declares the detail field count the copybook declares")
        void declaresTheDetailFieldCount() {
            assertThat(TransactionDto.DETAIL_FIELD_COUNT)
                    .as("group COTRN1AI declares twenty-one 02-level PIC items, including BOTH TRNIDINI "
                            + "at :60 and TRNIDI at :66 - the search key the operator types and the "
                            + "identifier the program echoes back are separate fields on that screen")
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("projects exactly twenty-one values in copybook order")
        void projectsExactlyTwentyOneValuesInOrder() {
            assertThat(detail().detailProjection())
                    .as("the projection is what a fixed-width comparison reads, so both its length and "
                            + "its order are contractual")
                    .hasSize(TransactionDto.DETAIL_FIELD_COUNT)
                    .startsWith("CT01", "CardDemo", "07/24/26", "COTRN01C", "View Transaction", "14:30:00")
                    .containsSequence(SYNTHETIC_TRANSACTION_ID, SYNTHETIC_TRANSACTION_ID,
                            SYNTHETIC_CARD_NUMBER);
        }

        @Test
        @DisplayName("keeps the typed search key and the echoed identifier as separate components")
        void keepsTheSearchKeyAndEchoedIdentifierSeparate() {
            final TransactionDto searched = withComponent("transactionIdInput", "0000000000000001");

            assertThat(searched.transactionIdInput())
                    .as("collapsing TRNIDINI and TRNIDI into one component would make it impossible to "
                            + "show the operator what they typed alongside what was found")
                    .isEqualTo("0000000000000001");
            assertThat(searched.transactionId()).isEqualTo(SYNTHETIC_TRANSACTION_ID);
        }

        @ParameterizedTest(name = "{1} PIC X({3}) at COTRN01.CPY:{2} -> {0}")
        @MethodSource("com.cardemo.unit.model.TransactionDtoApiContractTest#detailFieldContracts")
        @DisplayName("accepts a value at exactly the declared width and refuses one character more")
        void acceptsExactWidthAndRefusesOneMore(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            assertThat(withComponent(componentName, "x".repeat(width)))
                    .as("%d characters is the largest legal value for %s at app/cpy-bms/COTRN01.CPY:%d",
                            width, cobolItem, sourceLine)
                    .isNotNull();

            assertThatThrownBy(() -> withComponent(componentName, "x".repeat(width + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(componentName)
                    .hasMessageNotContaining("x".repeat(width + 1));
        }

        @Test
        @DisplayName("accepts null and blank throughout, because both are real screen states")
        void acceptsNullAndBlankThroughout() {
            assertThat(buildDetail(Collections.nCopies(TransactionDto.DETAIL_FIELD_COUNT, null),
                    null, null, null))
                    .as("an unpopulated detail screen is what the operator sees before searching")
                    .isNotNull();
            assertThat(withComponent("merchantName", " ".repeat(30)).merchantName())
                    .as("a blank-padded fixed-width field must survive verbatim, neither trimmed nor "
                            + "collapsed to empty")
                    .isEqualTo(" ".repeat(30));
        }

        @Test
        @DisplayName("separates the screen description width from the persisted one")
        void separatesScreenAndPersistedDescriptionWidths() {
            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .as("TDESCI is PIC X(60) on the detail screen while TRAN-DESC is PIC X(100) in the "
                            + "record; the screen shows a truncated view and the two must not be unified")
                    .isEqualTo(60)
                    .isLessThan(TransactionDto.DESCRIPTION_PERSISTED_LENGTH);
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .as("the list row is narrower again, at 26 characters, because ten rows share one "
                            + "screen")
                    .isEqualTo(26)
                    .isLessThan(TransactionDto.DESCRIPTION_LENGTH);
        }

        @Test
        @DisplayName("separates the detail date width from the row and persisted timestamp widths")
        void separatesDateWidths() {
            assertThat(TransactionDto.DETAIL_DATE_LENGTH)
                    .as("the detail screen shows a ten-character date, the list row an eight-character "
                            + "one, and the record a twenty-six-character timestamp; three widths for "
                            + "three renderings of one instant")
                    .isEqualTo(10);
            assertThat(TransactionDto.ROW_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionDto.PERSISTED_TIMESTAMP_LENGTH).isEqualTo(26);
        }

        @Test
        @DisplayName("carries no page number and no rows, which is what makes it a detail response")
        void carriesNoPageNumberAndNoRows() {
            assertThat(detail().pageNumber()).isNull();
            assertThat(detail().rows())
                    .as("null means 'not a list projection at all', which is distinct from an empty list "
                            + "meaning 'a list that displayed no rows'")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("3. List projection - the fifty-nine fields of COTRN00.CPY, ten rows per page")
    class ListProjection {

        @Test
        @DisplayName("resolves the fifty-nine field count from its four declared parts")
        void resolvesTheFiftyNineFieldCount() {
            assertThat(TransactionDto.LIST_PREAMBLE_FIELD_COUNT
                    + TransactionDto.PAGE_SIZE * TransactionDto.LIST_ROW_FIELD_COUNT
                    + TransactionDto.LIST_TRAILER_FIELD_COUNT)
                    .as("COTRN00 declares six headers plus a page number and a search key, then ten rows "
                            + "of five fields, then one error message: 8 + 50 + 1 = 59")
                    .isEqualTo(TransactionDto.LIST_FIELD_COUNT)
                    .isEqualTo(59);
        }

        @Test
        @DisplayName("declares the page size the source declares, ten rows")
        void declaresThePageSize() {
            assertThat(TransactionDto.PAGE_SIZE)
                    .as("app/cbl/COTRN00C.cbl:65-68 declares ten row slots - distinct from the card list, "
                            + "which declares seven; the two must not be unified into one constant")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("projects exactly fifty-nine values")
        void projectsExactlyFiftyNineValues() {
            assertThat(list().listProjection())
                    .as("every slot is projected whether or not it is populated, because the screen always "
                            + "sends all ten rows")
                    .hasSize(TransactionDto.LIST_FIELD_COUNT);
        }

        @Test
        @DisplayName("pads unpopulated row slots with nulls rather than shortening the projection")
        void padsUnpopulatedRowSlotsWithNulls() {
            final List<String> projection = list().listProjection();

            assertThat(projection).hasSize(59);
            assertThat(projection.subList(TransactionDto.LIST_PREAMBLE_FIELD_COUNT
                            + TransactionDto.LIST_ROW_FIELD_COUNT, projection.size() - 1))
                    .as("one row was supplied, so the nine remaining slots must project as forty-five "
                            + "nulls; shortening the projection instead would misalign every field after "
                            + "the rows")
                    .hasSize(45)
                    .containsOnlyNulls();
        }

        @Test
        @DisplayName("returns null for a slot beyond the supplied rows, and refuses one out of range")
        void returnsNullBeyondSuppliedRowsAndRefusesOutOfRange() {
            assertThat(list().rowAt(0)).isNotNull();
            assertThat(list().rowAt(TransactionDto.PAGE_SIZE - 1))
                    .as("a slot inside the page but beyond the supplied rows is empty, not an error")
                    .isNull();

            assertThatThrownBy(() -> list().rowAt(TransactionDto.PAGE_SIZE))
                    .as("a slot outside the page is a programming error and must be refused rather than "
                            + "silently yielding null")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(TransactionDto.PAGE_SIZE));
            assertThatThrownBy(() -> list().rowAt(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "row slot {0} is addressable")
        @ValueSource(ints = {0, 1, 5, 9})
        @DisplayName("addresses every slot the page declares without throwing")
        void addressesEverySlotThePageDeclares(final int slot) {
            final TransactionDto response = list();

            assertThat(catchThrowable(() -> response.rowAt(slot)))
                    .as("every slot from 0 to PAGE_SIZE minus one is addressable; an unpopulated slot "
                            + "yields null rather than raising, because the screen always sends all ten")
                    .isNull();
            if (slot == 0) {
                assertThat(response.rowAt(slot).transactionId()).isEqualTo(SYNTHETIC_TRANSACTION_ID);
            } else {
                assertThat(response.rowAt(slot)).isNull();
            }
        }

        @Test
        @DisplayName("takes a defensive copy of the rows on construction and on access")
        void takesADefensiveCopyOfTheRows() {
            final List<TransactionListRow> supplied = new ArrayList<>(List.of(
                    new TransactionListRow(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26", "A", "+1.00")));
            final TransactionDto response =
                    buildDetail(DETAIL_VALUES, "00000001", supplied, SYNTHETIC_AMOUNT_VALUE);

            supplied.clear();

            assertThat(response.rows())
                    .as("a caller retaining the list must not be able to empty the response after it was "
                            + "validated")
                    .hasSize(1);
            assertThatThrownBy(() -> response.rows().clear())
                    .as("the guarantee must hold on access as well as on construction")
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("keeps an empty row list distinct from an absent one")
        void keepsEmptyRowsDistinctFromAbsent() {
            assertThat(buildDetail(DETAIL_VALUES, "00000001", List.of(), null).rows())
                    .as("an empty list is a list projection that displayed no rows, which is a different "
                            + "outcome from a detail response that has no list at all")
                    .isNotNull()
                    .isEmpty();
            assertThat(buildDetail(DETAIL_VALUES, null, null, null).rows()).isNull();
        }

        @ParameterizedTest(name = "row {0} bounded at width {1}")
        @MethodSource("com.cardemo.unit.model.TransactionDtoApiContractTest#rowFieldContracts")
        @DisplayName("bounds every row component at its declared width")
        void boundsEveryRowComponentAtItsDeclaredWidth(final String componentName, final int width) {
            assertThat(rowWith(componentName, "x".repeat(width))).isNotNull();

            assertThatThrownBy(() -> rowWith(componentName, "x".repeat(width + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(componentName)
                    .hasMessageNotContaining("x".repeat(width + 1));
        }

        @Test
        @DisplayName("declares exactly the five row components the screen declares")
        void declaresExactlyFiveRowComponents() {
            assertThat(Arrays.stream(TransactionListRow.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("each of the ten row groups carries a selection flag, an identifier, a date, a "
                            + "description and an amount")
                    .containsExactly("selectionFlag", "transactionId", "transactionDate", "description",
                            "amount")
                    .hasSize(TransactionDto.LIST_ROW_FIELD_COUNT);
        }
    }

    @Nested
    @DisplayName("4. Diagnostic renderings - no card number on either type")
    class DiagnosticRenderings {

        @Test
        @DisplayName("both types override toString rather than inheriting the generated one")
        void bothTypesOverrideToString() {
            assertThat(List.of(TransactionDto.class, TransactionListRow.class))
                    .allSatisfy(type -> assertThat(Arrays.stream(type.getDeclaredMethods())
                            .map(method -> method.getName())
                            .filter("toString"::equals)
                            .toList())
                            .as("%s must declare its own toString", type.getSimpleName())
                            .containsExactly("toString"));
        }

        @Test
        @DisplayName("the response rendering emits no card number, amount or merchant name")
        void theResponseRenderingEmitsNoBusinessData() {
            final String rendered = detail().toString();

            assertThat(rendered)
                    .isEqualTo("TransactionDto[transactionId=" + SYNTHETIC_TRANSACTION_ID
                            + ", programName=COTRN01C, protectedFieldsOmitted=true]");
            assertThat(rendered)
                    .as("cardNumber at app/cpy-bms/COTRN01.CPY:72 is a primary account number")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_AMOUNT_IMAGE)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME)
                    .doesNotContain("7777")
                    .doesNotContain("4111");
        }

        @Test
        @DisplayName("the rendering states that fields were omitted, so a reader is not misled")
        void theRenderingStatesThatFieldsWereOmitted() {
            assertThat(detail().toString())
                    .as("a narrowed rendering that looked complete could lead a reader to conclude the "
                            + "omitted fields were empty; the marker removes that ambiguity")
                    .contains("protectedFieldsOmitted=true");
        }

        @Test
        @DisplayName("the row rendering emits the identifier only, not the description or amount")
        void theRowRenderingEmitsTheIdentifierOnly() {
            final String rendered = new TransactionListRow(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26",
                    "SYNTHETIC", SYNTHETIC_AMOUNT_IMAGE).toString();

            assertThat(rendered).isEqualTo("TransactionDto.TransactionListRow[transactionId="
                    + SYNTHETIC_TRANSACTION_ID + "]");
            assertThat(rendered)
                    .as("a row's description and amount are business data that has no place in a log line")
                    .doesNotContain(SYNTHETIC_AMOUNT_IMAGE)
                    .doesNotContain("SYNTHETIC");
        }

        @Test
        @DisplayName("neither discloses anything when interpolated, nor when a list is rendered")
        void neitherDisclosesAnythingWhenInterpolated() {
            assertThat("transaction lookup failed: " + list())
                    .as("the list projection holds rows, so a generated rendering would have expanded "
                            + "every one of them")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME)
                    .doesNotContain(SYNTHETIC_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("keeps every value readable through its accessor, so nothing was lost")
        void keepsEveryValueReadableThroughItsAccessor() {
            assertThat(detail().cardNumber()).isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThat(detail().merchantName()).isEqualTo(SYNTHETIC_MERCHANT_NAME);
            assertThat(detail().amount()).isEqualTo(SYNTHETIC_AMOUNT_IMAGE);
        }

        @Test
        @DisplayName("equality still spans every component, the suppressed companion included")
        void equalityStillSpansEveryComponent() {
            assertThat(detail()).isEqualTo(detail()).hasSameHashCodeAs(detail());
            assertThat(buildDetail(DETAIL_VALUES, null, null, new BigDecimal("1.00")))
                    .as("the companion is omitted from the wire and from the rendering, but it is still "
                            + "part of the value's identity")
                    .isNotEqualTo(buildDetail(DETAIL_VALUES, null, null, new BigDecimal("2.00")));
        }
    }

    @Nested
    @DisplayName("5. Amount constants - the edited mask and the persisted precision")
    class AmountConstants {

        @Test
        @DisplayName("declares the edited mask exactly as the source spells it")
        void declaresTheEditedMask() {
            assertThat(TransactionDto.AMOUNT_EDITED_MASK)
                    .as("the edited field carries a mandatory sign, eight integer digits and two decimals")
                    .isEqualTo("+99999999.99")
                    .hasSize(TransactionDto.AMOUNT_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("resolves the persisted precision from its two declared parts")
        void resolvesThePersistedPrecision() {
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS + TransactionDto.AMOUNT_SCALE)
                    .as("nine integer digits plus two decimals is the eleven-digit transaction money "
                            + "precision of TRAN-AMT PIC S9(09)V99, distinct from the account "
                            + "NUMERIC(12,2)")
                    .isEqualTo(TransactionDto.AMOUNT_PRECISION)
                    .isEqualTo(11);
        }

        @Test
        @DisplayName("declares HALF_EVEN rounding, never HALF_UP")
        void declaresHalfEvenRounding() {
            assertThat(TransactionDto.AMOUNT_ROUNDING_MODE)
                    .isEqualTo(RoundingMode.HALF_EVEN)
                    .isNotEqualTo(RoundingMode.HALF_UP);
        }

        @Test
        @DisplayName("keeps the mask's eight integer digits below the field's nine")
        void keepsTheMaskDigitsBelowTheFieldDigits() {
            assertThat(TransactionDto.AMOUNT_EDITED_MASK.chars().filter(ch -> ch == '9').count())
                    .as("the mask renders eight integer digits and two decimals, ten nines in total, "
                            + "while the numeric field holds nine integer digits. The asymmetry is "
                            + "preserved rather than repaired")
                    .isEqualTo(10L);
            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS)
                    .as("eight rendered integer digits against nine stored ones")
                    .isEqualTo(9);
        }
    }

    @Nested
    @DisplayName("6. Row-list guards - the ten slots COTRN00 declares, and no null slot")
    class RowListGuards {

        @Test
        @DisplayName("refuses more rows than the ten slots the list screen declares, naming neither row")
        void refusesMoreRowsThanTheScreenDeclares() {
            final List<TransactionListRow> tooMany = new ArrayList<>();
            for (int index = 0; index <= TransactionDto.PAGE_SIZE; index++) {
                tooMany.add(new TransactionListRow(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26", "SYNTHETIC",
                        SYNTHETIC_AMOUNT_IMAGE));
            }

            assertThatThrownBy(() -> buildDetail(DETAIL_VALUES, "00000001", tooMany, SYNTHETIC_AMOUNT_VALUE))
                    .as("app/cpy-bms/COTRN00.CPY declares exactly ten row groups, so an eleventh has no "
                            + "screen slot to occupy and is refused rather than silently dropped")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(TransactionDto.PAGE_SIZE + 1))
                    .hasMessageContaining(String.valueOf(TransactionDto.PAGE_SIZE));
        }

        @Test
        @DisplayName("accepts exactly the ten slots the list screen declares")
        void acceptsExactlyTheDeclaredSlotCount() {
            final List<TransactionListRow> exactlyFull = new ArrayList<>();
            for (int index = 0; index < TransactionDto.PAGE_SIZE; index++) {
                exactlyFull.add(new TransactionListRow(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26", "SYNTHETIC",
                        SYNTHETIC_AMOUNT_IMAGE));
            }

            assertThat(buildDetail(DETAIL_VALUES, "00000001", exactlyFull, SYNTHETIC_AMOUNT_VALUE).rows())
                    .as("the bound is an upper bound reached exactly, not exceeded")
                    .hasSize(TransactionDto.PAGE_SIZE);
        }

        @Test
        @DisplayName("refuses a null row slot, naming the offending index rather than the row")
        void refusesANullRowSlot() {
            final List<TransactionListRow> withNull = new ArrayList<>();
            withNull.add(new TransactionListRow(" ", SYNTHETIC_TRANSACTION_ID, "07/24/26", "SYNTHETIC",
                    SYNTHETIC_AMOUNT_IMAGE));
            withNull.add(null);

            assertThatThrownBy(() -> buildDetail(DETAIL_VALUES, "00000001", withNull, SYNTHETIC_AMOUNT_VALUE))
                    .as("an unpopulated screen row is modelled by null components inside a row, never by a "
                            + "null row, so a null slot is a caller defect and is refused")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("index 1");
        }

        @Test
        @DisplayName("an empty row list is a legal list projection rather than a detail projection")
        void anEmptyRowListRemainsAListProjection() {
            assertThat(buildDetail(DETAIL_VALUES, "00000001", List.of(), SYNTHETIC_AMOUNT_VALUE).rows())
                    .as("a list screen with no matching transactions is a normal state, and an empty list "
                            + "stays distinguishable from the absent list a detail projection carries")
                    .isNotNull()
                    .isEmpty();
        }
    }
}
