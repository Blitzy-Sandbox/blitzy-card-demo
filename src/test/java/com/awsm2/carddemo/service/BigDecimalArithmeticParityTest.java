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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.exception.OnSizeErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Foundational parity test for COBOL&rarr;Java {@link BigDecimal} arithmetic.
 *
 * <p>Verifies the Blitzy platform's mandatory rule (AAP &sect;0.6.1):</p>
 * <blockquote>
 *   Every COBOL {@code PIC S9(n)V99} / {@code COMP-3} monetary field maps to
 *   {@link java.math.BigDecimal} with {@link RoundingMode#HALF_EVEN} and
 *   scale = 2 &mdash; zero {@code float}/{@code double} substitution for
 *   monetary values.
 * </blockquote>
 *
 * <p>This test documents the arithmetic contract used by every monetary
 * computation in the CardDemo Java target. The verbatim COBOL formulas
 * exercised here are:</p>
 *
 * <ul>
 *   <li><b>CBACT04C</b> ({@code 1300-COMPUTE-INTEREST} paragraph, lines
 *       462&ndash;465) &mdash; the monthly interest formula
 *       {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
 *       Translates verbatim to
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200L), 2,
 *       RoundingMode.HALF_EVEN)}; <b>MUST NOT</b> be algebraically simplified.</li>
 *   <li><b>CBTRN02C</b> (lines 403&ndash;405) &mdash; the temp-balance formula
 *       {@code COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *       - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}, used for reject-code-102
 *       (OVERLIMIT) detection.</li>
 *   <li><b>COBIL00C</b> (line 234) &mdash; the bill-payment balance update
 *       {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} where
 *       {@code TRAN-AMT} has just been {@code MOVE}'d from {@code ACCT-CURR-BAL}
 *       (line 224), zeroing the account balance.</li>
 *   <li><b>CBSTM03A</b> &mdash; statement-total accumulation via
 *       {@code ADD WS-TRN-AMT TO WS-TOTAL-AMT} into a
 *       {@code PIC S9(9)V99 COMP-3} accumulator.</li>
 *   <li><b>{@code ON SIZE ERROR}</b> &mdash; every {@code COMPUTE}-translated
 *       arithmetic boundary that may overflow throws
 *       {@link OnSizeErrorException} when the result exceeds the target
 *       precision (PIC S9(10)V99 for account fields, PIC S9(09)V99 for
 *       transaction amounts).</li>
 * </ul>
 *
 * <p><b>COBOL precision &harr; Java {@code BigDecimal}</b> (verified against
 * {@code app/cpy/CVACT01Y.cpy} and {@code app/cpy/CVTRA05Y.cpy}):</p>
 * <ul>
 *   <li>{@code PIC S9(10)V99} (account-fields {@code ACCT-CURR-BAL},
 *       {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
 *       {@code ACCT-CURR-CYC-CREDIT}, {@code ACCT-CURR-CYC-DEBIT}) &rarr;
 *       precision = 12, scale = 2, max = 9,999,999,999.99.</li>
 *   <li>{@code PIC S9(09)V99} ({@code TRAN-AMT}, {@code DALYTRAN-AMT},
 *       {@code WS-TOTAL-AMT}, {@code WS-TRN-AMT}) &rarr; precision = 11,
 *       scale = 2, max = 999,999,999.99.</li>
 * </ul>
 *
 * <p>All assertions use AssertJ's
 * {@link org.assertj.core.api.AbstractBigDecimalAssert#isEqualByComparingTo
 * isEqualByComparingTo(...)}, which performs <i>value-level</i> comparison
 * (so {@code 1.00} equals {@code 1.0}). The default {@code BigDecimal.equals}
 * is scale-sensitive and would reject equal values with different scales,
 * which is incorrect for the parity contract.</p>
 *
 * <p>This class is a <b>standalone</b> test: it does NOT mock any service,
 * does NOT load a Spring context, and does NOT touch any repository or AWS
 * adapter. It directly exercises {@link BigDecimal} arithmetic to document
 * the foundational contract that every {@code @Service} class in the
 * {@code com.awsm2.carddemo.service} package indirectly depends on.</p>
 *
 * @see com.awsm2.carddemo.exception.OnSizeErrorException
 */
@DisplayName("BigDecimal Arithmetic Parity \u2014 COBOL PIC S9(n)V99 / COMP-3 \u2192 Java BigDecimal HALF_EVEN")
class BigDecimalArithmeticParityTest {

    /**
     * COBOL {@code PIC S9(10)V99} maximum value &mdash; 10 integer digits +
     * 2 decimal digits = {@code 9,999,999,999.99}. Applied to every monetary
     * account field per {@code app/cpy/CVACT01Y.cpy}: {@code ACCT-CURR-BAL},
     * {@code ACCT-CREDIT-LIMIT}, {@code ACCT-CASH-CREDIT-LIMIT},
     * {@code ACCT-CURR-CYC-CREDIT}, and {@code ACCT-CURR-CYC-DEBIT}.
     */
    private static final BigDecimal PIC_S9_10_V99_MAX = new BigDecimal("9999999999.99");

    /**
     * COBOL {@code PIC S9(09)V99} maximum value &mdash; 9 integer digits +
     * 2 decimal digits = {@code 999,999,999.99}. Applied to {@code TRAN-AMT}
     * per {@code app/cpy/CVTRA05Y.cpy} and {@code DALYTRAN-AMT} per
     * {@code app/cpy/CVTRA06Y.cpy}.
     */
    private static final BigDecimal PIC_S9_09_V99_MAX = new BigDecimal("999999999.99");

    /**
     * Verbatim COBOL divisor from CBACT04C line 464 &mdash; the constant
     * {@code 1200} appearing in
     * {@code COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
     * <b>MUST NOT</b> be replaced with {@code 0.001} or
     * {@code rate.divide(BigDecimal.valueOf(1200))} (algebraic
     * simplification); the verbatim formula preserves intermediate precision.
     */
    private static final BigDecimal MONTHLY_DIVISOR = BigDecimal.valueOf(1200L);

    // =========================================================================
    // CBACT04C interest formula: (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
    // =========================================================================

    /**
     * Parity tests for the {@code 1300-COMPUTE-INTEREST} paragraph of
     * {@code app/cbl/CBACT04C.cbl} (lines 462&ndash;465). The COBOL statement
     * is reproduced verbatim:
     *
     * <pre>{@code
     * COMPUTE WS-MONTHLY-INT
     *     = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
     * }</pre>
     */
    @Nested
    @DisplayName("CBACT04C interest formula: (balance \u00D7 rate) / 1200 \u2192 scale 2 HALF_EVEN")
    class InterestFormulaParity {

        /**
         * Documents the verbatim COBOL formula. Production
         * {@code InterestCalculationService} implementations <b>MUST</b>
         * follow this exact form &mdash; multiply first, then divide with
         * {@code scale = 2, HALF_EVEN}.
         */
        private BigDecimal computeMonthlyInterest(BigDecimal balance, BigDecimal rate) {
            // COBOL: CBACT04C.cbl L462-L465 (1300-COMPUTE-INTEREST paragraph)
            return balance.multiply(rate)
                          .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_EVEN);
        }

        @ParameterizedTest(name = "balance={0} \u00D7 rate={1} / 1200 = {2}")
        @CsvSource({
            // Exact (integer-result) values
            "1200.00, 18.99, 18.99",     //  (1200    * 18.99) / 1200 = 18.99 exact
            "100.00,  12.00,  1.00",     //  ( 100    * 12.00) / 1200 =  1.00 exact
            "5000.00, 24.00, 100.00",    //  (5000    * 24.00) / 1200 = 100.00 exact
            "0.00,    18.99,  0.00",     //  zero balance \u2192 zero interest
            // HALF_EVEN rounding cases (verified against Python decimal)
            "2500.00, 19.99, 41.65",     //  49975/1200    = 41.64583... \u2192 41.65
            "10000.00, 29.99, 249.92",   //  299900/1200   = 249.91666... \u2192 249.92
            "1234.56, 17.99,  18.51",    //  22209.7344/1200 = 18.508112 \u2192 18.51
        })
        @DisplayName("Verbatim formula matches COBOL golden values under HALF_EVEN scale 2")
        void interestFormula_matchesGoldenCobolValues(String balance, String rate, String expected) {
            BigDecimal result = computeMonthlyInterest(new BigDecimal(balance), new BigDecimal(rate));
            assertThat(result).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(result.scale()).isEqualTo(2);
        }

        /**
         * Dynamic golden-value provider demonstrating
         * {@link MethodSource @MethodSource} + {@link Stream}{@code <}
         * {@link Arguments}{@code >} for cases that don't fit cleanly in a
         * {@link CsvSource @CsvSource}. The Blitzy platform reserves this
         * pattern for parallel-run COBOL-output diffing during the cutover
         * window (AAP &sect;0.6.1 BigDecimal validation strategy).
         */
        static Stream<Arguments> additionalGoldenValues() {
            return Stream.of(
                // Mid-range balances against the typical disclosure-group rates
                Arguments.of(new BigDecimal("3600.00"), new BigDecimal("18.00"), new BigDecimal("54.00")),
                Arguments.of(new BigDecimal("7200.00"), new BigDecimal("12.00"), new BigDecimal("72.00")),
                Arguments.of(new BigDecimal("6000.00"), new BigDecimal("24.00"), new BigDecimal("120.00")),
                // ZEROAPR disclosure group \u2192 zero interest
                Arguments.of(new BigDecimal("12345.67"), new BigDecimal("0.00"), new BigDecimal("0.00"))
            );
        }

        @ParameterizedTest(name = "({0} \u00D7 {1}) / 1200 = {2}")
        @MethodSource("additionalGoldenValues")
        @DisplayName("Dynamic golden values via MethodSource")
        void interestFormula_dynamicGoldenValues(BigDecimal balance, BigDecimal rate, BigDecimal expected) {
            BigDecimal result = computeMonthlyInterest(balance, rate);
            assertThat(result).isEqualByComparingTo(expected);
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Verbatim formula yields the contract value (18.51 for 1234.56 \u00D7 17.99 / 1200)")
        void interestFormula_verbatimFormula_isContractValue() {
            BigDecimal balance = new BigDecimal("1234.56");
            BigDecimal rate = new BigDecimal("17.99");

            // Verbatim COBOL: COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
            BigDecimal verbatim = balance.multiply(rate)
                                         .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_EVEN);

            // 1234.56 \u00D7 17.99 = 22209.7344; / 1200 = 18.508112; HALF_EVEN scale 2 \u2192 18.51
            assertThat(verbatim).isEqualByComparingTo(new BigDecimal("18.51"));
            assertThat(verbatim.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Zero rate (ZEROAPR disclosure group) yields zero interest")
        void interestFormula_zeroRate_yieldsZero() {
            BigDecimal result = computeMonthlyInterest(new BigDecimal("5000.00"), BigDecimal.ZERO);
            assertThat(result).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Zero balance yields zero interest")
        void interestFormula_zeroBalance_yieldsZero() {
            BigDecimal result = computeMonthlyInterest(BigDecimal.ZERO, new BigDecimal("18.99"));
            assertThat(result).isEqualByComparingTo(new BigDecimal("0.00"));
            assertThat(result.scale()).isEqualTo(2);
        }
    }

    // =========================================================================
    // HALF_EVEN (banker's rounding) boundary cases
    // =========================================================================

    /**
     * Parity tests for the mandatory {@link RoundingMode#HALF_EVEN} rounding
     * mode (banker's rounding). The Blitzy platform forbids
     * {@link RoundingMode#HALF_UP} and the default unscaled
     * {@link BigDecimal#divide(BigDecimal)} contract (which throws
     * {@link ArithmeticException} on non-terminating decimals).
     *
     * <p>The boundary cases here capture the canonical "tie" pattern of
     * HALF_EVEN: when the discarded fraction is exactly {@code 0.5}, the
     * result is rounded to the nearest <i>even</i> neighbor. HALF_UP would
     * always round away from zero in this case.</p>
     */
    @Nested
    @DisplayName("HALF_EVEN (banker's rounding) boundary cases")
    class HalfEvenBoundaryParity {

        @ParameterizedTest(name = "{0} \u2192 scale 2 HALF_EVEN \u2192 {1}")
        @CsvSource({
            // Tie cases (the .5 cliff)
            "1.005, 1.00",        // 00 is even \u2192 stay (HALF_UP would go to 1.01)
            "1.015, 1.02",        // 01 is odd  \u2192 round up to 02 (even)
            "1.025, 1.02",        // 02 is even \u2192 stay
            "1.035, 1.04",        // 03 is odd  \u2192 round up to 04 (even)
            "1.045, 1.04",        // 04 is even \u2192 stay
            "1.055, 1.06",        // 05 is odd  \u2192 round up to 06 (even)
            // Scale preservation
            "2.5,    2.50",       // exact at scale 2
            "0.001,  0.00",       // sub-cent truncation
            "0.005,  0.00",       // 00 even, stay
            "0.015,  0.02",       // 01 odd, round up
            // Negative ties
            "-1.005, -1.00",      // 00 even, stay
            "-1.015, -1.02",      // 01 odd, round to 02
            "-1.025, -1.02",      // 02 even, stay
            // Non-tie cases (sanity)
            "100.4999, 100.50",   // discarded fraction > .5 \u2192 round up
            "100.4949, 100.49",   // discarded fraction < .5 \u2192 round down
        })
        @DisplayName("setScale(2, HALF_EVEN) matches banker's-rounding contract")
        void scaleSetWithHalfEven_matchesExpected(String input, String expected) {
            BigDecimal result = new BigDecimal(input).setScale(2, RoundingMode.HALF_EVEN);
            assertThat(result).isEqualByComparingTo(new BigDecimal(expected));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("HALF_EVEN diverges from HALF_UP at exactly .5 \u2014 contract is HALF_EVEN")
        void halfEven_vsHalfUp_documentDifference() {
            BigDecimal value = new BigDecimal("1.005");

            BigDecimal halfEven = value.setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal halfUp = value.setScale(2, RoundingMode.HALF_UP);

            // HALF_EVEN \u2192 1.00 (00 is even, stay)
            assertThat(halfEven).isEqualByComparingTo(new BigDecimal("1.00"));
            // HALF_UP \u2192 1.01 (always away from zero on .5)
            assertThat(halfUp).isEqualByComparingTo(new BigDecimal("1.01"));
            // The CardDemo Java target MUST use HALF_EVEN per AAP \u00A70.6.1.
            assertThat(halfEven).isNotEqualByComparingTo(halfUp);
        }
    }

    // =========================================================================
    // CBTRN02C temp balance: ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
    // =========================================================================

    /**
     * Parity tests for the temp-balance computation in
     * {@code app/cbl/CBTRN02C.cbl} (lines 403&ndash;405). The COBOL statement
     * is reproduced verbatim:
     *
     * <pre>{@code
     * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
     *                     - ACCT-CURR-CYC-DEBIT
     *                     + DALYTRAN-AMT
     * }</pre>
     *
     * <p>The downstream guard at lines 406&ndash;410 is:</p>
     *
     * <pre>{@code
     * IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
     *     CONTINUE
     * ELSE
     *     MOVE 102 TO WS-VALIDATION-FAIL-REASON
     * END-IF
     * }</pre>
     *
     * <p>which produces the OVERLIMIT (reject code {@code 102}) outcome. This
     * test exercises only the arithmetic; the reject decision lives in
     * {@code TransactionPostingService}.</p>
     */
    @Nested
    @DisplayName("CBTRN02C temp balance: cycCredit \u2212 cycDebit + tranAmt")
    class TempBalanceParity {

        private BigDecimal computeTempBalance(BigDecimal cycCredit, BigDecimal cycDebit, BigDecimal tranAmt) {
            // COBOL: CBTRN02C.cbl L403-L405
            return cycCredit.subtract(cycDebit).add(tranAmt).setScale(2, RoundingMode.HALF_EVEN);
        }

        @Test
        @DisplayName("Typical purchase \u2014 positive temp balance")
        void tempBalance_typicalCase() {
            BigDecimal result = computeTempBalance(
                new BigDecimal("500.00"),     // cycCredit
                new BigDecimal("100.00"),     // cycDebit
                new BigDecimal("50.00")       // tranAmt (new purchase)
            );
            assertThat(result).isEqualByComparingTo(new BigDecimal("450.00"));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Over-credit purchase \u2014 produces large temp balance that triggers reject 102 downstream")
        void tempBalance_overlimitTrigger() {
            BigDecimal result = computeTempBalance(
                new BigDecimal("100.00"),
                new BigDecimal("90.00"),
                new BigDecimal("5000.00")     // large purchase
            );
            // 100 \u2212 90 + 5000 = 5010
            assertThat(result).isEqualByComparingTo(new BigDecimal("5010.00"));
        }

        @Test
        @DisplayName("Refund \u2014 negative tranAmt reduces the temp balance")
        void tempBalance_refundCase() {
            BigDecimal result = computeTempBalance(
                new BigDecimal("500.00"),
                new BigDecimal("100.00"),
                new BigDecimal("-50.00")      // refund credit
            );
            // 500 \u2212 100 + (\u22125) = 350
            assertThat(result).isEqualByComparingTo(new BigDecimal("350.00"));
        }

        @Test
        @DisplayName("Sub-cent fractional tranAmt \u2014 HALF_EVEN scale 2 collapses to nearest cent")
        void tempBalance_subCentFraction_halfEven() {
            // Even though TRAN-AMT is PIC S9(09)V99, mid-calculation values can exceed
            // scale 2; HALF_EVEN at scale 2 is the contract.
            BigDecimal result = computeTempBalance(
                new BigDecimal("1000.005"),    // intermediate (would not exist in PIC S9 storage)
                new BigDecimal("0.00"),
                new BigDecimal("0.00")
            );
            // 1000.005 \u2192 HALF_EVEN scale 2 \u2192 1000.00 (00 even, stay)
            assertThat(result).isEqualByComparingTo(new BigDecimal("1000.00"));
            assertThat(result.scale()).isEqualTo(2);
        }
    }

    // =========================================================================
    // COBIL00C bill payment: balance → 0.00
    // =========================================================================

    /**
     * Parity tests for the bill-payment balance-zeroing path in
     * {@code app/cbl/COBIL00C.cbl}. The two-line COBOL sequence is:
     *
     * <pre>{@code
     * MOVE ACCT-CURR-BAL  TO TRAN-AMT                       (line 224)
     * COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT      (line 234)
     * }</pre>
     *
     * <p>Because line 224 sets {@code TRAN-AMT} equal to {@code ACCT-CURR-BAL},
     * the subtraction on line 234 is effectively {@code bal - bal = 0}. The
     * Java counterpart in {@code BillPaymentService} performs the same
     * full-balance payment, leaving the account at exactly {@code 0.00} with
     * scale 2.</p>
     */
    @Nested
    @DisplayName("COBIL00C bill payment: full-balance payment \u2192 0.00")
    class BillPaymentZeroingParity {

        @Test
        @DisplayName("Full-balance payment yields exact 0.00 with scale 2")
        void billPayment_fullBalancePaysToZero() {
            BigDecimal accountBalance = new BigDecimal("1234.56");

            // COBOL: COBIL00C.cbl L224 \u2014 MOVE ACCT-CURR-BAL TO TRAN-AMT
            BigDecimal tranAmt = accountBalance;

            // COBOL: COBIL00C.cbl L234 \u2014 COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
            BigDecimal afterPayment = accountBalance.subtract(tranAmt)
                                                     .setScale(2, RoundingMode.HALF_EVEN);

            assertThat(afterPayment).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(afterPayment.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Negative balance \u2014 paying a negative balance preserves 0.00 outcome")
        void billPayment_negativeBalance_zerosToZero() {
            // Although CardDemo accounts typically don't carry negative
            // balances, the arithmetic invariant {@code x - x = 0} holds for
            // every value in the {@code PIC S9(10)V99} range.
            BigDecimal accountBalance = new BigDecimal("-987.65");

            BigDecimal tranAmt = accountBalance;
            BigDecimal afterPayment = accountBalance.subtract(tranAmt)
                                                     .setScale(2, RoundingMode.HALF_EVEN);

            assertThat(afterPayment).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(afterPayment.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Already-zero balance stays at 0.00")
        void billPayment_zeroBalance_staysZero() {
            BigDecimal accountBalance = new BigDecimal("0.00");
            BigDecimal tranAmt = accountBalance;
            BigDecimal afterPayment = accountBalance.subtract(tranAmt)
                                                     .setScale(2, RoundingMode.HALF_EVEN);
            assertThat(afterPayment).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(afterPayment.scale()).isEqualTo(2);
        }
    }

    // =========================================================================
    // CBSTM03A statement total: ADD TRAN-AMT TO WS-TOTAL-AMT
    // =========================================================================

    /**
     * Parity tests for the statement-total accumulation in
     * {@code app/cbl/CBSTM03A.CBL}. The COBOL accumulator is declared:
     *
     * <pre>{@code
     * 01 COMP3-VARIABLES               COMP-3.
     *     05  WS-TOTAL-AMT             PIC S9(9)V99 VALUE 0.
     * }</pre>
     *
     * <p>and the statement loop performs {@code ADD WS-TRN-AMT TO WS-TOTAL-AMT}
     * per transaction. The Java counterpart accumulates via
     * {@code total = total.add(tranAmt).setScale(2, RoundingMode.HALF_EVEN)},
     * preserving scale 2 across the entire fold.</p>
     */
    @Nested
    @DisplayName("CBSTM03A statement total: SUM(tranAmt) preserving scale 2")
    class StatementTotalParity {

        @Test
        @DisplayName("Mixed-sign transactions sum to the expected statement total")
        void statementTotal_summation() {
            BigDecimal[] amounts = {
                new BigDecimal("100.00"),
                new BigDecimal("250.50"),
                new BigDecimal("75.25"),
                new BigDecimal("-50.00"),    // refund
                new BigDecimal("12.99"),
            };

            // COBOL: CBSTM03A.CBL \u2014 ADD WS-TRN-AMT TO WS-TOTAL-AMT
            BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            for (BigDecimal amt : amounts) {
                total = total.add(amt).setScale(2, RoundingMode.HALF_EVEN);
            }

            // 100.00 + 250.50 + 75.25 \u2212 50.00 + 12.99 = 388.74
            assertThat(total).isEqualByComparingTo(new BigDecimal("388.74"));
            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Empty transaction list \u2014 total is 0.00 at scale 2")
        void statementTotal_emptyList() {
            BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            assertThat(total).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Single transaction \u2014 total equals that transaction's amount")
        void statementTotal_singleTransaction() {
            BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            total = total.add(new BigDecimal("123.45")).setScale(2, RoundingMode.HALF_EVEN);
            assertThat(total).isEqualByComparingTo(new BigDecimal("123.45"));
            assertThat(total.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Many small additions \u2014 BigDecimal stays exact (no double drift)")
        void statementTotal_manySmallAdditions_remainsExact() {
            // 1000 \u00D7 0.01 = 10.00 \u2014 BigDecimal arithmetic is exact;
            // the equivalent {@code double} version drifts (see
            // FloatDoubleProhibitionParity).
            BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal cent = new BigDecimal("0.01");
            for (int i = 0; i < 1000; i++) {
                total = total.add(cent).setScale(2, RoundingMode.HALF_EVEN);
            }
            assertThat(total).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(total.scale()).isEqualTo(2);
        }
    }

    // =========================================================================
    // COBOL PIC precision boundary parity
    // =========================================================================

    /**
     * Parity tests for the COBOL {@code PIC S9(n)V99} precision boundaries
     * &mdash; verifying that the maximum (and negative-maximum) values fit
     * exactly within the corresponding {@link BigDecimal#precision()} and
     * {@link BigDecimal#scale()} contracts.
     *
     * <p>The JPA {@code @Column(precision, scale)} mapping is derived from
     * these boundaries: {@code precision = digits_total + 0} (the sign is
     * implicit in {@code BigDecimal}; PostgreSQL {@code NUMERIC} also
     * encodes sign separately). See AAP &sect;0.6.1.</p>
     */
    @Nested
    @DisplayName("COBOL PIC precision boundary parity")
    class PrecisionBoundaryParity {

        @Test
        @DisplayName("PIC S9(10)V99 max value (9,999,999,999.99) fits precision \u2264 12, scale = 2")
        void picS9_10_V99_maxValue_validScale() {
            BigDecimal max = PIC_S9_10_V99_MAX;
            assertThat(max.precision()).isLessThanOrEqualTo(12);
            assertThat(max.scale()).isEqualTo(2);
            assertThat(max).isEqualByComparingTo(new BigDecimal("9999999999.99"));
        }

        @Test
        @DisplayName("PIC S9(09)V99 max value (999,999,999.99) fits precision \u2264 11, scale = 2")
        void picS9_09_V99_maxValue_validScale() {
            BigDecimal max = PIC_S9_09_V99_MAX;
            assertThat(max.precision()).isLessThanOrEqualTo(11);
            assertThat(max.scale()).isEqualTo(2);
            assertThat(max).isEqualByComparingTo(new BigDecimal("999999999.99"));
        }

        @Test
        @DisplayName("Negative PIC S9(10)V99 max (\u22129,999,999,999.99) preserves precision and scale")
        void picS9_10_V99_negativeMax() {
            BigDecimal negMax = PIC_S9_10_V99_MAX.negate();
            assertThat(negMax).isEqualByComparingTo(new BigDecimal("-9999999999.99"));
            assertThat(negMax.precision()).isLessThanOrEqualTo(12);
            assertThat(negMax.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Negative PIC S9(09)V99 max (\u2212999,999,999.99) preserves precision and scale")
        void picS9_09_V99_negativeMax() {
            BigDecimal negMax = PIC_S9_09_V99_MAX.negate();
            assertThat(negMax).isEqualByComparingTo(new BigDecimal("-999999999.99"));
            assertThat(negMax.precision()).isLessThanOrEqualTo(11);
            assertThat(negMax.scale()).isEqualTo(2);
        }
    }

    // =========================================================================
    // COBOL ON SIZE ERROR → OnSizeErrorException
    // =========================================================================

    /**
     * Parity tests for the COBOL {@code ON SIZE ERROR} clause semantics.
     * Every {@code COMPUTE}-translated arithmetic boundary that may overflow
     * the target {@code PIC} precision must verify the result against the
     * declared precision and throw {@link OnSizeErrorException} on overflow,
     * per AAP &sect;0.7.1.
     *
     * <p>The helper {@link #addWithSizeCheck(BigDecimal, BigDecimal, int)}
     * documents the canonical overflow-check pattern used in
     * {@code InterestCalculationService}, {@code TransactionPostingService},
     * and {@code BillPaymentService}.</p>
     */
    @Nested
    @DisplayName("COBOL ON SIZE ERROR \u2192 OnSizeErrorException")
    class OnSizeErrorParity {

        /**
         * Documents the canonical overflow-check pattern. Every production
         * arithmetic call site replicates this skeleton: compute, set scale,
         * then verify {@link BigDecimal#precision()} against the column's
         * declared precision.
         */
        private BigDecimal addWithSizeCheck(BigDecimal a, BigDecimal b, int maxPrecision) {
            BigDecimal result = a.add(b).setScale(2, RoundingMode.HALF_EVEN);
            if (result.abs().precision() > maxPrecision) {
                throw new OnSizeErrorException(
                    "COMPUTE overflow: " + result
                    + " exceeds PIC precision " + maxPrecision);
            }
            return result;
        }

        @Test
        @DisplayName("Overflow beyond PIC S9(09)V99 \u2192 OnSizeErrorException with 'overflow' message")
        void onSizeError_picS9_09_V99_overflow() {
            BigDecimal a = PIC_S9_09_V99_MAX;             // 999,999,999.99 \u2014 PIC S9(09)V99 max
            BigDecimal b = new BigDecimal("1.00");

            // 999,999,999.99 + 1.00 = 1,000,000,000.99 (precision 12) > PIC S9(09)V99 max precision 11
            assertThatThrownBy(() -> addWithSizeCheck(a, b, 11))
                .isInstanceOf(OnSizeErrorException.class)
                .hasMessageContaining("overflow");
        }

        @Test
        @DisplayName("No overflow within PIC S9(09)V99 \u2192 returns the computed result")
        void onSizeError_picS9_09_V99_withinBounds() {
            BigDecimal a = new BigDecimal("999999998.00");
            BigDecimal b = new BigDecimal("1.99");

            // 999,999,998.00 + 1.99 = 999,999,999.99 \u2014 fits exactly at PIC S9(09)V99 max
            BigDecimal result = addWithSizeCheck(a, b, 11);
            assertThat(result).isEqualByComparingTo(PIC_S9_09_V99_MAX);
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Overflow beyond PIC S9(10)V99 \u2192 OnSizeErrorException")
        void onSizeError_picS9_10_V99_overflow() {
            BigDecimal a = PIC_S9_10_V99_MAX;             // 9,999,999,999.99 \u2014 PIC S9(10)V99 max
            BigDecimal b = new BigDecimal("1.00");

            // 9,999,999,999.99 + 1.00 = 10,000,000,000.99 (precision 13) > PIC S9(10)V99 max precision 12
            assertThatThrownBy(() -> addWithSizeCheck(a, b, 12))
                .isInstanceOf(OnSizeErrorException.class)
                .hasMessageContaining("overflow");
        }

        @Test
        @DisplayName("Negative overflow beyond PIC S9(09)V99 \u2192 OnSizeErrorException (abs() used)")
        void onSizeError_picS9_09_V99_negativeOverflow() {
            BigDecimal a = PIC_S9_09_V99_MAX.negate();    // \u2212999,999,999.99
            BigDecimal b = new BigDecimal("-1.00");

            // The helper applies {@code .abs()} so negative overflow is detected too.
            assertThatThrownBy(() -> addWithSizeCheck(a, b, 11))
                .isInstanceOf(OnSizeErrorException.class)
                .hasMessageContaining("overflow");
        }

        @Test
        @DisplayName("OnSizeErrorException carries DEFAULT_REASON_CODE 'ARITHMETIC_OVERFLOW'")
        void onSizeError_carriesDefaultReasonCode() {
            assertThatThrownBy(() -> addWithSizeCheck(PIC_S9_10_V99_MAX, new BigDecimal("1.00"), 12))
                .isInstanceOf(OnSizeErrorException.class)
                .extracting(ex -> ((OnSizeErrorException) ex).getReasonCode())
                .isEqualTo(OnSizeErrorException.DEFAULT_REASON_CODE);
        }
    }

    // =========================================================================
    // AAP §0.6.1: float / double substitution is FORBIDDEN for monetary values
    // =========================================================================

    /**
     * Parity tests documenting WHY {@code float} and {@code double} are
     * forbidden for monetary computation. These tests do NOT assert that the
     * production code uses {@link BigDecimal} (that's enforced elsewhere);
     * they document the precision errors that motivate the rule.
     *
     * <p>AAP &sect;0.6.1 states: "Every COBOL {@code PIC S9(n)V99} and
     * {@code COMP-3} field maps to {@code java.math.BigDecimal} with
     * {@code RoundingMode.HALF_EVEN} &mdash; zero {@code float}/{@code double}
     * substitution for monetary values."</p>
     */
    @Nested
    @DisplayName("AAP \u00A70.6.1: float / double substitution is FORBIDDEN for monetary values")
    class FloatDoubleProhibitionParity {

        @Test
        @DisplayName("double 0.1 + 0.2 != 0.3 \u2014 BigDecimal makes it exact")
        void doubleArithmetic_introducesPrecisionErrors() {
            // The classic IEEE-754 demonstration
            double dResult = 0.1 + 0.2;
            // 0.30000000000000004 \u2260 0.3
            assertThat(dResult).isNotEqualTo(0.3d);

            // BigDecimal is exact at all scales
            BigDecimal bResult = new BigDecimal("0.1").add(new BigDecimal("0.2"));
            assertThat(bResult).isEqualByComparingTo(new BigDecimal("0.3"));
        }

        @Test
        @DisplayName("Sum of 1000 \u00D7 0.01 \u2014 double drifts, BigDecimal stays exact at 10.00")
        void summingManySmallAmounts_doubleDrifts_bigDecimalIsExact() {
            // double drift demonstration
            double dSum = 0.0d;
            for (int i = 0; i < 1000; i++) {
                dSum += 0.01d;
            }
            // The drift accumulates to a value that is not 10.0
            assertThat(dSum).isNotEqualTo(10.0d);

            // BigDecimal exact accumulation
            BigDecimal bSum = BigDecimal.ZERO;
            BigDecimal cent = new BigDecimal("0.01");
            for (int i = 0; i < 1000; i++) {
                bSum = bSum.add(cent);
            }
            assertThat(bSum).isEqualByComparingTo(new BigDecimal("10.00"));
        }

        @Test
        @DisplayName("double \u00D7 BigDecimal conversion via valueOf preserves precision")
        void doubleToBigDecimal_useValueOf_notConstructor() {
            // BigDecimal.valueOf(double) calls Double.toString(...) internally,
            // which respects the decimal literal's text form: 0.1d \u2192 "0.1".
            BigDecimal viaValueOf = BigDecimal.valueOf(0.1d);
            assertThat(viaValueOf).isEqualByComparingTo(new BigDecimal("0.1"));

            // By contrast, new BigDecimal(0.1d) captures the IEEE-754
            // approximation, producing 0.1000000000000000055511151231257827...
            BigDecimal viaCtor = new BigDecimal(0.1d);
            assertThat(viaCtor).isNotEqualByComparingTo(new BigDecimal("0.1"));
        }
    }

    // =========================================================================
    // Scale = 2 invariant across arithmetic operations
    // =========================================================================

    /**
     * Parity tests documenting how {@link BigDecimal} scale propagates across
     * the four arithmetic operations, and where explicit
     * {@code setScale(2, RoundingMode.HALF_EVEN)} normalization is required
     * to maintain the AAP &sect;0.6.1 scale = 2 invariant.
     */
    @Nested
    @DisplayName("Scale = 2 invariant across arithmetic operations")
    class ScalePreservationParity {

        @Test
        @DisplayName("Addition of scale-2 operands preserves scale 2")
        void add_preservesScale2() {
            BigDecimal a = new BigDecimal("100.00");
            BigDecimal b = new BigDecimal("50.50");
            BigDecimal result = a.add(b);
            assertThat(result.scale()).isEqualTo(2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("150.50"));
        }

        @Test
        @DisplayName("Subtraction of scale-2 operands preserves scale 2")
        void subtract_preservesScale2() {
            BigDecimal a = new BigDecimal("100.00");
            BigDecimal b = new BigDecimal("50.50");
            BigDecimal result = a.subtract(b);
            assertThat(result.scale()).isEqualTo(2);
            assertThat(result).isEqualByComparingTo(new BigDecimal("49.50"));
        }

        @Test
        @DisplayName("Multiplication extends scale (scale = sum of operand scales) \u2014 must setScale(2, HALF_EVEN)")
        void multiply_extendsScale_requireExplicitSetScale() {
            BigDecimal a = new BigDecimal("100.00");    // scale 2
            BigDecimal b = new BigDecimal("0.05");      // scale 2

            BigDecimal raw = a.multiply(b);
            // a.multiply(b) returns scale 4: 5.0000
            assertThat(raw.scale()).isEqualTo(4);

            BigDecimal bounded = raw.setScale(2, RoundingMode.HALF_EVEN);
            assertThat(bounded.scale()).isEqualTo(2);
            assertThat(bounded).isEqualByComparingTo(new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("Division WITHOUT scale on non-terminating decimal throws ArithmeticException")
        void divide_requiresExplicitScale() {
            BigDecimal a = new BigDecimal("100.00");
            BigDecimal b = new BigDecimal("3");

            // a.divide(b) without scale throws ArithmeticException (non-terminating decimal)
            assertThatThrownBy(() -> a.divide(b))
                .isInstanceOf(ArithmeticException.class);

            // Correct form: explicit scale + RoundingMode.HALF_EVEN
            BigDecimal result = a.divide(b, 2, RoundingMode.HALF_EVEN);
            assertThat(result).isEqualByComparingTo(new BigDecimal("33.33"));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("Division with scale=2, HALF_EVEN produces banker's-rounded quotient")
        void divide_withScale_halfEven_rounds() {
            // 10 / 3 = 3.333... \u2192 HALF_EVEN scale 2 \u2192 3.33
            BigDecimal result = new BigDecimal("10.00")
                .divide(new BigDecimal("3"), 2, RoundingMode.HALF_EVEN);
            assertThat(result).isEqualByComparingTo(new BigDecimal("3.33"));
            assertThat(result.scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("isEqualByComparingTo treats 1.0 and 1.00 as equal; equals() treats them as different")
        void scaleSensitive_equals_vs_isEqualByComparingTo() {
            BigDecimal a = new BigDecimal("1.0");
            BigDecimal b = new BigDecimal("1.00");

            // BigDecimal.equals(...) is SCALE-sensitive \u2014 the two are considered different
            assertThat(a.equals(b)).isFalse();
            // isEqualByComparingTo uses compareTo (value-level) \u2014 the two ARE equal
            assertThat(a).isEqualByComparingTo(b);
        }

        @Test
        @DisplayName("Chain (multiply, divide with scale) preserves scale 2 at the boundary")
        void chainedArithmetic_preservesScale2AtBoundary() {
            // Verbatim interest formula closed-form
            BigDecimal balance = new BigDecimal("1000.00");
            BigDecimal rate = new BigDecimal("12.00");
            BigDecimal monthly = balance.multiply(rate)
                                        .divide(MONTHLY_DIVISOR, 2, RoundingMode.HALF_EVEN);
            // (1000 \u00D7 12) / 1200 = 10.00
            assertThat(monthly).isEqualByComparingTo(new BigDecimal("10.00"));
            assertThat(monthly.scale()).isEqualTo(2);
        }
    }
}
