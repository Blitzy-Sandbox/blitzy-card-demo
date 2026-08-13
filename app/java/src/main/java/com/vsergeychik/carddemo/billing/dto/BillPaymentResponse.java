package com.vsergeychik.carddemo.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

/**
 * The outbound payload of the CardDemo bill-payment screen: a field-for-field projection of
 * {@code 01 COBIL0AO REDEFINES COBIL0AI}, the output view of the {@code COBIL00} symbolic map declared in
 * {@code app/cpy-bms/COBIL00.CPY}.
 *
 * <p>COBOL {@code WORKING-STORAGE} never becomes a static Java field.
 */
@JsonPropertyOrder({
        "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin", "curbal",
        "confirm", "errmsg",
        "navigationContext", "nextProgram", "nextMapset", "nextMap", "trnIdFirst", "trnIdLast",
        "pageNum", "nextPageFlg", "trnSelFlg", "trnSelected"})
public class BillPaymentResponse {
    /**
     * The mapset name, {@code COBIL00}, as declared by {@code DEFINE MAPSET(COBIL00) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:114} and by {@code COBIL00 DFHMSD} at {@code app/bms/COBIL00.bms:19}.
     */
    public static final String MAPSET_NAME = "COBIL00";

    /**
     * The map name, {@code COBIL0A}, as declared by {@code COBIL0A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)}
     * at {@code app/bms/COBIL00.bms:26-28} and named in {@code MAP('COBIL0A')} at
     * {@code app/cbl/COBIL00C.cbl:296} and {@code 309}.
     */
    public static final String MAP_NAME = "COBIL0A";

    /**
     * The program name, {@code COBIL00C}, as declared by {@code DEFINE PROGRAM(COBIL00C) GROUP(CARDDEMO)}
     * at {@code app/csd/CARDDEMO.CSD:196} and held in {@code WS-PGMNAME} at
     * {@code app/cbl/COBIL00C.cbl:37}.
     */
    public static final String PROGRAM_NAME = "COBIL00C";

    /**
     * The transaction identifier, {@code CB00}, as declared by
     * {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)} with {@code PROGRAM(COBIL00C)} at
     * {@code app/csd/CARDDEMO.CSD:337-338} and held in {@code WS-TRANID} at
     * {@code app/cbl/COBIL00C.cbl:38}.
     */
    public static final String TRANSACTION_ID = "CB00";

    public static final String SIGN_ON_PROGRAM = "COSGN00C";

    public static final String MAIN_MENU_PROGRAM = "COMEN01C";

    /**
     * Width of the unnamed leading {@code FILLER}, {@value #TIOAPFX_PREFIX_LENGTH} bytes:
     * {@code 02 FILLER PIC X(12)} at {@code app/cpy-bms/COBIL00.CPY:80}, which is the same leading filler
     * {@code 01 COBIL0AI} declares at line 18.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    public static final int FIELD_PROLOGUE_LENGTH = 7;

    /**
     * The number of screen fields the map declares, {@value #MAP_FIELD_COUNT}.
     */
    public static final int MAP_FIELD_COUNT = 10;

    /**
     * Width of {@code TRNNAMEO PIC X(4)} at {@code app/cpy-bms/COBIL00.CPY:86}, {@value #TRN_NAME_LENGTH}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01O PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:92}, {@value #TITLE01_LENGTH}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEO PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:98}, {@value #CUR_DATE_LENGTH}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEO PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:104}, {@value #PGM_NAME_LENGTH}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02O PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:110}, {@value #TITLE02_LENGTH}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEO PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:116}, {@value #CUR_TIME_LENGTH}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of {@code ACTIDINO PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:122},
     * {@value #ACT_ID_IN_LENGTH}.
     */
    public static final int ACT_ID_IN_LENGTH = 11;

    /**
     * Width of {@code CURBALO PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:128}, {@value #CUR_BAL_LENGTH}.
     */
    public static final int CUR_BAL_LENGTH = 14;

    /**
     * Width of {@code CONFIRMO PIC X(1)} at {@code app/cpy-bms/COBIL00.CPY:134}, {@value #CONFIRM_LENGTH}.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width of {@code ERRMSGO PIC X(78)} at {@code app/cpy-bms/COBIL00.CPY:140}, {@value #ERR_MSG_LENGTH}.
     */
    public static final int ERR_MSG_LENGTH = 78;

