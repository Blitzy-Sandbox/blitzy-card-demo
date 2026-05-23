/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.aws.carddemo.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BaselineDiffUtil}, the byte-identical baseline-parity
 * assertion utility used by the five Spring Batch baseline-parity ITs and by
 * {@code BatchPipelineE2EIT}.
 *
 * <p><b>Why these tests exist (QA finding CK12-003):</b> {@code BaselineDiffUtil}
 * implements three distinct behaviour paths — byte-equal success, byte-inequal
 * failure with unified-diff content, and placeholder-detected failure with
 * remediation pointer. The placeholder-detection path is the critical safety
 * net that prevents silent pass-through during the migration's baseline-capture
 * phase; the diff-message path is the developer-facing failure mode that must
 * surface enough context to localise a parity regression. Both paths benefit
 * from focused unit-test coverage rather than only being exercised as a side
 * effect of an integration test.
 *
 * <p>These tests follow the AAP §0.10.10 style conventions:
 * <ul>
 *   <li>Arrange-Act-Assert structure with blank-line separators.</li>
 *   <li>AssertJ fluent assertions ({@link assertThat}, {@code assertThatExceptionOfType}).</li>
 *   <li>{@link TempDir} for filesystem isolation — each test owns its own
 *       directory that JUnit recursively deletes after the test completes.</li>
 *   <li>No production-code dependencies beyond {@link BaselineDiffUtil} itself
 *       and JDK types ({@link Files}, {@link Path}).</li>
 *   <li>Test method names follow the {@code methodUnderTest_inputCondition_expectedOutcome}
 *       convention so failures are self-describing in CI reports.</li>
 * </ul>
 *
 * <p>Coverage matrix (verified by these tests):
 * <table>
 *   <caption>Behaviour matrix exercised by this test class</caption>
 *   <tr><th>Scenario</th><th>Expected behaviour</th><th>Test method</th></tr>
 *   <tr>
 *     <td>Identical byte content</td>
 *     <td>Returns normally (no throw)</td>
 *     <td>{@code assertByteEqual_identicalBytes_returnsNormally},
 *         {@code assertByteEqual_emptyFilesIdentical_returnsNormally}</td>
 *   </tr>
 *   <tr>
 *     <td>Single byte mismatch in common prefix</td>
 *     <td>Throws {@link AssertionError} with diff message including
 *         "First differing byte offset"</td>
 *     <td>{@code assertByteEqual_singleByteDifference_throwsAssertionErrorWithDiff}</td>
 *   </tr>
 *   <tr>
 *     <td>Same prefix, different lengths</td>
 *     <td>Throws {@link AssertionError} with message mentioning length divergence</td>
 *     <td>{@code assertByteEqual_differentLengthsCommonPrefix_throwsAssertionError}</td>
 *   </tr>
 *   <tr>
 *     <td>Empty vs non-empty</td>
 *     <td>Throws {@link AssertionError}</td>
 *     <td>{@code assertByteEqual_emptyVsNonEmpty_throwsAssertionError}</td>
 *   </tr>
 *   <tr>
 *     <td>Expected file contains placeholder marker</td>
 *     <td>Throws {@link AssertionError} with remediation message naming
 *         {@code BASELINE_CAPTURE_PENDING_} and pointing at
 *         {@code docs/testing/baseline-parity.md}</td>
 *     <td>{@code assertByteEqual_expectedPlaceholder_throwsAssertionErrorWithRemediation},
 *         {@code assertByteEqual_placeholderTakesPrecedenceOverByteEquality_throws}</td>
 *   </tr>
 *   <tr>
 *     <td>Placeholder marker only in body beyond 2 KB</td>
 *     <td>Not flagged as placeholder; byte-diff message instead</td>
 *     <td>{@code assertByteEqual_markerBeyondScanLimit_notTreatedAsPlaceholder}</td>
 *   </tr>
 *   <tr>
 *     <td>Missing actual or expected file</td>
 *     <td>Throws {@link UncheckedIOException} with path detail</td>
 *     <td>{@code assertByteEqual_missingActualFile_throwsUncheckedIOException},
 *         {@code assertByteEqual_missingExpectedFile_throwsUncheckedIOException}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code null} path argument(s)</td>
 *     <td>Throws {@link NullPointerException}</td>
 *     <td>{@code assertByteEqual_nullPaths_throwsNullPointerException} (in nested class)</td>
 *   </tr>
 *   <tr>
 *     <td>String overload</td>
 *     <td>Behaves identically to {@link Path} overload</td>
 *     <td>{@code assertByteEqual_stringOverloadIdenticalBytes_returnsNormally},
 *         {@code assertByteEqual_stringOverloadNullPaths_throwsNullPointerException}</td>
 *   </tr>
 * </table>
 *
 * @see BaselineDiffUtil the utility under test
 */
