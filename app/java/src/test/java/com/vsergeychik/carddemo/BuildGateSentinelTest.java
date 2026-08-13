package com.vsergeychik.carddemo;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The build gate's sentinel: it proves that the suite the build claims to have run is actually the
 * suite this module contains.
 *
 * <h2>The failure mode this exists to close</h2>
 * <p>{@code mvn clean verify} is the single documented gating command, and its two halves can stand
 * down together and still report success. maven-surefire-plugin is content to find no test at all
 * unless {@code failIfNoTests} says otherwise; jacoco-maven-plugin's {@code check} goal
 * <em>skips its rules outright</em> when no execution data file was produced, which is precisely
 * what happens when nothing ran. A mis-scoped {@code <includes>}, a broken discovery configuration,
 * a renamed source root or a test-compile failure that was swallowed therefore yields a green build
 * over zero evidence - and for a parity gate stated as "diff count = 0", a suite that never ran
 * reports exactly the number the gate wants to see.
 *
 * <p>{@code failIfNoTests=true} in {@code app/java/pom.xml} closes the total case: <em>no</em> tests
 * fails the build. This class closes the partial case, which is the one that actually happens: the
 * suite ran, but it was a fraction of the module. The two are complementary and neither substitutes
 * for the other.
 *
 * <h2>How it checks, and why this way</h2>
 * <p>It enumerates the compiled {@code *Test.class} files in the module's own test output directory,
 * located through this class's own {@link URL} rather than through the process working directory, so
 * the check is hermetic and does not care where the build was launched from. Two assertions follow, and
 * <strong>neither is a count</strong>:
 * <ul>
 *   <li><strong>One gate per program that ships cases.</strong> The required set of parity suites is
 *       <em>generated</em> from the shipped corpus at {@code src/test/resources/parity/}: every program
 *       directory there must have a matching {@code <PROGRAM>ParityTest} in the build output. A
 *       hand-maintained list names what somebody remembered; a generated one cannot fall behind the
 *       corpus it is derived from, so adding a program is one directory and one class rather than a
 *       third edit somewhere else.</li>
 *   <li><strong>The named suites that carry the acceptance gates.</strong> The parity contract and the
 *       deterministic judge that computes the diff count, and the four foundational suites that pin
 *       numeric truncation, fixed-width byte layout, the IBM-supplied AID constants and the date
 *       utility's 80-byte contract. These carry gates that no parity case can carry for them.</li>
 * </ul>
 *
 * <h2>What this half cannot prove, and where the other half is</h2>
 * <p>This class runs <em>inside</em> the test run, so the only thing it can inspect is the build
 * output - which means it proves the suite was <strong>compiled</strong>. Compilation is not execution.
 * A suite can compile and never run, and then the parity gate reports the diff count of zero it wants
 * to see because nothing computed one. That claim needs the runner's own record of what it executed,
 * and that record is only complete after the run finishes, so it is checked in
 * {@link ExecutedSuiteInventoryVerification} - a second execution of the same surefire plugin, bound to
 * a later phase. The two are complementary: this one proves the module CONTAINS its suite, that one
 * proves the suite RAN. It used to be a floor of sixty compiled classes against the hundred and
 * ninety-four that exist, which proved neither.
 *
 * <p>Nothing here reads the wall clock, opens a network connection or writes a file, and there is no
 * static mutable state: the inventories are recomputed per test method from the build output.
 */
@DisplayName("Build gate sentinel - the suite that ran is the suite this module contains")
class BuildGateSentinelTest {

    /** The suffix maven-surefire-plugin's default includes match on. */
    private static final String TEST_CLASS_SUFFIX = "Test.class";

    /** The suffix of a parity gate class, one per migrated COBOL program. */
    private static final String PARITY_TEST_SUFFIX = "ParityTest";

    /** The package the parity gates live in. */
    private static final String PARITY_PACKAGE = "com.vsergeychik.carddemo.parity.";

    /** The classpath resource directory holding one sub-directory of cases per program. */
    private static final String PARITY_RESOURCE_ROOT = "parity";

    /**
     * The one compiled {@code *ParityTest} that names no COBOL program.
     *
     * <p>{@code FieldDifferByteParityTest} is the differ's own byte-level self-test - it drives the judge
     * against planted differences rather than a translated program - so it has no case directory and must
     * not be required to have one.
     */
    private static final String NON_PROGRAM_PARITY_SUITE = "FieldDifferByte";

