package com.vsergeychik.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Component;

/**
 * Parity tests for {@link AreaCodeLookup}, the translation of {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <p>This copybook is 1,318 lines of pure data: 1,276 literals across five {@code 88}-level
 * condition names. Its translation carries almost no logic, which means the risk it carries is
 * <strong>transcription</strong>, not design. The suite is organised around that fact:
 *
 * <ol>
 *   <li>{@link Provenance} is the gate that matters. It re-reads
 *       {@code app/cpy/CSLKPCDY.cpy} and the generated Java source at test time and proves, literal
 *       by literal, that every one of the 1,276 entries is the string the copybook line named in its
 *       provenance comment actually holds - and that no literal line of the copybook was skipped.
 *       No sampling.</li>
 *   <li>{@link Cardinality} and {@link Algebra} assert the declared counts 490, 410, 80, 56 and 240
 *       and the relationships between the three area-code tables.</li>
 *   <li>{@link MoveSemantics} drives the three places a naive {@code Set.contains} would diverge
 *       from COBOL: right truncation, right padding, and case sensitivity.</li>
 *   <li>{@link Predicates} drives all five condition names from <em>both</em> sides, which is what
 *       the branch-coverage gate is really asking for.</li>
 *   <li>{@link GroupLayout} asserts the {@code 01 US-STATE-ZIPCODE-TO-EDIT} group, including the
 *       {@code LAST-3-OF-ZIP} span that no COBOL statement ever touches.</li>
 *   <li>{@link Contracts} covers the null contracts, the immutability of the tables, the equivalence
 *       of the two constructors, and both sides of the transcription guard.</li>
 * </ol>
 */
@DisplayName("AreaCodeLookup - CSLKPCDY lookup-code repository")
class AreaCodeLookupTest {

    /** The copybook this class translates: the only source of truth for every literal. */
    private static final String COPYBOOK_PATH = "app/cpy/CSLKPCDY.cpy";

    /** The generated source, re-parsed by {@link Provenance} rather than trusted. */
    private static final String SOURCE_PATH =
            "app/java/src/main/java/com/vsergeychik/carddemo/account/AreaCodeLookup.java";

    /**
     * A literal in the copybook. The copybook uses COBOL alphanumeric literals, single-quoted, one
     * per continuation line.
     */
    private static final Pattern COPYBOOK_LITERAL = Pattern.compile("'([^']*)'");

    /**
     * One table entry in the generated Java: sixteen spaces of indentation, the literal, the comma
     * or closing parenthesis, then the provenance comment naming the copybook line.
     */
    private static final Pattern JAVA_TABLE_ENTRY =
            Pattern.compile("^ {16}\"([^\"]*)\"(?:,|\\);) // L(\\d+)$");

    /** The {@code orderedSet} call that opens each table, naming the {@code 88}-level it holds. */
    private static final Pattern JAVA_TABLE_HEADER =
            Pattern.compile("^ {8}return orderedSet\\(\"([A-Z0-9-]+)\", \\w+,$");

    /** Closes a table so a stray literal after one cannot be silently attributed to it. */
    private static final Pattern JAVA_TABLE_END = Pattern.compile("^ {4}}$");

    /**
     * The inclusive copybook line range of each {@code 88}-level's {@code VALUES} clause, and the
     * number of literals it declares. Verified against {@code app/cpy/CSLKPCDY.cpy} directly.
     */
    private static final Map<String, int[]> DECLARED = declaredRanges();

    private static List<String> copybook;

    private static List<String> generatedSource;

    private final AreaCodeLookup lookup = new AreaCodeLookup();

    private static Map<String, int[]> declaredRanges() {
        Map<String, int[]> declared = new LinkedHashMap<>();
        // { first line, last line, declared literal count }
        declared.put("VALID-PHONE-AREA-CODE", new int[] {30, 520, 490});
        declared.put("VALID-GENERAL-PURP-CODE", new int[] {521, 930, 410});
        declared.put("VALID-EASY-RECOG-AREA-CODE", new int[] {931, 1010, 80});
        declared.put("VALID-US-STATE-CODE", new int[] {1013, 1069, 56});
        declared.put("VALID-US-STATE-ZIP-CD2-COMBO", new int[] {1073, 1313, 240});
        return declared;
    }

    @BeforeAll
    static void readSources() {
        Path root = repositoryRoot();
        copybook = readLines(root.resolve(COPYBOOK_PATH));
        generatedSource = readLines(root.resolve(SOURCE_PATH));
    }

    /**
     * Locates the repository root by walking up from the working directory until the copybook is
     * found. Surefire runs with the module directory as its working directory, so the walk is two
     * levels; resolving it rather than hard-coding {@code ../..} keeps the suite runnable from the
     * repository root and from an IDE as well.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(COPYBOOK_PATH))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate " + COPYBOOK_PATH + " above "
                + Path.of("").toAbsolutePath() + ". It is the parity oracle for AreaCodeLookup and "
                + "is read-only, so it must be present: these tests prove the transcription against "
                + "it rather than against a copy.");
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot read " + path, cause);
        }
    }

    /** True for a copybook line that carries no declaration: blank, or a comment. */
    private static boolean skipped(String line) {
        String trimmed = line.stripLeading();
        return line.isBlank() || trimmed.startsWith("*");
    }

