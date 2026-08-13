package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.constraints.Size;

/**
 * The inbound payload of {@code POST /api/signon} - CICS transaction {@code CC00}, program {@code COSGN00C}
 * - as an immutable value.
 *
 * <p>{@code app/bms/COSGN00.bms} contains 37 {@code DFHMDF} definitions of which exactly
 * {@value #MAP_FIELD_COUNT} are name-labelled; the other 26 are literal {@code INITIAL} screen furniture -
 * captions and box characters - and are deliberately absent here.
 *
 * @param trnName {@code TRNNAMEI PIC X(4)}, {@code COSGN00.CPY:24}: the transaction identifier,
 *     {@code 'CC00'} per {@code COSGN00C.cbl:37}
 * @param title01 {@code TITLE01I PIC X(40)}, {@code COSGN00.CPY:30}: the first title line, supplied from
 *     {@code common.ScreenTitles}
 * @param curDate {@code CURDATEI PIC X(8)}, {@code COSGN00.CPY:36}: the current date as {@code MM/DD/YY},
 *     assembled at {@code COSGN00C.cbl:186-190}
 * @param pgmName {@code PGMNAMEI PIC X(8)}, {@code COSGN00.CPY:42}: the program name, {@code 'COSGN00C'}
 *     per {@code COSGN00C.cbl:36}
 * @param title02 {@code TITLE02I PIC X(40)}, {@code COSGN00.CPY:48}: the second title line, supplied from
 *     {@code common.ScreenTitles}
 * @param curTime {@code CURTIMEI PIC X(9)}, {@code COSGN00.CPY:54}: the current time as {@code HH:MM:SS},
 *     assembled at {@code COSGN00C.cbl:192-196}
 * @param applId {@code APPLIDI PIC X(8)}, {@code COSGN00.CPY:60}: the CICS application identifier from
 *     {@code EXEC CICS ASSIGN APPLID} at {@code COSGN00C.cbl:198-200}
 * @param sysId {@code SYSIDI PIC X(8)}, {@code COSGN00.CPY:66}: the CICS system identifier from
 *     {@code EXEC CICS ASSIGN SYSID} at {@code COSGN00C.cbl:202-204}
 * @param userId {@code USERIDI PIC X(8)}, {@code COSGN00.CPY:72}: the identifier keyed by the user,
 *     matching {@code SEC-USR-ID PIC X(08)} of {@code CSUSR01Y.cpy:18}
 * @param passwd {@code PASSWDI PIC X(8)}, {@code COSGN00.CPY:78}: the password keyed by the user, matching
 *     {@code SEC-USR-PWD PIC X(08)} of {@code CSUSR01Y.cpy:21}
 * @param errMsg {@code ERRMSGI PIC X(78)}, {@code COSGN00.CPY:84}: the error line. 78 wide against an
 *     80-byte {@code WS-MESSAGE}; the narrowing move is the controller's
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA} of {@code app/cpy/COCOM01Y.cpy}
 * @param aid the resolved key indication, the {@code EIBAID} that {@code COSGN00C} tests inline, as the
 *     token produced by {@code common.PfKeyResolver} - {@code 'ENTER'}, {@code 'PFK03'} and so on
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA,
        ResponseOnlyMembers.ROLE,
        ResponseOnlyMembers.PLAIN_TEXT})
public record SignOnRequest(@Size(max = TRNNAME_LENGTH) @JsonProperty("trnname") String trnName,
                            @Size(max = TITLE01_LENGTH) String title01,
                            @Size(max = CURDATE_LENGTH) @JsonProperty("curdate") String curDate,
                            @Size(max = PGMNAME_LENGTH) @JsonProperty("pgmname") String pgmName,
                            @Size(max = TITLE02_LENGTH) String title02,
                            @Size(max = CURTIME_LENGTH) @JsonProperty("curtime") String curTime,
                            @Size(max = APPLID_LENGTH) @JsonProperty("applid") String applId,
                            @Size(max = SYSID_LENGTH) @JsonProperty("sysid") String sysId,
                            @Size(max = USERID_LENGTH) @JsonProperty("userid") String userId,
                            @Size(max = PASSWD_LENGTH) String passwd,
                            @Size(max = ERRMSG_LENGTH) @JsonProperty("errmsg") String errMsg,
                            NavigationContext navigationContext,
                            @Size(max = AID_LENGTH) String aid) {
    /**
     * {@code TRNNAMEI PIC X(4)}, {@code COSGN00.CPY:24}; {@code TRNNAME DFHMDF LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, {@code COSGN00.CPY:30}; {@code TITLE01 DFHMDF LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, {@code COSGN00.CPY:36}; {@code CURDATE DFHMDF LENGTH=8}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, {@code COSGN00.CPY:42}; {@code PGMNAME DFHMDF LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}, {@code COSGN00.CPY:48}; {@code TITLE02 DFHMDF LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(9)}, {@code COSGN00.CPY:54}; {@code CURTIME DFHMDF LENGTH=9}.
     */
    public static final int CURTIME_LENGTH = 9;

    /**
     * {@code APPLIDI PIC X(8)}, {@code COSGN00.CPY:60}; {@code APPLID DFHMDF LENGTH=8}.
     */
    public static final int APPLID_LENGTH = 8;

    /**
     * {@code SYSIDI PIC X(8)}, {@code COSGN00.CPY:66}; {@code SYSID DFHMDF LENGTH=8}.
     */
    public static final int SYSID_LENGTH = 8;

    /**
     * {@code USERIDI PIC X(8)}, {@code COSGN00.CPY:72}; {@code USERID DFHMDF LENGTH=8}.
     */
    public static final int USERID_LENGTH = 8;

    /**
     * {@code PASSWDI PIC X(8)}, {@code COSGN00.CPY:78}; {@code PASSWD DFHMDF LENGTH=8}.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code COSGN00.CPY:84}; {@code ERRMSG DFHMDF LENGTH=78}. 78, not 80.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * The width of the resolved key token, {@code CCARD-AID PIC X(5)} of {@code app/cpy/CVCRD01Y.cpy}.
     */
    public static final int AID_LENGTH = 5;

    /**
     * The number of payload members that project a name-labelled {@code DFHMDF} field:
     * {@value #MAP_FIELD_COUNT}.
     */
    public static final int MAP_FIELD_COUNT = 11;

    private static final String PASSWD_REDACTED = SensitiveDiagnostics.REDACTED;

    /**
     * Carries every component exactly as it arrives, including an absent communication area.
     */
    public SignOnRequest {
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COSGN00C.cbl:80-95}.
     *
     * @return {@code true} when {@link #navigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value NavigationContext#COMMAREA_LENGTH} when a
     * communication area travelled with this request, and {@code 0} when none did.
     *
     * <p>{@code COSGN00C} passes no extension behind {@code CARDDEMO-COMMAREA}, so the non-zero case is
     * always exactly the commarea's own width.
     *
     * @return {@value NavigationContext#COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? NavigationContext.COMMAREA_LENGTH : 0;
    }

    /**
     * Whether this request is a first entry - {@code CDEMO-PGM-CONTEXT} holding
     * {@code 88 CDEMO-PGM-ENTER VALUE 0}, {@code app/cpy/COCOM01Y.cpy:30}.
     *
     * @return {@code true} when a communication area travelled and it is in the enter state
     */
    public boolean inEnterState() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * Whether this request is a re-entry - {@code CDEMO-PGM-CONTEXT} holding
     * {@code 88 CDEMO-PGM-REENTER VALUE 1}, {@code app/cpy/COCOM01Y.cpy:31}.
     *
     * <p>Also a read-through, and deliberately not written as the negation of {@link #inEnterState()}:
     * {@code CDEMO-PGM-CONTEXT} is {@code PIC 9(01)} and may hold any digit, so for a value such as
     * {@code 9} both predicates are correctly {@code false}.
     *
     * @return {@code true} when a communication area travelled and it is in the re-enter state
     */
    public boolean inReenterState() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * A diagnostic rendering that reports every component except the password, which is replaced by
     * {@link SensitiveDiagnostics#REDACTED}.
     *
     * <p>Carrying the credential in the clear is required for parity with {@code app/cbl/COSGN00C.cbl:223};
     * broadcasting it is not, and the two concerns are separable.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "SignOnRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", applId=" + applId
                + ", sysId=" + sysId
                + ", userId=" + userId
                + ", passwd=" + PASSWD_REDACTED
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ']';
    }
}
