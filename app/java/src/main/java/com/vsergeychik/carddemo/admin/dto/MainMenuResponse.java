package com.vsergeychik.carddemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The outbound payload of {@code GET /api/menu} - the main menu screen of CICS transaction {@code CM00},
 * program {@code COMEN01C}, mapset {@code COMEN01}, map {@code COMEN1A}.
 *
 * <p>The screen shape is corroborated from two independent directions, and the two agree:
 * {@code app/cpy-bms/COMEN01.CPY} declares exactly 20 {@code xxxO} items.
 *
 * @param trnName {@code TRNNAMEO PIC X(4)}, CPY line 146
 * @param title01 {@code TITLE01O PIC X(40)}, CPY line 152
 * @param curDate {@code CURDATEO PIC X(8)}, CPY line 158
 * @param pgmName {@code PGMNAMEO PIC X(8)}, CPY line 164
 * @param title02 {@code TITLE02O PIC X(40)}, CPY line 170
 * @param curTime {@code CURTIMEO PIC X(8)}, CPY line 176
 * @param optn001 {@code OPTN001O PIC X(40)}, CPY line 182
 * @param optn002 {@code OPTN002O PIC X(40)}, CPY line 188
 * @param optn003 {@code OPTN003O PIC X(40)}, CPY line 194
 * @param optn004 {@code OPTN004O PIC X(40)}, CPY line 200
 * @param optn005 {@code OPTN005O PIC X(40)}, CPY line 206
 * @param optn006 {@code OPTN006O PIC X(40)}, CPY line 212
 * @param optn007 {@code OPTN007O PIC X(40)}, CPY line 218
 * @param optn008 {@code OPTN008O PIC X(40)}, CPY line 224
 * @param optn009 {@code OPTN009O PIC X(40)}, CPY line 230
 * @param optn010 {@code OPTN010O PIC X(40)}, CPY line 236
 * @param optn011 {@code OPTN011O PIC X(40)}, CPY line 242
 * @param optn012 {@code OPTN012O PIC X(40)}, CPY line 248
 * @param option {@code OPTIONO PIC X(2)}, CPY line 254
 * @param errMsg {@code ERRMSGO PIC X(78)}, CPY line 260
 * @param navigationContext the echoed {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}, 160 bytes
 *     and returned unaltered - or {@code null} on the one path that carries none
 * @param nextProgram the {@code EXEC CICS XCTL} target the client should call next
 * @param nextMapset the mapset owning the next map; {@link #MAPSET_NAME} for this screen
 * @param nextMap the next map to render; {@link #MAP_NAME} for this screen
 * @param errMsgColor not a JSON property: the {@code ERRMSGC} attribute byte, which
 *     {@code app/cpy-bms/COMEN01.CPY:256} declares as metadata alongside {@code ERRMSGP}, {@code ERRMSGH} and
 *     {@code ERRMSGV}
 * @param resetAllOutputFields not a JSON property: it names an action and corresponds to no copybook item
 */
public record MainMenuResponse(
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
        @JsonIgnore byte errMsgColor,
        @JsonIgnore boolean resetAllOutputFields) {
    /**
     * The transaction that reaches this screen: {@code DEFINE TRANSACTION(CM00)} at
     * {@code app/csd/CARDDEMO.CSD:399}, whose {@code PROGRAM(COMEN01C)} is on line 400.
     */
    public static final String TRANSACTION_ID = "CM00";

    /**
     * The COBOL program this payload is the output of: {@code DEFINE PROGRAM(COMEN01C)} at
     * {@code app/csd/CARDDEMO.CSD:235}.
     */
    public static final String PROGRAM_NAME = "COMEN01C";

    /**
     * The mapset: {@code COMEN01 DFHMSD} at {@code app/bms/COMEN01.bms:19}, registered as
     * {@code DEFINE MAPSET(COMEN01)} at {@code app/csd/CARDDEMO.CSD:133}.
     */
    public static final String MAPSET_NAME = "COMEN01";

    /**
     * The map within the mapset: {@code COMEN1A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)} at
     * {@code app/bms/COMEN01.bms:26}, and the map named by {@code EXEC CICS SEND MAP('COMEN1A')} at
     * {@code app/cbl/COMEN01C.cbl:190}.
     */
    public static final String MAP_NAME = "COMEN1A";

    /**
     * The sign-on program this screen falls back to when the user presses {@code PF3} or arrives with no
     * communication area.
     */
    public static final String SIGNON_PROGRAM = "COSGN00C";

    /**
     * Width of {@link #trnName()}: {@code TRNNAMEO PIC X(4)}, CPY line 146.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@link #title01()} and {@link #title02()}: {@code TITLE01O PIC X(40)} at CPY line 152 and
     * {@code TITLE02O PIC X(40)} at CPY line 170.
     */
    public static final int TITLE_LENGTH = 40;

    /**
     * Width of {@link #curDate()}: {@code CURDATEO PIC X(8)}, CPY line 158.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@link #pgmName()}: {@code PGMNAMEO PIC X(8)}, CPY line 164.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@link #curTime()}: {@code CURTIMEO PIC X(8)}, CPY line 176.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of each option line: {@code OPTN001O} through {@code OPTN012O}, every one {@code PIC X(40)}, at
     * CPY lines 182, 188, 194, 200, 206, 212, 218, 224, 230, 236, 242 and 248.
     */
    public static final int OPTION_LINE_LENGTH = 40;

    /**
     * How many option lines the screen has: twelve.
     */
    public static final int OPTION_LINE_COUNT = 12;

    /**
     * Width of {@link #option()}: {@code OPTIONO PIC X(2)}, CPY line 254.
     */
    public static final int OPTION_LENGTH = 2;

    /**
     * Width of {@link #errMsg()}: {@code ERRMSGO PIC X(78)}, CPY line 260.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Width of {@link #nextProgram()}, taken from {@code CDEMO-TO-PROGRAM PIC X(08)} in
     * {@code app/cpy/COCOM01Y.cpy} - the very field {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} reads at
     * {@code app/cbl/COMEN01C.cbl:176}.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@link #nextMapset()}, taken from {@code CDEMO-LAST-MAPSET PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:44}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Width of {@link #nextMap()}, taken from {@code CDEMO-LAST-MAP PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:43}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * How many payload fields this screen has: twenty.
     */
    public static final int SYMBOLIC_MAP_FIELD_COUNT = 20;

    /**
     * How many {@code DFHMDF} definitions {@code app/bms/COMEN01.bms} contains: twenty-eight.
     */
    public static final int MAPSET_FIELD_DEFINITION_COUNT = 28;

    /**
     * The leading {@code FILLER PIC X(12)} of the {@code COMEN1AO} group at
     * {@code app/cpy-bms/COMEN01.CPY:140}.
     */
    public static final int TIOAPFX_FILLER_LENGTH = 12;

    /**
     * The per-field attribute prefix that precedes every {@code xxxO} item: {@code FILLER PICTURE X(3)}
     * plus the colour, programmed-symbol, highlight and validation bytes {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV}.
     */
    public static final int ATTRIBUTE_PREFIX_LENGTH = 7;

    public static final int PAYLOAD_BYTES = TRN_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE_LENGTH
            + CUR_TIME_LENGTH
            + (OPTION_LINE_COUNT * OPTION_LINE_LENGTH)
            + OPTION_LENGTH
            + ERR_MSG_LENGTH;

    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_FILLER_LENGTH
            + (SYMBOLIC_MAP_FIELD_COUNT * ATTRIBUTE_PREFIX_LENGTH)
            + PAYLOAD_BYTES;

    /**
     * How many option lines the program actually fills: ten, from
     * {@code CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10} at {@code app/cpy/COMEN02Y.cpy:21}.
     */
    public static final int ACTIVE_OPTION_LINE_COUNT = 10;

    /**
     * The lowest option-line slot number, and the reason it is stated at all: COBOL {@code OCCURS} tables
     * are 1-based while Java arrays and lists are 0-based, which makes an off-by-one the most likely defect
     * anywhere near this table.
     */
    public static final int FIRST_OPTION_LINE_SLOT = 1;

    public static final int LAST_OPTION_LINE_SLOT = OPTION_LINE_COUNT;

    // The screen's declared defaults - the message colour the mapset asks for, and this screen's own mapset
    // and map - are therefore applied by the two entry points below rather than hidden inside the
    // constructor, so a caller can always see where a default came from.

    /**
     * A freshly initialised response carrying this screen's declared defaults and nothing else.
     *
     * @return the first-entry response, never {@code null}
     */
    public static MainMenuResponse initial() {
        return builder().resetAllOutputFields(true).build();
    }

    /**
     * A builder seeded with this screen's declared defaults: {@code DFHRED} for the message colour,
     * {@link #MAPSET_NAME} and {@link #MAP_NAME} for the navigation targets, and an initial communication
     * area.
     *
     * @return a new builder, never {@code null}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder pre-loaded with every component of this response, for deriving a modified copy.
     *
     * @return a new builder holding this response's values, never {@code null}
     */
    public Builder toBuilder() {
        Builder builder = new Builder()
                .trnName(trnName)
                .title01(title01)
                .curDate(curDate)
                .pgmName(pgmName)
                .title02(title02)
                .curTime(curTime)
                .option(option)
                .errMsg(errMsg)
                .navigationContext(navigationContext)
                .nextProgram(nextProgram)
                .nextMapset(nextMapset)
                .nextMap(nextMap)
                .errMsgColor(errMsgColor)
                .resetAllOutputFields(resetAllOutputFields);
        for (int slot = FIRST_OPTION_LINE_SLOT; slot <= LAST_OPTION_LINE_SLOT; slot++) {
            builder.optionLine(slot, optionLine(slot));
        }
        return builder;
    }

    // The twelve members are individually named components, exactly as the mapset names them; these two
    // accessors are convenience views over them and are excluded from JSON so the serialised form stays
    // precisely the twenty-six declared members.

    /**
     * The twelve option lines in slot order, slot {@value #FIRST_OPTION_LINE_SLOT} first, as an
     * unmodifiable list.
     *
     * @return an unmodifiable, {@value #OPTION_LINE_COUNT}-element view, never {@code null}
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
     * One option line, addressed by its 1-based COBOL slot number.
     *
     * <p>The numbering deliberately matches {@code CDEMO-MENU-OPT(WS-IDX)} in {@code app/cpy/COMEN02Y.cpy}
     * and the {@code EVALUATE WS-IDX} arms of {@code BUILD-MENU-OPTIONS}, so a slot number taken from the
     * COBOL is used here unchanged.
     *
     * @param slot the 1-based slot number, from {@value #FIRST_OPTION_LINE_SLOT} to
     *     {@value #OPTION_LINE_COUNT} inclusive
     * @return the option line held in that slot, or {@code null} if nothing has been written to it
     * @throws IllegalArgumentException if {@code slot} is outside
     *     {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    @JsonIgnore
    public String optionLine(int slot) {
        return switch (requireValidSlot(slot)) {
            case 1 -> optn001;
            case 2 -> optn002;
            case 3 -> optn003;
            case 4 -> optn004;
            case 5 -> optn005;
            case 6 -> optn006;
            case 7 -> optn007;
            case 8 -> optn008;
            case 9 -> optn009;
            case 10 -> optn010;
            case 11 -> optn011;
            default -> optn012;
        };
    }

    /**
     * Whether a slot is one the program can actually fill - that is, whether it is within
     * {@value #ACTIVE_OPTION_LINE_COUNT}.
     *
     * @param slot the 1-based slot number, from {@value #FIRST_OPTION_LINE_SLOT} to
     *     {@value #OPTION_LINE_COUNT} inclusive
     * @return {@code true} if the COBOL loop reaches this slot
     * @throws IllegalArgumentException if {@code slot} is outside
     *     {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    @JsonIgnore
    public boolean isPopulatedByProgram(int slot) {
        return requireValidSlot(slot) <= ACTIVE_OPTION_LINE_COUNT;
    }

    private static int requireValidSlot(int slot) {
        if (slot < FIRST_OPTION_LINE_SLOT || slot > LAST_OPTION_LINE_SLOT) {
            throw new IllegalArgumentException("option line slot must be "
                    + FIRST_OPTION_LINE_SLOT + ".." + LAST_OPTION_LINE_SLOT + " (1-based, as the COBOL "
                    + "OCCURS table numbers them) but was " + slot);
        }
        return slot;
    }

    /**
     * A copy carrying a different message line, standing in for
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COMEN1AO} at {@code app/cbl/COMEN01C.cbl:187}.
     *
     * <p>The {@code PIC X(80)} to {@code PIC X(78)} narrowing that {@code MOVE} performs is fixed-width
     * work and stays with the controller and its codec; this method neither truncates nor pads.
     *
     * @param newErrMsg the message text, at most {@value #ERR_MSG_LENGTH} characters
     * @return a new response differing only in {@link #errMsg()}, never {@code null}
     */
    public MainMenuResponse withErrMsg(String newErrMsg) {
        return toBuilder().errMsg(newErrMsg).build();
    }

    /**
     * A copy carrying a different message colour, standing in for
     * {@code MOVE DFHGREEN TO ERRMSGC OF COMEN1AO} at {@code app/cbl/COMEN01C.cbl:158}.
     *
     * @param newErrMsgColor the extended-colour byte, normally {@code BmsAttributes.DFHRED} or
     *     {@code BmsAttributes.DFHGREEN}
     * @return a new response differing only in {@link #errMsgColor()}, never {@code null}
     */
    public MainMenuResponse withErrMsgColor(byte newErrMsgColor) {
        return toBuilder().errMsgColor(newErrMsgColor).build();
    }

    /**
     * A copy naming a different transfer target, standing in for both {@code EXEC CICS XCTL} sites.
     *
     * @param newNextProgram the program the client should call next, at most {@link #NEXT_PROGRAM_LENGTH}
     *     characters
     * @return a new response differing only in {@link #nextProgram()}, never {@code null}
     */
    public MainMenuResponse withNextProgram(String newNextProgram) {
        return toBuilder().nextProgram(newNextProgram).build();
    }

    /**
     * A copy carrying a different communication area.
     *
     * @param newNavigationContext the communication area to echo, or {@code null} for the one path that
     *     carries none - the bare {@code XCTL} of {@code app/cbl/COMEN01C.cbl:175-177}
     * @return a new response differing only in {@link #navigationContext()}, never {@code null}
     */
    public MainMenuResponse withNavigationContext(NavigationContext newNavigationContext) {
        return toBuilder().navigationContext(newNavigationContext).build();
    }

    /**
     * A copy with one option line replaced, standing in for one {@code EVALUATE WS-IDX} arm of
     * {@code BUILD-MENU-OPTIONS} ({@code app/cbl/COMEN01C.cbl:248-275}).
     *
     * @param slot the 1-based slot number, from {@value #FIRST_OPTION_LINE_SLOT} to
     *     {@value #OPTION_LINE_COUNT} inclusive
     * @param text the option line, at most {@value #OPTION_LINE_LENGTH} characters
     * @return a new response differing only in that option line, never {@code null}
     * @throws IllegalArgumentException if {@code slot} is outside
     *     {@value #FIRST_OPTION_LINE_SLOT}..{@value #OPTION_LINE_COUNT}
     */
    public MainMenuResponse withOptionLine(int slot, String text) {
        return toBuilder().optionLine(slot, text).build();
    }

    public static final class Builder {
        private final String[] optionLines = newUnpaintedOptionLines();

        // The eight screen members default to the unpainted image at their declared width rather than to
        // null: MOVE LOW-VALUES TO COMEN1AO (app/cbl/COMEN01C.cbl:89) is what clears this map, and a
        // fixed-width screen field always has a width and therefore always has an image.
        private String trnName = ScreenFieldImage.unpainted(TRN_NAME_LENGTH);
        private String title01 = ScreenFieldImage.unpainted(TITLE_LENGTH);
        private String curDate = ScreenFieldImage.unpainted(CUR_DATE_LENGTH);
        private String pgmName = ScreenFieldImage.unpainted(PGM_NAME_LENGTH);
        private String title02 = ScreenFieldImage.unpainted(TITLE_LENGTH);
        private String curTime = ScreenFieldImage.unpainted(CUR_TIME_LENGTH);
        private String option = ScreenFieldImage.unpainted(OPTION_LENGTH);
        private String errMsg = ScreenFieldImage.unpainted(ERR_MSG_LENGTH);

        private NavigationContext navigationContext = NavigationContext.empty();

        private String nextProgram;

        private String nextMapset = MAPSET_NAME;

        private String nextMap = MAP_NAME;

        private byte errMsgColor = BmsAttributes.DFHRED;

        private boolean resetAllOutputFields;

        private Builder() {
        }

        /**
         * Sets {@code TRNNAMEO PIC X(4)}, CPY line 146.
         *
         * @param value the transaction identifier, normally {@value MainMenuResponse#TRANSACTION_ID}
         * @return this builder
         */
        public Builder trnName(String value) {
            this.trnName = value;
            return this;
        }

        /**
         * Sets {@code TITLE01O PIC X(40)}, CPY line 152.
         *
         * @param value the first title line, from {@code CCDA-TITLE01}
         * @return this builder
         */
        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        /**
         * Sets {@code CURDATEO PIC X(8)}, CPY line 158.
         *
         * @param value the current date rendered {@code MM/DD/YY}
         * @return this builder
         */
        public Builder curDate(String value) {
            this.curDate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEO PIC X(8)}, CPY line 164.
         *
         * @param value the program name, normally {@value MainMenuResponse#PROGRAM_NAME}
         * @return this builder
         */
        public Builder pgmName(String value) {
            this.pgmName = value;
            return this;
        }

        /**
         * Sets {@code TITLE02O PIC X(40)}, CPY line 170.
         *
         * @param value the second title line, from {@code CCDA-TITLE02}
         * @return this builder
         */
        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEO PIC X(8)}, CPY line 176.
         *
         * @param value the current time rendered {@code HH:MM:SS}
         * @return this builder
         */
        public Builder curTime(String value) {
            this.curTime = value;
            return this;
        }

        /**
         * Sets one option line by its 1-based COBOL slot number.
         *
         * @param slot the 1-based slot number, {@value MainMenuResponse#FIRST_OPTION_LINE_SLOT} to
         *     {@value MainMenuResponse#OPTION_LINE_COUNT} inclusive
         * @param value the option line text
         * @return this builder
         * @throws IllegalArgumentException if {@code slot} is out of range
         */
        public Builder optionLine(int slot, String value) {
            this.optionLines[requireValidSlot(slot) - FIRST_OPTION_LINE_SLOT] = value;
            return this;
        }

        /**
         * Sets {@code OPTIONO PIC X(2)}, CPY line 254.
         *
         * @param value the selected option, zero-filled to two characters, for example {@code "01"} or
         *     {@code "10"}
         * @return this builder
         */
        public Builder option(String value) {
            this.option = value;
            return this;
        }

        /**
         * Sets {@code ERRMSGO PIC X(78)}, CPY line 260.
         *
         * @param value the message text, stored exactly as given
         * @return this builder
         */
        public Builder errMsg(String value) {
            this.errMsg = value;
            return this;
        }

        /**
         * Sets the communication area to echo, stored by reference and never altered - {@code null}
         * included, because on one path {@code null} is the answer.
         *
         * @param value the communication area, or {@code null} when the transfer carries none
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Sets the {@code XCTL} target the client should call next.
         *
         * @param value the program name, at most {@value MainMenuResponse#NEXT_PROGRAM_LENGTH} characters
         * @return this builder
         */
        public Builder nextProgram(String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the mapset owning the next map.
         *
         * @param value the mapset name, at most {@value MainMenuResponse#NEXT_MAPSET_LENGTH} characters
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
         * Sets the message line's extended-colour byte.
         *
         * @param value the colour byte, normally {@code BmsAttributes.DFHRED} or
         *     {@code BmsAttributes.DFHGREEN}
         * @return this builder
         */
        public Builder errMsgColor(byte value) {
            this.errMsgColor = value;
            return this;
        }

        /**
         * Sets whether the client should clear every output field before painting.
         *
         * @param value {@code true} to mirror {@code MOVE LOW-VALUES TO COMEN1AO}
         *     ({@code app/cbl/COMEN01C.cbl:89})
         * @return this builder
         */
        public Builder resetAllOutputFields(boolean value) {
            this.resetAllOutputFields = value;
            return this;
        }

        public MainMenuResponse build() {
            return new MainMenuResponse(trnName,
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
                    errMsgColor,
                    resetAllOutputFields);
        }

        private static String[] newUnpaintedOptionLines() {
            String[] lines = new String[OPTION_LINE_COUNT];
            Arrays.fill(lines, ScreenFieldImage.unpainted(OPTION_LINE_LENGTH));
            return lines;
        }
    }
}
