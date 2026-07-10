package com.carddemo.service;

import java.util.Set;

import org.springframework.stereotype.Service;

/**
 * Lookup-code repository service exposing the immutable North-American phone
 * area-code, United States state-code, and state{@code +}first-two-of-ZIP
 * validation tables used during customer-address and phone-number editing.
 *
 * <p>This service is the Java translation of the COBOL lookup-code copybook
 * {@code CSLKPCDY} (frozen reference SHA {@code 27d6c6f}), which declares three
 * {@code 88}-level condition sets:</p>
 * <ul>
 *   <li>{@code 88 VALID-PHONE-AREA-CODE} on {@code WS-US-PHONE-AREA-CODE-TO-EDIT
 *       PIC XXX} &rarr; {@link #PHONE_AREA_CODES} (490 three-digit codes,
 *       transcribed verbatim from {@code CSLKPCDY} lines 30-520).</li>
 *   <li>{@code 88 VALID-US-STATE-CODE} on {@code US-STATE-CODE-TO-EDIT PIC X(2)}
 *       &rarr; {@link #US_STATE_CODES} (56 two-letter codes, transcribed
 *       verbatim from {@code CSLKPCDY} lines 1014-1069).</li>
 *   <li>{@code 88 VALID-US-STATE-ZIP-CD2-COMBO} on
 *       {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} &rarr;
 *       {@link #STATE_ZIP_COMBOS} (240 four-character state{@code +}ZIP combos,
 *       transcribed verbatim from {@code CSLKPCDY} lines 1074-1313).</li>
 * </ul>
 *
 * <p>The copybook is included by the account-update program {@code COACTUPC}
 * ({@code COPY CSLKPCDY} at line 602); its address- and phone-editing paragraphs
 * exercise these tables (state code at {@code COACTUPC} line 2495, state{@code +}
 * ZIP combination at line 2542, and the phone area code moved into
 * {@code WS-US-PHONE-AREA-CODE-TO-EDIT} at line 2297). The public methods below
 * back {@code AccountUpdateService}, preserving the legacy validation contract
 * byte-for-byte (interface parity, Gate&nbsp;5).</p>
 *
 * <p>All backing collections are constructed once via {@link Set#of(Object...)},
 * making them immutable and safe for concurrent read access from any number of
 * request or batch threads. All inputs are normalised (trimmed and upper-cased)
 * to mirror the fixed-width, upper-cased COBOL working-storage fields, so
 * callers may pass lower-case or space-padded values.</p>
 */
@Service
public class LookupService {