    /** The single literal a copybook line holds, or {@code null} when it holds none. */
    private static String copybookLiteral(String line) {
        Matcher matcher = COPYBOOK_LITERAL.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String literal = matcher.group(1);
        return matcher.find() ? null : literal;
    }

    // =================================================================================================

    @Nested
    @DisplayName("Provenance - every literal traced back to app/cpy/CSLKPCDY.cpy")
    class Provenance {

        /**
         * The gate. For each of the 1,276 table entries in the generated Java, go to the copybook
         * line its comment names and confirm the literal there is the same string.
         *
         * <p>The direction matters. This walks Java to copybook, the opposite of the way the tables
         * were produced, so a mistake in the generator's line bookkeeping cannot hide behind the
         * same mistake being made twice.
         */
        @Test
        @DisplayName("all 1,276 entries match the copybook line their comment names")
        void everyLiteralMatchesItsCopybookLine() {
            List<String> failures = new ArrayList<>();
            int entries = 0;
            for (int index = 0; index < generatedSource.size(); index++) {
                Matcher matcher = JAVA_TABLE_ENTRY.matcher(generatedSource.get(index));
                if (!matcher.matches()) {
                    continue;
                }
                entries++;
                String literal = matcher.group(1);
                int copybookLine = Integer.parseInt(matcher.group(2));
                String actual = copybookLiteral(copybook.get(copybookLine - 1));
                if (!literal.equals(actual)) {
                    failures.add(SOURCE_PATH + ":" + (index + 1) + " holds \"" + literal
                            + "\" citing " + COPYBOOK_PATH + ":" + copybookLine
                            + ", which actually holds " + (actual == null ? "no single literal"
                            : "'" + actual + "'"));
                }
            }
            assertThat(entries)
                    .as("table entries found in the generated source")
                    .isEqualTo(1276);
            assertThat(failures).as("literals that disagree with the copybook").isEmpty();
        }

        /**
         * The complement of the check above: no literal line of any {@code VALUES} clause was
         * skipped. Together the two make the transcription exhaustive in both directions.
         *
         * <p>The comment at {@code app/cpy/CSLKPCDY.cpy:440}, "Easily recognizable codes begin
         * here.", sits <em>inside</em> the {@code VALID-PHONE-AREA-CODE} clause. It is the reason
         * lines 30 to 520 span 491 physical lines and yield 490 literals, and the reason this test
         * exists as well as its counterpart.
         */
        @Test
        @DisplayName("no literal line of any VALUES clause was skipped")
        void everyCopybookLiteralLineIsRepresented() {
            Map<String, Set<Integer>> cited = citedLinesByTable();
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, int[]> declaration : DECLARED.entrySet()) {
                String table = declaration.getKey();
                int[] range = declaration.getValue();
                Set<Integer> lines = cited.getOrDefault(table, Set.of());
                for (int line = range[0]; line <= range[1]; line++) {
                    String raw = copybook.get(line - 1);
                    if (skipped(raw) || copybookLiteral(raw) == null) {
                        continue;
                    }
                    if (!lines.contains(line)) {
                        failures.add("88 " + table + " omits " + COPYBOOK_PATH + ":" + line
                                + " ('" + copybookLiteral(raw) + "')");
                    }
                }
            }
            assertThat(failures).as("copybook literals missing from the Java tables").isEmpty();
        }

        /**
         * The tables are in the copybook's declaration order, and each stays inside its own clause.
         * That is what lets a reviewer diff this file against the copybook line by line, and it is
         * also what proves no entry was copied from the wrong clause.
         */
        @Test
        @DisplayName("each table is in strictly increasing copybook order, within its own clause")
        void tablesFollowCopybookDeclarationOrder() {
            Map<String, List<Integer>> cited = new LinkedHashMap<>();
            citedLinesByTableOrdered(cited);
            assertThat(cited.keySet()).containsExactlyElementsOf(DECLARED.keySet());
            for (Map.Entry<String, List<Integer>> entry : cited.entrySet()) {
                int[] range = DECLARED.get(entry.getKey());
                List<Integer> lines = entry.getValue();
                assertThat(lines).as("%s entry count", entry.getKey()).hasSize(range[2]);
                assertThat(lines).as("%s is strictly increasing", entry.getKey()).isSorted();
                assertThat(new LinkedHashSet<>(lines))
                        .as("%s cites no copybook line twice", entry.getKey())
                        .hasSize(range[2]);
                assertThat(lines).as("%s stays inside CSLKPCDY.cpy:%d-%d", entry.getKey(),
                        range[0], range[1]).allSatisfy(line ->
                        assertThat(line).isBetween(range[0], range[1]));
            }
        }