@DisplayName("BaselineDiffUtil — byte-identical baseline parity assertion utility")
class BaselineDiffUtilTest {

    /**
     * Per-test isolated temporary directory injected by JUnit 5's {@link TempDir}
     * extension. Files written here are recursively deleted after each test,
     * guaranteeing filesystem isolation between tests.
     */
    @TempDir
    Path workDir;

    // ------------------------------------------------------------------
    // Happy path — byte-equal files return normally
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Identical byte content returns normally without throwing")
    void assertByteEqual_identicalBytes_returnsNormally() throws IOException {
        // Arrange — write the same content to two files. Use a realistic
        // fixed-width COBOL-style record so the test exercises the same byte
        // shapes the parity ITs use in production.
        final byte[] payload = "ACCT0000000010CUST00001 100.00\n".getBytes(StandardCharsets.ISO_8859_1);
        final Path actual = writeBytes("actual.txt", payload);
        final Path expected = writeBytes("expected.txt", payload);

        // Act + Assert — must not throw.
        assertThatCode(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Two empty files compare as byte-equal and return normally")
    void assertByteEqual_emptyFilesIdentical_returnsNormally() throws IOException {
        // Arrange — empty files are still valid baselines (e.g., a job that
        // produces no output when filtering everything out).
        final Path actual = writeBytes("empty-actual.txt", new byte[0]);
        final Path expected = writeBytes("empty-expected.txt", new byte[0]);

        // Act + Assert — must not throw; empty == empty.
        assertThatCode(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Sad path — byte-inequal files throw AssertionError with diff content
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Single byte difference throws AssertionError with diff offset and unified-diff content")
    void assertByteEqual_singleByteDifference_throwsAssertionErrorWithDiff() throws IOException {
        // Arrange — same length, single byte different at offset 4. This
        // mirrors the kind of regression a parity IT must surface (e.g., a
        // sign-overpunch encoding change at a fixed column).
        final byte[] actualPayload = "AAAA1AAA\n".getBytes(StandardCharsets.ISO_8859_1);
        final byte[] expectedPayload = "AAAA2AAA\n".getBytes(StandardCharsets.ISO_8859_1);
        final Path actual = writeBytes("actual.txt", actualPayload);
        final Path expected = writeBytes("expected.txt", expectedPayload);

        // Act + Assert — throws AssertionError carrying the diff diagnostics.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Baseline parity failure: files differ.")
            .withMessageContaining("First differing byte offset: 4")
            .withMessageContaining("Line-level diff")
            // Unified-diff content surfaces both sides of the line.
            .withMessageContaining("- AAAA2AAA")
            .withMessageContaining("+ AAAA1AAA")
            // Byte counts surfaced so the developer can spot length divergence.
            .withMessageContaining("9 bytes");
    }

    @Test
    @DisplayName("Common prefix but different lengths throws AssertionError naming the length divergence")
    void assertByteEqual_differentLengthsCommonPrefix_throwsAssertionError() throws IOException {
        // Arrange — actual is a strict prefix of expected. The util's
        // findFirstDifferingOffset returns -1 in this case and the message
        // routes through the "common prefix" branch.
        final byte[] actualPayload = "AAAA\n".getBytes(StandardCharsets.ISO_8859_1);
        final byte[] expectedPayload = "AAAA\nEXTRA\n".getBytes(StandardCharsets.ISO_8859_1);
        final Path actual = writeBytes("actual.txt", actualPayload);
        final Path expected = writeBytes("expected.txt", expectedPayload);

        // Act + Assert — throws AssertionError with the length-divergence message.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Baseline parity failure: files differ.")
            .withMessageContaining("Files share a common prefix but differ in length.")
            // The expected file's extra line is surfaced as a "missing in actual" diff line.
            .withMessageContaining("- EXTRA");
    }

    @Test
    @DisplayName("Empty actual vs non-empty expected throws AssertionError")
    void assertByteEqual_emptyVsNonEmpty_throwsAssertionError() throws IOException {
        // Arrange — empty produced output where a non-empty baseline is expected.
        final Path actual = writeBytes("actual.txt", new byte[0]);
        final Path expected = writeBytes("expected.txt",
            "BASELINE\n".getBytes(StandardCharsets.ISO_8859_1));

        // Act + Assert — throws AssertionError; sizes (0 vs 9) surface in the message.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Baseline parity failure: files differ.")
            .withMessageContaining("0 bytes")
            .withMessageContaining("9 bytes");
    }

    @Test
    @DisplayName("Diff message truncates to MAX_DIFF_LINES when many lines differ")
    void assertByteEqual_manyDifferingLines_truncatesDiffWithEllipsis() throws IOException {
        // Arrange — produce 25 differing lines so the diff renderer crosses the
        // MAX_DIFF_LINES=20 truncation threshold.
        final StringBuilder actualBuilder = new StringBuilder();
        final StringBuilder expectedBuilder = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            actualBuilder.append(String.format("ACT-line-%02d%n", i));
            expectedBuilder.append(String.format("EXP-line-%02d%n", i));
        }
        final Path actual = writeBytes("actual.txt",
            actualBuilder.toString().getBytes(StandardCharsets.ISO_8859_1));
        final Path expected = writeBytes("expected.txt",
            expectedBuilder.toString().getBytes(StandardCharsets.ISO_8859_1));

        // Act + Assert — truncation marker present and the 25th line is omitted.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("diff truncated after 20 differing lines");
    }

    // ------------------------------------------------------------------
    // Placeholder-detection path — the critical safety net
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Expected file containing BASELINE_CAPTURE_PENDING_ marker throws AssertionError with remediation pointer")
    void assertByteEqual_expectedPlaceholder_throwsAssertionErrorWithRemediation() throws IOException {
        // Arrange — placeholder file shaped like the ones the migration's
        // testing flavor authored before the COBOL baselines were captured.
        final byte[] placeholderPayload = "# BASELINE_CAPTURE_PENDING_POSTING\n"
            .getBytes(StandardCharsets.UTF_8);
        final Path actual = writeBytes("actual.txt",
            "any-bytes\n".getBytes(StandardCharsets.ISO_8859_1));
        final Path expected = writeBytes("expected.txt", placeholderPayload);

        // Act + Assert — throws AssertionError with the dedicated remediation
        // message that names the marker constant AND points the developer at
        // the capture procedure documentation.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Baseline reference output has not yet been captured.")
            .withMessageContaining("BASELINE_CAPTURE_PENDING_")
            .withMessageContaining("Capture the COBOL reference output")
            .withMessageContaining("docs/testing/baseline-parity.md")
            // The placeholder file's absolute path is surfaced for IDE click-through.
            .withMessageContaining(expected.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("Placeholder detection takes precedence over byte-equality short-circuit")
    void assertByteEqual_placeholderTakesPrecedenceOverByteEquality_throws() throws IOException {
        // Arrange — both files contain the placeholder marker. Even though
        // they are byte-identical, the placeholder check (which runs BEFORE
        // the equality short-circuit per BaselineDiffUtil.assertByteEqual
        // implementation) must fail the assertion.
        //
        // This guards against the scenario where a CI run with all
        // placeholder fixtures still in place is accidentally treated as
        // "all parity ITs pass" because actual==expected as raw bytes.
        final byte[] placeholderPayload = "# BASELINE_CAPTURE_PENDING_INTEREST\n"
            .getBytes(StandardCharsets.UTF_8);
        final Path actual = writeBytes("actual.txt", placeholderPayload);
        final Path expected = writeBytes("expected.txt", placeholderPayload);

        // Act + Assert — placeholder remediation message wins, not silent success.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Baseline reference output has not yet been captured.")
            .withMessageContaining("BASELINE_CAPTURE_PENDING_");
    }

    @Test
    @DisplayName("Marker located beyond 2 KB scan window is not treated as a placeholder")
    void assertByteEqual_markerBeyondScanLimit_notTreatedAsPlaceholder() throws IOException {
        // Arrange — payload that places the marker AFTER the first 2 KB so the
        // scan limit (PLACEHOLDER_SCAN_LIMIT_BYTES = 2048) does not see it.
        // This is the precise corner case the limit exists to handle: a real
        // baseline can legitimately contain the literal substring deep inside
        // its body (e.g., transaction description text) without being mis-flagged.
        final byte[] padding = new byte[3000];
        for (int i = 0; i < padding.length; i++) {
            // ASCII 'A' so the prefix decodes deterministically as UTF-8.
            padding[i] = (byte) 'A';
        }
        final byte[] markerSuffix = "BASELINE_CAPTURE_PENDING_TEST".getBytes(StandardCharsets.UTF_8);

        // Compose actual = padding only; expected = padding + marker (different content).
        final Path actual = writeBytes("actual.txt", padding);
        final byte[] expectedPayload = new byte[padding.length + markerSuffix.length];
        System.arraycopy(padding, 0, expectedPayload, 0, padding.length);
        System.arraycopy(markerSuffix, 0, expectedPayload, padding.length, markerSuffix.length);
        final Path expected = writeBytes("expected.txt", expectedPayload);

        // Act + Assert — files differ in length (actual is a prefix of expected),
        // so the assertion fails with the byte-diff message, NOT the placeholder
        // remediation message. This proves the scan limit is honored.
        assertThatExceptionOfType(AssertionError.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Baseline parity failure: files differ.")
            // Explicitly NOT the placeholder remediation message:
            .withMessageNotContaining("Baseline reference output has not yet been captured.");
    }

    // ------------------------------------------------------------------
    // Error path — I/O failures and null arguments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Missing actual file throws UncheckedIOException with path detail")
    void assertByteEqual_missingActualFile_throwsUncheckedIOException() throws IOException {
        // Arrange — expected file exists but the actual file path does not.
        final Path actual = workDir.resolve("does-not-exist.txt");
        final Path expected = writeBytes("expected.txt",
            "baseline\n".getBytes(StandardCharsets.ISO_8859_1));

        // Act + Assert — UncheckedIOException wraps the underlying NoSuchFileException
        // and the actual file's absolute path is surfaced in the message.
        assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Failed to read file for baseline diff")
            .withMessageContaining(actual.toAbsolutePath().toString())
            .withCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Missing expected file throws UncheckedIOException with path detail")
    void assertByteEqual_missingExpectedFile_throwsUncheckedIOException() throws IOException {
        // Arrange — actual file exists but the expected file path does not.
        final Path actual = writeBytes("actual.txt",
            "produced\n".getBytes(StandardCharsets.ISO_8859_1));
        final Path expected = workDir.resolve("does-not-exist.txt");

        // Act + Assert — UncheckedIOException wraps the underlying NoSuchFileException
        // and the expected file's absolute path is surfaced in the message.
        assertThatExceptionOfType(UncheckedIOException.class)
            .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, expected))
            .withMessageContaining("Failed to read file for baseline diff")
            .withMessageContaining(expected.toAbsolutePath().toString())
            .withCauseInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------
    // String-overload contract
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("String-path overload")
    class StringOverload {

        @Test
        @DisplayName("String overload with identical byte content returns normally")
        void assertByteEqual_stringOverloadIdenticalBytes_returnsNormally() throws IOException {
            // Arrange — write identical content; call the String-path overload.
            final byte[] payload = "OK\n".getBytes(StandardCharsets.ISO_8859_1);
            final Path actual = writeBytes("actual.txt", payload);
            final Path expected = writeBytes("expected.txt", payload);

            // Act + Assert — must not throw; verifies the String overload routes
            // to the Path overload's success path.
            assertThatCode(() -> BaselineDiffUtil.assertByteEqual(
                actual.toString(), expected.toString()))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("String overload with byte mismatch throws AssertionError")
        void assertByteEqual_stringOverloadByteMismatch_throwsAssertionError() throws IOException {
            // Arrange — actual differs from expected; call the String overload.
            final Path actual = writeBytes("actual.txt",
                "AAA\n".getBytes(StandardCharsets.ISO_8859_1));
            final Path expected = writeBytes("expected.txt",
                "BBB\n".getBytes(StandardCharsets.ISO_8859_1));

            // Act + Assert — same failure surface as the Path overload.
            assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(
                    actual.toString(), expected.toString()))
                .withMessageContaining("Baseline parity failure: files differ.");
        }

        @Test
        @DisplayName("String overload with null actual throws NullPointerException")
        void assertByteEqual_stringOverloadNullActual_throwsNullPointerException() throws IOException {
            // Arrange — expected exists but actual is null.
            final Path expected = writeBytes("expected.txt",
                "OK\n".getBytes(StandardCharsets.ISO_8859_1));

            // Act + Assert — fast-fails with NPE carrying the parameter name.
            assertThatNullPointerException()
                .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(
                    (String) null, expected.toString()))
                .withMessageContaining("actual path must not be null");
        }

        @Test
        @DisplayName("String overload with null expected throws NullPointerException")
        void assertByteEqual_stringOverloadNullExpected_throwsNullPointerException() throws IOException {
            // Arrange — actual exists but expected is null.
            final Path actual = writeBytes("actual.txt",
                "OK\n".getBytes(StandardCharsets.ISO_8859_1));

            // Act + Assert — fast-fails with NPE carrying the parameter name.
            assertThatNullPointerException()
                .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(
                    actual.toString(), (String) null))
                .withMessageContaining("expected path must not be null");
        }
    }

    // ------------------------------------------------------------------
    // Null-argument contract for the Path overload
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Null-path argument contract")
    class NullPathArgs {

        @Test
        @DisplayName("Null actual Path throws NullPointerException with parameter name")
        void assertByteEqual_nullActualPath_throwsNullPointerException() throws IOException {
            // Arrange — expected exists, actual is null.
            final Path expected = writeBytes("expected.txt",
                "X\n".getBytes(StandardCharsets.ISO_8859_1));

            // Act + Assert — fast-fails with NPE before any I/O.
            assertThatNullPointerException()
                .isThrownBy(() -> BaselineDiffUtil.assertByteEqual((Path) null, expected))
                .withMessageContaining("actual path must not be null");
        }

        @Test
        @DisplayName("Null expected Path throws NullPointerException with parameter name")
        void assertByteEqual_nullExpectedPath_throwsNullPointerException() throws IOException {
            // Arrange — actual exists, expected is null.
            final Path actual = writeBytes("actual.txt",
                "X\n".getBytes(StandardCharsets.ISO_8859_1));

            // Act + Assert — fast-fails with NPE before any I/O.
            assertThatNullPointerException()
                .isThrownBy(() -> BaselineDiffUtil.assertByteEqual(actual, (Path) null))
                .withMessageContaining("expected path must not be null");
        }
    }

    // ------------------------------------------------------------------
    // Constructor contract (utility class — no instantiation)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Utility-class constructor contract")
    class ConstructorContract {

        /**
         * Verifies that {@link BaselineDiffUtil} cannot be reflectively
         * instantiated, even via {@link java.lang.reflect.Constructor#setAccessible(boolean)}.
         * This ensures the class enforces its utility-class invariant beyond
         * the {@code private} access modifier on the constructor.
         *
         * <p>Reflection access is wrapped in {@link java.lang.reflect.InvocationTargetException},
         * which carries the actual {@link UnsupportedOperationException} as its
         * cause; we unwrap and assert against the cause.
         */
        @Test
        @DisplayName("Reflective instantiation throws UnsupportedOperationException")
        void instantiation_viaReflection_throwsUnsupportedOperationException() throws Exception {
            // Arrange — locate the private no-arg constructor reflectively.
            final java.lang.reflect.Constructor<BaselineDiffUtil> ctor =
                BaselineDiffUtil.class.getDeclaredConstructor();
            ctor.setAccessible(true);

            // Act + Assert — newInstance() wraps the UnsupportedOperationException
            // in InvocationTargetException; verify the unwrapped cause.
            assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                .isThrownBy(ctor::newInstance)
                .havingCause()
                .isInstanceOf(UnsupportedOperationException.class)
                .withMessageContaining(
                    "BaselineDiffUtil is a utility class and cannot be instantiated.");
        }
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    /**
     * Writes the given byte array to a file under {@link #workDir} and returns
     * the resulting absolute path. Each call writes to a fresh file so the
     * caller can request multiple files within the same test.
     *
     * @param relativeName file name relative to {@link #workDir} (must not contain {@code /})
     * @param bytes content to write
     * @return absolute path of the written file
     * @throws IOException when the write fails (which would itself be a test infrastructure bug)
     */
    private Path writeBytes(String relativeName, byte[] bytes) throws IOException {
        final Path file = workDir.resolve(relativeName);
        Files.write(file, bytes);
        return file;
    }
}
