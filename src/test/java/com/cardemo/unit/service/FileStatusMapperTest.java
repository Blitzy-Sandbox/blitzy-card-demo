/*
 * FileStatusMapperTest.java
 *
 * JUnit 5 unit tests for the FileStatusMapper utility class that maps COBOL
 * FILE STATUS codes (2-character strings) to the CardDemo Java exception hierarchy.
 *
 * This test class covers all 4 public methods of FileStatusMapper:
 * - mapFileStatus(String, String, Object) — returns null for success/EOF, exception for errors
 * - throwOnError(String, String, Object) — throws on error codes, no-op on success/EOF
 * - isSuccess(String) — true for "00" and "02"
 * - isEndOfFile(String) — true for "10" and "46"
 *
 * COBOL Traceability (original repository commit SHA 27d6c6f):
 * - CBTRN02C.cbl: FILE STATUS patterns for DALYTRAN, TRANFILE, XREFFILE,
 *   DALYREJS, ACCTFILE, TCATBALF — statuses checked: '00' (success), '10' (EOF),
 *   '23' (not found), non-'00' (error -> ABEND).
 * - COACTUPC.cbl: CICS DFHRESP(NORMAL), DFHRESP(NOTFND), DFHRESP(DUPKEY)
 *   patterns that map to the same FILE STATUS equivalents.
 * - COCRDUPC.cbl: CICS DFHRESP(NORMAL), DFHRESP(NOTFND), DFHRESP(DUPREC)
 *   patterns for card record operations.
 *
 * Pure unit tests — no Spring context or mocks needed. FileStatusMapper is a
 * stateless utility instantiated directly via new FileStatusMapper().
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.service;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.service.shared.FileStatusMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileStatusMapper} — validates that every COBOL FILE STATUS
 * code maps correctly to the CardDemo Java exception hierarchy.
 *
 * <p>Tests cover the complete FILE STATUS code space documented in the AAP §0.8.4:</p>
 * <ul>
 *   <li>"00" and "02" — success codes returning null</li>
 *   <li>"10" and "46" — end-of-file codes returning null (isEndOfFile=true)</li>
 *   <li>"22" — DUPKEY/DUPREC returning {@link DuplicateRecordException}</li>
 *   <li>"23" — INVALID KEY returning {@link RecordNotFoundException}</li>
 *   <li>"35" — file not found returning {@link CardDemoException}</li>
 *   <li>Unknown/unmapped codes returning {@link CardDemoException}</li>
 * </ul>
 *
 * <p>Additionally tests context fields (dataset name and key value) in exception
 * messages, the {@code throwOnError} convenience method, and the boolean
 * {@code isSuccess} and {@code isEndOfFile} predicates.</p>
 */
class FileStatusMapperTest {

    /** The class under test — instantiated fresh before each test for isolation. */
    private FileStatusMapper fileStatusMapper;

    /**
     * Sets up a fresh {@link FileStatusMapper} instance before each test.
     * No Spring context or mocks needed — the mapper is a stateless utility.
     */
    @BeforeEach
    void setUp() {
        fileStatusMapper = new FileStatusMapper();
    }

    // ========================================================================
    // Success Codes
    // ========================================================================

    /**
     * FILE STATUS "00" (successful completion) must return null — no exception.
     *
     * <p>COBOL: Every program checks IF XXXFILE-STATUS = '00' for success.</p>
     */
    @Test
    void testMapFileStatus_00_returnsNull() {
        CardDemoException result = fileStatusMapper.mapFileStatus("00", "ACCTDAT", "00000000001");
        assertThat(result).isNull();
    }

    /**
     * FILE STATUS "02" (success with duplicate key on non-unique alternate index)
     * must return null — no exception.
     *
     * <p>COBOL: Status "02" occurs during WRITE/REWRITE when a record has been
     * successfully written but a duplicate alternate key was detected. Treated as
     * success in CardDemo because the primary operation completed.</p>
     */
    @Test
    void testMapFileStatus_02_returnsNull() {
        CardDemoException result = fileStatusMapper.mapFileStatus("02", "TRANSACT", "TXN-001");
        assertThat(result).isNull();
    }

    // ========================================================================
    // End-of-File Codes
    // ========================================================================

    /**
     * FILE STATUS "10" (end of file reached during sequential READ) must cause
     * {@code isEndOfFile} to return true.
     *
     * <p>COBOL: DALYTRAN-STATUS = '10' triggers APPL-EOF → END-OF-FILE = 'Y'
     * in CBTRN02C.cbl.</p>
     */
    @Test
    void testIsEndOfFile_10_returnsTrue() {
        boolean result = fileStatusMapper.isEndOfFile("10");
        assertThat(result).isTrue();
    }

    /**
     * FILE STATUS "46" (sequential READ attempted past end of file) must cause
     * {@code isEndOfFile} to return true.
     *
     * <p>COBOL: Status "46" indicates a READ was attempted after an EOF condition
     * was already reached — equivalent to reading past the last record.</p>
     */
    @Test
    void testIsEndOfFile_46_returnsTrue() {
        boolean result = fileStatusMapper.isEndOfFile("46");
        assertThat(result).isTrue();
    }

