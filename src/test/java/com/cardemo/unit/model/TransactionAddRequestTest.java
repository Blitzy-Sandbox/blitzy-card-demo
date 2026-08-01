/*
 * ******************************************************************
 * Program     : TransactionAddRequestTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the transaction-add payload against its
 *               frozen field contract: the 21 screen fields COTRN02
 *               declares, the eight-versus-nine digit asymmetry
 *               between the edited mask and the numeric field, the
 *               constraints deliberately NOT declared, and the
 *               toString override that withholds the card number.
 * Source      : app/cpy-bms/COTRN02.CPY  (21 input fields)
 *               app/cbl/COTRN02C.cbl     (58-60, 385-386, 456-457)
 *               app/cpy/CVTRA05Y.cpy     (TRAN-AMT PIC S9(09)V99)
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

import com.cardemo.model.dto.AccountDto;
import com.cardemo.model.dto.TransactionAddRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies {@link TransactionAddRequest} against {@code app/cpy-bms/COTRN02.CPY} and its COBOL source.
 *
 * <p>Two of the assertions here are worth more than the rest. The first is positional: each of the 21
 * components is matched against the copybook field at the same index and its declared {@code @Size} ceiling
 * is read from the copybook rather than restated, so a width can only be right if it agrees with the frozen
 * map. The second asserts a deliberate <em>absence</em> - the record declares no digits-only constraint on
 * the amount, because the legacy program parses that field with a currency-tolerant conversion and any
 * digits-only rule would reject input the source accepts.
 */
@DisplayName("TransactionAddRequest: 21 screen fields, a mask narrower than its value, and a redacted dump")
final class TransactionAddRequestTest {

    /** The transaction-add symbolic map, read from the frozen tree. */
    private static final BmsSymbolicMap COTRN02 = BmsSymbolicMap.of("COTRN02");

    /**
     * The record component for each COTRN02 input field, in copybook declaration order.
     *
     * <p>Read as pairs.
     */
    private static final List<String> FIELD_TO_COMPONENT = List.of(
            "TRNNAMEI", "transactionName",
            "TITLE01I", "title01",
            "CURDATEI", "currentDate",
            "PGMNAMEI", "programName",
            "TITLE02I", "title02",
            "CURTIMEI", "currentTime",
            "ACTIDINI", "accountId",
            "CARDNINI", "cardNumber",
            "TTYPCDI", "typeCode",
            "TCATCDI", "categoryCode",
            "TRNSRCI", "source",
            "TDESCI", "description",
            "TRNAMTI", "amount",
            "TORIGDTI", "originatingDate",
            "TPROCDTI", "processingDate",
            "MIDI", "merchantId",
            "MNAMEI", "merchantName",
            "MCITYI", "merchantCity",
            "MZIPI", "merchantZip",
            "CONFIRMI", "confirmation",
            "ERRMSGI", "errorMessage");

    private static List<String> fieldComponentPairs() {
        return RecordFieldContract.pairsOf(FIELD_TO_COMPONENT);
    }

    /**
     * Builds a payload whose every component carries a distinguishable, in-width value.
     *
     * @return a fully populated payload
     */
    private static TransactionAddRequest populated() {
        return new TransactionAddRequest("CT02", "CardDemo Add Transaction", "08/01/26", "COTRN02C",
                "Add Transaction", "14:22:31", "00000000001", "4111111111111111", "01", "0001",
                "POS TERMNL", "GROCERY PURCHASE", "+00001234.56", "2022-06-10", "2022-06-11",
                "000000042", "ACME SUPERMARKET", "NEW YORK", "10001-1234", "Y", "");
    }

    private Set<ConstraintViolation<TransactionAddRequest>> violationsOf(final TransactionAddRequest request) {
        return ValidationSupport.violationsOf(request, "request");
    }

    @Nested
    @DisplayName("1. The 21 fields the copybook declares, matched positionally")
    final class FieldContract {

