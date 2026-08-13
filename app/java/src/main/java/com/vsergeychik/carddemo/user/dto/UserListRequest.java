package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inbound payload of {@code GET /api/users} - CICS transaction {@code CU00}, program
 * {@code app/cbl/COUSR00C.cbl}, mapset {@code COUSR00}, map {@code COUSR0A}.
 *
 * <p>{@code app/bms/COUSR00.bms} contains 89 {@code DFHMDF} definitions of which exactly
 * {@value #MAP_FIELD_COUNT} are name-labelled.
 *
 * @param trnName {@code TRNNAMEI PIC X(4)}: the transaction identifier, {@link #TRANID}
 * @param title01 {@code TITLE01I PIC X(40)}: the first title line
 * @param curDate {@code CURDATEI PIC X(8)}: the current date, {@code mm/dd/yy}
 * @param pgmName {@code PGMNAMEI PIC X(8)}: the program name, {@link #PROGRAM}
 * @param title02 {@code TITLE02I PIC X(40)}: the second title line
 * @param curTime {@code CURTIMEI PIC X(8)}: the current time, {@code hh:mm:ss}
 * @param pageNum {@code PAGENUMI PIC X(8)}: the displayed page number, a character field, distinct from the
 *     numeric {@code cdemoCu00PageNum}
 * @param usrIdIn {@code USRIDINI PIC X(8)}: the browse-start user id; blank means "start at the beginning
 *     of the file"
 * @param rows the {@value #ROW_COUNT} screen rows, always exactly that many
 * @param errMsg {@code ERRMSGI PIC X(78)}: the message line
 * @param cdemoCu00UsrIdFirst {@code CDEMO-CU00-USRID-FIRST PIC X(08)}: the first user id on the page
 * @param cdemoCu00UsrIdLast {@code CDEMO-CU00-USRID-LAST PIC X(08)}: the last user id on the page
 * @param cdemoCu00PageNum {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}: the numeric page number, unsigned
 * @param cdemoCu00NextPageFlg {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}: {@link #NEXT_PAGE_YES} or
 *     {@link #NEXT_PAGE_NO}, defaulting to {@link #NEXT_PAGE_NO}
 * @param cdemoCu00UsrSelFlg {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}: the captured row action
 * @param cdemoCu00UsrSelected {@code CDEMO-CU00-USR-SELECTED PIC X(08)}: the captured row's user id
 * @param navigationContext {@code 01 CARDDEMO-COMMAREA}
 * @param aid the {@code EIBAID} key indication as a token, at most {@value #AID_LENGTH} characters The
 *     {@code @JsonIgnoreProperties} below names the members the paired response carries that this request does
 *     not
 */
@JsonPropertyOrder({
        "trnName", "title01", "curDate", "pgmName", "title02", "curTime", "pageNum", "usrIdIn",
        "sel0001", "usrId01", "fname01", "lname01", "utype01", "sel0002", "usrId02", "fname02",
        "lname02", "utype02", "sel0003", "usrId03", "fname03", "lname03", "utype03", "sel0004",
        "usrId04", "fname04", "lname04", "utype04", "sel0005", "usrId05", "fname05", "lname05",
        "utype05", "sel0006", "usrId06", "fname06", "lname06", "utype06", "sel0007", "usrId07",
        "fname07", "lname07", "utype07", "sel0008", "usrId08", "fname08", "lname08", "utype08",
        "sel0009", "usrId09", "fname09", "lname09", "utype09", "sel0010", "usrId10", "fname10",
        "lname10", "utype10", "errMsg", "cdemoCu00UsrIdFirst", "cdemoCu00UsrIdLast",
        "cdemoCu00PageNum", "cdemoCu00NextPageFlg", "cdemoCu00UsrSelFlg", "cdemoCu00UsrSelected",
        "navigationContext", "aid"})
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record UserListRequest(

        @Size(max = UserListRequest.TRNNAME_LENGTH) @JsonProperty("trnname") String trnName,
        @Size(max = UserListRequest.TITLE01_LENGTH) String title01,
        @Size(max = UserListRequest.CURDATE_LENGTH) @JsonProperty("curdate") String curDate,
        @Size(max = UserListRequest.PGMNAME_LENGTH) @JsonProperty("pgmname") String pgmName,
        @Size(max = UserListRequest.TITLE02_LENGTH) String title02,
        @Size(max = UserListRequest.CURTIME_LENGTH) @JsonProperty("curtime") String curTime,
        @Size(max = UserListRequest.PAGENUM_LENGTH) @JsonProperty("pagenum") String pageNum,
        @Size(max = UserListRequest.USRIDIN_LENGTH) @JsonProperty("usridin") String usrIdIn,

        @JsonIgnore
        @Valid @Size(min = UserListRequest.ROW_COUNT, max = UserListRequest.ROW_COUNT)
        List<UserListRow> rows,

        @Size(max = UserListRequest.ERRMSG_LENGTH) @JsonProperty("errmsg") String errMsg,

        @Size(max = UserListRequest.CU00_USRID_FIRST_LENGTH) String cdemoCu00UsrIdFirst,
        @Size(max = UserListRequest.CU00_USRID_LAST_LENGTH) String cdemoCu00UsrIdLast,
        int cdemoCu00PageNum,
        @Size(max = UserListRequest.CU00_NEXT_PAGE_FLG_LENGTH) String cdemoCu00NextPageFlg,
        @Size(max = UserListRequest.CU00_USR_SEL_FLG_LENGTH) String cdemoCu00UsrSelFlg,
        @Size(max = UserListRequest.CU00_USR_SELECTED_LENGTH) String cdemoCu00UsrSelected,

        NavigationContext navigationContext,
        @Size(max = UserListRequest.AID_LENGTH) String aid) {
    /**
     * {@code WS-TRANID VALUE 'CU00'} - the CICS transaction that runs this screen.
     */
    public static final String TRANID = "CU00";

    /**
     * {@code WS-PGMNAME VALUE 'COUSR00C'} - the COBOL program this payload was translated from.
     */
    public static final String PROGRAM = "COUSR00C";

    /**
     * The BMS mapset, {@code app/bms/COUSR00.bms} line 19: {@code COUSR00 DFHMSD}.
     */
    public static final String MAPSET = "COUSR00";

    /**
     * The BMS map, {@code app/bms/COUSR00.bms} line 26: {@code COUSR0A DFHMDI}.
     */
    public static final String MAP = "COUSR0A";

    /**
     * Label of {@link #trnName()}: {@code TRNNAME}, mapset line 34, {@code TRNNAMEI}.
     */
    public static final String TRNNAME_FIELD = "TRNNAME";

    /**
     * Label of {@link #title01()}: {@code TITLE01}, mapset line 38, {@code TITLE01I}.
     */
    public static final String TITLE01_FIELD = "TITLE01";

    /**
     * Label of {@link #curDate()}: {@code CURDATE}, mapset line 47, {@code CURDATEI}.
     */
    public static final String CURDATE_FIELD = "CURDATE";

    /**
     * Label of {@link #pgmName()}: {@code PGMNAME}, mapset line 57, {@code PGMNAMEI}.
     */
    public static final String PGMNAME_FIELD = "PGMNAME";

    /**
     * Label of {@link #title02()}: {@code TITLE02}, mapset line 61, {@code TITLE02I}.
     */
    public static final String TITLE02_FIELD = "TITLE02";

    /**
     * Label of {@link #curTime()}: {@code CURTIME}, mapset line 70, {@code CURTIMEI}.
     */
    public static final String CURTIME_FIELD = "CURTIME";

    /**
     * Label of {@link #pageNum()}: {@code PAGENUM}, mapset line 85, {@code PAGENUMI}.
     */
    public static final String PAGENUM_FIELD = "PAGENUM";

    /**
     * Label of {@link #usrIdIn()}: {@code USRIDIN}, mapset line 95, {@code USRIDINI}.
     */
    public static final String USRIDIN_FIELD = "USRIDIN";

    /**
     * Label of {@link #errMsg()}: {@code ERRMSG}, the last name-labelled {@code DFHMDF}.
     */
    public static final String ERRMSG_FIELD = "ERRMSG";

    public static final String SEL_FIELD_STEM = "SEL";

    public static final String USRID_FIELD_STEM = "USRID";

    public static final String FNAME_FIELD_STEM = "FNAME";

    public static final String LNAME_FIELD_STEM = "LNAME";

    public static final String UTYPE_FIELD_STEM = "UTYPE";

    public static final int SEL_FIELD_DIGITS = 4;

    public static final int ROW_FIELD_DIGITS = 2;

    /**
     * {@code TRNNAMEI PIC X(4)}, mapset {@code LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, mapset {@code LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, mapset {@code LENGTH=8}, {@code INITIAL='mm/dd/yy'}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, mapset {@code LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}, mapset {@code LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, mapset {@code LENGTH=8}, {@code INITIAL='hh:mm:ss'}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENUMI PIC X(8)}, mapset {@code LENGTH=8}, {@code INITIAL=' '}.
     */
    public static final int PAGENUM_LENGTH = 8;

    /**
     * {@code USRIDINI PIC X(8)}, mapset {@code LENGTH=8}, the one unprotected header field
     * ({@code ATTRB=(FSET,NORM,UNPROT)}, {@code HILIGHT=UNDERLINE}).
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * {@code SEL000nI PIC X(1)}, mapset {@code LENGTH=1}.
     */
    public static final int SEL_LENGTH = 1;

    /**
     * {@code USRIDnnI PIC X(8)}, mapset {@code LENGTH=8}; matches {@code SEC-USR-ID PIC X(08)}.
     */
    public static final int USRID_LENGTH = 8;

    /**
     * {@code FNAMEnnI PIC X(20)}, mapset {@code LENGTH=20}; matches {@code SEC-USR-FNAME PIC X(20)}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEnnI PIC X(20)}, mapset {@code LENGTH=20}; matches {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code UTYPEnnI PIC X(1)}, mapset {@code LENGTH=1}; matches {@code SEC-USR-TYPE PIC X(01)}.
     */
    public static final int UTYPE_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}, mapset {@code LENGTH=78}.
     */
    public static final int ERRMSG_LENGTH = 78;

    public static final int HEADER_FIELD_COUNT = 8;

    /**
     * Screen rows, and therefore the page size: exactly ten.
     */
    public static final int ROW_COUNT = 10;

    public static final int ROW_FIELD_COUNT = 5;

    public static final int TRAILER_FIELD_COUNT = 1;

    public static final int MAP_FIELD_COUNT = 59;

    /**
     * Copybook name of {@link #cdemoCu00UsrIdFirst()}: {@code CDEMO-CU00-USRID-FIRST}, line 67.
     */
    public static final String CU00_USRID_FIRST_FIELD = "CDEMO-CU00-USRID-FIRST";

    /**
     * Copybook name of {@link #cdemoCu00UsrIdLast()}: {@code CDEMO-CU00-USRID-LAST}, line 68.
     */
    public static final String CU00_USRID_LAST_FIELD = "CDEMO-CU00-USRID-LAST";

    /**
     * Copybook name of {@link #cdemoCu00PageNum()}: {@code CDEMO-CU00-PAGE-NUM}, line 69.
     */
    public static final String CU00_PAGE_NUM_FIELD = "CDEMO-CU00-PAGE-NUM";

    /**
     * Copybook name of {@link #cdemoCu00NextPageFlg()}: {@code CDEMO-CU00-NEXT-PAGE-FLG}, line 70.
     */
    public static final String CU00_NEXT_PAGE_FLG_FIELD = "CDEMO-CU00-NEXT-PAGE-FLG";

    /**
     * Copybook name of {@link #cdemoCu00UsrSelFlg()}: {@code CDEMO-CU00-USR-SEL-FLG}, line 73.
     */
    public static final String CU00_USR_SEL_FLG_FIELD = "CDEMO-CU00-USR-SEL-FLG";

    /**
     * Copybook name of {@link #cdemoCu00UsrSelected()}: {@code CDEMO-CU00-USR-SELECTED}, line 74.
     */
    public static final String CU00_USR_SELECTED_FIELD = "CDEMO-CU00-USR-SELECTED";

    /**
     * {@code CDEMO-CU00-USRID-FIRST PIC X(08)}.
     */
    public static final int CU00_USRID_FIRST_LENGTH = 8;

    /**
     * {@code CDEMO-CU00-USRID-LAST PIC X(08)}.
     */
    public static final int CU00_USRID_LAST_LENGTH = 8;

    /**
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} - eight digits, not characters.
     */
    public static final int CU00_PAGE_NUM_LENGTH = 8;

    /**
     * The largest value {@code PIC 9(08)} can hold: eight nines.
     */
    public static final int CU00_PAGE_NUM_MAX = 99_999_999;

    /**
     * The page number a fresh conversation starts on: zero.
     */
    public static final int CU00_PAGE_NUM_INITIAL = 0;

    /**
     * {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}.
     */
    public static final int CU00_NEXT_PAGE_FLG_LENGTH = 1;

    /**
     * {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}.
     */
    public static final int CU00_USR_SEL_FLG_LENGTH = 1;

    /**
     * {@code CDEMO-CU00-USR-SELECTED PIC X(08)}.
     */
    public static final int CU00_USR_SELECTED_LENGTH = 8;

    /**
     * Bytes in {@code 05 CDEMO-CU00-INFO}: 8 + 8 + 8 + 1 + 1 + 8 = 34.
     */
    public static final int CU00_INFO_LENGTH = 34;

    /**
     * Bytes in the {@code CU00} communication area: 160 + {@value #CU00_INFO_LENGTH} = 194.
     */
    public static final int CU00_COMMAREA_LENGTH = 194;

    /**
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COUSR00C.cbl} line 71.
     */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'}, {@code app/cbl/COUSR00C.cbl} line 72, and the {@code VALUE 'N'}
     * clause on the field itself at line 70.
     */
    public static final String NEXT_PAGE_NO = "N";

    /**
     * The row action that selects a user for update: {@link #USR_SEL_UPDATE}.
     */
    public static final String USR_SEL_UPDATE = "U";

    /**
     * The row action that selects a user for deletion: {@link #USR_SEL_DELETE}.
     */
    public static final String USR_SEL_DELETE = "D";

    /**
     * Characters in the {@code EIBAID} token carried by {@link #aid()}: five.
     */
    public static final int AID_LENGTH = 5;

    /**
     * Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field.
     */
    public static final String AID_FIELD = "EIBAID";

    private static final String SPACE = " ";

    private static final String SEL_LABEL_PAD = "00";

    /**
     * Canonical constructor, which normalises absent values and rejects values the screen cannot physically
     * hold.
     */
    public UserListRequest {
        trnName = requirePicX(trnName, TRNNAME_LENGTH, TRNNAME_FIELD);
        title01 = requirePicX(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requirePicX(curDate, CURDATE_LENGTH, CURDATE_FIELD);
        pgmName = requirePicX(pgmName, PGMNAME_LENGTH, PGMNAME_FIELD);
        title02 = requirePicX(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requirePicX(curTime, CURTIME_LENGTH, CURTIME_FIELD);
        pageNum = requirePicX(pageNum, PAGENUM_LENGTH, PAGENUM_FIELD);
        usrIdIn = requirePicX(usrIdIn, USRIDIN_LENGTH, USRIDIN_FIELD);
        rows = requireRows(rows);
        errMsg = requirePicX(errMsg, ERRMSG_LENGTH, ERRMSG_FIELD);
        cdemoCu00UsrIdFirst = requirePicX(cdemoCu00UsrIdFirst, CU00_USRID_FIRST_LENGTH, CU00_USRID_FIRST_FIELD);
        cdemoCu00UsrIdLast = requirePicX(cdemoCu00UsrIdLast, CU00_USRID_LAST_LENGTH, CU00_USRID_LAST_FIELD);
        cdemoCu00PageNum = requireCdemoCu00PageNum(cdemoCu00PageNum);
        cdemoCu00NextPageFlg =
                requirePicX(cdemoCu00NextPageFlg, CU00_NEXT_PAGE_FLG_LENGTH, CU00_NEXT_PAGE_FLG_FIELD);
        cdemoCu00UsrSelFlg = requirePicX(cdemoCu00UsrSelFlg, CU00_USR_SEL_FLG_LENGTH, CU00_USR_SEL_FLG_FIELD);
        cdemoCu00UsrSelected =
                requirePicX(cdemoCu00UsrSelected, CU00_USR_SELECTED_LENGTH, CU00_USR_SELECTED_FIELD);
        aid = requirePicX(aid, AID_LENGTH, AID_FIELD);
    }

    /**
     * A blank screen: every character field its declared width in spaces, the numeric page number
     * {@value #CU00_PAGE_NUM_INITIAL}, the next-page flag at its {@code VALUE} clause default
     * {@link #NEXT_PAGE_NO}, all {@value #ROW_COUNT} rows blank, and a cold-start communication area.
     *
     * @return a blank {@code CU00} request, never {@code null}
     */
    public static UserListRequest empty() {
        return new UserListRequest(spaces(TRNNAME_LENGTH),
                spaces(TITLE01_LENGTH),
                spaces(CURDATE_LENGTH),
                spaces(PGMNAME_LENGTH),
                spaces(TITLE02_LENGTH),
                spaces(CURTIME_LENGTH),
                spaces(PAGENUM_LENGTH),
                spaces(USRIDIN_LENGTH),
                blankRows(),
                spaces(ERRMSG_LENGTH),
                spaces(CU00_USRID_FIRST_LENGTH),
                spaces(CU00_USRID_LAST_LENGTH),
                CU00_PAGE_NUM_INITIAL,
                NEXT_PAGE_NO,
                spaces(CU00_USR_SEL_FLG_LENGTH),
                spaces(CU00_USR_SELECTED_LENGTH),
                NavigationContext.empty(),
                spaces(AID_LENGTH));
    }

    public static List<UserListRow> blankRows() {
        List<UserListRow> blanks = new ArrayList<>(ROW_COUNT);
        for (int index = 0; index < ROW_COUNT; index++) {
            blanks.add(UserListRow.blank());
        }
        return List.copyOf(blanks);
    }

    /**
     * One row of the user list - the five repeating screen fields at a single line of the map.
     *
     * <p>{@code POPULATE-USER-DATA} writes {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and
     * {@code UTYPEnnI} and never {@code SEL000nI}; neither does {@code INITIALIZE-USER-DATA}.
     *
     * @param sel {@code SEL000nI PIC X(1)}: the row action the user typed, blank when untouched
     * @param usrId {@code USRIDnnI PIC X(8)}: the user id, from {@code SEC-USR-ID}
     * @param fname {@code FNAMEnnI PIC X(20)}: the first name, from {@code SEC-USR-FNAME}
     * @param lname {@code LNAMEnnI PIC X(20)}: the last name, from {@code SEC-USR-LNAME}
     * @param utype {@code UTYPEnnI PIC X(1)}: the user type, from {@code SEC-USR-TYPE}
     */
    public record UserListRow(
            @Size(max = UserListRequest.SEL_LENGTH) String sel,
            @Size(max = UserListRequest.USRID_LENGTH) String usrId,
            @Size(max = UserListRequest.FNAME_LENGTH) String fname,
            @Size(max = UserListRequest.LNAME_LENGTH) String lname,
            @Size(max = UserListRequest.UTYPE_LENGTH) String utype) {
        public UserListRow {
            sel = requirePicX(sel, SEL_LENGTH, SEL_FIELD_STEM);
            usrId = requirePicX(usrId, USRID_LENGTH, USRID_FIELD_STEM);
            fname = requirePicX(fname, FNAME_LENGTH, FNAME_FIELD_STEM);
            lname = requirePicX(lname, LNAME_LENGTH, LNAME_FIELD_STEM);
            utype = requirePicX(utype, UTYPE_LENGTH, UTYPE_FIELD_STEM);
        }

        /**
         * A blank row: each of the five fields its declared width in spaces.
         *
         * <p>{@code INITIALIZE-USER-DATA} blanks four of the five; the selection column is blanked as well
         * here because a row that has never been rendered has nothing typed in it either.
         *
         * @return a blank row, never {@code null}
         */
        public static UserListRow blank() {
            return new UserListRow(spaces(SEL_LENGTH),
                    spaces(USRID_LENGTH),
                    spaces(FNAME_LENGTH),
                    spaces(LNAME_LENGTH),
                    spaces(UTYPE_LENGTH));
        }

        public UserListRow withSel(String newSel) {
            return new UserListRow(newSel, usrId, fname, lname, utype);
        }

        public UserListRow withUsrId(String newUsrId) {
            return new UserListRow(sel, newUsrId, fname, lname, utype);
        }

        public UserListRow withFname(String newFname) {
            return new UserListRow(sel, usrId, newFname, lname, utype);
        }

        public UserListRow withLname(String newLname) {
            return new UserListRow(sel, usrId, fname, newLname, utype);
        }

        public UserListRow withUtype(String newUtype) {
            return new UserListRow(sel, usrId, fname, lname, newUtype);
        }

        /**
         * A diagnostic rendering that withholds the listed user's name, per {@link SensitiveDiagnostics}.
         *
         * @return a rendering safe to log, never {@code null}
         */
        @Override
        public String toString() {
            return "UserListRow[sel='" + sel
                    + "', usrId='" + usrId
                    + "', fname=" + SensitiveDiagnostics.describeText(fname)
                    + ", lname=" + SensitiveDiagnostics.describeText(lname)
                    + ", utype='" + utype
                    + "']";
        }
}

    // Row access by the one-based COBOL row number, so translated code can keep the WS-IDX values its
    // paragraph used and the zero-based subtraction happens in exactly two places.

    /**
     * The row at the one-based {@code WS-IDX} position, counting from 1 as {@code POPULATE-USER-DATA} does.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return that row, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public UserListRow row(int rowNumber) {
        return rows.get(requireRowNumber(rowNumber) - 1);
    }

    /**
     * This request with one row replaced, addressed by its one-based {@code WS-IDX} number.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @param newRow the replacement row, {@code null} meaning {@link UserListRow#blank()}
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public UserListRequest withRow(int rowNumber, UserListRow newRow) {
        List<UserListRow> replaced = new ArrayList<>(rows);
        replaced.set(requireRowNumber(rowNumber) - 1, newRow);
        return withRows(replaced);
    }

    /**
     * Whether {@code 88 NEXT-PAGE-YES VALUE 'Y'} holds - that is, whether {@link #cdemoCu00NextPageFlg()}
     * is exactly {@link #NEXT_PAGE_YES}.
     *
     * @return {@code true} when the flag is {@link #NEXT_PAGE_YES}
     */
    public boolean nextPageYes() {
        return NEXT_PAGE_YES.equals(cdemoCu00NextPageFlg);
    }

    /**
     * Whether {@code 88 NEXT-PAGE-NO VALUE 'N'} holds - that is, whether {@link #cdemoCu00NextPageFlg()} is
     * exactly {@link #NEXT_PAGE_NO}.
     *
     * @return {@code true} when the flag is {@link #NEXT_PAGE_NO}
     */
    public boolean nextPageNo() {
        return NEXT_PAGE_NO.equals(cdemoCu00NextPageFlg);
    }

    /**
     * Whether the captured row action selects a user for update - {@link #cdemoCu00UsrSelFlg()} equal to
     * {@link #USR_SEL_UPDATE} in either case.
     *
     * @return {@code true} when the flag is {@code U} or {@code u}
     */
    public boolean usrSelUpdate() {
        return USR_SEL_UPDATE.equalsIgnoreCase(cdemoCu00UsrSelFlg);
    }

    /**
     * Whether the captured row action selects a user for deletion - {@link #cdemoCu00UsrSelFlg()} equal to
     * {@link #USR_SEL_DELETE} in either case.
     *
     * @return {@code true} when the flag is {@code D} or {@code d}
     */
    public boolean usrSelDelete() {
        return USR_SEL_DELETE.equalsIgnoreCase(cdemoCu00UsrSelFlg);
    }

    /**
     * The selection label for a row: {@code SEL0001} through {@code SEL0010}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String selFieldName(int rowNumber) {
        return SEL_FIELD_STEM + SEL_LABEL_PAD + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The user id label for a row: {@code USRID01} through {@code USRID10} - {@value #ROW_FIELD_DIGITS}
     * digits, unlike the selection column's {@value #SEL_FIELD_DIGITS}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String usrIdFieldName(int rowNumber) {
        return USRID_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The first name label for a row: {@code FNAME01} through {@code FNAME10}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String fnameFieldName(int rowNumber) {
        return FNAME_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The last name label for a row: {@code LNAME01} through {@code LNAME10}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String lnameFieldName(int rowNumber) {
        return LNAME_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * The user type label for a row: {@code UTYPE01} through {@code UTYPE10}.
     *
     * @param rowNumber the one-based row number, 1 to {@value #ROW_COUNT} inclusive
     * @return the label, never {@code null}
     * @throws IllegalArgumentException if {@code rowNumber} is outside 1 to {@value #ROW_COUNT}
     */
    public static String utypeFieldName(int rowNumber) {
        return UTYPE_FIELD_STEM + twoDigits(requireRowNumber(rowNumber));
    }

    /**
     * All {@value #MAP_FIELD_COUNT} screen field labels, in the order {@code app/cpy-bms/COUSR00.CPY}
     * declares them and {@code app/bms/COUSR00.bms} lays them out: the {@value #HEADER_FIELD_COUNT} header
     * fields, then the {@value #ROW_COUNT} rows of {@value #ROW_FIELD_COUNT}, then {@code ERRMSG}.
     *
     * @return the label list, never {@code null}, unmodifiable, exactly {@value #MAP_FIELD_COUNT} entries
     */
    public static List<String> mapFieldNames() {
        List<String> names = new ArrayList<>(MAP_FIELD_COUNT);
        names.add(TRNNAME_FIELD);
        names.add(TITLE01_FIELD);
        names.add(CURDATE_FIELD);
        names.add(PGMNAME_FIELD);
        names.add(TITLE02_FIELD);
        names.add(CURTIME_FIELD);
        names.add(PAGENUM_FIELD);
        names.add(USRIDIN_FIELD);
        for (int rowNumber = 1; rowNumber <= ROW_COUNT; rowNumber++) {
            names.add(selFieldName(rowNumber));
            names.add(usrIdFieldName(rowNumber));
            names.add(fnameFieldName(rowNumber));
            names.add(lnameFieldName(rowNumber));
            names.add(utypeFieldName(rowNumber));
        }
        names.add(ERRMSG_FIELD);
        return List.copyOf(names);
    }

    /**
     * This payload as a field-for-field view of the screen: all {@value #MAP_FIELD_COUNT} screen fields
     * keyed by their map label, in map declaration order.
     *
     * <p>Field-for-field is how the migration's parity check compares a Java result against the COBOL
     * contract, so this method exists to make that comparison possible without reflection and to make the
     * {@value #MAP_FIELD_COUNT} count assertable rather than assumed.
     *
     * @return the screen fields, never {@code null}, unmodifiable, iterating in map order with exactly
     *     {@value #MAP_FIELD_COUNT} entries
     */
    public Map<String, String> mapFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(TRNNAME_FIELD, trnName);
        fields.put(TITLE01_FIELD, title01);
        fields.put(CURDATE_FIELD, curDate);
        fields.put(PGMNAME_FIELD, pgmName);
        fields.put(TITLE02_FIELD, title02);
        fields.put(CURTIME_FIELD, curTime);
        fields.put(PAGENUM_FIELD, pageNum);
        fields.put(USRIDIN_FIELD, usrIdIn);
        for (int rowNumber = 1; rowNumber <= ROW_COUNT; rowNumber++) {
            UserListRow current = rows.get(rowNumber - 1);
            fields.put(selFieldName(rowNumber), current.sel());
            fields.put(usrIdFieldName(rowNumber), current.usrId());
            fields.put(fnameFieldName(rowNumber), current.fname());
            fields.put(lnameFieldName(rowNumber), current.lname());
            fields.put(utypeFieldName(rowNumber), current.utype());
        }
        fields.put(ERRMSG_FIELD, errMsg);
        return Collections.unmodifiableMap(fields);
    }

    // Each is its own name-labelled DFHMDF in app/bms/COUSR00.bms, so each is its own payload member - and
    // each must appear on the wire under its own name, because that name is the only thing tying a payload
    // field back to a screen field.
    /**
     * {@code SEL0001} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 1, never {@code null}
     */
    @JsonProperty("sel0001")
    public String sel0001() {
        return row(1).sel();
    }
    @JsonProperty("usrid01")
    public String usrId01() {
        return row(1).usrId();
    }
    @JsonProperty("fname01")
    public String fname01() {
        return row(1).fname();
    }
    @JsonProperty("lname01")
    public String lname01() {
        return row(1).lname();
    }
    @JsonProperty("utype01")
    public String utype01() {
        return row(1).utype();
    }
    /**
     * {@code SEL0002} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 2, never {@code null}
     */
    @JsonProperty("sel0002")
    public String sel0002() {
        return row(2).sel();
    }
    @JsonProperty("usrid02")
    public String usrId02() {
        return row(2).usrId();
    }
    @JsonProperty("fname02")
    public String fname02() {
        return row(2).fname();
    }
    @JsonProperty("lname02")
    public String lname02() {
        return row(2).lname();
    }
    @JsonProperty("utype02")
    public String utype02() {
        return row(2).utype();
    }
    /**
     * {@code SEL0003} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 3, never {@code null}
     */
    @JsonProperty("sel0003")
    public String sel0003() {
        return row(3).sel();
    }
    @JsonProperty("usrid03")
    public String usrId03() {
        return row(3).usrId();
    }
    @JsonProperty("fname03")
    public String fname03() {
        return row(3).fname();
    }
    @JsonProperty("lname03")
    public String lname03() {
        return row(3).lname();
    }
    @JsonProperty("utype03")
    public String utype03() {
        return row(3).utype();
    }
    /**
     * {@code SEL0004} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 4, never {@code null}
     */
    @JsonProperty("sel0004")
    public String sel0004() {
        return row(4).sel();
    }
    @JsonProperty("usrid04")
    public String usrId04() {
        return row(4).usrId();
    }
    @JsonProperty("fname04")
    public String fname04() {
        return row(4).fname();
    }
    @JsonProperty("lname04")
    public String lname04() {
        return row(4).lname();
    }
    @JsonProperty("utype04")
    public String utype04() {
        return row(4).utype();
    }
    /**
     * {@code SEL0005} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 5, never {@code null}
     */
    @JsonProperty("sel0005")
    public String sel0005() {
        return row(5).sel();
    }
    @JsonProperty("usrid05")
    public String usrId05() {
        return row(5).usrId();
    }
    @JsonProperty("fname05")
    public String fname05() {
        return row(5).fname();
    }
    @JsonProperty("lname05")
    public String lname05() {
        return row(5).lname();
    }
    @JsonProperty("utype05")
    public String utype05() {
        return row(5).utype();
    }
    /**
     * {@code SEL0006} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 6, never {@code null}
     */
    @JsonProperty("sel0006")
    public String sel0006() {
        return row(6).sel();
    }
    @JsonProperty("usrid06")
    public String usrId06() {
        return row(6).usrId();
    }
    @JsonProperty("fname06")
    public String fname06() {
        return row(6).fname();
    }
    @JsonProperty("lname06")
    public String lname06() {
        return row(6).lname();
    }
    @JsonProperty("utype06")
    public String utype06() {
        return row(6).utype();
    }
    /**
     * {@code SEL0007} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 7, never {@code null}
     */
    @JsonProperty("sel0007")
    public String sel0007() {
        return row(7).sel();
    }
    @JsonProperty("usrid07")
    public String usrId07() {
        return row(7).usrId();
    }
    @JsonProperty("fname07")
    public String fname07() {
        return row(7).fname();
    }
    @JsonProperty("lname07")
    public String lname07() {
        return row(7).lname();
    }
    @JsonProperty("utype07")
    public String utype07() {
        return row(7).utype();
    }
    /**
     * {@code SEL0008} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 8, never {@code null}
     */
    @JsonProperty("sel0008")
    public String sel0008() {
        return row(8).sel();
    }
    @JsonProperty("usrid08")
    public String usrId08() {
        return row(8).usrId();
    }
    @JsonProperty("fname08")
    public String fname08() {
        return row(8).fname();
    }
    @JsonProperty("lname08")
    public String lname08() {
        return row(8).lname();
    }
    @JsonProperty("utype08")
    public String utype08() {
        return row(8).utype();
    }
    /**
     * {@code SEL0009} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 9, never {@code null}
     */
    @JsonProperty("sel0009")
    public String sel0009() {
        return row(9).sel();
    }
    @JsonProperty("usrid09")
    public String usrId09() {
        return row(9).usrId();
    }
    @JsonProperty("fname09")
    public String fname09() {
        return row(9).fname();
    }
    @JsonProperty("lname09")
    public String lname09() {
        return row(9).lname();
    }
    @JsonProperty("utype09")
    public String utype09() {
        return row(9).utype();
    }
    /**
     * {@code SEL0010} - the selection column, the one field on this row the operator types into.
     *
     * @return the value as stored on row 10, never {@code null}
     */
    @JsonProperty("sel0010")
    public String sel0010() {
        return row(10).sel();
    }
    @JsonProperty("usrid10")
    public String usrId10() {
        return row(10).usrId();
    }
    @JsonProperty("fname10")
    public String fname10() {
        return row(10).fname();
    }
    @JsonProperty("lname10")
    public String lname10() {
        return row(10).lname();
    }
    @JsonProperty("utype10")
    public String utype10() {
        return row(10).utype();
    }
    /**
     * Builds a request from the wire form: the fifty numbered row members, flat, exactly as
     * {@code app/cpy-bms/COUSR00.CPY} names them and in its declaration order.
     *
     * <p>An absent member arrives as {@code null} and the canonical constructor blanks it to its declared
     * width in spaces, which is what CICS transmits for a field the operator never touched.
     *
     * @param trnName {@code TRNNAMEI}
     * @param title01 {@code TITLE01I}
     * @param curDate {@code CURDATEI}
     * @param pgmName {@code PGMNAMEI}
     * @param title02 {@code TITLE02I}
     * @param curTime {@code CURTIMEI}
     * @param pageNum {@code PAGENUMI}
     * @param usrIdIn {@code USRIDINI}
     * @param sel0001 {@code SEL0001I}, the row 1 selection column
     * @param usrId01 {@code USRID01I}
     * @param fname01 {@code FNAME01I}
     * @param lname01 {@code LNAME01I}
     * @param utype01 {@code UTYPE01I}
     * @param sel0002 {@code SEL0002I}
     * @param usrId02 {@code USRID02I}
     * @param fname02 {@code FNAME02I}
     * @param lname02 {@code LNAME02I}
     * @param utype02 {@code UTYPE02I}
     * @param sel0003 {@code SEL0003I}
     * @param usrId03 {@code USRID03I}
     * @param fname03 {@code FNAME03I}
     * @param lname03 {@code LNAME03I}
     * @param utype03 {@code UTYPE03I}
     * @param sel0004 {@code SEL0004I}
     * @param usrId04 {@code USRID04I}
     * @param fname04 {@code FNAME04I}
     * @param lname04 {@code LNAME04I}
     * @param utype04 {@code UTYPE04I}
     * @param sel0005 {@code SEL0005I}
     * @param usrId05 {@code USRID05I}
     * @param fname05 {@code FNAME05I}
     * @param lname05 {@code LNAME05I}
     * @param utype05 {@code UTYPE05I}
     * @param sel0006 {@code SEL0006I}
     * @param usrId06 {@code USRID06I}
     * @param fname06 {@code FNAME06I}
     * @param lname06 {@code LNAME06I}
     * @param utype06 {@code UTYPE06I}
     * @param sel0007 {@code SEL0007I}
     * @param usrId07 {@code USRID07I}
     * @param fname07 {@code FNAME07I}
     * @param lname07 {@code LNAME07I}
     * @param utype07 {@code UTYPE07I}
     * @param sel0008 {@code SEL0008I}
     * @param usrId08 {@code USRID08I}
     * @param fname08 {@code FNAME08I}
     * @param lname08 {@code LNAME08I}
     * @param utype08 {@code UTYPE08I}
     * @param sel0009 {@code SEL0009I}
     * @param usrId09 {@code USRID09I}
     * @param fname09 {@code FNAME09I}
     * @param lname09 {@code LNAME09I}
     * @param utype09 {@code UTYPE09I}
     * @param sel0010 {@code SEL0010I}, the row 10 selection column
     * @param usrId10 {@code USRID10I}
     * @param fname10 {@code FNAME10I}
     * @param lname10 {@code LNAME10I}
     * @param utype10 {@code UTYPE10I}
     * @param errMsg {@code ERRMSGI}
     * @param cdemoCu00UsrIdFirst {@code CDEMO-CU00-USRID-FIRST}
     * @param cdemoCu00UsrIdLast {@code CDEMO-CU00-USRID-LAST}
     * @param cdemoCu00PageNum {@code CDEMO-CU00-PAGE-NUM}
     * @param cdemoCu00NextPageFlg {@code CDEMO-CU00-NEXT-PAGE-FLG}
     * @param cdemoCu00UsrSelFlg {@code CDEMO-CU00-USR-SEL-FLG}
     * @param cdemoCu00UsrSelected {@code CDEMO-CU00-USR-SELECTED}
     * @param navigationContext {@code 01 CARDDEMO-COMMAREA}, or {@code null} for the {@code EIBCALEN = 0}
     *     cold start
     * @param aid the {@code EIBAID} token
     * @return the request, never {@code null}
     * @throws IllegalArgumentException if any value exceeds its declared width
     */
    @JsonCreator
    public static UserListRequest fromWire(
            @JsonProperty("trnname") String trnName,
            @JsonProperty("title01") String title01,
            @JsonProperty("curdate") String curDate,
            @JsonProperty("pgmname") String pgmName,
            @JsonProperty("title02") String title02,
            @JsonProperty("curtime") String curTime,
            @JsonProperty("pagenum") String pageNum,
            @JsonProperty("usridin") String usrIdIn,
            @JsonProperty("sel0001") String sel0001,
            @JsonProperty("usrid01") String usrId01,
            @JsonProperty("fname01") String fname01,
            @JsonProperty("lname01") String lname01,
            @JsonProperty("utype01") String utype01,
            @JsonProperty("sel0002") String sel0002,
            @JsonProperty("usrid02") String usrId02,
            @JsonProperty("fname02") String fname02,
            @JsonProperty("lname02") String lname02,
            @JsonProperty("utype02") String utype02,
            @JsonProperty("sel0003") String sel0003,
            @JsonProperty("usrid03") String usrId03,
            @JsonProperty("fname03") String fname03,
            @JsonProperty("lname03") String lname03,
            @JsonProperty("utype03") String utype03,
            @JsonProperty("sel0004") String sel0004,
            @JsonProperty("usrid04") String usrId04,
            @JsonProperty("fname04") String fname04,
            @JsonProperty("lname04") String lname04,
            @JsonProperty("utype04") String utype04,
            @JsonProperty("sel0005") String sel0005,
            @JsonProperty("usrid05") String usrId05,
            @JsonProperty("fname05") String fname05,
            @JsonProperty("lname05") String lname05,
            @JsonProperty("utype05") String utype05,
            @JsonProperty("sel0006") String sel0006,
            @JsonProperty("usrid06") String usrId06,
            @JsonProperty("fname06") String fname06,
            @JsonProperty("lname06") String lname06,
            @JsonProperty("utype06") String utype06,
            @JsonProperty("sel0007") String sel0007,
            @JsonProperty("usrid07") String usrId07,
            @JsonProperty("fname07") String fname07,
            @JsonProperty("lname07") String lname07,
            @JsonProperty("utype07") String utype07,
            @JsonProperty("sel0008") String sel0008,
            @JsonProperty("usrid08") String usrId08,
            @JsonProperty("fname08") String fname08,
            @JsonProperty("lname08") String lname08,
            @JsonProperty("utype08") String utype08,
            @JsonProperty("sel0009") String sel0009,
            @JsonProperty("usrid09") String usrId09,
            @JsonProperty("fname09") String fname09,
            @JsonProperty("lname09") String lname09,
            @JsonProperty("utype09") String utype09,
            @JsonProperty("sel0010") String sel0010,
            @JsonProperty("usrid10") String usrId10,
            @JsonProperty("fname10") String fname10,
            @JsonProperty("lname10") String lname10,
            @JsonProperty("utype10") String utype10,
            @JsonProperty("errmsg") String errMsg,
            @JsonProperty("cdemoCu00UsrIdFirst") String cdemoCu00UsrIdFirst,
            @JsonProperty("cdemoCu00UsrIdLast") String cdemoCu00UsrIdLast,
            @JsonProperty("cdemoCu00PageNum") int cdemoCu00PageNum,
            @JsonProperty("cdemoCu00NextPageFlg") String cdemoCu00NextPageFlg,
            @JsonProperty("cdemoCu00UsrSelFlg") String cdemoCu00UsrSelFlg,
            @JsonProperty("cdemoCu00UsrSelected") String cdemoCu00UsrSelected,
            @JsonProperty("navigationContext") NavigationContext navigationContext,
            @JsonProperty("aid") String aid) {
        List<UserListRow> wireRows = new ArrayList<>(ROW_COUNT);
        wireRows.add(new UserListRow(sel0001, usrId01, fname01, lname01, utype01));
        wireRows.add(new UserListRow(sel0002, usrId02, fname02, lname02, utype02));
        wireRows.add(new UserListRow(sel0003, usrId03, fname03, lname03, utype03));
        wireRows.add(new UserListRow(sel0004, usrId04, fname04, lname04, utype04));
        wireRows.add(new UserListRow(sel0005, usrId05, fname05, lname05, utype05));
        wireRows.add(new UserListRow(sel0006, usrId06, fname06, lname06, utype06));
        wireRows.add(new UserListRow(sel0007, usrId07, fname07, lname07, utype07));
        wireRows.add(new UserListRow(sel0008, usrId08, fname08, lname08, utype08));
        wireRows.add(new UserListRow(sel0009, usrId09, fname09, lname09, utype09));
        wireRows.add(new UserListRow(sel0010, usrId10, fname10, lname10, utype10));
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, wireRows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext,
                aid);
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COUSR00C.cbl:110-120}.
     *
     * @return {@code true} when {@link #navigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value #CU00_COMMAREA_LENGTH} when a communication
     * area travelled with this request, and {@code 0} when none did.
     *
     * @return {@value #CU00_COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? CU00_COMMAREA_LENGTH : 0;
    }

    /**
     * A copy carrying no communication area at all - the {@code EIBCALEN = 0} cold start that
     * {@code app/cbl/COUSR00C.cbl:113} tests before anything else, on which it moves {@code 'COSGN00C'}
     * into {@code CDEMO-TO-PROGRAM} and returns to the sign-on screen.
     *
     * @return a new request whose {@link #navigationContext()} is {@code null}
     */
    public UserListRequest withoutNavigationContext() {
        return withNavigationContext(null);
    }

    public UserListRequest withTrnName(String newTrnName) {
        return new UserListRequest(newTrnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    public UserListRequest withTitle01(String newTitle01) {
        return new UserListRequest(trnName, newTitle01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    public UserListRequest withCurDate(String newCurDate) {
        return new UserListRequest(trnName, title01, newCurDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    public UserListRequest withPgmName(String newPgmName) {
        return new UserListRequest(trnName, title01, curDate, newPgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    public UserListRequest withTitle02(String newTitle02) {
        return new UserListRequest(trnName, title01, curDate, pgmName, newTitle02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    public UserListRequest withCurTime(String newCurTime) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, newCurTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different displayed page number, the character field {@code PAGENUM}.
     *
     * @param newPageNum the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #PAGENUM_LENGTH} characters
     */
    public UserListRequest withPageNum(String newPageNum) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, newPageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg,
                cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different browse-start user id, {@code USRIDIN}.
     *
     * @param newUsrIdIn the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #USRIDIN_LENGTH} characters
     */
    public UserListRequest withUsrIdIn(String newUsrIdIn) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                newUsrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    public UserListRequest withRows(List<UserListRow> newRows) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, newRows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different message line, {@code ERRMSG}.
     *
     * <p>The field is {@value #ERRMSG_LENGTH} characters while {@code WS-MESSAGE} is eighty, so a caller
     * holding an eighty-character message must narrow it deliberately through
     * {@code common.FixedWidthCodec.movePicX}, reproducing the truncation {@code app/cbl/COUSR00C.cbl} line
     * 526 performs.
     *
     * @param newErrMsg the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #ERRMSG_LENGTH} characters
     */
    public UserListRequest withErrMsg(String newErrMsg) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, newErrMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CDEMO-CU00-USRID-FIRST}.
     *
     * @param newCdemoCu00UsrIdFirst the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USRID_FIRST_LENGTH} characters
     */
    public UserListRequest withCdemoCu00UsrIdFirst(String newCdemoCu00UsrIdFirst) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, newCdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CDEMO-CU00-USRID-LAST}.
     *
     * @param newCdemoCu00UsrIdLast the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USRID_LAST_LENGTH} characters
     */
    public UserListRequest withCdemoCu00UsrIdLast(String newCdemoCu00UsrIdLast) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, newCdemoCu00UsrIdLast, cdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different numeric page number, {@code CDEMO-CU00-PAGE-NUM}.
     *
     * @param newCdemoCu00PageNum the replacement page number, {@value #CU00_PAGE_NUM_INITIAL} being both
     *     legitimate and the initial value
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if negative or greater than {@value #CU00_PAGE_NUM_MAX}
     */
    public UserListRequest withCdemoCu00PageNum(int newCdemoCu00PageNum) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, newCdemoCu00PageNum,
                cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different {@code CDEMO-CU00-NEXT-PAGE-FLG}.
     *
     * @param newCdemoCu00NextPageFlg the replacement flag, normally {@link #NEXT_PAGE_YES} or
     *     {@link #NEXT_PAGE_NO}; {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_NEXT_PAGE_FLG_LENGTH} character
     */
    public UserListRequest withCdemoCu00NextPageFlg(String newCdemoCu00NextPageFlg) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                newCdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with the next-page flag set, the Java form of {@code SET NEXT-PAGE-YES TO TRUE}.
     *
     * @return a new request whose {@link #nextPageYes()} is {@code true}, never {@code null}
     */
    public UserListRequest withNextPageYes() {
        return withCdemoCu00NextPageFlg(NEXT_PAGE_YES);
    }

    /**
     * This request with the next-page flag cleared, the Java form of {@code SET NEXT-PAGE-NO TO TRUE} -
     * which {@code app/cbl/COUSR00C.cbl} performs at line 106, and again at lines 314 and 318 when a
     * read-ahead finds nothing.
     *
     * @return a new request whose {@link #nextPageNo()} is {@code true}, never {@code null}
     */
    public UserListRequest withNextPageNo() {
        return withCdemoCu00NextPageFlg(NEXT_PAGE_NO);
    }

    /**
     * This request with a different captured row action, {@code CDEMO-CU00-USR-SEL-FLG}.
     *
     * @param newCdemoCu00UsrSelFlg the replacement action, normally {@link #USR_SEL_UPDATE} or
     *     {@link #USR_SEL_DELETE} in either case; {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USR_SEL_FLG_LENGTH} character
     */
    public UserListRequest withCdemoCu00UsrSelFlg(String newCdemoCu00UsrSelFlg) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg,
                newCdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different selected user id, {@code CDEMO-CU00-USR-SELECTED}.
     *
     * @param newCdemoCu00UsrSelected the replacement value, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #CU00_USR_SELECTED_LENGTH} characters
     */
    public UserListRequest withCdemoCu00UsrSelected(String newCdemoCu00UsrSelected) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg,
                cdemoCu00UsrSelFlg, newCdemoCu00UsrSelected, navigationContext, aid);
    }

    /**
     * This request with a different communication area.
     *
     * @param newNavigationContext the replacement communication area, {@code null} meaning the cold-start
     *     area
     * @return a new request, never {@code null}
     */
    public UserListRequest withNavigationContext(NavigationContext newNavigationContext) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg,
                cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, newNavigationContext, aid);
    }

    /**
     * This request with a different key indication.
     *
     * @param newAid the replacement {@code EIBAID} token, {@code null} meaning blank
     * @return a new request, never {@code null}
     * @throws IllegalArgumentException if longer than {@value #AID_LENGTH} characters
     */
    public UserListRequest withAid(String newAid) {
        return new UserListRequest(trnName, title01, curDate, pgmName, title02, curTime, pageNum,
                usrIdIn, rows, errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast,
                cdemoCu00PageNum, cdemoCu00NextPageFlg,
                cdemoCu00UsrSelFlg, cdemoCu00UsrSelected, navigationContext, newAid);
    }

    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    private static String requirePicX(String value, int declaredWidth, String mapField) {
        if (value == null) {
            return spaces(declaredWidth);
        }
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Screen field " + mapField + " of mapset " + MAPSET
                    + " is declared PIC X(" + declaredWidth + ") and DFHMDF LENGTH=" + declaredWidth
                    + ", but was given " + value.length() + " character(s): '" + value + "'. A BMS "
                    + "field cannot hold the surplus. To shorten the value deliberately, pass it "
                    + "through FixedWidthCodec.movePicX(value, " + declaredWidth + "), which "
                    + "truncates on the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    /**
     * Rejects a page number {@code PIC 9(08)} cannot represent, returning it unchanged when it fits.
     *
     * @param value the value being stored
     */
    private static int requireCdemoCu00PageNum(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Field " + CU00_PAGE_NUM_FIELD + " is declared PIC 9("
                    + CU00_PAGE_NUM_LENGTH + "), which is unsigned and has no sign position, so it "
                    + "cannot hold the negative value " + value);
        }
        if (value > CU00_PAGE_NUM_MAX) {
            throw new IllegalArgumentException("Field " + CU00_PAGE_NUM_FIELD + " is declared PIC 9("
                    + CU00_PAGE_NUM_LENGTH + ") and cannot hold " + value + ", which needs more than "
                    + CU00_PAGE_NUM_LENGTH + " digits; the largest representable value is "
                    + CU00_PAGE_NUM_MAX);
        }
        return value;
    }

    private static List<UserListRow> requireRows(List<UserListRow> rows) {
        if (rows == null) {
            return blankRows();
        }
        if (rows.size() != ROW_COUNT) {
            throw new IllegalArgumentException("Mapset " + MAPSET + " declares exactly " + ROW_COUNT
                    + " screen rows, so " + ROW_COUNT + " row(s) are required, but " + rows.size()
                    + " were given. The row count is behaviour fixed by the map and by COUSR00C's "
                    + "loop bounds, not a configurable page size");
        }
        List<UserListRow> copy = new ArrayList<>(ROW_COUNT);
        for (UserListRow current : rows) {
            if (current == null) {
                copy.add(UserListRow.blank());
            } else {
                copy.add(current);
            }
        }
        return List.copyOf(copy);
    }

    private static int requireRowNumber(int rowNumber) {
        if (rowNumber < 1 || rowNumber > ROW_COUNT) {
            throw new IllegalArgumentException("Row number must be between 1 and " + ROW_COUNT
                    + " inclusive, counting from 1 as COUSR00C's WS-IDX does, but was " + rowNumber
                    + ". Java list indices are 0 to " + (ROW_COUNT - 1) + "; use rows() directly for "
                    + "those");
        }
        return rowNumber;
    }

    private static String twoDigits(int rowNumber) {
        if (rowNumber < 10) {
            return "0" + rowNumber;
        }
        return Integer.toString(rowNumber);
    }
}
