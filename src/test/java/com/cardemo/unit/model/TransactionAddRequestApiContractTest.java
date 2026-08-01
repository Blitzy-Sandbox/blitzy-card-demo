/*
 * ******************************************************************
 * Program     : TransactionAddRequestApiContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/model
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live AWS endpoint
 * Function    : Pins the twenty-one field contract of the
 *               transaction-add payload, the redacted diagnostic
 *               rendering that keeps the primary account number out
 *               of the logs, and the refusal of any property the map
 *               does not declare. Also pins the deliberately
 *               preserved eight-versus-nine digit asymmetry between
 *               the legacy numeric field and its edited display mask.
 * Source      : app/cpy-bms/COTRN02.CPY (21 input fields, group
 *               COTRN2AI) + app/cbl/COTRN02C.cbl:58,:59,:60,:204,
 *               :218,:383,:385-386,:444-451 + app/cpy/CVTRA05Y.cpy:10
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

import com.cardemo.model.dto.TransactionAddRequest;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
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

/**
 * Unit contract for {@link TransactionAddRequest}.
 *
 * <p>This payload is the widest trust boundary in the online surface: twenty-one caller-supplied strings,
 * one of which is a primary account number and one of which is a monetary amount in text form. Three
 * guarantees therefore get disproportionate attention here - the width bound on every field, the redacted
 * diagnostic rendering, and the refusal of an undeclared property - because each of the three is the kind
 * of control that keeps working silently until someone removes it.
 */
@DisplayName("TransactionAddRequest - app/cpy-bms/COTRN02.CPY group COTRN2AI + app/cbl/COTRN02C.cbl")
class TransactionAddRequestApiContractTest {

    /** Input data items declared by group {@code COTRN2AI}. */
    private static final int ADD_MAP_INPUT_FIELD_COUNT = 21;

    /** A synthetic primary account number at the declared width; distinctive so a leak is unambiguous. */
    private static final String SYNTHETIC_CARD_NUMBER = "4111999988887777";

    /** A synthetic account identifier at the declared width. */
    private static final String SYNTHETIC_ACCOUNT_ID = "00000000099";

    /** A synthetic merchant name, distinctive for the same reason as the card number. */
    private static final String SYNTHETIC_MERCHANT_NAME = "ZZQQ-MERCHANT-LEAK-CANARY";

    /** A synthetic amount image in the shape the screen field carries. */
    private static final String SYNTHETIC_AMOUNT_IMAGE = "+00076543.21";

    /** The twenty-one component names, in {@code COTRN02.CPY} declaration order. */
    private static final List<String> COMPONENT_ORDER = List.of("transactionName", "title01", "currentDate",
            "programName", "title02", "currentTime", "accountId", "cardNumber", "typeCode", "categoryCode",
            "source", "description", "amount", "originatingDate", "processingDate", "merchantId",
            "merchantName", "merchantCity", "merchantZip", "confirmation", "errorMessage");

    /** The baseline component values, positionally aligned with {@link #COMPONENT_ORDER}. */
    private static final List<String> BASELINE_VALUES = List.of("CT02", "CardDemo", "07/24/26", "COTRN02C",
            "Add Transaction", "14:30:00", SYNTHETIC_ACCOUNT_ID, SYNTHETIC_CARD_NUMBER, "01", "0001", "POS",
            "SYNTHETIC PURCHASE", SYNTHETIC_AMOUNT_IMAGE, "2026-07-24", "2026-07-24", "000000123",
            SYNTHETIC_MERCHANT_NAME, "SYNTHETIC CITY", "0000012345", "Y", "");

    /**
     * The twenty-one field contracts of {@code COTRN2AI}, in copybook declaration order.
     *
     * @return {@code componentName}, {@code cobolItem}, {@code sourceLine}, {@code width} tuples
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
                Arguments.of("cardNumber", "CARDNINI", 66, 16),
                Arguments.of("typeCode", "TTYPCDI", 72, 2),
                Arguments.of("categoryCode", "TCATCDI", 78, 4),
                Arguments.of("source", "TRNSRCI", 84, 10),
                Arguments.of("description", "TDESCI", 90, 60),
                Arguments.of("amount", "TRNAMTI", 96, 12),
                Arguments.of("originatingDate", "TORIGDTI", 102, 10),
                Arguments.of("processingDate", "TPROCDTI", 108, 10),
                Arguments.of("merchantId", "MIDI", 114, 9),
                Arguments.of("merchantName", "MNAMEI", 120, 30),
                Arguments.of("merchantCity", "MCITYI", 126, 25),
                Arguments.of("merchantZip", "MZIPI", 132, 10),
                Arguments.of("confirmation", "CONFIRMI", 138, 1),
                Arguments.of("errorMessage", "ERRMSGI", 144, 78));
    }

    /**
     * A fully populated, entirely width-legal request.
     *
     * @return a valid request built only from this class's deterministic fixtures
     */
    private static TransactionAddRequest baseline() {
        return build(BASELINE_VALUES);
    }

