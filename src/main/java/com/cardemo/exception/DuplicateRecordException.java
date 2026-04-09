/*
 * DuplicateRecordException.java
 *
 * Exception mapping for COBOL FILE STATUS 22 (DUPKEY/DUPREC) — thrown when a write
 * operation encounters a pre-existing primary or alternate key.
 *
 * COBOL Traceability:
 * - COTRN02C.cbl lines 735-736: DFHRESP(DUPKEY)/DFHRESP(DUPREC) on WRITE to TRANSACT
 *   file. Message: "Tran ID already exist..." (line 738). Triggered when a transaction
 *   is added with an existing Transaction ID.
 * - COUSR01C.cbl lines 260-261: DFHRESP(DUPKEY)/DFHRESP(DUPREC) on WRITE to USRSEC
 *   file. Message: "User ID already exist..." (line 263). Triggered when a user is
 *   added with an existing User ID.
 * - FILE STATUS 22 across batch programs (CBTRN02C.cbl) when WRITE operations encounter
 *   existing keys during daily transaction posting.
 *
 * COBOL FILE STATUS 22 Definition:
 * "An attempt was made to write a record that would create a duplicate primary key
 *  or duplicate alternate key without the DUPLICATES option."
 *
 * Spring Integration:
 * - Wraps {@code org.springframework.dao.DataIntegrityViolationException} from Spring
 *   Data JPA when a unique constraint violation occurs during {@code JpaRepository.save()}.
 * - Mapped to HTTP 409 (Conflict) in REST controllers via {@code @ControllerAdvice}.
 * - Participates in Spring {@code @Transactional} rollback semantics as an unchecked
 *   exception (inherits from {@link CardDemoException} → {@link RuntimeException}).
 *
 * Design Decision (Decision Log D-EXCEPT-002):
 * Carries {@code entityName} and {@code duplicateId} metadata fields for structured
 * error reporting in API responses and observability logging, enabling downstream
 * consumers to identify which entity type and which specific identifier caused the
 * conflict without parsing the exception message.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

/**
 * Exception thrown when an attempt to create a record violates a unique key constraint.
 *
 * <p>This exception maps directly to the COBOL FILE STATUS code {@code 22}
 * (DUPKEY/DUPREC) and the CICS response codes {@code DFHRESP(DUPKEY)} and
 * {@code DFHRESP(DUPREC)}. It is the Java equivalent of the COBOL
 * {@code WRITE ... INVALID KEY} condition for duplicate keys.</p>
 *
 * <h3>COBOL Source Mapping</h3>
 * <table>
 *   <tr><th>COBOL Program</th><th>Paragraph</th><th>Condition</th><th>Message</th></tr>
 *   <tr>
 *     <td>COTRN02C.cbl</td><td>WRITE-TRANSACT-FILE</td>
 *     <td>DFHRESP(DUPKEY)/DFHRESP(DUPREC)</td><td>"Tran ID already exist..."</td>
 *   </tr>
 *   <tr>
 *     <td>COUSR01C.cbl</td><td>WRITE-USER-SEC-FILE</td>
 *     <td>DFHRESP(DUPKEY)/DFHRESP(DUPREC)</td><td>"User ID already exist..."</td>
 *   </tr>
 * </table>
 *
 * <h3>Usage Examples</h3>
 * <pre>{@code
 * // In TransactionAddService (← COTRN02C.cbl WRITE-TRANSACT-FILE):
 * try {
 *     transactionRepository.save(transaction);
 * } catch (DataIntegrityViolationException ex) {
 *     throw new DuplicateRecordException("Transaction", transactionId);
 * }
 *
 * // In UserAddService (← COUSR01C.cbl WRITE-USER-SEC-FILE):
 * try {
 *     userSecurityRepository.save(user);
 * } catch (DataIntegrityViolationException ex) {
 *     throw new DuplicateRecordException(
 *         "User ID already exist: " + userId, ex);
 * }
 * }</pre>
 *
 * <h3>HTTP Mapping</h3>
 * <p>Mapped to HTTP 409 (Conflict) by {@code @ControllerAdvice} exception handlers.
 * The response body includes the {@code errorCode} ("DUP"), {@code fileStatusCode}
 * ("22"), {@code entityName}, and {@code duplicateId} for structured error reporting.</p>
 *
 * @see CardDemoException
 * @see com.cardemo.model.enums.FileStatus
 */
