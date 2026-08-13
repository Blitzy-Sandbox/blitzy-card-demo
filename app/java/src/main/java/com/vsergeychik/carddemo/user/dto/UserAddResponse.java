package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import java.util.List;

/**
 * The outbound payload of {@code POST /api/users} - the screen that CICS transaction {@code CU01} paints
 * through program {@code app/cbl/COUSR01C.cbl} and mapset {@code app/bms/COUSR01.bms}.
 *
 * <p>An unlabelled {@code DFHMDF} has no symbolic-map item at all, so it cannot become a payload member.
 *
 * @param trnName {@code TRNNAMEO PIC X(4)}: the transaction identifier, always {@link #TRANSACTION_ID},
 *     written by {@code app/cbl/COUSR01C.cbl:220} from {@code WS-TRANID}
 * @param title01 {@code TITLE01O PIC X(40)}: the first title line, written at line 218 from
 *     {@code CCDA-TITLE01} of {@code app/cpy/COTTL01Y.cpy} - see {@code common.ScreenTitles}
 * @param curDate {@code CURDATEO PIC X(8)}: the current date as {@code MM/DD/YY}, composed at lines 223 to
 *     227 - see {@code common.DateHeader}
 * @param pgmName {@code PGMNAMEO PIC X(8)}: the program identifier, always {@link #PROGRAM_NAME}, written
 *     at line 221 from {@code WS-PGMNAME}
 * @param title02 {@code TITLE02O PIC X(40)}: the second title line, written at line 219 from
 *     {@code CCDA-TITLE02}
 * @param curTime {@code CURTIMEO PIC X(8)}: the current time as {@code hh:mm:ss}, composed at lines 229 to
 *     233
 * @param fName {@code FNAMEO PIC X(20)}: the first name, the same width as {@code SEC-USR-FNAME PIC X(20)}
 *     of {@code app/cpy/CSUSR01Y.cpy}
 * @param lName {@code LNAMEO PIC X(20)}: the last name, matching {@code SEC-USR-LNAME PIC X(20)}
 * @param userId {@code USERIDO PIC X(8)}: the user identifier, matching {@code SEC-USR-ID PIC X(08)}
 * @param passwd {@code PASSWDO PIC X(8)}: the password field, matching {@code SEC-USR-PWD PIC X(08)}
 * @param usrType {@code USRTYPEO PIC X(1)}: the user type, matching {@code SEC-USR-TYPE PIC X(01)}:
 *     {@code 'A'} for an administrator, {@code 'U'} for a regular user
 * @param errMsg {@code ERRMSGO PIC X(78)}: the message line, written at line 188 from the {@code PIC X(80)}
 *     {@code WS-MESSAGE}
 * @param navigationContext {@code 01 CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}: the conversation
 *     state returned at lines 107 to 110
 * @param nextProgram the program to transfer to, replacing {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at line
 *     176: {@code 'COSGN00C'} at lines 79 and 168, {@code 'COADM01C'} at line 94
 * @param nextMapset the mapset the client should render next, {@link #MAPSET_NAME} for this screen and
 *     {@link #NEXT_MAPSET_LENGTH} spaces on the {@code XCTL} arm
 * @param nextMap the map the client should render next, {@link #MAP_NAME} for this screen and
 *     {@link #NEXT_MAP_LENGTH} spaces on the {@code XCTL} arm, for the reason given for {@code nextMapset}
 */
