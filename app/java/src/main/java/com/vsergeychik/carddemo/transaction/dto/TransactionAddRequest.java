package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Inbound REST payload for CSD transaction {@code CT01}, program {@code COTRN01C}, mapset {@code COTRN01},
 * map {@code COTRN1A}.
 *
 * <p>Every payload member below is a 1:1 projection of one {@code xxxI} item of {@code 01 COTRN1AI} in
 * {@code app/cpy-bms/COTRN01.CPY} (the group opens at line 17), and each of those items pairs with exactly
 * one name-labelled {@code DFHMDF} definition in {@code app/bms/COTRN01.bms}.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class TransactionAddRequest {
    public static final String TRANSACTION_ID = "CT01";

    /**
     * The COBOL program this payload models: {@code COTRN01C}.
     */
    public static final String PROGRAM_NAME = "COTRN01C";

    /**
     * BMS mapset name, as {@code MAPSET('COTRN01')} at {@code COTRN01C} line 221.
     */
    public static final String MAPSET_NAME = "COTRN01";

    public static final String MAP_NAME = "COTRN1A";

    /**
     * Symbolic map group projected by this type: {@code 01 COTRN1AI}, copybook line 17.
     */
    public static final String SYMBOLIC_MAP_GROUP = "COTRN1AI";

    /**
     * The aliasing group that redefines {@link #SYMBOLIC_MAP_GROUP}:
     * {@code 01 COTRN1AO REDEFINES COTRN1AI}, copybook line 145.
     */
    public static final String SYMBOLIC_MAP_OUTPUT_GROUP = "COTRN1AO";

    // Nothing below is a magic number: every offset is derived from the field before it, exactly as the
    // copybook lays them out, so the chain can be read straight down against app/cpy-bms/COTRN01.CPY.

    /**
     * {@code 02 FILLER PIC X(12)}, the {@code TIOAPFX=YES} prefix that opens the group.
     */
    public static final int TIOA_PREFIX_LENGTH = 12;

    /**
     * {@code 02 xxxL COMP PIC S9(4)} - a signed binary halfword, two bytes.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * {@code 02 xxxF PICTURE X} - the flag byte, one byte, redefined by {@code xxxA}.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * {@code 02 FILLER PICTURE X(4)} - the reserved span between the flag byte and the value.
     */
    public static final int RESERVED_FILLER_LENGTH = 4;

    public static final int FIELD_METADATA_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /**
     * Number of payload fields: name-labelled {@code DFHMDF} entries and {@code xxxI} items alike.
     */
    public static final int PAYLOAD_FIELD_COUNT = 21;

    public static final short CURSOR_REQUEST = -1;

    /**
     * {@code EIBAID} is a single-byte attention identifier; the {@code DFHAID} tokens are one byte.
     */
    public static final int AID_LENGTH = 1;

    /**
     * Characters in the {@code CCARD-AID} token form of {@link #getAid()}: five.
     */
    public static final int AID_TOKEN_LENGTH = 5;

    /**
     * {@code TRNNAMEI}, copybook line 24; {@code DFHMDF TRNNAME} at BMS line 34, {@code POS=(1,7)}.
     */
    public static final String TRNNAME_FIELD = "TRNNAMEI";

    /**
     * {@code TITLE01I}, copybook line 30; {@code DFHMDF TITLE01} at BMS line 38, {@code POS=(1,21)}.
     */
    public static final String TITLE01_FIELD = "TITLE01I";

    /**
     * {@code CURDATEI}, copybook line 36; {@code DFHMDF CURDATE} at BMS line 47, {@code POS=(1,71)}.
     */
    public static final String CURDATE_FIELD = "CURDATEI";

    /**
     * {@code PGMNAMEI}, copybook line 42; {@code DFHMDF PGMNAME} at BMS line 57, {@code POS=(2,7)}.
     */
    public static final String PGMNAME_FIELD = "PGMNAMEI";

    /**
     * {@code TITLE02I}, copybook line 48; {@code DFHMDF TITLE02} at BMS line 61, {@code POS=(2,21)}.
     */
    public static final String TITLE02_FIELD = "TITLE02I";

    /**
     * {@code CURTIMEI}, copybook line 54; {@code DFHMDF CURTIME} at BMS line 70, {@code POS=(2,71)}.
     */
    public static final String CURTIME_FIELD = "CURTIMEI";

    /**
     * {@code TRNIDINI}, copybook line 60; {@code DFHMDF TRNIDIN} at BMS line 85,
     * {@code ATTRB=(FSET,IC,NORM,UNPROT)}, {@code POS=(6,21)}.
     */
    public static final String TRNIDIN_FIELD = "TRNIDINI";

    /**
     * {@code TRNIDI}, copybook line 66; {@code DFHMDF TRNID} at BMS line 105, {@code POS=(10,22)}.
     */
    public static final String TRNID_FIELD = "TRNIDI";

    /**
     * {@code CARDNUMI}, copybook line 72; {@code DFHMDF CARDNUM} at BMS line 118, {@code POS=(10,58)}.
     */
    public static final String CARDNUM_FIELD = "CARDNUMI";

    /**
     * {@code TTYPCDI}, copybook line 78; {@code DFHMDF TTYPCD} at BMS line 132, {@code POS=(12,15)}.
     */
    public static final String TTYPCD_FIELD = "TTYPCDI";

    /**
     * {@code TCATCDI}, copybook line 84; {@code DFHMDF TCATCD}.
     */
    public static final String TCATCD_FIELD = "TCATCDI";

    /**
     * {@code TRNSRCI}, copybook line 90; {@code DFHMDF TRNSRC}.
     */
    public static final String TRNSRC_FIELD = "TRNSRCI";

    /**
     * {@code TDESCI}, copybook line 96; {@code DFHMDF TDESC}.
     */
    public static final String TDESC_FIELD = "TDESCI";

    /**
     * {@code TRNAMTI}, copybook line 102; {@code DFHMDF TRNAMT}.
     */
    public static final String TRNAMT_FIELD = "TRNAMTI";

    /**
     * {@code TORIGDTI}, copybook line 108; {@code DFHMDF TORIGDT}.
     */
    public static final String TORIGDT_FIELD = "TORIGDTI";

    /**
     * {@code TPROCDTI}, copybook line 114; {@code DFHMDF TPROCDT}.
     */
    public static final String TPROCDT_FIELD = "TPROCDTI";

    /**
     * {@code MIDI}, copybook line 120; {@code DFHMDF MID}.
     */
    public static final String MID_FIELD = "MIDI";

    /**
     * {@code MNAMEI}, copybook line 126; {@code DFHMDF MNAME}.
     */
    public static final String MNAME_FIELD = "MNAMEI";

    /**
     * {@code MCITYI}, copybook line 132; {@code DFHMDF MCITY}.
     */
    public static final String MCITY_FIELD = "MCITYI";

    /**
     * {@code MZIPI}, copybook line 138; {@code DFHMDF MZIP}.
     */
    public static final String MZIP_FIELD = "MZIPI";

    /**
     * {@code ERRMSGI}, copybook line 144; {@code DFHMDF ERRMSG}.
     */
    public static final String ERRMSG_FIELD = "ERRMSGI";

    /**
     * {@code TRNNAMEI PIC X(4)}; {@code LENGTH=4}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}; {@code LENGTH=40}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}; {@code LENGTH=8}, {@code INITIAL='mm/dd/yy'}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}; {@code LENGTH=8}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}; {@code LENGTH=40}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}; {@code LENGTH=8}, {@code INITIAL='hh:mm:ss'}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code TRNIDINI PIC X(16)}; {@code LENGTH=16}.
     */
    public static final int TRNIDIN_LENGTH = 16;

    /**
     * {@code TRNIDI PIC X(16)}; {@code LENGTH=16}.
     */
    public static final int TRNID_LENGTH = 16;

    /**
     * {@code CARDNUMI PIC X(16)}; {@code LENGTH=16}.
     */
    public static final int CARDNUM_LENGTH = 16;

    /**
     * {@code TTYPCDI PIC X(2)}; {@code LENGTH=2}.
     */
    public static final int TTYPCD_LENGTH = 2;

    /**
     * {@code TCATCDI PIC X(4)}; {@code LENGTH=4}.
     */
    public static final int TCATCD_LENGTH = 4;

    /**
     * {@code TRNSRCI PIC X(10)}; {@code LENGTH=10}.
     */
    public static final int TRNSRC_LENGTH = 10;

    /**
     * {@code TDESCI PIC X(60)}; {@code LENGTH=60}.
     */
    public static final int TDESC_LENGTH = 60;

    /**
     * {@code TRNAMTI PIC X(12)}; {@code LENGTH=12}.
     */
    public static final int TRNAMT_LENGTH = 12;

    /**
     * {@code TORIGDTI PIC X(10)}; {@code LENGTH=10}.
     */
    public static final int TORIGDT_LENGTH = 10;

    /**
     * {@code TPROCDTI PIC X(10)}; {@code LENGTH=10}.
     */
    public static final int TPROCDT_LENGTH = 10;

    /**
     * {@code MIDI PIC X(9)}; {@code LENGTH=9}.
     */
    public static final int MID_LENGTH = 9;

    /**
     * {@code MNAMEI PIC X(30)}; {@code LENGTH=30}.
     */
    public static final int MNAME_LENGTH = 30;

    /**
     * {@code MCITYI PIC X(25)}; {@code LENGTH=25}.
     */
    public static final int MCITY_LENGTH = 25;

    /**
     * {@code MZIPI PIC X(10)}; {@code LENGTH=10}.
     */
    public static final int MZIP_LENGTH = 10;

    /**
     * {@code ERRMSGI PIC X(78)}; {@code LENGTH=78}.
     */
    public static final int ERRMSG_LENGTH = 78;

    public static final int TRNNAME_GROUP_OFFSET = TIOA_PREFIX_LENGTH;
    public static final int TRNNAME_OFFSET = TRNNAME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TITLE01_GROUP_OFFSET = TRNNAME_OFFSET + TRNNAME_LENGTH;
    public static final int TITLE01_OFFSET = TITLE01_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int CURDATE_GROUP_OFFSET = TITLE01_OFFSET + TITLE01_LENGTH;
    public static final int CURDATE_OFFSET = CURDATE_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int PGMNAME_GROUP_OFFSET = CURDATE_OFFSET + CURDATE_LENGTH;
    public static final int PGMNAME_OFFSET = PGMNAME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TITLE02_GROUP_OFFSET = PGMNAME_OFFSET + PGMNAME_LENGTH;
    public static final int TITLE02_OFFSET = TITLE02_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int CURTIME_GROUP_OFFSET = TITLE02_OFFSET + TITLE02_LENGTH;
    public static final int CURTIME_OFFSET = CURTIME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TRNIDIN_GROUP_OFFSET = CURTIME_OFFSET + CURTIME_LENGTH;
    public static final int TRNIDIN_OFFSET = TRNIDIN_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TRNID_GROUP_OFFSET = TRNIDIN_OFFSET + TRNIDIN_LENGTH;
    public static final int TRNID_OFFSET = TRNID_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int CARDNUM_GROUP_OFFSET = TRNID_OFFSET + TRNID_LENGTH;
    public static final int CARDNUM_OFFSET = CARDNUM_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TTYPCD_GROUP_OFFSET = CARDNUM_OFFSET + CARDNUM_LENGTH;
    public static final int TTYPCD_OFFSET = TTYPCD_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TCATCD_GROUP_OFFSET = TTYPCD_OFFSET + TTYPCD_LENGTH;
    public static final int TCATCD_OFFSET = TCATCD_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TRNSRC_GROUP_OFFSET = TCATCD_OFFSET + TCATCD_LENGTH;
    public static final int TRNSRC_OFFSET = TRNSRC_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TDESC_GROUP_OFFSET = TRNSRC_OFFSET + TRNSRC_LENGTH;
    public static final int TDESC_OFFSET = TDESC_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TRNAMT_GROUP_OFFSET = TDESC_OFFSET + TDESC_LENGTH;
    public static final int TRNAMT_OFFSET = TRNAMT_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TORIGDT_GROUP_OFFSET = TRNAMT_OFFSET + TRNAMT_LENGTH;
    public static final int TORIGDT_OFFSET = TORIGDT_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int TPROCDT_GROUP_OFFSET = TORIGDT_OFFSET + TORIGDT_LENGTH;
    public static final int TPROCDT_OFFSET = TPROCDT_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int MID_GROUP_OFFSET = TPROCDT_OFFSET + TPROCDT_LENGTH;
    public static final int MID_OFFSET = MID_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int MNAME_GROUP_OFFSET = MID_OFFSET + MID_LENGTH;
    public static final int MNAME_OFFSET = MNAME_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int MCITY_GROUP_OFFSET = MNAME_OFFSET + MNAME_LENGTH;
    public static final int MCITY_OFFSET = MCITY_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int MZIP_GROUP_OFFSET = MCITY_OFFSET + MCITY_LENGTH;
    public static final int MZIP_OFFSET = MZIP_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int ERRMSG_GROUP_OFFSET = MZIP_OFFSET + MZIP_LENGTH;
    public static final int ERRMSG_OFFSET = ERRMSG_GROUP_OFFSET + FIELD_METADATA_LENGTH;

    public static final int PAYLOAD_TOTAL_LENGTH = 416;

    public static final int SYMBOLIC_MAP_LENGTH = 575;

    /**
     * Width of the communication area {@code COTRN01C} passes: {@value NavigationContext#COMMAREA_LENGTH}
     * bytes of {@code CARDDEMO-COMMAREA} plus the {@value Ct01Info#RECORD_LENGTH}-byte
     * {@code CDEMO-CT01-INFO} extension the program appends at lines 53-61 =
     * {@link #COMMAREA_TOTAL_LENGTH}.
     */
    public static final int COMMAREA_TOTAL_LENGTH =
            NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH;

    /**
     * The {@value #PAYLOAD_FIELD_COUNT} field names in copybook declaration order.
     */
    public static final List<String> PAYLOAD_FIELD_NAMES = List.of(
            TRNNAME_FIELD, TITLE01_FIELD, CURDATE_FIELD, PGMNAME_FIELD, TITLE02_FIELD,
            CURTIME_FIELD, TRNIDIN_FIELD, TRNID_FIELD, CARDNUM_FIELD, TTYPCD_FIELD,
            TCATCD_FIELD, TRNSRC_FIELD, TDESC_FIELD, TRNAMT_FIELD, TORIGDT_FIELD,
            TPROCDT_FIELD, MID_FIELD, MNAME_FIELD, MCITY_FIELD, MZIP_FIELD, ERRMSG_FIELD);

    private static final List<Integer> PAYLOAD_FIELD_WIDTHS = List.of(
            TRNNAME_LENGTH, TITLE01_LENGTH, CURDATE_LENGTH, PGMNAME_LENGTH, TITLE02_LENGTH,
            CURTIME_LENGTH, TRNIDIN_LENGTH, TRNID_LENGTH, CARDNUM_LENGTH, TTYPCD_LENGTH,
            TCATCD_LENGTH, TRNSRC_LENGTH, TDESC_LENGTH, TRNAMT_LENGTH, TORIGDT_LENGTH,
            TPROCDT_LENGTH, MID_LENGTH, MNAME_LENGTH, MCITY_LENGTH, MZIP_LENGTH, ERRMSG_LENGTH);

    private static final Map<String, Integer> DECLARED_LENGTHS = buildDeclaredLengths();

    static {
        verifyGeometry(PAYLOAD_FIELD_NAMES.size(),
                PAYLOAD_FIELD_WIDTHS.size(),
                sumOf(PAYLOAD_FIELD_WIDTHS),
                ERRMSG_OFFSET + ERRMSG_LENGTH,
                TIOA_PREFIX_LENGTH + PAYLOAD_FIELD_COUNT * FIELD_METADATA_LENGTH
                        + PAYLOAD_TOTAL_LENGTH);
    }

    static int sumOf(List<Integer> widths) {
        Objects.requireNonNull(widths, "A width list is required to total it");
        int total = 0;
        for (Integer width : widths) {
            total += Objects.requireNonNull(width, "A declared width cannot be null");
        }
        return total;
    }

    static void verifyGeometry(int nameCount, int widthCount, int widthSum, int offsetChainEnd,
                               int componentSum) {
        if (nameCount != PAYLOAD_FIELD_COUNT || widthCount != PAYLOAD_FIELD_COUNT) {
            throw new IllegalStateException(SYMBOLIC_MAP_GROUP + " declares "
                    + PAYLOAD_FIELD_COUNT + " payload fields, but this type lists " + nameCount
                    + " name(s) and " + widthCount + " width(s); the two lists are positionally "
                    + "aligned and must both match app/cpy-bms/COTRN01.CPY");
        }
        if (widthSum != PAYLOAD_TOTAL_LENGTH) {
            throw new IllegalStateException("The " + PAYLOAD_FIELD_COUNT + " declared widths of "
                    + SYMBOLIC_MAP_GROUP + " sum to " + widthSum + ", not " + PAYLOAD_TOTAL_LENGTH
                    + "; one width disagrees with its PICTURE clause in app/cpy-bms/COTRN01.CPY or "
                    + "its LENGTH= in app/bms/COTRN01.bms");
        }
        if (offsetChainEnd != SYMBOLIC_MAP_LENGTH || componentSum != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalStateException(SYMBOLIC_MAP_GROUP + " must occupy "
                    + SYMBOLIC_MAP_LENGTH + " bytes, but the offset chain ends at " + offsetChainEnd
                    + " and the component sum is " + componentSum + "; the offset chain and the "
                    + "width table have diverged");
        }
    }

    private static Map<String, Integer> buildDeclaredLengths() {
        Map<String, Integer> lengths = new LinkedHashMap<>();
        for (int i = 0; i < PAYLOAD_FIELD_NAMES.size(); i++) {
            lengths.put(PAYLOAD_FIELD_NAMES.get(i), PAYLOAD_FIELD_WIDTHS.get(i));
        }
        return Collections.unmodifiableMap(lengths);
    }

    /**
     * The COBOL figurative constant {@code SPACES} sized to a field: a run of {@code width} spaces.
     *
     * @param width the field width in characters, zero or more
     * @return a string of exactly {@code width} spaces
     * @throws IllegalArgumentException if {@code width} is negative
     */
    public static String spaces(int width) {
        if (width < 0) {
            throw new IllegalArgumentException("A field cannot be " + width + " characters wide");
        }
        return " ".repeat(width);
    }

    /**
     * The declared width of a payload field, by its verbatim {@code xxxI} name.
     *
     * @param fieldName one of {@link #PAYLOAD_FIELD_NAMES}, for example {@code "CARDNUMI"}
     * @return the declared {@code PICTURE} width
     * @throws NullPointerException if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not one of the {@value #PAYLOAD_FIELD_COUNT}
     *     fields of this map
     */
    public static int declaredLengthOf(String fieldName) {
        Objects.requireNonNull(fieldName, "A field name is required to look up a declared width");
        Integer declared = DECLARED_LENGTHS.get(fieldName);
        if (declared == null) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a field of "
                    + SYMBOLIC_MAP_GROUP + "; the " + PAYLOAD_FIELD_COUNT + " field names are "
                    + PAYLOAD_FIELD_NAMES);
        }
        return declared;
    }

    /**
     * Treats an absent value as COBOL's blank field: a {@code PIC X} item is never null.
     *
     * @param value the value to normalise, possibly {@code null}
     * @return {@code value}, or the empty string when it is {@code null}
     */
    private static String orBlank(String value) {
        return value == null ? "" : value;
    }

    @Size(max = TRNNAME_LENGTH)
    private String trnname = spaces(TRNNAME_LENGTH);

    @Size(max = TITLE01_LENGTH)
    private String title01 = spaces(TITLE01_LENGTH);

    @Size(max = CURDATE_LENGTH)
    private String curdate = spaces(CURDATE_LENGTH);

    @Size(max = PGMNAME_LENGTH)
    private String pgmname = spaces(PGMNAME_LENGTH);

    @Size(max = TITLE02_LENGTH)
    private String title02 = spaces(TITLE02_LENGTH);

    @Size(max = CURTIME_LENGTH)
    private String curtime = spaces(CURTIME_LENGTH);

    @Size(max = TRNIDIN_LENGTH)
    private String trnidin = spaces(TRNIDIN_LENGTH);

    @Size(max = TRNID_LENGTH)
    private String trnid = spaces(TRNID_LENGTH);

    @Size(max = CARDNUM_LENGTH)
    private String cardnum = spaces(CARDNUM_LENGTH);

    @Size(max = TTYPCD_LENGTH)
    private String ttypcd = spaces(TTYPCD_LENGTH);

    @Size(max = TCATCD_LENGTH)
    private String tcatcd = spaces(TCATCD_LENGTH);

    @Size(max = TRNSRC_LENGTH)
    private String trnsrc = spaces(TRNSRC_LENGTH);

    @Size(max = TDESC_LENGTH)
    private String tdesc = spaces(TDESC_LENGTH);

    @Size(max = TRNAMT_LENGTH)
    private String trnamt = spaces(TRNAMT_LENGTH);

    @Size(max = TORIGDT_LENGTH)
    private String torigdt = spaces(TORIGDT_LENGTH);

    @Size(max = TPROCDT_LENGTH)
    private String tprocdt = spaces(TPROCDT_LENGTH);

    @Size(max = MID_LENGTH)
    private String mid = spaces(MID_LENGTH);

    @Size(max = MNAME_LENGTH)
    private String mname = spaces(MNAME_LENGTH);

    @Size(max = MCITY_LENGTH)
    private String mcity = spaces(MCITY_LENGTH);

    @Size(max = MZIP_LENGTH)
    private String mzip = spaces(MZIP_LENGTH);

    @Size(max = ERRMSG_LENGTH)
    private String errmsg = spaces(ERRMSG_LENGTH);

    private NavigationContext navigationContext;

    @Valid
    private Ct01Info ct01Info = new Ct01Info();

    @Size(max = AID_TOKEN_LENGTH)
    private String aid = spaces(AID_TOKEN_LENGTH);

    @JsonIgnore
    private final Map<String, ScreenFieldMetadata> metadata;

    /**
     * Creates a request in the state a freshly initialised COBOL work area would hold.
     */
    public TransactionAddRequest() {
        Map<String, ScreenFieldMetadata> carriers = new LinkedHashMap<>();
        for (String fieldName : PAYLOAD_FIELD_NAMES) {
            carriers.put(fieldName, new ScreenFieldMetadata(
                    screenNameOf(fieldName), declaredLengthOf(fieldName)));
        }
        this.metadata = carriers;
    }

    public TransactionAddRequest(TransactionAddRequest other) {
        Objects.requireNonNull(other, "A source TransactionAddRequest is required to copy one");
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.trnidin = other.trnidin;
        this.trnid = other.trnid;
        this.cardnum = other.cardnum;
        this.ttypcd = other.ttypcd;
        this.tcatcd = other.tcatcd;
        this.trnsrc = other.trnsrc;
        this.tdesc = other.tdesc;
        this.trnamt = other.trnamt;
        this.torigdt = other.torigdt;
        this.tprocdt = other.tprocdt;
        this.mid = other.mid;
        this.mname = other.mname;
        this.mcity = other.mcity;
        this.mzip = other.mzip;
        this.errmsg = other.errmsg;
        this.navigationContext = other.navigationContext;
        this.ct01Info = new Ct01Info(other.ct01Info);
        this.aid = other.aid;
        Map<String, ScreenFieldMetadata> carriers = new LinkedHashMap<>();
        for (Map.Entry<String, ScreenFieldMetadata> entry : other.metadata.entrySet()) {
            carriers.put(entry.getKey(), new ScreenFieldMetadata(entry.getValue()));
        }
        this.metadata = carriers;
    }

    static String screenNameOf(String fieldName) {
        if (!fieldName.endsWith("I")) {
            throw new IllegalStateException("Symbolic map value item '" + fieldName + "' does not "
                    + "end in the directional 'I' suffix, so no DFHMDF label can be derived from it; "
                    + "the field name constants and app/cpy-bms/COTRN01.CPY have diverged");
        }
        return fieldName.substring(0, fieldName.length() - 1);
    }

    public String getTrnname() {
        return trnname;
    }

    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    public String getPgmname() {
        return pgmname;
    }

    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    public String getCurtime() {
        return curtime;
    }

    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    public String getTrnidin() {
        return trnidin;
    }

    public void setTrnidin(String trnidin) {
        this.trnidin = trnidin;
    }

    public String getTrnid() {
        return trnid;
    }

    public void setTrnid(String trnid) {
        this.trnid = trnid;
    }

    public String getCardnum() {
        return cardnum;
    }

    public void setCardnum(String cardnum) {
        this.cardnum = cardnum;
    }

    public String getTtypcd() {
        return ttypcd;
    }

    public void setTtypcd(String ttypcd) {
        this.ttypcd = ttypcd;
    }

    public String getTcatcd() {
        return tcatcd;
    }

    public void setTcatcd(String tcatcd) {
        this.tcatcd = tcatcd;
    }

    public String getTrnsrc() {
        return trnsrc;
    }

    public void setTrnsrc(String trnsrc) {
        this.trnsrc = trnsrc;
    }

    public String getTdesc() {
        return tdesc;
    }

    public void setTdesc(String tdesc) {
        this.tdesc = tdesc;
    }

    public String getTrnamt() {
        return trnamt;
    }

    public void setTrnamt(String trnamt) {
        this.trnamt = trnamt;
    }

    public String getTorigdt() {
        return torigdt;
    }

    public void setTorigdt(String torigdt) {
        this.torigdt = torigdt;
    }

    public String getTprocdt() {
        return tprocdt;
    }

    public void setTprocdt(String tprocdt) {
        this.tprocdt = tprocdt;
    }

    public String getMid() {
        return mid;
    }

    public void setMid(String mid) {
        this.mid = mid;
    }

    public String getMname() {
        return mname;
    }

    public void setMname(String mname) {
        this.mname = mname;
    }

    public String getMcity() {
        return mcity;
    }

    public void setMcity(String mcity) {
        this.mcity = mcity;
    }

    public String getMzip() {
        return mzip;
    }

    public void setMzip(String mzip) {
        this.mzip = mzip;
    }

    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * The communication area carried by this request, or {@code null} when none was passed.
     *
     * @return the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area, or {@code null} for
     *     the {@code EIBCALEN = 0} cold start of {@code app/cbl/COTRN01C.cbl:94}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the communication area, or removes it.
     *
     * @param navigationContext the area to carry, or {@code null} to carry none
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COTRN01C.cbl:94}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@link #COMMAREA_TOTAL_LENGTH} when a communication
     * area travelled with this request, and {@code 0} when none did.
     *
     * @return {@link #COMMAREA_TOTAL_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? COMMAREA_TOTAL_LENGTH : 0;
    }

    public Ct01Info getCt01Info() {
        return ct01Info;
    }

    public void setCt01Info(Ct01Info ct01Info) {
        this.ct01Info = ct01Info == null ? new Ct01Info() : ct01Info;
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    @JsonIgnore
    public ScreenFieldMetadata getMetadata(String fieldName) {
        Objects.requireNonNull(fieldName, "A field name is required to look up its metadata");
        ScreenFieldMetadata carrier = metadata.get(fieldName);
        if (carrier == null) {
            throw new IllegalArgumentException("'" + fieldName + "' is not a field of "
                    + SYMBOLIC_MAP_GROUP + "; the " + PAYLOAD_FIELD_COUNT + " field names are "
                    + PAYLOAD_FIELD_NAMES);
        }
        return carrier;
    }

    /**
     * Every metadata carrier, keyed by verbatim {@code xxxI} field name in declaration order.
     *
     * @return an unmodifiable view; the carriers themselves stay mutable, because
     *     {@code MOVE -1 TO TRNIDINL} has to be expressible
     */
    @JsonIgnore
    public Map<String, ScreenFieldMetadata> getAllMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * Places the cursor in one field: {@code MOVE -1 TO <field>L}.
     *
     * @param fieldName the field to place the cursor in, one of {@link #PAYLOAD_FIELD_NAMES}
     * @throws NullPointerException if {@code fieldName} is {@code null}
     * @throws IllegalArgumentException if {@code fieldName} is not a field of this map
     */
    public void requestCursorAt(String fieldName) {
        getMetadata(fieldName).requestCursor();
    }

    /**
     * The field holding the cursor request, if any.
     *
     * @return the field currently requesting the cursor, or {@code null} when none is
     */
    @JsonIgnore
    public String cursorField() {
        for (Map.Entry<String, ScreenFieldMetadata> entry : metadata.entrySet()) {
            if (entry.getValue().isCursorRequested()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Returns every metadata carrier to its neutral state: length item zero, flag byte a space.
     */
    public void resetMetadata() {
        for (ScreenFieldMetadata carrier : metadata.values()) {
            carrier.reset();
        }
    }

    /**
     * Renders the {@value #PAYLOAD_FIELD_COUNT} payload fields as declared-width images, keyed by verbatim
     * {@code xxxI} name in declaration order.
     *
     * @param codec the codec, carrying the code page explicitly - never the platform default
     * @return a mutable, insertion-ordered map of {@value #PAYLOAD_FIELD_COUNT} entries
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public Map<String, String> toFieldImages(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render " + SYMBOLIC_MAP_GROUP
                + ": the code page of a fixed-width image is always stated explicitly and never "
                + "derived from the platform");
        Map<String, String> images = new LinkedHashMap<>();
        images.put(TRNNAME_FIELD, codec.movePicX(orBlank(trnname), TRNNAME_LENGTH));
        images.put(TITLE01_FIELD, codec.movePicX(orBlank(title01), TITLE01_LENGTH));
        images.put(CURDATE_FIELD, codec.movePicX(orBlank(curdate), CURDATE_LENGTH));
        images.put(PGMNAME_FIELD, codec.movePicX(orBlank(pgmname), PGMNAME_LENGTH));
        images.put(TITLE02_FIELD, codec.movePicX(orBlank(title02), TITLE02_LENGTH));
        images.put(CURTIME_FIELD, codec.movePicX(orBlank(curtime), CURTIME_LENGTH));
        images.put(TRNIDIN_FIELD, codec.movePicX(orBlank(trnidin), TRNIDIN_LENGTH));
        images.put(TRNID_FIELD, codec.movePicX(orBlank(trnid), TRNID_LENGTH));
        images.put(CARDNUM_FIELD, codec.movePicX(orBlank(cardnum), CARDNUM_LENGTH));
        images.put(TTYPCD_FIELD, codec.movePicX(orBlank(ttypcd), TTYPCD_LENGTH));
        images.put(TCATCD_FIELD, codec.movePicX(orBlank(tcatcd), TCATCD_LENGTH));
        images.put(TRNSRC_FIELD, codec.movePicX(orBlank(trnsrc), TRNSRC_LENGTH));
        images.put(TDESC_FIELD, codec.movePicX(orBlank(tdesc), TDESC_LENGTH));
        images.put(TRNAMT_FIELD, codec.movePicX(orBlank(trnamt), TRNAMT_LENGTH));
        images.put(TORIGDT_FIELD, codec.movePicX(orBlank(torigdt), TORIGDT_LENGTH));
        images.put(TPROCDT_FIELD, codec.movePicX(orBlank(tprocdt), TPROCDT_LENGTH));
        images.put(MID_FIELD, codec.movePicX(orBlank(mid), MID_LENGTH));
        images.put(MNAME_FIELD, codec.movePicX(orBlank(mname), MNAME_LENGTH));
        images.put(MCITY_FIELD, codec.movePicX(orBlank(mcity), MCITY_LENGTH));
        images.put(MZIP_FIELD, codec.movePicX(orBlank(mzip), MZIP_LENGTH));
        images.put(ERRMSG_FIELD, codec.movePicX(orBlank(errmsg), ERRMSG_LENGTH));
        return images;
    }

    /**
     * Concatenates the {@value #PAYLOAD_FIELD_COUNT} declared-width field images in declaration order.
     *
     * @param codec the codec, carrying the code page explicitly
     * @return exactly {@value #PAYLOAD_TOTAL_LENGTH} characters
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public String toPayloadImage(FixedWidthCodec codec) {
        StringBuilder image = new StringBuilder(PAYLOAD_TOTAL_LENGTH);
        for (String fieldImage : toFieldImages(codec).values()) {
            image.append(fieldImage);
        }
        return image.toString();
    }

    /**
     * Renders the communication area {@code COTRN01C} actually passes: the standard
     * {@value NavigationContext#COMMAREA_LENGTH}-byte area followed by the
     * {@value Ct01Info#RECORD_LENGTH}-byte {@code CDEMO-CT01-INFO} extension.
     *
     * @param codec the codec, carrying the code page explicitly
     * @return exactly {@link #COMMAREA_TOTAL_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     * @throws IllegalStateException if no communication area travelled with this request
     */
    public byte[] toCommareaImage(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A FixedWidthCodec is required to render the "
                + COMMAREA_TOTAL_LENGTH + "-byte communication area");
        if (!hasNavigationContext()) {
            throw new IllegalStateException("No communication area travelled with this request, so "
                    + "there are no " + COMMAREA_TOTAL_LENGTH + " bytes to render: EIBCALEN is 0, "
                    + "which is the cold start COTRN01C.cbl:94 tests for and answers by transferring "
                    + "to COSGN00C. Test hasNavigationContext() first, or set one with "
                    + "setNavigationContext");
        }
        byte[] standard = navigationContext.toFixedWidth(codec);
        byte[] extension = ct01Info.toFixedWidth(codec);
        byte[] combined = new byte[standard.length + extension.length];
        System.arraycopy(standard, 0, combined, 0, standard.length);
        System.arraycopy(extension, 0, combined, standard.length, extension.length);
        return combined;
    }

    private List<Object> stateValues() {
        return Arrays.asList(trnname, title01, curdate, pgmname, title02, curtime,
                trnidin, trnid, cardnum, ttypcd, tcatcd, trnsrc, tdesc, trnamt, torigdt, tprocdt,
                mid, mname, mcity, mzip, errmsg, navigationContext, ct01Info, aid, metadata);
    }

    /**
     * Value equality across all {@value #PAYLOAD_FIELD_COUNT} payload fields, the communication area, the
     * {@code CDEMO-CT01-INFO} extension, the attention identifier and every metadata carrier.
     *
     * @param other the object to compare against
     * @return {@code true} when every piece of state matches
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionAddRequest that)) {
            return false;
        }
        return stateValues().equals(that.stateValues());
    }

    @Override
    public int hashCode() {
        return stateValues().hashCode();
    }

    /**
     * A short diagnostic summary naming the screen and the identifying fields.
     */
    @Override
    public String toString() {
        return "TransactionAddRequest[" + TRANSACTION_ID + "/" + PROGRAM_NAME
                + ", map=" + MAPSET_NAME + "." + MAP_NAME
                + ", " + TRNIDIN_FIELD + "='" + trnidin + "'"
                + ", " + TRNID_FIELD + "='" + trnid + "'"
                + ", aid='" + aid + "'"
                + ", pgmContext=" + (hasNavigationContext()
                        ? String.valueOf(navigationContext.pgmContext())
                        : "none (EIBCALEN=0)")
                + ", " + Ct01Info.TRN_SELECTED_FIELD + "='" + ct01Info.getTrnSelected() + "']";
    }

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} items belonging to one screen field.
     *
     * <p>{@code xxxL} is declared {@code COMP PIC S9(4)} - a signed binary halfword - and is held here as a
     * {@code short} for exactly that reason.
     */
    public static final class ScreenFieldMetadata {
        /**
         * Neutral content of a one-byte {@code PICTURE X} item: a single space.
         */
        public static final String UNSET_BYTE = " ";

        private final String screenName;

        private final int declaredLength;

        private short lengthItem;

        private String flagByte = UNSET_BYTE;

        /**
         * Creates the metadata carrier for one screen field.
         *
         * @param screenName the {@code DFHMDF} label, not blank
         * @param declaredLength the companion {@code xxxI} item's declared width, at least 1
         * @throws NullPointerException if {@code screenName} is {@code null}
         * @throws IllegalArgumentException if {@code screenName} is blank or {@code declaredLength} is
         *     below 1
         */
        public ScreenFieldMetadata(String screenName, int declaredLength) {
            Objects.requireNonNull(screenName, "A screen field's DFHMDF label is required");
            if (screenName.isBlank()) {
                throw new IllegalArgumentException("A screen field's DFHMDF label cannot be blank; "
                        + "it names the field the L, F and A items belong to");
            }
            if (declaredLength < 1) {
                throw new IllegalArgumentException("Screen field '" + screenName + "' cannot declare "
                        + "a width of " + declaredLength + "; a DFHMDF field occupies at least "
                        + "1 byte");
            }
            this.screenName = screenName;
            this.declaredLength = declaredLength;
        }

        /**
         * Copy constructor, so a request can be defensively copied without sharing metadata.
         *
         * @param other the carrier to copy, not {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public ScreenFieldMetadata(ScreenFieldMetadata other) {
            Objects.requireNonNull(other, "A source ScreenFieldMetadata is required to copy one");
            this.screenName = other.screenName;
            this.declaredLength = other.declaredLength;
            this.lengthItem = other.lengthItem;
            this.flagByte = other.flagByte;
        }

        public String getScreenName() {
            return screenName;
        }

        public int getDeclaredLength() {
            return declaredLength;
        }

        public String lengthItemName() {
            return screenName + "L";
        }

        public String flagItemName() {
            return screenName + "F";
        }

        public String attributeItemName() {
            return screenName + "A";
        }

        /**
         * Name of the {@code xxxI} value item - the payload field.
         *
         * @return the verbatim symbolic-map name, for example {@code TRNNAMEI}
         */
        public String inputItemName() {
            return screenName + "I";
        }

        public short getLengthItem() {
            return lengthItem;
        }

        public void setLengthItem(short lengthItem) {
            this.lengthItem = lengthItem;
        }

        /**
         * Sets the length item to {@link #CURSOR_REQUEST}, the {@code MOVE -1} idiom.
         */
        public void requestCursor() {
            this.lengthItem = CURSOR_REQUEST;
        }

        public boolean isCursorRequested() {
            return lengthItem == CURSOR_REQUEST;
        }

        /**
         * Whether the terminal sent input for this field.
         *
         * @return {@code true} when the terminal sent at least one character for this field
         */
        public boolean hasInput() {
            return lengthItem > 0;
        }

        public String getFlagItem() {
            return flagByte;
        }

        /**
         * Sets the {@code xxxF} flag byte, and therefore the {@code xxxA} attribute byte with it.
         *
         * @param flagItem the one-character value; {@code null} is normalised to a space, because a COBOL
         *     {@code PICTURE X} item has no null state
         * @throws IllegalArgumentException if more than one character is supplied - the item is one byte
         *     wide and silently dropping the surplus would hide a defect
         */
        public void setFlagItem(String flagItem) {
            this.flagByte = requireSingleByte(flagItem, flagItemName());
        }

        public String getAttributeItem() {
            return flagByte;
        }

        /**
         * Sets the {@code xxxA} attribute byte, and therefore the {@code xxxF} flag byte with it.
         *
         * @param attributeItem the one-character attribute; {@code null} is normalised to a space
         * @throws IllegalArgumentException if more than one character is supplied
         */
        public void setAttributeItem(String attributeItem) {
            this.flagByte = requireSingleByte(attributeItem, attributeItemName());
        }

        /**
         * Restores the neutral state: length item zero, flag and attribute byte a space.
         */
        public void reset() {
            this.lengthItem = 0;
            this.flagByte = UNSET_BYTE;
        }

        private static String requireSingleByte(String value, String itemName) {
            if (value == null) {
                return UNSET_BYTE;
            }
            if (value.length() > FLAG_ITEM_LENGTH) {
                throw new IllegalArgumentException("Item " + itemName + " is PICTURE X, one byte "
                        + "wide, but was given " + value.length() + " character(s): '" + value
                        + "'. It cannot hold the surplus");
            }
            return value.isEmpty() ? UNSET_BYTE : value;
        }

        private List<Object> stateValues() {
            return Arrays.asList(screenName, declaredLength, lengthItem, flagByte);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ScreenFieldMetadata that)) {
                return false;
            }
            return stateValues().equals(that.stateValues());
        }

        @Override
        public int hashCode() {
            return stateValues().hashCode();
        }

        @Override
        public String toString() {
            return "ScreenFieldMetadata[" + screenName + ", declaredLength=" + declaredLength
                    + ", " + lengthItemName() + "=" + lengthItem
                    + ", " + flagItemName() + "='" + flagByte + "']";
        }
    }

    /**
     * The {@code 05 CDEMO-CT01-INFO} group that {@code COTRN01C} appends to the communication area.
     */
    public static final class Ct01Info {
        /**
         * {@code CDEMO-CT01-TRNID-FIRST}, {@code COTRN01C} line 54.
         */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT01-TRNID-FIRST";

        /**
         * {@code CDEMO-CT01-TRNID-LAST}, {@code COTRN01C} line 55.
         */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT01-TRNID-LAST";

        /**
         * {@code CDEMO-CT01-PAGE-NUM}, {@code COTRN01C} line 56.
         */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT01-PAGE-NUM";

        /**
         * {@code CDEMO-CT01-NEXT-PAGE-FLG}, {@code COTRN01C} line 57.
         */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT01-NEXT-PAGE-FLG";

        /**
         * {@code CDEMO-CT01-TRN-SEL-FLG}, {@code COTRN01C} line 60.
         */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT01-TRN-SEL-FLG";

        /**
         * {@code CDEMO-CT01-TRN-SELECTED}, {@code COTRN01C} line 61.
         */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT01-TRN-SELECTED";

        /**
         * {@code PIC X(16)}.
         */
        public static final int TRNID_FIRST_LENGTH = 16;

        /**
         * {@code PIC X(16)}.
         */
        public static final int TRNID_LAST_LENGTH = 16;

        /**
         * {@code PIC 9(08)} - eight unsigned digits, so no sign position and no scale.
         */
        public static final int PAGE_NUM_LENGTH = 8;

        /**
         * {@code PIC X(01)}.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * {@code PIC X(01)}.
         */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /**
         * {@code PIC X(16)}.
         */
        public static final int TRN_SELECTED_LENGTH = 16;

        /**
         * Offset of {@code CDEMO-CT01-TRNID-FIRST} within the extension.
         */
        public static final int TRNID_FIRST_OFFSET = 0;
        /**
         * Offset of {@code CDEMO-CT01-TRNID-LAST}.
         */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;
        /**
         * Offset of {@code CDEMO-CT01-PAGE-NUM}.
         */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;
        /**
         * Offset of {@code CDEMO-CT01-NEXT-PAGE-FLG}.
         */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;
        /**
         * Offset of {@code CDEMO-CT01-TRN-SEL-FLG}.
         */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;
        /**
         * Offset of {@code CDEMO-CT01-TRN-SELECTED}.
         */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        public static final int RECORD_LENGTH = 58;

        /**
         * The largest value {@code PIC 9(08)} can hold: eight nines.
         */
        public static final int PAGE_NUM_MAX = 99_999_999;

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}.
         */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'} - and the group's {@code VALUE 'N'} initial state.
         */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * Byte geometry of the extension, every span named and contiguous from offset zero.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(RECORD_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(
                        NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET, NEXT_PAGE_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(
                        TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET, TRN_SELECTED_LENGTH));

        @Size(max = TRNID_FIRST_LENGTH)
        private String trnidFirst = spaces(TRNID_FIRST_LENGTH);

        @Size(max = TRNID_LAST_LENGTH)
        private String trnidLast = spaces(TRNID_LAST_LENGTH);

        @Min(0)
        @Max(PAGE_NUM_MAX)
        private int pageNum;

        @Size(max = NEXT_PAGE_FLG_LENGTH)
        private String nextPageFlg = NEXT_PAGE_NO;

        @Size(max = TRN_SEL_FLG_LENGTH)
        private String trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);

        @Size(max = TRN_SELECTED_LENGTH)
        private String trnSelected = spaces(TRN_SELECTED_LENGTH);

        /**
         * Creates the extension in its declared initial state: character fields blank at their declared
         * widths, page number zero, and the next-page flag {@code "N"} exactly as
         * {@code PIC X(01) VALUE 'N'} specifies.
         */
        public Ct01Info() {
        }

        /**
         * Copy constructor, so an enclosing request can be deep-copied.
         *
         * @param other the extension to copy, not {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public Ct01Info(Ct01Info other) {
            Objects.requireNonNull(other, "A source Ct01Info is required to copy one");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * The {@code CDEMO-CT01-TRNID-FIRST} field.
         *
         * @return {@code CDEMO-CT01-TRNID-FIRST}, the first transaction id on the current page
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Stores {@code CDEMO-CT01-TRNID-FIRST}, unchanged.
         *
         * @param trnidFirst the value to store; {@code null} is kept as {@code null} and rendered blank
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = trnidFirst;
        }

        /**
         * The {@code CDEMO-CT01-TRNID-LAST} field.
         *
         * @return {@code CDEMO-CT01-TRNID-LAST}, the last transaction id on the current page
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Stores {@code CDEMO-CT01-TRNID-LAST}, unchanged.
         *
         * @param trnidLast the value to store; {@code null} is kept as {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = trnidLast;
        }

        /**
         * The {@code CDEMO-CT01-PAGE-NUM} field.
         *
         * @return {@code CDEMO-CT01-PAGE-NUM}
         */
        public int getPageNum() {
            return pageNum;
        }

        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(08), an unsigned "
                        + "picture with no sign position, so it cannot hold " + pageNum);
            }
            if (pageNum > PAGE_NUM_MAX) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(08) and holds at most "
                        + PAGE_NUM_MAX + ", so it cannot hold " + pageNum
                        + "; storing it would silently lose the high-order digit(s)");
            }
            this.pageNum = pageNum;
        }

        /**
         * The {@code CDEMO-CT01-NEXT-PAGE-FLG} field.
         *
         * @return {@code CDEMO-CT01-NEXT-PAGE-FLG}; {@code "N"} until something sets it otherwise
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Stores {@code CDEMO-CT01-NEXT-PAGE-FLG}, unchanged.
         *
         * @param nextPageFlg the flag to store; {@code null} is kept as {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg = nextPageFlg;
        }

        /**
         * Condition name {@code NEXT-PAGE-YES}.
         *
         * @return {@code true} when the flag holds {@code 'Y'} - condition name {@code NEXT-PAGE-YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * Condition name {@code NEXT-PAGE-NO}.
         *
         * @return {@code true} when the flag holds {@code 'N'} - condition name {@code NEXT-PAGE-NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /**
         * {@code SET NEXT-PAGE-YES TO TRUE} - stores {@code 'Y'}.
         */
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /**
         * {@code SET NEXT-PAGE-NO TO TRUE} - stores {@code 'N'}.
         */
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * The {@code CDEMO-CT01-TRN-SEL-FLG} field.
         *
         * @return {@code CDEMO-CT01-TRN-SEL-FLG}, the selection indicator carried from the list
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Stores {@code CDEMO-CT01-TRN-SEL-FLG}, unchanged.
         *
         * @param trnSelFlg the value to store; {@code null} is kept as {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = trnSelFlg;
        }

        /**
         * The {@code CDEMO-CT01-TRN-SELECTED} item.
         *
         * @return {@code CDEMO-CT01-TRN-SELECTED}, the transaction id chosen on the list screen
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Stores {@code CDEMO-CT01-TRN-SELECTED}, unchanged.
         *
         * @param trnSelected the value to store; {@code null} is kept as {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = trnSelected;
        }

        /**
         * Whether a transaction was selected on the list screen.
         *
         * @return {@code true} when a selection was carried in, mirroring {@code COTRN01C} line 103's
         *     {@code IF CDEMO-CT01-TRN-SELECTED NOT = SPACES AND LOW-VALUES}
         */
        @JsonIgnore
        public boolean hasSelection() {
            if (trnSelected == null || trnSelected.isEmpty()) {
                return false;
            }
            for (int i = 0; i < trnSelected.length(); i++) {
                char character = trnSelected.charAt(i);
                if (character != ' ' && character != '\0') {
                    return true;
                }
            }
            return false;
        }

        /**
         * Renders the extension as exactly {@value #RECORD_LENGTH} bytes.
         *
         * @param codec the codec, carrying the code page explicitly - never the platform default
         * @return exactly {@value #RECORD_LENGTH} bytes in the codec's code page
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] toFixedWidth(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to render "
                    + "CDEMO-CT01-INFO: the code page of a fixed-width image is always stated "
                    + "explicitly and never derived from the platform");
            Map<String, String> images = new LinkedHashMap<>();
            images.put(TRNID_FIRST_FIELD, codec.movePicX(orBlank(trnidFirst), TRNID_FIRST_LENGTH));
            images.put(TRNID_LAST_FIELD, codec.movePicX(orBlank(trnidLast), TRNID_LAST_LENGTH));
            images.put(PAGE_NUM_FIELD, codec.movePic9(pageNum, PAGE_NUM_LENGTH));
            images.put(NEXT_PAGE_FLG_FIELD,
                    codec.movePicX(orBlank(nextPageFlg), NEXT_PAGE_FLG_LENGTH));
            images.put(TRN_SEL_FLG_FIELD, codec.movePicX(orBlank(trnSelFlg), TRN_SEL_FLG_LENGTH));
            images.put(TRN_SELECTED_FIELD,
                    codec.movePicX(orBlank(trnSelected), TRN_SELECTED_LENGTH));
            return codec.serialise(LAYOUT, images);
        }

        /**
         * Reads a {@value #RECORD_LENGTH}-byte image back into an extension.
         *
         * @param codec the codec, carrying the code page explicitly
         * @param image exactly {@value #RECORD_LENGTH} bytes
         * @return the extension the image denotes, never {@code null}
         * @throws NullPointerException if {@code codec} or {@code image} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@value #RECORD_LENGTH} bytes,
         *     or the page-number span does not hold digits
         */
        public static Ct01Info fromFixedWidth(FixedWidthCodec codec, byte[] image) {
            Objects.requireNonNull(codec, "A FixedWidthCodec is required to read CDEMO-CT01-INFO: "
                    + "the code page of a fixed-width image is always stated explicitly");
            Objects.requireNonNull(image, "A " + RECORD_LENGTH + "-byte image is required to read "
                    + "CDEMO-CT01-INFO; use the no-argument constructor for a fresh extension");
            Map<String, String> images = codec.deserialise(LAYOUT, image);
            Ct01Info info = new Ct01Info();
            info.trnidFirst = images.get(TRNID_FIRST_FIELD);
            info.trnidLast = images.get(TRNID_LAST_FIELD);
            info.pageNum = codec.decodePic9AsInt(images.get(PAGE_NUM_FIELD));
            info.nextPageFlg = images.get(NEXT_PAGE_FLG_FIELD);
            info.trnSelFlg = images.get(TRN_SEL_FLG_FIELD);
            info.trnSelected = images.get(TRN_SELECTED_FIELD);
            return info;
        }

        private List<Object> stateValues() {
            return Arrays.asList(
                    trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg, trnSelected);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ct01Info that)) {
                return false;
            }
            return stateValues().equals(that.stateValues());
        }

        @Override
        public int hashCode() {
            return stateValues().hashCode();
        }

        @Override
        public String toString() {
            return "Ct01Info[trnidFirst='" + trnidFirst + "', trnidLast='" + trnidLast
                    + "', pageNum=" + pageNum + ", nextPageFlg='" + nextPageFlg
                    + "', trnSelFlg='" + trnSelFlg + "', trnSelected='" + trnSelected + "']";
        }
    }
}
