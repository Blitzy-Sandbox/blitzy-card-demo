package com.carddemo.service.shared;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.enums.FileStatus;
import org.springframework.stereotype.Component;

/**
 * Maps two-character COBOL {@code FILE STATUS} codes to the CardDemo exception hierarchy
 * and the {@link FileStatus} enum.
 *
 * <p>This stateless, singleton component is the single, reusable replacement for the
 * repeated {@code IF xxx-STATUS = '00' ... ELSE PERFORM 9999-ABEND-PROGRAM} I/O checks
 * scattered through the batch programs of the source mainframe application
 * ({@code CardDemo_v1.0-15-g27d6c6f-68}, source commit {@code 27d6c6f}). The reference site is
 * the daily transaction posting program {@code app/cbl/CBTRN02C.cbl}; the statement
 * generation program {@code app/cbl/CBSTM03A.CBL} contributes the {@code '00' OR '04'}
 * acceptable-status set. It is injected into batch readers, writers, and processors and into
 * the data-access services.</p>
 *
 * <p>The predicate methods let a caller replicate per-site COBOL control flow (for example the
 * {@code TCATBAL} random read that tolerates {@code '00' OR '23'}) without an exception being
 * raised, while {@link #toException(String, String, Object)} and the {@code throwOnError}
 * overloads convert an unrecoverable status into the corresponding {@link CardDemoException}.
 * The catastrophic {@code 9999-ABEND-PROGRAM} path is represented by throwing
 * {@link FileAccessException}; the JVM is never halted via {@code System.exit} or
 * {@code Runtime}.</p>
 */
@Component
public class FileStatusMapper {

    /**
     * Tests whether the code is the COBOL success status {@code "00"}.
     *
     * @param code the two-character {@code FILE STATUS}; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "00"}
     */
    public boolean isSuccess(String code) {
        return FileStatus.SUCCESS.getCode().equals(code);
    }

    /**
     * Tests whether the code is the end-of-file control signal {@code "10"}.
     *
     * @param code the two-character {@code FILE STATUS}; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "10"}
     */
    public boolean isEndOfFile(String code) {
        return FileStatus.END_OF_FILE.getCode().equals(code);
    }

    /**
     * Tests whether the code is the record-not-found / invalid-key status {@code "23"}.
     *
     * @param code the two-character {@code FILE STATUS}; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "23"}
     */
    public boolean isRecordNotFound(String code) {
        return FileStatus.RECORD_NOT_FOUND.getCode().equals(code);
    }

    /**
     * Tests whether the code is the duplicate-key status {@code "22"}.
     *
     * @param code the two-character {@code FILE STATUS}; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "22"}
     */
    public boolean isDuplicate(String code) {
        return FileStatus.DUPLICATE_KEY.getCode().equals(code);
    }

    /**
     * Tests whether the code is in the acceptable, non-error set {@code "00"} or {@code "04"}
     * (read succeeded; {@code "04"} indicates a record-length mismatch that the source treats
     * as acceptable). The end-of-file signal {@code "10"} is deliberately excluded and is
     * reported separately through {@link #isEndOfFile(String)}.
     *
     * @param code the two-character {@code FILE STATUS}; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "00"} or {@code "04"}
     */
    public boolean isAcceptable(String code) {
        return FileStatus.SUCCESS.getCode().equals(code)
                || FileStatus.READ_LENGTH_MISMATCH.getCode().equals(code);
    }

    /**
     * Resolves a two-character {@code FILE STATUS} code to its {@link FileStatus} constant.
     *
     * @param code the two-character {@code FILE STATUS}; may be {@code null}
     * @return the matching {@link FileStatus}, or {@code null} when the code is unmapped or
     *         {@code code} is {@code null}
     */
    public FileStatus toFileStatus(String code) {
        return FileStatus.fromCode(code);
    }

    /**
     * Translates a {@code FILE STATUS} code into the matching {@link CardDemoException}, or
     * {@code null} when the status is not an error.
     *
     * <p>The success-equivalent statuses ({@code "00"}, {@code "04"}) and the end-of-file
     * control signal ({@code "10"}) produce {@code null}. A duplicate key ({@code "22"}) yields
     * a {@link DuplicateRecordException} and a missing record ({@code "23"}) yields a
     * {@link RecordNotFoundException}, both keyed to {@code entityName}/{@code key}. Any other,
     * unmapped, {@code null}, or {@code '9x'} / non-numeric code is treated as the unrecoverable
     * abend path and yields a {@link FileAccessException} that preserves the raw {@code code}.</p>
     *
     * @param code       the two-character {@code FILE STATUS}; may be {@code null}
     * @param entityName the logical entity / dataset name for diagnostics (for example
     *                   {@code "Account"})
     * @param key        the key value involved in the failing operation; may be {@code null}
     * @return the corresponding {@link CardDemoException}, or {@code null} when the status is
     *         success, acceptable, or end-of-file
     */
    public CardDemoException toException(String code, String entityName, Object key) {
        FileStatus status = FileStatus.fromCode(code);
        if (status == null) {
            return new FileAccessException(
                    "Unrecoverable file access error for " + entityName
                            + " (FILE STATUS=" + code + ")", code);
        }
        return switch (status) {
            case SUCCESS, READ_LENGTH_MISMATCH, END_OF_FILE -> null;
            case DUPLICATE_KEY -> DuplicateRecordException.forKey(entityName, key);
            case RECORD_NOT_FOUND -> RecordNotFoundException.forKey(entityName, key);
        };
    }

    /**
     * Throws the {@link CardDemoException} mapped from the supplied {@code FILE STATUS} code, or
     * returns quietly when the status is success, acceptable, or end-of-file.
     *
     * @param code       the two-character {@code FILE STATUS}; may be {@code null}
     * @param entityName the logical entity / dataset name for diagnostics
     * @param key        the key value involved in the failing operation; may be {@code null}
     * @throws CardDemoException when {@code code} maps to an error status
     */
    public void throwOnError(String code, String entityName, Object key) {
        CardDemoException ex = toException(code, entityName, key);
        if (ex != null) {
            throw ex;
        }
    }

    /**
     * Convenience overload of {@link #throwOnError(String, String, Object)} for call sites that
     * have no key to report; delegates with a {@code null} key.
     *
     * @param code       the two-character {@code FILE STATUS}; may be {@code null}
     * @param entityName the logical entity / dataset name for diagnostics
     * @throws CardDemoException when {@code code} maps to an error status
     */
    public void throwOnError(String code, String entityName) {
        throwOnError(code, entityName, null);
    }
}
