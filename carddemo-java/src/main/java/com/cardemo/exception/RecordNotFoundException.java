package com.cardemo.exception;

import java.io.Serial;

/**
 * Concrete domain exception signalling that a keyed record lookup found no
 * matching row &mdash; the Java&nbsp;25 mapping of the COBOL {@code INVALID KEY}
 * condition and VSAM {@code FILE STATUS '23'} ("record not found") across the
 * CardDemo estate.
 *
 * <p>On the legacy mainframe, every keyed VSAM access ({@code READ ... KEY},
 * {@code REWRITE}, {@code DELETE}) could raise the {@code INVALID KEY}
 * condition, which the programs surfaced by moving a numeric reject code and a
 * fixed description into working storage. The authoritative source for this
 * mapping is the batch posting program {@code app/cbl/CBTRN02C.cbl}, where the
 * pattern occurs three times:</p>
 *
 * <ul>
 *   <li>{@code 1500-A-LOOKUP-XREF} (lines 380-391): {@code READ XREF-FILE ...
 *       INVALID KEY} moves reject code {@code 100} with the description
 *       {@code 'INVALID CARD NUMBER FOUND'} &mdash; the card cross-reference
 *       key was absent.</li>
 *   <li>{@code 1500-B-LOOKUP-ACCT} (lines 393-399): {@code READ ACCOUNT-FILE
 *       ... INVALID KEY} moves reject code {@code 101} with the description
 *       {@code 'ACCOUNT RECORD NOT FOUND'}.</li>
 *   <li>{@code 2800-UPDATE-ACCOUNT-REC} (lines 554-558): {@code REWRITE ...
 *       INVALID KEY} moves reject code {@code 109} with the description
 *       {@code 'ACCOUNT RECORD NOT FOUND'} &mdash; the account key vanished
 *       between read and rewrite.</li>
 * </ul>
 *
 * <p>The same {@code INVALID KEY} / not-found pattern recurs across the online
 * programs whenever an account, card, transaction or user is fetched by its
 * key, so this is the highest-frequency domain exception in the migrated
 * application: any keyed read can miss.</p>
 *
 * <h2>Technology substitution (documented per the Minimal Change Clause)</h2>
 * <p>This type is the single, idiomatic replacement for the scattered
 * {@code INVALID KEY} branches and {@code IF *-STATUS = '23'} comparisons of
 * the COBOL baseline; the behaviour (a keyed lookup that finds nothing) is
 * preserved exactly, only the mechanism changes. Repositories and services
 * throw it when a {@code findBy...} returns empty or an
 * {@code Optional.orElseThrow(...)} fires. Because it is unchecked (inherited
 * from {@link CardDemoException} &rarr; {@link RuntimeException}), it also
 * triggers a Spring {@code @Transactional} rollback by default. HTTP-status
 * mapping is deliberately <em>not</em> performed here: a centralized
 * {@code @RestControllerAdvice} in the web layer translates this exception to
 * an HTTP&nbsp;<strong>404 Not Found</strong> response, keeping this class free
 * of any web-framework coupling so it stays a foundational, low-dependency
 * type.</p>
 *
 * <h2>Diagnostic context</h2>
 * <p>The optional {@link #getEntityType() entityType} and {@link #getKey() key}
 * fields preserve the diagnostic richness the COBOL reject descriptions carried
 * (for example distinguishing "card cross-reference not found" from "account
 * not found") <em>without</em> copying any COBOL text. Both are nullable: the
 * message-only constructors leave them {@code null}, while the convenience
 * {@link #RecordNotFoundException(String, String)} constructor &mdash; the one
 * repositories and services will normally call &mdash; populates both and
 * derives a deterministic detail message. Messages intentionally carry only the
 * lookup key needed for diagnostics and never embed full card numbers or other
 * PII, consistent with the no-hardcoded-secrets posture of the migration.</p>
 *
 * <p><strong>Traceability.</strong> Mapped from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (authoritative blueprint
 * {@code docs/technical-specifications.md} L452 &mdash;
 * {@code RecordNotFoundException.java (\u2190 INVALID KEY)} &mdash; and the
 * {@code FILE STATUS} map at L1063, {@code 23 = RecordNotFoundException}). The
 * COBOL source is read-only reference and is never copied into this
 * repository.</p>
 *
 * @see CardDemoException
 * @see #FILE_STATUS_CODE
 */
