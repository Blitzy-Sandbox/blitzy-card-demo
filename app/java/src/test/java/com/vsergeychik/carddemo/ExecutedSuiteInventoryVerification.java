package com.vsergeychik.carddemo;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The build gate's execution proof: it reads what the test runner recorded as executed and requires that to
 * be the whole of what this module contains.
 */
@DisplayName("Executed suite inventory - every suite this module contains was actually run")
class ExecutedSuiteInventoryVerification {
    private static final String TEST_CLASS_SUFFIX = "Test.class";

    private static final String PARITY_TEST_SUFFIX = "ParityTest";

    private static final String PARITY_PACKAGE = "com.vsergeychik.carddemo.parity.";

    private static final String PARITY_RESOURCE_ROOT = "parity";

    private static final int CASES_PER_PROGRAM = 20;

    private static final String SELF = ExecutedSuiteInventoryVerification.class.getName();

    private static final Pattern REPORT_FILE = Pattern.compile("TEST-(.+)\\.xml");

    private static final Pattern SUITE_ATTRIBUTES = Pattern.compile(
        "<testsuite\\b[^>]*?\\bname=\"([^\"]*)\"[^>]*?\\btests=\"(\\d+)\""
            + "[^>]*?\\berrors=\"(\\d+)\"[^>]*?\\bskipped=\"(\\d+)\"[^>]*?\\bfailures=\"(\\d+)\"",
        Pattern.DOTALL);

    private record ExecutedSuite(String name, int tests, int failures, int errors, int skipped) {
        private int ran() {
            return tests - skipped;
        }
    }

    @Nested
    @DisplayName("The executed record itself")
    class TheExecutedRecord {
        @Test
        @DisplayName("the runner wrote a report for at least one suite, so there is a record to check")
        void theReportDirectoryIsPopulated() {
            Assertions.assertThat(executedSuites())
                .as("surefire writes one TEST-*.xml per executed suite into %s. An empty directory "
                    + "means this verification ran without a preceding test run, at which point it "
                    + "can prove nothing: it is bound to a later phase than the main test execution "
                    + "precisely so the record is complete when it reads it.", reportsDirectory())
                .isNotEmpty();
        }

