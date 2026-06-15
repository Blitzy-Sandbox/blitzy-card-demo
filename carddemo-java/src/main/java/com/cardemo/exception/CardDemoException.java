package com.cardemo.exception;

import java.io.Serial;

/**
 * Abstract root of the CardDemo domain-exception hierarchy.
 *
 * <p>This class is the single, centralized super-type for every business and
 * data-access failure raised by the CardDemo application. It is the idiomatic
 * Java replacement for the scattered, per-program error handling that the
 * legacy AWS CardDemo mainframe estate implemented in COBOL/CICS, namely:</p>
 *
 * <ul>
 *   <li>{@code FILE STATUS} checks following VSAM {@code READ}/{@code WRITE}/
 *       {@code REWRITE}/{@code DELETE} operations (for example status
 *       {@code 23} "record not found" or {@code 22} "duplicate key");</li>
 *   <li>the {@code INVALID KEY}, {@code DUPKEY} and {@code DUPREC} conditions
 *       and the {@code EVALUATE DFHRESP(...)} branches that inspected CICS
 *       response codes after {@code EXEC CICS} file commands;</li>
 *   <li>the working-storage error flag/text idiom (for example
 *       {@code WS-ERR-FLG} paired with {@code WS-MESSAGE}) used to surface a
 *       failure back to the 3270 screen;</li>
 *   <li>the fatal {@code 9999-ABEND-PROGRAM} paragraph, which issued
 *       {@code CALL 'CEE3ABD'} to abend the running task.</li>
 * </ul>
 *
 * <p>Collapsing all of those mechanisms into one exception root lets a single
 * centralized {@code @RestControllerAdvice} (authored in the web layer, not in
 * this package) catch {@code CardDemoException} and translate any domain
 * failure into a uniform REST error response. That advice is the modern
 * equivalent of every COBOL program formatting its own error text. HTTP-status
 * mapping is deliberately the responsibility of that advice; this class is kept
 * free of any web-framework coupling so that it remains a foundational,
 * low-dependency type.</p>
 *
 * <h2>Design contract</h2>
 * <ul>
 *   <li><strong>Unchecked.</strong> The class extends {@link RuntimeException}
 *       rather than a checked {@link Exception}. Runtime exceptions trigger a
 *       Spring {@code @Transactional} rollback by default, which preserves the
 *       sole {@code SYNCPOINT ROLLBACK} semantics of {@code COACTUPC} (the dual
 *       {@code ACCTDAT} plus {@code CUSTDAT} update) without forcing
 *       {@code throws} clauses onto every service and controller signature.</li>
 *   <li><strong>Abstract.</strong> The root is never thrown directly; only its
 *       concrete subtypes are instantiated. Each subtype maps to a specific
 *       COBOL failure mode, for example {@code RecordNotFoundException}
 *       ({@code INVALID KEY} / FILE STATUS {@code 23}),
 *       {@code DuplicateRecordException} ({@code DUPKEY}/{@code DUPREC}),
 *       {@code ConcurrentModificationException} (optimistic-lock snapshot
 *       mismatch), {@code CreditLimitExceededException} (reject code 102),
 *       {@code ExpiredCardException} (reject code 103) and
 *       {@code ValidationException} (field validation).</li>
 *   <li><strong>Minimal.</strong> Per the migration's Minimal Change Clause the
 *       base carries no business logic, no error-code field, no logging and no
 *       I/O. Any code constant (such as a FILE STATUS or reject code) lives on
 *       the concrete subtype that owns it, keeping each subtype faithful to its
 *       individual COBOL origin.</li>
 * </ul>
 *
 * <p><strong>Traceability.</strong> This is a synthetic base type with no single
 * COBOL source equivalent; it generalizes the error semantics common across the
 * estate. The frozen COBOL baseline is referenced by commit SHA
 * {@code 27d6c6f}; COBOL sources are not copied into this repository.</p>
 *
 * @see RuntimeException
 */
public abstract class CardDemoException extends RuntimeException {

    /**
     * Serialization version identifier.
     *
     * <p>{@link Throwable}, and therefore every exception, is
     * {@link java.io.Serializable}. Declaring an explicit
     * {@code serialVersionUID} (annotated with {@link Serial}) pins the
     * serialized form of the hierarchy and keeps the Java 25 build free of the
     * {@code serial} compiler lint warning.</p>
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new domain exception with the supplied detail message.
     *
     * <p>Visibility is {@code protected} because this class is {@code abstract};
     * only concrete subtypes within the {@code com.cardemo.exception} hierarchy
     * invoke it via {@code super(message)}.</p>
     *
     * @param message the human-readable detail message describing the failure,
     *                retained for later retrieval via {@link #getMessage()}
     */
    protected CardDemoException(String message) {
        super(message);
    }

    /**
     * Constructs a new domain exception with the supplied detail message and
     * underlying cause.
     *
     * <p>The cause overload lets a subtype wrap the originating exception while
     * preserving its stack trace, for example wrapping a JPA
     * {@code OptimisticLockException} inside a
     * {@code ConcurrentModificationException}, or a Spring
     * {@code DataAccessException} inside a {@code RecordNotFoundException}.</p>
     *
     * @param message the human-readable detail message describing the failure,
     *                retained for later retrieval via {@link #getMessage()}
     * @param cause   the underlying cause, retained for later retrieval via
     *                {@link #getCause()}; a {@code null} value indicates the
     *                cause is nonexistent or unknown
     */
    protected CardDemoException(String message, Throwable cause) {
        super(message, cause);
    }
}
