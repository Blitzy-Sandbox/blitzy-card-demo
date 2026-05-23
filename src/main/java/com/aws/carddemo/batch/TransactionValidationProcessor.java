/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.batch;

import com.aws.carddemo.entity.Transaction;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBTRN01C.cbl} — the
 * daily-transaction validation processor.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBTRN01C.cbl} reads each daily transaction record
 * and validates it against the master files (XREFFILE for card-number
 * lookup, ACCOUNT-FILE for account lookup). Validation rejects are
 * routed to the DALYREJS reject file; valid transactions flow through
 * to the posting step (CBTRN02C).
 *
 * <p>The validation cascade is encoded by the COBOL paragraph
 * {@code 1500-VALIDATE-TRAN} which calls (in order):
 * <ol>
 *   <li>{@code 1500-A-LOOKUP-XREF} — verifies the card number exists
 *       in the cross-reference file; on miss, sets
 *       {@code WS-VALIDATION-FAIL-REASON = 100} ("INVALID CARD NUMBER FOUND").</li>
 *   <li>{@code 1500-B-LOOKUP-ACCT} — verifies the account exists in
 *       the account master; on miss, sets reason code 101 ("ACCOUNT
 *       RECORD NOT FOUND"). Also computes the cycle balance + new
 *       transaction amount and rejects with code 102 if it exceeds
 *       the credit limit ("OVERLIMIT TRANSACTION"). Finally checks
 *       account expiration with code 103 ("TRANSACTION RECEIVED
 *       AFTER ACCT EXPIRATION").</li>
 * </ol>
 *
 * <p>Each stage short-circuits subsequent stages — if the first stage
 * fails, the second never runs. This is the COBOL idiom
 * {@code IF WS-VALIDATION-FAIL-REASON = 0 THEN ... ELSE CONTINUE}.
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>This class exposes the validation contract as pure functions
 * without coupling to the file-read or reject-write infrastructure.
 * Concrete I/O (XREFFILE reads, ACCOUNT-FILE reads, DALYREJS writes)
 * is the responsibility of the Spring Batch step orchestration that
 * wraps this processor in an {@code ItemProcessor<Transaction,
 * Transaction>} with an {@code ItemWriter<RejectRecord>} side path.
 *
 * <p>The unit-test contract is:
 * <ul>
 *   <li>{@link #validateBasic(Transaction)} — input shape validation
 *       (null/empty fields, numeric ranges); applies BEFORE the
 *       cross-reference/account lookups. This is the Java equivalent
 *       of the COBOL "is the record well-formed?" entry guard.</li>
 *   <li>{@link #rejectCodeFor(boolean, boolean, boolean, boolean)} —
 *       maps a 4-tuple of validation-stage outcomes (cardFound,
 *       accountFound, withinCreditLimit, notExpired) into a single
 *       reject-code result. Encodes the COBOL short-circuit cascade.</li>
 *   <li>{@link #rejectDescriptionFor(int)} — maps a reject code to
 *       the COBOL-equivalent description string (for inclusion in the
 *       DALYREJS file's trailer).</li>
 * </ul>
 *
 * @see com.aws.carddemo.batch.TransactionValidationProcessorTest
 */
public class TransactionValidationProcessor {

    /** Sentinel value indicating the transaction passed all validation stages. */
    public static final int REASON_OK = 0;

    /** Reject code: card number not found in CARDXREF. */
    public static final int REASON_INVALID_CARD = 100;

    /** Reject code: account not found in ACCTFILE for the XREF-resolved account ID. */
    public static final int REASON_ACCOUNT_NOT_FOUND = 101;

    /** Reject code: cycle balance + transaction amount exceeds credit limit. */
    public static final int REASON_OVERLIMIT = 102;

    /** Reject code: account is expired (transaction date >= account expiration date). */
    public static final int REASON_ACCOUNT_EXPIRED = 103;

    /** Description for {@link #REASON_INVALID_CARD}. */
    public static final String DESC_INVALID_CARD = "INVALID CARD NUMBER FOUND";

    /** Description for {@link #REASON_ACCOUNT_NOT_FOUND}. */
    public static final String DESC_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /** Description for {@link #REASON_OVERLIMIT}. */
    public static final String DESC_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /** Description for {@link #REASON_ACCOUNT_EXPIRED}. */
    public static final String DESC_ACCOUNT_EXPIRED = "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /** Constructs a new processor. No collaborators. */
    public TransactionValidationProcessor() {
        // No collaborators.
    }

    /**
     * Validate the basic shape of a daily transaction record before
     * the cross-reference / account-master lookups.
     *
     * <p>Checks:
     * <ul>
     *   <li>Card number is non-null and non-blank.</li>
     *   <li>Amount is non-null.</li>
     *   <li>Transaction type code is non-null and non-blank.</li>
     *   <li>Transaction category code is non-null and non-blank.</li>
     * </ul>
     *
     * <p>A failure on any of these triggers REASON_INVALID_CARD as the
     * earliest matching COBOL reject; well-formed input returns
     * {@link #REASON_OK}.
     *
     * @param transaction the transaction to validate; if null, returns REASON_INVALID_CARD
     * @return one of {@link #REASON_OK} or {@link #REASON_INVALID_CARD}
     */
    public int validateBasic(Transaction transaction) {
        if (transaction == null) {
            return REASON_INVALID_CARD;
        }
        String cardNum = transaction.getCardNumber();
        if (cardNum == null || cardNum.isBlank()) {
            return REASON_INVALID_CARD;
        }
        if (transaction.getAmount() == null) {
            return REASON_INVALID_CARD;
        }
        String typeCode = transaction.getTransactionTypeCode();
        if (typeCode == null || typeCode.isBlank()) {
            return REASON_INVALID_CARD;
        }
        String catCode = transaction.getTransactionCategoryCode();
        if (catCode == null || catCode.isBlank()) {
            return REASON_INVALID_CARD;
        }
        return REASON_OK;
    }

    /**
     * Map the 4-tuple of validation-stage outcomes to a reject code,
     * encoding the COBOL short-circuit cascade in
     * {@code 1500-VALIDATE-TRAN}.
     *
     * <p>The cascade order is fixed by the COBOL paragraph order:
     * <ol>
     *   <li>{@code cardFound == false} → {@link #REASON_INVALID_CARD}
     *       (no subsequent stages run).</li>
     *   <li>{@code accountFound == false} → {@link #REASON_ACCOUNT_NOT_FOUND}
     *       (overlimit and expiration checks do not run).</li>
     *   <li>{@code withinCreditLimit == false} → {@link #REASON_OVERLIMIT}
     *       (expiration check still runs, but a later expiration failure
     *       does NOT override the overlimit code in the COBOL — the
     *       overlimit code wins because it's set first; that said, the
     *       COBOL does check expiration after overlimit, which COULD
     *       overwrite the reason. We preserve the COBOL behaviour
     *       precisely below).</li>
     *   <li>{@code notExpired == false} → {@link #REASON_ACCOUNT_EXPIRED}.</li>
     * </ol>
     *
     * <p>Note on overlimit-vs-expired ordering: per the COBOL source
     * lines 410-419, both the overlimit and expiration checks run
     * unconditionally when the account is found; the LAST failed check
     * wins. The Java implementation mirrors this exactly: expiration
     * takes precedence over overlimit when both fail, because the
     * COBOL's IF-ELSE for expiration follows the IF-ELSE for overlimit
     * and overwrites the WS-VALIDATION-FAIL-REASON value.
     *
     * @param cardFound          whether {@code XREF-FILE READ} succeeded
     * @param accountFound       whether {@code ACCOUNT-FILE READ} succeeded
     * @param withinCreditLimit  whether (cycle + amount) <= credit limit
     * @param notExpired         whether account expiration >= transaction date
     * @return one of {@link #REASON_OK}, {@link #REASON_INVALID_CARD},
     *         {@link #REASON_ACCOUNT_NOT_FOUND}, {@link #REASON_OVERLIMIT},
     *         or {@link #REASON_ACCOUNT_EXPIRED}
     */
    public int rejectCodeFor(boolean cardFound, boolean accountFound,
                             boolean withinCreditLimit, boolean notExpired) {
        if (!cardFound) {
            return REASON_INVALID_CARD;
        }
        if (!accountFound) {
            return REASON_ACCOUNT_NOT_FOUND;
        }
        // Both overlimit and expiration checks run when account is found.
        // The COBOL overwrites the reason if the second check fails,
        // so expiration takes precedence in the ordering when both fail.
        if (!notExpired) {
            return REASON_ACCOUNT_EXPIRED;
        }
        if (!withinCreditLimit) {
            return REASON_OVERLIMIT;
        }
        return REASON_OK;
    }

    /**
     * Map a reject code to the COBOL-equivalent description string.
     *
     * <p>The descriptions come verbatim from the COBOL source lines
     * 386, 398, 411, 418 of {@code CBTRN01C.cbl}.
     *
     * @param rejectCode one of the REASON_* constants
     * @return the description, or {@code "UNKNOWN"} for unrecognised codes
     */
    public String rejectDescriptionFor(int rejectCode) {
        return switch (rejectCode) {
            case REASON_OK -> "";
            case REASON_INVALID_CARD -> DESC_INVALID_CARD;
            case REASON_ACCOUNT_NOT_FOUND -> DESC_ACCOUNT_NOT_FOUND;
            case REASON_OVERLIMIT -> DESC_OVERLIMIT;
            case REASON_ACCOUNT_EXPIRED -> DESC_ACCOUNT_EXPIRED;
            default -> "UNKNOWN";
        };
    }

    /**
     * Compute the projected cycle balance after applying a new transaction
     * amount — the COBOL equivalent of
     * {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}.
     *
     * <p>Pure arithmetic; no side effects. Preserves scale 2 throughout.
     *
     * @param cycleCredit current cycle credit (PIC S9(10)V99); must not be null
     * @param cycleDebit  current cycle debit (PIC S9(10)V99); must not be null
     * @param amount      the new transaction amount (PIC S9(09)V99); must not be null
     * @return the projected balance at scale 2
     */
    public BigDecimal projectedBalance(BigDecimal cycleCredit,
                                       BigDecimal cycleDebit,
                                       BigDecimal amount) {
        Objects.requireNonNull(cycleCredit, "cycleCredit must not be null");
        Objects.requireNonNull(cycleDebit, "cycleDebit must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        return cycleCredit.subtract(cycleDebit).add(amount)
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
    }
}
