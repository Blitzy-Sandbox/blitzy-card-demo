/*
 * ******************************************************************
 * Program     : AccountDtoTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the account-view payload against its frozen
 *               field contract: the 37 screen fields COACTVW declares,
 *               the monetary conversion and its refusals, and the
 *               toString override that withholds protected data.
 * Source      : app/cpy-bms/COACTVW.CPY  (37 input fields)
 *               app/cpy/CVACT01Y.cpy     (PIC S9(10)V99 amounts)
 *               app/cbl/COACTVWC.cbl     (LOW-VALUES screen state)
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

import com.cardemo.model.dto.AccountDto;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link AccountDto} against {@code app/cpy-bms/COACTVW.CPY} and {@code app/cpy/CVACT01Y.cpy}.
 *
 * <p>The field count on this payload was an open question worth settling in a test rather than in a comment.
 * AAP section 0.2.1.4 records {@code COACTVW} as declaring 36 input fields, while this record declares 37
 * components and publishes {@code FIELD_COUNT = 37}. The copybook settles it: 36 fields are declared
 * {@code PIC X(n)} and one, {@code 02 ACCTSIDI PIC 99999999999} at line 60, is declared with a numeric
 * picture. The AAP figure omits it. {@link BmsSymbolicMap} reads all 37, and corroborates the total against
 * the 37 {@code COMP PIC S9(4)} length fields the generator emits one per input field.
 *
 * <p>The strongest assertion here is positional. Every one of the 37 components is matched against the
 * copybook field at the same index, so the count cannot be satisfied by an invented thirty-seventh
 * component: the record's declaration order has to be the copybook's declaration order, interleaved dates
 * and amounts included.
 */
@DisplayName("AccountDto: 37 screen fields, monetary conversion, and a toString that withholds")
final class AccountDtoTest {

    /** The account-view symbolic map, read from the frozen tree. */
    private static final BmsSymbolicMap COACTVW = BmsSymbolicMap.of("COACTVW");

    /** The bill-payment map, whose current balance is a different name at a different width. */
    private static final BmsSymbolicMap COBIL00 = BmsSymbolicMap.of("COBIL00");

