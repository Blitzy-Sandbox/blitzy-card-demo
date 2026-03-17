/*
 * RecordNotFoundException.java
 *
 * Exception class mapping COBOL FILE STATUS 23 (INVALID KEY — record not found)
 * and CICS DFHRESP(NOTFND) to the Java exception hierarchy.
 *
 * This is the most commonly used exception in the CardDemo application, thrown
 * whenever a JPA findById() returns Optional.empty() or a keyed lookup fails.
 *
 * COBOL Traceability:
 * - FILE STATUS 23 = INVALID KEY condition on READ, START, or DELETE operations
 * - CICS DFHRESP(NOTFND) = record not found on EXEC CICS READ DATASET
 *
 * Source COBOL Patterns (original repository commit SHA 27d6c6f):
 * - CBTRN02C.cbl lines 384, 396, 475, 555 — INVALID KEY on batch VSAM reads
 *   (reject codes 100: INVALID CARD NUMBER, 101: ACCOUNT RECORD NOT FOUND,
 *    109: ACCOUNT RECORD NOT FOUND on REWRITE)
 * - COACTUPC.cbl lines 3668, 3716, 3766 — DFHRESP(NOTFND) on CICS READ
 *   for cross-reference, account master, and customer master files
 * - COACTVWC.cbl — DFHRESP(NOTFND) on account view multi-dataset read
 * - COCRDSLC.cbl — DFHRESP(NOTFND) on card detail single keyed read
 * - COTRN01C.cbl — DFHRESP(NOTFND) on transaction detail view
 * - COUSR03C.cbl — DFHRESP(NOTFND) on user security delete confirmation
 *
 * Design Decision (Decision Log D-EXCEPT-002):
 * Carries entityName and entityId metadata for structured error reporting in
 * REST API responses. The @ControllerAdvice handler maps this exception to
 * HTTP 404 Not Found with a JSON body containing the entity context.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.exception;

/**
 * Exception thrown when a requested record cannot be found in the data store.
 *
 * <p>Maps directly to COBOL FILE STATUS code {@code "23"} (INVALID KEY condition —
 * the record was not found on a READ, START, or DELETE operation) and CICS
 * {@code DFHRESP(NOTFND)} response across all 18 online programs and 10 batch
 * programs in the CardDemo application.</p>
 *
 * <p>This exception carries two additional metadata fields beyond the standard
 * {@link CardDemoException} fields:</p>
 * <ul>
 *   <li>{@code entityName} — The type of entity that was not found (e.g., "Account",
 *       "Card", "Transaction", "UserSecurity"). Corresponds to the VSAM dataset
 *       name in the original COBOL system (e.g., ACCTDAT, CARDDAT, TRANSACT, USRSEC).</li>
 *   <li>{@code entityId} — The identifier used for the lookup that failed. May be a
 *       {@code String} (account ID), {@code Long} (transaction ID), or any other
 *       key type. Corresponds to the RIDFLD value in CICS READ operations.</li>
 * </ul>
 *
 * <h3>Usage Contexts</h3>
 * <table>
 *   <caption>COBOL-to-Java Exception Mapping</caption>
 *   <tr><th>COBOL Program</th><th>Java Service</th><th>Trigger</th></tr>
 *   <tr><td>COACTVWC.cbl</td><td>AccountViewService</td>
 *       <td>Account/customer/cross-ref not found on view</td></tr>
 *   <tr><td>COACTUPC.cbl</td><td>AccountUpdateService</td>
 *       <td>Account/customer/cross-ref not found during update</td></tr>
 *   <tr><td>COCRDSLC.cbl</td><td>CardDetailService</td>
 *       <td>Card record not found on detail view</td></tr>
 *   <tr><td>COCRDUPC.cbl</td><td>CardUpdateService</td>
 *       <td>Card record not found during update</td></tr>
 *   <tr><td>COTRN01C.cbl</td><td>TransactionDetailService</td>
 *       <td>Transaction record not found on detail view</td></tr>
 *   <tr><td>COUSR03C.cbl</td><td>UserDeleteService</td>
 *       <td>User security record not found for deletion</td></tr>
 *   <tr><td>CBTRN02C.cbl</td><td>TransactionPostingProcessor</td>
 *       <td>Card/account not found during batch validation (reject codes 100, 101)</td></tr>
 * </table>
 *
 * <h3>REST API Mapping</h3>
 * <p>The {@code @ControllerAdvice} exception handler in {@code WebConfig} maps this
 * exception to HTTP 404 Not Found with a JSON response body:</p>
 * <pre>
 * {
 *   "errorCode": "RNF",
 *   "fileStatusCode": "23",
 *   "entityName": "Account",
 *   "entityId": "00000000001",
 *   "message": "Account not found with id: 00000000001"
 * }
 * </pre>
 *
 * @see CardDemoException
 */
