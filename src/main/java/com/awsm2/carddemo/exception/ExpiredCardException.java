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
 * Thrown when a transaction is received after the account expiration date.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 422 Unprocessable Entity</b> per AAP &sect;0.4.1 ("Maps to 422
 * Unprocessable Entity"). HTTP status mapping is centralized in the
 * {@code GlobalExceptionHandler} as the single source of truth; this class
 * deliberately does NOT carry a {@code @ResponseStatus} annotation, matching
 * the pattern established by {@link CardDemoException} and the other typed
 * domain exceptions in this package.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces COBOL reject code {@code 103}
 * ("TRANSACTION RECEIVED AFTER ACCT EXPIRATION") emitted by the
 * {@code 1500-B-LOOKUP-ACCT} validation paragraph in
 * {@code app/cbl/CBTRN02C.cbl} lines 414&ndash;420:</p>
 *
 * <pre>{@code
 * IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
 *   CONTINUE
 * ELSE
 *   MOVE 103 TO WS-VALIDATION-FAIL-REASON
 *   MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
 *     TO WS-VALIDATION-FAIL-REASON-DESC
 * END-IF
 * }</pre>
 *
 * <p>When the COBOL check fails, the program subsequently writes the
 * transaction to {@code DALYREJS-FILE} (the daily rejections sequential
 * dataset, replaced in the Java target by S3 versioned objects under
 * {@code s3://${S3_OUTPUT_BUCKET}/...} via the
 * {@code com.awsm2.carddemo.adapter.S3OutputService} adapter) carrying the
 * reject code {@code 103} and the verbatim description
 * {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}. Downstream
 * regulatory and operations systems consume that rejection file, so the
 * reject code AND the description MUST be preserved character-for-character
 * per AAP &sect;0.7.2 ("Error codes and condition handling surfaced to
 * downstream consumers must be preserved verbatim").</p>
 *
 * <p><b>Verbatim-preservation note (AAP &sect;0.7.2):</b> The COBOL source
 * uses the misspelling {@code ACCT-EXPIRAION-DATE} (sic &mdash; missing the
 * letter {@code T}) on line 414. The Java JPA entity uses the corrected
 * spelling {@code expirationDate} on the {@code Account} domain object per
 * the Minimal Change Clause allowance for typo correction in domain naming
 * (AAP &sect;0.7.3). However, that typo-correction allowance does NOT
 * extend to the reject code value or the reject description text: both the
 * {@link #REJECT_CODE} constant ({@code "103"}) and the
 * {@link #REJECT_DESCRIPTION} constant
 * ({@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}) are preserved
 * exactly as they appear in {@code app/cbl/CBTRN02C.cbl} lines 417&ndash;419,
 * including any spacing or case quirks.</p>
 *
 * <p><b>Companion typed exception:</b> The sibling reject code
 * {@code 102} ("OVERLIMIT TRANSACTION") emitted immediately before this
 * check in the same {@code 1500-B-LOOKUP-ACCT} paragraph (lines 407&ndash;413
 * of {@code app/cbl/CBTRN02C.cbl}) is surfaced via
 * {@link CreditLimitExceededException}. Together with
 * {@link RecordNotFoundException} (reject codes {@code 100}, {@code 101},
 * {@code 109}), these typed exceptions cover the entire
 * {@code WS-VALIDATION-FAIL-REASON} family ({@code 100}&ndash;{@code 109})
 * documented in {@code app/cbl/CBTRN02C.cbl}.</p>
 *
 * <p><b>Usage example:</b></p>
 *
 * <pre>{@code
 * // COBOL: CBTRN02C.cbl 1500-B-LOOKUP-ACCT line 417
 * //   MOVE 103 TO WS-VALIDATION-FAIL-REASON
 * if (account.getExpirationDate().isBefore(transactionDate)) {
 *     throw new ExpiredCardException();
 * }
 * }</pre>
 *
 * <p>The {@linkplain #ExpiredCardException() no-arg constructor} is the
 * preferred form because it produces the exact verbatim COBOL output with
 * zero ambiguity. Custom messages via the other constructors are rare and
 * should be used only when a specific contextual diagnostic message is
 * required for an operational log scenario that does NOT feed the
 * {@code DALYREJS} rejection file.</p>
 *
 * <p><b>Exception propagation:</b> Per AAP &sect;0.7.1 (layered
 * architecture), services THROW this exception; the
 * {@code GlobalExceptionHandler} catches it via
 * {@code @RestControllerAdvice}. Controllers do NOT catch this exception
 * &mdash; they let it propagate to the handler. This preserves the layered
 * architecture mandate from AAP &sect;0.7.1 and ensures a single source of
 * truth for HTTP status mapping and error envelope shape.</p>
 *
 * <p><b>Immutability and thread-safety:</b> Like {@link CardDemoException},
 * this class adds no mutable instance state. The single inherited
 * {@code reasonCode} field is {@code final}. All instances are effectively
 * immutable and safe to share across threads.</p>
 *
 * @see CardDemoException
 * @see CreditLimitExceededException
 * @see RecordNotFoundException
 * @see GlobalExceptionHandler
 */
public class ExpiredCardException extends CardDemoException {

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
     * Verbatim COBOL reject code from {@code app/cbl/CBTRN02C.cbl} line 417
     * ({@code MOVE 103 TO WS-VALIDATION-FAIL-REASON}).
     *
     * <p>This is a three-character string (not a number) preserved as-is
     * from the COBOL {@code WS-VALIDATION-FAIL-REASON} value. Downstream
     * regulatory and operations systems that consume the
     * {@code DALYREJS-FILE} rejection output expect this exact value;
     * altering it &mdash; including by stripping leading zeroes,
     * zero-padding, parsing to an integer, or any other transformation
     * &mdash; breaks AAP &sect;0.7.2 ("Error codes and condition handling
     * surfaced to downstream consumers must be preserved verbatim").</p>
     *
     * <p>Used by all four {@code ExpiredCardException} constructors except
     * the {@link #ExpiredCardException(String, String) reasonCode+message}
     * constructor, which permits the caller to override this default when
     * a more specific code applies. Propagated by
     * {@code GlobalExceptionHandler} into the {@code code} property of the
     * standardized JSON error envelope defined in AAP &sect;0.3.4.</p>
     */
    public static final String REJECT_CODE = "103";

    /**
     * Verbatim COBOL reject description from {@code app/cbl/CBTRN02C.cbl}
     * lines 418&ndash;419
     * ({@code MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
     * TO WS-VALIDATION-FAIL-REASON-DESC}).
     *
     * <p>Preserved character-for-character including case, spacing, and any
     * abbreviations ({@code ACCT}, {@code EXPIRATION}) so that downstream
     * systems consuming the {@code DALYREJS-FILE} rejection output can
     * match on the exact string. Per AAP &sect;0.7.2 this value MUST NOT
     * be modified (no normalization, no internationalization, no
     * sentence-casing) regardless of how Java conventions might otherwise
     * format such a message.</p>
     *
     * <p>Used by the no-arg {@link #ExpiredCardException()} constructor as
     * the default exception message. Propagated by
     * {@code GlobalExceptionHandler} into the {@code message} property of
     * the standardized JSON error envelope defined in AAP &sect;0.3.4.</p>
     */
    public static final String REJECT_DESCRIPTION =
        "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    /**
     * Constructs an {@code ExpiredCardException} with the default COBOL
     * reject code {@code "103"} and the default verbatim description
     * {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}.
     *
     * <p>This is the preferred form: it produces the verbatim COBOL output
     * with zero ambiguity and is the canonical invocation throughout the
     * transaction posting service. Use this constructor whenever the
     * account expiration check from
     * {@code app/cbl/CBTRN02C.cbl} {@code 1500-B-LOOKUP-ACCT} fails.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: CBTRN02C.cbl 1500-B-LOOKUP-ACCT line 417
     * //   MOVE 103 TO WS-VALIDATION-FAIL-REASON
     * if (account.getExpirationDate().isBefore(transactionDate)) {
     *     throw new ExpiredCardException();
     * }
     * }</pre>
     */
    public ExpiredCardException() {
        super(REJECT_CODE, REJECT_DESCRIPTION);
    }

    /**
     * Constructs an {@code ExpiredCardException} with the supplied message
     * and the default COBOL reject code {@link #REJECT_CODE}
     * ({@code "103"}).
     *
     * <p>Use this constructor when an operational log message must convey
     * additional diagnostic context (e.g., the specific account ID, the
     * stored expiration date, and the incoming transaction timestamp) that
     * supplements the verbatim COBOL description. Note that the message
     * supplied here does NOT replace {@link #REJECT_DESCRIPTION} in the
     * {@code DALYREJS} rejection file output &mdash; the
     * {@code S3OutputService} writer always emits the verbatim description
     * to that file regardless of this exception's message.</p>
     *
     * <p><b>PII discipline:</b> Per AAP &sect;0.6.6, the {@code message}
     * MUST NOT contain sensitive financial data (full card numbers, CVVs,
     * credentials). Account IDs and expiration dates are acceptable for
     * operational diagnostics.</p>
     *
     * @param message a human-readable description supplementing the
     *                verbatim COBOL reject message
     */
    public ExpiredCardException(String message) {
        super(REJECT_CODE, message);
    }

    /**
     * Constructs an {@code ExpiredCardException} with the supplied message,
     * a wrapped cause, and the default COBOL reject code
     * {@link #REJECT_CODE} ({@code "103"}).
     *
     * <p>Use this constructor when a lower-level exception (for example,
     * a {@code DateTimeParseException} surfaced from
     * {@code DateValidationService} while normalizing
     * {@code DALYTRAN-ORIG-TS} or
     * {@code ACCT-EXPIRAION-DATE}) must be adapted into the domain
     * exception hierarchy. Preserving the cause keeps the original stack
     * trace available for root-cause analysis in CloudWatch and OpenSearch
     * log indexes (AAP &sect;0.6.6).</p>
     *
     * @param message a human-readable description supplementing the
     *                verbatim COBOL reject message
     * @param cause   the underlying exception being wrapped; preserves the
     *                stack trace. May be {@code null}.
     */
    public ExpiredCardException(String message, Throwable cause) {
        super(REJECT_CODE, message, cause);
    }

    /**
     * Constructs an {@code ExpiredCardException} with an explicit reason
     * code and message.
     *
     * <p>Use this constructor in the rare case where a more specific COBOL
     * {@code WS-VALIDATION-FAIL-REASON} value or a custom semantic
     * identifier applies instead of the default {@link #REJECT_CODE}
     * ({@code "103"}). The reason code is preserved verbatim per AAP
     * &sect;0.7.2 ("Error codes and condition handling surfaced to
     * downstream consumers must be preserved verbatim") so that downstream
     * systems consuming the JSON error envelope can branch on the exact
     * value.</p>
     *
     * <p>The calling service is expected to include an inline comment
     * naming the COBOL program, paragraph, and reject code per the AAP
     * &sect;0.7.3 traceability requirement
     * (e.g., {@code // COBOL: CBTRN02C.cbl 1500-B-LOOKUP-ACCT &mdash;
     * reject reason 103}).</p>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (typically {@code "103"} but may be any
     *                   custom code applicable to the calling context);
     *                   may be {@code null}, in which case
     *                   {@code GlobalExceptionHandler} substitutes a
     *                   default code
     * @param message    a human-readable description of the expiration
     *                   condition
     */
    public ExpiredCardException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
