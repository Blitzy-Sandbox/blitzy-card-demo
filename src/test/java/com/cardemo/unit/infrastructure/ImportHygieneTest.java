/*
 * ****************************************************************************
 * Program     : ImportHygieneTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Holds Rule 1 Clause B's prohibition on unused imports
 *               mechanically across both Java trees. An unused import compiles
 *               cleanly and reads as a declared dependency that is not one, so
 *               nothing in the build reported the twenty-five a review found by
 *               hand - which is the argument for a gate rather than a habit.
 * Source      : docs/technical-specifications.md 0.3.1.1 and 0.3.1.3 - the
 *                 declared CREATE patterns for src/main/java/com/cardemo/**
 *                 and src/test/java/com/cardemo/**, the two trees scanned here
 *               docs/technical-specifications.md 0.6.2.3 - the import rules the
 *                 migration authors every import against, including the three
 *                 collapse rules and the prohibition on import churn
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no Java source in either tree declares an import it does not use.
 *
 * <h2>Why this is a gate and not a review note</h2>
 *
 * <p>An unused import is invisible to everything the build already runs. It compiles without complaint, it
 * survives {@code -Xlint:all -Werror} because an unused import is not a compiler warning in {@code javac},
 * and the doclint gate has no opinion on it. So the twenty-five this class now forbids were found by reading,
 * and reading is exactly the mechanism that let seventeen of them accumulate after the first eight were
 * reported: the same four shapes repeated across sibling files, and a hand search that stopped at the named
 * examples missed the rest. Rule 1 Clause B forbids dead code without qualifying it by severity, and the only
 * way a prohibition on something the compiler tolerates stays true is to check it.
 *
 * <p>The four shapes are worth naming, because each is a way a correct file becomes an incorrect one without
 * anybody editing an import. A test that stops using a {@code Sort} or a {@code PageImpl} because its
 * assertion moved to a keyset finder; a writer that stops needing {@code Pattern} because its rendering moved
 * to a formatter; a record that stops needing {@code JsonProperty} because its component names became the
 * wire names; and a static import of a Mockito or AssertJ member left behind when the case that used it was
 * rewritten. In every one the deletion happened somewhere else in the file.
 *
 * <h2>The rule, and why it is deliberately conservative</h2>
 *
 * <p>An import is unused when the simple name it introduces never appears as a whole word anywhere after the
 * final import statement. That is a text rule rather than a resolved-symbol rule, and it errs in one
 * direction only: every one of these counts as a use, so the gate under-reports rather than demanding the
 * removal of an import something still needs.
 *
 * <ul>
 *   <li><b>A Javadoc reference is a use.</b> {@code {@link Foo}} and {@code {@code Foo}} both contain the
 *       simple name, so an import that exists only to let documentation link to a type is kept. This is not a
 *       concession: removing such an import breaks the {@code javadoc} doclint gate, so a rule that reported
 *       it would be asking for a build failure.</li>
 *   <li><b>A fully qualified reference is a use.</b> {@code org.springframework.data.domain.Pageable} in the
 *       body contains {@code Pageable} as a word, so a file that imports a type and also names it in full is
 *       left alone. That combination is redundant rather than dead, and it is not what Clause B is about.</li>
 *   <li><b>A mention in a comment or a string literal is a use.</b> The rule cannot tell a live reference
 *       from a described one, and it resolves that in favour of keeping the import.</li>
 * </ul>
 *
 * <p>On-demand imports are excluded entirely. A wildcard introduces no single name, so no textual rule can
 * attribute a use to it, and reporting one would be a guess. The tree contains none at the time of writing,
 * and {@link #onDemandImportsAreAbsent()} records that as an observation rather than smuggling in a new
 * prohibition the requirements do not state.
 *
 * <p>{@link #theRuleItselfIsExercised()} runs the rule over synthetic sources whose answers are known. Two of
 * this session's false readings came from a scanner whose regex was quietly wrong rather than from the tree
 * it scanned, and a gate whose own rule is untested is a gate that can start passing for the wrong reason.
 */
