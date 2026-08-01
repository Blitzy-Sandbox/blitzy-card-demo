/*
 * ******************************************************************
 * Program     : CommAreaApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the nine live COMMAREA fields that survive the
 *               move to a stateless REST surface, and in particular
 *               that all three numeric-PIC identifiers are carried as
 *               width-bounded strings rather than integers. A numeric
 *               type would strip the leading zeros the seeded eleven
 *               and nine byte images carry, which no compiler or test
 *               would otherwise catch. Also pins that the seven
 *               routing and screen-state fields are deliberately
 *               absent, and that the rendering omits every name and
 *               identifier.
 * Source      : app/cpy/COCOM01Y.cpy:20-45 (CDEMO-* fields, the 88
 *               levels ADMIN 'A' and USER 'U') + the seeded images in
 *               app/data/ASCII/acctdata.txt and custdata.txt
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

import com.cardemo.model.dto.CommArea;
import com.cardemo.model.enums.UserType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit contract for {@link CommArea}.
 *
 * <p>The centre of gravity here is the representation of the three identifier fields. {@code COCOM01Y}
 * declares {@code CDEMO-CUST-ID} as {@code PIC 9(09)}, {@code CDEMO-ACCT-ID} as {@code PIC 9(11)} and
 * {@code CDEMO-CARD-NUM} as {@code PIC 9(16)} - all three numeric pictures. A {@code DISPLAY} item with a
 * numeric picture is nevertheless a fixed-width byte field, and the seeded fixtures prove it: the first
 * account image is {@code 00000000001} and the first customer image is {@code 000000001}. Carrying any of
 * the three as an integer would silently discard those leading zeros, and because nothing in Java would
 * complain, the loss would only surface as a failed lookup much later. All three are therefore strings, and
 * these tests exist so that a later "simplification" back to a numeric type fails here rather than in
 * production.
 */
@DisplayName("CommArea - app/cpy/COCOM01Y.cpy:20-45, the nine live fields of the COMMAREA")
class CommAreaApiContractTest {

    /** Live fields carried forward from {@code COCOM01Y}; the routing and screen-state fields are not. */
    private static final int LIVE_FIELD_COUNT = 9;

    /** The nine component names in {@code COCOM01Y} declaration order. */
    private static final List<String> COMPONENT_ORDER = List.of("userId", "userType", "customerId",
            "customerFirstName", "customerMiddleName", "customerLastName", "accountId", "accountStatus",
            "cardNumber");

    /** Baseline values, positionally aligned with {@link #COMPONENT_ORDER}. */
    private static final List<String> BASELINE_VALUES = List.of("STDUSR01", "U", "000000001", "FNAMEAA6",
            "", "LNAME6", "00000000001", "Y", "0000000000000001");

    /**
     * The COMMAREA fields that have no counterpart on a stateless surface.
     *
     * <p>Four are routing fields, one is the pseudo-conversational enter-versus-re-enter flag and two are
     * the last screen and mapset. Routing is a URL and there is no retained screen, so none of the seven
     * may appear as a component.
     */
    private static final List<String> ROUTING_AND_SCREEN_STATE_FIELDS = List.of("fromTranId", "toTranId",
            "fromProgram", "toProgram", "pgmContext", "lastMap", "lastMapset");

