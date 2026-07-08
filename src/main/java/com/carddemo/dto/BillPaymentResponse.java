package com.carddemo.dto;

import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable response payload returned by the bill-payment (CB00) operation after
 * a full-balance payment has been applied to a credit-card account.
 *
 * <p>This DTO is the headless-REST replacement for the COBOL BMS screen
 * {@code COBIL00} ({@code app/cpy-bms/COBIL00.CPY}), whose display fields are
 * backed by the {@code ACCOUNT-RECORD} layout in {@code app/cpy/CVACT01Y.cpy}
 * (AAP &sect;0.5.1, program {@code COBIL00C}). It reports the balance that was
 * owed before the payment, the amount actually paid, the resulting balance, and
 * a confirmation of the generated payment transaction.
 *
 * <p><strong>Decimal fidelity.</strong> Every monetary component mirrors the
 * COBOL {@code PIC S9(10)V99} picture of {@code ACCT-CURR-BAL} and is therefore
 * normalized to {@link java.math.BigDecimal} <em>scale 2</em> using
 * {@link java.math.RoundingMode#HALF_UP} in the canonical constructor. Floating
 * point types are never used for monetary values (AAP &sect;0.8.2).
 *
 * <p>The type is stateless and immutable; neither {@code message} nor
 * {@code confirmationNumber} exposes any internal implementation detail.
 *
 * @param accountId          the 11-character account identifier that was paid,
 *                           echoed from the request ({@code ACTIDIN},
 *                           {@code PIC X(11)})
 * @param currentBalance     the account balance <em>before</em> the payment was
 *                           applied ({@code CURBAL} / {@code ACCT-CURR-BAL}),
 *                           held at scale 2
 * @param paymentAmount      the amount paid, equal to the full current balance,
 *                           held at scale 2
 * @param newBalance         the account balance <em>after</em> the payment
 *                           ({@code ACCT-CURR-BAL}), typically {@code 0.00},
 *                           held at scale 2
 * @param confirmationNumber the identifier of the generated payment transaction
 *                           (maximum 16 characters)
 * @param message            human-readable confirmation text for the caller
 */
public record BillPaymentResponse(

        @Size(max = 11) String accountId,

        BigDecimal currentBalance,

        BigDecimal paymentAmount,

        BigDecimal newBalance,

        @Size(max = 16) String confirmationNumber,

        String message) {

    /**
     * Scale mandated for every monetary field, matching the two decimal places of
     * the COBOL {@code PIC S9(10)V99} picture of {@code ACCT-CURR-BAL}.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * Canonical constructor that guarantees decimal fidelity by normalizing each
     * monetary component to {@link #MONEY_SCALE} decimal places using
     * {@link RoundingMode#HALF_UP}, reproducing COBOL fixed-point rounding. A
     * {@code null} monetary value is preserved as {@code null} so the record can
     * represent an unpopulated field without throwing.
     */
    public BillPaymentResponse {
        currentBalance = normalizeAmount(currentBalance);
        paymentAmount = normalizeAmount(paymentAmount);
        newBalance = normalizeAmount(newBalance);
    }

    /**
     * Normalizes a monetary amount to the mandated {@link #MONEY_SCALE} using
     * {@link RoundingMode#HALF_UP} rounding.
     *
     * @param amount the raw amount (may be {@code null})
     * @return the amount at the mandated scale, or {@code null} when the input was
     *         {@code null}
     */
    private static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount == null ? null : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
