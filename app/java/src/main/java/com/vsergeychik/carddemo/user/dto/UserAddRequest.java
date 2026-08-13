package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The inbound payload of {@code POST /api/users} - CICS transaction {@code CU01}, program
 * {@code app/cbl/COUSR01C.cbl}, the Add User screen.
 *
 * <p>The 80-to-78 truncation itself belongs to the controller and is performed by
 * {@code common.FixedWidthCodec.movePicX}, which truncates on the right exactly as COBOL does.
 *
 * @param trnName {@code TRNNAMEI PIC X(4)} of {@code app/cpy-bms/COUSR01.CPY:24}: the transaction
 *     identifier shown in the header, {@link #TRANSACTION_ID}
 * @param title01 {@code TITLE01I PIC X(40)} of line 30: the first title line
 * @param curDate {@code CURDATEI PIC X(8)} of line 36: the current date as {@code mm/dd/yy}
 * @param pgmName {@code PGMNAMEI PIC X(8)} of line 42: the program name, {@link #PROGRAM_NAME}
 * @param title02 {@code TITLE02I PIC X(40)} of line 48: the second title line
 * @param curTime {@code CURTIMEI PIC X(8)} of line 54: the current time as {@code hh:mm:ss}
 * @param fName {@code FNAMEI PIC X(20)} of line 60: the new user's first name, moved to
 *     {@code SEC-USR-FNAME} at {@code COUSR01C:155}
 * @param lName {@code LNAMEI PIC X(20)} of line 66: the new user's last name, moved to
 *     {@code SEC-USR-LNAME} at {@code COUSR01C:156}
 * @param userId {@code USERIDI PIC X(8)} of line 72: the new user's identifier, moved to {@code SEC-USR-ID}
 *     at {@code COUSR01C:154} and used as the {@code USRSEC} key
 * @param passwd {@code PASSWDI PIC X(8)} of line 78: the new user's password, stored in the clear into
 *     {@code SEC-USR-PWD} at {@code COUSR01C:157}
 * @param usrType {@code USRTYPEI PIC X(1)} of line 84: {@code 'A'} for an administrator, {@code 'U'} for a
 *     regular user, moved to {@code SEC-USR-TYPE} at {@code COUSR01C:158}
 * @param errMsg {@code ERRMSGI PIC X(78)} of line 90: the message line, 78 characters and not the 80 of
 *     {@code WS-MESSAGE}
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy};
 *     {@code null} represents the {@code EIBCALEN = 0} case of {@code COUSR01C:78}
 * @param aid the resolved AID token, at most {@value #AID_LENGTH} characters
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record UserAddRequest(
        @Size(max = UserAddRequest.TRNNAME_LENGTH) @JsonProperty("trnname") String trnName,
        @Size(max = UserAddRequest.TITLE01_LENGTH) String title01,
        @Size(max = UserAddRequest.CURDATE_LENGTH) @JsonProperty("curdate") String curDate,
        @Size(max = UserAddRequest.PGMNAME_LENGTH) @JsonProperty("pgmname") String pgmName,
        @Size(max = UserAddRequest.TITLE02_LENGTH) String title02,
        @Size(max = UserAddRequest.CURTIME_LENGTH) @JsonProperty("curtime") String curTime,
        @Size(max = UserAddRequest.FNAME_LENGTH) @JsonProperty("fname") String fName,
        @Size(max = UserAddRequest.LNAME_LENGTH) @JsonProperty("lname") String lName,
        @Size(max = UserAddRequest.USERID_LENGTH) @JsonProperty("userid") String userId,
        @Size(max = UserAddRequest.PASSWD_LENGTH) String passwd,
        @Size(max = UserAddRequest.USRTYPE_LENGTH) @JsonProperty("usrtype") String usrType,
        @Size(max = UserAddRequest.ERRMSG_LENGTH) @JsonProperty("errmsg") String errMsg,
        NavigationContext navigationContext,
        @Size(max = UserAddRequest.AID_LENGTH) String aid) {
    // The COBOL names of the twelve payload fields, spelled exactly as app/cpy-bms/COUSR01.CPY spells them.
    // These are what a field-by-field diff keys on, so a "tidied" spelling here would hide a real
    // difference.

    /**
     * Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEI}, {@code COUSR01.CPY:24}.
     */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /**
     * Symbolic-map item behind {@link #title01()}: {@code TITLE01I}, {@code COUSR01.CPY:30}.
     */
    public static final String TITLE01_FIELD = "TITLE01I";

    /**
     * Symbolic-map item behind {@link #curDate()}: {@code CURDATEI}, {@code COUSR01.CPY:36}.
     */
    public static final String CURDATE_FIELD = "CURDATEI";

    /**
     * Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEI}, {@code COUSR01.CPY:42}.
     */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /**
     * Symbolic-map item behind {@link #title02()}: {@code TITLE02I}, {@code COUSR01.CPY:48}.
     */
    public static final String TITLE02_FIELD = "TITLE02I";

    /**
     * Symbolic-map item behind {@link #curTime()}: {@code CURTIMEI}, {@code COUSR01.CPY:54}.
     */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /**
     * Symbolic-map item behind {@link #fName()}: {@code FNAMEI}, {@code COUSR01.CPY:60}.
     */
    public static final String FNAME_FIELD = "FNAMEI";

    /**
     * Symbolic-map item behind {@link #lName()}: {@code LNAMEI}, {@code COUSR01.CPY:66}.
     */
    public static final String LNAME_FIELD = "LNAMEI";

    /**
     * Symbolic-map item behind {@link #userId()}: {@code USERIDI}, {@code COUSR01.CPY:72}.
     */
    public static final String USERID_FIELD = "USERIDI";

    /**
     * Symbolic-map item behind {@link #passwd()}: {@code PASSWDI}, {@code COUSR01.CPY:78}.
     */
    public static final String PASSWD_FIELD = "PASSWDI";

    /**
     * Symbolic-map item behind {@link #usrType()}: {@code USRTYPEI}, {@code COUSR01.CPY:84}.
     */
    public static final String USRTYPE_FIELD = "USRTYPEI";

    /**
     * Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGI}, {@code COUSR01.CPY:90}.
     */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    /**
     * {@code TRNNAMEI PIC X(4)}; {@code DFHMDF TRNNAME LENGTH=4} at {@code COUSR01.bms:36}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}; {@code DFHMDF TITLE01 LENGTH=40} at {@code COUSR01.bms:40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}; {@code DFHMDF CURDATE LENGTH=8} at {@code COUSR01.bms:49}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}; {@code DFHMDF PGMNAME LENGTH=8} at {@code COUSR01.bms:59}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}; {@code DFHMDF TITLE02 LENGTH=40} at {@code COUSR01.bms:63}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}; {@code DFHMDF CURTIME LENGTH=8} at {@code COUSR01.bms:72}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code FNAMEI PIC X(20)}; {@code DFHMDF FNAME LENGTH=20} at {@code COUSR01.bms:87}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEI PIC X(20)}; {@code DFHMDF LNAME LENGTH=20} at {@code COUSR01.bms:100}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code USERIDI PIC X(8)}; {@code DFHMDF USERID LENGTH=8} at {@code COUSR01.bms:114}.
     */
    public static final int USERID_LENGTH = 8;

    /**
     * {@code PASSWDI PIC X(8)}; {@code DFHMDF PASSWD LENGTH=8} at {@code COUSR01.bms:129}.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code USRTYPEI PIC X(1)}; {@code DFHMDF USRTYPE LENGTH=1} at {@code COUSR01.bms:144}.
     */
    public static final int USRTYPE_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}; {@code DFHMDF ERRMSG LENGTH=78} at {@code COUSR01.bms:153}. 78, not 80.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * The declared width of {@link #aid()}, matching {@code common.PfKeyResolver}'s AID token length and
     * {@code CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy}, the copybook that gives the token its
     * canonical five-character form.
     */
    public static final int AID_LENGTH = 5;

    /**
     * {@code WS-TRANID VALUE 'CU01'} of {@code COUSR01C:37}, the CSD transaction for this screen.
     */
    public static final String TRANSACTION_ID = "CU01";

    /**
     * {@code WS-PGMNAME VALUE 'COUSR01C'} of {@code COUSR01C:36}.
     */
    public static final String PROGRAM_NAME = "COUSR01C";

    public static final String MAP_NAME = "COUSR1A";

    /**
     * The BMS mapset, {@code MAPSET('COUSR01')} of {@code COUSR01C:192}.
     */
    public static final String MAPSET_NAME = "COUSR01";

    public static final String PASSWORD_MASK = SensitiveDiagnostics.REDACTED;

    /**
     * The twelve payload field names in symbolic-map order, immutable.
     */
    public static final List<String> MAP_FIELD_NAMES = List.of(TRNNAME_FIELD,
            TITLE01_FIELD,
            CURDATE_FIELD,
            PGMNAME_FIELD,
            TITLE02_FIELD,
            CURTIME_FIELD,
            FNAME_FIELD,
            LNAME_FIELD,
            USERID_FIELD,
            PASSWD_FIELD,
            USRTYPE_FIELD,
            ERRMSG_FIELD);

    /**
     * Whether the carried communication area asserts {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry, on
     * which {@code COUSR01C} paints the screen and validates nothing.
     *
     * <p>The value is read from {@link NavigationContext#isEnter()} on every call and is stored nowhere
     * here, so it cannot drift out of step with the {@code CDEMO-PGM-CONTEXT} byte it summarises.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *     {@code CDEMO-PGM-ENTER} value
     */
    public boolean pgmEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether the carried communication area asserts {@code 88 CDEMO-PGM-REENTER VALUE 1} - re-entry, on
     * which {@code COUSR01C:88-103} receives the map and evaluates {@code EIBAID}, and on which the ordered
     * blank-field chain of {@code COUSR01C:117-151} runs.
     *
     * @return {@code true} only when a communication area is present and its program context is the
     *     {@code CDEMO-PGM-REENTER} value
     */
    public boolean pgmReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    /**
     * A diagnostic rendering that reproduces the shape a record's generated {@code toString()} would
     * produce, with one difference: {@link #passwd()} is replaced by {@link #PASSWORD_MASK}.
     *
     * @return the masked rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "UserAddRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", userId=" + userId
                + ", passwd=" + PASSWORD_MASK
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ']';
    }
}
