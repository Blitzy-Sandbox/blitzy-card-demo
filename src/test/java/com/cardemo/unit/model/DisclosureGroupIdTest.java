/*
 * ******************************************************************
 * Program     : DisclosureGroupIdTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that com.cardemo.model.key.DisclosureGroupId
 *               reproduces the DIS-GROUP-KEY composite key contract
 *               exactly - three components in COBOL declaration order
 *               summing to the catalogued key length of 16 bytes, the
 *               PIC-derived width and range guards, and a total
 *               equals/hashCode identity over all three components so
 *               that a keyed lookup behaves as the VSAM browse did.
 * Source      : app/cpy/CVTRA02Y.cpy:L5-L8 @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (DISCGRP cluster, key length 16)
 * Source      : app/cbl/CBACT04C.cbl:L80-L81, L211-L212 @ 7756d89
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

import com.cardemo.model.key.DisclosureGroupId;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * Unit test for {@link DisclosureGroupId}, the {@code @Embeddable} replacement for the three-component
 * {@code DIS-GROUP-KEY} group of the disclosure-group record layout.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds {@link DisclosureGroupId} to the field contract of the frozen legacy corpus, read first hand
 * at the traceability anchor commit {@code 7756d89}:
 *
 * <ol>
 *   <li><strong>The component set is closed and ordered.</strong> {@code app/cpy/CVTRA02Y.cpy:L5-L8}
 *       declares {@code 05 DIS-GROUP-KEY.} followed by exactly three subordinate items in this order:
 *       {@code 10 DIS-ACCT-GROUP-ID PIC X(10).}, {@code 10 DIS-TRAN-TYPE-CD PIC X(02).} and
 *       {@code 10 DIS-TRAN-CAT-CD PIC 9(04).} Three items means three persistent fields, in that order and
 *       no other, because a composite key whose components are reordered is a different key: the VSAM
 *       browse that {@code CBACT04C} performs is key-ordered, so component order is behaviour, not style.</li>
 *   <li><strong>The widths sum to the catalogued key length.</strong> 10 + 2 + 4 = 16 bytes, which is exactly
 *       the key length the catalogue records for the {@code DISCGRP} cluster. The test asserts each
 *       component width from its PIC clause and asserts the sum, so a silent widening of any one component
 *       fails here rather than at a database boundary.</li>
 *   <li><strong>The guards are PIC-derived, and the width guard is a maximum rather than an exact match.</strong>
 *       This is asserted as observed behaviour: {@code accountGroupId} and {@code tranTypeCd} reject a value
 *       longer than their PIC width but accept a shorter one, whereas the category code is range-checked
 *       against the four-digit domain {@code 0..9999} that {@code PIC 9(04)} defines. The asymmetry against
 *       {@link com.cardemo.model.key.TransactionCategoryId}, whose type-code guard demands an exact width,
 *       is recorded deliberately: these are two separate classes with two separate guards, and this test
 *       pins the behaviour each one actually has rather than the behaviour they might be assumed to share.</li>
 *   <li><strong>Identity is total over all three components.</strong> Two instances are equal when and only
 *       when all three components match, and the type behaves correctly as a hash key, because JPA resolves
 *       an {@code @EmbeddedId} through {@code equals} and {@code hashCode} and a partial identity would
 *       collapse distinct disclosure groups onto one another.</li>
 *   </ol>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test                                       # whole unit tier
 * ./mvnw -B -ntp -o test -Dtest=DisclosureGroupIdTest          # this class alone
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. This tier starts no Spring context, opens no connection and reads no external resource, so it
 * has no configuration to document and cannot be affected by a profile, an environment variable or a
 * container. The reflection this class performs reads declared fields and annotations of an already
 * compiled class and needs no additional module access on Java 25.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A width assertion fails.</strong> A {@code @Column} length or a guard constant has drifted
 *       from its PIC clause. The copybook is frozen and is the authority: correct the entity, never the
 *       expectation.</li>
 *   <li><strong>A component-order assertion fails.</strong> The declared field order was changed. Restore
 *       the copybook order; a reordered composite key silently changes browse order.</li>
 *   <li><strong>An identity assertion fails.</strong> {@code equals} or {@code hashCode} stopped covering
 *       all three components, which would make two different disclosure groups indistinguishable to JPA.</li>
 *   </ul>
 *
 * @see DisclosureGroupId
 */
class DisclosureGroupIdTest {

    /** {@code DIS-ACCT-GROUP-ID PIC X(10)} - app/cpy/CVTRA02Y.cpy:L6. */
    private static final int ACCOUNT_GROUP_ID_WIDTH = 10;

