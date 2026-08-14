/*
 * ******************************************************************
 * Program     : BillPaymentRequestApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the ten field contract of the bill payment
 *               payload and, in particular, the two containment
 *               guarantees that a generated record does not provide
 *               on its own: that the diagnostic rendering emits
 *               neither the account identifier nor the balance, and
 *               that an unrecognised JSON property is refused rather
 *               than silently discarded. Also pins the absence of a
 *               payment amount field, because the source always pays
 *               the full balance and never a partial figure.
 * Source      : app/cpy-bms/COBIL00.CPY (10 input fields, group
 *               COBIL0AI) + app/cbl/COBIL00C.cbl:193,:198,:224,:234
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

import com.cardemo.model.dto.BillPaymentRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
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

/**
 * Unit contract for {@link BillPaymentRequest}.
 *
 * <p>Two of these assertions exist because a Java record's generated members are actively wrong for this
 * payload rather than merely insufficient. The generated {@code toString()} emits every component, and two
 * of these components are an account identifier and a monetary balance; and Jackson's default posture is
 * to discard a JSON property it does not recognise, which on this payload would let a misspelling pass as
 * an absent confirmation. Both are therefore pinned here, so that a later edit removing the override or
 * the guard fails rather than quietly reopening the hole.
 */
@DisplayName("BillPaymentRequest - app/cpy-bms/COBIL00.CPY group COBIL0AI + app/cbl/COBIL00C.cbl")
class BillPaymentRequestApiContractTest {

    /** Input data items declared by group {@code COBIL0AI}. */
    private static final int PAYMENT_MAP_INPUT_FIELD_COUNT = 10;

    /** A synthetic account identifier at the declared width, deliberately not one of the seeded rows. */
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000099";

    /** A synthetic balance rendering, wide enough to prove the width and clearly not a real figure. */
    private static final String SYNTHETIC_BALANCE = "+00001234.56";

    /**
     * The ten field contracts of {@code COBIL0AI}, in copybook declaration order.
     *
     * @return the ten contracts as {@code componentName}, {@code cobolItem}, {@code sourceLine},
     *         {@code width} tuples
     */
    static Stream<Arguments> fieldContracts() {
        return Stream.of(
                Arguments.of("transactionName", "TRNNAMEI", 24, 4),
                Arguments.of("title01", "TITLE01I", 30, 40),
                Arguments.of("currentDate", "CURDATEI", 36, 8),
                Arguments.of("programName", "PGMNAMEI", 42, 8),
                Arguments.of("title02", "TITLE02I", 48, 40),
                Arguments.of("currentTime", "CURTIMEI", 54, 8),
                Arguments.of("accountId", "ACTIDINI", 60, 11),
                Arguments.of("currentBalance", "CURBALI", 66, 14),
                Arguments.of("confirmation", "CONFIRMI", 72, 1),
                Arguments.of("errorMessage", "ERRMSGI", 78, 78));
    }

    /**
     * A fully populated, entirely width-legal request.
     *
     * @return a valid request built only from this class's deterministic fixtures
     */
    private static BillPaymentRequest baseline() {
        return new BillPaymentRequest("CB00", "CardDemo", "07/24/26", "COBIL00C", "Bill Payment",
                "14:30:00", SYNTHETIC_ACCOUNT_ID, SYNTHETIC_BALANCE, "Y", "");
    }

    /**
     * Builds the baseline with exactly one component replaced, so that a width assertion isolates a
     * single field and any violation it raises can only have come from that field.
     *
     * <p>The replacement is positional rather than reflective: the index is resolved from the record's
     * own component order, so if a component were ever reordered this helper would follow the
     * reordering rather than silently writing the value into the wrong field.</p>
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the baseline carrying {@code value} in {@code componentName} and its fixture elsewhere
     */
    private static BillPaymentRequest withComponent(final String componentName, final String value) {
        final String[] values = {"CB00", "CardDemo", "07/24/26", "COBIL00C", "Bill Payment",
                "14:30:00", SYNTHETIC_ACCOUNT_ID, SYNTHETIC_BALANCE, "Y", ""};
        final List<String> names = Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        final int index = names.indexOf(componentName);

        if (index < 0) {
            throw new AssertionError("BillPaymentRequest declares no component " + componentName);
        }
        values[index] = value;

        return new BillPaymentRequest(values[0], values[1], values[2], values[3], values[4],
                values[5], values[6], values[7], values[8], values[9]);
    }

