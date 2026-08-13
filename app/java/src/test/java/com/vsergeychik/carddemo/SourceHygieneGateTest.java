package com.vsergeychik.carddemo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The static hygiene gate: it reads this project's own deliverable files and refuses the kinds of
 * change that carry no meaning.
 *
 * <h2>Why a gate rather than a cleanup</h2>
 * <p>Trailing whitespace, a second blank line at end of file and an import listed twice are all
 * invisible where they are introduced and expensive where they are read: they put lines in a diff that
 * say nothing, and a reviewer looking for the change that matters has to rule each one out by hand.
 * Cleaning them once fixes the files that exist; a gate is what stops the next one, which is why this
 * class exists alongside the cleanup rather than instead of it.
 *
 * <h2>Non-mutating, by construction</h2>
 * <p>Nothing here writes, moves or rewrites a file. Every method opens files read-only, collects what it
 * found, and asserts - so a failure names the file and line for a human to fix rather than reformatting
 * a tree under them. A formatter bound to the build would also silently rewrite the one thing this gate
 * must never touch, which the next section is about.
 *
 * <h2>What is deliberately NOT judged, and why that is not a loophole</h2>
 * <p>{@code src/test/resources/fixtures/} is excluded from the whitespace rules, and the exclusion is a
 * requirement rather than a convenience. Those nine files are fixed-width record images derived from
 * {@code app/data/ASCII}: every {@code PIC X} field is space-padded to its declared width, so the
 * trailing spaces on 450 of their lines <strong>are the data</strong>. Stripping them would shorten
 * records below the width their copybook declares and every parity diff over them would fail. The
 * exclusion is scoped to that one directory by exact path - not by extension, and not by a pattern that
 * could grow - and {@link TheFixtureExclusion} pins both halves of the claim: that the directory really
 * does carry semantic trailing whitespace, and that nothing else in the deliverable set does.
 *
 * <p>Reference sources under {@code app/cbl}, {@code app/cpy}, {@code app/cpy-bms}, {@code app/bms},
 * {@code app/jcl}, {@code app/proc}, {@code app/csd}, {@code app/ctl}, {@code app/catlg} and
 * {@code app/data} are outside the scanned set entirely. They are read-only inputs this migration must
 * not touch (practice <strong>B3</strong>): judging their whitespace would invite exactly the edit that
 * destroys the only oracle the parity gate has.
 *
 * <h2>Scope</h2>
 * <p>The scanned set is this project's own change surface, which gate <strong>G5</strong> pins: the
 * {@code app/java} module - Java sources, YAML resources, the parity case corpus and the module
 * descriptor - together with {@code README.md}, {@code docs/project-guide.md} and {@code mkdocs.yml}.
 * The module root is located the way the sibling suites locate it, through Surefire's {@code basedir},
 * so the scan does not depend on where the build was launched from.
 */
@DisplayName("Static hygiene gate - no whitespace-only change, no import listed twice")
class SourceHygieneGateTest {

    /** Extensions whose files are judged. Everything else in the tree is ignored. */
    private static final Set<String> JUDGED_EXTENSIONS =
            Set.of(".java", ".yml", ".yaml", ".xml", ".json", ".md");

    /**
     * The one directory whose trailing whitespace is data.
     *
     * <p>Relative to the {@code app/java} module root. Held as a path rather than a glob so it cannot
     * quietly widen: a second exclusion has to be written, named and justified.
     */
    private static final Path FIXTURE_DIRECTORY =
            Path.of("src", "test", "resources", "fixtures");

    /**
     * The documents outside the module that this project also authors.
     *
     * <p>Relative to the repository root, which is the module root's grandparent -
     * {@code app/java} -> {@code app} -> repository.
     *
     * <p>{@code mkdocs.yml} is deliberately absent. The plan lists it as an update to make only if new
     * documentation pages are added, none were, and it is byte-identical to what this project inherited -
     * including its missing final newline. Judging it would mean writing that newline, which is a
     * whitespace-only change to a file nothing else in this work touches: the very thing this gate exists
     * to prevent. A file becomes judged when this project starts authoring it, not before.
     */
    private static final List<Path> REPOSITORY_DOCUMENTS = List.of(
            Path.of("README.md"),
            Path.of("docs", "project-guide.md"));

