/*
 * FileStatusMapper.java
 *
 * Central exception translation utility that maps COBOL FILE STATUS codes
 * (2-character string codes) to the custom Java exception hierarchy.
 *
 * This is a stateless utility class used by service and batch components when
 * they need to translate legacy COBOL FILE STATUS semantics into Java exceptions
 * for consistent error handling across the entire CardDemo application.
 *
 * COBOL Traceability (original repository commit SHA 27d6c6f):
 * - CBTRN02C.cbl lines 103-133: FILE STATUS variable declarations for 6 files
 *   (DALYTRAN, TRANFILE, XREFFILE, DALYREJS, ACCTFILE, TCATBALF) plus IO-STATUS
 * - CBTRN02C.cbl lines 714-727: 9910-DISPLAY-IO-STATUS paragraph — binary
 *   decoding for '9x' extended status codes, numeric fallback for standard codes
 * - CBTRN02C.cbl lines 239, 257, 276, 347, 351, 481: FILE STATUS check patterns
 *   (success = '00', EOF = '10', optional = '00' OR '23')
 * - CBACT01C.cbl lines 94, 98, 176-189: Identical FILE STATUS patterns for
 *   account file sequential read (success, EOF, error)
 * - COACTUPC.cbl: CICS DFHRESP(NORMAL), DFHRESP(NOTFND), DFHRESP(DUPKEY)
 *   patterns that map to the same FILE STATUS equivalents
 * - COCRDUPC.cbl: CICS DFHRESP(NORMAL), DFHRESP(NOTFND), DFHRESP(DUPREC)
 *   patterns for card record operations
 *
 * Design Decision (Decision Log D-EXCEPT-005):
 * FILE STATUS codes are preserved as 2-character strings (not integers) to
 * maintain exact fidelity with COBOL PIC X(2) representation. The COBOL pattern
 * of checking IO-STATUS NOT NUMERIC OR IO-STAT1 = '9' for extended status codes
 * is mapped to the default case in the switch statement. The class returns null
 * for success/EOF codes rather than throwing, allowing callers to distinguish
 * between error conditions and normal flow control (EOF) without exception overhead.
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.service.shared;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps COBOL FILE STATUS codes to the CardDemo Java exception hierarchy.
 *
 * <p>This Spring-managed component provides centralized translation from COBOL
 * FILE STATUS codes (2-character strings per COBOL PIC X(2) specification) to
 * the appropriate Java exception types. It is injected via {@code @Autowired}
 * into all service and batch components that need FILE STATUS code translation.</p>
 *
 * <h3>Complete FILE STATUS Code Mapping</h3>
 * <table>
 *   <caption>COBOL FILE STATUS to Java Exception Mapping</caption>
 *   <tr><th>FILE STATUS</th><th>Meaning</th><th>Java Result</th></tr>
 *   <tr><td>00</td><td>Successful completion</td><td>{@code null} (no error)</td></tr>
 *   <tr><td>02</td><td>Success, duplicate key (non-unique AIX)</td><td>{@code null} (no error)</td></tr>
 *   <tr><td>10</td><td>End of file / no more records</td><td>{@code null} (EOF signal)</td></tr>
 *   <tr><td>21</td><td>Sequence error</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>22</td><td>Duplicate key (DUPKEY/DUPREC)</td><td>{@link DuplicateRecordException}</td></tr>
 *   <tr><td>23</td><td>Record not found (INVALID KEY)</td><td>{@link RecordNotFoundException}</td></tr>
 *   <tr><td>24</td><td>Boundary violation</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>30</td><td>Permanent I/O error</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>35</td><td>File not found (OPEN)</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>37</td><td>File access mode conflict</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>39</td><td>File attribute mismatch</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>41</td><td>File already open</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>42</td><td>File not open (CLOSE)</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>43</td><td>No prior READ for REWRITE/DELETE</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>44</td><td>Record length mismatch on REWRITE</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>46</td><td>Sequential READ past EOF</td><td>{@code null} (EOF signal)</td></tr>
 *   <tr><td>47</td><td>READ on file not open for input</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>48</td><td>WRITE on file not open for output</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>49</td><td>DELETE/REWRITE on file not open for I/O</td><td>{@link CardDemoException}</td></tr>
 *   <tr><td>9x</td><td>Operating system / runtime error</td><td>{@link CardDemoException}</td></tr>
 * </table>
 *
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 * // In any service or batch processor:
 * @Autowired
 * private FileStatusMapper fileStatusMapper;
 *
 * // Pattern 1: Check and throw (most common)
 * fileStatusMapper.throwOnError(fileStatusCode, "Account", accountId);
 *
 * // Pattern 2: Check and handle specific conditions
 * CardDemoException ex = fileStatusMapper.mapFileStatus(code, "Transaction", txnId);
 * if (ex != null) {
 *     // Handle error
 * }
 *
 * // Pattern 3: Quick success/EOF check
 * if (fileStatusMapper.isSuccess(code)) { /* process record *&#47; }
 * if (fileStatusMapper.isEndOfFile(code)) { /* stop reading *&#47; }
 * }</pre>
 *
 * @see com.cardemo.model.enums.FileStatus
 * @see CardDemoException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
@Component
public class FileStatusMapper {

    /**
     * SLF4J logger for structured logging with correlation IDs per AAP §0.7.1
     * observability requirement. Logs warning messages for non-success FILE STATUS
     * codes with entity context for diagnostic traceability.
     */
    private static final Logger log = LoggerFactory.getLogger(FileStatusMapper.class);

    /** FILE STATUS code for successful completion. */
    private static final String FS_SUCCESS = "00";

    /** FILE STATUS code for success with duplicate key on non-unique alternate index. */
    private static final String FS_SUCCESS_DUPLICATE_AIX = "02";

    /** FILE STATUS code for end-of-file / no more records. */
    private static final String FS_END_OF_FILE = "10";

    /** FILE STATUS code for sequence error on indexed sequential write. */
    private static final String FS_SEQUENCE_ERROR = "21";

    /** FILE STATUS code for duplicate key on WRITE (DUPKEY/DUPREC). */
    private static final String FS_DUPLICATE_KEY = "22";

    /** FILE STATUS code for record not found / INVALID KEY. */
    private static final String FS_RECORD_NOT_FOUND = "23";

    /** FILE STATUS code for key boundary violation. */
    private static final String FS_BOUNDARY_VIOLATION = "24";

    /** FILE STATUS code for permanent I/O error. */
    private static final String FS_PERMANENT_IO_ERROR = "30";

    /** FILE STATUS code for file not found on OPEN. */
    private static final String FS_FILE_NOT_FOUND = "35";

    /** FILE STATUS code for file access mode conflict. */
    private static final String FS_ACCESS_MODE_CONFLICT = "37";

    /** FILE STATUS code for file attribute mismatch. */
    private static final String FS_ATTRIBUTE_MISMATCH = "39";

    /** FILE STATUS code for file already open. */
    private static final String FS_FILE_ALREADY_OPEN = "41";

    /** FILE STATUS code for file not open on CLOSE. */
    private static final String FS_FILE_NOT_OPEN = "42";

    /** FILE STATUS code for DELETE/REWRITE without prior READ. */
    private static final String FS_NO_PRIOR_READ = "43";

    /** FILE STATUS code for record length mismatch on REWRITE. */
    private static final String FS_RECORD_LENGTH_MISMATCH = "44";

    /** FILE STATUS code for sequential READ with no next record (past EOF). */
    private static final String FS_READ_PAST_EOF = "46";

    /** FILE STATUS code for READ on file not open for input. */
    private static final String FS_NOT_OPEN_INPUT = "47";

    /** FILE STATUS code for WRITE on file not open for output. */
    private static final String FS_NOT_OPEN_OUTPUT = "48";

    /** FILE STATUS code for DELETE/REWRITE on file not open for I/O. */
    private static final String FS_NOT_OPEN_IO = "49";

    /**
     * Maps a COBOL FILE STATUS code to the corresponding Java exception.
     *
     * <p>Returns {@code null} for success codes ({@code "00"}, {@code "02"}) and
     * EOF codes ({@code "10"}, {@code "46"}), allowing callers to distinguish
     * between error conditions and normal flow control without exception overhead.</p>
     *
     * <p>For error codes, returns the appropriate exception from the CardDemo
     * exception hierarchy:</p>
     * <ul>
     *   <li>{@code "22"} (DUPKEY/DUPREC) → {@link DuplicateRecordException}</li>
     *   <li>{@code "23"} (INVALID KEY) → {@link RecordNotFoundException}</li>
     *   <li>All other error codes → {@link CardDemoException} with descriptive message,
     *       error code in format {@code "FS_XX"}, and the original FILE STATUS code</li>
     * </ul>
     *
     * <p>Logs a warning for every non-success, non-EOF code to support structured
     * logging and diagnostic traceability per AAP §0.7.1 observability requirements.</p>
     *
     * <p>COBOL Traceability: This method consolidates the FILE STATUS checking patterns
     * found in every COBOL program's I/O paragraphs (e.g., CBTRN02C.cbl paragraphs
     * 0000-DALYTRAN-OPEN through 9500-TCATBALF-CLOSE, and 9910-DISPLAY-IO-STATUS).</p>
     *
     * @param fileStatusCode the 2-character FILE STATUS code from COBOL (e.g., "00",
     *                       "23", "35"); may be {@code null} or blank (treated as success)
     * @param entityName     the entity name for error context (e.g., "Account",
     *                       "Transaction", "CardCrossReference"); used in exception
     *                       messages and structured log entries
     * @param entityId       the entity identifier for error context (e.g., account ID,
     *                       transaction ID); may be {@code null} when the identifier
     *                       is not available
     * @return the corresponding {@link CardDemoException} subclass for error codes,
     *         or {@code null} for success codes ("00", "02") and EOF codes ("10", "46")
     */
    public CardDemoException mapFileStatus(String fileStatusCode, String entityName,
                                           Object entityId) {
        /* Defensive null/blank check — treat missing status as success
         * (mirrors COBOL behavior where uninitialized PIC X(2) is spaces) */
        if (fileStatusCode == null || fileStatusCode.isBlank()) {
            return null;
        }

        /* Normalize to exactly 2 characters, trimming whitespace.
         * COBOL FILE STATUS is always PIC X(2) = 2 bytes, but Java callers
         * may pass strings with leading/trailing whitespace. */
        String code = fileStatusCode.trim();
        if (code.length() > 2) {
            code = code.substring(0, 2);
        }

        /* Map FILE STATUS code to exception using switch expression.
         * Success and EOF codes return null; error codes return exceptions. */
        return switch (code) {
            /* Success codes — no exception, no logging */
            case FS_SUCCESS, FS_SUCCESS_DUPLICATE_AIX ->
                null;

            /* EOF codes — no exception, no logging (caller handles EOF flow) */
            case FS_END_OF_FILE, FS_READ_PAST_EOF ->
                null;

            /* FILE STATUS 22: Duplicate key on WRITE (DUPKEY/DUPREC)
             * COBOL: WRITE ... INVALID KEY / CICS DFHRESP(DUPKEY)/DFHRESP(DUPREC)
             * Maps to HTTP 409 Conflict in REST controllers */
            case FS_DUPLICATE_KEY -> {
                log.warn("FILE STATUS {} (DUPLICATE KEY) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new DuplicateRecordException(entityName, entityId);
            }

            /* FILE STATUS 23: Record not found / INVALID KEY
             * COBOL: READ/START/DELETE INVALID KEY / CICS DFHRESP(NOTFND)
             * Maps to HTTP 404 Not Found in REST controllers */
            case FS_RECORD_NOT_FOUND -> {
                log.warn("FILE STATUS {} (RECORD NOT FOUND) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new RecordNotFoundException(entityName, entityId);
            }

            /* FILE STATUS 21: Sequence error — key not in ascending order */
            case FS_SEQUENCE_ERROR -> {
                log.warn("FILE STATUS {} (SEQUENCE ERROR) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "Sequence error for " + entityName, "FS_21", "21");
            }

            /* FILE STATUS 24: Key boundary violation */
            case FS_BOUNDARY_VIOLATION -> {
                log.warn("FILE STATUS {} (BOUNDARY VIOLATION) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "Boundary violation for " + entityName, "FS_24", "24");
            }

            /* FILE STATUS 30: Permanent I/O error */
            case FS_PERMANENT_IO_ERROR -> {
                log.warn("FILE STATUS {} (PERMANENT I/O ERROR) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "Permanent I/O error for " + entityName, "FS_30", "30");
            }

            /* FILE STATUS 35: File not found on OPEN */
            case FS_FILE_NOT_FOUND -> {
                log.warn("FILE STATUS {} (FILE NOT FOUND) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File not found: " + entityName, "FS_35", "35");
            }

            /* FILE STATUS 37: File access mode conflict */
            case FS_ACCESS_MODE_CONFLICT -> {
                log.warn("FILE STATUS {} (ACCESS MODE CONFLICT) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File access conflict for " + entityName, "FS_37", "37");
            }

            /* FILE STATUS 39: File attribute mismatch */
            case FS_ATTRIBUTE_MISMATCH -> {
                log.warn("FILE STATUS {} (ATTRIBUTE MISMATCH) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File access conflict for " + entityName, "FS_39", "39");
            }

            /* FILE STATUS 41: File already open */
            case FS_FILE_ALREADY_OPEN -> {
                log.warn("FILE STATUS {} (FILE ALREADY OPEN) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File state error for " + entityName, "FS_41", "41");
            }

            /* FILE STATUS 42: File not open on CLOSE */
            case FS_FILE_NOT_OPEN -> {
                log.warn("FILE STATUS {} (FILE NOT OPEN) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File state error for " + entityName, "FS_42", "42");
            }

            /* FILE STATUS 43: DELETE/REWRITE without prior READ */
            case FS_NO_PRIOR_READ -> {
                log.warn("FILE STATUS {} (NO PRIOR READ) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File state error for " + entityName, "FS_43", "43");
            }

            /* FILE STATUS 44: Record length mismatch on REWRITE */
            case FS_RECORD_LENGTH_MISMATCH -> {
                log.warn("FILE STATUS {} (RECORD LENGTH MISMATCH) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File state error for " + entityName, "FS_44", "44");
            }

            /* FILE STATUS 47: READ on file not open for input */
            case FS_NOT_OPEN_INPUT -> {
                log.warn("FILE STATUS {} (NOT OPEN FOR INPUT) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File mode error for " + entityName, "FS_47", "47");
            }

            /* FILE STATUS 48: WRITE on file not open for output */
            case FS_NOT_OPEN_OUTPUT -> {
                log.warn("FILE STATUS {} (NOT OPEN FOR OUTPUT) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File mode error for " + entityName, "FS_48", "48");
            }

            /* FILE STATUS 49: DELETE/REWRITE on file not open for I/O */
            case FS_NOT_OPEN_IO -> {
                log.warn("FILE STATUS {} (NOT OPEN FOR I/O) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "File mode error for " + entityName, "FS_49", "49");
            }

            /* Default: System error (including '9x' codes where IO-STAT1 = '9')
             * COBOL: IO-STATUS NOT NUMERIC OR IO-STAT1 = '9' triggers binary
             * decoding of the extended status code (CBTRN02C.cbl lines 714-727).
             * In Java, these are all mapped to a generic system error exception. */
            default -> {
                log.warn("FILE STATUS {} (SYSTEM/UNKNOWN ERROR) mapped for entity {} with id {}",
                        code, entityName, entityId);
                yield new CardDemoException(
                        "System error (FILE STATUS: " + code + ") for " + entityName,
                        "FS_SYS", code);
            }
        };
    }

    /**
     * Maps a FILE STATUS code and throws the corresponding exception if it indicates
     * an error condition.
     *
     * <p>This is the most commonly used method in service and batch components. It
     * does nothing for success codes ({@code "00"}, {@code "02"}) and EOF codes
     * ({@code "10"}, {@code "46"}), allowing the normal execution path to continue.
     * For any error code, it throws the appropriate exception from the CardDemo
     * exception hierarchy.</p>
     *
     * <p>COBOL Equivalent Pattern:</p>
     * <pre>
     * IF  DALYTRAN-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * ELSE
     *     MOVE 12 TO APPL-RESULT
     * END-IF
     * IF  APPL-AOK
     *     CONTINUE
     * ELSE
     *     DISPLAY 'ERROR OPENING DALYTRAN'
     *     MOVE DALYTRAN-STATUS TO IO-STATUS
     *     PERFORM 9910-DISPLAY-IO-STATUS
     *     PERFORM 9999-ABEND-PROGRAM
     * END-IF
     * </pre>
     *
     * <p>In the Java migration, the ABEND program call is replaced by throwing the
     * appropriate exception, which propagates up the call stack for handling by
     * Spring's transaction management and error handling infrastructure.</p>
     *
     * @param fileStatusCode the 2-character FILE STATUS code
     * @param entityName     the entity name for error context (e.g., "Account",
     *                       "Transaction")
     * @param entityId       the entity identifier for error context; may be {@code null}
     * @throws CardDemoException        for general file I/O errors (status 21, 24, 30,
     *                                  35, 37, 39, 41-44, 47-49, 9x)
     * @throws RecordNotFoundException  for FILE STATUS 23 (INVALID KEY / record not found)
     * @throws DuplicateRecordException for FILE STATUS 22 (DUPKEY/DUPREC)
     */
    public void throwOnError(String fileStatusCode, String entityName, Object entityId) {
        CardDemoException exception = mapFileStatus(fileStatusCode, entityName, entityId);
        if (exception != null) {
            throw exception;
        }
    }

    /**
     * Checks if a FILE STATUS code indicates successful completion.
     *
     * <p>Returns {@code true} for FILE STATUS codes {@code "00"} (successful completion)
     * and {@code "02"} (successful completion with duplicate key on non-unique alternate
     * index). Returns {@code false} for all other codes, including {@code null} and
     * blank strings.</p>
     *
     * <p>COBOL Equivalent Pattern:</p>
     * <pre>
     * IF  ACCTFILE-STATUS = '00'
     *     MOVE 0 TO APPL-RESULT
     * </pre>
     *
     * <p>Note: FILE STATUS "02" occurs during WRITE or REWRITE operations when the
     * record has been successfully written but a duplicate alternate key (without
     * the WITH DUPLICATES clause preventing it) has been detected. This is treated
     * as a success condition in the CardDemo application because the primary operation
     * completed successfully.</p>
     *
     * @param fileStatusCode the 2-character FILE STATUS code; may be {@code null}
     * @return {@code true} if the code indicates success ({@code "00"} or {@code "02"}),
     *         {@code false} otherwise
     */
    public boolean isSuccess(String fileStatusCode) {
        if (fileStatusCode == null || fileStatusCode.isBlank()) {
            return false;
        }
        String code = fileStatusCode.trim();
        return FS_SUCCESS.equals(code) || FS_SUCCESS_DUPLICATE_AIX.equals(code);
    }

    /**
     * Checks if a FILE STATUS code indicates end-of-file.
     *
     * <p>Returns {@code true} for FILE STATUS codes {@code "10"} (end of file reached
     * during sequential READ or CICS STARTBR/READNEXT browse) and {@code "46"}
     * (sequential READ attempted after end of file has already been reached).
     * Returns {@code false} for all other codes, including {@code null} and blank
     * strings.</p>
     *
     * <p>COBOL Equivalent Pattern:</p>
     * <pre>
     * IF  DALYTRAN-STATUS = '10'
     *     MOVE 16 TO APPL-RESULT        (88 APPL-EOF VALUE 16)
     * ...
     * IF  APPL-EOF
     *     MOVE 'Y' TO END-OF-FILE
     * </pre>
     *
     * <p>In batch processing, EOF signals the end of a sequential file read loop
     * (e.g., the daily transaction file in CBTRN02C.cbl). In online CICS programs,
     * EOF signals the end of a browse operation (READNEXT returning ENDFILE).</p>
     *
     * @param fileStatusCode the 2-character FILE STATUS code; may be {@code null}
     * @return {@code true} if the code indicates end-of-file ({@code "10"} or
     *         {@code "46"}), {@code false} otherwise
     */
    public boolean isEndOfFile(String fileStatusCode) {
        if (fileStatusCode == null || fileStatusCode.isBlank()) {
            return false;
        }
        String code = fileStatusCode.trim();
        return FS_END_OF_FILE.equals(code) || FS_READ_PAST_EOF.equals(code);
    }
}
