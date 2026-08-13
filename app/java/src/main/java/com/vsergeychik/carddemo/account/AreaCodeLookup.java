package com.vsergeychik.carddemo.account;

import com.vsergeychik.carddemo.common.FixedWidthCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Translation of {@code app/cpy/CSLKPCDY.cpy}, the CardDemo lookup-code repository: the North American
 * telephone area codes, the United States state codes, and the permitted state-plus-first-two-digits-of-ZIP
 * combinations.
 *
 * <p>Any generated approximation admits or rejects at least one code the COBOL does not, and that is a
 * parity diff.
 */
@Component
public final class AreaCodeLookup {
    /**
     * Width of {@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX} - {@code app/cpy/CSLKPCDY.cpy:24}.
     */
    public static final int PHONE_AREA_CODE_LENGTH = 3;

    /**
     * Width of {@code 01 US-STATE-CODE-TO-EDIT PIC X(2)} - {@code app/cpy/CSLKPCDY.cpy:1012}.
     */
    public static final int US_STATE_CODE_LENGTH = 2;

    /**
     * Width of {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} - {@code app/cpy/CSLKPCDY.cpy:1072}.
     */
    public static final int STATE_AND_FIRST_ZIP2_LENGTH = 4;

    /**
     * Width of {@code 02 LAST-3-OF-ZIP PIC X(3)} - {@code app/cpy/CSLKPCDY.cpy:1314}.
     */
    public static final int LAST_3_OF_ZIP_LENGTH = 3;

    /**
     * Width of the whole group {@code 01 US-STATE-ZIPCODE-TO-EDIT} - {@code app/cpy/CSLKPCDY.cpy:1071}.
     */
    public static final int STATE_ZIPCODE_GROUP_LENGTH =
            STATE_AND_FIRST_ZIP2_LENGTH + LAST_3_OF_ZIP_LENGTH;

    /**
     * Zero-based offset of {@code US-STATE-AND-FIRST-ZIP2} within {@code 01 US-STATE-ZIPCODE-TO-EDIT}: it
     * is the first subordinate, so COBOL position 1.
     */
    public static final int STATE_AND_FIRST_ZIP2_OFFSET = 0;

    /**
     * Zero-based offset of {@code LAST-3-OF-ZIP} within {@code 01 US-STATE-ZIPCODE-TO-EDIT}: it follows a
     * four-character subordinate, so COBOL position 5.
     */
    public static final int LAST_3_OF_ZIP_OFFSET = STATE_AND_FIRST_ZIP2_LENGTH;

    /**
     * Width of {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} - {@code app/cbl/COACTUPC.cbl:809}.
     */
    public static final int CUSTOMER_ZIP_LENGTH = 10;

    /**
     * Number of ZIP characters the {@code STRING} at {@code app/cbl/COACTUPC.cbl:2538} contributes:
     * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} takes exactly the first two.
     */
    public static final int ZIP_PREFIX_LENGTH = 2;

    /**
     * Literals in {@code 88 VALID-PHONE-AREA-CODE} - {@code app/cpy/CSLKPCDY.cpy:30-520}.
     */
    public static final int VALID_PHONE_AREA_CODE_COUNT = 490;

    /**
     * Literals in {@code 88 VALID-GENERAL-PURP-CODE} - {@code app/cpy/CSLKPCDY.cpy:521-930}.
     */
    public static final int VALID_GENERAL_PURP_CODE_COUNT = 410;

    /**
     * Literals in {@code 88 VALID-EASY-RECOG-AREA-CODE} - {@code app/cpy/CSLKPCDY.cpy:931-1010}.
     */
    public static final int VALID_EASY_RECOG_AREA_CODE_COUNT = 80;

    /**
     * Literals in {@code 88 VALID-US-STATE-CODE} - {@code app/cpy/CSLKPCDY.cpy:1013-1069}.
     */
    public static final int VALID_US_STATE_CODE_COUNT = 56;

    /**
     * Literals in {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} - {@code app/cpy/CSLKPCDY.cpy:1073-1313}.
     */
    public static final int VALID_US_STATE_ZIP_CD2_COMBO_COUNT = 240;

    private static final String ITEM_PHONE_AREA_CODE_PROBE = "WS-US-PHONE-AREA-CODE-TO-EDIT";

    private static final String ITEM_US_STATE_CODE_PROBE = "US-STATE-CODE-TO-EDIT";

    private static final String ITEM_STATE_AND_FIRST_ZIP2 = "US-STATE-AND-FIRST-ZIP2";

    private static final String ITEM_STATE_ZIPCODE_GROUP = "US-STATE-ZIPCODE-TO-EDIT";

    private static final String ITEM_CUSTOMER_STATE_CODE = "ACUP-NEW-CUST-ADDR-STATE-CD";

    private static final String ITEM_CUSTOMER_ZIP = "ACUP-NEW-CUST-ADDR-ZIP";

    private static final Set<String> VALID_PHONE_AREA_CODE = buildValidPhoneAreaCode();

