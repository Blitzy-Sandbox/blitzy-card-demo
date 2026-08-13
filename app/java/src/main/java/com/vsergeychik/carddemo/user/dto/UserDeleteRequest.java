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
import java.util.Map;

/**
 * The inbound payload of {@code DELETE /api/users/&#123;userId&#125;} - the REST projection of the input
 * view of the BMS screen driven by CICS transaction {@code CU03}, program {@code app/cbl/COUSR03C.cbl}
 * ("Delete a user from USRSEC file", line 5).
 *
 * @param trnName {@code TRNNAMEI PIC X(4)}, {@code app/cpy-bms/COUSR03.CPY} line 24
 * @param title01 {@code TITLE01I PIC X(40)}, line 30
 * @param curDate {@code CURDATEI PIC X(8)}, line 36
 * @param pgmName {@code PGMNAMEI PIC X(8)}, line 42
 * @param title02 {@code TITLE02I PIC X(40)}, line 48
 * @param curTime {@code CURTIMEI PIC X(8)}, line 54
 * @param usrIdIn {@code USRIDINI PIC X(8)}, line 60
 * @param fName {@code FNAMEI PIC X(20)}, line 66
 * @param lName {@code LNAMEI PIC X(20)}, line 72
 * @param usrType {@code USRTYPEI PIC X(1)}, line 78
 * @param errMsg {@code ERRMSGI PIC X(78)}, line 84
 * @param navigationContext the {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} of
 *     {@code app/cpy/COCOM01Y.cpy}, copied by {@code COUSR03C} at line 49 and returned to CICS at line 136
 * @param aid the resolved AID token, as produced by {@code common.PfKeyResolver} from {@code EIBAID}:
 *     {@value #AID_LENGTH} characters, the width of {@code CCARD-AID PIC X(5)}
 * @param cu03Info the {@code 05 CDEMO-CU03-INFO} extension the program appends to {@code CARDDEMO-COMMAREA}
 *     at {@code app/cbl/COUSR03C.cbl:50}
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public record UserDeleteRequest(

        @Size(max = TRNNAME_LENGTH) @JsonProperty("trnname") String trnName,

        @Size(max = TITLE01_LENGTH) String title01,

        @Size(max = CURDATE_LENGTH) @JsonProperty("curdate") String curDate,

        @Size(max = PGMNAME_LENGTH) @JsonProperty("pgmname") String pgmName,

        @Size(max = TITLE02_LENGTH) String title02,

        @Size(max = CURTIME_LENGTH) @JsonProperty("curtime") String curTime,

        @Size(max = USRIDIN_LENGTH) @JsonProperty("usridin") String usrIdIn,

        @Size(max = FNAME_LENGTH) @JsonProperty("fname") String fName,

        @Size(max = LNAME_LENGTH) @JsonProperty("lname") String lName,

        @Size(max = USRTYPE_LENGTH) @JsonProperty("usrtype") String usrType,

        @Size(max = ERRMSG_LENGTH) @JsonProperty("errmsg") String errMsg,

        NavigationContext navigationContext,

        @Size(max = AID_LENGTH) String aid,

        Cu03Info cu03Info) {
    /**
     * Normalises the extension carrier, which has no absent state.
     */
    public UserDeleteRequest {
        cu03Info = cu03Info == null ? Cu03Info.initial() : cu03Info;
    }

    /**
     * The CICS transaction that drives this screen: {@code WS-TRANID PIC X(04) VALUE 'CU03'},
     * {@code app/cbl/COUSR03C.cbl} line 37.
     */
    public static final String TRANSACTION_ID = "CU03";

    /**
     * The COBOL program this payload's screen belongs to: {@code WS-PGMNAME PIC X(08) VALUE 'COUSR03C'},
     * {@code app/cbl/COUSR03C.cbl} line 36.
     */
    public static final String PROGRAM_NAME = "COUSR03C";

    /**
     * The BMS map: {@code COUSR3A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)}, {@code app/bms/COUSR03.bms} lines
     * 26-28.
     */
    public static final String MAP_NAME = "COUSR3A";

    /**
     * The BMS mapset:
     * {@code COUSR03 DFHMSD CTRL=(ALARM,FREEKB), EXTATT=YES, LANG=COBOL, MODE=INOUT, STORAGE=AUTO, TIOAPFX=YES},
     * {@code app/bms/COUSR03.bms} lines 19-25.
     */
    public static final String MAPSET_NAME = "COUSR03";

    /**
     * The number of map-derived payload members: {@value}.
     */
    public static final int MAP_FIELD_COUNT = 11;

    // The COBOL item names, spelled exactly as app/cpy-bms/COUSR03.CPY spells them - the "I" suffix
    // included. These are the names the parity differ compares field by field, so a "tidied" name would
    // make a real difference invisible.

    public static final String TRNNAME_FIELD = "TRNNAMEI";

    public static final String TITLE01_FIELD = "TITLE01I";

    public static final String CURDATE_FIELD = "CURDATEI";

    public static final String PGMNAME_FIELD = "PGMNAMEI";

    public static final String TITLE02_FIELD = "TITLE02I";

    public static final String CURTIME_FIELD = "CURTIMEI";

    public static final String USRIDIN_FIELD = "USRIDINI";

    public static final String FNAME_FIELD = "FNAMEI";

    public static final String LNAME_FIELD = "LNAMEI";

    public static final String USRTYPE_FIELD = "USRTYPEI";

    public static final String ERRMSG_FIELD = "ERRMSGI";

    /**
     * Width of {@code TRNNAMEI PIC X(4)}, line 24; {@code TRNNAME DFHMDF LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * Width of {@code TITLE01I PIC X(40)}, line 30; {@code TITLE01 DFHMDF LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * Width of {@code CURDATEI PIC X(8)}, line 36; {@code CURDATE DFHMDF LENGTH=8}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * Width of {@code PGMNAMEI PIC X(8)}, line 42; {@code PGMNAME DFHMDF LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * Width of {@code TITLE02I PIC X(40)}, line 48; {@code TITLE02 DFHMDF LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * Width of {@code CURTIMEI PIC X(8)}, line 54; {@code CURTIME DFHMDF LENGTH=8}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * Width of {@code USRIDINI PIC X(8)}, line 60; {@code USRIDIN DFHMDF LENGTH=8}.
     */
    public static final int USRIDIN_LENGTH = 8;

    /**
     * Width of {@code FNAMEI PIC X(20)}, line 66; {@code FNAME DFHMDF LENGTH=20}.
     */
    public static final int FNAME_LENGTH = 20;

    /**
     * Width of {@code LNAMEI PIC X(20)}, line 72; {@code LNAME DFHMDF LENGTH=20}.
     */
    public static final int LNAME_LENGTH = 20;

    /**
     * Width of {@code USRTYPEI PIC X(1)}, line 78; {@code USRTYPE DFHMDF LENGTH=1}.
     */
    public static final int USRTYPE_LENGTH = 1;

    /**
     * Width of {@code ERRMSGI PIC X(78)}, line 84; {@code ERRMSG DFHMDF LENGTH=78}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Width of the AID token carried by {@link #aid()}: {@value}, the width of {@code CCARD-AID PIC X(5)}
     * in {@code app/cpy/CVCRD01Y.cpy}.
     */
    public static final int AID_LENGTH = 5;

    public static final int MAP_FIELDS_WIDTH_TOTAL = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + USRIDIN_LENGTH + FNAME_LENGTH
            + LNAME_LENGTH + USRTYPE_LENGTH + ERRMSG_LENGTH;

    /**
     * Width of the leading {@code 02 FILLER PIC X(12)} of both symbolic-map views,
     * {@code app/cpy-bms/COUSR03.CPY} lines 18 and 86: {@value}.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The per-field overhead of the input view: {@value} bytes, being {@code xxxL COMP PIC S9(4)} (2) +
     * {@code xxxF PICTURE X} (1) + {@code FILLER PICTURE X(4)} (4).
     */
    public static final int FIELD_OVERHEAD_LENGTH = 7;

    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + (MAP_FIELD_COUNT * FIELD_OVERHEAD_LENGTH) + MAP_FIELDS_WIDTH_TOTAL;

    /**
     * A payload whose {@value #MAP_FIELD_COUNT} map-derived members are each a run of spaces of that
     * member's declared width, whose navigation context is {@link NavigationContext#empty()} and whose AID
     * token is {@value #AID_LENGTH} spaces.
     *
     * @return a blank request payload, never {@code null} and containing no {@code null} member
     */
    public static UserDeleteRequest empty() {
        return new UserDeleteRequest(spaces(TRNNAME_LENGTH),
                spaces(TITLE01_LENGTH),
                spaces(CURDATE_LENGTH),
                spaces(PGMNAME_LENGTH),
                spaces(TITLE02_LENGTH),
                spaces(CURTIME_LENGTH),
                spaces(USRIDIN_LENGTH),
                spaces(FNAME_LENGTH),
                spaces(LNAME_LENGTH),
                spaces(USRTYPE_LENGTH),
                spaces(ERRMSG_LENGTH),
                NavigationContext.empty(),
                spaces(AID_LENGTH),
                Cu03Info.initial());
    }

    private static String spaces(int width) {
        return " ".repeat(width);
    }

    private static final String SPACE = " ";

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * Whether {@code 88 CDEMO-PGM-ENTER VALUE 0} holds for the carried navigation context - first entry, on
     * which {@code COUSR03C} paints the screen rather than validating it.
     *
     * @return {@code true} when a navigation context is present and its {@code CDEMO-PGM-CONTEXT} is
     *     {@value NavigationContext#PGM_CONTEXT_ENTER}; {@code false} when it is not
     */
    public boolean pgmEnter() {
        return navigationContext != null && navigationContext.isEnter();
    }

    /**
     * Whether {@code 88 CDEMO-PGM-REENTER VALUE 1} holds for the carried navigation context - re-entry, on
     * which {@code COUSR03C} evaluates {@code EIBAID} (lines 108-130) and validates what was typed.
     *
     * @return {@code true} when a navigation context is present and its {@code CDEMO-PGM-CONTEXT} is
     *     {@value NavigationContext#PGM_CONTEXT_REENTER}; {@code false} otherwise, including when no context
     *     was supplied
     */
    public boolean pgmReenter() {
        return navigationContext != null && navigationContext.isReenter();
    }

    /**
     * A diagnostic rendering that withholds the personal name, per {@link SensitiveDiagnostics}.
     *
     * @return a rendering safe to log, never {@code null}
     */
    @Override
    public String toString() {
        return "UserDeleteRequest[trnName=" + trnName
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
                + ", aid=" + aid
                + ']';
    }

    /**
     * {@code 05 CDEMO-CU03-INFO} - the thirty-four bytes this program appends to {@code CARDDEMO-COMMAREA},
     * declared in its own working storage at lines 50-58: These six items are not in
     * {@code app/cpy/COCOM01Y.cpy}.
     *
     * @param usridFirst {@code CDEMO-CU03-USRID-FIRST PIC X(08)}, line 51 - carried, never read here
     * @param usridLast {@code CDEMO-CU03-USRID-LAST PIC X(08)}, line 52 - carried, never read here
     * @param pageNum {@code CDEMO-CU03-PAGE-NUM PIC 9(08)}, line 53 - carried, never read here
     * @param nextPageFlg {@code CDEMO-CU03-NEXT-PAGE-FLG PIC X(01)}, line 54, whose {@code 88}-levels at
     *     55-56 are {@code NEXT-PAGE-YES 'Y'} and {@code NEXT-PAGE-NO 'N'} - carried, never read here
     * @param usrSelFlg {@code CDEMO-CU03-USR-SEL-FLG PIC X(01)}, line 57 - carried, never read here
     * @param usrSelected {@code CDEMO-CU03-USR-SELECTED PIC X(08)}, line 58 - the one item this program
     *     reads, at lines 99-102
     */
    public record Cu03Info(@JsonProperty("usridFirst") String usridFirst,
                           @JsonProperty("usridLast") String usridLast,
                           @JsonProperty("pageNum") int pageNum,
                           @JsonProperty("nextPageFlg") String nextPageFlg,
                           @JsonProperty("usrSelFlg") String usrSelFlg,
                           @JsonProperty("usrSelected") String usrSelected) {
        /**
         * Declared width of {@code CDEMO-CU03-USRID-FIRST}: {@code PIC X(08)}.
         */
        public static final int USRID_FIRST_LENGTH = 8;

        /**
         * Declared width of {@code CDEMO-CU03-USRID-LAST}: {@code PIC X(08)}.
         */
        public static final int USRID_LAST_LENGTH = 8;

        /**
         * Declared digits of {@code CDEMO-CU03-PAGE-NUM}: {@code PIC 9(08)}, unsigned.
         */
        public static final int PAGE_NUM_DIGITS = 8;

        /**
         * Declared width of {@code CDEMO-CU03-NEXT-PAGE-FLG}: {@code PIC X(01)}.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * Declared width of {@code CDEMO-CU03-USR-SEL-FLG}: {@code PIC X(01)}.
         */
        public static final int USR_SEL_FLG_LENGTH = 1;

        /**
         * Declared width of {@code CDEMO-CU03-USR-SELECTED}: {@code PIC X(08)}.
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
        public Cu03Info {
            usridFirst = image(usridFirst, USRID_FIRST_LENGTH);
            usridLast = image(usridLast, USRID_LAST_LENGTH);
            nextPageFlg = image(nextPageFlg, NEXT_PAGE_FLG_LENGTH);
            usrSelFlg = image(usrSelFlg, USR_SEL_FLG_LENGTH);
            usrSelected = image(usrSelected, USR_SELECTED_LENGTH);
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CU03-PAGE-NUM is PIC 9(" + PAGE_NUM_DIGITS
                        + "), an unsigned picture with no sign position, so " + pageNum
                        + " has no representation in it");
            }
            if (pageNum >= (int) Math.pow(10, PAGE_NUM_DIGITS)) {
                throw new IllegalArgumentException("CDEMO-CU03-PAGE-NUM is PIC 9(" + PAGE_NUM_DIGITS
                        + ") and cannot hold " + pageNum + "; a numeric MOVE would drop its high-order "
                        + "digits and the result would still look plausible");
            }
        }

        /**
         * The group as a freshly initialised area: spaces in the five character items, zero in the page
         * number, and {@code 'N'} in {@code CDEMO-CU03-NEXT-PAGE-FLG}.
         *
         * @return the initial extension group, never {@code null}
         */
        public static Cu03Info initial() {
            return new Cu03Info(SPACE.repeat(USRID_FIRST_LENGTH),
                    SPACE.repeat(USRID_LAST_LENGTH),
                    0,
                    NEXT_PAGE_NO,
                    SPACE.repeat(USR_SEL_FLG_LENGTH),
                    SPACE.repeat(USR_SELECTED_LENGTH));
        }

        /**
         * The six items keyed by the name {@code app/cbl/COUSR03C.cbl:51-58} spells, in declaration order.
         *
         * @return an unmodifiable, declaration-ordered map of the six item names to their images
         */
        public Map<String, String> fieldImages() {
            Map<String, String> images = new LinkedHashMap<>();
            images.put("CDEMO-CU03-USRID-FIRST", usridFirst);
            images.put("CDEMO-CU03-USRID-LAST", usridLast);
            images.put("CDEMO-CU03-PAGE-NUM", PICTURE_RULES.movePic9(pageNum, PAGE_NUM_DIGITS));
            images.put("CDEMO-CU03-NEXT-PAGE-FLG", nextPageFlg);
            images.put("CDEMO-CU03-USR-SEL-FLG", usrSelFlg);
            images.put("CDEMO-CU03-USR-SELECTED", usrSelected);
            return Collections.unmodifiableMap(images);
        }

        private static String image(String value, int length) {
            return PICTURE_RULES.movePicX(value == null ? "" : value, length);
        }
    }
}
