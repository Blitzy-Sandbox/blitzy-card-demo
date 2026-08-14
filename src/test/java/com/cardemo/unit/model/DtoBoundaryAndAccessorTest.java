/*
 * ******************************************************************
 * Program     : DtoBoundaryAndAccessorTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Closes the residual contract surface of the DTO
 *               package: the hand-written accessor pairs on the two
 *               account-update snapshot groups, the row-array
 *               positional rules, the amount magnitude ceilings, and
 *               the diagnostic renderings of the nested types.
 * Source      : app/cbl/COACTUPC.cbl   (669-756, 757-849 snapshots)
 *               app/cbl/COCRDLIC.cbl   (177-178 row slots)
 *               app/cbl/COTRN00C.cbl   (65-68 row slots)
 *               app/cpy/CVTRA05Y.cpy   (TRAN-AMT PIC S9(09)V99)
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

import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.dto.CardDto;
import com.cardemo.model.dto.MenuResponse;
import com.cardemo.model.dto.StatementTransaction;
import com.cardemo.model.dto.TransactionDto;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Closes the residual contract surface of the DTO package.
 *
 * <p>The two account-update snapshot groups carry roughly forty hand-written setter and getter pairs each,
 * written out one field at a time because the source declares them one field at a time. The characteristic
 * defect in code shaped like that is not a missing method but a transposed one - a setter that assigns the
 * neighbouring field, which no round-trip through that same field would ever reveal. The first two groups
 * here therefore do two things per pair: they round-trip the value, and they assert that setting one field
 * leaves <em>every other</em> paired field untouched. That second assertion is what catches a transposition.
 *
 * <p>The remaining groups cover the boundaries that carry real business rules rather than storage: the
 * positional row-array contracts taken from the two list screens, the amount magnitude ceilings taken from
 * {@code PIC S9(09)V99}, and the diagnostic renderings of the nested types.
 */
@DisplayName("DTO package: accessor pairs, positional row arrays, amount ceilings and nested renderings")
final class DtoBoundaryAndAccessorTest {

    /** The two snapshot group types, whose accessor pairs are generated from the same COBOL shape. */
    private static final List<Class<?>> SNAPSHOT_GROUPS =
            List.of(AccountUpdateRequest.OldDetails.class, AccountUpdateRequest.NewDetails.class);

    private static List<String> snapshotGroupNames() {
        return SNAPSHOT_GROUPS.stream().map(Class::getSimpleName).toList();
    }

    private static Class<?> groupNamed(final String simpleName) {
        return SNAPSHOT_GROUPS.stream()
                .filter(candidate -> candidate.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no snapshot group named " + simpleName));
    }

    /**
     * Names the canonical all-arguments constructor of a snapshot group.
     *
     * <p>The two groups are immutable: {@code app/cbl/COACTUPC.cbl} captures the snapshot once, at
     * {@code 9000-READ-DATA}, and {@code 9700-CHECK-CHANGE-IN-REC} compares it later, so a payload that
     * could be rewritten between those two points would not be a snapshot at all. There is therefore no
     * setter to pair with a reader, and the constructor is the only way in - which is what makes it the
     * right place to detect a transposed field.
     *
     * @param type the snapshot group type
     * @return the widest public constructor the type declares
     */
    private static Constructor<?> canonicalConstructor(final Class<?> type) {
        return Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow(() -> new AssertionError("no public constructor on " + type.getName()));
    }

    /**
     * Pairs every constructor parameter with the accessor that reads the field it initialises.
     *
     * @param type the snapshot group type
     * @return parameter name to reader, in constructor declaration order
     */
    private static Map<String, Method> readersByParameter(final Class<?> type) {
        final Map<String, Method> readers = new LinkedHashMap<>();
        for (final Parameter parameter : canonicalConstructor(type).getParameters()) {
            final String name = parameter.getName();
            final String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            readerFor(type, suffix, parameter.getType()).ifPresent(reader -> readers.put(name, reader));
        }
        return readers;
    }

    private static Optional<Method> readerFor(final Class<?> type, final String suffix,
            final Class<?> valueType) {
        for (final String candidate : List.of("get" + suffix,
                Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1))) {
            try {
                final Method reader = type.getMethod(candidate);
                if (reader.getReturnType().equals(valueType)) {
                    return Optional.of(reader);
                }
            } catch (final NoSuchMethodException absent) {
                continue;
            }
        }
        return Optional.empty();
    }

