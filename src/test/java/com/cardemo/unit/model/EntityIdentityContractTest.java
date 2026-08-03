/*
 * ******************************************************************
 * Program     : EntityIdentityContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Proves the identity contract of all eleven JPA entities
 *               that replace the ten VSAM KSDS clusters - each is equal
 *               by its VSAM primary key alone and never by its payload,
 *               each has the protected no-argument constructor the
 *               persistence provider requires, and none is final so a
 *               lazy-loading proxy can subclass it. It also pins the TWO
 *               genuinely different null-identity policies in use, side
 *               by side, including the HashSet collapse hazard that the
 *               weaker of the two carries.
 * Source      : app/catlg/LISTCAT.txt (cluster key lengths) @ 7756d89
 * Source      : app/cpy/CVACT01Y.cpy, CVACT02Y.cpy, CVACT03Y.cpy @ 7756d89
 * Source      : app/cpy/CVTRA01Y.cpy .. CVTRA06Y.cpy, CVCUS01Y.cpy @ 7756d89
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

import com.cardemo.model.entity.Account;
import com.cardemo.model.entity.Card;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Customer;
import com.cardemo.model.entity.DailyTransaction;
import com.cardemo.model.entity.DisclosureGroup;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionCategoryBalance;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.entity.UserSecurity;
import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.model.enums.UserType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit test for the shared identity contract of the eleven JPA entities.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>Ten VSAM KSDS clusters plus the JCL-defined user security file become eleven entities. Every one of them
 * was keyed in the source by a single primary key, and browse order depended on that key. Three structural
 * properties therefore have to hold for all eleven, and are asserted here once rather than eleven times:
 *
 * <ol>
 *   <li><strong>A protected no-argument constructor exists.</strong> Hibernate instantiates an entity before
 *       populating it. Without this constructor the provider cannot materialise a row at all, and the failure
 *       appears at runtime rather than at compile time - which is exactly why it is worth a test.</li>
 *   <li><strong>The class is not final.</strong> The provider subclasses each entity to build lazy-loading
 *       proxies. A final entity silently disables that.</li>
 *   <li><strong>Equality is by primary key only, never by payload.</strong> Two instances of the same row
 *       loaded in different persistence contexts must be equal, and a mutated payload must not change
 *       identity. Equally, {@code hashCode} must not read a mutable payload field or an entity would go
 *       missing from a {@link HashSet} after an update.</li>
 *   </ol>
 *
 * <h2>The two null-identity policies, and why the difference matters</h2>
 *
 * <p>The eleven split into two groups that behave differently for an unsaved instance whose key is still
 * {@code null}. Both were read from the source and both are asserted:
 *
 * <table border="1">
 *   <caption>Null-identity behaviour by entity</caption>
 *   <tr><th>Policy</th><th>Entities</th><th>Two distinct null-key instances are…</th></tr>
 *   <tr><td>{@code key != null && key.equals(…)}</td>
 *       <td>{@link Account}, {@link Customer}, {@link DisclosureGroup}, {@link TransactionCategory}</td>
 *       <td><strong>NOT equal</strong> - the JPA-safe answer</td></tr>
 *   <tr><td>{@code Objects.equals(key, …)}</td>
 *       <td>{@link Card}, {@link CardCrossReference}, {@link Transaction}, {@link DailyTransaction},
 *           {@link TransactionCategoryBalance}, {@link TransactionType}</td>
 *       <td><strong>EQUAL</strong> - carries a HashSet collapse hazard</td></tr>
 *   </table>
 *
 * <p>The second policy means that adding two <em>different</em> unsaved instances to a {@link HashSet}
 * collapses them into one entry, because both have a {@code null} key and therefore compare equal. That is a
 * real hazard for any batch step that stages new rows in a set before flushing. This test asserts the
 * behaviour <strong>as it is</strong> and names the hazard, rather than asserting what would be preferable -
 * so that harmonising the two policies later is a deliberate, reviewed change with a failing test to prompt
 * it, not an accident.
 *
 * <h2>2. How to run it</h2>
 *
 * <pre>{@code
 * ./mvnw -B -ntp -o test -Dtest=EntityIdentityContractTest
 * }</pre>
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>None. No persistence unit is started; the entities are exercised as plain objects, which is the whole
 * point of keeping this in the unit tier rather than the Testcontainers tier.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A no-argument-constructor assertion fails.</strong> An entity lost its provider constructor
 *       and can no longer be loaded from the database. This test is the only thing that catches it without a
 *       running database.</li>
 *   <li><strong>An {@code equals}-ignores-payload assertion fails.</strong> Somebody generated {@code equals}
 *       over all fields. That breaks JPA identity: the same row loaded twice would compare unequal after one
 *       copy was edited.</li>
 *   <li><strong>A null-identity assertion fails.</strong> The two policies were harmonised. Confirm that was
 *       intended, then move the entity between the two registry methods below.</li>
 *   </ul>
 */
