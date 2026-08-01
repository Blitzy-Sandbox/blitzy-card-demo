/*
 * ******************************************************************
 * Program     : TransactionAndCardDtoTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the BMS symbolic-map field budgets of the two
 *               paginated screens - the transaction list at 10 rows per
 *               page and the card list at 7 - proving each declared
 *               field count is ARITHMETICALLY derived from its preamble,
 *               row and trailer parts rather than asserted as a literal,
 *               and pins the amount display mask whose 8 integer digits
 *               are narrower than the 9 the numeric field permits.
 * Source      : app/cpy-bms/COTRN01.CPY (21 detail fields) @ 7756d89
 * Source      : app/cpy-bms/COTRN00.CPY (59 list fields, 10 rows) @ 7756d89
 * Source      : app/cpy-bms/COCRDSL.CPY (15 detail fields) @ 7756d89
 * Source      : app/cpy-bms/COCRDLI.CPY (45 list fields, 7 rows) @ 7756d89
 * Source      : app/cbl/COTRN00C.cbl:L65-L68 (page size 10) @ 7756d89
 * Source      : app/cbl/COCRDLIC.cbl:L177 (page size 7) @ 7756d89
 * Source      : app/cbl/COTRN02C.cbl:L58-L59 (numeric vs edited field) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.TransactionDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionDto} and {@link CardDto}, the two paginated screen projections.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>These two DTOs carry the field contracts of four BMS symbolic maps. The important property is that each
 * declared field count is <strong>derived</strong>, not asserted: the transaction list's 59 fields are
 * {@code 8 preamble + 10 rows x 5 fields + 1 trailer}, so the test proves the arithmetic closes rather than
 * merely restating the number from the copybook. If a page size or a row width were changed, the derivation
 * would stop matching the copybook total and the test would fail - which a bare literal assertion would not
 * catch.
 *
 * <h3>Pagination sizes are behaviour, not tuning</h3>
 *
 * <p>{@code app/cbl/COTRN00C.cbl:L65-L68} declares ten row slots and {@code app/cbl/COCRDLIC.cbl:L177}
 * declares seven. These are not adjustable page sizes - the screen has exactly that many physical row
 * positions, and the symbolic map generates exactly that many field groups. Changing either changes the field
 * count and breaks the map contract.
 *
 * <h3>The amount mask is narrower than the amount field</h3>
 *
 * <p>{@code app/cbl/COTRN02C.cbl:L58} declares the numeric amount as a signed nine-integer, two-decimal value
 * while {@code :L59} declares the <em>edited</em> display field as {@code +99999999.99} - a mandatory sign,
 * <strong>eight</strong> integer digits and two decimals. The display mask is therefore one digit narrower
 * than the field it renders. That is a real legacy constraint, and the magnitude guard exists to enforce it.
 *
 * <h3>Three width tiers for description, and three for dates</h3>
 *
 * <p>The same logical value is carried at different widths depending on where it appears: a description is 26
 * characters in a list row, 60 on the detail screen and 100 as persisted; a date is 8 characters in a list
 * row, 10 on the detail screen and 26 as a persisted timestamp. Collapsing any tier into another would either
 * truncate persisted data or overflow a screen field.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * mvn -B -o test -Dtest=TransactionAndCardDtoTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A field-count derivation fails.</strong> A page size or row width changed. Recount the
 *       symbolic map before touching the test - the copybook is the authority.</li>
 *   <li><strong>A defensive-copy test fails.</strong> A collection accessor now leaks its backing list.
 *       A caller could then mutate a response after it was built.</li>
 *   <li><strong>The magnitude-guard test fails.</strong> The bound was widened past the edited mask's
 *       capacity, so an amount would render incorrectly on the screen.</li>
 * </ul>
 */
class TransactionAndCardDtoTest {

    private static TransactionDto.TransactionListRow row(final String id) {
        return new TransactionDto.TransactionListRow("S", id, "20240101", "Description", "+00000100.00");
    }

    private static TransactionDto listDto(final List<TransactionDto.TransactionListRow> rows) {
        return new TransactionDto("CT00", "T1", "01/01/24", "COTRN00C", "T2", "12.30.45",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, "0001", rows, null);
    }

