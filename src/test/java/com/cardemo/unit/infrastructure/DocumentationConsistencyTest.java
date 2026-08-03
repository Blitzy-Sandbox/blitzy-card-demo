/*
 * ****************************************************************************
 * Program     : DocumentationConsistencyTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Keeps the tree's documentation from drifting away from the tree.
 *               Three invariants: no file may assert that a present artefact is
 *               absent; no file may claim that a decision is recorded in a
 *               document that does not exist; and the retained-parity register
 *               stays bounded to its three enumerated sites.
 * Source      : app/** (the frozen corpus, whose measured state every claim in
 *                 the migrated tree is written against)
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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds the tree's own documentation to the tree's measured state.
 *
 * <p>Documentation drift in this project is not a cosmetic problem, because the documentation <em>is</em> the
 * evidence. Three specific drifts were found and are each closed by an invariant here.
 *
 * <p><strong>Stale absence claims.</strong> Dozens of docstrings asserted that
 * {@code V2__create_indexes.sql}, {@code V3__seed_data.sql}, the four profile files,
 * {@code SecurityConfig}, {@code CardDemoApplication}, {@code HealthIndicators}, {@code MetricsConfig} and
 * {@code logback-spring.xml} did not exist. Every one of them does. A reader following such a claim
 * concludes that a whole layer is missing, and worse, contract text written as "what that file must satisfy"
 * reads as speculation when it is in fact reconciled against real DDL. {@link NoStaleAbsenceClaims} forbids
 * the assertion for any artefact the test can see on disk.
 *
 * <p><strong>Claims about a document that does not exist.</strong> {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md} are scheduled artefacts absent from this branch, yet hundreds of sites said
 * a decision was "recorded in" or "tracked in" one of them. That sends a reader to nothing. The convention is
 * now a forward reference - "tracked <em>for</em>", never "tracked <em>in</em>" - and the decision itself
 * lives in the docstring of the file it governs. {@link NoClaimsOnAbsentEvidence} forbids the present-tense
 * form and re-checks the premise, so if either document is ever authored the gate itself demands revision.
 *
 * <p><strong>An unbounded register.</strong> "Retained for parity" was applied to five sites in one place
 * and three in another. The term means something precise - a reachable no-op or unused constant that exists
 * only so the paragraph map stays provable - and a preserved COBOL {@code CONTINUE} or a preserved asymmetry
 * is not one. {@link BoundedRetainedRegister} pins the register to its three enumerated sites.
 *
 * <p>All three scans skip {@code docs/project-guide.md}, which is frozen prior-run evidence about a previous
 * attempt rather than a claim this branch makes.
 */
@DisplayName("Documentation consistency - claims must match the tree's measured state")
final class DocumentationConsistencyTest {

    /** The repository root, located once and reused. */
    private static final Path ROOT = repositoryRoot();

    /** Extensions scanned for prose claims. */
    private static final List<String> SCANNED_EXTENSIONS =
            List.of(".java", ".yml", ".yaml", ".sql", ".xml", ".sh", ".properties", ".md");

    /**
     * Files excluded from the scan because they are frozen evidence of an earlier run.
     *
     * <p>{@code docs/project-guide.md} records the state of a <em>previous</em> migration attempt. Its
     * absence claims were true when it was written and rewriting them would destroy the evidence, so it is
     * excluded by path rather than by weakening any pattern.
     */
    private static final List<String> EXCLUDED_FILES = List.of("docs/project-guide.md");

    /**
     * Prepositions that make the following artefact the <em>location</em> of the missing thing rather than
     * the missing thing itself.
     */
    private static final List<String> CONTAINER_PREPOSITIONS =
            List.of("in", "from", "of", "within", "inside", "under", "against", "via", "by", "beside");

    /**
     * How many characters before an absence phrase are searched for its subject.
     *
     * <p>Wide enough to span a qualified path and an intervening clause, narrow enough that an artefact
     * named in a different clause of a long sentence does not bind.
     */
    private static final int BINDING_WINDOW = 170;

    /** Longest offence excerpt reported, so a failure names the sentence without flooding the report. */
    private static final int EXCERPT_LIMIT = 160;