    /**
     * Produces a distinctive value for a constructor parameter type.
     *
     * @param valueType the declared parameter type
     * @param ordinal   the parameter's position, so that no two parameters receive the same value
     * @return a value of that type, distinct from every other position's
     */
    private static Object sampleValue(final Class<?> valueType, final int ordinal) {
        if (valueType.equals(String.class)) {
            // Two characters, so the value stays inside the narrowest PIC clause on either group while
            // remaining unique across all of them.
            return Character.toString((char) ('A' + ordinal / 26)) + (char) ('A' + ordinal % 26);
        }
        if (valueType.equals(Integer.class)) {
            return Integer.valueOf(300 + ordinal);
        }
        if (valueType.equals(Long.class)) {
            return Long.valueOf(451L + ordinal);
        }
        throw new AssertionError("no sample value defined for " + valueType.getName()
                + "; extend sampleValue so the accessor is still exercised");
    }

    /**
     * Builds a snapshot group through its canonical constructor.
     *
     * @param type      the snapshot group type
     * @param arguments one value per constructor parameter, in declaration order
     * @return the constructed instance
     */
    private static Object construct(final Class<?> type, final Object... arguments) {
        try {
            return canonicalConstructor(type).newInstance(arguments);
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("cannot construct " + type.getName(), failure);
        }
    }

    /**
     * Builds a snapshot group whose every parameter carries a value unique to its position.
     *
     * @param type the snapshot group type
     * @return the constructed instance and the value handed to each parameter, by parameter name
     */
    private static Map.Entry<Object, Map<String, Object>> constructDistinct(final Class<?> type) {
        final Parameter[] parameters = canonicalConstructor(type).getParameters();
        final Object[] arguments = new Object[parameters.length];
        final Map<String, Object> byName = new LinkedHashMap<>();
        for (int index = 0; index < parameters.length; index++) {
            arguments[index] = sampleValue(parameters[index].getType(), index);
            byName.put(parameters[index].getName(), arguments[index]);
        }
        return Map.entry(construct(type, arguments), byName);
    }

    /**
     * Builds a snapshot group in which exactly one field carries a value.
     *
     * @param type      the snapshot group type
     * @param parameter the constructor parameter to populate
     * @param value     the value to hand it
     * @return the constructed instance
     */
    private static Object constructWithOnly(final Class<?> type, final String parameter,
            final Object value) {
        final Parameter[] parameters = canonicalConstructor(type).getParameters();
        final Object[] arguments = new Object[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            if (parameters[index].getName().equals(parameter)) {
                arguments[index] = value;
            }
        }
        return construct(type, arguments);
    }

    /**
     * Builds a snapshot group in which every parameter is absent.
     *
     * @param type the snapshot group type
     * @return the constructed instance
     */
    private static Object constructAbsent(final Class<?> type) {
        return construct(type, new Object[canonicalConstructor(type).getParameterCount()]);
    }

    private static Object invoke(final Method method, final Object target, final Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (final IllegalAccessException | InvocationTargetException failure) {
            throw new AssertionError("cannot invoke " + method.getName(), failure);
        }
    }

    /**
     * Builds a card list row for a given position.
     *
     * <p>Row one carries a null selector type deliberately: {@code app/cpy-bms/COCRDLI.CPY} declares
     * {@code CRDSTP2I} through {@code CRDSTP7I} and declares no {@code CRDSTP1I} anywhere, so the first row
     * group has four fields where the other six have five. Passing a value here would be refused.
     *
     * @param rowNumber the one-based position within the page
     * @return a row valid for that position
     */
    private static CardDto.CardListRow cardRow(final int rowNumber) {
        final String selectorType = rowNumber == CardDto.ROW_NUMBER_WITHOUT_SELECTOR_TYPE ? null : "T";
        return new CardDto.CardListRow(rowNumber, "S", selectorType, "00000000001", "4111111111111111",
                "Y");
    }

