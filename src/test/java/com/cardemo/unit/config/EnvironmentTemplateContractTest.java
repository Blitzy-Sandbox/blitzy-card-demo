/*
 * ****************************************************************************
 * Program     : EnvironmentTemplateContractTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Holds .env.example and the four Spring profile files to one
 *               reconciled environment contract. Two invariants are asserted:
 *               COMPLETE, every ${NAME} placeholder the profiles read is
 *               documented in the template; and LIVE, every name the template
 *               documents is read by a committed consumer. A third assertion
 *               forbids shipping a variable that binds a Spring property
 *               directly alongside the decomposed placeholders it would
 *               silently supersede.
 * Source      : app/jcl/DUSRSECJ.jcl (the ten inline users whose plaintext
 *                 password never becomes an environment default)
 *               + app/csd/CARDDEMO.CSD:L499-L503 (DEFINE TDQUEUE(JOBS), whose
 *                 queue name is one of the documented resource names)
 *               + app/jcl/DEFGDGB.jcl (the seven GDG bases the three documented
 *                 bucket names replace)
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
package com.cardemo.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reconciles {@code .env.example} against the exact property graph the four Spring profile files
 * read.
 *
 * <p>The defect this test closes was not cosmetic. An earlier revision of the template shipped
 * {@code SPRING_DATASOURCE_URL} and {@code MANAGEMENT_OTLP_TRACING_ENDPOINT} and shipped neither
 * {@code POSTGRES_HOST} nor {@code OTEL_EXPORTER_OTLP_ENDPOINT}. Those two composed names bind
 * {@code spring.datasource.url} and {@code management.otlp.tracing.endpoint} <em>directly</em>, by
 * relaxed binding at a higher precedence than any profile file, so while either was exported the
 * decomposed placeholders in {@code application.yml} were never evaluated. A developer who copied
 * the template verbatim therefore booted successfully while the contract the base profile actually
 * declares went entirely unexercised - the template appeared to work and documented the wrong
 * thing, which is worse than a template that fails, because nothing reports it.
 *
 * <p>Three assertions hold the two files together:
 *
 * <ol>
 *   <li><strong>COMPLETE</strong> - every {@code ${NAME}} placeholder read by
 *       {@code application.yml}, {@code application-local.yml}, {@code application-test.yml} or
 *       {@code application-prod.yml} is assigned in the template. A placeholder with no entry is a
 *       setting a developer cannot discover without reading the profiles.
 *   <li><strong>LIVE</strong> - every name assigned in the template is read by at least one
 *       committed consumer: a profile placeholder, {@code docker-compose.yml},
 *       {@code localstack-init/init-aws.sh}, {@code pom.xml},
 *       {@code .github/workflows/build.yml}, or Spring's own relaxed binding for the one name on
 *       {@link #RELAXED_BINDING_NAMES}. A name no file reads documents a setting that does not
 *       exist.
 *   <li><strong>ONE SPELLING</strong> - no name on {@link #SUPERSEDING_NAMES} appears in the
 *       template, because each binds a property this project composes from decomposed variables
 *       and would leave that decomposed group dead while appearing to work.
 *   </ol>
 *
 * <p>Only the profile files are scanned for placeholders. {@code logback-spring.xml} also uses
 * {@code ${...}} syntax, but its {@code REDACTION*}, {@code APPLICATION_NAME},
 * {@code ACTIVE_PROFILES} and {@code CONSOLE_LEVEL_CEILING} references resolve against
 * {@code <property>} and {@code <springProperty>} elements declared in that same file rather than
 * against the environment, so including it would manufacture names no environment ever supplies.
 *
 * <p>YAML comment lines are excluded before scanning. The profile files discuss variable names in
 * prose at length - {@code application-prod.yml} contains the literal text
 * {@code "There is not one ${VAR:fallback} anywhere below"} - and a scanner that read comments
 * would demand an entry for {@code VAR}.
 */
@DisplayName(".env.example and the four profiles - one reconciled environment contract")
final class EnvironmentTemplateContractTest {

    /** {@code ${NAME}} or {@code ${NAME:default}} at the start of a Spring property placeholder. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

    /** A shell-style assignment of an upper-case name at the start of a template line. */
    private static final Pattern ASSIGNMENT = Pattern.compile("^([A-Z][A-Z0-9_]*)=");

    /** The four profile files that constitute the property graph. */
    private static final List<String> PROFILE_FILES = List.of(
            "src/main/resources/application.yml",
            "src/main/resources/application-local.yml",
            "src/main/resources/application-test.yml",
            "src/main/resources/application-prod.yml");

