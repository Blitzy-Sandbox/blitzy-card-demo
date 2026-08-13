package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code GET /api/accounts/&#123;acctId&#125;} - CICS transaction {@code CAVW},
 * COBOL program {@code app/cbl/COACTVWC.cbl}, BMS mapset {@code app/bms/COACTVW.bms}.
 *
 * <p>{@code app/bms/COACTVW.bms} declares 100 {@code DFHMDF} entries of which exactly 37 carry a name.
 */
@JsonDeserialize(builder = AccountViewResponse.Builder.class)
public final class AccountViewResponse {
    /**
     * {@code LIT-THISPGM PIC X(8) VALUE 'COACTVWC'}, {@code app/cbl/COACTVWC.cbl:143}.
     */
    public static final String THIS_PROGRAM = "COACTVWC";

    /**
     * {@code LIT-THISTRANID PIC X(4) VALUE 'CAVW'}, {@code app/cbl/COACTVWC.cbl:146-147}.
     */
    public static final String THIS_TRANID = "CAVW";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTVW '}, {@code app/cbl/COACTVWC.cbl:148}.
     */
    public static final String THIS_MAPSET = "COACTVW ";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CACTVWA'}, {@code app/cbl/COACTVWC.cbl:149}.
     */
    public static final String MAP_NAME = "CACTVWA";

    // Naming mirrors AccountViewRequest exactly - &lt;DFHMDF LABEL&gt;_LENGTH, with no O suffix - so the
    // two halves of the CAVW contract can be diffed constant for constant.

    /**
     * {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COACTVW.CPY:248}; {@code LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:254}; {@code LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:260}; {@code LENGTH=8}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:266}; {@code LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COACTVW.CPY:272}; {@code LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COACTVW.CPY:278}; {@code LENGTH=8}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COACTVW.CPY:284}; {@code LENGTH=11}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * {@code ACSTTUSO PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:290}; {@code LENGTH=1}.
     */
    public static final int ACSTTUS_LENGTH = 1;

    /**
     * {@code ADTOPENO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:296}; {@code LENGTH=10}.
     */
    public static final int ADTOPEN_LENGTH = 10;

    /**
     * {@code ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:302}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:120}.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /**
     * {@code AEXPDTO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:308}; {@code LENGTH=10}.
     */
    public static final int AEXPDT_LENGTH = 10;

    /**
     * {@code ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:314}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:141}.
     */
    public static final int ACSHLIM_LENGTH = 15;

    /**
     * {@code AREISDTO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:320}; {@code LENGTH=10}.
     */
    public static final int AREISDT_LENGTH = 10;

    /**
     * {@code ACURBALO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:326}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:162}.
     */
    public static final int ACURBAL_LENGTH = 15;

    /**
     * {@code ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:332}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:174}.
     */
    public static final int ACRCYCR_LENGTH = 15;

    /**
     * {@code AADDGRPO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:338}; {@code LENGTH=10}.
     */
    public static final int AADDGRP_LENGTH = 10;

    /**
     * {@code ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99}, {@code app/cpy-bms/COACTVW.CPY:344}; {@code LENGTH=15},
     * {@code PICOUT='+ZZZ,ZZZ,ZZZ.99'} at {@code app/bms/COACTVW.bms:195}.
     */
    public static final int ACRCYDB_LENGTH = 15;

    /**
     * {@code ACSTNUMO PIC X(9)}, {@code app/cpy-bms/COACTVW.CPY:350}; {@code LENGTH=9}.
     */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * {@code ACSTSSNO PIC X(12)}, {@code app/cpy-bms/COACTVW.CPY:356}; {@code LENGTH=12}.
     */
    public static final int ACSTSSN_LENGTH = 12;

    /**
     * {@code ACSTDOBO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:362}; {@code LENGTH=10}.
     */
    public static final int ACSTDOB_LENGTH = 10;

    /**
     * {@code ACSTFCOO PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:368}; {@code LENGTH=3}.
     */
    public static final int ACSTFCO_LENGTH = 3;

    /**
     * {@code ACSFNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:374}; {@code LENGTH=25}.
     */
    public static final int ACSFNAM_LENGTH = 25;

    /**
     * {@code ACSMNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:380}; {@code LENGTH=25}.
     */
    public static final int ACSMNAM_LENGTH = 25;

    /**
     * {@code ACSLNAMO PIC X(25)}, {@code app/cpy-bms/COACTVW.CPY:386}; {@code LENGTH=25}.
     */
    public static final int ACSLNAM_LENGTH = 25;

    /**
     * {@code ACSADL1O PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:392}; {@code LENGTH=50}.
     */
    public static final int ACSADL1_LENGTH = 50;

    /**
     * {@code ACSSTTEO PIC X(2)}, {@code app/cpy-bms/COACTVW.CPY:398}; {@code LENGTH=2}.
     */
    public static final int ACSSTTE_LENGTH = 2;

    /**
     * {@code ACSADL2O PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:404}; {@code LENGTH=50}.
     */
    public static final int ACSADL2_LENGTH = 50;

    /**
     * {@code ACSZIPCO PIC X(5)}, {@code app/cpy-bms/COACTVW.CPY:410}; {@code LENGTH=5},
     * {@code JUSTIFY=(RIGHT)} at {@code app/bms/COACTVW.bms:291}.
     */
    public static final int ACSZIPC_LENGTH = 5;

    /**
     * {@code ACSCITYO PIC X(50)}, {@code app/cpy-bms/COACTVW.CPY:416}; {@code LENGTH=50}.
     */
    public static final int ACSCITY_LENGTH = 50;

    /**
     * {@code ACSCTRYO PIC X(3)}, {@code app/cpy-bms/COACTVW.CPY:422}; {@code LENGTH=3}.
     */
    public static final int ACSCTRY_LENGTH = 3;

    /**
     * {@code ACSPHN1O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:428}; {@code LENGTH=13}.
     */
    public static final int ACSPHN1_LENGTH = 13;

    /**
     * {@code ACSGOVTO PIC X(20)}, {@code app/cpy-bms/COACTVW.CPY:434}; {@code LENGTH=20}.
     */
    public static final int ACSGOVT_LENGTH = 20;

    /**
     * {@code ACSPHN2O PIC X(13)}, {@code app/cpy-bms/COACTVW.CPY:440}; {@code LENGTH=13}.
     */
    public static final int ACSPHN2_LENGTH = 13;

    /**
     * {@code ACSEFTCO PIC X(10)}, {@code app/cpy-bms/COACTVW.CPY:446}; {@code LENGTH=10}.
     */
    public static final int ACSEFTC_LENGTH = 10;

