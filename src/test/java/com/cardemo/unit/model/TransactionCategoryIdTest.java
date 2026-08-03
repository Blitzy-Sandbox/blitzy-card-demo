/*
 * ******************************************************************
 * Program     : TransactionCategoryIdTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that
 *               com.cardemo.model.key.TransactionCategoryId reproduces
 *               the TRAN-CAT-KEY composite key contract of the
 *               transaction-category record - two components in COBOL
 *               declaration order summing to the catalogued key length
 *               of 6 bytes, an EXACT width guard on the type code, the
 *               four-digit range guard on the category code, and the
 *               protected no-argument constructor that keeps the JPA
 *               contract without offering an unvalidated public one.
 * Source      : app/cpy/CVTRA04Y.cpy:L5-L7 @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (TRANCATG cluster, key length 6)
 * Source      : app/cbl/CBACT04C.cbl:L482-L483 @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.model.key.TransactionCategoryId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test for {@link TransactionCategoryId}, the {@code @Embeddable} replacement for the two-component
 * {@code TRAN-CAT-KEY} group of the transaction-category record layout.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds {@link TransactionCategoryId} to the field contract of the frozen legacy corpus, read first
 * hand at the traceability anchor commit {@code 7756d89}:
 *
 * <ol>
 *   <li><strong>The component set is closed and ordered.</strong> {@code app/cpy/CVTRA04Y.cpy:L5-L7}
 *       declares {@code 05 TRAN-CAT-KEY.} followed by exactly two subordinate items in this order:
 *       {@code 10 TRAN-TYPE-CD PIC X(02).} and {@code 10 TRAN-CAT-CD PIC 9(04).} Note that the group name
 *       {@code TRAN-CAT-KEY} is shared with {@code app/cpy/CVTRA01Y.cpy} while the component list is
 *       different - two items here against three there - so the two keys are distinct types and this test
 *       asserts the two-component shape explicitly rather than inheriting an assumption.</li>
 *   <li><strong>The widths sum to the catalogued key length.</strong> 2 + 4 = 6 bytes, exactly the key
 *       length the catalogue records for the {@code TRANCATG} cluster, and the shortest key in the
 *       corpus.</li>
 *   <li><strong>The type-code guard demands an EXACT width.</strong> Unlike
 *       {@link com.cardemo.model.key.DisclosureGroupId}, whose character guard permits a value shorter than
 *       its PIC width, this class rejects any type code whose length is not exactly two. That difference is
 *       asserted here as observed behaviour on both sides, so neither class is silently assumed to follow
 *       the other.</li>
 *   <li><strong>The no-argument constructor is protected, not public.</strong> JPA needs a no-argument
 *       constructor, but making it public would offer application code a way to build a key that has passed
 *       no guard at all. The reduced visibility keeps the JPA contract while leaving the validating
 *       two-argument constructor as the only route open to callers, and this test asserts that visibility
 *       by reflection.</li>
 *   <li><strong>The literals the interest program writes are valid keys.</strong>
 *       {@code app/cbl/CBACT04C.cbl:L482-L483} moves the literals {@code '01'} and {@code '05'} into the
 *       type and category fields of every generated interest transaction, so that exact pair is exercised.</li>
 *   </ol>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test                                          # whole unit tier
 * ./mvnw -B -ntp -o test -Dtest=TransactionCategoryIdTest          # this class alone
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. No Spring context, no connection, no external resource, therefore no configuration.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The exact-width assertion fails.</strong> The guard was relaxed to a maximum. Decide
 *       deliberately: the copybook width is two, and a one-character type code cannot round-trip.</li>
 *   <li><strong>The constructor-visibility assertion fails.</strong> The no-argument constructor was made
 *       public, which re-opens the unvalidated construction path.</li>
 *   <li><strong>The key-length assertion fails.</strong> A component width drifted from its PIC clause.</li>
 * </ul>
 *
 * @see TransactionCategoryId
 */
class TransactionCategoryIdTest {

    /** {@code TRAN-TYPE-CD PIC X(02)} - app/cpy/CVTRA04Y.cpy:L6. */
    private static final int TRAN_TYPE_CD_WIDTH = 2;

    /** {@code TRAN-CAT-CD PIC 9(04)} - app/cpy/CVTRA04Y.cpy:L7, four digits wide. */
    private static final int TRAN_CAT_CD_WIDTH = 4;

    /** The key length the catalogue records for the TRANCATG cluster: 2 + 4. */
    private static final int CATALOGUED_KEY_LENGTH = 6;

    /** The literal type and category pair every generated interest transaction carries. */
    private static TransactionCategoryId interestTransactionKey() {
        return new TransactionCategoryId("01", 5);
    }

    @Test
    @DisplayName("declares exactly the two TRAN-CAT-KEY components, in copybook order and no others")
    void declaresExactlyTheTwoCopybookComponentsInOrder() {
        assertThat(persistentFieldNames())
                .as("app/cpy/CVTRA04Y.cpy:L5-L7 declares TRAN-TYPE-CD then TRAN-CAT-CD and nothing else; "
                        + "the group name TRAN-CAT-KEY is shared with CVTRA01Y but that key has three "
                        + "components, so this shape is asserted explicitly")
                .containsExactly("tranTypeCd", "tranCatCd");
    }

