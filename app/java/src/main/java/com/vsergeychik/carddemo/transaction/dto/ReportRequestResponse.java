package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload of {@code POST /api/reports} - CSD transaction {@code CR00}, program
 * {@code app/cbl/CORPT00C.cbl} - projected field for field from the {@code xxxO} items of
 * {@code 01 CORPT0AO REDEFINES CORPT0AI} in {@code app/cpy-bms/CORPT00.CPY} (line 121) and their
 * name-labelled {@code DFHMDF} definitions in {@code app/bms/CORPT00.bms}.
 *
 * <p>Only labelled fields become payload members; the 25 unlabelled ones are screen literals such as
 * {@code 'Tran:'} and {@code '(MM/DD/YYYY)'} that CICS never transmits.
 */
public final class ReportRequestResponse {
    /**
     * The CSD transaction that reaches this screen: {@code CR00}, defined at
     * {@code app/csd/CARDDEMO.CSD:409-410} as {@code TRANSACTION(CR00) ... PROGRAM(CORPT00C)}.
     */
    public static final String TRANSACTION_ID = "CR00";

    /**
     * The COBOL program this screen belongs to, {@code CORPT00C.cbl:37}
     * {@code WS-PGMNAME PIC X(08) VALUE 'CORPT00C'}, moved to {@code PGMNAMEO} at line 616.
     */
    public static final String PROGRAM_NAME = "CORPT00C";

    /**
     * The BMS mapset, {@code app/bms/CORPT00.bms:19} {@code CORPT00 DFHMSD}.
     */
    public static final String MAPSET_NAME = "CORPT00";

    /**
     * The BMS map, {@code app/bms/CORPT00.bms:26} {@code CORPT0A DFHMDI}.
     */
    public static final String MAP_NAME = "CORPT0A";

    /**
     * The outbound symbolic-map group this type projects, {@code app/cpy-bms/CORPT00.CPY:121}
     * {@code 01 CORPT0AO REDEFINES CORPT0AI}.
     */
    public static final String SYMBOLIC_MAP_GROUP = "CORPT0AO";

    /**
     * The program {@code CORPT00C.cbl:542-543} falls back to when {@code CDEMO-TO-PROGRAM} arrives as
     * {@code LOW-VALUES} or {@code SPACES}:
     * {@code IF CDEMO-TO-PROGRAM = LOW-VALUES OR SPACES / MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM}.
     */
    public static final String DEFAULT_NEXT_PROGRAM = "COSGN00C";

    /**
     * The program {@code CORPT00C.cbl:190-191} transfers to on {@code DFHPF3}:
     * {@code WHEN DFHPF3 / MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM}.
     */
    public static final String PF3_NEXT_PROGRAM = "COMEN01C";

    /**
     * Screen fields with a {@code DFHMDF} label, and therefore payload members: seventeen.
     */
    public static final int SCREEN_FIELD_COUNT = 17;

    /**
     * The unnamed {@code 02 FILLER PIC X(12)} that opens both groups, {@code app/cpy-bms/CORPT00.CPY:122}.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that opens each field's outbound prefix.
     */
    public static final int ATTRIBUTE_FILLER_LENGTH = 3;

    /**
     * The {@code xxxC} colour item: {@code PICTURE X}, one byte.
     */
    public static final int COLOUR_ITEM_LENGTH = 1;

    /**
     * The {@code xxxP} programmed-symbols item: {@code PICTURE X}, one byte.
     */
    public static final int PS_ITEM_LENGTH = 1;

    /**
     * The {@code xxxH} highlight item: {@code PICTURE X}, one byte.
     */
    public static final int HILIGHT_ITEM_LENGTH = 1;

    /**
     * The {@code xxxV} validation item: {@code PICTURE X}, one byte.
     */
    public static final int VALIDN_ITEM_LENGTH = 1;

    public static final int FIELD_PREFIX_LENGTH =
            ATTRIBUTE_FILLER_LENGTH + COLOUR_ITEM_LENGTH + PS_ITEM_LENGTH + HILIGHT_ITEM_LENGTH
                    + VALIDN_ITEM_LENGTH;

    public static final int ATTRIBUTE_PREFIX_TOTAL = SCREEN_FIELD_COUNT * FIELD_PREFIX_LENGTH;

    public static final int PAYLOAD_WIDTH_TOTAL = 206;

    public static final int RECORD_LENGTH =
            TIOAPFX_PREFIX_LENGTH + ATTRIBUTE_PREFIX_TOTAL + PAYLOAD_WIDTH_TOTAL;

    /**
     * The one byte a BMS attribute item holds when the program has not set it.
     */
    public static final byte ATTRIBUTE_UNSET = BmsAttributes.DFHDFCOL;

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static final String SPACE = " ";

    // The LOW-VALUES fill is ScreenFieldImage.LOW_VALUE and ScreenFieldImage.unpainted(int); it is not
    // redeclared here, so this screen cannot drift away from the other sixteen.

    /**
     * One of the seventeen name-labelled {@code DFHMDF} fields of mapset {@code CORPT00}, carrying the
     * geometry of both its payload item and its four attribute items.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAMEO PIC X(4)}, .CPY line 128; BMS {@code LENGTH=4 POS=(1,7) COLOR=BLUE}.
         */
        TRNNAME("TRNNAME", 4, TIOAPFX_PREFIX_LENGTH),

        /**
         * {@code TITLE01O PIC X(40)}, .CPY line 134; BMS {@code LENGTH=40 POS=(1,21) COLOR=YELLOW}.
         */
        TITLE01("TITLE01", 40, 23),

        /**
         * {@code CURDATEO PIC X(8)}, .CPY line 140; BMS
         * {@code LENGTH=8 POS=(1,71) COLOR=BLUE INITIAL='mm/dd/yy'}.
         */
        CURDATE("CURDATE", 8, 70),

        /**
         * {@code PGMNAMEO PIC X(8)}, .CPY line 146; BMS {@code LENGTH=8 POS=(2,7) COLOR=BLUE}.
         */
        PGMNAME("PGMNAME", 8, 85),