public class RecordNotFoundException extends CardDemoException {

    /**
     * Serial version UID for serialization compatibility.
     *
     * <p>Required because {@link RuntimeException} implements {@link java.io.Serializable}
     * through the {@link Throwable} chain. Ensures consistent deserialization across
     * JVM versions and clustered environments.</p>
     */
    private static final long serialVersionUID = 1L;

    /**
     * Application-level error code for record-not-found conditions.
     *
     * <p>Used in API error responses and structured logging to categorize this
     * error type. Maps to the COBOL pattern where DFHRESP(NOTFND) triggers an
     * error message construction with the dataset name and RIDFLD value.</p>
     */
    private static final String ERROR_CODE = "RNF";

    /**
     * COBOL FILE STATUS code for INVALID KEY / record not found.
     *
     * <p>FILE STATUS "23" indicates that an attempt was made to access a record
     * identified by a key, and that record does not exist in the file, or a
     * START or READ operation has been tried on an optional input file that is
     * not present. This maps to the COBOL IO-STATUS structure:</p>
     * <pre>
     * 01  IO-STATUS.
     *     05  IO-STAT1  PIC X.   (value '2')
     *     05  IO-STAT2  PIC X.   (value '3')
     * </pre>
     */
    private static final String FILE_STATUS_CODE = "23";

    /**
     * The type of entity that was not found.
     *
     * <p>Corresponds to the VSAM dataset name in the original COBOL system.
     * Examples: "Account" (ACCTDAT), "Card" (CARDDAT), "Transaction" (TRANSACT),
     * "UserSecurity" (USRSEC), "CardCrossReference" (CARDXREF),
     * "Customer" (CUSTDAT).</p>
     *
     * <p>May be {@code null} when the exception is constructed with only a
     * message string (e.g., when wrapping lower-level exceptions).</p>
     */
    private final String entityName;

    /**
     * The identifier that was used for the failed lookup.
     *
     * <p>Corresponds to the RIDFLD value in CICS READ operations or the
     * record key in batch VSAM keyed reads. May be a {@code String} for
     * fixed-width keys (e.g., 11-digit account ID) or a {@code Long} for
     * numeric keys (e.g., 16-digit transaction ID).</p>
     *
     * <p>May be {@code null} when the exception is constructed with only a
     * message string.</p>
     *
     * <p>Declared {@code transient} because {@code Object} does not implement
     * {@link java.io.Serializable}. The entity ID is contextual metadata used
     * for error reporting and structured logging — it is captured in the
     * exception message (which IS serialized) and does not need independent
     * serialization. This avoids the {@code -Xlint:serial} warning while
     * maintaining the flexible {@code Object} type for diverse key types.</p>
     */
    private final transient Object entityId;

    /**
     * Constructs a new {@code RecordNotFoundException} with the specified detail message.
     *
     * <p>Sets the error code to {@code "RNF"} and the FILE STATUS code to {@code "23"}.
     * The {@code entityName} and {@code entityId} fields are set to {@code null}.</p>
     *
     * <p>Use this constructor when the entity context is not available or when
     * constructing a general record-not-found error. For structured error reporting
     * with entity context, prefer {@link #RecordNotFoundException(String, Object)}.</p>
     *
     * <p>COBOL equivalent: Setting IO-STATUS to "23" and constructing a generic
     * error message without specifying the dataset name or record key.</p>
     *
     * @param message the detail message describing which record was not found
     *                and the context of the failed lookup
     */
    public RecordNotFoundException(String message) {
        super(message, ERROR_CODE, FILE_STATUS_CODE);
        this.entityName = null;
        this.entityId = null;
    }

    /**
     * Constructs a new {@code RecordNotFoundException} with entity context.
     *
     * <p>Builds a descriptive message in the format
     * {@code "{entityName} not found with id: {entityId}"} and sets the error code
     * to {@code "RNF"} and FILE STATUS code to {@code "23"}.</p>
     *
     * <p>This is the preferred constructor for service layer usage, as it provides
     * full entity context for REST API error responses and structured logging.</p>
     *
     * <p>COBOL equivalent: The STRING operation that constructs error messages like
     * {@code 'Account: XXXXXXXXXXX not found in Acct Master file.'} in COACTUPC.cbl
     * paragraph 9300-GETACCTDATA-BYACCT (lines 3716-3734).</p>
     *
     * <p>Example usage:</p>
     * <pre>
     * // In AccountViewService (← COACTVWC.cbl DFHRESP(NOTFND))
     * Account account = accountRepository.findById(accountId)
     *     .orElseThrow(() -&gt; new RecordNotFoundException("Account", accountId));
     *
     * // In TransactionDetailService (← COTRN01C.cbl DFHRESP(NOTFND))
     * Transaction txn = transactionRepository.findById(transactionId)
     *     .orElseThrow(() -&gt; new RecordNotFoundException("Transaction", transactionId));
     * </pre>
     *
     * @param entityName the type of entity that was not found (e.g., "Account",
     *                   "Card", "Transaction", "UserSecurity"); should not be
     *                   {@code null}
     * @param entityId   the identifier used for the failed lookup (e.g., account ID,
     *                   card number, transaction ID); may be {@code null} if the
     *                   identifier is not available
     */
    public RecordNotFoundException(String entityName, Object entityId) {
        super(buildMessage(entityName, entityId), ERROR_CODE, FILE_STATUS_CODE);
        this.entityName = entityName;
        this.entityId = entityId;
    }