    @Test
    @DisplayName("is @Embeddable and Serializable, as a JPA composite key must be")
    void isEmbeddableAndSerializable() {
        assertThat(TransactionCategoryId.class.getAnnotation(Embeddable.class)).isNotNull();
        assertThat(Serializable.class).isAssignableFrom(TransactionCategoryId.class);
    }

    @Test
    @DisplayName("the no-argument constructor exists for JPA but is protected, not public")
    void theNoArgumentConstructorIsProtectedRatherThanPublic() throws NoSuchMethodException {
        final Constructor<TransactionCategoryId> constructor =
                TransactionCategoryId.class.getDeclaredConstructor();

        assertThat(Modifier.isProtected(constructor.getModifiers()))
                .as("JPA needs a no-argument constructor, but a PUBLIC one would offer callers a key that "
                        + "has passed no width or range guard; protected keeps the provider contract while "
                        + "leaving the validating constructor as the only route open to application code")
                .isTrue();
        assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse();
    }

    @Test
    @DisplayName("the protected constructor still yields an instance with both components unset")
    void theProtectedConstructorYieldsAnInstanceWithBothComponentsUnset() throws ReflectiveOperationException {
        final Constructor<TransactionCategoryId> constructor =
                TransactionCategoryId.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final TransactionCategoryId blank = constructor.newInstance();

        assertThat(blank.getTranTypeCd()).isNull();
        assertThat(blank.getTranCatCd()).isNull();
    }

    @Test
    @DisplayName("the two component widths sum to the catalogued TRANCATG key length of 6 bytes")
    void componentWidthsSumToTheCataloguedKeyLength() {
        assertThat(TRAN_TYPE_CD_WIDTH + TRAN_CAT_CD_WIDTH)
                .as("TRAN-TYPE-CD X(02) + TRAN-CAT-CD 9(04) is the 6-byte key the catalogue records for "
                        + "TRANCATG, the shortest key in the corpus")
                .isEqualTo(CATALOGUED_KEY_LENGTH);
    }

    @Test
    @DisplayName("maps the type code to tran_type_cd at the exact PIC X(02) width and NOT NULL")
    void mapsTheTypeCodeAtItsPicWidth() throws NoSuchFieldException {
        final Column column =
                TransactionCategoryId.class.getDeclaredField("tranTypeCd").getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("tran_type_cd");
        assertThat(column.length()).isEqualTo(TRAN_TYPE_CD_WIDTH);
        assertThat(column.nullable()).isFalse();
    }

    @Test
    @DisplayName("maps the category code to a numeric(4) column, matching PIC 9(04) rather than a wider type")
    void mapsTheCategoryCodeToANumericFourColumn() throws NoSuchFieldException {
        final Column column =
                TransactionCategoryId.class.getDeclaredField("tranCatCd").getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("tran_cat_cd");
        assertThat(column.columnDefinition())
                .as("PIC 9(04) is four digits, so the column is declared numeric(4); a wider column would "
                        + "accept a value the legacy record could not hold")
                .isEqualTo("numeric(4)");
        assertThat(column.nullable()).isFalse();
    }