    /**
     * FILE STATUS "00" (success) must NOT be treated as end-of-file.
     * {@code isEndOfFile} must return false for non-EOF codes.
     */
    @Test
    void testIsEndOfFile_00_returnsFalse() {
        boolean result = fileStatusMapper.isEndOfFile("00");
        assertThat(result).isFalse();
    }

    // ========================================================================
    // Duplicate Record — FILE STATUS 22
    // ========================================================================

    /**
     * FILE STATUS "22" (DUPKEY/DUPREC — duplicate key on WRITE) must return a
     * {@link DuplicateRecordException} with the dataset name in the message.
     *
     * <p>COBOL: DFHRESP(DUPKEY)/DFHRESP(DUPREC) on WRITE to TRANSACT file
     * in COTRN02C.cbl, and WRITE to USRSEC file in COUSR01C.cbl.</p>
     */
    @Test
    void testMapFileStatus_22_returnsDuplicateRecordException() {
        CardDemoException result = fileStatusMapper.mapFileStatus("22", "Transaction", "TXN-12345");

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(DuplicateRecordException.class);

        DuplicateRecordException dupException = (DuplicateRecordException) result;
        assertThat(dupException.getEntityName()).isEqualTo("Transaction");
        assertThat(dupException.getDuplicateId()).isEqualTo("TXN-12345");
        assertThat(dupException.getErrorCode()).isEqualTo("DUP");
        assertThat(dupException.getFileStatusCode()).isEqualTo("22");
        assertThat(dupException.getMessage()).contains("Transaction");
    }

    // ========================================================================
    // Record Not Found — FILE STATUS 23
    // ========================================================================

    /**
     * FILE STATUS "23" (INVALID KEY — record not found) must return a
     * {@link RecordNotFoundException} with the dataset name and key in the message.
     *
     * <p>COBOL: DFHRESP(NOTFND) on CICS READ DATASET in COACTUPC.cbl,
     * COACTVWC.cbl, COCRDSLC.cbl, and batch VSAM reads in CBTRN02C.cbl.</p>
     */
    @Test
    void testMapFileStatus_23_returnsRecordNotFoundException() {
        CardDemoException result = fileStatusMapper.mapFileStatus("23", "Account", "00000000001");

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(RecordNotFoundException.class);

        RecordNotFoundException rnfException = (RecordNotFoundException) result;
        assertThat(rnfException.getEntityName()).isEqualTo("Account");
        assertThat(rnfException.getEntityId()).isEqualTo("00000000001");
        assertThat(rnfException.getErrorCode()).isEqualTo("RNF");
        assertThat(rnfException.getFileStatusCode()).isEqualTo("23");
        assertThat(rnfException.getMessage()).contains("Account");
        assertThat(rnfException.getMessage()).contains("00000000001");
    }

    // ========================================================================
    // File Not Found — FILE STATUS 35
    // ========================================================================

    /**
     * FILE STATUS "35" (file not found on OPEN) must return a
     * {@link CardDemoException} with "file not found" context.
     *
     * <p>COBOL: FILE STATUS "35" indicates that an OPEN statement was attempted
     * on a non-optional file that is not present on the system.</p>
     */
    @Test
    void testMapFileStatus_35_returnsCardDemoException() {
        CardDemoException result = fileStatusMapper.mapFileStatus("35", "DALYTRAN", null);

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(CardDemoException.class);
        assertThat(result).isNotInstanceOf(RecordNotFoundException.class);
        assertThat(result).isNotInstanceOf(DuplicateRecordException.class);
        assertThat(result.getErrorCode()).isEqualTo("FS_35");
        assertThat(result.getFileStatusCode()).isEqualTo("35");
        assertThat(result.getMessage()).containsIgnoringCase("file not found");
        assertThat(result.getMessage()).contains("DALYTRAN");
    }

    // ========================================================================
    // Unknown / Unmapped Codes
    // ========================================================================

    /**
     * FILE STATUS "47" (READ on file not open for input) — mapped to a specific
     * CardDemoException with file mode error context.
     *
     * <p>Although "47" has a specific mapping in FileStatusMapper (FS_NOT_OPEN_INPUT),
     * it still produces a CardDemoException (not a specific subclass).</p>
     */
    @Test
    void testMapFileStatus_unknownCode_returnsCardDemoException() {
        CardDemoException result = fileStatusMapper.mapFileStatus("47", "TRANFILE", "KEY-999");

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(CardDemoException.class);
        assertThat(result).isNotInstanceOf(RecordNotFoundException.class);
        assertThat(result).isNotInstanceOf(DuplicateRecordException.class);
        assertThat(result.getFileStatusCode()).isEqualTo("47");
    }

