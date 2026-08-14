/*
 * ******************************************************************
 * Program     : CardDtoApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the two card read projections carried by
 *               com.cardemo.model.dto.CardDto against the frozen
 *               symbolic maps they were translated from. In
 *               particular it pins the two message widths that
 *               DIFFER between those maps in OPPOSITE directions, the
 *               seven-row page geometry whose first row declares one
 *               field fewer than the other six, and the serialized
 *               surface, so that no later edit can share a width
 *               across the two maps, emit a structural helper as
 *               though it were a map field, or emit a selector type
 *               for the row that has none.
 * Source      : app/cpy-bms/COCRDSL.CPY (15 input fields, group
 *               CCRDSLAI) + app/cpy-bms/COCRDLI.CPY (45 input fields,
 *               group CCRDLIAI) + app/cbl/COCRDLIC.cbl:177-178
 *               + app/cbl/COCRDSLC.cbl @ 7756d89
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

import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.CardDto.CardListRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression suite for {@link CardDto}, the read projection shared by the card detail screen and the
 * card list screen.
 *
 * <h2>What it does</h2>
 *
 * <p>It holds the projection to the two frozen symbolic maps it was translated from, and it exists
 * because the two maps agree on most fields and disagree on exactly the ones that are easiest to get
 * wrong. Three properties are pinned that a plausible edit would silently destroy.</p>
 *
 * <p><strong>The two message widths differ, and they differ in opposite directions.</strong> The
 * detail map declares {@code INFOMSGI PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:96} and
 * {@code ERRMSGI PIC X(80)} at {@code :102}. The list map declares {@code INFOMSGI PIC X(45)} at
 * {@code app/cpy-bms/COCRDLI.CPY:282} and {@code ERRMSGI PIC X(78)} at {@code :288}. The list map is
 * therefore the wider of the two on the information line and the <em>narrower</em> of the two on the
 * error line. That crossing is what makes a single shared maximum unsafe: sizing each field to
 * whichever map declares it wider relaxes the contract on both maps at once, admitting a
 * forty-five-character information line the detail screen cannot render and an eighty-character
 * error line the list screen cannot render. The tests below assert the accepted boundary and the
 * first rejected length for each field on each projection, so a regression to one shared constant
 * fails four assertions rather than none.</p>
 *
 * <p><strong>The first row of the list carries one field fewer than the other six.</strong>
 * {@code app/cpy-bms/COCRDLI.CPY} declares {@code CRDSTP2I} through {@code CRDSTP7I} at lines 108,
 * 138, 168, 198, 228 and 258 and declares no {@code CRDSTP1I} anywhere. Row one is consequently a
 * four-field group and rows two through seven are five-field groups, which is also the arithmetic
 * that resolves the map's total to 45: nine preamble fields, four in row one, thirty across the
 * remaining six rows, and two trailer fields.</p>
 *
 * <p><strong>Two members of a row are structure, not content.</strong> The row number and the field
 * count are facts <em>about</em> the map rather than values <em>in</em> it; the map expresses a row's
 * position through the numeric suffix of its generated field names and through nothing else. Both
 * are therefore withheld from the serialized form, and the selector type is omitted entirely rather
 * than emitted as an explicit null for the row that does not declare it.</p>
 *
 * <h2>How to run it</h2>
 *
 * <p>{@code ./mvnw -B -ntp test -Dtest=CardDtoApiContractTest} for this class alone, or
 * {@code ./mvnw -B -ntp test} for the
 * tier. It needs no container, no Spring context, no database and no network, so it runs in the
 * default profile with no configuration at all.</p>
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>Every width, field name, line number and count asserted below is transcribed from the frozen
 * corpus at commit {@code 7756d89} and declared as a constant in this class rather than inlined at a
 * call site, so that the transcription can be audited in one place. Nothing is derived from the
 * class under test; a constant that agreed with the implementation because it was read from the
 * implementation would assert nothing.</p>
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li>A failure in {@code ProjectionDiscriminatedMessageWidths} means the two maps' message widths
 *       have been merged back into a shared constant. The fix is to restore one constant per map per
 *       field, four in total, and to select between them on whether the row list is present.</li>
 *   <li>A failure in {@code RowGeometry} means either the page size or the first row's field count
 *       has drifted. Neither may be adjusted to match code: both are transcribed from the map and
 *       from {@code WS-MAX-SCREEN-LINES} at {@code app/cbl/COCRDLIC.cbl:177-178}.</li>
 *   <li>A failure in {@code SerializedSurface} means a structural helper has become a JSON property,
 *       or the selector type has begun to be emitted as an explicit null. Both are contract
 *       breaches: the first invents a field the map never sent, the second sends a field row one
 *       does not declare.</li>
 *   </ul>
 */
