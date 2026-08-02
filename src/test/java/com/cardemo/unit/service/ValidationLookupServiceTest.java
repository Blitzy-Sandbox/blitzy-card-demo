/*
 * ******************************************************************
 * Program     : ValidationLookupServiceTest.java
 * Application : CardDemo
 * Type        : JUnit 5 unit test - Java 25 / Spring Boot 3.5.11
 * Function    : Proves the BEAN BEHAVIOUR of ValidationLookupService -
 *               the five-tables-over-three-resources collapse, its
 *               constructor injection, its load-once immutability, its
 *               fail-fast refusal of a defective resource, its lookup
 *               misses as return values, its deliberate NON-enforcement
 *               of state-code membership, and its startup size log.
 * Source      : app/cpy/CSLKPCDY.cpy         (the five 88-level tables)
 *               app/cpy/CVCUS01Y.cpy         (customer column arithmetic)
 *               app/cbl/COACTUPC.cbl         (the only program that
 *                                             COPYs CSLKPCDY)
 *               app/cbl/CBACT04C.cbl:1-21    (this banner's canonical
 *                                             21-line form)
 *               all frozen at commit 7756d89
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
package com.cardemo.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cardemo.exception.FatalProcessingException;
import com.cardemo.exception.ValidationException;
import com.cardemo.service.shared.ValidationLookupService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SequencedSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Bean-behaviour specification for {@link ValidationLookupService}, the migration's <em>second collapse
 * rule</em>: one injected bean serving the five {@code 88}-level lookup tables of the frozen copybook
 * {@code app/cpy/CSLKPCDY.cpy} from exactly three classpath JSON resources.
 *
 * <h2>1. What it does</h2>
 *
 * <p>This class asserts how the <strong>bean</strong> behaves - what it is wired to, when it reads, what it
 * hands back, and how it refuses - rather than re-enumerating the reference data it serves. Every figure
 * below was re-derived from the frozen source by delimiter-based extraction and is quoted with its locator.
 *
 * <p>{@code app/cpy/CSLKPCDY.cpy} is 1318 lines and 51399 bytes of pure data: three {@code 01} items, two
 * {@code 02} items and five {@code 88} condition names, with no {@code PROCEDURE DIVISION} anywhere.
 *
 * <ul>
 *   <li>{@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} at {@code :L24}, preceded by the provenance
 *       comment at {@code :L26-L28} that cites a numbering-plan report. That citation is
 *       <strong>reference only and is never fetched</strong>: this tier opens no network connection.</li>
 *   <li>{@code 88 VALID-PHONE-AREA-CODE} at {@code :L30}, ending {@code '999'.} at {@code :L520} - 490
 *       members, three characters each.</li>
 *   <li>{@code 88 VALID-GENERAL-PURP-CODE} at {@code :L521}, ending {@code '989'.} at {@code :L930} - 410
 *       members.</li>
 *   <li>{@code 88 VALID-EASY-RECOG-AREA-CODE} at {@code :L931}, ending {@code '999'.} at {@code :L1010} -
 *       80 members. Its head line carries {@code VALUES} followed by <em>two</em> spaces, severity
 *       <strong>Low</strong>.</li>
 *   <li>{@code 01 US-STATE-CODE-TO-EDIT  PIC X(2)} at {@code :L1012}, with two spaces before {@code PIC},
 *       severity <strong>Low</strong>. Its {@code 88 VALID-US-STATE-CODE} at {@code :L1013} ends
 *       {@code 'VI'.} at {@code :L1069} - 56 members, two characters each.</li>
 *   <li>{@code 01 US-STATE-ZIPCODE-TO-EDIT.} at {@code :L1071} splits into
 *       {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} at {@code :L1072}, whose
 *       {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at {@code :L1073} ends {@code 'WY83'.} at {@code :L1313} -
 *       240 members - and {@code 02 LAST-3-OF-ZIP PIC X(3)} at {@code :L1314}, which carries no
 *       {@code 88} level here or anywhere in the corpus and is therefore <strong>never validated</strong>.
 *       See section 5.</li>
 * </ul>
 *
 * <p>490 plus 410 plus 80 plus 56 plus 240 is 1276, which is exactly the number of single-quoted literals
 * in the file. Three interior comments punctuate the data and must be skipped when counting:
 * {@code :L440} {@code *Easily recognizable codes begin here.}, {@code :L1011}
 * {@code *Search list of valid Phone area codes} - <strong>misplaced</strong>, since it sits above the
 * <em>state</em> table rather than a phone table, severity <strong>Low</strong> - and {@code :L1070}
 * {@code *State Zip Code Combinations}, which is the comment {@code :L1011} should have been.
 *
 * <p><strong>Customer column arithmetic.</strong> {@code app/cpy/CVCUS01Y.cpy} declares field widths 9, 25,
 * 25, 25, 50, 50, 50, <strong>2</strong>, 3, 10, 15, 15, 9, 20, <strong>10</strong>, 10, 1, 3, 168, whose
 * cumulative ends are 9, 34, 59, 84, 134, 184, 234, <strong>236</strong>, 239, 249, 264, 279, 288, 308,
 * <strong>318</strong>, 328, 329, 332, <strong>500</strong>. So {@code CUST-ADDR-STATE-CD} occupies columns
 * 235-236 of each 500-byte record. {@code app/data/ASCII/custdata.txt} holds 50 such records and carries
 * four state codes the 56-entry table omits - {@code AP} once, {@code FM} twice, {@code MH} once and
 * {@code PW} once, so <strong>five of its fifty rows</strong> would fail a membership check. That is why
 * section 4 asserts the bean does <em>not</em> enforce state membership. {@code CUST-SSN} at columns
 * 280-288 and {@code CUST-DOB-YYYY-MM-DD} at columns 309-318 are personally identifying; this class
 * reasons about them by column arithmetic only and never reads, echoes or logs either, and it never
 * trims a fixed-width record.
 *
 * <h2>2. How to build, run and test it</h2>
 *
 * <p>{@code ./mvnw -B clean test} runs this tier; {@code ./mvnw -B clean verify} adds the coverage floor.
 * <strong>Surefire</strong> 3.5.4 binds it, because the plugin includes {@code **}{@code /*Test.java} and
 * excludes only {@code **}{@code /integration/**} and {@code **}{@code /e2e/**}; a class placed outside
 * {@code src/test/java/com/cardemo/unit/} would be collected by neither Surefire nor Failsafe and would
 * silently never run. Compilation is Java 25 with {@code release 25}, no preview features, and
 * {@code -Xlint:all -Werror} with {@code failOnWarning}, which reaches test compilation - so a single
 * unused import fails the build rather than warning. To run this class alone:
 * {@code ./mvnw -B test -Dtest=ValidationLookupServiceTest}, which also selects the complementary class
 * named in section 6 because the two share a simple name across two packages.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <p>There is none to set. The bean is reached only through its single constructor,
 * {@code (ResourceLoader, ObjectMapper)}, and the three classpath locations are compile-time constants of
 * the service: {@code validation/nanpa-area-codes.json}, {@code validation/us-state-codes.json} and
 * {@code validation/state-zip-prefixes.json}. All five tables are read <strong>once</strong> during
 * construction into unmodifiable, source-ordered {@link SequencedSet} instances, so the accessors hand back
 * the same instance on every call and a lookup never re-reads a resource. Each document is a JSON object
 * whose first member is {@code _metadata}; the service reads only the five members it names, so
 * {@code _metadata} is <strong>ignored by name, never by blanket unknown-property tolerance</strong>. This
 * tier needs no clock, no fixture loader, no container, no Spring context, no database and no HTTP client.
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><em>A single unused import.</em> {@code -Werror} turns it into a build failure. <strong>Remedy:</strong>
 *       remove it; do not silence the lint.</li>
 *   <li><em>A silently-empty table</em>, severity <strong>Blocker</strong>. An empty table rejects every
 *       telephone number and every address while reporting success. The service must abend at construction
 *       instead, and section 7 proves it does. <strong>Remedy:</strong> never rescue a load failure into an
 *       empty collection.</li>
 *   <li><em>Column-based extraction from the copybook</em>, severity <strong>Blocker</strong>. 1033 of the
 *       1318 lines contain tabs and there are three indentation styles: the {@code VALID-PHONE-AREA-CODE},
 *       {@code VALID-GENERAL-PURP-CODE} and {@code VALID-EASY-RECOG-AREA-CODE} continuations begin with two
 *       tabs, {@code VALID-US-STATE-CODE} with one space then two tabs, and
 *       {@code VALID-US-STATE-ZIP-CD2-COMBO} with pure spaces. <strong>Remedy:</strong> extract by
 *       delimiter, matching quoted literals, and skip every line whose column 7 is {@code *} - the banner
 *       at {@code :L1-L22}, the provenance at {@code :L25-L29}, the interior comments at {@code :L440},
 *       {@code :L1011} and {@code :L1070}, and the version stamp at {@code :L1315-L1317}.</li>
 *   <li><em>Sorting any table</em>, severity <strong>High</strong>. Neither the phone table, the state table
 *       nor the zip table is sorted, and each carries index checkpoints that a sort destroys. Sections 3
 *       and 4 pin them.</li>
 *   <li><em>Altering the six-extras asymmetry</em>, severity <strong>Blocker</strong>. The zip table uses 62
 *       prefixes and the state table declares 56; the difference is exactly {@code AA}, {@code AE},
 *       {@code AP}, {@code FM}, {@code MH}, {@code PW}. Adding them to the state table, or deleting their
 *       combinations from the zip table, breaks parity in opposite directions.</li>
 *   <li><em>Enforcing state membership against the fixtures</em>, severity <strong>Blocker</strong>, for the
 *       reason given in section 1.</li>
 * </ul>
 *
 * <h2>5. The one retained parity artefact</h2>
 *
 * <p>{@code 02 LAST-3-OF-ZIP PIC X(3)} at {@code app/cpy/CSLKPCDY.cpy:L1314} is declared and never
 * validated. Rule 1 Clause B forbids <em>untracked</em> dead code; this artefact is tracked, cited and
 * justified, so parity governs and the field is retained rather than invented away. Its non-consultation is
 * asserted as behaviour in section 4 and it is recorded in {@code DECISION_LOG.md}. Severity
 * <strong>Low</strong>. As measured, {@code DECISION_LOG.md} is <em>Not available</em> in this tree; what
 * is needed is that file, and this Javadoc together with the service's own is the record until it exists.
 *
 * <h2>6. Scope boundary - the complementary class</h2>
 *
 * <p>The <strong>data contract</strong> - the exhaustive 1276-literal membership, the per-value predicate
 * sweeps and the full catalogue of resource defects - belongs to
 * {@code com.cardemo.unit.validation.ValidationLookupServiceTest}. The two are complementary and neither
 * substitutes for the other: that class proves the tables <em>hold the right values</em>, this one proves
 * the bean <em>is wired, loads once, refuses loudly and answers misses with a return value</em>. Rule 1
 * Clause C asks that duplication be avoided, so the five cardinalities and the cross-resource asymmetry
 * appear here only because they are wiring facts, and the 1276 literals are not re-enumerated. The plan for
 * this file named that counterpart {@code LookupTableResourceContractTest}; as measured, no file of that
 * name exists in the tree, so the real path above is cited instead.
 */