    /**
     * The nine field contracts of {@code COCOM01Y}, in copybook declaration order.
     *
     * @return {@code componentName}, {@code cobolItem}, {@code sourceLine}, {@code width} tuples
     */
    static Stream<Arguments> fieldContracts() {
        return Stream.of(
                arguments("userId", "CDEMO-USER-ID", 25, CommArea.USER_ID_MAX_LENGTH),
                arguments("userType", "CDEMO-USER-TYPE", 26, CommArea.USER_TYPE_LENGTH),
                arguments("customerId", "CDEMO-CUST-ID", 33, CommArea.CUSTOMER_ID_LENGTH),
                arguments("customerFirstName", "CDEMO-CUST-FNAME", 34, CommArea.CUSTOMER_NAME_MAX_LENGTH),
                arguments("customerMiddleName", "CDEMO-CUST-MNAME", 35, CommArea.CUSTOMER_NAME_MAX_LENGTH),
                arguments("customerLastName", "CDEMO-CUST-LNAME", 36, CommArea.CUSTOMER_NAME_MAX_LENGTH),
                arguments("accountId", "CDEMO-ACCT-ID", 38, CommArea.ACCOUNT_ID_LENGTH),
                arguments("accountStatus", "CDEMO-ACCT-STATUS", 39, CommArea.ACCOUNT_STATUS_LENGTH),
                arguments("cardNumber", "CDEMO-CARD-NUM", 41, CommArea.CARD_NUMBER_MAX_LENGTH));
    }

    /**
     * Convenience wrapper so the contract table above reads as a table rather than as boilerplate.
     *
     * @param componentName the Java component name
     * @param cobolItem     the COBOL data item name
     * @param sourceLine    the declaring line in {@code app/cpy/COCOM01Y.cpy}
     * @param width         the declared PIC width
     * @return the four values as a parameter tuple
     */
    private static Arguments arguments(final String componentName,
            final String cobolItem, final int sourceLine, final int width) {
        return Arguments.of(componentName, cobolItem, sourceLine, width);
    }

    /**
     * A fully populated context drawn from the seeded fixtures.
     *
     * @return a valid context whose identifiers carry their real leading zeros
     */
    private static CommArea baseline() {
        return build(BASELINE_VALUES);
    }