        @Test
        @DisplayName("the record declares one component per copybook input field")
        void componentCountMatchesTheCopybook() {
            assertThat(RecordFieldContract.componentNames(TransactionAddRequest.class))
                    .hasSize(COTRN02.inputFieldCount())
                    .hasSize(21);
        }

        @Test
        @DisplayName("every component matches the copybook field at the same index")
        void componentsCorrespondPositionally() {
            final List<String> components = RecordFieldContract.componentNames(TransactionAddRequest.class);
            final List<String> copybookOrder = COTRN02.fieldNames();

            assertThat(FIELD_TO_COMPONENT).hasSize(2 * components.size());
            for (int index = 0; index < components.size(); index++) {
                assertThat(copybookOrder.get(index))
                        .as("copybook field at index %d", index)
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index));
                assertThat(components.get(index))
                        .as("component at index %d", index)
                        .isEqualTo(FIELD_TO_COMPONENT.get(2 * index + 1));
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.TransactionAddRequestTest#fieldComponentPairs")
        @DisplayName("each declared width is the copybook's width, not a restated literal")
        void declaredWidthComesFromTheCopybook(final String pair) {
            final String field = pair.substring(0, pair.indexOf(':'));
            final String component = pair.substring(pair.indexOf(':') + 1);

            assertThat(RecordFieldContract.declaredSizeMax(TransactionAddRequest.class, component))
                    .as("%s declares PIC X(%d) on COTRN02", field, COTRN02.widthOf(field))
                    .isEqualTo(COTRN02.widthOf(field));
        }

        @Test
        @DisplayName("a fully populated in-width payload raises no violation")
        void populatedPayloadIsValid() {
            assertThat(violationsOf(populated())).isEmpty();
        }

        @Test
        @DisplayName("an over-width component raises a violation naming it")
        void overWidthComponentIsRejected() {
            final TransactionAddRequest oversized = new TransactionAddRequest("CT021", null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);

            assertThat(violationsOf(oversized))
                    .singleElement()
                    .satisfies(violation -> {
                        assertThat(violation.getPropertyPath()).hasToString("transactionName");
                        assertThat(violation.getMessage()).contains("must not exceed 4 characters");
                    });
        }

        @Test
        @DisplayName("an all-absent payload raises no violation, because absence is legitimate")
        void allAbsentPayloadIsValid() {
            final TransactionAddRequest empty = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);

            assertThat(violationsOf(empty))
                    .as("the source tolerates an unpopulated screen field everywhere")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("2. The eight-versus-nine digit asymmetry, preserved not repaired")
    final class AmountAsymmetry {

        @Test
        @DisplayName("the edited mask renders eight integer digits")
        void maskRendersEightIntegerDigits() {
            final long integerDigits = TransactionAddRequest.AMOUNT_DISPLAY_MASK.chars()
                    .filter(character -> character == '9')
                    .count() - TransactionAddRequest.AMOUNT_SCALE;

            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK).isEqualTo("+99999999.99");
            assertThat(integerDigits).as("app/cbl/COTRN02C.cbl:59 WS-TRAN-AMT-E").isEqualTo(8L);
        }

        @Test
        @DisplayName("the mask occupies exactly the width the screen field declares")
        void maskFitsTheScreenField() {
            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK)
                    .as("TRNAMTI is PIC X(%d) on COTRN02", COTRN02.widthOf("TRNAMTI"))
                    .hasSize(COTRN02.widthOf("TRNAMTI"));
        }

