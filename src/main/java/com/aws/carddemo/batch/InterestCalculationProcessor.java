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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBACT04C.cbl} (652
 * lines) — the monthly interest-calculation processor.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBACT04C.cbl} reads the TCATBAL VSAM file
 * sequentially, joins each record to its ACCOUNT (via XREF) and its
 * DISCGRP entry, computes monthly interest using the COBOL formula
 * <pre>
 *   COMPUTE WS-MONTHLY-INT
 *      = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 * </pre>
 * and writes a system-generated transaction record (TRAN-TYPE-CD = '01',
 * TRAN-CAT-CD = '05', source 'System') for each non-zero result.
 *
 * <p>{@link #computeInterest(BigDecimal, BigDecimal)} is the core
 * arithmetic — the Java replacement for the COMPUTE statement at
 * {@code 1300-COMPUTE-INTEREST} (line 464 of {@code CBACT04C.cbl}).
 *
 * <h2>Critical Financial-Precision Contract (AAP §0.10.3)</h2>
 *
 * <p>The COBOL workspace fields constrain precision and rounding:
 * <ul>
 *   <li>{@code WS-MONTHLY-INT PIC S9(09)V99} — scale 2 (two digits
 *       after the implied decimal point); the Java result is therefore
 *       {@link BigDecimal} at scale 2.</li>
 *   <li>COBOL's COMPUTE uses bankers' rounding (HALF_EVEN) by default
 *       when truncating to the receiving field's scale; the Java
 *       implementation must use {@link RoundingMode#HALF_EVEN}
 *       explicitly to preserve byte-equality parity with the COBOL
 *       output.</li>
 *   <li>The intermediate division at 34-digit precision (via
 *       {@link MathContext#DECIMAL128}) ensures the rounding boundary
 *       is reached precisely; using a low-precision intermediate would
 *       lose information before the final {@code setScale} call.</li>
 *   <li>No {@code float} or {@code double} anywhere in the call chain —
 *       this is the AAP §0.10.3 NON-NEGOTIABLE.</li>
 * </ul>
 *
 * <h2>ZEROAPR Skip and DEFAULT Group Fallback</h2>
 *
 * <p>The COBOL workflow includes two special behaviours that
 * {@link #computeInterest} encodes:
 * <ul>
 *   <li><b>Zero-rate skip.</b> {@code IF DIS-INT-RATE NOT = 0 ... PERFORM
 *       1300-COMPUTE-INTEREST} — when the disclosure group's interest
 *       rate is zero (ZEROAPR group), interest computation is skipped
 *       entirely and the monthly interest is treated as zero. Java
 *       equivalent: {@link #computeInterest} returns
 *       {@code BigDecimal.ZERO.setScale(2)} whenever rate is zero.</li>
 *   <li><b>DEFAULT group fallback.</b> When the discount-group lookup
 *       returns no match for the (account-group-id, tran-type-cd,
 *       tran-cat-cd) composite key, the COBOL falls back to a DEFAULT
 *       group reload via {@code 1200-A-GET-DEFAULT-INT-RATE}. This
 *       fallback is the responsibility of the calling layer (the
 *       Spring Batch step or service that wires this processor); the
 *       processor itself only receives the resolved rate.</li>
 * </ul>
 *
 * <h2>Minimal Change Clause Compliance</h2>
 *
 * <p>Per AAP §0.10.2, this class exposes only the arithmetic seam needed
 * to make the unit tests compliant with the Require Test Coverage rule
 * (AAP §0.10.1). Repository wiring and Spring Batch step orchestration
 * are out of scope for this checkpoint — those collaborators will be
 * injected and exercised in the subsequent Spring Batch integration
 * checkpoint where {@link InterestCalculationProcessor} is wrapped in an
 * {@code ItemProcessor<TransactionCategoryBalance, Transaction>}.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.3.1 (Test Target Identification — InterestCalculationProcessor
 * for the {@code CBACT04C} migration), §0.4.2 Blueprint B (test
 * categories), §0.10.3 (Financial Precision NON-NEGOTIABLE).
 *
 * @see com.aws.carddemo.batch.InterestCalculationProcessorTest
 */
public class InterestCalculationProcessor {

    /**
     * The monthly-interest divisor — 1200 — matching the COBOL formula
     * literal at {@code CBACT04C.cbl} line 465:
     * {@code ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}.
     *
     * <p>Divisor rationale: the COBOL formula expresses the annual
     * percentage rate (APR) as a percentage (e.g., 18.00 meaning 18%),
     * and converts to a monthly fraction by dividing by 100 (percent →
     * fraction) and then by 12 (annual → monthly). 100 × 12 = 1200.
     * Using a single literal preserves COBOL parity at the byte level.
     */
    public static final BigDecimal MONTHLY_INTEREST_DIVISOR = new BigDecimal("1200");

    /**
     * The monetary scale — 2 — matching the COBOL declaration
     * {@code WS-MONTHLY-INT PIC S9(09)V99}.
     */
    public static final int MONETARY_SCALE = 2;

    /**
     * The COBOL-equivalent rounding mode — {@link RoundingMode#HALF_EVEN}.
     * Used by the {@code setScale} call inside {@link #computeInterest}
     * to truncate the (balance × rate) / 1200 quotient to scale 2.
     *
     * <p>HALF_EVEN (banker's rounding) is the COBOL default when
     * truncating to a smaller scale. The CardDemo migration MUST use
     * HALF_EVEN per AAP §0.10.3.
     */
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;

    /**
     * Constructs a new processor.
     *
     * <p>This minimal-viable class has no external collaborators.
     * Repository and writer wiring is the responsibility of the Spring
     * Batch step configuration that adapts this processor into an
     * {@code ItemProcessor}.
     */
    public InterestCalculationProcessor() {
        // No collaborators.
    }

    /**
     * Compute the monthly interest for a single (balance, rate) pair —
     * the Java replacement for the COBOL paragraph
     * {@code 1300-COMPUTE-INTEREST}.
     *
     * <p>Formula (matches CBACT04C.cbl line 465):
     * <pre>
     *   monthlyInterest = (balance × rate) / 1200,
     *   rounded HALF_EVEN to scale 2.
     * </pre>
     *
     * <p>Special cases:
     * <ul>
     *   <li>{@code balance == null} → throws {@link NullPointerException}.</li>
     *   <li>{@code rate == null} → throws {@link NullPointerException}.</li>
     *   <li>Zero rate → returns {@code BigDecimal.ZERO} at scale 2 (the
     *       ZEROAPR group skip).</li>
     *   <li>Zero balance → returns {@code BigDecimal.ZERO} at scale 2
     *       (no interest accrues on a zero balance).</li>
     *   <li>Negative balance → still computes the product/quotient (the
     *       COBOL behaviour: negative balances yield negative interest,
     *       which is then added to a negative running total). The Java
     *       implementation preserves this for byte-equality parity.</li>
     * </ul>
     *
     * <p>The intermediate division uses {@link MathContext#DECIMAL128}
     * to preserve 34 significant digits before the final
     * {@code setScale(2, HALF_EVEN)} step; this guarantees that
     * HALF_EVEN is applied to a precisely-computed raw quotient, not to
     * a prematurely-truncated approximation.
     *
     * @param balance the transaction-category balance (PIC S9(09)V99,
     *                scale 2); must not be {@code null}
     * @param rate    the disclosure-group interest rate (PIC S9(04)V99,
     *                scale 2); must not be {@code null}
     * @return the monthly interest at scale 2, rounded HALF_EVEN
     * @throws NullPointerException if {@code balance} or {@code rate} is {@code null}
     */
    public BigDecimal computeInterest(BigDecimal balance, BigDecimal rate) {
        Objects.requireNonNull(balance, "balance must not be null");
        Objects.requireNonNull(rate, "rate must not be null");

        // ZEROAPR group skip — COBOL: IF DIS-INT-RATE NOT = 0 ...
        // PERFORM 1300-COMPUTE-INTEREST.
        if (rate.signum() == 0) {
            return BigDecimal.ZERO.setScale(MONETARY_SCALE, ROUNDING_MODE);
        }

        // Compute (balance × rate) / 1200 in HALF_EVEN, scale 2.
        // BigDecimal.multiply is exact — the product scale equals the
        // sum of operand scales (no rounding). The divide step is the
        // only place rounding can occur; using DECIMAL128 (34-digit
        // precision) ensures rounding happens far beyond the scale-2
        // target, so the subsequent setScale call sees an effectively
        // exact raw value and produces the correct HALF_EVEN result.
        BigDecimal product = balance.multiply(rate);
        BigDecimal rawQuotient = product.divide(MONTHLY_INTEREST_DIVISOR, MathContext.DECIMAL128);
        return rawQuotient.setScale(MONETARY_SCALE, ROUNDING_MODE);
    }

    /**
     * Add a freshly-computed monthly interest amount to a running total
     * — the Java replacement for the COBOL statement
     * {@code ADD WS-MONTHLY-INT TO WS-TOTAL-INT}.
     *
     * <p>Both operands are at scale 2; the result is at scale 2 (no
     * scale shift). {@link BigDecimal#add(BigDecimal)} preserves the
     * larger scale of its operands.
     *
     * @param runningTotal the existing total before this addition; must not be {@code null}
     * @param monthlyInterest the amount to add; must not be {@code null}
     * @return {@code runningTotal + monthlyInterest} at scale 2
     */
    public BigDecimal accumulate(BigDecimal runningTotal, BigDecimal monthlyInterest) {
        Objects.requireNonNull(runningTotal, "runningTotal must not be null");
        Objects.requireNonNull(monthlyInterest, "monthlyInterest must not be null");
        return runningTotal.add(monthlyInterest).setScale(MONETARY_SCALE, ROUNDING_MODE);
    }

    /**
     * Returns {@code true} iff the given rate represents the ZEROAPR
     * group (a disclosure-group whose interest rate is zero) — the
     * Java equivalent of the COBOL guard
     * {@code IF DIS-INT-RATE NOT = 0}.
     *
     * <p>Used by the Spring Batch step orchestration to short-circuit
     * the interest-write path when the resolved group is ZEROAPR.
     * Exposed as a public seam so the unit test can verify the skip
     * semantics directly.
     *
     * @param rate the disclosure-group rate; {@code null} returns {@code false}
     *             (null is treated as "unknown/uninitialised", not as zero)
     * @return {@code true} if {@code rate} is exactly zero; {@code false}
     *         if {@code rate} is null or non-zero
     */
    public boolean isZeroAprGroup(BigDecimal rate) {
        return rate != null && rate.signum() == 0;
    }
}