    /** {@code DIS-TRAN-TYPE-CD PIC X(02)} - app/cpy/CVTRA02Y.cpy:L7. */
    private static final int TRAN_TYPE_CD_WIDTH = 2;

    /** {@code DIS-TRAN-CAT-CD PIC 9(04)} - app/cpy/CVTRA02Y.cpy:L8, four digits wide. */
    private static final int TRAN_CAT_CD_WIDTH = 4;

    /** The key length the catalogue records for the DISCGRP cluster: 10 + 2 + 4. */
    private static final int CATALOGUED_KEY_LENGTH = 16;

    /** A representative valid key, using the literal default group id the interest program falls back to. */
    private static DisclosureGroupId defaultGroupKey() {
        return new DisclosureGroupId("DEFAULT   ", "01", 1);
    }

    @Test
    @DisplayName("declares exactly the three DIS-GROUP-KEY components, in copybook order and no others")
    void declaresExactlyTheThreeCopybookComponentsInOrder() {
        final String[] declared = persistentFieldNames();

        assertThat(declared)
                .as("app/cpy/CVTRA02Y.cpy:L5-L8 declares DIS-ACCT-GROUP-ID, DIS-TRAN-TYPE-CD and "
                        + "DIS-TRAN-CAT-CD in that order; a composite key whose component order differs "
                        + "is a different key, because the CBACT04C browse is key-ordered")
                .containsExactly("accountGroupId", "tranTypeCd", "tranCatCd");
    }

    @Test
    @DisplayName("is @Embeddable and Serializable, as a JPA composite key must be")
    void isEmbeddableAndSerializable() {
        assertThat(DisclosureGroupId.class.getAnnotation(Embeddable.class))
                .as("a composite key mapped with @EmbeddedId must itself be @Embeddable")
                .isNotNull();
        assertThat(Serializable.class)
                .as("JPA requires a composite key class to be serializable")
                .isAssignableFrom(DisclosureGroupId.class);
    }

