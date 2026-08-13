package com.vsergeychik.carddemo.user.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inbound payload of {@code PUT /api/users/&#123;userId&#125;} - CICS transaction {@code CU02}, program
 * {@code app/cbl/COUSR02C.cbl}, mapset {@code COUSR02}, map {@code COUSR2A}.
 *
 * <p>The order below is the declaration order of {@code 01 COUSR2AI} and, independently, the order of the
 * name-labelled {@code DFHMDF} definitions in the mapset.
 *
 * @param trnName {@code TRNNAMEI PIC X(4)}: the transaction identifier shown top left,
 *     {@link #TRANSACTION_ID} for this screen
 * @param title01 {@code TITLE01I PIC X(40)}: the first title line
 * @param curDate {@code CURDATEI PIC X(8)}: the current date as {@code mm/dd/yy}
 * @param pgmName {@code PGMNAMEI PIC X(8)}: the program name shown second line left, {@link #PROGRAM_NAME}
 *     for this screen
 * @param title02 {@code TITLE02I PIC X(40)}: the second title line
 * @param curTime {@code CURTIMEI PIC X(8)}: the current time as {@code hh:mm:ss} - eight characters on this
 *     screen, not nine
 * @param usrIdIn {@code USRIDINI PIC X(8)}: the user to look up or update, keying
 *     {@code SEC-USR-ID PIC X(08)}
 * @param fName {@code FNAMEI PIC X(20)}: the first name, compared with {@code SEC-USR-FNAME}
 * @param lName {@code LNAMEI PIC X(20)}: the last name, compared with {@code SEC-USR-LNAME}
 * @param passwd {@code PASSWDI PIC X(8)}: the password, plaintext and compared with {@code SEC-USR-PWD}
 *     byte for byte
 * @param usrType {@code USRTYPEI PIC X(1)}: the user type, compared with {@code SEC-USR-TYPE}
 * @param errMsg {@code ERRMSGI PIC X(78)}: the message line, seventy-eight characters
 * @param navigationContext the {@code CARDDEMO-COMMAREA} handed back by the previous turn, or {@code null}
 *     when there is none - the {@code EIBCALEN = 0} state of {@code COUSR02C.cbl:90}
 * @param aid the resolved attention identifier token, at most {@value #AID_LENGTH} characters, as
 *     {@code common.PfKeyResolver} produces it - {@code ENTER}, {@code PFK03}, {@code PFK04}, {@code PFK05}
 * @param cu02Info the 34-byte {@code 05 CDEMO-CU02-INFO} extension of {@code app/cbl/COUSR02C.cbl:50-58},
 *     restored from {@code DFHCOMMAREA} at line 94 behind the 160-byte communication area
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record UserUpdateRequest(

        @Size(max = TRNNAME_LENGTH) @JsonProperty("trnname") String trnName,

        @Size(max = TITLE01_LENGTH) String title01,

        @Size(max = CURDATE_LENGTH) @JsonProperty("curdate") String curDate,

        @Size(max = PGMNAME_LENGTH) @JsonProperty("pgmname") String pgmName,

        @Size(max = TITLE02_LENGTH) String title02,

        @Size(max = CURTIME_LENGTH) @JsonProperty("curtime") String curTime,

        // USRIDIN, not USERID, and declared before the two name fields exactly as this mapset orders them.
        @Size(max = USRIDIN_LENGTH) @JsonProperty("usridin") String usrIdIn,

        @Size(max = FNAME_LENGTH) @JsonProperty("fname") String fName,

        @Size(max = LNAME_LENGTH) @JsonProperty("lname") String lName,

        @Size(max = PASSWD_LENGTH) String passwd,

        @Size(max = USRTYPE_LENGTH) @JsonProperty("usrtype") String usrType,

        // Seventy-eight, although WS-MESSAGE is X(80): the 80-to-78 truncation at COUSR02C.cbl:270 is the
        // controller's, performed through common.FixedWidthCodec, and is not performed in this file.
        @Size(max = ERRMSG_LENGTH) @JsonProperty("errmsg") String errMsg,

        // Referenced, never widened - the copybook is exactly 160 bytes and is shared by all seventeen
        // controllers. May be null: that is EIBCALEN = 0, the handled state at COUSR02C.cbl:90-92.
        NavigationContext navigationContext,

        @Size(max = AID_LENGTH) String aid,

        // It is carried as a member of its own rather than folded into NavigationContext, because the
        // copybook is exactly 160 bytes and is shared by all seventeen controllers while this group belongs
        // to this program alone.
        Cu02Info cu02Info) {
    /**
     * Normalises the extension carrier, which has no absent state.
     *
     * <p>Every other component is left exactly as the caller sent it, including its trailing spaces,
     * because the change tests at {@code COUSR02C.cbl:219-234} compare untrimmed.
     */
    public UserUpdateRequest {
        cu02Info = cu02Info == null ? Cu02Info.initial() : cu02Info;
    }

    /**
     * Symbolic-map item behind {@link #trnName()}: {@code TRNNAMEI}, {@code COUSR02.CPY:24}.
     */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /**
     * Symbolic-map item behind {@link #title01()}: {@code TITLE01I}, {@code COUSR02.CPY:30}.
     */
    public static final String TITLE01_FIELD = "TITLE01I";

    /**
     * Symbolic-map item behind {@link #curDate()}: {@code CURDATEI}, {@code COUSR02.CPY:36}.
     */
    public static final String CURDATE_FIELD = "CURDATEI";

    /**
     * Symbolic-map item behind {@link #pgmName()}: {@code PGMNAMEI}, {@code COUSR02.CPY:42}.
     */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /**
     * Symbolic-map item behind {@link #title02()}: {@code TITLE02I}, {@code COUSR02.CPY:48}.
     */
    public static final String TITLE02_FIELD = "TITLE02I";

    /**
     * Symbolic-map item behind {@link #curTime()}: {@code CURTIMEI}, {@code COUSR02.CPY:54}.
     */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /**
     * Symbolic-map item behind {@link #usrIdIn()}: {@code USRIDINI}, {@code COUSR02.CPY:60}.
     */
    public static final String USRIDIN_FIELD = "USRIDINI";

    /**
     * Symbolic-map item behind {@link #fName()}: {@code FNAMEI}, {@code COUSR02.CPY:66}.
     */
    public static final String FNAME_FIELD = "FNAMEI";

    /**
     * Symbolic-map item behind {@link #lName()}: {@code LNAMEI}, {@code COUSR02.CPY:72}.
     */
    public static final String LNAME_FIELD = "LNAMEI";

    /**
     * Symbolic-map item behind {@link #passwd()}: {@code PASSWDI}, {@code COUSR02.CPY:78}.
     */
    public static final String PASSWD_FIELD = "PASSWDI";

    /**
     * Symbolic-map item behind {@link #usrType()}: {@code USRTYPEI}, {@code COUSR02.CPY:84}.
     */
    public static final String USRTYPE_FIELD = "USRTYPEI";

    /**
     * Symbolic-map item behind {@link #errMsg()}: {@code ERRMSGI}, {@code COUSR02.CPY:90}.
     */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    /**
     * {@code TRNNAMEI PIC X(4)}, {@code TRNNAME DFHMDF LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, {@code TITLE01 DFHMDF LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, {@code CURDATE DFHMDF LENGTH=8}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, {@code PGMNAME DFHMDF LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}, {@code TITLE02 DFHMDF LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code CURTIME DFHMDF LENGTH=8}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code USRIDINI PIC X(8)}, {@code USRIDIN DFHMDF LENGTH=8}.
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * {@code FNAMEI PIC X(20)}, {@code FNAME DFHMDF LENGTH=20}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * {@code LNAMEI PIC X(20)}, {@code LNAME DFHMDF LENGTH=20}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * {@code PASSWDI PIC X(8)}, {@code PASSWD DFHMDF LENGTH=8}.
     */
    public static final int PASSWD_LENGTH = 8;

    /**
     * {@code USRTYPEI PIC X(1)}, {@code USRTYPE DFHMDF LENGTH=1}.
     */
    public static final int USRTYPE_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code ERRMSG DFHMDF LENGTH=78} at {@code POS=(23,1)}.
     */
    public static final int ERRMSG_LENGTH = 78;

    public static final int AID_LENGTH = 5;

    /**
     * The map: {@code COUSR2A}, from {@code COUSR2A DFHMDI} at {@code app/bms/COUSR02.bms:26} and from
     * {@code MAP('COUSR2A')} at {@code COUSR02C.cbl:273} and {@code :286}.
     */
    public static final String MAP_NAME = "COUSR2A";

    /**
     * The mapset: {@code COUSR02}, from {@code COUSR02 DFHMSD} at {@code app/bms/COUSR02.bms:19} and from
     * {@code MAPSET('COUSR02')} at {@code COUSR02C.cbl:274} and {@code :287}.
     */
    public static final String MAPSET_NAME = "COUSR02";

    /**
     * The symbolic map this payload projects: {@code COUSR2AI}, the input view declared at
     * {@code app/cpy-bms/COUSR02.CPY:17} and the target of {@code INTO(COUSR2AI)} at
     * {@code COUSR02C.cbl:288}.
     */
    public static final String SYMBOLIC_MAP_INPUT = "COUSR2AI";

    /**
     * The CICS transaction: {@code CU02}, from {@code WS-TRANID PIC X(04) VALUE 'CU02'} at
     * {@code COUSR02C.cbl:37}.
     */
    public static final String TRANSACTION_ID = "CU02";

    /**
     * The COBOL program: {@code COUSR02C}, from {@code WS-PGMNAME PIC X(08) VALUE 'COUSR02C'} at
     * {@code COUSR02C.cbl:36}.
     */
    public static final String PROGRAM_NAME = "COUSR02C";

    public static final String USER_TYPE_ADMIN = "A";

    public static final String USER_TYPE_USER = "U";

    /**
     * The number of members that project a screen field: twelve.
     */
    public static final int MAP_FIELD_COUNT = 12;

    /**
     * The twelve symbolic-map item names in declaration order - the screen contract as data.
     */
    public static final List<String> MAP_FIELD_NAMES = List.of(TRNNAME_FIELD,
            TITLE01_FIELD,
            CURDATE_FIELD,
            PGMNAME_FIELD,
            TITLE02_FIELD,
            CURTIME_FIELD,
            USRIDIN_FIELD,
            FNAME_FIELD,
            LNAME_FIELD,
            PASSWD_FIELD,
            USRTYPE_FIELD,
            ERRMSG_FIELD);

    private static final String PASSWD_MASK = SensitiveDiagnostics.REDACTED;

    private static final String SPACE = " ";

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * Whether a communication area travelled with this request.
     *
     * @return {@code true} when {@link #navigationContext()} is present, {@code false} when it is
     *     {@code null} - the {@code EIBCALEN = 0} state
     */
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * Whether the communication area says this is a first entry - {@code 88 CDEMO-PGM-ENTER VALUE 0} over
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)}, {@code app/cpy/COCOM01Y.cpy:29-30}.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *     {@link NavigationContext#PGM_CONTEXT_ENTER}; {@code false} when there is no communication area at all
     */
    public boolean contextIsEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether the communication area says this is a re-entry - {@code 88 CDEMO-PGM-REENTER VALUE 1} over
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)}, {@code app/cpy/COCOM01Y.cpy:29,31}.
     *
     * @return {@code true} only when a communication area is present and its program context is
     *     {@link NavigationContext#PGM_CONTEXT_REENTER}; {@code false} when there is no communication area at
     *     all
     */
    public boolean contextIsReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    /**
     * A diagnostic rendering with {@link #passwd()} replaced by {@link SensitiveDiagnostics#REDACTED}.
     *
     * @return a rendering of all fifteen components, never {@code null}, with the password masked
     */
    @Override
    public String toString() {
        return "UserUpdateRequest[trnName=" + trnName
                + ", title01=" + title01
                + ", curDate=" + curDate
                + ", pgmName=" + pgmName
                + ", title02=" + title02
                + ", curTime=" + curTime
                + ", usrIdIn=" + usrIdIn
                + ", fName=" + SensitiveDiagnostics.describeText(fName)
                + ", lName=" + SensitiveDiagnostics.describeText(lName)
                + ", passwd=" + (passwd == null ? null : PASSWD_MASK)
                + ", usrType=" + usrType
                + ", errMsg=" + errMsg
                + ", navigationContext=" + navigationContext
                + ", aid=" + aid
                + ", cu02Info=" + cu02Info
                + ']';
    }

    /**
     * {@code 05 CDEMO-CU02-INFO} - the thirty-four bytes this program appends to {@code CARDDEMO-COMMAREA},
     * declared in its own working storage at lines 50-58: These six items are not in
     * {@code app/cpy/COCOM01Y.cpy}.
     *
     * @param usridFirst {@code CDEMO-CU02-USRID-FIRST PIC X(08)}, line 51 - carried, never read here
     * @param usridLast {@code CDEMO-CU02-USRID-LAST PIC X(08)}, line 52 - carried, never read here
     * @param pageNum {@code CDEMO-CU02-PAGE-NUM PIC 9(08)}, line 53 - carried, never read here
     * @param nextPageFlg {@code CDEMO-CU02-NEXT-PAGE-FLG PIC X(01)}, line 54, whose {@code 88}-levels at
     *     55-56 are {@code NEXT-PAGE-YES 'Y'} and {@code NEXT-PAGE-NO 'N'} - carried, never read here
     * @param usrSelFlg {@code CDEMO-CU02-USR-SEL-FLG PIC X(01)}, line 57 - carried, never read here
     * @param usrSelected {@code CDEMO-CU02-USR-SELECTED PIC X(08)}, line 58 - the one item this program
     *     reads, at lines 99-102
     */
    public record Cu02Info(@JsonProperty("usridFirst") String usridFirst,
                           @JsonProperty("usridLast") String usridLast,
                           @JsonProperty("pageNum") int pageNum,
                           @JsonProperty("nextPageFlg") String nextPageFlg,
                           @JsonProperty("usrSelFlg") String usrSelFlg,
                           @JsonProperty("usrSelected") String usrSelected) {
        /**
         * Declared width of {@code CDEMO-CU02-USRID-FIRST}: {@code PIC X(08)}.
         */
        public static final int USRID_FIRST_LENGTH = 8;

        /**
         * Declared width of {@code CDEMO-CU02-USRID-LAST}: {@code PIC X(08)}.
         */
        public static final int USRID_LAST_LENGTH = 8;

        /**
         * Declared digits of {@code CDEMO-CU02-PAGE-NUM}: {@code PIC 9(08)}, unsigned.
         */
        public static final int PAGE_NUM_DIGITS = 8;

        /**
         * Declared width of {@code CDEMO-CU02-NEXT-PAGE-FLG}: {@code PIC X(01)}.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * Declared width of {@code CDEMO-CU02-USR-SEL-FLG}: {@code PIC X(01)}.
         */
        public static final int USR_SEL_FLG_LENGTH = 1;

        /**
         * Declared width of {@code CDEMO-CU02-USR-SELECTED}: {@code PIC X(08)}.
         */
        public static final int USR_SELECTED_LENGTH = 8;

        public static final int LENGTH = USRID_FIRST_LENGTH + USRID_LAST_LENGTH + PAGE_NUM_DIGITS
                + NEXT_PAGE_FLG_LENGTH + USR_SEL_FLG_LENGTH + USR_SELECTED_LENGTH;

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'} - line 55.
         */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'} - line 56, and the field's own {@code VALUE} at line 54.
         */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * Renders every character item at its declared width and rejects a negative page number.
         */
        public Cu02Info {
            usridFirst = image(usridFirst, USRID_FIRST_LENGTH);
            usridLast = image(usridLast, USRID_LAST_LENGTH);
            nextPageFlg = image(nextPageFlg, NEXT_PAGE_FLG_LENGTH);
            usrSelFlg = image(usrSelFlg, USR_SEL_FLG_LENGTH);
            usrSelected = image(usrSelected, USR_SELECTED_LENGTH);
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CU02-PAGE-NUM is PIC 9(" + PAGE_NUM_DIGITS
                        + "), an unsigned picture with no sign position, so " + pageNum
                        + " has no representation in it");
            }
            if (pageNum >= (int) Math.pow(10, PAGE_NUM_DIGITS)) {
                throw new IllegalArgumentException("CDEMO-CU02-PAGE-NUM is PIC 9(" + PAGE_NUM_DIGITS
                        + ") and cannot hold " + pageNum + "; a numeric MOVE would drop its high-order "
                        + "digits and the result would still look plausible");
            }
        }

        /**
         * The group as a freshly initialised area: spaces in the five character items, zero in the page
         * number, and {@code 'N'} in {@code CDEMO-CU02-NEXT-PAGE-FLG}.
         *
         * @return the initial extension group, never {@code null}
         */
        public static Cu02Info initial() {
            return new Cu02Info(SPACE.repeat(USRID_FIRST_LENGTH),
                    SPACE.repeat(USRID_LAST_LENGTH),
                    0,
                    NEXT_PAGE_NO,
                    SPACE.repeat(USR_SEL_FLG_LENGTH),
                    SPACE.repeat(USR_SELECTED_LENGTH));
        }

        /**
         * The six items keyed by the name {@code app/cbl/COUSR02C.cbl:51-58} spells, in declaration order.
         *
         * @return an unmodifiable, declaration-ordered map of the six item names to their images
         */
        public Map<String, String> fieldImages() {
            Map<String, String> images = new LinkedHashMap<>();
            images.put("CDEMO-CU02-USRID-FIRST", usridFirst);
            images.put("CDEMO-CU02-USRID-LAST", usridLast);
            images.put("CDEMO-CU02-PAGE-NUM", PICTURE_RULES.movePic9(pageNum, PAGE_NUM_DIGITS));
            images.put("CDEMO-CU02-NEXT-PAGE-FLG", nextPageFlg);
            images.put("CDEMO-CU02-USR-SEL-FLG", usrSelFlg);
            images.put("CDEMO-CU02-USR-SELECTED", usrSelected);
            return Collections.unmodifiableMap(images);
        }

        private static String image(String value, int length) {
            return PICTURE_RULES.movePicX(value == null ? "" : value, length);
        }
    }
}
