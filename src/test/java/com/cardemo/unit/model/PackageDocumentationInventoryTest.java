/*
 * ******************************************************************
 * Program     : PackageDocumentationInventoryTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Enforces the bijection between packages and package
 *               documentation, so the drift this test replaces cannot
 *               recur silently. Every package under
 *               src/main/java/com/cardemo that contains a type must
 *               carry exactly one package-info.java, and no package
 *               that contains no type may carry one - bar the
 *               intermediate packages named in DOCUMENTED_CONTAINERS,
 *               which are documented on purpose and whose entries this
 *               test also keeps honest. A fixed census in prose - "132
 *               .java files, of which exactly 14 are package-info.java",
 *               with a per-package breakdown and an assertion that the
 *               nine service leaves are "documented by their parent" -
 *               cannot stay true: the parent packages contain no types
 *               and carry no documentation, so such leaves would be
 *               documented by nothing at all. A census in a comment is a
 *               claim no build step maintains. This test is the build
 *               step.
 * Source      : CONTRIBUTING.md:L33-L34 (focus the change; ensure local
 *               tests pass) @ 7756d89
 * Source      : app/cbl/CBACT04C.cbl:L1-L21 (the universal Apache-2.0
 *               source banner convention every new file carries)
 *               @ 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts that package documentation exists for exactly the packages that need it, and that each such document
 * carries the content Rule 1 Clause E requires.
 *
 * <p><strong>Why this is a test rather than a paragraph.</strong> Describing the tree's own shape with
 * hard-coded counts in {@code CardDemoApplication} and in the root {@code package-info.java}, including the claim
 * that a fixed number of documentation files exists "at exactly these locations, and nowhere else", is a figure
 * nothing maintains, so it drifts, and the drift is invisible: adding a package produces no error and
 * no warning. This class holds an invariant instead. The <em>counts</em> are free to change; the
 * <em>equality</em> is not.
 *
 * <p><strong>What is checked, and deliberately not checked.</strong> Existence and the presence of each mandated
 * section heading are checked, because both are mechanically decidable. The <em>accuracy</em> of a document's
 * prose is not, and this test does not pretend otherwise - that remains a review concern. What it does prevent is
 * a package with no documentation at all, a documentation file for a package that has been emptied, and a
 * document that omits one of the four topics the rule names.
 *
 * <p><strong>The one exemption, and why it is still an invariant.</strong> An intermediate package holds no type
 * and so falls outside the bijection's purpose - it cannot be emptied and its document cannot go stale that way -
 * yet it can legitimately own facts that belong to a whole layer rather than to any leaf. Those packages are
 * named in {@link #DOCUMENTED_CONTAINERS} and are exempted from the "no type-less package is documented" rule
 * only - a list deliberately kept as short as the tree allows. {@code com.cardemo.batch} was on it until the
 * batch layer's shared object-key namespace contract was published there; the package then earned its document
 * by holding a type, and the exemption became a claim the tree contradicts, so it was withdrawn rather than
 * left in place. A stale exemption is precisely what would let a genuinely undocumented package hide behind
 * it. The exemption is bounded by two further assertions of its own: every entry must genuinely hold no type,
 * and every entry must genuinely be documented. A type-less package that is not on the list still fails, and a
 * listed package that stops being either type-less or documented also fails, so nothing can drift in behind it.
 *
 * <p><strong>Side effects.</strong> None. It reads the source tree from the module root and writes nothing.
 */
@DisplayName("Package documentation inventory - one document per package that contains a type")
class PackageDocumentationInventoryTest {

