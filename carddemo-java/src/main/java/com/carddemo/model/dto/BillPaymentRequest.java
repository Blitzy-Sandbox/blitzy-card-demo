package com.carddemo.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Immutable request payload for the bill-payment screen.
 *
 * <p>Accepted as the JSON body by {@code controller.BillingController} on
 * {@code POST /api/billing/pay} and consumed by
 * {@code service.billing.BillPaymentService} (the modernized equivalent of the
 * online program {@code COBIL00C}, which posts a full-balance payment against an
 * account). It mirrors only the <em>input</em> fields of the COBOL
 * {@code COBIL00} BMS symbolic map.
 *
 * <p>Per the migration contract, the following elements of the original map are
 * intentionally excluded from this request: the server-computed current balance
 * ({@code CURBAL}, a DISPLAY/output field returned on the response, never sent by
 * the client), the error message line ({@code ERRMSG}, response-only), and all
 * screen chrome, attribute bytes, and PF-key legends.
 *
 * <p>Both components are {@code String}, faithfully reflecting the source map's
 * fixed-width alphanumeric ({@code PIC X}) input fields. Field-edit semantics
 * carried by the COBOL screen handler (the {@code CSSETATY} attribute helper)
 * are expressed here as Jakarta Bean Validation constraints, which are enforced
 * by the controller via {@code @Valid}.
 *
 * <p>Lineage is preserved by reference to the source repository commit
 * {@code 27d6c6f}; the original COBOL is not copied into this project.
 *
 * @param accountId the 11-digit account identifier to pay
 *                  ({@code COBIL00} input field {@code ACTIDIN}, {@code PIC X(11)});
 *                  required, numeric, one to eleven digits
 * @param confirm   the single-character confirmation flag
 *                  ({@code COBIL00} input field {@code CONFIRM}, {@code PIC X(1)});
 *                  optional, at most one character
 */
public record BillPaymentRequest(
        @NotBlank
        @Pattern(regexp = "\\d{1,11}")
        @Size(max = 11)
        String accountId,

        @Size(max = 1)
        String confirm) {
}