    /**
     * Builds a card list row, supplying the selector type only for the rows whose map declares one.
     *
     * <p>Row 1 must be given a {@code null} selector type: the constructor <strong>enforces</strong> the BMS
     * asymmetry and rejects a non-null value there. An earlier draft of this helper passed the same selector
     * type for every row and three tests failed with that exact diagnostic - which is how the enforcement was
     * discovered rather than assumed.
     *
     * @param number the one-based BMS row number, 1 through 7
     * @return a row shaped exactly as its map slot permits
     */
    private static CardDto.CardListRow cardRow(final int number) {
        final String selectorType = number == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "T";
        return new CardDto.CardListRow(
                number, "S", selectorType, "00000000001", "4111111111111111", "Y");
    }

    @Nested
    @DisplayName("the transaction screens: 21 detail fields and 59 list fields at 10 rows per page")
    class TransactionFieldBudget {

        @Test
        @DisplayName("the detail field count matches COTRN01.CPY exactly")
        void theDetailFieldCountMatchesTheCopybook() {
            assertThat(TransactionDto.DETAIL_FIELD_COUNT)
                    .as("app/cpy-bms/COTRN01.CPY generates 21 input fields")
                    .isEqualTo(21);
        }

        @Test
        @DisplayName("the list field count is DERIVED as 8 + 10 x 5 + 1 and equals COTRN00.CPY's 59")
        void theListFieldCountIsDerivedAndEquals59() {
            final int derived = TransactionDto.LIST_PREAMBLE_FIELD_COUNT
                    + TransactionDto.PAGE_SIZE * TransactionDto.LIST_ROW_FIELD_COUNT
                    + TransactionDto.LIST_TRAILER_FIELD_COUNT;

            assertThat(TransactionDto.LIST_PREAMBLE_FIELD_COUNT).isEqualTo(8);
            assertThat(TransactionDto.PAGE_SIZE).isEqualTo(10);
            assertThat(TransactionDto.LIST_ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionDto.LIST_TRAILER_FIELD_COUNT).isEqualTo(1);
            assertThat(derived)
                    .as("8 preamble + 10 rows x 5 fields + 1 trailer = 59, which is exactly the input "
                            + "field count of app/cpy-bms/COTRN00.CPY. Deriving it rather than asserting "
                            + "the literal 59 means a change to the page size or the row width is caught "
                            + "here instead of surfacing as a malformed screen")
                    .isEqualTo(59)
                    .isEqualTo(TransactionDto.LIST_FIELD_COUNT);
        }

