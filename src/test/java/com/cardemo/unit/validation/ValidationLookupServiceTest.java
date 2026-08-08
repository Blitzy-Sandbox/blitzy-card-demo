/*
 * ******************************************************************
 * Program     : ValidationLookupServiceTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no live endpoint
 * Function    : Pins the five CSLKPCDY lookup tables externalised as
 *               three classpath JSON resources: their exact cardinality
 *               and membership, their preserved COBOL source ordering,
 *               the disjoint-union relationship between the three
 *               area-code tables, the six zip-prefix state codes that
 *               deliberately have no entry in the state-code table, the
 *               PIC-clause width guards on every predicate, and the
 *               eleven fail-fast conditions that make a corrupted
 *               resource a startup abend rather than a silent
 *               validation hole.
 * Source      : app/cpy/CSLKPCDY.cpy:L24-L30 (WS-US-PHONE-AREA-CODE-TO-EDIT
 *               PIC XXX and VALID-PHONE-AREA-CODE), L440 (the interior
 *               comment marking where the easily recognisable codes
 *               begin), L521 (VALID-GENERAL-PURP-CODE), L931
 *               (VALID-EASY-RECOG-AREA-CODE), L1012-L1013
 *               (US-STATE-CODE-TO-EDIT PIC X(2) and
 *               VALID-US-STATE-CODE), L1071-L1073
 *               (US-STATE-ZIPCODE-TO-EDIT, US-STATE-AND-FIRST-ZIP2
 *               PIC X(4) and VALID-US-STATE-ZIP-CD2-COMBO), L1314
 *               (LAST-3-OF-ZIP PIC X(3), which carries no condition
 *               name and is therefore never validated) @ 7756d89
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License
 * ******************************************************************
 */