    /**
     * How close an artefact must sit to an absence phrase to bind to it despite an intervening relative
     * clause.
     *
     * <p>Two sentence shapes need separating, and they are described here with the placeholder artefact
     * name {@code Xyzzy} rather than a real one, because this gate scans its own source and a realistic
     * specimen would be indistinguishable from an assertion.
     *
     * <ul>
     *   <li>A cleft - "it is {@code Xyzzy} specifically that is missing" - where the artefact IS the
     *       subject even though a relative pronoun intervenes. The pronoun sits within a few characters of
     *       the artefact.</li>
     *   <li>A subordinate clause - "{@code Xyzzy} keeps validate-group-membership at true, so naming a
     *       contributor that is missing fails the context" - where the subject of the absence is the
     *       contributor and the artefact merely opened a much earlier clause.</li>
     * </ul>
     *
     * <p>Distance is what separates them, so a relative pronoun only breaks the binding when the artefact
     * is far away from the absence phrase.
     */
    private static final int ADJACENT_GAP = 60;

    /** Relative pronouns that introduce a new subject between an artefact and an absence phrase. */
    private static final Pattern RELATIVE_PRONOUN =
            Pattern.compile("\\b(?:that|which|who|whose|whom)\\b", Pattern.CASE_INSENSITIVE);

    /** Comment and markup leaders stripped when reconstructing a sentence from wrapped lines. */
    private static final Pattern COMMENT_LEADER =
            Pattern.compile("^\\s*(?:\\*/|/\\*\\*|\\*|//|--|#)\\s?");

    /** Sentence terminators, including the block-level HTML tags Javadoc uses instead of a full stop. */
    private static final Pattern SENTENCE_BREAK =
            Pattern.compile("(?<=[.;:])\\s+|</p>|</li>|</ul>|</ol>|<h2>|<li>");

    /** Directories scanned in full. */
    private static final List<String> SCANNED_DIRECTORIES =
            List.of("src", "docs", "observability", "localstack-init", ".github");

    /** Individually named root files scanned. */
    private static final List<String> SCANNED_FILES =
            List.of("pom.xml", "Dockerfile", "docker-compose.yml", ".env.example");

    /**
     * Artefacts whose absence was asserted somewhere and which are all present. Each entry is the repository
     * path, paired with the shortest spelling a docstring uses for it.
     */
    private static final List<String[]> PRESENT_ARTEFACTS = List.of(
            new String[] {"src/main/resources/db/migration/V1__create_schema.sql", "V1__create_schema.sql"},
            new String[] {"src/main/resources/db/migration/V2__create_indexes.sql", "V2__create_indexes.sql"},
            new String[] {"src/main/resources/db/migration/V3__seed_data.sql", "V3__seed_data.sql"},
            new String[] {"src/main/resources/application.yml", "application.yml"},
            new String[] {"src/main/resources/application-local.yml", "application-local.yml"},
            new String[] {"src/main/resources/application-test.yml", "application-test.yml"},
            new String[] {"src/main/resources/application-prod.yml", "application-prod.yml"},
            new String[] {"src/main/resources/logback-spring.xml", "logback-spring.xml"},
            new String[] {"src/main/java/com/cardemo/config/SecurityConfig.java", "SecurityConfig"},
            new String[] {"src/main/java/com/cardemo/CardDemoApplication.java", "CardDemoApplication"},
            new String[] {"src/main/java/com/cardemo/observability/HealthIndicators.java", "HealthIndicators"},
            new String[] {"src/main/java/com/cardemo/observability/MetricsConfig.java", "MetricsConfig"});