    /**
     * Builds a context from nine positional values.
     *
     * @param values exactly nine values, aligned with {@link #COMPONENT_ORDER}
     * @return the context the values describe
     */
    private static CommArea build(final List<String> values) {
        if (values.size() != LIVE_FIELD_COUNT) {
            throw new AssertionError("expected " + LIVE_FIELD_COUNT + " values, got " + values.size());
        }

        return new CommArea(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4),
                values.get(5), values.get(6), values.get(7), values.get(8));
    }

    /**
     * Builds the baseline with exactly one component replaced.
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the baseline carrying {@code value} in {@code componentName}
     */
    private static CommArea withComponent(final String componentName, final String value) {
        final int index = COMPONENT_ORDER.indexOf(componentName);

        if (index < 0) {
            throw new AssertionError("CommArea declares no component " + componentName);
        }
        final List<String> values = new ArrayList<>(BASELINE_VALUES);
        values.set(index, value);

        return build(values);
    }

    /**
     * Reads an annotation from the field a record component generates.
     *
     * @param componentName  the record component whose generated field should be inspected
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the annotation, or {@code null} when the component does not carry it
     */
    private static <A extends java.lang.annotation.Annotation> A fieldAnnotation(
            final String componentName, final Class<A> annotationType) {
        try {
            final Field field = CommArea.class.getDeclaredField(componentName);
            return field.getAnnotation(annotationType);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError("CommArea declares no field for component " + componentName, cause);
        }
    }

    /**
     * Validates a context with a freshly built, immediately closed validator.
     *
     * @param context the context to validate
     * @return every violation raised, possibly empty
     */
    private static Set<ConstraintViolation<CommArea>> violationsOf(final CommArea context) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            final Validator validator = factory.getValidator();
            return validator.validate(context);
        }
    }

    @Nested
    @DisplayName("1. HIGH: every numeric-PIC identifier is a width-bounded String, never an integer")
    class IdentifierRepresentation {

        @Test
        @DisplayName("types all nine components as String, the three numeric-PIC ones included")
        void typesAllNineComponentsAsString() {
            assertThat(CommArea.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType())
                            .as("component %s must be a String: a PIC 9(n) DISPLAY item is an n-BYTE "
                                    + "zoned-decimal field, not an n-digit integer, so its leading zeros "
                                    + "are part of the value", component.getName())
                            .isEqualTo(String.class));
        }

        @ParameterizedTest(name = "{0} carries no integral type")
        @ValueSource(strings = {"customerId", "accountId", "cardNumber"})
        @DisplayName("no identifier is typed Long, Integer, BigInteger or a primitive")
        void noIdentifierIsTypedNumerically(final String componentName) {
            final Class<?> type = Arrays.stream(CommArea.class.getRecordComponents())
                    .filter(component -> componentName.equals(component.getName()))
                    .map(RecordComponent::getType)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no component " + componentName));

            assertThat(type)
                    .as("all three of CDEMO-CUST-ID, CDEMO-ACCT-ID and CDEMO-CARD-NUM carry numeric "
                            + "pictures, and the inconsistency of typing one of them as a String and the "
                            + "others as integers was itself the signal that the integers were wrong")
                    .isNotIn(Long.class, Integer.class, BigInteger.class, long.class, int.class);
        }

        @Test
        @DisplayName("bounds each identifier by length rather than by digit count")
        void boundsEachIdentifierByLength() {
            assertThat(fieldAnnotation("customerId", Size.class))
                    .as("a Size bound constrains the byte width the source declares; a Digits bound would "
                            + "constrain a numeric magnitude, which is not what PIC 9(09) DISPLAY fixes")
                    .isNotNull();
            assertThat(fieldAnnotation("accountId", Size.class)).isNotNull();
            assertThat(fieldAnnotation("cardNumber", Size.class)).isNotNull();

            assertThat(Arrays.stream(CommArea.class.getDeclaredFields())
                    .filter(field -> field.getAnnotation(Digits.class) != null)
                    .toList())
                    .as("no component may carry a Digits constraint; the three identifiers are "
                            + "identifiers, never operands - nothing adds to them or compares them by "
                            + "magnitude - so a numeric constraint would assert the wrong property")
                    .isEmpty();
        }

        @Test
        @DisplayName("preserves the seeded eleven-byte account image, leading zeros intact")
        void preservesTheSeededAccountImage() {
            assertThat(baseline().accountId())
                    .as("app/data/ASCII/acctdata.txt begins with the eleven bytes 00000000001. A numeric "
                            + "type would render that as 1 and could not reconstruct the original without "
                            + "knowing the width from somewhere else")
                    .isEqualTo("00000000001")
                    .hasSize(CommArea.ACCOUNT_ID_LENGTH);
        }

        @Test
        @DisplayName("preserves the seeded nine-byte customer image, leading zeros intact")
        void preservesTheSeededCustomerImage() {
            assertThat(baseline().customerId())
                    .as("app/data/ASCII/custdata.txt begins with the nine bytes 000000001")
                    .isEqualTo("000000001")
                    .hasSize(CommArea.CUSTOMER_ID_LENGTH);
        }

        @Test
        @DisplayName("preserves a sixteen-byte card number whose value is entirely leading zeros but one")
        void preservesALeadingZeroCardNumber() {
            assertThat(baseline().cardNumber())
                    .as("CARD-NUM is PIC X(16) at app/cpy/CVACT02Y.cpy while COCOM01Y spells the same "
                            + "field PIC 9(16); the divergence is documented and the wider, safer of the "
                            + "two representations is taken")
                    .isEqualTo("0000000000000001")
                    .hasSize(CommArea.CARD_NUMBER_MAX_LENGTH);
        }

        @Test
        @DisplayName("survives a JSON round trip with every leading zero still present")
        void survivesAJsonRoundTripWithLeadingZerosIntact() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final CommArea bound = mapper.readValue(mapper.writeValueAsString(baseline()), CommArea.class);

            assertThat(bound)
                    .as("the JSON boundary is where a numeric type would have done its damage: an integer "
                            + "component serialises as an unquoted number and comes back without its "
                            + "padding, and the round trip would silently stop being an identity")
                    .isEqualTo(baseline());
            assertThat(bound.accountId()).isEqualTo("00000000001");
            assertThat(bound.customerId()).isEqualTo("000000001");
        }

        @Test
        @DisplayName("emits every identifier as a quoted string, not as a JSON number")
        void emitsEveryIdentifierAsAQuotedString() throws Exception {
            final String json = new ObjectMapper().writeValueAsString(baseline());

            assertThat(json)
                    .as("a quoted value is what preserves the padding across the wire; an unquoted number "
                            + "would be normalised by any conforming JSON reader")
                    .contains("\"accountId\":\"00000000001\"")
                    .contains("\"customerId\":\"000000001\"")
                    .contains("\"cardNumber\":\"0000000000000001\"");
            assertThat(json)
                    .as("no identifier may appear unquoted")
                    .doesNotContain("\"accountId\":1")
                    .doesNotContain("\"customerId\":1");
        }
    }

    @Nested
    @DisplayName("2. Field contract - nine live fields at the widths COCOM01Y declares")
    class FieldContract {

        @Test
        @DisplayName("declares exactly the nine live fields")
        void declaresExactlyNineComponents() {
            assertThat(CommArea.class.getRecordComponents())
                    .as("COCOM01Y declares sixteen items; seven are routing or screen state and have no "
                            + "counterpart on a stateless surface, leaving nine that carry data forward")
                    .hasSize(LIVE_FIELD_COUNT);
        }

        @Test
        @DisplayName("orders its components exactly as the copybook declares them")
        void ordersComponentsAsTheCopybookDeclaresThem() {
            assertThat(Arrays.stream(CommArea.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("the canonical constructor is positional and all nine components are String, so a "
                            + "reordering would compile and transpose, for instance, the first name with "
                            + "the middle name without any diagnostic")
                    .containsExactlyElementsOf(COMPONENT_ORDER);
        }

        @ParameterizedTest(name = "{1} at COCOM01Y.cpy:{2} -> {0}, width {3}")
        @MethodSource("com.cardemo.unit.model.CommAreaApiContractTest#fieldContracts")
        @DisplayName("bounds every component at its declared width and no tighter")
        void boundsEveryComponentAtItsDeclaredWidth(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            final Size size = fieldAnnotation(componentName, Size.class);

            assertThat(size)
                    .as("%s is declared at app/cpy/COCOM01Y.cpy:%d", cobolItem, sourceLine)
                    .isNotNull();
            assertThat(size.max())
                    .as("the bound must equal the declared width of %s", cobolItem)
                    .isEqualTo(width);
            assertThat(size.min()).isZero();
        }

        @ParameterizedTest(name = "{1}: width {3} accepted, {3} + 1 refused")
        @MethodSource("com.cardemo.unit.model.CommAreaApiContractTest#fieldContracts")
        @DisplayName("accepts a value at exactly the declared width and refuses one character more")
        void acceptsExactWidthAndRefusesOneMore(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            assertThat(violationsOf(withComponent(componentName, "x".repeat(width))))
                    .as("%d characters is the largest legal value for %s at line %d", width, cobolItem,
                            sourceLine)
                    .isEmpty();

            final Set<ConstraintViolation<CommArea>> violations =
                    violationsOf(withComponent(componentName, "x".repeat(width + 1)));

            assertThat(violations)
                    .as("only %s exceeds its bound, so exactly one violation is expected", componentName)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo(componentName);
        }

        @Test
        @DisplayName("treats null and blank as valid, because an unpopulated context is a real state")
        void treatsNullAndBlankAsValid() {
            assertThat(violationsOf(build(Collections.nCopies(LIVE_FIELD_COUNT, null))))
                    .as("a context is populated progressively as the user navigates, so at sign-on only "
                            + "the identity fields are known and the rest are genuinely absent")
                    .isEmpty();
            assertThat(violationsOf(build(Collections.nCopies(LIVE_FIELD_COUNT, ""))))
                    .as("a blank name is what an unpopulated fixed-width field looks like once it has "
                            + "been sent to the terminal, and must be distinguishable from absence")
                    .isEmpty();
        }

        @Test
        @DisplayName("keeps a blank middle name distinct from an absent one")
        void keepsBlankDistinctFromAbsent() {
            assertThat(withComponent("customerMiddleName", "").customerMiddleName())
                    .as("the middle name is blank in the baseline rather than null, and the two must not "
                            + "be collapsed: one says the field was sent empty, the other that it was "
                            + "never populated at all")
                    .isNotNull()
                    .isEqualTo("");
            assertThat(withComponent("customerMiddleName", null).customerMiddleName()).isNull();
        }
    }

    @Nested
    @DisplayName("3. Deliberate omissions - the seven routing and screen-state fields")
    class DeliberateOmissions {

        @Test
        @DisplayName("declares none of the seven routing or screen-state fields")
        void declaresNoneOfTheSevenOmittedFields() {
            final List<String> declared = Arrays.stream(CommArea.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(declared)
                    .as("routing is a URL on this surface, so the from and to transaction and program "
                            + "fields have no counterpart; the enter-versus-re-enter flag collapses into "
                            + "stateless request handling; and no screen state is retained, so the last "
                            + "map and mapset have nowhere to live")
                    .doesNotContainAnyElementsOf(ROUTING_AND_SCREEN_STATE_FIELDS);
        }

        @Test
        @DisplayName("does not reintroduce screen state under a renamed component")
        void doesNotReintroduceScreenStateUnderARenamedComponent() {
            final List<String> suspicious = Arrays.stream(CommArea.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("map")
                            || name.toLowerCase(Locale.ROOT).contains("tranid")
                            || name.toLowerCase(Locale.ROOT).contains("context"))
                    .toList();

            assertThat(suspicious)
                    .as("server-side session state is forbidden on this surface; reintroducing it under "
                            + "another name would be the same violation with a different spelling")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("4. User type resolution - the 88 levels ADMIN 'A' and USER 'U'")
    class UserTypeResolution {

        @ParameterizedTest(name = "user type code {0} resolves to {1}")
        @CsvSource({"A, ADMIN", "U, USER"})
        @DisplayName("resolves each declared code to its enum constant")
        void resolvesEachDeclaredCode(final String code, final String expected) {
            assertThat(withComponent("userType", code).resolvedUserType())
                    .as("app/cpy/COCOM01Y.cpy:27-28 declares exactly two 88 levels on CDEMO-USER-TYPE, "
                            + "and the authorisation model is built on them")
                    .isEqualTo(UserType.valueOf(expected));
        }

        @ParameterizedTest(name = "undeclared code {0} resolves to null rather than throwing")
        @ValueSource(strings = {"a", "u", "X", "", " ", "AU"})
        @DisplayName("resolves an undeclared code to null instead of throwing")
        void resolvesAnUndeclaredCodeToNull(final String code) {
            assertThat(withComponent("userType", code).resolvedUserType())
                    .as("resolution is a read of caller-influenced data, so it must report 'not one of the "
                            + "two' rather than throw; the authorisation layer then denies rather than "
                            + "erroring. Note that the lower-case forms resolve to null too - the source "
                            + "compares the byte, and it does not case-fold")
                    .isNull();
        }

        @Test
        @DisplayName("resolves a null code to null rather than throwing")
        void resolvesANullCodeToNull() {
            assertThat(withComponent("userType", null).resolvedUserType())
                    .as("an unpopulated context must be safe to interrogate")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("5. The diagnostic rendering omits every name and identifier")
    class DiagnosticRendering {

        @Test
        @DisplayName("overrides toString rather than inheriting the generated one")
        void overridesToStringRatherThanInheritingIt() {
            assertThat(Arrays.stream(CommArea.class.getDeclaredMethods())
                    .map(method -> method.getName())
                    .filter("toString"::equals)
                    .toList())
                    .as("a record's generated toString emits every component, which here means three "
                            + "customer names, a customer identifier, an account identifier and a card "
                            + "number - six disclosures in one line")
                    .containsExactly("toString");
        }

        @Test
        @DisplayName("emits the user identity and account status only")
        void emitsTheUserIdentityAndAccountStatusOnly() {
            assertThat(baseline().toString())
                    .as("the signed-on user, their role and the account status are what an operator needs "
                            + "in order to reason about an authorisation decision, and none of the three "
                            + "is customer data")
                    .isEqualTo("CommArea[userId=STDUSR01, userType=U, accountStatus=Y]");
        }

        @Test
        @DisplayName("emits no customer name, no identifier and no card number")
        void emitsNoCustomerNameNoIdentifierAndNoCardNumber() {
            final String rendered = baseline().toString();

            assertThat(rendered)
                    .doesNotContain("FNAMEAA6")
                    .doesNotContain("LNAME6")
                    .doesNotContain("000000001")
                    .doesNotContain("00000000001")
                    .doesNotContain("0000000000000001");
        }

        @Test
        @DisplayName("discloses nothing when interpolated into a message")
        void disclosesNothingWhenInterpolated() {
            assertThat("authorisation denied for " + baseline())
                    .as("implicit toString through concatenation is how a generated rendering reaches a "
                            + "log without anyone deciding that it should")
                    .doesNotContain("FNAMEAA6")
                    .doesNotContain("0000000000000001");
        }

        @Test
        @DisplayName("keeps every value readable through its accessor, so nothing was lost")
        void keepsEveryValueReadableThroughItsAccessor() {
            final CommArea context = baseline();

            assertThat(context.customerFirstName()).isEqualTo("FNAMEAA6");
            assertThat(context.customerLastName()).isEqualTo("LNAME6");
            assertThat(context.cardNumber()).isEqualTo("0000000000000001");
        }

        @Test
        @DisplayName("equality still spans every component, so redaction did not weaken identity")
        void equalityStillSpansEveryComponent() {
            assertThat(baseline()).isEqualTo(baseline()).hasSameHashCodeAs(baseline());
            assertThat(withComponent("cardNumber", "0000000000000001"))
                    .as("two contexts differing only in a component the rendering omits must still be "
                            + "unequal")
                    .isNotEqualTo(withComponent("cardNumber", "0000000000000002"));
        }
    }

    @Nested
    @DisplayName("6. Serialized surface - exactly the nine live fields")
    class SerializedSurface {

        @Test
        @DisplayName("emits exactly the nine live fields and nothing invented")
        void emitsExactlyTheNineLiveFields() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final Map<String, Object> emitted = mapper.readValue(
                    mapper.writeValueAsString(baseline()),
                    new TypeReference<LinkedHashMap<String, Object>>() { });

            assertThat(emitted.keySet())
                    .as("resolvedUserType is a derived reading rather than a field, and its no-get naming "
                            + "is what keeps it off the wire; a tenth key here would mean it had leaked "
                            + "into the contract")
                    .containsExactlyInAnyOrderElementsOf(COMPONENT_ORDER);
        }

        @Test
        @DisplayName("does not publish the resolved user type as a wire field")
        void doesNotPublishTheResolvedUserTypeAsAWireField() throws Exception {
            assertThat(new ObjectMapper().writeValueAsString(baseline()))
                    .as("publishing it would offer a second, differently spelled representation of the "
                            + "same byte the userType component already carries")
                    .doesNotContain("resolvedUserType");
            assertThat(new ObjectMapper().writeValueAsString(withComponent("userType", "A")))
                    .as("nor may the enum constant name appear anywhere; the wire carries the single "
                            + "byte the copybook declares, not a Java identifier")
                    .doesNotContain("ADMIN");
        }
    }
}
