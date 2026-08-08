package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Component;

/**
 * Parity tests for {@link AreaCodeLookup}, the Java form of {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <p><strong>What this copybook is.</strong> 1,318 lines of pure data: three {@code 01} group items
 * carrying <em>five</em> {@code 88}-level {@code VALUES} lists, 1,276 literals in total. It declares
 * no {@code OCCURS} anywhere, so it is a set of membership predicates and <em>not</em> a table
 * &mdash; there is no subscript to convert from COBOL's 1-based indexing, and no index arithmetic is
 * tested here. {@link Provenance#theCopybookIsNotATable()} asserts that directly, so a later reader
 * cannot mistake this class for a table lookup.
 *
 * <p><strong>Where the expected values come from.</strong> No COBOL runtime exists in this
 * environment &mdash; the eight blockers are recorded in the plan as risk R-A &mdash; so nothing here
 * was captured from a live execution. Every expectation is <em>statically derived from
 * {@code app/cpy/CSLKPCDY.cpy}</em>, and the derivation is not left to a comment: {@link Provenance}
 * re-reads the copybook at test time and diffs each of the five runtime tables against the literals
 * on the copybook's own lines, in order. That makes all 1,276 literals verified rather than sampled,
 * and it means the counts 490, 410, 80, 56 and 240 asserted throughout this suite are proved against
 * the oracle instead of transcribed by hand. Each remaining block of expectations names the copybook
 * or program line it came from, so a wrong expectation is traceable to a misread line.
 *
 * <p><strong>What the risk actually is.</strong> Almost none of this translation is logic; the class
 * holds tables and compares strings. Two things can therefore go wrong, and the suite is built around
 * them:
 * <ol>
 *   <li><em>Transcription.</em> A literal lost, duplicated or copied from the neighbouring clause.
 *       {@link Provenance} and {@link Cardinality} close that off.</li>
 *   <li><em>Input shaping.</em> COBOL moves a value into a fixed-width {@code PICTURE} item before it
 *       tests the {@code 88}-level, and {@code COACTUPC} applies {@code FUNCTION TRIM} first. A Java
 *       translation that reached straight for {@code Set.contains} would agree with COBOL on clean
 *       input and diverge on everything else. {@link MoveSemantics}, {@link PhoneEditIdiom} and
 *       {@link StateAndZipEditIdioms} drive that shaping.</li>
 * </ol>
 *
 * <p><strong>Both sides of every condition name.</strong> {@link Predicates} drives all five
 * {@code 88}-levels true <em>and</em> false, including the two that no program in this repository
 * tests. Those two are dead condition names, and they are covered rather than quietly dropped,
 * because the copybook is the contract; see {@link Predicates} for the reference counts.
 *
 * <p><strong>Rules.</strong> The project supplies no user rules &mdash; {@code review_rules} returns
 * only "No user rules provided." &mdash; so this file is held to the plan's enterprise practices
 * instead: a plain JUnit 5 test with no Spring context and no mocking framework, no wildcard and no
 * static imports, no static mutable state, nothing disabled, and no dependency outside the closed set
 * of JUnit Jupiter, AssertJ and the module's own classes.
 */
@DisplayName("AreaCodeLookup - app/cpy/CSLKPCDY.cpy, five 88-level VALUES lists")
class AreaCodeLookupTest {

    /**
     * The copybook this class translates, relative to the repository root. It is the parity oracle
     * and is read-only: these tests read it and never write it.
     */
    private static final String COPYBOOK_PATH = "app/cpy/CSLKPCDY.cpy";

    /** A COBOL alphanumeric literal. Every literal in this copybook is single-quoted. */
    private static final Pattern COPYBOOK_LITERAL = Pattern.compile("'([^']*)'");

    /** The character COBOL's {@code FUNCTION TRIM} removes, and the only one. */
    private static final char SPACE = ' ';

    /**
     * The subject under test, rebuilt for every test method. An instance field assigned in a
     * non-static {@code @BeforeEach} rather than a static holder: {@code WORKING-STORAGE} must never
     * become static Java state, and a shared fixture would let one test's use leak into another's.
     */
    private AreaCodeLookup lookup;

    @BeforeEach
    void createSubjectUnderTest() {
        lookup = new AreaCodeLookup();
    }

    // =================================================================================================
    // The oracle. app/cpy/CSLKPCDY.cpy is read at test time so that no literal, and no count, has to
    // be trusted to a hand transcription in this file either.
    // =================================================================================================

    /**
     * The five {@code 88}-level {@code VALUES} clauses of {@code app/cpy/CSLKPCDY.cpy}: the condition
     * name, the inclusive copybook line range its literals occupy, the number of literals the clause
     * declares, and the runtime table {@link AreaCodeLookup} exposes for it.
     *
     * <p>The line ranges are the extents {@link Provenance#clauseBoundariesAreWhereTheCopybookPutsThem()}
     * verifies against the copybook's own punctuation, so they are checked rather than asserted.
     */
    private record Clause(String conditionName, int firstLine, int lastLine, int declaredCount,
                          Set<String> runtimeTable) {
    }

    /**
     * The five clauses, in copybook declaration order.
     *
     * <p>Line ranges and counts read from {@code app/cpy/CSLKPCDY.cpy}: the three area-code clauses
     * hang off {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at line 24, the state clause off
     * {@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} at line 1012, and the ZIP clause off
     * {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at line 1072.
     */
    private List<Clause> declaredClauses() {
        return List.of(
                // 88 VALID-PHONE-AREA-CODE ....... CSLKPCDY.cpy:30, literals on lines 30-520.
                // 491 physical lines yield 490 literals: line 440 is the in-list comment
                // "Easily recognizable codes begin here." and carries none.
                new Clause("VALID-PHONE-AREA-CODE", 30, 520,
                        AreaCodeLookup.VALID_PHONE_AREA_CODE_COUNT, lookup.validPhoneAreaCodes()),
                // 88 VALID-GENERAL-PURP-CODE ..... CSLKPCDY.cpy:521, literals on lines 521-930.
                new Clause("VALID-GENERAL-PURP-CODE", 521, 930,
                        AreaCodeLookup.VALID_GENERAL_PURP_CODE_COUNT,
                        lookup.validGeneralPurposeCodes()),
                // 88 VALID-EASY-RECOG-AREA-CODE .. CSLKPCDY.cpy:931, literals on lines 931-1010.
                new Clause("VALID-EASY-RECOG-AREA-CODE", 931, 1010,
                        AreaCodeLookup.VALID_EASY_RECOG_AREA_CODE_COUNT,
                        lookup.validEasyRecognitionAreaCodes()),
                // 88 VALID-US-STATE-CODE ......... CSLKPCDY.cpy:1013, literals on lines 1014-1069.
                // The 88 line itself carries no literal, so 57 physical lines yield 56.
                new Clause("VALID-US-STATE-CODE", 1013, 1069,
                        AreaCodeLookup.VALID_US_STATE_CODE_COUNT, lookup.validUsStateCodes()),
                // 88 VALID-US-STATE-ZIP-CD2-COMBO  CSLKPCDY.cpy:1073, literals on lines 1074-1313.
                // The 88 line carries no literal here either, so 241 lines yield 240.
                new Clause("VALID-US-STATE-ZIP-CD2-COMBO", 1073, 1313,
                        AreaCodeLookup.VALID_US_STATE_ZIP_CD2_COMBO_COUNT,
                        lookup.validStateZip2Combos()));
    }

    /**
     * The literals of one clause, in the order the copybook declares them, read straight out of
     * {@code app/cpy/CSLKPCDY.cpy}.
     *
     * <p>Comment lines are skipped, which is what makes line 440 harmless. No line in any of the five
     * clauses holds two literals, so one literal per line is a safe reading of this copybook and a
     * line holding none is simply skipped.
     */
    private List<String> copybookLiterals(Clause clause) {
        List<String> lines = copybookLines();
        List<String> literals = new ArrayList<>();
        for (int line = clause.firstLine(); line <= clause.lastLine(); line++) {
            String literal = copybookLiteral(lines.get(line - 1));
            if (literal != null) {
                literals.add(literal);
            }
        }
        return literals;
    }

    /**
     * The single literal a copybook line holds, or {@code null} when it holds none - a comment, a
     * blank line, or the {@code 88 ... VALUES} line of a clause whose first literal is on the line
     * below.
     */
    private String copybookLiteral(String line) {
        if (isComment(line)) {
            return null;
        }
        Matcher matcher = COPYBOOK_LITERAL.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** True for a COBOL fixed-format comment: an asterisk in the indicator area, column 7. */
    private boolean isComment(String line) {
        return line.length() >= 7 && line.charAt(6) == '*';
    }

    /** Every line of the copybook, 0-based, so copybook line <em>n</em> is element <em>n</em>-1. */
    private List<String> copybookLines() {
        Path copybook = copybookPath();
        try {
            // The copybook is pure ASCII, so US-ASCII reads it exactly; the charset is named rather
            // than defaulted, as every encoding decision in this module is.
            return Files.readAllLines(copybook, StandardCharsets.US_ASCII);
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot read the parity oracle " + copybook, cause);
        }
    }

    /**
     * Locates {@code app/cpy/CSLKPCDY.cpy} by walking up from the working directory.
     *
     * <p>Surefire runs with the Maven module directory as its working directory, so the walk is two
     * levels; resolving it rather than hard-coding {@code ../..} keeps the suite runnable from the
     * repository root and from an IDE as well. The nearest ancestor wins, so a checkout nested inside
     * another cannot be read by mistake.
     */
    private Path copybookPath() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path copybook = candidate.resolve(COPYBOOK_PATH);
            if (Files.isRegularFile(copybook)) {
                return copybook;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + COPYBOOK_PATH + " at or above "
                + Path.of("").toAbsolutePath() + ". It is the parity oracle for AreaCodeLookup and "
                + "is a read-only reference file, so it must be present: this suite proves the "
                + "transcription against the copybook itself rather than against a copy of it.");
    }

