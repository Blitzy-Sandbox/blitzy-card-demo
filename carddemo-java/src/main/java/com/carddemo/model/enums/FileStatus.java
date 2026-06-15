package com.carddemo.model.enums;

/**
 * Type-safe Java equivalents of the two-character COBOL {@code FILE STATUS} codes
 * acted upon by the CardDemo batch programs.
 *
 * <p>The source mainframe application ({@code CardDemo_v1.0-15-g27d6c6f-68}, commit
 * {@code 27d6c6f}) signals the outcome of every VSAM and sequential I/O operation through a
 * two-character {@code FILE STATUS} data item declared on each {@code SELECT} clause. The
 * daily transaction posting program {@code CBTRN02C} and the statement generation programs
 * {@code CBSTM03A} and {@code CBSTM03B} inspect these codes to drive their control flow
 * (success, end-of-file, record-not-found, and similar outcomes).</p>
 *
 * <p>This enum captures exactly the codes those programs act on, preserving each value as a
 * two-character {@link String} so that leading zeros (for example {@code "00"} and
 * {@code "04"}) are retained. It is resolved by
 * {@code com.carddemo.service.shared.FileStatusMapper}, which translates a status into the
 * {@code com.carddemo.exception} hierarchy.</p>
 */
public enum FileStatus {

    /** {@code "00"} &mdash; successful completion. */
    SUCCESS("00"),

    /** {@code "04"} &mdash; read succeeded but the record length did not match the fixed file attributes. */
    READ_LENGTH_MISMATCH("04"),

    /** {@code "10"} &mdash; end of file reached on a sequential read ({@code AT END}). */
    END_OF_FILE("10"),

    /** {@code "22"} &mdash; duplicate key detected on a write to an indexed file. */
    DUPLICATE_KEY("22"),

    /** {@code "23"} &mdash; record not found or invalid key on a keyed read. */
    RECORD_NOT_FOUND("23");

    /** The two-character COBOL {@code FILE STATUS} code backing this constant. */
    private final String code;

    /**
     * Binds a constant to its two-character COBOL {@code FILE STATUS} code.
     *
     * @param code the two-character status code (for example {@code "00"})
     */
    FileStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the two-character COBOL {@code FILE STATUS} code for this constant.
     *
     * @return the two-character status code (for example {@code "00"} or {@code "23"})
     */
    public String getCode() {
        return code;
    }

    /**
     * Resolves a two-character COBOL {@code FILE STATUS} code to its enum constant.
     *
     * <p>The lookup is null-safe: a {@code null} input never raises an exception and yields a
     * {@code null} result, identical to an unmapped code. Callers such as
     * {@code com.carddemo.service.shared.FileStatusMapper} treat a {@code null} result as an
     * unmapped, general I/O status.</p>
     *
     * @param code the two-character {@code FILE STATUS} code (for example {@code "00"} or
     *             {@code "23"}); may be {@code null}
     * @return the matching {@code FileStatus}, or {@code null} if the code is unmapped or
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