    private static final Set<String> VALID_GENERAL_PURP_CODE = buildValidGeneralPurpCode();

    private static final Set<String> VALID_EASY_RECOG_AREA_CODE = buildValidEasyRecogAreaCode();

    private static final Set<String> VALID_US_STATE_CODE = buildValidUsStateCode();

    private static final Set<String> VALID_US_STATE_ZIP_CD2_COMBO = buildValidUsStateZipCd2Combo();

    private final FixedWidthCodec codec;

    /**
     * Creates a lookup with its own codec over {@link StandardCharsets#US_ASCII}.
     */
    public AreaCodeLookup() {
        this(new FixedWidthCodec(StandardCharsets.US_ASCII));
    }

    /**
     * Creates a lookup that applies {@code PICTURE} width rules through the supplied codec.
     *
     * @param codec the codec whose {@link FixedWidthCodec#movePicX(String, int)} normalises every argument
     *     to its probe's declared width; must not be {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public AreaCodeLookup(FixedWidthCodec codec) {
        this.codec = Objects.requireNonNull(codec, "A FixedWidthCodec is required: every argument "
                + "is put through the COBOL alphanumeric MOVE rule at its probe's declared width "
                + "before it is looked up, and that rule is the codec's to apply");
    }

    /**
     * Tests {@code 88 VALID-PHONE-AREA-CODE} - is this one of the 490 North American Numbering Plan area
     * codes of {@code app/cpy/CSLKPCDY.cpy:30-520}?
     *
     * <p>The argument is first moved into {@code WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX}, so it is
     * space-padded on the right when shorter than three characters and truncated on the right when longer.
     *
     * @param areaCode the value being moved into the probe; must not be {@code null}, and may be of any
     *     length
     * @return {@code true} when the three-character probe image is one of the 490 literals
     * @throws NullPointerException if {@code areaCode} is {@code null}
     */
    public boolean isValidPhoneAreaCode(String areaCode) {
        return VALID_PHONE_AREA_CODE.contains(phoneAreaCodeProbe(areaCode));
    }

    /**
     * Tests {@code 88 VALID-GENERAL-PURP-CODE} - is this one of the 410 general purpose area codes of
     * {@code app/cpy/CSLKPCDY.cpy:521-930}?
     *
     * <p>Trimming is the caller's business and is deliberately not repeated here: this method reproduces
     * the {@code MOVE} into the probe and the {@code 88}-level test, which is precisely the copybook's half
     * of the contract.
     *
     * @param areaCode the value being moved into the probe; must not be {@code null}, and may be of any
     *     length
     * @return {@code true} when the three-character probe image is one of the 410 literals
     * @throws NullPointerException if {@code areaCode} is {@code null}
     */
    public boolean isValidGeneralPurposeCode(String areaCode) {
        return VALID_GENERAL_PURP_CODE.contains(phoneAreaCodeProbe(areaCode));
    }

    /**
     * Tests {@code 88 VALID-EASY-RECOG-AREA-CODE} - is this one of the 80 easily recognisable area codes of
     * {@code app/cpy/CSLKPCDY.cpy:931-1010}?
     *
     * @param areaCode the value being moved into the probe; must not be {@code null}, and may be of any
     *     length
     * @return {@code true} when the three-character probe image is one of the 80 literals
     * @throws NullPointerException if {@code areaCode} is {@code null}
     */
    public boolean isValidEasyRecognitionAreaCode(String areaCode) {
        return VALID_EASY_RECOG_AREA_CODE.contains(phoneAreaCodeProbe(areaCode));
    }

    /**
     * Tests {@code 88 VALID-US-STATE-CODE} - is this one of the 56 United States state, district and
     * territory codes of {@code app/cpy/CSLKPCDY.cpy:1013-1069}?
     *
     * <p>The sender is {@code PIC X(02)} and the receiver {@code PIC X(2)}, so in the real program the move
     * is a same-width copy; the width rule is applied here regardless, so that a caller passing a shorter
     * or longer value is treated exactly as COBOL would treat it.
     *
     * @param stateCode the value being moved into the probe; must not be {@code null}, and may be of any
     *     length
     * @return {@code true} when the two-character probe image is one of the 56 literals
     * @throws NullPointerException if {@code stateCode} is {@code null}
     */
    public boolean isValidUsStateCode(String stateCode) {
        return VALID_US_STATE_CODE.contains(usStateCodeProbe(stateCode));
    }

    /**
     * Tests {@code 88 VALID-US-STATE-ZIP-CD2-COMBO} against an already composed four-character probe - is
     * this one of the 240 state-and-ZIP-prefix combinations of {@code app/cpy/CSLKPCDY.cpy:1073-1313}?
     *
     * <p>The argument is moved into {@code US-STATE-AND-FIRST-ZIP2 PIC X(4)} first, so it is space-padded
     * on the right when shorter than four characters and truncated on the right when longer.
     *
     * @param stateAndFirstZip2 the four-character image, two of state code followed by two of ZIP; must not
     *     be {@code null}, and may be of any length
     * @return {@code true} when the four-character probe image is one of the 240 literals
     * @throws NullPointerException if {@code stateAndFirstZip2} is {@code null}
     */
    public boolean isValidStateZip2Combo(String stateAndFirstZip2) {
        return VALID_US_STATE_ZIP_CD2_COMBO.contains(
                stateAndFirstZip2Probe(stateAndFirstZip2));
    }

