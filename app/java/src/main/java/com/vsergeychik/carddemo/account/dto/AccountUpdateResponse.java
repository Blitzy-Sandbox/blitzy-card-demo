package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.AcctSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.ChangeAction;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CommArea;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.CustSnapshot;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.account.dto.AccountUpdateRequest.Details;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload of {@code PUT /api/accounts/&#123;acctId&#125;} - CSD transaction {@code CAUP}.
 *
 * <p>The BMS layer alone is the presentation contract, and it is a byte-level one: position, length,
 * attribute and colour are all declared explicitly, so every payload field below traces to exactly one
 * {@code DFHMDF} definition and takes its width from exactly one symbolic-map {@code PICTURE} clause.
 */
@JsonDeserialize(builder = AccountUpdateResponse.Builder.class)
public final class AccountUpdateResponse {
    // Transcribed from app/cbl/COACTUPC.cbl:533-540 including the trailing space LIT-THISMAPSET carries,
    // because that space is exactly what an X(8) to X(7) move discards.

    /**
     * {@code LIT-THISPGM PIC X(8) VALUE 'COACTUPC'}, {@code app/cbl/COACTUPC.cbl:533-534}.
     */
    public static final String THIS_PROGRAM = "COACTUPC";

    /**
     * {@code LIT-THISTRANID PIC X(4) VALUE 'CAUP'}, {@code app/cbl/COACTUPC.cbl:535-536}.
     */
    public static final String THIS_TRANSACTION = "CAUP";

    /**
     * {@code LIT-THISMAPSET PIC X(8) VALUE 'COACTUP '}, {@code app/cbl/COACTUPC.cbl:537-538} - eight
     * characters, the eighth a space.
     */
    public static final String THIS_MAPSET_LITERAL = "COACTUP ";

    /**
     * The mapset as {@code app/bms/COACTUP.bms} names it - seven characters, no trailing space.
     */
    public static final String MAPSET_NAME = "COACTUP";

    /**
     * {@code LIT-THISMAP PIC X(7) VALUE 'CACTUPA'}, {@code app/cbl/COACTUPC.cbl:539-540}.
     */
    public static final String MAP_NAME = "CACTUPA";

    /**
     * The symbolic input group, {@code app/cpy-bms/COACTUP.CPY:23}.
     */
    public static final String INPUT_GROUP_NAME = "CACTUPAI";

    /**
     * The symbolic output group this type projects, {@code app/cpy-bms/COACTUP.CPY:343}.
     */
    public static final String OUTPUT_GROUP_NAME = "CACTUPAO";

    /**
     * {@code CACTUPA DFHMDI SIZE=(24,80)} - rows.
     */
    public static final int SCREEN_ROWS = 24;

    /**
     * {@code CACTUPA DFHMDI SIZE=(24,80)} - columns.
     */
    public static final int SCREEN_COLUMNS = 80;

    /**
     * Every {@code DFHMDF} entry in {@code app/bms/COACTUP.bms}, labelled or not.
     */
    public static final int DFHMDF_ENTRY_COUNT = 128;

    /**
     * {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:350}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COACTUP.CPY:356}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COACTUP.CPY:362}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COACTUP.CPY:368}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COACTUP.CPY:374}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COACTUP.CPY:380}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COACTUP.CPY:386}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * {@code ACSTTUSO PIC X(1)}, {@code app/cpy-bms/COACTUP.CPY:392}.
     */
    public static final int ACSTTUS_LENGTH = 1;

    /**
     * {@code OPNYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:398}.
     */
    public static final int OPNYEAR_LENGTH = 4;

    /**
     * {@code OPNMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:404}.
     */
    public static final int OPNMON_LENGTH = 2;

    /**
     * {@code OPNDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:410}.
     */
    public static final int OPNDAY_LENGTH = 2;

    /**
     * {@code ACRDLIMO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:416}.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /**
     * {@code EXPYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:422}.
     */
    public static final int EXPYEAR_LENGTH = 4;

    /**
     * {@code EXPMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:428}.
     */
    public static final int EXPMON_LENGTH = 2;

    /**
     * {@code EXPDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:434}.
     */
    public static final int EXPDAY_LENGTH = 2;

    /**
     * {@code ACSHLIMO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:440}.
     */
    public static final int ACSHLIM_LENGTH = 15;

    /**
     * {@code RISYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:446}.
     */
    public static final int RISYEAR_LENGTH = 4;

    /**
     * {@code RISMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:452}.
     */
    public static final int RISMON_LENGTH = 2;

    /**
     * {@code RISDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:458}.
     */
    public static final int RISDAY_LENGTH = 2;

    /**
     * {@code ACURBALO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:464}.
     */
    public static final int ACURBAL_LENGTH = 15;

    /**
     * {@code ACRCYCRO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:470}.
     */
    public static final int ACRCYCR_LENGTH = 15;

    /**
     * {@code AADDGRPO PIC X(10)}, {@code app/cpy-bms/COACTUP.CPY:476}.
     */
    public static final int AADDGRP_LENGTH = 10;

    /**
     * {@code ACRCYDBO PIC X(15)}, {@code app/cpy-bms/COACTUP.CPY:482}.
     */
    public static final int ACRCYDB_LENGTH = 15;

    /**
     * {@code ACSTNUMO PIC X(9)}, {@code app/cpy-bms/COACTUP.CPY:488}.
     */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * {@code ACTSSN1O PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:494}.
     */
    public static final int ACTSSN1_LENGTH = 3;

    /**
     * {@code ACTSSN2O PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:500}.
     */
    public static final int ACTSSN2_LENGTH = 2;

    /**
     * {@code ACTSSN3O PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:506}.
     */
    public static final int ACTSSN3_LENGTH = 4;

    /**
     * {@code DOBYEARO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:512}.
     */
    public static final int DOBYEAR_LENGTH = 4;

    /**
     * {@code DOBMONO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:518}.
     */
    public static final int DOBMON_LENGTH = 2;

    /**
     * {@code DOBDAYO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:524}.
     */
    public static final int DOBDAY_LENGTH = 2;

    /**
     * {@code ACSTFCOO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:530}.
     */
    public static final int ACSTFCO_LENGTH = 3;

    /**
     * {@code ACSFNAMO PIC X(25)}, {@code app/cpy-bms/COACTUP.CPY:536}.
     */
    public static final int ACSFNAM_LENGTH = 25;

    /**
     * {@code ACSMNAMO PIC X(25)}, {@code app/cpy-bms/COACTUP.CPY:542}.
     */
    public static final int ACSMNAM_LENGTH = 25;

    /**
     * {@code ACSLNAMO PIC X(25)}, {@code app/cpy-bms/COACTUP.CPY:548}.
     */
    public static final int ACSLNAM_LENGTH = 25;

    /**
     * {@code ACSADL1O PIC X(50)}, {@code app/cpy-bms/COACTUP.CPY:554}.
     */
    public static final int ACSADL1_LENGTH = 50;

    /**
     * {@code ACSSTTEO PIC X(2)}, {@code app/cpy-bms/COACTUP.CPY:560}.
     */
    public static final int ACSSTTE_LENGTH = 2;

    /**
     * {@code ACSADL2O PIC X(50)}, {@code app/cpy-bms/COACTUP.CPY:566}.
     */
    public static final int ACSADL2_LENGTH = 50;

    /**
     * {@code ACSZIPCO PIC X(5)}, {@code app/cpy-bms/COACTUP.CPY:572}.
     */
    public static final int ACSZIPC_LENGTH = 5;

    /**
     * {@code ACSCITYO PIC X(50)}, {@code app/cpy-bms/COACTUP.CPY:578}.
     */
    public static final int ACSCITY_LENGTH = 50;

    /**
     * {@code ACSCTRYO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:584}.
     */
    public static final int ACSCTRY_LENGTH = 3;

    /**
     * {@code ACSPH1AO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:590} - phone 1 area code.
     */
    public static final int ACSPH1A_LENGTH = 3;

    /**
     * {@code ACSPH1BO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:596} - phone 1 prefix.
     */
    public static final int ACSPH1B_LENGTH = 3;

    /**
     * {@code ACSPH1CO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:602} - phone 1 line number.
     */
    public static final int ACSPH1C_LENGTH = 4;

    /**
     * {@code ACSGOVTO PIC X(20)}, {@code app/cpy-bms/COACTUP.CPY:608}.
     */
    public static final int ACSGOVT_LENGTH = 20;

    /**
     * {@code ACSPH2AO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:614} - phone 2 area code.
     */
    public static final int ACSPH2A_LENGTH = 3;

    /**
     * {@code ACSPH2BO PIC X(3)}, {@code app/cpy-bms/COACTUP.CPY:620} - phone 2 prefix.
     */
    public static final int ACSPH2B_LENGTH = 3;

    /**
     * {@code ACSPH2CO PIC X(4)}, {@code app/cpy-bms/COACTUP.CPY:626} - phone 2 line number.
     */
    public static final int ACSPH2C_LENGTH = 4;

    /**
     * {@code ACSEFTCO PIC X(10)}, {@code app/cpy-bms/COACTUP.CPY:632}.
     */
    public static final int ACSEFTC_LENGTH = 10;

    /**
     * {@code ACSPFLGO PIC X(1)}, {@code app/cpy-bms/COACTUP.CPY:638}.
     */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * {@code INFOMSGO PIC X(45)}, {@code app/cpy-bms/COACTUP.CPY:644}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COACTUP.CPY:650}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * {@code FKEYSO PIC X(21)}, {@code app/cpy-bms/COACTUP.CPY:656}.
     */
    public static final int FKEYS_LENGTH = 21;

    /**
     * {@code FKEY05O PIC X(7)}, {@code app/cpy-bms/COACTUP.CPY:662}.
     */
    public static final int FKEY05_LENGTH = 7;

    /**
     * {@code FKEY12O PIC X(10)}, {@code app/cpy-bms/COACTUP.CPY:668}.
     */
    public static final int FKEY12_LENGTH = 10;

    /**
     * {@code 02 FILLER PIC X(12)}, {@code app/cpy-bms/COACTUP.CPY:344} - the {@code TIOAPFX=YES} prefix
     * that {@code app/bms/COACTUP.bms:23} asks BMS to generate.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * {@code 02 FILLER PICTURE X(3)}, the unnamed span that opens each field's stride.
     */
    public static final int FIELD_PREFIX_FILLER_LENGTH = 3;

    /**
     * {@code 02 xxxC PICTURE X} - the COLOR item, one byte.
     */
    public static final int COLOUR_ITEM_LENGTH = 1;

    /**
     * {@code 02 xxxP PICTURE X} - the PS (programmed symbols) item, one byte.
     */
    public static final int PS_ITEM_LENGTH = 1;

    /**
     * {@code 02 xxxH PICTURE X} - the HILIGHT item, one byte.
     */
    public static final int HILIGHT_ITEM_LENGTH = 1;

    /**
     * {@code 02 xxxV PICTURE X} - the VALIDN item, one byte.
     */
    public static final int VALIDN_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_QUAD_LENGTH =
            COLOUR_ITEM_LENGTH + PS_ITEM_LENGTH + HILIGHT_ITEM_LENGTH + VALIDN_ITEM_LENGTH;

    public static final int FIELD_OVERHEAD = FIELD_PREFIX_FILLER_LENGTH + ATTRIBUTE_QUAD_LENGTH;

    /**
     * The name-labelled {@code DFHMDF} fields of {@code app/bms/COACTUP.bms}.
     */
    public static final int FIELD_COUNT = 54;

    public static final int PAYLOAD_LENGTH = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + ACCTSID_LENGTH + ACSTTUS_LENGTH
            + OPNYEAR_LENGTH + OPNMON_LENGTH + OPNDAY_LENGTH + ACRDLIM_LENGTH + EXPYEAR_LENGTH
            + EXPMON_LENGTH + EXPDAY_LENGTH + ACSHLIM_LENGTH + RISYEAR_LENGTH + RISMON_LENGTH
            + RISDAY_LENGTH + ACURBAL_LENGTH + ACRCYCR_LENGTH + AADDGRP_LENGTH + ACRCYDB_LENGTH
            + ACSTNUM_LENGTH + ACTSSN1_LENGTH + ACTSSN2_LENGTH + ACTSSN3_LENGTH + DOBYEAR_LENGTH
            + DOBMON_LENGTH + DOBDAY_LENGTH + ACSTFCO_LENGTH + ACSFNAM_LENGTH + ACSMNAM_LENGTH
            + ACSLNAM_LENGTH + ACSADL1_LENGTH + ACSSTTE_LENGTH + ACSADL2_LENGTH + ACSZIPC_LENGTH
            + ACSCITY_LENGTH + ACSCTRY_LENGTH + ACSPH1A_LENGTH + ACSPH1B_LENGTH + ACSPH1C_LENGTH
            + ACSGOVT_LENGTH + ACSPH2A_LENGTH + ACSPH2B_LENGTH + ACSPH2C_LENGTH + ACSEFTC_LENGTH
            + ACSPFLG_LENGTH + INFOMSG_LENGTH + ERRMSG_LENGTH + FKEYS_LENGTH + FKEY05_LENGTH
            + FKEY12_LENGTH;

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * FIELD_OVERHEAD + PAYLOAD_LENGTH;

    /**
     * {@code LIT-THISPGM PIC X(8)} - the width of a program name, and of {@code CDEMO-TO-PROGRAM}.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * The width a mapset name arrives at: {@code CDEMO-LAST-MAPSET PIC X(7)}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * {@code LIT-THISMAP PIC X(7)} and {@code CDEMO-LAST-MAP PIC X(7)} - the same seven characters.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * The eight-character mapset literal {@code app/cbl/COACTUPC.cbl:537-538} declares.
     */
    public static final int THIS_MAPSET_LITERAL_LENGTH = 8;

    /**
     * The bytes {@code COMMON-RETURN} actually fills of {@code WS-COMMAREA PIC X(2000)}
     * [{@code app/cbl/COACTUPC.cbl:850}]: the {@value NavigationContext#COMMAREA_LENGTH}-byte
     * {@code CARDDEMO-COMMAREA} followed by the {@value CommArea#RECORD_LENGTH}-byte
     * {@code WS-THIS-PROGCOMMAREA}, written at {@code :1010-1013}.
     */
    public static final int TOTAL_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CommArea.RECORD_LENGTH;

    /**
     * {@code WS-COMMAREA PIC X(2000)}, {@code app/cbl/COACTUPC.cbl:850}.
     */
    public static final int COMMAREA_CAPACITY = 2000;

    public static final int CSMSG01Y_MESSAGE_LENGTH = SystemMessages.MESSAGE_LENGTH;

    /**
     * The scale every monetary value this screen renders is held at, {@link CobolDecimal#MONETARY_SCALE}.
     */
    public static final int MONETARY_SCALE = CobolDecimal.MONETARY_SCALE;

    /**
     * The only faithful rounding mode, {@link CobolDecimal#COBOL_ROUNDING}.
     *
     * <p>{@code ROUNDED} appears zero times across all 28 COBOL programs, so COBOL truncates excess
     * fractional digits on store and {@link RoundingMode#DOWN} is the only correct choice.
     */
    public static final RoundingMode MONETARY_ROUNDING = CobolDecimal.COBOL_ROUNDING;

    /**
     * The suffix of a payload item: {@code 02 xxxO PIC X(n)}.
     */
    public static final String OUTPUT_ITEM_SUFFIX = "O";

    /**
     * The suffix of the COLOR item: {@code 02 xxxC PICTURE X}.
     */
    public static final String COLOUR_ITEM_SUFFIX = "C";

    /**
     * The suffix of the PS item: {@code 02 xxxP PICTURE X}.
     */
    public static final String PS_ITEM_SUFFIX = "P";

    /**
     * The suffix of the HILIGHT item: {@code 02 xxxH PICTURE X}.
     */
    public static final String HILIGHT_ITEM_SUFFIX = "H";

    /**
     * The suffix of the VALIDN item: {@code 02 xxxV PICTURE X}.
     */
    public static final String VALIDN_ITEM_SUFFIX = "V";

    public static final String BLANK_FIELD_MARKER = FieldAttributeSetter.ASTERISK;

    /**
     * {@code FKEYS DFHMDF LENGTH=21 INITIAL='ENTER=Process F3=Exit'}, {@code app/bms/COACTUP.bms:493} -
     * exactly {@value #FKEYS_LENGTH} characters, so the legend fills its field with no padding.
     */
    public static final String FKEYS_LEGEND = "ENTER=Process F3=Exit";

    /**
     * {@code FKEY05 DFHMDF LENGTH=7 INITIAL='F5=Save'}, {@code app/bms/COACTUP.bms:498}.
     */
    public static final String FKEY05_LEGEND = "F5=Save";

    /**
     * {@code FKEY12 DFHMDF LENGTH=10 INITIAL='F12=Cancel'}, {@code app/bms/COACTUP.bms:503}.
     */
    public static final String FKEY12_LEGEND = "F12=Cancel";

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private static final byte LOW_VALUE_BYTE = (byte) 0x00;

    /**
     * One of the {@value #FIELD_COUNT} name-labelled {@code DFHMDF} fields of {@code app/bms/COACTUP.bms},
     * in the order the mapset declares them - which is also the order {@code app/cpy-bms/COACTUP.CPY} lays
     * their storage down.
     */
    public enum ScreenField {
        TRNNAME("TRNNAME", "TRNNAMEO", "X(4)", TRNNAME_LENGTH, 350, 34, 1, 7, 19),

        TITLE01("TITLE01", "TITLE01O", "X(40)", TITLE01_LENGTH, 356, 38, 1, 21, 30),

        /**
         * {@code CURDATE} - {@code INITIAL='mm/dd/yy'}; fed from {@code WS-CURDATE-MM-DD-YY} at
         * {@code :2684}.
         */
        CURDATE("CURDATE", "CURDATEO", "X(8)", CURDATE_LENGTH, 362, 47, 1, 71, 77),

        /**
         * {@code PGMNAME} - from {@code LIT-THISPGM}, moved at {@code :2676}.
         */
        PGMNAME("PGMNAME", "PGMNAMEO", "X(8)", PGMNAME_LENGTH, 368, 57, 2, 7, 92),

        TITLE02("TITLE02", "TITLE02O", "X(40)", TITLE02_LENGTH, 374, 61, 2, 21, 107),

        /**
         * {@code CURTIME} - {@code INITIAL='hh:mm:ss'}; fed from {@code WS-CURTIME-HH-MM-SS} at
         * {@code :2690}.
         */
        CURTIME("CURTIME", "CURTIMEO", "X(8)", CURTIME_LENGTH, 380, 70, 2, 71, 154),

        ACCTSID("ACCTSID", "ACCTSIDO", "X(11)", ACCTSID_LENGTH, 386, 84, 5, 38, 169),

        /**
         * {@code ACSTTUS} - {@code ACCT-ACTIVE-STATUS}, one character.
         */
        ACSTTUS("ACSTTUS", "ACSTTUSO", "X(1)", ACSTTUS_LENGTH, 392, 94, 5, 70, 187),

        OPNYEAR("OPNYEAR", "OPNYEARO", "X(4)", OPNYEAR_LENGTH, 398, 104, 6, 17, 195),

        OPNMON("OPNMON", "OPNMONO", "X(2)", OPNMON_LENGTH, 404, 112, 6, 24, 206),

        OPNDAY("OPNDAY", "OPNDAYO", "X(2)", OPNDAY_LENGTH, 410, 120, 6, 29, 215),

        /**
         * {@code ACRDLIM} - credit limit, rendered by {@code WS-EDIT-CURRENCY-9-2-F} into {@code X(15)}.
         */
        ACRDLIM("ACRDLIM", "ACRDLIMO", "X(15)", ACRDLIM_LENGTH, 416, 132, 6, 61, 224),

        EXPYEAR("EXPYEAR", "EXPYEARO", "X(4)", EXPYEAR_LENGTH, 422, 142, 7, 17, 246),

        EXPMON("EXPMON", "EXPMONO", "X(2)", EXPMON_LENGTH, 428, 150, 7, 24, 257),

        EXPDAY("EXPDAY", "EXPDAYO", "X(2)", EXPDAY_LENGTH, 434, 158, 7, 29, 266),

        ACSHLIM("ACSHLIM", "ACSHLIMO", "X(15)", ACSHLIM_LENGTH, 440, 170, 7, 61, 275),

        RISYEAR("RISYEAR", "RISYEARO", "X(4)", RISYEAR_LENGTH, 446, 180, 8, 17, 297),

        RISMON("RISMON", "RISMONO", "X(2)", RISMON_LENGTH, 452, 188, 8, 24, 308),

        RISDAY("RISDAY", "RISDAYO", "X(2)", RISDAY_LENGTH, 458, 196, 8, 29, 317),

        ACURBAL("ACURBAL", "ACURBALO", "X(15)", ACURBAL_LENGTH, 464, 208, 8, 61, 326),

        ACRCYCR("ACRCYCR", "ACRCYCRO", "X(15)", ACRCYCR_LENGTH, 470, 219, 9, 61, 348),

        /**
         * {@code AADDGRP} - the account group identifier, {@code ACCT-GROUP-ID PIC X(10)}.
         */
        AADDGRP("AADDGRP", "AADDGRPO", "X(10)", AADDGRP_LENGTH, 476, 229, 10, 23, 370),

        ACRCYDB("ACRCYDB", "ACRCYDBO", "X(15)", ACRCYDB_LENGTH, 482, 240, 10, 61, 387),

        ACSTNUM("ACSTNUM", "ACSTNUMO", "X(9)", ACSTNUM_LENGTH, 488, 254, 12, 23, 409),

        ACTSSN1("ACTSSN1", "ACTSSN1O", "X(3)", ACTSSN1_LENGTH, 494, 264, 12, 55, 425),

        ACTSSN2("ACTSSN2", "ACTSSN2O", "X(2)", ACTSSN2_LENGTH, 500, 272, 12, 61, 435),

        ACTSSN3("ACTSSN3", "ACTSSN3O", "X(4)", ACTSSN3_LENGTH, 506, 280, 12, 66, 444),

        DOBYEAR("DOBYEAR", "DOBYEARO", "X(4)", DOBYEAR_LENGTH, 512, 291, 13, 23, 455),

        DOBMON("DOBMON", "DOBMONO", "X(2)", DOBMON_LENGTH, 518, 299, 13, 30, 466),

        DOBDAY("DOBDAY", "DOBDAYO", "X(2)", DOBDAY_LENGTH, 524, 307, 13, 35, 475),

        ACSTFCO("ACSTFCO", "ACSTFCOO", "X(3)", ACSTFCO_LENGTH, 530, 318, 13, 62, 484),

        ACSFNAM("ACSFNAM", "ACSFNAMO", "X(25)", ACSFNAM_LENGTH, 536, 336, 15, 1, 494),

        ACSMNAM("ACSMNAM", "ACSMNAMO", "X(25)", ACSMNAM_LENGTH, 542, 342, 15, 28, 526),

        ACSLNAM("ACSLNAM", "ACSLNAMO", "X(25)", ACSLNAM_LENGTH, 548, 348, 15, 55, 558),

        ACSADL1("ACSADL1", "ACSADL1O", "X(50)", ACSADL1_LENGTH, 554, 356, 16, 10, 590),

        ACSSTTE("ACSSTTE", "ACSSTTEO", "X(2)", ACSSTTE_LENGTH, 560, 366, 16, 73, 647),

        ACSADL2("ACSADL2", "ACSADL2O", "X(50)", ACSADL2_LENGTH, 566, 372, 17, 10, 656),

        /**
         * {@code ACSZIPC} - the postal code as the screen shows it, five characters.
         */
        ACSZIPC("ACSZIPC", "ACSZIPCO", "X(5)", ACSZIPC_LENGTH, 572, 382, 17, 73, 713),

        ACSCITY("ACSCITY", "ACSCITYO", "X(50)", ACSCITY_LENGTH, 578, 392, 18, 10, 725),

        ACSCTRY("ACSCTRY", "ACSCTRYO", "X(3)", ACSCTRY_LENGTH, 584, 402, 18, 73, 782),

        ACSPH1A("ACSPH1A", "ACSPH1AO", "X(3)", ACSPH1A_LENGTH, 590, 412, 19, 10, 792),

        ACSPH1B("ACSPH1B", "ACSPH1BO", "X(3)", ACSPH1B_LENGTH, 596, 417, 19, 14, 802),

        ACSPH1C("ACSPH1C", "ACSPH1CO", "X(4)", ACSPH1C_LENGTH, 602, 422, 19, 18, 812),

        ACSGOVT("ACSGOVT", "ACSGOVTO", "X(20)", ACSGOVT_LENGTH, 608, 433, 19, 58, 823),

        ACSPH2A("ACSPH2A", "ACSPH2AO", "X(3)", ACSPH2A_LENGTH, 614, 443, 20, 10, 850),

        ACSPH2B("ACSPH2B", "ACSPH2BO", "X(3)", ACSPH2B_LENGTH, 620, 448, 20, 14, 860),

        ACSPH2C("ACSPH2C", "ACSPH2CO", "X(4)", ACSPH2C_LENGTH, 626, 453, 20, 18, 870),

        ACSEFTC("ACSEFTC", "ACSEFTCO", "X(10)", ACSEFTC_LENGTH, 632, 464, 20, 41, 881),

        ACSPFLG("ACSPFLG", "ACSPFLGO", "X(1)", ACSPFLG_LENGTH, 638, 474, 20, 78, 898),

        /**
         * {@code INFOMSG} - the informational message line, {@code ATTRB=(ASKIP)}.
         */
        INFOMSG("INFOMSG", "INFOMSGO", "X(45)", INFOMSG_LENGTH, 644, 480, 22, 23, 906),

        ERRMSG("ERRMSG", "ERRMSGO", "X(78)", ERRMSG_LENGTH, 650, 489, 23, 1, 958),

        FKEYS("FKEYS", "FKEYSO", "X(21)", FKEYS_LENGTH, 656, 493, 24, 1, 1043),

        FKEY05("FKEY05", "FKEY05O", "X(7)", FKEY05_LENGTH, 662, 498, 24, 23, 1071),

        FKEY12("FKEY12", "FKEY12O", "X(10)", FKEY12_LENGTH, 668, 503, 24, 31, 1085);

        private final String label;

        private final String symbolicItemName;

        private final String picture;

        private final int length;

        private final int copybookLine;

        private final int mapsetLine;

        private final int screenRow;

        private final int screenColumn;

        private final int dataOffset;

        ScreenField(String label,
                    String symbolicItemName,
                    String picture,
                    int length,
                    int copybookLine,
                    int mapsetLine,
                    int screenRow,
                    int screenColumn,
                    int dataOffset) {
            this.label = label;
            this.symbolicItemName = symbolicItemName;
            this.picture = picture;
            this.length = length;
            this.copybookLine = copybookLine;
            this.mapsetLine = mapsetLine;
            this.screenRow = screenRow;
            this.screenColumn = screenColumn;
            this.dataOffset = dataOffset;
        }

        /**
         * The {@code DFHMDF} label, which is also this field's key in {@link #fieldValues()} and the name a
         * field-by-field diff reports.
         *
         * @return the label, verbatim
         */
        public String label() {
            return label;
        }

        /**
         * The symbolic-map payload item name as {@code app/cpy-bms/COACTUP.CPY} declares it - the
         * {@code xxxO} form, which is what makes this the output projection.
         *
         * @return the {@code xxxO} item name
         */
        public String symbolicItemName() {
            return symbolicItemName;
        }

        /**
         * The {@code PICTURE} as written.
         *
         * @return the picture string
         */
        public String picture() {
            return picture;
        }

        public boolean isAlphanumeric() {
            return picture.startsWith("X(");
        }

        /**
         * The declared width, which is both the {@code xxxO} {@code PICTURE} length and the
         * {@code DFHMDF LENGTH=}.
         *
         * @return the width in bytes
         */
        public int length() {
            return length;
        }

        /**
         * The line of the {@code xxxO} item in {@code app/cpy-bms/COACTUP.CPY}.
         *
         * @return the 1-based copybook line
         */
        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The line of the {@code DFHMDF} entry in {@code app/bms/COACTUP.bms}.
         *
         * @return the 1-based mapset line
         */
        public int mapsetLine() {
            return mapsetLine;
        }

        /**
         * The screen row from {@code POS=(row,column)}, between 1 and
         * {@value AccountUpdateResponse#SCREEN_ROWS}.
         *
         * @return the 1-based row
         */
        public int screenRow() {
            return screenRow;
        }

        /**
         * The screen column from {@code POS=(row,column)}, between 1 and
         * {@value AccountUpdateResponse#SCREEN_COLUMNS}.
         *
         * @return the 1-based column
         */
        public int screenColumn() {
            return screenColumn;
        }

        /**
         * The absolute 0-based offset of the {@code xxxO} payload item within the
         * {@value AccountUpdateResponse#GROUP_LENGTH}-byte group.
         *
         * @return the data offset
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * The 0-based offset of this field's {@code 02 FILLER PICTURE X(3)} prefix span.
         *
         * @return the prefix {@code FILLER}'s offset
         */
        public int prefixFillerOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxC PICTURE X} COLOR item - the byte
         * {@code CSSETATY} moves {@code DFHRED} into.
         *
         * @return the colour item's offset
         */
        public int colourItemOffset() {
            return prefixFillerOffset() + FIELD_PREFIX_FILLER_LENGTH;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxP PICTURE X} PS item.
         *
         * @return the programmed-symbols item's offset
         */
        public int psItemOffset() {
            return colourItemOffset() + COLOUR_ITEM_LENGTH;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxH PICTURE X} HILIGHT item.
         *
         * @return the highlight item's offset
         */
        public int hilightItemOffset() {
            return psItemOffset() + PS_ITEM_LENGTH;
        }

        /**
         * The 0-based offset of this field's {@code 02 xxxV PICTURE X} VALIDN item.
         *
         * @return the validation item's offset
         */
        public int validnItemOffset() {
            return hilightItemOffset() + HILIGHT_ITEM_LENGTH;
        }

        /**
         * The exclusive end offset of this field's payload item.
         *
         * @return {@code dataOffset() + length()}
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        public String colourItemName() {
            return label + COLOUR_ITEM_SUFFIX;
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
         * The {@code 02 FILLER PICTURE X(3)} descriptor that opens this field's stride.
         *
         * @return a {@code FILLER} span, never {@code null}
         */
        public FieldSpan prefixFillerSpan() {
            return FieldSpan.filler(prefixFillerOffset(), FIELD_PREFIX_FILLER_LENGTH);
        }

        /**
         * The {@code 02 xxxC PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxC}
         */
        public FieldSpan colourItemSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourItemOffset(), COLOUR_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxP PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxP}
         */
        public FieldSpan psItemSpan() {
            return FieldSpan.alphanumeric(psItemName(), psItemOffset(), PS_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxH PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxH}
         */
        public FieldSpan hilightItemSpan() {
            return FieldSpan.alphanumeric(hilightItemName(), hilightItemOffset(), HILIGHT_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxV PICTURE X} descriptor.
         *
         * @return an alphanumeric span named {@code xxxV}
         */
        public FieldSpan validnItemSpan() {
            return FieldSpan.alphanumeric(validnItemName(), validnItemOffset(), VALIDN_ITEM_LENGTH);
        }

        /**
         * The {@code 02 xxxO PIC X(n)} payload descriptor.
         *
         * @return an alphanumeric span named {@code xxxO}
         */
        public FieldSpan outputItemSpan() {
            return FieldSpan.alphanumeric(symbolicItemName, dataOffset, length);
        }

        /**
         * A one-line description naming every element of this field's provenance, for a failure message
         * that a reviewer can act on without opening the copybook.
         *
         * @return the description
         */
        public String describe() {
            return label + " (" + symbolicItemName + " " + picture + " at "
                    + "app/cpy-bms/COACTUP.CPY:" + copybookLine + ", app/bms/COACTUP.bms:" + mapsetLine
                    + ", POS=(" + screenRow + "," + screenColumn + "), group offset " + dataOffset + ")";
        }

        /**
         * The field carrying a {@code DFHMDF} label.
         *
         * @param label the label to look up; compared exactly, because {@code DFHMDF} labels are upper-case
         *     by construction
         * @return the matching field
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField ofLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look a field up");
            for (ScreenField candidate : values()) {
                if (candidate.label.equals(label)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("'" + label + "' is not one of the " + FIELD_COUNT
                    + " name-labelled DFHMDF fields of app/bms/COACTUP.bms. The 74 unnamed entries are "
                    + "screen literals and generate no symbolic-map item.");
        }
    }

    /**
     * The {@value #FIELD_COUNT} fields in symbolic-map order, unmodifiable.
     */
    public static final List<ScreenField> FIELDS =
            Collections.unmodifiableList(new ArrayList<>(List.of(ScreenField.values())));

    /**
     * The {@link #GROUP_LENGTH}-byte {@code CACTUPAO} layout, declared span by span.
     */
    public static final RecordLayout OUTPUT_GROUP_LAYOUT = declareOutputGroupLayout();

    private static RecordLayout declareOutputGroupLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + FIELD_COUNT * 6);
        spans.add(FieldSpan.filler(0, TIOAPFX_LENGTH));
        for (ScreenField field : ScreenField.values()) {
            spans.add(field.prefixFillerSpan());
            spans.add(field.colourItemSpan());
            spans.add(field.psItemSpan());
            spans.add(field.hilightItemSpan());
            spans.add(field.validnItemSpan());
            spans.add(field.outputItemSpan());
        }
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    /**
     * One field's four extended-attribute items: {@code xxxC}, {@code xxxP}, {@code xxxH}, {@code xxxV}.
     */
    public static final class FieldAttributes {
        /**
         * The unset attribute byte: LOW-VALUES, binary zero.
         */
        public static final byte UNSET = LOW_VALUE_BYTE;

        private byte colour;

        private byte ps;

        private byte hilight;

        private byte validn;

        /**
         * A quad at its post-{@code MOVE LOW-VALUES} state: all four items binary zero.
         */
        public FieldAttributes() {
            this(UNSET, UNSET, UNSET, UNSET);
        }

        public FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
            this.colour = colour;
            this.ps = ps;
            this.hilight = hilight;
            this.validn = validn;
        }

        /**
         * A copy, so that deriving one response from another does not alias the other's quads.
         *
         * @param other the quad to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A quad to copy is required");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        public byte getColour() {
            return colour;
        }

        /**
         * Sets the COLOR byte - the destination {@code CSSETATY} moves {@link BmsAttributes#DFHRED} to.
         *
         * @param colour the colour byte
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

        /**
         * Sets the HILIGHT byte - {@link BmsAttributes#DFHBLINK}, {@link BmsAttributes#DFHREVRS} and
         * {@link BmsAttributes#DFHUNDLN} are the values a 3270 recognises.
         *
         * @param hilight the highlight byte
         */
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
         * Returns all four items to LOW-VALUES, which is what {@code MOVE LOW-VALUES TO CACTUPAO}
         * [{@code app/cbl/COACTUPC.cbl:2668}] does to them at the top of every send.
         */
        public void resetToLowValues() {
            this.colour = UNSET;
            this.ps = UNSET;
            this.hilight = UNSET;
            this.validn = UNSET;
        }

        /**
         * Whether the COLOR byte carries {@link BmsAttributes#DFHRED} - that is, whether {@code CSSETATY}
         * has flagged this field as in error or blank while in REENTER context.
         *
         * @return {@code true} when the colour item is red
         */
        public boolean isRedHighlighted() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether the COLOR byte is the default, {@link BmsAttributes#DFHDFCOL}.
         *
         * @return {@code true} when the colour item is the default colour
         */
        public boolean isDefaultColour() {
            return colour == BmsAttributes.DFHDFCOL;
        }

        /**
         * Whether the HILIGHT byte asks for a visible emphasis rather than the default.
         *
         * @return {@code true} when the highlight item is not the default
         */
        public boolean isHighlighted() {
            return hilight != BmsAttributes.DFHDFHI;
        }

        /**
         * A rendering of all four items as mnemonics where {@link BmsAttributes} knows one and as hex where
         * it does not.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "[C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn) + ']';
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes that)) {
                return false;
            }
            return colour == that.colour && ps == that.ps
                    && hilight == that.hilight && validn == that.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }
    }

    // That matters for correctness, not brevity: a field added to the mapset cannot then be left out of one
    // of those operations by omission.

    private final Map<ScreenField, String> values;

    private final Map<ScreenField, FieldAttributes> attributes;

    private final String nextProgram;

    private final String nextMapset;

    private final String nextMap;

    private final CommArea commArea;

    /**
     * The sealed form of {@link #commArea}, which is the only form that crosses the wire.
     *
     * <p>{@code WS-THIS-PROGCOMMAREA} is the program's own storage rather than screen data, and its first
     * byte records that this screen's twenty-four edits already passed - which is why
     * {@code app/cbl/COACTUPC.cbl:1463-1468} skips every one of them when it reads
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED} and {@code :2602-2604} writes on {@code PF5}. CICS passes that
     * area; a terminal never sees it. Publishing it as structured JSON would hand a caller the confirmation
     * to compose and the old snapshot the concurrency check compares, so it travels as one opaque token
     * from {@link ConversationStateSeal} instead. The bytes inside are the exact
     * {@value CommArea#RECORD_LENGTH}, and {@link #getCommArea()} still answers the structured value to a
     * parity case.
     *
     * <p>Empty when the program carried no state on this path - a bare transfer hands on no area at all.
     * Never {@code null}.
     */
    private final String stateToken;

    /** The 213-byte {@code CVCRD01Y} work area. */
    private final CardScreenState cardScreenState;

    private final NavigationContext navigationContext;

    private AccountUpdateResponse(Builder builder) {
        Map<ScreenField, String> collected = new EnumMap<>(ScreenField.class);
        Map<ScreenField, FieldAttributes> quads = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            String supplied = builder.values.get(field);
            collected.put(field, supplied == null ? lowValues(field.length()) : supplied);
            FieldAttributes quad = builder.attributes.get(field);
            quads.put(field, quad == null ? new FieldAttributes() : new FieldAttributes(quad));
        }
        this.values = collected;
        this.attributes = quads;
        this.nextProgram = builder.nextProgram == null
                ? lowValues(NEXT_PROGRAM_LENGTH) : builder.nextProgram;
        this.nextMapset = builder.nextMapset == null
                ? lowValues(NEXT_MAPSET_LENGTH) : builder.nextMapset;
        this.nextMap = builder.nextMap == null ? lowValues(NEXT_MAP_LENGTH) : builder.nextMap;
        this.commArea = builder.commArea == null ? CommArea.initialised() : builder.commArea;
        this.stateToken = builder.stateToken == null ? "" : builder.stateToken;
        this.cardScreenState = builder.cardScreenState == null
                ? new CardScreenState() : new CardScreenState(builder.cardScreenState);
        this.navigationContext = builder.navigationContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The response as it stands immediately after {@code MOVE LOW-VALUES TO CACTUPAO}
     * [{@code app/cbl/COACTUPC.cbl:2668}]: all {@value #FIELD_COUNT} fields at their declared widths in
     * LOW-VALUES, all {@value #FIELD_COUNT} attribute quads unset, no navigation target, an initialised
     * communication area and an initialised card work area.
     *
     * @return the initial response; never {@code null}
     */
    public static AccountUpdateResponse initial() {
        return new Builder().build();
    }

    /**
     * The response {@code 3100-SCREEN-INIT} produces: the LOW-VALUES baseline, then the two titles, the
     * transaction identifier, the program name, the date and the time
     * [{@code app/cbl/COACTUPC.cbl:2668-2690}], then the three function-key legends the mapset declares as
     * {@code INITIAL} values.
     *
     * @param dateHeader the captured date and time, supplying the {@code MM/DD/YY} and {@code HH:MM:SS}
     *     renderings
     * @return the initialised response
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public static AccountUpdateResponse screenInit(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required for 3100-SCREEN-INIT: it supplies "
                + "the WS-CURDATE-MM-DD-YY and WS-CURTIME-HH-MM-SS renderings moved at "
                + "app/cbl/COACTUPC.cbl:2684 and :2690");
        return initial()
                .withScreenTitles()
                .withDateTimeHeader(dateHeader)
                .withFunctionKeyLegends();
    }

    /**
     * A builder pre-loaded with this response, for deriving a variant.
     *
     * @return a builder carrying every field, quad and carrier of this response
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, values.get(field));
            builder.attributes(field, new FieldAttributes(attributes.get(field)));
        }
        return builder.nextProgram(nextProgram)
                .nextMapset(nextMapset)
                .nextMap(nextMap)
                .commArea(commArea)
                .stateToken(stateToken)
                .cardScreenState(cardScreenState)
                .navigationContext(navigationContext);
    }

    /**
     * A string of {@code length} spaces - what a {@code PIC X} field pads with.
     *
     * @param length how many spaces; never negative
     * @return exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide, so "
                    + "there is no such thing as " + length + " spaces");
        }
        return String.valueOf(SPACE).repeat(length);
    }

    /**
     * A string of {@code length} LOW-VALUES characters - the state {@code MOVE LOW-VALUES TO CACTUPAO}
     * leaves a field in.
     *
     * @param length how many characters; never negative
     * @return exactly {@code length} {@code U+0000} characters
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        if (length < 0) {
            throw new IllegalArgumentException("A field cannot be " + length + " characters wide, so "
                    + "there is no such thing as " + length + " LOW-VALUES characters");
        }
        return ScreenFieldImage.unpainted(length);
    }

    /**
     * The declared width of a field, without needing an instance.
     *
     * @param field the item the value belongs to
     * @return its {@code xxxO} {@code PICTURE} width
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public static int declaredLength(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to report a declared length");
        return field.length();
    }

    /**
     * {@code TRNNAMEO PIC X(4)} - see {@link ScreenField#TRNNAME} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = TRNNAME_LENGTH)
    public String getTrnname() {
        return values.get(ScreenField.TRNNAME);
    }
    /**
     * {@code TITLE01O PIC X(40)} - see {@link ScreenField#TITLE01} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = TITLE01_LENGTH)
    public String getTitle01() {
        return values.get(ScreenField.TITLE01);
    }
    /**
     * {@code CURDATEO PIC X(8)} - see {@link ScreenField#CURDATE} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = CURDATE_LENGTH)
    public String getCurdate() {
        return values.get(ScreenField.CURDATE);
    }
    /**
     * {@code PGMNAMEO PIC X(8)} - see {@link ScreenField#PGMNAME} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = PGMNAME_LENGTH)
    public String getPgmname() {
        return values.get(ScreenField.PGMNAME);
    }
    /**
     * {@code TITLE02O PIC X(40)} - see {@link ScreenField#TITLE02} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = TITLE02_LENGTH)
    public String getTitle02() {
        return values.get(ScreenField.TITLE02);
    }
    /**
     * {@code CURTIMEO PIC X(8)} - see {@link ScreenField#CURTIME} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = CURTIME_LENGTH)
    public String getCurtime() {
        return values.get(ScreenField.CURTIME);
    }
    /**
     * {@code ACCTSIDO PIC X(11)} - see {@link ScreenField#ACCTSID} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACCTSID_LENGTH)
    public String getAcctsid() {
        return values.get(ScreenField.ACCTSID);
    }
    /**
     * {@code ACSTTUSO PIC X(1)} - see {@link ScreenField#ACSTTUS} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSTTUS_LENGTH)
    public String getAcsttus() {
        return values.get(ScreenField.ACSTTUS);
    }
    /**
     * {@code OPNYEARO PIC X(4)} - see {@link ScreenField#OPNYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = OPNYEAR_LENGTH)
    public String getOpnyear() {
        return values.get(ScreenField.OPNYEAR);
    }
    /**
     * {@code OPNMONO PIC X(2)} - see {@link ScreenField#OPNMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = OPNMON_LENGTH)
    public String getOpnmon() {
        return values.get(ScreenField.OPNMON);
    }
    /**
     * {@code OPNDAYO PIC X(2)} - see {@link ScreenField#OPNDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = OPNDAY_LENGTH)
    public String getOpnday() {
        return values.get(ScreenField.OPNDAY);
    }
    /**
     * {@code ACRDLIMO PIC X(15)} - see {@link ScreenField#ACRDLIM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACRDLIM_LENGTH)
    public String getAcrdlim() {
        return values.get(ScreenField.ACRDLIM);
    }
    /**
     * {@code EXPYEARO PIC X(4)} - see {@link ScreenField#EXPYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = EXPYEAR_LENGTH)
    public String getExpyear() {
        return values.get(ScreenField.EXPYEAR);
    }
    /**
     * {@code EXPMONO PIC X(2)} - see {@link ScreenField#EXPMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = EXPMON_LENGTH)
    public String getExpmon() {
        return values.get(ScreenField.EXPMON);
    }
    /**
     * {@code EXPDAYO PIC X(2)} - see {@link ScreenField#EXPDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = EXPDAY_LENGTH)
    public String getExpday() {
        return values.get(ScreenField.EXPDAY);
    }
    /**
     * {@code ACSHLIMO PIC X(15)} - see {@link ScreenField#ACSHLIM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSHLIM_LENGTH)
    public String getAcshlim() {
        return values.get(ScreenField.ACSHLIM);
    }
    /**
     * {@code RISYEARO PIC X(4)} - see {@link ScreenField#RISYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = RISYEAR_LENGTH)
    public String getRisyear() {
        return values.get(ScreenField.RISYEAR);
    }
    /**
     * {@code RISMONO PIC X(2)} - see {@link ScreenField#RISMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = RISMON_LENGTH)
    public String getRismon() {
        return values.get(ScreenField.RISMON);
    }
    /**
     * {@code RISDAYO PIC X(2)} - see {@link ScreenField#RISDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = RISDAY_LENGTH)
    public String getRisday() {
        return values.get(ScreenField.RISDAY);
    }
    /**
     * {@code ACURBALO PIC X(15)} - see {@link ScreenField#ACURBAL} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACURBAL_LENGTH)
    public String getAcurbal() {
        return values.get(ScreenField.ACURBAL);
    }
    /**
     * {@code ACRCYCRO PIC X(15)} - see {@link ScreenField#ACRCYCR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACRCYCR_LENGTH)
    public String getAcrcycr() {
        return values.get(ScreenField.ACRCYCR);
    }
    /**
     * {@code AADDGRPO PIC X(10)} - see {@link ScreenField#AADDGRP} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = AADDGRP_LENGTH)
    public String getAaddgrp() {
        return values.get(ScreenField.AADDGRP);
    }
    /**
     * {@code ACRCYDBO PIC X(15)} - see {@link ScreenField#ACRCYDB} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACRCYDB_LENGTH)
    public String getAcrcydb() {
        return values.get(ScreenField.ACRCYDB);
    }
    /**
     * {@code ACSTNUMO PIC X(9)} - see {@link ScreenField#ACSTNUM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSTNUM_LENGTH)
    public String getAcstnum() {
        return values.get(ScreenField.ACSTNUM);
    }
    /**
     * {@code ACTSSN1O PIC X(3)} - see {@link ScreenField#ACTSSN1} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACTSSN1_LENGTH)
    public String getActssn1() {
        return values.get(ScreenField.ACTSSN1);
    }
    /**
     * {@code ACTSSN2O PIC X(2)} - see {@link ScreenField#ACTSSN2} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACTSSN2_LENGTH)
    public String getActssn2() {
        return values.get(ScreenField.ACTSSN2);
    }
    /**
     * {@code ACTSSN3O PIC X(4)} - see {@link ScreenField#ACTSSN3} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACTSSN3_LENGTH)
    public String getActssn3() {
        return values.get(ScreenField.ACTSSN3);
    }
    /**
     * {@code DOBYEARO PIC X(4)} - see {@link ScreenField#DOBYEAR} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = DOBYEAR_LENGTH)
    public String getDobyear() {
        return values.get(ScreenField.DOBYEAR);
    }
    /**
     * {@code DOBMONO PIC X(2)} - see {@link ScreenField#DOBMON} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = DOBMON_LENGTH)
    public String getDobmon() {
        return values.get(ScreenField.DOBMON);
    }
    /**
     * {@code DOBDAYO PIC X(2)} - see {@link ScreenField#DOBDAY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = DOBDAY_LENGTH)
    public String getDobday() {
        return values.get(ScreenField.DOBDAY);
    }
    /**
     * {@code ACSTFCOO PIC X(3)} - see {@link ScreenField#ACSTFCO} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSTFCO_LENGTH)
    public String getAcstfco() {
        return values.get(ScreenField.ACSTFCO);
    }
    /**
     * {@code ACSFNAMO PIC X(25)} - see {@link ScreenField#ACSFNAM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSFNAM_LENGTH)
    public String getAcsfnam() {
        return values.get(ScreenField.ACSFNAM);
    }
    /**
     * {@code ACSMNAMO PIC X(25)} - see {@link ScreenField#ACSMNAM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSMNAM_LENGTH)
    public String getAcsmnam() {
        return values.get(ScreenField.ACSMNAM);
    }
    /**
     * {@code ACSLNAMO PIC X(25)} - see {@link ScreenField#ACSLNAM} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSLNAM_LENGTH)
    public String getAcslnam() {
        return values.get(ScreenField.ACSLNAM);
    }
    /**
     * {@code ACSADL1O PIC X(50)} - see {@link ScreenField#ACSADL1} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSADL1_LENGTH)
    public String getAcsadl1() {
        return values.get(ScreenField.ACSADL1);
    }
    /**
     * {@code ACSSTTEO PIC X(2)} - see {@link ScreenField#ACSSTTE} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSSTTE_LENGTH)
    public String getAcsstte() {
        return values.get(ScreenField.ACSSTTE);
    }
    /**
     * {@code ACSADL2O PIC X(50)} - see {@link ScreenField#ACSADL2} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSADL2_LENGTH)
    public String getAcsadl2() {
        return values.get(ScreenField.ACSADL2);
    }
    /**
     * {@code ACSZIPCO PIC X(5)} - see {@link ScreenField#ACSZIPC} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSZIPC_LENGTH)
    public String getAcszipc() {
        return values.get(ScreenField.ACSZIPC);
    }
    /**
     * {@code ACSCITYO PIC X(50)} - see {@link ScreenField#ACSCITY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSCITY_LENGTH)
    public String getAcscity() {
        return values.get(ScreenField.ACSCITY);
    }
    /**
     * {@code ACSCTRYO PIC X(3)} - see {@link ScreenField#ACSCTRY} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSCTRY_LENGTH)
    public String getAcsctry() {
        return values.get(ScreenField.ACSCTRY);
    }
    /**
     * {@code ACSPH1AO PIC X(3)} - see {@link ScreenField#ACSPH1A} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH1A_LENGTH)
    public String getAcsph1a() {
        return values.get(ScreenField.ACSPH1A);
    }
    /**
     * {@code ACSPH1BO PIC X(3)} - see {@link ScreenField#ACSPH1B} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH1B_LENGTH)
    public String getAcsph1b() {
        return values.get(ScreenField.ACSPH1B);
    }
    /**
     * {@code ACSPH1CO PIC X(4)} - see {@link ScreenField#ACSPH1C} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH1C_LENGTH)
    public String getAcsph1c() {
        return values.get(ScreenField.ACSPH1C);
    }
    /**
     * {@code ACSGOVTO PIC X(20)} - see {@link ScreenField#ACSGOVT} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSGOVT_LENGTH)
    public String getAcsgovt() {
        return values.get(ScreenField.ACSGOVT);
    }
    /**
     * {@code ACSPH2AO PIC X(3)} - see {@link ScreenField#ACSPH2A} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH2A_LENGTH)
    public String getAcsph2a() {
        return values.get(ScreenField.ACSPH2A);
    }
    /**
     * {@code ACSPH2BO PIC X(3)} - see {@link ScreenField#ACSPH2B} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH2B_LENGTH)
    public String getAcsph2b() {
        return values.get(ScreenField.ACSPH2B);
    }
    /**
     * {@code ACSPH2CO PIC X(4)} - see {@link ScreenField#ACSPH2C} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPH2C_LENGTH)
    public String getAcsph2c() {
        return values.get(ScreenField.ACSPH2C);
    }
    /**
     * {@code ACSEFTCO PIC X(10)} - see {@link ScreenField#ACSEFTC} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSEFTC_LENGTH)
    public String getAcseftc() {
        return values.get(ScreenField.ACSEFTC);
    }
    /**
     * {@code ACSPFLGO PIC X(1)} - see {@link ScreenField#ACSPFLG} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ACSPFLG_LENGTH)
    public String getAcspflg() {
        return values.get(ScreenField.ACSPFLG);
    }
    /**
     * {@code INFOMSGO PIC X(45)} - see {@link ScreenField#INFOMSG} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = INFOMSG_LENGTH)
    public String getInfomsg() {
        return values.get(ScreenField.INFOMSG);
    }
    /**
     * {@code ERRMSGO PIC X(78)} - see {@link ScreenField#ERRMSG} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = ERRMSG_LENGTH)
    public String getErrmsg() {
        return values.get(ScreenField.ERRMSG);
    }
    /**
     * {@code FKEYSO PIC X(21)} - see {@link ScreenField#FKEYS} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = FKEYS_LENGTH)
    public String getFkeys() {
        return values.get(ScreenField.FKEYS);
    }
    /**
     * {@code FKEY05O PIC X(7)} - see {@link ScreenField#FKEY05} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = FKEY05_LENGTH)
    public String getFkey05() {
        return values.get(ScreenField.FKEY05);
    }
    /**
     * {@code FKEY12O PIC X(10)} - see {@link ScreenField#FKEY12} for the full trace.
     *
     * @return the value as stored, at whatever width it was supplied; never {@code null}
     */
    @Size(max = FKEY12_LENGTH)
    public String getFkey12() {
        return values.get(ScreenField.FKEY12);
    }

    @JsonIgnore
    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read a value");
        return values.get(field);
    }

    /**
     * This response with one field replaced, everything else carried over.
     *
     * @param field the field to replace
     * @param value the new value; {@code null} restores the field's declared width in LOW-VALUES
     * @return a new response; this one is unchanged
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateResponse withValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A field is required to replace a value");
        return toBuilder().value(field, value).build();
    }

    /**
     * All {@value #FIELD_COUNT} values keyed by {@code DFHMDF} label, in copybook order.
     *
     * @return an unmodifiable, insertion-ordered map
     */
    @JsonIgnore
    public Map<String, String> fieldValues() {
        Map<String, String> rendered = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            rendered.put(field.label(), values.get(field));
        }
        return Collections.unmodifiableMap(rendered);
    }

    @JsonIgnore
    public FieldAttributes attributes(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read an attribute quad");
        return attributes.get(field);
    }

    @JsonIgnore
    public Map<ScreenField, FieldAttributes> attributeQuads() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Returns every quad to LOW-VALUES, which is what {@code MOVE LOW-VALUES TO CACTUPAO}
     * [{@code app/cbl/COACTUPC.cbl:2668}] does to all {@value #FIELD_COUNT} of them.
     */
    public void resetAttributeQuads() {
        for (ScreenField field : ScreenField.values()) {
            attributes.get(field).resetToLowValues();
        }
    }

    /**
     * Applies {@code CSSETATY} to one field: the decision is {@link FieldAttributeSetter}'s, the two
     * destinations are this type's.
     *
     * @param field the field being edited
     * @param state its validation state, from the program's {@code FLG-xxx} condition names
     * @param reenter whether {@code CDEMO-PGM-REENTER} holds
     * @return a response with the asterisk applied where the copybook applies it; the same values otherwise
     * @throws NullPointerException if {@code field} or {@code state} is {@code null}
     */
    public AccountUpdateResponse applyHighlight(ScreenField field,
                                                FieldValidationState state,
                                                boolean reenter) {
        Objects.requireNonNull(field, "A field is required to apply CSSETATY");
        Objects.requireNonNull(state, "A validation state is required to apply CSSETATY; it is what the "
                + "program's FLG-xxx-NOT-OK and FLG-xxx-BLANK condition names carry");
        FieldHighlight highlight =
                FieldAttributeSetter.resolve(state, reenter, field.label(), MAP_NAME);
        if (highlight.colourItemAssigned()) {
            attributes.get(field).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            return withValue(field, highlight.outputItemValue());
        }
        return this;
    }

    /**
     * The two title moves of {@code 3100-SCREEN-INIT}: {@link ScreenTitles#CCDA_TITLE01} into
     * {@code TITLE01O} [{@code app/cbl/COACTUPC.cbl:2673}] and {@link ScreenTitles#CCDA_TITLE02} into
     * {@code TITLE02O} [{@code :2674}], plus {@code LIT-THISTRANID} into {@code TRNNAMEO} [{@code :2675}]
     * and {@code LIT-THISPGM} into {@code PGMNAMEO} [{@code :2676}].
     *
     * @return a response carrying the four header values
     */
    public AccountUpdateResponse withScreenTitles() {
        return toBuilder()
                .title01(ScreenTitles.CCDA_TITLE01)
                .title02(ScreenTitles.CCDA_TITLE02)
                .trnname(THIS_TRANSACTION)
                .pgmname(THIS_PROGRAM)
                .build();
    }

    /**
     * The date and time moves of {@code 3100-SCREEN-INIT}: {@code WS-CURDATE-MM-DD-YY} into
     * {@code CURDATEO} [{@code app/cbl/COACTUPC.cbl:2684}] and {@code WS-CURTIME-HH-MM-SS} into
     * {@code CURTIMEO} [{@code :2690}].
     *
     * @param dateHeader the captured date and time
     * @return a response carrying the date and time header
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public AccountUpdateResponse withDateTimeHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required: it supplies the MM/DD/YY and "
                + "HH:MM:SS renderings moved at app/cbl/COACTUPC.cbl:2684 and :2690");
        return toBuilder()
                .curdate(dateHeader.wsCurdateMmDdYy())
                .curtime(dateHeader.wsCurtimeHhMmSs())
                .build();
    }

    /**
     * The three function-key legends the mapset declares as {@code INITIAL} values.
     *
     * @return a response carrying the three legends at their mapset-declared values
     */
    public AccountUpdateResponse withFunctionKeyLegends() {
        return toBuilder()
                .fkeys(FKEYS_LEGEND)
                .fkey05(FKEY05_LEGEND)
                .fkey12(FKEY12_LEGEND)
                .build();
    }

    /**
     * Reveals the {@code F5=Save} legend by moving {@link BmsAttributes#DFHBMASB} into {@code FKEY05}'s
     * highlight item - {@code app/cbl/COACTUPC.cbl:3579}, taken when {@code PROMPT-FOR-CONFIRMATION} holds.
     */
    public void revealSaveLegend() {
        attributes.get(ScreenField.FKEY05).setHilight(BmsAttributes.DFHBMASB);
    }

    /**
     * Reveals the {@code F12=Cancel} legend - {@code app/cbl/COACTUPC.cbl:3575}, taken when changes have
     * been made but are not yet done, and again at {@code :3580} when confirmation is prompted for.
     */
    public void revealCancelLegend() {
        attributes.get(ScreenField.FKEY12).setHilight(BmsAttributes.DFHBMASB);
    }

    @JsonIgnore
    public boolean isSaveLegendRevealed() {
        return attributes.get(ScreenField.FKEY05).getHilight() == BmsAttributes.DFHBMASB;
    }

    @JsonIgnore
    public boolean isCancelLegendRevealed() {
        return attributes.get(ScreenField.FKEY12).getHilight() == BmsAttributes.DFHBMASB;
    }

    /**
     * Sets the informational-message line's visibility the way {@code 3390-SETUP-INFOMSG-ATTRS} does:
     * {@link BmsAttributes#DFHBMDAR} when there is no message and {@link BmsAttributes#DFHBMASB} when there
     * is [{@code app/cbl/COACTUPC.cbl:3567-3571}].
     *
     * @param hasInfoMessage {@code true} when a message is present, so the line should be visible
     */
    public void applyInfoMessageVisibility(boolean hasInfoMessage) {
        attributes.get(ScreenField.INFOMSG)
                .setHilight(hasInfoMessage ? BmsAttributes.DFHBMASB : BmsAttributes.DFHBMDAR);
    }

    /**
     * {@code CDEMO-TO-PROGRAM} - the program {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}
     * [{@code app/cbl/COACTUPC.cbl:956-958}] would have transferred to.
     *
     * @return eight characters; never {@code null}
     */
    @Size(max = NEXT_PROGRAM_LENGTH)
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * The mapset the client should ask for next.
     *
     * @return seven characters; never {@code null}
     */
    @Size(max = NEXT_MAPSET_LENGTH)
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * The map the client should ask for next - {@code CDEMO-LAST-MAP PIC X(7)}, moved at
     * {@code app/cbl/COACTUPC.cbl:950}, and the same seven characters {@code 3400-SEND-SCREEN} puts in
     * {@code CCARD-NEXT-MAP} at {@code :3592}.
     *
     * @return seven characters; never {@code null}
     */
    @Size(max = NEXT_MAP_LENGTH)
    public String getNextMap() {
        return nextMap;
    }

    /**
     * This response naming a different {@code XCTL} target.
     *
     * @param program the next program; {@code null} clears it to LOW-VALUES
     * @param mapset the next mapset; {@code null} clears it to LOW-VALUES
     * @param map the next map; {@code null} clears it to LOW-VALUES
     * @return a new response
     */
    public AccountUpdateResponse withNextTarget(String program, String mapset, String map) {
        return toBuilder().nextProgram(program).nextMapset(mapset).nextMap(map).build();
    }

    /**
     * This response naming this screen as the next target - what {@code 3400-SEND-SCREEN} does at
     * {@code app/cbl/COACTUPC.cbl:3591-3592} when it re-paints rather than transferring.
     *
     * <p>The mapset is {@link #MAPSET_NAME}, the seven-character form, not the eight-character
     * {@link #THIS_MAPSET_LITERAL} - the same truncation the COBOL move performs.
     *
     * @return a new response pointing back at {@code CACTUPA}
     */
    public AccountUpdateResponse withSelfAsNextTarget() {
        return withNextTarget(THIS_PROGRAM, MAPSET_NAME, MAP_NAME);
    }

    /**
     * The {@value CommArea#RECORD_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA}
     * [{@code app/cbl/COACTUPC.cbl:652}], echoed so the client can send it back on the next turn.
     *
     * @return the communication area; never {@code null}
     */
    @JsonIgnore
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * The sealed communication area, as it crosses the wire.
     *
     * @return the token, empty when this path carried no area; never {@code null}
     */
    public String getStateToken() {
        return stateToken;
    }

    /**
     * This response carrying a different sealed communication area.
     *
     * @param replacement the token; {@code null} becomes empty
     * @return a new response
     */
    public AccountUpdateResponse withStateToken(String replacement) {
        return toBuilder().stateToken(replacement).build();
    }

    /**
     * This response carrying a different communication area.
     *
     * @param replacement the area; {@code null} restores {@link CommArea#initialised()}, which is what
     *     {@code INITIALIZE WS-THIS-PROGCOMMAREA} does at {@code app/cbl/COACTUPC.cbl:968} and {@code :981}
     * @return a new response
     */
    public AccountUpdateResponse withCommArea(CommArea replacement) {
        return toBuilder().commArea(replacement).build();
    }

    /**
     * The {@value CardScreenState#RECORD_LENGTH}-byte {@code CVCRD01Y} work area.
     *
     * @return a copy of the card work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * This response carrying a different card work area.
     *
     * @param replacement the work area; {@code null} restores a fresh one
     * @return a new response
     */
    public AccountUpdateResponse withCardScreenState(CardScreenState replacement) {
        return toBuilder().cardScreenState(replacement).build();
    }

    /**
     * The {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA}, or {@code null} when
     * none travelled - which is the {@code EIBCALEN IS EQUAL TO 0} case at
     * {@code app/cbl/COACTUPC.cbl:880}.
     *
     * @return the navigation context, or {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * This response carrying a different navigation context.
     *
     * @param replacement the context, or {@code null} for none
     * @return a new response
     */
    public AccountUpdateResponse withNavigationContext(NavigationContext replacement) {
        return toBuilder().navigationContext(replacement).build();
    }

    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The bytes this response would fill of {@code WS-COMMAREA PIC X(2000)}: the communication area always,
     * plus the navigation context when one travelled.
     *
     * @return {@link #TOTAL_COMMAREA_LENGTH} when a context is present, otherwise
     *     {@value CommArea#RECORD_LENGTH}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext()
                ? TOTAL_COMMAREA_LENGTH
                : CommArea.RECORD_LENGTH;
    }

    /**
     * Whether {@code CDEMO-PGM-ENTER} holds - the first-entry path, on which the screen is painted and no
     * highlight is applied.
     *
     * @return {@code true} when a context is present and reports ENTER
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether {@code CDEMO-PGM-REENTER} holds - the re-entry path, on which {@code CSSETATY} applies its
     * highlight.
     *
     * @return {@code true} when a context is present and reports REENTER
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    /**
     * The {@value Details#RECORD_LENGTH}-byte {@code ACUP-OLD-DETAILS} before-image.
     *
     * @return the OLD details; never {@code null}
     */
    @JsonIgnore
    public Details oldDetails() {
        return commArea.oldDetails();
    }

    /**
     * The {@value Details#RECORD_LENGTH}-byte {@code ACUP-NEW-DETAILS} after-image.
     *
     * @return the NEW details; never {@code null}
     */
    @JsonIgnore
    public Details newDetails() {
        return commArea.newDetails();
    }

    /**
     * The {@value AcctSnapshot#RECORD_LENGTH}-byte account half of the before-image.
     *
     * <p>The {@code EXPIRAION} misspelling of {@code ACUP-OLD-EXPIRAION-DATE}
     * [{@code app/cbl/COACTUPC.cbl:690}] is preserved verbatim in {@link AcctSnapshot#expiraionDate()}.
     *
     * @return the OLD account snapshot; never {@code null}
     */
    @JsonIgnore
    public AcctSnapshot oldAcct() {
        return commArea.oldDetails().acct();
    }

    /**
     * The {@value CustSnapshot#RECORD_LENGTH}-byte customer half of the before-image.
     *
     * @return the OLD customer snapshot; never {@code null}
     */
    @JsonIgnore
    public CustSnapshot oldCust() {
        return commArea.oldDetails().cust();
    }

    @JsonIgnore
    public AcctSnapshot newAcct() {
        return commArea.newDetails().acct();
    }

    /**
     * The customer half of the after-image, whose SSN is the three-part group.
     *
     * @return the NEW customer snapshot; never {@code null}
     */
    @JsonIgnore
    public CustSnapshot newCust() {
        return commArea.newDetails().cust();
    }

    /**
     * Which of the two prefixes the before-image declares - {@link DetailGroup#OLD}, always.
     *
     * @return the OLD group marker
     */
    @JsonIgnore
    public DetailGroup oldDetailGroup() {
        return commArea.oldDetails().group();
    }

    /**
     * Which of the two prefixes the after-image declares - {@link DetailGroup#NEW}, always.
     *
     * @return the NEW group marker
     */
    @JsonIgnore
    public DetailGroup newDetailGroup() {
        return commArea.newDetails().group();
    }

    /**
     * The one-byte {@code ACUP-CHANGE-ACTION} through which {@code AccountUpdateService} signals its
     * outcome.
     *
     * @return the change action; never {@code null}
     */
    @JsonIgnore
    public ChangeAction changeAction() {
        return commArea.changeAction();
    }

    public AccountUpdateResponse withChangeAction(ChangeAction replacement) {
        Objects.requireNonNull(replacement, "A change action is required; ACUP-CHANGE-ACTION is PIC X(1) "
                + "and always holds one of the nine states, LOW-VALUES included");
        return withCommArea(commArea.withChangeAction(replacement));
    }

    /**
     * {@code 88 ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} [{@code app/cbl/COACTUPC.cbl:656-658}]
     * - nothing has been read yet.
     *
     * @return {@code true} in the not-fetched state
     */
    @JsonIgnore
    public boolean isDetailsNotFetched() {
        return changeAction().isDetailsNotFetched();
    }

    /**
     * {@code 88 ACUP-SHOW-DETAILS VALUE 'S'} [{@code app/cbl/COACTUPC.cbl:659}].
     *
     * @return {@code true} in the show-details state
     */
    @JsonIgnore
    public boolean isShowDetails() {
        return changeAction().isShowDetails();
    }

    /**
     * {@code 88 ACUP-CHANGES-MADE VALUES 'E', 'N', 'C', 'L', 'F'} [{@code app/cbl/COACTUPC.cbl:660-662}].
     *
     * @return {@code true} when changes have been made, in any of the five senses
     */
    @JsonIgnore
    public boolean isChangesMade() {
        return changeAction().isChangesMade();
    }

    /**
     * {@code 88 ACUP-CHANGES-NOT-OK VALUE 'E'} [{@code app/cbl/COACTUPC.cbl:663}] - the edits rejected the
     * input.
     *
     * @return {@code true} in the changes-not-OK state
     */
    @JsonIgnore
    public boolean isChangesNotOk() {
        return changeAction().isChangesNotOk();
    }

    /**
     * {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} [{@code app/cbl/COACTUPC.cbl:664}] - the state in
     * which F5 becomes valid, per the AID guard at {@code :908}.
     *
     * @return {@code true} in the OK-but-unconfirmed state
     */
    @JsonIgnore
    public boolean isChangesOkNotConfirmed() {
        return changeAction().isChangesOkNotConfirmed();
    }

    /**
     * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} [{@code app/cbl/COACTUPC.cbl:665}] - written
     * successfully.
     *
     * @return {@code true} in the done state
     */
    @JsonIgnore
    public boolean isChangesOkayedAndDone() {
        return changeAction().isChangesOkayedAndDone();
    }

    /**
     * {@code 88 ACUP-CHANGES-FAILED VALUES 'L', 'F'} [{@code app/cbl/COACTUPC.cbl:666}] - the second
     * grouping.
     *
     * @return {@code true} in either failure state
     */
    @JsonIgnore
    public boolean isChangesFailed() {
        return changeAction().isChangesFailed();
    }

    /**
     * {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} [{@code app/cbl/COACTUPC.cbl:667}] - the
     * {@code COULD-NOT-LOCK-ACCT-FOR-UPDATE} and {@code COULD-NOT-LOCK-CUST-FOR-UPDATE} paths.
     *
     * @return {@code true} in the lock-error state
     */
    @JsonIgnore
    public boolean isChangesOkayedLockError() {
        return changeAction().isChangesOkayedLockError();
    }

    /**
     * {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} [{@code app/cbl/COACTUPC.cbl:668}] - the
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} outcome of {@code 9700-CHECK-CHANGE-IN-REC} [{@code :4143},
     * {@code :4189}].
     *
     * @return {@code true} in the changed-before-update state
     */
    @JsonIgnore
    public boolean isChangesOkayedButFailed() {
        return changeAction().isChangesOkayedButFailed();
    }

    /**
     * One field's value as it would be stored: the {@code PIC X} move to its declared width.
     *
     * <p>Padded on the right with spaces if short and truncated on the right if long, which is what a COBOL
     * alphanumeric {@code MOVE} does - the opposite of the numeric case, and the reason this goes through
     * {@link FixedWidthCodec#movePicX(String, int)} rather than through Java string arithmetic.
     *
     * @param field the item the value belongs to
     * @param codec the codec supplying the move rule
     * @return exactly {@code field.length()} characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String image(ScreenField field, FixedWidthCodec codec) {
        Objects.requireNonNull(field, "A field is required to render its stored image");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required: it supplies the PIC X move rule");
        return codec.movePicX(values.get(field), field.length());
    }

    /**
     * This response with every field moved to its declared width.
     *
     * @param codec the codec supplying the move rule and the charset
     * @return a response whose {@value #FIELD_COUNT} values are each exactly their declared width
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public AccountUpdateResponse normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise field widths");
        Builder builder = toBuilder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, image(field, codec));
        }
        return builder.build();
    }

    /**
     * Renders the {@link #GROUP_LENGTH}-byte {@code CACTUPAO} image {@code 3400-SEND-SCREEN} sends
     * {@code FROM} at {@code app/cbl/COACTUPC.cbl:3596}.
     *
     * @param codec the codec supplying the charset and the move rule
     * @return a fresh {@link #GROUP_LENGTH}-byte array
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTUPAO group image: "
                + "it supplies both the PIC X move rule and the charset");
        FixedWidthRecord area = codec.newRecord(OUTPUT_GROUP_LAYOUT);
        area.fill(0, GROUP_LENGTH, LOW_VALUE_BYTE);
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributes.get(field);
            area.writeSpanBytes(field.colourItemSpan(), new byte[] {quad.getColour()});
            area.writeSpanBytes(field.psItemSpan(), new byte[] {quad.getPs()});
            area.writeSpanBytes(field.hilightItemSpan(), new byte[] {quad.getHilight()});
            area.writeSpanBytes(field.validnItemSpan(), new byte[] {quad.getValidn()});
            area.writeSpanBytes(field.outputItemSpan(),
                    FixedWidthRecord.encodeText(image(field, codec), codec.charset(),
                            field.describe()));
        }
        return area.toByteArray();
    }

    /**
     * Reads a {@link #GROUP_LENGTH}-byte {@code CACTUPAO} image back into a response.
     *
     * @param groupImage the {@link #GROUP_LENGTH}-byte output group; read, never retained
     * @param codec the codec supplying the charset
     * @return a response carrying the image's values and attribute quads
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@link #GROUP_LENGTH} bytes
     */
    public static AccountUpdateResponse fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTUPAO area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTUPAO area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTUPAO group of app/cpy-bms/COACTUP.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        FixedWidthRecord area = codec.wrap(groupImage, OUTPUT_GROUP_LAYOUT);
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.attributes(field, new FieldAttributes(
                    singleByte(area, field.colourItemSpan()),
                    singleByte(area, field.psItemSpan()),
                    singleByte(area, field.hilightItemSpan()),
                    singleByte(area, field.validnItemSpan())));
            builder.value(field, FixedWidthRecord.decodeText(
                    area.readSpanBytes(field.outputItemSpan()), codec.charset(), field.describe()));
        }
        return builder.build();
    }

    private static byte singleByte(FixedWidthRecord area, FieldSpan span) {
        return area.readSpanBytes(span)[0];
    }

    /**
     * Value equality over all {@value #FIELD_COUNT} fields, all {@value #FIELD_COUNT} attribute quads, the
     * navigation trio and all three carriers.
     *
     * @param other the object to compare with
     * @return {@code true} when every part agrees
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountUpdateResponse that)) {
            return false;
        }
        return values.equals(that.values)
                && attributes.equals(that.attributes)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && commArea.equals(that.commArea)
                && stateToken.equals(that.stateToken)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(values, attributes, nextProgram, nextMapset, nextMap,
                commArea, stateToken, cardScreenState, navigationContext);
    }

    /**
     * A single-line rendering of every field, every quad, the navigation trio and the carriers.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountUpdateResponse[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(field), values.get(field)))
                    .append("' ")
                    .append(attributes.get(field))
                    .append(", ");
        }
        return rendered.append("nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', commArea=").append(commArea)
                .append(", cardScreenState=").append(cardScreenState)
                .append(", navigationContext=").append(navigationContext)
                .append(']')
                .toString();
    }

    static SensitiveDiagnostics.Disclosure disclosureOf(ScreenField field) {
        if (field == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        return switch (field) {
            case ACTSSN1, ACTSSN2, ACTSSN3, DOBYEAR, DOBMON, DOBDAY, ACSGOVT, ACSEFTC, ACSTFCO ->
                    SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
            case ACCTSID, ACSTNUM -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case ACSFNAM, ACSMNAM, ACSLNAM, ACSADL1, ACSADL2, ACSCITY, ACSZIPC,
                 ACSPH1A, ACSPH1B, ACSPH1C, ACSPH2A, ACSPH2B, ACSPH2C ->
                    SensitiveDiagnostics.Disclosure.TEXT;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {
        private final Map<ScreenField, String> values = new EnumMap<>(ScreenField.class);

        private final Map<ScreenField, FieldAttributes> attributes = new EnumMap<>(ScreenField.class);

        private String nextProgram;

        private String nextMapset;

        private String nextMap;

        private CommArea commArea;

        /** The sealed {@code WS-THIS-PROGCOMMAREA}; {@code null} becomes empty. */
        private String stateToken;

        /** The card work area, or {@code null} for a fresh one. */
        private CardScreenState cardScreenState;

        private NavigationContext navigationContext;

        public Builder() {
        }

        /**
         * Sets one field by name, for a caller that iterates rather than one that knows the field.
         *
         * @param field the item the value belongs to
         * @param value the value; {@code null} leaves it at the LOW-VALUES baseline
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder value(ScreenField field, String value) {
            Objects.requireNonNull(field, "A field is required to set a value");
            if (value == null) {
                values.remove(field);
            } else {
                values.put(field, value);
            }
            return this;
        }

        public Builder attributes(ScreenField field, FieldAttributes quad) {
            Objects.requireNonNull(field, "A field is required to set an attribute quad");
            if (quad == null) {
                attributes.remove(field);
            } else {
                attributes.put(field, quad);
            }
            return this;
        }

        public Builder nextProgram(String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the next mapset.
         *
         * @param value seven characters, or {@code null}
         * @return this builder
         */
        public Builder nextMapset(String value) {
            this.nextMapset = value;
            return this;
        }

        public Builder nextMap(String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the {@value CommArea#RECORD_LENGTH}-byte communication area.
         *
         * <p>{@code @JsonIgnore} so that a client echoing this response cannot smuggle a composed area
         * back in through the request that shares this member name; the token is what travels.
         *
         * @param value the area, or {@code null} for {@link CommArea#initialised()}
         * @return this builder
         */
        @JsonIgnore
        public Builder commArea(CommArea value) {
            this.commArea = value;
            return this;
        }

        /**
         * Sets the sealed communication area.
         *
         * @param value the token, or {@code null} for empty
         * @return this builder
         */
        public Builder stateToken(String value) {
            this.stateToken = value;
            return this;
        }

        /**
         * Sets the {@value CardScreenState#RECORD_LENGTH}-byte card work area. Copied on build.
         *
         * @param value the work area, or {@code null} for a fresh one
         * @return this builder
         */
        public Builder cardScreenState(CardScreenState value) {
            this.cardScreenState = value;
            return this;
        }

        /**
         * Sets the {@value NavigationContext#COMMAREA_LENGTH}-byte navigation context.
         *
         * @param value the context, or {@code null} for none - the {@code EIBCALEN IS EQUAL TO 0} case
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        // Each is the Jackson property binding for its DFHMDF field as well as the fluent setter, which is
        // why the names match the accessors' (get&lt;Name&gt;() pairs with &lt;name&gt;(..)) and
        // AccountUpdateRequest's builder exactly.
        /**
         * Sets {@code TRNNAMEO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#TRNNAME_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder trnname(String value) {
            return value(ScreenField.TRNNAME, value);
        }
        /**
         * Sets {@code TITLE01O PIC X(40)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#TITLE01_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder title01(String value) {
            return value(ScreenField.TITLE01, value);
        }
        /**
         * Sets {@code CURDATEO PIC X(8)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#CURDATE_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder curdate(String value) {
            return value(ScreenField.CURDATE, value);
        }
        /**
         * Sets {@code PGMNAMEO PIC X(8)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#PGMNAME_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder pgmname(String value) {
            return value(ScreenField.PGMNAME, value);
        }
        /**
         * Sets {@code TITLE02O PIC X(40)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#TITLE02_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder title02(String value) {
            return value(ScreenField.TITLE02, value);
        }
        /**
         * Sets {@code CURTIMEO PIC X(8)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#CURTIME_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder curtime(String value) {
            return value(ScreenField.CURTIME, value);
        }
        /**
         * Sets {@code ACCTSIDO PIC X(11)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACCTSID_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acctsid(String value) {
            return value(ScreenField.ACCTSID, value);
        }
        /**
         * Sets {@code ACSTTUSO PIC X(1)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSTTUS_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsttus(String value) {
            return value(ScreenField.ACSTTUS, value);
        }
        /**
         * Sets {@code OPNYEARO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#OPNYEAR_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder opnyear(String value) {
            return value(ScreenField.OPNYEAR, value);
        }
        /**
         * Sets {@code OPNMONO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#OPNMON_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder opnmon(String value) {
            return value(ScreenField.OPNMON, value);
        }
        /**
         * Sets {@code OPNDAYO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#OPNDAY_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder opnday(String value) {
            return value(ScreenField.OPNDAY, value);
        }
        /**
         * Sets {@code ACRDLIMO PIC X(15)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACRDLIM_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acrdlim(String value) {
            return value(ScreenField.ACRDLIM, value);
        }
        /**
         * Sets {@code EXPYEARO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#EXPYEAR_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder expyear(String value) {
            return value(ScreenField.EXPYEAR, value);
        }
        /**
         * Sets {@code EXPMONO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#EXPMON_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder expmon(String value) {
            return value(ScreenField.EXPMON, value);
        }
        /**
         * Sets {@code EXPDAYO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#EXPDAY_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder expday(String value) {
            return value(ScreenField.EXPDAY, value);
        }
        /**
         * Sets {@code ACSHLIMO PIC X(15)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSHLIM_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acshlim(String value) {
            return value(ScreenField.ACSHLIM, value);
        }
        /**
         * Sets {@code RISYEARO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#RISYEAR_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder risyear(String value) {
            return value(ScreenField.RISYEAR, value);
        }
        /**
         * Sets {@code RISMONO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#RISMON_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder rismon(String value) {
            return value(ScreenField.RISMON, value);
        }
        /**
         * Sets {@code RISDAYO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#RISDAY_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder risday(String value) {
            return value(ScreenField.RISDAY, value);
        }
        /**
         * Sets {@code ACURBALO PIC X(15)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACURBAL_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acurbal(String value) {
            return value(ScreenField.ACURBAL, value);
        }
        /**
         * Sets {@code ACRCYCRO PIC X(15)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACRCYCR_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acrcycr(String value) {
            return value(ScreenField.ACRCYCR, value);
        }
        /**
         * Sets {@code AADDGRPO PIC X(10)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#AADDGRP_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder aaddgrp(String value) {
            return value(ScreenField.AADDGRP, value);
        }
        /**
         * Sets {@code ACRCYDBO PIC X(15)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACRCYDB_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acrcydb(String value) {
            return value(ScreenField.ACRCYDB, value);
        }
        /**
         * Sets {@code ACSTNUMO PIC X(9)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSTNUM_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acstnum(String value) {
            return value(ScreenField.ACSTNUM, value);
        }
        /**
         * Sets {@code ACTSSN1O PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACTSSN1_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder actssn1(String value) {
            return value(ScreenField.ACTSSN1, value);
        }
        /**
         * Sets {@code ACTSSN2O PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACTSSN2_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder actssn2(String value) {
            return value(ScreenField.ACTSSN2, value);
        }
        /**
         * Sets {@code ACTSSN3O PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACTSSN3_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder actssn3(String value) {
            return value(ScreenField.ACTSSN3, value);
        }
        /**
         * Sets {@code DOBYEARO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#DOBYEAR_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder dobyear(String value) {
            return value(ScreenField.DOBYEAR, value);
        }
        /**
         * Sets {@code DOBMONO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#DOBMON_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder dobmon(String value) {
            return value(ScreenField.DOBMON, value);
        }
        /**
         * Sets {@code DOBDAYO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#DOBDAY_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder dobday(String value) {
            return value(ScreenField.DOBDAY, value);
        }
        /**
         * Sets {@code ACSTFCOO PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSTFCO_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acstfco(String value) {
            return value(ScreenField.ACSTFCO, value);
        }
        /**
         * Sets {@code ACSFNAMO PIC X(25)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSFNAM_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsfnam(String value) {
            return value(ScreenField.ACSFNAM, value);
        }
        /**
         * Sets {@code ACSMNAMO PIC X(25)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSMNAM_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsmnam(String value) {
            return value(ScreenField.ACSMNAM, value);
        }
        /**
         * Sets {@code ACSLNAMO PIC X(25)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSLNAM_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acslnam(String value) {
            return value(ScreenField.ACSLNAM, value);
        }
        /**
         * Sets {@code ACSADL1O PIC X(50)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSADL1_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsadl1(String value) {
            return value(ScreenField.ACSADL1, value);
        }
        /**
         * Sets {@code ACSSTTEO PIC X(2)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSSTTE_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsstte(String value) {
            return value(ScreenField.ACSSTTE, value);
        }
        /**
         * Sets {@code ACSADL2O PIC X(50)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSADL2_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsadl2(String value) {
            return value(ScreenField.ACSADL2, value);
        }
        /**
         * Sets {@code ACSZIPCO PIC X(5)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSZIPC_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acszipc(String value) {
            return value(ScreenField.ACSZIPC, value);
        }
        /**
         * Sets {@code ACSCITYO PIC X(50)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSCITY_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acscity(String value) {
            return value(ScreenField.ACSCITY, value);
        }
        /**
         * Sets {@code ACSCTRYO PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSCTRY_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsctry(String value) {
            return value(ScreenField.ACSCTRY, value);
        }
        /**
         * Sets {@code ACSPH1AO PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH1A_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph1a(String value) {
            return value(ScreenField.ACSPH1A, value);
        }
        /**
         * Sets {@code ACSPH1BO PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH1B_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph1b(String value) {
            return value(ScreenField.ACSPH1B, value);
        }
        /**
         * Sets {@code ACSPH1CO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH1C_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph1c(String value) {
            return value(ScreenField.ACSPH1C, value);
        }
        /**
         * Sets {@code ACSGOVTO PIC X(20)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSGOVT_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsgovt(String value) {
            return value(ScreenField.ACSGOVT, value);
        }
        /**
         * Sets {@code ACSPH2AO PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH2A_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph2a(String value) {
            return value(ScreenField.ACSPH2A, value);
        }
        /**
         * Sets {@code ACSPH2BO PIC X(3)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH2B_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph2b(String value) {
            return value(ScreenField.ACSPH2B, value);
        }
        /**
         * Sets {@code ACSPH2CO PIC X(4)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPH2C_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acsph2c(String value) {
            return value(ScreenField.ACSPH2C, value);
        }
        /**
         * Sets {@code ACSEFTCO PIC X(10)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSEFTC_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acseftc(String value) {
            return value(ScreenField.ACSEFTC, value);
        }
        /**
         * Sets {@code ACSPFLGO PIC X(1)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ACSPFLG_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder acspflg(String value) {
            return value(ScreenField.ACSPFLG, value);
        }
        /**
         * Sets {@code INFOMSGO PIC X(45)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#INFOMSG_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder infomsg(String value) {
            return value(ScreenField.INFOMSG, value);
        }
        /**
         * Sets {@code ERRMSGO PIC X(78)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#ERRMSG_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder errmsg(String value) {
            return value(ScreenField.ERRMSG, value);
        }
        /**
         * Sets {@code FKEYSO PIC X(21)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#FKEYS_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder fkeys(String value) {
            return value(ScreenField.FKEYS, value);
        }
        /**
         * Sets {@code FKEY05O PIC X(7)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#FKEY05_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder fkey05(String value) {
            return value(ScreenField.FKEY05, value);
        }
        /**
         * Sets {@code FKEY12O PIC X(10)}.
         *
         * @param value the value, at most {@value AccountUpdateResponse#FKEY12_LENGTH} characters; may be
         *     {@code "*"}, all spaces or LOW-VALUES, and {@code null} leaves the baseline
         * @return this builder
         */
        public Builder fkey12(String value) {
            return value(ScreenField.FKEY12, value);
        }
        public AccountUpdateResponse build() {
            return new AccountUpdateResponse(this);
        }
    }
}
