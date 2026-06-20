package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable request payload for the bill-payment endpoint {@code POST /api/billing/pay}.
 *
 * <p>This DTO mirrors the <em>input</em> fields of the {@code COBIL00} BMS symbolic map
 * (source commit {@code 27d6c6f}) that drove the original CICS bill-payment screen handled by
 * {@code COBIL00C}. It is bound from the JSON request body by {@code BillingController} and
 * consumed by {@code BillPaymentService}, which posts the payment against the account balance.</p>
 *
 * <p>Only the two operator-supplied input fields are carried here. The server-computed
 * current-balance field ({@code CURBAL}), the screen error-message field ({@code ERRMSG}),
 * and all screen chrome / PF-key / attribute fields are intentionally excluded: they are either
 * presentation concerns or response-only values returned by the corresponding response DTO.</p>
 *
 * <p>Field-edit semantics that the COBOL map enforced via screen attributes (see {@code CSSETATY})
 * are expressed declaratively as Jakarta Bean Validation constraints, allowing the controller to
 * reject malformed requests via {@code @Valid} before any business logic executes.</p>
 *
 * @param accountId the 11-digit account identifier to which the payment is applied
 *                  (from {@code ACTIDIN}, {@code PIC X(11)}); required and strictly numeric
 * @param confirm   the single-character confirmation flag captured from the screen
 *                  (from {@code CONFIRM}, {@code PIC X(1)}); optional, at most one character
 */
public record BillPaymentRequest(

        @NotBlank
        @Pattern(regexp = "\\d{1,11}")
        @Size(max = 11)
        String accountId,

        @Size(max = 1)
        String confirm) {
}