public record UserAddResponse(@JsonProperty("trnname") String trnName,
                              String title01,
                              @JsonProperty("curdate") String curDate,
                              @JsonProperty("pgmname") String pgmName,
                              String title02,
                              @JsonProperty("curtime") String curTime,
                              @JsonProperty("fname") String fName,
                              @JsonProperty("lname") String lName,
                              @JsonProperty("userid") String userId,
                              @JsonIgnore String passwd,
                              @JsonProperty("usrtype") String usrType,
                              @JsonProperty("errmsg") String errMsg,
                              NavigationContext navigationContext,
                              String nextProgram,
                              String nextMapset,
                              String nextMap) {

    /**
     * Normalises the one component that is not published.
     *
     * <p>{@link #passwd()} is {@link JsonIgnore}d, so a body that was serialised and read back arrives
     * without it. {@value #PASSWD_LENGTH} spaces is the COBOL {@code SPACES} image at the declared width
     * and the value L293's {@code INITIALIZE-ALL-FIELDS} leaves there, so it is the right absent state.
     * Every other component is published and is left exactly as given.
     */
    public UserAddResponse {
        passwd = passwd == null ? " ".repeat(PASSWD_LENGTH) : passwd;
    }

    // =================================================================================================
    // The COBOL names, carried VERBATIM as app/cpy-bms/COUSR01.CPY spells them. These are the names a
    // field-for-field differ keys its comparison by, so a "tidied" spelling would make a real
    // difference invisible. Each is the xxxO output item; the paired xxxI input item differs only in
    // its final letter, and the two overlay the same storage because 01 COUSR1AO REDEFINES COUSR1AI.
    // =================================================================================================

    /** Symbolic-map name of {@link #trnName()}: {@code TRNNAMEO}, {@code COUSR01.CPY} line 98. */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /**
     * Symbolic-map name of {@link #title01()}: {@code TITLE01O}, {@code COUSR01.CPY} line 104.
     */
    public static final String TITLE01_FIELD = "TITLE01O";

    /**
     * Symbolic-map name of {@link #curDate()}: {@code CURDATEO}, {@code COUSR01.CPY} line 110.
     */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /**
     * Symbolic-map name of {@link #pgmName()}: {@code PGMNAMEO}, {@code COUSR01.CPY} line 116.
     */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /**
     * Symbolic-map name of {@link #title02()}: {@code TITLE02O}, {@code COUSR01.CPY} line 122.
     */
    public static final String TITLE02_FIELD = "TITLE02O";

    /**
     * Symbolic-map name of {@link #curTime()}: {@code CURTIMEO}, {@code COUSR01.CPY} line 128.
     */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /**
     * Symbolic-map name of {@link #fName()}: {@code FNAMEO}, {@code COUSR01.CPY} line 134.
     */
    public static final String F_NAME_FIELD = "FNAMEO";

    /**
     * Symbolic-map name of {@link #lName()}: {@code LNAMEO}, {@code COUSR01.CPY} line 140.
     */
    public static final String L_NAME_FIELD = "LNAMEO";

    /**
     * Symbolic-map name of {@link #userId()}: {@code USERIDO}, {@code COUSR01.CPY} line 146.
     */
    public static final String USER_ID_FIELD = "USERIDO";

    /**
     * Symbolic-map name of {@link #passwd()}: {@code PASSWDO}, {@code COUSR01.CPY} line 152.
     */
    public static final String PASSWD_FIELD = "PASSWDO";

    /**
     * Symbolic-map name of {@link #usrType()}: {@code USRTYPEO}, {@code COUSR01.CPY} line 158.
     */
    public static final String USR_TYPE_FIELD = "USRTYPEO";

    /**
     * Symbolic-map name of {@link #errMsg()}: {@code ERRMSGO}, {@code COUSR01.CPY} line 164.
     */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    /**
     * Declared width of {@code TRNNAMEO PIC X(4)}; {@code DFHMDF LENGTH=4} at {@code POS=(1,7)}.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * Declared width of {@code TITLE01O PIC X(40)}; {@code DFHMDF LENGTH=40} at {@code POS=(1,21)}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Declared width of {@code CURDATEO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(1,71)}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * Declared width of {@code PGMNAMEO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(2,7)}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * Declared width of {@code TITLE02O PIC X(40)}; {@code DFHMDF LENGTH=40} at {@code POS=(2,21)}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Declared width of {@code CURTIMEO PIC X(8)} - eight, not nine; {@code DFHMDF LENGTH=8} at
     * {@code POS=(2,71)} with {@code INITIAL='hh:mm:ss'}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * Declared width of {@code FNAMEO PIC X(20)}; {@code DFHMDF LENGTH=20} at {@code POS=(8,18)}.
     */
    public static final int F_NAME_LENGTH = 20;

    /**
     * Declared width of {@code LNAMEO PIC X(20)}; {@code DFHMDF LENGTH=20} at {@code POS=(8,56)}.
     */
    public static final int L_NAME_LENGTH = 20;

    /**
     * Declared width of {@code USERIDO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(11,15)}.
     */
    public static final int USER_ID_LENGTH = 8;

    /**
     * Declared width of {@code PASSWDO PIC X(8)}; {@code DFHMDF LENGTH=8} at {@code POS=(11,55)}, declared
     * {@code ATTRB=(DRK,FSET,UNPROT)} - {@code DRK} being the non-display attribute.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * Declared width of {@code USRTYPEO PIC X(1)}; {@code DFHMDF LENGTH=1} at {@code POS=(14,17)}.
     */
    public static final int USR_TYPE_LENGTH = 1;

    /**
     * Declared width of {@code ERRMSGO PIC X(78)} - seventy-eight, not eighty; {@code DFHMDF LENGTH=78} at
     * {@code POS=(23,1)}, one full line of an eighty-column screen less the two columns the map reserves.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * Declared width of {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COUSR01C.cbl:38} - the source of
     * {@link #errMsg()}, published so the two-character narrowing at line 188 is stated rather than left to
     * be rediscovered.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The number of members that derive from a screen field: 12.
     *
     * <p>{@code app/bms/COUSR01.bms} declares 28 {@code DFHMDF} fields of which exactly 12 carry a name
     * label, and {@code app/cpy-bms/COUSR01.CPY} declares 12 {@code xxxI} items and 12 {@code xxxO} items.
     */
    public static final int MAP_DERIVED_FIELD_COUNT = 12;

    /**
     * The twelve {@code xxxO} item names of {@code 01 COUSR1AO}, in symbolic-map declaration order, which
     * is also the declaration order of the members of this record.
     */
    public static final List<String> MAP_DERIVED_FIELD_NAMES = List.of(TRN_NAME_FIELD,
                                                                       TITLE01_FIELD,
                                                                       CUR_DATE_FIELD,
                                                                       PGM_NAME_FIELD,
                                                                       TITLE02_FIELD,
                                                                       CUR_TIME_FIELD,
                                                                       F_NAME_FIELD,
                                                                       L_NAME_FIELD,
                                                                       USER_ID_FIELD,
                                                                       PASSWD_FIELD,
                                                                       USR_TYPE_FIELD,
                                                                       ERR_MSG_FIELD);

    /**
     * The twelve name-labelled {@code DFHMDF} labels of {@code app/bms/COUSR01.bms}, in mapset declaration
     * order.
     */
    public static final List<String> DFHMDF_FIELD_NAMES = List.of("TRNNAME",
                                                                  "TITLE01",
                                                                  "CURDATE",
                                                                  "PGMNAME",
                                                                  "TITLE02",
                                                                  "CURTIME",
                                                                  "FNAME",
                                                                  "LNAME",
                                                                  "USERID",
                                                                  "PASSWD",
                                                                  "USRTYPE",
                                                                  "ERRMSG");

    /**
     * The declared widths of the twelve map-derived members, in the same order as
     * {@link #MAP_DERIVED_FIELD_NAMES} and {@link #DFHMDF_FIELD_NAMES}.
     */
    public static final List<Integer> MAP_DERIVED_FIELD_LENGTHS = List.of(TRN_NAME_LENGTH,
                                                                         TITLE01_LENGTH,
                                                                         CUR_DATE_LENGTH,
                                                                         PGM_NAME_LENGTH,
                                                                         TITLE02_LENGTH,
                                                                         CUR_TIME_LENGTH,
                                                                         F_NAME_LENGTH,
                                                                         L_NAME_LENGTH,
                                                                         USER_ID_LENGTH,
                                                                         PASSWD_LENGTH,
                                                                         USR_TYPE_LENGTH,
                                                                         ERR_MSG_LENGTH);

    /**
     * The CICS transaction identifier, {@code 'CU01'}: {@code WS-TRANID PIC X(04) VALUE 'CU01'} at
     * {@code app/cbl/COUSR01C.cbl:37}, moved into {@code TRNNAMEO} at line 220 and returned as the next
     * transaction at line 108.
     */
    public static final String TRANSACTION_ID = "CU01";

    /**
     * The program identifier, {@code 'COUSR01C'}: {@code WS-PGMNAME PIC X(08) VALUE 'COUSR01C'} at
     * {@code app/cbl/COUSR01C.cbl:36}, moved into {@code PGMNAMEO} at line 221 and into
     * {@code CDEMO-FROM-PROGRAM} at line 171.
     */
    public static final String PROGRAM_NAME = "COUSR01C";

    /**
     * The BMS map, {@code 'COUSR1A'}: the {@code MAP('COUSR1A')} operand of the send at
     * {@code app/cbl/COUSR01C.cbl:191} and of the receive at line 204, declared as {@code COUSR1A DFHMDI}
     * in {@code app/bms/COUSR01.bms:26}.
     */
    public static final String MAP_NAME = "COUSR1A";

    /**
     * The BMS mapset, {@code 'COUSR01'}: the {@code MAPSET('COUSR01')} operand at
     * {@code app/cbl/COUSR01C.cbl:192} and line 205, declared as {@code COUSR01 DFHMSD} in
     * {@code app/bms/COUSR01.bms:19}.
     */
    public static final String MAPSET_NAME = "COUSR01";

    /**
     * Declared width of {@link #nextProgram()}: {@link #NEXT_PROGRAM_LENGTH}, taken from
     * {@code CDEMO-TO-PROGRAM PIC X(08)} at {@code app/cpy/COCOM01Y.cpy:24} - the very field
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COUSR01C.cbl:176} transfers through.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Declared width of {@link #nextMapset()}: {@link #NEXT_MAPSET_LENGTH}, taken from
     * {@code CDEMO-LAST-MAPSET PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:44}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Declared width of {@link #nextMap()}: {@link #NEXT_MAP_LENGTH}, taken from
     * {@code CDEMO-LAST-MAP PIC X(7)} at {@code app/cpy/COCOM01Y.cpy:43}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    private static final String PASSWD_NOT_RENDERED = SensitiveDiagnostics.REDACTED;

    public UserAddResponse withTrnName(String trnName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withTitle01(String title01) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withCurDate(String curDate) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withPgmName(String pgmName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withTitle02(String title02) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withCurTime(String curTime) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withFName(String fName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withLName(String lName) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withUserId(String userId) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withPasswd(String passwd) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withUsrType(String usrType) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withErrMsg(String errMsg) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the communication area replaced.
     *
     * @param navigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte
     *     {@code CARDDEMO-COMMAREA} handed back to the client
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNavigationContext(NavigationContext navigationContext) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the transfer target replaced - the stateless form of
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COUSR01C.cbl:176}.
     *
     * @param nextProgram the program the client should call next, {@link #NEXT_PROGRAM_LENGTH} characters:
     *     {@code 'COSGN00C'} for the sign-on screen or {@code 'COADM01C'} for the administration menu
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNextProgram(String nextProgram) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * Returns a copy with the target mapset replaced.
     *
     * @param nextMapset the mapset the client should render next, {@link #NEXT_MAPSET_LENGTH} characters -
     *     seven, not eight
     * @return a new instance; this one is unchanged
     */
    public UserAddResponse withNextMapset(String nextMapset) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    public UserAddResponse withNextMap(String nextMap) {
        return new UserAddResponse(trnName, title01, curDate, pgmName, title02, curTime, fName, lName,
                                   userId, passwd, usrType, errMsg, navigationContext, nextProgram,
                                   nextMapset, nextMap);
    }

    /**
     * A diagnostic rendering of every member except {@link #passwd()}, which is replaced by a fixed
     * placeholder.
     *
     * @return a rendering safe to log
     */
    @Override
    public String toString() {
        return "UserAddResponse[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", userId=" + userId
                + ", passwd=" + PASSWD_NOT_RENDERED
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + "]";
    }
}
