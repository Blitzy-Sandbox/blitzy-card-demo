package com.carddemo.model.dto.constraint;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ConstraintValidator} backing the {@link NoHtml} constraint. A value is rejected when it
 * contains either angle-bracket character ({@code '<'} or {@code '>'}), the minimal set required
 * to form any HTML/XML tag. {@code null} is accepted so the constraint composes cleanly with
 * presence constraints ({@code @NotNull}/{@code @NotBlank}) declared on the same element.
 */
public class NoHtmlValidator implements ConstraintValidator<NoHtml, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            // Presence is governed by @NotNull/@NotBlank; do not pre-empt their message ordering.
            return true;
        }
        // Rejecting just the angle brackets prevents any tag from being formed while leaving every
        // other character (Unicode letters, emoji, digits, punctuation) untouched.
        return value.indexOf('<') < 0 && value.indexOf('>') < 0;
    }
}