public class RecordNotFoundException extends CardDemoException {

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
     * {@code "23"} (record not found on a keyed access).
     *
     * <p>Preserved verbatim &mdash; including the significant leading digits and
     * exactly two characters &mdash; because it is part of the external
     * interface contract. This constant is the contract anchor consumed by the
     * downstream {@code com.cardemo.service.shared.FileStatusMapper}, which maps
     * a raw status code to this exception type. It mirrors the authoritative
     * {@code FILE STATUS} mapping in the migration blueprint
     * ({@code docs/technical-specifications.md} L1063:
     * {@code 23 = RecordNotFoundException}) and the {@code '23'} literal checked
     * in {@code app/cbl/CBTRN02C.cbl} (for example the
     * {@code IF TCATBALF-STATUS = '00' OR '23'} test).</p>
     */
    public static final String FILE_STATUS_CODE = "23";

    /**
     * The kind of record that was not found &mdash; for example
     * {@code "Account"}, {@code "Card"}, {@code "CardCrossReference"},
     * {@code "Transaction"} or {@code "User"}.
     *
     * <p>This mirrors which VSAM dataset the COBOL {@code INVALID KEY} condition
     * fired on (for instance {@code XREF-FILE} versus {@code ACCOUNT-FILE} in
     * {@code CBTRN02C.cbl}). It is {@code null} when the exception was created
     * through a message-only constructor.</p>
     */
    private final String entityType;

    /**
     * The lookup key, rendered as a {@code String}, for which no record existed.
     *
     * <p>Captures the missing key (account id, card number, transaction id, user
     * id, composite key, &hellip;) for diagnostics, analogous to the key the
     * COBOL program had moved into the file's key field before the failing
     * {@code READ}/{@code REWRITE}. It is {@code null} when the exception was
     * created through a message-only constructor.</p>
     */
    private final String key;

    /**
     * Constructs the exception with an explicit detail message, leaving the
     * structured {@link #getEntityType() entityType} and {@link #getKey() key}
     * context unset ({@code null}).
     *
     * <p>Use this overload when a caller has already composed a complete,
     * human-readable message and does not need the structured fields populated.</p>
     *
     * @param message the human-readable detail message describing the missing
     *                record, retained for retrieval via {@link #getMessage()}
     */
    public RecordNotFoundException(String message) {
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
     * Spring {@code DataAccessException} or a JDBC {@code SQLException} raised
     * while performing the keyed read &mdash; while preserving its stack trace.</p>
     *
     * <p><strong>Overload note:</strong> this constructor's signature is
     * {@code (String, Throwable)} whereas {@link #RecordNotFoundException(String,
     * String)} is {@code (String, String)}. A call site passing a bare
     * {@code null} as the second argument is therefore ambiguous and must cast
     * the {@code null} (for example {@code (Throwable) null}) to select this
     * overload; supplying a real {@link Throwable} reference is unambiguous.</p>
     *
     * @param message the human-readable detail message describing the missing
     *                record, retained for retrieval via {@link #getMessage()}
     * @param cause   the underlying cause, retained for retrieval via
     *                {@link #getCause()}; {@code null} indicates the cause is
     *                nonexistent or unknown
     */
    public RecordNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.entityType = null;
        this.key = null;
    }

    /**
     * Constructs the exception from the structured {@code entityType} and
     * {@code key} context, deriving a deterministic detail message of the form
     * {@code "<entityType> not found for key: <key>"}.
     *
     * <p>This is the primary, recommended constructor for repositories and
     * services: it captures both <em>what</em> was being looked up and
     * <em>which key</em> missed, reproducing the diagnostic intent of the COBOL
     * reject descriptions (for example {@code 'ACCOUNT RECORD NOT FOUND'} for
     * the account dataset) in a uniform, structured way. Only the key required
     * for diagnostics is placed in the message; no PII beyond that key is
     * included.</p>
     *
     * @param entityType the kind of record that was not found, for example
     *                   {@code "Account"} or {@code "Card"}; retrievable via
     *                   {@link #getEntityType()}
     * @param key        the lookup key, as a {@code String}, for which no record
     *                   existed; retrievable via {@link #getKey()}
     */
    public RecordNotFoundException(String entityType, String key) {
        super(entityType + " not found for key: " + key);
        this.entityType = entityType;
        this.key = key;
    }

    /**
     * Returns the kind of record that was not found (for example
     * {@code "Account"}), or {@code null} when this exception was created
     * through a message-only constructor.
     *
     * @return the entity type, or {@code null} if unset
     */
    public String getEntityType() {
        return entityType;
    }

    /**
     * Returns the lookup key, as a {@code String}, for which no record existed,
     * or {@code null} when this exception was created through a message-only
     * constructor.
     *
     * @return the missing lookup key, or {@code null} if unset
     */
    public String getKey() {
        return key;
    }
}