    private static CardDto cardListWith(final List<CardDto.CardListRow> rows) {
        return CardDto.list("CCLI", "CardDemo Card List", "08/01/26", "COCRDLIC", "Card List",
                "14:22:31", "001", "00000000001", "4111111111111111", rows, "", "");
    }

    private static TransactionDto transactionListWith(final List<TransactionDto.TransactionListRow> rows) {
        return new TransactionDto("CT00", "CardDemo Transaction List", "08/01/26", "COTRN00C",
                "Transaction List", "14:22:31", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, "", "00000001", rows, null);
    }

    @Nested
    @DisplayName("1. Every snapshot field is readable through its own accessor, and only its own")
    final class AccessorRoundTrip {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.DtoBoundaryAndAccessorTest#snapshotGroupNames")
        @DisplayName("the group exposes a reader for every constructor parameter")
        void readersAreDiscovered(final String groupName) {
            // A guard on the reflection itself. If the naming convention changed and no reader were found,
            // the round-trip and isolation tests below would pass by examining nothing at all.
            final Class<?> type = groupNamed(groupName);
            final Map<String, Method> readers = readersByParameter(type);

            // The two groups are deliberately different sizes: the OLD group carries the 29 members
            // 9000-READ-DATA snapshots, the NEW group the 35 that 1200-EDIT-MAP-INPUTS derives, and the
            // six extra NEW members are the decomposed views the source declares only on that side. A
            // single shared count would hide a member going missing from either one.
            assertThat(readers)
                    .as("%s must expose a reader per snapshot field", groupName)
                    .hasSize("OldDetails".equals(groupName) ? 29 : 35);
            assertThat(readers)
                    .as("every constructor parameter of %s must be readable, or a field is write-only "
                            + "and the comparison could never see it", groupName)
                    .hasSize(canonicalConstructor(type).getParameterCount());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.DtoBoundaryAndAccessorTest#snapshotGroupNames")
        @DisplayName("each constructor argument is readable through its own reader")
        void everyFieldRoundTrips(final String groupName) {
            final Class<?> type = groupNamed(groupName);
            final Map.Entry<Object, Map<String, Object>> built = constructDistinct(type);

            readersByParameter(type).forEach((name, reader) ->
                    assertThat(invoke(reader, built.getKey()))
                            .as("%s.%s reads the value handed to parameter %s", groupName,
                                    reader.getName(), name)
                            .isEqualTo(built.getValue().get(name)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.DtoBoundaryAndAccessorTest#snapshotGroupNames")
        @DisplayName("an absent group reads absent in every field")
        void absentGroupIsEntirelyAbsent(final String groupName) {
            final Class<?> type = groupNamed(groupName);
            final Object instance = constructAbsent(type);

            readersByParameter(type).forEach((name, reader) ->
                    assertThat(invoke(reader, instance))
                            .as("%s.%s on a group constructed entirely absent", groupName, reader.getName())
                            .isNull());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.DtoBoundaryAndAccessorTest#snapshotGroupNames")
        @DisplayName("no reader returns a neighbouring field's value, so no field is transposed")
        void noFieldIsTransposed(final String groupName) {
            // The characteristic defect in a class with forty hand-written fields is not a missing accessor
            // but a transposed one - a reader that returns the neighbouring field. Because every parameter
            // is handed a value unique to its position, a transposition shows up as a reader returning
            // some other position's value rather than its own.
            final Class<?> type = groupNamed(groupName);
            final Map.Entry<Object, Map<String, Object>> built = constructDistinct(type);
            final Map<String, Object> expected = built.getValue();

            readersByParameter(type).forEach((name, reader) -> {
                final Object read = invoke(reader, built.getKey());
                assertThat(expected.entrySet().stream()
                        .filter(candidate -> !candidate.getKey().equals(name))
                        .filter(candidate -> candidate.getValue().equals(read))
                        .map(Map.Entry::getKey)
                        .toList())
                        .as("%s.%s returned the value belonging to another field", groupName,
                                reader.getName())
                        .isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("2. The compact date component views, on every date they apply to")
    final class CompactDateComponents {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.DtoBoundaryAndAccessorTest#snapshotGroupNames")
        @DisplayName("every year, month and day view decomposes at offsets 1, 5 and 7")
        void componentsDecomposeAtTheCompactOffsets(final String groupName) {
            // The snapshot holds each date compact, PIC X(08) with no separators, so its components sit at
            // 1, 5 and 7 - not at the 1, 6 and 9 of the dash-separated live record. Comparing the two whole
            // strings would report a change on every request; app/cbl/COACTUPC.cbl:4109-4193 compares the
            // components instead, and these views are what make that possible.
            final Class<?> type = groupNamed(groupName);
            int examined = 0;

            for (final String field : readersByParameter(type).keySet()) {
                final Optional<Method> year = componentAccessor(type, field, "Year");
                final Optional<Method> month = componentAccessor(type, field, "Month");
                final Optional<Method> day = componentAccessor(type, field, "Day");
                if (year.isEmpty() || month.isEmpty() || day.isEmpty()) {
                    continue;
                }

                final Object instance = constructWithOnly(type, field, "20200115");

                assertThat(invoke(year.orElseThrow(), instance)).as("%s year", field).isEqualTo("2020");
                assertThat(invoke(month.orElseThrow(), instance)).as("%s month", field).isEqualTo("01");
                assertThat(invoke(day.orElseThrow(), instance)).as("%s day", field).isEqualTo("15");
                examined++;
            }

            assertThat(examined)
                    .as("%s must expose at least one compact date with all three components", groupName)
                    .isPositive();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.cardemo.unit.model.DtoBoundaryAndAccessorTest#snapshotGroupNames")
        @DisplayName("an absent date yields absent components rather than an exception")
        void absentDateYieldsAbsentComponents(final String groupName) {
            final Class<?> type = groupNamed(groupName);
            final Object absent = constructAbsent(type);
            int examined = 0;

            for (final String field : readersByParameter(type).keySet()) {
                final Optional<Method> year = componentAccessor(type, field, "Year");
                if (year.isEmpty()) {
                    continue;
                }

                assertThat(invoke(year.orElseThrow(), absent))
                        .as("%s.%s on a group constructed entirely absent", groupName,
                                year.orElseThrow().getName())
                        .isNull();
                examined++;
            }

            assertThat(examined).isPositive();
        }

        private Optional<Method> componentAccessor(final Class<?> type, final String field,
                final String part) {
            try {
                return Optional.of(type.getMethod(field + part));
            } catch (final NoSuchMethodException absent) {
                return Optional.empty();
            }
        }
    }

    @Nested
    @DisplayName("3. The card list row array is positional, and says so when it is not")
    final class CardRowArray {

        @Test
        @DisplayName("a full page of consecutively numbered rows is accepted")
        void aFullPageIsAccepted() {
            final List<CardDto.CardListRow> rows = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= 7; rowNumber++) {
                rows.add(cardRow(rowNumber));
            }

            assertThat(cardListWith(rows).getRows()).hasSize(7);
        }

        @Test
        @DisplayName("more rows than the screen declares is refused, citing the source")
        void anOverfullPageIsRefused() {
            final List<CardDto.CardListRow> rows = new ArrayList<>();
            for (int rowNumber = 1; rowNumber <= 7; rowNumber++) {
                rows.add(cardRow(rowNumber));
            }
            rows.add(cardRow(1));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cardListWith(rows))
                    .withMessageContaining("at most 7")
                    .withMessageContaining("COCRDLIC.cbl:177-178");
        }

        @Test
        @DisplayName("a null element is refused, naming its index")
        void aNullElementIsRefused() {
            final List<CardDto.CardListRow> rows = new ArrayList<>();
            rows.add(cardRow(1));
            rows.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cardListWith(rows))
                    .withMessageContaining("must not contain a null element")
                    .withMessageContaining("index 1");
        }

        @Test
        @DisplayName("a row carrying the wrong number for its position is refused")
        void aMisnumberedRowIsRefused() {
            final List<CardDto.CardListRow> rows = new ArrayList<>();
            rows.add(cardRow(1));
            rows.add(cardRow(3));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cardListWith(rows))
                    .withMessageContaining("must be positional")
                    .withMessageContaining("index 1 must carry row number 2 but carries 3");
        }

        @Test
        @DisplayName("the array must start at the map's first row number")
        void theArrayStartsAtOne() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> cardListWith(List.of(cardRow(2))))
                    .withMessageContaining("index 0 must carry row number 1");
        }

        @Test
        @DisplayName("an empty row array is a list projection that matched nothing")
        void anEmptyArrayIsAccepted() {
            assertThat(cardListWith(List.of()).getRows()).isEmpty();
        }
    }

    @Nested
    @DisplayName("4. The transaction list row array")
    final class TransactionRowArray {

        @Test
        @DisplayName("a full page of ten rows is accepted")
        void aFullPageIsAccepted() {
            final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
            for (int slot = 0; slot < 10; slot++) {
                rows.add(new TransactionDto.TransactionListRow("S", "0000000000000001", "08/01/26",
                        "GROCERY", "1234.56"));
            }

            assertThat(transactionListWith(rows).rows()).hasSize(10);
        }

        @Test
        @DisplayName("more rows than the screen declares is refused")
        void anOverfullPageIsRefused() {
            final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
            for (int slot = 0; slot <= 10; slot++) {
                rows.add(new TransactionDto.TransactionListRow("S", "0000000000000001", "08/01/26",
                        "GROCERY", "1234.56"));
            }

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> transactionListWith(rows))
                    .withMessageContaining("exceeds the 10")
                    .withMessageContaining("row slots");
        }

        @Test
        @DisplayName("a null element is refused, naming its index")
        void aNullElementIsRefused() {
            final List<TransactionDto.TransactionListRow> rows = new ArrayList<>();
            rows.add(new TransactionDto.TransactionListRow("S", "0000000000000001", "08/01/26",
                    "GROCERY", "1234.56"));
            rows.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> transactionListWith(rows))
                    .withMessageContaining("must not contain a null element")
                    .withMessageContaining("index 1");
        }

        @Test
        @DisplayName("the row's own rendering names only the transaction identifier")
        void rowRenderingIsNarrow() {
            final TransactionDto.TransactionListRow row = new TransactionDto.TransactionListRow("S",
                    "0000000000000001", "08/01/26", "GROCERY", "1234.56");

            assertThat(row.toString())
                    .isEqualTo("TransactionDto.TransactionListRow[transactionId=0000000000000001]");
            assertThat(row.toString()).doesNotContain("GROCERY").doesNotContain("1234.56");
        }
    }

    @Nested
    @DisplayName("5. The amount magnitude ceiling of PIC S9(09)V99")
    final class AmountCeiling {

        @Test
        @DisplayName("the widest transaction amount is accepted")
        void widestAmountIsAccepted() {
            assertThat(transactionListWith(List.of()).rows()).isEmpty();
            assertThat(new TransactionDto("CT01", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    new BigDecimal("999999999.99")).amountValue())
                    .isEqualByComparingTo("999999999.99");
        }

        @Test
        @DisplayName("an amount needing a tenth integer digit is refused")
        void tenIntegerDigitsAreRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionDto("CT01", null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, new BigDecimal("1000000000.00")))
                    .withMessageContaining("amountValue exceeds the 9");
        }

