package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import java.util.List;
import java.util.Objects;

/**
 * The outbound payload of {@code GET /api/users} - CICS transaction {@code CU00}, program
 * {@code app/cbl/COUSR00C.cbl} - as an immutable value.
 *
 * <p>Three independent measurements of this screen agree, and that agreement is the whole basis for the
 * count: {@code app/bms/COUSR00.bms} declares 89 {@code DFHMDF} fields, of which exactly
 * {@value #MAP_FIELD_COUNT} carry a name label.
 *
 * @param trnName {@code TRNNAMEO PIC X(4)}: the transaction identifier on the screen
 * @param title01 {@code TITLE01O PIC X(40)}: the first title line
 * @param curDate {@code CURDATEO PIC X(8)}: the current date, {@code mm/dd/yy}
 * @param pgmName {@code PGMNAMEO PIC X(8)}: the program name on the screen
 * @param title02 {@code TITLE02O PIC X(40)}: the second title line
 * @param curTime {@code CURTIMEO PIC X(8)}: the current time, {@code hh:mm:ss}
 * @param pageNum {@code PAGENUMO PIC X(8)}: the displayed page number, as characters
 * @param usrIdIn {@code USRIDINO PIC X(8)}: the browse-start user identifier
 * @param sel0001 {@code SEL0001O PIC X(1)}: row 1 selection, echoed input
 * @param usrId01 {@code USRID01O PIC X(8)}: row 1 {@code SEC-USR-ID}
 * @param fname01 {@code FNAME01O PIC X(20)}: row 1 {@code SEC-USR-FNAME}
 * @param lname01 {@code LNAME01O PIC X(20)}: row 1 {@code SEC-USR-LNAME}
 * @param utype01 {@code UTYPE01O PIC X(1)}: row 1 {@code SEC-USR-TYPE}
 * @param sel0002 {@code SEL0002O PIC X(1)}: row 2 selection, echoed input
 * @param usrId02 {@code USRID02O PIC X(8)}: row 2 {@code SEC-USR-ID}
 * @param fname02 {@code FNAME02O PIC X(20)}: row 2 {@code SEC-USR-FNAME}
 * @param lname02 {@code LNAME02O PIC X(20)}: row 2 {@code SEC-USR-LNAME}
 * @param utype02 {@code UTYPE02O PIC X(1)}: row 2 {@code SEC-USR-TYPE}
 * @param sel0003 {@code SEL0003O PIC X(1)}: row 3 selection, echoed input
 * @param usrId03 {@code USRID03O PIC X(8)}: row 3 {@code SEC-USR-ID}
 * @param fname03 {@code FNAME03O PIC X(20)}: row 3 {@code SEC-USR-FNAME}
 * @param lname03 {@code LNAME03O PIC X(20)}: row 3 {@code SEC-USR-LNAME}
 * @param utype03 {@code UTYPE03O PIC X(1)}: row 3 {@code SEC-USR-TYPE}
 * @param sel0004 {@code SEL0004O PIC X(1)}: row 4 selection, echoed input
 * @param usrId04 {@code USRID04O PIC X(8)}: row 4 {@code SEC-USR-ID}
 * @param fname04 {@code FNAME04O PIC X(20)}: row 4 {@code SEC-USR-FNAME}
 * @param lname04 {@code LNAME04O PIC X(20)}: row 4 {@code SEC-USR-LNAME}
 * @param utype04 {@code UTYPE04O PIC X(1)}: row 4 {@code SEC-USR-TYPE}
 * @param sel0005 {@code SEL0005O PIC X(1)}: row 5 selection, echoed input
 * @param usrId05 {@code USRID05O PIC X(8)}: row 5 {@code SEC-USR-ID}
 * @param fname05 {@code FNAME05O PIC X(20)}: row 5 {@code SEC-USR-FNAME}
 * @param lname05 {@code LNAME05O PIC X(20)}: row 5 {@code SEC-USR-LNAME}
 * @param utype05 {@code UTYPE05O PIC X(1)}: row 5 {@code SEC-USR-TYPE}
 * @param sel0006 {@code SEL0006O PIC X(1)}: row 6 selection, echoed input
 * @param usrId06 {@code USRID06O PIC X(8)}: row 6 {@code SEC-USR-ID}
 * @param fname06 {@code FNAME06O PIC X(20)}: row 6 {@code SEC-USR-FNAME}
 * @param lname06 {@code LNAME06O PIC X(20)}: row 6 {@code SEC-USR-LNAME}
 * @param utype06 {@code UTYPE06O PIC X(1)}: row 6 {@code SEC-USR-TYPE}
 * @param sel0007 {@code SEL0007O PIC X(1)}: row 7 selection, echoed input
 * @param usrId07 {@code USRID07O PIC X(8)}: row 7 {@code SEC-USR-ID}
 * @param fname07 {@code FNAME07O PIC X(20)}: row 7 {@code SEC-USR-FNAME}
 * @param lname07 {@code LNAME07O PIC X(20)}: row 7 {@code SEC-USR-LNAME}
 * @param utype07 {@code UTYPE07O PIC X(1)}: row 7 {@code SEC-USR-TYPE}
 * @param sel0008 {@code SEL0008O PIC X(1)}: row 8 selection, echoed input
 * @param usrId08 {@code USRID08O PIC X(8)}: row 8 {@code SEC-USR-ID}
 * @param fname08 {@code FNAME08O PIC X(20)}: row 8 {@code SEC-USR-FNAME}
 * @param lname08 {@code LNAME08O PIC X(20)}: row 8 {@code SEC-USR-LNAME}
 * @param utype08 {@code UTYPE08O PIC X(1)}: row 8 {@code SEC-USR-TYPE}
 * @param sel0009 {@code SEL0009O PIC X(1)}: row 9 selection, echoed input
 * @param usrId09 {@code USRID09O PIC X(8)}: row 9 {@code SEC-USR-ID}
 * @param fname09 {@code FNAME09O PIC X(20)}: row 9 {@code SEC-USR-FNAME}
 * @param lname09 {@code LNAME09O PIC X(20)}: row 9 {@code SEC-USR-LNAME}
 * @param utype09 {@code UTYPE09O PIC X(1)}: row 9 {@code SEC-USR-TYPE}
 * @param sel0010 {@code SEL0010O PIC X(1)}: row 10 selection, echoed input; note the four-digit spelling
 * @param usrId10 {@code USRID10O PIC X(8)}: row 10 {@code SEC-USR-ID}
 * @param fname10 {@code FNAME10O PIC X(20)}: row 10 {@code SEC-USR-FNAME}
 * @param lname10 {@code LNAME10O PIC X(20)}: row 10 {@code SEC-USR-LNAME}
 * @param utype10 {@code UTYPE10O PIC X(1)}: row 10 {@code SEC-USR-TYPE}
 * @param errMsg {@code ERRMSGO PIC X(78)}: the message line - 78, not 80
 * @param cdemoCu00UsrIdFirst {@code CDEMO-CU00-USRID-FIRST PIC X(08)}: backward browse cursor
 * @param cdemoCu00UsrIdLast {@code CDEMO-CU00-USRID-LAST PIC X(08)}: forward browse cursor
 * @param cdemoCu00PageNum {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}: the page number as a number
 * @param cdemoCu00NextPageFlg {@code CDEMO-CU00-NEXT-PAGE-FLG PIC X(01)}: {@code 'Y'} or {@code 'N'},
 *     default {@code 'N'}
 * @param cdemoCu00UsrSelFlg {@code CDEMO-CU00-USR-SEL-FLG PIC X(01)}: the ticked row's selection
 * @param cdemoCu00UsrSelected {@code CDEMO-CU00-USR-SELECTED PIC X(08)}: the ticked row's user
 * @param nextProgram the {@code XCTL} target, {@code CDEMO-TO-PROGRAM PIC X(08)}
 * @param nextMapset the mapset to display next, {@code PIC X(7)}
 * @param nextMap the map to display next, {@code PIC X(7)}
 * @param navigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA},
 *     referenced and never widened
 */
