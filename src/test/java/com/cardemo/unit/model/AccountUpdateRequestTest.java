/*
 * ******************************************************************
 * Program     : AccountUpdateRequestTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the account-update request payload carried by
 *               com.cardemo.model.dto.AccountUpdateRequest against
 *               the frozen map and snapshot groups it was translated
 *               from. In particular it pins that the payload is
 *               IMMUTABLE, that validation cascades into BOTH
 *               snapshot groups, that the source's EXPIRAION
 *               misspelling survives on the wire, that every
 *               REDEFINES overlay carries exactly ONE stored member
 *               with the other reading derived, that the stored side
 *               of the telephone overlay differs between the two
 *               groups exactly as the source differs, and that an
 *               unrecognised property is refused rather than
 *               discarded.
 * Source      : app/cpy-bms/COACTUP.CPY (54 input fields, group
 *               CACTUPAI, lines 17-342) + app/cbl/COACTUPC.cbl
 *               ACUP-OLD-DETAILS:669-756 and ACUP-NEW-DETAILS:757-849
 *               + 1205-COMPARE-OLD-NEW:1681-1777
 *               + 9700-CHECK-CHANGE-IN-REC:4109-4193 @ 7756d89
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
import static org.assertj.core.api.Assertions.catchThrowable;

import com.cardemo.model.dto.AccountUpdateRequest;
import com.cardemo.model.dto.AccountUpdateRequest.NewDetails;
import com.cardemo.model.dto.AccountUpdateRequest.OldDetails;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regression suite for {@link AccountUpdateRequest}, the account-update request payload.
 *
 * <h2>What it does</h2>
 *
 * <p>This is the most intricate payload in the package, because it is the only one that must serve
 * <em>two</em> comparison regimes that normalise the same fields differently.
 * {@code 1205-COMPARE-OLD-NEW} ({@code app/cbl/COACTUPC.cbl:1681-1777}) asks whether the user
 * changed anything, comparing the NEW group against the OLD group.
 * {@code 9700-CHECK-CHANGE-IN-REC} ({@code :4109-4193}) asks whether somebody else changed the
 * record, comparing the live record against the OLD group. Six properties are pinned here that a
 * plausible tidy-up would silently break.</p>
 *
 * <p><strong>The payload is immutable.</strong> An earlier revision exposed 139 setters, so a value
 * could be rewritten between validation and the snapshot comparison. A concurrency guard that can
 * be rewritten after it is validated is not a guard. Every field must be {@code final}, no setter
 * may exist, and each type must be reachable through exactly one all-arguments creator.</p>
 *
 * <p><strong>Validation cascades into both snapshot groups.</strong> An earlier revision left
 * {@code oldDetails} without a cascade and without one width contract, arguing that the OLD group
 * declares no {@code 88}-level condition name. A {@code PIC} clause is itself a contract, and under
 * statelessness the group arrives from the client rather than from {@code 9000-READ-DATA}
 * ({@code app/cbl/COACTUPC.cbl:3610}), so it is the untrusted operand of the concurrency guard.</p>
 *
 * <p><strong>Each REDEFINES overlay carries one stored member.</strong>
 * {@code ACUP-OLD-CURR-BAL PIC X(12)} at {@code app/cbl/COACTUPC.cbl:675} and
 * {@code ACUP-OLD-CURR-BAL-N PIC S9(10)V99} at {@code :676-677} name the <em>same twelve
 * bytes</em>. A model in which the text and the number are independently writable describes a byte
 * state that cannot exist. The stored side is the side the source assigns; the other reading is a
 * derived accessor deliberately not named as a bean property, so the serializer neither emits it
 * nor binds it.</p>
 *
 * <p><strong>The telephone overlay stores opposite sides on the two groups.</strong> The OLD group
 * is assigned whole, by {@code MOVE CUST-PHONE-NUM-1} at {@code app/cbl/COACTUPC.cbl:3876}, and
 * never by part. The NEW group is assigned only by part, at {@code :1359-1396}, and never whole.
 * Flattening either side to match the other would name a value that group never holds.</p>
 *
 * <p><strong>The source's misspelling survives.</strong> {@code ACUP-OLD-EXPIRAION-DATE}
 * ({@code :690}) and {@code ACUP-NEW-EXPIRAION-DATE} ({@code :778}) are misspelled in the frozen
 * corpus. The wire name must be {@code expiraionDate}, and the corrected spelling must be actively
 * refused rather than quietly accepted as an alias.</p>
 *
 * <p><strong>An unrecognised property is refused.</strong> The framework disables failure on
 * unknown properties by default and no profile in this repository re-enables it, so a declarative
 * type-level annotation would be inert. The guard has to hold under a lenient mapper as well as a
 * strict one, because otherwise a derived view submitted as though it were a member would be
 * silently discarded - which looks like acceptance.</p>
 *
 * <h2>How to run it</h2>
 *
 * <p>{@code ./mvnw -B -o test -Dtest=AccountUpdateRequestTest} runs this class alone;
 * {@code ./mvnw -B test} runs it with the rest of the unit tier. It needs no container, no Spring
 * context, no database and no network.</p>
 *
 * <h2>Failure modes and troubleshooting</h2>
 *
 * <p>A failure in {@link Immutability} means a setter or a non-final field was reintroduced. A
 * failure in {@link SnapshotCascade} means a cascade or a width contract was dropped. A failure in
 * {@link OverlayCanonicalisation} means an overlay reading became independently writable again -
 * check whether a stored member was added alongside a derived view of the same bytes. A failure in
 * {@link SourceFaithfulNaming} means the misspelling was corrected, which is a parity break. In
 * every case the frozen corpus is right and the code is wrong.</p>
 *
 * @see AccountUpdateRequest
 */
@DisplayName("AccountUpdateRequest - app/cpy-bms/COACTUP.CPY group CACTUPAI + app/cbl/COACTUPC.cbl")
final class AccountUpdateRequestTest {

    /** Screen fields declared by {@code app/cpy-bms/COACTUP.CPY} between lines 17 and 342. */
    private static final int MAP_FIELDS = 54;

    /** Top-level members: the 54 screen fields plus the two snapshot groups. */
    private static final int TOP_LEVEL_MEMBERS = 56;

    /** Members of {@code ACUP-OLD-DETAILS} after overlay canonicalisation. */
    private static final int OLD_MEMBERS = 29;

    /** Members of {@code ACUP-NEW-DETAILS} after overlay canonicalisation. */
    private static final int NEW_MEMBERS = 35;

    /** Declared width of a snapshot telephone member, {@code PIC X(15)}. */
    private static final int PHONE_WIDTH = 15;

    /** Bound on cause-chain traversal, so a self-referential cause cannot spin. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper();

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Supplies the three payload types together with the member count each must declare.
     *
     * @return the outer request type and both nested snapshot groups, each with its member count
     */
    static Stream<Arguments> payloadTypes() {
        return Stream.of(
                Arguments.of(AccountUpdateRequest.class, TOP_LEVEL_MEMBERS),
                Arguments.of(OldDetails.class, OLD_MEMBERS),
                Arguments.of(NewDetails.class, NEW_MEMBERS));
    }

    /**
     * Supplies the three payload types on their own.
     *
     * @return the outer request type and both nested snapshot groups
     */
    static Stream<Class<?>> payloadTypesOnly() {
        return Stream.of(AccountUpdateRequest.class, OldDetails.class, NewDetails.class);
    }