    @Test
    @DisplayName("accepts the '01'/5 pair that CBACT04C writes onto every generated interest transaction")
    void acceptsTheInterestTransactionLiteralPair() {
        final TransactionCategoryId key = interestTransactionKey();

        assertThat(key.getTranTypeCd())
                .as("app/cbl/CBACT04C.cbl:L482 moves the literal '01' into TRAN-TYPE-CD")
                .isEqualTo("01")
                .hasSize(TRAN_TYPE_CD_WIDTH);
        assertThat(key.getTranCatCd())
                .as("app/cbl/CBACT04C.cbl:L483 moves the literal '05' into TRAN-CAT-CD")
                .isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"00", "01", "02", "05", "99", "AB", "  "})
    @DisplayName("accepts any type code of exactly two characters, blanks included, carried verbatim")
    void acceptsAnyTypeCodeOfExactlyTwoCharacters(final String twoCharacterCode) {
        assertThat(new TransactionCategoryId(twoCharacterCode, 1).getTranTypeCd())
                .as("PIC X(02) is alphanumeric, so the guard is a width guard and not a numeric one; the "
                        + "value is carried verbatim without trimming")
                .isEqualTo(twoCharacterCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "1", "001", "0123", "     "})
    @DisplayName("rejects any type code whose width is not exactly two: the guard is EXACT, not a maximum")
    void rejectsAnyTypeCodeThatIsNotExactlyTwoCharacters(final String wrongWidthCode) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("this guard demands an exact width, unlike DisclosureGroupId which permits a shorter "
                        + "value; '%s' has length %d", wrongWidthCode, wrongWidthCode.length())
                .isThrownBy(() -> new TransactionCategoryId(wrongWidthCode, 1))
                .withMessageContaining("tranTypeCd")
                .withMessageContaining("TRAN-TYPE-CD")
                .withMessageContaining(String.valueOf(TRAN_TYPE_CD_WIDTH));
    }

    @Test
    @DisplayName("rejects a null type code, naming the Java field and the COBOL PIC clause")
    void rejectsANullTypeCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TransactionCategoryId(null, 1))
                .withMessageContaining("tranTypeCd")
                .withMessageContaining("TRAN-TYPE-CD")
                .withMessageContaining("must not be null");
    }

    @Test
    @DisplayName("rejects a null category code")
    void rejectsANullCategoryCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new TransactionCategoryId("01", null))
                .withMessageContaining("tranCatCd")
                .withMessageContaining("TRAN-CAT-CD")
                .withMessageContaining("must not be null");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 9, 99, 1000, 9998, 9999})
    @DisplayName("accepts every category code inside the four-digit PIC 9(04) domain, boundaries included")
    void acceptsEveryCategoryCodeInsideTheFourDigitDomain(final int inDomainCode) {
        assertThat(new TransactionCategoryId("01", inDomainCode).getTranCatCd()).isEqualTo(inDomainCode);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -5, 10_000, 12_345, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @DisplayName("rejects every category code outside PIC 9(04), including negatives and five-digit values")
    void rejectsEveryCategoryCodeOutsideTheFourDigitDomain(final int outOfDomainCode) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("PIC 9(04) is unsigned and four digits wide, so %d cannot round-trip", outOfDomainCode)
                .isThrownBy(() -> new TransactionCategoryId("01", outOfDomainCode))
                .withMessageContaining("tranCatCd")
                .withMessageContaining(String.valueOf(outOfDomainCode));
    }

    @Test
    @DisplayName("is equal to itself and to a separately built instance carrying the same two components")
    void isEqualToAnIdenticallyValuedInstance() {
        final TransactionCategoryId first = interestTransactionKey();
        final TransactionCategoryId second = interestTransactionKey();

        assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest
    @CsvSource({"02, 5", "01, 6"})
    @DisplayName("differs when either component differs, so identity is total over both")
    void differsWhenEitherComponentDiffers(final String typeCd, final int catCd) {
        assertThat(interestTransactionKey()).isNotEqualTo(new TransactionCategoryId(typeCd, catCd));
    }

    @Test
    @DisplayName("is unequal to null and to a foreign type, without throwing")
    void isUnequalToNullAndToAForeignType() {
        final TransactionCategoryId key = interestTransactionKey();

        assertThat(key.equals(null)).isFalse();
        assertThat(key.equals("015")).isFalse();
        assertThat(key).isNotEqualTo(new Object());
    }

    @Test
    @DisplayName("behaves as a hash key: a set de-duplicates equal keys and a map resolves by value")
    void behavesAsAHashKey() {
        final Set<TransactionCategoryId> set = new HashSet<>();
        set.add(interestTransactionKey());
        set.add(interestTransactionKey());
        set.add(new TransactionCategoryId("01", 6));

        assertThat(set).hasSize(2);

        final Map<TransactionCategoryId, String> descriptions = new HashMap<>();
        descriptions.put(interestTransactionKey(), "Interest");

        assertThat(descriptions.get(interestTransactionKey())).isEqualTo("Interest");
    }

    @Test
    @DisplayName("hashCode is stable across repeated calls on the same instance")
    void hashCodeIsStableAcrossRepeatedCalls() {
        final TransactionCategoryId key = interestTransactionKey();
        final int first = key.hashCode();

        assertThat(key.hashCode()).isEqualTo(first);
        assertThat(key.hashCode()).isEqualTo(first);
    }

    @Test
    @DisplayName("toString names the type and renders both components for diagnostics")
    void toStringNamesTheTypeAndBothComponents() {
        assertThat(interestTransactionKey().toString())
                .isEqualTo("TransactionCategoryId[tranTypeCd=01, tranCatCd=5]");
    }

    @Test
    @DisplayName("round-trips through Java serialization with identity preserved")
    void roundTripsThroughJavaSerialization() throws IOException, ClassNotFoundException {
        final TransactionCategoryId original = interestTransactionKey();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        final Object restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = in.readObject();
        }

        assertThat(restored).isEqualTo(original).hasSameHashCodeAs(original);
    }

    @Test
    @DisplayName("declares a serialVersionUID, so the serialized form is pinned rather than computed")
    void declaresAPinnedSerialVersionUid() throws NoSuchFieldException {
        final Field uid = TransactionCategoryId.class.getDeclaredField("serialVersionUID");

        assertThat(Modifier.isStatic(uid.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(uid.getModifiers())).isTrue();
        assertThat(uid.getType()).isEqualTo(long.class);
    }

    @Test
    @DisplayName("carries no mutator, so a persisted key cannot be altered in place")
    void carriesNoMutator() {
        assertThat(TransactionCategoryId.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    /** Declared instance field names in declaration order, excluding synthetic and static members. */
    private static String[] persistentFieldNames() {
        return Arrays.stream(TransactionCategoryId.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toArray(String[]::new);
    }
}
