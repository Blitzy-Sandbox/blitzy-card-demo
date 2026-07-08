package com.carddemo.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.Size;

/**
 * Immutable response returned by the transaction-add endpoint (transaction
 * {@code CT02}) after a new transaction has been persisted successfully.
 *
 * <p>This DTO is the Java 25 / Spring Boot translation of the confirmation that
 * the legacy CICS program {@code COTRN02C} rendered on the {@code COTRN02} BMS
 * map once an add succeeded. It conveys the newly assigned transaction
 * identifier together with a human-readable confirmation message so the caller
 * can display the outcome without a follow-up read.</p>
 *
 * <h2>COBOL source mapping</h2>
 * <ul>
 *   <li>{@code transactionId} &larr; {@code TRAN-ID PIC X(16)} of the
 *       {@code TRAN-RECORD} copybook {@code CVTRA05Y} (auto-generated at add
 *       time).</li>
 *   <li>{@code accountId} &larr; {@code ACTIDIN PIC X(11)} of the
 *       {@code COTRN02} symbolic map (echo of the target account).</li>
 *   <li>{@code amount} &larr; {@code TRAN-AMT PIC S9(09)V99} of
 *       {@code CVTRA05Y} (echo of the posted amount, fixed scale 2).</li>
 *   <li>{@code message} &larr; the confirmation text presented to the user.</li>
 * </ul>
 *
 * <h2>Decimal fidelity</h2>
 * <p>Per the migration's decimal-precision rules, the monetary
 * {@link #amount()} is represented with {@link java.math.BigDecimal} at a fixed
 * scale of {@value #AMOUNT_SCALE} (matching the COBOL {@code Vnn} picture); no
 * {@code float} or {@code double} is used for money. The compact constructor
 * normalizes any supplied value to that scale using
 * {@link java.math.RoundingMode#HALF_UP}, reproducing the COBOL rounding
 * behavior on assignment.</p>
 *
 * <h2>Security</h2>
 * <p>The card number (PAN, {@code TRAN-CARD-NUM}) is intentionally
 * <strong>not</strong> included in this response, and the confirmation
 * {@link #message()} exposes no sensitive internals, so the primary account
 * number is never leaked through the add-transaction confirmation.</p>
 *
 * <p>The type is a stateless, immutable {@code record} and is safe to share
 * across threads.</p>
 *
 * @param transactionId the newly assigned 16-character transaction identifier
 * @param accountId     the 11-character account identifier the transaction was
 *                      posted to
 * @param amount        the posted transaction amount, normalized to scale 2
 * @param message       a human-readable confirmation message
 */
public record TransactionAddResponse(

        @Size(max = 16) String transactionId,

        @Size(max = 11) String accountId,

        BigDecimal amount,

        String message) {

    /**
     * Fixed decimal scale for monetary amounts, matching the COBOL
     * {@code TRAN-AMT PIC S9(09)V99} picture (two fractional digits).
     */
    public static final int AMOUNT_SCALE = 2;

    /**
     * Canonical constructor that enforces the scale-2 invariant on
     * {@link #amount()}.
     *
     * <p>When a non-null {@code amount} is supplied it is rescaled to
     * {@value #AMOUNT_SCALE} fractional digits using
     * {@link java.math.RoundingMode#HALF_UP}, guaranteeing consistent decimal
     * fidelity regardless of the scale of the value passed in. A {@code null}
     * amount is preserved as {@code null}.</p>
     */
    public TransactionAddResponse {
        if (amount != null) {
            amount = amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        }
    }

    /**
     * Builds a response carrying the standard success confirmation message for
     * a newly added transaction.
     *
     * <p>The message follows the format
     * {@code "Transaction {id} added successfully"} and contains no sensitive
     * data. The supplied {@code amount} is normalized to scale
     * {@value #AMOUNT_SCALE} by the canonical constructor.</p>
     *
     * @param transactionId the newly assigned transaction identifier
     * @param accountId     the account identifier the transaction was posted to
     * @param amount        the posted transaction amount
     * @return a fully populated {@code TransactionAddResponse} with a standard
     *         confirmation message
     */
    public static TransactionAddResponse withConfirmation(String transactionId,
                                                          String accountId,
                                                          BigDecimal amount) {
        return new TransactionAddResponse(
                transactionId,
                accountId,
                amount,
                "Transaction " + transactionId + " added successfully");
    }
}
