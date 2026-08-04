/*
 * ****************************************************************************
 * Program     : BuildProvenanceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Holds pom.xml to a build a reviewer can reason about from the
 *               file alone. One bill of materials per coordinate, so no version
 *               is decided by declaration order; the coverage pin, its
 *               overridden bytecode reader and its runtime agent held to the
 *               three exact values the documented Java 25 exception names; and
 *               no range, LATEST or RELEASE version anywhere.
 * Source      : samples/BATCMP.jcl, samples/CICCMP.jcl, samples/BMSCMP.jcl (the
 *                 three z/OS compile templates this single build supersedes)
 *               + samples/BUILDBAT.prc, samples/BUILDBMS.prc,
 *                 samples/BUILDONL.prc (the three build procedures the pinned
 *                 wrapper supersedes)
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Asserts the build-provenance properties of {@code pom.xml} that a reader must be able to
 * establish without running Maven.
 *
 * <p>Two defects motivated this file, and both were invisible from the POM text.
 *
 * <p><strong>A bill of materials imported twice.</strong> An earlier revision imported
 * {@code io.netty:netty-bom} in this project's {@code dependencyManagement} at
 * {@code ${netty.version}}. The Spring Boot parent already imports that same BOM at that same
 * property, so one BOM was imported twice at one version and which import won was settled by
 * declaration order rather than by intent. A reviewer reading the file could not tell which
 * declaration was load bearing. The duplicate is gone and the property is the single lever;
 * {@code dependency:list} filtered to {@code io.netty} reports the same ten artifacts at
 * {@code 4.1.136.Final} either way. {@link OneBomPerCoordinate} keeps it gone.
 *
 * <p><strong>A version split that looks like an oversight and is not.</strong> The coverage plugin
 * is pinned at {@code 0.8.12} because the requirement pins it there, while its bytecode reader is
 * overridden to ASM {@code 9.9} and its runtime agent to {@code 0.8.14}, because ASM 9.7 - what
 * 0.8.12 ships - has a class file ceiling of 67 and this toolchain emits 69. Raising the pin would
 * resolve unilaterally the divergence the requirement says to record; lowering the agent would
 * reinstate the failure. {@link DocumentedJavaTwentyFiveException} pins all three values so the
 * split cannot be quietly "tidied" in either direction.
 *
 * <p>The POM is parsed with line-oriented regular expressions rather than an XML parser on purpose.
 * The assertions are about the <em>text a reviewer reads</em> - which coordinates appear, and with
 * which literal version strings - not about the effective model Maven computes, which is exactly
 * the thing a reader cannot see.
 */
@DisplayName("pom.xml - a build a reviewer can reason about from the file alone")
final class BuildProvenanceTest {

    /** {@code <groupId>} element with its text content. */
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>([^<]+)</groupId>");

    /** {@code <artifactId>} element with its text content. */
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    /** {@code <version>} element with its text content. */
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

    /** A property declaration whose name ends in {@code .version}. */
    private static final Pattern VERSION_PROPERTY =
            Pattern.compile("<([a-zA-Z0-9._-]+\\.version)>([^<]+)</\\1>");

    /** The one BOM import this project declares itself. */
    private static final String AWS_BOM = "io.awspring.cloud:spring-cloud-aws-dependencies";

    /** The coordinate whose duplicate import this test exists to keep out. */
    private static final String NETTY_BOM = "io.netty:netty-bom";

    /** The repository root, located once and reused. */
    private static final Path ROOT = repositoryRoot();

    /** Every line of {@code pom.xml}, in order. */
    private static final List<String> POM = pomLines();

    /** Every {@code *.version} property, name to literal value. */
    private static final Map<String, String> VERSION_PROPERTIES = versionProperties();

    /**
     * Walks upward from the working directory to the directory that holds {@code pom.xml}.
     *
     * <p>Surefire runs with {@code ${basedir}} as the working directory, so the first candidate
     * matches in a normal build. The walk exists so the test is also correct when a runner starts
     * it from a nested directory, and it fails with the directory it started from rather than
     * silently passing on an empty file.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            final Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom) && Files.isDirectory(candidate.resolve("src"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds both pom.xml and src/, so the build "
                        + "descriptor cannot be located. Run this test with the repository root, or "
                        + "any directory beneath it, as the working directory.");
    }

    /**
     * Reads {@code pom.xml} as UTF-8 lines.
     *
     * @return the build descriptor's lines, in order
     */
    private static List<String> pomLines() {
        try {
            return Files.readAllLines(ROOT.resolve("pom.xml"), StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + ROOT.resolve("pom.xml"), cause);
        }
    }