    /**
     * Assertions of absence. Each is anchored so it can only match a claim <em>about</em> something, never a
     * conditional diagnostic such as "a group naming a contributor that does not exist fails the context".
     */
    private static final List<Pattern> ABSENCE_ASSERTIONS = List.of(
            Pattern.compile("do(?:es)? not (?:yet )?exist(?!s)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("remains? (?:<[a-z]+>)?not available", Pattern.CASE_INSENSITIVE),
            Pattern.compile("remain (?:<[a-z]+>)?not available", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:is|are) (?:<[a-z]+>)?Not available"),
            Pattern.compile("has not been (?:created|authored|written)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("planned[;,]? (?:but )?absent", Pattern.CASE_INSENSITIVE),
            Pattern.compile("no such file exists", Pattern.CASE_INSENSITIVE),
            Pattern.compile("is unauthored", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:is|are|was|were) (?:<[a-z]+>)?(?:still )?absent", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:is|are) not (?:yet )?(?:present|created|authored)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:was|were) not available", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:is|are) (?:<[a-z]+>)?unavailable", Pattern.CASE_INSENSITIVE),
            Pattern.compile("still not available", Pattern.CASE_INSENSITIVE));

    /**
     * Markers that identify a line as a <em>withdrawal</em> of a former absence claim rather than a fresh
     * one. A withdrawal must name the correction, which is why the markers are specific.
     */
    private static final List<String> WITHDRAWAL_MARKERS = List.of(
            "withdrawn", "earlier revision", "no longer", "is present now", "has since been",
            "was recorded", "were recorded", "at the time", "when this", "measurably wrong",
            "stale absence", "is present", "are present", "exists and", "closed:", "corrected");

    /** The two scheduled evidence documents that do not exist in this branch. */
    private static final List<String> ABSENT_EVIDENCE_DOCUMENTS =
            List.of("DECISION_LOG.md", "TRACEABILITY_MATRIX.md");

    /** Verbs that, followed by "in &lt;absent document&gt;", claim the record already lives there. */
    private static final Pattern PRESENT_TENSE_EVIDENCE_CLAIM = Pattern.compile(
            "(recorded|tracked|documented|justified|logged|cited|entered|noted|captured|carried)"
                    + " in (\\{@code )?(DECISION_LOG\\.md|TRACEABILITY_MATRIX\\.md)",
            Pattern.CASE_INSENSITIVE);

    /** The three sites the retained-parity register admits, each named in the root package documentation. */
    private static final List<String> REGISTER_SITES =
            List.of("RejectCode", "computeFees1400", "statement processor");

    /**
     * Walks upward from the working directory to the repository root.
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
                "No directory from " + start + " upward holds pom.xml, src/ and app/, so documentation "
                        + "claims cannot be checked against the tree. Run this test with the repository "
                        + "root, or any directory beneath it, as the working directory.");
    }

    /**
     * Collects every scanned text file.
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
                        .filter(path -> !EXCLUDED_FILES.contains(
                                ROOT.relativize(path).toString().replace('\\', '/')))
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
     * One reconstructed sentence together with the line it starts on.
     *
     * @param line 1-based line number of the sentence's first character
     * @param text the sentence, with comment leaders removed and wrapped lines joined
     */
    private record Claim(int line, String text) {}

    /**
     * Reconstructs sentences from lines, joining text that a comment block wrapped across several of them.
     *
     * <p>Scanning line by line cannot see a claim whose subject and predicate were split by wrapping - and
     * because Javadoc, YAML comments and SQL comments all wrap at a column, that is the normal case rather
     * than the exception. Every claim this gate exists to catch is a sentence, so sentences are what it
     * examines.
     *
     * @param content the file's lines, in order
     * @return every sentence in the file, each tagged with the line it begins on
     */
    private static List<Claim> claims(final List<String> content) {
        final List<Claim> claims = new ArrayList<>();
        final StringBuilder buffer = new StringBuilder();
        int startLine = 1;
        for (int index = 0; index < content.size(); index++) {
            final String stripped = COMMENT_LEADER.matcher(content.get(index)).replaceFirst("").strip();
            if (stripped.isEmpty()) {
                flush(claims, buffer, startLine);
                startLine = index + 2;
                continue;
            }
            if (buffer.isEmpty()) {
                startLine = index + 1;
            }
            buffer.append(stripped).append(' ');
            final String[] parts = SENTENCE_BREAK.split(buffer.toString(), -1);
            if (parts.length > 1) {
                for (int part = 0; part < parts.length - 1; part++) {
                    record(claims, parts[part], startLine);
                }
                buffer.setLength(0);
                buffer.append(parts[parts.length - 1]);
                startLine = index + 1;
            }
        }
        flush(claims, buffer, startLine);
        return claims;
    }

    /**
     * Emits whatever remains in the buffer as a final sentence and clears it.
     *
     * @param claims   accumulator
     * @param buffer   pending text
     * @param startLine line the pending text began on
     */
    private static void flush(
            final List<Claim> claims, final StringBuilder buffer, final int startLine) {
        record(claims, buffer.toString(), startLine);
        buffer.setLength(0);
    }

    /**
     * Adds one non-blank sentence to the accumulator with its whitespace normalised.
     *
     * @param claims    accumulator
     * @param text      candidate sentence
     * @param startLine line the sentence began on
     */
    private static void record(final List<Claim> claims, final String text, final int startLine) {
        final String normalised = text.strip().replaceAll("\\s+", " ");
        if (!normalised.isEmpty()) {
            claims.add(new Claim(startLine, normalised));
        }
    }

    /**
     * Finds where the earliest absence assertion starts in a sentence.
     *
     * @param text the sentence
     * @return the offset, or {@code -1} when the sentence asserts no absence
     */
    private static int firstAbsenceOffset(final String text) {
        int earliest = -1;
        for (final Pattern pattern : ABSENCE_ASSERTIONS) {
            final Matcher matcher = pattern.matcher(text);
            if (matcher.find() && (earliest < 0 || matcher.start() < earliest)) {
                earliest = matcher.start();
            }
        }
        return earliest;
    }

    /**
     * Removes a trailing code-span opener so the preposition before it becomes visible.
     *
     * <p>A sentence reading &quot;does not exist in &#123;&#64;code application.yml&#125;&quot; puts the
     * sequence &quot;in &#123;&#64;code &quot; between the preposition and the artefact. The braces are
     * written as character entities because a Javadoc inline tag cannot carry an unbalanced brace, not even
     * inside {@literal @literal}. Without stripping the opener the preposition test never matches and a
     * correct sentence is reported as an offence.
     *
     * @param before text preceding an artefact spelling
     * @return the same text with a trailing code-span opener and whitespace removed
     */
    private static String stripCodeSpanOpener(final String before) {
        return before.stripTrailing()
                .replaceAll("[`]$", "")
                .replaceAll("\\{@code$", "")
                .replaceAll("\\{@link$", "")
                .stripTrailing();
    }

    /**
     * Shortens a sentence for inclusion in a failure message.
     *
     * @param text the sentence
     * @return the sentence, truncated with an ellipsis when it exceeds the excerpt limit
     */
    private static String abbreviate(final String text) {
        return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT) + "...";
    }