        /**
         * {@code TITLE02O PIC X(40)}, .CPY line 152; BMS {@code LENGTH=40 POS=(2,21) COLOR=YELLOW}.
         */
        TITLE02("TITLE02", 40, 100),

        /**
         * {@code CURTIMEO PIC X(8)}, .CPY line 158; BMS
         * {@code LENGTH=8 POS=(2,71) COLOR=BLUE INITIAL='hh:mm:ss'}.
         */
        CURTIME("CURTIME", 8, 147),

        /**
         * {@code MONTHLYO PIC X(1)}, .CPY line 164; BMS
         * {@code LENGTH=1 POS=(7,10) COLOR=GREEN HILIGHT=UNDERLINE ATTRB=(FSET,IC,NORM,UNPROT)}.
         */
        MONTHLY("MONTHLY", 1, 162),

        /**
         * {@code YEARLYO PIC X(1)}, .CPY line 170; BMS
         * {@code LENGTH=1 POS=(9,10) COLOR=GREEN HILIGHT=UNDERLINE}.
         */
        YEARLY("YEARLY", 1, 170),

        /**
         * {@code CUSTOMO PIC X(1)}, .CPY line 176; BMS
         * {@code LENGTH=1 POS=(11,10) COLOR=GREEN HILIGHT=UNDERLINE}.
         */
        CUSTOM("CUSTOM", 1, 178),

        /**
         * {@code SDTMMO PIC X(2)}, .CPY line 182; BMS
         * {@code LENGTH=2 POS=(13,29) COLOR=GREEN ATTRB=(FSET,NORM,NUM,UNPROT)}.
         */
        SDTMM("SDTMM", 2, 186),

        /**
         * {@code SDTDDO PIC X(2)}, .CPY line 188; BMS {@code LENGTH=2 POS=(13,34)}.
         */
        SDTDD("SDTDD", 2, 195),

        /**
         * {@code SDTYYYYO PIC X(4)}, .CPY line 194; BMS {@code LENGTH=4 POS=(13,39)}.
         */
        SDTYYYY("SDTYYYY", 4, 204),

        /**
         * {@code EDTMMO PIC X(2)}, .CPY line 200; BMS {@code LENGTH=2 POS=(14,29)}.
         */
        EDTMM("EDTMM", 2, 215),

        /**
         * {@code EDTDDO PIC X(2)}, .CPY line 206; BMS {@code LENGTH=2 POS=(14,34)}.
         */
        EDTDD("EDTDD", 2, 224),

        /**
         * {@code EDTYYYYO PIC X(4)}, .CPY line 212; BMS {@code LENGTH=4 POS=(14,39)}.
         */
        EDTYYYY("EDTYYYY", 4, 233),

        /**
         * {@code CONFIRMO PIC X(1)}, .CPY line 218; BMS
         * {@code LENGTH=1 POS=(19,66) COLOR=GREEN HILIGHT=UNDERLINE}.
         */
        CONFIRM("CONFIRM", 1, 244),

        /**
         * {@code ERRMSGO PIC X(78)}, .CPY line 224; BMS
         * {@code LENGTH=78 POS=(23,1) ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
         */
        ERRMSG("ERRMSG", 78, 252);

        private final String baseName;

        private final int payloadLength;

        private final int attributePrefixOffset;

        private final FieldSpan attributeFillerSpan;
        private final FieldSpan colourSpan;
        private final FieldSpan psSpan;
        private final FieldSpan hilightSpan;
        private final FieldSpan validnSpan;
        private final FieldSpan payloadSpan;