    /**
     * {@code ACSPFLGO PIC X(1)}, {@code app/cpy-bms/COACTVW.CPY:452}; {@code LENGTH=1}.
     */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * {@code INFOMSGO PIC X(45)}, {@code app/cpy-bms/COACTVW.CPY:458}; {@code LENGTH=45},
     * {@code ATTRB=(PROT), COLOR=NEUTRAL, HILIGHT=OFF} at {@code app/bms/COACTVW.bms:356}. 45, the map
     * width - not the 40 of {@code WS-INFO-MSG}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COACTVW.CPY:464}; {@code LENGTH=78},
     * {@code ATTRB=(ASKIP,BRT,FSET), COLOR=RED} at {@code app/bms/COACTVW.bms:365}. 78, the map width - not
     * the 75 of {@code WS-RETURN-MSG}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * The {@value #FIELD_COUNT} name-labelled {@code DFHMDF} entries of {@code app/bms/COACTVW.bms}.
     */
    public static final int FIELD_COUNT = 37;

    /**
     * The {@code 02 FILLER PIC X(12)} that opens the group at {@code app/cpy-bms/COACTVW.CPY:242}.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that opens every field's overhead in {@code CACTVWAO}.
     */
    public static final int FILLER_LENGTH = 3;

    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_ITEMS_PER_FIELD = 4;

    /**
     * The suffix of a programmed-symbols item name, as {@code app/cpy-bms/COACTVW.CPY} spells it.
     */
    public static final String PS_ITEM_SUFFIX = "P";

    public static final String HILIGHT_ITEM_SUFFIX = "H";

    public static final String VALIDN_ITEM_SUFFIX = "V";

    public static final int FIELD_OVERHEAD =
            FILLER_LENGTH + ATTRIBUTE_ITEMS_PER_FIELD * ATTRIBUTE_ITEM_LENGTH;

    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + ACSTTUS_LENGTH
            + ADTOPEN_LENGTH + ACRDLIM_LENGTH + AEXPDT_LENGTH + ACSHLIM_LENGTH + AREISDT_LENGTH
            + ACURBAL_LENGTH + ACRCYCR_LENGTH + AADDGRP_LENGTH + ACRCYDB_LENGTH + ACSTNUM_LENGTH
            + ACSTSSN_LENGTH + ACSTDOB_LENGTH + ACSTFCO_LENGTH + ACSFNAM_LENGTH + ACSMNAM_LENGTH
            + ACSLNAM_LENGTH + ACSADL1_LENGTH + ACSSTTE_LENGTH + ACSADL2_LENGTH + ACSZIPC_LENGTH
            + ACSCITY_LENGTH + ACSCTRY_LENGTH + ACSPHN1_LENGTH + ACSGOVT_LENGTH + ACSPHN2_LENGTH
            + ACSEFTC_LENGTH + ACSPFLG_LENGTH + INFOMSG_LENGTH + ERRMSG_LENGTH;

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    // Widths are taken from CardScreenState rather than restated, because these three values are exactly
    // what CVCRD01Y's CCARD-NEXT-PROG, CCARD-NEXT-MAPSET and CCARD-NEXT-MAP hold, and one declaration of a
    // width is always better than two that can drift apart.

    /**
     * {@code CCARD-NEXT-PROG PIC X(8)} - the {@code XCTL} target program name.
     */
    public static final int NEXT_PROGRAM_LENGTH = CardScreenState.CCARD_NEXT_PROG_LENGTH;

    /**
     * {@code CCARD-NEXT-MAPSET PIC X(7)} - see {@link #THIS_MAPSET} for why this is 7 and not 8.
     */
    public static final int NEXT_MAPSET_LENGTH = CardScreenState.CCARD_NEXT_MAPSET_LENGTH;

    /**
     * {@code CCARD-NEXT-MAP PIC X(7)} - the map name, {@link #MAP_NAME} for this screen.
     */
    public static final int NEXT_MAP_LENGTH = CardScreenState.CCARD_NEXT_MAP_LENGTH;

    /**
     * {@code WS-RETURN-MSG PIC X(75)}, {@code app/cbl/COACTVWC.cbl:117}.
     */
    public static final int WS_RETURN_MSG_LENGTH = CardScreenState.CCARD_ERROR_MSG_LENGTH;

    /**
     * {@code WS-INFO-MSG PIC X(40)}, {@code app/cbl/COACTVWC.cbl:110}, with
     * {@code 88 WS-NO-INFO-MESSAGE VALUES SPACES LOW-VALUES}.
     */
    public static final int WS_INFO_MSG_LENGTH = 40;

    /**
     * Spaces {@code WS-RETURN-MSG} gains on the right when moved into {@code ERRMSGO}:
     * {@value #ERRMSG_LENGTH} - {@link #WS_RETURN_MSG_LENGTH} = 3.
     */
    public static final int ERRMSG_RETURN_MESSAGE_PADDING = ERRMSG_LENGTH - WS_RETURN_MSG_LENGTH;

    /**
     * Spaces {@code WS-INFO-MSG} gains on the right when moved into {@code INFOMSGO}:
     * {@value #INFOMSG_LENGTH} - {@value #WS_INFO_MSG_LENGTH} = 5.
     */
    public static final int INFOMSG_INFO_MESSAGE_PADDING = INFOMSG_LENGTH - WS_INFO_MSG_LENGTH;

    public static final int ERRMSG_STANDARD_MESSAGE_PADDING =
            ERRMSG_LENGTH - SystemMessages.MESSAGE_LENGTH;

    public static final int INFOMSG_STANDARD_MESSAGE_TRUNCATION =
            SystemMessages.MESSAGE_LENGTH - INFOMSG_LENGTH;

    /**
     * Characters the hyphenating {@code STRING} writes into {@code ACSTSSNO}: 3 + 1 + 2 + 1 + 4 = 11, of
     * {@value #ACSTSSN_LENGTH} declared.
     */
    public static final int ACSTSSN_STRING_LENGTH = 11;

    /**
     * The separator the {@code STRING} at {@code app/cbl/COACTVWC.cbl:497-503} inserts twice.
     */
    public static final String SSN_GROUP_SEPARATOR = "-";

    /**
     * {@code CUST-SSN PIC 9(09)} of {@code app/cpy/CVCUS01Y.cpy} - 9 characters.
     */
    public static final int SSN_LENGTH = 9;

    /**
     * The whole-field marker {@code app/cbl/COACTVWC.cbl:563} moves into {@code ACCTSIDO} when the filter
     * is blank on re-entry: {@link FieldAttributeSetter#ASTERISK}.
     */
    public static final String BLANK_FIELD_MARKER = FieldAttributeSetter.ASTERISK;

    /**
     * The mask exactly as the copybook and the mapset write it.
     */
    public static final String AMOUNT_PICTURE = "+ZZZ,ZZZ,ZZZ.99";

    public static final int AMOUNT_MASK_WIDTH = 15;

    /**
     * Integer digit positions the mask provides: 9.
     */
    public static final int AMOUNT_INTEGER_POSITIONS = 9;

    public static final int AMOUNT_FRACTION_DIGITS = 2;

    public static final int AMOUNT_GROUP_SIZE = 3;

    public static final int AMOUNT_DIGIT_POSITIONS = AMOUNT_INTEGER_POSITIONS + AMOUNT_FRACTION_DIGITS;

    /**
     * Integer digits each source field declares: {@code PIC S9(10)V99} - 10.
     */
    public static final int SOURCE_INTEGER_DIGITS = 10;

    public static final int AMOUNT_MASK_FIELD_COUNT = 5;

    private static final FixedWidthCodec PIC_X_CODEC = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final byte LOW_VALUES_BYTE = 0x00;

    private static final char AMOUNT_SIGN_POSITIVE = '+';

    private static final char AMOUNT_SIGN_NEGATIVE = '-';

    private static final char AMOUNT_GROUP_SEPARATOR = ',';

    private static final char AMOUNT_DECIMAL_POINT = '.';

    private static final char AMOUNT_SUPPRESSION_CHARACTER = ' ';

    private static final char ZERO_DIGIT = '0';

    static {
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each CACTVWAO field carries 3 + 4 = 7 bytes of FILLER and "
                    + "attribute storage before its data - the same 7 as CACTVWAI's 2 + 1 + 4, which is "
                    + "what makes the REDEFINES line up - but FIELD_OVERHEAD computes to "
                    + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 684) {
            throw new IllegalStateException("The 37 xxxO PICTURE widths of app/cpy-bms/COACTVW.CPY sum "
                    + "to 684, which is also the sum of the 37 LENGTH= operands of "
                    + "app/bms/COACTVW.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 955) {
            throw new IllegalStateException("The CACTVWAO group is 12 + 37 * 7 + 684 = 955 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        if (AMOUNT_MASK_WIDTH != 1 + AMOUNT_INTEGER_POSITIONS + 2 + 1 + AMOUNT_FRACTION_DIGITS) {
            throw new IllegalStateException("PIC " + AMOUNT_PICTURE + " occupies a sign, "
                    + AMOUNT_INTEGER_POSITIONS + " digit positions, 2 group separators, a decimal point "
                    + "and " + AMOUNT_FRACTION_DIGITS + " forced digits, which is "
                    + (1 + AMOUNT_INTEGER_POSITIONS + 2 + 1 + AMOUNT_FRACTION_DIGITS)
                    + " characters, not the declared " + AMOUNT_MASK_WIDTH);
        }
        verifyFieldStrides();
        verifyMaskedFieldWidths();
    }

    private static void verifyFieldStrides() {
        ScreenField[] fields = ScreenField.values();
        if (fields.length != FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COACTVW.bms carries " + FIELD_COUNT
                    + " name-labelled DFHMDF entries, so ScreenField must declare " + FIELD_COUNT
                    + " constants, but it declares " + fields.length);
        }
        int cursor = TIOAPFX_LENGTH;
        int widths = 0;
        for (ScreenField field : fields) {
            if (field.fillerOffset() != cursor) {
                throw new IllegalStateException("Field " + field.label() + " declares its data at offset "
                        + field.dataOffset() + ", which places its FILLER at " + field.fillerOffset()
                        + "; the preceding storage ends at " + cursor + ", so the CACTVWAO group would "
                        + "have a gap or an overlap there");
            }
            cursor = field.endOffsetExclusive();
            widths += field.length();
        }
        if (widths != PAYLOAD_LENGTH) {
            throw new IllegalStateException("The " + FIELD_COUNT + " ScreenField widths sum to " + widths
                    + " but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("Walking the " + FIELD_COUNT + " field strides from the end "
                    + "of the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix ends at " + cursor
                    + ", not at the declared group length of " + GROUP_LENGTH);
        }
    }

    private static void verifyMaskedFieldWidths() {
        int masked = 0;
        for (ScreenField field : ScreenField.values()) {
            if (!field.isNumericEdited()) {
                continue;
            }
            masked++;
            if (field.length() != AMOUNT_MASK_WIDTH) {
                throw new IllegalStateException("Field " + field.label() + " is declared PIC "
                        + field.picture() + ", which occupies " + AMOUNT_MASK_WIDTH
                        + " characters, but its width constant says " + field.length());
            }
        }
        if (masked != AMOUNT_MASK_FIELD_COUNT) {
            throw new IllegalStateException("app/cpy-bms/COACTVW.CPY declares PIC " + AMOUNT_PICTURE
                    + " on exactly " + AMOUNT_MASK_FIELD_COUNT + " items and app/bms/COACTVW.bms "
                    + "declares PICOUT on exactly " + AMOUNT_MASK_FIELD_COUNT + " entries, but "
                    + masked + " ScreenField constants report themselves numeric-edited");
        }
    }

    // Every member is a final String holding an image at its declared width - Builder.build() applies the
    // PIC X move rule once, on store, because a COBOL MOVE into an xxxO output item pads a short sending
    // value and truncates a long one at the moment of the move.

    @JsonProperty("trnname")
    @Size(max = TRNNAME_LENGTH, message = "TRNNAME is TRNNAMEO PIC X(4) at "
            + "app/cpy-bms/COACTVW.CPY:248 and holds at most 4 characters")
    private final String trnname;

    @JsonProperty("title01")
    @Size(max = TITLE01_LENGTH, message = "TITLE01 is TITLE01O PIC X(40) at "
            + "app/cpy-bms/COACTVW.CPY:254 and holds at most 40 characters")
    private final String title01;

    @JsonProperty("curdate")
    @Size(max = CURDATE_LENGTH, message = "CURDATE is CURDATEO PIC X(8) at "
            + "app/cpy-bms/COACTVW.CPY:260 and holds at most 8 characters")
    private final String curdate;

    @JsonProperty("pgmname")
    @Size(max = PGMNAME_LENGTH, message = "PGMNAME is PGMNAMEO PIC X(8) at "
            + "app/cpy-bms/COACTVW.CPY:266 and holds at most 8 characters")
    private final String pgmname;

    @JsonProperty("title02")
    @Size(max = TITLE02_LENGTH, message = "TITLE02 is TITLE02O PIC X(40) at "
            + "app/cpy-bms/COACTVW.CPY:272 and holds at most 40 characters")
    private final String title02;

    @JsonProperty("curtime")
    @Size(max = CURTIME_LENGTH, message = "CURTIME is CURTIMEO PIC X(8) at "
            + "app/cpy-bms/COACTVW.CPY:278 and holds at most 8 characters")
    private final String curtime;

    @JsonProperty("acctsid")
    @Size(max = ACCTSID_LENGTH, message = "ACCTSID is ACCTSIDO PIC X(11) at "
            + "app/cpy-bms/COACTVW.CPY:284 and holds at most 11 characters")
    private final String acctsid;

    @JsonProperty("acsttus")
    @Size(max = ACSTTUS_LENGTH, message = "ACSTTUS is ACSTTUSO PIC X(1) at "
            + "app/cpy-bms/COACTVW.CPY:290 and holds at most 1 character")
    private final String acsttus;

    @JsonProperty("adtopen")
    @Size(max = ADTOPEN_LENGTH, message = "ADTOPEN is ADTOPENO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:296 and holds at most 10 characters")
    private final String adtopen;

    @JsonProperty("acrdlim")
    @Size(max = ACRDLIM_LENGTH, message = "ACRDLIM is ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:302 and holds at most 15 characters")
    private final String acrdlim;

    @JsonProperty("aexpdt")
    @Size(max = AEXPDT_LENGTH, message = "AEXPDT is AEXPDTO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:308 and holds at most 10 characters")
    private final String aexpdt;

    @JsonProperty("acshlim")
    @Size(max = ACSHLIM_LENGTH, message = "ACSHLIM is ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:314 and holds at most 15 characters")
    private final String acshlim;

    @JsonProperty("areisdt")
    @Size(max = AREISDT_LENGTH, message = "AREISDT is AREISDTO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:320 and holds at most 10 characters")
    private final String areisdt;

    @JsonProperty("acurbal")
    @Size(max = ACURBAL_LENGTH, message = "ACURBAL is ACURBALO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:326 and holds at most 15 characters")
    private final String acurbal;

    @JsonProperty("acrcycr")
    @Size(max = ACRCYCR_LENGTH, message = "ACRCYCR is ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:332 and holds at most 15 characters")
    private final String acrcycr;

    @JsonProperty("aaddgrp")
    @Size(max = AADDGRP_LENGTH, message = "AADDGRP is AADDGRPO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:338 and holds at most 10 characters")
    private final String aaddgrp;

    @JsonProperty("acrcydb")
    @Size(max = ACRCYDB_LENGTH, message = "ACRCYDB is ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99 at "
            + "app/cpy-bms/COACTVW.CPY:344 and holds at most 15 characters")
    private final String acrcydb;

    @JsonProperty("acstnum")
    @Size(max = ACSTNUM_LENGTH, message = "ACSTNUM is ACSTNUMO PIC X(9) at "
            + "app/cpy-bms/COACTVW.CPY:350 and holds at most 9 characters")
    private final String acstnum;

    @JsonProperty("acstssn")
    @Size(max = ACSTSSN_LENGTH, message = "ACSTSSN is ACSTSSNO PIC X(12) at "
            + "app/cpy-bms/COACTVW.CPY:356 and holds at most 12 characters")
    private final String acstssn;

    @JsonProperty("acstdob")
    @Size(max = ACSTDOB_LENGTH, message = "ACSTDOB is ACSTDOBO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:362 and holds at most 10 characters")
    private final String acstdob;

    @JsonProperty("acstfco")
    @Size(max = ACSTFCO_LENGTH, message = "ACSTFCO is ACSTFCOO PIC X(3) at "
            + "app/cpy-bms/COACTVW.CPY:368 and holds at most 3 characters")
    private final String acstfco;

    @JsonProperty("acsfnam")
    @Size(max = ACSFNAM_LENGTH, message = "ACSFNAM is ACSFNAMO PIC X(25) at "
            + "app/cpy-bms/COACTVW.CPY:374 and holds at most 25 characters")
    private final String acsfnam;

    @JsonProperty("acsmnam")
    @Size(max = ACSMNAM_LENGTH, message = "ACSMNAM is ACSMNAMO PIC X(25) at "
            + "app/cpy-bms/COACTVW.CPY:380 and holds at most 25 characters")
    private final String acsmnam;

    @JsonProperty("acslnam")
    @Size(max = ACSLNAM_LENGTH, message = "ACSLNAM is ACSLNAMO PIC X(25) at "
            + "app/cpy-bms/COACTVW.CPY:386 and holds at most 25 characters")
    private final String acslnam;

    @JsonProperty("acsadl1")
    @Size(max = ACSADL1_LENGTH, message = "ACSADL1 is ACSADL1O PIC X(50) at "
            + "app/cpy-bms/COACTVW.CPY:392 and holds at most 50 characters")
    private final String acsadl1;

    @JsonProperty("acsstte")
    @Size(max = ACSSTTE_LENGTH, message = "ACSSTTE is ACSSTTEO PIC X(2) at "
            + "app/cpy-bms/COACTVW.CPY:398 and holds at most 2 characters")
    private final String acsstte;

    @JsonProperty("acsadl2")
    @Size(max = ACSADL2_LENGTH, message = "ACSADL2 is ACSADL2O PIC X(50) at "
            + "app/cpy-bms/COACTVW.CPY:404 and holds at most 50 characters")
    private final String acsadl2;

    @JsonProperty("acszipc")
    @Size(max = ACSZIPC_LENGTH, message = "ACSZIPC is ACSZIPCO PIC X(5) at "
            + "app/cpy-bms/COACTVW.CPY:410 and holds at most 5 characters")
    private final String acszipc;

    @JsonProperty("acscity")
    @Size(max = ACSCITY_LENGTH, message = "ACSCITY is ACSCITYO PIC X(50) at "
            + "app/cpy-bms/COACTVW.CPY:416 and holds at most 50 characters")
    private final String acscity;

    @JsonProperty("acsctry")
    @Size(max = ACSCTRY_LENGTH, message = "ACSCTRY is ACSCTRYO PIC X(3) at "
            + "app/cpy-bms/COACTVW.CPY:422 and holds at most 3 characters")
    private final String acsctry;

    @JsonProperty("acsphn1")
    @Size(max = ACSPHN1_LENGTH, message = "ACSPHN1 is ACSPHN1O PIC X(13) at "
            + "app/cpy-bms/COACTVW.CPY:428 and holds at most 13 characters")
    private final String acsphn1;

    @JsonProperty("acsgovt")
    @Size(max = ACSGOVT_LENGTH, message = "ACSGOVT is ACSGOVTO PIC X(20) at "
            + "app/cpy-bms/COACTVW.CPY:434 and holds at most 20 characters")
    private final String acsgovt;

    @JsonProperty("acsphn2")
    @Size(max = ACSPHN2_LENGTH, message = "ACSPHN2 is ACSPHN2O PIC X(13) at "
            + "app/cpy-bms/COACTVW.CPY:440 and holds at most 13 characters")
    private final String acsphn2;

    @JsonProperty("acseftc")
    @Size(max = ACSEFTC_LENGTH, message = "ACSEFTC is ACSEFTCO PIC X(10) at "
            + "app/cpy-bms/COACTVW.CPY:446 and holds at most 10 characters")
    private final String acseftc;

    @JsonProperty("acspflg")
    @Size(max = ACSPFLG_LENGTH, message = "ACSPFLG is ACSPFLGO PIC X(1) at "
            + "app/cpy-bms/COACTVW.CPY:452 and holds at most 1 character")
    private final String acspflg;

    @JsonProperty("infomsg")
    @Size(max = INFOMSG_LENGTH, message = "INFOMSG is INFOMSGO PIC X(45) at "
            + "app/cpy-bms/COACTVW.CPY:458 and holds at most 45 characters")
    private final String infomsg;

    @JsonProperty("errmsg")
    @Size(max = ERRMSG_LENGTH, message = "ERRMSG is ERRMSGO PIC X(78) at "
            + "app/cpy-bms/COACTVW.CPY:464 and holds at most 78 characters")
    private final String errmsg;

    @JsonProperty("nextProgram")
    @Size(max = NEXT_PROGRAM_LENGTH, message = "nextProgram is CCARD-NEXT-PROG PIC X(8) and holds at "
            + "most 8 characters")
    private final String nextProgram;

    @JsonProperty("nextMapset")
    @Size(max = NEXT_MAPSET_LENGTH, message = "nextMapset is CCARD-NEXT-MAPSET PIC X(7) and holds at "
            + "most 7 characters")
    private final String nextMapset;

    @JsonProperty("nextMap")
    @Size(max = NEXT_MAP_LENGTH, message = "nextMap is CCARD-NEXT-MAP PIC X(7) and holds at most 7 "
            + "characters")
    private final String nextMap;

    // Imported, never re-declared: CVCRD01Y is owned by card/dto as CardScreenState and CARDDEMO-COMMAREA
    // by common as NavigationContext, one Java type per copybook.

    @JsonProperty("cardScreenState")
    private final CardScreenState cardScreenState;

    @JsonProperty("navigationContext")
    private final NavigationContext navigationContext;

    @JsonIgnore
    private final Map<ScreenField, FieldAttributes> attributes;

    // The only route in is Builder, so there is exactly one place where a value can be stored and exactly
    // one place where the PIC X move rule is applied on store.

    private AccountViewResponse(Builder builder) {
        this.trnname = builder.trnname;
        this.title01 = builder.title01;
        this.curdate = builder.curdate;
        this.pgmname = builder.pgmname;
        this.title02 = builder.title02;
        this.curtime = builder.curtime;
        this.acctsid = builder.acctsid;
        this.acsttus = builder.acsttus;
        this.adtopen = builder.adtopen;
        this.acrdlim = builder.acrdlim;
        this.aexpdt = builder.aexpdt;
        this.acshlim = builder.acshlim;
        this.areisdt = builder.areisdt;
        this.acurbal = builder.acurbal;
        this.acrcycr = builder.acrcycr;
        this.aaddgrp = builder.aaddgrp;
        this.acrcydb = builder.acrcydb;
        this.acstnum = builder.acstnum;
        this.acstssn = builder.acstssn;
        this.acstdob = builder.acstdob;
        this.acstfco = builder.acstfco;
        this.acsfnam = builder.acsfnam;
        this.acsmnam = builder.acsmnam;
        this.acslnam = builder.acslnam;
        this.acsadl1 = builder.acsadl1;
        this.acsstte = builder.acsstte;
        this.acsadl2 = builder.acsadl2;
        this.acszipc = builder.acszipc;
        this.acscity = builder.acscity;
        this.acsctry = builder.acsctry;
        this.acsphn1 = builder.acsphn1;
        this.acsgovt = builder.acsgovt;
        this.acsphn2 = builder.acsphn2;
        this.acseftc = builder.acseftc;
        this.acspflg = builder.acspflg;
        this.infomsg = builder.infomsg;
        this.errmsg = builder.errmsg;
        this.nextProgram = builder.nextProgram;
        this.nextMapset = builder.nextMapset;
        this.nextMap = builder.nextMap;
        this.cardScreenState = new CardScreenState(builder.cardScreenState);
        this.navigationContext = builder.navigationContext;
        this.attributes = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            this.attributes.put(field, new FieldAttributes(builder.attributes.get(field)));
        }
    }

    /**
     * A new builder in the state {@code MOVE LOW-VALUES TO CACTVWAO} leaves the group in.
     *
     * @return a builder whose {@value #FIELD_COUNT} fields are {@code LOW-VALUES} at their declared widths;
     *     never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The payload exactly as {@code 1100-SCREEN-INIT} leaves it: the whole group at {@code LOW-VALUES}.
     *
     * @return a payload holding {@code LOW-VALUES} throughout, with a freshly initialised work area and an
     *     empty commarea; never {@code null}
     */
    public static AccountViewResponse initialGroup() {
        return builder().build();
    }

    /**
     * A builder pre-loaded with everything this payload holds, for deriving a changed copy.
     *
     * @return a builder that would rebuild an equal payload; never {@code null}
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, value(field));
            builder.attributes.put(field, new FieldAttributes(attributes.get(field)));
        }
        return builder.nextProgram(nextProgram)
                .nextMapset(nextMapset)
                .nextMap(nextMap)
                .cardScreenState(cardScreenState)
                .navigationContext(navigationContext);
    }

    /**
     * Renders an amount exactly as {@code MOVE <source> TO <xxxO>} renders it into a
     * {@code PIC +ZZZ,ZZZ,ZZZ.99} receiver: {@value #AMOUNT_MASK_WIDTH} characters, always.
     *
     * @param amount the sending value, at any scale; scaled to {@value CobolDecimal#MONETARY_SCALE} here
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters, never {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}; COBOL has no absent numeric, and a
     *     field the program never wrote holds {@code LOW-VALUES}
     */
    public static String editAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "An amount is required to render PIC " + AMOUNT_PICTURE
                + "; a money field the program never wrote holds LOW-VALUES, which is stored as an image "
                + "rather than rendered from a number");

        BigDecimal stored = CobolDecimal.storeMonetary(amount);
        boolean negative = stored.signum() < 0;
        String digits = alignToMask(stored.abs().unscaledValue().toString());
        String integerDigits = digits.substring(0, AMOUNT_INTEGER_POSITIONS);
        String fractionDigits = digits.substring(AMOUNT_INTEGER_POSITIONS);

        int firstSignificant = AMOUNT_INTEGER_POSITIONS;
        for (int position = 0; position < AMOUNT_INTEGER_POSITIONS; position++) {
            if (integerDigits.charAt(position) != ZERO_DIGIT) {
                firstSignificant = position;
                break;
            }
        }

        StringBuilder edited = new StringBuilder(AMOUNT_MASK_WIDTH);
        edited.append(negative ? AMOUNT_SIGN_NEGATIVE : AMOUNT_SIGN_POSITIVE);
        for (int position = 0; position < AMOUNT_INTEGER_POSITIONS; position++) {
            if (position > 0 && position % AMOUNT_GROUP_SIZE == 0) {
                edited.append(firstSignificant >= position
                        ? AMOUNT_SUPPRESSION_CHARACTER
                        : AMOUNT_GROUP_SEPARATOR);
            }
            edited.append(position < firstSignificant
                    ? AMOUNT_SUPPRESSION_CHARACTER
                    : integerDigits.charAt(position));
        }
        return edited.append(AMOUNT_DECIMAL_POINT).append(fractionDigits).toString();
    }

    private static String alignToMask(String magnitude) {
        int carried = magnitude.length();
        if (carried < AMOUNT_DIGIT_POSITIONS) {
            return String.valueOf(ZERO_DIGIT).repeat(AMOUNT_DIGIT_POSITIONS - carried) + magnitude;
        }
        if (carried > AMOUNT_DIGIT_POSITIONS) {
            return magnitude.substring(carried - AMOUNT_DIGIT_POSITIONS);
        }
        return magnitude;
    }

    // A PIC X field's trailing spaces are part of its value and the parity differ compares them, so
    // trimming here would discard bytes the gate exists to check.

    public String getTrnname() {
        return trnname;
    }

    public String getTitle01() {
        return title01;
    }

    public String getCurdate() {
        return curdate;
    }

    public String getPgmname() {
        return pgmname;
    }

    public String getTitle02() {
        return title02;
    }

    public String getCurtime() {
        return curtime;
    }

    /**
     * {@code ACCTSIDO} - the account number, or {@code LOW-VALUES}, or {@code '*'} and ten spaces.
     *
     * @return {@value #ACCTSID_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcctsid() {
        return acctsid;
    }

    /**
     * {@code ACSTTUSO} - {@code ACCT-ACTIVE-STATUS}.
     *
     * @return {@value #ACSTTUS_LENGTH} character, untrimmed; never {@code null}
     */
    public String getAcsttus() {
        return acsttus;
    }

    /**
     * {@code ADTOPENO} - {@code ACCT-OPEN-DATE}.
     *
     * @return {@value #ADTOPEN_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAdtopen() {
        return adtopen;
    }

    /**
     * {@code ACRDLIMO} - {@code ACCT-CREDIT-LIMIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACRDLIM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcrdlim() {
        return acrdlim;
    }

    /**
     * {@code AEXPDTO} - {@code ACCT-EXPIRAION-DATE}, spelled as the copybook spells it.
     *
     * @return {@value #AEXPDT_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAexpdt() {
        return aexpdt;
    }

    /**
     * {@code ACSHLIMO} - {@code ACCT-CASH-CREDIT-LIMIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACSHLIM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcshlim() {
        return acshlim;
    }

    /**
     * {@code AREISDTO} - {@code ACCT-REISSUE-DATE}.
     *
     * @return {@value #AREISDT_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAreisdt() {
        return areisdt;
    }

    /**
     * {@code ACURBALO} - {@code ACCT-CURR-BAL} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACURBAL_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcurbal() {
        return acurbal;
    }

    /**
     * {@code ACRCYCRO} - {@code ACCT-CURR-CYC-CREDIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACRCYCR_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcrcycr() {
        return acrcycr;
    }

    /**
     * {@code AADDGRPO} - {@code ACCT-GROUP-ID}.
     *
     * @return {@value #AADDGRP_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAaddgrp() {
        return aaddgrp;
    }

    /**
     * {@code ACRCYDBO} - {@code ACCT-CURR-CYC-DEBIT} rendered through {@link #editAmount(BigDecimal)}.
     *
     * @return {@value #ACRCYDB_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcrcydb() {
        return acrcydb;
    }

    /**
     * {@code ACSTNUMO} - {@code CUST-ID}.
     *
     * @return {@value #ACSTNUM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcstnum() {
        return acstnum;
    }

    public String getAcstssn() {
        return acstssn;
    }

    public String getAcstdob() {
        return acstdob;
    }

    /**
     * {@code ACSTFCOO} - {@code CUST-FICO-CREDIT-SCORE}.
     *
     * @return {@value #ACSTFCO_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcstfco() {
        return acstfco;
    }

    /**
     * {@code ACSFNAMO} - {@code CUST-FIRST-NAME}.
     *
     * @return {@value #ACSFNAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsfnam() {
        return acsfnam;
    }

    /**
     * {@code ACSMNAMO} - {@code CUST-MIDDLE-NAME}.
     *
     * @return {@value #ACSMNAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsmnam() {
        return acsmnam;
    }

    /**
     * {@code ACSLNAMO} - {@code CUST-LAST-NAME}.
     *
     * @return {@value #ACSLNAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcslnam() {
        return acslnam;
    }

    /**
     * {@code ACSADL1O} - {@code CUST-ADDR-LINE-1}.
     *
     * @return {@value #ACSADL1_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsadl1() {
        return acsadl1;
    }

    /**
     * {@code ACSSTTEO} - {@code CUST-ADDR-STATE-CD}.
     *
     * @return {@value #ACSSTTE_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsstte() {
        return acsstte;
    }

    /**
     * {@code ACSADL2O} - {@code CUST-ADDR-LINE-2}.
     *
     * @return {@value #ACSADL2_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsadl2() {
        return acsadl2;
    }

    /**
     * {@code ACSZIPCO} - the first five characters of {@code CUST-ADDR-ZIP}.
     *
     * @return {@value #ACSZIPC_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcszipc() {
        return acszipc;
    }

    /**
     * {@code ACSCITYO} - {@code CUST-ADDR-LINE-3}, not a city field; see the member's documentation.
     *
     * @return {@value #ACSCITY_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcscity() {
        return acscity;
    }

    /**
     * {@code ACSCTRYO} - {@code CUST-ADDR-COUNTRY-CD}.
     *
     * @return {@value #ACSCTRY_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsctry() {
        return acsctry;
    }

    /**
     * {@code ACSPHN1O} - the first thirteen characters of {@code CUST-PHONE-NUM-1}.
     *
     * @return {@value #ACSPHN1_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsphn1() {
        return acsphn1;
    }

    public String getAcsgovt() {
        return acsgovt;
    }

    /**
     * {@code ACSPHN2O} - the first thirteen characters of {@code CUST-PHONE-NUM-2}.
     *
     * @return {@value #ACSPHN2_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcsphn2() {
        return acsphn2;
    }

    /**
     * {@code ACSEFTCO} - {@code CUST-EFT-ACCOUNT-ID}.
     *
     * @return {@value #ACSEFTC_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getAcseftc() {
        return acseftc;
    }

    /**
     * {@code ACSPFLGO} - {@code CUST-PRI-CARD-HOLDER-IND}.
     *
     * @return {@value #ACSPFLG_LENGTH} character, untrimmed; never {@code null}
     */
    public String getAcspflg() {
        return acspflg;
    }

    /**
     * {@code INFOMSGO} - the information line; its visibility lives in {@code INFOMSGC}.
     *
     * @return {@value #INFOMSG_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getInfomsg() {
        return infomsg;
    }

    /**
     * {@code ERRMSGO} - the error line, also carried on the work area.
     *
     * @return {@value #ERRMSG_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * The {@code XCTL} target program the client should call next.
     *
     * @return {@link #NEXT_PROGRAM_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * The mapset of the next screen.
     *
     * @return {@link #NEXT_MAPSET_LENGTH} characters, untrimmed; never {@code null}
     */
    public String getNextMapset() {
        return nextMapset;
    }

    public String getNextMap() {
        return nextMap;
    }

    /**
     * The {@code CVCRD01Y} work area, as an independent copy.
     *
     * @return a copy of the work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * The {@code CARDDEMO-COMMAREA} the client must send back.
     *
     * @return the commarea; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a field value");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACCTSID -> acctsid;
            case ACSTTUS -> acsttus;
            case ADTOPEN -> adtopen;
            case ACRDLIM -> acrdlim;
            case AEXPDT -> aexpdt;
            case ACSHLIM -> acshlim;
            case AREISDT -> areisdt;
            case ACURBAL -> acurbal;
            case ACRCYCR -> acrcycr;
            case AADDGRP -> aaddgrp;
            case ACRCYDB -> acrcydb;
            case ACSTNUM -> acstnum;
            case ACSTSSN -> acstssn;
            case ACSTDOB -> acstdob;
            case ACSTFCO -> acstfco;
            case ACSFNAM -> acsfnam;
            case ACSMNAM -> acsmnam;
            case ACSLNAM -> acslnam;
            case ACSADL1 -> acsadl1;
            case ACSSTTE -> acsstte;
            case ACSADL2 -> acsadl2;
            case ACSZIPC -> acszipc;
            case ACSCITY -> acscity;
            case ACSCTRY -> acsctry;
            case ACSPHN1 -> acsphn1;
            case ACSGOVT -> acsgovt;
            case ACSPHN2 -> acsphn2;
            case ACSEFTC -> acseftc;
            case ACSPFLG -> acspflg;
            case INFOMSG -> infomsg;
            case ERRMSG -> errmsg;
        };
    }

    /**
     * One field's attribute quad, writable in place.
     *
     * <p>{@code app/cbl/COACTVWC.cbl:555-571} writes {@code xxxC} bytes after the map has been populated,
     * so the colour byte of a built payload has to be settable:
     * {@code response.attributes(ScreenField.INFOMSG).setColour(BmsAttributes.DFHBMDAR)} reproduces
     * {@code :568} exactly.
     *
     * @param field which field's quad
     * @return the live holder; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldAttributes attributes(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read an attribute quad");
        return attributes.get(field);
    }

    @JsonIgnore
    public Map<ScreenField, FieldAttributes> attributeQuads() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * The {@value #FIELD_COUNT} x {@value #ATTRIBUTE_ITEMS_PER_FIELD} attribute items, keyed by their
     * copybook names - {@code TRNNAMEC}, {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV} and so on.
     *
     * <p>The shape a parity case asserts attribute bytes in: every item the output group declares, named as
     * {@code app/cpy-bms/COACTVW.CPY} names it.
     *
     * @return an unmodifiable, insertion-ordered snapshot of 148 entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, Byte> attributeItems() {
        Map<String, Byte> items = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            items.put(field.colourItemName(), quad.getColour());
            items.put(field.psItemName(), quad.getPs());
            items.put(field.hilightItemName(), quad.getHilight());
            items.put(field.validnItemName(), quad.getValidn());
        }
        return Collections.unmodifiableMap(items);
    }

    /**
     * The {@value #FIELD_COUNT} field images, keyed by their {@code xxxO} copybook names.
     *
     * @return an unmodifiable, insertion-ordered snapshot of {@value #FIELD_COUNT} entries; never
     *     {@code null}
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.symbolicItemName(), value(field));
        }
        return Collections.unmodifiableMap(images);
    }

    /**
     * Whether the conversation is on its first entry - {@code CDEMO-PGM-ENTER}, context
     * {@value NavigationContext#PGM_CONTEXT_ENTER}.
     *
     * @return {@code true} when the carried commarea says {@code ENTER}
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext.pgmContext() == NavigationContext.PGM_CONTEXT_ENTER;
    }

    /**
     * Whether the conversation is a re-entry - {@code CDEMO-PGM-REENTER}, context
     * {@value NavigationContext#PGM_CONTEXT_REENTER}.
     *
     * @return {@code true} when the carried commarea says {@code REENTER}
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext.pgmContext() == NavigationContext.PGM_CONTEXT_REENTER;
    }

    /**
     * Applies a highlight decision to one field: {@link BmsAttributes#DFHRED} into its {@code xxxC} item
     * and, when the decision says so, {@code '*'} into its {@code xxxO} item.
     *
     * <p>The asterisk goes through the ordinary {@code PIC X} store, so it arrives space-padded to the
     * receiver's width: {@code "*"} into {@code ACCTSIDO PIC X(11)} becomes an asterisk and ten spaces,
     * which is exactly what {@code MOVE '*' TO ACCTSIDO} produces.
     *
     * @param field the field to repaint
     * @param highlight the decision to apply
     * @return a payload carrying the decision; never {@code null}
     * @throws NullPointerException if {@code field} or {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision names a different field, which would mean it was
     *     resolved for one field and applied to another
     */
    public AccountViewResponse withHighlight(ScreenField field, FieldHighlight highlight) {
        Objects.requireNonNull(field, "A screen field is required to apply a highlight");
        Objects.requireNonNull(highlight, "A highlight decision is required; use "
                + "FieldHighlight.none(field, map) to express 'change nothing'");

        String decidedFor = highlight.screenFieldPrefix();
        if (!decidedFor.isEmpty() && !decidedFor.equals(field.label())) {
            throw new IllegalArgumentException("The highlight was resolved for " + decidedFor
                    + " but is being applied to " + field.label() + "; CSSETATY qualifies both of its "
                    + "moves with one field, so a decision cannot be carried across fields");
        }

        Builder builder = toBuilder();
        if (highlight.colourItemAssigned()) {
            builder.attributes.get(field).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            builder.value(field, highlight.outputItemValue());
        }
        return builder.build();
    }

    /**
     * Resolves the highlight for one field and applies it, in one call.
     *
     * @param field the field whose edit outcome is being reported
     * @param state the field's validation state - {@code OK}, {@code NOT_OK} or {@code BLANK}
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *     {@code CDEMO-PGM-CONTEXT} is {@value NavigationContext#PGM_CONTEXT_REENTER}
     * @return a payload carrying the resolved decision; never {@code null}
     * @throws NullPointerException if {@code field} or {@code state} is {@code null}
     */
    public AccountViewResponse withHighlight(ScreenField field, FieldValidationState state,
            boolean reenter) {
        Objects.requireNonNull(field, "A screen field is required to resolve a highlight");
        Objects.requireNonNull(state, "A validation state is required; use "
                + "FieldValidationState.of(notOk, blank) to derive one from the two 88-levels");
        return withHighlight(field,
                FieldAttributeSetter.resolve(state, reenter, field.label(), MAP_NAME));
    }

    /**
     * The whole {@code CACTVWAO} output group as exactly {@link #GROUP_LENGTH} bytes.
     *
     * <p>Character data is encoded through {@link FixedWidthCodec#charset()} and never through a platform
     * default, so a space is {@code 0x40} under IBM037 and {@code 0x20} under US-ASCII without this method
     * knowing which.
     *
     * @param codec the codec supplying the charset
     * @return a new array of exactly {@link #GROUP_LENGTH} bytes; never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *     character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTVWAO group "
                + "image: it supplies the charset the field data is encoded in");
        byte[] group = new byte[GROUP_LENGTH];
        Arrays.fill(group, 0, TIOAPFX_LENGTH, LOW_VALUES_BYTE);
        for (ScreenField field : ScreenField.values()) {
            Arrays.fill(group, field.fillerOffset(), field.fillerOffset() + FILLER_LENGTH,
                    LOW_VALUES_BYTE);
            FieldAttributes quad = attributes.get(field);
            group[field.colourOffset()] = quad.getColour();
            group[field.psOffset()] = quad.getPs();
            group[field.hilightOffset()] = quad.getHilight();
            group[field.validnOffset()] = quad.getValidn();
            writeCharacters(group, field.dataOffset(), value(field), field.length(), codec,
                    field.describe());
        }
        return group;
    }

    /**
     * Reads a {@link #GROUP_LENGTH}-byte {@code CACTVWAO} image back into a payload.
     *
     * @param groupImage the {@link #GROUP_LENGTH}-byte output group; read, never retained
     * @param codec the codec supplying the charset
     * @return a payload carrying the image's fields and attribute bytes; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@link #GROUP_LENGTH} bytes
     */
    public static AccountViewResponse fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTVWAO area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTVWAO area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTVWAO group of app/cpy-bms/COACTVW.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, readCharacters(groupImage, field, codec));
            FieldAttributes quad = builder.attributes.get(field);
            quad.setColour(groupImage[field.colourOffset()]);
            quad.setPs(groupImage[field.psOffset()]);
            quad.setHilight(groupImage[field.hilightOffset()]);
            quad.setValidn(groupImage[field.validnOffset()]);
        }
        return builder.build();
    }

    private static String readCharacters(byte[] groupImage, ScreenField field, FixedWidthCodec codec) {
        byte[] span = Arrays.copyOfRange(groupImage, field.dataOffset(), field.endOffsetExclusive());
        return codec.decodeImage(span, "the CACTVWAO item " + field.symbolicItemName());
    }

    private static void writeCharacters(byte[] group,
                                        int offset,
                                        String image,
                                        int declaredWidth,
                                        FixedWidthCodec codec,
                                        String what) {
        byte[] encoded = codec.encodeImage(image, what);
        if (encoded.length != declaredWidth) {
            throw new IllegalArgumentException("Charset " + codec.charset().name() + " encodes " + what
                    + " to " + encoded.length + " byte(s) where the copybook declares " + declaredWidth
                    + "; a PIC X(n) item is n bytes, so the CACTVWAO group can only be rendered from an "
                    + "image already at its declared width under a single-byte code page such as IBM037 "
                    + "or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    // The quads are included because they are behaviour - a payload whose ACCTSIDC is DFHRED is not the
    // same screen as one whose ACCTSIDC is DFHDFCOL - even though they never reach the wire.

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountViewResponse response)) {
            return false;
        }
        return sameFieldValues(response)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap)
                && cardScreenState.equals(response.cardScreenState)
                && navigationContext.equals(response.navigationContext)
                && attributes.equals(response.attributes);
    }

    private boolean sameFieldValues(AccountViewResponse that) {
        for (ScreenField field : ScreenField.values()) {
            if (!value(field).equals(that.value(field))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A hash consistent with {@link #equals(Object)} over the same members.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        int result = 1;
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + value(field).hashCode();
        }
        result = 31 * result + nextProgram.hashCode();
        result = 31 * result + nextMapset.hashCode();
        result = 31 * result + nextMap.hashCode();
        result = 31 * result + cardScreenState.hashCode();
        result = 31 * result + navigationContext.hashCode();
        return 31 * result + attributes.hashCode();
    }

    /**
     * A single-line rendering naming the map, the next-screen triple and the two message lines.
     *
     * @return a description of the payload's identity and outcome; never {@code null}
     */
    @JsonIgnore
    public String describe() {
        return "CACTVWAO[map=" + MAP_NAME
                + ", tran=" + THIS_TRANID
                + ", next=" + nextProgram.trim() + '/' + nextMapset.trim() + '/' + nextMap.trim()
                + ", infomsg='" + infomsg.trim()
                + "', errmsg='" + errmsg.trim()
                + "']";
    }

    /**
     * A diagnostic rendering that discloses nothing a log must not hold.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountViewResponse[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(DiagnosticText.screenField(field.label(), value(field)))
                    .append("' ")
                    .append(attributes.get(field))
                    .append(", ");
        }
        return rendered.append("nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', cardScreenState=").append(cardScreenState)
                .append(", navigationContext=").append(navigationContext)
                .append(']')
                .toString();
    }

    /**
     * One of the {@value #FIELD_COUNT} name-labelled {@code DFHMDF} fields of {@code app/bms/COACTVW.bms},
     * in the order the mapset declares them - which is also the order {@code app/cpy-bms/COACTVW.CPY} lays
     * their storage down.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAME} - the transaction identifier, {@code TRNNAMEO PIC X(4)}.
         */
        TRNNAME("TRNNAME", "X(4)", TRNNAME_LENGTH, 248, 34, 1, 7, 19, false),

        /**
         * {@code TITLE01} - the first title line, {@code TITLE01O PIC X(40)}.
         */
        TITLE01("TITLE01", "X(40)", TITLE01_LENGTH, 254, 38, 1, 21, 30, false),

        /**
         * {@code CURDATE} - {@code mm/dd/yy}, {@code CURDATEO PIC X(8)}.
         */
        CURDATE("CURDATE", "X(8)", CURDATE_LENGTH, 260, 47, 1, 71, 77, false),

        /**
         * {@code PGMNAME} - the program name, {@code PGMNAMEO PIC X(8)}.
         */
        PGMNAME("PGMNAME", "X(8)", PGMNAME_LENGTH, 266, 57, 2, 7, 92, false),

        /**
         * {@code TITLE02} - the second title line, {@code TITLE02O PIC X(40)}.
         */
        TITLE02("TITLE02", "X(40)", TITLE02_LENGTH, 272, 61, 2, 21, 107, false),

        /**
         * {@code CURTIME} - {@code hh:mm:ss}, {@code CURTIMEO PIC X(8)}.
         */
        CURTIME("CURTIME", "X(8)", CURTIME_LENGTH, 278, 70, 2, 71, 154, false),

        /**
         * {@code ACCTSID} - the account number, {@code ACCTSIDO PIC X(11)}.
         */
        ACCTSID("ACCTSID", "X(11)", ACCTSID_LENGTH, 284, 84, 5, 38, 169, false),

        /**
         * {@code ACSTTUS} - the account status, {@code ACSTTUSO PIC X(1)}.
         */
        ACSTTUS("ACSTTUS", "X(1)", ACSTTUS_LENGTH, 290, 97, 5, 70, 187, false),

        /**
         * {@code ADTOPEN} - the open date, {@code ADTOPENO PIC X(10)}.
         */
        ADTOPEN("ADTOPEN", "X(10)", ADTOPEN_LENGTH, 296, 107, 6, 17, 195, false),

        /**
         * {@code ACRDLIM} - the credit limit, {@code ACRDLIMO PIC +ZZZ,ZZZ,ZZZ.99}.
         */
        ACRDLIM("ACRDLIM", AMOUNT_PICTURE, ACRDLIM_LENGTH, 302, 117, 6, 61, 212, true),

        /**
         * {@code AEXPDT} - the expiry date, {@code AEXPDTO PIC X(10)}.
         */
        AEXPDT("AEXPDT", "X(10)", AEXPDT_LENGTH, 308, 128, 7, 17, 234, false),

        /**
         * {@code ACSHLIM} - the cash credit limit, {@code ACSHLIMO PIC +ZZZ,ZZZ,ZZZ.99}.
         */
        ACSHLIM("ACSHLIM", AMOUNT_PICTURE, ACSHLIM_LENGTH, 314, 138, 7, 61, 251, true),

        /**
         * {@code AREISDT} - the reissue date, {@code AREISDTO PIC X(10)}.
         */
        AREISDT("AREISDT", "X(10)", AREISDT_LENGTH, 320, 149, 8, 17, 273, false),

        /**
         * {@code ACURBAL} - the current balance, {@code ACURBALO PIC +ZZZ,ZZZ,ZZZ.99}.
         */
        ACURBAL("ACURBAL", AMOUNT_PICTURE, ACURBAL_LENGTH, 326, 159, 8, 61, 290, true),

        /**
         * {@code ACRCYCR} - the current cycle credit, {@code ACRCYCRO PIC +ZZZ,ZZZ,ZZZ.99}.
         */
        ACRCYCR("ACRCYCR", AMOUNT_PICTURE, ACRCYCR_LENGTH, 332, 171, 9, 61, 312, true),

        /**
         * {@code AADDGRP} - the disclosure group, {@code AADDGRPO PIC X(10)}.
         */
        AADDGRP("AADDGRP", "X(10)", AADDGRP_LENGTH, 338, 182, 10, 23, 334, false),

        /**
         * {@code ACRCYDB} - the current cycle debit, {@code ACRCYDBO PIC +ZZZ,ZZZ,ZZZ.99}.
         */
        ACRCYDB("ACRCYDB", AMOUNT_PICTURE, ACRCYDB_LENGTH, 344, 192, 10, 61, 351, true),

        /**
         * {@code ACSTNUM} - the customer identifier, {@code ACSTNUMO PIC X(9)}.
         */
        ACSTNUM("ACSTNUM", "X(9)", ACSTNUM_LENGTH, 350, 207, 12, 23, 373, false),

        /**
         * {@code ACSTSSN} - the hyphenated social security number, {@code ACSTSSNO PIC X(12)}.
         */
        ACSTSSN("ACSTSSN", "X(12)", ACSTSSN_LENGTH, 356, 216, 12, 54, 389, false),

        /**
         * {@code ACSTDOB} - the date of birth, {@code ACSTDOBO PIC X(10)}.
         */
        ACSTDOB("ACSTDOB", "X(10)", ACSTDOB_LENGTH, 362, 225, 13, 23, 408, false),

        /**
         * {@code ACSTFCO} - the FICO score, {@code ACSTFCOO PIC X(3)}.
         */
        ACSTFCO("ACSTFCO", "X(3)", ACSTFCO_LENGTH, 368, 234, 13, 61, 425, false),

        /**
         * {@code ACSFNAM} - the given name, {@code ACSFNAMO PIC X(25)}.
         */
        ACSFNAM("ACSFNAM", "X(25)", ACSFNAM_LENGTH, 374, 251, 15, 1, 435, false),

        /**
         * {@code ACSMNAM} - the middle name, {@code ACSMNAMO PIC X(25)}.
         */
        ACSMNAM("ACSMNAM", "X(25)", ACSMNAM_LENGTH, 380, 256, 15, 28, 467, false),

        /**
         * {@code ACSLNAM} - the family name, {@code ACSLNAMO PIC X(25)}.
         */
        ACSLNAM("ACSLNAM", "X(25)", ACSLNAM_LENGTH, 386, 261, 15, 55, 499, false),

        /**
         * {@code ACSADL1} - address line 1, {@code ACSADL1O PIC X(50)}.
         */
        ACSADL1("ACSADL1", "X(50)", ACSADL1_LENGTH, 392, 268, 16, 10, 531, false),

        /**
         * {@code ACSSTTE} - the state code, {@code ACSSTTEO PIC X(2)}.
         */
        ACSSTTE("ACSSTTE", "X(2)", ACSSTTE_LENGTH, 398, 277, 16, 73, 588, false),

        /**
         * {@code ACSADL2} - address line 2, {@code ACSADL2O PIC X(50)}.
         */
        ACSADL2("ACSADL2", "X(50)", ACSADL2_LENGTH, 404, 282, 17, 10, 597, false),

        /**
         * {@code ACSZIPC} - the postal code, {@code ACSZIPCO PIC X(5)}, right-justified on the screen.
         */
        ACSZIPC("ACSZIPC", "X(5)", ACSZIPC_LENGTH, 410, 291, 17, 73, 654, false),

        /**
         * {@code ACSCITY} - {@code ACSCITYO PIC X(50)}, fed from {@code CUST-ADDR-LINE-3}.
         */
        ACSCITY("ACSCITY", "X(50)", ACSCITY_LENGTH, 416, 301, 18, 10, 666, false),

        /**
         * {@code ACSCTRY} - the country code, {@code ACSCTRYO PIC X(3)}.
         */
        ACSCTRY("ACSCTRY", "X(3)", ACSCTRY_LENGTH, 422, 310, 18, 73, 723, false),

        /**
         * {@code ACSPHN1} - the first telephone number, {@code ACSPHN1O PIC X(13)}.
         */
        ACSPHN1("ACSPHN1", "X(13)", ACSPHN1_LENGTH, 428, 319, 19, 10, 733, false),

        /**
         * {@code ACSGOVT} - the government-issued identifier, {@code ACSGOVTO PIC X(20)}.
         */
        ACSGOVT("ACSGOVT", "X(20)", ACSGOVT_LENGTH, 434, 326, 19, 58, 753, false),

        /**
         * {@code ACSPHN2} - the second telephone number, {@code ACSPHN2O PIC X(13)}.
         */
        ACSPHN2("ACSPHN2", "X(13)", ACSPHN2_LENGTH, 440, 335, 20, 10, 780, false),

        /**
         * {@code ACSEFTC} - the EFT account, {@code ACSEFTCO PIC X(10)}.
         */
        ACSEFTC("ACSEFTC", "X(10)", ACSEFTC_LENGTH, 446, 342, 20, 41, 800, false),

        /**
         * {@code ACSPFLG} - the primary card holder indicator, {@code ACSPFLGO PIC X(1)}.
         */
        ACSPFLG("ACSPFLG", "X(1)", ACSPFLG_LENGTH, 452, 351, 20, 78, 817, false),

        /**
         * {@code INFOMSG} - the information line, {@code INFOMSGO PIC X(45)}.
         */
        INFOMSG("INFOMSG", "X(45)", INFOMSG_LENGTH, 458, 356, 22, 23, 825, false),

        /**
         * {@code ERRMSG} - the error line, {@code ERRMSGO PIC X(78)}.
         */
        ERRMSG("ERRMSG", "X(78)", ERRMSG_LENGTH, 464, 365, 23, 1, 877, false);

        private final String label;

        private final String picture;

        private final int length;

        private final int copybookLine;

        private final int mapsetLine;

        private final int screenRow;

        private final int screenColumn;

        private final int dataOffset;

        private final boolean numericEdited;

        ScreenField(String label,
                    String picture,
                    int length,
                    int copybookLine,
                    int mapsetLine,
                    int screenRow,
                    int screenColumn,
                    int dataOffset,
                    boolean numericEdited) {
            this.label = label;
            this.picture = picture;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
            this.screenRow = screenRow;
            this.screenColumn = screenColumn;
            this.dataOffset = dataOffset;
            this.numericEdited = numericEdited;
        }

        /**
         * The {@code DFHMDF} label - also the {@code (SCRNVAR2)} token {@code CSSETATY} substitutes.
         *
         * @return the label, for example {@code "ACCTSID"}; never {@code null}
         */
        public String label() {
            return label;
        }

        /**
         * The output data item's name: the label followed by
         * {@link FieldAttributeSetter#OUTPUT_ITEM_SUFFIX}.
         *
         * @return for example {@code "ACCTSIDO"}; never {@code null}
         */
        public String symbolicItemName() {
            return label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The colour item's name: the label followed by {@link FieldAttributeSetter#COLOUR_ITEM_SUFFIX}.
         *
         * @return for example {@code "ACCTSIDC"}; never {@code null}
         */
        public String colourItemName() {
            return label + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        public String psItemName() {
            return label + PS_ITEM_SUFFIX;
        }

        public String hilightItemName() {
            return label + HILIGHT_ITEM_SUFFIX;
        }

        public String validnItemName() {
            return label + VALIDN_ITEM_SUFFIX;
        }

        /**
         * The {@code PICTURE} as {@code app/cpy-bms/COACTVW.CPY} writes it.
         *
         * @return {@code "X(n)"} for 32 fields, {@link #AMOUNT_PICTURE} for the other five; never
         *     {@code null}
         */
        public String picture() {
            return picture;
        }

        /**
         * Whether this field carries the {@link #AMOUNT_PICTURE} edit mask.
         *
         * @return {@code true} for the {@value #AMOUNT_MASK_FIELD_COUNT} money items
         */
        public boolean isNumericEdited() {
            return numericEdited;
        }

        /**
         * The declared width in bytes, which is both the {@code PICTURE} width and the mapset's
         * {@code LENGTH=} operand.
         *
         * @return the width, at least 1
         */
        public int length() {
            return length;
        }

        /**
         * The {@code xxxO} item's line in {@code app/cpy-bms/COACTVW.CPY}.
         *
         * @return a 1-based line number between 248 and 464
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The field's {@code DFHMDF} first line in {@code app/bms/COACTVW.bms}.
         *
         * @return a 1-based line number between 34 and 365
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        public int screenRow() {
            return screenRow;
        }

        public int screenColumn() {
            return screenColumn;
        }

        /**
         * Where the field's {@code FILLER PICTURE X(3)} begins - the start of its overhead.
         *
         * @return {@code dataOffset -}{@link #FIELD_OVERHEAD}
         */
        public int fillerOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        public int colourOffset() {
            return fillerOffset() + FILLER_LENGTH;
        }

        /**
         * Where the field's {@code xxxP} programmed-symbols byte sits.
         *
         * @return the offset of the PS item
         */
        public int psOffset() {
            return colourOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int hilightOffset() {
            return psOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        public int validnOffset() {
            return hilightOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * Where the field's data begins in the {@link #GROUP_LENGTH}-byte group.
         *
         * @return a 0-based offset between 19 and 877
         */
        public int dataOffset() {
            return dataOffset;
        }

        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /**
         * A description naming the item, its {@code PICTURE}, both source lines and its screen position.
         *
         * @return for example
         *     {@code "ACCTSIDO PIC X(11) at app/cpy-bms/COACTVW.CPY:284, DFHMDF ACCTSID at app/bms/COACTVW.bms:84, POS=(5,38)"};
         *     never {@code null}
         */
        public String describe() {
            return symbolicItemName() + " PIC " + picture + " at app/cpy-bms/COACTVW.CPY:" + copybookLine
                    + ", DFHMDF " + label + " at app/bms/COACTVW.bms:" + mapsetLine + ", POS=("
                    + screenRow + ',' + screenColumn + ')';
        }

        /**
         * The constant whose {@code DFHMDF} label is {@code label}.
         *
         * @param label the label to look up, case sensitive as the mapset writes it
         * @return the matching constant; never {@code null}
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField byLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look up a ScreenField");
            for (ScreenField field : values()) {
                if (field.label.equals(label)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("app/bms/COACTVW.bms declares no name-labelled DFHMDF "
                    + "called '" + label + "'; the " + FIELD_COUNT + " it does declare are "
                    + Arrays.toString(values()));
        }
    }

    public static final class FieldAttributes {
        /**
         * The unwritten value of every item: {@code LOW-VALUES}.
         */
        public static final byte UNSET = LOW_VALUES_BYTE;

        private byte colour;

        private byte ps;

        private byte hilight;

        private byte validn;

        /**
         * A quad at {@code LOW-VALUES}, as map initialisation leaves it.
         */
        public FieldAttributes() {
            resetToLowValues();
        }

        public FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
            this.colour = colour;
            this.ps = ps;
            this.hilight = hilight;
            this.validn = validn;
        }

        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A quad is required to copy one");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        public byte getColour() {
            return colour;
        }

        /**
         * Writes the {@code xxxC} colour byte - {@code MOVE DFHDFCOL TO ACCTSIDC} at
         * {@code app/cbl/COACTVWC.cbl:555} and {@code MOVE DFHRED TO ACCTSIDC} at {@code :558}.
         *
         * @param colour a {@link BmsAttributes} colour constant
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        public byte getPs() {
            return ps;
        }

        public void setPs(byte ps) {
            this.ps = ps;
        }

        public byte getHilight() {
            return hilight;
        }

        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        public byte getValidn() {
            return validn;
        }

        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /**
         * Returns all four items to {@code LOW-VALUES}, reproducing {@code MOVE LOW-VALUES}.
         */
        public void resetToLowValues() {
            this.colour = UNSET;
            this.ps = UNSET;
            this.hilight = UNSET;
            this.validn = UNSET;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHRED} - the {@code CSSETATY} error colour.
         *
         * @return {@code true} when the field is painted red
         */
        public boolean isRedHighlighted() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHDFCOL}, the default.
         *
         * @return {@code true} when the field carries the default colour
         */
        public boolean isDefaultColour() {
            return colour == BmsAttributes.DFHDFCOL;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHBMDAR}, the non-display attribute
         * {@code app/cbl/COACTVWC.cbl:568} moves into {@code INFOMSGC} to hide the information line.
         *
         * @return {@code true} when the field is suppressed from display
         */
        public boolean isNonDisplay() {
            return colour == BmsAttributes.DFHBMDAR;
        }

        /**
         * Whether the colour item holds {@link BmsAttributes#DFHNEUTR}, which
         * {@code app/cbl/COACTVWC.cbl:570} moves into {@code INFOMSGC} to show the information line.
         *
         * @return {@code true} when the field is shown in the neutral colour
         */
        public boolean isNeutral() {
            return colour == BmsAttributes.DFHNEUTR;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes quad)) {
                return false;
            }
            return colour == quad.colour && ps == quad.ps && hilight == quad.hilight
                    && validn == quad.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }

        /**
         * The four bytes in hexadecimal, with the colour's mnemonic where it has one.
         *
         * @return for example {@code "&#123;C=0xF2 DFHRED, P=0x00, H=0x00, V=0x00&#125;"}; never
         *     {@code null}
         */
        @Override
        public String toString() {
            return "{C=" + BmsAttributes.toHex(colour) + ' ' + BmsAttributes.colourMnemonic(colour)
                    + ", P=" + BmsAttributes.toHex(ps)
                    + ", H=" + BmsAttributes.toHex(hilight)
                    + ", V=" + BmsAttributes.toHex(validn)
                    + '}';
        }
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private String trnname;
        private String title01;
        private String curdate;
        private String pgmname;
        private String title02;
        private String curtime;
        private String acctsid;
        private String acsttus;
        private String adtopen;
        private String acrdlim;
        private String aexpdt;
        private String acshlim;
        private String areisdt;
        private String acurbal;
        private String acrcycr;
        private String aaddgrp;
        private String acrcydb;
        private String acstnum;
        private String acstssn;
        private String acstdob;
        private String acstfco;
        private String acsfnam;
        private String acsmnam;
        private String acslnam;
        private String acsadl1;
        private String acsstte;
        private String acsadl2;
        private String acszipc;
        private String acscity;
        private String acsctry;
        private String acsphn1;
        private String acsgovt;
        private String acsphn2;
        private String acseftc;
        private String acspflg;
        private String infomsg;
        private String errmsg;
        private String nextProgram;
        private String nextMapset;
        private String nextMap;
        private CardScreenState cardScreenState;
        private NavigationContext navigationContext;
        private final Map<ScreenField, FieldAttributes> attributes = new EnumMap<>(ScreenField.class);

        /**
         * A builder in the {@code MOVE LOW-VALUES TO CACTVWAO} state.
         */
        public Builder() {
            for (ScreenField field : ScreenField.values()) {
                value(field, CardScreenState.lowValues(field.length()));
                attributes.put(field, new FieldAttributes());
            }
            this.nextProgram = CardScreenState.spaces(NEXT_PROGRAM_LENGTH);
            this.nextMapset = CardScreenState.spaces(NEXT_MAPSET_LENGTH);
            this.nextMap = CardScreenState.spaces(NEXT_MAP_LENGTH);
            this.cardScreenState = new CardScreenState();
            this.navigationContext = NavigationContext.empty();
        }

        public Builder trnname(String trnname) {
            this.trnname = orLowValues(trnname, TRNNAME_LENGTH);
            return this;
        }

        public Builder title01(String title01) {
            this.title01 = orLowValues(title01, TITLE01_LENGTH);
            return this;
        }

        public Builder curdate(String curdate) {
            this.curdate = orLowValues(curdate, CURDATE_LENGTH);
            return this;
        }

        public Builder pgmname(String pgmname) {
            this.pgmname = orLowValues(pgmname, PGMNAME_LENGTH);
            return this;
        }

        public Builder title02(String title02) {
            this.title02 = orLowValues(title02, TITLE02_LENGTH);
            return this;
        }

        public Builder curtime(String curtime) {
            this.curtime = orLowValues(curtime, CURTIME_LENGTH);
            return this;
        }

        /**
         * {@code ACCTSIDO} - an account identifier, {@code LOW-VALUES}, or
         * {@value AccountViewResponse#BLANK_FIELD_MARKER}.
         *
         * @param acctsid the value; {@code null} means {@code LOW-VALUES}, which is also what
         *     {@code app/cbl/COACTVWC.cbl:466} moves when the filter is blank
         * @return this builder
         */
        public Builder acctsid(String acctsid) {
            this.acctsid = orLowValues(acctsid, ACCTSID_LENGTH);
            return this;
        }

        public Builder acsttus(String acsttus) {
            this.acsttus = orLowValues(acsttus, ACSTTUS_LENGTH);
            return this;
        }

        public Builder adtopen(String adtopen) {
            this.adtopen = orLowValues(adtopen, ADTOPEN_LENGTH);
            return this;
        }

        /**
         * {@code ACRDLIMO} as an already-rendered image - the route for {@code LOW-VALUES}.
         *
         * @param acrdlim the image; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acrdlim(String acrdlim) {
            this.acrdlim = orLowValues(acrdlim, ACRDLIM_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CREDIT-LIMIT TO ACRDLIMO}, {@code app/cbl/COACTVWC.cbl:477}.
         *
         * @param amount {@code ACCT-CREDIT-LIMIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acrdlimAmount(BigDecimal amount) {
            return acrdlim(editAmount(amount));
        }

        /**
         * {@code AEXPDTO} - fed from {@code ACCT-EXPIRAION-DATE}, spelled as the copybook spells it.
         *
         * @param aexpdt the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder aexpdt(String aexpdt) {
            this.aexpdt = orLowValues(aexpdt, AEXPDT_LENGTH);
            return this;
        }

        public Builder acshlim(String acshlim) {
            this.acshlim = orLowValues(acshlim, ACSHLIM_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CASH-CREDIT-LIMIT TO ACSHLIMO}, {@code app/cbl/COACTVWC.cbl:479-480}.
         *
         * @param amount {@code ACCT-CASH-CREDIT-LIMIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acshlimAmount(BigDecimal amount) {
            return acshlim(editAmount(amount));
        }

        public Builder areisdt(String areisdt) {
            this.areisdt = orLowValues(areisdt, AREISDT_LENGTH);
            return this;
        }

        public Builder acurbal(String acurbal) {
            this.acurbal = orLowValues(acurbal, ACURBAL_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CURR-BAL TO ACURBALO}, {@code app/cbl/COACTVWC.cbl:475}.
         *
         * @param amount {@code ACCT-CURR-BAL}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acurbalAmount(BigDecimal amount) {
            return acurbal(editAmount(amount));
        }

        public Builder acrcycr(String acrcycr) {
            this.acrcycr = orLowValues(acrcycr, ACRCYCR_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CURR-CYC-CREDIT TO ACRCYCRO}, {@code app/cbl/COACTVWC.cbl:482-483}.
         *
         * @param amount {@code ACCT-CURR-CYC-CREDIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acrcycrAmount(BigDecimal amount) {
            return acrcycr(editAmount(amount));
        }

        public Builder aaddgrp(String aaddgrp) {
            this.aaddgrp = orLowValues(aaddgrp, AADDGRP_LENGTH);
            return this;
        }

        public Builder acrcydb(String acrcydb) {
            this.acrcydb = orLowValues(acrcydb, ACRCYDB_LENGTH);
            return this;
        }

        /**
         * {@code MOVE ACCT-CURR-CYC-DEBIT TO ACRCYDBO}, {@code app/cbl/COACTVWC.cbl:485}.
         *
         * @param amount {@code ACCT-CURR-CYC-DEBIT}, a {@code PIC S9(10)V99} value
         * @return this builder
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Builder acrcydbAmount(BigDecimal amount) {
            return acrcydb(editAmount(amount));
        }

        public Builder acstnum(String acstnum) {
            this.acstnum = orLowValues(acstnum, ACSTNUM_LENGTH);
            return this;
        }

        public Builder acstssn(String acstssn) {
            this.acstssn = orLowValues(acstssn, ACSTSSN_LENGTH);
            return this;
        }

        /**
         * Reproduces the {@code STRING} at {@code app/cbl/COACTVWC.cbl:496-504} exactly: Three subtleties,
         * all reproduced: The reference modifications are 1-based, so {@code (1:3)} is characters 1 to 3,
         * {@code (4:2)} is 4 to 5 and {@code (6:4)} is 6 to 9 - which together consume all nine characters
         * of {@code CUST-SSN PIC 9(09)} and nothing more.
         *
         * @param custSsn {@code CUST-SSN}, exactly 9 characters
         * @return this builder
         * @throws NullPointerException if {@code custSsn} is {@code null}
         * @throws IllegalArgumentException if {@code custSsn} is not exactly 9 characters, because
         *     {@code CUST-SSN(6:4)} would then reference storage the field does not have
         */
        public Builder acstssnFromSsn(String custSsn) {
            Objects.requireNonNull(custSsn, "CUST-SSN is required to compose ACSTSSNO; it is "
                    + "PIC 9(09) in app/cpy/CVCUS01Y.cpy and has no absent state");
            if (custSsn.length() != SSN_LENGTH) {
                throw new IllegalArgumentException("CUST-SSN is PIC 9(09) in app/cpy/CVCUS01Y.cpy, so "
                        + "the STRING at app/cbl/COACTVWC.cbl:496-504 needs exactly " + SSN_LENGTH
                        + " characters to reference (1:3), (4:2) and (6:4); this value is "
                        + custSsn.length());
            }
            String composed = custSsn.substring(0, 3)
                    + SSN_GROUP_SEPARATOR
                    + custSsn.substring(3, 5)
                    + SSN_GROUP_SEPARATOR
                    + custSsn.substring(5, 9);
            char untouchedTail = this.acstssn.charAt(ACSTSSN_STRING_LENGTH);
            this.acstssn = composed + untouchedTail;
            return this;
        }

        public Builder acstdob(String acstdob) {
            this.acstdob = orLowValues(acstdob, ACSTDOB_LENGTH);
            return this;
        }

        public Builder acstfco(String acstfco) {
            this.acstfco = orLowValues(acstfco, ACSTFCO_LENGTH);
            return this;
        }

        public Builder acsfnam(String acsfnam) {
            this.acsfnam = orLowValues(acsfnam, ACSFNAM_LENGTH);
            return this;
        }

        public Builder acsmnam(String acsmnam) {
            this.acsmnam = orLowValues(acsmnam, ACSMNAM_LENGTH);
            return this;
        }

        public Builder acslnam(String acslnam) {
            this.acslnam = orLowValues(acslnam, ACSLNAM_LENGTH);
            return this;
        }

        public Builder acsadl1(String acsadl1) {
            this.acsadl1 = orLowValues(acsadl1, ACSADL1_LENGTH);
            return this;
        }

        public Builder acsstte(String acsstte) {
            this.acsstte = orLowValues(acsstte, ACSSTTE_LENGTH);
            return this;
        }

        public Builder acsadl2(String acsadl2) {
            this.acsadl2 = orLowValues(acsadl2, ACSADL2_LENGTH);
            return this;
        }

        /**
         * {@code ACSZIPCO} - narrowed from {@code CUST-ADDR-ZIP PIC X(10)} at {@link #build()}.
         *
         * @param acszipc the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acszipc(String acszipc) {
            this.acszipc = orLowValues(acszipc, ACSZIPC_LENGTH);
            return this;
        }

        /**
         * {@code ACSCITYO} - fed from {@code CUST-ADDR-LINE-3}, not from a city field.
         *
         * @param acscity the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acscity(String acscity) {
            this.acscity = orLowValues(acscity, ACSCITY_LENGTH);
            return this;
        }

        public Builder acsctry(String acsctry) {
            this.acsctry = orLowValues(acsctry, ACSCTRY_LENGTH);
            return this;
        }

        /**
         * {@code ACSPHN1O} - narrowed from {@code CUST-PHONE-NUM-1 PIC X(15)} at {@link #build()}.
         *
         * @param acsphn1 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsphn1(String acsphn1) {
            this.acsphn1 = orLowValues(acsphn1, ACSPHN1_LENGTH);
            return this;
        }

        public Builder acsgovt(String acsgovt) {
            this.acsgovt = orLowValues(acsgovt, ACSGOVT_LENGTH);
            return this;
        }

        /**
         * {@code ACSPHN2O} - narrowed from {@code CUST-PHONE-NUM-2 PIC X(15)} at {@link #build()}.
         *
         * @param acsphn2 the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder acsphn2(String acsphn2) {
            this.acsphn2 = orLowValues(acsphn2, ACSPHN2_LENGTH);
            return this;
        }

        public Builder acseftc(String acseftc) {
            this.acseftc = orLowValues(acseftc, ACSEFTC_LENGTH);
            return this;
        }

        public Builder acspflg(String acspflg) {
            this.acspflg = orLowValues(acspflg, ACSPFLG_LENGTH);
            return this;
        }

        /**
         * {@code INFOMSGO} - widened by {@value AccountViewResponse#INFOMSG_INFO_MESSAGE_PADDING} spaces
         * when fed from {@code WS-INFO-MSG PIC X(40)}.
         *
         * @param infomsg the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder infomsg(String infomsg) {
            this.infomsg = orLowValues(infomsg, INFOMSG_LENGTH);
            return this;
        }

        /**
         * {@code ERRMSGO} - widened by {@value AccountViewResponse#ERRMSG_RETURN_MESSAGE_PADDING} spaces
         * when fed from {@code WS-RETURN-MSG PIC X(75)}.
         *
         * @param errmsg the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         */
        public Builder errmsg(String errmsg) {
            this.errmsg = orLowValues(errmsg, ERRMSG_LENGTH);
            return this;
        }

        public Builder nextProgram(String nextProgram) {
            this.nextProgram = orSpaces(nextProgram, NEXT_PROGRAM_LENGTH);
            return this;
        }

        /**
         * The next screen's mapset.
         *
         * @param nextMapset the mapset name; {@code null} means spaces
         * @return this builder
         */
        public Builder nextMapset(String nextMapset) {
            this.nextMapset = orSpaces(nextMapset, NEXT_MAPSET_LENGTH);
            return this;
        }

        public Builder nextMap(String nextMap) {
            this.nextMap = orSpaces(nextMap, NEXT_MAP_LENGTH);
            return this;
        }

        public Builder cardScreenState(CardScreenState cardScreenState) {
            Objects.requireNonNull(cardScreenState, "A work area is required; use new CardScreenState() "
                    + "for the state INITIALIZE CC-WORK-AREA produces");
            this.cardScreenState = new CardScreenState(cardScreenState);
            return this;
        }

        /**
         * The {@code CARDDEMO-COMMAREA}.
         *
         * @param navigationContext the commarea
         * @return this builder
         * @throws NullPointerException if {@code navigationContext} is {@code null}; use
         *     {@link NavigationContext#empty()} for the {@code EIBCALEN = 0} case
         */
        public Builder navigationContext(NavigationContext navigationContext) {
            Objects.requireNonNull(navigationContext, "A commarea is required; use "
                    + "NavigationContext.empty() when no communication area was passed");
            this.navigationContext = navigationContext;
            return this;
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:436-437}: {@code MOVE CCDA-TITLE01 TO TITLE01O} and
         * {@code MOVE CCDA-TITLE02 TO TITLE02O}.
         *
         * <p>The literals come from {@link ScreenTitles}, which transcribes {@code app/cpy/COTTL01Y.cpy}
         * character for character including the spaces that make each exactly
         * {@value AccountViewResponse#TITLE01_LENGTH} wide.
         *
         * @return this builder
         */
        public Builder screenTitles() {
            return title01(ScreenTitles.CCDA_TITLE01).title02(ScreenTitles.CCDA_TITLE02);
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:438-439}: {@code MOVE LIT-THISTRANID TO TRNNAMEO} and
         * {@code MOVE LIT-THISPGM TO PGMNAMEO}.
         *
         * @return this builder
         */
        public Builder screenIdentity() {
            return trnname(THIS_TRANID).pgmname(THIS_PROGRAM);
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:447} and {@code :453}:
         * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO} and {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO}.
         *
         * @param dateHeader the {@code CSDAT01Y} header {@code COACTVWC} copies at
         *     {@code app/cbl/COACTVWC.cbl:164}
         * @return this builder
         * @throws NullPointerException if {@code dateHeader} is {@code null}
         */
        public Builder dateHeader(DateHeader dateHeader) {
            Objects.requireNonNull(dateHeader, "A date header is required; this payload never reads a "
                    + "clock of its own, so the instant must be supplied by the caller");
            return curdate(dateHeader.wsCurdateMmDdYy()).curtime(dateHeader.wsCurtimeHhMmSs());
        }

        /**
         * Reproduces {@code app/cbl/COACTVWC.cbl:906-907}: {@code MOVE LIT-THISMAPSET TO CCARD-NEXT-MAPSET}
         * and {@code MOVE LIT-THISMAP TO CCARD-NEXT-MAP}, which {@code 1400-SEND-SCREEN} performs so the
         * screen re-displays itself.
         *
         * @return this builder
         */
        public Builder thisScreenAsNextTarget() {
            return nextProgram(THIS_PROGRAM).nextMapset(THIS_MAPSET).nextMap(MAP_NAME);
        }

        /**
         * The whole next-screen triple, for the {@code XCTL} path at {@code app/cbl/COACTVWC.cbl:349}.
         *
         * @param nextProgram the target program; {@code null} means spaces
         * @param nextMapset the target mapset; {@code null} means spaces
         * @param nextMap the target map; {@code null} means spaces
         * @return this builder
         */
        public Builder nextTarget(String nextProgram, String nextMapset, String nextMap) {
            return nextProgram(nextProgram).nextMapset(nextMapset).nextMap(nextMap);
        }

        /**
         * Stores one field addressed by its {@link ScreenField}.
         *
         * @param field which field
         * @param value the value; {@code null} means {@code LOW-VALUES}
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder value(ScreenField field, String value) {
            Objects.requireNonNull(field, "A ScreenField is required to store a field value");
            return switch (field) {
                case TRNNAME -> trnname(value);
                case TITLE01 -> title01(value);
                case CURDATE -> curdate(value);
                case PGMNAME -> pgmname(value);
                case TITLE02 -> title02(value);
                case CURTIME -> curtime(value);
                case ACCTSID -> acctsid(value);
                case ACSTTUS -> acsttus(value);
                case ADTOPEN -> adtopen(value);
                case ACRDLIM -> acrdlim(value);
                case AEXPDT -> aexpdt(value);
                case ACSHLIM -> acshlim(value);
                case AREISDT -> areisdt(value);
                case ACURBAL -> acurbal(value);
                case ACRCYCR -> acrcycr(value);
                case AADDGRP -> aaddgrp(value);
                case ACRCYDB -> acrcydb(value);
                case ACSTNUM -> acstnum(value);
                case ACSTSSN -> acstssn(value);
                case ACSTDOB -> acstdob(value);
                case ACSTFCO -> acstfco(value);
                case ACSFNAM -> acsfnam(value);
                case ACSMNAM -> acsmnam(value);
                case ACSLNAM -> acslnam(value);
                case ACSADL1 -> acsadl1(value);
                case ACSSTTE -> acsstte(value);
                case ACSADL2 -> acsadl2(value);
                case ACSZIPC -> acszipc(value);
                case ACSCITY -> acscity(value);
                case ACSCTRY -> acsctry(value);
                case ACSPHN1 -> acsphn1(value);
                case ACSGOVT -> acsgovt(value);
                case ACSPHN2 -> acsphn2(value);
                case ACSEFTC -> acseftc(value);
                case ACSPFLG -> acspflg(value);
                case INFOMSG -> infomsg(value);
                case ERRMSG -> errmsg(value);
            };
        }

        /**
         * One field's attribute quad, writable before the payload is built.
         *
         * @param field which field's quad
         * @return the live holder; never {@code null}
         * @throws NullPointerException if {@code field} is {@code null}
         */
        @JsonIgnore
        public FieldAttributes attributes(ScreenField field) {
            Objects.requireNonNull(field, "A ScreenField is required to read an attribute quad");
            return attributes.get(field);
        }

        /**
         * Builds the payload, applying the {@code PIC X} move rule to every field and to the next-screen
         * triple.
         *
         * <p>{@link FixedWidthCodec#movePicX(String, int)} pads on the right when the value is short and
         * truncates on the right when it is long, which is the COBOL rule for an alphanumeric receiver; it
         * is not reimplemented here, so there is exactly one place in the system where that rule can be
         * wrong.
         *
         * @return the payload; never {@code null}
         */
        public AccountViewResponse build() {
            for (ScreenField field : ScreenField.values()) {
                value(field, PIC_X_CODEC.movePicX(valueOf(field), field.length()));
            }
            this.nextProgram = PIC_X_CODEC.movePicX(nextProgram, NEXT_PROGRAM_LENGTH);
            this.nextMapset = PIC_X_CODEC.movePicX(nextMapset, NEXT_MAPSET_LENGTH);
            this.nextMap = PIC_X_CODEC.movePicX(nextMap, NEXT_MAP_LENGTH);
            return new AccountViewResponse(this);
        }

        private String valueOf(ScreenField field) {
            return switch (field) {
                case TRNNAME -> trnname;
                case TITLE01 -> title01;
                case CURDATE -> curdate;
                case PGMNAME -> pgmname;
                case TITLE02 -> title02;
                case CURTIME -> curtime;
                case ACCTSID -> acctsid;
                case ACSTTUS -> acsttus;
                case ADTOPEN -> adtopen;
                case ACRDLIM -> acrdlim;
                case AEXPDT -> aexpdt;
                case ACSHLIM -> acshlim;
                case AREISDT -> areisdt;
                case ACURBAL -> acurbal;
                case ACRCYCR -> acrcycr;
                case AADDGRP -> aaddgrp;
                case ACRCYDB -> acrcydb;
                case ACSTNUM -> acstnum;
                case ACSTSSN -> acstssn;
                case ACSTDOB -> acstdob;
                case ACSTFCO -> acstfco;
                case ACSFNAM -> acsfnam;
                case ACSMNAM -> acsmnam;
                case ACSLNAM -> acslnam;
                case ACSADL1 -> acsadl1;
                case ACSSTTE -> acsstte;
                case ACSADL2 -> acsadl2;
                case ACSZIPC -> acszipc;
                case ACSCITY -> acscity;
                case ACSCTRY -> acsctry;
                case ACSPHN1 -> acsphn1;
                case ACSGOVT -> acsgovt;
                case ACSPHN2 -> acsphn2;
                case ACSEFTC -> acseftc;
                case ACSPFLG -> acspflg;
                case INFOMSG -> infomsg;
                case ERRMSG -> errmsg;
            };
        }

        private static String orLowValues(String value, int width) {
            return value == null ? CardScreenState.lowValues(width) : value;
        }

        private static String orSpaces(String value, int width) {
            return value == null ? CardScreenState.spaces(width) : value;
        }
    }

}