    /** F18 - no file may assert that a present artefact is absent. */
    @Nested
    @DisplayName("no stale absence claim about an artefact that is on disk")
    final class NoStaleAbsenceClaims {

        @Test
        @DisplayName("every named present artefact really is present, so the scan below is not vacuous")
        void thePremiseHolds() {
            for (final String[] artefact : PRESENT_ARTEFACTS) {
                assertThat(ROOT.resolve(artefact[0]))
                        .as(
                                "%s is listed as a present artefact. If it has been removed, remove it from "
                                        + "this list rather than leaving the list wrong",
                                artefact[0])
                        .exists();
            }
            assertThat(PRESENT_ARTEFACTS).hasSizeGreaterThanOrEqualTo(12);
        }

        @Test
        @DisplayName("no sentence asserts that one of them does not exist, unless it withdraws an old claim")
        void noLineAssertsAPresentArtefactIsAbsent() {
            final List<String> offences = new ArrayList<>();
            for (final Path path : scannedFiles()) {
                final String relative = ROOT.relativize(path).toString();
                for (final Claim claim : claims(lines(relative))) {
                    final int absenceAt = firstAbsenceOffset(claim.text());
                    if (absenceAt < 0) {
                        continue;
                    }
                    final String lowered = claim.text().toLowerCase(Locale.ROOT);
                    if (WITHDRAWAL_MARKERS.stream().anyMatch(lowered::contains)) {
                        continue;
                    }
                    for (final String[] artefact : PRESENT_ARTEFACTS) {
                        if (bindsToAbsence(claim.text(), artefact[1], absenceAt)) {
                            offences.add(relative + ":" + claim.line() + "  " + abbreviate(claim.text()));
                            break;
                        }
                    }
                }
            }
            assertThat(offences)
                    .as(
                            "each line asserts that an artefact which IS on disk is absent. Documentation is "
                                    + "this project's evidence mechanism, so a stale absence claim tells a "
                                    + "reader a whole layer is missing and turns contract text reconciled "
                                    + "against real DDL back into speculation. Rewrite the claim as measured "
                                    + "state, or as an explicit withdrawal of the earlier one")
                    .isEmpty();
        }