    /**
     * Committed files that consume an environment variable by name without going through a Spring
     * property placeholder: the compose topology, the LocalStack provisioning hook, the build and
     * the CI workflow.
     */
    private static final List<String> OTHER_CONSUMERS = List.of(
            "docker-compose.yml",
            "localstack-init/init-aws.sh",
            "pom.xml",
            ".github/workflows/build.yml");

    /**
     * Names consumed by Spring itself rather than by any file in this repository.
     * {@code SPRING_PROFILES_ACTIVE} binds {@code spring.profiles.active} through relaxed binding,
     * and the base profile deliberately declares no default so an unprofiled boot is visible.
     */
    private static final Set<String> RELAXED_BINDING_NAMES = Set.of("SPRING_PROFILES_ACTIVE");

    /**
     * Names that bind a Spring property directly and would supersede a decomposed placeholder
     * group this project composes. Shipping one of these in the template makes the group beneath
     * it dead while the template still appears to work, so none may appear there. Relaxed binding
     * still honours them when a deployment sets one deliberately, in its own environment.
     */
    private static final Set<String> SUPERSEDING_NAMES = Set.of(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "MANAGEMENT_OTLP_TRACING_ENDPOINT");

    /** The repository root, located once and reused by every assertion. */
    private static final Path ROOT = repositoryRoot();

    /** Placeholder name to the {@code file:line} locators that read it, in file order. */
    private static final Map<String, List<String>> PLACEHOLDERS_READ = placeholdersRead();

    /** Template variable name to the one-based line it is assigned on. */
    private static final Map<String, Integer> TEMPLATE_ASSIGNMENTS = templateAssignments();

    /**
     * Walks upward from the working directory to the directory that holds both {@code pom.xml} and
     * {@code .env.example}.
     *
     * <p>Surefire runs with {@code ${basedir}} as the working directory, so the first candidate
     * matches in a normal build. The walk exists so the test is also correct when a runner starts
     * it from a nested directory, and it fails with the directory it started from rather than
     * silently passing on an empty file set.
     *
     * @return the repository root
     */
    private static Path repositoryRoot() {
        final Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve(".env.example"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No directory from " + start + " upward holds both pom.xml and .env.example, so the "
                        + "environment contract cannot be located. Run this test with the "
                        + "repository root, or any directory beneath it, as the working directory.");
    }

