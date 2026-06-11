package com.cardemo.exception;

import java.io.Serial;

/**
 * Concrete domain exception signalling that an insert/add would collide with an
 * existing keyed record &mdash; the Java&nbsp;25 mapping of the COBOL CICS
 * {@code DFHRESP(DUPKEY)} and {@code DFHRESP(DUPREC)} response conditions and
 * the VSAM {@code FILE STATUS '22'} ("duplicate key" on {@code WRITE}) across
 * the CardDemo estate.
 *
 * <p>On the legacy mainframe, an attempt to {@code WRITE} a record whose key
 * already existed raised the {@code DUPKEY}/{@code DUPREC} CICS condition (or,
 * for native VSAM access, {@code FILE STATUS '22'}). The programs surfaced this
 * by setting an error flag and moving a fixed "... already exist..." description
 * into working storage. The authoritative sources for this mapping are the two
 * add paths in the estate:</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COTRN02C.cbl} (transaction add, lines 735-739): the
 *       {@code EVALUATE WS-RESP-CD} branch {@code WHEN DFHRESP(DUPKEY) WHEN
 *       DFHRESP(DUPREC)} sets {@code WS-ERR-FLG} to {@code 'Y'} and moves
 *       {@code 'Tran ID already exist...'} into {@code WS-MESSAGE} &mdash; the
 *       transaction id key already existed.</li>
 *   <li>{@code app/cbl/COUSR01C.cbl} (user add, lines 260-264): the equivalent
 *       {@code WHEN DFHRESP(DUPKEY) WHEN DFHRESP(DUPREC)} branch moves
 *       {@code 'User ID already exist...'} into {@code WS-MESSAGE} &mdash; the
 *       user id key already existed.</li>
 * </ul>
 *
 * <p>The same duplicate-on-add pattern recurs wherever a VSAM {@code WRITE} can
 * return {@code FILE STATUS '22'}, so this is the canonical domain exception for
 * unique-constraint violations on add operations in the migrated application.</p>
 *
 * <h2>Technology substitution (documented per the Minimal Change Clause)</h2>
 * <p>This type is the single, idiomatic replacement for the scattered
 * {@code DUPKEY}/{@code DUPREC} branches and {@code IF *-STATUS = '22'}
 * comparisons of the COBOL baseline; the behaviour (an add that collides with an
 * existing key) is preserved exactly, only the mechanism changes. Services and
 * repositories throw it when an add would violate a primary-key or unique
 * constraint &mdash; for example when a JPA {@code save}/{@code persist} of a new
 * row surfaces a {@code DataIntegrityViolationException} backed by a database
 * unique-constraint violation. Because it is unchecked (inherited from
 * {@link CardDemoException} &rarr; {@link RuntimeException}), it also triggers a
 * Spring {@code @Transactional} rollback by default. HTTP-status mapping is
 * deliberately <em>not</em> performed here: a centralized
 * {@code @RestControllerAdvice} in the web layer translates this exception to an
 * HTTP&nbsp;<strong>409 Conflict</strong> response, keeping this class free of
 * any web-framework coupling so it stays a foundational, low-dependency type.</p>
 *
 * <h2>Diagnostic context</h2>
 * <p>The optional {@link #getEntityType() entityType} and {@link #getKey() key}
 * fields preserve the diagnostic richness the COBOL "... already exist..."
 * descriptions carried (for example distinguishing a duplicate transaction from
 * a duplicate user) <em>without</em> copying any COBOL text. Both are nullable:
 * the message-only constructors leave them {@code null}, while the convenience
 * {@link #DuplicateRecordException(String, String)} constructor &mdash; the one
 * repositories and services will normally call &mdash; populates both and
 * derives a deterministic detail message. Messages intentionally carry only the
 * colliding key needed for diagnostics and never embed full card numbers or
 * other PII, consistent with the no-hardcoded-secrets posture of the
 * migration.</p>
 *
 * <p><strong>Traceability.</strong> Mapped from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (authoritative blueprint
 * {@code docs/technical-specifications.md} L453 &mdash;
 * {@code DuplicateRecordException.java (\u2190 DUPKEY/DUPREC)} &mdash; and the
 * {@code FILE STATUS} map at L1063, {@code 22 = duplicate}). The blueprint tree
 * and folder specification name this type {@code DuplicateRecordException} and
 * are authoritative over the L1063 prose, which informally calls it
 * "DuplicateKeyException". The COBOL source is read-only reference and is never
 * copied into this repository.</p>
 *
 * @see CardDemoException
 * @see #FILE_STATUS_CODE
 */
public class DuplicateRecordException extends CardDemoException {