        ScreenField(String baseName, int payloadLength, int attributePrefixOffset) {
            this.baseName = baseName;
            this.payloadLength = payloadLength;
            this.attributePrefixOffset = attributePrefixOffset;

            int cursor = attributePrefixOffset;
            this.attributeFillerSpan = FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH);
            cursor += ATTRIBUTE_FILLER_LENGTH;
            this.colourSpan = FieldSpan.alphanumeric(baseName + "C", cursor, COLOUR_ITEM_LENGTH);
            cursor += COLOUR_ITEM_LENGTH;
            this.psSpan = FieldSpan.alphanumeric(baseName + "P", cursor, PS_ITEM_LENGTH);
            cursor += PS_ITEM_LENGTH;
            this.hilightSpan = FieldSpan.alphanumeric(baseName + "H", cursor, HILIGHT_ITEM_LENGTH);
            cursor += HILIGHT_ITEM_LENGTH;
            this.validnSpan = FieldSpan.alphanumeric(baseName + "V", cursor, VALIDN_ITEM_LENGTH);
            cursor += VALIDN_ITEM_LENGTH;
            this.payloadSpan = FieldSpan.alphanumeric(baseName + "O", cursor, payloadLength);
        }

        /**
         * The {@code DFHMDF} label this field is known by, for example {@code "ERRMSG"}.
         *
         * @return the label, verbatim and never {@code null}
         */
        public String baseName() {
            return baseName;
        }

        /**
         * The copybook name of the payload item, for example {@code "ERRMSGO"}.
         *
         * @return the {@code xxxO} item name
         */
        public String payloadItemName() {
            return baseName + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The copybook name of the colour item, for example {@code "ERRMSGC"} - the item
         * {@code CORPT00C.cbl:448} writes {@code DFHGREEN} into and the item {@code CSSETATY} writes
         * {@code DFHRED} into.
         *
         * @return the {@code xxxC} item name
         */
        public String colourItemName() {
            return baseName + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The declared {@code PICTURE} width of the payload item, in bytes.
         *
         * @return the width, always at least 1
         */
        public int payloadLength() {
            return payloadLength;
        }

        /**
         * Absolute 0-based offset of the payload item within the 337-byte group image.
         *
         * @return the offset
         */
        public int payloadOffset() {
            return payloadSpan.offset();
        }

        /**
         * Absolute 0-based offset of the seven-byte attribute prefix within the group image.
         *
         * @return the offset
         */
        public int attributePrefixOffset() {
            return attributePrefixOffset;
        }

        /**
         * The payload item's span, for reading and writing the field by absolute offset.
         *
         * @return the {@code xxxO} span
         */
        public FieldSpan payloadSpan() {
            return payloadSpan;
        }

        /**
         * The colour item's span - the {@code (SCRNVAR2)C} target of {@code CSSETATY} line 22.
         *
         * @return the {@code xxxC} span
         */
        public FieldSpan colourSpan() {
            return colourSpan;
        }

        public FieldSpan psSpan() {
            return psSpan;
        }

        public FieldSpan hilightSpan() {
            return hilightSpan;
        }

        public FieldSpan validnSpan() {
            return validnSpan;
        }

        /**
         * The unnamed three-byte {@code FILLER} that opens this field's prefix.
         *
         * @return the {@code FILLER} span
         */
        public FieldSpan attributeFillerSpan() {
            return attributeFillerSpan;
        }

        /**
         * All six spans this field owns, in copybook order: {@code FILLER}, {@code xxxC}, {@code xxxP},
         * {@code xxxH}, {@code xxxV}, {@code xxxO}.
         *
         * @return an immutable list of six spans
         */
        public List<FieldSpan> spans() {
            return List.of(attributeFillerSpan, colourSpan, psSpan, hilightSpan, validnSpan,
                    payloadSpan);
        }
    }

    /**
     * The unnamed twelve-byte {@code FILLER} at {@code app/cpy-bms/CORPT00.CPY:122}, reserved by
     * {@code TIOAPFX=YES}.
     */
    public static final FieldSpan TIOAPFX_PREFIX_SPAN = FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH);

    /**
     * Every span of {@code 01 CORPT0AO}, in copybook order: the twelve-byte {@code TIOAPFX} prefix, then
     * for each of the seventeen fields its three-byte {@code FILLER}, its {@code xxxC}, {@code xxxP},
     * {@code xxxH} and {@code xxxV} attribute items and its {@code xxxO} payload item.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + SCREEN_FIELD_COUNT * 6);
        spans.add(TIOAPFX_PREFIX_SPAN);
        for (ScreenField field : ScreenField.values()) {
            spans.addAll(field.spans());
        }
        return new RecordLayout(RECORD_LENGTH, spans);
    }

    /**
     * The {@code xxxC}/{@code xxxP}/{@code xxxH}/{@code xxxV} quad of one screen field: the colour,
     * programmed-symbols, highlight and validation bytes BMS transmits ahead of the field's data.
     *
     * @param colour the {@code xxxC} item - the {@code CSSETATY} and {@code CORPT00C.cbl:448} target
     * @param ps the {@code xxxP} programmed-symbols item
     * @param hilight the {@code xxxH} highlight item, for example {@link BmsAttributes#DFHUNDLN}
     * @param validn the {@code xxxV} validation item
     */
    public record FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
        /**
         * The quad as {@code MOVE LOW-VALUES TO CORPT0AO} ({@code CORPT00C.cbl:179}) leaves it: all four
         * bytes {@code X'00'}, which CICS reads as "use the attribute the map already declares".
         */
        public static final FieldAttributes UNSET =
                new FieldAttributes(ATTRIBUTE_UNSET, ATTRIBUTE_UNSET, ATTRIBUTE_UNSET,
                        ATTRIBUTE_UNSET);

        /**
         * This quad with a different colour item, leaving the other three untouched.
         *
         * @param value the new {@code xxxC} byte
         * @return a new quad
         */
        public FieldAttributes withColour(byte value) {
            return new FieldAttributes(value, ps, hilight, validn);
        }

        /**
         * This quad with a different programmed-symbols item.
         *
         * @param value the new {@code xxxP} byte
         * @return a new quad
         */
        public FieldAttributes withPs(byte value) {
            return new FieldAttributes(colour, value, hilight, validn);
        }

        public FieldAttributes withHilight(byte value) {
            return new FieldAttributes(colour, ps, value, validn);
        }

        public FieldAttributes withValidn(byte value) {
            return new FieldAttributes(colour, ps, hilight, value);
        }

        /**
         * Whether all four items are still {@link #ATTRIBUTE_UNSET}, that is whether the program has left
         * this field's attributes as {@code LOW-VALUES}.
         *
         * @return {@code true} when nothing has been set
         */
        public boolean allUnset() {
            return colour == ATTRIBUTE_UNSET && ps == ATTRIBUTE_UNSET && hilight == ATTRIBUTE_UNSET
                    && validn == ATTRIBUTE_UNSET;
        }

        /**
         * A diagnostic rendering that names each byte by its CICS mnemonic where one exists, so a failing
         * comparison reads as {@code DFHRED} rather than as {@code -14}.
         *
         * @return a human-readable description; never {@code null}
         */
        public String describe() {
            return "C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn);
        }
    }

    private String trnnameo;

    private String title01o;

    private String curdateo;

    private String pgmnameo;

    private String title02o;

    private String curtimeo;

    private String monthlyo;

    private String yearlyo;

    private String customo;

    private String sdtmmo;

    private String sdtddo;

    private String sdtyyyyo;

    private String edtmmo;

    private String edtddo;

    private String edtyyyyo;

    private String confirmo;

    private String errmsgo;

    private final EnumMap<ScreenField, FieldAttributes> attributes =
            new EnumMap<>(ScreenField.class);

    private String nextProgram;

    private String nextMapset;

    private String nextMap;

    private NavigationContext navigationContext;

    /**
     * A response with every field carrying the unpainted image at its declared width, every attribute quad
     * {@link FieldAttributes#UNSET}, and an empty commarea.
     */
    public ReportRequestResponse() {
        for (ScreenField field : ScreenField.values()) {
            attributes.put(field, FieldAttributes.UNSET);
            setPayloadValue(field, ScreenFieldImage.unpainted(field.payloadLength()));
        }

        this.nextProgram = spaces(NavigationContext.TO_PROGRAM_LENGTH);
        this.nextMapset = MAPSET_NAME;
        this.nextMap = MAP_NAME;
        this.navigationContext = NavigationContext.empty();
    }

    /**
     * A deep-enough copy of another response: every payload string, every attribute quad and the navigation
     * state are carried across.
     *
     * @param other the response to copy; never {@code null}
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public ReportRequestResponse(ReportRequestResponse other) {
        Objects.requireNonNull(other, "A response is required to copy from");
        this.trnnameo = other.trnnameo;
        this.title01o = other.title01o;
        this.curdateo = other.curdateo;
        this.pgmnameo = other.pgmnameo;
        this.title02o = other.title02o;
        this.curtimeo = other.curtimeo;
        this.monthlyo = other.monthlyo;
        this.yearlyo = other.yearlyo;
        this.customo = other.customo;
        this.sdtmmo = other.sdtmmo;
        this.sdtddo = other.sdtddo;
        this.sdtyyyyo = other.sdtyyyyo;
        this.edtmmo = other.edtmmo;
        this.edtddo = other.edtddo;
        this.edtyyyyo = other.edtyyyyo;
        this.confirmo = other.confirmo;
        this.errmsgo = other.errmsgo;
        this.attributes.putAll(other.attributes);
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.navigationContext = other.navigationContext;
    }

    /**
     * {@code SPACES} at a given width - the {@code PIC X} fill character repeated.
     *
     * @param length how many spaces; must be at least 1
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is less than 1
     */
    public static String spaces(int length) {
        requireDeclaredWidth(length, "SPACES");
        return SPACE.repeat(length);
    }

    /**
     * {@code LOW-VALUES} at a given width - {@code X'00'} repeated.
     *
     * @param length how many bytes; must be at least 1
     * @return a string of exactly {@code length} {@code X'00'} characters
     * @throws IllegalArgumentException if {@code length} is less than 1
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        requireDeclaredWidth(length, "LOW-VALUES");
        return ScreenFieldImage.unpainted(length);
    }

    /**
     * Fills all seventeen payload items with {@code SPACES} at their declared widths, leaving the attribute
     * quads alone.
     */
    public void moveSpacesToAllFields() {
        for (ScreenField field : ScreenField.values()) {
            setPayloadValue(field, spaces(field.payloadLength()));
        }
    }

    /**
     * Reproduces {@code CORPT00C.cbl:179} {@code MOVE LOW-VALUES TO CORPT0AO}.
     */
    public void moveLowValuesToMapGroup() {
        for (ScreenField field : ScreenField.values()) {
            setPayloadValue(field, lowValues(field.payloadLength()));
            attributes.put(field, FieldAttributes.UNSET);
        }
    }

    /**
     * Reproduces the {@code INITIALIZE-ALL-FIELDS} paragraph, {@code CORPT00C.cbl:636-645}.
     *
     * <p>COBOL's {@code INITIALIZE} sets an alphanumeric item to {@code SPACES}, not to {@code LOW-VALUES};
     * the two are different bytes and the distinction is preserved.
     */
    public void initializeAllFields() {
        setPayloadValue(ScreenField.MONTHLY, spaces(ScreenField.MONTHLY.payloadLength()));
        setPayloadValue(ScreenField.YEARLY, spaces(ScreenField.YEARLY.payloadLength()));
        setPayloadValue(ScreenField.CUSTOM, spaces(ScreenField.CUSTOM.payloadLength()));
        setPayloadValue(ScreenField.SDTMM, spaces(ScreenField.SDTMM.payloadLength()));
        setPayloadValue(ScreenField.SDTDD, spaces(ScreenField.SDTDD.payloadLength()));
        setPayloadValue(ScreenField.SDTYYYY, spaces(ScreenField.SDTYYYY.payloadLength()));
        setPayloadValue(ScreenField.EDTMM, spaces(ScreenField.EDTMM.payloadLength()));
        setPayloadValue(ScreenField.EDTDD, spaces(ScreenField.EDTDD.payloadLength()));
        setPayloadValue(ScreenField.EDTYYYY, spaces(ScreenField.EDTYYYY.payloadLength()));
        setPayloadValue(ScreenField.CONFIRM, spaces(ScreenField.CONFIRM.payloadLength()));
    }

    /**
     * Reads one payload item by field, exactly as stored: never trimmed, never normalised.
     *
     * @param field which screen field; never {@code null}
     * @return the item at its declared width
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String payloadValue(ScreenField field) {
        requireField(field);
        return switch (field) {
            case TRNNAME -> trnnameo;
            case TITLE01 -> title01o;
            case CURDATE -> curdateo;
            case PGMNAME -> pgmnameo;
            case TITLE02 -> title02o;
            case CURTIME -> curtimeo;
            case MONTHLY -> monthlyo;
            case YEARLY -> yearlyo;
            case CUSTOM -> customo;
            case SDTMM -> sdtmmo;
            case SDTDD -> sdtddo;
            case SDTYYYY -> sdtyyyyo;
            case EDTMM -> edtmmo;
            case EDTDD -> edtddo;
            case EDTYYYY -> edtyyyyo;
            case CONFIRM -> confirmo;
            case ERRMSG -> errmsgo;
        };
    }

    /**
     * Writes one payload item by field, applying the alphanumeric {@code MOVE} rules for that field's
     * declared width.
     *
     * @param field which screen field; never {@code null}
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    @JsonIgnore
    public void setPayloadValue(ScreenField field, String value) {
        requireField(field);
        String fitted = movePicX(value, field.payloadLength(), field.payloadItemName());
        switch (field) {
            case TRNNAME -> trnnameo = fitted;
            case TITLE01 -> title01o = fitted;
            case CURDATE -> curdateo = fitted;
            case PGMNAME -> pgmnameo = fitted;
            case TITLE02 -> title02o = fitted;
            case CURTIME -> curtimeo = fitted;
            case MONTHLY -> monthlyo = fitted;
            case YEARLY -> yearlyo = fitted;
            case CUSTOM -> customo = fitted;
            case SDTMM -> sdtmmo = fitted;
            case SDTDD -> sdtddo = fitted;
            case SDTYYYY -> sdtyyyyo = fitted;
            case EDTMM -> edtmmo = fitted;
            case EDTDD -> edtddo = fitted;
            case EDTYYYY -> edtyyyyo = fitted;
            case CONFIRM -> confirmo = fitted;
            case ERRMSG -> errmsgo = fitted;
        }
    }

    @JsonIgnore
    public FieldAttributes attributesOf(ScreenField field) {
        requireField(field);
        return attributes.get(field);
    }

    /**
     * Replaces the whole attribute quad of one field.
     *
     * @param field which screen field; never {@code null}
     * @param quad the replacement quad; never {@code null}
     * @throws NullPointerException if {@code field} or {@code quad} is {@code null}
     */
    @JsonIgnore
    public void setAttributes(ScreenField field, FieldAttributes quad) {
        requireField(field);
        Objects.requireNonNull(quad, "An attribute quad is required for "
                + field.baseName() + "; pass FieldAttributes.UNSET to clear it");
        attributes.put(field, quad);
    }

    /**
     * The {@code xxxC} colour byte of one field - the item {@code app/cpy/CSSETATY.cpy:21-22} moves
     * {@link BmsAttributes#DFHRED} into and {@code CORPT00C.cbl:448} moves {@link BmsAttributes#DFHGREEN}
     * into.
     *
     * @param field which screen field; never {@code null}
     * @return the colour byte, {@link #ATTRIBUTE_UNSET} when never set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public byte colourAttribute(ScreenField field) {
        return attributesOf(field).colour();
    }

    /**
     * Sets the {@code xxxC} colour byte of one field, leaving the other three items of the quad as they
     * are.
     *
     * @param field which screen field; never {@code null}
     * @param colour the colour byte, for example {@link BmsAttributes#DFHRED}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public void setColourAttribute(ScreenField field, byte colour) {
        setAttributes(field, attributesOf(field).withColour(colour));
    }

    /**
     * The {@code xxxP} programmed-symbols byte of one field.
     *
     * @param field which screen field; never {@code null}
     * @return the byte, {@link #ATTRIBUTE_UNSET} when never set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public byte psAttribute(ScreenField field) {
        return attributesOf(field).ps();
    }

    /**
     * Sets the {@code xxxP} programmed-symbols byte of one field.
     *
     * @param field which screen field; never {@code null}
     * @param ps the byte to set
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public void setPsAttribute(ScreenField field, byte ps) {
        setAttributes(field, attributesOf(field).withPs(ps));
    }

    @JsonIgnore
    public byte hilightAttribute(ScreenField field) {
        return attributesOf(field).hilight();
    }

    @JsonIgnore
    public void setHilightAttribute(ScreenField field, byte hilight) {
        setAttributes(field, attributesOf(field).withHilight(hilight));
    }

    @JsonIgnore
    public byte validnAttribute(ScreenField field) {
        return attributesOf(field).validn();
    }

    @JsonIgnore
    public void setValidnAttribute(ScreenField field, byte validn) {
        setAttributes(field, attributesOf(field).withValidn(validn));
    }

    /**
     * The {@code ERRMSGC} byte, named because it is the one attribute item {@code CORPT00C} writes
     * directly.
     *
     * @return the colour byte of the error line
     */
    @JsonIgnore
    public byte getErrmsgc() {
        return colourAttribute(ScreenField.ERRMSG);
    }

    @JsonIgnore
    public void setErrmsgc(byte colour) {
        setColourAttribute(ScreenField.ERRMSG, colour);
    }

    /**
     * Reproduces {@code CORPT00C.cbl:448} {@code MOVE DFHGREEN TO ERRMSGC OF CORPT0AO}, which the program
     * performs on the success path - after {@code INITIALIZE-ALL-FIELDS} and before composing the
     * {@code ' report submitted for printing ...'} text - so that a confirmation reads green on a line the
     * mapset declares {@code COLOR=RED}.
     */
    public void moveDfhgreenToErrmsgc() {
        setErrmsgc(BmsAttributes.DFHGREEN);
    }

    /**
     * Applies a highlight decision taken by {@link FieldAttributeSetter} to one field.
     *
     * @param field the field to highlight; never {@code null}
     * @param highlight the decision to apply; never {@code null}
     * @return {@code true} if anything was written, {@code false} if the decision was to leave the field
     *     alone
     * @throws NullPointerException if {@code field} or {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the highlight names a different field or a different map
     */
    public boolean applyHighlight(ScreenField field, FieldHighlight highlight) {
        requireField(field);
        Objects.requireNonNull(highlight, "A highlight decision is required; call "
                + "FieldAttributeSetter.resolve(...) and pass its result, or "
                + "FieldHighlight.none(...) to leave the field alone");

        if (!highlight.screenFieldPrefix().isEmpty()
                && !highlight.screenFieldPrefix().equals(field.baseName())) {
            throw new IllegalArgumentException("Highlight was resolved for field '"
                    + highlight.screenFieldPrefix() + "' but is being applied to '" + field.baseName()
                    + "'; CSSETATY substitutes one (SCRNVAR2) per expansion, so the two must agree");
        }
        if (!highlight.mapName().isEmpty() && !highlight.mapName().equals(MAP_NAME)) {
            throw new IllegalArgumentException("Highlight was resolved for map '"
                    + highlight.mapName() + "' but this response projects " + SYMBOLIC_MAP_GROUP
                    + "; expected map name '" + MAP_NAME + "'");
        }

        if (highlight.untouched()) {
            return false;
        }

        setColourAttribute(field, highlight.colourItemValue());

        if (highlight.outputItemAssigned()) {
            setPayloadValue(field, highlight.outputItemValue());
        }
        return true;
    }

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown against the {@code 'Tran:'} label at row
     * 1 column 7.
     *
     * @return four characters, space-padded, never trimmed
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return trnnameo;
    }

    public void setTrnnameo(String trnnameo) {
        setPayloadValue(ScreenField.TRNNAME, trnnameo);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the first title line, {@code COLOR=YELLOW} at row 1 column 21.
     *
     * @return forty characters
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return title01o;
    }

    public void setTitle01o(String title01o) {
        setPayloadValue(ScreenField.TITLE01, title01o);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date against the {@code 'Date:'} label at row 1 column 71,
     * declared {@code INITIAL='mm/dd/yy'}.
     *
     * @return eight characters
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return curdateo;
    }

    public void setCurdateo(String curdateo) {
        setPayloadValue(ScreenField.CURDATE, curdateo);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name against the {@code 'Prog:'} label at row 2 column 7.
     *
     * @return eight characters
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return pgmnameo;
    }

    public void setPgmnameo(String pgmnameo) {
        setPayloadValue(ScreenField.PGMNAME, pgmnameo);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the second title line, {@code COLOR=YELLOW} at row 2 column 21.
     *
     * @return forty characters
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return title02o;
    }

    public void setTitle02o(String title02o) {
        setPayloadValue(ScreenField.TITLE02, title02o);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time against the {@code 'Time:'} label at row 2 column 71,
     * declared {@code INITIAL='hh:mm:ss'}.
     *
     * @return eight characters
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return curtimeo;
    }

    public void setCurtimeo(String curtimeo) {
        setPayloadValue(ScreenField.CURTIME, curtimeo);
    }

    /**
     * {@code MONTHLYO PIC X(1)} - the {@code 'Monthly (Current Month)'} selector at row 7 column 10.
     *
     * @return one character
     */
    @JsonProperty("monthly")
    public String getMonthlyo() {
        return monthlyo;
    }

    public void setMonthlyo(String monthlyo) {
        setPayloadValue(ScreenField.MONTHLY, monthlyo);
    }

    /**
     * {@code YEARLYO PIC X(1)} - the {@code 'Yearly (Current Year)'} selector at row 9 column 10, tested at
     * {@code CORPT00C.cbl:239}.
     *
     * @return one character
     */
    @JsonProperty("yearly")
    public String getYearlyo() {
        return yearlyo;
    }

    public void setYearlyo(String yearlyo) {
        setPayloadValue(ScreenField.YEARLY, yearlyo);
    }

    /**
     * {@code CUSTOMO PIC X(1)} - the {@code 'Custom (Date Range)'} selector at row 11 column 10, tested at
     * {@code CORPT00C.cbl:256}.
     *
     * @return one character
     */
    @JsonProperty("custom")
    public String getCustomo() {
        return customo;
    }

    public void setCustomo(String customo) {
        setPayloadValue(ScreenField.CUSTOM, customo);
    }

    /**
     * {@code SDTMMO PIC X(2)} - start-date month at row 13 column 29.
     *
     * @return two characters
     */
    @JsonProperty("sdtmm")
    public String getSdtmmo() {
        return sdtmmo;
    }

    public void setSdtmmo(String sdtmmo) {
        setPayloadValue(ScreenField.SDTMM, sdtmmo);
    }

    /**
     * {@code SDTDDO PIC X(2)} - start-date day at row 13 column 34, tested at {@code CORPT00C.cbl:338-339}
     * against {@code '31'} as a string.
     *
     * @return two characters
     */
    @JsonProperty("sdtdd")
    public String getSdtddo() {
        return sdtddo;
    }

    public void setSdtddo(String sdtddo) {
        setPayloadValue(ScreenField.SDTDD, sdtddo);
    }

    /**
     * {@code SDTYYYYO PIC X(4)} - start-date year at row 13 column 39, tested at {@code CORPT00C.cbl:347}.
     *
     * @return four characters
     */
    @JsonProperty("sdtyyyy")
    public String getSdtyyyyo() {
        return sdtyyyyo;
    }

    public void setSdtyyyyo(String sdtyyyyo) {
        setPayloadValue(ScreenField.SDTYYYY, sdtyyyyo);
    }

    /**
     * {@code EDTMMO PIC X(2)} - end-date month at row 14 column 29, tested at {@code CORPT00C.cbl:355-356}.
     *
     * @return two characters
     */
    @JsonProperty("edtmm")
    public String getEdtmmo() {
        return edtmmo;
    }

    public void setEdtmmo(String edtmmo) {
        setPayloadValue(ScreenField.EDTMM, edtmmo);
    }

    /**
     * {@code EDTDDO PIC X(2)} - end-date day at row 14 column 34, tested at {@code CORPT00C.cbl:364-365}.
     *
     * @return two characters
     */
    @JsonProperty("edtdd")
    public String getEdtddo() {
        return edtddo;
    }

    public void setEdtddo(String edtddo) {
        setPayloadValue(ScreenField.EDTDD, edtddo);
    }

    /**
     * {@code EDTYYYYO PIC X(4)} - end-date year at row 14 column 39, tested at {@code CORPT00C.cbl:373}.
     *
     * @return four characters
     */
    @JsonProperty("edtyyyy")
    public String getEdtyyyyo() {
        return edtyyyyo;
    }

    public void setEdtyyyyo(String edtyyyyo) {
        setPayloadValue(ScreenField.EDTYYYY, edtyyyyo);
    }

    /**
     * {@code CONFIRMO PIC X(1)} - the {@code (Y/N)} answer at row 19 column 66, tested at
     * {@code CORPT00C.cbl:478} and line 480 against {@code 'Y'}, {@code 'y'}, {@code 'N'} and {@code 'n'}.
     *
     * @return one character
     */
    @JsonProperty("confirm")
    public String getConfirmo() {
        return confirmo;
    }

    public void setConfirmo(String confirmo) {
        setPayloadValue(ScreenField.CONFIRM, confirmo);
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line at row 23, {@code ATTRB=(ASKIP,BRT,FSET)} and
     * {@code COLOR=RED}.
     *
     * @return seventy-eight characters, space-padded and never trimmed, so a JSON round trip returns an
     *     equal value
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return errmsgo;
    }

    public void setErrmsgo(String errmsgo) {
        setPayloadValue(ScreenField.ERRMSG, errmsgo);
    }

    /**
     * Reproduces {@code CORPT00C.cbl:169-170} {@code MOVE SPACES TO WS-MESSAGE ERRMSGO OF CORPT0AO} - the
     * first thing the program does on every invocation, clearing any message left over from the previous
     * send.
     */
    public void moveSpacesToErrmsgo() {
        setErrmsgo(spaces(ScreenField.ERRMSG.payloadLength()));
    }

    /**
     * Reproduces the invalid-key path, {@code CORPT00C.cbl:193}
     * {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE} followed by line 560's move into {@code ERRMSGO}.
     *
     * <p>The text is {@link SystemMessages#CCDA_MSG_INVALID_KEY}, taken from {@code app/cpy/CSMSG01Y.cpy}
     * rather than retyped, so the bytes cannot drift.
     */
    public void moveInvalidKeyMessageToErrmsgo() {
        setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Reproduces the {@code POPULATE-HEADER-INFO} paragraph, {@code CORPT00C.cbl:613-628}, which
     * {@code SEND-TRNRPT-SCREEN} performs before every send.
     *
     * <p>The literals come from {@link ScreenTitles}, which transcribes {@code app/cpy/COTTL01Y.cpy}, so
     * they are never retyped here.
     *
     * @param dateHeader the captured date and time, standing in for {@code WS-CURDATE-DATA}; never
     *     {@code null}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void populateHeaderInfo(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required to populate the screen header: "
                + "CORPT00C.cbl:611 reads FUNCTION CURRENT-DATE, and pinning that instant is what "
                + "makes a response reproducible");
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
        setTrnnameo(TRANSACTION_ID);
        setPgmnameo(PROGRAM_NAME);
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * The program the client should invoke next - the stateless replacement for {@code CORPT00C.cbl:549}
     * {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}.
     *
     * @return eight characters
     */
    public String getNextProgram() {
        return nextProgram;
    }

    public void setNextProgram(String nextProgram) {
        this.nextProgram = movePicX(nextProgram, NavigationContext.TO_PROGRAM_LENGTH,
                NavigationContext.TO_PROGRAM_FIELD);
    }

    /**
     * The mapset of the screen this response describes, {@link #MAPSET_NAME}.
     *
     * @return seven characters
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the next mapset.
     *
     * @param nextMapset the mapset name; never {@code null}
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = movePicX(nextMapset, NavigationContext.LAST_MAPSET_LENGTH,
                NavigationContext.LAST_MAPSET_FIELD);
    }

    /**
     * The map of the screen this response describes, {@link #MAP_NAME}.
     *
     * @return seven characters
     */
    public String getNextMap() {
        return nextMap;
    }

    public void setNextMap(String nextMap) {
        this.nextMap = movePicX(nextMap, NavigationContext.LAST_MAP_LENGTH,
                NavigationContext.LAST_MAP_FIELD);
    }

    /**
     * The commarea, echoed so the client can send it back unchanged on the next call.
     *
     * @return the context; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Sets the commarea echo without touching the navigation trio.
     *
     * @param navigationContext the context; never {@code null}
     * @throws NullPointerException if {@code navigationContext} is {@code null}
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A NavigationContext is required; pass NavigationContext.empty() for a blank "
                        + "commarea rather than null, because COBOL has no null");
    }

    /**
     * Echoes a commarea into this response and derives the navigation trio from it for a transfer: the
     * program from {@link NavigationContext#toProgram()} - the field {@code CORPT00C.cbl:549} passes to
     * {@code XCTL} - and the mapset and map blank.
     *
     * @param context the commarea to echo; never {@code null}
     * @throws NullPointerException if {@code context} is {@code null}
     */
    public void echoNavigation(NavigationContext context) {
        setNavigationContext(context);
        setNextProgram(context.toProgram());
        setNextMapset(spaces(NavigationContext.LAST_MAPSET_LENGTH));
        setNextMap(spaces(NavigationContext.LAST_MAP_LENGTH));
    }

    /**
     * Whether a fixed-width value satisfies COBOL's {@code = LOW-VALUES OR SPACES} test - the guard at
     * {@code CORPT00C.cbl:542} and, on the inbound items, at lines 213, 239, 256 and 259-299.
     *
     * @param value the value to test; may be {@code null}, which is treated as blank
     * @return {@code true} when every byte is a space, or every byte is {@code X'00'}, or the value is
     *     {@code null} or empty
     */
    public static boolean isSpacesOrLowValues(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        boolean allSpaces = true;
        boolean allLowValues = true;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ') {
                allSpaces = false;
            }
            if (character != '\u0000') {
                allLowValues = false;
            }
        }
        return allSpaces || allLowValues;
    }

    // This is what a CICS SEND MAP would transmit and what a field-by-field parity diff compares, so it is
    // rendered by absolute offset from LAYOUT and never by concatenation.

    /**
     * Renders this response as the {@link #RECORD_LENGTH}-byte {@code 01 CORPT0AO} image.
     *
     * @param charset the code page to render in - {@code IBM037} for a mainframe image, {@code US-ASCII}
     *     for a fixture
     * @return exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render " + SYMBOLIC_MAP_GROUP
                + " as bytes: a fixed-width image is bytes in a specific code page, so the code page "
                + "must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes this response into an existing record area at the layout's absolute offsets.
     *
     * @param record the target area, which must be exactly {@link #RECORD_LENGTH} bytes wide; never
     *     {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record is not {@link #RECORD_LENGTH} bytes wide
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write " + SYMBOLIC_MAP_GROUP
                + " into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireGroupWidth(record);
        for (ScreenField field : ScreenField.values()) {
            codec.writePicX(record, field.payloadSpan(), payloadValue(field));
            FieldAttributes quad = attributesOf(field);
            record.writeSpanBytes(field.colourSpan(), new byte[] {quad.colour()});
            record.writeSpanBytes(field.psSpan(), new byte[] {quad.ps()});
            record.writeSpanBytes(field.hilightSpan(), new byte[] {quad.hilight()});
            record.writeSpanBytes(field.validnSpan(), new byte[] {quad.validn()});
        }
    }

    /**
     * Rebuilds a response from a {@link #RECORD_LENGTH}-byte group image.
     *
     * @param image exactly {@link #RECORD_LENGTH} bytes; never {@code null}
     * @param charset the code page the image is in; never {@code null}
     * @return a response carrying every payload item and every attribute byte from the image
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not {@link #RECORD_LENGTH} bytes long
     */
    public static ReportRequestResponse fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "Stored bytes are required to read " + SYMBOLIC_MAP_GROUP
                + " from");
        Objects.requireNonNull(charset, "A charset is required to read " + SYMBOLIC_MAP_GROUP
                + " from bytes; it is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(image, LAYOUT), codec);
    }

    /**
     * Reads a response out of an existing record area.
     *
     * @param record the source area, exactly {@link #RECORD_LENGTH} bytes wide; never {@code null}
     * @return a response carrying every payload item and every attribute byte
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record is not {@link #RECORD_LENGTH} bytes wide
     */
    public static ReportRequestResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read " + SYMBOLIC_MAP_GROUP
                + " from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    private static ReportRequestResponse readFrom(FixedWidthRecord record, FixedWidthCodec codec) {
        requireGroupWidth(record);
        ReportRequestResponse response = new ReportRequestResponse();
        for (ScreenField field : ScreenField.values()) {
            response.setPayloadValue(field, codec.readPicX(record, field.payloadSpan()));
            response.setAttributes(field, new FieldAttributes(
                    record.readSpanBytes(field.colourSpan())[0],
                    record.readSpanBytes(field.psSpan())[0],
                    record.readSpanBytes(field.hilightSpan())[0],
                    record.readSpanBytes(field.validnSpan())[0]));
        }
        return response;
    }

    /**
     * The seventeen payload items keyed by their verbatim copybook names - {@code TRNNAMEO},
     * {@code TITLE01O} and so on - in copybook order, at their declared widths and untrimmed.
     *
     * @return a new, mutable, insertion-ordered map of seventeen entries
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.payloadItemName(), payloadValue(field));
        }
        return images;
    }

    /**
     * The sixty-eight attribute items keyed by their verbatim copybook names - {@code TRNNAMEC},
     * {@code TRNNAMEP}, {@code TRNNAMEH}, {@code TRNNAMEV} and so on - rendered as two-character hex, in
     * copybook order.
     *
     * @return a new, mutable, insertion-ordered map of sixty-eight entries
     */
    @JsonIgnore
    public Map<String, String> attributeImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            FieldAttributes quad = attributesOf(field);
            String base = field.baseName();
            images.put(base + "C", BmsAttributes.toHex(quad.colour()));
            images.put(base + "P", BmsAttributes.toHex(quad.ps()));
            images.put(base + "H", BmsAttributes.toHex(quad.hilight()));
            images.put(base + "V", BmsAttributes.toHex(quad.validn()));
        }
        return images;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReportRequestResponse that)) {
            return false;
        }
        return trnnameo.equals(that.trnnameo)
                && title01o.equals(that.title01o)
                && curdateo.equals(that.curdateo)
                && pgmnameo.equals(that.pgmnameo)
                && title02o.equals(that.title02o)
                && curtimeo.equals(that.curtimeo)
                && monthlyo.equals(that.monthlyo)
                && yearlyo.equals(that.yearlyo)
                && customo.equals(that.customo)
                && sdtmmo.equals(that.sdtmmo)
                && sdtddo.equals(that.sdtddo)
                && sdtyyyyo.equals(that.sdtyyyyo)
                && edtmmo.equals(that.edtmmo)
                && edtddo.equals(that.edtddo)
                && edtyyyyo.equals(that.edtyyyyo)
                && confirmo.equals(that.confirmo)
                && errmsgo.equals(that.errmsgo)
                && attributes.equals(that.attributes)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && navigationContext.equals(that.navigationContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trnnameo, title01o, curdateo, pgmnameo, title02o, curtimeo, monthlyo,
                yearlyo, customo, sdtmmo, sdtddo, sdtyyyyo, edtmmo, edtddo, edtyyyyo, confirmo,
                errmsgo, attributes, nextProgram, nextMapset, nextMap, navigationContext);
    }

    /**
     * A diagnostic rendering naming every item by its copybook name, so a failed assertion reads as
     * {@code ERRMSGO='...'} rather than as an anonymous tuple.
     *
     * @return a human-readable description; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendered = new StringBuilder(512)
                .append("ReportRequestResponse[")
                .append(SYMBOLIC_MAP_GROUP)
                .append(' ')
                .append(RECORD_LENGTH)
                .append(" bytes");
        for (ScreenField field : ScreenField.values()) {
            rendered.append(", ")
                    .append(field.payloadItemName())
                    .append("='")
                    .append(payloadValue(field))
                    .append('\'');
            FieldAttributes quad = attributesOf(field);
            if (!quad.allUnset()) {
                rendered.append(" (").append(quad.describe()).append(')');
            }
        }
        return rendered.append(", nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', ").append(NavigationContext.TO_PROGRAM_FIELD).append("='")
                .append(navigationContext.toProgram())
                .append("']")
                .toString();
    }

    private static String movePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass spaces(" + length + ") or lowValues(" + length + ") to state which "
                + "figurative constant is meant");
        return PICTURE_RULES.movePicX(value, length);
    }

    private static void requireField(ScreenField field) {
        Objects.requireNonNull(field, "A ScreenField is required to select one of the "
                + SCREEN_FIELD_COUNT + " name-labelled DFHMDF fields of mapset " + MAPSET_NAME);
    }

    private static void requireDeclaredWidth(int length, String figurativeConstant) {
        if (length < 1) {
            throw new IllegalArgumentException(figurativeConstant + " was requested at width "
                    + length + "; every copybook item occupies at least 1 byte");
        }
    }

    private static void requireGroupWidth(FixedWidthRecord record) {
        if (record.recordLength() != RECORD_LENGTH) {
            throw new IllegalArgumentException("A " + SYMBOLIC_MAP_GROUP + " image is "
                    + RECORD_LENGTH + " byte(s) - " + TIOAPFX_PREFIX_LENGTH + " (TIOAPFX) + "
                    + SCREEN_FIELD_COUNT + " x " + FIELD_PREFIX_LENGTH + " (attribute prefixes) + "
                    + PAYLOAD_WIDTH_TOTAL + " (payload) - but the record area is "
                    + record.recordLength() + " byte(s) wide");
        }
    }
}
