package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.user.dto.UserDeleteRequest.Cu03Info;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code DELETE /api/users/&#123;userId&#125;} - the Java projection of the CICS
 * screen that {@code app/cbl/COUSR03C.cbl} sends under transaction {@code CU03}.
 *
 * <p>{@code app/bms/COUSR03.bms} - 26 {@code DFHMDF} definitions of which exactly eleven carry a name
 * label.
 *
 * @param trnName the transaction identifier shown in the {@code Tran:} field - {@code TRNNAMEO},
 *     {@code PIC X(4)}, populated with {@link #TRANSACTION_ID} from {@code COUSR03C:249}
 * @param title01 the first title line - {@code TITLE01O}, {@code PIC X(40)}, populated from
 *     {@code common.ScreenTitles} by way of {@code CCDA-TITLE01} at {@code COUSR03C:247}
 * @param curDate the current date as {@code MM/DD/YY} - {@code CURDATEO}, {@code PIC X(8)}, assembled by
 *     {@code common.DateHeader} as {@code COUSR03C:252-256} does
 * @param pgmName the program name shown in the {@code Prog:} field - {@code PGMNAMEO}, {@code PIC X(8)},
 *     populated with {@link #PROGRAM_NAME} from {@code COUSR03C:250}
 * @param title02 the second title line - {@code TITLE02O}, {@code PIC X(40)}, populated from
 *     {@code common.ScreenTitles} by way of {@code CCDA-TITLE02} at {@code COUSR03C:248}
 * @param curTime the current time as {@code hh:mm:ss} - {@code CURTIMEO}, {@code PIC X(8)}, eight
 *     characters and not nine, assembled as {@code COUSR03C:258-262} does
 * @param usrIdIn the user id the screen is acting on - {@code USRIDINO}, {@code PIC X(8)}, named for
 *     {@code USRIDIN} and never for {@code USERID}
 * @param fName the first name read back from the security file - {@code FNAMEO}, {@code PIC X(20)}, from
 *     {@code SEC-USR-FNAME} via {@code COUSR03C:165}
 * @param lName the last name read back from the security file - {@code LNAMEO}, {@code PIC X(20)}, from
 *     {@code SEC-USR-LNAME} via {@code COUSR03C:166}
 * @param usrType the user type, {@code 'A'} for admin or {@code 'U'} for user - {@code USRTYPEO},
 *     {@code PIC X(1)}, from {@code SEC-USR-TYPE} via {@code COUSR03C:167}
 * @param errMsg the message line - {@code ERRMSGO}, {@code PIC X(78)}, the receiver of
 *     {@code MOVE WS-MESSAGE TO ERRMSGO} at {@code COUSR03C:217}
 * @param navigationContext the {@code CARDDEMO-COMMAREA} carried back to the client
 * @param nextProgram the {@code XCTL} target, projected as response data so the client navigates
 * @param nextMapset the mapset the client should request next, {@code X(7)} wide
 * @param nextMap the map the client should request next, {@code X(7)} wide
 * @param cu03Info the 34-byte {@code 05 CDEMO-CU03-INFO} extension of {@code app/cbl/COUSR03C.cbl:50-58},
 *     handed back behind the communication area exactly as line 94 restored it
 */
public record UserDeleteResponse(@JsonProperty("trnname") String trnName,
                                 String title01,
                                 @JsonProperty("curdate") String curDate,
                                 @JsonProperty("pgmname") String pgmName,
                                 String title02,
                                 @JsonProperty("curtime") String curTime,
                                 @JsonProperty("usridin") String usrIdIn,
                                 @JsonProperty("fname") String fName,
                                 @JsonProperty("lname") String lName,
                                 @JsonProperty("usrtype") String usrType,
                                 @JsonProperty("errmsg") String errMsg,
                                 NavigationContext navigationContext,
                                 String nextProgram,
                                 String nextMapset,
                                 String nextMap,
                                 Cu03Info cu03Info) {
    /**
     * The CICS transaction identifier this screen runs under, {@code 'CU03'}.
     */
    public static final String TRANSACTION_ID = "CU03";

    /**
     * The COBOL program this response projects, {@code 'COUSR03C'}.
     */
    public static final String PROGRAM_NAME = "COUSR03C";

    public static final String MAP_NAME = "COUSR3A";

    /**
     * The BMS mapset name, {@code 'COUSR03'}.
     */
    public static final String MAPSET_NAME = "COUSR03";

    /**
     * The number of map-derived members this payload carries: eleven.
     */
    public static final int MAP_FIELD_COUNT = 11;

    /**
     * The COBOL name of {@link #trnName()}: {@code TRNNAMEO}, from {@code COUSR03.CPY:92}.
     */
    public static final String TRN_NAME_FIELD = "TRNNAMEO";

    /**
     * The COBOL name of {@link #title01()}: {@code TITLE01O}, from {@code COUSR03.CPY:98}.
     */
    public static final String TITLE01_FIELD = "TITLE01O";

    /**
     * The COBOL name of {@link #curDate()}: {@code CURDATEO}, from {@code COUSR03.CPY:104}.
     */
    public static final String CUR_DATE_FIELD = "CURDATEO";

    /**
     * The COBOL name of {@link #pgmName()}: {@code PGMNAMEO}, from {@code COUSR03.CPY:110}.
     */
    public static final String PGM_NAME_FIELD = "PGMNAMEO";

    /**
     * The COBOL name of {@link #title02()}: {@code TITLE02O}, from {@code COUSR03.CPY:116}.
     */
    public static final String TITLE02_FIELD = "TITLE02O";

    /**
     * The COBOL name of {@link #curTime()}: {@code CURTIMEO}, from {@code COUSR03.CPY:122}.
     */
    public static final String CUR_TIME_FIELD = "CURTIMEO";

    /**
     * The COBOL name of {@link #usrIdIn()}: {@code USRIDINO}, from {@code COUSR03.CPY:128}.
     */
    public static final String USR_ID_IN_FIELD = "USRIDINO";

    /**
     * The COBOL name of {@link #fName()}: {@code FNAMEO}, from {@code COUSR03.CPY:134}.
     */
    public static final String F_NAME_FIELD = "FNAMEO";

    /**
     * The COBOL name of {@link #lName()}: {@code LNAMEO}, from {@code COUSR03.CPY:140}.
     */
    public static final String L_NAME_FIELD = "LNAMEO";

    /**
     * The COBOL name of {@link #usrType()}: {@code USRTYPEO}, from {@code COUSR03.CPY:146}.
     */
    public static final String USR_TYPE_FIELD = "USRTYPEO";

    /**
     * The COBOL name of {@link #errMsg()}: {@code ERRMSGO}, from {@code COUSR03.CPY:152}.
     */
    public static final String ERR_MSG_FIELD = "ERRMSGO";

    /**
     * {@code TRNNAMEO PIC X(4)} - four characters.
     */
    public static final int TRN_NAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)} - forty characters.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)} - eight characters, the {@code mm/dd/yy} shape the mapset seeds as its
     * {@code INITIAL} value.
     */
    public static final int CUR_DATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)} - eight characters, exactly the width of {@link #PROGRAM_NAME}.
     */
    public static final int PGM_NAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)} - forty characters.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)} - eight characters, the {@code hh:mm:ss} shape the mapset seeds as its
     * {@code INITIAL} value.
     */
    public static final int CUR_TIME_LENGTH = 8;

    /**
     * {@code USRIDINO PIC X(8)} - eight characters, the width of {@code SEC-USR-ID} in
     * {@code app/cpy/CSUSR01Y.cpy}.
     */
    public static final int USR_ID_IN_LENGTH = 8;

    /**
     * {@code FNAMEO PIC X(20)} - twenty characters, the width of {@code SEC-USR-FNAME}.
     */
    public static final int F_NAME_LENGTH = 20;

    /**
     * {@code LNAMEO PIC X(20)} - twenty characters, the width of {@code SEC-USR-LNAME}.
     */
    public static final int L_NAME_LENGTH = 20;

    /**
     * {@code USRTYPEO PIC X(1)} - a single character, the width of {@code SEC-USR-TYPE}.
     */
    public static final int USR_TYPE_LENGTH = 1;

    /**
     * {@code ERRMSGO PIC X(78)} - seventy-eight characters.
     */
    public static final int ERR_MSG_LENGTH = 78;

    /**
     * The width of the COBOL work field that feeds {@link #errMsg()}: {@code 80}.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The width of {@link #nextProgram()}: {@value}, from {@code CDEMO-TO-PROGRAM PIC X(08)} in
     * {@code app/cpy/COCOM01Y.cpy:24}.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * The width of {@link #nextMapset()}: {@value}, from {@code CDEMO-LAST-MAPSET PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:44}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * The width of {@link #nextMap()}: {@value}, from {@code CDEMO-LAST-MAP PIC X(7)} in
     * {@code app/cpy/COCOM01Y.cpy:43}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * The COBOL field whose width governs {@link #nextProgram()}: {@code CDEMO-TO-PROGRAM}.
     */
    public static final String NEXT_PROGRAM_FIELD = NavigationContext.TO_PROGRAM_FIELD;

    /**
     * The COBOL field whose width governs {@link #nextMapset()}: {@code CDEMO-LAST-MAPSET}.
     */
    public static final String NEXT_MAPSET_FIELD = NavigationContext.LAST_MAPSET_FIELD;

    /**
     * The COBOL field whose width governs {@link #nextMap()}: {@code CDEMO-LAST-MAP}.
     */
    public static final String NEXT_MAP_FIELD = NavigationContext.LAST_MAP_FIELD;

    private static final String SPACE = " ";

    /**
     * Validates every member against its declared width and rejects {@code null} throughout.
     *
     * <p>A value shorter than its declared width is accepted unchanged, exactly as a COBOL {@code MOVE}
     * into a wider {@code PIC X} receiver is accepted and padded on the right when the image is rendered.
     */
    public UserDeleteResponse {
        trnName = requireWidth(trnName, TRN_NAME_LENGTH, TRN_NAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CUR_DATE_LENGTH, CUR_DATE_FIELD);
        pgmName = requireWidth(pgmName, PGM_NAME_LENGTH, PGM_NAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CUR_TIME_LENGTH, CUR_TIME_FIELD);
        usrIdIn = requireWidth(usrIdIn, USR_ID_IN_LENGTH, USR_ID_IN_FIELD);
        fName = requireWidth(fName, F_NAME_LENGTH, F_NAME_FIELD);
        lName = requireWidth(lName, L_NAME_LENGTH, L_NAME_FIELD);
        usrType = requireWidth(usrType, USR_TYPE_LENGTH, USR_TYPE_FIELD);
        errMsg = requireWidth(errMsg, ERR_MSG_LENGTH, ERR_MSG_FIELD);
        Objects.requireNonNull(navigationContext,
                "navigationContext carries CARDDEMO-COMMAREA and is never absent; the communication "
                        + "area travels in the payload because no server-side session holds it "
                        + "(gate G37). Use NavigationContext.empty() for a cold start");
        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH, NEXT_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH, NEXT_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NEXT_MAP_FIELD);
        cu03Info = cu03Info == null ? Cu03Info.initial() : cu03Info;
    }

    /**
     * A freshly initialised screen: every screen field carrying the unpainted image at its declared width,
     * and a freshly initialised communication area.
     *
     * <p>In the COBOL those moves happen in {@code POPULATE-HEADER-INFO} on the way out, not at
     * initialisation, and the ordering is preserved here so a caller populates the header explicitly and
     * visibly.
     *
     * @return an empty screen payload, never {@code null}
     */
    public static UserDeleteResponse empty() {
        return new UserDeleteResponse(ScreenFieldImage.unpainted(TRN_NAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE01_LENGTH),
                ScreenFieldImage.unpainted(CUR_DATE_LENGTH),
                ScreenFieldImage.unpainted(PGM_NAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE02_LENGTH),
                ScreenFieldImage.unpainted(CUR_TIME_LENGTH),
                ScreenFieldImage.unpainted(USR_ID_IN_LENGTH),
                ScreenFieldImage.unpainted(F_NAME_LENGTH),
                ScreenFieldImage.unpainted(L_NAME_LENGTH),
                ScreenFieldImage.unpainted(USR_TYPE_LENGTH),
                ScreenFieldImage.unpainted(ERR_MSG_LENGTH),
                NavigationContext.empty(),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                Cu03Info.initial());
    }

    /**
     * This response with {@code TRNNAMEO} replaced - the equivalent of
     * {@code MOVE WS-TRANID TO TRNNAMEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:249}.
     *
     * @param newTrnName the transaction name, at most {@value #TRN_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withTrnName(String newTrnName) {
        return new UserDeleteResponse(newTrnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code TITLE01O} replaced - the equivalent of
     * {@code MOVE CCDA-TITLE01 TO TITLE01O OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:247}.
     *
     * @param newTitle01 the first title line, at most {@value #TITLE01_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withTitle01(String newTitle01) {
        return new UserDeleteResponse(trnName, newTitle01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code CURDATEO} replaced - the equivalent of
     * {@code MOVE WS-CURDATE-MM-DD-YY TO CURDATEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:256}.
     *
     * @param newCurDate the date as {@code MM/DD/YY}, at most {@value #CUR_DATE_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withCurDate(String newCurDate) {
        return new UserDeleteResponse(trnName, title01, newCurDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code PGMNAMEO} replaced - the equivalent of
     * {@code MOVE WS-PGMNAME TO PGMNAMEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:250}.
     *
     * @param newPgmName the program name, at most {@value #PGM_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withPgmName(String newPgmName) {
        return new UserDeleteResponse(trnName, title01, curDate, newPgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code TITLE02O} replaced - the equivalent of
     * {@code MOVE CCDA-TITLE02 TO TITLE02O OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:248}.
     *
     * @param newTitle02 the second title line, at most {@value #TITLE02_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withTitle02(String newTitle02) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, newTitle02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code CURTIMEO} replaced - the equivalent of
     * {@code MOVE WS-CURTIME-HH-MM-SS TO CURTIMEO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:262}.
     *
     * @param newCurTime the time as {@code hh:mm:ss}, at most {@value #CUR_TIME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withCurTime(String newCurTime) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, newCurTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                nextMap, cu03Info);
    }

    /**
     * This response with {@code USRIDINO} replaced - the equivalent of the moves into
     * {@code USRIDINI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:102} and 352, which land in the same
     * bytes because {@code COUSR3AO REDEFINES COUSR3AI} at the same stride.
     *
     * @param newUsrIdIn the user id, at most {@value #USR_ID_IN_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withUsrIdIn(String newUsrIdIn) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                newUsrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code FNAMEO} replaced - the equivalent of
     * {@code MOVE SEC-USR-FNAME TO FNAMEI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:165}.
     *
     * @param newFName the first name, at most {@value #F_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withFName(String newFName) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, newFName, lName, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code LNAMEO} replaced - the equivalent of
     * {@code MOVE SEC-USR-LNAME TO LNAMEI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:166}.
     *
     * @param newLName the last name, at most {@value #L_NAME_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withLName(String newLName) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, newLName, usrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code USRTYPEO} replaced - the equivalent of
     * {@code MOVE SEC-USR-TYPE TO USRTYPEI OF COUSR3AI} at {@code app/cbl/COUSR03C.cbl:167}.
     *
     * @param newUsrType the user type, at most {@value #USR_TYPE_LENGTH} character
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withUsrType(String newUsrType) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, newUsrType, errMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with {@code ERRMSGO} replaced - the equivalent of
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COUSR3AO} at {@code app/cbl/COUSR03C.cbl:217}.
     *
     * <p>The COBOL {@code MOVE} narrows {@value #WS_MESSAGE_LENGTH} bytes to {@value #ERR_MSG_LENGTH} by
     * truncating on the right, and that narrowing is performed by {@code common.FixedWidthCodec.movePicX}
     * in the controller, never here.
     *
     * @param newErrMsg the message line, at most {@value #ERR_MSG_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withErrMsg(String newErrMsg) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, newErrMsg, navigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different communication area - the equivalent of updating
     * {@code CARDDEMO-COMMAREA} before {@code EXEC CICS RETURN ... COMMAREA(CARDDEMO-COMMAREA)} at
     * {@code app/cbl/COUSR03C.cbl:134-137}.
     *
     * @param newNavigationContext the communication area, never {@code null}
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNavigationContext(NavigationContext newNavigationContext) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, newNavigationContext, nextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different transfer target - the equivalent of the program named by
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} at {@code app/cbl/COUSR03C.cbl:206}.
     *
     * @param newNextProgram the next program, at most {@link #NEXT_PROGRAM_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNextProgram(String newNextProgram) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, newNextProgram,
                nextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different next mapset - {@link #MAPSET_NAME} while the conversation stays on
     * this screen.
     *
     * @param newNextMapset the next mapset, at most {@link #NEXT_MAPSET_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNextMapset(String newNextMapset) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram,
                newNextMapset, nextMap, cu03Info);
    }

    /**
     * This response with a different next map - {@link #MAP_NAME} while the conversation stays on this
     * screen.
     *
     * @param newNextMap the next map, at most {@link #NEXT_MAP_LENGTH} characters
     * @return a new instance; this one is unchanged
     */
    public UserDeleteResponse withNextMap(String newNextMap) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime,
                usrIdIn, fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset,
                newNextMap, cu03Info);
    }

    /**
     * A copy carrying a different {@code 05 CDEMO-CU03-INFO} extension - the thirty-four bytes
     * {@code app/cbl/COUSR03C.cbl:50-58} appends to the communication area and lines 205-208 hand back on
     * the {@code XCTL} along with it.
     *
     * <p>Carried separately from {@link #navigationContext()} because {@code app/cpy/COCOM01Y.cpy} is
     * exactly {@value NavigationContext#COMMAREA_LENGTH} bytes and is shared by all seventeen controllers,
     * while this group belongs to this program alone.
     *
     * @param newCu03Info the extension; {@code null} is replaced by {@link Cu03Info#initial()}
     * @return a new response
     */
    public UserDeleteResponse withCu03Info(Cu03Info newCu03Info) {
        return new UserDeleteResponse(trnName, title01, curDate, pgmName, title02, curTime, usrIdIn,
                fName, lName, usrType, errMsg, navigationContext, nextProgram, nextMapset, nextMap,
                newCu03Info);
    }

    private static String spaces(int width) {
        return SPACE.repeat(width);
    }

    private static String requireWidth(String value, int declaredWidth, String cobolName) {
        Objects.requireNonNull(value, "Field " + cobolName + " requires a value; there is no null in "
                + "a COBOL screen field, so move SPACES explicitly or start from "
                + "UserDeleteResponse.empty()");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException("Field " + cobolName + " is declared PIC X("
                    + declaredWidth + ") but was given " + value.length() + " character(s): '" + value
                    + "'. The screen field cannot hold the surplus. To shorten the value "
                    + "deliberately, pass it through FixedWidthCodec.movePicX(value, "
                    + declaredWidth + "), which truncates on the right as a COBOL alphanumeric MOVE "
                    + "does");
        }
        return value;
    }

    /**
     * The eleven {@code xxxO} items of {@code COUSR3AO}, keyed by the names {@code app/cpy-bms/COUSR03.CPY}
     * spells, in screen order.
     *
     * <p>Values are returned exactly as they are held, not padded to the widths the copybook declares.
     *
     * @return an unmodifiable, screen-ordered map of all eleven field names to their values; never
     *     {@code null}
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
        values.put(F_NAME_FIELD, fName);
        values.put(L_NAME_FIELD, lName);
        values.put(USR_TYPE_FIELD, usrType);
        values.put(ERR_MSG_FIELD, errMsg);
        return Collections.unmodifiableMap(values);
    }

    /**
     * A diagnostic rendering that withholds the personal name, per {@link SensitiveDiagnostics}.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "UserDeleteResponse[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", usrIdIn=" + usrIdIn
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ']';
    }

}