@DisplayName("Import hygiene - no Java source declares an import it does not use")
final class ImportHygieneTest {

    /**
     * A single-type or single-static-member import declaration.
     *
     * <p>Group 1 is the qualified name. An on-demand import ends in {@code .*} and is matched here so it can
     * be counted and then deliberately skipped, rather than silently failing to parse.
     */
    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;\\s*$");

    /** The two Java trees the Agent Action Plan declares, both scanned in full. */
    private static final List<String> SCANNED_TREES =
            List.of("src/main/java/com/cardemo", "src/test/java/com/cardemo");

    /** The repository root, located once and reused. */
    private static final Path ROOT = repositoryRoot();

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
                "No directory from " + start + " upward holds both pom.xml and app/, so the Java trees "
                        + "cannot be located. Run this test with the repository root, or any directory "
                        + "beneath it, as the working directory.");
    }

    /**
     * Collects every {@code .java} source in both declared trees.
     *
     * @return the sources to scan, in a stable order
     */
    private static List<Path> javaSources() {
        final List<Path> sources = new ArrayList<>();
        for (final String tree : SCANNED_TREES) {
            final Path base = ROOT.resolve(tree);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(sources::add);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot walk " + base, cause);
            }
        }
        return sources;
    }

    /**
     * Applies the rule to one source's lines.
     *
     * <p>Kept package-private and driven from {@link #theRuleItselfIsExercised()} as well as from the tree
     * scan, so the rule is proved against known answers rather than only against the tree it judges.
     *
     * @param lines the source's lines, in order
     * @return one entry per unused import, as {@code <one-based line>:<qualified name>}
     */
    private static List<String> unusedImports(final List<String> lines) {
        final List<int[]> positions = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int lastImport = -1;
        for (int index = 0; index < lines.size(); index++) {
            final Matcher matcher = IMPORT.matcher(lines.get(index));
            if (!matcher.matches()) {
                continue;
            }
            lastImport = index;
            if (matcher.group(1).endsWith(".*")) {
                continue;
            }
            positions.add(new int[] {index});
            names.add(matcher.group(1));
        }
        if (names.isEmpty()) {
            return List.of();
        }

        // The body is everything after the LAST import, so one import can never count as another's use.
        final String body = String.join("\n", lines.subList(lastImport + 1, lines.size()));
        final List<String> unused = new ArrayList<>();
        for (int slot = 0; slot < names.size(); slot++) {
            final String qualified = names.get(slot);
            final String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
            if (!Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(body).find()) {
                unused.add((positions.get(slot)[0] + 1) + ":" + qualified);
            }
        }
        return unused;
    }

    /**
     * Reads one source, decoding as UTF-8 because the whole tree is authored that way.
     *
     * @param source the file to read
     * @return its lines, in order
     */
    private static List<String> lines(final Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + source, cause);
        }
    }

    @Test
    @DisplayName("the scan reaches both trees, so the assertion below cannot pass vacuously")
    void theScanReachesBothTrees() {
        final List<Path> sources = javaSources();
        assertThat(sources)
                .as("a scan that finds no source would report no violation and prove nothing")
                .hasSizeGreaterThan(300);

        for (final String tree : SCANNED_TREES) {
            final Path base = ROOT.resolve(tree);
            assertThat(sources.stream().anyMatch(source -> source.startsWith(base)))
                    .as(
                            "%s must be reached: an unused import in the main tree is the same defect as one "
                                    + "in the test tree, and a gate over half the code is a gate that half "
                                    + "works",
                            tree)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no source in either tree declares an import whose simple name it never uses")
    void noSourceDeclaresAnUnusedImport() {
        final List<String> violations = new ArrayList<>();
        for (final Path source : javaSources()) {
            for (final String unused : unusedImports(lines(source))) {
                violations.add(ROOT.relativize(source) + ":" + unused);
            }
        }

        assertThat(violations)
                .as(
                        "each of these declares a dependency the file does not have. The compiler does not "
                                + "warn, -Werror does not catch it and doclint has no opinion, so this is "
                                + "the only place it is checked. Remediation is to delete the import - not "
                                + "to add a use for it, and not to suppress this assertion. Note that a "
                                + "Javadoc reference counts as a use, so anything reported here is genuinely "
                                + "unreferenced. Occurrences: %s",
                        violations)
                .isEmpty();
    }

    @Test
    @DisplayName("no source uses an on-demand import, so the rule's one blind spot is unoccupied")
    void onDemandImportsAreAbsent() {
        final List<String> wildcards = new ArrayList<>();
        for (final Path source : javaSources()) {
            final List<String> content = lines(source);
            for (int index = 0; index < content.size(); index++) {
                final Matcher matcher = IMPORT.matcher(content.get(index));
                if (matcher.matches() && matcher.group(1).endsWith(".*")) {
                    wildcards.add(ROOT.relativize(source) + ":" + (index + 1) + " " + matcher.group(1));
                }
            }
        }

        assertThat(wildcards)
                .as(
                        "a wildcard introduces no single name, so no textual rule can decide whether it is "
                                + "used and noSourceDeclaresAnUnusedImport() cannot see behind one. The tree "
                                + "contains none, and recording that here is what keeps the blind spot from "
                                + "opening quietly. This is an observation about the tree's existing style, "
                                + "not a new prohibition: if a wildcard is ever wanted, this assertion is "
                                + "the place to state the reason. Occurrences: %s",
                        wildcards)
                .isEmpty();
    }

    @Test
    @DisplayName("the rule reports what it should and keeps what it should, proved on known sources")
    void theRuleItselfIsExercised() {
        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.List;",
                        "",
                        "final class Sample {",
                        "    private List<String> values;",
                        "}")))
                .as("an import the body names is used")
                .isEmpty();

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.List;",
                        "import java.util.Map;",
                        "",
                        "final class Sample {",
                        "    private List<String> values;",
                        "}")))
                .as("an import no part of the body names is reported, with its line and qualified name")
                .containsExactly("4:java.util.Map");

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.Map;",
                        "",
                        "/** Documented against {@link Map}. */",
                        "final class Sample {",
                        "}")))
                .as(
                        "a Javadoc reference is a use: removing this import would fail the doclint gate, so "
                                + "a rule that reported it would be asking for a broken build")
                .isEmpty();

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import static org.assertj.core.api.Assertions.assertThat;",
                        "import static org.mockito.Mockito.verify;",
                        "",
                        "final class Sample {",
                        "    void check() {",
                        "        assertThat(1).isEqualTo(1);",
                        "    }",
                        "}")))
                .as("a static import is judged on its member name, exactly like a type import")
                .containsExactly("4:org.mockito.Mockito.verify");

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.Map;",
                        "import java.util.List;",
                        "",
                        "final class Sample {",
                        "    private List<String> values;",
                        "}")))
                .as(
                        "the body starts after the LAST import, so an earlier import's name appearing in a "
                                + "later import line can never be mistaken for a use. Were the boundary set "
                                + "at the first import instead, Map would read as used here")
                .containsExactly("3:java.util.Map");

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.List;",
                        "import java.util.ArrayList;",
                        "",
                        "final class Sample {",
                        "    private List<String> values = new java.util.ArrayList<>();",
                        "}")))
                .as("a fully qualified use contains the simple name as a word, so the import is kept")
                .isEmpty();

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.List;",
                        "",
                        "final class Sample {",
                        "    private Listener listener;",
                        "}")))
                .as(
                        "matching is on whole words, so List must not be counted as used by Listener - a "
                                + "substring rule would silently keep every import with a common prefix")
                .containsExactly("3:java.util.List");

        assertThat(unusedImports(List.of(
                        "package com.cardemo.sample;",
                        "",
                        "import java.util.*;",
                        "",
                        "final class Sample {",
                        "}")))
                .as("an on-demand import is skipped rather than reported, because no name can be attributed")
                .isEmpty();

        assertThat(unusedImports(List.of("package com.cardemo.sample;", "", "final class Sample {", "}")))
                .as("a source with no import at all yields no violation and no exception")
                .isEmpty();
    }
}
