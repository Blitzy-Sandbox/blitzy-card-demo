package com.carddemo.service.shared;

import com.carddemo.exception.CardDemoException;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.enums.FileStatus;
import org.springframework.stereotype.Component;

/**
 * Maps two-character COBOL {@code FILE STATUS} codes (from {@code CBTRN02C} and its batch
 * peers, source commit {@code 27d6c6f}; COBOL not copied) to the CardDemo exception
 * hierarchy and the {@link FileStatus} enum.
 *
 * <p>This stateless, singleton component is the single reusable replacement for the repeated
 * {@code IF xxx-STATUS = '00' ... ELSE PERFORM 9999-ABEND-PROGRAM} I/O checks scattered
 * through the batch programs. The catastrophic {@code 9999-ABEND-PROGRAM} path (a Language
 * Environment abend) is modeled by throwing {@link FileAccessException} rather than
 * terminating the JVM.
 *
 * <p>The predicate methods let callers replicate per-call-site COBOL nuances &mdash; for
 * example tolerating {@code '23'} (record-not-found) on a keyed read to drive a create
 * instead of an abend &mdash; while {@link #toException} and {@link #throwOnError} provide the
 * default success/abend translation. Business reject codes (100&ndash;109) are out of scope
 * for this type and are modeled elsewhere.
 */
@Component
public class FileStatusMapper {

    /**
     * Indicates COBOL FILE STATUS {@code '00'} (successful completion).
     *
     * @param code the raw two-character FILE STATUS code; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "00"}
     */
    public boolean isSuccess(String code) {
        return FileStatus.SUCCESS.getCode().equals(code);
    }

    /**
     * Indicates COBOL FILE STATUS {@code '10'} (end-of-file reached on a sequential read).
     *
     * @param code the raw two-character FILE STATUS code; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "10"}
     */
    public boolean isEndOfFile(String code) {
        return FileStatus.END_OF_FILE.getCode().equals(code);
    }

    /**
     * Indicates COBOL FILE STATUS {@code '23'} (record / key not found on a keyed read).
     *
     * @param code the raw two-character FILE STATUS code; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "23"}
     */
    public boolean isRecordNotFound(String code) {
        return FileStatus.RECORD_NOT_FOUND.getCode().equals(code);
    }

    /**
     * Indicates COBOL FILE STATUS {@code '22'} (duplicate key on an indexed write).
     *
     * @param code the raw two-character FILE STATUS code; may be {@code null}
     * @return {@code true} only when {@code code} equals {@code "22"}
     */
    public boolean isDuplicate(String code) {
        return FileStatus.DUPLICATE_KEY.getCode().equals(code);
    }

    /**
     * Indicates an acceptable, non-error status &mdash; {@code '00'} (success) or {@code '04'}
     * (record read with a length mismatch), the {@code '00' OR '04'} success-equivalent set
     * tolerated by {@code CBSTM03A}. End-of-file ({@code '10'}) is intentionally excluded here
     * and is reported separately via {@link #isEndOfFile(String)}.
     *
     * @param code the raw two-character FILE STATUS code; may be {@code null}
     * @return {@code true} when {@code code} equals {@code "00"} or {@code "04"}
     */
    public boolean isAcceptable(String code) {
        return FileStatus.SUCCESS.getCode().equals(code)
                || FileStatus.READ_LENGTH_MISMATCH.getCode().equals(code);
    }

    /**
     * Resolves a raw FILE STATUS code to its {@link FileStatus} constant.
     *
     * @param code the raw two-character FILE STATUS code; may be {@code null}
     * @return the matching {@link FileStatus}, or {@code null} when the code is unmapped or
     *         {@code null}
     */
    public FileStatus toFileStatus(String code) {
        return FileStatus.fromCode(code);
    }

    /**
     * Translates a FILE STATUS code into the matching CardDemo exception, or {@code null} when
     * the status represents a non-error outcome.
     *
     * <p>Statuses {@code '00'}, {@code '04'} and {@code '10'} yield {@code null} (no error);
     * {@code '22'} yields a {@link DuplicateRecordException} and {@code '23'} a
     * {@link RecordNotFoundException}. Any unmapped, {@code null}, {@code '9x'} or non-numeric
     * code yields a {@link FileAccessException} (the abend path), preserving the raw code via
     * {@link FileAccessException#getFileStatus()}.
     *
     * @param code       the raw two-character FILE STATUS code; may be {@code null}
     * @param entityName the logical entity / dataset name used in the message (e.g. {@code "Account"})
     * @param key        the key value involved, used for {@code '22'} / {@code '23'} messages; may be {@code null}
     * @return the corresponding {@link CardDemoException}, or {@code null} for a non-error status
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
     * Throws the exception that {@link #toException(String, String, Object)} maps the code to,
     * or returns quietly when the status represents a non-error outcome.
     *
     * @param code       the raw two-character FILE STATUS code; may be {@code null}
     * @param entityName the logical entity / dataset name used in the message
     * @param key        the key value involved, used for {@code '22'} / {@code '23'} messages; may be {@code null}
     * @throws CardDemoException when the status maps to an error (the concrete subtype depends
     *                           on the code)
     */
    public void throwOnError(String code, String entityName, Object key) {
        CardDemoException ex = toException(code, entityName, key);
        if (ex != null) {
            throw ex;
        }
    }

    /**
     * Convenience overload of {@link #throwOnError(String, String, Object)} with no key value.
     *
     * @param code       the raw two-character FILE STATUS code; may be {@code null}
     * @param entityName the logical entity / dataset name used in the message
     * @throws CardDemoException when the status maps to an error
     */
    public void throwOnError(String code, String entityName) {
        throwOnError(code, entityName, null);
    }
}
