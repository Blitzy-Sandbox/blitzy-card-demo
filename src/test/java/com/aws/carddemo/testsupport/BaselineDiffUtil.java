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

// JDK imports only — no third-party libraries (AAP §0.10.2 Minimal Change Clause).
// java.io: checked IOException raised by Files.readAllBytes plus the unchecked
//          wrapper used to keep the public API free of checked-exception declarations.
// java.nio.charset: UTF-8 charset constant used for placeholder-marker scanning
//                   and ISO_8859_1 used for lossless one-byte-per-character line
//                   decoding in the unified-diff renderer.
// java.nio.file: Files.readAllBytes for whole-file ingestion and Path for the
//                canonical parameter type (with Path.of(...) used in the String
//                convenience overload).
// java.util: Arrays.equals for the byte-identical comparison, List as the return
//            type for splitLines, and ArrayList as the concrete list backing the
//            line accumulator.
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Byte-identical baseline-parity assertion utility for the CardDemo migration test
 * suite.
 *
 * <p>The CardDemo migration's parity contract (AAP §0.10.4) requires every Spring
 * Batch job's output file to be byte-for-byte identical to the captured COBOL
 * reference output. This utility encapsulates that comparison as a single method:
 * {@link #assertByteEqual(Path, Path)}.
 *
 * <p>Consumed by all five baseline-parity ITs and by {@code BatchPipelineE2EIT}:
 * <ul>
 *   <li>{@code TransactionPostingBaselineParityIT} — parity for
 *       {@code app/jcl/POSTTRAN.jcl} / {@code app/cbl/CBTRN02C.cbl}</li>
 *   <li>{@code InterestCalculationBaselineParityIT} — parity for
 *       {@code app/jcl/INTCALC.jcl} / {@code app/cbl/CBACT04C.cbl}</li>
 *   <li>{@code CombineTransactionsBaselineParityIT} — parity for
 *       {@code app/jcl/COMBTRAN.jcl} (DFSORT step, no COBOL program)</li>
 *   <li>{@code StatementGenerationBaselineParityIT} — parity for
 *       {@code app/jcl/CREASTMT.jcl} / {@code app/cbl/CBSTM03A.cbl}</li>
 *   <li>{@code TransactionReportBaselineParityIT} — parity for
 *       {@code app/jcl/TRANREPT.jcl} / {@code app/cbl/CBTRN03C.cbl}</li>
 * </ul>
 *
 * <h2>Behaviour Summary</h2>
 *
 * <p>{@link #assertByteEqual(Path, Path)} throws an {@link AssertionError} when:
 * <ul>
 *   <li>The expected file contains the {@code BASELINE_CAPTURE_PENDING_} placeholder
 *       (signals that the COBOL reference output has not yet been captured — the
 *       test MUST fail to prevent silent false positives).</li>
 *   <li>The two files have different byte lengths.</li>
 *   <li>Any single byte differs between the two files.</li>
 * </ul>
 *
 * <p>The {@link AssertionError} message includes:
 * <ul>
 *   <li>Actual and expected file paths.</li>
 *   <li>Actual and expected byte lengths.</li>
 *   <li>First mismatching byte offset (when applicable).</li>
 *   <li>Up to 20 differing lines in unified-diff style (each prefixed with
 *       {@code - } for expected, {@code + } for actual).</li>
 * </ul>
 *
 * <h2>Why pure byte comparison (not String comparison)</h2>
 *
 * <p>The COBOL reference outputs include fixed-width records with sign-overpunch
 * encoding ({@code &#123;} = +0, {@code A}..{@code I} = +1..+9, {@code &#125;} = -0,
 * {@code J}..{@code R} = -1..-9). A naive String comparison after default-charset
 * decoding could normalize line endings (CR/LF on Windows vs LF on Linux) and would
 * mask a single-byte sign-overpunch mismatch that violates AAP §0.10.3 financial-
 * precision requirements. Byte comparison preserves all of these signals.
 *
 * <h2>Why no AssertJ dependency</h2>
 *
 * <p>AAP §0.10.2 Minimal Change Clause. The utility is self-contained on the JDK
 * standard library; the custom line-diff implementation below is small,
 * deterministic, and avoids dragging an additional dependency surface into the
 * test-support layer.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All methods are pure functions over their inputs; there is no shared mutable
 * state. Safe to call concurrently across JUnit 5 parallel test classes
 * (AAP §0.10.9 parallel execution).
 *
 * <h2>Operational Note</h2>
 *
 * <p>A baseline parity IT typically runs a Spring Batch job that produces an output
 * file at e.g. {@code target/test-output/posted.txt}, then calls:
 * <pre>{@code
 * BaselineDiffUtil.assertByteEqual(
 *     Path.of("target/test-output/posted.txt"),
 *     Path.of("src/test/resources/baseline/expected/posted.txt"));
 * }</pre>
 * Zero-byte delta is required; any difference fails the test with a line-level
 * diagnostic identifying where the migrated Java code diverges from the COBOL
 * baseline.
 *
 * @see <a href="..">AAP §0.10.4 Immutable Boundaries</a>
 */
public final class BaselineDiffUtil {

    /**
     * Maximum number of differing lines to include in the assertion failure
     * message. Beyond this, the message is truncated with an ellipsis to keep
     * test output legible while still surfacing enough context for a developer to
     * localize the discrepancy.
     */
    private static final int MAX_DIFF_LINES = 20;

    /**
     * Marker substring used in placeholder baseline-expected files to indicate
     * that the COBOL reference output has not yet been captured. Any expected
     * file whose first 2 KB contains this substring causes an immediate
     * assertion failure with a clear diagnostic. The marker is intentionally a
     * fixed ASCII constant so it can be embedded verbatim in placeholder files
     * authored by humans.
     */
    private static final String PLACEHOLDER_MARKER = "BASELINE_CAPTURE_PENDING_";

    /**
     * Upper bound for the placeholder-marker scan, in bytes. The marker, when
     * present, always appears at the very top of the expected file (typically
     * inside the first comment line such as
     * {@code # BASELINE_CAPTURE_PENDING_POSTED}). Limiting the scan to the
     * first 2 KB avoids accidentally matching the literal substring deep inside
     * a large reference output (e.g., a transaction-report file that happens to
     * contain the marker as data).
     */
    private static final int PLACEHOLDER_SCAN_LIMIT_BYTES = 2048;

    private BaselineDiffUtil() {
        // Utility class — prevent instantiation. The throw guards against
        // reflective construction (e.g., via setAccessible(true)).
        throw new UnsupportedOperationException(
            "BaselineDiffUtil is a utility class and cannot be instantiated.");
    }

    /**
     * Asserts that two files are byte-for-byte identical.
     *
     * <p>This is the canonical parity gate for the CardDemo migration's
     * baseline-parity ITs. The method reads both files into memory (acceptable
     * for the small file sizes involved — the largest reference output is
     * {@code transaction_report.txt} at approximately 100 KB) and compares the
     * byte arrays via {@link Arrays#equals(byte[], byte[])}.
     *
     * <p><b>Placeholder detection:</b> If the expected file's content (decoded
     * as UTF-8 over its first {@value #PLACEHOLDER_SCAN_LIMIT_BYTES} bytes)
     * contains the {@code BASELINE_CAPTURE_PENDING_} marker, the assertion
     * fails immediately with a diagnostic indicating that the baseline
     * reference output has not yet been captured. This prevents silently
     * passing tests during the migration's early phases.
     *
     * <p><b>On success:</b> returns normally (no return value).
     *
     * <p><b>On failure:</b> throws {@link AssertionError} with a message that
     * includes:
     * <ul>
     *   <li>The two file paths (absolute, to disambiguate working-directory
     *       confusion in CI failure logs).</li>
     *   <li>Both file sizes in bytes.</li>
     *   <li>The first mismatching byte offset.</li>
     *   <li>Up to {@value #MAX_DIFF_LINES} differing lines in unified-diff
     *       format.</li>
     * </ul>
     *
     * @param actual   path to the file produced by the migrated Java code
     *                 (typically a Spring Batch job's output under
     *                 {@code target/test-output/})
     * @param expected path to the captured COBOL reference output under
     *                 {@code src/test/resources/baseline/expected/}
     * @throws AssertionError       when the files differ in any byte, or when
     *                              the expected file still contains the
     *                              {@code BASELINE_CAPTURE_PENDING_} marker
     * @throws NullPointerException when either path is {@code null}
     * @throws UncheckedIOException when either file cannot be read (e.g.,
     *                              missing, unreadable, or an I/O error
     *                              occurs during the read)
     */
    public static void assertByteEqual(Path actual, Path expected) {
        if (actual == null) {
            throw new NullPointerException("actual path must not be null");
        }
        if (expected == null) {
            throw new NullPointerException("expected path must not be null");
        }

        final byte[] actualBytes = readAllBytes(actual);
        final byte[] expectedBytes = readAllBytes(expected);

        // Placeholder recognition: fail loudly if the expected file is still a
        // stub. This check must run BEFORE the byte-equality short-circuit so
        // that a placeholder is never silently treated as a valid baseline,
        // even in the unlikely case that the actual output happens to equal
        // the placeholder text.
        if (isPlaceholder(expectedBytes)) {
            throw new AssertionError(buildPlaceholderMessage(expected));
        }

        if (Arrays.equals(actualBytes, expectedBytes)) {
            return; // Parity achieved — zero-byte delta.
        }

        throw new AssertionError(
            buildDiffMessage(actual, expected, actualBytes, expectedBytes));
    }

    /**
     * Convenience overload that accepts string paths. Equivalent to
     * {@link #assertByteEqual(Path, Path)} after {@link Path#of(String, String...)}
     * conversion.
     *
     * <p>Prefer the {@link Path}-typed overload in new test code; this overload
     * exists for call-site brevity when test fixtures and outputs are referenced
     * by literal string paths.
     *
     * @param actual   path string to the actual file
     * @param expected path string to the expected file
     * @throws AssertionError         when the files differ in any byte, or
     *                                when the expected file still contains the
     *                                {@code BASELINE_CAPTURE_PENDING_} marker
     * @throws NullPointerException   when either path string is {@code null}
     * @throws UncheckedIOException   when either file cannot be read
     * @see #assertByteEqual(Path, Path)
     */
    public static void assertByteEqual(String actual, String expected) {
        if (actual == null) {
            throw new NullPointerException("actual path must not be null");
        }
        if (expected == null) {
            throw new NullPointerException("expected path must not be null");
        }
        assertByteEqual(Path.of(actual), Path.of(expected));
    }

    // ================================================================
    // Private helpers
    // ================================================================

    /**
     * Wraps {@link Files#readAllBytes(Path)} with an unchecked exception for
     * cleaner call sites. The wrapped {@link UncheckedIOException} carries the
     * original {@link IOException} as its cause so that JUnit's failure report
     * shows the underlying error (e.g., "no such file", "permission denied").
     */
    private static byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException ioe) {
            throw new UncheckedIOException(
                "Failed to read file for baseline diff: " + path.toAbsolutePath(),
                ioe);
        }
    }

    /**
     * Returns {@code true} when the expected file's content (decoded as UTF-8
     * over its first {@value #PLACEHOLDER_SCAN_LIMIT_BYTES} bytes) contains the
     * {@link #PLACEHOLDER_MARKER} substring.
     *
     * <p>UTF-8 decoding is safe here because the marker is a pure ASCII
     * constant and ASCII is a strict UTF-8 subset; even if the expected file's
     * body contains non-UTF-8 bytes further in, the prefix decoding remains
     * deterministic up to the first invalid byte (which UTF-8 replaces with
     * {@code U+FFFD} REPLACEMENT CHARACTER under the default charset behaviour
     * of {@link String#String(byte[], int, int, java.nio.charset.Charset)}).
     */
    private static boolean isPlaceholder(byte[] expectedBytes) {
        if (expectedBytes.length == 0) {
            return false;
        }
        // Limit the search to a small prefix so a large reference output that
        // happens to contain the marker as a literal substring deep in its
        // body is not falsely flagged as a placeholder.
        final int searchLimit = Math.min(expectedBytes.length, PLACEHOLDER_SCAN_LIMIT_BYTES);
        final String prefix = new String(expectedBytes, 0, searchLimit, StandardCharsets.UTF_8);
        return prefix.contains(PLACEHOLDER_MARKER);
    }

    /**
     * Constructs the assertion failure message for the placeholder-detected case.
     *
     * <p>The message is deliberately verbose: it identifies the placeholder
     * file by absolute path, names the marker, and points the developer at the
     * capture procedure documented under {@code docs/testing/baseline-parity.md}.
     * This minimises mean-time-to-remediate when a parity IT fails purely
     * because the baseline has not been captured yet.
     */
    private static String buildPlaceholderMessage(Path expected) {
        return String.format(
            "Baseline reference output has not yet been captured.%n"
                + "  Expected file: %s%n"
                + "  This file currently contains the '%s' placeholder marker.%n"
                + "  Capture the COBOL reference output and replace the placeholder.%n"
                + "  See docs/testing/baseline-parity.md for the capture procedure.",
            expected.toAbsolutePath(),
            PLACEHOLDER_MARKER);
    }

    /**
     * Constructs the assertion failure message for a true byte mismatch.
     *
     * <p>Includes: file paths, byte counts, first differing offset (when the
     * common prefix contains a mismatch), and up to {@value #MAX_DIFF_LINES}
     * unified-diff lines. Lines are decoded as ISO-8859-1 so every byte renders
     * as exactly one character, preserving the column alignment of fixed-width
     * COBOL record output.
     */
    private static String buildDiffMessage(
        Path actual, Path expected, byte[] actualBytes, byte[] expectedBytes) {

        final StringBuilder sb = new StringBuilder(4096);
        sb.append("Baseline parity failure: files differ.").append(System.lineSeparator());
        sb.append("  Actual:   ").append(actual.toAbsolutePath())
            .append(" (").append(actualBytes.length).append(" bytes)")
            .append(System.lineSeparator());
        sb.append("  Expected: ").append(expected.toAbsolutePath())
            .append(" (").append(expectedBytes.length).append(" bytes)")
            .append(System.lineSeparator());

        final int firstDiff = findFirstDifferingOffset(actualBytes, expectedBytes);
        if (firstDiff >= 0) {
            sb.append("  First differing byte offset: ").append(firstDiff)
                .append(System.lineSeparator());
        } else if (actualBytes.length != expectedBytes.length) {
            // Common prefix is identical but lengths differ — the shorter file
            // is a prefix of the longer one.
            sb.append("  Files share a common prefix but differ in length.")
                .append(System.lineSeparator());
        }

        sb.append("  Line-level diff (up to ").append(MAX_DIFF_LINES)
            .append(" differing lines):")
            .append(System.lineSeparator());

        appendUnifiedDiff(sb, actualBytes, expectedBytes);

        return sb.toString();
    }

    /**
     * Returns the offset of the first differing byte between two arrays, or
     * {@code -1} if no byte in the common prefix differs (in which case the
     * arrays differ only in length and the shorter is a prefix of the longer).
     */
    private static int findFirstDifferingOffset(byte[] a, byte[] b) {
        final int common = Math.min(a.length, b.length);
        for (int i = 0; i < common; i++) {
            if (a[i] != b[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Appends up to {@value #MAX_DIFF_LINES} differing lines to {@code sb} in
     * unified-diff style. Lines are decoded as ISO-8859-1 to guarantee one byte
     * = one character (no multi-byte decoding surprises and no decoder
     * exceptions on arbitrary byte sequences). Lines are split on {@code 0x0A}
     * (LF); trailing CR bytes are preserved as visible characters to expose any
     * unexpected line-ending difference between actual and expected.
     */
    private static void appendUnifiedDiff(
        StringBuilder sb, byte[] actualBytes, byte[] expectedBytes) {

        final List<String> actualLines = splitLines(actualBytes);
        final List<String> expectedLines = splitLines(expectedBytes);

        int diffsEmitted = 0;
        final int maxLine = Math.max(actualLines.size(), expectedLines.size());
        for (int i = 0; i < maxLine && diffsEmitted < MAX_DIFF_LINES; i++) {
            final String actLine = i < actualLines.size() ? actualLines.get(i) : null;
            final String expLine = i < expectedLines.size() ? expectedLines.get(i) : null;
            if (actLine == null && expLine == null) {
                // Should not happen given the loop bound, but defensively skip.
                continue;
            }
            if (actLine == null) {
                // Expected has a line the actual file lacks — actual file is shorter.
                sb.append("    line ").append(i + 1).append(": - ")
                    .append(expLine).append(System.lineSeparator());
                diffsEmitted++;
            } else if (expLine == null) {
                // Actual has a line the expected file lacks — actual file is longer.
                sb.append("    line ").append(i + 1).append(": + ")
                    .append(actLine).append(System.lineSeparator());
                diffsEmitted++;
            } else if (!actLine.equals(expLine)) {
                // Both files have a line at this index but they differ.
                sb.append("    line ").append(i + 1).append(":")
                    .append(System.lineSeparator())
                    .append("      - ").append(expLine).append(System.lineSeparator())
                    .append("      + ").append(actLine).append(System.lineSeparator());
                diffsEmitted++;
            }
            // Identical lines are intentionally omitted from the diff output.
        }

        if (diffsEmitted == MAX_DIFF_LINES && maxLine > MAX_DIFF_LINES) {
            sb.append("    ... (diff truncated after ").append(MAX_DIFF_LINES)
                .append(" differing lines) ...").append(System.lineSeparator());
        }
    }

    /**
     * Splits a byte buffer into lines on {@code 0x0A} (LF). Each line is
     * decoded as ISO-8859-1 so every byte renders as one character; trailing
     * CR ({@code 0x0D}) bytes are preserved as visible characters in the
     * returned string. The final line is included only when the buffer does
     * NOT end with LF — a buffer that ends with LF produces no trailing empty
     * line, matching the textbook line-counting convention used by most diff
     * tools.
     */
    private static List<String> splitLines(byte[] bytes) {
        final ArrayList<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == (byte) '\n') {
                lines.add(new String(bytes, start, i - start, StandardCharsets.ISO_8859_1));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            // Trailing content without a terminating LF is still a line.
            lines.add(new String(bytes, start, bytes.length - start, StandardCharsets.ISO_8859_1));
        }
        return lines;
    }
}