    /**
     * The whole of {@code 1280-EDIT-US-STATE-ZIP-CD} - composes the probe from a state code and a ZIP code
     * and then tests {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}.
     *
     * @param stateCode the {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} value; must not be {@code null}
     * @param zipCode the {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} value, of which only the first two
     *     characters are used; must not be {@code null}
     * @return {@code true} when the composed four-character probe is one of the 240 literals
     * @throws NullPointerException if either argument is {@code null}
     */
    public boolean isValidStateAndZipCombination(String stateCode, String zipCode) {
        return VALID_US_STATE_ZIP_CD2_COMBO.contains(
                composeStateAndFirstZip2(stateCode, zipCode));
    }

    /**
     * Composes {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} exactly as the {@code STRING} statement at
     * {@code app/cbl/COACTUPC.cbl:2537-2540} does.
     *
     * <p>Two characters plus two characters exactly fill the four-character receiver, so the transfer
     * overwrites the whole field and COBOL's rule that {@code STRING} leaves any unreached part of a
     * receiver untouched never comes into play here.
     *
     * @param stateCode the {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} value; must not be {@code null}
     * @param zipCode the {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} value; must not be {@code null}
     * @return the four-character probe image, always exactly {@value #STATE_AND_FIRST_ZIP2_LENGTH}
     *     characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String composeStateAndFirstZip2(String stateCode, String zipCode) {
        Objects.requireNonNull(stateCode, "A state code is required to compose "
                + ITEM_STATE_AND_FIRST_ZIP2 + "; " + ITEM_CUSTOMER_STATE_CODE
                + " is PIC X(02) and COBOL has no null, so pass SPACES explicitly to compose a "
                + "blank probe");
        Objects.requireNonNull(zipCode, "A ZIP code is required to compose "
                + ITEM_STATE_AND_FIRST_ZIP2 + "; " + ITEM_CUSTOMER_ZIP
                + " is PIC X(10) and COBOL has no null, so pass SPACES explicitly to compose a "
                + "blank probe");

        String state = codec.movePicX(stateCode, US_STATE_CODE_LENGTH);
        String zipAtDeclaredWidth = codec.movePicX(zipCode, CUSTOMER_ZIP_LENGTH);
        // ACUP-NEW-CUST-ADDR-ZIP(1:2): an alphanumeric MOVE truncates on the right, so moving the
        // ten-character image into a two-character receiver yields exactly positions 1 and 2.
        String zipPrefix = codec.movePicX(zipAtDeclaredWidth, ZIP_PREFIX_LENGTH);

        String composed = codec.concatenateDelimitedBySize(state, zipPrefix);
        // The receiver is PIC X(4) and the concatenation is exactly 4, so this is an identity move; it is
        // written out so the receiver's declared width is stated at the point of assignment rather than
        // inferred from the operands.
        return codec.movePicX(composed, STATE_AND_FIRST_ZIP2_LENGTH);
    }

    /**
     * The image of the whole group {@code 01 US-STATE-ZIPCODE-TO-EDIT} after
     * {@code 1280-EDIT-US-STATE-ZIP-CD} has run - seven characters, the composed probe followed by a blank
     * {@code LAST-3-OF-ZIP}.
     *
     * @param stateCode the {@code ACUP-NEW-CUST-ADDR-STATE-CD PIC X(02)} value; must not be {@code null}
     * @param zipCode the {@code ACUP-NEW-CUST-ADDR-ZIP PIC X(10)} value; must not be {@code null}
     * @return the group image, always exactly {@link #STATE_ZIPCODE_GROUP_LENGTH} characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String stateZipcodeGroupImage(String stateCode, String zipCode) {
        // Padding the four-character subordinate out to the group's width is exactly what leaves
        // LAST-3-OF-ZIP blank: an alphanumeric MOVE pads on the right with spaces.
        return codec.movePicX(composeStateAndFirstZip2(stateCode, zipCode),
                STATE_ZIPCODE_GROUP_LENGTH);
    }

    /**
     * Reads {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4)} out of a {@code 01 US-STATE-ZIPCODE-TO-EDIT} group
     * image - COBOL positions 1 through 4.
     *
     * @param stateZipcodeGroupImage an image of {@code 01 US-STATE-ZIPCODE-TO-EDIT}; must not be
     *     {@code null}, and may be of any length
     * @return the four characters of {@code US-STATE-AND-FIRST-ZIP2}, always exactly
     *     {@value #STATE_AND_FIRST_ZIP2_LENGTH} characters
     * @throws NullPointerException if {@code stateZipcodeGroupImage} is {@code null}
     */
    public String stateAndFirstZip2(String stateZipcodeGroupImage) {
        String group = requireGroupImage(stateZipcodeGroupImage);
        return group.substring(STATE_AND_FIRST_ZIP2_OFFSET,
                STATE_AND_FIRST_ZIP2_OFFSET + STATE_AND_FIRST_ZIP2_LENGTH);
    }

