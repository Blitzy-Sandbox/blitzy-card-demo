package com.carddemo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for the Bill Payment transaction ({@code CB00} / {@code COBIL00C}).
 *
 * <p>Migrated from the input fields of the {@code COBIL00} BMS symbolic map
 * ({@code app/cpy-bms/COBIL00.CPY}). In the legacy pseudo-conversational flow the
 * user keys an account id, the screen echoes the account's current balance, and the
 * user confirms to pay the <em>full</em> current balance. Accordingly this request
 * carries only the two user-supplied inputs; the payment amount always equals the
 * full account balance and is computed server-side, so no money field is accepted
 * here.</p>
 *
 * <p>The {@code CURBAL} ({@code PIC X(14)}) field present on the screen is
 * display-only (the balance echoed back to the user) and therefore belongs on
 * {@code BillPaymentResponse}, not on this request.</p>
 *
 * <p>Because the target service is stateless (there is no CICS {@code COMMAREA}),
 * the {@code confirm} flag replaces the pseudo-conversational confirmation state
 * that the mainframe carried across screen interactions.</p>
 *
 * @param accountId the account identifier to pay, mirroring the legacy
 *                  {@code ACTIDIN} field ({@code PIC X(11)}); required, numeric,
 *                  and at most 11 digits
 * @param confirm   optional confirmation flag replacing the legacy {@code CONFIRM}
 *                  field ({@code PIC X(1)}, where {@code 'Y'} confirmed);
 *                  {@code true} authorizes paying the full current balance, while
 *                  {@code null} or {@code false} indicates the balance is only being
 *                  requested for display and payment is not yet authorized
 */
public record BillPaymentRequest(

        @NotBlank(message = "Account ID must be supplied")
        @Size(max = 11, message = "Account ID must be at most 11 characters")
        @Pattern(regexp = "\\d{1,11}", message = "Account ID must be 1 to 11 digits")
        String accountId,

        Boolean confirm

) {
}