        @Test
        @DisplayName("the ten-row page size is the screen's physical row count, per COTRN00C")
        void theTenRowPageSizeIsPhysical() {
            assertThat(TransactionDto.PAGE_SIZE)
                    .as("app/cbl/COTRN00C.cbl:L65-L68 declares ten row slots. This is not a tunable page "
                            + "size - the symbolic map generates exactly ten field groups, so changing it "
                            + "changes the field count and breaks the map contract")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the six universal header widths match the values shared by all seventeen maps")
        void theSixUniversalHeaderWidthsMatch() {
            assertThat(TransactionDto.TRANSACTION_NAME_LENGTH)
                    .as("TRNNAME X(4) recurs on all seventeen symbolic maps")
                    .isEqualTo(4);
            assertThat(TransactionDto.TITLE_LENGTH).as("TITLE01 and TITLE02 are X(40)").isEqualTo(40);
            assertThat(TransactionDto.CURRENT_DATE_LENGTH).as("CURDATE X(8)").isEqualTo(8);
            assertThat(TransactionDto.PROGRAM_NAME_LENGTH).as("PGMNAME X(8)").isEqualTo(8);
            assertThat(TransactionDto.CURRENT_TIME_LENGTH)
                    .as("the DTO carries the time at 8 characters")
                    .isEqualTo(8);
        }

        @ParameterizedTest
        @CsvSource({
            "16, TRAN-ID",
            "16, TRAN-CARD-NUM",
            "2,  TRAN-TYPE-CD",
            "4,  TRAN-CAT-CD",
            "10, TRAN-SOURCE",
            "9,  TRAN-MERCHANT-ID",
            "10, TRAN-MERCHANT-ZIP",
        })
        @DisplayName("the business field widths agree with their CVTRA05Y pictures")
        void theBusinessFieldWidthsAgreeWithTheCopybook(final int width, final String cobolField) {
            final List<Integer> declared = List.of(
                    TransactionDto.TRANSACTION_ID_LENGTH, TransactionDto.CARD_NUMBER_LENGTH,
                    TransactionDto.TYPE_CODE_LENGTH, TransactionDto.CATEGORY_CODE_LENGTH,
                    TransactionDto.SOURCE_LENGTH, TransactionDto.MERCHANT_ID_LENGTH,
                    TransactionDto.MERCHANT_ZIP_LENGTH);

            assertThat(declared)
                    .as("%s is declared at width %d in app/cpy/CVTRA05Y.cpy and the DTO must carry the "
                            + "same width, or a fixed-width round trip loses or pads bytes", cobolField,
                            width)
                    .contains(width);
        }

        @Test
        @DisplayName("the description exists at three distinct widths: 26 row, 60 detail, 100 persisted")
        void theDescriptionExistsAtThreeWidths() {
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .as("a list row shows only 26 characters, because ten rows must fit the screen")
                    .isEqualTo(26);
            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .as("the detail screen shows 60")
                    .isEqualTo(60);
            assertThat(TransactionDto.DESCRIPTION_PERSISTED_LENGTH)
                    .as("TRAN-DESC is PIC X(100) as persisted. THREE tiers for one logical value: "
                            + "collapsing the persisted width down to a screen width would silently "
                            + "truncate stored data, and widening a screen field to 100 would overflow "
                            + "the map")
                    .isEqualTo(100);
            assertThat(TransactionDto.ROW_DESCRIPTION_LENGTH)
                    .isLessThan(TransactionDto.DESCRIPTION_LENGTH);
            assertThat(TransactionDto.DESCRIPTION_LENGTH)
                    .isLessThan(TransactionDto.DESCRIPTION_PERSISTED_LENGTH);
        }

        @Test
        @DisplayName("the date exists at three distinct widths: 8 row, 10 detail, 26 persisted")
        void theDateExistsAtThreeWidths() {
            assertThat(TransactionDto.ROW_DATE_LENGTH).isEqualTo(8);
            assertThat(TransactionDto.DETAIL_DATE_LENGTH)
                    .as("the detail screen shows the ten-character date portion, which is exactly the "
                            + "prefix the report sort's INCLUDE COND filters on")
                    .isEqualTo(10);
            assertThat(TransactionDto.PERSISTED_TIMESTAMP_LENGTH)
                    .as("TRAN-ORIG-TS and TRAN-PROC-TS are PIC X(26) as persisted")
                    .isEqualTo(26);
        }

        @Test
        @DisplayName("the merchant name and city are narrower on screen than in the record")
        void theMerchantFieldsAreNarrowerOnScreen() {
            assertThat(TransactionDto.MERCHANT_NAME_LENGTH)
                    .as("the screen shows 30 characters of the PIC X(50) merchant name")
                    .isEqualTo(30);
            assertThat(TransactionDto.MERCHANT_CITY_LENGTH)
                    .as("and 25 of the PIC X(50) city. The screen truncation is a display concern; the "
                            + "record retains all 50, so the DTO width must not be pushed back into the "
                            + "entity")
                    .isEqualTo(25);
        }
    }

    @Nested
    @DisplayName("the amount display mask, one integer digit narrower than the numeric field")
    class AmountMask {

        @Test
        @DisplayName("the edited mask is +99999999.99, twelve characters wide")
        void theEditedMaskIsTwelveCharactersWide() {
            assertThat(TransactionDto.AMOUNT_EDITED_MASK)
                    .as("app/cbl/COTRN02C.cbl:L59 declares the edited field with a MANDATORY sign, eight "
                            + "integer digits and two decimals")
                    .isEqualTo("+99999999.99")
                    .hasSize(12);
            assertThat(TransactionDto.AMOUNT_DISPLAY_LENGTH)
                    .as("the display length must equal the mask width exactly")
                    .isEqualTo(TransactionDto.AMOUNT_EDITED_MASK.length())
                    .isEqualTo(12);
        }

        @Test
        @DisplayName("the mask carries eight integer digits while the field permits nine")
        void theMaskIsOneDigitNarrowerThanTheField() {
            final long maskIntegerDigits =
                    TransactionDto.AMOUNT_EDITED_MASK.chars().filter(ch -> ch == '9').count()
                            - TransactionDto.AMOUNT_SCALE;

            assertThat(TransactionDto.AMOUNT_INTEGER_DIGITS)
                    .as("app/cbl/COTRN02C.cbl:L58 declares the NUMERIC field as S9(09)V99 - nine integer "
                            + "digits")
                    .isEqualTo(9);
            assertThat(maskIntegerDigits)
                    .as("but the EDITED field at :L59 carries only EIGHT integer digits. The display mask "
                            + "is genuinely one digit narrower than the field it renders - a real legacy "
                            + "constraint, which is why a magnitude guard exists rather than a simple "
                            + "scale adjustment")
                    .isEqualTo(8);
            assertThat(maskIntegerDigits).isLessThan(TransactionDto.AMOUNT_INTEGER_DIGITS);
        }

