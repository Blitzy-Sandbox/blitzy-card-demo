package com.carddemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.model.dto.TransactionAddRequest;
import com.carddemo.model.dto.UserAddRequest;
import com.carddemo.model.dto.UserUpdateRequest;
import com.carddemo.model.dto.constraint.NoHtml;
import com.carddemo.model.dto.constraint.NoHtmlValidator;
import com.carddemo.model.enums.UserType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reusable {@link NoHtml} constraint and its {@link NoHtmlValidator}. Covers both
 * the validator in isolation and its wiring onto the {@link TransactionAddRequest} free-text
 * fields, confirming that active markup is rejected while legitimate Unicode/emoji content is
 * accepted (QA stored-XSS finding closure with no regression for valid input).
 */
@DisplayName("NoHtml constraint - rejects HTML markup, accepts Unicode/emoji")
class NoHtmlValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    // ---- Validator in isolation -------------------------------------------------------------

    @Test
    @DisplayName("null is accepted (presence governed by @NotBlank, not pre-empted)")
    void nullIsValid() {
        assertThat(new NoHtmlValidator().isValid(null, null)).isTrue();
    }

    @Test
    @DisplayName("plain text, Unicode letters, and emoji are accepted")
    void cleanContentIsValid() {
        NoHtmlValidator v = new NoHtmlValidator();
        assertThat(v.isValid("POS", null)).isTrue();
        assertThat(v.isValid("CAFE MUNCHEN", null)).isTrue();
        assertThat(v.isValid("caf\u00e9 \u2615 \uD83D\uDE00", null)).isTrue();
        assertThat(v.isValid("O'BRIEN & SONS, 100% OK!", null)).isTrue();
        assertThat(v.isValid("", null)).isTrue();
    }

    @Test
    @DisplayName("a leading '<' or a trailing/embedded '>' is rejected")
    void markupCharactersAreRejected() {
        NoHtmlValidator v = new NoHtmlValidator();
        assertThat(v.isValid("<script>x</script>", null)).isFalse();
        assertThat(v.isValid("<svg/onload=1>", null)).isFalse();
        assertThat(v.isValid("a<b", null)).isFalse();
        assertThat(v.isValid("a>b", null)).isFalse();
        assertThat(v.isValid("<img src=x onerror=alert(1)>", null)).isFalse();
    }

    // ---- Wiring onto TransactionAddRequest free-text fields ---------------------------------

    private static TransactionAddRequest req(String source, String description,
            String merchantName, String merchantCity, String merchantZip) {
        return new TransactionAddRequest("00000000001", "4111111111111111", "01", "0001",
                source, description, new BigDecimal("100.00"), "2022-07-01", "2022-07-01",
                "123456789", merchantName, merchantCity, merchantZip, "Y");
    }

    @Test
    @DisplayName("clean request (incl. emoji) has no NoHtml violation")
    void cleanRequestHasNoNoHtmlViolation() {
        var violations = validator.validate(
                req("POS", "Coffee \u2615", "CAFE", "MUNCHEN", "12345"));
        assertThat(noHtmlViolatedFields(violations)).isEmpty();
    }

    @Test
    @DisplayName("markup in each free-text field raises a NoHtml violation on exactly that field")
    void markupRaisesNoHtmlViolationPerField() {
        assertThat(noHtmlViolatedFields(validator.validate(
                req("<x>", "d", "m", "c", "11111")))).contains("source");
        assertThat(noHtmlViolatedFields(validator.validate(
                req("POS", "<svg/onload=1>", "m", "c", "11111")))).contains("description");
        assertThat(noHtmlViolatedFields(validator.validate(
                req("POS", "d", "<script>x</script>", "c", "11111")))).contains("merchantName");
        assertThat(noHtmlViolatedFields(validator.validate(
                req("POS", "d", "m", "ci<ty", "11111")))).contains("merchantCity");
        assertThat(noHtmlViolatedFields(validator.validate(
                req("POS", "d", "m", "c", "1234>")))).contains("merchantZip");
    }

    // ---- Wiring onto admin user request DTOs (firstName / lastName) -------------------------

    @Test
    @DisplayName("UserAddRequest: clean names pass; markup in firstName/lastName is rejected on that field")
    void userAddRequestNoHtmlWiring() {
        // Clean names (incl. Unicode) raise no NoHtml violation.
        assertThat(noHtmlFieldsOf(validator.validate(
                new UserAddRequest("Jos\u00e9", "M\u00fcller", "USR00001", "Passw0rd", UserType.USER))))
                .isEmpty();
        // <b>x</b> in firstName -> NoHtml violation on firstName.
        assertThat(noHtmlFieldsOf(validator.validate(
                new UserAddRequest("<b>x</b>", "Smith", "USR00001", "Passw0rd", UserType.USER))))
                .contains("firstName");
        // <i>y</i> in lastName -> NoHtml violation on lastName.
        assertThat(noHtmlFieldsOf(validator.validate(
                new UserAddRequest("Jane", "<i>y</i>", "USR00001", "Passw0rd", UserType.USER))))
                .contains("lastName");
    }

    @Test
    @DisplayName("UserUpdateRequest: clean names pass; markup in firstName/lastName is rejected on that field")
    void userUpdateRequestNoHtmlWiring() {
        assertThat(noHtmlFieldsOf(validator.validate(
                new UserUpdateRequest("USR00001", "Jos\u00e9", "M\u00fcller", "Passw0rd", UserType.USER))))
                .isEmpty();
        assertThat(noHtmlFieldsOf(validator.validate(
                new UserUpdateRequest("USR00001", "<b>x</b>", "Smith", "Passw0rd", UserType.USER))))
                .contains("firstName");
        assertThat(noHtmlFieldsOf(validator.validate(
                new UserUpdateRequest("USR00001", "Jane", "<i>y</i>", "Passw0rd", UserType.USER))))
                .contains("lastName");
    }

    /** Field names that carry a {@link NoHtml} violation in the supplied violation set. */
    private static Set<String> noHtmlViolatedFields(
            Set<ConstraintViolation<TransactionAddRequest>> violations) {
        return violations.stream()
                .filter(v -> v.getConstraintDescriptor().getAnnotation() instanceof NoHtml)
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    /** Type-agnostic variant of {@link #noHtmlViolatedFields} for any validated bean. */
    private static Set<String> noHtmlFieldsOf(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .filter(v -> v.getConstraintDescriptor().getAnnotation() instanceof NoHtml)
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