        /**
         * Reports whether the artefact appears as the <em>container</em> of the missing thing rather than as
         * the thing that is missing.
         *
         * <p>"{@code CARDDEMO_S3_OUTPUT_BUCKET} does not exist <b>in</b> {@code application.yml}" is a claim
         * about a variable spelling, and it presupposes that {@code application.yml} exists - so it is not a
         * stale absence claim at all. Distinguishing the two is what keeps this gate from forcing correct
         * prose to be rewritten, and it is a narrowing of the rule rather than an exemption: the artefact
         * must be the SUBJECT of the absence, not its location.
         *
         * @param line     the whole line
         * @param artefact the artefact spelling found on it
         * @return {@code true} when the artefact is preceded by {@code in} and is therefore the container
         */
        private boolean containedRatherThanSubject(final String line, final String artefact) {
            final int at = line.indexOf(artefact);
            final String before = stripCodeSpanOpener(line.substring(0, at));
            return CONTAINER_PREPOSITIONS.stream().anyMatch(before::endsWith);
        }

        /**
         * Reports whether an artefact spelling is the grammatical subject of an absence phrase.
         *
         * <p>Naming an artefact somewhere in a sentence that also contains an absence phrase is not the same
         * as claiming that artefact is absent. {@code "@throws FatalProcessingException if it is absent or
         * wider than the field"} mentions {@code FileStatusMapper} elsewhere in the same sentence while the
         * thing that may be absent is an argument value. Requiring the artefact to appear <em>before</em> the
         * absence phrase, and close to it, is what separates the two: English puts the subject ahead of its
         * predicate, so a spelling that only appears after the phrase is being named for some other reason.
         *
         * @param text      the reconstructed sentence
         * @param artefact  the artefact spelling to test
         * @param absenceAt offset of the absence phrase within {@code text}
         * @return {@code true} when the artefact binds to the absence phrase as its subject
         */
        private boolean bindsToAbsence(final String text, final String artefact, final int absenceAt) {
            final int window = Math.max(0, absenceAt - BINDING_WINDOW);
            final String subjectRegion = text.substring(window, absenceAt);
            final int at = subjectRegion.lastIndexOf(artefact);
            if (at < 0) {
                return false;
            }
            final String before = stripCodeSpanOpener(subjectRegion.substring(0, at));
            if (CONTAINER_PREPOSITIONS.stream().anyMatch(before::endsWith)) {
                return false;
            }
            final String gap = subjectRegion.substring(at + artefact.length());
            return gap.length() <= ADJACENT_GAP || !RELATIVE_PRONOUN.matcher(gap).find();
        }
    }

    /**
     * Reports whether every occurrence of a phrase on a line sits inside quotation marks.
     *
     * <p>A gate has to be able to NAME the wording it forbids. The detector patterns below, the fixtures that
     * exercise them and the retraction notes that record a superseded claim all quote the offending phrase,
     * and a scan that counted those quotations reported the enforcement as the offence - and, worse, could
     * only be satisfied by deleting the enforcement. A phrase inside quotation marks is being <em>named</em>;
     * a phrase written as prose is being <em>asserted</em>. Only the second is a claim.
     *
     * @param line    the source line, never {@code null}
     * @param phrase  the matched phrase, never {@code null}
     * @return {@code true} when every occurrence of the phrase on that line is quoted
     */
    private static boolean everyOccurrenceIsQuoted(final String line, final String phrase) {
        int from = 0;
        while (true) {
            final int at = line.indexOf(phrase, from);
            if (at < 0) {
                return true;
            }
            final long quotesBefore = line.chars().limit(at).filter(c -> c == '"').count();
            if (quotesBefore % 2 == 0) {
                return false;
            }
            from = at + phrase.length();
        }
    }

