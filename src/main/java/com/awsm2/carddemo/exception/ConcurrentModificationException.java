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
 * Thrown when an optimistic-lock conflict is detected during an update
 * operation &mdash; i.e., the JPA entity's {@code @Version} value at write
 * time differs from the value captured at read time, indicating another
 * transaction modified the row between the {@code READ FOR UPDATE} and the
 * {@code REWRITE}.
 *
 * <p><b>HTTP status mapping:</b> Mapped by {@code GlobalExceptionHandler} to
 * <b>HTTP 409 Conflict</b> per AAP &sect;0.4.1 ("Maps snapshot mismatch (JPA
 * OptimisticLockException) to 409 Conflict"). HTTP status mapping is
 * centralized in the {@code GlobalExceptionHandler} as the single source of
 * truth; this class deliberately does NOT carry a {@code @ResponseStatus}
 * annotation, matching the pattern established by {@link CardDemoException}
 * and the other typed domain exceptions in this package.</p>
 *
 * <p><b>COBOL provenance:</b> Replaces the before/after image comparison
 * pattern used by the COBOL online update programs to detect concurrent
 * modification of VSAM records under CICS pseudo-conversational control. The
 * canonical source locations are:</p>
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl} lines 4076&ndash;4103 &mdash; the
 *       {@code 9500-WRITE-PROCESSING} sequence. After {@code REWRITE} of the
 *       account record succeeds (lines 4076&ndash;4081), the program issues a
 *       second {@code REWRITE} against the customer record (lines
 *       4085&ndash;4091). If the customer rewrite fails (lines 4095&ndash;4103),
 *       the program sets {@code LOCKED-BUT-UPDATE-FAILED} and issues
 *       {@code EXEC CICS SYNCPOINT ROLLBACK} (line 4100) to undo the
 *       already-committed account rewrite, preserving multi-record
 *       transactional integrity. The {@code 9700-CHECK-CHANGE-IN-REC}
 *       paragraph performs the before/after image comparison that sets
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (line 4143) when the captured
 *       snapshot no longer matches the current record.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} line 207 &mdash; the 88-level condition
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} (value:
 *       {@code 'Record changed by some one else. Please review'}) raised when
 *       the {@code 9700-CHECK-CHANGE-IN-REC} paragraph (line 1455) detects a
 *       snapshot mismatch on the card record before the {@code REWRITE}
 *       (line 1478). Coupled with the {@code LOCKED-BUT-UPDATE-FAILED}
 *       condition (line 209) when the rewrite itself fails after a successful
 *       lock acquisition.</li>
 * </ul>
 *
 * <p><b>JPA bridge pattern:</b> In the Java target, the most common origin of
 * this exception is Spring/JPA's {@code OptimisticLockingFailureException}
 * (and its concrete subtypes {@code ObjectOptimisticLockingFailureException}
 * and {@code StaleObjectStateException}) raised by Hibernate when a
 * {@code @Version}-protected entity's version column at the {@code UPDATE}
 * differs from the value first read into the persistence context. The
 * {@code GlobalExceptionHandler} bridges the Spring data exception to this
 * domain exception &mdash; or services may bridge explicitly when adding
 * domain context:</p>
 *
 * <pre>{@code
 * try {
 *     accountRepository.save(account);
 * } catch (OptimisticLockingFailureException ex) {
 *     // COBOL: COACTUPC.cbl 9700-CHECK-CHANGE-IN-REC -- DATA-WAS-CHANGED-BEFORE-UPDATE
 *     throw new ConcurrentModificationException(
 *         "OPTIMISTIC_LOCK",
 *         "Account " + accountId + " was modified by another transaction. Please review and retry.",
 *         ex);
 * }
 * }</pre>
 *
 * <p>The {@code GlobalExceptionHandler} also installs a separate handler for
 * {@code OptimisticLockingFailureException} that produces the same HTTP 409
 * response envelope when the service layer does not explicitly bridge.</p>
 *
 * <p><b>Reason code semantics:</b> Unlike {@link DuplicateRecordException}
 * (which carries the canonical COBOL {@code FILE STATUS '22'}) or
 * {@link RecordNotFoundException} (which carries {@code FILE STATUS '23'}),
 * an optimistic-lock conflict has no single canonical COBOL {@code FILE STATUS}
 * value &mdash; the COBOL programs detect it through application-level
 * before/after image comparison ({@code 9700-CHECK-CHANGE-IN-REC}) rather
 * than through a VSAM file-status code. Callers therefore pass an explicit
 * reason code via the {@link #ConcurrentModificationException(String, String)}
 * or {@link #ConcurrentModificationException(String, String, Throwable)}
 * constructor when they want to surface a specific identifier; the
 * {@link #ConcurrentModificationException(String)} and
 * {@link #ConcurrentModificationException(String, Throwable)} constructors
 * pass {@code null} for the reason code, in which case the
 * {@code GlobalExceptionHandler} substitutes its default code in the JSON
 * envelope per AAP &sect;0.3.4.</p>
 *
 * <p>Conventional reason-code strings used across the codebase include:</p>
 * <ul>
 *   <li>{@code "OPTIMISTIC_LOCK"} &mdash; the JPA-bridge default; surfaced
 *       when {@code OptimisticLockingFailureException} is wrapped.</li>
 *   <li>{@code "DATA_CHANGED_BEFORE_UPDATE"} &mdash; verbatim port of the
 *       {@code DATA-WAS-CHANGED-BEFORE-UPDATE} condition flag from
 *       {@code app/cbl/COACTUPC.cbl} line 521 and
 *       {@code app/cbl/COCRDUPC.cbl} line 207.</li>
 *   <li>{@code "LOCKED_BUT_UPDATE_FAILED"} &mdash; verbatim port of the
 *       {@code LOCKED-BUT-UPDATE-FAILED} condition flag from
 *       {@code app/cbl/COACTUPC.cbl} line 4079 and
 *       {@code app/cbl/COCRDUPC.cbl} line 209.</li>
 * </ul>
 * <p>These string values are mandated by AAP &sect;0.7.2 ("Error codes and
 * condition handling surfaced to downstream consumers must be preserved
 * verbatim") and should be supplied by callers as the {@code reasonCode}
 * argument so downstream consumers can branch on the code without parsing
 * the human-readable message.</p>
 *
 * <p><b>Distinct from {@link DuplicateRecordException}:</b> Both exceptions
 * map to HTTP 409 Conflict, but they represent DIFFERENT semantics:</p>
 * <ul>
 *   <li><b>{@code DuplicateRecordException}</b> &mdash; an {@code INSERT}
 *       (COBOL {@code WRITE}) failed because the primary key already exists.
 *       Source COBOL signal is {@code FILE STATUS '22'} (DUPKEY) or CICS
 *       {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)}.</li>
 *   <li><b>{@code ConcurrentModificationException}</b> &mdash; an
 *       {@code UPDATE} (COBOL {@code REWRITE}) failed because another
 *       transaction modified the row between the {@code READ FOR UPDATE} and
 *       the {@code REWRITE}. Source COBOL signal is the snapshot mismatch
 *       detected in {@code 9700-CHECK-CHANGE-IN-REC} of
 *       {@code app/cbl/COACTUPC.cbl} and {@code app/cbl/COCRDUPC.cbl},
 *       mapped to JPA {@code @Version} optimistic-locking conflicts.</li>
 * </ul>
 * <p>Both share HTTP 409, but they carry different reason codes and convey
 * different remediation guidance to the API caller. Use the correct exception
 * type for the originating COBOL semantic.</p>
 *
 * <p><b>CRITICAL NAMING WARNING &mdash; JDK collision:</b> The simple name
 * {@code ConcurrentModificationException} also exists in the JDK as
 * {@link java.util.ConcurrentModificationException}, an UNRELATED exception
 * raised by collection iterators when the backing collection is structurally
 * modified during iteration. The two classes have completely different
 * semantics:</p>
 * <ul>
 *   <li>{@code com.awsm2.carddemo.exception.ConcurrentModificationException}
 *       (this class) &mdash; a domain exception for OPTIMISTIC-LOCK
 *       conflicts on persisted entities; mapped to HTTP 409.</li>
 *   <li>{@link java.util.ConcurrentModificationException} &mdash; a JDK
 *       runtime exception for unsynchronized concurrent collection
 *       modification (e.g., adding to a {@code List} while iterating).</li>
 * </ul>
 * <p>Within the {@code com.awsm2.carddemo.exception} package and any file
 * that imports this class via its simple name, the unqualified
 * {@code ConcurrentModificationException} identifier resolves to THIS class.
 * Files that genuinely need the JDK's iterator-failure exception MUST
 * fully-qualify it as {@code java.util.ConcurrentModificationException}.
 * Do NOT add {@code import java.util.ConcurrentModificationException;} to
 * this file or to any file that already imports the domain exception &mdash;
 * the import would create an ambiguity that the Java compiler will reject.
 * </p>
 *
 * <p><b>PII discipline:</b> Per AAP &sect;0.6.6 (Cross-Cutting: Audit,
 * Observability, and PCI-DSS), the exception {@code message} MUST NOT contain
 * sensitive financial data. Echoing back an entity key (account ID, card
 * number) supplied by the caller is acceptable for "another transaction
 * modified this record" feedback because that value was provided by the
 * caller; however, including current balances, credit limits, social-security
 * numbers, expiration dates, CVVs, or any credential material is strictly
 * forbidden. The {@code GlobalExceptionHandler} additionally enforces this
 * discipline by emitting only the reason code and a sanitized message in
 * the JSON error envelope.</p>
 *
 * <p><b>Exception propagation:</b> Per AAP &sect;0.7.1 (layered
 * architecture), services THROW this exception; the
 * {@code GlobalExceptionHandler} catches it via
 * {@code @RestControllerAdvice}. Controllers do NOT catch this exception
 * &mdash; they let it propagate to the handler. This preserves the layered
 * architecture mandate from AAP &sect;0.7.1 and ensures a single source of
 * truth for HTTP status mapping and error envelope shape.</p>
 *
 * <p><b>Transaction-rollback semantics:</b> Because this exception extends
 * {@link CardDemoException} which extends {@link RuntimeException}, throwing
 * it from inside a method annotated with
 * {@code @Transactional(rollbackFor = Exception.class)} triggers an automatic
 * rollback of the surrounding transaction. This mirrors the COBOL
 * {@code EXEC CICS SYNCPOINT ROLLBACK} semantic at line 4100 of
 * {@code app/cbl/COACTUPC.cbl}: when the customer rewrite fails after the
 * account rewrite succeeded, both are undone atomically. In the Java target
 * the {@code @Transactional} boundary on
 * {@code AccountUpdateService.updateAccount(...)} provides the equivalent
 * atomic-rollback guarantee.</p>
 *
 * <p><b>Immutability and thread-safety:</b> Like {@link CardDemoException},
 * this class adds no mutable instance state. The single inherited
 * {@code reasonCode} field is {@code final}. All instances are effectively
 * immutable and safe to share across threads.</p>
 *
 * @see CardDemoException
 * @see DuplicateRecordException
 * @see RecordNotFoundException
 * @see ValidationException
 * @see java.util.ConcurrentModificationException
 */
// COBOL: COACTUPC.cbl (lines 4076-4103, SYNCPOINT ROLLBACK on REWRITE failure)
// COBOL: COCRDUPC.cbl (line 207, DATA-WAS-CHANGED-BEFORE-UPDATE)
public class ConcurrentModificationException extends CardDemoException {

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
     * Constructs a {@code ConcurrentModificationException} with a message and
     * no explicit reason code.
     *
     * <p>The base-class {@link CardDemoException#getReasonCode()} will return
     * {@code null} for instances created via this constructor, and the
     * {@code GlobalExceptionHandler} will substitute a default semantic code
     * (e.g., {@code "CONFLICT"} or {@code "OPTIMISTIC_LOCK"}) when emitting
     * the JSON error envelope. Use one of the
     * {@code reasonCode}-bearing constructors when a specific code such as
     * {@code "OPTIMISTIC_LOCK"}, {@code "DATA_CHANGED_BEFORE_UPDATE"}, or
     * {@code "LOCKED_BUT_UPDATE_FAILED"} should be surfaced.</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * // COBOL: COCRDUPC.cbl 9700-CHECK-CHANGE-IN-REC -- DATA-WAS-CHANGED-BEFORE-UPDATE
     * throw new ConcurrentModificationException(
     *     "Card " + cardNumber + " was modified by another transaction. Please review.");
     * }</pre>
     *
     * @param message a human-readable description of the optimistic-lock
     *                conflict. Should identify the entity (e.g., "Account
     *                123" or "Card 4111...") that was modified concurrently.
     *                Must not contain PII beyond what the caller originally
     *                supplied per AAP &sect;0.6.6.
     */
    public ConcurrentModificationException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ConcurrentModificationException} with a message and
     * a wrapped cause, but no explicit reason code.
     *
     * <p>Use this constructor when adapting a lower-level Spring/JPA
     * optimistic-lock exception (typically
     * {@code org.springframework.dao.OptimisticLockingFailureException},
     * {@code org.springframework.orm.ObjectOptimisticLockingFailureException},
     * or {@code org.hibernate.StaleObjectStateException}) to the domain
     * exception hierarchy when no specific reason code is needed. Preserving
     * the cause keeps the original stack trace available for root-cause
     * analysis in CloudWatch and OpenSearch log indexes (AAP
     * &sect;0.6.6).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * try {
     *     accountRepository.save(account);
     * } catch (OptimisticLockingFailureException ex) {
     *     // COBOL: COACTUPC.cbl 9700-CHECK-CHANGE-IN-REC
     *     throw new ConcurrentModificationException(
     *         "Account " + accountId + " was modified concurrently", ex);
     * }
     * }</pre>
     *
     * @param message a human-readable description of the optimistic-lock
     *                conflict
     * @param cause   the underlying exception being wrapped (typically a
     *                Spring or Hibernate optimistic-locking failure); may be
     *                {@code null}
     */
    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a {@code ConcurrentModificationException} with an explicit
     * reason code and message.
     *
     * <p>Use this constructor when the calling service wants to surface a
     * specific reason code such as {@code "OPTIMISTIC_LOCK"},
     * {@code "DATA_CHANGED_BEFORE_UPDATE"} (verbatim port of
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} from
     * {@code app/cbl/COACTUPC.cbl} line 521 and {@code app/cbl/COCRDUPC.cbl}
     * line 207), or {@code "LOCKED_BUT_UPDATE_FAILED"} (verbatim port of
     * {@code LOCKED-BUT-UPDATE-FAILED} from {@code app/cbl/COACTUPC.cbl}
     * line 4079 and {@code app/cbl/COCRDUPC.cbl} line 209).</p>
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
     * // COBOL: COACTUPC.cbl 9700-CHECK-CHANGE-IN-REC -- DATA-WAS-CHANGED-BEFORE-UPDATE
     * if (currentVersion != snapshotVersion) {
     *     throw new ConcurrentModificationException(
     *         "DATA_CHANGED_BEFORE_UPDATE",
     *         "Account " + accountId + " was modified by another user. Please review.");
     * }
     * }</pre>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope (e.g., {@code "OPTIMISTIC_LOCK"},
     *                   {@code "DATA_CHANGED_BEFORE_UPDATE"},
     *                   {@code "LOCKED_BUT_UPDATE_FAILED"}); may be
     *                   {@code null}, in which case
     *                   {@code GlobalExceptionHandler} substitutes a default
     *                   code
     * @param message    a human-readable description of the optimistic-lock
     *                   conflict
     */
    public ConcurrentModificationException(String reasonCode, String message) {
        super(reasonCode, message);
    }

    /**
     * Constructs a {@code ConcurrentModificationException} with an explicit
     * reason code, a message, and a wrapped cause. This is the most
     * expressive constructor.
     *
     * <p>Use this constructor when both a specific reject code AND an
     * underlying exception must be preserved &mdash; typically when adapting
     * a Spring/JPA {@code OptimisticLockingFailureException} (or its concrete
     * subtypes {@code ObjectOptimisticLockingFailureException},
     * {@code StaleObjectStateException}) to the domain hierarchy while
     * surfacing a specific reason code. The cause's stack trace is preserved
     * on the resulting exception, enabling full root-cause analysis in
     * CloudWatch and OpenSearch log indexes (AAP &sect;0.6.6).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * try {
     *     accountRepository.save(account);
     * } catch (OptimisticLockingFailureException ex) {
     *     // COBOL: COACTUPC.cbl lines 4076-4103 -- SYNCPOINT ROLLBACK on REWRITE conflict
     *     throw new ConcurrentModificationException(
     *         "OPTIMISTIC_LOCK",
     *         "Account " + accountId + " was modified by another transaction",
     *         ex);
     * }
     * }</pre>
     *
     * @param reasonCode the explicit reason code to surface in the error
     *                   envelope; may be {@code null}
     * @param message    a human-readable description of the optimistic-lock
     *                   conflict
     * @param cause      the underlying exception being wrapped (typically a
     *                   Spring or Hibernate optimistic-locking failure);
     *                   preserves the stack trace. May be {@code null}.
     */
    public ConcurrentModificationException(String reasonCode, String message, Throwable cause) {
        super(reasonCode, message, cause);
    }
}
