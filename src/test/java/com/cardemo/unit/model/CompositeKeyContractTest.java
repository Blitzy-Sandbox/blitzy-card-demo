/*
 * ******************************************************************
 * Program     : CompositeKeyContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Verifies the construction contract shared by the three
 *               composite identifiers in com.cardemo.model.key. Each
 *               validates every component of its application-facing
 *               constructor against the picture clause that component
 *               comes from, each keeps its no-argument constructor
 *               non-public because only the persistence provider needs
 *               it, and each preserves COBOL component order so that a
 *               key-ordered browse behaves as the source's did. Also
 *               verifies that equals, hashCode and toString remain total
 *               over partially populated instances, which the provider
 *               can still produce.
 * Source      : app/cpy/CVTRA01Y.cpy:L6-L8    (TRANCAT key, length 17)
 *               app/cpy/CVTRA02Y.cpy:L6-L8    (DIS-GROUP-KEY, length 16)
 *               app/cpy/CVTRA04Y.cpy          (TRAN-CAT key, length 6)
 *               app/cbl/CBTRN02C.cbl:L469-L471 (all three components are
 *               populated before the READ) and :L481 ('00' OR '23'
 *               concerns row existence, never key completeness)
 *               app/catlg/LISTCAT.txt (TCATBALF KEYLEN 17) @ 7756d89
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
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.cardemo.model.key.DisclosureGroupId;
import com.cardemo.model.key.TransactionCategoryBalanceId;
import com.cardemo.model.key.TransactionCategoryId;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts that all three composite identifiers behave the same way at their boundaries.
 *
 * <p>Uniformity is the point. Before this contract was asserted the three diverged: one validated every
 * component, one validated two of three, and one validated none while documenting a rationale for that
 * permissiveness which the source does not support. A caller cannot reason about a key package whose members
 * disagree about whether {@code null} is acceptable, so the tests below are written across all three rather
 * than per class, and a new identifier added to the package will fail them until it conforms.
 */
@DisplayName("Composite identifier contract: the same boundary rules across all three keys")
class CompositeKeyContractTest {

    /** The three composite identifiers, which are the complete membership of the package. */
    private static final List<Class<?>> KEYS =
            List.of(DisclosureGroupId.class, TransactionCategoryBalanceId.class, TransactionCategoryId.class);

    // ==================================================================
    // 1 - Visibility. The no-argument constructor exists for the
    //     persistence provider and for nobody else.
    // ==================================================================

    @Nested
    @DisplayName("1. Visibility: the no-argument constructor is protected on all three, never public")
    class Visibility {