    /**
     * Reads {@code 02 LAST-3-OF-ZIP PIC X(3)} out of a {@code 01 US-STATE-ZIPCODE-TO-EDIT} group image -
     * COBOL positions 5 through 7.
     *
     * @param stateZipcodeGroupImage an image of {@code 01 US-STATE-ZIPCODE-TO-EDIT}; must not be
     *     {@code null}, and may be of any length
     * @return the three characters of {@code LAST-3-OF-ZIP}, always exactly {@value #LAST_3_OF_ZIP_LENGTH}
     *     characters
     * @throws NullPointerException if {@code stateZipcodeGroupImage} is {@code null}
     */
    public String lastThreeOfZip(String stateZipcodeGroupImage) {
        String group = requireGroupImage(stateZipcodeGroupImage);
        return group.substring(LAST_3_OF_ZIP_OFFSET, LAST_3_OF_ZIP_OFFSET + LAST_3_OF_ZIP_LENGTH);
    }

    private String requireGroupImage(String stateZipcodeGroupImage) {
        Objects.requireNonNull(stateZipcodeGroupImage, "A group image is required to read a "
                + "subordinate of " + ITEM_STATE_ZIPCODE_GROUP + "; the group is "
                + STATE_ZIPCODE_GROUP_LENGTH + " characters and COBOL has no null, so pass SPACES "
                + "explicitly for an unset group");
        return codec.movePicX(stateZipcodeGroupImage, STATE_ZIPCODE_GROUP_LENGTH);
    }

    /**
     * {@code 88 VALID-PHONE-AREA-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_PHONE_AREA_CODE_COUNT} three-character codes
     */
    public Set<String> validPhoneAreaCodes() {
        return VALID_PHONE_AREA_CODE;
    }

    /**
     * {@code 88 VALID-GENERAL-PURP-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_GENERAL_PURP_CODE_COUNT} three-character codes
     */
    public Set<String> validGeneralPurposeCodes() {
        return VALID_GENERAL_PURP_CODE;
    }

    /**
     * {@code 88 VALID-EASY-RECOG-AREA-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_EASY_RECOG_AREA_CODE_COUNT} three-character codes
     */
    public Set<String> validEasyRecognitionAreaCodes() {
        return VALID_EASY_RECOG_AREA_CODE;
    }

    /**
     * {@code 88 VALID-US-STATE-CODE}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_US_STATE_CODE_COUNT} two-character codes
     */
    public Set<String> validUsStateCodes() {
        return VALID_US_STATE_CODE;
    }

    /**
     * {@code 88 VALID-US-STATE-ZIP-CD2-COMBO}, in the copybook's declaration order.
     *
     * @return an unmodifiable set of {@value #VALID_US_STATE_ZIP_CD2_COMBO_COUNT} four-character
     *     combinations
     */
    public Set<String> validStateZip2Combos() {
        return VALID_US_STATE_ZIP_CD2_COMBO;
    }

    /**
     * Performs {@code MOVE ... TO WS-US-PHONE-AREA-CODE-TO-EDIT}: the argument as an image of exactly
     * {@value #PHONE_AREA_CODE_LENGTH} characters.
     *
     * @param areaCode the three-digit area code to test
     */
    private String phoneAreaCodeProbe(String areaCode) {
        return moveIntoProbe(areaCode, PHONE_AREA_CODE_LENGTH, ITEM_PHONE_AREA_CODE_PROBE);
    }

    /**
     * Performs {@code MOVE ... TO US-STATE-CODE-TO-EDIT}: the argument as an image of exactly
     * {@value #US_STATE_CODE_LENGTH} characters.
     *
     * @param stateCode the two-character state code to test
     */
    private String usStateCodeProbe(String stateCode) {
        return moveIntoProbe(stateCode, US_STATE_CODE_LENGTH, ITEM_US_STATE_CODE_PROBE);
    }

    /**
     * Performs a move into {@code US-STATE-AND-FIRST-ZIP2}: the argument as an image of exactly
     * {@value #STATE_AND_FIRST_ZIP2_LENGTH} characters.
     *
     * @param stateAndFirstZip2 the state code followed by the first two digits of the ZIP code
     */
    private String stateAndFirstZip2Probe(String stateAndFirstZip2) {
        return moveIntoProbe(stateAndFirstZip2, STATE_AND_FIRST_ZIP2_LENGTH,
                ITEM_STATE_AND_FIRST_ZIP2);
    }