    /**
     * Valid United States state / territory codes.
     *
     * <p>Verbatim transcription of the {@code 88 VALID-US-STATE-CODE} values in
     * {@code CSLKPCDY} (SHA {@code 27d6c6f}, lines 1014-1069): the 50 states,
     * the District of Columbia, and the five inhabited territories (AS, GU, MP,
     * PR, VI) &mdash; 56 codes in total. The set is intentionally sized to match
     * the copybook exactly; no code is added or removed.</p>
     */
    private static final Set<String> US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE",
            "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS",
            "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS",
            "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY",
            "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV",
            "WI", "WY", "DC", "AS", "GU", "MP", "PR", "VI"
    );

    /**
     * Valid North-American phone area codes.
     *
     * <p>Verbatim transcription of the {@code 88 VALID-PHONE-AREA-CODE} values in
     * {@code CSLKPCDY} (SHA {@code 27d6c6f}, lines 30-520): 490 three-digit area
     * codes sourced from the North American Numbering Plan Administrator.</p>
     */
    private static final Set<String> PHONE_AREA_CODES = Set.of(
            "201", "202", "203", "204", "205", "206", "207", "208", "209", "210",
            "212", "213", "214", "215", "216", "217", "218", "219", "220", "223",
            "224", "225", "226", "228", "229", "231", "234", "236", "239", "240",
            "242", "246", "248", "249", "250", "251", "252", "253", "254", "256",
            "260", "262", "264", "267", "268", "269", "270", "272", "276", "279",
            "281", "284", "289", "301", "302", "303", "304", "305", "306", "307",
            "308", "309", "310", "312", "313", "314", "315", "316", "317", "318",
            "319", "320", "321", "323", "325", "326", "330", "331", "332", "334",
            "336", "337", "339", "340", "341", "343", "345", "346", "347", "351",
            "352", "360", "361", "364", "365", "367", "368", "380", "385", "386",
            "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
            "412", "413", "414", "415", "416", "417", "418", "419", "423", "424",
            "425", "430", "431", "432", "434", "435", "437", "438", "440", "441",
            "442", "443", "445", "447", "448", "450", "458", "463", "464", "469",
            "470", "473", "474", "475", "478", "479", "480", "484", "501", "502",
            "503", "504", "505", "506", "507", "508", "509", "510", "512", "513",
            "514", "515", "516", "517", "518", "519", "520", "530", "531", "534",
            "539", "540", "541", "548", "551", "559", "561", "562", "563", "564",
            "567", "570", "571", "572", "573", "574", "575", "579", "580", "581",
            "582", "585", "586", "587", "601", "602", "603", "604", "605", "606",
            "607", "608", "609", "610", "612", "613", "614", "615", "616", "617",
            "618", "619", "620", "623", "626", "628", "629", "630", "631", "636",
            "639", "640", "641", "646", "647", "649", "650", "651", "656", "657",
            "658", "659", "660", "661", "662", "664", "667", "669", "670", "671",
            "672", "678", "680", "681", "682", "683", "684", "689", "701", "702",
            "703", "704", "705", "706", "707", "708", "709", "712", "713", "714",
            "715", "716", "717", "718", "719", "720", "721", "724", "725", "726",
            "727", "731", "732", "734", "737", "740", "742", "743", "747", "753",
            "754", "757", "758", "760", "762", "763", "765", "767", "769", "770",
            "771", "772", "773", "774", "775", "778", "779", "780", "781", "782",
            "784", "785", "786", "787", "801", "802", "803", "804", "805", "806",
            "807", "808", "809", "810", "812", "813", "814", "815", "816", "817",
            "818", "819", "820", "825", "826", "828", "829", "830", "831", "832",
            "838", "839", "840", "843", "845", "847", "848", "849", "850", "854",
            "856", "857", "858", "859", "860", "862", "863", "864", "865", "867",
            "868", "869", "870", "872", "873", "876", "878", "901", "902", "903",
            "904", "905", "906", "907", "908", "909", "910", "912", "913", "914",
            "915", "916", "917", "918", "919", "920", "925", "928", "929", "930",
            "931", "934", "936", "937", "938", "939", "940", "941", "943", "945",
            "947", "948", "949", "951", "952", "954", "956", "959", "970", "971",
            "972", "973", "978", "979", "980", "983", "984", "985", "986", "989",
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
     * Valid United States state{@code +}first-two-of-ZIP combinations.
     *
     * <p>Verbatim transcription of the {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}
     * values in {@code CSLKPCDY} (SHA {@code 27d6c6f}, lines 1074-1313): 240
     * four-character keys, each formed from a two-letter state code followed by
     * the first two digits of a ZIP code (for example {@code "CA90"}).</p>
     */
    private static final Set<String> STATE_ZIP_COMBOS = Set.of(
            "AA34", "AE90", "AE91", "AE92", "AE93", "AE94", "AE95", "AE96", "AE97", "AE98",
            "AK99", "AL35", "AL36", "AP96", "AR71", "AR72", "AS96", "AZ85", "AZ86", "CA90",
            "CA91", "CA92", "CA93", "CA94", "CA95", "CA96", "CO80", "CO81", "CT60", "CT61",
            "CT62", "CT63", "CT64", "CT65", "CT66", "CT67", "CT68", "CT69", "DC20", "DC56",
            "DC88", "DE19", "FL32", "FL33", "FL34", "FM96", "GA30", "GA31", "GA39", "GU96",
            "HI96", "IA50", "IA51", "IA52", "ID83", "IL60", "IL61", "IL62", "IN46", "IN47",
            "KS66", "KS67", "KY40", "KY41", "KY42", "LA70", "LA71", "MA10", "MA11", "MA12",
            "MA13", "MA14", "MA15", "MA16", "MA17", "MA18", "MA19", "MA20", "MA21", "MA22",
            "MA23", "MA24", "MA25", "MA26", "MA27", "MA55", "MD20", "MD21", "ME39", "ME40",
            "ME41", "ME42", "ME43", "ME44", "ME45", "ME46", "ME47", "ME48", "ME49", "MH96",
            "MI48", "MI49", "MN55", "MN56", "MO63", "MO64", "MO65", "MO72", "MP96", "MS38",
            "MS39", "MT59", "NC27", "NC28", "ND58", "NE68", "NE69", "NH30", "NH31", "NH32",
            "NH33", "NH34", "NH35", "NH36", "NH37", "NH38", "NJ70", "NJ71", "NJ72", "NJ73",
            "NJ74", "NJ75", "NJ76", "NJ77", "NJ78", "NJ79", "NJ80", "NJ81", "NJ82", "NJ83",
            "NJ84", "NJ85", "NJ86", "NJ87", "NJ88", "NJ89", "NM87", "NM88", "NV88", "NV89",
            "NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14", "OH43", "OH44",
            "OH45", "OK73", "OK74", "OR97", "PA15", "PA16", "PA17", "PA18", "PA19", "PR60",
            "PR61", "PR62", "PR63", "PR64", "PR65", "PR66", "PR67", "PR68", "PR69", "PR70",
            "PR71", "PR72", "PR73", "PR74", "PR75", "PR76", "PR77", "PR78", "PR79", "PR90",
            "PR91", "PR92", "PR93", "PR94", "PR95", "PR96", "PR97", "PR98", "PW96", "RI28",
            "RI29", "SC29", "SD57", "TN37", "TN38", "TX73", "TX75", "TX76", "TX77", "TX78",
            "TX79", "TX88", "UT84", "VA20", "VA22", "VA23", "VA24", "VI80", "VI82", "VI83",
            "VI84", "VI85", "VT50", "VT51", "VT52", "VT53", "VT54", "VT56", "VT57", "VT58",
            "VT59", "WA98", "WA99", "WI53", "WI54", "WV24", "WV25", "WV26", "WY82", "WY83"
    );

    /**
     * Determines whether the supplied value is a valid US state / territory code.
     *
     * <p>Mirrors the COBOL {@code IF VALID-US-STATE-CODE} test in {@code COACTUPC}
     * (line 2495). The input is trimmed and upper-cased before the membership
     * check so that lower-case or space-padded values are accepted, matching the
     * upper-cased fixed-width COBOL field {@code US-STATE-CODE-TO-EDIT}.</p>
     *
     * @param stateCd the candidate two-letter state code; may be {@code null}
     * @return {@code true} if the normalised value is a member of
     *         {@link #US_STATE_CODES}; {@code false} when {@code stateCd} is
     *         {@code null} or not a recognised code
     */
    public boolean isValidStateCode(String stateCd) {
        return stateCd != null && US_STATE_CODES.contains(stateCd.trim().toUpperCase());
    }

    /**
     * Determines whether the supplied value is a valid North-American phone area
     * code.
     *
     * <p>Mirrors the COBOL area-code edit in the {@code EDIT-AREA-CODE} paragraph
     * of {@code COACTUPC} (the area code is moved into
     * {@code WS-US-PHONE-AREA-CODE-TO-EDIT} at line 2297). The input is trimmed
     * and upper-cased before the membership check.</p>
     *
     * @param areaCode the candidate three-digit area code; may be {@code null}
     * @return {@code true} if the normalised value is a member of
     *         {@link #PHONE_AREA_CODES}; {@code false} when {@code areaCode} is
     *         {@code null} or not a recognised code
     */
    public boolean isValidPhoneAreaCode(String areaCode) {
        return areaCode != null && PHONE_AREA_CODES.contains(areaCode.trim().toUpperCase());
    }

    /**
     * Determines whether the combination of a state code and the first two
     * digits of a ZIP code is a recognised US state{@code +}ZIP pairing.
     *
     * <p>Mirrors the COBOL {@code IF VALID-US-STATE-ZIP-CD2-COMBO} test in
     * {@code COACTUPC} (line 2542). The lookup key is built as the trimmed,
     * upper-cased state code concatenated with the first two characters of the
     * trimmed ZIP code, reproducing the four-byte {@code US-STATE-AND-FIRST-ZIP2}
     * field.</p>
     *
     * @param stateCd the two-letter state code; may be {@code null}
     * @param zip     the ZIP code; may be {@code null}. Only the first two
     *                characters (after trimming) participate in the key
     * @return {@code true} if the derived key is a member of
     *         {@link #STATE_ZIP_COMBOS}; {@code false} when either argument is
     *         {@code null}, when the trimmed ZIP is shorter than two characters,
     *         or when the pairing is not recognised
     */
    public boolean isValidStateZipCombo(String stateCd, String zip) {
        if (stateCd == null || zip == null) {
            return false;
        }
        String normalizedZip = zip.trim();
        if (normalizedZip.length() < 2) {
            return false;
        }
        String key = stateCd.trim().toUpperCase() + normalizedZip.substring(0, 2);
        return STATE_ZIP_COMBOS.contains(key);
    }
}
