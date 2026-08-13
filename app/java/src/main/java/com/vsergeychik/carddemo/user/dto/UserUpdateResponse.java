package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.user.dto.UserUpdateRequest.Cu02Info;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code PUT /api/users/&#123;userId&#125;} - CICS transaction {@code CU02},
 * program {@code COUSR02C}, mapset {@code COUSR02}, map {@code COUSR2A} - as an immutable value.
 *
 * <p>A blanket instruction circulates for this package: a response should never echo the password, because
 * {@code SEND-SIGNON-SCREEN} does not send it back.
 *
 * @param trnName {@code TRNNAMEO PIC X(4)} - the transaction name, {@code 'CU02'}; written at
 *     {@code COUSR02C} line 302
 * @param title01 {@code TITLE01O PIC X(40)} - the first title line, written at line 300
 * @param curDate {@code CURDATEO PIC X(8)} - the current date as {@code MM/DD/YY}, written at line 309
 * @param pgmName {@code PGMNAMEO PIC X(8)} - the program name, {@code 'COUSR02C'}; written at line 303
 * @param title02 {@code TITLE02O PIC X(40)} - the second title line, written at line 301
 * @param curTime {@code CURTIMEO PIC X(8)} - the current time as {@code HH:MM:SS}, written at line 315
 * @param usrIdIn {@code USRIDINO PIC X(8)} - the user id being updated
 * @param fName {@code FNAMEO PIC X(20)} - the first name, from {@code SEC-USR-FNAME} at line 167
 * @param lName {@code LNAMEO PIC X(20)} - the last name, from {@code SEC-USR-LNAME} at line 168
 * @param passwd {@code PASSWDO PIC X(8)} - the stored plaintext password, from {@code SEC-USR-PWD} at line
 *     169
 * @param usrType {@code USRTYPEO PIC X(1)} - the user type, {@code 'A'} for an administrator or {@code 'U'}
 *     for a regular user, from {@code SEC-USR-TYPE} at line 170
 * @param errMsg {@code ERRMSGO PIC X(78)} - the message line. 78 characters, receiving an 80-character
 *     {@code WS-MESSAGE} that line 270 truncates on the right
 * @param navigationContext the 160-byte {@code CARDDEMO-COMMAREA}
 * @param nextProgram the {@code XCTL} target of line 259, projected as response data so the client
 *     navigates
 * @param nextMapset the mapset to display next
 * @param nextMap the map to display next
 * @param cu02Info the {@code 05 CDEMO-CU02-INFO} extension the program appends to {@code CARDDEMO-COMMAREA}
 *     at {@code app/cbl/COUSR02C.cbl:50}
 */