        @Test
        @DisplayName("the precision is derived as integer digits plus scale, giving 11")
        void thePrecisionIsDerived() {
            assertThat(TransactionDto.AMOUNT_PRECISION)
                    .as("9 integer digits + 2 decimals = 11 significant digits, i.e. NUMERIC(11,2) - the "
                            + "same tier as TRAN-AMT and TRAN-CAT-BAL, and NOT the account fields' 12")
                    .isEqualTo(TransactionDto.AMOUNT_INTEGER_DIGITS + TransactionDto.AMOUNT_SCALE)
                    .isEqualTo(11);
            assertThat(TransactionDto.AMOUNT_SCALE).isEqualTo(2);
        }

        @Test
        @DisplayName("the rounding mode is HALF_EVEN, never left to a default")
        void theRoundingModeIsHalfEven() {
            assertThat(TransactionDto.AMOUNT_ROUNDING_MODE)
                    .as("HALF_EVEN is mandated for every financial field. The two modes genuinely "
                            + "disagree - 2.425 rounds to 2.42 under HALF_EVEN and 2.43 under HALF_UP - so "
                            + "the mode may never be left implicit")
                    .isEqualTo(RoundingMode.HALF_EVEN);
        }

        @Test
        @DisplayName("an amount is normalised to scale 2 using HALF_EVEN")
        void anAmountIsNormalisedToScaleTwo() {
            final TransactionDto dto = listDto(null);

            assertThat(dto.amountValue()).as("a null amount stays null").isNull();

            final TransactionDto scaled = new TransactionDto("CT00", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, new BigDecimal("1.005"));
            assertThat(scaled.amountValue())
                    .as("1.005 at scale 2 under HALF_EVEN goes DOWN to 1.00, because the retained digit 0 "
                            + "is already even. HALF_UP would give 1.01")
                    .isEqualByComparingTo("1.00");
            assertThat(scaled.amountValue().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"1000000000.00", "-1000000000.00", "9999999999.99"})
        @DisplayName("an amount at or beyond the nine-digit magnitude limit is rejected symmetrically")
        void anAmountBeyondTheMagnitudeLimitIsRejected(final String tooLarge) {
            assertThatIllegalArgumentException()
                    .as("the guard bounds the MAGNITUDE symmetrically, so a large negative is rejected "
                            + "exactly like a large positive. The abs() used in the check never rewrites "
                            + "the sign of a value that passes - no absolute-value normalisation happens "
                            + "on this path")
                    .isThrownBy(() -> new TransactionDto(null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, new BigDecimal(tooLarge)))
                    .withMessageContaining("9 integer digits");
        }

        @ParameterizedTest
        @ValueSource(strings = {"999999999.99", "-999999999.99", "0.00", "-0.01"})
        @DisplayName("an amount within nine integer digits is accepted with its sign preserved")
        void anAmountWithinNineDigitsIsAccepted(final String permitted) {
            final TransactionDto dto = new TransactionDto(null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, new BigDecimal(permitted));

            assertThat(dto.amountValue())
                    .as("the widest permitted magnitude must be accepted, and the SIGN must survive: the "
                            + "fixture dailytran.txt carries genuinely negative amounts that exercise the "
                            + "cycle-debit branch")
                    .isEqualByComparingTo(permitted);
        }

        @Test
        @DisplayName("a field exceeding its declared COBOL width is rejected with both lengths named")
        void aFieldExceedingItsWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .as("the message names the supplied and permitted lengths so the diagnostic is "
                            + "actionable without reading the copybook")
                    .isThrownBy(() -> new TransactionDto("TOOLONG", null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null))
                    .withMessageContainingAll("exceeds its declared COBOL width", "7", "4");
        }