    /**
     * FILE STATUS "99" (system/unknown error) must return a CardDemoException
     * with system error context.
     *
     * <p>COBOL: '9x' codes trigger binary decoding of the extended status code
     * in paragraph 9910-DISPLAY-IO-STATUS (CBTRN02C.cbl lines 714-727). In Java,
     * all '9x' codes map to generic system error exceptions.</p>
     */
    @Test
    void testMapFileStatus_99_returnsCardDemoException() {
        CardDemoException result = fileStatusMapper.mapFileStatus("99", "ACCTDAT", "ACCT-001");

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(CardDemoException.class);
        assertThat(result).isNotInstanceOf(RecordNotFoundException.class);
        assertThat(result).isNotInstanceOf(DuplicateRecordException.class);
        assertThat(result.getMessage()).contains("99");
        assertThat(result.getMessage()).contains("ACCTDAT");
    }

    // ========================================================================
    // isSuccess Method
    // ========================================================================

    /**
     * isSuccess("00") must return true — FILE STATUS "00" is the primary success code.
     *
     * <p>COBOL: Every batch and online program checks for '00' after file I/O.
     * Example: IF ACCTFILE-STATUS = '00' MOVE 0 TO APPL-RESULT.</p>
     */
    @Test
    void testIsSuccess_00_returnsTrue() {
        boolean result = fileStatusMapper.isSuccess("00");
        assertThat(result).isTrue();
    }

    /**
     * isSuccess("02") must return true — FILE STATUS "02" (success with DUPKEY
     * on non-unique alternate index) is also a success condition.
     */
    @Test
    void testIsSuccess_02_returnsTrue() {
        boolean result = fileStatusMapper.isSuccess("02");
        assertThat(result).isTrue();
    }

    /**
     * isSuccess("23") must return false — FILE STATUS "23" (record not found)
     * is NOT a success condition.
     */
    @Test
    void testIsSuccess_23_returnsFalse() {
        boolean result = fileStatusMapper.isSuccess("23");
        assertThat(result).isFalse();
    }

    /**
     * isSuccess("22") must return false — FILE STATUS "22" (duplicate key on WRITE)
     * is NOT a success condition.
     */
    @Test
    void testIsSuccess_22_returnsFalse() {
        boolean result = fileStatusMapper.isSuccess("22");
        assertThat(result).isFalse();
    }

    // ========================================================================
    // throwOnError Method
    // ========================================================================

    /**
     * throwOnError("00", ...) must NOT throw any exception.
     *
     * <p>Success codes must allow normal execution to continue without
     * exception overhead.</p>
     */
    @Test
    void testThrowOnError_00_noException() {
        assertThatCode(() ->
                fileStatusMapper.throwOnError("00", "ACCTDAT", "00000000001")
        ).doesNotThrowAnyException();
    }

    /**
     * throwOnError("23", ...) must throw a {@link RecordNotFoundException}.
     *
     * <p>COBOL: FILE STATUS "23" (INVALID KEY) triggers error handling path.
     * The Java equivalent throws the exception for propagation through Spring's
     * transaction management.</p>
     */
    @Test
    void testThrowOnError_23_throwsRecordNotFound() {
        assertThatThrownBy(() ->
                fileStatusMapper.throwOnError("23", "Account", "00000000001")
        )
                .isInstanceOf(RecordNotFoundException.class)
                .hasMessageContaining("Account")
                .hasMessageContaining("00000000001");
    }

    /**
     * throwOnError("22", ...) must throw a {@link DuplicateRecordException}.
     *
     * <p>COBOL: FILE STATUS "22" (DUPKEY/DUPREC) triggers error handling path.
     * The Java equivalent throws the exception for duplicate key conditions.</p>
     */
    @Test
    void testThrowOnError_22_throwsDuplicateRecord() {
        assertThatThrownBy(() ->
                fileStatusMapper.throwOnError("22", "Transaction", "TXN-99999")
        )
                .isInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("Transaction");
    }

    // ========================================================================
    // Context Fields — Dataset Name and Key Value
    // ========================================================================

    /**
     * Exception message must include the dataset name parameter for error context.
     *
     * <p>COBOL: Error messages always include the dataset name (e.g., 'DALYTRAN',
     * 'ACCTDAT') to identify which file operation failed. The Java mapper preserves
     * this pattern by including the entity name in the exception message.</p>
     */
    @Test
    void testMapFileStatus_includesDatasetName() {
        String datasetName = "CARDXREF";
        CardDemoException result = fileStatusMapper.mapFileStatus("35", datasetName, null);

        assertThat(result).isNotNull();
        assertThat(result.getMessage()).contains(datasetName);
    }

    /**
     * Exception message must reference the key object parameter for error context.
     *
     * <p>COBOL: Error messages include the RIDFLD (record identification field) value
     * so that operators can identify which specific record triggered the error. The
     * Java mapper preserves this by including the entity ID in the exception message
     * (for exceptions that carry entity context, such as RecordNotFoundException and
     * DuplicateRecordException).</p>
     */
    @Test
    void testMapFileStatus_includesKeyValue() {
        String keyValue = "CARD-00000000019";
        CardDemoException result = fileStatusMapper.mapFileStatus("23", "CardCrossReference", keyValue);

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(RecordNotFoundException.class);
        assertThat(result.getMessage()).contains(keyValue);

        RecordNotFoundException rnfException = (RecordNotFoundException) result;
        assertThat(rnfException.getEntityId()).isEqualTo(keyValue);
    }
}