    /**
     * The suites whose absence would empty the acceptance gates of meaning, named as fully qualified
     * class names.
     *
     * <p>Each earns its place by the gate it carries, not by being important in general:
     * {@code ParityCaseTest} and {@code FieldDifferTest} are the only checks on the case contract
     * and on the deterministic judge that computes the diff count the module gate is stated in;
     * {@code CobolDecimalTest} is the only check that rounding truncates toward zero rather than
     * half-rounding; {@code FixedWidthCodecTest} is the only check on the byte-level pad, fill and
     * sign-overpunch rules every record layout rests on; {@code CicsAidTest} pins the AID constants
     * reproduced from IBM documentation because the copybook is absent from this repository; and
     * {@code DateUtilityJobTest} pins the exact 80-byte composed message of the called date
     * subprogram.
     *
     * <p>The list grows as the module does. It names the gate-carrying suites that exist, so adding
     * an entry is part of adding such a suite rather than an afterthought - and an entry naming a
     * suite that does not exist yet would fail the build for a reason no one can act on.
     */
    private static final List<String> REQUIRED_SUITES = List.of(
        "com.vsergeychik.carddemo.parity.ParityCaseTest",
        "com.vsergeychik.carddemo.parity.FieldDifferTest",
        "com.vsergeychik.carddemo.common.CobolDecimalTest",
        "com.vsergeychik.carddemo.common.FixedWidthCodecTest",
        "com.vsergeychik.carddemo.common.CicsAidTest",
        "com.vsergeychik.carddemo.util.DateUtilityJobTest");

    @Nested
    @DisplayName("One gate per program that ships cases")
    class GatePerProgram {

        @Test
        @DisplayName("every program shipping a case directory has a compiled parity gate")
        void everyShippedProgramHasACompiledGate() {
            List<String> shipped = programsShippingCases();
            List<String> compiled = compiledParityGates();

            Assertions.assertThat(shipped)
                .as("the required set is GENERATED from the %s resource directory rather than "
                    + "hand-listed, so it cannot fall behind the corpus. An empty list means the "
                    + "corpus is not on the classpath, at which point this proves nothing.",
                    PARITY_RESOURCE_ROOT)
                .isNotEmpty();
            Assertions.assertThat(compiled)
                .as("one gate per program that ships cases. A case directory with no gate is twenty "
                    + "files that read in review as part of the acceptance criteria and execute "
                    + "nothing - which is worse than an absent directory, because it looks covered. "
                    + "%d programs ship cases; %d parity gates compiled.",
                    shipped.size(), compiled.size())
                .containsAll(shipped);
        }

        @Test
        @DisplayName("every compiled parity gate belongs to a program that ships cases")
        void everyCompiledGateHasAShippedProgram() {
            List<String> shipped = programsShippingCases();
            List<String> orphans = compiledParityGates().stream()
                .filter(name -> !shipped.contains(name))
                .toList();

            Assertions.assertThat(orphans)
                .as("a gate whose program ships no cases fails at load time - ParityHarness.casesOf "
                    + "refuses anything but exactly case01 to case20 - which is a worse diagnostic "
                    + "than this one. %s is the single permitted exception: it is the differ's own "
                    + "byte-level self-test and names no COBOL program.", NON_PROGRAM_PARITY_SUITE)
                .containsExactly(NON_PROGRAM_PARITY_SUITE);
        }