        @Test
        @DisplayName("no executed suite reports a failure, an error, or a suite that ran nothing")
        void everyExecutedSuiteReachedAVerdict() {
            Map<String, ExecutedSuite> executed = executedSuites();

            List<String> empty = new ArrayList<>();
            List<String> broken = new ArrayList<>();
            executed.values().forEach(suite -> {
                if (suite.ran() <= 0) {
                    empty.add(suite.name() + " (tests=" + suite.tests() + ", skipped="
                        + suite.skipped() + ')');
                }
                if (suite.failures() > 0 || suite.errors() > 0) {
                    broken.add(suite.name() + " (failures=" + suite.failures() + ", errors="
                        + suite.errors() + ')');
                }
            });

            Assertions.assertThat(empty)
                .as("a suite whose report records no test that reached a verdict contributed no "
                    + "evidence, and a gate with no evidence behind it reports the number it wants "
                    + "to see")
                .isEmpty();
            Assertions.assertThat(broken)
                .as("surefire fails the build on these already; asserting it here as well means the "
                    + "executed record and the build outcome cannot disagree")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("Compiled against executed")
    class CompiledAgainstExecuted {
        @Test
        @DisplayName("every compiled suite appears in the executed record, with tests that ran")
        void everyCompiledSuiteWasExecuted() {
            List<String> compiled = compiledTestClasses();
            Map<String, ExecutedSuite> executed = executedSuites();

            List<String> neverRan = compiled.stream()
                .filter(name -> !executed.containsKey(name))
                .toList();

            Assertions.assertThat(neverRan)
                .as("compiled but absent from the executed record. This is an EQUALITY between what "
                    + "the module contains and what the runner ran, deliberately rather than a "
                    + "count-based floor: any floor is satisfied while three quarters of the suite "
                    + "sits unexecuted, which is the failure this check exists to catch. %d suites "
                    + "compiled, %d appear in %s.",
                    compiled.size(), executed.size(), reportsDirectory())
                .isEmpty();
            Assertions.assertThat(compiled)
                .as("the compiled inventory itself must not be empty, or the equality above is "
                    + "vacuously satisfied")
                .isNotEmpty();
        }

        @Test
        @DisplayName("the executed record names no suite this module does not contain")
        void theExecutedRecordNamesOnlyThisModulesSuites() {
            List<String> compiled = compiledTestClasses();
            List<String> unknown = executedSuites().keySet().stream()
                .filter(name -> !compiled.contains(name))
                .toList();

            Assertions.assertThat(unknown)
                .as("a report naming a suite absent from this module's output means the directory "
                    + "carries a record from an earlier or a different build, and the equality above "
                    + "would then be satisfied by evidence that is not this run's. %s is already "
                    + "absent from the record by construction - see the exclusion where the record is "
                    + "parsed - and is in any case not named *Test, so it is not part of the compiled "
                    + "inventory it verifies.", SELF)
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("The parity gates, generated from the shipped corpus")
    class TheParityGates {
        @Test
        @DisplayName("every program that ships cases has an executed gate running at least 20 of them")
        void everyShippedProgramsGateWasExecuted() {
            List<String> programs = programsShippingCases();
            Map<String, ExecutedSuite> executed = executedSuites();

            Assertions.assertThat(programs)
                .as("the required inventory is GENERATED from %s rather than hand-listed, so it "
                    + "cannot fall behind the corpus. An empty list would mean the corpus is not on "
                    + "the classpath, at which point this check proves nothing.",
                    PARITY_RESOURCE_ROOT)
                .isNotEmpty();

            List<String> missing = new ArrayList<>();
            List<String> short0 = new ArrayList<>();
            for (String program : programs) {
                String suite = PARITY_PACKAGE + program + PARITY_TEST_SUFFIX;
                ExecutedSuite record = executed.get(suite);
                if (record == null) {
                    missing.add(suite);
                } else if (record.ran() < CASES_PER_PROGRAM) {
                    short0.add(suite + " ran " + record.ran() + " test(s), fewer than the "
                        + CASES_PER_PROGRAM + " cases " + program + " ships");
                }
            }

            Assertions.assertThat(missing)
                .as("%d programs ship cases under %s, so %d parity gates must appear in the executed "
                    + "record. A program whose gate never ran has 20 case files that assert nothing, "
                    + "and its module reports a diff count of zero because nothing computed one.",
                    programs.size(), PARITY_RESOURCE_ROOT, programs.size())
                .isEmpty();
            Assertions.assertThat(short0)
                .as("a gate that ran fewer tests than its program ships cases left cases "
                    + "unexecuted, and gate G18 requires the diff count to be zero across ALL "
                    + "twenty of them")
                .isEmpty();
        }

        @Test
        @DisplayName("the compiled parity gates and the shipped program directories are the same set")
        void theCompiledGatesMatchTheShippedPrograms() {
            List<String> shipped = programsShippingCases();
            List<String> gates = compiledTestClasses().stream()
                .filter(name -> name.startsWith(PARITY_PACKAGE) && name.endsWith(PARITY_TEST_SUFFIX))
                .map(name -> name.substring(PARITY_PACKAGE.length(),
                    name.length() - PARITY_TEST_SUFFIX.length()))
                .toList();

            Assertions.assertThat(gates)
                .as("every program directory needs a gate: a case directory with no gate is 20 files "
                    + "that read in review as part of the acceptance criteria and execute nothing")
                .containsAll(shipped);
            Assertions.assertThat(gates.stream().filter(name -> !shipped.contains(name)).toList())
                .as("and every gate needs a directory: a gate whose program ships no cases would "
                    + "fail at load time, which is a worse diagnostic than this. %s is the one "
                    + "permitted exception - it is the differ's own byte-level self-test and names "
                    + "no COBOL program.", "FieldDifferByte")
                .containsAnyElementsOf(List.of("FieldDifferByte"))
                .hasSize(1);
        }
    }

    private static Map<String, ExecutedSuite> executedSuites() {
        Path reports = reportsDirectory();
        if (!Files.isDirectory(reports)) {
            return Map.of();
        }
        Map<String, ExecutedSuite> executed = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(reports)) {
            files.filter(Files::isRegularFile)
                .filter(path -> REPORT_FILE.matcher(path.getFileName().toString()).matches())
                .sorted()
                .forEach(path -> parseReport(path)
                    .filter(suite -> !SELF.equals(suite.name()))
                    .ifPresent(suite -> executed.put(suite.name(), suite)));
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot list the surefire report directory " + reports
                + " to establish what was executed", cause);
        }
        return executed;
    }

    private static java.util.Optional<ExecutedSuite> parseReport(Path report) {
        String xml;
        try {
            xml = Files.readString(report, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot read the surefire report " + report, cause);
        }
        Matcher matcher = SUITE_ATTRIBUTES.matcher(xml);
        if (!matcher.find()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ExecutedSuite(matcher.group(1),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(5)),
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4))));
    }

    private static List<String> compiledTestClasses() {
        Path root = testClassesRoot();
        try (Stream<Path> tree = Files.walk(root)) {
            List<String> names = new ArrayList<>();
            tree.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(TEST_CLASS_SUFFIX))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .forEach(path -> {
                    String relative = root.relativize(path).toString()
                        .replace(File.separatorChar, '.');
                    names.add(relative.substring(0, relative.length() - ".class".length()));
                });
            names.sort(String::compareTo);
            return names;
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot walk the test output directory " + root
                + " to establish the compiled inventory", cause);
        }
    }

    private static List<String> programsShippingCases() {
        URL root = ExecutedSuiteInventoryVerification.class.getClassLoader()
            .getResource(PARITY_RESOURCE_ROOT);
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
            return entries.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream()
                .toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot list the parity corpus at " + directory, cause);
        }
    }

    private static Path reportsDirectory() {
        return testClassesRoot().getParent().resolve("surefire-reports");
    }

    private static Path testClassesRoot() {
        URL location = ExecutedSuiteInventoryVerification.class.getProtectionDomain()
            .getCodeSource().getLocation();
        try {
            Path root = Path.of(location.toURI());
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("The test output location " + root
                    + " is not a directory, so neither the compiled inventory nor the report "
                    + "directory beside it can be found. This verification expects to run from an "
                    + "exploded target/test-classes directory, which is how surefire runs it.");
            }
            return root;
        } catch (URISyntaxException malformed) {
            throw new IllegalStateException("Cannot resolve the test output directory from " + location
                + String.format(Locale.ROOT, " (%s)", malformed.getMessage()), malformed);
        }
    }
}
