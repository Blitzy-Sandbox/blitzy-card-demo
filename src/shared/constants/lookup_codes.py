"""Lookup code constants converted from COBOL copybook CSLKPCDY.cpy.

Contains NANPA phone area codes, US state/territory codes, and state-ZIP prefix
validation combinations. Converted from COBOL 88-level conditions to Python
frozenset collections for O(1) lookup.

The original COBOL copybook defines these as `88`-level condition values on PIC
fields (``PIC XXX``, ``PIC X(2)``, ``PIC X(4)``) — for example::

    01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.
        88 VALID-PHONE-AREA-CODE VALUES '201', '202', ...
        88 VALID-GENERAL-PURP-CODE VALUES '201', '202', ...
        88 VALID-EASY-RECOG-AREA-CODE VALUES '200', '211', ...

    01 US-STATE-CODE-TO-EDIT PIC X(2).
        88 VALID-US-STATE-CODE VALUES 'AL', 'AK', ...

    01 US-STATE-ZIPCODE-TO-EDIT.
       02 US-STATE-AND-FIRST-ZIP2 PIC X(4).
          88 VALID-US-STATE-ZIP-CD2-COMBO VALUES 'AA34', 'AE90', ...

In Python, each 88-level VALUES list becomes a :class:`frozenset` literal, and
the COBOL idiom ``IF VALID-PHONE-AREA-CODE`` is replaced by membership tests
(``code in VALID_PHONE_AREA_CODES``) via the helper functions defined below.

All values are preserved exactly as they appear in the COBOL source — the
migration is a literal translation of the 88-level condition enumerations with
no additions, omissions, or reorderings that would alter membership semantics.
"""
# Source: app/cpy/CSLKPCDY.cpy
#
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# North America Phone area codes List obtained from North America
# Numbering Plan Administrator (nanpa): https://nationalnanpa.com/nanp1/npa_report.csv

from __future__ import annotations