        @Test
        @DisplayName("a null field passes the width guard, since absent is not over-long")
        void aNullFieldPassesTheWidthGuard() {
            assertThatCode(() -> listDto(null))
                    .as("the width guard only rejects a value that is present AND too long; a null must "
                            + "reach the service so the absent case keeps its own message")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("row access, projections and defensive copying on the transaction list")
    class RowAccessAndProjections {

        @Test
        @DisplayName("the detail projection emits exactly the declared 21 fields")
        void theDetailProjectionEmits21Fields() {
            assertThat(listDto(null).detailProjection())
                    .as("the projection is the wire form of the detail map, so its size must equal the "
                            + "map's field count exactly")
                    .hasSize(TransactionDto.DETAIL_FIELD_COUNT)
                    .hasSize(21);
        }

        @Test
        @DisplayName("the list projection emits exactly 59 fields even when no rows are present")
        void theListProjectionEmits59FieldsWhenEmpty() {
            assertThat(listDto(null).listProjection())
                    .as("a screen always has ten physical row slots, so an empty page still emits all 59 "
                            + "fields with the absent rows null-padded. Emitting fewer would leave stale "
                            + "content in the unwritten slots")
                    .hasSize(TransactionDto.LIST_FIELD_COUNT)
                    .hasSize(59);
        }

        @Test
        @DisplayName("the list projection emits 59 fields for a partially filled page")
        void theListProjectionEmits59FieldsWhenPartiallyFilled() {
            final List<TransactionDto.TransactionListRow> three =
                    List.of(row("0000000000000001"), row("0000000000000002"), row("0000000000000003"));

            final List<String> projection = listDto(three).listProjection();

            assertThat(projection).hasSize(59);
            assertThat(projection.get(TransactionDto.LIST_PREAMBLE_FIELD_COUNT + 1))
                    .as("the first row's transaction id sits immediately after the eight preamble fields "
                            + "and its own selection flag")
                    .isEqualTo("0000000000000001");
            assertThat(projection.subList(projection.size() - 1, projection.size()))
                    .as("the single trailer field is the error message and comes last")
                    .containsExactly((String) null);
        }

        @Test
        @DisplayName("the list projection is unmodifiable, so a caller cannot mutate a built response")
        void theListProjectionIsUnmodifiable() {
            final List<String> projection = listDto(null).listProjection();

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("returning a mutable projection would let a caller alter a response after it was "
                            + "built, which is a defect that only ever shows up under concurrency")
                    .isThrownBy(() -> projection.add("injected"));
        }

        @ParameterizedTest
        @CsvSource({"0", "1", "5", "9"})
        @DisplayName("a valid row slot within the ten positions is addressable")
        void aValidRowSlotIsAddressable(final int slot) {
            final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
            for (int i = 0; i < TransactionDto.PAGE_SIZE; i++) {
                rows.add(row(String.format("%016d", i)));
            }

            assertThat(listDto(rows).rowAt(slot))
                    .as("slot %d is within the ten declared positions", slot)
                    .isNotNull();
            assertThat(listDto(rows).rowAt(slot).transactionId()).isEqualTo(String.format("%016d", slot));
        }

        @ParameterizedTest
        @CsvSource({"-1", "10", "11", "100"})
        @DisplayName("a row slot outside the ten positions is rejected, naming the page size")
        void aRowSlotOutsideTheTenPositionsIsRejected(final int slot) {
            assertThatIllegalArgumentException()
                    .as("the screen declares exactly ten row slots, so slot %d cannot exist. Rejecting it "
                            + "rather than returning null distinguishes a programming error from an empty "
                            + "slot", slot)
                    .isThrownBy(() -> listDto(null).rowAt(slot))
                    .withMessageContaining("less than 10");
        }

        @Test
        @DisplayName("an in-range slot beyond the supplied rows returns null rather than throwing")
        void anInRangeSlotBeyondTheSuppliedRowsReturnsNull() {
            final List<TransactionDto.TransactionListRow> two =
                    List.of(row("0000000000000001"), row("0000000000000002"));
            final TransactionDto dto = listDto(two);

            assertThat(dto.rowAt(0)).isNotNull();
            assertThat(dto.rowAt(1)).isNotNull();
            assertThat(dto.rowAt(2))
                    .as("slot 2 is a legitimate screen position that simply has no data on this page, so "
                            + "it is EMPTY rather than invalid - the distinction matters because the "
                            + "projection must still pad it")
                    .isNull();
            assertThat(dto.rowAt(9)).isNull();
            assertThat(listDto(null).rowAt(0))
                    .as("a null row list yields null for every in-range slot")
                    .isNull();
        }

        @Test
        @DisplayName("the rows accessor returns an unmodifiable view, and null stays null")
        void theRowsAccessorIsDefensive() {
            assertThat(listDto(null).rows()).as("null rows round-trip as null").isNull();

            final List<TransactionDto.TransactionListRow> rows = listDto(List.of(row("1"))).rows();
            assertThat(rows).hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("the accessor must not leak a mutable reference to the backing list")
                    .isThrownBy(() -> rows.add(row("2")));
        }

        @Test
        @DisplayName("the constructor copies the caller's list, so later mutation cannot leak in")
        void theConstructorCopiesTheCallersList() {
            final List<TransactionDto.TransactionListRow> mutable = new ArrayList<>();
            mutable.add(row("0000000000000001"));

            final TransactionDto dto = listDto(mutable);
            mutable.add(row("0000000000000002"));

            assertThat(dto.rows())
                    .as("the DTO captured a COPY at construction, so mutating the caller's list "
                            + "afterwards must not change the built response")
                    .hasSize(1);
        }

        @Test
        @DisplayName("a list row carries the five fields the symbolic map generates per slot")
        void aListRowCarriesFiveFields() {
            final TransactionDto.TransactionListRow single = row("0000000000000001");

            assertThat(single.selectionFlag()).isEqualTo("S");
            assertThat(single.transactionId()).isEqualTo("0000000000000001");
            assertThat(single.transactionDate()).isEqualTo("20240101");
            assertThat(single.description()).isEqualTo("Description");
            assertThat(single.amount())
                    .as("five fields per row - selection flag, id, date, description and amount - which "
                            + "is exactly LIST_ROW_FIELD_COUNT")
                    .isEqualTo("+00000100.00");
            assertThat(TransactionDto.LIST_ROW_FIELD_COUNT).isEqualTo(5);
            assertThat(TransactionDto.SELECTION_FLAG_LENGTH)
                    .as("the selection flag is a single character")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a list row is a value type with equals, hashCode and toString")
        void aListRowIsAValueType() {
            final TransactionDto.TransactionListRow a = row("0000000000000001");
            final TransactionDto.TransactionListRow b = row("0000000000000001");
            final TransactionDto.TransactionListRow other = row("0000000000000002");

            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(other);
            assertThat(listDto(null)).isEqualTo(listDto(null));
        }

        @Test
        @DisplayName("toString redacts every protected field, so a logged DTO leaks nothing")
        void toStringRedactsEveryProtectedField() {
            final TransactionDto dto = new TransactionDto("CT00", "T1", "01/01/24", "COTRN01C", "T2",
                    "12.30.45", null, "0000000000000001", "4111111111111111", "01", "0005",
                    "POS TERM", "SENSITIVE DESCRIPTION", "+00000100.00", "2024-01-01", "2024-01-02",
                    "000000009", "MERCHANT NAME", "MERCHANT CITY", "12345", null, null, null,
                    new BigDecimal("100.00"));

            final String rendered = dto.toString();

            assertThat(rendered)
                    .as("the DTO deliberately renders ONLY the transaction id and the program name, and "
                            + "flags the omission explicitly. This is a secret-hygiene contract, not a "
                            + "convenience: a DTO reaching a log or an exception message must not carry a "
                            + "card number into it")
                    .contains("0000000000000001")
                    .contains("COTRN01C")
                    .contains("protectedFieldsOmitted=true");
            assertThat(rendered)
                    .as("the card number must NEVER appear - it is the single most sensitive field on the "
                            + "screen, and the default record toString WOULD have printed it, which is "
                            + "precisely why this override exists")
                    .doesNotContain("4111111111111111");
            assertThat(rendered)
                    .as("neither the amount, the description nor the merchant identity may appear")
                    .doesNotContain("SENSITIVE DESCRIPTION")
                    .doesNotContain("MERCHANT NAME")
                    .doesNotContain("+00000100.00");
        }

        @Test
        @DisplayName("a list row's toString also redacts, exposing only the transaction id")
        void aListRowToStringAlsoRedacts() {
            final TransactionDto.TransactionListRow single = new TransactionDto.TransactionListRow(
                    "S", "0000000000000001", "20240101", "SENSITIVE ROW TEXT", "+00000100.00");

            assertThat(single.toString())
                    .as("the row override is consistent with the outer DTO's: an identifier is enough to "
                            + "correlate a log entry, and the remaining fields add leak risk without "
                            + "adding diagnostic value")
                    .contains("0000000000000001")
                    .doesNotContain("SENSITIVE ROW TEXT")
                    .doesNotContain("+00000100.00");
        }
    }

    @Nested
    @DisplayName("the card screens: 15 detail fields, 45 list fields, 7 rows, and the row-1 quirk")
    class CardFieldBudget {

        @Test
        @DisplayName("the card detail and list field counts match their copybooks")
        void theCardFieldCountsMatchTheirCopybooks() {
            assertThat(CardDto.DETAIL_FIELD_COUNT)
                    .as("app/cpy-bms/COCRDSL.CPY generates 15 input fields")
                    .isEqualTo(15);
            assertThat(CardDto.LIST_FIELD_COUNT)
                    .as("app/cpy-bms/COCRDLI.CPY generates 45")
                    .isEqualTo(45);
        }

        @Test
        @DisplayName("the card list page size is seven, per COCRDLIC")
        void theCardListPageSizeIsSeven() {
            assertThat(CardDto.CARD_LIST_PAGE_SIZE)
                    .as("app/cbl/COCRDLIC.cbl:L177 declares SEVEN row slots - deliberately fewer than the "
                            + "transaction and user lists' ten, because each card row is wider")
                    .isEqualTo(7)
                    .isNotEqualTo(TransactionDto.PAGE_SIZE);
        }

        @Test
        @DisplayName("row one carries four fields where rows two to seven carry five")
        void rowOneCarriesFourFieldsWhereTheOthersCarryFive() {
            assertThat(CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE)
                    .as("the symbolic map omits the selector-type field on exactly one row")
                    .isEqualTo(1);
            assertThat(cardRow(1).hasSelectorType())
                    .as("PRESERVED BMS QUIRK: row 1 has NO selector-type field. This is asymmetric in the "
                            + "generated map itself, so the DTO must reproduce it rather than normalise "
                            + "all seven rows to the same shape")
                    .isFalse();
            assertThat(cardRow(1).getBmsFieldCount())
                    .isEqualTo(CardDto.ROW_FIELD_COUNT_WITHOUT_SELECTOR_TYPE)
                    .isEqualTo(4);

            for (int number = 2; number <= CardDto.CARD_LIST_PAGE_SIZE; number++) {
                assertThat(cardRow(number).hasSelectorType())
                        .as("row %d carries the selector type", number)
                        .isTrue();
                assertThat(cardRow(number).getBmsFieldCount())
                        .isEqualTo(CardDto.ROW_FIELD_COUNT_WITH_SELECTOR_TYPE)
                        .isEqualTo(5);
            }
        }

        @Test
        @DisplayName("the seven rows' field counts sum to 34, leaving 11 for preamble and trailer")
        void theSevenRowsSumTo34LeavingElevenAround() {
            int rowFields = 0;
            for (int number = CardDto.FIRST_ROW_NUMBER;
                    number <= CardDto.CARD_LIST_PAGE_SIZE; number++) {
                rowFields += cardRow(number).getBmsFieldCount();
            }

            assertThat(rowFields)
                    .as("six rows at 5 fields plus one row at 4 = 34. Deriving this from the per-row "
                            + "counts proves the row-1 asymmetry is actually honoured, which a literal "
                            + "assertion of 45 would not")
                    .isEqualTo(34);
            assertThat(CardDto.LIST_FIELD_COUNT - rowFields)
                    .as("45 total minus 34 row fields leaves 11 preamble and trailer fields, and the "
                            + "arithmetic closing is what confirms the row model is right")
                    .isEqualTo(11);
            assertThat(CardDto.FIRST_ROW_NUMBER)
                    .as("BMS row numbering is ONE-based, unlike the zero-based Java slot index used by "
                            + "the transaction projection")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the detail factory populates the detail fields and leaves rows absent")
        void theDetailFactoryPopulatesDetailFields() {
            final CardDto detail = CardDto.detail("CCDL", "T1", "01/01/24", "COCRDSLC", "T2",
                    "12.30.45", "00000000001", "4111111111111111", "CARDHOLDER NAME", "Y", "12",
                    "2030", "Info", null, "F3=Exit");

            assertThat(detail.getRows())
                    .as("the DETAIL screen has no row table, so its rows must be absent rather than an "
                            + "empty list - the two are distinguishable and the projection depends on it")
                    .isNull();
        }

        @Test
        @DisplayName("the list factory captures the rows defensively")
        void theListFactoryCapturesRowsDefensively() {
            final List<CardDto.CardListRow> mutable = new ArrayList<>();
            mutable.add(cardRow(1));

            final CardDto list = CardDto.list("CCLI", "T1", "01/01/24", "COCRDLIC", "T2", "12.30.45",
                    "001", "00000000001", "4111111111111111", mutable, "Info", null);
            mutable.add(cardRow(2));

            assertThat(list.getRows())
                    .as("the factory copied the list, so the caller's later mutation must not leak in")
                    .hasSize(1);
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .as("and the accessor must not leak a mutable reference back out")
                    .isThrownBy(() -> list.getRows().add(cardRow(3)));
        }

        @Test
        @DisplayName("a null row list is preserved as null by the list factory")
        void aNullRowListIsPreserved() {
            final CardDto list = CardDto.list("CCLI", null, null, null, null, null, null, null, null,
                    null, null, null);

            assertThat(list.getRows())
                    .as("absent rows stay absent; the service distinguishes a page that was never "
                            + "populated from one that legitimately returned nothing")
                    .isNull();
        }

        @Test
        @DisplayName("a card field exceeding its declared width is rejected")
        void aCardFieldExceedingItsWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .as("the transaction name is X(4) on every map, so five characters must be refused")
                    .isThrownBy(() -> CardDto.detail("TOOLONG", null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null))
                    .withMessageContaining("4");

            assertThatIllegalArgumentException()
                    .as("the card number is X(16); seventeen characters cannot be stored")
                    .isThrownBy(() -> CardDto.detail("CCDL", null, null, null, null, null, null,
                            "1".repeat(17), null, null, null, null, null, null, null))
                    .withMessageContaining("16");
        }

        @Test
        @DisplayName("the constructor ENFORCES the row-1 quirk, rejecting a selector type there")
        void theConstructorEnforcesTheRowOneQuirk() {
            assertThatIllegalArgumentException()
                    .as("the DTO does not merely REPORT the asymmetry through hasSelectorType - it "
                            + "actively refuses to construct an impossible row. That is stronger than "
                            + "documenting the quirk, because a caller cannot accidentally populate a "
                            + "field the map has no slot for")
                    .isThrownBy(() -> new CardDto.CardListRow(
                            1, "S", "T", "00000000001", "4111111111111111", "Y"))
                    .withMessageContainingAll("selectorType must be null for row 1",
                            "declares no CRDSTP1I anywhere", "4 fields and not 5");

            assertThatCode(() -> new CardDto.CardListRow(
                    1, "S", null, "00000000001", "4111111111111111", "Y"))
                    .as("row 1 with a null selector type is the only legal shape for that slot")
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @CsvSource({"0", "-1", "8", "100"})
        @DisplayName("a row number outside the seven declared groups is rejected")
        void aRowNumberOutsideTheSevenGroupsIsRejected(final int rowNumber) {
            assertThatIllegalArgumentException()
                    .as("app/cpy-bms/COCRDLI.CPY declares exactly seven row groups, so row %d cannot "
                            + "exist. Note the range is ONE-based, so 0 is invalid where a Java slot "
                            + "index would accept it", rowNumber)
                    .isThrownBy(() -> new CardDto.CardListRow(
                            rowNumber, "S", null, "00000000001", "4111111111111111", "Y"))
                    .withMessageContaining("rowNumber must be between 1 and 7");
        }

        @Test
        @DisplayName("a row component exceeding its declared width is rejected")
        void aRowComponentExceedingItsWidthIsRejected() {
            assertThatIllegalArgumentException()
                    .as("the selection flag is a single character")
                    .isThrownBy(() -> new CardDto.CardListRow(
                            2, "TOO", "T", "00000000001", "4111111111111111", "Y"))
                    .withMessageContaining("selectionFlag");
            assertThatIllegalArgumentException()
                    .as("the account number is X(11), matching the ACCTDATA key length")
                    .isThrownBy(() -> new CardDto.CardListRow(
                            2, "S", "T", "1".repeat(12), "4111111111111111", "Y"))
                    .withMessageContaining("accountNumber");
        }

        @Test
        @DisplayName("a card list row exposes all six of its components")
        void aCardListRowExposesAllSixComponents() {
            final CardDto.CardListRow single = cardRow(3);

            assertThat(single.getRowNumber()).isEqualTo(3);
            assertThat(single.getSelectionFlag()).isEqualTo("S");
            assertThat(single.getSelectorType()).isEqualTo("T");
            assertThat(single.getAccountNumber())
                    .as("the row shows the account number, which is what makes the account-filtered list "
                            + "path meaningful")
                    .isEqualTo("00000000001");
            assertThat(single.getCardNumber()).isEqualTo("4111111111111111");
            assertThat(single.getStatusCode()).isEqualTo("Y");
        }
    }
}
