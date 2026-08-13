package com.vsergeychik.carddemo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The source-hygiene guard: it fails the build on a duplicated import or on stray trailing
 * whitespace, so neither can be reintroduced silently after being cleaned out.
 *
 * <h2>Why a test rather than a formatter or a git hook</h2>
 * <p>Both defects this closes are invisible in review and harmless at runtime, which is exactly why
 * they accumulate. A duplicate import compiles without a warning under {@code javac} - the JLS
 * permits a single-type-import declaration to name a type already imported - so nothing in the
 * toolchain objects. Trailing whitespace changes no behaviour at all; it just makes every later diff
 * noisier than the change it carries.
 *
 * <p>{@code git diff --check} is the usual answer to the second one and it is a good one, but it
 * cannot be the only one here for two reasons. It only inspects lines a diff ADDS, so it is blind to
 * whatever is already committed; and it is a property of a developer's checkout rather than of the
 * build, so a clone that never installed the hook is unprotected. This class runs inside
 * {@code mvn clean verify} - the single documented gating command - so the check travels with the
 * module rather than with a workstation. The two are complementary, and the git-side check remains
 * worth running.
 *
 * <h2>What it checks</h2>
 * <ul>
 *   <li><strong>No import is declared twice</strong> in any {@code .java} file under
 *       {@code src/main/java} or {@code src/test/java}. Static and non-static imports are compared
 *       on their fully-qualified name, which is what the duplication actually is.</li>
 *   <li><strong>No line carries trailing whitespace</strong>, and <strong>no file ends with a blank
 *       line</strong>, across the same Java sources plus {@code pom.xml}, {@code README.md} and
 *       {@code docs/project-guide.md} - the text files this migration writes.</li>
 *   <li><strong>The fixed-width fixtures KEEP their padding.</strong> This one is asserted in the
 *       positive, and it is the reason the sweep above is scoped rather than global.</li>
 * </ul>
 *
 * <h2>The exemption that matters, and why it is asserted rather than merely skipped</h2>
 * <p>{@code src/test/resources/fixtures/} holds fixed-width record images copied from
 * {@code app/data/ASCII}. A 300-byte {@code ACCOUNT-RECORD} whose tail is {@code FILLER X(178)} is
 * SUPPOSED to end in spaces - the trailing bytes are the record, not formatting - and
 * {@code acctdata.txt}, {@code custdata.txt}, {@code carddata.txt} and {@code dailytran.txt} each
 * carry them on every row. A whitespace sweep that reached those files would silently shorten every
 * record and break every offset downstream of the change.
 *
 * <p>So the exemption is stated as a REQUIREMENT rather than as an omission: the fixtures must still
 * have trailing spaces. Written that way, a future "tidy up the whitespace" pass that reaches the
 * fixture data fails here with an explanation, instead of producing a subtly wrong corpus that the
 * parity differ would then report as hundreds of field mismatches with no hint as to the cause. An
 * exemption nobody can see is an exemption somebody will eventually delete.
 *
 * <h2>How the files are located</h2>
 * <p>Through this class's own code source, the pattern the rest of this suite's source-reading tests
 * use: {@code target/test-classes} sits one level below {@code target}, which sits one below the
 * module root, and the repository root is two above that. The working directory is a fallback, not
 * the primary route, so the check does not depend on where the JVM was started. If the tree cannot
 * be found the check FAILS rather than passing vacuously - a hygiene assertion that silently skips
 * itself is worse than no assertion, because it reports success.
 *
 * <p>Nothing here reads the wall clock, opens a network connection or writes a file, and there is no
 * static mutable state: every inventory is recomputed per test method.
 */
@DisplayName("Source hygiene - no duplicated import, no stray trailing whitespace")
class SourceHygieneTest {

    /**
     * A single import declaration on a line of its own.
     *
     * <p>Group 1 is the {@code static} keyword when present, group 2 the fully-qualified name. The
     * anchors matter: an {@code import} appearing inside a string literal or a javadoc block is not
     * matched, because it would not be alone on the line at statement position.
     */
    private static final Pattern IMPORT_DECLARATION =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;\\s*$");

    /** The text files outside {@code src} that this migration writes and therefore owns. */
    private static final List<String> OWNED_TEXT_FILES = List.of(
            "app/java/pom.xml",
            "README.md",
            "docs/project-guide.md");

    /** The fixture files whose trailing bytes are record content rather than formatting. */
    private static final List<String> PADDED_FIXTURES = List.of(
            "acctdata.txt",
            "custdata.txt",
            "carddata.txt",
            "dailytran.txt");