public class DuplicateRecordException extends CardDemoException {

    /**
     * Serial version UID for serialization compatibility.
     * Required because {@link CardDemoException} extends {@link RuntimeException},
     * which implements {@link java.io.Serializable} through the {@link Throwable} chain.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Application-level error code identifying duplicate record conditions.
     *
     * <p>Used in structured API error responses and observability logging to
     * categorize this exception type without inspecting the class name.</p>
     */
    public static final String ERROR_CODE = "DUP";

    /**
     * COBOL FILE STATUS code for duplicate key on write operation.
     *
     * <p>FILE STATUS 22 is returned by COBOL I/O verbs (WRITE, REWRITE) when the
     * operation would create a duplicate primary key or duplicate alternate record
     * key without the DUPLICATES clause. In CICS programs, the equivalent response
     * codes are {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)}.</p>
     */
    public static final String FILE_STATUS_CODE = "22";

    /**
     * The name of the entity type that triggered the duplicate key violation.
     *
     * <p>Examples: "Transaction", "UserSecurity", "Account", "Card".
     * May be {@code null} when the exception is constructed with only a message
     * string (e.g., when wrapping a Spring {@code DataIntegrityViolationException}
     * where the entity context is not readily available).</p>
     */
    private final String entityName;

    /**
     * The identifier value that caused the duplicate key violation.
     *
     * <p>Stored as {@link Object} to accommodate various key types: {@link String}
     * for user IDs (COUSR01C), {@link Long} for transaction IDs (COTRN02C),
     * or composite key objects for entities with multi-column primary keys.</p>
     *
     * <p>May be {@code null} when the exception is constructed with only a message
     * string or a message-and-cause pair.</p>
     *
     * <p>Declared {@code transient} because {@link Object} does not implement
     * {@link java.io.Serializable}. The duplicate ID value is captured in the
     * exception message (which IS serialized), so no information is lost during
     * the rare event of exception serialization (e.g., RMI, JMS).</p>
     */
    private final transient Object duplicateId;

    /**
     * Constructs a new {@code DuplicateRecordException} with the specified detail message.
     *
     * <p>Sets the error code to {@link #ERROR_CODE} ("DUP") and the COBOL FILE STATUS
     * code to {@link #FILE_STATUS_CODE} ("22"). The {@code entityName} and
     * {@code duplicateId} fields are set to {@code null}.</p>
     *
     * <p>Use this constructor when a descriptive message is available but the entity
     * type and specific duplicate identifier are not separately known. For structured
     * error reporting with entity metadata, prefer
     * {@link #DuplicateRecordException(String, Object)}.</p>
     *
     * @param message the detail message describing the duplicate key condition
     *                (e.g., "Tran ID already exist...", mirroring COTRN02C.cbl line 738)
     */
    public DuplicateRecordException(String message) {
        super(message, ERROR_CODE, FILE_STATUS_CODE);
        this.entityName = null;
        this.duplicateId = null;
    }