        /** The runtime tables hold exactly the literals the generated source declares. */
        @Test
        @DisplayName("the runtime tables hold exactly what the source declares")
        void runtimeTablesMatchTheSource() {
            Map<String, List<String>> declared = new LinkedHashMap<>();
            String table = null;
            for (String line : generatedSource) {
                Matcher header = JAVA_TABLE_HEADER.matcher(line);
                if (header.matches()) {
                    table = header.group(1);
                    declared.put(table, new ArrayList<>());
                    continue;
                }
                if (JAVA_TABLE_END.matcher(line).matches()) {
                    table = null;
                    continue;
                }
                Matcher entry = JAVA_TABLE_ENTRY.matcher(line);
                if (entry.matches() && table != null) {
                    declared.get(table).add(entry.group(1));
                }
            }
            assertThat(lookup.validPhoneAreaCodes())
                    .containsExactlyElementsOf(declared.get("VALID-PHONE-AREA-CODE"));
            assertThat(lookup.validGeneralPurposeCodes())
                    .containsExactlyElementsOf(declared.get("VALID-GENERAL-PURP-CODE"));
            assertThat(lookup.validEasyRecognitionAreaCodes())
                    .containsExactlyElementsOf(declared.get("VALID-EASY-RECOG-AREA-CODE"));
            assertThat(lookup.validUsStateCodes())
                    .containsExactlyElementsOf(declared.get("VALID-US-STATE-CODE"));
            assertThat(lookup.validStateZip2Combos())
                    .containsExactlyElementsOf(declared.get("VALID-US-STATE-ZIP-CD2-COMBO"));
        }

        /** No literal may be compressed away: the source must state all 1,276 explicitly. */
        @Test
        @DisplayName("no table is computed - the source contains no range, regex or loop over codes")
        void tablesAreWrittenOutRatherThanComputed() {
            String source = String.join("\n", generatedSource);
            assertThat(source)
                    .as("a regular expression would approximate a membership set")
                    .doesNotContain("Pattern.compile")
                    .doesNotContain("matches(")
                    .as("case folding would accept input the COBOL rejects")
                    .doesNotContain("toUpperCase")
                    .doesNotContain("toLowerCase")
                    .as("trimming would change the probe width")
                    .doesNotContain(".trim()")
                    .as("a wildcard import would hide which types this file depends on")
                    .doesNotContain(".*;");
        }

        private Map<String, Set<Integer>> citedLinesByTable() {
            Map<String, List<Integer>> ordered = new LinkedHashMap<>();
            citedLinesByTableOrdered(ordered);
            Map<String, Set<Integer>> cited = new LinkedHashMap<>();
            ordered.forEach((table, lines) -> cited.put(table, new TreeSet<>(lines)));
            return cited;
        }

