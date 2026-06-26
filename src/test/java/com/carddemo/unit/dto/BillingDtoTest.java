package com.carddemo.unit.dto;

import com.carddemo.dto.BillingDto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit 5 unit tests for {@link BillingDto} and its nested
 * {@link BillingDto.PayRequest} / {@link BillingDto.PayResponse} records.
 *
 * <p>The field contract under test is derived byte-accurately from BMS mapset
 * {@code app/bms/COBIL00.bms} and program {@code app/cbl/COBIL00C.cbl}
 * (CICS transaction {@code CB00}) at source commit {@code 27d6c6f}: the bill
 * payment screen exposes {@code ACTIDIN} ({@code PIC X(11)} account id input),
 * {@code CURBAL} (current balance display realizing
 * {@code ACCT-CURR-BAL PIC S9(10)V99}) and {@code CONFIRM} ({@code PIC X(1)}
 * Y/N flag).</p>
 *
 * <p>These tests are intentionally standalone: they exercise the jakarta Bean
 * Validation {@link Validator} and JDK record reflection only, with no Spring
 * context, Testcontainers or Mockito. The two structural concerns guarded here
 * are (1) the exact record shape — component names, order and types — and
 * (2) the bean-validation constraints, asserting both violating and passing
 * inputs by {@code propertyPath}. The decimal-exactness guard
 * (AAP &sect;0.6.1) is enforced by asserting that no component is a
 * floating-point type and that {@code currentBalance} is a {@link BigDecimal}.</p>
 */
class BillingDtoTest {

    /** Shared, thread-safe validator built once for the whole test class. */
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        // ValidatorFactory is AutoCloseable; close it once the singleton Validator is obtained.
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /** Returns the ordered record-component names of the given record type. */
    private static List<String> componentNames(Class<?> recordType) {
        List<String> names = new java.util.ArrayList<>();
        for (RecordComponent component : recordType.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    /** Returns the named record component, or fails the test if it is absent. */
    private static RecordComponent component(Class<?> recordType, String name) {
        for (RecordComponent candidate : recordType.getRecordComponents()) {
            if (candidate.getName().equals(name)) {
                return candidate;
            }
        }
        throw new AssertionError(
                "no record component named '" + name + "' on " + recordType.getName());
    }

    /**
     * Asserts that no component of the given record type is a floating-point
     * type — monetary precision must be carried by {@link BigDecimal}
     * (decimal exactness, AAP &sect;0.6.1), never {@code double}/{@code float}.
     */
    private static void assertNoFloatingPointComponents(Class<?> recordType) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            assertThat(component.getType())
                    .as("record component '%s' of %s must not be floating-point (AAP 0.6.1)",
                            component.getName(), recordType.getSimpleName())
                    .isNotIn(double.class, float.class, Double.class, Float.class);
        }
    }

