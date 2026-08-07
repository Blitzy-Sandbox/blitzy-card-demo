/*
 * ****************************************************************************
 * Program     : SourceCitationResolutionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Mechanically resolves every app/... citation the migrated tree
 *               makes against the frozen COBOL corpus. A citation is the whole
 *               evidence mechanism of this migration, so a path that does not
 *               resolve is a broken proof, not a typo - and neither does a path
 *               that resolves while the line number beside it does not exist,
 *               which is why the locator is checked as well as the path.
 * Source      : app/** (the frozen corpus every citation points into: 28
 *                 programs, 28 copybooks, 17 mapsets, 17 symbolic maps, 29 JCL
 *                 members, 2 procedures, 1 control card, 1 CSD, 1 catalogue
 *                 listing and 21 data files)
 *               @ 7756d89
 * ****************************************************************************
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
 * language governing permissions and limitations under the License
 * ****************************************************************************
 */
package com.cardemo.unit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Resolves every {@code app/...} citation in the migrated tree against the frozen corpus on disk.
 *
 * <p>Citations are not decoration in this project. They are the evidence mechanism: the scope-coverage gate
 * reads them, every entity width and every reject literal is justified by one, and a reviewer's only route
 * from a Java method back to the paragraph it reproduces is the path in its Javadoc. A citation that does not
 * resolve is therefore a broken proof rather than a cosmetic slip, and it is invisible to the compiler
 * because it lives in a comment.
 *
 * <p>The review found three such citations. Two were casing errors against a corpus whose filenames are
 * mixed case on purpose - {@code app/cbl/CBSTM03A.CBL} and {@code app/cbl/CBSTM03B.CBL} are upper case while
 * their twenty-six siblings are lower case - and one was an unqualified dataset name where the corpus holds
 * the fully qualified form. A hand search found the three; this test found eight more of the same casing
 * error that the hand search had missed, which is the argument for holding it mechanically.
 *
 * <p>Two exemptions exist, both narrow and both asserted. The first is unconditional:
 * {@code app/data/ASCII/usrsec.txt} does not exist and is cited precisely to say so - the ten seeded users are
 * inline {@code SYSUT1 DD *} data in {@code app/jcl/DUSRSECJ.jcl} rather than a fixture, and three places
 * state that absence explicitly. {@link #theOneNonResolvingPathIsOnlyEverCitedAsAbsent()} requires every one
 * of those mentions to carry a negation, so it cannot be borrowed to hide a genuinely broken citation.
 *
 * <p>The second is CONDITIONAL and does nothing in a clone: citations under {@code app/data/EBCDIC} are
 * passed over only when that whole directory is absent, which happens exclusively inside a Docker build
 * context that pruned the out-of-scope codepage datasets. With the directory present - here, and in CI - every
 * one of those citations is resolved strictly. {@link #theEbcdicExemptionIsNarrowAndSelfLimiting()} holds that
 * shape, and the reasoning is recorded on {@link #PRUNABLE_TREE}.
 *
 * <h2>Why the line number is checked too</h2>
 *
 * <p>Checking only the path leaves half the citation unverified, and a later review found eleven citations
 * whose path resolved while the line beside it did not exist. Each failure mode was distinct, and each is
 * the kind a reader trusts <em>because</em> the path is right. The four below are described with the path and
 * the number deliberately held apart, never written as a locator, because this gate reads its own source and
 * an example written in citation form would be reported as the very defect it illustrates.
 *
 * <ul>
 *   <li><b>A COBOL paragraph number written as a line number.</b> {@code 2900-WRITE-TRANSACTION-FILE} is a
 *       paragraph label in {@code app/cbl/CBTRN02C.cbl}; a locator of 2900 overshoots that file's 731 lines.
 *       The label sits at line 562.</li>
 *   <li><b>A locator carried over from a sibling copybook.</b> Line 18 of {@code app/cpy/CVACT02Y.cpy}, which
 *       has 14. {@code CARD-NUM} is at line 5 and {@code CARD-CVV-CD} at line 7, which is what a hundred other
 *       sites in this tree already cite correctly.</li>
 *   <li><b>A file name transcribed wrongly while the range stayed right.</b> Lines 345 to 366 are
 *       {@code 1000-XREFFILE-GET-NEXT} in {@code app/cbl/CBSTM03A.CBL}, not in the 178-line
 *       {@code app/cbl/CBACT03C.cbl}.</li>
 *   <li><b>A range whose end overshot the file by two lines.</b> An end of 262 in
 *       {@code app/cbl/COSGN00C.cbl}, which has 260. Only a bounds check on the <em>end</em> of a range
 *       catches this one; the start was perfectly valid.</li>
 * </ul>
 *
 * <p>A fifth kind is worth recording because no bounds check can find it: a locator that is in range and
 * still wrong. One citation named a line in {@code app/cbl/CBSTM03A.CBL} as the source of
 * {@code FUNCTION CURRENT-DATE} when that program does not use the intrinsic anywhere at all. That was
 * corrected by reading the corpus, and it is the reason this gate is described as necessary rather than
 * sufficient.
 *
 * <p>{@link #everyCitedLineIsWithinItsFile()} closes that gap. Two decisions in it are deliberate and were
 * both established by measuring the tree rather than by assumption.
 *
 * <ul>
 *   <li><b>Only a digit locator is a line reference.</b> The corpus is also cited by JCL step name
 *       ({@code app/jcl/CREASTMT.JCL:STEP040}, {@code :DELDEF01}), by CSD statement
 *       ({@code app/csd/CARDDEMO.CSD:DEFINE TDQUEUE(JOBS)}) and, inside a parameterised assertion message,
 *       by format specifier ({@code app/cpy/CVTRA05Y.cpy:L%d}). None of those is a line number and none is
 *       checked.</li>
 *   <li><b>A range admits no whitespace around its hyphen.</b> Every real range in the tree is written
 *       closed up - {@code :L345-L366}, {@code :L5-L11}, {@code :6-11}. Admitting spaces was measured to
 *       misread three correct citations of the form {@code app/cbl/CSUTLDTC.cbl:L84 - 01 LS-DATE PIC X(10)},
 *       where the {@code 01} is a COBOL <em>level number</em> in the following prose rather than the end of a
 *       range. A gate that reports a correct citation is worse than no gate, because it teaches the reader to
 *       ignore it.</li>
 * </ul>
 */
@DisplayName("Every app/... citation resolves against the frozen corpus")
final class SourceCitationResolutionTest {

    /**
     * A citation into the frozen corpus. Anchored at a word boundary, admitting the character set the corpus
     * actually uses, and required to end on a name character so a trailing sentence period, colon or closing
     * brace is not absorbed into the path.
     */
    private static final Pattern CITATION =
            Pattern.compile("\\b(app/[A-Za-z0-9_./-]*[A-Za-z0-9_])");

    /**
     * The line locator that may follow a citation, matched at the character immediately after the path.
     *
     * <p>Group 1 is the line, group 2 the optional end of a range. The {@code L} prefix is optional because
     * the tree uses both forms deliberately - {@code :L5} in Javadoc prose and {@code :5} in the schema
     * comments - and both are equally valid.
     *
     * <p>The hyphen of a range admits no surrounding whitespace, and that restriction is load-bearing rather
     * than tidy: see the class documentation for the three correct citations that a permissive form misread.
     * Requiring digits also excludes the step-name, CSD-statement and format-specifier locators, none of
     * which is a line reference.
     */
    private static final Pattern LINE_LOCATOR = Pattern.compile("^:L?(\\d+)(?:-L?(\\d+))?");

    /** File extensions worth scanning: everything the migration authors as text. */
    private static final Set<String> SCANNED_EXTENSIONS =
            Set.of(".java", ".yml", ".yaml", ".sql", ".xml", ".sh", ".json", ".properties");

    /** Directories scanned in full. */
    private static final List<String> SCANNED_DIRECTORIES =
            List.of("src", "localstack-init", "observability", ".github");

    /** Individually named root files scanned. */
    private static final List<String> SCANNED_FILES =
            List.of("pom.xml", "Dockerfile", "docker-compose.yml", ".env.example");

    /**
     * The single path that does not resolve, and is cited only in order to say it does not exist.
     *
     * <p>The nine fixtures under {@code app/data/ASCII} seed nine tables. The tenth table, {@code USRSEC},
     * has no fixture at all: its ten rows are inline {@code SYSUT1 DD *} data in
     * {@code app/jcl/DUSRSECJ.jcl}, fed through IEBGENER. Naming the absent file is the clearest way to
     * record that, so the name appears while the file does not.
     */
    private static final String DELIBERATELY_ABSENT = "app/data/ASCII/usrsec.txt";

    /**
     * The one subtree whose citations are resolved only when the subtree itself is present.
     *
     * <p>{@code app/data/EBCDIC} holds twelve fixed-width {@code .PS} datasets kept as byte-level codepage
     * reference. The AAP puts them out of scope: nothing transcodes them and no build step parses them. They
     * are consequently the one part of the corpus a Docker build context legitimately prunes - and
     * {@code .dockerignore} prunes it, because 204 KB of material no stage reads has no business crossing to
     * the daemon.
     *
     * <p>That pruning collided with this test. The Dockerfile runs the unit tier inside the builder stage,
     * where the subtree is absent, so thirteen citations naming it - two concrete {@code .PS} datasets and
     * eleven references to the directory - read as "cited but does not resolve" and failed the image build on
     * a defect that existed only in the build context. Withdrawing the exclusion made the build green by
     * admitting out-of-scope data into the context, which fixed the symptom by widening the scope.
     *
     * <p>The exemption below is keyed on the DIRECTORY, not on the individual files: when
     * {@code app/data/EBCDIC} is present - in a clone, in CI, in a developer's build - every citation under it
     * must resolve exactly like any other, so a mistyped dataset name is still caught. Only when the whole
     * subtree is absent, which happens exclusively inside a pruned build context, are those citations passed
     * over. {@link #theEbcdicExemptionIsNarrowAndSelfLimiting()} holds that shape.
     */
    private static final String PRUNABLE_TREE = "app/data/EBCDIC";

    /** Words that mark a citation as a statement of absence rather than a reference. */
    private static final List<String> NEGATIONS =
            List.of("no ", "none", "not ", "absent", "never", "nothing");

    /** The repository root, located once and reused. */
    private static final Path ROOT = repositoryRoot();

    /** Every scanned file, resolved once. */
    private static final List<Path> SCANNED = scannedFiles();

    /**
     * Walks upward from the working directory to the directory holding both {@code pom.xml} and {@code app/}.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("app"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds both pom.xml and app/, so citations cannot be "
                        + "resolved. Run this test with the repository root, or any directory beneath it, as "
                        + "the working directory.");
    }

    /**
     * Collects every text file the migration authors.
     *
     * @return the files to scan, in a stable order
     */
    private static List<Path> scannedFiles() {
        final List<Path> files = new ArrayList<>();
        for (final String directory : SCANNED_DIRECTORIES) {
            final Path base = ROOT.resolve(directory);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> SCANNED_EXTENSIONS.stream()
                                .anyMatch(extension -> path.getFileName().toString().endsWith(extension)))
                        .sorted()
                        .forEach(files::add);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot walk " + base, cause);
            }
        }
        for (final String file : SCANNED_FILES) {
            final Path path = ROOT.resolve(file);
            if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        return files;
    }

    /**
     * A citation, the file that makes it, the line it sits on and the sentence around it.
     *
     * @param path     the citing file, relative to the repository root
     * @param line     the one-based line number
     * @param citation the cited corpus path
     * @param text     the whole citing line
     * @param context  the citing line together with the two lines either side, which is the window a wrapped
     *                 Javadoc or SQL-comment sentence occupies
     */
    private record Reference(String path, int line, String citation, String text, String context) {

        @Override
        public String toString() {
            return path + ":" + line + " -> " + citation;
        }
    }

    /**
     * Extracts every citation from every scanned file.
     *
     * @return one entry per citation occurrence
     */
    private static List<Reference> allReferences() {
        final List<Reference> references = new ArrayList<>();
        for (final Path path : SCANNED) {
            final String relative = ROOT.relativize(path).toString();
            final List<String> content;
            try {
                content = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + path, cause);
            }
            for (int index = 0; index < content.size(); index++) {
                final String line = content.get(index);
                final Matcher matcher = CITATION.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                matcher.reset();
                final String context = String.join(" ",
                        content.subList(Math.max(0, index - 2), Math.min(content.size(), index + 3)));
                while (matcher.find()) {
                    references.add(
                            new Reference(relative, index + 1, matcher.group(1), line, context));
                }
            }
        }
        return references;
    }

    /**
     * A citation that carries a line locator, resolved into its numeric bounds.
     *
     * @param path     the citing file, relative to the repository root
     * @param line     the one-based line of the citing file
     * @param citation the cited corpus path
     * @param locator  the locator exactly as written, for a failure message that quotes the source
     * @param start    the cited line, one-based
     * @param end      the end of a cited range, or {@code start} when a single line is cited
     */
    private record LineReference(
            String path, int line, String citation, String locator, int start, int end) {

        @Override
        public String toString() {
            return path + ":" + line + " -> " + citation + locator;
        }
    }

    /**
     * Extracts every citation that carries a line locator.
     *
     * <p>Citations whose path does not resolve are skipped: {@link #everyCitedPathExists()} owns that
     * failure, and reporting it twice would make one defect look like two.
     *
     * @return one entry per line-bearing citation occurrence
     */
    private static List<LineReference> lineReferences() {
        final List<LineReference> located = new ArrayList<>();
        for (final Path path : SCANNED) {
            final String relative = ROOT.relativize(path).toString();
            final List<String> content;
            try {
                content = Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + path, cause);
            }
            for (int index = 0; index < content.size(); index++) {
                final String line = content.get(index);
                final Matcher citations = CITATION.matcher(line);
                while (citations.find()) {
                    final Matcher locator = LINE_LOCATOR.matcher(line.substring(citations.end()));
                    if (!locator.find()) {
                        continue;
                    }
                    final int start = Integer.parseInt(locator.group(1));
                    final int end =
                            locator.group(2) == null ? start : Integer.parseInt(locator.group(2));
                    located.add(new LineReference(
                            relative, index + 1, citations.group(1), locator.group(), start, end));
                }
            }
        }
        return located;
    }

    /**
     * Counts the lines of a corpus file the way a reader counts them, so a locator can be bounded.
     *
     * @param corpusPath the cited path, relative to the repository root
     * @return the number of lines, or {@code -1} when the file does not exist
     */
    private static int corpusLineCount(final String corpusPath) {
        final Path resolved = ROOT.resolve(corpusPath);
        if (!Files.isRegularFile(resolved)) {
            return -1;
        }
        try {
            // ISO-8859-1 maps every byte to a character, so a corpus member holding a byte that is not
            // valid UTF-8 is still counted rather than throwing. Only the line count is wanted here.
            return Files.readAllLines(resolved, StandardCharsets.ISO_8859_1).size();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + resolved, cause);
        }
    }

    @Test
    @DisplayName("every cited app/... path exists on disk, bar the one deliberately absent fixture")
    void everyCitedPathExists() {
        final List<Reference> references = allReferences();
        assertThat(references)
                .as(
                        "the scan must find the citation set, or it proves nothing. Over thirteen thousand "
                                + "occurrences were measured across the tree")
                .hasSizeGreaterThan(5_000);

        final boolean prunableTreePresent = Files.isDirectory(ROOT.resolve(PRUNABLE_TREE));
        final Set<String> unresolved = new TreeSet<>();
        final List<String> locations = new ArrayList<>();
        for (final Reference reference : references) {
            if (DELIBERATELY_ABSENT.equals(reference.citation())) {
                continue;
            }
            // Passed over ONLY when the whole subtree is absent, which happens exclusively inside a build
            // context that pruned it. Where the subtree exists, these citations are checked like any other.
            if (!prunableTreePresent && reference.citation().startsWith(PRUNABLE_TREE)) {
                continue;
            }
            if (!Files.exists(ROOT.resolve(reference.citation()))) {
                unresolved.add(reference.citation());
                locations.add(reference.toString());
            }
        }

        assertThat(unresolved)
                .as(
                        "each of these paths is cited as evidence and does not exist, so the claim resting "
                                + "on it cannot be checked. Filename casing in app/ is load-bearing: "
                                + "CBSTM03A.CBL and CBSTM03B.CBL are upper case while their twenty-six "
                                + "siblings are lower case, and dataset citations must use the fully "
                                + "qualified name the corpus holds. Occurrences: %s",
                        locations)
                .isEmpty();
    }

    @Test
    @DisplayName("every cited line, and every end of a cited range, exists in the file it points into")
    void everyCitedLineIsWithinItsFile() {
        final List<LineReference> located = lineReferences();
        assertThat(located)
                .as(
                        "the locator scan must find the line-bearing citations, or it proves nothing. Over "
                                + "thirteen thousand were measured across the tree")
                .hasSizeGreaterThan(5_000);

        final List<String> violations = new ArrayList<>();
        for (final LineReference reference : located) {
            final int lines = corpusLineCount(reference.citation());
            if (lines < 0) {
                continue;
            }
            if (reference.start() < 1 || reference.start() > lines) {
                violations.add(reference + "  (the file has " + lines + " lines)");
            } else if (reference.end() > lines || reference.end() < reference.start()) {
                violations.add(reference + "  (range end is outside 1.." + lines
                        + " or precedes its start)");
            }
        }

        assertThat(violations)
                .as(
                        "each of these points a reader at a line that is not there, and the path resolving "
                                + "is exactly what makes the claim look checked when it is not. A COBOL "
                                + "paragraph number is not a line number; a locator does not survive being "
                                + "copied from a sibling copybook; and the end of a range needs bounding as "
                                + "much as its start. Occurrences: %s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("the locator rule reads the forms the tree actually uses, and refuses the ones it does not")
    void theLocatorRuleMatchesTheMeasuredForms() {
        assertThat(LINE_LOCATOR.matcher(":L9").find())
                .as("the dominant Javadoc form, an L prefix and a line")
                .isTrue();
        assertThat(LINE_LOCATOR.matcher(":9").find())
                .as("the schema-comment form, a bare line with no prefix")
                .isTrue();

        final Matcher range = LINE_LOCATOR.matcher(":L345-L366");
        assertThat(range.find()).as("a closed-up range is a range").isTrue();
        assertThat(range.group(1)).isEqualTo("345");
        assertThat(range.group(2)).isEqualTo("366");

        final Matcher spaced = LINE_LOCATOR.matcher(":L84 - 01 LS-DATE PIC X(10)");
        assertThat(spaced.find()).as("the line itself still matches").isTrue();
        assertThat(spaced.group(2))
                .as(
                        "a spaced hyphen is prose, not a range: the 01 here is a COBOL level number, and "
                                + "reading it as a range end reported three correct citations as broken")
                .isNull();

        assertThat(LINE_LOCATOR.matcher(":STEP040").find())
                .as("a JCL step name is not a line number")
                .isFalse();
        assertThat(LINE_LOCATOR.matcher(":DELDEF01").find())
                .as("a JCL step name that ends in digits is still not a line number")
                .isFalse();
        assertThat(LINE_LOCATOR.matcher(":L%d must begin at byte %d").find())
                .as("a format specifier inside an assertion message is not a line number")
                .isFalse();
        assertThat(LINE_LOCATOR.matcher(":DEFINE TDQUEUE(JOBS)").find())
                .as("a CSD statement is not a line number")
                .isFalse();
    }

    @Test
    @DisplayName("the one non-resolving path is only ever cited in order to say it does not exist")
    void theOneNonResolvingPathIsOnlyEverCitedAsAbsent() {
        assertThat(ROOT.resolve(DELIBERATELY_ABSENT))
                .as("if this fixture ever appears, the exemption must be deleted rather than kept")
                .doesNotExist();

        final List<Reference> mentions = allReferences().stream()
                .filter(reference -> DELIBERATELY_ABSENT.equals(reference.citation()))
                .toList();
        assertThat(mentions)
                .as("the absence is recorded deliberately, so at least one statement of it must exist")
                .isNotEmpty();

        for (final Reference mention : mentions) {
            // The window is the citing line plus two either side, because a Javadoc or SQL-comment sentence
            // wraps and the negation routinely lands on a different line from the path. Narrowing this to the
            // citing line alone was measured to reject a correct statement of absence in this very file.
            final String lowered = mention.context().toLowerCase(Locale.ROOT);
            assertThat(NEGATIONS.stream().anyMatch(lowered::contains))
                    .as(
                            "%s cites a path that does not exist. That is only legitimate as a statement of "
                                    + "absence, so the surrounding sentence must carry a negation - "
                                    + "otherwise the exemption is being borrowed to hide a broken citation. "
                                    + "Line: %s",
                            mention, mention.text().strip())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the prunable-subtree exemption is narrow, and inert wherever the subtree is present")
    void theEbcdicExemptionIsNarrowAndSelfLimiting() {
        assertThat(PRUNABLE_TREE)
                .as("""
                    the exemption stays scoped to the one out-of-scope subtree, whichever context this runs \
                    in. Widening this prefix - to app/data, or app/ - would silence citation failures across \
                    material the migration actually derives from.""")
                .isEqualTo("app/data/EBCDIC");

        final List<Reference> citations = allReferences().stream()
                .filter(reference -> reference.citation().startsWith(PRUNABLE_TREE))
                .toList();
        assertThat(citations)
                .as("""
                    the exemption must protect something real: these are the citations that failed the image \
                    build when .dockerignore pruned the subtree. If none is left, delete PRUNABLE_TREE and \
                    the branch that reads it rather than keeping a rule with nothing behind it.""")
                .isNotEmpty();

        final Set<String> cited = new TreeSet<>();
        final Set<String> broken = new TreeSet<>();
        final List<String> occurrences = new ArrayList<>();
        for (final Reference reference : citations) {
            cited.add(reference.citation());
            if (!Files.exists(ROOT.resolve(reference.citation()))) {
                broken.add(reference.citation());
                occurrences.add(reference.toString());
            }
        }

        // This test runs in BOTH contexts and must be meaningful in each, which is the whole point of an
        // exemption keyed on the directory: where the subtree exists the exemption is inert and strictness is
        // asserted; where it does not, the exemption is doing its job and the absence must be TOTAL rather
        // than partial - a subtree missing some of its files is a damaged corpus, not a pruned context.
        if (Files.isDirectory(ROOT.resolve(PRUNABLE_TREE))) {
            assertThat(broken)
                    .as("""
                        with the subtree on disk - a clone, CI, a developer build - every citation under it \
                        resolves like any other. The exemption is keyed on the DIRECTORY being absent, never \
                        on a file being absent, so it cannot be borrowed to carry a mistyped dataset name. \
                        Occurrences: %s""", occurrences)
                    .isEmpty();
        } else {
            assertThat(broken)
                    .as("""
                        the subtree is absent, so this is a pruned build context and the exemption is what \
                        keeps the citation gate honest here. Every DISTINCT path cited under the subtree must \
                        be unresolvable: if some resolve and some do not, the subtree was partially removed \
                        rather than pruned, and that is a damaged corpus which no exemption should hide.""")
                    .containsExactlyInAnyOrderElementsOf(cited);
        }
    }

    @Test
    @DisplayName("the corpus is cited across all of its directories, so no whole class of evidence is missing")
    void everyCorpusDirectoryIsCited() {
        final Set<String> citedRoots = new LinkedHashSet<>();
        for (final Reference reference : allReferences()) {
            final String[] segments = reference.citation().split("/");
            if (segments.length >= 2) {
                citedRoots.add(segments[0] + "/" + segments[1]);
            }
        }
        assertThat(citedRoots)
                .as(
                        "programs, copybooks, symbolic maps, mapsets, job control, procedures, the CICS "
                                + "resource definitions, the catalogue listing and the ASCII fixtures are "
                                + "each a distinct class of contract, and each must be cited somewhere")
                .contains(
                        "app/cbl",
                        "app/cpy",
                        "app/cpy-bms",
                        "app/bms",
                        "app/jcl",
                        "app/proc",
                        "app/csd",
                        "app/catlg",
                        "app/data");
    }

    /**
     * The complement of this class's main rule: the files that are NOT frozen may not be cited by line.
     *
     * <p>An {@code app/...} line locator is safe forever because the corpus is frozen - that is why the rest
     * of this class merely checks such locators resolve. A locator into a file this project edits is the
     * opposite: it is correct only until the next edit to the file it points INTO, and nothing about editing
     * that file surfaces the citations elsewhere that have just been invalidated.
     *
     * <p>That is not hypothetical. A sweep found 53 line locators into these five files across nine sources,
     * and essentially every one had rotted: locators aimed at the resource-name declarations of
     * {@code localstack-init/init-aws.sh} landed in the prose above them, several {@code .env.example}
     * locators landed on blank lines or in an unrelated section, most {@code docker-compose.yml} locators
     * landed in the usage-example header, and the {@code Dockerfile} health-check locators landed in a
     * comment about a JVM option. Each had been accurate when written. Renumbering them all would have
     * re-armed the same trap, so each became a SYMBOL citation instead - a variable name, a shell function,
     * a YAML path or a Dockerfile instruction - which survives an edit to the file it names.
     *
     * <p>This is a citation-FORM rule, not a resolution rule, because form is the only thing that can be
     * checked cheaply and kept true: any line number resolves, so a resolution check would have passed on
     * every one of the rotted locators above.
     */
    @Nested
    @DisplayName("No mutable file is cited by line number, because such a locator rots on the next edit")
    final class MutableTargets {

        /**
         * The files this project edits and whose line numbers therefore cannot be cited.
         *
         * <p>Each is named by its file name alone, so a citation is caught however it spells the path:
         * with or without the directory, and with or without the {@code L} prefix on the number. The
         * forbidden form is deliberately not spelled out as a literal example anywhere in this file - doing
         * so made the rule fail on its own documentation, which is the one offender it must not report.
         */
        private final List<String> mutableTargets = List.of(
                ".env.example", "docker-compose.yml", "init-aws.sh", "build.yml", "Dockerfile");

        /**
         * Matches a line locator attached to any of the mutable file names.
         *
         * @return the compiled pattern
         */
        private Pattern locatorPattern() {
            final String alternatives = mutableTargets.stream()
                    .map(Pattern::quote)
                    .reduce((left, right) -> left + "|" + right)
                    .orElseThrow();
            return Pattern.compile("(" + alternatives + "):L?\\d+(-L?\\d+)?");
        }

        @Test
        @DisplayName("no source cites one of the five mutable configuration files by line")
        void noMutableFileIsCitedByLine() {
            final Pattern locator = locatorPattern();
            final List<String> offenders = new ArrayList<>();

            for (final Path file : SCANNED) {
                final String relative = ROOT.relativize(file).toString();
                final List<String> content = readLines(file);
                for (int index = 0; index < content.size(); index++) {
                    final Matcher matcher = locator.matcher(content.get(index));
                    while (matcher.find()) {
                        offenders.add(relative + ":" + (index + 1) + " -> " + matcher.group());
                    }
                }
            }

            assertThat(offenders)
                    .as("""
                            Each entry is a line locator into a file this project edits. Cite a SYMBOL                             instead - the variable name, shell function, YAML path or Dockerfile                             instruction - because that survives an edit to the file it names, and a line                             number does not. Line locators remain correct, and remain checked, for                             app/... alone, which is frozen.""")
                    .isEmpty();
        }

        @Test
        @DisplayName("the five files are still cited by name, so the rule was not satisfied by silence")
        void theMutableFilesAreStillCitedBySymbol() {
            for (final String target : mutableTargets) {
                long mentions = 0;
                for (final Path file : SCANNED) {
                    if (file.getFileName().toString().equals(target)) {
                        continue;
                    }
                    mentions += readLines(file).stream().filter(line -> line.contains(target)).count();
                }
                assertThat(mentions)
                        .as("""
                                %s is cited by no other file at all. The rule above is satisfied by                                 deleting every citation as easily as by converting it, so this asserts the                                 evidence is still there - converted, not removed.""", target)
                        .isGreaterThan(2);
            }
        }

        @Test
        @DisplayName("every symbol the citations name resolves in the file it names")
        void everyCitedSymbolResolves() {
            // The replacement for a line locator is only better if it is CHECKED. Each row carries four
            // fields: the citing file, the target file, the symbol AS CITED in prose, and the symbol AS
            // DECLARED in the target. The two spellings are separate on purpose. Prose says
            // `readonly REPORT_QUEUE`, and searching the target for that alone would still match after a
            // rename to `readonly REPORT_QUEUE_V2` - a mutation test renamed one symbol exactly that way and
            // the check passed, which is why the declaration form carries its `=` or `()` and is matched in
            // full. A rename in either direction now fails: the declaration must exist in the target, and
            // the citing file must still make the citation, so the table cannot rot while quietly passing.
            final List<String[]> citations = List.of(
                    new String[] {"src/main/resources/application.yml", "localstack-init/init-aws.sh",
                                  "readonly INPUT_BUCKET", "readonly INPUT_BUCKET="},
                    new String[] {"src/main/resources/application.yml", "localstack-init/init-aws.sh",
                                  "readonly REPORT_QUEUE", "readonly REPORT_QUEUE="},
                    new String[] {"src/main/resources/application.yml", "localstack-init/init-aws.sh",
                                  "readonly QUEUE_LOGICAL", "readonly QUEUE_LOGICAL="},
                    new String[] {"src/main/resources/application.yml", "localstack-init/init-aws.sh",
                                  "readonly NOTIFICATION_TOPIC", "readonly NOTIFICATION_TOPIC="},
                    new String[] {"src/main/resources/application.yml", "localstack-init/init-aws.sh",
                                  "enable_and_verify_versioning", "enable_and_verify_versioning() {"},
                    new String[] {"src/main/resources/application-local.yml", "localstack-init/init-aws.sh",
                                  "readonly REGION", "readonly REGION="},
                    new String[] {"src/main/resources/application-local.yml", "localstack-init/init-aws.sh",
                                  "require_local_endpoint", "require_local_endpoint() {"},
                    new String[] {"src/main/resources/application-local.yml", "localstack-init/init-aws.sh",
                                  "validate_endpoint_port", "validate_endpoint_port() {"},
                    new String[] {"src/main/resources/application-local.yml", "docker-compose.yml",
                                  "x-carddemo-aws-context", "x-carddemo-aws-context:"},
                    new String[] {"src/main/java/com/cardemo/config/AwsConfig.java",
                                  "localstack-init/init-aws.sh", "ALLOWED_ENDPOINT_HOSTS",
                                  "readonly ALLOWED_ENDPOINT_HOSTS="},
                    new String[] {"src/main/java/com/cardemo/config/AwsConfig.java",
                                  "localstack-init/init-aws.sh", "ALLOWED_ENDPOINT_HOST_PATTERN",
                                  "readonly ALLOWED_ENDPOINT_HOST_PATTERN="},
                    new String[] {"src/main/java/com/cardemo/observability/HealthIndicators.java",
                                  "Dockerfile", "HEALTHCHECK", "HEALTHCHECK --"});

            final List<String> offenders = new ArrayList<>();
            for (final String[] citation : citations) {
                final String citing = citation[0];
                final String target = citation[1];
                final String cited = citation[2];
                final String declaration = citation[3];

                if (readLines(ROOT.resolve(target)).stream().noneMatch(line -> line.contains(declaration))) {
                    offenders.add(target + " no longer declares [" + declaration + "], cited by " + citing);
                }
                if (readLines(ROOT.resolve(citing)).stream().noneMatch(line -> line.contains(cited))) {
                    offenders.add(citing + " no longer cites [" + cited + "]; drop this row instead");
                }
            }

            assertThat(offenders)
                    .as("""
                            A symbol citation is only an improvement on a line locator while it still                             resolves. Either the target renamed the symbol - update both - or the citation                             was reworded, in which case this table should lose the row.""")
                    .isEmpty();
        }

        /**
         * Reads one scanned file into lines.
         *
         * @param file the file to read
         * @return its lines, never {@code null}
         */
        private List<String> readLines(final Path file) {
            try {
                return Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + file, cause);
            }
        }
    }
}
