/*
 * CreditLimitExceededException.java
 *
 * Exception thrown when a transaction amount exceeds the account's available
 * credit limit, mapping COBOL batch reject code 102 ('OVERLIMIT TRANSACTION')
 * from the daily transaction posting validation cascade in CBTRN02C.cbl.
 *
 * COBOL Traceability:
 * - CBTRN02C.cbl paragraph 1500-B-LOOKUP-ACCT (lines 393-422):
 *     COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                         - ACCT-CURR-CYC-DEBIT
 *                         + DALYTRAN-AMT
 *     IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
 *       CONTINUE
 *     ELSE
 *       MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *       MOVE 'OVERLIMIT TRANSACTION'
 *         TO WS-VALIDATION-FAIL-REASON-DESC
 *     END-IF
 *
 * - COBIL00C.cbl (Bill Payment online program): validates account balance
 *   before posting a bill payment transaction.
 *
 * Usage Context:
 * - Batch: Thrown by TransactionPostingProcessor during 4-stage validation
 *   cascade (stage 3: credit limit check). Caught by the batch step, logged
 *   with reject code 102, and the transaction is written to the rejection file.
 * - Online: Thrown by BillPaymentService when a payment would exceed the
 *   credit limit. Caught by @ControllerAdvice and mapped to HTTP 422
 *   (Unprocessable Entity).
 *
 * Design Decision (Decision Log D-EXCEPT-102):
 * All financial amount fields use BigDecimal (never float/double) to preserve
 * COBOL COMP-3 packed decimal precision per AAP §0.8.2. The COBOL fields
 * ACCT-CREDIT-LIMIT, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT, and
 * DALYTRAN-AMT are all PIC S9(n)V99 COMP-3 packed decimal fields.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

import java.math.BigDecimal;

/**
 * Exception thrown when a transaction amount causes the account balance to
 * exceed the credit limit.
 *
 * <p>This exception maps directly to COBOL batch reject code {@code 102}
 * ('OVERLIMIT TRANSACTION') from the daily transaction posting validation
 * cascade in {@code CBTRN02C.cbl}. The COBOL validation computes:</p>
 *
 * <pre>
 * WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
 * IF ACCT-CREDIT-LIMIT &lt; WS-TEMP-BAL → reject code 102
 * </pre>
 *
 * <p>In the Java migration, this translates to: if the projected balance
 * (current cycle credits minus debits plus the new transaction amount) exceeds
 * the account's credit limit, this exception is thrown.</p>
 *
 * <h3>Usage Contexts</h3>
 * <ul>
 *   <li><strong>Batch processing</strong> — {@code TransactionPostingProcessor}
 *       catches this exception during the 4-stage validation cascade, logs
 *       reject code 102, and writes the transaction to the rejection file
 *       with an 'OVERLIMIT TRANSACTION' trailer.</li>
 *   <li><strong>Online processing</strong> — {@code BillPaymentService} throws
 *       this when a bill payment would push the account over its credit limit.
 *       The {@code @ControllerAdvice} handler maps it to HTTP 422
 *       (Unprocessable Entity).</li>
 * </ul>
 *
 * <h3>Financial Precision</h3>
 * <p>All monetary fields ({@code transactionAmount}, {@code creditLimit},
 * {@code currentBalance}) use {@link BigDecimal} to preserve COBOL COMP-3
 * packed decimal precision. The use of {@code float}, {@code double},
 * {@code Float}, or {@code Double} is prohibited for financial fields
 * per AAP §0.8.2.</p>
 *
 * @see CardDemoException
 * @see java.math.BigDecimal
 */
public class CreditLimitExceededException extends CardDemoException {

    /**
     * Serial version UID for serialization compatibility.
     * Maintains consistency with the {@link CardDemoException} serialization chain.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL batch reject code for overlimit transactions.
     *
     * <p>Maps to {@code MOVE 102 TO WS-VALIDATION-FAIL-REASON} in
     * {@code CBTRN02C.cbl} paragraph {@code 1500-B-LOOKUP-ACCT}.
     * The COBOL working storage field {@code WS-VALIDATION-FAIL-REASON}
     * is defined as {@code PIC 9(04)}, representing a 4-digit numeric code.</p>
     *
     * <p>This constant is used by batch processors to identify the rejection
     * reason when writing to the daily rejection file (DALYREJS).</p>
     */
    public static final int REJECT_CODE = 102;