    private String moveIntoProbe(String value, int width, String cobolItem) {
        Objects.requireNonNull(value, "A value is required to probe " + cobolItem + ": the item is "
                + "PIC X(" + width + ") and COBOL has no null, so pass SPACES or an empty string "
                + "explicitly to probe a blank value - which matches no literal in "
                + "app/cpy/CSLKPCDY.cpy");
        return codec.movePicX(value, width);
    }

    private static Set<String> orderedSet(String cobolItem, int declaredCount, String... literals) {
        Objects.requireNonNull(literals, "The literals of " + cobolItem + " are required");
        Set<String> ordered = new LinkedHashSet<>(Arrays.asList(literals));
        return requireExactSize(Collections.unmodifiableSet(ordered), declaredCount, cobolItem);
    }

    static Set<String> requireExactSize(Set<String> values, int declaredCount, String cobolItem) {
        Objects.requireNonNull(values, "A table is required to check the cardinality of " + cobolItem);
        if (values.size() != declaredCount) {
            throw new IllegalStateException("88 " + cobolItem + " holds " + values.size()
                    + " distinct literals but app/cpy/CSLKPCDY.cpy declares " + declaredCount
                    + "; a literal has been lost, duplicated or added, and this lookup would no "
                    + "longer agree with the COBOL. Re-derive the list from the copybook rather "
                    + "than adjusting the expected count");
        }
        return values;
    }

    // Any approximation admits or rejects a code the COBOL does not, and that is a parity diff. DO NOT
    // hand-edit an entry either - re-derive the list from the copybook.

    private static Set<String> buildValidPhoneAreaCode() {
        return orderedSet("VALID-PHONE-AREA-CODE", VALID_PHONE_AREA_CODE_COUNT,
                "201",
                "202",
                "203",
                "204",
                "205",
                "206",
                "207",
                "208",
                "209",
                "210",
                "212",
                "213",
                "214",
                "215",
                "216",
                "217",
                "218",
                "219",
                "220",
                "223",
                "224",
                "225",
                "226",
                "228",
                "229",
                "231",
                "234",
                "236",
                "239",
                "240",
                "242",
                "246",
                "248",
                "249",
                "250",
                "251",
                "252",
                "253",
                "254",
                "256",
                "260",
                "262",
                "264",
                "267",
                "268",
                "269",
                "270",
                "272",
                "276",
                "279",
                "281",
                "284",
                "289",
                "301",
                "302",
                "303",
                "304",
                "305",
                "306",
                "307",
                "308",
                "309",
                "310",
                "312",
                "313",
                "314",
                "315",
                "316",
                "317",
                "318",
                "319",
                "320",
                "321",
                "323",
                "325",
                "326",
                "330",
                "331",
                "332",
                "334",
                "336",
                "337",
                "339",
                "340",
                "341",
                "343",
                "345",
                "346",
                "347",
                "351",
                "352",
                "360",
                "361",
                "364",
                "365",
                "367",
                "368",
                "380",
                "385",
                "386",
                "401",
                "402",
                "403",
                "404",
                "405",
                "406",
                "407",
                "408",
                "409",
                "410",
                "412",
                "413",
                "414",
                "415",
                "416",
                "417",
                "418",
                "419",
                "423",
                "424",
                "425",
                "430",
                "431",
                "432",
                "434",
                "435",
                "437",
                "438",
                "440",
                "441",
                "442",
                "443",
                "445",
                "447",
                "448",
                "450",
                "458",
                "463",
                "464",
                "469",
                "470",
                "473",
                "474",
                "475",
                "478",
                "479",
                "480",
                "484",
                "501",
                "502",
                "503",
                "504",
                "505",
                "506",
                "507",
                "508",
                "509",
                "510",
                "512",
                "513",
                "514",
                "515",
                "516",
                "517",
                "518",
                "519",
                "520",
                "530",
                "531",
                "534",
                "539",
                "540",
                "541",
                "548",
                "551",
                "559",
                "561",
                "562",
                "563",
                "564",
                "567",
                "570",
                "571",
                "572",
                "573",
                "574",
                "575",
                "579",
                "580",
                "581",
                "582",
                "585",
                "586",
                "587",
                "601",
                "602",
                "603",
                "604",
                "605",
                "606",
                "607",
                "608",
                "609",
                "610",
                "612",
                "613",
                "614",
                "615",
                "616",
                "617",
                "618",
                "619",
                "620",
                "623",
                "626",
                "628",
                "629",
                "630",
                "631",
                "636",
                "639",
                "640",
                "641",
                "646",
                "647",
                "649",
                "650",
                "651",
                "656",
                "657",
                "658",
                "659",
                "660",
                "661",
                "662",
                "664",
                "667",
                "669",
                "670",
                "671",
                "672",
                "678",
                "680",
                "681",
                "682",
                "683",
                "684",
                "689",
                "701",
                "702",
                "703",
                "704",
                "705",
                "706",
                "707",
                "708",
                "709",
                "712",
                "713",
                "714",
                "715",
                "716",
                "717",
                "718",
                "719",
                "720",
                "721",
                "724",
                "725",
                "726",
                "727",
                "731",
                "732",
                "734",
                "737",
                "740",
                "742",
                "743",
                "747",
                "753",
                "754",
                "757",
                "758",
                "760",
                "762",
                "763",
                "765",
                "767",
                "769",
                "770",
                "771",
                "772",
                "773",
                "774",
                "775",
                "778",
                "779",
                "780",
                "781",
                "782",
                "784",
                "785",
                "786",
                "787",
                "801",
                "802",
                "803",
                "804",
                "805",
                "806",
                "807",
                "808",
                "809",
                "810",
                "812",
                "813",
                "814",
                "815",
                "816",
                "817",
                "818",
                "819",
                "820",
                "825",
                "826",
                "828",
                "829",
                "830",
                "831",
                "832",
                "838",
                "839",
                "840",
                "843",
                "845",
                "847",
                "848",
                "849",
                "850",
                "854",
                "856",
                "857",
                "858",
                "859",
                "860",
                "862",
                "863",
                "864",
                "865",
                "867",
                "868",
                "869",
                "870",
                "872",
                "873",
                "876",
                "878",
                "901",
                "902",
                "903",
                "904",
                "905",
                "906",
                "907",
                "908",
                "909",
                "910",
                "912",
                "913",
                "914",
                "915",
                "916",
                "917",
                "918",
                "919",
                "920",
                "925",
                "928",
                "929",
                "930",
                "931",
                "934",
                "936",
                "937",
                "938",
                "939",
                "940",
                "941",
                "943",
                "945",
                "947",
                "948",
                "949",
                "951",
                "952",
                "954",
                "956",
                "959",
                "970",
                "971",
                "972",
                "973",
                "978",
                "979",
                "980",
                "983",
                "984",
                "985",
                "986",
                "989",
                "200",
                "211",
                "222",
                "233",
                "244",
                "255",
                "266",
                "277",
                "288",
                "299",
                "300",
                "311",
                "322",
                "333",
                "344",
                "355",
                "366",
                "377",
                "388",
                "399",
                "400",
                "411",
                "422",
                "433",
                "444",
                "455",
                "466",
                "477",
                "488",
                "499",
                "500",
                "511",
                "522",
                "533",
                "544",
                "555",
                "566",
                "577",
                "588",
                "599",
                "600",
                "611",
                "622",
                "633",
                "644",
                "655",
                "666",
                "677",
                "688",
                "699",
                "700",
                "711",
                "722",
                "733",
                "744",
                "755",
                "766",
                "777",
                "788",
                "799",
                "800",
                "811",
                "822",
                "833",
                "844",
                "855",
                "866",
                "877",
                "888",
                "899",
                "900",
                "911",
                "922",
                "933",
                "944",
                "955",
                "966",
                "977",
                "988",
                "999");
    }

