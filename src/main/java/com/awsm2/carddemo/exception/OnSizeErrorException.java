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
package com.awsm2.carddemo.exception;

/**
 * Thrown when a {@link java.math.BigDecimal} arithmetic operation produces a
 * result whose magnitude (precision) exceeds the target column's declared
 * precision, replicating COBOL's {@code ON SIZE ERROR} clause on
 * {@code COMPUTE} / {@code ADD} / {@code SUBTRACT} / {@code MULTIPLY} /
 * {@code DIVIDE} arithmetic statements.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 500 Internal Server Error</b>. A {@code SIZE ERROR} is fundamentally
 * a system error: the financial application has produced a value it cannot
 * represent at the target precision. This is not a client-input failure and
 * is therefore deliberately surfaced as a 5xx response (not 4xx). HTTP status
 * mapping is centralized in the {@code GlobalExceptionHandler} as the single
 * source of truth; this class deliberately does NOT carry a
 * {@code @ResponseStatus} annotation, matching the pattern established by
 * {@link CardDemoException} and the other typed domain exceptions in this
 * package.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces the {@code ON SIZE ERROR} clause that
 * COBOL programmers attach to arithmetic statements when the receiving field's
 * {@code PIC} clause cannot hold the computed result. In COBOL the canonical
 * pattern is:</p>
 *
 * <pre>{@code
 * COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
 *   ON SIZE ERROR
 *       DISPLAY 'INTEREST COMPUTATION OVERFLOW'
 *       PERFORM 9999-ABEND-PROGRAM
 * END-COMPUTE.
 * }</pre>
 *
 * <p>Specific call sites in the migration (per AAP &sect;0.6.1 and the agent
 * prompt) include:</p>
 * <ul>
 *   <li>{@code app/cbl/CBACT04C.cbl} {@code 1300-COMPUTE-INTEREST} paragraph
 *       (line 462) &mdash; the monthly interest computation
 *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}. The Java counterpart in
 *       {@code InterestCalculationService} is
 *       {@code balance.multiply(rate).divide(BigDecimal.valueOf(1200), 2,
 *       RoundingMode.HALF_EVEN)} with a precision check against the
 *       {@code Account} entity's {@code @Column(precision = 12, scale = 2)}
 *       (PIC S9(10)V99).</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} {@code 2800-UPDATE-ACCOUNT-REC} paragraph
 *       (line 547) &mdash; {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL} that
 *       updates the account balance during the daily transaction posting
 *       pipeline. The Java counterpart in {@code TransactionPostingService}
 *       must verify the post-addition balance fits within the
 *       {@code ACCT-CURR-BAL} {@code PIC S9(10)V99} precision before
 *       persisting.</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} lines 1079&ndash;1130 &mdash; account
 *       update {@code COMPUTE ACUP-NEW-CREDIT-LIMIT-N = ...} and
 *       {@code COMPUTE ACUP-NEW-CASH-CREDIT-LIMIT-N = ...} statements that
 *       recompute the new credit limit and cash credit limit during the
 *       interactive account-maintenance flow. The Java counterpart in
 *       {@code AccountUpdateService} performs the same recomputations using
 *       {@code BigDecimal} arithmetic with explicit precision validation.</li>
 *   <li>{@code app/cbl/COBIL00C.cbl} balance-update path (line 234) &mdash;
 *       {@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT} in the bill
 *       payment flow. The Java counterpart in {@code BillPaymentService}
 *       subtracts using {@code BigDecimal.subtract(...).setScale(2,
 *       RoundingMode.HALF_EVEN)} and validates against the target column
 *       precision.</li>
 * </ul>
 *
 * <p><b>Java implementation pattern (per AAP &sect;0.6.1):</b> Java services
 * MUST explicitly check {@link java.math.BigDecimal#precision()} after each
 * arithmetic operation and throw this exception when the result exceeds the
 * receiving column's declared precision. The canonical pattern is:</p>
 *
 * <pre>{@code
 * try {
 *     BigDecimal result = balance
 *         .multiply(rate)
 *         .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_EVEN);
 *     if (result.precision() > 12) {            // PIC S9(10)V99 → precision 12
 *         // COBOL: CBACT04C.cbl line 462 — COMPUTE WS-MONTHLY-INT
 *         //        (replaces ON SIZE ERROR clause)
 *         throw new OnSizeErrorException(
 *             "Interest computation overflow for account " + acctId);
 *     }
 *     return result;
 * } catch (ArithmeticException ex) {
 *     // BigDecimal.divide(...) with non-terminating decimal expansion, or
 *     // any other unexpected arithmetic failure, is wrapped as an overflow
 *     // condition so that downstream handling is uniform.
 *     throw new OnSizeErrorException(
 *         "Arithmetic error during interest calculation", ex);
 * }
 * }</pre>
 *
 * <p><b>Wrapped causes:</b> When a {@link java.lang.ArithmeticException} is
 * the trigger (for instance from {@code BigDecimal.divide} with non-terminating
 * decimal expansion and no {@code MathContext}, or division by zero), callers
 * SHOULD wrap the original via the {@code (String, Throwable)} or
 * {@code (String, String, Throwable)} constructor to preserve the stack trace
 * for root-cause analysis in CloudWatch and OpenSearch log indexes.</p>
 *
 * <p><b>Reason code policy:</b> Defaults to {@link #DEFAULT_REASON_CODE}
 * ({@code "ARITHMETIC_OVERFLOW"}). This is a SEMANTIC identifier &mdash; unlike
 * {@code DuplicateRecordException} which uses {@code "22"} (FILE STATUS) or
 * {@code CreditLimitExceededException} which uses {@code "102"}
 * ({@code WS-VALIDATION-FAIL-REASON}), the COBOL {@code ON SIZE ERROR} clause
 * does not have a single numeric reason code (it can fire on ANY arithmetic
 * statement). Callers MAY supply a more specific semantic reason code (for
 * example {@code "INTEREST_OVERFLOW"} or {@code "BALANCE_OVERFLOW"}) via the
 * {@code (String reasonCode, String message)} or
 * {@code (String reasonCode, String message, Throwable cause)} constructor to
 * help operations dashboards distinguish call sites without having to parse
 * the message text.</p>
 *
 * <p><b>Logging level:</b> The {@code GlobalExceptionHandler} logs this
 * exception at ERROR level (not WARN) because it signals that a financial
 * calculation produced an unexpected result that operations and engineering
 * MUST investigate. This is distinct from validation errors (WARN, since the
 * client supplied bad input) and from {@code ConcurrentModificationException}
 * (WARN, since concurrent modification is recoverable by retry).</p>
 *
 * <p><b>Per AAP &sect;0.7.1:</b> "Replicate COBOL {@code ON SIZE ERROR} with
 * explicit overflow checks in Java." Per AAP &sect;0.6.1: "Each arithmetic
 * operation is wrapped in a check against the configured precision, and an
 * {@code OnSizeErrorException} is thrown if the result would exceed the
 * column's {@code precision}."</p>
 *
 * @see CardDemoException
 * @see java.math.BigDecimal#precision()
 * @see java.lang.ArithmeticException
 */
