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
import org.junit.jupiter.params.provider.ValueSource;

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
 *   <li><strong>ONE ASSIGNMENT</strong> - no name is assigned twice, and no name that carries
 *       credential material ships with a usable value. This one was added after the template was found
 *       assigning five names twice, the four least-privilege database role variables among them:
 *       an empty declaration in one block and a populated one in a later block, where
 *       last-assignment dotenv semantics quietly activated predictable {@code carddemo_app} and
 *       {@code carddemo_migrator} passwords while the earlier empty lines and the surrounding prose
 *       both read as though nothing had been committed. A map keyed by name cannot see that
 *       collision, which is why {@link #TEMPLATE_OCCURRENCES} keeps every line. See {@link Assignments}.
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

    /** The same assignment, capturing the value that follows it so an empty one can be told apart. */
    private static final Pattern ASSIGNMENT_WITH_VALUE = Pattern.compile("^([A-Z][A-Z0-9_]*)=(.*)$");

    /**
     * The two names whose committed value is deliberately non-empty although the name reads like a
     * secret. Both are LocalStack placeholders: the emulator validates no credential, the pair
     * exists only to displace the SDK's default provider chain, and {@code AwsConfig} refuses any
     * value beginning {@code AKIA} or {@code ASIA}, so neither can address a real account. The
     * review that prompted this assertion said so explicitly - they are not the defect.
     */
    private static final Set<String> INERT_CREDENTIAL_PLACEHOLDERS =
            Set.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY");

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

    /** Template variable name to every one-based line assigning it, so a repeat is visible. */
    private static final Map<String, List<Integer>> TEMPLATE_OCCURRENCES = templateOccurrences();

    /** Template variable name to every value assigned to it, in file order. */
    private static final Map<String, List<String>> TEMPLATE_VALUES = templateValues();

    /**
     * Name fragments that make a variable a secret, so its template value must not be a usable one.
     *
     * <p>Matched as an infix on the upper-case name rather than as a suffix, because the names in play are
     * {@code JWT_SIGNING_KEY}, {@code AWS_SECRET_ACCESS_KEY}, {@code NVD_API_KEY} and
     * {@code METRICS_SCRAPE_PASSWORD} - the significant word sits in the middle as often as at the end.
     */
    private static final List<String> SECRET_NAME_FRAGMENTS =
            List.of("PASSWORD", "SECRET", "TOKEN", "CREDENTIAL", "_KEY", "KEY_");

    /**
     * The only secret-named variables permitted a non-empty template value, each with the exact value.
     *
     * <p>Both are the emulator's own fixed placeholders. LocalStack accepts any credential and the SDK
     * refuses to sign a request without one, so the pair has to be present for a local start to work at all;
     * {@code com.cardemo.config.AwsConfig} additionally enforces an endpoint allowlist, so neither value can
     * reach a real account. They are enumerated with their values rather than merely exempted by name, so
     * substituting a real key for either fails this suite.
     */
    private static final Map<String, String> PERMITTED_EMULATOR_PLACEHOLDERS =
            Map.of("AWS_ACCESS_KEY_ID", "test", "AWS_SECRET_ACCESS_KEY", "test");

    /**
     * Non-secret variables whose template value must still never be the name of the principal it selects.
     *
     * <p>These four form one atomic set with the two passwords beside them, and the defect this closes
     * assigned each of them a value equal to its own role name. A role name is not a secret, but a template
     * that ships {@code carddemo_app} as both the user and its password teaches the pattern that produced the
     * finding, so the user names are held empty too and the whole quartet is populated together or not at all.
     */
    private static final List<String> ATOMIC_ROLE_NAMES = List.of(
            "CARDDEMO_DB_APP_USER",
            "CARDDEMO_DB_APP_PASSWORD",
            "CARDDEMO_DB_MIGRATION_USER",
            "CARDDEMO_DB_MIGRATION_PASSWORD");

    /**
     * Names that carry credential material and must therefore ship with an empty value.
     *
     * <p>The list is deliberately explicit rather than pattern-matched on {@code PASSWORD} or {@code KEY}: a
     * pattern would silently stop covering a name that is renamed, and it would also sweep in
     * {@code AWS_ACCESS_KEY_ID}, whose committed value {@code test} is the LocalStack convention and is not a
     * secret at all. Each entry here is a real credential for a real principal.
     *
     * <p><strong>The two role USER names are deliberately not here, and that is a contract rather than an
     * exemption.</strong> A role name is an identifier the provisioning step creates and the application
     * binds, not credential material - and {@code docker-compose.yml} guards all four role variables with
     * {@code ${VAR:?}} and no {@code :-} default, where Compose reads an EMPTY value as unset. Listing the
     * two names here would require them to ship empty, which would stop {@code docker compose config} on a
     * value an operator has no way to choose. {@code Assignments.bothRoleNamesShipPopulated} asserts the
     * other half of that contract, and the two PASSWORDS remain the operator's to generate.
     */
    private static final List<String> CREDENTIAL_NAMES = List.of(
            "JWT_SIGNING_KEY",
            "POSTGRES_PASSWORD",
            "CARDDEMO_DB_APP_PASSWORD",
            "CARDDEMO_DB_MIGRATION_PASSWORD",
            "GRAFANA_ADMIN_PASSWORD",
            "METRICS_SCRAPE_PASSWORD");

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
     * Collects every assignment the template makes, keeping <em>every</em> occurrence of a repeated name.
     *
     * <p><strong>Why a list and not a map value.</strong> An earlier revision of this method kept one line
     * per name with {@code Map.put}, so a second assignment of the same name silently replaced the first and
     * the duplicate became invisible to every assertion below. That is not a hypothetical: the template
     * shipped the whole least-privilege section twice, and the second copy assigned
     * {@code CARDDEMO_DB_APP_PASSWORD=carddemo_app} where the first left it empty. Shell and Compose
     * {@code env_file} semantics are last-assignment-wins, so the predictable value was the effective one
     * while the reviewed, empty declaration sat above it looking authoritative. Collecting occurrences is
     * what lets {@link Assignments} refuse that shape outright.
     *
     * @return variable name to every one-based line in {@code .env.example} that assigns it, in file order
     */
    private static Map<String, List<Integer>> templateOccurrences() {
        final Map<String, List<Integer>> found = new LinkedHashMap<>();
        final List<String> content = lines(".env.example");
        for (int index = 0; index < content.size(); index++) {
            final Matcher matcher = ASSIGNMENT.matcher(content.get(index));
            if (matcher.find()) {
                found.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                        .add(Integer.valueOf(index + 1));
            }
        }
        return found;
    }

    /**
     * Collects the value each assignment carries, keyed the same way as {@link #templateOccurrences()}.
     *
     * @return variable name to every assigned value, in file order, with surrounding whitespace stripped
     */
    private static Map<String, List<String>> templateValues() {
        final Map<String, List<String>> found = new LinkedHashMap<>();
        final List<String> content = lines(".env.example");
        for (final String line : content) {
            final Matcher matcher = ASSIGNMENT.matcher(line);
            if (matcher.find()) {
                found.computeIfAbsent(matcher.group(1), key -> new ArrayList<>())
                        .add(line.substring(matcher.end()).strip());
            }
        }
        return found;
    }

    /**
     * Collects every variable the template assigns, taking the <em>first</em> occurrence's line.
     *
     * <p>First rather than last deliberately: {@link Assignments} has already refused any repeat, so the two
     * readings can only differ on a template this suite would reject anyway, and reporting the first
     * occurrence points a reader at the declaration they are most likely to have read.
     *
     * @return variable name to its one-based line in {@code .env.example}
     */
    private static Map<String, Integer> templateAssignments() {
        final Map<String, Integer> found = new LinkedHashMap<>();
        templateOccurrences().forEach((name, occurrences) -> found.put(name, occurrences.get(0)));
        return found;
    }

    /**
     * The names that carry credential material, as parameterised-test arguments.
     *
     * @return one argument per documented name whose spelling marks it as a password or a key,
     *     excluding the two inert emulator placeholders
     */
    private static Stream<String> credentialNames() {
        return TEMPLATE_OCCURRENCES.keySet().stream()
                .filter(name -> name.contains("_KEY") || name.endsWith("_PASSWORD"))
                .filter(name -> !INERT_CREDENTIAL_PLACEHOLDERS.contains(name))
                .sorted();
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
     * The four least-privilege database-role names, as parameterised-test arguments.
     *
     * @return one argument per member of the atomic quartet
     */
    private static Stream<String> atomicRoleNames() {
        return ATOMIC_ROLE_NAMES.stream();
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

        @Test
        @DisplayName("no name is assigned twice, because a dotenv loader lets the LAST assignment win")
        void noNameIsAssignedTwice() {
            // The defect this pins. The template carried two least-privilege blocks and two lock-timeout
            // blocks, and TEMPLATE_ASSIGNMENTS is a Map keyed by name - so the duplicate was invisible to
            // every other guard in this class, which only ever saw the surviving entry. A dotenv loader and
            // `set -a; . ./.env` both take the LAST assignment, so a second block silently overrode the
            // first and the file contradicted itself. Counting raw assignment LINES rather than distinct
            // names is what makes that visible.
            final Map<String, Integer> occurrences = new LinkedHashMap<>();
            final List<String> content = lines(".env.example");
            for (final String line : content) {
                final Matcher matcher = ASSIGNMENT.matcher(line);
                if (matcher.find()) {
                    occurrences.merge(matcher.group(1), 1, Integer::sum);
                }
            }
            assertThat(occurrences.entrySet().stream()
                            .filter(entry -> entry.getValue() > 1)
                            .map(Map.Entry::getKey)
                            .toList())
                    .as(
                            "each of these names is assigned more than once in .env.example. The later "
                                    + "assignment wins, so the earlier one is documentation of a value that "
                                    + "never takes effect. Declare each name exactly once")
                    .isEmpty();
        }

        @Test
        @DisplayName("every credential-bearing name ships EMPTY, so no predictable secret is committed")
        void noCredentialShipsWithAValue() {
            // Rule 1 Clause D forbids a secret in configuration. The template previously shipped
            // CARDDEMO_DB_APP_PASSWORD=carddemo_app and CARDDEMO_DB_MIGRATION_PASSWORD=carddemo_migrator -
            // predictable credentials for the two least-privilege roles, which defeats the point of
            // splitting them off the superuser in the first place. An empty assignment documents the name
            // and commits nothing, which is the shape every one of these must keep.
            final List<String> offenders = new ArrayList<>();
            for (final String line : lines(".env.example")) {
                final Matcher matcher = ASSIGNMENT.matcher(line);
                if (matcher.find() && CREDENTIAL_NAMES.contains(matcher.group(1))) {
                    final String value = line.substring(line.indexOf('=') + 1).trim();
                    if (!value.isEmpty()) {
                        offenders.add(matcher.group(1) + "=" + value);
                    }
                }
            }
            assertThat(offenders)
                    .as(
                            "each of these ships a committed credential value. Assign the name and leave the "
                                    + "value empty; local values are generated during setup, never checked in")
                    .isEmpty();
            assertThat(TEMPLATE_ASSIGNMENTS.keySet())
                    .as("the credential names this rule protects must actually be in the template, or it "
                            + "passes by scanning nothing")
                    .containsAll(CREDENTIAL_NAMES);
        }
    }

    /**
     * ONE ASSIGNMENT PER NAME, AND NO USABLE SECRET DEFAULT.
     *
     * <p>Two invariants that are about the template as a <em>file</em> rather than about the property graph,
     * and that the three above could not have caught between them.
     *
     * <p><strong>The defect.</strong> The template shipped its whole least-privilege section twice. The first
     * copy left the four database-role values empty; the second assigned each one a value equal to its own
     * role name - {@code CARDDEMO_DB_APP_PASSWORD=carddemo_app} and
     * {@code CARDDEMO_DB_MIGRATION_PASSWORD=carddemo_migrator}. Shell {@code source} and Compose
     * {@code env_file} both take the last assignment, so the predictable values were the effective ones while
     * the empty declarations sat above them looking authoritative, and a developer copying the template got a
     * database whose two least-privilege principals had guessable passwords. Rule 1 clause D asks for least
     * privilege in configuration; a predictable shared credential is the opposite of it.
     *
     * <p><strong>Why the duplicate matters independently of the value.</strong> A repeated assignment is a
     * determinism failure in its own right - Rule 1 clause C - because which value takes effect depends on the
     * consumer's ordering rule rather than on the file's evident intent. So the repeat is refused for every
     * name, not only for the secret-named ones.
     */
    @Nested
    @DisplayName("ONE ASSIGNMENT - no name is assigned twice, and no secret ships a usable default")
    final class Assignments {

        @Test
        @DisplayName("no variable is assigned more than once, whatever its value")
        void noVariableIsAssignedTwice() {
            final Map<String, List<Integer>> repeated = new LinkedHashMap<>();
            TEMPLATE_OCCURRENCES.forEach((name, occurrences) -> {
                if (occurrences.size() > 1) {
                    repeated.put(name, occurrences);
                }
            });

            assertThat(repeated)
                    .as("""
                            A repeated assignment is resolved by the consumer's ordering rule, not by the \
                            file: shell `source` and Compose `env_file` both take the LAST one, so the \
                            declaration a reviewer reads need not be the one that takes effect. Delete the \
                            duplicate rather than reconciling the two values. Repeats found (name -> lines \
                            in .env.example): %s""", repeated)
                    .isEmpty();
        }

        @ParameterizedTest(name = "{0} ships no usable secret value")
        @MethodSource("com.cardemo.unit.config.EnvironmentTemplateContractTest#templateNames")
        @DisplayName("every secret-named variable is empty, or is an enumerated emulator placeholder")
        void everySecretNamedVariableIsEmptyOrAnEnumeratedPlaceholder(final String name) {
            if (SECRET_NAME_FRAGMENTS.stream().noneMatch(name::contains)) {
                return;
            }
            final List<String> values = TEMPLATE_VALUES.get(name);
            final String permitted = PERMITTED_EMULATOR_PLACEHOLDERS.get(name);
            if (permitted != null) {
                assertThat(values)
                        .as("%s is the emulator's fixed placeholder, so its value is pinned exactly rather "
                                + "than merely exempted: any other value here would be a real credential in "
                                + "a tracked file", name)
                        .containsExactly(permitted);
                return;
            }
            assertThat(values)
                    .as("%s is secret-named, so the template documents that it exists and supplies nothing. "
                            + "A committed default is a credential in version control even when it looks "
                            + "like a sample, and every profile resolves this name from the environment with "
                            + "no fallback. Assigned at .env.example:%s",
                            name, TEMPLATE_OCCURRENCES.get(name))
                    .containsOnly("");
        }

        @ParameterizedTest(name = "{0} is not its own role name")
        @ValueSource(strings = {"CARDDEMO_DB_APP_PASSWORD", "CARDDEMO_DB_MIGRATION_PASSWORD"})
        @DisplayName("neither least-privilege role PASSWORD ships a value, predictable or otherwise")
        void neitherLeastPrivilegeRolePasswordShipsAValue(final String name) {
            // The quartet is atomic in the sense that all four are required, not in the sense that all four
            // are secret: the two role NAMES are the contract the provisioning step creates and the
            // application binds, and bothRoleNamesShipPopulated asserts they ship spelled out because the
            // compose file guards all four with `${VAR:?}` and no default. The two PASSWORDS are the
            // operator's to generate out of band, and the defect this closes assigned each of them a value
            // equal to its own role name.
            assertThat(TEMPLATE_VALUES.get(name))
                    .as("""
                            %s is a credential and the template documents that it exists while supplying \
                            nothing. The defect this refuses shipped it equal to its own role name, which \
                            teaches a predictable shared password as the pattern.""", name)
                    .containsOnly("");
        }

        @Test
        @DisplayName("all four least-privilege role variables are documented, because compose guards all four")
        void allFourLeastPrivilegeRoleVariablesAreDocumented() {
            assertThat(atomicRoleNames().toList())
                    .as("the quartet docker-compose.yml guards with `${VAR:?}` and no default")
                    .containsExactly("CARDDEMO_DB_APP_USER", "CARDDEMO_DB_APP_PASSWORD",
                            "CARDDEMO_DB_MIGRATION_USER", "CARDDEMO_DB_MIGRATION_PASSWORD");
            assertThat(TEMPLATE_OCCURRENCES.keySet())
                    .as("every one must be documented in the template, or an operator cannot supply a value "
                            + "the compose file refuses to default")
                    .containsAll(ATOMIC_ROLE_NAMES);
        }

        @Test
        @DisplayName("the value scan really read values, so an empty-string pass cannot be vacuous")
        void theValueScanIsNotVacuous() {
            assertThat(TEMPLATE_VALUES)
                    .as("the parse must have produced a value list for every name it found")
                    .hasSameSizeAs(TEMPLATE_OCCURRENCES);
            assertThat(TEMPLATE_VALUES.values().stream().flatMap(List::stream).filter(value -> !value.isEmpty())
                    .count())
                    .as("""
                            Most template values are non-empty - ports, bucket names, the emulator endpoint - \
                            so a parse that produced empty strings everywhere would make the assertions above \
                            pass without reading anything. This is the guard against that.""")
                    .isGreaterThanOrEqualTo(20L);
            assertThat(SECRET_NAME_FRAGMENTS.stream()
                    .flatMap(fragment -> TEMPLATE_VALUES.keySet().stream().filter(n -> n.contains(fragment)))
                    .distinct()
                    .toList())
                    .as("and the secret-name rule must actually select the names it exists for")
                    .contains("JWT_SIGNING_KEY", "POSTGRES_PASSWORD", "METRICS_SCRAPE_PASSWORD",
                            "GRAFANA_ADMIN_PASSWORD", "CARDDEMO_DB_APP_PASSWORD",
                            "CARDDEMO_DB_MIGRATION_PASSWORD", "AWS_SECRET_ACCESS_KEY", "NVD_API_KEY");
        }

        @ParameterizedTest(name = "{0} ships with no value")
        @MethodSource("com.cardemo.unit.config.EnvironmentTemplateContractTest#credentialNames")
        @DisplayName("every documented credential ships empty, so copying the template commits nothing")
        void everyCredentialShipsEmpty(final String name) {
            assertThat(TEMPLATE_VALUES.get(name))
                    .as("""
                            %s is assigned at .env.example:%s. Rule 1 Clause D forbids secrets in \
                            configuration, and a template value is a committed credential however harmless it \
                            looks - a predictable one is worse than none, because a deployment that never \
                            changed it is indistinguishable from one that chose it. Generate the value out of \
                            band and put it only in the ignored .env.""",
                            name, TEMPLATE_OCCURRENCES.get(name))
                    .containsOnly("");
        }

        @Test
        @DisplayName("the credential scan is not vacuous and covers both least-privilege role passwords")
        void theCredentialScanCoversTheRolePasswords() {
            assertThat(credentialNames().toList())
                    .as("an exemption or a renaming must not be able to empty this scan silently")
                    .hasSizeGreaterThanOrEqualTo(7)
                    .contains(
                            "JWT_SIGNING_KEY",
                            "POSTGRES_PASSWORD",
                            "CARDDEMO_DB_APP_PASSWORD",
                            "CARDDEMO_DB_MIGRATION_PASSWORD",
                            "GRAFANA_ADMIN_PASSWORD",
                            "METRICS_SCRAPE_PASSWORD");
        }

        @ParameterizedTest(name = "{0} ships with the role name the provisioning step creates")
        @ValueSource(strings = {"CARDDEMO_DB_APP_USER", "CARDDEMO_DB_MIGRATION_USER"})
        @DisplayName("both role NAMES ship populated, because docker-compose.yml requires all four")
        void bothRoleNamesShipPopulated(final String name) {
            // This is the one place the two reviews reached opposite conclusions, and the compose file
            // settles it. All four role variables are guarded with `${VAR:?}` and no `:-` default, and
            // Compose treats an EMPTY value as unset for that form - so a template shipping the two names
            // empty would stop `docker compose config` on a value an operator has no way to choose. The two
            // passwords remain the operator's to generate; the two names are the contract the provisioning
            // step creates and the application binds, so they ship spelled out. See
            // com.cardemo.unit.config.DataTierPrincipalContractTest for the compose-side half.
            assertThat(TEMPLATE_VALUES.get(name))
                    .as("""
                            A role name is not a credential, and all four variables are guarded with \
                            Compose's `:?` rather than defaulted, so an empty name here would fail \
                            `docker compose config`. Assigned at .env.example:%s""",
                            TEMPLATE_OCCURRENCES.get(name))
                    .isNotEmpty()
                    .noneMatch(String::isEmpty);
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
     * DECLARED ONCE - no variable is assigned twice, whatever the prose around it says.
     *
     * <p>The template had grown a duplicated section: {@code POSTGRES_LOCK_TIMEOUT_MS} was assigned
     * twice and the whole least-privilege role block appeared twice, once with the four values blank
     * and once with predictable passwords. Shell sourcing keeps the LAST assignment, so the committed
     * passwords were the effective ones while the blank block above them read as authoritative - the
     * two halves of the file disagreed and the one a reader trusted was not the one that applied.
     *
     * <p>{@link #TEMPLATE_ASSIGNMENTS} cannot see this: it is a map, so a second assignment silently
     * replaces the first. This group counts assignments per name instead, which is the only way the
     * duplication is visible at all.
     */
    @Nested
    @DisplayName("DECLARED ONCE - every documented variable is assigned exactly once")
    final class DeclaredOnce {

        /**
         * Counts how many times the template assigns each name.
         *
         * @return variable name to the one-based lines that assign it, in file order
         */
        private Map<String, List<Integer>> assignmentsByName() {
            final Map<String, List<Integer>> found = new LinkedHashMap<>();
            final List<String> content = lines(".env.example");
            for (int index = 0; index < content.size(); index++) {
                final Matcher matcher = ASSIGNMENT.matcher(content.get(index));
                if (matcher.find()) {
                    found.computeIfAbsent(matcher.group(1), name -> new ArrayList<>()).add(index + 1);
                }
            }
            return found;
        }

        @Test
        @DisplayName("no name is assigned twice, so the effective value is the one a reader sees")
        void noNameIsAssignedTwice() {
            final Map<String, List<Integer>> duplicated = new LinkedHashMap<>();
            assignmentsByName().forEach((name, at) -> {
                if (at.size() > 1) {
                    duplicated.put(name, at);
                }
            });

            assertThat(duplicated)
                    .as("""
                            Each name here is assigned more than once in .env.example, with the line                             numbers listed. A later assignment wins when the file is sourced, so a                             duplicate makes the earlier block - and any prose above it - a false                             statement about what the value will be.""")
                    .isEmpty();
        }

        @Test
        @DisplayName("the assignment scan is not vacuous, so an empty file could not pass it")
        void theScanIsNotVacuous() {
            assertThat(assignmentsByName())
                    .as("the template documents the whole environment surface, not a handful of names")
                    .hasSizeGreaterThan(30);
        }
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

    @Nested
    @DisplayName("F-027: no committed secret, and no name assigned twice")
    class SecretHygiene {

        /** Names whose value is a credential and must therefore ship empty. */
        private static final Pattern SECRET_BEARING =
                Pattern.compile("^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|SIGNING_KEY|API_KEY)$");

        /**
         * The one credential-shaped name that legitimately carries a value.
         *
         * <p>{@code AWS_SECRET_ACCESS_KEY=test} is the emulator's own fixed dummy: LocalStack accepts any
         * credential and the AAP forbids a live one, so {@code test} is not a secret and blanking it would
         * break every local AWS call for no security gain. {@code AWS_ACCESS_KEY_ID} carries the same value
         * but does not match the pattern above, so only this one needs naming.
         */
        private static final String EMULATOR_DUMMY = "AWS_SECRET_ACCESS_KEY";

        @Test
        @DisplayName("every credential-shaped name ships empty, so no checkout shares a working password")
        void noSecretBearingNameShipsAValue() {
            final List<String> shipped = new ArrayList<>();
            for (final String line : lines(".env.example")) {
                final Matcher matcher = ASSIGNMENT.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                final String name = matcher.group(1);
                final String value = line.substring(matcher.end());
                if (SECRET_BEARING.matcher(name).matches()
                        && !name.equals(EMULATOR_DUMMY)
                        && !value.isBlank()) {
                    shipped.add(name);
                }
            }

            assertThat(shipped)
                    .as("a committed default password is a committed secret however local it is meant to be "
                            + "(Rule 1 Clause D). The template documents how to generate local-only values "
                            + "into a gitignored .env instead; it never ships one")
                    .isEmpty();
        }

        @Test
        @DisplayName("no name is assigned twice, so no later value can silently override an earlier one")
        void noNameIsAssignedTwice() {
            final Map<String, List<Integer>> byName = new LinkedHashMap<>();
            final List<String> content = lines(".env.example");
            for (int index = 0; index < content.size(); index++) {
                final Matcher matcher = ASSIGNMENT.matcher(content.get(index));
                if (matcher.find()) {
                    byName.computeIfAbsent(matcher.group(1), key -> new ArrayList<>()).add(index + 1);
                }
            }
            final List<String> repeated = byName.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1)
                    .map(entry -> entry.getKey() + " at lines " + entry.getValue())
                    .toList();

            assertThat(repeated)
                    .as("finding F-027 was exactly this: the four database credential names were assigned a "
                            + "second time further down the file, with working values, and because a later "
                            + "assignment wins those overrode the empty quartet above. The duplication is "
                            + "what hid it, so uniqueness is asserted here rather than only blankness")
                    .isEmpty();
        }

        @Test
        @DisplayName("the scan sees real assignments, so neither assertion above can pass vacuously")
        void theScanIsNotVacuous() {
            final long assignments = lines(".env.example").stream()
                    .filter(line -> ASSIGNMENT.matcher(line).find())
                    .count();

            assertThat(assignments)
                    .as("the template must still assign its documented environment; an empty scan would make "
                            + "both assertions above meaningless")
                    .isGreaterThan(30L);
            assertThat(TEMPLATE_ASSIGNMENTS.keySet())
                    .as("the four credential names must still be documented, blank but present")
                    .contains("CARDDEMO_DB_APP_PASSWORD", "CARDDEMO_DB_MIGRATION_PASSWORD",
                            "POSTGRES_PASSWORD", "JWT_SIGNING_KEY");
        }
    }

}
