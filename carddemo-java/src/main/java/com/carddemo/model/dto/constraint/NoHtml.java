package com.carddemo.model.dto.constraint;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint that rejects values containing HTML/XML markup metacharacters
 * (the angle brackets {@code '<'} and {@code '>'}). It is applied to free-text fields whose
 * values are persisted and later echoed back through the JSON API, so an active markup payload
 * (for example {@code <script>...</script>} or {@code <svg/onload=...>}) can be neither stored
 * nor reflected &mdash; closing the stored-XSS vector reported in QA against the transaction-add
 * and admin-user text fields.
 *
 * <p><strong>Design notes.</strong> A {@code null} value is treated as valid so this constraint
 * never pre-empts {@code @NotNull}/{@code @NotBlank} presence ordering on the same component; a
 * blank or whitespace value is likewise accepted here (presence is enforced separately). Only the
 * two angle-bracket characters are rejected, which is sufficient to prevent any HTML element or
 * tag from being formed while leaving all other content untouched &mdash; Unicode letters, emoji,
 * punctuation, and SQL-style metacharacters (already rendered inert by parameterized JPA queries)
 * all remain acceptable, preserving the COBOL fixed-width alphanumeric behavior for legitimate
 * input. This is a security-hardening addition consistent with the migration's posture (cf. the
 * BCrypt upgrade of the formerly plaintext credential); the original 3270 green-screen had no
 * markup-rendering surface, so storing such characters verbatim was incidental, never a business
 * rule, and rejecting them introduces no behavioral regression for valid data.</p>
 */
@Documented
@Constraint(validatedBy = NoHtmlValidator.class)
@Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
@Retention(RUNTIME)
@Repeatable(NoHtml.List.class)
public @interface NoHtml {

    /** Validation message surfaced when markup characters are detected. */
    String message() default "must not contain HTML markup characters ('<' or '>')";

    /** Standard Bean Validation grouping attribute. */
    Class<?>[] groups() default {};

    /** Standard Bean Validation payload attribute. */
    Class<? extends Payload>[] payload() default {};

    /**
     * Container annotation enabling {@link NoHtml} to be declared more than once on the same
     * element, as required by the Bean Validation {@link Repeatable} contract.
     */
    @Target({METHOD, FIELD, ANNOTATION_TYPE, CONSTRUCTOR, PARAMETER, TYPE_USE})
    @Retention(RUNTIME)
    @Documented
    @interface List {
        NoHtml[] value();
    }
}
