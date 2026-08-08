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
 * <p>It counts the compiled {@code *Test.class} files in the module's own test output directory,
 * located through this class's own {@link URL} rather than through the process working directory, so
 * the check is hermetic and does not care where the build was launched from. Two assertions follow:
 * <ul>
 *   <li><strong>A floor on the count.</strong> Supplied by the build as
 *       {@code carddemo.test.suite-floor} so the number lives in exactly one place. It is a
 *       floor and never an equality - adding a suite must never require editing the pom - but losing
 *       most of the suite fails immediately.</li>
 *   <li><strong>The named suites that carry the acceptance gates.</strong> A count alone can be
 *       satisfied by any set of classes, so the classes without which the gate means nothing are
 *       named individually: the parity contract and the deterministic judge that computes the diff
 *       count, and the four foundational suites that pin numeric truncation, fixed-width byte
 *       layout, the IBM-supplied AID constants and the date utility's 80-byte contract.</li>
 * </ul>
 *
 * <p>Nothing here reads the wall clock, opens a network connection or writes a file, and there is no
 * static mutable state: the counts are recomputed per test method from the build output.
 */
@DisplayName("Build gate sentinel - the suite that ran is the suite this module contains")
class BuildGateSentinelTest {

    /**
     * The system property carrying the floor, set by surefire from
     * {@code carddemo.test.suite-floor} in {@code app/java/pom.xml}.
     */
    private static final String MINIMUM_PROPERTY = "carddemo.test.suite-floor";

    /**
     * The floor used when the property is absent - an IDE run, or a direct JUnit launch outside
     * Maven. Deliberately low rather than zero: an out-of-build run should still catch a
     * catastrophically empty test output directory without failing for a reason the runner cannot
     * fix.
     */
    private static final int FALLBACK_MINIMUM = 1;

    /** The suffix maven-surefire-plugin's default includes match on. */
    private static final String TEST_CLASS_SUFFIX = "Test.class";

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
    @DisplayName("Discovered suite size")
    class SuiteSize {

        @Test
        @DisplayName("at least the declared floor of test classes was compiled and is discoverable")
        void discoveredSuiteMeetsTheDeclaredFloor() {
            List<String> discovered = discoveredTestClasses();
            int floor = declaredFloor();

            Assertions.assertThat(discovered)
                .as("compiled *Test classes under %s. The floor %d comes from %s in "
                        + "app/java/pom.xml. A count below it means the build ran a fraction of this "
                        + "module's suite and reported success over it, which is the failure mode "
                        + "failIfNoTests cannot catch because SOME tests did run.",
                    testClassesRoot(), floor, MINIMUM_PROPERTY)
                .hasSizeGreaterThanOrEqualTo(floor);
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
    @DisplayName("The floor itself")
    class Floor {

        @Test
        @DisplayName("the floor is positive, so it can actually fail a build")
        void theFloorIsPositive() {
            Assertions.assertThat(declaredFloor())
                .as("a floor of zero is not a gate: it would accept the empty suite it exists to "
                    + "reject")
                .isPositive();
        }

        @Test
        @DisplayName("the floor covers at least the named required suites")
        void theFloorCoversTheRequiredSuites() {
            Assertions.assertThat(declaredFloor())
                .as("the floor must be at least the number of suites named individually, or the two "
                    + "checks could disagree about what a minimal acceptable suite is")
                .isGreaterThanOrEqualTo(REQUIRED_SUITES.size());
        }
    }

    /**
     * The floor the build declared, or {@link #FALLBACK_MINIMUM} when this suite is run outside
     * Maven.
     *
     * @return the floor, always positive
     */
    private static int declaredFloor() {
        String declared = System.getProperty(MINIMUM_PROPERTY);
        if (declared == null || declared.isBlank()) {
            return FALLBACK_MINIMUM;
        }
        try {
            return Integer.parseInt(declared.strip());
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException("System property " + MINIMUM_PROPERTY + " is '" + declared
                + "', which is not an integer. It is set by surefire from the property of the same "
                + "name in app/java/pom.xml and must be a plain count of test classes.", notANumber);
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