        @Test
        @DisplayName("the ceiling is symmetric, so a large negative amount is refused too")
        void theCeilingIsSymmetric() {
            // abs() bounds the magnitude symmetrically and never rewrites the sign of the stored value, so a
            // legitimate negative amount inside the ceiling survives with its sign.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionDto("CT01", null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null, null,
                            null, null, new BigDecimal("-1000000000.00")))
                    .withMessageContaining("amountValue exceeds the 9");
            assertThat(new TransactionDto("CT01", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    new BigDecimal("-999999999.99")).amountValue())
                    .isNegative();
        }

        @Test
        @DisplayName("the statement record applies the same ceiling")
        void theStatementRecordAppliesTheSameCeiling() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> statementWith(new BigDecimal("1000000000.00")))
                    .withMessageContaining("amount exceeds the 9");
            assertThat(statementWith(new BigDecimal("999999999.99")).amount())
                    .isEqualByComparingTo("999999999.99");
        }

        @Test
        @DisplayName("amount comparison treats two absent amounts as the same and one as different")
        void absentAmountsCompare() {
            final StatementTransaction withAmount = statementWith(new BigDecimal("10.00"));
            final StatementTransaction withoutAmount = statementWith(null);

            assertThat(withoutAmount.hasSameAmountAs(null)).isTrue();
            assertThat(withoutAmount.hasSameAmountAs(new BigDecimal("10.00"))).isFalse();
            assertThat(withAmount.hasSameAmountAs(null)).isFalse();
            assertThat(withAmount.hasSameAmountAs(new BigDecimal("10.000")))
                    .as("value equality, not representational equality")
                    .isTrue();
        }

        private StatementTransaction statementWith(final BigDecimal amount) {
            return new StatementTransaction("4111111111111111", "0000000000000001", "01", "0001",
                    "POS", "GROCERY", amount, "000000042", "ACME", "NEW YORK", "10001",
                    "2022-06-10-19.27.53.123000", "2022-06-11-19.27.53.123000", "");
        }
    }

    @Nested
    @DisplayName("6. The nested renderings and the card group guards")
    final class NestedRenderings {

        @Test
        @DisplayName("the statement record renders only its transaction identifier")
        void statementRenderingIsNarrow() {
            final StatementTransaction statement = new StatementTransaction("4111111111111111",
                    "0000000000000001", "01", "0001", "POS", "GROCERY", new BigDecimal("10.00"),
                    "000000042", "ACME", "NEW YORK", "10001", "2022-06-10-19.27.53.123000",
                    "2022-06-11-19.27.53.123000", "");

            assertThat(statement.toString())
                    .isEqualTo("StatementTransaction[transactionId=0000000000000001]");
            assertThat(statement.toString())
                    .as("the card number must not reach a diagnostic rendering")
                    .doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("the transaction payload's rendering omits the card number entirely")
        void transactionRenderingIsNarrow() {
            final TransactionDto detail = new TransactionDto("CT01", "CardDemo Transaction View",
                    "08/01/26", "COTRN01C", "Transaction View", "14:22:31", "0000000000000001",
                    "0000000000000001", "4111111111111111", "01", "0001", "POS", "GROCERY", "1234.56",
                    "08/01/26", "08/02/26", "000000042", "ACME", "NEW YORK", "10001", "", null, null,
                    null);

            assertThat(detail.toString()).isEqualTo("TransactionDto[transactionId=0000000000000001, "
                    + "programName=COTRN01C, protectedFieldsOmitted=true]");
            assertThat(detail.toString())
                    .as("not in full, not masked, and not as a last-four")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("1111")
                    .doesNotContain("GROCERY")
                    .doesNotContain("ACME");
        }

        @Test
        @DisplayName("the composite key renders only its transaction identifier")
        void keyRenderingIsNarrow() {
            final StatementTransaction.Key key =
                    new StatementTransaction.Key("4111111111111111", "0000000000000001");

            assertThat(key.toString())
                    .isEqualTo("StatementTransaction.Key[transactionId=0000000000000001]");
            assertThat(key.toString()).doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("every menu factory yields a menu type, which is why the constructor's null guard "
                + "cannot fire")
        void everyMenuFactoryYieldsAMenuType() {
            // MenuResponse's constructor is private with exactly four call sites, and all four pass a
            // MenuType enum literal, so its menuType null guard is unreachable through the public API. That
            // is a property worth asserting rather than a line worth reaching by reflection: this test fails
            // the moment a fifth factory is added that does not establish the discriminator.
            assertThat(List.of(MenuResponse.mainMenu(), MenuResponse.ofMainMenu(List.of()),
                            MenuResponse.adminMenu(), MenuResponse.ofAdminMenu(List.of())))
                    .allSatisfy(response -> assertThat(response.getMenuType()).isNotNull())
                    .hasSize(4);
        }

        @Test
        @DisplayName("the card group renders a count rather than its contents")
        void cardGroupRenderingIsACount() {
            final StatementTransaction.CardGroup group =
                    new StatementTransaction.CardGroup("4111111111111111", List.of());

            assertThat(group.toString())
                    .isEqualTo("StatementTransaction.CardGroup[transactionCount=0]");
        }

        @Test
        @DisplayName("a card group refuses a null transaction list, directing the caller to an empty one")
        void cardGroupRefusesANullList() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.CardGroup("4111111111111111", null))
                    .withMessageContaining("use an empty list instead");
        }

        @Test
        @DisplayName("a card group refuses a null element")
        void cardGroupRefusesANullElement() {
            final List<StatementTransaction> transactions = new ArrayList<>();
            transactions.add(null);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new StatementTransaction.CardGroup("4111111111111111", transactions))
                    .withMessageContaining("must not contain a null element");
        }

        @Test
        @DisplayName("a row outside the seven declared groups is refused")
        void aRowOutsideTheDeclaredGroupsIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(8, "S", "T", "00000000001",
                            "4111111111111111", "Y"))
                    .withMessageContaining("must be between 1 and 7")
                    .withMessageContaining("COCRDLI.CPY");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(0, "S", null, "00000000001",
                            "4111111111111111", "Y"))
                    .withMessageContaining("must be between 1 and 7");
        }

        @Test
        @DisplayName("row one refuses a selector type, because the map declares no CRDSTP1I")
        void rowOneRefusesASelectorType() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CardDto.CardListRow(1, "S", "T", "00000000001",
                            "4111111111111111", "Y"))
                    .withMessageContaining("must be null for row 1")
                    .withMessageContaining("declares no CRDSTP1I anywhere");
            assertThat(cardRow(2).getSelectorType())
                    .as("row two does declare CRDSTP2I, so a value is legitimate there")
                    .isEqualTo("T");
        }

        @Test
        @DisplayName("the two list-only header accessors read absent on a list payload")
        void listOnlyAccessorsReadAbsent() {
            final CardDto card = cardListWith(List.of());

            assertThat(card.getCardStatusCode())
                    .as("the list map carries status per row, not once per payload")
                    .isNull();
            assertThat(card.getFunctionKeys())
                    .as("the list map declares no function-key field")
                    .isNull();
        }

        @Test
        @DisplayName("the card payload's header accessors return what was supplied")
        void cardHeaderAccessorsRoundTrip() {
            final CardDto card = cardListWith(List.of());

            assertThat(card.getTransactionName()).isEqualTo("CCLI");
            assertThat(card.getTitle01()).isEqualTo("CardDemo Card List");
            assertThat(card.getCurrentDate()).isEqualTo("08/01/26");
            assertThat(card.getProgramName()).isEqualTo("COCRDLIC");
            assertThat(card.getTitle02()).isEqualTo("Card List");
            assertThat(card.getCurrentTime()).isEqualTo("14:22:31");
            assertThat(card.getPageNumber()).isEqualTo("001");
            assertThat(card.getAccountId()).isEqualTo("00000000001");
            assertThat(card.getCardNumber()).isEqualTo("4111111111111111");
        }
    }
}