    /**
     * Returns the sole declared constructor of a payload type.
     *
     * @param type the payload type
     * @return its only constructor
     */
    private static Constructor<?> soleConstructor(final Class<?> type) {
        final Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertThat(constructors)
                .as("%s must offer exactly one way in, so that no path bypasses the width contracts",
                        type.getSimpleName())
                .hasSize(1);
        return constructors[0];
    }

    /**
     * Returns the constructor parameter names of a payload type, in declaration order.
     *
     * @param type the payload type
     * @return the parameter names
     */
    private static List<String> parameterNames(final Class<?> type) {
        return Arrays.stream(soleConstructor(type).getParameters())
                .map(Parameter::getName)
                .toList();
    }

    /**
     * Returns the non-static field names of a payload type, in declaration order.
     *
     * @param type the payload type
     * @return the instance field names
     */
    private static List<String> fieldNames(final Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .toList();
    }

    /**
     * Builds an instance of a payload type in which one named member holds {@code value} and every
     * other member is {@code null}.
     *
     * @param type       the payload type
     * @param memberName the member to populate
     * @param value      the value to place in it
     * @param <T>        the payload type
     * @return the constructed instance
     */
    private static <T> T withOnly(final Class<T> type, final String memberName, final String value) {
        final Map<String, String> single = new LinkedHashMap<>();
        single.put(memberName, value);
        return withMembers(type, single);
    }

    /**
     * Builds an instance of a payload type from a sparse map of member names to values.
     *
     * @param type   the payload type
     * @param values the members to populate; every other member is {@code null}
     * @param <T>    the payload type
     * @return the constructed instance
     */
    private static <T> T withMembers(final Class<T> type, final Map<String, String> values) {
        final List<String> names = parameterNames(type);
        final Object[] arguments = new Object[names.size()];
        values.forEach((name, value) -> {
            final int index = names.indexOf(name);
            assertThat(index)
                    .withFailMessage("%s declares no member named %s", type.getSimpleName(), name)
                    .isNotNegative();
            arguments[index] = value;
        });
        try {
            return type.cast(soleConstructor(type).newInstance(arguments));
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot construct " + type.getSimpleName(), cause);
        }
    }

    /**
     * Builds an instance of a payload type in which every member holds a value exactly as wide as
     * its declared {@code PIC} clause, composed only of decimal digits.
     *
     * <p>Digits satisfy every overlay simultaneously: a twelve-digit money image decodes as an
     * unsigned zoned decimal, an eight-digit date slices cleanly at 4/2/2, and a three-digit credit
     * score reads as a number. That makes this instance the widest legal payload, which is the one
     * on which no accessor may throw.</p>
     *
     * @param type the payload type
     * @param <T>  the payload type
     * @return the fully populated instance
     */
    private static <T> T fullyPopulated(final Class<T> type) {
        final Map<String, String> values = new LinkedHashMap<>();
        for (final String member : fieldNames(type)) {
            if (isSnapshotGroup(type, member)) {
                continue;
            }
            values.put(member, "0".repeat(declaredWidth(type, member)));
        }
        return withMembers(type, values);
    }

    /**
     * Reports whether a top-level member is one of the two nested snapshot groups.
     *
     * @param type       the payload type
     * @param memberName the member to test
     * @return {@code true} when the member is a snapshot group rather than a screen field
     */
    private static boolean isSnapshotGroup(final Class<?> type, final String memberName) {
        try {
            return !String.class.equals(type.getDeclaredField(memberName).getType());
        } catch (NoSuchFieldException cause) {
            throw new AssertionError(type.getSimpleName() + " has no member " + memberName, cause);
        }
    }

    /**
     * Reads the {@code @Size} maximum declared on a member's backing field.
     *
     * <p>Read from the field rather than from a getter, because that is where the production code
     * declares the annotation.</p>
     *
     * @param type       the payload type
     * @param memberName the member whose declared width is wanted
     * @return the declared maximum
     */
    private static int declaredWidth(final Class<?> type, final String memberName) {
        final Size size = sizeOf(type, memberName);
        assertThat(size)
                .withFailMessage("%s.%s declares no @Size, so its PIC width is unenforced",
                        type.getSimpleName(), memberName)
                .isNotNull();
        return size.max();
    }

    /**
     * Returns the {@code @Size} annotation on a member's backing field, or {@code null}.
     *
     * @param type       the payload type
     * @param memberName the member to inspect
     * @return the annotation, or {@code null} when the member declares none
     */
    private static Size sizeOf(final Class<?> type, final String memberName) {
        try {
            return type.getDeclaredField(memberName).getAnnotation(Size.class);
        } catch (NoSuchFieldException cause) {
            throw new AssertionError(type.getSimpleName() + " has no member " + memberName, cause);
        }
    }

    /**
     * Serializes a payload and returns its JSON property names.
     *
     * @param payload the payload to serialize
     * @return the emitted property names
     */
    private static Set<String> serializedProperties(final Object payload) {
        return STRICT_MAPPER
                .convertValue(payload, new TypeReference<LinkedHashMap<String, Object>>() { })
                .keySet();
    }