@DisplayName("ValidationLookupService: bean behaviour - five CSLKPCDY tables over exactly three resources")
class ValidationLookupServiceTest {

    /** {@code 88 VALID-PHONE-AREA-CODE}, {@code app/cpy/CSLKPCDY.cpy:L30-L520}. */
    private static final int PHONE_AREA_CODES = 490;

    /** {@code 88 VALID-GENERAL-PURP-CODE}, {@code app/cpy/CSLKPCDY.cpy:L521-L930}. */
    private static final int GENERAL_PURPOSE_AREA_CODES = 410;

    /** {@code 88 VALID-EASY-RECOG-AREA-CODE}, {@code app/cpy/CSLKPCDY.cpy:L931-L1010}. */
    private static final int EASILY_RECOGNISABLE_AREA_CODES = 80;

    /** {@code 88 VALID-US-STATE-CODE}, {@code app/cpy/CSLKPCDY.cpy:L1013-L1069}. */
    private static final int US_STATE_CODES = 56;

    /** {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, {@code app/cpy/CSLKPCDY.cpy:L1073-L1313}. */
    private static final int STATE_ZIP_COMBINATIONS = 240;

    /** Single-quoted literals in {@code app/cpy/CSLKPCDY.cpy}, which the five tables must exhaust. */
    private static final int COPYBOOK_LITERAL_COUNT = 1276;

    /** Distinct two-character prefixes used by {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}. */
    private static final int STATE_ZIP_PREFIXES = 62;

    /** The location the service asks for first, carrying three of the five tables. */
    private static final String AREA_CODES_LOCATION = "classpath:validation/nanpa-area-codes.json";

    /** The location carrying {@code 88 VALID-US-STATE-CODE}. */
    private static final String STATE_CODES_LOCATION = "classpath:validation/us-state-codes.json";

    /** The location carrying {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}. */
    private static final String STATE_ZIP_LOCATION = "classpath:validation/state-zip-prefixes.json";

    /** Resource path, without the scheme, as the service quotes it in a load failure. */
    private static final String STATE_CODES_PATH = "validation/us-state-codes.json";

    /** The JSON member name the service reads from {@link #STATE_CODES_PATH}. */
    private static final String STATE_CODE_MEMBER = "VALID-US-STATE-CODE";

    /**
     * The six prefixes the zip table uses that {@code 88 VALID-US-STATE-CODE} does not declare: the three
     * military and diplomatic mail designations and the three Freely Associated States.
     */
    private static final List<String> ZIP_ONLY_PREFIXES = List.of("AA", "AE", "AP", "FM", "MH", "PW");

    /**
     * The four state codes {@code app/data/ASCII/custdata.txt} carries at columns 235-236 that the 56-entry
     * table omits, covering five of its fifty records.
     */
    private static final List<String> FIXTURE_NON_MEMBER_STATE_CODES = List.of("AP", "FM", "MH", "PW");

    /** Every {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} value is two letters then two digits. */
    private static final Pattern STATE_ZIP_KEY = Pattern.compile("^[A-Z]{2}[0-9]{2}$");