    public static final int MAP_DATA_LENGTH = TRN_NAME_LENGTH
            + TITLE01_LENGTH
            + CUR_DATE_LENGTH
            + PGM_NAME_LENGTH
            + TITLE02_LENGTH
            + CUR_TIME_LENGTH
            + ACT_ID_IN_LENGTH
            + CUR_BAL_LENGTH
            + CONFIRM_LENGTH
            + ERR_MSG_LENGTH;

    public static final int SYMBOLIC_MAP_LENGTH = TIOAPFX_PREFIX_LENGTH
            + (MAP_FIELD_COUNT * FIELD_PROLOGUE_LENGTH)
            + MAP_DATA_LENGTH;

    /**
     * Width of {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COBIL00C.cbl:39}, {@value #WS_MESSAGE_LENGTH}
     * - the working-storage field every message text is composed into before being sent.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * Width of a BMS field-attribute byte, {@value #MESSAGE_HIGHLIGHT_LENGTH}.
     */
    public static final int MESSAGE_HIGHLIGHT_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRNID-FIRST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:65},
     * {@value #TRN_ID_FIRST_LENGTH} - a transaction identifier, matching {@code TRAN-ID PIC X(16)} in the
     * transaction record.
     */
    public static final int TRN_ID_FIRST_LENGTH = 16;

    /**
     * Width of {@code CDEMO-CB00-TRNID-LAST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:66},
     * {@value #TRN_ID_LAST_LENGTH}.
     */
    public static final int TRN_ID_LAST_LENGTH = 16;

    /**
     * Digit count of {@code CDEMO-CB00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COBIL00C.cbl:67},
     * {@value #PAGE_NUM_DIGITS}.
     */
    public static final int PAGE_NUM_DIGITS = 8;

    /**
     * Width of {@code CDEMO-CB00-NEXT-PAGE-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:68},
     * {@value #NEXT_PAGE_FLG_LENGTH}.
     */
    public static final int NEXT_PAGE_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SEL-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:71},
     * {@value #TRN_SEL_FLG_LENGTH}.
     */
    public static final int TRN_SEL_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SELECTED PIC X(16)} at {@code app/cbl/COBIL00C.cbl:72},
     * {@value #TRN_SELECTED_LENGTH}.
     */
    public static final int TRN_SELECTED_LENGTH = 16;

    /**
     * Total width of {@code CDEMO-CB00-INFO}, {@link #CB00_INFO_LENGTH} bytes, declared as the sum of its
     * six members: 16 + 16 + 8 + 1 + 1 + 16.
     */
    public static final int CB00_INFO_LENGTH = TRN_ID_FIRST_LENGTH
            + TRN_ID_LAST_LENGTH
            + PAGE_NUM_DIGITS
            + NEXT_PAGE_FLG_LENGTH
            + TRN_SEL_FLG_LENGTH
            + TRN_SELECTED_LENGTH;

    /**
     * Total width of the communication area as {@code COBIL00C} declares it, {@link #CB00_COMMAREA_LENGTH}
     * bytes: the shared {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} plus this
     * program's {@link #CB00_INFO_LENGTH}-byte extension.
     */
    public static final int CB00_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CB00_INFO_LENGTH;

    /**
     * The value of {@code 88 NEXT-PAGE-YES} at {@code app/cbl/COBIL00C.cbl:69}, {@code 'Y'}.
     */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * The value of {@code 88 NEXT-PAGE-NO} at {@code app/cbl/COBIL00C.cbl:70}, {@code 'N'}, which is also
     * the {@code VALUE} clause on the field itself at line 68 and therefore the initial state of
     * {@link #getNextPageFlg()}.
     */
    public static final String NEXT_PAGE_NO = "N";

    /**
     * Which screen field the cursor is placed in when this response is rendered.
     */
    public enum CursorField {
        /**
         * No cursor repositioning is requested, so the terminal applies the map's own default.
         */
        NONE,

        /**
         * The cursor is placed in the account-identifier field, {@code ACTIDIN} at {@code (6,21)}.
         */
        ACTIDIN,

        CONFIRM
    }

    @JsonProperty("trnname")
    private String trnName = ScreenFieldImage.unpainted(TRN_NAME_LENGTH);

    private String title01 = ScreenFieldImage.unpainted(TITLE01_LENGTH);

    @JsonProperty("curdate")
    private String curDate = ScreenFieldImage.unpainted(CUR_DATE_LENGTH);

    @JsonProperty("pgmname")
    private String pgmName = ScreenFieldImage.unpainted(PGM_NAME_LENGTH);

    private String title02 = ScreenFieldImage.unpainted(TITLE02_LENGTH);

    @JsonProperty("curtime")
    private String curTime = ScreenFieldImage.unpainted(CUR_TIME_LENGTH);

    @JsonProperty("actidin")
    private String actIdIn = ScreenFieldImage.unpainted(ACT_ID_IN_LENGTH);

    @JsonProperty("curbal")
    private String curBal = ScreenFieldImage.unpainted(CUR_BAL_LENGTH);

    private String confirm = ScreenFieldImage.unpainted(CONFIRM_LENGTH);

    @JsonProperty("errmsg")
    private String errMsg = ScreenFieldImage.unpainted(ERR_MSG_LENGTH);

    private CursorField cursorField = CursorField.NONE;

    private String messageHighlight;

    private NavigationContext navigationContext;

    private String nextProgram = ScreenFieldImage.spaces(NavigationContext.TO_PROGRAM_LENGTH);

    private String nextMapset = ScreenFieldImage.spaces(NavigationContext.LAST_MAPSET_LENGTH);

    private String nextMap = ScreenFieldImage.spaces(NavigationContext.LAST_MAP_LENGTH);

    private String trnIdFirst = ScreenFieldImage.spaces(TRN_ID_FIRST_LENGTH);

    private String trnIdLast = ScreenFieldImage.spaces(TRN_ID_LAST_LENGTH);

    private int pageNum;

    private String nextPageFlg = NEXT_PAGE_NO;

    private String trnSelFlg = ScreenFieldImage.spaces(TRN_SEL_FLG_LENGTH);

    private String trnSelected = ScreenFieldImage.spaces(TRN_SELECTED_LENGTH);

    /**
     * Creates an empty response: every member carrying a declared-width image, and none of them
     * {@code null}.
     *
     * <p>The ten map members start at {@link ScreenFieldImage#unpainted(int)} - {@code X'00'} at each
     * declared width - which is exactly what {@code MOVE LOW-VALUES TO COBIL0AO} at
     * {@code app/cbl/COBIL00C.cbl:114} produces.
     */
    public BillPaymentResponse() {
    }

    /**
     * Returns {@code TRNNAMEO}, the transaction identifier shown in the screen header.
     *
     * @return at most {@value #TRN_NAME_LENGTH} characters, normally {@link #TRANSACTION_ID}, possibly
     *     {@code null}
     */
    public String getTrnName() {
        return trnName;
    }

    public void setTrnName(String trnName) {
        this.trnName = trnName;
    }

    /**
     * Returns {@code TITLE01O}, the first header title line.
     *
     * @return at most {@value #TITLE01_LENGTH} characters, possibly {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns {@code CURDATEO}, the current date rendered {@code MM/DD/YY}.
     *
     * @return at most {@value #CUR_DATE_LENGTH} characters, possibly {@code null}
     */
    public String getCurDate() {
        return curDate;
    }

    public void setCurDate(String curDate) {
        this.curDate = curDate;
    }

    /**
     * Returns {@code PGMNAMEO}, the program name shown in the screen header.
     *
     * @return at most {@value #PGM_NAME_LENGTH} characters, normally {@link #PROGRAM_NAME}, possibly
     *     {@code null}
     */
    public String getPgmName() {
        return pgmName;
    }

    public void setPgmName(String pgmName) {
        this.pgmName = pgmName;
    }

    /**
     * Returns {@code TITLE02O}, the second header title line.
     *
     * @return at most {@value #TITLE02_LENGTH} characters, possibly {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns {@code CURTIMEO}, the current time rendered {@code HH:MM:SS}.
     *
     * @return at most {@value #CUR_TIME_LENGTH} characters, possibly {@code null}
     */
    public String getCurTime() {
        return curTime;
    }

    public void setCurTime(String curTime) {
        this.curTime = curTime;
    }

    /**
     * Returns {@code ACTIDINO}, the account identifier echoed back to the screen.
     *
     * @return at most {@value #ACT_ID_IN_LENGTH} characters, possibly {@code null}
     */
    public String getActIdIn() {
        return actIdIn;
    }

    public void setActIdIn(String actIdIn) {
        this.actIdIn = actIdIn;
    }

    /**
     * Returns {@code CURBALO}, the account balance already rendered as characters through the
     * {@code PIC +9999999999.99} edit mask - sign, ten digits, decimal point, two digits.
     *
     * @return at most {@value #CUR_BAL_LENGTH} characters, possibly {@code null}
     */
    public String getCurBal() {
        return curBal;
    }

    public void setCurBal(String curBal) {
        this.curBal = curBal;
    }

    /**
     * Returns {@code CONFIRMO}, the one-character payment confirmation echoed back to the screen.
     *
     * @return at most {@value #CONFIRM_LENGTH} character, possibly {@code null}
     */
    public String getConfirm() {
        return confirm;
    }

    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    public String getErrMsg() {
        return errMsg;
    }

    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }

    @JsonIgnore
    public CursorField getCursorField() {
        return cursorField;
    }

    public void setCursorField(CursorField cursorField) {
        this.cursorField = cursorField;
    }

    /**
     * Returns the colour attribute applied to the message line.
     *
     * @return a {@value #MESSAGE_HIGHLIGHT_LENGTH}-character BMS attribute value, or {@code null} where the
     *     program applies no override and the map's declared red stands
     */
    @JsonIgnore
    public String getMessageHighlight() {
        return messageHighlight;
    }

    /**
     * Sets the colour attribute applied to the message line.
     *
     * @param messageHighlight the BMS attribute value, {@value #MESSAGE_HIGHLIGHT_LENGTH} character;
     *     {@code null} requests no override
     */
    public void setMessageHighlight(String messageHighlight) {
        this.messageHighlight = messageHighlight;
    }

    /**
     * Returns the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area handed back to the
     * client.
     *
     * @return the carried context, or {@code null} where none was set
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the communication area handed back to the client.
     *
     * @param navigationContext the context to carry; {@code null} is permitted and preserved
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Returns the program the client should call next.
     *
     * @return one of {@link #SIGN_ON_PROGRAM}, {@link #MAIN_MENU_PROGRAM}, an echoed
     *     {@code CDEMO-FROM-PROGRAM} or {@code CDEMO-TO-PROGRAM} value
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Sets the program the client should call next.
     *
     * @param nextProgram the next program name; {@code null} requests no transfer
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram = nextProgram;
    }

    /**
     * Returns the mapset the client should render next.
     *
     * @return {@link #MAPSET_NAME} where this screen re-displays itself, possibly {@code null}
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the mapset the client should render next.
     *
     * @param nextMapset the next mapset name; {@code null} is permitted and preserved
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = nextMapset;
    }

    /**
     * Returns the map the client should render next.
     *
     * @return {@link #MAP_NAME} where this screen re-displays itself, possibly {@code null}
     */
    public String getNextMap() {
        return nextMap;
    }

    public void setNextMap(String nextMap) {
        this.nextMap = nextMap;
    }

    /**
     * Returns {@code CDEMO-CB00-TRNID-FIRST}.
     *
     * @return at most {@value #TRN_ID_FIRST_LENGTH} characters, possibly {@code null}
     */
    public String getTrnIdFirst() {
        return trnIdFirst;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-FIRST}.
     *
     * @param trnIdFirst the first transaction identifier of a page, at most {@value #TRN_ID_FIRST_LENGTH}
     *     characters; {@code null} is permitted
     */
    public void setTrnIdFirst(String trnIdFirst) {
        this.trnIdFirst = trnIdFirst;
    }

    /**
     * Returns {@code CDEMO-CB00-TRNID-LAST}.
     *
     * @return at most {@value #TRN_ID_LAST_LENGTH} characters, possibly {@code null}
     */
    public String getTrnIdLast() {
        return trnIdLast;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-LAST}.
     *
     * @param trnIdLast the last transaction identifier of a page, at most {@value #TRN_ID_LAST_LENGTH}
     *     characters; {@code null} is permitted
     */
    public void setTrnIdLast(String trnIdLast) {
        this.trnIdLast = trnIdLast;
    }

    /**
     * Returns {@code CDEMO-CB00-PAGE-NUM}.
     *
     * @return the page number, at most {@value #PAGE_NUM_DIGITS} digits, zero unless assigned
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * Sets {@code CDEMO-CB00-PAGE-NUM}.
     *
     * @param pageNum the page number, at most {@value #PAGE_NUM_DIGITS} digits
     */
    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    /**
     * Returns {@code CDEMO-CB00-NEXT-PAGE-FLG}.
     *
     * @return {@link #NEXT_PAGE_YES}, {@link #NEXT_PAGE_NO} or any other single character the communication
     *     area carried; {@link #NEXT_PAGE_NO} unless assigned
     */
    public String getNextPageFlg() {
        return nextPageFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-NEXT-PAGE-FLG}.
     *
     * @param nextPageFlg the flag, at most {@value #NEXT_PAGE_FLG_LENGTH} character; {@code null} is
     *     permitted and preserved
     */
    public void setNextPageFlg(String nextPageFlg) {
        this.nextPageFlg = nextPageFlg;
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SEL-FLG}.
     *
     * @return at most {@value #TRN_SEL_FLG_LENGTH} character, possibly {@code null}
     */
    public String getTrnSelFlg() {
        return trnSelFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SEL-FLG}.
     *
     * @param trnSelFlg the selection flag, at most {@value #TRN_SEL_FLG_LENGTH} character; {@code null} is
     *     permitted and preserved
     */
    public void setTrnSelFlg(String trnSelFlg) {
        this.trnSelFlg = trnSelFlg;
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SELECTED} - the one member of the extension the program reads, at lines
     * 116-119, to seed the account field when another screen navigated here with an account already chosen.
     *
     * @return at most {@value #TRN_SELECTED_LENGTH} characters, possibly {@code null}
     */
    public String getTrnSelected() {
        return trnSelected;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SELECTED}.
     *
     * @param trnSelected the selected identifier, at most {@value #TRN_SELECTED_LENGTH} characters;
     *     {@code null} is permitted and preserved
     */
    public void setTrnSelected(String trnSelected) {
        this.trnSelected = trnSelected;
    }

    /**
     * A diagnostic rendering that masks the identifiers and withholds the balance.
     *
     * <p>The JSON payload and every accessor are untouched: the REST projection of the symbolic map is the
     * parity surface, and this rendering has no COBOL counterpart.
     *
     * @return the rendering; never {@code null}
     */
    @Override
    public String toString() {
        return "BillPaymentResponse[trnName=" + DiagnosticText.singleLine(trnName)
                + ", title01=" + DiagnosticText.singleLine(title01)
                + ", curDate=" + DiagnosticText.singleLine(curDate)
                + ", pgmName=" + DiagnosticText.singleLine(pgmName)
                + ", title02=" + DiagnosticText.singleLine(title02)
                + ", curTime=" + DiagnosticText.singleLine(curTime)
                + ", actIdIn=" + SensitiveDiagnostics.maskIdentifier(actIdIn)
                + ", curBal=" + DiagnosticText.omitted(curBal)
                + ", confirm=" + DiagnosticText.singleLine(confirm)
                + ", errMsg=" + DiagnosticText.singleLine(errMsg)
                + ", cursorField=" + cursorField
                + ", messageHighlight=" + messageHighlight
                + ", navigationContext=" + navigationContext
                + ", nextProgram=" + DiagnosticText.singleLine(nextProgram)
                + ", nextMapset=" + DiagnosticText.singleLine(nextMapset)
                + ", nextMap=" + DiagnosticText.singleLine(nextMap)
                + ", trnIdFirst=" + SensitiveDiagnostics.maskIdentifier(trnIdFirst)
                + ", trnIdLast=" + SensitiveDiagnostics.maskIdentifier(trnIdLast)
                + ", pageNum=" + pageNum
                + ", nextPageFlg=" + DiagnosticText.singleLine(nextPageFlg)
                + ", trnSelFlg=" + DiagnosticText.singleLine(trnSelFlg)
                + ", trnSelected=" + SensitiveDiagnostics.maskIdentifier(trnSelected)
                + "]";
    }
}
