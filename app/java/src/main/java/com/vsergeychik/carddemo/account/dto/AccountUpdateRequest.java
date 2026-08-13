package com.vsergeychik.carddemo.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.vsergeychik.carddemo.card.dto.CardScreenState;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.PictureKind;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound payload of {@code PUT /api/accounts/&#123;acctId&#125;} - the account update screen, CSD
 * transaction {@code CAUP}, backed by {@code app/cbl/COACTUPC.cbl} (4,236 lines, the largest program in the
 * system).
 *
 * <p>A COBOL alphanumeric receiver is filled from its leftmost position, padded on the right when the
 * sending value is short and truncated on the right when it is long.
 */
@JsonDeserialize(builder = AccountUpdateRequest.Builder.class)
public final class AccountUpdateRequest {
    /**
     * CSD transaction identifier of this screen: {@code CAUP}, from
     * {@code 05 LIT-THISTRANID PIC X(4) VALUE 'CAUP'} at {@code app/cbl/COACTUPC.cbl:535-536}.
     */
    public static final String TRANSACTION_ID = "CAUP";

    /**
     * COBOL program this type is translated from: {@code COACTUPC}, from
     * {@code 05 LIT-THISPGM PIC X(8) VALUE 'COACTUPC'} at {@code app/cbl/COACTUPC.cbl:533-534}.
     */
    public static final String PROGRAM_NAME = "COACTUPC";

    /**
     * BMS mapset name, from {@code COACTUP DFHMSD} at {@code app/bms/COACTUP.bms:20}.
     */
    public static final String MAPSET_NAME = "COACTUP";

    /**
     * BMS map name, from {@code CACTUPA DFHMDI} at {@code app/bms/COACTUP.bms:25} and
     * {@code 05 LIT-THISMAP PIC X(7) VALUE 'CACTUPA'} at {@code app/cbl/COACTUPC.cbl:539-540}.
     */
    public static final String MAP_NAME = "CACTUPA";

    /**
     * Name of the input symbolic group this type projects: {@code app/cpy-bms/COACTUP.CPY:17}.
     */
    public static final String INPUT_GROUP_NAME = "CACTUPAI";

    /**
     * Row count from {@code SIZE=(24,80)} on {@code CACTUPA DFHMDI}, {@code app/bms/COACTUP.bms:28}.
     */
    public static final int SCREEN_ROWS = 24;

    /**
     * Column count from {@code SIZE=(24,80)} on {@code CACTUPA DFHMDI}.
     */
    public static final int SCREEN_COLUMNS = 80;

    /**
     * {@code DFHMDF} entries in {@code app/bms/COACTUP.bms}, of which {@value #FIELD_COUNT} carry a name
     * label.
     */
    public static final int DFHMDF_ENTRY_COUNT = 128;

    /**
     * Width of {@code TRNNAMEI PIC X(4)}, {@code COACTUP.CPY:24}; {@code TRNNAME LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01I PIC X(40)}, {@code COACTUP.CPY:30}; {@code TITLE01 LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)}, {@code COACTUP.CPY:36}; {@code CURDATE LENGTH=8} with
     * {@code INITIAL='mm/dd/yy'} - eight characters, so the placeholder exactly fills the field.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEI PIC X(8)}, {@code COACTUP.CPY:42}; {@code PGMNAME LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02I PIC X(40)}, {@code COACTUP.CPY:48}; {@code TITLE02 LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)}, {@code COACTUP.CPY:54}; {@code CURTIME LENGTH=8} with
     * {@code INITIAL='hh:mm:ss'}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code ACCTSIDI PIC X(11)}, {@code COACTUP.CPY:60}; {@code ACCTSID LENGTH=11}.
     */
    public static final int ACCTSID_LENGTH = 11;

    /**
     * Width of {@code ACSTTUSI PIC X(1)}, {@code COACTUP.CPY:66}; {@code ACSTTUS LENGTH=1}.
     */
    public static final int ACSTTUS_LENGTH = 1;

    /**
     * Width of {@code OPNYEARI PIC X(4)}, {@code COACTUP.CPY:72}; {@code OPNYEAR LENGTH=4}.
     */
    public static final int OPNYEAR_LENGTH = 4;

    /**
     * Width of {@code OPNMONI PIC X(2)}, {@code COACTUP.CPY:78}; {@code OPNMON LENGTH=2}.
     */
    public static final int OPNMON_LENGTH = 2;

    /**
     * Width of {@code OPNDAYI PIC X(2)}, {@code COACTUP.CPY:84}; {@code OPNDAY LENGTH=2}.
     */
    public static final int OPNDAY_LENGTH = 2;

    /**
     * Width of {@code ACRDLIMI PIC X(15)}, {@code COACTUP.CPY:90}; {@code ACRDLIM LENGTH=15}.
     */
    public static final int ACRDLIM_LENGTH = 15;

    /**
     * Width of {@code EXPYEARI PIC X(4)}, {@code COACTUP.CPY:96}; {@code EXPYEAR LENGTH=4}.
     */
    public static final int EXPYEAR_LENGTH = 4;

    /**
     * Width of {@code EXPMONI PIC X(2)}, {@code COACTUP.CPY:102}; {@code EXPMON LENGTH=2}.
     */
    public static final int EXPMON_LENGTH = 2;

    /**
     * Width of {@code EXPDAYI PIC X(2)}, {@code COACTUP.CPY:108}; {@code EXPDAY LENGTH=2}.
     */
    public static final int EXPDAY_LENGTH = 2;

    /**
     * Width of {@code ACSHLIMI PIC X(15)}, {@code COACTUP.CPY:114}; {@code ACSHLIM LENGTH=15}.
     */
    public static final int ACSHLIM_LENGTH = 15;

    /**
     * Width of {@code RISYEARI PIC X(4)}, {@code COACTUP.CPY:120}; {@code RISYEAR LENGTH=4}.
     */
    public static final int RISYEAR_LENGTH = 4;

    /**
     * Width of {@code RISMONI PIC X(2)}, {@code COACTUP.CPY:126}; {@code RISMON LENGTH=2}.
     */
    public static final int RISMON_LENGTH = 2;

    /**
     * Width of {@code RISDAYI PIC X(2)}, {@code COACTUP.CPY:132}; {@code RISDAY LENGTH=2}.
     */
    public static final int RISDAY_LENGTH = 2;

    /**
     * Width of {@code ACURBALI PIC X(15)}, {@code COACTUP.CPY:138}; {@code ACURBAL LENGTH=15}.
     */
    public static final int ACURBAL_LENGTH = 15;

    /**
     * Width of {@code ACRCYCRI PIC X(15)}, {@code COACTUP.CPY:144}; {@code ACRCYCR LENGTH=15}.
     */
    public static final int ACRCYCR_LENGTH = 15;

    /**
     * Width of {@code AADDGRPI PIC X(10)}, {@code COACTUP.CPY:150}; {@code AADDGRP LENGTH=10}.
     */
    public static final int AADDGRP_LENGTH = 10;

    /**
     * Width of {@code ACRCYDBI PIC X(15)}, {@code COACTUP.CPY:156}; {@code ACRCYDB LENGTH=15}.
     */
    public static final int ACRCYDB_LENGTH = 15;

    /**
     * Width of {@code ACSTNUMI PIC X(9)}, {@code COACTUP.CPY:162}; {@code ACSTNUM LENGTH=9}.
     */
    public static final int ACSTNUM_LENGTH = 9;

    /**
     * Width of {@code ACTSSN1I PIC X(3)}, {@code COACTUP.CPY:168}; {@code ACTSSN1 LENGTH=3} with
     * {@code INITIAL='999'}.
     */
    public static final int ACTSSN1_LENGTH = 3;

    /**
     * Width of {@code ACTSSN2I PIC X(2)}, {@code COACTUP.CPY:174}; {@code ACTSSN2 LENGTH=2} with
     * {@code INITIAL='99'}.
     */
    public static final int ACTSSN2_LENGTH = 2;

    /**
     * Width of {@code ACTSSN3I PIC X(4)}, {@code COACTUP.CPY:180}; {@code ACTSSN3 LENGTH=4} with
     * {@code INITIAL='9999'}.
     */
    public static final int ACTSSN3_LENGTH = 4;

    /**
     * Width of {@code DOBYEARI PIC X(4)}, {@code COACTUP.CPY:186}; {@code DOBYEAR LENGTH=4}.
     */
    public static final int DOBYEAR_LENGTH = 4;

    /**
     * Width of {@code DOBMONI PIC X(2)}, {@code COACTUP.CPY:192}; {@code DOBMON LENGTH=2}.
     */
    public static final int DOBMON_LENGTH = 2;

    /**
     * Width of {@code DOBDAYI PIC X(2)}, {@code COACTUP.CPY:198}; {@code DOBDAY LENGTH=2}.
     */
    public static final int DOBDAY_LENGTH = 2;

    /**
     * Width of {@code ACSTFCOI PIC X(3)}, {@code COACTUP.CPY:204}; {@code ACSTFCO LENGTH=3}.
     */
    public static final int ACSTFCO_LENGTH = 3;

    /**
     * Width of {@code ACSFNAMI PIC X(25)}, {@code COACTUP.CPY:210}; {@code ACSFNAM LENGTH=25}.
     */
    public static final int ACSFNAM_LENGTH = 25;

    /**
     * Width of {@code ACSMNAMI PIC X(25)}, {@code COACTUP.CPY:216}; {@code ACSMNAM LENGTH=25}.
     */
    public static final int ACSMNAM_LENGTH = 25;

    /**
     * Width of {@code ACSLNAMI PIC X(25)}, {@code COACTUP.CPY:222}; {@code ACSLNAM LENGTH=25}.
     */
    public static final int ACSLNAM_LENGTH = 25;

    /**
     * Width of {@code ACSADL1I PIC X(50)}, {@code COACTUP.CPY:228}; {@code ACSADL1 LENGTH=50}.
     */
    public static final int ACSADL1_LENGTH = 50;

    /**
     * Width of {@code ACSSTTEI PIC X(2)}, {@code COACTUP.CPY:234}; {@code ACSSTTE LENGTH=2}.
     */
    public static final int ACSSTTE_LENGTH = 2;

    /**
     * Width of {@code ACSADL2I PIC X(50)}, {@code COACTUP.CPY:240}; {@code ACSADL2 LENGTH=50}.
     */
    public static final int ACSADL2_LENGTH = 50;

    /**
     * Width of {@code ACSZIPCI PIC X(5)}, {@code COACTUP.CPY:246}; {@code ACSZIPC LENGTH=5}.
     */
    public static final int ACSZIPC_LENGTH = 5;

    /**
     * Width of {@code ACSCITYI PIC X(50)}, {@code COACTUP.CPY:252}; {@code ACSCITY LENGTH=50}.
     */
    public static final int ACSCITY_LENGTH = 50;

    /**
     * Width of {@code ACSCTRYI PIC X(3)}, {@code COACTUP.CPY:258}; {@code ACSCTRY LENGTH=3}.
     */
    public static final int ACSCTRY_LENGTH = 3;

    /**
     * Width of {@code ACSPH1AI PIC X(3)}, {@code COACTUP.CPY:264}; {@code ACSPH1A LENGTH=3}.
     */
    public static final int ACSPH1A_LENGTH = 3;

    /**
     * Width of {@code ACSPH1BI PIC X(3)}, {@code COACTUP.CPY:270}; {@code ACSPH1B LENGTH=3}.
     */
    public static final int ACSPH1B_LENGTH = 3;

    /**
     * Width of {@code ACSPH1CI PIC X(4)}, {@code COACTUP.CPY:276}; {@code ACSPH1C LENGTH=4}.
     */
    public static final int ACSPH1C_LENGTH = 4;

    /**
     * Width of {@code ACSGOVTI PIC X(20)}, {@code COACTUP.CPY:282}; {@code ACSGOVT LENGTH=20}.
     */
    public static final int ACSGOVT_LENGTH = 20;

    /**
     * Width of {@code ACSPH2AI PIC X(3)}, {@code COACTUP.CPY:288}; {@code ACSPH2A LENGTH=3}.
     */
    public static final int ACSPH2A_LENGTH = 3;

    /**
     * Width of {@code ACSPH2BI PIC X(3)}, {@code COACTUP.CPY:294}; {@code ACSPH2B LENGTH=3}.
     */
    public static final int ACSPH2B_LENGTH = 3;

    /**
     * Width of {@code ACSPH2CI PIC X(4)}, {@code COACTUP.CPY:300}; {@code ACSPH2C LENGTH=4}.
     */
    public static final int ACSPH2C_LENGTH = 4;

    /**
     * Width of {@code ACSEFTCI PIC X(10)}, {@code COACTUP.CPY:306}; {@code ACSEFTC LENGTH=10}.
     */
    public static final int ACSEFTC_LENGTH = 10;

    /**
     * Width of {@code ACSPFLGI PIC X(1)}, {@code COACTUP.CPY:312}; {@code ACSPFLG LENGTH=1}.
     */
    public static final int ACSPFLG_LENGTH = 1;

    /**
     * Width of {@code INFOMSGI PIC X(45)}, {@code COACTUP.CPY:318}; {@code INFOMSG LENGTH=45}.
     */
    public static final int INFOMSG_LENGTH = 45;

    /**
     * Width of {@code ERRMSGI PIC X(78)}, {@code COACTUP.CPY:324}; {@code ERRMSG LENGTH=78}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Width of {@code FKEYSI PIC X(21)}, {@code COACTUP.CPY:330}; {@code FKEYS LENGTH=21} with
     * {@code INITIAL='ENTER=Process F3=Exit'}, which is exactly twenty-one characters - a useful
     * independent signal that the width was read correctly.
     */
    public static final int FKEYS_LENGTH = 21;

    /**
     * Width of {@code FKEY05I PIC X(7)}, {@code COACTUP.CPY:336}; {@code FKEY05 LENGTH=7} with
     * {@code INITIAL='F5=Save'}, again exactly the declared width.
     */
    public static final int FKEY05_LENGTH = 7;

    /**
     * Width of {@code FKEY12I PIC X(10)}, {@code COACTUP.CPY:342}; {@code FKEY12 LENGTH=10} with
     * {@code INITIAL='F12=Cancel'}.
     */
    public static final int FKEY12_LENGTH = 10;

    /**
     * Bytes of {@code 02 FILLER PIC X(12)} at the head of the group, {@code app/cpy-bms/COACTUP.CPY:18}.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * Bytes of {@code 02 xxxL COMP PIC S9(4)}, the signed binary halfword CICS reports a received field's
     * length in - and the channel {@code MOVE -1 TO ...L} uses to position the cursor.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * Bytes of {@code 02 xxxF PICTURE X}, the flag byte that {@code 03 xxxA PICTURE X} redefines.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * Bytes of the unnamed {@code 02 FILLER PICTURE X(4)} between the flag byte and the data.
     */
    public static final int EXTENDED_ATTRIBUTE_ITEM_LENGTH = 4;

    public static final int FIELD_OVERHEAD =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + EXTENDED_ATTRIBUTE_ITEM_LENGTH;

    /**
     * Name-labelled {@code DFHMDF} entries in {@code app/bms/COACTUP.bms}, of {@value #DFHMDF_ENTRY_COUNT}
     * entries in all.
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
     * Bytes {@code COACTUPC} actually uses of {@code 01 WS-COMMAREA PIC X(2000)}
     * ({@code app/cbl/COACTUPC.cbl:850}): the {@value NavigationContext#COMMAREA_LENGTH}-byte
     * {@code CARDDEMO-COMMAREA} followed by the {@value CommArea#RECORD_LENGTH}-byte
     * {@code WS-THIS-PROGCOMMAREA}.
     */
    public static final int TOTAL_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CommArea.RECORD_LENGTH;

    /**
     * Declared capacity of {@code 01 WS-COMMAREA PIC X(2000)}, {@code app/cbl/COACTUPC.cbl:850}.
     */
    public static final int COMMAREA_CAPACITY = 2000;

    private static final char SPACE = ' ';

    private static final char LOW_VALUE = '\u0000';

    private static final byte LOW_VALUE_BYTE = 0x00;

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    static {
        if (FIELD_OVERHEAD != 7) {
            throw new IllegalStateException("Each COACTUP.CPY input field carries 2 + 1 + 4 = 7 bytes "
                    + "of length, flag and extended-attribute storage before its data, but "
                    + "FIELD_OVERHEAD computes to " + FIELD_OVERHEAD);
        }
        if (PAYLOAD_LENGTH != 705) {
            throw new IllegalStateException("The 54 xxxI PICTURE widths of app/cpy-bms/COACTUP.CPY sum "
                    + "to 705, which is also the sum of the 54 LENGTH= operands of "
                    + "app/bms/COACTUP.bms, but the width constants sum to " + PAYLOAD_LENGTH);
        }
        if (GROUP_LENGTH != 1095) {
            throw new IllegalStateException("The CACTUPAI group is 12 + 54 * 7 + 705 = 1095 bytes, but "
                    + "the constants compute " + GROUP_LENGTH);
        }
        if (AcctSnapshot.MONEY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE
                != AcctSnapshot.MONEY_LENGTH) {
            throw new IllegalStateException("ACUP-xxx-CURR-BAL-N is PIC S9(10)V99, so its "
                    + AcctSnapshot.MONEY_INTEGER_DIGITS + " integer digits plus its "
                    + CobolDecimal.MONETARY_SCALE + " fraction digits must occupy the "
                    + AcctSnapshot.MONEY_LENGTH + " bytes ACUP-xxx-CURR-BAL declares, but they sum to "
                    + (AcctSnapshot.MONEY_INTEGER_DIGITS + CobolDecimal.MONETARY_SCALE));
        }
        verifyFieldStrides();
    }

    private static void verifyFieldStrides() {
        ScreenField[] fields = ScreenField.values();
        if (fields.length != FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COACTUP.bms carries " + FIELD_COUNT
                    + " name-labelled DFHMDF entries, so ScreenField must declare " + FIELD_COUNT
                    + " constants, but it declares " + fields.length);
        }
        int cursor = TIOAPFX_LENGTH;
        int widths = 0;
        for (ScreenField field : fields) {
            if (field.lengthItemOffset() != cursor) {
                throw new IllegalStateException("Field " + field.label() + " declares its data at "
                        + "offset " + field.dataOffset() + ", which places its xxxL item at "
                        + field.lengthItemOffset() + "; the preceding storage ends at " + cursor
                        + ", so the CACTUPAI group would have a gap or an overlap there");
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

    @Size(max = TRNNAME_LENGTH)
    private final String trnname;

    @Size(max = TITLE01_LENGTH)
    private final String title01;

    @Size(max = CURDATE_LENGTH)
    private final String curdate;

    @Size(max = PGMNAME_LENGTH)
    private final String pgmname;

    @Size(max = TITLE02_LENGTH)
    private final String title02;

    @Size(max = CURTIME_LENGTH)
    private final String curtime;

    @Size(max = ACCTSID_LENGTH)
    private final String acctsid;

    @Size(max = ACSTTUS_LENGTH)
    private final String acsttus;

    @Size(max = OPNYEAR_LENGTH)
    private final String opnyear;

    @Size(max = OPNMON_LENGTH)
    private final String opnmon;

    @Size(max = OPNDAY_LENGTH)
    private final String opnday;

    @Size(max = ACRDLIM_LENGTH)
    private final String acrdlim;

    @Size(max = EXPYEAR_LENGTH)
    private final String expyear;

    @Size(max = EXPMON_LENGTH)
    private final String expmon;

    @Size(max = EXPDAY_LENGTH)
    private final String expday;

    @Size(max = ACSHLIM_LENGTH)
    private final String acshlim;

    @Size(max = RISYEAR_LENGTH)
    private final String risyear;

    @Size(max = RISMON_LENGTH)
    private final String rismon;

    @Size(max = RISDAY_LENGTH)
    private final String risday;

    @Size(max = ACURBAL_LENGTH)
    private final String acurbal;

    @Size(max = ACRCYCR_LENGTH)
    private final String acrcycr;

    @Size(max = AADDGRP_LENGTH)
    private final String aaddgrp;

    @Size(max = ACRCYDB_LENGTH)
    private final String acrcydb;

    @Size(max = ACSTNUM_LENGTH)
    private final String acstnum;

    @Size(max = ACTSSN1_LENGTH)
    private final String actssn1;

    @Size(max = ACTSSN2_LENGTH)
    private final String actssn2;

    @Size(max = ACTSSN3_LENGTH)
    private final String actssn3;

    @Size(max = DOBYEAR_LENGTH)
    private final String dobyear;

    @Size(max = DOBMON_LENGTH)
    private final String dobmon;

    @Size(max = DOBDAY_LENGTH)
    private final String dobday;

    @Size(max = ACSTFCO_LENGTH)
    private final String acstfco;

    @Size(max = ACSFNAM_LENGTH)
    private final String acsfnam;

    @Size(max = ACSMNAM_LENGTH)
    private final String acsmnam;

    @Size(max = ACSLNAM_LENGTH)
    private final String acslnam;

    @Size(max = ACSADL1_LENGTH)
    private final String acsadl1;

    @Size(max = ACSSTTE_LENGTH)
    private final String acsstte;

    @Size(max = ACSADL2_LENGTH)
    private final String acsadl2;

    @Size(max = ACSZIPC_LENGTH)
    private final String acszipc;

    @Size(max = ACSCITY_LENGTH)
    private final String acscity;

    @Size(max = ACSCTRY_LENGTH)
    private final String acsctry;

    @Size(max = ACSPH1A_LENGTH)
    private final String acsph1a;

    @Size(max = ACSPH1B_LENGTH)
    private final String acsph1b;

    @Size(max = ACSPH1C_LENGTH)
    private final String acsph1c;

    @Size(max = ACSGOVT_LENGTH)
    private final String acsgovt;

    @Size(max = ACSPH2A_LENGTH)
    private final String acsph2a;

    @Size(max = ACSPH2B_LENGTH)
    private final String acsph2b;

    @Size(max = ACSPH2C_LENGTH)
    private final String acsph2c;

    @Size(max = ACSEFTC_LENGTH)
    private final String acseftc;

    @Size(max = ACSPFLG_LENGTH)
    private final String acspflg;

    @Size(max = INFOMSG_LENGTH)
    private final String infomsg;

    @Size(max = ERRMSG_LENGTH)
    private final String errmsg;

    @Size(max = FKEYS_LENGTH)
    private final String fkeys;

    @Size(max = FKEY05_LENGTH)
    private final String fkey05;

    @Size(max = FKEY12_LENGTH)
    private final String fkey12;

    @Valid
    private final CommArea commArea;

    /**
     * The sealed form of {@link #commArea}, which is the only form that crosses the wire.
     *
     * <p>{@code WS-THIS-PROGCOMMAREA} is not screen data. It is the program's own storage, and the byte
     * it opens with - {@code ACUP-CHANGE-ACTION} - is the record that this screen's twenty-four edits
     * already passed: {@code app/cbl/COACTUPC.cbl:1463-1468} skips every one of them when it reads
     * {@code ACUP-CHANGES-OK-NOT-CONFIRMED}, and {@code :2602-2604} then writes on {@code PF5}. On a 3270
     * that is sound, because CICS passes the area and the terminal never sees it. Published as structured
     * JSON it stops being sound: a caller could compose the byte, skip every edit and reach persistence
     * with values nothing validated, and could compose the old snapshot the concurrency check compares.
     *
     * <p>So the area travels as one opaque token issued by {@link ConversationStateSeal}. The bytes inside
     * it are exactly the {@value CommArea#RECORD_LENGTH} the COBOL area holds - nothing is renamed,
     * dropped or reshaped, and {@link #getCommArea()} still answers the structured value to the program
     * flow and to a parity case. What changed is only that a caller can no longer write it.
     *
     * <p>Empty means no token arrived, which is a cold start or a turn on which the program had no state
     * to carry. It is never {@code null}.
     */
    private final String stateToken;

    /**
     * {@code app/cpy/CVCRD01Y.cpy} - the card work area, carrying {@code CCARD-AID} (which key was
     * pressed), the next program, mapset and map, and the error and return messages.
     *
     * <p>{@link CardScreenState} is mutable, so it is copied on the way in and on the way out; this
     * type's immutability does not depend on a caller's restraint.
     */
    private final CardScreenState cardScreenState;

    private final NavigationContext navigationContext;

    private final Map<ScreenField, FieldMetadata> metadata;

    private AccountUpdateRequest(Builder builder) {
        this.trnname = orSpaces(builder.trnname, TRNNAME_LENGTH);
        this.title01 = orSpaces(builder.title01, TITLE01_LENGTH);
        this.curdate = orSpaces(builder.curdate, CURDATE_LENGTH);
        this.pgmname = orSpaces(builder.pgmname, PGMNAME_LENGTH);
        this.title02 = orSpaces(builder.title02, TITLE02_LENGTH);
        this.curtime = orSpaces(builder.curtime, CURTIME_LENGTH);
        this.acctsid = orSpaces(builder.acctsid, ACCTSID_LENGTH);
        this.acsttus = orSpaces(builder.acsttus, ACSTTUS_LENGTH);
        this.opnyear = orSpaces(builder.opnyear, OPNYEAR_LENGTH);
        this.opnmon = orSpaces(builder.opnmon, OPNMON_LENGTH);
        this.opnday = orSpaces(builder.opnday, OPNDAY_LENGTH);
        this.acrdlim = orSpaces(builder.acrdlim, ACRDLIM_LENGTH);
        this.expyear = orSpaces(builder.expyear, EXPYEAR_LENGTH);
        this.expmon = orSpaces(builder.expmon, EXPMON_LENGTH);
        this.expday = orSpaces(builder.expday, EXPDAY_LENGTH);
        this.acshlim = orSpaces(builder.acshlim, ACSHLIM_LENGTH);
        this.risyear = orSpaces(builder.risyear, RISYEAR_LENGTH);
        this.rismon = orSpaces(builder.rismon, RISMON_LENGTH);
        this.risday = orSpaces(builder.risday, RISDAY_LENGTH);
        this.acurbal = orSpaces(builder.acurbal, ACURBAL_LENGTH);
        this.acrcycr = orSpaces(builder.acrcycr, ACRCYCR_LENGTH);
        this.aaddgrp = orSpaces(builder.aaddgrp, AADDGRP_LENGTH);
        this.acrcydb = orSpaces(builder.acrcydb, ACRCYDB_LENGTH);
        this.acstnum = orSpaces(builder.acstnum, ACSTNUM_LENGTH);
        this.actssn1 = orSpaces(builder.actssn1, ACTSSN1_LENGTH);
        this.actssn2 = orSpaces(builder.actssn2, ACTSSN2_LENGTH);
        this.actssn3 = orSpaces(builder.actssn3, ACTSSN3_LENGTH);
        this.dobyear = orSpaces(builder.dobyear, DOBYEAR_LENGTH);
        this.dobmon = orSpaces(builder.dobmon, DOBMON_LENGTH);
        this.dobday = orSpaces(builder.dobday, DOBDAY_LENGTH);
        this.acstfco = orSpaces(builder.acstfco, ACSTFCO_LENGTH);
        this.acsfnam = orSpaces(builder.acsfnam, ACSFNAM_LENGTH);
        this.acsmnam = orSpaces(builder.acsmnam, ACSMNAM_LENGTH);
        this.acslnam = orSpaces(builder.acslnam, ACSLNAM_LENGTH);
        this.acsadl1 = orSpaces(builder.acsadl1, ACSADL1_LENGTH);
        this.acsstte = orSpaces(builder.acsstte, ACSSTTE_LENGTH);
        this.acsadl2 = orSpaces(builder.acsadl2, ACSADL2_LENGTH);
        this.acszipc = orSpaces(builder.acszipc, ACSZIPC_LENGTH);
        this.acscity = orSpaces(builder.acscity, ACSCITY_LENGTH);
        this.acsctry = orSpaces(builder.acsctry, ACSCTRY_LENGTH);
        this.acsph1a = orSpaces(builder.acsph1a, ACSPH1A_LENGTH);
        this.acsph1b = orSpaces(builder.acsph1b, ACSPH1B_LENGTH);
        this.acsph1c = orSpaces(builder.acsph1c, ACSPH1C_LENGTH);
        this.acsgovt = orSpaces(builder.acsgovt, ACSGOVT_LENGTH);
        this.acsph2a = orSpaces(builder.acsph2a, ACSPH2A_LENGTH);
        this.acsph2b = orSpaces(builder.acsph2b, ACSPH2B_LENGTH);
        this.acsph2c = orSpaces(builder.acsph2c, ACSPH2C_LENGTH);
        this.acseftc = orSpaces(builder.acseftc, ACSEFTC_LENGTH);
        this.acspflg = orSpaces(builder.acspflg, ACSPFLG_LENGTH);
        this.infomsg = orSpaces(builder.infomsg, INFOMSG_LENGTH);
        this.errmsg = orSpaces(builder.errmsg, ERRMSG_LENGTH);
        this.fkeys = orSpaces(builder.fkeys, FKEYS_LENGTH);
        this.fkey05 = orSpaces(builder.fkey05, FKEY05_LENGTH);
        this.fkey12 = orSpaces(builder.fkey12, FKEY12_LENGTH);
        this.commArea = builder.commArea == null ? CommArea.initialised() : builder.commArea;
        this.stateToken = builder.stateToken == null ? "" : builder.stateToken;
        this.cardScreenState = builder.cardScreenState == null
                ? new CardScreenState()
                : new CardScreenState(builder.cardScreenState);
        this.navigationContext = builder.navigationContext;
        this.metadata = unmodifiableMetadata(builder.metadata);
    }

    /**
     * A builder in which every screen field is unset, the work area is initialised and the navigation
     * context is absent.
     *
     * @return a fresh builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A request in the shape of a first entry to the screen: every field holds its declared width in
     * spaces, the work area is at {@link CommArea#initialised()} - so
     * {@link ChangeAction#isDetailsNotFetched()} is true, the state
     * {@code SET ACUP-DETAILS-NOT-FETCHED TO TRUE} at {@code app/cbl/COACTUPC.cbl:886} produces - the card
     * work area is initialised and there is no communication area.
     *
     * @return the initial request, never {@code null}
     */
    public static AccountUpdateRequest initial() {
        return builder().build();
    }

    /**
     * A first-entry request carrying only an account filter, which is the single value an operator has to
     * supply to reach any account.
     *
     * @param acctsid the account filter, stored verbatim - {@code null} becomes {@value #ACCTSID_LENGTH}
     *     spaces
     * @return the request, never {@code null}
     */
    public static AccountUpdateRequest withAccountFilter(String acctsid) {
        return builder().acctsid(acctsid).build().withCursorOn(ScreenField.ACCTSID);
    }

    /**
     * A builder pre-loaded with everything this request holds, so a caller can derive a modified copy
     * without restating the other 53 fields.
     *
     * @return a builder equal to this request, never {@code null}
     */
    public Builder toBuilder() {
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, value(field));
            builder.metadata(field, metadata.get(field));
        }
        builder.commArea = commArea;
        builder.stateToken = stateToken;
        builder.cardScreenState = cardScreenState;
        builder.navigationContext = navigationContext;
        return builder;
    }

    /**
     * A string of {@code length} spaces - the value of an untransmitted {@code PIC X} field, and the fill
     * of every field in {@link #initial()}.
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
     * A string of {@code length} {@code LOW-VALUES} characters, the figurative constant
     * {@code app/cbl/COACTUPC.cbl:1053} moves into {@code ACUP-NEW-ACCT-ID-X} when the operator clears or
     * wildcards the account filter.
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

    private static String orSpaces(String value, int width) {
        return value == null ? spaces(width) : value;
    }

    private static Map<ScreenField, FieldMetadata> unmodifiableMetadata(
            Map<ScreenField, FieldMetadata> source) {
        Map<ScreenField, FieldMetadata> complete = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            FieldMetadata supplied = source.get(field);
            complete.put(field, supplied == null ? FieldMetadata.unset() : supplied);
        }
        return Collections.unmodifiableMap(complete);
    }

    // Each returns exactly what was stored - untrimmed, because a PIC X field's trailing spaces are part of
    // its value and the parity differ compares them.

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

    public String getAcctsid() {
        return acctsid;
    }

    public String getAcsttus() {
        return acsttus;
    }

    public String getOpnyear() {
        return opnyear;
    }

    public String getOpnmon() {
        return opnmon;
    }

    public String getOpnday() {
        return opnday;
    }

    public String getAcrdlim() {
        return acrdlim;
    }

    public String getExpyear() {
        return expyear;
    }

    public String getExpmon() {
        return expmon;
    }

    public String getExpday() {
        return expday;
    }

    public String getAcshlim() {
        return acshlim;
    }

    public String getRisyear() {
        return risyear;
    }

    public String getRismon() {
        return rismon;
    }

    public String getRisday() {
        return risday;
    }

    public String getAcurbal() {
        return acurbal;
    }

    public String getAcrcycr() {
        return acrcycr;
    }

    public String getAaddgrp() {
        return aaddgrp;
    }

    public String getAcrcydb() {
        return acrcydb;
    }

    public String getAcstnum() {
        return acstnum;
    }

    public String getActssn1() {
        return actssn1;
    }

    public String getActssn2() {
        return actssn2;
    }

    public String getActssn3() {
        return actssn3;
    }

    public String getDobyear() {
        return dobyear;
    }

    public String getDobmon() {
        return dobmon;
    }

    public String getDobday() {
        return dobday;
    }

    public String getAcstfco() {
        return acstfco;
    }

    public String getAcsfnam() {
        return acsfnam;
    }

    public String getAcsmnam() {
        return acsmnam;
    }

    public String getAcslnam() {
        return acslnam;
    }

    public String getAcsadl1() {
        return acsadl1;
    }

    public String getAcsstte() {
        return acsstte;
    }

    public String getAcsadl2() {
        return acsadl2;
    }

    public String getAcszipc() {
        return acszipc;
    }

    public String getAcscity() {
        return acscity;
    }

    public String getAcsctry() {
        return acsctry;
    }

    public String getAcsph1a() {
        return acsph1a;
    }

    public String getAcsph1b() {
        return acsph1b;
    }

    public String getAcsph1c() {
        return acsph1c;
    }

    public String getAcsgovt() {
        return acsgovt;
    }

    public String getAcsph2a() {
        return acsph2a;
    }

    public String getAcsph2b() {
        return acsph2b;
    }

    public String getAcsph2c() {
        return acsph2c;
    }

    public String getAcseftc() {
        return acseftc;
    }

    public String getAcspflg() {
        return acspflg;
    }

    public String getInfomsg() {
        return infomsg;
    }

    public String getErrmsg() {
        return errmsg;
    }

    public String getFkeys() {
        return fkeys;
    }

    public String getFkey05() {
        return fkey05;
    }

    public String getFkey12() {
        return fkey12;
    }

    public String value(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a field's value");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACCTSID -> acctsid;
            case ACSTTUS -> acsttus;
            case OPNYEAR -> opnyear;
            case OPNMON -> opnmon;
            case OPNDAY -> opnday;
            case ACRDLIM -> acrdlim;
            case EXPYEAR -> expyear;
            case EXPMON -> expmon;
            case EXPDAY -> expday;
            case ACSHLIM -> acshlim;
            case RISYEAR -> risyear;
            case RISMON -> rismon;
            case RISDAY -> risday;
            case ACURBAL -> acurbal;
            case ACRCYCR -> acrcycr;
            case AADDGRP -> aaddgrp;
            case ACRCYDB -> acrcydb;
            case ACSTNUM -> acstnum;
            case ACTSSN1 -> actssn1;
            case ACTSSN2 -> actssn2;
            case ACTSSN3 -> actssn3;
            case DOBYEAR -> dobyear;
            case DOBMON -> dobmon;
            case DOBDAY -> dobday;
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
            case ACSPH1A -> acsph1a;
            case ACSPH1B -> acsph1b;
            case ACSPH1C -> acsph1c;
            case ACSGOVT -> acsgovt;
            case ACSPH2A -> acsph2a;
            case ACSPH2B -> acsph2b;
            case ACSPH2C -> acsph2c;
            case ACSEFTC -> acseftc;
            case ACSPFLG -> acspflg;
            case INFOMSG -> infomsg;
            case ERRMSG -> errmsg;
            case FKEYS -> fkeys;
            case FKEY05 -> fkey05;
            case FKEY12 -> fkey12;
        };
    }

    /**
     * A copy of this request with one field replaced and everything else unchanged.
     *
     * @param field which field
     * @param value the new value, stored verbatim; {@code null} becomes the field's declared width in
     *     spaces
     * @return a new request, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateRequest withValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A ScreenField is required to replace a field's value");
        return toBuilder().value(field, value).build();
    }

    /**
     * Every field's value, keyed by {@code DFHMDF} label in declaration order.
     *
     * @return an unmodifiable, insertion-ordered map of all {@value #FIELD_COUNT} fields
     */
    public Map<String, String> fieldValues() {
        Map<ScreenField, String> byField = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            byField.put(field, value(field));
        }
        Map<String, String> byLabel = new LinkedHashMap<>(byField.size());
        byField.forEach((field, value) -> byLabel.put(field.label(), value));
        return Collections.unmodifiableMap(byLabel);
    }

    public static int declaredLength(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to report a declared length");
        return field.length();
    }

    @JsonIgnore
    public FieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to read a field's metadata");
        return metadata.get(field);
    }

    /**
     * Every field's metadata, keyed by {@link ScreenField} in declaration order.
     *
     * @return an unmodifiable map with an entry for all {@value #FIELD_COUNT} fields
     */
    @JsonIgnore
    public Map<ScreenField, FieldMetadata> metadata() {
        return metadata;
    }

    /**
     * A copy of this request with one field's metadata replaced.
     *
     * @param field which field
     * @param replacement the new carrier
     * @return a new request, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public AccountUpdateRequest withMetadata(ScreenField field, FieldMetadata replacement) {
        Objects.requireNonNull(field, "A ScreenField is required to replace a field's metadata");
        Objects.requireNonNull(replacement, "A FieldMetadata is required; every field has one, and its "
                + "unset state is FieldMetadata.unset() rather than a Java null");
        return toBuilder().metadata(field, replacement).build();
    }

    /**
     * A copy of this request with the cursor asked onto one field - {@code MOVE -1 TO xxxL OF CACTUPAI},
     * which {@code COACTUPC} performs at 41 sites including {@code :3015} and {@code :3166}.
     *
     * @param field which field the cursor should land on
     * @return a new request whose {@code field} reports {@link FieldMetadata#isCursorHere()}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateRequest withCursorOn(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to position the cursor");
        return withMetadata(field, metadata(field).withCursorHere());
    }

    /**
     * A copy of this request with one field's attribute byte replaced - the {@code xxxA} view of
     * {@code xxxF}, written by {@code 3310-PROTECT-ALL-ATTRS} and {@code 3320-UNPROTECT-FEW-ATTRS}.
     *
     * @param field which field
     * @param attribute the attribute byte, which must come from {@code common/BmsAttributes} rather than
     *     being written as a literal
     * @return a new request, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public AccountUpdateRequest withAttribute(ScreenField field, byte attribute) {
        Objects.requireNonNull(field, "A ScreenField is required to set a field's attribute byte");
        return withMetadata(field, metadata(field).withAttribute(attribute));
    }

    /**
     * Whether {@code FKEY05} is revealed - that is, whether its attribute byte is
     * {@link BmsAttributes#DFHBMASB}, which is what {@code app/cbl/COACTUPC.cbl:3579-3580} moves into
     * {@code FKEY05A OF CACTUPAI} to make "{@code F5=Save}" visible.
     *
     * @return {@code true} when the save legend has been revealed
     */
    @JsonIgnore
    public boolean isSaveLegendRevealed() {
        return metadata(ScreenField.FKEY05).isBright();
    }

    /**
     * Whether {@code FKEY12} is revealed - {@code app/cbl/COACTUPC.cbl:3575} moves
     * {@link BmsAttributes#DFHBMASB} into {@code FKEY12A OF CACTUPAI} to make "{@code F12=Cancel}" visible.
     *
     * @return {@code true} when the cancel legend has been revealed
     */
    @JsonIgnore
    public boolean isCancelLegendRevealed() {
        return metadata(ScreenField.FKEY12).isBright();
    }

    /**
     * The {@value CommArea#RECORD_LENGTH}-byte program work area.
     *
     * <p>{@code @JsonIgnore}: this is the one member of this request that is <strong>not</strong> part of
     * the wire contract. It reaches the program flow, the service and a parity case as the structured
     * value it has always been, but a caller cannot supply it - {@link #getStateToken()} is what crosses
     * the boundary, and {@code AccountUpdateController} is what turns one into the other. A body that
     * names {@code commArea} has it ignored rather than honoured.
     *
     * @return the work area, never {@code null}
     */
    @JsonIgnore
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * The sealed program work area, as it crosses the wire.
     *
     * @return the token, empty when none arrived; never {@code null}
     */
    public String getStateToken() {
        return stateToken;
    }

    /**
     * A copy of this request carrying a different sealed work area.
     *
     * @param replacement the token; {@code null} becomes empty
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withStateToken(String replacement) {
        Builder builder = toBuilder();
        builder.stateToken = replacement;
        return builder.build();
    }

    /**
     * A copy of this request carrying a different work area.
     *
     * @param replacement the new work area; {@code null} becomes {@link CommArea#initialised()}
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withCommArea(CommArea replacement) {
        Builder builder = toBuilder();
        builder.commArea = replacement;
        return builder.build();
    }

    public CardScreenState getCardScreenState() {
        return new CardScreenState(cardScreenState);
    }

    /**
     * A copy of this request carrying a different card work area.
     *
     * @param replacement the new work area; {@code null} becomes a freshly constructed one, and a
     *     non-{@code null} one is copied
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withCardScreenState(CardScreenState replacement) {
        Builder builder = toBuilder();
        builder.cardScreenState = replacement;
        return builder.build();
    }

    /**
     * The {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA}.
     *
     * @return the communication area, or {@code null} for the {@code EIBCALEN = 0} cold start of
     *     {@code app/cbl/COACTUPC.cbl:880}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * A copy of this request carrying a different communication area.
     *
     * @param replacement the new communication area, or {@code null} to express the cold start
     * @return a new request, never {@code null}
     */
    public AccountUpdateRequest withNavigationContext(NavigationContext replacement) {
        Builder builder = toBuilder();
        builder.navigationContext = replacement;
        return builder.build();
    }

    /**
     * Whether a communication area was passed at all - the discriminator for the first disjunct of
     * {@code IF EIBCALEN IS EQUAL TO 0 OR ...} at {@code app/cbl/COACTUPC.cbl:880}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The number of bytes {@code EIBCALEN} would report for this request.
     *
     * @return {@code 0} for the cold start, otherwise {@link #TOTAL_COMMAREA_LENGTH}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? TOTAL_COMMAREA_LENGTH : 0;
    }

    /**
     * {@code CDEMO-PGM-CONTEXT}, the {@code ENTER} / {@code REENTER} flag.
     *
     * @return the raw context value, or {@code 0} - the {@code ENTER} state - when no communication area
     *     was passed
     */
    @JsonIgnore
    public int getPgmContext() {
        return hasNavigationContext() ? navigationContext.pgmContext() : 0;
    }

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry, so the program paints the screen and returns.
     *
     * @return {@code true} when this turn is a first entry
     */
    @JsonIgnore
    public boolean isEnter() {
        return !hasNavigationContext() || navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1} - the operator has been shown the screen and typed into it, so
     * the program validates the input.
     *
     * @return {@code true} when this turn is a re-entry
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * One field's value at exactly its declared width, through the {@code PIC X} move rule: padded on the
     * right with spaces when short, truncated on the right when long.
     *
     * @param field which field
     * @param codec the codec supplying the move rule
     * @return exactly {@link ScreenField#length()} characters
     * @throws NullPointerException if either argument is {@code null}
     */
    public String image(ScreenField field, FixedWidthCodec codec) {
        Objects.requireNonNull(field, "A ScreenField is required to render a field image");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render a field image: it owns "
                + "the PIC X move rule, which pads on the right and truncates on the right");
        return codec.movePicX(value(field), field.length());
    }

    /**
     * A copy of this request in which every field holds exactly its declared width, as though each had been
     * {@code MOVE}d into its {@code CACTUPAI} item.
     *
     * @param codec the codec supplying the move rule
     * @return a new request whose 54 values are all at their declared widths
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public AccountUpdateRequest normalize(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to normalise a request");
        Builder builder = toBuilder();
        for (ScreenField field : ScreenField.values()) {
            builder.value(field, image(field, codec));
        }
        return builder.build();
    }

    /**
     * Renders the whole {@link #GROUP_LENGTH}-byte {@code CACTUPAI} group - the storage a
     * {@code RECEIVE MAP INTO(CACTUPAI)} ({@code app/cbl/COACTUPC.cbl:1040-1045}) would have filled.
     *
     * @param codec the codec supplying both the move rule and the charset
     * @return a new array of exactly {@link #GROUP_LENGTH} bytes, never {@code null}
     * @throws NullPointerException if {@code codec} is {@code null}
     * @throws IllegalArgumentException if the codec's charset does not encode this data one byte per
     *     character
     */
    public byte[] toGroupImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the CACTUPAI group "
                + "image: it supplies both the PIC X move rule and the charset");
        byte[] group = new byte[GROUP_LENGTH];
        writeCharacters(group, 0, spaces(TIOAPFX_LENGTH), TIOAPFX_LENGTH, codec,
                "the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix");
        for (ScreenField field : ScreenField.values()) {
            FieldMetadata holder = metadata.get(field);
            int lengthItem = holder.lengthItem();
            group[field.lengthItemOffset()] = (byte) ((lengthItem >> 8) & 0xFF);
            group[field.lengthItemOffset() + 1] = (byte) (lengthItem & 0xFF);
            group[field.flagItemOffset()] = holder.attribute();
            Arrays.fill(group,
                    field.extendedAttributeItemOffset(),
                    field.extendedAttributeItemOffset() + EXTENDED_ATTRIBUTE_ITEM_LENGTH,
                    LOW_VALUE_BYTE);
            writeCharacters(group, field.dataOffset(), image(field, codec), field.length(), codec,
                    field.describe());
        }
        return group;
    }

    /**
     * Reads a {@link #GROUP_LENGTH}-byte {@code CACTUPAI} image back into a request.
     *
     * @param groupImage the {@link #GROUP_LENGTH}-byte input group; read, never retained
     * @param codec the codec supplying the charset
     * @return a request carrying the image's {@value #FIELD_COUNT} fields and their metadata
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code groupImage} is not exactly {@link #GROUP_LENGTH} bytes, or
     *     holds a length item outside the range {@code COMP PIC S9(4)} can represent
     */
    public static AccountUpdateRequest fromGroupImage(byte[] groupImage, FixedWidthCodec codec) {
        Objects.requireNonNull(groupImage, "A group image is required to read a CACTUPAI area");
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to read a CACTUPAI area: it "
                + "supplies the charset the field data is encoded in");
        if (groupImage.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("The CACTUPAI group of app/cpy-bms/COACTUP.CPY is "
                    + GROUP_LENGTH + " bytes - " + TIOAPFX_LENGTH + " of TIOAPFX prefix, plus "
                    + FIELD_COUNT + " fields at " + FIELD_OVERHEAD + " bytes of overhead each, plus "
                    + PAYLOAD_LENGTH + " bytes of data - but this image is " + groupImage.length
                    + " byte(s)");
        }
        Builder builder = new Builder();
        for (ScreenField field : ScreenField.values()) {
            builder.metadata(field, new FieldMetadata(decodeHalfword(groupImage, field),
                    groupImage[field.flagItemOffset()]));
            builder.value(field, readCharacters(groupImage, field, codec));
        }
        return builder.build();
    }

    /**
     * Reads one field's {@code xxxL COMP PIC S9(4)} item from a group image as a big-endian, signed
     * halfword, and rejects a value the {@code PICTURE} cannot represent.
     *
     * @param groupImage the group image to read from
     * @param field which field's length item to read
     * @return the halfword value
     * @throws IllegalArgumentException if the value is outside the {@code PIC S9(4)} range
     */
    private static int decodeHalfword(byte[] groupImage, ScreenField field) {
        int offset = field.lengthItemOffset();
        int halfword = (short) ((groupImage[offset] << 8) | (groupImage[offset + 1] & 0xFF));
        if (halfword < FieldMetadata.LENGTH_ITEM_MIN || halfword > FieldMetadata.LENGTH_ITEM_MAX) {
            throw new IllegalArgumentException("The xxxL item of " + field.describe() + " reads "
                    + halfword + " at offset " + offset + ", which COMP PIC S9(4) cannot represent: it "
                    + "holds " + FieldMetadata.LENGTH_ITEM_MIN + " to " + FieldMetadata.LENGTH_ITEM_MAX);
        }
        return halfword;
    }

    private static String readCharacters(byte[] groupImage, ScreenField field, FixedWidthCodec codec) {
        byte[] span = Arrays.copyOfRange(groupImage, field.dataOffset(), field.endOffsetExclusive());
        return codec.decodeImage(span, "the CACTUPAI item " + field.symbolicItemName());
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
                    + "; a PIC X(n) item is n bytes, so the CACTUPAI group can only be rendered from an "
                    + "image already at its declared width under a single-byte code page such as IBM037 "
                    + "or US-ASCII");
        }
        System.arraycopy(encoded, 0, group, offset, declaredWidth);
    }

    /**
     * Value equality over the {@value #FIELD_COUNT} fields, all three carriers and all
     * {@value #FIELD_COUNT} metadata pairs.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a request holding the same values throughout
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountUpdateRequest that)) {
            return false;
        }
        return sameFieldValues(that)
                && metadata.equals(that.metadata)
                && commArea.equals(that.commArea)
                && stateToken.equals(that.stateToken)
                && cardScreenState.equals(that.cardScreenState)
                && Objects.equals(navigationContext, that.navigationContext);
    }

    private boolean sameFieldValues(AccountUpdateRequest that) {
        for (ScreenField field : ScreenField.values()) {
            if (!value(field).equals(that.value(field))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + value(field).hashCode();
        }
        result = 31 * result + metadata.hashCode();
        result = 31 * result + commArea.hashCode();
        result = 31 * result + stateToken.hashCode();
        result = 31 * result + cardScreenState.hashCode();
        return 31 * result + Objects.hashCode(navigationContext);
    }

    /**
     * A diagnostic rendering that discloses nothing a log must not hold.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder("AccountUpdateRequest[");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(field.label())
                    .append("='")
                    .append(DiagnosticText.screenField(field.label(), value(field)))
                    .append("' ")
                    .append(metadata.get(field))
                    .append(", ");
        }
        return rendered.append("commArea=")
                .append(commArea)
                .append(", cardScreenState=")
                .append(cardScreenState)
                .append(", navigationContext=")
                .append(navigationContext)
                .append(']')
                .toString();
    }

    /**
     * One of the {@value AccountUpdateRequest#FIELD_COUNT} name-labelled {@code DFHMDF} fields of
     * {@code app/bms/COACTUP.bms}, in the order the mapset declares them - which is also the order
     * {@code app/cpy-bms/COACTUP.CPY} lays their storage down.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAME} - the transaction identifier, {@code TRNNAMEI PIC X(4)} at {@code POS=(1,7)}.
         */
        TRNNAME("TRNNAME", "TRNNAMEI", "X(4)", TRNNAME_LENGTH, 24, 34, 1, 7, 19),

        /**
         * {@code TITLE01} - the first title line, {@code TITLE01I PIC X(40)} at {@code POS=(1,21)}.
         */
        TITLE01("TITLE01", "TITLE01I", "X(40)", TITLE01_LENGTH, 30, 38, 1, 21, 30),

        /**
         * {@code CURDATE} - the current date, {@code CURDATEI PIC X(8)} at {@code POS=(1,71)},
         * {@code INITIAL='mm/dd/yy'}.
         */
        CURDATE("CURDATE", "CURDATEI", "X(8)", CURDATE_LENGTH, 36, 47, 1, 71, 77),

        /**
         * {@code PGMNAME} - the program name, {@code PGMNAMEI PIC X(8)} at {@code POS=(2,7)}.
         */
        PGMNAME("PGMNAME", "PGMNAMEI", "X(8)", PGMNAME_LENGTH, 42, 57, 2, 7, 92),

        /**
         * {@code TITLE02} - the second title line, {@code TITLE02I PIC X(40)} at {@code POS=(2,21)}.
         */
        TITLE02("TITLE02", "TITLE02I", "X(40)", TITLE02_LENGTH, 48, 61, 2, 21, 107),

        /**
         * {@code CURTIME} - the current time, {@code CURTIMEI PIC X(8)} at {@code POS=(2,71)},
         * {@code INITIAL='hh:mm:ss'}.
         */
        CURTIME("CURTIME", "CURTIMEI", "X(8)", CURTIME_LENGTH, 54, 70, 2, 71, 154),

        /**
         * {@code ACCTSID} - the account filter, and the field the insertion cursor starts on,
         * {@code ACCTSIDI PIC X(11)} at {@code POS=(5,38)}.
         */
        ACCTSID("ACCTSID", "ACCTSIDI", "X(11)", ACCTSID_LENGTH, 60, 84, 5, 38, 169),

        /**
         * {@code ACSTTUS} - the active status, {@code ACSTTUSI PIC X(1)} at {@code POS=(5,70)}.
         */
        ACSTTUS("ACSTTUS", "ACSTTUSI", "X(1)", ACSTTUS_LENGTH, 66, 94, 5, 70, 187),

        /**
         * {@code OPNYEAR} - the open date year, {@code OPNYEARI PIC X(4)} at {@code POS=(6,17)}.
         */
        OPNYEAR("OPNYEAR", "OPNYEARI", "X(4)", OPNYEAR_LENGTH, 72, 104, 6, 17, 195),

        /**
         * {@code OPNMON} - the open date month, {@code OPNMONI PIC X(2)} at {@code POS=(6,24)}.
         */
        OPNMON("OPNMON", "OPNMONI", "X(2)", OPNMON_LENGTH, 78, 112, 6, 24, 206),

        /**
         * {@code OPNDAY} - the open date day, {@code OPNDAYI PIC X(2)} at {@code POS=(6,29)}.
         */
        OPNDAY("OPNDAY", "OPNDAYI", "X(2)", OPNDAY_LENGTH, 84, 120, 6, 29, 215),

        /**
         * {@code ACRDLIM} - the credit limit, {@code ACRDLIMI PIC X(15)} at {@code POS=(6,61)}.
         */
        ACRDLIM("ACRDLIM", "ACRDLIMI", "X(15)", ACRDLIM_LENGTH, 90, 132, 6, 61, 224),

        /**
         * {@code EXPYEAR} - the expiry date year, {@code EXPYEARI PIC X(4)} at {@code POS=(7,17)}.
         */
        EXPYEAR("EXPYEAR", "EXPYEARI", "X(4)", EXPYEAR_LENGTH, 96, 142, 7, 17, 246),

        /**
         * {@code EXPMON} - the expiry date month, {@code EXPMONI PIC X(2)} at {@code POS=(7,24)}.
         */
        EXPMON("EXPMON", "EXPMONI", "X(2)", EXPMON_LENGTH, 102, 150, 7, 24, 257),

        /**
         * {@code EXPDAY} - the expiry date day, {@code EXPDAYI PIC X(2)} at {@code POS=(7,29)}.
         */
        EXPDAY("EXPDAY", "EXPDAYI", "X(2)", EXPDAY_LENGTH, 108, 158, 7, 29, 266),

        /**
         * {@code ACSHLIM} - the cash credit limit, {@code ACSHLIMI PIC X(15)} at {@code POS=(7,61)}.
         */
        ACSHLIM("ACSHLIM", "ACSHLIMI", "X(15)", ACSHLIM_LENGTH, 114, 170, 7, 61, 275),

        /**
         * {@code RISYEAR} - the reissue date year, {@code RISYEARI PIC X(4)} at {@code POS=(8,17)}.
         */
        RISYEAR("RISYEAR", "RISYEARI", "X(4)", RISYEAR_LENGTH, 120, 180, 8, 17, 297),

        /**
         * {@code RISMON} - the reissue date month, {@code RISMONI PIC X(2)} at {@code POS=(8,24)}.
         */
        RISMON("RISMON", "RISMONI", "X(2)", RISMON_LENGTH, 126, 188, 8, 24, 308),

        /**
         * {@code RISDAY} - the reissue date day, {@code RISDAYI PIC X(2)} at {@code POS=(8,29)}.
         */
        RISDAY("RISDAY", "RISDAYI", "X(2)", RISDAY_LENGTH, 132, 196, 8, 29, 317),

        /**
         * {@code ACURBAL} - the current balance, {@code ACURBALI PIC X(15)} at {@code POS=(8,61)}.
         */
        ACURBAL("ACURBAL", "ACURBALI", "X(15)", ACURBAL_LENGTH, 138, 208, 8, 61, 326),

        /**
         * {@code ACRCYCR} - the current cycle credit, {@code ACRCYCRI PIC X(15)} at {@code POS=(9,61)}.
         */
        ACRCYCR("ACRCYCR", "ACRCYCRI", "X(15)", ACRCYCR_LENGTH, 144, 219, 9, 61, 348),

        /**
         * {@code AADDGRP} - the account group identifier, {@code AADDGRPI PIC X(10)} at
         * {@code POS=(10,23)}.
         */
        AADDGRP("AADDGRP", "AADDGRPI", "X(10)", AADDGRP_LENGTH, 150, 229, 10, 23, 370),

        /**
         * {@code ACRCYDB} - the current cycle debit, {@code ACRCYDBI PIC X(15)} at {@code POS=(10,61)}.
         */
        ACRCYDB("ACRCYDB", "ACRCYDBI", "X(15)", ACRCYDB_LENGTH, 156, 240, 10, 61, 387),

        /**
         * {@code ACSTNUM} - the customer number, {@code ACSTNUMI PIC X(9)} at {@code POS=(12,23)}.
         */
        ACSTNUM("ACSTNUM", "ACSTNUMI", "X(9)", ACSTNUM_LENGTH, 162, 254, 12, 23, 409),

        /**
         * {@code ACTSSN1} - social security number part one, {@code ACTSSN1I PIC X(3)} at
         * {@code POS=(12,55)}, {@code INITIAL='999'}.
         */
        ACTSSN1("ACTSSN1", "ACTSSN1I", "X(3)", ACTSSN1_LENGTH, 168, 264, 12, 55, 425),

        /**
         * {@code ACTSSN2} - social security number part two, {@code ACTSSN2I PIC X(2)} at
         * {@code POS=(12,61)}, {@code INITIAL='99'}.
         */
        ACTSSN2("ACTSSN2", "ACTSSN2I", "X(2)", ACTSSN2_LENGTH, 174, 272, 12, 61, 435),

        /**
         * {@code ACTSSN3} - social security number part three, {@code ACTSSN3I PIC X(4)} at
         * {@code POS=(12,66)}, {@code INITIAL='9999'}.
         */
        ACTSSN3("ACTSSN3", "ACTSSN3I", "X(4)", ACTSSN3_LENGTH, 180, 280, 12, 66, 444),

        /**
         * {@code DOBYEAR} - the date of birth year, {@code DOBYEARI PIC X(4)} at {@code POS=(13,23)}.
         */
        DOBYEAR("DOBYEAR", "DOBYEARI", "X(4)", DOBYEAR_LENGTH, 186, 291, 13, 23, 455),

        /**
         * {@code DOBMON} - the date of birth month, {@code DOBMONI PIC X(2)} at {@code POS=(13,30)}.
         */
        DOBMON("DOBMON", "DOBMONI", "X(2)", DOBMON_LENGTH, 192, 299, 13, 30, 466),

        /**
         * {@code DOBDAY} - the date of birth day, {@code DOBDAYI PIC X(2)} at {@code POS=(13,35)}.
         */
        DOBDAY("DOBDAY", "DOBDAYI", "X(2)", DOBDAY_LENGTH, 198, 307, 13, 35, 475),

        /**
         * {@code ACSTFCO} - the FICO credit score, {@code ACSTFCOI PIC X(3)} at {@code POS=(13,62)}.
         */
        ACSTFCO("ACSTFCO", "ACSTFCOI", "X(3)", ACSTFCO_LENGTH, 204, 318, 13, 62, 484),

        /**
         * {@code ACSFNAM} - the first name, {@code ACSFNAMI PIC X(25)} at {@code POS=(15,1)}.
         */
        ACSFNAM("ACSFNAM", "ACSFNAMI", "X(25)", ACSFNAM_LENGTH, 210, 336, 15, 1, 494),

        /**
         * {@code ACSMNAM} - the middle name, {@code ACSMNAMI PIC X(25)} at {@code POS=(15,28)}.
         */
        ACSMNAM("ACSMNAM", "ACSMNAMI", "X(25)", ACSMNAM_LENGTH, 216, 342, 15, 28, 526),

        /**
         * {@code ACSLNAM} - the last name, {@code ACSLNAMI PIC X(25)} at {@code POS=(15,55)}.
         */
        ACSLNAM("ACSLNAM", "ACSLNAMI", "X(25)", ACSLNAM_LENGTH, 222, 348, 15, 55, 558),

        /**
         * {@code ACSADL1} - address line one, {@code ACSADL1I PIC X(50)} at {@code POS=(16,10)}.
         */
        ACSADL1("ACSADL1", "ACSADL1I", "X(50)", ACSADL1_LENGTH, 228, 356, 16, 10, 590),

        /**
         * {@code ACSSTTE} - the state code, {@code ACSSTTEI PIC X(2)} at {@code POS=(16,73)}.
         */
        ACSSTTE("ACSSTTE", "ACSSTTEI", "X(2)", ACSSTTE_LENGTH, 234, 366, 16, 73, 647),

        /**
         * {@code ACSADL2} - address line two, {@code ACSADL2I PIC X(50)} at {@code POS=(17,10)}.
         */
        ACSADL2("ACSADL2", "ACSADL2I", "X(50)", ACSADL2_LENGTH, 240, 372, 17, 10, 656),

        /**
         * {@code ACSZIPC} - the postal code, {@code ACSZIPCI PIC X(5)} at {@code POS=(17,73)}.
         */
        ACSZIPC("ACSZIPC", "ACSZIPCI", "X(5)", ACSZIPC_LENGTH, 246, 382, 17, 73, 713),

        /**
         * {@code ACSCITY} - the city, {@code ACSCITYI PIC X(50)} at {@code POS=(18,10)}.
         */
        ACSCITY("ACSCITY", "ACSCITYI", "X(50)", ACSCITY_LENGTH, 252, 392, 18, 10, 725),

        /**
         * {@code ACSCTRY} - the country code, {@code ACSCTRYI PIC X(3)} at {@code POS=(18,73)}.
         */
        ACSCTRY("ACSCTRY", "ACSCTRYI", "X(3)", ACSCTRY_LENGTH, 258, 402, 18, 73, 782),

        /**
         * {@code ACSPH1A} - telephone one area code, {@code ACSPH1AI PIC X(3)} at {@code POS=(19,10)}.
         */
        ACSPH1A("ACSPH1A", "ACSPH1AI", "X(3)", ACSPH1A_LENGTH, 264, 412, 19, 10, 792),

        /**
         * {@code ACSPH1B} - telephone one prefix, {@code ACSPH1BI PIC X(3)} at {@code POS=(19,14)}.
         */
        ACSPH1B("ACSPH1B", "ACSPH1BI", "X(3)", ACSPH1B_LENGTH, 270, 417, 19, 14, 802),

        /**
         * {@code ACSPH1C} - telephone one line number, {@code ACSPH1CI PIC X(4)} at {@code POS=(19,18)}.
         */
        ACSPH1C("ACSPH1C", "ACSPH1CI", "X(4)", ACSPH1C_LENGTH, 276, 422, 19, 18, 812),

        /**
         * {@code ACSGOVT} - the government-issued identifier reference, {@code ACSGOVTI PIC X(20)} at
         * {@code POS=(19,58)}.
         */
        ACSGOVT("ACSGOVT", "ACSGOVTI", "X(20)", ACSGOVT_LENGTH, 282, 433, 19, 58, 823),

        /**
         * {@code ACSPH2A} - telephone two area code, {@code ACSPH2AI PIC X(3)} at {@code POS=(20,10)}.
         */
        ACSPH2A("ACSPH2A", "ACSPH2AI", "X(3)", ACSPH2A_LENGTH, 288, 443, 20, 10, 850),

        /**
         * {@code ACSPH2B} - telephone two prefix, {@code ACSPH2BI PIC X(3)} at {@code POS=(20,14)}.
         */
        ACSPH2B("ACSPH2B", "ACSPH2BI", "X(3)", ACSPH2B_LENGTH, 294, 448, 20, 14, 860),

        /**
         * {@code ACSPH2C} - telephone two line number, {@code ACSPH2CI PIC X(4)} at {@code POS=(20,18)}.
         */
        ACSPH2C("ACSPH2C", "ACSPH2CI", "X(4)", ACSPH2C_LENGTH, 300, 453, 20, 18, 870),

        /**
         * {@code ACSEFTC} - the electronic funds transfer account identifier, {@code ACSEFTCI PIC X(10)} at
         * {@code POS=(20,41)}.
         */
        ACSEFTC("ACSEFTC", "ACSEFTCI", "X(10)", ACSEFTC_LENGTH, 306, 464, 20, 41, 881),

        /**
         * {@code ACSPFLG} - the primary card holder indicator, {@code ACSPFLGI PIC X(1)} at
         * {@code POS=(20,78)}.
         */
        ACSPFLG("ACSPFLG", "ACSPFLGI", "X(1)", ACSPFLG_LENGTH, 312, 474, 20, 78, 898),

        /**
         * {@code INFOMSG} - the informational message line, {@code INFOMSGI PIC X(45)} at
         * {@code POS=(22,23)}.
         */
        INFOMSG("INFOMSG", "INFOMSGI", "X(45)", INFOMSG_LENGTH, 318, 480, 22, 23, 906),

        /**
         * {@code ERRMSG} - the error message line, {@code ERRMSGI PIC X(78)} at {@code POS=(23,1)}.
         */
        ERRMSG("ERRMSG", "ERRMSGI", "X(78)", ERRMSG_LENGTH, 324, 489, 23, 1, 958),

        /**
         * {@code FKEYS} - the always-visible function-key legend, {@code FKEYSI PIC X(21)} at
         * {@code POS=(24,1)}, {@code INITIAL='ENTER=Process F3=Exit'}.
         */
        FKEYS("FKEYS", "FKEYSI", "X(21)", FKEYS_LENGTH, 330, 493, 24, 1, 1043),

        /**
         * {@code FKEY05} - the conditionally revealed save legend, {@code FKEY05I PIC X(7)} at
         * {@code POS=(24,23)}, {@code INITIAL='F5=Save'}.
         */
        FKEY05("FKEY05", "FKEY05I", "X(7)", FKEY05_LENGTH, 336, 498, 24, 23, 1071),

        /**
         * {@code FKEY12} - the conditionally revealed cancel legend, {@code FKEY12I PIC X(10)} at
         * {@code POS=(24,31)}, {@code INITIAL='F12=Cancel'}.
         */
        FKEY12("FKEY12", "FKEY12I", "X(10)", FKEY12_LENGTH, 342, 503, 24, 31, 1085);

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
         * The symbolic-map input item name, as {@code app/cpy-bms/COACTUP.CPY} declares it.
         *
         * @return the {@code xxxI} item name
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

        /**
         * Whether this field's {@code PICTURE} is alphanumeric.
         *
         * @return {@code true} for every field of this mapset, which is the property being asserted
         */
        public boolean isAlphanumeric() {
            return picture.startsWith("X(");
        }

        public int length() {
            return length;
        }

        public int copybookLine() {
            return copybookLine;
        }

        /**
         * The declaring line of the mapset.
         *
         * @return the {@code app/bms/COACTUP.bms} line number
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
         * The absolute 0-based offset of this field's data within the {@link #GROUP_LENGTH}-byte group
         * image.
         *
         * @return the data offset
         */
        public int dataOffset() {
            return dataOffset;
        }

        /**
         * The absolute 0-based offset of this field's {@code xxxL COMP PIC S9(4)} halfword - seven bytes
         * ahead of the data, because that is what the field's five items cost.
         *
         * @return the length item's offset
         */
        public int lengthItemOffset() {
            return dataOffset - FIELD_OVERHEAD;
        }

        /**
         * The absolute 0-based offset of this field's {@code xxxF PICTURE X} flag byte, which
         * {@code 03 xxxA PICTURE X} redefines.
         *
         * @return the flag and attribute byte's offset
         */
        public int flagItemOffset() {
            return lengthItemOffset() + LENGTH_ITEM_LENGTH;
        }

        /**
         * The absolute 0-based offset of this field's unnamed {@code 02 FILLER PICTURE X(4)}
         * extended-attribute span.
         *
         * @return the extended-attribute {@code FILLER}'s offset
         */
        public int extendedAttributeItemOffset() {
            return flagItemOffset() + FLAG_ITEM_LENGTH;
        }

        /**
         * One past this field's last data byte, which is also the next field's {@link #lengthItemOffset()}.
         *
         * @return the exclusive end offset of the data span
         */
        public int endOffsetExclusive() {
            return dataOffset + length;
        }

        /**
         * A one-line description naming everything a failure message needs.
         *
         * @return the label, item name, picture, screen position and both source lines
         */
        public String describe() {
            return label + " (" + symbolicItemName + " PIC " + picture + " at POS=(" + screenRow + ","
                    + screenColumn + "), app/bms/COACTUP.bms:" + mapsetLine
                    + ", app/cpy-bms/COACTUP.CPY:" + copybookLine + ")";
        }

        /**
         * Looks a field up by its {@code DFHMDF} label, for a differ or a case fixture that carries the
         * copybook name rather than an enum constant.
         *
         * @param label the {@code DFHMDF} label, matched exactly
         * @return the field
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field carries that label
         */
        public static ScreenField ofLabel(String label) {
            Objects.requireNonNull(label, "A DFHMDF label is required to look a field up");
            for (ScreenField field : values()) {
                if (field.label.equals(label)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("app/bms/COACTUP.bms declares no name-labelled DFHMDF "
                    + "field called '" + label + "'; it declares " + FIELD_COUNT + ", from "
                    + TRNNAME.label + " to " + FKEY12.label);
        }
    }

    /**
     * The {@code xxxL} and {@code xxxA} items of one input field: the two pieces of per-field metadata a
     * BMS symbolic map declares beside the data, and the two {@code COACTUPC} actually writes.
     *
     * <p>Rejecting a value the copybook cannot represent is the point - silently storing 30000 in a field
     * declared {@code S9(4)} is precisely the kind of divergence a parity migration exists to prevent.
     *
     * @param lengthItem the {@code xxxL COMP PIC S9(4)} halfword; {@value #LENGTH_UNSET} for a field CICS
     *     reports as not entered, {@link #CURSOR_HERE} to request the cursor
     * @param attribute the single byte declared {@code xxxF PICTURE X} and redefined
     *     {@code xxxA PICTURE X}; {@code LOW-VALUES} when untouched
     */
    public record FieldMetadata(int lengthItem, byte attribute) {
        /**
         * Lowest value {@code xxxL COMP PIC S9(4)} can represent: four digits and a sign.
         */
        public static final int LENGTH_ITEM_MIN = -9999;

        /**
         * Highest value {@code xxxL COMP PIC S9(4)} can represent.
         */
        public static final int LENGTH_ITEM_MAX = 9999;

        /**
         * The length item of a field CICS reports as not entered, and the value a freshly initialised map
         * area holds.
         */
        public static final int LENGTH_UNSET = 0;

        /**
         * The length item that asks CICS to place the cursor on this field - the {@code MOVE -1 TO xxxL}
         * convention {@code COACTUPC} uses at 41 sites, {@code app/cbl/COACTUPC.cbl:3015} and {@code :3166}
         * among them.
         */
        public static final int CURSOR_HERE = -1;

        /**
         * The unset attribute byte: {@code LOW-VALUES}, which is binary zero on every code page because it
         * is by definition the lowest character of the collating sequence.
         */
        public static final byte ATTRIBUTE_UNSET = LOW_VALUE_BYTE;

        /**
         * Enforces the declared {@code PICTURE} rather than the halfword's wider capacity.
         */
        public FieldMetadata {
            if (lengthItem < LENGTH_ITEM_MIN || lengthItem > LENGTH_ITEM_MAX) {
                throw new IllegalArgumentException("An xxxL item is COMP PIC S9(4) and holds "
                        + LENGTH_ITEM_MIN + " to " + LENGTH_ITEM_MAX + ", but was given " + lengthItem
                        + "; the PICTURE is the constraint, not the halfword's wider capacity");
            }
        }

        /**
         * The state a map area holds before CICS or the program touches it: no length reported and a
         * {@code LOW-VALUES} attribute byte.
         *
         * @return the unset pair
         */
        public static FieldMetadata unset() {
            return new FieldMetadata(LENGTH_UNSET, ATTRIBUTE_UNSET);
        }

        /**
         * A pair with the cursor requested on this field and no attribute assigned.
         *
         * @return a pair reporting {@link #isCursorHere()}
         */
        public static FieldMetadata cursorHere() {
            return new FieldMetadata(CURSOR_HERE, ATTRIBUTE_UNSET);
        }

        /**
         * A pair with a given attribute byte and no length reported.
         *
         * @param attribute the attribute byte, from {@code common/BmsAttributes}
         * @return the pair
         */
        public static FieldMetadata withAttributeOnly(byte attribute) {
            return new FieldMetadata(LENGTH_UNSET, attribute);
        }

        /**
         * The {@code xxxF} view of the single byte this pair holds.
         *
         * @return the same byte {@link #attribute()} returns
         */
        @JsonIgnore
        public byte flag() {
            return attribute;
        }

        public FieldMetadata withLengthItem(int replacement) {
            return new FieldMetadata(replacement, attribute);
        }

        /**
         * This pair with the cursor requested - the immutable form of {@code MOVE -1 TO xxxL}.
         *
         * @return a new pair reporting {@link #isCursorHere()}
         */
        public FieldMetadata withCursorHere() {
            return new FieldMetadata(CURSOR_HERE, attribute);
        }

        /**
         * This pair with a different attribute byte - the immutable form of {@code MOVE DFHxxx TO xxxA}.
         *
         * @param replacement the new attribute byte, from {@code common/BmsAttributes}
         * @return a new pair
         */
        public FieldMetadata withAttribute(byte replacement) {
            return new FieldMetadata(lengthItem, replacement);
        }

        @JsonIgnore
        public boolean isCursorHere() {
            return lengthItem == CURSOR_HERE;
        }

        /**
         * Whether CICS reported any characters for this field - the "was this field entered?" question a
         * symbolic map answers with its length item.
         *
         * @return {@code true} when the length item is above {@value #LENGTH_UNSET}
         */
        @JsonIgnore
        public boolean isEntered() {
            return lengthItem > LENGTH_UNSET;
        }

        /**
         * Whether the attribute byte is still {@code LOW-VALUES}, meaning neither CICS nor the program has
         * assigned one.
         *
         * @return {@code true} when the attribute byte is unset
         */
        @JsonIgnore
        public boolean isAttributeUnset() {
            return attribute == ATTRIBUTE_UNSET;
        }

        /**
         * Whether the attribute byte protects the field from typing, per
         * {@link BmsAttributes#isProtected(byte)}.
         *
         * @return {@code true} when the field is protected
         */
        @JsonIgnore
        public boolean isProtectedField() {
            return BmsAttributes.isProtected(attribute);
        }

        /**
         * Whether the attribute byte is {@link BmsAttributes#DFHBMASB} - autoskip, bright - which is what
         * {@code app/cbl/COACTUPC.cbl:3575} and {@code :3579-3580} move into {@code FKEY12A} and
         * {@code FKEY05A} to reveal a legend declared {@code ATTRB=(ASKIP,DRK)}.
         *
         * @return {@code true} when the field has been made bright and skipped over
         */
        @JsonIgnore
        public boolean isBright() {
            return attribute == BmsAttributes.DFHBMASB;
        }

        /**
         * A rendering naming the length item and the attribute byte by mnemonic, so a failure message reads
         * {@code DFHBMPRF} rather than {@code 0x61}.
         *
         * @return a compact description of the pair
         */
        @Override
        public String toString() {
            return "{xxxL=" + lengthItem + (isCursorHere() ? " (cursor here)" : "")
                    + ", xxxA=" + BmsAttributes.toHex(attribute)
                    + " " + BmsAttributes.fieldAttributeMnemonic(attribute) + "}";
        }
    }

    /**
     * {@code 10 ACUP-CHANGE-ACTION PIC X(1) VALUE LOW-VALUES}, the single byte of
     * {@code 05 ACCT-UPDATE-SCREEN-DATA} - {@code app/cbl/COACTUPC.cbl:653-668}.
     *
     * @param value exactly one character - the byte itself, never a decoded meaning
     */
    public record ChangeAction(String value) {
        /**
         * {@code VALUE LOW-VALUES}, the declared initial state: {@code x'00'}.
         */
        public static final String LOW_VALUES = "\u0000";

        /**
         * {@code SPACES}, the other byte satisfying {@code ACUP-DETAILS-NOT-FETCHED}.
         */
        public static final String SPACES = " ";

        /**
         * {@code 88 ACUP-SHOW-DETAILS VALUE 'S'}, {@code app/cbl/COACTUPC.cbl:659}.
         */
        public static final String SHOW_DETAILS = "S";

        /**
         * {@code 88 ACUP-CHANGES-NOT-OK VALUE 'E'}, {@code app/cbl/COACTUPC.cbl:663}.
         */
        public static final String CHANGES_NOT_OK = "E";

        /**
         * {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'}, {@code app/cbl/COACTUPC.cbl:664}.
         */
        public static final String CHANGES_OK_NOT_CONFIRMED = "N";

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'}, {@code app/cbl/COACTUPC.cbl:665}.
         */
        public static final String CHANGES_OKAYED_AND_DONE = "C";

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'}, {@code app/cbl/COACTUPC.cbl:667}.
         */
        public static final String CHANGES_OKAYED_LOCK_ERROR = "L";

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'}, {@code app/cbl/COACTUPC.cbl:668}.
         */
        public static final String CHANGES_OKAYED_BUT_FAILED = "F";

        /**
         * Declared width of {@code ACUP-CHANGE-ACTION PIC X(1)}.
         */
        public static final int RECORD_LENGTH = 1;

        public static final String FIELD_NAME = "ACUP-CHANGE-ACTION";

        /**
         * Verbatim COBOL name of the {@code 05}-level group holding the byte.
         */
        public static final String GROUP_NAME = "ACCT-UPDATE-SCREEN-DATA";

        /**
         * The two values {@code 88 ACUP-DETAILS-NOT-FETCHED} covers, verbatim from
         * {@code app/cbl/COACTUPC.cbl:656-658}.
         */
        public static final List<String> DETAILS_NOT_FETCHED_VALUES = List.of(LOW_VALUES, SPACES);

        /**
         * The five values {@code 88 ACUP-CHANGES-MADE} covers, verbatim from
         * {@code app/cbl/COACTUPC.cbl:660-662}.
         */
        public static final List<String> CHANGES_MADE_VALUES = List.of(CHANGES_NOT_OK,
                CHANGES_OK_NOT_CONFIRMED,
                CHANGES_OKAYED_AND_DONE,
                CHANGES_OKAYED_LOCK_ERROR,
                CHANGES_OKAYED_BUT_FAILED);

        /**
         * The two values {@code 88 ACUP-CHANGES-FAILED} covers, verbatim from
         * {@code app/cbl/COACTUPC.cbl:666}.
         */
        public static final List<String> CHANGES_FAILED_VALUES =
                List.of(CHANGES_OKAYED_LOCK_ERROR, CHANGES_OKAYED_BUT_FAILED);

        public ChangeAction {
            Objects.requireNonNull(value, "A change-action byte is required; ACUP-CHANGE-ACTION is PIC "
                    + "X(1) with VALUE LOW-VALUES, so its unset state is x'00' and never a Java null");
            if (value.length() != RECORD_LENGTH) {
                throw new IllegalArgumentException("ACUP-CHANGE-ACTION is PIC X(1) and holds exactly one "
                        + "character, but was given " + value.length());
            }
        }

        /**
         * The declared initial state, {@code VALUE LOW-VALUES} - what
         * {@code INITIALIZE WS-THIS-PROGCOMMAREA} and {@code SET ACUP-DETAILS-NOT-FETCHED TO TRUE}
         * ({@code app/cbl/COACTUPC.cbl:886}) leave behind.
         *
         * @return a state satisfying {@link #isDetailsNotFetched()}
         */
        public static ChangeAction initial() {
            return new ChangeAction(LOW_VALUES);
        }

        /**
         * The other state satisfying {@code ACUP-DETAILS-NOT-FETCHED}: {@code SPACES}.
         *
         * @return a state satisfying {@link #isDetailsNotFetched()}
         */
        public static ChangeAction spacesState() {
            return new ChangeAction(SPACES);
        }

        /**
         * The {@code 'S'} state, which is what {@code SET ACUP-SHOW-DETAILS TO TRUE} sets.
         *
         * @return the show-details state
         */
        public static ChangeAction showDetails() {
            return new ChangeAction(SHOW_DETAILS);
        }

        /**
         * The {@code 'E'} state, which is what {@code SET ACUP-CHANGES-NOT-OK TO TRUE} sets.
         *
         * @return the changes-not-ok state
         */
        public static ChangeAction changesNotOk() {
            return new ChangeAction(CHANGES_NOT_OK);
        }

        /**
         * The {@code 'N'} state, which is what {@code SET ACUP-CHANGES-OK-NOT-CONFIRMED TO TRUE} sets.
         *
         * @return the changes-ok-not-confirmed state
         */
        public static ChangeAction changesOkNotConfirmed() {
            return new ChangeAction(CHANGES_OK_NOT_CONFIRMED);
        }

        /**
         * The {@code 'C'} state, which is what {@code SET ACUP-CHANGES-OKAYED-AND-DONE TO TRUE} sets.
         *
         * @return the changes-okayed-and-done state
         */
        public static ChangeAction changesOkayedAndDone() {
            return new ChangeAction(CHANGES_OKAYED_AND_DONE);
        }

        /**
         * The {@code 'L'} state, which is what {@code SET ACUP-CHANGES-OKAYED-LOCK-ERROR TO TRUE} sets.
         *
         * @return the lock-error state
         */
        public static ChangeAction changesOkayedLockError() {
            return new ChangeAction(CHANGES_OKAYED_LOCK_ERROR);
        }

        /**
         * The {@code 'F'} state, which is what {@code SET ACUP-CHANGES-OKAYED-BUT-FAILED TO TRUE} sets.
         *
         * @return the okayed-but-failed state
         */
        public static ChangeAction changesOkayedButFailed() {
            return new ChangeAction(CHANGES_OKAYED_BUT_FAILED);
        }

        /**
         * Wraps an arbitrary byte, including one no {@code 88}-level covers, so the {@code WHEN OTHER} path
         * of the decision logic stays reachable.
         *
         * @param value exactly one character
         * @return the state
         * @throws NullPointerException if {@code value} is {@code null}
         * @throws IllegalArgumentException if {@code value} is not exactly one character
         */
        public static ChangeAction of(String value) {
            return new ChangeAction(value);
        }

        public static ChangeAction of(char value) {
            return new ChangeAction(String.valueOf(value));
        }

        /**
         * {@code 88 ACUP-DETAILS-NOT-FETCHED VALUES LOW-VALUES, SPACES} - condition 1 of 9.
         *
         * @return {@code true} for {@code x'00'} and for a space, and for nothing else
         */
        @JsonIgnore
        public boolean isDetailsNotFetched() {
            return DETAILS_NOT_FETCHED_VALUES.contains(value);
        }

        /**
         * {@code 88 ACUP-SHOW-DETAILS VALUE 'S'} - condition 2 of 9.
         *
         * @return {@code true} for {@code 'S'}
         */
        @JsonIgnore
        public boolean isShowDetails() {
            return SHOW_DETAILS.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-MADE VALUES 'E','N','C','L','F'} - condition 3 of 9, and the first of the
         * two grouping levels.
         *
         * @return {@code true} for any of the five values
         */
        @JsonIgnore
        public boolean isChangesMade() {
            return CHANGES_MADE_VALUES.contains(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-NOT-OK VALUE 'E'} - condition 4 of 9.
         *
         * @return {@code true} for {@code 'E'}
         */
        @JsonIgnore
        public boolean isChangesNotOk() {
            return CHANGES_NOT_OK.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED VALUE 'N'} - condition 5 of 9, and the state in which
         * {@code app/cbl/COACTUPC.cbl:908} admits {@code F5}.
         *
         * @return {@code true} for {@code 'N'}
         */
        @JsonIgnore
        public boolean isChangesOkNotConfirmed() {
            return CHANGES_OK_NOT_CONFIRMED.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-AND-DONE VALUE 'C'} - condition 6 of 9, and the first arm of the
         * pair at {@code app/cbl/COACTUPC.cbl:979-980} that resets the work area for a fresh search.
         *
         * @return {@code true} for {@code 'C'}
         */
        @JsonIgnore
        public boolean isChangesOkayedAndDone() {
            return CHANGES_OKAYED_AND_DONE.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-FAILED VALUES 'L','F'} - condition 7 of 9, the second grouping level, and
         * the second arm of that pair.
         *
         * @return {@code true} for {@code 'L'} and for {@code 'F'}
         */
        @JsonIgnore
        public boolean isChangesFailed() {
            return CHANGES_FAILED_VALUES.contains(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-LOCK-ERROR VALUE 'L'} - condition 8 of 9: the rewrite was
         * authorised but the record had changed underneath it.
         *
         * @return {@code true} for {@code 'L'}
         */
        @JsonIgnore
        public boolean isChangesOkayedLockError() {
            return CHANGES_OKAYED_LOCK_ERROR.equals(value);
        }

        /**
         * {@code 88 ACUP-CHANGES-OKAYED-BUT-FAILED VALUE 'F'} - condition 9 of 9.
         *
         * @return {@code true} for {@code 'F'}
         */
        @JsonIgnore
        public boolean isChangesOkayedButFailed() {
            return CHANGES_OKAYED_BUT_FAILED.equals(value);
        }

        /**
         * Whether this byte satisfies none of the nine conditions, which is the {@code WHEN OTHER} case.
         *
         * @return {@code true} when no {@code 88}-level covers the byte
         */
        @JsonIgnore
        public boolean isUnrecognised() {
            return !isDetailsNotFetched() && !isShowDetails() && !isChangesMade();
        }

        /**
         * A rendering that names the byte readably, since {@code LOW-VALUES} and a space are both invisible
         * in a failure message.
         *
         * @return the condition name where one applies, otherwise the quoted byte
         */
        @Override
        public String toString() {
            String named = switch (value) {
                case LOW_VALUES -> "LOW-VALUES";
                case SPACES -> "SPACES";
                case SHOW_DETAILS -> "ACUP-SHOW-DETAILS";
                case CHANGES_NOT_OK -> "ACUP-CHANGES-NOT-OK";
                case CHANGES_OK_NOT_CONFIRMED -> "ACUP-CHANGES-OK-NOT-CONFIRMED";
                case CHANGES_OKAYED_AND_DONE -> "ACUP-CHANGES-OKAYED-AND-DONE";
                case CHANGES_OKAYED_LOCK_ERROR -> "ACUP-CHANGES-OKAYED-LOCK-ERROR";
                case CHANGES_OKAYED_BUT_FAILED -> "ACUP-CHANGES-OKAYED-BUT-FAILED";
                default -> "'" + value + "'";
            };
            return FIELD_NAME + "=" + named;
        }
    }

    /**
     * {@code 10 ACUP-xxx-ACCT-DATA} - the {@value #RECORD_LENGTH}-byte account half of a detail group,
     * {@code app/cbl/COACTUPC.cbl:670-708} for {@code OLD} and {@code :758-796} for {@code NEW}.
     *
     * @param acctIdX {@code ACUP-xxx-ACCT-ID-X PIC X(11)}
     * @param activeStatus {@code ACUP-xxx-ACTIVE-STATUS PIC X(01)}
     * @param currBal {@code ACUP-xxx-CURR-BAL PIC X(12)}
     * @param creditLimit {@code ACUP-xxx-CREDIT-LIMIT PIC X(12)}
     * @param cashCreditLimit {@code ACUP-xxx-CASH-CREDIT-LIMIT PIC X(12)}
     * @param openDate {@code ACUP-xxx-OPEN-DATE PIC X(08)}, unseparated
     * @param expiraionDate {@code ACUP-xxx-EXPIRAION-DATE PIC X(08)}, unseparated, misspelling intact
     * @param reissueDate {@code ACUP-xxx-REISSUE-DATE PIC X(08)}, unseparated
     * @param currCycCredit {@code ACUP-xxx-CURR-CYC-CREDIT PIC X(12)}
     * @param currCycDebit {@code ACUP-xxx-CURR-CYC-DEBIT PIC X(12)}
     * @param groupId {@code ACUP-xxx-GROUP-ID PIC X(10)}
     */
    public record AcctSnapshot(String acctIdX,
                               String activeStatus,
                               String currBal,
                               String creditLimit,
                               String cashCreditLimit,
                               String openDate,
                               String expiraionDate,
                               String reissueDate,
                               String currCycCredit,
                               String currCycDebit,
                               String groupId) {
        public static final int RECORD_LENGTH = 106;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-ACCT-ID-X} within the group.
         */
        public static final int ACCT_ID_OFFSET = 0;

        /**
         * Declared width of {@code ACUP-xxx-ACCT-ID-X PIC X(11)} and of its {@code PIC 9(11)} overlay.
         */
        public static final int ACCT_ID_LENGTH = 11;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-ACTIVE-STATUS}.
         */
        public static final int ACTIVE_STATUS_OFFSET = 11;

        /**
         * Declared width of {@code ACUP-xxx-ACTIVE-STATUS PIC X(01)}.
         */
        public static final int ACTIVE_STATUS_LENGTH = 1;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CURR-BAL}.
         */
        public static final int CURR_BAL_OFFSET = 12;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CREDIT-LIMIT}.
         */
        public static final int CREDIT_LIMIT_OFFSET = 24;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CASH-CREDIT-LIMIT}.
         */
        public static final int CASH_CREDIT_LIMIT_OFFSET = 36;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-OPEN-DATE}.
         */
        public static final int OPEN_DATE_OFFSET = 48;

        public static final int EXPIRAION_DATE_OFFSET = 56;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-REISSUE-DATE}.
         */
        public static final int REISSUE_DATE_OFFSET = 64;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CURR-CYC-CREDIT}.
         */
        public static final int CURR_CYC_CREDIT_OFFSET = 72;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CURR-CYC-DEBIT}.
         */
        public static final int CURR_CYC_DEBIT_OFFSET = 84;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-GROUP-ID}.
         */
        public static final int GROUP_ID_OFFSET = 96;

        /**
         * Declared width of {@code ACUP-xxx-GROUP-ID PIC X(10)}.
         */
        public static final int GROUP_ID_LENGTH = 10;

        /**
         * Declared width of each of the five money items: {@code PIC X(12)}, and equally the twelve bytes a
         * {@code PIC S9(10)V99} overlay occupies once the sign is overpunched rather than given a byte.
         */
        public static final int MONEY_LENGTH = 12;

        /**
         * Integer digit positions of the {@code PIC S9(10)V99} overlays.
         */
        public static final int MONEY_INTEGER_DIGITS = 10;

        /**
         * Declared width of each of the three dates: {@code PIC X(08)}, unseparated, against a ten-byte
         * separated counterpart in the account record.
         */
        public static final int DATE_LENGTH = 8;

        public static final int DATE_YEAR_LENGTH = 4;

        public static final int DATE_MONTH_LENGTH = 2;

        public static final int DATE_DAY_LENGTH = 2;

        /**
         * Normalises and checks every item, so a snapshot can never exist in a shape the work area cannot
         * hold.
         */
        public AcctSnapshot {
            acctIdX = fitAcct("ACCT-ID-X", acctIdX, ACCT_ID_LENGTH);
            activeStatus = fitAcct("ACTIVE-STATUS", activeStatus, ACTIVE_STATUS_LENGTH);
            currBal = fitAcct("CURR-BAL", currBal, MONEY_LENGTH);
            creditLimit = fitAcct("CREDIT-LIMIT", creditLimit, MONEY_LENGTH);
            cashCreditLimit = fitAcct("CASH-CREDIT-LIMIT", cashCreditLimit, MONEY_LENGTH);
            openDate = fitAcct("OPEN-DATE", openDate, DATE_LENGTH);
            expiraionDate = fitAcct("EXPIRAION-DATE", expiraionDate, DATE_LENGTH);
            reissueDate = fitAcct("REISSUE-DATE", reissueDate, DATE_LENGTH);
            currCycCredit = fitAcct("CURR-CYC-CREDIT", currCycCredit, MONEY_LENGTH);
            currCycDebit = fitAcct("CURR-CYC-DEBIT", currCycDebit, MONEY_LENGTH);
            groupId = fitAcct("GROUP-ID", groupId, GROUP_ID_LENGTH);
        }

        /**
         * A snapshot in which every item holds its declared width in spaces - the state {@code INITIALIZE}
         * leaves an all-{@code PIC X} group in.
         *
         * @return the all-spaces snapshot
         */
        public static AcctSnapshot initialised() {
            return new AcctSnapshot(null, null, null, null, null, null, null, null, null, null, null);
        }

        /**
         * {@code ACUP-xxx-ACCT-ID REDEFINES ACUP-xxx-ACCT-ID-X PIC 9(11)} - the same eleven bytes seen as
         * an unsigned integer.
         *
         * @return {@code 0} when the span holds {@code LOW-VALUES}, spaces or zeros - all three of which
         *     {@code app/cbl/COACTUPC.cbl:1053} can put there - and otherwise the value its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor one of those three states
         */
        @JsonIgnore
        public long acctId() {
            return numericView(acctIdX, ACCT_ID_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CURR-BAL-N REDEFINES ACUP-xxx-CURR-BAL PIC S9(10)V99} - the same twelve bytes
         * seen as a signed decimal of scale {@link CobolDecimal#MONETARY_SCALE}.
         *
         * @return the balance at scale 2, truncated never rounded; {@code 0.00} for an unset span
         */
        @JsonIgnore
        public BigDecimal currBalN() {
            return monetaryView(currBal);
        }

        /**
         * {@code ACUP-xxx-CREDIT-LIMIT-N REDEFINES ACUP-xxx-CREDIT-LIMIT PIC S9(10)V99}.
         *
         * @return the credit limit at scale 2
         */
        @JsonIgnore
        public BigDecimal creditLimitN() {
            return monetaryView(creditLimit);
        }

        /**
         * {@code ACUP-xxx-CASH-CREDIT-LIMIT-N REDEFINES ACUP-xxx-CASH-CREDIT-LIMIT PIC S9(10)V99}.
         *
         * @return the cash credit limit at scale 2
         */
        @JsonIgnore
        public BigDecimal cashCreditLimitN() {
            return monetaryView(cashCreditLimit);
        }

        /**
         * {@code ACUP-xxx-CURR-CYC-CREDIT-N REDEFINES ACUP-xxx-CURR-CYC-CREDIT PIC S9(10)V99}.
         *
         * @return the current cycle credit at scale 2
         */
        @JsonIgnore
        public BigDecimal currCycCreditN() {
            return monetaryView(currCycCredit);
        }

        /**
         * {@code ACUP-xxx-CURR-CYC-DEBIT-N REDEFINES ACUP-xxx-CURR-CYC-DEBIT PIC S9(10)V99}.
         *
         * @return the current cycle debit at scale 2
         */
        @JsonIgnore
        public BigDecimal currCycDebitN() {
            return monetaryView(currCycDebit);
        }

        /**
         * {@code ACUP-xxx-OPEN-YEAR PIC X(4)}, the first part of
         * {@code ACUP-xxx-OPEN-DATE-PARTS REDEFINES ACUP-xxx-OPEN-DATE}.
         *
         * @return four characters of the open date
         */
        @JsonIgnore
        public String openYear() {
            return datePart(openDate, 0, DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-OPEN-MON PIC X(2)}.
         *
         * @return two characters of the open date
         */
        @JsonIgnore
        public String openMon() {
            return datePart(openDate, DATE_YEAR_LENGTH, DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-OPEN-DAY PIC X(2)}.
         *
         * @return two characters of the open date
         */
        @JsonIgnore
        public String openDay() {
            return datePart(openDate, DATE_YEAR_LENGTH + DATE_MONTH_LENGTH, DATE_DAY_LENGTH);
        }

        /**
         * {@code ACUP-xxx-EXP-YEAR PIC X(4)}, the first part of {@code ACUP-xxx-EXPIRAION-DATE-PARTS} -
         * note that the parts drop to {@code EXP-} while the group keeps the misspelled {@code EXPIRAION}.
         *
         * @return four characters of the expiry date
         */
        @JsonIgnore
        public String expYear() {
            return datePart(expiraionDate, 0, DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-EXP-MON PIC X(2)}, compared against {@code ACCT-EXPIRAION-DATE(6:2)} at
         * {@code :4132}.
         *
         * @return two characters of the expiry date
         */
        @JsonIgnore
        public String expMon() {
            return datePart(expiraionDate, DATE_YEAR_LENGTH, DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-EXP-DAY PIC X(2)}, compared against {@code ACCT-EXPIRAION-DATE(9:2)} at
         * {@code :4133}.
         *
         * @return two characters of the expiry date
         */
        @JsonIgnore
        public String expDay() {
            return datePart(expiraionDate, DATE_YEAR_LENGTH + DATE_MONTH_LENGTH, DATE_DAY_LENGTH);
        }

        /**
         * {@code ACUP-xxx-REISSUE-YEAR PIC X(4)}, compared against {@code ACCT-REISSUE-DATE(1:4)} at
         * {@code app/cbl/COACTUPC.cbl:4135}.
         *
         * @return four characters of the reissue date
         */
        @JsonIgnore
        public String reissueYear() {
            return datePart(reissueDate, 0, DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-REISSUE-MON PIC X(2)}, compared against {@code ACCT-REISSUE-DATE(6:2)} at
         * {@code :4136}.
         *
         * @return two characters of the reissue date
         */
        @JsonIgnore
        public String reissueMon() {
            return datePart(reissueDate, DATE_YEAR_LENGTH, DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-REISSUE-DAY PIC X(2)}, compared against {@code ACCT-REISSUE-DATE(9:2)} at
         * {@code :4137}.
         *
         * @return two characters of the reissue date
         */
        @JsonIgnore
        public String reissueDay() {
            return datePart(reissueDate, DATE_YEAR_LENGTH + DATE_MONTH_LENGTH, DATE_DAY_LENGTH);
        }

        private void writeInto(FixedWidthRecord area, int base) {
            area.writeString(base + ACCT_ID_OFFSET, ACCT_ID_LENGTH, acctIdX);
            area.writeString(base + ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_LENGTH, activeStatus);
            area.writeString(base + CURR_BAL_OFFSET, MONEY_LENGTH, currBal);
            area.writeString(base + CREDIT_LIMIT_OFFSET, MONEY_LENGTH, creditLimit);
            area.writeString(base + CASH_CREDIT_LIMIT_OFFSET, MONEY_LENGTH, cashCreditLimit);
            area.writeString(base + OPEN_DATE_OFFSET, DATE_LENGTH, openDate);
            area.writeString(base + EXPIRAION_DATE_OFFSET, DATE_LENGTH, expiraionDate);
            area.writeString(base + REISSUE_DATE_OFFSET, DATE_LENGTH, reissueDate);
            area.writeString(base + CURR_CYC_CREDIT_OFFSET, MONEY_LENGTH, currCycCredit);
            area.writeString(base + CURR_CYC_DEBIT_OFFSET, MONEY_LENGTH, currCycDebit);
            area.writeString(base + GROUP_ID_OFFSET, GROUP_ID_LENGTH, groupId);
        }

        private static AcctSnapshot readFrom(FixedWidthRecord area, int base) {
            return new AcctSnapshot(area.readString(base + ACCT_ID_OFFSET, ACCT_ID_LENGTH),
                    area.readString(base + ACTIVE_STATUS_OFFSET, ACTIVE_STATUS_LENGTH),
                    area.readString(base + CURR_BAL_OFFSET, MONEY_LENGTH),
                    area.readString(base + CREDIT_LIMIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + CASH_CREDIT_LIMIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + OPEN_DATE_OFFSET, DATE_LENGTH),
                    area.readString(base + EXPIRAION_DATE_OFFSET, DATE_LENGTH),
                    area.readString(base + REISSUE_DATE_OFFSET, DATE_LENGTH),
                    area.readString(base + CURR_CYC_CREDIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + CURR_CYC_DEBIT_OFFSET, MONEY_LENGTH),
                    area.readString(base + GROUP_ID_OFFSET, GROUP_ID_LENGTH));
        }

        /**
         * A diagnostic rendering that discloses no balance and no identifier in full.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "AcctSnapshot[acctIdX=" + SensitiveDiagnostics.maskIdentifier(acctIdX)
                    + ", activeStatus=" + DiagnosticText.singleLine(activeStatus)
                    + ", currBal=" + DiagnosticText.omitted(currBal)
                    + ", creditLimit=" + DiagnosticText.omitted(creditLimit)
                    + ", cashCreditLimit=" + DiagnosticText.omitted(cashCreditLimit)
                    + ", openDate=" + DiagnosticText.singleLine(openDate)
                    + ", expiraionDate=" + DiagnosticText.singleLine(expiraionDate)
                    + ", reissueDate=" + DiagnosticText.singleLine(reissueDate)
                    + ", currCycCredit=" + DiagnosticText.omitted(currCycCredit)
                    + ", currCycDebit=" + DiagnosticText.omitted(currCycDebit)
                    + ", groupId=" + DiagnosticText.singleLine(groupId) + ']';
        }
    }

    /**
     * {@code 10 ACUP-xxx-CUST-DATA} - the {@value #RECORD_LENGTH}-byte customer half of a detail group,
     * {@code app/cbl/COACTUPC.cbl:709-756} for {@code OLD} and {@code :797-849} for {@code NEW}.
     *
     * @param custIdX {@code ACUP-xxx-CUST-ID-X PIC X(09)}
     * @param firstName {@code ACUP-xxx-CUST-FIRST-NAME PIC X(25)}
     * @param middleName {@code ACUP-xxx-CUST-MIDDLE-NAME PIC X(25)}
     * @param lastName {@code ACUP-xxx-CUST-LAST-NAME PIC X(25)}
     * @param addrLine1 {@code ACUP-xxx-CUST-ADDR-LINE-1 PIC X(50)}
     * @param addrLine2 {@code ACUP-xxx-CUST-ADDR-LINE-2 PIC X(50)}
     * @param addrLine3 {@code ACUP-xxx-CUST-ADDR-LINE-3 PIC X(50)} - the city on the screen
     * @param addrStateCd {@code ACUP-xxx-CUST-ADDR-STATE-CD PIC X(02)}
     * @param addrCountryCd {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD PIC X(03)}
     * @param addrZip {@code ACUP-xxx-CUST-ADDR-ZIP PIC X(10)} - ten here, five on the screen
     * @param phoneNum1 {@code ACUP-xxx-CUST-PHONE-NUM-1 PIC X(15)}, punctuation included
     * @param phoneNum2 {@code ACUP-xxx-CUST-PHONE-NUM-2 PIC X(15)}, punctuation included
     * @param ssnX {@code ACUP-xxx-CUST-SSN-X}, nine bytes - flat in {@code OLD}, a three-part group in
     *     {@code NEW}
     * @param govtIssuedId {@code ACUP-xxx-CUST-GOVT-ISSUED-ID PIC X(20)}
     * @param dobYyyyMmDd {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD PIC X(08)}, unseparated
     * @param eftAccountId {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID PIC X(10)}
     * @param priHolderInd {@code ACUP-xxx-CUST-PRI-HOLDER-IND PIC X(01)}
     * @param ficoScoreX {@code ACUP-xxx-CUST-FICO-SCORE-X PIC X(03)}
     */
    public record CustSnapshot(String custIdX,
                               String firstName,
                               String middleName,
                               String lastName,
                               String addrLine1,
                               String addrLine2,
                               String addrLine3,
                               String addrStateCd,
                               String addrCountryCd,
                               String addrZip,
                               String phoneNum1,
                               String phoneNum2,
                               String ssnX,
                               String govtIssuedId,
                               String dobYyyyMmDd,
                               String eftAccountId,
                               String priHolderInd,
                               String ficoScoreX) {
        public static final int RECORD_LENGTH = 330;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ID-X} within the customer group.
         */
        public static final int CUST_ID_OFFSET = 0;

        /**
         * Declared width of {@code ACUP-xxx-CUST-ID-X PIC X(09)} and of its {@code PIC 9(09)} overlay.
         */
        public static final int CUST_ID_LENGTH = 9;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-FIRST-NAME}.
         */
        public static final int FIRST_NAME_OFFSET = 9;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-MIDDLE-NAME}.
         */
        public static final int MIDDLE_NAME_OFFSET = 34;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-LAST-NAME}.
         */
        public static final int LAST_NAME_OFFSET = 59;

        /**
         * Declared width of each of the three name items: {@code PIC X(25)}.
         */
        public static final int NAME_LENGTH = 25;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-LINE-1}.
         */
        public static final int ADDR_LINE_1_OFFSET = 84;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-LINE-2}.
         */
        public static final int ADDR_LINE_2_OFFSET = 134;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-LINE-3}.
         */
        public static final int ADDR_LINE_3_OFFSET = 184;

        /**
         * Declared width of each of the three address lines: {@code PIC X(50)}.
         */
        public static final int ADDR_LINE_LENGTH = 50;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-STATE-CD}.
         */
        public static final int ADDR_STATE_CD_OFFSET = 234;

        /**
         * Declared width of {@code ACUP-xxx-CUST-ADDR-STATE-CD PIC X(02)}.
         */
        public static final int ADDR_STATE_CD_LENGTH = 2;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD}.
         */
        public static final int ADDR_COUNTRY_CD_OFFSET = 236;

        /**
         * Declared width of {@code ACUP-xxx-CUST-ADDR-COUNTRY-CD PIC X(03)}.
         */
        public static final int ADDR_COUNTRY_CD_LENGTH = 3;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-ADDR-ZIP}.
         */
        public static final int ADDR_ZIP_OFFSET = 239;

        /**
         * Declared width of {@code ACUP-xxx-CUST-ADDR-ZIP PIC X(10)}.
         */
        public static final int ADDR_ZIP_LENGTH = 10;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-PHONE-NUM-1}.
         */
        public static final int PHONE_NUM_1_OFFSET = 249;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-PHONE-NUM-2}.
         */
        public static final int PHONE_NUM_2_OFFSET = 264;

        /**
         * Declared width of each telephone item: {@code PIC X(15)}, punctuation included.
         */
        public static final int PHONE_NUM_LENGTH = 15;

        public static final int PHONE_AREA_CODE_RELATIVE_OFFSET = 1;

        /**
         * Width of the area-code part, {@code ...-PHONE-NUM-nA PIC X(3)}.
         */
        public static final int PHONE_AREA_CODE_LENGTH = 3;

        public static final int PHONE_PREFIX_RELATIVE_OFFSET = 5;

        /**
         * Width of the prefix part, {@code ...-PHONE-NUM-nB PIC X(3)}.
         */
        public static final int PHONE_PREFIX_LENGTH = 3;

        public static final int PHONE_LINE_NUMBER_RELATIVE_OFFSET = 9;

        /**
         * Width of the line-number part, {@code ...-PHONE-NUM-nC PIC X(4)}.
         */
        public static final int PHONE_LINE_NUMBER_LENGTH = 4;

        public static final int PHONE_LEADING_FILLER_LENGTH = 1;

        public static final int PHONE_INNER_FILLER_LENGTH = 1;

        public static final int PHONE_TRAILING_FILLER_LENGTH = 2;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-SSN-X} and of its {@code PIC 9(09)} overlay.
         */
        public static final int SSN_OFFSET = 279;

        public static final int SSN_LENGTH = 9;

        /**
         * Width of {@code ACUP-NEW-CUST-SSN-1 PIC X(03)}, the {@code NEW} group's first SSN part.
         */
        public static final int SSN_PART_1_LENGTH = 3;

        /**
         * Width of {@code ACUP-NEW-CUST-SSN-2 PIC X(02)}.
         */
        public static final int SSN_PART_2_LENGTH = 2;

        /**
         * Width of {@code ACUP-NEW-CUST-SSN-3 PIC X(04)}.
         */
        public static final int SSN_PART_3_LENGTH = 4;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-GOVT-ISSUED-ID}.
         */
        public static final int GOVT_ISSUED_ID_OFFSET = 288;

        /**
         * Declared width of {@code ACUP-xxx-CUST-GOVT-ISSUED-ID PIC X(20)}.
         */
        public static final int GOVT_ISSUED_ID_LENGTH = 20;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD}.
         */
        public static final int DOB_OFFSET = 308;

        /**
         * Declared width of {@code ACUP-xxx-CUST-DOB-YYYY-MM-DD PIC X(08)} - eight bytes, unseparated,
         * against the record's separated ten.
         */
        public static final int DOB_LENGTH = 8;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID}.
         */
        public static final int EFT_ACCOUNT_ID_OFFSET = 316;

        /**
         * Declared width of {@code ACUP-xxx-CUST-EFT-ACCOUNT-ID PIC X(10)}.
         */
        public static final int EFT_ACCOUNT_ID_LENGTH = 10;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-PRI-HOLDER-IND}.
         */
        public static final int PRI_HOLDER_IND_OFFSET = 326;

        /**
         * Declared width of {@code ACUP-xxx-CUST-PRI-HOLDER-IND PIC X(01)}.
         */
        public static final int PRI_HOLDER_IND_LENGTH = 1;

        /**
         * Absolute 0-based offset of {@code ACUP-xxx-CUST-FICO-SCORE-X}.
         */
        public static final int FICO_SCORE_OFFSET = 327;

        /**
         * Declared width of {@code ACUP-xxx-CUST-FICO-SCORE-X PIC X(03)} and its {@code 9(03)} overlay.
         */
        public static final int FICO_SCORE_LENGTH = 3;

        /**
         * Lowest score {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} accepts,
         * {@code app/cbl/COACTUPC.cbl:848-849}.
         */
        public static final int FICO_RANGE_MINIMUM = 300;

        /**
         * Highest score {@code 88 FICO-RANGE-IS-VALID} accepts.
         */
        public static final int FICO_RANGE_MAXIMUM = 850;

        public CustSnapshot {
            custIdX = fitCust("CUST-ID-X", custIdX, CUST_ID_LENGTH);
            firstName = fitCust("CUST-FIRST-NAME", firstName, NAME_LENGTH);
            middleName = fitCust("CUST-MIDDLE-NAME", middleName, NAME_LENGTH);
            lastName = fitCust("CUST-LAST-NAME", lastName, NAME_LENGTH);
            addrLine1 = fitCust("CUST-ADDR-LINE-1", addrLine1, ADDR_LINE_LENGTH);
            addrLine2 = fitCust("CUST-ADDR-LINE-2", addrLine2, ADDR_LINE_LENGTH);
            addrLine3 = fitCust("CUST-ADDR-LINE-3", addrLine3, ADDR_LINE_LENGTH);
            addrStateCd = fitCust("CUST-ADDR-STATE-CD", addrStateCd, ADDR_STATE_CD_LENGTH);
            addrCountryCd = fitCust("CUST-ADDR-COUNTRY-CD", addrCountryCd, ADDR_COUNTRY_CD_LENGTH);
            addrZip = fitCust("CUST-ADDR-ZIP", addrZip, ADDR_ZIP_LENGTH);
            phoneNum1 = fitCust("CUST-PHONE-NUM-1", phoneNum1, PHONE_NUM_LENGTH);
            phoneNum2 = fitCust("CUST-PHONE-NUM-2", phoneNum2, PHONE_NUM_LENGTH);
            ssnX = fitCust("CUST-SSN-X", ssnX, SSN_LENGTH);
            govtIssuedId = fitCust("CUST-GOVT-ISSUED-ID", govtIssuedId, GOVT_ISSUED_ID_LENGTH);
            dobYyyyMmDd = fitCust("CUST-DOB-YYYY-MM-DD", dobYyyyMmDd, DOB_LENGTH);
            eftAccountId = fitCust("CUST-EFT-ACCOUNT-ID", eftAccountId, EFT_ACCOUNT_ID_LENGTH);
            priHolderInd = fitCust("CUST-PRI-HOLDER-IND", priHolderInd, PRI_HOLDER_IND_LENGTH);
            ficoScoreX = fitCust("CUST-FICO-SCORE-X", ficoScoreX, FICO_SCORE_LENGTH);
        }

        /**
         * A snapshot in which every item holds its declared width in spaces.
         *
         * @return the all-spaces snapshot
         */
        public static CustSnapshot initialised() {
            return new CustSnapshot(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        /**
         * {@code ACUP-xxx-CUST-ID REDEFINES ACUP-xxx-CUST-ID-X PIC 9(09)} - the same nine bytes as an
         * unsigned integer, a byte reinterpretation rather than a parse.
         *
         * @return {@code 0} for an unset span, otherwise the value its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor an unset state
         */
        @JsonIgnore
        public long custId() {
            return numericView(custIdX, CUST_ID_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-1A PIC X(3)} - the area code, at relative offset
         * {@value #PHONE_AREA_CODE_RELATIVE_OFFSET} inside {@link #phoneNum1()} because a one-byte
         * {@code FILLER} precedes it.
         *
         * @return three characters of the first telephone number
         */
        @JsonIgnore
        public String phoneNum1A() {
            return phonePart(phoneNum1, PHONE_AREA_CODE_RELATIVE_OFFSET, PHONE_AREA_CODE_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-1B PIC X(3)} - the prefix.
         *
         * @return three characters of the first telephone number
         */
        @JsonIgnore
        public String phoneNum1B() {
            return phonePart(phoneNum1, PHONE_PREFIX_RELATIVE_OFFSET, PHONE_PREFIX_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-1C PIC X(4)} - the line number.
         *
         * @return four characters of the first telephone number
         */
        @JsonIgnore
        public String phoneNum1C() {
            return phonePart(phoneNum1, PHONE_LINE_NUMBER_RELATIVE_OFFSET, PHONE_LINE_NUMBER_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-2A PIC X(3)} - the second telephone's area code.
         *
         * @return three characters of the second telephone number
         */
        @JsonIgnore
        public String phoneNum2A() {
            return phonePart(phoneNum2, PHONE_AREA_CODE_RELATIVE_OFFSET, PHONE_AREA_CODE_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-2B PIC X(3)}.
         *
         * @return three characters of the second telephone number
         */
        @JsonIgnore
        public String phoneNum2B() {
            return phonePart(phoneNum2, PHONE_PREFIX_RELATIVE_OFFSET, PHONE_PREFIX_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-PHONE-NUM-2C PIC X(4)}.
         *
         * @return four characters of the second telephone number
         */
        @JsonIgnore
        public String phoneNum2C() {
            return phonePart(phoneNum2, PHONE_LINE_NUMBER_RELATIVE_OFFSET, PHONE_LINE_NUMBER_LENGTH);
        }

        /**
         * {@code ACUP-NEW-CUST-SSN-1 PIC X(03)} - the first of the three sub-items the {@code NEW} group
         * declares under {@code 15 ACUP-NEW-CUST-SSN-X} ({@code app/cbl/COACTUPC.cbl:830-833}).
         *
         * @return three characters of the social security number
         */
        @JsonIgnore
        public String ssn1() {
            return substring(ssnX, SSN_LENGTH, 0, SSN_PART_1_LENGTH);
        }

        /**
         * {@code ACUP-NEW-CUST-SSN-2 PIC X(02)}.
         *
         * @return two characters of the social security number
         */
        @JsonIgnore
        public String ssn2() {
            return substring(ssnX, SSN_LENGTH, SSN_PART_1_LENGTH, SSN_PART_2_LENGTH);
        }

        /**
         * {@code ACUP-NEW-CUST-SSN-3 PIC X(04)}.
         *
         * @return four characters of the social security number
         */
        @JsonIgnore
        public String ssn3() {
            return substring(ssnX, SSN_LENGTH, SSN_PART_1_LENGTH + SSN_PART_2_LENGTH, SSN_PART_3_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-SSN REDEFINES ACUP-xxx-CUST-SSN-X PIC 9(09)} - the same nine bytes as an
         * unsigned integer.
         *
         * @return {@code 0} for an unset span, otherwise the value its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor an unset state
         */
        @JsonIgnore
        public long ssn() {
            return numericView(ssnX, SSN_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-DOB-YEAR PIC X(4)}, the first part of
         * {@code ACUP-xxx-CUST-DOB-PARTS REDEFINES ACUP-xxx-CUST-DOB-YYYY-MM-DD}.
         *
         * @return four characters of the date of birth
         */
        @JsonIgnore
        public String dobYear() {
            return substring(dobYyyyMmDd, DOB_LENGTH, 0, AcctSnapshot.DATE_YEAR_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-DOB-MON PIC X(2)}, at position 5 of this eight-byte span - compared against
         * position 6 of the record's ten-byte separated field, at {@code app/cbl/COACTUPC.cbl:4176-4177}.
         *
         * @return two characters of the date of birth
         */
        @JsonIgnore
        public String dobMon() {
            return substring(dobYyyyMmDd, DOB_LENGTH, AcctSnapshot.DATE_YEAR_LENGTH,
                    AcctSnapshot.DATE_MONTH_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-DOB-DAY PIC X(2)}, at position 7 of this span against position 9 of the
         * record's, at {@code app/cbl/COACTUPC.cbl:4178-4179}.
         *
         * @return two characters of the date of birth
         */
        @JsonIgnore
        public String dobDay() {
            return substring(dobYyyyMmDd, DOB_LENGTH,
                    AcctSnapshot.DATE_YEAR_LENGTH + AcctSnapshot.DATE_MONTH_LENGTH,
                    AcctSnapshot.DATE_DAY_LENGTH);
        }

        /**
         * {@code ACUP-xxx-CUST-FICO-SCORE REDEFINES ACUP-xxx-CUST-FICO-SCORE-X PIC 9(03)}.
         *
         * @return {@code 0} for an unset span, otherwise the score its digits denote
         * @throws IllegalArgumentException if the span holds neither digits nor an unset state
         */
        @JsonIgnore
        public int ficoScore() {
            return (int) numericView(ficoScoreX, FICO_SCORE_LENGTH);
        }

        /**
         * {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850}, declared on
         * {@code ACUP-NEW-CUST-FICO-SCORE} at {@code app/cbl/COACTUPC.cbl:848-849}.
         *
         * @return {@code true} when the score is between {@value #FICO_RANGE_MINIMUM} and
         *     {@value #FICO_RANGE_MAXIMUM} inclusive
         */
        @JsonIgnore
        public boolean ficoRangeIsValid() {
            int score = ficoScore();
            return score >= FICO_RANGE_MINIMUM && score <= FICO_RANGE_MAXIMUM;
        }

        private void writeInto(FixedWidthRecord area, int base) {
            area.writeString(base + CUST_ID_OFFSET, CUST_ID_LENGTH, custIdX);
            area.writeString(base + FIRST_NAME_OFFSET, NAME_LENGTH, firstName);
            area.writeString(base + MIDDLE_NAME_OFFSET, NAME_LENGTH, middleName);
            area.writeString(base + LAST_NAME_OFFSET, NAME_LENGTH, lastName);
            area.writeString(base + ADDR_LINE_1_OFFSET, ADDR_LINE_LENGTH, addrLine1);
            area.writeString(base + ADDR_LINE_2_OFFSET, ADDR_LINE_LENGTH, addrLine2);
            area.writeString(base + ADDR_LINE_3_OFFSET, ADDR_LINE_LENGTH, addrLine3);
            area.writeString(base + ADDR_STATE_CD_OFFSET, ADDR_STATE_CD_LENGTH, addrStateCd);
            area.writeString(base + ADDR_COUNTRY_CD_OFFSET, ADDR_COUNTRY_CD_LENGTH, addrCountryCd);
            area.writeString(base + ADDR_ZIP_OFFSET, ADDR_ZIP_LENGTH, addrZip);
            area.writeString(base + PHONE_NUM_1_OFFSET, PHONE_NUM_LENGTH, phoneNum1);
            area.writeString(base + PHONE_NUM_2_OFFSET, PHONE_NUM_LENGTH, phoneNum2);
            area.writeString(base + SSN_OFFSET, SSN_LENGTH, ssnX);
            area.writeString(base + GOVT_ISSUED_ID_OFFSET, GOVT_ISSUED_ID_LENGTH, govtIssuedId);
            area.writeString(base + DOB_OFFSET, DOB_LENGTH, dobYyyyMmDd);
            area.writeString(base + EFT_ACCOUNT_ID_OFFSET, EFT_ACCOUNT_ID_LENGTH, eftAccountId);
            area.writeString(base + PRI_HOLDER_IND_OFFSET, PRI_HOLDER_IND_LENGTH, priHolderInd);
            area.writeString(base + FICO_SCORE_OFFSET, FICO_SCORE_LENGTH, ficoScoreX);
        }

        private static CustSnapshot readFrom(FixedWidthRecord area, int base) {
            return new CustSnapshot(area.readString(base + CUST_ID_OFFSET, CUST_ID_LENGTH),
                    area.readString(base + FIRST_NAME_OFFSET, NAME_LENGTH),
                    area.readString(base + MIDDLE_NAME_OFFSET, NAME_LENGTH),
                    area.readString(base + LAST_NAME_OFFSET, NAME_LENGTH),
                    area.readString(base + ADDR_LINE_1_OFFSET, ADDR_LINE_LENGTH),
                    area.readString(base + ADDR_LINE_2_OFFSET, ADDR_LINE_LENGTH),
                    area.readString(base + ADDR_LINE_3_OFFSET, ADDR_LINE_LENGTH),
                    area.readString(base + ADDR_STATE_CD_OFFSET, ADDR_STATE_CD_LENGTH),
                    area.readString(base + ADDR_COUNTRY_CD_OFFSET, ADDR_COUNTRY_CD_LENGTH),
                    area.readString(base + ADDR_ZIP_OFFSET, ADDR_ZIP_LENGTH),
                    area.readString(base + PHONE_NUM_1_OFFSET, PHONE_NUM_LENGTH),
                    area.readString(base + PHONE_NUM_2_OFFSET, PHONE_NUM_LENGTH),
                    area.readString(base + SSN_OFFSET, SSN_LENGTH),
                    area.readString(base + GOVT_ISSUED_ID_OFFSET, GOVT_ISSUED_ID_LENGTH),
                    area.readString(base + DOB_OFFSET, DOB_LENGTH),
                    area.readString(base + EFT_ACCOUNT_ID_OFFSET, EFT_ACCOUNT_ID_LENGTH),
                    area.readString(base + PRI_HOLDER_IND_OFFSET, PRI_HOLDER_IND_LENGTH),
                    area.readString(base + FICO_SCORE_OFFSET, FICO_SCORE_LENGTH));
        }

        /**
         * A diagnostic rendering that discloses no identity data.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "CustSnapshot[custIdX=" + SensitiveDiagnostics.maskIdentifier(custIdX)
                    + ", firstName=" + SensitiveDiagnostics.describeText(firstName)
                    + ", middleName=" + SensitiveDiagnostics.describeText(middleName)
                    + ", lastName=" + SensitiveDiagnostics.describeText(lastName)
                    + ", addrLine1=" + SensitiveDiagnostics.describeText(addrLine1)
                    + ", addrLine2=" + SensitiveDiagnostics.describeText(addrLine2)
                    + ", addrLine3=" + SensitiveDiagnostics.describeText(addrLine3)
                    + ", addrStateCd=" + DiagnosticText.singleLine(addrStateCd)
                    + ", addrCountryCd=" + DiagnosticText.singleLine(addrCountryCd)
                    + ", addrZip=" + SensitiveDiagnostics.describeText(addrZip)
                    + ", phoneNum1=" + SensitiveDiagnostics.describeText(phoneNum1)
                    + ", phoneNum2=" + SensitiveDiagnostics.describeText(phoneNum2)
                    + ", ssnX=" + DiagnosticText.omitted(ssnX)
                    + ", govtIssuedId=" + DiagnosticText.omitted(govtIssuedId)
                    + ", dobYyyyMmDd=" + DiagnosticText.omitted(dobYyyyMmDd)
                    + ", eftAccountId=" + DiagnosticText.omitted(eftAccountId)
                    + ", priHolderInd=" + DiagnosticText.singleLine(priHolderInd)
                    + ", ficoScoreX=" + DiagnosticText.singleLine(ficoScoreX) + ']';
        }
    }

    // One implementation of "does this fit", one of the PIC 9 view and one of the PIC S9V99 view, so a
    // REDEFINES cannot be decoded two different ways.

    private static String fitAcct(String suffix, String value, int width) {
        return fitItem("ACUP-xxx-" + suffix, value, width);
    }

    private static String fitCust(String suffix, String value, int width) {
        return fitItem("ACUP-xxx-" + suffix, value, width);
    }

    private static String fitItem(String name, String value, int width) {
        if (value == null) {
            return spaces(width);
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(name + " is declared " + width + " character(s) wide but "
                    + "was given " + value.length() + "; the work area cannot hold it, and truncating it "
                    + "here would corrupt the 9700-CHECK-CHANGE-IN-REC comparison rather than report the "
                    + "problem");
        }
        return value.length() == width ? value : value + spaces(width - value.length());
    }

    private static long numericView(String value, int width) {
        String image = PICTURE_RULES.movePicX(value, width);
        if (isZeroValued(image)) {
            return 0L;
        }
        return PICTURE_RULES.decodePic9(image);
    }

    private static BigDecimal monetaryView(String value) {
        String image = PICTURE_RULES.movePicX(value, AcctSnapshot.MONEY_LENGTH);
        if (isZeroValued(image)) {
            return CobolDecimal.monetaryZero();
        }
        return PICTURE_RULES.decodeSignedScaled(image, CobolDecimal.MONETARY_SCALE);
    }

    private static boolean isZeroValued(String image) {
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != LOW_VALUE && character != SPACE && character != '0') {
                return false;
            }
        }
        return true;
    }

    private static String datePart(String date, int offset, int width) {
        return substring(date, AcctSnapshot.DATE_LENGTH, offset, width);
    }

    private static String phonePart(String phone, int offset, int width) {
        return substring(phone, CustSnapshot.PHONE_NUM_LENGTH, offset, width);
    }

    private static String substring(String value, int spanWidth, int offset, int partWidth) {
        String image = PICTURE_RULES.movePicX(value, spanWidth);
        return image.substring(offset, offset + partWidth);
    }

    /**
     * Which of the two {@code 05}-level detail groups a {@link Details} snapshot is -
     * {@code ACUP-OLD-DETAILS} ({@code app/cbl/COACTUPC.cbl:669}) or {@code ACUP-NEW-DETAILS}
     * ({@code :757}).
     */
    public enum DetailGroup {
        /**
         * {@code ACUP-OLD-DETAILS} - the snapshot the screen was painted from, and the one
         * {@code 9700-CHECK-CHANGE-IN-REC} ({@code app/cbl/COACTUPC.cbl:4109-4193}) compares the freshly
         * read record against before allowing a rewrite.
         */
        OLD("ACUP-OLD-", false),

        /**
         * {@code ACUP-NEW-DETAILS} - what the operator typed, rebuilt by
         * {@code INITIALIZE ACUP-NEW-DETAILS} at {@code app/cbl/COACTUPC.cbl:1047} and then filled field by
         * field from {@code CACTUPAI}.
         */
        NEW("ACUP-NEW-", true);

        private final String prefix;

        private final boolean newShape;

        private final RecordLayout layout;

        private final RecordLayout acctLayout;

        private final RecordLayout custLayout;

        DetailGroup(String prefix, boolean newShape) {
            this.prefix = prefix;
            this.newShape = newShape;
            this.acctLayout = RecordLayout.of(AcctSnapshot.RECORD_LENGTH,
                    acctSpans(prefix, 0).toArray(FieldSpan[]::new));
            this.custLayout = RecordLayout.of(CustSnapshot.RECORD_LENGTH,
                    custSpans(prefix, newShape, 0).toArray(FieldSpan[]::new));
            List<FieldSpan> spans = new ArrayList<>();
            spans.addAll(acctSpans(prefix, Details.ACCT_DATA_OFFSET));
            spans.addAll(custSpans(prefix, newShape, Details.CUST_DATA_OFFSET));
            spans.add(FieldSpan.redefining(prefix + "ACCT-DATA", Details.ACCT_DATA_OFFSET,
                    AcctSnapshot.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.add(FieldSpan.redefining(prefix + "CUST-DATA", Details.CUST_DATA_OFFSET,
                    CustSnapshot.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            this.layout = RecordLayout.of(Details.RECORD_LENGTH, spans.toArray(FieldSpan[]::new));
        }

        private static List<FieldSpan> acctSpans(String prefix, int base) {
            List<FieldSpan> spans = new ArrayList<>();
            FieldSpan acctId = FieldSpan.alphanumeric(prefix + "ACCT-ID-X",
                    base + AcctSnapshot.ACCT_ID_OFFSET, AcctSnapshot.ACCT_ID_LENGTH);
            spans.add(acctId);
            spans.add(acctId.redefinedAs(prefix + "ACCT-ID", PictureKind.UNSIGNED_NUMERIC));
            spans.add(FieldSpan.alphanumeric(prefix + "ACTIVE-STATUS",
                    base + AcctSnapshot.ACTIVE_STATUS_OFFSET, AcctSnapshot.ACTIVE_STATUS_LENGTH));
            spans.addAll(moneySpans(prefix, "CURR-BAL", base + AcctSnapshot.CURR_BAL_OFFSET));
            spans.addAll(moneySpans(prefix, "CREDIT-LIMIT", base + AcctSnapshot.CREDIT_LIMIT_OFFSET));
            spans.addAll(moneySpans(prefix, "CASH-CREDIT-LIMIT",
                    base + AcctSnapshot.CASH_CREDIT_LIMIT_OFFSET));
            spans.addAll(dateSpans(prefix, "OPEN-DATE", "OPEN", base + AcctSnapshot.OPEN_DATE_OFFSET));
            spans.addAll(dateSpans(prefix, "EXPIRAION-DATE", "EXP",
                    base + AcctSnapshot.EXPIRAION_DATE_OFFSET));
            spans.addAll(dateSpans(prefix, "REISSUE-DATE", "REISSUE",
                    base + AcctSnapshot.REISSUE_DATE_OFFSET));
            spans.addAll(moneySpans(prefix, "CURR-CYC-CREDIT",
                    base + AcctSnapshot.CURR_CYC_CREDIT_OFFSET));
            spans.addAll(moneySpans(prefix, "CURR-CYC-DEBIT", base + AcctSnapshot.CURR_CYC_DEBIT_OFFSET));
            spans.add(FieldSpan.alphanumeric(prefix + "GROUP-ID", base + AcctSnapshot.GROUP_ID_OFFSET,
                    AcctSnapshot.GROUP_ID_LENGTH));
            return spans;
        }

        private static List<FieldSpan> moneySpans(String prefix, String suffix, int offset) {
            FieldSpan characters =
                    FieldSpan.alphanumeric(prefix + suffix, offset, AcctSnapshot.MONEY_LENGTH);
            return List.of(characters,
                    characters.redefinedAs(prefix + suffix + "-N", PictureKind.SIGNED_SCALED));
        }

        private static List<FieldSpan> dateSpans(String prefix, String suffix, String partStem,
                                                 int offset) {
            return List.of(
                    FieldSpan.alphanumeric(prefix + suffix, offset, AcctSnapshot.DATE_LENGTH),
                    FieldSpan.redefining(prefix + suffix + "-PARTS", offset, AcctSnapshot.DATE_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + partStem + "-YEAR", offset,
                            AcctSnapshot.DATE_YEAR_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + partStem + "-MON",
                            offset + AcctSnapshot.DATE_YEAR_LENGTH, AcctSnapshot.DATE_MONTH_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + partStem + "-DAY",
                            offset + AcctSnapshot.DATE_YEAR_LENGTH + AcctSnapshot.DATE_MONTH_LENGTH,
                            AcctSnapshot.DATE_DAY_LENGTH, PictureKind.ALPHANUMERIC));
        }

        private static List<FieldSpan> custSpans(String prefix, boolean newShape, int base) {
            List<FieldSpan> spans = new ArrayList<>();
            FieldSpan custId = FieldSpan.alphanumeric(prefix + "CUST-ID-X",
                    base + CustSnapshot.CUST_ID_OFFSET, CustSnapshot.CUST_ID_LENGTH);
            spans.add(custId);
            spans.add(custId.redefinedAs(prefix + "CUST-ID", PictureKind.UNSIGNED_NUMERIC));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-FIRST-NAME",
                    base + CustSnapshot.FIRST_NAME_OFFSET, CustSnapshot.NAME_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-MIDDLE-NAME",
                    base + CustSnapshot.MIDDLE_NAME_OFFSET, CustSnapshot.NAME_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-LAST-NAME",
                    base + CustSnapshot.LAST_NAME_OFFSET, CustSnapshot.NAME_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-LINE-1",
                    base + CustSnapshot.ADDR_LINE_1_OFFSET, CustSnapshot.ADDR_LINE_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-LINE-2",
                    base + CustSnapshot.ADDR_LINE_2_OFFSET, CustSnapshot.ADDR_LINE_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-LINE-3",
                    base + CustSnapshot.ADDR_LINE_3_OFFSET, CustSnapshot.ADDR_LINE_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-STATE-CD",
                    base + CustSnapshot.ADDR_STATE_CD_OFFSET, CustSnapshot.ADDR_STATE_CD_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-COUNTRY-CD",
                    base + CustSnapshot.ADDR_COUNTRY_CD_OFFSET, CustSnapshot.ADDR_COUNTRY_CD_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-ADDR-ZIP",
                    base + CustSnapshot.ADDR_ZIP_OFFSET, CustSnapshot.ADDR_ZIP_LENGTH));
            spans.addAll(phoneSpans(prefix, "1", base + CustSnapshot.PHONE_NUM_1_OFFSET));
            spans.addAll(phoneSpans(prefix, "2", base + CustSnapshot.PHONE_NUM_2_OFFSET));
            spans.addAll(ssnSpans(prefix, newShape, base + CustSnapshot.SSN_OFFSET));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-GOVT-ISSUED-ID",
                    base + CustSnapshot.GOVT_ISSUED_ID_OFFSET, CustSnapshot.GOVT_ISSUED_ID_LENGTH));
            spans.addAll(dobSpans(prefix, base + CustSnapshot.DOB_OFFSET));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-EFT-ACCOUNT-ID",
                    base + CustSnapshot.EFT_ACCOUNT_ID_OFFSET, CustSnapshot.EFT_ACCOUNT_ID_LENGTH));
            spans.add(FieldSpan.alphanumeric(prefix + "CUST-PRI-HOLDER-IND",
                    base + CustSnapshot.PRI_HOLDER_IND_OFFSET, CustSnapshot.PRI_HOLDER_IND_LENGTH));
            FieldSpan fico = FieldSpan.alphanumeric(prefix + "CUST-FICO-SCORE-X",
                    base + CustSnapshot.FICO_SCORE_OFFSET, CustSnapshot.FICO_SCORE_LENGTH);
            spans.add(fico);
            spans.add(fico.redefinedAs(prefix + "CUST-FICO-SCORE", PictureKind.UNSIGNED_NUMERIC));
            return spans;
        }

        private static List<FieldSpan> phoneSpans(String prefix, String which, int offset) {
            String item = prefix + "CUST-PHONE-NUM-" + which;
            return List.of(
                    FieldSpan.alphanumeric(item, offset, CustSnapshot.PHONE_NUM_LENGTH),
                    FieldSpan.redefining(item + "-X", offset, CustSnapshot.PHONE_NUM_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM, offset, CustSnapshot.PHONE_LEADING_FILLER_LENGTH,
                            PictureKind.FILLER),
                    FieldSpan.redefining(item + "A",
                            offset + CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET,
                            CustSnapshot.PHONE_AREA_CODE_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM,
                            offset + CustSnapshot.PHONE_AREA_CODE_RELATIVE_OFFSET
                                    + CustSnapshot.PHONE_AREA_CODE_LENGTH,
                            CustSnapshot.PHONE_INNER_FILLER_LENGTH, PictureKind.FILLER),
                    FieldSpan.redefining(item + "B", offset + CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET,
                            CustSnapshot.PHONE_PREFIX_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM,
                            offset + CustSnapshot.PHONE_PREFIX_RELATIVE_OFFSET
                                    + CustSnapshot.PHONE_PREFIX_LENGTH,
                            CustSnapshot.PHONE_INNER_FILLER_LENGTH, PictureKind.FILLER),
                    FieldSpan.redefining(item + "C",
                            offset + CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET,
                            CustSnapshot.PHONE_LINE_NUMBER_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(FILLER_ITEM,
                            offset + CustSnapshot.PHONE_LINE_NUMBER_RELATIVE_OFFSET
                                    + CustSnapshot.PHONE_LINE_NUMBER_LENGTH,
                            CustSnapshot.PHONE_TRAILING_FILLER_LENGTH, PictureKind.FILLER));
        }

        private static List<FieldSpan> ssnSpans(String prefix, boolean newShape, int offset) {
            if (!newShape) {
                FieldSpan flat = FieldSpan.alphanumeric(prefix + "CUST-SSN-X", offset,
                        CustSnapshot.SSN_LENGTH);
                return List.of(flat, flat.redefinedAs(prefix + "CUST-SSN", PictureKind.UNSIGNED_NUMERIC));
            }
            return List.of(
                    FieldSpan.alphanumeric(prefix + "CUST-SSN-1", offset, CustSnapshot.SSN_PART_1_LENGTH),
                    FieldSpan.alphanumeric(prefix + "CUST-SSN-2", offset + CustSnapshot.SSN_PART_1_LENGTH,
                            CustSnapshot.SSN_PART_2_LENGTH),
                    FieldSpan.alphanumeric(prefix + "CUST-SSN-3",
                            offset + CustSnapshot.SSN_PART_1_LENGTH + CustSnapshot.SSN_PART_2_LENGTH,
                            CustSnapshot.SSN_PART_3_LENGTH),
                    FieldSpan.redefining(prefix + "CUST-SSN-X", offset, CustSnapshot.SSN_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-SSN", offset, CustSnapshot.SSN_LENGTH,
                            PictureKind.UNSIGNED_NUMERIC));
        }

        private static List<FieldSpan> dobSpans(String prefix, int offset) {
            return List.of(
                    FieldSpan.alphanumeric(prefix + "CUST-DOB-YYYY-MM-DD", offset,
                            CustSnapshot.DOB_LENGTH),
                    FieldSpan.redefining(prefix + "CUST-DOB-PARTS", offset, CustSnapshot.DOB_LENGTH,
                            PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-DOB-YEAR", offset,
                            AcctSnapshot.DATE_YEAR_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-DOB-MON", offset + AcctSnapshot.DATE_YEAR_LENGTH,
                            AcctSnapshot.DATE_MONTH_LENGTH, PictureKind.ALPHANUMERIC),
                    FieldSpan.redefining(prefix + "CUST-DOB-DAY",
                            offset + AcctSnapshot.DATE_YEAR_LENGTH + AcctSnapshot.DATE_MONTH_LENGTH,
                            AcctSnapshot.DATE_DAY_LENGTH, PictureKind.ALPHANUMERIC));
        }

        /**
         * The item-name prefix every item of this group carries.
         *
         * @return {@code "ACUP-OLD-"} or {@code "ACUP-NEW-"}
         */
        public String prefix() {
            return prefix;
        }

        /**
         * The verbatim COBOL name of the {@code 05}-level group itself.
         *
         * @return {@code "ACUP-OLD-DETAILS"} or {@code "ACUP-NEW-DETAILS"}
         */
        public String groupName() {
            return prefix + "DETAILS";
        }

        /**
         * Qualifies an item suffix with this group's prefix.
         *
         * @param suffix the item suffix, for example {@code "EXPIRAION-DATE"}
         * @return the verbatim COBOL item name
         * @throws NullPointerException if {@code suffix} is {@code null}
         */
        public String qualify(String suffix) {
            Objects.requireNonNull(suffix, "An item suffix is required to qualify a group item name");
            return prefix + suffix;
        }

        /**
         * Whether this group declares {@code CUST-SSN-1}, {@code -2} and {@code -3} as named sub-items -
         * true of {@code NEW} only, per {@code app/cbl/COACTUPC.cbl:830-833} against {@code :742}.
         *
         * @return {@code true} for {@link #NEW}
         */
        public boolean declaresSsnParts() {
            return newShape;
        }

        /**
         * Whether this group's FICO field carries {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} -
         * true of {@code NEW} only, per {@code app/cbl/COACTUPC.cbl:848-849}.
         *
         * @return {@code true} for {@link #NEW}
         */
        public boolean declaresFicoRangeCondition() {
            return newShape;
        }

        /**
         * This group's whole {@value Details#RECORD_LENGTH}-byte layout.
         *
         * @return the layout, with every byte declared and every overlay named under this group's prefix
         */
        public RecordLayout layout() {
            return layout;
        }

        /**
         * This group's account half as a standalone {@value AcctSnapshot#RECORD_LENGTH}-byte layout.
         *
         * @return the account layout
         */
        public RecordLayout acctLayout() {
            return acctLayout;
        }

        /**
         * This group's customer half as a standalone {@value CustSnapshot#RECORD_LENGTH}-byte layout.
         *
         * @return the customer layout
         */
        public RecordLayout custLayout() {
            return custLayout;
        }
    }

    private static final String FILLER_ITEM = "FILLER";

    /**
     * One {@code 05}-level detail group - {@value #RECORD_LENGTH} bytes made of an account half and a
     * customer half, labelled with which of the two groups it is.
     *
     * @param group which of the two groups this is
     * @param acct the {@value AcctSnapshot#RECORD_LENGTH}-byte account half
     * @param cust the {@value CustSnapshot#RECORD_LENGTH}-byte customer half
     */
    public record Details(DetailGroup group, AcctSnapshot acct, CustSnapshot cust) {
        public static final int RECORD_LENGTH = AcctSnapshot.RECORD_LENGTH + CustSnapshot.RECORD_LENGTH;

        /**
         * Absolute 0-based offset of {@code 10 ACUP-xxx-ACCT-DATA} within the group.
         */
        public static final int ACCT_DATA_OFFSET = 0;

        /**
         * Absolute 0-based offset of {@code 10 ACUP-xxx-CUST-DATA}: {@value AcctSnapshot#RECORD_LENGTH}.
         */
        public static final int CUST_DATA_OFFSET = AcctSnapshot.RECORD_LENGTH;

        public Details {
            Objects.requireNonNull(group, "A DetailGroup is required: ACUP-OLD-DETAILS and "
                    + "ACUP-NEW-DETAILS share a layout but not a single item name, and the names are what "
                    + "a field-by-field diff compares");
            acct = acct == null ? AcctSnapshot.initialised() : acct;
            cust = cust == null ? CustSnapshot.initialised() : cust;
        }

        /**
         * A group in which every item holds its declared width in spaces - the state
         * {@code INITIALIZE ACUP-NEW-DETAILS} ({@code app/cbl/COACTUPC.cbl:1047}) leaves behind.
         *
         * @param group which of the two groups to build
         * @return the all-spaces group
         * @throws NullPointerException if {@code group} is {@code null}
         */
        public static Details initialised(DetailGroup group) {
            return new Details(group, AcctSnapshot.initialised(), CustSnapshot.initialised());
        }

        /**
         * The same bytes relabelled as the other group, for the
         * {@code MOVE ACUP-NEW-DETAILS TO ACUP-OLD-DETAILS} shape of operation - a move that changes which
         * names the bytes answer to without changing the bytes.
         *
         * @param target which group to relabel as
         * @return this group when already labelled {@code target}, otherwise a relabelled copy
         * @throws NullPointerException if {@code target} is {@code null}
         */
        public Details asGroup(DetailGroup target) {
            Objects.requireNonNull(target, "A target DetailGroup is required to relabel a snapshot");
            return target == group ? this : new Details(target, acct, cust);
        }

        /**
         * The verbatim COBOL name of this group.
         *
         * @return {@code "ACUP-OLD-DETAILS"} or {@code "ACUP-NEW-DETAILS"}
         */
        @JsonIgnore
        public String groupName() {
            return group.groupName();
        }

        /**
         * Renders this group as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders this group as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        /**
         * This group as a record area under its own layout, so a caller can read any span by name -
         * including a {@code REDEFINES} overlay and a {@code FILLER}.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return a {@value #RECORD_LENGTH}-byte area
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the code page is required to render "
                    + "a detail group; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(group.layout());
            acct.writeInto(area, ACCT_DATA_OFFSET);
            cust.writeInto(area, CUST_DATA_OFFSET);
            return area;
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a group.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param group which group the bytes are
         * @param codec the codec, carrying the code page explicitly
         * @return the group
         * @throws NullPointerException if any argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes
         */
        public static Details decode(byte[] image, DetailGroup group, FixedWidthCodec codec) {
            Objects.requireNonNull(group, "A DetailGroup is required to decode a snapshot");
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to decode "
                    + "a detail group; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode a detail group");
            if (image.length != RECORD_LENGTH) {
                throw new IllegalArgumentException(group.groupName() + " is " + RECORD_LENGTH + " bytes - "
                        + AcctSnapshot.RECORD_LENGTH + " of account data plus "
                        + CustSnapshot.RECORD_LENGTH + " of customer data - but this image is "
                        + image.length + " byte(s)");
            }
            return readFrom(codec.wrap(image, group.layout()), group, ACCT_DATA_OFFSET);
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a group, in a named code page.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param group which group the bytes are
         * @param charset the code page, stated explicitly
         * @return the group
         * @throws NullPointerException if any argument is {@code null}
         */
        public static Details decode(byte[] image, DetailGroup group, Charset charset) {
            return decode(image, group, new FixedWidthCodec(charset));
        }

        private static Details readFrom(FixedWidthRecord area, DetailGroup group, int base) {
            return new Details(group,
                    AcctSnapshot.readFrom(area, base + ACCT_DATA_OFFSET),
                    CustSnapshot.readFrom(area, base + CUST_DATA_OFFSET));
        }

        /**
         * A diagnostic rendering that delegates to the two snapshots' own safe renderings.
         *
         * @return the rendering; never {@code null}
         */
        @Override
        public String toString() {
            return "Details[group=" + group + ", acct=" + acct + ", cust=" + cust + ']';
        }
    }

    /**
     * {@code 01 WS-THIS-PROGCOMMAREA} - the {@value #RECORD_LENGTH}-byte work area {@code COACTUPC} keeps
     * across a pseudo-conversational turn, {@code app/cbl/COACTUPC.cbl:652-849}.
     *
     * @param changeAction the single-byte state, with its nine {@code 88}-level conditions
     * @param oldDetails the {@code ACUP-OLD-DETAILS} snapshot, labelled {@link DetailGroup#OLD}
     * @param newDetails the {@code ACUP-NEW-DETAILS} snapshot, labelled {@link DetailGroup#NEW}
     */
    public record CommArea(ChangeAction changeAction, Details oldDetails, Details newDetails) {
        public static final int RECORD_LENGTH =
                ChangeAction.RECORD_LENGTH + Details.RECORD_LENGTH + Details.RECORD_LENGTH;

        /**
         * Absolute 0-based offset of {@code ACCT-UPDATE-SCREEN-DATA} and its single byte.
         */
        public static final int CHANGE_ACTION_OFFSET = 0;

        /**
         * Absolute 0-based offset of {@code ACUP-OLD-DETAILS}: {@code 0 + 1}.
         */
        public static final int OLD_DETAILS_OFFSET = ChangeAction.RECORD_LENGTH;

        /**
         * Absolute 0-based offset of {@code ACUP-NEW-DETAILS}: {@code 1 + 436}.
         */
        public static final int NEW_DETAILS_OFFSET = OLD_DETAILS_OFFSET + Details.RECORD_LENGTH;

        /**
         * {@code ACUP-CHANGE-ACTION PIC X(1)} at offset {@value #CHANGE_ACTION_OFFSET}.
         */
        public static final FieldSpan ACUP_CHANGE_ACTION = FieldSpan.alphanumeric(
                ChangeAction.FIELD_NAME, CHANGE_ACTION_OFFSET, ChangeAction.RECORD_LENGTH);

        public static final RecordLayout LAYOUT = buildLayout();

        public CommArea {
            Objects.requireNonNull(changeAction, "ACUP-CHANGE-ACTION is required; its unset state is "
                    + "LOW-VALUES, which is a byte and not a Java null");
            oldDetails = oldDetails == null ? Details.initialised(DetailGroup.OLD) : oldDetails;
            newDetails = newDetails == null ? Details.initialised(DetailGroup.NEW) : newDetails;
            if (oldDetails.group() != DetailGroup.OLD) {
                throw new IllegalArgumentException("The snapshot in the ACUP-OLD-DETAILS position is "
                        + "labelled " + oldDetails.groupName() + "; relabel it with "
                        + "asGroup(DetailGroup.OLD) rather than storing it under the wrong names");
            }
            if (newDetails.group() != DetailGroup.NEW) {
                throw new IllegalArgumentException("The snapshot in the ACUP-NEW-DETAILS position is "
                        + "labelled " + newDetails.groupName() + "; relabel it with "
                        + "asGroup(DetailGroup.NEW) rather than storing it under the wrong names");
            }
        }

        /**
         * The work area as the program starts it: {@code ACUP-CHANGE-ACTION} at its declared
         * {@code VALUE LOW-VALUES} and both snapshots all spaces.
         *
         * @return the initial work area
         */
        public static CommArea initialised() {
            return new CommArea(ChangeAction.initial(),
                    Details.initialised(DetailGroup.OLD),
                    Details.initialised(DetailGroup.NEW));
        }

        /**
         * This work area with a different change-action byte - the immutable form of
         * {@code SET ACUP-xxx TO TRUE}.
         *
         * @param replacement the new state
         * @return a new work area
         * @throws NullPointerException if {@code replacement} is {@code null}
         */
        public CommArea withChangeAction(ChangeAction replacement) {
            Objects.requireNonNull(replacement, "A change-action byte is required");
            return new CommArea(replacement, oldDetails, newDetails);
        }

        /**
         * This work area with a different {@code ACUP-OLD-DETAILS} snapshot.
         *
         * @param replacement the new snapshot, which must be labelled {@link DetailGroup#OLD}
         * @return a new work area
         * @throws IllegalArgumentException if {@code replacement} is labelled {@link DetailGroup#NEW}
         */
        public CommArea withOldDetails(Details replacement) {
            return new CommArea(changeAction, replacement, newDetails);
        }

        /**
         * This work area with a different {@code ACUP-NEW-DETAILS} snapshot.
         *
         * @param replacement the new snapshot, which must be labelled {@link DetailGroup#NEW}
         * @return a new work area
         * @throws IllegalArgumentException if {@code replacement} is labelled {@link DetailGroup#OLD}
         */
        public CommArea withNewDetails(Details replacement) {
            return new CommArea(changeAction, oldDetails, replacement);
        }

        /**
         * Renders the work area as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] encode(FixedWidthCodec codec) {
            return toFixedWidthRecord(codec).toByteArray();
        }

        /**
         * Renders the work area as exactly {@value #RECORD_LENGTH} bytes in a named code page.
         *
         * @param charset the code page, stated explicitly and never defaulted
         * @return exactly {@value #RECORD_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] encode(Charset charset) {
            return encode(new FixedWidthCodec(charset));
        }

        /**
         * The work area as a record area under {@link #LAYOUT}, so a caller can read any span by name.
         *
         * @param codec the codec, carrying the code page explicitly
         * @return a {@value #RECORD_LENGTH}-byte area
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public FixedWidthRecord toFixedWidthRecord(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the code page is required to render "
                    + "WS-THIS-PROGCOMMAREA; the platform default is never assumed");
            FixedWidthRecord area = codec.newRecord(LAYOUT);
            codec.writePicX(area, ACUP_CHANGE_ACTION, changeAction.value());
            oldDetails.acct().writeInto(area, OLD_DETAILS_OFFSET + Details.ACCT_DATA_OFFSET);
            oldDetails.cust().writeInto(area, OLD_DETAILS_OFFSET + Details.CUST_DATA_OFFSET);
            newDetails.acct().writeInto(area, NEW_DETAILS_OFFSET + Details.ACCT_DATA_OFFSET);
            newDetails.cust().writeInto(area, NEW_DETAILS_OFFSET + Details.CUST_DATA_OFFSET);
            return area;
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a work area.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param codec the codec, carrying the code page explicitly
         * @return the work area
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalArgumentException if {@code image} is not {@value #RECORD_LENGTH} bytes
         */
        public static CommArea decode(byte[] image, FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec carrying the image's code page is required to decode "
                    + "WS-THIS-PROGCOMMAREA; the platform default is never assumed");
            Objects.requireNonNull(image, "An image is required to decode WS-THIS-PROGCOMMAREA");
            if (image.length != RECORD_LENGTH) {
                throw new IllegalArgumentException("WS-THIS-PROGCOMMAREA is " + RECORD_LENGTH + " bytes - "
                        + ChangeAction.RECORD_LENGTH + " of change action plus two detail groups of "
                        + Details.RECORD_LENGTH + " - but this image is " + image.length + " byte(s)");
            }
            FixedWidthRecord area = codec.wrap(image, LAYOUT);
            return new CommArea(ChangeAction.of(codec.readPicX(area, ACUP_CHANGE_ACTION)),
                    Details.readFrom(area, DetailGroup.OLD, OLD_DETAILS_OFFSET),
                    Details.readFrom(area, DetailGroup.NEW, NEW_DETAILS_OFFSET));
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into a work area, in a named code page.
         *
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @param charset the code page, stated explicitly
         * @return the work area
         * @throws NullPointerException if either argument is {@code null}
         */
        public static CommArea decode(byte[] image, Charset charset) {
            return decode(image, new FixedWidthCodec(charset));
        }

        private static RecordLayout buildLayout() {
            List<FieldSpan> spans = new ArrayList<>();
            spans.add(ACUP_CHANGE_ACTION);
            spans.add(FieldSpan.redefining(ChangeAction.GROUP_NAME, CHANGE_ACTION_OFFSET,
                    ChangeAction.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.addAll(shifted(DetailGroup.OLD, OLD_DETAILS_OFFSET));
            spans.add(FieldSpan.redefining(DetailGroup.OLD.groupName(), OLD_DETAILS_OFFSET,
                    Details.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            spans.addAll(shifted(DetailGroup.NEW, NEW_DETAILS_OFFSET));
            spans.add(FieldSpan.redefining(DetailGroup.NEW.groupName(), NEW_DETAILS_OFFSET,
                    Details.RECORD_LENGTH, PictureKind.ALPHANUMERIC));
            return RecordLayout.of(RECORD_LENGTH, spans.toArray(FieldSpan[]::new));
        }

        private static List<FieldSpan> shifted(DetailGroup group, int offset) {
            List<FieldSpan> shifted = new ArrayList<>();
            for (FieldSpan span : group.layout().spans()) {
                shifted.add(new FieldSpan(span.name(), span.offset() + offset, span.length(), span.kind(),
                        span.initialValue(), span.redefinition()));
            }
            return shifted;
        }
    }

    /**
     * Collects the {@value AccountUpdateRequest#FIELD_COUNT} screen fields, the three conversation-state
     * carriers and the per-field metadata, and produces an immutable {@link AccountUpdateRequest}.
     */
    @JsonPOJOBuilder(withPrefix = "")
    @JsonIgnoreProperties({
            ResponseOnlyMembers.NEXT_PROGRAM,
            ResponseOnlyMembers.NEXT_MAPSET,
            ResponseOnlyMembers.NEXT_MAP,
            ResponseOnlyMembers.SCREEN_METADATA})
    public static final class Builder {
        private String trnname;

        private String title01;

        private String curdate;

        private String pgmname;

        private String title02;

        private String curtime;

        private String acctsid;

        private String acsttus;

        private String opnyear;

        private String opnmon;

        private String opnday;

        private String acrdlim;

        private String expyear;

        private String expmon;

        private String expday;

        private String acshlim;

        private String risyear;

        private String rismon;

        private String risday;

        private String acurbal;

        private String acrcycr;

        private String aaddgrp;

        private String acrcydb;

        private String acstnum;

        private String actssn1;

        private String actssn2;

        private String actssn3;

        private String dobyear;

        private String dobmon;

        private String dobday;

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

        private String acsph1a;

        private String acsph1b;

        private String acsph1c;

        private String acsgovt;

        private String acsph2a;

        private String acsph2b;

        private String acsph2c;

        private String acseftc;

        private String acspflg;

        private String infomsg;

        private String errmsg;

        private String fkeys;

        private String fkey05;

        private String fkey12;

        private CommArea commArea;

        /** The sealed {@code WS-THIS-PROGCOMMAREA}; {@code null} becomes empty. */
        private String stateToken;

        /** The {@code CVCRD01Y} work area; {@code null} becomes a freshly constructed one. */
        private CardScreenState cardScreenState;

        private NavigationContext navigationContext;

        private final Map<ScreenField, FieldMetadata> metadata = new EnumMap<>(ScreenField.class);

        private Builder() {
        }

        public Builder trnname(String value) {
            this.trnname = value;
            return this;
        }

        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        public Builder curdate(String value) {
            this.curdate = value;
            return this;
        }

        public Builder pgmname(String value) {
            this.pgmname = value;
            return this;
        }

        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        public Builder curtime(String value) {
            this.curtime = value;
            return this;
        }

        public Builder acctsid(String value) {
            this.acctsid = value;
            return this;
        }

        public Builder acsttus(String value) {
            this.acsttus = value;
            return this;
        }

        public Builder opnyear(String value) {
            this.opnyear = value;
            return this;
        }

        public Builder opnmon(String value) {
            this.opnmon = value;
            return this;
        }

        public Builder opnday(String value) {
            this.opnday = value;
            return this;
        }

        public Builder acrdlim(String value) {
            this.acrdlim = value;
            return this;
        }

        public Builder expyear(String value) {
            this.expyear = value;
            return this;
        }

        public Builder expmon(String value) {
            this.expmon = value;
            return this;
        }

        public Builder expday(String value) {
            this.expday = value;
            return this;
        }

        public Builder acshlim(String value) {
            this.acshlim = value;
            return this;
        }

        public Builder risyear(String value) {
            this.risyear = value;
            return this;
        }

        public Builder rismon(String value) {
            this.rismon = value;
            return this;
        }

        public Builder risday(String value) {
            this.risday = value;
            return this;
        }

        public Builder acurbal(String value) {
            this.acurbal = value;
            return this;
        }

        public Builder acrcycr(String value) {
            this.acrcycr = value;
            return this;
        }

        public Builder aaddgrp(String value) {
            this.aaddgrp = value;
            return this;
        }

        public Builder acrcydb(String value) {
            this.acrcydb = value;
            return this;
        }

        public Builder acstnum(String value) {
            this.acstnum = value;
            return this;
        }

        public Builder actssn1(String value) {
            this.actssn1 = value;
            return this;
        }

        public Builder actssn2(String value) {
            this.actssn2 = value;
            return this;
        }

        public Builder actssn3(String value) {
            this.actssn3 = value;
            return this;
        }

        public Builder dobyear(String value) {
            this.dobyear = value;
            return this;
        }

        public Builder dobmon(String value) {
            this.dobmon = value;
            return this;
        }

        public Builder dobday(String value) {
            this.dobday = value;
            return this;
        }

        public Builder acstfco(String value) {
            this.acstfco = value;
            return this;
        }

        public Builder acsfnam(String value) {
            this.acsfnam = value;
            return this;
        }

        public Builder acsmnam(String value) {
            this.acsmnam = value;
            return this;
        }

        public Builder acslnam(String value) {
            this.acslnam = value;
            return this;
        }

        public Builder acsadl1(String value) {
            this.acsadl1 = value;
            return this;
        }

        public Builder acsstte(String value) {
            this.acsstte = value;
            return this;
        }

        public Builder acsadl2(String value) {
            this.acsadl2 = value;
            return this;
        }

        public Builder acszipc(String value) {
            this.acszipc = value;
            return this;
        }

        public Builder acscity(String value) {
            this.acscity = value;
            return this;
        }

        public Builder acsctry(String value) {
            this.acsctry = value;
            return this;
        }

        public Builder acsph1a(String value) {
            this.acsph1a = value;
            return this;
        }

        public Builder acsph1b(String value) {
            this.acsph1b = value;
            return this;
        }

        public Builder acsph1c(String value) {
            this.acsph1c = value;
            return this;
        }

        public Builder acsgovt(String value) {
            this.acsgovt = value;
            return this;
        }

        public Builder acsph2a(String value) {
            this.acsph2a = value;
            return this;
        }

        public Builder acsph2b(String value) {
            this.acsph2b = value;
            return this;
        }

        public Builder acsph2c(String value) {
            this.acsph2c = value;
            return this;
        }

        public Builder acseftc(String value) {
            this.acseftc = value;
            return this;
        }

        public Builder acspflg(String value) {
            this.acspflg = value;
            return this;
        }

        public Builder infomsg(String value) {
            this.infomsg = value;
            return this;
        }

        public Builder errmsg(String value) {
            this.errmsg = value;
            return this;
        }

        public Builder fkeys(String value) {
            this.fkeys = value;
            return this;
        }

        public Builder fkey05(String value) {
            this.fkey05 = value;
            return this;
        }

        public Builder fkey12(String value) {
            this.fkey12 = value;
            return this;
        }

        /**
         * Sets {@code WS-THIS-PROGCOMMAREA}.
         *
         * <p>{@code @JsonIgnore} so that Jackson never binds it: the area is state this screen issues, and
         * a caller that could write it could claim the confirmation its twenty-four edits are proof of.
         * The wire carries {@link #stateToken(String)} instead. This setter stays available to the
         * controller, to a service and to a parity case, all of which construct the request in Java.
         *
         * @param value the work area; {@code null} becomes {@link CommArea#initialised()}
         * @return this builder
         */
        @JsonIgnore
        public Builder commArea(CommArea value) {
            this.commArea = value;
            return this;
        }

        /**
         * Sets the sealed {@code WS-THIS-PROGCOMMAREA}.
         *
         * @param value the token as the caller sent it; {@code null} becomes empty
         * @return this builder
         */
        public Builder stateToken(String value) {
            this.stateToken = value;
            return this;
        }

        /**
         * Sets the {@code CVCRD01Y} work area.
         *
         * @param value the work area; {@code null} becomes a freshly constructed one, and a
         *              non-{@code null} one is copied by {@link #build()}
         * @return this builder
         */
        public Builder cardScreenState(CardScreenState value) {
            this.cardScreenState = value;
            return this;
        }

        /**
         * Sets the {@code CARDDEMO-COMMAREA}.
         *
         * @param value the communication area, or {@code null} for the {@code EIBCALEN = 0} cold start,
         *     which is kept rather than completed
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Sets one field by enumeration, for a caller that addresses fields generically - a differ, a
         * fixture loader, {@link AccountUpdateRequest#normalize(FixedWidthCodec)}.
         *
         * @param field which field
         * @param value the value, stored verbatim
         * @return this builder
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public Builder value(ScreenField field, String value) {
            Objects.requireNonNull(field, "A ScreenField is required to set a field's value");
            switch (field) {
                case TRNNAME -> trnname = value;
                case TITLE01 -> title01 = value;
                case CURDATE -> curdate = value;
                case PGMNAME -> pgmname = value;
                case TITLE02 -> title02 = value;
                case CURTIME -> curtime = value;
                case ACCTSID -> acctsid = value;
                case ACSTTUS -> acsttus = value;
                case OPNYEAR -> opnyear = value;
                case OPNMON -> opnmon = value;
                case OPNDAY -> opnday = value;
                case ACRDLIM -> acrdlim = value;
                case EXPYEAR -> expyear = value;
                case EXPMON -> expmon = value;
                case EXPDAY -> expday = value;
                case ACSHLIM -> acshlim = value;
                case RISYEAR -> risyear = value;
                case RISMON -> rismon = value;
                case RISDAY -> risday = value;
                case ACURBAL -> acurbal = value;
                case ACRCYCR -> acrcycr = value;
                case AADDGRP -> aaddgrp = value;
                case ACRCYDB -> acrcydb = value;
                case ACSTNUM -> acstnum = value;
                case ACTSSN1 -> actssn1 = value;
                case ACTSSN2 -> actssn2 = value;
                case ACTSSN3 -> actssn3 = value;
                case DOBYEAR -> dobyear = value;
                case DOBMON -> dobmon = value;
                case DOBDAY -> dobday = value;
                case ACSTFCO -> acstfco = value;
                case ACSFNAM -> acsfnam = value;
                case ACSMNAM -> acsmnam = value;
                case ACSLNAM -> acslnam = value;
                case ACSADL1 -> acsadl1 = value;
                case ACSSTTE -> acsstte = value;
                case ACSADL2 -> acsadl2 = value;
                case ACSZIPC -> acszipc = value;
                case ACSCITY -> acscity = value;
                case ACSCTRY -> acsctry = value;
                case ACSPH1A -> acsph1a = value;
                case ACSPH1B -> acsph1b = value;
                case ACSPH1C -> acsph1c = value;
                case ACSGOVT -> acsgovt = value;
                case ACSPH2A -> acsph2a = value;
                case ACSPH2B -> acsph2b = value;
                case ACSPH2C -> acsph2c = value;
                case ACSEFTC -> acseftc = value;
                case ACSPFLG -> acspflg = value;
                case INFOMSG -> infomsg = value;
                case ERRMSG -> errmsg = value;
                case FKEYS -> fkeys = value;
                case FKEY05 -> fkey05 = value;
                case FKEY12 -> fkey12 = value;
            }
            return this;
        }

        public Builder metadata(ScreenField field, FieldMetadata replacement) {
            Objects.requireNonNull(field, "A ScreenField is required to set a field's metadata");
            if (replacement == null) {
                metadata.remove(field);
            } else {
                metadata.put(field, replacement);
            }
            return this;
        }

        public AccountUpdateRequest build() {
            return new AccountUpdateRequest(this);
        }
    }
}