    /**
     * Collects every {@code *.version} property declared in the POM.
     *
     * @return property name to its literal value
     */
    private static Map<String, String> versionProperties() {
        final Map<String, String> found = new LinkedHashMap<>();
        for (final String line : POM) {
            if (isCommentBody(line)) {
                continue;
            }
            final Matcher matcher = VERSION_PROPERTY.matcher(line);
            while (matcher.find()) {
                found.put(matcher.group(1), matcher.group(2).strip());
            }
        }
        return found;
    }

    /**
     * Reports whether a line is prose inside an XML comment rather than markup.
     *
     * <p>Every comment in this POM is a block whose interior lines carry no angle bracket that
     * would match the element patterns above, except where the prose quotes an element name. A
     * line is treated as comment body when it opens a comment, closes one, or lies between the
     * two - determined by {@link #commentDepthBefore(int)} for the indexed form.
     *
     * @param line the raw line
     * @return {@code true} when the line begins or ends an XML comment
     */
    private static boolean isCommentBody(final String line) {
        final String stripped = line.strip();
        return stripped.startsWith("<!--") || stripped.startsWith("-->") || stripped.endsWith("-->");
    }

    /**
     * Reports whether the line at an index sits inside an XML comment.
     *
     * @param index zero-based line index
     * @return {@code true} when the line is inside a comment, or is a comment delimiter
     */
    private static boolean insideComment(final int index) {
        return commentDepthBefore(index) > 0 || isCommentBody(POM.get(index));
    }