    /**
     * The application-level error code used in the {@link CardDemoException}
     * hierarchy for credit limit exceeded conditions.
     *
     * <p>Used in structured logging, API error responses, and
     * {@code @ControllerAdvice} exception handling to categorize the error type.</p>
     */
    private static final String ERROR_CODE = "CREDIT";

    /**
     * The account identifier that exceeded the credit limit.
     *
     * <p>Corresponds to COBOL field {@code XREF-ACCT-ID} resolved from the
     * card cross-reference lookup in paragraph {@code 1500-A-LOOKUP-XREF},
     * which is then used as {@code FD-ACCT-ID} in paragraph
     * {@code 1500-B-LOOKUP-ACCT}.</p>
     *
     * <p>May be {@code null} when the exception is constructed with only a
     * message string (simple constructor).</p>
     */
    private final String accountId;

    /**
     * The transaction amount that caused the credit limit to be exceeded.
     *
     * <p>Corresponds to COBOL field {@code DALYTRAN-AMT} (PIC S9(n)V99 COMP-3),
     * the amount from the daily transaction file being posted.</p>
     *
     * <p>Uses {@link BigDecimal} to preserve COMP-3 packed decimal precision.
     * May be {@code null} when the exception is constructed with only a message
     * string (simple constructor).</p>
     */
    private final BigDecimal transactionAmount;

    /**
     * The account's credit limit that was exceeded.
     *
     * <p>Corresponds to COBOL field {@code ACCT-CREDIT-LIMIT}
     * (PIC S9(n)V99 COMP-3) from the account record read in paragraph
     * {@code 1500-B-LOOKUP-ACCT}.</p>
     *
     * <p>Uses {@link BigDecimal} to preserve COMP-3 packed decimal precision.
     * May be {@code null} when the exception is constructed with only a message
     * string (simple constructor).</p>
     */
    private final BigDecimal creditLimit;

    /**
     * The current cycle balance at the time the overlimit condition was detected.
     *
     * <p>Corresponds to the COBOL computed value {@code WS-TEMP-BAL}
     * (PIC S9(09)V99), calculated as:</p>
     * <pre>
     * WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
     * </pre>
     *
     * <p>This represents the projected balance after applying the transaction.
     * Uses {@link BigDecimal} to preserve COMP-3 packed decimal precision.
     * May be {@code null} when the exception is constructed with only a message
     * string (simple constructor).</p>
     */
    private final BigDecimal currentBalance;

    /**
     * Constructs a new {@code CreditLimitExceededException} with the specified
     * detail message.
     *
     * <p>This constructor creates the exception without financial context fields.
     * The {@code accountId}, {@code transactionAmount}, {@code creditLimit}, and
     * {@code currentBalance} fields will be {@code null}. Suitable for cases
     * where only a descriptive message is available, such as re-throwing from
     * a generic error handler.</p>
     *
     * @param message the detail message describing the overlimit condition
     */
    public CreditLimitExceededException(String message) {
        super(message);
        this.accountId = null;
        this.transactionAmount = null;
        this.creditLimit = null;
        this.currentBalance = null;
    }