class EntityIdentityContractTest {

    /** All eleven entity types, registered explicitly so adding one is a deliberate act. */
    static Stream<Class<?>> allEntities() {
        return Stream.of(
                Account.class,
                Card.class,
                Customer.class,
                CardCrossReference.class,
                Transaction.class,
                DailyTransaction.class,
                TransactionCategoryBalance.class,
                DisclosureGroup.class,
                TransactionType.class,
                TransactionCategory.class,
                UserSecurity.class);
    }

    /**
     * A BCrypt strength-10 digest of the declared shape and length, used where a password column must be
     * populated but its value is irrelevant to the assertion.
     *
     * <p>{@code sec_usr_pwd} is guarded to exactly sixty characters of {@code $2a$10$} form, because the
     * ten seeded users of {@code app/jcl/DUSRSECJ.jcl} are stored only as hashes and a short placeholder
     * would let a plaintext value through the column the guard exists to protect. These are structurally
     * valid digests rather than real ones: no plaintext anywhere in this repository produces them.</p>
     */
    private static final String DIGEST_A = "$2a$10$abcdefghijklmnopqrstuuWJmMNQ7vJ8gkE1kBAsCvOoS3bYaCNiX";

    /** A second digest of the same shape, distinct from {@link #DIGEST_A} in its salt and its hash. */
    private static final String DIGEST_B = "$2a$10$zyxwvutsrqponmlkjihgfeR7mYRxQ8nPzVdG2jLBtDwPpT4cZbDOj";

    /**
     * Builds a pair of distinct instances that share a primary key, plus a third with a different key.
     * Returned as arguments so the identity assertions run over every entity without repetition.
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> keyedTriples() {
        return Stream.of(
                args("Account", account(1L, "Y"), account(1L, "N"), account(2L, "Y")),
                args("Card", card("4111111111111111", 1L), card("4111111111111111", 2L),
                        card("4111111111111112", 1L)),
                args("Customer", customer(9L, "FNAMEAA6"), customer(9L, "FNAM7"), customer(10L, "FNAMEAA6")),
                args("CardCrossReference",
                        new CardCrossReference("4111111111111111", 9L, 1L),
                        new CardCrossReference("4111111111111111", 10L, 2L),
                        new CardCrossReference("4111111111111112", 9L, 1L)),
                args("Transaction", transaction("0000000000000001", "01"),
                        transaction("0000000000000001", "02"), transaction("0000000000000002", "01")),
                args("DailyTransaction", dailyTransaction(1L, "0000000000000001"),
                        dailyTransaction(1L, "0000000000000002"),
                        dailyTransaction(2L, "0000000000000001")),
                args("TransactionCategoryBalance",
                        new TransactionCategoryBalance(balanceId(1L), new BigDecimal("10.00")),
                        new TransactionCategoryBalance(balanceId(1L), new BigDecimal("20.00")),
                        new TransactionCategoryBalance(balanceId(2L), new BigDecimal("10.00"))),
                args("DisclosureGroup",
                        new DisclosureGroup(groupId("DEFAULT"), new BigDecimal("15.00")),
                        new DisclosureGroup(groupId("DEFAULT"), new BigDecimal("20.00")),
                        new DisclosureGroup(groupId("ZEROAPR"), new BigDecimal("15.00"))),
                args("TransactionType", new TransactionType("01", "Purchase"),
                        new TransactionType("01", "Payment"), new TransactionType("02", "Purchase")),
                args("TransactionCategory",
                        new TransactionCategory(categoryId("01", 5), "Regular Sales Draft"),
                        new TransactionCategory(categoryId("01", 5), "Something else"),
                        new TransactionCategory(categoryId("01", 6), "Regular Sales Draft")),
                args("UserSecurity",
                        new UserSecurity("STDUSR01", "FNAMEAA6", "LNAME6", DIGEST_A, UserType.USER),
                        new UserSecurity("STDUSR01", "FNAMEAA7", "LNAME7", DIGEST_B, UserType.USER),
                        new UserSecurity("STDUSR02", "FNAMEAA6", "LNAME6", DIGEST_A, UserType.USER)));
    }

    /** The four entities whose equals treats a null key as never-equal - the JPA-safe policy. */
    static Stream<Class<?>> nullKeyNeverEqualEntities() {
        return Stream.of(
                Account.class, Customer.class, DisclosureGroup.class, TransactionCategory.class);
    }