    @Nested
    @DisplayName("Imports")
    class Imports {

        @Test
        @DisplayName("no source file declares the same import twice")
        void noDuplicateImports() {
            Map<String, List<String>> offenders = new LinkedHashMap<>();
            for (Path source : javaSources()) {
                Map<String, List<Integer>> occurrences = importOccurrences(source);
                List<String> duplicated = new ArrayList<>();
                occurrences.forEach((fullyQualifiedName, lines) -> {
                    if (lines.size() > 1) {
                        duplicated.add(fullyQualifiedName + " on lines " + lines);
                    }
                });
                if (!duplicated.isEmpty()) {
                    offenders.put(relativeToRepository(source), duplicated);
                }
            }
            assertThat(offenders)
                    .withFailMessage("These sources declare an import more than once. javac does not "
                            + "warn about it, so nothing else in the build will tell you. Delete the "
                            + "copy that sits out of alphabetical order, which restores both "
                            + "uniqueness and the ordering of the block:%n%s", describe(offenders))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Whitespace")
    class Whitespace {

        @Test
        @DisplayName("no line in an owned text file carries trailing whitespace")
        void noTrailingWhitespace() {
            Map<String, List<String>> offenders = new LinkedHashMap<>();
            for (Path file : ownedTextFiles()) {
                List<String> lines = readLines(file);
                List<String> dirty = new ArrayList<>();
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (!line.equals(stripTrailing(line))) {
                        dirty.add("line " + (index + 1) + " ends with "
                                + (line.length() - stripTrailing(line).length())
                                + " whitespace character(s)");
                    }
                }
                if (!dirty.isEmpty()) {
                    offenders.put(relativeToRepository(file), dirty);
                }
            }
            assertThat(offenders)
                    .withFailMessage("These files carry trailing whitespace. It changes no behaviour, "
                            + "which is exactly why it survives review - and why every later diff "
                            + "that touches the line carries it as noise:%n%s", describe(offenders))
                    .isEmpty();
        }

        @Test
        @DisplayName("no owned text file ends with a blank line")
        void noBlankLineAtEndOfFile() {
            List<String> offenders = new ArrayList<>();
            for (Path file : ownedTextFiles()) {
                String text = readText(file);
                if (text.endsWith("\n\n") || text.endsWith("\n\r\n")) {
                    offenders.add(relativeToRepository(file));
                }
            }
            assertThat(offenders)
                    .withFailMessage("These files end with a blank line before end-of-file. A text "
                            + "file should end with exactly one newline, so that appending to it "
                            + "produces a one-line diff rather than a two-line one: %s", offenders)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("The fixture exemption, asserted in the positive")
    class FixtureExemption {

        @Test
        @DisplayName("the fixed-width fixtures still carry their trailing padding")
        void fixturesKeepTheirPadding() {
            Path fixtures = moduleRoot().resolve(Path.of("src", "test", "resources", "fixtures"));
            assertThat(fixtures).as("the fixture directory the parity corpus is seeded from")
                    .isDirectory();
            Map<String, Integer> paddedRowCounts = new LinkedHashMap<>();
            for (String name : PADDED_FIXTURES) {
                Path fixture = fixtures.resolve(name);
                assertThat(fixture).as("fixture %s", name).isRegularFile();
                int padded = 0;
                for (String row : readLines(fixture)) {
                    if (!row.isBlank() && !row.equals(stripTrailing(row))) {
                        padded++;
                    }
                }
                paddedRowCounts.put(name, padded);
            }
            assertThat(paddedRowCounts)
                    .withFailMessage("Every one of these fixtures must still have rows that END IN "
                            + "SPACES: the trailing bytes are the record's own FILLER, not "
                            + "formatting, and stripping them shortens the record and moves every "
                            + "offset after it. If a whitespace sweep reached src/test/resources/"
                            + "fixtures, restore it from app/data/ASCII. Padded row counts "
                            + "found: %s", paddedRowCounts)
                    .allSatisfy((name, count) -> assertThat(count)
                            .as("rows of %s ending in padding", name)
                            .isPositive());
        }
    }

    // =============================================================================================
    // Reading the tree. Located from the code source, never from the working directory alone.
    // =============================================================================================

    /**
     * Every {@code .java} file in the module, main sources and test sources alike.
     *
     * @return the source files, in a stable order so a failure message is reproducible
     */
    private static List<Path> javaSources() {
        List<Path> sources = new ArrayList<>();
        for (String tree : List.of("main", "test")) {
            Path root = moduleRoot().resolve(Path.of("src", tree, "java"));
            if (!Files.isDirectory(root)) {
                throw new IllegalStateException("The " + tree + " source tree was not found at "
                        + root + ". This check is an assertion over the sources, so it must not pass "
                        + "when it cannot read them.");
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(sources::add);
            } catch (IOException unreadable) {
                throw new UncheckedIOException("Cannot walk " + root, unreadable);
            }
        }
        assertThat(sources).as("the module's Java sources").isNotEmpty();
        return sources;
    }

    /**
     * The Java sources plus the text files outside {@code src} that this migration writes.
     *
     * @return the files the whitespace sweep covers
     */
    private static List<Path> ownedTextFiles() {
        List<Path> files = new ArrayList<>(javaSources());
        Path repository = repositoryRoot();
        for (String owned : OWNED_TEXT_FILES) {
            Path file = repository.resolve(owned);
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("The owned text file " + owned + " was not found at "
                        + file + ". It is named explicitly rather than discovered, so a rename must "
                        + "fail here rather than quietly drop the file from the sweep.");
            }
            files.add(file);
        }
        return files;
    }

    /**
     * Where each imported fully-qualified name appears in a source file.
     *
     * @param source the file to read
     * @return imported name to the one-based line numbers declaring it, in encounter order
     */
    private static Map<String, List<Integer>> importOccurrences(Path source) {
        Map<String, List<Integer>> occurrences = new LinkedHashMap<>();
        List<String> lines = readLines(source);
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = IMPORT_DECLARATION.matcher(lines.get(index));
            if (matcher.matches()) {
                String key = (matcher.group(1) == null ? "" : "static ") + matcher.group(2);
                occurrences.computeIfAbsent(key, unused -> new ArrayList<>()).add(index + 1);
            }
        }
        return occurrences;
    }

