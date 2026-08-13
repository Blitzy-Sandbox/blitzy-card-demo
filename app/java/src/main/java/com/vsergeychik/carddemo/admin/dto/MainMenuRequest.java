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
 * The inbound REST payload of {@code GET /api/menu} - the main menu screen of the CardDemo online
 * application.
 *
 * <p>The client holds the value between calls, exactly as a CICS terminal held the COMMAREA.
 *
 * @param trnName {@code TRNNAMEI PIC X(4)}, {@code COMEN01.CPY:24}: the transaction identifier shown in the
 *     header
 * @param title01 {@code TITLE01I PIC X(40)}, {@code COMEN01.CPY:30}: the first title line
 * @param curDate {@code CURDATEI PIC X(8)}, {@code COMEN01.CPY:36}: the header date
 * @param pgmName {@code PGMNAMEI PIC X(8)}, {@code COMEN01.CPY:42}: the program name shown in the header
 * @param title02 {@code TITLE02I PIC X(40)}, {@code COMEN01.CPY:48}: the second title line
 * @param curTime {@code CURTIMEI PIC X(8)}, {@code COMEN01.CPY:54}: the header time
 * @param optn001 {@code OPTN001I PIC X(40)}, {@code COMEN01.CPY:60}: menu option line 1
 * @param optn002 {@code OPTN002I PIC X(40)}, {@code COMEN01.CPY:66}: menu option line 2
 * @param optn003 {@code OPTN003I PIC X(40)}, {@code COMEN01.CPY:72}: menu option line 3
 * @param optn004 {@code OPTN004I PIC X(40)}, {@code COMEN01.CPY:78}: menu option line 4
 * @param optn005 {@code OPTN005I PIC X(40)}, {@code COMEN01.CPY:84}: menu option line 5
 * @param optn006 {@code OPTN006I PIC X(40)}, {@code COMEN01.CPY:90}: menu option line 6
 * @param optn007 {@code OPTN007I PIC X(40)}, {@code COMEN01.CPY:96}: menu option line 7
 * @param optn008 {@code OPTN008I PIC X(40)}, {@code COMEN01.CPY:102}: menu option line 8
 * @param optn009 {@code OPTN009I PIC X(40)}, {@code COMEN01.CPY:108}: menu option line 9
 * @param optn010 {@code OPTN010I PIC X(40)}, {@code COMEN01.CPY:114}: menu option line 10
 * @param optn011 {@code OPTN011I PIC X(40)}, {@code COMEN01.CPY:120}: menu option line 11, present on the
 *     map and never populated by {@code COMEN01C}
 * @param optn012 {@code OPTN012I PIC X(40)}, {@code COMEN01.CPY:126}: menu option line 12, present on the
 *     map and never populated by {@code COMEN01C}
 * @param option {@code OPTIONI PIC X(2)}, {@code COMEN01.CPY:132}: the selected option, the only
 *     unprotected field on the screen, carried raw
 * @param errMsg {@code ERRMSGI PIC X(78)}, {@code COMEN01.CPY:138}: the error message line
 * @param navigationContext the inbound {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, or
 *     {@code null} when none was supplied, which is {@code app/cbl/COMEN01C.cbl:82}'s {@code IF EIBCALEN = 0}
 * @param eibAid the raw {@code EIBAID} byte that {@code app/cbl/COMEN01C.cbl:93-103} evaluates; resolving
 *     it to a key token is the shared program-function key resolver's job in the service
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record MainMenuRequest(
        @Size(max = MainMenuRequest.TRN_NAME_LENGTH) @JsonProperty("trnname") String trnName,
        @Size(max = MainMenuRequest.TITLE_LENGTH) String title01,
        @Size(max = MainMenuRequest.CUR_DATE_LENGTH) @JsonProperty("curdate") String curDate,
        @Size(max = MainMenuRequest.PGM_NAME_LENGTH) @JsonProperty("pgmname") String pgmName,
        @Size(max = MainMenuRequest.TITLE_LENGTH) String title02,
        @Size(max = MainMenuRequest.CUR_TIME_LENGTH) @JsonProperty("curtime") String curTime,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn001,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn002,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn003,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn004,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn005,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn006,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn007,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn008,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn009,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn010,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn011,
        @Size(max = MainMenuRequest.OPTION_LINE_LENGTH) String optn012,
        @Size(max = MainMenuRequest.OPTION_LENGTH) String option,
        @Size(max = MainMenuRequest.ERR_MSG_LENGTH) @JsonProperty("errmsg") String errMsg,
        NavigationContext navigationContext,
        byte eibAid) {
    /**
     * The CICS transaction identifier, {@code 'CM00'}.
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * The COBOL program name, {@code 'COMEN01C'}, from {@code app/cbl/COMEN01C.cbl:36} -
     * {@code 05 WS-PGMNAME PIC X(08) VALUE 'COMEN01C'} - and corroborated by
     * {@code app/csd/CARDDEMO.CSD:235}, {@code DEFINE PROGRAM(COMEN01C)}.
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * The BMS mapset name, {@code 'COMEN01'}, from {@code app/bms/COMEN01.bms:19} ({@code COMEN01 DFHMSD})
     * and {@code app/csd/CARDDEMO.CSD:133} ({@code DEFINE MAPSET(COMEN01)}).
     */
    public static final String MAPSET_NAME = "COMEN01";

    /**
     * The BMS map name, {@code 'COMEN1A'}, from {@code app/bms/COMEN01.bms:26}
     * ({@code COMEN1A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)}) and named on the {@code EXEC CICS SEND} at
     * {@code app/cbl/COMEN01C.cbl:190} as {@code MAP('COMEN1A')}.
     */
    public static final String MAP_NAME = "COMEN1A";

    /**
     * {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COMEN01.CPY:24}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, {@code app/cpy-bms/COMEN01.CPY:30}, and {@code TITLE02I PIC X(40)},
     * {@code app/cpy-bms/COMEN01.CPY:48}.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, {@code app/cpy-bms/COMEN01.CPY:36}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, {@code app/cpy-bms/COMEN01.CPY:42}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code app/cpy-bms/COMEN01.CPY:54}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code OPTN001I} through {@code OPTN012I}, each {@code PIC X(40)}, at {@code app/cpy-bms/COMEN01.CPY}
     * lines 60, 66, 72, 78, 84, 90, 96, 102, 108, 114, 120 and 126.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * How many menu option lines the map declares: {@value #OPTION_LINE_COUNT}.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * {@code OPTIONI PIC X(2)}, {@code app/cpy-bms/COMEN01.CPY:132}.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code app/cpy-bms/COMEN01.CPY:138}, and
     * {@code app/bms/COMEN01.bms:154-157} declares {@code LENGTH=78 COLOR=RED POS=(23,1)}.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Every {@code DFHMDF} field definition in {@code app/bms/COMEN01.bms}: {@value #SCREEN_FIELD_COUNT}.
     */
    public static final int SCREEN_FIELD_COUNT = 28;

    public static final int MAP_FIELD_COUNT = 20;

    /**
     * The {@code TIOAPFX} prefix at {@code app/cpy-bms/COMEN01.CPY:18} - {@code 02 FILLER PIC X(12)} -
     * present because the mapset is generated with {@code TIOAPFX=YES} ({@code app/bms/COMEN01.bms:24}).
     */
    public static final int TIOAPFX_FILLER_LENGTH = 12;

    /**
     * The per-field metadata overhead that precedes every {@code xxxI} item: {@link #FIELD_METADATA_LENGTH}
     * bytes.
     */
    public static final int FIELD_METADATA_LENGTH = 2 + 1 + 4;

    /**
     * The sum of the {@value #MAP_FIELD_COUNT} payload widths: {@link #SYMBOLIC_MAP_PAYLOAD_LENGTH} bytes.
     * 4 + 40 + 8 + 8 + 40 + 8 + (12 x 40) + 2 + 78.
     */
    public static final int SYMBOLIC_MAP_PAYLOAD_LENGTH = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + (OPTION_LINE_COUNT * OPTION_LINE_LENGTH)
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    /**
     * The width of the whole {@code 01 COMEN1AI} group item: {@link #SYMBOLIC_MAP_LENGTH} bytes.
     */
    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_FILLER_LENGTH
            + (MAP_FIELD_COUNT * FIELD_METADATA_LENGTH)
            + SYMBOLIC_MAP_PAYLOAD_LENGTH;

    /**
     * The twelve menu option lines as an unmodifiable list, in map order - index 0 is {@link #optn001()}
     * and index {@value #OPTION_LINE_COUNT} minus one is {@link #optn012()}.
     *
     * @return the twelve option lines in map order, never {@code null} and never resizable
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
     * One menu option line addressed by its one-based map position, so that {@code optionLine(1)} is
     * {@link #optn001()} and {@code optionLine(12)} is {@link #optn012()}.
     *
     * @param mapPosition the one-based option line position, from 1 to {@value #OPTION_LINE_COUNT}
     *     inclusive
     * @return that option line, which may be {@code null} if the payload did not supply it
     * @throws IndexOutOfBoundsException if {@code mapPosition} is below 1 or above
     *     {@value #OPTION_LINE_COUNT}
     */
    @JsonIgnore
    public String optionLine(int mapPosition) {
        if (mapPosition < 1 || mapPosition > OPTION_LINE_COUNT) {
            throw new IndexOutOfBoundsException("Menu option line " + mapPosition
                    + " is outside the 1.." + OPTION_LINE_COUNT + " lines declared by map "
                    + MAP_NAME + " of mapset " + MAPSET_NAME);
        }
        return optionLines().get(mapPosition - 1);
    }

    /**
     * Whether a communication area was supplied with this request.
     *
     * @return {@code true} when {@link #navigationContext()} is present
     */
    @JsonIgnore
    public boolean commareaPresent() {
        return navigationContext != null;
    }

    /**
     * Whether no communication area was supplied - {@code app/cbl/COMEN01C.cbl:82}'s
     * {@code IF EIBCALEN = 0} exactly, which is the route to the sign-on screen.
     *
     * @return {@code true} when {@link #navigationContext()} is absent
     */
    @JsonIgnore
    public boolean commareaAbsent() {
        return navigationContext == null;
    }

    /**
     * Whether this is a first entry to the transaction - {@code 88 CDEMO-PGM-ENTER VALUE 0} of
     * {@code app/cpy/COCOM01Y.cpy:30} holding on the carried communication area.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *     {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean enter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether the terminal is returning to a screen this transaction has already painted -
     * {@code 88 CDEMO-PGM-REENTER VALUE 1} of {@code app/cpy/COCOM01Y.cpy:31} holding on the carried
     * communication area.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *     {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean reenter() {
        return navigationContext != null && navigationContext.isReenter();
    }
}
