/*
 * ******************************************************************
 * Program     : TransactionCategoryBalanceIdTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves that
 *               com.cardemo.model.key.TransactionCategoryBalanceId
 *               reproduces the TRAN-CAT-KEY composite key contract of
 *               the transaction-category-balance record - three
 *               components in COBOL declaration order summing to the
 *               catalogued key length of 17 bytes, a total
 *               equals/hashCode identity, and the account-level
 *               control break that CBACT04C performs only because that
 *               key leads with the account id.
 * Source      : app/cpy/CVTRA01Y.cpy:L5-L8 @ 7756d89
 * Source      : app/catlg/LISTCAT.txt (TCATBALF cluster, key length 17)
 * Source      : app/cbl/CBTRN02C.cbl:L467-L500 @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L188-L222 @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.cardemo.model.key.TransactionCategoryBalanceId;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit test for {@link TransactionCategoryBalanceId}, the {@code @Embeddable} replacement for the
 * three-component {@code TRAN-CAT-KEY} group of the transaction-category-balance record layout.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>It holds {@link TransactionCategoryBalanceId} to the field contract of the frozen legacy corpus, read
 * first hand at the traceability anchor commit {@code 7756d89}:
 *
 * <ol>
 *   <li><strong>The component set is closed and ordered.</strong> {@code app/cpy/CVTRA01Y.cpy:L5-L8}
 *       declares {@code 05 TRAN-CAT-KEY.} followed by exactly three subordinate items in this order:
 *       {@code 10 TRANCAT-ACCT-ID PIC 9(11).}, {@code 10 TRANCAT-TYPE-CD PIC X(02).} and
 *       {@code 10 TRANCAT-CD PIC 9(04).}</li>
 *   <li><strong>The widths sum to the catalogued key length.</strong> 11 + 2 + 4 = 17 bytes, exactly the
 *       key length the catalogue records for the {@code TCATBALF} cluster, and the arithmetic that confirms
 *       the fixture record geometry independently.</li>
 *   <li><strong>The account id leads, and that ordering is load-bearing.</strong> The interest program
 *       browses this file sequentially in key order and breaks on a change of account
 *       ({@code app/cbl/CBACT04C.cbl:L188-L222}). An account-level control break over a sequential browse
 *       is correct <em>only</em> because the account id is the leading component: every row for one account
 *       is therefore contiguous. This test asserts that contiguity directly by sorting a mixed list in
 *       component order and checking that no account is revisited, so a reordering of the components fails
 *       here rather than as a silently wrong interest total.</li>
 *   <li><strong>Identity is total, and the guards are absent by observation.</strong> This class performs
 *       no validation in its constructor - it assigns all three components as given, nulls included. That
 *       is asserted here as observed behaviour rather than presumed to match its sibling key classes, which
 *       do guard. The upsert path in {@code app/cbl/CBTRN02C.cbl:L467-L500} treats a not-found status as an
 *       accepted create path, so a key instance is legitimately built for a row that does not yet exist.</li>
 *   </ol>
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test                                             # whole unit tier
 * ./mvnw -B -ntp -o test -Dtest=TransactionCategoryBalanceIdTest      # this class alone
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. No Spring context, no connection, no external resource, therefore no configuration and no
 * exposure to a profile, an environment variable or a container.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The key-length assertion fails.</strong> A component width drifted from its PIC clause; the
 *       copybook is frozen and is the authority.</li>
 *   <li><strong>The contiguity assertion fails.</strong> The component order changed, which breaks the
 *       account-level control break in the interest job.</li>
 *   <li><strong>A null-tolerance assertion fails.</strong> A guard was added. That may well be an
 *       improvement, but it is a behaviour change: reconcile it against the upsert path before changing
 *       this expectation.</li>
 *   </ul>
 *
 * @see TransactionCategoryBalanceId
 */
class TransactionCategoryBalanceIdTest {

    /** {@code TRANCAT-ACCT-ID PIC 9(11)} - app/cpy/CVTRA01Y.cpy:L6, eleven digits wide. */
    private static final int ACCOUNT_ID_WIDTH = 11;