        @Test
        @DisplayName("the test output directory is not empty, which failIfNoTests alone would miss")
        void testOutputDirectoryIsPopulated() {
            Assertions.assertThat(discoveredTestClasses())
                .as("a populated test output directory is the precondition for every other gate: "
                    + "jacoco:check SKIPS its rules when no execution data exists, so an empty "
                    + "suite would satisfy the 0.90 branch rule by never producing evidence")
                .isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Suites the acceptance gates depend on")
    class RequiredSuites {

        @Test
        @DisplayName("every named gate-carrying suite is present in the build output")
        void everyRequiredSuiteIsPresent() {
            List<String> discovered = discoveredTestClasses();

            Assertions.assertThat(discovered)
                .as("the suites that carry the acceptance gates. A count check alone can be met by "
                    + "any set of classes, so these are named: without ParityCaseTest and "
                    + "FieldDifferTest the diff-count gate has no check of its own, and without the "
                    + "four foundational suites nothing pins truncation toward zero, the "
                    + "fixed-width byte rules, the IBM-supplied AID constants or the 80-byte date "
                    + "utility contract.")
                .containsAll(REQUIRED_SUITES);
        }

        @Test
        @DisplayName("the required-suite list itself is well formed: no duplicate, no blank entry")
        void theRequiredSuiteListIsWellFormed() {
            Assertions.assertThat(REQUIRED_SUITES)
                .as("a duplicated entry would weaken the check silently by making one absence look "
                    + "like two presences")
                .doesNotHaveDuplicates()
                .allSatisfy(name -> Assertions.assertThat(name)
                    .as("fully qualified class name")
                    .isNotBlank()
                    .contains("."));
        }
    }

    @Nested
    @DisplayName("The generated inventory itself")
    class TheGeneratedInventory {

        @Test
        @DisplayName("the shipped programs are distinct, non-blank, and named as COBOL programs are")
        void theShippedProgramInventoryIsWellFormed() {
            Assertions.assertThat(programsShippingCases())
                .as("a duplicate would weaken the check silently by making one absence look like two "
                    + "presences")
                .doesNotHaveDuplicates()
                .allSatisfy(program -> Assertions.assertThat(program)
                    .as("a case directory is named for the COBOL program it covers, so it is upper "
                        + "case and eight characters, as every name in app/cbl is")
                    .isNotBlank()
                    .matches("[A-Z][A-Z0-9]{7}"));
        }

        @Test
        @DisplayName("the generated inventory is a superset of nothing hand-listed twice")
        void theGeneratedInventoryDoesNotDuplicateTheNamedSuites() {
            List<String> named = REQUIRED_SUITES;
            List<String> generated = programsShippingCases().stream()
                .map(program -> PARITY_PACKAGE + program + PARITY_TEST_SUFFIX)
                .toList();

            Assertions.assertThat(named)
                .as("the two inventories exist for different reasons and must not overlap: the "
                    + "generated one requires a gate per program, and the named one requires the "
                    + "suites carrying gates NO parity case can carry. A suite in both would be "
                    + "maintained in two places, which is how one of them goes stale.")
                .doesNotContainAnyElementsOf(generated);
        }
    }

    /**
     * Every compiled test class in this module's test output directory, as fully qualified class
     * names, sorted so a failure message reads the same way on every machine.
     *
     * @return the discovered class names, never {@code null}
     */
    private static List<String> discoveredTestClasses() {
        Path root = testClassesRoot();
        try (Stream<Path> tree = Files.walk(root)) {
            List<String> names = new ArrayList<>();
            tree.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(TEST_CLASS_SUFFIX))
                // A @Nested inner suite compiles to Outer$Inner.class. Counting those would inflate
                // the number several fold and make the floor meaningless, so only top-level suites
                // are counted - which is also the unit surefire discovers.
                .filter(path -> !path.getFileName().toString().contains("$"))
                .forEach(path -> names.add(toClassName(root, path)));
            names.sort(String::compareTo);
            return names;
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot walk the test output directory " + root
                + " to count the discovered suite", cause);
        }
    }

    /**
     * Every compiled parity gate, reduced to the program name it covers.
     *
     * @return the program names, sorted
     */
    private static List<String> compiledParityGates() {
        return discoveredTestClasses().stream()
            .filter(name -> name.startsWith(PARITY_PACKAGE) && name.endsWith(PARITY_TEST_SUFFIX))
            .map(name -> name.substring(PARITY_PACKAGE.length(),
                name.length() - PARITY_TEST_SUFFIX.length()))
            .sorted()
            .toList();
    }

    /**
     * Every program that ships a case directory, read from the classpath rather than from a list.
     *
     * <p>This is the generated half of the inventory, and generating it is the point: a hand-maintained
     * list of programs would name what somebody remembered at the time, and a program added to the
     * corpus without a matching edit would go unnoticed - which is exactly the omission this class
     * exists to catch.
     *
     * @return the program names in ascending order, or empty when the corpus is not on the classpath
     */
    private static List<String> programsShippingCases() {
        URL root = BuildGateSentinelTest.class.getClassLoader().getResource(PARITY_RESOURCE_ROOT);
        if (root == null) {
            return List.of();
        }
        Path directory;
        try {
            directory = Path.of(root.toURI());
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException("Cannot resolve the parity corpus from " + root
                + String.format(Locale.ROOT, " (%s)", malformed.getMessage()), malformed);
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<String> programs = new ArrayList<>();
            entries.filter(Files::isDirectory)
                .forEach(path -> programs.add(path.getFileName().toString()));
            programs.sort(String::compareTo);
            return programs;
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot list the parity corpus at " + directory
                + " to generate the required gate inventory", cause);
        }
    }

    /**
     * Converts a compiled class file under the test output root into its fully qualified class name.
     *
     * <p>The caller has already excluded nested suites, so every path reaching here names a
     * top-level class.
     */
    private static String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace(File.separatorChar, '.');
        return relative.substring(0, relative.length() - ".class".length());
    }

    /**
     * The module's test output directory, located from this class's own code source rather than from
     * the process working directory.
     *
     * @return the {@code target/test-classes} directory this class was loaded from
     */
    private static Path testClassesRoot() {
        URL location = BuildGateSentinelTest.class.getProtectionDomain().getCodeSource().getLocation();
        try {
            Path root = Path.of(location.toURI());
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("The test output location " + root
                    + " is not a directory, so the compiled suite cannot be counted. This sentinel "
                    + "expects to run from an exploded target/test-classes directory, which is how "
                    + "surefire runs it.");
            }
            return root;
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException("Cannot resolve the test output directory from " + location
                + String.format(Locale.ROOT, " (%s)", malformed.getMessage()), malformed);
        }
    }
}
