/*
 * ******************************************************************
 * Program     : AccountDtoApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the account-view read projection carried by
 *               com.cardemo.model.dto.AccountDto against the frozen
 *               symbolic map it was translated from. In particular it
 *               pins that all THIRTY-SEVEN component widths are
 *               ENFORCED rather than merely documented, that the
 *               enforcement rejects without repairing, that absent,
 *               empty, blank and LOW-VALUES all remain legitimate,
 *               that the three legacy-narrowed fields keep the map
 *               width and not the wider record width, and that no
 *               protected field can reach a log through toString.
 * Source      : app/cpy-bms/COACTVW.CPY (37 input fields, group
 *               CACTVWAI, lines 17-240) + app/cbl/COACTVWC.cbl
 *               + app/cpy/CVACT01Y.cpy + app/cpy/CVCUS01Y.cpy
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

import com.cardemo.model.dto.AccountDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression suite for {@link AccountDto}, the outbound account-view projection.
 *
 * <h2>What it does</h2>
 *
 * <p>It holds the projection to {@code app/cpy-bms/COACTVW.CPY}, the frozen symbolic map it was
 * translated from, and it exists because that map's contract is a set of thirty-seven fixed widths
 * which for a time were documented on every component and enforced on none. A record's implicit
 * constructor accepts any string, so an instance could carry an {@code addressStateCode} of seven
 * characters or an {@code errorMessage} of two hundred and still serialise cleanly while describing
 * a screen state the map cannot represent.</p>
 *
 * <p>Four properties are pinned that a plausible edit would silently destroy.</p>
 *
 * <p><strong>Every width is enforced, and enforced at the map's figure.</strong> Each of the
 * thirty-seven components is driven one character past its declared width and the construction is
 * required to fail. The declared widths are restated here from the copybook rather than read back
 * from the class, so a width edited in the production constant is caught rather than confirmed.</p>
 *
 * <p><strong>Three fields keep the narrower map width, not the wider record width.</strong>
 * {@code CUST-ADDR-ZIP PIC X(10)} moves into {@code ACSZIPCI PIC X(5)}
 * ({@code app/cbl/COACTVWC.cbl:515}) and both {@code CUST-PHONE-NUM-n PIC X(15)} fields move into
 * {@code ACSPHNnI PIC X(13)} ({@code :517-518}), so the legacy screen truncates. The map figure is
 * the contract for this payload; widening any of the three to the record figure would accept a value
 * the screen never displayed, and the tests below fail if that happens.</p>
 *
 * <p><strong>The guard rejects and never repairs.</strong> A value within its width is carried back
 * byte for byte - no trim, no pad, no case fold - and a value over its width raises rather than
 * being cut down. Absence stays legitimate in all four of its forms: {@code null}, the empty string,
 * blanks and {@code LOW-VALUES}. The source distinguishes all four and
 * {@code app/cbl/COACTVWC.cbl:466} writes {@code LOW-VALUES} deliberately, so collapsing any into
 * another would erase a distinction the consuming service reads.</p>
 *
 * <p><strong>No protected field can reach a log.</strong> The payload carries a social security
 * number, a date of birth, both telephone numbers, a government-issued identifier, an electronic
 * funds transfer account identifier and three customer names. {@code toString} must expose none of
 * them, and no failure message may quote a rejected value.</p>
 *
 * <h2>How to run it</h2>
 *
 * <p>{@code ./mvnw -B -o test -Dtest=AccountDtoApiContractTest} runs this class alone; {@code ./mvnw -B test}
 * runs it with the rest of the unit tier. It needs no container, no Spring context, no database and
 * no network, so it runs identically on a developer machine and in continuous integration.</p>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <p>A failure in {@link WidthEnforcement} means a declared width no longer matches the copybook, or
 * a guard was dropped from the canonical constructor. Re-read the cited line of
 * {@code app/cpy-bms/COACTVW.CPY} before changing the expectation: the copybook is frozen, so the
 * test is right and the code is wrong. A failure in {@link LegacyNarrowing} most likely means
 * someone widened a truncated field to its record width, which is a parity break rather than a
 * fix. A failure in {@link ProtectedData} means a protected value became loggable.</p>
 *
 * @see AccountDto
 */