@DisplayName("CardDto - app/cpy-bms/COCRDSL.CPY (15 fields) and app/cpy-bms/COCRDLI.CPY (45 fields)")
final class CardDtoApiContractTest {

    /** Input-field count of the detail map, group {@code CCRDSLAI}, {@code app/cpy-bms/COCRDSL.CPY}. */
    private static final int DETAIL_MAP_FIELDS = 15;

    /** Input-field count of the list map, group {@code CCRDLIAI}, {@code app/cpy-bms/COCRDLI.CPY}. */
    private static final int LIST_MAP_FIELDS = 45;

    /** Rows per page, {@code WS-MAX-SCREEN-LINES} at {@code app/cbl/COCRDLIC.cbl:177-178}. */
    private static final int PAGE_SIZE = 7;

    /** {@code INFOMSGI PIC X(40)} at {@code app/cpy-bms/COCRDSL.CPY:96}. */
    private static final int DETAIL_INFORMATION_WIDTH = 40;

    /** {@code ERRMSGI PIC X(80)} at {@code app/cpy-bms/COCRDSL.CPY:102}. */
    private static final int DETAIL_ERROR_WIDTH = 80;

    /** {@code INFOMSGI PIC X(45)} at {@code app/cpy-bms/COCRDLI.CPY:282}. */
    private static final int LIST_INFORMATION_WIDTH = 45;

    /** {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COCRDLI.CPY:288}. */
    private static final int LIST_ERROR_WIDTH = 78;

    /** {@code CARDSIDI PIC X(16)}, and {@code CRDNUMnI PIC X(16)} on every row group. */
    private static final int CARD_NUMBER_WIDTH = 16;

    /** {@code ACCTSIDI PIC X(11)}, and {@code ACCTNOnI PIC X(11)} on every row group. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code CRDNAMEI PIC X(50)} at {@code app/cpy-bms/COCRDSL.CPY:72}. */
    private static final int CARDHOLDER_NAME_WIDTH = 50;

    /** {@code PAGENOI PIC X(3)} at {@code app/cpy-bms/COCRDLI.CPY:60}. */
    private static final int PAGE_NUMBER_WIDTH = 3;

    /** {@code FKEYSI PIC X(75)} at {@code app/cpy-bms/COCRDSL.CPY:108}, one field and not two. */
    private static final int DETAIL_FUNCTION_KEYS_WIDTH = 75;

    /** The two structural helpers on a row that are facts about the map rather than fields in it. */
    private static final List<String> STRUCTURAL_ROW_ACCESSORS =
            List.of("getRowNumber", "hasSelectorType", "getBmsFieldCount");

    /**
     * Returns a string of the requested length made only of the letter {@code A}.
     *
     * <p>A single repeated letter is used deliberately: it keeps the assertion about length and
     * nothing else, and it contains no digit that could be mistaken for a card number or an account
     * identifier if it ever reached a log during a failure diagnosis.</p>
     *
     * @param length the exact number of characters the returned value carries; zero yields the empty
     *               string, which is the state the maps use for an untouched field
     * @return a value of exactly {@code length} characters
     */
    private static String text(final int length) {
        return "A".repeat(length);
    }

    /**
     * Builds a detail projection whose information and error lines carry the supplied values.
     *
     * <p>Every other field is left absent so a failure names the field under test rather than an
     * unrelated one. The factory under exercise is the detail factory, which passes a null row list
     * and thereby selects the detail widths.</p>
     *
     * @param information the value for {@code INFOMSGI}, or {@code null} for an absent line
     * @param error       the value for {@code ERRMSGI}, or {@code null} for an absent line
     * @return the constructed detail projection
     */
    private static CardDto detailWithMessages(final String information, final String error) {
        return CardDto.detail(null, null, null, null, null, null, null, null, null, null, null, null,
                information, error, null);
    }

    /**
     * Builds a list projection whose information and error lines carry the supplied values.
     *
     * <p>The row list is empty rather than absent. An empty list is the list projection's own
     * representation of a page with no rows, whereas an absent list is what marks a detail
     * projection; the distinction is what selects the widths, so it is made explicit here.</p>
     *
     * @param information the value for {@code INFOMSGI}, or {@code null} for an absent line
     * @param error       the value for {@code ERRMSGI}, or {@code null} for an absent line
     * @return the constructed list projection
     */
    private static CardDto listWithMessages(final String information, final String error) {
        return CardDto.list(null, null, null, null, null, null, null, null, null, List.of(),
                information, error);
    }