    /** True when any violation in the set targets the given property path. */
    private static <T> boolean hasViolationOn(Set<ConstraintViolation<T>> violations, String property) {
        for (ConstraintViolation<T> violation : violations) {
            if (violation.getPropertyPath().toString().equals(property)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Record shape (reflection on the nested records)
    // ------------------------------------------------------------------

    @Test
    void payRequestComponentsInOrder() {
        // PIC X(11) account id (ACTIDIN) then PIC X(1) confirm flag (CONFIRM).
        assertThat(componentNames(BillingDto.PayRequest.class))
                .containsExactly("accountId", "confirm");
    }

    @Test
    void payResponseComponentsInOrder() {
        // PayResponse models the three business fields plus the two success-only
        // fields surfaced for COBIL00C parity (#4): transactionId and the byte-exact
        // confirmationMessage banner, both @JsonInclude(NON_NULL) so they are omitted
        // from the JSON response on the confirm = N cancel path.
        assertThat(componentNames(BillingDto.PayResponse.class))
                .containsExactly("accountId", "currentBalance", "confirm",
                        "transactionId", "confirmationMessage");
        // CURBAL <- ACCT-CURR-BAL S9(10)V99 must be BigDecimal, never a binary float.
        assertThat(component(BillingDto.PayResponse.class, "currentBalance").getType())
                .isEqualTo(BigDecimal.class);
        // transactionId (TRAN-ID PIC X(16)) and confirmationMessage are Strings.
        assertThat(component(BillingDto.PayResponse.class, "transactionId").getType())
                .isEqualTo(String.class);
        assertThat(component(BillingDto.PayResponse.class, "confirmationMessage").getType())
                .isEqualTo(String.class);
    }

    // ------------------------------------------------------------------
    // No floating-point components (decimal exactness, AAP 0.6.1)
    // ------------------------------------------------------------------

    @Test
    void noFloatingPointComponents() {
        assertNoFloatingPointComponents(BillingDto.PayRequest.class);
        assertNoFloatingPointComponents(BillingDto.PayResponse.class);
    }

    // ------------------------------------------------------------------
    // Bean validation — PayRequest
    // ------------------------------------------------------------------

    @Test
    void payRequestBlankAccountIdAcceptedByDto() {
        // Parity fix (QA #3): @NotBlank removed and @Pattern relaxed to "\\d{0,11}" so a blank
        // account id reaches BillingService.payBill, which emits the byte-exact
        // "Acct ID can NOT be empty..." message (AAP 0.7.1.1; mirrors D-056/D-062).
        BillingDto.PayRequest request = new BillingDto.PayRequest("", "Y");
        Set<ConstraintViolation<BillingDto.PayRequest>> violations = validator.validate(request);
        assertThat(hasViolationOn(violations, "accountId")).isFalse();
    }

    @Test
    void payRequestTwelveDigitAccountIdFailsSize() {
        // 12 characters exceeds the X(11) width.
        BillingDto.PayRequest tooLong = new BillingDto.PayRequest("123456789012", "Y");
        Set<ConstraintViolation<BillingDto.PayRequest>> tooLongViolations = validator.validate(tooLong);
        assertThat(hasViolationOn(tooLongViolations, "accountId")).isTrue();

        // Exactly 11 digits is the boundary and must pass.
        BillingDto.PayRequest exactlyEleven = new BillingDto.PayRequest("12345678901", "Y");
        Set<ConstraintViolation<BillingDto.PayRequest>> exactlyElevenViolations =
                validator.validate(exactlyEleven);
        assertThat(hasViolationOn(exactlyElevenViolations, "accountId")).isFalse();
    }

    @Test
    void payRequestNonDigitAccountIdFailsPattern() {
        // @Pattern("\\d{1,11}") rejects a non-digit character within the 11-char width.
        BillingDto.PayRequest nonDigit = new BillingDto.PayRequest("12A45678901", "Y");
        Set<ConstraintViolation<BillingDto.PayRequest>> violations = validator.validate(nonDigit);
        assertThat(hasViolationOn(violations, "accountId")).isTrue();
    }

    @Test
    void payRequestConfirmWithinOneCharAcceptedByDto() {
        // Parity fix (QA #3): @Pattern("[YyNn]?") was removed so an out-of-range flag like 'X'
        // reaches BillingService, which emits the byte-exact "Invalid value. Valid values are
        // (Y/N)..." message. Only @Size(max=1) remains, so a single character passes the DTO.
        BillingDto.PayRequest invalidConfirm = new BillingDto.PayRequest("12345678901", "X");
        Set<ConstraintViolation<BillingDto.PayRequest>> invalidViolations =
                validator.validate(invalidConfirm);
        assertThat(hasViolationOn(invalidViolations, "confirm")).isFalse();

        // 'Y' is accepted.
        BillingDto.PayRequest yesConfirm = new BillingDto.PayRequest("12345678901", "Y");
        assertThat(hasViolationOn(validator.validate(yesConfirm), "confirm")).isFalse();

        // The empty string is accepted (within @Size(max=1)).
        BillingDto.PayRequest emptyConfirm = new BillingDto.PayRequest("12345678901", "");
        assertThat(hasViolationOn(validator.validate(emptyConfirm), "confirm")).isFalse();
    }

    @Test
    void payRequestValidInstancePasses() {
        BillingDto.PayRequest request = new BillingDto.PayRequest("12345678901", "Y");
        assertThat(validator.validate(request)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Bean validation — PayResponse (@Digits(integer = 10, fraction = 2))
    // ------------------------------------------------------------------

    @Test
    void payResponseCurrentBalanceOverScaleFailsDigits() {
        // 3 fractional digits exceeds fraction = 2.
        BillingDto.PayResponse overScale =
                new BillingDto.PayResponse("12345678901", new BigDecimal("1.234"), "Y", null, null);
        assertThat(hasViolationOn(validator.validate(overScale), "currentBalance")).isTrue();

        // 11 integer digits exceeds integer = 10.
        BillingDto.PayResponse overInteger =
                new BillingDto.PayResponse("12345678901", new BigDecimal("12345678901.12"), "Y", null, null);
        assertThat(hasViolationOn(validator.validate(overInteger), "currentBalance")).isTrue();
    }

    @Test
    void payResponseCurrentBalanceBoundaryPasses() {
        // 10 integer digits + 2 fractional digits is the S9(10)V99 boundary and must pass.
        BillingDto.PayResponse boundary =
                new BillingDto.PayResponse("12345678901", new BigDecimal("1234567890.99"), "Y", null, null);
        assertThat(hasViolationOn(validator.validate(boundary), "currentBalance")).isFalse();
    }
}