@DisplayName("AccountDto - app/cpy-bms/COACTVW.CPY group CACTVWAI + app/cbl/COACTVWC.cbl")
final class AccountDtoApiContractTest {

    /** Component count of the record, restated from the copybook rather than read from the class. */
    private static final int COMPONENTS = 37;

    /**
     * The declared width of each component, restated from {@code app/cpy-bms/COACTVW.CPY}.
     *
     * <p>Arguments are the component name, the declared width, the COBOL field name and the copybook
     * line, so a failure message identifies the source of truth without a lookup.</p>
     *
     * @return one argument set per component, in map declaration order
     */
    private static Stream<Arguments> declaredWidths() {
        return Stream.of(
                Arguments.of("transactionName", 4, "TRNNAMEI", 24),
                Arguments.of("title01", 40, "TITLE01I", 30),
                Arguments.of("currentDate", 8, "CURDATEI", 36),
                Arguments.of("programName", 8, "PGMNAMEI", 42),
                Arguments.of("title02", 40, "TITLE02I", 48),
                Arguments.of("currentTime", 8, "CURTIMEI", 54),
                Arguments.of("accountId", 11, "ACCTSIDI", 60),
                Arguments.of("accountStatus", 1, "ACSTTUSI", 66),
                Arguments.of("openDate", 10, "ADTOPENI", 72),
                Arguments.of("creditLimit", 15, "ACRDLIMI", 78),
                Arguments.of("expiryDate", 10, "AEXPDTI", 84),
                Arguments.of("cashCreditLimit", 15, "ACSHLIMI", 90),
                Arguments.of("reissueDate", 10, "AREISDTI", 96),
                Arguments.of("currentBalance", 15, "ACURBALI", 102),
                Arguments.of("currentCycleCredit", 15, "ACRCYCRI", 108),
                Arguments.of("accountGroupId", 10, "AADDGRPI", 114),
                Arguments.of("currentCycleDebit", 15, "ACRCYDBI", 120),
                Arguments.of("customerId", 9, "ACSTNUMI", 126),
                Arguments.of("customerSsn", 12, "ACSTSSNI", 132),
                Arguments.of("customerDateOfBirth", 10, "ACSTDOBI", 138),
                Arguments.of("customerFicoScore", 3, "ACSTFCOI", 144),
                Arguments.of("customerFirstName", 25, "ACSFNAMI", 150),
                Arguments.of("customerMiddleName", 25, "ACSMNAMI", 156),
                Arguments.of("customerLastName", 25, "ACSLNAMI", 162),
                Arguments.of("addressLine1", 50, "ACSADL1I", 168),
                Arguments.of("addressStateCode", 2, "ACSSTTEI", 174),
                Arguments.of("addressLine2", 50, "ACSADL2I", 180),
                Arguments.of("addressZip", 5, "ACSZIPCI", 186),
                Arguments.of("addressCity", 50, "ACSCITYI", 192),
                Arguments.of("addressCountryCode", 3, "ACSCTRYI", 198),
                Arguments.of("phoneNumber1", 13, "ACSPHN1I", 204),
                Arguments.of("governmentIssuedId", 20, "ACSGOVTI", 210),
                Arguments.of("phoneNumber2", 13, "ACSPHN2I", 216),
                Arguments.of("eftAccountId", 10, "ACSEFTCI", 222),
                Arguments.of("primaryCardHolderIndicator", 1, "ACSPFLGI", 228),
                Arguments.of("informationMessage", 45, "INFOMSGI", 234),
                Arguments.of("errorMessage", 78, "ERRMSGI", 240));
    }

