package com.cardemo.model.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enumeration of COBOL FILE STATUS codes used across all 28 CardDemo COBOL programs.
 *
 * <p>These 2-character codes are the standard COBOL I/O status indicators checked after
 * every file operation (OPEN, READ, WRITE, REWRITE, DELETE, CLOSE). In the migrated
 * Java application, this enum is consumed by {@code FileStatusMapper} to translate
 * legacy status semantics into the Java exception hierarchy.</p>
 *
 * <h3>COBOL FILE STATUS Mapping:</h3>
 * <ul>
 *   <li>{@code '00'} — Successful completion (checked in every I/O operation)</li>
 *   <li>{@code '10'} — End of file / At end (sequential read EOF, CICS browse end)</li>
 *   <li>{@code '21'} — Sequence error on indexed write</li>
 *   <li>{@code '22'} — Duplicate key (WRITE with existing key, DFHRESP DUPKEY/DUPREC)</li>
 *   <li>{@code '23'} — Record not found / Invalid key (DFHRESP NOTFND)</li>
 *   <li>{@code '30'} — Permanent I/O error</li>
 *   <li>{@code '35'} — File not found on OPEN</li>
 *   <li>{@code '41'} — File already open (OPEN on already-open file)</li>
 *   <li>{@code '46'} — Read past end of file (sequential read after EOF)</li>
 * </ul>
 *
 * <h3>Source COBOL References:</h3>
 * <ul>
 *   <li>CBTRN02C.cbl — Batch transaction posting: checks '00', '10', '23' on all file ops</li>
 *   <li>CBACT04C.cbl — Interest calculation: checks '00', '10', '23' with DEFAULT fallback</li>
 *   <li>COACTUPC.cbl — Account update: CICS DFHRESP(NORMAL/NOTFND/DUPKEY) equivalents</li>
 *   <li>COCRDUPC.cbl — Card update: CICS DFHRESP(NORMAL/NOTFND/DUPREC) equivalents</li>
 *   <li>CBTRN03C.cbl — Transaction report: checks '00', '10' for sequential reads</li>
 * </ul>
 *
 * @see com.cardemo.service.shared.FileStatusMapper
 */
public enum FileStatus {

    /**
     * Successful completion of I/O operation.
     * COBOL: FILE STATUS = '00'. Checked after every file operation in all 28 programs.
     * CICS equivalent: DFHRESP(NORMAL).
     */
    SUCCESS("00", "Successful completion"),

    /**
     * End of file reached during sequential read or CICS browse.
     * COBOL: FILE STATUS = '10'. Used in CBTRN02C (DALYTRAN-STATUS = '10'),
     * CBACT04C (TCATBALF-STATUS = '10'), and all batch sequential readers.
     * CICS equivalent: DFHRESP(ENDFILE).
     */
    END_OF_FILE("10", "End of file reached"),

    /**
     * Sequence error — key not in ascending order during indexed sequential write.
     * COBOL: FILE STATUS = '21'. Standard COBOL I/O status for key sequencing violations.
     */
    SEQUENCE_ERROR("21", "Sequence error"),

    /**
     * Duplicate key detected during WRITE operation.
     * COBOL: FILE STATUS = '22'. Occurs when attempting to write a record with
     * an existing primary key value.
     * CICS equivalents: DFHRESP(DUPKEY), DFHRESP(DUPREC).
     */
    DUPLICATE_KEY("22", "Duplicate key detected"),

    /**
     * Record not found — READ/START with nonexistent key.
     * COBOL: FILE STATUS = '23'. Checked in CBTRN02C (TCATBALF-STATUS = '00' OR '23'),
     * CBACT04C (DISCGRP-STATUS = '00' OR '23' and DISCGRP-STATUS = '23').
     * CICS equivalent: DFHRESP(NOTFND).
     * Also covers INVALID KEY condition on indexed READ operations.
     */
    RECORD_NOT_FOUND("23", "Record not found"),

    /**
     * Permanent I/O error — unrecoverable hardware or operating system level failure.
     * COBOL: FILE STATUS = '30'. Represents a fatal I/O condition.
     */
    PERMANENT_ERROR("30", "Permanent I/O error"),

