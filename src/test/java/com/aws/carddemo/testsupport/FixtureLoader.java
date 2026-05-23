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
// java.io: stream I/O primitives for classpath resource reading.
// java.nio.charset: strict US_ASCII decoder (catches fixture corruption immediately).
// java.util: collection primitives for returning fixture content as unmodifiable lists.
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test fixture loader for the CardDemo migration test suite.
 *
 * <p>Provides static helpers that read canonical fixtures from the test classpath
 * ({@code src/test/resources/baseline/input/*.txt} and
 * {@code src/test/resources/fixtures/edge/*.csv}) and return them as raw
 * {@link String} lines or {@code String[]} rows. Production parsing code (the
 * migrated {@code com.aws.carddemo.io.*} adapters) is responsible for interpreting
 * the fixed-width COBOL record layouts and decoding sign-overpunch — this utility
 * deliberately does NO business-logic decoding so as not to violate the
 * AAP §0.10.1 Require Test Coverage rule ("Tests MUST NOT reimplement any business
 * or calculation logic inside test bodies").
 *
 * <h2>Available fixtures</h2>
 *
 * <p>Canonical ASCII fixtures under {@code baseline/input/} (RECLN per COBOL copybook):
 * <ul>
 *   <li>{@code acctdata.txt} — 50 records, 300 chars each ({@link #loadAccountFixtures})</li>
 *   <li>{@code carddata.txt} — 50 records, 150 chars each ({@link #loadCardFixtures})</li>
 *   <li>{@code cardxref.txt} — 50 records, 36 chars each</li>
 *   <li>{@code custdata.txt} — 50 records, 500 chars each</li>
 *   <li>{@code dailytran.txt} — 300 records, 350 chars each ({@link #loadDailyTransactions})</li>
 *   <li>{@code discgrp.txt} — 51 records, 50 chars each</li>
 *   <li>{@code tcatbal.txt} — 50 records, 50 chars each</li>
 *   <li>{@code trancatg.txt} — 18 records, 60 chars each</li>
 *   <li>{@code trantype.txt} — 7 records, 60 chars each</li>
 * </ul>
 *
 * <p>Edge-case CSVs under {@code fixtures/edge/} (loaded via {@link #loadEdgeCases(String)}):
 * <ul>
 *   <li>{@code interest_halfeven_boundary.csv}, {@code interest_zero_balance.csv},
 *       {@code interest_zeroapr_skip.csv}, {@code interest_default_fallback.csv}</li>
 *   <li>{@code posting_reject_codes.csv}, {@code overflow_boundary.csv}</li>
 *   <li>{@code lookup_invalid_keys.csv}, {@code date_validation_variants.csv}</li>
 *   <li>{@code status_code_mappings.csv}, {@code eof_boundary.csv}</li>
 * </ul>
 *
 * <h2>Return semantics</h2>
 *
 * <p>{@link #loadAccountFixtures()}, {@link #loadCardFixtures()},
 * {@link #loadDailyTransactions()}, and the generic
 * {@link #loadAsLinesFromBaselineInput(String)} return {@code List<String>}
 * where each element is one COBOL record (one line) WITHOUT the trailing
 * line-feed. Each line's length equals the RECLN declared by the originating
 * copybook (e.g., 300 chars for accounts).
 *
 * <p>{@link #loadAsBytes(String)} returns a {@code byte[]} containing the raw,
 * un-decoded file contents — useful when staging an input file for a Spring Batch
 * job that reads via {@code FlatFileItemReader} from a filesystem path.
 *
 * <p>{@link #loadEdgeCases(String)} returns {@code List<String[]>} where each
 * element is one CSV row. The header row is the first element; callers typically
 * skip it via {@code .subList(1, rows.size())} when iterating data rows.
 *
 * <h2>Thread safety</h2>
 *
 * <p>All methods are pure functions reading from the classpath; no mutable state
 * is maintained. Safe for concurrent use across the JUnit 5 parallel test classes
 * configured by {@code junit-platform.properties}
 * ({@code junit.jupiter.execution.parallel.enabled = true}, AAP §0.10.9).
 *
 * <h2>Charset discipline</h2>
 *
 * <p>ASCII fixtures are decoded as {@link StandardCharsets#US_ASCII}. The strict
 * US-ASCII decoder rejects any byte outside {@code 0x00..0x7F} with a
 * {@code MalformedInputException}, catching fixture corruption at load time
 * rather than letting silently-mangled data flow into production parsing code.
 *
 * <h2>Business-logic exclusion</h2>
 *
 * <p>Per AAP §0.10.1 (Require Test Coverage rule), this class deliberately does
 * NOT contain:
 * <ul>
 *   <li>Sign-overpunch decoding (the {@code '{'}, {@code 'A'-'I'}, {@code '}'},
 *       {@code 'J'-'R'} characters) — that is monetary-calculation logic owned
 *       by production code under {@code com.aws.carddemo.io.*}.</li>
 *   <li>{@code BigDecimal} arithmetic of any kind — monetary computation is the
 *       sole responsibility of production service / processor classes.</li>
 *   <li>Fixed-width record parsing — production adapters interpret the COBOL
 *       record layouts; tests pass raw lines through to them.</li>
 *   <li>Validity flags, status decoding, or any business-semantic interpretation
 *       of fixture content.</li>
 * </ul>
 *
 * <h2>Companion</h2>
 *
 * <p>{@link TestFixtures} defines the canonical string constants for fixture
 * filenames and sample IDs. Tests typically pass {@code TestFixtures.Paths.*}
 * constants through the {@code loadAs*} methods on this class.
 *
 * <h2>Authority</h2>
 *
 * <p>AAP §0.5.1 (Test Support Utilities), §0.5.5 (Cross-File Test Dependencies),
 * §0.4.4 (Test Data and Fixtures Design), §0.10.1 (Require Test Coverage rule),
 * §0.10.2 (Minimal Change Clause), §0.10.5 (Security Constraints — no logging),
 * §0.10.9 (Test Execution Independence and Parallelism).
 */
public final class FixtureLoader {

    /**
     * Classpath directory holding canonical ASCII fixtures (one-for-one copies of
     * the {@code app/data/ASCII/*.txt} files in the source repository).
     *
     * <p>Leading-slash form so it can be concatenated directly with a filename and
     * passed to {@link Class#getResourceAsStream(String)} — the leading slash
     * causes the JVM to resolve relative to the classpath root rather than the
     * package of {@link FixtureLoader}.
     */
    private static final String BASELINE_INPUT_DIR = "/baseline/input/";

    /**
     * Classpath directory holding edge-case CSV fixtures consumed by
     * {@code @CsvFileSource} and {@link #loadEdgeCases(String)}.
     */
    private static final String EDGE_FIXTURES_DIR = "/fixtures/edge/";

    /**
     * Canonical filename of the account master fixture (50 records,
     * RECLN 300 per {@code app/cpy/CVACT01Y.cpy}).
     */
    private static final String ACCOUNT_FIXTURE = "acctdata.txt";

    /**
     * Canonical filename of the card master fixture (50 records,
     * RECLN 150 per {@code app/cpy/CVACT02Y.cpy}).
     */
    private static final String CARD_FIXTURE = "carddata.txt";

    /**
     * Canonical filename of the daily transactions fixture (300 records,
     * RECLN 350 per {@code app/cpy/CVTRA05Y.cpy}).
     */
    private static final String DAILY_TRANSACTIONS_FIXTURE = "dailytran.txt";

    /**
     * Default buffer size (8 KiB) for byte-level reads in {@link #loadAsBytes}.
     * Chosen to match the JDK default for {@link BufferedReader} and the typical
     * filesystem page size — small enough to keep memory pressure low when many
     * test classes run in parallel, large enough to amortise the per-read JVM
     * boundary cost.
     */
    private static final int IO_BUFFER_SIZE = 8192;

    /**
     * Utility class — instantiation is forbidden. The {@link UnsupportedOperationException}
     * makes reflective abuse fail loudly rather than silently producing an inert
     * instance.
     */
    private FixtureLoader() {
        throw new UnsupportedOperationException(
            "FixtureLoader is a utility class and cannot be instantiated.");
    }

    // ================================================================
    // Named ASCII fixture loaders
    // ================================================================

    /**
     * Loads the 50-record account master fixture as a list of fixed-width lines.
     *
     * <p>Each returned string is exactly 300 ASCII characters wide, matching the
     * {@code CVACT01Y.cpy} record layout (RECLN 300). The trailing line-feed is
     * stripped by {@link BufferedReader#readLine()}.
     *
     * <p>The returned list is unmodifiable; callers must not attempt to add or
     * remove entries. A fresh list is produced on each call, so concurrent callers
     * do not share mutable state.
     *
     * @return immutable list of 50 fixed-width account records
     * @throws UncheckedIOException     when an I/O error occurs while reading
     * @throws IllegalArgumentException when {@code acctdata.txt} is missing from
     *                                  the test classpath
     * @see #loadAsLinesFromBaselineInput(String)
     */
    public static List<String> loadAccountFixtures() {
        return loadAsLines(BASELINE_INPUT_DIR + ACCOUNT_FIXTURE);
    }

    /**
     * Loads the 50-record card master fixture as a list of fixed-width lines.
     *
     * <p>Each returned string is exactly 150 ASCII characters wide, matching the
     * {@code CVACT02Y.cpy} record layout (RECLN 150). The trailing line-feed is
     * stripped.
     *
     * @return immutable list of 50 fixed-width card records
     * @throws UncheckedIOException     when an I/O error occurs while reading
     * @throws IllegalArgumentException when {@code carddata.txt} is missing from
     *                                  the test classpath
     */
    public static List<String> loadCardFixtures() {
        return loadAsLines(BASELINE_INPUT_DIR + CARD_FIXTURE);
    }

    /**
     * Loads the 300-record daily transactions fixture as a list of fixed-width
     * lines.
     *
     * <p>Each returned string is exactly 350 ASCII characters wide, matching the
     * {@code CVTRA05Y.cpy} record layout (RECLN 350). The trailing line-feed is
     * stripped.
     *
     * @return immutable list of 300 fixed-width transaction records
     * @throws UncheckedIOException     when an I/O error occurs while reading
     * @throws IllegalArgumentException when {@code dailytran.txt} is missing from
     *                                  the test classpath
     */
    public static List<String> loadDailyTransactions() {
        return loadAsLines(BASELINE_INPUT_DIR + DAILY_TRANSACTIONS_FIXTURE);
    }

    // ================================================================
    // Generic baseline-input loader
    // ================================================================

    /**
     * Loads any canonical ASCII fixture under
     * {@code src/test/resources/baseline/input/} by simple filename.
     *
     * <p>This is the preferred call site for fixtures that do not have a dedicated
     * named loader (e.g., {@code "discgrp.txt"}, {@code "tcatbal.txt"},
     * {@code "trancatg.txt"}, {@code "trantype.txt"}, {@code "cardxref.txt"},
     * {@code "custdata.txt"}). For the three highest-traffic fixtures (account,
     * card, daily transactions) prefer the corresponding named loader for
     * readability.
     *
     * @param filename simple filename relative to {@code baseline/input/}
     *                 (no leading slash, no directory prefix) — for example
     *                 {@code "discgrp.txt"} or {@code "tcatbal.txt"}
     * @return immutable list of lines, each without trailing LF
     * @throws IllegalArgumentException when the resource cannot be located on the
     *                                  classpath (typically a typo in the
     *                                  filename or a missing fixture)
     * @throws UncheckedIOException     when an I/O error occurs while reading
     */
    public static List<String> loadAsLinesFromBaselineInput(String filename) {
        return loadAsLines(BASELINE_INPUT_DIR + filename);
    }

    // ================================================================
    // Low-level classpath loaders
    // ================================================================

    /**
     * Loads any classpath resource as a list of lines decoded as US-ASCII (each
     * line WITHOUT the trailing LF).
     *
     * <p>Use {@link #loadAsLinesFromBaselineInput(String)} as a more readable
     * call site when the resource is under {@code baseline/input/}. This method
     * exists for callers that need to load fixtures from arbitrary classpath
     * locations (e.g., expected-output goldens under {@code baseline/expected/}
     * or test-specific resources outside the canonical fixture directories).
     *
     * <p>Charset behaviour: the {@link StandardCharsets#US_ASCII} decoder is
     * strict — any byte outside {@code 0x00..0x7F} raises a
     * {@link java.nio.charset.MalformedInputException} wrapped as an
     * {@link UncheckedIOException}. This catches fixture corruption at load time
     * (canonical CardDemo fixtures are pure ASCII per AAP §0.4.4).
     *
     * @param classpathResource leading-slash classpath path (for example
     *                          {@code "/baseline/input/acctdata.txt"})
     * @return immutable list of lines; empty list if the resource is empty
     * @throws IllegalArgumentException when the resource cannot be located on
     *                                  the classpath
     * @throws UncheckedIOException     when an I/O error or character-decoding
     *                                  error occurs while reading
     */
    public static List<String> loadAsLines(String classpathResource) {
        try (InputStream in = openResource(classpathResource);
             InputStreamReader isr = new InputStreamReader(in, StandardCharsets.US_ASCII);
             BufferedReader reader = new BufferedReader(isr)) {
            final List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return Collections.unmodifiableList(lines);
        } catch (IOException ioe) {
            throw new UncheckedIOException(
                "Failed to read classpath resource: " + classpathResource, ioe);
        }
    }

    /**
     * Loads any classpath resource as a raw byte array, without charset decoding.
     *
     * <p>Useful for staging input files for Spring Batch jobs that read via
     * {@code FlatFileItemReader} — the {@code FlatFileItemReader} works on
     * filesystem paths rather than classpath resources, so batch ITs typically
     * call {@code loadAsBytes(...)} and write the result to a temporary file
     * before pointing the reader at it. Also useful for byte-level golden-file
     * comparisons performed by {@code BaselineDiffUtil}.
     *
     * <p>Returns a defensive copy of the file contents; the underlying input
     * stream is fully consumed and closed before the method returns.
     *
     * @param classpathResource leading-slash classpath path (for example
     *                          {@code "/baseline/input/tcatbal.txt"})
     * @return raw byte array containing the entire file contents
     * @throws IllegalArgumentException when the resource cannot be located on
     *                                  the classpath
     * @throws UncheckedIOException     when an I/O error occurs while reading
     */
    public static byte[] loadAsBytes(String classpathResource) {
        try (InputStream in = openResource(classpathResource);
             ByteArrayOutputStream buf = new ByteArrayOutputStream(IO_BUFFER_SIZE)) {
            final byte[] tmp = new byte[IO_BUFFER_SIZE];
            int n;
            while ((n = in.read(tmp)) >= 0) {
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException ioe) {
            throw new UncheckedIOException(
                "Failed to read classpath resource: " + classpathResource, ioe);
        }
    }

    // ================================================================
    // Edge-case CSV loader
    // ================================================================

    /**
     * Loads an edge-case CSV under {@code src/test/resources/fixtures/edge/} as
     * a list of cell-array rows.
     *
     * <p>The header row is the first element of the returned list. Test methods
     * typically iterate {@code rows.subList(1, rows.size())} to skip the header,
     * or use the loader to make assertions on header structure as well as data.
     * For JUnit 5's built-in {@code @CsvFileSource} integration, prefer
     * annotating the test method directly when the test method body operates on
     * a single row at a time — both approaches are valid; this loader is the
     * right choice when the test needs the entire table at once (for example
     * when asserting row count or doing cross-row aggregation).
     *
     * <h3>CSV parsing scope</h3>
     *
     * <p>The embedded parser handles:
     * <ul>
     *   <li>Comma-separated fields</li>
     *   <li>Double-quoted fields containing commas (e.g.,
     *       {@code "ACCOUNT, FROZEN"})</li>
     *   <li>Doubled quotes inside quoted fields ({@code ""} → {@code "})</li>
     *   <li>Trailing line-feed or CR-LF (stripped by {@link BufferedReader})</li>
     *   <li>Blank lines (skipped silently — common CSV convention)</li>
     * </ul>
     *
     * <p>It does NOT handle embedded newlines inside quoted fields — none of the
     * AAP-specified edge-case CSVs contain such content (AAP §0.10.2 Minimal
     * Change Clause keeps the parser to ~50 lines rather than pulling in
     * Apache Commons CSV or OpenCSV).
     *
     * @param csvName simple filename relative to {@code fixtures/edge/} (e.g.
     *                {@code "interest_halfeven_boundary.csv"} or
     *                {@code "posting_reject_codes.csv"})
     * @return immutable list of rows; first row is the header, remaining rows
     *         are data rows
     * @throws IllegalArgumentException when the CSV resource cannot be located
     * @throws UncheckedIOException     when an I/O error occurs while reading
     */
    public static List<String[]> loadEdgeCases(String csvName) {
        final List<String> rawLines = loadAsLines(EDGE_FIXTURES_DIR + csvName);
        final List<String[]> rows = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            if (line.isEmpty()) {
                continue; // skip blank lines (CSV convention)
            }
            rows.add(parseCsvLine(line));
        }
        return Collections.unmodifiableList(rows);
    }

    // ================================================================
    // Private helpers
    // ================================================================

    /**
     * Opens a classpath resource for reading. Throws
     * {@link IllegalArgumentException} with a clear, actionable message when the
     * resource cannot be located so test failures point to the missing fixture
     * rather than producing an opaque {@link NullPointerException} at the call
     * site.
     *
     * <p>Uses {@link Class#getResourceAsStream(String)} rather than
     * {@link ClassLoader#getResourceAsStream(String)} so the leading-slash form
     * resolves against the classpath root. Maven's Surefire and Failsafe plugins
     * place {@code src/test/resources/} at the classpath root, so
     * {@code "/baseline/input/acctdata.txt"} resolves to
     * {@code src/test/resources/baseline/input/acctdata.txt} at test time.
     *
     * @param classpathResource leading-slash classpath path
     * @return non-null {@link InputStream} positioned at the start of the
     *         resource
     * @throws IllegalArgumentException when the resource cannot be located
     */
    private static InputStream openResource(String classpathResource) {
        final InputStream in = FixtureLoader.class.getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalArgumentException(
                "Classpath resource not found: " + classpathResource
                    + ". Ensure the fixture exists under src/test/resources"
                    + classpathResource + ".");
        }
        return in;
    }

    /**
     * Parses a single CSV line into an array of cell values.
     *
     * <p>Supports comma separators, double-quoted fields with embedded commas,
     * and doubled quotes inside quoted fields. Does NOT support embedded newlines
     * (intentional — none of the AAP-specified fixtures require that complexity;
     * AAP §0.10.2 Minimal Change Clause keeps this parser self-contained without
     * a third-party dependency).
     *
     * <p>State-machine summary:
     * <ul>
     *   <li>Outside quotes: a comma terminates the current cell; an opening
     *       double-quote enters quoted mode (without contributing a character);
     *       any other character is appended.</li>
     *   <li>Inside quotes: a double-quote followed by another double-quote is an
     *       escaped quote (one quote contributes to the cell, the second is
     *       consumed); a lone double-quote exits quoted mode; any other
     *       character is appended verbatim (commas inside quotes are NOT
     *       separators).</li>
     * </ul>
     *
     * <p>The final cell is flushed after the loop completes, so a trailing comma
     * on the line produces an empty trailing cell (matching common CSV reader
     * behaviour).
     *
     * @param line a single CSV record without trailing LF/CRLF
     * @return array of cell values; never {@code null} and always contains at
     *         least one element (an empty input line yields {@code new String[]{""}})
     */
    private static String[] parseCsvLine(String line) {
        final List<String> cells = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        // Escaped double-quote inside a quoted field: append one
                        // quote to the current cell and skip the second.
                        current.append('"');
                        i++;
                    } else {
                        // Closing quote — leave quoted mode without contributing
                        // a character.
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == ',') {
                    cells.add(current.toString());
                    current.setLength(0);
                } else if (c == '"') {
                    // Opening quote — enter quoted mode without contributing a
                    // character.
                    inQuotes = true;
                } else {
                    current.append(c);
                }
            }
        }
        cells.add(current.toString());
        return cells.toArray(new String[0]);
    }
}
