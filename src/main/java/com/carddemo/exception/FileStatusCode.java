package com.carddemo.exception;

import org.springframework.http.HttpStatus;

/**
 * Typed representation of the two-character COBOL {@code FILE STATUS} codes (and
 * their CICS {@code DFHRESP} equivalents) used throughout the legacy CardDemo
 * batch and online programs.
 *
 * <p>Each constant pairs the original file-status literal with a concise
 * description, the HTTP status that best represents the condition when it is
 * surfaced through the REST layer, and a flag indicating whether the status
 * denotes normal end-of-file termination rather than an error.
 *
 * <p>This enum is a foundational, sibling-free member of
 * {@code com.carddemo.exception}: it depends only on the JDK and
 * {@link org.springframework.http.HttpStatus}, so it can be built first and
 * reused by the exception hierarchy, services, batch components, controllers,
 * and configuration without introducing package cycles.
 */
public enum FileStatusCode {

    /** Successful completion of the file operation. */
    SUCCESS("00", "Successful completion", HttpStatus.OK, false),

    /**
     * End of file / end of browse. This is a normal reader-termination signal,
     * never an error: batch readers translate it into {@code null} /
     * {@code Optional.empty()} instead of throwing an exception.
     */
    END_OF_FILE("10", "End of file / end of browse — normal reader termination", HttpStatus.OK, true),

    /** Duplicate key: a record with the supplied key already exists. */
    DUPLICATE_KEY("22", "Duplicate key — record already exists", HttpStatus.CONFLICT, false),

    /** No record was found for the supplied key. */
    RECORD_NOT_FOUND("23", "Record not found for the supplied key", HttpStatus.NOT_FOUND, false),

    /** The file / dataset could not be located when opening. */
    FILE_NOT_FOUND("35", "File/dataset not found on OPEN", HttpStatus.NOT_FOUND, false),

    /** A permanent (non-recoverable) I/O error occurred. */
    PERMANENT_IO_ERROR("30", "Permanent I/O error", HttpStatus.INTERNAL_SERVER_ERROR, false),

    /** VSAM / logic error class: any status whose first character is {@code '9'}. */
    LOGIC_ERROR("90", "VSAM/logic error (status class '9x')", HttpStatus.INTERNAL_SERVER_ERROR, false),

    /** Fallback for any file status that is not explicitly modelled. */
    UNKNOWN("??", "Unmapped/unknown file status", HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final String code;
    private final String description;
    private final HttpStatus httpStatus;
    private final boolean endOfFile;

    FileStatusCode(String code, String description, HttpStatus httpStatus, boolean endOfFile) {
        this.code = code;
        this.description = description;
        this.httpStatus = httpStatus;
        this.endOfFile = endOfFile;
    }

    /**
     * Returns the original two-character COBOL file-status code.
     *
     * @return the file-status literal (for example {@code "00"} or {@code "10"})
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns a concise, human-readable description of the status.
     *
     * @return the status description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the HTTP status representing this condition at the REST boundary.
     *
     * @return the mapped {@link HttpStatus}
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * Indicates whether this status denotes normal end-of-file termination.
     * Only {@link #END_OF_FILE} returns {@code true}.
     *
     * @return {@code true} for {@link #END_OF_FILE}, otherwise {@code false}
     */
    public boolean isEndOfFile() {
        return endOfFile;
    }

    /**
     * Reports whether this status represents successful completion.
     *
     * @return {@code true} if this is {@link #SUCCESS}
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Reports whether this status represents an error condition, that is,
     * neither successful completion nor normal end-of-file termination.
     *
     * @return {@code true} if this is neither {@link #SUCCESS} nor {@link #END_OF_FILE}
     */
    public boolean isError() {
        return this != SUCCESS && this != END_OF_FILE;
    }

    /**
     * Resolves a raw two-character file-status value to its typed constant.
     *
     * <p>The lookup is null-safe. A {@code null} or blank input yields
     * {@link #UNKNOWN}. The input is trimmed and, when longer than two
     * characters, compared on its first two characters. When no exact match
     * exists, any value whose first character is {@code '9'} resolves to
     * {@link #LOGIC_ERROR} (mirroring the legacy {@code IO-STAT1 = '9'} error
     * class); all other unmatched values resolve to {@link #UNKNOWN}.
     *
     * @param rawCode the raw file-status value (may be {@code null})
     * @return the matching {@code FileStatusCode}; never {@code null}
     */
    public static FileStatusCode fromCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return UNKNOWN;
        }
        String trimmed = rawCode.trim();
        String candidate = trimmed.length() > 2 ? trimmed.substring(0, 2) : trimmed;
        for (FileStatusCode status : values()) {
            if (status.code.equals(candidate)) {
                return status;
            }
        }
        if (candidate.charAt(0) == '9') {
            return LOGIC_ERROR;
        }
        return UNKNOWN;
    }

    /**
     * Convenience predicate for batch readers: reports whether the supplied raw
     * status denotes normal end-of-file termination and can therefore be treated
     * as a clean reader stop rather than a thrown exception.
     *
     * @param rawCode the raw file-status value (may be {@code null})
     * @return {@code true} if the status maps to {@link #END_OF_FILE}
     */
    public static boolean isNormalTermination(String rawCode) {
        return fromCode(rawCode).isEndOfFile();
    }
}
