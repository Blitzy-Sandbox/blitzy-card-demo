package com.carddemo.model.enums;

/**
 * Type-safe representation of the two-character COBOL {@code FILE STATUS} codes
 * used by the CardDemo batch programs (notably {@code CBTRN02C} and the
 * statement-generation peers {@code CBSTM03A}/{@code CBSTM03B}; source commit
 * {@code 27d6c6f}).
 *
 * <p>Each constant carries its original two-character code as a {@link String}
 * so that the leading zeros of codes such as {@code "00"} and {@code "04"} are
 * preserved verbatim.
 *
 * <p>Instances are resolved from a raw code by
 * {@code com.carddemo.service.shared.FileStatusMapper}, which translates the
 * status into the application's exception hierarchy. {@link #SUCCESS} is the
 * only non-error status; an unrecognized code resolves to {@code null} and is
 * handled by the mapper as a generic I/O error.
 */
public enum FileStatus {

    /** Successful completion. */
    SUCCESS("00"),

    /** Record read successfully, but its length did not match the fixed file attributes. */
    READ_LENGTH_MISMATCH("04"),

    /** End of file reached on a sequential read (COBOL {@code AT END}). */
    END_OF_FILE("10"),

    /** Duplicate key encountered on a write to an indexed file. */
    DUPLICATE_KEY("22"),

    /** Record not found / invalid key on a keyed read. */
    RECORD_NOT_FOUND("23");

    /** The original two-character COBOL FILE STATUS code. */
    private final String code;

    FileStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the original two-character COBOL FILE STATUS code.
     *
     * @return the two-character code (e.g. {@code "00"}, {@code "23"})
     */
    public String getCode() {
        return code;
    }

    /**
     * Resolves a raw COBOL FILE STATUS code to its matching constant.
     *
     * @param code the two-character COBOL FILE STATUS code (e.g. {@code "00"},
     *             {@code "23"}); may be {@code null}
     * @return the matching constant, or {@code null} if the code is unmapped or
     *         {@code code} is {@code null}
     */
    public static FileStatus fromCode(String code) {
        for (FileStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