public record UserListResponse(@JsonProperty("trnname") String trnName,
                               String title01,
                               @JsonProperty("curdate") String curDate,
                               @JsonProperty("pgmname") String pgmName,
                               String title02,
                               @JsonProperty("curtime") String curTime,
                               @JsonProperty("pagenum") String pageNum,
                               @JsonProperty("usridin") String usrIdIn,
                               String sel0001,
                               @JsonProperty("usrid01") String usrId01,
                               String fname01,
                               String lname01,
                               String utype01,
                               String sel0002,
                               @JsonProperty("usrid02") String usrId02,
                               String fname02,
                               String lname02,
                               String utype02,
                               String sel0003,
                               @JsonProperty("usrid03") String usrId03,
                               String fname03,
                               String lname03,
                               String utype03,
                               String sel0004,
                               @JsonProperty("usrid04") String usrId04,
                               String fname04,
                               String lname04,
                               String utype04,
                               String sel0005,
                               @JsonProperty("usrid05") String usrId05,
                               String fname05,
                               String lname05,
                               String utype05,
                               String sel0006,
                               @JsonProperty("usrid06") String usrId06,
                               String fname06,
                               String lname06,
                               String utype06,
                               String sel0007,
                               @JsonProperty("usrid07") String usrId07,
                               String fname07,
                               String lname07,
                               String utype07,
                               String sel0008,
                               @JsonProperty("usrid08") String usrId08,
                               String fname08,
                               String lname08,
                               String utype08,
                               String sel0009,
                               @JsonProperty("usrid09") String usrId09,
                               String fname09,
                               String lname09,
                               String utype09,
                               String sel0010,
                               @JsonProperty("usrid10") String usrId10,
                               String fname10,
                               String lname10,
                               String utype10,
                               @JsonProperty("errmsg") String errMsg,
                               String cdemoCu00UsrIdFirst,
                               String cdemoCu00UsrIdLast,
                               int cdemoCu00PageNum,
                               String cdemoCu00NextPageFlg,
                               String cdemoCu00UsrSelFlg,
                               String cdemoCu00UsrSelected,
                               String nextProgram,
                               String nextMapset,
                               String nextMap,
                               NavigationContext navigationContext) {
    /**
     * {@code WS-TRANID} of {@code app/cbl/COUSR00C.cbl} line 37: the CICS transaction identifier.
     */
    public static final String TRANSACTION_ID = "CU00";

    /**
     * {@code WS-PGMNAME} of {@code app/cbl/COUSR00C.cbl} line 36: the COBOL program migrated here.
     */
    public static final String PROGRAM_NAME = "COUSR00C";

    /**
     * {@code MAPSET('COUSR00')} of {@code app/cbl/COUSR00C.cbl} line 529 - seven characters.
     */
    public static final String MAPSET_NAME = "COUSR00";

    /**
     * {@code MAP('COUSR0A')} of {@code app/cbl/COUSR00C.cbl} line 528 - seven characters.
     */
    public static final String MAP_NAME = "COUSR0A";

    /**
     * {@code MOVE 'COUSR02C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COUSR00C.cbl} line 192, reached from
     * {@code WHEN 'U'} and {@code WHEN 'u'} - the selection is case-insensitive.
     */
    public static final String NEXT_PROGRAM_USER_UPDATE = "COUSR02C";

    /**
     * {@code MOVE 'COUSR03C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COUSR00C.cbl} line 202, reached from
     * {@code WHEN 'D'} and {@code WHEN 'd'} - the selection is case-insensitive.
     */
    public static final String NEXT_PROGRAM_USER_DELETE = "COUSR03C";

    /**
     * {@code MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM} at {@code app/cbl/COUSR00C.cbl} line 509, the fallback
     * when {@code PF3} is pressed and no prior screen was recorded.
     */
    public static final String NEXT_PROGRAM_SIGNON = "COSGN00C";

    /**
     * The message of {@code WHEN OTHER} at {@code app/cbl/COUSR00C.cbl} lines 210-213, carried verbatim
     * including its spacing, because message text is part of observable behaviour.
     */
    public static final String INVALID_SELECTION_MESSAGE =
            "Invalid selection. Valid values are U and D";

    /**
     * Map-derived members: 8 header + 50 row + 1 message.
     */
    public static final int MAP_FIELD_COUNT = 59;

    public static final int COMPONENT_COUNT = 69;

    public static final int ROW_COUNT = 10;

    public static final int ROW_FIELD_COUNT = 5;

    public static final int SYMBOLIC_MAP_LENGTH = 1127;

    /**
     * {@code TRNNAME}: {@code PIC X(4)}, {@code DFHMDF LENGTH=4} at {@code POS=(1,7)}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01}: {@code PIC X(40)}, {@code DFHMDF LENGTH=40} at {@code POS=(1,21)}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATE}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(1,71)}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAME}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(2,7)}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02}: {@code PIC X(40)}, {@code DFHMDF LENGTH=40} at {@code POS=(2,21)}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIME}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(2,71)}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENUM}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8} at {@code POS=(4,71)}.
     */
    public static final int PAGENUM_LENGTH = 8;

    /**
     * {@code USRIDIN}: {@code PIC X(8)}, {@code DFHMDF LENGTH=8}, {@code ATTRB=(FSET,NORM,UNPROT)}.
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * {@code SEL000n}: {@code PIC X(1)}, {@code DFHMDF LENGTH=1}, {@code ATTRB=(FSET,NORM,UNPROT)}.
     */
    public static final int SEL_LENGTH = 1;

    /**
     * {@code USRIDnn}: {@code PIC X(8)}, matching {@code SEC-USR-ID PIC X(08)}.
     */
    public static final int USRID_LENGTH = 8;

    /**
     * {@code FNAMEnn}: {@code PIC X(20)}, matching {@code SEC-USR-FNAME PIC X(20)}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEnn}: {@code PIC X(20)}, matching {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code UTYPEnn}: {@code PIC X(1)}, matching {@code SEC-USR-TYPE PIC X(01)}.
     */
    public static final int UTYPE_LENGTH = 1;

    /**
     * {@code ERRMSG}: {@code PIC X(78)}, {@code DFHMDF LENGTH=78} at {@code POS=(23,1)}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * {@code CDEMO-CU00-USRID-FIRST PIC X(08)}.
     */
    public static final int CU00_USRID_FIRST_LENGTH = 8;

    /**
     * {@code CDEMO-CU00-USRID-LAST PIC X(08)}.
     */
    public static final int CU00_USRID_LAST_LENGTH = 8;

    /**
     * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)}: eight unsigned digits, no {@code V}, no scale.
     */
    public static final int CU00_PAGE_NUM_DIGITS = 8;

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
     * {@code 05 CDEMO-CU00-INFO}: 8 + 8 + 8 + 1 + 1 + 8 bytes.
     */
    public static final int CU00_INFO_LENGTH = 34;

    /**
     * The communication area {@code CU00} actually passes: {@value NavigationContext#COMMAREA_LENGTH} bytes
     * of {@code CARDDEMO-COMMAREA} plus {@value #CU00_INFO_LENGTH} bytes of extension.
     */
    public static final int CU00_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + CU00_INFO_LENGTH;

    /**
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'} of {@code app/cbl/COUSR00C.cbl} line 71.
     */
    public static final String NEXT_PAGE_YES = "Y";

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'} of {@code app/cbl/COUSR00C.cbl} line 72, and the {@code VALUE 'N'}
     * the field is initialised to at line 70.
     */
    public static final String NEXT_PAGE_NO = "N";

    /**
     * {@code CDEMO-TO-PROGRAM PIC X(08)} of {@code app/cpy/COCOM01Y.cpy} line 24.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * {@code CDEMO-LAST-MAPSET PIC X(7)} of {@code app/cpy/COCOM01Y.cpy} line 44 - seven.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * {@code CDEMO-LAST-MAP PIC X(7)} of {@code app/cpy/COCOM01Y.cpy} line 43 - seven.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    // COBOL field names, spelled exactly as the map spells them. These are the keys the parity differ
    // compares by, so a tidied name would make a real difference invisible.

    public static final String TRNNAME_FIELD = "TRNNAME";

    public static final String TITLE01_FIELD = "TITLE01";

    public static final String CURDATE_FIELD = "CURDATE";

    public static final String PGMNAME_FIELD = "PGMNAME";

    public static final String TITLE02_FIELD = "TITLE02";

    public static final String CURTIME_FIELD = "CURTIME";

    public static final String PAGENUM_FIELD = "PAGENUM";

    public static final String USRIDIN_FIELD = "USRIDIN";

    public static final String ERRMSG_FIELD = "ERRMSG";

    public static final String CU00_USRID_FIRST_FIELD = "CDEMO-CU00-USRID-FIRST";

    public static final String CU00_USRID_LAST_FIELD = "CDEMO-CU00-USRID-LAST";

    public static final String CU00_PAGE_NUM_FIELD = "CDEMO-CU00-PAGE-NUM";

    public static final String CU00_NEXT_PAGE_FLG_FIELD = "CDEMO-CU00-NEXT-PAGE-FLG";

    public static final String CU00_USR_SEL_FLG_FIELD = "CDEMO-CU00-USR-SEL-FLG";

    public static final String CU00_USR_SELECTED_FIELD = "CDEMO-CU00-USR-SELECTED";

    /**
     * The {@value #MAP_FIELD_COUNT} map-derived field names, as literals, in the order
     * {@code app/cpy-bms/COUSR00.CPY} declares them and {@code app/bms/COUSR00.bms} labels them.
     */
    public static final List<String> FIELD_NAMES = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, PAGENUM_FIELD, USRIDIN_FIELD,
            "SEL0001", "USRID01", "FNAME01", "LNAME01", "UTYPE01",
            "SEL0002", "USRID02", "FNAME02", "LNAME02", "UTYPE02",
            "SEL0003", "USRID03", "FNAME03", "LNAME03", "UTYPE03",
            "SEL0004", "USRID04", "FNAME04", "LNAME04", "UTYPE04",
            "SEL0005", "USRID05", "FNAME05", "LNAME05", "UTYPE05",
            "SEL0006", "USRID06", "FNAME06", "LNAME06", "UTYPE06",
            "SEL0007", "USRID07", "FNAME07", "LNAME07", "UTYPE07",
            "SEL0008", "USRID08", "FNAME08", "LNAME08", "UTYPE08",
            "SEL0009", "USRID09", "FNAME09", "LNAME09", "UTYPE09",
            "SEL0010", "USRID10", "FNAME10", "LNAME10", "UTYPE10",
            ERRMSG_FIELD);

    private static final String SPACE = " ";

    /**
     * Validates every component against the width its {@code PICTURE} clause declares.
     */
    public UserListResponse {
        trnName = requireWidth(trnName, TRNNAME_LENGTH, TRNNAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CURDATE_LENGTH, CURDATE_FIELD);
        pgmName = requireWidth(pgmName, PGMNAME_LENGTH, PGMNAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CURTIME_LENGTH, CURTIME_FIELD);
        pageNum = requireWidth(pageNum, PAGENUM_LENGTH, PAGENUM_FIELD);
        usrIdIn = requireWidth(usrIdIn, USRIDIN_LENGTH, USRIDIN_FIELD);

        sel0001 = requireWidth(sel0001, SEL_LENGTH, "SEL0001");
        usrId01 = requireWidth(usrId01, USRID_LENGTH, "USRID01");
        fname01 = requireWidth(fname01, FNAME_LENGTH, "FNAME01");
        lname01 = requireWidth(lname01, LNAME_LENGTH, "LNAME01");
        utype01 = requireWidth(utype01, UTYPE_LENGTH, "UTYPE01");

        sel0002 = requireWidth(sel0002, SEL_LENGTH, "SEL0002");
        usrId02 = requireWidth(usrId02, USRID_LENGTH, "USRID02");
        fname02 = requireWidth(fname02, FNAME_LENGTH, "FNAME02");
        lname02 = requireWidth(lname02, LNAME_LENGTH, "LNAME02");
        utype02 = requireWidth(utype02, UTYPE_LENGTH, "UTYPE02");

        sel0003 = requireWidth(sel0003, SEL_LENGTH, "SEL0003");
        usrId03 = requireWidth(usrId03, USRID_LENGTH, "USRID03");
        fname03 = requireWidth(fname03, FNAME_LENGTH, "FNAME03");
        lname03 = requireWidth(lname03, LNAME_LENGTH, "LNAME03");
        utype03 = requireWidth(utype03, UTYPE_LENGTH, "UTYPE03");

        sel0004 = requireWidth(sel0004, SEL_LENGTH, "SEL0004");
        usrId04 = requireWidth(usrId04, USRID_LENGTH, "USRID04");
        fname04 = requireWidth(fname04, FNAME_LENGTH, "FNAME04");
        lname04 = requireWidth(lname04, LNAME_LENGTH, "LNAME04");
        utype04 = requireWidth(utype04, UTYPE_LENGTH, "UTYPE04");

        sel0005 = requireWidth(sel0005, SEL_LENGTH, "SEL0005");
        usrId05 = requireWidth(usrId05, USRID_LENGTH, "USRID05");
        fname05 = requireWidth(fname05, FNAME_LENGTH, "FNAME05");
        lname05 = requireWidth(lname05, LNAME_LENGTH, "LNAME05");
        utype05 = requireWidth(utype05, UTYPE_LENGTH, "UTYPE05");

        sel0006 = requireWidth(sel0006, SEL_LENGTH, "SEL0006");
        usrId06 = requireWidth(usrId06, USRID_LENGTH, "USRID06");
        fname06 = requireWidth(fname06, FNAME_LENGTH, "FNAME06");
        lname06 = requireWidth(lname06, LNAME_LENGTH, "LNAME06");
        utype06 = requireWidth(utype06, UTYPE_LENGTH, "UTYPE06");

        sel0007 = requireWidth(sel0007, SEL_LENGTH, "SEL0007");
        usrId07 = requireWidth(usrId07, USRID_LENGTH, "USRID07");
        fname07 = requireWidth(fname07, FNAME_LENGTH, "FNAME07");
        lname07 = requireWidth(lname07, LNAME_LENGTH, "LNAME07");
        utype07 = requireWidth(utype07, UTYPE_LENGTH, "UTYPE07");

        sel0008 = requireWidth(sel0008, SEL_LENGTH, "SEL0008");
        usrId08 = requireWidth(usrId08, USRID_LENGTH, "USRID08");
        fname08 = requireWidth(fname08, FNAME_LENGTH, "FNAME08");
        lname08 = requireWidth(lname08, LNAME_LENGTH, "LNAME08");
        utype08 = requireWidth(utype08, UTYPE_LENGTH, "UTYPE08");

        sel0009 = requireWidth(sel0009, SEL_LENGTH, "SEL0009");
        usrId09 = requireWidth(usrId09, USRID_LENGTH, "USRID09");
        fname09 = requireWidth(fname09, FNAME_LENGTH, "FNAME09");
        lname09 = requireWidth(lname09, LNAME_LENGTH, "LNAME09");
        utype09 = requireWidth(utype09, UTYPE_LENGTH, "UTYPE09");

        sel0010 = requireWidth(sel0010, SEL_LENGTH, "SEL0010");
        usrId10 = requireWidth(usrId10, USRID_LENGTH, "USRID10");
        fname10 = requireWidth(fname10, FNAME_LENGTH, "FNAME10");
        lname10 = requireWidth(lname10, LNAME_LENGTH, "LNAME10");
        utype10 = requireWidth(utype10, UTYPE_LENGTH, "UTYPE10");

        errMsg = requireWidth(errMsg, ERRMSG_LENGTH, ERRMSG_FIELD);

        cdemoCu00UsrIdFirst = requireWidth(cdemoCu00UsrIdFirst, CU00_USRID_FIRST_LENGTH,
                CU00_USRID_FIRST_FIELD);
        cdemoCu00UsrIdLast = requireWidth(cdemoCu00UsrIdLast, CU00_USRID_LAST_LENGTH,
                CU00_USRID_LAST_FIELD);
        cdemoCu00PageNum = storeUnsignedDigits(cdemoCu00PageNum, CU00_PAGE_NUM_DIGITS,
                CU00_PAGE_NUM_FIELD);
        cdemoCu00NextPageFlg = requireWidth(cdemoCu00NextPageFlg, CU00_NEXT_PAGE_FLG_LENGTH,
                CU00_NEXT_PAGE_FLG_FIELD);
        cdemoCu00UsrSelFlg = requireWidth(cdemoCu00UsrSelFlg, CU00_USR_SEL_FLG_LENGTH,
                CU00_USR_SEL_FLG_FIELD);
        cdemoCu00UsrSelected = requireWidth(cdemoCu00UsrSelected, CU00_USR_SELECTED_LENGTH,
                CU00_USR_SELECTED_FIELD);

        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH,
                NavigationContext.TO_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH,
                NavigationContext.LAST_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NavigationContext.LAST_MAP_FIELD);

        navigationContext = Objects.requireNonNull(navigationContext,
                "The CARDDEMO-COMMAREA is not optional; every online transaction is handed one. "
                        + "Use NavigationContext.empty() for the initial state");
    }

    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " requires a value; a COBOL screen "
                + "field holds spaces when it is empty, never null, so move SPACES explicitly or "
                + "start from UserListResponse.blank()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC X("
                    + declaredWidth + ") on map " + MAP_NAME + " of mapset " + MAPSET_NAME
                    + " but was given " + value.length() + " character(s): '" + value + "'. The "
                    + "surplus has nowhere to go on a " + SYMBOLIC_MAP_LENGTH + "-byte symbolic "
                    + "map. To shorten it deliberately, truncate on the right as a COBOL "
                    + "alphanumeric MOVE does before offering the value here");
        }
        return value;
    }

    private static int storeUnsignedDigits(int value, int declaredDigits, String cobolName) {
        if (value < 0) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC 9("
                    + declaredDigits + "), an unsigned picture with no sign position, so it cannot "
                    + "hold " + value);
        }
        // ADD 1 TO CDEMO-CU00-PAGE-NUM at COUSR00C:320 is exactly such a store, so a ninth digit is dropped
        // by the receiver rather than refused: 100000000 stores as 00000000.
        int modulus = 1;
        for (int digit = 0; digit < declaredDigits; digit++) {
            modulus = modulus * 10;
        }
        return value % modulus;
    }

    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    /**
     * The screen as {@code COUSR00C} finds it before it has produced anything: every screen field carrying
     * the unpainted image at its declared width, the page number zero, and the next-page flag at the
     * {@code VALUE 'N'} its declaration gives it at {@code app/cbl/COUSR00C.cbl} line 70.
     *
     * @return a fully unpainted response, ready to be filled in through {@link #toBuilder()}
     */
    public static UserListResponse blank() {
        return new UserListResponse(ScreenFieldImage.unpainted(TRNNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE01_LENGTH),
                ScreenFieldImage.unpainted(CURDATE_LENGTH),
                ScreenFieldImage.unpainted(PGMNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE02_LENGTH),
                ScreenFieldImage.unpainted(CURTIME_LENGTH),
                ScreenFieldImage.unpainted(PAGENUM_LENGTH),
                ScreenFieldImage.unpainted(USRIDIN_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(SEL_LENGTH), ScreenFieldImage.unpainted(USRID_LENGTH), ScreenFieldImage.unpainted(FNAME_LENGTH),
                ScreenFieldImage.unpainted(LNAME_LENGTH), ScreenFieldImage.unpainted(UTYPE_LENGTH),
                ScreenFieldImage.unpainted(ERRMSG_LENGTH),
                spaces(CU00_USRID_FIRST_LENGTH),
                spaces(CU00_USRID_LAST_LENGTH),
                0,
                NEXT_PAGE_NO,
                spaces(CU00_USR_SEL_FLG_LENGTH),
                spaces(CU00_USR_SELECTED_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                NavigationContext.empty());
    }

    // Each reads the stored field rather than duplicating its state, so there is exactly one source of
    // truth, and neither is the negation of the other because the field can hold a third value: it is PIC
    // X(01), and a fresh commarea arriving from a client that omitted it holds a space, which is neither
    // 'Y' nor 'N'.

    /**
     * {@code 88 NEXT-PAGE-YES VALUE 'Y'}: a further page exists beyond the one being displayed.
     *
     * @return {@code true} when the flag holds exactly {@link #NEXT_PAGE_YES}
     */
    public boolean nextPageYes() {
        return NEXT_PAGE_YES.equals(cdemoCu00NextPageFlg);
    }

    /**
     * {@code 88 NEXT-PAGE-NO VALUE 'N'}: the page being displayed is the last one.
     *
     * @return {@code true} when the flag holds exactly {@link #NEXT_PAGE_NO}
     */
    public boolean nextPageNo() {
        return NEXT_PAGE_NO.equals(cdemoCu00NextPageFlg);
    }

    /**
     * One row of the list as {@code POPULATE-USER-DATA} fills it.
     *
     * @param rowNumber the COBOL row number, 1-based
     * @param selection {@code SEL000n PIC X(1)}: echoed user input, never written by the program
     * @param userId {@code USRIDnn PIC X(8)} from {@code SEC-USR-ID}
     * @param firstName {@code FNAMEnn PIC X(20)} from {@code SEC-USR-FNAME}
     * @param lastName {@code LNAMEnn PIC X(20)} from {@code SEC-USR-LNAME}
     * @param userType {@code UTYPEnn PIC X(1)} from {@code SEC-USR-TYPE}
     */
    public record Row(int rowNumber,
                      String selection,
                      String userId,
                      String firstName,
                      String lastName,
                      String userType) {
        /**
         * A diagnostic rendering that withholds the personal name, per {@link SensitiveDiagnostics}.
         *
         * @return a rendering safe to log, never {@code null}
         */
        @Override
        public String toString() {
            return "Row[" + rowNumber
                    + ", selection='" + selection
                    + "', userId='" + userId
                    + "', firstName=" + SensitiveDiagnostics.describeText(firstName)
                    + ", lastName=" + SensitiveDiagnostics.describeText(lastName)
                    + ", userType='" + userType
                    + "']";
        }

        /**
         * {@code true} when all four data cells carry no user - which covers both of the two ways a row can
         * hold nothing, because the program produces both.
         *
         * <p>{@code MOVE LOW-VALUES TO COUSR0AO} at {@code :117} clears the map before the first paint, so
         * a row on a screen that {@code INITIALIZE-USER-DATA} never ran for holds {@code X'00'}.
         *
         * @return {@code true} when all four data cells are spaces or all {@code LOW-VALUES}
         */
        public boolean blankRow() {
            return ScreenFieldImage.isSpacesOrLowValues(userId)
                    && ScreenFieldImage.isSpacesOrLowValues(firstName)
                    && ScreenFieldImage.isSpacesOrLowValues(lastName)
                    && ScreenFieldImage.isSpacesOrLowValues(userType);
        }
    }

    /**
     * The row bearing a given COBOL row number.
     *
     * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT}, exactly as
     *     {@code WS-IDX} counts
     * @return that row's five cells
     * @throws IllegalArgumentException if {@code rowNumber} is outside {@code 1..}{@value #ROW_COUNT}
     */
    public Row row(int rowNumber) {
        return switch (rowNumber) {
            case 1 -> new Row(1, sel0001, usrId01, fname01, lname01, utype01);
            case 2 -> new Row(2, sel0002, usrId02, fname02, lname02, utype02);
            case 3 -> new Row(3, sel0003, usrId03, fname03, lname03, utype03);
            case 4 -> new Row(4, sel0004, usrId04, fname04, lname04, utype04);
            case 5 -> new Row(5, sel0005, usrId05, fname05, lname05, utype05);
            case 6 -> new Row(6, sel0006, usrId06, fname06, lname06, utype06);
            case 7 -> new Row(7, sel0007, usrId07, fname07, lname07, utype07);
            case 8 -> new Row(8, sel0008, usrId08, fname08, lname08, utype08);
            case 9 -> new Row(9, sel0009, usrId09, fname09, lname09, utype09);
            case 10 -> new Row(10, sel0010, usrId10, fname10, lname10, utype10);
            default -> throw new IllegalArgumentException("Row " + rowNumber + " does not exist; "
                    + MAP_NAME + " declares exactly " + ROW_COUNT + " rows, numbered 1 to "
                    + ROW_COUNT + " as WS-IDX counts them. Ten rows is the screen, not a setting");
        };
    }

    /**
     * All {@value #ROW_COUNT} rows, in screen order, always {@value #ROW_COUNT} of them.
     *
     * @return an immutable list of exactly {@value #ROW_COUNT} rows, blanks included
     */
    public List<Row> rows() {
        return List.of(row(1), row(2), row(3), row(4), row(5),
                row(6), row(7), row(8), row(9), row(10));
    }

    /**
     * The {@value #MAP_FIELD_COUNT} map-derived field names in map order, as an instance-level convenience
     * over {@link #FIELD_NAMES}.
     *
     * @return the immutable field-name list; the same list {@link #FIELD_NAMES} exposes
     */
    public List<String> fieldNames() {
        return FIELD_NAMES;
    }

    /**
     * A builder seeded from {@link #blank()} - the state {@code COUSR00C} starts every page from.
     *
     * @return a new builder holding a fully space-filled screen
     */
    public static Builder builder() {
        return new Builder(blank());
    }

    /**
     * A builder seeded from this response, for producing a modified copy.
     *
     * @return a new builder holding this response's values
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private String trnName;
        private String title01;
        private String curDate;
        private String pgmName;
        private String title02;
        private String curTime;
        private String pageNum;
        private String usrIdIn;

        private final String[] selection = new String[ROW_COUNT];

        private final String[] userId = new String[ROW_COUNT];

        private final String[] firstName = new String[ROW_COUNT];

        private final String[] lastName = new String[ROW_COUNT];

        private final String[] userType = new String[ROW_COUNT];

        private String errMsg;
        private String cdemoCu00UsrIdFirst;
        private String cdemoCu00UsrIdLast;
        private int cdemoCu00PageNum;
        private String cdemoCu00NextPageFlg;
        private String cdemoCu00UsrSelFlg;
        private String cdemoCu00UsrSelected;
        private String nextProgram;
        private String nextMapset;
        private String nextMap;
        private NavigationContext navigationContext;

        private Builder(UserListResponse seed) {
            trnName = seed.trnName();
            title01 = seed.title01();
            curDate = seed.curDate();
            pgmName = seed.pgmName();
            title02 = seed.title02();
            curTime = seed.curTime();
            pageNum = seed.pageNum();
            usrIdIn = seed.usrIdIn();
            for (Row seedRow : seed.rows()) {
                int index = seedRow.rowNumber() - 1;
                selection[index] = seedRow.selection();
                userId[index] = seedRow.userId();
                firstName[index] = seedRow.firstName();
                lastName[index] = seedRow.lastName();
                userType[index] = seedRow.userType();
            }
            errMsg = seed.errMsg();
            cdemoCu00UsrIdFirst = seed.cdemoCu00UsrIdFirst();
            cdemoCu00UsrIdLast = seed.cdemoCu00UsrIdLast();
            cdemoCu00PageNum = seed.cdemoCu00PageNum();
            cdemoCu00NextPageFlg = seed.cdemoCu00NextPageFlg();
            cdemoCu00UsrSelFlg = seed.cdemoCu00UsrSelFlg();
            cdemoCu00UsrSelected = seed.cdemoCu00UsrSelected();
            nextProgram = seed.nextProgram();
            nextMapset = seed.nextMapset();
            nextMap = seed.nextMap();
            navigationContext = seed.navigationContext();
        }

        /**
         * Sets {@code TRNNAMEO}, the transaction identifier shown on line 1.
         *
         * @param value at most {@value #TRNNAME_LENGTH} characters
         * @return this builder
         */
        public Builder trnName(String value) {
            this.trnName = value;
            return this;
        }

        public Builder title01(String value) {
            this.title01 = value;
            return this;
        }

        public Builder curDate(String value) {
            this.curDate = value;
            return this;
        }

        /**
         * Sets {@code PGMNAMEO}, the program name shown on line 2.
         *
         * @param value at most {@value #PGMNAME_LENGTH} characters
         * @return this builder
         */
        public Builder pgmName(String value) {
            this.pgmName = value;
            return this;
        }

        public Builder title02(String value) {
            this.title02 = value;
            return this;
        }

        /**
         * Sets {@code CURTIMEO}, the time shown on line 2 - {@value #CURTIME_LENGTH} characters, not nine.
         *
         * @param value at most {@value #CURTIME_LENGTH} characters
         * @return this builder
         */
        public Builder curTime(String value) {
            this.curTime = value;
            return this;
        }

        /**
         * Sets {@code PAGENUMO}, the page number as it appears on the screen.
         *
         * @param value at most {@value #PAGENUM_LENGTH} characters
         * @return this builder
         */
        public Builder pageNum(String value) {
            this.pageNum = value;
            return this;
        }

        public Builder usrIdIn(String value) {
            this.usrIdIn = value;
            return this;
        }

        /**
         * Fills one row's four data cells, exactly as {@code POPULATE-USER-DATA} does.
         *
         * <p>The selection cell is deliberately left alone, because {@code app/cbl/COUSR00C.cbl} lines
         * 386-441 write only {@code USRIDnnI}, {@code FNAMEnnI}, {@code LNAMEnnI} and {@code UTYPEnnI} from
         * {@code SEC-USER-DATA}.
         *
         * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT} - the same
         *     {@code WS-IDX} the {@code EVALUATE} switches on, not a Java index
         * @param userId {@code SEC-USR-ID}, at most {@value #USRID_LENGTH} characters
         * @param firstName {@code SEC-USR-FNAME}, at most {@value #FNAME_LENGTH} characters
         * @param lastName {@code SEC-USR-LNAME}, at most {@value #LNAME_LENGTH} characters
         * @param userType {@code SEC-USR-TYPE}, at most {@value #UTYPE_LENGTH} characters
         * @return this builder
         * @throws IllegalArgumentException if {@code rowNumber} is outside {@code 1..}{@value #ROW_COUNT}
         */
        public Builder populateRow(int rowNumber,
                                   String userId,
                                   String firstName,
                                   String lastName,
                                   String userType) {
            int index = rowIndex(rowNumber);
            this.userId[index] = userId;
            this.firstName[index] = firstName;
            this.lastName[index] = lastName;
            this.userType[index] = userType;
            return this;
        }

        /**
         * Blanks one row's four data cells, exactly as {@code INITIALIZE-USER-DATA} does.
         *
         * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT}
         * @return this builder
         * @throws IllegalArgumentException if {@code rowNumber} is outside {@code 1..}{@value #ROW_COUNT}
         */
        public Builder blankRow(int rowNumber) {
            return populateRow(rowNumber,
                    spaces(USRID_LENGTH),
                    spaces(FNAME_LENGTH),
                    spaces(LNAME_LENGTH),
                    spaces(UTYPE_LENGTH));
        }

        /**
         * Sets one row's selection cell, {@code SEL000n} - the four-digit column.
         *
         * <p>The program never writes this cell; it reads it, at {@code app/cbl/COUSR00C.cbl} lines
         * 141-184, to learn which row was ticked.
         *
         * @param rowNumber the COBOL row number, {@code 1} through {@value #ROW_COUNT}
         * @param value at most {@value #SEL_LENGTH} character
         * @return this builder
         * @throws IllegalArgumentException if {@code rowNumber} is outside {@code 1..}{@value #ROW_COUNT}
         */
        public Builder selection(int rowNumber, String value) {
            this.selection[rowIndex(rowNumber)] = value;
            return this;
        }

        public Builder errMsg(String value) {
            this.errMsg = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-USRID-FIRST}, the backward browse cursor.
         *
         * @param value at most {@value #CU00_USRID_FIRST_LENGTH} characters
         * @return this builder
         */
        public Builder cdemoCu00UsrIdFirst(String value) {
            this.cdemoCu00UsrIdFirst = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-USRID-LAST}, the forward browse cursor.
         *
         * @param value at most {@value #CU00_USRID_LAST_LENGTH} characters
         * @return this builder
         */
        public Builder cdemoCu00UsrIdLast(String value) {
            this.cdemoCu00UsrIdLast = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-PAGE-NUM}, the page number as a number.
         *
         * @param value an unsigned value of at most {@value #CU00_PAGE_NUM_DIGITS} digits, because the
         *     picture is {@code 9(08)}
         * @return this builder
         */
        public Builder cdemoCu00PageNum(int value) {
            this.cdemoCu00PageNum = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-NEXT-PAGE-FLG} directly.
         *
         * @param value at most {@value #CU00_NEXT_PAGE_FLG_LENGTH} character; normally
         *     {@link #NEXT_PAGE_YES} or {@link #NEXT_PAGE_NO}
         * @return this builder
         */
        public Builder cdemoCu00NextPageFlg(String value) {
            this.cdemoCu00NextPageFlg = value;
            return this;
        }

        /**
         * {@code SET NEXT-PAGE-YES TO TRUE}: records that a further page exists.
         *
         * @return this builder
         */
        public Builder nextPageYes() {
            return cdemoCu00NextPageFlg(NEXT_PAGE_YES);
        }

        /**
         * {@code SET NEXT-PAGE-NO TO TRUE}: records that this is the last page.
         *
         * @return this builder
         */
        public Builder nextPageNo() {
            return cdemoCu00NextPageFlg(NEXT_PAGE_NO);
        }

        /**
         * Sets {@code CDEMO-CU00-USR-SEL-FLG}, the selection character of the ticked row.
         *
         * @param value at most {@value #CU00_USR_SEL_FLG_LENGTH} character
         * @return this builder
         */
        public Builder cdemoCu00UsrSelFlg(String value) {
            this.cdemoCu00UsrSelFlg = value;
            return this;
        }

        /**
         * Sets {@code CDEMO-CU00-USR-SELECTED}, the user identifier of the ticked row.
         *
         * @param value at most {@value #CU00_USR_SELECTED_LENGTH} characters
         * @return this builder
         */
        public Builder cdemoCu00UsrSelected(String value) {
            this.cdemoCu00UsrSelected = value;
            return this;
        }

        /**
         * Sets the {@code XCTL} target the client should call next.
         *
         * @param value at most {@link #NEXT_PROGRAM_LENGTH} characters; the documented targets are
         *     {@link #NEXT_PROGRAM_USER_UPDATE}, {@link #NEXT_PROGRAM_USER_DELETE} and
         *     {@link #NEXT_PROGRAM_SIGNON}
         * @return this builder
         */
        public Builder nextProgram(String value) {
            this.nextProgram = value;
            return this;
        }

        /**
         * Sets the mapset to display next - {@link #NEXT_MAPSET_LENGTH} characters, not eight.
         *
         * @param value at most {@link #NEXT_MAPSET_LENGTH} characters
         * @return this builder
         */
        public Builder nextMapset(String value) {
            this.nextMapset = value;
            return this;
        }

        /**
         * Sets the map to display next - {@link #NEXT_MAP_LENGTH} characters, not eight.
         *
         * @param value at most {@link #NEXT_MAP_LENGTH} characters
         * @return this builder
         */
        public Builder nextMap(String value) {
            this.nextMap = value;
            return this;
        }

        /**
         * Sets the {@code CARDDEMO-COMMAREA} this response hands back.
         *
         * @param value the communication area; referenced as it is, never widened
         * @return this builder
         */
        public Builder navigationContext(NavigationContext value) {
            this.navigationContext = value;
            return this;
        }

        /**
         * Builds the response, validating every width in the canonical constructor.
         *
         * @return the assembled response
         * @throws NullPointerException if a field was cleared to {@code null}
         * @throws IllegalArgumentException if a value exceeds its declared width
         */
        public UserListResponse build() {
            return new UserListResponse(trnName, title01, curDate, pgmName, title02, curTime,
                    pageNum, usrIdIn,
                    selection[0], userId[0], firstName[0], lastName[0], userType[0],
                    selection[1], userId[1], firstName[1], lastName[1], userType[1],
                    selection[2], userId[2], firstName[2], lastName[2], userType[2],
                    selection[3], userId[3], firstName[3], lastName[3], userType[3],
                    selection[4], userId[4], firstName[4], lastName[4], userType[4],
                    selection[5], userId[5], firstName[5], lastName[5], userType[5],
                    selection[6], userId[6], firstName[6], lastName[6], userType[6],
                    selection[7], userId[7], firstName[7], lastName[7], userType[7],
                    selection[8], userId[8], firstName[8], lastName[8], userType[8],
                    selection[9], userId[9], firstName[9], lastName[9], userType[9],
                    errMsg, cdemoCu00UsrIdFirst, cdemoCu00UsrIdLast, cdemoCu00PageNum,
                    cdemoCu00NextPageFlg, cdemoCu00UsrSelFlg, cdemoCu00UsrSelected,
                    nextProgram, nextMapset, nextMap, navigationContext);
        }

        private static int rowIndex(int rowNumber) {
            if (rowNumber < 1 || rowNumber > ROW_COUNT) {
                throw new IllegalArgumentException("Row " + rowNumber + " does not exist; "
                        + MAP_NAME + " declares exactly " + ROW_COUNT + " rows, numbered 1 to "
                        + ROW_COUNT + " as WS-IDX counts them, so the valid range is 1 to "
                        + ROW_COUNT + " inclusive");
            }
            return rowNumber - 1;
        }
    }

    /**
     * A diagnostic rendering that withholds the twenty personal names this screen carries, per
     * {@link SensitiveDiagnostics}.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(512);
        text.append("UserListResponse[trnName=").append(trnName)
                .append(", title01=").append(title01)
                .append(", curDate=").append(curDate)
                .append(", pgmName=").append(pgmName)
                .append(", title02=").append(title02)
                .append(", curTime=").append(curTime)
                .append(", pageNum=").append(pageNum)
                .append(", usrIdIn=").append(usrIdIn);
        for (Row row : rows()) {
            text.append(", ").append(row);
        }
        return text.append(", errMsg=").append(errMsg)
                .append(", cdemoCu00UsrIdFirst=").append(cdemoCu00UsrIdFirst)
                .append(", cdemoCu00UsrIdLast=").append(cdemoCu00UsrIdLast)
                .append(", cdemoCu00PageNum=").append(cdemoCu00PageNum)
                .append(", cdemoCu00NextPageFlg=").append(cdemoCu00NextPageFlg)
                .append(", cdemoCu00UsrSelFlg=").append(cdemoCu00UsrSelFlg)
                .append(", cdemoCu00UsrSelected=").append(cdemoCu00UsrSelected)
                .append(", nextProgram=").append(nextProgram)
                .append(", nextMapset=").append(nextMapset)
                .append(", nextMap=").append(nextMap)
                .append(", navigationContext=").append(navigationContext)
                .append(']').toString();
    }

}