    /**
     * Serialization version identifier.
     *
     * <p>{@link Throwable}, and therefore every exception, is
     * {@link java.io.Serializable}. Declaring an explicit
     * {@code serialVersionUID} (annotated with {@link Serial}) pins the
     * serialized form and keeps the Java&nbsp;25 build free of the
     * {@code serial} compiler lint warning, matching the convention of the
     * {@link CardDemoException} base type.</p>
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The COBOL/VSAM {@code FILE STATUS} value this exception represents:
     * {@code "22"} (duplicate key on a {@code WRITE}).
     *
     * <p>Preserved verbatim &mdash; including the significant leading digit and
     * exactly two characters &mdash; because it is part of the external
     * interface contract. This constant is the contract anchor consumed by the
     * downstream {@code com.cardemo.service.shared.FileStatusMapper}, which maps
     * a raw status code to this exception type. It mirrors the authoritative
     * {@code FILE STATUS} mapping in the migration blueprint
     * ({@code docs/technical-specifications.md} L1063: {@code 22 = duplicate})
     * and corresponds to the {@code DFHRESP(DUPKEY)}/{@code DFHRESP(DUPREC)}
     * branches checked in {@code app/cbl/COTRN02C.cbl} (L735-739) and
     * {@code app/cbl/COUSR01C.cbl} (L260-264).</p>
     */
    public static final String FILE_STATUS_CODE = "22";

    /**
     * The kind of record that already existed &mdash; for example
     * {@code "Transaction"} or {@code "User"}.
     *
     * <p>This mirrors which VSAM dataset the COBOL {@code DUPKEY}/{@code DUPREC}
     * condition fired on (for instance the transaction file in
     * {@code COTRN02C.cbl} versus the user-security file in
     * {@code COUSR01C.cbl}). It is {@code null} when the exception was created
     * through a message-only constructor.</p>
     */
    private final String entityType;

    /**
     * The colliding key, rendered as a {@code String}, for which a record
     * already existed.
     *
     * <p>Captures the duplicate key (transaction id, user id, account id,
     * composite key, &hellip;) for diagnostics, analogous to the key the COBOL
     * program had moved into the file's key field before the failing
     * {@code WRITE}. It is {@code null} when the exception was created through a
     * message-only constructor.</p>
     */
    private final String key;

    /**
     * Constructs the exception with an explicit detail message, leaving the
     * structured {@link #getEntityType() entityType} and {@link #getKey() key}
     * context unset ({@code null}).
     *
     * <p>Use this overload when a caller has already composed a complete,
     * human-readable message and does not need the structured fields
     * populated.</p>
     *
     * @param message the human-readable detail message describing the duplicate
     *                record, retained for retrieval via {@link #getMessage()}
     */
    public DuplicateRecordException(String message) {
        super(message);
        this.entityType = null;
        this.key = null;
    }

    /**
     * Constructs the exception with an explicit detail message and an underlying
     * cause, leaving the structured {@link #getEntityType() entityType} and
     * {@link #getKey() key} context unset ({@code null}).
     *
     * <p>Use this overload to wrap a lower-level failure &mdash; for example a
     * Spring {@code DataIntegrityViolationException} or a JDBC
     * {@code SQLException} (SQL state {@code 23505}) raised by the database when
     * the insert violated a unique constraint &mdash; while preserving its stack
     * trace.</p>
     *
     * <p><strong>Overload note:</strong> this constructor's signature is
     * {@code (String, Throwable)} whereas {@link #DuplicateRecordException(String,
     * String)} is {@code (String, String)}. A call site passing a bare
     * {@code null} as the second argument is therefore ambiguous and must cast
     * the {@code null} (for example {@code (Throwable) null}) to select this
     * overload; supplying a real {@link Throwable} reference is unambiguous.</p>
     *
     * @param message the human-readable detail message describing the duplicate
     *                record, retained for retrieval via {@link #getMessage()}
     * @param cause   the underlying cause, retained for retrieval via
     *                {@link #getCause()}; {@code null} indicates the cause is
     *                nonexistent or unknown
     */
    public DuplicateRecordException(String message, Throwable cause) {
        super(message, cause);
        this.entityType = null;
        this.key = null;
    }

    /**
     * Constructs the exception from the structured {@code entityType} and
     * {@code key} context, deriving a deterministic detail message of the form
     * {@code "<entityType> already exists for key: <key>"}.
     *
     * <p>This is the primary, recommended constructor for repositories and
     * services: it captures both <em>what</em> was being added and
     * <em>which key</em> collided, reproducing the diagnostic intent of the
     * COBOL "... already exist..." descriptions (for example
     * {@code 'Tran ID already exist...'} in {@code COTRN02C.cbl} or
     * {@code 'User ID already exist...'} in {@code COUSR01C.cbl}) in a uniform,
     * structured way without copying any COBOL text. Only the key required for
     * diagnostics is placed in the message; no PII beyond that key is
     * included.</p>
     *
     * @param entityType the kind of record that already existed, for example
     *                   {@code "Transaction"} or {@code "User"}; retrievable via
     *                   {@link #getEntityType()}
     * @param key        the colliding key, as a {@code String}, for which a
     *                   record already existed; retrievable via
     *                   {@link #getKey()}
     */
    public DuplicateRecordException(String entityType, String key) {
        super(entityType + " already exists for key: " + key);
        this.entityType = entityType;
        this.key = key;
    }

    /**
     * Returns the kind of record that already existed (for example
     * {@code "Transaction"}), or {@code null} when this exception was created
     * through a message-only constructor.
     *
     * @return the entity type, or {@code null} if unset
     */
    public String getEntityType() {
        return entityType;
    }

    /**
     * Returns the colliding key, as a {@code String}, for which a record already
     * existed, or {@code null} when this exception was created through a
     * message-only constructor.
     *
     * @return the duplicate lookup key, or {@code null} if unset
     */
    public String getKey() {
        return key;
    }
}
