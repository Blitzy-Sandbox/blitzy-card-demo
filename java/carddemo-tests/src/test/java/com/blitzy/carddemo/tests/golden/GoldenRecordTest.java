/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright Contributors to the CardDemo Project.
 */
package com.blitzy.carddemo.tests.golden;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for byte-for-byte golden-record parity tests.
 *
 * <p>This is the <strong>NON-NEGOTIABLE PR gate</strong> per AAP &sect;0.6.11.
 * Every COBOL program in {@code app/cbl/} has one concrete subclass in this
 * package that asserts the Java translation produces byte-identical output to
 * the COBOL baseline for the same input fixtures.</p>
 *
 * <h2>Subclass Contract</h2>
 * Concrete subclasses MUST override:
 * <ul>
 *   <li>{@link #programClass()} &mdash; the Java application/utility class
 *       under test (e.g.&nbsp;{@code CbAct01C.class})</li>
 *   <li>{@link #inputFile()} &mdash; the primary input fixture path</li>
 *   <li>{@link #expectedOutputFile()} &mdash; the primary expected
 *       (COBOL-captured) output file path</li>
 * </ul>
 *
 * <p>Concrete subclasses MAY override:</p>
 * <ul>
 *   <li>{@link #auxiliaryInputs()} &mdash; additional input fixtures (defaults
 *       to an empty {@code List})</li>
 *   <li>{@link #expectedOutputs()} &mdash; multiple expected-output declarations
 *       (defaults to a single-element list wrapping
 *       {@link #expectedOutputFile()})</li>
 *   <li>{@link #runProgram(Class, Path, List)} &mdash; custom orchestration
 *       (default throws {@link UnsupportedOperationException}, see Phase&nbsp;6
 *       of the agent prompt and AAP &sect;0.6.11)</li>
 * </ul>
 *
 * <h2>Test Invariant</h2>
 * <p>The single test method {@link #byteForByteParity()} asserts that EACH
 * declared {@link ExpectedOutput} is byte-identical to the actual output
 * produced by the Java translation. Failure blocks the PR per AAP
 * &sect;0.6.11.</p>
 *
 * <h2>Fixture Resolution</h2>
 * <p>Two helper methods route to the canonical fixture locations used by the
 * harness:</p>
 * <ul>
 *   <li>{@link #resolveAppDataPath(String)} &rarr;
 *       {@code <repo-root>/app/data/ASCII/<fileName>} (the 9 ASCII fixtures
 *       from AAP &sect;0.4.1, read directly from {@code app/} without copying
 *       per AAP &sect;0.6.11)</li>
 *   <li>{@link #resolveExpectedOutputPath(String, String)} &rarr;
 *       {@code <module-root>/src/test/resources/golden/<programDir>/expected/<fileName>}</li>
 * </ul>
 *
 * <h2>Initial {@code @Disabled} Scaffolding</h2>
 * <p>Per AAP &sect;0.6.11 ("Initial test scaffolding may use placeholder
 * expected files marked {@code @Disabled} until COBOL captures are
 * available"), concrete subclasses may override {@link #byteForByteParity()}
 * with {@code @Disabled} and a detailed reason citing AAP &sect;0.6.11 until
 * COBOL captures are committed. The harness skeleton is unconditionally
 * present so that the test suite always discovers and reports the per-program
 * tests.</p>
 *
 * <h2>Multi-Output Programs</h2>
 * <p>Programs whose COBOL counterparts produce multiple output files
 * (e.g.&nbsp;{@code CbTrn02CGoldenTest} with 5 outputs:
 * {@code transact.txt}, {@code acctdata.txt}, {@code tcatbal.txt},
 * {@code dalyrejs.txt}, {@code stdout.txt}; {@code CoActUpCGoldenTest} with
 * 4 outputs; {@code CbStm03AGoldenTest} with 3 outputs) override
 * {@link #expectedOutputs()} to return a list of {@link ExpectedOutput}
 * records. The base harness iterates and asserts byte parity for each
 * independently, reporting which output mismatched in the AssertJ
 * failure message via the {@code .as(...)} description.</p>
 *
 * <h2>Repository Root Discovery</h2>
 * <p>{@link #repositoryRoot()} ascends from {@code user.dir} until a directory
 * containing {@code app/data/ASCII} is found, making the tests runnable from
 * both Maven ({@code mvn -pl carddemo-tests test}) and an IDE
 * ({@code Right-click &rarr; Run}) without configuration.</p>
 *
 * <h2>Forbidden Constructs (per AAP)</h2>
 * <ul>
 *   <li>NO {@code java.io.File} (use {@link java.nio.file.Path}) per AAP
 *       &sect;0.6.5</li>
 *   <li>NO {@code java.util.Date} / {@code Calendar} (use
 *       {@code java.time.*}) per AAP &sect;0.6.4</li>
 *   <li>NO {@code ThreadLocal} (use {@code ScopedValue}) per AAP
 *       &sect;0.6.6</li>
 *   <li>NO {@code double} / {@code float} for monetary assertions per AAP
 *       &sect;0.6.1</li>
 *   <li>NO Spring / Spring Boot Test / Mockito-Spring / Testcontainers /
 *       LocalStack per AAP &sect;0.7.4</li>
 *   <li>NO preview features (JEP&nbsp;502, JEP&nbsp;505, JEP&nbsp;507,
 *       JEP&nbsp;512) per AAP &sect;0.7.4</li>
 *   <li>NO {@code --enable-preview} JVM flag per AAP &sect;0.7.4</li>
 *   <li>NO reflection ({@code Class.forName}, {@code Method.invoke},
 *       {@code Constructor.newInstance}) &mdash; the {@link
 *       #runProgram(Class, Path, List)} hook documentation directs
 *       implementers to use {@link java.lang.invoke.MethodHandles.Lookup
 *       MethodHandles.Lookup#findConstructor} instead</li>
 * </ul>
 *
 * <h2>Mandated Java 25 Finalized Features</h2>
 * <ul>
 *   <li>{@link java.nio.file.Path} + {@link Files#readAllBytes(Path)} for
 *       byte-for-byte parity (AAP &sect;0.6.5)</li>
 *   <li>{@code record ExpectedOutput(...)} (Java 16+, AAP &sect;0.3.2)</li>
 *   <li>Compact canonical constructor with validation (JEP&nbsp;513 Flexible
 *       Constructor Bodies, AAP &sect;0.6.7, &sect;0.6.3)</li>
 *   <li>AssertJ fluent assertions for rich failure diagnostics
 *       (AAP &sect;0.5.1)</li>
 * </ul>
 *
 * @see <a href="https://github.com/aws-samples/aws-mainframe-modernization-carddemo">AWS CardDemo</a>
 * @since 25
 */
public abstract class GoldenRecordTest {

    /**
     * Declaration of one expected-output file in a multi-output scenario.
     *
     * <p>Subclasses with multiple outputs (e.g.&nbsp;{@code CbTrn02CGoldenTest}
     * has 5 outputs: {@code transact.txt}, {@code acctdata.txt},
     * {@code tcatbal.txt}, {@code dalyrejs.txt}, {@code stdout.txt}) override
     * {@link GoldenRecordTest#expectedOutputs()} to return a list of these
     * records.</p>
     *
     * <p>The base harness uses {@link #name()} <em>only</em> for AssertJ
     * description text (the {@code .as(...)} clause in
     * {@link GoldenRecordTest#byteForByteParity()}) and as the map key looked
     * up against the result of
     * {@link GoldenRecordTest#runProgram(Class, Path, List)}. It is NOT used
     * to resolve a file path; the {@link #path()} field carries the absolute
     * filesystem path of the expected (COBOL-captured) output.</p>
     *
     * @param name a short identifier for the output
     *             (e.g.&nbsp;{@code "stdout.txt"},
     *             {@code "transact.txt"}); used only for assertion messages
     *             and {@code Map} lookup of the corresponding actual-output
     *             path returned by {@code runProgram(...)}.
     *             Must be non-{@code null} and non-blank.
     * @param path the expected (COBOL-captured) output file path; the test
     *             harness reads this via {@link Files#readAllBytes(Path)} and
     *             asserts byte-identical equality against the Java-produced
     *             actual output. Must be non-{@code null}.
     */
    protected record ExpectedOutput(String name, Path path) {

        /**
         * Compact canonical constructor that validates non-{@code null},
         * non-blank fields.
         *
         * <p>Per AAP &sect;0.6.7 and &sect;0.6.3, JEP&nbsp;513 Flexible
         * Constructor Bodies (finalized in Java&nbsp;25) permits validation
         * before field assignment. Records use the compact constructor form
         * to express this idiomatically.</p>
         *
         * @throws IllegalArgumentException if {@code name} is {@code null} or
         *                                  blank, or if {@code path} is
         *                                  {@code null}
         */
        public ExpectedOutput {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                    "ExpectedOutput.name must be non-blank");
            }
            if (path == null) {
                throw new IllegalArgumentException(
                    "ExpectedOutput.path must be non-null");
            }
        }
    }

    /**
     * Returns the Java application class under test
     * (e.g.&nbsp;{@code CbAct01C.class}).
     *
     * <p>The class is instantiated by the test harness via reflection-free
     * constructor introspection (see {@link #runProgram(Class, Path, List)}
     * documentation) and its primary entry method is invoked.</p>
     *
     * @return the {@code Class<?>} reference; never {@code null}
     */
    protected abstract Class<?> programClass();

    /**
     * Returns the primary input fixture path.
     *
     * <p>For tests that read directly from {@code app/data/ASCII/} (per AAP
     * &sect;0.4.1, the 9 immutable ASCII fixtures), use
     * {@link #resolveAppDataPath(String)}. For tests with synthesized inputs
     * captured under {@code src/test/resources/golden/}, use
     * {@link #resolveExpectedOutputPath(String, String)} (the same routine
     * resolves both expected outputs and ancillary scenario input files
     * because they share the per-program {@code golden/<programDir>/}
     * subtree).</p>
     *
     * @return absolute {@link Path}; never {@code null}
     */
    protected abstract Path inputFile();

    /**
     * Returns the primary expected (COBOL-captured) output file path.
     *
     * <p>Typically constructed via
     * {@link #resolveExpectedOutputPath(String, String)} pointing to
     * {@code <module-root>/src/test/resources/golden/<programDir>/expected/<file>}.</p>
     *
     * @return absolute {@link Path}; never {@code null}
     */
    protected abstract Path expectedOutputFile();

    /**
     * Returns additional input fixtures beyond {@link #inputFile()}.
     *
     * <p>For multi-input scenarios like {@code CbTrn02CGoldenTest} (which
     * reads dailytran, cardxref, acctdata, tcatbal), override this method to
     * return all auxiliary inputs. The default returns an empty list.</p>
     *
     * <p>The returned list is passed verbatim as the third argument to
     * {@link #runProgram(Class, Path, List)}. Subclasses are responsible for
     * preserving deterministic ordering when relevant.</p>
     *
     * @return immutable list of auxiliary {@link Path}s; never {@code null};
     *         default empty
     */
    protected List<Path> auxiliaryInputs() {
        return List.of();
    }

    /**
     * Returns the list of expected-output declarations for byte-for-byte
     * comparison.
     *
     * <p>Subclasses with multiple outputs (per AAP &sect;0.6.11, e.g.
     * {@code CbTrn02CGoldenTest} has 5 outputs, {@code CoActUpCGoldenTest}
     * has 4, {@code CbStm03AGoldenTest} has 3) override this method. The
     * default returns a single {@link ExpectedOutput} wrapping
     * {@link #expectedOutputFile()} with name {@code "expected"}.</p>
     *
     * <p>The harness iterates this list in order and asserts byte parity for
     * each output independently. The AssertJ failure message identifies which
     * specific output mismatched in multi-output scenarios.</p>
     *
     * @return immutable list of {@link ExpectedOutput}; never {@code null};
     *         default single-element list
     */
    protected List<ExpectedOutput> expectedOutputs() {
        return List.of(new ExpectedOutput("expected", expectedOutputFile()));
    }

    /**
     * Resolves a file name to an absolute path under {@code app/data/ASCII/}.
     *
     * <p>Per AAP &sect;0.4.1 and &sect;0.6.11, the 9 ASCII fixtures
     * ({@code acctdata.txt}, {@code carddata.txt}, {@code cardxref.txt},
     * {@code custdata.txt}, {@code dailytran.txt}, {@code discgrp.txt},
     * {@code tcatbal.txt}, {@code trancatg.txt}, {@code trantype.txt}) are
     * read directly from {@code app/} via a path relative to the repository
     * root &mdash; they are NOT copied into {@code java/}.</p>
     *
     * <p>The repository root is located by ascending from the test working
     * directory (typically {@code java/carddemo-tests/} when launched by
     * Maven, or any descendant when launched by an IDE) until an
     * {@code app/data/ASCII} directory is found.</p>
     *
     * <p><strong>Path-traversal guard (CWE-22)</strong>: {@code fileName}
     * must be a simple file name with no separators and no
     * {@code ".."} segments. The resolved path is normalised and
     * verified to remain underneath {@code <repo-root>/app/data/ASCII}
     * before being returned. This blocks any future subclass from
     * accidentally escaping the fixture sandbox via a hostile
     * {@code fileName} such as {@code "../../etc/passwd"}.</p>
     *
     * @param fileName the simple file name (e.g.&nbsp;{@code "acctdata.txt"})
     * @return absolute {@link Path} to
     *         {@code <repo-root>/app/data/ASCII/<fileName>}
     * @throws IllegalArgumentException if {@code fileName} contains a
     *         path separator, is {@code ".."}, or otherwise resolves
     *         to a location outside the ASCII fixtures directory
     */
    protected Path resolveAppDataPath(String fileName) {
        requireSimpleFileName(fileName, "fileName");
        Path base = repositoryRoot()
            .resolve("app")
            .resolve("data")
            .resolve("ASCII")
            .normalize()
            .toAbsolutePath();
        Path resolved = base.resolve(fileName).normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                "fileName '" + fileName + "' resolves outside the ASCII "
                    + "fixtures directory (" + base + "); resolved to "
                    + resolved);
        }
        return resolved;
    }

    /**
     * Resolves a program-scoped expected-output (or scenario input) file path
     * under {@code <module-root>/src/test/resources/golden/<programDir>/expected/}.
     *
     * <p>The same routine is used both for COBOL-captured expected outputs
     * (per AAP &sect;0.6.11) and for any synthesized scenario inputs that
     * subclasses choose to commit alongside their expected outputs (e.g. an
     * {@code input_scenario.txt} for an online-CICS test that injects a
     * synthetic BMS event stream).</p>
     *
     * <p><strong>Path-traversal guard (CWE-22)</strong>: both
     * {@code programDir} and {@code fileName} must be simple names
     * (no separators, no {@code ".."}). The resolved path is
     * normalised and verified to remain underneath
     * {@code <module-root>/src/test/resources/golden} before being
     * returned. This blocks any future subclass from reading or
     * writing files outside the golden fixture tree via a hostile
     * argument such as {@code programDir = "../../../etc"}.</p>
     *
     * @param programDir the program directory name
     *                   (e.g.&nbsp;{@code "cbact01c"}, {@code "cbtrn02c"});
     *                   must be lowercase to match the
     *                   {@code src/test/resources/golden/<dir>/} convention
     * @param fileName the simple file name
     *                 (e.g.&nbsp;{@code "stdout.txt"},
     *                 {@code "input_scenario.txt"})
     * @return absolute {@link Path} to
     *         {@code <module-root>/src/test/resources/golden/<programDir>/expected/<fileName>}
     * @throws IllegalArgumentException if either {@code programDir} or
     *         {@code fileName} contains a path separator, equals
     *         {@code ".."}, or otherwise resolves to a location
     *         outside the golden fixture tree
     */
    protected Path resolveExpectedOutputPath(String programDir, String fileName) {
        requireSimpleFileName(programDir, "programDir");
        requireSimpleFileName(fileName, "fileName");
        Path base = moduleRoot()
            .resolve("src")
            .resolve("test")
            .resolve("resources")
            .resolve("golden")
            .normalize()
            .toAbsolutePath();
        Path resolved = base
            .resolve(programDir)
            .resolve("expected")
            .resolve(fileName)
            .normalize()
            .toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                "programDir/fileName resolve outside the golden fixture "
                    + "tree (" + base + "); programDir='" + programDir
                    + "', fileName='" + fileName + "', resolved=" + resolved);
        }
        return resolved;
    }

    /**
     * Validates that a caller-supplied path component is a simple
     * relative name: non-null, non-empty, free of any path separator
     * (either {@code '/'} or {@code '\\'}), and not equal to
     * {@code "."} or {@code ".."}. Used by both
     * {@link #resolveAppDataPath(String)} and
     * {@link #resolveExpectedOutputPath(String, String)} to block
     * CWE-22 path traversal before the resolved path is even
     * computed (a defence-in-depth layer in addition to the
     * {@code normalize() + startsWith(base)} containment check).
     *
     * @param value the candidate component
     * @param paramName the caller's parameter name, used only in the
     *                  exception message for diagnostic clarity
     * @throws IllegalArgumentException if {@code value} is not a
     *         simple relative file name
     */
    private static void requireSimpleFileName(String value, String paramName) {
        if (value == null) {
            throw new IllegalArgumentException(paramName + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(paramName + " must not be empty");
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                paramName + " must not contain path separators; got '"
                    + value + "'");
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(
                paramName + " must not be '.' or '..'; got '" + value + "'");
        }
    }

    /**
     * Runs the program under test and asserts byte-for-byte parity against
     * every declared {@link ExpectedOutput}.
     *
     * <p>This is THE non-negotiable PR gate test method per AAP &sect;0.6.11
     * ("These are non-negotiable and run on every PR"). All 28 concrete
     * subclasses inherit this method.</p>
     *
     * <p>Concrete subclasses MAY override this method to add a
     * {@code @Disabled} annotation (with a detailed reason citing AAP
     * &sect;0.6.11) until COBOL captures are committed, per AAP &sect;0.6.11
     * ("Initial test scaffolding may use placeholder expected files marked
     * {@code @Disabled} until COBOL captures are available"). Once a
     * program's COBOL output is captured and committed under
     * {@code src/test/resources/golden/<program>/expected/}, the
     * {@code @Disabled} annotation is removed in the same PR.</p>
     *
     * <p>The harness performs these steps:</p>
     * <ol>
     *   <li>Resolves the {@link #expectedOutputs()} list. If empty, throws
     *       {@link IllegalStateException} to signal a malformed subclass.</li>
     *   <li>Runs the program via
     *       {@link #runProgram(Class, Path, List)}, receiving a mapping from
     *       output name (matching {@link ExpectedOutput#name()}) to the
     *       actual-output file path.</li>
     *   <li>For each declared {@link ExpectedOutput}, looks up the
     *       corresponding actual-output path in the returned map. A missing
     *       entry triggers an {@link AssertionError} identifying the missing
     *       output name.</li>
     *   <li>Reads {@code expected.path()} and the actual path via
     *       {@link Files#readAllBytes(Path)} (per AAP &sect;0.6.5 mandating
     *       {@code java.nio.file}; {@code java.io.File} is FORBIDDEN).</li>
     *   <li>Asserts byte-array equality using AssertJ
     *       {@code assertThat(actual).isEqualTo(expected)} with a descriptive
     *       {@code .as(...)} clause identifying the specific output that
     *       mismatched in multi-output scenarios.</li>
     * </ol>
     *
     * @throws IOException if any file read fails (declared for
     *                     {@link Files#readAllBytes(Path)} clarity even
     *                     though it is subsumed by the broader
     *                     {@code throws Exception})
     * @throws Exception   if the program under test, or the
     *                     {@link #runProgram(Class, Path, List)} hook, throws
     *                     any exception type during orchestration
     */
    @Test
    public void byteForByteParity() throws Exception {
        List<ExpectedOutput> outputs = expectedOutputs();
        if (outputs.isEmpty()) {
            throw new IllegalStateException(
                "Subclass " + getClass().getSimpleName()
                    + " declared no expected outputs; override expectedOutputs() to "
                    + "return at least one ExpectedOutput.");
        }
        java.util.Map<String, Path> actualByName =
            runProgram(programClass(), inputFile(), auxiliaryInputs());
        if (actualByName == null) {
            throw new AssertionError(
                "runProgram() returned null for "
                    + programClass().getSimpleName()
                    + "; it must return a non-null map of output-name -> actual-output path.");
        }
        for (ExpectedOutput expected : outputs) {
            Path actualPath = actualByName.get(expected.name());
            if (actualPath == null) {
                throw new AssertionError(
                    "runProgram() did not produce an output named '"
                        + expected.name()
                        + "' for "
                        + programClass().getSimpleName()
                        + "; declared output names: "
                        + actualByName.keySet());
            }
            byte[] expectedBytes = Files.readAllBytes(expected.path());
            byte[] actualBytes = Files.readAllBytes(actualPath);

            // AAP §0.6.11 structured-record diff hook: if the subclass
            // declared any MaskedRange for this output (e.g. for embedded
            // timestamps in report files that legitimately vary), zero
            // those byte ranges in both buffers before comparison. Strict
            // byte equality is preserved for every other byte. The default
            // hook returns an empty list, so subclasses that did not
            // override maskedRanges() continue to receive exact byte
            // equality (the non-negotiable PR gate behaviour).
            List<MaskedRange> masks = maskedRanges(expected.name());
            if (masks != null && !masks.isEmpty()) {
                expectedBytes = applyMasks(expectedBytes, masks);
                actualBytes = applyMasks(actualBytes, masks);
            }

            assertThat(actualBytes)
                .as("Byte-for-byte parity for output '%s' (program: %s)",
                    expected.name(), programClass().getSimpleName())
                .isEqualTo(expectedBytes);
        }
    }

    /**
     * Declares zero or more byte ranges that should be masked (replaced
     * with {@code 0x00}) in both the expected and the actual output
     * before byte equality is asserted. Returning an empty list (the
     * default) keeps {@link #byteForByteParity()} in its strictest mode:
     * every single byte of every single output must match the COBOL
     * capture exactly.
     *
     * <p><strong>When to override this method</strong>: per AAP &sect;0.6.11
     * "where fixed-width byte equality is insufficient (e.g., for report
     * files with embedded timestamps that legitimately vary), the harness
     * also provides a field-by-field comparison mode that masks
     * known-variable fields." A canonical example is a report header
     * carrying a print timestamp such as {@code "PRINT TIME: 14:32:07"}
     * that varies every run; mask the 8 bytes at the timestamp offset
     * rather than disabling the entire test.
     *
     * <p><strong>What this hook does NOT do</strong>: it does NOT
     * implement field-aware comparison (parsing a record layout and
     * comparing field-by-field with type-aware predicates); it implements
     * byte-range masking, which is sufficient for the AAP §0.6.11
     * use case (suppressing variable substrings) and avoids dragging in
     * a record-layout descriptor language. If a future requirement
     * genuinely needs field-aware diff (e.g. masking a BigDecimal whose
     * encoding length depends on the value), introduce a separate hook
     * that returns a higher-level comparator and document the rationale
     * in {@code MIGRATION_NOTES.md}.
     *
     * <p><strong>Mask semantics</strong>:
     * <ul>
     *   <li>The hook returns a {@link List} of {@link MaskedRange}; each
     *       range covers {@code [offset, offset + length)} (half-open
     *       interval).</li>
     *   <li>Both the expected and the actual byte arrays are mutated in
     *       parallel before the AssertJ {@code isEqualTo} call: every
     *       byte in any declared range is overwritten with {@code 0x00}
     *       in both arrays, so a mismatch in any other position is still
     *       detected.</li>
     *   <li>Ranges that overlap, are out of order, or extend past
     *       end-of-buffer are tolerated (clamped to buffer bounds; the
     *       overlap simply zeros the same byte twice).</li>
     *   <li>Returning {@code null} is treated as an empty list (no
     *       masking).</li>
     * </ul>
     *
     * <p><strong>Default behaviour</strong>: returns an immutable empty
     * list. Subclasses that need masking override and return their own
     * list. The hook is called once per {@link ExpectedOutput} in
     * {@link #byteForByteParity()}; the {@code outputName} argument lets
     * a single subclass declare different masks for different outputs
     * (e.g. a {@code "report.txt"} output may have masked timestamps
     * while a {@code "audit.txt"} output is fully byte-equal).
     *
     * @param outputName the {@link ExpectedOutput#name()} of the output
     *                   currently being compared; never {@code null}
     * @return list of byte ranges to mask in both expected and actual
     *         bytes for this output; never {@code null} (return
     *         {@link Collections#emptyList()} to opt out of masking)
     */
    protected List<MaskedRange> maskedRanges(String outputName) {
        return Collections.emptyList();
    }

    /**
     * Applies the supplied byte-range masks to a copy of {@code buffer}
     * and returns the masked copy. Every byte in any declared range is
     * overwritten with {@code 0x00}. Ranges are clamped to
     * {@code [0, buffer.length)} so overlong masks do not throw
     * {@link ArrayIndexOutOfBoundsException}; this lets the harness
     * tolerate masks declared for the COBOL capture length when the
     * Java output happens to be shorter (a real mismatch is still
     * detected by the subsequent length comparison inside
     * {@code isEqualTo}).
     *
     * <p>This helper deliberately returns a new buffer rather than
     * mutating the input so that subsequent failure messages and AssertJ
     * descriptions can still reference the original (unmasked) bytes
     * via the local variables in {@link #byteForByteParity()} if
     * needed in a future revision (e.g., reporting the first masked
     * byte's original value for diagnostics).
     *
     * @param buffer the bytes to mask
     * @param masks  the byte ranges to overwrite with {@code 0x00}
     * @return a masked copy of {@code buffer}; never the same array
     */
    private static byte[] applyMasks(byte[] buffer, List<MaskedRange> masks) {
        byte[] copy = Arrays.copyOf(buffer, buffer.length);
        for (MaskedRange m : masks) {
            int start = Math.max(0, m.offset());
            int end = Math.min(copy.length, m.offset() + m.length());
            for (int i = start; i < end; i++) {
                copy[i] = 0;
            }
        }
        return copy;
    }

    /**
     * A half-open byte range {@code [offset, offset + length)} to be
     * zeroed in both expected and actual outputs before byte equality
     * is asserted by {@link #byteForByteParity()}. Used by subclasses
     * to mask known-variable bytes (e.g. embedded run timestamps,
     * generated batch run IDs, environment-specific paths) without
     * disabling the entire byte-for-byte parity contract.
     *
     * <p>Construction validates that {@code offset >= 0} and
     * {@code length >= 0} via the compact constructor (JEP 513
     * Flexible Constructor Bodies); a {@code length} of {@code 0} is
     * legal but a no-op (declares an empty range).
     *
     * <p><strong>Example</strong>: a 14-byte ISO-8601 timestamp at
     * offset 1024 of a report file is masked via
     * {@code List.of(new MaskedRange(1024, 14))}.
     *
     * @param offset the starting byte offset (inclusive); must be
     *               non-negative
     * @param length the number of bytes in the range; must be
     *               non-negative
     */
    public record MaskedRange(int offset, int length) {
        /**
         * Compact constructor validating non-negative bounds. JEP 513
         * Flexible Constructor Bodies permits the validation to run
         * before the canonical field bindings, matching the AAP
         * &sect;0.6.3 COBOL-style "validate before bind" pattern.
         */
        public MaskedRange {
            if (offset < 0) {
                throw new IllegalArgumentException(
                    "MaskedRange offset must be >= 0; got " + offset);
            }
            if (length < 0) {
                throw new IllegalArgumentException(
                    "MaskedRange length must be >= 0; got " + length);
            }
        }
    }

    /**
     * Runs the program under test and returns a mapping from output name to
     * the actual-output file path.
     *
     * <p><strong>Subclasses MAY override</strong> this method to provide
     * custom orchestration (e.g.&nbsp;scenario-driven multi-call dispatch,
     * BMS event injection for online-CICS tests, or
     * {@code ScopedValue<BatchRunContext>} binding for batch tests per AAP
     * &sect;0.6.6).</p>
     *
     * <p>The default implementation throws
     * {@link UnsupportedOperationException} with an instructive message
     * &mdash; concrete subclasses must EITHER (a) override this hook with a
     * working implementation, OR (b) keep their {@link #byteForByteParity()}
     * {@code @Disabled} until both the COBOL capture and the orchestration
     * logic are in place. This deliberate two-step approach ensures the
     * harness skeleton is present (JUnit discovers each per-program test and
     * reports it in CI) while preventing false failures during the
     * COBOL-capture lead time.</p>
     *
     * <p>A future revision will provide a default implementation that:</p>
     * <ol>
     *   <li>Discovers file-based adapter implementations (e.g.&nbsp;
     *       {@code FileAccountRepository}, {@code FileCardRepository},
     *       {@code FileCardXrefRepository}, {@code FileCustomerRepository},
     *       {@code FileTransactionRepository},
     *       {@code FileDailyTransactionRepository},
     *       {@code FileTransactionCategoryBalanceRepository},
     *       {@code FileDiscountGroupRepository},
     *       {@code FileTransactionTypeRepository},
     *       {@code FileTransactionCategoryRepository},
     *       {@code FileUserSecurityRepository}) from the
     *       {@code carddemo-adapter-file} module.</li>
     *   <li>Inspects the program class's primary public constructor and
     *       matches parameter types to instantiated adapters using
     *       {@link java.lang.invoke.MethodHandles.Lookup#findConstructor
     *       MethodHandles.Lookup#findConstructor} (per AAP &sect;0.7.4
     *       forbidding reflection, this hook uses {@code MethodHandles},
     *       NOT {@code Class.getDeclaredConstructors()} +
     *       {@code Constructor.newInstance()}).</li>
     *   <li>Establishes {@code ScopedValue<BatchRunContext>} bindings for
     *       batch tests per AAP &sect;0.6.6 (NEVER {@code ThreadLocal}).</li>
     *   <li>Captures SLF4J output via a Logback file appender to a temporary
     *       file named {@code "stdout.txt"} in the returned map.</li>
     *   <li>Invokes the program's primary entry method
     *       (e.g.&nbsp;{@code run()} for batch programs,
     *       {@code handle(input)} for online programs).</li>
     *   <li>Returns a map of output name to output path
     *       (e.g.&nbsp;{@code "stdout.txt"} &rarr; {@code tempStdoutPath},
     *       {@code "transact.txt"} &rarr;
     *       {@code /tmp/run-001/transact.txt}).</li>
     * </ol>
     *
     * <p><em>Structured-record diff hook</em>: per AAP &sect;0.6.11,
     * subclasses can override {@link #maskedRanges(String)} to declare
     * byte ranges that should be zeroed in both expected and actual
     * outputs before byte equality is asserted (for example, to mask
     * embedded run timestamps in report files that legitimately vary).
     * The default implementation returns an empty list, so all 28
     * subclasses inherit strict byte equality unless they explicitly
     * opt in to masking. See {@link #maskedRanges(String)} and
     * {@link MaskedRange} for details.</p>
     *
     * @param programClass    the class under test (passed verbatim from
     *                        {@link #programClass()})
     * @param inputFile       the primary input fixture path (passed verbatim
     *                        from {@link #inputFile()})
     * @param auxiliaryInputs additional input fixture paths (passed verbatim
     *                        from {@link #auxiliaryInputs()})
     * @return mapping from output name (as declared in
     *         {@link ExpectedOutput#name()}) to the actual-output file path
     * @throws Exception if any step of orchestration fails
     */
    protected java.util.Map<String, Path> runProgram(
            Class<?> programClass,
            Path inputFile,
            List<Path> auxiliaryInputs) throws Exception {
        throw new UnsupportedOperationException(
            "Default runProgram() orchestration is intentionally not implemented in "
                + "GoldenRecordTest base class. Concrete subclasses must keep "
                + "byteForByteParity() @Disabled until either (a) this hook is given "
                + "a default implementation in a follow-up revision, OR (b) the "
                + "subclass overrides this hook with program-specific wiring. See "
                + "AAP \u00a70.6.11 and java/MIGRATION_NOTES.md for the COBOL "
                + "capture procedure. Program: "
                + (programClass == null ? "<null>" : programClass.getName()));
    }

    /**
     * Returns the repository root (the parent of the {@code java/} directory).
     *
     * <p>Ascends from {@code user.dir} until a directory containing
     * {@code app/data/ASCII} is found. This makes the tests runnable from
     * both Maven ({@code mvn -pl carddemo-tests test}, where {@code user.dir}
     * is typically {@code <repo-root>/java/carddemo-tests}) and an IDE
     * ({@code Right-click &rarr; Run}, where {@code user.dir} may be any
     * descendant of the repository root) without configuration.</p>
     *
     * @return absolute {@link Path} to the repository root containing
     *         {@code app/data/ASCII}
     * @throws IllegalStateException if no ancestor of {@code user.dir}
     *                               contains an {@code app/data/ASCII}
     *                               directory
     */
    private Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("app").resolve("data").resolve("ASCII"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
            "Unable to locate repository root containing app/data/ASCII; user.dir="
                + System.getProperty("user.dir"));
    }

    /**
     * Returns the {@code carddemo-tests} module root (where
     * {@code src/test/resources} lives).
     *
     * <p>Ascends from {@code user.dir} until a directory containing
     * {@code src/test/resources} is found. Falls back to
     * {@code <repo-root>/java/carddemo-tests} if no such ancestor is located,
     * preserving best-effort behavior for unusual launch configurations.</p>
     *
     * @return absolute {@link Path} to the carddemo-tests module root
     */
    private Path moduleRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("src").resolve("test").resolve("resources"))) {
                return current;
            }
            current = current.getParent();
        }
        // Fallback: assume carddemo-tests sits under <repo-root>/java/.
        return repositoryRoot().resolve("java").resolve("carddemo-tests");
    }
}