# ---------------------------------------------------------------------------
# VALID_GENERAL_PURPOSE_CODES
# ---------------------------------------------------------------------------
# Source: COBOL 88 VALID-GENERAL-PURP-CODE (CSLKPCDY.cpy lines 521-930).
# 410 real NANPA area codes that have been assigned to a geographic area
# (i.e., excluding the "easily recognizable codes" enumerated separately in
# ``VALID_EASILY_RECOGNIZABLE_CODES``).
#
# A :class:`frozenset` is used for immutability and O(1) membership testing,
# mirroring the semantics of a COBOL 88-level condition check.
#
# The ``# fmt: off`` / ``# fmt: on`` directives preserve the compact row-wise
# layout (10 codes per line, grouped by century) for human readability. Each
# group corresponds to a range of line numbers in the COBOL source.
# fmt: off
VALID_GENERAL_PURPOSE_CODES: frozenset[str] = frozenset({
    # --- 2xx ---
    "201", "202", "203", "204", "205", "206", "207", "208", "209", "210",
    "212", "213", "214", "215", "216", "217", "218", "219", "220", "223",
    "224", "225", "226", "228", "229", "231", "234", "236", "239", "240",
    "242", "246", "248", "249", "250", "251", "252", "253", "254", "256",
    "260", "262", "264", "267", "268", "269", "270", "272", "276", "279",
    "281", "284", "289",
    # --- 3xx ---
    "301", "302", "303", "304", "305", "306", "307", "308", "309", "310",
    "312", "313", "314", "315", "316", "317", "318", "319", "320", "321",
    "323", "325", "326", "330", "331", "332", "334", "336", "337", "339",
    "340", "341", "343", "345", "346", "347", "351", "352", "360", "361",
    "364", "365", "367", "368", "380", "385", "386",
    # --- 4xx ---
    "401", "402", "403", "404", "405", "406", "407", "408", "409", "410",
    "412", "413", "414", "415", "416", "417", "418", "419", "423", "424",
    "425", "430", "431", "432", "434", "435", "437", "438", "440", "441",
    "442", "443", "445", "447", "448", "450", "458", "463", "464", "469",
    "470", "473", "474", "475", "478", "479", "480", "484",
    # --- 5xx ---
    "501", "502", "503", "504", "505", "506", "507", "508", "509", "510",
    "512", "513", "514", "515", "516", "517", "518", "519", "520", "530",
    "531", "534", "539", "540", "541", "548", "551", "559", "561", "562",
    "563", "564", "567", "570", "571", "572", "573", "574", "575", "579",
    "580", "581", "582", "585", "586", "587",
    # --- 6xx ---
    "601", "602", "603", "604", "605", "606", "607", "608", "609", "610",
    "612", "613", "614", "615", "616", "617", "618", "619", "620", "623",
    "626", "628", "629", "630", "631", "636", "639", "640", "641", "646",
    "647", "649", "650", "651", "656", "657", "658", "659", "660", "661",
    "662", "664", "667", "669", "670", "671", "672", "678", "680", "681",
    "682", "683", "684", "689",
    # --- 7xx ---
    "701", "702", "703", "704", "705", "706", "707", "708", "709", "712",
    "713", "714", "715", "716", "717", "718", "719", "720", "721", "724",
    "725", "726", "727", "731", "732", "734", "737", "740", "742", "743",
    "747", "753", "754", "757", "758", "760", "762", "763", "765", "767",
    "769", "770", "771", "772", "773", "774", "775", "778", "779", "780",
    "781", "782", "784", "785", "786", "787",
    # --- 8xx ---
    "801", "802", "803", "804", "805", "806", "807", "808", "809", "810",
    "812", "813", "814", "815", "816", "817", "818", "819", "820", "825",
    "826", "828", "829", "830", "831", "832", "838", "839", "840", "843",
    "845", "847", "848", "849", "850", "854", "856", "857", "858", "859",
    "860", "862", "863", "864", "865", "867", "868", "869", "870", "872",
    "873", "876", "878",
    # --- 9xx ---
    "901", "902", "903", "904", "905", "906", "907", "908", "909", "910",
    "912", "913", "914", "915", "916", "917", "918", "919", "920", "925",
    "928", "929", "930", "931", "934", "936", "937", "938", "939", "940",
    "941", "943", "945", "947", "948", "949", "951", "952", "954", "956",
    "959", "970", "971", "972", "973", "978", "979", "980", "983", "984",
    "985", "986", "989",
})
# fmt: on


# ---------------------------------------------------------------------------
# VALID_EASILY_RECOGNIZABLE_CODES
# ---------------------------------------------------------------------------
# Source: COBOL 88 VALID-EASY-RECOG-AREA-CODE (CSLKPCDY.cpy lines 931-1010).
# 80 NANPA "easily recognizable codes" (ERC) — pattern-based codes consisting
# of a digit 2-9 followed by a doubled digit (e.g., 200, 211, 222, ..., 999).
# These codes are reserved for services (e.g., 800, 888, 877 for toll-free;
# 900 for premium; N11 for abbreviated dialing) rather than geographic areas.
#
# The complete set is the Cartesian product ``{2..9} x {00, 11, 22, ..., 99}``.
# fmt: off
VALID_EASILY_RECOGNIZABLE_CODES: frozenset[str] = frozenset({
    "200", "211", "222", "233", "244", "255", "266", "277", "288", "299",
    "300", "311", "322", "333", "344", "355", "366", "377", "388", "399",
    "400", "411", "422", "433", "444", "455", "466", "477", "488", "499",
    "500", "511", "522", "533", "544", "555", "566", "577", "588", "599",
    "600", "611", "622", "633", "644", "655", "666", "677", "688", "699",
    "700", "711", "722", "733", "744", "755", "766", "777", "788", "799",
    "800", "811", "822", "833", "844", "855", "866", "877", "888", "899",
    "900", "911", "922", "933", "944", "955", "966", "977", "988", "999",
})
# fmt: on