    /** F19 - no file may claim a decision is recorded in a document that does not exist. */
    @Nested
    @DisplayName("no claim that a decision is recorded in a document that does not exist")
    final class NoClaimsOnAbsentEvidence {

        @Test
        @DisplayName("neither scheduled evidence document exists, which is the premise this gate rests on")
        void thePremiseHolds() {
            for (final String document : ABSENT_EVIDENCE_DOCUMENTS) {
                assertThat(ROOT.resolve(document))
                        .as(
                                "%s is outside this change's file inventory and must not be created here. If "
                                        + "it is ever authored, this gate must be revised deliberately rather "
                                        + "than passing by accident",
                                document)
                        .doesNotExist();
            }
        }

        @Test
        @DisplayName("no site says a decision is recorded, tracked or cited IN either document")
        void noPresentTenseEvidenceClaim() {
            final List<String> offences = new ArrayList<>();
            for (final Path path : scannedFiles()) {
                final String relative = ROOT.relativize(path).toString();
                final List<String> content = lines(relative);
                for (int index = 0; index < content.size(); index++) {
                    final Matcher matcher = PRESENT_TENSE_EVIDENCE_CLAIM.matcher(content.get(index));
                    if (matcher.find() && !everyOccurrenceIsQuoted(content.get(index), matcher.group())) {
                        offences.add(relative + ":" + (index + 1) + "  " + matcher.group());
                    }
                }
            }
            assertThat(offences)
                    .as(
                            "these sites send a reader to a document that is not in this branch. The "
                                    + "convention is a forward reference - \"tracked FOR\", never \"tracked "
                                    + "IN\" - with the decision itself in the docstring of the file it "
                                    + "governs, which is where it cannot drift from the code")
                    .isEmpty();
        }

        @Test
        @DisplayName("the root package documentation states once, for the whole tree, where the record lives")
        void theConventionIsStatedOnce() {
            final String rootDoc = String.join(" ", lines("src/main/java/com/cardemo/package-info.java"));
            assertThat(rootDoc)
                    .as(
                            "one authoritative statement is what keeps the other 78 files from each having "
                                    + "to qualify every mention")
                    .contains("scheduled artefacts that do not")
                    .contains("in the docstring of the file it");
        }
    }

    /** F19 - the retained-parity register stays bounded. */
    @Nested
    @DisplayName("the retained-parity register is bounded to three enumerated sites")
    final class BoundedRetainedRegister {

        @Test
        @DisplayName("the root package documentation enumerates exactly the three register sites")
        void theRegisterIsEnumerated() {
            final String rootDoc = String.join(" ", lines("src/main/java/com/cardemo/package-info.java"));
            assertThat(rootDoc)
                    .as("the register must be bounded in one place, or the term spreads")
                    .contains("Exactly three sites carry that retained-for-parity status");
            for (final String site : REGISTER_SITES) {
                assertThat(rootDoc)
                        .as("register site %s must be named in the enumeration", site)
                        .contains(site);
            }
        }

        @Test
        @DisplayName("no file claims a different register size")
        void noFileClaimsADifferentRegisterSize() {
            final Pattern wrongCount = Pattern.compile(
                    "(tree has (two|four|five|six|seven) of them|"
                            + "(two|four|five|six|seven) retained parity artefacts)",
                    Pattern.CASE_INSENSITIVE);
            final List<String> offences = new ArrayList<>();
            for (final Path path : scannedFiles()) {
                final String relative = ROOT.relativize(path).toString();
                final List<String> content = lines(relative);
                for (int index = 0; index < content.size(); index++) {
                    final Matcher matcher = wrongCount.matcher(content.get(index));
                    if (matcher.find() && !everyOccurrenceIsQuoted(content.get(index), matcher.group())) {
                        offences.add(relative + ":" + (index + 1) + "  " + matcher.group());
                    }
                }
            }
            assertThat(offences)
                    .as(
                            "the register holds three. A file counting a different number is what made the "
                                    + "term look over-applied: a preserved COBOL CONTINUE, a preserved "
                                    + "asymmetry and a preserved absent guard are documented source "
                                    + "behaviour at their own locators, not register entries")
                    .isEmpty();
        }
    }
}
