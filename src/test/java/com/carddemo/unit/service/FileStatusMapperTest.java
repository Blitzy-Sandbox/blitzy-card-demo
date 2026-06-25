/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.unit.service;

import com.carddemo.enums.FileStatusCode;
import com.carddemo.exception.DuplicateRecordException;
import com.carddemo.exception.FileAccessException;
import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.service.FileStatusMapper;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link FileStatusMapper}, the cross-cutting component that
 * translates a COBOL VSAM {@code FILE STATUS} code into the application's typed
 * exception hierarchy during the AWS CardDemo COBOL&#8594;Java migration
 * (reference corpus {@code app/cbl} @ commit {@code 27d6c6f}).
 *
 * <p>{@code FileStatusMapper} is the Java realization of the rule "every
 * two-character {@code FILE STATUS} code maps to a typed exception plus a status
 * enum" (AAP &sect;0.4.2, &sect;0.6.3). No single COBOL program is its 1:1
 * source; the behavioral contract verified here is the canonical translation of
 * the file-status idiom that recurs across the batch programs (for example the
 * {@code '00'} success / {@code '10'} end-of-file / {@code 9x} abend branches in
 * {@code CBACT01C} and {@code CBTRN02C}) and the online {@code NORMAL} /
 * {@code NOTFND} response handling in programs such as {@code COACTVWC}. Those
 * programs are consulted as parity references only; no COBOL is reproduced here.</p>
 *
 * <p>The system under test has no collaborators, so it is instantiated directly
 * rather than mocked. By design this suite is a <em>pure</em> JUnit&nbsp;5 +
 * AssertJ test: it touches no Spring context, no Mockito mocks, no Jakarta APIs,
 * no database, and no AWS service, so it runs deterministically and instantly
 * under Surefire (class name ends in {@code Test}).</p>
 *
 * <p>Exception detail messages are asserted <strong>verbatim</strong> because
 * they form part of the observable, byte-equivalent behavior guarded by Gate&nbsp;1
 * and Gate&nbsp;4.</p>
 */
@DisplayName("FileStatusMapper — COBOL FILE STATUS to typed-exception translation")
class FileStatusMapperTest {

    /**
     * The component under test. It is stateless and dependency-free, so a single
     * shared instance is reused across every test method.
     */
    private final FileStatusMapper mapper = new FileStatusMapper();

    /**
     * Supplies the {@code (rawCode, expectedExceptionType, verbatimMessage)} rows
     * exercised by {@link #checkStringResolvesAndThrowsTypedException}.
     *
     * <p>The {@code "92"} row encodes the CRITICAL {@code 9x}-folding parity
     * point: {@link FileStatusCode#fromCode(String)} collapses any {@code 9x}
     * code to {@link FileStatusCode#LOGIC_ERROR}, so the resulting
     * {@link FileAccessException} reports the {@code LOGIC_ERROR} code
     * {@code "90"} &mdash; <em>not</em> the original {@code "92"}.</p>
     *
     * @return the parameterized rows for the string-delegation throwing matrix
     */
    static Stream<Arguments> stringThrowingCases() {
        return Stream.of(
                Arguments.of("23", RecordNotFoundException.class, "READ not found: K1"),
                Arguments.of("22", DuplicateRecordException.class, "READ already exists: K1"),
                Arguments.of("35", FileAccessException.class, "Data access error during READ (status 35)"),
                Arguments.of("92", FileAccessException.class, "Data access error during READ (status 90)"));
    }

    // ------------------------------------------------------------------
    // Phase 1 — check(FileStatusCode, ...) benign statuses are no-ops
    // ------------------------------------------------------------------