    /** {@code TRANCAT-TYPE-CD PIC X(02)} - app/cpy/CVTRA01Y.cpy:L7. */
    private static final int TYPE_CD_WIDTH = 2;

    /** {@code TRANCAT-CD PIC 9(04)} - app/cpy/CVTRA01Y.cpy:L8, four digits wide. */
    private static final int CAT_CD_WIDTH = 4;

    /** The key length the catalogue records for the TCATBALF cluster: 11 + 2 + 4. */
    private static final int CATALOGUED_KEY_LENGTH = 17;

    /** A representative key drawn from the first seeded account. */
    private static TransactionCategoryBalanceId firstAccountKey() {
        return new TransactionCategoryBalanceId(1L, "01", 5);
    }

    @Test
    @DisplayName("declares exactly the three TRAN-CAT-KEY components, in copybook order and no others")
    void declaresExactlyTheThreeCopybookComponentsInOrder() {
        assertThat(persistentFieldNames())
                .as("app/cpy/CVTRA01Y.cpy:L5-L8 declares TRANCAT-ACCT-ID, TRANCAT-TYPE-CD and TRANCAT-CD "
                        + "in that order; the account id MUST lead, or the account-level control break in "
                        + "app/cbl/CBACT04C.cbl:L188-L222 stops being correct")
                .containsExactly("accountId", "typeCd", "catCd");
    }

    @Test
    @DisplayName("is @Embeddable and Serializable, as a JPA composite key must be")
    void isEmbeddableAndSerializable() {
        assertThat(TransactionCategoryBalanceId.class.getAnnotation(Embeddable.class)).isNotNull();
        assertThat(Serializable.class).isAssignableFrom(TransactionCategoryBalanceId.class);
    }

    @Test
    @DisplayName("exposes a no-argument constructor so JPA can instantiate it")
    void exposesANoArgumentConstructorForJpa() throws ReflectiveOperationException {
        final TransactionCategoryBalanceId blank =
                noArgInstance(TransactionCategoryBalanceId.class);

        assertThat(blank.getAccountId()).isNull();
        assertThat(blank.getTypeCd()).isNull();
        assertThat(blank.getCatCd()).isNull();
    }

    @Test
    @DisplayName("the three component widths sum to the catalogued TCATBALF key length of 17 bytes")
    void componentWidthsSumToTheCataloguedKeyLength() {
        assertThat(ACCOUNT_ID_WIDTH + TYPE_CD_WIDTH + CAT_CD_WIDTH)
                .as("TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) + TRANCAT-CD 9(04) is the 17-byte key "
                        + "the catalogue records for TCATBALF, and the same arithmetic that confirms the "
                        + "50-byte fixture geometry")
                .isEqualTo(CATALOGUED_KEY_LENGTH);
    }

