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
 * Thrown when a {@code WRITE} (insert) operation attempts to create a record
 * whose primary key already exists in the target store.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 409 Conflict</b> per AAP &sect;0.4.1 ("Maps DUPKEY to 409
 * Conflict"). HTTP status mapping is centralized in the
 * {@code GlobalExceptionHandler} as the single source of truth; this class
 * deliberately does NOT carry a {@code @ResponseStatus} annotation, matching
 * the pattern established by {@link CardDemoException} and the other typed
 * domain exceptions in this package.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces {@code FILE STATUS '22'} (DUPKEY)
 * handling on VSAM {@code WRITE} operations and the CICS
 * {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} response-code branches
 * found in every {@code EXEC CICS WRITE} site in the source codebase. The
 * canonical source locations are:</p>
 * <ul>
 *   <li>{@code app/cbl/COUSR01C.cbl} lines 240&ndash;274 &mdash;
 *       {@code WRITE-USER-SEC-FILE} paragraph: the
 *       {@code EXEC CICS WRITE DATASET(WS-USRSEC-FILE) FROM(SEC-USER-DATA)}
 *       call followed by
 *       {@code EVALUATE WS-RESP-CD ... WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)
 *       MOVE 'User ID already exist...' TO WS-MESSAGE} (lines 260&ndash;266).
 *       This is the source of the {@code "User ID already exists"} error path
 *       in the user-add flow.</li>
 *   <li>{@code app/cbl/COTRN02C.cbl} lines 711&ndash;749 &mdash;
 *       {@code WRITE-TRANSACT-FILE} paragraph: the
 *       {@code EXEC CICS WRITE DATASET(WS-TRANSACT-FILE) FROM(TRAN-RECORD)}
 *       call followed by
 *       {@code EVALUATE WS-RESP-CD ... WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)
 *       MOVE 'Tran ID already exist...' TO WS-MESSAGE} (lines 735&ndash;741).
 *       This is the source of the {@code "Transaction ID already exists"}
 *       error path in the transaction-add flow.</li>
 *   <li>The same pattern applies to any other COBOL {@code WRITE} to an
 *       INDEXED VSAM cluster, including batch writes such as
 *       {@code app/cbl/CBTRN02C.cbl} {@code WRITE-TRANSACT-FILE} where a
 *       duplicate {@code TRAN-ID} would surface {@code FILE STATUS '22'}.</li>
 * </ul>
 *
 * <p><b>JPA bridge pattern:</b> In the Java target, the most common origin of
 * this exception is a Spring {@code DataIntegrityViolationException} thrown
 * by Hibernate when a JPA {@code save(...)} call violates a unique-constraint
 * on a primary key or natural key column. Services bridge the Spring data
 * exception to this domain exception via try/catch:</p>
 *
 * <pre>{@code
 * try {
 *     userSecurityRepository.save(user);
 * } catch (DataIntegrityViolationException ex) {
 *     // COBOL: COUSR01C.cbl WRITE-USER-SEC-FILE — WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)
 *     throw new DuplicateRecordException(
 *         "User ID '" + user.getUserId() + "' already exists", ex);
 * }
 * }</pre>
 *
 * <p>Batch programs may also pre-check existence before insert to surface a
 * more descriptive message before the database layer flags the conflict:</p>
 *
 * <pre>{@code
 * // COBOL: CBTRN02C.cbl WRITE FD-TRANFILE-REC — DUPKEY would set FILE STATUS '22'
 * if (transactionRepository.existsById(tranId)) {
 *     throw new DuplicateRecordException(
 *         "Transaction " + tranId + " already exists");
 * }
 * transactionRepository.save(transaction);
 * }</pre>
 *
 * <p><b>Reason code semantics:</b> The {@linkplain #FILE_STATUS_DUPKEY default
 * reason code} ({@code "22"}) is the verbatim COBOL {@code FILE STATUS} value
 * preserved per AAP &sect;0.7.2 ("Error codes and condition handling
 * surfaced to downstream consumers must be preserved verbatim"). Downstream
 * consumers of error responses can branch on {@code reasonCode.equals("22")}
 * to detect "duplicate key" without parsing the human-readable message
 * text.</p>
 *
 * <p>Callers MAY override the default reason code via the
 * {@code reasonCode}-bearing constructors when a more specific COBOL reject
 * value applies (for example, a downstream consumer convention that
 * distinguishes between primary-key collisions and natural-key collisions).
 * When passing a non-default code, the calling service is expected to include
 * an inline comment naming the COBOL program, paragraph, and source line
 * range per the AAP &sect;0.7.3 traceability requirement (e.g.,
 * {@code // COBOL: COTRN02C.cbl WRITE-TRANSACT-FILE — DFHRESP(DUPKEY)}).</p>
 *
 * <p><b>Distinct from {@link ConcurrentModificationException}:</b> Both
 * exceptions map to HTTP 409 Conflict, but they represent DIFFERENT
 * semantics:</p>
 * <ul>
 *   <li><b>{@code DuplicateRecordException}</b> &mdash; an {@code INSERT}
 *       (COBOL {@code WRITE}) failed because the primary key (or a natural
 *       key with a unique constraint) already exists. Source COBOL signal is
 *       {@code FILE STATUS '22'} (DUPKEY) or CICS {@code DFHRESP(DUPKEY)} /
 *       {@code DFHRESP(DUPREC)}.</li>
 *   <li><b>{@code ConcurrentModificationException}</b> &mdash; an
 *       {@code UPDATE} (COBOL {@code REWRITE}) failed because another
 *       transaction modified the row between the {@code READ FOR UPDATE} and
 *       the {@code REWRITE}. Source COBOL signal is the snapshot mismatch in
 *       {@code app/cbl/COACTUPC.cbl} and {@code app/cbl/COCRDUPC.cbl},
 *       mapped to JPA {@code @Version} optimistic-locking conflicts.</li>
 * </ul>
 * <p>Both share HTTP 409, but they carry different reason codes and convey
 * different remediation guidance to the API caller. Use the correct exception
 * type for the originating COBOL semantic.</p>
 *
 * <p><b>PII discipline:</b> Per AAP &sect;0.6.6 (Cross-Cutting: Audit,
 * Observability, and PCI-DSS), the exception {@code message} MUST NOT contain
 * sensitive financial data. Echoing back a key value supplied by the caller
 * (such as a user ID, account ID, or transaction ID) is acceptable for
 * "duplicate" feedback because that value was provided by the caller and is
 * not a secret; however, including card numbers, current balances, credit
 * limits, social-security numbers, or any credential material is strictly
 * forbidden. The {@code GlobalExceptionHandler} additionally enforces this
 * discipline by emitting only the COBOL reason code and a sanitized
 * message in the JSON error envelope.</p>
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
 * @see RecordNotFoundException
 * @see ConcurrentModificationException
 * @see ValidationException
 * @see GlobalExceptionHandler
 */
public class DuplicateRecordException extends CardDemoException {

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
     * Default reason code corresponding to COBOL {@code FILE STATUS '22'}
     * (DUPKEY).
     *
     * <p>The value is a two-character string (not a number) preserved
     * verbatim from the COBOL {@code FILE STATUS} clause syntax (e.g., the
     * implicit {@code '22'} value that COBOL sets when a {@code WRITE}
     * encounters a duplicate primary key on a VSAM KSDS). Preservation of
     * the literal {@code "22"} value is mandated by AAP &sect;0.7.2 ("Error
     * codes and condition handling surfaced to downstream consumers must be
     * preserved verbatim") so that downstream systems consuming this
     * application's JSON error envelope can branch on the reason code
     * without parsing the human-readable message.</p>
     *
     * <p>The value mirrors the CICS {@code DFHRESP(DUPKEY)} and
     * {@code DFHRESP(DUPREC)} response codes that appear in
     * {@code app/cbl/COUSR01C.cbl} line 260 and
     * {@code app/cbl/COTRN02C.cbl} line 735, even though those CICS response
     * codes are numeric internally; the surfaced {@code FILE STATUS}
     * equivalent is the literal two-digit string {@code "22"}.</p>
     *
     * <p>Callers who need to surface a more specific reject code may invoke
     * the {@link #DuplicateRecordException(String, String)} or
     * {@link #DuplicateRecordException(String, String, Throwable)}
     * constructor and supply the specific code as the first argument.</p>
     */
    public static final String FILE_STATUS_DUPKEY = "22";

    /**
     * Constructs a {@code DuplicateRecordException} with the supplied
     * message and the {@linkplain #FILE_STATUS_DUPKEY default reason code}
     * ({@code "22"}, the verbatim COBOL {@code FILE STATUS} DUPKEY value).
     *
     * <p>This is the most commonly used constructor when the calling service
     * performs an explicit pre-check (e.g.,
     * {@code if (repository.existsById(id))}) and wishes to throw a
     * descriptive exception before invoking {@code save(...)}.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: COTRN02C.cbl WRITE-TRANSACT-FILE — DFHRESP(DUPKEY)/DFHRESP(DUPREC)
     * if (transactionRepository.existsById(tranId)) {
     *     throw new DuplicateRecordException(
     *         "Transaction " + tranId + " already exists");
     * }
     * }</pre>
     *
     * @param message a human-readable description of the duplicate
     *                condition. Should identify the entity type and (if
     *                supplied by the caller) the key value that collided.
     *                Must not contain PII beyond what the caller originally
     *                supplied per AAP &sect;0.6.6.
     */
    public DuplicateRecordException(String message) {
        super(FILE_STATUS_DUPKEY, message);
    }

    /**
     * Constructs a {@code DuplicateRecordException} with the supplied
     * message, a wrapped cause, and the {@linkplain #FILE_STATUS_DUPKEY
     * default reason code} ({@code "22"}).
     *
     * <p>Use this constructor when a lower-level exception (typically a
     * Spring {@code DataIntegrityViolationException} thrown by Hibernate
     * for a unique-constraint violation, or a JPA {@code EntityExistsException}
     * thrown by the persistence provider) is being adapted to the domain
     * exception hierarchy. Preserving the cause keeps the original stack
     * trace available for root-cause analysis in CloudWatch and OpenSearch
     * log indexes (AAP &sect;0.6.6).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * try {
     *     userSecurityRepository.save(user);
     * } catch (DataIntegrityViolationException ex) {
     *     // COBOL: COUSR01C.cbl WRITE-USER-SEC-FILE — WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)
     *     throw new DuplicateRecordException(
     *         "User ID '" + user.getUserId() + "' already exists", ex);
     * }
     * }</pre>
     *
     * @param message a human-readable description of the duplicate condition
     * @param cause   the underlying exception being wrapped; preserves the
     *                stack trace. Typically a
     *                {@code DataIntegrityViolationException} or
     *                {@code EntityExistsException}. May be {@code null}.
     */
    public DuplicateRecordException(String message, Throwable cause) {
        super(FILE_STATUS_DUPKEY, message, cause);
    }

    /**
     * Constructs a {@code DuplicateRecordException} with an explicit reason
     * code and message.
     *
     * <p>Use this constructor when a more specific reject code than the
     * default {@code "22"} applies. While the COBOL source codebase
     * predominantly uses {@code FILE STATUS '22'} for duplicate-key
     * conditions, downstream consumer conventions may require a different
     * code (e.g., to distinguish primary-key collisions from natural-key
     * collisions in audit feeds, or to surface a normalized internal code
     * such as {@code "CONFLICT"}).</p>
     *
     * <p>The reason code is preserved verbatim per AAP &sect;0.7.2 ("Error
     * codes and condition handling surfaced to downstream consumers must be
     * preserved verbatim"). The calling service is expected to include an
     * inline comment naming the COBOL program, paragraph, and (where
     * applicable) source line range per the AAP &sect;0.7.3 traceability
     * requirement.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: COUSR01C.cbl WRITE-USER-SEC-FILE — DFHRESP(DUPKEY)/DFHRESP(DUPREC)
     * throw new DuplicateRecordException(
     *     "22", "User ID '" + user.getUserId() + "' already exists");
     * }</pre>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (e.g., {@code "22"} for the verbatim COBOL
     *                   DUPKEY code, or a domain-specific code); may be
     *                   {@code null}, in which case
     *                   {@code GlobalExceptionHandler} substitutes a default
     *                   code
     * @param message    a human-readable description of the duplicate
     *                   condition
     */
    public DuplicateRecordException(String reasonCode, String message) {
        super(reasonCode, message);
    }

    /**
     * Constructs a {@code DuplicateRecordException} with an explicit reason
     * code, a message, and a wrapped cause. This is the most expressive
     * constructor.
     *
     * <p>Use this constructor when both a specific reject code AND an
     * underlying exception must be preserved. The cause's stack trace is
     * preserved on the resulting exception, enabling full root-cause
     * analysis in CloudWatch and OpenSearch log indexes (AAP &sect;0.6.6).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * try {
     *     transactionRepository.save(transaction);
     * } catch (DataIntegrityViolationException ex) {
     *     // COBOL: COTRN02C.cbl WRITE-TRANSACT-FILE — DFHRESP(DUPKEY)/DFHRESP(DUPREC)
     *     throw new DuplicateRecordException(
     *         "22", "Transaction " + transaction.getId() + " already exists", ex);
     * }
     * }</pre>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope; may be {@code null}
     * @param message    a human-readable description of the duplicate
     *                   condition
     * @param cause      the underlying exception being wrapped; preserves
     *                   the stack trace. May be {@code null}.
     */
    public DuplicateRecordException(String reasonCode, String message, Throwable cause) {
        super(reasonCode, message, cause);
    }
}