        @Test
        @DisplayName("the numeric field admits nine integer digits, one more than the mask can render")
        void valueAdmitsNineIntegerDigits() {
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX).isEqualByComparingTo("999999999.99");
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.precision()
                    - TransactionAddRequest.AMOUNT_VALUE_MAX.scale())
                    .as("app/cbl/COTRN02C.cbl:58 WS-TRAN-AMT-N PIC S9(9)V99")
                    .isEqualTo(9);
        }

        @Test
        @DisplayName("the mask ceiling is strictly below the value ceiling, which is the asymmetry")
        void maskCeilingIsBelowValueCeiling() {
            // app/cbl/COTRN02C.cbl:385-386 moves the parsed value into the edited field and the edited field
            // straight back into the screen field, so an amount above the mask ceiling loses its leading
            // digit on the echo. The mask is deliberately not widened to nine digits.
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX)
                    .isEqualByComparingTo("99999999.99")
                    .isLessThan(TransactionAddRequest.AMOUNT_VALUE_MAX);
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.precision()
                    - TransactionAddRequest.AMOUNT_MASK_MAX.precision())
                    .as("exactly one digit is lost on the echo")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an amount between the two ceilings is representable but not renderable")
        void anAmountBetweenTheCeilingsExists() {
            final BigDecimal between = new BigDecimal("123456789.99");

            assertThat(between).isGreaterThan(TransactionAddRequest.AMOUNT_MASK_MAX);
            assertThat(between).isLessThanOrEqualTo(TransactionAddRequest.AMOUNT_VALUE_MAX);
        }

        @Test
        @DisplayName("the transaction precision is not the account precision")
        void transactionPrecisionIsNotAccountPrecision() {
            // CVTRA05Y declares TRAN-AMT as S9(09)V99 -> NUMERIC(11,2); CVACT01Y declares account balances
            // as S9(10)V99 -> NUMERIC(12,2). Conflating them silently widens or narrows a monetary field.
            assertThat(TransactionAddRequest.AMOUNT_PRECISION).isEqualTo(11);
            assertThat(TransactionAddRequest.AMOUNT_SCALE).isEqualTo(2);
            assertThat(TransactionAddRequest.AMOUNT_PRECISION)
                    .as("the transaction amount is one digit narrower than an account balance")
                    .isNotEqualTo(AccountDto.MONEY_PRECISION)
                    .isEqualTo(AccountDto.MONEY_PRECISION - 1);
        }

        @Test
        @DisplayName("the value ceiling is consistent with the declared precision and scale")
        void valueCeilingMatchesPrecisionAndScale() {
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.precision())
                    .isEqualTo(TransactionAddRequest.AMOUNT_PRECISION);
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.scale())
                    .isEqualTo(TransactionAddRequest.AMOUNT_SCALE);
        }

        @Test
        @DisplayName("rounding is banker's rounding, shared with the account payload")
        void roundingIsHalfEven() {
            assertThat(TransactionAddRequest.AMOUNT_ROUNDING_MODE)
                    .isEqualTo(RoundingMode.HALF_EVEN)
                    .isEqualTo(AccountDto.MONEY_ROUNDING);
        }
    }

    @Nested
    @DisplayName("3. The constraints deliberately not declared")
    final class DeliberateAbsences {

        @Test
        @DisplayName("the amount declares no digits constraint, so the tolerant parser still works")
        void amountDeclaresNoDigitsConstraint() {
            // app/cbl/COTRN02C.cbl:456-457 parses the amount with the currency-tolerant conversion, which
            // accepts a sign, a decimal point and grouping. A @Digits or digits-only @Pattern here would
            // reject input the source accepts.
            assertThat(RecordFieldContract.declares(TransactionAddRequest.class, "amount", Digits.class))
                    .isFalse();
            assertThat(RecordFieldContract.declares(TransactionAddRequest.class, "amount", Pattern.class))
                    .isFalse();
        }

        @Test
        @DisplayName("no component declares a non-null constraint")
        void noComponentIsMandatory() {
            assertThat(RecordFieldContract.componentNames(TransactionAddRequest.class))
                    .allSatisfy(component -> assertThat(
                            RecordFieldContract.declares(TransactionAddRequest.class, component,
                                    NotNull.class))
                            .as("%s must tolerate absence", component)
                            .isFalse());
        }

        @Test
        @DisplayName("no component declares a pattern constraint anywhere on the record")
        void noComponentDeclaresAPattern() {
            assertThat(RecordFieldContract.componentNames(TransactionAddRequest.class))
                    .allSatisfy(component -> assertThat(
                            RecordFieldContract.declares(TransactionAddRequest.class, component,
                                    Pattern.class))
                            .as("%s must not impose a format the screen does not", component)
                            .isFalse());
        }

        @Test
        @DisplayName("an amount carrying a sign and a separator raises no violation")
        void aToleratedAmountRaisesNoViolation() {
            final TransactionAddRequest tolerant = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, "+1,234.56", null, null, null, null, null,
                    null, null, null);

            assertThat(violationsOf(tolerant))
                    .as("the width is the only rule the boundary imposes on the amount")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("4. The date literal is a COBOL picture, not a Java pattern")
    final class DateFormatLiteral {

        @Test
        @DisplayName("the literal is the one the source hands to its date routine")
        void theLiteralIsTheSourceLiteral() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT)
                    .as("WS-DATE-FORMAT PIC X(10) at app/cbl/COTRN02C.cbl:60")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(10);
        }

        @Test
        @DisplayName("used as a Java pattern it silently produces a different date")
        void usedAsAJavaPatternItMisformats() {
            // This is why the constant must be passed to the date-validation service as data and never to a
            // DateTimeFormatter: Y is week-based-year and D is day-of-year, so the tenth of June renders as
            // day 161. It does not fail loudly; it produces a plausible wrong answer.
            final LocalDate tenthOfJune = LocalDate.of(2022, 6, 10);
            final String misformatted = DateTimeFormatter
                    .ofPattern(TransactionAddRequest.DATE_VALIDATION_FORMAT).format(tenthOfJune);

            assertThat(misformatted).isEqualTo("2022-06-161");
            assertThat(misformatted).isNotEqualTo("2022-06-10");
            assertThat(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(tenthOfJune))
                    .as("the correct Java pattern uses lower-case year and day-of-month")
                    .isEqualTo("2022-06-10");
        }

        @Test
        @DisplayName("the literal differs from the Java pattern that means the same thing")
        void theLiteralIsNotTheJavaPattern() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT)
                    .isNotEqualTo("yyyy-MM-dd")
                    .isEqualTo("YYYY-MM-DD".toUpperCase(java.util.Locale.ROOT));
        }

        @Test
        @DisplayName("the literal fits the date screen fields it validates")
        void theLiteralFitsTheDateFields() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT.length())
                    .isEqualTo(COTRN02.widthOf("TORIGDTI"))
                    .isEqualTo(COTRN02.widthOf("TPROCDTI"));
        }
    }

    @Nested
    @DisplayName("5. The diagnostic rendering withholds the card number")
    final class Redaction {

        @Test
        @DisplayName("only the account identifier and the program name are rendered")
        void onlyTwoFieldsAreRendered() {
            assertThat(populated().toString())
                    .isEqualTo("TransactionAddRequest[accountId=00000000001, programName=COTRN02C]");
        }

        @Test
        @DisplayName("the card number is omitted entirely, not masked or truncated")
        void cardNumberIsOmittedEntirely() {
            final String rendered = populated().toString();

            assertThat(rendered).doesNotContain("4111111111111111");
            assertThat(rendered).as("not truncated to a last four either").doesNotContain("1111");
            assertThat(rendered).as("no placeholder token derived from it").doesNotContain("*");
        }

        @Test
        @DisplayName("no other populated component reaches the rendering")
        void noOtherComponentIsRendered() {
            final String rendered = populated().toString();

            assertThat(rendered)
                    .doesNotContain("GROCERY PURCHASE")
                    .doesNotContain("ACME SUPERMARKET")
                    .doesNotContain("10001-1234")
                    .doesNotContain("POS TERMNL")
                    .doesNotContain("+00001234.56");
        }

        @Test
        @DisplayName("an absent value stays distinguishable from a blank one")
        void absenceIsDistinguishable() {
            final TransactionAddRequest empty = new TransactionAddRequest(null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null);

            assertThat(empty.toString())
                    .as("rendered exactly as received, without normalisation")
                    .isEqualTo("TransactionAddRequest[accountId=null, programName=null]");
        }
    }
}
