/*
 * ******************************************************************
 * Program     : LookupTableResourceContractTest.java
 * Component   : Unit test tier, resident at
 *               src/test/java/com/cardemo/unit/validation
 * Application : CardDemo
 * Type        : JUnit 5 unit test - pure JVM tier, no container, no
 *               Spring context, no database, no network endpoint
 * Function    : Pins the DATA CONTRACT of the three classpath JSON
 *               resources that carry the five 88-level lookup tables
 *               of CSLKPCDY: their exact cardinality, their preserved
 *               COBOL source ORDER, the order-preserving disjoint
 *               partition of the three area-code tables, the
 *               generative rule behind the easily recognisable codes,
 *               the deliberately unsorted state list with its
 *               sorter-destroying adjacencies, the out-of-order New
 *               York run and the unfilled numeric gaps of the
 *               state-and-zip table, the 56-versus-62 prefix
 *               asymmetry, the absence of any rule for LAST-3-OF-ZIP,
 *               the JSON document shape including the guard against
 *               polymorphic type hints, and the pairing that matters
 *               most: the state table holds exactly its 56 source
 *               entries AND the customer fixture's five non-member
 *               rows are still tolerated.
 * Source      : app/cpy/CSLKPCDY.cpy:L24 (WS-US-PHONE-AREA-CODE-TO-EDIT
 *               PIC XXX), L30 (VALID-PHONE-AREA-CODE), L440 (the
 *               interior comment where the easily recognisable codes
 *               begin), L521 (VALID-GENERAL-PURP-CODE), L931
 *               (VALID-EASY-RECOG-AREA-CODE), L1011 (the misplaced
 *               comment), L1012-L1013 (US-STATE-CODE-TO-EDIT PIC X(2)
 *               and VALID-US-STATE-CODE), L1014-L1069 (the 56 state
 *               codes), L1070 (State Zip Code Combinations),
 *               L1071-L1073 (US-STATE-ZIPCODE-TO-EDIT,
 *               US-STATE-AND-FIRST-ZIP2 PIC X(4) and
 *               VALID-US-STATE-ZIP-CD2-COMBO), L1074-L1313 (the 240
 *               combinations), L1314 (LAST-3-OF-ZIP PIC X(3), which
 *               carries no condition name and is therefore never
 *               validated); app/cpy/CVCUS01Y.cpy (the PIC clauses that
 *               place CUST-ADDR-STATE-CD at columns 235-236 of a
 *               500-byte record); app/data/ASCII/custdata.txt (the 50
 *               customer rows, five of which carry a state code the
 *               table does not hold); app/cbl/CBACT04C.cbl:L1-L21 (the
 *               canonical banner form reproduced above)
 *               @ 7756d89
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
import com.cardemo.unit.model.FixtureLoader;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Pins the data contract of the three classpath JSON resources that replace the five {@code 88}-level lookup
 * tables of {@code app/cpy/CSLKPCDY.cpy}.
 *
 * <h2>1. What it does</h2>
 *
 * <p>{@code app/cpy/CSLKPCDY.cpy} is 1,318 lines and 51,399 bytes of pure lookup literals: five {@code 88}-level
 * condition tables holding 1,276 single-quoted values between them. The five heads sit at {@code :L30}
 * ({@code VALID-PHONE-AREA-CODE}), {@code :L521} ({@code VALID-GENERAL-PURP-CODE}), {@code :L931}
 * ({@code VALID-EASY-RECOG-AREA-CODE}), {@code :L1013} ({@code VALID-US-STATE-CODE}) and {@code :L1073}
 * ({@code VALID-US-STATE-ZIP-CD2-COMBO}). Three {@code 01} items declare the edited fields, at {@code :L24}
 * ({@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}), {@code :L1012} ({@code US-STATE-CODE-TO-EDIT PIC X(2)}) and
 * {@code :L1071} ({@code US-STATE-ZIPCODE-TO-EDIT}); two {@code 02} items subdivide the last of them, at
 * {@code :L1072} ({@code US-STATE-AND-FIRST-ZIP2 PIC X(4)}) and {@code :L1314}
 * ({@code LAST-3-OF-ZIP PIC X(3)}). The 56 state codes occupy {@code :L1014-L1069}, and the value-bearing lines
 * of the whole member span {@code :L30-L1313}. Three interior comment lines fall inside those ranges, at
 * {@code :L440}, {@code :L1011} and {@code :L1070}, and are data to no table.
 *
 * <p>This class asserts <em>membership, cardinality, order and JSON shape</em>. The sibling suites assert the
 * injected bean's predicate behaviour. Both are required and neither substitutes for the other: a predicate
 * test proves the bean answers correctly for the values it was given, while this class proves it was given the
 * right values, in the right order, from a document of the right shape.
 *
 * <h3>1.1 Why exactly three resources, and not a generated constants class</h3>
 *
 * <p>The five tables collapse onto exactly three classpath resources - {@code validation/nanpa-area-codes.json}
 * carries the three area-code tables, {@code validation/us-state-codes.json} carries the state table and
 * {@code validation/state-zip-prefixes.json} carries the state-and-zip table. That externalisation is a
 * deliberate performance-and-maintainability tradeoff and it is the one this class is obliged to justify
 * rather than assume. Emitting 1,276 literals as Java constants would have produced well over a thousand lines
 * of source carrying no logic, which no reviewer can check by reading and which the compiler cannot validate
 * beyond syntax; as data, the same values are checkable by the assertions below, diffable against the frozen
 * copybook, and loaded once at construction into immutable sets whose lookup cost is a hash probe either way.
 * There is therefore no runtime cost to the choice and a large reviewability gain. <strong>A generated
 * constants class is forbidden, and reintroducing one would be a High-severity regression</strong> because it
 * would move the values back out of reach of every assertion here.
 *
 * <h3>1.2 The preserved source oddities, and why this Javadoc is their tracking reference</h3>
 *
 * <p>Several assertions below pin behaviour that looks like a defect and is not. The phone table is not
 * sorted; the state list is not in alphabetical order by code; the state-and-zip table contains a run that
 * goes backwards numerically; whole numeric ranges are left unfilled; a comment at {@code :L1011} describes the
 * state table as phone area codes; and {@code LAST-3-OF-ZIP} is declared and never validated. Each is the
 * behaviour of the system of record, so each is reproduced rather than corrected. <strong>These assertions are
 * themselves the tracking reference for those oddities.</strong> No later change may tidy a resource so that a
 * test reads more sensibly - the test is the reason the resource looks the way it does, and a failure here
 * means the data drifted from the corpus, not that the expectation is stale.
 *
 * <p>No retained no-op lives in this class. Every method below asserts something.
 *
 * <h2>2. How to run, build and test</h2>
 *
 * <p>Run this tier with {@code ./mvnw -B -ntp test}; compile it alone with {@code ./mvnw -B -ntp test-compile};
 * the full gate is {@code ./mvnw -B -ntp verify}. Surefire 3.5.4 collects this class because it lives under
 * {@code src/test/java/com/cardemo/unit} and its name ends in {@code Test}: the include set is
 * {@code **}{@code /*Test.java} and {@code **}{@code /*Tests.java} with the integration and end-to-end trees
 * excluded by path. That placement is load-bearing rather than tidy. A class outside this tree matches neither
 * Surefire's nor Failsafe's include set, so no plugin collects it and every assertion inside it silently stops
 * running while the build stays green - which is why relocating or renaming this file is a Blocker rather than
 * a refactor.
 *
 * <p>Test compilation runs at {@code release 25} under {@code -Xlint:all -Werror} with {@code failOnWarning},
 * so one raw collection type here fails the whole build rather than printing a warning. Nothing in this class
 * needs a container, a Spring context, a database or a network endpoint: {@code DefaultResourceLoader} and
 * {@code ObjectMapper} are constructed directly as plain objects, exactly as the production bean receives them.
 *
 * <h2>3. Key configuration and defaults</h2>
 *
 * <ul>
 *   <li><strong>Three classpath resource names</strong>, read exactly as the production loader reads them:
 *       {@code validation/nanpa-area-codes.json}, {@code validation/us-state-codes.json} and
 *       {@code validation/state-zip-prefixes.json}. They are resolved through the {@code classpath:} scheme
 *       only; no filesystem path, absolute or relative, appears anywhere in this class.</li>
 *   <li><strong>An explicit charset on every read.</strong> Documents are decoded as {@code UTF_8} and the
 *       fixture as US-ASCII inside {@code FixtureLoader}. The platform default is never relied on.</li>
 *   <li><strong>{@code FixtureLoader} from the sibling {@code unit.model} package</strong> is the only way this
 *       class reads {@code app/data/ASCII/custdata.txt}. It is imported rather than reimplemented, and it never
 *       trims a fixed-width record - trimming would destroy the column arithmetic that puts the state code at
 *       columns 235-236.</li>
 *   <li><strong>{@code Locale.ROOT} wherever case is normalised</strong>, so a host locale cannot change an
 *       answer.</li>
 *   <li><strong>No clock, no randomness, no environment.</strong> Nothing here reads the wall clock, the
 *       default locale, the default time zone or a random source, so a run is reproducible byte for byte.</li>
 *   <li><strong>Documents are parsed into {@code final} instance fields under {@code Lifecycle.PER_CLASS}</strong>,
 *       so no test method ever reloads them and no static cache exists. The measured cost of one such load is
 *       roughly 200 milliseconds - about 100 for the bean, which parses the area-code document once per table,
 *       and about 95 for the three trees this suite reads directly - against roughly 2 milliseconds for the
 *       fixture. JUnit constructs the enclosing instance once per nested group rather than once for the whole
 *       class, so the arrangement costs a small multiple of that figure instead of paying it on all 117 test
 *       methods, which is the tradeoff this default is chosen for. Expected literal sets are
 *       {@code static final} because they are constants rather than state, which is the distinction the
 *       prohibition on global mutable state actually draws.</li>
 * </ul>
 *
 * <h2>4. Common failure modes and troubleshooting</h2>
 *
 * <ul>
 *   <li><strong>The build fails with no test failure.</strong> That is the {@code -Xlint:all -Werror} gate, not
 *       an assertion. A raw {@code Set} or {@code List}, an unchecked cast or a deprecated call is fatal here.
 *       Parameterise the collection rather than relaxing the compiler configuration.</li>
 *   <li><strong>A cardinality assertion fails by a small amount.</strong> Suspect the extractor, not the
 *       copybook. The three area-code tables carry their first value <em>on the {@code 88} head line itself</em>
 *       while the state and state-and-zip tables carry none, so an extractor that assumes one rule for all five
 *       silently loses three values or invents two. Extraction must be delimiter-based on the single quotes,
 *       excluding any line whose column 7 is {@code *} - this is a Blocker-severity hazard because the three
 *       tables are also indented three different ways: two tabs for the area codes, a space then two tabs for
 *       the state codes, and pure spaces for the state-and-zip combinations, so a fixed-column reader loses
 *       whole tables. Zero comment lines in the member contain a single quote, which is what makes the
 *       column-7 exclusion sufficient.</li>
 *   <li><strong>An ordering assertion fails.</strong> Something sorted a table. Sorting the phone table
 *       destroys the partition, sorting the state list destroys the by-name ordering, and sorting the
 *       state-and-zip table destroys the New York run. Restore source order; do not relax the assertion.</li>
 *   <li><strong>{@code isValidUsStateCode} starts answering true for {@code AA}, {@code AE}, {@code AP},
 *       {@code FM}, {@code MH} or {@code PW}.</strong> Somebody reconciled the two tables. The copybook
 *       declares 56 state codes and 62 distinct zip prefixes and the six-code gap is the behaviour: adding them
 *       to the state resource, or removing {@code AA34}, {@code AE90}-{@code AE98}, {@code AP96},
 *       {@code FM96}, {@code MH96} or {@code PW96} from the zip resource, is a Blocker either way.</li>
 *   <li><strong>A customer fixture row is rejected for an unknown state code.</strong> State membership must
 *       never be enforced at fixture load time. Five of the fifty rows carry a code the table does not hold and
 *       all five are valid data; enforcing membership there is a Blocker, and so is silencing it by adding the
 *       four codes to the state resource.</li>
 *   <li><strong>Every membership assertion passes but the tables are empty.</strong> That is the vacuous pass
 *       the loader exists to prevent: an empty table would accept nothing and would make a membership test
 *       trivially true. A missing or malformed resource must fail fast with the resource path in the message
 *       and the original throwable as the cause, never degrade to an empty set.</li>
 *   <li><strong>A resource read returns nothing at all.</strong> Run {@code ./mvnw -B -ntp test-compile}, which
 *       copies {@code src/main/resources} to {@code target/classes}, and confirm the three documents are under
 *       {@code validation/} there. Note also the fixture-name trap that catches neighbouring suites: the ASCII
 *       fixture spells the word in full as {@code dailytran.txt} even though the mainframe DD name is
 *       {@code DALYTRAN}, so {@code dalytran.txt} resolves to nothing.</li>
 * </ul>
 *
 * <h2>5. Severity of the findings this class guards</h2>
 *
 * <ul>
 *   <li><strong>Blocker</strong> - relocating or renaming this file out of the Surefire include set; a
 *       column-based extractor losing values across the three indentation styles; altering the six-prefix
 *       asymmetry in either direction; enforcing state-code membership against the fixtures; a loader
 *       degrading to an empty table.</li>
 *   <li><strong>High</strong> - sorting any of the five tables; losing the phone-table seam at index 409 and
 *       410; losing the New York run; mishandling the head-line value asymmetry; generating a Java constants
 *       class in place of the three resources; enabling polymorphic deserialization.</li>
 *   <li><strong>Medium</strong> - binding a document to a loosely typed target instead of a concrete one;
 *       tolerating unknown JSON properties as a blanket policy rather than ignoring {@code _metadata} by
 *       name.</li>
 *   <li><strong>Low</strong> - the misplaced comment at {@code app/cpy/CSLKPCDY.cpy:L1011}, which describes the
 *       state table as phone area codes; the 22-line banner variant at {@code :L1-L22}, which this file does not
 *       imitate because the canonical form at {@code app/cbl/CBACT04C.cbl:L1-L21} is 21 lines; the double space
 *       after {@code VALUES} at {@code :L931} where the two sibling heads have one; the double space before
 *       {@code PIC} at {@code :L1012}; and the version stamp at {@code :L1316}, whose seconds differ from
 *       {@code app/cpy/CSUTLDPY.cpy}, {@code app/cpy/CSUTLDWY.cpy} and {@code app/cbl/CSUTLDTC.cbl}.</li>
 * </ul>
 *
 * <h2>6. Not available</h2>
 *
 * <ul>
 *   <li><em>The semantic distinction the source intends between general-purpose and easily recognisable area
 *       codes</em>, beyond the order-preserving disjoint partition proved below. Not available. What would be
 *       needed is a design note from the system owner; the interior comment at
 *       {@code app/cpy/CSLKPCDY.cpy:L440} marks the boundary without explaining it.</li>
 *   <li><em>Any rationale for the New York run at indices 150 to 157, for the unfilled numeric gaps, or for the
 *       six prefixes that exist only in the zip table.</em> Not available. All three are reproduced as
 *       measured, and what would be needed is an authoritative statement of intent.</li>
 *   <li><em>Any validation rule for {@code LAST-3-OF-ZIP}.</em> Not available, and none exists: no {@code 88}
 *       level is declared on it anywhere in the copybook, whose only other content after {@code :L1314} is the
 *       version stamp. What would be needed is a source condition name that is absent. None is invented, and
 *       the absence is asserted below so that nobody later adds one by guesswork.</li>
 *   <li><em>Any documented reason the comment at {@code app/cpy/CSLKPCDY.cpy:L1011} describes the state table
 *       as phone area codes.</em> Not available. It reads as a copy-and-paste artefact, and the correct comment
 *       for the following table is at {@code :L1070}.</li>
 *   <li><em>Any latency or throughput objective for this tier.</em> Not available and none exists in the
 *       source, so nothing here asserts a timing threshold. A measured baseline is the only honest figure.</li>
 * </ul>
 */
