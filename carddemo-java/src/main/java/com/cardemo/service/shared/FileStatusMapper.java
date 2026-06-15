package com.cardemo.service.shared;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;

import org.springframework.stereotype.Service;

/**
 * Centralised translator from the COBOL/VSAM two-character {@code FILE STATUS}
 * codes to the CardDemo typed exception hierarchy and {@link FileStatus} enum.
 *
 * <p>This {@code @Service} is the Java&nbsp;25 realization of AAP &sect;0.4.1
 * (tech-spec L640: <em>{@code FileStatusMapper.java}&nbsp;&larr;
 * {@code app/cbl/CBTRN02C.cbl} (FILE STATUS patterns)&nbsp;&mdash; FILE STATUS
 * &rarr; exception hierarchy mapping</em>) and of the AAP &sect;0.7.5
 * {@code FILE STATUS} error-mapping rule. It collapses the per-file
 * {@code IF *-STATUS = '00' / '10' / '23' ...} comparisons that the legacy AWS
 * CardDemo estate repeated after every VSAM {@code OPEN}/{@code READ}/
 * {@code WRITE}/{@code REWRITE} &mdash; together with the fatal
 * {@code 9999-ABEND-PROGRAM} path &mdash; into one reusable, stateless seam that
 * readers, writers, repositories and services can call to convert a raw I/O
 * status into either a normal outcome or a thrown domain exception.</p>
 *
 * <h2>Authoritative COBOL source</h2>
 * <p>The canonical pattern is the batch posting program
 * {@code app/cbl/CBTRN02C.cbl}, whose status handling this class reproduces:</p>
 * <ul>
 *   <li><strong>{@code '00'} success</strong> &mdash; {@code 0000-DALYTRAN-OPEN}
 *       (L236&ndash;252) and {@code 1000-DALYTRAN-GET-NEXT} (L347): an
 *       {@code IF *-STATUS = '00'} that continues normal processing.</li>
 *   <li><strong>{@code '10'} end-of-file</strong> &mdash;
 *       {@code 1000-DALYTRAN-GET-NEXT} (L351): {@code IF DALYTRAN-STATUS = '10'}
 *       sets {@code APPL-EOF} and {@code MOVE 'Y' TO END-OF-FILE}, the sequential
 *       read-loop terminator. EOF is a <em>normal control signal</em> and is
 *       never an error.</li>
 *   <li><strong>{@code '23'} record-not-found</strong> &mdash; the
 *       {@code TCATBAL-FILE} read (L470&ndash;495) takes the {@code INVALID KEY}
 *       branch and treats {@code IF TCATBALF-STATUS = '00' OR '23'} as
 *       acceptable (L481).</li>
 *   <li><strong>any other non-success status</strong> &mdash; routes to
 *       {@code 9999-ABEND-PROGRAM} (L707&ndash;711:
 *       {@code DISPLAY 'ABENDING PROGRAM'} / {@code CALL 'CEE3ABD'}), the fatal
 *       task-abend path; this includes {@code '35'} (dataset not available on
 *       {@code OPEN}).</li>
 * </ul>
 *
 * <h2>COBOL &rarr; Java substitution (documented per the Minimal Change Clause, AAP &sect;0.7.1)</h2>
 * <p>The scattered two-byte {@code FILE STATUS} string checks are replaced by a
 * single typed translation point. Behaviour is preserved exactly &mdash; only the
 * mechanism changes from procedural status comparison to typed exceptions:</p>
 * <ul>
 *   <li>{@code "00"} &rarr; {@link FileStatus#SUCCESS}: returns normally
 *       (the I/O succeeded).</li>
 *   <li>{@code "10"} &rarr; {@link FileStatus#END_OF_FILE}: returns normally
 *       (EOF is a normal control signal, <em>not</em> an error). Callers that
 *       need to detect EOF use {@link #isEndOfFile(String)}.</li>
 *   <li>{@code "23"} &rarr; {@link FileStatus#RECORD_NOT_FOUND}: throws
 *       {@link RecordNotFoundException} (centralised advice &rarr; HTTP&nbsp;404).</li>
 *   <li>{@code "22"} &rarr; {@link FileStatus#DUPLICATE_KEY}: throws
 *       {@link DuplicateRecordException} (centralised advice &rarr; HTTP&nbsp;409).</li>
 *   <li>{@code "35"} &rarr; {@link FileStatus#FILE_NOT_FOUND}, and any other
 *       unmapped status, throws an unrecoverable {@link IllegalStateException}
 *       that mirrors {@code 9999-ABEND-PROGRAM} (centralised advice &rarr;
 *       HTTP&nbsp;500). The exception package deliberately has no
 *       {@code FileNotFoundException}; a dataset-open failure is infrastructure,
 *       not a business outcome, so no {@link CardDemoException} subtype models
 *       it.</li>
 * </ul>
 *
 * <p>Both thrown domain exceptions ({@link RecordNotFoundException} and
 * {@link DuplicateRecordException}) extend the abstract
 * {@link CardDemoException} root, so a single {@code @RestControllerAdvice} in
 * the web layer renders the uniform REST error response &mdash; the modern
 * equivalent of every COBOL program formatting its own error text.</p>
 *
 * <h2>Scope boundary</h2>
 * <p>This mapper handles <em>only</em> the two-byte I/O {@code FILE STATUS}
 * values. The COBOL business reject codes {@code 100}/{@code 101} (invalid card
 * / account on the {@code 1500-A/B-LOOKUP} paths), {@code 102} (over-limit) and
 * {@code 103} (after-expiration) are <em>not</em> {@code FILE STATUS} codes;
 * they belong to the batch validation processor and the
 * {@code CreditLimitExceededException}/{@code ExpiredCardException}/
 * {@code RejectCode} path and are intentionally out of scope here.</p>
 *
 * <h2>Concurrency</h2>
 * <p>The component is stateless and immutable: it declares no mutable instance
 * fields, performs no I/O, and is therefore safe to share as a Spring singleton
 * across all calling threads.</p>
 *
 * <p><strong>Traceability.</strong> Behaviour is translated from the frozen AWS
 * CardDemo COBOL baseline at commit SHA {@code 27d6c6f}. The COBOL source is
 * read-only reference and is <em>never copied</em> into this repository (AAP
 * &sect;0.7.2 preservation requirements).</p>
 *
 * @see FileStatus
 * @see CardDemoException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
@Service
public class FileStatusMapper {

    /**
     * Reports whether the supplied raw {@code FILE STATUS} code denotes a
     * successful I/O operation.
     *
     * <p>This is the idiomatic replacement for the pervasive COBOL
     * {@code IF *-STATUS = '00'} "all OK" test (for example
     * {@code IF DALYTRAN-STATUS = '00'} at {@code CBTRN02C.cbl} L239 and L347).
     * Like the COBOL comparison &mdash; which never abends, it simply evaluates
     * true or false &mdash; this predicate is total and null-safe: an unknown or
     * {@code null} code is not "success" and yields {@code false} rather than
     * throwing.</p>
     *
     * @param statusCode the exact two-character {@code FILE STATUS} code (may be
     *                   {@code null})
     * @return {@code true} if and only if {@code statusCode} equals the success
     *         code {@code "00"}; {@code false} otherwise
     */
    public boolean isSuccess(final String statusCode) {
        // COBOL substitution: IF *-STATUS = '00' (a pure equality test that never
        // abends) -> String equality against FileStatus.SUCCESS ("00").
        return FileStatus.SUCCESS.getCode().equals(statusCode);
    }

    /**
     * Reports whether the supplied raw {@code FILE STATUS} code denotes
     * end-of-file on a sequential read.
     *
     * <p>This mirrors the COBOL {@code IF DALYTRAN-STATUS = '10'} branch of
     * {@code 1000-DALYTRAN-GET-NEXT} ({@code CBTRN02C.cbl} L351), which sets the
     * {@code APPL-EOF} result and {@code MOVE 'Y' TO END-OF-FILE} to terminate
     * the read loop. End-of-file is a normal control signal, <em>not</em> an
     * error, so callers detect it through this predicate rather than by catching
     * an exception. The check is total and null-safe.</p>
     *
     * @param statusCode the exact two-character {@code FILE STATUS} code (may be
     *                   {@code null})
     * @return {@code true} if and only if {@code statusCode} equals the
     *         end-of-file code {@code "10"}; {@code false} otherwise
     */
    public boolean isEndOfFile(final String statusCode) {
        // COBOL substitution: IF *-STATUS = '10' -> END-OF-FILE = 'Y' loop
        // terminator (CBTRN02C L351). Pure equality against FileStatus.END_OF_FILE.
        return FileStatus.END_OF_FILE.getCode().equals(statusCode);
    }

    /**
     * Translates a raw {@code FILE STATUS} code into a normal return or the
     * mapped CardDemo exception, using the supplied diagnostic context.
     *
     * <p>This is the central seam that replaces the scattered COBOL status
     * checks of {@code app/cbl/CBTRN02C.cbl}. Successful I/O ({@code "00"}) and
     * end-of-file ({@code "10"}) return normally; record-not-found
     * ({@code "23"}) and duplicate-key ({@code "22"}) raise the corresponding
     * {@link CardDemoException} subtype; and {@code "35"} or any unmapped status
     * raises an unrecoverable {@link IllegalStateException} that mirrors the
     * COBOL {@code 9999-ABEND-PROGRAM} path. See the class Javadoc for the full
     * mapping table.</p>
     *
     * <p>The raw code is first resolved through {@link FileStatus#fromCode(String)};
     * a {@code null} or unrecognised code (for which {@code fromCode} raises
     * {@link IllegalArgumentException}) is converted here into the unrecoverable
     * {@link IllegalStateException} rather than being allowed to escape, so
     * callers see a single, clear failure type for "this status cannot be
     * handled".</p>
     *
     * @param statusCode the exact two-character {@code FILE STATUS} code returned
     *                   by the I/O operation (for example {@code "00"} or
     *                   {@code "23"}); a {@code null} value is treated as an
     *                   unmapped status
     * @param entityType the kind of record involved (for example
     *                   {@code "Account"} or {@code "Transaction"}), used to
     *                   build a diagnostic message; may be {@code null} when no
     *                   context is available
     * @param key        the lookup or colliding key, as a {@code String}, used
     *                   for diagnostics; may be {@code null}
     * @throws RecordNotFoundException  if {@code statusCode} is {@code "23"}
     *                                  (record not found / {@code INVALID KEY})
     * @throws DuplicateRecordException if {@code statusCode} is {@code "22"}
     *                                  (duplicate key on {@code WRITE})
     * @throws IllegalStateException    if {@code statusCode} is {@code "35"}
     *                                  (dataset not available) or any other
     *                                  unmapped/unrecognised status &mdash; the
     *                                  unrecoverable {@code 9999-ABEND-PROGRAM}
     *                                  equivalent
     */
    public void verify(final String statusCode, final String entityType, final String key) {
        final FileStatus status;
        try {
            // Resolve the raw two-byte code to its typed FileStatus. fromCode
            // throws IllegalArgumentException for any value the estate does not
            // enumerate (and for null); that maps to the COBOL "any other status"
            // abend handled below.
            status = FileStatus.fromCode(statusCode);
        } catch (final IllegalArgumentException unknownStatus) {
            // COBOL substitution: an unrecognised FILE STATUS falls through to
            // 9999-ABEND-PROGRAM (CBTRN02C L707-711). Surface a clear,
            // unrecoverable IllegalStateException instead of leaking the raw
            // IllegalArgumentException from fromCode; preserve it as the cause.
            throw abend(statusCode, null, entityType, unknownStatus);
        }
        switch (status) {
            // '00' -> APPL-AOK (CBTRN02C L239/L347): the I/O succeeded; continue
            // normally with no exception.
            case SUCCESS -> {
                /* successful I/O - nothing to translate */
            }
            // '10' -> APPL-EOF (CBTRN02C L351): end-of-file is a NORMAL control
            // signal that terminates a sequential read loop, NOT an error, so
            // verify() must not throw. Callers detect EOF via isEndOfFile(String).
            case END_OF_FILE -> {
                /* end-of-file - normal control signal, not an error */
            }
            // '23' -> INVALID KEY / record not found (CBTRN02C L481).
            case RECORD_NOT_FOUND -> throw recordNotFound(entityType, key);
            // '22' -> duplicate key on WRITE (DFHRESP(DUPKEY)/DUPREC write paths).
            case DUPLICATE_KEY -> throw duplicateRecord(entityType, key);
            // '35' -> dataset not available on OPEN: the COBOL non-'00' OPEN
            // status routes to 9999-ABEND-PROGRAM (CBTRN02C L236-252). No
            // CardDemoException subtype models a dataset-open failure.
            case FILE_NOT_FOUND -> throw abend(status.getCode(), status.getDescription(), entityType, null);
            // Defensive: any FileStatus constant introduced in future with no
            // business mapping follows the same COBOL "any other status ->
            // 9999-ABEND-PROGRAM" fatal path. Arrow form has no silent
            // fall-through (AAP 0.7.4 control-flow preservation).
            default -> throw abend(status.getCode(), status.getDescription(), entityType, null);
        }
    }

    /**
     * Convenience overload of {@link #verify(String, String, String)} for call
     * sites that have no entity/key context.
     *
     * <p>Behaviour is identical to the three-argument form; when a throwing
     * status is encountered, the resulting exception carries a generic message
     * derived from the {@link FileStatus} description (for example
     * {@code "Record not found (file status '23')"}) instead of an
     * entity/key-specific message.</p>
     *
     * @param statusCode the exact two-character {@code FILE STATUS} code (may be
     *                   {@code null}, which is treated as an unmapped status)
     * @throws RecordNotFoundException  if {@code statusCode} is {@code "23"}
     * @throws DuplicateRecordException if {@code statusCode} is {@code "22"}
     * @throws IllegalStateException    if {@code statusCode} is {@code "35"} or
     *                                  any other unmapped/unrecognised status
     */
    public void verify(final String statusCode) {
        // Delegate with no entity/key context; the exception factories below
        // produce a description-based message when entityType is null.
        verify(statusCode, null, null);
    }

    /**
     * Builds the {@link RecordNotFoundException} for a {@code "23"} status.
     *
     * <p>When {@code entityType} is supplied the structured
     * {@code (entityType, key)} constructor is used (yielding the message
     * {@code "<entityType> not found for key: <key>"}); otherwise a message-only
     * exception derived from the {@link FileStatus#RECORD_NOT_FOUND} description
     * and the {@link RecordNotFoundException#FILE_STATUS_CODE} contract constant
     * is produced, so no two-character literal is duplicated in this class.</p>
     *
     * @param entityType the kind of record, or {@code null} when unavailable
     * @param key        the missing key, or {@code null}
     * @return the exception to throw (never {@code null})
     */
    private RecordNotFoundException recordNotFound(final String entityType, final String key) {
        if (entityType != null) {
            // Both arguments are String-typed, so the (String entityType, String
            // key) constructor is selected unambiguously over (String, Throwable).
            return new RecordNotFoundException(entityType, key);
        }
        // No caller context: build a non-sensitive message from the enum
        // description plus the exception's own FILE STATUS contract constant.
        return new RecordNotFoundException(
                FileStatus.RECORD_NOT_FOUND.getDescription()
                        + " (file status '" + RecordNotFoundException.FILE_STATUS_CODE + "')");
    }

    /**
     * Builds the {@link DuplicateRecordException} for a {@code "22"} status.
     *
     * <p>When {@code entityType} is supplied the structured
     * {@code (entityType, key)} constructor is used (yielding the message
     * {@code "<entityType> already exists for key: <key>"}); otherwise a
     * message-only exception derived from the {@link FileStatus#DUPLICATE_KEY}
     * description and the {@link DuplicateRecordException#FILE_STATUS_CODE}
     * contract constant is produced.</p>
     *
     * @param entityType the kind of record, or {@code null} when unavailable
     * @param key        the colliding key, or {@code null}
     * @return the exception to throw (never {@code null})
     */
    private DuplicateRecordException duplicateRecord(final String entityType, final String key) {
        if (entityType != null) {
            // String-typed arguments select the (String entityType, String key)
            // constructor unambiguously over (String, Throwable).
            return new DuplicateRecordException(entityType, key);
        }
        return new DuplicateRecordException(
                FileStatus.DUPLICATE_KEY.getDescription()
                        + " (file status '" + DuplicateRecordException.FILE_STATUS_CODE + "')");
    }

    /**
     * Builds the unrecoverable {@link IllegalStateException} that mirrors the
     * COBOL {@code 9999-ABEND-PROGRAM} fatal path ({@code CBTRN02C.cbl}
     * L707&ndash;711) for {@code "35"} and for any unmapped/unrecognised status.
     *
     * <p>The detail message is ASCII-only and carries only a diagnostic entity
     * hint &mdash; never PII or a full key value. When an originating throwable
     * is supplied (for example the {@link IllegalArgumentException} raised by
     * {@link FileStatus#fromCode(String)} for an unknown code) it is preserved
     * as the cause to retain the stack trace.</p>
     *
     * @param statusCode  the raw status code that could not be handled
     * @param description the {@link FileStatus} description when the code was
     *                    recognised, or {@code null} when it was not
     * @param entityType  the diagnostic entity hint, or {@code null}
     * @param cause       the originating throwable to preserve as the cause, or
     *                    {@code null} when there is none
     * @return the exception to throw (never {@code null})
     */
    private IllegalStateException abend(final String statusCode, final String description,
                                        final String entityType, final Throwable cause) {
        final StringBuilder message = new StringBuilder("Unrecoverable file status '")
                .append(statusCode)
                .append('\'');
        if (description != null) {
            message.append(" (").append(description).append(')');
        }
        if (entityType != null) {
            message.append(" on ").append(entityType);
        }
        message.append(" - mirrors COBOL 9999-ABEND-PROGRAM");
        // 'cause' is statically typed Throwable, so the (String, Throwable)
        // constructor is selected unambiguously when it is present; the null
        // branch uses the (String) constructor.
        return (cause == null)
                ? new IllegalStateException(message.toString())
                : new IllegalStateException(message.toString(), cause);
    }
}
