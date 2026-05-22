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
 * Thrown when a keyed {@code READ} operation cannot locate a record by its key.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 404 Not Found</b> per AAP &sect;0.4.1 ("Maps VSAM NOTFND to 404 Not
 * Found"). HTTP status mapping is centralized in the
 * {@code GlobalExceptionHandler} as the single source of truth; this class
 * deliberately does NOT carry a {@code @ResponseStatus} annotation, matching
 * the pattern established by {@link CardDemoException} and the other typed
 * domain exceptions in this package.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces {@code FILE STATUS '23'} (NOTFND)
 * handling and CICS {@code INVALID KEY} clauses on every keyed {@code READ}
 * operation in the source codebase. Specific representative source sites
 * include:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} lines 380&ndash;392 &mdash;
 *       {@code READ XREF-FILE} with {@code INVALID KEY} branch that performs
 *       {@code MOVE 100 TO WS-VALIDATION-FAIL-REASON} (card-cross-reference
 *       lookup failure during batch transaction posting).</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} lines 393&ndash;400 &mdash;
 *       {@code READ ACCOUNT-FILE} with {@code INVALID KEY} branch that
 *       performs {@code MOVE 101 TO WS-VALIDATION-FAIL-REASON}
 *       (validation-time account lookup failure).</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} line 555 &mdash; {@code READ ACCOUNT-FILE}
 *       with {@code INVALID KEY} during the rewrite path that surfaces as
 *       reject reason {@code 109}.</li>
 *   <li>{@code app/cbl/CBTRN02C.cbl} line 475 &mdash; the
 *       {@code READ TCATBAL-FILE} with {@code INVALID KEY} (where {@code '23'}
 *       NOTFND is benign and triggers the "create new category balance" flow,
 *       so callers in that specific path do NOT throw this exception).</li>
 *   <li>{@code app/cbl/CBACT04C.cbl} line 436 &mdash;
 *       {@code IF DISCGRP-STATUS = '23'} (NOTFND triggers the
 *       {@code DEFAULT} disclosure-group fallback rather than an error; again,
 *       callers in that specific path do NOT throw this exception).</li>
 *   <li>{@code app/cbl/COACTVWC.cbl} (lines 741, 789, 839) &mdash; CICS
 *       {@code EXEC CICS READ} operations against the ACCTDAT, CUSTDAT, and
 *       CXACAIX files that branch on {@code DFHRESP(NOTFND)} to surface
 *       "account not found", "customer not found", or "cross-reference not
 *       found" feedback to the 3270 user.</li>
 *   <li>{@code app/cbl/COCRDSLC.cbl} (lines 755, 796) &mdash; CICS card
 *       detail lookup that branches on {@code DFHRESP(NOTFND)} when the
 *       supplied card number is not present in CARDDAT.</li>
 *   <li>{@code app/cbl/COTRN01C.cbl} (line 283) &mdash; CICS transaction
 *       detail lookup that branches on {@code DFHRESP(NOTFND)} when the
 *       supplied transaction ID is not present in TRANSACT.</li>
 *   <li>The same pattern applies in {@code app/cbl/COACTUPC.cbl},
 *       {@code app/cbl/COCRDUPC.cbl}, {@code app/cbl/COCRDLIC.cbl},
 *       {@code app/cbl/COTRN02C.cbl}, {@code app/cbl/COBIL00C.cbl},
 *       {@code app/cbl/COUSR02C.cbl}, and {@code app/cbl/COUSR03C.cbl} for
 *       every CICS-based keyed {@code READ}.</li>
 * </ul>
 *
 * <p><b>JPA bridge pattern:</b> Java target services use Spring Data JPA's
 * {@link java.util.Optional} return type and throw this exception via
 * {@link java.util.Optional#orElseThrow(java.util.function.Supplier)
 * orElseThrow(...)}. The bridge from a missing {@code Optional} to a typed
 * domain exception preserves the COBOL NOTFND semantic while remaining
 * idiomatic in Spring Boot:</p>
 *
 * <pre>{@code
 * // COBOL: COACTVWC.cbl — EXEC CICS READ FILE('ACCTDAT') ... RESP(WS-RESP-CD)
 * // followed by EVALUATE WS-RESP-CD WHEN DFHRESP(NOTFND).
 * Account account = accountRepository.findById(acctId)
 *     .orElseThrow(() -> new RecordNotFoundException(
 *         "Account " + acctId + " not found"));
 * }</pre>
 *
 * <p><b>Reason code semantics:</b> The {@linkplain #FILE_STATUS_NOTFND default
 * reason code} ({@code "23"}) is the verbatim COBOL {@code FILE STATUS} value
 * preserved per AAP &sect;0.7.2 ("Error codes and condition handling surfaced
 * to downstream consumers must be preserved verbatim"). Downstream consumers
 * of error responses can inspect {@code reasonCode.equals("23")} to detect
 * "record not found" without parsing the human-readable message text.</p>
 *
 * <p>Callers MAY override the default reason code via the {@code reasonCode}
 * constructor argument when a more specific COBOL
 * {@code WS-VALIDATION-FAIL-REASON} value applies, such as:</p>
 * <ul>
 *   <li>{@code "100"} &mdash; {@code app/cbl/CBTRN02C.cbl}
 *       {@code 1500-A-LOOKUP-XREF} (XREF / card cross-reference lookup
 *       failure during batch posting).</li>
 *   <li>{@code "101"} &mdash; {@code app/cbl/CBTRN02C.cbl}
 *       {@code 1500-B-LOOKUP-ACCT} (validation-time ACCOUNT lookup
 *       failure).</li>
 *   <li>{@code "109"} &mdash; {@code app/cbl/CBTRN02C.cbl} rewrite-path
 *       ACCOUNT lookup failure at line 555.</li>
 * </ul>
 *
 * <p>When passing these specific codes, the calling service is expected to
 * include an inline comment naming the COBOL program, paragraph, and reject
 * code per the AAP &sect;0.7.3 traceability requirement
 * ({@code // COBOL: CBTRN02C.cbl 1500-A-LOOKUP-XREF — reject reason 100}).</p>
 *
 * <p><b>PII discipline:</b> Per AAP &sect;0.6.6 (Cross-Cutting: Audit,
 * Observability, and PCI-DSS), the exception {@code message} MUST NOT contain
 * sensitive financial data. Echoing back an account ID or card number
 * supplied by the caller for "not found" feedback is acceptable; including
 * current balances, credit limits, SSNs, or any credential material is
 * forbidden. The {@code GlobalExceptionHandler} additionally enforces this
 * discipline by emitting only the COBOL reason code and a sanitized
 * message in the JSON error envelope.</p>
 *
 * <p><b>Exception propagation:</b> Per AAP &sect;0.7.1 (layered architecture),
 * services THROW this exception; the {@code GlobalExceptionHandler} catches
 * it via {@code @RestControllerAdvice}. Controllers do NOT catch this
 * exception &mdash; they let it propagate to the handler. This preserves the
 * layered architecture mandate from AAP &sect;0.7.1 and ensures a single
 * source of truth for HTTP status mapping and error envelope shape.</p>
 *
 * <p><b>Immutability and thread-safety:</b> Like {@link CardDemoException},
 * this class adds no mutable instance state. The single inherited
 * {@code reasonCode} field is {@code final}. All instances are effectively
 * immutable and safe to share across threads.</p>
 *
 * @see CardDemoException
 * @see DuplicateRecordException
 * @see ConcurrentModificationException
 * @see ValidationException
 */
public class RecordNotFoundException extends CardDemoException {

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
     * Default reason code corresponding to COBOL {@code FILE STATUS '23'}
     * (NOTFND).
     *
     * <p>The value is a two-character string (not a number) preserved verbatim
     * from the COBOL {@code FILE STATUS} clause syntax (e.g.,
     * {@code IF DISCGRP-STATUS = '23'} in {@code app/cbl/CBTRN02C.cbl} line
     * 481 and {@code app/cbl/CBACT04C.cbl} line 436). Preservation of the
     * literal {@code "23"} value is mandated by AAP &sect;0.7.2 ("Error codes
     * and condition handling surfaced to downstream consumers must be
     * preserved verbatim") so that downstream systems consuming this
     * application's JSON error envelope can branch on the reason code
     * without parsing the human-readable message.</p>
     *
     * <p>Callers who need to surface a more specific
     * {@code WS-VALIDATION-FAIL-REASON} value (e.g., {@code "100"},
     * {@code "101"}, or {@code "109"} from {@code app/cbl/CBTRN02C.cbl}) must
     * invoke the {@link #RecordNotFoundException(String, String)} or
     * {@link #RecordNotFoundException(String, String, Throwable)}
     * constructor and supply the specific code as the first argument.</p>
     */
    public static final String FILE_STATUS_NOTFND = "23";

    /**
     * Constructs a {@code RecordNotFoundException} with the supplied message
     * and the {@linkplain #FILE_STATUS_NOTFND default reason code}
     * ({@code "23"}, the verbatim COBOL {@code FILE STATUS} NOTFND value).
     *
     * <p>This is the most commonly used constructor across the Java target;
     * the canonical invocation site is {@link java.util.Optional#orElseThrow}
     * from a Spring Data JPA repository {@code findById(...)} or derived
     * query method that returns no result.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: COACTVWC.cbl — EXEC CICS READ FILE('ACCTDAT') with NOTFND branch
     * Account account = accountRepository.findById(acctId)
     *     .orElseThrow(() -> new RecordNotFoundException(
     *         "Account " + acctId + " not found"));
     * }</pre>
     *
     * @param message a human-readable description of the not-found condition.
     *                Should identify the entity type and (if supplied by the
     *                caller) the key value that failed to resolve. Must not
     *                contain PII beyond what the caller originally supplied
     *                per AAP &sect;0.6.6.
     */
    public RecordNotFoundException(String message) {
        super(FILE_STATUS_NOTFND, message);
    }

    /**
     * Constructs a {@code RecordNotFoundException} with the supplied message,
     * a wrapped cause, and the {@linkplain #FILE_STATUS_NOTFND default reason
     * code} ({@code "23"}).
     *
     * <p>Use this constructor when a lower-level exception (e.g., a Spring
     * Data {@code EmptyResultDataAccessException}, a JPA
     * {@code EntityNotFoundException}, or an AWS SDK
     * {@code NoSuchKeyException} surfaced by
     * {@code S3OutputService}) is being adapted to the domain exception
     * hierarchy. Preserving the cause keeps the original stack trace
     * available for root-cause analysis in CloudWatch and OpenSearch
     * log indexes (AAP &sect;0.6.6).</p>
     *
     * @param message a human-readable description of the not-found condition
     * @param cause   the underlying exception being wrapped; preserves the
     *                stack trace. May be {@code null}.
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(FILE_STATUS_NOTFND, message, cause);
    }

    /**
     * Constructs a {@code RecordNotFoundException} with an explicit reason
     * code and message.
     *
     * <p>Use this constructor when a more specific COBOL
     * {@code WS-VALIDATION-FAIL-REASON} value applies than the default
     * {@code "23"}, such as:</p>
     * <ul>
     *   <li>{@code "100"} &mdash; {@code app/cbl/CBTRN02C.cbl}
     *       {@code 1500-A-LOOKUP-XREF}: XREF / card cross-reference lookup
     *       failure during batch transaction posting.</li>
     *   <li>{@code "101"} &mdash; {@code app/cbl/CBTRN02C.cbl}
     *       {@code 1500-B-LOOKUP-ACCT}: validation-time ACCOUNT lookup
     *       failure.</li>
     *   <li>{@code "109"} &mdash; {@code app/cbl/CBTRN02C.cbl} rewrite-path
     *       ACCOUNT lookup failure at line 555.</li>
     * </ul>
     *
     * <p>The reason code is preserved verbatim per AAP &sect;0.7.2 ("Error
     * codes and condition handling surfaced to downstream consumers must be
     * preserved verbatim"). The calling service is expected to include an
     * inline comment naming the COBOL program, paragraph, and reject code
     * per the AAP &sect;0.7.3 traceability requirement.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: CBTRN02C.cbl 1500-A-LOOKUP-XREF — reject reason 100
     * CardCrossReference xref = cardCrossReferenceRepository
     *     .findById(cardNum)
     *     .orElseThrow(() -> new RecordNotFoundException(
     *         "100", "INVALID CARD NUMBER FOUND"));
     * }</pre>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (e.g., {@code "100"}, {@code "101"},
     *                   {@code "109"}); may be {@code null}, in which case
     *                   {@code GlobalExceptionHandler} substitutes a default
     *                   code
     * @param message    a human-readable description of the not-found
     *                   condition
     */
    public RecordNotFoundException(String reasonCode, String message) {
        super(reasonCode, message);
    }

    /**
     * Constructs a {@code RecordNotFoundException} with an explicit reason
     * code, a message, and a wrapped cause. This is the most expressive
     * constructor.
     *
     * <p>Use this constructor when both a specific COBOL
     * {@code WS-VALIDATION-FAIL-REASON} value AND an underlying exception
     * must be preserved. The cause's stack trace is preserved on the
     * resulting exception, enabling full root-cause analysis in CloudWatch
     * and OpenSearch log indexes (AAP &sect;0.6.6).</p>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (e.g., {@code "100"}, {@code "101"},
     *                   {@code "109"}); may be {@code null}
     * @param message    a human-readable description of the not-found
     *                   condition
     * @param cause      the underlying exception being wrapped; preserves
     *                   the stack trace. May be {@code null}.
     */
    public RecordNotFoundException(String reasonCode, String message, Throwable cause) {
        super(reasonCode, message, cause);
    }
}