    /**
     * One instance shared by every test, built from the real classpath through the real resource loader.
     *
     * <p>Declared {@code final} so that this class holds no mutable static state, which Rule 1 Clause B
     * requires. Loading is the expensive part and the service is immutable, so sharing is safe; and because
     * construction is what fails when a resource is missing, a defect surfaces in every test at once, which
     * is the right blast radius for a startup-fatal condition.
     */
    private static final ValidationLookupService SERVICE =
            new ValidationLookupService(new DefaultResourceLoader(), new ObjectMapper());

    /**
     * Returns a table as an indexable list, preserving the encounter order the service guarantees.
     *
     * @param table the source-ordered table to index; never {@code null}
     * @return an immutable list in the table's own order
     */
    private static List<String> ordered(final SequencedSet<String> table) {
        return List.copyOf(table);
    }

    /**
     * Returns the distinct two-character prefixes of the zip table, in first-appearance order.
     *
     * <p>A {@link LinkedHashSet} is used rather than a hash set because these assertions are about order as
     * well as membership, and Rule 1 Clause A forbids depending on hash iteration order.
     *
     * @return the prefixes in the order the zip table introduces them
     */
    private static SequencedSet<String> zipPrefixes() {
        final LinkedHashSet<String> prefixes = new LinkedHashSet<>();
        for (final String combination : SERVICE.getValidStateZipCodeCombinations()) {
            prefixes.add(combination.substring(0, ValidationLookupService.STATE_CODE_WIDTH));
        }
        return prefixes;
    }

    /**
     * Returns every value the service loaded, across all five tables.
     *
     * @return the union of the five tables, used to prove no member value is ever logged
     */
    private static Set<String> everyLoadedValue() {
        final Set<String> values = new LinkedHashSet<>(SERVICE.getValidPhoneAreaCodes());
        values.addAll(SERVICE.getValidGeneralPurposeAreaCodes());
        values.addAll(SERVICE.getValidEasilyRecognisableAreaCodes());
        values.addAll(SERVICE.getValidUsStateCodes());
        values.addAll(SERVICE.getValidStateZipCodeCombinations());
        return values;
    }