public class OnSizeErrorException extends CardDemoException {

    /**
     * Serialization version identifier. {@link CardDemoException} (via
     * {@link RuntimeException}) implements {@link java.io.Serializable};
     * declaring this constant suppresses the compiler-generated warning and
     * stabilizes the wire format for any future cross-process propagation.
     * The initial value of {@code 1L} reflects this class's first stable
     * layout.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Default reason code identifying this exception as an arithmetic
     * overflow per AAP &sect;0.6.1. This is a SEMANTIC identifier (not a
     * COBOL FILE STATUS code or {@code WS-VALIDATION-FAIL-REASON} numeric
     * value) because the COBOL {@code ON SIZE ERROR} clause does not have a
     * single canonical reason code &mdash; it can fire on ANY arithmetic
     * statement.
     *
     * <p>Callers MAY override this default by using the
     * {@link #OnSizeErrorException(String, String)} or
     * {@link #OnSizeErrorException(String, String, Throwable)} constructor to
     * supply a more specific identifier (for example {@code "INTEREST_OVERFLOW"}
     * or {@code "BALANCE_OVERFLOW"}). The reason code is surfaced verbatim
     * into the {@code ApiResponse.code} field of the standardized JSON error
     * envelope per AAP &sect;0.3.4.</p>
     */
    public static final String DEFAULT_REASON_CODE = "ARITHMETIC_OVERFLOW";