    /**
     * Constructs a new {@code DuplicateRecordException} with entity name and duplicate ID.
     *
     * <p>Generates a descriptive message in the format
     * {@code "{entityName} already exists with id: {id}"} and sets the error code to
     * {@link #ERROR_CODE} ("DUP") and the COBOL FILE STATUS code to
     * {@link #FILE_STATUS_CODE} ("22").</p>
     *
     * <p>This is the preferred constructor for service-layer code where the entity type
     * and conflicting identifier are known, enabling structured error reporting in API
     * responses.</p>
     *
     * <h4>COBOL Traceability</h4>
     * <ul>
     *   <li>COTRN02C.cbl: {@code entityName="Transaction"}, {@code id=TRAN-ID}</li>
     *   <li>COUSR01C.cbl: {@code entityName="UserSecurity"}, {@code id=SEC-USR-ID}</li>
     * </ul>
     *
     * @param entityName the name of the entity type (e.g., "Transaction", "UserSecurity")
     * @param id         the duplicate identifier value that caused the violation
     */
    public DuplicateRecordException(String entityName, Object id) {
        super(entityName + " already exists with id: " + id, ERROR_CODE, FILE_STATUS_CODE);
        this.entityName = entityName;
        this.duplicateId = id;
    }

    /**
     * Constructs a new {@code DuplicateRecordException} with a detail message and cause.
     *
     * <p>Sets the error code to {@link #ERROR_CODE} ("DUP") and the COBOL FILE STATUS
     * code to {@link #FILE_STATUS_CODE} ("22"). The {@code entityName} and
     * {@code duplicateId} fields are set to {@code null}.</p>
     *
     * <p>This constructor is intended for wrapping Spring's
     * {@code DataIntegrityViolationException} (or its cause,
     * {@code ConstraintViolationException}) when JPA detects a unique constraint
     * violation during {@code save()} or {@code persist()} operations.</p>
     *
     * <h4>Example — Wrapping Spring DataIntegrityViolationException</h4>
     * <pre>{@code
     * try {
     *     repository.save(entity);
     * } catch (DataIntegrityViolationException ex) {
     *     throw new DuplicateRecordException(
     *         "Duplicate record detected: " + ex.getMostSpecificCause().getMessage(),
     *         ex);
     * }
     * }</pre>
     *
     * @param message the detail message describing the duplicate key condition
     * @param cause   the underlying cause, typically a
     *                {@code DataIntegrityViolationException} from Spring Data JPA
     */
    public DuplicateRecordException(String message, Throwable cause) {
        super(message, ERROR_CODE, FILE_STATUS_CODE, cause);
        this.entityName = null;
        this.duplicateId = null;
    }

    /**
     * Returns the name of the entity type that triggered the duplicate key violation.
     *
     * <p>This value is populated when the exception is constructed via
     * {@link #DuplicateRecordException(String, Object)}. For exceptions constructed
     * with other constructors, this returns {@code null}.</p>
     *
     * <p>Common entity names in the CardDemo application:</p>
     * <ul>
     *   <li>"Transaction" — from COTRN02C.cbl WRITE-TRANSACT-FILE paragraph</li>
     *   <li>"UserSecurity" — from COUSR01C.cbl WRITE-USER-SEC-FILE paragraph</li>
     *   <li>"Account" — from account creation operations</li>
     *   <li>"Card" — from card creation operations</li>
     * </ul>
     *
     * @return the entity name, or {@code null} if not specified at construction
     */
    public String getEntityName() {
        return entityName;
    }

    /**
     * Returns the identifier value that caused the duplicate key violation.
     *
     * <p>This value is populated when the exception is constructed via
     * {@link #DuplicateRecordException(String, Object)}. For exceptions constructed
     * with other constructors, this returns {@code null}.</p>
     *
     * <p>The return type is {@link Object} to accommodate various key types:</p>
     * <ul>
     *   <li>{@link String} — for user IDs (SEC-USR-ID from CSUSR01Y.cpy)</li>
     *   <li>{@link Long} or {@link String} — for transaction IDs (TRAN-ID from CVTRA05Y.cpy)</li>
     *   <li>Composite key objects — for entities with multi-column primary keys</li>
     * </ul>
     *
     * @return the duplicate identifier, or {@code null} if not specified at construction
     */
    public Object getDuplicateId() {
        return duplicateId;
    }
}