    /**
     * COBOL's {@code FUNCTION TRIM}: removes leading and trailing <em>spaces</em>, and nothing else.
     *
     * <p>Deliberately not {@link String#strip()}, which also removes tabs and every other Unicode
     * whitespace character. {@code FUNCTION TRIM} on an alphanumeric item removes spaces only, and
     * this helper exists so {@link PhoneEditIdiom} reproduces {@code app/cbl/COACTUPC.cbl:2296}
     * rather than something close to it.
     */
    private String functionTrim(String value) {
        int first = 0;
        int last = value.length();
        while (first < last && value.charAt(first) == SPACE) {
            first++;
        }
        while (last > first && value.charAt(last - 1) == SPACE) {
            last--;
        }
        return value.substring(first, last);
    }

    // =================================================================================================

    @Nested
    @DisplayName("Provenance - every literal diffed against app/cpy/CSLKPCDY.cpy")
    class Provenance {

        /**
         * The gate that matters. For each of the five clauses, read the literals off the copybook's own
         * lines and require the runtime table to be exactly that list, in exactly that order.
         *
         * <p>All 1,276 literals, no sampling. Order is asserted as well as membership, because a table
         * that iterates in copybook order is a table a reviewer can diff against the copybook by eye;
         * and because a literal copied from the neighbouring clause would still be a member of
         * <em>something</em>, while it could not possibly be at the right position.
         */
        @Test
        @DisplayName("each table is exactly its copybook clause, in declaration order")
        void everyTableIsExactlyItsCopybookClause() {
            for (Clause clause : declaredClauses()) {
                List<String> declared = copybookLiterals(clause);
                Assertions.assertThat(declared)
                        .as("literals read from %s:%d-%d", COPYBOOK_PATH, clause.firstLine(),
                                clause.lastLine())
                        .hasSize(clause.declaredCount());
                Assertions.assertThat(clause.runtimeTable())
                        .as("88 %s against %s:%d-%d", clause.conditionName(), COPYBOOK_PATH,
                                clause.firstLine(), clause.lastLine())
                        .containsExactlyElementsOf(declared);
            }
        }

        /**
         * The counts 490, 410, 80, 56 and 240 are asserted all over this suite and are published as
         * constants by the class under test. Here they are proved against the copybook, so that every
         * other use of them rests on the oracle rather than on a number somebody typed.
         */
        @Test
        @DisplayName("the counts 490, 410, 80, 56 and 240 are the copybook's own")
        void theCopybookDeclaresTheCountsThisSuiteAsserts() {
            List<Clause> clauses = declaredClauses();
            Assertions.assertThat(clauses).hasSize(5);

            for (Clause clause : clauses) {
                Assertions.assertThat(copybookLiterals(clause))
                        .as("88 %s literal count", clause.conditionName())
                        .hasSize(clause.declaredCount());
                Assertions.assertThat(clause.runtimeTable())
                        .as("88 %s table size", clause.conditionName())
                        .hasSize(clause.declaredCount());
            }

            // Spelled out once, so the numbers this suite quotes are visible next to their proof.
            Assertions.assertThat(clauses.stream().map(Clause::declaredCount))
                    .containsExactly(490, 410, 80, 56, 240);
            Assertions.assertThat(clauses.stream().mapToInt(Clause::declaredCount).sum())
                    .as("1,276 literals across the five clauses")
                    .isEqualTo(1276);
        }

        /**
         * The line ranges this suite reads are the clause extents the copybook's own punctuation
         * defines: a {@code VALUES} clause runs from its {@code 88} line to the line carrying the
         * terminating period, every literal line in between ends in a comma, and the next declaration
         * begins immediately after.
         *
         * <p>Without this, a range that silently overran into the next clause would make every other
         * provenance assertion agree with a table that had been built the same wrong way.
         */
        @Test
        @DisplayName("clause line ranges end where the copybook's terminating period is")
        void clauseBoundariesAreWhereTheCopybookPutsThem() {
            List<String> lines = copybookLines();
            Assertions.assertThat(lines).as("%s line count", COPYBOOK_PATH).hasSize(1318);

            for (Clause clause : declaredClauses()) {
                String first = lines.get(clause.firstLine() - 1);
                Assertions.assertThat(first)
                        .as("%s:%d opens 88 %s", COPYBOOK_PATH, clause.firstLine(),
                                clause.conditionName())
                        .contains("88 " + clause.conditionName())
                        .contains("VALUES");

                for (int line = clause.firstLine(); line <= clause.lastLine(); line++) {
                    String raw = lines.get(line - 1);
                    if (isComment(raw)) {
                        continue;
                    }
                    String content = raw.stripTrailing();
                    if (line == clause.lastLine()) {
                        Assertions.assertThat(content)
                                .as("%s:%d closes 88 %s", COPYBOOK_PATH, line,
                                        clause.conditionName())
                                .endsWith(".");
                    } else if (copybookLiteral(raw) != null) {
                        Assertions.assertThat(content)
                                .as("%s:%d continues 88 %s", COPYBOOK_PATH, line,
                                        clause.conditionName())
                                .endsWith(",");
                    } else {
                        Assertions.assertThat(content)
                                .as("%s:%d is the VALUES header of 88 %s", COPYBOOK_PATH, line,
                                        clause.conditionName())
                                .endsWith("VALUES");
                    }
                }

                String next = lines.get(clause.lastLine());
                Assertions.assertThat(isComment(next) || startsDeclaration(next))
                        .as("%s:%d begins a new declaration or is a comment, so 88 %s really ends "
                                        + "at line %d", COPYBOOK_PATH, clause.lastLine() + 1,
                                clause.conditionName(), clause.lastLine())
                        .isTrue();
            }
        }

        /**
         * {@code CSLKPCDY} is predicates over sets, not a table.
         *
         * <p>Asserted, not merely stated, because the one thing a reader coming to a COBOL lookup
         * expects is an {@code OCCURS} table and a subscript - and the 1-based to 0-based conversion
         * that comes with it. There is no such conversion to get wrong here, and this test is what
         * keeps that claim honest if the copybook is ever revisited.
         */
        @Test
        @DisplayName("the copybook declares no OCCURS, so there is no index to convert")
        void theCopybookIsNotATable() {
            Assertions.assertThat(copybookLines())
                    .as("no line of %s declares an OCCURS", COPYBOOK_PATH)
                    .noneMatch(line -> line.contains("OCCURS"));
        }

        /**
         * The probe widths modelled by {@link AreaCodeLookup} are the widths the copybook's
         * {@code PICTURE} clauses declare, checked line by line.
         *
         * <p>{@code PIC XXX} at line 24 is {@code PIC X(3)} written the older way; it is three
         * character positions either way.
         */
        @Test
        @DisplayName("the probe widths are the copybook's PICTURE clauses")
        void probeWidthsComeFromTheCopybookPictureClauses() {
            List<String> lines = copybookLines();

            Assertions.assertThat(lines.get(23))
                    .as("%s:24", COPYBOOK_PATH)
                    .contains("01 WS-US-PHONE-AREA-CODE-TO-EDIT")
                    .contains("PIC XXX");
            Assertions.assertThat(AreaCodeLookup.PHONE_AREA_CODE_LENGTH).isEqualTo(3);

            Assertions.assertThat(lines.get(1011))
                    .as("%s:1012", COPYBOOK_PATH)
                    .contains("01 US-STATE-CODE-TO-EDIT")
                    .contains("PIC X(2)");
            Assertions.assertThat(AreaCodeLookup.US_STATE_CODE_LENGTH).isEqualTo(2);

            Assertions.assertThat(lines.get(1070))
                    .as("%s:1071", COPYBOOK_PATH)
                    .contains("01 US-STATE-ZIPCODE-TO-EDIT");
            Assertions.assertThat(lines.get(1071))
                    .as("%s:1072", COPYBOOK_PATH)
                    .contains("02 US-STATE-AND-FIRST-ZIP2")
                    .contains("PIC X(4)");
            Assertions.assertThat(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH).isEqualTo(4);

            Assertions.assertThat(lines.get(1313))
                    .as("%s:1314", COPYBOOK_PATH)
                    .contains("02 LAST-3-OF-ZIP")
                    .contains("PIC X(3)");
            Assertions.assertThat(AreaCodeLookup.LAST_3_OF_ZIP_LENGTH).isEqualTo(3);
        }

