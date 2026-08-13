package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The inbound REST payload of {@code GET /api/admin/menu} - the admin menu screen of CICS transaction
 * {@link #TRANSACTION_ID}, program {@link #PROGRAM_NAME}, mapset {@link #MAPSET_NAME}, map
 * {@link #MAP_NAME}.
 *
 * <p>{@code app/bms/COADM01.bms} declares 28 {@code DFHMDF} fields of which exactly
 * {@value #MAPPED_FIELD_COUNT} carry a name.
 *
 * @param trnName {@code TRNNAMEI PIC X(4)} (line 24): the transaction identifier, {@link #TRANSACTION_ID}
 * @param title01 {@code TITLE01I PIC X(40)} (line 30): the first title line
 * @param curDate {@code CURDATEI PIC X(8)} (line 36): the date header, {@code MM/DD/YY}
 * @param pgmName {@code PGMNAMEI PIC X(8)} (line 42): the program name, {@link #PROGRAM_NAME}
 * @param title02 {@code TITLE02I PIC X(40)} (line 48): the second title line
 * @param curTime {@code CURTIMEI PIC X(8)} (line 54): the time header, {@code HH:MM:SS}
 * @param optn001 {@code OPTN001I PIC X(40)} (line 60): menu option line 1
 * @param optn002 {@code OPTN002I PIC X(40)} (line 66): menu option line 2
 * @param optn003 {@code OPTN003I PIC X(40)} (line 72): menu option line 3
 * @param optn004 {@code OPTN004I PIC X(40)} (line 78): menu option line 4
 * @param optn005 {@code OPTN005I PIC X(40)} (line 84): menu option line 5
 * @param optn006 {@code OPTN006I PIC X(40)} (line 90): menu option line 6
 * @param optn007 {@code OPTN007I PIC X(40)} (line 96): menu option line 7
 * @param optn008 {@code OPTN008I PIC X(40)} (line 102): menu option line 8
 * @param optn009 {@code OPTN009I PIC X(40)} (line 108): menu option line 9
 * @param optn010 {@code OPTN010I PIC X(40)} (line 114): menu option line 10
 * @param optn011 {@code OPTN011I PIC X(40)} (line 120): menu option line 11
 * @param optn012 {@code OPTN012I PIC X(40)} (line 126): menu option line 12
 * @param option {@code OPTIONI PIC X(2)} (line 132): the typed option, and the only unprotected field on
 *     the screen
 * @param errMsg {@code ERRMSGI PIC X(78)} (line 138): the error message line, 78 and never 80
 * @param navigationContext the inbound {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, exactly
 *     160 bytes, carrying {@code CDEMO-USER-TYPE} with its {@code 88} admin and user levels
 * @param eibAid the raw {@code EIBAID} attention identifier byte, carried unresolved
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record AdminMenuRequest(@Size(max = TRN_NAME_LENGTH) @JsonProperty("trnname") String trnName,
                               @Size(max = TITLE_LENGTH) String title01,
                               @Size(max = CUR_DATE_LENGTH) @JsonProperty("curdate") String curDate,
                               @Size(max = PGM_NAME_LENGTH) @JsonProperty("pgmname") String pgmName,
                               @Size(max = TITLE_LENGTH) String title02,
                               @Size(max = CUR_TIME_LENGTH) @JsonProperty("curtime") String curTime,
                               @Size(max = OPTION_LINE_LENGTH) String optn001,
                               @Size(max = OPTION_LINE_LENGTH) String optn002,
                               @Size(max = OPTION_LINE_LENGTH) String optn003,
                               @Size(max = OPTION_LINE_LENGTH) String optn004,
                               @Size(max = OPTION_LINE_LENGTH) String optn005,
                               @Size(max = OPTION_LINE_LENGTH) String optn006,
                               @Size(max = OPTION_LINE_LENGTH) String optn007,
                               @Size(max = OPTION_LINE_LENGTH) String optn008,
                               @Size(max = OPTION_LINE_LENGTH) String optn009,
                               @Size(max = OPTION_LINE_LENGTH) String optn010,
                               @Size(max = OPTION_LINE_LENGTH) String optn011,
                               @Size(max = OPTION_LINE_LENGTH) String optn012,
                               @Size(max = OPTION_LENGTH) String option,
                               @Size(max = ERR_MSG_LENGTH) @JsonProperty("errmsg") String errMsg,
                               NavigationContext navigationContext,
                               byte eibAid) {
    /**
     * The CICS transaction identifier: {@code DEFINE TRANSACTION(CA00) ... PROGRAM(COADM01C)} at
     * {@code app/csd/CARDDEMO.CSD:327-328}, and the literal {@code WS-TRANID PIC X(04) VALUE 'CA00'} at
     * {@code app/cbl/COADM01C.cbl:37}.
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * The program name: {@code DEFINE PROGRAM(COADM01C)} at {@code app/csd/CARDDEMO.CSD:189}, and the
     * literal {@code WS-PGMNAME PIC X(08) VALUE 'COADM01C'} at {@code app/cbl/COADM01C.cbl:36}.
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The BMS mapset: {@code DEFINE MAPSET(COADM01)} at {@code app/csd/CARDDEMO.CSD:110}, declared as
     * {@code COADM01 DFHMSD} at {@code app/bms/COADM01.bms:19} and named by {@code MAPSET('COADM01')} at
     * {@code app/cbl/COADM01C.cbl:193}.
     */
    public static final String MAPSET_NAME = "COADM01";

    /**
     * The BMS map: {@code COADM1A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)} at
     * {@code app/bms/COADM01.bms:26-28}, named by {@code MAP('COADM1A')} at
     * {@code app/cbl/COADM01C.cbl:192}.
     */
    public static final String MAP_NAME = "COADM1A";

    // Every one of the twenty payload members below names its item here, so a reviewer - or the parity
    // differ, which compares by copybook field name - can walk this class against the copybook line by
    // line.

    public static final String TRN_NAME_FIELD = "TRNNAMEI";

    public static final String TITLE01_FIELD = "TITLE01I";

    public static final String CUR_DATE_FIELD = "CURDATEI";

    public static final String PGM_NAME_FIELD = "PGMNAMEI";

    public static final String TITLE02_FIELD = "TITLE02I";

    public static final String CUR_TIME_FIELD = "CURTIMEI";

    public static final String OPTN001_FIELD = "OPTN001I";

    public static final String OPTN002_FIELD = "OPTN002I";

    public static final String OPTN003_FIELD = "OPTN003I";

    public static final String OPTN004_FIELD = "OPTN004I";

    public static final String OPTN005_FIELD = "OPTN005I";

    public static final String OPTN006_FIELD = "OPTN006I";

    public static final String OPTN007_FIELD = "OPTN007I";

    public static final String OPTN008_FIELD = "OPTN008I";

    public static final String OPTN009_FIELD = "OPTN009I";

    public static final String OPTN010_FIELD = "OPTN010I";

    public static final String OPTN011_FIELD = "OPTN011I";

    public static final String OPTN012_FIELD = "OPTN012I";

    public static final String OPTION_FIELD = "OPTIONI";

    public static final String ERR_MSG_FIELD = "ERRMSGI";

    // Widths are NOT deduplicated by value: TITLE_LENGTH and OPTION_LINE_LENGTH are both 40 but come from
    // different PICTURE clauses, so they stay separate and a future divergence in one cannot silently move
    // the other.

    /**
     * Width of {@link #trnName()}: {@code TRNNAMEI PIC X(4)}, CPY line 24.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@link #title01()} and {@link #title02()}: {@code TITLE01I PIC X(40)} at CPY line 30 and
     * {@code TITLE02I PIC X(40)} at CPY line 48.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * Width of {@link #curDate()}: {@code CURDATEI PIC X(8)}, CPY line 36.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@link #pgmName()}: {@code PGMNAMEI PIC X(8)}, CPY line 42.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@link #curTime()}: {@code CURTIMEI PIC X(8)}, CPY line 54.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of each of {@link #optn001()} through {@link #optn012()}: {@code OPTN00nI PIC X(40)} at CPY
     * lines 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120 and 126.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * Width of {@link #option()}: {@code OPTIONI PIC X(2)}, CPY line 132.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * Width of {@link #errMsg()}: {@code ERRMSGI PIC X(78)}, CPY line 138.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * The number of menu option lines the map declares: twelve, at CPY lines 60 through 126 and
     * {@code app/bms/COADM01.bms:80-139}.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * The {@code TIOAPFX} prefix that opens both views: {@code 02 FILLER PIC X(12).} at CPY lines 18 and
     * 140, present because {@code app/bms/COADM01.bms:24} declares {@code TIOAPFX=YES}.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The metadata bytes each field contributes ahead of its value: seven.
     */
    public static final int METADATA_BYTES_PER_FIELD = 7;

    public static final int MAPPED_FIELD_COUNT = 20;

    public static final int PAYLOAD_DATA_LENGTH = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + OPTION_LINE_COUNT * OPTION_LINE_LENGTH
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * The width of the whole symbolic map, either view: 12 + (20 x 7) + 668, which is 820.
     */
    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_LENGTH + MAPPED_FIELD_COUNT * METADATA_BYTES_PER_FIELD + PAYLOAD_DATA_LENGTH;

    public static final List<String> OPTION_LINE_FIELDS = List.of(OPTN001_FIELD,
            OPTN002_FIELD,
            OPTN003_FIELD,
            OPTN004_FIELD,
            OPTN005_FIELD,
            OPTN006_FIELD,
            OPTN007_FIELD,
            OPTN008_FIELD,
            OPTN009_FIELD,
            OPTN010_FIELD,
            OPTN011_FIELD,
            OPTN012_FIELD);

    /**
     * The {@value #OPTION_LINE_COUNT} menu option lines in map order, {@link #optn001()} first.
     *
     * @return the twelve option lines in map order, never {@code null}, always of size
     *     {@value #OPTION_LINE_COUNT}, individual elements possibly {@code null}
     */
    @JsonIgnore
    public List<String> optionLines() {
        return Collections.unmodifiableList(Arrays.asList(optn001,
                optn002,
                optn003,
                optn004,
                optn005,
                optn006,
                optn007,
                optn008,
                optn009,
                optn010,
                optn011,
                optn012));
    }

    /**
     * Whether a communication area accompanied this request.
     *
     * @return {@code true} when a communication area is present, mirroring the {@code ELSE} branch at
     *     {@code app/cbl/COADM01C.cbl:85}; {@code false} when it is absent
     */
    @JsonIgnore
    public boolean isCommareaPresent() {
        return navigationContext != null;
    }

    /**
     * Whether this is a first entry into the transaction - {@code 88 CDEMO-PGM-ENTER VALUE 0}.
     *
     * <p>Returns {@code false} when no communication area is present, because the program never reaches its
     * context test in that case: {@code EIBCALEN = 0} at {@code app/cbl/COADM01C.cbl:82} diverts to the
     * sign-on screen first.
     *
     * @return {@code true} only when a communication area is present and its program context is the enter
     *     value
     */
    @JsonIgnore
    public boolean isEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether this is a re-entry into the transaction - {@code 88 CDEMO-PGM-REENTER VALUE 1}.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *     re-enter value
     */
    @JsonIgnore
    public boolean isReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }
}