    /**
     * The smallest number of files a meaningful scan sees.
     *
     * <p>A scan that silently found nothing would pass every assertion below, so the count is asserted
     * too. Stated as a floor well under the real total - 357 Java sources, 560 parity cases, four YAML
     * documents, one descriptor and three repository documents at the time of writing - because an exact
     * count would fail every time a file was legitimately added.
     */
    private static final int SCAN_FLOOR = 500;

    /** Matches a line whose last character is a space or a tab. */
    private static final Pattern TRAILING_WHITESPACE = Pattern.compile("[ \t]+$");

    /** Matches an import statement, capturing the whole statement for comparison. */
    private static final Pattern IMPORT_STATEMENT =
            Pattern.compile("^\\s*(import\\s+(?:static\\s+)?[\\w.*]+\\s*;)\\s*$");

    // =================================================================================================
    // Whitespace.
    // =================================================================================================

    @Nested
    @DisplayName("Whitespace carries no change")
    class WhitespaceCarriesNoChange {

        @Test
        @DisplayName("no judged file has a line ending in a space or a tab")
        void noJudgedFileHasTrailingWhitespace() {
            Map<String, Integer> offenders = new LinkedHashMap<>();
            int scanned = 0;

            for (Path file : judgedFiles()) {
                scanned++;
                List<String> lines = linesOf(file);
                for (int index = 0; index < lines.size(); index++) {
                    if (TRAILING_WHITESPACE.matcher(lines.get(index)).find()) {
                        offenders.put(relative(file) + ":" + (index + 1), index + 1);
                    }
                }
            }

            Assertions.assertThat(scanned)
                    .as("the scan must see this project's whole change surface, or its result proves "
                            + "nothing")
                    .isGreaterThanOrEqualTo(SCAN_FLOOR);
            Assertions.assertThat(offenders.keySet())
                    .as("a line ending in whitespace is a diff line that says nothing; strip it rather "
                            + "than committing it. Offenders: %s", offenders.keySet())
                    .isEmpty();
        }

        @Test
        @DisplayName("every judged file ends with exactly one newline and no blank line before it")
        void everyJudgedFileEndsWithExactlyOneNewline() {
            List<String> missingNewline = new ArrayList<>();
            List<String> blankAtEof = new ArrayList<>();

            for (Path file : judgedFiles()) {
                String body = bodyOf(file);
                if (body.isEmpty()) {
                    continue;
                }
                if (!body.endsWith("\n")) {
                    missingNewline.add(relative(file));
                    continue;
                }
                // One terminating newline is the line's own; a second, or a whitespace-only final line,
                // is the "new blank line at EOF" git reports.
                if (body.endsWith("\n\n") || body.endsWith("\n \n") || body.endsWith("\n\t\n")) {
                    blankAtEof.add(relative(file));
                }
            }

            Assertions.assertThat(missingNewline)
                    .as("a file with no terminating newline makes its last line unstable in every "
                            + "future diff. Offenders: %s", missingNewline)
                    .isEmpty();
            Assertions.assertThat(blankAtEof)
                    .as("a blank line at end of file is the other half of the same noise. Offenders: %s",
                            blankAtEof)
                    .isEmpty();
        }

        @Test
        @DisplayName("no judged file mixes line endings, and none of this project's own files uses CRLF")
        void lineEndingsAreConsistent() {
            // README.md was the repository's ONLY CRLF file, and converting it whole-file made all 324
            // of its pre-existing lines appear as changes. Every other file here - every COBOL source,
            // copybook, JCL deck, YAML document and markdown page - is LF, so LF is the convention this
            // project writes in and a CRLF file is the anomaly rather than the standard.
            List<String> carriageReturns = new ArrayList<>();
            for (Path file : judgedFiles()) {
                if (bodyOf(file).indexOf('\r') >= 0) {
                    carriageReturns.add(relative(file));
                }
            }
            Assertions.assertThat(carriageReturns)
                    .as("this repository is LF throughout; a carriage return makes every line of a file "
                            + "differ from its counterpart. Offenders: %s", carriageReturns)
                    .isEmpty();
        }
    }