    /**
     * Constructs a new {@code CreditLimitExceededException} with full financial
     * context from the COBOL validation cascade.
     *
     * <p>This is the primary constructor used by {@code TransactionPostingProcessor}
     * and {@code BillPaymentService} when the credit limit check fails. It captures
     * all the financial data needed for reject file writing (batch) and API error
     * responses (online).</p>
     *
     * <p>The generated message includes all financial details for structured logging
     * and error reporting, formatted as:</p>
     * <pre>
     * Credit limit exceeded for account {accountId}: transaction amount {amount}
     * would result in balance {balance} exceeding credit limit {limit}
     * (reject code 102)
     * </pre>
     *
     * @param accountId         the account identifier that exceeded the limit
     *                          (COBOL: XREF-ACCT-ID → FD-ACCT-ID)
     * @param transactionAmount the transaction amount causing the overlimit
     *                          (COBOL: DALYTRAN-AMT, COMP-3 packed decimal);
     *                          must be {@link BigDecimal}, never float/double
     * @param creditLimit       the account's credit limit
     *                          (COBOL: ACCT-CREDIT-LIMIT, COMP-3 packed decimal);
     *                          must be {@link BigDecimal}, never float/double
     * @param currentBalance    the projected balance after applying the transaction
     *                          (COBOL: WS-TEMP-BAL = credits - debits + amount);
     *                          must be {@link BigDecimal}, never float/double
     */
    public CreditLimitExceededException(String accountId,
                                         BigDecimal transactionAmount,
                                         BigDecimal creditLimit,
                                         BigDecimal currentBalance) {
        super(buildMessage(accountId, transactionAmount, creditLimit, currentBalance),
              ERROR_CODE);
        this.accountId = accountId;
        this.transactionAmount = transactionAmount;
        this.creditLimit = creditLimit;
        this.currentBalance = currentBalance;
    }

    /**
     * Returns the account identifier that exceeded the credit limit.
     *
     * <p>This is the account resolved from the card cross-reference lookup
     * (COBOL: XREF-ACCT-ID from paragraph 1500-A-LOOKUP-XREF).</p>
     *
     * @return the account ID, or {@code null} if constructed with only a message
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the transaction amount that caused the credit limit to be exceeded.
     *
     * <p>Corresponds to COBOL field DALYTRAN-AMT (COMP-3 packed decimal).
     * This value is always a {@link BigDecimal} to preserve precision per
     * AAP §0.8.2 zero floating-point substitution requirement.</p>
     *
     * @return the transaction amount as {@link BigDecimal}, or {@code null}
     *         if constructed with only a message
     */
    public BigDecimal getTransactionAmount() {
        return transactionAmount;
    }

    /**
     * Returns the account's credit limit that was exceeded.
     *
     * <p>Corresponds to COBOL field ACCT-CREDIT-LIMIT (COMP-3 packed decimal).
     * This value is always a {@link BigDecimal} to preserve precision per
     * AAP §0.8.2 zero floating-point substitution requirement.</p>
     *
     * @return the credit limit as {@link BigDecimal}, or {@code null}
     *         if constructed with only a message
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * Returns the current cycle balance at the time of the overlimit detection.
     *
     * <p>This corresponds to the COBOL computed value WS-TEMP-BAL calculated as:
     * {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}.
     * This value is always a {@link BigDecimal} to preserve precision per
     * AAP §0.8.2 zero floating-point substitution requirement.</p>
     *
     * @return the projected balance as {@link BigDecimal}, or {@code null}
     *         if constructed with only a message
     */
    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    /**
     * Builds a descriptive error message containing all financial context from
     * the overlimit validation.
     *
     * <p>The message format provides all the information needed for both
     * structured logging in batch processing and API error responses in
     * online processing. The reject code 102 is included for traceability
     * to the original COBOL validation cascade.</p>
     *
     * @param accountId         the account identifier
     * @param transactionAmount the transaction amount causing the overlimit
     * @param creditLimit       the account's credit limit
     * @param currentBalance    the projected balance after the transaction
     * @return a formatted error message string
     */
    private static String buildMessage(String accountId,
                                        BigDecimal transactionAmount,
                                        BigDecimal creditLimit,
                                        BigDecimal currentBalance) {
        return String.format(
                "Credit limit exceeded for account %s: transaction amount %s " +
                "would result in balance %s exceeding credit limit %s " +
                "(reject code %d)",
                accountId,
                transactionAmount != null ? transactionAmount.toPlainString() : "null",
                currentBalance != null ? currentBalance.toPlainString() : "null",
                creditLimit != null ? creditLimit.toPlainString() : "null",
                REJECT_CODE);
    }
}