    /**
     * Builds a request from twenty-one positional values.
     *
     * @param values exactly twenty-one values, aligned with {@link #COMPONENT_ORDER}
     * @return the request the values describe
     */
    private static TransactionAddRequest build(final List<String> values) {
        if (values.size() != ADD_MAP_INPUT_FIELD_COUNT) {
            throw new AssertionError("expected " + ADD_MAP_INPUT_FIELD_COUNT + " values, got "
                    + values.size());
        }

        return new TransactionAddRequest(values.get(0), values.get(1), values.get(2), values.get(3),
                values.get(4), values.get(5), values.get(6), values.get(7), values.get(8), values.get(9),
                values.get(10), values.get(11), values.get(12), values.get(13), values.get(14),
                values.get(15), values.get(16), values.get(17), values.get(18), values.get(19),
                values.get(20));
    }

    /**
     * Builds the baseline with exactly one component replaced, so a width assertion isolates one field.
     *
     * @param componentName the component to replace
     * @param value         the replacement value, which may legitimately be over-wide
     * @return the baseline carrying {@code value} in {@code componentName} and its fixture elsewhere
     */
    private static TransactionAddRequest withComponent(final String componentName, final String value) {
        final int index = COMPONENT_ORDER.indexOf(componentName);

        if (index < 0) {
            throw new AssertionError("TransactionAddRequest declares no component " + componentName);
        }
        final List<String> values = new ArrayList<>(BASELINE_VALUES);
        values.set(index, value);

        return build(values);
    }

    /**
     * Reads an annotation from the field a record component generates, which is where {@link Size}
     * lands: {@link Size} does not list {@code RECORD_COMPONENT} among its targets.
     *
     * @param componentName  the record component whose generated field should be inspected
     * @param annotationType the annotation to look for
     * @param <A>            the annotation type
     * @return the annotation, or {@code null} when the component does not carry it
     */
    private static <A extends java.lang.annotation.Annotation> A fieldAnnotation(
            final String componentName, final Class<A> annotationType) {
        try {
            final Field field = TransactionAddRequest.class.getDeclaredField(componentName);
            return field.getAnnotation(annotationType);
        } catch (final NoSuchFieldException cause) {
            throw new AssertionError(
                    "TransactionAddRequest declares no field for component " + componentName, cause);
        }
    }

