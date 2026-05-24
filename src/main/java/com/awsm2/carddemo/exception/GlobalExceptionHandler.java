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

import com.awsm2.carddemo.dto.ApiResponse;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Centralized exception-to-HTTP translation for the CardDemo REST API.
 *
 * <p>This {@code @RestControllerAdvice} acts as the single source of truth for
 * mapping every typed domain exception, every Spring framework exception, every
 * Spring Security exception, and every Spring/JPA data-access exception to the
 * standardized JSON error envelope defined in AAP &sect;0.3.4
 * ({@code {"code": "...", "message": "...", "fieldErrors": [...]}}). The DTO that
 * carries this envelope is {@link ApiResponse}; all factory methods used here
 * (notably {@link ApiResponse#error(String, String, String)} and
 * {@link ApiResponse#errorWithFieldErrors(String, String, List, String)}) live
 * on that record.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces the COBOL pattern of inspecting
 * {@code FILE STATUS} codes, {@code WS-RESP-CD} / {@code WS-REAS-CD} (CICS
 * {@code DFHRESP(...)} return codes), {@code WS-VALIDATION-FAIL-REASON}, and
 * {@code RETURN-CODE} after every file operation, then routing to error display
 * paragraphs ({@code 9910-DISPLAY-IO-STATUS}, {@code 9999-ABEND-PROGRAM} in
 * {@code app/cbl/CBTRN02C.cbl} and the analogous paragraphs in every other
 * COBOL program). In the Java target, services and adapters throw typed
 * exceptions and this handler catches them and emits an HTTP response. The
 * canonical source-side references are:</p>
 * <ul>
 *   <li>{@code app/cbl/CBTRN02C.cbl} lines 32&ndash;61 (FILE STATUS / FILE-STATUS
 *       clauses on FD definitions) and lines 385&ndash;420 (the
 *       {@code 1500-A-LOOKUP-XREF} and {@code 1500-B-LOOKUP-ACCT} validation
 *       paragraphs that emit reject reasons 100&ndash;109).</li>
 *   <li>{@code app/cbl/COACTUPC.cbl} lines 4076&ndash;4103 (the
 *       {@code 9500-WRITE-PROCESSING} {@code SYNCPOINT ROLLBACK} path on
 *       multi-record update failure).</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} lines 260&ndash;266 (the
 *       {@code WRITE-USER-SEC-FILE} {@code WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)}
 *       "User ID already exist..." branch).</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} {@code WRITE-TRANSACT-FILE} duplicate-key
 *       branch.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} {@code DATA-WAS-CHANGED-BEFORE-UPDATE}
 *       optimistic-lock branch.</li>
 * </ul>
 *
 * <p><b>Layered architecture:</b> Per AAP &sect;0.7.1, services throw typed
 * exceptions and controllers do NOT catch them; instead this
 * {@code @RestControllerAdvice} catches them globally. The handler is
 * positioned as the boundary between the service layer and the HTTP wire
 * protocol &mdash; it owns HTTP status mapping, log emission, correlation
 * identifier generation, and JSON envelope construction.</p>
 *
 * <p><b>Order matters:</b> Spring's exception resolver selects the
 * most-specific {@code @ExceptionHandler} for a given thrown type. Typed
 * subclasses (e.g., {@link RecordNotFoundException}, {@link DuplicateRecordException})
 * MUST appear before their base class ({@link CardDemoException}), which in
 * turn MUST appear before the final catch-all {@link Exception} handler. The
 * order in this file is informational (for readability and review) &mdash;
 * runtime selection is driven by Spring's resolver, not by file ordering.</p>
 *
 * <p><b>Correlation identifier propagation:</b> Each handler generates a fresh
 * {@link UUID} correlation identifier via {@link #generateCorrelationId()},
 * includes it in the structured log record, and surfaces it back to the API
 * caller in the {@code correlationId} property of the response envelope. This
 * allows operators to find the offending log entry from the API response and
 * stitch together CloudWatch, OpenSearch, and CloudTrail entries per AAP
 * &sect;0.6.6 (Cross-Cutting: Audit, Observability, and PCI-DSS).</p>
 *
 * <p><b>Logging discipline:</b> 4xx responses log at {@code WARN} level
 * (client error, not actionable for operations); 5xx responses log at
 * {@code ERROR} level with full stack trace (server error, actionable for
 * operations). Every log line includes the correlation identifier, the
 * request URI, the reason code, and the exception message.</p>
 *
 * <p><b>PII/PCI-DSS discipline:</b> Per AAP &sect;0.6.6 and &sect;0.7.2, this
 * handler MUST NOT echo card numbers, account numbers, CVVs, SSNs, expiration
 * dates, current balances, credit limits, or any credential material to the
 * caller. Default messages are intentionally generic; service-emitted messages
 * are the responsibility of the throwing site to keep PII-free. The
 * catch-all {@link #handleGenericException(Exception, HttpServletRequest)}
 * deliberately suppresses the underlying exception's message and emits
 * {@code "An unexpected error occurred"} to prevent stack-trace or internal
 * class-name leakage in the response body.</p>
 *
 * <p><b>Verbatim reason-code preservation:</b> Per AAP &sect;0.7.2, COBOL
 * {@code FILE STATUS} values (e.g., {@code "22"} DUPKEY, {@code "23"} NOTFND)
 * and {@code WS-VALIDATION-FAIL-REASON} values (100, 101, 102, 103, 109) are
 * surfaced verbatim through the {@code reasonCode} accessor on each domain
 * exception and propagated into the {@code code} field of the JSON envelope.
 * This guarantees that downstream consumers can branch on the reason code
 * without parsing human-readable message text.</p>
 *
 * <p><b>Single source of truth:</b> Per the AAP architectural design,
 * individual exception classes deliberately do NOT carry
 * {@code @ResponseStatus} annotations. All HTTP status mapping is centralized
 * here. This ensures consistent envelope generation, correlation ID emission,
 * and PCI-DSS compliance enforcement across every error path.</p>
 *
 * @see ApiResponse
 * @see CardDemoException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 * @see ConcurrentModificationException
 * @see CreditLimitExceededException
 * @see ExpiredCardException
 * @see ValidationException
 * @see OnSizeErrorException
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * SLF4J logger for this handler. Bound at class load time via
     * {@link LoggerFactory#getLogger(Class)}. Every {@code @ExceptionHandler}
     * method emits at least one log record so that operators can correlate
     * API errors with CloudWatch / OpenSearch entries (AAP &sect;0.6.6).
     */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Default constructor. Spring instantiates this bean during
     * {@code @RestControllerAdvice} auto-detection &mdash; no explicit
     * configuration is required beyond the standard component scan over
     * {@code com.awsm2.carddemo}.
     */
    public GlobalExceptionHandler() {
        // No-arg constructor; bean instantiation handled by Spring.
    }

    // =====================================================================
    // Domain exceptions — typed CardDemo exception subclasses.
    //
    // Order: most-specific subclasses first; CardDemoException base catches
    // any unhandled subclass; final Exception handler catches everything else.
    // =====================================================================

    /**
     * Handles {@link RecordNotFoundException} by returning HTTP 404 Not Found
     * with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code FILE STATUS '23'} (NOTFND)
     * handling on every keyed {@code READ} operation. Representative source
     * sites: {@code app/cbl/CBTRN02C.cbl} lines 380&ndash;392
     * ({@code 1500-A-LOOKUP-XREF} {@code INVALID KEY} branch &mdash;
     * {@code MOVE 100 TO WS-VALIDATION-FAIL-REASON}); lines 393&ndash;400
     * ({@code 1500-B-LOOKUP-ACCT} {@code INVALID KEY} branch &mdash;
     * {@code MOVE 101 TO WS-VALIDATION-FAIL-REASON}); line 555 (rewrite-path
     * NOTFND, reject reason 109). Also replaces CICS {@code DFHRESP(NOTFND)}
     * branches in {@code app/cbl/COACTVWC.cbl}, {@code app/cbl/COCRDSLC.cbl},
     * {@code app/cbl/COTRN01C.cbl}, etc.</p>
     *
     * <p>The {@code reasonCode} surfaced to the caller is preserved verbatim
     * from the {@code RecordNotFoundException} per AAP &sect;0.7.2 (defaults
     * to {@code "23"} via {@link RecordNotFoundException#FILE_STATUS_NOTFND},
     * but the calling service may override with {@code "100"}, {@code "101"},
     * or {@code "109"} for the specific COBOL reject reason).</p>
     *
     * @param ex      the {@link RecordNotFoundException} thrown by a service or
     *                adapter when a keyed lookup returns no result
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 404 and the standardized
     *         {@link ApiResponse} envelope
     */
    @ExceptionHandler(RecordNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleRecordNotFound(
            RecordNotFoundException ex, HttpServletRequest request) {
        // COBOL: replaces FILE STATUS '23' (NOTFND) and DFHRESP(NOTFND) handling
        // in app/cbl/CBTRN02C.cbl:395-400, app/cbl/COACTVWC.cbl, app/cbl/COCRDSLC.cbl,
        // and every other CICS-based keyed READ.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null) ? ex.getReasonCode() : "NOT_FOUND";
        LOG.warn("[{}] RecordNotFoundException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handles {@link DuplicateRecordException} by returning HTTP 409 Conflict
     * with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code FILE STATUS '22'} (DUPKEY)
     * handling on VSAM {@code WRITE} operations and CICS
     * {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} branches. Canonical
     * source sites: {@code app/cbl/COUSR01C.cbl} lines 260&ndash;266
     * ({@code WRITE-USER-SEC-FILE} &mdash; "User ID already exist...");
     * {@code app/cbl/COTRN02C.cbl} {@code WRITE-TRANSACT-FILE} duplicate-key
     * branch.</p>
     *
     * <p>The {@code reasonCode} defaults to {@code "22"}
     * ({@link DuplicateRecordException#FILE_STATUS_DUPKEY}) preserved
     * verbatim from the COBOL {@code FILE STATUS} clause per AAP
     * &sect;0.7.2.</p>
     *
     * @param ex      the {@link DuplicateRecordException} thrown by a service
     *                when a {@code WRITE}/{@code save(...)} hit a duplicate key
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 409 and the standardized
     *         {@link ApiResponse} envelope
     */
    @ExceptionHandler(DuplicateRecordException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateRecord(
            DuplicateRecordException ex, HttpServletRequest request) {
        // COBOL: replaces FILE STATUS '22' (DUPKEY) and DFHRESP(DUPKEY)/DFHRESP(DUPREC)
        // handling in app/cbl/COUSR01C.cbl:260-266 ("User ID already exist...") and
        // app/cbl/COTRN02C.cbl WRITE-TRANSACT-FILE duplicate-key branch.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null) ? ex.getReasonCode() : "DUPLICATE";
        LOG.warn("[{}] DuplicateRecordException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles {@link ConcurrentModificationException} (the CardDemo domain
     * exception, NOT {@link java.util.ConcurrentModificationException}) by
     * returning HTTP 409 Conflict with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces the before/after image comparison
     * pattern used by COBOL online update programs to detect concurrent
     * modification under CICS pseudo-conversational control. Canonical source
     * sites: {@code app/cbl/COACTUPC.cbl} lines 4076&ndash;4103 (the
     * {@code 9500-WRITE-PROCESSING} sequence where the customer rewrite fails
     * after the account rewrite succeeded, triggering
     * {@code EXEC CICS SYNCPOINT ROLLBACK}); and {@code app/cbl/COCRDUPC.cbl}
     * line 207 (the {@code DATA-WAS-CHANGED-BEFORE-UPDATE} 88-level condition
     * raised by {@code 9700-CHECK-CHANGE-IN-REC}).</p>
     *
     * <p>The {@code reasonCode} surfaced to the caller is preserved verbatim
     * from the exception (typical values: {@code "OPTIMISTIC_LOCK"},
     * {@code "DATA_CHANGED_BEFORE_UPDATE"}, {@code "LOCKED_BUT_UPDATE_FAILED"}).
     * When the exception was constructed without an explicit reason code,
     * the default {@code "CONFLICT"} is substituted.</p>
     *
     * @param ex      the domain {@link ConcurrentModificationException} thrown
     *                by a service when a {@code @Version}-protected entity
     *                was modified concurrently
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 409 and the standardized
     *         {@link ApiResponse} envelope
     */
    @ExceptionHandler(ConcurrentModificationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConcurrentModification(
            ConcurrentModificationException ex, HttpServletRequest request) {
        // COBOL: replaces before/after image comparison in
        // app/cbl/COACTUPC.cbl:4076-4103 (SYNCPOINT ROLLBACK on REWRITE failure)
        // and app/cbl/COCRDUPC.cbl DATA-WAS-CHANGED-BEFORE-UPDATE branch.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null) ? ex.getReasonCode() : "CONFLICT";
        LOG.warn("[{}] ConcurrentModificationException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles Spring/JPA {@link OptimisticLockingFailureException} by returning
     * HTTP 409 Conflict with the standardized envelope.
     *
     * <p>This is the Spring/JPA-native bridge: when a JPA {@code @Version}-annotated
     * entity fails its optimistic-lock check at flush/save time, Hibernate raises
     * {@link OptimisticLockingFailureException} (or its concrete subtype
     * {@code ObjectOptimisticLockingFailureException}). The handler translates
     * this Spring/JPA exception into the same HTTP 409 envelope as the domain
     * {@link ConcurrentModificationException}, per AAP &sect;0.4.1
     * ("Maps snapshot mismatch (JPA OptimisticLockException) to 409 Conflict").</p>
     *
     * <p><b>COBOL provenance:</b> Same as {@link #handleConcurrentModification}
     * &mdash; this handler exists so that services do not need to explicitly
     * bridge JPA exceptions; the bridge happens at the handler layer.</p>
     *
     * <p>This handler does NOT rethrow as a new exception; it directly emits
     * the response envelope. The default reason code is {@code "CONFLICT"};
     * the default message is a generic, PCI-safe string that does not echo
     * any of the JPA exception's internal details.</p>
     *
     * @param ex      the {@link OptimisticLockingFailureException} thrown by
     *                Hibernate on a {@code @Version} mismatch
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 409 and the standardized
     *         {@link ApiResponse} envelope
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleSpringOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        // COBOL: bridges Spring/JPA @Version conflict to the same 409 envelope
        // as the domain ConcurrentModificationException (see provenance there:
        // app/cbl/COACTUPC.cbl:4076-4103 and app/cbl/COCRDUPC.cbl
        // DATA-WAS-CHANGED-BEFORE-UPDATE).
        String correlationId = generateCorrelationId();
        String message = "Record was modified by another transaction; please retry with the latest version";
        LOG.warn("[{}] OptimisticLockingFailureException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), "CONFLICT", ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error("CONFLICT", message, correlationId);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles {@link CreditLimitExceededException} by returning HTTP 422
     * Unprocessable Entity with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code MOVE 102 TO
     * WS-VALIDATION-FAIL-REASON} / {@code 'OVERLIMIT TRANSACTION'} in
     * {@code app/cbl/CBTRN02C.cbl} lines 407&ndash;413 (the
     * {@code 1500-B-LOOKUP-ACCT} paragraph). The check is
     * {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} where {@code WS-TEMP-BAL =
     * ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT} &mdash;
     * the projected cycle balance after applying the incoming transaction.
     * When the projected balance exceeds the credit limit, COBOL emits the
     * verbatim reject code {@code "102"} and description
     * {@code "OVERLIMIT TRANSACTION"}.</p>
     *
     * <p>The {@code reasonCode} defaults to {@code "102"} (verbatim from
     * {@link CreditLimitExceededException#REJECT_CODE}) per AAP &sect;0.7.2.
     * The default message is the verbatim {@code "OVERLIMIT TRANSACTION"}
     * string from {@link CreditLimitExceededException#REJECT_DESCRIPTION}
     * when constructed with the no-arg constructor.</p>
     *
     * @param ex      the {@link CreditLimitExceededException} thrown by the
     *                transaction posting service when the credit-limit check
     *                fails
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 422 Unprocessable Entity and
     *         the standardized {@link ApiResponse} envelope
     */
    @ExceptionHandler(CreditLimitExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleCreditLimitExceeded(
            CreditLimitExceededException ex, HttpServletRequest request) {
        // COBOL: replaces MOVE 102 TO WS-VALIDATION-FAIL-REASON /
        // 'OVERLIMIT TRANSACTION' in app/cbl/CBTRN02C.cbl:410-413
        // (1500-B-LOOKUP-ACCT paragraph). Reject code preserved verbatim
        // per AAP §0.7.2.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null)
                ? ex.getReasonCode()
                : CreditLimitExceededException.REJECT_CODE;
        LOG.warn("[{}] CreditLimitExceededException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * Handles {@link ExpiredCardException} by returning HTTP 422 Unprocessable
     * Entity with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code MOVE 103 TO
     * WS-VALIDATION-FAIL-REASON} / {@code 'TRANSACTION RECEIVED AFTER ACCT
     * EXPIRATION'} in {@code app/cbl/CBTRN02C.cbl} lines 414&ndash;420 (the
     * {@code 1500-B-LOOKUP-ACCT} paragraph). The check is
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}. When the
     * incoming transaction timestamp post-dates the account expiration, COBOL
     * emits the verbatim reject code {@code "103"} and description
     * {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}.</p>
     *
     * <p>The {@code reasonCode} defaults to {@code "103"} (verbatim from
     * {@link ExpiredCardException#REJECT_CODE}) per AAP &sect;0.7.2.</p>
     *
     * @param ex      the {@link ExpiredCardException} thrown by the
     *                transaction posting service when the expiration check
     *                fails
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 422 Unprocessable Entity and
     *         the standardized {@link ApiResponse} envelope
     */
    @ExceptionHandler(ExpiredCardException.class)
    public ResponseEntity<ApiResponse<Object>> handleExpiredCard(
            ExpiredCardException ex, HttpServletRequest request) {
        // COBOL: replaces MOVE 103 TO WS-VALIDATION-FAIL-REASON /
        // 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION' in
        // app/cbl/CBTRN02C.cbl:417-420 (1500-B-LOOKUP-ACCT paragraph).
        // Reject code preserved verbatim per AAP §0.7.2.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null)
                ? ex.getReasonCode()
                : ExpiredCardException.REJECT_CODE;
        LOG.warn("[{}] ExpiredCardException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * Handles {@link ValidationException} by returning HTTP 400 Bad Request
     * with the standardized envelope. When the exception carries per-field
     * errors via {@link ValidationException#getFieldErrors()}, those errors
     * are projected into the {@code fieldErrors} property of the envelope per
     * AAP &sect;0.3.4.
     *
     * <p><b>COBOL provenance:</b> Replaces field validation cascades across
     * the online programs:</p>
     * <ul>
     *   <li>{@code app/cbl/COACTUPC.cbl} &mdash; account update validations
     *       (phone area code against the NANPA registry; state/ZIP-prefix
     *       check; SSN, date-of-birth, open/expiry/reissue date checks).</li>
     *   <li>{@code app/cbl/COTRN02C.cbl} &mdash; transaction-add validations
     *       (TRAN-AMT decimal format, card number format, date format,
     *       account ID presence, transaction type/category presence).</li>
     *   <li>{@code app/cpy/CSLKPCDY.cpy} &mdash; NANPA area codes, US
     *       state/territory abbreviations, valid state/ZIP-prefix
     *       combinations (ported to
     *       {@code com.awsm2.carddemo.validation.ValidationLookupService}).</li>
     * </ul>
     *
     * <p>The {@code reasonCode} defaults to {@code "VALIDATION"} (from
     * {@link ValidationException#DEFAULT_REASON_CODE}). The
     * {@code fieldErrors} list (which is never {@code null} &mdash; the
     * accessor returns {@link java.util.Collections#emptyList()} when empty)
     * is mapped to {@link ApiResponse.FieldError} entries via
     * {@link ApiResponse.FieldError#of(String, String, String)} (the 3-arg
     * factory that omits {@code rejectedValue} per PCI-DSS guidance, AAP
     * &sect;0.6.6).</p>
     *
     * @param ex      the {@link ValidationException} thrown by a service
     *                when field-level validation fails
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 400 Bad Request and the
     *         standardized {@link ApiResponse} envelope, including
     *         {@code fieldErrors} when the exception supplied any
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(
            ValidationException ex, HttpServletRequest request) {
        // COBOL: replaces field validation cascades in app/cbl/COACTUPC.cbl,
        // app/cbl/COTRN02C.cbl and the CSLKPCDY.cpy NANPA / state / ZIP
        // lookup tables.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null) ? ex.getReasonCode() : "VALIDATION";
        LOG.warn("[{}] ValidationException at {}: reasonCode={}, message={}, fieldErrorsCount={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage(),
                ex.getFieldErrors().size());
        ApiResponse<Object> body;
        if (!ex.getFieldErrors().isEmpty()) {
            // Map the exception's (field, message) pairs to the wire-shape
            // ApiResponse.FieldError(field, code, message, rejectedValue=null).
            // The PCI-safe 3-arg factory deliberately omits rejectedValue
            // per AAP §0.6.6 — services that throw ValidationException must
            // never carry sensitive rejected values in the exception payload.
            List<ApiResponse.FieldError> wireErrors = ex.getFieldErrors().stream()
                    .map(fe -> ApiResponse.FieldError.of(fe.field(), reasonCode, fe.message()))
                    .collect(Collectors.toList());
            body = ApiResponse.errorWithFieldErrors(reasonCode, ex.getMessage(), wireErrors, correlationId);
        } else {
            body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link OnSizeErrorException} by returning HTTP 500 Internal
     * Server Error with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Replaces {@code COMPUTE ... ON SIZE ERROR}
     * clauses across the codebase. Representative source sites:</p>
     * <ul>
     *   <li>{@code app/cbl/CBACT04C.cbl} {@code 1300-COMPUTE-INTEREST}
     *       (interest calculation overflow:
     *       {@code (TRAN-CAT-BAL * DIS-INT-RATE) / 1200}).</li>
     *   <li>{@code app/cbl/CBTRN02C.cbl} {@code 2800-UPDATE-ACCOUNT-REC}
     *       (account-balance update overflow:
     *       {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL}).</li>
     *   <li>{@code app/cbl/COACTUPC.cbl} balance-recompute overflow
     *       ({@code COMPUTE ACUP-NEW-CREDIT-LIMIT-N = ...}).</li>
     *   <li>{@code app/cbl/COBIL00C.cbl} bill payment balance update
     *       ({@code COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}).</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.6.1, every Java arithmetic operation on a monetary
     * value MUST be wrapped in a precision check; if the result would exceed
     * the column's declared precision, this exception is thrown. A
     * {@code SIZE ERROR} is fundamentally a system error (the financial
     * application produced a value it cannot represent), so the response is
     * a 5xx and the log level is {@code ERROR} with full stack trace per the
     * agent prompt's logging discipline.</p>
     *
     * <p>The {@code reasonCode} defaults to {@code "ARITHMETIC_OVERFLOW"}
     * (from {@link OnSizeErrorException#DEFAULT_REASON_CODE}); callers MAY
     * supply a more specific identifier (e.g., {@code "INTEREST_OVERFLOW"}
     * or {@code "BALANCE_OVERFLOW"}).</p>
     *
     * @param ex      the {@link OnSizeErrorException} thrown by a service
     *                when an arithmetic operation produced an out-of-range
     *                value
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 500 Internal Server Error and
     *         the standardized {@link ApiResponse} envelope
     */
    @ExceptionHandler(OnSizeErrorException.class)
    public ResponseEntity<ApiResponse<Object>> handleOnSizeError(
            OnSizeErrorException ex, HttpServletRequest request) {
        // COBOL: replaces COMPUTE ... ON SIZE ERROR clauses (e.g.,
        // app/cbl/CBACT04C.cbl line 462 — interest computation,
        // app/cbl/CBTRN02C.cbl line 547 — balance update,
        // app/cbl/COACTUPC.cbl lines 1079-1130 — credit-limit recompute,
        // app/cbl/COBIL00C.cbl line 234 — bill payment).
        // Explicit overflow detection per AAP §0.6.1.
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null)
                ? ex.getReasonCode()
                : OnSizeErrorException.DEFAULT_REASON_CODE;
        LOG.error("[{}] OnSizeErrorException at {}: reasonCode={}, message={}",
                correlationId, request.getRequestURI(), reasonCode, ex.getMessage(), ex);
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // =====================================================================
    // Spring framework exceptions — Bean Validation, JSON parse, request
    // binding, and routing failures. These are 4xx client-error responses
    // logged at WARN level.
    // =====================================================================

    /**
     * Handles {@link MethodArgumentNotValidException} thrown by Spring MVC
     * when Jakarta Bean Validation fails on an {@code @Valid @RequestBody}
     * DTO. Returns HTTP 400 Bad Request with the standardized envelope and a
     * populated {@code fieldErrors} list extracted from the binding result.
     *
     * <p><b>COBOL provenance:</b> Conceptually replaces the BMS field
     * validation feedback loop &mdash; in the COBOL terminal world, each
     * invalid field was highlighted via {@code DFHRED} attributes and an
     * error message string was placed in {@code ERRMSGO}. In the JSON REST
     * world, this handler projects per-field errors into the
     * {@code fieldErrors} array of the envelope so the caller can drive
     * equivalent field-level UI feedback.</p>
     *
     * <p>For each Spring {@link FieldError} in
     * {@link MethodArgumentNotValidException#getBindingResult()}, the handler
     * emits an {@link ApiResponse.FieldError} via the PCI-safe 3-arg factory
     * {@link ApiResponse.FieldError#of(String, String, String)} (omitting the
     * Spring-provided {@code rejectedValue} so that sensitive values such as
     * passwords are not echoed back per AAP &sect;0.6.6).</p>
     *
     * @param ex      the validation exception thrown by Spring
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 400 Bad Request and the
     *         standardized envelope with {@code fieldErrors} populated
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        // COBOL: conceptually replaces BMS field-level error highlighting
        // and ERRMSGO feedback (see app/bms/*.bms field validation flows).
        // Jakarta Bean Validation surfaces on @Valid @RequestBody DTOs.
        String correlationId = generateCorrelationId();
        // Map Spring's org.springframework.validation.FieldError (per binding
        // result) into the wire-shape ApiResponse.FieldError using the
        // PCI-safe 3-arg factory.
        List<FieldError> springErrors = ex.getBindingResult().getFieldErrors();
        List<ApiResponse.FieldError> wireErrors = springErrors.stream()
                .map(this::toFieldError)
                .collect(Collectors.toList());
        LOG.warn("[{}] MethodArgumentNotValidException at {}: fieldErrorsCount={}",
                correlationId, request.getRequestURI(), wireErrors.size());
        ApiResponse<Object> body = ApiResponse.errorWithFieldErrors(
                "VALIDATION", "Validation failed", wireErrors, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link ConstraintViolationException} raised when Jakarta Bean
     * Validation fails on a {@code @PathVariable} or {@code @RequestParam}
     * (as opposed to a {@code @RequestBody} DTO which fires
     * {@link MethodArgumentNotValidException}). Returns HTTP 400 Bad Request
     * with the standardized envelope and a populated {@code fieldErrors} list.
     *
     * <p>The handler iterates
     * {@link ConstraintViolationException#getConstraintViolations()} and maps
     * each {@link ConstraintViolation} to an {@link ApiResponse.FieldError}
     * using the property path as the {@code field}, the constraint annotation
     * simple name as the {@code code}, and the constraint message as the
     * {@code message}. The PCI-safe 3-arg factory omits the rejected value.</p>
     *
     * @param ex      the constraint violation exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 400 Bad Request and the
     *         standardized envelope with {@code fieldErrors} populated
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        // COBOL: conceptually replaces BMS field-level error highlighting
        // for path/query parameter validation (e.g., COACTVW account-ID
        // length and numeric checks; CSLKPCDY.cpy lookups for state/ZIP/area code).
        String correlationId = generateCorrelationId();
        List<ApiResponse.FieldError> wireErrors = ex.getConstraintViolations().stream()
                .map(this::toFieldError)
                .collect(Collectors.toList());
        LOG.warn("[{}] ConstraintViolationException at {}: violationsCount={}",
                correlationId, request.getRequestURI(), wireErrors.size());
        ApiResponse<Object> body = ApiResponse.errorWithFieldErrors(
                "VALIDATION", "Validation failed", wireErrors, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link HttpMessageNotReadableException} thrown when the request
     * body cannot be deserialized (malformed JSON, missing body, mismatched
     * input types, etc.). Returns HTTP 400 Bad Request with a sanitized
     * message that does NOT leak Jackson internal class names.
     *
     * <p><b>Sanitization rationale:</b> Per AAP &sect;0.6.6 PCI-DSS guidance,
     * the response body must not echo internal stack-trace or library class
     * names. {@link HttpMessageNotReadableException#getMostSpecificCause()}
     * frequently surfaces Jackson exception types in its message
     * (e.g., "Cannot deserialize value of type ..."). The handler substitutes
     * a generic message {@code "Request body could not be parsed as JSON"}
     * instead.</p>
     *
     * @param ex      the message-not-readable exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 400 Bad Request and the
     *         standardized envelope with a PCI-safe sanitized message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        // COBOL: replaces BMS terminal input parse errors that previously
        // surfaced as red-highlight feedback. In REST, malformed JSON
        // yields a sanitized 400 response.
        String correlationId = generateCorrelationId();
        LOG.warn("[{}] HttpMessageNotReadableException at {}: cause={}",
                correlationId, request.getRequestURI(),
                ex.getMostSpecificCause() != null
                        ? ex.getMostSpecificCause().getClass().getSimpleName()
                        : ex.getClass().getSimpleName());
        boolean typeMismatch = ex.getMostSpecificCause() instanceof MismatchedInputException;
        ApiResponse<Object> body = typeMismatch
                ? ApiResponse.error(
                        "TYPE_MISMATCH",
                        "Request body contains a JSON value with an incompatible type",
                        correlationId)
                : ApiResponse.error(
                        "MALFORMED_REQUEST",
                        "Request body could not be parsed as JSON",
                        correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link MissingServletRequestParameterException} thrown when a
     * required query parameter is absent. Returns HTTP 400 Bad Request with
     * the standardized envelope and a message identifying the missing
     * parameter by name and expected type.
     *
     * @param ex      the missing-parameter exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 400 Bad Request and the
     *         standardized envelope
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        // COBOL: replaces BMS field-level "missing input" feedback (e.g.,
        // mandatory account ID on COACTVW, mandatory tran ID on COTRN01).
        String correlationId = generateCorrelationId();
        String message = "Required parameter '" + ex.getParameterName()
                + "' of type " + ex.getParameterType() + " is missing";
        LOG.warn("[{}] MissingServletRequestParameterException at {}: parameter={}, type={}",
                correlationId, request.getRequestURI(),
                ex.getParameterName(), ex.getParameterType());
        ApiResponse<Object> body = ApiResponse.error("MISSING_PARAMETER", message, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link MethodArgumentTypeMismatchException} thrown when a path
     * or query parameter cannot be coerced to its declared Java type (e.g.,
     * non-numeric value for an {@code @PathVariable Long}). Returns HTTP 400
     * Bad Request with the standardized envelope.
     *
     * <p>{@link MethodArgumentTypeMismatchException#getRequiredType()} can be
     * {@code null} in edge cases (e.g., when the controller method has no
     * declared parameter type). The handler defensively guards against this
     * and substitutes {@code "?"} for an unknown type.</p>
     *
     * @param ex      the type-mismatch exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 400 Bad Request and the
     *         standardized envelope
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        // COBOL: replaces BMS numeric-field validation (e.g., ACCT-ID must
        // be 11 digits, CARD-NUM must be 16 digits, TRAN-AMT must be a valid
        // S9(09)V99 decimal). In REST, type coercion failure yields a 400.
        String correlationId = generateCorrelationId();
        String typeName = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "?";
        String message = "Parameter '" + ex.getName() + "' must be of type " + typeName;
        LOG.warn("[{}] MethodArgumentTypeMismatchException at {}: parameter={}, requiredType={}",
                correlationId, request.getRequestURI(), ex.getName(), typeName);
        ApiResponse<Object> body = ApiResponse.error("TYPE_MISMATCH", message, correlationId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link NoHandlerFoundException} raised when the request URI
     * does not match any controller mapping. Returns HTTP 404 Not Found
     * with the standardized envelope.
     *
     * <p><b>Configuration prerequisite:</b> This handler is only invoked
     * when Spring Boot is configured with
     * {@code spring.mvc.throw-exception-if-no-handler-found=true} and
     * {@code spring.web.resources.add-mappings=false}. The configuration
     * agent is responsible for setting these properties in
     * {@code application.yml}. Without them, Spring serves a default 404 page
     * instead of throwing this exception.</p>
     *
     * @param ex      the no-handler-found exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 404 Not Found and the
     *         standardized envelope
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoHandlerFound(
            NoHandlerFoundException ex, HttpServletRequest request) {
        // COBOL: no direct CICS analogue — in CICS, unmapped transactions
        // surface as DFHAC2001/AC2002 abend codes. In REST, unmapped paths
        // yield a 404 ENDPOINT_NOT_FOUND.
        String correlationId = generateCorrelationId();
        String message = "No handler for " + ex.getHttpMethod() + " " + ex.getRequestURL();
        LOG.warn("[{}] NoHandlerFoundException at {}: httpMethod={}, requestURL={}",
                correlationId, request.getRequestURI(),
                ex.getHttpMethod(), ex.getRequestURL());
        ApiResponse<Object> body = ApiResponse.error("ENDPOINT_NOT_FOUND", message, correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handles {@link NoResourceFoundException} raised by Spring 6.x when the
     * request URI does not match any controller mapping AND no static
     * resource is served from that path. This is the Spring 6 successor to
     * {@link NoHandlerFoundException} for resource-style URLs and is thrown
     * by default (no configuration flag required) when the
     * {@code ResourceHttpRequestHandler} cannot locate a matching resource.
     * Returns HTTP 404 Not Found with the standardized envelope.
     *
     * <p><b>Rationale for separate handler:</b> Without this handler,
     * unmapped URLs surface to the catch-all {@code Exception} handler and
     * are returned as HTTP 500 Internal Server Error, which is the wrong
     * REST semantic. Adding this handler aligns the error envelope and the
     * status code for unmapped URLs even when the
     * {@code spring.mvc.throw-exception-if-no-handler-found} property is
     * not configured. This addresses the CP5 review area of concern
     * regarding {@link NoHandlerFoundException} configuration
     * prerequisites.</p>
     *
     * <p><b>COBOL provenance:</b> The COBOL source has no direct CICS
     * analogue &mdash; in CICS, unmapped transactions surface as
     * DFHAC2001/AC2002 abend codes. In REST, unmapped paths yield HTTP 404
     * {@code ENDPOINT_NOT_FOUND}, mirroring the
     * {@link NoHandlerFoundException} behaviour above.</p>
     *
     * @param ex      the no-resource-found exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 404 Not Found and the
     *         standardized envelope
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResourceFound(
            NoResourceFoundException ex, HttpServletRequest request) {
        // COBOL: see NoHandlerFoundException handler above for rationale.
        String correlationId = generateCorrelationId();
        String message = "No handler for " + request.getMethod() + " " + request.getRequestURI();
        LOG.warn("[{}] NoResourceFoundException at {}: httpMethod={}, resourcePath={}",
                correlationId, request.getRequestURI(),
                request.getMethod(), ex.getResourcePath());
        ApiResponse<Object> body = ApiResponse.error("ENDPOINT_NOT_FOUND", message, correlationId);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // =====================================================================
    // Spring Security exceptions — authentication (401) and authorization
    // (403) failures. Logged at WARN level. CRITICAL: the response body
    // MUST NOT echo the attempted credentials or username back to the
    // caller per AAP §0.7.2 PCI-DSS guidance.
    // =====================================================================

    /**
     * Handles {@link BadCredentialsException} raised by Spring Security when
     * sign-on credentials are invalid. Returns HTTP 401 Unauthorized with a
     * generic message that does NOT echo the attempted user ID back to the
     * caller (PCI-DSS / brute-force-mitigation discipline per AAP &sect;0.7.2).
     *
     * <p><b>COBOL provenance:</b> Replaces the {@code COSGN00C.cbl}
     * "Wrong Password..." flow ({@code WHEN BAD-PASSWORD-FOUND} branch). In
     * the COBOL terminal, the message read "Wrong Password... Try again ...";
     * in the JSON REST world, the canonical caller-facing message is
     * {@code "Invalid User ID or Password"} which mirrors common JSON API
     * conventions while preserving the COBOL semantic.</p>
     *
     * @param ex      the bad-credentials exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 401 Unauthorized and the
     *         standardized envelope
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Object>> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest request) {
        // COBOL: replaces COSGN00C signon failure flow ("Wrong Password...
        // Try again ..."). Per PCI-DSS, the response body does NOT echo
        // the attempted user ID back to the caller.
        String correlationId = generateCorrelationId();
        LOG.warn("[{}] BadCredentialsException at {}: signon failure",
                correlationId, request.getRequestURI());
        ApiResponse<Object> body = ApiResponse.error(
                "UNAUTHORIZED",
                "Invalid User ID or Password",
                correlationId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Handles the broader {@link AuthenticationException} base type for any
     * authentication failure not caught by {@link #handleBadCredentials}
     * (e.g., expired JWT, malformed token, missing credentials, locked
     * account). Returns HTTP 401 Unauthorized with a generic message.
     *
     * <p>This handler is the catch-all for the Spring Security authentication
     * layer; it MUST appear after {@link #handleBadCredentials} so that the
     * more-specific subclass handler is preferred for bad-password failures.</p>
     *
     * @param ex      the authentication exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 401 Unauthorized and the
     *         standardized envelope
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthentication(
            AuthenticationException ex, HttpServletRequest request) {
        // COBOL: replaces COSGN00C catch-all signon failures (expired
        // session, missing CICS COMMAREA, etc.). In the Java target,
        // missing/expired JWT yields a 401 UNAUTHORIZED.
        String correlationId = generateCorrelationId();
        LOG.warn("[{}] AuthenticationException at {}: type={}, message={}",
                correlationId, request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(
                "UNAUTHORIZED",
                "Authentication required",
                correlationId);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * Handles {@link AccessDeniedException} raised by Spring Security when an
     * authenticated user lacks the necessary authority for the requested
     * resource (e.g., a {@code USER}-typed account attempting an admin-only
     * endpoint). Returns HTTP 403 Forbidden with a generic message.
     *
     * <p><b>COBOL provenance:</b> Replaces the role-based menu gating in
     * {@code app/cbl/COMEN01C.cbl} (main menu) and {@code app/cbl/COADM01C.cbl}
     * (admin menu) where the COBOL programs branch on
     * {@code CDEMO-USRTYP-USER} vs {@code CDEMO-USRTYP-ADMIN} to allow or
     * deny menu options. In the Java target, this gating is performed by
     * Spring Security's {@code @PreAuthorize} and authorization filters; the
     * resulting denial surfaces here.</p>
     *
     * @param ex      the access-denied exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 403 Forbidden and the
     *         standardized envelope
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        // COBOL: replaces COMEN01C / COADM01C role-based menu gating
        // (CDEMO-USRTYP-USER vs CDEMO-USRTYP-ADMIN). In Java, Spring
        // Security @PreAuthorize raises AccessDeniedException; this handler
        // translates it to a 403 FORBIDDEN response.
        String correlationId = generateCorrelationId();
        LOG.warn("[{}] AccessDeniedException at {}: message={}",
                correlationId, request.getRequestURI(), ex.getMessage());
        ApiResponse<Object> body = ApiResponse.error(
                "FORBIDDEN",
                "Access denied",
                correlationId);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // =====================================================================
    // HTTP protocol-level exceptions — method-not-allowed (405) and
    // media-type-not-supported (415). Both are Spring Web framework
    // exceptions raised before controller method invocation; mapping them
    // explicitly here ensures the response carries the standardized
    // ApiResponse envelope rather than Spring's default ErrorResponse JSON
    // (which would break the AAP §0.3.4 contract for consumers that parse
    // every CardDemo response with the ApiResponse shape).
    //
    // CP5 review-mandated additions (Code Review Report — Checkpoint CP5,
    // GlobalExceptionHandler MAJOR finding L201-958).
    // =====================================================================

    /**
     * Handles {@link HttpRequestMethodNotSupportedException} raised when the
     * request HTTP method is not declared by any handler for the matched URL
     * (e.g., {@code POST /api/accounts/{id}} when only {@code GET} and
     * {@code PUT} are mapped). Returns HTTP 405 Method Not Allowed with the
     * standardized envelope and an {@code Allow} response header advertising
     * the supported methods per RFC 7231 &sect;6.5.5.
     *
     * <p><b>COBOL provenance:</b> No direct CICS analogue &mdash; in CICS the
     * client could only submit a transaction-ID via 3270 AID keys; the
     * concept of "wrong HTTP method" does not exist. In the Java REST
     * target this exception arises naturally from Spring's dispatcher
     * servlet when, e.g., a client {@code POST}s to a {@code GET}-only
     * endpoint or forgets the {@code PUT} verb on an update. Translating
     * it here preserves the AAP &sect;0.3.4 envelope contract for every
     * 4xx response.</p>
     *
     * <p><b>Response shape:</b> a 405 carries an {@code Allow} header listing
     * supported methods (as required by RFC 7231 &sect;6.5.5) in addition to
     * the JSON body. The body's {@code message} echoes the supported-method
     * list so non-CORS clients (which cannot read the {@code Allow} header
     * directly) can render an actionable error message.</p>
     *
     * <p><b>Logging:</b> WARN level &mdash; client-induced error (4xx),
     * non-sensitive (the HTTP method name is metadata, not credentials).</p>
     *
     * @param ex      the method-not-supported exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 405 Method Not Allowed, the
     *         {@code Allow} response header, and the standardized envelope
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        // COBOL: no direct CICS analogue — in CICS clients could only
        // submit a transaction-ID via 3270 AID keys. In REST this surfaces
        // as a 405 when the HTTP method is not mapped for the URL.
        String correlationId = generateCorrelationId();
        // Spring exposes the supported HTTP methods via getSupportedHttpMethods();
        // null in pathological cases (e.g., the matched handler has no methods
        // declared) — guard with a defensive empty-list fallback.
        java.util.Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        String supportedList = (supported != null && !supported.isEmpty())
                ? supported.stream()
                        .map(HttpMethod::name)
                        .sorted()
                        .collect(Collectors.joining(", "))
                : "";
        String attemptedMethod = ex.getMethod() != null ? ex.getMethod() : "?";
        String message = "HTTP method '" + attemptedMethod + "' not allowed"
                + (supportedList.isEmpty() ? "" : "; supported: " + supportedList);
        LOG.warn("[{}] HttpRequestMethodNotSupportedException at {}: method={}, supported={}",
                correlationId, request.getRequestURI(), attemptedMethod, supportedList);
        ApiResponse<Object> body = ApiResponse.error(
                "METHOD_NOT_ALLOWED",
                message,
                correlationId);
        // RFC 7231 §6.5.5 requires a 405 to advertise supported methods
        // in the Allow response header. Build the header value from the
        // exception's supported-method set.
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (supported != null && !supported.isEmpty()) {
            HttpMethod[] methods = supported.toArray(new HttpMethod[0]);
            builder.allow(methods);
        }
        return builder.body(body);
    }

    /**
     * Handles {@link HttpMediaTypeNotSupportedException} raised when the
     * request {@code Content-Type} is not declared by any handler for the
     * matched URL (e.g., {@code text/xml} when only
     * {@code application/json} is consumed). Returns HTTP 415 Unsupported
     * Media Type with the standardized envelope and an {@code Accept}
     * response header advertising the supported media types per RFC 7231
     * &sect;6.5.13.
     *
     * <p><b>COBOL provenance:</b> No direct CICS analogue &mdash; CICS BMS
     * always exchanged 3270 data streams in a fixed binary format. The
     * REST target uses {@code application/json} exclusively per AAP
     * &sect;0.3.4. This handler exists so clients that send malformed
     * content-type headers receive a structured 415 in the standardized
     * envelope rather than Spring's default ErrorResponse JSON.</p>
     *
     * <p><b>Response shape:</b> the body's {@code message} echoes the
     * supported media-type list so the caller can correct its request.
     * The {@code Accept} response header is also set per RFC 7231
     * &sect;6.5.13.</p>
     *
     * <p><b>Logging:</b> WARN level &mdash; client-induced error (4xx),
     * non-sensitive (media-type strings are metadata).</p>
     *
     * @param ex      the media-type-not-supported exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 415 Unsupported Media Type,
     *         the {@code Accept} response header, and the standardized
     *         envelope
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        // COBOL: no direct CICS analogue — CICS BMS used fixed 3270 binary
        // data streams. REST surfaces wrong Content-Type as a 415.
        String correlationId = generateCorrelationId();
        // Spring exposes the supported media types via getSupportedMediaTypes();
        // null/empty in pathological cases — guard with a defensive
        // empty-list fallback so the response is always well-formed.
        List<MediaType> supported = ex.getSupportedMediaTypes();
        String supportedList = (supported != null && !supported.isEmpty())
                ? supported.stream()
                        .map(MediaType::toString)
                        .collect(Collectors.joining(", "))
                : "";
        MediaType attemptedType = ex.getContentType();
        String attemptedTypeStr = attemptedType != null ? attemptedType.toString() : "?";
        String message = "Content-Type '" + attemptedTypeStr + "' not supported"
                + (supportedList.isEmpty() ? "" : "; supported: " + supportedList);
        LOG.warn("[{}] HttpMediaTypeNotSupportedException at {}: contentType={}, supported={}",
                correlationId, request.getRequestURI(), attemptedTypeStr, supportedList);
        ApiResponse<Object> body = ApiResponse.error(
                "UNSUPPORTED_MEDIA_TYPE",
                message,
                correlationId);
        // RFC 7231 §6.5.13 — advertise supported media types via Accept
        // response header (note: Accept header is technically a request
        // header per RFC 7231 §5.3.2, but Spring + many HTTP libraries
        // use the Accept response header as an advisory mechanism on 415
        // responses, mirroring how a 406 response advertises with the
        // same header).
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        if (supported != null && !supported.isEmpty()) {
            builder.header("Accept", supportedList);
        }
        return builder.body(body);
    }

    // =====================================================================
    // Data-access exceptions — Spring's DataAccessException hierarchy
    // (JPA, JDBC, transaction failures). MUST appear BEFORE the generic
    // CardDemoException + Exception fallbacks below, but AFTER the more
    // specific OptimisticLockingFailureException handler (line 321) so
    // optimistic-lock conflicts continue to be mapped to 409 Conflict
    // (DataAccessException is a superclass of OptimisticLockingFailureException;
    // Spring's @ExceptionHandler resolver picks the most-specific match
    // first regardless of physical order, but we keep this handler late
    // in the file as a defensive measure).
    //
    // CP5 review-mandated addition (Code Review Report — Checkpoint CP5,
    // GlobalExceptionHandler MAJOR finding L201-958).
    // =====================================================================

    /**
     * Handles {@link DataAccessException} raised by Spring Data JPA, JDBC,
     * or any underlying data-source layer when a database operation fails
     * outside the typed cases already handled by
     * {@link RecordNotFoundException}, {@link DuplicateRecordException},
     * {@link OptimisticLockingFailureException}, and
     * {@link ConcurrentModificationException}. Returns HTTP 500 Internal
     * Server Error with a PCI-safe generic message.
     *
     * <p><b>COBOL provenance:</b> Replaces the COBOL pattern of inspecting
     * {@code FILE STATUS} codes after a {@code READ} / {@code WRITE} /
     * {@code REWRITE} / {@code DELETE} operation and routing to the
     * {@code 9910-DISPLAY-IO-STATUS} or {@code 9999-ABEND-PROGRAM} paragraph
     * for any unhandled non-zero status (e.g., {@code 91} - I/O error,
     * {@code 92} - logic error, {@code 93} - resource unavailable,
     * {@code 95} - file name not found, {@code 97} - successful execution
     * with extra info). In the COBOL source, the
     * {@code 9910-DISPLAY-IO-STATUS} paragraph in {@code CBTRN02C.cbl}
     * displays the file-status code and the offending record key before
     * abending; this handler is the Java equivalent &mdash; it produces a
     * 500 response with a correlation ID for log lookup rather than echoing
     * the underlying database error to the caller.</p>
     *
     * <p><b>PCI-DSS / security discipline (AAP &sect;0.7.2):</b> the response
     * body emits only the generic message {@code "A data access error occurred"}.
     * The underlying exception class name, SQL fragments, schema details,
     * connection-string fragments, and stack trace are NEVER leaked to the
     * caller because that would aid attackers in reconnaissance (e.g.,
     * fingerprinting the database engine or schema layout). The full
     * exception including stack trace IS captured in the WARN-level log
     * record with the correlation identifier so operators can locate the
     * offending entry from the caller's correlation ID and inspect the
     * actual error.</p>
     *
     * <p><b>Why WARN not ERROR:</b> a data-access failure is often a
     * transient infrastructure issue (network blip, connection pool
     * exhaustion, deadlock retry exhaustion) rather than a code defect.
     * Logging at WARN avoids alert fatigue while still capturing the full
     * stack trace for operators. The handler-of-last-resort
     * {@link #handleGenericException(Exception, HttpServletRequest)} logs
     * at ERROR for everything else.</p>
     *
     * <p><b>Resolver precedence:</b> Spring's {@code @ExceptionHandler}
     * resolver picks the MOST SPECIFIC match by class hierarchy regardless
     * of physical order in the file. This means
     * {@link OptimisticLockingFailureException} (declared earlier at line
     * &asymp;321), which is a subclass of {@link DataAccessException},
     * will continue to map to HTTP 409 Conflict. This handler catches only
     * those {@code DataAccessException} subclasses that are NOT separately
     * handled (e.g., {@code DataIntegrityViolationException},
     * {@code QueryTimeoutException}, {@code CannotAcquireLockException},
     * {@code TransientDataAccessException}, etc.).</p>
     *
     * @param ex      the data-access exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 500 Internal Server Error
     *         and the standardized envelope; the body never echoes
     *         {@code ex.getMessage()} or the exception class name
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataAccessException(
            DataAccessException ex, HttpServletRequest request) {
        // COBOL: replaces 9910-DISPLAY-IO-STATUS / 9999-ABEND-PROGRAM
        // paragraphs that display the FILE STATUS and offending record
        // key on any unhandled VSAM I/O failure (see app/cbl/CBTRN02C.cbl
        // and the analogous abend paragraphs across all app/cbl/*.cbl
        // programs). In the Java target, services and Spring Data JPA
        // raise DataAccessException; this handler maps it to HTTP 500
        // with a non-leaky generic message.
        String correlationId = generateCorrelationId();
        // WARN (not ERROR) — see Javadoc for rationale. Stack trace IS
        // captured for operators, just at WARN so it does not trip ERROR
        // alarms for transient infrastructure issues.
        LOG.warn("[{}] DataAccessException at {}: type={}, message={}",
                correlationId, request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage(), ex);
        // PCI-DSS-safe generic message — never echoes ex.getMessage(),
        // ex.getClass().getSimpleName(), or any SQL/schema fragment to
        // the caller per AAP §0.7.2.
        ApiResponse<Object> body = ApiResponse.error(
                "DATA_ACCESS_ERROR",
                "A data access error occurred",
                correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // =====================================================================
    // Fallback handlers — must appear AFTER all specific handlers so that
    // Spring's resolver picks the most-specific match first.
    //
    // handleCardDemoException: defensive catch for any CardDemoException
    // subclass not specifically handled above.
    //
    // handleGenericException: final catch-all for any unexpected runtime
    // failure. NEVER leaks the underlying exception message to the client.
    // =====================================================================

    /**
     * Defensive fallback handler for any {@link CardDemoException} subclass
     * not specifically handled by an earlier {@code @ExceptionHandler}.
     * Should be unreachable in practice if the exception hierarchy is fully
     * enumerated; included as a safety net in case a new subclass is added
     * without updating this handler. Returns HTTP 500 Internal Server Error
     * with the standardized envelope.
     *
     * <p><b>COBOL provenance:</b> Fallback for any {@code RETURN-CODE != 0}
     * or {@code CEE3ABD} invocation not otherwise mapped. In the COBOL
     * source, the {@code 9999-ABEND-PROGRAM} paragraph in {@code CBTRN02C.cbl}
     * and analogous abend paragraphs in every other program invoke the LE
     * service {@code CEE3ABD} for controlled abend; this handler is the Java
     * equivalent of that controlled abend &mdash; it produces a 500 response
     * with a preserved reason code rather than crashing the JVM.</p>
     *
     * <p>This handler logs at {@code ERROR} level with full stack trace
     * because reaching this branch indicates an unhandled domain exception
     * subtype, which is itself a code-quality alert for operations.</p>
     *
     * @param ex      the unhandled CardDemoException subtype
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 500 Internal Server Error and
     *         the standardized envelope
     */
    @ExceptionHandler(CardDemoException.class)
    public ResponseEntity<ApiResponse<Object>> handleCardDemoException(
            CardDemoException ex, HttpServletRequest request) {
        // COBOL: fallback for RETURN-CODE != 0 / CEE3ABD invocation
        // (see 9999-ABEND-PROGRAM paragraphs throughout app/cbl/*.cbl).
        String correlationId = generateCorrelationId();
        String reasonCode = (ex.getReasonCode() != null) ? ex.getReasonCode() : "SYSTEM_ERROR";
        LOG.error("[{}] CardDemoException at {}: type={}, reasonCode={}, message={}",
                correlationId, request.getRequestURI(),
                ex.getClass().getSimpleName(), reasonCode, ex.getMessage(), ex);
        ApiResponse<Object> body = ApiResponse.error(reasonCode, ex.getMessage(), correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /**
     * Final catch-all for any {@link Exception} not caught by a more-specific
     * handler. Returns HTTP 500 Internal Server Error with a PCI-safe generic
     * message that does NOT leak the underlying exception's class name,
     * message, or stack trace to the caller.
     *
     * <p><b>PCI-DSS / security discipline (AAP &sect;0.7.2):</b> The response
     * body emits only {@code "An unexpected error occurred"} regardless of the
     * underlying exception type or message. This prevents accidental
     * disclosure of internal class names, database schema details, file
     * system paths, or any other information that could aid an attacker. The
     * full exception including stack trace IS captured in the log record at
     * {@code ERROR} level with the correlation identifier; operators can
     * locate the offending log entry from the caller's correlation ID and
     * inspect the actual error.</p>
     *
     * <p><b>MUST be the LAST {@code @ExceptionHandler} method in this
     * class.</b> Spring's resolver picks the most-specific handler for any
     * given thrown type; declaring this handler with {@code Exception.class}
     * makes it the catch-all of last resort. Adding any more-specific
     * handler AFTER this one is fine for runtime correctness (Spring's
     * selection is type-based, not file-order-based), but file ordering is
     * documented for review clarity.</p>
     *
     * <p><b>Note on {@link Throwable}:</b> The handler deliberately catches
     * only {@link Exception} (not {@link Throwable}). Errors such as
     * {@link OutOfMemoryError} and {@link StackOverflowError} must propagate
     * up the call stack so that the JVM can react appropriately; intercepting
     * them in a generic handler would mask a fatal condition.</p>
     *
     * @param ex      the unhandled exception
     * @param request the HTTP request (for path logging)
     * @return {@link ResponseEntity} with HTTP 500 Internal Server Error and
     *         the standardized envelope with a generic, PCI-safe message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(
            Exception ex, HttpServletRequest request) {
        // COBOL: final catch-all — equivalent to the unrecoverable abend
        // path in the COBOL source (CEE3ABD with reason code 0). Per PCI-DSS
        // (AAP §0.7.2), the response body never leaks the underlying
        // exception message; only a generic message is emitted.
        String correlationId = generateCorrelationId();
        LOG.error("[{}] Unhandled exception at {}: type={}, message={}",
                correlationId, request.getRequestURI(),
                ex.getClass().getSimpleName(), ex.getMessage(), ex);
        ApiResponse<Object> body = ApiResponse.error(
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                correlationId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // =====================================================================
    // Private helper methods.
    // =====================================================================

    /**
     * Generates a fresh correlation identifier for distributed tracing.
     * Emitted in every log record and surfaced back to the caller in the
     * {@code correlationId} property of the response envelope, enabling
     * operators to stitch together CloudWatch Logs entries, OpenSearch
     * documents, and CloudTrail events for a single request per AAP
     * &sect;0.6.6 (Cross-Cutting: Audit, Observability, and PCI-DSS).
     *
     * <p>The implementation uses {@link UUID#randomUUID()} which produces a
     * cryptographically random version-4 UUID; collisions are
     * astronomically improbable.</p>
     *
     * @return a fresh UUID string (e.g.,
     *         {@code "b3a4f9e8-1c2d-4e5f-8a7b-9c0d1e2f3a4b"})
     */
    private String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Translates a Spring {@link FieldError} (from a
     * {@code BindingResult}) into the wire-shape
     * {@link ApiResponse.FieldError} using the PCI-safe 3-arg factory
     * {@link ApiResponse.FieldError#of(String, String, String)}.
     *
     * <p>Extracted fields:</p>
     * <ul>
     *   <li>{@code field} &mdash; {@link FieldError#getField()}.</li>
     *   <li>{@code code} &mdash; {@link FieldError#getCode()} (the
     *       constraint annotation name, e.g., {@code "NotBlank"}). Falls
     *       back to {@code "VALIDATION"} if the binding error has no
     *       associated code.</li>
     *   <li>{@code message} &mdash; {@link FieldError#getDefaultMessage()}.
     *       Falls back to {@code "Invalid value"} when no message is
     *       supplied.</li>
     * </ul>
     *
     * <p>The Spring-provided {@code rejectedValue} is intentionally NOT
     * forwarded to the response envelope per AAP &sect;0.6.6 PCI-DSS
     * guidance &mdash; this protects against accidental disclosure of
     * sensitive values such as passwords, card numbers, CVVs, and SSNs.</p>
     *
     * @param fieldError the Spring binding-result field error
     * @return a fully populated {@link ApiResponse.FieldError} ready to
     *         include in the standardized envelope
     */
    private ApiResponse.FieldError toFieldError(FieldError fieldError) {
        String code = fieldError.getCode() != null ? fieldError.getCode() : "VALIDATION";
        String message = fieldError.getDefaultMessage() != null
                ? fieldError.getDefaultMessage()
                : "Invalid value";
        return ApiResponse.FieldError.of(fieldError.getField(), code, message);
    }

    /**
     * Translates a Jakarta Bean Validation {@link ConstraintViolation} into
     * the wire-shape {@link ApiResponse.FieldError} using the PCI-safe 3-arg
     * factory {@link ApiResponse.FieldError#of(String, String, String)}.
     *
     * <p>Extracted fields:</p>
     * <ul>
     *   <li>{@code field} &mdash;
     *       {@link ConstraintViolation#getPropertyPath()} converted to a
     *       dotted-path string (e.g., {@code "accountId"},
     *       {@code "merchantAddress.zip"}).</li>
     *   <li>{@code code} &mdash; the simple name of the constraint
     *       annotation (e.g., {@code "NotBlank"}, {@code "Size"},
     *       {@code "Pattern"}). Falls back to {@code "VALIDATION"} if the
     *       constraint descriptor is unavailable.</li>
     *   <li>{@code message} &mdash; the human-readable
     *       {@link ConstraintViolation#getMessage()}.</li>
     * </ul>
     *
     * <p>The rejected value is intentionally NOT included
     * ({@link ApiResponse.FieldError} has a {@code rejectedValue} component
     * but the 3-arg factory passes {@code null}) per AAP &sect;0.6.6 PCI-DSS
     * guidance &mdash; this protects against accidental disclosure of
     * sensitive values such as passwords, card numbers, CVVs, and SSNs.</p>
     *
     * @param violation the Jakarta Bean Validation violation
     * @return a fully populated {@link ApiResponse.FieldError} ready to
     *         include in the standardized envelope
     */
    private ApiResponse.FieldError toFieldError(ConstraintViolation<?> violation) {
        String field = violation.getPropertyPath() != null
                ? violation.getPropertyPath().toString()
                : "";
        String code;
        if (violation.getConstraintDescriptor() != null
                && violation.getConstraintDescriptor().getAnnotation() != null) {
            code = violation.getConstraintDescriptor()
                    .getAnnotation()
                    .annotationType()
                    .getSimpleName();
        } else {
            code = "VALIDATION";
        }
        String message = violation.getMessage() != null
                ? violation.getMessage()
                : "Invalid value";
        return ApiResponse.FieldError.of(field, code, message);
    }
}