    /**
     * Builds one row group, supplying the selector type only for the rows that declare one.
     *
     * @param rowNumber the one-based row position, between one and the page size
     * @return a row group whose members are all populated to their declared widths
     */
    private static CardListRow rowAt(final int rowNumber) {
        final String selectorType = rowNumber == 1 ? null : "C";
        return new CardListRow(rowNumber, "S", selectorType, text(ACCOUNT_ID_WIDTH),
                text(CARD_NUMBER_WIDTH), "Y");
    }

    /**
     * Builds a full page of rows, one through the page size, in positional order.
     *
     * @return an ordered list of exactly {@link #PAGE_SIZE} rows
     */
    private static List<CardListRow> fullPage() {
        final List<CardListRow> rows = new ArrayList<>(PAGE_SIZE);
        for (int rowNumber = 1; rowNumber <= PAGE_SIZE; rowNumber++) {
            rows.add(rowAt(rowNumber));
        }
        return rows;
    }

    /**
     * Returns the declared instance field names of a type, in declaration order.
     *
     * @param type the type to inspect
     * @return the names of every declared non-static field, in the order the compiler recorded them
     */
    private static List<String> instanceFieldNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        for (final Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /**
     * Returns the declared method names of a type.
     *
     * @param type the type to inspect
     * @return the names of every declared method, without duplicates removed
     */
    private static List<String> declaredMethodNames(final Class<?> type) {
        final List<String> names = new ArrayList<>();
        for (final Method method : type.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }

    /**
     * Supplies the four message-width cases: each of two projections against each of two fields.
     *
     * @return one argument triple per case, carrying the projection name, the field name and the
     *         declared width of that field on that projection
     */
    private static Stream<Arguments> messageWidthCases() {
        return Stream.of(
                Arguments.of("detail", "informationMessage", DETAIL_INFORMATION_WIDTH),
                Arguments.of("detail", "errorMessage", DETAIL_ERROR_WIDTH),
                Arguments.of("list", "informationMessage", LIST_INFORMATION_WIDTH),
                Arguments.of("list", "errorMessage", LIST_ERROR_WIDTH));
    }

    /**
     * Constructs a projection of the named kind carrying the named field at the given length.
     *
     * @param projection either {@code detail} or {@code list}
     * @param fieldName  either {@code informationMessage} or {@code errorMessage}
     * @param length     the number of characters to place in that field
     * @return the constructed projection
     */
    private static CardDto projectionWith(final String projection, final String fieldName,
            final int length) {

        final String value = text(length);
        final boolean information = "informationMessage".equals(fieldName);
        if ("detail".equals(projection)) {
            return information ? detailWithMessages(value, null) : detailWithMessages(null, value);
        }
        return information ? listWithMessages(value, null) : listWithMessages(null, value);
    }

    @Nested
    @DisplayName("Field census: the two maps are 15 and 45 fields, and neither is the other")
    class FieldCensus {

        @Test
        @DisplayName("the declared counts are the maps' own counts, 15 and 45, not one shared count")
        void declaredCountsAreTheMapsOwnCounts() {
            assertThat(CardDto.DETAIL_FIELD_COUNT).isEqualTo(DETAIL_MAP_FIELDS);
            assertThat(CardDto.LIST_FIELD_COUNT).isEqualTo(LIST_MAP_FIELDS);
            assertThat(CardDto.DETAIL_FIELD_COUNT).isNotEqualTo(CardDto.LIST_FIELD_COUNT);
        }

        @Test
        @DisplayName("the list map's 45 fields resolve as 9 preamble, 4 in row one, 30 in rows two "
                + "to seven, and 2 trailer")
        void theListCountResolvesArithmetically() {
            final int preamble = 9;
            final int firstRow = CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE;
            final int remainingRows =
                    (PAGE_SIZE - 1) * CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE;
            final int trailer = 2;

            assertThat(preamble + firstRow + remainingRows + trailer).isEqualTo(LIST_MAP_FIELDS);
        }

        @Test
        @DisplayName("the page size is seven, from WS-MAX-SCREEN-LINES and not from the row count of "
                + "any other list screen")
        void thePageSizeIsSeven() {
            assertThat(CardDto.CARD_LIST_PAGE_SIZE).isEqualTo(PAGE_SIZE);
            assertThat(CardDto.CARD_LIST_PAGE_SIZE).isNotEqualTo(10);
        }

        @Test
        @DisplayName("the carrier holds one field per distinct map field and no invented member")
        void theCarrierHoldsNoInventedMember() {
            assertThat(instanceFieldNames(CardDto.class)).containsExactly(
                    "transactionName", "title01", "currentDate", "programName", "title02",
                    "currentTime", "pageNumber", "accountId", "cardNumber", "cardholderName",
                    "cardStatusCode", "expiryMonth", "expiryYear", "rows", "informationMessage",
                    "errorMessage", "functionKeys");
        }

        @Test
        @DisplayName("the detail map declares no expiry day, so no expiry day member exists")
        void theDetailMapDeclaresNoExpiryDay() {
            assertThat(instanceFieldNames(CardDto.class))
                    .contains("expiryMonth", "expiryYear")
                    .doesNotContain("expiryDay");
            assertThat(declaredMethodNames(CardDto.class)).doesNotContain("getExpiryDay");
        }

        @Test
        @DisplayName("the detail function-key legend is one 75-character field, not the update map's "
                + "two fields of 21 and 18")
        void theFunctionKeyLegendIsOneField() {
            assertThat(instanceFieldNames(CardDto.class))
                    .contains("functionKeys")
                    .doesNotContain("functionKeysContinued");
            assertThat(detailAcceptsFunctionKeysOfLength(DETAIL_FUNCTION_KEYS_WIDTH)).isTrue();
            assertThat(detailAcceptsFunctionKeysOfLength(DETAIL_FUNCTION_KEYS_WIDTH + 1)).isFalse();
        }

        private boolean detailAcceptsFunctionKeysOfLength(final int length) {
            try {
                CardDto.detail(null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, text(length));
                return true;
            } catch (final IllegalArgumentException expected) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("Message widths are chosen per projection, because the two maps cross over")
    class ProjectionDiscriminatedMessageWidths {

        @ParameterizedTest(name = "{0} projection accepts {1} at its declared width of {2}")
        @MethodSource("com.cardemo.unit.model.CardDtoApiContractTest#messageWidthCases")
        @DisplayName("each projection accepts each message field exactly at the width its own map "
                + "declares")
        void eachProjectionAcceptsItsOwnDeclaredWidth(final String projection,
                final String fieldName, final int width) {

            assertThat(projectionWith(projection, fieldName, width)).isNotNull();
        }

        @ParameterizedTest(name = "{0} projection refuses {1} at {2} plus one")
        @MethodSource("com.cardemo.unit.model.CardDtoApiContractTest#messageWidthCases")
        @DisplayName("each projection refuses each message field one character beyond its own width")
        void eachProjectionRefusesOneBeyondItsOwnWidth(final String projection,
                final String fieldName, final int width) {

            assertThatThrownBy(() -> projectionWith(projection, fieldName, width + 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(fieldName)
                    .hasMessageContaining("PIC X(" + width + ")");
        }

        @Test
        @DisplayName("the crossing is real: the list map is wider on the information line and "
                + "narrower on the error line")
        void theTwoMapsCrossOver() {
            assertThat(LIST_INFORMATION_WIDTH).isGreaterThan(DETAIL_INFORMATION_WIDTH);
            assertThat(LIST_ERROR_WIDTH).isLessThan(DETAIL_ERROR_WIDTH);
        }

        @Test
        @DisplayName("a 45-character information line is a list value and is refused by the detail "
                + "projection")
        void theDetailProjectionRefusesTheListInformationWidth() {
            assertThat(listWithMessages(text(LIST_INFORMATION_WIDTH), null)).isNotNull();
            assertThatThrownBy(() -> detailWithMessages(text(LIST_INFORMATION_WIDTH), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("informationMessage")
                    .hasMessageContaining("PIC X(" + DETAIL_INFORMATION_WIDTH + ")");
        }

        @Test
        @DisplayName("an 80-character error line is a detail value and is refused by the list "
                + "projection")
        void theListProjectionRefusesTheDetailErrorWidth() {
            assertThat(detailWithMessages(null, text(DETAIL_ERROR_WIDTH))).isNotNull();
            assertThatThrownBy(() -> listWithMessages(null, text(DETAIL_ERROR_WIDTH)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("errorMessage")
                    .hasMessageContaining("PIC X(" + LIST_ERROR_WIDTH + ")");
        }

        @Test
        @DisplayName("no single width would satisfy both maps, which is why one shared constant per "
                + "field is wrong in principle and not merely lax")
        void noSharedWidthSatisfiesBothMaps() {
            final int sharedInformation = Math.max(DETAIL_INFORMATION_WIDTH, LIST_INFORMATION_WIDTH);
            final int sharedError = Math.max(DETAIL_ERROR_WIDTH, LIST_ERROR_WIDTH);

            assertThat(sharedInformation).isGreaterThan(DETAIL_INFORMATION_WIDTH);
            assertThat(sharedError).isGreaterThan(LIST_ERROR_WIDTH);
        }

        @Test
        @DisplayName("the presence of the row list, not the caller's intent, selects the widths")
        void thePresenceOfTheRowListSelectsTheWidths() {
            assertThat(CardDto.list(null, null, null, null, null, null, null, null, null,
                    List.of(), text(LIST_INFORMATION_WIDTH), null)).isNotNull();
            assertThatThrownBy(() -> CardDto.list(null, null, null, null, null, null, null, null,
                    null, null, text(LIST_INFORMATION_WIDTH), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PIC X(" + DETAIL_INFORMATION_WIDTH + ")");
        }

        @Test
        @DisplayName("an absent message line is not a width violation on either projection")
        void anAbsentMessageLineIsNotAViolation() {
            assertThat(detailWithMessages(null, null).getInformationMessage()).isNull();
            assertThat(listWithMessages(null, null).getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("an empty message line is carried as the empty string on both projections and "
                + "is never coerced to absent")
        void anEmptyMessageLineStaysEmpty() {
            assertThat(detailWithMessages("", "").getInformationMessage()).isEmpty();
            assertThat(detailWithMessages("", "").getErrorMessage()).isEmpty();
            assertThat(listWithMessages("", "").getInformationMessage()).isEmpty();
            assertThat(listWithMessages("", "").getErrorMessage()).isEmpty();
        }

        @Test
        @DisplayName("the refusal names the field and its picture clause but never the value")
        void theRefusalNeverQuotesTheValue() {
            final String secret = "9".repeat(LIST_INFORMATION_WIDTH + 1);

            assertThatThrownBy(() -> listWithMessages(secret, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("informationMessage")
                    .hasMessageContaining("PIC X(45)")
                    .hasMessageNotContaining(secret);
        }
    }

    @Nested
    @DisplayName("Row geometry: seven rows, of which the first declares one field fewer")
    class RowGeometry {

        @Test
        @DisplayName("row one carries four fields and rows two to seven carry five")
        void rowOneCarriesFourFields() {
            assertThat(CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE).isEqualTo(4);
            assertThat(CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE).isEqualTo(5);
            assertThat(CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE)
                    .isEqualTo(CardDto.FIRST_ROW_NUMBER);
        }

        @Test
        @DisplayName("row one reports four fields and declares no selector type")
        void rowOneReportsFourFields() {
            final CardListRow first = rowAt(1);

            assertThat(first.getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE);
            assertThat(first.hasSelectorType()).isFalse();
            assertThat(first.getSelectorType()).isNull();
        }

        @ParameterizedTest(name = "row {0} reports five fields and carries a selector type")
        @ValueSource(ints = {2, 3, 4, 5, 6, 7})
        @DisplayName("every row from two to seven reports five fields and carries a selector type")
        void everyLaterRowReportsFiveFields(final int rowNumber) {
            final CardListRow row = rowAt(rowNumber);

            assertThat(row.getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE);
            assertThat(row.hasSelectorType()).isTrue();
            assertThat(row.getSelectorType()).isEqualTo("C");
        }

        @Test
        @DisplayName("row one refuses a selector type, naming the field the map does not declare")
        void rowOneRefusesASelectorType() {
            assertThatThrownBy(() -> new CardListRow(1, "S", "C", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selectorType")
                    .hasMessageContaining("CRDSTP1I");
        }

        @Test
        @DisplayName("row one still accepts a null selector type, which is how it is expressed")
        void rowOneAcceptsANullSelectorType() {
            assertThat(new CardListRow(1, "S", null, null, null, null).getSelectorType()).isNull();
        }

        @ParameterizedTest(name = "row number {0} is outside the seven the map declares")
        @ValueSource(ints = {0, -1, 8, 99})
        @DisplayName("a row number outside one through seven is refused, because the map declares "
                + "exactly seven row groups")
        void aRowNumberOutsideTheMapIsRefused(final int rowNumber) {
            assertThatThrownBy(() -> new CardListRow(rowNumber, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rowNumber");
        }

        @Test
        @DisplayName("a page of seven rows is accepted and an eighth row is refused")
        void aPageHoldsAtMostSevenRows() {
            assertThat(CardDto.list(null, null, null, null, null, null, null, null, null,
                    fullPage(), null, null).getRows()).hasSize(PAGE_SIZE);

            final List<CardListRow> overflowing = new ArrayList<>(fullPage());
            overflowing.add(rowAt(PAGE_SIZE));
            assertThatThrownBy(() -> CardDto.list(null, null, null, null, null, null, null, null,
                    null, overflowing, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rows");
        }

        @Test
        @DisplayName("rows must be positional, so a page whose rows are out of order is refused")
        void rowsMustBePositional() {
            final List<CardListRow> reversed = new ArrayList<>(fullPage());
            java.util.Collections.reverse(reversed);

            assertThatThrownBy(() -> CardDto.list(null, null, null, null, null, null, null, null,
                    null, reversed, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positional");
        }

        @Test
        @DisplayName("every row member is bounded by the width its own row group declares")
        void everyRowMemberIsBounded() {
            assertThatThrownBy(() -> new CardListRow(2, null, null,
                    text(ACCOUNT_ID_WIDTH + 1), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("accountNumber")
                    .hasMessageContaining("PIC X(" + ACCOUNT_ID_WIDTH + ")");
            assertThatThrownBy(() -> new CardListRow(2, null, null, null,
                    text(CARD_NUMBER_WIDTH + 1), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cardNumber")
                    .hasMessageContaining("PIC X(" + CARD_NUMBER_WIDTH + ")");
            assertThatThrownBy(() -> new CardListRow(2, "SS", null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selectionFlag");
            assertThatThrownBy(() -> new CardListRow(2, null, "CC", null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selectorType");
            assertThatThrownBy(() -> new CardListRow(2, null, null, null, null, "YY"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("statusCode");
        }

        @Test
        @DisplayName("the returned page is unmodifiable, so a caller cannot lengthen it past seven "
                + "after construction")
        void theReturnedPageIsUnmodifiable() {
            final List<CardListRow> rows = CardDto.list(null, null, null, null, null, null, null,
                    null, null, fullPage(), null, null).getRows();

            assertThatThrownBy(() -> rows.add(rowAt(1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("mutating the caller's list after construction does not change the projection")
        void theProjectionCopiesTheCallersList() {
            final List<CardListRow> caller = new ArrayList<>(List.of(rowAt(1), rowAt(2)));
            final CardDto projection = CardDto.list(null, null, null, null, null, null, null, null,
                    null, caller, null, null);
            caller.clear();

            assertThat(projection.getRows()).hasSize(2);
        }

        @Test
        @DisplayName("the detail projection carries no row list at all, which is what marks it")
        void theDetailProjectionCarriesNoRowList() {
            assertThat(detailWithMessages(null, null).getRows()).isNull();
        }
    }

    @Nested
    @DisplayName("Serialized surface: only map fields are emitted, and only where the map declares "
            + "them")
    class SerializedSurface {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("the row number is not a JSON property, because the map expresses position "
                + "through the field-name suffix and not through a field")
        void theRowNumberIsNotAJsonProperty() throws Exception {
            final String json = mapper.writeValueAsString(rowAt(3));

            assertThat(json).doesNotContain("rowNumber");
        }

        @Test
        @DisplayName("the field count is not a JSON property, being a statement about the contract "
                + "rather than a value in it")
        void theFieldCountIsNotAJsonProperty() throws Exception {
            final String json = mapper.writeValueAsString(rowAt(3));

            assertThat(json).doesNotContain("bmsFieldCount");
        }

        @Test
        @DisplayName("the selector-type predicate is not a JSON property, the JSON already "
                + "expressing that fact structurally")
        void theSelectorTypePredicateIsNotAJsonProperty() throws Exception {
            final String json = mapper.writeValueAsString(rowAt(3));

            assertThat(json).doesNotContain("selectorTypePresent").doesNotContain("\"present\"");
        }

        @Test
        @DisplayName("row one omits the selector-type key entirely rather than emitting it as null, "
                + "because CRDSTP1I does not exist")
        void rowOneOmitsTheSelectorTypeKey() throws Exception {
            final String json = mapper.writeValueAsString(rowAt(1));

            assertThat(json).doesNotContain("selectorType");
        }

        @Test
        @DisplayName("rows two to seven do emit the selector-type key, because CRDSTP2I through "
                + "CRDSTP7I do exist")
        void laterRowsEmitTheSelectorTypeKey() throws Exception {
            for (int rowNumber = 2; rowNumber <= PAGE_SIZE; rowNumber++) {
                assertThat(mapper.writeValueAsString(rowAt(rowNumber)))
                        .as("row %d", rowNumber)
                        .contains("\"selectorType\":\"C\"");
            }
        }

        @Test
        @DisplayName("suppression is scoped to the selector type: a later row whose selector type is "
                + "absent still omits only that key, while its other unpopulated members are emitted "
                + "as null")
        void suppressionIsScopedToTheSelectorType() throws Exception {
            final String json = mapper.writeValueAsString(
                    new CardListRow(2, null, null, null, null, null));

            assertThat(json)
                    .doesNotContain("selectorType")
                    .contains("\"selectionFlag\":null")
                    .contains("\"accountNumber\":null")
                    .contains("\"cardNumber\":null")
                    .contains("\"statusCode\":null");
        }

        @Test
        @DisplayName("the emitted row properties are exactly the four or five the map declares")
        void theEmittedRowPropertiesAreTheMapsOwn() throws Exception {
            assertThat(mapper.readTree(mapper.writeValueAsString(rowAt(1))).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("selectionFlag", "accountNumber", "cardNumber",
                            "statusCode");
            assertThat(mapper.readTree(mapper.writeValueAsString(rowAt(2))).fieldNames())
                    .toIterable()
                    .containsExactlyInAnyOrder("selectionFlag", "selectorType", "accountNumber",
                            "cardNumber", "statusCode");
        }

        @Test
        @DisplayName("the three structural accessors are still available to Java callers; they are "
                + "withheld from JSON, not deleted")
        void theStructuralAccessorsRemainAvailableToJava() {
            assertThat(declaredMethodNames(CardListRow.class))
                    .containsAll(STRUCTURAL_ROW_ACCESSORS);
            assertThat(rowAt(4).getRowNumber()).isEqualTo(4);
        }

        @Test
        @DisplayName("the projection itself emits every map field it carries, including the absent "
                + "ones, so a client can distinguish absent from unpopulated")
        void theProjectionEmitsItsOwnFields() throws Exception {
            final String json = mapper.writeValueAsString(detailWithMessages("info", "error"));

            assertThat(json)
                    .contains("\"informationMessage\":\"info\"")
                    .contains("\"errorMessage\":\"error\"")
                    .contains("\"cardNumber\":null")
                    .contains("\"rows\":null");
        }
    }

    @Nested
    @DisplayName("Immutability, type discipline and rendering safety")
    class Immutability {

        @Test
        @DisplayName("every instance field of the projection is final, so nothing can change after "
                + "the widths have been checked")
        void everyProjectionFieldIsFinal() {
            for (final Field field : CardDto.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("field %s", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("every instance field of a row is final for the same reason")
        void everyRowFieldIsFinal() {
            for (final Field field : CardListRow.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    assertThat(Modifier.isFinal(field.getModifiers()))
                            .as("field %s", field.getName())
                            .isTrue();
                }
            }
        }

        @Test
        @DisplayName("neither type declares a mutator, so a validated projection stays validated")
        void neitherTypeDeclaresAMutator() {
            assertThat(declaredMethodNames(CardDto.class)).noneMatch(name -> name.startsWith("set"));
            assertThat(declaredMethodNames(CardListRow.class))
                    .noneMatch(name -> name.startsWith("set"));
        }

        @Test
        @DisplayName("both types are final, so no subclass can widen a width or re-introduce a "
                + "mutator")
        void bothTypesAreFinal() {
            assertThat(Modifier.isFinal(CardDto.class.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(CardListRow.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("neither type is serializable, so neither can take part in Java native "
                + "serialization while holding a card number")
        void neitherTypeIsSerializable() {
            assertThat(Serializable.class.isAssignableFrom(CardDto.class)).isFalse();
            assertThat(Serializable.class.isAssignableFrom(CardListRow.class)).isFalse();
        }

        @Test
        @DisplayName("every textual member is a String, so a leading zero and a blank both survive")
        void everyTextualMemberIsAString() {
            final List<Class<?>> rowTypes = new ArrayList<>();
            for (final Field field : CardListRow.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    rowTypes.add(field.getType());
                }
            }

            assertThat(rowTypes)
                    .containsExactly(int.class, String.class, String.class, String.class,
                            String.class, String.class);
        }

        @Test
        @DisplayName("no rendering of either type reproduces a card number, because neither "
                + "overrides toString and the inherited one emits no field value")
        void noRenderingReproducesACardNumber() {
            final String pan = "4111111111111111";
            final CardDto detail = CardDto.detail(null, null, null, null, null, null, null, pan,
                    null, null, null, null, null, null, null);
            final CardListRow row = new CardListRow(2, "S", "C", null, pan, "Y");

            assertThat(declaredMethodNames(CardDto.class)).doesNotContain("toString");
            assertThat(declaredMethodNames(CardListRow.class)).doesNotContain("toString");
            assertThat(detail.toString()).doesNotContain(pan);
            assertThat(row.toString()).doesNotContain(pan);
        }
    }

    @Nested
    @DisplayName("Hostile input: refused when over-wide, carried verbatim when within width")
    class HostileInput {

        @Test
        @DisplayName("a value at exactly its declared width is carried, character for character")
        void aBoundaryValueIsCarriedVerbatim() {
            final String name = text(CARDHOLDER_NAME_WIDTH);
            final CardDto detail = CardDto.detail(null, null, null, null, null, null, null, null,
                    name, null, null, null, null, null, null);

            assertThat(detail.getCardholderName()).isEqualTo(name).hasSize(CARDHOLDER_NAME_WIDTH);
        }

        @Test
        @DisplayName("nothing is trimmed, padded or case-folded on the way in")
        void nothingIsNormalisedOnTheWayIn() {
            final String padded = "  mIxEd  ";
            final CardDto detail = CardDto.detail(null, null, null, null, null, null, null, null,
                    padded, null, null, null, null, null, null);

            assertThat(detail.getCardholderName()).isEqualTo(padded);
        }

        @Test
        @DisplayName("an out-of-domain single-character status code is carried rather than rejected, "
                + "so the service emits the source's own message")
        void anOutOfDomainStatusCodeIsCarried() {
            final CardDto detail = CardDto.detail(null, null, null, null, null, null, null, null,
                    null, "Z", null, null, null, null, null);

            assertThat(detail.getCardStatusCode()).isEqualTo("Z");
        }

        @Test
        @DisplayName("absence, emptiness and a low-values marker remain three distinct states")
        void absenceEmptinessAndMarkingRemainDistinct() {
            final String lowValues = "\u0000";

            assertThat(CardDto.detail(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null).getCardStatusCode()).isNull();
            assertThat(CardDto.detail(null, null, null, null, null, null, null, null, null, "",
                    null, null, null, null, null).getCardStatusCode()).isEmpty();
            assertThat(CardDto.detail(null, null, null, null, null, null, null, null, null,
                    lowValues, null, null, null, null, null).getCardStatusCode())
                    .isEqualTo(lowValues);
        }

        @Test
        @DisplayName("the page number is three characters on the list map, not the eight of the user "
                + "list map")
        void thePageNumberIsThreeCharacters() {
            assertThat(CardDto.list(null, null, null, null, null, null, text(PAGE_NUMBER_WIDTH),
                    null, null, List.of(), null, null)).isNotNull();
            assertThatThrownBy(() -> CardDto.list(null, null, null, null, null, null,
                    text(PAGE_NUMBER_WIDTH + 1), null, null, List.of(), null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("pageNumber")
                    .hasMessageContaining("PIC X(3)");
        }

        @ParameterizedTest(name = "{0} is bounded on the detail projection")
        @ValueSource(strings = {"accountId", "cardNumber", "cardholderName"})
        @DisplayName("every protected field on the detail projection is bounded and never echoed in "
                + "its own refusal")
        void everyProtectedFieldIsBoundedAndNeverEchoed(final String fieldName) {
            final int width = switch (fieldName) {
                case "accountId" -> ACCOUNT_ID_WIDTH;
                case "cardNumber" -> CARD_NUMBER_WIDTH;
                default -> CARDHOLDER_NAME_WIDTH;
            };
            final String overWide = "7".repeat(width + 1);
            final String account = "accountId".equals(fieldName) ? overWide : null;
            final String card = "cardNumber".equals(fieldName) ? overWide : null;
            final String name = "cardholderName".equals(fieldName) ? overWide : null;

            assertThatThrownBy(() -> CardDto.detail(null, null, null, null, null, null, account,
                    card, name, null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(fieldName)
                    .hasMessageContaining("PIC X(" + width + ")")
                    .hasMessageNotContaining(overWide);
        }

        @Test
        @DisplayName("a null element inside the row list is refused rather than carried")
        void aNullRowElementIsRefused() {
            final List<CardListRow> withNull = new ArrayList<>(Arrays.asList(rowAt(1), null));

            assertThatThrownBy(() -> CardDto.list(null, null, null, null, null, null, null, null,
                    null, withNull, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }
    }
}
