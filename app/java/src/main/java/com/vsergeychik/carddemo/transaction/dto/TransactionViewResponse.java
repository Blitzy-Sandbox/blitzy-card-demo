package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound REST payload for CSD transaction {@code CT02}, program {@code app/cbl/COTRN02C.cbl},
 * projected field for field from the {@code xxxO} items of {@code 01 COTRN2AO REDEFINES COTRN2AI} in
 * {@code app/cpy-bms/COTRN02.CPY} (line 145) and their name-labelled {@code DFHMDF} definitions in
 * {@code app/bms/COTRN02.bms}.
 *
 * <p>Callers that need the amount as a number parse {@link #getTrnamto()} where the COBOL does, in the
 * controller, exactly as {@code app/cbl/COTRN02C.cbl:383} and {@code :456} apply {@code FUNCTION NUMVAL-C}.
 */
public final class TransactionViewResponse {
    /**
     * {@code CT02} - the CICS transaction identifier.
     */
    public static final String TRANSACTION_ID = "CT02";

    public static final String PROGRAM_ID = "COTRN02C";

    /**
     * {@code COTRN02} - the mapset, seven characters.
     */
    public static final String MAPSET_NAME = "COTRN02";

    public static final String MAP_NAME = "COTRN2A";

    /**
     * The 12-byte {@code TIOAPFX=YES} prefix at {@code app/cpy-bms/COTRN02.CPY:146}.
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code FILLER PICTURE X(3)} that opens every field's prefix in the {@code AO} view.
     */
    public static final int ATTRIBUTE_PREFIX_FILLER_LENGTH = 3;

    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    public static final int PER_FIELD_PREFIX_LENGTH =
            ATTRIBUTE_PREFIX_FILLER_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    /**
     * 21 - the number of name-labelled {@code DFHMDF} definitions in {@code app/bms/COTRN02.bms}, of
     * {@code xxxI} items and of {@code xxxO} items in {@code app/cpy-bms/COTRN02.CPY}.
     */
    public static final int FIELD_COUNT = 21;

    /**
     * {@code 02 TRNNAMEO PIC X(4)}, {@code COTRN02.CPY:152}; {@code TRNNAME DFHMDF LENGTH=4}.
     */
    public static final int TRNNAMEO_LENGTH = 4;

    /**
     * {@code 02 TITLE01O PIC X(40)}, {@code COTRN02.CPY:158}; {@code LENGTH=40}.
     */
    public static final int TITLE01O_LENGTH = 40;

    /**
     * {@code 02 CURDATEO PIC X(8)}, {@code COTRN02.CPY:164}; {@code LENGTH=8}.
     */
    public static final int CURDATEO_LENGTH = 8;

    /**
     * {@code 02 PGMNAMEO PIC X(8)}, {@code COTRN02.CPY:170}; {@code LENGTH=8}.
     */
    public static final int PGMNAMEO_LENGTH = 8;

    /**
     * {@code 02 TITLE02O PIC X(40)}, {@code COTRN02.CPY:176}; {@code LENGTH=40}.
     */
    public static final int TITLE02O_LENGTH = 40;

    /**
     * {@code 02 CURTIMEO PIC X(8)}, {@code COTRN02.CPY:182}; {@code LENGTH=8}.
     */
    public static final int CURTIMEO_LENGTH = 8;

    /**
     * {@code 02 ACTIDINO PIC X(11)}, {@code COTRN02.CPY:188}; {@code ACTIDIN DFHMDF LENGTH=11}.
     */
    public static final int ACTIDINO_LENGTH = 11;

    /**
     * {@code 02 CARDNINO PIC X(16)}, {@code COTRN02.CPY:194}; {@code CARDNIN DFHMDF LENGTH=16}.
     */
    public static final int CARDNINO_LENGTH = 16;

    /**
     * {@code 02 TTYPCDO PIC X(2)}, {@code COTRN02.CPY:200}; {@code LENGTH=2}.
     */
    public static final int TTYPCDO_LENGTH = 2;

    /**
     * {@code 02 TCATCDO PIC X(4)}, {@code COTRN02.CPY:206}; {@code LENGTH=4}.
     */
    public static final int TCATCDO_LENGTH = 4;

    /**
     * {@code 02 TRNSRCO PIC X(10)}, {@code COTRN02.CPY:212}; {@code LENGTH=10}.
     */
    public static final int TRNSRCO_LENGTH = 10;

    /**
     * {@code 02 TDESCO PIC X(60)}, {@code COTRN02.CPY:218}; {@code LENGTH=60}.
     */
    public static final int TDESCO_LENGTH = 60;

    /**
     * {@code 02 TRNAMTO PIC X(12)}, {@code COTRN02.CPY:224}; {@code TRNAMT DFHMDF LENGTH=12}.
     */
    public static final int TRNAMTO_LENGTH = 12;

    /**
     * {@code 02 TORIGDTO PIC X(10)}, {@code COTRN02.CPY:230}; {@code LENGTH=10}.
     */
    public static final int TORIGDTO_LENGTH = 10;

    /**
     * {@code 02 TPROCDTO PIC X(10)}, {@code COTRN02.CPY:236}; {@code LENGTH=10}.
     */
    public static final int TPROCDTO_LENGTH = 10;

    /**
     * {@code 02 MIDO PIC X(9)}, {@code COTRN02.CPY:242}; {@code MID DFHMDF LENGTH=9}.
     */
    public static final int MIDO_LENGTH = 9;

    /**
     * {@code 02 MNAMEO PIC X(30)}, {@code COTRN02.CPY:248}; {@code LENGTH=30}.
     */
    public static final int MNAMEO_LENGTH = 30;

    /**
     * {@code 02 MCITYO PIC X(25)}, {@code COTRN02.CPY:254}; {@code LENGTH=25}.
     */
    public static final int MCITYO_LENGTH = 25;

    /**
     * {@code 02 MZIPO PIC X(10)}, {@code COTRN02.CPY:260}; {@code LENGTH=10}.
     */
    public static final int MZIPO_LENGTH = 10;

    /**
     * {@code 02 CONFIRMO PIC X(1)}, {@code COTRN02.CPY:266}; {@code CONFIRM DFHMDF LENGTH=1}.
     */
    public static final int CONFIRMO_LENGTH = 1;

    /**
     * {@code 02 ERRMSGO PIC X(78)}, {@code COTRN02.CPY:272};
     * {@code ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78 POS=(23,1)}.
     */
    public static final int ERRMSGO_LENGTH = 78;

    /**
     * 396 - the sum of the 21 declared widths, and independently the sum of the 21 {@code DFHMDF LENGTH=}
     * values.
     */
    public static final int PAYLOAD_LENGTH =
            TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH + PGMNAMEO_LENGTH
                    + TITLE02O_LENGTH + CURTIMEO_LENGTH + ACTIDINO_LENGTH + CARDNINO_LENGTH
                    + TTYPCDO_LENGTH + TCATCDO_LENGTH + TRNSRCO_LENGTH + TDESCO_LENGTH
                    + TRNAMTO_LENGTH + TORIGDTO_LENGTH + TPROCDTO_LENGTH + MIDO_LENGTH
                    + MNAMEO_LENGTH + MCITYO_LENGTH + MZIPO_LENGTH + CONFIRMO_LENGTH
                    + ERRMSGO_LENGTH;

    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_LENGTH + FIELD_COUNT * PER_FIELD_PREFIX_LENGTH + PAYLOAD_LENGTH;

    /**
     * The 21 name-labelled screen fields of map {@code COTRN2A}, in {@code app/bms/COTRN02.bms} and
     * {@code app/cpy-bms/COTRN02.CPY} declaration order.
     */
    public enum ScreenField {
        TRNNAME("TRNNAME", TRNNAMEO_LENGTH, false),

        TITLE01("TITLE01", TITLE01O_LENGTH, false),

        CURDATE("CURDATE", CURDATEO_LENGTH, false),

        PGMNAME("PGMNAME", PGMNAMEO_LENGTH, false),

        TITLE02("TITLE02", TITLE02O_LENGTH, false),

        CURTIME("CURTIME", CURTIMEO_LENGTH, false),

        /**
         * {@code ACTIDIN}, {@code ATTRB=(FSET,IC,NORM,UNPROT) COLOR=GREEN LENGTH=11 POS=(6,21)}.
         */
        ACTIDIN("ACTIDIN", ACTIDINO_LENGTH, true),

        CARDNIN("CARDNIN", CARDNINO_LENGTH, true),

        TTYPCD("TTYPCD", TTYPCDO_LENGTH, true),

        TCATCD("TCATCD", TCATCDO_LENGTH, true),

        TRNSRC("TRNSRC", TRNSRCO_LENGTH, true),

        TDESC("TDESC", TDESCO_LENGTH, true),

        TRNAMT("TRNAMT", TRNAMTO_LENGTH, true),

        TORIGDT("TORIGDT", TORIGDTO_LENGTH, true),

        TPROCDT("TPROCDT", TPROCDTO_LENGTH, true),

        MID("MID", MIDO_LENGTH, true),

        MNAME("MNAME", MNAMEO_LENGTH, true),

        MCITY("MCITY", MCITYO_LENGTH, true),

        MZIP("MZIP", MZIPO_LENGTH, true),

        CONFIRM("CONFIRM", CONFIRMO_LENGTH, true),

        ERRMSG("ERRMSG", ERRMSGO_LENGTH, false);

        private final String label;
        private final int width;
        private final boolean input;

        ScreenField(String label, int width, boolean input) {
            this.label = label;
            this.width = width;
            this.input = input;
        }

        /**
         * The BMS field label - the {@code xxx} stem, exactly as {@code app/bms/COTRN02.bms} labels the
         * {@code DFHMDF}.
         *
         * @return the label, for example {@code TRNAMT}
         */
        public String label() {
            return label;
        }

        /**
         * The declared width of this field's {@code xxxO} item, which equals its {@code DFHMDF}
         * {@code LENGTH=}.
         *
         * @return the width in characters, always 1 or more
         */
        public int width() {
            return width;
        }

        /**
         * Whether the {@code DFHMDF} declares this field {@code UNPROT} - that is, whether the terminal
         * operator can type into it.
         *
         * @return {@code true} for an unprotected input field
         */
        public boolean input() {
            return input;
        }

        /**
         * The name of this field's payload item in the {@code AO} view - the label plus {@code O}.
         *
         * @return for example {@code TRNAMTO}
         */
        public String outputItemName() {
            return label + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The name of this field's colour item - the label plus {@code C}.
         *
         * @return for example {@code TRNAMTC}
         */
        public String colourItemName() {
            return label + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The name of this field's programmed-symbols item - the label plus {@code P}.
         *
         * @return for example {@code TRNAMTP}
         */
        public String psItemName() {
            return label + "P";
        }

        /**
         * The name of this field's highlight item - the label plus {@code H}.
         *
         * @return for example {@code TRNAMTH}
         */
        public String highlightItemName() {
            return label + "H";
        }

        /**
         * The name of this field's validation item - the label plus {@code V}.
         *
         * @return for example {@code TRNAMTV}
         */
        public String validnItemName() {
            return label + "V";
        }

        /**
         * Resolves a field from its BMS label, as carried by {@link FieldHighlight#screenFieldPrefix()}.
         *
         * @param label the BMS label, for example {@code TRNAMT}; compared exactly, after trimming trailing
         *     spaces so a space-padded prefix still resolves
         * @return the matching field
         * @throws NullPointerException if {@code label} is {@code null}
         * @throws IllegalArgumentException if no field of map {@code COTRN2A} carries that label
         */
        public static ScreenField ofLabel(String label) {
            Objects.requireNonNull(label, "A screen field label is required");
            String wanted = label.stripTrailing();
            for (ScreenField field : values()) {
                if (field.label.equals(wanted)) {
                    return field;
                }
            }
            throw new IllegalArgumentException("Map " + MAP_NAME + " declares no field labelled '"
                    + wanted + "'; the 21 labelled DFHMDF fields of app/bms/COTRN02.bms are "
                    + LABELS + ". Note that TRNID belongs to COTRN01, not to this map.");
        }
    }

    private static final List<String> LABELS = buildLabels();

    private static List<String> buildLabels() {
        List<String> labels = new ArrayList<>(FIELD_COUNT);
        for (ScreenField field : ScreenField.values()) {
            labels.add(field.label());
        }
        return List.copyOf(labels);
    }

    /**
     * The five spans of one screen field in the {@code AO} view: the four one-byte attribute items and the
     * payload item.
     *
     * @param colour the {@code xxxC} colour item, one byte
     * @param ps the {@code xxxP} programmed-symbols item, one byte
     * @param highlight the {@code xxxH} highlight item, one byte
     * @param validn the {@code xxxV} validation item, one byte
     * @param output the {@code xxxO} payload item, the field's declared width
     */
    public record FieldSpans(FieldSpan colour, FieldSpan ps, FieldSpan highlight, FieldSpan validn,
            FieldSpan output) {
        public FieldSpans {
            Objects.requireNonNull(colour, "A colour item descriptor is required");
            Objects.requireNonNull(ps, "A programmed-symbols item descriptor is required");
            Objects.requireNonNull(highlight, "A highlight item descriptor is required");
            Objects.requireNonNull(validn, "A validation item descriptor is required");
            Objects.requireNonNull(output, "An output item descriptor is required");
        }
    }

    /**
     * Holder for the two derived layout constants, so the walk that builds them runs once.
     *
     * @param spans the per-field spans of the output group
     * @param layout the layout of the whole group
     */
    private record LayoutBundle(Map<ScreenField, FieldSpans> spans, RecordLayout layout) {
    }

    private static final LayoutBundle LAYOUT_BUNDLE = buildLayout();

    public static final Map<ScreenField, FieldSpans> FIELD_SPANS = LAYOUT_BUNDLE.spans();

    /**
     * The complete layout of {@code 01 COTRN2AO REDEFINES COTRN2AI}: 127 spans in copybook declaration
     * order, declared at {@link #SYMBOLIC_MAP_LENGTH}.
     */
    public static final RecordLayout LAYOUT = LAYOUT_BUNDLE.layout();

    private static LayoutBundle buildLayout() {
        Map<ScreenField, FieldSpans> spans = new EnumMap<>(ScreenField.class);
        List<FieldSpan> ordered = new ArrayList<>(1 + FIELD_COUNT * 6);

        int offset = 0;

        ordered.add(FieldSpan.filler(offset, TIOAPFX_LENGTH));
        offset += TIOAPFX_LENGTH;

        for (ScreenField field : ScreenField.values()) {
            ordered.add(FieldSpan.filler(offset, ATTRIBUTE_PREFIX_FILLER_LENGTH));
            offset += ATTRIBUTE_PREFIX_FILLER_LENGTH;

            FieldSpan colour =
                    FieldSpan.alphanumeric(field.colourItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan ps = FieldSpan.alphanumeric(field.psItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan highlight =
                    FieldSpan.alphanumeric(field.highlightItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan validn =
                    FieldSpan.alphanumeric(field.validnItemName(), offset, ATTRIBUTE_ITEM_LENGTH);
            offset += ATTRIBUTE_ITEM_LENGTH;

            FieldSpan output =
                    FieldSpan.alphanumeric(field.outputItemName(), offset, field.width());
            offset += field.width();

            ordered.add(colour);
            ordered.add(ps);
            ordered.add(highlight);
            ordered.add(validn);
            ordered.add(output);

            spans.put(field, new FieldSpans(colour, ps, highlight, validn, output));
        }

        if (offset != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalStateException("The COTRN2AO group walk produced " + offset
                    + " bytes but 01 COTRN2AO REDEFINES COTRN2AI is " + SYMBOLIC_MAP_LENGTH
                    + " bytes (" + TIOAPFX_LENGTH + " + " + FIELD_COUNT + " * "
                    + PER_FIELD_PREFIX_LENGTH + " + " + PAYLOAD_LENGTH + "); a declared width in "
                    + "app/cpy-bms/COTRN02.CPY has been mistranscribed");
        }

        RecordLayout layout =
                RecordLayout.of(SYMBOLIC_MAP_LENGTH, ordered.toArray(new FieldSpan[0]));
        return new LayoutBundle(Collections.unmodifiableMap(spans), layout);
    }

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * The COBOL figurative constant {@code SPACES} rendered for a field of the given width.
     *
     * <p>A {@code PIC X} field always holds exactly its declared width in characters, and an "empty" one
     * holds spaces, so this is how a caller says empty.
     *
     * @param length the field width in characters
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String spaces(int length) {
        requireDeclaredWidth(length, "SPACES");
        return " ".repeat(length);
    }

    /**
     * The COBOL figurative constant {@code LOW-VALUES} rendered for a field of the given width: the
     * character {@code U+0000} repeated, which is the byte {@code 0x00} under both code pages this system
     * uses.
     *
     * @param length the field width in characters
     * @return a string of exactly {@code length} {@code U+0000} characters
     * @throws IllegalArgumentException if {@code length} is below 1
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        requireDeclaredWidth(length, "LOW-VALUES");
        return ScreenFieldImage.unpainted(length);
    }

    private static void requireDeclaredWidth(int length, String figurativeConstant) {
        if (length < 1) {
            throw new IllegalArgumentException("Cannot render " + figurativeConstant + " for a field "
                    + "of " + length + " character(s); every item of COTRN2AO is at least 1 byte "
                    + "wide");
        }
    }

    /**
     * Applies the COBOL {@code PIC X} move rule: pad on the right with spaces when the value is shorter
     * than the field, truncate on the right when it is longer.
     *
     * @param value the value to store
     * @param length the declared field width
     * @param cobolName the item name, for the diagnostic
     * @return the value at exactly {@code length} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    private static String movePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass spaces(" + length + ") or lowValues(" + length + ") to state which "
                + "figurative constant is meant");
        return PICTURE_RULES.movePicX(value, length);
    }

    /**
     * The four one-byte BMS attribute items of a single screen field - the {@code xxxC} colour,
     * {@code xxxP} programmed symbols, {@code xxxH} highlight and {@code xxxV} validation items of
     * {@code 01 COTRN2AO}.
     */
    public static final class FieldMetadata {
        private byte colour = BmsAttributes.DFHDFCOL;
        private byte programmedSymbols = BmsAttributes.DFHDFCOL;
        private byte highlight = BmsAttributes.DFHDFCOL;
        private byte validation = BmsAttributes.DFHDFCOL;

        /**
         * Creates a quad with all four items at {@link BmsAttributes#DFHDFCOL}.
         */
        public FieldMetadata() {
        }

        /**
         * The {@code xxxC} colour item - the byte {@code app/cpy/CSSETATY.cpy} moves
         * {@link BmsAttributes#DFHRED} into when a field is in error during {@code REENTER}.
         *
         * @return the colour attribute byte
         */
        public byte getColour() {
            return colour;
        }

        public void setColour(byte colour) {
            this.colour = colour;
        }

        public byte getProgrammedSymbols() {
            return programmedSymbols;
        }

        public void setProgrammedSymbols(byte programmedSymbols) {
            this.programmedSymbols = programmedSymbols;
        }

        public byte getHighlight() {
            return highlight;
        }

        public void setHighlight(byte highlight) {
            this.highlight = highlight;
        }

        public byte getValidation() {
            return validation;
        }

        public void setValidation(byte validation) {
            this.validation = validation;
        }

        /**
         * Whether all four items are still at {@link BmsAttributes#DFHDFCOL}, meaning no attribute has been
         * stated for this field.
         *
         * @return {@code true} when the quad is untouched
         */
        @JsonIgnore
        public boolean isDefault() {
            return colour == BmsAttributes.DFHDFCOL
                    && programmedSymbols == BmsAttributes.DFHDFCOL
                    && highlight == BmsAttributes.DFHDFCOL
                    && validation == BmsAttributes.DFHDFCOL;
        }

        /**
         * Restores all four items to {@link BmsAttributes#DFHDFCOL}.
         */
        public void reset() {
            colour = BmsAttributes.DFHDFCOL;
            programmedSymbols = BmsAttributes.DFHDFCOL;
            highlight = BmsAttributes.DFHDFCOL;
            validation = BmsAttributes.DFHDFCOL;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldMetadata that)) {
                return false;
            }
            return colour == that.colour
                    && programmedSymbols == that.programmedSymbols
                    && highlight == that.highlight
                    && validation == that.validation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, programmedSymbols, highlight, validation);
        }

        @Override
        public String toString() {
            return "FieldMetadata[C=" + BmsAttributes.toHex(colour)
                    + ", P=" + BmsAttributes.toHex(programmedSymbols)
                    + ", H=" + BmsAttributes.toHex(highlight)
                    + ", V=" + BmsAttributes.toHex(validation) + "]";
        }
    }

    // The three sibling programs name their extensions CDEMO-CT00-*, CDEMO-CT01-* and CDEMO-CT02-*; the
    // names differ, so the areas are not interchangeable, and field-for-field diffing depends on the
    // distinct names being preserved.

    /**
     * {@code 05 CDEMO-CT02-INFO} - the 58-byte pagination and selection cursor that
     * {@code app/cbl/COTRN02C.cbl} appends to the shared commarea at lines 72 to 80:
     * {@code 16 + 16 + 8 + 1 + 1 + 16 = 58}, asserted by {@link #LENGTH}.
     */
    public static final class Ct02Info {
        /**
         * {@code 10 CDEMO-CT02-TRNID-FIRST PIC X(16)}, {@code app/cbl/COTRN02C.cbl:73}.
         */
        public static final int TRNID_FIRST_LENGTH = 16;

        /**
         * {@code 10 CDEMO-CT02-TRNID-LAST PIC X(16)}, line 74.
         */
        public static final int TRNID_LAST_LENGTH = 16;

        /**
         * {@code 10 CDEMO-CT02-PAGE-NUM PIC 9(08)}, line 75.
         */
        public static final int PAGE_NUM_LENGTH = 8;

        /**
         * {@code 10 CDEMO-CT02-NEXT-PAGE-FLG PIC X(01)}, line 76.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * {@code 10 CDEMO-CT02-TRN-SEL-FLG PIC X(01)}, line 79.
         */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /**
         * {@code 10 CDEMO-CT02-TRN-SELECTED PIC X(16)}, line 80.
         */
        public static final int TRN_SELECTED_LENGTH = 16;

        /**
         * 58 - the length of {@code CDEMO-CT02-INFO}, written as the explicit sum of its six items so the
         * addition is reviewable against lines 73 to 80.
         */
        public static final int LENGTH = TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH + PAGE_NUM_LENGTH
                + NEXT_PAGE_FLG_LENGTH + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COTRN02C.cbl:77}.
         */
        public static final String NEXT_PAGE_YES_VALUE = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, line 78.
         */
        public static final String NEXT_PAGE_NO_VALUE = "N";

        /**
         * The total commarea this program passes: {@code 160 + 58 = 218} bytes.
         */
        public static final int PASSED_COMMAREA_LENGTH = NavigationContext.COMMAREA_LENGTH + LENGTH;

        private String trnidFirst = spaces(TRNID_FIRST_LENGTH);
        private String trnidLast = spaces(TRNID_LAST_LENGTH);
        private int pageNum;
        private String nextPageFlg = NEXT_PAGE_NO_VALUE;
        private String trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
        private String trnSelected = spaces(TRN_SELECTED_LENGTH);

        /**
         * Creates the cursor in its declared initial state: the two identifiers and both flags at spaces,
         * the page number at zero, and {@code CDEMO-CT02-NEXT-PAGE-FLG} at {@code 'N'} per its
         * {@code VALUE 'N'} clause on line 76 - so {@link #isNextPageNo()} is true and
         * {@link #isNextPageYes()} is false on a fresh instance.
         */
        public Ct02Info() {
        }

        /**
         * {@code CDEMO-CT02-TRNID-FIRST} - the first transaction identifier on the current page, untrimmed
         * and exactly 16 characters.
         *
         * @return the identifier, or spaces
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Stores {@code CDEMO-CT02-TRNID-FIRST} through the {@code PIC X(16)} move rule.
         *
         * @param trnidFirst the identifier; padded or truncated on the right to 16 characters
         * @throws NullPointerException if {@code trnidFirst} is {@code null}
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst =
                    movePicX(trnidFirst, TRNID_FIRST_LENGTH, "CDEMO-CT02-TRNID-FIRST");
        }

        /**
         * {@code CDEMO-CT02-TRNID-LAST} - the last transaction identifier on the current page, untrimmed
         * and exactly 16 characters.
         *
         * @return the identifier, or spaces
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Stores {@code CDEMO-CT02-TRNID-LAST} through the {@code PIC X(16)} move rule.
         *
         * @param trnidLast the identifier; padded or truncated on the right to 16 characters
         * @throws NullPointerException if {@code trnidLast} is {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = movePicX(trnidLast, TRNID_LAST_LENGTH, "CDEMO-CT02-TRNID-LAST");
        }

        /**
         * {@code CDEMO-CT02-PAGE-NUM PIC 9(08)} - the current page number.
         *
         * @return the page number, 0 to 99999999
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Stores {@code CDEMO-CT02-PAGE-NUM}.
         *
         * @param pageNum the page number; must fit the eight declared digits
         * @throws IllegalArgumentException if {@code pageNum} is negative or exceeds eight digits, because
         *     {@code PIC 9(08)} is unsigned and eight digits wide and a value outside that range could not have
         *     been stored by the COBOL either
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), which is "
                        + "unsigned, so it cannot hold " + pageNum);
            }
            if (pageNum > MAX_PAGE_NUM) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), so it cannot "
                        + "hold " + pageNum + "; the largest value it can carry is " + MAX_PAGE_NUM);
            }
            this.pageNum = pageNum;
        }

        private static final int MAX_PAGE_NUM = 99_999_999;

        /**
         * {@code CDEMO-CT02-NEXT-PAGE-FLG} - the one-character next-page flag.
         *
         * @return {@code "Y"}, {@code "N"} or any other single character the caller stored
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Stores {@code CDEMO-CT02-NEXT-PAGE-FLG} through the {@code PIC X(01)} move rule.
         *
         * @param nextPageFlg the flag; padded or truncated on the right to one character
         * @throws NullPointerException if {@code nextPageFlg} is {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg =
                    movePicX(nextPageFlg, NEXT_PAGE_FLG_LENGTH, "CDEMO-CT02-NEXT-PAGE-FLG");
        }

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code app/cbl/COTRN02C.cbl:77}.
         *
         * @return {@code true} when the flag holds {@code 'Y'}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES_VALUE.equals(nextPageFlg);
        }

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, line 78.
         *
         * @return {@code true} when the flag holds {@code 'N'}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO_VALUE.equals(nextPageFlg);
        }

        /**
         * Reproduces {@code SET NEXT-PAGE-YES TO TRUE} by storing the condition's declared literal.
         */
        @JsonIgnore
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES_VALUE;
        }

        /**
         * Reproduces {@code SET NEXT-PAGE-NO TO TRUE} by storing the condition's declared literal.
         */
        @JsonIgnore
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO_VALUE;
        }

        /**
         * {@code CDEMO-CT02-TRN-SEL-FLG} - the one-character selection flag.
         *
         * @return the flag, or a space
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Stores {@code CDEMO-CT02-TRN-SEL-FLG} through the {@code PIC X(01)} move rule.
         *
         * @param trnSelFlg the flag; padded or truncated on the right to one character
         * @throws NullPointerException if {@code trnSelFlg} is {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = movePicX(trnSelFlg, TRN_SEL_FLG_LENGTH, "CDEMO-CT02-TRN-SEL-FLG");
        }

        /**
         * {@code CDEMO-CT02-TRN-SELECTED} - the identifier the operator selected on the calling screen,
         * untrimmed and exactly 16 characters.
         *
         * @return the selected identifier, or spaces
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Stores {@code CDEMO-CT02-TRN-SELECTED} through the {@code PIC X(16)} move rule.
         *
         * @param trnSelected the identifier; padded or truncated on the right to 16 characters
         * @throws NullPointerException if {@code trnSelected} is {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected =
                    movePicX(trnSelected, TRN_SELECTED_LENGTH, "CDEMO-CT02-TRN-SELECTED");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ct02Info that)) {
                return false;
            }
            return pageNum == that.pageNum
                    && trnidFirst.equals(that.trnidFirst)
                    && trnidLast.equals(that.trnidLast)
                    && nextPageFlg.equals(that.nextPageFlg)
                    && trnSelFlg.equals(that.trnSelFlg)
                    && trnSelected.equals(that.trnSelected);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg,
                    trnSelected);
        }

        @Override
        public String toString() {
            return "Ct02Info[CDEMO-CT02-TRNID-FIRST='" + trnidFirst
                    + "', CDEMO-CT02-TRNID-LAST='" + trnidLast
                    + "', CDEMO-CT02-PAGE-NUM=" + pageNum
                    + ", CDEMO-CT02-NEXT-PAGE-FLG='" + nextPageFlg
                    + "', CDEMO-CT02-TRN-SEL-FLG='" + trnSelFlg
                    + "', CDEMO-CT02-TRN-SELECTED='" + trnSelected + "']";
        }
    }

    private String trnnameo = ScreenFieldImage.unpainted(TRNNAMEO_LENGTH);
    private String title01o = ScreenFieldImage.unpainted(TITLE01O_LENGTH);
    private String curdateo = ScreenFieldImage.unpainted(CURDATEO_LENGTH);
    private String pgmnameo = ScreenFieldImage.unpainted(PGMNAMEO_LENGTH);
    private String title02o = ScreenFieldImage.unpainted(TITLE02O_LENGTH);
    private String curtimeo = ScreenFieldImage.unpainted(CURTIMEO_LENGTH);
    private String actidino = ScreenFieldImage.unpainted(ACTIDINO_LENGTH);
    private String cardnino = ScreenFieldImage.unpainted(CARDNINO_LENGTH);
    private String ttypcdo = ScreenFieldImage.unpainted(TTYPCDO_LENGTH);
    private String tcatcdo = ScreenFieldImage.unpainted(TCATCDO_LENGTH);
    private String trnsrco = ScreenFieldImage.unpainted(TRNSRCO_LENGTH);
    private String tdesco = ScreenFieldImage.unpainted(TDESCO_LENGTH);
    private String trnamto = ScreenFieldImage.unpainted(TRNAMTO_LENGTH);
    private String torigdto = ScreenFieldImage.unpainted(TORIGDTO_LENGTH);
    private String tprocdto = ScreenFieldImage.unpainted(TPROCDTO_LENGTH);
    private String mido = ScreenFieldImage.unpainted(MIDO_LENGTH);
    private String mnameo = ScreenFieldImage.unpainted(MNAMEO_LENGTH);
    private String mcityo = ScreenFieldImage.unpainted(MCITYO_LENGTH);
    private String mzipo = ScreenFieldImage.unpainted(MZIPO_LENGTH);
    private String confirmo = ScreenFieldImage.unpainted(CONFIRMO_LENGTH);
    private String errmsgo = ScreenFieldImage.unpainted(ERRMSGO_LENGTH);

    private final Map<ScreenField, FieldMetadata> metadata = newMetadataMap();

    private String nextProgram = spaces(NavigationContext.TO_PROGRAM_LENGTH);
    private String nextMapset = MAPSET_NAME;
    private String nextMap = MAP_NAME;

    private NavigationContext navigationContext = NavigationContext.empty();
    private Ct02Info ct02Info = new Ct02Info();

    /**
     * Creates a response whose 21 payload items are all spaces, whose 84 attribute items are all
     * {@link BmsAttributes#DFHDFCOL}, whose navigation targets name this screen, and whose commarea and
     * cursor are in their declared initial states.
     */
    public TransactionViewResponse() {
    }

    private static Map<ScreenField, FieldMetadata> newMetadataMap() {
        Map<ScreenField, FieldMetadata> created = new EnumMap<>(ScreenField.class);
        for (ScreenField field : ScreenField.values()) {
            created.put(field, new FieldMetadata());
        }
        return created;
    }

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown in the header.
     *
     * @return four characters, untrimmed
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return trnnameo;
    }

    /**
     * Stores {@code TRNNAMEO} through the {@code PIC X(4)} move rule.
     *
     * @param trnnameo the transaction identifier; padded or truncated on the right to 4 characters
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        this.trnnameo = movePicX(trnnameo, TRNNAMEO_LENGTH, "TRNNAMEO");
    }

    /**
     * {@code TITLE01O PIC X(40)} - the first title line.
     *
     * @return forty characters, untrimmed
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return title01o;
    }

    /**
     * Stores {@code TITLE01O} through the {@code PIC X(40)} move rule.
     *
     * @param title01o the title; padded or truncated on the right to 40 characters
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        this.title01o = movePicX(title01o, TITLE01O_LENGTH, "TITLE01O");
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code mm/dd/yy}, matching the map's
     * {@code INITIAL='mm/dd/yy'}.
     *
     * @return eight characters, untrimmed
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return curdateo;
    }

    /**
     * Stores {@code CURDATEO} through the {@code PIC X(8)} move rule.
     *
     * @param curdateo the date image; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        this.curdateo = movePicX(curdateo, CURDATEO_LENGTH, "CURDATEO");
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown in the header.
     *
     * @return eight characters, untrimmed
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return pgmnameo;
    }

    /**
     * Stores {@code PGMNAMEO} through the {@code PIC X(8)} move rule.
     *
     * @param pgmnameo the program name; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        this.pgmnameo = movePicX(pgmnameo, PGMNAMEO_LENGTH, "PGMNAMEO");
    }

    /**
     * {@code TITLE02O PIC X(40)} - the second title line.
     *
     * @return forty characters, untrimmed
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return title02o;
    }

    /**
     * Stores {@code TITLE02O} through the {@code PIC X(40)} move rule.
     *
     * @param title02o the title; padded or truncated on the right to 40 characters
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        this.title02o = movePicX(title02o, TITLE02O_LENGTH, "TITLE02O");
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code hh:mm:ss}, matching the map's
     * {@code INITIAL='hh:mm:ss'}.
     *
     * @return eight characters, untrimmed
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return curtimeo;
    }

    /**
     * Stores {@code CURTIMEO} through the {@code PIC X(8)} move rule.
     *
     * @param curtimeo the time image; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        this.curtimeo = movePicX(curtimeo, CURTIMEO_LENGTH, "CURTIMEO");
    }

    /**
     * {@code ACTIDINO PIC X(11)} - the account identifier echoed back to the screen.
     *
     * @return eleven characters, untrimmed
     */
    @JsonProperty("actidin")
    public String getActidino() {
        return actidino;
    }

    /**
     * Stores {@code ACTIDINO} through the {@code PIC X(11)} move rule.
     *
     * @param actidino the account identifier; padded or truncated on the right to 11 characters
     * @throws NullPointerException if {@code actidino} is {@code null}
     */
    public void setActidino(String actidino) {
        this.actidino = movePicX(actidino, ACTIDINO_LENGTH, "ACTIDINO");
    }

    /**
     * {@code CARDNINO PIC X(16)} - the card number echoed back to the screen.
     *
     * @return sixteen characters, untrimmed
     */
    @JsonProperty("cardnin")
    public String getCardnino() {
        return cardnino;
    }

    /**
     * Stores {@code CARDNINO} through the {@code PIC X(16)} move rule.
     *
     * @param cardnino the card number; padded or truncated on the right to 16 characters
     * @throws NullPointerException if {@code cardnino} is {@code null}
     */
    public void setCardnino(String cardnino) {
        this.cardnino = movePicX(cardnino, CARDNINO_LENGTH, "CARDNINO");
    }

    /**
     * {@code TTYPCDO PIC X(2)} - the transaction type code.
     *
     * @return two characters, untrimmed
     */
    @JsonProperty("ttypcd")
    public String getTtypcdo() {
        return ttypcdo;
    }

    /**
     * Stores {@code TTYPCDO} through the {@code PIC X(2)} move rule.
     *
     * @param ttypcdo the type code; padded or truncated on the right to 2 characters
     * @throws NullPointerException if {@code ttypcdo} is {@code null}
     */
    public void setTtypcdo(String ttypcdo) {
        this.ttypcdo = movePicX(ttypcdo, TTYPCDO_LENGTH, "TTYPCDO");
    }

    /**
     * {@code TCATCDO PIC X(4)} - the transaction category code.
     *
     * @return four characters, untrimmed
     */
    @JsonProperty("tcatcd")
    public String getTcatcdo() {
        return tcatcdo;
    }

    /**
     * Stores {@code TCATCDO} through the {@code PIC X(4)} move rule.
     *
     * @param tcatcdo the category code; padded or truncated on the right to 4 characters
     * @throws NullPointerException if {@code tcatcdo} is {@code null}
     */
    public void setTcatcdo(String tcatcdo) {
        this.tcatcdo = movePicX(tcatcdo, TCATCDO_LENGTH, "TCATCDO");
    }

    /**
     * {@code TRNSRCO PIC X(10)} - the transaction source.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("trnsrc")
    public String getTrnsrco() {
        return trnsrco;
    }

    /**
     * Stores {@code TRNSRCO} through the {@code PIC X(10)} move rule.
     *
     * @param trnsrco the source; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code trnsrco} is {@code null}
     */
    public void setTrnsrco(String trnsrco) {
        this.trnsrco = movePicX(trnsrco, TRNSRCO_LENGTH, "TRNSRCO");
    }

    /**
     * {@code TDESCO PIC X(60)} - the transaction description.
     *
     * <p>Sixty characters on the screen against {@code TRAN-DESC PIC X(100)} in the record, so
     * {@code app/cbl/COTRN02C.cbl:486} truncates 100 to 60 on the way out and {@code :455} pads 60 to 100
     * on the way back.
     *
     * @return sixty characters, untrimmed
     */
    @JsonProperty("tdesc")
    public String getTdesco() {
        return tdesco;
    }

    /**
     * Stores {@code TDESCO} through the {@code PIC X(60)} move rule.
     *
     * @param tdesco the description; padded or truncated on the right to 60 characters
     * @throws NullPointerException if {@code tdesco} is {@code null}
     */
    public void setTdesco(String tdesco) {
        this.tdesco = movePicX(tdesco, TDESCO_LENGTH, "TDESCO");
    }

    /**
     * {@code TRNAMTO PIC X(12)} - the transaction amount in its edited form.
     *
     * @return twelve characters, untrimmed
     */
    @JsonProperty("trnamt")
    public String getTrnamto() {
        return trnamto;
    }

    /**
     * Stores {@code TRNAMTO} through the {@code PIC X(12)} move rule.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:340} to {@code :343} is a validation step that runs in the program and
     * reports a message on failure; if this setter rejected a malformed amount, the controller could never
     * store the operator's bad input in order to redisplay it, and the error path would be unreachable.
     *
     * @param trnamto the edited amount; padded or truncated on the right to 12 characters
     * @throws NullPointerException if {@code trnamto} is {@code null}
     */
    public void setTrnamto(String trnamto) {
        this.trnamto = movePicX(trnamto, TRNAMTO_LENGTH, "TRNAMTO");
    }

    /**
     * {@code TORIGDTO PIC X(10)} - the origination date as {@code YYYY-MM-DD}.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("torigdt")
    public String getTorigdto() {
        return torigdto;
    }

    /**
     * Stores {@code TORIGDTO} through the {@code PIC X(10)} move rule.
     *
     * @param torigdto the date image; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code torigdto} is {@code null}
     */
    public void setTorigdto(String torigdto) {
        this.torigdto = movePicX(torigdto, TORIGDTO_LENGTH, "TORIGDTO");
    }

    /**
     * {@code TPROCDTO PIC X(10)} - the processing date as {@code YYYY-MM-DD}, validated the same way as
     * {@link #getTorigdto()} at {@code app/cbl/COTRN02C.cbl:369} to {@code :373} and by
     * {@code CALL 'CSUTLDTC'} at {@code :413}.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("tprocdt")
    public String getTprocdto() {
        return tprocdto;
    }

    /**
     * Stores {@code TPROCDTO} through the {@code PIC X(10)} move rule.
     *
     * @param tprocdto the date image; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code tprocdto} is {@code null}
     */
    public void setTprocdto(String tprocdto) {
        this.tprocdto = movePicX(tprocdto, TPROCDTO_LENGTH, "TPROCDTO");
    }

    /**
     * {@code MIDO PIC X(9)} - the merchant identifier.
     *
     * @return nine characters, untrimmed
     */
    @JsonProperty("mid")
    public String getMido() {
        return mido;
    }

    /**
     * Stores {@code MIDO} through the {@code PIC X(9)} move rule.
     *
     * @param mido the merchant identifier; padded or truncated on the right to 9 characters
     * @throws NullPointerException if {@code mido} is {@code null}
     */
    public void setMido(String mido) {
        this.mido = movePicX(mido, MIDO_LENGTH, "MIDO");
    }

    /**
     * {@code MNAMEO PIC X(30)} - the merchant name.
     *
     * <p>Thirty characters on the screen against {@code TRAN-MERCHANT-NAME PIC X(50)} in the record, so
     * {@code app/cbl/COTRN02C.cbl:490} truncates 50 to 30 on the right.
     *
     * @return thirty characters, untrimmed
     */
    @JsonProperty("mname")
    public String getMnameo() {
        return mnameo;
    }

    /**
     * Stores {@code MNAMEO} through the {@code PIC X(30)} move rule.
     *
     * @param mnameo the merchant name; padded or truncated on the right to 30 characters
     * @throws NullPointerException if {@code mnameo} is {@code null}
     */
    public void setMnameo(String mnameo) {
        this.mnameo = movePicX(mnameo, MNAMEO_LENGTH, "MNAMEO");
    }

    /**
     * {@code MCITYO PIC X(25)} - the merchant city.
     *
     * <p>Twenty-five characters against {@code TRAN-MERCHANT-CITY PIC X(50)}, truncated on the right at
     * {@code app/cbl/COTRN02C.cbl:491}.
     *
     * @return twenty-five characters, untrimmed
     */
    @JsonProperty("mcity")
    public String getMcityo() {
        return mcityo;
    }

    /**
     * Stores {@code MCITYO} through the {@code PIC X(25)} move rule.
     *
     * @param mcityo the merchant city; padded or truncated on the right to 25 characters
     * @throws NullPointerException if {@code mcityo} is {@code null}
     */
    public void setMcityo(String mcityo) {
        this.mcityo = movePicX(mcityo, MCITYO_LENGTH, "MCITYO");
    }

    /**
     * {@code MZIPO PIC X(10)} - the merchant postal code, the same width as
     * {@code TRAN-MERCHANT-ZIP PIC X(10)}; moved at {@code app/cbl/COTRN02C.cbl:492}.
     *
     * @return ten characters, untrimmed
     */
    @JsonProperty("mzip")
    public String getMzipo() {
        return mzipo;
    }

    /**
     * Stores {@code MZIPO} through the {@code PIC X(10)} move rule.
     *
     * @param mzipo the postal code; padded or truncated on the right to 10 characters
     * @throws NullPointerException if {@code mzipo} is {@code null}
     */
    public void setMzipo(String mzipo) {
        this.mzipo = movePicX(mzipo, MZIPO_LENGTH, "MZIPO");
    }

    /**
     * {@code CONFIRMO PIC X(1)} - the add-confirmation flag echoed back to the screen.
     *
     * @return one character, untrimmed
     */
    @JsonProperty("confirm")
    public String getConfirmo() {
        return confirmo;
    }

    /**
     * Stores {@code CONFIRMO} through the {@code PIC X(1)} move rule.
     *
     * @param confirmo the confirmation flag; padded or truncated on the right to 1 character
     * @throws NullPointerException if {@code confirmo} is {@code null}
     */
    public void setConfirmo(String confirmo) {
        this.confirmo = movePicX(confirmo, CONFIRMO_LENGTH, "CONFIRMO");
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line on row 23, painted
     * {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     *
     * @return seventy-eight characters, untrimmed - a message shorter than the field is space-padded and
     *     stays that way through a JSON round trip
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return errmsgo;
    }

    /**
     * Stores {@code ERRMSGO} through the {@code PIC X(78)} move rule.
     *
     * <p>{@code WS-MESSAGE} is {@code PIC X(80)} at {@code :38}, so the move discards its last two
     * characters; passing an 80-character message here reproduces that truncation exactly.
     *
     * @param errmsgo the message; padded or truncated on the right to 78 characters
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        this.errmsgo = movePicX(errmsgo, ERRMSGO_LENGTH, "ERRMSGO");
    }

    /**
     * The current value of a field's {@code xxxO} payload item.
     *
     * @param field the field to read
     * @return the value at exactly {@link ScreenField#width()} characters, untrimmed
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public String getOutputItem(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return switch (field) {
            case TRNNAME -> trnnameo;
            case TITLE01 -> title01o;
            case CURDATE -> curdateo;
            case PGMNAME -> pgmnameo;
            case TITLE02 -> title02o;
            case CURTIME -> curtimeo;
            case ACTIDIN -> actidino;
            case CARDNIN -> cardnino;
            case TTYPCD -> ttypcdo;
            case TCATCD -> tcatcdo;
            case TRNSRC -> trnsrco;
            case TDESC -> tdesco;
            case TRNAMT -> trnamto;
            case TORIGDT -> torigdto;
            case TPROCDT -> tprocdto;
            case MID -> mido;
            case MNAME -> mnameo;
            case MCITY -> mcityo;
            case MZIP -> mzipo;
            case CONFIRM -> confirmo;
            case ERRMSG -> errmsgo;
        };
    }

    /**
     * Stores a field's {@code xxxO} payload item through the {@code PIC X} move rule for its declared
     * width.
     *
     * @param field the field to write
     * @param value the value; padded or truncated on the right to the field's declared width
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    @JsonIgnore
    public void setOutputItem(ScreenField field, String value) {
        Objects.requireNonNull(field, "A screen field is required");
        switch (field) {
            case TRNNAME -> setTrnnameo(value);
            case TITLE01 -> setTitle01o(value);
            case CURDATE -> setCurdateo(value);
            case PGMNAME -> setPgmnameo(value);
            case TITLE02 -> setTitle02o(value);
            case CURTIME -> setCurtimeo(value);
            case ACTIDIN -> setActidino(value);
            case CARDNIN -> setCardnino(value);
            case TTYPCD -> setTtypcdo(value);
            case TCATCD -> setTcatcdo(value);
            case TRNSRC -> setTrnsrco(value);
            case TDESC -> setTdesco(value);
            case TRNAMT -> setTrnamto(value);
            case TORIGDT -> setTorigdto(value);
            case TPROCDT -> setTprocdto(value);
            case MID -> setMido(value);
            case MNAME -> setMnameo(value);
            case MCITY -> setMcityo(value);
            case MZIP -> setMzipo(value);
            case CONFIRM -> setConfirmo(value);
            case ERRMSG -> setErrmsgo(value);
        }
    }

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of one field.
     *
     * @param field the field whose attribute items are wanted
     * @return the live quad, never {@code null} - every field has one from construction
     * @throws NullPointerException if {@code field} is {@code null}
     */
    @JsonIgnore
    public FieldMetadata getMetadata(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return metadata.get(field);
    }

    @JsonIgnore
    public Map<ScreenField, FieldMetadata> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    /**
     * Applies a highlight decision produced by {@link FieldAttributeSetter}, reproducing
     * {@code app/cpy/CSSETATY.cpy}: The two writes are applied only when the decision says they were made,
     * so an {@link FieldHighlight#untouched()} decision - which is what
     * {@link FieldAttributeSetter#resolve} returns for a valid field, or for any field while the program is
     * in {@code ENTER} context - leaves this object co.
     *
     * @param highlight the resolved decision
     * @return {@code true} if anything was written, {@code false} for an untouched decision
     * @throws NullPointerException if {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision names a field this map does not declare
     */
    public boolean applyHighlight(FieldHighlight highlight) {
        Objects.requireNonNull(highlight, "A resolved FieldHighlight is required; "
                + "FieldAttributeSetter.resolve(...) owns the decision, this method only applies it");
        if (highlight.untouched()) {
            return false;
        }
        ScreenField field = ScreenField.ofLabel(highlight.screenFieldPrefix());
        boolean written = false;
        if (highlight.colourItemAssigned()) {
            getMetadata(field).setColour(highlight.colourItemValue());
            written = true;
        }
        if (highlight.outputItemAssigned()) {
            setOutputItem(field, highlight.outputItemValue());
            written = true;
        }
        return written;
    }

    /**
     * Restores all 21 attribute quads to {@link BmsAttributes#DFHDFCOL}, discarding every highlight.
     */
    public void resetMetadata() {
        for (FieldMetadata quad : metadata.values()) {
            quad.reset();
        }
    }

    /**
     * The program the client should call next - what {@code app/cbl/COTRN02C.cbl:509}'s
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} transferred to.
     *
     * @return eight characters, untrimmed
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Stores the next program through the {@code PIC X(8)} move rule.
     *
     * @param nextProgram the program name; padded or truncated on the right to 8 characters
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram =
                movePicX(nextProgram, NavigationContext.TO_PROGRAM_LENGTH, "CDEMO-TO-PROGRAM");
    }

    /**
     * The mapset of the next screen - {@link #MAPSET_NAME} while the conversation stays here.
     *
     * @return seven characters, untrimmed
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Stores the next mapset through the {@code PIC X(7)} move rule.
     *
     * @param nextMapset the mapset name; padded or truncated on the right to 7 characters
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset =
                movePicX(nextMapset, NavigationContext.LAST_MAPSET_LENGTH, "CDEMO-LAST-MAPSET");
    }

    /**
     * The map of the next screen - {@link #MAP_NAME} while the conversation stays here.
     *
     * @return seven characters, untrimmed
     */
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Stores the next map through the {@code PIC X(7)} move rule.
     *
     * @param nextMap the map name; padded or truncated on the right to 7 characters
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = movePicX(nextMap, NavigationContext.LAST_MAP_LENGTH, "CDEMO-LAST-MAP");
    }

    /**
     * {@code CARDDEMO-COMMAREA} - the 160-byte shared commarea from {@code app/cpy/COCOM01Y.cpy}, echoed so
     * the client can send it back on the next call.
     *
     * @return the commarea, never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext =
                Objects.requireNonNull(navigationContext, "A NavigationContext is required; use "
                        + "NavigationContext.empty() for the initial state rather than null, because "
                        + "COBOL has no null commarea");
    }

    /**
     * {@code CDEMO-CT02-INFO} - this program's 58-byte commarea extension, echoed so the pagination and
     * selection cursor survives without server-side state.
     *
     * @return the cursor, never {@code null}
     */
    public Ct02Info getCt02Info() {
        return ct02Info;
    }

    public void setCt02Info(Ct02Info ct02Info) {
        this.ct02Info = Objects.requireNonNull(ct02Info, "A Ct02Info is required; use "
                + "new Ct02Info() for the initial state rather than null");
    }

    /**
     * Reproduces {@code POPULATE-HEADER-INFO}, {@code app/cbl/COTRN02C.cbl:552} to {@code :571}, which is
     * performed at the top of {@code SEND-TRNADD-SCREEN} before every {@code SEND}: The titles come from
     * {@link ScreenTitles}, which carries {@code app/cpy/COTTL01Y.cpy}'s literals byte for byte at exactly
     * the 40 characters {@code TITLE01O} and {@code TITLE02O} declare.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:554} performs the corresponding
     * {@code MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA}, and the two derived images it then builds are
     * exactly {@code DateHeader}'s {@code mm/dd/yy} and {@code hh:mm:ss} views, both eight characters wide.
     *
     * @param dateHeader the date and time header to take {@code mm/dd/yy} and {@code hh:mm:ss} from
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void populateHeaderInfo(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required; COTRN02C:554 performs "
                + "MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA, so the caller supplies the "
                + "already-captured date and time rather than this method reading a clock");
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
        setTrnnameo(TRANSACTION_ID);
        setPgmnameo(PROGRAM_ID);
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * Reproduces {@code app/cbl/COTRN02C.cbl:112} to {@code :113}
     * {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO OF COTRN2AO} - the clearing of the error line at the top of
     * {@code MAIN-PARA}.
     */
    public void clearErrmsgo() {
        setErrmsgo(spaces(ERRMSGO_LENGTH));
    }

    /**
     * Reproduces the {@code WHEN OTHER} branch of {@code app/cbl/COTRN02C.cbl:148} to {@code :151} - an
     * unrecognised AID - by placing {@code CCDA-MSG-INVALID-KEY} on the error line:
     * {@code CCDA-MSG-INVALID-KEY} is {@code PIC X(50)} in {@code app/cpy/CSMSG01Y.cpy}, so routing it
     * through the {@code PIC X(78)} move rule space-pads it to 78 - exactly as the two-step move through
     * {@code WS-MESSAGE PIC X(80)}
     */
    public void setErrmsgoInvalidKey() {
        setErrmsgo(SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * Reproduces {@code app/cbl/COTRN02C.cbl:122} {@code MOVE LOW-VALUES TO COTRN2AO} - the whole output
     * group set to low values on the transition from {@code ENTER} into {@code REENTER}.
     */
    public void moveLowValuesToOutputMap() {
        for (ScreenField field : ScreenField.values()) {
            setOutputItem(field, lowValues(field.width()));
        }
        resetMetadata();
    }

    /**
     * Sets all 21 payload items to spaces, leaving the attribute items untouched.
     */
    public void moveSpacesToOutputMap() {
        for (ScreenField field : ScreenField.values()) {
            setOutputItem(field, spaces(field.width()));
        }
    }

    /**
     * Renders this response as the 555-byte {@code COTRN2AO} group image.
     *
     * @param charset the code page to encode in; named explicitly by the caller, never defaulted
     * @return a new array of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required; a COTRN2AO image is bytes in a "
                + "specific code page and must never be encoded with the platform default");
        FixedWidthRecord record = FixedWidthRecord.forLayout(LAYOUT, charset);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes all 105 addressable items of this response into an existing record laid out by
     * {@link #LAYOUT}.
     *
     * @param record the record to write into; must be laid out by {@link #LAYOUT}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A FixedWidthRecord is required");
        requireGroupLength(record.recordLength());
        for (ScreenField field : ScreenField.values()) {
            FieldSpans spans = FIELD_SPANS.get(field);
            FieldMetadata quad = metadata.get(field);
            record.writeSpanBytes(spans.colour(), new byte[] {quad.getColour()});
            record.writeSpanBytes(spans.ps(), new byte[] {quad.getProgrammedSymbols()});
            record.writeSpanBytes(spans.highlight(), new byte[] {quad.getHighlight()});
            record.writeSpanBytes(spans.validn(), new byte[] {quad.getValidation()});
            record.writeSpan(spans.output(), getOutputItem(field));
        }
    }

    /**
     * Rebuilds a response from a 555-byte {@code COTRN2AO} group image.
     *
     * @param image exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @param charset the code page {@code image} is encoded in; named explicitly, never defaulted
     * @return a new response carrying the image's 21 payload items and 84 attribute items
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public static TransactionViewResponse fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "A COTRN2AO group image is required");
        Objects.requireNonNull(charset, "A charset is required; a COTRN2AO image is bytes in a "
                + "specific code page and must never be decoded with the platform default");
        requireGroupLength(image.length);
        FixedWidthRecord record =
                FixedWidthRecord.copyOf(image, SYMBOLIC_MAP_LENGTH, charset);
        TransactionViewResponse response = new TransactionViewResponse();
        response.readFrom(record);
        return response;
    }

    /**
     * Replaces this response's 21 payload items and 84 attribute items with those of a record laid out by
     * {@link #LAYOUT}.
     *
     * @param record the record to read; must be laid out by {@link #LAYOUT}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public void readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A FixedWidthRecord is required");
        requireGroupLength(record.recordLength());
        for (ScreenField field : ScreenField.values()) {
            FieldSpans spans = FIELD_SPANS.get(field);
            FieldMetadata quad = metadata.get(field);
            quad.setColour(record.readSpanBytes(spans.colour())[0]);
            quad.setProgrammedSymbols(record.readSpanBytes(spans.ps())[0]);
            quad.setHighlight(record.readSpanBytes(spans.highlight())[0]);
            quad.setValidation(record.readSpanBytes(spans.validn())[0]);
            setOutputItem(field, record.readSpan(spans.output()));
        }
    }

    private static void requireGroupLength(int length) {
        if (length != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("01 COTRN2AO REDEFINES COTRN2AI is "
                    + SYMBOLIC_MAP_LENGTH + " bytes (" + TIOAPFX_LENGTH + " TIOAPFX + "
                    + FIELD_COUNT + " * " + PER_FIELD_PREFIX_LENGTH + " attribute prefix + "
                    + PAYLOAD_LENGTH + " payload) but " + length + " byte(s) were supplied");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionViewResponse that)) {
            return false;
        }
        return trnnameo.equals(that.trnnameo)
                && title01o.equals(that.title01o)
                && curdateo.equals(that.curdateo)
                && pgmnameo.equals(that.pgmnameo)
                && title02o.equals(that.title02o)
                && curtimeo.equals(that.curtimeo)
                && actidino.equals(that.actidino)
                && cardnino.equals(that.cardnino)
                && ttypcdo.equals(that.ttypcdo)
                && tcatcdo.equals(that.tcatcdo)
                && trnsrco.equals(that.trnsrco)
                && tdesco.equals(that.tdesco)
                && trnamto.equals(that.trnamto)
                && torigdto.equals(that.torigdto)
                && tprocdto.equals(that.tprocdto)
                && mido.equals(that.mido)
                && mnameo.equals(that.mnameo)
                && mcityo.equals(that.mcityo)
                && mzipo.equals(that.mzipo)
                && confirmo.equals(that.confirmo)
                && errmsgo.equals(that.errmsgo)
                && metadata.equals(that.metadata)
                && nextProgram.equals(that.nextProgram)
                && nextMapset.equals(that.nextMapset)
                && nextMap.equals(that.nextMap)
                && navigationContext.equals(that.navigationContext)
                && ct02Info.equals(that.ct02Info);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trnnameo, title01o, curdateo, pgmnameo, title02o, curtimeo,
                actidino, cardnino, ttypcdo, tcatcdo, trnsrco, tdesco);
        result = 31 * result + Objects.hash(trnamto, torigdto, tprocdto, mido, mnameo, mcityo,
                mzipo, confirmo, errmsgo);
        return 31 * result + Objects.hash(metadata, nextProgram, nextMapset, nextMap,
                navigationContext, ct02Info);
    }

    /**
     * A diagnostic rendering that quotes every payload item so trailing spaces are visible, because a width
     * defect is otherwise invisible in a log.
     *
     * @return a single-line description; not a wire format and not parsed by anything
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(512);
        text.append("TransactionViewResponse[").append(TRANSACTION_ID).append('/')
                .append(PROGRAM_ID).append(' ').append(MAPSET_NAME).append('.').append(MAP_NAME);
        for (ScreenField field : ScreenField.values()) {
            text.append(", ").append(field.outputItemName()).append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(field), getOutputItem(field)))
                    .append('\'');
        }
        text.append(", nextProgram='").append(nextProgram)
                .append("', nextMapset='").append(nextMapset)
                .append("', nextMap='").append(nextMap)
                .append("', ").append(ct02Info)
                .append(']');
        return text.toString();
    }

    static SensitiveDiagnostics.Disclosure disclosureOf(ScreenField field) {
        if (field == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        return switch (field) {
            case ACTIDIN -> SensitiveDiagnostics.Disclosure.IDENTIFIER;
            case CARDNIN -> SensitiveDiagnostics.Disclosure.PAN;
            default -> SensitiveDiagnostics.Disclosure.PLAIN;
        };
    }

}