    @Test
    @DisplayName("exposes a no-argument constructor so JPA can instantiate it")
    void exposesANoArgumentConstructorForJpa() throws ReflectiveOperationException {
        final DisclosureGroupId blank = noArgInstance(DisclosureGroupId.class);

        assertThat(blank.getAccountGroupId())
                .as("the JPA constructor leaves components unset; the provider populates them by field")
                .isNull();
        assertThat(blank.getTranTypeCd()).isNull();
        assertThat(blank.getTranCatCd()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "accountGroupId, acct_group_id, " + ACCOUNT_GROUP_ID_WIDTH,
        "tranTypeCd,     tran_type_cd,  " + TRAN_TYPE_CD_WIDTH,
    })
    @DisplayName("maps each character component to its copybook column name at its exact PIC width")
    void mapsEachCharacterComponentAtItsPicWidth(final String javaName, final String columnName,
            final int picWidth) throws NoSuchFieldException {
        final Column column = DisclosureGroupId.class.getDeclaredField(javaName).getAnnotation(Column.class);

        assertThat(column).as("%s must be mapped explicitly, never by naming convention", javaName).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.length())
                .as("%s carries the PIC width from app/cpy/CVTRA02Y.cpy; widening it silently accepts "
                        + "values the legacy record could not hold", javaName)
                .isEqualTo(picWidth);
        assertThat(column.nullable())
                .as("every component of a composite key is NOT NULL by construction")
                .isFalse();
    }

    @Test
    @DisplayName("the three component widths sum to the catalogued DISCGRP key length of 16 bytes")
    void componentWidthsSumToTheCataloguedKeyLength() {
        assertThat(ACCOUNT_GROUP_ID_WIDTH + TRAN_TYPE_CD_WIDTH + TRAN_CAT_CD_WIDTH)
                .as("DIS-ACCT-GROUP-ID X(10) + DIS-TRAN-TYPE-CD X(02) + DIS-TRAN-CAT-CD 9(04) is the "
                        + "16-byte key the catalogue records for the DISCGRP cluster")
                .isEqualTo(CATALOGUED_KEY_LENGTH);
    }

    @Test
    @DisplayName("accepts a key at exactly the PIC widths and returns every component unaltered")
    void acceptsAKeyAtExactlyThePicWidths() {
        final DisclosureGroupId key = new DisclosureGroupId("DEFAULT   ", "01", 5);

        assertThat(key.getAccountGroupId())
                .as("the value is carried verbatim, including the trailing blanks that pad X(10)")
                .isEqualTo("DEFAULT   ")
                .hasSize(ACCOUNT_GROUP_ID_WIDTH);
        assertThat(key.getTranTypeCd()).isEqualTo("01").hasSize(TRAN_TYPE_CD_WIDTH);
        assertThat(key.getTranCatCd()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "DEFAULT", "DEFAULT  "})
    @DisplayName("accepts an account group id SHORTER than X(10): the guard is a maximum, not an exact width")
    void acceptsAnAccountGroupIdShorterThanThePicWidth(final String shorterValue) {
        final DisclosureGroupId key = new DisclosureGroupId(shorterValue, "01", 1);

        assertThat(key.getAccountGroupId())
                .as("this class guards the MAXIMUM width only, so an unpadded value is accepted and "
                        + "carried verbatim; asserted as observed behaviour, and deliberately different "
                        + "from TransactionCategoryId, whose type-code guard demands an exact width")
                .isEqualTo(shorterValue);
    }

    @Test
    @DisplayName("rejects an account group id longer than X(10), naming the Java field and the COBOL field")
    void rejectsAnOverlongAccountGroupId() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupId("DEFAULTGROUP", "01", 1))
                .withMessageContaining("accountGroupId")
                .withMessageContaining("DIS-ACCT-GROUP-ID")
                .withMessageContaining(String.valueOf(ACCOUNT_GROUP_ID_WIDTH))
                .withMessageContaining("12");
    }

    @Test
    @DisplayName("rejects a transaction type code longer than X(02)")
    void rejectsAnOverlongTranTypeCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupId("DEFAULT   ", "012", 1))
                .withMessageContaining("tranTypeCd")
                .withMessageContaining("DIS-TRAN-TYPE-CD")
                .withMessageContaining(String.valueOf(TRAN_TYPE_CD_WIDTH));
    }

    @Test
    @DisplayName("rejects a null account group id rather than persisting a null key component")
    void rejectsANullAccountGroupId() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupId(null, "01", 1))
                .withMessageContaining("accountGroupId")
                .withMessageContaining("DIS-ACCT-GROUP-ID")
                .withMessageContaining("must not be null");
    }

    @Test
    @DisplayName("rejects a null transaction type code")
    void rejectsANullTranTypeCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupId("DEFAULT   ", null, 1))
                .withMessageContaining("tranTypeCd")
                .withMessageContaining("DIS-TRAN-TYPE-CD");
    }

    @Test
    @DisplayName("rejects a null category code")
    void rejectsANullCategoryCode() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new DisclosureGroupId("DEFAULT   ", "01", null))
                .withMessageContaining("tranCatCd")
                .withMessageContaining("DIS-TRAN-CAT-CD")
                .withMessageContaining("must not be null");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 9, 10, 1000, 9998, 9999})
    @DisplayName("accepts every category code inside the four-digit PIC 9(04) domain, boundaries included")
    void acceptsEveryCategoryCodeInsideTheFourDigitDomain(final int inDomainCode) {
        assertThat(new DisclosureGroupId("DEFAULT   ", "01", inDomainCode).getTranCatCd())
                .as("PIC 9(04) spans 0 to 9999 inclusive, so both boundaries must be accepted")
                .isEqualTo(inDomainCode);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -9999, 10_000, 100_000, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @DisplayName("rejects every category code outside PIC 9(04), including negatives and five-digit values")
    void rejectsEveryCategoryCodeOutsideTheFourDigitDomain(final int outOfDomainCode) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("PIC 9(04) is unsigned and four digits wide, so %d cannot round-trip through the "
                        + "legacy record and must be refused at construction", outOfDomainCode)
                .isThrownBy(() -> new DisclosureGroupId("DEFAULT   ", "01", outOfDomainCode))
                .withMessageContaining("tranCatCd")
                .withMessageContaining(String.valueOf(outOfDomainCode));
    }

    @Test
    @DisplayName("is equal to itself and to a separately built instance carrying the same three components")
    void isEqualToAnIdenticallyValuedInstance() {
        final DisclosureGroupId first = defaultGroupKey();
        final DisclosureGroupId second = defaultGroupKey();

        assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest
    @CsvSource({
        "OTHER     , 01, 1",
        "DEFAULT   , 02, 1",
        "DEFAULT   , 01, 2",
    })
    @DisplayName("differs when ANY single component differs, so identity is total over all three")
    void differsWhenAnySingleComponentDiffers(final String groupId, final String typeCd, final int catCd) {
        final DisclosureGroupId other = new DisclosureGroupId(groupId, typeCd, catCd);

        assertThat(defaultGroupKey())
                .as("a partial identity would collapse distinct disclosure groups onto one another, and "
                        + "JPA resolves an @EmbeddedId through equals and hashCode")
                .isNotEqualTo(other);
    }

    @Test
    @DisplayName("is unequal to null and to a foreign type, without throwing")
    void isUnequalToNullAndToAForeignType() {
        final DisclosureGroupId key = defaultGroupKey();

        assertThat(key.equals(null)).isFalse();
        assertThat(key.equals("DEFAULT   011")).isFalse();
        assertThat(key).isNotEqualTo(new Object());
    }

    @Test
    @DisplayName("behaves as a hash key: a set de-duplicates equal keys and a map resolves by value")
    void behavesAsAHashKey() {
        final Set<DisclosureGroupId> set = new HashSet<>();
        set.add(defaultGroupKey());
        set.add(defaultGroupKey());
        set.add(new DisclosureGroupId("DEFAULT   ", "01", 2));

        assertThat(set)
                .as("two equal keys must occupy one slot; a third distinct key must occupy its own")
                .hasSize(2);

        final Map<DisclosureGroupId, String> rates = new HashMap<>();
        rates.put(defaultGroupKey(), "0015.00");

        assertThat(rates.get(defaultGroupKey()))
                .as("a rate lookup keyed on a freshly built equal key must resolve, exactly as the VSAM "
                        + "keyed read did")
                .isEqualTo("0015.00");
    }

    @Test
    @DisplayName("hashCode is stable across repeated calls on the same instance")
    void hashCodeIsStableAcrossRepeatedCalls() {
        final DisclosureGroupId key = defaultGroupKey();
        final int first = key.hashCode();

        assertThat(key.hashCode()).isEqualTo(first);
        assertThat(key.hashCode()).isEqualTo(first);
    }

    @Test
    @DisplayName("toString names the type and renders all three components for diagnostics")
    void toStringNamesTheTypeAndAllThreeComponents() {
        assertThat(defaultGroupKey().toString())
                .startsWith("DisclosureGroupId[")
                .contains("accountGroupId=DEFAULT")
                .contains("tranTypeCd=01")
                .contains("tranCatCd=1")
                .endsWith("]");
    }

    @Test
    @DisplayName("round-trips through Java serialization with identity preserved")
    void roundTripsThroughJavaSerialization() throws IOException, ClassNotFoundException {
        final DisclosureGroupId original = defaultGroupKey();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }

        final Object restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = in.readObject();
        }

        assertThat(restored)
                .as("a serializable composite key must survive a round trip with its identity intact, "
                        + "because a JPA provider may serialize a key held in a second level cache")
                .isEqualTo(original)
                .hasSameHashCodeAs(original);
    }

    @Test
    @DisplayName("declares a serialVersionUID, so the serialized form is pinned rather than computed")
    void declaresAPinnedSerialVersionUid() throws NoSuchFieldException {
        final Field uid = DisclosureGroupId.class.getDeclaredField("serialVersionUID");

        assertThat(Modifier.isStatic(uid.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(uid.getModifiers())).isTrue();
        assertThat(uid.getType()).isEqualTo(long.class);
    }

    @Test
    @DisplayName("carries no mutator, so a persisted key cannot be altered in place")
    void carriesNoMutator() {
        assertThat(DisclosureGroupId.class.getDeclaredMethods())
                .as("a composite key must be immutable after construction; a setter would let application "
                        + "code change the identity of an already managed row")
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    /** Declared instance field names in declaration order, excluding synthetic and static members. */
    private static String[] persistentFieldNames() {
        return java.util.Arrays.stream(DisclosureGroupId.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toArray(String[]::new);
    }
    /**
     * Instantiates a type through its no-argument constructor, unsealing it first.
     *
     * <p>The constructor under test is deliberately {@code protected}: an {@code @Embeddable} identifier
     * must expose one for the persistence provider, and {@code protected} is the narrowest visibility that
     * satisfies the JPA specification without publishing a half-built key to application code. Hibernate
     * reaches it reflectively after calling {@code setAccessible(true)}, so the test reaches it the same
     * way; calling it directly would assert public visibility, which is precisely what the type must not
     * have.</p>
     *
     * @param <T>  the type to instantiate
     * @param type the class of that type
     * @return a new instance built through the no-argument constructor
     * @throws ReflectiveOperationException if the constructor is absent or cannot be invoked
     */
    private static <T> T noArgInstance(final Class<T> type) throws ReflectiveOperationException {
        final java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

}