    /** The six entities whose equals treats two null keys as equal - the collapse-hazard policy. */
    static Stream<Class<?>> nullKeyEqualEntities() {
        return Stream.of(
                Card.class,
                CardCrossReference.class,
                Transaction.class,
                DailyTransaction.class,
                TransactionCategoryBalance.class,
                TransactionType.class);
    }

    @Test
    @DisplayName("exactly eleven entities are registered: ten VSAM clusters plus the JCL-defined user file")
    void exactlyElevenEntitiesAreRegistered() {
        assertThat(allEntities().toList())
                .as("app/catlg/LISTCAT.txt catalogues exactly ten base clusters, and USRSEC is defined in "
                        + "app/jcl/DUSRSECJ.jcl rather than catalogued - eleven in total. A twelfth entity "
                        + "would have no cluster behind it")
                .hasSize(11)
                .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @MethodSource("allEntities")
    @DisplayName("every entity declares the protected no-argument constructor the provider requires")
    void everyEntityDeclaresAProtectedNoArgumentConstructor(final Class<?> entity)
            throws ReflectiveOperationException {
        final Constructor<?> provider = entity.getDeclaredConstructor();

        assertThat(Modifier.isProtected(provider.getModifiers()))
                .as("%s must expose a protected no-argument constructor: Hibernate instantiates the entity "
                        + "and then populates fields reflectively. Protected rather than public keeps it "
                        + "out of the application's own reach, where an unkeyed instance would be a bug",
                        entity.getSimpleName())
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("allEntities")
    @DisplayName("the provider constructor is usable, so a row can actually be materialised")
    void theProviderConstructorIsUsable(final Class<?> entity) throws ReflectiveOperationException {
        final Constructor<?> provider = entity.getDeclaredConstructor();
        provider.setAccessible(true);

        assertThat(provider.newInstance())
                .as("%s must be instantiable through its no-argument constructor without throwing - a "
                        + "constructor that validated here would reject every row the provider loads",
                        entity.getSimpleName())
                .isNotNull()
                .isInstanceOf(entity);
    }

    @ParameterizedTest
    @MethodSource("allEntities")
    @DisplayName("no entity is final, so a lazy-loading proxy can subclass it")
    void noEntityIsFinal(final Class<?> entity) {
        assertThat(Modifier.isFinal(entity.getModifiers()))
                .as("%s must not be final: the provider generates a subclass to implement lazy loading, "
                        + "and a final entity silently forces eager fetching instead",
                        entity.getSimpleName())
                .isFalse();
    }

    @ParameterizedTest
    @MethodSource("allEntities")
    @DisplayName("every entity overrides equals, hashCode and toString rather than inheriting them")
    void everyEntityOverridesTheObjectMethods(final Class<?> entity) throws ReflectiveOperationException {
        assertThat(entity.getDeclaredMethod("equals", Object.class).getDeclaringClass())
                .as("%s must override equals: identity semantics inherited from Object would make two "
                        + "loads of the same row unequal", entity.getSimpleName())
                .isEqualTo(entity);
        assertThat(entity.getDeclaredMethod("hashCode").getDeclaringClass()).isEqualTo(entity);
        assertThat(entity.getDeclaredMethod("toString").getDeclaringClass())
                .as("%s must override toString: the default is an identity hash, which is useless in a "
                        + "diagnostic log", entity.getSimpleName())
                .isEqualTo(entity);
    }

    @ParameterizedTest
    @MethodSource("keyedTriples")
    @DisplayName("two instances sharing a primary key are equal even when their payloads differ")
    void sameKeyDifferentPayloadIsEqual(
            final String name, final Object sameKeyA, final Object sameKeyB, final Object otherKey) {
        assertThat(sameKeyA)
                .as("%s: the same VSAM record read twice, then edited in one copy, must remain the same "
                        + "entity - equality is by key, never by payload", name)
                .isEqualTo(sameKeyB);
        assertThat(sameKeyB).as("%s: equals must be symmetric", name).isEqualTo(sameKeyA);
        assertThat(sameKeyA.hashCode())
                .as("%s: hashCode must agree with equals, or the entity would go missing from a HashSet "
                        + "after any payload change", name)
                .isEqualTo(sameKeyB.hashCode());
        assertThat(otherKey)
                .as("%s: a different key is a different record and must not be equal", name)
                .isNotEqualTo(sameKeyA);
    }

    @ParameterizedTest
    @MethodSource("keyedTriples")
    @DisplayName("equals is reflexive, null-safe and type-safe on every entity")
    void equalsIsReflexiveNullSafeAndTypeSafe(
            final String name, final Object sameKeyA, final Object sameKeyB, final Object otherKey) {
        assertThat(sameKeyA).as("%s: reflexive", name).isEqualTo(sameKeyA);
        assertThat(sameKeyA.equals(null)).as("%s: never equal to null", name).isFalse();
        assertThat(sameKeyA.equals("a string"))
                .as("%s: never equal to a foreign type, which a bare cast in equals would fail on", name)
                .isFalse();
        assertThat(sameKeyB).isNotSameAs(sameKeyA);
        assertThat(otherKey).isNotEqualTo(sameKeyB);
    }

    @ParameterizedTest
    @MethodSource("keyedTriples")
    @DisplayName("a HashSet deduplicates two instances of the same keyed record")
    void aHashSetDeduplicatesTheSameKeyedRecord(
            final String name, final Object sameKeyA, final Object sameKeyB, final Object otherKey) {
        final Set<Object> set = new HashSet<>(List.of(sameKeyA, sameKeyB, otherKey));

        assertThat(set)
                .as("%s: two reads of one record plus one other record is two distinct entities; a "
                        + "hashCode that read the payload would yield three", name)
                .hasSize(2);
        assertThat(set.contains(sameKeyB))
                .as("%s: membership must be decided by key, so either copy finds the entry", name)
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("keyedTriples")
    @DisplayName("toString is non-empty, names the type and never throws")
    void toStringNamesTheTypeAndNeverThrows(
            final String name, final Object sameKeyA, final Object sameKeyB, final Object otherKey) {
        assertThat(sameKeyA.toString())
                .as("%s: a diagnostic log needs the type name to be readable at a glance", name)
                .isNotBlank()
                .contains(sameKeyA.getClass().getSimpleName());
        assertThat(sameKeyB.toString()).isNotBlank();
        assertThat(otherKey.toString()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("nullKeyNeverEqualEntities")
    @DisplayName("JPA-safe policy: two unsaved instances with null keys are NOT equal")
    void unsavedInstancesAreNotEqualUnderTheSafePolicy(final Class<?> entity)
            throws ReflectiveOperationException {
        final Object first = newUnsaved(entity);
        final Object second = newUnsaved(entity);

        assertThat(first)
                .as("%s guards with 'key != null && key.equals(...)', so an unsaved instance is equal only "
                        + "to itself. This is the correct JPA answer: two rows that do not exist yet are "
                        + "not the same row", entity.getSimpleName())
                .isNotEqualTo(second);
        assertThat(first).as("%s must still be reflexive", entity.getSimpleName()).isEqualTo(first);
    }

    @ParameterizedTest
    @MethodSource("nullKeyNeverEqualEntities")
    @DisplayName("JPA-safe policy: a HashSet keeps both unsaved instances, which is what a batch stage needs")
    void aHashSetKeepsBothUnsavedInstancesUnderTheSafePolicy(final Class<?> entity)
            throws ReflectiveOperationException {
        final Set<Object> staged = new HashSet<>();
        staged.add(newUnsaved(entity));
        staged.add(newUnsaved(entity));

        assertThat(staged)
                .as("%s: a batch step that stages two new rows in a set before flushing keeps both, which "
                        + "is the behaviour a caller would expect", entity.getSimpleName())
                .hasSize(2);
    }

    @ParameterizedTest
    @MethodSource("nullKeyEqualEntities")
    @DisplayName("weaker policy: two unsaved instances with null keys ARE equal - the collapse hazard")
    void unsavedInstancesAreEqualUnderTheWeakerPolicy(final Class<?> entity)
            throws ReflectiveOperationException {
        final Object first = newUnsaved(entity);
        final Object second = newUnsaved(entity);

        assertThat(first)
                .as("OBSERVED BEHAVIOUR, pinned deliberately and NOT endorsed: %s compares with "
                        + "Objects.equals(key, ...), so two distinct unsaved instances - both with a null "
                        + "key - compare EQUAL. Four sibling entities guard with 'key != null &&' and do "
                        + "not behave this way. This assertion records the divergence so that harmonising "
                        + "the two policies is a reviewed change rather than an accident",
                        entity.getSimpleName())
                .isEqualTo(second);
        assertThat(first.hashCode())
                .as("%s: hashCode agrees with equals here, which is self-consistent - the hazard is the "
                        + "equality itself, not an inconsistency between the two",
                        entity.getSimpleName())
                .isEqualTo(second.hashCode());
    }

    @ParameterizedTest
    @MethodSource("nullKeyEqualEntities")
    @DisplayName("weaker policy: a HashSet COLLAPSES two distinct unsaved instances into one entry")
    void aHashSetCollapsesUnsavedInstancesUnderTheWeakerPolicy(final Class<?> entity)
            throws ReflectiveOperationException {
        final Set<Object> staged = new HashSet<>();
        staged.add(newUnsaved(entity));
        staged.add(newUnsaved(entity));

        assertThat(staged)
                .as("OBSERVED HAZARD, pinned deliberately: staging two distinct new %s instances in a "
                        + "HashSet before flushing yields ONE entry, silently discarding a row. Any batch "
                        + "writer for this entity must therefore stage in a List, not a Set. That is a "
                        + "real constraint on calling code and this test is where it is recorded",
                        entity.getSimpleName())
                .hasSize(1);
    }

    @Test
    @DisplayName("the two policies together account for ten entities, with UserSecurity tested separately")
    void theTwoPoliciesAccountForTenEntities() {
        final List<Class<?>> safe = nullKeyNeverEqualEntities().toList();
        final List<Class<?>> weak = nullKeyEqualEntities().toList();

        assertThat(safe).hasSize(4).doesNotHaveDuplicates();
        assertThat(weak).hasSize(6).doesNotHaveDuplicates();
        assertThat(safe)
                .as("no entity may appear in both registries, or one of the two assertions would be "
                        + "vacuous")
                .doesNotContainAnyElementsOf(weak);
        assertThat(allEntities().toList())
                .as("the two policy registries plus UserSecurity, whose own test class covers it, must "
                        + "exhaust the entity set - otherwise an entity's null-identity behaviour is "
                        + "unasserted")
                .containsAll(safe)
                .containsAll(weak)
                .hasSize(safe.size() + weak.size() + 1);
    }

    private static Object newUnsaved(final Class<?> entity) throws ReflectiveOperationException {
        final Constructor<?> provider = entity.getDeclaredConstructor();
        provider.setAccessible(true);
        return provider.newInstance();
    }

    private static org.junit.jupiter.params.provider.Arguments args(
            final String name, final Object sameKeyA, final Object sameKeyB, final Object otherKey) {
        return org.junit.jupiter.params.provider.Arguments.of(name, sameKeyA, sameKeyB, otherKey);
    }

    private static Account account(final long accountId, final String status) {
        return new Account(accountId, status, new BigDecimal("194.00"), new BigDecimal("2020.00"),
                new BigDecimal("1020.00"), "2020-01-01", "2025-01-01", "2022-01-01",
                BigDecimal.ZERO, BigDecimal.ZERO, "12345", "DEFAULT");
    }

    private static Card card(final String cardNumber, final long accountId) {
        return new Card(cardNumber, accountId, "FNAMEAA6 LNAME6", "2025-01-01", "Y");
    }

    private static Customer customer(final long customerId, final String firstName) {
        return new Customer(customerId, firstName, "M", "LNAME6", "1 Main St", "", "", "NY", "USA",
                "12345", "5551234567", "5557654321", "123456789", "GOV123", "1980-01-01", "EFT001",
                "Y", "750");
    }

    private static Transaction transaction(final String transactionId, final String typeCode) {
        return new Transaction(transactionId, typeCode, 5, "POS TERM", "Regular Sales Draft",
                new BigDecimal("100.00"), 9L, "MERCHANT", "CITY", "12345", "4111111111111111",
                "2024-01-01-00.00.00.000000", "2024-01-01-00.00.00.000000");
    }

    /**
     * Builds a staged row whose identity is the ingestion ordinal, not {@code DALYTRAN-ID}. The dataset is
     * unkeyed physical sequential, so the ordinal is the primary key and the identifier is ordinary payload.
     */
    private static DailyTransaction dailyTransaction(final long ingestSequence, final String transactionId) {
        return new DailyTransaction(ingestSequence, transactionId, "01", 5, "POS TERM",
                "Regular Sales Draft", new BigDecimal("100.00"), 9L, "MERCHANT", "CITY", "12345",
                "4111111111111111", "2024-01-01-00.00.00.000000", "2024-01-01-00.00.00.000000");
    }

    private static TransactionCategoryBalanceId balanceId(final long accountId) {
        return new TransactionCategoryBalanceId(accountId, "01", 5);
    }

    private static DisclosureGroupId groupId(final String group) {
        return new DisclosureGroupId(group, "01", 5);
    }

    private static TransactionCategoryId categoryId(final String typeCode, final int categoryCode) {
        return new TransactionCategoryId(typeCode, categoryCode);
    }
}
