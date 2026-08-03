/*
 * ******************************************************************
 * Program     : EvidenceHonestyTest
 * Application : CardDemo
 * Type        : JUnit 5 unit test - repository-wide documentation guard
 * Function    : Asserts that no comment anywhere in src/ claims an absent
 *               artefact exists, or claims a present artefact is absent.
 * Source      : No COBOL counterpart. The frozen corpus has no evidence
 *               register and no documentation gate; this class exists only to
 *               enforce Rule 1 Clause F (honest "Not available" disclosure)
 *               mechanically instead of by review. @ 7756d89
 * ******************************************************************
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
 * ******************************************************************
 */
package com.cardemo.unit.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Repository-wide guard on the honesty of documentation claims.
 *
 * <h2>What this class does and why it exists</h2>
 *
 * <p>Four review findings in this project shared one root cause: a comment asserted something about the tree
 * that the tree did not support. Three shapes recurred. A comment claimed an artefact <em>exists</em> when it
 * does not - decisions "recorded in {@code DECISION_LOG.md}", a step "declared by {@code BatchConfig}", a test
 * that "lives at" a path holding no file. A comment claimed an artefact is <em>absent</em> when it is present -
 * migrations, configuration profiles, the application entry point and entity tests that all arrived in a later
 * unit of work while the prose that predated them was never revisited. And prose cited a named test class that
 * was never authored.
 *
 * <p>Every one of those was fixed by hand. This class exists so that none of them can silently return. It is
 * deliberately a <strong>data-driven scan of the source tree</strong> rather than a set of hand-written
 * expectations: a fixed list of forbidden strings would itself go stale, which is the very defect being
 * guarded against.
 *
 * <h2>How to build, run and test</h2>
 *
 * <p>Run this class alone with
 * {@code ./mvnw -B -ntp -o test -Dtest=EvidenceHonestyTest -Djacoco.skip=true}, or as part of the unit tier
 * with {@code ./mvnw -B -ntp test}. It needs no Spring context, no database and no container: it reads files
 * from the working directory and asserts on their text, so it runs in milliseconds and cannot be affected by
 * the environment. Compilation is {@code -Xlint:all -Werror} at release 25, so any warning here fails the
 * build.
 *
 * <h2>Key configuration and defaults</h2>
 *
 * <p>There is no configuration key and no default value. The two scanned roots are {@code src/main/java} and
 * {@code src/test/java}, resolved relative to the working directory, which Surefire sets to the module
 * base directory. If neither root resolves the tests fail rather than passing vacuously, because a guard that
 * silently scans nothing is worse than no guard at all.
 *
 * <h2>Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A failure naming {@code DECISION_LOG.md} or {@code TRACEABILITY_MATRIX.md}</strong> means a
 *       comment states that something is already recorded in a register that does not exist at this commit.
 *       Reword it to the obligation - "owed an entry in the planned {@code DECISION_LOG.md}" - rather than
 *       creating the file, which is owned elsewhere.</li>
 *   <li><strong>A failure naming {@code BatchConfig} or {@code BatchPipelineOrchestrator}</strong> means a
 *       comment attributes present-tense behaviour to a class that has not been authored. Qualify the
 *       reference as planned.</li>
 *   <li><strong>A failure naming a test path</strong> means prose says a test lives somewhere it does not.
 *       Either author the test or state the coverage as owed.</li>
 *   <li><strong>A failure from the stale-absence group</strong> is the opposite case: prose says an artefact
 *       is absent, and it is now present. Re-measure and withdraw the claim explicitly rather than deleting
 *       the sentence, so the correction is visible to the next reader.</li>
 *   <li><strong>Withdrawal wording is always permitted.</strong> A sentence that quotes a former claim in
 *       order to retract it necessarily contains the forbidden phrasing, so any sentence carrying a
 *       withdrawal marker such as "earlier revision", "withdrawn" or "re-measured" is exempt. That is what
 *       makes an honest correction expressible.</li>
 *   </ul>
 */
@DisplayName("Documentation honesty - no comment may misstate what the tree contains")
class EvidenceHonestyTest {

    /** Roots scanned. Both must resolve, or the tests fail rather than passing vacuously. */
    private static final List<Path> ROOTS = List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"));

    /** This class quotes the forbidden phrasings as literals, so it must exempt itself. */
    private static final String SELF = "EvidenceHonestyTest.java";

    /**
     * Sentences carrying any of these are exempt: they retract a former claim, and a retraction has to be
     * able to quote what it retracts.
     */
    private static final Pattern WITHDRAWAL = Pattern.compile(
            "earlier revision|earlier generation|withdraw|re-measured|re-derive|does not exist at this commit"
                    + "|not available at this commit|no file of that name exists|-Dtest=",
            Pattern.CASE_INSENSITIVE);

