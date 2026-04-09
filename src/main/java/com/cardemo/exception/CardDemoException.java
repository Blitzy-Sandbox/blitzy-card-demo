/*
 * CardDemoException.java
 *
 * Base exception class for the CardDemo application.
 *
 * This class serves as the root of the CardDemo exception hierarchy, providing
 * a unified structure for error codes, messages, and optional COBOL FILE STATUS
 * code traceability. All application-specific exceptions extend this class.
 *
 * COBOL Traceability:
 * - IO-STATUS structure (CBTRN02C.cbl lines 131-140)
 * - APPL-RESULT pattern (CBTRN02C.cbl lines 142-144)
 * - CICS RESP/RESP2 error handling (COACTUPC.cbl lines 389-408)
 * - FILE STATUS codes across all batch and online programs
 *
 * Design Decision (Decision Log D-EXCEPT-001):
 * Extends RuntimeException (unchecked) rather than Exception (checked) because:
 * 1. Spring @Transactional rolls back by default on RuntimeException
 * 2. Avoids forced try-catch on every service method call
 * 3. Matches the COBOL pattern where errors propagate via GO TO exit paragraphs
 *    and PERFORM ... THRU boundaries without explicit "throws" contracts
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

/**
 * Base exception class for all CardDemo application errors.
 *
 * <p>Every exception in the {@code com.cardemo.exception} package extends this class,
 * enabling uniform error handling through Spring's {@code @ControllerAdvice} and
 * automatic transaction rollback via Spring's {@code @Transactional} semantics.</p>
 *
 * <p>This class carries two metadata fields beyond the standard exception message:</p>
 * <ul>
 *   <li>{@code errorCode} — An application-level error code used for API error responses
 *       and structured logging (e.g., "RNF" for record not found, "DUP" for duplicates,
 *       "LOCK" for concurrent modification, "CREDIT" for overlimit, "EXPIRY" for expired
 *       cards, "VALID" for validation failures).</li>
 *   <li>{@code fileStatusCode} — An optional COBOL FILE STATUS code mapping that provides
 *       traceability back to the original mainframe error handling system. For example,
 *       "23" maps to record-not-found (INVALID KEY), "22" maps to duplicate key
 *       (DUPKEY/DUPREC). May be {@code null} for exceptions that do not originate from
 *       file I/O operations.</li>
 * </ul>
 *
 * <h3>COBOL FILE STATUS Code Reference</h3>
 * <table>
 *   <tr><td>00</td><td>Success (no exception thrown)</td></tr>
 *   <tr><td>22</td><td>DUPKEY/DUPREC → DuplicateRecordException</td></tr>
 *   <tr><td>23</td><td>INVALID KEY (not found) → RecordNotFoundException</td></tr>
 *   <tr><td>35</td><td>File not found (infrastructure level)</td></tr>
 *   <tr><td>46</td><td>Sequential READ, no next record (EOF in readers)</td></tr>
 *   <tr><td>47</td><td>READ on file not open for input (infrastructure)</td></tr>
 *   <tr><td>9x</td><td>Operating system errors (infrastructure)</td></tr>
 * </table>
 *
 * <h3>Subclass Hierarchy</h3>
 * <ul>
 *   <li>{@code RecordNotFoundException} — FILE STATUS 23, HTTP 404</li>
 *   <li>{@code DuplicateRecordException} — FILE STATUS 22, HTTP 409</li>
 *   <li>{@code ConcurrentModificationException} — CICS REWRITE failure, HTTP 409</li>
 *   <li>{@code CreditLimitExceededException} — Reject code 102, HTTP 422</li>
 *   <li>{@code ExpiredCardException} — Reject code 103, HTTP 422</li>
 *   <li>{@code ValidationException} — Field validation failures, HTTP 400</li>
 * </ul>
 *
 * @see java.lang.RuntimeException
 */
public class CardDemoException extends RuntimeException {

    /**
     * Serial version UID for serialization compatibility.
     * RuntimeException implements Serializable via the Throwable chain.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Default error code used when no specific error code is provided.
     * Represents a generic CardDemo application error.
     */
    public static final String DEFAULT_ERROR_CODE = "CARDDEMO_ERROR";

    /**
     * Application-level error code for categorizing the type of error.
     *
     * <p>Examples include:</p>
     * <ul>
     *   <li>"RNF" — Record not found (maps to COBOL INVALID KEY)</li>
     *   <li>"DUP" — Duplicate record (maps to COBOL DUPKEY/DUPREC)</li>
     *   <li>"LOCK" — Concurrent modification (maps to CICS REWRITE failure)</li>
     *   <li>"CREDIT" — Credit limit exceeded (maps to reject code 102)</li>
     *   <li>"EXPIRY" — Expired card/account (maps to reject code 103)</li>
     *   <li>"VALID" — Validation failure (maps to field-level validation errors)</li>
     *   <li>"CARDDEMO_ERROR" — Generic application error (default)</li>
     * </ul>
     *
     * <p>This field is never {@code null}; defaults to {@link #DEFAULT_ERROR_CODE}.</p>
     */
    private final String errorCode;

    /**
     * Optional COBOL FILE STATUS code that traces this exception back to the
     * original mainframe error handling system.
     *
     * <p>COBOL FILE STATUS is a two-character code (e.g., "00", "22", "23")
     * returned after every file I/O operation. This field preserves that mapping
     * for traceability and structured error reporting.</p>
     *
     * <p>May be {@code null} for exceptions that do not originate from file I/O
     * operations, such as validation errors or authentication failures.</p>
     *
     * <p>Corresponds to the COBOL IO-STATUS structure:</p>
     * <pre>
     * 01  IO-STATUS.
     *     05  IO-STAT1  PIC X.
     *     05  IO-STAT2  PIC X.
     * </pre>
     */
    private final String fileStatusCode;