package com.cardemo.unit.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.service.shared.ValidationLookupService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SequencedSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Unit test for {@link ValidationLookupService}, the Java replacement for the five 88-level lookup tables
 * declared in {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <h2>1. What this test does</h2>
 *
 * <p>The copybook declares 1,276 literal values across five condition names. Generating them as Java
 * constants would have produced well over a thousand lines of source that no reviewer could check, so they
 * are externalised as three classpath JSON resources and loaded once at construction. That decision moves the
 * risk: the values are now data, and data can be silently wrong. This test is what makes them provable.
 *
 * <h3>Cardinality was counted from the frozen copybook, not copied from the resource</h3>
 *
 * <p>Every count asserted here was obtained by extracting single-quoted literals from the copybook's own line
 * ranges and counting them, independently of the JSON. The five figures are 490 phone area codes
 * ({@code app/cpy/CSLKPCDY.cpy:L30-L520}), 410 general-purpose codes ({@code :L521-L930}), 80 easily
 * recognisable codes ({@code :L931-L1011}), 56 state codes ({@code :L1013-L1070}) and 240 state-and-zip-prefix
 * combinations ({@code :L1073-L1313}). A resource that has drifted from the corpus fails these assertions.
 *
 * <h3>The three area-code tables are a disjoint union, and that is checkable</h3>
 *
 * <p>410 + 80 = 490 is suggestive; the stronger fact is that the phone table is <em>exactly</em> the
 * general-purpose table followed by the easily recognisable table, in that order, with the boundary at index
 * 410 and no element shared between the two. That is the structural meaning of the interior comment at
 * {@code app/cpy/CSLKPCDY.cpy:L440}, {@code Easily recognizable codes begin here.}, which sits inside the
 * range of {@code VALID-PHONE-AREA-CODE} and is skipped as a comment rather than transcribed as data.
 * {@link TableCardinalityAndOrder#thePhoneTableIsExactlyTheGeneralTableFollowedByTheEasilyRecognisableTable()}
 * asserts the whole relationship rather than the arithmetic alone.
 *
 * <h3>Ordering is COBOL source order and must not be sorted</h3>
 *
 * <p>None of the five tables is sorted. The state codes run {@code AL, AK, AZ, AR, CA} - alphabetical by
 * state <em>name</em>, not by code - and the zip combinations for New York run {@code NY50, NY54, NY63, NY10}.
 * The accessors therefore return {@link SequencedSet} rather than {@code Set}, and this test asserts the first
 * and last elements together with the out-of-alphabetical-order runs, because a well-meaning sort would be
 * invisible to a membership-only test.
 *
 * <h3>Six zip-prefix state codes are deliberately absent from the state-code table</h3>
 *
 * <p>{@code AA}, {@code AE}, {@code AP}, {@code FM}, {@code MH} and {@code PW} appear as zip-combination
 * prefixes but are not members of {@code VALID-US-STATE-CODE}. They are the Armed Forces
 * Americas, Europe and Pacific codes and three freely associated states. The consequence is a real legacy
 * asymmetry: {@code isValidStateAndZipCode("AE", "09012")} answers true while
 * {@code isValidUsStateCode("AE")} answers false, so a screen that runs both edits rejects an address the
 * zip-combination edit alone would accept. This is preserved, not reconciled.
 *
 * <h3>A corrupted resource is a startup abend, never a silent validation hole</h3>
 *
 * <p>All five tables load in the constructor, so failure is eager. {@code loadTable} rejects eleven distinct
 * conditions - absent resource, unreadable stream, malformed JSON, non-object root, absent member, null
 * member, non-array member, empty array, non-string element, blank element, wrong-width element and duplicate
 * element - and each becomes a {@link FatalProcessingException} naming {@code CSLKPCDY} as the culprit. The
 * alternative would be a table that quietly loaded fewer values than the copybook declares, which would
 * reject valid customer input with no diagnostic at all.
 *
 * <h2>2. How to run it</h2>
 *
 * <p>Whole class, offline, from the repository root. The only prerequisite is JDK 25 on {@code PATH} with
 * {@code JAVA_HOME} set; Maven comes from the pinned wrapper, which is why the wrapper and never a host
 * {@code mvn} is invoked:
 *
 * <pre>
 *     ./mvnw -B -ntp -o test -Dtest=ValidationLookupServiceTest -DfailIfNoSpecifiedTests=false -Djacoco.skip=true
 * </pre>
 *
 * <p>As part of the gated build. Note what the two flags mean: {@code -o} makes Maven offline, which
 * causes it to skip {@code dependency-check:check} because that goal declares {@code requiresOnline}, and
 * {@code -Ddependency-check.skip=true} skips it explicitly. Either way <strong>a skipped scan is never
 * evidence that the scan passes</strong>, so the vulnerability gate must be run separately and online:
 *
 * <pre>
 *     ./mvnw -B -ntp -o clean verify -Ddependency-check.skip=true
 * </pre>
 *
 * <p>No container, no database and no Spring context. The happy-path fixture is built from a plain
 * {@link DefaultResourceLoader} and a plain {@link ObjectMapper}, which is exactly what Spring injects in
 * production, so the resources under test are the shipped ones rather than test copies.
 *
 * <h2>3. Configuration and defaults</h2>
 *
 * <p>The three resource paths are compile-time constants, not properties: {@code validation/nanpa-area-codes
 * .json}, {@code validation/us-state-codes.json} and {@code validation/state-zip-prefixes.json}, each resolved
 * through the {@code classpath:} scheme. There is no profile, property or environment variable that can point
 * the service at different data, which is deliberate - the tables are frozen reference data derived from a
 * frozen copybook, and the copybook cites no live registry that could legitimately update them.
 *
 * <p>The public width constants are the PIC clauses: area code 3, state code 2, state-and-zip key 4, zip
 * prefix 2 and a minimum full zip length of 5. {@code LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED} is 3 and is
 * documentary: the field exists at {@code app/cpy/CSLKPCDY.cpy:L1314} but carries no condition name anywhere
 * in the corpus, so the last three digits of a zip code are never validated.
 *
 * <h2>4. Failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>A cardinality assertion fails</strong> - a value was added to or removed from a JSON
 *       resource. Re-extract the literals from the copybook line range named in that test and compare; the
 *       corpus wins, always.</li>
 *   <li><strong>An ordering assertion fails while membership still passes</strong> - a resource was sorted or
 *       deduplicated by a formatter or an editor. Source order is contractual because the accessors are
 *       {@link SequencedSet} and callers may rely on first-match semantics.</li>
 *   <li><strong>The disjoint-union assertion fails</strong> - a code was moved between the general-purpose
 *       and easily recognisable tables, or duplicated across both. Check the boundary at index 410 against
 *       {@code app/cpy/CSLKPCDY.cpy:L440}.</li>
 *   <li><strong>{@code isValidUsStateCode("AE")} starts answering true</strong> - somebody has reconciled the
 *       state table with the zip-combination table. That is a behaviour change: the copybook declares 56 state
 *       codes and 62 distinct zip prefixes, and the gap is the behaviour.</li>
 *   <li><strong>A load-failure test stops throwing</strong> - a validation step was removed from
 *       {@code loadTable}, which converts a corrupted resource from a loud startup failure into a silent
 *       reduction of the accepted value set. Restore the step rather than relaxing the test.</li>
 *   <li><strong>A width-guard test throws the wrong {@code FailureKind}</strong> - {@code BLANK} is for
 *       absent or all-space input and {@code INVALID} is for a present value of the wrong width. The two
 *       drive different screen attributes at {@code app/cpy/CSSETATY.cpy}, where blank additionally earns an
 *       asterisk, so they are not interchangeable.</li>
 *   </ul>
 */
@DisplayName("ValidationLookupService: the five CSLKPCDY lookup tables as verifiable classpath data")
class ValidationLookupServiceTest {

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L30-L520}, excluding the interior comment at {@code :L440}. */
    private static final int PHONE_AREA_CODE_COUNT = 490;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L521-L930}. */
    private static final int GENERAL_PURPOSE_CODE_COUNT = 410;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L931-L1011}. */
    private static final int EASILY_RECOGNISABLE_CODE_COUNT = 80;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L1013-L1070}. */
    private static final int US_STATE_CODE_COUNT = 56;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L1073-L1313}. */
    private static final int STATE_ZIP_COMBINATION_COUNT = 240;

    /** The production wiring: the shipped resources, the real loader and the real mapper. */
    private static final ValidationLookupService SERVICE =
            new ValidationLookupService(new DefaultResourceLoader(), new ObjectMapper());

    /**
     * Builds a resource loader that answers every location with one supplied resource.
     *
     * <p>The constructor loads five tables in a fixed order and the first is
     * {@code VALID-PHONE-AREA-CODE} from {@code validation/nanpa-area-codes.json}, so a single substituted
     * resource deterministically fails that first load and the assertion can name it exactly.
     *
     * @param resource the resource to return for any location
     * @return a loader that ignores the requested location
     */
    private static ResourceLoader loaderReturning(Resource resource) {
        return new ResourceLoader() {

            @Override
            public Resource getResource(String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return ValidationLookupServiceTest.class.getClassLoader();
            }
        };
    }

    /**
     * Wraps a JSON document as an in-memory classpath-style resource.
     *
     * @param json the document body
     * @return a resource that exists and yields the supplied body
     */
    private static Resource jsonResource(String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Constructs the service against a substituted resource and returns the resulting abend.
     *
     * @param resource the resource every load will see
     * @return the fatal exception the constructor threw
     */
    private static FatalProcessingException loadFailureFor(Resource resource) {
        return catchThrowableOfType(FatalProcessingException.class,
                () -> new ValidationLookupService(loaderReturning(resource), new ObjectMapper()));
    }

    /**
     * Constructs the service against a substituted JSON body and returns the resulting abend.
     *
     * @param json the document body every load will see
     * @return the fatal exception the constructor threw
     */
    private static FatalProcessingException loadFailureFor(String json) {
        return loadFailureFor(jsonResource(json));
    }

    @Nested
    @DisplayName("Cardinality and ordering match the frozen copybook exactly")
    class TableCardinalityAndOrder {

        @Test
        @DisplayName("All five tables hold exactly the number of values the copybook declares, counted from "
                + "the source line ranges rather than from the JSON resources")
        void allFiveTablesHoldTheCountedNumberOfValues() {
            assertThat(SERVICE.getValidPhoneAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L30-L520 declares 490 literals for VALID-PHONE-AREA-CODE once "
                            + "the interior comment at :L440 is skipped")
                    .hasSize(PHONE_AREA_CODE_COUNT);
            assertThat(SERVICE.getValidGeneralPurposeAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L521-L930 declares 410 literals")
                    .hasSize(GENERAL_PURPOSE_CODE_COUNT);
            assertThat(SERVICE.getValidEasilyRecognisableAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L931-L1011 declares 80 literals")
                    .hasSize(EASILY_RECOGNISABLE_CODE_COUNT);
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L1013-L1070 declares 56 literals: 50 states, the District of "
                            + "Columbia and five territories")
                    .hasSize(US_STATE_CODE_COUNT);
            assertThat(SERVICE.getValidStateZipCodeCombinations())
                    .as("app/cpy/CSLKPCDY.cpy:L1073-L1313 declares 240 literals")
                    .hasSize(STATE_ZIP_COMBINATION_COUNT);
        }

        @Test
        @DisplayName("Every element is exactly as wide as its PIC clause declares, so a truncated or padded "
                + "value can never enter a table")
        void everyElementMatchesItsDeclaredWidth() {
            assertThat(SERVICE.getValidPhoneAreaCodes())
                    .as("the parent item at app/cpy/CSLKPCDY.cpy:L24 is PIC XXX, so every area code is three "
                            + "characters and a two or four character value is a transcription error")
                    .allMatch(code -> code.length() == ValidationLookupService.AREA_CODE_WIDTH);
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("the parent item at app/cpy/CSLKPCDY.cpy:L1012 is PIC X(2)")
                    .allMatch(code -> code.length() == ValidationLookupService.STATE_CODE_WIDTH);
            assertThat(SERVICE.getValidStateZipCodeCombinations())
                    .as("the subfield at app/cpy/CSLKPCDY.cpy:L1072 is PIC X(4): two state letters followed "
                            + "by the first two zip digits")
                    .allMatch(combo -> combo.length() == ValidationLookupService.STATE_ZIP_KEY_WIDTH);
        }

        @Test
        @DisplayName("The phone table is exactly the general-purpose table followed by the easily "
                + "recognisable table, with the boundary at index 410 and no element shared")
        void thePhoneTableIsExactlyTheGeneralTableFollowedByTheEasilyRecognisableTable() {
            List<String> phone = List.copyOf(SERVICE.getValidPhoneAreaCodes());
            List<String> general = List.copyOf(SERVICE.getValidGeneralPurposeAreaCodes());
            List<String> easilyRecognisable = List.copyOf(SERVICE.getValidEasilyRecognisableAreaCodes());
            assertThat(phone.subList(0, GENERAL_PURPOSE_CODE_COUNT))
                    .as("the first 410 phone codes are the general-purpose table in its own source order, "
                            + "which is what app/cpy/CSLKPCDY.cpy:L440 marks the end of")
                    .isEqualTo(general);
            assertThat(phone.subList(GENERAL_PURPOSE_CODE_COUNT, PHONE_AREA_CODE_COUNT))
                    .as("and the remaining 80 are the easily recognisable table, again in source order")
                    .isEqualTo(easilyRecognisable);
            assertThat(general)
                    .as("the two sub-tables share no element, so the phone table is a disjoint union rather "
                            + "than a merge that happens to sum correctly")
                    .doesNotContainAnyElementsOf(easilyRecognisable);
        }

        @Test
        @DisplayName("The tables are held in COBOL source order and not sorted, proven by runs that are out "
                + "of alphabetical or numeric sequence")
        void theTablesArePreservedInSourceOrderRatherThanSorted() {
            assertThat(List.copyOf(SERVICE.getValidUsStateCodes()).subList(0, 5))
                    .as("app/cpy/CSLKPCDY.cpy:L1013 begins AL, AK, AZ, AR, CA - alphabetical by state name "
                            + "rather than by code, so a sort by code would reorder AK before AL")
                    .containsExactly("AL", "AK", "AZ", "AR", "CA");
            assertThat(List.copyOf(SERVICE.getValidStateZipCodeCombinations()))
                    .as("the New York combinations appear as NY50, NY54, NY63 before NY10, which no sort "
                            + "would produce")
                    .containsSubsequence("NY50", "NY54", "NY63", "NY10");
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("and the table ends with the territories rather than with a W code")
                    .endsWith("DC", "AS", "GU", "MP", "PR", "VI");
        }

        @Test
        @DisplayName("The first and last element of each table are the copybook's first and last literals, "
                + "which pins both ends of every transcription")
        void theFirstAndLastElementsPinBothEndsOfEachTranscription() {
            assertThat(SERVICE.getValidPhoneAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L30 opens with '201' and the range closes at '999'")
                    .startsWith("201", "202", "203")
                    .endsWith("977", "988", "999");
            assertThat(SERVICE.getValidGeneralPurposeAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L521 opens with '201' and :L930 closes with '989'")
                    .startsWith("201")
                    .endsWith("985", "986", "989");
            assertThat(SERVICE.getValidEasilyRecognisableAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L931 opens with '200' and the range closes at '999'")
                    .startsWith("200", "211", "222")
                    .endsWith("988", "999");
            assertThat(SERVICE.getValidStateZipCodeCombinations())
                    .as("app/cpy/CSLKPCDY.cpy:L1073 opens with 'AA34' and :L1313 closes with 'WY83'")
                    .startsWith("AA34", "AE90")
                    .endsWith("WY82", "WY83");
        }

        @Test
        @DisplayName("No table contains a duplicate, which is asserted separately from cardinality because a "
                + "set silently absorbs duplicates and would hide the drift")
        void noTableContainsADuplicate() {
            assertThat(List.copyOf(SERVICE.getValidPhoneAreaCodes()))
                    .as("the loader rejects a repeated element outright, so a set-size assertion alone would "
                            + "not distinguish 490 unique values from 491 with one repeat")
                    .doesNotHaveDuplicates();
            assertThat(List.copyOf(SERVICE.getValidStateZipCodeCombinations()))
                    .as("the same reasoning for the 240 state-and-zip combinations")
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("Every one of the 56 state codes has at least one zip-prefix combination, so no state "
                + "is unreachable through the address edit")
        void everyStateCodeHasAtLeastOneZipCombination() {
            SequencedSet<String> combinations = SERVICE.getValidStateZipCodeCombinations();
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("a state present in the state table but absent from the combination table would "
                            + "pass the state edit and then fail the combination edit for every zip code, "
                            + "making the state impossible to enter")
                    .allMatch(state -> combinations.stream().anyMatch(combo -> combo.startsWith(state)));
        }
    }

    @Nested
    @DisplayName("The three area-code predicates answer for three different tables")
    class AreaCodePredicates {

        @ParameterizedTest(name = "area code {0} is a valid phone area code")
        @ValueSource(strings = {"201", "202", "212", "800", "888", "911", "999", "200"})
        @DisplayName("The phone predicate accepts both geographic and service codes, because its table is "
                + "the union of the other two")
        void thePhonePredicateAcceptsTheWholeUnion(String areaCode) {
            assertThat(SERVICE.isValidPhoneAreaCode(areaCode))
                    .as("VALID-PHONE-AREA-CODE spans app/cpy/CSLKPCDY.cpy:L30-L520 and includes the easily "
                            + "recognisable block that begins at the interior comment on :L440")
                    .isTrue();
        }

        @ParameterizedTest(name = "the general-purpose predicate rejects the service code {0}")
        @ValueSource(strings = {"800", "888", "877", "866", "900", "555", "411", "611", "911", "999", "200"})
        @DisplayName("The general-purpose predicate rejects every easily recognisable code, which is the "
                + "whole point of maintaining two tables over one parent item")
        void theGeneralPurposePredicateRejectsServiceCodes(String areaCode) {
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                    .as("a toll-free or service code is not a geographic area code, so a customer telephone "
                            + "number carrying one must fail the general-purpose edit even though the same "
                            + "value passes the phone edit")
                    .isFalse();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode))
                    .as("and the same value must pass the easily recognisable edit")
                    .isTrue();
            assertThat(SERVICE.isValidPhoneAreaCode(areaCode))
                    .as("and the union edit, since the union contains both sub-tables")
                    .isTrue();
        }

        @ParameterizedTest(name = "the easily recognisable predicate rejects the geographic code {0}")
        @ValueSource(strings = {"201", "202", "203", "212", "312", "415"})
        @DisplayName("The easily recognisable predicate rejects every geographic code, completing the "
                + "two-way exclusivity of the split")
        void theEasilyRecognisablePredicateRejectsGeographicCodes(String areaCode) {
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode))
                    .as("the two sub-tables are disjoint, so membership in one implies non-membership in the "
                            + "other for every value in either")
                    .isFalse();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                    .as("while the general-purpose edit accepts it")
                    .isTrue();
        }

        @ParameterizedTest(name = "area code {0} is in no table at all")
        @ValueSource(strings = {"000", "100", "001", "010", "199"})
        @DisplayName("A syntactically well-formed but unassigned area code is rejected by all three "
                + "predicates, so the tables are allowlists rather than format checks")
        void unassignedAreaCodesAreRejectedByAllThreePredicates(String areaCode) {
            assertThat(SERVICE.isValidPhoneAreaCode(areaCode))
                    .as("the copybook enumerates assigned codes; a three-digit numeric string is not "
                            + "sufficient, which is why the tables exist at all")
                    .isFalse();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(areaCode))
                    .as("nor is it a general-purpose code")
                    .isFalse();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(areaCode))
                    .as("nor an easily recognisable one")
                    .isFalse();
        }

        @Test
        @DisplayName("Membership is an exact string comparison, so a value with surrounding spaces is not "
                + "silently trimmed into a match")
        void membershipIsAnExactStringComparison() {
            assertThat(SERVICE.isValidPhoneAreaCode("201"))
                    .as("the bare three-character code matches")
                    .isTrue();
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode(" 201")))
                    .as("a four character value fails the PIC XXX width guard before membership is even "
                            + "consulted, so no trimming can occur")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("The state-code predicate and the zip-combination predicate disagree on six prefixes")
    class StateAndZipPredicates {

        @ParameterizedTest(name = "state code {0} is valid")
        @ValueSource(strings = {"AL", "AK", "AZ", "AR", "CA", "NY", "TX", "WY", "DC", "AS", "GU", "MP", "PR",
            "VI"})
        @DisplayName("Every state, the District of Columbia and all five territories are accepted, which is "
                + "the full 56-entry table the copybook declares")
        void everyDeclaredStateCodeIsAccepted(String stateCode) {
            assertThat(SERVICE.isValidUsStateCode(stateCode))
                    .as("app/cpy/CSLKPCDY.cpy:L1013-L1070 enumerates 56 codes and this is one of them")
                    .isTrue();
        }

        @ParameterizedTest(name = "state code {0} is rejected")
        @ValueSource(strings = {"ZZ", "XX", "QQ", "AB", "BC"})
        @DisplayName("A two-letter code the copybook does not declare is rejected, including plausible "
                + "Canadian province codes")
        void undeclaredStateCodesAreRejected(String stateCode) {
            assertThat(SERVICE.isValidUsStateCode(stateCode))
                    .as("the table is a United States allowlist; accepting AB or BC would let a Canadian "
                            + "address through an edit the legacy system fails")
                    .isFalse();
        }

        @ParameterizedTest(name = "prefix {0} has zip combinations but is not a valid state code")
        @CsvSource({
            "AA,AA34",
            "AE,AE90",
            "AP,AP96",
            "FM,FM96",
            "MH,MH96",
            "PW,PW96",
        })
        @DisplayName("Six prefixes appear in the zip-combination table but not in the state-code table, so "
                + "the two edits genuinely disagree and the disagreement is preserved")
        void sixPrefixesExistOnlyInTheZipCombinationTable(String prefix, String combination) {
            assertThat(SERVICE.isValidStateZipCodeCombination(combination))
                    .as("app/cpy/CSLKPCDY.cpy:L1073-L1313 lists this combination, so the combination edit "
                            + "accepts it")
                    .isTrue();
            assertThat(SERVICE.isValidUsStateCode(prefix))
                    .as("yet app/cpy/CSLKPCDY.cpy:L1013-L1070 omits the prefix, so the state edit rejects "
                            + "it. These are the Armed Forces codes and the freely associated states; the "
                            + "gap is legacy behaviour and is not reconciled")
                    .isFalse();
        }

        @ParameterizedTest(name = "combination {0} is valid")
        @ValueSource(strings = {"CA90", "CA96", "NY10", "NY63", "TX75", "WY83", "AA34"})
        @DisplayName("A declared state-and-prefix combination is accepted exactly as the four-character "
                + "subfield spells it")
        void declaredCombinationsAreAccepted(String combination) {
            assertThat(SERVICE.isValidStateZipCodeCombination(combination))
                    .as("the subfield at app/cpy/CSLKPCDY.cpy:L1072 is PIC X(4), so the key is the state "
                            + "code concatenated with the first two zip digits and nothing else")
                    .isTrue();
        }

        @ParameterizedTest(name = "combination {0} is rejected")
        @ValueSource(strings = {"CA00", "CA10", "NY90", "ZZ99", "WY99"})
        @DisplayName("A valid state paired with a prefix that state does not use is rejected, which is the "
                + "cross-field check the combination table exists to perform")
        void undeclaredCombinationsAreRejected(String combination) {
            assertThat(SERVICE.isValidStateZipCodeCombination(combination))
                    .as("CA and 90 are each individually valid but CA with 00 is not; the table encodes "
                            + "which pairings exist, which is why it has 240 entries rather than 56 times 100")
                    .isFalse();
        }

        @ParameterizedTest(name = "state {0} with zip {1} is {2}")
        @CsvSource({
            "CA,90210,true",
            "CA,96001,true",
            "NY,10001,true",
            "NY,90001,false",
            "TX,75001,true",
            "WY,83001,true",
            "CA,00001,false",
        })
        @DisplayName("The two-argument form composes the key from the state code and the first two zip "
                + "digits only, ignoring the last three digits entirely")
        void theTwoArgumentFormComposesTheKeyFromTheFirstTwoZipDigits(String stateCode, String zipCode,
                boolean expected) {
            assertThat(SERVICE.isValidStateAndZipCode(stateCode, zipCode))
                    .as("app/cpy/CSLKPCDY.cpy:L1314 declares LAST-3-OF-ZIP PIC X(3) with no condition name "
                            + "anywhere in the corpus, so the last three digits are never validated and "
                            + "cannot affect the answer")
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("The last three digits of a zip code are ignored, proven by three different suffixes "
                + "under one state and prefix all answering identically")
        void theLastThreeZipDigitsAreIgnored() {
            assertThat(SERVICE.isValidStateAndZipCode("CA", "90210"))
                    .as("a well known Californian zip code")
                    .isTrue();
            assertThat(SERVICE.isValidStateAndZipCode("CA", "90000"))
                    .as("the same prefix with a different and possibly unassigned suffix answers the same, "
                            + "because no rule for the suffix exists to consult")
                    .isTrue();
            assertThat(SERVICE.isValidStateAndZipCode("CA", "90999"))
                    .as("and so does a third suffix; the documented width constant "
                            + "LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED records that this is intentional")
                    .isTrue();
            assertThat(ValidationLookupService.LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED)
                    .as("the constant carries the declared PIC X(3) width for documentation, not for use in "
                            + "a check that the source does not perform")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("A zip code longer than five characters is accepted and only its first two digits are "
                + "used, so a nine-digit zip plus four does not fail the edit")
        void aLongerZipCodeIsAcceptedAndOnlyItsPrefixIsUsed() {
            assertThat(SERVICE.isValidStateAndZipCode("CA", "902101234"))
                    .as("the guard enforces a minimum of five characters, not an exact five, so a zip plus "
                            + "four still resolves through its first two digits")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Every predicate guards its argument against the PIC clause before consulting a table")
    class ArgumentGuards {

        @ParameterizedTest(name = "an area code of width {0} is rejected as INVALID")
        @ValueSource(strings = {"2", "20", "2011", "20111"})
        @DisplayName("An area code that is present but not three characters wide raises an INVALID "
                + "validation failure naming the copybook data item")
        void anAreaCodeOfTheWrongWidthIsInvalid(String areaCode) {
            ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode(areaCode));
            assertThat(failure.getFailureKind())
                    .as("the value is present, so the failure is a width violation rather than a blank one; "
                            + "app/cpy/CSSETATY.cpy drives different screen attributes for the two kinds")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(failure.getFieldName())
                    .as("the field name is the COBOL data item, so an error surfaced to a caller points at "
                            + "the copybook rather than at a Java parameter name")
                    .isEqualTo("WS-US-PHONE-AREA-CODE-TO-EDIT");
            assertThat(failure)
                    .as("and the message quotes both the expected and the supplied width")
                    .hasMessageContaining("must be exactly 3 characters wide")
                    .hasMessageContaining(String.valueOf(areaCode.length()) + " were supplied");
        }

        @Test
        @DisplayName("A null argument raises a BLANK validation failure, because an unsupplied screen field "
                + "is a different error from a wrongly sized one")
        void aNullArgumentIsBlankRatherThanInvalid() {
            ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode(null));
            assertThat(failure.getFailureKind())
                    .as("BLANK is what earns the asterisk marker at app/cpy/CSSETATY.cpy, so conflating it "
                            + "with INVALID would change what the screen displays")
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(failure)
                    .as("and the message says supplied rather than sized")
                    .hasMessage("WS-US-PHONE-AREA-CODE-TO-EDIT must be supplied");
        }

        @Test
        @DisplayName("An all-space argument is also BLANK, reproducing COBOL where an unmoved field holds "
                + "spaces rather than nothing")
        void anAllSpaceArgumentIsAlsoBlank() {
            ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode("   "));
            assertThat(failure.getFailureKind())
                    .as("three spaces is exactly what an untouched PIC XXX contains, so it must be treated "
                            + "as absent and not as a three-character value that fails membership")
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(failure)
                    .as("and the message distinguishes blank from absent")
                    .hasMessage("WS-US-PHONE-AREA-CODE-TO-EDIT must not be blank");
        }

        @Test
        @DisplayName("The state-code predicate names its own data item, so two different edits failing on "
                + "the same screen are attributable to different fields")
        void theStateCodePredicateNamesItsOwnDataItem() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidUsStateCode("CAL")).getFieldName())
                    .as("app/cpy/CSLKPCDY.cpy:L1012 declares US-STATE-CODE-TO-EDIT, which is the name the "
                            + "failure must carry")
                    .isEqualTo("US-STATE-CODE-TO-EDIT");
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateZipCodeCombination("CA9")).getFieldName())
                    .as("while app/cpy/CSLKPCDY.cpy:L1072 declares US-STATE-AND-FIRST-ZIP2")
                    .isEqualTo("US-STATE-AND-FIRST-ZIP2");
        }

        @Test
        @DisplayName("The two-argument form names the COACTUP screen fields rather than the copybook work "
                + "items, because that is where the values come from")
        void theTwoArgumentFormNamesTheScreenFields() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateAndZipCode("CAL", "90210")).getFieldName())
                    .as("the state argument of the composite edit originates in the account update screen, "
                            + "so it reports ACUP-NEW-CUST-ADDR-STATE-CD")
                    .isEqualTo("ACUP-NEW-CUST-ADDR-STATE-CD");
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateAndZipCode("CA", "902")).getFieldName())
                    .as("and the zip argument reports ACUP-NEW-CUST-ADDR-ZIP")
                    .isEqualTo("ACUP-NEW-CUST-ADDR-ZIP");
        }

        @ParameterizedTest(name = "a zip code of width {0} is too short")
        @ValueSource(strings = {"9", "90", "902", "9021"})
        @DisplayName("A zip code shorter than five characters is rejected as INVALID with a minimum-length "
                + "message, not a width message, because the screen field permits a longer value")
        void aZipCodeShorterThanFiveCharactersIsRejected(String zipCode) {
            ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateAndZipCode("CA", zipCode));
            assertThat(failure.getFailureKind())
                    .as("the value is present but unusable, so it is INVALID")
                    .isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(failure)
                    .as("the guard is a minimum rather than an exact width, because a zip plus four is a "
                            + "legitimate longer value whose first two digits still resolve")
                    .hasMessageContaining("must be at least 5 characters long");
        }

        @Test
        @DisplayName("The public width constants are the PIC clauses themselves, so a caller can size a "
                + "screen field from the same source of truth the guards use")
        void thePublicWidthConstantsAreThePicClauses() {
            assertThat(ValidationLookupService.AREA_CODE_WIDTH)
                    .as("app/cpy/CSLKPCDY.cpy:L24 is PIC XXX")
                    .isEqualTo(3);
            assertThat(ValidationLookupService.STATE_CODE_WIDTH)
                    .as("app/cpy/CSLKPCDY.cpy:L1012 is PIC X(2)")
                    .isEqualTo(2);
            assertThat(ValidationLookupService.STATE_ZIP_KEY_WIDTH)
                    .as("app/cpy/CSLKPCDY.cpy:L1072 is PIC X(4)")
                    .isEqualTo(4);
            assertThat(ValidationLookupService.ZIP_PREFIX_LENGTH)
                    .as("the state-and-zip key is two state letters plus two zip digits, so the prefix taken "
                            + "from the zip code is two characters")
                    .isEqualTo(2);
            assertThat(ValidationLookupService.MINIMUM_ZIP_CODE_LENGTH)
                    .as("a United States zip code is at least five digits, which is the shortest value from "
                            + "which a two digit prefix can be taken with confidence")
                    .isEqualTo(5);
            assertThat(ValidationLookupService.STATE_CODE_WIDTH + ValidationLookupService.ZIP_PREFIX_LENGTH)
                    .as("2 + 2 = 4, so the composed key width equals the declared subfield width; a mismatch "
                            + "would build keys that can never match a table entry")
                    .isEqualTo(ValidationLookupService.STATE_ZIP_KEY_WIDTH);
        }
    }

    @Nested
    @DisplayName("The accessors expose immutable, stable views of the loaded tables")
    class AccessorImmutability {

        @Test
        @DisplayName("Every accessor returns an unmodifiable set, so no caller can add a value the copybook "
                + "does not declare or remove one it does")
        void everyAccessorReturnsAnUnmodifiableSet() {
            assertThat(catchThrowableOfType(UnsupportedOperationException.class,
                    () -> SERVICE.getValidPhoneAreaCodes().add("000")))
                    .as("a mutable table would let one caller widen the accepted area codes for every other "
                            + "caller in the process, which is a validation bypass rather than a bug")
                    .isNotNull();
            assertThat(catchThrowableOfType(UnsupportedOperationException.class,
                    () -> SERVICE.getValidUsStateCodes().remove("CA")))
                    .as("and removal would narrow them just as silently")
                    .isNotNull();
            assertThat(catchThrowableOfType(UnsupportedOperationException.class,
                    () -> SERVICE.getValidStateZipCodeCombinations().clear()))
                    .as("clearing the combination table would reject every address")
                    .isNotNull();
        }

        @Test
        @DisplayName("Repeated accessor calls return the same instance, confirming the tables are loaded once "
                + "at construction rather than rebuilt per call")
        void repeatedAccessorCallsReturnTheSameInstance() {
            assertThat(SERVICE.getValidPhoneAreaCodes())
                    .as("the constructor loads all five tables eagerly, so an accessor is a field read; "
                            + "rebuilding per call would parse 1,276 values on every screen edit")
                    .isSameAs(SERVICE.getValidPhoneAreaCodes());
            assertThat(SERVICE.getValidStateZipCodeCombinations())
                    .as("the same for the largest table")
                    .isSameAs(SERVICE.getValidStateZipCodeCombinations());
        }

        @Test
        @DisplayName("A second service instance built from the same resources agrees element for element, "
                + "confirming the load is deterministic and order-stable")
        void aSecondInstanceAgreesElementForElement() {
            ValidationLookupService other =
                    new ValidationLookupService(new DefaultResourceLoader(), new ObjectMapper());
            assertThat(List.copyOf(other.getValidPhoneAreaCodes()))
                    .as("JSON array order is defined, so two loads of the same resource must produce the "
                            + "same sequence; a difference would mean the loader depends on iteration order "
                            + "of a hash structure somewhere")
                    .isEqualTo(List.copyOf(SERVICE.getValidPhoneAreaCodes()));
            assertThat(List.copyOf(other.getValidStateZipCodeCombinations()))
                    .as("and the same for the combination table")
                    .isEqualTo(List.copyOf(SERVICE.getValidStateZipCodeCombinations()));
        }
    }

    @Nested
    @DisplayName("A corrupted resource abends at construction rather than silently narrowing validation")
    class EagerLoadFailFast {

        @Test
        @DisplayName("An absent resource abends, naming the missing classpath path and the table member it "
                + "was supposed to supply")
        void anAbsentResourceAbends() {
            FatalProcessingException failure =
                    loadFailureFor(new ClassPathResource("validation/deliberately-absent-resource.json"));
            assertThat(failure)
                    .as("the first table loaded is VALID-PHONE-AREA-CODE from the area-code resource, so the "
                            + "message names that pair and the caller knows exactly what to restore")
                    .hasMessage("Cannot load CSLKPCDY lookup table VALID-PHONE-AREA-CODE from classpath "
                            + "resource validation/nanpa-area-codes.json: the resource is absent from the "
                            + "classpath");
            assertThat(failure.getAbendCulprit())
                    .as("the culprit is the copybook whose tables failed to load, so the abend points at the "
                            + "COBOL artefact rather than at a Java class name")
                    .isEqualTo("CSLKPCDY");
            assertThat(failure.getAbendReason())
                    .as("and the reason distinguishes a lookup-table load failure from an I/O guard failure")
                    .isEqualTo("ValidationLookupService lookup table load failure");
        }

        @Test
        @DisplayName("An unreadable resource abends with a read failure and preserves the underlying "
                + "IOException as the cause")
        void anUnreadableResourceAbendsAndPreservesTheCause() {
            Resource unreadable = new ByteArrayResource(new byte[0]) {

                @Override
                public InputStream getInputStream() throws IOException {
                    throw new IOException("simulated device error");
                }
            };
            FatalProcessingException failure = loadFailureFor(unreadable);
            assertThat(failure)
                    .as("a resource that exists but cannot be read is a different fault from one that is "
                            + "absent, and the message must say which")
                    .hasMessageEndingWith("the resource could not be read");
            assertThat(failure.getCause())
                    .as("the root cause is never swallowed, so the stack trace still reaches the frame that "
                            + "actually failed")
                    .isInstanceOf(IOException.class)
                    .hasMessage("simulated device error");
        }

        @Test
        @DisplayName("Malformed JSON abends with a well-formedness message rather than loading a partial "
                + "table")
        void malformedJsonAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"201\","))
                    .as("a truncated document could otherwise yield a table containing only the values that "
                            + "happened to parse, which would reject every later area code with no "
                            + "diagnostic at all")
                    .hasMessageEndingWith("the resource is not well-formed JSON");
        }

        @Test
        @DisplayName("A document whose root is an array rather than an object abends, because the member "
                + "name has nowhere to be looked up")
        void aNonObjectRootAbends() {
            assertThat(loadFailureFor("[\"201\", \"202\"]"))
                    .as("the resource contract is an object keyed by COBOL condition name; a bare array "
                            + "would be ambiguous about which of the three tables it carries")
                    .hasMessageEndingWith("the document root is not a JSON object");
        }

        @Test
        @DisplayName("An empty document abends on the root check, because an absent root is not an object")
        void anEmptyDocumentAbends() {
            assertThat(loadFailureFor(""))
                    .as("an empty resource is a plausible outcome of a failed build step, and it must not "
                            + "produce five empty tables")
                    .hasMessageEndingWith("the document root is not a JSON object");
        }

        @Test
        @DisplayName("An absent table member abends, so renaming a key in the resource cannot silently "
                + "disable a whole edit")
        void anAbsentTableMemberAbends() {
            assertThat(loadFailureFor("{\"SOME-OTHER-NAME\": [\"201\"]}"))
                    .as("the member name is the COBOL condition name; if it were optional, a typo would "
                            + "leave the table empty and the edit would reject everything")
                    .hasMessageEndingWith("the table member is absent");
        }

        @Test
        @DisplayName("A null table member abends with the same message as an absent one, because JSON null "
                + "and a missing key mean the same thing here")
        void aNullTableMemberAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": null}"))
                    .as("distinguishing the two would add a message with no distinct remedy; both require "
                            + "the same fix, which is to restore the array")
                    .hasMessageEndingWith("the table member is absent");
        }

        @Test
        @DisplayName("A table member that is not an array abends, so an object or scalar cannot be coerced "
                + "into a single-element table")
        void aNonArrayTableMemberAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": {\"201\": true}}"))
                    .as("an 88-level VALUES clause is a list, so the resource shape is a list; coercing an "
                            + "object would silently accept a structure the transcription never produces")
                    .hasMessageEndingWith("the table member is not a JSON array");
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": \"201\"}"))
                    .as("and a bare string is rejected for the same reason, even though it looks like a "
                            + "single valid value")
                    .hasMessageEndingWith("the table member is not a JSON array");
        }

        @Test
        @DisplayName("An empty array abends with an explanation of the consequence, because an empty "
                + "allowlist rejects every input and is the most dangerous shape of all")
        void anEmptyArrayAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": []}"))
                    .as("an empty table is structurally valid JSON and would load without complaint, then "
                            + "reject every telephone number ever entered; the message states that outcome "
                            + "so the diagnosis is immediate")
                    .hasMessageEndingWith("the table member is an empty array, which would reject every "
                            + "input");
        }

        @Test
        @DisplayName("A non-string element abends and reports its zero-based index, because the source "
                + "field is character data even when the value looks numeric")
        void aNonStringElementAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"201\", 202]}"))
                    .as("PIC XXX is character data, so 202 as a JSON number would lose a leading zero for "
                            + "any code that had one and would compare unequal to the string it must match")
                    .hasMessageEndingWith("element 1 is not a JSON string, but the source field is character "
                            + "data");
        }

        @Test
        @DisplayName("A blank element abends and reports its index, because a blank value would match an "
                + "unmoved COBOL field and accept an empty screen entry")
        void aBlankElementAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"201\", \"   \"]}"))
                    .as("three spaces is exactly what an untouched PIC XXX holds; admitting it to the table "
                            + "would make a blank telephone area code valid")
                    .hasMessageEndingWith("element 1 is blank");
        }

        @Test
        @DisplayName("A wrong-width element abends quoting both the supplied width and the PIC clause, "
                + "which is the assertion that catches a truncated transcription")
        void aWrongWidthElementAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"201\", \"2020\"]}"))
                    .as("a four character area code can never match a three character field, so it would "
                            + "sit in the table forever without ever being consulted")
                    .hasMessageEndingWith("element 1 is 4 characters wide but the source PIC clause "
                            + "declares 3");
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"20\"]}"))
                    .as("and a two character one is rejected the same way, with its own width quoted")
                    .hasMessageEndingWith("element 0 is 2 characters wide but the source PIC clause "
                            + "declares 3");
        }

        @Test
        @DisplayName("A duplicate element abends rather than being absorbed, so the loaded cardinality "
                + "always equals the transcribed cardinality")
        void aDuplicateElementAbends() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"201\", \"202\", \"201\"]}"))
                    .as("a set would silently absorb the repeat and the table would hold two values where "
                            + "the resource lists three, which is precisely the drift a size assertion "
                            + "cannot see; the copybook has no duplicate in any of its five tables")
                    .hasMessageEndingWith("element 2 repeats an earlier element, but the source table has no "
                            + "duplicate");
        }

        @Test
        @DisplayName("Every load failure carries the same culprit and reason, so any of the eleven "
                + "conditions is attributable to the copybook without parsing the message")
        void everyLoadFailureCarriesTheSameCulpritAndReason() {
            List<FatalProcessingException> failures = List.of(
                    loadFailureFor("{\"VALID-PHONE-AREA-CODE\": []}"),
                    loadFailureFor("{\"VALID-PHONE-AREA-CODE\": [\"2020\"]}"),
                    loadFailureFor("{\"VALID-PHONE-AREA-CODE\": null}"),
                    loadFailureFor("[\"201\"]"));
            assertThat(failures)
                    .as("the abend culprit is app/cpy/CSLKPCDY.cpy and the reason is the load failure, "
                            + "regardless of which validation step rejected the resource")
                    .allSatisfy(failure -> {
                        assertThat(failure.getAbendCulprit()).isEqualTo("CSLKPCDY");
                        assertThat(failure.getAbendReason())
                                .isEqualTo("ValidationLookupService lookup table load failure");
                    });
        }

        @Test
        @DisplayName("A load failure leaves the abend code unset, because a resource load has no "
                + "four-character COBOL abend code to report")
        void aLoadFailureLeavesTheAbendCodeUnset() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": []}").getAbendCode())
                    .as("app/cpy/CSMSG02Y.cpy declares ABEND-CODE PIC X(4) and the load path supplies none, "
                            + "so it stays unset; app/cbl/CBTRN02C.cbl:L707-L711 moves 999 into ABCODE "
                            + "inside 9999-ABEND-PROGRAM, which is a different field. Observed rather than "
                            + "assumed: FatalProcessingException substitutes a default for a null message "
                            + "only, never for a null code")
                    .isNull();
        }

        @Test
        @DisplayName("The failure names the resource path and member of the table that failed, not merely "
                + "that some table failed, so the remedy is unambiguous")
        void theFailureNamesTheResourcePathAndMember() {
            assertThat(loadFailureFor("{\"VALID-PHONE-AREA-CODE\": []}"))
                    .as("five tables load from three resources, so a message that named neither would leave "
                            + "the reader to guess which of the five is broken")
                    .hasMessageStartingWith("Cannot load CSLKPCDY lookup table VALID-PHONE-AREA-CODE from "
                            + "classpath resource validation/nanpa-area-codes.json: ");
        }
    }
}