    /**
     * Constructs a new {@code RecordNotFoundException} with the specified detail message
     * and underlying cause.
     *
     * <p>Sets the error code to {@code "RNF"} and the FILE STATUS code to {@code "23"}.
     * The {@code entityName} and {@code entityId} fields are set to {@code null}.</p>
     *
     * <p>Use this constructor when wrapping lower-level exceptions (e.g., JPA
     * {@code EntityNotFoundException}, Spring Data {@code EmptyResultDataAccessException})
     * into the CardDemo exception hierarchy.</p>
     *
     * <p>COBOL equivalent: The error handling path in batch programs where an I/O error
     * is detected via FILE STATUS check, the status is moved to IO-STATUS, and
     * 9910-DISPLAY-IO-STATUS is performed before 9999-ABEND-PROGRAM (CBTRN02C.cbl
     * pattern). In the Java target, instead of abending, the exception propagates to
     * the caller for graceful handling.</p>
     *
     * @param message the detail message describing which record was not found
     * @param cause   the underlying cause of this exception (e.g., a JPA exception
     *                that indicated the record does not exist); may be {@code null}
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, ERROR_CODE, FILE_STATUS_CODE, cause);
        this.entityName = null;
        this.entityId = null;
    }

    /**
     * Returns the type of entity that was not found.
     *
     * <p>Corresponds to the VSAM dataset name in the original COBOL system.
     * This value is used in REST API error responses to provide structured
     * context about which entity type the failed lookup targeted.</p>
     *
     * <p>Returns {@code null} when the exception was constructed without entity
     * context (i.e., using the single-argument message constructor or the
     * message-and-cause constructor).</p>
     *
     * @return the entity type name (e.g., "Account", "Card", "Transaction"),
     *         or {@code null} if not specified at construction time
     */
    public String getEntityName() {
        return entityName;
    }

    /**
     * Returns the identifier that was used for the failed lookup.
     *
     * <p>Corresponds to the RIDFLD (Record Identification Field) value in CICS
     * READ operations or the record key in batch VSAM keyed reads. The return
     * type is {@code Object} to accommodate various key types:</p>
     * <ul>
     *   <li>{@code String} — for fixed-width VSAM keys (e.g., 11-digit account ID)</li>
     *   <li>{@code Long} — for numeric keys (e.g., transaction sequence numbers)</li>
     *   <li>Composite key objects — for multi-field keys (e.g., TransactionCategoryBalanceId)</li>
     * </ul>
     *
     * <p>Returns {@code null} when the exception was constructed without entity
     * context.</p>
     *
     * @return the lookup identifier, or {@code null} if not specified at
     *         construction time
     */
    public Object getEntityId() {
        return entityId;
    }

    /**
     * Builds a descriptive error message from the entity name and identifier.
     *
     * <p>Produces messages in the format {@code "{entityName} not found with id: {entityId}"},
     * which mirrors the COBOL STRING operations that construct error messages like
     * {@code 'Account: XXXXXXXXXXX not found in Acct Master file.'}</p>
     *
     * <p>Handles {@code null} values gracefully:</p>
     * <ul>
     *   <li>If entityName is {@code null}: produces "Record not found with id: {entityId}"</li>
     *   <li>If entityId is {@code null}: produces "{entityName} not found"</li>
     *   <li>If both are {@code null}: produces "Record not found"</li>
     * </ul>
     *
     * @param entityName the type of entity that was not found; may be {@code null}
     * @param entityId   the identifier used for the failed lookup; may be {@code null}
     * @return a descriptive error message, never {@code null}
     */
    private static String buildMessage(String entityName, Object entityId) {
        String name = (entityName != null && !entityName.isEmpty()) ? entityName : "Record";
        if (entityId != null) {
            return name + " not found with id: " + entityId;
        }
        return name + " not found";
    }
}