    // =================================================================================================
    // Imports.
    // =================================================================================================

    @Nested
    @DisplayName("Every import is listed once")
    class EveryImportIsListedOnce {

        @Test
        @DisplayName("no Java source lists the same import twice")
        void noJavaSourceDuplicatesAnImport() {
            Map<String, List<String>> offenders = new LinkedHashMap<>();
            int scanned = 0;

            for (Path file : judgedFiles()) {
                if (!file.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                scanned++;
                Set<String> seen = new LinkedHashSet<>();
                List<String> duplicated = new ArrayList<>();
                for (String line : linesOf(file)) {
                    Matcher matcher = IMPORT_STATEMENT.matcher(line);
                    if (matcher.matches() && !seen.add(matcher.group(1))) {
                        duplicated.add(matcher.group(1));
                    }
                }
                if (!duplicated.isEmpty()) {
                    offenders.put(relative(file), duplicated);
                }
            }

            Assertions.assertThat(scanned)
                    .as("the scan must see the Java sources, or its result proves nothing")
                    .isGreaterThan(300);
            Assertions.assertThat(offenders)
                    .as("an import listed twice compiles and means nothing; it is the artefact of an "
                            + "edit that inserted rather than replaced. Offenders: %s", offenders)
                    .isEmpty();
        }

        @Test
        @DisplayName("no Java source uses a wildcard import, so every copybook-to-type link stays "
                + "auditable")
        void noJavaSourceUsesAWildcardImport() {
            // Gate G52, checked here because this is the class that already has every source open. A
            // star import hides which type a file reaches for, and the whole point of one Java type per
            // copybook is that a reviewer can trace the correspondence by reading the import block.
            Map<String, List<String>> offenders = new LinkedHashMap<>();
            for (Path file : judgedFiles()) {
                if (!file.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                List<String> wildcards = new ArrayList<>();
                for (String line : linesOf(file)) {
                    Matcher matcher = IMPORT_STATEMENT.matcher(line);
                    if (matcher.matches() && matcher.group(1).contains(".*")) {
                        wildcards.add(matcher.group(1));
                    }
                }
                if (!wildcards.isEmpty()) {
                    offenders.put(relative(file), wildcards);
                }
            }
            Assertions.assertThat(offenders)
                    .as("gate G52 forbids wildcard imports. Offenders: %s", offenders)
                    .isEmpty();
        }
    }

    // =================================================================================================
    // The one exclusion, pinned from both sides.
    // =================================================================================================

    @Nested
    @DisplayName("The fixture exclusion is necessary and is the only one")
    class TheFixtureExclusion {

        @Test
        @DisplayName("the excluded fixtures really do carry trailing whitespace as data")
        void theFixturesCarrySemanticTrailingWhitespace() {
            // If this ever stops being true, the exclusion above has become dead weight and should go.
            Path fixtures = moduleRoot().resolve(FIXTURE_DIRECTORY);
            Assertions.assertThat(fixtures).isDirectory();

            int padded = 0;
            int files = 0;
            for (Path fixture : filesUnder(fixtures)) {
                files++;
                for (String record : linesOf(fixture)) {
                    if (TRAILING_WHITESPACE.matcher(record).find()) {
                        padded++;
                    }
                }
            }

            Assertions.assertThat(files)
                    .as("the nine fixed-width fixtures derived from app/data/ASCII")
                    .isEqualTo(9);
            Assertions.assertThat(padded)
                    .as("PIC X fields are space-padded to their declared width, so these trailing "
                            + "spaces are record content and stripping them would shorten the record")
                    .isGreaterThan(400);
        }

        @Test
        @DisplayName("the exclusion is scoped to that one directory and nothing else relies on it")
        void theExclusionIsScopedToOneDirectory() {
            // Stated as an exact path rather than a pattern, so widening it is a visible edit.
            Assertions.assertThat(FIXTURE_DIRECTORY.toString().replace('\\', '/'))
                    .isEqualTo("src/test/resources/fixtures");

            // And the judged set really does omit it - the assertion that keeps the two halves honest.
            Assertions.assertThat(judgedFiles())
                    .as("no fixture may be inside the judged set, or the whitespace rules would strip "
                            + "record padding")
                    .noneMatch(file -> file.startsWith(moduleRoot().resolve(FIXTURE_DIRECTORY)));
        }
    }

    // =================================================================================================
    // Helpers. Each one reads; none writes.
    // =================================================================================================

    /**
     * Every file this gate judges: the {@code app/java} module's own sources and resources, minus the
     * fixture directory, plus the three repository documents this project authors.
     *
     * @return the files in a stable order, never {@code null}
     */
    private static List<Path> judgedFiles() {
        Path module = moduleRoot();
        Path fixtures = module.resolve(FIXTURE_DIRECTORY);

        List<Path> judged = new ArrayList<>(filesUnder(module.resolve("src")).stream()
                .filter(file -> !file.startsWith(fixtures))
                .filter(SourceHygieneGateTest::isJudgedExtension)
                .toList());
        judged.add(module.resolve("pom.xml"));

        Path repository = module.getParent().getParent();
        for (Path document : REPOSITORY_DOCUMENTS) {
            Path resolved = repository.resolve(document);
            Assertions.assertThat(resolved)
                    .as("%s is part of this project's change surface and must exist to be judged",
                            document)
                    .isRegularFile();
            judged.add(resolved);
        }
        judged.sort(Comparator.naturalOrder());
        return judged;
    }

    /**
     * Whether a file's extension is one this gate judges.
     *
     * @param file the candidate; must not be {@code null}
     * @return {@code true} when the extension is in {@link #JUDGED_EXTENSIONS}
     */
    private static boolean isJudgedExtension(final Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && JUDGED_EXTENSIONS.contains(name.substring(dot));
    }

    /**
     * Every regular file under a directory, sorted.
     *
     * @param directory the root to walk; must exist
     * @return the files found, never {@code null}
     */
    private static List<Path> filesUnder(final Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException failure) {
            throw new UncheckedIOException("The hygiene gate could not read " + directory
                    + ", so it can prove nothing about this project's files", failure);
        }
    }

