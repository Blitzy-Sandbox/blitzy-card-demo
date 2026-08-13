package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenMetadata;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import java.util.List;
import java.util.Objects;

/**
 * The outbound payload of {@code POST /api/signon} - CICS transaction {@code CC00}, program
 * {@code app/cbl/COSGN00C.cbl}, mapset {@code app/bms/COSGN00.bms}, map {@code COSGN0A}.
 *
 * <p>The {@code LENGTH=} operand of every {@code DFHMDF} agrees with its {@code xxxO} {@code PICTURE}
 * exactly.
 *
 * @param trnName {@code TRNNAMEO PIC X(4)}: the transaction identifier shown as {@code Tran :},
 *     {@link #TRANID} here
 * @param title01 {@code TITLE01O PIC X(40)}: the first title line, from {@code common.ScreenTitles}
 * @param curDate {@code CURDATEO PIC X(8)}: the current date as {@code MM/DD/YY}, from
 *     {@code common.DateHeader}
 * @param pgmName {@code PGMNAMEO PIC X(8)}: the program name shown as {@code Prog :}, {@link #PROGRAM_NAME}
 *     here
 * @param title02 {@code TITLE02O PIC X(40)}: the second title line, from {@code common.ScreenTitles}
 * @param curTime {@code CURTIMEO PIC X(9)}: the current time as {@code HH:MM:SS} in a nine-character field,
 *     one wider than the other user screens
 * @param applId {@code APPLIDO PIC X(8)}: the CICS region's application identifier, from
 *     {@code EXEC CICS ASSIGN} and therefore supplied by configuration here
 * @param sysId {@code SYSIDO PIC X(8)}: the CICS system identifier, likewise from {@code EXEC CICS ASSIGN}
 * @param userId {@code USERIDO PIC X(8)}: the user-id field of the screen
 * @param passwd {@code PASSWDO PIC X(8)}: the password field of the screen, dark and modified-data-tagged
 * @param errMsg {@code ERRMSGO PIC X(78)}: the error line at row 23, red and bright
 * @param role {@code CDEMO-USER-TYPE PIC X(01)}: {@link #ROLE_ADMIN} for an administrator,
 *     {@link #ROLE_USER} for a regular user, a space before sign-on, and possibly none of those
 * @param nextProgram the {@code XCTL} target the client should call next - {@link #NEXT_PROGRAM_ADMIN} or
 *     {@link #NEXT_PROGRAM_USER}
 * @param nextMapset the mapset the client should render next, {@code PIC X(7)}
 * @param nextMap the map the client should render next, {@code PIC X(7)}
 * @param plainText the {@value #PLAIN_TEXT_LENGTH} bytes {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE)}
 *     transmitted on the PF3 path, and {@value #PLAIN_TEXT_LENGTH} spaces on every path that sent no text
 * @param navigationContext the {@code CARDDEMO-COMMAREA} carried between calls, never {@code null}
 */
