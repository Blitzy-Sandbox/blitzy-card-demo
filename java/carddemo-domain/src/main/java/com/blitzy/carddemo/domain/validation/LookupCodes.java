/*
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
 * language governing permissions and limitations under the License.
 */
package com.blitzy.carddemo.domain.validation;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Lookup-code repository translated verbatim from the COBOL working-storage
 * copybook {@code app/cpy/CSLKPCDY.cpy}. Three closed lookup categories are
 * exposed as immutable {@link Set} constants plus null-safe convenience
 * predicates that replace the original COBOL 88-level condition tests:
 *
 * <ol>
 *   <li><b>NANPA phone area codes</b> &mdash; three related sets:
 *     <ul>
 *       <li>{@link #NANPA_GENERAL_PURPOSE_AREA_CODES} (410 entries) translated
 *           from {@code 88 VALID-GENERAL-PURP-CODE} at CSLKPCDY.cpy
 *           lines 521-930.</li>
 *       <li>{@link #NANPA_EASILY_RECOGNIZABLE_AREA_CODES} (80 entries)
 *           translated from {@code 88 VALID-EASY-RECOG-AREA-CODE} at
 *           CSLKPCDY.cpy lines 931-1010.</li>
 *       <li>{@link #NANPA_VALID_AREA_CODES} (490 entries) &mdash; the union of
 *           the two sets above; translated from {@code 88 VALID-PHONE-AREA-CODE}
 *           at CSLKPCDY.cpy lines 30-520 (which is itself a duplicated union in
 *           the COBOL source).</li>
 *     </ul>
 *   </li>
 *   <li><b>US state and territory codes</b> &mdash; {@link #US_STATE_CODES}
 *       (56 entries: 50 states + DC + 5 territories) translated from
 *       {@code 88 VALID-US-STATE-CODE} at CSLKPCDY.cpy lines 1013-1069.</li>
 *   <li><b>US state + first-2-digits-of-ZIP combinations</b> &mdash;
 *       {@link #US_STATE_ZIP_PREFIX_COMBOS} (240 entries, each 4 chars)
 *       translated from {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} at
 *       CSLKPCDY.cpy lines 1074-1313.</li>
 * </ol>
 *
 * <h2>Source provenance</h2>
 * The COBOL source declares (verbatim from CSLKPCDY.cpy lines 26-28):
 * <pre>
 *   North America Phone area codes List obtained from North America
 *   Numbering Plan Administrator *nanpa,
 *   https://nationalnanpa.com/nanp1/npa_report.csv
 * </pre>
 *
 * <h2>Translation fidelity</h2>
 * Per the Agent Action Plan (AAP) &sect;0.1.1 and &sect;0.7.1 the lookup
 * contents are byte-identical to the COBOL 88-level VALUES lists &mdash; no
 * codes added, none removed, no reordering except where the COBOL source
 * itself is unordered. The ordering of every entry in every set follows the
 * COBOL source for diff-friendliness against the reference implementation
 * (note in particular the NY ZIP prefixes at CSLKPCDY.cpy lines 1224-1231,
 * where {@code NY50, NY54, NY63} appear before {@code NY10, NY11, NY12,
 * NY13, NY14} &mdash; the Java translation preserves this exact ordering).
 *
 * <h2>Special-case codes intentionally preserved</h2>
 * {@link #US_STATE_ZIP_PREFIX_COMBOS} includes territory codes <em>not</em>
 * present in {@link #US_STATE_CODES}: {@code AA} (Americas military),
 * {@code AE} (Europe/Africa/Middle East military), {@code AP} (Pacific
 * military), {@code FM} (Federated States of Micronesia), {@code MH}
 * (Marshall Islands), and {@code PW} (Palau). This is INTENTIONAL &mdash;
 * the COBOL source has these. Preserved verbatim per AAP &sect;0.7.1.
 *
 * <h2>Immutability</h2>
 * All five {@link Set} constants are produced via {@link Set#of(Object[])}
 * or {@link Set#copyOf(java.util.Collection)} and are therefore truly
 * immutable: any attempt to mutate them throws
 * {@link UnsupportedOperationException}. The class itself has no
 * constructors (it is a constants holder) and uses a static initializer
 * to fail-fast at class load if any cardinality or length invariant is
 * violated (see {@code Self-verification} block).
 *
 * <h2>Thread safety</h2>
 * This class is immutable and thread-safe. All public state is
 * {@code public static final} referencing immutable {@link Set} instances
 * published by {@link Set#of(Object[])} / {@link Set#copyOf(java.util.Collection)},
 * both of which provide safe publication via the JMM's final-field
 * guarantees.
 *
 * <h2>Java 25 features used</h2>
 * <ul>
 *   <li>Immutable collection factories ({@link Set#of(Object[])},
 *       {@link Set#copyOf(java.util.Collection)}) per AAP &sect;0.6.7.</li>
 *   <li>{@link CobolProgram} traceability annotation per AAP &sect;0.7.1.</li>
 * </ul>
 *
 * <h2>Java 25 features intentionally not used</h2>
 * No preview features (per AAP &sect;0.7.4): no primitive patterns (JEP 507),
 * no structured concurrency (JEP 505), no stable values (JEP 502), no compact
 * source files (JEP 512). No {@link ThreadLocal}. No mutable static state.
 *
 * @see <a href="https://nationalnanpa.com/nanp1/npa_report.csv">NANPA NPA report</a>
 * @since 1.0.0
 */
@CobolProgram(
        value = "CSLKPCDY",
        sourcePath = "app/cpy/CSLKPCDY.cpy",
        translationDate = "2025-10-15",
        notes = "Lookup code repository: NANPA phone area codes (general-purpose + "
                + "easily-recognizable, union of 490 codes), US state/territory codes "
                + "(56 entries), and US state + first-2-of-ZIP combinations (240 entries). "
                + "Contents byte-identical to COBOL 88-level VALUES per AAP §0.7.1."
)
public final class LookupCodes {

    /**
     * Private constructor &mdash; this is a constants holder class and must
     * never be instantiated. Throws {@link UnsupportedOperationException}
     * defensively if reflection is used to bypass the private access modifier.
     */
    private LookupCodes() {
        throw new UnsupportedOperationException(
                "LookupCodes is a constants holder class and cannot be instantiated");
    }

    // ============================================================================
    // NANPA general-purpose phone area codes (410 entries)
    // ============================================================================

    /**
     * NANPA general-purpose area codes &mdash; 410 entries.
     *
     * <p>Translation of {@code 88 VALID-GENERAL-PURP-CODE} from CSLKPCDY.cpy
     * lines 521-930 (identical to the general-purpose portion of
     * {@code 88 VALID-PHONE-AREA-CODE} at lines 30-439).
     *
     * <p>These are the "ordinary" 3-digit phone area codes assigned to
     * geographic regions across the North American Numbering Plan (US,
     * Canada, Caribbean, etc.). They explicitly exclude the "easily
     * recognizable" pattern of {@code XYY} codes (e.g., {@code 800},
     * {@code 888}, {@code 911}) which are listed separately in
     * {@link #NANPA_EASILY_RECOGNIZABLE_AREA_CODES}.
     *
     * <p>Source: North America Numbering Plan Administrator (NANPA).
     * <p>Order preserved as per COBOL source for diff-friendliness.
     */
    public static final Set<String> NANPA_GENERAL_PURPOSE_AREA_CODES = Set.of(
            // 2xx codes (53 entries: CSLKPCDY.cpy lines 30-82 / 521-573)
            "201", "202", "203", "204", "205", "206", "207", "208", "209", "210",
            "212", "213", "214", "215", "216", "217", "218", "219", "220", "223",
            "224", "225", "226", "228", "229", "231", "234", "236", "239", "240",
            "242", "246", "248", "249", "250", "251", "252", "253", "254", "256",
            "260", "262", "264", "267", "268", "269", "270", "272", "276", "279",
            "281", "284", "289",
            // 3xx codes (47 entries: CSLKPCDY.cpy lines 83-129 / 574-620)
            "301", "302", "303", "304", "305", "306", "307", "308", "309", "310",
            "312", "313", "314", "315", "316", "317", "318", "319", "320", "321",
            "323", "325", "326", "330", "331", "332", "334", "336", "337", "339",
            "340", "341", "343", "345", "346", "347", "351", "352", "360", "361",
            "364", "365", "367", "368", "380", "385", "386",
            // 4xx codes (48 entries: CSLKPCDY.cpy lines 130-177 / 621-668)
            "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
            "412", "413", "414", "415", "416", "417", "418", "419", "423", "424",
            "425", "430", "431", "432", "434", "435", "437", "438", "440", "441",
            "442", "443", "445", "447", "448", "450", "458", "463", "464", "469",
            "470", "473", "474", "475", "478", "479", "480", "484",
            // 5xx codes (46 entries: CSLKPCDY.cpy lines 178-223 / 669-714)
            "501", "502", "503", "504", "505", "506", "507", "508", "509", "510",
            "512", "513", "514", "515", "516", "517", "518", "519", "520", "530",
            "531", "534", "539", "540", "541", "548", "551", "559", "561", "562",
            "563", "564", "567", "570", "571", "572", "573", "574", "575", "579",
            "580", "581", "582", "585", "586", "587",
            // 6xx codes (54 entries: CSLKPCDY.cpy lines 224-277 / 715-768)
            "601", "602", "603", "604", "605", "606", "607", "608", "609", "610",
            "612", "613", "614", "615", "616", "617", "618", "619", "620", "623",
            "626", "628", "629", "630", "631", "636", "639", "640", "641", "646",
            "647", "649", "650", "651", "656", "657", "658", "659", "660", "661",
            "662", "664", "667", "669", "670", "671", "672", "678", "680", "681",
            "682", "683", "684", "689",
            // 7xx codes (56 entries: CSLKPCDY.cpy lines 278-333 / 769-824)
            "701", "702", "703", "704", "705", "706", "707", "708", "709", "712",
            "713", "714", "715", "716", "717", "718", "719", "720", "721", "724",
            "725", "726", "727", "731", "732", "734", "737", "740", "742", "743",
            "747", "753", "754", "757", "758", "760", "762", "763", "765", "767",
            "769", "770", "771", "772", "773", "774", "775", "778", "779", "780",
            "781", "782", "784", "785", "786", "787",
            // 8xx codes (53 entries: CSLKPCDY.cpy lines 334-386 / 825-877)
            "801", "802", "803", "804", "805", "806", "807", "808", "809", "810",
            "812", "813", "814", "815", "816", "817", "818", "819", "820", "825",
            "826", "828", "829", "830", "831", "832", "838", "839", "840", "843",
            "845", "847", "848", "849", "850", "854", "856", "857", "858", "859",
            "860", "862", "863", "864", "865", "867", "868", "869", "870", "872",
            "873", "876", "878",
            // 9xx codes (53 entries: CSLKPCDY.cpy lines 387-439 / 878-930)
            "901", "902", "903", "904", "905", "906", "907", "908", "909", "910",
            "912", "913", "914", "915", "916", "917", "918", "919", "920", "925",
            "928", "929", "930", "931", "934", "936", "937", "938", "939", "940",
            "941", "943", "945", "947", "948", "949", "951", "952", "954", "956",
            "959", "970", "971", "972", "973", "978", "979", "980", "983", "984",
            "985", "986", "989"
    );

    // ============================================================================
    // NANPA easily-recognizable phone area codes (80 entries)
    // ============================================================================

    /**
     * NANPA easily-recognizable area codes &mdash; 80 entries.
     *
     * <p>Translation of {@code 88 VALID-EASY-RECOG-AREA-CODE} from
     * CSLKPCDY.cpy lines 931-1010 (identical to the easily-recognizable
     * portion of {@code 88 VALID-PHONE-AREA-CODE} at lines 441-520).
     *
     * <p><b>Pattern</b>: for each first digit {@code X} in {@code 2..9}, the
     * 10 codes are {@code X00, X11, X22, X33, X44, X55, X66, X77, X88, X99}.
     * This yields 8 &times; 10 = 80 codes. These are reserved for special
     * services and are visually/aurally distinctive (the "easily
     * recognizable" designation). Notable examples:
     * <ul>
     *   <li>{@code 800, 833, 844, 855, 866, 877, 888} &mdash; toll-free</li>
     *   <li>{@code 822} &mdash; reserved toll-free (future expansion)</li>
     *   <li>{@code 811} &mdash; "Call Before You Dig" utility location</li>
     *   <li>{@code 411} &mdash; directory assistance</li>
     *   <li>{@code 911} &mdash; emergency services</li>
     *   <li>{@code 900} &mdash; premium-rate services</li>
     * </ul>
     *
     * <p>Order preserved as per COBOL source for diff-friendliness.
     */
    public static final Set<String> NANPA_EASILY_RECOGNIZABLE_AREA_CODES = Set.of(
            // First digit 2 (10 entries: CSLKPCDY.cpy lines 441-450 / 931-940)
            "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
            // First digit 3 (10 entries: CSLKPCDY.cpy lines 451-460 / 941-950)
            "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
            // First digit 4 (10 entries: CSLKPCDY.cpy lines 461-470 / 951-960)
            "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
            // First digit 5 (10 entries: CSLKPCDY.cpy lines 471-480 / 961-970)
            "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
            // First digit 6 (10 entries: CSLKPCDY.cpy lines 481-490 / 971-980)
            "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
            // First digit 7 (10 entries: CSLKPCDY.cpy lines 491-500 / 981-990)
            "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
            // First digit 8 (10 entries: CSLKPCDY.cpy lines 501-510 / 991-1000)
            "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
            // First digit 9 (10 entries: CSLKPCDY.cpy lines 511-520 / 1001-1010)
            "900", "911", "922", "933", "944", "955", "966", "977", "988", "999"
    );

    // ============================================================================
    // Combined NANPA valid phone area codes (490 entries — union)
    // ============================================================================

    /**
     * Combined set of valid NANPA area codes &mdash; the union of
     * {@link #NANPA_GENERAL_PURPOSE_AREA_CODES} (410 entries) and
     * {@link #NANPA_EASILY_RECOGNIZABLE_AREA_CODES} (80 entries), for a
     * total of <b>490 entries</b>.
     *
     * <p>Translation of {@code 88 VALID-PHONE-AREA-CODE} from CSLKPCDY.cpy
     * lines 30-520.
     *
     * <p><b>Implementation note</b>: this set is built dynamically by
     * unioning the two component sets via {@link HashSet} and then producing
     * an immutable snapshot with {@link Set#copyOf(java.util.Collection)}.
     * This avoids the maintenance burden of re-listing all 490 entries
     * (which would risk drift between the two declarations) while still
     * producing a truly immutable {@link Set}. The static initializer block
     * later in this class verifies the union cardinality is exactly 490,
     * which transitively proves the two component sets are disjoint &mdash;
     * a structural invariant of the COBOL source that must be preserved.
     *
     * <p>The iteration order of this set is the deterministic
     * insertion-order of {@link Set#copyOf(java.util.Collection)} applied to
     * the {@link HashSet} union, which is not guaranteed to match the COBOL
     * source order. Callers that need source-ordered iteration should use
     * {@link #NANPA_GENERAL_PURPOSE_AREA_CODES} and
     * {@link #NANPA_EASILY_RECOGNIZABLE_AREA_CODES} directly. The
     * {@code contains(...)} predicate is unaffected by iteration order.
     */
    public static final Set<String> NANPA_VALID_AREA_CODES;
    static {
        HashSet<String> union = new HashSet<>(
                NANPA_GENERAL_PURPOSE_AREA_CODES.size()
                        + NANPA_EASILY_RECOGNIZABLE_AREA_CODES.size());
        union.addAll(NANPA_GENERAL_PURPOSE_AREA_CODES);
        union.addAll(NANPA_EASILY_RECOGNIZABLE_AREA_CODES);
        NANPA_VALID_AREA_CODES = Set.copyOf(union);
    }

    // ============================================================================
    // US state/territory two-letter postal codes (56 entries)
    // ============================================================================

    /**
     * Valid US state/territory two-letter postal codes &mdash; 56 entries.
     *
     * <p>Translation of {@code 88 VALID-US-STATE-CODE} from CSLKPCDY.cpy
     * lines 1013-1069.
     *
     * <p><b>Contents</b>:
     * <ul>
     *   <li>All 50 US states (AL through WY).</li>
     *   <li>District of Columbia: DC.</li>
     *   <li>5 US territories: AS (American Samoa), GU (Guam),
     *       MP (Northern Mariana Islands), PR (Puerto Rico),
     *       VI (US Virgin Islands).</li>
     * </ul>
     *
     * <p><b>NOTE</b>: this set deliberately omits the military/diplomatic
     * codes (AA, AE, AP) and certain Pacific territory codes (FM, MH, PW)
     * that <em>do</em> appear in {@link #US_STATE_ZIP_PREFIX_COMBOS}. The
     * COBOL source has this asymmetry &mdash; it is preserved here verbatim
     * per AAP &sect;0.7.1.
     *
     * <p>Order preserved as per COBOL source for diff-friendliness.
     */
    public static final Set<String> US_STATE_CODES = Set.of(
            // 50 states (lines 1014-1063)
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            // District of Columbia (line 1064)
            "DC",
            // 5 US territories (lines 1065-1069)
            "AS", "GU", "MP", "PR", "VI"
    );

    // ============================================================================
    // US state + first-2-digits-of-ZIP combinations (240 entries)
    // ============================================================================

    /**
     * Valid US state + first-2-digits-of-ZIP combinations &mdash; 240 entries
     * (each 4 chars: 2-letter state/territory code + 2-digit ZIP prefix).
     *
     * <p>Translation of {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} from
     * CSLKPCDY.cpy lines 1074-1313.
     *
     * <p><b>NOTE on extra codes</b>: this set includes some codes
     * <em>not</em> present in {@link #US_STATE_CODES}:
     * <ul>
     *   <li>{@code AA} &mdash; Americas military (APO/FPO addresses)</li>
     *   <li>{@code AE} &mdash; Europe/Africa/Middle East military</li>
     *   <li>{@code AP} &mdash; Pacific military</li>
     *   <li>{@code FM} &mdash; Federated States of Micronesia</li>
     *   <li>{@code MH} &mdash; Marshall Islands</li>
     *   <li>{@code PW} &mdash; Palau</li>
     * </ul>
     * This asymmetry is INTENTIONAL &mdash; the COBOL source has these.
     * Preserved verbatim per AAP &sect;0.7.1.
     *
     * <p><b>NOTE on NY ordering</b>: the COBOL source lists NY ZIP prefixes
     * at lines 1224-1231 as {@code NY50, NY54, NY63, NY10, NY11, NY12, NY13,
     * NY14} &mdash; out of numeric order. The Java translation preserves
     * this exact ordering for diff-friendliness against the COBOL source,
     * even though it's not strictly sorted. The {@code contains(...)}
     * predicate is unaffected by iteration order.
     *
     * <p>Order preserved as per COBOL source for diff-friendliness.
     */
    public static final Set<String> US_STATE_ZIP_PREFIX_COMBOS = Set.of(
            // Military and diplomatic codes (AA, AE, AP) and ZIPs per CSLKPCDY lines 1074-1083, 1087
            "AA34",
            "AE90", "AE91", "AE92", "AE93", "AE94", "AE95", "AE96", "AE97", "AE98",
            "AK99",
            "AL35", "AL36",
            "AP96",
            "AR71", "AR72",
            "AS96",
            "AZ85", "AZ86",
            "CA90", "CA91", "CA92", "CA93", "CA94", "CA95", "CA96",
            "CO80", "CO81",
            "CT60", "CT61", "CT62", "CT63", "CT64", "CT65", "CT66", "CT67", "CT68", "CT69",
            "DC20", "DC56", "DC88",
            "DE19",
            "FL32", "FL33", "FL34",
            "FM96",
            "GA30", "GA31", "GA39",
            "GU96",
            "HI96",
            "IA50", "IA51", "IA52",
            "ID83",
            "IL60", "IL61", "IL62",
            "IN46", "IN47",
            "KS66", "KS67",
            "KY40", "KY41", "KY42",
            "LA70", "LA71",
            "MA10", "MA11", "MA12", "MA13", "MA14", "MA15", "MA16", "MA17", "MA18", "MA19",
            "MA20", "MA21", "MA22", "MA23", "MA24", "MA25", "MA26", "MA27", "MA55",
            "MD20", "MD21",
            "ME39", "ME40", "ME41", "ME42", "ME43", "ME44", "ME45", "ME46", "ME47", "ME48", "ME49",
            "MH96",
            "MI48", "MI49",
            "MN55", "MN56",
            "MO63", "MO64", "MO65", "MO72",
            "MP96",
            "MS38", "MS39",
            "MT59",
            "NC27", "NC28",
            "ND58",
            "NE68", "NE69",
            "NH30", "NH31", "NH32", "NH33", "NH34", "NH35", "NH36", "NH37", "NH38",
            "NJ70", "NJ71", "NJ72", "NJ73", "NJ74", "NJ75", "NJ76", "NJ77", "NJ78", "NJ79",
            "NJ80", "NJ81", "NJ82", "NJ83", "NJ84", "NJ85", "NJ86", "NJ87", "NJ88", "NJ89",
            "NM87", "NM88",
            "NV88", "NV89",
            // NY codes — COBOL source order preserved (NOT numerically sorted)
            "NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14",
            "OH43", "OH44", "OH45",
            "OK73", "OK74",
            "OR97",
            "PA15", "PA16", "PA17", "PA18", "PA19",
            "PR60", "PR61", "PR62", "PR63", "PR64", "PR65", "PR66", "PR67", "PR68", "PR69",
            "PR70", "PR71", "PR72", "PR73", "PR74", "PR75", "PR76", "PR77", "PR78", "PR79",
            "PR90", "PR91", "PR92", "PR93", "PR94", "PR95", "PR96", "PR97", "PR98",
            "PW96",
            "RI28", "RI29",
            "SC29",
            "SD57",
            "TN37", "TN38",
            "TX73", "TX75", "TX76", "TX77", "TX78", "TX79", "TX88",
            "UT84",
            "VA20", "VA22", "VA23", "VA24",
            "VI80", "VI82", "VI83", "VI84", "VI85",
            "VT50", "VT51", "VT52", "VT53", "VT54", "VT56", "VT57", "VT58", "VT59",
            "WA98", "WA99",
            "WI53", "WI54",
            "WV24", "WV25", "WV26",
            "WY82", "WY83"
    );


    // ============================================================================
    // Convenience query methods (replace COBOL 88-level condition tests)
    // ============================================================================

    /**
     * Returns {@code true} if the supplied 3-digit area code is in the NANPA
     * general-purpose set ({@link #NANPA_GENERAL_PURPOSE_AREA_CODES}).
     *
     * <p>This method is the Java equivalent of the COBOL
     * {@code IF VALID-GENERAL-PURP-CODE} predicate from CSLKPCDY.cpy
     * line 521.
     *
     * <p>The method is null-safe: a {@code null} argument returns
     * {@code false} (it does <strong>not</strong> throw
     * {@link NullPointerException}). This preserves COBOL's two-valued
     * truth semantics for missing data.
     *
     * @param areaCode the 3-digit area code to test (must be exactly 3 chars
     *                 for a positive result; any other length or content
     *                 simply returns {@code false}); may be {@code null}
     * @return {@code true} iff {@code areaCode} is a valid NANPA
     *         general-purpose area code; {@code false} otherwise (including
     *         the cases {@code areaCode == null}, wrong length, or unknown
     *         code)
     */
    public static boolean isValidGeneralPurposeAreaCode(String areaCode) {
        return areaCode != null && NANPA_GENERAL_PURPOSE_AREA_CODES.contains(areaCode);
    }

    /**
     * Returns {@code true} if the supplied 3-digit area code is in the NANPA
     * easily-recognizable set ({@link #NANPA_EASILY_RECOGNIZABLE_AREA_CODES}).
     *
     * <p>This method is the Java equivalent of the COBOL
     * {@code IF VALID-EASY-RECOG-AREA-CODE} predicate from CSLKPCDY.cpy
     * line 931.
     *
     * <p>The method is null-safe: a {@code null} argument returns
     * {@code false}.
     *
     * @param areaCode the 3-digit area code to test; may be {@code null}
     * @return {@code true} iff {@code areaCode} is a valid NANPA
     *         easily-recognizable area code; {@code false} otherwise
     */
    public static boolean isValidEasilyRecognizableAreaCode(String areaCode) {
        return areaCode != null && NANPA_EASILY_RECOGNIZABLE_AREA_CODES.contains(areaCode);
    }

    /**
     * Returns {@code true} if the supplied 3-digit area code is in the
     * combined NANPA valid set ({@link #NANPA_VALID_AREA_CODES} &mdash; the
     * union of general-purpose and easily-recognizable area codes).
     *
     * <p>This method is the Java equivalent of the COBOL
     * {@code IF VALID-PHONE-AREA-CODE} predicate from CSLKPCDY.cpy
     * line 30.
     *
     * <p>The method is null-safe: a {@code null} argument returns
     * {@code false}.
     *
     * @param areaCode the 3-digit area code to test; may be {@code null}
     * @return {@code true} iff {@code areaCode} is a valid NANPA area code;
     *         {@code false} otherwise
     */
    public static boolean isValidPhoneAreaCode(String areaCode) {
        return areaCode != null && NANPA_VALID_AREA_CODES.contains(areaCode);
    }

    /**
     * Returns {@code true} if the supplied 2-letter code is a valid US state
     * or territory code ({@link #US_STATE_CODES}).
     *
     * <p>This method is the Java equivalent of the COBOL
     * {@code IF VALID-US-STATE-CODE} predicate from CSLKPCDY.cpy line 1013.
     *
     * <p>The method is null-safe: a {@code null} argument returns
     * {@code false}.
     *
     * <p><b>NOTE</b>: this method does <em>not</em> recognize the military
     * codes (AA, AE, AP) or the Pacific territory codes (FM, MH, PW) that
     * appear in {@link #US_STATE_ZIP_PREFIX_COMBOS} but not in
     * {@link #US_STATE_CODES}. Callers that need to validate those codes
     * should use {@link #isValidUsStateZipPrefixCombo(String)} or
     * {@link #isValidUsStateZipPrefixCombo(String, String)} instead.
     *
     * @param stateCode the 2-letter state/territory code to test; may be
     *                  {@code null}
     * @return {@code true} iff {@code stateCode} is a valid US state or
     *         territory code; {@code false} otherwise
     */
    public static boolean isValidUsStateCode(String stateCode) {
        return stateCode != null && US_STATE_CODES.contains(stateCode);
    }

    /**
     * Returns {@code true} if the supplied 4-character combination is in the
     * valid state+ZIP-prefix set ({@link #US_STATE_ZIP_PREFIX_COMBOS}).
     *
     * <p>This method is the Java equivalent of the COBOL
     * {@code IF VALID-US-STATE-ZIP-CD2-COMBO} predicate from CSLKPCDY.cpy
     * line 1073.
     *
     * <p>The method is null-safe: a {@code null} argument returns
     * {@code false}.
     *
     * @param stateZipPrefix 4-char string: 2-letter state/territory code
     *                       followed by 2-digit ZIP prefix
     *                       (e.g., {@code "CA90"}, {@code "NY10"});
     *                       may be {@code null}
     * @return {@code true} iff the combined string is in the valid
     *         state+ZIP-prefix set; {@code false} otherwise
     */
    public static boolean isValidUsStateZipPrefixCombo(String stateZipPrefix) {
        return stateZipPrefix != null && US_STATE_ZIP_PREFIX_COMBOS.contains(stateZipPrefix);
    }

    /**
     * Two-argument convenience overload of
     * {@link #isValidUsStateZipPrefixCombo(String)}. The two arguments are
     * concatenated into a 4-character key and checked against
     * {@link #US_STATE_ZIP_PREFIX_COMBOS}.
     *
     * <p>The method is null-safe: a {@code null} argument (either one)
     * returns {@code false}. Both arguments must be exactly 2 characters;
     * any other length returns {@code false} without consulting the set.
     *
     * <p>Example: {@code isValidUsStateZipPrefixCombo("CA", "90")} returns
     * {@code true} (because {@code "CA90"} is in the set).
     *
     * @param stateCode  the 2-letter state/territory code (e.g., {@code "CA"});
     *                   may be {@code null}
     * @param zipPrefix2 the 2-digit ZIP prefix (first two digits of a 5-digit
     *                   ZIP code, e.g., {@code "90"} for {@code "90210"});
     *                   may be {@code null}
     * @return {@code true} iff the concatenation
     *         {@code stateCode + zipPrefix2} is a valid state+ZIP combination;
     *         {@code false} otherwise (including either argument null, wrong
     *         length, or unknown combination)
     */
    public static boolean isValidUsStateZipPrefixCombo(String stateCode, String zipPrefix2) {
        if (stateCode == null || zipPrefix2 == null) {
            return false;
        }
        if (stateCode.length() != 2 || zipPrefix2.length() != 2) {
            return false;
        }
        return isValidUsStateZipPrefixCombo(stateCode + zipPrefix2);
    }

    // ============================================================================
    // Self-verification — fail-fast on class load if counts or shapes do not
    // match the COBOL source. The static initializer below runs at class
    // initialization time (per JLS §12.4.2). If any invariant is violated the
    // {@link ExceptionInInitializerError} thrown here will prevent the class
    // from being usable, which surfaces data-corruption defects loudly rather
    // than allowing silent miscalculation of phone- or address-validation
    // results downstream.
    //
    // The invariants checked are:
    //   1. Cardinality of each set matches the COBOL source count.
    //   2. The union NANPA_VALID_AREA_CODES has exactly 490 entries, which
    //      proves the two component sets are disjoint (a structural invariant
    //      of the COBOL source per AAP §0.7.1).
    //   3. Every area code is exactly 3 characters in length.
    //   4. Every state code is exactly 2 characters in length.
    //   5. Every state+ZIP combination is exactly 4 characters in length.
    //   6. No code is null (already guaranteed by {@link Set#of(Object[])}
    //      but defensively verified here via {@link Objects#requireNonNull}).
    // ============================================================================

    static {
        // Cardinality checks against the COBOL source counts.
        if (NANPA_GENERAL_PURPOSE_AREA_CODES.size() != 410) {
            throw new IllegalStateException(
                    "NANPA_GENERAL_PURPOSE_AREA_CODES expected 410 entries (CSLKPCDY.cpy lines 521-930), got "
                            + NANPA_GENERAL_PURPOSE_AREA_CODES.size());
        }
        if (NANPA_EASILY_RECOGNIZABLE_AREA_CODES.size() != 80) {
            throw new IllegalStateException(
                    "NANPA_EASILY_RECOGNIZABLE_AREA_CODES expected 80 entries (CSLKPCDY.cpy lines 931-1010), got "
                            + NANPA_EASILY_RECOGNIZABLE_AREA_CODES.size());
        }
        // The union should have exactly 490 entries (410 + 80 = 490 — proves no overlap).
        // If this fails it indicates either a duplicate within one of the component sets
        // (impossible because Set.of(...) throws IllegalArgumentException on duplicates)
        // or an overlap between the two component sets, which would be a translation defect.
        if (NANPA_VALID_AREA_CODES.size() != 490) {
            throw new IllegalStateException(
                    "NANPA_VALID_AREA_CODES expected 490 entries (410 general + 80 easy, CSLKPCDY.cpy lines 30-520), got "
                            + NANPA_VALID_AREA_CODES.size());
        }
        if (US_STATE_CODES.size() != 56) {
            throw new IllegalStateException(
                    "US_STATE_CODES expected 56 entries (CSLKPCDY.cpy lines 1013-1069), got "
                            + US_STATE_CODES.size());
        }
        if (US_STATE_ZIP_PREFIX_COMBOS.size() != 240) {
            throw new IllegalStateException(
                    "US_STATE_ZIP_PREFIX_COMBOS expected 240 entries (CSLKPCDY.cpy lines 1074-1313), got "
                            + US_STATE_ZIP_PREFIX_COMBOS.size());
        }

        // Length checks against the COBOL PIC clauses (PIC XXX, PIC X(2), PIC X(4)).
        // We use Objects.requireNonNull() defensively even though Set.of(...) rejects
        // null elements — this documents the non-null guarantee in code and gives a
        // clearer error if a future refactor accidentally introduces a null element.
        for (String code : NANPA_GENERAL_PURPOSE_AREA_CODES) {
            Objects.requireNonNull(code, "NANPA_GENERAL_PURPOSE_AREA_CODES contains null");
            if (code.length() != 3) {
                throw new IllegalStateException(
                        "NANPA general-purpose area code must be 3 chars (PIC XXX): '" + code + "'");
            }
        }
        for (String code : NANPA_EASILY_RECOGNIZABLE_AREA_CODES) {
            Objects.requireNonNull(code, "NANPA_EASILY_RECOGNIZABLE_AREA_CODES contains null");
            if (code.length() != 3) {
                throw new IllegalStateException(
                        "NANPA easily-recognizable area code must be 3 chars (PIC XXX): '" + code + "'");
            }
        }
        for (String code : US_STATE_CODES) {
            Objects.requireNonNull(code, "US_STATE_CODES contains null");
            if (code.length() != 2) {
                throw new IllegalStateException(
                        "US state/territory code must be 2 chars (PIC X(2)): '" + code + "'");
            }
        }
        for (String combo : US_STATE_ZIP_PREFIX_COMBOS) {
            Objects.requireNonNull(combo, "US_STATE_ZIP_PREFIX_COMBOS contains null");
            if (combo.length() != 4) {
                throw new IllegalStateException(
                        "US state+ZIP combo must be 4 chars (PIC X(4)): '" + combo + "'");
            }
        }
    }
}

