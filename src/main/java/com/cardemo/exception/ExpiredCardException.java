/*
 * ExpiredCardException.java
 *
 * Exception representing COBOL batch reject code 103: 'TRANSACTION RECEIVED
 * AFTER ACCT EXPIRATION'. Thrown when a transaction is attempted on an account
 * whose expiration date has passed.
 *
 * COBOL Traceability:
 * - CBTRN02C.cbl lines 414-420 (batch daily transaction posting, validation
 *   stage 4 of the 4-stage cascade):
 *     IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
 *       CONTINUE
 *     ELSE
 *       MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *       MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *         TO WS-VALIDATION-FAIL-REASON-DESC
 *     END-IF
 *
 * - COTRN02C.cbl (online transaction add) — account expiration is validated
 *   prior to adding a new transaction via the interactive CICS program.
 *
 * Note: The COBOL source contains a typo "ACCT-EXPIRAION-DATE" (missing 'T').
 * The Java implementation uses the correct spelling "expirationDate".
 *
 * Design Decision (Decision Log D-EXCEPT-103):
 * Uses java.time.LocalDate for all date fields instead of COBOL string
 * comparisons (ACCT-EXPIRAION-DATE vs DALYTRAN-ORIG-TS(1:10)), per AAP §0.8.3
 * requiring LE CEEDAYS date validation to be ported to java.time.LocalDate.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

import java.time.LocalDate;

/**
 * Exception thrown when a transaction is attempted on an account or card whose
 * expiration date has already passed.
 *
 * <p>This exception maps directly to COBOL batch reject code {@code 103}
 * ('TRANSACTION RECEIVED AFTER ACCT EXPIRATION') from the daily transaction
 * posting validation cascade in {@code CBTRN02C.cbl} (lines 414-420). The
 * COBOL logic compares {@code ACCT-EXPIRAION-DATE} against the first 10
 * characters of {@code DALYTRAN-ORIG-TS} (the transaction date portion). If
 * the account expiration date precedes the transaction date, the transaction
 * is rejected with code 103.</p>
 *
 * <h3>Usage Contexts</h3>
 * <ul>
 *   <li><strong>Batch context</strong> — Thrown by
 *       {@code TransactionPostingProcessor} during the 4-stage validation
 *       cascade (stage 4: expiration check). Caught by the batch step
 *       framework, logged with reject code 103, and the transaction is
 *       written to the rejection file with a validation trailer.</li>
 *   <li><strong>Online context</strong> — Thrown by
 *       {@code TransactionAddService} when a user attempts to add a
 *       transaction on an expired account via the REST API. Caught by
 *       {@code @ControllerAdvice} and mapped to HTTP 422 (Unprocessable
 *       Entity) with an expiration error message.</li>
 * </ul>
 *
 * <h3>COBOL Validation Cascade Order (CBTRN02C.cbl)</h3>
 * <ol>
 *   <li>Stage 1 — Card number cross-reference lookup (reject code 100)</li>
 *   <li>Stage 2 — Account record not found (reject code 101)</li>
 *   <li>Stage 3 — Credit limit exceeded (reject code 102)</li>
 *   <li>Stage 4 — Account expiration date check (reject code 103) — this exception</li>
 * </ol>
 *
 * @see CardDemoException
 * @see java.time.LocalDate
 */
public class ExpiredCardException extends CardDemoException {

    /**
     * Serial version UID for serialization compatibility.
     * Follows the same pattern as the base {@link CardDemoException}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL batch reject code for expired account transactions.
     *
     * <p>Corresponds to:</p>
     * <pre>
     * MOVE 103 TO WS-VALIDATION-FAIL-REASON
     * </pre>
     * <p>in {@code CBTRN02C.cbl} line 417. Used by batch processors to
     * categorize rejection reasons and by the rejection file writer to
     * produce validation trailers.</p>
     */
    public static final int REJECT_CODE = 103;

    /**
     * Application-level error code used when constructing this exception.
     * Passed to the base {@link CardDemoException} constructor for uniform
     * error categorization in API responses and structured logging.
     */
    private static final String ERROR_CODE = "EXPIRY";

    /**
     * The card number associated with the expired account transaction.
     *
     * <p>Corresponds to the COBOL field {@code DALYTRAN-CARD-NUM} in the
     * daily transaction record. May be {@code null} when the exception is
     * thrown in contexts where the card number is not yet resolved (e.g.,
     * when only an account ID is known).</p>
     */
    private final String cardNumber;

    /**
     * The account identifier whose expiration date has passed.
     *
     * <p>Corresponds to the COBOL field {@code XREF-ACCT-ID} (resolved from
     * the card cross-reference) or the direct account ID from the
     * transaction record. This is the account that was found to have an
     * expiration date prior to the transaction date.</p>
     */
    private final String accountId;

    /**
     * The expiration date of the account or card.
     *
     * <p>Corresponds to the COBOL field {@code ACCT-EXPIRAION-DATE} (note:
     * the COBOL source contains a typo "EXPIRAION" — the Java field uses
     * the correct spelling). In the COBOL source, this is a string-based
     * date comparison; in Java, it is represented as a {@link LocalDate}
     * per AAP §0.8.3 requirement to port LE CEEDAYS date validation to
     * {@code java.time.LocalDate}.</p>
     */
    private final LocalDate expirationDate;

    /**
     * The transaction date that triggered the expiration check failure.
     *
     * <p>Corresponds to the COBOL expression {@code DALYTRAN-ORIG-TS(1:10)},
     * which extracts the first 10 characters (the date portion) of the
     * daily transaction original timestamp. The transaction is rejected
     * because this date is after the account's expiration date.</p>
     */
    private final LocalDate transactionDate;

