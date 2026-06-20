package com.carddemo.model.dto;

import java.math.BigDecimal;

/**
 * Response DTO for the bill-payment screen.
 *
 * <p>Returned as JSON by {@code BillingController} for {@code POST /api/billing/pay}
 * and on the initial balance inquiry, and produced by {@code BillPaymentService}
 * (migrated from the {@code COBIL00C} online program). This is a plain, immutable,
 * JSON-serializable value carrier: it holds no behavior, no persistence concerns,
 * and no presentation chrome.</p>
 *
 * <p>It mirrors the output/display fields of the {@code COBIL00} BMS symbolic map
 * (bill-payment screen) of the AWS CardDemo mainframe application. Screen chrome
 * (transaction name, titles, date, time, program name) and PF-key legends are
 * intentionally excluded, as they have no place in a stateless REST contract.
 * Lineage is preserved by reference to the original COBOL source commit
 * {@code 27d6c6f}; no COBOL is copied here.</p>
 *
 * <p>Field mapping (COBOL symbolic-map field &rarr; record component):</p>
 * <ul>
 *   <li>{@code ACTIDIN} (PIC X(11)) &rarr; {@link #accountId()}</li>
 *   <li>{@code CURBAL} (PIC X(14); underlying account current balance
 *       PIC S9(10)V99) &rarr; {@link #currentBalance()}, represented as a
 *       {@link java.math.BigDecimal} with scale 2 so the fixed-point decimal
 *       precision of the COBOL field is preserved exactly (never
 *       {@code float}/{@code double})</li>
 *   <li>{@code CONFIRM} (PIC X(1)) &rarr; {@link #confirm()}</li>
 *   <li>{@code ERRMSG} (PIC X(78)) &rarr; {@link #errorMessage()}</li>
 * </ul>
 *
 * @param accountId      the account identifier echoed on the screen (up to 11 characters)
 * @param currentBalance the account current balance, as a {@link java.math.BigDecimal}
 *                       carrying scale 2; may be {@code null} on the initial inquiry
 *                       before an account has been resolved
 * @param confirm        the single-character payment confirmation flag (for example
 *                       {@code "Y"} or {@code "N"}); may be {@code null} when no
 *                       confirmation has yet been requested
 * @param errorMessage   the screen error or status message text (up to 78 characters);
 *                       {@code null} or empty when there is no message to display
 */
public record BillPaymentResponse(
        String accountId,
        BigDecimal currentBalance,
        String confirm,
        String errorMessage) {
}