    /** The two evidence registers, neither of which exists at this commit. */
    private static final Pattern REGISTER_CLAIM = Pattern.compile(
            "\\b(?:is|are|and|,)?\\s*(?:recorded|tracked|documented|logged|justified|cited|captured|noted"
                    + "|registered|entered|listed)\\s+in\\s+(?!(?:the\\s+)?planned\\b)"
                    + "(?:\\{@code\\s+)?(?:DECISION_LOG\\.md|TRACEABILITY_MATRIX\\.md)",
            Pattern.CASE_INSENSITIVE);

    /** Classes named by the plan that have not been authored; a bare reference implies they exist. */
    private static final Pattern UNAUTHORED_CLASS = Pattern.compile(
            "(?<!planned\\s)\\{@code\\s+com\\.cardemo\\.batch\\.jobs\\.BatchPipelineOrchestrator\\}");

    /**
     * Classes this guard used to cover and no longer does, because they have since been authored.
     *
     * <p>{@code BatchConfig} and {@code ObservabilityConfig} were outstanding when the guard was written and
     * a bare reference to either implied an artefact that did not exist. Both are now present, so requiring
     * every mention to be qualified as planned would force the documentation to describe delivered classes as
     * pending. The narrowing is self-checking: {@link #theNarrowingOfThisGuardStillHolds} asserts these files
     * exist, so if one is ever removed the guard must be revisited deliberately rather than passing by
     * accident.
     */
    private static final List<String> FORMERLY_UNAUTHORED = List.of(
            "src/main/java/com/cardemo/config/BatchConfig.java",
            "src/main/java/com/cardemo/config/ObservabilityConfig.java");

    /** A test file path asserted in prose. */
    private static final Pattern TEST_PATH = Pattern.compile("src/test/java/[A-Za-z0-9_/]+\\.java");

    /**
     * Wording that cites a test path as an <em>obligation</em> rather than as a fact.
     *
     * <p>This exemption is what separates the two readings of a path. "The test lives at X" asserts that X is
     * on disk and is a defect when it is not. "The test belongs at X", or "the planned X", asserts only where
     * it will go, which stays true before the file exists and remains true after. The guard therefore forbids
     * the first and permits the second, instead of banning the path outright - a rule that banned it would
     * push authors into naming no path at all, which loses the one piece of information a reader needs.
     */
    private static final Pattern OWED_PATH = Pattern.compile(
            "\\bplanned\\b|\\bbelongs?\\b|\\bowed\\b|\\bto be authored\\b|\\bwould live\\b|\\bmust be authored\\b",
            Pattern.CASE_INSENSITIVE);