    /**
     * Reads an annotation from the field a record component generates, which is where {@link Size}
     * actually lands: {@link Size} does not list {@code RECORD_COMPONENT} among its targets, so it is not
     * directly present on the component itself.
     *
     * @param componentName  the record component whose generated field should be inspected
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the annotation, or {@code null} when the component does not carry it
     */
    private static <A extends java.lang.annotation.Annotation> A fieldAnnotation(
            final String componentName, final Class<A> annotationType) {
        try {
            final Field field = BillPaymentRequest.class.getDeclaredField(componentName);
            return field.getAnnotation(annotationType);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError(
                    "BillPaymentRequest declares no field for component " + componentName, cause);
        }
    }

    /**
     * Validates a request with a freshly built, immediately closed validator.
     *
     * @param request the request to validate
     * @return every violation raised, possibly empty
     */
    private static Set<ConstraintViolation<BillPaymentRequest>> violationsOf(
            final BillPaymentRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            final Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /**
     * Runs an action expected to be refused and returns the {@link IllegalArgumentException} that caused
     * the refusal, or {@code null} if none appears in the cause chain.
     *
     * <p>The chain is walked rather than asserted on directly because Jackson wraps an exception thrown
     * from an any-setter, and how deeply it nests it is an implementation detail of the databind version.
     *
     * @param action the action expected to be refused
     * @return the refusal, or {@code null} if the action was not refused for that reason
     */
    private static IllegalArgumentException refusalOf(final ThrowingAction action) {
        try {
            action.run();
            return null;
        } catch (final Throwable thrown) {
            Throwable cursor = thrown;
            for (int depth = 0; cursor != null && depth < 16; depth++) {
                if (cursor instanceof IllegalArgumentException refusal) {
                    return refusal;
                }
                cursor = cursor.getCause();
            }
            return null;
        }
    }

    /** An action that may throw any exception, so that {@link #refusalOf} can invoke it. */
    @FunctionalInterface
    private interface ThrowingAction {

        /**
         * Runs the action.
         *
         * @throws Exception if the action fails, which is the case under test
         */
        void run() throws Exception;
    }

    @Nested
    @DisplayName("1. Field contract - ten input fields of COBIL0AI, every width byte exact")
    class FieldContract {

        @Test
        @DisplayName("declares exactly the ten input fields the map declares")
        void declaresExactlyTenComponents() {
            assertThat(BillPaymentRequest.class.getRecordComponents())
                    .as("group COBIL0AI declares ten 02-level PIC items; a component more would invent a "
                            + "field the screen never had, and one fewer would drop one it did")
                    .hasSize(PAYMENT_MAP_INPUT_FIELD_COUNT);
        }

        @Test
        @DisplayName("orders its components exactly as the copybook declares them")
        void ordersComponentsAsTheCopybookDeclaresThem() {
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("a record's canonical constructor is positional, so reordering these would "
                            + "silently change the meaning of every existing call site while still "
                            + "compiling - and all ten are String, so nothing would catch it")
                    .containsExactly("transactionName", "title01", "currentDate", "programName",
                            "title02", "currentTime", "accountId", "currentBalance", "confirmation",
                            "errorMessage");
        }

        @Test
        @DisplayName("types every component as String, because every input item is PIC X")
        void typesEveryComponentAsString() {
            assertThat(BillPaymentRequest.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType())
                            .as("component %s carries a PIC X item and must stay a String",
                                    component.getName())
                            .isEqualTo(String.class));
        }

        @ParameterizedTest(name = "{1} PIC X({3}) at COBIL00.CPY:{2} -> {0}")
        @MethodSource("com.cardemo.unit.model.BillPaymentRequestApiContractTest#fieldContracts")
        @DisplayName("bounds every component at its declared width and no tighter")
        void boundsEveryComponentAtItsDeclaredWidth(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            final Size size = fieldAnnotation(componentName, Size.class);

            assertThat(size)
                    .as("%s is declared at app/cpy-bms/COBIL00.CPY:%d and this payload is a trust "
                            + "boundary, so %s must carry a Size bound", cobolItem, sourceLine,
                            componentName)
                    .isNotNull();
            assertThat(size.max())
                    .as("the bound must equal the declared PIC X(%d) width of %s - neither truncated "
                            + "nor widened", width, cobolItem)
                    .isEqualTo(width);
            assertThat(size.min())
                    .as("the source imposes no minimum length on %s, so neither may this type", cobolItem)
                    .isZero();
        }

        @ParameterizedTest(name = "{1} X({3}): {3} accepted, {3} + 1 refused")
        @MethodSource("com.cardemo.unit.model.BillPaymentRequestApiContractTest#fieldContracts")
        @DisplayName("accepts a value at exactly the declared width and refuses one character more")
        void acceptsExactWidthAndRefusesOneMore(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            assertThat(violationsOf(withComponent(componentName, "x".repeat(width))))
                    .as("%d characters is the largest legal value for %s", width, cobolItem)
                    .isEmpty();

            final Set<ConstraintViolation<BillPaymentRequest>> violations =
                    violationsOf(withComponent(componentName, "x".repeat(width + 1)));

            assertThat(violations)
                    .as("only %s exceeds its bound, so exactly one violation is expected; a second "
                            + "would mean the baseline itself is invalid", componentName)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .as("the violation must name the offending field so a caller can act on it")
                    .isEqualTo(componentName);
        }

        @Test
        @DisplayName("treats null and blank as valid, because absence and blankness are source states")
        void treatsNullAndBlankAsValid() {
            assertThat(violationsOf(
                    new BillPaymentRequest(null, null, null, null, null, null, null, null, null, null)))
                    .as("a Size bound treats null as valid, which is correct here: the source reports a "
                            + "missing account identifier through its own message, not as a format error")
                    .isEmpty();
            assertThat(violationsOf(
                    new BillPaymentRequest("", "", "", "", "", "", "", "", "", "")))
                    .as("app/cbl/COBIL00C.cbl:199 tolerates SPACES and LOW-VALUES, so blankness is a "
                            + "state the service interprets rather than a bound breach")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares no payment amount field, because the source always pays the full balance")
        void declaresNoPaymentAmountField() {
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).contains("amount")
                            || name.toLowerCase(Locale.ROOT).contains("payment"))
                    .toList())
                    .as("app/cbl/COBIL00C.cbl:224 moves the ENTIRE current balance into the transaction "
                            + "amount and :234 drives the balance to zero, so the payment is never "
                            + "partial. A payment-amount field would invent a capability the source does "
                            + "not have, and group COBIL0AI declares no such item")
                    .isEmpty();
        }

        @Test
        @DisplayName("carries the balance as a display string, not as a numeric type")
        void carriesTheBalanceAsADisplayString() {
            assertThat(BillPaymentRequest.class.getRecordComponents()[7].getType())
                    .as("CURBALI is PIC X(14) at app/cpy-bms/COBIL00.CPY:66 - a rendering the program "
                            + "wrote, not an operand. A numeric type here would have to re-derive the "
                            + "edited mask on the way out and would lose the sign position")
                    .isEqualTo(String.class);
            assertThat(Arrays.stream(BillPaymentRequest.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .filter(name -> name.contains("Float") || name.contains("Double")
                            || "float".equals(name) || "double".equals(name))
                    .toList())
                    .as("no floating-point type may appear in any financial field, which the security "
                            + "gate asserts by inspection")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("2. HIGH: the diagnostic rendering emits no account identifier and no balance")
    class DiagnosticRendering {

        @Test
        @DisplayName("overrides toString rather than inheriting the generated one")
        void overridesToStringRatherThanInheritingIt() {
            assertThat(Arrays.stream(BillPaymentRequest.class.getDeclaredMethods())
                    .map(method -> method.getName())
                    .filter("toString"::equals)
                    .toList())
                    .as("a record's generated toString emits EVERY component, which on this payload "
                            + "means the account identifier and the balance. The override is mandatory, "
                            + "not cosmetic, so its presence is pinned here")
                    .containsExactly("toString");
        }

        @Test
        @DisplayName("emits neither the account identifier nor the balance, in full or in part")
        void emitsNeitherTheAccountIdentifierNorTheBalance() {
            final String rendered = baseline().toString();

            assertThat(rendered)
                    .as("the account identifier selects a customer's account and the balance is "
                            + "financial data; neither may reach a log line")
                    .doesNotContain(SYNTHETIC_ACCOUNT_ID)
                    .doesNotContain(SYNTHETIC_BALANCE);
            assertThat(rendered)
                    .as("nothing may be emitted in their place either - not a masked form, not a "
                            + "truncation to a last four, and not a length, since each still discloses "
                            + "something about a value that should not appear at all")
                    .doesNotContain("1234")
                    .doesNotContain("0099");
        }

        @Test
        @DisplayName("emits a fixed structural form, withholding the presentation header as well")
        void emitsAFixedStructuralFormAndNoContent() {
            // The transaction name and the program name are constructor arguments, so they too are
            // caller supplied; rendering either verbatim admitted a line break into a log line. They
            // are withheld with the rest of the presentation header, and every remaining member is
            // described by shape alone rather than by content.
            assertThat(baseline().toString())
                    .as("the whole rendering is pinned, so a future change that reintroduced any "
                            + "value would fail here rather than reach a log unnoticed")
                    .isEqualTo("BillPaymentRequest[accountId=11 chars, currentBalance=12 chars, "
                            + "confirmation=0x59, errorMessage=empty, "
                            + "header=<6 presentation members omitted>]");
        }

        @Test
        @DisplayName("discloses nothing when interpolated into a message, which is how a log leaks")
        void disclosesNothingWhenInterpolated() {
            assertThat("payment failed for " + baseline())
                    .as("string concatenation invokes toString implicitly, which is exactly the path by "
                            + "which a generated rendering reaches a log without anyone intending it")
                    .doesNotContain(SYNTHETIC_ACCOUNT_ID)
                    .doesNotContain(SYNTHETIC_BALANCE);
        }

        @Test
        @DisplayName("keeps every value readable through its accessor, so nothing was lost")
        void keepsEveryValueReadableThroughItsAccessor() {
            final BillPaymentRequest request = baseline();

            assertThat(request.accountId())
                    .as("redacting the rendering must not redact the data: the service still needs the "
                            + "account identifier it was sent")
                    .isEqualTo(SYNTHETIC_ACCOUNT_ID);
            assertThat(request.currentBalance()).isEqualTo(SYNTHETIC_BALANCE);
            assertThat(request.confirmation()).isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("3. MEDIUM: an unrecognised JSON property is refused, not silently discarded")
    class UnrecognisedProperties {

        @Test
        @DisplayName("refuses an unrecognised property under a lenient mapper as well as a strict one")
        void refusesAnUnrecognisedPropertyUnderEitherMapperPosture() {
            final String body = "{\"accountId\":\"" + SYNTHETIC_ACCOUNT_ID + "\",\"amount\":\"1.00\"}";

            final ObjectMapper lenient = new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            final ObjectMapper strict = new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            assertThat(refusalOf(() -> lenient.readValue(body, BillPaymentRequest.class)))
                    .as("this repository publishes no application*.yml, so the framework default of "
                            + "ignoring unknown properties is the posture that actually ships. The guard "
                            + "is declared on the type precisely so it holds under a lenient mapper")
                    .isNotNull();
            assertThat(refusalOf(() -> strict.readValue(body, BillPaymentRequest.class)))
                    .as("a strict mapper must refuse it too")
                    .isNotNull();
        }

        @Test
        @DisplayName("states the field count and the copybook, and echoes neither name nor value")
        void statesTheContractAndEchoesNeitherNameNorValue() {
            final String offendingName = "paymentAmount";
            final String offendingValue = "0.01";
            final String body = "{\"accountId\":\"" + SYNTHETIC_ACCOUNT_ID + "\",\""
                    + offendingName + "\":\"" + offendingValue + "\"}";

            final IllegalArgumentException refusal =
                    refusalOf(() -> new ObjectMapper().readValue(body, BillPaymentRequest.class));

            assertThat(refusal).isNotNull();
            assertThat(refusal.getMessage())
                    .as("the message must state the declared field count and cite the copybook so a "
                            + "caller can find the contract it breached")
                    .contains(String.valueOf(PAYMENT_MAP_INPUT_FIELD_COUNT))
                    .contains("app/cpy-bms/COBIL00.CPY");
            assertThat(refusal.getMessage())
                    .as("echoing either would let a caller place chosen text into the logs of a failed "
                            + "payment request")
                    .doesNotContain(offendingName)
                    .doesNotContain(offendingValue);
        }

        @ParameterizedTest(name = "a misspelling of {0} is refused rather than dropped")
        @CsvSource({"confirmation, confirmatoin", "accountId, accountID", "currentBalance, balance"})
        @DisplayName("a misspelled property is refused, so a typo cannot read as an absent field")
        void aMisspelledPropertyIsRefused(final String declared, final String misspelling) {
            final String body = "{\"" + misspelling + "\":\"Y\"}";

            assertThat(refusalOf(() -> new ObjectMapper().readValue(body, BillPaymentRequest.class)))
                    .as("silently dropping '%s' would leave '%s' absent, and an absent confirmation "
                            + "reads to app/cbl/COBIL00C.cbl:173 as 'not yet confirmed' rather than as "
                            + "the malformed request it is", misspelling, declared)
                    .isNotNull();
        }

        @Test
        @DisplayName("accepts a body naming only declared properties, so it refuses nothing it should "
                + "admit")
        void acceptsABodyNamingOnlyDeclaredProperties() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final BillPaymentRequest bound =
                    mapper.readValue(mapper.writeValueAsString(baseline()), BillPaymentRequest.class);

            assertThat(bound)
                    .as("a round trip of this type's own output names only declared properties and must "
                            + "pass the guard untouched")
                    .isEqualTo(baseline());
        }
    }

    @Nested
    @DisplayName("4. Serialized surface - exactly the ten map fields, nothing invented")
    class SerializedSurface {

        @Test
        @DisplayName("emits exactly the ten map fields and no structural helper")
        void emitsExactlyTheTenMapFields() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final Map<String, Object> emitted = mapper.readValue(
                    mapper.writeValueAsString(baseline()),
                    new TypeReference<LinkedHashMap<String, Object>>() { });

            assertThat(emitted.keySet())
                    .as("the wire contract is the map's ten fields; an extra key would publish a field "
                            + "the screen never had")
                    .containsExactlyInAnyOrderElementsOf(List.of("transactionName", "title01",
                            "currentDate", "programName", "title02", "currentTime", "accountId",
                            "currentBalance", "confirmation", "errorMessage"));
        }

        @Test
        @DisplayName("round-trips every component verbatim, padding included")
        void roundTripsEveryComponentVerbatim() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();
            final BillPaymentRequest padded = new BillPaymentRequest("CB00", "CardDemo", "07/24/26",
                    "COBIL00C", "Bill Payment", "14:30:00", "1          ", "        0.00  ", " ",
                    "x" + " ".repeat(77));

            assertThat(mapper.readValue(mapper.writeValueAsString(padded), BillPaymentRequest.class))
                    .as("the map fields are fixed width and space padded, so trimming any of them would "
                            + "change the byte image the parity comparison reads")
                    .isEqualTo(padded);
        }

        @Test
        @DisplayName("keeps a leading-zero account identifier intact across the JSON boundary")
        void keepsALeadingZeroAccountIdentifierIntact() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final BillPaymentRequest bound = mapper.readValue(
                    "{\"accountId\":\"00000000001\"}", BillPaymentRequest.class);

            assertThat(bound.accountId())
                    .as("app/data/ASCII/acctdata.txt:1 seeds the eleven byte image 00000000001; a "
                            + "numeric type would render it as 1 and could not reconstruct it")
                    .isEqualTo("00000000001")
                    .hasSize(11);
        }
    }
}