public record SignOnResponse(@JsonProperty("trnname") String trnName,
                             String title01,
                             @JsonProperty("curdate") String curDate,
                             @JsonProperty("pgmname") String pgmName,
                             String title02,
                             @JsonProperty("curtime") String curTime,
                             @JsonProperty("applid") String applId,
                             @JsonProperty("sysid") String sysId,
                             @JsonProperty("userid") String userId,
                             @JsonIgnore String passwd,
                             @JsonProperty("errmsg") String errMsg,
                             String role,
                             String nextProgram,
                             String nextMapset,
                             String nextMap,
                             String plainText,
                             NavigationContext navigationContext) {
    public static final String TRNNAME_FIELD = "TRNNAMEO";

    public static final String TITLE01_FIELD = "TITLE01O";

    public static final String CURDATE_FIELD = "CURDATEO";

    public static final String PGMNAME_FIELD = "PGMNAMEO";

    public static final String TITLE02_FIELD = "TITLE02O";

    public static final String CURTIME_FIELD = "CURTIMEO";

    public static final String APPLID_FIELD = "APPLIDO";

    public static final String SYSID_FIELD = "SYSIDO";

    public static final String USERID_FIELD = "USERIDO";

    public static final String PASSWD_FIELD = "PASSWDO";

    public static final String ERRMSG_FIELD = "ERRMSGO";

    /**
     * {@code TRNNAMEO PIC X(4)}, and {@code TRNNAME ... LENGTH=4} at bms line 36.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)}, and {@code TITLE01 ... LENGTH=40} at bms line 40.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, and {@code CURDATE ... LENGTH=8} at bms line 49.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}, and {@code PGMNAME ... LENGTH=8} at bms line 59.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)}, and {@code TITLE02 ... LENGTH=40} at bms line 63.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(9)}, and {@code CURTIME ... LENGTH=9} at bms line 72 with
     * {@code INITIAL='Ahh:mm:ss'}.
     */
    public static final int CURTIME_LENGTH = 9;

    /**
     * {@code APPLIDO PIC X(8)}, and {@code APPLID ... LENGTH=8} at bms line 82.
     */
    public static final int APPLID_LENGTH = 8;

    /**
     * {@code SYSIDO PIC X(8)}, and {@code SYSID ... LENGTH=8} at bms line 91.
     */
    public static final int SYSID_LENGTH = 8;

    /**
     * {@code USERIDO PIC X(8)}, and {@code USERID ... LENGTH=8} at bms line 159.
     */
    public static final int USERID_LENGTH = 8;

    /**
     * {@code PASSWDO PIC X(8)}, and {@code PASSWD ... LENGTH=8} at bms line 178.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code ERRMSGO PIC X(78)}, and {@code ERRMSG ... LENGTH=78} at bms line 199.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Width of {@link #role()}: {@code CDEMO-USER-TYPE PIC X(01)}, COCOM01Y line 26.
     */
    public static final int ROLE_LENGTH = NavigationContext.USER_TYPE_LENGTH;

    /**
     * Width of {@link #nextProgram()}: {@code CDEMO-TO-PROGRAM PIC X(08)}, COCOM01Y line 24.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@link #nextMapset()}: {@code CDEMO-LAST-MAPSET PIC X(7)}, COCOM01Y line 44.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Width of {@link #nextMap()}: {@code CDEMO-LAST-MAP PIC X(7)}, COCOM01Y line 43.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * Width of {@link #plainText()}: {@code WS-MESSAGE PIC X(80)} at {@code app/cbl/COSGN00C.cbl:38}, which
     * is what {@code SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE)} at L164-169 transmits.
     */
    public static final int PLAIN_TEXT_LENGTH = 80;

    public static final String PLAIN_TEXT_MEMBER = "WS-MESSAGE";

    private static final String PASSWD_REDACTED = SensitiveDiagnostics.REDACTED;

    // Publishing all three counts is what makes the projection machine-checkable: the mapset's named-field
    // count, the symbolic map's item count and this payload's member count are one number, and an assertion
    // holds them equal so a dropped field cannot pass unnoticed.

    /**
     * Total {@code DFHMDF} definitions in {@code app/bms/COSGN00.bms}, named and unnamed.
     */
    public static final int MAPSET_FIELD_COUNT = 37;

    /**
     * Name-labelled {@code DFHMDF} definitions in {@code app/bms/COSGN00.bms}, which is also the number of
     * {@code xxxI} items and of {@code xxxO} items in {@code app/cpy-bms/COSGN00.CPY}.
     */
    public static final int MAPSET_NAMED_FIELD_COUNT = 11;

    /**
     * Map-derived members of this payload - every named screen field, so this equals
     * {@value #MAPSET_NAMED_FIELD_COUNT}.
     */
    public static final int MAP_FIELD_COUNT = MAPSET_NAMED_FIELD_COUNT;

    /**
     * All {@value #MAPSET_NAMED_FIELD_COUNT} name-labelled {@code DFHMDF} fields of
     * {@code app/bms/COSGN00.bms}, in mapset declaration order.
     */
    public static final List<String> MAPSET_NAMED_FIELDS = List.of(
            "TRNNAME", "TITLE01", "CURDATE", "PGMNAME", "TITLE02", "CURTIME",
            "APPLID", "SYSID", "USERID", "PASSWD", "ERRMSG");

    public static final List<String> MAP_FIELDS = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, APPLID_FIELD, SYSID_FIELD, USERID_FIELD, PASSWD_FIELD, ERRMSG_FIELD);

    /**
     * The one projected screen field that is <strong>not</strong> serialized into the HTTP payload:
     * {@value #PASSWD_FIELD}, the span that carries the submitted credential.
     *
     * <p>The distinction this constant records is between a <em>3270 datastream</em> and an
     * <em>HTTP response body</em>, and it is the one place in this migration where those two are not
     * the same thing.
     *
     * <p>On the terminal the span is legitimately re-transmitted. {@code COSGN00C} contains no
     * {@code MOVE} into {@code PASSWDO} at all - the only references to the field anywhere in
     * {@code app/cbl/COSGN00C.cbl} are {@code PASSWDI} at {@code :123} and {@code :135} and
     * {@code PASSWDL} at {@code :126} and {@code :244} - but {@code COSGN0AO REDEFINES COSGN0AI} at
     * {@code app/cpy-bms/COSGN00.CPY:85} puts the received bytes inside the area
     * {@code EXEC CICS SEND MAP ... FROM(COSGN0AO)} transmits at {@code :151-157}, and the field is
     * declared {@code ATTRB=(DRK,FSET,UNPROT)} at {@code app/bms/COSGN00.bms:175-179}, so the terminal
     * never renders it. The screen image therefore contains the span, and {@link #passwd()} reports it,
     * and a parity fingerprint of the map area asserts it.
     *
     * <p>An HTTP response body has no {@code DRK} attribute and no terminal to withhold it. Returning
     * the submitted credential to the caller in JSON is an exposure of sensitive information that the
     * source cannot be said to have specified, because the source has no JSON: it is an artefact of
     * projecting a 3270 buffer onto a wire format whose properties differ. Nothing observable about
     * {@code COSGN00C} depends on the value crossing that boundary - a caller that submitted the
     * password already has it, the repaint's cursor and error message are separate members, and the
     * {@code XCTL} path at {@code :231-239} paints no map at all - so the field is bound
     * {@code WRITE_ONLY}: still accepted on input, never written on output.
     *
     * <p>Consequence a caller must know: this payload is not round-trip symmetric. Serializing a
     * response and reading it back yields {@link ScreenFieldImage#unpainted(int)} in the withheld
     * member rather than the value that was serialized, because the member is absent from the
     * document. The canonical constructor still rejects an explicit {@code null} - the strict width
     * and null rules apply to all sixteen character components without exception - so a caller
     * reconstructing a response must supply the span itself.
     */
    public static final String WIRE_WITHHELD_FIELD = PASSWD_FIELD;

    /**
     * Map-derived members the serialized payload actually carries: {@value #MAP_FIELD_COUNT} projected
     * screen fields less the one named by {@link #WIRE_WITHHELD_FIELD}.
     *
     * <p>Published as its own constant, rather than left as the difference between two numbers, so the
     * omission is something a test asserts and a reader can find. {@link #MAP_FIELD_COUNT} remains the
     * census of the <em>screen</em>, which is unchanged and still equals
     * {@value #MAPSET_NAMED_FIELD_COUNT}; this is the census of the <em>wire</em>, and the two
     * differing by exactly one is the whole of the difference between them.
     */
    public static final int WIRE_FIELD_COUNT = MAP_FIELD_COUNT - 1;

    /**
     * The {@value #WIRE_FIELD_COUNT} symbolic-map items the serialized payload carries, in component
     * declaration order - {@link #MAP_FIELDS} with {@link #WIRE_WITHHELD_FIELD} removed. Immutable.
     */
    public static final List<String> WIRE_FIELDS = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, APPLID_FIELD, SYSID_FIELD, USERID_FIELD, ERRMSG_FIELD);

    // =================================================================================================
    // Literals the program itself declares. Transcribed once here so the controller and the service
    // cannot drift apart on them, and so a change to any of them is visible in exactly one place.
    // =================================================================================================

    /** {@code WS-TRANID PIC X(04) VALUE 'CC00'}, {@code app/cbl/COSGN00C.cbl:37}. */
    public static final String TRANID = "CC00";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COSGN00C'}, {@code app/cbl/COSGN00C.cbl:36}.
     */
    public static final String PROGRAM_NAME = "COSGN00C";

    /**
     * The mapset of {@code EXEC CICS SEND MAP('COSGN0A') MAPSET('COSGN00')},
     * {@code app/cbl/COSGN00C.cbl:153}.
     */
    public static final String MAPSET_NAME = "COSGN00";

    /**
     * The map of the same {@code SEND}, {@code app/cbl/COSGN00C.cbl:152}.
     */
    public static final String MAP_NAME = "COSGN0A";

    /**
     * {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'}, {@code app/cpy/COCOM01Y.cpy:27}.
     */
    public static final String ROLE_ADMIN = NavigationContext.USER_TYPE_ADMIN;

    /**
     * {@code 88 CDEMO-USRTYP-USER VALUE 'U'}, {@code app/cpy/COCOM01Y.cpy:28}.
     */
    public static final String ROLE_USER = NavigationContext.USER_TYPE_USER;

    /**
     * {@code EXEC CICS XCTL PROGRAM ('COADM01C')}, {@code app/cbl/COSGN00C.cbl:232} - the target when
     * {@code 88 CDEMO-USRTYP-ADMIN} holds.
     */
    public static final String NEXT_PROGRAM_ADMIN = "COADM01C";

    /**
     * {@code EXEC CICS XCTL PROGRAM ('COMEN01C')}, {@code app/cbl/COSGN00C.cbl:237} - the target of the
     * {@code ELSE}, and therefore the target for every user type that is not {@link #ROLE_ADMIN}.
     */
    public static final String NEXT_PROGRAM_USER = "COMEN01C";

    /**
     * Validates every component against the width its {@code PICTURE} clause declares.
     */
    public SignOnResponse {
        trnName = requireWidth(trnName, TRNNAME_LENGTH, TRNNAME_FIELD);
        title01 = requireWidth(title01, TITLE01_LENGTH, TITLE01_FIELD);
        curDate = requireWidth(curDate, CURDATE_LENGTH, CURDATE_FIELD);
        pgmName = requireWidth(pgmName, PGMNAME_LENGTH, PGMNAME_FIELD);
        title02 = requireWidth(title02, TITLE02_LENGTH, TITLE02_FIELD);
        curTime = requireWidth(curTime, CURTIME_LENGTH, CURTIME_FIELD);
        applId = requireWidth(applId, APPLID_LENGTH, APPLID_FIELD);
        sysId = requireWidth(sysId, SYSID_LENGTH, SYSID_FIELD);
        userId = requireWidth(userId, USERID_LENGTH, USERID_FIELD);
        // The one component null is legal for: it is not published, so a body read back from JSON
        // arrives without it. Eight spaces is the COBOL SPACES image at the declared width, which is
        // what an unset PIC X(8) holds.
        passwd = passwd == null
                ? spaces(PASSWD_LENGTH)
                : requireWidth(passwd, PASSWD_LENGTH, PASSWD_FIELD);
        errMsg = requireWidth(errMsg, ERRMSG_LENGTH, ERRMSG_FIELD);
        role = requireWidth(role, ROLE_LENGTH, NavigationContext.USER_TYPE_FIELD);
        nextProgram = requireWidth(nextProgram, NEXT_PROGRAM_LENGTH, NavigationContext.TO_PROGRAM_FIELD);
        nextMapset = requireWidth(nextMapset, NEXT_MAPSET_LENGTH, NavigationContext.LAST_MAPSET_FIELD);
        nextMap = requireWidth(nextMap, NEXT_MAP_LENGTH, NavigationContext.LAST_MAP_FIELD);
        plainText = requireWidth(plainText, PLAIN_TEXT_LENGTH, PLAIN_TEXT_MEMBER);
        navigationContext = Objects.requireNonNull(navigationContext, "navigationContext");
    }

    /**
     * The sign-on screen before anything has been written to it: every one of the {@link #MAP_FIELD_COUNT}
     * screen fields carrying the unpainted image at its own declared width, and
     * {@link NavigationContext#empty()} as the communication area.
     *
     * <p>The unpainted image is therefore {@code LOW-VALUES}: {@code X'00'} at the declared width, exactly
     * the byte line 81 moves.
     *
     * @return the initial sign-on response, never {@code null}
     */
    public static SignOnResponse empty() {
        return new SignOnResponse(ScreenFieldImage.unpainted(TRNNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE01_LENGTH),
                ScreenFieldImage.unpainted(CURDATE_LENGTH),
                ScreenFieldImage.unpainted(PGMNAME_LENGTH),
                ScreenFieldImage.unpainted(TITLE02_LENGTH),
                ScreenFieldImage.unpainted(CURTIME_LENGTH),
                ScreenFieldImage.unpainted(APPLID_LENGTH),
                ScreenFieldImage.unpainted(SYSID_LENGTH),
                ScreenFieldImage.unpainted(USERID_LENGTH),
                ScreenFieldImage.unpainted(PASSWD_LENGTH),
                ScreenFieldImage.unpainted(ERRMSG_LENGTH),
                spaces(ROLE_LENGTH),
                spaces(NEXT_PROGRAM_LENGTH),
                spaces(NEXT_MAPSET_LENGTH),
                spaces(NEXT_MAP_LENGTH),
                spaces(PLAIN_TEXT_LENGTH),
                NavigationContext.empty());
    }

    /**
     * Whether {@code 88 CDEMO-USRTYP-ADMIN VALUE 'A'} holds for the given user type.
     *
     * @param role the user type to test, typically {@link #role()} or {@link NavigationContext#userType()};
     *     may be {@code null}
     * @return {@code true} if and only if {@code role} is exactly {@link #ROLE_ADMIN}
     */
    public static boolean isAdminRole(String role) {
        return ROLE_ADMIN.equals(role);
    }

    /**
     * The {@code EXEC CICS XCTL} target for the given user type, transcribed from
     * {@code app/cbl/COSGN00C.cbl:230-240}.
     *
     * @param role the user type to route on, from {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at line 227;
     *     may be {@code null}
     * @return {@link #NEXT_PROGRAM_ADMIN} for {@link #ROLE_ADMIN}, otherwise {@link #NEXT_PROGRAM_USER};
     *     never {@code null}
     */
    public static String resolveNextProgram(String role) {
        return isAdminRole(role) ? NEXT_PROGRAM_ADMIN : NEXT_PROGRAM_USER;
    }

    // THE INVARIANT: one method per paragraph of app/cbl/COSGN00C.cbl that writes this screen, and none for
    // anything else - so a call site reads as the paragraph it stands for, and no convenience exists for
    // writing a field the program never writes.

    /**
     * The eight header fields, exactly as {@code POPULATE-HEADER-INFO} writes them -
     * {@code app/cbl/COSGN00C.cbl:177-204}.
     *
     * @param newTrnName the transaction identifier, at most {@value #TRNNAME_LENGTH} characters
     * @param newTitle01 the first title line, at most {@value #TITLE01_LENGTH} characters
     * @param newCurDate the date as {@code MM/DD/YY}, at most {@value #CURDATE_LENGTH} characters
     * @param newPgmName the program name, at most {@value #PGMNAME_LENGTH} characters
     * @param newTitle02 the second title line, at most {@value #TITLE02_LENGTH} characters
     * @param newCurTime the time as {@code HH:MM:SS}, at most {@value #CURTIME_LENGTH} characters
     * @param newApplId the CICS application identifier, at most {@value #APPLID_LENGTH} characters
     * @param newSysId the CICS system identifier, at most {@value #SYSID_LENGTH} characters
     * @return a new response carrying the header, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if any argument exceeds its declared width
     */
    public SignOnResponse withHeader(String newTrnName,
                                     String newTitle01,
                                     String newCurDate,
                                     String newPgmName,
                                     String newTitle02,
                                     String newCurTime,
                                     String newApplId,
                                     String newSysId) {
        return new SignOnResponse(newTrnName, newTitle01, newCurDate, newPgmName, newTitle02,
                newCurTime, newApplId, newSysId, userId, passwd, errMsg,
                role, nextProgram, nextMapset, nextMap, plainText, navigationContext);
    }

    /**
     * The error line, as {@code MOVE WS-MESSAGE TO ERRMSGO OF COSGN0AO} writes it -
     * {@code app/cbl/COSGN00C.cbl:149}.
     *
     * @param newErrMsg the message, at most {@value #ERRMSG_LENGTH} characters
     * @return a new response carrying the message, never {@code null}
     * @throws NullPointerException if {@code newErrMsg} is {@code null}; a cleared line is
     *     {@value #ERRMSG_LENGTH} spaces, which is what {@code MOVE SPACES TO ERRMSGO} at line 78 writes
     * @throws IllegalArgumentException if {@code newErrMsg} exceeds {@value #ERRMSG_LENGTH} characters
     */
    public SignOnResponse withErrMsg(String newErrMsg) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                userId, passwd, newErrMsg, role, nextProgram, nextMapset, nextMap, plainText,
                navigationContext);
    }

    /**
     * The navigation outcome of a successful sign-on - the stateless form of
     * {@code app/cbl/COSGN00C.cbl:224-240}.
     *
     * @param newRole the user type from {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE} at line 227, at most
     *     {@link #ROLE_LENGTH} character
     * @param newNextProgram the {@code XCTL} target, at most {@link #NEXT_PROGRAM_LENGTH} characters,
     *     normally {@link #resolveNextProgram(String)} of {@code newRole}
     * @param newNextMapset the mapset to render next, at most {@link #NEXT_MAPSET_LENGTH} characters
     * @param newNextMap the map to render next, at most {@link #NEXT_MAP_LENGTH} characters
     * @param newNavigationContext the communication area populated at lines 224-228, never {@code null}
     * @return a new response carrying the navigation outcome, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if any character argument exceeds its declared width
     */
    public SignOnResponse withNavigation(String newRole,
                                         String newNextProgram,
                                         String newNextMapset,
                                         String newNextMap,
                                         NavigationContext newNavigationContext) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                userId, passwd, errMsg, newRole, newNextProgram, newNextMapset, newNextMap, plainText,
                newNavigationContext);
    }

    /**
     * The two overlay spans, as {@code EXEC CICS RECEIVE MAP} at {@code app/cbl/COSGN00C.cbl:110-115} left
     * them.
     *
     * @param newUserId the {@code USERIDI}/{@code USERIDO} image, at most {@value #USERID_LENGTH}
     *     characters
     * @param newPasswd the {@code PASSWDI}/{@code PASSWDO} image, at most {@value #PASSWD_LENGTH}
     *     characters
     * @return a new response carrying both spans, never {@code null}
     * @throws NullPointerException if either argument is {@code null}; an untransmitted field is
     *     {@code LOW-VALUES} at its declared width, not {@code null}
     * @throws IllegalArgumentException if either argument exceeds its declared width
     */
    public SignOnResponse withReceivedMapArea(String newUserId, String newPasswd) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                newUserId, newPasswd, errMsg, role, nextProgram, nextMapset, nextMap, plainText,
                navigationContext);
    }

    /**
     * The unformatted transmission of {@code SEND-PLAIN-TEXT} -
     * {@code EXEC CICS SEND TEXT FROM(WS-MESSAGE) LENGTH(LENGTH OF WS-MESSAGE) ERASE FREEKB} at
     * {@code app/cbl/COSGN00C.cbl:164-169}.
     *
     * @param newPlainText the transmitted text, at most {@value #PLAIN_TEXT_LENGTH} characters
     * @return a new response carrying the transmission, never {@code null}
     * @throws NullPointerException if {@code newPlainText} is {@code null}; a path that sends no text
     *     carries {@value #PLAIN_TEXT_LENGTH} spaces
     * @throws IllegalArgumentException if {@code newPlainText} exceeds {@value #PLAIN_TEXT_LENGTH}
     *     characters
     */
    public SignOnResponse withPlainText(String newPlainText) {
        return new SignOnResponse(trnName, title01, curDate, pgmName, title02, curTime, applId, sysId,
                userId, passwd, errMsg, role, nextProgram, nextMapset, nextMap, newPlainText,
                navigationContext);
    }

    /**
     * A rendering that names every member except the password, which is replaced by a constant marker.
     *
     * <p>The record's generated {@code toString} includes every component, so inheriting it would reproduce
     * the plaintext credential in any log entry, exception message or debugger view that rendered this
     * object - which is CWE-532, and is a disclosure {@code app/cbl/COSGN00C.cbl} never makes.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "SignOnResponse[trnName=" + trnName
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
                + ", role=" + role
                + ", nextProgram=" + nextProgram
                + ", nextMapset=" + nextMapset
                + ", nextMap=" + nextMap
                + ", plainText=" + plainText
                + ", navigationContext=" + navigationContext
                + ']';
    }

    private static String requireWidth(String value, int width, String field) {
        if (value == null) {
            throw new NullPointerException(field + " must not be null; an unset PIC X("
                    + width + ") field holds " + width + " spaces");
        }
        if (value.length() > width) {
            throw new IllegalArgumentException(field + " is PIC X(" + width + ") but the value is "
                    + value.length() + " characters: \"" + value
                    + "\". Shorten it deliberately with FixedWidthCodec.movePicX rather than "
                    + "relying on an implicit truncation");
        }
        return value;
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }
}