        @Test
        @DisplayName("no key exposes a public no-argument constructor")
        void theNoArgConstructorIsNeverPublic() {
            for (final Class<?> key : KEYS) {
                final Constructor<?> noArg = noArgConstructorOf(key);

                assertThat(Modifier.isPublic(noArg.getModifiers()))
                        .as("Hibernate instantiates an embeddable reflectively and requires only that "
                                + "the constructor exist, not that it be public. A public one is an "
                                + "invitation to build a key with no components, which then addresses "
                                + "no row and fails a NOT NULL constraint much later. %s",
                                key.getSimpleName())
                        .isFalse();
                assertThat(Modifier.isProtected(noArg.getModifiers()))
                        .as("and protected rather than private, because the provider may subclass. %s",
                                key.getSimpleName())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("the provider path still works, and yields a key whose components are all null")
        void theProviderPathRemainsUsable() {
            for (final Class<?> key : KEYS) {
                final Object staged = instanceOf(key);

                assertThat(componentValuesOf(staged))
                        .as("narrowing the constructor must not break the path it exists for: the "
                                + "provider builds the instance empty and fills it by field access. %s",
                                key.getSimpleName())
                        .isNotEmpty()
                        .allSatisfy(value -> assertThat(value).isNull());
            }
        }

        @Test
        @DisplayName("every key remains Serializable with an explicit serialVersionUID")
        void everyKeyIsSerializableWithAnExplicitVersion() {
            for (final Class<?> key : KEYS) {
                assertThat(Serializable.class.isAssignableFrom(key))
                        .as("JPA requires a composite identifier to be serializable. %s",
                                key.getSimpleName())
                        .isTrue();
                assertThatNoException()
                        .as("an implicit serialVersionUID would change with any recompilation, and "
                                + "-Xlint:all -Werror reports its absence. %s", key.getSimpleName())
                        .isThrownBy(() -> key.getDeclaredField("serialVersionUID"));
            }
        }
    }

    // ==================================================================
    // 2 - Every component of every application-facing constructor is
    //     validated. This is the divergence F6 identified.
    // ==================================================================

    @Nested
    @DisplayName("2. Component validation: all three keys refuse a null in every component")
    class ComponentValidation {

        @Test
        @DisplayName("TransactionCategoryBalanceId refuses a null in each of its three components")
        void categoryBalanceIdRefusesEveryNull() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("app/cbl/CBTRN02C.cbl:L469-L471 moves the account, the type code and the "
                            + "category code into the key before the READ, so all three are populated "
                            + "on every access. The '00' OR '23' acceptance at :L481 concerns whether "
                            + "the row exists, never whether the key is complete, so there is no upsert "
                            + "path that needs a null component")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(null, "01", 1))
                    .withMessageContaining("accountId")
                    .withMessageContaining("TRANCAT-ACCT-ID");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, null, 1))
                    .withMessageContaining("typeCd");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", null))
                    .withMessageContaining("catCd");
        }

        @Test
        @DisplayName("DisclosureGroupId and TransactionCategoryId refuse a null in every component too")
        void theOtherTwoKeysRefuseEveryNull() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId(null, "01", 1))
                    .withMessageContaining("accountGroupId");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT", null, 1))
                    .withMessageContaining("tranTypeCd");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT", "01", null))
                    .withMessageContaining("tranCatCd");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryId(null, 1))
                    .withMessageContaining("tranTypeCd");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryId("01", null))
                    .withMessageContaining("tranCatCd");
        }

        @Test
        @DisplayName("every guard is a private static method on every key")
        void everyGuardIsPrivateAndStatic() {
            for (final Class<?> key : KEYS) {
                final List<Method> guards = Arrays.stream(key.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .filter(method -> method.getName().startsWith("require"))
                        .toList();

                assertThat(guards)
                        .as("%s must validate its own components rather than delegating to the entity "
                                + "that embeds it: the key is the single definition of its own surface",
                                key.getSimpleName())
                        .isNotEmpty()
                        .allSatisfy(guard -> {
                            assertThat(Modifier.isPrivate(guard.getModifiers())).isTrue();
                            assertThat(Modifier.isStatic(guard.getModifiers()))
                                    .as("static avoids this-escape, which -Xlint:all -Werror makes "
                                            + "fatal. %s.%s", key.getSimpleName(), guard.getName())
                                    .isTrue();
                        });
            }
        }
    }

    // ==================================================================
    // 3 - Width and range, taken from the picture clause of each
    //     component and not from a shared default.
    // ==================================================================

    @Nested
    @DisplayName("3. Width and range: exact where the key is fixed, bounded where it is a number")
    class WidthAndRange {

        @Test
        @DisplayName("all three keys require a two character type code exactly, not merely at most")
        void theTypeCodeWidthIsExactOnEveryKey() {
            assertThatNoException().isThrownBy(() -> {
                new TransactionCategoryBalanceId(1L, "01", 1);
                new DisclosureGroupId("DEFAULT", "01", 1);
                new TransactionCategoryId("01", 1);
            });

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the component occupies bytes 12 and 13 of a 17 byte key, so a one character "
                            + "value does not merely under-fill it - it shifts the category code and "
                            + "addresses a different row. That is why the check is exact rather than a "
                            + "maximum, unlike a free text field")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "1", 1))
                    .withMessageContaining("typeCd");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "001", 1))
                    .withMessageContaining("typeCd");

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("DIS-TRAN-TYPE-CD sits at bytes 11 and 12 of a 16 byte key, which is the same "
                            + "situation, so a short value must fail here for the same reason. This key "
                            + "used to accept it while both siblings refused it, and a caller cannot "
                            + "reason about a package whose members disagree on the same picture clause")
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT", "1", 1))
                    .withMessageContaining("tranTypeCd");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryId("1", 1))
                    .withMessageContaining("tranTypeCd");
        }

        @ParameterizedTest(name = "a type code of [{0}] is accepted by all three keys, being two characters")
        @ValueSource(strings = {"01", "99", "  ", "AB", "ab", "\u0000\u0000"})
        @DisplayName("any two character value is accepted, because the picture clause is alphanumeric")
        void anyTwoCharacterTypeCodeIsAccepted(final String candidate) {
            assertThat(candidate).hasSize(2);

            assertThatNoException()
                    .as("the component is PIC X(02), not PIC 9(02), so a blank or alphabetic value is "
                            + "inside the domain. Restricting it to digits would reject values the "
                            + "record can hold, and the exact-width rule must not quietly become a "
                            + "content rule")
                    .isThrownBy(() -> {
                        new TransactionCategoryBalanceId(1L, candidate, 1);
                        new DisclosureGroupId("DEFAULT", candidate, 1);
                        new TransactionCategoryId(candidate, 1);
                    });
        }

        @Test
        @DisplayName("the two width rules are distinct: a ceiling on the group id, exact on the type code")
        void theTwoWidthRulesAreDeliberatelyDifferent() {
            assertThatNoException()
                    .as("app/cbl/CBACT04C.cbl:L415-L460 retries the rate lookup with the bare literal "
                            + "DEFAULT rather than with ten padded bytes, so a ceiling is the correct "
                            + "rule for DIS-ACCT-GROUP-ID and an exact check there would reject the "
                            + "source's own fallback")
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT", "01", 1));

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("while the same short-value shape on the type code is refused, which is the "
                            + "distinction the two helpers exist to keep")
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT", "0", 1))
                    .withMessageContaining("exactly");
        }

        @ParameterizedTest(name = "a category code of {0} is refused as outside PIC 9(04)")
        @ValueSource(ints = {-1, 10_000})
        @DisplayName("the four digit category code is bounded identically on all three keys")
        void categoryCodeBoundsAreUniform(final int candidate) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryBalanceId(1L, "01", candidate))
                    .withMessageContaining("9999");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId("DEFAULT", "01", candidate))
                    .withMessageContaining("9999");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryId("01", candidate))
                    .withMessageContaining("9999");
        }

        @Test
        @DisplayName("both ends of the category code range are accepted, the bound being inclusive")
        void categoryCodeBoundsAreInclusive() {
            assertThatNoException().isThrownBy(() -> {
                new TransactionCategoryBalanceId(1L, "01", 0);
                new TransactionCategoryBalanceId(1L, "01", 9999);
                new DisclosureGroupId("DEFAULT", "01", 0);
                new TransactionCategoryId("01", 9999);
            });
        }

        @Test
        @DisplayName("the eleven digit account key is bounded by its own picture clause")
        void theAccountKeyRangeFollowsItsPictureClause() {
            assertThatNoException()
                    .as("TRANCAT-ACCT-ID is PIC 9(11), which matches the catalogued KEYLEN 17 as "
                            + "11 + 2 + 4, so eleven digits are inside the domain and zero is too")
                    .isThrownBy(() -> {
                        new TransactionCategoryBalanceId(0L, "01", 1);
                        new TransactionCategoryBalanceId(99_999_999_999L, "01", 1);
                    });

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the picture clause carries no S, so a negative account is outside it")
                    .isThrownBy(() -> new TransactionCategoryBalanceId(-1L, "01", 1))
                    .withMessageContaining("accountId");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new TransactionCategoryBalanceId(100_000_000_000L, "01", 1))
                    .withMessageContaining("99999999999");
        }

        @Test
        @DisplayName("the group identifier is bounded at ten characters and admits a blank")
        void theGroupIdentifierWidthIsAMaximum() {
            assertThatNoException()
                    .as("DIS-ACCT-GROUP-ID is PIC X(10) and app/data/ASCII/discgrp.txt carries the "
                            + "literal DEFAULT padded to that width, so a shorter value is normal")
                    .isThrownBy(() -> {
                        new DisclosureGroupId("DEFAULT", "01", 1);
                        new DisclosureGroupId("          ", "01", 1);
                        new DisclosureGroupId("0123456789", "01", 1);
                    });

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new DisclosureGroupId("01234567890", "01", 1))
                    .withMessageContaining("accountGroupId");
        }
    }

    // ==================================================================
    // 4 - Component order, which a key-ordered browse depends on.
    // ==================================================================

    @Nested
    @DisplayName("4. Component order reproduces the copybook, because a browse depends on it")
    class ComponentOrder {

        @Test
        @DisplayName("the declared field order of each key matches its COBOL group")
        void fieldOrderMatchesTheCopybook() {
            assertThat(componentNamesOf(TransactionCategoryBalanceId.class))
                    .as("app/cpy/CVTRA01Y.cpy declares TRANCAT-ACCT-ID, then TRANCAT-TYPE-CD, then "
                            + "TRANCAT-CD. The interest calculation control-breaks on the account "
                            + "because the key leads with it, so reordering these would silently break "
                            + "that break")
                    .containsExactly("accountId", "typeCd", "catCd");

            assertThat(componentNamesOf(DisclosureGroupId.class))
                    .as("app/cpy/CVTRA02Y.cpy:L6-L8 declares the group, then the type, then the category")
                    .containsExactly("accountGroupId", "tranTypeCd", "tranCatCd");

            assertThat(componentNamesOf(TransactionCategoryId.class))
                    .as("app/cpy/CVTRA04Y.cpy declares the type then the category, a six byte key")
                    .containsExactly("tranTypeCd", "tranCatCd");
        }

        @Test
        @DisplayName("every component carries an explicit column name, so no naming strategy can move it")
        void everyComponentNamesItsColumn() {
            for (final Class<?> key : KEYS) {
                assertThat(mappedFieldsOf(key))
                        .as("an embeddable's columns are contributed to the owning table, so an implicit "
                                + "name would depend on a strategy setting rather than on this file. %s",
                                key.getSimpleName())
                        .allSatisfy(field -> assertThat(
                                field.getAnnotation(jakarta.persistence.Column.class))
                                .as("%s.%s declares @Column", key.getSimpleName(), field.getName())
                                .isNotNull());
            }
        }
    }

    // ==================================================================
    // 5 - equals, hashCode and toString stay total, because the provider
    //     can still hand out a partially populated instance.
    // ==================================================================

    @Nested
    @DisplayName("5. Totality: equals, hashCode and toString never throw, even on a staged instance")
    class Totality {

        @Test
        @DisplayName("a fully null staged instance can still be compared, hashed and rendered")
        void aStagedInstanceIsStillUsable() {
            for (final Class<?> key : KEYS) {
                final Object staged = instanceOf(key);
                final Object other = instanceOf(key);

                assertThatNoException()
                        .as("narrowing the constructor does not narrow these three: the provider fills "
                                + "fields one at a time, and a collection or a log statement can touch "
                                + "the instance while it is still incomplete. %s", key.getSimpleName())
                        .isThrownBy(() -> {
                            staged.equals(other);
                            staged.hashCode();
                            staged.toString();
                            staged.equals(null);
                            staged.equals("a foreign type");
                        });

                assertThat(staged)
                        .as("two empty instances of the same key denote the same absent key. %s",
                                key.getSimpleName())
                        .isEqualTo(other)
                        .hasSameHashCodeAs(other);
            }
        }

        @Test
        @DisplayName("two keys built from the same components are equal, and differ on any one component")
        void equalityIsComponentWise() {
            final TransactionCategoryBalanceId left = new TransactionCategoryBalanceId(1L, "01", 5);
            final TransactionCategoryBalanceId right = new TransactionCategoryBalanceId(1L, "01", 5);

            assertThat(left).isEqualTo(right).hasSameHashCodeAs(right);
            assertThat(left).isNotEqualTo(new TransactionCategoryBalanceId(2L, "01", 5));
            assertThat(left).isNotEqualTo(new TransactionCategoryBalanceId(1L, "02", 5));
            assertThat(left).isNotEqualTo(new TransactionCategoryBalanceId(1L, "01", 6));
        }

        @Test
        @DisplayName("toString never quotes a credential or a card number, neither key carrying one")
        void toStringCarriesNoSensitiveComponent() {
            final String rendered = new TransactionCategoryBalanceId(1L, "01", 5).toString();

            assertThat(rendered)
                    .as("these keys are account, type and category codes, none of which is sensitive, so "
                            + "rendering them in full is correct. The assertion exists to catch a future "
                            + "component that is")
                    .doesNotContain("$2a$")
                    .doesNotContain("4111");
        }
    }

    // ==================================================================
    // Reflection helpers.
    // ==================================================================

    @Nested
    @DisplayName("6. A populated key exposes every component and compares by value over all of them")
    final class PopulatedKeys {

        @ParameterizedTest(name = "{0}")
        @ValueSource(classes = {TransactionCategoryBalanceId.class, DisclosureGroupId.class,
            TransactionCategoryId.class})
        @DisplayName("every component accessor returns the value the constructor stored")
        void accessorsReturnWhatWasConstructed(final Class<?> key) {
            final Object populated = CompositeKeys.of(key, 0);
            final List<Field> fields = mappedFieldsOf(key);

            assertThat(fields).as("%s must declare at least two components to be composite",
                    key.getSimpleName()).hasSizeGreaterThan(1);
            for (final Field field : fields) {
                final String accessor = "get" + Character.toUpperCase(field.getName().charAt(0))
                        + field.getName().substring(1);
                final Object viaAccessor;
                final Object viaField;
                try {
                    viaAccessor = key.getMethod(accessor).invoke(populated);
                    field.setAccessible(true);
                    viaField = field.get(populated);
                } catch (final ReflectiveOperationException failure) {
                    throw new AssertionError(key.getSimpleName() + " exposes no working " + accessor,
                            failure);
                }

                assertThat(viaAccessor)
                        .as("%s.%s must return the stored component, unaltered and untrimmed",
                                key.getSimpleName(), accessor)
                        .isNotNull()
                        .isEqualTo(viaField);
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(classes = {TransactionCategoryBalanceId.class, DisclosureGroupId.class,
            TransactionCategoryId.class})
        @DisplayName("a key equals itself, which is the identity shortcut every equals opens with")
        void equalsIsReflexive(final Class<?> key) {
            final Object populated = CompositeKeys.of(key, 0);

            assertThat(populated.equals(populated))
                    .as("%s must take its identity shortcut", key.getSimpleName()).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(classes = {TransactionCategoryBalanceId.class, DisclosureGroupId.class,
            TransactionCategoryId.class})
        @DisplayName("two keys built from the same components are equal and share a hash")
        void valueEqualityHoldsOverAllComponents(final Class<?> key) {
            final Object first = CompositeKeys.of(key, 0);
            final Object second = CompositeKeys.of(key, 0);

            assertThat(first).as("%s compares by value", key.getSimpleName()).isEqualTo(second);
            assertThat(second).as("equality must be symmetric").isEqualTo(first);
            assertThat(first.hashCode())
                    .as("%s equal keys must share a hash, or a keyed lookup breaks",
                            key.getSimpleName())
                    .isEqualTo(second.hashCode());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(classes = {TransactionCategoryBalanceId.class, DisclosureGroupId.class,
            TransactionCategoryId.class})
        @DisplayName("keys differing in one component are unequal, so no component is ignored")
        void differingComponentsAreUnequal(final Class<?> key) {
            assertThat(CompositeKeys.of(key, 0))
                    .as("%s must compare every component, not only the first", key.getSimpleName())
                    .isNotEqualTo(CompositeKeys.of(key, 1));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(classes = {TransactionCategoryBalanceId.class, DisclosureGroupId.class,
            TransactionCategoryId.class})
        @DisplayName("a populated key never equals null, a foreign type, or a staged sibling")
        void equalsRejectsEverythingElse(final Class<?> key) {
            final Object populated = CompositeKeys.of(key, 0);

            assertThat(populated.equals(null)).as("%s must not equal null", key.getSimpleName())
                    .isFalse();
            assertThat(populated.equals("a string"))
                    .as("%s must not equal a foreign type", key.getSimpleName()).isFalse();
            assertThat(populated).as("a populated key must not equal an unpopulated one")
                    .isNotEqualTo(instanceOf(key));
        }

        @Test
        @DisplayName("no two key classes compare equal, even holding the same type and category code")
        void distinctKeyClassesNeverCompareEqual() {
            // All three carry a two character type code and a four digit category code, so a structural
            // comparison would let them collide. The exact-class test in each equals is what prevents it.
            final Object balance = CompositeKeys.of(TransactionCategoryBalanceId.class, 0);
            final Object group = CompositeKeys.of(DisclosureGroupId.class, 0);
            final Object category = CompositeKeys.of(TransactionCategoryId.class, 0);

            assertThat(balance).isNotEqualTo(group).isNotEqualTo(category);
            assertThat(group).isNotEqualTo(balance).isNotEqualTo(category);
            assertThat(category).isNotEqualTo(balance).isNotEqualTo(group);
        }

        @Test
        @DisplayName("the shared factory covers exactly the three keys this class exercises")
        void theSharedFactoryCoversEveryKey() {
            assertThat(CompositeKeys.types())
                    .as("a fourth key would need a factory entry before any test could build it")
                    .containsExactlyInAnyOrderElementsOf(KEYS);
        }
    }

    private static List<Field> mappedFieldsOf(final Class<?> key) {
        return Arrays.stream(key.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    private static List<String> componentNamesOf(final Class<?> key) {
        return mappedFieldsOf(key).stream().map(Field::getName).toList();
    }

    private static List<Object> componentValuesOf(final Object instance) {
        return mappedFieldsOf(instance.getClass()).stream()
                .map(field -> {
                    try {
                        field.setAccessible(true);
                        return field.get(instance);
                    } catch (final ReflectiveOperationException failure) {
                        throw new AssertionError("could not read " + field.getName(), failure);
                    }
                })
                .toList();
    }

    private static Object instanceOf(final Class<?> key) {
        try {
            final Constructor<?> noArg = noArgConstructorOf(key);
            noArg.setAccessible(true);
            return noArg.newInstance();
        } catch (final ReflectiveOperationException failure) {
            throw new AssertionError("could not instantiate " + key.getName(), failure);
        }
    }

    private static Constructor<?> noArgConstructorOf(final Class<?> key) {
        return Arrays.stream(key.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        key.getName() + " declares no no-argument constructor, which JPA requires of "
                                + "every embeddable identifier"));
    }
}
