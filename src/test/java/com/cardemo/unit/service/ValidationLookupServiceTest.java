/*
 * ******************************************************************
 * Program     : ValidationLookupServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Loads the three CSLKPCDY lookup resources from the
 *               real classpath and proves every membership claim they
 *               make about themselves, together with the five
 *               predicates that replace the frozen 88-level condition
 *               names. The resources assert that tests covering these
 *               claims exist; this class is what makes that true.
 * Source      : app/cpy/CSLKPCDY.cpy:1013-1069 (VALID-US-STATE-CODE)
 *               app/cpy/CSLKPCDY.cpy:1073-1313 (state + zip2 combos)
 *               app/cpy/CSLKPCDY.cpy            (three NANPA tables)
 *               frozen at commit 7756d89
 * ******************************************************************
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 * ******************************************************************
 */
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cardemo.exception.ValidationException;
import com.cardemo.service.shared.ValidationLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Executable specification for the five lookup tables the frozen copybook declares as 88-levels.
 *
 * <p>This test deliberately constructs the service against a real {@link DefaultResourceLoader} and a real
 * {@link ObjectMapper}, so the three JSON documents are genuinely read from the classpath, genuinely
 * parsed and genuinely bound. Mocking the loader would have made the class faster and would have proved
 * nothing: the entire risk in this component is that a resource is missing, renamed, malformed, or has
 * drifted from the copybook. A test that stubs the resource away cannot see any of those.
 *
 * <p>The three resources each document, in their own {@code howToBuildAndTest} member, a specific list of
 * properties they claim are covered by assertions under {@code src/test/java/com/cardemo/unit/}. Those
 * claims were made before any such assertion existed. Every one of them is discharged here - element
 * counts, first and last values, interior index checkpoints, disjointness, the concatenation identity, and
 * the subset relationship between the two address tables - so that the documentation becomes true rather
 * than aspirational.
 *
 * <p>Two of those claims read as though they were wrong, and checking them properly showed they are not.
 * It is worth recording why, because the same misreading is easy to repeat. The zip resource speaks of
 * "the 62 distinct prefixes", and there are in fact 90 distinct two-digit zip prefixes in that table - but
 * the resource defines "prefix" as the leading two characters of the four-character key, and confirms that
 * definition by calling {@code AA}, {@code AE} and {@code AP} prefixes. Under its own vocabulary 62 is
 * correct, and 62 is indeed the number of distinct leading pairs. Likewise it refers to "the descending NY
 * run", which sounds like a claim that the run descends monotonically; the full description says something
 * narrower and exactly true, that this is the one run in the table where the <em>zip part</em> descends.
 * Both figures are pinned below anyway - 62 leading pairs and 90 zip prefixes - not to correct the
 * resource but to remove the ambiguity that made a correct document look incorrect, in a file whose own
 * name uses "prefixes" in the other sense.
 *
 * <p>One asymmetry in the frozen data is worth recording because it looks like an error and is not. The
 * state-and-zip table contains six state codes the state-code table omits - {@code AA}, {@code AE} and
 * {@code AP}, the military postal designations, and {@code FM}, {@code MH} and {@code PW}, the freely
 * associated Pacific states. The subset relation therefore runs one way only: every state code is present
 * in the zip table, but not conversely. A test asserting mutual equality would fail against correct data.
 */
@DisplayName("ValidationLookupService: the five CSLKPCDY tables, loaded for real and checked against the copybook")
class ValidationLookupServiceTest {

    /** NANPA table sizes proven by literal extraction from the copybook. */
    private static final int ALL_AREA_CODES = 490;
    private static final int GENERAL_PURPOSE_AREA_CODES = 410;
    private static final int EASILY_RECOGNISABLE_AREA_CODES = 80;
    private static final int US_STATE_CODES = 56;
    private static final int STATE_ZIP_COMBOS = 240;

    /**
     * The service is immutable and the load is the expensive part, so one instance serves every test. If
     * any resource were missing or malformed this construction would fail and every test would report it,
     * which is the desired blast radius for a startup-fatal condition.
     */
    private static ValidationLookupService service;

    @BeforeAll
    static void loadFromTheRealClasspath() {
        service = new ValidationLookupService(new DefaultResourceLoader(), new ObjectMapper());
    }

    @Nested
    @DisplayName("1. All three resources load from the classpath and bind to the declared widths")
    class ResourcesLoad {