    /**
     * Returns the record's component names in declaration order.
     *
     * @return the thirty-seven component names
     */
    private static List<String> componentNames() {
        return Arrays.stream(AccountDto.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }

    /**
     * Builds an instance in which one named component holds {@code value} and every other is
     * {@code null}.
     *
     * @param componentName the component to populate
     * @param value         the value to place in it
     * @return the constructed payload
     * @throws ReflectiveOperationException if the canonical constructor cannot be invoked
     */
    private static AccountDto withOnly(String componentName, String value)
            throws ReflectiveOperationException {
        List<String> names = componentNames();
        int index = names.indexOf(componentName);
        assertThat(index)
                .withFailMessage("component %s is not declared on AccountDto", componentName)
                .isNotNegative();
        Object[] arguments = new Object[names.size()];
        arguments[index] = value;
        return canonicalConstructor().newInstance(arguments);
    }

    /**
     * Returns the record's canonical constructor.
     *
     * @return the thirty-seven argument constructor
     */
    private static Constructor<AccountDto> canonicalConstructor() {
        Class<?>[] parameterTypes = new Class<?>[COMPONENTS];
        Arrays.fill(parameterTypes, String.class);
        try {
            return AccountDto.class.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException cause) {
            throw new AssertionError("AccountDto no longer declares a 37-String canonical constructor",
                    cause);
        }
    }

    /**
     * Invokes the canonical constructor, unwrapping the reflective wrapper so that assertions can be
     * made against the exception the constructor actually threw.
     *
     * @param componentName the component to populate
     * @param value         the value to place in it
     * @return a runnable that constructs the payload and rethrows the constructor's own exception
     */
    private static org.junit.jupiter.api.function.ThrowingSupplier<AccountDto> construction(
            String componentName, String value) {
        return () -> {
            try {
                return withOnly(componentName, value);
            } catch (java.lang.reflect.InvocationTargetException wrapper) {
                if (wrapper.getCause() instanceof RuntimeException unwrapped) {
                    throw unwrapped;
                }
                throw wrapper;
            }
        };
    }

    @Nested
    @DisplayName("1. Field contract: 37 input fields of CACTVWAI")
    final class FieldContract {

        @Test
        @DisplayName("declares exactly the 37 input fields the copybook declares, in map order")
        void declaresExactlyThirtySevenComponentsInMapOrder() {
            List<String> expected = declaredWidths()
                    .map(arguments -> (String) arguments.get()[0])
                    .toList();
            assertThat(componentNames())
                    .as("app/cpy-bms/COACTVW.CPY declares 37 input fields between line 17 and line 240")
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("publishes the verified census of 37, not the specification's 36")
        void publishesTheVerifiedCensus() {
            assertThat(AccountDto.FIELD_COUNT).isEqualTo(COMPONENTS);
            assertThat(AccountDto.class.getRecordComponents()).hasSize(AccountDto.FIELD_COUNT);
        }

        @Test
        @DisplayName("carries every component as text, so leading zeros and LOW-VALUES survive")
        void everyComponentIsText() {
            assertThat(AccountDto.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType()).isEqualTo(String.class));
        }

        @Test
        @DisplayName("declares the account identifier as text although the map declares it numeric")
        void accountIdentifierIsTextDespiteNumericDeclaration() throws ReflectiveOperationException {
            AccountDto payload = withOnly("accountId", "00000000001");
            assertThat(payload.accountId())
                    .as("ACCTSIDI PIC 99999999999 at app/cpy-bms/COACTVW.CPY:60 is the corpus's sole "
                            + "numeric declaration of this field; a numeric Java type would drop the "
                            + "leading zeros the screen shows")
                    .isEqualTo("00000000001");
        }
    }

    @Nested
    @DisplayName("2. Width enforcement: every declared width is checked, not merely documented")
    final class WidthEnforcement {

        @ParameterizedTest(name = "{2} PIC({1}) at COACTVW.CPY:{3} accepts exactly {1} characters")
        @MethodSource("com.cardemo.unit.model.AccountDtoApiContractTest#declaredWidths")
        @DisplayName("accepts a value of exactly the declared width")
        void acceptsExactlyTheDeclaredWidth(String componentName, int width, String cobolField,
                int copybookLine) throws ReflectiveOperationException {
            String atLimit = "X".repeat(width);
            AccountDto payload = withOnly(componentName, atLimit);
            assertThat(readComponent(payload, componentName))
                    .as("%s is declared %s PIC X(%d) at app/cpy-bms/COACTVW.CPY:%d",
                            componentName, cobolField, width, copybookLine)
                    .isEqualTo(atLimit);
        }

        @ParameterizedTest(name = "{2} PIC({1}) at COACTVW.CPY:{3} rejects {1} plus one")
        @MethodSource("com.cardemo.unit.model.AccountDtoApiContractTest#declaredWidths")
        @DisplayName("rejects a value one character past the declared width")
        void rejectsOneCharacterPastTheDeclaredWidth(String componentName, int width, String cobolField,
                int copybookLine) {
            assertThatThrownBy(() -> construction(componentName, "X".repeat(width + 1)).get())
                    .as("%s must not accept more than the %d characters %s declares at "
                                    + "app/cpy-bms/COACTVW.CPY:%d",
                            componentName, width, cobolField, copybookLine)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(componentName)
                    .hasMessageContaining(cobolField)
                    .hasMessageContaining("COACTVW.CPY:" + copybookLine);
        }

        @ParameterizedTest(name = "{0} is rejected rather than truncated")
        @MethodSource("com.cardemo.unit.model.AccountDtoApiContractTest#declaredWidths")
        @DisplayName("rejects rather than repairing: no truncation, no padding, no case folding")
        void rejectsRatherThanRepairing(String componentName, int width, String cobolField,
                int copybookLine) throws ReflectiveOperationException {
            assertThat(cobolField).isNotBlank();
            assertThat(copybookLine).isPositive();
            assertThatThrownBy(() -> construction(componentName, "y".repeat(width * 3)).get())
                    .isInstanceOf(IllegalArgumentException.class);
            String shortValue = width == 1 ? "z" : "z".repeat(width - 1);
            assertThat(readComponent(withOnly(componentName, shortValue), componentName))
                    .as("a value inside its width is carried verbatim: not padded to the declared "
                            + "width and not case folded")
                    .isEqualTo(shortValue);
        }

        @Test
        @DisplayName("the guard message never reproduces the rejected value")
        void guardMessageNeverQuotesTheValue() {
            String secret = "123-45-6789-SECRET";
            assertThatThrownBy(() -> construction("customerSsn", secret).get())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("customerSsn")
                    .hasMessageNotContaining(secret)
                    .hasMessageNotContaining("SECRET");
        }

        @Test
        @DisplayName("the guard is on the canonical constructor, so every instance passes through it")
        void guardIsOnTheCanonicalConstructor() {
            assertThat(AccountDto.class.getDeclaredConstructors())
                    .as("a second constructor would be a second, unguarded way in")
                    .hasSize(1);
            assertThat(canonicalConstructor().getParameterCount()).isEqualTo(COMPONENTS);
        }

        @Test
        @DisplayName("every component is checked: none is left unguarded")
        void everyComponentIsGuarded() {
            List<String> unguarded = new ArrayList<>();
            for (String componentName : componentNames()) {
                int declared = declaredWidths()
                        .filter(arguments -> componentName.equals(arguments.get()[0]))
                        .map(arguments -> (Integer) arguments.get()[1])
                        .findFirst()
                        .orElseThrow();
                try {
                    construction(componentName, "Q".repeat(declared + 1)).get();
                    unguarded.add(componentName);
                } catch (IllegalArgumentException expected) {
                    assertThat(expected).hasMessageContaining(componentName);
                } catch (Throwable unexpected) {
                    throw new AssertionError("unexpected failure for " + componentName, unexpected);
                }
            }
            assertThat(unguarded)
                    .as("every one of the 37 map fields must have its width enforced")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("3. Legacy narrowing: the map width is the contract, never the record width")
    final class LegacyNarrowing {

        @ParameterizedTest(name = "{0} keeps its {1}-byte map width")
        @MethodSource
        @DisplayName("keeps the narrower map width for the three truncated fields")
        void keepsTheNarrowerMapWidth(String componentName, int mapWidth, int recordWidth,
                String recordField) {
            assertThat(mapWidth).isLessThan(recordWidth);
            assertThatThrownBy(() -> construction(componentName, "9".repeat(recordWidth)).get())
                    .as("%s is %s PIC X(%d) in the record but is narrowed by the legacy move; "
                                    + "accepting %d characters would accept a value the screen never "
                                    + "displayed",
                            componentName, recordField, recordWidth, recordWidth)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static Stream<Arguments> keepsTheNarrowerMapWidth() {
            return Stream.of(
                    Arguments.of("addressZip", 5, 10, "CUST-ADDR-ZIP"),
                    Arguments.of("phoneNumber1", 13, 15, "CUST-PHONE-NUM-1"),
                    Arguments.of("phoneNumber2", 13, 15, "CUST-PHONE-NUM-2"));
        }

        @Test
        @DisplayName("the postal code constant is five, matching the map and not the record")
        void postalCodeConstantIsFive() {
            assertThat(AccountDto.ZIP_LENGTH)
                    .as("ACSZIPCI PIC X(5) at app/cpy-bms/COACTVW.CPY:186 against "
                            + "CUST-ADDR-ZIP PIC X(10) at app/cpy/CVCUS01Y.cpy:11")
                    .isEqualTo(5);
        }

        @Test
        @DisplayName("the telephone constant is thirteen, matching the map and not the record")
        void telephoneConstantIsThirteen() {
            assertThat(AccountDto.PHONE_NUMBER_LENGTH)
                    .as("ACSPHN1I PIC X(13) at app/cpy-bms/COACTVW.CPY:204 against "
                            + "CUST-PHONE-NUM-1 PIC X(15) at app/cpy/CVCUS01Y.cpy:12")
                    .isEqualTo(13);
        }

        @Test
        @DisplayName("the social security display width is twelve, the field width and not the eleven "
                + "characters the STRING assembles")
        void socialSecurityDisplayWidthIsTwelve() {
            assertThat(AccountDto.SSN_DISPLAY_LENGTH)
                    .as("ACSTSSNI PIC X(12) at app/cpy-bms/COACTVW.CPY:132; the STRING at "
                            + "app/cbl/COACTVWC.cbl:496-504 assembles eleven characters into it")
                    .isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("4. Field states: absent, empty, blank and low-values stay distinct")
    final class FieldStates {

        @Test
        @DisplayName("accepts an all-null payload, because absence is a legitimate screen state")
        void acceptsAnAllNullPayload() throws ReflectiveOperationException {
            Object[] arguments = new Object[COMPONENTS];
            AccountDto payload = canonicalConstructor().newInstance(arguments);
            assertThat(componentNames())
                    .allSatisfy(name -> assertThat(readComponent(payload, name)).isNull());
        }

        @ParameterizedTest(name = "{0} accepts null, empty, blank and LOW-VALUES")
        @MethodSource("com.cardemo.unit.model.AccountDtoApiContractTest#declaredWidths")
        @DisplayName("accepts all four forms of absence on every component")
        void acceptsAllFourFormsOfAbsence(String componentName, int width, String cobolField,
                int copybookLine) throws ReflectiveOperationException {
            assertThat(cobolField).isNotBlank();
            assertThat(copybookLine).isPositive();
            assertThat(readComponent(withOnly(componentName, null), componentName)).isNull();
            assertThat(readComponent(withOnly(componentName, ""), componentName)).isEmpty();
            String blanks = " ".repeat(width);
            assertThat(readComponent(withOnly(componentName, blanks), componentName))
                    .isEqualTo(blanks);
            String lowValues = "\u0000".repeat(width);
            assertThat(readComponent(withOnly(componentName, lowValues), componentName))
                    .isEqualTo(lowValues);
        }

        @Test
        @DisplayName("keeps null, empty, blank and low-values distinguishable from one another")
        void keepsTheFourStatesDistinct() throws ReflectiveOperationException {
            assertThat(withOnly("accountStatus", null).accountStatus()).isNull();
            assertThat(withOnly("accountStatus", "").accountStatus()).isEmpty();
            assertThat(withOnly("accountStatus", " ").accountStatus()).isEqualTo(" ");
            assertThat(withOnly("accountStatus", "\u0000").accountStatus()).isEqualTo("\u0000");
        }

        @Test
        @DisplayName("blanks and low-values still count against the declared width")
        void whitespaceAndLowValuesStillCountAgainstTheWidth() {
            assertThatThrownBy(() -> construction("addressStateCode", "   ").get())
                    .as("three blanks cannot fit ACSSTTEI PIC X(2); blanks are not free")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> construction("addressStateCode", "\u0000\u0000\u0000").get())
                    .as("three binary zeros cannot fit ACSSTTEI PIC X(2) either")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("5. Monetary conversion: on demand, locale-independent, sign-preserving")
    final class MonetaryConversion {

        @Test
        @DisplayName("carries monetary display text unconverted on the record itself")
        void carriesDisplayTextUnconverted() throws ReflectiveOperationException {
            assertThat(withOnly("currentBalance", "     1,000.00 ").currentBalance())
                    .as("no parsing, formatting, trimming or case folding happens on ingest")
                    .isEqualTo("     1,000.00 ");
        }

        @ParameterizedTest(name = "\"{0}\" converts on demand")
        @ValueSource(strings = {"194.00", " 194.00 ", "-194.00", "0.00", "0000000194.00"})
        @DisplayName("converts a well-formed display amount on demand")
        void convertsAWellFormedAmount(String displayText) {
            assertThat(AccountDto.toAmount(displayText, "currentBalance"))
                    .isPresent()
                    .get(org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL)
                    .hasScaleOf(AccountDto.MONEY_SCALE);
        }

        @Test
        @DisplayName("preserves a negative amount and never normalises it to an absolute value")
        void preservesNegativeAmounts() {
            Optional<BigDecimal> debit = AccountDto.toAmount("-250.75", "currentCycleDebit");
            assertThat(debit).isPresent();
            assertThat(debit.orElseThrow())
                    .as("the current cycle debit legitimately accumulates negative amounts "
                            + "(app/cbl/CBTRN02C.cbl:547-552) and the over-limit arithmetic depends on "
                            + "that sign surviving")
                    .isEqualByComparingTo(new BigDecimal("-250.75"));
        }

        @Test
        @DisplayName("treats null, blanks and low-values as an absent amount")
        void treatsAbsenceAsAnAbsentAmount() {
            assertThat(AccountDto.toAmount(null, "creditLimit")).isEmpty();
            assertThat(AccountDto.toAmount("               ", "creditLimit")).isEmpty();
            assertThat(AccountDto.toAmount("\u0000\u0000\u0000", "creditLimit")).isEmpty();
            assertThat(AccountDto.toAmount("", "creditLimit")).isEmpty();
        }

        @Test
        @DisplayName("rejects grouping separators and currency symbols, which this screen never emits")
        void rejectsGroupingSeparatorsAndCurrencySymbols() {
            assertThatThrownBy(() -> AccountDto.toAmount("1,000.00", "creditLimit"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(NumberFormatException.class);
            assertThatThrownBy(() -> AccountDto.toAmount("$194.00", "creditLimit"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("rejects text longer than the fifteen-byte monetary field")
        void rejectsOverWideMonetaryText() {
            assertThatThrownBy(() -> AccountDto.toAmount("1".repeat(16), "creditLimit"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("creditLimit");
        }

        @Test
        @DisplayName("rejects an amount needing more than the PIC S9(10)V99 precision")
        void rejectsExcessivePrecision() {
            assertThatThrownBy(() -> AccountDto.toAmount("99999999999.99", "creditLimit"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("creditLimit");
        }

        @Test
        @DisplayName("requires a field name so that a failure can be attributed without quoting a value")
        void requiresAFieldName() {
            assertThatThrownBy(() -> AccountDto.toAmount("194.00", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AccountDto.toAmount("194.00", "  "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("applies banker's rounding, so conversion is idempotent and order-independent")
        void appliesBankersRounding() {
            assertThat(AccountDto.MONEY_ROUNDING).isEqualTo(java.math.RoundingMode.HALF_EVEN);
            assertThat(AccountDto.toAmount("1.005", "creditLimit").orElseThrow())
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(AccountDto.toAmount("1.015", "creditLimit").orElseThrow())
                    .isEqualByComparingTo(new BigDecimal("1.02"));
        }

        @Test
        @DisplayName("declares the money geometry the PIC S9(10)V99 record field imposes")
        void declaresTheMoneyGeometry() {
            assertThat(AccountDto.MONEY_DISPLAY_LENGTH).isEqualTo(15);
            assertThat(AccountDto.MONEY_SCALE).isEqualTo(2);
            assertThat(AccountDto.MONEY_PRECISION).isEqualTo(12);
        }
    }

    @Nested
    @DisplayName("6. Protected data: never emitted, never logged, never quoted")
    final class ProtectedData {

        @Test
        @DisplayName("toString omits every protected field")
        void toStringOmitsEveryProtectedField() throws ReflectiveOperationException {
            Object[] arguments = new Object[COMPONENTS];
            List<String> names = componentNames();
            Map<String, String> protectedValues = Map.of(
                    "customerSsn", "123-45-6789",
                    "customerDateOfBirth", "1975-04-12",
                    "phoneNumber1", "(555)867-5309",
                    "phoneNumber2", "(555)555-1234",
                    "governmentIssuedId", "GOVTID9876543210",
                    "eftAccountId", "EFT0001234",
                    "customerFirstName", "FNAMEAA1",
                    "customerMiddleName", "ANNE",
                    "customerLastName", "LNM1");
            protectedValues.forEach((name, value) -> arguments[names.indexOf(name)] = value);
            arguments[names.indexOf("accountId")] = "00000000001";
            arguments[names.indexOf("accountStatus")] = "Y";
            arguments[names.indexOf("programName")] = "COACTVWC";
            String rendered = canonicalConstructor().newInstance(arguments).toString();
            assertThat(rendered)
                    .contains("00000000001", "Y", "COACTVWC")
                    .contains("protectedFieldsOmitted=true");
            assertThat(protectedValues.values())
                    .allSatisfy(value -> assertThat(rendered)
                            .as("a record's generated toString would emit every component; the "
                                    + "override exists so that none of these can reach a log")
                            .doesNotContain(value));
        }

        @Test
        @DisplayName("declares no password or hash member, and must not acquire one")
        void declaresNoCredentialMember() {
            assertThat(componentNames())
                    .as("app/cpy-bms/COACTVW.CPY declares no credential field; least privilege "
                            + "forbids inventing one")
                    .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("password")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("secret")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("hash")
                            || name.toLowerCase(java.util.Locale.ROOT).contains("credential"));
        }

        @Test
        @DisplayName("does not implement Serializable, so it is not exposed to native deserialization")
        void doesNotImplementSerializable() {
            assertThat(Serializable.class.isAssignableFrom(AccountDto.class)).isFalse();
        }

        @Test
        @DisplayName("declares no mutable static field, so nothing leaks across requests")
        void declaresNoMutableStaticField() {
            assertThat(AccountDto.class.getDeclaredFields())
                    .filteredOn(field -> Modifier.isStatic(field.getModifiers()))
                    .allSatisfy(field -> assertThat(Modifier.isFinal(field.getModifiers()))
                            .withFailMessage("static field %s must be final", field.getName())
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("7. Serialized surface: 37 map fields and nothing else")
    final class SerializedSurface {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        @DisplayName("emits exactly the 37 component names and no structural helper")
        void emitsExactlyTheThirtySevenComponents() throws ReflectiveOperationException {
            Object[] arguments = new Object[COMPONENTS];
            AccountDto payload = canonicalConstructor().newInstance(arguments);
            @SuppressWarnings("unchecked")
            Map<String, Object> serialized = mapper.convertValue(payload, Map.class);
            assertThat(serialized.keySet())
                    .as("field count, declared widths and the low-values marker are internal "
                            + "constants and must never surface as JSON properties")
                    .containsExactlyInAnyOrderElementsOf(componentNames());
            assertThat(serialized.keySet())
                    .doesNotContain("fieldCount", "moneyScale", "moneyPrecision", "amount");
        }

        @Test
        @DisplayName("round-trips through JSON, so a client can return what it was given")
        void roundTripsThroughJson() throws Exception {
            AccountDto original = withOnly("accountId", "00000000001");
            AccountDto restored = mapper.readValue(mapper.writeValueAsString(original),
                    AccountDto.class);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("enforces widths on the deserialization path as well as on direct construction")
        void enforcesWidthsOnDeserialization() {
            String hostile = "{\"addressStateCode\":\"TOO-WIDE\"}";
            assertThatThrownBy(() -> mapper.readValue(hostile, AccountDto.class))
                    .as("binding runs through the canonical constructor, so the guard applies there "
                            + "too")
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("exposes the declared widths as constants so the census is machine-checkable")
        void exposesDeclaredWidthsAsConstants() {
            assertThat(AccountDto.TRANSACTION_NAME_LENGTH).isEqualTo(4);
            assertThat(AccountDto.TITLE_LENGTH).isEqualTo(40);
            assertThat(AccountDto.CURRENT_DATE_LENGTH).isEqualTo(8);
            assertThat(AccountDto.PROGRAM_NAME_LENGTH).isEqualTo(8);
            assertThat(AccountDto.CURRENT_TIME_LENGTH).isEqualTo(8);
            assertThat(AccountDto.ACCOUNT_ID_LENGTH).isEqualTo(11);
            assertThat(AccountDto.ACCOUNT_STATUS_LENGTH).isEqualTo(1);
            assertThat(AccountDto.DATE_TEXT_LENGTH).isEqualTo(10);
            assertThat(AccountDto.ACCOUNT_GROUP_ID_LENGTH).isEqualTo(10);
            assertThat(AccountDto.CUSTOMER_ID_LENGTH).isEqualTo(9);
            assertThat(AccountDto.FICO_SCORE_LENGTH).isEqualTo(3);
            assertThat(AccountDto.NAME_LENGTH).isEqualTo(25);
            assertThat(AccountDto.ADDRESS_LINE_LENGTH).isEqualTo(50);
            assertThat(AccountDto.STATE_CODE_LENGTH).isEqualTo(2);
            assertThat(AccountDto.COUNTRY_CODE_LENGTH).isEqualTo(3);
            assertThat(AccountDto.GOVERNMENT_ID_LENGTH).isEqualTo(20);
            assertThat(AccountDto.EFT_ACCOUNT_ID_LENGTH).isEqualTo(10);
            assertThat(AccountDto.CARD_HOLDER_INDICATOR_LENGTH).isEqualTo(1);
            assertThat(AccountDto.INFORMATION_MESSAGE_LENGTH).isEqualTo(45);
            assertThat(AccountDto.ERROR_MESSAGE_LENGTH).isEqualTo(78);
        }

        @Test
        @DisplayName("does not share the information or error width with any other map")
        void doesNotShareMessageWidthsAcrossMaps() {
            assertThat(AccountDto.INFORMATION_MESSAGE_LENGTH)
                    .as("INFOMSGI is X(45) here, X(40) on app/cpy-bms/COCRDSL.CPY:96")
                    .isEqualTo(45)
                    .isNotEqualTo(40);
            assertThat(AccountDto.ERROR_MESSAGE_LENGTH)
                    .as("ERRMSGI is X(78) here, X(80) on app/cpy-bms/COCRDSL.CPY:102")
                    .isEqualTo(78)
                    .isNotEqualTo(80);
        }
    }

    /**
     * Reads one component of a payload by name.
     *
     * @param payload       the payload to read
     * @param componentName the component to read
     * @return the component's value, which may be {@code null}
     * @throws ReflectiveOperationException if the component's backing field cannot be read
     */
    private static String readComponent(AccountDto payload, String componentName)
            throws ReflectiveOperationException {
        Field field = AccountDto.class.getDeclaredField(componentName);
        field.setAccessible(true);
        return (String) field.get(payload);
    }
}