    private static Set<String> buildValidGeneralPurpCode() {
        return orderedSet("VALID-GENERAL-PURP-CODE", VALID_GENERAL_PURP_CODE_COUNT,
                "201",
                "202",
                "203",
                "204",
                "205",
                "206",
                "207",
                "208",
                "209",
                "210",
                "212",
                "213",
                "214",
                "215",
                "216",
                "217",
                "218",
                "219",
                "220",
                "223",
                "224",
                "225",
                "226",
                "228",
                "229",
                "231",
                "234",
                "236",
                "239",
                "240",
                "242",
                "246",
                "248",
                "249",
                "250",
                "251",
                "252",
                "253",
                "254",
                "256",
                "260",
                "262",
                "264",
                "267",
                "268",
                "269",
                "270",
                "272",
                "276",
                "279",
                "281",
                "284",
                "289",
                "301",
                "302",
                "303",
                "304",
                "305",
                "306",
                "307",
                "308",
                "309",
                "310",
                "312",
                "313",
                "314",
                "315",
                "316",
                "317",
                "318",
                "319",
                "320",
                "321",
                "323",
                "325",
                "326",
                "330",
                "331",
                "332",
                "334",
                "336",
                "337",
                "339",
                "340",
                "341",
                "343",
                "345",
                "346",
                "347",
                "351",
                "352",
                "360",
                "361",
                "364",
                "365",
                "367",
                "368",
                "380",
                "385",
                "386",
                "401",
                "402",
                "403",
                "404",
                "405",
                "406",
                "407",
                "408",
                "409",
                "410",
                "412",
                "413",
                "414",
                "415",
                "416",
                "417",
                "418",
                "419",
                "423",
                "424",
                "425",
                "430",
                "431",
                "432",
                "434",
                "435",
                "437",
                "438",
                "440",
                "441",
                "442",
                "443",
                "445",
                "447",
                "448",
                "450",
                "458",
                "463",
                "464",
                "469",
                "470",
                "473",
                "474",
                "475",
                "478",
                "479",
                "480",
                "484",
                "501",
                "502",
                "503",
                "504",
                "505",
                "506",
                "507",
                "508",
                "509",
                "510",
                "512",
                "513",
                "514",
                "515",
                "516",
                "517",
                "518",
                "519",
                "520",
                "530",
                "531",
                "534",
                "539",
                "540",
                "541",
                "548",
                "551",
                "559",
                "561",
                "562",
                "563",
                "564",
                "567",
                "570",
                "571",
                "572",
                "573",
                "574",
                "575",
                "579",
                "580",
                "581",
                "582",
                "585",
                "586",
                "587",
                "601",
                "602",
                "603",
                "604",
                "605",
                "606",
                "607",
                "608",
                "609",
                "610",
                "612",
                "613",
                "614",
                "615",
                "616",
                "617",
                "618",
                "619",
                "620",
                "623",
                "626",
                "628",
                "629",
                "630",
                "631",
                "636",
                "639",
                "640",
                "641",
                "646",
                "647",
                "649",
                "650",
                "651",
                "656",
                "657",
                "658",
                "659",
                "660",
                "661",
                "662",
                "664",
                "667",
                "669",
                "670",
                "671",
                "672",
                "678",
                "680",
                "681",
                "682",
                "683",
                "684",
                "689",
                "701",
                "702",
                "703",
                "704",
                "705",
                "706",
                "707",
                "708",
                "709",
                "712",
                "713",
                "714",
                "715",
                "716",
                "717",
                "718",
                "719",
                "720",
                "721",
                "724",
                "725",
                "726",
                "727",
                "731",
                "732",
                "734",
                "737",
                "740",
                "742",
                "743",
                "747",
                "753",
                "754",
                "757",
                "758",
                "760",
                "762",
                "763",
                "765",
                "767",
                "769",
                "770",
                "771",
                "772",
                "773",
                "774",
                "775",
                "778",
                "779",
                "780",
                "781",
                "782",
                "784",
                "785",
                "786",
                "787",
                "801",
                "802",
                "803",
                "804",
                "805",
                "806",
                "807",
                "808",
                "809",
                "810",
                "812",
                "813",
                "814",
                "815",
                "816",
                "817",
                "818",
                "819",
                "820",
                "825",
                "826",
                "828",
                "829",
                "830",
                "831",
                "832",
                "838",
                "839",
                "840",
                "843",
                "845",
                "847",
                "848",
                "849",
                "850",
                "854",
                "856",
                "857",
                "858",
                "859",
                "860",
                "862",
                "863",
                "864",
                "865",
                "867",
                "868",
                "869",
                "870",
                "872",
                "873",
                "876",
                "878",
                "901",
                "902",
                "903",
                "904",
                "905",
                "906",
                "907",
                "908",
                "909",
                "910",
                "912",
                "913",
                "914",
                "915",
                "916",
                "917",
                "918",
                "919",
                "920",
                "925",
                "928",
                "929",
                "930",
                "931",
                "934",
                "936",
                "937",
                "938",
                "939",
                "940",
                "941",
                "943",
                "945",
                "947",
                "948",
                "949",
                "951",
                "952",
                "954",
                "956",
                "959",
                "970",
                "971",
                "972",
                "973",
                "978",
                "979",
                "980",
                "983",
                "984",
                "985",
                "986",
                "989");
    }