    /**
     * Constructs an {@code OnSizeErrorException} with the default reason
     * code {@link #DEFAULT_REASON_CODE} and the supplied message. Use this
     * constructor at the throw site of an explicit precision check:
     *
     * <pre>{@code
     * if (result.precision() > 12) {
     *     // COBOL: CBACT04C.cbl line 462 — COMPUTE WS-MONTHLY-INT
     *     throw new OnSizeErrorException(
     *         "Interest computation overflow for account " + acctId);
     * }
     * }</pre>
     *
     * @param message human-readable description of the overflow condition;
     *                should include diagnostic context (account ID,
     *                computed value, target precision) so that operations
     *                can investigate without having to reconstruct state
     */
    public OnSizeErrorException(String message) {
        super(DEFAULT_REASON_CODE, message);
    }

    /**
     * Constructs an {@code OnSizeErrorException} with the default reason
     * code {@link #DEFAULT_REASON_CODE}, the supplied message, and a wrapped
     * cause. Use this constructor when an {@link java.lang.ArithmeticException}
     * (or other arithmetic failure) is the trigger:
     *
     * <pre>{@code
     * try {
     *     BigDecimal result = balance.multiply(rate)
     *         .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_EVEN);
     *     ...
     * } catch (ArithmeticException ex) {
     *     // COBOL: CBACT04C.cbl line 462 — ON SIZE ERROR fallback
     *     throw new OnSizeErrorException(
     *         "Arithmetic error during interest calculation", ex);
     * }
     * }</pre>
     *
     * <p>The cause's stack trace is preserved, enabling full root-cause
     * analysis in CloudWatch and OpenSearch.</p>
     *
     * @param message human-readable description of the overflow condition
     * @param cause   the underlying arithmetic exception being wrapped
     *                (typically {@link java.lang.ArithmeticException}); may
     *                be {@code null} when no underlying cause exists
     */
    public OnSizeErrorException(String message, Throwable cause) {
        super(DEFAULT_REASON_CODE, message, cause);
    }

    /**
     * Constructs an {@code OnSizeErrorException} with a caller-supplied
     * reason code and message. Use this constructor when the call site
     * has a more specific semantic identifier than the generic
     * {@link #DEFAULT_REASON_CODE}:
     *
     * <pre>{@code
     * throw new OnSizeErrorException(
     *     "INTEREST_OVERFLOW",
     *     "Interest computation overflow for account " + acctId);
     * }</pre>
     *
     * <p>The supplied reason code is propagated by
     * {@code GlobalExceptionHandler} into the {@code ApiResponse.code} field
     * of the standardized JSON error envelope (AAP &sect;0.3.4).</p>
     *
     * @param reasonCode a caller-supplied semantic identifier (for example
     *                   {@code "INTEREST_OVERFLOW"} or {@code "BALANCE_OVERFLOW"});
     *                   may be {@code null} to defer to the
     *                   {@code GlobalExceptionHandler}'s default
     * @param message    human-readable description of the overflow condition
     */
    public OnSizeErrorException(String reasonCode, String message) {
        super(reasonCode, message);
    }

    /**
     * Constructs an {@code OnSizeErrorException} with a caller-supplied
     * reason code, message, and wrapped cause. This is the most expressive
     * constructor: it preserves a specific semantic identifier AND the
     * underlying exception chain:
     *
     * <pre>{@code
     * try {
     *     BigDecimal result = a.multiply(b)
     *         .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_EVEN);
     *     ...
     * } catch (ArithmeticException ex) {
     *     throw new OnSizeErrorException(
     *         "INTEREST_OVERFLOW",
     *         "Arithmetic error during interest calculation",
     *         ex);
     * }
     * }</pre>
     *
     * @param reasonCode a caller-supplied semantic identifier; may be
     *                   {@code null}
     * @param message    human-readable description of the overflow condition
     * @param cause      the underlying arithmetic exception being wrapped
     *                   (typically {@link java.lang.ArithmeticException});
     *                   may be {@code null}
     */
    public OnSizeErrorException(String reasonCode, String message, Throwable cause) {
        super(reasonCode, message, cause);
    }
}
