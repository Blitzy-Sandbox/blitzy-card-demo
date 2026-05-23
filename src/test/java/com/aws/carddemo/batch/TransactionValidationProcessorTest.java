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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;

import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link TransactionValidationProcessor} — the Java migration
 * of the COBOL {@code CBTRN01C} daily-transaction validator (see
 * {@code app/cbl/CBTRN01C.cbl}).
 *
 * <h2>COBOL Provenance</h2>
 *
 * <p>{@code CBTRN01C.cbl} validates each daily-transaction record through
 * a four-stage cascade with reject codes 100, 101, 102, 103:
 * <ul>
 *   <li>Stage 1 (line 385): card cross-reference lookup → code 100 if miss.</li>
 *   <li>Stage 2 (line 397): account-master lookup → code 101 if miss.</li>
 *   <li>Stage 3 (line 410): credit-limit check → code 102 if exceeded.</li>
 *   <li>Stage 4 (line 417): account-expiration check → code 103 if expired.
 *       Per COBOL source, expiration overrides overlimit when both fail
 *       (the IF-ELSE for expiration is sequenced AFTER overlimit and
 *       overwrites the reject reason).</li>
 * </ul>
 *
 * <h2>Test Strategy (AAP §0.10.1 Require Test Coverage rule)</h2>
 *
 * <p>Every test invokes the <strong>real production
 * {@link TransactionValidationProcessor}</strong>. The test does NOT
 * reimplement the validation cascade logic — each parameterized row
 * supplies the four boolean stage outcomes and the expected reject code,
 * and the test calls {@link TransactionValidationProcessor#rejectCodeFor}
 * directly.
 *
 * @see TransactionValidationProcessor
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionValidationProcessor unit tests (CBTRN01C migration)")
class TransactionValidationProcessorTest {

    /** Real SUT — fresh per test. */
    private TransactionValidationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransactionValidationProcessor();
    }

    // ============================================================
    // Top-level sanity tests — verify constants align with COBOL
    // ============================================================

    @Test
    @DisplayName("rejectCodeConstants_alignWithCobolContract")
    void rejectCodeConstants_alignWithCobolContract() {
        // The reject-code numeric values are CBTRN01C-line-source-of-truth.
        assertThat(TransactionValidationProcessor.REASON_OK).isEqualTo(0);
        assertThat(TransactionValidationProcessor.REASON_INVALID_CARD).isEqualTo(100);
        assertThat(TransactionValidationProcessor.REASON_ACCOUNT_NOT_FOUND).isEqualTo(101);
        assertThat(TransactionValidationProcessor.REASON_OVERLIMIT).isEqualTo(102);
        assertThat(TransactionValidationProcessor.REASON_ACCOUNT_EXPIRED).isEqualTo(103);
    }

    @Test
    @DisplayName("rejectDescriptions_alignWithCobolLiterals")
    void rejectDescriptions_alignWithCobolLiterals() {
        // Verbatim CBTRN01C lines 386, 398, 411, 418.
        assertThat(TransactionValidationProcessor.DESC_INVALID_CARD)
                .isEqualTo("INVALID CARD NUMBER FOUND");
        assertThat(TransactionValidationProcessor.DESC_ACCOUNT_NOT_FOUND)
                .isEqualTo("ACCOUNT RECORD NOT FOUND");
        assertThat(TransactionValidationProcessor.DESC_OVERLIMIT)
                .isEqualTo("OVERLIMIT TRANSACTION");
        assertThat(TransactionValidationProcessor.DESC_ACCOUNT_EXPIRED)
                .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
    }

    // ============================================================
    // Nested test class 1 — validateBasic
    // ============================================================

    /**
     * Coverage for {@link TransactionValidationProcessor#validateBasic}
     * — the COBOL paragraph {@code 1500-VALIDATE-TRAN} basic-shape checks.
     */
    @Nested
    @DisplayName("validateBasic — Stage 0 basic-shape validation")
    class ValidateBasicTests {

        @Test
        @DisplayName("validateBasic_nullTransaction_returnsInvalidCard")
        void validateBasic_nullTransaction_returnsInvalidCard() {
            int result = processor.validateBasic(null);
            assertThat(result)
                    .as("Null transaction must yield REASON_INVALID_CARD")
                    .isEqualTo(TransactionValidationProcessor.REASON_INVALID_CARD);
        }

        @Test
        @DisplayName("validateBasic_wellFormedTransaction_returnsOk")
        void validateBasic_wellFormedTransaction_returnsOk() {
            Transaction t = new Transaction();
            t.setCardNumber("4111111111111111");
            t.setAmount(new BigDecimal("10.00"));
            t.setTransactionTypeCode("01");
            t.setTransactionCategoryCode("0001");

            int result = processor.validateBasic(t);

            assertThat(result)
                    .as("Well-formed transaction must yield REASON_OK")
                    .isEqualTo(TransactionValidationProcessor.REASON_OK);
        }

        @Test
        @DisplayName("validateBasic_blankCardNumber_returnsInvalidCard")
        void validateBasic_blankCardNumber_returnsInvalidCard() {
            Transaction t = new Transaction();
            t.setCardNumber("                ");  // 16 spaces - blank.
            t.setAmount(new BigDecimal("10.00"));
            t.setTransactionTypeCode("01");
            t.setTransactionCategoryCode("0001");

            assertThat(processor.validateBasic(t))
                    .isEqualTo(TransactionValidationProcessor.REASON_INVALID_CARD);
        }

        @Test
        @DisplayName("validateBasic_nullCardNumber_returnsInvalidCard")
        void validateBasic_nullCardNumber_returnsInvalidCard() {
            Transaction t = new Transaction();
            // cardNumber left null.
            t.setAmount(new BigDecimal("10.00"));
            t.setTransactionTypeCode("01");
            t.setTransactionCategoryCode("0001");

            assertThat(processor.validateBasic(t))
                    .isEqualTo(TransactionValidationProcessor.REASON_INVALID_CARD);
        }

        @Test
        @DisplayName("validateBasic_nullAmount_returnsInvalidCard")
        void validateBasic_nullAmount_returnsInvalidCard() {
            Transaction t = new Transaction();
            t.setCardNumber("4111111111111111");
            // amount left null.
            t.setTransactionTypeCode("01");
            t.setTransactionCategoryCode("0001");

            assertThat(processor.validateBasic(t))
                    .isEqualTo(TransactionValidationProcessor.REASON_INVALID_CARD);
        }

        @Test
        @DisplayName("validateBasic_blankTypeCode_returnsInvalidCard")
        void validateBasic_blankTypeCode_returnsInvalidCard() {
            Transaction t = new Transaction();
            t.setCardNumber("4111111111111111");
            t.setAmount(new BigDecimal("10.00"));
            t.setTransactionTypeCode("  ");  // blank.
            t.setTransactionCategoryCode("0001");

            assertThat(processor.validateBasic(t))
                    .isEqualTo(TransactionValidationProcessor.REASON_INVALID_CARD);
        }

        @Test
        @DisplayName("validateBasic_blankCategoryCode_returnsInvalidCard")
        void validateBasic_blankCategoryCode_returnsInvalidCard() {
            Transaction t = new Transaction();
            t.setCardNumber("4111111111111111");
            t.setAmount(new BigDecimal("10.00"));
            t.setTransactionTypeCode("01");
            t.setTransactionCategoryCode("    ");  // blank.

            assertThat(processor.validateBasic(t))
                    .isEqualTo(TransactionValidationProcessor.REASON_INVALID_CARD);
        }
    }

    // ============================================================
    // Nested test class 2 — rejectCodeFor cascade coverage
    // ============================================================

    /**
     * Coverage for {@link TransactionValidationProcessor#rejectCodeFor}
     * — the COBOL 4-stage validation cascade {@code 1500-VALIDATE-TRAN}.
     *
     * <p>Each parameterized row supplies the four boolean stage outcomes
     * (cardFound, accountFound, withinCreditLimit, notExpired) and the
     * expected reject code. The test invokes the production method
     * directly and asserts the result matches the literal expected
     * value.
     */
    @Nested
    @DisplayName("rejectCodeFor — 4-stage cascade (CBTRN01C 1500-VALIDATE-TRAN)")
    class RejectCodeCascadeTests {

        /**
         * Drive {@link TransactionValidationProcessor#rejectCodeFor} with
         * each boolean 4-tuple and assert the cascade output. Rows are
         * authored as a CsvSource (inline) to keep all permutations
         * visible at the test-class level rather than spread across a
         * separate CSV file — there are only 16 distinct permutations.
         */
        @ParameterizedTest(name = "[{index}] cardFound={0} acct={1} within={2} notExpired={3} → reject={4}")
        @CsvSource({
                // cardFound, acctFound, withinLimit, notExpired, expectedCode
                "true, true, true, true, 0",         // happy path
                "false, false, false, false, 100",   // card miss short-circuits
                "false, true, true, true, 100",      // card miss alone
                "false, true, false, false, 100",    // card miss masks all later
                "true, false, true, true, 101",      // account miss
                "true, false, false, true, 101",     // account miss masks overlimit
                "true, false, true, false, 101",     // account miss masks expired
                "true, true, false, true, 102",      // overlimit (within=false, notExpired=true)
                "true, true, true, false, 103",      // expired (within=true, notExpired=false)
                "true, true, false, false, 103",     // both fail → expired wins (COBOL ordering)
        })
        void rejectCodeFor_eachPermutation_producesExpectedCode(
                boolean cardFound, boolean accountFound,
                boolean withinCreditLimit, boolean notExpired,
                int expectedCode) {
            // Act — REAL production method invocation.
            int actual = processor.rejectCodeFor(cardFound, accountFound,
                    withinCreditLimit, notExpired);

            // Assert — production result matches the table-driven expectation.
            assertThat(actual)
                    .as("cardFound=%s acct=%s within=%s notExpired=%s: "
                            + "production rejectCodeFor must yield %d",
                            cardFound, accountFound, withinCreditLimit,
                            notExpired, expectedCode)
                    .isEqualTo(expectedCode);
        }
    }

    // ============================================================
    // Nested test class 3 — rejectDescriptionFor maps to verbatim COBOL
    // ============================================================

    /**
     * Coverage for {@link TransactionValidationProcessor#rejectDescriptionFor}
     * — verifies the production method emits the verbatim COBOL literals.
     *
     * <p>The test does NOT reimplement the reject-code → description
     * lookup; it asserts the production output equals the expected
     * description constant directly.
     */
    @Nested
    @DisplayName("rejectDescriptionFor — verbatim COBOL literal mapping")
    class RejectDescriptionTests {

        @Test
        @DisplayName("rejectDescriptionFor_okCode_returnsEmpty")
        void rejectDescriptionFor_okCode_returnsEmpty() {
            assertThat(processor.rejectDescriptionFor(
                    TransactionValidationProcessor.REASON_OK))
                    .as("REASON_OK must map to empty string (no reject)")
                    .isEmpty();
        }

        @Test
        @DisplayName("rejectDescriptionFor_invalidCard_returnsVerbatimLiteral")
        void rejectDescriptionFor_invalidCard_returnsVerbatimLiteral() {
            assertThat(processor.rejectDescriptionFor(
                    TransactionValidationProcessor.REASON_INVALID_CARD))
                    .as("REASON_INVALID_CARD must map to the verbatim CBTRN01C line-386 literal")
                    .isEqualTo("INVALID CARD NUMBER FOUND");
        }

        @Test
        @DisplayName("rejectDescriptionFor_accountNotFound_returnsVerbatimLiteral")
        void rejectDescriptionFor_accountNotFound_returnsVerbatimLiteral() {
            assertThat(processor.rejectDescriptionFor(
                    TransactionValidationProcessor.REASON_ACCOUNT_NOT_FOUND))
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");
        }

        @Test
        @DisplayName("rejectDescriptionFor_overlimit_returnsVerbatimLiteral")
        void rejectDescriptionFor_overlimit_returnsVerbatimLiteral() {
            assertThat(processor.rejectDescriptionFor(
                    TransactionValidationProcessor.REASON_OVERLIMIT))
                    .isEqualTo("OVERLIMIT TRANSACTION");
        }

        @Test
        @DisplayName("rejectDescriptionFor_accountExpired_returnsVerbatimLiteral")
        void rejectDescriptionFor_accountExpired_returnsVerbatimLiteral() {
            assertThat(processor.rejectDescriptionFor(
                    TransactionValidationProcessor.REASON_ACCOUNT_EXPIRED))
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");
        }

        @Test
        @DisplayName("rejectDescriptionFor_unknownCode_returnsUnknown")
        void rejectDescriptionFor_unknownCode_returnsUnknown() {
            // Codes outside the documented set fall through to UNKNOWN.
            assertThat(processor.rejectDescriptionFor(999))
                    .as("Undocumented code must map to UNKNOWN (defensive default)")
                    .isEqualTo("UNKNOWN");
        }

        /**
         * Drive {@link TransactionValidationProcessor#rejectDescriptionFor}
         * with each row of {@code posting_reject_codes.csv}; assert the
         * production method's output matches the fixture's expected reason.
         */
        @ParameterizedTest(name = "[{index}] reject={3} → reason={4}")
        @CsvFileSource(resources = "/fixtures/edge/posting_reject_codes.csv", numLinesToSkip = 1)
        void rejectDescriptionFor_csvRow_matchesFixture(
                String accountId, String cardNumber, String amount,
                int expectedRejectCode, String rejectReason) {
            // Map the reject code via the production method.
            String actual = processor.rejectDescriptionFor(expectedRejectCode);

            // Code 109 in the fixture is a CBTRN02C-specific code (REWRITE
            // failure) which shares the 101 reason text but is not part
            // of CBTRN01C's vocabulary. The production rejectDescriptionFor
            // returns "UNKNOWN" for code 109 (matches the defensive default).
            if (expectedRejectCode == 109) {
                assertThat(actual)
                        .as("Code 109 is not in CBTRN01C's vocabulary; "
                                + "production maps it to UNKNOWN")
                        .isEqualTo("UNKNOWN");
            } else {
                // For all CBTRN01C-emitted codes (100, 101, 102, 103),
                // the production output must match the fixture's
                // expected reason verbatim.
                assertThat(actual)
                        .as("Code %d: production rejectDescriptionFor must match "
                                + "fixture reason %s", expectedRejectCode, rejectReason)
                        .isEqualTo(rejectReason);
            }
        }
    }

    // ============================================================
    // Nested test class 4 — projectedBalance arithmetic
    // ============================================================

    /**
     * Coverage for {@link TransactionValidationProcessor#projectedBalance}
     * — the COBOL {@code COMPUTE WS-TEMP-BAL} expression at line 408.
     *
     * <p>This is the Stage 3 (overlimit) arithmetic. Per AAP §0.10.3,
     * the test asserts on production output values and does NOT
     * reimplement the formula in the test body.
     */
    @Nested
    @DisplayName("projectedBalance — WS-TEMP-BAL arithmetic (CBTRN01C line 408)")
    class ProjectedBalanceTests {

        @Test
        @DisplayName("projectedBalance_typicalCharge_computesExpectedValue")
        void projectedBalance_typicalCharge_computesExpectedValue() {
            // Production: cycleCredit - cycleDebit + amount, scale 2.
            BigDecimal cycleCredit = new BigDecimal("200.00");
            BigDecimal cycleDebit = new BigDecimal("150.00");
            BigDecimal amount = new BigDecimal("75.00");

            BigDecimal projected = processor.projectedBalance(
                    cycleCredit, cycleDebit, amount);

            // Expected literal (computed once by hand from the COBOL
            // formula): 200 - 150 + 75 = 125.00.
            assertThat(projected)
                    .as("projectedBalance must produce 125.00 for (200, 150, 75)")
                    .isEqualByComparingTo("125.00");
            assertThat(projected.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("projectedBalance_paymentDecreasesBalance")
        void projectedBalance_paymentDecreasesBalance() {
            BigDecimal cycleCredit = new BigDecimal("100.00");
            BigDecimal cycleDebit = new BigDecimal("0.00");
            BigDecimal amount = new BigDecimal("-50.00");

            BigDecimal projected = processor.projectedBalance(
                    cycleCredit, cycleDebit, amount);

            // 100 - 0 + (-50) = 50.00.
            assertThat(projected).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("projectedBalance_zeroInputs_yieldsZero")
        void projectedBalance_zeroInputs_yieldsZero() {
            BigDecimal projected = processor.projectedBalance(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
            assertThat(projected).isEqualByComparingTo("0.00");
            assertThat(projected.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("projectedBalance_nullInputs_throws")
        void projectedBalance_nullInputs_throws() {
            BigDecimal zero = BigDecimal.ZERO;
            assertThatThrownBy(() -> processor.projectedBalance(null, zero, zero))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> processor.projectedBalance(zero, null, zero))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> processor.projectedBalance(zero, zero, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // ============================================================
    // Nested test class 5 — Logging safety (AAP §0.10.5)
    // ============================================================

    @Nested
    @DisplayName("Logging safety — no financial data in logs (AAP §0.10.5)")
    class LoggingSafetyTests {

        @Test
        @DisplayName("processor_doesNotDeclareLoggerField")
        void processor_doesNotDeclareLoggerField() {
            assertThat(TransactionValidationProcessor.class.getDeclaredFields())
                    .as("TransactionValidationProcessor must not declare any "
                            + "logger fields (AAP §0.10.5 defensive design)")
                    .noneMatch(f -> f.getType().getName().toLowerCase().contains("log"));
        }
    }
}