    /**
     * The record component name for each COACTVW input field, in copybook declaration order.
     *
     * <p>Read as pairs. The ordering is the screen's own, which interleaves the date fields with the
     * monetary fields because the map lays them out in two columns; the record preserves that order rather
     * than grouping like with like.
     */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "TRNNAMEI", "transactionName",
            "TITLE01I", "title01",
            "CURDATEI", "currentDate",
            "PGMNAMEI", "programName",
            "TITLE02I", "title02",
            "CURTIMEI", "currentTime",
            "ACCTSIDI", "accountId",
            "ACSTTUSI", "accountStatus",
            "ADTOPENI", "openDate",
            "ACRDLIMI", "creditLimit",
            "AEXPDTI", "expiryDate",
            "ACSHLIMI", "cashCreditLimit",
            "AREISDTI", "reissueDate",
            "ACURBALI", "currentBalance",
            "ACRCYCRI", "currentCycleCredit",
            "AADDGRPI", "accountGroupId",
            "ACRCYDBI", "currentCycleDebit",
            "ACSTNUMI", "customerId",
            "ACSTSSNI", "customerSsn",
            "ACSTDOBI", "customerDateOfBirth",
            "ACSTFCOI", "customerFicoScore",
            "ACSFNAMI", "customerFirstName",
            "ACSMNAMI", "customerMiddleName",
            "ACSLNAMI", "customerLastName",
            "ACSADL1I", "addressLine1",
            "ACSSTTEI", "addressStateCode",
            "ACSADL2I", "addressLine2",
            "ACSZIPCI", "addressZip",
            "ACSCITYI", "addressCity",
            "ACSCTRYI", "addressCountryCode",
            "ACSPHN1I", "phoneNumber1",
            "ACSGOVTI", "governmentIssuedId",
            "ACSPHN2I", "phoneNumber2",
            "ACSEFTCI", "eftAccountId",
            "ACSPFLGI", "primaryCardHolderIndicator",
            "INFOMSGI", "informationMessage",
            "ERRMSGI", "errorMessage");

    /** The five monetary fields, every one of them declared {@code PIC X(15)} on this map. */
    private static final List<String> MONETARY_FIELDS =
            List.of("ACRDLIMI", "ACSHLIMI", "ACURBALI", "ACRCYCRI", "ACRCYDBI");

    /** The components that carry protected data and must never reach a diagnostic rendering. */
    private static final List<String> PROTECTED_VALUES = List.of(
            "123456789", "1961-06-08", "5551234567", "5559876543", "GOVT-ID-0000", "EFTACCT441",
            "FNAMEAA1", "MNAM1", "LNM1");

    private static List<String> componentNames() {
        return Arrays.stream(AccountDto.class.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    /**
     * Builds a payload whose every component carries a distinguishable value.
     *
     * @return a fully populated payload
     */
    private static AccountDto populated() {
        return new AccountDto("CAVW", "CardDemo Account View", "08/01/26", "COACTVWC",
                "View Account", "14:22:31", "00000000001", "Y", "2020-01-15", "9999999999.99",
                "2027-01-14", "500.00", "2021-06-30", "1234.56", "200.00", "default",
                "-45.00", "000000001", "123456789", "1961-06-08", "750", "FNAMEAA1",
                "MNAM1", "LNM1", "123 MAIN STREET", "NY", "APT 4B", "10001",
                "NEW YORK", "USA", "5551234567", "GOVT-ID-0000", "5559876543", "EFTACCT441",
                "Y", "Account retrieved", "");
    }

    @Nested
    @DisplayName("1. The 37 fields the copybook declares, matched positionally")
    final class FieldContract {

        @Test
        @DisplayName("FIELD_COUNT is the copybook's own input-field count")
        void fieldCountMatchesTheCopybook() {
            assertThat(AccountDto.FIELD_COUNT)
                    .as("app/cpy-bms/COACTVW.CPY input fields, 36 X-pictured plus ACCTSIDI at line 60")
                    .isEqualTo(COACTVW.inputFieldCount())
                    .isEqualTo(37);
        }

        @Test
        @DisplayName("the record declares exactly that many components")
        void recordDeclaresThatManyComponents() {
            assertThat(componentNames()).hasSize(AccountDto.FIELD_COUNT);
        }

        @Test
        @DisplayName("every component matches the copybook field at the same index")
        void componentsCorrespondPositionally() {
            final List<String> components = componentNames();
            final List<String> copybookOrder = COACTVW.fieldNames();

            assertThat(FIELD_TO_COMPONENT).hasSize(2 * AccountDto.FIELD_COUNT);
            for (int index = 0; index < AccountDto.FIELD_COUNT; index++) {
                final String expectedField = FIELD_TO_COMPONENT.get(2 * index);
                final String expectedComponent = FIELD_TO_COMPONENT.get(2 * index + 1);

                assertThat(copybookOrder.get(index))
                        .as("copybook field at index %d", index)
                        .isEqualTo(expectedField);
                assertThat(components.get(index))
                        .as("component for copybook field %s at index %d", expectedField, index)
                        .isEqualTo(expectedComponent);
            }
        }

        @Test
        @DisplayName("the numeric-pictured account identifier is one of the 37")
        void theNumericFieldIsIncluded() {
            assertThat(COACTVW.declares("ACCTSIDI")).isTrue();
            assertThat(COACTVW.widthOf("ACCTSIDI")).isEqualTo(11);
            assertThat(componentNames()).contains("accountId");
        }

        @Test
        @DisplayName("every component is a String, so the payload stays byte-comparable")
        void everyComponentIsAString() {
            assertThat(AccountDto.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType()).isEqualTo(String.class));
        }
    }

    @Nested
    @DisplayName("2. The monetary width belongs to this map alone")
    final class MonetaryWidth {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ACRDLIMI", "ACSHLIMI", "ACURBALI", "ACRCYCRI", "ACRCYDBI"})
        @DisplayName("each monetary field is declared at the published width")
        void monetaryFieldsShareTheWidth(final String field) {
            assertThat(COACTVW.widthOf(field))
                    .as("%s is declared PIC X(15) on the account-view map", field)
                    .isEqualTo(AccountDto.MONEY_DISPLAY_LENGTH);
        }

        @Test
        @DisplayName("all five monetary fields are accounted for")
        void allFiveAreAccountedFor() {
            assertThat(MONETARY_FIELDS).hasSize(5).allMatch(COACTVW::declares);
        }

        @Test
        @DisplayName("the bill-payment map declares a narrower balance, so the width cannot be shared")
        void billPaymentDeclaresADifferentWidth() {
            // The rationale documented on MONEY_DISPLAY_LENGTH: a different name at a different width, which
            // is why neither the constant nor toAmount may be promoted to a cross-map utility.
            assertThat(COBIL00.declares("CURBALI")).isTrue();
            assertThat(COBIL00.widthOf("CURBALI")).isEqualTo(14);
            assertThat(COBIL00.widthOf("CURBALI")).isNotEqualTo(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(COACTVW.declares("CURBALI"))
                    .as("the account-view map uses ACURBALI, not CURBALI")
                    .isFalse();
        }

        @Test
        @DisplayName("the scale and precision come from PIC S9(10)V99")
        void scaleAndPrecisionComeFromTheRecordLayout() {
            // app/cpy/CVACT01Y.cpy:7-14 declares all five amounts PIC S9(10)V99: ten integer digits and two
            // fractional digits, which is the NUMERIC(12,2) column width in the relational target.
            assertThat(AccountDto.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountDto.MONEY_PRECISION).isEqualTo(12);
            assertThat(AccountDto.MONEY_PRECISION - AccountDto.MONEY_SCALE)
                    .as("integer digits available to an account amount")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("the display mask is narrower than the stored precision, and that is the legacy limit")
        void theMaskIsNarrowerThanStorage() {
            // COACTVW.CPY:302,314,326,332,344 render these amounts as +ZZZ,ZZZ,ZZZ.99 - a sign, nine integer
            // digits and two decimals. Storage allows ten integer digits, so the tenth cannot be rendered by
            // the legacy mask at all. The gap is faithful to the corpus and is asserted rather than
            // reconciled.
            final String mask = "+ZZZ,ZZZ,ZZZ.99";
            final long maskIntegerDigits = mask.chars().filter(character -> character == 'Z').count();

            assertThat(mask).hasSize(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(maskIntegerDigits).isEqualTo(9L);
            assertThat(maskIntegerDigits)
                    .as("the mask cannot render the tenth integer digit that storage permits")
                    .isLessThan(AccountDto.MONEY_PRECISION - AccountDto.MONEY_SCALE);
        }
    }

    @Nested
    @DisplayName("3. Three screen states that all mean no amount")
    final class AbsentAmounts {

        @Test
        @DisplayName("a null display text yields an absent amount")
        void nullYieldsAbsent() {
            assertThat(AccountDto.toAmount(null, "currentBalance")).isEmpty();
        }

        @Test
        @DisplayName("blanks yield an absent amount")
        void blanksYieldAbsent() {
            assertThat(AccountDto.toAmount("               ", "currentBalance")).isEmpty();
            assertThat(AccountDto.toAmount("", "currentBalance")).isEmpty();
        }

        @Test
        @DisplayName("binary zeros yield an absent amount, because LOW-VALUES is not whitespace")
        void lowValuesYieldAbsent() {
            // app/cbl/COACTVWC.cbl:466 moves LOW-VALUES into a screen field when the filter is blank. Binary
            // zeros survive stripping, so they have to be neutralised explicitly.
            assertThat(AccountDto.toAmount("\u0000\u0000\u0000", "currentBalance")).isEmpty();
            assertThat(AccountDto.toAmount("\u0000\u0000 \u0000", "currentBalance")).isEmpty();
        }

        @Test
        @DisplayName("binary zeros around a value are neutralised rather than rejected")
        void lowValuesAroundAValueAreNeutralised() {
            assertThat(AccountDto.toAmount("\u0000123.45\u0000", "currentBalance"))
                    .contains(new BigDecimal("123.45"));
        }

        @Test
        @DisplayName("the three absent states stay distinguishable on the record itself")
        void theRecordDoesNotCollapseThem() {
            // Only the conversion collapses them, and only for arithmetic. The transport type keeps them
            // apart so the payload stays byte-comparable against the legacy baseline.
            final AccountDto withNull = new AccountDto(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(withNull.currentBalance()).isNull();
            assertThat(populated().currentBalance()).isEqualTo("1234.56");
        }
    }

    @Nested
    @DisplayName("4. Parsing, scaling and banker's rounding")
    final class Conversion {

        @Test
        @DisplayName("a plain amount converts and is scaled to two fractional digits")
        void plainAmountConverts() {
            final Optional<BigDecimal> converted = AccountDto.toAmount("1234.56", "currentBalance");

            assertThat(converted).isPresent();
            assertThat(converted.orElseThrow()).isEqualByComparingTo("1234.56");
            assertThat(converted.orElseThrow().scale()).isEqualTo(AccountDto.MONEY_SCALE);
        }

        @Test
        @DisplayName("an unscaled amount is widened to two fractional digits")
        void unscaledAmountIsWidened() {
            assertThat(AccountDto.toAmount("100", "creditLimit").orElseThrow())
                    .isEqualTo(new BigDecimal("100.00"));
        }

        @Test
        @DisplayName("a negative amount keeps its sign and is never normalised")
        void negativeKeepsItsSign() {
            // The current cycle debit legitimately accumulates negative amounts, and the over-limit
            // arithmetic of the posting path depends on that sign surviving.
            assertThat(AccountDto.toAmount("-45.00", "currentCycleDebit").orElseThrow())
                    .isEqualByComparingTo("-45.00")
                    .isNegative();
        }

        @Test
        @DisplayName("an explicitly signed positive amount converts")
        void explicitPlusConverts() {
            assertThat(AccountDto.toAmount("+45.00", "currentCycleCredit").orElseThrow())
                    .isEqualByComparingTo("45.00");
        }

        @Test
        @DisplayName("rounding is HALF_EVEN, which HALF_UP would get wrong")
        void roundingIsHalfEven() {
            // The discriminating case: a tie rounds to the even neighbour, so 0.125 becomes 0.12 and not the
            // 0.13 that HALF_UP produces. Without this the mode could be changed unnoticed.
            assertThat(AccountDto.toAmount("0.125", "currentBalance").orElseThrow())
                    .isEqualByComparingTo("0.12");
            assertThat(AccountDto.toAmount("0.135", "currentBalance").orElseThrow())
                    .isEqualByComparingTo("0.14");
            assertThat(AccountDto.MONEY_ROUNDING).isEqualTo(RoundingMode.HALF_EVEN);
        }

        @Test
        @DisplayName("a negative tie rounds to the even neighbour too")
        void negativeTieRoundsHalfEven() {
            assertThat(AccountDto.toAmount("-0.125", "currentCycleDebit").orElseThrow())
                    .isEqualByComparingTo("-0.12");
        }

        @Test
        @DisplayName("conversion of the same text is idempotent")
        void conversionIsIdempotent() {
            final BigDecimal once = AccountDto.toAmount("0.125", "currentBalance").orElseThrow();
            final BigDecimal twice = AccountDto.toAmount(once.toPlainString(), "currentBalance").orElseThrow();

            assertThat(twice).isEqualTo(once);
        }

        @Test
        @DisplayName("the widest amount the field allows converts")
        void widestAmountConverts() {
            assertThat(AccountDto.toAmount("9999999999.99", "creditLimit").orElseThrow())
                    .isEqualByComparingTo("9999999999.99");
        }

        @Test
        @DisplayName("zero converts to a scaled zero rather than an absent amount")
        void zeroIsNotAbsent() {
            assertThat(AccountDto.toAmount("0", "currentBalance"))
                    .as("zero is a value; only null, blanks and LOW-VALUES mean absent")
                    .contains(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("results are compared by value, never by representation")
        void compareByValueNotEquals() {
            final BigDecimal fromUnscaled = AccountDto.toAmount("100", "creditLimit").orElseThrow();
            final BigDecimal fromScaled = AccountDto.toAmount("100.00", "creditLimit").orElseThrow();

            assertThat(fromUnscaled).isEqualByComparingTo(fromScaled);
            assertThat(new BigDecimal("100")).isEqualByComparingTo(fromUnscaled);
            assertThat(new BigDecimal("100"))
                    .as("equals compares scale as well as value, which is why compareTo is required")
                    .isNotEqualTo(fromUnscaled);
        }
    }

    @Nested
    @DisplayName("5. What the conversion refuses")
    final class Refusals {

        @Test
        @DisplayName("a null field name is refused, because a failure could not then be identified")
        void nullFieldNameIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("1.00", null))
                    .withMessageContaining("fieldName is required");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "   "})
        @DisplayName("a blank field name is refused")
        void blankFieldNameIsRefused(final String fieldName) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("1.00", fieldName))
                    .withMessageContaining("fieldName is required");
        }

        @Test
        @DisplayName("the field name is validated before the display text, so both may be wrong")
        void fieldNameIsCheckedFirst() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("not a number at all", null))
                    .withMessageContaining("fieldName is required");
        }

        @Test
        @DisplayName("text longer than the declared width cannot have come from this map")
        void overWidthTextIsRefused() {
            final String tooLong = "1".repeat(AccountDto.MONEY_DISPLAY_LENGTH + 1);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount(tooLong, "currentBalance"))
                    .withMessageContaining("currentBalance")
                    .withMessageContaining("COACTVW.CPY")
                    .withMessageContaining(String.valueOf(AccountDto.MONEY_DISPLAY_LENGTH));
        }

        @Test
        @DisplayName("text of exactly the declared width is accepted")
        void exactWidthIsAccepted() {
            final String exact = "  9999999999.99";

            assertThat(exact).hasSize(AccountDto.MONEY_DISPLAY_LENGTH);
            assertThat(AccountDto.toAmount(exact, "creditLimit").orElseThrow())
                    .isEqualByComparingTo("9999999999.99");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"1,000.00", "$100.00", "1 000.00", "12.34.56", "abc", "-", "+", ".", "1.2.3"})
        @DisplayName("grouping separators, currency symbols and non-numeric text are refused")
        void nonNumericTextIsRefused(final String text) {
            // The screen is populated by a move from an unedited PIC S9(10)V99 field, so it never produces a
            // separator or a symbol. The currency-tolerant conversion belongs to the transaction-add path.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount(text, "currentBalance"))
                    .withMessageContaining("currentBalance")
                    .withMessageContaining("does not hold a decimal amount");
        }

        @Test
        @DisplayName("the originating NumberFormatException is preserved as the cause")
        void parseFailurePreservesItsCause() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("abc", "currentBalance"))
                    .withCauseInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("an amount needing more significant digits than the field allows is refused")
        void overPreciseAmountIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("10000000000.00", "creditLimit"))
                    .withMessageContaining("creditLimit")
                    .withMessageContaining("PIC S9(10)V99")
                    .withMessageContaining(String.valueOf(AccountDto.MONEY_PRECISION));
        }

        @Test
        @DisplayName("rounding is applied before the precision ceiling, so a tie can push a value over it")
        void roundingCanPushAValueOverTheCeiling() {
            // 9999999999.999 is thirteen significant digits before scaling and rounds to 10000000000.00,
            // which is still thirteen. The order of the two steps is therefore observable.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount("9999999999.999", "creditLimit"))
                    .withMessageContaining(String.valueOf(AccountDto.MONEY_PRECISION));
        }

        @Test
        @DisplayName("excess fractional digits are rounded away rather than refused")
        void excessFractionalDigitsAreRounded() {
            assertThat(AccountDto.toAmount("1.239", "currentBalance").orElseThrow())
                    .isEqualByComparingTo("1.24");
        }

        @Test
        @DisplayName("no refusal message ever quotes the offending value")
        void refusalsNeverQuoteTheValue() {
            // Several components on this payload are protected data, so a diagnostic names the field and
            // never reproduces its contents.
            final String secret = "123456789012.34";

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> AccountDto.toAmount(secret, "customerSsn"))
                    .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(secret));
        }
    }

    @Nested
    @DisplayName("6. The diagnostic rendering withholds every protected field")
    final class DiagnosticRendering {

        @Test
        @DisplayName("only the account identifier, status and program name are rendered")
        void onlyThreeFieldsAreRendered() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .startsWith("AccountDto[")
                    .contains("accountId=00000000001")
                    .contains("accountStatus=Y")
                    .contains("programName=COACTVWC")
                    .endsWith("protectedFieldsOmitted=true]");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"123456789", "1961-06-08", "5551234567", "5559876543", "GOVT-ID-0000",
                "EFTACCT441", "FNAMEAA1", "MNAM1", "LNM1"})
        @DisplayName("no protected value reaches the rendering")
        void protectedValuesAreWithheld(final String protectedValue) {
            // The defence that matters is never emitting the value at all; central log masking is only the
            // second line of defence.
            assertThat(populated().toString()).doesNotContain(protectedValue);
        }

        @Test
        @DisplayName("all nine protected values are covered by that check")
        void allProtectedValuesAreCovered() {
            assertThat(PROTECTED_VALUES).hasSize(9);
            final String rendered = populated().toString();
            assertThat(PROTECTED_VALUES).allSatisfy(value -> assertThat(rendered).doesNotContain(value));
        }

        @Test
        @DisplayName("the rendering states plainly that fields were withheld")
        void theRenderingAdmitsWithholding() {
            assertThat(populated().toString())
                    .as("a reader must not mistake the rendering for a complete dump")
                    .contains("protectedFieldsOmitted=true");
        }

        @Test
        @DisplayName("an unpopulated payload renders without failing")
        void unpopulatedPayloadRenders() {
            final AccountDto empty = new AccountDto(null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null);

            assertThat(empty.toString())
                    .contains("accountId=null")
                    .contains("protectedFieldsOmitted=true");
        }

        @Test
        @DisplayName("the rendering is far shorter than a generated one would be")
        void theRenderingIsShort() {
            // A generated record toString emits all 37 components. This one emits three.
            assertThat(populated().toString().split(", ")).hasSize(4);
        }
    }
}
