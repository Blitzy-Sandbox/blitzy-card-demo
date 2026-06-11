package com.cardemo.exception;

import java.io.Serial;

/**
 * Concrete domain exception signalling an <strong>optimistic-locking snapshot
 * mismatch</strong> &mdash; a record was changed by another actor between the
 * moment it was read and the moment the update was attempted. It is the
 * Java&nbsp;25 mapping of the COBOL <em>before/after record-image comparison</em>
 * that the two online update programs of the CardDemo estate performed, with
 * CICS read-for-update plus manual snapshot comparison replaced by JPA
 * {@code @Version} optimistic locking.
 *
 * <p>On the legacy mainframe, an update transaction re-read the record just
 * before rewriting it and compared that fresh image against the snapshot it had
 * captured when the screen was first painted. If the two differed, the program
 * raised its "data was changed before update" condition rather than overwrite a
 * concurrent change. The authoritative sources for this mapping are the two
 * online update programs:</p>
 *
 * <ul>
 *   <li>{@code app/cbl/COACTUPC.cbl} (account/customer update, lines 521-522):
 *       the {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} condition guards the dual
 *       {@code ACCTDAT}+{@code CUSTDAT} rewrite. This is also the only program
 *       in the estate that issues a {@code SYNCPOINT ROLLBACK}, so its two-file
 *       update must roll back atomically when the guard fires.</li>
 *   <li>{@code app/cbl/COCRDUPC.cbl} (card update, lines 207-208): the identical
 *       {@code 88 DATA-WAS-CHANGED-BEFORE-UPDATE} condition guards the
 *       {@code CARDDAT} rewrite.</li>
 * </ul>
 *
 * <h2>Technology substitution (documented per the Minimal Change Clause)</h2>
 * <p>This type is the single, idiomatic replacement for the COBOL
 * before/after image comparison; the behaviour (refuse to overwrite a record a
 * concurrent actor has already changed) is preserved exactly, only the
 * mechanism changes. The CICS read-for-update + manual comparison becomes JPA
 * {@code @Version} optimistic locking on the {@code Account} and {@code Card}
 * entities: when a stale version is rewritten, the persistence provider raises a
 * {@code jakarta.persistence.OptimisticLockException} (surfaced by Spring as an
 * {@code ObjectOptimisticLockingFailureException}). The service layer catches
 * that framework exception and rethrows it as a
 * {@code ConcurrentModificationException}, passing the original as the
 * {@link Throwable} <em>cause</em> &mdash; see {@link #ConcurrentModificationException(String, Throwable)}
 * and {@link #forEntity(String, Throwable)}.</p>
 *
 * <p>This class is deliberately kept free of any JPA, Spring or web-framework
 * coupling so that it remains a foundational, low-dependency type: it imports
 * nothing beyond {@code java.*}. The link to the persistence layer is realized
 * at the throw site by passing the framework exception as the cause, never by
 * importing {@code jakarta.persistence.OptimisticLockException} here. Because it
 * is unchecked (inherited from {@link CardDemoException} &rarr;
 * {@link RuntimeException}) it also triggers a Spring {@code @Transactional}
 * rollback by default, which is what preserves the dual-update atomicity of
 * {@code COACTUPC}'s sole {@code SYNCPOINT ROLLBACK}. HTTP-status mapping is
 * deliberately <em>not</em> performed here: a centralized
 * {@code @RestControllerAdvice} in the web layer translates this exception to an
 * HTTP&nbsp;<strong>409 Conflict</strong> response.</p>
 *
 * <h2>Name-clash warning &mdash; this is NOT the JDK type</h2>
 * <p><strong>Important:</strong> the JDK ships a same-named class,
 * {@link java.util.ConcurrentModificationException}, which the collections
 * framework throws on fail-fast iteration. This type
 * ({@code com.cardemo.exception.ConcurrentModificationException}) is the
 * CardDemo <em>domain</em> exception for optimistic-locking conflicts and is
 * entirely unrelated. Because the two share a simple name, any downstream code
 * that needs this domain type must import
 * {@code com.cardemo.exception.ConcurrentModificationException} explicitly (or
 * fully-qualify it); relying on an unqualified {@code ConcurrentModificationException}
 * after an unrelated {@code import java.util.*} would silently bind to the wrong
 * class. Within this source file there is no ambiguity: the JDK type is referred
 * to only by its fully-qualified name.</p>
 *
 * <h2>Diagnostic context</h2>
 * <p>The optional {@link #getEntityType() entityType} field records <em>which</em>
 * record's version check failed (for example {@code "Account"},
 * {@code "Customer"} or {@code "Card"}), preserving the diagnostic intent of the
 * COBOL condition without copying its text. It is {@code null} when the exception
 * was created through a message-only or message+cause constructor, and is
 * populated by the {@link #forEntity(String, Throwable)} factory.</p>
 *
 * <h2>Constructor / factory design note</h2>
 * <p>Two distinct creation behaviours are required: (a) wrap a framework cause
 * under a caller-supplied message, and (b) derive a deterministic message from
 * an {@code entityType} while still wrapping a cause. Expressed as constructors
 * these would both have the parameter list {@code (String, Throwable)}, which
 * Java forbids as a duplicate signature. Behaviour&nbsp;(a) is kept as the
 * primary public constructor {@link #ConcurrentModificationException(String, Throwable)};
 * behaviour&nbsp;(b) is exposed as the static factory
 * {@link #forEntity(String, Throwable)} (Effective Java's named-static-factory
 * idiom), which delegates to a private constructor that stores the
 * {@code entityType}.</p>
 *
 * <p><strong>Traceability.</strong> Mapped from the frozen COBOL baseline at
 * commit SHA {@code 27d6c6f} (authoritative blueprint
 * {@code docs/technical-specifications.md} L454 &mdash;
 * {@code ConcurrentModificationException.java (\u2190 Snapshot mismatch)} &mdash;
 * the optimistic-locking rule at L1061 and the {@code SYNCPOINT} rollback rule
 * at L1059). The COBOL source is read-only reference and is never copied into
 * this repository.</p>
 *
 * @see CardDemoException
 * @see #ConcurrentModificationException(String, Throwable)
 * @see #forEntity(String, Throwable)
 * @see java.util.ConcurrentModificationException the unrelated JDK type of the same simple name
 */