    /**
     * Verifies that the benign statuses ({@code SUCCESS "00"},
     * {@code FILE_CREATED "05"}, {@code DUPLICATE_ALTERNATE_KEY "02"}, and
     * {@code END_OF_FILE "10"}) return normally and never throw. End-of-file is a
     * control signal, not an error, and is detected separately by
     * {@link FileStatusMapper#isEndOfFile(FileStatusCode)}.
     *
     * @param status the benign file-status code that must be tolerated
     */
    @ParameterizedTest(name = "check({0}) does not throw")
    @EnumSource(value = FileStatusCode.class,
            names = {"SUCCESS", "FILE_CREATED", "DUPLICATE_ALTERNATE_KEY", "END_OF_FILE"})
    @DisplayName("check(enum): benign statuses are no-ops")
    void checkEnumBenignStatusesDoNotThrow(FileStatusCode status) {
        assertThatCode(() -> mapper.check(status, "READ", "K1")).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Phase 2 — check(FileStatusCode, ...) throwing paths
    // ------------------------------------------------------------------

    /**
     * Verifies that {@code RECORD_NOT_FOUND ("23")} raises a
     * {@link RecordNotFoundException} whose deterministic message and context
     * fields are derived from the supplied operation and key.
     */
    @Test
    @DisplayName("check(enum): RECORD_NOT_FOUND throws RecordNotFoundException")
    void checkEnumRecordNotFoundThrowsRecordNotFound() {
        assertThatExceptionOfType(RecordNotFoundException.class)
                .isThrownBy(() -> mapper.check(FileStatusCode.RECORD_NOT_FOUND, "READ ACCTFILE", "ACC-001"))
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).isEqualTo("READ ACCTFILE not found: ACC-001");
                    assertThat(ex.getEntityType()).isEqualTo("READ ACCTFILE");
                    assertThat(ex.getKey()).isEqualTo("ACC-001");
                });
    }

    /**
     * Verifies that {@code DUPLICATE_KEY ("22")} raises a
     * {@link DuplicateRecordException} whose deterministic message and context
     * fields are derived from the supplied operation and key.
     */
    @Test
    @DisplayName("check(enum): DUPLICATE_KEY throws DuplicateRecordException")
    void checkEnumDuplicateKeyThrowsDuplicateRecord() {
        assertThatExceptionOfType(DuplicateRecordException.class)
                .isThrownBy(() -> mapper.check(FileStatusCode.DUPLICATE_KEY, "WRITE CARDFILE", "CARD-99"))
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).isEqualTo("WRITE CARDFILE already exists: CARD-99");
                    assertThat(ex.getEntityType()).isEqualTo("WRITE CARDFILE");
                    assertThat(ex.getKey()).isEqualTo("CARD-99");
                });
    }

    /**
     * Verifies that the I/O-error family ({@code RECORD_LENGTH_MISMATCH "04"},
     * {@code FILE_NOT_FOUND "35"}, {@code LOGIC_ERROR "90"}) each raises a
     * {@link FileAccessException} carrying the originating operation and the
     * status code verbatim, with the deterministic diagnostic message.
     *
     * @param status the I/O-error status code that must map to a file-access fault
     */
    @ParameterizedTest(name = "check({0}) throws FileAccessException")
    @EnumSource(value = FileStatusCode.class,
            names = {"RECORD_LENGTH_MISMATCH", "FILE_NOT_FOUND", "LOGIC_ERROR"})
    @DisplayName("check(enum): I/O-error statuses throw FileAccessException")
    void checkEnumIoErrorStatusesThrowFileAccess(FileStatusCode status) {
        assertThatExceptionOfType(FileAccessException.class)
                .isThrownBy(() -> mapper.check(status, "OPEN", "KEYX"))
                .satisfies(ex -> {
                    assertThat(ex.getMessage())
                            .isEqualTo("Data access error during OPEN (status " + status.getCode() + ")");
                    assertThat(ex.getOperation()).isEqualTo("OPEN");
                    assertThat(ex.getFileStatus()).isEqualTo(status.getCode());
                });
    }

    /**
     * Verifies that a {@code null} {@link FileStatusCode} is treated as a
     * data-access fault and raises a {@link FileAccessException} whose message
     * carries the literal {@code null} status. The {@code (FileStatusCode)} cast
     * is required to disambiguate the {@code check} overloads.
     */
    @Test
    @DisplayName("check(enum): null status throws FileAccessException with null status")
    void checkEnumNullStatusThrowsFileAccess() {
        assertThatExceptionOfType(FileAccessException.class)
                .isThrownBy(() -> mapper.check((FileStatusCode) null, "READ", "K1"))
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).isEqualTo("Data access error during READ (status null)");
                    assertThat(ex.getOperation()).isEqualTo("READ");
                    assertThat(ex.getFileStatus()).isNull();
                });
    }

    // ------------------------------------------------------------------
    // Phase 3 — check(String, ...) delegates through FileStatusCode.fromCode
    // ------------------------------------------------------------------

    /**
     * Verifies that the raw success code {@code "00"} delegates to
     * {@link FileStatusCode#SUCCESS} and returns normally without throwing.
     */
    @Test
    @DisplayName("check(String): \"00\" resolves to SUCCESS and does not throw")
    void checkStringSuccessDoesNotThrow() {
        assertThatCode(() -> mapper.check("00", "READ", "K1")).doesNotThrowAnyException();
    }

    /**
     * Verifies that the {@code check(String, ...)} overload resolves a raw
     * two-character status via {@link FileStatusCode#fromCode(String)} and then
     * raises the same typed exception, with the same verbatim message, that the
     * enum overload produces. The {@code "92"} row proves the {@code 9x}-folding
     * rule: the message reports the folded {@code LOGIC_ERROR} code {@code "90"}.
     *
     * @param code            the raw two-character status value
     * @param expectedType    the typed exception the mapper must raise
     * @param expectedMessage the verbatim detail message the mapper must produce
     */
    @ParameterizedTest(name = "check(\"{0}\") throws {1}")
    @MethodSource("stringThrowingCases")
    @DisplayName("check(String): resolved codes raise the matching typed exception")
    void checkStringResolvesAndThrowsTypedException(String code,
                                                    Class<? extends RuntimeException> expectedType,
                                                    String expectedMessage) {
        assertThatThrownBy(() -> mapper.check(code, "READ", "K1"))
                .isInstanceOf(expectedType)
                .hasMessage(expectedMessage);
    }

    /**
     * Verifies that an unknown, non-{@code 9x} status code is rejected by
     * {@link FileStatusCode#fromCode(String)} and that the resulting
     * {@link IllegalArgumentException} surfaces unchanged through
     * {@code check(String, ...)}. {@code "77"} is the spec example; {@code "11"}
     * and {@code "ZZ"} extend the matrix.
     *
     * @param code an unrecognized, non-{@code 9x} status value
     */
    @ParameterizedTest(name = "check(\"{0}\") throws FileAccessException")
    @ValueSource(strings = {"77", "11", "ZZ"})
    @DisplayName("check(String): unknown non-9x codes raise a typed FileAccessException")
    void checkStringUnknownNonNineCodeThrowsIllegalArgument(String code) {
        assertThatThrownBy(() -> mapper.check(code, "READ", "K1"))
                .isInstanceOf(FileAccessException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that a {@code null} raw status raises an
     * {@link IllegalArgumentException} with the verbatim message produced by
     * {@link FileStatusCode#fromCode(String)}. The {@code (String)} cast is
     * required to disambiguate the {@code check} overloads.
     */
    @Test
    @DisplayName("check(String): null code raises a typed FileAccessException")
    void checkStringNullThrowsIllegalArgument() {
        assertThatThrownBy(() -> mapper.check((String) null, "READ", "K1"))
                .isInstanceOf(FileAccessException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause().hasMessage("COBOL FILE STATUS code must not be null");
    }

    /**
     * Verifies that a blank raw status (empty or whitespace-only, which trims to
     * empty) raises an {@link IllegalArgumentException} with the verbatim
     * "must not be blank" message.
     *
     * @param code a blank status value that must be rejected after trimming
     */
    @ParameterizedTest(name = "check(\"{0}\") throws blank FileAccessException")
    @ValueSource(strings = {"", "   "})
    @DisplayName("check(String): blank code raises a typed FileAccessException")
    void checkStringBlankThrowsIllegalArgument(String code) {
        assertThatThrownBy(() -> mapper.check(code, "READ", "K1"))
                .isInstanceOf(FileAccessException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .cause().hasMessage("COBOL FILE STATUS code must not be blank");
    }

    // ------------------------------------------------------------------
    // Phase 4 — predicate methods
    // ------------------------------------------------------------------

    /**
     * Verifies the end-of-file predicate across the entire enum in a single
     * pass: {@link FileStatusMapper#isEndOfFile(FileStatusCode)} must return
     * {@code true} for, and only for, {@link FileStatusCode#END_OF_FILE}.
     *
     * @param status each declared {@link FileStatusCode} constant
     */
    @ParameterizedTest(name = "isEndOfFile({0})")
    @EnumSource(FileStatusCode.class)
    @DisplayName("isEndOfFile: true only for END_OF_FILE")
    void isEndOfFileTrueOnlyForEndOfFile(FileStatusCode status) {
        assertThat(mapper.isEndOfFile(status)).isEqualTo(status == FileStatusCode.END_OF_FILE);
    }

    /**
     * Verifies that {@link FileStatusMapper#isEndOfFile(FileStatusCode)} is
     * null-safe and reports {@code false} for a {@code null} status. No cast is
     * needed because {@code isEndOfFile} has a single overload.
     */
    @Test
    @DisplayName("isEndOfFile: null is false")
    void isEndOfFileNullIsFalse() {
        assertThat(mapper.isEndOfFile(null)).isFalse();
    }

    /**
     * Verifies the success predicate across the entire enum in a single pass:
     * {@link FileStatusMapper#isSuccess(FileStatusCode)} must return
     * {@code true} for, and only for, {@link FileStatusCode#SUCCESS}.
     *
     * @param status each declared {@link FileStatusCode} constant
     */
    @ParameterizedTest(name = "isSuccess({0})")
    @EnumSource(FileStatusCode.class)
    @DisplayName("isSuccess(enum): true only for SUCCESS")
    void isSuccessEnumTrueOnlyForSuccess(FileStatusCode status) {
        assertThat(mapper.isSuccess(status)).isEqualTo(status == FileStatusCode.SUCCESS);
    }

    /**
     * Verifies that {@link FileStatusMapper#isSuccess(FileStatusCode)} is
     * null-safe and reports {@code false} for a {@code null} status. The
     * {@code (FileStatusCode)} cast is required to disambiguate the
     * {@code isSuccess} overloads.
     */
    @Test
    @DisplayName("isSuccess(enum): null is false")
    void isSuccessEnumNullIsFalse() {
        assertThat(mapper.isSuccess((FileStatusCode) null)).isFalse();
    }

    /**
     * Verifies the raw-string success convenience overload: {@code "00"} resolves
     * to {@link FileStatusCode#SUCCESS} and reports {@code true}; every other
     * resolvable code (record-not-found {@code "23"}, end-of-file {@code "10"},
     * and the {@code 9x}-folded {@code "90"}) reports {@code false}.
     *
     * @param code     the raw two-character status value
     * @param expected the success outcome the overload must report
     */
    @ParameterizedTest(name = "isSuccess(\"{0}\") == {1}")
    @CsvSource({"00,true", "23,false", "10,false", "90,false"})
    @DisplayName("isSuccess(String): true only when the resolved code is SUCCESS")
    void isSuccessStringResolvesAndTestsSuccess(String code, boolean expected) {
        assertThat(mapper.isSuccess(code)).isEqualTo(expected);
    }

    /**
     * Verifies that the raw-string success overload propagates the
     * {@link IllegalArgumentException} raised by
     * {@link FileStatusCode#fromCode(String)} for an unknown, non-{@code 9x}
     * code rather than silently returning {@code false}.
     */
    @Test
    @DisplayName("isSuccess(String): invalid code surfaces a typed FileAccessException")
    void isSuccessStringInvalidCodeThrowsIllegalArgument() {
        assertThatThrownBy(() -> mapper.isSuccess("77"))
                .isInstanceOf(FileAccessException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