        @Test
        @DisplayName("every table is non-empty, so no resource silently bound to nothing")
        void everyTableIsPopulated() {
            assertThat(service.getValidPhoneAreaCodes()).isNotEmpty();
            assertThat(service.getValidGeneralPurposeAreaCodes()).isNotEmpty();
            assertThat(service.getValidEasilyRecognisableAreaCodes()).isNotEmpty();
            assertThat(service.getValidUsStateCodes()).isNotEmpty();
            assertThat(service.getValidStateZipCodeCombinations()).isNotEmpty();
        }

        @Test
        @DisplayName("every value has exactly the width its PIC clause declares")
        void everyValueHasTheDeclaredWidth() {
            assertThat(service.getValidPhoneAreaCodes())
                    .allMatch(code -> code.length() == ValidationLookupService.AREA_CODE_WIDTH);
            assertThat(service.getValidUsStateCodes())
                    .allMatch(code -> code.length() == ValidationLookupService.STATE_CODE_WIDTH);
            assertThat(service.getValidStateZipCodeCombinations())
                    .allMatch(key -> key.length() == ValidationLookupService.STATE_ZIP_KEY_WIDTH);
        }

        @Test
        @DisplayName("no table contains a duplicate, so no copybook value was transcribed twice")
        void noTableContainsADuplicate() {
            // The accessors return a SequencedSet, so a duplicate in the JSON would be silently absorbed
            // rather than reported. Comparing the bound size against the count extracted from the copybook
            // is what actually detects that, which is why the counts below are asserted as exact.
            assertThat(service.getValidPhoneAreaCodes()).hasSize(ALL_AREA_CODES);
            assertThat(service.getValidStateZipCodeCombinations()).hasSize(STATE_ZIP_COMBOS);
        }

