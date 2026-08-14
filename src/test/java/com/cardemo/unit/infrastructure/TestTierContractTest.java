/*
 * ****************************************************************************
 * Test        : TestTierContractTest
 * Application : CardDemo
 * Type        : Java unit test - test-tier structural contract
 * Function    : Assert that the test tree's own shape holds: every source sits
 *               inside a scope boundary the Agent Action Plan declares, every
 *               support type is shaped so it can never masquerade as a suite,
 *               and the working directory the file-reading tests depend on is
 *               the repository root rather than an accident.
 * Source      : docs/technical-specifications.md 0.3.1.3 - the three declared
 *               CREATE patterns src/test/java/com/cardemo/unit/**,
 *               src/test/java/com/cardemo/integration/** and
 *               src/test/java/com/cardemo/e2e/**
 *               pom.xml - the Surefire and Failsafe include patterns and the
 *               pinned workingDirectory
 * ****************************************************************************
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Structural contract for the test tree itself.
 *
 * <p>Three properties of this tree are relied upon everywhere and were, until now, relied upon silently.
 * Each is asserted here because each has already been observed to go wrong.
 *
 * <ol>
 *   <li><strong>Scope membership.</strong> A review recorded three helper types -
 *       {@code ReflectionCensus}, {@code ValidationSupport} and {@code MenuServiceTestSupport} - as
 *       "out-of-inventory support types" through which in-scope tests execute. The Agent Action Plan
 *       declares three CREATE patterns for this tree, each a trailing wildcard over a concrete directory
 *       prefix: {@code src/test/java/com/cardemo/unit/**}, {@code .../integration/**} and
 *       {@code .../e2e/**}. All three helpers sit under the first of them, so they are inside a declared
 *       boundary rather than outside one. That is an assertion about paths, so it is made as one here
 *       instead of being argued in prose - and it holds for every future file too, which is the part prose
 *       cannot do.</li>
 *   <li><strong>Support-type shape.</strong> A helper that is in scope can still be wrong if it is shaped
 *       like a suite. A type carrying {@code @Test} methods but a name the runner's include patterns do not
 *       match is the dangerous case: it looks like a test, reports nothing, and its assertions never run.
 *       Every non-suite type in this tree is therefore required to declare no test method at all.</li>
 *   <li><strong>Working directory.</strong> Thirteen classes read repository files at run time - the frozen
 *       copybooks, the migrations, {@code .env.example}, {@code pom.xml} - and resolve them relative to the
 *       process working directory. That resolution was correct only because it matched Surefire's default.
 *       {@code pom.xml} now pins {@code workingDirectory} explicitly, and this class asserts the pin took
 *       effect, so a runner that forks elsewhere fails here, with a message naming the cause, rather than
 *       fifty assertions later with a missing file.</li>
 *   </ol>
 */
@DisplayName("Test tier contract - the test tree's own structural invariants")
final class TestTierContractTest {

    /** The Agent Action Plan's declared CREATE prefixes for this tree, from section 0.3.1.3. */
    private static final List<String> DECLARED_TEST_PREFIXES = List.of(
            "src/test/java/com/cardemo/unit/",
            "src/test/java/com/cardemo/integration/",
            "src/test/java/com/cardemo/e2e/");

    /** Suffixes the Surefire and Failsafe include patterns match, so a file carrying one IS a suite. */
    private static final List<String> SUITE_SUFFIXES = List.of("Test.java", "Tests.java");

    /** The repository root, located by structure rather than assumed from the working directory. */
    private static final Path ROOT = repositoryRoot();

    /**
     * Walks upward from the working directory to the repository root.
     *
     * <p>Deliberately does not trust the working directory to already <em>be</em> the root: that is the very
     * property under test below, and a locator that assumed it could not detect its absence.
     *
     * @return the directory holding {@code pom.xml}, {@code src/} and {@code app/}
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isDirectory(candidate.resolve("src"))
                    && Files.isDirectory(candidate.resolve("app"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds pom.xml, src/ and app/. Run this test with "
                        + "the repository root, or any directory beneath it, as the working directory.");
    }

    /**
     * Every {@code .java} file in the test tree, as a repository-relative slash-separated path.
     *
     * @return the paths, in a stable order
     */
    private static List<String> testTreeSources() {
        final Path base = ROOT.resolve("src/test/java");
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(path -> ROOT.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot walk " + base, cause);
        }
    }

    /**
     * Reads one repository file as UTF-8 text.
     *
     * @param relativePath path relative to the repository root
     * @return the file's whole content
     */
    private static String read(final String relativePath) {
        try {
            return Files.readString(ROOT.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + relativePath, cause);
        }
    }

    /** Scope membership: no test source may sit outside a declared CREATE prefix. */
    @Nested
    @DisplayName("every test source sits inside a declared scope boundary")
    final class ScopeMembership {

        @Test
        @DisplayName("the tree is non-empty, so the membership scan below cannot pass vacuously")
        void theTreeIsNotEmpty() {
            assertThat(testTreeSources())
                    .as("the test tree walk found no .java file at all, which would make every other "
                            + "assertion in this class meaningless")
                    .hasSizeGreaterThan(50);
        }