    /**
     * A file's whole content, decoded as UTF-8.
     *
     * @param file the file to read; must not be {@code null}
     * @return the content, never {@code null}
     */
    private static String bodyOf(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("The hygiene gate could not read " + file, failure);
        }
    }

    /**
     * A file's lines, without their terminators and without a phantom empty line at the end.
     *
     * @param file the file to read; must not be {@code null}
     * @return the lines, never {@code null}
     */
    private static List<String> linesOf(final Path file) {
        String body = bodyOf(file);
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            // split("\n", -1) yields a trailing empty element for the final newline; it is the
            // terminator rather than a line, and everyJudgedFileEndsWithExactlyOneNewline judges it.
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * A file's path relative to the repository root, for a diagnostic a human can act on.
     *
     * @param file the file to name; must not be {@code null}
     * @return the relative path, using forward slashes
     */
    private static String relative(final Path file) {
        Path repository = moduleRoot().getParent().getParent();
        Path relative = file.startsWith(repository) ? repository.relativize(file) : file;
        return relative.toString().replace('\\', '/');
    }

    /**
     * The {@code app/java} module root, located the way the sibling suites locate it.
     *
     * <p>Surefire supplies the module base directory as both the working directory and the
     * {@code basedir} system property, so the scan is independent of where the build was launched from.
     *
     * @return the module root
     * @throws IllegalStateException if the module descriptor is not where either property says
     */
    private static Path moduleRoot() {
        List<Path> candidates = List.of(
                Path.of(System.getProperty("basedir", ".")),
                Path.of(System.getProperty("user.dir", ".")));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("The module root was not found at " + candidates
                + ". This gate reads app/java's own sources and the three repository documents beside "
                + "it, locating both from the module base directory Surefire supplies as 'basedir'; run "
                + "it through Maven, or set basedir when running it another way.");
    }
}