        /**
         * No literal line of any clause was skipped: every physical line in each range is accounted
         * for as a literal, a comment, or the {@code VALUES} header.
         *
         * <p>This is where line 440 is pinned down. {@code VALID-PHONE-AREA-CODE} spans 491 physical
         * lines and declares 490 literals, and the missing one is not an omission - it is the in-list
         * comment "Easily recognizable codes begin here.", which sits <em>inside</em> the clause and
         * is the copybook's own note that the easily recognisable codes follow.
         */
        @Test
        @DisplayName("every physical line of every clause is accounted for, line 440 included")
        void everyPhysicalLineIsAccountedFor() {
            List<String> lines = copybookLines();
            for (Clause clause : declaredClauses()) {
                int literals = 0;
                int comments = 0;
                int headers = 0;
                for (int line = clause.firstLine(); line <= clause.lastLine(); line++) {
                    String raw = lines.get(line - 1);
                    if (isComment(raw)) {
                        comments++;
                    } else if (copybookLiteral(raw) != null) {
                        literals++;
                    } else {
                        headers++;
                    }
                }
                int physical = clause.lastLine() - clause.firstLine() + 1;
                Assertions.assertThat(literals + comments + headers)
                        .as("every line of 88 %s is a literal, a comment or the VALUES header",
                                clause.conditionName())
                        .isEqualTo(physical);
                Assertions.assertThat(literals)
                        .as("88 %s literal lines", clause.conditionName())
                        .isEqualTo(clause.declaredCount());
                Assertions.assertThat(headers)
                        .as("88 %s has at most one literal-free VALUES header line",
                                clause.conditionName())
                        .isLessThanOrEqualTo(1);
            }

            Assertions.assertThat(lines.get(439))
                    .as("%s:440, the in-list comment inside 88 VALID-PHONE-AREA-CODE", COPYBOOK_PATH)
                    .contains("Easily recognizable codes begin here");
            Assertions.assertThat(isComment(lines.get(439))).isTrue();
        }

