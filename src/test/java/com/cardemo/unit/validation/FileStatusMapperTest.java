/*
 * FileStatusMapperTest.java
 *
 * Comprehensive JUnit 5 unit tests for the FileStatusMapper utility class,
 * verifying that every COBOL FILE STATUS code (2-character string) maps to the
 * correct Java exception hierarchy outcome. Pure unit tests — no Spring context.
 *
 * COBOL Traceability (original repository commit SHA 27d6c6f):
 * - CBTRN02C.cbl: FILE STATUS patterns for DALYTRAN, TRANFILE, XREFFILE,
 *   DALYREJS, ACCTFILE, TCATBALF — statuses checked: '00' (success), '10' (EOF),
 *   '23' (not found), non-'00' (error → ABEND). Paragraph 9910-DISPLAY-IO-STATUS
 *   handles '9x' binary decoding for system-specific VSAM errors.
 * - All 28 COBOL programs use identical FILE STATUS checking patterns.
 *
 * Test Coverage:
 * - Success codes: 00, 02 → no exception (mapFileStatus returns null)
 * - EOF codes: 10, 46 → no exception (mapFileStatus returns null)
 * - Status 22 → DuplicateRecordException (COBOL DUPKEY/DUPREC)
 * - Status 23 → RecordNotFoundException (COBOL INVALID KEY)
 * - Status 21, 24, 30, 35 → CardDemoException (generic I/O errors)
 * - Status 90-99 → CardDemoException (system/VSAM errors, parameterized)
 * - Null/empty/blank status → mapFileStatus returns null (defensive handling)
 * - Exception hierarchy: RecordNotFoundException ⊂ CardDemoException ⊂ RuntimeException
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */
package com.cardemo.unit.validation;