    private static Set<String> buildValidEasyRecogAreaCode() {
        return orderedSet("VALID-EASY-RECOG-AREA-CODE", VALID_EASY_RECOG_AREA_CODE_COUNT,
                "200",
                "211",
                "222",
                "233",
                "244",
                "255",
                "266",
                "277",
                "288",
                "299",
                "300",
                "311",
                "322",
                "333",
                "344",
                "355",
                "366",
                "377",
                "388",
                "399",
                "400",
                "411",
                "422",
                "433",
                "444",
                "455",
                "466",
                "477",
                "488",
                "499",
                "500",
                "511",
                "522",
                "533",
                "544",
                "555",
                "566",
                "577",
                "588",
                "599",
                "600",
                "611",
                "622",
                "633",
                "644",
                "655",
                "666",
                "677",
                "688",
                "699",
                "700",
                "711",
                "722",
                "733",
                "744",
                "755",
                "766",
                "777",
                "788",
                "799",
                "800",
                "811",
                "822",
                "833",
                "844",
                "855",
                "866",
                "877",
                "888",
                "899",
                "900",
                "911",
                "922",
                "933",
                "944",
                "955",
                "966",
                "977",
                "988",
                "999");
    }

    private static Set<String> buildValidUsStateCode() {
        return orderedSet("VALID-US-STATE-CODE", VALID_US_STATE_CODE_COUNT,
                "AL",
                "AK",
                "AZ",
                "AR",
                "CA",
                "CO",
                "CT",
                "DE",
                "FL",
                "GA",
                "HI",
                "ID",
                "IL",
                "IN",
                "IA",
                "KS",
                "KY",
                "LA",
                "ME",
                "MD",
                "MA",
                "MI",
                "MN",
                "MS",
                "MO",
                "MT",
                "NE",
                "NV",
                "NH",
                "NJ",
                "NM",
                "NY",
                "NC",
                "ND",
                "OH",
                "OK",
                "OR",
                "PA",
                "RI",
                "SC",
                "SD",
                "TN",
                "TX",
                "UT",
                "VT",
                "VA",
                "WA",
                "WV",
                "WI",
                "WY",
                "DC",
                "AS",
                "GU",
                "MP",
                "PR",
                "VI");
    }