        /** True for a line that opens a new COBOL data or condition-name declaration. */
        private boolean startsDeclaration(String line) {
            String content = line.strip();
            return content.startsWith("01 ") || content.startsWith("02 ")
                    || content.startsWith("88 ");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Cardinality - 490, 410, 80, 56 and 240 entries at 3, 3, 3, 2 and 4 characters")
    class Cardinality {

        /**
         * Every table holds exactly what its {@code 88}-level declares, and the class publishes that
         * count as a constant. Both the constant and the table are checked against the copybook, so
         * neither can drift from it alone.
         */
        @Test
        @DisplayName("every table holds the number of literals the copybook declares")
        void tableSizesMatchTheDeclaredCounts() {
            Assertions.assertThat(lookup.validPhoneAreaCodes())
                    .hasSize(AreaCodeLookup.VALID_PHONE_AREA_CODE_COUNT)
                    .hasSize(490);
            Assertions.assertThat(lookup.validGeneralPurposeCodes())
                    .hasSize(AreaCodeLookup.VALID_GENERAL_PURP_CODE_COUNT)
                    .hasSize(410);
            Assertions.assertThat(lookup.validEasyRecognitionAreaCodes())
                    .hasSize(AreaCodeLookup.VALID_EASY_RECOG_AREA_CODE_COUNT)
                    .hasSize(80);
            Assertions.assertThat(lookup.validUsStateCodes())
                    .hasSize(AreaCodeLookup.VALID_US_STATE_CODE_COUNT)
                    .hasSize(56);
            Assertions.assertThat(lookup.validStateZip2Combos())
                    .hasSize(AreaCodeLookup.VALID_US_STATE_ZIP_CD2_COMBO_COUNT)
                    .hasSize(240);
        }

        /**
         * Every entry is exactly as wide as the probe it will be compared against.
         *
         * <p>An entry of the wrong width could never match anything, because a probe is always brought
         * to its item's declared width first - so a mistyped literal would become a silently
         * unreachable rule rather than a visible failure.
         */
        @Test
        @DisplayName("every entry is at its probe's declared PICTURE width")
        void everyEntryIsAtItsProbesDeclaredWidth() {
            Assertions.assertThat(lookup.validPhoneAreaCodes())
                    .as("PIC XXX, CSLKPCDY.cpy:24")
                    .allSatisfy(code -> Assertions.assertThat(code)
                            .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH));
            Assertions.assertThat(lookup.validGeneralPurposeCodes())
                    .allSatisfy(code -> Assertions.assertThat(code)
                            .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH));
            Assertions.assertThat(lookup.validEasyRecognitionAreaCodes())
                    .allSatisfy(code -> Assertions.assertThat(code)
                            .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH));
            Assertions.assertThat(lookup.validUsStateCodes())
                    .as("PIC X(2), CSLKPCDY.cpy:1012")
                    .allSatisfy(code -> Assertions.assertThat(code)
                            .hasSize(AreaCodeLookup.US_STATE_CODE_LENGTH));
            Assertions.assertThat(lookup.validStateZip2Combos())
                    .as("PIC X(4), CSLKPCDY.cpy:1072")
                    .allSatisfy(combo -> Assertions.assertThat(combo)
                            .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH));
        }

        /**
         * The first and last literal of each clause, both derived from the copybook and spelled out.
         *
         * <p>Spelling them out matters: the plan's table left the last literal of the two long
         * area-code clauses blank, and reading them off the copybook settles it -
         * {@code VALID-PHONE-AREA-CODE} ends at {@code '999'} (line 520) while
         * {@code VALID-GENERAL-PURP-CODE} ends at {@code '989'} (line 930). They are different codes
         * because the general purpose clause stops before the easily recognisable ones begin.
         */
        @Test
        @DisplayName("the first and last entry of each table are the copybook's own")
        void boundaryEntriesAreTheCopybookBoundaries() {
            for (Clause clause : declaredClauses()) {
                List<String> declared = copybookLiterals(clause);
                Assertions.assertThat(clause.runtimeTable())
                        .as("88 %s boundaries", clause.conditionName())
                        .startsWith(declared.get(0))
                        .endsWith(declared.get(declared.size() - 1));
            }

            // CSLKPCDY.cpy:30 and :520 / :521 and :930 / :931 and :1010 / :1014 and :1069 /
            // :1074 and :1313.
            Assertions.assertThat(lookup.validPhoneAreaCodes()).startsWith("201").endsWith("999");
            Assertions.assertThat(lookup.validGeneralPurposeCodes())
                    .startsWith("201").endsWith("989");
            Assertions.assertThat(lookup.validEasyRecognitionAreaCodes())
                    .startsWith("200").endsWith("999");
            Assertions.assertThat(lookup.validUsStateCodes()).startsWith("AL").endsWith("VI");
            Assertions.assertThat(lookup.validStateZip2Combos())
                    .startsWith("AA34").endsWith("WY83");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Algebra - how the three area-code clauses relate, derived not assumed")
    class Algebra {

        /**
         * The three area-code clauses hang off one {@code PIC XXX} item, and their relationship is a
         * property of the copybook rather than a convention: {@code VALID-GENERAL-PURP-CODE} and
         * {@code VALID-EASY-RECOG-AREA-CODE} are disjoint and their union is exactly
         * {@code VALID-PHONE-AREA-CODE}. 410 + 80 = 490.
         *
         * <p>Worth asserting because it is the sharpest test data available: every easily recognisable
         * code is a correct-width, genuinely valid area code that the condition {@code COACTUPC}
         * actually tests must nevertheless <em>reject</em>.
         */
        @Test
        @DisplayName("general purpose and easily recognisable partition the phone area codes")
        void generalAndEasyPartitionThePhoneList() {
            Set<String> general = lookup.validGeneralPurposeCodes();
            Set<String> easy = lookup.validEasyRecognitionAreaCodes();
            Set<String> union = new LinkedHashSet<>(general);
            union.addAll(easy);

            Assertions.assertThat(union).as("410 + 80 = 490, so nothing overlaps").hasSize(490);
            Assertions.assertThat(union)
                    .containsExactlyInAnyOrderElementsOf(lookup.validPhoneAreaCodes());
            Assertions.assertThat(general)
                    .as("the two clauses are disjoint")
                    .doesNotContainAnyElementsOf(easy);
            Assertions.assertThat(lookup.validPhoneAreaCodes())
                    .as("each clause is a subset of the full NANP list")
                    .containsAll(general)
                    .containsAll(easy);
        }

        /**
         * The 80 easily recognisable codes are {@code N00, N11, N22 ... N99} for the hundreds 2 to 9 -
         * the service-style codes such as {@code '800'}, {@code '888'} and {@code '911'}.
         *
         * <p>The rule is asserted here precisely so that nobody is tempted to <em>implement</em> it.
         * The other two clauses follow no rule at all, and a computed table would admit or reject codes
         * the COBOL does not.
         */
        @Test
        @DisplayName("the easily recognisable codes are N00, N11 ... N99 for the hundreds 2 to 9")
        void easilyRecognisableCodesFollowTheServicePattern() {
            Set<String> expected = new LinkedHashSet<>();
            for (char hundred = '2'; hundred <= '9'; hundred++) {
                for (char repeated = '0'; repeated <= '9'; repeated++) {
                    expected.add(String.valueOf(hundred) + repeated + repeated);
                }
            }
            Assertions.assertThat(expected).hasSize(80);
            Assertions.assertThat(lookup.validEasyRecognitionAreaCodes())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        /**
         * The copybook's two views of a state disagree, and the disagreement is preserved.
         *
         * <p>The 240 ZIP combinations are built from <em>62</em> distinct two-character prefixes, six
         * of which - {@code 'AA'}, {@code 'AE'}, {@code 'AP'}, {@code 'FM'}, {@code 'MH'} and
         * {@code 'PW'} - are absent from the 56-entry state clause. The asymmetry is one-way: every
         * state code does have at least one ZIP combination.
         *
         * <p>It has an observable consequence in the only program that uses this copybook.
         * {@code app/cbl/COACTUPC.cbl} runs {@code 1270-EDIT-US-STATE-CD} (line 2493) and
         * {@code 1280-EDIT-US-STATE-ZIP-CD} (line 2536) as two separate edits, so an armed-forces
         * address clears the ZIP edit and is still rejected by the state edit. Reconciling the two
         * lists would change the business rule rather than translate it.
         */
        @Test
        @DisplayName("the ZIP clause uses 62 prefixes, six of which are not valid state codes")
        void zipPrefixesExceedTheStateTable() {
            Set<String> prefixes = new TreeSet<>();
            for (String combo : lookup.validStateZip2Combos()) {
                prefixes.add(combo.substring(0, AreaCodeLookup.US_STATE_CODE_LENGTH));
            }
            Assertions.assertThat(prefixes).as("distinct state prefixes in the ZIP clause")
                    .hasSize(62);

            Set<String> notStateCodes = new TreeSet<>(prefixes);
            notStateCodes.removeAll(lookup.validUsStateCodes());
            Assertions.assertThat(notStateCodes)
                    .as("ZIP prefixes that 88 VALID-US-STATE-CODE does not recognise")
                    .containsExactly("AA", "AE", "AP", "FM", "MH", "PW");

            Set<String> statesWithoutAnyZip = new TreeSet<>(lookup.validUsStateCodes());
            statesWithoutAnyZip.removeAll(prefixes);
            Assertions.assertThat(statesWithoutAnyZip)
                    .as("the asymmetry is one-way: every state code has a ZIP combination")
                    .isEmpty();

            // The consequence, preserved rather than reconciled.
            Assertions.assertThat(lookup.isValidStateAndZipCombination("AA", "34000")).isTrue();
            Assertions.assertThat(lookup.isValidUsStateCode("AA")).isFalse();
        }

        /**
         * The 56 state entries are the fifty states, {@code 'DC'}, and the five territory codes
         * {@code 'AS'}, {@code 'GU'}, {@code 'MP'}, {@code 'PR'} and {@code 'VI'}.
         *
         * <p>Asserted so that the list is never "corrected" to fifty. The exact membership is proved
         * against the copybook by {@link Provenance}; what this adds is the notable inclusions and the
         * notable absences, each of which is a plausible-looking code that the COBOL rejects.
         */
        @Test
        @DisplayName("the state clause holds the fifty states, DC and five territories")
        void stateTableComposition() {
            Assertions.assertThat(lookup.validUsStateCodes())
                    .hasSize(56)
                    .contains("AL", "AK", "CA", "NY", "TX", "WY")
                    .as("the District of Columbia is a state code here")
                    .contains("DC")
                    .as("five territories are state codes here")
                    .contains("AS", "GU", "MP", "PR", "VI")
                    .as("the armed-forces codes are not state codes in this copybook, "
                            + "though the ZIP clause knows them")
                    .doesNotContain("AA", "AE", "AP")
                    .as("nor are the freely associated states")
                    .doesNotContain("FM", "MH", "PW");
            Assertions.assertThat(lookup.validUsStateCodes())
                    .as("every entry is two upper-case letters")
                    .allSatisfy(code -> Assertions.assertThat(code).matches("[A-Z]{2}"));
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("MOVE semantics - where a plain Set.contains would diverge from COBOL")
    class MoveSemantics {

        /**
         * A COBOL alphanumeric {@code MOVE} truncates on the <strong>right</strong>: the receiver is
         * filled from its leftmost position and the overflow is discarded. So an over-long argument is
         * not rejected, it is shortened - and the shortened value can be a member.
         */
        @ParameterizedTest(name = "[{index}] \"{0}\" moves into PIC XXX as \"{1}\"")
        @CsvSource({
            "2011,   201",
            "201X,   201",
            "9999,   999",
            "201999, 201",
        })
        @DisplayName("an over-long area code is truncated on the right, not rejected")
        void overLongAreaCodeIsTruncatedOnTheRight(String probe, String surviving) {
            Assertions.assertThat(probe).startsWith(surviving);
            Assertions.assertThat(lookup.isValidPhoneAreaCode(probe))
                    .as("\"%s\" and \"%s\" are the same probe once moved", probe, surviving)
                    .isEqualTo(lookup.isValidPhoneAreaCode(surviving))
                    .isTrue();
        }

        /**
         * Truncation on the left would keep different characters, and would accept input the COBOL
         * rejects. {@code "X201"} fills a {@code PIC XXX} receiver as {@code "X20"}, which is no area
         * code at all; a left-truncating translation would produce {@code "201"} and wrongly pass it.
         */
        @Test
        @DisplayName("truncation is on the right, never on the left")
        void truncationIsNeverOnTheLeft() {
            Assertions.assertThat(lookup.isValidPhoneAreaCode("X201")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("X201")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("XCA")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("XCA90")).isFalse();
        }

        /**
         * A short argument is space-padded on the right, and no literal in this copybook contains a
         * space - so a short probe can never be a member of any of the three area-code clauses.
         */
        @ParameterizedTest(name = "[{index}] \"{0}\" is space-padded and matches nothing")
        @ValueSource(strings = {"", " ", "2", "20", "  ", "   "})
        @DisplayName("a short or blank area code is space-padded on the right and matches nothing")
        void shortOrBlankAreaCodeIsPaddedAndMatchesNothing(String probe) {
            Assertions.assertThat(lookup.isValidPhoneAreaCode(probe)).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(probe)).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode(probe)).isFalse();
        }

        /**
         * All spaces is the state a COBOL {@code WORKING-STORAGE} item without a {@code VALUE} clause
         * starts in, and it is a member of nothing. Every one of the five condition names is driven
         * here, so the blank case is covered for all of them rather than for the area codes alone.
         */
        @Test
        @DisplayName("a blank probe is a member of no clause")
        void blankProbeIsAMemberOfNoClause() {
            Assertions.assertThat(lookup.isValidPhoneAreaCode("   ")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("   ")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("   ")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("  ")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("    ")).isFalse();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("  ", "          "))
                    .isFalse();

            // An empty string is the same probe once moved: the receiver is fixed width either way.
            Assertions.assertThat(lookup.isValidPhoneAreaCode("")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("")).isFalse();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("", "")).isFalse();
        }

        /**
         * A COBOL {@code 88}-level comparison folds no case, so neither may this class. Lower case is
         * a different byte and a different answer.
         */
        @ParameterizedTest(name = "[{index}] \"{0}\" is rejected, \"{1}\" is accepted")
        @CsvSource({
            "ca, CA",
            "ny, NY",
            "gu, GU",
            "vi, VI",
        })
        @DisplayName("state-code comparison is case-sensitive")
        void stateCodeComparisonIsCaseSensitive(String lower, String upper) {
            Assertions.assertThat(lookup.isValidUsStateCode(lower)).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode(upper)).isTrue();
        }

        @Test
        @DisplayName("ZIP-combination comparison is case-sensitive, in either half of the probe")
        void zipCombinationComparisonIsCaseSensitive() {
            Assertions.assertThat(lookup.isValidStateZip2Combo("wy83")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("Wy83")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("WY83")).isTrue();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("ca", "90210")).isFalse();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "90210")).isTrue();
        }

        /**
         * A probe narrower than its item is padded and so matches nothing, whatever it spells: two
         * digits are not an area code and one letter is not a state code.
         */
        @Test
        @DisplayName("a probe narrower than its item matches nothing")
        void aProbeNarrowerThanItsItemMatchesNothing() {
            Assertions.assertThat(lookup.isValidPhoneAreaCode("99")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("20")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("91")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("C")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("CA9")).isFalse();
        }

        /**
         * The other half of the same rule, and the counter-intuitive half: a probe <em>wider</em> than
         * its item is truncated on the right, so an over-long value can be accepted. {@code "CAL"}
         * fills {@code US-STATE-CODE-TO-EDIT PIC X(2)} as {@code "CA"} and passes the state edit.
         *
         * <p>That is COBOL's rule, not a leniency this class invented, and it is asserted rather than
         * left to be discovered: a translation that rejected over-long input instead - which reads like
         * the safer choice - would reject an address {@code COACTUPC} accepts.
         */
        @Test
        @DisplayName("a probe wider than its item is truncated on the right and can be accepted")
        void aProbeWiderThanItsItemIsTruncatedAndCanBeAccepted() {
            Assertions.assertThat(lookup.isValidUsStateCode("CAL"))
                    .as("\"CAL\" moves into PIC X(2) as \"CA\"")
                    .isTrue();
            Assertions.assertThat(lookup.isValidUsStateCode("CALIFORNIA")).isTrue();
            Assertions.assertThat(lookup.isValidStateZip2Combo("CA902"))
                    .as("\"CA902\" moves into PIC X(4) as \"CA90\"")
                    .isTrue();
            Assertions.assertThat(lookup.isValidPhoneAreaCode("2011")).isTrue();

            // Truncation cannot manufacture a member out of nothing: the leading characters still
            // have to spell one.
            Assertions.assertThat(lookup.isValidUsStateCode("ZZZ")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("ZZ999")).isFalse();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("1260-EDIT-US-PHONE-NUM - FUNCTION TRIM then MOVE, app/cbl/COACTUPC.cbl:2296-2298")
    class PhoneEditIdiom {

        /**
         * The idiom, verbatim from {@code app/cbl/COACTUPC.cbl:2296-2298}:
         * <pre>
         *   MOVE FUNCTION TRIM (WS-EDIT-US-PHONE-NUMA)
         *     TO WS-US-PHONE-AREA-CODE-TO-EDIT
         *   IF VALID-GENERAL-PURP-CODE
         *       CONTINUE
         *   ELSE
         *       ... ': Not valid North America general purpose area code' ...
         * </pre>
         * {@code WS-EDIT-US-PHONE-NUMA} is {@code PIC X(3)} (line 87) and the receiver is
         * {@code PIC XXX} (copybook line 24), so trimming can only ever <em>shorten</em> the value -
         * after which the {@code MOVE} pads it back out on the right. That round trip is the whole
         * subject of this group, and it is where a translation that simply called {@code contains}
         * would agree with COBOL on clean input and diverge on everything else.
         */
        @ParameterizedTest(name = "[{index}] TRIM(\"{0}\") -> \"{1}\", accepted")
        @CsvSource(quoteCharacter = '\'', value = {
            "' 201 ', '201'",
            "'201 ',  '201'",
            "' 201',  '201'",
            "' 989 ', '989'",
            "'212',   '212'",
        })
        @DisplayName("a padded but complete area code is trimmed and passes the edit")
        void aTrimmedAreaCodePassesTheEdit(String typed, String trimmed) {
            Assertions.assertThat(functionTrim(typed)).isEqualTo(trimmed);
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(functionTrim(typed)))
                    .as("TRIM(\"%s\") passes 88 VALID-GENERAL-PURP-CODE", typed)
                    .isTrue();
        }

        /**
         * The case the trim cannot rescue. A two-digit entry trims to two characters and the
         * {@code MOVE} into {@code PIC XXX} pads it back to {@code "20 "} - three characters, one of
         * them a space, and no literal in the clause contains a space. So the edit fails and
         * {@code COACTUPC} reports "Not valid North America general purpose area code" (line 2306).
         *
         * <p>The probe image is asserted, not just the answer, because {@code "20 "} is the shape a
         * reader has to see to believe the outcome.
         */
        @ParameterizedTest(name = "[{index}] TRIM(\"{0}\") -> \"{1}\" pads to \"{2}\", rejected")
        @CsvSource(quoteCharacter = '\'', value = {
            "'20 ', '20', '20 '",
            "' 20', '20', '20 '",
            "'2  ', '2',  '2  '",
            "'  2', '2',  '2  '",
        })
        @DisplayName("a short area code is padded back out and fails the edit")
        void aShortAreaCodeIsPaddedBackOutAndFailsTheEdit(String typed, String trimmed,
                                                         String probeImage) {
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            Assertions.assertThat(functionTrim(typed)).isEqualTo(trimmed);
            Assertions.assertThat(codec.movePicX(trimmed, AreaCodeLookup.PHONE_AREA_CODE_LENGTH))
                    .as("MOVE TRIM(\"%s\") TO WS-US-PHONE-AREA-CODE-TO-EDIT", typed)
                    .isEqualTo(probeImage)
                    .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH);
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(functionTrim(typed))).isFalse();
        }

        /**
         * An all-spaces or empty entry trims to nothing and pads to three spaces, which is a member of
         * no clause. In the running program a blank area code never reaches this test - line 2247
         * catches it first with "Area code must be supplied." - but the condition name must still
         * answer {@code false}, because that guard is the program's, not the copybook's.
         */
        @ParameterizedTest(name = "[{index}] TRIM(\"{0}\") is empty, rejected")
        @CsvSource(quoteCharacter = '\'', value = {"'   '", "' '", "''"})
        @DisplayName("a blank area code trims to nothing and fails the edit")
        void aBlankAreaCodeFailsTheEdit(String typed) {
            Assertions.assertThat(functionTrim(typed)).isEmpty();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(functionTrim(typed))).isFalse();
        }

        /**
         * The edit is gated on {@code VALID-GENERAL-PURP-CODE}, not on the full NANP list, and the
         * difference is observable: {@code '800'}, {@code '911'}, {@code '999'} and {@code '200'} are
         * real area codes and members of {@code VALID-PHONE-AREA-CODE}, yet {@code COACTUPC} rejects
         * every one of them as a customer phone area code.
         *
         * <p>This is the reason the two dead condition names are still modelled. Reaching for the
         * wider {@code VALID-PHONE-AREA-CODE} because its name reads like the obvious one would accept
         * eighty codes the program rejects, and no compiler would object.
         */
        @ParameterizedTest(name = "[{index}] \"{0}\" is a NANP code the phone edit still rejects")
        @ValueSource(strings = {"200", "800", "888", "911", "999"})
        @DisplayName("the edit rejects an easily recognisable code, though it is a real area code")
        void theEditRejectsAnEasilyRecognisableCode(String areaCode) {
            Assertions.assertThat(lookup.isValidPhoneAreaCode(areaCode))
                    .as("\"%s\" is in the full NANP list", areaCode)
                    .isTrue();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode(areaCode))
                    .as("\"%s\" is an easily recognisable code", areaCode)
                    .isTrue();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(areaCode))
                    .as("but the edit at COACTUPC:2298 rejects it")
                    .isFalse();
        }

        /**
         * The lookup does not trim on the caller's behalf, deliberately: {@code FUNCTION TRIM} is a
         * statement in {@code COACTUPC}, not part of the copybook's contract. A caller that skips it
         * gets exactly the answer COBOL would give untrimmed - {@code " 201"} fills the receiver as
         * {@code " 20"} and fails.
         */
        @Test
        @DisplayName("the lookup does not trim for you, so the caller's TRIM is what makes it pass")
        void theLookupDoesNotTrimForTheCaller() {
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(" 201"))
                    .as("untrimmed, \" 201\" is the probe \" 20\"")
                    .isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(functionTrim(" 201")))
                    .as("trimmed first, as COACTUPC:2296 does, it passes")
                    .isTrue();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("1270 and 1280 - the state and ZIP edits, app/cbl/COACTUPC.cbl:2493 and :2536")
    class StateAndZipEditIdioms {

        /**
         * {@code 1270-EDIT-US-STATE-CD} at {@code app/cbl/COACTUPC.cbl:2493}:
         * <pre>
         *   MOVE ACUP-NEW-CUST-ADDR-STATE-CD TO US-STATE-CODE-TO-EDIT
         *   IF VALID-US-STATE-CODE
         *       CONTINUE
         *   ELSE
         *       SET INPUT-ERROR TO TRUE
         *       SET FLG-STATE-NOT-OK TO TRUE
         *       ... ': is not a valid state code' ...
         * </pre>
         * The sender is {@code PIC X(02)} (line 807) and the receiver {@code PIC X(2)} (copybook line
         * 1012), so in the running program this is a same-width copy and the {@code MOVE} changes
         * nothing. That is worth pinning down, because it is the reason this edit has no shaping
         * surprises while the phone edit above has several.
         */
        @Test
        @DisplayName("the state edit is a same-width move, so the value reaches the test unchanged")
        void theStateEditIsASameWidthMove() {
            FixedWidthCodec codec = new FixedWidthCodec(StandardCharsets.US_ASCII);
            for (String stateCode : List.of("CA", "NY", "VI", "ZZ")) {
                Assertions.assertThat(codec.movePicX(stateCode, AreaCodeLookup.US_STATE_CODE_LENGTH))
                        .as("MOVE %s TO US-STATE-CODE-TO-EDIT", stateCode)
                        .isEqualTo(stateCode);
            }
            Assertions.assertThat(lookup.isValidUsStateCode("CA")).isTrue();
            Assertions.assertThat(lookup.isValidUsStateCode("ZZ")).isFalse();
        }

        /**
         * {@code 1280-EDIT-US-STATE-ZIP-CD} at {@code app/cbl/COACTUPC.cbl:2536}, under the source's
         * own comment "A crude zip code edit based on data from USPS web site":
         * <pre>
         *   STRING ACUP-NEW-CUST-ADDR-STATE-CD
         *          ACUP-NEW-CUST-ADDR-ZIP(1:2)
         *     DELIMITED BY SIZE
         *     INTO US-STATE-AND-FIRST-ZIP2
         * </pre>
         * State code first, ZIP prefix second. A failure there sets <em>both</em>
         * {@code FLG-STATE-NOT-OK} and {@code FLG-ZIPCODE-NOT-OK} (lines 2546-2547), so the same
         * screen flag can be raised by two different causes - this edit and the state edit above.
         */
        @ParameterizedTest(name = "[{index}] STRING \"{0}\" \"{1}\"(1:2) -> \"{2}\"")
        @CsvSource({
            "CA, 90210, CA90",
            "NY, 10001, NY10",
            "WY, 82001, WY82",
            "TX, 75001, TX75",
            "AA, 34000, AA34",
        })
        @DisplayName("the composer puts the state code first and the ZIP prefix second")
        void theComposerPutsTheStateCodeFirst(String stateCode, String zipCode, String expected) {
            Assertions.assertThat(lookup.composeStateAndFirstZip2(stateCode, zipCode))
                    .isEqualTo(expected)
                    .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
            Assertions.assertThat(lookup.isValidStateAndZipCombination(stateCode, zipCode)).isTrue();
        }

        /**
         * The operand order is observable, which is the reason to compose through the class rather
         * than by hand: {@code "AA34"} is a member and {@code "34AA"} is not, and neither looks more
         * plausible than the other at a call site.
         */
        @Test
        @DisplayName("the two operands are not interchangeable")
        void theOrderOfTheTwoOperandsIsObservable() {
            Assertions.assertThat(lookup.isValidStateZip2Combo("AA34")).isTrue();
            Assertions.assertThat(lookup.isValidStateZip2Combo("34AA")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("CA90")).isTrue();
            Assertions.assertThat(lookup.isValidStateZip2Combo("90CA")).isFalse();
        }

        /**
         * {@code DELIMITED BY SIZE} contributes each operand's <em>full declared width</em>, padding
         * included and nothing trimmed. A trimmed operand would shorten the result and shift every
         * character after it, so a one-character state code contributes {@code "C "} and the ZIP
         * prefix still lands in positions 3 and 4.
         */
        @ParameterizedTest(name = "[{index}] STRING \"{0}\" \"{1}\" -> \"{2}\"")
        @CsvSource(quoteCharacter = '\'', value = {
            "'C',   '90210',      'C 90'",
            "'CA',  '9',          'CA9 '",
            "'CA',  '',           'CA  '",
            "'',    '',           '    '",
            "'CAX', '90210',      'CA90'",
            "'CA',  '9021012345', 'CA90'",
        })
        @DisplayName("each operand contributes its full declared width, padded or truncated")
        void delimitedBySizeContributesFullDeclaredWidths(String stateCode, String zipCode,
                                                         String expected) {
            Assertions.assertThat(lookup.composeStateAndFirstZip2(stateCode, zipCode))
                    .isEqualTo(expected)
                    .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
        }

        /**
         * The reference modification {@code (1:2)} takes the first two ZIP characters and nothing
         * else, so the rest of the ten-character ZIP cannot affect the edit. Three different ZIPs
         * sharing a prefix compose the same probe.
         */
        @Test
        @DisplayName("only the first two ZIP characters reach the probe, per the (1:2) reference")
        void theZipEditUsesOnlyTheFirstTwoZipCharacters() {
            Assertions.assertThat(lookup.composeStateAndFirstZip2("CA", "90210"))
                    .isEqualTo(lookup.composeStateAndFirstZip2("CA", "90999"))
                    .isEqualTo(lookup.composeStateAndFirstZip2("CA", "90"))
                    .isEqualTo("CA90");
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "90210")).isTrue();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "90999")).isTrue();

            // And a valid state with a prefix that is not its own still fails: the pair is the rule,
            // not the state alone.
            Assertions.assertThat(lookup.isValidUsStateCode("CA")).isTrue();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "10001")).isFalse();
        }

        /**
         * {@code 01 US-STATE-ZIPCODE-TO-EDIT} is 4 + 3 = 7 characters: the probe at copybook line 1072
         * and {@code LAST-3-OF-ZIP} at line 1314. The two subordinates tile the group with no gap and
         * no overlap.
         */
        @Test
        @DisplayName("the two subordinates tile the seven-character group exactly")
        void subordinatesTileTheGroup() {
            Assertions.assertThat(AreaCodeLookup.STATE_AND_FIRST_ZIP2_OFFSET).isZero();
            Assertions.assertThat(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH).isEqualTo(4);
            Assertions.assertThat(AreaCodeLookup.LAST_3_OF_ZIP_OFFSET)
                    .as("LAST-3-OF-ZIP starts where US-STATE-AND-FIRST-ZIP2 ends")
                    .isEqualTo(AreaCodeLookup.STATE_AND_FIRST_ZIP2_OFFSET
                            + AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
            Assertions.assertThat(AreaCodeLookup.LAST_3_OF_ZIP_LENGTH).isEqualTo(3);
            Assertions.assertThat(AreaCodeLookup.LAST_3_OF_ZIP_OFFSET
                            + AreaCodeLookup.LAST_3_OF_ZIP_LENGTH)
                    .as("the subordinates fill the group")
                    .isEqualTo(AreaCodeLookup.STATE_ZIPCODE_GROUP_LENGTH)
                    .isEqualTo(7);
            Assertions.assertThat(AreaCodeLookup.CUSTOMER_ZIP_LENGTH)
                    .as("ACUP-NEW-CUST-ADDR-ZIP PIC X(10), COACTUPC:809")
                    .isEqualTo(10);
            Assertions.assertThat(AreaCodeLookup.ZIP_PREFIX_LENGTH)
                    .as("the (1:2) reference modification")
                    .isEqualTo(2);
        }

        /**
         * After {@code 1280-EDIT-US-STATE-ZIP-CD} the group holds the probe and then three spaces.
         * {@code LAST-3-OF-ZIP} is referenced by no statement anywhere in {@code COACTUPC}, and the
         * group is {@code WORKING-STORAGE} with no {@code VALUE} clause, so it is never written and
         * stands blank.
         */
        @Test
        @DisplayName("the group image leaves LAST-3-OF-ZIP blank, as COACTUPC leaves it")
        void theGroupImageLeavesLastThreeOfZipBlank() {
            String image = lookup.stateZipcodeGroupImage("CA", "90210");
            Assertions.assertThat(image)
                    .isEqualTo("CA90   ")
                    .hasSize(AreaCodeLookup.STATE_ZIPCODE_GROUP_LENGTH);
            Assertions.assertThat(lookup.stateAndFirstZip2(image)).isEqualTo("CA90");
            Assertions.assertThat(lookup.lastThreeOfZip(image)).isEqualTo("   ");
        }

        /**
         * Both subordinates are read at their declared offsets whatever the width of the image handed
         * in: a short image is padded to the group's seven characters first, and a long one is
         * truncated to them, so neither can run off the end.
         */
        @ParameterizedTest(name = "[{index}] group \"{0}\" -> zip2 \"{1}\", last3 \"{2}\"")
        @CsvSource(quoteCharacter = '\'', value = {
            "'WY82XYZ',   'WY82', 'XYZ'",
            "'CA90210',   'CA90', '210'",
            "'AA34   ',   'AA34', '   '",
            "'AA34',      'AA34', '   '",
            "'A',         'A   ', '   '",
            "'',          '    ', '   '",
            "'WY82XYZ99', 'WY82', 'XYZ'",
        })
        @DisplayName("both subordinates are read at their declared offsets, whatever the image width")
        void subordinatesAreReadAtTheirDeclaredOffsets(String image, String zip2, String last3) {
            Assertions.assertThat(lookup.stateAndFirstZip2(image))
                    .isEqualTo(zip2)
                    .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
            Assertions.assertThat(lookup.lastThreeOfZip(image))
                    .isEqualTo(last3)
                    .hasSize(AreaCodeLookup.LAST_3_OF_ZIP_LENGTH);
        }

        /**
         * Only the first four characters of the group participate in
         * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}. Varying {@code LAST-3-OF-ZIP} therefore cannot
         * change the outcome - the condition name is declared under the {@code 02} subordinate at
         * copybook line 1072, not under the {@code 01} group.
         */
        @ParameterizedTest(name = "[{index}] last three = \"{0}\" leaves membership untouched")
        @CsvSource(quoteCharacter = '\'', value = {"'XYZ'", "'000'", "'999'", "'   '", "'2 1'"})
        @DisplayName("LAST-3-OF-ZIP never affects membership")
        void theLastThreeOfZipNeverAffectsMembership(String lastThree) {
            String member = lookup.stateAndFirstZip2("WY82" + lastThree);
            Assertions.assertThat(member).isEqualTo("WY82");
            Assertions.assertThat(lookup.isValidStateZip2Combo(member))
                    .as("\"WY82\" is a member whatever follows it")
                    .isTrue();

            String notAMember = lookup.stateAndFirstZip2("CA00" + lastThree);
            Assertions.assertThat(notAMember).isEqualTo("CA00");
            Assertions.assertThat(lookup.isValidStateZip2Combo(notAMember))
                    .as("\"CA00\" is not a member whatever follows it")
                    .isFalse();
        }

        /** The probe read back out of a group image is the probe the predicate accepts. */
        @Test
        @DisplayName("the probe read out of a group image round-trips into the predicate")
        void theProbeReadFromAGroupImageRoundTrips() {
            String image = lookup.stateZipcodeGroupImage("WY", "82001");
            Assertions.assertThat(lookup.stateAndFirstZip2(image)).isEqualTo("WY82");
            Assertions.assertThat(lookup.isValidStateZip2Combo(lookup.stateAndFirstZip2(image)))
                    .isTrue();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("WY", "82001")).isTrue();
        }
    }

    // =================================================================================================

    /**
     * Every {@code 88}-level condition name of {@code app/cpy/CSLKPCDY.cpy} driven true <em>and</em>
     * false. Each case gives the first declared literal, the last, at least two interior ones, and at
     * least three correct-width values that the copybook does not declare.
     *
     * <p><strong>Two of the five have no consumer, and are covered anyway.</strong>
     * {@code app/cbl/COACTUPC.cbl} is the copybook's only consumer in the whole repository - one
     * {@code COPY CSLKPCDY.} at line 602 - and it references the five condition names this many times:
     *
     * <table border="1">
     *   <caption>Reference counts in app/cbl/COACTUPC.cbl</caption>
     *   <tr><th>Condition name</th><th>References</th><th>Site</th></tr>
     *   <tr><td>{@code VALID-PHONE-AREA-CODE}</td><td><strong>0</strong></td><td>none</td></tr>
     *   <tr><td>{@code VALID-GENERAL-PURP-CODE}</td><td>1</td><td>line 2298</td></tr>
     *   <tr><td>{@code VALID-EASY-RECOG-AREA-CODE}</td><td><strong>0</strong></td><td>none</td></tr>
     *   <tr><td>{@code VALID-US-STATE-CODE}</td><td>1</td><td>line 2495</td></tr>
     *   <tr><td>{@code VALID-US-STATE-ZIP-CD2-COMBO}</td><td>1</td><td>line 2542</td></tr>
     * </table>
     *
     * <p>So {@code VALID-PHONE-AREA-CODE} (490 literals) and {@code VALID-EASY-RECOG-AREA-CODE} (80
     * literals) are dead condition names: nothing in this codebase tests either of them. They are
     * still modelled and still driven from both sides, because the copybook is the contract and
     * dropping a declaration would be a change to it rather than a translation of it. They are not
     * inert, either - the partition they form is what makes the phone edit's rejections explicable,
     * which is exactly why the wider list must not be mistaken for the one the program uses.
     *
     * <p>{@code null} is covered by {@link Contracts}, where it is a thrown
     * {@link NullPointerException} rather than a {@code false}; the all-spaces probe is covered by
     * {@link MoveSemantics#blankProbeIsAMemberOfNoClause()}.
     */
    @Nested
    @DisplayName("Predicates - all five 88-levels driven true and false (the both-states gate)")
    class Predicates {

        /**
         * {@code 88 VALID-PHONE-AREA-CODE}, {@code app/cpy/CSLKPCDY.cpy:30-520}. First literal
         * {@code '201'} (line 30), last {@code '999'} (line 520). A dead condition name - zero
         * references in {@code COACTUPC} - covered from both sides regardless.
         */
        @ParameterizedTest(name = "[{index}] VALID-PHONE-AREA-CODE(\"{0}\") == {1}")
        @CsvSource(quoteCharacter = '\'', value = {
            "'201', true", "'999', true", "'202', true", "'212', true", "'907', true",
            "'989', true", "'200', true", "'211', true", "'911', true", "'800', true",
            "'221', false", "'199', false", "'998', false", "'990', false", "'950', false",
            "'100', false", "'000', false", "'111', false", "'ZZZ', false", "'abc', false",
            "'2 1', false",
        })
        @DisplayName("VALID-PHONE-AREA-CODE - the full NANP list, 490 literals")
        void validPhoneAreaCode(String probe, boolean expected) {
            Assertions.assertThat(lookup.isValidPhoneAreaCode(probe)).isEqualTo(expected);
        }

        /**
         * {@code 88 VALID-GENERAL-PURP-CODE}, {@code app/cpy/CSLKPCDY.cpy:521-930}. First literal
         * {@code '201'} (line 521), last {@code '989'} (line 930). This is the one condition
         * {@code app/cbl/COACTUPC.cbl:2298} tests, so the false cases here are the inputs that make
         * the program emit "Not valid North America general purpose area code".
         */
        @ParameterizedTest(name = "[{index}] VALID-GENERAL-PURP-CODE(\"{0}\") == {1}")
        @CsvSource(quoteCharacter = '\'', value = {
            "'201', true", "'989', true", "'202', true", "'212', true", "'504', true",
            "'907', true",
            "'200', false", "'211', false", "'911', false", "'800', false", "'999', false",
            "'221', false", "'199', false", "'000', false", "'ZZZ', false", "'20 ', false",
        })
        @DisplayName("VALID-GENERAL-PURP-CODE - the condition COACTUPC:2298 tests, 410 literals")
        void validGeneralPurposeCode(String probe, boolean expected) {
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(probe)).isEqualTo(expected);
        }

        /**
         * {@code 88 VALID-EASY-RECOG-AREA-CODE}, {@code app/cpy/CSLKPCDY.cpy:931-1010}. First literal
         * {@code '200'} (line 931), last {@code '999'} (line 1010). The second dead condition name -
         * zero references in {@code COACTUPC} - covered from both sides regardless.
         */
        @ParameterizedTest(name = "[{index}] VALID-EASY-RECOG-AREA-CODE(\"{0}\") == {1}")
        @CsvSource(quoteCharacter = '\'', value = {
            "'200', true", "'999', true", "'211', true", "'222', true", "'800', true",
            "'888', true", "'911', true",
            "'201', false", "'202', false", "'212', false", "'989', false", "'221', false",
            "'199', false", "'ZZZ', false",
        })
        @DisplayName("VALID-EASY-RECOG-AREA-CODE - the service-style codes, 80 literals")
        void validEasyRecognitionAreaCode(String probe, boolean expected) {
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode(probe)).isEqualTo(expected);
        }

        /**
         * {@code 88 VALID-US-STATE-CODE}, {@code app/cpy/CSLKPCDY.cpy:1013-1069}. First literal
         * {@code 'AL'} (line 1014), last {@code 'VI'} (line 1069). The condition
         * {@code app/cbl/COACTUPC.cbl:2495} tests.
         *
         * <p>The five territory codes {@code 'AS'}, {@code 'GU'}, {@code 'MP'}, {@code 'PR'} and
         * {@code 'VI'} are named explicitly as true cases so that the list is never "corrected" down
         * to fifty states, and the six codes the ZIP clause knows but this one does not are named
         * explicitly as false cases so that the two lists are never reconciled.
         */
        @ParameterizedTest(name = "[{index}] VALID-US-STATE-CODE(\"{0}\") == {1}")
        @CsvSource(quoteCharacter = '\'', value = {
            "'AL', true", "'VI', true", "'AK', true", "'CA', true", "'NY', true", "'TX', true",
            "'WY', true", "'DC', true", "'AS', true", "'GU', true", "'MP', true", "'PR', true",
            "'AA', false", "'AE', false", "'AP', false", "'FM', false", "'MH', false",
            "'PW', false", "'XX', false", "'ZZ', false", "'ca', false", "'99', false",
        })
        @DisplayName("VALID-US-STATE-CODE - the condition COACTUPC:2495 tests, 56 literals")
        void validUsStateCode(String probe, boolean expected) {
            Assertions.assertThat(lookup.isValidUsStateCode(probe)).isEqualTo(expected);
        }

        /**
         * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, {@code app/cpy/CSLKPCDY.cpy:1073-1313}. First
         * literal {@code 'AA34'} (line 1074), last {@code 'WY83'} (line 1313). The condition
         * {@code app/cbl/COACTUPC.cbl:2542} tests, against an already composed four-character probe.
         *
         * <p>{@code 'WY82'} and {@code 'WY83'} are members while {@code 'WY84'} is not, and
         * {@code 'CA90'} is while {@code 'CA00'} is not: the rule is a declared pair, not a range.
         */
        @ParameterizedTest(name = "[{index}] VALID-US-STATE-ZIP-CD2-COMBO(\"{0}\") == {1}")
        @CsvSource(quoteCharacter = '\'', value = {
            "'AA34', true", "'WY83', true", "'AE90', true", "'AL35', true", "'CA90', true",
            "'DC20', true", "'GU96', true", "'NY10', true", "'TX75', true", "'WY82', true",
            "'WY84', false", "'CA00', false", "'AA00', false", "'AL99', false", "'PR00', false",
            "'VI00', false", "'ZZ99', false", "'34AA', false", "'wy83', false",
        })
        @DisplayName("VALID-US-STATE-ZIP-CD2-COMBO - the condition COACTUPC:2542 tests, 240 literals")
        void validStateZip2Combo(String probe, boolean expected) {
            Assertions.assertThat(lookup.isValidStateZip2Combo(probe)).isEqualTo(expected);
        }

        /**
         * The whole of {@code 1280-EDIT-US-STATE-ZIP-CD}: compose the probe from a customer's state
         * code and ZIP, then test the condition name. Both outcomes, from the pair of values the
         * program actually holds rather than from a hand-built probe.
         */
        @ParameterizedTest(name = "[{index}] 1280-EDIT-US-STATE-ZIP-CD(\"{0}\", \"{1}\") == {2}")
        @CsvSource(quoteCharacter = '\'', value = {
            "'CA', '90210', true",
            "'NY', '10001', true",
            "'WY', '82001', true",
            "'TX', '75001', true",
            "'AL', '35004', true",
            "'AA', '34000', true",
            "'CA', '10001', false",
            "'NY', '90210', false",
            "'WY', '84001', false",
            "'ZZ', '99999', false",
            "'CA', '00000', false",
            "'ca', '90210', false",
        })
        @DisplayName("the composed state-and-ZIP edit, both outcomes")
        void theComposedStateAndZipEdit(String stateCode, String zipCode, boolean expected) {
            Assertions.assertThat(lookup.isValidStateAndZipCombination(stateCode, zipCode))
                    .isEqualTo(expected);
        }

        /**
         * The three area-code clauses are three different rules over one field, and this asserts the
         * three-way distinction directly: a general purpose code, an easily recognisable code, and a
         * code that is neither.
         */
        @Test
        @DisplayName("the three area-code clauses answer differently for the same probe")
        void theThreeAreaCodeClausesAreDistinguished() {
            // '201' - a general purpose code (CSLKPCDY.cpy:30 and :521).
            Assertions.assertThat(lookup.isValidPhoneAreaCode("201")).isTrue();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("201")).isTrue();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("201")).isFalse();

            // '200' - an easily recognisable code (CSLKPCDY.cpy:441 and :931).
            Assertions.assertThat(lookup.isValidPhoneAreaCode("200")).isTrue();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("200")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("200")).isTrue();

            // '221' - declared nowhere in the copybook, so a member of nothing.
            Assertions.assertThat(lookup.isValidPhoneAreaCode("221")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("221")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("221")).isFalse();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Contracts - nulls, immutability, wiring and the transcription guard")
    class Contracts {

        /**
         * COBOL has no null: an unset alphanumeric item holds spaces. So {@code null} is a programming
         * error rather than a blank probe, and every predicate says so instead of quietly answering
         * {@code false} - and says it while naming the copybook item the value was destined for, which
         * is what turns the stack trace into a diagnosis.
         */
        @Test
        @DisplayName("every predicate rejects null rather than treating it as blank")
        void everyPredicateRejectsNull() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidPhoneAreaCode(null))
                    .withMessageContaining("WS-US-PHONE-AREA-CODE-TO-EDIT");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidGeneralPurposeCode(null))
                    .withMessageContaining("WS-US-PHONE-AREA-CODE-TO-EDIT");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidEasyRecognitionAreaCode(null))
                    .withMessageContaining("WS-US-PHONE-AREA-CODE-TO-EDIT");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidUsStateCode(null))
                    .withMessageContaining("US-STATE-CODE-TO-EDIT");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidStateZip2Combo(null))
                    .withMessageContaining("US-STATE-AND-FIRST-ZIP2");
        }

        /**
         * The composing and group-reading helpers reject {@code null} in either position, naming the
         * {@code COACTUPC} field the argument stands for rather than the Java parameter.
         */
        @Test
        @DisplayName("the group helpers reject null in either position")
        void theGroupHelpersRejectNull() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.composeStateAndFirstZip2(null, "90210"))
                    .withMessageContaining("ACUP-NEW-CUST-ADDR-STATE-CD");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.composeStateAndFirstZip2("CA", null))
                    .withMessageContaining("ACUP-NEW-CUST-ADDR-ZIP");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidStateAndZipCombination(null, "90210"));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidStateAndZipCombination("CA", null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.stateZipcodeGroupImage(null, "90210"));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.stateZipcodeGroupImage("CA", null));
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.stateAndFirstZip2(null))
                    .withMessageContaining("US-STATE-ZIPCODE-TO-EDIT");
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> lookup.lastThreeOfZip(null))
                    .withMessageContaining("US-STATE-ZIPCODE-TO-EDIT");
        }

        @Test
        @DisplayName("the constructor rejects a null codec")
        void theConstructorRejectsANullCodec() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() -> new AreaCodeLookup(null))
                    .withMessageContaining("FixedWidthCodec");
        }

        /**
         * The tables are unmodifiable, not merely private. They live in static fields, and a mutable
         * collection in a static field is mutable static state however it is declared - one caller
         * adding an area code would change the validation rule for every caller in the process.
         */
        @Test
        @DisplayName("every table is unmodifiable")
        void everyTableIsUnmodifiable() {
            List<Set<String>> tables = List.of(
                    lookup.validPhoneAreaCodes(),
                    lookup.validGeneralPurposeCodes(),
                    lookup.validEasyRecognitionAreaCodes(),
                    lookup.validUsStateCodes(),
                    lookup.validStateZip2Combos());
            Assertions.assertThat(tables).hasSize(5);
            for (Set<String> table : tables) {
                Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(() -> table.add("999"));
                Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(() -> table.remove("201"));
                Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(table::clear);
            }
        }

        /**
         * A copybook is compiled into every program that copies it, but there is one copybook - so
         * there is one table, shared, and not a private copy per instance. Eleven programs copy
         * {@code CSLKPCDY}'s neighbours; duplicating 1,276 literals per injection point would be both
         * wasteful and a place for two copies to disagree.
         */
        @Test
        @DisplayName("the tables are shared between instances, not copied per instance")
        void theTablesAreSharedBetweenInstances() {
            AreaCodeLookup other = new AreaCodeLookup(
                    new FixedWidthCodec(StandardCharsets.US_ASCII));
            Assertions.assertThat(other.validPhoneAreaCodes())
                    .isSameAs(lookup.validPhoneAreaCodes());
            Assertions.assertThat(other.validGeneralPurposeCodes())
                    .isSameAs(lookup.validGeneralPurposeCodes());
            Assertions.assertThat(other.validEasyRecognitionAreaCodes())
                    .isSameAs(lookup.validEasyRecognitionAreaCodes());
            Assertions.assertThat(other.validUsStateCodes()).isSameAs(lookup.validUsStateCodes());
            Assertions.assertThat(other.validStateZip2Combos())
                    .isSameAs(lookup.validStateZip2Combos());
        }

        /**
         * Both constructors must answer identically, whatever charset the injected codec carries. The
         * charset is immaterial here and only here: the {@code PIC X} width rule is pure character
         * work, and this class reads no dataset bytes at all - it holds literals and compares strings.
         */
        @Test
        @DisplayName("both constructors agree, whatever charset the codec carries")
        void bothConstructorsAgree() {
            AreaCodeLookup injected = new AreaCodeLookup(
                    new FixedWidthCodec(StandardCharsets.ISO_8859_1));
            Assertions.assertThat(injected.isValidGeneralPurposeCode("201"))
                    .isEqualTo(lookup.isValidGeneralPurposeCode("201"))
                    .isTrue();
            Assertions.assertThat(injected.isValidGeneralPurposeCode("221"))
                    .isEqualTo(lookup.isValidGeneralPurposeCode("221"))
                    .isFalse();
            Assertions.assertThat(injected.isValidUsStateCode("VI"))
                    .isEqualTo(lookup.isValidUsStateCode("VI"))
                    .isTrue();
            Assertions.assertThat(injected.composeStateAndFirstZip2("CA", "90210"))
                    .isEqualTo(lookup.composeStateAndFirstZip2("CA", "90210"));
            Assertions.assertThat(injected.stateZipcodeGroupImage("CA", "90210"))
                    .isEqualTo(lookup.stateZipcodeGroupImage("CA", "90210"));
            Assertions.assertThat(injected.lastThreeOfZip("WY82XYZ"))
                    .isEqualTo(lookup.lastThreeOfZip("WY82XYZ"));
        }

        /**
         * An injectable, stateless component: the stereotype is present so component scanning finds
         * it, the class is final, every field is final, and a no-argument constructor exists because
         * {@link FixedWidthCodec} is not itself a bean.
         *
         * <p>{@code WORKING-STORAGE} must never become mutable static Java state, and this is the
         * assertion that keeps that true of the class under test rather than merely intended.
         */
        @Test
        @DisplayName("the class is an injectable @Component with no mutable state")
        void theClassIsAnInjectableComponentWithNoMutableState() {
            Assertions.assertThat(AreaCodeLookup.class.getAnnotation(Component.class))
                    .as("component scanning has to be able to find it")
                    .isNotNull();
            Assertions.assertThat(Modifier.isFinal(AreaCodeLookup.class.getModifiers()))
                    .as("the class is final")
                    .isTrue();
            Assertions.assertThat(AreaCodeLookup.class.getDeclaredFields())
                    .as("every field is final, and no field is an array a caller could write through")
                    .allSatisfy(field -> {
                        Assertions.assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s is final", field.getName())
                                .isTrue();
                        Assertions.assertThat(field.getType().isArray())
                                .as("%s is not an array", field.getName())
                                .isFalse();
                    });
            Assertions.assertThat(AreaCodeLookup.class.getDeclaredConstructors())
                    .as("a no-argument constructor exists for component scanning")
                    .anySatisfy(constructor -> Assertions.assertThat(constructor.getParameterCount())
                            .isZero());
        }

        /**
         * The transcription guard, from the passing side: a table whose size matches the count its
         * {@code 88}-level declares is returned unchanged.
         */
        @Test
        @DisplayName("the transcription guard passes a correct table through unchanged")
        void theTranscriptionGuardAcceptsACorrectTable() {
            Set<String> table = new LinkedHashSet<>(List.of("201", "202", "203"));
            Assertions.assertThat(
                            AreaCodeLookup.requireExactSize(table, 3, "VALID-PHONE-AREA-CODE"))
                    .isSameAs(table);
        }

        /**
         * The transcription guard from the failing side, which is the only branch in the class that a
         * correctly transcribed table can never reach - and therefore the only one that has to be
         * driven deliberately. A guard nobody has ever seen fire is a guard nobody knows works.
         *
         * <p>Both directions are driven: a literal lost, and a literal gained. A duplicated literal
         * lands in the first case, because the set is built before the count is taken and two copies
         * of one literal collapse to one member.
         */
        @Test
        @DisplayName("the transcription guard fails a table that lost or gained a literal")
        void theTranscriptionGuardRejectsAMiscountedTable() {
            Set<String> tooFew = new LinkedHashSet<>(List.of("201", "202"));
            Assertions.assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            AreaCodeLookup.requireExactSize(tooFew, 3, "VALID-PHONE-AREA-CODE"))
                    .withMessageContaining("88 VALID-PHONE-AREA-CODE holds 2")
                    .withMessageContaining("declares 3")
                    .withMessageContaining(COPYBOOK_PATH);

            Set<String> tooMany = new LinkedHashSet<>(List.of("AL", "AK", "AZ"));
            Assertions.assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            AreaCodeLookup.requireExactSize(tooMany, 2, "VALID-US-STATE-CODE"))
                    .withMessageContaining("88 VALID-US-STATE-CODE holds 3")
                    .withMessageContaining("declares 2");

            // A duplicate is a lost literal: the set collapses it and the count comes up short.
            Set<String> withDuplicate = new LinkedHashSet<>(List.of("201", "202", "201"));
            Assertions.assertThat(withDuplicate).hasSize(2);
            Assertions.assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> AreaCodeLookup.requireExactSize(withDuplicate, 3,
                            "VALID-PHONE-AREA-CODE"));
        }

        @Test
        @DisplayName("the transcription guard rejects a null table")
        void theTranscriptionGuardRejectsANullTable() {
            Assertions.assertThatNullPointerException()
                    .isThrownBy(() ->
                            AreaCodeLookup.requireExactSize(null, 1, "VALID-US-STATE-CODE"))
                    .withMessageContaining("VALID-US-STATE-CODE");
        }
    }
}
