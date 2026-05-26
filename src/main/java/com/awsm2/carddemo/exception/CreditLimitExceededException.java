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
 * Thrown when a transaction would cause the account balance to exceed the
 * credit limit.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 422 Unprocessable Entity</b> per AAP &sect;0.4.1 ("Maps to 422
 * Unprocessable Entity"). HTTP status mapping is centralized in the
 * {@code GlobalExceptionHandler} as the single source of truth; this class
 * deliberately does NOT carry a {@code @ResponseStatus} annotation, matching
 * the pattern established by {@link CardDemoException} and the other typed
 * domain exceptions in this package.</p>
 *
 * <p><b>Business-rule semantics:</b> This is a business-rule violation, not
 * a system error. The transaction is rejected but the system state remains
 * valid. No partial writes are performed, no compensating action is
 * required, and no operator intervention is needed beyond informing the
 * caller that the requested transaction was over the cardholder's credit
 * line.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces COBOL reject code {@code 102}
 * ("OVERLIMIT TRANSACTION") emitted by the {@code 1500-B-LOOKUP-ACCT}
 * validation paragraph in {@code app/cbl/CBTRN02C.cbl} lines
 * 407&ndash;413:</p>
 *
 * <pre>{@code
 * COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
 *                     - ACCT-CURR-CYC-DEBIT
 *                     + DALYTRAN-AMT
 *
 * IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
 *   CONTINUE
 * ELSE
 *   MOVE 102 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'OVERLIMIT TRANSACTION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * }</pre>
 *
 * <p>Where {@code WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT
 * + DALYTRAN-AMT} (lines 403&ndash;405) is the projected cycle balance
 * after applying the incoming transaction amount. When the projected
 * balance exceeds {@code ACCT-CREDIT-LIMIT}, the COBOL program writes the
 * transaction to {@code DALYREJS-FILE} (the daily rejections sequential
 * dataset, replaced in the Java target by S3 versioned objects under
 * {@code s3://${S3_OUTPUT_BUCKET}/...} via the
 * {@code com.awsm2.carddemo.adapter.S3OutputService} adapter) carrying the
 * reject code {@code 102} and the verbatim description
 * {@code "OVERLIMIT TRANSACTION"}. Downstream regulatory and operations
 * systems consume that rejection file, so the reject code AND the
 * description MUST be preserved character-for-character per AAP
 * &sect;0.7.2 ("Error codes and condition handling surfaced to downstream
 * consumers must be preserved verbatim").</p>
 *
 * <p><b>Verbatim-preservation note (AAP &sect;0.7.2):</b> Both the
 * {@link #REJECT_CODE} constant ({@code "102"}) and the
 * {@link #REJECT_DESCRIPTION} constant ({@code "OVERLIMIT TRANSACTION"})
 * are preserved exactly as they appear in {@code app/cbl/CBTRN02C.cbl}
 * lines 410&ndash;411, including any spacing or case quirks. These
 * constants are intentionally {@code public static final} so that other
 * parts of the codebase (services, validators, tests, and the
 * {@code S3OutputService} writer that emits the {@code DALYREJS} rejection
 * file) reference them by name &mdash; this avoids hardcoding
 * {@code "102"} and {@code "OVERLIMIT TRANSACTION"} in multiple locations
 * and makes the AAP &sect;0.7.2 verbatim-preservation guarantee
 * enforceable: any future change to the constant breaks the build for
 * every reference site, surfacing the violation before it can reach
 * downstream consumers.</p>
 *
 * <p><b>Companion typed exception:</b> The sibling reject code
 * {@code 103} ("TRANSACTION RECEIVED AFTER ACCT EXPIRATION") emitted
 * immediately after this check in the same {@code 1500-B-LOOKUP-ACCT}
 * paragraph (lines 414&ndash;420 of {@code app/cbl/CBTRN02C.cbl}) is
 * surfaced via {@link ExpiredCardException}. Together with
 * {@link RecordNotFoundException} (reject codes {@code 100}, {@code 101},
 * {@code 109}), these typed exceptions cover the entire
 * {@code WS-VALIDATION-FAIL-REASON} family ({@code 100}&ndash;{@code 109})
 * documented in {@code app/cbl/CBTRN02C.cbl}.</p>
 *
 * <p><b>BigDecimal arithmetic alignment:</b> The Java translation of the
 * COBOL {@code WS-TEMP-BAL} computation uses
 * {@link java.math.BigDecimal} with
 * {@link java.math.RoundingMode#HALF_EVEN} (banker's rounding) per AAP
 * &sect;0.6.1 to exactly replicate COBOL {@code PIC S9(10)V99}
 * fixed-point decimal arithmetic. The credit-limit comparison is performed
 * via {@code BigDecimal.compareTo(...)} rather than {@code equals(...)} to
 * avoid scale-sensitivity issues. The verbatim reject code {@code "102"}
 * is dispatched regardless of which side of the {@code >=} comparison
 * fails, matching the COBOL {@code IF / ELSE} branch semantics.</p>
 *
 * <p><b>Usage example:</b></p>
 *
 * <pre>{@code
 * // COBOL: CBTRN02C.cbl 1500-B-LOOKUP-ACCT line 410
 * //   MOVE 102 TO WS-VALIDATION-FAIL-REASON
 * BigDecimal tempBalance = account.getCurrCycCredit()
 *     .subtract(account.getCurrCycDebit())
 *     .add(transaction.getAmount());
 * if (account.getCreditLimit().compareTo(tempBalance) < 0) {
 *     throw new CreditLimitExceededException();
 * }
 * }</pre>
 *
 * <p>The {@linkplain #CreditLimitExceededException() no-arg constructor}
 * is the preferred form because it produces the exact verbatim COBOL
 * output with zero ambiguity. Custom messages via the other constructors
 * are rare and should be used only when a specific contextual diagnostic
 * message is required for an operational log scenario that does NOT feed
 * the {@code DALYREJS} rejection file.</p>
 *
 * <p><b>Exception propagation:</b> Per AAP &sect;0.7.1 (layered
 * architecture), services THROW this exception; the
 * {@code GlobalExceptionHandler} catches it via
 * {@code @RestControllerAdvice}. Controllers do NOT catch this exception
 * &mdash; they let it propagate to the handler. This preserves the
 * layered architecture mandate from AAP &sect;0.7.1 and ensures a single
 * source of truth for HTTP status mapping and error envelope shape.</p>
 *
 * <p><b>PII discipline:</b> Per AAP &sect;0.6.6 (Cross-Cutting: Audit,
 * Observability, and PCI-DSS), the exception {@code message} MUST NOT
 * contain sensitive financial data (full card numbers, CVVs, credit
 * limits, current balances, or any credential material). The verbatim
 * COBOL description {@code "OVERLIMIT TRANSACTION"} is intentionally
 * generic and safe to surface in the JSON error envelope, in CloudWatch
 * Logs, and in the OpenSearch audit index. Callers that supply a custom
 * message via the {@link #CreditLimitExceededException(String)} or
 * {@link #CreditLimitExceededException(String, Throwable)} constructor
 * are responsible for ensuring no PII is included in that message.</p>
 *
 * <p><b>Immutability and thread-safety:</b> Like {@link CardDemoException},
 * this class adds no mutable instance state. The single inherited
 * {@code reasonCode} field is {@code final}. All instances are
 * effectively immutable and safe to share across threads.</p>
 *
 * @see CardDemoException
 * @see ExpiredCardException
 * @see RecordNotFoundException
 * @see GlobalExceptionHandler
 */
public class CreditLimitExceededException extends CardDemoException {

    /**
     * Serialization version identifier. {@link CardDemoException} extends
     * {@link RuntimeException} which implements {@link java.io.Serializable};
     * declaring this constant suppresses the compiler-generated warning and
     * stabilizes the wire format for any future cross-process propagation.
     * The initial value of {@code 1L} reflects this class's first stable
     * layout.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Verbatim COBOL reject code from {@code app/cbl/CBTRN02C.cbl} line 410
     * ({@code MOVE 102 TO WS-VALIDATION-FAIL-REASON}).
     *
     * <p>This is a three-character string (not a number) preserved as-is
     * from the COBOL {@code WS-VALIDATION-FAIL-REASON} value. Downstream
     * regulatory and operations systems that consume the
     * {@code DALYREJS-FILE} rejection output (and now consume the S3
     * versioned-object equivalent emitted by {@code S3OutputService})
     * expect this exact value; altering it &mdash; including by stripping
     * leading zeroes, zero-padding, parsing to an integer, or any other
     * transformation &mdash; breaks AAP &sect;0.7.2 ("Error codes and
     * condition handling surfaced to downstream consumers must be
     * preserved verbatim").</p>
     *
     * <p>Used by all four {@code CreditLimitExceededException} constructors
     * except the
     * {@link #CreditLimitExceededException(String, String) reasonCode+message}
     * constructor, which permits the caller to override this default when
     * a more specific code applies. Propagated by
     * {@code GlobalExceptionHandler} into the {@code code} property of the
     * standardized JSON error envelope defined in AAP &sect;0.3.4.</p>
     */
    public static final String REJECT_CODE = "102";

    /**
     * Verbatim COBOL reject description from
     * {@code app/cbl/CBTRN02C.cbl} line 411
     * ({@code MOVE 'OVERLIMIT TRANSACTION' TO WS-VALIDATION-FAIL-REASON-DESC}).
     *
     * <p>Preserved character-for-character including case, spacing, and any
     * abbreviations so that downstream systems consuming the
     * {@code DALYREJS-FILE} rejection output can match on the exact string.
     * Per AAP &sect;0.7.2 this value MUST NOT be modified (no
     * normalization, no internationalization, no sentence-casing)
     * regardless of how Java conventions might otherwise format such a
     * message.</p>
     *
     * <p>Used by the no-arg {@link #CreditLimitExceededException()}
     * constructor as the default exception message. Propagated by
     * {@code GlobalExceptionHandler} into the {@code message} property of
     * the standardized JSON error envelope defined in AAP &sect;0.3.4.</p>
     */
    public static final String REJECT_DESCRIPTION = "OVERLIMIT TRANSACTION";

    /**
     * Constructs a {@code CreditLimitExceededException} with the default
     * COBOL reject code {@code "102"} and the default verbatim description
     * {@code "OVERLIMIT TRANSACTION"}.
     *
     * <p>This is the preferred form: it produces the verbatim COBOL output
     * with zero ambiguity and is the canonical invocation throughout the
     * transaction posting service. Use this constructor whenever the
     * credit-limit check from
     * {@code app/cbl/CBTRN02C.cbl} {@code 1500-B-LOOKUP-ACCT} (lines
     * 407&ndash;413) fails &mdash; that is, whenever the projected cycle
     * balance {@code (ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
     * DALYTRAN-AMT)} exceeds {@code ACCT-CREDIT-LIMIT}.</p>
     *
     * <p>Calling this no-arg constructor results in:</p>
     * <ul>
     *   <li>{@link #getReasonCode()} returning {@code "102"} (the verbatim
     *       COBOL reject code from line 410).</li>
     *   <li>{@link Throwable#getMessage() getMessage()} returning
     *       {@code "OVERLIMIT TRANSACTION"} (the verbatim COBOL reject
     *       description from line 411).</li>
     * </ul>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: CBTRN02C.cbl 1500-B-LOOKUP-ACCT line 410
     * //   MOVE 102 TO WS-VALIDATION-FAIL-REASON
     * BigDecimal tempBalance = account.getCurrCycCredit()
     *     .subtract(account.getCurrCycDebit())
     *     .add(transaction.getAmount());
     * if (account.getCreditLimit().compareTo(tempBalance) < 0) {
     *     throw new CreditLimitExceededException();
     * }
     * }</pre>
     */
    public CreditLimitExceededException() {
        super(REJECT_CODE, REJECT_DESCRIPTION);
    }

    /**
     * Constructs a {@code CreditLimitExceededException} with the supplied
     * message and the default COBOL reject code {@link #REJECT_CODE}
     * ({@code "102"}).
     *
     * <p>Even when a caller wants a custom message (e.g., adding the
     * transaction ID or account ID for operational context), the reject
     * code remains {@code "102"} because this exception, <i>by
     * definition</i>, IS COBOL reject code {@code 102}. The
     * {@code reasonCode} accessor on the inherited
     * {@link CardDemoException#getReasonCode()} will always return
     * {@code "102"} for instances created via this constructor &mdash;
     * preserving the AAP &sect;0.7.2 verbatim-preservation guarantee
     * regardless of what the human-readable message says.</p>
     *
     * <p>Note that the message supplied here does NOT replace
     * {@link #REJECT_DESCRIPTION} in the {@code DALYREJS} rejection file
     * output &mdash; the {@code S3OutputService} writer always emits the
     * verbatim description to that file regardless of this exception's
     * message. Custom messages affect only the JSON error envelope returned
     * to the REST API caller and the CloudWatch/OpenSearch log entries.</p>
     *
     * <p><b>PII discipline:</b> Per AAP &sect;0.6.6, the {@code message}
     * MUST NOT contain sensitive financial data (full card numbers,
     * CVVs, current balances, credit limits, or any credential material).
     * Transaction IDs and account IDs are acceptable for operational
     * diagnostics.</p>
     *
     * @param message a human-readable description supplementing the
     *                verbatim COBOL reject message
     */
    public CreditLimitExceededException(String message) {
        super(REJECT_CODE, message);
    }

    /**
     * Constructs a {@code CreditLimitExceededException} with the supplied
     * message, a wrapped cause, and the default COBOL reject code
     * {@link #REJECT_CODE} ({@code "102"}).
     *
     * <p>Use this constructor when a lower-level exception (for example,
     * a {@code java.lang.ArithmeticException} surfaced from a
     * {@link java.math.BigDecimal} operation during the
     * {@code WS-TEMP-BAL} computation, or an
     * {@code org.springframework.dao.DataAccessException} surfaced from
     * Spring Data JPA during the account lookup that immediately precedes
     * the credit-limit check) must be adapted into the domain exception
     * hierarchy. Preserving the cause keeps the original stack trace
     * available for root-cause analysis in CloudWatch and OpenSearch log
     * indexes (AAP &sect;0.6.6).</p>
     *
     * <p>The reject code remains {@code "102"} regardless of the wrapped
     * cause &mdash; this exception always represents COBOL reject reason
     * {@code 102} per AAP &sect;0.7.2.</p>
     *
     * @param message a human-readable description supplementing the
     *                verbatim COBOL reject message
     * @param cause   the underlying exception being wrapped; preserves the
     *                stack trace. May be {@code null}.
     */
    public CreditLimitExceededException(String message, Throwable cause) {
        super(REJECT_CODE, message, cause);
    }

    /**
     * Constructs a {@code CreditLimitExceededException} with an explicit
     * reason code and message.
     *
     * <p>This constructor exists for completeness and to support exception
     * chaining patterns that need to override the default {@code "102"}
     * reject code. It should rarely be used in practice &mdash; this
     * exception class IS COBOL reject reason {@code 102} by definition,
     * and the no-arg or message-only constructors will produce the
     * canonical {@code "102"} reason code automatically.</p>
     *
     * <p>The reason code is preserved verbatim per AAP &sect;0.7.2
     * ("Error codes and condition handling surfaced to downstream
     * consumers must be preserved verbatim") so that downstream systems
     * consuming the JSON error envelope can branch on the exact value.</p>
     *
     * <p>The calling service is expected to include an inline comment
     * naming the COBOL program, paragraph, and reject code per the AAP
     * &sect;0.7.3 traceability requirement
     * (e.g., {@code // COBOL: CBTRN02C.cbl 1500-B-LOOKUP-ACCT &mdash;
     * reject reason 102}).</p>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (typically {@code "102"} but may be any
     *                   custom code applicable to the calling context);
     *                   may be {@code null}, in which case
     *                   {@code GlobalExceptionHandler} substitutes a
     *                   default code
     * @param message    a human-readable description of the over-limit
     *                   condition
     */
    public CreditLimitExceededException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