    /**
     * Counts unterminated comment openings before an index.
     *
     * @param index zero-based line index
     * @return the number of {@code <!--} openings not yet closed by {@code -->}
     */
    private static int commentDepthBefore(final int index) {
        int depth = 0;
        for (int i = 0; i < index; i++) {
            final String line = POM.get(i);
            if (line.contains("<!--")) {
                depth++;
            }
            if (line.contains("-->")) {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth;
    }

    /**
     * Collects every {@code groupId:artifactId} coordinate declared as markup, outside comments.
     *
     * <p>A coordinate is recognised when a {@code <groupId>} line is followed within two lines by
     * an {@code <artifactId>} line, which is the shape every dependency, plugin and BOM import in
     * this POM uses.
     *
     * @return coordinates in declaration order, duplicates retained
     */
    private static List<String> declaredCoordinates() {
        final List<String> coordinates = new ArrayList<>();
        for (int index = 0; index < POM.size(); index++) {
            if (insideComment(index)) {
                continue;
            }
            final Matcher group = GROUP_ID.matcher(POM.get(index));
            if (!group.find()) {
                continue;
            }
            for (int ahead = index + 1; ahead <= Math.min(index + 2, POM.size() - 1); ahead++) {
                final Matcher artifact = ARTIFACT_ID.matcher(POM.get(ahead));
                if (artifact.find()) {
                    coordinates.add(group.group(1).strip() + ":" + artifact.group(1).strip());
                    break;
                }
            }
        }
        return coordinates;
    }

    /** One bill of materials per coordinate, so no version is decided by declaration order. */
    @Nested
    @DisplayName("one bill of materials per coordinate")
    final class OneBomPerCoordinate {

        @Test
        @DisplayName("no second Netty BOM is imported; the netty.version property is the single lever")
        void nettyIsRetargetedByPropertyOnly() {
            assertThat(declaredCoordinates())
                    .as(
                            "spring-boot-dependencies:3.5.11 already imports %s at "
                                    + "${netty.version}. Importing it again here would put one BOM "
                                    + "on the classpath twice at one version, with the winner "
                                    + "settled by declaration order rather than intent",
                            NETTY_BOM)
                    .doesNotContain(NETTY_BOM);
            assertThat(VERSION_PROPERTIES)
                    .as(
                            "the remediation itself must remain in force: the property is what "
                                    + "retargets the parent's own netty-bom import")
                    .containsEntry("netty.version", "4.1.136.Final");
        }

        @Test
        @DisplayName("exactly one BOM import is declared by this project, and it is the AWS one")
        void exactlyOneProjectDeclaredBomImport() {
            final List<String> imports = new ArrayList<>();
            for (int index = 0; index < POM.size(); index++) {
                if (insideComment(index) || !POM.get(index).contains("<scope>import</scope>")) {
                    continue;
                }
                for (int back = index - 1; back >= Math.max(0, index - 5); back--) {
                    final Matcher artifact = ARTIFACT_ID.matcher(POM.get(back));
                    if (artifact.find()) {
                        final String candidate = artifact.group(1).strip();
                        for (int further = back - 1; further >= Math.max(0, back - 2); further--) {
                            final Matcher group = GROUP_ID.matcher(POM.get(further));
                            if (group.find()) {
                                imports.add(group.group(1).strip() + ":" + candidate);
                                break;
                            }
                        }
                        break;
                    }
                }
            }
            assertThat(imports)
                    .as(
                            "the Testcontainers version is overridden by property rather than by a "
                                    + "competing BOM import for the same reason Netty now is, so "
                                    + "the AWS bill of materials must be the only import here")
                    .containsExactly(AWS_BOM);
        }
    }

    /** The coverage pin, its bytecode reader and its agent, held to the documented three values. */
    @Nested
    @DisplayName("the documented Java 25 coverage exception")
    final class DocumentedJavaTwentyFiveException {

        @Test
        @DisplayName("the plugin pin stays at the required 0.8.12 and is not raised to match the agent")
        void thePluginPinIsNotRaised() {
            assertThat(VERSION_PROPERTIES)
                    .as(
                            "the requirement pins jacoco-maven-plugin at 0.8.12 and says a "
                                    + "divergent release is to be RECORDED, not resolved "
                                    + "unilaterally. Raising this to 0.8.14 to make the versions "
                                    + "match would resolve it unilaterally")
                    .containsEntry("jacoco-maven-plugin.version", "0.8.12");
        }

        @Test
        @DisplayName("the bytecode reader and runtime agent stay on the values that can read class file 69")
        void theReaderAndAgentAreNotLowered() {
            assertThat(VERSION_PROPERTIES)
                    .as(
                            "ASM 9.7, which 0.8.12 ships, has a class file ceiling of 67 while this "
                                    + "toolchain emits 69. Lowering either of these to match the "
                                    + "plugin pin reinstates the measured failure")
                    .containsEntry("jacoco.asm.version", "9.9")
                    .containsEntry("jacoco.agent.runtime.version", "0.8.14");
        }

        @Test
        @DisplayName("the split is deliberate: the plugin declaration overrides the reader and the agent")
        void theOverrideIsWiredOnThePluginDeclaration() {
            final String text = String.join("\n", POM);
            assertThat(text)
                    .as(
                            "both halves are required. Overriding org.jacoco.core and "
                                    + "org.jacoco.report alone leaves ASM at 9.7 and fails "
                                    + "identically, which was measured rather than assumed")
                    .contains("<version>${jacoco.asm.version}</version>")
                    .contains("<version>${jacoco.agent.runtime.version}</version>");
        }

        @Test
        @DisplayName("the coverage floor stays at 0.80 and is expressed as a property")
        void theCoverageFloorIsUnchanged() {
            assertThat(POM)
                    .as(
                            "the floor is overridden on the command line while coverage is being "
                                    + "raised, never edited in the file")
                    .contains("    <jacoco.line.coverage.minimum>0.80</jacoco.line.coverage.minimum>");
        }
    }

    /** Determinism - every version is an exact coordinate. */
    @Nested
    @DisplayName("determinism - every version is an exact coordinate")
    final class ExactCoordinates {

        @Test
        @DisplayName("no version property is a range, LATEST or RELEASE")
        void noVersionPropertyIsFloating() {
            assertThat(VERSION_PROPERTIES)
                    .as("the property block must carry the pinned version set, or this proves nothing")
                    .hasSizeGreaterThanOrEqualTo(10);
            VERSION_PROPERTIES.forEach(
                    (name, value) ->
                            assertThat(value)
                                    .as(
                                            "%s must be an exact coordinate: a reproducible build "
                                                    + "cannot resolve a range, and Rule 1 Clause C "
                                                    + "forbids environment-specific resolution",
                                            name)
                                    .doesNotContain("[")
                                    .doesNotContain("(")
                                    .doesNotContain("LATEST")
                                    .doesNotContain("RELEASE")
                                    .doesNotContain("SNAPSHOT")
                                    .isNotBlank());
        }

        @Test
        @DisplayName("no coordinate <version> element is a range, LATEST or RELEASE")
        void noCoordinateVersionIsFloating() {
            final List<String> checked = new ArrayList<>();
            for (int index = 0; index < POM.size(); index++) {
                if (insideComment(index)) {
                    continue;
                }
                final Matcher matcher = VERSION.matcher(POM.get(index));
                if (!matcher.find() || !precededByArtifactId(index)) {
                    continue;
                }
                final String value = matcher.group(1).strip();
                checked.add(value);
                assertThat(value)
                        .as(
                                "pom.xml:%d declares coordinate version %s. A reproducible build "
                                        + "cannot resolve a range, and Rule 1 Clause C forbids "
                                        + "environment-specific resolution",
                                index + 1, value)
                        .doesNotContain("[")
                        .doesNotContain("]")
                        .doesNotContain("(")
                        .doesNotContain("LATEST")
                        .doesNotContain("RELEASE")
                        .doesNotContain("SNAPSHOT")
                        .isNotBlank();
            }
            assertThat(checked)
                    .as(
                            "the coordinate scan must find a substantial set, or it proves nothing. "
                                    + "Sixteen coordinate versions were measured: the parent, this "
                                    + "project's own, the AWS bill of materials, the one explicitly "
                                    + "versioned dependency, and twelve plugin or plugin-classpath "
                                    + "coordinates. Every other dependency is intentionally "
                                    + "version-free and inherits from a managed set")
                    .hasSizeGreaterThanOrEqualTo(15);
        }

        @Test
        @DisplayName("the only ranges in the file are the two enforcer floors, and they ARE ranges")
        void theOnlyRangesAreTheEnforcerFloors() {
            final List<String> ranges = new ArrayList<>();
            for (int index = 0; index < POM.size(); index++) {
                if (insideComment(index)) {
                    continue;
                }
                final Matcher matcher = VERSION.matcher(POM.get(index));
                while (matcher.find()) {
                    final String value = matcher.group(1).strip();
                    if (value.contains("[") || value.contains("(")) {
                        ranges.add(value);
                    }
                }
            }
            assertThat(ranges)
                    .as(
                            "requireJavaVersion and requireMavenVersion express a FLOOR, not a "
                                    + "coordinate, so each must remain an open-ended range - a "
                                    + "single exact value there would reject every newer toolchain. "
                                    + "Nothing else in the file may be a range")
                    .containsExactly("[25,)", "[3.9.11,)");
        }

        /**
         * Reports whether a {@code <version>} line belongs to a dependency or plugin coordinate.
         *
         * <p>Every coordinate in this POM declares {@code <artifactId>} within the two lines above
         * its {@code <version>}. An enforcer rule floor such as {@code <requireJavaVersion>} has no
         * {@code <artifactId>} above it, which is what distinguishes a resolvable coordinate from a
         * version constraint - and the distinction matters, because a floor MUST be a range.
         *
         * @param index zero-based index of the {@code <version>} line
         * @return {@code true} when an {@code <artifactId>} appears within the two preceding lines
         */
        private boolean precededByArtifactId(final int index) {
            for (int back = index - 1; back >= Math.max(0, index - 2); back--) {
                if (ARTIFACT_ID.matcher(POM.get(back)).find()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The continuous-integration workflow's supply chain.
     *
     * <p>The POM assertions above exist because a floating dependency version makes a build
     * irreproducible. A floating GitHub Action reference is the same defect with a wider blast radius,
     * and it was present: every action was referenced by a major-version tag, which is a branch the
     * publisher re-points at will. {@code @v4} therefore meant "whatever v4 is when the job starts",
     * and that code runs with the workflow's token and full access to the checkout. A harness this file
     * describes as deterministic and repeatable cannot be either while a third party can change what it
     * executes between two builds of the same commit.
     *
     * <p>These assertions hold the remediation in place. They are deliberately mechanical rather than
     * documentary, because the previous revision already <em>described</em> itself as a pinned,
     * deterministic harness while referencing four actions by moving tag - so prose was demonstrably not
     * sufficient to keep the property true. Each test below fails on the specific regression it names.
     */
    @Nested
    @DisplayName("the CI workflow pins what it executes")
    final class WorkflowSupplyChain {

        /** Any {@code uses:} reference, capturing the action and whatever follows the {@code @}. */
        private static final Pattern USES =
                Pattern.compile("^\\s*uses:\\s*([^@\\s]+)@(\\S+)");

        /** A 40-character lowercase hexadecimal Git commit identifier, and nothing looser. */
        private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");

        /**
         * Reads the workflow as UTF-8 lines.
         *
         * @return the workflow's lines, in order
         */
        private List<String> workflowLines() {
            final Path workflow = ROOT.resolve(".github/workflows/build.yml");
            assertThat(workflow)
                    .as("the CI workflow must exist: it is the harness Rule 1 Clause C's "
                            + "deterministic-build requirement is discharged by")
                    .isRegularFile();
            try {
                return Files.readAllLines(workflow, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + workflow, cause);
            }
        }

        /**
         * Collects every action reference in the workflow.
         *
         * @return the reference following each {@code uses:}, as {@code action@ref}
         */
        private List<String> actionReferences() {
            final List<String> references = new ArrayList<>();
            for (final String line : workflowLines()) {
                final Matcher matcher = USES.matcher(line);
                if (matcher.find()) {
                    references.add(matcher.group(1) + "@" + matcher.group(2));
                }
            }
            return references;
        }

        @Test
        @DisplayName("every action is pinned to an immutable commit SHA, never a movable tag")
        void everyActionIsPinnedToACommitSha() {
            final List<String> references = actionReferences();

            assertThat(references)
                    .as("the scan must find the workflow's action references, so a silent empty pass "
                            + "is impossible")
                    .isNotEmpty();

            final List<String> unpinned = new ArrayList<>();
            for (final String reference : references) {
                final String ref = reference.substring(reference.indexOf('@') + 1);
                if (!COMMIT_SHA.matcher(ref).matches()) {
                    unpinned.add(reference);
                }
            }

            assertThat(unpinned)
                    .as("""
                        each reference must resolve to one immutable commit. A tag - including an \
                        apparently specific one like v4.4.0 - is a mutable pointer the publisher can \
                        move, so it pins nothing; only a 40-character commit identifier does. Offending \
                        references are listed.""")
                    .isEmpty();
        }

        @Test
        @DisplayName("each pinned SHA carries its human-readable release in a trailing comment")
        void eachPinnedShaIsAnnotatedWithItsRelease() {
            final List<String> unannotated = new ArrayList<>();
            for (final String line : workflowLines()) {
                if (USES.matcher(line).find() && !line.contains("# v")) {
                    unannotated.add(line.strip());
                }
            }

            assertThat(unannotated)
                    .as("a bare 40-character hash is unreviewable and no dependency bot can tell "
                            + "whether it is current, so the release it corresponds to is stated beside "
                            + "it. The comment is for the reader; the SHA is what executes. Both are "
                            + "required, which is why this is asserted separately from the pinning.")
                    .isEmpty();
        }

        @Test
        @DisplayName("Maven runs through the checksum-verified wrapper, not a host or marketplace install")
        void mavenRunsThroughTheWrapper() {
            final List<String> hostInvocations = new ArrayList<>();
            for (final String line : workflowLines()) {
                final String stripped = line.strip();
                if (stripped.startsWith("#")) {
                    continue;
                }
                // A host invocation is `mvn` not immediately preceded by the wrapper's `./`.
                if (stripped.matches(".*(^|[^./\\w])mvn\\s.*") && !stripped.contains("./mvnw")) {
                    hostInvocations.add(stripped);
                }
            }

            assertThat(hostInvocations)
                    .as("""
                        .mvn/wrapper/maven-wrapper.properties names the exact distribution together with \
                        its distributionSha256Sum, so ./mvnw verifies what it downloads before running \
                        it - a stronger guarantee than any install step, and the same tool a developer \
                        and the Dockerfile build stage already use. A bare `mvn` reintroduces whatever \
                        version the runner happens to carry.""")
                    .isEmpty();

            // Asserted over action REFERENCES rather than over raw text, and for the same reason the
            // lifecycle-flag assertion in DemoUserSeedGateContractTest is: the workflow documents at
            // length why this step was removed, and that explanation must not read as the step existing.
            assertThat(actionReferences())
                    .as("and no marketplace action installs a Maven beside the wrapper: that step was "
                            + "removed rather than pinned, because it was both an unpinned third-party "
                            + "dependency and redundant once ./mvnw verifies its own distribution")
                    .noneSatisfy(reference -> assertThat(reference).contains("setup-maven"));
        }

        @Test
        @DisplayName("the delivered container image is scanned, with a stated High/Critical threshold")
        void theDeliveredImageIsScanned() {
            final String workflow = String.join("\n", workflowLines());

            assertThat(workflow)
                    .as("""
                        the Maven scan reads pom.xml's resolved graph and cannot see the runtime image. \
                        That gap is how outdated PostgreSQL, Prometheus and Grafana images passed a green \
                        build, one of them carrying CVSS 8.8 - above the threshold the Maven scan already \
                        enforced. The image the Dockerfile produces is therefore scanned too.""")
                    .contains("docker build --file Dockerfile")
                    .contains("--severity HIGH,CRITICAL")
                    .contains("--exit-code 1");

            assertThat(workflow)
                    .as("the scanner itself is pinned by digest, so the analysis is reproducible")
                    .contains("aquasec/trivy:0.73.0@sha256:");

            assertThat(workflow)
                    .as("""
                        and an advisory with no upstream fix is still reported: --ignore-unfixed must be \
                        explicitly false, because hiding those findings lets a base image drift out of \
                        support while this job stays green.""")
                    .contains("--ignore-unfixed=false");
        }
    }
}