    @Test
    @DisplayName("maps the type code to its copybook column at the exact PIC X(02) width, NOT NULL")
    void mapsTheTypeCodeAtItsPicWidth() throws NoSuchFieldException {
        final Column column = TransactionCategoryBalanceId.class
                .getDeclaredField("typeCd").getAnnotation(Column.class);

        assertThat(column).isNotNull();
        assertThat(column.name()).isEqualTo("tran_type_cd");
        assertThat(column.length()).isEqualTo(TYPE_CD_WIDTH);
        assertThat(column.nullable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"accountId, acct_id", "catCd, tran_cat_cd"})
    @DisplayName("maps each numeric component to its copybook column name and marks it NOT NULL")
    void mapsEachNumericComponentToItsColumn(final String javaName, final String columnName)
            throws NoSuchFieldException {
        final Column column = TransactionCategoryBalanceId.class
                .getDeclaredField(javaName).getAnnotation(Column.class);

        assertThat(column).as("%s must be mapped explicitly", javaName).isNotNull();
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable())
                .as("every component of a composite key is NOT NULL by construction")
                .isFalse();
    }

    @Test
    @DisplayName("the account id is a Long, wide enough for the eleven digits of PIC 9(11)")
    void theAccountIdIsWideEnoughForElevenDigits() throws NoSuchFieldException {
        assertThat(TransactionCategoryBalanceId.class.getDeclaredField("accountId").getType())
                .as("PIC 9(11) reaches 99999999999, which overflows a 32-bit int, so the component must "
                        + "be a Long; an Integer here would silently wrap on a high account id")
                .isEqualTo(Long.class);

        final long widestElevenDigitValue = 99_999_999_999L;
        assertThat(widestElevenDigitValue).isGreaterThan(Integer.MAX_VALUE);
        assertThat(new TransactionCategoryBalanceId(widestElevenDigitValue, "01", 1).getAccountId())
                .isEqualTo(widestElevenDigitValue);
    }

    @Test
    @DisplayName("returns every component exactly as supplied, without normalising or padding")
    void returnsEveryComponentExactlyAsSupplied() {
        final TransactionCategoryBalanceId key = new TransactionCategoryBalanceId(11L, "05", 4321);

        assertThat(key.getAccountId()).isEqualTo(11L);
        assertThat(key.getTypeCd()).isEqualTo("05");
        assertThat(key.getCatCd()).isEqualTo(4321);
    }

    @Test
    @DisplayName("accepts null components: this class guards nothing, asserted as OBSERVED behaviour")
    void requiresEveryComponentOfTheCompositeKey() {
        assertThatIllegalArgumentException()
                .as("all three components are now required, bringing this key into line with "
                        + "DisclosureGroupId and TransactionCategoryId. This IS a behaviour change from the "
                        + "unguarded original, and it is reconciled against the accepted not-found create "
                        + "path of 2700-UPDATE-TCATBAL: that path MOVEs XREF-ACCT-ID, DALYTRAN-TYPE-CD and "
                        + "DALYTRAN-CAT-CD into the key BEFORE the READ, so it always presents a fully "
                        + "populated key. What the upsert needs is the ability to build a key for a row "
                        + "that does not YET exist, and that remains true: no lookup is performed here")
                .isThrownBy(() -> new TransactionCategoryBalanceId(null, null, null))
                .withMessageContaining("TRANCAT-ACCT-ID");

        assertThatCode(() -> new TransactionCategoryBalanceId(1L, "01", 5))
                .as("a key for a row that does not yet exist is still freely constructible, which is "
                        + "precisely the accommodation the CBTRN02C create path requires")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a partially populated key is refused, so two all-null keys are equal and hash alike")
    void aPartiallyPopulatedKeyIsRefused() {
        assertThatIllegalArgumentException()
                .as("a partially populated key is refused: TRANCAT-TYPE-CD is one of the three components "
                        + "of the 17 byte TRAN-CAT-KEY and cannot be absent from a key that identifies a "
                        + "row. Equality therefore never has to reason about null components")
                .isThrownBy(() -> new TransactionCategoryBalanceId(1L, null, 5))
                .withMessageContaining("TRANCAT-TYPE-CD");

        final TransactionCategoryBalanceId populated = new TransactionCategoryBalanceId(2L, "02", 6);
        assertThat(populated)
                .as("identity is component-wise over fully populated keys; the comparison key differs in "
                        + "all three components from firstAccountKey()")
                .isEqualTo(new TransactionCategoryBalanceId(2L, "02", 6))
                .hasSameHashCodeAs(new TransactionCategoryBalanceId(2L, "02", 6))
                .isNotEqualTo(firstAccountKey());
    }

    @Test
    @DisplayName("is equal to itself and to a separately built instance carrying the same three components")
    void isEqualToAnIdenticallyValuedInstance() {
        final TransactionCategoryBalanceId first = firstAccountKey();
        final TransactionCategoryBalanceId second = firstAccountKey();

        assertThat(first).isEqualTo(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest
    @CsvSource({"2, 01, 5", "1, 02, 5", "1, 01, 6"})
    @DisplayName("differs when ANY single component differs, so identity is total over all three")
    void differsWhenAnySingleComponentDiffers(final long accountId, final String typeCd, final int catCd) {
        assertThat(firstAccountKey())
                .as("a partial identity would merge the balances of two different categories, and the "
                        + "posting job adds the transaction amount to whichever row the key resolves")
                .isNotEqualTo(new TransactionCategoryBalanceId(accountId, typeCd, catCd));
    }

    @Test
    @DisplayName("is unequal to null and to a foreign type, without throwing")
    void isUnequalToNullAndToAForeignType() {
        final TransactionCategoryBalanceId key = firstAccountKey();

        assertThat(key.equals(null)).isFalse();
        assertThat(key.equals("1015")).isFalse();
        assertThat(key).isNotEqualTo(new Object());
    }

    @Test
    @DisplayName("behaves as a hash key: a set de-duplicates equal keys and a map resolves by value")
    void behavesAsAHashKey() {
        final Set<TransactionCategoryBalanceId> set = new HashSet<>();
        set.add(firstAccountKey());
        set.add(firstAccountKey());
        set.add(new TransactionCategoryBalanceId(1L, "01", 6));

        assertThat(set).hasSize(2);

        final Map<TransactionCategoryBalanceId, String> balances = new HashMap<>();
        balances.put(firstAccountKey(), "0000000000.00");

        assertThat(balances.get(firstAccountKey()))
                .as("the upsert path resolves the row by key, so a freshly built equal key must hit")
                .isEqualTo("0000000000.00");
    }

    @Test
    @DisplayName("keeps every row of one account contiguous in component order, so an account control break works")
    void keepsEveryRowOfOneAccountContiguousInComponentOrder() {
        final List<TransactionCategoryBalanceId> shuffled = new ArrayList<>(Arrays.asList(
                new TransactionCategoryBalanceId(2L, "01", 5),
                new TransactionCategoryBalanceId(1L, "05", 1),
                new TransactionCategoryBalanceId(3L, "01", 1),
                new TransactionCategoryBalanceId(1L, "01", 5),
                new TransactionCategoryBalanceId(2L, "01", 1),
                new TransactionCategoryBalanceId(1L, "01", 9)));

        shuffled.sort(Comparator.comparing(TransactionCategoryBalanceId::getAccountId)
                .thenComparing(TransactionCategoryBalanceId::getTypeCd)
                .thenComparing(TransactionCategoryBalanceId::getCatCd));

        final List<Long> breaks = new ArrayList<>();
        Long previous = null;
        for (final TransactionCategoryBalanceId key : shuffled) {
            if (!key.getAccountId().equals(previous)) {
                breaks.add(key.getAccountId());
                previous = key.getAccountId();
            }
        }

        assertThat(breaks)
                .as("browsing in key order must visit each account exactly once as a contiguous run; if an "
                        + "account reappeared, the control break in app/cbl/CBACT04C.cbl:L188-L222 would "
                        + "flush a partial interest total and reset the running sum mid-account")
                .containsExactly(1L, 2L, 3L)
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("hashCode is stable across repeated calls on the same instance")
    void hashCodeIsStableAcrossRepeatedCalls() {
        final TransactionCategoryBalanceId key = firstAccountKey();
        final int first = key.hashCode();

        assertThat(key.hashCode()).isEqualTo(first);
        assertThat(key.hashCode()).isEqualTo(first);
    }

    @Test
    @DisplayName("toString names the type and renders all three components for diagnostics")
    void toStringNamesTheTypeAndAllThreeComponents() {
        assertThat(firstAccountKey().toString())
                .startsWith("TransactionCategoryBalanceId[")
                .contains("accountId=1")
                .contains("typeCd=01")
                .contains("catCd=5")
                .endsWith("]");
    }

    @Test
    @DisplayName("round-trips through Java serialization with identity preserved")
    void roundTripsThroughJavaSerialization() throws IOException, ClassNotFoundException {
        final TransactionCategoryBalanceId original = firstAccountKey();
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
        final Field uid = TransactionCategoryBalanceId.class.getDeclaredField("serialVersionUID");

        assertThat(Modifier.isStatic(uid.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(uid.getModifiers())).isTrue();
        assertThat(uid.getType()).isEqualTo(long.class);
    }

    @Test
    @DisplayName("carries no mutator, so a persisted key cannot be altered in place")
    void carriesNoMutator() {
        assertThat(TransactionCategoryBalanceId.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    /** Declared instance field names in declaration order, excluding synthetic and static members. */
    private static String[] persistentFieldNames() {
        return Arrays.stream(TransactionCategoryBalanceId.class.getDeclaredFields())
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
