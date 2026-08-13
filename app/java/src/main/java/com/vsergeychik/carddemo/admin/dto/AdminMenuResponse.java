package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outbound payload of {@code GET /api/admin/menu}: a field-for-field projection of the output view of
 * the admin menu screen.
 *
 * <p>{@code diff app/cpy-bms/COADM01.CPY app/cpy-bms/COMEN01.CPY} reports differences at exactly two lines
 * - 17 ({@code 01 COADM1AI} versus {@code 01 COMEN1AI}) and 139 ({@code 01 COADM1AO REDEFINES COADM1AI}
 * versus {@code 01 COMEN1AO REDEFINES COMEN1AI}).
 *
 * @param trnName {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COADM01.CPY:146}
 * @param title01 {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:152}
 * @param curDate {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:158}
 * @param pgmName {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:164}
 * @param title02 {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:170}
 * @param curTime {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:176}
 * @param optn001 {@code OPTN001O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:182}
 * @param optn002 {@code OPTN002O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:188}
 * @param optn003 {@code OPTN003O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:194}
 * @param optn004 {@code OPTN004O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:200}
 * @param optn005 {@code OPTN005O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:206}
 * @param optn006 {@code OPTN006O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:212}
 * @param optn007 {@code OPTN007O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:218}
 * @param optn008 {@code OPTN008O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:224}
 * @param optn009 {@code OPTN009O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:230}
 * @param optn010 {@code OPTN010O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:236}
 * @param optn011 {@code OPTN011O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:242}
 * @param optn012 {@code OPTN012O PIC X(40)}, {@code app/cpy-bms/COADM01.CPY:248}
 * @param option {@code OPTIONO PIC X(2)}, {@code app/cpy-bms/COADM01.CPY:254}
 * @param errMsg {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COADM01.CPY:260}
 * @param navigationContext the echoed {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, returned
 *     to the client so no conversation state is held on the server - or {@code null} on the one path that
 *     carries none
 * @param nextProgram the {@code EXEC CICS XCTL} target, {@code PIC X(08)} wide like
 *     {@code CDEMO-TO-PROGRAM}: an option target from {@code app/cpy/COADM02Y.cpy}, or {@link #SIGNON_PROGRAM}
 *     on the return route
 * @param nextMapset the mapset the client should render, {@code X(7)} wide like {@code CDEMO-LAST-MAPSET};
 *     {@link #MAPSET_NAME} for this screen
 * @param nextMap the map the client should render, {@code X(7)} wide like {@code CDEMO-LAST-MAP};
 *     {@link #MAP_NAME} for this screen
 * @param messageColour the BMS colour attribute for the message line: presentation metadata,
 *     {@link BmsAttributes#DFHRED} by map declaration and {@link BmsAttributes#DFHGREEN} on the coming-soon
 *     path
 * @param resetAllOutputFields whether the client should clear every output field before painting, mirroring
 *     {@code MOVE LOW-VALUES TO COADM1AO} at {@code app/cbl/COADM01C.cbl:89}
 */
public record AdminMenuResponse(
        @Size(max = TRN_NAME_LENGTH) @JsonProperty("trnname") String trnName,
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
        @Size(max = NEXT_PROGRAM_LENGTH) String nextProgram,
        @Size(max = NEXT_MAPSET_LENGTH) String nextMapset,
        @Size(max = NEXT_MAP_LENGTH) String nextMap,
        @JsonIgnore byte messageColour,
        @JsonIgnore boolean resetAllOutputFields) {
    // These four literals are what the CSD, the program and the mapset agree on, and they are stated once
    // here so no caller has to retype a name the parity differ will compare.

    /**
     * The CICS transaction identifier of the admin menu, {@code CA00}.
     */
    public static final String TRANSACTION_ID = "CA00";

    /**
     * The COBOL program this screen is the response of, {@code COADM01C}.
     */
    public static final String PROGRAM_NAME = "COADM01C";

    /**
     * The BMS mapset name, {@code COADM01} - the {@code DFHMSD} of {@code app/bms/COADM01.bms} and the
     * {@code MAPSET('COADM01')} operand of the {@code EXEC CICS SEND} at
     * {@code app/cbl/COADM01C.cbl:180-186}.
     */
    public static final String MAPSET_NAME = "COADM01";

    /**
     * The BMS map name, {@code COADM1A} - the {@code DFHMDI} label of {@code app/bms/COADM01.bms} and the
     * {@code MAP('COADM1A')} operand of the {@code EXEC CICS SEND} at {@code app/cbl/COADM01C.cbl:180-186}.
     */
    public static final String MAP_NAME = "COADM1A";

    /**
     * The sign-on program, {@code COSGN00C}: the {@code XCTL} target of the return route.
     *
     * <p>The four option targets are deliberately not duplicated here - they live in
     * {@code app/cpy/COADM02Y.cpy} and in the model type that translates it, and a second copy could drift
     * from the first.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Rows on the 3270 screen: the {@code SIZE=(24,80)} of {@code app/bms/COADM01.bms} {@code DFHMDI}.
     */
    public static final int SCREEN_ROWS = 24;

    public static final int SCREEN_COLUMNS = 80;

    /**
     * Total {@code DFHMDF} field definitions in {@code app/bms/COADM01.bms}: 28.
     */
    public static final int MAPSET_FIELD_COUNT = 28;

    /**
     * {@code DFHMDF} definitions carrying a name label, and therefore reaching the symbolic map: 20.
     */
    public static final int NAMED_MAP_FIELD_COUNT = 20;

    /**
     * Unnamed {@code DFHMDF} literals - screen furniture that never reaches the symbolic map and is
     * therefore never payload: {@value #MAPSET_FIELD_COUNT} minus {@value #NAMED_MAP_FIELD_COUNT} = 8.
     */
    public static final int UNNAMED_MAP_FIELD_COUNT = MAPSET_FIELD_COUNT - NAMED_MAP_FIELD_COUNT;

    /**
     * The {@code TIOAPFX} prefix that opens the symbolic map: {@code 02 FILLER PIC X(12).} at
     * {@code app/cpy-bms/COADM01.CPY:140}, present because the mapset declares {@code TIOAPFX=YES}.
     */
    public static final int TIOAPFX_FILLER_LENGTH = 12;

    /**
     * The reserved span that opens each field's attribute prefix in the output view:
     * {@code 02 FILLER PICTURE X(3).}, for instance at {@code app/cpy-bms/COADM01.CPY:141}.
     */
    public static final int ATTRIBUTE_FILLER_LENGTH = 3;

    /**
     * Bytes preceding every {@code xxxO} item: {@value #ATTRIBUTE_FILLER_LENGTH} of {@code FILLER} plus the
     * four one-byte attributes {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV}, so 7.
     */
    public static final int ATTRIBUTE_PREFIX_LENGTH = ATTRIBUTE_FILLER_LENGTH + 4;

    public static final int PAYLOAD_FIELD_COUNT = NAMED_MAP_FIELD_COUNT;

    /**
     * {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COADM01.CPY:146}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)} at {@code app/cpy-bms/COADM01.CPY:152} and {@code TITLE02O PIC X(40)} at
     * {@code app/cpy-bms/COADM01.CPY:170} - one constant because the two are declared identically, and
     * because {@code CCDA-TITLE01} and {@code CCDA-TITLE02} in {@code app/cpy/COTTL01Y.cpy} are both
     * {@code PIC X(40)} on the sending side.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:158}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:164}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COADM01.CPY:176}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code OPTN001O} through {@code OPTN012O}, each {@code PIC X(40)}, at {@code app/cpy-bms/COADM01.CPY}
     * lines 182, 188, 194, 200, 206, 212, 218, 224, 230, 236, 242 and 248, and each {@code LENGTH=40} in
     * the mapset.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    public static final int OPTION_LINE_COUNT = 12;

    /**
     * {@code OPTIONO PIC X(2)}, {@code app/cpy-bms/COADM01.CPY:254}, and {@code LENGTH=2} with
     * {@code JUSTIFY=(RIGHT,ZERO)} in the mapset.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * {@code ERRMSGO PIC X(78)}, {@code app/cpy-bms/COADM01.CPY:260}, and {@code LENGTH=78} at
     * {@code POS=(23,1)} in the mapset. 78, not the 80 of {@code WS-MESSAGE} and not the 50 of
     * {@code CCDA-MSG-INVALID-KEY}.
     */
    public static final int ERR_MSG_LENGTH = 78;

    public static final int PAYLOAD_DATA_LENGTH = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + OPTION_LINE_COUNT * OPTION_LINE_LENGTH
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_FILLER_LENGTH
            + PAYLOAD_FIELD_COUNT * ATTRIBUTE_PREFIX_LENGTH
            + PAYLOAD_DATA_LENGTH;

    /**
     * Width of {@link #nextProgram()}: 8, from {@code CDEMO-TO-PROGRAM PIC X(08)} of
     * {@code app/cpy/COCOM01Y.cpy}, which is the field {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} reads at
     * {@code app/cbl/COADM01C.cbl:166}.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@link #nextMapset()}: 7, from {@code CDEMO-LAST-MAPSET PIC X(7)}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Width of {@link #nextMap()}: 7, from {@code CDEMO-LAST-MAP PIC X(7)}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    // Symbolic-map names, spelled EXACTLY as the copybook spells them, trailing O and all. These are the
    // names a field-for-field parity diff keys on, so a name tidied into Java style here would make a
    // genuine difference invisible there.

    /**
     * Copybook name of {@link #trnName()}: {@code TRNNAMEO}, {@code app/cpy-bms/COADM01.CPY:146}.
     */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /**
     * Copybook name of {@link #title01()}: {@code TITLE01O}, {@code app/cpy-bms/COADM01.CPY:152}.
     */
    public static final String TITLE01_FIELD = "TITLE01O";

    /**
     * Copybook name of {@link #curDate()}: {@code CURDATEO}, {@code app/cpy-bms/COADM01.CPY:158}.
     */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /**
     * Copybook name of {@link #pgmName()}: {@code PGMNAMEO}, {@code app/cpy-bms/COADM01.CPY:164}.
     */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /**
     * Copybook name of {@link #title02()}: {@code TITLE02O}, {@code app/cpy-bms/COADM01.CPY:170}.
     */
    public static final String TITLE02_FIELD = "TITLE02O";

    /**
     * Copybook name of {@link #curTime()}: {@code CURTIMEO}, {@code app/cpy-bms/COADM01.CPY:176}.
     */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /**
     * Copybook name of {@link #option()}: {@code OPTIONO}, {@code app/cpy-bms/COADM01.CPY:254}.
     */
    public static final String OPTION_FIELD = "OPTIONO";

    /**
     * Copybook name of {@link #errMsg()}: {@code ERRMSGO}, {@code app/cpy-bms/COADM01.CPY:260}.
     */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    public static final List<String> OPTION_LINE_FIELDS = List.of("OPTN001O",
            "OPTN002O",
            "OPTN003O",
            "OPTN004O",
            "OPTN005O",
            "OPTN006O",
            "OPTN007O",
            "OPTN008O",
            "OPTN009O",
            "OPTN010O",
            "OPTN011O",
            "OPTN012O");

    /**
     * All {@link #PAYLOAD_FIELD_COUNT} symbolic-map names in map order, from {@code TRNNAMEO} at
     * {@code app/cpy-bms/COADM01.CPY:146} to {@code ERRMSGO} at {@code app/cpy-bms/COADM01.CPY:260}.
     */
    public static final List<String> PAYLOAD_FIELDS = buildPayloadFields();

    public static final List<Integer> PAYLOAD_FIELD_LENGTHS = buildPayloadFieldLengths();

    /**
     * Canonical constructor, normalising absent screen members to their COBOL-initial values.
     */
    public AdminMenuResponse {
        trnName = orUnpainted(trnName, TRN_NAME_LENGTH);
        title01 = orUnpainted(title01, TITLE_LENGTH);
        curDate = orUnpainted(curDate, CUR_DATE_LENGTH);
        pgmName = orUnpainted(pgmName, PGM_NAME_LENGTH);
        title02 = orUnpainted(title02, TITLE_LENGTH);
        curTime = orUnpainted(curTime, CUR_TIME_LENGTH);
        optn001 = orUnpainted(optn001, OPTION_LINE_LENGTH);
        optn002 = orUnpainted(optn002, OPTION_LINE_LENGTH);
        optn003 = orUnpainted(optn003, OPTION_LINE_LENGTH);
        optn004 = orUnpainted(optn004, OPTION_LINE_LENGTH);
        optn005 = orUnpainted(optn005, OPTION_LINE_LENGTH);
        optn006 = orUnpainted(optn006, OPTION_LINE_LENGTH);
        optn007 = orUnpainted(optn007, OPTION_LINE_LENGTH);
        optn008 = orUnpainted(optn008, OPTION_LINE_LENGTH);
        optn009 = orUnpainted(optn009, OPTION_LINE_LENGTH);
        optn010 = orUnpainted(optn010, OPTION_LINE_LENGTH);
        optn011 = orUnpainted(optn011, OPTION_LINE_LENGTH);
        optn012 = orUnpainted(optn012, OPTION_LINE_LENGTH);
        option = orUnpainted(option, OPTION_LENGTH);
        errMsg = orUnpainted(errMsg, ERR_MSG_LENGTH);
        nextProgram = orSpaces(nextProgram, NEXT_PROGRAM_LENGTH);
        nextMapset = orSpaces(nextMapset, NEXT_MAPSET_LENGTH);
        nextMap = orSpaces(nextMap, NEXT_MAP_LENGTH);
    }

    /**
     * The screen as {@code COADM01C} leaves it immediately after {@code MOVE LOW-VALUES TO COADM1AO} at
     * {@code app/cbl/COADM01C.cbl:89} and before {@code POPULATE-HEADER-INFO} runs: every screen field
     * carrying the unpainted image at its declared width, the communication area at its own initial value,
     * the message colour at the map's declared {@link BmsAttributes#DFHRED}, and the screen to render named
     * as.
     *
     * @return the unpainted initial response, never {@code null}
     */
    public static AdminMenuResponse empty() {
        return new AdminMenuResponse(ScreenFieldImage.unpainted(TRN_NAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE_LENGTH),
                ScreenFieldImage.unpainted(CUR_DATE_LENGTH),
                ScreenFieldImage.unpainted(PGM_NAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE_LENGTH),
                ScreenFieldImage.unpainted(CUR_TIME_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LINE_LENGTH),
                ScreenFieldImage.unpainted(OPTION_LENGTH),
                ScreenFieldImage.unpainted(ERR_MSG_LENGTH),
                NavigationContext.empty(),
                spaces(NEXT_PROGRAM_LENGTH),
                MAPSET_NAME,
                MAP_NAME,
                BmsAttributes.DFHRED,
                false);
    }

    /**
     * This screen's presentation metadata, in the shared envelope every online response publishes.
     *
     * @return the metadata; never {@code null}
     */
    @JsonIgnore
    public ScreenMetadata screenMetadata() {
        return ScreenMetadata.of(null, messageColour, resetAllOutputFields);
    }

    /**
     * The twelve menu lines in map order, positionally aligned with {@link #OPTION_LINE_FIELDS}.
     *
     * @return an unmodifiable list of exactly {@value #OPTION_LINE_COUNT} lines, never {@code null} and
     *     never containing {@code null}
     */
    @JsonIgnore
    public List<String> optionLines() {
        return List.of(optn001,
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
                optn012);
    }

    /**
     * One menu line by its COBOL subscript, 1 through {@value #OPTION_LINE_COUNT}.
     *
     * @param cobolSubscript the one-based line number, as {@code WS-IDX} counts it
     * @return that menu line, space-filled if it has never been written
     * @throws IndexOutOfBoundsException if {@code cobolSubscript} is outside 1 to
     *     {@value #OPTION_LINE_COUNT}
     */
    @JsonIgnore
    public String optionLine(final int cobolSubscript) {
        return optionLines().get(zeroBasedIndex(cobolSubscript));
    }

    /**
     * The {@link #PAYLOAD_FIELD_COUNT} screen-field values in map order, positionally aligned with
     * {@link #PAYLOAD_FIELDS} and {@link #PAYLOAD_FIELD_LENGTHS}.
     *
     * @return an unmodifiable list of exactly {@link #PAYLOAD_FIELD_COUNT} values, never {@code null} and
     *     never containing {@code null}
     */
    @JsonIgnore
    public List<String> payloadValues() {
        final List<String> values = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        values.add(trnName);
        values.add(title01);
        values.add(curDate);
        values.add(pgmName);
        values.add(title02);
        values.add(curTime);
        values.addAll(optionLines());
        values.add(option);
        values.add(errMsg);
        return List.copyOf(values);
    }

    /**
     * The message colour as its IBM {@code DFHBMSCA} mnemonic, for example {@code DFHRED} or
     * {@code DFHGREEN}.
     *
     * @return the mnemonic, or an unrecognised-value description if the byte is not a BMS colour
     */
    @JsonIgnore
    public String messageColourMnemonic() {
        return BmsAttributes.colourMnemonic(messageColour);
    }

    /**
     * The message colour as two hexadecimal digits, for example {@code F2} for
     * {@link BmsAttributes#DFHRED}.
     *
     * @return the unsigned hexadecimal rendering
     */
    @JsonIgnore
    public String messageColourHex() {
        return BmsAttributes.toHex(messageColour);
    }

    /**
     * A builder pre-loaded with every member of this instance, for producing a modified copy.
     *
     * @return a fresh builder, never {@code null}
     */
    public Builder toBuilder() {
        final Builder builder = new Builder();
        builder.trnName = trnName;
        builder.title01 = title01;
        builder.curDate = curDate;
        builder.pgmName = pgmName;
        builder.title02 = title02;
        builder.curTime = curTime;
        builder.optionLines = optionLines().toArray(new String[0]);
        builder.option = option;
        builder.errMsg = errMsg;
        builder.navigationContext = navigationContext;
        builder.nextProgram = nextProgram;
        builder.nextMapset = nextMapset;
        builder.nextMap = nextMap;
        builder.messageColour = messageColour;
        builder.resetAllOutputFields = resetAllOutputFields;
        return builder;
    }

    /**
     * A builder seeded exactly as {@link #empty()} is: space-filled text, the initial communication area,
     * {@link BmsAttributes#DFHRED}, this screen's mapset and map, and no repaint signal.
     *
     * @return a fresh builder at the initial state, never {@code null}
     */
    public static Builder builder() {
        return empty().toBuilder();
    }

    private static String spaces(final int width) {
        return " ".repeat(width);
    }

    private static String orSpaces(final String value, final int width) {
        return value == null ? spaces(width) : value;
    }

    private static String orUnpainted(final String value, final int width) {
        return value == null ? ScreenFieldImage.unpainted(width) : value;
    }

    private static int zeroBasedIndex(final int cobolSubscript) {
        if (cobolSubscript < 1 || cobolSubscript > OPTION_LINE_COUNT) {
            throw new IndexOutOfBoundsException("COBOL menu-line subscript must be 1 to "
                    + OPTION_LINE_COUNT + " (the twelve OPTN0nnO fields declared by "
                    + MAPSET_NAME + "), but was " + cobolSubscript);
        }
        return cobolSubscript - 1;
    }

    private static List<String> buildPayloadFields() {
        final List<String> names = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        names.add(TRN_NAME_FIELD);
        names.add(TITLE01_FIELD);
        names.add(CUR_DATE_FIELD);
        names.add(PGM_NAME_FIELD);
        names.add(TITLE02_FIELD);
        names.add(CUR_TIME_FIELD);
        names.addAll(OPTION_LINE_FIELDS);
        names.add(OPTION_FIELD);
        names.add(ERR_MSG_FIELD);
        return List.copyOf(names);
    }

    private static List<Integer> buildPayloadFieldLengths() {
        final List<Integer> widths = new ArrayList<>(PAYLOAD_FIELD_COUNT);
        widths.add(TRN_NAME_LENGTH);
        widths.add(TITLE_LENGTH);
        widths.add(CUR_DATE_LENGTH);
        widths.add(PGM_NAME_LENGTH);
        widths.add(TITLE_LENGTH);
        widths.add(CUR_TIME_LENGTH);
        widths.addAll(Collections.nCopies(OPTION_LINE_COUNT, OPTION_LINE_LENGTH));
        widths.add(OPTION_LENGTH);
        widths.add(ERR_MSG_LENGTH);
        return List.copyOf(widths);
    }

    /**
     * Assembles an {@link AdminMenuResponse} without a twenty-six-argument constructor call at every site,
     * and produces a modified copy of an existing one through {@link #toBuilder()}.
     *
     * <p>Annotation-processor libraries that generate accessors, builders or mappers are outside the closed
     * dependency set of this migration, and a generated builder would put the copybook-to-member
     * correspondence behind a code generator at exactly the point a reviewer needs to read it.
     */
    public static final class Builder {
        private String trnName;
        private String title01;
        private String curDate;
        private String pgmName;
        private String title02;
        private String curTime;
        private String[] optionLines = new String[OPTION_LINE_COUNT];
        private String option;
        private String errMsg;
        private NavigationContext navigationContext;
        private String nextProgram;
        private String nextMapset;
        private String nextMap;
        private byte messageColour = BmsAttributes.DFHRED;
        private boolean resetAllOutputFields;

        private Builder() {
        }

        /**
         * Sets {@code TRNNAMEO} - {@value AdminMenuResponse#TRN_NAME_FIELD}, {@code PIC X(4)}.
         *
         * @param value the transaction identifier, or {@code null} for spaces
         * @return this builder
         */
        public Builder trnName(final String value) {
            this.trnName = value;
            return this;
        }

        /**
         * Sets {@code TITLE01O} - {@value AdminMenuResponse#TITLE01_FIELD}, {@code PIC X(40)}.
         *
         * @param value the first title line, or {@code null} for spaces
         * @return this builder
         */
        public Builder title01(final String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets {@code CURDATEO} - {@value AdminMenuResponse#CUR_DATE_FIELD}, {@code PIC X(8)}.
         *
         * @param value the {@code MM/DD/YY} header date, or {@code null} for spaces
         * @return this builder
         */
        public Builder curDate(final String value) {
            this.curDate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEO} - {@value AdminMenuResponse#PGM_NAME_FIELD}, {@code PIC X(8)}.
         *
         * @param value the program name, or {@code null} for spaces
         * @return this builder
         */
        public Builder pgmName(final String value) {
            this.pgmName = value;
            return this;
        }

        /**
         * Sets {@code TITLE02O} - {@value AdminMenuResponse#TITLE02_FIELD}, {@code PIC X(40)}.
         *
         * @param value the second title line, or {@code null} for spaces
         * @return this builder
         */
        public Builder title02(final String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEO} - {@value AdminMenuResponse#CUR_TIME_FIELD}, {@code PIC X(8)}.
         *
         * @param value the {@code HH:MM:SS} header time, or {@code null} for spaces
         * @return this builder
         */
        public Builder curTime(final String value) {
            this.curTime = value;
            return this;
        }

        /**
         * Sets one menu line by its COBOL subscript, 1 through {@value AdminMenuResponse#OPTION_LINE_COUNT}
         * - the shape {@code BUILD-MENU-OPTIONS} works in, where {@code WS-IDX} counts from 1.
         *
         * @param cobolSubscript the one-based line number
         * @param value the composed {@code PIC X(40)} line, or {@code null} for spaces
         * @return this builder
         * @throws IndexOutOfBoundsException if the subscript is outside 1 to
         *     {@value AdminMenuResponse#OPTION_LINE_COUNT}
         */
        public Builder optionLine(final int cobolSubscript, final String value) {
            this.optionLines[zeroBasedIndex(cobolSubscript)] = value;
            return this;
        }

        /**
         * Sets {@code OPTN001O} - menu line 1 at {@code POS=(6,20)}, written {@code WHEN 1}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn001(final String value) {
            return optionLine(1, value);
        }

        /**
         * Sets {@code OPTN002O} - menu line 2 at {@code POS=(7,20)}, written {@code WHEN 2}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn002(final String value) {
            return optionLine(2, value);
        }

        /**
         * Sets {@code OPTN003O} - menu line 3 at {@code POS=(8,20)}, written {@code WHEN 3}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn003(final String value) {
            return optionLine(3, value);
        }

        /**
         * Sets {@code OPTN004O} - menu line 4 at {@code POS=(9,20)}, written {@code WHEN 4}.
         *
         * @param value the composed line, or {@code null} for spaces
         * @return this builder
         */
        public Builder optn004(final String value) {
            return optionLine(4, value);
        }

        public Builder optn005(final String value) {
            return optionLine(5, value);
        }

        public Builder optn006(final String value) {
            return optionLine(6, value);
        }

        public Builder optn007(final String value) {
            return optionLine(7, value);
        }

        public Builder optn008(final String value) {
            return optionLine(8, value);
        }

        public Builder optn009(final String value) {
            return optionLine(9, value);
        }

        public Builder optn010(final String value) {
            return optionLine(10, value);
        }

        public Builder optn011(final String value) {
            return optionLine(11, value);
        }

        public Builder optn012(final String value) {
            return optionLine(12, value);
        }

        /**
         * Sets {@code OPTIONO} - {@value AdminMenuResponse#OPTION_FIELD}, {@code PIC X(2)}: the zero-filled
         * echo written by {@code app/cbl/COADM01C.cbl:125}.
         *
         * @param value the two-character option echo, or {@code null} for spaces
         * @return this builder
         */
        public Builder option(final String value) {
            this.option = value;
            return this;
        }

        /**
         * Sets {@code ERRMSGO} - {@value AdminMenuResponse#ERR_MSG_FIELD}, {@code PIC X(78)}.
         *
         * @param value the already-narrowed message, or {@code null} for spaces
         * @return this builder
         */
        public Builder errMsg(final String value) {
            this.errMsg = value;
            return this;
        }

        /**
         * Sets the echoed {@code CARDDEMO-COMMAREA}.
         *
         * @param value the communication area, or {@code null} for {@link NavigationContext#empty()}
         * @return this builder
         */
        public Builder navigationContext(final NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Sets the {@code EXEC CICS XCTL} target the client should call next.
         *
         * @param value an option target from {@code app/cpy/COADM02Y.cpy}, or
         *     {@value AdminMenuResponse#SIGNON_PROGRAM} on the return route, or {@code null} for spaces
         * @return this builder
         */
        public Builder nextProgram(final String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the mapset the client should render.
         *
         * @param value the mapset name, or {@code null} for spaces
         * @return this builder
         */
        public Builder nextMapset(final String value) {
            this.nextMapset = value;
            return this;
        }

        public Builder nextMap(final String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the message-line colour attribute - {@link BmsAttributes#DFHRED} as the map declares it, or
         * {@link BmsAttributes#DFHGREEN} on the coming-soon path of {@code app/cbl/COADM01C.cbl:148}.
         *
         * @param value the BMS colour attribute byte
         * @return this builder
         */
        public Builder messageColour(final byte value) {
            this.messageColour = value;
            return this;
        }

        /**
         * Sets the repaint signal mirroring {@code MOVE LOW-VALUES TO COADM1AO} at
         * {@code app/cbl/COADM01C.cbl:89}.
         *
         * @param value whether the client should clear every output field before painting
         * @return this builder
         */
        public Builder resetAllOutputFields(final boolean value) {
            this.resetAllOutputFields = value;
            return this;
        }

        public AdminMenuResponse build() {
            return new AdminMenuResponse(trnName,
                    title01,
                    curDate,
                    pgmName,
                    title02,
                    curTime,
                    optionLines[0],
                    optionLines[1],
                    optionLines[2],
                    optionLines[3],
                    optionLines[4],
                    optionLines[5],
                    optionLines[6],
                    optionLines[7],
                    optionLines[8],
                    optionLines[9],
                    optionLines[10],
                    optionLines[11],
                    option,
                    errMsg,
                    navigationContext,
                    nextProgram,
                    nextMapset,
                    nextMap,
                    messageColour,
                    resetAllOutputFields);
        }
    }
}