    /**
     * Validates a request with a freshly built, immediately closed validator.
     *
     * @param request the request to validate
     * @return every violation raised, possibly empty
     */
    private static Set<ConstraintViolation<TransactionAddRequest>> violationsOf(
            final TransactionAddRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            final Validator validator = factory.getValidator();
            return validator.validate(request);
        }
    }

    /**
     * Runs an action expected to be refused and returns the {@link IllegalArgumentException} behind the
     * refusal, or {@code null} if none appears in the cause chain.
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
    @DisplayName("1. Field contract - twenty-one input fields of COTRN2AI, every width byte exact")
    class FieldContract {

        @Test
        @DisplayName("declares exactly the twenty-one input fields the map declares")
        void declaresExactlyTwentyOneComponents() {
            assertThat(TransactionAddRequest.class.getRecordComponents())
                    .as("group COTRN2AI declares twenty-one 02-level PIC items; the terminal could send "
                            + "nothing else, so the contract is closed in both directions")
                    .hasSize(ADD_MAP_INPUT_FIELD_COUNT);
        }

        @Test
        @DisplayName("orders its components exactly as the copybook declares them")
        void ordersComponentsAsTheCopybookDeclaresThem() {
            assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList())
                    .as("the canonical constructor is positional and all twenty-one components are "
                            + "String, so a reordering would compile cleanly and transpose the card "
                            + "number with the account identifier without a single warning")
                    .containsExactlyElementsOf(COMPONENT_ORDER);
        }

        @Test
        @DisplayName("types every component as String, because every input item is PIC X")
        void typesEveryComponentAsString() {
            assertThat(TransactionAddRequest.class.getRecordComponents())
                    .allSatisfy(component -> assertThat(component.getType())
                            .as("component %s carries a PIC X item; in particular TRNAMTI is PIC X(12) "
                                    + "at app/cpy-bms/COTRN02.CPY:96, an unparsed screen image rather "
                                    + "than a number, so the amount is a String here by design",
                                    component.getName())
                            .isEqualTo(String.class));
        }

        @ParameterizedTest(name = "{1} PIC X({3}) at COTRN02.CPY:{2} -> {0}")
        @MethodSource("com.cardemo.unit.model.TransactionAddRequestApiContractTest#fieldContracts")
        @DisplayName("bounds every component at its declared width and no tighter")
        void boundsEveryComponentAtItsDeclaredWidth(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            final Size size = fieldAnnotation(componentName, Size.class);

            assertThat(size)
                    .as("%s is declared at app/cpy-bms/COTRN02.CPY:%d and every one of these is caller "
                            + "supplied, so %s must carry a Size bound", cobolItem, sourceLine,
                            componentName)
                    .isNotNull();
            assertThat(size.max())
                    .as("the bound must equal the declared PIC X(%d) width of %s", width, cobolItem)
                    .isEqualTo(width);
            assertThat(size.min())
                    .as("the source imposes no minimum length on %s", cobolItem)
                    .isZero();
            assertThat(size.message())
                    .as("this file states its bound messages explicitly rather than relying on the "
                            + "default, so each must name its own component")
                    .contains(componentName);
        }

        @ParameterizedTest(name = "{1} X({3}): {3} accepted, {3} + 1 refused")
        @MethodSource("com.cardemo.unit.model.TransactionAddRequestApiContractTest#fieldContracts")
        @DisplayName("accepts a value at exactly the declared width and refuses one character more")
        void acceptsExactWidthAndRefusesOneMore(final String componentName, final String cobolItem,
                final int sourceLine, final int width) {
            assertThat(violationsOf(withComponent(componentName, "x".repeat(width))))
                    .as("%d characters is the largest legal value for %s at line %d", width, cobolItem,
                            sourceLine)
                    .isEmpty();

            final Set<ConstraintViolation<TransactionAddRequest>> violations =
                    violationsOf(withComponent(componentName, "x".repeat(width + 1)));

            assertThat(violations)
                    .as("only %s exceeds its bound, so exactly one violation is expected; a second would "
                            + "mean the baseline itself is invalid", componentName)
                    .hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString())
                    .isEqualTo(componentName);
        }

        @Test
        @DisplayName("treats null and blank as valid, because absence and blankness are source states")
        void treatsNullAndBlankAsValid() {
            assertThat(violationsOf(build(Collections.nCopies(ADD_MAP_INPUT_FIELD_COUNT, null))))
                    .as("a Size bound treats null as valid, which is right here: the source reports each "
                            + "missing field through its own screen message rather than as a format error")
                    .isEmpty();
            assertThat(violationsOf(build(Collections.nCopies(ADD_MAP_INPUT_FIELD_COUNT, ""))))
                    .as("a blank field is a state the service interprets, not a bound breach")
                    .isEmpty();
        }

        @Test
        @DisplayName("declares no transaction identifier field, because the program generates it")
        void declaresNoTransactionIdentifierField() {
            assertThat(COMPONENT_ORDER)
                    .as("app/cbl/COTRN02C.cbl:444-451 generates the identifier by browsing the file "
                            + "backwards from HIGH-VALUES and adding one to the maximum key. A caller "
                            + "cannot supply it, and COTRN2AI declares no item for it - unlike COTRN01, "
                            + "which declares TRNIDINI and TRNIDI because that screen searches by it")
                    .doesNotContain("transactionId")
                    .doesNotContain("transactionIdInput");
        }

        @Test
        @DisplayName("carries the amount as an unparsed image, not as a numeric type")
        void carriesTheAmountAsAnUnparsedImage() {
            assertThat(fieldAnnotation("amount", Size.class).max())
                    .as("TRNAMTI is PIC X(12) at app/cpy-bms/COTRN02.CPY:96 - the twelve characters the "
                            + "operator typed, which app/cbl/COTRN02C.cbl:383 then parses with the "
                            + "CURRENCY-aware intrinsic rather than the plain one")
                    .isEqualTo(12);
            assertThat(Arrays.stream(TransactionAddRequest.class.getRecordComponents())
                    .map(component -> component.getType().getName())
                    .filter(name -> name.contains("Float") || name.contains("Double")
                            || "float".equals(name) || "double".equals(name))
                    .toList())
                    .as("no floating-point type may appear in any financial field")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("2. The diagnostic rendering publishes no primary account number")
    class DiagnosticRendering {

        @Test
        @DisplayName("overrides toString rather than inheriting the generated one")
        void overridesToStringRatherThanInheritingIt() {
            assertThat(Arrays.stream(TransactionAddRequest.class.getDeclaredMethods())
                    .map(method -> method.getName())
                    .filter("toString"::equals)
                    .toList())
                    .as("a record's generated toString emits every component, which on this payload means "
                            + "the primary account number, the merchant name and the amount")
                    .containsExactly("toString");
        }

        @Test
        @DisplayName("emits neither the card number nor the amount nor the merchant name")
        void emitsNeitherCardNumberNorAmountNorMerchantName() {
            final String rendered = baseline().toString();

            assertThat(rendered)
                    .as("cardNumber is a primary account number; the amount and merchant name together "
                            + "describe what a customer bought and for how much")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_AMOUNT_IMAGE)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME);
            assertThat(rendered)
                    .as("no masked or last-four fragment of the card number is acceptable either")
                    .doesNotContain("7777")
                    .doesNotContain("4111");
        }

        @Test
        @DisplayName("emits only the account identifier and the program name")
        void emitsOnlyTheAccountIdentifierAndProgramName() {
            assertThat(baseline().toString())
                    .as("the account identifier is the correlation key an operator needs in order to "
                            + "find the failing request, and the program name says which screen produced it")
                    .isEqualTo("TransactionAddRequest[accountId=" + SYNTHETIC_ACCOUNT_ID
                            + ", programName=COTRN02C]");
        }

        @Test
        @DisplayName("discloses nothing when interpolated into a message, which is how a log leaks")
        void disclosesNothingWhenInterpolated() {
            assertThat("transaction add failed: " + baseline())
                    .as("implicit toString through concatenation is the path by which a generated "
                            + "rendering reaches a log without anyone deciding that it should")
                    .doesNotContain(SYNTHETIC_CARD_NUMBER)
                    .doesNotContain(SYNTHETIC_MERCHANT_NAME);
        }

        @Test
        @DisplayName("keeps every value readable through its accessor, so nothing was lost")
        void keepsEveryValueReadableThroughItsAccessor() {
            final TransactionAddRequest request = baseline();

            assertThat(request.cardNumber()).isEqualTo(SYNTHETIC_CARD_NUMBER);
            assertThat(request.amount()).isEqualTo(SYNTHETIC_AMOUNT_IMAGE);
            assertThat(request.merchantName()).isEqualTo(SYNTHETIC_MERCHANT_NAME);
            assertThat(request.confirmation())
                    .as("redacting the rendering must not redact the data the service acts on")
                    .isEqualTo("Y");
        }
    }

    @Nested
    @DisplayName("3. MEDIUM: an unrecognised JSON property is refused, not silently discarded")
    class UnrecognisedProperties {

        @Test
        @DisplayName("refuses an unrecognised property under a lenient mapper as well as a strict one")
        void refusesAnUnrecognisedPropertyUnderEitherMapperPosture() {
            final String body = "{\"accountId\":\"" + SYNTHETIC_ACCOUNT_ID + "\",\"transactionId\":\"1\"}";

            final ObjectMapper lenient = new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            final ObjectMapper strict = new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

            assertThat(refusalOf(() -> lenient.readValue(body, TransactionAddRequest.class)))
                    .as("this repository publishes no application*.yml, so the framework default of "
                            + "ignoring unknown properties is the posture that actually ships. The guard "
                            + "is declared on the type so that it holds regardless of mapper configuration")
                    .isNotNull();
            assertThat(refusalOf(() -> strict.readValue(body, TransactionAddRequest.class)))
                    .isNotNull();
        }

        @Test
        @DisplayName("states the field count and the copybook, and echoes neither name nor value")
        void statesTheContractAndEchoesNeitherNameNorValue() {
            final String offendingName = "sequenceNumber";
            final String offendingValue = "9999";
            final String body = "{\"" + offendingName + "\":\"" + offendingValue + "\"}";

            final IllegalArgumentException refusal =
                    refusalOf(() -> new ObjectMapper().readValue(body, TransactionAddRequest.class));

            assertThat(refusal).isNotNull();
            assertThat(refusal.getMessage())
                    .as("the message must state the declared field count and cite the map so a caller can "
                            + "find the contract it breached")
                    .contains(String.valueOf(ADD_MAP_INPUT_FIELD_COUNT))
                    .contains("app/cpy-bms/COTRN02.CPY");
            assertThat(refusal.getMessage())
                    .as("echoing either would let a caller place chosen text into the logs of a failed "
                            + "financial request")
                    .doesNotContain(offendingName)
                    .doesNotContain(offendingValue);
        }

        @ParameterizedTest(name = "a misspelling of {0} is refused rather than dropped")
        @CsvSource({"confirmation, confirmatoin", "cardNumber, cardNum", "amount, transactionAmount"})
        @DisplayName("a misspelled property is refused, so a typo cannot read as an absent field")
        void aMisspelledPropertyIsRefused(final String declared, final String misspelling) {
            final String body = "{\"" + misspelling + "\":\"Y\"}";

            assertThat(refusalOf(() -> new ObjectMapper().readValue(body, TransactionAddRequest.class)))
                    .as("silently dropping '%s' would leave '%s' absent, and an absent confirmation reads "
                            + "to the source as 'not yet confirmed' rather than as the malformed request "
                            + "it is - so the caller would be shown a confirmation prompt they already "
                            + "answered", misspelling, declared)
                    .isNotNull();
        }

        @Test
        @DisplayName("accepts a body naming only declared properties, so it refuses nothing it should "
                + "admit")
        void acceptsABodyNamingOnlyDeclaredProperties() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            assertThat(mapper.readValue(mapper.writeValueAsString(baseline()), TransactionAddRequest.class))
                    .as("a round trip of this type's own output names only declared properties and must "
                            + "pass the guard untouched")
                    .isEqualTo(baseline());
        }
    }

    @Nested
    @DisplayName("4. Serialized surface - exactly the twenty-one map fields, nothing invented")
    class SerializedSurface {

        @Test
        @DisplayName("emits exactly the twenty-one map fields and no structural helper")
        void emitsExactlyTheTwentyOneMapFields() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final Map<String, Object> emitted = mapper.readValue(
                    mapper.writeValueAsString(baseline()),
                    new TypeReference<LinkedHashMap<String, Object>>() { });

            assertThat(emitted.keySet())
                    .as("the wire contract is the map's twenty-one fields; a helper or a derived total "
                            + "would publish a field the screen never had")
                    .containsExactlyInAnyOrderElementsOf(COMPONENT_ORDER);
        }

        @Test
        @DisplayName("round-trips every component verbatim, padding included")
        void roundTripsEveryComponentVerbatim() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();
            final TransactionAddRequest padded = withComponent("merchantName", "A" + " ".repeat(29));

            assertThat(mapper.readValue(mapper.writeValueAsString(padded), TransactionAddRequest.class))
                    .as("the map fields are fixed width and space padded, so trimming any of them would "
                            + "change the byte image the parity comparison reads")
                    .isEqualTo(padded);
            assertThat(padded.merchantName()).hasSize(30);
        }

        @Test
        @DisplayName("keeps a leading-zero card number intact across the JSON boundary")
        void keepsALeadingZeroCardNumberIntact() throws Exception {
            final ObjectMapper mapper = new ObjectMapper();

            final TransactionAddRequest bound = mapper.readValue(
                    "{\"cardNumber\":\"0000000000000001\"}", TransactionAddRequest.class);

            assertThat(bound.cardNumber())
                    .as("CARD-NUM is PIC X(16) at app/cpy/CVACT02Y.cpy, so every one of its sixteen bytes "
                            + "is significant; a numeric type would render this as 1 and could never "
                            + "reconstruct the original")
                    .isEqualTo("0000000000000001")
                    .hasSize(16);
        }
    }

    @Nested
    @DisplayName("5. Amount constants - the preserved eight-versus-nine digit asymmetry")
    class AmountConstants {

        @Test
        @DisplayName("declares the edited display mask exactly as the source spells it")
        void declaresTheEditedDisplayMask() {
            assertThat(TransactionAddRequest.AMOUNT_DISPLAY_MASK)
                    .as("WS-TRAN-AMT-E at app/cbl/COTRN02C.cbl:59 carries a MANDATORY sign, exactly eight "
                            + "integer digits and two decimals")
                    .isEqualTo("+99999999.99")
                    .hasSize(12);
        }

        @Test
        @DisplayName("keeps the mask ceiling strictly below the numeric ceiling, preserving the asymmetry")
        void keepsTheMaskCeilingBelowTheNumericCeiling() {
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX)
                    .as("the edited field renders eight integer digits while the numeric field "
                            + "WS-TRAN-AMT-N at app/cbl/COTRN02C.cbl:58 holds nine. The round trip at "
                            + ":385-386 moves the parsed value through the edited field and back, so an "
                            + "amount above the mask ceiling loses its leading digit on the echo. That is "
                            + "preserved, not repaired - the mask is never widened to nine digits")
                    .isLessThan(TransactionAddRequest.AMOUNT_VALUE_MAX);
            assertThat(TransactionAddRequest.AMOUNT_MASK_MAX).isEqualByComparingTo("99999999.99");
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX).isEqualByComparingTo("999999999.99");
        }

        @Test
        @DisplayName("declares scale 2 and precision 11, from TRAN-AMT PIC S9(09)V99")
        void declaresScaleAndPrecision() {
            assertThat(TransactionAddRequest.AMOUNT_SCALE)
                    .as("TRAN-AMT at app/cpy/CVTRA05Y.cpy:10 is PIC S9(09)V99")
                    .isEqualTo(2);
            assertThat(TransactionAddRequest.AMOUNT_PRECISION)
                    .as("nine integer digits plus two decimals is eleven significant digits, which is the "
                            + "transaction money precision - distinct from the account NUMERIC(12,2)")
                    .isEqualTo(11);
            assertThat(TransactionAddRequest.AMOUNT_VALUE_MAX.precision())
                    .as("the declared ceiling must itself occupy the declared precision")
                    .isEqualTo(TransactionAddRequest.AMOUNT_PRECISION);
        }

        @Test
        @DisplayName("declares HALF_EVEN rounding, never HALF_UP")
        void declaresHalfEvenRounding() {
            assertThat(TransactionAddRequest.AMOUNT_ROUNDING_MODE)
                    .as("HALF_UP would round every .xx5 away from zero and drift against the baseline "
                            + "over a multi-record run")
                    .isEqualTo(RoundingMode.HALF_EVEN)
                    .isNotEqualTo(RoundingMode.HALF_UP);
        }

        @ParameterizedTest(name = "{0} scales half-even to {1}")
        @CsvSource({"1.005, 1.00", "1.015, 1.02", "1.025, 1.02", "-1.015, -1.02"})
        @DisplayName("the declared scale and mode together round as the source rounds")
        void theDeclaredScaleAndModeRoundAsTheSourceRounds(final String supplied, final String expected) {
            assertThat(new BigDecimal(supplied).setScale(TransactionAddRequest.AMOUNT_SCALE,
                    TransactionAddRequest.AMOUNT_ROUNDING_MODE))
                    .as("the constants exist so that the service and its tests share one definition of "
                            + "the arithmetic rather than two that can diverge")
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("carries the date format as a legacy picture string, not a Java pattern")
        void carriesTheDateFormatAsALegacyPictureString() {
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT)
                    .as("WS-DATE-FORMAT at app/cbl/COTRN02C.cbl:60 is handed to CSUTLDTC as DATA. Feeding "
                            + "it to a DateTimeFormatter would be wrong twice over: the year symbol there "
                            + "is lower case, and upper-case YYYY means week-based-year")
                    .isEqualTo("YYYY-MM-DD")
                    .hasSize(10);
            assertThat(TransactionAddRequest.DATE_VALIDATION_FORMAT)
                    .as("the same literal is quoted back to the operator in the screen message at "
                            + "app/cbl/COTRN02C.cbl:375-376, so its exact spelling is observable and the "
                            + "upper-case year symbol must survive")
                    .isEqualTo(TransactionAddRequest.DATE_VALIDATION_FORMAT.toUpperCase(Locale.ROOT))
                    .startsWith("YYYY");
        }
    }
}