    /**
     * Constructs a new {@code ExpiredCardException} with the specified
     * detail message.
     *
     * <p>This is the simplest constructor, used when only a descriptive
     * message is available without structured date or account fields.
     * The error code is set to the default ({@link CardDemoException#DEFAULT_ERROR_CODE})
     * and all context fields (cardNumber, accountId, expirationDate,
     * transactionDate) are set to {@code null}.</p>
     *
     * @param message the detail message describing the expiration condition
     */
    public ExpiredCardException(String message) {
        super(message);
        this.cardNumber = null;
        this.accountId = null;
        this.expirationDate = null;
        this.transactionDate = null;
    }

    /**
     * Constructs a new {@code ExpiredCardException} with the account ID,
     * expiration date, and transaction date.
     *
     * <p>Automatically constructs a descriptive message including the account
     * ID, expiration date, and transaction date. The error code is set to
     * "EXPIRY" for uniform categorization. The card number is set to
     * {@code null} — use the full constructor
     * {@link #ExpiredCardException(String, String, LocalDate, LocalDate)}
     * when the card number is known.</p>
     *
     * <p>This constructor is commonly used in the batch context
     * ({@code TransactionPostingProcessor}) where the account expiration is
     * detected after the account record is read but the card-level detail
     * is not always propagated.</p>
     *
     * @param accountId       the account identifier whose expiration has
     *                        passed; must not be {@code null}
     * @param expirationDate  the account/card expiration date; must not be
     *                        {@code null}
     * @param transactionDate the transaction date that triggered the check;
     *                        must not be {@code null}
     */
    public ExpiredCardException(String accountId, LocalDate expirationDate,
                                LocalDate transactionDate) {
        super(buildMessage(null, accountId, expirationDate, transactionDate),
              ERROR_CODE);
        this.cardNumber = null;
        this.accountId = accountId;
        this.expirationDate = expirationDate;
        this.transactionDate = transactionDate;
    }

    /**
     * Constructs a new {@code ExpiredCardException} with the card number,
     * account ID, expiration date, and transaction date.
     *
     * <p>This is the most complete constructor, providing full context for
     * error reporting, structured logging, and rejection file generation.
     * The error code is set to "EXPIRY" for uniform categorization.</p>
     *
     * <p>This constructor is commonly used in the online context
     * ({@code TransactionAddService}) where both the card number and
     * account ID are available from the cross-reference lookup.</p>
     *
     * @param cardNumber      the card number associated with the expired
     *                        account; may be {@code null} if unknown
     * @param accountId       the account identifier whose expiration has
     *                        passed; must not be {@code null}
     * @param expirationDate  the account/card expiration date; must not be
     *                        {@code null}
     * @param transactionDate the transaction date that triggered the check;
     *                        must not be {@code null}
     */
    public ExpiredCardException(String cardNumber, String accountId,
                                LocalDate expirationDate,
                                LocalDate transactionDate) {
        super(buildMessage(cardNumber, accountId, expirationDate,
              transactionDate), ERROR_CODE);
        this.cardNumber = cardNumber;
        this.accountId = accountId;
        this.expirationDate = expirationDate;
        this.transactionDate = transactionDate;
    }

    /**
     * Returns the card number associated with the expired account transaction.
     *
     * <p>May return {@code null} if the exception was constructed without a
     * card number (e.g., when only the account ID was known at the time of
     * the expiration check).</p>
     *
     * @return the card number, or {@code null} if not available
     */
    public String getCardNumber() {
        return cardNumber;
    }

    /**
     * Returns the account identifier whose expiration date has passed.
     *
     * <p>This is the account that was found to have an expiration date
     * (corresponding to COBOL {@code ACCT-EXPIRAION-DATE}) prior to the
     * transaction date.</p>
     *
     * @return the account ID, or {@code null} if constructed with only a
     *         message string
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the expiration date of the account or card.
     *
     * <p>Corresponds to the COBOL field {@code ACCT-EXPIRAION-DATE}. In the
     * original COBOL logic, this date was compared as a string against the
     * transaction timestamp; in Java, it is a proper {@link LocalDate}
     * instance for type-safe date comparisons.</p>
     *
     * @return the expiration date, or {@code null} if constructed with only
     *         a message string
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * Returns the transaction date that triggered the expiration check failure.
     *
     * <p>Corresponds to the COBOL expression {@code DALYTRAN-ORIG-TS(1:10)},
     * which extracts the date portion of the daily transaction original
     * timestamp. This date is after the account's expiration date, causing
     * reject code 103.</p>
     *
     * @return the transaction date, or {@code null} if constructed with only
     *         a message string
     */
    public LocalDate getTransactionDate() {
        return transactionDate;
    }

    /**
     * Builds a descriptive error message from the provided context fields.
     *
     * <p>Produces a message consistent with the COBOL reject reason
     * description format while including all relevant context for
     * structured logging and API error responses.</p>
     *
     * @param cardNumber      the card number (may be {@code null})
     * @param accountId       the account identifier
     * @param expirationDate  the account/card expiration date
     * @param transactionDate the transaction date
     * @return a formatted error message
     */
    private static String buildMessage(String cardNumber, String accountId,
                                       LocalDate expirationDate,
                                       LocalDate transactionDate) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Account ").append(accountId)
          .append(" expired on ").append(expirationDate)
          .append(", transaction date ").append(transactionDate);
        if (cardNumber != null && !cardNumber.isEmpty()) {
            sb.append(" (card ").append(cardNumber).append(')');
        }
        sb.append(" [reject code ").append(REJECT_CODE).append(']');
        return sb.toString();
    }
}
