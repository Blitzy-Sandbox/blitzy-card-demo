package com.vsergeychik.carddemo.billing.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.constraints.Size;

/**
 * The inbound payload of the CardDemo bill-payment screen: a field-for-field projection of
 * {@code 01 COBIL0AI}, the input view of the {@code COBIL00} symbolic map declared in
 * {@code app/cpy-bms/COBIL00.CPY}.
 *
 * <p>COBOL {@code WORKING-STORAGE} never becomes a static Java field.
 */
@JsonPropertyOrder({
        "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "actidin", "curbal",
        "confirm", "errmsg",
        // The transport extensions follow the map, never interleave with it: they are not DFHMDF fields but
        // the CARDDEMO-COMMAREA, the EIBAID and the COBIL00C paging state that CICS would have carried
        // outside the map area.
        "navigationContext", "aid", "trnIdFirst", "trnIdLast", "pageNum", "nextPageFlg",
        "trnSelFlg", "trnSelected"})
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class BillPaymentRequest {
    // WS-TRANID and WS-PGMNAME are the values POPULATE-HEADER-INFO moves into TRNNAMEO and PGMNAMEO at
    // app/cbl/COBIL00C.cbl:325-326, so two of the ten members below are populated from exactly these two
    // constants.

    /**
     * The mapset name, {@code COBIL00}: {@code DEFINE MAPSET(COBIL00) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:114}, and the label on {@code DFHMSD} at {@code app/bms/COBIL00.bms:19}.
     */
    public static final String MAPSET_NAME = "COBIL00";

    /**
     * The map name, {@code COBIL0A}: the label on {@code DFHMDI} at {@code app/bms/COBIL00.bms:26}, which
     * declares {@code COLUMN=1}, {@code LINE=1} and {@code SIZE=(24,80)}.
     */
    public static final String MAP_NAME = "COBIL0A";

    /**
     * The program name, {@code COBIL00C}: {@code DEFINE PROGRAM(COBIL00C) GROUP(CARDDEMO)} at
     * {@code app/csd/CARDDEMO.CSD:196}, and {@code WS-PGMNAME PIC X(08) VALUE 'COBIL00C'} at
     * {@code app/cbl/COBIL00C.cbl:37}.
     */
    public static final String PROGRAM_NAME = "COBIL00C";

    /**
     * The transaction identifier, {@code CB00}: {@code DEFINE TRANSACTION(CB00) GROUP(CARDDEMO)} with
     * {@code PROGRAM(COBIL00C)} at {@code app/csd/CARDDEMO.CSD:337-338}, and
     * {@code WS-TRANID PIC X(04) VALUE 'CB00'} at {@code app/cbl/COBIL00C.cbl:38}.
     */
    public static final String TRANSACTION_ID = "CB00";

    /**
     * The {@value #TIOAPFX_PREFIX_LENGTH}-byte unnamed {@code FILLER} that opens {@code 01 COBIL0AI} at
     * {@code app/cpy-bms/COBIL00.CPY:18}, present because {@code app/bms/COBIL00.bms:24} requests
     * {@code TIOAPFX=YES}.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The per-field prologue that precedes every data item in the symbolic map: {@code xxxL COMP PIC S9(4)}
     * occupies 2 bytes, {@code xxxF PICTURE X} occupies 1, and the reserved {@code FILLER PICTURE X(4)}
     * occupies 4, totalling {@value #FIELD_PROLOGUE_LENGTH}.
     */
    public static final int FIELD_PROLOGUE_LENGTH = 7;

    /**
     * The number of screen fields in {@code COBIL00} that carry a name and therefore a payload member:
     * {@value #MAP_FIELD_COUNT}.
     */
    public static final int MAP_FIELD_COUNT = 10;

    /**
     * Width of {@code TRNNAMEI PIC X(4)} at {@code app/cpy-bms/COBIL00.CPY:24}, matching {@code LENGTH=4}
     * at {@code app/bms/COBIL00.bms:36}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01I PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:30}, matching {@code LENGTH=40}
     * at {@code app/bms/COBIL00.bms:40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:36}, matching {@code LENGTH=8}
     * at {@code app/bms/COBIL00.bms:49}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:42}, matching {@code LENGTH=8}
     * at {@code app/bms/COBIL00.bms:59}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02I PIC X(40)} at {@code app/cpy-bms/COBIL00.CPY:48}, matching {@code LENGTH=40}
     * at {@code app/bms/COBIL00.bms:63}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)} at {@code app/cpy-bms/COBIL00.CPY:54}, matching {@code LENGTH=8}
     * at {@code app/bms/COBIL00.bms:72}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Width of {@code ACTIDINI PIC X(11)} at {@code app/cpy-bms/COBIL00.CPY:60}, matching {@code LENGTH=11}
     * at {@code app/bms/COBIL00.bms:88}.
     */
    public static final int ACT_ID_IN_LENGTH = 11;

    /**
     * Width of {@code CURBALI PIC X(14)} at {@code app/cpy-bms/COBIL00.CPY:66}, matching {@code LENGTH=14}
     * at {@code app/bms/COBIL00.bms:105}.
     */
    public static final int CUR_BAL_LENGTH = 14;

    /**
     * Width of {@code CONFIRMI PIC X(1)} at {@code app/cpy-bms/COBIL00.CPY:72}, matching {@code LENGTH=1}
     * at {@code app/bms/COBIL00.bms:118}.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * Width of {@code ERRMSGI PIC X(78)} at {@code app/cpy-bms/COBIL00.CPY:78}, matching {@code LENGTH=78}
     * at {@code app/bms/COBIL00.bms:129}.
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
     * Width of {@code CDEMO-CB00-TRNID-FIRST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:65}.
     */
    public static final int TRN_ID_FIRST_LENGTH = 16;

    /**
     * Width of {@code CDEMO-CB00-TRNID-LAST PIC X(16)} at {@code app/cbl/COBIL00C.cbl:66}.
     */
    public static final int TRN_ID_LAST_LENGTH = 16;

    /**
     * Digit count of {@code CDEMO-CB00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COBIL00C.cbl:67}.
     */
    public static final int PAGE_NUM_DIGITS = 8;

    /**
     * Width of {@code CDEMO-CB00-NEXT-PAGE-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:68}.
     */
    public static final int NEXT_PAGE_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SEL-FLG PIC X(01)} at {@code app/cbl/COBIL00C.cbl:71}.
     */
    public static final int TRN_SEL_FLG_LENGTH = 1;

    /**
     * Width of {@code CDEMO-CB00-TRN-SELECTED PIC X(16)} at {@code app/cbl/COBIL00C.cbl:72}.
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

    // COBOL distinguishes LOW-VALUES from SPACES and the program tests for both separately, so JSON null,
    // the empty string and an all-blank string are three distinct inbound states that must survive binding
    // unchanged.

    @Size(max = TRN_NAME_LENGTH)
    @JsonProperty("trnname")
    private String trnName;

    @Size(max = TITLE01_LENGTH)
    private String title01;

    @Size(max = CUR_DATE_LENGTH)
    @JsonProperty("curdate")
    private String curDate;

    @Size(max = PGM_NAME_LENGTH)
    @JsonProperty("pgmname")
    private String pgmName;

    @Size(max = TITLE02_LENGTH)
    private String title02;

    @Size(max = CUR_TIME_LENGTH)
    @JsonProperty("curtime")
    private String curTime;

    @Size(max = ACT_ID_IN_LENGTH)
    @JsonProperty("actidin")
    private String actIdIn;

    @Size(max = CUR_BAL_LENGTH)
    @JsonProperty("curbal")
    private String curBal;

    @Size(max = CONFIRM_LENGTH)
    private String confirm;

    @Size(max = ERR_MSG_LENGTH)
    @JsonProperty("errmsg")
    private String errMsg;

    private NavigationContext navigationContext;

    private String aid;

    // The enter-versus-re-enter context is CDEMO-PGM-CONTEXT PIC 9(01), declared at app/cpy/COCOM01Y.cpy:29
    // INSIDE 01 CARDDEMO-COMMAREA, and app/cbl/COBIL00C.cbl:64-72 appends exactly six items to that area -
    // TRNID-FIRST, TRNID-LAST, PAGE-NUM, NEXT-PAGE-FLG, TRN-SEL-FLG and TRN-SELECTED.

    // Exhaustive search of the program finds exactly two non-declaration references to the whole group,
    // both to TRN-SELECTED, at lines 116 and 118.

    private String trnIdFirst;

    private String trnIdLast;

    private int pageNum;

    private String nextPageFlg = NEXT_PAGE_NO;

    private String trnSelFlg;

    private String trnSelected;

    public BillPaymentRequest() {
    }

    /**
     * Returns {@code TRNNAMEI}, the transaction identifier shown at {@code (1,7)}.
     *
     * @return the transaction identifier, at most {@value #TRN_NAME_LENGTH} characters, possibly
     *     {@code null}
     */
    public String getTrnName() {
        return trnName;
    }

    public void setTrnName(String trnName) {
        this.trnName = trnName;
    }

    /**
     * Returns {@code TITLE01I}, the first title line shown at {@code (1,21)}.
     *
     * @return the first title line, at most {@value #TITLE01_LENGTH} characters, possibly {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * Returns {@code CURDATEI}, the current date shown at {@code (1,71)} as {@code mm/dd/yy}.
     *
     * @return the current date, at most {@value #CUR_DATE_LENGTH} characters, possibly {@code null}
     */
    public String getCurDate() {
        return curDate;
    }

    public void setCurDate(String curDate) {
        this.curDate = curDate;
    }

    /**
     * Returns {@code PGMNAMEI}, the program name shown at {@code (2,7)}.
     *
     * @return the program name, at most {@value #PGM_NAME_LENGTH} characters, possibly {@code null}
     */
    public String getPgmName() {
        return pgmName;
    }

    public void setPgmName(String pgmName) {
        this.pgmName = pgmName;
    }

    /**
     * Returns {@code TITLE02I}, the second title line shown at {@code (2,21)}.
     *
     * @return the second title line, at most {@value #TITLE02_LENGTH} characters, possibly {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * Returns {@code CURTIMEI}, the current time shown at {@code (2,71)} as {@code hh:mm:ss}.
     *
     * @return the current time, at most {@value #CUR_TIME_LENGTH} characters, possibly {@code null}
     */
    public String getCurTime() {
        return curTime;
    }

    public void setCurTime(String curTime) {
        this.curTime = curTime;
    }

    /**
     * Returns {@code ACTIDINI}, the account identifier the user typed - the primary input of this screen.
     *
     * <p>Blank and absent values are preserved as distinct states because {@code app/cbl/COBIL00C.cbl:159}
     * tests {@code = SPACES OR LOW-VALUES} and answers with a message rather than rejecting the request.
     *
     * @return the account identifier, at most {@value #ACT_ID_IN_LENGTH} characters, possibly {@code null},
     *     empty or blank
     */
    public String getActIdIn() {
        return actIdIn;
    }

    public void setActIdIn(String actIdIn) {
        this.actIdIn = actIdIn;
    }

    /**
     * Returns {@code CURBALI}, the current balance as the already-edited {@code +9999999999.99} text the
     * screen displayed.
     *
     * @return the edited balance text, at most {@value #CUR_BAL_LENGTH} characters, possibly {@code null}
     */
    public String getCurBal() {
        return curBal;
    }

    public void setCurBal(String curBal) {
        this.curBal = curBal;
    }

    /**
     * Returns {@code CONFIRMI}, the one-character confirmation the user typed.
     *
     * @return the confirmation character, at most {@value #CONFIRM_LENGTH} character, possibly
     *     {@code null}, empty or blank
     */
    public String getConfirm() {
        return confirm;
    }

    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * Returns {@code ERRMSGI}, the message line shown at {@code (23,1)}.
     *
     * @return the message text, at most {@value #ERR_MSG_LENGTH} characters, possibly {@code null}
     */
    public String getErrMsg() {
        return errMsg;
    }

    public void setErrMsg(String errMsg) {
        this.errMsg = errMsg;
    }

    /**
     * Returns the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area this request arrived
     * with.
     *
     * @return the communication area, or {@code null} where the request carries none - the cold start the
     *     program detects as {@code EIBCALEN = 0} at {@code app/cbl/COBIL00C.cbl:107}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Returns the resolved attention identifier - which key the user pressed.
     *
     * @return the one-character attention-identifier image, possibly {@code null} where no key indication
     *     accompanied the request
     */
    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    /**
     * Returns the enter-versus-re-enter context by reading through to the communication area:
     * {@value NavigationContext#PGM_CONTEXT_ENTER} on first entry,
     * {@value NavigationContext#PGM_CONTEXT_REENTER} on re-entry.
     *
     * @return the program context read from the communication area,
     *     {@value NavigationContext#PGM_CONTEXT_ENTER} where the request carries none, and any other digit only
     *     where a client sent one
     */
    @JsonIgnore
    public int getPgmContext() {
        NavigationContext carriedContext = this.navigationContext;
        return carriedContext == null
                ? NavigationContext.PGM_CONTEXT_ENTER
                : carriedContext.pgmContext();
    }

    /**
     * Returns {@code CDEMO-CB00-TRNID-FIRST}.
     *
     * @return the first transaction identifier of a page, at most {@value #TRN_ID_FIRST_LENGTH} characters,
     *     possibly {@code null}
     */
    public String getTrnIdFirst() {
        return trnIdFirst;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-FIRST}.
     *
     * @param trnIdFirst the first transaction identifier of a page; {@code null} is accepted and preserved
     */
    public void setTrnIdFirst(String trnIdFirst) {
        this.trnIdFirst = trnIdFirst;
    }

    /**
     * Returns {@code CDEMO-CB00-TRNID-LAST}.
     *
     * @return the last transaction identifier of a page, at most {@value #TRN_ID_LAST_LENGTH} characters,
     *     possibly {@code null}
     */
    public String getTrnIdLast() {
        return trnIdLast;
    }

    /**
     * Sets {@code CDEMO-CB00-TRNID-LAST}.
     *
     * @param trnIdLast the last transaction identifier of a page; {@code null} is accepted and preserved
     */
    public void setTrnIdLast(String trnIdLast) {
        this.trnIdLast = trnIdLast;
    }

    /**
     * Returns {@code CDEMO-CB00-PAGE-NUM}.
     *
     * @return the page number, an integer of at most {@value #PAGE_NUM_DIGITS} digits, 0 where unset
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * Sets {@code CDEMO-CB00-PAGE-NUM}.
     *
     * @param pageNum the page number; stored as given, since a {@code PIC 9(08)} item carries no range
     *     check of its own beyond its width
     */
    public void setPageNum(int pageNum) {
        this.pageNum = pageNum;
    }

    /**
     * Returns {@code CDEMO-CB00-NEXT-PAGE-FLG}.
     *
     * @return the next-page flag, at most {@value #NEXT_PAGE_FLG_LENGTH} character, initially
     *     {@link #NEXT_PAGE_NO}
     */
    public String getNextPageFlg() {
        return nextPageFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-NEXT-PAGE-FLG}.
     *
     * @param nextPageFlg the next-page flag, normally {@link #NEXT_PAGE_YES} or {@link #NEXT_PAGE_NO};
     *     {@code null} and any other value are accepted and preserved, in which case both condition names are
     *     false
     */
    public void setNextPageFlg(String nextPageFlg) {
        this.nextPageFlg = nextPageFlg;
    }

    /**
     * Whether {@code 88 NEXT-PAGE-YES VALUE 'Y'} holds - that is, whether {@link #getNextPageFlg()} is
     * exactly {@link #NEXT_PAGE_YES}.
     *
     * @return {@code true} where the flag is {@link #NEXT_PAGE_YES}
     */
    public boolean nextPageYes() {
        return NEXT_PAGE_YES.equals(nextPageFlg);
    }

    /**
     * Whether {@code 88 NEXT-PAGE-NO VALUE 'N'} holds - that is, whether {@link #getNextPageFlg()} is
     * exactly {@link #NEXT_PAGE_NO}.
     *
     * @return {@code true} where the flag is {@link #NEXT_PAGE_NO}
     */
    public boolean nextPageNo() {
        return NEXT_PAGE_NO.equals(nextPageFlg);
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SEL-FLG}.
     *
     * @return the selection flag, at most {@value #TRN_SEL_FLG_LENGTH} character, possibly {@code null}
     */
    public String getTrnSelFlg() {
        return trnSelFlg;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SEL-FLG}.
     *
     * @param trnSelFlg the selection flag; {@code null} is accepted and preserved
     */
    public void setTrnSelFlg(String trnSelFlg) {
        this.trnSelFlg = trnSelFlg;
    }

    /**
     * Returns {@code CDEMO-CB00-TRN-SELECTED}, the deep-link value - the one member of the extension the
     * program reads.
     *
     * @return the pre-selected account identifier, at most {@value #TRN_SELECTED_LENGTH} characters,
     *     possibly {@code null}, empty or blank
     */
    public String getTrnSelected() {
        return trnSelected;
    }

    /**
     * Sets {@code CDEMO-CB00-TRN-SELECTED}.
     *
     * @param trnSelected the pre-selected account identifier; {@code null}, empty and blank are all
     *     accepted and preserved as distinct states
     */
    public void setTrnSelected(String trnSelected) {
        this.trnSelected = trnSelected;
    }

    /**
     * Returns a diagnostic rendering of every member, for test failure messages and log lines.
     *
     * @return a single-line rendering naming every member, never {@code null}
     */
    @Override
    public String toString() {
        return "BillPaymentRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", actIdIn=" + SensitiveDiagnostics.maskIdentifier(actIdIn)
                + ", curBal=" + curBal
                + ", confirm=" + confirm
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ", trnIdFirst=" + trnIdFirst
                + ", trnIdLast=" + trnIdLast
                + ", pageNum=" + pageNum
                + ", nextPageFlg=" + nextPageFlg
                + ", trnSelFlg=" + trnSelFlg
                + ", trnSelected=" + trnSelected
                + "]";
    }
}