    private static Set<String> buildValidUsStateZipCd2Combo() {
        return orderedSet("VALID-US-STATE-ZIP-CD2-COMBO", VALID_US_STATE_ZIP_CD2_COMBO_COUNT,
                "AA34",
                "AE90",
                "AE91",
                "AE92",
                "AE93",
                "AE94",
                "AE95",
                "AE96",
                "AE97",
                "AE98",
                "AK99",
                "AL35",
                "AL36",
                "AP96",
                "AR71",
                "AR72",
                "AS96",
                "AZ85",
                "AZ86",
                "CA90",
                "CA91",
                "CA92",
                "CA93",
                "CA94",
                "CA95",
                "CA96",
                "CO80",
                "CO81",
                "CT60",
                "CT61",
                "CT62",
                "CT63",
                "CT64",
                "CT65",
                "CT66",
                "CT67",
                "CT68",
                "CT69",
                "DC20",
                "DC56",
                "DC88",
                "DE19",
                "FL32",
                "FL33",
                "FL34",
                "FM96",
                "GA30",
                "GA31",
                "GA39",
                "GU96",
                "HI96",
                "IA50",
                "IA51",
                "IA52",
                "ID83",
                "IL60",
                "IL61",
                "IL62",
                "IN46",
                "IN47",
                "KS66",
                "KS67",
                "KY40",
                "KY41",
                "KY42",
                "LA70",
                "LA71",
                "MA10",
                "MA11",
                "MA12",
                "MA13",
                "MA14",
                "MA15",
                "MA16",
                "MA17",
                "MA18",
                "MA19",
                "MA20",
                "MA21",
                "MA22",
                "MA23",
                "MA24",
                "MA25",
                "MA26",
                "MA27",
                "MA55",
                "MD20",
                "MD21",
                "ME39",
                "ME40",
                "ME41",
                "ME42",
                "ME43",
                "ME44",
                "ME45",
                "ME46",
                "ME47",
                "ME48",
                "ME49",
                "MH96",
                "MI48",
                "MI49",
                "MN55",
                "MN56",
                "MO63",
                "MO64",
                "MO65",
                "MO72",
                "MP96",
                "MS38",
                "MS39",
                "MT59",
                "NC27",
                "NC28",
                "ND58",
                "NE68",
                "NE69",
                "NH30",
                "NH31",
                "NH32",
                "NH33",
                "NH34",
                "NH35",
                "NH36",
                "NH37",
                "NH38",
                "NJ70",
                "NJ71",
                "NJ72",
                "NJ73",
                "NJ74",
                "NJ75",
                "NJ76",
                "NJ77",
                "NJ78",
                "NJ79",
                "NJ80",
                "NJ81",
                "NJ82",
                "NJ83",
                "NJ84",
                "NJ85",
                "NJ86",
                "NJ87",
                "NJ88",
                "NJ89",
                "NM87",
                "NM88",
                "NV88",
                "NV89",
                "NY50",
                "NY54",
                "NY63",
                "NY10",
                "NY11",
                "NY12",
                "NY13",
                "NY14",
                "OH43",
                "OH44",
                "OH45",
                "OK73",
                "OK74",
                "OR97",
                "PA15",
                "PA16",
                "PA17",
                "PA18",
                "PA19",
                "PR60",
                "PR61",
                "PR62",
                "PR63",
                "PR64",
                "PR65",
                "PR66",
                "PR67",
                "PR68",
                "PR69",
                "PR70",
                "PR71",
                "PR72",
                "PR73",
                "PR74",
                "PR75",
                "PR76",
                "PR77",
                "PR78",
                "PR79",
                "PR90",
                "PR91",
                "PR92",
                "PR93",
                "PR94",
                "PR95",
                "PR96",
                "PR97",
                "PR98",
                "PW96",
                "RI28",
                "RI29",
                "SC29",
                "SD57",
                "TN37",
                "TN38",
                "TX73",
                "TX75",
                "TX76",
                "TX77",
                "TX78",
                "TX79",
                "TX88",
                "UT84",
                "VA20",
                "VA22",
                "VA23",
                "VA24",
                "VI80",
                "VI82",
                "VI83",
                "VI84",
                "VI85",
                "VT50",
                "VT51",
                "VT52",
                "VT53",
                "VT54",
                "VT56",
                "VT57",
                "VT58",
                "VT59",
                "WA98",
                "WA99",
                "WI53",
                "WI54",
                "WV24",
                "WV25",
                "WV26",
                "WY82",
                "WY83");
    }
}