    /** Root of the application source tree, relative to the module root that Surefire runs from. */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "cardemo");

    /** The file name Java reserves for package documentation. */
    private static final String PACKAGE_INFO = "package-info.java";

    /**
     * Intermediate packages that hold no type of their own and are nevertheless documented on purpose.
     *
     * <p>The bijection below is "one document per package that contains a type", and it exists to stop two
     * specific kinds of rot: a package whose types nothing documents, and a document for a package that has been
     * emptied. An intermediate package is a third case that neither of those describes. It contains no type, so
     * it can never be emptied and its document can never go stale that way; but it does own layer-wide facts -
     * one toolchain, one configuration contract, one exception vocabulary, one paragraph-correspondence mandate -
     * that are properties of the layer rather than of any single leaf, and that would otherwise have to be
     * repeated in every leaf or stated nowhere. Repeating them is the duplication Rule 1 Clause C forbids;
     * stating them nowhere fails Rule 1 Clause E for the layer.
     *
     * <p>So the exemption is an <strong>allow-list, not a relaxation</strong>. Only the paths named here may
     * carry a document without holding a type, every other type-less package is still rejected, and the two
     * assertions immediately below keep the list itself honest: each entry must really be type-less, and each
     * entry must really be documented. An intermediate package that is neither cannot hide here.
     *
     * <p>{@code com/cardemo/model} is deliberately <em>absent</em> from this list. Its leaves are cohesive
     * enough to document themselves, so a container document there would be a summary with nothing to
     * summarise.
     *
     * <p>{@code com/cardemo/service} and {@code com/cardemo/batch} are different in kind, and both are
     * allow-listed. The service root spans nine leaves and 21 beans translated from 17 separate COBOL
     * programs, and the invariants that bind all 21 are what its document holds. The batch root spans four
     * leaves whose shared facts are properties of the layer rather than of any leaf - one exit-code
     * vocabulary of 0, 4, 8 and 12, one fixed-width record geometry contract, one generation-to-object-key
     * scheme over seven GDG bases, and one set of preserved source quirks - so stating them per leaf would be
     * the duplication Rule 1 Clause C forbids and stating them nowhere would fail Clause E for the layer.
     *
     * <p><strong>{@code com/cardemo/batch} does not belong on this list.</strong> The reason that applies to
     * {@code model} - a summary with nothing to summarise - does not describe the batch root, which carries
     * both a layer document and a type. The exemption
     * stays honest either way, because the two assertions below still require every entry here to be
     * genuinely type-less and genuinely documented.
     */
    private static final List<Path> DOCUMENTED_CONTAINERS =
            List.of(SOURCE_ROOT.resolve("service"));

    /**
     * The four topics Rule 1 Clause E names, as the heading text each document must carry. Matched on the
     * distinctive phrase rather than the exact heading, so a document may say "How to run, build and test" or
     * "How to build, run and test" without failing for word order.
     */
    private static final List<String> REQUIRED_TOPICS = List.of(
            "What it does",
            "build and test",
            "Key configuration and defaults",
            "Common failure modes and troubleshooting");

    /**
     * Lists every directory under the source root, including the root itself.
     *
     * @return the directories, in a stable order
     */
    private static List<Path> allPackageDirectories() {
        try (Stream<Path> entries = Files.walk(SOURCE_ROOT)) {
            return entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not walk " + SOURCE_ROOT, unreadable);
        }
    }

    /**
     * Reports whether a directory declares at least one type, ignoring package documentation.
     *
     * @param directory the directory to inspect
     * @return {@code true} when it contains a {@code .java} file other than {@code package-info.java}
     */
    private static boolean containsType(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.anyMatch(path -> path.getFileName().toString().endsWith(".java")
                    && !PACKAGE_INFO.equals(path.getFileName().toString()));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not list " + directory, unreadable);
        }
    }

    /**
     * Reports whether a directory carries package documentation.
     *
     * @param directory the directory to inspect
     * @return {@code true} when {@code package-info.java} is present
     */
    private static boolean containsDocumentation(Path directory) {
        return Files.isRegularFile(directory.resolve(PACKAGE_INFO));
    }

    /**
     * Reads one file as text.
     *
     * @param file the file to read
     * @return its contents
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("could not read " + file, unreadable);
        }
    }

    @Nested
    @DisplayName("the bijection")
    class Bijection {

        @Test
        @DisplayName("the source tree is present, so the following assertions have a subject")
        void theSourceTreeIsPresent() {
            assertThat(SOURCE_ROOT).isDirectory();
            assertThat(allPackageDirectories())
                    .as("if this were empty every other assertion here would pass vacuously")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("every package that contains a type carries package-info.java")
        void everyPackageWithATypeIsDocumented() {
            List<String> undocumented = new ArrayList<>();
            for (Path directory : allPackageDirectories()) {
                if (containsType(directory) && !containsDocumentation(directory)) {
                    undocumented.add(directory.toString());
                }
            }

            assertThat(undocumented)
                    .as("Rule 1 Clause E requires a docstring per module; these packages have types but no "
                            + "package-info.java, so nothing documents them - not even a parent, because the "
                            + "parent packages here contain no types")
                    .isEmpty();
        }

        @Test
        @DisplayName("no package without a type carries package-info.java, bar the allow-listed containers")
        void noEmptyPackageIsDocumented() {
            List<String> orphaned = new ArrayList<>();
            for (Path directory : allPackageDirectories()) {
                if (DOCUMENTED_CONTAINERS.contains(directory)) {
                    continue;
                }
                if (!containsType(directory) && containsDocumentation(directory)) {
                    orphaned.add(directory.toString());
                }
            }

            assertThat(orphaned)
                    .as("a document for a package that has been emptied describes nothing and will not be "
                            + "maintained; the intermediate package model contains no types and correctly "
                            + "carries none, and the only type-less packages allowed a document are the "
                            + "service and batch layer roots named in DOCUMENTED_CONTAINERS")
                    .isEmpty();
        }

        @Test
        @DisplayName("every allow-listed container really is type-less, so the allow-list masks no real package")
        void everyAllowListedContainerIsGenuinelyTypeLess() {
            List<String> notContainers = new ArrayList<>();
            for (Path container : DOCUMENTED_CONTAINERS) {
                if (!Files.isDirectory(container) || containsType(container)) {
                    notContainers.add(container.toString());
                }
            }

            assertThat(notContainers)
                    .as("an entry that has gained a type is covered by the bijection proper and must be removed "
                            + "from DOCUMENTED_CONTAINERS, or the exemption starts hiding a real package")
                    .isEmpty();
        }

        @Test
        @DisplayName("every allow-listed container really is documented, so the allow-list cannot rot")
        void everyAllowListedContainerIsDocumented() {
            List<String> undocumented = new ArrayList<>();
            for (Path container : DOCUMENTED_CONTAINERS) {
                if (!containsDocumentation(container)) {
                    undocumented.add(container.toString());
                }
            }

            assertThat(undocumented)
                    .as("these packages are exempted from the bijection precisely because they are documented on "
                            + "purpose; an exemption for a package carrying no document exempts nothing and is "
                            + "the drift this class exists to catch")
                    .isEmpty();
        }

        @Test
        @DisplayName("the two counts are equal, which is the invariant the withdrawn census replaced")
        void theCountsAreEqual() {
            long withType = allPackageDirectories().stream()
                    .filter(PackageDocumentationInventoryTest::containsType)
                    .count();
            long documented = allPackageDirectories().stream()
                    .filter(PackageDocumentationInventoryTest::containsDocumentation)
                    .count();
            long documentedContainers = DOCUMENTED_CONTAINERS.stream()
                    .filter(PackageDocumentationInventoryTest::containsDocumentation)
                    .count();

            assertThat(documented)
                    .as("the counts may change together; they may not diverge. The allow-listed container "
                            + "documents are added to the right-hand side rather than subtracted from the left, "
                            + "so a container document is only ever balanced by an entry in DOCUMENTED_CONTAINERS")
                    .isEqualTo(withType + documentedContainers);
        }
    }

    /**
     * Reports whether every occurrence of a phrase on a line sits inside quotation marks.
     *
     * <p>A gate has to be able to NAME the wording it forbids, and a retraction has to be able to quote the
     * superseded claim it withdraws. A scan that counted those quotations reported the enforcement and the
     * retraction as the offence, and could only be satisfied by deleting them. A phrase inside quotation
     * marks is being <em>named</em>; a phrase written as prose is being <em>asserted</em>. Only the second is
     * a claim.
     *
     * @param line   the source line, never {@code null}
     * @param phrase the matched phrase, never {@code null}
     * @return {@code true} when every occurrence of the phrase on that line is quoted
     */
    private static boolean everyOccurrenceIsQuoted(final String line, final String phrase) {
        int from = 0;
        while (true) {
            final int at = line.indexOf(phrase, from);
            if (at < 0) {
                return true;
            }
            if (line.chars().limit(at).filter(c -> c == '"').count() % 2 == 0) {
                return false;
            }
            from = at + phrase.length();
        }
    }

    @Nested
    @DisplayName("the content Rule 1 Clause E mandates")
    class MandatedContent {

        /**
         * Lists every package documentation file in the tree.
         *
         * @return the documents, in a stable order
         */
        private List<Path> documents() {
            return allPackageDirectories().stream()
                    .filter(PackageDocumentationInventoryTest::containsDocumentation)
                    .map(directory -> directory.resolve(PACKAGE_INFO))
                    .toList();
        }

        @Test
        @DisplayName("every document covers all four mandated topics")
        void everyDocumentCoversTheMandatedTopics() {
            List<String> gaps = new ArrayList<>();
            for (Path document : documents()) {
                String body = read(document);
                for (String topic : REQUIRED_TOPICS) {
                    if (!body.contains(topic)) {
                        gaps.add(document + " omits \"" + topic + "\"");
                    }
                }
            }

            assertThat(gaps)
                    .as("Clause E names four topics: what it does, how to build/run/test, key configuration and "
                            + "defaults, and common failure modes and troubleshooting")
                    .isEmpty();
        }

        @Test
        @DisplayName("every document carries the repository's Apache-2.0 banner and a package declaration")
        void everyDocumentCarriesTheBannerConvention() {
            List<String> gaps = new ArrayList<>();
            for (Path document : documents()) {
                String body = read(document);
                if (!body.contains("Apache License, Version 2.0")) {
                    gaps.add(document + " omits the Apache-2.0 notice");
                }
                if (!body.contains("Copyright Amazon.com, Inc. or its affiliates.")) {
                    gaps.add(document + " omits the copyright line");
                }
                if (!body.contains(" * Package     : com.cardemo")) {
                    gaps.add(document + " omits the Package banner line");
                }
                if (!body.contains("package com.cardemo")) {
                    gaps.add(document + " omits its package declaration");
                }
            }

            assertThat(gaps)
                    .as("the banner convention is universal in the legacy corpus - "
                            + "app/cbl/CBACT04C.cbl:L1-L21 is the canonical form - and every new file carries it")
                    .isEmpty();
        }

        @Test
        @DisplayName("no document states a global cardinality of retained parity no-ops")
        void noDocumentStatesAGlobalNoOpTally() {
            List<String> offenders = new ArrayList<>();
            for (Path document : documents()) {
                String body = read(document);
                // These phrasings each asserted a fixed, hand-maintained count, and they disagreed with one
                // another: three in two files, five in a third. The register of record is now the per-artefact
                // marker at each declaration, so no document may reintroduce a tally.
                for (String tally : List.of(
                        "All three retained-for-parity artefacts",
                        "The tree has five of them",
                        "the three documented sites where")) {
                    if (body.contains(tally)) {
                        offenders.add(document + " states \"" + tally + "\"");
                    }
                }
            }

            assertThat(offenders)
                    .as("a census maintained by hand across unrelated comments is a claim no build step "
                            + "maintains, which is how the contradictory counts arose")
                    .isEmpty();
        }

        @Test
        @DisplayName("no document describes the decision log or traceability matrix as planned or absent")
        void noDocumentClaimsAnAuthoredRegisterIsAbsent() {
            // This guard has been inverted with its premise. It once forbade "recorded in DECISION_LOG.md",
            // which was correct while neither register existed on disk. Both are now authored at the
            // repository root, so that rule had turned into a requirement to describe delivered evidence as
            // pending. What is a defect now is the opposite claim: calling either register planned, absent or
            // unavailable understates the record and sends a reader away from a document that is there.
            List<String> offenders = new ArrayList<>();
            for (Path document : documents()) {
                String body = read(document);
                for (String claim : List.of(
                        "planned {@code DECISION_LOG.md}",
                        "planned {@code TRACEABILITY_MATRIX.md}",
                        "{@code DECISION_LOG.md} does not exist",
                        "{@code TRACEABILITY_MATRIX.md} does not exist")) {
                    // A quoted occurrence is the phrase being NAMED - by a retraction that records a
                    // superseded claim, or by a gate that has to spell out what it forbids - not asserted.
                    if (body.lines().anyMatch(line -> line.contains(claim)
                            && !everyOccurrenceIsQuoted(line, claim))) {
                        offenders.add(document + " states \"" + claim + "\"");
                    }
                }
            }

            assertThat(offenders)
                    .as("both registers are authored at the repository root, so calling either planned or "
                            + "non-existent is false in the understating direction; state the obligation "
                            + "without the qualifier instead")
                    .isEmpty();
            for (String register : List.of("DECISION_LOG.md", "TRACEABILITY_MATRIX.md")) {
                assertThat(Path.of(register))
                        .as("%s being on disk is the premise of this inversion. If it is deleted, restore the "
                                + "former rule deliberately rather than leaving a guard whose premise has "
                                + "silently reversed a second time", register)
                        .exists();
            }
        }
    }
}