    /**
     * Wraps a JSON document as a readable classpath-substitute resource.
     *
     * @param json the document text, which may be deliberately malformed
     * @return a resource that reports itself present and yields exactly that text
     */
    private static Resource jsonResource(final String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Builds the service with one location substituted, and returns the load failure it raises.
     *
     * @param location the {@code classpath:} location to substitute
     * @param substitute the resource to hand back for that location
     * @return the raised failure, never {@code null}
     */
    private static FatalProcessingException loadFailure(final String location, final Resource substitute) {
        final ResourceLoader loader = new SubstitutingResourceLoader(location, substitute);
        final ObjectMapper mapper = new ObjectMapper();
        return catchThrowableOfType(FatalProcessingException.class,
                () -> new ValidationLookupService(loader, mapper));
    }

    /**
     * Builds the service with the state-code resource replaced by the given document.
     *
     * @param json the substitute document text
     * @return the constructed service, whose state table comes from {@code json}
     */
    private static ValidationLookupService serviceWithStateCodeDocument(final String json) {
        return new ValidationLookupService(new SubstitutingResourceLoader(STATE_CODES_LOCATION,
                jsonResource(json)), new ObjectMapper());
    }

    @Nested
    @DisplayName("1. The collapse: five tables served from exactly three classpath resources, read once")
    class CollapseAndWiring {

        @Test
        @DisplayName("five tables are served from exactly three distinct classpath locations")
        void fiveTablesComeFromThreeDistinctLocations() {
            final RecordingResourceLoader loader = new RecordingResourceLoader();
            final ValidationLookupService service = new ValidationLookupService(loader, new ObjectMapper());

            assertThat(Set.copyOf(loader.requested()))
                    .as("three resources, not five, is the collapse this bean exists to perform")
                    .hasSize(3);
            assertThat(loader.requested())
                    .as("one document carries three tables, so there are more requests than locations")
                    .hasSizeGreaterThan(Set.copyOf(loader.requested()).size());
            assertThat(List.of(service.getValidPhoneAreaCodes(), service.getValidGeneralPurposeAreaCodes(),
                            service.getValidEasilyRecognisableAreaCodes(), service.getValidUsStateCodes(),
                            service.getValidStateZipCodeCombinations()))
                    .as("all five tables are populated, so no resource bound to nothing")
                    .hasSize(5)
                    .allSatisfy(table -> assertThat(table).isNotEmpty());
        }

        @Test
        @DisplayName("the three locations are the declared ones, and the shared document is asked for once "
                + "per table")
        void theThreeLocationsAreTheDeclaredOnes() {
            final RecordingResourceLoader loader = new RecordingResourceLoader();
            new ValidationLookupService(loader, new ObjectMapper());

            // The nanpa document carries three of the five tables, so it is requested three times while
            // the other two are requested once each. Pinning the exact sequence documents the load order
            // and would catch a resource being renamed, re-ordered or dropped.
            assertThat(loader.requested()).containsExactly(AREA_CODES_LOCATION, AREA_CODES_LOCATION,
                    AREA_CODES_LOCATION, STATE_CODES_LOCATION, STATE_ZIP_LOCATION);
        }

        @Test
        @DisplayName("the tables are read once at construction and never re-read by a lookup")
        void thereIsNoReadAfterConstruction() {
            final RecordingResourceLoader loader = new RecordingResourceLoader();
            final ValidationLookupService service = new ValidationLookupService(loader, new ObjectMapper());
            final int readsAtConstruction = loader.requested().size();

            for (int repetition = 0; repetition < 25; repetition++) {
                service.isValidPhoneAreaCode("201");
                service.isValidGeneralPurposeAreaCode("201");
                service.isValidEasilyRecognisableAreaCode("200");
                service.isValidUsStateCode("AL");
                service.isValidStateZipCodeCombination("AA34");
                service.isValidStateAndZipCode("WY", "83001");
                service.getValidPhoneAreaCodes();
                service.getValidStateZipCodeCombinations();
            }

            assertThat(loader.requested()).as("a lookup that re-read its resource would be a per-call file "
                    + "open on a hot validation path").hasSize(readsAtConstruction);
        }

        @Test
        @DisplayName("construction is the only wiring: one public constructor taking the two collaborators")
        void constructionIsTheOnlyWiring() {
            // Rule 1 Clause B asks for dependency injection over global state. A single constructor that
            // takes both collaborators is what makes the bean testable here at all: no setter, no static
            // holder and no default constructor means there is no way to reach a half-initialised table.
            assertThat(ValidationLookupService.class.getConstructors())
                    .as("exactly one public constructor")
                    .hasSize(1)
                    .allSatisfy(constructor -> assertThat(constructor.getParameterTypes())
                            .containsExactly(ResourceLoader.class, ObjectMapper.class));
            assertThat(ValidationLookupService.class.getFields())
                    .as("no public field can expose a table for mutation")
                    .allSatisfy(field -> assertThat(field.getType()).isEqualTo(int.class));
        }

        @Test
        @DisplayName("every accessor hands back the same immutable instance, so no caller can corrupt a table")
        void everyAccessorIsImmutableAndStable() {
            final List<SequencedSet<String>> tables = List.of(SERVICE.getValidPhoneAreaCodes(),
                    SERVICE.getValidGeneralPurposeAreaCodes(), SERVICE.getValidEasilyRecognisableAreaCodes(),
                    SERVICE.getValidUsStateCodes(), SERVICE.getValidStateZipCodeCombinations());

            assertThat(tables).allSatisfy(table -> {
                assertThat(catchThrowableOfType(UnsupportedOperationException.class,
                        () -> table.add("ZZZZ"))).isNotNull();
                assertThat(catchThrowableOfType(UnsupportedOperationException.class,
                        table::clear)).isNotNull();
            });
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("the same instance every call, which is what load-once means to a caller")
                    .isSameAs(SERVICE.getValidUsStateCodes());
        }
    }

    @Nested
    @DisplayName("2. The five cardinalities the copybook declares")
    class Cardinalities {

        @Test
        @DisplayName("VALID-PHONE-AREA-CODE holds exactly 490 members")
        void phoneAreaCodeTableHolds490() {
            assertThat(SERVICE.getValidPhoneAreaCodes()).hasSize(PHONE_AREA_CODES);
        }

        @Test
        @DisplayName("VALID-GENERAL-PURP-CODE holds exactly 410 members")
        void generalPurposeTableHolds410() {
            assertThat(SERVICE.getValidGeneralPurposeAreaCodes()).hasSize(GENERAL_PURPOSE_AREA_CODES);
        }

        @Test
        @DisplayName("VALID-EASY-RECOG-AREA-CODE holds exactly 80 members")
        void easilyRecognisableTableHolds80() {
            assertThat(SERVICE.getValidEasilyRecognisableAreaCodes())
                    .hasSize(EASILY_RECOGNISABLE_AREA_CODES);
        }

        @Test
        @DisplayName("VALID-US-STATE-CODE holds exactly 56 members")
        void stateCodeTableHolds56() {
            assertThat(SERVICE.getValidUsStateCodes()).hasSize(US_STATE_CODES);
        }

        @Test
        @DisplayName("VALID-US-STATE-ZIP-CD2-COMBO holds exactly 240 members")
        void stateZipTableHolds240() {
            assertThat(SERVICE.getValidStateZipCodeCombinations()).hasSize(STATE_ZIP_COMBINATIONS);
        }

        @Test
        @DisplayName("the five tables exhaust the copybook's 1276 quoted literals, leaving none unmodelled")
        void theFiveTablesExhaustTheCopybook() {
            // Counting the loaded members against the literal count is the one check that detects a whole
            // table having been dropped or a stray literal having been invented, which no per-table size
            // assertion can see on its own.
            assertThat(PHONE_AREA_CODES + GENERAL_PURPOSE_AREA_CODES + EASILY_RECOGNISABLE_AREA_CODES
                    + US_STATE_CODES + STATE_ZIP_COMBINATIONS).isEqualTo(COPYBOOK_LITERAL_COUNT);
            assertThat(SERVICE.getValidPhoneAreaCodes().size()
                    + SERVICE.getValidGeneralPurposeAreaCodes().size()
                    + SERVICE.getValidEasilyRecognisableAreaCodes().size()
                    + SERVICE.getValidUsStateCodes().size()
                    + SERVICE.getValidStateZipCodeCombinations().size())
                    .isEqualTo(COPYBOOK_LITERAL_COUNT);
        }

        @Test
        @DisplayName("the head-line asymmetry does not distort a count: three tables open with a value, two "
                + "do not")
        void theHeadLineAsymmetryDoesNotDistortACount() {
            // At :L30, :L521 and :L931 the first literal sits on the 88 head line itself; at :L1013 and
            // :L1073 the head line carries VALUES and nothing else. A parser that assumed one uniform shape
            // would be short by one on three tables and would still produce plausible numbers, so the first
            // member of every table is pinned alongside the sizes.
            assertThat(SERVICE.getValidPhoneAreaCodes().getFirst()).isEqualTo("201");
            assertThat(SERVICE.getValidGeneralPurposeAreaCodes().getFirst()).isEqualTo("201");
            assertThat(SERVICE.getValidEasilyRecognisableAreaCodes().getFirst()).isEqualTo("200");
            assertThat(SERVICE.getValidUsStateCodes().getFirst()).isEqualTo("AL");
            assertThat(SERVICE.getValidStateZipCodeCombinations().getFirst()).isEqualTo("AA34");
        }
    }

    @Nested
    @DisplayName("3. The order-preserving disjoint partition of the three area-code tables")
    class DisjointPartition {

        @Test
        @DisplayName("the general-purpose and easily-recognisable tables share no member")
        void theTwoSubsetsAreDisjoint() {
            assertThat(SERVICE.getValidGeneralPurposeAreaCodes())
                    .doesNotContainAnyElementsOf(SERVICE.getValidEasilyRecognisableAreaCodes());
        }

        @Test
        @DisplayName("general-purpose then easily-recognisable IS the phone table, element for element in "
                + "order")
        void theConcatenationIsThePhoneTableInOrder() {
            // Set equality would hold for any permutation. Asserting the ORDERED concatenation is the
            // stronger statement, and it is the only one that proves the phone table is these two tables
            // laid end to end rather than a merged or sorted rendering of them.
            final List<String> concatenation = new ArrayList<>(SERVICE.getValidGeneralPurposeAreaCodes());
            concatenation.addAll(SERVICE.getValidEasilyRecognisableAreaCodes());

            assertThat(concatenation).hasSize(PHONE_AREA_CODES);
            assertThat(SERVICE.getValidPhoneAreaCodes()).containsExactlyElementsOf(concatenation);
        }

        @Test
        @DisplayName("the easily-recognisable table is exactly the generative set d00, d11 .. d99 for d in "
                + "2..9")
        void theEasilyRecognisableTableMatchesItsGenerativeRule() {
            // The rule is asserted, never used to produce the data: the tables are reference data read from
            // a resource, and generating them in production code would put the copybook's content into a
            // compilation unit, which is the High-severity outcome this migration avoids.
            final List<String> generated = new ArrayList<>();
            for (int leading = 2; leading <= 9; leading++) {
                for (int repeated = 0; repeated <= 9; repeated++) {
                    generated.add("" + leading + repeated + repeated);
                }
            }

            assertThat(generated).hasSize(EASILY_RECOGNISABLE_AREA_CODES);
            assertThat(SERVICE.getValidEasilyRecognisableAreaCodes())
                    .containsExactlyElementsOf(generated);
        }

        @Test
        @DisplayName("the phone table is NOT sorted, and its single descent is the seam at index 409 to 410")
        void thePhoneTableIsNotSortedAndTheSeamProvesIt() {
            // Both subsets ascend individually, so laying them end to end produces exactly one descent, and
            // it must fall at the boundary: index 409 is the last general-purpose code and index 410 the
            // first easily-recognisable one, straddling the interior comment at
            // app/cpy/CSLKPCDY.cpy:L440. A test asserting this table is sorted would fail, and a change
            // that sorted it would destroy every index checkpoint in this file.
            final List<String> all = ordered(SERVICE.getValidPhoneAreaCodes());
            final List<Integer> descents = new ArrayList<>();
            for (int index = 0; index < all.size() - 1; index++) {
                if (all.get(index).compareTo(all.get(index + 1)) > 0) {
                    descents.add(index);
                }
            }

            assertThat(descents).containsExactly(GENERAL_PURPOSE_AREA_CODES - 1);
            assertThat(all.get(409)).isEqualTo("989");
            assertThat(all.get(410)).isEqualTo("200");
        }

        @Test
        @DisplayName("the two witnesses separate the subsets: 201 is general-purpose only, 200 easily "
                + "recognisable only")
        void theTwoWitnessesSeparateTheSubsets() {
            assertThat(SERVICE.isValidPhoneAreaCode("201")).isTrue();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("201")).isTrue();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode("201")).isFalse();

            assertThat(SERVICE.isValidPhoneAreaCode("200")).isTrue();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode("200")).isTrue();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode("200")).isFalse();
        }
    }

    @Nested
    @DisplayName("4. The 56-versus-62 asymmetry and the preserved source order of both address tables")
    class AddressTableAsymmetry {

        @Test
        @DisplayName("the zip table uses 62 prefixes, the state table declares 56, and the six extras are "
                + "named")
        void theZipTableUsesSixMorePrefixesThanTheStateTableDeclares() {
            // The subset relation runs one way only. Adding the six to the state table would accept
            // addresses the legacy screen rejected; deleting their combinations from the zip table would
            // reject addresses it accepted. Either direction is a Blocker, so both directions are asserted.
            final SequencedSet<String> prefixes = zipPrefixes();

            assertThat(prefixes).hasSize(STATE_ZIP_PREFIXES);
            assertThat(SERVICE.getValidUsStateCodes()).hasSize(US_STATE_CODES);
            assertThat(prefixes).as("every declared state code is used by at least one combination")
                    .containsAll(SERVICE.getValidUsStateCodes());
            assertThat(prefixes).as("the military and diplomatic mail codes and the Freely Associated "
                    + "States are the extras").containsAll(ZIP_ONLY_PREFIXES);
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("and none of the six is a declared state code, so equality would be wrong")
                    .doesNotContainAnyElementsOf(ZIP_ONLY_PREFIXES);
            assertThat(prefixes).hasSize(US_STATE_CODES + ZIP_ONLY_PREFIXES.size());
        }

        @ParameterizedTest(name = "{0} is followed by {1}, which alphabetical order forbids")
        @CsvSource({"AL, AK", "IN, IA", "ME, MD", "MS, MO", "NE, NV", "NV, NH", "NY, NC", "NC, ND",
                    "VT, VA", "WA, WV", "WV, WI", "WI, WY", "WY, DC", "DC, AS"})
        @DisplayName("the state table is ordered by full state NAME, proven by adjacencies a sort destroys")
        void theStateTableIsOrderedByStateNameNotByCode(final String earlier, final String later) {
            // app/cpy/CSLKPCDY.cpy:L1013-L1069 lists the states alphabetically by their full names, then
            // the District of Columbia, then the territories - so it ends WY DC AS GU MP PR VI. Each pair
            // here is out of alphabetical order by code, so any sort breaks it. Naming them individually
            // reports which adjacency broke rather than only that the table changed.
            final List<String> codes = ordered(SERVICE.getValidUsStateCodes());
            final int position = codes.indexOf(earlier);

            assertThat(position).as("%s is a declared state code", earlier).isNotNegative();
            assertThat(codes.get(position + 1)).isEqualTo(later);
        }

        @ParameterizedTest(name = "state table index {0} is {1}")
        @CsvSource({"0, AL", "14, IA", "28, NH", "42, TX", "55, VI"})
        @DisplayName("the state table's index checkpoints hold, pinning the transcription end to end")
        void theStateTableIndexCheckpointsHold(final int index, final String expected) {
            assertThat(ordered(SERVICE.getValidUsStateCodes()).get(index)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "zip table index {0} is {1}")
        @CsvSource({"0, AA34", "60, KS66", "120, NH33", "180, PR71", "239, WY83"})
        @DisplayName("the zip table's index checkpoints hold, so it is source-ordered and not sorted")
        void theZipTableIndexCheckpointsHold(final int index, final String expected) {
            assertThat(ordered(SERVICE.getValidStateZipCodeCombinations()).get(index)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the New York run at indices 150-157 descends, and must never be sorted")
        void theNewYorkRunDescendsAndIsPreserved() {
            // NY50 NY54 NY63 then NY10 - the one place in the zip table where the numeric part goes
            // backwards. It is source order, not a defect, and sorting it is a High-severity change.
            assertThat(ordered(SERVICE.getValidStateZipCodeCombinations()).subList(150, 158))
                    .containsExactly("NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14");
        }

        @ParameterizedTest(name = "{0} is absent, because numeric gaps are not filled")
        @ValueSource(strings = {"VT55", "TX74", "VI81", "AE99", "VA21"})
        @DisplayName("numeric gaps in the zip table are genuine and are never filled in")
        void numericGapsAreNeverFilled(final String absent) {
            // The state's other prefixes are present, so absence here is a real gap in the source rather
            // than a missing state. Filling a gap would accept an address the legacy screen rejected.
            assertThat(SERVICE.getValidStateZipCodeCombinations()).doesNotContain(absent);
            assertThat(zipPrefixes()).as("the state itself is present, so only the numeric value is absent")
                    .contains(absent.substring(0, ValidationLookupService.STATE_CODE_WIDTH));
        }

        @Test
        @DisplayName("the discontinuous runs are preserved exactly: MO65 to MO72, MA27 to MA55, ME from "
                + "ME39, DC in three pieces and PR in two")
        void theDiscontinuousRunsArePreserved() {
            final List<String> combinations = ordered(SERVICE.getValidStateZipCodeCombinations());

            assertThat(combinations).containsSequence("MO65", "MO72");
            assertThat(combinations).containsSequence("MA27", "MA55");
            assertThat(combinations.stream().filter(key -> key.startsWith("ME")).toList())
                    .as("Maine starts at ME39, not at ME00").startsWith("ME39");
            assertThat(combinations.stream().filter(key -> key.startsWith("DC")).toList())
                    .as("the District of Columbia is spread across three values")
                    .containsExactly("DC20", "DC56", "DC88");
            assertThat(combinations).as("Puerto Rico runs to PR79 then resumes at PR90")
                    .containsSequence("PR79", "PR90");
        }

        @Test
        @DisplayName("every zip key is two upper-case letters then two digits")
        void everyZipKeyIsTwoLettersThenTwoDigits() {
            assertThat(SERVICE.getValidStateZipCodeCombinations())
                    .allMatch(key -> STATE_ZIP_KEY.matcher(key).matches());
        }

        @Test
        @DisplayName("LAST-3-OF-ZIP is never consulted, so the last three digits cannot change an outcome")
        void theLastThreeOfZipIsNeverConsulted() {
            // app/cpy/CSLKPCDY.cpy:L1314 declares 02 LAST-3-OF-ZIP PIC X(3) and no 88 level exists for it
            // anywhere in the corpus. INTENTIONAL NO-OP, RETAINED FOR PARITY: the field is kept as the
            // service's own width constant and is deliberately never read. Tracked in DECISION_LOG.md;
            // severity Low. Inventing a rule for it would add a validation the legacy system never performs.
            assertThat(SERVICE.isValidStateAndZipCode("WY", "83001")).isTrue();
            assertThat(SERVICE.isValidStateAndZipCode("WY", "83999")).isTrue();
            assertThat(SERVICE.isValidStateAndZipCode("WY", "83000")).isTrue();
            assertThat(SERVICE.isValidStateAndZipCode("WY", "830011234"))
                    .as("a longer zip is accepted, because only the first two digits participate").isTrue();
            assertThat(SERVICE.isValidStateAndZipCode("WY", "99001"))
                    .as("changing the participating prefix does change the outcome").isFalse();
            assertThat(ValidationLookupService.LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED)
                    .as("the width is retained as documentation of the field that carries no 88 level")
                    .isEqualTo(3);
            assertThat(ValidationLookupService.ZIP_PREFIX_LENGTH
                    + ValidationLookupService.LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED)
                    .as("two validated digits plus three unvalidated ones is the five-digit zip")
                    .isEqualTo(ValidationLookupService.MINIMUM_ZIP_CODE_LENGTH);
        }
    }

    @Nested
    @DisplayName("5. State-code membership is deliberately NOT enforced against the seed fixtures")
    class StateMembershipIsNotEnforced {

        @Test
        @DisplayName("an unknown state code answers false and throws nothing")
        void anUnknownStateCodeAnswersFalse() {
            // A miss is the 88 level's own outcome: the condition name simply evaluates false. Turning a
            // miss into an exception would make the caller's ELSE branch unreachable.
            assertThat(SERVICE.isValidUsStateCode("ZZ")).isFalse();
            assertThat(SERVICE.isValidUsStateCode("XX")).isFalse();
        }

        @ParameterizedTest(name = "{0} is not a declared state code yet still answers false, not an error")
        @ValueSource(strings = {"AP", "FM", "MH", "PW"})
        @DisplayName("the four fixture codes the table omits are misses, never load-time or lookup failures")
        void theFourFixtureCodesAreMissesRatherThanFailures(final String fixtureCode) {
            // app/data/ASCII/custdata.txt carries these at columns 235-236 in five of its fifty 500-byte
            // records. Enforcing membership at load time, or adding them to us-state-codes.json, is a
            // Blocker either way: the first makes the seed unloadable, the second changes what the legacy
            // screen accepted. So the bean answers false and the caller decides.
            assertThat(SERVICE.getValidUsStateCodes()).doesNotContain(fixtureCode);
            assertThat(SERVICE.isValidUsStateCode(fixtureCode)).isFalse();
            assertThat(zipPrefixes()).as("yet the zip table does use it, which is the whole asymmetry")
                    .contains(fixtureCode);
        }

        @Test
        @DisplayName("construction succeeds even though the seed carries non-member state codes")
        void constructionDoesNotEnforceStateMembership() {
            // The proof is that a freshly constructed service loads all five tables while the four codes
            // above remain non-members. If any startup check cross-validated the fixtures against
            // us-state-codes.json, this construction could not complete.
            final ValidationLookupService service =
                    new ValidationLookupService(new DefaultResourceLoader(), new ObjectMapper());

            assertThat(service.getValidUsStateCodes()).hasSize(US_STATE_CODES)
                    .doesNotContainAnyElementsOf(FIXTURE_NON_MEMBER_STATE_CODES);
            assertThat(service.getValidStateZipCodeCombinations()).hasSize(STATE_ZIP_COMBINATIONS);
            assertThat(FIXTURE_NON_MEMBER_STATE_CODES)
                    .as("all four are among the six prefixes only the zip table knows")
                    .isSubsetOf(ZIP_ONLY_PREFIXES);
        }
    }

    @Nested
    @DisplayName("6. Hostile and boundary arguments: refusals name the field, misses are return values")
    class HostileArguments {

        @Test
        @DisplayName("a null argument is refused explicitly as BLANK on every predicate")
        void nullIsRefusedExplicitly() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode(null)).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidUsStateCode(null)).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateZipCodeCombination(null)).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.BLANK);
        }

        @Test
        @DisplayName("an empty argument is refused explicitly as BLANK")
        void emptyIsRefusedExplicitly() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode("")).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.BLANK);
        }

        @Test
        @DisplayName("an all-whitespace argument is refused explicitly as BLANK, never treated as a pass")
        void whitespaceIsRefusedExplicitly() {
            // A COBOL PIC XXX field holding three spaces is exactly what an untouched screen field looks
            // like. The 88 level evaluates false for it and the caller sets its per-field error flag, so
            // the one outcome that would be wrong is silently accepting it.
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode("   ")).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.BLANK);
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidUsStateCode("  ")).getFailureKind())
                    .isEqualTo(ValidationException.FailureKind.BLANK);
        }

        @Test
        @DisplayName("a two-character area code is refused, because no PIC XXX field could hold one")
        void aTwoCharacterAreaCodeIsRefused() {
            final ValidationException refusal = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode("20"));

            assertThat(refusal.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getMessage()).contains("WS-US-PHONE-AREA-CODE-TO-EDIT")
                    .contains("exactly").contains(String.valueOf(ValidationLookupService.AREA_CODE_WIDTH));
        }

        @Test
        @DisplayName("a four-character area code is refused, so a padded caller argument cannot slip through")
        void aFourCharacterAreaCodeIsRefused() {
            final ValidationException refusal = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidPhoneAreaCode("2011"));

            assertThat(refusal.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getMessage()).contains("WS-US-PHONE-AREA-CODE-TO-EDIT");
        }

        @Test
        @DisplayName("a five-character zip-prefix probe is refused, because the key is PIC X(4)")
        void aFiveCharacterZipKeyIsRefused() {
            final ValidationException refusal = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateZipCodeCombination("WY830"));

            assertThat(refusal.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getMessage()).contains("US-STATE-AND-FIRST-ZIP2")
                    .contains(String.valueOf(ValidationLookupService.STATE_ZIP_KEY_WIDTH));
        }

        @Test
        @DisplayName("a zip shorter than five characters is refused rather than padded into a false prefix")
        void aShortZipIsRefusedRatherThanPadded() {
            final ValidationException refusal = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidStateAndZipCode("WY", "83"));

            assertThat(refusal.getFailureKind()).isEqualTo(ValidationException.FailureKind.INVALID);
            assertThat(refusal.getMessage()).contains("ACUP-NEW-CUST-ADDR-ZIP").contains("at least");
        }

        @Test
        @DisplayName("a lower-case code is a miss, because an 88-level comparison folds no case")
        void aLowerCaseCodeIsAMiss() {
            assertThat(SERVICE.isValidUsStateCode("al")).isFalse();
            assertThat(SERVICE.isValidUsStateCode("Al")).isFalse();
            assertThat(SERVICE.isValidStateZipCodeCombination("wy83")).isFalse();
        }

        @ParameterizedTest(name = "the correctly sized but absent value {0} answers false")
        @ValueSource(strings = {"000", "001", "010", "100", "2 1", "ZZZ"})
        @DisplayName("a correctly sized value that is absent - including one with an embedded space - is a "
                + "miss, not an error")
        void aWellSizedAbsentValueIsAMiss(final String candidate) {
            assertThat(SERVICE.isValidPhoneAreaCode(candidate)).isFalse();
            assertThat(SERVICE.isValidGeneralPurposeAreaCode(candidate)).isFalse();
            assertThat(SERVICE.isValidEasilyRecognisableAreaCode(candidate)).isFalse();
        }

        @Test
        @DisplayName("a refusal names the COBOL data item and never quotes the rejected value")
        void aRefusalNamesTheFieldAndNeverTheValue() {
            // A telephone area code and a postal code are personal data, and Rule 1 Clause D names tests
            // explicitly. The message has to be actionable without echoing the value, so the field name is
            // asserted present and the value asserted absent.
            final ValidationException refusal = catchThrowableOfType(ValidationException.class,
                    () -> SERVICE.isValidUsStateCode("Q"));

            assertThat(refusal.getFieldName()).isEqualTo("US-STATE-CODE-TO-EDIT");
            assertThat(refusal.getMessage()).contains("US-STATE-CODE-TO-EDIT").doesNotContain("Q");
            assertThat(refusal).isInstanceOf(ValidationException.class)
                    .as("a refusal is a validation failure, never the fatal load abend")
                    .isNotInstanceOf(FatalProcessingException.class);
        }
    }

    @Nested
    @DisplayName("7. A defective resource abends at construction and never degrades to an empty table")
    class FailFastOnDefectiveResource {

        @Test
        @DisplayName("an absent resource fails construction, naming the resource path and the member")
        void anAbsentResourceFailsConstruction() {
            // ClassPathResource for a location that genuinely does not exist reports exists() == false,
            // which is the branch a mistyped or unpackaged resource takes in production.
            final FatalProcessingException failure = loadFailure(STATE_CODES_LOCATION,
                    new ClassPathResource("validation/deliberately-absent-for-this-assertion.json"));

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage()).contains(STATE_CODES_PATH).contains(STATE_CODE_MEMBER)
                    .contains("absent from the classpath");
            assertThat(failure.getAbendCulprit()).isEqualTo("CSLKPCDY");
            assertThat(failure.getCause())
                    .as("this defect is structural, so there is no throwable to attribute and none is "
                            + "invented")
                    .isNull();
        }

        @Test
        @DisplayName("a malformed resource fails construction and preserves the parse failure as the cause")
        void aMalformedResourceFailsConstructionAndPreservesTheCause() {
            final FatalProcessingException failure =
                    loadFailure(STATE_CODES_LOCATION, jsonResource("{\"VALID-US-STATE-CODE\": [\"AL\","));

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage()).contains(STATE_CODES_PATH).contains(STATE_CODE_MEMBER)
                    .contains("not well-formed JSON");
            assertThat(failure.getCause())
                    .as("Rule 1 Clause B: wrap with context, never swallow the root cause")
                    .isInstanceOf(JsonProcessingException.class);
        }

        @Test
        @DisplayName("an empty table member abends rather than degrading to an empty set")
        void anEmptyMemberAbendsRatherThanDegrading() {
            // Blocker. An empty table answers false for every state code in existence while reporting a
            // successful start, so every address edit would fail with no diagnostic anywhere.
            final FatalProcessingException failure =
                    loadFailure(STATE_CODES_LOCATION, jsonResource("{\"VALID-US-STATE-CODE\": []}"));

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage()).contains(STATE_CODES_PATH).contains("empty array");
        }

        @Test
        @DisplayName("a failed construction publishes no service, so no caller can observe a partial table")
        void aFailedConstructionPublishesNoService() {
            final ResourceLoader loader = new SubstitutingResourceLoader(STATE_CODES_LOCATION,
                    jsonResource("{\"VALID-US-STATE-CODE\": []}"));
            final ObjectMapper mapper = new ObjectMapper();

            assertThat(catchThrowableOfType(FatalProcessingException.class,
                    () -> new ValidationLookupService(loader, mapper)))
                    .as("the constructor is the only way in, so a throwing constructor means there is no "
                            + "instance to hold a half-loaded table")
                    .isNotNull();
            assertThat(SERVICE.getValidUsStateCodes())
                    .as("and the already-built shared instance is untouched by another instance's failure")
                    .hasSize(US_STATE_CODES);
        }
    }

    @Nested
    @DisplayName("8. Deserialization is by name and non-polymorphic, which is Clause D's named risk here")
    class DeserializationSafety {

        @Test
        @DisplayName("_metadata is ignored BY NAME and can never be mistaken for a table")
        void metadataIsIgnoredByName() {
            // Each real document opens with a _metadata member. The service reads only the five members it
            // names, so _metadata is skipped by construction rather than by a blanket unknown-property
            // setting - which matters, because a blanket setting would also swallow a renamed table.
            final ValidationLookupService service = serviceWithStateCodeDocument(
                    "{\"_metadata\": [\"QQ\", \"XX\"], \"VALID-US-STATE-CODE\": [\"AL\", \"AK\"]}");

            assertThat(service.getValidUsStateCodes()).containsExactly("AL", "AK");
            assertThat(service.getValidUsStateCodes())
                    .as("nothing from _metadata reached the table")
                    .doesNotContain("QQ", "XX");
            assertThat(service.isValidUsStateCode("QQ")).isFalse();
        }

        @Test
        @DisplayName("a rogue sibling key cannot substitute for the real member: the load abends instead")
        void aRogueSiblingKeyCannotSubstituteForTheRealMember() {
            // The near-miss key is the dangerous case. If the loader enumerated members, or tolerated a
            // missing one, a typo would silently narrow validation to nothing. It must abend instead, so an
            // unexpected key is emphatically not silently tolerated in place of the member it resembles.
            final FatalProcessingException failure = loadFailure(STATE_CODES_LOCATION, jsonResource(
                    "{\"_metadata\": [], \"VALID_US_STATE_CODE\": [\"AL\"], \"extra\": [\"AK\"]}"));

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage()).contains(STATE_CODE_MEMBER).contains("table member is absent");
        }

        @Test
        @DisplayName("an unrelated extra key alongside the real member changes nothing about the table")
        void anUnrelatedExtraKeyChangesNothing() {
            final ValidationLookupService service = serviceWithStateCodeDocument(
                    "{\"VALID-US-STATE-CODE\": [\"AL\"], \"VALID-US-STATE-ZIP-CD2-COMBO\": [\"ZZ99\"]}");

            assertThat(service.getValidUsStateCodes()).containsExactly("AL");
            assertThat(service.getValidStateZipCodeCombinations())
                    .as("the zip table still comes from its own resource, not from a lookalike key here")
                    .hasSize(STATE_ZIP_COMBINATIONS)
                    .doesNotContain("ZZ99");
        }

        @Test
        @DisplayName("type-hint keys are not honoured, and every bound element is exactly a java.lang.String")
        void typeHintKeysAreNotHonoured() {
            // Clause D names insecure deserialization as the risk for this file. The service binds a
            // concrete JsonNode tree and requires each element to be textual, so no @class, @type, $type or
            // _class hint can select a type; default typing is never enabled anywhere, and this class never
            // calls activateDefaultTyping. The elements are asserted to be plain strings to show that
            // nothing was instantiated on the document's instruction.
            final ValidationLookupService service = serviceWithStateCodeDocument("{"
                    + "\"@class\": \"java.util.ArrayList\","
                    + "\"@type\": \"java.util.ArrayList\","
                    + "\"$type\": \"java.util.ArrayList\","
                    + "\"_class\": \"java.util.ArrayList\","
                    + "\"VALID-US-STATE-CODE\": [\"AL\", \"AK\"]}");

            assertThat(service.getValidUsStateCodes()).containsExactly("AL", "AK");
            assertThat(service.getValidUsStateCodes())
                    .allSatisfy(code -> assertThat(code).isExactlyInstanceOf(String.class));
            assertThat(SERVICE.getValidStateZipCodeCombinations())
                    .allSatisfy(key -> assertThat(key).isExactlyInstanceOf(String.class));
        }

        @Test
        @DisplayName("an object element carrying a type hint abends instead of being instantiated")
        void anObjectElementCarryingATypeHintAbends() {
            final FatalProcessingException failure = loadFailure(STATE_CODES_LOCATION, jsonResource(
                    "{\"VALID-US-STATE-CODE\": [{\"@class\": \"java.util.ArrayList\", \"value\": \"AL\"}]}"));

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage()).contains(STATE_CODE_MEMBER).contains("not a JSON string");
        }

        @Test
        @DisplayName("a numeric element abends, because an area code and a zip prefix are text and keep "
                + "their leading zeros")
        void aNumericElementAbends() {
            // 201 as a JSON number would round-trip as "201" but 010 could not, and a numeric binding would
            // quietly drop a leading zero. The source field is PIC X, so the element must be a JSON string.
            final FatalProcessingException failure =
                    loadFailure(STATE_CODES_LOCATION, jsonResource("{\"VALID-US-STATE-CODE\": [10, 11]}"));

            assertThat(failure).isNotNull();
            assertThat(failure.getMessage()).contains("not a JSON string");
        }
    }

    @Nested
    @DisplayName("9. Startup observability: the five sizes are published once and no member value ever is")
    class StartupObservability {

        @Test
        @DisplayName("construction logs the five cardinalities exactly once, and logs no member value")
        void constructionPublishesTheFiveCardinalitiesOnce() {
            // The legacy corpus has no instrumentation beyond DISPLAY, so this line is new capability
            // rather than a translation. It is the only way to detect a resource that loaded successfully
            // but with a wrong count, which no compiler and no schema can catch.
            final ch.qos.logback.classic.Logger serviceLogger =
                    (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ValidationLookupService.class);
            final Level originalLevel = serviceLogger.getLevel();
            final ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            serviceLogger.addAppender(appender);
            serviceLogger.setLevel(Level.INFO);
            try {
                final ValidationLookupService service =
                        new ValidationLookupService(new DefaultResourceLoader(), new ObjectMapper());
                for (int repetition = 0; repetition < 10; repetition++) {
                    service.isValidUsStateCode("AL");
                    service.isValidPhoneAreaCode("201");
                    service.isValidStateZipCodeCombination("AA34");
                }

                assertThat(appender.list).as("once per construction, and never once per lookup").hasSize(1);
                final ILoggingEvent event = appender.list.getFirst();
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .contains("VALID-PHONE-AREA-CODE=" + PHONE_AREA_CODES)
                        .contains("VALID-GENERAL-PURP-CODE=" + GENERAL_PURPOSE_AREA_CODES)
                        .contains("VALID-EASY-RECOG-AREA-CODE=" + EASILY_RECOGNISABLE_AREA_CODES)
                        .contains("VALID-US-STATE-CODE=" + US_STATE_CODES)
                        .contains("VALID-US-STATE-ZIP-CD2-COMBO=" + STATE_ZIP_COMBINATIONS);
                assertThat(event.getArgumentArray())
                        .as("the statement is parameterised, so the five names and five sizes are "
                                + "structured fields rather than a pre-formatted string")
                        .hasSize(10);
                assertThat(event.getFormattedMessage())
                        .as("no looked-up or loaded value is ever rendered into the line")
                        .doesNotContain("AA34", "WY83", "NY10", "201", "999", "200");

                // The check below is deliberately TYPE-AWARE, and it has to be. Two of the five
                // cardinalities coincide with genuine area codes - 410 and 240 are both members of
                // VALID-PHONE-AREA-CODE - so comparing every argument's string form against the tables
                // would fail on a correct log line. The sizes are logged as numbers and the members are
                // text, and that distinction is what separates a published size from a leaked value.
                final Set<String> loaded = everyLoadedValue();
                assertThat(loaded).as("the two coinciding sizes really are area codes, which is why the "
                        + "string form of an argument cannot be used on its own").contains("410", "240");
                for (final Object argument : event.getArgumentArray()) {
                    if (argument instanceof String text) {
                        assertThat(loaded).as("a textual argument must be a member NAME, never a member "
                                + "value; this checks all %d loaded values", loaded.size())
                                .doesNotContain(text);
                    } else {
                        assertThat(argument).as("every non-textual argument is a published size")
                                .isInstanceOf(Integer.class);
                    }
                }
            } finally {
                serviceLogger.detachAppender(appender);
                serviceLogger.setLevel(originalLevel);
                appender.stop();
            }
        }
    }

    /**
     * A real resource loader that additionally records every location it is asked for.
     *
     * <p>This is what makes the collapse and the load-once guarantee observable. Counting requests is the
     * only way to distinguish "reads three resources once" from "re-reads on every lookup", and a mock
     * would have to be told the answer in advance. {@link ResourceLoader} declares two abstract methods, so
     * it cannot be a lambda; it is implemented as a named class deliberately rather than reflectively.
     */
    private static final class RecordingResourceLoader implements ResourceLoader {

        /** The real loader, so the resources genuinely come off the classpath. */
        private final ResourceLoader delegate = new DefaultResourceLoader();

        /** Every location requested, in request order. */
        private final List<String> requested = new ArrayList<>();

        @Override
        public Resource getResource(final String location) {
            this.requested.add(location);
            return this.delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return this.delegate.getClassLoader();
        }

        /**
         * Returns the locations requested so far, in order.
         *
         * @return an immutable snapshot, so a later request cannot alter an assertion already made
         */
        List<String> requested() {
            return List.copyOf(this.requested);
        }
    }

    /**
     * A real resource loader with exactly one location replaced, leaving the other two genuine.
     *
     * <p>Substituting a single document is what lets a defect be injected into one table while the rest of
     * the load stays real, so a failure assertion cannot pass for the wrong reason.
     */
    private static final class SubstitutingResourceLoader implements ResourceLoader {

        /** The real loader, used for every location except the substituted one. */
        private final ResourceLoader delegate = new DefaultResourceLoader();

        /** The single location to intercept. */
        private final String substitutedLocation;

        /** What to hand back for that location. */
        private final Resource substitute;

        /**
         * Creates a loader that intercepts one location.
         *
         * @param substitutedLocation the {@code classpath:} location to intercept
         * @param substitute the resource to return for it
         */
        SubstitutingResourceLoader(final String substitutedLocation, final Resource substitute) {
            this.substitutedLocation = substitutedLocation;
            this.substitute = substitute;
        }

        @Override
        public Resource getResource(final String location) {
            return this.substitutedLocation.equals(location) ? this.substitute
                    : this.delegate.getResource(location);
        }

        @Override
        public ClassLoader getClassLoader() {
            return this.delegate.getClassLoader();
        }
    }
}
