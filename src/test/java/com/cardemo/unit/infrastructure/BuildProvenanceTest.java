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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

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

    /**
     * The premises {@code owasp-suppressions.xml} rests on, and the gate it must never weaken.
     *
     * <p>A suppression entry is an argument, and an argument has premises. Two of this file's entries state
     * theirs as repository facts: the Actuator bypass acceptance turns on no Health Group
     * {@code additional-path} being configured, because the bypass requires an additional path that collides
     * with a secured application endpoint; and the Tomcat examples entry turns on this build declaring no
     * WebSocket surface. Both premises were true when the entries were written and neither was enforced, so
     * the configuration change that would silently invalidate one of them - three lines of YAML - would have
     * produced a green build and a false record. That is the gap these assertions close.
     *
     * <p>Each guard is written to <em>retire with its entry</em> rather than to outlive it. While the entry is
     * declared the premise is enforced; once the entry is gone the assertion inverts into a statement about
     * why it is no longer needed, so a reader who deletes an entry is told what else to delete rather than
     * left with a constraint whose reason has been removed from the tree.
     *
     * <p>Two further assertions guard the gate itself rather than an entry. One holds the threshold, the
     * absent skip and the scan's full scope, because the recorded temptation when a scan turns red is to lower
     * the number rather than to disposition the record. The other holds the file's own numerals to the entries
     * it declares - the file has twice recorded a count of itself that disagreed with its contents, which is
     * exactly the class of defect that makes a register unusable as evidence.
     *
     * <p>Unlike the POM assertions above, this class parses the suppression file with a DOM parser rather than
     * with line patterns. The reason is the inverse of the POM's: there the claim is about the literal text a
     * reviewer reads, whereas an entry's scope and its identifiers are structure, and a malformed suppression
     * file must fail here - loudly, in the unit tier - rather than at scan time in a job whose failure looks
     * like a vulnerability report.
     */
    @Nested
    @DisplayName("owasp-suppressions.xml - the premises its dispositions rest on")
    final class SuppressionPremises {

        /** The Actuator health-group bypass whose acceptance assumes no additional path is configured. */
        private static final String ACTUATOR_BYPASS_CVE = "CVE-2026-22731";

        /** The Tomcat examples advisory whose withdrawal assumes no WebSocket surface and an absent fix. */
        private static final String TOMCAT_EXAMPLES_CVE = "CVE-2026-66299";

        /** The Tomcat release that fixes {@link #TOMCAT_EXAMPLES_CVE} on the 10.1 line. */
        private static final String TOMCAT_FIX_VERSION = "10.1.58";

        /** The parent version pinned by requirement, and the one the bypass acceptance is stated against. */
        private static final String PINNED_PARENT = "3.5.11";

        /** Every configuration surface a Spring property can be set from, relative to the repository root. */
        private static final List<String> CONFIGURATION_SURFACES = List.of(
                "src/main/resources/application.yml",
                "src/main/resources/application-local.yml",
                "src/main/resources/application-test.yml",
                "src/main/resources/application-prod.yml",
                "docker-compose.yml",
                ".env.example",
                "Dockerfile",
                ".github/workflows/build.yml");

        /** The numerals the file writes about itself, as {@code N <cve> ... M <cpe> ... across K <suppress>}. */
        private static final Pattern SELF_COUNT = Pattern.compile(
                "declares (\\d+) <cve> identifiers, all distinct, and (\\d+) <cpe>\\s+identifiers across "
                        + "(\\d+) <suppress> entries");

        /** The spelled tally sentence: total, then the Tier 1, Tier 2 and Tier 3 counts in order. */
        private static final Pattern SPELLED_TALLY = Pattern.compile(
                "What remains is (\\w+) entries: (\\w+) Tier 1 identifier corrections, (\\w+)\\s+"
                        + "Tier 2, (\\w+) Tier 3");

        /** The tier a note declares for itself. */
        private static final Pattern TIER = Pattern.compile("TIER\\s+(\\d)");

        /** An {@code until} attribute value: an ISO date with the zone suffix Dependency-Check expects. */
        private static final Pattern UNTIL = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}Z$");

        /** The English number words this file uses to spell its tallies. */
        private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
                Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
                Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
                Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
                Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13),
                Map.entry("fourteen", 14), Map.entry("fifteen", 15), Map.entry("sixteen", 16),
                Map.entry("seventeen", 17), Map.entry("eighteen", 18), Map.entry("nineteen", 19),
                Map.entry("twenty", 20));

        /**
         * Reads the suppression file as UTF-8 text.
         *
         * @return the whole file, comments included
         */
        private String suppressionText() {
            final Path file = ROOT.resolve("owasp-suppressions.xml");
            assertThat(file)
                    .as("owasp-suppressions.xml is an input to the vulnerability gate configured in pom.xml, "
                            + "so its absence would change what the gate measures")
                    .isRegularFile();
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read " + file, cause);
            }
        }

        /**
         * Parses the suppression file into a document.
         *
         * <p>External entity resolution and document type declarations are refused rather than left at their
         * defaults: this parser reads a file from the repository, but a parser that would follow an external
         * reference is a parser that behaves differently depending on the network, which is the property this
         * whole class exists to keep out of the build.
         *
         * @return the parsed document
         */
        private Document suppressionDocument() {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                final DocumentBuilder builder = factory.newDocumentBuilder();
                return builder.parse(ROOT.resolve("owasp-suppressions.xml").toFile());
            } catch (final ParserConfigurationException | SAXException cause) {
                throw new IllegalStateException(
                        "owasp-suppressions.xml is not well formed XML, so Dependency-Check cannot read it "
                                + "and every suppression it declares is inert", cause);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot read owasp-suppressions.xml", cause);
            }
        }

        /**
         * Collects the suppression entries the file declares.
         *
         * @return every {@code suppress} element, in document order
         */
        private List<Element> entries() {
            final NodeList nodes = suppressionDocument().getElementsByTagName("suppress");
            final List<Element> elements = new ArrayList<>(nodes.getLength());
            for (int index = 0; index < nodes.getLength(); index++) {
                elements.add((Element) nodes.item(index));
            }
            return elements;
        }

        /**
         * Reports whether the file declares a suppression for an identifier.
         *
         * <p>Read from parsed {@code cve} elements rather than from the raw text, because the file's own
         * commentary names identifiers it has DELETED, and a text search would count those as live.
         *
         * @param cve the identifier to look for
         * @return {@code true} when some entry names it
         */
        private boolean declares(final String cve) {
            final NodeList nodes = suppressionDocument().getElementsByTagName("cve");
            for (int index = 0; index < nodes.getLength(); index++) {
                if (cve.equals(nodes.item(index).getTextContent().strip())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Finds every configured Health Group additional path across the configuration surfaces.
         *
         * <p>Comment lines are skipped, and deliberately: the property is discussed at length in this
         * repository's commentary - including in the suppression entry this guard protects - and a check that
         * could not tell prose from configuration would either fail on its own justification or be deleted.
         * Names are normalised by removing hyphens and underscores so that the YAML spelling
         * {@code additional-path}, the relaxed camel spelling and the environment variable spelling
         * {@code ...ADDITIONALPATH} are all caught by one comparison.
         *
         * @param scanned collects the surfaces actually read, so a vacuous pass is detectable
         * @return one {@code file:line} description per offending line
         */
        private List<String> configuredAdditionalPaths(final List<String> scanned) {
            final List<String> offenders = new ArrayList<>();
            for (final String surface : CONFIGURATION_SURFACES) {
                final Path file = ROOT.resolve(surface);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                scanned.add(surface);
                final List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (final IOException cause) {
                    throw new UncheckedIOException("Cannot read " + file, cause);
                }
                for (int index = 0; index < lines.size(); index++) {
                    final String stripped = lines.get(index).strip();
                    if (stripped.startsWith("#")) {
                        continue;
                    }
                    final String normalised = stripped
                            .replace("-", "")
                            .replace("_", "")
                            .toLowerCase(Locale.ROOT);
                    if (normalised.contains("additionalpath")) {
                        offenders.add(surface + ":" + (index + 1) + " " + stripped);
                    }
                }
            }
            return offenders;
        }

        /**
         * Compares two dotted numeric versions.
         *
         * @param left  the version to compare
         * @param right the version to compare it against
         * @return a negative number, zero or a positive number as {@code left} orders before, with or after
         *         {@code right}
         */
        private int compareVersions(final String left, final String right) {
            final String[] leftParts = left.split("\\.");
            final String[] rightParts = right.split("\\.");
            for (int index = 0; index < Math.max(leftParts.length, rightParts.length); index++) {
                final int leftValue = index < leftParts.length ? numericPrefix(leftParts[index]) : 0;
                final int rightValue = index < rightParts.length ? numericPrefix(rightParts[index]) : 0;
                if (leftValue != rightValue) {
                    return Integer.compare(leftValue, rightValue);
                }
            }
            return 0;
        }

        /**
         * Reads the leading integer of a version component.
         *
         * @param component one dot-separated component
         * @return its leading digits as an integer, or zero when it begins with none
         */
        private int numericPrefix(final String component) {
            int end = 0;
            while (end < component.length() && Character.isDigit(component.charAt(end))) {
                end++;
            }
            return end == 0 ? 0 : Integer.parseInt(component.substring(0, end));
        }

        /**
         * Reads the version the POM declares for its parent.
         *
         * @return the parent's literal version string
         */
        private String pinnedParentVersion() {
            boolean insideParent = false;
            for (final String line : POM) {
                if (line.contains("<parent>")) {
                    insideParent = true;
                }
                if (insideParent) {
                    final Matcher version = VERSION.matcher(line);
                    if (version.find()) {
                        return version.group(1).strip();
                    }
                    if (line.contains("</parent>")) {
                        break;
                    }
                }
            }
            throw new IllegalStateException("pom.xml declares no <parent> version");
        }

        /**
         * Translates a spelled number into its value.
         *
         * @param word the number word, in any case
         * @return the value it names
         */
        private int spelled(final String word) {
            final Integer value = NUMBER_WORDS.get(word.toLowerCase(Locale.ROOT));
            assertThat(value)
                    .as("the register spells its tallies in words; \"%s\" is not one this guard can read, so "
                            + "either the word or this table needs correcting", word)
                    .isNotNull();
            return value;
        }

        @Test
        @DisplayName("no health-group additional path is configured while the Actuator bypass entry lives")
        void noHealthGroupAdditionalPathIsConfiguredWhileTheBypassEntryLives() {
            final List<String> scanned = new ArrayList<>();
            final List<String> offenders = configuredAdditionalPaths(scanned);

            assertThat(scanned)
                    .as("the guard must actually read the surfaces a property can be set from, or it passes "
                            + "on nothing. The four profiles are the minimum; the compose file, the "
                            + "environment template, the image and the workflow are included because a "
                            + "Spring property is settable from each of them")
                    .hasSizeGreaterThanOrEqualTo(7)
                    .contains("src/main/resources/application.yml", "src/main/resources/application-prod.yml");

            if (declares(ACTUATOR_BYPASS_CVE)) {
                assertThat(offenders)
                        .as("""
                            the CVE-2026-22731 entry in owasp-suppressions.xml accepts an Actuator \
                            authentication bypass on ONE compensating control: that no health group is given \
                            an additional-path, so no secured application endpoint can be shadowed by one. \
                            Configuring management.endpoint.health.group.*.additional-path - in a profile, in \
                            the compose file, as an environment variable or in the image - removes that \
                            control and makes the accepted risk live, while every gate in this build stays \
                            green because the entry is what keeps the record out of the report. Either revert \
                            the configuration, or advance spring-boot-starter-parent to 3.5.12 or later and \
                            DELETE the entry along with this guard. Offending lines are listed.""")
                        .isEmpty();
            } else {
                assertThat(pinnedParentVersion())
                        .as("""
                            the CVE-2026-22731 entry is gone from owasp-suppressions.xml, which is correct \
                            only if the parent now carries the fix. While the parent is still %s the advisory \
                            applies at CVSS 8.1 and the no-additional-path control is the only thing standing \
                            in for it, so the entry documented the risk and this guard enforced it. Restore \
                            both, or advance the parent to 3.5.12 or later - then delete this guard, which \
                            has no premise left to protect.""", PINNED_PARENT)
                        .isNotEqualTo(PINNED_PARENT);
            }
        }

        @Test
        @DisplayName("the Tomcat examples entry never coexists with the pin that fixes it")
        void theTomcatExamplesEntryNeverCoexistsWithItsFix() {
            final boolean suppressed = declares(TOMCAT_EXAMPLES_CVE);
            final String pin = VERSION_PROPERTIES.get("tomcat.version");

            assertThat(pin)
                    .as("tomcat.version is the forward security pin this project controls, and both the "
                            + "residual register in pom.xml and the suppression entry reason about its value")
                    .isNotNull();

            assertThat(suppressed && compareVersions(pin, TOMCAT_FIX_VERSION) >= 0)
                    .as("""
                        a forward pin and a suppression for one record must never both be in force: the pin \
                        removes the code, after which the entry suppresses nothing and reads as though \
                        something were still being held back. tomcat.version is %s and %s carries the fix for \
                        %s, so the entry in owasp-suppressions.xml is now dead configuration - delete it, and \
                        name the CVE beside the pin as the other security pins are named.""",
                            pin, TOMCAT_FIX_VERSION, TOMCAT_EXAMPLES_CVE)
                    .isFalse();

            if (suppressed) {
                assertThat(declaredCoordinates())
                        .as("""
                            the entry withdraws %s on evidence that the vulnerable component - Tomcat's \
                            WebSocket chat EXAMPLE, which ships only in the apache-tomcat binary \
                            distribution - is in none of the three tomcat-embed jars and that this \
                            application has no WebSocket surface at all. Declaring a WebSocket starter, or \
                            pulling in Jasper or a full Catalina, changes the deployment the evidence was \
                            taken against and the record must be re-assessed before either is added.""",
                                TOMCAT_EXAMPLES_CVE)
                        .doesNotContain(
                                "org.springframework.boot:spring-boot-starter-websocket",
                                "org.apache.tomcat.embed:tomcat-embed-jasper",
                                "org.apache.tomcat:tomcat-catalina");
            }
        }

        @Test
        @DisplayName("the register's numerals match the entries the file actually declares")
        void theRegisterNumeralsMatchTheFile() {
            final String text = suppressionText();
            final List<Element> entries = entries();
            final Document document = suppressionDocument();
            final int cveCount = document.getElementsByTagName("cve").getLength();
            final int cpeCount = document.getElementsByTagName("cpe").getLength();

            final Map<Integer, Integer> byTier = new LinkedHashMap<>();
            for (final Element entry : entries) {
                final NodeList notes = entry.getElementsByTagName("notes");
                assertThat(notes.getLength())
                        .as("every entry states its evidence in a notes element, which is the file's whole "
                                + "premise: an entry a reviewer cannot assess is an entry nobody can keep")
                        .isEqualTo(1);
                final Matcher tier = TIER.matcher(notes.item(0).getTextContent());
                assertThat(tier.find())
                        .as("every note names its tier, so a reader can see which kind of claim is being "
                                + "made without reconstructing it from the argument")
                        .isTrue();
                byTier.merge(Integer.parseInt(tier.group(1)), 1, Integer::sum);
            }

            final Matcher selfCount = SELF_COUNT.matcher(text);
            assertThat(selfCount.find())
                    .as("the file states its own counts in the REGISTER CURRENCY block; this guard exists "
                            + "because it has twice stated a count that disagreed with its contents")
                    .isTrue();
            assertThat(Integer.parseInt(selfCount.group(1)))
                    .as("the file says it declares %s <cve> identifiers; it declares %d",
                            selfCount.group(1), cveCount)
                    .isEqualTo(cveCount);
            assertThat(Integer.parseInt(selfCount.group(2)))
                    .as("the file says it declares %s <cpe> identifiers; it declares %d",
                            selfCount.group(2), cpeCount)
                    .isEqualTo(cpeCount);
            assertThat(Integer.parseInt(selfCount.group(3)))
                    .as("the file says it holds %s <suppress> entries; it holds %d",
                            selfCount.group(3), entries.size())
                    .isEqualTo(entries.size());

            final Matcher tally = SPELLED_TALLY.matcher(text);
            assertThat(tally.find())
                    .as("the same block spells the total and the per-tier breakdown in words, and the two "
                            + "spellings of one count are exactly where the file's recorded drift happened")
                    .isTrue();
            assertThat(spelled(tally.group(1)))
                    .as("the spelled total \"%s\" must agree with the %d entries on disk, and with the "
                            + "numeral in the sentence above it", tally.group(1), entries.size())
                    .isEqualTo(entries.size());
            assertThat(spelled(tally.group(2)))
                    .as("the Tier 1 tally is spelled \"%s\"; %d entries declare TIER 1",
                            tally.group(2), byTier.getOrDefault(1, 0))
                    .isEqualTo(byTier.getOrDefault(1, 0));
            assertThat(spelled(tally.group(3)))
                    .as("the Tier 2 tally is spelled \"%s\"; %d entries declare TIER 2",
                            tally.group(3), byTier.getOrDefault(2, 0))
                    .isEqualTo(byTier.getOrDefault(2, 0));
            assertThat(spelled(tally.group(4)))
                    .as("the Tier 3 tally is spelled \"%s\"; %d entries declare TIER 3",
                            tally.group(4), byTier.getOrDefault(3, 0))
                    .isEqualTo(byTier.getOrDefault(3, 0));
        }

        @Test
        @DisplayName("every entry is scoped to one coordinate, and every expiring one states its own expiry")
        void everyEntryIsScopedAndEveryExpiryIsStatedConsistently() {
            final List<Element> entries = entries();

            assertThat(entries)
                    .as("the scan must find entries, so an empty file cannot pass this class silently")
                    .isNotEmpty();

            int expiring = 0;
            for (int index = 0; index < entries.size(); index++) {
                final Element entry = entries.get(index);
                final String notes = entry.getElementsByTagName("notes").item(0).getTextContent();
                assertThat(entry.getElementsByTagName("packageUrl").getLength())
                        .as("entry %d must be scoped to exactly one coordinate by packageUrl. The file's own "
                                + "rules forbid a bare cpe, a wildcard and a vendor-wide entry, because each "
                                + "of those silently absorbs advisories nobody assessed", index + 1)
                        .isEqualTo(1);

                final String until = entry.getAttribute("until");
                if (until.isEmpty()) {
                    continue;
                }
                expiring++;
                assertThat(until)
                        .as("entry %d expires, so its until value must be the ISO date with the zone suffix "
                                + "Dependency-Check parses; a value it cannot read is a suppression that "
                                + "never expires", index + 1)
                        .matches(UNTIL);
                final String date = until.substring(0, until.length() - 1);
                assertThat(notes)
                        .as("""
                            entry %d expires on %s, and its note must say so in the same words a reader \
                            reaches for, plus the remediation that makes the expiry unnecessary. An expiry \
                            recorded only in an attribute surfaces as an unexplained red scan months later, \
                            which is how a dated acceptance turns into a mystery.""", index + 1, date)
                        .contains("EXPIRES on " + date)
                        .contains("REMEDIATION");
            }

            assertThat(expiring)
                    .as("at least one entry is time boxed - the Tier 4 acceptance whose fix is a newer "
                            + "parent - so this loop measures something. Zero would mean the expiry "
                            + "machinery is asserted against nothing")
                    .isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("the scan gate is not weakened: threshold 7, no skip element, and full scope")
        void theScanGateIsNotWeakened() {
            final String descriptor = String.join("\n", POM);

            assertThat(descriptor)
                    .as("""
                        the failBuildOnCVSS threshold is the gate. Lowering it is the one response to a red \
                        scan this build forbids, because it converts an unassessed High finding into a \
                        silently passing one - and it is cheaper to type than a disposition, which is why it \
                        is asserted rather than trusted.""")
                    .contains("<owasp.failBuildOnCVSS>7</owasp.failBuildOnCVSS>");
            assertThat(descriptor)
                    .as("the suppression file stays wired, since the dispositions it holds are what let the "
                            + "gate pass on evidence rather than on a lowered number")
                    .contains("owasp-suppressions.xml");
            assertThat(descriptor)
                    .as("an analyzer that cannot complete must fail the build rather than produce a partial "
                            + "report, which would read as a clean one")
                    .contains("<failOnError>true</failOnError>");
            assertThat(descriptor)
                    .as("""
                        and the scan keeps its full scope. Both flags default to true, which would exclude \
                        test and provided dependencies from the report - a real exclusion that reads as \
                        nothing at all, since the report simply has fewer rows.""")
                    .contains("<skipTestScope>false</skipTestScope>")
                    .contains("<skipProvidedScope>false</skipProvidedScope>");

            final List<String> skips = new ArrayList<>();
            for (int index = 0; index < POM.size(); index++) {
                if (insideComment(index)) {
                    continue;
                }
                final String line = POM.get(index).strip();
                if (line.startsWith("<skip>")) {
                    skips.add("pom.xml:" + (index + 1) + " " + line);
                }
            }
            assertThat(skips)
                    .as("""
                        no skip element may be configured for any plugin in this build. The vulnerability \
                        scan is the one that matters here: it is bound to verify with no skip, so the only \
                        way to reach a green build without it is to pass the plugin's user property on the \
                        command line, and that leaves a trace in the invocation. A skip in the POM would \
                        leave none. Offending lines are listed.""")
                    .isEmpty();
        }
    }
}