        @Test
        @DisplayName("no source lies outside unit/, integration/ or e2e/")
        void everySourceIsUnderADeclaredPrefix() {
            final List<String> outside = testTreeSources().stream()
                    .filter(path -> DECLARED_TEST_PREFIXES.stream().noneMatch(path::startsWith))
                    .toList();

            assertThat(outside)
                    .as("each of these test sources sits outside every CREATE pattern the Agent Action "
                            + "Plan declares for this tree (%s). A file outside the declared boundary is "
                            + "unreviewed by construction: no scope list names it, so nothing obliges "
                            + "anyone to look at it. Move it under a declared prefix",
                            DECLARED_TEST_PREFIXES)
                    .isEmpty();
        }

        @Test
        @DisplayName("the three helper types a review flagged are each inside the unit/ boundary")
        void theFlaggedSupportTypesAreInsideTheBoundary() {
            final List<String> flagged = List.of(
                    "src/test/java/com/cardemo/unit/model/ReflectionCensus.java",
                    "src/test/java/com/cardemo/unit/model/ValidationSupport.java",
                    "src/test/java/com/cardemo/unit/service/MenuServiceTestSupport.java");

            for (final String helper : flagged) {
                assertThat(ROOT.resolve(helper))
                        .as("%s was recorded as an out-of-inventory support type. If it has been removed "
                                + "or moved, update this list rather than leaving it wrong", helper)
                        .exists();
                assertThat(DECLARED_TEST_PREFIXES.stream().anyMatch(helper::startsWith))
                        .as("%s must sit under one of the declared CREATE prefixes %s for its scope "
                                + "membership to be demonstrable rather than argued", helper,
                                DECLARED_TEST_PREFIXES)
                        .isTrue();
            }
        }
    }

    /** Support-type shape: a helper must not be able to hide unrun assertions. */
    @Nested
    @DisplayName("no support type can masquerade as a suite")
    final class SupportTypeShape {

        @Test
        @DisplayName("a type the runner does not collect declares no test method")
        void nonSuiteTypesDeclareNoTestMethod() {
            final List<String> silent = new ArrayList<>();
            for (final String path : testTreeSources()) {
                final String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (SUITE_SUFFIXES.stream().anyMatch(fileName::endsWith)) {
                    continue;
                }
                final String body = read(path);
                final long tests = body.lines()
                        .map(String::strip)
                        .filter(line -> line.equals("@Test") || line.startsWith("@Test("))
                        .count();
                if (tests > 0) {
                    silent.add(path + " declares " + tests + " @Test method(s)");
                }
            }

            assertThat(silent)
                    .as("each of these files declares test methods but has a name that neither the "
                            + "Surefire nor the Failsafe include patterns match, so the runner never "
                            + "collects it and every assertion inside it silently does not run - the "
                            + "worst possible failure mode for a test, because the build stays green. "
                            + "Rename the file to end in Test.java, or move the assertions into a "
                            + "collected suite")
                    .isEmpty();
        }
    }

    /** Method ordering: permitted only where a between-test mechanism is the subject, and only if declared. */
    @Nested
    @DisplayName("a suite that orders its methods says why, and no route suite orders them at all")
    final class MethodOrderingIsJustified {

        /** The annotation that imposes an order on a suite's methods. */
        private static final String ORDERING_ANNOTATION = "@TestMethodOrder";

        /** The declaration a suite must carry to be allowed to impose one. */
        private static final String ORDERING_JUSTIFICATION = "ORDERING IS DELIBERATE";

        /** The suite whose scenarios must each be runnable on their own, named because that was the defect. */
        private static final String ROUTE_SUITE =
                "src/test/java/com/cardemo/e2e/OnlineTransactionE2ETest.java";

        /**
         * Whether a source <em>uses</em> the ordering annotation, as opposed to mentioning it.
         *
         * <p>The distinction is not pedantic and it is why this is a method rather than a
         * {@code contains} call. The suite that had its ordering removed now documents that removal, and
         * this class names the annotation in a constant so it can report it. A plain text search would
         * classify both as ordered - the first for saying it is not, the second for being the rule - so the
         * rule would fail on precisely the two files that prove it holds. An annotation is used only where it
         * opens a line, so that is what is matched.
         *
         * @param body the source text
         * @return {@code true} when a line begins with the ordering annotation
         */
        private boolean imposesAnOrder(final String body) {
            return body.lines().map(String::strip).anyMatch(line -> line.startsWith(ORDERING_ANNOTATION));
        }