# ---------------------------------------------------------------------------
# VALID_PHONE_AREA_CODES
# ---------------------------------------------------------------------------
# Source: COBOL 88 VALID-PHONE-AREA-CODE (CSLKPCDY.cpy lines 30-520).
# 490 total codes — the complete NANPA area code universe accepted by the
# validator. This is the union of ``VALID_GENERAL_PURPOSE_CODES`` (410 real
# geographic codes) and ``VALID_EASILY_RECOGNIZABLE_CODES`` (80 pattern codes).
#
# The values below are enumerated literally from lines 30-520 of the COBOL
# source to preserve exact parity with the 88-level condition. The invariant
# ``VALID_PHONE_AREA_CODES == VALID_GENERAL_PURPOSE_CODES |
# VALID_EASILY_RECOGNIZABLE_CODES`` is enforced by the unit tests accompanying
# this module.
# fmt: off
VALID_PHONE_AREA_CODES: frozenset[str] = frozenset({
    "200", "201", "202", "203", "204", "205", "206", "207", "208", "209",
    "210", "211", "212", "213", "214", "215", "216", "217", "218", "219",
    "220", "222", "223", "224", "225", "226", "228", "229", "231", "233",
    "234", "236", "239", "240", "242", "244", "246", "248", "249", "250",
    "251", "252", "253", "254", "255", "256", "260", "262", "264", "266",
    "267", "268", "269", "270", "272", "276", "277", "279", "281", "284",
    "288", "289", "299",
    "300", "301", "302", "303", "304", "305", "306", "307", "308", "309",
    "310", "311", "312", "313", "314", "315", "316", "317", "318", "319",
    "320", "321", "322", "323", "325", "326", "330", "331", "332", "333",
    "334", "336", "337", "339", "340", "341", "343", "344", "345", "346",
    "347", "351", "352", "355", "360", "361", "364", "365", "366", "367",
    "368", "377", "380", "385", "386", "388", "399",
    "400", "401", "402", "403", "404", "405", "406", "407", "408", "409",
    "410", "411", "412", "413", "414", "415", "416", "417", "418", "419",
    "422", "423", "424", "425", "430", "431", "432", "433", "434", "435",
    "437", "438", "440", "441", "442", "443", "444", "445", "447", "448",
    "450", "455", "458", "463", "464", "466", "469", "470", "473", "474",
    "475", "477", "478", "479", "480", "484", "488", "499",
    "500", "501", "502", "503", "504", "505", "506", "507", "508", "509",
    "510", "511", "512", "513", "514", "515", "516", "517", "518", "519",
    "520", "522", "530", "531", "533", "534", "539", "540", "541", "544",
    "548", "551", "555", "559", "561", "562", "563", "564", "566", "567",
    "570", "571", "572", "573", "574", "575", "577", "579", "580", "581",
    "582", "585", "586", "587", "588", "599",
    "600", "601", "602", "603", "604", "605", "606", "607", "608", "609",
    "610", "611", "612", "613", "614", "615", "616", "617", "618", "619",
    "620", "622", "623", "626", "628", "629", "630", "631", "633", "636",
    "639", "640", "641", "644", "646", "647", "649", "650", "651", "655",
    "656", "657", "658", "659", "660", "661", "662", "664", "666", "667",
    "669", "670", "671", "672", "677", "678", "680", "681", "682", "683",
    "684", "688", "689", "699",
    "700", "701", "702", "703", "704", "705", "706", "707", "708", "709",
    "711", "712", "713", "714", "715", "716", "717", "718", "719", "720",
    "721", "722", "724", "725", "726", "727", "731", "732", "733", "734",
    "737", "740", "742", "743", "744", "747", "753", "754", "755", "757",
    "758", "760", "762", "763", "765", "766", "767", "769", "770", "771",
    "772", "773", "774", "775", "777", "778", "779", "780", "781", "782",
    "784", "785", "786", "787", "788", "799",
    "800", "801", "802", "803", "804", "805", "806", "807", "808", "809",
    "810", "811", "812", "813", "814", "815", "816", "817", "818", "819",
    "820", "822", "825", "826", "828", "829", "830", "831", "832", "833",
    "838", "839", "840", "843", "844", "845", "847", "848", "849", "850",
    "854", "855", "856", "857", "858", "859", "860", "862", "863", "864",
    "865", "866", "867", "868", "869", "870", "872", "873", "876", "877",
    "878", "888", "899",
    "900", "901", "902", "903", "904", "905", "906", "907", "908", "909",
    "910", "911", "912", "913", "914", "915", "916", "917", "918", "919",
    "920", "922", "925", "928", "929", "930", "931", "933", "934", "936",
    "937", "938", "939", "940", "941", "943", "944", "945", "947", "948",
    "949", "951", "952", "954", "955", "956", "959", "966", "970", "971",
    "972", "973", "977", "978", "979", "980", "983", "984", "985", "986",
    "988", "989", "999",
})
# fmt: on


