package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.common.FixedWidthCodec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
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
 */
@DisplayName("AreaCodeLookup - app/cpy/CSLKPCDY.cpy, five 88-level VALUES lists")
class AreaCodeLookupTest {
    private static final String COPYBOOK_PATH = "app/cpy/CSLKPCDY.cpy";

    private static final String COPYBOOK_RESOURCE = "/cobol-oracle/CSLKPCDY.cpy";

    private static final Pattern COPYBOOK_LITERAL = Pattern.compile("'([^']*)'");

    private static final char SPACE = ' ';

    private AreaCodeLookup lookup;

    @BeforeEach
    void createSubjectUnderTest() {
        lookup = new AreaCodeLookup();
    }

    /**
     * The five {@code 88}-level {@code VALUES} clauses of {@code app/cpy/CSLKPCDY.cpy}: the condition name,
     * the inclusive copybook line range its literals occupy, the number of literals the clause declares,
     * and the runtime table {@link AreaCodeLookup} exposes for it.
     *
     * @param conditionName the name of the copybook condition being checked
     * @param firstLine the first copybook line the literals occupy
     * @param lastLine the last copybook line the literals occupy
     * @param declaredCount the number of literals the copybook clause declares
     * @param runtimeTable the table the Java translation exposes for that clause
     */
    private record Clause(String conditionName, int firstLine, int lastLine, int declaredCount,
                          Set<String> runtimeTable) {
    }

    private List<Clause> declaredClauses() {
        return List.of(
                new Clause("VALID-PHONE-AREA-CODE", 30, 520,
                        AreaCodeLookup.VALID_PHONE_AREA_CODE_COUNT, lookup.validPhoneAreaCodes()),
                new Clause("VALID-GENERAL-PURP-CODE", 521, 930,
                        AreaCodeLookup.VALID_GENERAL_PURP_CODE_COUNT,
                        lookup.validGeneralPurposeCodes()),
                new Clause("VALID-EASY-RECOG-AREA-CODE", 931, 1010,
                        AreaCodeLookup.VALID_EASY_RECOG_AREA_CODE_COUNT,
                        lookup.validEasyRecognitionAreaCodes()),
                new Clause("VALID-US-STATE-CODE", 1013, 1069,
                        AreaCodeLookup.VALID_US_STATE_CODE_COUNT, lookup.validUsStateCodes()),
                new Clause("VALID-US-STATE-ZIP-CD2-COMBO", 1073, 1313,
                        AreaCodeLookup.VALID_US_STATE_ZIP_CD2_COMBO_COUNT,
                        lookup.validStateZip2Combos()));
    }

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

    private String copybookLiteral(String line) {
        if (isComment(line)) {
            return null;
        }
        Matcher matcher = COPYBOOK_LITERAL.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private boolean isComment(String line) {
        return line.length() >= 7 && line.charAt(6) == '*';
    }

    private List<String> copybookLines() {
        try (InputStream oracle = AreaCodeLookupTest.class.getResourceAsStream(COPYBOOK_RESOURCE)) {
            if (oracle == null) {
                throw new IllegalStateException("The parity oracle " + COPYBOOK_RESOURCE
                        + " is absent from the test classpath. It is " + COPYBOOK_PATH
                        + ", copied there by the second <testResource> in app/java/pom.xml, and this "
                        + "suite proves the transcription against the copybook itself rather than "
                        + "against a copy of it - so its absence is a build configuration failure and "
                        + "not a reason to skip the check.");
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(oracle, StandardCharsets.US_ASCII))) {
                List<String> lines = new ArrayList<>();
                String line = reader.readLine();
                while (line != null) {
                    lines.add(line);
                    line = reader.readLine();
                }
                return lines;
            }
        } catch (IOException cause) {
            throw new UncheckedIOException("Cannot read the parity oracle " + COPYBOOK_RESOURCE
                    + " (" + COPYBOOK_PATH + ") from the test classpath", cause);
        }
    }

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

    @Nested
    @DisplayName("Provenance - every literal diffed against app/cpy/CSLKPCDY.cpy")
    class Provenance {
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

            Assertions.assertThat(clauses.stream().map(Clause::declaredCount))
                    .containsExactly(490, 410, 80, 56, 240);
            Assertions.assertThat(clauses.stream().mapToInt(Clause::declaredCount).sum())
                    .as("1,276 literals across the five clauses")
                    .isEqualTo(1276);
        }

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

        @Test
        @DisplayName("the copybook declares no OCCURS, so there is no index to convert")
        void theCopybookIsNotATable() {
            Assertions.assertThat(copybookLines())
                    .as("no line of %s declares an OCCURS", COPYBOOK_PATH)
                    .noneMatch(line -> line.contains("OCCURS"));
        }

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