        /**
         * Every suite imposing an order declares, in its own text, why the order is the subject.
         *
         * <p><strong>A declaration requirement rather than a prohibition, and rather than a list of exempt
         * file names.</strong> Ordering is occasionally correct: when the behaviour under test is what
         * happens <em>between</em> two tests, the assertion cannot live inside either one of them. A blanket
         * ban would delete that assertion. A list of exempt names would go stale the moment a file was
         * renamed and would say nothing about why any name was on it. Requiring the reason at the site
         * scales to a suite nobody has written yet and puts the justification where a reader meets the
         * annotation.
         *
         * <p>What this refuses is the far commoner case: ordering used to let each test inherit a
         * precondition an earlier test happened to leave behind. That arrangement cannot be run one test at
         * a time, and its first failure masks every test after it.
         */
        @Test
        @DisplayName("every suite imposing an order declares the reason at the site")
        void orderedSuitesDeclareWhy() {
            final List<String> undeclared = new ArrayList<>();
            int ordered = 0;
            for (final String path : testTreeSources()) {
                final String body = read(path);
                if (!imposesAnOrder(body)) {
                    continue;
                }
                ordered++;
                if (!body.contains(ORDERING_JUSTIFICATION)) {
                    undeclared.add(path);
                }
            }

            assertThat(ordered)
                    .as("at least one suite must impose an order, or this rule guards an empty set and "
                            + "passes without asserting anything")
                    .isPositive();
            assertThat(undeclared)
                    .as("each of these suites imposes a method order without declaring '%s' and the reason "
                            + "for it. Either the order is the subject - a between-test mechanism, where one "
                            + "method must act and the next must observe - in which case say so at the site, "
                            + "or it is inherited setup, in which case each test should provision what it "
                            + "needs and the annotation should go",
                            ORDERING_JUSTIFICATION)
                    .isEmpty();
        }

        /**
         * The route suite imposes no order and pins no method position.
         *
         * <p>Asserted by name because this is the regression it closes rather than a general principle. That
         * suite covers seventeen REST operations, and the first thing anyone changing one of them does is run
         * its scenario alone - which an imposed order does not support. Twenty-two
         * {@code @Order} positions over principals created once for the whole class would mean a failure early
         * in the sequence masked every scenario after it, and one scenario deleting a principal the others
         * authenticated with.
         */
        @Test
        @DisplayName("the seventeen-operation route suite imposes no order and pins no position")
        void theRouteSuiteImposesNoOrder() {
            final String body = read(ROUTE_SUITE);

            assertThat(imposesAnOrder(body))
                    .as("%s must not impose a method order: every route scenario has to be runnable on its "
                            + "own, and an order is what makes that unsupported", ROUTE_SUITE)
                    .isFalse();
            assertThat(body.lines().map(String::strip).filter(line -> line.startsWith("@Order(")).count())
                    .as("and it must pin no individual method position either - the annotation and the "
                            + "positions are removable only together, and leaving the positions behind "
                            + "would restore the coupling the moment the class annotation came back")
                    .isZero();
            assertThat(body.lines().map(String::strip)
                    .anyMatch(line -> line.startsWith("@TestInstance(")))
                    .as("nor may it hold principals across methods: a per-class instance lifecycle is what "
                            + "let one scenario's deletion be observed by the next")
                    .isFalse();
            assertThat(body.lines().map(String::strip).anyMatch(line -> line.equals("@BeforeEach")))
                    .as("and each test must provision what it needs for itself")
                    .isTrue();
        }
    }

    /** Working directory: the contract the file-reading tests depend on. */
    @Nested
    @DisplayName("the working directory is the repository root, by declaration not by luck")
    final class WorkingDirectoryContract {

        @Test
        @DisplayName("the process working directory IS the repository root")
        void workingDirectoryIsTheRepositoryRoot() {
            final Path actual = Path.of("").toAbsolutePath().normalize();

            assertThat(actual)
                    .as("this suite forked with %s as its working directory, but the repository root is "
                            + "%s. Test classes in this tree resolve repository-relative paths - the "
                            + "frozen copybooks under app/, the Flyway migrations, .env.example, pom.xml - "
                            + "against the working directory, so they would fail with a missing file far "
                            + "from the real cause. pom.xml pins <workingDirectory> for both Surefire and "
                            + "Failsafe; if that pin has been removed or the runner ignores it, restore it "
                            + "rather than adjusting the paths", actual, ROOT)
                    .isEqualTo(ROOT);
        }

        @Test
        @DisplayName("both test plugins declare the pin, so the guarantee is not incidental")
        void bothTestPluginsPinTheWorkingDirectory() {
            final String pom = read("pom.xml");

            assertThat(pom.split("<workingDirectory>\\$\\{project\\.basedir\\}</workingDirectory>", -1))
                    .as("pom.xml must pin <workingDirectory> to ${project.basedir} in BOTH the Surefire "
                            + "and the Failsafe configuration. Pinning one leaves the other tier relying "
                            + "on a plugin default, which is the condition this assertion exists to "
                            + "prevent")
                    .hasSize(3);
        }

        @Test
        @DisplayName("a repository-relative read resolves, which is what the pin buys")
        void aRepositoryRelativeReadResolves() {
            assertThat(Path.of("app", "cbl", "COCRDSLC.cbl"))
                    .as("a bare repository-relative path must resolve from the working directory, because "
                            + "thirteen classes in this tree read files that way")
                    .exists();
            assertThat(Path.of("src", "main", "resources", "db", "migration", "V1__create_schema.sql"))
                    .as("the migration directory must resolve the same way")
                    .exists();
        }
    }
}