# ---------------------------------------------------------------------------
# VALID_US_STATE_CODES
# ---------------------------------------------------------------------------
# Source: COBOL 88 VALID-US-STATE-CODE (CSLKPCDY.cpy lines 1013-1069).
# 56 two-letter USPS codes — 50 states plus DC and 5 US territories
# (American Samoa, Guam, Northern Mariana Islands, Puerto Rico, US Virgin
# Islands). Values are preserved in COBOL source order (alphabetical by
# state, then DC, then territories).
# fmt: off
VALID_US_STATE_CODES: frozenset[str] = frozenset({
    "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
    "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
    "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
    "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
    "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
    "DC",
    "AS", "GU", "MP", "PR", "VI",
})
# fmt: on


# ---------------------------------------------------------------------------
# VALID_US_STATE_ZIP_COMBOS
# ---------------------------------------------------------------------------
# Source: COBOL 88 VALID-US-STATE-ZIP-CD2-COMBO (CSLKPCDY.cpy lines 1073-1313).
# 240 four-character codes concatenating a 2-letter USPS code with the first
# two digits of a valid ZIP code for that state/territory. Used to validate
# that a ZIP code's leading digits are consistent with the stated jurisdiction.
#
# The set includes:
#   - All 50 US states (grouped by state abbreviation)
#   - District of Columbia (DC20, DC56, DC88)
#   - US territories (AS, GU, MP, PR, VI)
#   - Armed Forces addresses (AA34, AE90-AE98, AP96)
#   - Pacific freely-associated states (FM96, MH96, PW96)
#
# Note: AA/AE/AP/FM/MH/PW are NOT members of ``VALID_US_STATE_CODES`` — they
# are USPS delivery designations rather than states. This asymmetry is
# faithful to the COBOL source.
# fmt: off
VALID_US_STATE_ZIP_COMBOS: frozenset[str] = frozenset({
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
    "CT60", "CT61", "CT62", "CT63", "CT64", "CT65", "CT66", "CT67", "CT68",
    "CT69",
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
    "MA10", "MA11", "MA12", "MA13", "MA14", "MA15", "MA16", "MA17", "MA18",
    "MA19", "MA20", "MA21", "MA22", "MA23", "MA24", "MA25", "MA26", "MA27",
    "MA55",
    "MD20", "MD21",
    "ME39", "ME40", "ME41", "ME42", "ME43", "ME44", "ME45", "ME46", "ME47",
    "ME48", "ME49",
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
    "NJ70", "NJ71", "NJ72", "NJ73", "NJ74", "NJ75", "NJ76", "NJ77", "NJ78",
    "NJ79", "NJ80", "NJ81", "NJ82", "NJ83", "NJ84", "NJ85", "NJ86", "NJ87",
    "NJ88", "NJ89",
    "NM87", "NM88",
    "NV88", "NV89",
    "NY50", "NY54", "NY63", "NY10", "NY11", "NY12", "NY13", "NY14",
    "OH43", "OH44", "OH45",
    "OK73", "OK74",
    "OR97",
    "PA15", "PA16", "PA17", "PA18", "PA19",
    "PR60", "PR61", "PR62", "PR63", "PR64", "PR65", "PR66", "PR67", "PR68",
    "PR69", "PR70", "PR71", "PR72", "PR73", "PR74", "PR75", "PR76", "PR77",
    "PR78", "PR79", "PR90", "PR91", "PR92", "PR93", "PR94", "PR95", "PR96",
    "PR97", "PR98",
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
    "WY82", "WY83",
})
# fmt: on