@DisplayName("CSLKPCDY lookup tables: the data contract of the three classpath JSON resources")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupTableResourceContractTest {

    /** The {@code classpath:} scheme prefix, the only resource scheme this class ever uses. */
    private static final String CLASSPATH_SCHEME = "classpath:";

    /** Carries the three area-code tables of {@code app/cpy/CSLKPCDY.cpy:L30}, {@code :L521} and {@code :L931}. */
    private static final String AREA_CODES_RESOURCE = "validation/nanpa-area-codes.json";

    /** Carries the state table of {@code app/cpy/CSLKPCDY.cpy:L1013}. */
    private static final String STATE_CODES_RESOURCE = "validation/us-state-codes.json";

    /** Carries the state-and-zip table of {@code app/cpy/CSLKPCDY.cpy:L1073}. */
    private static final String STATE_ZIP_RESOURCE = "validation/state-zip-prefixes.json";

    /** All three resources, in the order the production loader reads them. Exactly three, never four. */
    private static final List<String> ALL_RESOURCES =
            List.of(AREA_CODES_RESOURCE, STATE_CODES_RESOURCE, STATE_ZIP_RESOURCE);

    /** The provenance-only member every document opens with, ignored by name rather than by blanket tolerance. */
    private static final String METADATA_MEMBER = "_metadata";

    /** The {@code 88} condition name at {@code app/cpy/CSLKPCDY.cpy:L30}, verbatim. */
    private static final String PHONE_AREA_CODE_MEMBER = "VALID-PHONE-AREA-CODE";

    /** The {@code 88} condition name at {@code app/cpy/CSLKPCDY.cpy:L521}, verbatim. */
    private static final String GENERAL_PURPOSE_CODE_MEMBER = "VALID-GENERAL-PURP-CODE";

    /** The {@code 88} condition name at {@code app/cpy/CSLKPCDY.cpy:L931}, verbatim. */
    private static final String EASILY_RECOGNISABLE_CODE_MEMBER = "VALID-EASY-RECOG-AREA-CODE";

    /** The {@code 88} condition name at {@code app/cpy/CSLKPCDY.cpy:L1013}, verbatim. */
    private static final String US_STATE_CODE_MEMBER = "VALID-US-STATE-CODE";

    /** The {@code 88} condition name at {@code app/cpy/CSLKPCDY.cpy:L1073}, verbatim. */
    private static final String STATE_ZIP_COMBO_MEMBER = "VALID-US-STATE-ZIP-CD2-COMBO";

    /**
     * The {@code 02} subfield at {@code app/cpy/CSLKPCDY.cpy:L1314} that carries no {@code 88} level at all.
     * Named here only so that its absence from every document can be asserted rather than assumed.
     */
    private static final String UNVALIDATED_SUBFIELD = "LAST-3-OF-ZIP";

    /** Which table members each resource must carry, and no others besides {@link #METADATA_MEMBER}. */
    private static final Map<String, List<String>> MEMBERS_BY_RESOURCE = Map.of(
            AREA_CODES_RESOURCE,
            List.of(PHONE_AREA_CODE_MEMBER, GENERAL_PURPOSE_CODE_MEMBER, EASILY_RECOGNISABLE_CODE_MEMBER),
            STATE_CODES_RESOURCE, List.of(US_STATE_CODE_MEMBER),
            STATE_ZIP_RESOURCE, List.of(STATE_ZIP_COMBO_MEMBER));

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L30-L520}, excluding the interior comment at {@code :L440}. */
    private static final int PHONE_AREA_CODE_COUNT = 490;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L521-L930}. */
    private static final int GENERAL_PURPOSE_CODE_COUNT = 410;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L931-L1010}. */
    private static final int EASILY_RECOGNISABLE_CODE_COUNT = 80;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L1014-L1069}. */
    private static final int US_STATE_CODE_COUNT = 56;

    /** Counted from {@code app/cpy/CSLKPCDY.cpy:L1074-L1313}. */
    private static final int STATE_ZIP_COMBINATION_COUNT = 240;

    /** 490 + 410 + 80 + 56 + 240: every single-quoted literal in the member, counted once. */
    private static final int TOTAL_LITERAL_COUNT = 1_276;

    /** Declared width of an area code, {@code PIC XXX} at {@code app/cpy/CSLKPCDY.cpy:L24}. */
    private static final int AREA_CODE_WIDTH = 3;

    /** Declared width of a state code, {@code PIC X(2)} at {@code app/cpy/CSLKPCDY.cpy:L1012}. */
    private static final int STATE_CODE_WIDTH = 2;

    /** Declared width of the state-and-zip key, {@code PIC X(4)} at {@code app/cpy/CSLKPCDY.cpy:L1072}. */
    private static final int STATE_ZIP_KEY_WIDTH = 4;

    /**
     * The index at which the phone table stops being the general-purpose table and starts being the easily
     * recognisable one. The seam straddles the interior comment at {@code app/cpy/CSLKPCDY.cpy:L440}.
     */
    private static final int SEAM_INDEX = 410;

    /** The last general-purpose code, at index {@link #SEAM_INDEX} minus one of the phone table. */
    private static final String SEAM_LAST_ASCENDING_VALUE = "989";

    /** The first easily recognisable code, at index {@link #SEAM_INDEX} of the phone table. */
    private static final String SEAM_FIRST_RESTARTED_VALUE = "200";

    /** Lowest leading digit of an easily recognisable code: the rule runs over the digits 2 through 9. */
    private static final int EASILY_RECOGNISABLE_FIRST_DIGIT = 2;

    /** Highest leading digit of an easily recognisable code. */
    private static final int EASILY_RECOGNISABLE_LAST_DIGIT = 9;

    /** Repeated-digit suffixes per leading digit: {@code d00} through {@code d99}, ten of them. */
    private static final int EASILY_RECOGNISABLE_SUFFIXES_PER_DIGIT = 10;

    /** Index at which the out-of-numeric-order New York run begins in the state-and-zip table. */
    private static final int NEW_YORK_RUN_START_INDEX = 150;

    /**
     * The New York run exactly as the source holds it. The numeric suffixes climb to 63 and then drop back to
     * 10, which is the one genuine ordering anomaly in the table and is preserved rather than sorted.
     */
    private static final List<String> NEW_YORK_RUN =
            List.of("NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14");

    /**
     * Combinations a range-expanding loader would invent and the source does not hold. Each is a canary: its
     * appearance means somebody filled a numeric gap that the corpus leaves open.
     */
    private static final List<String> UNFILLED_GAPS = List.of("VT55", "TX74", "VI81", "AE99", "VA21");

    /**
     * The six two-character prefixes that appear in the state-and-zip table and are <em>not</em> members of the
     * state table: three military and diplomatic mail codes and three Freely Associated States. The gap is
     * source-intentional and must never be closed from either side.
     */
    private static final Set<String> ZIP_ONLY_PREFIXES = Set.of("AA", "AE", "AP", "FM", "MH", "PW");

    /** 56 state codes plus the six zip-only prefixes: the distinct prefix count of the state-and-zip table. */
    private static final int DISTINCT_ZIP_PREFIX_COUNT = 62;

    /**
     * The 56 state codes of {@code app/cpy/CSLKPCDY.cpy:L1014-L1069} in exact source order.
     *
     * <p>The ordering is alphabetical by full state <em>name</em>, then the District of Columbia, then the five
     * territories - which is why it is not alphabetical by code. Never sort, dedupe, re-case or gap-fill it.
     */
    private static final List<String> EXPECTED_US_STATE_CODES = List.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            "DC", "AS", "GU", "MP", "PR", "VI");

    /**
     * The 240 state-and-first-two-zip-digit combinations of {@code app/cpy/CSLKPCDY.cpy:L1074-L1313} in exact
     * source order, including the New York run and every unfilled numeric gap.
     */
    private static final List<String> EXPECTED_STATE_ZIP_COMBINATIONS = List.of(
            "AA34", "AE90", "AE91", "AE92", "AE93", "AE94", "AE95", "AE96",
            "AE97", "AE98", "AK99", "AL35", "AL36", "AP96", "AR71", "AR72",
            "AS96", "AZ85", "AZ86", "CA90", "CA91", "CA92", "CA93", "CA94",
            "CA95", "CA96", "CO80", "CO81", "CT60", "CT61", "CT62", "CT63",
            "CT64", "CT65", "CT66", "CT67", "CT68", "CT69", "DC20", "DC56",
            "DC88", "DE19", "FL32", "FL33", "FL34", "FM96", "GA30", "GA31",
            "GA39", "GU96", "HI96", "IA50", "IA51", "IA52", "ID83", "IL60",
            "IL61", "IL62", "IN46", "IN47", "KS66", "KS67", "KY40", "KY41",
            "KY42", "LA70", "LA71", "MA10", "MA11", "MA12", "MA13", "MA14",
            "MA15", "MA16", "MA17", "MA18", "MA19", "MA20", "MA21", "MA22",
            "MA23", "MA24", "MA25", "MA26", "MA27", "MA55", "MD20", "MD21",
            "ME39", "ME40", "ME41", "ME42", "ME43", "ME44", "ME45", "ME46",
            "ME47", "ME48", "ME49", "MH96", "MI48", "MI49", "MN55", "MN56",
            "MO63", "MO64", "MO65", "MO72", "MP96", "MS38", "MS39", "MT59",
            "NC27", "NC28", "ND58", "NE68", "NE69", "NH30", "NH31", "NH32",
            "NH33", "NH34", "NH35", "NH36", "NH37", "NH38", "NJ70", "NJ71",
            "NJ72", "NJ73", "NJ74", "NJ75", "NJ76", "NJ77", "NJ78", "NJ79",
            "NJ80", "NJ81", "NJ82", "NJ83", "NJ84", "NJ85", "NJ86", "NJ87",
            "NJ88", "NJ89", "NM87", "NM88", "NV88", "NV89", "NY50", "NY54",
            "NY63", "NY10", "NY11", "NY12", "NY13", "NY14", "OH43", "OH44",
            "OH45", "OK73", "OK74", "OR97", "PA15", "PA16", "PA17", "PA18",
            "PA19", "PR60", "PR61", "PR62", "PR63", "PR64", "PR65", "PR66",
            "PR67", "PR68", "PR69", "PR70", "PR71", "PR72", "PR73", "PR74",
            "PR75", "PR76", "PR77", "PR78", "PR79", "PR90", "PR91", "PR92",
            "PR93", "PR94", "PR95", "PR96", "PR97", "PR98", "PW96", "RI28",
            "RI29", "SC29", "SD57", "TN37", "TN38", "TX73", "TX75", "TX76",
            "TX77", "TX78", "TX79", "TX88", "UT84", "VA20", "VA22", "VA23",
            "VA24", "VI80", "VI82", "VI83", "VI84", "VI85", "VT50", "VT51",
            "VT52", "VT53", "VT54", "VT56", "VT57", "VT58", "VT59", "WA98",
            "WA99", "WI53", "WI54", "WV24", "WV25", "WV26", "WY82", "WY83");

    /** The one-based column of {@code CUST-ADDR-STATE-CD}, derived in the class documentation of this suite. */
    private static final int CUSTOMER_STATE_CODE_COLUMN = 235;

    /** The record width of {@code app/data/ASCII/custdata.txt}, which the offset arithmetic must total. */
    private static final int CUSTOMER_RECORD_WIDTH = 500;

    /** The row count of {@code app/data/ASCII/custdata.txt}. */
    private static final int CUSTOMER_ROW_COUNT = 50;

    /**
     * How many customer rows carry a state code the 56-entry table does not hold, and which codes they are.
     * Counted from the fixture at columns 235-236 and reproduced here so a drift in either direction is loud.
     */
    private static final Map<String, Integer> NON_MEMBER_STATE_CODE_ROWS =
            Map.of("AP", 1, "FM", 2, "MH", 1, "PW", 1);

    /** Five of fifty: the sum of {@link #NON_MEMBER_STATE_CODE_ROWS}. */
    private static final int NON_MEMBER_ROW_TOTAL = 5;

    /** Keys that would signal polymorphic deserialization and must appear in no document, at any depth. */
    private static final List<String> POLYMORPHIC_TYPE_HINTS = List.of("@class", "@type", "$type", "_class");

    /** Every state-and-zip combination is two uppercase letters then two digits. */
    private static final Pattern STATE_ZIP_KEY = Pattern.compile("^[A-Z]{2}[0-9]{2}$");

    /** Every area code is three digits. */
    private static final Pattern AREA_CODE = Pattern.compile("^[0-9]{3}$");

    /** Every state code is two uppercase letters. */
    private static final Pattern STATE_CODE = Pattern.compile("^[A-Z]{2}$");

    /** A JSON line comment or block comment opener, neither of which strict JSON permits. */
    private static final Pattern JSON_COMMENT = Pattern.compile("(?<!:)//|/\\*");

    /** A comma followed only by whitespace and a closing bracket or brace: a trailing comma. */
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*[\\]}]");

    /**
     * The resource loader the production bean is given, resolving {@code classpath:} locations and nothing else.
     *
     * <p>Reused for the document reads below so that this suite resolves the three resources through exactly the
     * mechanism the bean uses, rather than through a parallel path that could succeed where production fails.
     */
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    /**
     * The production wiring: the shipped resources, the real loader and a plain mapper with default settings.
     *
     * <p>Constructed into a {@code final} instance field under {@code Lifecycle.PER_CLASS}, which is deliberate
     * in both directions. A {@code static} holder would be global mutable state and would turn an absent
     * resource into an error thrown from class initialisation - harder to diagnose and impossible to exercise,
     * since the fail-fast path below needs a live constructor to catch. A field re-read per test method would
     * parse the same documents on every one of them for no gain. This is the arrangement that avoids reloading
     * without caching in static state.
     */
    private final ValidationLookupService service =
            new ValidationLookupService(resourceLoader, new ObjectMapper());

    /** The area-code document as a concrete, explicitly typed tree. Never bound to a loosely typed target. */
    private final JsonNode areaCodeDocument = readDocument(AREA_CODES_RESOURCE);

    /** The state-code document as a concrete, explicitly typed tree. */
    private final JsonNode stateCodeDocument = readDocument(STATE_CODES_RESOURCE);

    /** The state-and-zip document as a concrete, explicitly typed tree. */
    private final JsonNode stateZipDocument = readDocument(STATE_ZIP_RESOURCE);

    /**
     * The 50 customer records of {@code app/data/ASCII/custdata.txt}, read through the shared fixture loader.
     *
     * <p>Read once per class, untrimmed, at their full 500-byte width. Only columns 235-236 are ever sliced out
     * of a record by this suite: the fixture also carries a social security number and a date of birth, and
     * neither is read, echoed or logged anywhere here.
     */
    private final FixtureLoader.FixtureData customerFixture =
            FixtureLoader.load(FixtureLoader.Fixture.CUSTOMER);

    /**
     * Parses one classpath document into a concrete {@link JsonNode} tree, with an explicit charset.
     *
     * <p>Deliberately not bound to {@code Object} and not to a loosely typed map, and the mapper is a plain
     * instance with default typing left off, because these three documents are treated as untrusted input for
     * deserialization purposes even though they ship inside the artefact.
     *
     * @param resourcePath the classpath-relative location, without the {@code classpath:} scheme
     * @return the parsed document tree, never {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath, naming the resource
     * @throws UncheckedIOException if the resource cannot be read or parsed, naming the resource and preserving
     *     the original throwable as the cause
     */
    private JsonNode readDocument(final String resourcePath) {
        final Resource resource = resourceLoader.getResource(CLASSPATH_SCHEME + resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("classpath resource " + resourcePath + " is absent, so the "
                    + "CSLKPCDY data contract cannot be checked at all. Run ./mvnw -B -ntp test-compile to "
                    + "copy src/main/resources into target/classes");
        }
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            final JsonNode root = new ObjectMapper().readTree(reader);
            if (root == null) {
                throw new IllegalStateException(
                        "classpath resource " + resourcePath + " parsed to nothing at all");
            }
            return root;
        } catch (final IOException cause) {
            throw new UncheckedIOException("cannot read classpath resource " + resourcePath, cause);
        }
    }

    /**
     * Reads one classpath document as raw bytes, so file hygiene can be asserted before any parsing.
     *
     * @param resourcePath the classpath-relative location, without the {@code classpath:} scheme
     * @return the document's bytes exactly as they ship
     * @throws UncheckedIOException if the resource cannot be read, preserving the original throwable
     */
    private byte[] readBytes(final String resourcePath) {
        final Resource resource = resourceLoader.getResource(CLASSPATH_SCHEME + resourcePath);
        try (InputStream stream = resource.getInputStream()) {
            return stream.readAllBytes();
        } catch (final IOException cause) {
            throw new UncheckedIOException("cannot read classpath resource " + resourcePath, cause);
        }
    }

    /**
     * Reads one classpath document as text, decoded with an explicit charset.
     *
     * @param resourcePath the classpath-relative location, without the {@code classpath:} scheme
     * @return the document's whole text
     */
    private String readText(final String resourcePath) {
        return new String(readBytes(resourcePath), StandardCharsets.UTF_8);
    }

    /**
     * Returns the parsed tree of one of the three resources.
     *
     * @param resourcePath one of {@link #ALL_RESOURCES}
     * @return the already-parsed document tree
     * @throws IllegalArgumentException if {@code resourcePath} is not one of the three
     */
    private JsonNode documentFor(final String resourcePath) {
        return switch (resourcePath) {
            case AREA_CODES_RESOURCE -> areaCodeDocument;
            case STATE_CODES_RESOURCE -> stateCodeDocument;
            case STATE_ZIP_RESOURCE -> stateZipDocument;
            default -> throw new IllegalArgumentException(
                    resourcePath + " is not one of the three CSLKPCDY resources " + ALL_RESOURCES);
        };
    }

    /**
     * Returns one table member of a document as a list of strings, in document order.
     *
     * <p>Reading the array directly rather than through the service is what lets a shape defect be attributed to
     * the document rather than to the loader.
     *
     * @param resourcePath one of {@link #ALL_RESOURCES}
     * @param memberName the {@code 88}-level condition name, spelled exactly as the copybook spells it
     * @return the member's elements in order, each as text
     */
    private List<String> arrayMember(final String resourcePath, final String memberName) {
        final JsonNode member = documentFor(resourcePath).get(memberName);
        assertThat(member)
                .as("%s must carry the table member %s, which is the %s condition name spelled verbatim",
                        resourcePath, memberName, "app/cpy/CSLKPCDY.cpy 88-level")
                .isNotNull();
        assertThat(member.isArray())
                .as("%s member %s must be a JSON array, because the source table is an ordered list of "
                        + "literals", resourcePath, memberName)
                .isTrue();
        final List<String> values = new ArrayList<>(member.size());
        for (final JsonNode element : member) {
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    /**
     * Returns the top-level member names of a document, in document order.
     *
     * @param resourcePath one of {@link #ALL_RESOURCES}
     * @return the member names, ordered as the document declares them
     */
    private List<String> memberNames(final String resourcePath) {
        final List<String> names = new ArrayList<>();
        documentFor(resourcePath).fieldNames().forEachRemaining(names::add);
        return List.copyOf(names);
    }

    /**
     * Collects every object key in a document, at every depth.
     *
     * <p>Used only to prove a negative - that no polymorphic type hint is present anywhere - which a top-level
     * scan could not establish.
     *
     * @param resourcePath one of {@link #ALL_RESOURCES}
     * @return every key found, deduplicated
     */
    private Set<String> everyKeyAtEveryDepth(final String resourcePath) {
        final Set<String> keys = new TreeSet<>();
        collectKeys(documentFor(resourcePath), keys);
        return keys;
    }

    /**
     * Walks a tree adding every object key to an accumulator.
     *
     * @param node the subtree to walk
     * @param keys the accumulator
     */
    private static void collectKeys(final JsonNode node, final Set<String> keys) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                keys.add(name);
                collectKeys(node.get(name), keys);
            });
        } else if (node.isArray()) {
            for (final JsonNode child : node) {
                collectKeys(child, keys);
            }
        }
    }

    /**
     * Builds a resource loader that answers every location with one supplied resource.
     *
     * <p>The production constructor loads the phone table from {@code validation/nanpa-area-codes.json} first,
     * so a single substituted resource deterministically fails that first load and an assertion can name it.
     *
     * @param resource the resource to return for any location
     * @return a loader that ignores the requested location
     */
    private static ResourceLoader loaderReturning(final Resource resource) {
        return new ResourceLoader() {

            @Override
            public Resource getResource(final String location) {
                return resource;
            }

            @Override
            public ClassLoader getClassLoader() {
                return LookupTableResourceContractTest.class.getClassLoader();
            }
        };
    }

    /**
     * Constructs the production bean against a substituted resource and returns the abend it raised.
     *
     * @param resource the resource every load will see
     * @return the fatal exception the constructor threw, never {@code null} when the resource is defective
     */
    private static FatalProcessingException loadFailureFor(final Resource resource) {
        return catchThrowableOfType(FatalProcessingException.class,
                () -> new ValidationLookupService(loaderReturning(resource), new ObjectMapper()));
    }

    /**
     * Wraps a JSON body as an in-memory resource that exists and is readable.
     *
     * @param json the document body
     * @return a resource yielding that body as UTF-8
     */
    private static Resource jsonResource(final String json) {
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generates the easily recognisable area codes from their rule rather than restating the data.
     *
     * <p>The rule is every repeated-digit pattern {@code d00} through {@code d99} for each leading digit 2
     * through 9: eight leading digits by ten suffixes. Asserting the resource against a generated list proves
     * the rule holds, where asserting it against a transcription would only prove the transcription agrees
     * with itself.
     *
     * @return the 80 codes in the order the rule produces them, which is the order the source holds
     */
    private static List<String> generateEasilyRecognisableCodes() {
        final List<String> generated = new ArrayList<>(EASILY_RECOGNISABLE_CODE_COUNT);
        for (int digit = EASILY_RECOGNISABLE_FIRST_DIGIT; digit <= EASILY_RECOGNISABLE_LAST_DIGIT; digit++) {
            for (int suffix = 0; suffix < EASILY_RECOGNISABLE_SUFFIXES_PER_DIGIT; suffix++) {
                generated.add("" + digit + suffix + suffix);
            }
        }
        return List.copyOf(generated);
    }

    /**
     * Returns a naturally sorted copy of a table, used only to prove that the original is <em>not</em> sorted.
     *
     * <p>Comparing a table against its own sort is the only assertion that catches a well-meaning sort, because
     * every membership and cardinality check survives one untouched.
     *
     * @param values the table in source order
     * @return the same values in natural order
     */
    private static List<String> sortedCopy(final List<String> values) {
        return values.stream().sorted().toList();
    }

    /**
     * Returns the state code of one customer record, sliced at its PIC-derived column.
     *
     * <p>Columns 235-236 and nothing else. The surrounding fields include a social security number at columns
     * 280-288 and a date of birth at columns 309-318, so this is the only slice this suite ever takes.
     *
     * @param rowIndex the zero-based record index
     * @return the two-character state code exactly as the fixture holds it, untrimmed
     */
    private String fixtureStateCode(final int rowIndex) {
        return customerFixture.field(rowIndex, CUSTOMER_STATE_CODE_COLUMN, STATE_CODE_WIDTH);
    }

    /**
     * Counts, per state code, how many fixture rows carry a code the state table does not hold.
     *
     * <p>Keyed by code and never by row content: the value is a count, so no customer field other than the
     * state code can reach an assertion message through this method.
     *
     * @return the census, keyed by the offending state code
     */
    private Map<String, Integer> nonMemberStateCodeCensus() {
        final Map<String, Integer> census = new LinkedHashMap<>();
        for (int row = 0; row < customerFixture.recordCount(); row++) {
            final String code = fixtureStateCode(row);
            if (!EXPECTED_US_STATE_CODES.contains(code)) {
                census.merge(code, 1, Integer::sum);
            }
        }
        return Map.copyOf(census);
    }

    /** The collapse rule: five source tables, three classpath resources, and nothing in between. */
    @Nested
    @DisplayName("the five 88-level tables collapse onto exactly three classpath resources")
    final class ResourceCollapseRule {

        @Test
        @DisplayName("exactly three resources exist and between them carry all 1,276 literals")
        void exactlyThreeResourcesCarryEveryLiteral() {
            assertThat(ALL_RESOURCES)
                    .as("the collapse rule is three resources, not two and not four: the three area-code "
                            + "tables share one document because app/cpy/CSLKPCDY.cpy:L30, :L521 and :L931 "
                            + "edit the same PIC XXX field at :L24, while the state and state-and-zip tables "
                            + "edit their own fields at :L1012 and :L1072")
                    .hasSize(3)
                    .doesNotHaveDuplicates();

            final int carried = arrayMember(AREA_CODES_RESOURCE, PHONE_AREA_CODE_MEMBER).size()
                    + arrayMember(AREA_CODES_RESOURCE, GENERAL_PURPOSE_CODE_MEMBER).size()
                    + arrayMember(AREA_CODES_RESOURCE, EASILY_RECOGNISABLE_CODE_MEMBER).size()
                    + arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER).size()
                    + arrayMember(STATE_ZIP_RESOURCE, STATE_ZIP_COMBO_MEMBER).size();

            assertThat(carried)
                    .as("every single-quoted literal of app/cpy/CSLKPCDY.cpy must survive the move into data. "
                            + "The member spans 1,318 lines with its value-bearing lines at :L30-L1313, and "
                            + "1,276 is what a delimiter-based extraction of those lines counts")
                    .isEqualTo(TOTAL_LITERAL_COUNT);
        }

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("each resource carries exactly its own table members and no others")
        void eachResourceCarriesExactlyItsOwnMembers(final String resourcePath) {
            final List<String> expected = new ArrayList<>();
            expected.add(METADATA_MEMBER);
            expected.addAll(MEMBERS_BY_RESOURCE.get(resourcePath));

            assertThat(memberNames(resourcePath))
                    .as("%s must declare exactly %s and nothing else. An extra top-level member is either a "
                            + "table that belongs in another document or a wrapper the loader does not read; "
                            + "either way it is data nobody validates", resourcePath, expected)
                    .containsExactlyElementsOf(expected);
        }

        @Test
        @DisplayName("the head-line value asymmetry survived extraction, in both directions")
        void theHeadLineValueAsymmetrySurvivedExtraction() {
            assertThat(arrayMember(AREA_CODES_RESOURCE, PHONE_AREA_CODE_MEMBER))
                    .as("app/cpy/CSLKPCDY.cpy:L30 reads \"88 VALID-PHONE-AREA-CODE VALUES '201',\" - the "
                            + "first value sits on the 88 head line itself. An extractor that skips head "
                            + "lines drops '201' here and leaves 489 values starting at '202'")
                    .hasSize(PHONE_AREA_CODE_COUNT)
                    .startsWith("201");

            assertThat(arrayMember(AREA_CODES_RESOURCE, GENERAL_PURPOSE_CODE_MEMBER))
                    .as("app/cpy/CSLKPCDY.cpy:L521 also carries its first value, '201', on the head line")
                    .hasSize(GENERAL_PURPOSE_CODE_COUNT)
                    .startsWith("201");

            assertThat(arrayMember(AREA_CODES_RESOURCE, EASILY_RECOGNISABLE_CODE_MEMBER))
                    .as("app/cpy/CSLKPCDY.cpy:L931 carries '200' on the head line too, after two spaces "
                            + "rather than one - a formatting difference that must not become a data one")
                    .hasSize(EASILY_RECOGNISABLE_CODE_COUNT)
                    .startsWith("200");

            assertThat(arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER))
                    .as("app/cpy/CSLKPCDY.cpy:L1013 ends at \"VALUES\" with a trailing space and no value; "
                            + "the first state code 'AL' is on :L1014. An extractor that assumes the head "
                            + "line always holds a value invents a 57th entry here")
                    .hasSize(US_STATE_CODE_COUNT)
                    .startsWith("AL");

            assertThat(arrayMember(STATE_ZIP_RESOURCE, STATE_ZIP_COMBO_MEMBER))
                    .as("app/cpy/CSLKPCDY.cpy:L1073 likewise holds no value; 'AA34' is on :L1074")
                    .hasSize(STATE_ZIP_COMBINATION_COUNT)
                    .startsWith("AA34");
        }
    }

    /** The central structural invariant of the area-code resource. */
    @Nested
    @DisplayName("the three area-code tables are an order-preserving disjoint partition")
    final class AreaCodePartition {

        @Test
        @DisplayName("all five cardinalities are exactly as the copybook counts")
        void allFiveCardinalitiesAreExact() {
            assertThat(service.getValidPhoneAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L30 VALID-PHONE-AREA-CODE holds 490 literals")
                    .hasSize(PHONE_AREA_CODE_COUNT);
            assertThat(service.getValidGeneralPurposeAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L521 VALID-GENERAL-PURP-CODE holds 410 literals")
                    .hasSize(GENERAL_PURPOSE_CODE_COUNT);
            assertThat(service.getValidEasilyRecognisableAreaCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L931 VALID-EASY-RECOG-AREA-CODE holds 80 literals")
                    .hasSize(EASILY_RECOGNISABLE_CODE_COUNT);
            assertThat(service.getValidUsStateCodes())
                    .as("app/cpy/CSLKPCDY.cpy:L1013 VALID-US-STATE-CODE holds 56 literals")
                    .hasSize(US_STATE_CODE_COUNT);
            assertThat(service.getValidStateZipCodeCombinations())
                    .as("app/cpy/CSLKPCDY.cpy:L1073 VALID-US-STATE-ZIP-CD2-COMBO holds 240 literals")
                    .hasSize(STATE_ZIP_COMBINATION_COUNT);

            assertThat(PHONE_AREA_CODE_COUNT + GENERAL_PURPOSE_CODE_COUNT + EASILY_RECOGNISABLE_CODE_COUNT
                    + US_STATE_CODE_COUNT + STATE_ZIP_COMBINATION_COUNT)
                    .as("the five counts must close on the literal census of the whole member")
                    .isEqualTo(TOTAL_LITERAL_COUNT);
        }

        @Test
        @DisplayName("the general-purpose table followed by the easily recognisable one IS the phone table")
        void theTwoHalvesConcatenateToThePhoneTableInOrder() {
            final List<String> concatenated =
                    new ArrayList<>(service.getValidGeneralPurposeAreaCodes());
            concatenated.addAll(service.getValidEasilyRecognisableAreaCodes());

            assertThat(service.getValidPhoneAreaCodes())
                    .as("this is the invariant, not the arithmetic. 410 + 80 = 490 would hold under any "
                            + "reshuffle; what app/cpy/CSLKPCDY.cpy actually declares is that "
                            + "VALID-PHONE-AREA-CODE at :L30 is VALID-GENERAL-PURP-CODE at :L521 followed by "
                            + "VALID-EASY-RECOG-AREA-CODE at :L931, element for element, in that order. "
                            + "containsExactlyElementsOf is order sensitive on purpose: set equality would "
                            + "pass on a scrambled table and the order is the contract")
                    .containsExactlyElementsOf(concatenated);
        }

        @Test
        @DisplayName("the two halves share no element, so the union is a partition rather than an overlap")
        void theTwoHalvesAreDisjoint() {
            assertThat(service.getValidGeneralPurposeAreaCodes())
                    .as("a code present in both halves would make the phone table's 490 an accident of "
                            + "duplication rather than a partition of 410 and 80")
                    .doesNotContainAnyElementsOf(service.getValidEasilyRecognisableAreaCodes());
        }

        @Test
        @DisplayName("the phone table carries no duplicate at all")
        void thePhoneTableCarriesNoDuplicate() {
            final List<String> codes = arrayMember(AREA_CODES_RESOURCE, PHONE_AREA_CODE_MEMBER);

            assertThat(codes)
                    .as("the document is a JSON array and could repeat an element where the loaded set "
                            + "cannot, so the duplicate check belongs on the document. app/cpy/CSLKPCDY.cpy "
                            + "declares 490 distinct literals between :L30 and :L520")
                    .doesNotHaveDuplicates()
                    .hasSize(PHONE_AREA_CODE_COUNT);
            assertThat(new LinkedHashSet<>(codes))
                    .as("deduplicating must therefore change nothing")
                    .hasSize(PHONE_AREA_CODE_COUNT);
        }

        @Test
        @DisplayName("the two witness codes sit on opposite sides of the partition")
        void theWitnessCodesSitOnOppositeSides() {
            assertThat(service.getValidPhoneAreaCodes())
                    .as("'201' opens both the phone table at :L30 and the general-purpose table at :L521")
                    .contains("201");
            assertThat(service.getValidGeneralPurposeAreaCodes()).as("'201' is general purpose")
                    .contains("201");
            assertThat(service.getValidEasilyRecognisableAreaCodes())
                    .as("'201' is a geographic code and is not easily recognisable, so it must be absent "
                            + "from the table at :L931")
                    .doesNotContain("201");

            assertThat(service.getValidPhoneAreaCodes())
                    .as("'200' opens the easily recognisable table at :L931 and reappears in the phone "
                            + "table at the seam")
                    .contains("200");
            assertThat(service.getValidEasilyRecognisableAreaCodes()).as("'200' is easily recognisable")
                    .contains("200");
            assertThat(service.getValidGeneralPurposeAreaCodes())
                    .as("'200' is a service code, so it must be absent from the general-purpose table")
                    .doesNotContain("200");
        }

        @Test
        @DisplayName("every area code across all three tables is exactly three digits")
        void everyAreaCodeIsThreeDigits() {
            final List<String> everyCode = new ArrayList<>(service.getValidPhoneAreaCodes());
            everyCode.addAll(service.getValidGeneralPurposeAreaCodes());
            everyCode.addAll(service.getValidEasilyRecognisableAreaCodes());

            assertThat(everyCode)
                    .as("980 values across the three tables, each edited against "
                            + "WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX at app/cpy/CSLKPCDY.cpy:L24")
                    .hasSize(PHONE_AREA_CODE_COUNT + GENERAL_PURPOSE_CODE_COUNT
                            + EASILY_RECOGNISABLE_CODE_COUNT);
            assertThat(everyCode)
                    .allSatisfy(code -> assertThat(AREA_CODE.matcher(code).matches())
                            .as("area code %s must be exactly %d digits, from PIC XXX at "
                                    + "app/cpy/CSLKPCDY.cpy:L24. A numeric JSON encoding would have "
                                    + "destroyed the leading structure of a value such as '200'",
                                    code, AREA_CODE_WIDTH)
                            .isTrue());
        }

        @Test
        @DisplayName("the phone table is NOT globally sorted, and its seam is where the copybook puts it")
        void thePhoneTableIsNotSortedAndItsSeamIsAtTheDocumentedIndex() {
            final List<String> codes = arrayMember(AREA_CODES_RESOURCE, PHONE_AREA_CODE_MEMBER);

            assertThat(codes)
                    .as("VALID-PHONE-AREA-CODE ascends within each half and breaks at the join, so the "
                            + "table as a whole is not sorted. A test that asserted it WAS sorted would "
                            + "fail, and a loader that sorted it would look tidier while destroying the "
                            + "partition proved above")
                    .isNotEqualTo(sortedCopy(codes));

            assertThat(codes.get(SEAM_INDEX - 1))
                    .as("index %d is the last general-purpose code, on app/cpy/CSLKPCDY.cpy:L439",
                            SEAM_INDEX - 1)
                    .isEqualTo(SEAM_LAST_ASCENDING_VALUE);
            assertThat(codes.get(SEAM_INDEX))
                    .as("index %d is the first easily recognisable code, on app/cpy/CSLKPCDY.cpy:L441. The "
                            + "two lines straddle the interior comment at :L440, \"Easily recognizable "
                            + "codes begin here.\", which is why the sequence drops from 989 back to 200",
                            SEAM_INDEX)
                    .isEqualTo(SEAM_FIRST_RESTARTED_VALUE);

            assertThat(codes.subList(0, SEAM_INDEX))
                    .as("the first half ascends throughout, so the break really is a single seam rather "
                            + "than general disorder")
                    .isSorted();
            assertThat(codes.subList(SEAM_INDEX, codes.size()))
                    .as("and the second half ascends throughout as well")
                    .isSorted();
        }

        @ParameterizedTest
        @CsvSource({"0, 201", "122, 431", "245, 683", "367, 912", "489, 999"})
        @DisplayName("the indexed checkpoints of the phone table hold")
        void theIndexedCheckpointsOfThePhoneTableHold(final int index, final String expected) {
            assertThat(arrayMember(AREA_CODES_RESOURCE, PHONE_AREA_CODE_MEMBER).get(index))
                    .as("VALID-PHONE-AREA-CODE index %d must be '%s'. Indexed checkpoints spread across "
                            + "the table catch a shift that a count and a first-and-last check would both "
                            + "survive", index, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("both ends of the phone table are pinned")
        void bothEndsOfThePhoneTableArePinned() {
            assertThat(arrayMember(AREA_CODES_RESOURCE, PHONE_AREA_CODE_MEMBER))
                    .as("the first five run 201 202 203 204 205 from app/cpy/CSLKPCDY.cpy:L30 onward, and "
                            + "the last five are the repeated-digit tail 955 966 977 988 999 at :L520")
                    .startsWith("201", "202", "203", "204", "205")
                    .endsWith("955", "966", "977", "988", "999");
        }

        @ParameterizedTest
        @CsvSource({"0, 201", "102, 403", "205, 613", "307, 804", "409, 989"})
        @DisplayName("the indexed checkpoints of the general-purpose table hold")
        void theIndexedCheckpointsOfTheGeneralPurposeTableHold(final int index, final String expected) {
            assertThat(arrayMember(AREA_CODES_RESOURCE, GENERAL_PURPOSE_CODE_MEMBER).get(index))
                    .as("VALID-GENERAL-PURP-CODE index %d must be '%s'", index, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("the general-purpose table ascends throughout and ends where the copybook ends it")
        void theGeneralPurposeTableAscendsThroughout() {
            assertThat(arrayMember(AREA_CODES_RESOURCE, GENERAL_PURPOSE_CODE_MEMBER))
                    .as("unlike the phone table this one has no seam: app/cpy/CSLKPCDY.cpy:L521-L930 is "
                            + "one ascending run ending 983 984 985 986 989 - note that 987 and 988 are "
                            + "simply not assigned, and are not gap-filled")
                    .isSorted()
                    .endsWith("983", "984", "985", "986", "989");
        }

        @Test
        @DisplayName("the easily recognisable table satisfies its generative rule, element for element")
        void theEasilyRecognisableTableSatisfiesItsGenerativeRule() {
            final List<String> generated = generateEasilyRecognisableCodes();

            assertThat(generated)
                    .as("the rule itself must produce 8 leading digits by 10 repeated-digit suffixes")
                    .hasSize(EASILY_RECOGNISABLE_CODE_COUNT)
                    .startsWith("200", "211", "222")
                    .endsWith("977", "988", "999");

            assertThat(arrayMember(AREA_CODES_RESOURCE, EASILY_RECOGNISABLE_CODE_MEMBER))
                    .as("VALID-EASY-RECOG-AREA-CODE at app/cpy/CSLKPCDY.cpy:L931-L1010 is exactly the set "
                            + "of patterns d00, d11 ... d99 for d in 2..9, in that order. Asserting the "
                            + "resource against a generated list proves the rule holds; asserting it "
                            + "against a transcription would only prove the transcription agrees with "
                            + "itself. The data is never generated - it is compared")
                    .containsExactlyElementsOf(generated);
        }
    }

    /** The 56 state codes, whose ordering is the whole point. */
    @Nested
    @DisplayName("the 56 state codes are held in source order, which is not alphabetical by code")
    final class StateCodeTable {

        @Test
        @DisplayName("the resource holds exactly the 56 source entries, in source order")
        void theResourceHoldsExactlyTheFiftySixSourceEntriesInOrder() {
            assertThat(arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER))
                    .as("VALID-US-STATE-CODE at app/cpy/CSLKPCDY.cpy:L1013 runs from 'AL' on :L1014 to "
                            + "'VI' on :L1069. No more and no fewer: adding a code widens what the screen "
                            + "edit accepts and removing one narrows it, and either is a behaviour change")
                    .containsExactlyElementsOf(EXPECTED_US_STATE_CODES);
        }

        @Test
        @DisplayName("every state code is two uppercase letters")
        void everyStateCodeIsTwoUppercaseLetters() {
            assertThat(service.getValidUsStateCodes())
                    .allSatisfy(code -> assertThat(STATE_CODE.matcher(code).matches())
                            .as("state code %s must be exactly %d uppercase letters, from "
                                    + "US-STATE-CODE-TO-EDIT PIC X(2) at app/cpy/CSLKPCDY.cpy:L1012 - a "
                                    + "declaration written there with two spaces before PIC, which is a "
                                    + "formatting quirk and not a width one", code, STATE_CODE_WIDTH)
                            .isTrue());
        }

        @ParameterizedTest
        @CsvSource({"0, AL", "14, IA", "28, NH", "42, TX", "55, VI"})
        @DisplayName("the indexed checkpoints of the state table hold")
        void theIndexedCheckpointsOfTheStateTableHold(final int index, final String expected) {
            assertThat(arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER).get(index))
                    .as("VALID-US-STATE-CODE index %d must be '%s', counting from 'AL' at "
                            + "app/cpy/CSLKPCDY.cpy:L1014", index, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("both ends of the state table are pinned")
        void bothEndsOfTheStateTableArePinned() {
            assertThat(arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER))
                    .as("the first five are AL AK AZ AR CA and the last five are the territories "
                            + "AS GU MP PR VI, which follow DC at index 50")
                    .startsWith("AL", "AK", "AZ", "AR", "CA")
                    .endsWith("AS", "GU", "MP", "PR", "VI");
        }

        @Test
        @DisplayName("the list is DELIBERATELY not alphabetically sorted by code")
        void theListIsDeliberatelyNotSortedByCode() {
            final List<String> codes = arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER);

            assertThat(codes)
                    .as("app/cpy/CSLKPCDY.cpy:L1014-L1069 orders the codes alphabetically by full state "
                            + "NAME - Alabama, Alaska, Arizona, Arkansas - then DC, then the five "
                            + "territories. By code that reads AL AK AZ AR, which is unsorted. Never sort, "
                            + "never dedupe, never re-case and never gap-fill this table")
                    .isNotEqualTo(sortedCopy(codes));

            assertThat(sortedCopy(codes))
                    .as("a natural sort would open AK AL AR AS, putting Alaska before Alabama because 'AK' "
                            + "precedes 'AL' as text. That is the shortest proof the two orderings "
                            + "genuinely differ rather than merely appearing to")
                    .startsWith("AK", "AL", "AR", "AS");
            assertThat(codes)
                    .as("the source instead opens AL AK AZ AR, which is Alabama, Alaska, Arizona, Arkansas "
                            + "- alphabetical by the state NAME that the code abbreviates")
                    .startsWith("AL", "AK", "AZ", "AR");
        }

        @ParameterizedTest
        @CsvSource({
            "AL, AK, 0", "IN, IA, 13", "ME, MD, 18", "MS, MO, 23", "NE, NV, 26", "NV, NH, 27",
            "NY, NC, 31", "NC, ND, 32", "VT, VA, 44", "WA, WV, 46", "WV, WI, 47", "WI, WY, 48",
            "WY, DC, 49", "DC, AS, 50",
        })
        @DisplayName("each sorter-destroying adjacency is preserved at its own index")
        void eachSorterDestroyingAdjacencyIsPreserved(final String first, final String second,
                final int firstIndex) {
            final List<String> codes = arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER);

            assertThat(codes.get(firstIndex))
                    .as("index %d of VALID-US-STATE-CODE must be '%s'", firstIndex, first)
                    .isEqualTo(first);
            assertThat(codes.get(firstIndex + 1))
                    .as("'%s' must be followed immediately by '%s' at index %d. This pair is a canary: a "
                            + "naive alphabetical sort would reorder it, so its survival proves the source "
                            + "ordering of app/cpy/CSLKPCDY.cpy:L1014-L1069 was preserved rather than "
                            + "merely its membership", first, second, firstIndex + 1)
                    .isEqualTo(second);
        }
    }

    /** The 240 state-and-zip combinations, including the one genuine ordering anomaly in the corpus. */
    @Nested
    @DisplayName("the 240 state-and-zip combinations keep their source order, anomaly and gaps")
    final class StateZipCombinationTable {

        @Test
        @DisplayName("the resource holds exactly the 240 source entries, in source order")
        void theResourceHoldsExactlyTheTwoHundredAndFortySourceEntriesInOrder() {
            assertThat(arrayMember(STATE_ZIP_RESOURCE, STATE_ZIP_COMBO_MEMBER))
                    .as("VALID-US-STATE-ZIP-CD2-COMBO at app/cpy/CSLKPCDY.cpy:L1073 runs from 'AA34' on "
                            + ":L1074 to 'WY83' on :L1313. The comment naming this table is at :L1070")
                    .containsExactlyElementsOf(EXPECTED_STATE_ZIP_COMBINATIONS)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("every combination is two uppercase letters followed by two digits")
        void everyCombinationIsTwoLettersThenTwoDigits() {
            assertThat(service.getValidStateZipCodeCombinations())
                    .allSatisfy(combination -> assertThat(STATE_ZIP_KEY.matcher(combination).matches())
                            .as("combination %s must be %d characters shaped as two letters then two "
                                    + "digits, from US-STATE-AND-FIRST-ZIP2 PIC X(4) at "
                                    + "app/cpy/CSLKPCDY.cpy:L1072", combination, STATE_ZIP_KEY_WIDTH)
                            .isTrue());
        }

        @ParameterizedTest
        @CsvSource({"0, AA34", "60, KS66", "120, NH33", "180, PR71", "239, WY83"})
        @DisplayName("the indexed checkpoints of the state-and-zip table hold")
        void theIndexedCheckpointsOfTheStateZipTableHold(final int index, final String expected) {
            assertThat(arrayMember(STATE_ZIP_RESOURCE, STATE_ZIP_COMBO_MEMBER).get(index))
                    .as("VALID-US-STATE-ZIP-CD2-COMBO index %d must be '%s'", index, expected)
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("THE NEW YORK ANOMALY: suffixes climb to 63 and then drop back to 10")
        void theNewYorkRunIsPreservedOutOfNumericOrder() {
            final List<String> combinations = arrayMember(STATE_ZIP_RESOURCE, STATE_ZIP_COMBO_MEMBER);

            assertThat(combinations.subList(NEW_YORK_RUN_START_INDEX,
                    NEW_YORK_RUN_START_INDEX + NEW_YORK_RUN.size()))
                    .as("indices %d to %d hold the one genuinely out-of-order run in the whole table: "
                            + "NY50 NY54 NY63 and then NY10 NY11 NY12 NY13 NY14. The numeric suffixes go "
                            + "50, 54, 63 and then back to 10. This is exactly what app/cpy/CSLKPCDY.cpy "
                            + "declares and it is preserved rather than sorted - the run is the reason the "
                            + "accessors return a sequenced set rather than a plain one",
                            NEW_YORK_RUN_START_INDEX,
                            NEW_YORK_RUN_START_INDEX + NEW_YORK_RUN.size() - 1)
                    .containsExactlyElementsOf(NEW_YORK_RUN);

            assertThat(combinations)
                    .as("and the table as a whole is therefore not sorted")
                    .isNotEqualTo(sortedCopy(combinations));
        }

        @ParameterizedTest
        @ValueSource(strings = {"VT55", "TX74", "VI81", "AE99", "VA21"})
        @DisplayName("each unfilled numeric gap stays unfilled")
        void eachUnfilledNumericGapStaysUnfilled(final String absent) {
            assertThat(service.getValidStateZipCodeCombinations())
                    .as("'%s' is not declared anywhere in app/cpy/CSLKPCDY.cpy:L1074-L1313 and must not "
                            + "appear. Each of these is a canary against a range-expanding loader that "
                            + "interpolated between two declared suffixes - VT54 to VT56, TX73 to TX75, "
                            + "VI80 to VI82, AE98 to AK99, VA20 to VA22", absent)
                    .doesNotContain(absent);
            assertThat(UNFILLED_GAPS)
                    .as("the gap census itself must stay in step with the parameters of this test")
                    .contains(absent);
        }

        @Test
        @DisplayName("the larger discontinuities are preserved as jumps rather than smoothed over")
        void theLargerDiscontinuitiesArePreserved() {
            final List<String> combinations = arrayMember(STATE_ZIP_RESOURCE, STATE_ZIP_COMBO_MEMBER);

            assertThat(combinations.indexOf("MO72"))
                    .as("'MO65' is followed immediately by 'MO72': suffixes 66 through 71 are simply not "
                            + "declared at app/cpy/CSLKPCDY.cpy")
                    .isEqualTo(combinations.indexOf("MO65") + 1);
            assertThat(combinations.indexOf("MA55"))
                    .as("'MA27' is followed immediately by 'MA55', a jump of twenty-seven suffixes")
                    .isEqualTo(combinations.indexOf("MA27") + 1);
            assertThat(combinations.stream().filter(value -> value.startsWith("ME")).toList())
                    .as("the Maine run starts at 'ME39' rather than at 'ME00' or 'ME01'")
                    .startsWith("ME39")
                    .hasSize(11);
            assertThat(combinations.stream().filter(value -> value.startsWith("DC")).toList())
                    .as("the District of Columbia is spread across three widely separated prefixes")
                    .containsExactly("DC20", "DC56", "DC88");
            assertThat(combinations.indexOf("PR90"))
                    .as("Puerto Rico splits into PR60 through PR79 and then PR90 through PR98, so 'PR79' "
                            + "is followed immediately by 'PR90' with ten suffixes skipped")
                    .isEqualTo(combinations.indexOf("PR79") + 1);
            assertThat(combinations.stream().filter(value -> value.startsWith("PR")).toList())
                    .as("twenty in the first PR run and nine in the second")
                    .hasSize(29)
                    .endsWith("PR98");
        }
    }

    /**
     * The 56-versus-62 asymmetry: the most important cross-resource invariant here, and the reason this class
     * covers all three documents instead of being split into one suite per resource.
     *
     * <p>Splitting would orphan this group. The invariant relates the state resource to the state-and-zip
     * resource, so no per-resource suite could hold it, and three suites would each need the same loading
     * setup - duplication the repository conventions exist to prevent.
     */
    @Nested
    @DisplayName("56 state codes versus 62 zip prefixes: the six-code gap is source-intentional")
    final class CrossResourceAsymmetry {

        /**
         * Collects the distinct two-character prefixes of the state-and-zip table.
         *
         * @return the prefixes, deduplicated and naturally ordered so the assertion message is stable
         */
        private Set<String> distinctZipPrefixes() {
            final Set<String> prefixes = new TreeSet<>();
            for (final String combination : service.getValidStateZipCodeCombinations()) {
                prefixes.add(combination.substring(0, STATE_CODE_WIDTH));
            }
            return prefixes;
        }

        @Test
        @DisplayName("the state-and-zip table has exactly 62 distinct two-character prefixes")
        void theStateZipTableHasExactlySixtyTwoDistinctPrefixes() {
            assertThat(distinctZipPrefixes())
                    .as("240 combinations at app/cpy/CSLKPCDY.cpy:L1074-L1313 reduce to 62 distinct "
                            + "prefixes. That figure is the left-hand side of the whole asymmetry")
                    .hasSize(DISTINCT_ZIP_PREFIX_COUNT);
        }

        @Test
        @DisplayName("direction one: every one of the 56 state codes appears as a zip prefix")
        void everyStateCodeAppearsAsAZipPrefix() {
            assertThat(distinctZipPrefixes())
                    .as("the 56 codes of VALID-US-STATE-CODE at app/cpy/CSLKPCDY.cpy:L1013 are a STRICT "
                            + "SUBSET of the zip prefixes: not one of them is missing. Both directions "
                            + "matter, because a subset check alone would pass if a state code were dropped "
                            + "from the zip table and the extras list were widened to compensate")
                    .containsAll(EXPECTED_US_STATE_CODES);
        }

        @Test
        @DisplayName("direction two: the set difference is exactly the six military and associated codes")
        void theSetDifferenceIsExactlyTheSixExtras() {
            final Set<String> extras = new TreeSet<>(distinctZipPrefixes());
            EXPECTED_US_STATE_CODES.forEach(extras::remove);

            assertThat(extras)
                    .as("the six prefixes that exist only in the zip table are AA, AE and AP - military and "
                            + "diplomatic mail - and FM, MH and PW, the Freely Associated States. This is "
                            + "SOURCE-INTENTIONAL. Never add them to %s and never remove AA34, AE90 through "
                            + "AE98, AP96, FM96, MH96 or PW96 from %s: either change is a Blocker, because "
                            + "the gap is what makes a zip-combination edit accept an address that the "
                            + "state-code edit rejects", STATE_CODES_RESOURCE, STATE_ZIP_RESOURCE)
                    .containsExactlyInAnyOrderElementsOf(ZIP_ONLY_PREFIXES);
        }

        @Test
        @DisplayName("the arithmetic of the asymmetry closes: 56 + 6 = 62")
        void theArithmeticOfTheAsymmetryCloses() {
            assertThat(US_STATE_CODE_COUNT + ZIP_ONLY_PREFIXES.size())
                    .as("56 state codes plus 6 zip-only prefixes must account for all 62 distinct prefixes "
                            + "with nothing left over. A residue either way would mean a prefix is neither "
                            + "a state code nor a documented extra")
                    .isEqualTo(DISTINCT_ZIP_PREFIX_COUNT);
            assertThat(ZIP_ONLY_PREFIXES)
                    .as("and the extras themselves must be absent from the state table")
                    .doesNotContainAnyElementsOf(EXPECTED_US_STATE_CODES);
        }

        @ParameterizedTest
        @CsvSource({"AA, AA34", "AE, AE90", "AP, AP96", "FM, FM96", "MH, MH96", "PW, PW96"})
        @DisplayName("the two predicates disagree on each of the six extras, and that is the behaviour")
        void theTwoPredicatesDisagreeOnEachExtra(final String prefix, final String combination) {
            assertThat(service.isValidStateZipCodeCombination(combination))
                    .as("'%s' is declared at app/cpy/CSLKPCDY.cpy:L1074-L1313, so the zip-combination edit "
                            + "must accept it", combination)
                    .isTrue();
            assertThat(service.isValidUsStateCode(prefix))
                    .as("'%s' is NOT declared at app/cpy/CSLKPCDY.cpy:L1014-L1069, so the state-code edit "
                            + "must reject it. A screen running both edits therefore refuses an address "
                            + "that the zip edit alone would accept - the asymmetry is preserved and not "
                            + "reconciled", prefix)
                    .isFalse();
        }
    }

    /** The subfield the copybook declares and never validates. */
    @Nested
    @DisplayName("LAST-3-OF-ZIP is declared and never validated, and that absence is asserted")
    final class UnvalidatedSubfield {

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("no resource carries a top-level table for the last three zip digits")
        void noResourceCarriesATableForTheLastThreeZipDigits(final String resourcePath) {
            assertThat(memberNames(resourcePath))
                    .as("app/cpy/CSLKPCDY.cpy:L1314 declares \"02 LAST-3-OF-ZIP PIC X(3).\" and is followed "
                            + "immediately by the version-stamp comments at :L1315-L1317. No 88 level is "
                            + "declared on it anywhere in the copybook, so there is no membership rule to "
                            + "transcribe and %s must carry no such member. Asserting the absence is the "
                            + "only way to pin this contract: nothing else would stop a later change from "
                            + "inventing a rule the source does not have", resourcePath)
                    .doesNotContain(UNVALIDATED_SUBFIELD);
        }

        @Test
        @DisplayName("the declared width is published as a constant without a table behind it")
        void theDeclaredWidthIsPublishedWithoutATable() {
            assertThat(ValidationLookupService.LAST_THREE_OF_ZIP_WIDTH_NOT_VALIDATED)
                    .as("the width from PIC X(3) at app/cpy/CSLKPCDY.cpy:L1314 is published so the field is "
                            + "documented rather than forgotten, while its very name records that nothing "
                            + "validates it")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("only the first two zip digits take part in a lookup; the last three are ignored")
        void onlyTheFirstTwoZipDigitsTakePartInALookup() {
            assertThat(service.isValidStateAndZipCode("NY", "10001"))
                    .as("US-STATE-AND-FIRST-ZIP2 PIC X(4) at app/cpy/CSLKPCDY.cpy:L1072 composes 'NY' with "
                            + "the first two zip digits to give 'NY10', which is declared")
                    .isTrue();
            assertThat(service.isValidStateAndZipCode("NY", "10999"))
                    .as("changing only the last three digits cannot change the answer, because they feed no "
                            + "condition name at all")
                    .isTrue();
            assertThat(service.isValidStateAndZipCode("NY", "99001"))
                    .as("changing the first two digits does change it: 'NY99' is not declared")
                    .isFalse();
        }
    }

    /** The shape of the documents themselves, asserted before any behaviour depends on it. */
    @Nested
    @DisplayName("the three documents have the JSON shape the loader is entitled to assume")
    final class JsonDocumentShape {

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("the root is a JSON object whose first member is _metadata")
        void theRootIsAnObjectWhoseFirstMemberIsMetadata(final String resourcePath) {
            assertThat(documentFor(resourcePath).isObject())
                    .as("%s must have a JSON object at its root, because the loader reads a named member "
                            + "out of it. A bare array would carry the values with nowhere to record which "
                            + "88 condition name they belong to", resourcePath)
                    .isTrue();
            assertThat(memberNames(resourcePath))
                    .as("%s must open with %s, which is provenance only and carries no lookup value. The "
                            + "loader ignores it BY NAME - it reads the table member it wants and never "
                            + "asks for this one - rather than by tolerating unknown properties as a "
                            + "blanket policy, which would also swallow a misspelled table member",
                            resourcePath, METADATA_MEMBER)
                    .first()
                    .isEqualTo(METADATA_MEMBER);
        }

        @Test
        @DisplayName("the array keys are the verbatim COBOL condition names, uppercase with hyphens")
        void theArrayKeysAreTheVerbatimCobolConditionNames() {
            final List<String> everyMember = new ArrayList<>();
            ALL_RESOURCES.forEach(resourcePath -> everyMember.addAll(memberNames(resourcePath)));

            assertThat(everyMember)
                    .as("the five table keys must be spelled exactly as app/cpy/CSLKPCDY.cpy spells the 88 "
                            + "condition names at :L30, :L521, :L931, :L1013 and :L1073 - uppercase with "
                            + "hyphens, not camelCase, not snake_case and not lowercase. The verbatim name "
                            + "is what lets a reader move between the resource and the copybook without a "
                            + "translation table")
                    .contains(PHONE_AREA_CODE_MEMBER, GENERAL_PURPOSE_CODE_MEMBER,
                            EASILY_RECOGNISABLE_CODE_MEMBER, US_STATE_CODE_MEMBER, STATE_ZIP_COMBO_MEMBER);

            assertThat(everyMember)
                    .as("and no re-spelling of any of them may appear alongside or instead")
                    .doesNotContain("validPhoneAreaCode", "valid_phone_area_code", "valid-phone-area-code",
                            "validUsStateCode", "valid_us_state_code", "validStateZipCd2Combo");
        }

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("the table arrays are top-level siblings of _metadata, never nested in a wrapper")
        void theTableArraysAreTopLevelSiblings(final String resourcePath) {
            final JsonNode root = documentFor(resourcePath);
            for (final String memberName : MEMBERS_BY_RESOURCE.get(resourcePath)) {
                assertThat(root.get(memberName))
                        .as("%s must be reachable as a direct member of the root of %s", memberName,
                                resourcePath)
                        .isNotNull();
                assertThat(root.get(memberName).isArray())
                        .as("%s must be a JSON array", memberName)
                        .isTrue();
            }
            assertThat(memberNames(resourcePath))
                    .as("%s must not wrap its tables in a container: the loader asks the root for the "
                            + "condition name directly, so a \"tables\" or \"data\" level would make every "
                            + "member absent and fail the load", resourcePath)
                    .doesNotContain("tables", "data", "lookups", "values");
        }

        @Test
        @DisplayName("every element of every table is a JSON string, never a number")
        void everyElementIsAJsonStringRatherThanANumber() {
            for (final String resourcePath : ALL_RESOURCES) {
                for (final String memberName : MEMBERS_BY_RESOURCE.get(resourcePath)) {
                    final JsonNode member = documentFor(resourcePath).get(memberName);
                    int index = 0;
                    for (final JsonNode element : member) {
                        assertThat(element.isTextual())
                                .as("%s element %d of %s must be a JSON string. The source fields are "
                                        + "character data - PIC XXX at app/cpy/CSLKPCDY.cpy:L24, PIC X(2) "
                                        + "at :L1012 and PIC X(4) at :L1072 - and a numeric encoding would "
                                        + "destroy the leading structure of a value such as '200' the "
                                        + "moment it round-tripped", memberName, index, resourcePath)
                                .isTrue();
                        index++;
                    }
                }
            }
        }

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("no document carries a polymorphic type hint, at any depth")
        void noDocumentCarriesAPolymorphicTypeHint(final String resourcePath) {
            assertThat(everyKeyAtEveryDepth(resourcePath))
                    .as("a key such as @class or @type in %s would only be meaningful to a mapper with "
                            + "default typing switched on, and its presence is the signature of insecure "
                            + "deserialization: it lets a document name the Java type it becomes. These "
                            + "documents bind to a concrete tree and then to sets of strings, never to a "
                            + "loosely typed target, and the mapper is left with default typing off. The "
                            + "scan is at every depth because _metadata is a nested object and a hint "
                            + "buried there would be just as effective as one at the root",
                            resourcePath)
                    .doesNotContainAnyElementsOf(POLYMORPHIC_TYPE_HINTS);

            final String text = readText(resourcePath);
            assertThat(POLYMORPHIC_TYPE_HINTS)
                    .allSatisfy(hint -> assertThat(text)
                            .as("%s must not contain the literal text %s anywhere either, so that the "
                                    + "guarantee does not depend on the parser's view of the document",
                                    resourcePath, hint)
                            .doesNotContain(hint));
        }

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("each document is strict JSON: no comments, no trailing commas, no duplicate keys")
        void eachDocumentIsStrictJson(final String resourcePath) {
            final String text = readText(resourcePath);

            assertThat(JSON_COMMENT.matcher(text).find())
                    .as("%s must carry no JSON comment. Comments are not JSON, so a parser that accepted "
                            + "one would be running with a non-default feature enabled, and the document "
                            + "would stop being portable", resourcePath)
                    .isFalse();
            assertThat(TRAILING_COMMA.matcher(text).find())
                    .as("%s must carry no trailing comma before a closing bracket or brace", resourcePath)
                    .isFalse();

            final ObjectMapper strict = new ObjectMapper();
            strict.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            try (Reader reader = new InputStreamReader(
                    resourceLoader.getResource(CLASSPATH_SCHEME + resourcePath).getInputStream(),
                    StandardCharsets.UTF_8)) {
                assertThat(strict.readTree(reader))
                        .as("%s must re-parse cleanly with strict duplicate detection enabled. A repeated "
                                + "key is legal to most parsers and silently keeps the last occurrence, "
                                + "which is exactly how a table gets quietly replaced", resourcePath)
                        .isNotNull();
            } catch (final IOException cause) {
                throw new UncheckedIOException(
                        "cannot re-parse classpath resource " + resourcePath + " under strict duplicate "
                                + "detection", cause);
            }
        }

        @ParameterizedTest
        @CsvSource({
            "validation/nanpa-area-codes.json",
            "validation/us-state-codes.json",
            "validation/state-zip-prefixes.json",
        })
        @DisplayName("each document is UTF-8 without a byte-order mark, LF only, with a final newline")
        void eachDocumentSatisfiesTheFileHygieneContract(final String resourcePath) {
            final byte[] bytes = readBytes(resourcePath);

            assertThat(bytes).as("%s must not be empty", resourcePath).isNotEmpty();
            assertThat(bytes[0])
                    .as("%s must not open with a UTF-8 byte-order mark. The loader decodes with an explicit "
                            + "UTF-8 charset, which does not strip one, so a mark would become part of the "
                            + "first member name and make the table unreachable", resourcePath)
                    .isNotEqualTo((byte) 0xEF);
            assertThat(bytes[bytes.length - 1])
                    .as("%s must end with a final newline, as .editorconfig requires of every file",
                            resourcePath)
                    .isEqualTo((byte) '\n');

            final String text = new String(bytes, StandardCharsets.UTF_8);
            assertThat(text)
                    .as("%s must use LF line endings only and must contain no tab character. A CRLF "
                            + "conversion would put a stray carriage return inside a JSON string, and a tab "
                            + "would contradict the two-space indent the resource tree is written in",
                            resourcePath)
                    .doesNotContain("\r")
                    .doesNotContain("\t");

            final List<String> ragged = text.lines()
                    .filter(line -> !line.equals(line.stripTrailing()))
                    .toList();
            assertThat(ragged)
                    .as("%s must carry no trailing whitespace on any line, as .editorconfig requires. "
                            + "Reported as a count of offending lines rather than their content, so a "
                            + "diagnostic never echoes data", resourcePath)
                    .isEmpty();

            final List<String> misIndented = text.lines()
                    .filter(line -> !line.isBlank())
                    .filter(line -> (line.length() - line.stripLeading().length()) % 2 != 0)
                    .toList();
            assertThat(misIndented)
                    .as("%s is indented in multiples of two spaces throughout, so an odd indent means the "
                            + "document was reformatted by a tool with different settings", resourcePath)
                    .isEmpty();
        }
    }

    /** How the tables must behave once loaded, and how a defective resource must fail. */
    @Nested
    @DisplayName("the tables load once into immutable sets and a defective resource fails loudly")
    final class LoadTimeBehaviourContract {

        @Test
        @DisplayName("every accessor returns an immutable, source-ordered set and the same instance each time")
        void everyAccessorReturnsAnImmutableSourceOrderedSet() {
            final SequencedSet<String> stateCodes = service.getValidUsStateCodes();

            assertThat(stateCodes)
                    .as("the accessor's declared type is a sequenced set precisely because the order of "
                            + "app/cpy/CSLKPCDY.cpy:L1014-L1069 is part of the contract; a plain set would "
                            + "make the ordering assertions in this suite unstatable")
                    .containsExactlyElementsOf(EXPECTED_US_STATE_CODES);
            assertThat(stateCodes.getFirst()).as("the first element is addressable and is 'AL'")
                    .isEqualTo("AL");
            assertThat(stateCodes.getLast()).as("the last element is addressable and is 'VI'")
                    .isEqualTo("VI");

            assertThat(catchThrowableOfType(UnsupportedOperationException.class,
                    () -> stateCodes.add("ZZ")))
                    .as("the table must be unmodifiable: it is loaded once at construction and never "
                            + "mutated, so a caller that tried to widen it must be refused rather than "
                            + "silently changing what every other caller sees")
                    .isNotNull();

            assertThat(service.getValidUsStateCodes())
                    .as("and a repeated call must hand back the same instance rather than reloading")
                    .isSameAs(stateCodes);
        }

        @Test
        @DisplayName("no table is ever empty, so no membership assertion in this suite can pass vacuously")
        void noTableIsEverEmpty() {
            assertThat(service.getValidPhoneAreaCodes()).as("phone table").isNotEmpty();
            assertThat(service.getValidGeneralPurposeAreaCodes()).as("general-purpose table").isNotEmpty();
            assertThat(service.getValidEasilyRecognisableAreaCodes())
                    .as("easily recognisable table").isNotEmpty();
            assertThat(service.getValidUsStateCodes()).as("state table").isNotEmpty();
            assertThat(service.getValidStateZipCodeCombinations()).as("state-and-zip table").isNotEmpty();
        }

        @Test
        @DisplayName("an absent resource fails fast, naming the resource path and the member")
        void anAbsentResourceFailsFastNamingThePath() {
            final Resource missing =
                    resourceLoader.getResource(CLASSPATH_SCHEME + "validation/no-such-table.json");
            assertThat(missing.exists()).as("the substituted resource must genuinely not exist").isFalse();

            final FatalProcessingException abend = loadFailureFor(missing);

            assertThat(abend)
                    .as("a missing lookup resource must abend at construction. Degrading to an empty table "
                            + "would be the worst outcome available: every membership assertion in this "
                            + "suite would still pass, while the running system rejected every input")
                    .isNotNull();
            assertThat(abend.getMessage())
                    .as("the message must name the resource path AND the member being loaded, because an "
                            + "operator reading a log line has neither to hand otherwise")
                    .contains(AREA_CODES_RESOURCE)
                    .contains(PHONE_AREA_CODE_MEMBER);
            assertThat(abend.getAbendCulprit())
                    .as("the abend carries the frozen copybook as its culprit, so the operator knows which "
                            + "contract to consult")
                    .isEqualTo("CSLKPCDY");
        }

        @Test
        @DisplayName("an unreadable resource fails fast AND preserves the original throwable as the cause")
        void anUnreadableResourceFailsFastAndPreservesTheCause() {
            final IOException rootCause = new IOException("simulated read failure");
            final Resource unreadable = new ByteArrayResource(new byte[] {'{', '}'}) {

                @Override
                public InputStream getInputStream() throws IOException {
                    throw rootCause;
                }
            };

            final FatalProcessingException abend = loadFailureFor(unreadable);

            assertThat(abend).as("an unreadable resource must abend").isNotNull();
            assertThat(abend.getMessage())
                    .as("with the resource path in the message")
                    .contains(AREA_CODES_RESOURCE);
            assertThat(abend.getCause())
                    .as("and with the ORIGINAL throwable preserved as the cause. Asserting the exception "
                            + "type alone would pass even if the underlying failure had been swallowed and "
                            + "replaced, which is precisely what loses the diagnosis")
                    .isSameAs(rootCause);
        }

        @Test
        @DisplayName("malformed JSON fails fast AND preserves the parse failure as the cause")
        void malformedJsonFailsFastAndPreservesTheCause() {
            final FatalProcessingException abend = loadFailureFor(jsonResource("{ this is not JSON"));

            assertThat(abend).as("a document that is not well-formed JSON must abend").isNotNull();
            assertThat(abend.getMessage()).as("naming the resource").contains(AREA_CODES_RESOURCE);
            assertThat(abend.getCause())
                    .as("and carrying the parser's own exception, which is the only thing that reports "
                            + "where in the document the defect is")
                    .isNotNull();
        }

        @Test
        @DisplayName("an empty array fails fast rather than yielding a table that accepts nothing")
        void anEmptyArrayFailsFastRatherThanYieldingAnEmptyTable() {
            final FatalProcessingException abend =
                    loadFailureFor(jsonResource("{\"" + PHONE_AREA_CODE_MEMBER + "\": []}"));

            assertThat(abend)
                    .as("an empty table is syntactically valid and semantically catastrophic: it would "
                            + "reject every area code a user could type while every test that only checks "
                            + "for absence of members still passed. It must be refused at load")
                    .isNotNull();
            assertThat(abend.getMessage())
                    .as("and the message must say so, not merely report a size")
                    .contains(AREA_CODES_RESOURCE)
                    .contains(PHONE_AREA_CODE_MEMBER);
        }

        @Test
        @DisplayName("a lookup MISS is a return value, not an exception")
        void aLookupMissIsAReturnValueRatherThanAnException() {
            assertThat(service.isValidPhoneAreaCode("199"))
                    .as("'199' is a well-formed three-digit area code that app/cpy/CSLKPCDY.cpy does not "
                            + "declare. A miss is an ordinary negative answer that a screen edit turns into "
                            + "a field message, so it must be returned as false and never thrown")
                    .isFalse();
            assertThat(service.isValidGeneralPurposeAreaCode("100")).as("'100' is undeclared").isFalse();
            assertThat(service.isValidEasilyRecognisableAreaCode("201"))
                    .as("'201' is declared, but in another table, so this predicate must answer false "
                            + "without complaint")
                    .isFalse();
            assertThat(service.isValidUsStateCode("ZZ")).as("'ZZ' is undeclared").isFalse();
            assertThat(service.isValidStateZipCodeCombination("ZZ99")).as("'ZZ99' is undeclared").isFalse();
            assertThat(service.isValidStateAndZipCode("ZZ", "99999")).as("'ZZ99' composed").isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"20", "2001", "20011"})
        @DisplayName("an area code of the wrong width is rejected before any lookup happens")
        void anAreaCodeOfTheWrongWidthIsRejectedBeforeLookup(final String candidate) {
            final ValidationException failure = catchThrowableOfType(ValidationException.class,
                    () -> service.isValidPhoneAreaCode(candidate));

            assertThat(failure)
                    .as("input width is validated BEFORE the set is consulted, because "
                            + "WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX at app/cpy/CSLKPCDY.cpy:L24 is exactly "
                            + "three characters and anything else is a malformed field rather than an "
                            + "unknown value. Answering false would conflate the two")
                    .isNotNull();
            assertThat(failure.getMessage())
                    .as("and the failure names the COBOL data item, so the message points at the screen "
                            + "field rather than at a Java parameter")
                    .contains("WS-US-PHONE-AREA-CODE-TO-EDIT");
        }

        @ParameterizedTest
        @ValueSource(strings = {"A", "ALA", "ALAB"})
        @DisplayName("a state code of the wrong width is rejected before any lookup happens")
        void aStateCodeOfTheWrongWidthIsRejectedBeforeLookup(final String candidate) {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.isValidUsStateCode(candidate)))
                    .as("US-STATE-CODE-TO-EDIT PIC X(2) at app/cpy/CSLKPCDY.cpy:L1012 is exactly two "
                            + "characters")
                    .isNotNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"AA", "AA3", "AA345"})
        @DisplayName("a state-and-zip key of the wrong width is rejected before any lookup happens")
        void aStateZipKeyOfTheWrongWidthIsRejectedBeforeLookup(final String candidate) {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.isValidStateZipCodeCombination(candidate)))
                    .as("US-STATE-AND-FIRST-ZIP2 PIC X(4) at app/cpy/CSLKPCDY.cpy:L1072 is exactly four "
                            + "characters")
                    .isNotNull();
        }

        @Test
        @DisplayName("null, empty and all-space input are each handled explicitly")
        void nullEmptyAndAllSpaceInputAreHandledExplicitly() {
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.isValidUsStateCode(null)))
                    .as("a null state code is an absent field, not an unknown value, and must be reported "
                            + "rather than dereferenced")
                    .isNotNull();
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.isValidUsStateCode("")))
                    .as("an empty string is likewise absent")
                    .isNotNull();
            assertThat(catchThrowableOfType(ValidationException.class,
                    () -> service.isValidUsStateCode("  ")))
                    .as("and an all-space field of the right width is still absent - a 3270 screen delivers "
                            + "an untouched field as spaces, so this is the common case rather than an "
                            + "edge one")
                    .isNotNull();
        }

        @Test
        @DisplayName("lowercase and non-ASCII input are not silently normalised into a match")
        void lowercaseAndNonAsciiInputAreNotSilentlyNormalised() {
            assertThat(service.isValidUsStateCode("AL".toLowerCase(Locale.ROOT)))
                    .as("app/cpy/CSLKPCDY.cpy declares 'AL' in upper case and membership is an exact string "
                            + "comparison, so the lowered form must answer false. Locale.ROOT is used for "
                            + "the lowering so that a Turkish or Azeri host locale cannot change which "
                            + "characters this test actually probes with")
                    .isFalse();
            assertThat(service.isValidStateZipCodeCombination("aa34"))
                    .as("the same holds for a four-character key: 'aa34' is not 'AA34'")
                    .isFalse();
            assertThat(service.isValidUsStateCode("\u00c1L"))
                    .as("a non-ASCII two-character input is the right width and is not a member, so it must "
                            + "answer false rather than throwing or matching by some accent-folding rule "
                            + "that the source has no notion of")
                    .isFalse();
        }

        @Test
        @DisplayName("iteration order never influences an answer")
        void iterationOrderNeverInfluencesAnAnswer() {
            final List<String> forward = new ArrayList<>(EXPECTED_US_STATE_CODES);
            final List<String> reversed = new ArrayList<>(EXPECTED_US_STATE_CODES);
            Collections.reverse(reversed);

            final List<Boolean> forwardAnswers = forward.stream().map(service::isValidUsStateCode).toList();
            final List<Boolean> reversedAnswers = reversed.stream()
                    .map(service::isValidUsStateCode).toList();

            assertThat(forwardAnswers)
                    .as("every declared state code answers true regardless of the order it is asked in")
                    .hasSize(US_STATE_CODE_COUNT)
                    .containsOnly(Boolean.TRUE);
            assertThat(reversedAnswers)
                    .as("membership is a hash probe against an immutable set, so the order of enquiry "
                            + "cannot influence a result. Asserting this explicitly is what keeps a future "
                            + "implementation from substituting an order-dependent scan of the kind "
                            + "app/cbl/CBSTM03A.CBL uses, whose early exit is only correct because its "
                            + "input is sorted")
                    .hasSize(US_STATE_CODE_COUNT)
                    .containsOnly(Boolean.TRUE);
        }
    }

    /**
     * The pairing that must be asserted in one place: the state resource is exact, and the fixture is tolerated.
     *
     * <p>Both halves belong together because each one alone invites the wrong fix. Knowing only that the
     * resource holds 56 entries tempts an author to add the four codes the fixture uses; knowing only that the
     * fixture must load tempts them to enforce membership and then relax the table. Stating both at once makes
     * the coherent reading the obvious one: these codes are zip-prefix-valid and not state-code-valid.
     */
    @Nested
    @DisplayName("the state table is exact AND the customer fixture's non-member rows are still tolerated")
    final class FixtureTolerance {

        @Test
        @DisplayName("the fixture geometry confirms the state-code offset arithmetic")
        void theFixtureGeometryConfirmsTheOffsetArithmetic() {
            assertThat(customerFixture.recordCount())
                    .as("app/data/ASCII/custdata.txt holds 50 customer records")
                    .isEqualTo(CUSTOMER_ROW_COUNT);
            assertThat(customerFixture.recordWidth())
                    .as("each record is 500 characters. That total is the check on the offset arithmetic: "
                            + "the app/cpy/CVCUS01Y.cpy field widths 9, 25, 25, 25, 50, 50, 50, 2, 3, 10, "
                            + "15, 15, 9, 20, 10, 10, 1, 3 and 168 accumulate to exactly 500, which places "
                            + "CUST-ADDR-STATE-CD at columns 235-236. If the width were anything else the "
                            + "column would be wrong and every state code read below would be garbage")
                    .isEqualTo(CUSTOMER_RECORD_WIDTH);
            assertThat(customerFixture.byteCount())
                    .as("and the byte count must equal the record census, which is what proves no record "
                            + "was trimmed, lost or converted to CRLF on the way in")
                    .isEqualTo(customerFixture.impliedByteCount());

            for (int row = 0; row < customerFixture.recordCount(); row++) {
                assertThat(STATE_CODE.matcher(fixtureStateCode(row)).matches())
                        .as("row %d must yield two uppercase letters at columns 235-236; anything else "
                                + "means the offset is wrong. The row is named by index and never by "
                                + "content, because this record also carries a social security number at "
                                + "columns 280-288 and a date of birth at columns 309-318", row)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("BOTH halves: the resource holds exactly 56 entries and all 50 fixture rows loaded")
        void theResourceIsExactAndEveryFixtureRowStillLoaded() {
            assertThat(arrayMember(STATE_CODES_RESOURCE, US_STATE_CODE_MEMBER))
                    .as("half one - %s holds exactly the 56 entries of app/cpy/CSLKPCDY.cpy:L1014-L1069, "
                            + "no more and no fewer", STATE_CODES_RESOURCE)
                    .containsExactlyElementsOf(EXPECTED_US_STATE_CODES);

            assertThat(customerFixture.records())
                    .as("half two - all 50 rows of app/data/ASCII/custdata.txt loaded, including the five "
                            + "whose state code the table above does not hold. Membership is NOT enforced "
                            + "at load time and must never become so: doing that would reject five valid "
                            + "customer records, and silencing it by adding the four codes to the resource "
                            + "would widen a screen edit the copybook deliberately keeps narrow. Both are "
                            + "Blockers")
                    .hasSize(CUSTOMER_ROW_COUNT);

            final Map<String, Integer> census = nonMemberStateCodeCensus();
            assertThat(census)
                    .as("and the non-member rows really are present, so this test is not passing because "
                            + "the fixture happens to contain none")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("exactly five of the fifty rows carry a state code the table does not hold")
        void exactlyFiveOfTheFiftyRowsCarryANonMemberStateCode() {
            final Map<String, Integer> census = nonMemberStateCodeCensus();

            assertThat(census)
                    .as("counted from columns 235-236 of app/data/ASCII/custdata.txt: AP once, FM twice, MH "
                            + "once and PW once. A drift in either direction means the fixture or the state "
                            + "table changed, and both are meant to be frozen")
                    .containsExactlyInAnyOrderEntriesOf(NON_MEMBER_STATE_CODE_ROWS);
            assertThat(census.values().stream().mapToInt(Integer::intValue).sum())
                    .as("five rows of fifty, which is the figure the tolerance claim rests on")
                    .isEqualTo(NON_MEMBER_ROW_TOTAL);
        }

        @Test
        @DisplayName("every non-member fixture code is one of the six zip-prefix extras, so the gap is coherent")
        void everyNonMemberFixtureCodeIsOneOfTheSixExtras() {
            final Set<String> offending = new TreeSet<>(nonMemberStateCodeCensus().keySet());

            assertThat(offending)
                    .as("this is what makes the asymmetry coherent rather than a defect: every state code "
                            + "the fixture uses and the table lacks is one of the six prefixes that the "
                            + "state-and-zip table does declare. The codes are zip-prefix-valid and not "
                            + "state-code-valid, exactly as app/cpy/CSLKPCDY.cpy has it. AA and AE simply "
                            + "do not occur in this fixture, which is why four appear here and not six")
                    .isSubsetOf(ZIP_ONLY_PREFIXES)
                    .hasSize(4);

            for (final String code : offending) {
                assertThat(service.isValidUsStateCode(code))
                        .as("state code %s, used by a real fixture row, is rejected by the state-code edit",
                                code)
                        .isFalse();
            }
        }
    }
}
