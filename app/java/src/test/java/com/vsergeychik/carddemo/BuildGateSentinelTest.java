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
 * The build gate's sentinel: it proves that the suite the build claims to have run is actually the suite
 * this module contains.
 */
@DisplayName("Build gate sentinel - the suite that ran is the suite this module contains")
class BuildGateSentinelTest {
    private static final String TEST_CLASS_SUFFIX = "Test.class";

    private static final String PARITY_TEST_SUFFIX = "ParityTest";

    private static final String PARITY_PACKAGE = "com.vsergeychik.carddemo.parity.";

    private static final String PARITY_RESOURCE_ROOT = "parity";

    private static final String NON_PROGRAM_PARITY_SUITE = "FieldDifferByte";

    private static final List<String> REQUIRED_SUITES = List.of(
        "com.vsergeychik.carddemo.parity.ParityCaseTest",
        "com.vsergeychik.carddemo.parity.FieldDifferTest",
        "com.vsergeychik.carddemo.common.CobolDecimalTest",
        "com.vsergeychik.carddemo.common.FixedWidthCodecTest",
        "com.vsergeychik.carddemo.common.CicsAidTest",
        "com.vsergeychik.carddemo.util.DateUtilityJobTest",
        "com.vsergeychik.carddemo.SourceHygieneGateTest");

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

    private static List<String> discoveredTestClasses() {
        Path root = testClassesRoot();
        try (Stream<Path> tree = Files.walk(root)) {
            List<String> names = new ArrayList<>();
            tree.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(TEST_CLASS_SUFFIX))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .forEach(path -> names.add(toClassName(root, path)));
            names.sort(String::compareTo);
            return names;
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot walk the test output directory " + root
                + " to count the discovered suite", cause);
        }
    }

    private static List<String> compiledParityGates() {
        return discoveredTestClasses().stream()
            .filter(name -> name.startsWith(PARITY_PACKAGE) && name.endsWith(PARITY_TEST_SUFFIX))
            .map(name -> name.substring(PARITY_PACKAGE.length(),
                name.length() - PARITY_TEST_SUFFIX.length()))
            .sorted()
            .toList();
    }

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

    private static String toClassName(Path root, Path classFile) {
        String relative = root.relativize(classFile).toString().replace(File.separatorChar, '.');
        return relative.substring(0, relative.length() - ".class".length());
    }

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