    /**
     * Invokes a callable that must be refused, and returns the refusal.
     *
     * <p>The refusal may arrive on its own or wrapped by the serializer, so the whole cause chain is
     * searched rather than only the root. That keeps the assertion about <em>what</em> was refused
     * rather than about how many layers the serializer happened to add.</p>
     *
     * @param callable the code that must be refused
     * @return the {@link IllegalArgumentException} found in the thrown cause chain
     */
    private static IllegalArgumentException refusalOf(final ThrowingCallable callable) {
        final Throwable thrown = catchThrowable(callable);
        assertThat(thrown).as("the payload must be refused rather than accepted").isNotNull();
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof IllegalArgumentException refused) {
                return refused;
            }
            current = current.getCause();
        }
        throw new AssertionError("no IllegalArgumentException in the cause chain of " + thrown);
    }

    /**
     * Returns a copy of {@code payload} in which one snapshot group is replaced.
     *
     * <p>The payload is immutable, so this rebuilds it through the canonical constructor rather than
     * mutating it - which is the point of the {@link Immutability} group.</p>
     *
     * @param payload the payload to copy
     * @param group   {@code oldDetails} or {@code newDetails}
     * @param value   the group to place in the copy
     * @return the rebuilt payload
     */
    private static AccountUpdateRequest replaceGroup(final AccountUpdateRequest payload,
            final String group, final Object value) {
        final List<String> names = parameterNames(AccountUpdateRequest.class);
        final Object[] arguments = new Object[names.size()];
        for (int index = 0; index < names.size(); index++) {
            final String name = names.get(index);
            arguments[index] = name.equals(group) ? value : readMember(payload, name);
        }
        try {
            return (AccountUpdateRequest) soleConstructor(AccountUpdateRequest.class)
                    .newInstance(arguments);
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot rebuild AccountUpdateRequest", cause);
        }
    }

    /**
     * Reads one member of a payload through its public accessor.
     *
     * @param payload    the payload to read
     * @param memberName the member to read
     * @return the member's value
     */
    private static Object readMember(final Object payload, final String memberName) {
        final String accessor = "get" + Character.toUpperCase(memberName.charAt(0))
                + memberName.substring(1);
        try {
            return payload.getClass().getMethod(accessor).invoke(payload);
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError("cannot read member " + memberName, cause);
        }
    }

    /**
     * Returns the names of every member of a payload type that declares no {@code @Size}.
     *
     * @param type the payload type
     * @return the unconstrained member names, empty when every member carries a width contract
     */
    private static List<String> membersWithoutAWidthContract(final Class<?> type) {
        final List<String> unconstrained = new ArrayList<>();
        for (final String member : fieldNames(type)) {
            if (isSnapshotGroup(type, member)) {
                continue;
            }
            if (sizeOf(type, member) == null) {
                unconstrained.add(member);
            }
        }
        return unconstrained;
    }

    /**
     * Returns the public zero-argument declared methods of a payload type.
     *
     * @param type the payload type
     * @return the accessors and derived views the type exposes
     */
    private static List<Method> zeroArgumentAccessors(final Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getParameterCount() == 0)
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .toList();
    }

    @Nested
    @DisplayName("1. Field contract: 54 screen fields plus two snapshot groups")
    final class FieldContract {

        @Test
        @DisplayName("declares the 54 COACTUP screen fields plus oldDetails and newDetails")
        void declaresFiftyFourScreenFieldsPlusTwoGroups() {
            final List<String> names = fieldNames(AccountUpdateRequest.class);
            assertThat(names)
                    .as("app/cpy-bms/COACTUP.CPY declares 54 input fields between line 17 and "
                            + "line 342; the two snapshot groups are additional")
                    .hasSize(TOP_LEVEL_MEMBERS)
                    .endsWith("oldDetails", "newDetails");
            assertThat(names.size() - 2).isEqualTo(MAP_FIELDS);
        }

        @Test
        @DisplayName("carries every screen field as text, so no inbound code binds to an enum")
        void carriesEveryScreenFieldAsText() {
            assertThat(declaredWidth(AccountUpdateRequest.class, "accountStatus")).isEqualTo(1);
            assertThat(declaredWidth(AccountUpdateRequest.class, "primaryCardHolderIndicator"))
                    .isEqualTo(1);
            assertThat(Arrays.stream(AccountUpdateRequest.class.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> field.getType().isEnum())
                    .toList())
                    .as("binding an inbound one-character code to an enum would turn an "
                            + "out-of-domain value into a framework error instead of the source's "
                            + "own message")
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} declares {1} members and {1} constructor parameters")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypes")
        @DisplayName("declares one constructor parameter per member on each type")
        void declaresOneParameterPerMember(final Class<?> type, final int expectedMembers) {
            assertThat(fieldNames(type)).hasSize(expectedMembers);
            assertThat(soleConstructor(type).getParameterCount()).isEqualTo(expectedMembers);
            assertThat(parameterNames(type))
                    .as("a parameter that matches no member would be silently discarded")
                    .containsExactlyElementsOf(fieldNames(type));
        }

        @Test
        @DisplayName("decomposes the social security number three ways on NEW and one way on OLD")
        void reproducesTheSocialSecurityAsymmetry() {
            assertThat(fieldNames(OldDetails.class))
                    .as("ACUP-OLD-CUST-SSN-X PIC X(09) at app/cbl/COACTUPC.cbl:742 is one flat "
                            + "field")
                    .contains("ssn")
                    .doesNotContain("ssnPart1", "ssnPart2", "ssnPart3");
            assertThat(fieldNames(NewDetails.class))
                    .as("ACUP-NEW-CUST-SSN-X at app/cbl/COACTUPC.cbl:830-833 is three parts")
                    .contains("ssnPart1", "ssnPart2", "ssnPart3")
                    .doesNotContain("ssn");
            assertThat(declaredWidth(OldDetails.class, "ssn")).isEqualTo(9);
            assertThat(declaredWidth(NewDetails.class, "ssnPart1")).isEqualTo(3);
            assertThat(declaredWidth(NewDetails.class, "ssnPart2")).isEqualTo(2);
            assertThat(declaredWidth(NewDetails.class, "ssnPart3")).isEqualTo(4);
        }

        @Test
        @DisplayName("stores every snapshot date compact at eight characters, never dash-separated")
        void storesSnapshotDatesCompact() {
            for (final String date : List.of("openDate", "expiraionDate", "reissueDate",
                    "dateOfBirth")) {
                assertThat(declaredWidth(OldDetails.class, date))
                        .as("%s is PIC X(08) on the snapshot against PIC X(10) on the live record, "
                                + "which is why 9700-CHECK-CHANGE-IN-REC compares offsets 1/6/9 "
                                + "against 1/5/7 at app/cbl/COACTUPC.cbl:4174-4179", date)
                        .isEqualTo(8);
                assertThat(declaredWidth(NewDetails.class, date)).isEqualTo(8);
            }
        }

        @Test
        @DisplayName("keeps the screen expiry fields named after the map, which is not misspelled")
        void keepsScreenExpiryFieldsNamedAfterTheMap() {
            assertThat(fieldNames(AccountUpdateRequest.class))
                    .as("app/cpy-bms/COACTUP.CPY declares EXPYEARI, EXPMONI and EXPDAYI at lines "
                            + "96, 102 and 108; the misspelling belongs to the copybook snapshot "
                            + "fields, not to the map")
                    .contains("expiryDateYear", "expiryDateMonth", "expiryDateDay");
        }
    }

    @Nested
    @DisplayName("2. Immutability: nothing can change between binding and comparison")
    final class Immutability {

        @ParameterizedTest(name = "{0} declares no setter")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no setter on any of the three types")
        void declaresNoSetter(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter(name -> name.startsWith("set"))
                    .toList())
                    .as("an earlier revision exposed 139 setters across these three types, so a "
                            + "value could be rewritten after validation and before the snapshot "
                            + "comparison")
                    .isEmpty();
        }

        @ParameterizedTest(name = "every field of {0} is final")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares every instance field final")
        void declaresEveryFieldFinal(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList())
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} has exactly one constructor taking {1} parameters")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypes")
        @DisplayName("offers exactly one way in, so no path bypasses the width contracts")
        void offersExactlyOneWayIn(final Class<?> type, final int expectedMembers) {
            assertThat(soleConstructor(type).getParameterCount()).isEqualTo(expectedMembers);
        }

        @ParameterizedTest(name = "{0} declares no mutable static state")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no mutable static state")
        void declaresNoMutableStaticState(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .filter(field -> !Modifier.isFinal(field.getModifiers()))
                    .map(Field::getName)
                    .toList())
                    .isEmpty();
        }

        @Test
        @DisplayName("binds through the creator, so the payload is complete when it is validated")
        void bindsThroughTheCreator() throws Exception {
            final String json = "{\"accountId\":\"00000000001\","
                    + "\"oldDetails\":{\"activeStatus\":\"Y\"},"
                    + "\"newDetails\":{\"activeStatus\":\"N\"}}";
            final AccountUpdateRequest bound =
                    STRICT_MAPPER.readValue(json, AccountUpdateRequest.class);
            assertThat(bound.getAccountId()).isEqualTo("00000000001");
            assertThat(bound.getOldDetails().getActiveStatus()).isEqualTo("Y");
            assertThat(bound.getNewDetails().getActiveStatus()).isEqualTo("N");
        }

        @ParameterizedTest(name = "{0} does not implement Serializable")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("does not implement Serializable, given the protected data it carries")
        void doesNotImplementSerializable(final Class<?> type) {
            assertThat(Serializable.class.isAssignableFrom(type))
                    .withFailMessage("%s must not be exposed to native deserialization",
                            type.getSimpleName())
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} declares no toString")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares no toString, so the inherited one cannot leak a member")
        void declaresNoToString(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .filter("toString"::equals)
                    .toList())
                    .as("%s carries social security numbers, dates of birth, telephone numbers and "
                            + "names; the inherited Object.toString emits none of them",
                            type.getSimpleName())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("3. Snapshot cascade: both groups validated, only NEW carries a range")
    final class SnapshotCascade {

        @ParameterizedTest(name = "{0} carries @Valid")
        @ValueSource(strings = {"oldDetails", "newDetails"})
        @DisplayName("cascades into oldDetails as well as newDetails")
        void cascadesIntoBothGroups(final String group) throws NoSuchFieldException {
            assertThat(AccountUpdateRequest.class.getDeclaredField(group).getAnnotation(Valid.class))
                    .as("a cascade omitted is a cascade that never fires, and %s is untrusted "
                            + "client input under statelessness", group)
                    .isNotNull();
        }

        @Test
        @DisplayName("reports a violation for every over-wide oldDetails member")
        void reportsViolationsInsideOldDetails() {
            final Map<String, String> hostile = new LinkedHashMap<>();
            hostile.put("addressStateCode", "TOO-WIDE");
            hostile.put("currentBalance", "9".repeat(40));
            hostile.put("ficoScore", "9999");
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withMembers(OldDetails.class, hostile));
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    VALIDATOR.validate(payload);
            assertThat(violations)
                    .as("an earlier revision produced zero violations here, because oldDetails "
                            + "carried neither a cascade nor a single width contract")
                    .isNotEmpty();
            assertThat(violations.stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .toList())
                    .containsExactlyInAnyOrder("oldDetails.addressStateCode",
                            "oldDetails.currentBalance", "oldDetails.ficoScore");
        }

        @Test
        @DisplayName("reports a violation for an over-wide newDetails member as well")
        void reportsViolationsInsideNewDetails() {
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "newDetails",
                    withOnly(NewDetails.class, "phoneNumber1AreaCode", "5555"));
            assertThat(VALIDATOR.validate(payload).stream()
                    .map(violation -> violation.getPropertyPath().toString())
                    .toList())
                    .containsExactly("newDetails.phoneNumber1AreaCode");
        }

        @Test
        @DisplayName("reports no violation for a snapshot whose members all fit their PIC clauses")
        void acceptsAWellFormedSnapshot() {
            final Map<String, String> wellFormed = new LinkedHashMap<>();
            wellFormed.put("accountId", "00000000001");
            wellFormed.put("addressStateCode", "NY");
            wellFormed.put("currentBalance", "00000019400");
            wellFormed.put("ficoScore", "720");
            wellFormed.put("dateOfBirth", "19750412");
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withMembers(OldDetails.class, wellFormed));
            assertThat(VALIDATOR.validate(payload))
                    .as("enforcing the declared width can only reject what the source could never "
                            + "have produced")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts an entirely absent snapshot, because absence is not a violation")
        void acceptsAnAbsentSnapshot() {
            assertThat(VALIDATOR.validate(withMembers(AccountUpdateRequest.class, Map.of())))
                    .isEmpty();
            assertThat(VALIDATOR.validate(withMembers(OldDetails.class, Map.of()))).isEmpty();
            assertThat(VALIDATOR.validate(withMembers(NewDetails.class, Map.of()))).isEmpty();
        }

        @Test
        @DisplayName("accepts a snapshot whose members are blank or low-values at full width")
        void acceptsBlankAndLowValueSnapshotMembers() {
            final Map<String, String> blanks = new LinkedHashMap<>();
            blanks.put("currentBalance", " ".repeat(12));
            blanks.put("dateOfBirth", "\u0000".repeat(8));
            blanks.put("groupId", "");
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withMembers(OldDetails.class, blanks));
            assertThat(VALIDATOR.validate(payload))
                    .as("INITIALIZE at app/cbl/COACTUPC.cbl:1047 and MOVE LOW-VALUES at :1359 both "
                            + "produce states the source holds, so neither may be rejected")
                    .isEmpty();
        }

        @ParameterizedTest(name = "OldDetails.{0} declares @Size(max = {1})")
        @CsvSource({
            "accountId,11", "activeStatus,1", "currentBalance,12", "creditLimit,12",
            "cashCreditLimit,12", "openDate,8", "expiraionDate,8", "reissueDate,8",
            "currentCycleCredit,12", "currentCycleDebit,12", "groupId,10", "customerId,9",
            "firstName,25", "middleName,25", "lastName,25", "addressLine1,50", "addressLine2,50",
            "addressLine3,50", "addressStateCode,2", "addressCountryCode,3", "addressZip,10",
            "phoneNumber1,15", "phoneNumber2,15", "ssn,9", "governmentIssuedId,20",
            "dateOfBirth,8", "eftAccountId,10", "primaryCardHolderIndicator,1", "ficoScore,3",
        })
        @DisplayName("declares the source PIC width on every one of the 29 OLD members")
        void declaresEveryOldWidth(final String memberName, final int expectedWidth) {
            assertThat(declaredWidth(OldDetails.class, memberName)).isEqualTo(expectedWidth);
        }

        @ParameterizedTest(name = "NewDetails.{0} declares @Size(max = {1})")
        @CsvSource({
            "accountId,11", "activeStatus,1", "currentBalance,12", "creditLimit,12",
            "cashCreditLimit,12", "openDate,8", "expiraionDate,8", "reissueDate,8",
            "currentCycleCredit,12", "currentCycleDebit,12", "groupId,10", "customerId,9",
            "firstName,25", "middleName,25", "lastName,25", "addressLine1,50", "addressLine2,50",
            "addressLine3,50", "addressStateCode,2", "addressCountryCode,3", "addressZip,10",
            "phoneNumber1AreaCode,3", "phoneNumber1Prefix,3", "phoneNumber1LineNumber,4",
            "phoneNumber2AreaCode,3", "phoneNumber2Prefix,3", "phoneNumber2LineNumber,4",
            "ssnPart1,3", "ssnPart2,2", "ssnPart3,4", "governmentIssuedId,20", "dateOfBirth,8",
            "eftAccountId,10", "primaryCardHolderIndicator,1", "ficoScore,3",
        })
        @DisplayName("declares the source PIC width on every one of the 35 NEW members")
        void declaresEveryNewWidth(final String memberName, final int expectedWidth) {
            assertThat(declaredWidth(NewDetails.class, memberName)).isEqualTo(expectedWidth);
        }

        @ParameterizedTest(name = "{0} leaves no member without a width contract")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("leaves no member of any type without a width contract")
        void leavesNoMemberUnconstrained(final Class<?> type) {
            assertThat(membersWithoutAWidthContract(type)).isEmpty();
        }

        @Test
        @DisplayName("expresses the credit-score range as a NEW-only predicate, not a constraint")
        void expressesTheRangeAsAPredicateOnNewOnly() {
            assertThat(Arrays.stream(NewDetails.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter("ficoScoreIsInValidRange"::equals)
                    .toList())
                    .as("88 FICO-RANGE-IS-VALID at app/cbl/COACTUPC.cbl:848-849 is declared on the "
                            + "NEW numeric member only")
                    .hasSize(1);
            assertThat(Arrays.stream(OldDetails.class.getDeclaredMethods())
                    .map(Method::getName)
                    .filter("ficoScoreIsInValidRange"::equals)
                    .toList())
                    .as("the OLD group declares no 88-level at all, so it must have no twin")
                    .isEmpty();
            assertThat(VALIDATOR.validate(withOnly(NewDetails.class, "ficoScore", "299")))
                    .as("the source moves the screen value into storage unvalidated at "
                            + "app/cbl/COACTUPC.cbl:1283 and emits its own message, so a bean "
                            + "constraint would substitute a framework rejection")
                    .isEmpty();
        }

        @ParameterizedTest(name = "credit score \"{0}\" in range: {1}")
        @CsvSource({
            "300,true", "850,true", "720,true", "299,false", "851,false", "000,false",
        })
        @DisplayName("reproduces the 300 through 850 range of the NEW 88-level exactly")
        void reproducesTheCreditScoreRange(final String image, final boolean inRange) {
            assertThat(withOnly(NewDetails.class, "ficoScore", image).ficoScoreIsInValidRange())
                    .isEqualTo(inRange);
        }

        @Test
        @DisplayName("treats an unreadable credit score as out of range rather than throwing")
        void treatsAnUnreadableCreditScoreAsOutOfRange() {
            assertThat(withMembers(NewDetails.class, Map.of()).ficoScoreIsInValidRange()).isFalse();
            assertThat(withOnly(NewDetails.class, "ficoScore", "abc").ficoScoreIsInValidRange())
                    .isFalse();
        }

        @Test
        @DisplayName("names the member and the source locator in a violation, never the value")
        void violationMessageNeverQuotesTheValue() {
            final AccountUpdateRequest payload = replaceGroup(
                    withMembers(AccountUpdateRequest.class, Map.of()), "oldDetails",
                    withOnly(OldDetails.class, "ssn", "123456789-LEAKED"));
            final Set<ConstraintViolation<AccountUpdateRequest>> violations =
                    VALIDATOR.validate(payload);
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .contains("OldDetails.ssn")
                    .contains("ACUP-OLD-CUST-SSN-X")
                    .contains("app/cbl/COACTUPC.cbl:742")
                    .doesNotContain("LEAKED")
                    .doesNotContain("123456789");
        }
    }

    @Nested
    @DisplayName("4. Source-faithful naming: the EXPIRAION misspelling survives")
    final class SourceFaithfulNaming {

        @ParameterizedTest(name = "{0} declares expiraionDate and not expirationDate")
        @MethodSource("groups")
        @DisplayName("declares the misspelled member on both snapshot groups")
        void declaresTheMisspelledMember(final Class<?> type) {
            assertThat(fieldNames(type))
                    .as("ACUP-OLD-EXPIRAION-DATE at app/cbl/COACTUPC.cbl:690 and "
                            + "ACUP-NEW-EXPIRAION-DATE at :778 are both misspelled in the frozen "
                            + "corpus")
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
        }

        @ParameterizedTest(name = "{0} exposes getExpiraionDate and no corrected alias")
        @MethodSource("groups")
        @DisplayName("exposes the misspelled accessor and its misspelled component views")
        void exposesTheMisspelledAccessor(final Class<?> type) {
            final List<String> methods = Arrays.stream(type.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();
            assertThat(methods)
                    .contains("getExpiraionDate", "expiraionDateYear", "expiraionDateMonth",
                            "expiraionDateDay")
                    .doesNotContain("getExpirationDate", "expirationDateYear",
                            "expirationDateMonth", "expirationDateDay");
        }

        @Test
        @DisplayName("emits the misspelled JSON name and never the corrected one")
        void emitsTheMisspelledJsonName() {
            assertThat(serializedProperties(
                    withOnly(OldDetails.class, "expiraionDate", "20250131")))
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
            assertThat(serializedProperties(
                    withOnly(NewDetails.class, "expiraionDate", "20260131")))
                    .contains("expiraionDate")
                    .doesNotContain("expirationDate");
        }

        @ParameterizedTest(name = "the corrected spelling in {0} is refused")
        @ValueSource(strings = {"oldDetails", "newDetails"})
        @DisplayName("refuses the corrected spelling rather than accepting it as an alias")
        void refusesTheCorrectedSpelling(final String group) {
            final String json = "{\"" + group + "\":{\"expirationDate\":\"20250131\"}}";
            for (final ObjectMapper mapper : List.of(STRICT_MAPPER, LENIENT_MAPPER)) {
                assertThat(refusalOf(() -> mapper.readValue(json, AccountUpdateRequest.class)))
                        .as("accepting the corrected spelling as an alias would make the wire "
                                + "contract diverge from the frozen corpus")
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("binds the misspelled name, so the contract is usable and not merely strict")
        void bindsTheMisspelledName() throws Exception {
            final String json = "{\"oldDetails\":{\"expiraionDate\":\"20250131\"},"
                    + "\"newDetails\":{\"expiraionDate\":\"20260131\"}}";
            final AccountUpdateRequest bound =
                    STRICT_MAPPER.readValue(json, AccountUpdateRequest.class);
            assertThat(bound.getOldDetails().getExpiraionDate()).isEqualTo("20250131");
            assertThat(bound.getNewDetails().getExpiraionDate()).isEqualTo("20260131");
        }

        /**
         * Supplies the two snapshot group types.
         *
         * @return the OLD and NEW snapshot group types
         */
        static Stream<Class<?>> groups() {
            return Stream.of(OldDetails.class, NewDetails.class);
        }
    }

    @Nested
    @DisplayName("5. Overlay canonicalisation: one stored member per REDEFINES")
    final class OverlayCanonicalisation {

        @ParameterizedTest(name = "OldDetails.{0} is stored once with a derived numeric view")
        @ValueSource(strings = {"currentBalance", "creditLimit", "cashCreditLimit",
            "currentCycleCredit", "currentCycleDebit"})
        @DisplayName("stores each money overlay as the display text the source holds")
        void storesMoneyAsDisplayText(final String memberName) {
            assertThat(declaredWidth(OldDetails.class, memberName))
                    .as("PIC X(12), overlaid by PIC S9(10)V99 at app/cbl/COACTUPC.cbl:676-707")
                    .isEqualTo(12);
            final Set<String> emitted = serializedProperties(
                    withOnly(OldDetails.class, memberName, "00000001940{"));
            assertThat(emitted).contains(memberName);
            assertThat(emitted)
                    .as("the numeric reading names the same twelve bytes, so it must not be a "
                            + "separately writable property")
                    .doesNotContain(memberName + "Amount");
        }

        @ParameterizedTest(name = "\"{0}\" decodes to {1}")
        @CsvSource({
            "00000001940{,194.00", "00000001940},-194.00", "00000000001A,0.11",
            "00000000001J,-0.11", "000000019400,194.00", "000000000000,0.00",
            "00000001940I,194.09", "00000001940R,-194.09",
        })
        @DisplayName("decodes the zoned-decimal overpunch sign through the derived view")
        void decodesTheOverpunchSign(final String image, final String expected) {
            assertThat(withOnly(OldDetails.class, "currentBalance", image).currentBalanceAmount())
                    .as("app/data/ASCII/acctdata.txt:1 records 00000001940{ for +194.00; the "
                            + "decode table is { = +0, A-I = +1..+9, } = -0, J-R = -1..-9")
                    .isEqualByComparingTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("decodes the NEW money overlay the same way as the OLD one")
        void decodesTheNewMoneyOverlayIdentically() {
            final NewDetails edited = withOnly(NewDetails.class, "creditLimit", "00000500000{");
            assertThat(edited.creditLimitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
            assertThat(serializedProperties(edited))
                    .contains("creditLimit")
                    .doesNotContain("creditLimitAmount");
        }

        @ParameterizedTest(name = "\"{0}\" has no numeric reading")
        @ValueSource(strings = {"            ", "0000000194  ", "00000001940 ", "abcdefghijkl",
            "0000000194", "0000000019400", "0000000X001{", "00000 01940A", "+0000019400{"})
        @DisplayName("returns no amount when the twelve bytes are not a zoned-decimal number")
        void returnsNoAmountForANonNumericImage(final String image) {
            assertThat(withOnly(OldDetails.class, "currentBalance", image).currentBalanceAmount())
                    .as("the source stores whatever was typed and emits its own message, so "
                            + "throwing here would substitute a framework error and collapse the "
                            + "three-state model of app/cpy/CSSETATY.cpy:17-27")
                    .isNull();
        }

        @Test
        @DisplayName("rejects a non-digit body even when the trailing overpunch sign is itself valid")
        void rejectsANonDigitBodyDespiteAValidOverpunchSign() {
            assertThat(withOnly(OldDetails.class, "currentBalance", "00000001940{").currentBalanceAmount())
                    .as("the control image is the first money field of app/data/ASCII/acctdata.txt:1, where "
                            + "'{' denotes +0 and the field decodes to +194.00")
                    .isEqualByComparingTo(new BigDecimal("194.00"));

            assertThat(withOnly(OldDetails.class, "currentBalance", "0000000X001{").currentBalanceAmount())
                    .as("'{' is a valid trailing sign, so rejection here can only come from the eleven "
                            + "leading bytes. Decoding is position-aware from the PIC clause, which is why "
                            + "a letter legitimately appearing in a text field is still refused in a money "
                            + "field rather than silently absorbed")
                    .isNull();
            assertThat(withOnly(OldDetails.class, "currentBalance", "0000001940A").currentBalanceAmount())
                    .as("eleven bytes is not the declared twelve, so this fails on length before the sign "
                            + "or the body is ever examined")
                    .isNull();
        }

        @Test
        @DisplayName("returns no amount for an absent money member")
        void returnsNoAmountForAnAbsentMember() {
            assertThat(withMembers(OldDetails.class, Map.of()).currentBalanceAmount()).isNull();
            assertThat(withMembers(NewDetails.class, Map.of()).creditLimitAmount()).isNull();
        }

        @Test
        @DisplayName("reads the credit score both as text and as a number from the same bytes")
        void readsTheCreditScoreBothWays() {
            final OldDetails snapshot = withOnly(OldDetails.class, "ficoScore", "720");
            assertThat(snapshot.getFicoScore())
                    .as("1205-COMPARE-OLD-NEW compares the TEXT members at "
                            + "app/cbl/COACTUPC.cbl:1767-1768")
                    .isEqualTo("720");
            assertThat(snapshot.ficoScoreValue())
                    .as("9700-CHECK-CHANGE-IN-REC compares the NUMERIC member at "
                            + "app/cbl/COACTUPC.cbl:4186")
                    .isEqualTo(720);
            assertThat(serializedProperties(snapshot))
                    .as("one storage cell, two readings, one wire property")
                    .contains("ficoScore")
                    .doesNotContain("ficoScoreValue");
        }

        @ParameterizedTest(name = "credit score \"{0}\" has no numeric reading")
        @ValueSource(strings = {"", "72", "7200", "abc", "   ", "\u0000\u0000\u0000", "7 0", "+20"})
        @DisplayName("returns no credit score unless the three bytes are three decimal digits")
        void returnsNoCreditScoreUnlessThreeDigits(final String image) {
            assertThat(withOnly(OldDetails.class, "ficoScore", image).ficoScoreValue())
                    .as("the clause is unsigned PIC 9(03), so unlike money there is no overpunch")
                    .isNull();
        }

        @Test
        @DisplayName("stores the OLD telephone whole and derives its three components")
        void storesTheOldTelephoneWhole() {
            assertThat(fieldNames(OldDetails.class))
                    .as("MOVE CUST-PHONE-NUM-1 TO ACUP-OLD-CUST-PHONE-NUM-1 at "
                            + "app/cbl/COACTUPC.cbl:3876 assigns the whole and never a part")
                    .contains("phoneNumber1", "phoneNumber2")
                    .doesNotContain("phoneNumber1AreaCode", "phoneNumber1Prefix",
                            "phoneNumber1LineNumber");
            final OldDetails snapshot =
                    withOnly(OldDetails.class, "phoneNumber1", "(555)867-5309  ");
            assertThat(snapshot.phoneNumber1AreaCode())
                    .as("the REDEFINES at app/cbl/COACTUPC.cbl:723-731 places the components at "
                            + "COBOL offsets 2, 6 and 10, because the filler bytes are the "
                            + "parentheses and the hyphen")
                    .isEqualTo("555");
            assertThat(snapshot.phoneNumber1Prefix()).isEqualTo("867");
            assertThat(snapshot.phoneNumber1LineNumber()).isEqualTo("5309");
            assertThat(serializedProperties(snapshot))
                    .contains("phoneNumber1")
                    .doesNotContain("phoneNumber1AreaCode");
        }

        @Test
        @DisplayName("stores the NEW telephone by part and derives the fifteen-byte whole")
        void storesTheNewTelephoneByPart() {
            assertThat(fieldNames(NewDetails.class))
                    .as("1100-RECEIVE-MAP assigns only the parts, at "
                            + "app/cbl/COACTUPC.cbl:1359-1396, and never the whole")
                    .contains("phoneNumber1AreaCode", "phoneNumber1Prefix",
                            "phoneNumber1LineNumber", "phoneNumber2AreaCode", "phoneNumber2Prefix",
                            "phoneNumber2LineNumber")
                    .doesNotContain("phoneNumber1", "phoneNumber2");
            final Map<String, String> parts = new LinkedHashMap<>();
            parts.put("phoneNumber1AreaCode", "555");
            parts.put("phoneNumber1Prefix", "867");
            parts.put("phoneNumber1LineNumber", "5309");
            final NewDetails edited = withMembers(NewDetails.class, parts);
            assertThat(edited.phoneNumber1())
                    .as("INITIALIZE ACUP-NEW-DETAILS at app/cbl/COACTUPC.cbl:1047 leaves the filler "
                            + "positions as spaces and nothing assigns them afterwards; the "
                            + "receiving overlay even carries VALUE '(', ')' and '-' clauses that "
                            + "the author commented out, at :86, :91 and :96")
                    .isEqualTo(" 555 867 5309  ")
                    .hasSize(PHONE_WIDTH);
            assertThat(serializedProperties(edited))
                    .contains("phoneNumber1AreaCode")
                    .doesNotContain("phoneNumber1", "phoneNumber2");
        }

        @Test
        @DisplayName("pads an absent NEW telephone part to its declared width in the derived whole")
        void padsAnAbsentNewTelephonePart() {
            assertThat(withOnly(NewDetails.class, "phoneNumber1Prefix", "8").phoneNumber1())
                    .as("a MOVE into a fixed-width alphanumeric item space-pads on the right")
                    .isEqualTo("     8         ")
                    .hasSize(PHONE_WIDTH);
            assertThat(withMembers(NewDetails.class, Map.of()).phoneNumber2())
                    .isEqualTo(" ".repeat(PHONE_WIDTH));
        }

        @Test
        @DisplayName("refuses to assemble a NEW telephone whole from an over-wide part")
        void refusesToAssembleFromAnOverWidePart() {
            assertThat(refusalOf(() -> withOnly(NewDetails.class, "phoneNumber1AreaCode", "5555")
                    .phoneNumber1()))
                    .hasMessageContaining("NewDetails.phoneNumber1")
                    .hasMessageContaining("app/cbl/COACTUPC.cbl:811-819")
                    .hasMessageNotContaining("5555");
        }

        @Test
        @DisplayName("refuses to slice an OLD telephone longer than the fifteen-byte overlay")
        void rejectsAnOverWideOldTelephone() {
            assertThat(refusalOf(() -> withOnly(OldDetails.class, "phoneNumber1", "X".repeat(16))
                    .phoneNumber1AreaCode()))
                    .as("offsets 2, 6 and 10 cannot be applied to a value longer than the overlay")
                    .hasMessageContaining("OldDetails.phoneNumber1")
                    .hasMessageNotContaining("XXXX");
        }

        @Test
        @DisplayName("returns the empty string for an OLD telephone component the value never reaches")
        void returnsEmptyForAComponentTheValueNeverReaches() {
            final OldDetails snapshot = withOnly(OldDetails.class, "phoneNumber1", "(555");
            assertThat(snapshot.phoneNumber1AreaCode()).isEqualTo("555");
            assertThat(snapshot.phoneNumber1Prefix()).isEmpty();
            assertThat(snapshot.phoneNumber1LineNumber()).isEmpty();
            assertThat(withMembers(OldDetails.class, Map.of()).phoneNumber1AreaCode()).isNull();
        }

        @ParameterizedTest(name = "{0} exposes its compact date as 4/2/2 components")
        @ValueSource(strings = {"openDate", "expiraionDate", "reissueDate", "dateOfBirth"})
        @DisplayName("exposes each compact date as year, month and day without storing the parts")
        void exposesCompactDateComponents(final String memberName) {
            final OldDetails snapshot = withOnly(OldDetails.class, memberName, "19750412");
            assertThat(invokeView(snapshot, memberName + "Year")).isEqualTo("1975");
            assertThat(invokeView(snapshot, memberName + "Month")).isEqualTo("04");
            assertThat(invokeView(snapshot, memberName + "Day")).isEqualTo("12");
            assertThat(serializedProperties(snapshot))
                    .contains(memberName)
                    .doesNotContain(memberName + "Year", memberName + "Month", memberName + "Day");
        }

        @Test
        @DisplayName("returns no date component for an absent compact date")
        void returnsNoDateComponentForAnAbsentDate() {
            final OldDetails empty = withMembers(OldDetails.class, Map.of());
            assertThat(invokeView(empty, "dateOfBirthYear")).isNull();
            assertThat(invokeView(empty, "openDateMonth")).isNull();
            assertThat(invokeView(withOnly(OldDetails.class, "openDate", "1975"), "openDateMonth"))
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses a dash-separated snapshot date, which the compact offsets cannot slice")
        void rejectsADashSeparatedSnapshotDate() {
            assertThat(refusalOf(() -> withOnly(OldDetails.class, "dateOfBirth", "1975-04-12")
                    .dateOfBirthMonth()))
                    .as("the live record is PIC X(10) dash-separated at app/cpy/CVCUS01Y.cpy and "
                            + "the snapshot is PIC X(08) compact, which is exactly why "
                            + "9700-CHECK-CHANGE-IN-REC compares offsets 1/6/9 against 1/5/7 at "
                            + "app/cbl/COACTUPC.cbl:4174-4179")
                    .hasMessageContaining("OldDetails.dateOfBirth")
                    .hasMessageNotContaining("1975-04-12");
        }

        @ParameterizedTest(name = "{0} emits exactly its stored members")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("emits exactly the stored members and no derived view, on all three types")
        void emitsExactlyTheStoredMembers(final Class<?> type) {
            assertThat(serializedProperties(withMembers(type, Map.of())))
                    .containsExactlyInAnyOrderElementsOf(fieldNames(type));
        }

        @Test
        @DisplayName("round-trips, so a client can return the snapshot it was given")
        void roundTripsThroughJson() throws Exception {
            final AccountUpdateRequest original = replaceGroup(
                    withOnly(AccountUpdateRequest.class, "accountId", "00000000001"),
                    "oldDetails", withOnly(OldDetails.class, "currentBalance", "00000001940{"));
            final String json = STRICT_MAPPER.writeValueAsString(original);
            final AccountUpdateRequest restored =
                    STRICT_MAPPER.readValue(json, AccountUpdateRequest.class);
            assertThat(restored.getAccountId()).isEqualTo("00000000001");
            assertThat(restored.getOldDetails().getCurrentBalance()).isEqualTo("00000001940{");
            assertThat(restored.getOldDetails().currentBalanceAmount())
                    .isEqualByComparingTo(new BigDecimal("194.00"));
        }

        /**
         * Invokes a no-argument derived view by name.
         *
         * @param target   the snapshot group
         * @param viewName the view's method name
         * @return the view's value
         */
        private String invokeView(final Object target, final String viewName) {
            try {
                return (String) target.getClass().getDeclaredMethod(viewName).invoke(target);
            } catch (ReflectiveOperationException cause) {
                throw new AssertionError("cannot invoke view " + viewName, cause);
            }
        }
    }

    @Nested
    @DisplayName("6. Unknown properties: refused, not discarded, under either mapper")
    final class UnknownProperties {

        @ParameterizedTest(name = "{1} is refused under the {0} mapper")
        @MethodSource("unknownPropertyPayloads")
        @DisplayName("refuses an unrecognised property wherever it appears")
        void refusesAnUnrecognisedProperty(final String mode, final String description,
                final String json) {
            final ObjectMapper mapper = "strict".equals(mode) ? STRICT_MAPPER : LENIENT_MAPPER;
            assertThat(description).isNotBlank();
            assertThat(refusalOf(() -> mapper.readValue(json, AccountUpdateRequest.class)))
                    .as("the framework disables failure on unknown properties by default, so a "
                            + "type-level annotation would be inert and a discarded property would "
                            + "look like acceptance")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "{0} declares exactly one any-setter guard")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("declares the guard on all three types")
        void declaresTheGuardOnAllThreeTypes(final Class<?> type) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.getAnnotation(JsonAnySetter.class) != null)
                    .map(Method::getName)
                    .toList())
                    .withFailMessage("%s must refuse a property it does not declare",
                            type.getSimpleName())
                    .hasSize(1);
        }

        @Test
        @DisplayName("withholds the offending name and value from the refusal message")
        void withholdsTheOffendingNameAndValue() {
            final String json = "{\"oldDetails\":{\"forgedName\":\"123-45-6789\"}}";
            assertThat(refusalOf(() -> STRICT_MAPPER.readValue(json, AccountUpdateRequest.class)))
                    .hasMessageContaining("OldDetails accepts only the 29 properties")
                    .hasMessageNotContaining("forgedName")
                    .hasMessageNotContaining("123-45-6789");
        }

        @Test
        @DisplayName("states the declared property count of each type in its refusal")
        void statesTheDeclaredPropertyCount() {
            assertThat(refusalOf(() -> STRICT_MAPPER.readValue("{\"notAField\":\"x\"}",
                    AccountUpdateRequest.class)))
                    .hasMessageContaining("AccountUpdateRequest accepts only the "
                            + TOP_LEVEL_MEMBERS + " properties");
            assertThat(refusalOf(() -> STRICT_MAPPER.readValue(
                    "{\"newDetails\":{\"notAField\":\"x\"}}", AccountUpdateRequest.class)))
                    .hasMessageContaining("NewDetails accepts only the " + NEW_MEMBERS
                            + " properties");
        }

        @Test
        @DisplayName("accepts every declared property, so the guard is not over-broad")
        void acceptsEveryDeclaredProperty() throws Exception {
            for (final String member : fieldNames(AccountUpdateRequest.class)) {
                assertThat(STRICT_MAPPER.readValue("{\"" + member + "\":null}",
                        AccountUpdateRequest.class)).isNotNull();
            }
            final Map<String, Class<?>> groups = new LinkedHashMap<>();
            groups.put("oldDetails", OldDetails.class);
            groups.put("newDetails", NewDetails.class);
            for (final Map.Entry<String, Class<?>> group : groups.entrySet()) {
                for (final String member : fieldNames(group.getValue())) {
                    final String json =
                            "{\"" + group.getKey() + "\":{\"" + member + "\":null}}";
                    assertThat(STRICT_MAPPER.readValue(json, AccountUpdateRequest.class))
                            .isNotNull();
                }
            }
        }

        /**
         * Supplies one refusal case per unrecognised-property shape, under each mapper.
         *
         * @return the mapper mode, a description, and the payload that must be refused
         */
        static Stream<Arguments> unknownPropertyPayloads() {
            final Map<String, String> payloads = new LinkedHashMap<>();
            payloads.put("an invented top-level property", "{\"notAField\":\"x\"}");
            payloads.put("an invented oldDetails property",
                    "{\"oldDetails\":{\"notAField\":\"x\"}}");
            payloads.put("an invented newDetails property",
                    "{\"newDetails\":{\"notAField\":\"x\"}}");
            payloads.put("a money numeric view submitted as a member",
                    "{\"oldDetails\":{\"currentBalanceAmount\":194.00}}");
            payloads.put("a credit-score numeric view submitted as a member",
                    "{\"newDetails\":{\"ficoScoreValue\":720}}");
            payloads.put("an OLD telephone component submitted as a member",
                    "{\"oldDetails\":{\"phoneNumber1AreaCode\":\"555\"}}");
            payloads.put("a NEW telephone whole submitted as a member",
                    "{\"newDetails\":{\"phoneNumber1\":\"(555)867-5309\"}}");
            payloads.put("a compact-date component submitted as a member",
                    "{\"oldDetails\":{\"dateOfBirthYear\":\"1975\"}}");
            payloads.put("a NEW SSN part submitted on the OLD group",
                    "{\"oldDetails\":{\"ssnPart1\":\"123\"}}");
            payloads.put("an OLD SSN whole submitted on the NEW group",
                    "{\"newDetails\":{\"ssn\":\"123456789\"}}");
            payloads.put("the NEW range predicate submitted as a member",
                    "{\"newDetails\":{\"ficoScoreIsInValidRange\":true}}");
            final List<Arguments> cases = new ArrayList<>();
            for (final String mode : List.of("strict", "lenient")) {
                payloads.forEach((description, json) ->
                        cases.add(Arguments.of(mode, description, json)));
            }
            return cases.stream();
        }
    }

    @Nested
    @DisplayName("7. Accessor surface: no accessor throws for a width-legal payload")
    final class AccessorSurface {

        @ParameterizedTest(name = "every accessor of {0} succeeds when every member is absent")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("survives an entirely absent payload on every accessor")
        void survivesAnEntirelyAbsentPayload(final Class<?> type) {
            final Object payload = withMembers(type, Map.of());
            for (final Method accessor : zeroArgumentAccessors(type)) {
                assertThatAccessorSucceeds(payload, accessor);
            }
        }

        @ParameterizedTest(name = "every accessor of {0} succeeds at full declared width")
        @MethodSource("com.cardemo.unit.model.AccountUpdateRequestTest#payloadTypesOnly")
        @DisplayName("survives the widest legal payload on every accessor")
        void survivesTheWidestLegalPayload(final Class<?> type) {
            final Object payload = fullyPopulated(type);
            for (final Method accessor : zeroArgumentAccessors(type)) {
                assertThatAccessorSucceeds(payload, accessor);
            }
        }

        @Test
        @DisplayName("returns the value it was given from every top-level accessor")
        void returnsTheValueItWasGiven() {
            final AccountUpdateRequest payload = fullyPopulated(AccountUpdateRequest.class);
            for (final String member : fieldNames(AccountUpdateRequest.class)) {
                if (isSnapshotGroup(AccountUpdateRequest.class, member)) {
                    assertThat(readMember(payload, member)).isNull();
                    continue;
                }
                assertThat(readMember(payload, member))
                        .as("%s must be returned exactly as supplied, with no trim, pad or "
                                + "case-fold", member)
                        .isEqualTo("0".repeat(declaredWidth(AccountUpdateRequest.class, member)));
            }
        }

        @Test
        @DisplayName("reads every derived view of a fully populated snapshot without loss")
        void readsEveryDerivedViewOfAFullSnapshot() {
            final OldDetails snapshot = fullyPopulated(OldDetails.class);
            assertThat(snapshot.currentBalanceAmount())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(snapshot.ficoScoreValue()).isZero();
            assertThat(snapshot.dateOfBirthYear()).isEqualTo("0000");
            assertThat(snapshot.phoneNumber1AreaCode()).isEqualTo("000");
            final NewDetails edited = fullyPopulated(NewDetails.class);
            assertThat(edited.phoneNumber1()).isEqualTo(" 000 000 0000  ");
            assertThat(edited.ficoScoreIsInValidRange())
                    .as("a score of 000 is outside 300 through 850")
                    .isFalse();
        }

        /**
         * Asserts that one accessor returns rather than throwing.
         *
         * @param payload  the payload to read
         * @param accessor the accessor to invoke
         */
        private void assertThatAccessorSucceeds(final Object payload, final Method accessor) {
            try {
                accessor.invoke(payload);
            } catch (InvocationTargetException cause) {
                throw new AssertionError(payload.getClass().getSimpleName() + "."
                        + accessor.getName() + " must not throw for a width-legal payload",
                        cause.getCause());
            } catch (ReflectiveOperationException cause) {
                throw new AssertionError("cannot invoke " + accessor.getName(), cause);
            }
        }
    }
}