    /**
     * A file's lines, without the empty element a trailing newline would otherwise contribute.
     *
     * @param file the file to read
     * @return the lines, carriage returns retained so a stray one is reported rather than hidden
     */
    private static List<String> readLines(Path file) {
        String text = readText(file);
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * A file's whole text, read as UTF-8 rather than in the platform default encoding.
     *
     * @param file the file to read
     * @return its contents
     */
    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Cannot read " + file, unreadable);
        }
    }

    /**
     * A line without its trailing whitespace, spaces and tabs alike.
     *
     * @param line the line to trim
     * @return the line with every trailing whitespace character removed
     */
    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    /**
     * A path rendered relative to the repository root, so failure messages are readable.
     *
     * @param file the file to describe
     * @return the repository-relative path, or the absolute one if it lies outside
     */
    private static String relativeToRepository(Path file) {
        Path repository = repositoryRoot();
        return file.startsWith(repository)
                ? repository.relativize(file).toString()
                : file.toString();
    }

    /**
     * Renders an offender map as one indented line per finding.
     *
     * @param offenders file to the findings within it
     * @return a multi-line description
     */
    private static String describe(Map<String, List<String>> offenders) {
        StringBuilder rendered = new StringBuilder();
        offenders.forEach((file, findings) -> {
            rendered.append("  ").append(file).append(System.lineSeparator());
            findings.forEach(finding ->
                    rendered.append("      ").append(finding).append(System.lineSeparator()));
        });
        return rendered.toString();
    }

    /**
     * The Maven module root, derived from where this test's classes were loaded from.
     *
     * @return the directory holding {@code pom.xml} and {@code src}
     * @throws IllegalStateException if the code source cannot be resolved to a directory
     */
    private static Path moduleRoot() {
        try {
            Path testClasses = Path.of(SourceHygieneTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            // target/test-classes -> target -> the module root.
            Path fromCodeSource = testClasses.getParent().getParent();
            if (Files.isRegularFile(fromCodeSource.resolve("pom.xml"))) {
                return fromCodeSource;
            }
        } catch (URISyntaxException | NullPointerException unresolvable) {
            // Fall through to the working directory, then fail with both routes named.
        }
        Path fromWorkingDirectory = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(fromWorkingDirectory.resolve("pom.xml"))) {
            return fromWorkingDirectory;
        }
        throw new IllegalStateException("The module root could not be derived from this class's code "
                + "source, and the working directory " + fromWorkingDirectory + " does not hold a "
                + "pom.xml either. This check reads the sources, so it fails rather than skipping.");
    }

    /**
     * The repository root, two directories above the module.
     *
     * @return the directory holding {@code README.md} and {@code app}
     */
    private static Path repositoryRoot() {
        // app/java -> app -> the repository root.
        return moduleRoot().getParent().getParent();
    }
}
