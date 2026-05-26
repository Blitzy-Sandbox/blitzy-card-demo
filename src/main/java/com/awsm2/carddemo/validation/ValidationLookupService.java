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
package com.awsm2.carddemo.validation;

import com.awsm2.carddemo.exception.ValidationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless lookup service that ports the validation tables from
 * {@code app/cpy/CSLKPCDY.cpy} (the "Lookup code repository" copybook) into
 * native Java {@link Set} and {@link Map} structures.
 *
 * <p>Per the COBOL source header ({@code CSLKPCDY.cpy:L1-L7}), this lookup
 * repository contains three validation tables:</p>
 * <ol>
 *   <li>North American (NANPA) phone area codes — sourced from the NANPA
 *       registry (per {@code CSLKPCDY.cpy:L26-L28})
 *       <a href="https://nationalnanpa.com/nanp1/npa_report.csv">nationalnanpa.com/nanp1/npa_report.csv</a></li>
 *   <li>United States state/territory codes (50 states + DC + 5 territories)</li>
 *   <li>United States state + first-2-digits-of-ZIP combinations</li>
 * </ol>
 *
 * <p>Per AAP &sect;0.7.2, every value in these tables is preserved
 * <strong>verbatim, character-for-character</strong> from the COBOL source.
 * The tables were sourced from official regulatory data; any divergence
 * breaks the migration parity guarantee.</p>
 *
 * <p>Per AAP &sect;0.7.1, this service:</p>
 * <ul>
 *   <li>Is stateless and thread-safe (all data is immutable static constants
 *       initialized once at class load via {@link Set#of} and
 *       {@link Map#ofEntries})</li>
 *   <li>Returns {@code boolean} from each {@code isValidXxx(...)} method
 *       (does not throw — the calling service decides whether to throw
 *       {@code com.awsm2.carddemo.exception.ValidationException})</li>
 *   <li>Performs no AWS SDK, JPA, Kafka, or network I/O — local CPU work only</li>
 * </ul>
 *
 * <p>COBOL source provenance: each lookup table ports the corresponding
 * 88-LEVEL value list from {@code CSLKPCDY.cpy}:</p>
 * <table>
 *   <caption>COBOL → Java mapping</caption>
 *   <tr><th>COBOL 88-LEVEL</th><th>Source lines</th><th>Java constant</th></tr>
 *   <tr><td>{@code VALID-PHONE-AREA-CODE}</td><td>L30-L520</td><td>{@link #NANPA_AREA_CODES}</td></tr>
 *   <tr><td>{@code VALID-US-STATE-CODE}</td><td>L1013-L1069</td><td>{@link #US_STATE_CODES}</td></tr>
 *   <tr><td>{@code VALID-US-STATE-ZIP-CD2-COMBO}</td><td>L1074-L1313</td><td>{@link #STATE_TO_ZIP_PREFIXES}</td></tr>
 * </table>
 *
 * <p>Note that the COBOL source defines two additional 88-LEVELs that are
 * subsets of {@code VALID-PHONE-AREA-CODE}:
 * {@code VALID-GENERAL-PURP-CODE} ({@code CSLKPCDY.cpy:L521-L930}) and
 * {@code VALID-EASY-RECOG-AREA-CODE} ({@code CSLKPCDY.cpy:L931-L1010}). Per
 * the AAP &sect;0.7.3 Minimal Change Clause, these subsets are NOT exposed
 * as separate Java fields because no calling service requires fine-grained
 * distinction between them — the canonical {@code IF VALID-PHONE-AREA-CODE}
 * predicate is sufficient.</p>
 *
 * @see com.awsm2.carddemo.exception.ValidationException
 */
@Service
public class ValidationLookupService {

    /**
     * The full NANPA area code set: 3-digit US/Canada/Caribbean phone area codes.
     * Sourced verbatim from {@code app/cpy/CSLKPCDY.cpy:L30-L520} (88-level
     * {@code VALID-PHONE-AREA-CODE}), which is the union of general-purpose
     * area codes and "easily recognizable codes" (X11/X22/...).
     *
     * <p>Contains 490 codes total:</p>
     * <ul>
     *   <li>410 general-purpose geographic codes (e.g., 201 NJ, 415 SF Bay
     *       Area, 808 Hawaii) from {@code CSLKPCDY.cpy:L30-L439}</li>
     *   <li>80 easily-recognizable codes (X00/XYY patterns including service
     *       codes 211/311/411/911 and toll-free 800/888/877/866/855/844/833/822/811)
     *       from {@code CSLKPCDY.cpy:L441-L520}</li>
     * </ul>
     *
     * <p>Per AAP &sect;0.7.2, these values are preserved verbatim from the
     * COBOL source in their source-listing order.</p>
     */
    public static final Set<String> NANPA_AREA_CODES = Set.of(
        // === General-purpose codes (CSLKPCDY.cpy:L30-L439, VALID-PHONE-AREA-CODE primary block) ===
        // 2XX (CSLKPCDY.cpy:L30-L82)
        "201", "202", "203", "204", "205", "206", "207", "208", "209", "210",
        "212", "213", "214", "215", "216", "217", "218", "219", "220", "223",
        "224", "225", "226", "228", "229", "231", "234", "236", "239", "240",
        "242", "246", "248", "249", "250", "251", "252", "253", "254", "256",
        "260", "262", "264", "267", "268", "269", "270", "272", "276", "279",
        "281", "284", "289",
        // 3XX (CSLKPCDY.cpy:L83-L129)
        "301", "302", "303", "304", "305", "306", "307", "308", "309", "310",
        "312", "313", "314", "315", "316", "317", "318", "319", "320", "321",
        "323", "325", "326", "330", "331", "332", "334", "336", "337", "339",
        "340", "341", "343", "345", "346", "347", "351", "352", "360", "361",
        "364", "365", "367", "368", "380", "385", "386",
        // 4XX (CSLKPCDY.cpy:L130-L177)
        "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
        "412", "413", "414", "415", "416", "417", "418", "419", "423", "424",
        "425", "430", "431", "432", "434", "435", "437", "438", "440", "441",
        "442", "443", "445", "447", "448", "450", "458", "463", "464", "469",
        "470", "473", "474", "475", "478", "479", "480", "484",
        // 5XX (CSLKPCDY.cpy:L178-L223)
        "501", "502", "503", "504", "505", "506", "507", "508", "509", "510",
        "512", "513", "514", "515", "516", "517", "518", "519", "520", "530",
        "531", "534", "539", "540", "541", "548", "551", "559", "561", "562",
        "563", "564", "567", "570", "571", "572", "573", "574", "575", "579",
        "580", "581", "582", "585", "586", "587",
        // 6XX (CSLKPCDY.cpy:L224-L277)
        "601", "602", "603", "604", "605", "606", "607", "608", "609", "610",
        "612", "613", "614", "615", "616", "617", "618", "619", "620", "623",
        "626", "628", "629", "630", "631", "636", "639", "640", "641", "646",
        "647", "649", "650", "651", "656", "657", "658", "659", "660", "661",
        "662", "664", "667", "669", "670", "671", "672", "678", "680", "681",
        "682", "683", "684", "689",
        // 7XX (CSLKPCDY.cpy:L278-L333)
        "701", "702", "703", "704", "705", "706", "707", "708", "709", "712",
        "713", "714", "715", "716", "717", "718", "719", "720", "721", "724",
        "725", "726", "727", "731", "732", "734", "737", "740", "742", "743",
        "747", "753", "754", "757", "758", "760", "762", "763", "765", "767",
        "769", "770", "771", "772", "773", "774", "775", "778", "779", "780",
        "781", "782", "784", "785", "786", "787",
        // 8XX (CSLKPCDY.cpy:L334-L386)
        "801", "802", "803", "804", "805", "806", "807", "808", "809", "810",
        "812", "813", "814", "815", "816", "817", "818", "819", "820", "825",
        "826", "828", "829", "830", "831", "832", "838", "839", "840", "843",
        "845", "847", "848", "849", "850", "854", "856", "857", "858", "859",
        "860", "862", "863", "864", "865", "867", "868", "869", "870", "872",
        "873", "876", "878",
        // 9XX (CSLKPCDY.cpy:L387-L439)
        "901", "902", "903", "904", "905", "906", "907", "908", "909", "910",
        "912", "913", "914", "915", "916", "917", "918", "919", "920", "925",
        "928", "929", "930", "931", "934", "936", "937", "938", "939", "940",
        "941", "943", "945", "947", "948", "949", "951", "952", "954", "956",
        "959", "970", "971", "972", "973", "978", "979", "980", "983", "984",
        "985", "986", "989",
        // === Easily-recognizable codes (CSLKPCDY.cpy:L441-L520, "Easily recognizable codes begin here.") ===
        "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
        "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
        "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
        "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
        "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
        "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
        "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
        "900", "911", "922", "933", "944", "955", "966", "977", "988", "999"
    );

    /**
     * The 56-element set of US state, federal district, and territory codes.
     * Sourced verbatim from {@code app/cpy/CSLKPCDY.cpy:L1013-L1069} (88-level
     * {@code VALID-US-STATE-CODE}).
     *
     * <p>Includes:</p>
     * <ul>
     *   <li>50 US states (AL, AK, AZ, AR, CA, ..., WI, WY)</li>
     *   <li>1 federal district (DC)</li>
     *   <li>5 territories: AS (American Samoa), GU (Guam), MP (N. Mariana Islands),
     *       PR (Puerto Rico), VI (US Virgin Islands)</li>
     * </ul>
     *
     * <p>Note: This set does NOT include military mail codes (AA, AE, AP) or
     * federal territory codes (FM Federated States of Micronesia, MH Marshall
     * Islands, PW Palau) — those codes appear in {@link #STATE_TO_ZIP_PREFIXES}
     * but are not part of the COBOL {@code VALID-US-STATE-CODE} 88-level list.
     * This faithfully matches the COBOL source.</p>
     */
    public static final Set<String> US_STATE_CODES = Set.of(
        // CSLKPCDY.cpy:L1014-L1063 — 50 states in their COBOL source order
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
        "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
        "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
        // CSLKPCDY.cpy:L1064-L1069 — federal district + 5 territories
        "DC", "AS", "GU", "MP", "PR", "VI"
    );

    /**
     * Map of US state code → set of valid first-2-digits-of-ZIP combinations.
     * Sourced verbatim from {@code app/cpy/CSLKPCDY.cpy:L1074-L1313} (88-level
     * {@code VALID-US-STATE-ZIP-CD2-COMBO}).
     *
     * <p>The COBOL source stores composite 4-character values (e.g., {@code 'AL35'}
     * meaning "AL state with ZIP code starting in 35"). The Java target
     * restructures this into a {@link Map} keyed by state code with the value
     * being the set of valid 2-digit ZIP prefixes. The total entry count (240
     * 4-character COBOL values) is preserved.</p>
     *
     * <p>This map includes military and federal codes that are NOT in the
     * basic {@link #US_STATE_CODES} set, matching the COBOL source:</p>
     * <ul>
     *   <li>Military mail codes: AA (Armed Forces — Americas, ZIP 34),
     *       AE (Armed Forces — Europe/MidEast/Africa, ZIPs 90-98),
     *       AP (Armed Forces — Pacific, ZIP 96)</li>
     *   <li>Pacific federal territories: FM (Federated States of Micronesia),
     *       MH (Marshall Islands), PW (Palau) — all on ZIP prefix 96</li>
     * </ul>
     *
     * <p>Used by {@link #isValidStateZipCombination(String, String)} to check
     * that a (state, ZIP) pair is geographically valid per USPS conventions.</p>
     */
    public static final Map<String, Set<String>> STATE_TO_ZIP_PREFIXES = Map.ofEntries(
        // CSLKPCDY.cpy:L1074 — Armed Forces Americas
        Map.entry("AA", Set.of("34")),
        // CSLKPCDY.cpy:L1075-L1083 — Armed Forces Europe/MidEast/Africa
        Map.entry("AE", Set.of("90", "91", "92", "93", "94", "95", "96", "97", "98")),
        // CSLKPCDY.cpy:L1084 — Alaska
        Map.entry("AK", Set.of("99")),
        // CSLKPCDY.cpy:L1085-L1086 — Alabama
        Map.entry("AL", Set.of("35", "36")),
        // CSLKPCDY.cpy:L1087 — Armed Forces Pacific
        Map.entry("AP", Set.of("96")),
        // CSLKPCDY.cpy:L1088-L1089 — Arkansas
        Map.entry("AR", Set.of("71", "72")),
        // CSLKPCDY.cpy:L1090 — American Samoa
        Map.entry("AS", Set.of("96")),
        // CSLKPCDY.cpy:L1091-L1092 — Arizona
        Map.entry("AZ", Set.of("85", "86")),
        // CSLKPCDY.cpy:L1093-L1099 — California
        Map.entry("CA", Set.of("90", "91", "92", "93", "94", "95", "96")),
        // CSLKPCDY.cpy:L1100-L1101 — Colorado
        Map.entry("CO", Set.of("80", "81")),
        // CSLKPCDY.cpy:L1102-L1111 — Connecticut
        Map.entry("CT", Set.of("60", "61", "62", "63", "64", "65", "66", "67", "68", "69")),
        // CSLKPCDY.cpy:L1112-L1114 — District of Columbia
        Map.entry("DC", Set.of("20", "56", "88")),
        // CSLKPCDY.cpy:L1115 — Delaware
        Map.entry("DE", Set.of("19")),
        // CSLKPCDY.cpy:L1116-L1118 — Florida
        Map.entry("FL", Set.of("32", "33", "34")),
        // CSLKPCDY.cpy:L1119 — Federated States of Micronesia
        Map.entry("FM", Set.of("96")),
        // CSLKPCDY.cpy:L1120-L1122 — Georgia
        Map.entry("GA", Set.of("30", "31", "39")),
        // CSLKPCDY.cpy:L1123 — Guam
        Map.entry("GU", Set.of("96")),
        // CSLKPCDY.cpy:L1124 — Hawaii
        Map.entry("HI", Set.of("96")),
        // CSLKPCDY.cpy:L1125-L1127 — Iowa
        Map.entry("IA", Set.of("50", "51", "52")),
        // CSLKPCDY.cpy:L1128 — Idaho
        Map.entry("ID", Set.of("83")),
        // CSLKPCDY.cpy:L1129-L1131 — Illinois
        Map.entry("IL", Set.of("60", "61", "62")),
        // CSLKPCDY.cpy:L1132-L1133 — Indiana
        Map.entry("IN", Set.of("46", "47")),
        // CSLKPCDY.cpy:L1134-L1135 — Kansas
        Map.entry("KS", Set.of("66", "67")),
        // CSLKPCDY.cpy:L1136-L1138 — Kentucky
        Map.entry("KY", Set.of("40", "41", "42")),
        // CSLKPCDY.cpy:L1139-L1140 — Louisiana
        Map.entry("LA", Set.of("70", "71")),
        // CSLKPCDY.cpy:L1141-L1159 — Massachusetts
        Map.entry("MA", Set.of("10", "11", "12", "13", "14", "15", "16", "17", "18", "19",
                                "20", "21", "22", "23", "24", "25", "26", "27", "55")),
        // CSLKPCDY.cpy:L1160-L1161 — Maryland
        Map.entry("MD", Set.of("20", "21")),
        // CSLKPCDY.cpy:L1162-L1172 — Maine
        Map.entry("ME", Set.of("39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49")),
        // CSLKPCDY.cpy:L1173 — Marshall Islands
        Map.entry("MH", Set.of("96")),
        // CSLKPCDY.cpy:L1174-L1175 — Michigan
        Map.entry("MI", Set.of("48", "49")),
        // CSLKPCDY.cpy:L1176-L1177 — Minnesota
        Map.entry("MN", Set.of("55", "56")),
        // CSLKPCDY.cpy:L1178-L1181 — Missouri
        Map.entry("MO", Set.of("63", "64", "65", "72")),
        // CSLKPCDY.cpy:L1182 — Northern Mariana Islands
        Map.entry("MP", Set.of("96")),
        // CSLKPCDY.cpy:L1183-L1184 — Mississippi
        Map.entry("MS", Set.of("38", "39")),
        // CSLKPCDY.cpy:L1185 — Montana
        Map.entry("MT", Set.of("59")),
        // CSLKPCDY.cpy:L1186-L1187 — North Carolina
        Map.entry("NC", Set.of("27", "28")),
        // CSLKPCDY.cpy:L1188 — North Dakota
        Map.entry("ND", Set.of("58")),
        // CSLKPCDY.cpy:L1189-L1190 — Nebraska
        Map.entry("NE", Set.of("68", "69")),
        // CSLKPCDY.cpy:L1191-L1199 — New Hampshire
        Map.entry("NH", Set.of("30", "31", "32", "33", "34", "35", "36", "37", "38")),
        // CSLKPCDY.cpy:L1200-L1219 — New Jersey
        Map.entry("NJ", Set.of("70", "71", "72", "73", "74", "75", "76", "77", "78", "79",
                                "80", "81", "82", "83", "84", "85", "86", "87", "88", "89")),
        // CSLKPCDY.cpy:L1220-L1221 — New Mexico
        Map.entry("NM", Set.of("87", "88")),
        // CSLKPCDY.cpy:L1222-L1223 — Nevada
        Map.entry("NV", Set.of("88", "89")),
        // CSLKPCDY.cpy:L1224-L1231 — New York
        // Source-listing order in CSLKPCDY.cpy: NY50, NY54, NY63, NY10, NY11,
        // NY12, NY13, NY14. The `Set.of(...)` literal below uses ascending
        // numeric order purely as a readable convention; `Set` membership is
        // order-agnostic, so runtime validation behaviour is unaffected
        // either way and the source semantic of "any of these eight ZIP
        // prefixes maps to NY" is preserved exactly.
        Map.entry("NY", Set.of("10", "11", "12", "13", "14", "50", "54", "63")),
        // CSLKPCDY.cpy:L1232-L1234 — Ohio
        Map.entry("OH", Set.of("43", "44", "45")),
        // CSLKPCDY.cpy:L1235-L1236 — Oklahoma
        Map.entry("OK", Set.of("73", "74")),
        // CSLKPCDY.cpy:L1237 — Oregon
        Map.entry("OR", Set.of("97")),
        // CSLKPCDY.cpy:L1238-L1242 — Pennsylvania
        Map.entry("PA", Set.of("15", "16", "17", "18", "19")),
        // CSLKPCDY.cpy:L1243-L1271 — Puerto Rico
        Map.entry("PR", Set.of("60", "61", "62", "63", "64", "65", "66", "67", "68", "69",
                                "70", "71", "72", "73", "74", "75", "76", "77", "78", "79",
                                "90", "91", "92", "93", "94", "95", "96", "97", "98")),
        // CSLKPCDY.cpy:L1272 — Palau
        Map.entry("PW", Set.of("96")),
        // CSLKPCDY.cpy:L1273-L1274 — Rhode Island
        Map.entry("RI", Set.of("28", "29")),
        // CSLKPCDY.cpy:L1275 — South Carolina
        Map.entry("SC", Set.of("29")),
        // CSLKPCDY.cpy:L1276 — South Dakota
        Map.entry("SD", Set.of("57")),
        // CSLKPCDY.cpy:L1277-L1278 — Tennessee
        Map.entry("TN", Set.of("37", "38")),
        // CSLKPCDY.cpy:L1279-L1285 — Texas
        Map.entry("TX", Set.of("73", "75", "76", "77", "78", "79", "88")),
        // CSLKPCDY.cpy:L1286 — Utah
        Map.entry("UT", Set.of("84")),
        // CSLKPCDY.cpy:L1287-L1290 — Virginia
        Map.entry("VA", Set.of("20", "22", "23", "24")),
        // CSLKPCDY.cpy:L1291-L1295 — US Virgin Islands
        Map.entry("VI", Set.of("80", "82", "83", "84", "85")),
        // CSLKPCDY.cpy:L1296-L1304 — Vermont
        Map.entry("VT", Set.of("50", "51", "52", "53", "54", "56", "57", "58", "59")),
        // CSLKPCDY.cpy:L1305-L1306 — Washington
        Map.entry("WA", Set.of("98", "99")),
        // CSLKPCDY.cpy:L1307-L1308 — Wisconsin
        Map.entry("WI", Set.of("53", "54")),
        // CSLKPCDY.cpy:L1309-L1311 — West Virginia
        Map.entry("WV", Set.of("24", "25", "26")),
        // CSLKPCDY.cpy:L1312-L1313 — Wyoming
        Map.entry("WY", Set.of("82", "83"))
    );

    /**
     * Default no-arg constructor. This service has no dependencies — all
     * data is immutable static constants and the methods are pure functions.
     * Constructor injection is not required.
     *
     * <p>Spring discovers this bean via {@code @Service} component scanning
     * from {@code com.awsm2.carddemo.CardDemoApplication}.</p>
     */
    public ValidationLookupService() {
        // no-op
    }

    /**
     * Validates that the supplied 3-digit area code is in the NANPA registry.
     *
     * <p>COBOL provenance: replaces 88-level {@code VALID-PHONE-AREA-CODE}
     * check in {@code app/cpy/CSLKPCDY.cpy:L30-L520}. Used by COBOL programs
     * that performed phone number editing via the COBOL pattern:</p>
     * <pre>
     * MOVE phoneAreaCode TO WS-US-PHONE-AREA-CODE-TO-EDIT
     * IF VALID-PHONE-AREA-CODE
     *     ...
     * END-IF
     * </pre>
     *
     * <p>The Java equivalent is a {@link Set#contains} call against
     * {@link #NANPA_AREA_CODES}, which contains all 490 codes from the
     * COBOL 88-level list (union of general-purpose + easily-recognizable).</p>
     *
     * @param areaCode the 3-digit area code to validate. May be {@code null}
     *                 or of any length; the method returns {@code false} for
     *                 invalid input rather than throwing. Per COBOL convention,
     *                 the lookup is case-sensitive: the area-code value space
     *                 is numeric so case is not a practical concern.
     * @return {@code true} if the area code is in the NANPA registry;
     *         {@code false} otherwise (including null/empty/wrong-length input)
     */
    public boolean isValidAreaCode(String areaCode) {
        // COBOL: CSLKPCDY.cpy 88-level VALID-PHONE-AREA-CODE (L30-L520)
        if (areaCode == null) {
            return false;
        }
        return NANPA_AREA_CODES.contains(areaCode);
    }

    /**
     * Validates that the supplied 2-character code is a US state, federal
     * district, or territory abbreviation.
     *
     * <p>COBOL provenance: replaces 88-level {@code VALID-US-STATE-CODE} check
     * in {@code app/cpy/CSLKPCDY.cpy:L1013-L1069}. The COBOL pattern was:</p>
     * <pre>
     * MOVE stateCode TO US-STATE-CODE-TO-EDIT
     * IF VALID-US-STATE-CODE
     *     ...
     * END-IF
     * </pre>
     *
     * <p>Note that the COBOL 88-level {@code VALID-US-STATE-CODE} does NOT
     * include military mail codes (AA, AE, AP) or Pacific federal territory
     * codes (FM, MH, PW). Those codes are recognized only by
     * {@link #isValidStateZipCombination(String, String)} via
     * {@link #STATE_TO_ZIP_PREFIXES}. This method preserves that distinction
     * exactly.</p>
     *
     * @param stateCode the 2-character state code (case-sensitive uppercase
     *                  per COBOL convention). May be {@code null}; the method
     *                  returns {@code false} for null input.
     * @return {@code true} if the state code is recognized; {@code false} otherwise
     */
    public boolean isValidStateCode(String stateCode) {
        // COBOL: CSLKPCDY.cpy 88-level VALID-US-STATE-CODE (L1013-L1069)
        if (stateCode == null) {
            return false;
        }
        return US_STATE_CODES.contains(stateCode);
    }

    /**
     * Validates that the supplied state code and ZIP code form a geographically
     * consistent combination per USPS conventions.
     *
     * <p>COBOL provenance: replaces 88-level {@code VALID-US-STATE-ZIP-CD2-COMBO}
     * check in {@code app/cpy/CSLKPCDY.cpy:L1074-L1313}. The COBOL pattern was:</p>
     * <pre>
     * MOVE stateCode       TO US-STATE-AND-FIRST-ZIP2(1:2)
     * MOVE zipCode(1:2)    TO US-STATE-AND-FIRST-ZIP2(3:2)
     * IF VALID-US-STATE-ZIP-CD2-COMBO
     *     ...
     * END-IF
     * </pre>
     *
     * <p>The Java equivalent:</p>
     * <ol>
     *   <li>Look up the state in {@link #STATE_TO_ZIP_PREFIXES}</li>
     *   <li>Extract the first 2 characters of the ZIP code</li>
     *   <li>Return {@code true} if the state's prefix set contains the
     *       extracted prefix</li>
     * </ol>
     *
     * <p>This method ACCEPTS military mail codes (AA/AE/AP) and federal
     * territory codes (FM/MH/PW) because they appear in
     * {@link #STATE_TO_ZIP_PREFIXES}, exactly matching the COBOL source.</p>
     *
     * @param stateCode the 2-character state code (e.g., {@code "CA"})
     * @param zipCode   the ZIP code; at least 2 characters required (the
     *                  remaining characters are ignored). May be 5-digit
     *                  ({@code "94105"}) or ZIP+4 ({@code "94105-1234"}).
     * @return {@code true} if the (state, zip-prefix) combination is valid;
     *         {@code false} if either argument is {@code null}, the state is
     *         unknown, the ZIP code has fewer than 2 characters, or the
     *         prefix is not in the state's valid set
     */
    public boolean isValidStateZipCombination(String stateCode, String zipCode) {
        // COBOL: CSLKPCDY.cpy 88-level VALID-US-STATE-ZIP-CD2-COMBO (L1074-L1313)
        if (stateCode == null || zipCode == null || zipCode.length() < 2) {
            return false;
        }
        Set<String> validPrefixes = STATE_TO_ZIP_PREFIXES.get(stateCode);
        if (validPrefixes == null) {
            return false;
        }
        String zipPrefix = zipCode.substring(0, 2);
        return validPrefixes.contains(zipPrefix);
    }

    // =====================================================================
    // Exception-throwing validation API (CP3 checkpoint requirement)
    // =====================================================================
    //
    // The CP3 checkpoint requires that ValidationLookupService expose
    // exception-throwing variants of the boolean lookup methods so that
    // service-layer callers can short-circuit a validation cascade with
    // a single throw and feed the resulting message into the standard
    // ApiResponse error envelope via GlobalExceptionHandler.
    //
    // Per AAP §0.4.1, the typed ValidationException maps to HTTP 400 in
    // the GlobalExceptionHandler. Each throwing method:
    //
    //   * Calls the corresponding boolean predicate as the source of truth
    //   * Throws ValidationException with a deterministic FieldError on
    //     failure so the caller's resulting JSON response identifies the
    //     offending field by name
    //   * Returns void on success so the caller can chain validations
    //
    // The original boolean `isValidXxx(...)` methods remain available
    // unchanged for callers that need the predicate semantics directly
    // (e.g., conditional warnings, search-as-you-type validation hints).

    /** Field name surfaced in the {@code FieldError} for area-code failures. */
    private static final String FIELD_AREA_CODE = "areaCode";
    /** Field name surfaced in the {@code FieldError} for state-code failures. */
    private static final String FIELD_STATE_CODE = "stateCode";
    /** Field name surfaced in the {@code FieldError} for state+ZIP failures. */
    private static final String FIELD_STATE_ZIP = "stateZipCombination";

    /** Reason code stamped on area-code validation failures. */
    static final String REASON_CODE_AREA_CODE = "INVALID_AREA_CODE";
    /** Reason code stamped on state-code validation failures. */
    static final String REASON_CODE_STATE_CODE = "INVALID_STATE_CODE";
    /** Reason code stamped on state+ZIP validation failures. */
    static final String REASON_CODE_STATE_ZIP = "INVALID_STATE_ZIP_COMBINATION";

    /**
     * Validates a NANPA area code and throws a typed
     * {@link ValidationException} if the code is unknown.
     *
     * <p>Replaces (AAP &sect;0.4.1): COBOL {@code 88-LEVEL
     * VALID-PHONE-AREA-CODE} from {@code CSLKPCDY.cpy:L30-L520} when
     * used as a guard (e.g., {@code IF NOT VALID-PHONE-AREA-CODE GO TO
     * REJECT-RECORD.}). Service callers in the Java target replace the
     * COBOL conditional branch with a single call to this method.</p>
     *
     * <p>The exception's {@code fieldErrors} list always contains exactly
     * one entry &mdash; field name {@code "areaCode"} &mdash; so callers
     * never see a {@code ValidationException} from this method with an
     * empty error list.</p>
     *
     * @param areaCode the 3-digit area code to validate
     * @throws ValidationException with reason
     *         {@link #REASON_CODE_AREA_CODE} when the code is null,
     *         blank, or absent from the NANPA registry
     */
    public void validateAreaCode(String areaCode) {
        // COBOL: CSLKPCDY.cpy 88-level VALID-PHONE-AREA-CODE (L30-L520)
        if (!isValidAreaCode(areaCode)) {
            throw new ValidationException(
                    REASON_CODE_AREA_CODE,
                    "Invalid NANPA phone area code: " + safe(areaCode),
                    List.of(new ValidationException.FieldError(
                            FIELD_AREA_CODE,
                            "Area code is not a recognised NANPA registry value")));
        }
    }

    /**
     * Validates a US state / federal-district / territory code and throws
     * a typed {@link ValidationException} if the code is unknown.
     *
     * <p>Replaces (AAP &sect;0.4.1): COBOL {@code 88-LEVEL
     * VALID-US-STATE-CODE} from {@code CSLKPCDY.cpy:L1013-L1069} when
     * used as a guard.</p>
     *
     * @param stateCode the 2-character state code (uppercase per COBOL
     *                  convention)
     * @throws ValidationException with reason
     *         {@link #REASON_CODE_STATE_CODE} when the state code is
     *         null, blank, or unknown
     */
    public void validateStateCode(String stateCode) {
        // COBOL: CSLKPCDY.cpy 88-level VALID-US-STATE-CODE (L1013-L1069)
        if (!isValidStateCode(stateCode)) {
            throw new ValidationException(
                    REASON_CODE_STATE_CODE,
                    "Invalid US state/territory code: " + safe(stateCode),
                    List.of(new ValidationException.FieldError(
                            FIELD_STATE_CODE,
                            "State code is not a recognised US state/territory")));
        }
    }

    /**
     * Validates that the supplied state code + ZIP code form a
     * geographically consistent combination per USPS conventions, and
     * throws a typed {@link ValidationException} on mismatch.
     *
     * <p>Replaces (AAP &sect;0.4.1): COBOL {@code 88-LEVEL
     * VALID-US-STATE-ZIP-CD2-COMBO} from
     * {@code CSLKPCDY.cpy:L1074-L1313} when used as a guard in
     * {@code COACTUPC.cbl} customer address edits.</p>
     *
     * @param stateCode the 2-character state code
     * @param zipCode   the 5-digit or ZIP+4 ZIP code
     * @throws ValidationException with reason
     *         {@link #REASON_CODE_STATE_ZIP} when the combination is
     *         invalid
     */
    public void validateStateZipCombination(String stateCode, String zipCode) {
        // COBOL: CSLKPCDY.cpy 88-level VALID-US-STATE-ZIP-CD2-COMBO (L1074-L1313)
        if (!isValidStateZipCombination(stateCode, zipCode)) {
            throw new ValidationException(
                    REASON_CODE_STATE_ZIP,
                    "State/ZIP combination is geographically inconsistent: "
                            + "state=" + safe(stateCode) + " zip=" + safe(zipCode),
                    List.of(new ValidationException.FieldError(
                            FIELD_STATE_ZIP,
                            "State and ZIP code prefix do not match per USPS conventions")));
        }
    }

    /**
     * PCI-DSS-safe rendering of an arbitrary input string for use in
     * exception messages. Returns the value unchanged when present,
     * {@code "<null>"} for a null reference, and {@code "<blank>"} for
     * an empty/whitespace-only string. The result never reveals masked
     * or sensitive content because area / state / ZIP values are public
     * by nature.
     */
    private static String safe(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isBlank()) {
            return "<blank>";
        }
        return value;
    }
}