        private boolean startsDeclaration(String line) {
            String content = line.strip();
            return content.startsWith("01 ") || content.startsWith("02 ")
                    || content.startsWith("88 ");
        }
    }

    @Nested
    @DisplayName("Cardinality - 490, 410, 80, 56 and 240 entries at 3, 3, 3, 2 and 4 characters")
    class Cardinality {
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

    @Nested
    @DisplayName("Algebra - how the three area-code clauses relate, derived not assumed")
    class Algebra {
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

            Assertions.assertThat(lookup.isValidStateAndZipCombination("AA", "34000")).isTrue();
            Assertions.assertThat(lookup.isValidUsStateCode("AA")).isFalse();
        }

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

    @Nested
    @DisplayName("MOVE semantics - where a plain Set.contains would diverge from COBOL")
    class MoveSemantics {
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

        @Test
        @DisplayName("truncation is on the right, never on the left")
        void truncationIsNeverOnTheLeft() {
            Assertions.assertThat(lookup.isValidPhoneAreaCode("X201")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("X201")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("XCA")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("XCA90")).isFalse();
        }

        @ParameterizedTest(name = "[{index}] \"{0}\" is space-padded and matches nothing")
        @ValueSource(strings = {"", " ", "2", "20", "  ", "   "})
        @DisplayName("a short or blank area code is space-padded on the right and matches nothing")
        void shortOrBlankAreaCodeIsPaddedAndMatchesNothing(String probe) {
            Assertions.assertThat(lookup.isValidPhoneAreaCode(probe)).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(probe)).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode(probe)).isFalse();
        }

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

            Assertions.assertThat(lookup.isValidPhoneAreaCode("")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("")).isFalse();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("", "")).isFalse();
        }

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

        @Test
        @DisplayName("a probe narrower than its item matches nothing")
        void aProbeNarrowerThanItsItemMatchesNothing() {
            Assertions.assertThat(lookup.isValidPhoneAreaCode("99")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("20")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("91")).isFalse();
            Assertions.assertThat(lookup.isValidUsStateCode("C")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("CA9")).isFalse();
        }

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

            Assertions.assertThat(lookup.isValidUsStateCode("ZZZ")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("ZZ999")).isFalse();
        }
    }

    @Nested
    @DisplayName("1260-EDIT-US-PHONE-NUM - FUNCTION TRIM then MOVE, app/cbl/COACTUPC.cbl:2296-2298")
    class PhoneEditIdiom {
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

        @ParameterizedTest(name = "[{index}] TRIM(\"{0}\") is empty, rejected")
        @CsvSource(quoteCharacter = '\'', value = {"'   '", "' '", "''"})
        @DisplayName("a blank area code trims to nothing and fails the edit")
        void aBlankAreaCodeFailsTheEdit(String typed) {
            Assertions.assertThat(functionTrim(typed)).isEmpty();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode(functionTrim(typed))).isFalse();
        }

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

    @Nested
    @DisplayName("1270 and 1280 - the state and ZIP edits, app/cbl/COACTUPC.cbl:2493 and :2536")
    class StateAndZipEditIdioms {
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

        @Test
        @DisplayName("the two operands are not interchangeable")
        void theOrderOfTheTwoOperandsIsObservable() {
            Assertions.assertThat(lookup.isValidStateZip2Combo("AA34")).isTrue();
            Assertions.assertThat(lookup.isValidStateZip2Combo("34AA")).isFalse();
            Assertions.assertThat(lookup.isValidStateZip2Combo("CA90")).isTrue();
            Assertions.assertThat(lookup.isValidStateZip2Combo("90CA")).isFalse();
        }

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

        @Test
        @DisplayName("only the first two ZIP characters reach the probe, per the (1:2) reference")
        void theZipEditUsesOnlyTheFirstTwoZipCharacters() {
            Assertions.assertThat(lookup.composeStateAndFirstZip2("CA", "90210"))
                    .isEqualTo(lookup.composeStateAndFirstZip2("CA", "90999"))
                    .isEqualTo(lookup.composeStateAndFirstZip2("CA", "90"))
                    .isEqualTo("CA90");
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "90210")).isTrue();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "90999")).isTrue();

            Assertions.assertThat(lookup.isValidUsStateCode("CA")).isTrue();
            Assertions.assertThat(lookup.isValidStateAndZipCombination("CA", "10001")).isFalse();
        }

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

    @Nested
    @DisplayName("Predicates - all five 88-levels driven true and false (the both-states gate)")
    class Predicates {
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

        @Test
        @DisplayName("the three area-code clauses answer differently for the same probe")
        void theThreeAreaCodeClausesAreDistinguished() {
            Assertions.assertThat(lookup.isValidPhoneAreaCode("201")).isTrue();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("201")).isTrue();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("201")).isFalse();

            Assertions.assertThat(lookup.isValidPhoneAreaCode("200")).isTrue();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("200")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("200")).isTrue();

            Assertions.assertThat(lookup.isValidPhoneAreaCode("221")).isFalse();
            Assertions.assertThat(lookup.isValidGeneralPurposeCode("221")).isFalse();
            Assertions.assertThat(lookup.isValidEasyRecognitionAreaCode("221")).isFalse();
        }
    }

    @Nested
    @DisplayName("Contracts - nulls, immutability, wiring and the transcription guard")
    class Contracts {
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

        @Test
        @DisplayName("the transcription guard passes a correct table through unchanged")
        void theTranscriptionGuardAcceptsACorrectTable() {
            Set<String> table = new LinkedHashSet<>(List.of("201", "202", "203"));
            Assertions.assertThat(
                            AreaCodeLookup.requireExactSize(table, 3, "VALID-PHONE-AREA-CODE"))
                    .isSameAs(table);
        }

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