public record UserUpdateResponse(@JsonProperty("trnname") String trnName,
                                 String title01,
                                 @JsonProperty("curdate") String curDate,
                                 @JsonProperty("pgmname") String pgmName,
                                 String title02,
                                 @JsonProperty("curtime") String curTime,
                                 @JsonProperty("usridin") String usrIdIn,
                                 @JsonProperty("fname") String fName,
                                 @JsonProperty("lname") String lName,
                                 @JsonIgnore String passwd,
                                 @JsonProperty("usrtype") String usrType,
                                 @JsonProperty("errmsg") String errMsg,
                                 NavigationContext navigationContext,
                                 String nextProgram,
                                 String nextMapset,
                                 String nextMap,
                                 Cu02Info cu02Info) {
    /**
     * The BMS map name, {@code COUSR2A}, as {@code app/cbl/COUSR02C.cbl} line 273 names it in
     * {@code MAP('COUSR2A')}.
     */
    public static final String MAP_NAME = "COUSR2A";

    /**
     * The BMS mapset name, {@code COUSR02}, as line 274 names it in {@code MAPSET('COUSR02')}.
     */
    public static final String MAPSET_NAME = "COUSR02";

    /**
     * The CICS transaction id, {@code CU02}, declared as {@code WS-TRANID PIC X(04) VALUE 'CU02'} at
     * {@code app/cbl/COUSR02C.cbl} line 37 and moved to {@code TRNNAMEO} at line 302.
     */
    public static final String TRANSACTION_ID = "CU02";

    /**
     * The program name, {@code COUSR02C}, declared as {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} at line
     * 36 and moved to {@code PGMNAMEO} at line 303.
     */
    public static final String PROGRAM_NAME = "COUSR02C";

    /**
     * The name of the symbolic-map group item this type projects, {@code 01 COUSR2AO REDEFINES COUSR2AI} at
     * {@code app/cpy-bms/COUSR02.CPY} line 91.
     */
    public static final String GROUP_NAME = "COUSR2AO";

    /**
     * The number of name-labelled {@code DFHMDF} fields on this mapset, and therefore the number of
     * map-derived members this type declares: 12.
     */
    public static final int MAP_FIELD_COUNT = 12;

    // Field names, spelled exactly as app/cpy-bms/COUSR02.CPY spells the xxxO items. These are the names a
    // field-by-field comparison keys on, so a "tidied" spelling would hide a real difference.

    /**
     * {@code TRNNAMEO}, copybook line 98.
     */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /**
     * {@code TITLE01O}, copybook line 104.
     */
    public static final String TITLE01_FIELD = "TITLE01O";

    /**
     * {@code CURDATEO}, copybook line 110.
     */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /**
     * {@code PGMNAMEO}, copybook line 116.
     */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /**
     * {@code TITLE02O}, copybook line 122.
     */
    public static final String TITLE02_FIELD = "TITLE02O";

    /**
     * {@code CURTIMEO}, copybook line 128.
     */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /**
     * {@code USRIDINO}, copybook line 134.
     */
    public static final String USR_ID_IN_FIELD = "USRIDINO";

    /**
     * {@code FNAMEO}, copybook line 140.
     */
    public static final String FNAME_FIELD = "FNAMEO";

    /**
     * {@code LNAMEO}, copybook line 146.
     */
    public static final String LNAME_FIELD = "LNAMEO";

    /**
     * {@code PASSWDO}, copybook line 152.
     */
    public static final String PASSWD_FIELD = "PASSWDO";

    /**
     * {@code USRTYPEO}, copybook line 158.
     */
    public static final String USR_TYPE_FIELD = "USRTYPEO";

    /**
     * {@code ERRMSGO}, copybook line 164.
     */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    /**
     * {@code CDEMO-TO-PROGRAM}, {@code app/cpy/COCOM01Y.cpy} line 24 - the {@code XCTL} target.
     */
    public static final String NEXT_PROGRAM_FIELD = "CDEMO-TO-PROGRAM";

    /**
     * {@code CDEMO-LAST-MAPSET}, {@code app/cpy/COCOM01Y.cpy} line 44.
     */
    public static final String NEXT_MAPSET_FIELD = "CDEMO-LAST-MAPSET";

    /**
     * {@code CDEMO-LAST-MAP}, {@code app/cpy/COCOM01Y.cpy} line 43.
     */
    public static final String NEXT_MAP_FIELD = "CDEMO-LAST-MAP";

    // Every one is a literal read straight off an xxxO PICTURE clause - never inferred from a sample value,
    // never derived from another constant, and never shared between two fields that merely happen to be the
    // same width today.

    /**
     * {@code TRNNAMEO PIC X(4)}, copybook line 98.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)}, copybook line 104.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, copybook line 110 - {@code MM/DD/YY}.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}, copybook line 116.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)}, copybook line 122.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)}, copybook line 128 - {@code HH:MM:SS}.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code USRIDINO PIC X(8)}, copybook line 134 - and {@code SEC-USR-ID PIC X(08)}.
     */
    public static final int USR_ID_IN_LENGTH = 8;

    /**
     * {@code FNAMEO PIC X(20)}, copybook line 140 - and {@code SEC-USR-FNAME PIC X(20)}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEO PIC X(20)}, copybook line 146 - and {@code SEC-USR-LNAME PIC X(20)}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code PASSWDO PIC X(8)}, copybook line 152 - and {@code SEC-USR-PWD PIC X(08)} in
     * {@code app/cpy/CSUSR01Y.cpy} line 21, the field line 169 moves from.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * The {@value #PASSWD_LENGTH}-character marker published under the name {@code passwd} in place of
     * the stored password, and the value a client sends back to mean "the operator did not touch this
     * field".
     *
     * <p>It is exactly the field's declared width, so it is a legal {@code PASSWDO} image and needs no
     * special handling anywhere in the codec, and it is deliberately not blank: line 198 of
     * {@code app/cbl/COUSR02C.cbl} rejects a blank password with "Password can NOT be empty", so a
     * blank marker would leave a client unable to change a first name without also inventing a
     * password. {@code UserUpdateController} substitutes the stored {@code SEC-USR-PWD} for this value
     * before line 227's {@code IF PASSWDI NOT = SEC-USR-PWD}, so an untouched field reads equal, the
     * {@code USR-MODIFIED-YES} flag is not set spuriously, and the locked rewrite carries the stored
     * secret through unchanged - which is precisely the outcome line 169 exists to produce.
     *
     * <p><strong>The one bounded consequence:</strong> this literal cannot itself be assigned as a
     * password through this route, because a client sending it is understood to mean "unchanged". Eight
     * asterisks is not a credential anyone sets deliberately, and the alternative - publishing the
     * stored secret so that one implausible string remains assignable - trades a real exposure for a
     * theoretical capability.
     */
    public static final String PASSWD_UNCHANGED = "********";

    /** {@code USRTYPEO PIC X(1)}, copybook line 158 - and {@code SEC-USR-TYPE PIC X(01)}. */
    public static final int USR_TYPE_LENGTH = 1;

    /**
     * {@code ERRMSGO PIC X(78)}, copybook line 164. 78, not 80.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * {@code CDEMO-TO-PROGRAM PIC X(08)}, {@code app/cpy/COCOM01Y.cpy} line 24.
     */
    public static final int NEXT_PROGRAM_LENGTH = 8;

    /**
     * {@code CDEMO-LAST-MAPSET PIC X(7)}, {@code app/cpy/COCOM01Y.cpy} line 44 - seven, not eight.
     */
    public static final int NEXT_MAPSET_LENGTH = 7;

    /**
     * {@code CDEMO-LAST-MAP PIC X(7)}, {@code app/cpy/COCOM01Y.cpy} line 43 - seven, not eight.
     */
    public static final int NEXT_MAP_LENGTH = 7;

    /**
     * The twelve map-derived field names in screen order, which is the order
     * {@code grep -E '^[A-Z0-9]+ +DFHMDF' app/bms/COUSR02.bms} reports and therefore the order a
     * field-by-field comparison walks.
     */
    public static final List<String> MAP_FIELD_NAMES = List.of(TRN_NAME_FIELD,
            TITLE01_FIELD,
            CUR_DATE_FIELD,
            PGM_NAME_FIELD,
            TITLE02_FIELD,
            CUR_TIME_FIELD,
            USR_ID_IN_FIELD,
            FNAME_FIELD,
            LNAME_FIELD,
            PASSWD_FIELD,
            USR_TYPE_FIELD,
            ERR_MSG_FIELD);

    private static final String PASSWORD_PLACEHOLDER = "****(withheld)";

    private static final String SPACE = " ";

    /**
     * Checks every character member against the width its {@code xxxO} item declares, and supplies an
     * initialised communication area in place of a {@code null} one.
     *
     * <p>{@code null} is rejected for every character member, because a COBOL {@code PIC X} field is never
     * absent - an unset one holds spaces, which is exactly what {@link #blank()} produces.
     */
    public UserUpdateResponse {
        trnName = requireWidth(trnName, TRN_NAME_LENGTH, TRN_NAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CUR_DATE_LENGTH, CUR_DATE_FIELD);
        pgmName = requireWidth(pgmName, PGM_NAME_LENGTH, PGM_NAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CUR_TIME_LENGTH, CUR_TIME_FIELD);
        usrIdIn = requireWidth(usrIdIn, USR_ID_IN_LENGTH, USR_ID_IN_FIELD);
        fName = requireWidth(fName, FNAME_LENGTH, FNAME_FIELD);
        lName = requireWidth(lName, LNAME_LENGTH, LNAME_FIELD);
        // The component is not published, so this is the one field a body serialised and read back
        // arrives without. It is normalised to the unpainted image rather than refused, for the same
        // reason SignOnResponse and UserAddResponse normalise theirs: a type that rejected null there
        // could not represent its own wire form. The distinction between "the screen held a password"
        // and "it held none" is genuinely lost on that leg, and the blank image is the safe reading of
        // the two - it can never be mistaken for a value the operator left in place. Nothing in the
        // program flow depends on it, because what the client sends back is read as UserUpdateRequest.
        passwd = passwd == null
                ? spaces(PASSWD_LENGTH)
                : requireWidth(passwd, PASSWD_LENGTH, PASSWD_FIELD);
        usrType = requireWidth(usrType, USR_TYPE_LENGTH, USR_TYPE_FIELD);
        errMsg = requireWidth(errMsg, ERR_MSG_LENGTH, ERR_MSG_FIELD);
        navigationContext = navigationContext == null ? NavigationContext.empty() : navigationContext;
        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH, NEXT_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH, NEXT_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NEXT_MAP_FIELD);
        cu02Info = cu02Info == null ? Cu02Info.initial() : cu02Info;
    }

    /**
     * The screen as {@code EXEC CICS SEND ... ERASE} leaves it: every one of the twelve map fields a run of
     * spaces of its declared width, no navigation target set, and an initialised communication area.
     *
     * @return a blank response, never {@code null}
     */
    public static UserUpdateResponse blank() {
        return new UserUpdateResponse(spaces(TRN_NAME_LENGTH),
                spaces(TITLE01_LENGTH),
                spaces(CUR_DATE_LENGTH),
                spaces(PGM_NAME_LENGTH),
                spaces(TITLE02_LENGTH),
                spaces(CUR_TIME_LENGTH),
                spaces(USR_ID_IN_LENGTH),
                spaces(FNAME_LENGTH),
                spaces(LNAME_LENGTH),
                spaces(PASSWD_LENGTH),
                spaces(USR_TYPE_LENGTH),
                spaces(ERR_MSG_LENGTH),
                NavigationContext.empty(),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                Cu02Info.initial());
    }

    /**
     * A copy with {@code TRNNAMEO} replaced - the transaction name written at line 302.
     *
     * @param newTrnName the transaction name, normally {@link #TRANSACTION_ID}; at most
     *     {@value #TRN_NAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newTrnName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #TRN_NAME_LENGTH} characters
     */
    public UserUpdateResponse withTrnName(String newTrnName) {
        return new UserUpdateResponse(newTrnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code TITLE01O} replaced - the first title line written at line 300 from
     * {@code CCDA-TITLE01}, the literal owned by {@code common.ScreenTitles}.
     *
     * @param newTitle01 the title text; at most {@value #TITLE01_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newTitle01} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #TITLE01_LENGTH} characters
     */
    public UserUpdateResponse withTitle01(String newTitle01) {
        return new UserUpdateResponse(trnName, newTitle01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code CURDATEO} replaced - the {@code MM/DD/YY} date written at line 309 from the header
     * owned by {@code common.DateHeader}.
     *
     * @param newCurDate the date text; at most {@value #CUR_DATE_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newCurDate} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #CUR_DATE_LENGTH} characters
     */
    public UserUpdateResponse withCurDate(String newCurDate) {
        return new UserUpdateResponse(trnName, title01, newCurDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code PGMNAMEO} replaced - the program name written at line 303.
     *
     * @param newPgmName the program name, normally {@link #PROGRAM_NAME}; at most {@value #PGM_NAME_LENGTH}
     *     characters
     * @return a new response
     * @throws NullPointerException if {@code newPgmName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #PGM_NAME_LENGTH} characters
     */
    public UserUpdateResponse withPgmName(String newPgmName) {
        return new UserUpdateResponse(trnName, title01, curDate, newPgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code TITLE02O} replaced - the second title line written at line 301.
     *
     * @param newTitle02 the title text; at most {@value #TITLE02_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newTitle02} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #TITLE02_LENGTH} characters
     */
    public UserUpdateResponse withTitle02(String newTitle02) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, newTitle02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code CURTIMEO} replaced - the {@code HH:MM:SS} time written at line 315.
     *
     * @param newCurTime the time text; at most {@value #CUR_TIME_LENGTH} characters, which is eight and not
     *     nine
     * @return a new response
     * @throws NullPointerException if {@code newCurTime} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #CUR_TIME_LENGTH} characters
     */
    public UserUpdateResponse withCurTime(String newCurTime) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, newCurTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code USRIDINO} replaced - the user id being updated.
     *
     * @param newUsrIdIn the user id; at most {@value #USR_ID_IN_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newUsrIdIn} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #USR_ID_IN_LENGTH} characters
     */
    public UserUpdateResponse withUsrIdIn(String newUsrIdIn) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                newUsrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code FNAMEO} replaced - the first name, which line 167 moves from
     * {@code SEC-USR-FNAME}.
     *
     * @param newFName the first name; at most {@value #FNAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newFName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #FNAME_LENGTH} characters
     */
    public UserUpdateResponse withFName(String newFName) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, newFName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code LNAMEO} replaced - the last name, which line 168 moves from {@code SEC-USR-LNAME}.
     *
     * @param newLName the last name; at most {@value #LNAME_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newLName} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #LNAME_LENGTH} characters
     */
    public UserUpdateResponse withLName(String newLName) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, newLName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code PASSWDO} replaced - the stored plaintext password that
     * {@code app/cbl/COUSR02C.cbl} line 169 moves from {@code SEC-USR-PWD} onto the screen.
     *
     * @param newPasswd the plaintext password; at most {@value #PASSWD_LENGTH} characters, matching
     *     {@code SEC-USR-PWD PIC X(08)}
     * @return a new response
     * @throws NullPointerException if {@code newPasswd} is {@code null}; pass spaces to blank the field as
     *     lines 155-158 do
     * @throws IllegalArgumentException if it exceeds {@value #PASSWD_LENGTH} characters
     */
    public UserUpdateResponse withPasswd(String newPasswd) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, newPasswd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code USRTYPEO} replaced - the user type, which line 170 moves from
     * {@code SEC-USR-TYPE}.
     *
     * @param newUsrType the user type, {@code 'A'} for an administrator or {@code 'U'} for a regular user
     *     as the screen's own {@code '(A=Admin, U=User)'} legend states; at most {@value #USR_TYPE_LENGTH}
     *     character
     * @return a new response
     * @throws NullPointerException if {@code newUsrType} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #USR_TYPE_LENGTH} character
     */
    public UserUpdateResponse withUsrType(String newUsrType) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, newUsrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy with {@code ERRMSGO} replaced - the message line written at lines 88 and 270.
     *
     * @param newErrMsg the message text; at most {@value #ERR_MSG_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newErrMsg} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #ERR_MSG_LENGTH} characters
     */
    public UserUpdateResponse withErrMsg(String newErrMsg) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, newErrMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy carrying a different communication area - the 160-byte {@code CARDDEMO-COMMAREA} that line 260
     * hands back on the {@code XCTL}.
     *
     * @param newNavigationContext the communication area; {@code null} is replaced by
     *     {@link NavigationContext#empty()}
     * @return a new response
     */
    public UserUpdateResponse withNavigationContext(NavigationContext newNavigationContext) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, newNavigationContext, nextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy naming a different transfer target - the {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} of line 259,
     * projected as response data so the client performs the navigation.
     *
     * @param newNextProgram the program to transfer to; at most {@value #NEXT_PROGRAM_LENGTH} characters
     * @return a new response
     * @throws NullPointerException if {@code newNextProgram} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #NEXT_PROGRAM_LENGTH} characters
     */
    public UserUpdateResponse withNextProgram(String newNextProgram) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, newNextProgram,
                nextMapset, nextMap, cu02Info);
    }

    /**
     * A copy naming a different mapset to display next.
     *
     * @param newNextMapset the mapset name, {@link #MAPSET_NAME} for this screen; at most
     *     {@value #NEXT_MAPSET_LENGTH} characters - seven, not eight
     * @return a new response
     * @throws NullPointerException if {@code newNextMapset} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #NEXT_MAPSET_LENGTH} characters
     */
    public UserUpdateResponse withNextMapset(String newNextMapset) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                newNextMapset, nextMap, cu02Info);
    }

    /**
     * A copy naming a different map to display next.
     *
     * @param newNextMap the map name, {@link #MAP_NAME} for this screen; at most {@value #NEXT_MAP_LENGTH}
     *     characters - seven, not eight
     * @return a new response
     * @throws NullPointerException if {@code newNextMap} is {@code null}
     * @throws IllegalArgumentException if it exceeds {@value #NEXT_MAP_LENGTH} characters
     */
    public UserUpdateResponse withNextMap(String newNextMap) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, newNextMap, cu02Info);
    }

    /**
     * A copy carrying a different {@code 05 CDEMO-CU02-INFO} extension - the thirty-four bytes
     * {@code app/cbl/COUSR02C.cbl:50-58} appends to the communication area and line 260 hands back on the
     * {@code XCTL} along with it.
     *
     * <p>Carried separately from {@link #navigationContext()} because {@code app/cpy/COCOM01Y.cpy} is
     * exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and is shared by all seventeen controllers,
     * while this group belongs to this program alone.
     *
     * @param newCu02Info the extension; {@code null} is replaced by {@link Cu02Info#initial()}
     * @return a new response
     */
    public UserUpdateResponse withCu02Info(Cu02Info newCu02Info) {
        return new UserUpdateResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, passwd, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, newCu02Info);
    }

    // =============================================================================================
    // The published stand-in for the password member.
    // =============================================================================================

    /**
     * What crosses the wire under the name {@code passwd}: always {@link #PASSWD_UNCHANGED} when the
     * screen holds a password, and the blank image when it does not.
     *
     * <p>{@link #passwd()} keeps the {@code PASSWDO} image faithfully, because that is what line 169
     * puts on the screen and this type's whole contract is to project the map field-for-field. This
     * accessor is what Jackson publishes in its place, and it is the only member of this payload whose
     * wire value is not its component value. The reason is that the two transports differ in what they
     * guarantee: {@code ATTRB=(DRK,FSET,UNPROT)} at {@code app/bms/COUSR02.bms} line 130 sends the
     * eight characters to exactly one device and renders them invisibly there, whereas a JSON member
     * has no {@code DRK} bit and no single recipient and is picked up by access logs, proxy caches and
     * error reports on the way.
     *
     * <p>A blank screen field is published blank rather than as the marker, because "no user has been
     * read yet" is an observable state a client must be able to see: submitting it produces line 198's
     * "Password can NOT be empty" exactly as it does at a terminal. Only a screen that actually holds
     * a password is stood in for.
     *
     * <p>The member is read-only on the wire: a client's echo of it is read back as
     * {@code UserUpdateRequest}, which is the inbound half of the pair and where a typed credential
     * belongs. Deserialising a <em>response</em> body therefore leaves {@link #passwd()} at the
     * unpainted image rather than at whatever this accessor published, and nothing in the program flow
     * depends on that leg.
     *
     * @return {@link #PASSWD_UNCHANGED}, or a {@value #PASSWD_LENGTH}-character blank image when the
     *         screen's password field is blank; never {@code null}
     */
    @JsonProperty("passwd")
    public String passwdOnTheWire() {
        // isBlankImage rather than isBlank(): a LOW-VALUES span of x'00' bytes is the "nothing was
        // sent" image this screen genuinely produces, and it is not whitespace.
        return isBlankImage(passwd) ? spaces(PASSWD_LENGTH) : PASSWD_UNCHANGED;
    }

    /**
     * Whether an image carries nothing an operator typed - all spaces, all {@code LOW-VALUES}, or
     * empty.
     *
     * <p>No null check: the compact constructor normalises the only component this is asked about, so a
     * null cannot reach here and a guard for one would be an untestable branch.
     *
     * @param image the value to test, never {@code null}
     * @return {@code true} when every character is a space or {@code x'00'}, or there are none
     */
    private static boolean isBlankImage(String image) {
        if (image.isEmpty()) {
            return true;
        }
        for (int index = 0; index < image.length(); index++) {
            char character = image.charAt(index);
            if (character != ' ' && character != '\u0000') {
                return false;
            }
        }
        return true;
    }

    // =============================================================================================
    // Field projection, for field-by-field comparison and for tracing every member back to its
    // DFHMDF definition.
    // =============================================================================================

    /**
     * The twelve map-derived values keyed by the {@code xxxO} name the copybook spells, in screen order.
     *
     * @return an unmodifiable, screen-ordered map of all {@value #MAP_FIELD_COUNT} field names to their
     *     values, never {@code null}
     */
    public Map<String, String> fieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(TRN_NAME_FIELD, trnName);
        values.put(TITLE01_FIELD, title01);
        values.put(CUR_DATE_FIELD, curDate);
        values.put(PGM_NAME_FIELD, pgmName);
        values.put(TITLE02_FIELD, title02);
        values.put(CUR_TIME_FIELD, curTime);
        values.put(USR_ID_IN_FIELD, usrIdIn);
        values.put(FNAME_FIELD, fName);
        values.put(LNAME_FIELD, lName);
        values.put(PASSWD_FIELD, passwd);
        values.put(USR_TYPE_FIELD, usrType);
        values.put(ERR_MSG_FIELD, errMsg);
        return Collections.unmodifiableMap(values);
    }

    /**
     * One map-derived value, looked up by the {@code xxxO} name the copybook spells.
     *
     * @param cobolName the {@code xxxO} field name, one of {@link #MAP_FIELD_NAMES}
     * @return that field's value, exactly as held
     * @throws NullPointerException if {@code cobolName} is {@code null}
     * @throws IllegalArgumentException if {@code cobolName} is not one of the {@value #MAP_FIELD_COUNT}
     *     field names, with the names that are valid listed
     */
    public String value(String cobolName) {
        Objects.requireNonNull(cobolName, "A field name is required to look up a value of "
                + GROUP_NAME + "; the " + MAP_FIELD_COUNT + " valid names are " + MAP_FIELD_NAMES);
        String found = fieldValues().get(cobolName);
        if (found == null) {
            throw new IllegalArgumentException("'" + cobolName + "' is not a field of " + GROUP_NAME
                    + ". The " + MAP_FIELD_COUNT + " fields of this map, in screen order, are "
                    + MAP_FIELD_NAMES + ". Note that this map spells its user id field "
                    + USR_ID_IN_FIELD + ", which COUSR01 spells USERIDO; the two are deliberately "
                    + "not harmonised. The attribute items - the xxxC colour byte, xxxP, xxxH, xxxV, "
                    + "and the xxxL length and xxxF flag of the input view - are presentation "
                    + "metadata and are not fields of this payload");
        }
        return found;
    }

    /**
     * A single-line description that never includes the password.
     *
     * @return a description of this response with the password masked, never {@code null}
     */
    @Override
    public String toString() {
        return GROUP_NAME + "["
                + TRN_NAME_FIELD + "='" + trnName + "', "
                + TITLE01_FIELD + "='" + title01 + "', "
                + CUR_DATE_FIELD + "='" + curDate + "', "
                + PGM_NAME_FIELD + "='" + pgmName + "', "
                + TITLE02_FIELD + "='" + title02 + "', "
                + CUR_TIME_FIELD + "='" + curTime + "', "
                + USR_ID_IN_FIELD + "='" + usrIdIn + "', "
                + FNAME_FIELD + "='" + fName + "', "
                + LNAME_FIELD + "='" + lName + "', "
                + PASSWD_FIELD + "=" + PASSWORD_PLACEHOLDER + ", "
                + USR_TYPE_FIELD + "='" + usrType + "', "
                + ERR_MSG_FIELD + "='" + errMsg + "', "
                + NEXT_PROGRAM_FIELD + "='" + nextProgram + "', "
                + NEXT_MAPSET_FIELD + "='" + nextMapset + "', "
                + NEXT_MAP_FIELD + "='" + nextMap + "'"
                + "]";
    }

    private static final String REJECTED_VALUE_REDACTED = "[REDACTED]";

    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " of " + GROUP_NAME + " requires a "
                + "value; there is no null in a COBOL record, so move SPACES explicitly or start "
                + "from UserUpdateResponse.blank()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " of " + GROUP_NAME
                    + " is declared PIC X(" + declaredWidth + ") but was given " + value.length()
                    + " character(s) (value " + REJECTED_VALUE_REDACTED + "). This payload never "
                    + "truncates, so that the loss of a character is always a deliberate act rather "
                    + "than a silent one. To shorten the value, pass it through "
                    + "FixedWidthCodec.movePicX(value, " + declaredWidth + "), which truncates on "
                    + "the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    private static String spaces(int width) {
        return SPACE.repeat(width);
    }
}