# ---------------------------------------------------------------------------
# Validation helper functions
# ---------------------------------------------------------------------------
# These mirror the semantics of COBOL 88-level condition checks:
#
#     COBOL:   IF VALID-PHONE-AREA-CODE
#     Python:  if is_valid_phone_area_code(code):
#
# The COBOL 88-level condition is true when the parent data item equals any
# value in the condition's VALUES list. In Python this corresponds to a
# membership test against the corresponding ``frozenset``.


def is_valid_phone_area_code(code: str) -> bool:
    """Return ``True`` if *code* is an accepted NANPA area code.

    Mirrors the COBOL 88-level condition ``VALID-PHONE-AREA-CODE``: the full
    set of 490 codes (general-purpose geographic codes plus the 80 easily
    recognizable pattern codes).

    The comparison is case-sensitive because COBOL PIC XXX fields for area
    codes are digit-only (0-9) — there is no letter-case concept.

    Parameters
    ----------
    code:
        A 3-character area code string to validate.

    Returns
    -------
    bool
        ``True`` if *code* is a member of :data:`VALID_PHONE_AREA_CODES`;
        ``False`` otherwise. A non-string or incorrectly-sized value will
        simply fail membership and return ``False``.
    """
    return code in VALID_PHONE_AREA_CODES


def is_valid_us_state_code(code: str) -> bool:
    """Return ``True`` if *code* is an accepted 2-letter USPS state code.

    Mirrors the COBOL 88-level condition ``VALID-US-STATE-CODE``. The input
    is normalized to upper case before lookup so that lowercase or mixed-case
    input (e.g., ``"ca"`` or ``"Ca"``) is accepted. COBOL source data is
    typically upper case; the normalization is a pragmatic accommodation for
    JSON API consumers.

    Parameters
    ----------
    code:
        A 2-character state/territory code to validate.

    Returns
    -------
    bool
        ``True`` if ``code.upper()`` is a member of
        :data:`VALID_US_STATE_CODES`; ``False`` otherwise.
    """
    return code.upper() in VALID_US_STATE_CODES


def is_valid_state_zip_combo(state_zip: str) -> bool:
    """Return ``True`` if *state_zip* is an accepted state/ZIP-prefix pair.

    Mirrors the COBOL 88-level condition ``VALID-US-STATE-ZIP-CD2-COMBO``.
    The input is the 2-letter state code concatenated with the first two
    digits of the ZIP code (e.g., ``"CA90"`` for a ZIP starting with ``90``
    in California). The input is normalized to upper case before lookup.

    Parameters
    ----------
    state_zip:
        A 4-character string: 2-letter state code followed by 2-digit ZIP
        prefix.

    Returns
    -------
    bool
        ``True`` if ``state_zip.upper()`` is a member of
        :data:`VALID_US_STATE_ZIP_COMBOS`; ``False`` otherwise.
    """
    return state_zip.upper() in VALID_US_STATE_ZIP_COMBOS


__all__ = [
    "VALID_PHONE_AREA_CODES",
    "VALID_GENERAL_PURPOSE_CODES",
    "VALID_EASILY_RECOGNIZABLE_CODES",
    "VALID_US_STATE_CODES",
    "VALID_US_STATE_ZIP_COMBOS",
    "is_valid_phone_area_code",
    "is_valid_us_state_code",
    "is_valid_state_zip_combo",
]
