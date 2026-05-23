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

import com.aws.carddemo.entity.Account;
import com.aws.carddemo.entity.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBTRN02C.cbl} (731
 * lines) — the daily-transaction posting processor.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBTRN02C.cbl} reads each validated daily
 * transaction record, performs a 4-stage validation cascade (matching
 * {@link TransactionValidationProcessor}), and on success performs a
 * dual-write of:
 * <ol>
 *   <li>The TRANSACT (master transaction file) — new transaction record
 *       inserted via {@code 2900-WRITE-TRANSACTION-FILE}.</li>
 *   <li>The ACCOUNT-FILE — updated account cycle balances via
 *       {@code 2800-UPDATE-ACCOUNT-REC}.</li>
 *   <li>The TCATBAL (transaction-category-balance file) — updated via
 *       {@code 2700-UPDATE-TCATBAL}.</li>
 * </ol>
 *
 * <p>If any of the three writes fails, the COBOL emits an ERROR DISPLAY
 * and ABENDs; the Spring Batch migration replaces this with
 * {@link org.springframework.transaction.annotation.Transactional}
 * (rollbackFor = Exception.class) — a single uncommitted exception
 * rolls all three writes back atomically.
 *
 * <p>Rejected transactions (validation cascade returned a non-zero
 * reason code) are written to the DALYREJS file via
 * {@code 2500-WRITE-REJECT-REC}. The Spring Batch migration replaces
 * this with a {@link RejectRecord}-returning side path that the step's
 * composite ItemWriter routes to a reject-file writer.
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>This class exposes pure-function seams for the unit tests:
 * <ul>
 *   <li>{@link #applyCycleUpdate(Account, BigDecimal)} — encodes the
 *       COBOL {@code 2800-UPDATE-ACCOUNT-REC} cycle-balance update:
 *       add the transaction amount to the cycle debit (for charges)
 *       or credit (for payments). The sign of {@code amount}
 *       determines which counter is updated.</li>
 *   <li>{@link #buildPostedTransaction(Transaction, java.time.LocalDateTime)} —
 *       converts the daily-transaction record into the posted-transaction
 *       record by stamping the processing timestamp (the COBOL
 *       {@code DB2-FORMAT-TS} from {@code Z-GET-DB2-FORMAT-TIMESTAMP}).</li>
 *   <li>{@link #buildReject(Transaction, int, String)} — builds a
 *       {@link RejectRecord} for the DALYREJS side path.</li>
 * </ul>
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1, §0.4.2, §0.10.3 (Financial Precision), §0.10.4
 * (Immutable Boundaries — record layouts).
 *
 * @see TransactionValidationProcessor
 * @see com.aws.carddemo.batch.TransactionPostingProcessorTest
 */
public class TransactionPostingProcessor {

    /**
     * Constructs a new processor. No collaborators at this checkpoint;
     * the JPA repositories (AccountRepository, TransactionRepository)
     * and the RejectRecord writer are wired by the Spring Batch step
     * configuration in a subsequent checkpoint.
     */
    public TransactionPostingProcessor() {
        // No collaborators.
    }

    /**
     * Apply the cycle-balance update to an {@link Account} — the Java
     * equivalent of COBOL paragraph {@code 2800-UPDATE-ACCOUNT-REC}.
     *
     * <p>Semantics:
     * <ul>
     *   <li>If {@code amount > 0} (a charge): add to
     *       {@link Account#getCurrentCycleDebit()} and to
     *       {@link Account#getCurrentBalance()} (current balance
     *       increases with each new charge).</li>
     *   <li>If {@code amount < 0} (a payment/credit): add the absolute
     *       value to {@link Account#getCurrentCycleCredit()} and
     *       subtract from {@link Account#getCurrentBalance()} (current
     *       balance decreases with each payment).</li>
     *   <li>If {@code amount == 0}: no change (the COBOL still writes
     *       the record but no fields change). The Java implementation
     *       short-circuits to avoid unnecessary entity mutation.</li>
     * </ul>
     *
     * <p>This method mutates the {@code account} argument in place,
     * matching the COBOL behaviour where the account record is
     * modified before the REWRITE statement. Callers must not pass an
     * immutable or shared {@link Account} instance.
     *
     * @param account the account to update; must not be null. Will be mutated.
     * @param amount  the transaction amount (sign-significant); must not be null
     * @return the mutated {@code account} (returned for chaining)
     */
    public Account applyCycleUpdate(Account account, BigDecimal amount) {
        Objects.requireNonNull(account, "account must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        int sign = amount.signum();
        if (sign == 0) {
            return account;
        }

        BigDecimal current = nullSafeZero(account.getCurrentBalance());
        BigDecimal cycleDebit = nullSafeZero(account.getCurrentCycleDebit());
        BigDecimal cycleCredit = nullSafeZero(account.getCurrentCycleCredit());

        if (sign > 0) {
            // Charge — debit increases, current balance increases.
            account.setCurrentCycleDebit(cycleDebit.add(amount).setScale(2, RoundingMode.HALF_EVEN));
            account.setCurrentBalance(current.add(amount).setScale(2, RoundingMode.HALF_EVEN));
        } else {
            // Payment — credit increases (by absolute value), balance decreases.
            BigDecimal absAmount = amount.abs();
            account.setCurrentCycleCredit(cycleCredit.add(absAmount).setScale(2, RoundingMode.HALF_EVEN));
            account.setCurrentBalance(current.add(amount).setScale(2, RoundingMode.HALF_EVEN));
        }
        return account;
    }

    /**
     * Build a posted-transaction record from a daily-transaction record
     * and a processing-timestamp — the Java equivalent of the COBOL
     * MOVE sequence at lines 423-436 of {@code CBTRN02C.cbl}.
     *
     * <p>The daily-transaction record's {@code TRAN-PROC-TS} field is
     * stamped with the wall-clock processing time (replacing COBOL
     * {@code Z-GET-DB2-FORMAT-TIMESTAMP}); all other fields are copied
     * unchanged. The returned Transaction is a fresh instance — the
     * caller's daily transaction is not mutated.
     *
     * @param daily      the daily transaction to post; must not be null
     * @param processTimestamp the processing-time stamp; must not be null
     * @return a new {@link Transaction} ready for insertion into the master file
     */
    public Transaction buildPostedTransaction(Transaction daily, java.time.LocalDateTime processTimestamp) {
        Objects.requireNonNull(daily, "daily must not be null");
        Objects.requireNonNull(processTimestamp, "processTimestamp must not be null");

        Transaction posted = new Transaction();
        posted.setTransactionId(daily.getTransactionId());
        posted.setTransactionTypeCode(daily.getTransactionTypeCode());
        posted.setTransactionCategoryCode(daily.getTransactionCategoryCode());
        posted.setSource(daily.getSource());
        posted.setDescription(daily.getDescription());
        posted.setAmount(daily.getAmount());
        posted.setMerchantId(daily.getMerchantId());
        posted.setMerchantName(daily.getMerchantName());
        posted.setMerchantCity(daily.getMerchantCity());
        posted.setMerchantZip(daily.getMerchantZip());
        posted.setCardNumber(daily.getCardNumber());
        posted.setOriginTimestamp(daily.getOriginTimestamp());
        // Stamp the processing timestamp in COBOL-equivalent DB2-FORMAT-TS
        // (YYYY-MM-DD-HH.MM.SS.000000). java.time.LocalDateTime#toString
        // emits ISO_LOCAL_DATE_TIME (YYYY-MM-DDTHH:MM:SS) which differs;
        // we format explicitly for byte-equality parity.
        posted.setProcessTimestamp(formatDb2Timestamp(processTimestamp));
        return posted;
    }

    /**
     * Build a {@link RejectRecord} from a rejected daily transaction
     * — the Java equivalent of COBOL paragraph
     * {@code 2500-WRITE-REJECT-REC} where the validation trailer
     * (reason code + description) is appended to the original
     * transaction data.
     *
     * @param daily       the rejected daily transaction; must not be null
     * @param reasonCode  the reject reason code (one of the
     *                    {@code TransactionValidationProcessor.REASON_*} constants)
     * @param description the COBOL-equivalent description for the reason code;
     *                    must not be null
     * @return a new {@link RejectRecord} ready for the DALYREJS file
     */
    public RejectRecord buildReject(Transaction daily, int reasonCode, String description) {
        Objects.requireNonNull(daily, "daily must not be null");
        Objects.requireNonNull(description, "description must not be null");
        return new RejectRecord(daily, reasonCode, description);
    }

    /**
     * Returns a zero-scale-2 BigDecimal if {@code value} is null;
     * otherwise returns {@code value} unchanged.
     */
    private static BigDecimal nullSafeZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN) : value;
    }

    /**
     * Format a {@link java.time.LocalDateTime} as the COBOL
     * {@code DB2-FORMAT-TS} string {@code YYYY-MM-DD-HH.MM.SS.NNNNNN}
     * (26 chars, dot separators, microseconds at end).
     */
    private static String formatDb2Timestamp(java.time.LocalDateTime ts) {
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%06d",
                ts.getYear(), ts.getMonthValue(), ts.getDayOfMonth(),
                ts.getHour(), ts.getMinute(), ts.getSecond(),
                ts.getNano() / 1000);
    }

    /**
     * Immutable value object representing one row of the DALYREJS reject
     * file. Carries the original daily transaction plus the validation
     * trailer (reason code + description).
     */
    public static final class RejectRecord {
        private final Transaction daily;
        private final int reasonCode;
        private final String description;

        /** Constructs a new RejectRecord. */
        public RejectRecord(Transaction daily, int reasonCode, String description) {
            this.daily = Objects.requireNonNull(daily, "daily must not be null");
            this.reasonCode = reasonCode;
            this.description = Objects.requireNonNull(description, "description must not be null");
        }

        /** @return the original daily transaction that failed validation */
        public Transaction getDaily() { return daily; }

        /** @return the reject reason code (100-103 in the current cascade) */
        public int getReasonCode() { return reasonCode; }

        /** @return the COBOL-equivalent description string */
        public String getDescription() { return description; }

        @Override
        public String toString() {
            return "RejectRecord{reason=" + reasonCode
                    + ", description='" + description + "'}";
        }
    }
}