    /** Artefacts that DO exist; prose may not assert their absence. */
    private static final Pattern STALE_ABSENCE = Pattern.compile(
            "\\{@code\\s+(V1__create_schema\\.sql|V2__create_indexes\\.sql|V3__seed_data\\.sql"
                    + "|application\\*\\.yml|CardDemoApplication|com\\.cardemo\\.config\\.SecurityConfig)\\}"
                    + "\\s+(?:is|are|remains?)\\s+(?:<[^>]+>)?\\s*(?:not available|absent|not present)"
                    + "|\\bno\\s+\\{@code\\s+@SpringBootApplication\\}\\s+entry\\s+point",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.;:])\\s+(?=[A-Z(<{])");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern DOC_PREFIX = Pattern.compile("^/?\\*+\\s?|^//\\s?");

    /** One comment sentence, with enough provenance to name the offender in an assertion message. */
    private record Claim(Path file, int line, String sentence) {

        String describe() {
            return file + ":" + line + " -> " + sentence;
        }
    }

    private static List<Path> sources() {
        final List<Path> found = new ArrayList<>();
        for (final Path root : ROOTS) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(p -> !p.getFileName().toString().equals(SELF))
                        .forEach(found::add);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot walk " + root, e);
            }
        }
        return found;
    }

    /**
     * Reflows each run of comment lines into logical paragraphs and splits those into sentences.
     *
     * <p>Reflowing matters: Javadoc wraps a sentence across lines with a {@code *} prefix between the words,
     * so a line-by-line scan misses any claim that happens to straddle a line break - which is exactly how
     * most of the original findings escaped detection.
     */
    private static List<Claim> claims(final Path file) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        final List<Claim> result = new ArrayList<>();
        final StringBuilder paragraph = new StringBuilder();
        int startLine = 0;
        for (int i = 0; i < lines.size(); i++) {
            final String trimmed = lines.get(i).trim();
            final boolean comment = trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//");
            if (comment) {
                if (paragraph.isEmpty()) {
                    startLine = i + 1;
                }
                paragraph.append(DOC_PREFIX.matcher(trimmed).replaceFirst("")).append(' ');
            } else if (!paragraph.isEmpty()) {
                addSentences(result, file, startLine, paragraph.toString());
                paragraph.setLength(0);
            }
        }
        if (!paragraph.isEmpty()) {
            addSentences(result, file, startLine, paragraph.toString());
        }
        return result;
    }

    private static void addSentences(final List<Claim> sink, final Path file, final int line, final String text) {
        final String flat = WHITESPACE.matcher(HTML_TAG.matcher(text).replaceAll(" ")).replaceAll(" ");
        for (final String sentence : SENTENCE_SPLIT.split(flat)) {
            if (!sentence.isBlank() && !WITHDRAWAL.matcher(sentence).find()) {
                sink.add(new Claim(file, line, sentence.strip()));
            }
        }
    }

    private static List<String> offenders(final Pattern forbidden) {
        final List<String> found = new ArrayList<>();
        for (final Path file : sources()) {
            for (final Claim claim : claims(file)) {
                if (forbidden.matcher(claim.sentence()).find()) {
                    found.add(claim.describe());
                }
            }
        }
        return found;
    }

    @Nested
    @DisplayName("no comment may claim an absent artefact is present")
    class AbsentArtefactsAreNotClaimedPresent {

        @Test
        @DisplayName("the source tree is actually scanned, so a pass is never vacuous")
        void theScanIsNotVacuous() {
            final List<Path> files = sources();
            assertThat(files)
                    .as("both src/main/java and src/test/java must resolve from the working directory %s",
                            Path.of("").toAbsolutePath())
                    .hasSizeGreaterThan(100);
            assertThat(files).noneMatch(p -> p.getFileName().toString().equals(SELF));
        }

        @Test
        @DisplayName("nothing is described as already recorded in DECISION_LOG.md or TRACEABILITY_MATRIX.md")
        void noRegisterIsDescribedAsAlreadyHoldingAnEntry() {
            assertThat(offenders(REGISTER_CLAIM))
                    .as("neither register exists at this commit, so an entry can only be owed, never held; "
                            + "reword as \"owed an entry in the planned DECISION_LOG.md\"")
                    .isEmpty();
        }

        @Test
        @DisplayName("no unauthored configuration class is referenced as though it existed")
        void noUnauthoredClassIsReferencedBare() {
            assertThat(offenders(UNAUTHORED_CLASS))
                    .as("BatchPipelineOrchestrator is not authored at this commit; every reference must be "
                            + "qualified as planned")
                    .isEmpty();
        }

        @Test
        @DisplayName("the classes this guard stopped covering really are authored, so the narrowing is honest")
        void theNarrowingOfThisGuardStillHolds() {
            for (final String authored : FORMERLY_UNAUTHORED) {
                assertThat(Path.of(authored))
                        .as("%s was removed from the unauthored guard because it exists. If it no longer "
                                + "does, the guard must cover it again rather than silently permitting a "
                                + "bare reference to an absent class", authored)
                        .exists();
            }
        }

        @Test
        @DisplayName("every test file path asserted in prose resolves to a real file")
        void everyCitedTestPathExists() {
            final List<String> missing = new ArrayList<>();
            for (final Path file : sources()) {
                for (final Claim claim : claims(file)) {
                    if (OWED_PATH.matcher(claim.sentence()).find()) {
                        continue;
                    }
                    final Matcher m = TEST_PATH.matcher(claim.sentence());
                    while (m.find()) {
                        if (!Files.isRegularFile(Path.of(m.group()))) {
                            missing.add(claim.file() + ":" + claim.line() + " cites " + m.group());
                        }
                    }
                }
            }
            assertThat(missing)
                    .as("prose that says a test *lives* at a path asserts the file exists; either author it, "
                            + "or cite the path as an obligation - \"belongs at\", \"the planned ...\" - which "
                            + "stays true either way")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("no comment may claim a present artefact is absent")
    class PresentArtefactsAreNotClaimedAbsent {

        @Test
        @DisplayName("the artefacts this rule protects really are present, so the rule has force")
        void theProtectedArtefactsExist() {
            assertThat(Path.of("src", "main", "java", "com", "cardemo", "CardDemoApplication.java")).exists();
            assertThat(Path.of("src", "main", "java", "com", "cardemo", "config", "SecurityConfig.java")).exists();
            for (final String migration : List.of("V1__create_schema.sql", "V2__create_indexes.sql",
                    "V3__seed_data.sql")) {
                assertThat(Path.of("src", "main", "resources", "db", "migration", migration)).exists();
            }
            for (final String profile : List.of("application.yml", "application-local.yml",
                    "application-test.yml", "application-prod.yml")) {
                assertThat(Path.of("src", "main", "resources", profile)).exists();
            }
        }

        @Test
        @DisplayName("no migration, profile, entry point or SecurityConfig is described as unavailable")
        void noPresentInfrastructureIsDescribedAsUnavailable() {
            assertThat(offenders(STALE_ABSENCE))
                    .as("these artefacts exist; a stale absence claim sends a reader to look for something "
                            + "that is already there. Re-measure and withdraw the claim explicitly")
                    .isEmpty();
        }

        @Test
        @DisplayName("no existing test class is described as non-existent")
        void noExistingTestClassIsDescribedAsAbsent() {
            final Set<String> existing = new LinkedHashSet<>();
            for (final Path file : sources()) {
                final String name = file.getFileName().toString();
                if (name.endsWith("Test.java")) {
                    existing.add(name.substring(0, name.length() - ".java".length()));
                }
            }
            assertThat(existing).as("the unit tier must contain test classes for this rule to have force")
                    .hasSizeGreaterThan(20);

            final List<String> stale = new ArrayList<>();
            for (final Path file : sources()) {
                for (final Claim claim : claims(file)) {
                    for (final String testName : existing) {
                        final Pattern denial = Pattern.compile(
                                "\\bno\\s+\\{@code\\s+" + Pattern.quote(testName) + "\\}\\s+exists"
                                        + "|\\{@code\\s+" + Pattern.quote(testName)
                                        + "\\}\\s+(?:is\\s+not\\s+available|does\\s+not\\s+exist)",
                                Pattern.CASE_INSENSITIVE);
                        if (denial.matcher(claim.sentence()).find()) {
                            stale.add(claim.file() + ":" + claim.line() + " denies " + testName);
                        }
                    }
                }
            }
            assertThat(stale)
                    .as("%d test classes exist; none may be documented as absent", Integer.valueOf(existing.size()))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the guard discriminates, rather than passing on everything")
    class TheGuardDiscriminates {

        @Test
        @DisplayName("each forbidden pattern matches a synthetic offender and spares its honest rewording")
        void eachPatternMatchesAnOffenderAndSparesTheRewording() {
            assertThat(REGISTER_CLAIM.matcher("The quirk is recorded in {@code DECISION_LOG.md}.").find())
                    .as("the register rule must catch a present-tense claim")
                    .isTrue();
            assertThat(REGISTER_CLAIM
                    .matcher("The quirk is owed an entry in the planned {@code DECISION_LOG.md}.").find())
                    .as("the register rule must allow the owed form")
                    .isFalse();

            assertThat(UNAUTHORED_CLASS.matcher(
                    "a job comes from {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}.").find())
                    .isTrue();
            assertThat(UNAUTHORED_CLASS.matcher(
                    "a job comes from the planned {@code com.cardemo.batch.jobs.BatchPipelineOrchestrator}.")
                    .find())
                    .isFalse();

            assertThat(STALE_ABSENCE.matcher("{@code V3__seed_data.sql} is not available.").find()).isTrue();
            assertThat(STALE_ABSENCE.matcher("{@code V3__seed_data.sql} is present.").find()).isFalse();

            assertThat(WITHDRAWAL
                    .matcher("An earlier revision said it is recorded in {@code DECISION_LOG.md}.").find())
                    .as("a withdrawal must be exempt, or an honest correction could not be written")
                    .isTrue();

            assertThat(OWED_PATH.matcher("The test lives at src/test/java/com/cardemo/unit/XTest.java.").find())
                    .as("a bare existence claim about a test path must NOT be exempted")
                    .isFalse();
            assertThat(OWED_PATH
                    .matcher("The test belongs at src/test/java/com/cardemo/unit/XTest.java.").find())
                    .as("citing a test path as an obligation must be exempted")
                    .isTrue();
        }

        @Test
        @DisplayName("sentence reflow joins a claim split across two Javadoc lines")
        void reflowJoinsAClaimSplitAcrossLines() throws IOException {
            final Path probe = Files.createTempFile("blitzy_adhoc_test_reflow", ".java");
            try {
                Files.writeString(probe, String.join(System.lineSeparator(),
                        "/**", " * The decision is recorded", " * in {@code DECISION_LOG.md}.", " */",
                        "class Probe { }", ""));
                final List<Claim> found = claims(probe);
                assertThat(found).as("the comment must reflow into at least one sentence").isNotEmpty();
                assertThat(found)
                        .as("a claim wrapped across two lines must still be detected, in %s",
                                probe.toAbsolutePath().toString().toLowerCase(Locale.ROOT))
                        .anyMatch(c -> REGISTER_CLAIM.matcher(c.sentence()).find());
            } finally {
                Files.deleteIfExists(probe);
            }
        }
    }
}