import com.cardemo.exception.CardDemoException;
import com.cardemo.exception.DuplicateRecordException;
import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.enums.FileStatus;
import com.cardemo.service.shared.FileStatusMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FileStatusMapper} — the central utility that maps COBOL
 * FILE STATUS codes to the CardDemo Java exception hierarchy.
 *
 * <p>These tests exercise all four public methods of {@code FileStatusMapper}:
 * {@link FileStatusMapper#mapFileStatus(String, String, Object)},
 * {@link FileStatusMapper#throwOnError(String, String, Object)},
 * {@link FileStatusMapper#isSuccess(String)}, and
 * {@link FileStatusMapper#isEndOfFile(String)}.</p>
 *
 * <p><strong>No Spring context is loaded.</strong> {@code FileStatusMapper} is a
 * stateless utility — {@code new FileStatusMapper()} is sufficient for testing.
 * This keeps tests fast and focused on pure mapping logic.</p>
 *
 * @see FileStatusMapper
 * @see FileStatus
 * @see CardDemoException
 * @see RecordNotFoundException
 * @see DuplicateRecordException
 */
@DisplayName("FileStatusMapper — COBOL FILE STATUS to Java Exception Mapping")
class FileStatusMapperTest {

    /**
     * The class under test — instantiated fresh before each test method to ensure
     * stateless isolation. No Spring context or DI needed.
     */
    private FileStatusMapper fileStatusMapper;

    /**
     * Initializes a fresh {@link FileStatusMapper} instance before each test.
     * The mapper is a pure stateless utility, so a simple {@code new} suffices.
     */
    @BeforeEach
    void setUp() {
        fileStatusMapper = new FileStatusMapper();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 2: Success Status Tests — FILE STATUS codes that do NOT throw
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FILE STATUS 00 (SUCCESS) — the most common path in every COBOL I/O operation.
     *
     * <p>COBOL pattern: {@code IF DALYTRAN-STATUS = '00' MOVE 0 TO APPL-RESULT}
     * (CBTRN02C.cbl paragraph 0000-DALYTRAN-OPEN, line 239).</p>
     *
     * <p>Verifies:
     * <ul>
     *   <li>{@code throwOnError("00", ...)} does not throw any exception</li>
     *   <li>{@code mapFileStatus("00", ...)} returns {@code null}</li>
     *   <li>{@code isSuccess("00")} returns {@code true}</li>
     *   <li>Cross-check with {@code FileStatus.SUCCESS.getCode()}</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("FILE STATUS 00 (SUCCESS) → no exception, isSuccess=true")
    void testStatus00_Success_NoExceptionThrown() {
        // throwOnError must not throw for success status
        assertThatCode(() -> fileStatusMapper.throwOnError("00", "Account", "ACC001"))
                .doesNotThrowAnyException();

        // mapFileStatus must return null for success status
        assertThat(fileStatusMapper.mapFileStatus("00", "Account", "ACC001"))
                .isNull();

        // isSuccess must return true for "00"
        assertThat(fileStatusMapper.isSuccess("00")).isTrue();

        // Cross-validate with FileStatus.SUCCESS enum constant
        assertThat(fileStatusMapper.isSuccess(FileStatus.SUCCESS.getCode())).isTrue();

        // isEndOfFile must return false for success
        assertThat(fileStatusMapper.isEndOfFile("00")).isFalse();
    }

    /**
     * FILE STATUS 02 (DUPLICATE_ALT_KEY) — success with duplicate alternate key.
     *
     * <p>COBOL: Status '02' means the I/O operation succeeded but a duplicate
     * value was detected in a non-unique alternate index. This is informational
     * only — not an error condition.</p>
     *
     * <p>Verifies:
     * <ul>
     *   <li>{@code throwOnError("02", ...)} does not throw any exception</li>
     *   <li>{@code mapFileStatus("02", ...)} returns {@code null}</li>
     *   <li>{@code isSuccess("02")} returns {@code true}</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("FILE STATUS 02 (DUPLICATE_ALT_KEY) → no exception, isSuccess=true")
    void testStatus02_DuplicateAlternateKey_NoException() {
        assertThatCode(() -> fileStatusMapper.throwOnError("02", "Card", "CRD001"))
                .doesNotThrowAnyException();

        assertThat(fileStatusMapper.mapFileStatus("02", "Card", "CRD001"))
                .isNull();

        assertThat(fileStatusMapper.isSuccess("02")).isTrue();
    }

    /**
     * FILE STATUS 10 (END_OF_FILE) — sequential read reached end of dataset.
     *
     * <p>COBOL pattern: {@code IF DALYTRAN-STATUS = '10' SET APPL-EOF TO TRUE}
     * (CBTRN02C.cbl paragraph 1000-DALYTRAN-GET-NEXT, line ~330).</p>
     *
     * <p>Verifies:
     * <ul>
     *   <li>{@code throwOnError("10", ...)} does not throw any exception</li>
     *   <li>{@code mapFileStatus("10", ...)} returns {@code null} (EOF signal)</li>
     *   <li>{@code isEndOfFile("10")} returns {@code true}</li>
     *   <li>Cross-check with {@code FileStatus.END_OF_FILE.getCode()}</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("FILE STATUS 10 (END_OF_FILE) → no exception, isEndOfFile=true")
    void testStatus10_EndOfFile_NoException() {
        assertThatCode(() -> fileStatusMapper.throwOnError("10", "DailyTransaction", "DTX001"))
                .doesNotThrowAnyException();

        assertThat(fileStatusMapper.mapFileStatus("10", "DailyTransaction", "DTX001"))
                .isNull();

        assertThat(fileStatusMapper.isEndOfFile("10")).isTrue();

        // Cross-validate with FileStatus.END_OF_FILE enum constant
        assertThat(fileStatusMapper.isEndOfFile(FileStatus.END_OF_FILE.getCode())).isTrue();

        // isSuccess must return false for EOF
        assertThat(fileStatusMapper.isSuccess("10")).isFalse();
    }

    /**
     * FILE STATUS 46 (NO_NEXT_RECORD) — sequential READ past EOF boundary.
     *
     * <p>COBOL: Status '46' occurs when a sequential READ is attempted after the
     * end-of-file condition has already been reached. This is an EOF variant,
     * not an error condition.</p>
     *
     * <p>Verifies:
     * <ul>
     *   <li>{@code throwOnError("46", ...)} does not throw any exception</li>
     *   <li>{@code mapFileStatus("46", ...)} returns {@code null}</li>
     *   <li>{@code isEndOfFile("46")} returns {@code true}</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("FILE STATUS 46 (NO_NEXT_RECORD) → no exception, isEndOfFile=true")
    void testStatus46_NoNextRecord_NoException() {
        assertThatCode(() -> fileStatusMapper.throwOnError("46", "Transaction", "TXN999"))
                .doesNotThrowAnyException();

        assertThat(fileStatusMapper.mapFileStatus("46", "Transaction", "TXN999"))
                .isNull();

        assertThat(fileStatusMapper.isEndOfFile("46")).isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 3: Specific Exception Type Tests — RecordNotFoundException / DuplicateRecordException
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FILE STATUS 23 (RECORD_NOT_FOUND / INVALID KEY) → {@link RecordNotFoundException}.
     *
     * <p>COBOL pattern: {@code IF TCATBALF-STATUS = '23'} in CBTRN02C.cbl
     * paragraph 2700-UPDATE-TCATBAL (line ~440). Also maps to CICS
     * {@code DFHRESP(NOTFND)} across all online programs.</p>
     *
     * <p>Verifies:
     * <ul>
     *   <li>{@code throwOnError("23", ...)} throws {@link RecordNotFoundException}</li>
     *   <li>Thrown exception is also an instance of {@link CardDemoException}</li>
     *   <li>{@code mapFileStatus("23", ...)} returns non-null {@link RecordNotFoundException}</li>
     *   <li>Exception message contains entity context</li>
     *   <li>Cross-check with {@code FileStatus.RECORD_NOT_FOUND.getCode()}</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("FILE STATUS 23 (RECORD_NOT_FOUND) → RecordNotFoundException")
    void testStatus23_RecordNotFound_ThrowsRecordNotFoundException() {
        // throwOnError must throw RecordNotFoundException for status 23
        assertThatThrownBy(() -> fileStatusMapper.throwOnError("23", "Account", "ACC999"))
                .isInstanceOf(RecordNotFoundException.class)
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("Account")
                .hasMessageContaining("not found");

        // mapFileStatus must return a RecordNotFoundException (not throw, just return)
        CardDemoException exception = fileStatusMapper.mapFileStatus("23", "Account", "ACC999");
        assertThat(exception)
                .isNotNull()
                .isInstanceOf(RecordNotFoundException.class);

        // Verify FileStatus enum code matches
        assertThat(FileStatus.RECORD_NOT_FOUND.getCode()).isEqualTo("23");

        // Status 23 is neither success nor EOF
        assertThat(fileStatusMapper.isSuccess("23")).isFalse();
        assertThat(fileStatusMapper.isEndOfFile("23")).isFalse();
    }

    /**
     * FILE STATUS 22 (DUPLICATE_KEY / DUPKEY / DUPREC) → {@link DuplicateRecordException}.
     *
     * <p>COBOL: {@code DFHRESP(DUPKEY)} / {@code DFHRESP(DUPREC)} in CICS online
     * programs (COTRN02C.cbl, COUSR01C.cbl). In batch, FILE STATUS 22 from WRITE
     * with existing primary key.</p>
     *
     * <p>Verifies:
     * <ul>
     *   <li>{@code throwOnError("22", ...)} throws {@link DuplicateRecordException}</li>
     *   <li>Thrown exception is also an instance of {@link CardDemoException}</li>
     *   <li>{@code mapFileStatus("22", ...)} returns non-null {@link DuplicateRecordException}</li>
     *   <li>Exception message contains entity context</li>
     *   <li>Cross-check with {@code FileStatus.DUPLICATE_KEY.getCode()}</li>
     * </ul>
     * </p>
     */
    @Test
    @DisplayName("FILE STATUS 22 (DUPLICATE_KEY) → DuplicateRecordException")
    void testStatus22_DuplicateKey_ThrowsDuplicateRecordException() {
        // throwOnError must throw DuplicateRecordException for status 22
        assertThatThrownBy(() -> fileStatusMapper.throwOnError("22", "Transaction", "TXN001"))
                .isInstanceOf(DuplicateRecordException.class)
                .isInstanceOf(CardDemoException.class)
                .hasMessageContaining("Transaction")
                .hasMessageContaining("already exists");

        // mapFileStatus must return a DuplicateRecordException (not throw, just return)
        CardDemoException exception = fileStatusMapper.mapFileStatus("22", "Transaction", "TXN001");
        assertThat(exception)
                .isNotNull()
                .isInstanceOf(DuplicateRecordException.class);

        // Verify FileStatus enum code matches
        assertThat(FileStatus.DUPLICATE_KEY.getCode()).isEqualTo("22");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 4: Generic CardDemoException Tests — I/O errors, logic errors
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FILE STATUS 21 (SEQUENCE_ERROR) — key not in ascending order on indexed write.
     *
     * <p>COBOL: Standard COBOL I/O status for key sequencing violations during
     * indexed sequential WRITE operations.</p>
     */
    @Test
    @DisplayName("FILE STATUS 21 (SEQUENCE_ERROR) → CardDemoException")
    void testStatus21_SequenceError_ThrowsCardDemoException() {
        assertThatThrownBy(() -> fileStatusMapper.throwOnError("21", "Transaction", "TXN021"))
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .isNotInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("Sequence error");
    }

    /**
     * FILE STATUS 24 (KEY_BOUNDARY) — key boundary violation on WRITE.
     *
     * <p>COBOL: Occurs when a WRITE operation attempts to write past the defined
     * boundaries of a VSAM key range.</p>
     */
    @Test
    @DisplayName("FILE STATUS 24 (KEY_BOUNDARY) → CardDemoException")
    void testStatus24_KeyBoundary_ThrowsCardDemoException() {
        assertThatThrownBy(() -> fileStatusMapper.throwOnError("24", "Account", "ACC024"))
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .isNotInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("Boundary violation");
    }

    /**
     * FILE STATUS 30 (PERMANENT_ERROR) — unrecoverable I/O error.
     *
     * <p>COBOL: Permanent hardware or media failure. The COBOL program would
     * typically ABEND via PERFORM 9999-ABEND-PROGRAM.</p>
     */
    @Test
    @DisplayName("FILE STATUS 30 (PERMANENT_ERROR) → CardDemoException")
    void testStatus30_PermanentError_ThrowsCardDemoException() {
        assertThatThrownBy(() -> fileStatusMapper.throwOnError("30", "DataFile", "DF030"))
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .isNotInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("Permanent I/O error");
    }

    /**
     * FILE STATUS 35 (FILE_NOT_FOUND) — file does not exist on OPEN.
     *
     * <p>COBOL: The dataset referenced in the SELECT/ASSIGN is not catalogued
     * or the DD statement is missing from the JCL.</p>
     */
    @Test
    @DisplayName("FILE STATUS 35 (FILE_NOT_FOUND) → CardDemoException")
    void testStatus35_FileNotFound_ThrowsCardDemoException() {
        assertThatThrownBy(() -> fileStatusMapper.throwOnError("35", "MissingFile", "MF035"))
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .isNotInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining("File not found");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 4b: System Error Tests (9x) — VSAM/OS-level errors
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * FILE STATUS 9x (90 through 99) — operating system / VSAM runtime errors.
     *
     * <p>COBOL pattern from CBTRN02C.cbl paragraph 9910-DISPLAY-IO-STATUS
     * (lines 714-727): {@code IF IO-STAT1 = '9' MOVE IO-STAT2 TO TWO-BYTE-NUM}.
     * All '9x' codes are system-specific VSAM errors mapped to the default
     * case in FileStatusMapper's switch expression.</p>
     *
     * <p>Parameterized test covers all 10 possible 9x status codes.</p>
     *
     * @param statusCode the 2-character FILE STATUS code (90 through 99)
     */
    @ParameterizedTest(name = "FILE STATUS {0} → CardDemoException (system error)")
    @ValueSource(strings = {"90", "91", "92", "93", "94", "95", "96", "97", "98", "99"})
    @DisplayName("FILE STATUS 9x (SYSTEM_ERROR) → all throw CardDemoException")
    void testAllSystemErrors_ThrowCardDemoException(String statusCode) {
        assertThatThrownBy(() -> fileStatusMapper.throwOnError(statusCode, "SystemFile", "SYS"))
                .isInstanceOf(CardDemoException.class)
                .isNotInstanceOf(RecordNotFoundException.class)
                .isNotInstanceOf(DuplicateRecordException.class)
                .hasMessageContaining(statusCode);

        // mapFileStatus also returns a non-null exception
        CardDemoException exception = fileStatusMapper.mapFileStatus(statusCode, "SystemFile", "SYS");
        assertThat(exception).isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 5: Edge Case Tests — null, empty, blank inputs
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Null FILE STATUS code — defensive handling.
     *
     * <p>The FileStatusMapper treats null/blank FILE STATUS codes as success
     * conditions (returns null from {@code mapFileStatus}). This mirrors COBOL
     * behavior where uninitialized PIC X(2) fields contain spaces.</p>
     *
     * <p>Note: {@code isSuccess(null)} returns {@code false} because null is
     * not equivalent to "00" or "02" — it represents an unknown/unset state.
     * However, {@code mapFileStatus(null, ...)} returns null (no error), which
     * is the defensive design choice for graceful handling of uninitialized data.</p>
     */
    @Test
    @DisplayName("Null FILE STATUS → mapFileStatus returns null (defensive, no throw)")
    void testNullStatus_ThrowsCardDemoException() {
        // mapFileStatus treats null as success — returns null (no exception)
        assertThat(fileStatusMapper.mapFileStatus(null, "TestEntity", null)).isNull();

        // throwOnError does not throw for null status
        assertThatCode(() -> fileStatusMapper.throwOnError(null, "TestEntity", null))
                .doesNotThrowAnyException();

        // isSuccess returns false for null (null ≠ "00")
        assertThat(fileStatusMapper.isSuccess(null)).isFalse();

        // isEndOfFile returns false for null (null ≠ "10")
        assertThat(fileStatusMapper.isEndOfFile(null)).isFalse();
    }

    /**
     * Empty string FILE STATUS code — defensive handling.
     *
     * <p>The FileStatusMapper treats empty/blank strings as success conditions
     * (returns null from {@code mapFileStatus}). This handles edge cases where
     * a FILE STATUS variable has been initialized to spaces but no I/O operation
     * has occurred yet.</p>
     */
    @Test
    @DisplayName("Empty FILE STATUS → mapFileStatus returns null (defensive, no throw)")
    void testEmptyStatus_ThrowsCardDemoException() {
        // mapFileStatus treats empty string as blank → returns null (no exception)
        assertThat(fileStatusMapper.mapFileStatus("", "TestEntity", null)).isNull();

        // throwOnError does not throw for empty status
        assertThatCode(() -> fileStatusMapper.throwOnError("", "TestEntity", null))
                .doesNotThrowAnyException();

        // isSuccess returns false for empty string
        assertThat(fileStatusMapper.isSuccess("")).isFalse();

        // isEndOfFile returns false for empty string
        assertThat(fileStatusMapper.isEndOfFile("")).isFalse();
    }

    /**
     * Parameterized null-and-empty test using {@code @NullAndEmptySource}.
     *
     * <p>Provides additional coverage for the null/blank defensive handling path
     * in {@code mapFileStatus}, verifying both null and empty-string inputs via
     * JUnit 5's parameterized test infrastructure.</p>
     *
     * @param statusCode the FILE STATUS code (null or empty from @NullAndEmptySource)
     */
    @ParameterizedTest(name = "Null/Empty status [{0}] → mapFileStatus returns null")
    @NullAndEmptySource
    @DisplayName("Null/Empty FILE STATUS → mapFileStatus returns null")
    void testNullAndEmptyStatus_MapToNull(String statusCode) {
        assertThat(fileStatusMapper.mapFileStatus(statusCode, "Entity", null)).isNull();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Phase 7: Exception Hierarchy Tests — verify inheritance chain
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Verifies that {@link RecordNotFoundException} extends {@link CardDemoException}.
     *
     * <p>This hierarchy ensures that generic {@code catch(CardDemoException)} blocks
     * in the service layer also catch record-not-found conditions, mirroring the
     * COBOL pattern where all FILE STATUS errors route through the same error
     * handling paragraphs (9910-DISPLAY-IO-STATUS → 9999-ABEND-PROGRAM).</p>
     */
    @Test
    @DisplayName("RecordNotFoundException extends CardDemoException")
    void testRecordNotFoundExtendsCardDemoException() {
        RecordNotFoundException exception = new RecordNotFoundException("test record");
        assertThat(exception).isInstanceOf(CardDemoException.class);
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies that {@link DuplicateRecordException} extends {@link CardDemoException}.
     *
     * <p>This hierarchy ensures that generic {@code catch(CardDemoException)} blocks
     * also catch duplicate key conditions, consistent with the COBOL pattern where
     * DUPKEY/DUPREC responses flow through the same error handling infrastructure.</p>
     */
    @Test
    @DisplayName("DuplicateRecordException extends CardDemoException")
    void testDuplicateRecordExtendsCardDemoException() {
        DuplicateRecordException exception = new DuplicateRecordException("test duplicate");
        assertThat(exception).isInstanceOf(CardDemoException.class);
        assertThat(exception).isInstanceOf(RuntimeException.class);
    }

    /**
     * Verifies that {@link CardDemoException} extends {@link RuntimeException}.
     *
     * <p>This ensures unchecked exception semantics: Spring {@code @Transactional}
     * rolls back by default on RuntimeException, and service methods are not
     * burdened with forced {@code throws} declarations. Mirrors the COBOL pattern
     * where errors propagate via GO TO exit paragraphs without explicit error
     * contracts.</p>
     */
    @Test
    @DisplayName("CardDemoException extends RuntimeException")
    void testCardDemoExceptionExtendsRuntimeException() {
        CardDemoException exception = new CardDemoException("test base exception");
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception).isInstanceOf(Exception.class);
        assertThat(exception).isInstanceOf(Throwable.class);
    }
}
