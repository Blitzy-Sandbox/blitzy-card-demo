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

    /**
     * The two evidence registers, neither of which exists at this commit, claimed by a verb of record.
     *
     * <p>The intervening {@code [^.;]{0,60}?} is what makes this rule cover the wording a review actually
     * found rather than only the adjacent form. "Recorded in DECISION_LOG.md" and "Recorded <em>as a
     * preserved quirk</em> in DECISION_LOG.md" assert exactly the same untrue thing, and an earlier version
     * of this pattern required the verb and the {@code in} to be adjacent, so the second slipped past it four
     * times. The bound is lazy and stops at a sentence break, so the rule cannot reach across a full stop and
     * pair a verb in one sentence with a register named in the next.
     */
    private static final Pattern REGISTER_CLAIM = Pattern.compile(
            "\\b(?:is|are|and|,)?\\s*(?:recorded|tracked|documented|logged|justified|cited|captured|noted"
                    + "|registered|entered|listed|reflected|labelled|labeled|marked|disclosed|explained"
                    + "|declared|stated|flagged|acknowledged|attributed)\\b[^.;]{0,60}?\\bin\\s+"
                    + "(?!(?:the\\s+)?planned\\b)"
                    + "(?:\\{@code\\s+)?(?:DECISION_LOG\\.md|TRACEABILITY_MATRIX\\.md)",
            Pattern.CASE_INSENSITIVE);

    /**
     * The same untrue assertion made attributively rather than with a verb of record.
     *
     * <p>"This one <em>carries a</em> {@code DECISION_LOG.md} entry" and "each <em>carrying a</em>
     * {@code TRACEABILITY_MATRIX.md} row" claim a held entry without using any verb {@link #REGISTER_CLAIM}
     * looks for, which is how four of them survived a guard that had been running over these very files since
     * before they were written. The optional closing brace matters and is not cosmetic: the tree writes the
     * register inside {@code {@code ...}}, so a pattern that expected the noun immediately after the filename
     * matches nothing at all - a defect that made a scan of this same rule silently report zero.
     */
    private static final Pattern REGISTER_POSSESSION = Pattern.compile(
            "\\b(?:carr(?:y|ies|ying)|with|has|have|having|bearing|bears)\\b[^.;]{0,40}?"
                    + "(?!(?:the\\s+)?planned\\b)"
                    + "(?:\\{@code\\s+)?(?:DECISION_LOG\\.md|TRACEABILITY_MATRIX\\.md)\\}?"
                    + "\\s*(?:entry|entries|row|rows)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Sources exempt from the string-literal scan because they enumerate the forbidden phrasings verbatim.
     *
     * <p>A gate has to be able to spell out what it forbids, and both of these do: this class quotes every
     * pattern's offender and its honest rewording in {@code TheGuardDiscriminates}, and
     * {@code PackageDocumentationInventoryTest} holds a literal list of the four claim phrases it rejects,
     * behind its own {@code everyOccurrenceIsQuoted} check. Exempting them from the literal scan is the same
     * concession {@link #SELF} already makes for the comment scan, extended to the tier that scan did not
     * reach. {@link #thePhraseListingExemptionIsEarned} keeps it honest by requiring each exempt file to
     * still be a gate, so the exemption cannot be inherited by a file that merely makes the claim.
     */
    private static final List<String> PHRASE_LISTING_SOURCES =
            List.of(SELF, "PackageDocumentationInventoryTest.java");

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

    /**
     * The two forbidden phrasings for denying a test class, with the denied name captured.
     *
     * <p>It replaces a pattern that was built per test class name from {@code Pattern.quote(name)} and
     * compiled inside the matching loop. Capturing the name instead makes one pattern serve every name, which
     * is what turns the check from a product of three growing quantities into one pass over the sentences;
     * {@code noExistingTestClassIsDescribedAsAbsent} records the measurement. The capture is {@code \w+}
     * because a test class name is a Java identifier, so it spans exactly what the quoted literal spanned,
     * and the required closing brace keeps it from matching a longer name that merely ends with the one being
     * looked for.
     */
    private static final Pattern TEST_CLASS_DENIAL = Pattern.compile(
            "\\bno\\s+\\{@code\\s+(\\w+)\\}\\s+exists"
                    + "|\\{@code\\s+(\\w+)\\}\\s+(?:is\\s+not\\s+available|does\\s+not\\s+exist)",
            Pattern.CASE_INSENSITIVE);

    /**
     * One double-quoted string literal's contents.
     *
     * <p>Escaped quotes are admitted so a message containing {@code \"} does not truncate the run. No attempt
     * is made to distinguish a literal from a character class inside a regex literal, because a false claim is
     * a false claim wherever it is spelled out, and the pattern's own sentence rules keep the noise down.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

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

    /**
     * Reflows each run of string-literal-bearing code lines into sentences, the way {@link #claims} does for
     * comments.
     *
     * <p>An assertion message is documentation. It is the sentence a reader is shown when the build fails,
     * and a false claim in one is read by more people than a false claim in a Javadoc paragraph. But
     * {@link #claims} collects only lines beginning {@code *}, {@code /*} or {@code //}, so every claim living
     * inside an {@code .as(...)} message was invisible to this class - which is how four survived. They are
     * gathered separately rather than by widening {@link #claims} itself, because that method feeds four other
     * guards whose patterns were written against prose, and turning code strings into their input would change
     * what those four assert as a side effect of fixing this one.
     *
     * <p>Concatenated literals are joined across lines before splitting, since the tree routinely builds one
     * message from several {@code + "..."} fragments and the claim usually straddles the join.
     *
     * @param file the source to read
     * @return one entry per sentence found in a string literal
     */
    private static List<Claim> literalClaims(final Path file) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        final List<Claim> result = new ArrayList<>();
        final StringBuilder run = new StringBuilder();
        int startLine = 0;
        for (int i = 0; i < lines.size(); i++) {
            final String trimmed = lines.get(i).trim();
            final boolean comment =
                    trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//");
            final Matcher literal = STRING_LITERAL.matcher(lines.get(i));
            boolean any = false;
            if (!comment) {
                while (literal.find()) {
                    if (run.isEmpty()) {
                        startLine = i + 1;
                    }
                    run.append(literal.group(1)).append(' ');
                    any = true;
                }
            }
            if (!any && !run.isEmpty()) {
                addSentences(result, file, startLine, run.toString());
                run.setLength(0);
            }
        }
        if (!run.isEmpty()) {
            addSentences(result, file, startLine, run.toString());
        }
        return result;
    }

    /**
     * Applies a forbidden pattern to both the comment tier and the string-literal tier.
     *
     * @param forbidden the pattern whose matches are offences
     * @return every offending sentence, named by file and line
     */
    private static List<String> offendersIncludingLiterals(final Pattern forbidden) {
        final List<String> found = new ArrayList<>(offenders(forbidden));
        for (final Path file : sources()) {
            if (PHRASE_LISTING_SOURCES.contains(file.getFileName().toString())) {
                continue;
            }
            for (final Claim claim : literalClaims(file)) {
                if (forbidden.matcher(claim.sentence()).find()) {
                    found.add(claim.describe());
                }
            }
        }
        return found;
    }

    /**
     * Returns every test class name one sentence denies the existence of, in the order the sentence names
     * them.
     *
     * <p>A sentence may deny more than one name, and one pass collects them all. Exactly one of the pattern's
     * two capturing groups participates in any given match - they belong to different alternatives - so the
     * one that is not {@code null} is the captured name. Names are de-duplicated, because a sentence that
     * denies the same class twice is one offence, which is what the former per-name matching reported.
     *
     * @param sentence one comment sentence
     * @return the denied names, empty if the sentence denies none
     */
    private static Set<String> deniedTestClassNames(final String sentence) {
        final Matcher matcher = TEST_CLASS_DENIAL.matcher(sentence);
        final Set<String> denied = new LinkedHashSet<>();
        while (matcher.find()) {
            denied.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        }
        return denied;
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
            assertThat(offendersIncludingLiterals(REGISTER_CLAIM))
                    .as("neither register exists at this commit, so an entry can only be owed, never held; "
                            + "reword as \"owed an entry in the planned DECISION_LOG.md\". This now covers "
                            + "assertion messages as well as comments, and admits words between the verb and "
                            + "the register, because both gaps were used")
                    .isEmpty();
        }

        @Test
        @DisplayName("nothing is described as carrying an entry or a row in either absent register")
        void noRegisterIsDescribedAsAlreadyCarryingAnEntry() {
            assertThat(offendersIncludingLiterals(REGISTER_POSSESSION))
                    .as("an attributive claim asserts the same untrue thing as a verb of record: a file that "
                            + "\"carries a DECISION_LOG.md entry\" is claiming an entry in a document that "
                            + "does not exist. Reword as \"owed an entry in the planned DECISION_LOG.md\"")
                    .isEmpty();
        }

        @Test
        @DisplayName("the phrase-listing exemption is earned, so it cannot be inherited by a file that claims")
        void thePhraseListingExemptionIsEarned() {
            for (final String exempt : PHRASE_LISTING_SOURCES) {
                final List<Path> matches = sources().stream()
                        .filter(p -> p.getFileName().toString().equals(exempt))
                        .toList();
                if (exempt.equals(SELF)) {
                    // This class removes itself from sources() outright, so it is absent by construction.
                    assertThat(matches).as("%s is excluded from the scan at source", exempt).isEmpty();
                    continue;
                }
                assertThat(matches)
                        .as("%s is exempt from the literal scan, so it must exist - an exemption naming a "
                                + "file that is gone is an exemption nobody is checking", exempt)
                        .hasSize(1);
                final String body;
                try {
                    body = Files.readString(matches.getFirst());
                } catch (final IOException e) {
                    throw new UncheckedIOException("cannot read " + matches.getFirst(), e);
                }
                assertThat(body)
                        .as("%s is exempt only because it is itself a gate that spells out what it forbids. "
                                + "If it stops asserting, the exemption must be withdrawn rather than left "
                                + "to shelter a claim", exempt)
                        .contains("@Test")
                        .contains("isEmpty()");
            }
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
            // Walked once and reused. The two loops below both need the same file list, and each call to
            // sources() re-walks both source roots.
            final List<Path> files = sources();

            final Set<String> existing = new LinkedHashSet<>();
            for (final Path file : files) {
                final String name = file.getFileName().toString();
                if (name.endsWith("Test.java")) {
                    existing.add(name.substring(0, name.length() - ".java".length()));
                }
            }
            assertThat(existing).as("the unit tier must contain test classes for this rule to have force")
                    .hasSizeGreaterThan(20);

            // ONE MATCH PER SENTENCE, NOT ONE PER SENTENCE AND TEST CLASS NAME.
            //
            // This loop used to compile a pattern per test class name at its innermost point and match every
            // sentence against every one of them, so both the compilation count and the match count were the
            // product of three growing quantities: source files, documented sentences within them, and test
            // class names. On the present tier that is 191 files times some 37,000 claim sentences times 180
            // names - above ten million compilations and as many matches. The method ran for more than half an
            // hour without finishing, and every file added to the repository made it worse superlinearly - a
            // growing tax on every future change rather than a fixed cost.
            //
            // Inverting it removes the product outright. TEST_CLASS_DENIAL, compiled once as a constant,
            // recognises the two forbidden phrasings and CAPTURES the name they deny; the name is then looked
            // up in the set of names that exist. Hoisting a per-name pattern out of the loop would have cut
            // the compilations alone and left the match count untouched, so the capture is taken instead and
            // the cheap {@code ...} pre-filter below keeps the sentences that cannot match out of the scan
            // entirely. The outcome is identical, and deliberately so: a test class name is a Java identifier,
            // so the captured \w+ spans exactly what Pattern.quote(name) used to match, and a captured name
            // that is not a real test class simply fails the lookup where it formerly failed the match.
            // Offenders are still reported one per claim and name, in the order of the existing set, so the
            // message a failure produces is unchanged too.
            final List<String> stale = new ArrayList<>();
            for (final Path file : files) {
                for (final Claim claim : claims(file)) {
                    // Every denial phrasing requires a {@code ...} reference, so a sentence without one
                    // cannot match either alternative and need not be scanned at all.
                    final String sentence = claim.sentence();
                    if (!sentence.contains("{@code")) {
                        continue;
                    }
                    final Set<String> denied = deniedTestClassNames(sentence);
                    if (denied.isEmpty()) {
                        continue;
                    }
                    for (final String testName : existing) {
                        if (denied.contains(testName)) {
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
            assertThat(REGISTER_CLAIM
                    .matcher("Recorded as a preserved quirk in {@code DECISION_LOG.md}.").find())
                    .as("words between the verb and the register must not buy an escape: this exact wording "
                            + "passed the adjacent-only form four times")
                    .isTrue();
            assertThat(REGISTER_CLAIM
                    .matcher("It is recorded here. An entry in {@code DECISION_LOG.md} is owed.").find())
                    .as("the intervening bound must stop at a sentence break, so a verb in one sentence "
                            + "cannot be paired with a register named in the next")
                    .isFalse();
            assertThat(REGISTER_CLAIM
                    .matcher("it is labelled explicitly as a deviation in {@code DECISION_LOG.md}.").find())
                    .as("a claim does not need a verb of record to be a claim. This exact sentence used "
                            + "'labelled', which no earlier version of this rule listed, and it survived a "
                            + "guard running over its own file")
                    .isTrue();

            assertThat(REGISTER_POSSESSION
                    .matcher("This one carries a {@code DECISION_LOG.md} entry.").find())
                    .as("an attributive claim must be caught; the closing brace sits between the filename "
                            + "and the noun, and a rule that forgot it matched nothing")
                    .isTrue();
            assertThat(REGISTER_POSSESSION
                    .matcher("each carrying a {@code TRACEABILITY_MATRIX.md} row - but").find())
                    .as("the participle form and the row noun must be caught too")
                    .isTrue();
            assertThat(REGISTER_POSSESSION
                    .matcher("is owed an entry in the planned {@code DECISION_LOG.md} plus a row").find())
                    .as("the owed rewording must survive both rules, or the fix could not be written")
                    .isFalse();
            assertThat(REGISTER_POSSESSION
                    .matcher("destined for a {@code DECISION_LOG.md} entry and a row").find())
                    .as("a future-tense obligation is not a claim of a held entry; 'destined for' says the "
                            + "entry is owed, which is exactly what is true")
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
