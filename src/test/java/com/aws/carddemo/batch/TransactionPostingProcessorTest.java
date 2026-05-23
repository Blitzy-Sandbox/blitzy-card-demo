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
import com.aws.carddemo.testsupport.TestFixtures;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link TransactionPostingProcessor} — the Java migration
 * of the COBOL {@code CBTRN02C} transaction-posting program (731 lines;
 * see {@code app/cbl/CBTRN02C.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBTRN02C.cbl} is the daily-transaction posting batch that
 * applies validated transactions to the account master file. The Java
 * migration exposes pure-function seams via
 * {@link TransactionPostingProcessor}:
 * <ul>
 *   <li>{@link TransactionPostingProcessor#applyCycleUpdate} —
 *       paragraph 2800-UPDATE-ACCOUNT-REC</li>
 *   <li>{@link TransactionPostingProcessor#buildPostedTransaction} —
 *       MOVE sequence at lines 423-436</li>
 *   <li>{@link TransactionPostingProcessor#buildReject} — paragraph
 *       2500-WRITE-REJECT-REC</li>
 * </ul>
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test method here invokes the <strong>real production
 * {@link TransactionPostingProcessor}</strong> via constructor
 * instantiation. The test does <strong>NOT reimplement reject-code
 * mapping</strong>; reject-code → reason lookups go through
 * {@link TransactionValidationProcessor} constants (the production
 * single source of truth) — not duplicated in test bodies.
 *
 * <h2>Coverage Categories</h2>
 *
 * <p>Five {@code @Nested} groups:
 * <ol>
 *   <li>{@link CycleUpdateTests} — applyCycleUpdate for charges, payments,
 *       and zero amounts (paragraph 2800-UPDATE-ACCOUNT-REC).</li>
 *   <li>{@link RejectCodeTests} — buildReject driven by the CBTRN02C
 *       reject-code CSV; verifies reject record carries the correct
 *       code/reason for each fixture row.</li>
 *   <li>{@link PostedTransactionTests} — buildPostedTransaction;
 *       verifies the posted record copies all fields and stamps the
 *       processing timestamp.</li>
 *   <li>{@link NullInputTests} — defensive null-handling.</li>
 *   <li>{@link LoggingSafetyTests} — AAP §0.10.5 NON-NEGOTIABLE: no
 *       financial data in logs.</li>
 * </ol>
 *
 * @see TransactionPostingProcessor
 * @see TransactionValidationProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionPostingProcessor unit tests (CBTRN02C migration)")
class TransactionPostingProcessorTest {

    /** Real SUT — fresh per test for isolation (AAP §0.10.9). */
    private TransactionPostingProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransactionPostingProcessor();
    }

    // ============================================================
    // Top-level sanity tests
    // ============================================================

    /**
     * Sanity test confirming the production class can be instantiated
     * and that the inner {@link TransactionPostingProcessor.RejectRecord}
     * type is publicly visible (it crosses the test/production boundary).
     */
    @Test
    @DisplayName("processor_isInstantiable_andRejectRecordIsAccessible")
    void processor_isInstantiable_andRejectRecordIsAccessible() {
        assertThat(processor)
                .as("Real TransactionPostingProcessor must be instantiable")
                .isNotNull();
        // The RejectRecord nested type is part of the public API surface.
        assertThat(TransactionPostingProcessor.RejectRecord.class)
                .as("RejectRecord must be a public nested type")
                .isPublic();
    }

    // ============================================================
    // Nested test class 1 — applyCycleUpdate (paragraph 2800)
    // ============================================================

    /**
     * Coverage for {@link TransactionPostingProcessor#applyCycleUpdate}
     * — the COBOL paragraph {@code 2800-UPDATE-ACCOUNT-REC}.
     *
     * <p>Verifies:
     * <ul>
     *   <li>Positive amount (charge) → cycleDebit and currentBalance
     *       increase by amount.</li>
     *   <li>Negative amount (payment) → cycleCredit increases by abs(amount),
     *       currentBalance decreases by abs(amount).</li>
     *   <li>Zero amount → no field changes.</li>
     *   <li>All resulting balances at scale 2 (HALF_EVEN).</li>
     * </ul>
     */
    @Nested
    @DisplayName("applyCycleUpdate — paragraph 2800-UPDATE-ACCOUNT-REC")
    class CycleUpdateTests {

        @Test
        @DisplayName("applyCycleUpdate_charge_increasesDebitAndBalance")
        void applyCycleUpdate_charge_increasesDebitAndBalance() {
            Account account = new Account();
            account.setCurrentBalance(new BigDecimal("100.00"));
            account.setCurrentCycleDebit(new BigDecimal("50.00"));
            account.setCurrentCycleCredit(new BigDecimal("25.00"));

            Account result = processor.applyCycleUpdate(account, new BigDecimal("30.00"));

            // Charge: debit += 30, balance += 30, credit unchanged.
            assertThat(result.getCurrentCycleDebit())
                    .as("Charge must add to currentCycleDebit")
                    .isEqualByComparingTo("80.00");
            assertThat(result.getCurrentBalance())
                    .as("Charge must add to currentBalance")
                    .isEqualByComparingTo("130.00");
            assertThat(result.getCurrentCycleCredit())
                    .as("Charge must NOT modify currentCycleCredit")
                    .isEqualByComparingTo("25.00");
            // Scale preservation
            assertThat(result.getCurrentBalance().scale())
                    .as("Balance must remain at scale 2")
                    .isEqualTo(2);
            assertThat(result.getCurrentCycleDebit().scale())
                    .as("Cycle debit must remain at scale 2")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("applyCycleUpdate_payment_increasesCreditAndDecreasesBalance")
        void applyCycleUpdate_payment_increasesCreditAndDecreasesBalance() {
            Account account = new Account();
            account.setCurrentBalance(new BigDecimal("100.00"));
            account.setCurrentCycleDebit(new BigDecimal("50.00"));
            account.setCurrentCycleCredit(new BigDecimal("25.00"));

            // Payment (negative amount).
            Account result = processor.applyCycleUpdate(account, new BigDecimal("-40.00"));

            // Payment: credit += abs(40), balance += -40, debit unchanged.
            assertThat(result.getCurrentCycleCredit())
                    .as("Payment must add abs(amount) to currentCycleCredit")
                    .isEqualByComparingTo("65.00");
            assertThat(result.getCurrentBalance())
                    .as("Payment must add (signed) amount to currentBalance — balance decreases")
                    .isEqualByComparingTo("60.00");
            assertThat(result.getCurrentCycleDebit())
                    .as("Payment must NOT modify currentCycleDebit")
                    .isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("applyCycleUpdate_zeroAmount_leavesBalancesUnchanged")
        void applyCycleUpdate_zeroAmount_leavesBalancesUnchanged() {
            Account account = new Account();
            account.setCurrentBalance(new BigDecimal("100.00"));
            account.setCurrentCycleDebit(new BigDecimal("50.00"));
            account.setCurrentCycleCredit(new BigDecimal("25.00"));

            Account result = processor.applyCycleUpdate(account, BigDecimal.ZERO);

            assertThat(result.getCurrentBalance()).isEqualByComparingTo("100.00");
            assertThat(result.getCurrentCycleDebit()).isEqualByComparingTo("50.00");
            assertThat(result.getCurrentCycleCredit()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("applyCycleUpdate_nullCurrentBalance_treatedAsZero")
        void applyCycleUpdate_nullCurrentBalance_treatedAsZero() {
            // Defensive null-handling — production must treat null
            // monetary fields as zero, not propagate NPE.
            Account account = new Account();
            // All monetary fields null.

            Account result = processor.applyCycleUpdate(account, new BigDecimal("50.00"));

            assertThat(result.getCurrentBalance())
                    .as("Null pre-balance treated as zero; charge yields 50.00")
                    .isEqualByComparingTo("50.00");
            assertThat(result.getCurrentCycleDebit())
                    .as("Null pre-debit treated as zero; charge yields 50.00")
                    .isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("applyCycleUpdate_returnsSameAccountInstance_forChaining")
        void applyCycleUpdate_returnsSameAccountInstance_forChaining() {
            Account account = new Account();
            account.setCurrentBalance(new BigDecimal("0.00"));
            account.setCurrentCycleDebit(new BigDecimal("0.00"));
            account.setCurrentCycleCredit(new BigDecimal("0.00"));

            Account result = processor.applyCycleUpdate(account, new BigDecimal("10.00"));

            // Same instance — matches the COBOL "REWRITE same record" semantic.
            assertThat(result).isSameAs(account);
        }
    }

    // ============================================================
    // Nested test class 2 — buildReject driven by CSV fixture
    // ============================================================

    /**
     * Coverage for {@link TransactionPostingProcessor#buildReject} —
     * the COBOL paragraph {@code 2500-WRITE-REJECT-REC}. Drives the real
     * production class with each row of {@code posting_reject_codes.csv}
     * and asserts the resulting {@link TransactionPostingProcessor.RejectRecord}
     * carries the expected reject code and reason text.
     *
     * <p>The test does NOT reimplement the reject-code mapping logic.
     * Each fixture row supplies the {@code expectedRejectCode} and
     * {@code rejectReason} as literals; the test passes both directly
     * to the production {@code buildReject} method and asserts the
     * resulting record carries them verbatim.
     */
    @Nested
    @DisplayName("buildReject — paragraph 2500-WRITE-REJECT-REC (driven by posting_reject_codes.csv)")
    class RejectCodeTests {

        /**
         * Drive {@link TransactionPostingProcessor#buildReject} with
         * each CSV row; assert the production-built RejectRecord
         * carries the fixture's literal reject code and reason.
         */
        @ParameterizedTest(name = "[{index}] account={0} card={1} amount={2} → reject={3} reason={4}")
        @CsvFileSource(resources = "/fixtures/edge/posting_reject_codes.csv", numLinesToSkip = 1)
        void buildReject_carriesFixtureCodeAndReason(
                String accountId, String cardNumber, String amount,
                int expectedRejectCode, String rejectReason) {
            // Build a daily-transaction record from the CSV row inputs.
            Transaction daily = new Transaction();
            daily.setCardNumber(cardNumber);
            daily.setAmount(new BigDecimal(amount));
            daily.setTransactionId(TestFixtures.Transactions.SAMPLE_TRANSACTION_ID);

            // Act — call the REAL production buildReject method with the
            // CSV-supplied reject code and reason. No reject-code mapping
            // reimplementation in the test body — per AAP §0.10.1.
            TransactionPostingProcessor.RejectRecord rejectRecord =
                    processor.buildReject(daily, expectedRejectCode, rejectReason);

            // Assertion #1: production preserves the reject code verbatim.
            assertThat(rejectRecord.getReasonCode())
                    .as("RejectRecord reasonCode must match CSV row's expected code")
                    .isEqualTo(expectedRejectCode);

            // Assertion #2: production preserves the reason text verbatim
            // (no transformation, no truncation, no normalization). This is
            // the contract that preserves byte-equality with the
            // DALYREJS-FILE downstream consumer (AAP §0.10.4).
            assertThat(rejectRecord.getDescription())
                    .as("RejectRecord description must match CSV row's expected reason verbatim")
                    .isEqualTo(rejectReason);

            // Assertion #3: production preserves the daily-transaction
            // payload by reference — the reject envelope carries the
            // original daily record for downstream forensic analysis.
            assertThat(rejectRecord.getDaily())
                    .as("RejectRecord must carry the original daily transaction")
                    .isSameAs(daily);

            // Assertion #4: fixture-consistency — the CSV's rejectReason
            // must be one of the four verbatim COBOL literals from
            // TransactionValidationProcessor (the single source of
            // truth — NOT duplicated as a switch in the test body).
            assertThat(rejectReason)
                    .as("CSV rejectReason must be a verbatim CBTRN02C/CBTRN01C constant")
                    .isIn(
                            TransactionValidationProcessor.DESC_INVALID_CARD,
                            TransactionValidationProcessor.DESC_ACCOUNT_NOT_FOUND,
                            TransactionValidationProcessor.DESC_OVERLIMIT,
                            TransactionValidationProcessor.DESC_ACCOUNT_EXPIRED);
        }
    }

    // ============================================================
    // Nested test class 3 — buildPostedTransaction
    // ============================================================

    /**
     * Coverage for {@link TransactionPostingProcessor#buildPostedTransaction}
     * — the COBOL MOVE sequence at lines 423-436 of CBTRN02C.cbl.
     */
    @Nested
    @DisplayName("buildPostedTransaction — MOVE sequence (CBTRN02C lines 423-436)")
    class PostedTransactionTests {

        @Test
        @DisplayName("buildPostedTransaction_copiesAllFieldsAndStampsTimestamp")
        void buildPostedTransaction_copiesAllFieldsAndStampsTimestamp() {
            Transaction daily = new Transaction();
            daily.setTransactionId("0000000000000001");
            daily.setTransactionTypeCode("01");
            daily.setTransactionCategoryCode("0001");
            daily.setSource("POS ");
            daily.setDescription("Sample charge                          ");
            daily.setAmount(new BigDecimal("25.99"));
            daily.setMerchantId("000000000999");
            daily.setMerchantName("ACME WIDGETS                  ");
            daily.setMerchantCity("SEATTLE          ");
            daily.setMerchantZip("98101    ");
            daily.setCardNumber("4111111111111111");
            daily.setOriginTimestamp("2024-01-15-10.30.45.123456");

            LocalDateTime processTs = LocalDateTime.parse("2024-01-15T20:00:00");

            Transaction posted = processor.buildPostedTransaction(daily, processTs);

            // Assertion #1: posted record is a NEW instance (not aliased
            // to the daily record — required for byte-equality with the
            // COBOL output where the posted file is separate from the
            // daily file).
            assertThat(posted)
                    .as("Posted must be a NEW instance, not aliased to daily")
                    .isNotSameAs(daily);

            // Assertion #2: all source-of-truth fields copied verbatim.
            assertThat(posted.getTransactionId()).isEqualTo(daily.getTransactionId());
            assertThat(posted.getTransactionTypeCode()).isEqualTo(daily.getTransactionTypeCode());
            assertThat(posted.getTransactionCategoryCode()).isEqualTo(daily.getTransactionCategoryCode());
            assertThat(posted.getSource()).isEqualTo(daily.getSource());
            assertThat(posted.getDescription()).isEqualTo(daily.getDescription());
            assertThat(posted.getAmount()).isEqualByComparingTo(daily.getAmount());
            assertThat(posted.getMerchantId()).isEqualTo(daily.getMerchantId());
            assertThat(posted.getMerchantName()).isEqualTo(daily.getMerchantName());
            assertThat(posted.getMerchantCity()).isEqualTo(daily.getMerchantCity());
            assertThat(posted.getMerchantZip()).isEqualTo(daily.getMerchantZip());
            assertThat(posted.getCardNumber()).isEqualTo(daily.getCardNumber());
            assertThat(posted.getOriginTimestamp()).isEqualTo(daily.getOriginTimestamp());

            // Assertion #3: process timestamp stamped at the wall-clock
            // value supplied by the caller (Java equivalent of COBOL
            // Z-GET-DB2-FORMAT-TIMESTAMP).
            assertThat(posted.getProcessTimestamp())
                    .as("Process timestamp must be stamped")
                    .isNotBlank();
            assertThat(posted.getProcessTimestamp())
                    .as("Process timestamp must match the supplied LocalDateTime "
                            + "in DB2-style format YYYY-MM-DD-HH.MM.SS.NNNNNN")
                    .startsWith("2024-01-15-20.00.00");
        }

        @Test
        @DisplayName("buildPostedTransaction_preservesDailyRecord_noMutation")
        void buildPostedTransaction_preservesDailyRecord_noMutation() {
            Transaction daily = new Transaction();
            daily.setTransactionId("0000000000000002");
            daily.setProcessTimestamp("           ");  // Blank originally.

            String originalDailyProcessTs = daily.getProcessTimestamp();

            processor.buildPostedTransaction(daily, LocalDateTime.parse("2024-01-15T20:00:00"));

            // The daily record's processTimestamp must be untouched —
            // only the posted output carries the stamp.
            assertThat(daily.getProcessTimestamp())
                    .as("buildPostedTransaction must not mutate the input daily record")
                    .isEqualTo(originalDailyProcessTs);
        }
    }

    // ============================================================
    // Nested test class 4 — Null-input defensive handling
    // ============================================================

    @Nested
    @DisplayName("Null-input defensive handling")
    class NullInputTests {

        @Test
        @DisplayName("applyCycleUpdate_nullAccount_throws")
        void applyCycleUpdate_nullAccount_throws() {
            assertThatThrownBy(() -> processor.applyCycleUpdate(null, BigDecimal.ZERO))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("account");
        }

        @Test
        @DisplayName("applyCycleUpdate_nullAmount_throws")
        void applyCycleUpdate_nullAmount_throws() {
            assertThatThrownBy(() -> processor.applyCycleUpdate(new Account(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("amount");
        }

        @Test
        @DisplayName("buildPostedTransaction_nullDaily_throws")
        void buildPostedTransaction_nullDaily_throws() {
            assertThatThrownBy(() -> processor.buildPostedTransaction(null, LocalDateTime.now()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("daily");
        }

        @Test
        @DisplayName("buildPostedTransaction_nullTimestamp_throws")
        void buildPostedTransaction_nullTimestamp_throws() {
            assertThatThrownBy(() -> processor.buildPostedTransaction(new Transaction(), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("processTimestamp");
        }

        @Test
        @DisplayName("buildReject_nullDaily_throws")
        void buildReject_nullDaily_throws() {
            assertThatThrownBy(() -> processor.buildReject(null,
                    TransactionValidationProcessor.REASON_INVALID_CARD,
                    TransactionValidationProcessor.DESC_INVALID_CARD))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("daily");
        }
    }

    // ============================================================
    // Nested test class 5 — Logging safety (AAP §0.10.5)
    // ============================================================

    /**
     * Coverage for AAP §0.10.5 NON-NEGOTIABLE: "No financial data
     * written to logs at any level". The production
     * {@link TransactionPostingProcessor} is a pure-function arithmetic
     * seam with no logger field; any future regression introducing one
     * is caught by these tests.
     */
    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            // Pure arithmetic seam — must have no logger field.
            assertThat(TransactionPostingProcessor.class.getDeclaredFields())
                    .as("TransactionPostingProcessor must not declare any "
                            + "logger fields (AAP §0.10.5 defensive design)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }

        @Test
        @DisplayName("applyCycleUpdate_doesNotWriteSensitiveDataToStderr")
        void applyCycleUpdate_doesNotWriteSensitiveDataToStderr() {
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            try {
                System.setErr(new PrintStream(capturedErr));

                // Inputs containing sensitive financial values.
                Account account = new Account();
                account.setAccountId("00000000010");
                account.setCurrentBalance(new BigDecimal("9999.99"));
                account.setCurrentCycleDebit(new BigDecimal("0.00"));
                account.setCurrentCycleCredit(new BigDecimal("0.00"));

                processor.applyCycleUpdate(account, new BigDecimal("123.45"));

            } finally {
                System.setErr(originalErr);
            }

            String captured = capturedErr.toString();
            assertThat(captured)
                    .as("applyCycleUpdate must not emit anything to stderr")
                    .isEmpty();
            // Defensive double-check: no sensitive values leaked even
            // if stderr was empty (in case framework prepended whitespace).
            assertThat(captured).doesNotContain("9999.99");
            assertThat(captured).doesNotContain("123.45");
            assertThat(captured).doesNotContain("00000000010");
        }

        @Test
        @DisplayName("buildReject_doesNotWriteSensitiveDataToStderr")
        void buildReject_doesNotWriteSensitiveDataToStderr() {
            ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
            PrintStream originalErr = System.err;
            try {
                System.setErr(new PrintStream(capturedErr));

                Transaction daily = new Transaction();
                daily.setCardNumber("4111111111111111");   // PAN-shaped sensitive value
                daily.setAmount(new BigDecimal("500.00"));
                daily.setTransactionId(TestFixtures.Transactions.SAMPLE_TRANSACTION_ID);

                processor.buildReject(daily,
                        TransactionValidationProcessor.REASON_OVERLIMIT,
                        TransactionValidationProcessor.DESC_OVERLIMIT);

            } finally {
                System.setErr(originalErr);
            }

            String captured = capturedErr.toString();
            // Most defensive: PAN must never appear in any log/stderr line.
            assertThat(captured)
                    .as("buildReject must not write the PAN/amount to stderr")
                    .doesNotContain("4111111111111111")
                    .doesNotContain("500.00");
        }
    }
}
