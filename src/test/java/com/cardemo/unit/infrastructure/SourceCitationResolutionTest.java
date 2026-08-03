/*
 * ****************************************************************************
 * Program     : SourceCitationResolutionTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Mechanically resolves every app/... citation the migrated tree
 *               makes against the frozen COBOL corpus. A citation is the whole
 *               evidence mechanism of this migration, so a path that does not
 *               resolve is a broken proof, not a typo.
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
 * <p>Exactly one path is exempt, and its exemption is itself asserted. {@code app/data/ASCII/usrsec.txt}
 * does not exist and is cited precisely to say so: the ten seeded users are inline {@code SYSUT1 DD *} data
 * in {@code app/jcl/DUSRSECJ.jcl} rather than a fixture, and three places state that absence explicitly.
 * {@link #theOneNonResolvingPathIsOnlyEverCitedAsAbsent()} requires every one of those mentions to carry a
 * negation, so the exemption cannot be borrowed to hide a genuinely broken citation.
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

    @Test
    @DisplayName("every cited app/... path exists on disk, bar the one deliberately absent fixture")
    void everyCitedPathExists() {
        final List<Reference> references = allReferences();
        assertThat(references)
                .as(
                        "the scan must find the citation set, or it proves nothing. Over thirteen thousand "
                                + "occurrences were measured across the tree")
                .hasSizeGreaterThan(5_000);

        final Set<String> unresolved = new TreeSet<>();
        final List<String> locations = new ArrayList<>();
        for (final Reference reference : references) {
            if (DELIBERATELY_ABSENT.equals(reference.citation())) {
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
}
