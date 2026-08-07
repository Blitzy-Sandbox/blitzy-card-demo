/*
 * ****************************************************************************
 * Program     : DocumentationConsistencyTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11 (Surefire tier)
 * Function    : Keeps the tree's documentation from drifting away from the tree.
 *               Four invariants: no file may assert that a present artefact is
 *               absent; no file may claim that a decision is recorded in a
 *               document that does not exist; the retained-parity register
 *               stays bounded to its three enumerated sites; and every
 *               traceability row cites a test method that exists and can run.
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
 * evidence. Four specific drifts were found and are each closed by an invariant here.
 *
 * <p><strong>Stale absence claims.</strong> Dozens of docstrings asserted that
 * {@code V2__create_indexes.sql}, {@code V3__seed_data.sql}, the four profile files,
 * {@code SecurityConfig}, {@code CardDemoApplication}, {@code HealthIndicators}, {@code MetricsConfig} and
 * {@code logback-spring.xml} did not exist. Every one of them does. A reader following such a claim
 * concludes that a whole layer is missing, and worse, contract text written as "what that file must satisfy"
 * reads as speculation when it is in fact reconciled against real DDL. {@link NoStaleAbsenceClaims} forbids
 * the assertion for any artefact the test can see on disk.
 *
 * <p><strong>Claims about a document that did not exist.</strong> {@code DECISION_LOG.md} and
 * {@code TRACEABILITY_MATRIX.md} were both scheduled artefacts absent from this branch, yet hundreds of sites
 * said a decision was "recorded in" or "tracked in" one of them. That sent a reader to nothing. The convention
 * was a forward reference - "tracked <em>for</em>", never "tracked <em>in</em>" - with the decision itself in
 * the docstring of the file it governs. {@link NoClaimsOnAbsentEvidence} forbade the present-tense form and
 * re-checked the premise, so that if a document were ever authored the gate itself would demand revision.
 *
 * <p><strong>That revision has since been demanded twice, and taken both times.</strong>
 * {@code DECISION_LOG.md} was authored first and its premise row inverted: the gate asserts that it
 * <em>exists</em>, which is what stops this guard passing by accident if the file is later deleted.
 * {@code TRACEABILITY_MATRIX.md} has now been authored too, so <strong>the absent set is empty</strong> and
 * no name is forbidden in the present tense any longer. Emptiness is asserted explicitly rather than left
 * implicit, and both names are asserted present, so the narrowing cannot be mistaken for a name having been
 * quietly dropped: deleting either register fails this gate immediately.
 *
 * <p><strong>The forward references have since been reworded, in one mechanical pass.</strong> An earlier
 * revision of this paragraph left roughly 198 of them in place on the grounds that they under-claim rather
 * than over-claim; that reasoning is withdrawn. Understating delivered evidence is the damaging direction for
 * an evidence artefact, because a reader concludes that authored work does not exist, and the qualifier
 * <em>planned</em> had become simply false. 103 sites across 42 files dropped the qualifier and 19 that
 * additionally asserted one or both registers absent were rewritten individually, each withdrawing its own
 * former claim in writing. It was one pass rather than a trickle precisely because
 * {@code CONTRIBUTING.md} asks a change to stay focused: a half-swept tree disagrees with itself, which is a
 * worse review burden than a single uniform correction. {@link NoStaleAbsenceClaims#neitherRegisterIsCalledPlannedOrAbsent()}
 * is what keeps the qualifier from returning.
 *
 * <p><strong>An unbounded register.</strong> "Retained for parity" was applied to five sites in one place
 * and three in another. The term means something precise - a reachable no-op or unused constant that exists
 * only so the paragraph map stays provable - and a preserved COBOL {@code CONTINUE} or a preserved asymmetry
 * is not one. {@link BoundedRetainedRegister} requires the register to be enumerated by locator and its size
 * to be derived from the marked sites rather than declared as a closed total.
 *
 * <p><strong>A citation that names no assertion.</strong> Every row of {@code TRACEABILITY_MATRIX.md} once
 * named only a test <em>file</em>. A file here holds hundreds of methods, so the row could not tell a reader
 * which assertion backed it, and it went on looking cited after the method that backed it was renamed away.
 * {@link TraceabilityTestCitations} resolves all 537 {@code file::method} pairs against the test sources and
 * additionally requires each named method to be an executable test rather than a fixture helper.
 *
 * <p>The three prose scans skip {@code docs/project-guide.md}, which is frozen prior-run evidence about a
 * previous attempt rather than a claim this branch makes. The citation gate reads only the matrix and the
 * test tree, so it has nothing to exclude.
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
            new String[] {"src/main/java/com/cardemo/observability/MetricsConfig.java", "MetricsConfig"},
            // Added 4 August 2026. Each of these five was delivered after the entries above were written, and
            // each had its absence asserted somewhere in the documentation at the moment it became untrue -
            // a package document calling a delivered controller planned, a configuration comment recording a
            // present class as Not available. Listing them here is what turns that reconciliation from a
            // one-off correction into a standing check, so the same drift cannot recur silently for them.
            //
            // DailyTransactionReader was delivered in the same batch and is deliberately NOT listed, which is
            // a limitation of this rule rather than an exemption granted to that class. The rule pairs a
            // spelling with an absence assertion in the same window, and that reader's own documentation
            // discusses, correctly and repeatedly, a configuration KEY being absent - "the default is what the
            // shipped configuration uses when the key is absent". Adding it reported that true sentence as a
            // defect, and the only ways to silence it would be to reword correct prose to suit a tool or to
            // loosen the assertion for everyone. Its delivery is instead held by the reconciled roster in
            // src/main/java/com/cardemo/batch/readers/package-info.java and by EvidenceHonestyTest's
            // test-class-denial rule, which forbids saying DailyTransactionReaderTest does not exist.
            new String[] {"src/main/java/com/cardemo/config/ObservabilityConfig.java", "ObservabilityConfig"},
            new String[] {"src/main/java/com/cardemo/controller/AuthController.java", "AuthController"},
            new String[] {"src/main/java/com/cardemo/controller/AdminController.java", "AdminController"},
            new String[] {
                "src/main/java/com/cardemo/batch/jobs/DailyTransactionPostingJob.java",
                "DailyTransactionPostingJob"},
            new String[] {
                "src/main/java/com/cardemo/batch/jobs/StatementGenerationJob.java",
                "StatementGenerationJob"},
            // Added 6 August 2026. Both were scheduled deliverables whose absence was asserted across the
            // documentation set - a scope table marking them Absent, a scope-boundary paragraph saying one
            // "does not exist yet", a build-verification note saying links to them have no target - and both
            // are now authored. Listing them here is what turns that reconciliation into a standing check:
            // if either were deleted tomorrow, every forward reference would silently become correct again
            // and nothing would notice.
            new String[] {"docs/onboarding-guide.md", "onboarding-guide.md"},
            new String[] {"docs/executive-presentation.html", "executive-presentation.html"});

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

    /**
     * The scheduled evidence documents that still do not exist in this branch.
     *
     * <p>Was two names, then one, and is now <strong>empty</strong>: {@code DECISION_LOG.md} and
     * {@code TRACEABILITY_MATRIX.md} have both been authored at the repository root and both moved to
     * {@link #AUTHORED_EVIDENCE_DOCUMENTS}, so a present-tense claim about either is now a true statement and
     * a guard that forbade it would be enforcing a premise it no longer has.
     *
     * <p>The list is kept rather than deleted, and its emptiness is asserted rather than assumed, because a
     * future scheduled register would belong here and the guard has to be re-armed deliberately. If a name is
     * ever added back it must also be added to {@link #PRESENT_TENSE_EVIDENCE_CLAIM}, or the pattern would
     * silently forbid nothing.
     */
    private static final List<String> ABSENT_EVIDENCE_DOCUMENTS = List.of();

    /**
     * Evidence documents that have been authored, asserted present so the guard cannot pass by accident.
     *
     * <p>Without this list the narrowing above would be indistinguishable from simply dropping a name: if
     * either register were deleted tomorrow, every forward reference in the tree would silently become
     * correct again and nothing would notice. Asserting existence is what makes the narrowing safe, and it is
     * why this list grows by exactly the name the list above loses.
     */
    private static final List<String> AUTHORED_EVIDENCE_DOCUMENTS =
            List.of("DECISION_LOG.md", "TRACEABILITY_MATRIX.md");

    /**
     * Verbs that, followed by "in &lt;absent document&gt;", claim the record already lives there.
     *
     * <p><strong>Derived from {@link #ABSENT_EVIDENCE_DOCUMENTS}, never hard-coded.</strong> That coupling is
     * the point: a name can no longer be dropped from the absent set while a stale alternation keeps forbidding
     * it, nor added while the alternation silently ignores it. Both registers now exist, so the set is empty
     * and the pattern below is deliberately one that cannot match - a present-tense claim about a document
     * that is on disk is a true statement, and a guard forbidding a true statement would be enforcing a
     * premise it no longer has.
     *
     * <p>Note that the sibling guard in {@code EvidenceHonestyTest} still scans for both names, including
     * inside string literals. That is why the phrasing here describes the forbidden form rather than quoting
     * it: quoting it would trip that guard. That guard forbids a form the tree does not use, so it continues
     * to pass, and leaving it untouched keeps this change focused on the failure it has to fix.
     */
    private static final Pattern PRESENT_TENSE_EVIDENCE_CLAIM = Pattern.compile(
            ABSENT_EVIDENCE_DOCUMENTS.isEmpty()
                    ? "(?!)"
                    : "(recorded|tracked|documented|justified|logged|cited|entered|noted|captured|carried)"
                            + " in (\\{@code )?("
                            + String.join("|", ABSENT_EVIDENCE_DOCUMENTS).replace(".", "\\.")
                            + ")",
            Pattern.CASE_INSENSITIVE);

    /** The register sites named in the root package documentation, as derived at this commit. */
    private static final List<String> REGISTER_SITES =
            List.of("RejectCode", "computeFees1400", "statement processor");

    /**
     * The frozen-corpus locator each register site must publish beside its name.
     *
     * <p>A name on its own is not auditable - a reader cannot check "the statement processor's redundant index
     * assignment" against anything. A locator is what lets each entry be verified against {@code app/}
     * individually, which is what replaces the withdrawn closed total as the thing this guard protects.
     */
    private static final List<String> REGISTER_SITE_LOCATORS = List.of(
            "app/cbl/CBTRN02C.cbl:L545-L560",
            "app/cbl/CBACT04C.cbl:L518-L520",
            "app/cbl/CBSTM03A.CBL:L316-L338");

    /**
     * The superseded closed-total sentence, which must survive only as a quotation.
     *
     * <p>This guard once <em>required</em> this exact wording. It is kept as a constant rather than inlined
     * because two assertions now use it in opposite directions - it must appear quoted, and it must not appear
     * unquoted - and a single spelling is what keeps those two from drifting apart.
     */
    private static final String WITHDRAWN_CLOSED_TOTAL =
            "Exactly three sites carry that retained-for-parity status";

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

        @Test
        @DisplayName("neither evidence register is qualified as planned, or asserted not to exist")
        void neitherRegisterIsCalledPlannedOrAbsent() {
            // The general rule above binds an absence verb to an artefact spelling. This one is narrower and
            // catches what that rule cannot see: the single word "planned" in front of a register name is not
            // an absence verb, yet it is exactly how 103 sites described a document that is on disk. Both
            // spellings are checked, and a quoted occurrence is exempt because a withdrawal has to be able to
            // reproduce what it withdraws - which is the same concession the register-of-record paragraph in
            // com.cardemo's package documentation relies on.
            final List<String> qualifiers = new ArrayList<>();
            for (final String register : AUTHORED_EVIDENCE_DOCUMENTS) {
                qualifiers.add("planned {@code " + register + "}");
                qualifiers.add("planned " + register);
                qualifiers.add("{@code " + register + "} does not exist");
                qualifiers.add(register + " does not exist");
            }
            final List<String> offences = new ArrayList<>();
            for (final Path path : scannedFiles()) {
                final String relative = ROOT.relativize(path).toString();
                final List<String> content = lines(relative);
                for (int index = 0; index < content.size(); index++) {
                    final String line = content.get(index);
                    for (final String qualifier : qualifiers) {
                        if (line.contains(qualifier) && !everyOccurrenceIsQuoted(line, qualifier)) {
                            offences.add(relative + ":" + (index + 1) + "  " + qualifier);
                        }
                    }
                }
            }
            assertThat(offences)
                    .as(
                            "both registers are authored at the repository root, so \"planned\" in front of "
                                    + "either name understates delivered evidence - the damaging direction, "
                                    + "because a reader concludes the record was never written. State the "
                                    + "obligation without the qualifier: an entry may still be owed in a "
                                    + "document that exists")
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
        @DisplayName("every document named absent really is absent, and the set is now empty")
        void thePremiseHolds() {
            for (final String document : ABSENT_EVIDENCE_DOCUMENTS) {
                assertThat(ROOT.resolve(document))
                        .as(
                                "%s is named as not authored in this branch, which is what would make a "
                                        + "present-tense claim about it a claim about nothing. If it is in "
                                        + "fact authored, move it to AUTHORED_EVIDENCE_DOCUMENTS - revised "
                                        + "deliberately, rather than passing by accident",
                                document)
                        .doesNotExist();
            }
            assertThat(ABSENT_EVIDENCE_DOCUMENTS)
                    .as(
                            "both scheduled registers are now authored, so the absent set is empty and the "
                                    + "claim pattern forbids nothing. Emptiness is asserted rather than "
                                    + "assumed: a future scheduled register must be added here AND to "
                                    + "PRESENT_TENSE_EVIDENCE_CLAIM, which is derived from this list so the "
                                    + "two cannot drift apart")
                    .isEmpty();
        }

        @Test
        @DisplayName("the authored evidence document is present, so the narrowing above stays honest")
        void theAuthoredRegisterIsPresent() {
            for (final String document : AUTHORED_EVIDENCE_DOCUMENTS) {
                assertThat(ROOT.resolve(document))
                        .as(
                                "%s was removed from the absent set because it exists. If it no longer does, "
                                        + "restore it to ABSENT_EVIDENCE_DOCUMENTS and to the claim pattern "
                                        + "rather than leaving a guard whose premise has silently reversed",
                                document)
                        .exists();
            }
        }

        @Test
        @DisplayName("no site says a decision is recorded, tracked or cited IN a document that does not exist")
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

            assertThat(AUTHORED_EVIDENCE_DOCUMENTS)
                    .as(
                            "with the absent set empty this scan can only pass vacuously, so the guard that "
                                    + "still carries weight is the one below it: both registers must be on "
                                    + "disk. Asserting that here keeps this test method meaningful instead of "
                                    + "silently becoming a no-op")
                    .isNotEmpty();
            for (final String document : AUTHORED_EVIDENCE_DOCUMENTS) {
                assertThat(ROOT.resolve(document))
                        .as(
                                "%s is why no present-tense claim about it is forbidden any more. If it is "
                                        + "deleted, restore it to ABSENT_EVIDENCE_DOCUMENTS rather than "
                                        + "leaving a guard whose premise has silently reversed",
                                document)
                        .exists();
            }
        }

        @Test
        @DisplayName("the root package documentation states once, for the whole tree, where the record lives")
        void theConventionIsStatedOnce() {
            final String rootDoc = String.join(" ", lines("src/main/java/com/cardemo/package-info.java"));
            // The assertion inverted with the premise. It used to require the root document to say the two
            // registers were "scheduled artefacts that do not" exist, which was the correct statement while
            // that was true and became a guard enforcing a falsehood the moment both were authored. It now
            // requires the opposite: that the root document states they ARE present, and still states where
            // the decision itself lives so the other files do not each have to qualify every mention.
            assertThat(rootDoc)
                    .as(
                            "one authoritative statement is what keeps the other 78 files from each having "
                                    + "to qualify every mention, and it has to be a TRUE statement: both "
                                    + "registers are on disk at the repository root")
                    .contains("authored and present at the repository root")
                    .contains("in the docstring of the file it");
            assertThat(rootDoc)
                    .as(
                            "the withdrawn prohibition must be withdrawn in writing, not silently deleted, "
                                    + "or a reader who remembers it cannot tell whether it still applies")
                    .contains("scheduled artefacts that do not exist in this branch");
            for (final String document : AUTHORED_EVIDENCE_DOCUMENTS) {
                assertThat(ROOT.resolve(document))
                        .as("%s is the premise of the statement above", document)
                        .exists();
            }
        }
    }

    /**
     * An added guard has to be labelled where a caller will read it, not only where an implementer will.
     *
     * <p><strong>Finding, severity Medium - remediated, and this is the gate that keeps it remediated.</strong>
     * The restriction of {@code USRTYPEI} to {@code 'A'} and {@code 'U'} is a guard the source does not have:
     * {@code COUSR01C}'s only test on the field is
     * {@code WHEN USRTYPEI OF COUSR1AI = SPACES OR LOW-VALUES} at {@code app/cbl/COUSR01C.cbl:142}, and every
     * other value is moved straight onto the record at {@code :158}. Section 0.8.3 of the migration plan
     * preserves an absent guard unless its removal is <em>explicitly labelled</em> as a deviation. It was
     * labelled in both services' Javadoc and nowhere a caller would look, so the published contract described
     * only the blank rejection and a reader met a message with no source locator and no explanation.
     *
     * <p>The two assertions below are deliberately different in kind. The first pins the label to the place
     * the guard is enforced, so the reasoning cannot drift away from the code. The second pins it to the place
     * the guard is <em>observed</em>, so it cannot be enforced without being published.
     */
    @Nested
    @DisplayName("the added user-type guard is labelled where it is enforced AND where it is observed")
    final class AddedGuardIsLabelled {

        /** The message the guard publishes, which has no locator anywhere in the frozen corpus. */
        private static final String GUARD_MESSAGE =
                "User Type must be A for an administrator or U for a regular user";

        /** The two services that enforce it, each mapping the byte onto the typed enumeration. */
        private static final List<String> ENFORCING_SITES = List.of(
                "src/main/java/com/cardemo/service/admin/UserAddService.java",
                "src/main/java/com/cardemo/service/admin/UserUpdateService.java");

        @Test
        @DisplayName("every site that enforces it labels it as a deviation and says where it is published")
        void everyEnforcingSiteLabelsIt() {
            for (final String site : ENFORCING_SITES) {
                final String content = String.join(" ", lines(site));
                assertThat(content)
                        .as("%s publishes the guard message, so it must also carry the label", site)
                        .contains(GUARD_MESSAGE)
                        .contains("Labelled deviation")
                        .contains("Where the deviation is published");
                assertThat(content)
                        .as("%s must cite the source locator that proves the guard is an addition, or the "
                                + "label is an assertion rather than evidence", site)
                        .contains("app/cpy/COCOM01Y.cpy");
            }
        }

        @Test
        @DisplayName("the published contract carries the label, not merely the message")
        void thePublishedContractCarriesTheLabel() {
            final String contract = String.join(" ", lines("docs/api-contracts.md"));

            assertThat(contract)
                    .as("a caller reads the contract. Publishing the message without the label is exactly "
                            + "the state this gate exists to prevent")
                    .contains(GUARD_MESSAGE);
            assertThat(contract)
                    .as("the label must be a heading a reader can be pointed at, so both operations can "
                            + "reference one place instead of restating it")
                    .contains("Labelled deviation: `userType` is restricted to `A` and `U`");
            assertThat(contract)
                    .as("the label must show the guard is an ADDITION by citing the source test it is "
                            + "absent from, and must state the blank check still runs first")
                    .contains("app/cbl/COUSR01C.cbl:L142")
                    .contains("ck_user_security_type");
        }
    }

    /**
     * F19 - the retained-parity register is enumerated by locator and its census is derived, not declared.
     *
     * <p><strong>This guard used to enforce the defect it now forbids.</strong> It required the root package
     * document to contain the sentence "Exactly three sites carry that retained-for-parity status" - a
     * permanently closed global total that nothing derived and that a fourth genuine site would have falsified
     * silently, because the only thing holding the number was a sentence. What actually needs guarding is
     * different and stronger: that every register entry is <em>identified by a stable locator</em> so it can be
     * audited individually, and that the count is presented as a <em>measurement of this commit</em> whose
     * authority is the mechanism that derives it - {@code dispositions.justifiedNoOps}, which
     * {@code com.cardemo.e2e.GateVerificationTest} writes from the marked sites themselves.
     */
    @Nested
    @DisplayName("the retained-parity register is enumerated by locator, with a derived rather than fixed census")
    final class BoundedRetainedRegister {

        @Test
        @DisplayName("the root package documentation enumerates every register site with its locator")
        void theRegisterIsEnumerated() {
            final String rootDoc = String.join(" ", lines("src/main/java/com/cardemo/package-info.java"));
            for (final String site : REGISTER_SITES) {
                assertThat(rootDoc)
                        .as("register site %s must be named in the enumeration", site)
                        .contains(site);
            }
            for (final String locator : REGISTER_SITE_LOCATORS) {
                assertThat(rootDoc)
                        .as(
                                "register site locator %s must be published, so the entry can be checked "
                                        + "against the frozen corpus one site at a time rather than taken on "
                                        + "the strength of a total",
                                locator)
                        .contains(locator);
            }
        }

        @Test
        @DisplayName("the census is presented as derived at this commit, never as a permanently closed total")
        void theCensusIsDerivedRatherThanDeclared() {
            final String rootDoc = String.join(" ", lines("src/main/java/com/cardemo/package-info.java"));
            assertThat(rootDoc)
                    .as(
                            "a closed total is the defect: it cannot be re-derived, so a fourth site would "
                                    + "make it false with nothing noticing. Say the number is measured at "
                                    + "this commit and name what measures it")
                    .contains("as at this commit")
                    .contains("is a measurement, not a closed total")
                    .contains("dispositions.justifiedNoOps");
            // The withdrawn sentence has to survive as a QUOTATION and must not survive as an assertion. A
            // plain doesNotContain cannot tell those apart and fired on the withdrawal itself, which would
            // have forced the correction to delete the wording it withdraws - the failure mode
            // theConventionIsStatedOnce exists to prevent one paragraph further up. So the two halves are
            // asserted separately, reusing the same quoted-occurrence rule the rest of this class uses.
            final List<String> unquoted = new ArrayList<>();
            boolean withdrawnInWriting = false;
            final List<String> rootLines = lines("src/main/java/com/cardemo/package-info.java");
            for (int index = 0; index < rootLines.size(); index++) {
                final String line = rootLines.get(index);
                if (!line.contains(WITHDRAWN_CLOSED_TOTAL)) {
                    continue;
                }
                if (everyOccurrenceIsQuoted(line, WITHDRAWN_CLOSED_TOTAL)) {
                    withdrawnInWriting = true;
                } else {
                    unquoted.add("src/main/java/com/cardemo/package-info.java:" + (index + 1));
                }
            }
            assertThat(unquoted)
                    .as(
                            "an unquoted occurrence of \"%s\" is the closed total being asserted again",
                            WITHDRAWN_CLOSED_TOTAL)
                    .isEmpty();
            assertThat(withdrawnInWriting)
                    .as(
                            "the superseded wording must remain present as a quotation so a reader who "
                                    + "remembers it can see it was withdrawn rather than wonder whether it "
                                    + "still applies")
                    .isTrue();
        }

        @Test
        @DisplayName("no file asserts a closed global total for the register")
        void noFileAssertsAClosedTotal() {
            // Widened from "a number other than three" to "any closed total, including three". The earlier
            // pattern let the defect through by construction: it forbade every count except the one being
            // wrongly asserted.
            final Pattern closedTotal = Pattern.compile(
                    "(tree has (two|three|four|five|six|seven) of them|"
                            + "exactly (two|three|four|five|six|seven) (sites? carry|retained[- ]parity)|"
                            + "(two|three|four|five|six|seven) retained parity artefacts)",
                    Pattern.CASE_INSENSITIVE);
            final List<String> offences = new ArrayList<>();
            for (final Path path : scannedFiles()) {
                final String relative = ROOT.relativize(path).toString();
                final List<String> content = lines(relative);
                for (int index = 0; index < content.size(); index++) {
                    final Matcher matcher = closedTotal.matcher(content.get(index));
                    if (matcher.find() && !everyOccurrenceIsQuoted(content.get(index), matcher.group())) {
                        offences.add(relative + ":" + (index + 1) + "  " + matcher.group());
                    }
                }
            }
            assertThat(offences)
                    .as(
                            "the register's size is derived from the marked sites, so no file may declare it "
                                    + "closed at any number. A preserved COBOL CONTINUE, a preserved "
                                    + "asymmetry and a preserved absent guard are documented source "
                                    + "behaviour at their own locators, not register entries")
                    .isEmpty();
        }
    }

    /**
     * Documentation publication, which is the one failure in this repository that reports nothing at all.
     *
     * <p><strong>Finding M-04, severity Medium, and finding H-05, severity High.</strong> {@code catalog-info.yaml}
     * sets {@code backstage.io/techdocs-ref: dir:.}, so Backstage TechDocs renders straight from the
     * {@code nav} block of {@code mkdocs.yml}. A page omitted from that block <em>never appears in the published
     * site</em>, and nothing reports a problem: the build succeeds, the file sits in the repository, and the
     * reader who needs it cannot reach it.
     *
     * <p>The obvious safety net does not work. It is natural to expect {@code mkdocs build --strict} to catch an
     * omission, because {@code --strict} promotes warnings to errors - but MkDocs defaults
     * {@code validation.nav.omitted_files} to {@code info}, and {@code --strict} acts only on warnings, so under
     * a configuration that declares no {@code validation} block an omitted page passes a strict build
     * <em>silently</em>. The failure is not merely error-free, it is warning-free by default. That is why the
     * fourth test below asserts the setting itself rather than trusting the build to notice.
     *
     * <p>These are structural assertions over the file rather than an invocation of MkDocs, deliberately: MkDocs
     * is a Python tool that is not a build dependency of this project and is not guaranteed to be on the path of
     * a machine running the test tier. Asserting the configuration proves the same invariant without adding a
     * dependency, and it gives a contributor the answer locally rather than after a push.
     */
    @Nested
    @DisplayName("documentation publication - every page under docs/ reaches the published site")
    class DocumentationPublication {

        /**
         * The two documents the Agent Action Plan mandates and the review recorded as absent.
         *
         * <p>Named explicitly, rather than left to the general scan below, because the general scan only proves
         * that whatever exists is published. It cannot notice a document that was never written, and being
         * absent was exactly this pair's defect.
         */
        private static final List<String> MANDATED_DOCUMENTS =
                List.of("docs/onboarding-guide.md", "docs/executive-presentation.html");

        /**
         * The five {@code nav} entries the review found missing, as literal {@code title: file} pairs.
         *
         * <p>The titles are not free choices: the repository fixed them before the documents existed, in
         * {@code docs/technical-specifications.md} and in {@code docs/validation-gates.md}. Asserting the exact
         * pair therefore catches a renamed title as well as a dropped entry, and a title drifting from the one
         * the sibling documents cite is itself a defect.
         */
        private static final List<String> REQUIRED_NAV_ENTRIES = List.of(
                "API Contracts: api-contracts.md",
                "Architecture Before and After: architecture-before-after.md",
                "Onboarding Guide: onboarding-guide.md",
                "Validation Gates: validation-gates.md",
                "Executive Presentation: executive-presentation.html");

        @Test
        @DisplayName("both mandated documents exist and carry content")
        void bothMandatedDocumentsExistAndCarryContent() {
            final List<String> offences = new ArrayList<>();
            for (final String relative : MANDATED_DOCUMENTS) {
                final Path path = ROOT.resolve(relative);
                if (!Files.isRegularFile(path)) {
                    offences.add(relative + "  absent");
                    continue;
                }
                final long size = sizeOf(path);
                if (size == 0L) {
                    offences.add(relative + "  present but empty");
                }
            }
            assertThat(offences)
                    .as(
                            "both are CREATE deliverables of the Agent Action Plan and both were recorded "
                                    + "absent by review finding H-04. An empty file is counted as absent "
                                    + "because a nav entry pointing at one publishes a blank page, which is "
                                    + "the same failure with extra steps")
                    .isEmpty();
        }

        @Test
        @DisplayName("every one of the five entries the review found missing is declared, title and file")
        void everyRequiredNavEntryIsDeclared() {
            final String nav = navBlock();
            final List<String> missing = new ArrayList<>();
            for (final String entry : REQUIRED_NAV_ENTRIES) {
                if (!nav.contains(entry)) {
                    missing.add(entry);
                }
            }
            assertThat(missing)
                    .as(
                            "review finding H-05: the nav carried only Home, Project Guide and Technical "
                                    + "Specifications, so these five documents were unpublished. The titles "
                                    + "are the ones docs/technical-specifications.md and "
                                    + "docs/validation-gates.md already cite, so a renamed title is a "
                                    + "defect too")
                    .isEmpty();
        }

        @Test
        @DisplayName("no publishable document under docs/ is missing from the nav, whatever the doc set becomes")
        void noPublishableDocumentIsMissingFromTheNav() {
            final String nav = navBlock();
            final List<String> unpublished = new ArrayList<>();
            try (Stream<Path> tree = Files.list(ROOT.resolve("docs"))) {
                tree.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(DocumentationPublication::isPublishable)
                        .sorted()
                        .forEach(name -> {
                            if (!nav.contains(": " + name)) {
                                unpublished.add(name);
                            }
                        });
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot list docs/", cause);
            }
            assertThat(unpublished)
                    .as(
                            "this is the invariant rather than a fixed list, so it keeps holding as documents "
                                    + "are added: anything publishable under docs/ must be reachable from the "
                                    + "nav, because TechDocs renders from the nav and an omission produces no "
                                    + "error and no output")
                    .isEmpty();
        }

        @Test
        @DisplayName("an omitted page is configured to FAIL a strict build rather than pass it silently")
        void anOmittedPageFailsAStrictBuild() {
            final List<String> configuration = lines("mkdocs.yml");
            final String declared = String.join("\n", configuration);

            // Matched as a top-level key rather than as a substring. MkDocs reads the
            // policy only under a key spelled exactly "validation:" at column zero, so
            // "disabled_validation:" - the obvious way to switch the block off - leaves
            // the policy inert while still containing the substring. A contains() check
            // therefore cannot fail for that mutation, and an assertion that cannot fail
            // is not an assertion.
            assertThat(configuration)
                    .as(
                            "MkDocs defaults validation.nav.omitted_files to info and --strict acts only on "
                                    + "warnings, so without a top-level validation: block an omitted page "
                                    + "passes a strict build silently - warning-free, not merely error-free. "
                                    + "Renaming the key away disables it just as effectively as deleting it")
                    .anySatisfy(line -> assertThat(line).isEqualTo("validation:"));

            assertThat(declared)
                    .as(
                            "the policy that turns a silent omission into a build failure, and the companion "
                                    + "checks closing the same gap from the other side: a nav entry or an "
                                    + "in-page link naming a file or anchor that does not exist")
                    .contains("omitted_files: warn")
                    .contains("not_found: warn");
        }

        /**
         * Reads the {@code nav} block, ending at the first subsequent top-level key.
         *
         * <p>Scoped to the block rather than the whole file so that a document merely <em>mentioned</em> in one
         * of the file's explanatory comments cannot satisfy an assertion about being published.
         *
         * @return the {@code nav} block's text, never {@code null} and never blank
         */
        private String navBlock() {
            final List<String> configuration = lines("mkdocs.yml");
            final List<String> block = new ArrayList<>();
            boolean inNav = false;
            for (final String line : configuration) {
                if ("nav:".equals(line.strip())) {
                    inNav = true;
                    continue;
                }
                if (inNav) {
                    final boolean topLevelKey =
                            !line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#");
                    if (topLevelKey) {
                        break;
                    }
                    block.add(line);
                }
            }
            assertThat(block)
                    .as("mkdocs.yml must declare a nav block; TechDocs renders the site from it")
                    .isNotEmpty();
            return String.join("\n", block);
        }

        /**
         * Decides whether a file under {@code docs/} is something the site publishes as a page.
         *
         * @param fileName the bare file name, never {@code null}
         * @return {@code true} when the file must carry a nav entry
         */
        private static boolean isPublishable(final String fileName) {
            final String lower = fileName.toLowerCase(Locale.ROOT);
            return lower.endsWith(".md") || lower.endsWith(".html");
        }

        /**
         * Reports a file's size, converting the checked failure the same way {@code lines} does.
         *
         * @param path the file to size, never {@code null}
         * @return the size in bytes
         */
        private static long sizeOf(final Path path) {
            try {
                return Files.size(path);
            } catch (final IOException cause) {
                throw new UncheckedIOException("Cannot size " + path, cause);
            }
        }
    }

    @Nested
    @DisplayName("every operational property a bean reads is declared in a profile")
    class PropertyDeclarationCoverage {

        /**
         * The five per-job commit intervals, which are deliberately named in {@code application.yml} rather
         * than given values.
         *
         * <p>Each is read through a TWO-LEVEL fallback that ends at {@code carddemo.batch.chunk-size}.
         * Declaring one would resolve the first level and permanently detach the global from that job, so a
         * value would change behaviour - which is precisely what declaring the others was careful not to do.
         * The profile therefore names them in prose, and this group asserts that naming rather than a value.
         */
        private static final List<String> NAMED_NOT_VALUED = List.of(
                "carddemo.batch.posttran.chunk-size",
                "carddemo.batch.intcalc.chunk-size",
                "carddemo.batch.creastmt.chunk-size",
                "carddemo.batch.tranrept.chunk-size",
                "carddemo.batch.combtran.chunk-size");

        @Test
        @DisplayName("no bean reads a carddemo.* key that appears in no profile - finding F-017")
        void everyKeyReadIsDeclaredOrDeliberatelyNamed() {
            final List<String> declared = declaredKeys();
            final String profileProse = profileText();
            final List<String> undeclared = new ArrayList<>();

            for (final String key : keysReadByBeans()) {
                if (declared.contains(key)) {
                    continue;
                }
                // A named-not-valued key is discoverable if the profile mentions it in prose.
                if (NAMED_NOT_VALUED.contains(key) && profileProse.contains(key)) {
                    continue;
                }
                undeclared.add(key);
            }

            assertThat(undeclared)
                    .as("every carddemo.* property a bean binds must be declared with its intended value, or "
                            + "- for the five fallback-chained commit intervals only - named in the profile's "
                            + "own prose. A key read at runtime that appears nowhere in a profile is "
                            + "invisible to whoever operates the job, which is finding F-017")
                    .isEmpty();
        }

        @Test
        @DisplayName("the scan finds real keys, so the assertion above cannot pass vacuously")
        void theScanIsNotVacuous() {
            assertThat(keysReadByBeans())
                    .as("the @Value scan must find the tree's property keys; an empty result would make the "
                            + "coverage assertion meaningless")
                    .hasSizeGreaterThan(20)
                    .contains("carddemo.batch.jobs.tranrept.name", "carddemo.aws.s3.work-prefixes.trxfl");
            assertThat(declaredKeys())
                    .as("the profile scan must find the declared keys")
                    .hasSizeGreaterThan(100);
        }

        @Test
        @DisplayName("all six job names share one spelling, so the key an operator edits is the key read")
        void allJobNamesShareOneSpelling() {
            final List<String> jobNameKeys = keysReadByBeans().stream()
                    .filter(key -> key.endsWith(".name") && key.startsWith("carddemo.batch."))
                    .sorted()
                    .toList();

            assertThat(jobNameKeys)
                    .as("a second spelling of one decision leaves the declared key inert: editing it changes "
                            + "nothing and nothing reports that it did not")
                    .containsExactly(
                            "carddemo.batch.jobs.combtran.name",
                            "carddemo.batch.jobs.creastmt.name",
                            "carddemo.batch.jobs.intcalc.name",
                            "carddemo.batch.jobs.pipeline.name",
                            "carddemo.batch.jobs.posttran.name",
                            "carddemo.batch.jobs.tranrept.name");
        }

        /** Concatenates every profile so a prose mention can be located. */
        private static String profileText() {
            final StringBuilder text = new StringBuilder();
            for (final Path profile : profiles()) {
                text.append(readFile(profile)).append('\n');
            }
            return text.toString();
        }

        /** The four profile documents, in a stable order. */
        private static List<Path> profiles() {
            final Path resources = ROOT.resolve("src/main/resources");
            return List.of(
                    resources.resolve("application.yml"),
                    resources.resolve("application-local.yml"),
                    resources.resolve("application-test.yml"),
                    resources.resolve("application-prod.yml"));
        }

        /**
         * Flattens every profile into fully qualified key paths.
         *
         * @return the declared keys, deduplicated
         */
        private static List<String> declaredKeys() {
            final List<String> keys = new ArrayList<>();
            final Pattern entry = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+):(.*)$");
            for (final Path profile : profiles()) {
                final List<int[]> stack = new ArrayList<>();
                final List<String> names = new ArrayList<>();
                for (final String raw : readFile(profile).split("\n", -1)) {
                    final String line = raw.stripTrailing();
                    if (line.isBlank() || line.strip().startsWith("#")) {
                        continue;
                    }
                    final Matcher matcher = entry.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }
                    final int indent = matcher.group(1).length();
                    while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) {
                        stack.remove(stack.size() - 1);
                        names.remove(names.size() - 1);
                    }
                    stack.add(new int[] {indent});
                    names.add(matcher.group(2));
                    final String path = String.join(".", names);
                    if (!keys.contains(path)) {
                        keys.add(path);
                    }
                }
            }
            return keys;
        }

        /**
         * Extracts every {@code carddemo.*} key bound by a {@code @Value} annotation in main sources.
         *
         * <p>Continuation lines are joined and Java string concatenation is collapsed first, because several
         * annotations spread one placeholder expression over two lines and embed a constant in the default.
         * A scan that read single lines would silently miss those - which is how the gap this group closes
         * went unnoticed.
         *
         * @return the keys read, deduplicated
         */
        private static List<String> keysReadByBeans() {
            final List<String> keys = new ArrayList<>();
            final Pattern placeholder = Pattern.compile("\\$\\{\\s*(carddemo\\.[A-Za-z0-9_.-]+)");
            for (final Path source : mainJavaSources()) {
                final String[] lines = readFile(source).split("\n", -1);
                for (int index = 0; index < lines.length; index++) {
                    if (!lines[index].contains("@Value")) {
                        continue;
                    }
                    final StringBuilder joined = new StringBuilder();
                    int depth = 0;
                    for (int scan = index; scan < Math.min(index + 8, lines.length); scan++) {
                        joined.append(lines[scan]);
                        depth += count(lines[scan], '(') - count(lines[scan], ')');
                        if (depth <= 0) {
                            break;
                        }
                    }
                    final String flattened = joined.toString().replaceAll("\"\\s*\\+\\s*[A-Za-z0-9_.]+", "X");
                    final Matcher matcher = placeholder.matcher(flattened);
                    while (matcher.find()) {
                        if (!keys.contains(matcher.group(1))) {
                            keys.add(matcher.group(1));
                        }
                    }
                }
            }
            return keys;
        }

        /** Counts one character, avoiding a regex for a single-character tally. */
        private static int count(final String text, final char target) {
            int total = 0;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == target) {
                    total++;
                }
            }
            return total;
        }

        /** Every Java file under the main source tree. */
        private static List<Path> mainJavaSources() {
            try (Stream<Path> walk = Files.walk(ROOT.resolve("src/main/java"))) {
                return walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("Could not walk the main source tree.", unreadable);
            }
        }

        /** Reads one file as UTF-8. */
        private static String readFile(final Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("Could not read " + path + " for the property scan.",
                        unreadable);
            }
        }
    }


    /**
     * Holds the published route tables to the controllers, because prose about a URL cannot be trusted.
     *
     * <p><strong>The drift this closes, and why a one-off correction was not enough.</strong>
     * {@code TRACEABILITY_MATRIX.md} published four routes no controller serves - the account update as
     * {@code PUT} on an account-identifier path, card detail and card update on a card-number path, and
     * transaction detail on a transaction-identifier path. All four were the same mistake: assuming that a
     * screen taking an identifier becomes a URL carrying that identifier. In each case the implementation
     * deliberately does something else, because the selector is either already in the body or is one of two
     * mutually exclusive alternatives that a single path variable cannot express.
     *
     * <p>Correcting the four strings fixes today. This group fixes the class: the census below is derived
     * from the controller annotations, so any route named in the evidence that the controllers do not serve
     * fails here rather than being published.
     *
     * <p><strong>Why it reads the table rows rather than the whole document.</strong> The correction in
     * {@code TRACEABILITY_MATRIX.md} names the four withdrawn forms in prose, in order to explain them. A
     * scan of the whole file would report those explanations as defects and would leave only two ways out -
     * deleting the explanation, or loosening the rule for everyone. Binding to the transaction table's own
     * rows, which begin with a four-character CICS transaction identifier, is what lets the document explain
     * a withdrawn form without asserting it.
     */
    @Nested
    @DisplayName("every route the evidence publishes is a route a controller actually serves")
    class PublishedRoutesMatchTheControllers {

        /** A transaction-table row: a four-character CICS identifier in the first cell. */
        private static final Pattern TRANSACTION_ROW =
                Pattern.compile("^\\|\\s*`(C[A-Z0-9]{3})`\\s*\\|");

        /** A method or path constant on a controller, for example {@code static final String BASE_PATH = "/x";}. */
        private static final Pattern PATH_CONSTANT =
                Pattern.compile("String\\s+([A-Z_]*PATH)\\s*=\\s*\"([^\"]*)\"");

        /** A method-level mapping, with the constant it uses if it names one. */
        private static final Pattern METHOD_MAPPING =
                Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping\\b\\s*(?:\\(\\s*([A-Za-z_]*PATH)?[^)]*\\))?");

        /** A route as the evidence spells it: {@code VERB /api/...}. */
        private static final Pattern PUBLISHED_ROUTE =
                Pattern.compile("(GET|PUT|POST|DELETE|PATCH) (/api/[A-Za-z0-9/{}_-]*)");

        @Test
        @DisplayName("the traceability matrix's transaction table names only real routes - finding F-019")
        void matrixTransactionTableNamesOnlyRealRoutes() {
            final List<String> served = servedRoutes();
            final List<String> published = new ArrayList<>();
            for (final String line : readFile(ROOT.resolve("TRACEABILITY_MATRIX.md")).split("\n", -1)) {
                if (!TRANSACTION_ROW.matcher(line).find()) {
                    continue;
                }
                final Matcher route = PUBLISHED_ROUTE.matcher(line);
                while (route.find()) {
                    published.add(route.group(1) + " " + route.group(2));
                }
            }

            assertThat(published)
                    .as("the transaction table must name one route per row; finding nothing would make the "
                            + "assertion below vacuous")
                    .hasSize(17);
            assertThat(published)
                    .as("every route the traceability matrix publishes must be one the controllers serve. "
                            + "Four path-variable forms were published that do not exist, which is finding "
                            + "F-019; the controllers settle this, not the prose. Served: %s", served)
                    .allSatisfy(route -> assertThat(served).contains(route));
        }

        @Test
        @DisplayName("the API contract covers all 17 served routes")
        void apiContractCoversEveryServedRoute() {
            assertThat(publishedIn("docs/api-contracts.md"))
                    .as("the contract of record must name every operation the controllers serve; a served "
                            + "route absent from it is an undocumented endpoint")
                    .containsAll(servedRoutes());
        }

        /**
         * Holds the contract document's <em>templated</em> routes to the controllers.
         *
         * <p><strong>Why templated forms only, which is a narrowing and not a loophole.</strong> A contract
         * document legitimately contains request lines that are not route declarations: a worked example with
         * a real identifier substituted, and deliberate negative examples - an unknown path, a path with a
         * trailing segment, an identifier of the wrong length - each shown in order to state the status code
         * it produces. Asserting that every {@code VERB /api/...} string in the document is a served route
         * reports all of those as defects, and the only ways to satisfy it would be to delete correct
         * documentation or to weaken the rule for everyone.
         *
         * <p>A route carrying a {@code {placeholder}} segment, by contrast, is never an example - it is a
         * declaration of shape. That is also exactly the form all four of finding F-019's errors took, so
         * binding to it catches the class of mistake without touching the examples.
         */
        @Test
        @DisplayName("every templated route the API contract declares is a real one - finding F-019")
        void apiContractDeclaresOnlyRealTemplatedRoutes() {
            final List<String> served = servedRoutes();
            final List<String> templated = publishedIn("docs/api-contracts.md").stream()
                    .filter(route -> route.contains("{"))
                    .toList();

            assertThat(templated)
                    .as("the contract declares path-variable routes; finding none would make the assertion "
                            + "below vacuous")
                    .isNotEmpty();
            assertThat(templated)
                    .as("a templated route is a declaration of shape, never an example, so every one must be "
                            + "served. Served: %s", served)
                    .allSatisfy(route -> assertThat(served).contains(route));
        }

        /**
         * Every distinct {@code VERB /api/...} string in one document, in first-appearance order.
         *
         * @param relativePath the document, relative to the repository root
         * @return the published route strings, deduplicated
         */
        private static List<String> publishedIn(final String relativePath) {
            final Matcher route = PUBLISHED_ROUTE.matcher(readFile(ROOT.resolve(relativePath)));
            final List<String> published = new ArrayList<>();
            while (route.find()) {
                final String spelled = route.group(1) + " " + route.group(2);
                if (!published.contains(spelled)) {
                    published.add(spelled);
                }
            }
            return published;
        }

        @Test
        @DisplayName("the census is derived from the controllers and finds exactly 17 operations")
        void theCensusIsDerivedAndComplete() {
            assertThat(servedRoutes())
                    .as("the 17 sourced CICS screen programs become 17 operations across 8 controllers; a "
                            + "census that found another number would mean this gate is measuring the wrong "
                            + "thing")
                    .hasSize(17)
                    .contains("PUT /api/accounts", "GET /api/cards/detail", "PUT /api/cards",
                            "GET /api/transactions/detail")
                    .doesNotContain("PUT /api/accounts/{accountId}", "GET /api/cards/{cardNumber}",
                            "PUT /api/cards/{cardNumber}", "GET /api/transactions/{transactionId}");
        }

        /**
         * Derives every served route from the controller annotations.
         *
         * @return one {@code VERB /path} entry per method-level mapping, in file order
         */
        private static List<String> servedRoutes() {
            final List<String> routes = new ArrayList<>();
            for (final Path controller : controllerSources()) {
                final String code = withoutComments(readFile(controller));
                final java.util.Map<String, String> constants = new java.util.LinkedHashMap<>();
                final Matcher constant = PATH_CONSTANT.matcher(code);
                while (constant.find()) {
                    constants.put(constant.group(1), constant.group(2));
                }
                final String base = constants.getOrDefault("BASE_PATH", "");
                final Matcher mapping = METHOD_MAPPING.matcher(code);
                while (mapping.find()) {
                    final String named = mapping.group(2);
                    final String suffix = named == null ? "" : constants.getOrDefault(named, "");
                    routes.add(verbOf(mapping.group(1)) + " " + base + suffix);
                }
            }
            return routes;
        }

        /** Maps a mapping-annotation prefix to its HTTP method. */
        private static String verbOf(final String annotationPrefix) {
            return annotationPrefix.toUpperCase(Locale.ROOT);
        }

        /** Strips block and line comments, so a javadoc example cannot be mistaken for a mapping. */
        private static String withoutComments(final String source) {
            final String withoutBlocks = source.replaceAll("(?s)/\\*.*?\\*/", "");
            return withoutBlocks.replaceAll("(?m)^\\s*//.*$", "");
        }

        /** The eight controller sources, excluding the package document. */
        private static List<Path> controllerSources() {
            try (Stream<Path> walk = Files.list(ROOT.resolve("src/main/java/com/cardemo/controller"))) {
                return walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                        .sorted()
                        .toList();
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("Could not list the controller package.", unreadable);
            }
        }

        /** Reads one file as UTF-8. */
        private static String readFile(final Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("Could not read " + path + " for the route census.",
                        unreadable);
            }
        }
    }

    /**
     * Holds the feature census contiguous, so a catalogued feature cannot go undefined.
     *
     * <p><strong>The drift this closes.</strong> The parity contract is 22 features, {@code F-001} through
     * {@code F-022}. The program table in {@code TRACEABILITY_MATRIX.md} named 21 of them and skipped
     * {@code F-021}, and <em>no artefact anywhere defined what F-021 was</em>. The skip itself is legitimate -
     * that table is keyed on COBOL program and F-021, transaction combine, has none, because
     * {@code app/jcl/COMBTRAN.jcl} is a sort step and a copy step with no {@code EXEC PGM=} naming a COBOL
     * module. What was not legitimate was leaving the number undefined and the skip unexplained, which is
     * finding F-022.
     *
     * <p>This group asserts the reconciliation rather than the absence: every identifier in the contiguous
     * range is accounted for, either by a program row or by an explicit definition that says why it has none.
     */
    @Nested
    @DisplayName("every catalogued feature F-001..F-022 is accounted for")
    class FeatureCensusIsContiguous {

        /** The contract's range: 22 identifiers, contiguous, no gaps. */
        private static final int CATALOGUED_FEATURES = 22;

        /** The one feature with no COBOL program, and the job control that is its source of truth. */
        private static final String PROGRAMLESS_FEATURE = "F-021";

        @Test
        @DisplayName("no identifier in F-001..F-022 is undefined anywhere - finding F-022")
        void everyFeatureIdentifierIsDefined() {
            final String matrix = readMatrix();
            final List<String> undefined = new ArrayList<>();
            for (int number = 1; number <= CATALOGUED_FEATURES; number++) {
                final String identifier = String.format(Locale.ROOT, "F-%03d", number);
                if (!matrix.contains(identifier)) {
                    undefined.add(identifier);
                }
            }

            assertThat(undefined)
                    .as("the parity contract names %d features and every one must be defined in the "
                            + "traceability matrix - either on a program row, or, for the one feature with no "
                            + "program, by the census that says why. An identifier the contract names and no "
                            + "artefact defines is finding F-022", Integer.valueOf(CATALOGUED_FEATURES))
                    .isEmpty();
        }

        @Test
        @DisplayName("the programless feature is defined by its job control, and says so")
        void theProgramlessFeatureIsDefinedByItsJobControl() {
            final String matrix = readMatrix();

            assertThat(matrix)
                    .as("%s is the one catalogued feature with no COBOL program, so the matrix must define it "
                            + "against the job control that IS its source of truth, and must say why it "
                            + "carries no program row", PROGRAMLESS_FEATURE)
                    .contains(PROGRAMLESS_FEATURE + " Transaction combine")
                    .contains("app/jcl/COMBTRAN.jcl");
            assertThat(ROOT.resolve("app/jcl/COMBTRAN.jcl"))
                    .as("the job control the census cites must exist in the frozen corpus")
                    .isRegularFile();
        }

        @Test
        @DisplayName("the program table still covers 28 programs and 21 of the 22 features")
        void theProgramTableCensusHoldsItsShape() {
            final String matrix = readMatrix();
            final int start = matrix.indexOf("| # | Program | Lines | Mode | Rows | Feature |");
            final int end = matrix.indexOf("| | **Total** | **19,254** | | **537** | |");
            assertThat(start).as("the program table header must be findable").isNotNegative();
            assertThat(end).as("the program table total row must be findable").isGreaterThan(start);

            final Matcher feature = Pattern.compile("\\|\\s*(F-0\\d\\d)\\b")
                    .matcher(matrix.substring(start, end));
            final List<String> rows = new ArrayList<>();
            while (feature.find()) {
                rows.add(feature.group(1));
            }

            assertThat(rows)
                    .as("one feature per program row, for all 28 programs")
                    .hasSize(28);
            assertThat(rows.stream().distinct().toList())
                    .as("the table names every feature except the programless one; if that changes, the "
                            + "census prose above it is wrong and must move with it")
                    .hasSize(CATALOGUED_FEATURES - 1)
                    .doesNotContain(PROGRAMLESS_FEATURE);
        }

        /** The traceability matrix, read once per test. */
        private static String readMatrix() {
            try {
                return Files.readString(ROOT.resolve("TRACEABILITY_MATRIX.md"), StandardCharsets.UTF_8);
            } catch (final IOException unreadable) {
                throw new UncheckedIOException("Could not read the traceability matrix.", unreadable);
            }
        }
    }


    // =================================================================================================
    // The traceability matrix's Test column.
    //
    // A row that names only a test FILE is not evidence. The file holds hundreds of methods, so a
    // reader cannot tell which of them the row rests on, and - worse - the row keeps looking cited
    // long after the method that backed it was renamed or deleted. Naming the method closes both,
    // but only if something checks the name, which is what this class is: it resolves all 537 pairs
    // against the test sources on every build.
    // =================================================================================================

    /** The paragraph rows of the matrix carry fourteen columns; the Test cell is the twelfth. */
    private static final Pattern MATRIX_ROW = Pattern.compile("^\\| `TM-.*\\|$");

    /** Column index of the Test cell within a parsed paragraph row. */
    private static final int TEST_COLUMN = 11;

    /** Number of columns a paragraph row carries; anything else is a findings row, not a mapping. */
    private static final int PARAGRAPH_ROW_COLUMNS = 14;

    /** The published paragraph-row count: 528 derived labels plus the 9 synthetic rows. */
    private static final int PUBLISHED_PARAGRAPH_ROWS = 537;

    /** Test sources are cited relative to this directory. */
    private static final String TEST_BASE = "src/test/java/com/cardemo/";

    /** Only an executable test may be cited - never a fixture helper, a setUp or a given method. */
    private static final Pattern EXECUTABLE_TEST = Pattern.compile(
            "@(?:Test|ParameterizedTest|RepeatedTest)\\b[\\s\\S]*?\\bvoid\\s+(\\w+)\\s*\\(");

    /** A parsed citation: the test file relative to {@link #TEST_BASE}, and the method inside it. */
    private record Citation(String rowId, String file, String method) {
    }

    /** F20 - every matrix row names one executable test method, and it resolves. */
    @Nested
    @DisplayName("every traceability row cites a test METHOD that exists and can actually run")
    final class TraceabilityTestCitations {

        @Test
        @DisplayName("all 537 paragraph rows carry a file::method citation, none file-only")
        void everyRowNamesAMethod() {
            final List<Citation> citations = citations();
            assertThat(citations)
                    .as("537 rows are published: 528 derived paragraph labels plus 9 synthetic rows")
                    .hasSize(PUBLISHED_PARAGRAPH_ROWS);
            assertThat(citations)
                    .as("a citation naming only a file cannot tell a reader which assertion backs the row")
                    .allSatisfy(citation -> {
                        assertThat(citation.method()).isNotBlank();
                        assertThat(citation.file()).endsWith(".java");
                    });
        }

        @Test
        @DisplayName("every cited test file exists at its exact-case path under the test base")
        void everyCitedFileResolves() {
            final List<String> unresolved = citations().stream()
                    .filter(citation -> !Files.isRegularFile(ROOT.resolve(TEST_BASE + citation.file())))
                    .map(citation -> citation.rowId() + " -> " + citation.file())
                    .distinct()
                    .toList();
            assertThat(unresolved)
                    .as("a citation that does not resolve on disk is worse than no citation at all")
                    .isEmpty();
        }

        @Test
        @DisplayName("every cited method exists and carries an executable test annotation")
        void everyCitedMethodIsAnExecutableTest() {
            final List<String> offences = new ArrayList<>();
            for (final Citation citation : citations()) {
                final Path path = ROOT.resolve(TEST_BASE + citation.file());
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                if (!executableTests(citation.file()).contains(citation.method())) {
                    offences.add(citation.rowId() + " -> " + citation.file() + "::" + citation.method());
                }
            }
            assertThat(offences)
                    .as("each cited method must be a @Test, @ParameterizedTest or @RepeatedTest in the "
                            + "cited file: a setUp or a given-helper can neither pass nor fail on its own, "
                            + "so citing one would name evidence that does not exist")
                    .isEmpty();
        }

        @Test
        @DisplayName("the column legend documents the file::method convention it is checked against")
        void theLegendDocumentsTheConvention() {
            final String matrix = String.join(" ", lines("TRACEABILITY_MATRIX.md"));
            assertThat(matrix)
                    .as("a convention that is enforced but undocumented reads as an accident")
                    .contains("<file>::<method>");
        }

        /** Every `file::method` pair published in the matrix's Test column, in document order. */
        private List<Citation> citations() {
            final List<Citation> found = new ArrayList<>();
            for (final String line : lines("TRACEABILITY_MATRIX.md")) {
                if (!MATRIX_ROW.matcher(line).matches()) {
                    continue;
                }
                final String[] cells = line.split("\\|", -1);
                // split keeps a leading and a trailing empty cell for the row's own delimiters
                if (cells.length - 2 != PARAGRAPH_ROW_COLUMNS) {
                    continue;
                }
                final String rowId = cells[1].trim().replace("`", "");
                final String cell = cells[TEST_COLUMN + 1].trim().replace("`", "");
                final int separator = cell.indexOf("::");
                found.add(separator < 0
                        ? new Citation(rowId, cell, "")
                        : new Citation(rowId, cell.substring(0, separator),
                                cell.substring(separator + 2)));
            }
            return found;
        }

        /** The executable test method names declared in one test source. */
        private List<String> executableTests(final String relativeTestFile) {
            final String source = String.join("\n", lines(TEST_BASE + relativeTestFile));
            final List<String> names = new ArrayList<>();
            final Matcher matcher = EXECUTABLE_TEST.matcher(source);
            int from = 0;
            while (matcher.find(from)) {
                names.add(matcher.group(1));
                from = matcher.start() + 1;
            }
            return names;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The matrix's Java census figures, re-measured rather than trusted
    // ---------------------------------------------------------------------------------------------

    /**
     * Counts the Java files under one source root.
     *
     * @param relativeRoot source root relative to the repository root, for example {@code src/main/java}
     * @param packageDocs  {@code true} to count only {@code package-info.java}, {@code false} to count
     *                     only the files that are not {@code package-info.java}
     * @return the number of matching files
     */
    private static long javaFileCount(final String relativeRoot, final boolean packageDocs) {
        final Path base = ROOT.resolve(relativeRoot);
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".java"))
                    .filter(name -> "package-info.java".equals(name) == packageDocs)
                    .count();
        } catch (final IOException cause) {
            throw new UncheckedIOException("Cannot walk " + base, cause);
        }
    }

    /**
     * Reads the single integer published in a named row of the matrix's source-census table.
     *
     * <p>The table's rows are pipe-delimited with the label first and the count second, so the count is
     * the second cell. A label that matches no row is an error rather than a zero: it means the table was
     * renamed or removed and this check silently stopped guarding anything.</p>
     *
     * @param rowLabel the exact label in the table's first cell
     * @return the integer in that row's count cell
     */
    private static int censusFigure(final String rowLabel) {
        for (final String line : lines("TRACEABILITY_MATRIX.md")) {
            final String[] cells = line.split("\\|", -1);
            if (cells.length > 3 && rowLabel.equals(cells[1].trim())) {
                return Integer.parseInt(cells[2].trim());
            }
        }
        throw new IllegalStateException(
                "TRACEABILITY_MATRIX.md publishes no source-census row labelled \"" + rowLabel
                        + "\". Either the table was renamed, in which case this check must be repointed, "
                        + "or the row was deleted, in which case the census claim is gone and nothing "
                        + "replaced it.");
    }

    /**
     * The matrix's Java file counts, checked against the tree on every build.
     *
     * <p>Three figures in the source-census table of {@code TRACEABILITY_MATRIX.md} §2 describe this
     * repository's own Java tree rather than the frozen COBOL corpus: the production class count, the
     * {@code package-info.java} count that accompanies it, and the test class count. Unlike every other
     * row in that table, these three move whenever a file is added or removed, and they were authored by
     * hand. One of them had in fact already gone stale &mdash; it read 256 after a duplicate test class
     * was consolidated away, leaving the page overstating the suite by one file.</p>
     *
     * <p>A census figure that drifts is worse than an absent one, because a reader takes a published
     * number as measured. These tests therefore derive all three from the tree and fail if the page
     * disagrees, which converts the figures from assertions into measurements.</p>
     */
    @Nested
    @DisplayName("the matrix's Java census figures are measured against the tree, not authored by hand")
    final class JavaCensusIsMeasured {

        @Test
        @DisplayName("the production class count and its package-info companion both match src/main/java")
        void theProductionCensusMatches() {
            final long classes = javaFileCount("src/main/java", false);
            final long packageDocs = javaFileCount("src/main/java", true);

            assertThat(censusFigure("Java production classes"))
                    .as("TRACEABILITY_MATRIX.md publishes the production class count; src/main/java holds "
                            + "%d non-package-info Java files", Long.valueOf(classes))
                    .isEqualTo((int) classes);

            final String matrix = String.join(" ", lines("TRACEABILITY_MATRIX.md"));
            assertThat(matrix)
                    .as("the same row states the package-info companion count, and src/main/java holds %d",
                            Long.valueOf(packageDocs))
                    .contains("plus " + packageDocs + " `package-info.java`");
            assertThat(matrix)
                    .as("the row also states the file total, which must be the two parts added: %d + %d",
                            Long.valueOf(classes), Long.valueOf(packageDocs))
                    .contains("so " + (classes + packageDocs) + " files under `src/main/java`");
        }

        @Test
        @DisplayName("the test class count matches src/test/java")
        void theTestCensusMatches() {
            final long tests = javaFileCount("src/test/java", false);
            assertThat(censusFigure("Java test classes"))
                    .as("TRACEABILITY_MATRIX.md publishes the test class count; src/test/java holds %d "
                            + "non-package-info Java files", Long.valueOf(tests))
                    .isEqualTo((int) tests);
        }

        @Test
        @DisplayName("the test tree carries no package-info.java, exactly as the row's note states")
        void theTestTreeHasNoPackageDocumentation() {
            assertThat(javaFileCount("src/test/java", true))
                    .as("the census row's note says there is none; Rule 1 Clause E governs the published "
                            + "surface, which is the production tree, so a test-tree package doc would "
                            + "make the note wrong rather than make the tree better")
                    .isZero();
        }

        @Test
        @DisplayName("the page says the figures are measured, so a reader knows they are not hand-authored")
        void thePageDisclosesThatTheFiguresAreMeasured() {
            final String matrix = String.join(" ", lines("TRACEABILITY_MATRIX.md"));
            assertThat(matrix)
                    .as("a measured figure that reads as authored invites the next reader to edit it by "
                            + "hand, which is how this row went stale in the first place")
                    .contains("re-measured on every build by")
                    .contains("DocumentationConsistencyTest.JavaCensusIsMeasured");
        }
    }
}