    /**
     * Reads a repository file as UTF-8 lines.
     *
     * @param relativePath path relative to the repository root
     * @return the file's lines, in order
     */
    private static List<String> lines(final String relativePath) {
        final Path path = ROOT.resolve(relativePath);
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot read " + path, cause);
        }
    }

    /**
     * Collects every placeholder name the four profile files read outside a comment.
     *
     * @return placeholder name to the {@code file:line} locators that read it
     */
    private static Map<String, List<String>> placeholdersRead() {
        final Map<String, List<String>> found = new LinkedHashMap<>();
        for (final String file : PROFILE_FILES) {
            final List<String> content = lines(file);
            final String shortName = file.substring(file.lastIndexOf('/') + 1);
            for (int index = 0; index < content.size(); index++) {
                final String line = content.get(index);
                if (line.stripLeading().startsWith("#")) {
                    continue;
                }
                final Matcher matcher = PLACEHOLDER.matcher(line);
                while (matcher.find()) {
                    found.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                            .add(shortName + ":" + (index + 1));
                }
            }
        }
        return found;
    }

    /**
     * Collects every variable the template assigns.
     *
     * @return variable name to its one-based line in {@code .env.example}
     */
    private static Map<String, Integer> templateAssignments() {
        final Map<String, Integer> found = new LinkedHashMap<>();
        final List<String> content = lines(".env.example");
        for (int index = 0; index < content.size(); index++) {
            final Matcher matcher = ASSIGNMENT.matcher(content.get(index));
            if (matcher.find()) {
                found.put(matcher.group(1), index + 1);
            }
        }
        return found;
    }

    /**
     * The placeholder names, as parameterised-test arguments.
     *
     * @return one argument per distinct placeholder the profiles read
     */
    private static Stream<String> placeholderNames() {
        return new TreeSet<>(PLACEHOLDERS_READ.keySet()).stream();
    }

    /**
     * The template variable names, as parameterised-test arguments.
     *
     * @return one argument per distinct name the template assigns
     */
    private static Stream<String> templateNames() {
        return new TreeSet<>(TEMPLATE_ASSIGNMENTS.keySet()).stream();
    }

    /**
     * Reports whether any consumer outside the profile files mentions a variable name.
     *
     * @param name the variable name
     * @return the consumer that mentions it, or {@code null} when none does
     */
    private static String consumerOutsideProfiles(final String name) {
        for (final String file : OTHER_CONSUMERS) {
            for (final String line : lines(file)) {
                if (line.contains(name)) {
                    return file;
                }
            }
        }
        return null;
    }

    /** COMPLETE - the template documents every placeholder the profiles read. */
    @Nested
    @DisplayName("COMPLETE - every placeholder the profiles read is documented")
    final class Complete {

        @ParameterizedTest(name = "{0} is documented in .env.example")
        @MethodSource(
                "com.cardemo.unit.config.EnvironmentTemplateContractTest#placeholderNames")
        @DisplayName("each placeholder the profiles read has a template entry")
        void eachPlaceholderIsDocumented(final String name) {
            assertThat(TEMPLATE_ASSIGNMENTS)
                    .as(
                            "%s is read at %s but .env.example assigns no value for it, so a "
                                    + "developer cannot discover it without reading the profiles",
                            name, PLACEHOLDERS_READ.get(name))
                    .containsKey(name);
        }

        @Test
        @DisplayName("the profile scan finds a non-trivial placeholder set, so a silent empty pass is impossible")
        void theScanIsNotVacuous() {
            assertThat(PLACEHOLDERS_READ.keySet())
                    .as("the four profile files must read a substantial set of placeholders")
                    .hasSizeGreaterThanOrEqualTo(20)
                    .contains(
                            "JWT_SIGNING_KEY",
                            "POSTGRES_HOST",
                            "OTEL_EXPORTER_OTLP_ENDPOINT",
                            "AWS_ENDPOINT_URL",
                            "CARDDEMO_TIME_ZONE");
        }

        @Test
        @DisplayName("the four names the earlier template omitted are all documented now")
        void thePreviouslyOmittedNamesAreDocumented() {
            assertThat(TEMPLATE_ASSIGNMENTS.keySet())
                    .as(
                            "these are the names the review found used but undocumented, plus the "
                                    + "clock zone added with the single production Clock bean")
                    .contains(
                            "AWS_REGION",
                            "JWT_ISSUER",
                            "OTEL_EXPORTER_OTLP_ENDPOINT",
                            "POSTGRES_HOST",
                            "CARDDEMO_TIME_ZONE");
        }
    }

    /** LIVE - every documented name is read by a committed consumer. */
    @Nested
    @DisplayName("LIVE - every documented name is read by a committed consumer")
    final class Live {

        @ParameterizedTest(name = "{0} has a consumer")
        @MethodSource("com.cardemo.unit.config.EnvironmentTemplateContractTest#templateNames")
        @DisplayName("each documented name is read by a profile, another committed consumer, or relaxed binding")
        void eachDocumentedNameIsConsumed(final String name) {
            if (PLACEHOLDERS_READ.containsKey(name) || RELAXED_BINDING_NAMES.contains(name)) {
                return;
            }
            assertThat(consumerOutsideProfiles(name))
                    .as(
                            "%s is assigned at .env.example:%d but no profile placeholder, %s, and "
                                    + "no relaxed-binding exemption reads it, so it documents a "
                                    + "setting that does not exist",
                            name, TEMPLATE_ASSIGNMENTS.get(name), OTHER_CONSUMERS)
                    .isNotNull();
        }

        @Test
        @DisplayName("the template assigns a non-trivial name set, so a silent empty pass is impossible")
        void theTemplateIsNotVacuous() {
            assertThat(TEMPLATE_ASSIGNMENTS.keySet())
                    .as(".env.example must document a substantial set of names")
                    .hasSizeGreaterThanOrEqualTo(30);
        }
    }

    /** ONE SPELLING - no superseding variable is shipped beside the group it would mask. */
    @Nested
    @DisplayName("ONE SPELLING - no directly-binding variable is shipped beside the group it masks")
    final class OneSpelling {

        @ParameterizedTest(name = "{0} is absent from .env.example")
        @MethodSource(
                "com.cardemo.unit.config.EnvironmentTemplateContractTest#supersedingNames")
        @DisplayName("each superseding variable is absent from the template")
        void eachSupersedingNameIsAbsent(final String name) {
            assertThat(TEMPLATE_ASSIGNMENTS)
                    .as(
                            "%s binds its Spring property directly, at a higher precedence than "
                                    + "any profile file. Shipping it here would leave the "
                                    + "decomposed placeholders it masks permanently unevaluated "
                                    + "while the template still booted, which is the exact drift "
                                    + "this file exists to prevent",
                            name)
                    .doesNotContainKey(name);
        }

        @Test
        @DisplayName("each masked decomposed group is documented, so the surviving spelling is the usable one")
        void theDecomposedSpellingIsTheOneShipped() {
            assertThat(TEMPLATE_ASSIGNMENTS.keySet())
                    .as(
                            "the composed forms are excluded, so the decomposed groups they would "
                                    + "have masked must be present and complete")
                    .contains(
                            "POSTGRES_HOST",
                            "POSTGRES_PORT",
                            "POSTGRES_DB",
                            "POSTGRES_USER",
                            "POSTGRES_PASSWORD",
                            "OTEL_EXPORTER_OTLP_ENDPOINT");
        }
    }

    /**
     * The superseding names, as parameterised-test arguments.
     *
     * @return one argument per forbidden name
     */
    private static Stream<String> supersedingNames() {
        return new TreeSet<>(new LinkedHashSet<>(SUPERSEDING_NAMES)).stream();
    }

    /**
     * Every port {@code docker-compose.yml} publishes is qualified with a host address (H-14).
     */
    @Nested
    @DisplayName("H-14: every published compose port binds an explicit host address")
    final class PublishedPortBinding {

        /** The variable that decides the bind address, and its safe default. */
        private static final String BIND_EXPRESSION = "${CARDDEMO_BIND_ADDRESS:-127.0.0.1}";

        /**
         * Matches a published port entry: a list item whose value is a quoted mapping. Long-form
         * {@code host_ip}/{@code published} entries are not used in this file, so a mapping is the only shape
         * a published port takes here.
         */
        private static final Pattern PUBLISHED_PORT =
                Pattern.compile("^\\s*-\\s*\"([^\"]*:[^\"]*)\"\\s*$");

        @Test
        @DisplayName("no published port omits its host address, so none can bind every interface")
        void everyPublishedPortNamesItsHostAddress() {
            final List<String> unqualified = new ArrayList<>();
            boolean inPorts = false;

            for (final String line : lines("docker-compose.yml")) {
                final String trimmed = line.strip();
                if (trimmed.equals("ports:")) {
                    inPorts = true;
                    continue;
                }
                if (inPorts && !trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("-")) {
                    // A sibling key at the service level ends the ports block.
                    inPorts = false;
                }
                if (!inPorts) {
                    continue;
                }
                final Matcher mapping = PUBLISHED_PORT.matcher(line);
                if (mapping.matches() && !mapping.group(1).startsWith(BIND_EXPRESSION + ":")) {
                    unqualified.add(trimmed);
                }
            }

            assertThat(unqualified)
                    .as("A mapping written as \"8080:8080\" binds 0.0.0.0, so it publishes the service to "
                            + "anywhere that can route to the host. Every mapping in this file must begin "
                            + BIND_EXPRESSION + ": so the stack is loopback-only unless an operator opts in "
                            + "to remote exposure. Rule 1 clause D, least privilege for configuration.")
                    .isEmpty();
        }

        @Test
        @DisplayName("every published port goes through the one bind expression, spelled identically")
        void allPublishedPortsShareOneBindExpression() {
            final long qualified = lines("docker-compose.yml").stream()
                    .filter(line -> line.contains(BIND_EXPRESSION + ":"))
                    .filter(line -> PUBLISHED_PORT.matcher(line).matches())
                    .count();

            // Seven mappings across six services: the application, PostgreSQL, the object-store emulator,
            // the trace store's UI and its OTLP/HTTP receiver, Prometheus and Grafana. One spelling, so a
            // change of default cannot reach only some of them.
            //
            // Seven and not eight because the trace store's OTLP/gRPC receiver is deliberately NOT published:
            // the exporter this application is configured with speaks OTLP over HTTP, and the collector
            // reaches the store over the compose network without either receiver being published at all. A
            // port published for nothing is reach granted for nothing, which is the position Rule 1 clause D
            // takes. Should a gRPC exporter ever be configured, publishing 4317 must add a mapping here
            // rather than change this count silently.
            assertThat(qualified).isEqualTo(7L);
        }

        @Test
        @DisplayName("the variable is documented in .env.example with the loopback default")
        void theBindAddressIsDocumented() {
            assertThat(lines(".env.example"))
                    .as("an operator has to be told the variable exists, what its default is, and what "
                            + "setting it to 0.0.0.0 means, or the safe default is merely an obstacle")
                    .anyMatch(line -> line.strip().equals("CARDDEMO_BIND_ADDRESS=127.0.0.1"));
        }
    }
}