    /**
     * Constructs a new CardDemoException with the specified detail message.
     *
     * <p>Uses the default error code ({@link #DEFAULT_ERROR_CODE}) and no FILE STATUS
     * code mapping. Suitable for general application errors that do not map to a
     * specific COBOL error pattern.</p>
     *
     * @param message the detail message describing the error condition
     */
    public CardDemoException(String message) {
        super(message);
        this.errorCode = DEFAULT_ERROR_CODE;
        this.fileStatusCode = null;
    }

    /**
     * Constructs a new CardDemoException with the specified detail message and cause.
     *
     * <p>Uses the default error code ({@link #DEFAULT_ERROR_CODE}) and no FILE STATUS
     * code mapping. Suitable for wrapping lower-level exceptions (e.g., JPA exceptions,
     * I/O exceptions) into the CardDemo exception hierarchy.</p>
     *
     * @param message the detail message describing the error condition
     * @param cause   the underlying cause of this exception (may be {@code null})
     */
    public CardDemoException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = DEFAULT_ERROR_CODE;
        this.fileStatusCode = null;
    }

    /**
     * Constructs a new CardDemoException with the specified message and error code.
     *
     * <p>No FILE STATUS code mapping is set. Suitable for application errors with a
     * specific categorization but no direct COBOL file I/O origin.</p>
     *
     * @param message   the detail message describing the error condition
     * @param errorCode the application-level error code (e.g., "RNF", "DUP", "VALID");
     *                  if {@code null}, defaults to {@link #DEFAULT_ERROR_CODE}
     */
    public CardDemoException(String message, String errorCode) {
        super(message);
        this.errorCode = (errorCode != null) ? errorCode : DEFAULT_ERROR_CODE;
        this.fileStatusCode = null;
    }

    /**
     * Constructs a new CardDemoException with message, error code, and FILE STATUS code.
     *
     * <p>This is the primary constructor for exceptions that map directly to COBOL
     * FILE STATUS codes. The {@code fileStatusCode} provides bidirectional traceability
     * to the original mainframe error handling system.</p>
     *
     * @param message        the detail message describing the error condition
     * @param errorCode      the application-level error code (e.g., "RNF", "DUP");
     *                       if {@code null}, defaults to {@link #DEFAULT_ERROR_CODE}
     * @param fileStatusCode the COBOL FILE STATUS code (e.g., "22", "23"); may be
     *                       {@code null} for non-file-related errors
     */
    public CardDemoException(String message, String errorCode, String fileStatusCode) {
        super(message);
        this.errorCode = (errorCode != null) ? errorCode : DEFAULT_ERROR_CODE;
        this.fileStatusCode = fileStatusCode;
    }

    /**
     * Constructs a new CardDemoException with message, error code, FILE STATUS code,
     * and underlying cause.
     *
     * <p>This is the most complete constructor, used when wrapping a lower-level exception
     * (e.g., {@code DataIntegrityViolationException}, {@code OptimisticLockException})
     * with full COBOL traceability metadata.</p>
     *
     * <p>Example usage in FileStatusMapper:</p>
     * <pre>
     * throw new RecordNotFoundException(
     *     "Account not found with id: " + accountId,
     *     "RNF",    // application error code
     *     "23",     // COBOL FILE STATUS code for INVALID KEY
     *     originalException
     * );
     * </pre>
     *
     * @param message        the detail message describing the error condition
     * @param errorCode      the application-level error code; if {@code null}, defaults
     *                       to {@link #DEFAULT_ERROR_CODE}
     * @param fileStatusCode the COBOL FILE STATUS code; may be {@code null}
     * @param cause          the underlying cause of this exception (may be {@code null})
     */
    public CardDemoException(String message, String errorCode, String fileStatusCode,
                             Throwable cause) {
        super(message, cause);
        this.errorCode = (errorCode != null) ? errorCode : DEFAULT_ERROR_CODE;
        this.fileStatusCode = fileStatusCode;
    }

    /**
     * Returns the application-level error code for this exception.
     *
     * <p>The error code categorizes the type of error for API responses and structured
     * logging. This value is never {@code null}; it defaults to
     * {@link #DEFAULT_ERROR_CODE} ("CARDDEMO_ERROR") if no specific code was provided
     * at construction time.</p>
     *
     * @return the application error code, never {@code null}
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the COBOL FILE STATUS code mapping for this exception, if applicable.
     *
     * <p>This field provides traceability back to the original COBOL mainframe error
     * handling system. COBOL FILE STATUS codes are two-character strings (e.g., "00"
     * for success, "22" for duplicate key, "23" for record not found).</p>
     *
     * <p>Returns {@code null} for exceptions that do not originate from file I/O
     * operations, such as validation errors or authentication failures.</p>
     *
     * @return the COBOL FILE STATUS code, or {@code null} if not applicable
     */
    public String getFileStatusCode() {
        return fileStatusCode;
    }

    /**
     * Returns a string representation of this exception including the error code,
     * FILE STATUS code (if present), and the exception message.
     *
     * <p>Format: {@code CardDemoException[errorCode=XYZ, fileStatusCode=NN]: message}</p>
     *
     * @return a descriptive string representation of this exception
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(getClass().getSimpleName());
        sb.append("[errorCode=").append(errorCode);
        if (fileStatusCode != null) {
            sb.append(", fileStatusCode=").append(fileStatusCode);
        }
        sb.append("]: ").append(getMessage());
        return sb.toString();
    }
}