    /**
     * File not found on OPEN — the specified file does not exist.
     * COBOL: FILE STATUS = '35'. Occurs when OPEN references a nonexistent dataset.
     */
    FILE_NOT_FOUND("35", "File not found on open"),

    /**
     * Logic error — file already open when OPEN is attempted.
     * COBOL: FILE STATUS = '41'. Indicates a programming logic error.
     */
    LOGIC_ERROR("41", "File already open"),

    /**
     * Boundary violation — sequential read past end of file.
     * COBOL: FILE STATUS = '46'. Occurs when a READ is attempted after
     * an end-of-file condition has already been encountered.
     */
    BOUNDARY_VIOLATION("46", "Read past end of file");

    /**
     * Static lookup map keyed by 2-character COBOL FILE STATUS code for O(1) retrieval.
     * Built once at class load time from all enum constants via stream collection.
     */
    private static final Map<String, FileStatus> CODE_MAP =
            Arrays.stream(values())
                    .collect(Collectors.toMap(FileStatus::getCode, Function.identity()));

    /** The 2-character COBOL FILE STATUS code (e.g., "00", "23", "10"). */
    private final String code;

    /** Human-readable description of the file status condition. */
    private final String description;

    /**
     * Constructs a FileStatus enum constant with the specified COBOL code and description.
     *
     * @param code        the 2-character COBOL FILE STATUS code
     * @param description human-readable description of the status condition
     */
    FileStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * Returns the FileStatus constant corresponding to the given 2-character COBOL FILE STATUS code.
     *
     * <p>This factory method enables deserialization from COBOL-format status codes and provides
     * O(1) lookup performance via the pre-built static map.</p>
     *
     * @param code the 2-character COBOL FILE STATUS code (e.g., "00", "23")
     * @return the matching FileStatus constant
     * @throws IllegalArgumentException if the code does not match any known FILE STATUS
     */
    public static FileStatus fromCode(String code) {
        FileStatus status = CODE_MAP.get(code);
        if (status == null) {
            throw new IllegalArgumentException("Unknown file status code: " + code);
        }
        return status;
    }

    /**
     * Returns {@code true} if this status indicates successful completion (code "00").
     *
     * <p>In COBOL, this is the primary success check performed after every I/O operation:
     * {@code IF xxxxx-STATUS = '00'}.</p>
     *
     * @return {@code true} if this is the SUCCESS status
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * Returns {@code true} if this status indicates end of file (code "10").
     *
     * <p>In COBOL batch programs, this triggers the end-of-file flag:
     * {@code IF DALYTRAN-STATUS = '10' MOVE 'Y' TO END-OF-FILE}.</p>
     *
     * @return {@code true} if this is the END_OF_FILE status
     */
    public boolean isEndOfFile() {
        return this == END_OF_FILE;
    }

    /**
     * Returns {@code true} if this status indicates an error condition.
     *
     * <p>An error is any status that is neither SUCCESS nor END_OF_FILE. In COBOL programs,
     * non-success/non-EOF statuses typically trigger error display and program abend:
     * {@code PERFORM 9910-DISPLAY-IO-STATUS / PERFORM 9999-ABEND-PROGRAM}.</p>
     *
     * @return {@code true} if this status represents an error (not success and not EOF)
     */
    public boolean isError() {
        return this != SUCCESS && this != END_OF_FILE;
    }

    /**
     * Returns the 2-character COBOL FILE STATUS code.
     *
     * @return the status code string (e.g., "00", "10", "23")
     */
    public String getCode() {
        return code;
    }

    /**
     * Returns the human-readable description of this file status condition.
     *
     * @return the description string
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the 2-character COBOL FILE STATUS code string.
     *
     * <p>This override preserves COBOL comparison semantics. In COBOL, FILE STATUS
     * variables are compared as 2-character strings (e.g., {@code IF xxxxx-STATUS = '00'}).
     * By returning the code from {@code toString()}, Java string comparisons using
     * {@code equals()} or pattern matching produce identical results to COBOL
     * alphanumeric comparisons.</p>
     *
     * @return the 2-character COBOL FILE STATUS code (e.g., "00", "23", "10")
     */
    @Override
    public String toString() {
        return code;
    }
}
