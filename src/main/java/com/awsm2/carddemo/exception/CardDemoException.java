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
 * Base unchecked exception for the CardDemo domain. All typed CardDemo exceptions
 * (RecordNotFoundException, DuplicateRecordException, ConcurrentModificationException,
 * CreditLimitExceededException, ExpiredCardException, ValidationException,
 * OnSizeErrorException) extend this class.
 *
 * <p>Carries an optional {@code reasonCode} field that preserves the original COBOL
 * {@code RETURN-CODE}, {@code WS-VALIDATION-FAIL-REASON}, or {@code FILE STATUS} value
 * verbatim, per AAP &sect;0.7.2 ("Error codes and condition handling surfaced to
 * downstream consumers must be preserved verbatim").</p>
 *
 * <p>Mapped by {@code GlobalExceptionHandler} to HTTP 500 Internal Server Error when no
 * specific subclass handler matches. HTTP status mapping is centralized in the
 * {@code GlobalExceptionHandler} as the single source of truth; this class deliberately
 * does NOT carry a {@code @ResponseStatus} annotation per the standardized error
 * response envelope defined in AAP &sect;0.3.4.</p>
 *
 * <p>This is an UNCHECKED exception ({@code extends RuntimeException}) per AAP
 * &sect;0.7.1. Service and controller methods do NOT need to declare
 * {@code throws CardDemoException} in their signatures. The {@code GlobalExceptionHandler}
 * catches and translates these exceptions to HTTP responses per the standardized
 * envelope defined in AAP &sect;0.3.4. Spring Data JPA, Spring Web MVC, and Spring Batch
 * all expect unchecked exceptions for declarative transaction rollback behavior.</p>
 *
 * <p>COBOL provenance: replaces the COBOL {@code RETURN-CODE} return-code mechanism
 * (e.g., {@code MOVE 4 TO RETURN-CODE} in
 * {@code app/cbl/CBTRN02C.cbl}), the {@code 9999-ABEND-PROGRAM} paragraph that calls
 * LE service {@code CEE3ABD} for controlled abends, and the
 * {@code WS-VALIDATION-FAIL-REASON} value propagation (codes 100, 101, 102, 103, 109)
 * documented in {@code app/cbl/CBTRN02C.cbl}. The {@code SYNCPOINT ROLLBACK} path in
 * {@code app/cbl/COACTUPC.cbl} ultimately surfaces as a typed subclass of this
 * exception (see {@code ConcurrentModificationException}).</p>
 *
 * <p>The {@code reasonCode} accessor is propagated by {@code GlobalExceptionHandler}
 * into the {@code ApiResponse.code} property of the JSON error envelope, ensuring the
 * COBOL-equivalent reason codes reach downstream consumers unchanged.</p>
 *
 * @see com.awsm2.carddemo.exception.RecordNotFoundException
 * @see com.awsm2.carddemo.exception.DuplicateRecordException
 * @see com.awsm2.carddemo.exception.ConcurrentModificationException
 * @see com.awsm2.carddemo.exception.CreditLimitExceededException
 * @see com.awsm2.carddemo.exception.ExpiredCardException
 * @see com.awsm2.carddemo.exception.ValidationException
 * @see com.awsm2.carddemo.exception.OnSizeErrorException
 * @see com.awsm2.carddemo.exception.GlobalExceptionHandler
 */
public class CardDemoException extends RuntimeException {

    /**
     * Serialization version identifier. {@link RuntimeException} implements
     * {@link java.io.Serializable}; declaring this constant suppresses the
     * compiler-generated warning and stabilizes the wire format for any future
     * cross-process propagation. The initial value of {@code 1L} reflects this
     * class's first stable layout.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Optional COBOL-equivalent reason code. May be:
     * <ul>
     *   <li>A FILE STATUS value (e.g., {@code "22"} for DUPKEY, {@code "23"}
     *       for NOTFND) &mdash; see {@code FILE-STATUS} clauses in
     *       {@code app/cbl/CBTRN02C.cbl}.</li>
     *   <li>A {@code WS-VALIDATION-FAIL-REASON} value (e.g., {@code "100"},
     *       {@code "101"}, {@code "102"}, {@code "103"}, {@code "109"})
     *       &mdash; see line 181 of {@code app/cbl/CBTRN02C.cbl}.</li>
     *   <li>A semantic identifier (e.g., {@code "VALIDATION"},
     *       {@code "ARITHMETIC_OVERFLOW"}, {@code "CONFLICT"},
     *       {@code "INTERNAL_ERROR"}).</li>
     *   <li>{@code null} if the exception has no specific reason code; the
     *       {@code GlobalExceptionHandler} substitutes a default code in that
     *       case.</li>
     * </ul>
     *
     * <p>This field is {@code final} &mdash; once set at construction it cannot
     * be mutated. There is intentionally no setter.</p>
     */
    private final String reasonCode;

    /**
     * Constructs a {@code CardDemoException} with a message and no reason code.
     * Use this constructor for system-level errors that do not have a COBOL
     * {@code RETURN-CODE} / {@code FILE STATUS} counterpart; the
     * {@code GlobalExceptionHandler} will fall back to a default semantic code
     * (e.g., {@code "INTERNAL_ERROR"}) when emitting the response envelope.
     *
     * @param message a human-readable description of the error condition; should
     *                never be {@code null} (every CardDemoException carries
     *                diagnostic context)
     */
    public CardDemoException(String message) {
        super(message);
        this.reasonCode = null;
    }

    /**
     * Constructs a {@code CardDemoException} with a message and a wrapped cause.
     * The cause's stack trace is preserved on the resulting exception, enabling
     * full root-cause analysis in CloudWatch and OpenSearch log indexes.
     *
     * @param message a human-readable description of the error condition
     * @param cause   the underlying exception being wrapped (preserves stack
     *                trace); may be {@code null} when no underlying cause exists
     */
    public CardDemoException(String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = null;
    }

    /**
     * Constructs a {@code CardDemoException} with a reason code and message. The
     * {@code reasonCode} preserves the original COBOL {@code RETURN-CODE},
     * {@code FILE STATUS}, or {@code WS-VALIDATION-FAIL-REASON} value verbatim
     * per AAP &sect;0.7.2 ("Error codes and condition handling surfaced to
     * downstream consumers must be preserved verbatim").
     *
     * @param reasonCode the verbatim COBOL reason code (e.g., {@code "22"} for
     *                   DUPKEY, {@code "23"} for NOTFND, {@code "102"} for
     *                   credit-limit exceeded, {@code "103"} for card expired);
     *                   may be {@code null}
     * @param message    a human-readable description of the error condition
     */
    public CardDemoException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    /**
     * Constructs a {@code CardDemoException} with a reason code, message, and
     * wrapped cause. This is the most expressive constructor: it preserves the
     * COBOL reason code AND the underlying exception chain.
     *
     * @param reasonCode the verbatim COBOL reason code; may be {@code null}
     * @param message    a human-readable description of the error condition
     * @param cause      the underlying exception being wrapped (preserves stack
     *                   trace); may be {@code null}
     */
    public CardDemoException(String reasonCode, String message, Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode;
    }

    /**
     * Returns the reason code associated with this exception, or {@code null} if
     * none was provided. The reason code mirrors COBOL {@code RETURN-CODE} /
     * {@code WS-VALIDATION-FAIL-REASON} / {@code FILE STATUS} values verbatim
     * per AAP &sect;0.7.2 and is propagated by {@code GlobalExceptionHandler}
     * into the {@code ApiResponse.code} field of the standardized JSON error
     * envelope (AAP &sect;0.3.4).
     *
     * @return the reason code as a {@link String}, or {@code null} when no
     *         reason code was supplied at construction
     */
    public String getReasonCode() {
        return reasonCode;
    }
}
