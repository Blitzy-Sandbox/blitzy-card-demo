package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.DiagnosticText;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.ScreenFieldImage;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The inbound REST payload of CSD transaction {@code CT02}, program {@code COTRN02C} - a field-for-field
 * projection of the {@code xxxI} items of {@code 01 COTRN2AI} in {@code app/cpy-bms/COTRN02.CPY} and their
 * name-labelled {@code DFHMDF} definitions in {@code app/bms/COTRN02.bms}.
 *
 * <p>The name sets agree exactly, and every BMS {@code LENGTH=} equals its {@code PIC X(n)} - checked field
 * by field, with no exceptions.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class TransactionViewRequest {
    /**
     * The CICS transaction identifier, {@code CT02}.
     */
    public static final String TRANSACTION_ID = "CT02";

    /**
     * The COBOL program projected here, {@code COTRN02C}: {@code app/csd/CARDDEMO.CSD:271}.
     */
    public static final String PROGRAM_NAME = "COTRN02C";

    /**
     * The BMS mapset, {@code COTRN02}: {@code app/csd/CARDDEMO.CSD:153}.
     */
    public static final String MAPSET_NAME = "COTRN02";

    /**
     * The BMS map, {@code COTRN2A}: the {@code DFHMDI} label of {@code app/bms/COTRN02.bms:26}.
     */
    public static final String MAP_NAME = "COTRN2A";

    /**
     * The symbolic-map input group projected here: {@code 01 COTRN2AI}, copybook line 17.
     */
    public static final String SYMBOLIC_MAP_INPUT_GROUP = "COTRN2AI";

    /**
     * The symbolic-map output group, {@code 01 COTRN2AO}, declared at copybook line 145 as
     * {@code REDEFINES COTRN2AI}.
     */
    public static final String SYMBOLIC_MAP_OUTPUT_GROUP = "COTRN2AO";

    /**
     * The leading {@code 02 FILLER PIC X(12)} of {@code 01 COTRN2AI} at copybook line 18.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The width of an {@code xxxL} length item, {@code COMP PIC S9(4)}: a signed binary halfword, two
     * bytes.
     */
    public static final int LENGTH_ITEM_LENGTH = 2;

    /**
     * The width of an {@code xxxF} flag item, {@code PICTURE X}, which {@code xxxA} redefines.
     */
    public static final int FLAG_ITEM_LENGTH = 1;

    /**
     * The width of the {@code 02 FILLER PICTURE X(4)} reserved span that precedes each payload item.
     */
    public static final int RESERVED_FILLER_LENGTH = 4;

    public static final int METADATA_PREFIX_LENGTH =
            LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH + RESERVED_FILLER_LENGTH;

    /**
     * The number of payload fields: {@value #PAYLOAD_FIELD_COUNT}.
     */
    public static final int PAYLOAD_FIELD_COUNT = 21;

    public static final int PAYLOAD_WIDTH_TOTAL = 396;

    public static final int AI_GROUP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + PAYLOAD_FIELD_COUNT * METADATA_PREFIX_LENGTH + PAYLOAD_WIDTH_TOTAL;

    public static final int CURSOR_REQUEST = -1;

    /**
     * {@code TRNNAMEI PIC X(4)}, copybook line 24; {@code TRNNAME DFHMDF LENGTH=4}, mapset line 34.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, copybook line 30; {@code LENGTH=40}, mapset line 38.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, copybook line 36; {@code LENGTH=8}, mapset line 47.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, copybook line 42; {@code LENGTH=8}, mapset line 57.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}, copybook line 48; {@code LENGTH=40}, mapset line 61.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, copybook line 54; {@code LENGTH=8}, mapset line 70.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code ACTIDINI PIC X(11)}, copybook line 60; {@code LENGTH=11}, mapset line 85.
     */
    public static final int ACTIDIN_LENGTH = 11;

    /**
     * {@code CARDNINI PIC X(16)}, copybook line 66; {@code LENGTH=16}, mapset line 104.
     */
    public static final int CARDNIN_LENGTH = 16;

    /**
     * {@code TTYPCDI PIC X(2)}, copybook line 72; {@code LENGTH=2}, mapset line 122.
     */
    public static final int TTYPCD_LENGTH = 2;

    /**
     * {@code TCATCDI PIC X(4)}, copybook line 78; {@code LENGTH=4}, mapset line 135.
     */
    public static final int TCATCD_LENGTH = 4;

    /**
     * {@code TRNSRCI PIC X(10)}, copybook line 84; {@code LENGTH=10}, mapset line 148.
     */
    public static final int TRNSRC_LENGTH = 10;

    /**
     * {@code TDESCI PIC X(60)}, copybook line 90; {@code LENGTH=60}, mapset line 161.
     */
    public static final int TDESC_LENGTH = 60;

    /**
     * {@code TRNAMTI PIC X(12)}, copybook line 96; {@code LENGTH=12}, mapset line 174.
     */
    public static final int TRNAMT_LENGTH = 12;

    /**
     * {@code TORIGDTI PIC X(10)}, copybook line 102; {@code LENGTH=10}, mapset line 187.
     */
    public static final int TORIGDT_LENGTH = 10;

    /**
     * {@code TPROCDTI PIC X(10)}, copybook line 108; {@code LENGTH=10}, mapset line 200.
     */
    public static final int TPROCDT_LENGTH = 10;

    /**
     * {@code MIDI PIC X(9)}, copybook line 114; {@code LENGTH=9}, mapset line 228.
     */
    public static final int MID_LENGTH = 9;

    /**
     * {@code MNAMEI PIC X(30)}, copybook line 120; {@code LENGTH=30}, mapset line 241.
     */
    public static final int MNAME_LENGTH = 30;

    /**
     * {@code MCITYI PIC X(25)}, copybook line 126; {@code LENGTH=25}, mapset line 254.
     */
    public static final int MCITY_LENGTH = 25;

    /**
     * {@code MZIPI PIC X(10)}, copybook line 132; {@code LENGTH=10}, mapset line 267.
     */
    public static final int MZIP_LENGTH = 10;

    /**
     * {@code CONFIRMI PIC X(1)}, copybook line 138; {@code LENGTH=1}, mapset line 281.
     */
    public static final int CONFIRM_LENGTH = 1;

    /**
     * {@code ERRMSGI PIC X(78)}, copybook line 144; {@code LENGTH=78}, mapset line 293, declared
     * {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * Characters in the {@code EIBAID} token carried by {@link #getAid()}: five.
     */
    public static final int AID_LENGTH = 5;

    /**
     * Name of the pseudo-conversational key indication, the CICS {@code EIBAID} field.
     */
    public static final String AID_FIELD = "EIBAID";

    /**
     * The twenty-one screen fields of mapset {@code COTRN02}, in {@code 01 COTRN2AI} declaration order.
     */
    public enum ScreenField {
        TRNNAME("TRNNAME", TRNNAME_LENGTH, 12, false),

        TITLE01("TITLE01", TITLE01_LENGTH, 23, false),

        CURDATE("CURDATE", CURDATE_LENGTH, 70, false),

        PGMNAME("PGMNAME", PGMNAME_LENGTH, 85, false),

        TITLE02("TITLE02", TITLE02_LENGTH, 100, false),

        CURTIME("CURTIME", CURTIME_LENGTH, 147, false),

        ACTIDIN("ACTIDIN", ACTIDIN_LENGTH, 162, true),

        CARDNIN("CARDNIN", CARDNIN_LENGTH, 180, true),

        TTYPCD("TTYPCD", TTYPCD_LENGTH, 203, true),

        TCATCD("TCATCD", TCATCD_LENGTH, 212, true),

        TRNSRC("TRNSRC", TRNSRC_LENGTH, 223, true),

        TDESC("TDESC", TDESC_LENGTH, 240, true),

        TRNAMT("TRNAMT", TRNAMT_LENGTH, 307, true),

        TORIGDT("TORIGDT", TORIGDT_LENGTH, 326, true),

        TPROCDT("TPROCDT", TPROCDT_LENGTH, 343, true),

        MID("MID", MID_LENGTH, 360, true),

        MNAME("MNAME", MNAME_LENGTH, 376, true),

        MCITY("MCITY", MCITY_LENGTH, 413, true),

        MZIP("MZIP", MZIP_LENGTH, 445, true),

        CONFIRM("CONFIRM", CONFIRM_LENGTH, 462, true),

        ERRMSG("ERRMSG", ERRMSG_LENGTH, 470, false);

        static {
            int expected = TIOAPFX_PREFIX_LENGTH;
            int widths = 0;
            for (ScreenField field : values()) {
                if (field.groupOffset != expected) {
                    throw new IllegalStateException("Screen field " + field.name() + " declares group "
                            + "offset " + field.groupOffset + " but the preceding fields end at byte "
                            + expected + "; every byte of the " + AI_GROUP_LENGTH + "-byte COTRN2AI "
                            + "image must be accounted for");
                }
                expected += METADATA_PREFIX_LENGTH + field.length;
                widths += field.length;
            }
            if (values().length != PAYLOAD_FIELD_COUNT) {
                throw new IllegalStateException("Mapset COTRN02 declares " + PAYLOAD_FIELD_COUNT
                        + " name-labelled DFHMDF fields but the table lists " + values().length);
            }
            if (widths != PAYLOAD_WIDTH_TOTAL) {
                throw new IllegalStateException("Declared widths sum to " + widths + " but the copybook "
                        + "sums to " + PAYLOAD_WIDTH_TOTAL);
            }
            if (expected != AI_GROUP_LENGTH) {
                throw new IllegalStateException("The field table spans " + expected + " byte(s) but "
                        + "01 COTRN2AI is " + AI_GROUP_LENGTH + " byte(s) wide");
            }
        }

        private final String label;

        private final int length;

        private final int groupOffset;

        private final boolean unprotected;

        ScreenField(String label, int length, int groupOffset, boolean unprotected) {
            this.label = label;
            this.length = length;
            this.groupOffset = groupOffset;
            this.unprotected = unprotected;
        }

        /**
         * The {@code DFHMDF} label as {@code app/bms/COTRN02.bms} spells it, which is also the stem of all
         * four symbolic-map item names.
         *
         * @return the label, for example {@code TRNAMT}
         */
        public String label() {
            return label;
        }

        /**
         * The declared payload width in bytes, from the {@code xxxI} {@code PICTURE} clause.
         *
         * @return the width, for example {@value #TRNAMT_LENGTH} for {@link #TRNAMT}
         */
        public int length() {
            return length;
        }

        /**
         * The absolute 0-based offset of this field's {@code xxxL} length item, where its
         * {@link #METADATA_PREFIX_LENGTH}-byte metadata prefix begins.
         *
         * @return the group offset within the {@link #AI_GROUP_LENGTH}-byte image
         */
        public int groupOffset() {
            return groupOffset;
        }

        /**
         * The absolute 0-based offset of this field's payload bytes, which is {@link #groupOffset()} plus
         * {@link #METADATA_PREFIX_LENGTH}.
         *
         * @return the payload offset within the {@link #AI_GROUP_LENGTH}-byte image
         */
        public int payloadOffset() {
            return groupOffset + METADATA_PREFIX_LENGTH;
        }

        /**
         * Whether the {@code DFHMDF} declares {@code ATTRB=(...,UNPROT)} and so accepts operator input.
         *
         * @return {@code true} for the fourteen input-capable fields, {@code false} for the six ASKIP
         *     header fields and the ASKIP error line
         */
        public boolean unprotectedField() {
            return unprotected;
        }

        /**
         * The symbolic-map payload item name, verbatim: the label with BMS's {@code I} suffix.
         *
         * @return the {@code xxxI} item name, which is also this field's span name in {@link #AI_LAYOUT}
         *     and the name the parity differ compares by
         */
        public String inputItem() {
            return label + "I";
        }

        /**
         * The symbolic-map output item name: the label with BMS's {@code O} suffix.
         *
         * @return the {@code xxxO} item name
         */
        public String outputItem() {
            return label + "O";
        }

        /**
         * The {@code xxxL} length item name: {@code COMP PIC S9(4)}, metadata, never a payload member.
         *
         * @return the {@code xxxL} item name, for example {@code TRNAMTL}
         */
        public String lengthItem() {
            return label + "L";
        }

        /**
         * The {@code xxxF} flag item name: {@code PICTURE X}, metadata.
         *
         * @return the {@code xxxF} item name, for example {@code TRNAMTF}
         */
        public String flagItem() {
            return label + "F";
        }

        public String attributeItem() {
            return label + "A";
        }

        /**
         * This field's payload span: an alphanumeric {@code PIC X(n)} descriptor named with the verbatim
         * {@code xxxI} item name and anchored at {@link #payloadOffset()}.
         *
         * @return the payload descriptor, the only span of this field that carries data
         */
        public FieldSpan payloadSpan() {
            return FieldSpan.alphanumeric(inputItem(), payloadOffset(), length);
        }

        /**
         * The reserved spans this field's metadata prefix occupies in the group image, in declaration
         * order: the two-byte {@code xxxL} halfword, the one-byte {@code xxxF} flag which {@code xxxA}
         * redefines, and the four-byte {@code FILLER}.
         *
         * @return the three reserved descriptors, never {@code null} and never empty
         */
        public List<FieldSpan> metadataSpans() {
            return List.of(
                    FieldSpan.filler(groupOffset, LENGTH_ITEM_LENGTH),
                    FieldSpan.filler(groupOffset + LENGTH_ITEM_LENGTH, FLAG_ITEM_LENGTH),
                    FieldSpan.filler(groupOffset + LENGTH_ITEM_LENGTH + FLAG_ITEM_LENGTH,
                            RESERVED_FILLER_LENGTH));
        }
    }

    public static final RecordLayout AI_LAYOUT = buildAiLayout();

    private static RecordLayout buildAiLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        for (ScreenField field : ScreenField.values()) {
            spans.addAll(field.metadataSpans());
            spans.add(field.payloadSpan());
        }
        return RecordLayout.of(AI_GROUP_LENGTH, spans.toArray(new FieldSpan[0]));
    }

    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * The {@code xxxL}, {@code xxxF} and {@code xxxA} metadata of one screen field: the length item CICS
     * reports, and the attribute byte the program writes to highlight a field in error.
     *
     * <p>A payload member must trace to a {@code DFHMDF} field definition, and these three items trace to
     * the symbolic map's plumbing instead - which is why the whole carrier is {@link JsonIgnore}d on the
     * instance that holds it.
     */
    public static final class FieldMetadata {
        /**
         * The attribute byte of a field that has neither been transmitted nor highlighted: low-values,
         * which is what CICS leaves in {@code xxxF} for a field the operator did not modify.
         */
        public static final char UNSET_ATTRIBUTE = '\u0000';

        /**
         * The length CICS reports for a field the operator did not enter.
         */
        public static final int NO_INPUT_LENGTH = 0;

        private int length;

        private char attributeByte;

        /**
         * Creates metadata for a field that has not been entered and carries no attribute.
         */
        public FieldMetadata() {
            this.length = NO_INPUT_LENGTH;
            this.attributeByte = UNSET_ATTRIBUTE;
        }

        public FieldMetadata(int length, char attributeByte) {
            setLength(length);
            this.attributeByte = attributeByte;
        }

        /**
         * Copy constructor, so a request can be duplicated without sharing metadata with its original.
         *
         * @param other the metadata to copy, never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldMetadata(FieldMetadata other) {
            Objects.requireNonNull(other, "Metadata to copy is required");
            this.length = other.length;
            this.attributeByte = other.attributeByte;
        }

        public int length() {
            return length;
        }

        public void setLength(int newLength) {
            if (newLength < Short.MIN_VALUE || newLength > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Length item value " + newLength + " does not fit "
                        + "COMP PIC S9(4), a signed binary halfword holding " + Short.MIN_VALUE
                        + " to " + Short.MAX_VALUE);
            }
            this.length = newLength;
        }

        /**
         * Requests the 3270 cursor on this field, reproducing {@code MOVE -1 TO <field>L}.
         */
        public void requestCursor() {
            this.length = CURSOR_REQUEST;
        }

        /**
         * Whether the cursor has been requested on this field.
         *
         * @return {@code true} when the length item holds {@value TransactionViewRequest#CURSOR_REQUEST}
         */
        public boolean isCursorRequested() {
            return length == CURSOR_REQUEST;
        }

        /**
         * Whether CICS reported any input for this field, which is the test a length item exists to
         * support.
         *
         * @return {@code true} when the length item is greater than {@value #NO_INPUT_LENGTH}
         */
        public boolean hasInput() {
            return length > NO_INPUT_LENGTH;
        }

        public char flag() {
            return attributeByte;
        }

        public void setFlag(char newFlag) {
            this.attributeByte = newFlag;
        }

        public char attribute() {
            return attributeByte;
        }

        /**
         * Sets the {@code xxxA} attribute byte, which is the same byte {@link #setFlag(char)} sets.
         *
         * @param newAttribute the byte to store
         */
        public void setAttribute(char newAttribute) {
            this.attributeByte = newAttribute;
        }

        /**
         * Restores the not-entered, not-highlighted state of a freshly received map.
         */
        public void reset() {
            this.length = NO_INPUT_LENGTH;
            this.attributeByte = UNSET_ATTRIBUTE;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldMetadata candidate)) {
                return false;
            }
            return length == candidate.length && attributeByte == candidate.attributeByte;
        }

        @Override
        public int hashCode() {
            return Objects.hash(length, attributeByte);
        }

        @Override
        public String toString() {
            return "FieldMetadata[length=" + length + ", attribute=0x"
                    + Integer.toHexString(attributeByte) + "]";
        }
    }

    /**
     * {@code 05 CDEMO-CT02-INFO}, the {@link #CT02_INFO_LENGTH}-byte pagination cursor that
     * {@code COTRN02C} appends to the shared communication area.
     */
    public static final class Ct02Info {
        /**
         * {@code CDEMO-CT02-TRNID-FIRST PIC X(16)}, {@code COTRN02C:73}.
         */
        public static final int TRNID_FIRST_LENGTH = 16;

        /**
         * {@code CDEMO-CT02-TRNID-LAST PIC X(16)}, {@code COTRN02C:74}.
         */
        public static final int TRNID_LAST_LENGTH = 16;

        /**
         * {@code CDEMO-CT02-PAGE-NUM PIC 9(08)}, {@code COTRN02C:75}.
         */
        public static final int PAGE_NUM_LENGTH = 8;

        /**
         * {@code CDEMO-CT02-NEXT-PAGE-FLG PIC X(01)}, {@code COTRN02C:76}.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * {@code CDEMO-CT02-TRN-SEL-FLG PIC X(01)}, {@code COTRN02C:79}.
         */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /**
         * {@code CDEMO-CT02-TRN-SELECTED PIC X(16)}, {@code COTRN02C:80}.
         */
        public static final int TRN_SELECTED_LENGTH = 16;

        /**
         * The full width of {@code CDEMO-CT02-INFO}: {@value #TRNID_FIRST_LENGTH} +
         * {@value #TRNID_LAST_LENGTH} + {@value #PAGE_NUM_LENGTH} + {@value #NEXT_PAGE_FLG_LENGTH} +
         * {@value #TRN_SEL_FLG_LENGTH} + {@value #TRN_SELECTED_LENGTH} = {@link #CT02_INFO_LENGTH} bytes.
         */
        public static final int CT02_INFO_LENGTH = TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH
                + PAGE_NUM_LENGTH + NEXT_PAGE_FLG_LENGTH + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        public static final int COMMAREA_TOTAL_LENGTH =
                NavigationContext.COMMAREA_LENGTH + CT02_INFO_LENGTH;

        public static final String TRNID_FIRST_FIELD = "CDEMO-CT02-TRNID-FIRST";

        public static final String TRNID_LAST_FIELD = "CDEMO-CT02-TRNID-LAST";

        public static final String PAGE_NUM_FIELD = "CDEMO-CT02-PAGE-NUM";

        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT02-NEXT-PAGE-FLG";

        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT02-TRN-SEL-FLG";

        public static final String TRN_SELECTED_FIELD = "CDEMO-CT02-TRN-SELECTED";

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, {@code COTRN02C:77}.
         */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, {@code COTRN02C:78}.
         */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * The declared {@code VALUE 'N'} of {@code CDEMO-CT02-NEXT-PAGE-FLG}, which is the only
         * {@code VALUE} clause in the group and is applied by {@link #Ct02Info()}.
         */
        public static final String NEXT_PAGE_DEFAULT = NEXT_PAGE_NO;

        /**
         * Absolute 0-based offset of {@code CDEMO-CT02-TRNID-FIRST} within the group.
         */
        public static final int TRNID_FIRST_OFFSET = 0;

        /**
         * Absolute 0-based offset of {@code CDEMO-CT02-TRNID-LAST}.
         */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;

        /**
         * Absolute 0-based offset of {@code CDEMO-CT02-PAGE-NUM}.
         */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;

        /**
         * Absolute 0-based offset of {@code CDEMO-CT02-NEXT-PAGE-FLG}.
         */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;

        /**
         * Absolute 0-based offset of {@code CDEMO-CT02-TRN-SEL-FLG}.
         */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;

        /**
         * Absolute 0-based offset of {@code CDEMO-CT02-TRN-SELECTED}.
         */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * The {@link #CT02_INFO_LENGTH}-byte layout of {@code CDEMO-CT02-INFO}, span names verbatim.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(CT02_INFO_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET, NEXT_PAGE_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET, TRN_SELECTED_LENGTH));

        @Size(max = TRNID_FIRST_LENGTH)
        private String trnidFirst;

        @Size(max = TRNID_LAST_LENGTH)
        private String trnidLast;

        private int pageNum;

        @Size(max = NEXT_PAGE_FLG_LENGTH)
        private String nextPageFlg;

        @Size(max = TRN_SEL_FLG_LENGTH)
        private String trnSelFlg;

        @Size(max = TRN_SELECTED_LENGTH)
        private String trnSelected;

        /**
         * Creates the group in its declared initial state: the two ids, the selection flag and the selected
         * id are spaces, the page number is zero, and {@code CDEMO-CT02-NEXT-PAGE-FLG} carries its declared
         * {@code VALUE 'N'} - so {@link #isNextPageNo()} is true and {@link #isNextPageYes()} is false on a
         * fresh instance, exactly as the copybook specifies.
         */
        public Ct02Info() {
            this.trnidFirst = spaces(TRNID_FIRST_LENGTH);
            this.trnidLast = spaces(TRNID_LAST_LENGTH);
            this.pageNum = 0;
            this.nextPageFlg = NEXT_PAGE_DEFAULT;
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        public Ct02Info(String trnidFirst,
                        String trnidLast,
                        int pageNum,
                        String nextPageFlg,
                        String trnSelFlg,
                        String trnSelected) {
            this.trnidFirst = trnidFirst;
            this.trnidLast = trnidLast;
            setPageNum(pageNum);
            this.nextPageFlg = nextPageFlg;
            this.trnSelFlg = trnSelFlg;
            this.trnSelected = trnSelected;
        }

        /**
         * Copy constructor, so a request can be duplicated without sharing its cursor.
         *
         * @param other the group to copy, never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public Ct02Info(Ct02Info other) {
            Objects.requireNonNull(other, "A CDEMO-CT02-INFO group to copy is required");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * {@code CDEMO-CT02-TRNID-FIRST}, exactly as stored.
         *
         * @return the first transaction id of the page, possibly {@code null}
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Sets {@code CDEMO-CT02-TRNID-FIRST} without trimming or truncating.
         *
         * @param trnidFirst the value to store, may be {@code null}
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = trnidFirst;
        }

        /**
         * {@code CDEMO-CT02-TRNID-LAST}, exactly as stored.
         *
         * @return the last transaction id of the page, possibly {@code null}
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Sets {@code CDEMO-CT02-TRNID-LAST} without trimming or truncating.
         *
         * @param trnidLast the value to store, may be {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = trnidLast;
        }

        /**
         * {@code CDEMO-CT02-PAGE-NUM}.
         *
         * @return the page number, {@code 0} to 99999999
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Sets {@code CDEMO-CT02-PAGE-NUM}.
         *
         * @param pageNum the page number to store
         * @throws IllegalArgumentException if {@code pageNum} is negative or exceeds eight digits, since
         *     {@code PIC 9(08)} is unsigned and eight digits wide
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), which is "
                        + "unsigned, so it cannot hold " + pageNum);
            }
            if (pageNum > 99_999_999) {
                throw new IllegalArgumentException("CDEMO-CT02-PAGE-NUM is PIC 9(08), which holds at "
                        + "most 8 digits, so it cannot hold " + pageNum);
            }
            this.pageNum = pageNum;
        }

        /**
         * {@code CDEMO-CT02-NEXT-PAGE-FLG}, exactly as stored.
         *
         * @return the flag character, possibly {@code null} and not necessarily {@link #NEXT_PAGE_YES} or
         *     {@link #NEXT_PAGE_NO}
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Sets {@code CDEMO-CT02-NEXT-PAGE-FLG} to any character, including one that satisfies neither
         * {@code 88}-level - a space, for instance, which is what a zero-initialised commarea holds.
         *
         * @param nextPageFlg the value to store, may be {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg = nextPageFlg;
        }

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, tested against the stored character.
         *
         * <p>Deliberately not {@code !isNextPageNo()}: the field is {@code PIC X(01)} and the two
         * conditions are not exhaustive, so a blank or unexpected character makes this and
         * {@link #isNextPageNo()} both false.
         *
         * @return {@code true} only when the flag is exactly {@link #NEXT_PAGE_YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, tested against the stored character.
         *
         * @return {@code true} only when the flag is exactly {@link #NEXT_PAGE_NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /**
         * Asserts {@code 88 NEXT-PAGE-YES}, reproducing {@code SET NEXT-PAGE-YES TO TRUE}.
         */
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /**
         * Asserts {@code 88 NEXT-PAGE-NO}, reproducing {@code SET NEXT-PAGE-NO TO TRUE}.
         */
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * {@code CDEMO-CT02-TRN-SEL-FLG}, exactly as stored.
         *
         * @return the selection marker, possibly {@code null}
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Sets {@code CDEMO-CT02-TRN-SEL-FLG} without trimming or truncating.
         *
         * @param trnSelFlg the value to store, may be {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = trnSelFlg;
        }

        /**
         * {@code CDEMO-CT02-TRN-SELECTED}, exactly as stored.
         *
         * @return the selected transaction id, possibly {@code null}
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Sets {@code CDEMO-CT02-TRN-SELECTED} without trimming or truncating.
         *
         * @param trnSelected the value to store, may be {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = trnSelected;
        }

        /**
         * Renders this group as its {@link #CT02_INFO_LENGTH}-byte fixed-width image.
         *
         * @param charset the code page to encode in, named explicitly by the caller and never derived from
         *     the platform
         * @return exactly {@link #CT02_INFO_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] toFixedWidth(Charset charset) {
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            Map<String, String> images = new LinkedHashMap<>();
            images.put(TRNID_FIRST_FIELD, orSpaces(trnidFirst, TRNID_FIRST_LENGTH));
            images.put(TRNID_LAST_FIELD, orSpaces(trnidLast, TRNID_LAST_LENGTH));
            images.put(PAGE_NUM_FIELD, codec.movePic9(pageNum, PAGE_NUM_LENGTH));
            images.put(NEXT_PAGE_FLG_FIELD, orSpaces(nextPageFlg, NEXT_PAGE_FLG_LENGTH));
            images.put(TRN_SEL_FLG_FIELD, orSpaces(trnSelFlg, TRN_SEL_FLG_LENGTH));
            images.put(TRN_SELECTED_FIELD, orSpaces(trnSelected, TRN_SELECTED_LENGTH));
            return codec.serialise(LAYOUT, images);
        }

        /**
         * Reads a {@link #CT02_INFO_LENGTH}-byte image back into a group, leaving every character field
         * untrimmed so that a round trip returns an equal value.
         *
         * @param image exactly {@link #CT02_INFO_LENGTH} bytes
         * @param charset the code page the bytes are in, named explicitly by the caller
         * @return the decoded group, never {@code null}
         * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@link #CT02_INFO_LENGTH} bytes
         */
        public static Ct02Info fromFixedWidth(byte[] image, Charset charset) {
            Objects.requireNonNull(image, "A CDEMO-CT02-INFO image is required");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            Map<String, String> values = codec.deserialise(LAYOUT, image);
            Ct02Info group = new Ct02Info();
            group.trnidFirst = values.get(TRNID_FIRST_FIELD);
            group.trnidLast = values.get(TRNID_LAST_FIELD);
            group.setPageNum(codec.decodePic9AsInt(values.get(PAGE_NUM_FIELD)));
            group.nextPageFlg = values.get(NEXT_PAGE_FLG_FIELD);
            group.trnSelFlg = values.get(TRN_SEL_FLG_FIELD);
            group.trnSelected = values.get(TRN_SELECTED_FIELD);
            return group;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ct02Info candidate)) {
                return false;
            }
            return pageNum == candidate.pageNum
                    && Objects.equals(trnidFirst, candidate.trnidFirst)
                    && Objects.equals(trnidLast, candidate.trnidLast)
                    && Objects.equals(nextPageFlg, candidate.nextPageFlg)
                    && Objects.equals(trnSelFlg, candidate.trnSelFlg)
                    && Objects.equals(trnSelected, candidate.trnSelected);
        }

        @Override
        public int hashCode() {
            return Objects.hash(trnidFirst, trnidLast, pageNum, nextPageFlg, trnSelFlg, trnSelected);
        }

        @Override
        public String toString() {
            return "Ct02Info[" + TRNID_FIRST_FIELD + "=" + trnidFirst
                    + ", " + TRNID_LAST_FIELD + "=" + trnidLast
                    + ", " + PAGE_NUM_FIELD + "=" + pageNum
                    + ", " + NEXT_PAGE_FLG_FIELD + "=" + nextPageFlg
                    + ", " + TRN_SEL_FLG_FIELD + "=" + trnSelFlg
                    + ", " + TRN_SELECTED_FIELD + "=" + trnSelected + "]";
        }
    }

    // One per name-labelled DFHMDF field, in copybook order, each constrained only by its declared width -
    // never more strictly than the COBOL.

    @Size(max = TRNNAME_LENGTH)
    private String trnname;

    @Size(max = TITLE01_LENGTH)
    private String title01;

    @Size(max = CURDATE_LENGTH)
    private String curdate;

    @Size(max = PGMNAME_LENGTH)
    private String pgmname;

    @Size(max = TITLE02_LENGTH)
    private String title02;

    @Size(max = CURTIME_LENGTH)
    private String curtime;

    @Size(max = ACTIDIN_LENGTH)
    private String actidin;

    @Size(max = CARDNIN_LENGTH)
    private String cardnin;

    @Size(max = TTYPCD_LENGTH)
    private String ttypcd;

    @Size(max = TCATCD_LENGTH)
    private String tcatcd;

    @Size(max = TRNSRC_LENGTH)
    private String trnsrc;

    @Size(max = TDESC_LENGTH)
    private String tdesc;

    @Size(max = TRNAMT_LENGTH)
    private String trnamt;

    @Size(max = TORIGDT_LENGTH)
    private String torigdt;

    @Size(max = TPROCDT_LENGTH)
    private String tprocdt;

    @Size(max = MID_LENGTH)
    private String mid;

    @Size(max = MNAME_LENGTH)
    private String mname;

    @Size(max = MCITY_LENGTH)
    private String mcity;

    @Size(max = MZIP_LENGTH)
    private String mzip;

    @Size(max = CONFIRM_LENGTH)
    private String confirm;

    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    private NavigationContext navigationContext;

    @Valid
    private Ct02Info ct02Info;

    @Size(max = AID_LENGTH)
    private String aid;

    @JsonIgnore
    private final Map<ScreenField, FieldMetadata> fieldMetadata = new EnumMap<>(ScreenField.class);

    /**
     * Creates an empty request: every payload field spaces at its declared width, a freshly initialised
     * {@link NavigationContext} in enter context, a {@link Ct02Info} carrying its declared
     * {@code VALUE 'N'}, and metadata reporting no input and no highlight on every field.
     */
    public TransactionViewRequest() {
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.put(field, new FieldMetadata());
            setPayloadValue(field, spaces(field.length()));
        }
        // No communication area: nothing has been passed to a request nobody has filled in yet, which is
        // exactly the EIBCALEN = 0 state COTRN02C.cbl:115 tests for.
        this.navigationContext = null;
        this.ct02Info = new Ct02Info();
        this.aid = spaces(AID_LENGTH);
    }

    /**
     * Creates a request with every payload field supplied explicitly, in copybook declaration order.
     *
     * @param trnname {@code TRNNAMEI PIC X(4)}
     * @param title01 {@code TITLE01I PIC X(40)}
     * @param curdate {@code CURDATEI PIC X(8)}
     * @param pgmname {@code PGMNAMEI PIC X(8)}
     * @param title02 {@code TITLE02I PIC X(40)}
     * @param curtime {@code CURTIMEI PIC X(8)}
     * @param actidin {@code ACTIDINI PIC X(11)}
     * @param cardnin {@code CARDNINI PIC X(16)}
     * @param ttypcd {@code TTYPCDI PIC X(2)}
     * @param tcatcd {@code TCATCDI PIC X(4)}
     * @param trnsrc {@code TRNSRCI PIC X(10)}
     * @param tdesc {@code TDESCI PIC X(60)}
     * @param trnamt {@code TRNAMTI PIC X(12)}, the {@code +99999999.99} edit mask
     * @param torigdt {@code TORIGDTI PIC X(10)}
     * @param tprocdt {@code TPROCDTI PIC X(10)}
     * @param mid {@code MIDI PIC X(9)}
     * @param mname {@code MNAMEI PIC X(30)}
     * @param mcity {@code MCITYI PIC X(25)}
     * @param mzip {@code MZIPI PIC X(10)}
     * @param confirm {@code CONFIRMI PIC X(1)}
     * @param errmsg {@code ERRMSGI PIC X(78)}
     * @param navigationContext the shared commarea, or {@code null} for the {@code EIBCALEN = 0} cold start
     *     - preserved as {@code null}, never completed
     * @param ct02Info the {@code CDEMO-CT02-INFO} cursor; {@code null} yields a fresh group
     */
    public TransactionViewRequest(String trnname,
                                 String title01,
                                 String curdate,
                                 String pgmname,
                                 String title02,
                                 String curtime,
                                 String actidin,
                                 String cardnin,
                                 String ttypcd,
                                 String tcatcd,
                                 String trnsrc,
                                 String tdesc,
                                 String trnamt,
                                 String torigdt,
                                 String tprocdt,
                                 String mid,
                                 String mname,
                                 String mcity,
                                 String mzip,
                                 String confirm,
                                 String errmsg,
                                 NavigationContext navigationContext,
                                 Ct02Info ct02Info) {
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.put(field, new FieldMetadata());
        }
        this.trnname = trnname;
        this.title01 = title01;
        this.curdate = curdate;
        this.pgmname = pgmname;
        this.title02 = title02;
        this.curtime = curtime;
        this.actidin = actidin;
        this.cardnin = cardnin;
        this.ttypcd = ttypcd;
        this.tcatcd = tcatcd;
        this.trnsrc = trnsrc;
        this.tdesc = tdesc;
        this.trnamt = trnamt;
        this.torigdt = torigdt;
        this.tprocdt = tprocdt;
        this.mid = mid;
        this.mname = mname;
        this.mcity = mcity;
        this.mzip = mzip;
        this.confirm = confirm;
        this.errmsg = errmsg;
        this.navigationContext = navigationContext;
        this.ct02Info = ct02Info == null ? new Ct02Info() : ct02Info;
        this.aid = spaces(AID_LENGTH);
    }

    public TransactionViewRequest(TransactionViewRequest other) {
        Objects.requireNonNull(other, "A request to copy is required");
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.put(field, new FieldMetadata(other.metadata(field)));
            setPayloadValue(field, other.payloadValue(field));
        }
        this.navigationContext = other.navigationContext;
        this.ct02Info = new Ct02Info(other.ct02Info);
        this.aid = other.aid;
    }

    /**
     * The current value of one payload field, exactly as stored.
     *
     * @param field the screen field to read, never {@code null}
     * @return the stored value, which may be {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String payloadValue(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return switch (field) {
            case TRNNAME -> trnname;
            case TITLE01 -> title01;
            case CURDATE -> curdate;
            case PGMNAME -> pgmname;
            case TITLE02 -> title02;
            case CURTIME -> curtime;
            case ACTIDIN -> actidin;
            case CARDNIN -> cardnin;
            case TTYPCD -> ttypcd;
            case TCATCD -> tcatcd;
            case TRNSRC -> trnsrc;
            case TDESC -> tdesc;
            case TRNAMT -> trnamt;
            case TORIGDT -> torigdt;
            case TPROCDT -> tprocdt;
            case MID -> mid;
            case MNAME -> mname;
            case MCITY -> mcity;
            case MZIP -> mzip;
            case CONFIRM -> confirm;
            case ERRMSG -> errmsg;
        };
    }

    /**
     * Stores one payload field by enum key, without trimming, padding or truncating.
     *
     * @param field the screen field to write, never {@code null}
     * @param value the value to store, which may be {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public void setPayloadValue(ScreenField field, String value) {
        Objects.requireNonNull(field, "A screen field is required");
        switch (field) {
            case TRNNAME -> trnname = value;
            case TITLE01 -> title01 = value;
            case CURDATE -> curdate = value;
            case PGMNAME -> pgmname = value;
            case TITLE02 -> title02 = value;
            case CURTIME -> curtime = value;
            case ACTIDIN -> actidin = value;
            case CARDNIN -> cardnin = value;
            case TTYPCD -> ttypcd = value;
            case TCATCD -> tcatcd = value;
            case TRNSRC -> trnsrc = value;
            case TDESC -> tdesc = value;
            case TRNAMT -> trnamt = value;
            case TORIGDT -> torigdt = value;
            case TPROCDT -> tprocdt = value;
            case MID -> mid = value;
            case MNAME -> mname = value;
            case MCITY -> mcity = value;
            case MZIP -> mzip = value;
            case CONFIRM -> confirm = value;
            case ERRMSG -> errmsg = value;
        }
    }

    /**
     * The live {@code xxxL} / {@code xxxF} / {@code xxxA} metadata of one field.
     *
     * @param field the screen field, never {@code null}
     * @return that field's metadata carrier, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public FieldMetadata metadata(ScreenField field) {
        Objects.requireNonNull(field, "A screen field is required");
        return fieldMetadata.get(field);
    }

    /**
     * Requests the 3270 cursor on one field, reproducing {@code MOVE -1 TO <field>L}.
     *
     * @param field the field to place the cursor on, never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public void requestCursor(ScreenField field) {
        metadata(field).requestCursor();
    }

    /**
     * Whether the cursor has been requested on one field.
     *
     * @param field the field to test, never {@code null}
     * @return {@code true} when that field's length item holds {@link #CURSOR_REQUEST}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public boolean isCursorRequested(ScreenField field) {
        return metadata(field).isCursorRequested();
    }

    /**
     * Restores every field's metadata to the not-entered, not-highlighted state of a freshly received map.
     */
    public void resetMetadata() {
        for (ScreenField field : ScreenField.values()) {
            fieldMetadata.get(field).reset();
        }
    }

    /**
     * The shared {@value NavigationContext#COMMAREA_LENGTH}-byte communication area, or {@code null} when
     * none was passed.
     *
     * @return the context, or {@code null} for the {@code EIBCALEN = 0} cold start of
     *     {@code app/cbl/COTRN02C.cbl:115}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    /**
     * Replaces the communication area, or removes it.
     *
     * @param navigationContext the context to carry, or {@code null} to carry none
     */
    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COTRN02C.cbl:115}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value Ct02Info#COMMAREA_TOTAL_LENGTH} when a
     * communication area travelled with this request, and {@code 0} when none did.
     *
     * @return {@value Ct02Info#COMMAREA_TOTAL_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? Ct02Info.COMMAREA_TOTAL_LENGTH : 0;
    }

    /**
     * The resolved {@code EIBAID} key indication - the key the operator pressed, which
     * {@code app/cbl/COTRN02C.cbl:133} evaluates.
     *
     * @return the token, {@value #AID_LENGTH} characters wide, spaces when no key has been resolved
     */
    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        if (aid == null) {
            this.aid = spaces(AID_LENGTH);
            return;
        }
        if (aid.length() > AID_LENGTH) {
            throw new IllegalArgumentException(AID_FIELD + " is carried as a PIC X(" + AID_LENGTH
                    + ") token, matching common.PfKeyResolver.AID_TOKEN_LENGTH, but was given "
                    + aid.length() + " character(s). AidKey.token() already space-pads to that width, "
                    + "so a resolved token never overflows it");
        }
        this.aid = aid;
    }

    /**
     * The {@code CDEMO-CT02-INFO} pagination cursor.
     *
     * @return the cursor, never {@code null}
     */
    public Ct02Info getCt02Info() {
        return ct02Info;
    }

    public void setCt02Info(Ct02Info ct02Info) {
        this.ct02Info = ct02Info == null ? new Ct02Info() : ct02Info;
    }

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0}: first entry, so the program paints the screen and validates
     * nothing.
     *
     * @return {@code true} when {@code CDEMO-PGM-CONTEXT} is {@value NavigationContext#PGM_CONTEXT_ENTER}
     */
    @JsonIgnore
    public boolean isEnterContext() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1}: re-entry, so the program validates what was typed.
     *
     * @return {@code true} when {@code CDEMO-PGM-CONTEXT} is {@value NavigationContext#PGM_CONTEXT_REENTER}
     */
    @JsonIgnore
    public boolean isReenterContext() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * {@code TRNNAMEI PIC X(4)}.
     *
     * @return the transaction-name header, possibly {@code null}
     */
    public String getTrnname() {
        return trnname;
    }

    /**
     * Sets {@code TRNNAMEI PIC X(4)}.
     *
     * @param trnname the value to store, may be {@code null}
     */
    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    /**
     * {@code TITLE01I PIC X(40)}.
     *
     * @return the first title header, possibly {@code null}
     */
    public String getTitle01() {
        return title01;
    }

    /**
     * Sets {@code TITLE01I PIC X(40)}.
     *
     * @param title01 the value to store, may be {@code null}
     */
    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    /**
     * {@code CURDATEI PIC X(8)}.
     *
     * @return the current-date header, possibly {@code null}
     */
    public String getCurdate() {
        return curdate;
    }

    /**
     * Sets {@code CURDATEI PIC X(8)}.
     *
     * @param curdate the value to store, may be {@code null}
     */
    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    /**
     * {@code PGMNAMEI PIC X(8)}.
     *
     * @return the program-name header, possibly {@code null}
     */
    public String getPgmname() {
        return pgmname;
    }

    /**
     * Sets {@code PGMNAMEI PIC X(8)}.
     *
     * @param pgmname the value to store, may be {@code null}
     */
    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    /**
     * {@code TITLE02I PIC X(40)}.
     *
     * @return the second title header, possibly {@code null}
     */
    public String getTitle02() {
        return title02;
    }

    /**
     * Sets {@code TITLE02I PIC X(40)}.
     *
     * @param title02 the value to store, may be {@code null}
     */
    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    /**
     * {@code CURTIMEI PIC X(8)}.
     *
     * @return the current-time header, possibly {@code null}
     */
    public String getCurtime() {
        return curtime;
    }

    /**
     * Sets {@code CURTIMEI PIC X(8)}.
     *
     * @param curtime the value to store, may be {@code null}
     */
    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    /**
     * {@code ACTIDINI PIC X(11)}, the account key - eleven characters, carried unmasked.
     *
     * @return the account key, possibly {@code null}
     */
    public String getActidin() {
        return actidin;
    }

    /**
     * Sets {@code ACTIDINI PIC X(11)}.
     *
     * @param actidin the value to store, may be {@code null}
     */
    public void setActidin(String actidin) {
        this.actidin = actidin;
    }

    /**
     * {@code CARDNINI PIC X(16)}, the card key at its full sixteen characters, unmasked and unredacted.
     *
     * @return the card key, possibly {@code null}
     */
    public String getCardnin() {
        return cardnin;
    }

    /**
     * Sets {@code CARDNINI PIC X(16)}.
     *
     * @param cardnin the value to store, may be {@code null}
     */
    public void setCardnin(String cardnin) {
        this.cardnin = cardnin;
    }

    /**
     * {@code TTYPCDI PIC X(2)}.
     *
     * @return the transaction type code, possibly {@code null}
     */
    public String getTtypcd() {
        return ttypcd;
    }

    /**
     * Sets {@code TTYPCDI PIC X(2)}.
     *
     * @param ttypcd the value to store, may be {@code null}
     */
    public void setTtypcd(String ttypcd) {
        this.ttypcd = ttypcd;
    }

    /**
     * {@code TCATCDI PIC X(4)}.
     *
     * @return the transaction category code, possibly {@code null}
     */
    public String getTcatcd() {
        return tcatcd;
    }

    /**
     * Sets {@code TCATCDI PIC X(4)}.
     *
     * @param tcatcd the value to store, may be {@code null}
     */
    public void setTcatcd(String tcatcd) {
        this.tcatcd = tcatcd;
    }

    /**
     * {@code TRNSRCI PIC X(10)}.
     *
     * @return the transaction source, possibly {@code null}
     */
    public String getTrnsrc() {
        return trnsrc;
    }

    /**
     * Sets {@code TRNSRCI PIC X(10)}.
     *
     * @param trnsrc the value to store, may be {@code null}
     */
    public void setTrnsrc(String trnsrc) {
        this.trnsrc = trnsrc;
    }

    /**
     * {@code TDESCI PIC X(60)}.
     *
     * @return the transaction description, possibly {@code null}
     */
    public String getTdesc() {
        return tdesc;
    }

    /**
     * Sets {@code TDESCI PIC X(60)}.
     *
     * @param tdesc the value to store, may be {@code null}
     */
    public void setTdesc(String tdesc) {
        this.tdesc = tdesc;
    }

    /**
     * {@code TRNAMTI PIC X(12)}, the amount under the edit mask {@code +99999999.99}.
     *
     * <p>The record field {@code TRAN-AMT} is {@code PIC S9(09)V99} and holds nine integer digits where
     * this mask holds eight, so the record-to-screen move at {@code :481-485} left-truncates; the field is
     * not widened to hide that.
     *
     * @return the twelve-character edited amount, possibly {@code null}
     */
    public String getTrnamt() {
        return trnamt;
    }

    /**
     * Sets {@code TRNAMTI PIC X(12)}.
     *
     * @param trnamt the value to store, may be {@code null}
     */
    public void setTrnamt(String trnamt) {
        this.trnamt = trnamt;
    }

    /**
     * {@code TORIGDTI PIC X(10)}, the origination date.
     *
     * @return the origination date, possibly {@code null}
     */
    public String getTorigdt() {
        return torigdt;
    }

    /**
     * Sets {@code TORIGDTI PIC X(10)}.
     *
     * @param torigdt the value to store, may be {@code null}
     */
    public void setTorigdt(String torigdt) {
        this.torigdt = torigdt;
    }

    /**
     * {@code TPROCDTI PIC X(10)}, the processing date, validated through {@code CSUTLDTC} at
     * {@code COTRN02C:413} under the same severity rule as {@link #getTorigdt()}.
     *
     * @return the processing date, possibly {@code null}
     */
    public String getTprocdt() {
        return tprocdt;
    }

    /**
     * Sets {@code TPROCDTI PIC X(10)}.
     *
     * @param tprocdt the value to store, may be {@code null}
     */
    public void setTprocdt(String tprocdt) {
        this.tprocdt = tprocdt;
    }

    /**
     * {@code MIDI PIC X(9)}, the merchant id at its full nine characters, unmasked.
     *
     * @return the merchant id, possibly {@code null}
     */
    public String getMid() {
        return mid;
    }

    /**
     * Sets {@code MIDI PIC X(9)}.
     *
     * @param mid the value to store, may be {@code null}
     */
    public void setMid(String mid) {
        this.mid = mid;
    }

    /**
     * {@code MNAMEI PIC X(30)}.
     *
     * @return the merchant name, possibly {@code null}
     */
    public String getMname() {
        return mname;
    }

    /**
     * Sets {@code MNAMEI PIC X(30)}.
     *
     * @param mname the value to store, may be {@code null}
     */
    public void setMname(String mname) {
        this.mname = mname;
    }

    /**
     * {@code MCITYI PIC X(25)}.
     *
     * @return the merchant city, possibly {@code null}
     */
    public String getMcity() {
        return mcity;
    }

    /**
     * Sets {@code MCITYI PIC X(25)}.
     *
     * @param mcity the value to store, may be {@code null}
     */
    public void setMcity(String mcity) {
        this.mcity = mcity;
    }

    /**
     * {@code MZIPI PIC X(10)}.
     *
     * @return the merchant postal code, possibly {@code null}
     */
    public String getMzip() {
        return mzip;
    }

    /**
     * Sets {@code MZIPI PIC X(10)}.
     *
     * @param mzip the value to store, may be {@code null}
     */
    public void setMzip(String mzip) {
        this.mzip = mzip;
    }

    /**
     * {@code CONFIRMI PIC X(1)}, the add-confirmation flag.
     *
     * @return the confirmation flag, possibly {@code null}
     */
    public String getConfirm() {
        return confirm;
    }

    /**
     * Sets {@code CONFIRMI PIC X(1)}.
     *
     * @param confirm the value to store, may be {@code null}
     */
    public void setConfirm(String confirm) {
        this.confirm = confirm;
    }

    /**
     * {@code ERRMSGI PIC X(78)}, the error line.
     *
     * @return the error message, possibly {@code null}
     */
    public String getErrmsg() {
        return errmsg;
    }

    /**
     * Sets {@code ERRMSGI PIC X(78)}.
     *
     * @param errmsg the value to store, may be {@code null}
     */
    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * COBOL {@code SPACES} at a declared width - the value a character field holds after
     * {@code MOVE SPACES}, and the initial state of every payload field of a new request.
     *
     * @param length the declared width, zero or more
     * @return a string of exactly {@code length} spaces
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String spaces(int length) {
        requireNonNegative(length, "SPACES");
        return " ".repeat(length);
    }

    /**
     * COBOL {@code LOW-VALUES} at a declared width: {@code length} bytes of {@code x'00'}.
     *
     * @param length the declared width, zero or more
     * @return a string of exactly {@code length} null characters
     * @throws IllegalArgumentException if {@code length} is negative
     */
    public static String lowValues(int length) {
        // One implementation of the LOW-VALUES image, in common.ScreenFieldImage, so the choice cannot
        // drift back apart across screens. Any width validation above is this method's own contract.
        requireNonNegative(length, "LOW-VALUES");
        return ScreenFieldImage.unpainted(length);
    }

    private static void requireNonNegative(int length, String figurativeConstant) {
        if (length < 0) {
            throw new IllegalArgumentException("A width of " + length + " is not a width; "
                    + figurativeConstant + " can only fill a field of zero or more bytes");
        }
    }

    private static String orSpaces(String value, int length) {
        return value == null ? spaces(length) : value;
    }

    /**
     * The twenty-one payload field images keyed by their verbatim {@code xxxI} item names, each already
     * exactly its declared width.
     *
     * @return a fresh, mutable map of {@value #PAYLOAD_FIELD_COUNT} entries, never {@code null}
     */
    public Map<String, String> payloadImages() {
        Map<String, String> images = new LinkedHashMap<>();
        for (ScreenField field : ScreenField.values()) {
            images.put(field.inputItem(), imageOf(field));
        }
        return images;
    }

    private String imageOf(ScreenField field) {
        String value = payloadValue(field);
        if (value == null) {
            return lowValues(field.length());
        }
        return PICTURE_RULES.movePicX(value, field.length());
    }

    /**
     * Renders this request as the {@link #AI_GROUP_LENGTH}-byte image of {@code 01 COTRN2AI}.
     *
     * @param charset the code page to encode in, named explicitly by the caller and never derived from the
     *     platform
     * @return exactly {@link #AI_GROUP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} cannot encode a digit, a sign overpunch character
     *     or the space to a single byte
     */
    public byte[] toFixedWidth(Charset charset) {
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return codec.serialise(AI_LAYOUT, payloadImages());
    }

    /**
     * Writes this request's twenty-one payload fields into an existing {@code 01 COTRN2AI} work area,
     * leaving the prefix and metadata spans as the area already holds them.
     *
     * @param record the work area, exactly {@link #AI_GROUP_LENGTH} bytes wide
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@link #AI_GROUP_LENGTH} bytes wide
     */
    public void writeInto(FixedWidthRecord record) {
        requireGroupWidth(record);
        for (ScreenField field : ScreenField.values()) {
            record.writeSpan(AI_LAYOUT.span(field.inputItem()), imageOf(field));
        }
    }

    /**
     * Reads a {@link #AI_GROUP_LENGTH}-byte {@code 01 COTRN2AI} image back into a request.
     *
     * @param image exactly {@link #AI_GROUP_LENGTH} bytes
     * @param charset the code page the bytes are in, named explicitly by the caller
     * @return the decoded request, with a fresh {@link NavigationContext} and {@link Ct02Info} since the
     *     group image carries neither
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #AI_GROUP_LENGTH} bytes
     */
    public static TransactionViewRequest fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "A COTRN2AI image is required");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        Map<String, String> values = codec.deserialise(AI_LAYOUT, image);
        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.setPayloadValue(field, values.get(field.inputItem()));
        }
        return request;
    }

    /**
     * Reads this request's twenty-one payload fields out of an existing {@code 01 COTRN2AI} work area.
     *
     * @param record the work area, exactly {@link #AI_GROUP_LENGTH} bytes wide
     * @return the decoded request, never {@code null}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if {@code record} is not {@link #AI_GROUP_LENGTH} bytes wide
     */
    public static TransactionViewRequest readFrom(FixedWidthRecord record) {
        requireGroupWidth(record);
        TransactionViewRequest request = new TransactionViewRequest();
        for (ScreenField field : ScreenField.values()) {
            request.setPayloadValue(field, record.readSpan(AI_LAYOUT.span(field.inputItem())));
        }
        return request;
    }

    private static void requireGroupWidth(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A COTRN2AI work area is required");
        if (record.recordLength() != AI_GROUP_LENGTH) {
            throw new IllegalArgumentException("A COTRN2AI work area is " + AI_GROUP_LENGTH
                    + " byte(s) wide but the supplied area is " + record.recordLength()
                    + "; the group is a 12-byte TIOAPFX prefix plus " + PAYLOAD_FIELD_COUNT
                    + " fields of " + METADATA_PREFIX_LENGTH + " metadata bytes each plus "
                    + PAYLOAD_WIDTH_TOTAL + " payload bytes");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionViewRequest candidate)) {
            return false;
        }
        for (ScreenField field : ScreenField.values()) {
            if (!Objects.equals(payloadValue(field), candidate.payloadValue(field))) {
                return false;
            }
            if (!Objects.equals(metadata(field), candidate.metadata(field))) {
                return false;
            }
        }
        return Objects.equals(navigationContext, candidate.navigationContext)
                && Objects.equals(ct02Info, candidate.ct02Info)
                && Objects.equals(aid, candidate.aid);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(navigationContext, ct02Info, aid);
        for (ScreenField field : ScreenField.values()) {
            result = 31 * result + Objects.hashCode(payloadValue(field));
            result = 31 * result + Objects.hashCode(metadata(field));
        }
        return result;
    }

    /**
     * A diagnostic rendering keyed by the verbatim {@code xxxI} item names.
     *
     * @return the rendering, never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder rendering = new StringBuilder("TransactionViewRequest[")
                .append(TRANSACTION_ID).append('/').append(PROGRAM_NAME)
                .append('/').append(SYMBOLIC_MAP_INPUT_GROUP);
        for (ScreenField field : ScreenField.values()) {
            rendering.append(", ").append(field.inputItem()).append('=')
                    .append(SensitiveDiagnostics.render(disclosureOf(field), payloadValue(field)));
            if (isCursorRequested(field)) {
                rendering.append(" (cursor)");
            }
        }
        return rendering.append(", ").append(AID_FIELD).append('=').append(aid)
                .append(", navigationContext=").append(navigationContext)
                .append(", ct02Info=").append(ct02Info)
                .append(']')
                .toString();
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