public class ConcurrentModificationException extends CardDemoException {

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
     * The kind of record whose optimistic-lock version check failed &mdash; for
     * example {@code "Account"}, {@code "Customer"} or {@code "Card"}.
     *
     * <p>This mirrors which record the COBOL
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} condition fired on (the
     * {@code ACCTDAT}/{@code CUSTDAT} pair in {@code COACTUPC.cbl} or the
     * {@code CARDDAT} record in {@code COCRDUPC.cbl}). It is {@code null} when the
     * exception was created through a constructor that does not capture it; the
     * {@link #forEntity(String, Throwable)} factory populates it.</p>
     */
    private final String entityType;

    /**
     * Constructs the exception with an explicit detail message, leaving the
     * structured {@link #getEntityType() entityType} context unset
     * ({@code null}).
     *
     * <p>Use this overload when a caller has already composed a complete,
     * human-readable message and has no framework cause to attach &mdash; for
     * example a service that detects the stale-version condition itself, mirroring
     * the COBOL manual before/after image comparison that produced no underlying
     * runtime error.</p>
     *
     * @param message the human-readable detail message describing the conflict,
     *                retained for retrieval via {@link #getMessage()}
     */
    public ConcurrentModificationException(String message) {
        super(message);
        this.entityType = null;
    }

    /**
     * Constructs the exception with an explicit detail message and an underlying
     * cause, leaving the structured {@link #getEntityType() entityType} context
     * unset ({@code null}).
     *
     * <p>This is the <strong>primary</strong> constructor for the service layer:
     * a service that catches a JPA {@code jakarta.persistence.OptimisticLockException}
     * (or the Spring {@code ObjectOptimisticLockingFailureException} that wraps
     * it) rethrows it as
     * {@code new ConcurrentModificationException("...", ex)}, preserving the
     * original stack trace as the {@linkplain #getCause() cause}. The link to the
     * persistence framework is established here, by reference, without this
     * foundational type importing any JPA class.</p>
     *
     * @param message the human-readable detail message describing the conflict,
     *                retained for retrieval via {@link #getMessage()}
     * @param cause   the underlying cause (typically the framework
     *                optimistic-lock exception), retained for retrieval via
     *                {@link #getCause()}; {@code null} indicates the cause is
     *                nonexistent or unknown
     */
    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
        this.entityType = null;
    }

    /**
     * Private, fully-specified constructor that backs the
     * {@link #forEntity(String, Throwable)} factory: it stores the
     * {@code entityType} while delegating the already-derived {@code message} and
     * {@code cause} to {@link CardDemoException}.
     *
     * <p>It carries a three-argument signature ({@code String, String, Throwable})
     * precisely so that it does not collide with the public
     * {@code (String, Throwable)} constructor above; see the class-level
     * "Constructor / factory design note" for the rationale.</p>
     *
     * @param entityType the kind of record whose version check failed; stored for
     *                   retrieval via {@link #getEntityType()}
     * @param message    the derived, human-readable detail message
     * @param cause      the underlying cause, or {@code null} if none
     */
    private ConcurrentModificationException(String entityType, String message, Throwable cause) {
        super(message, cause);
        this.entityType = entityType;
    }

    /**
     * Creates an exception for a named entity type, deriving a deterministic
     * detail message of the form
     * {@code "<entityType> was modified by another user; please review and retry."}
     * and capturing both the {@code entityType} and the underlying {@code cause}.
     *
     * <p>This is the recommended way for the service layer to translate a JPA
     * optimistic-lock failure into the domain exception while retaining the
     * record context, for example:</p>
     * <pre>{@code
     * try {
     *     accountRepository.save(account);   // @Version mismatch -> OptimisticLockException
     * } catch (org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
     *     throw ConcurrentModificationException.forEntity("Account", ex);
     * }
     * }</pre>
     *
     * <p>The phrasing is intentionally generic so that it preserves the intent of
     * the COBOL {@code DATA-WAS-CHANGED-BEFORE-UPDATE} condition
     * ("Record changed by some one else. Please review") without copying that
     * literal text into the migrated application.</p>
     *
     * <p>This is exposed as a static factory rather than a constructor because a
     * {@code (String entityType, Throwable cause)} constructor would have the same
     * {@code (String, Throwable)} signature as
     * {@link #ConcurrentModificationException(String, Throwable)} and could not
     * coexist with it.</p>
     *
     * @param entityType the kind of record whose version check failed, for example
     *                   {@code "Account"}, {@code "Customer"} or {@code "Card"};
     *                   retrievable via {@link #getEntityType()}
     * @param cause      the underlying cause (typically the framework
     *                   optimistic-lock exception), retained for retrieval via
     *                   {@link #getCause()}; may be {@code null}
     * @return a new {@code ConcurrentModificationException} carrying the derived
     *         message, the supplied {@code entityType} and the supplied
     *         {@code cause}
     */
    public static ConcurrentModificationException forEntity(String entityType, Throwable cause) {
        return new ConcurrentModificationException(
                entityType,
                entityType + " was modified by another user; please review and retry.",
                cause);
    }

    /**
     * Returns the kind of record whose optimistic-lock version check failed (for
     * example {@code "Account"}), or {@code null} when this exception was created
     * through a constructor that does not capture the entity type.
     *
     * @return the entity type, or {@code null} if unset
     */
    public String getEntityType() {
        return entityType;
    }
}
