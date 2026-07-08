package com.carddemo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Unit tests for {@link BillPaymentRequest}, the request body of the Bill Payment
 * transaction ({@code CB00} / {@code COBIL00C}).
 *
 * <p>The production record is migrated from the input fields of the {@code COBIL00}
 * BMS symbolic map ({@code app/cpy-bms/COBIL00.CPY}, referenced by source SHA
 * {@code 27d6c6f}). Only the two user-supplied inputs are carried on the request:
 * the account identifier ({@code ACTIDIN}, {@code PIC X(11)}) and the confirmation
 * flag ({@code CONFIRM}, {@code PIC X(1)}). The screen's current-balance field
 * ({@code CURBAL}, {@code PIC X(14)}) is display-only and therefore lives on the
 * response DTO, not here.</p>
 *
 * <p>These tests pin the validation and JSON-binding contract of the record:</p>
 * <ul>
 *   <li><strong>Valid instances</strong> — a well-formed 1-to-11-digit account id
 *       with any {@code confirm} value produces zero constraint violations.</li>
 *   <li><strong>{@code accountId} constraints</strong> — the field carries three
 *       Jakarta Bean-Validation constraints on the production record:
 *       {@code @NotBlank}, {@code @Size(max = 11)} and
 *       {@code @Pattern(regexp = "\\d{1,11}")}. Each is proven with failing input,
 *       and every violation is proven to target the {@code accountId} property.</li>
 *   <li><strong>JSON binding / round-trip</strong> — the canonical JSON payload
 *       deserializes into a matching record, the account id stays a {@code String}
 *       so leading zeros survive, and a serialize/deserialize cycle preserves value
 *       equality.</li>
 * </ul>
 *
 * <p>Validation is exercised with the shared, framework-free
 * {@link DtoTestSupport#VALIDATOR} and JSON with the production-mirroring
 * {@link DtoTestSupport} mapper helpers, so no Spring context or Testcontainers are
 * required.</p>
 */
@DisplayName("BillPaymentRequest DTO")
class BillPaymentRequestTest {

    /**
     * The single validated property on the request record; every constraint
     * violation raised by these tests must target this property path.
     */
    private static final String PROPERTY_ACCOUNT_ID = "accountId";

    /** A canonical, fully valid 11-digit account id (the maximum permitted width). */
    private static final String VALID_ACCOUNT_ID = "12345678901";

    /**
     * Validates the supplied request with the shared programmatic
     * {@link DtoTestSupport#VALIDATOR}.
     *
     * @param request the request to validate
     * @return the (possibly empty) set of constraint violations
     */
    private static Set<ConstraintViolation<BillPaymentRequest>> validate(BillPaymentRequest request) {
        return DtoTestSupport.VALIDATOR.validate(request);
    }

    /**
     * Collects the distinct constraint-annotation types that produced the supplied
     * violations (for example {@code NotBlank.class}, {@code Pattern.class}).
     *
     * <p>The result is intentionally typed as {@code Set<Class<?>>} rather than
     * {@code Set<Class<? extends Annotation>>}: an unbounded-wildcard array
     * ({@code Class<?>[]}) is reifiable, so AssertJ's varargs {@code contains(...)}
     * assertions raise no {@code unchecked}/{@code varargs} warning under
     * {@code -Xlint:all}.</p>
     *
     * @param violations the violations to inspect
     * @return the set of annotation types that were violated
     */
    private static Set<Class<?>> violatedConstraintTypes(
            Set<ConstraintViolation<BillPaymentRequest>> violations) {
        return violations.stream()
                .<Class<?>>map(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType())
                .collect(Collectors.toSet());
    }

    @Nested
    @DisplayName("valid instances produce no violations")
    class ValidInstances {

        @Test
        @DisplayName("11-digit account id with confirm=true is valid")
        void elevenDigitAccountIdWithConfirmIsValid() {
            BillPaymentRequest request = new BillPaymentRequest(VALID_ACCOUNT_ID, true);
            assertThat(validate(request)).isEmpty();
        }

        @Test
        @DisplayName("single-digit account id is valid (pattern allows 1..11 digits)")
        void singleDigitAccountIdIsValid() {
            BillPaymentRequest request = new BillPaymentRequest("5", true);
            assertThat(validate(request)).isEmpty();
        }

        @ParameterizedTest(name = "confirm={0}")
        @NullSource
        @ValueSource(booleans = {true, false})
        @DisplayName("confirm flag is unconstrained: true, false and null are all valid")
        void confirmFlagIsUnconstrained(Boolean confirm) {
            BillPaymentRequest request = new BillPaymentRequest(VALID_ACCOUNT_ID, confirm);
            assertThat(validate(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("accountId constraints")
    class AccountIdConstraints {

        @ParameterizedTest(name = "accountId=[{0}]")
        @NullSource
        @EmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("null, empty or whitespace account id triggers @NotBlank")
        void blankAccountIdTriggersNotBlank(String accountId) {
            Set<ConstraintViolation<BillPaymentRequest>> violations =
                    validate(new BillPaymentRequest(accountId, true));

            assertThat(violations).isNotEmpty();
            assertThat(violatedConstraintTypes(violations)).contains(NotBlank.class);
            assertThat(violations).allSatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).isEqualTo(PROPERTY_ACCOUNT_ID));
        }

        @Test
        @DisplayName("12-digit account id violates @Pattern and @Size(max=11)")
        void tooLongAccountIdViolatesPatternAndSize() {
            Set<ConstraintViolation<BillPaymentRequest>> violations =
                    validate(new BillPaymentRequest("123456789012", true));

            assertThat(violations).isNotEmpty();
            // A 12-character id breaches both the numeric pattern (1..11 digits) and
            // the explicit @Size(max = 11) bound present on the production record.
            assertThat(violatedConstraintTypes(violations)).contains(Pattern.class, Size.class);
            assertThat(violations).allSatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).isEqualTo(PROPERTY_ACCOUNT_ID));
        }

        @Test
        @DisplayName("account id with non-digit characters violates @Pattern only")
        void nonDigitAccountIdViolatesPattern() {
            Set<ConstraintViolation<BillPaymentRequest>> violations =
                    validate(new BillPaymentRequest("12345ABC", true));

            assertThat(violations).isNotEmpty();
            // Eight characters: within @Size(max = 11) and not blank, so @Pattern is
            // the sole violated constraint.
            assertThat(violatedConstraintTypes(violations)).containsOnly(Pattern.class);
            assertThat(violations).allSatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).isEqualTo(PROPERTY_ACCOUNT_ID));
        }

        @Test
        @DisplayName("every violation targets the 'accountId' property path")
        void violationsTargetAccountIdPropertyPath() {
            Set<ConstraintViolation<BillPaymentRequest>> violations =
                    validate(new BillPaymentRequest("ABCDE", true));

            assertThat(violations).isNotEmpty();
            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsOnly(PROPERTY_ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("JSON binding and round-trip")
    class JsonBinding {

        @Test
        @DisplayName("deserializes canonical JSON and binds both fields")
        void deserializesCanonicalJsonAndBindsFields() {
            String json = "{\"accountId\":\"12345678901\",\"confirm\":true}";

            BillPaymentRequest request = DtoTestSupport.fromJson(json, BillPaymentRequest.class);

            assertThat(request.accountId()).isEqualTo("12345678901");
            assertThat(request.confirm()).isTrue();
        }

        @Test
        @DisplayName("account id stays a String, preserving leading zeros")
        void accountIdStaysStringPreservingLeadingZeros() {
            String json = "{\"accountId\":\"00000000123\",\"confirm\":false}";

            BillPaymentRequest request = DtoTestSupport.fromJson(json, BillPaymentRequest.class);

            // Had the id been bound as a number, the leading zeros would be lost; an
            // exact String match proves it is preserved verbatim.
            assertThat(request.accountId()).isEqualTo("00000000123");
            assertThat(request.confirm()).isFalse();
        }

        @Test
        @DisplayName("serializes account id as a quoted JSON string")
        void serializesAccountIdAsQuotedString() {
            String json = DtoTestSupport.toJson(new BillPaymentRequest("00000000123", true));

            assertThat(json).contains("\"accountId\":\"00000000123\"");
        }

        @Test
        @DisplayName("round-trips through JSON with value equality")
        void roundTripsWithValueEquality() {
            BillPaymentRequest original = new BillPaymentRequest(VALID_ACCOUNT_ID, true);

            assertThat(DtoTestSupport.roundTrip(original, BillPaymentRequest.class)).isEqualTo(original);
        }
    }
}