        @Test
        @DisplayName("the returned collections are immutable, so no caller can corrupt the tables")
        void returnedCollectionsAreImmutable() {
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> service.getValidUsStateCodes().add("ZZ"));
            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> service.getValidPhoneAreaCodes().clear());
        }
    }

    @Nested
    @DisplayName("2. The NANPA resource's self-declared claims")
    class NanpaClaims {

        @Test
        @DisplayName("the three element counts are 490, 410 and 80")
        void elementCounts() {
            assertThat(service.getValidPhoneAreaCodes()).hasSize(ALL_AREA_CODES);
            assertThat(service.getValidGeneralPurposeAreaCodes()).hasSize(GENERAL_PURPOSE_AREA_CODES);
            assertThat(service.getValidEasilyRecognisableAreaCodes())
                    .hasSize(EASILY_RECOGNISABLE_AREA_CODES);
        }

        @Test
        @DisplayName("the first and last value of each table match the copybook")
        void firstAndLastOfEachTable() {
            assertThat(first(service.getValidPhoneAreaCodes())).isEqualTo("201");
            assertThat(last(service.getValidPhoneAreaCodes())).isEqualTo("999");
            assertThat(first(service.getValidGeneralPurposeAreaCodes())).isEqualTo("201");
            assertThat(last(service.getValidGeneralPurposeAreaCodes())).isEqualTo("989");
            assertThat(first(service.getValidEasilyRecognisableAreaCodes())).isEqualTo("200");
            assertThat(last(service.getValidEasilyRecognisableAreaCodes())).isEqualTo("999");
        }

        @Test
        @DisplayName("the two subsets are disjoint")
        void subsetsAreDisjoint() {
            assertThat(service.getValidGeneralPurposeAreaCodes())
                    .doesNotContainAnyElementsOf(service.getValidEasilyRecognisableAreaCodes());
        }

        @Test
        @DisplayName("the concatenation identity holds: general plus easily-recognisable is the whole table")
        void concatenationIdentity() {
            // 410 + 80 = 490 exactly, with no overlap and no residue in either direction. This is the
            // strongest available evidence that the transcription of all three tables is complete and that
            // no value was dropped or invented.
            Set<String> union = new LinkedHashSet<>(service.getValidGeneralPurposeAreaCodes());
            union.addAll(service.getValidEasilyRecognisableAreaCodes());

            assertThat(union)
                    .hasSize(ALL_AREA_CODES)
                    .containsExactlyElementsOf(service.getValidPhoneAreaCodes());
        }

        @ParameterizedTest(name = "all-table index {0} is {1}")
        @CsvSource({"122, 431", "245, 683", "367, 912"})
        @DisplayName("interior index checkpoints hold for the combined table")
        void combinedTableCheckpoints(final int index, final String expected) {
            assertThat(at(service.getValidPhoneAreaCodes(), index)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "general index {0} is {1}")
        @CsvSource({"102, 403", "205, 613", "307, 804"})
        @DisplayName("interior index checkpoints hold for the general-purpose subset")
        void generalPurposeCheckpoints(final int index, final String expected) {
            assertThat(at(service.getValidGeneralPurposeAreaCodes(), index)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "easily-recognisable index {0} is {1}")
        @CsvSource({"20, 400", "40, 600", "60, 800"})
        @DisplayName("interior index checkpoints hold for the easily-recognisable subset")
        void easilyRecognisableCheckpoints(final int index, final String expected) {
            assertThat(at(service.getValidEasilyRecognisableAreaCodes(), index)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the combined table's only descent is the seam between the two subsets")
        void theOnlyDescentIsTheSeam() {
            // Each subset is individually ascending, so concatenating them produces exactly one descent,
            // and it must fall at the boundary: index 409 is the last general-purpose code and index 410
            // the first easily-recognisable one. This is structural proof that the combined array is the
            // two subsets in order rather than a merged or sorted rendering of them - a stronger statement
            // than set equality, which would hold for any permutation.
            List<String> all = new ArrayList<>(service.getValidPhoneAreaCodes());
            List<Integer> descents = new ArrayList<>();
            for (int index = 0; index < all.size() - 1; index++) {
                if (all.get(index).compareTo(all.get(index + 1)) > 0) {
                    descents.add(index);
                }
            }

            assertThat(descents).containsExactly(GENERAL_PURPOSE_AREA_CODES - 1);
            assertThat(all.get(GENERAL_PURPOSE_AREA_CODES - 1)).isEqualTo("989");
            assertThat(all.get(GENERAL_PURPOSE_AREA_CODES)).isEqualTo("200");
        }

        @Test
        @DisplayName("the whole table preserves general-then-easily-recognisable order")
        void concatenationIsAlsoOrderPreserving() {
            List<String> expected = new ArrayList<>(service.getValidGeneralPurposeAreaCodes());
            expected.addAll(service.getValidEasilyRecognisableAreaCodes());

            assertThat(service.getValidPhoneAreaCodes()).containsExactlyElementsOf(expected);
        }
    }

    @Nested
    @DisplayName("3. The state-code resource's self-declared claims")
    class StateCodeClaims {

        @Test
        @DisplayName("the element count is 56 and the bounds are AL and VI")
        void countAndBounds() {
            assertThat(service.getValidUsStateCodes()).hasSize(US_STATE_CODES);
            assertThat(first(service.getValidUsStateCodes())).isEqualTo("AL");
            assertThat(last(service.getValidUsStateCodes())).isEqualTo("VI");
        }

        @ParameterizedTest(name = "index {0} is {1}")
        @CsvSource({"14, IA", "28, NH", "42, TX"})
        @DisplayName("the interior index checkpoints hold, proving copybook order is preserved")
        void interiorCheckpoints(final int index, final String expected) {
            assertThat(at(service.getValidUsStateCodes(), index)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the table is NOT sorted, because the copybook is not sorted")
        void tableIsNotSorted() {
            // 'VI' last rather than 'WY' is the giveaway. Sorting the array would look tidier and would
            // silently destroy the index checkpoints that prove faithful transcription.
            List<String> actual = new ArrayList<>(service.getValidUsStateCodes());
            List<String> sorted = new ArrayList<>(actual);
            sorted.sort(null);

            assertThat(actual).isNotEqualTo(sorted);
        }
    }

    @Nested
    @DisplayName("4. The state-and-zip resource's self-declared claims")
    class StateZipClaims {

        @Test
        @DisplayName("the element count is 240 and the bounds are AA34 and WY83")
        void countAndBounds() {
            assertThat(service.getValidStateZipCodeCombinations()).hasSize(STATE_ZIP_COMBOS);
            assertThat(first(service.getValidStateZipCodeCombinations())).isEqualTo("AA34");
            assertThat(last(service.getValidStateZipCodeCombinations())).isEqualTo("WY83");
        }

        @ParameterizedTest(name = "index {0} is {1}")
        @CsvSource({"60, KS66", "120, NH33", "180, PR71"})
        @DisplayName("the interior index checkpoints hold")
        void interiorCheckpoints(final int index, final String expected) {
            assertThat(at(service.getValidStateZipCodeCombinations(), index)).isEqualTo(expected);
        }

        @Test
        @DisplayName("both senses of prefix are pinned: 62 leading pairs and 90 zip prefixes")
        void bothSensesOfPrefixArePinned() {
            // The resource's "62 distinct prefixes" counts leading two-character pairs, which are the
            // state portion of the key. That is correct. Pinning the other figure alongside it means a
            // future reader cannot mistake one for the other, which is what happened when this test was
            // first written.
            SequencedSet<String> combos = service.getValidStateZipCodeCombinations();

            assertThat(combos.stream().map(key -> key.substring(0, 2)).distinct().count())
                    .as("distinct leading two-character pairs, the resource's 62")
                    .isEqualTo(62L);
            assertThat(combos.stream().map(key -> key.substring(2, 4)).distinct().count())
                    .as("distinct two-digit zip portions, a different and larger count")
                    .isEqualTo(90L);
        }

        @Test
        @DisplayName("the NY run at 150-157 contains the table's single zip-part descent, as documented")
        void theNyRunHasExactlyOneDescent() {
            List<String> run = new ArrayList<>(service.getValidStateZipCodeCombinations())
                    .subList(150, 158);

            assertThat(run)
                    .containsExactly("NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14");

            long descents = 0;
            for (int index = 0; index < run.size() - 1; index++) {
                if (run.get(index).compareTo(run.get(index + 1)) > 0) {
                    descents++;
                }
            }
            assertThat(descents)
                    .as("one descent, NY63 -> NY10, exactly as the resource describes it")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("every state code appears in the zip table, but not conversely")
        void subsetRelationRunsOneWayOnly() {
            Set<String> zipStates = new LinkedHashSet<>();
            service.getValidStateZipCodeCombinations()
                    .forEach(key -> zipStates.add(key.substring(0, 2)));

            assertThat(zipStates)
                    .as("the state-code table is a subset of the states used by the zip table")
                    .containsAll(service.getValidUsStateCodes());
            assertThat(zipStates)
                    .as("military postal codes and the freely associated Pacific states are extra")
                    .contains("AA", "AE", "AP", "FM", "MH", "PW");
            assertThat(service.getValidUsStateCodes())
                    .as("and those six are absent from the state-code table, so equality would be wrong")
                    .doesNotContain("AA", "AE", "AP", "FM", "MH", "PW");
        }
    }

    @Nested
    @DisplayName("5. The five predicates that replace the 88-level condition names")
    class Predicates {

        @Test
        @DisplayName("a known area code is accepted by the whole table and by exactly one subset")
        void areaCodePredicates() {
            assertThat(service.isValidPhoneAreaCode("201")).isTrue();
            assertThat(service.isValidGeneralPurposeAreaCode("201")).isTrue();
            assertThat(service.isValidEasilyRecognisableAreaCode("201")).isFalse();

            assertThat(service.isValidPhoneAreaCode("200")).isTrue();
            assertThat(service.isValidEasilyRecognisableAreaCode("200")).isTrue();
            assertThat(service.isValidGeneralPurposeAreaCode("200")).isFalse();

            assertThat(service.isValidEasilyRecognisableAreaCode("555"))
                    .as("555 is an easily-recognisable code in this copybook, not a reserved absence")
                    .isTrue();
        }

        @ParameterizedTest(name = "area code {0} is absent from the table")
        @ValueSource(strings = {"000", "001", "010", "100", "abc", "ZZZ"})
        @DisplayName("a correctly-sized value that is absent answers false")
        void absentButWellFormedAreaCodesAnswerFalse(final String candidate) {
            // These are three characters wide, so they are values a PIC X(3) field could actually hold.
            // The legacy condition name simply evaluates false for them, so the predicate returns false.
            assertThat(service.isValidPhoneAreaCode(candidate)).isFalse();
            assertThat(service.isValidGeneralPurposeAreaCode(candidate)).isFalse();
            assertThat(service.isValidEasilyRecognisableAreaCode(candidate)).isFalse();
        }

        @ParameterizedTest(name = "width {0} is refused")
        @ValueSource(strings = {"1", "12", "1234", "20"})
        @DisplayName("a value of the wrong width is refused, because no PIC X(3) field could hold it")
        void wrongWidthIsRefused(final String candidate) {
            // This is not a user-input path. A COBOL PIC X(3) field is always exactly three characters,
            // space-padded, so a two- or four-character value cannot arise from the screen at all. It can
            // only arise from a caller that failed to normalise to the field width, and refusing it names
            // that mistake instead of silently answering false and hiding it.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.isValidPhoneAreaCode(candidate))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("WS-US-PHONE-AREA-CODE-TO-EDIT")
                            .contains("exactly"));
        }

        @Test
        @DisplayName("null and blank are refused as field errors, which is the ELSE branch's own outcome")
        void nullAndBlankAreFieldErrors() {
            // COACTUPC.cbl:2493-2510 moves the screen field in and evaluates the condition name with no
            // blank pre-guard, so a blank state code falls to the ELSE branch and sets INPUT-ERROR plus
            // FLG-STATE-NOT-OK. A ValidationException carrying the field name is that same outcome in
            // Java - the AAP maps ValidationException onto CSSETATY's per-field error semantics - so
            // refusing here is faithful rather than stricter. What the legacy code never does is treat a
            // blank as a silent pass, and neither does this.
            for (String candidate : new String[] {null, "", "   "}) {
                assertThatExceptionOfType(ValidationException.class)
                        .as("area code %s", candidate == null ? "null" : "'" + candidate + "'")
                        .isThrownBy(() -> service.isValidPhoneAreaCode(candidate));
                assertThatExceptionOfType(ValidationException.class)
                        .isThrownBy(() -> service.isValidUsStateCode(candidate));
                assertThatExceptionOfType(ValidationException.class)
                        .isThrownBy(() -> service.isValidStateZipCodeCombination(candidate));
            }
        }

        @Test
        @DisplayName("the refusal names the COBOL argument it guards, never the value")
        void refusalNamesTheArgumentNotTheValue() {
            // A phone number and a postal code are personal data. The message must be actionable without
            // quoting the value, which is also what the logging-mask requirement demands.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.isValidUsStateCode("Q"))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("US-STATE-CODE-TO-EDIT")
                            .doesNotContain("Q"));
        }

        @Test
        @DisplayName("state codes are accepted from the table and rejected otherwise")
        void stateCodePredicate() {
            assertThat(service.isValidUsStateCode("AL")).isTrue();
            assertThat(service.isValidUsStateCode("VI")).isTrue();
            assertThat(service.isValidUsStateCode("TX")).isTrue();
            assertThat(service.isValidUsStateCode("ZZ")).isFalse();
            assertThat(service.isValidUsStateCode("al"))
                    .as("the copybook literals are upper case; no case folding is performed")
                    .isFalse();
        }

        @Test
        @DisplayName("the combination predicate takes the four-character key STRINGed in COACTUPC")
        void combinationPredicate() {
            assertThat(service.isValidStateZipCodeCombination("AA34")).isTrue();
            assertThat(service.isValidStateZipCodeCombination("WY83")).isTrue();
            assertThat(service.isValidStateZipCodeCombination("TX99")).isFalse();
        }

        @Test
        @DisplayName("the two-argument form composes the state with the first two zip digits only")
        void twoArgumentFormUsesOnlyTheFirstTwoZipDigits() {
            // 1280-EDIT-US-STATE-ZIP-CD STRINGs the state code with ACUP-NEW-CUST-ADDR-ZIP(1:2) only. The
            // last three digits carry no 88-level and are deliberately not validated, which the service
            // records in its own constant. Varying them must not change the outcome; that is the property
            // worth asserting, rather than a single happy path.
            assertThat(service.isValidStateAndZipCode("WY", "83001")).isTrue();
            assertThat(service.isValidStateAndZipCode("WY", "83999")).isTrue();
            assertThat(service.isValidStateAndZipCode("WY", "99001")).isFalse();
            assertThat(ValidationLookupService.LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED).isEqualTo(3);
        }

        @Test
        @DisplayName("a zip shorter than five characters is refused rather than padded")
        void shortZipIsRefused() {
            // Padding a short zip would fabricate a prefix and could accept an address the legacy screen
            // rejected. The preceding numeric edit guarantees five digits by the time this runs.
            assertThatExceptionOfType(ValidationException.class)
                    .isThrownBy(() -> service.isValidStateAndZipCode("WY", "83"))
                    .satisfies(thrown -> assertThat(thrown.getMessage())
                            .contains("ACUP-NEW-CUST-ADDR-ZIP")
                            .contains("at least"));
            assertThat(ValidationLookupService.MINIMUM_ZIP_CODE_LENGTH).isEqualTo(5);
        }

        @Test
        @DisplayName("a longer zip is accepted, because only the first two digits participate")
        void longerZipIsAcceptedOnItsPrefix() {
            assertThat(service.isValidStateAndZipCode("WY", "830011234")).isTrue();
        }
    }

    private static String first(final SequencedSet<String> values) {
        return values.getFirst();
    }

    private static String last(final SequencedSet<String> values) {
        return values.getLast();
    }

    private static String at(final SequencedSet<String> values, final int index) {
        return new ArrayList<>(values).get(index);
    }
}