        private void citedLinesByTableOrdered(Map<String, List<Integer>> cited) {
            String table = null;
            for (String line : generatedSource) {
                Matcher header = JAVA_TABLE_HEADER.matcher(line);
                if (header.matches()) {
                    table = header.group(1);
                    cited.put(table, new ArrayList<>());
                    continue;
                }
                if (JAVA_TABLE_END.matcher(line).matches()) {
                    table = null;
                    continue;
                }
                Matcher entry = JAVA_TABLE_ENTRY.matcher(line);
                if (entry.matches() && table != null) {
                    cited.get(table).add(Integer.parseInt(entry.group(2)));
                }
            }
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Cardinality - 490, 410, 80, 56, 240")
    class Cardinality {

        @Test
        @DisplayName("every table holds exactly the number of literals the copybook declares")
        void tableSizesMatchTheDeclaredCounts() {
            assertThat(lookup.validPhoneAreaCodes())
                    .hasSize(AreaCodeLookup.VALID_PHONE_AREA_CODE_COUNT).hasSize(490);
            assertThat(lookup.validGeneralPurposeCodes())
                    .hasSize(AreaCodeLookup.VALID_GENERAL_PURP_CODE_COUNT).hasSize(410);
            assertThat(lookup.validEasyRecognitionAreaCodes())
                    .hasSize(AreaCodeLookup.VALID_EASY_RECOG_AREA_CODE_COUNT).hasSize(80);
            assertThat(lookup.validUsStateCodes())
                    .hasSize(AreaCodeLookup.VALID_US_STATE_CODE_COUNT).hasSize(56);
            assertThat(lookup.validStateZip2Combos())
                    .hasSize(AreaCodeLookup.VALID_US_STATE_ZIP_CD2_COMBO_COUNT).hasSize(240);
        }

        /** Every entry is exactly as wide as the probe it will be compared against. */
        @Test
        @DisplayName("every entry is at its probe's declared PICTURE width")
        void everyEntryIsAtItsDeclaredWidth() {
            assertThat(lookup.validPhoneAreaCodes())
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH));
            assertThat(lookup.validGeneralPurposeCodes())
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH));
            assertThat(lookup.validEasyRecognitionAreaCodes())
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(AreaCodeLookup.PHONE_AREA_CODE_LENGTH));
            assertThat(lookup.validUsStateCodes())
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(AreaCodeLookup.US_STATE_CODE_LENGTH));
            assertThat(lookup.validStateZip2Combos())
                    .allSatisfy(code -> assertThat(code)
                            .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH));
        }

        /** The copybook's first and last literal in each clause, checked by iteration order. */
        @Test
        @DisplayName("first and last entry of each table are the copybook's own")
        void boundaryEntriesAreTheCopybookBoundaries() {
            assertThat(lookup.validPhoneAreaCodes()).startsWith("201").endsWith("999");
            assertThat(lookup.validGeneralPurposeCodes()).startsWith("201").endsWith("989");
            assertThat(lookup.validEasyRecognitionAreaCodes()).startsWith("200").endsWith("999");
            assertThat(lookup.validUsStateCodes()).startsWith("AL")
                    .endsWith("GU", "MP", "PR", "VI");
            assertThat(lookup.validStateZip2Combos()).startsWith("AA34").endsWith("WY83");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Algebra - how the three area-code tables relate")
    class Algebra {

        @Test
        @DisplayName("general purpose and easily recognisable partition the phone area codes")
        void theTwoSubsetsPartitionTheUnion() {
            Set<String> general = lookup.validGeneralPurposeCodes();
            Set<String> easy = lookup.validEasyRecognitionAreaCodes();
            Set<String> union = new LinkedHashSet<>(general);
            union.addAll(easy);

            assertThat(union).as("410 + 80 = 490").hasSize(490);
            assertThat(union).containsExactlyInAnyOrderElementsOf(lookup.validPhoneAreaCodes());
            assertThat(lookup.validPhoneAreaCodes()).containsAll(general).containsAll(easy);
            assertThat(general).as("the two subsets are disjoint").doesNotContainAnyElementsOf(easy);
        }

        /**
         * The 80 easily recognisable codes are the one table that follows a rule, and the rule is
         * asserted here precisely so that nobody is tempted to <em>implement</em> it: the other two
         * tables follow no rule at all, and a computed table would be a parity defect.
         */
        @Test
        @DisplayName("the easily recognisable codes are N00, N11 ... N99 for the eight hundreds")
        void easilyRecognisableCodesFollowTheServicePattern() {
            Set<String> expected = new LinkedHashSet<>();
            for (char hundred = '2'; hundred <= '9'; hundred++) {
                for (char pair = '0'; pair <= '9'; pair++) {
                    expected.add("" + hundred + pair + pair);
                }
            }
            assertThat(expected).hasSize(80);
            assertThat(lookup.validEasyRecognitionAreaCodes())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }

        /**
         * The two state lists in this copybook genuinely disagree, and the disagreement is
         * preserved rather than reconciled. Six ZIP prefixes are not valid state codes.
         */
        @Test
        @DisplayName("the ZIP table uses 62 prefixes, six of which are not valid state codes")
        void zipPrefixesExceedTheStateTable() {
            Set<String> prefixes = new TreeSet<>();
            for (String combo : lookup.validStateZip2Combos()) {
                prefixes.add(combo.substring(0, AreaCodeLookup.US_STATE_CODE_LENGTH));
            }
            assertThat(prefixes).hasSize(62);

            Set<String> notStates = new TreeSet<>(prefixes);
            notStates.removeAll(lookup.validUsStateCodes());
            assertThat(notStates).containsExactly("AA", "AE", "AP", "FM", "MH", "PW");

            // Every state code does have at least one ZIP combination, so the asymmetry is one-way.
            Set<String> statesWithoutZip = new TreeSet<>(lookup.validUsStateCodes());
            statesWithoutZip.removeAll(prefixes);
            assertThat(statesWithoutZip).isEmpty();

            // The observable consequence, preserved: COACTUPC runs the two edits separately.
            assertThat(lookup.isValidStateAndZipCombination("AA", "34000")).isTrue();
            assertThat(lookup.isValidUsStateCode("AA")).isFalse();
        }

        /** The 56 state entries are the 50 states, DC, and five territories - and nothing else. */
        @Test
        @DisplayName("the state table holds the 50 states, DC and five territories")
        void stateTableComposition() {
            assertThat(lookup.validUsStateCodes())
                    .contains("AL", "AK", "CA", "NY", "TX", "WY")
                    .contains("DC")
                    .contains("AS", "GU", "MP", "PR", "VI")
                    .as("the armed-forces codes are not state codes in this copybook")
                    .doesNotContain("AA", "AE", "AP")
                    .as("the freely-associated states are not state codes either")
                    .doesNotContain("FM", "MH", "PW");
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("MOVE semantics - the three places a naive contains() would diverge")
    class MoveSemantics {

        /**
         * A COBOL alphanumeric {@code MOVE} truncates on the <strong>right</strong>. So an over-long
         * argument is not rejected, it is shortened - and the shortened value can be valid.
         */
        @ParameterizedTest(name = "[{index}] \"{0}\" truncates to a valid code")
        @CsvSource({
            "2011, 201",
            "201X, 201",
            "9999, 999",
            "201999, 201",
        })
        @DisplayName("an over-long area code is truncated on the right, not rejected")
        void overLongAreaCodeIsTruncatedOnTheRight(String probe, String surviving) {
            assertThat(probe).startsWith(surviving);
            assertThat(lookup.isValidPhoneAreaCode(probe))
                    .isEqualTo(lookup.isValidPhoneAreaCode(surviving))
                    .isTrue();
        }

        /** Truncation on the left would keep different characters, and would be wrong. */
        @Test
        @DisplayName("truncation is on the right, never on the left")
        void truncationIsNotOnTheLeft() {
            // "X201" truncates right to "X20", which is no code at all. Left truncation would give
            // "201" and wrongly report it valid.
            assertThat(lookup.isValidPhoneAreaCode("X201")).isFalse();
            assertThat(lookup.isValidUsStateCode("XCA")).isFalse();
            assertThat(lookup.isValidStateZip2Combo("XCA90")).isFalse();
        }

        /** A short argument is space-padded on the right, and no literal has a trailing space. */
        @ParameterizedTest(name = "[{index}] \"{0}\" is padded and matches nothing")
        @ValueSource(strings = {"", " ", "2", "20", "  ", "   "})
        @DisplayName("a short or blank area code is space-padded on the right and matches nothing")
        void shortAreaCodeIsPaddedAndMatchesNothing(String probe) {
            assertThat(lookup.isValidPhoneAreaCode(probe)).isFalse();
            assertThat(lookup.isValidGeneralPurposeCode(probe)).isFalse();
            assertThat(lookup.isValidEasyRecognitionAreaCode(probe)).isFalse();
        }

        @Test
        @DisplayName("a blank probe is a member of no table")
        void blankProbeMatchesNothing() {
            assertThat(lookup.isValidPhoneAreaCode("   ")).isFalse();
            assertThat(lookup.isValidGeneralPurposeCode("")).isFalse();
            assertThat(lookup.isValidEasyRecognitionAreaCode(" ")).isFalse();
            assertThat(lookup.isValidUsStateCode("  ")).isFalse();
            assertThat(lookup.isValidUsStateCode("")).isFalse();
            assertThat(lookup.isValidStateZip2Combo("    ")).isFalse();
            assertThat(lookup.isValidStateZip2Combo("")).isFalse();
            assertThat(lookup.isValidStateAndZipCombination("  ", "          ")).isFalse();
        }

        /** COBOL folds no case in an 88-level test, so neither may this class. */
        @ParameterizedTest(name = "[{index}] \"{0}\" lower case is rejected, \"{1}\" upper accepted")
        @CsvSource({
            "ca, CA",
            "ny, NY",
            "gu, GU",
            "vi, VI",
        })
        @DisplayName("state-code comparison is case-sensitive")
        void stateCodeComparisonIsCaseSensitive(String lower, String upper) {
            assertThat(lookup.isValidUsStateCode(lower)).isFalse();
            assertThat(lookup.isValidUsStateCode(upper)).isTrue();
        }

        @Test
        @DisplayName("ZIP-combination comparison is case-sensitive")
        void zipComboComparisonIsCaseSensitive() {
            assertThat(lookup.isValidStateZip2Combo("wy83")).isFalse();
            assertThat(lookup.isValidStateZip2Combo("Wy83")).isFalse();
            assertThat(lookup.isValidStateZip2Combo("WY83")).isTrue();
            assertThat(lookup.isValidStateAndZipCombination("ca", "90210")).isFalse();
            assertThat(lookup.isValidStateAndZipCombination("CA", "90210")).isTrue();
        }

        /** A probe of the wrong item's width is simply the wrong probe. */
        @Test
        @DisplayName("a two-character probe where three are expected is invalid")
        void wrongWidthProbeIsInvalid() {
            assertThat(lookup.isValidPhoneAreaCode("99")).isFalse();
            assertThat(lookup.isValidGeneralPurposeCode("20")).isFalse();
            assertThat(lookup.isValidUsStateCode("C")).isFalse();
            assertThat(lookup.isValidStateZip2Combo("CA9")).isFalse();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Predicates - every 88-level driven from both sides")
    class Predicates {

        @ParameterizedTest(name = "[{index}] VALID-PHONE-AREA-CODE(\"{0}\") == {1}")
        @CsvSource({
            "201, true", "202, true", "999, true", "989, true", "200, true", "211, true",
            "911, true", "800, true", "212, true", "907, true",
            "221, false", "199, false", "998, false", "990, false", "950, false", "100, false",
            "000, false", "111, false", "ZZZ, false", "abc, false",
        })
        @DisplayName("VALID-PHONE-AREA-CODE, both outcomes")
        void validPhoneAreaCode(String probe, boolean expected) {
            assertThat(lookup.isValidPhoneAreaCode(probe)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] VALID-GENERAL-PURP-CODE(\"{0}\") == {1}")
        @CsvSource({
            "201, true", "202, true", "989, true", "212, true", "907, true", "504, true",
            "200, false", "211, false", "911, false", "800, false", "999, false",
            "221, false", "199, false", "ZZZ, false",
        })
        @DisplayName("VALID-GENERAL-PURP-CODE, both outcomes - the condition COACTUPC:2298 tests")
        void validGeneralPurposeCode(String probe, boolean expected) {
            assertThat(lookup.isValidGeneralPurposeCode(probe)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] VALID-EASY-RECOG-AREA-CODE(\"{0}\") == {1}")
        @CsvSource({
            "200, true", "211, true", "222, true", "800, true", "888, true", "911, true",
            "999, true",
            "201, false", "202, false", "989, false", "212, false", "221, false", "ZZZ, false",
        })
        @DisplayName("VALID-EASY-RECOG-AREA-CODE, both outcomes")
        void validEasyRecognitionAreaCode(String probe, boolean expected) {
            assertThat(lookup.isValidEasyRecognitionAreaCode(probe)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] VALID-US-STATE-CODE(\"{0}\") == {1}")
        @CsvSource({
            "AL, true", "AK, true", "CA, true", "NY, true", "TX, true", "WY, true", "DC, true",
            "AS, true", "GU, true", "MP, true", "PR, true", "VI, true",
            "ZZ, false", "AA, false", "AE, false", "AP, false", "FM, false", "MH, false",
            "PW, false", "XX, false", "ca, false", "99, false",
        })
        @DisplayName("VALID-US-STATE-CODE, both outcomes - the condition COACTUPC:2495 tests")
        void validUsStateCode(String probe, boolean expected) {
            assertThat(lookup.isValidUsStateCode(probe)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] VALID-US-STATE-ZIP-CD2-COMBO(\"{0}\") == {1}")
        @CsvSource({
            "AA34, true", "AE90, true", "CA90, true", "NY10, true", "WY82, true", "WY83, true",
            "ZZ99, false", "CA00, false", "WY84, false", "34AA, false", "AA00, false",
            "wy83, false",
        })
        @DisplayName("VALID-US-STATE-ZIP-CD2-COMBO, both outcomes - the condition COACTUPC:2542 tests")
        void validStateZip2Combo(String probe, boolean expected) {
            assertThat(lookup.isValidStateZip2Combo(probe)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{index}] 1280-EDIT-US-STATE-ZIP-CD(\"{0}\", \"{1}\") == {2}")
        @CsvSource({
            "CA, 90210, true",
            "NY, 10001, true",
            "WY, 82001, true",
            "TX, 75001, true",
            "CA, 10001, false",
            "NY, 90210, false",
            "ZZ, 99999, false",
            "CA, 00000, false",
        })
        @DisplayName("the composed state-and-ZIP edit, both outcomes")
        void stateAndZipCombination(String stateCode, String zipCode, boolean expected) {
            assertThat(lookup.isValidStateAndZipCombination(stateCode, zipCode))
                    .isEqualTo(expected);
        }

        /**
         * Membership is decided per table, and the three area-code tables genuinely differ. This
         * asserts the three-way distinction directly rather than one table at a time.
         */
        @Test
        @DisplayName("a general purpose code is not an easily recognisable one, and vice versa")
        void theThreeAreaCodeTablesAreDistinguished() {
            assertThat(lookup.isValidGeneralPurposeCode("201")).isTrue();
            assertThat(lookup.isValidEasyRecognitionAreaCode("201")).isFalse();
            assertThat(lookup.isValidPhoneAreaCode("201")).isTrue();

            assertThat(lookup.isValidGeneralPurposeCode("200")).isFalse();
            assertThat(lookup.isValidEasyRecognitionAreaCode("200")).isTrue();
            assertThat(lookup.isValidPhoneAreaCode("200")).isTrue();

            assertThat(lookup.isValidGeneralPurposeCode("221")).isFalse();
            assertThat(lookup.isValidEasyRecognitionAreaCode("221")).isFalse();
            assertThat(lookup.isValidPhoneAreaCode("221")).isFalse();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("01 US-STATE-ZIPCODE-TO-EDIT - the group layout")
    class GroupLayout {

        @Test
        @DisplayName("the two subordinates tile the group with no gap and no overlap")
        void subordinatesTileTheGroup() {
            assertThat(AreaCodeLookup.STATE_AND_FIRST_ZIP2_OFFSET).isZero();
            assertThat(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH).isEqualTo(4);
            assertThat(AreaCodeLookup.LAST_3_OF_ZIP_OFFSET)
                    .isEqualTo(AreaCodeLookup.STATE_AND_FIRST_ZIP2_OFFSET
                            + AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
            assertThat(AreaCodeLookup.LAST_3_OF_ZIP_LENGTH).isEqualTo(3);
            assertThat(AreaCodeLookup.LAST_3_OF_ZIP_OFFSET + AreaCodeLookup.LAST_3_OF_ZIP_LENGTH)
                    .isEqualTo(AreaCodeLookup.STATE_ZIPCODE_GROUP_LENGTH)
                    .isEqualTo(7);
        }

        /** The declared widths come straight from the copybook's PICTURE clauses. */
        @Test
        @DisplayName("the probe widths are the copybook's PICTURE widths")
        void probeWidthsAreTheDeclaredWidths() {
            assertThat(AreaCodeLookup.PHONE_AREA_CODE_LENGTH).isEqualTo(3);
            assertThat(AreaCodeLookup.US_STATE_CODE_LENGTH).isEqualTo(2);
            assertThat(AreaCodeLookup.CUSTOMER_ZIP_LENGTH).isEqualTo(10);
            assertThat(AreaCodeLookup.ZIP_PREFIX_LENGTH).isEqualTo(2);
        }

        /** State code first, then the ZIP prefix - the order of the STRING at COACTUPC:2537-2540. */
        @ParameterizedTest(name = "[{index}] STRING \"{0}\" \"{1}\" -> \"{2}\"")
        @CsvSource({
            "CA, 90210, CA90",
            "NY, 10001, NY10",
            "WY, 82001, WY82",
            "AA, 34000, AA34",
        })
        @DisplayName("the composer puts the state code first and the ZIP prefix second")
        void composerOrderMatchesTheStringStatement(String stateCode, String zipCode,
                                                   String expected) {
            assertThat(lookup.composeStateAndFirstZip2(stateCode, zipCode)).isEqualTo(expected);
        }

        /** DELIMITED BY SIZE contributes each operand's full declared width, padding included. */
        @ParameterizedTest(name = "[{index}] STRING \"{0}\" \"{1}\" -> \"{2}\"")
        @CsvSource(quoteCharacter = '\'', value = {
            "'C',  '90210', 'C 90'",
            "'CA', '9',     'CA9 '",
            "'CA', '',      'CA  '",
            "'',   '',      '    '",
            "'CAX','90210', 'CA90'",
            "'CA', '9021012345', 'CA90'",
        })
        @DisplayName("operands are padded and truncated to their declared widths before concatenation")
        void composerAppliesDeclaredWidths(String stateCode, String zipCode, String expected) {
            assertThat(lookup.composeStateAndFirstZip2(stateCode, zipCode))
                    .isEqualTo(expected)
                    .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
        }

        /**
         * The group image after {@code 1280-EDIT-US-STATE-ZIP-CD}: the probe, then a blank
         * {@code LAST-3-OF-ZIP}, because no statement in {@code COACTUPC} ever writes that span.
         */
        @Test
        @DisplayName("the group image leaves LAST-3-OF-ZIP blank, as COACTUPC leaves it")
        void groupImageLeavesLastThreeOfZipBlank() {
            String image = lookup.stateZipcodeGroupImage("CA", "90210");
            assertThat(image).isEqualTo("CA90   ")
                    .hasSize(AreaCodeLookup.STATE_ZIPCODE_GROUP_LENGTH);
            assertThat(lookup.stateAndFirstZip2(image)).isEqualTo("CA90");
            assertThat(lookup.lastThreeOfZip(image)).isEqualTo("   ");
        }

        @ParameterizedTest(name = "[{index}] group \"{0}\" -> zip2 \"{1}\", last3 \"{2}\"")
        @CsvSource(quoteCharacter = '\'', value = {
            "'WY82XYZ', 'WY82', 'XYZ'",
            "'CA90210', 'CA90', '210'",
            "'AA34   ', 'AA34', '   '",
            "'AA34',    'AA34', '   '",
            "'A',       'A   ', '   '",
            "'',        '    ', '   '",
            "'WY82XYZ99', 'WY82', 'XYZ'",
        })
        @DisplayName("both subordinates are read at their declared offsets, whatever the image width")
        void subordinatesAreReadAtTheirDeclaredOffsets(String image, String zip2, String last3) {
            assertThat(lookup.stateAndFirstZip2(image)).isEqualTo(zip2)
                    .hasSize(AreaCodeLookup.STATE_AND_FIRST_ZIP2_LENGTH);
            assertThat(lookup.lastThreeOfZip(image)).isEqualTo(last3)
                    .hasSize(AreaCodeLookup.LAST_3_OF_ZIP_LENGTH);
        }

        /** The probe read back out of a group image is the probe the predicate accepts. */
        @Test
        @DisplayName("the probe read out of a group image round-trips into the predicate")
        void probeReadFromGroupImageRoundTrips() {
            String image = lookup.stateZipcodeGroupImage("WY", "82001");
            assertThat(lookup.isValidStateZip2Combo(lookup.stateAndFirstZip2(image))).isTrue();
            assertThat(lookup.isValidStateAndZipCombination("WY", "82001")).isTrue();
        }
    }

    // =================================================================================================

    @Nested
    @DisplayName("Contracts - nulls, immutability, wiring and the transcription guard")
    class Contracts {

        @Test
        @DisplayName("every predicate rejects null rather than treating it as blank")
        void predicatesRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidPhoneAreaCode(null))
                    .withMessageContaining("WS-US-PHONE-AREA-CODE-TO-EDIT");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidGeneralPurposeCode(null))
                    .withMessageContaining("WS-US-PHONE-AREA-CODE-TO-EDIT");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidEasyRecognitionAreaCode(null))
                    .withMessageContaining("WS-US-PHONE-AREA-CODE-TO-EDIT");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidUsStateCode(null))
                    .withMessageContaining("US-STATE-CODE-TO-EDIT");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidStateZip2Combo(null))
                    .withMessageContaining("US-STATE-AND-FIRST-ZIP2");
        }

        @Test
        @DisplayName("the group helpers reject null in either position")
        void groupHelpersRejectNull() {
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.composeStateAndFirstZip2(null, "90210"))
                    .withMessageContaining("ACUP-NEW-CUST-ADDR-STATE-CD");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.composeStateAndFirstZip2("CA", null))
                    .withMessageContaining("ACUP-NEW-CUST-ADDR-ZIP");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidStateAndZipCombination(null, "90210"));
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.isValidStateAndZipCombination("CA", null));
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.stateZipcodeGroupImage(null, "90210"));
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.stateAndFirstZip2(null))
                    .withMessageContaining("US-STATE-ZIPCODE-TO-EDIT");
            assertThatNullPointerException()
                    .isThrownBy(() -> lookup.lastThreeOfZip(null))
                    .withMessageContaining("US-STATE-ZIPCODE-TO-EDIT");
        }

        @Test
        @DisplayName("the constructor rejects a null codec")
        void constructorRejectsNullCodec() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AreaCodeLookup(null))
                    .withMessageContaining("FixedWidthCodec");
        }

        /** Static tables must be immutable, not merely private (binding practice B9). */
        @Test
        @DisplayName("every table is unmodifiable")
        void tablesAreUnmodifiable() {
            List<Set<String>> tables = List.of(
                    lookup.validPhoneAreaCodes(),
                    lookup.validGeneralPurposeCodes(),
                    lookup.validEasyRecognitionAreaCodes(),
                    lookup.validUsStateCodes(),
                    lookup.validStateZip2Combos());
            for (Set<String> table : tables) {
                assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(() -> table.add("999"));
                assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(() -> table.remove("201"));
                assertThatExceptionOfType(UnsupportedOperationException.class)
                        .isThrownBy(table::clear);
            }
        }

        /** The tables are shared, so two instances must not each hold their own copy. */
        @Test
        @DisplayName("the tables are shared between instances")
        void tablesAreSharedBetweenInstances() {
            AreaCodeLookup other = new AreaCodeLookup(
                    new FixedWidthCodec(StandardCharsets.US_ASCII));
            assertThat(other.validPhoneAreaCodes()).isSameAs(lookup.validPhoneAreaCodes());
            assertThat(other.validStateZip2Combos()).isSameAs(lookup.validStateZip2Combos());
        }

        /**
         * Both constructors must answer identically. The charset a codec carries is immaterial here,
         * because the width rule is pure character work and this class reads no dataset bytes.
         */
        @Test
        @DisplayName("both constructors give identical answers, whatever charset the codec carries")
        void bothConstructorsAgree() {
            AreaCodeLookup injected = new AreaCodeLookup(
                    new FixedWidthCodec(StandardCharsets.ISO_8859_1));
            assertThat(injected.isValidGeneralPurposeCode("201"))
                    .isEqualTo(lookup.isValidGeneralPurposeCode("201")).isTrue();
            assertThat(injected.isValidGeneralPurposeCode("221"))
                    .isEqualTo(lookup.isValidGeneralPurposeCode("221")).isFalse();
            assertThat(injected.composeStateAndFirstZip2("CA", "90210"))
                    .isEqualTo(lookup.composeStateAndFirstZip2("CA", "90210"));
            assertThat(injected.lastThreeOfZip("WY82XYZ"))
                    .isEqualTo(lookup.lastThreeOfZip("WY82XYZ"));
        }

        /** A stateless, injectable lookup: the stereotype must be present and the class immutable. */
        @Test
        @DisplayName("the class is an injectable @Component with no mutable state")
        void classIsAnInjectableComponent() {
            assertThat(AreaCodeLookup.class.getAnnotation(Component.class)).isNotNull();
            assertThat(Modifier.isFinal(AreaCodeLookup.class.getModifiers())).isTrue();
            assertThat(AreaCodeLookup.class.getDeclaredFields())
                    .as("no field may be non-final, and no static field may be an array")
                    .allSatisfy(field -> {
                        assertThat(Modifier.isFinal(field.getModifiers()))
                                .as("%s is final", field.getName()).isTrue();
                        assertThat(field.getType().isArray())
                                .as("%s is not an array", field.getName()).isFalse();
                    });
            assertThat(AreaCodeLookup.class.getDeclaredConstructors())
                    .as("a no-argument constructor must exist for component scanning")
                    .anySatisfy(constructor ->
                            assertThat(constructor.getParameterCount()).isZero());
        }

        /**
         * Both sides of the transcription guard. Its failing branch is unreachable while the tables
         * are correct, which is exactly why it is driven directly here: a guard that is never
         * executed is a guard nobody knows works.
         */
        @Test
        @DisplayName("the transcription guard passes a correct table through unchanged")
        void transcriptionGuardAcceptsACorrectTable() {
            Set<String> table = new LinkedHashSet<>(List.of("201", "202", "203"));
            assertThat(AreaCodeLookup.requireExactSize(table, 3, "VALID-PHONE-AREA-CODE"))
                    .isSameAs(table);
        }

        @Test
        @DisplayName("the transcription guard fails a table that lost or gained a literal")
        void transcriptionGuardRejectsAMiscountedTable() {
            Set<String> tooFew = new LinkedHashSet<>(List.of("201", "202"));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            AreaCodeLookup.requireExactSize(tooFew, 3, "VALID-PHONE-AREA-CODE"))
                    .withMessageContaining("88 VALID-PHONE-AREA-CODE holds 2")
                    .withMessageContaining("declares 3")
                    .withMessageContaining("app/cpy/CSLKPCDY.cpy");

            Set<String> tooMany = new LinkedHashSet<>(List.of("AL", "AK", "AZ"));
            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() ->
                            AreaCodeLookup.requireExactSize(tooMany, 2, "VALID-US-STATE-CODE"))
                    .withMessageContaining("88 VALID-US-STATE-CODE holds 3");
        }

        @Test
        @DisplayName("the transcription guard rejects a null table")
        void transcriptionGuardRejectsNull() {
            assertThatNullPointerException()
                    .isThrownBy(() ->
                            AreaCodeLookup.requireExactSize(null, 1, "VALID-US-STATE-CODE"))
                    .withMessageContaining("VALID-US-STATE-CODE");
        }
    }
}
