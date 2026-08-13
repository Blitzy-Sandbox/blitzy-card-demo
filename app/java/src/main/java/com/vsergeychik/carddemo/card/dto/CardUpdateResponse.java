package com.vsergeychik.carddemo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardDetails;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CardUpdateRecord;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.CommArea;
import com.vsergeychik.carddemo.card.dto.CardUpdateRequest.DetailGroup;
import com.vsergeychik.carddemo.common.ConversationStateSeal;
import com.vsergeychik.carddemo.common.BmsAttributes;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.DateHeader;
import com.vsergeychik.carddemo.common.FieldAttributeSetter;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldHighlight;
import com.vsergeychik.carddemo.common.FieldAttributeSetter.FieldValidationState;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ScreenTitles;
import com.vsergeychik.carddemo.common.SystemMessages;
import jakarta.validation.constraints.Size;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The outbound payload of {@code PUT /api/cards/&#123;cardNum&#125;}: a field-for-field projection of the
 * output half of the card-update screen, CICS transaction {@code CCUP}, backed by
 * {@code app/cbl/COCRDUPC.cbl} (1,560 lines).
 *
 * <p>A fresh instance holds {@code LOW-VALUES} in all 17 payload fields and {@code 0x00} in all 68
 * attribute bytes, which is exactly the state {@code 3100-SCREEN-INIT} establishes with
 * {@code MOVE LOW-VALUES TO CCRDUPAO} at {@code app/cbl/COCRDUPC.cbl:1053} - the first statement of the
 * send path.
 */
@JsonPropertyOrder({
        "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "acctsid", "cardsid",
        "crdname", "crdstcd", "expmon", "expyear", "expday", "infomsg", "errmsg", "fkeys", "fkeysc",
        // Transport extensions after the map: the commarea in both its shapes, CVCRD01Y's screen
        // state, and the target COCRDUPC names through CDEMO-TO-PROGRAM at :474.
        "stateToken", "cardScreenState", "navigationContext", "nextProgram", "nextMapset", "nextMap"})
public final class CardUpdateResponse {
    /**
     * The mapset name, {@code app/bms/COCRDUP.bms:20}.
     */
    public static final String MAPSET_NAME = "COCRDUP";

    /**
     * The map name, {@code app/bms/COCRDUP.bms:25}; seven characters, as BMS requires.
     */
    public static final String MAP_NAME = "CCRDUPA";

    /**
     * The input symbolic group, {@code app/cpy-bms/COCRDUP.CPY:17}: {@link #MAP_NAME} plus {@code I}.
     */
    public static final String INPUT_GROUP_NAME = MAP_NAME + "I";

    /**
     * The output symbolic group, {@code app/cpy-bms/COCRDUP.CPY:121}: {@link #MAP_NAME} plus {@code O}.
     */
    public static final String OUTPUT_GROUP_NAME = MAP_NAME + "O";

    /**
     * The CSD transaction that runs this screen, {@code app/csd/CARDDEMO.CSD}.
     */
    public static final String TRANSACTION_ID = "CCUP";

    /**
     * The COBOL program behind it, {@code app/cbl/COCRDUPC.cbl}.
     */
    public static final String PROGRAM_NAME = "COCRDUPC";

    /**
     * The {@code DFHMDF} entries {@code app/bms/COCRDUP.bms} declares in total: 34.
     */
    public static final int DFHMDF_TOTAL_COUNT = 34;

    /**
     * The name-labelled {@code DFHMDF} entries, and therefore the payload members here: 17.
     */
    public static final int NAMED_FIELD_COUNT = 17;

    /**
     * The {@code 02 FILLER PIC X(12)} that opens both groups ({@code app/cpy-bms/COCRDUP.CPY:18} and
     * {@code :122}).
     */
    public static final int TIOAPFX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that precedes each field's attribute quad in the output group, for
     * example {@code app/cpy-bms/COCRDUP.CPY:123}.
     */
    public static final int FIELD_PREFIX_FILLER_LENGTH = 3;

    /**
     * The four one-byte attribute items {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} that
     * precede each {@code xxxO} payload item.
     */
    public static final int ATTRIBUTE_QUAD_LENGTH = 4;

    public static final int ITEM_OVERHEAD_LENGTH = FIELD_PREFIX_FILLER_LENGTH + ATTRIBUTE_QUAD_LENGTH;

    /**
     * {@code TRNNAMEO PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:128} - the transaction identifier.
     */
    public static final int TRNNAMEO_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:134} - the upper heading line.
     */
    public static final int TITLE01O_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:140} - {@code mm/dd/yy}.
     */
    public static final int CURDATEO_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:146} - the program name.
     */
    public static final int PGMNAMEO_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:152} - the lower heading line.
     */
    public static final int TITLE02O_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)}, {@code app/cpy-bms/COCRDUP.CPY:158} - {@code hh:mm:ss}.
     */
    public static final int CURTIMEO_LENGTH = 8;

    /**
     * {@code ACCTSIDO PIC X(11)}, {@code app/cpy-bms/COCRDUP.CPY:164} - the account number.
     */
    public static final int ACCTSIDO_LENGTH = 11;

    public static final int CARDSIDO_LENGTH = 16;

    /**
     * {@code CRDNAMEO PIC X(50)}, {@code app/cpy-bms/COCRDUP.CPY:176} - the embossed name.
     */
    public static final int CRDNAMEO_LENGTH = 50;

    /**
     * {@code CRDSTCDO PIC X(1)}, {@code app/cpy-bms/COCRDUP.CPY:182} - the active status, Y or N.
     */
    public static final int CRDSTCDO_LENGTH = 1;

    /**
     * {@code EXPMONO PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:188} - the expiry month.
     */
    public static final int EXPMONO_LENGTH = 2;

    /**
     * {@code EXPYEARO PIC X(4)}, {@code app/cpy-bms/COCRDUP.CPY:194} - the expiry year.
     */
    public static final int EXPYEARO_LENGTH = 4;

    /**
     * {@code EXPDAYO PIC X(2)}, {@code app/cpy-bms/COCRDUP.CPY:200} - the expiry day.
     */
    public static final int EXPDAYO_LENGTH = 2;

    /**
     * {@code INFOMSGO PIC X(40)}, {@code app/cpy-bms/COCRDUP.CPY:206} - the information line.
     */
    public static final int INFOMSGO_LENGTH = 40;

    /**
     * {@code ERRMSGO PIC X(80)}, {@code app/cpy-bms/COCRDUP.CPY:212} - the error line.
     */
    public static final int ERRMSGO_LENGTH = 80;

    /**
     * {@code FKEYSO PIC X(21)}, {@code app/cpy-bms/COCRDUP.CPY:218} - the first function-key line,
     * {@code INITIAL='ENTER=Process F3=Exit'} at {@code app/bms/COCRDUP.bms:158-162}.
     */
    public static final int FKEYSO_LENGTH = 21;

    /**
     * {@code FKEYSCO PIC X(18)}, {@code app/cpy-bms/COCRDUP.CPY:224} - the second function-key line,
     * {@code INITIAL='F5=Save F12=Cancel'} at {@code app/bms/COCRDUP.bms:163-167}.
     */
    public static final int FKEYSCO_LENGTH = 18;

    public static final int PAYLOAD_LENGTH = TRNNAMEO_LENGTH + TITLE01O_LENGTH + CURDATEO_LENGTH
            + PGMNAMEO_LENGTH + TITLE02O_LENGTH + CURTIMEO_LENGTH + ACCTSIDO_LENGTH
            + CARDSIDO_LENGTH + CRDNAMEO_LENGTH + CRDSTCDO_LENGTH + EXPMONO_LENGTH
            + EXPYEARO_LENGTH + EXPDAYO_LENGTH + INFOMSGO_LENGTH + ERRMSGO_LENGTH + FKEYSO_LENGTH
            + FKEYSCO_LENGTH;

    public static final int GROUP_LENGTH =
            TIOAPFX_LENGTH + NAMED_FIELD_COUNT * ITEM_OVERHEAD_LENGTH + PAYLOAD_LENGTH;

    /**
     * {@code DFHMDF} label {@code TRNNAME}, {@code app/bms/COCRDUP.bms:34}.
     */
    public static final String TRNNAME = "TRNNAME";

    /**
     * {@code DFHMDF} label {@code TITLE01}, {@code app/bms/COCRDUP.bms:38}.
     */
    public static final String TITLE01 = "TITLE01";

    /**
     * {@code DFHMDF} label {@code CURDATE}, {@code app/bms/COCRDUP.bms:47}.
     */
    public static final String CURDATE = "CURDATE";

    /**
     * {@code DFHMDF} label {@code PGMNAME}, {@code app/bms/COCRDUP.bms:57}.
     */
    public static final String PGMNAME = "PGMNAME";

    /**
     * {@code DFHMDF} label {@code TITLE02}, {@code app/bms/COCRDUP.bms:61}.
     */
    public static final String TITLE02 = "TITLE02";

    /**
     * {@code DFHMDF} label {@code CURTIME}, {@code app/bms/COCRDUP.bms:70}.
     */
    public static final String CURTIME = "CURTIME";

    /**
     * {@code DFHMDF} label {@code ACCTSID}, {@code app/bms/COCRDUP.bms:84}.
     */
    public static final String ACCTSID = "ACCTSID";

    /**
     * {@code DFHMDF} label {@code CARDSID}, {@code app/bms/COCRDUP.bms:96}.
     */
    public static final String CARDSID = "CARDSID";

    /**
     * {@code DFHMDF} label {@code CRDNAME}, {@code app/bms/COCRDUP.bms:107}.
     */
    public static final String CRDNAME = "CRDNAME";

    /**
     * {@code DFHMDF} label {@code CRDSTCD}, {@code app/bms/COCRDUP.bms:117}.
     */
    public static final String CRDSTCD = "CRDSTCD";

    /**
     * {@code DFHMDF} label {@code EXPMON}, {@code app/bms/COCRDUP.bms:127}.
     */
    public static final String EXPMON = "EXPMON";

    /**
     * {@code DFHMDF} label {@code EXPYEAR}, {@code app/bms/COCRDUP.bms:135}.
     */
    public static final String EXPYEAR = "EXPYEAR";

    /**
     * {@code DFHMDF} label {@code EXPDAY}, {@code app/bms/COCRDUP.bms:142}.
     */
    public static final String EXPDAY = "EXPDAY";

    /**
     * {@code DFHMDF} label {@code INFOMSG}, {@code app/bms/COCRDUP.bms:149}.
     */
    public static final String INFOMSG = "INFOMSG";

    /**
     * {@code DFHMDF} label {@code ERRMSG}, {@code app/bms/COCRDUP.bms:154}.
     */
    public static final String ERRMSG = "ERRMSG";

    /**
     * {@code DFHMDF} label {@code FKEYS}, {@code app/bms/COCRDUP.bms:158} - 21 bytes.
     */
    public static final String FKEYS = "FKEYS";

    /**
     * {@code DFHMDF} label {@code FKEYSC}, {@code app/bms/COCRDUP.bms:163} - 18 bytes.
     */
    public static final String FKEYSC = "FKEYSC";

    public static final String COLOUR_ITEM_SUFFIX = "C";

    public static final String PS_ITEM_SUFFIX = "P";

    public static final String HILIGHT_ITEM_SUFFIX = "H";

    public static final String VALIDN_ITEM_SUFFIX = "V";

    public static final String OUTPUT_ITEM_SUFFIX = "O";

    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(StandardCharsets.US_ASCII);

    /**
     * One name-labelled screen field of {@code 01 CCRDUPAO}, carrying its declared width, the copybook line
     * its {@code xxxO} item sits on, and the absolute offset at which its six-item block begins.
     *
     * @param name the {@code DFHMDF} label exactly as declared, for example {@code "FKEYSC"}; this is the
     *     {@code (SCRNVAR2)} token and the key of every name-addressed operation
     * @param length the {@code xxxO} declared width in characters
     * @param copybookLine the line of {@code app/cpy-bms/COCRDUP.CPY} declaring the {@code xxxO} item
     * @param fieldOffset the absolute 0-based offset of the block's leading {@code FILLER X(3)}
     */
    public record ScreenField(String name, int length, int copybookLine, int fieldOffset) {
        public ScreenField {
            Objects.requireNonNull(name, "A DFHMDF label is required to declare a screen field");
            if (name.isBlank()) {
                throw new IllegalArgumentException("A screen field declares an empty DFHMDF label; "
                        + "only the 17 name-labelled entries of app/bms/COCRDUP.bms are modelled, and "
                        + "the 17 unnamed literal entries must not be");
            }
            if (length < 1) {
                throw new IllegalArgumentException("Screen field '" + name + "' declares a width of "
                        + length + "; every xxxO item of app/cpy-bms/COCRDUP.CPY is PIC X(n) with n "
                        + "of at least 1");
            }
            if (copybookLine < 1) {
                throw new IllegalArgumentException("Screen field '" + name + "' cites copybook line "
                        + copybookLine + "; every xxxO item has a real line in "
                        + "app/cpy-bms/COCRDUP.CPY");
            }
            if (fieldOffset < TIOAPFX_LENGTH) {
                throw new IllegalArgumentException("Screen field '" + name + "' begins at offset "
                        + fieldOffset + ", inside the " + TIOAPFX_LENGTH + "-byte TIOAPFX prefix "
                        + "declared at app/cpy-bms/COCRDUP.CPY:122; the first field begins at offset "
                        + TIOAPFX_LENGTH);
            }
        }

        public String colourItemName() {
            return name + COLOUR_ITEM_SUFFIX;
        }

        public String psItemName() {
            return name + PS_ITEM_SUFFIX;
        }

        public String hilightItemName() {
            return name + HILIGHT_ITEM_SUFFIX;
        }

        public String validnItemName() {
            return name + VALIDN_ITEM_SUFFIX;
        }

        /**
         * The {@code xxxO} payload item name - the only item that reaches the JSON wire.
         *
         * @return the payload item name, for example {@code "FKEYSCO"}
         */
        public String outputItemName() {
            return name + OUTPUT_ITEM_SUFFIX;
        }

        /**
         * Where this field's six-item block begins, at its leading {@code FILLER PICTURE X(3)}.
         *
         * @return the absolute 0-based offset of the leading {@code FILLER}
         */
        public int prefixFillerOffset() {
            return fieldOffset;
        }

        /**
         * Where the {@code xxxC} colour byte sits, three bytes into the block.
         *
         * @return the absolute 0-based offset of the colour byte
         */
        public int colourItemOffset() {
            return fieldOffset + FIELD_PREFIX_FILLER_LENGTH;
        }

        public int psItemOffset() {
            return colourItemOffset() + 1;
        }

        public int hilightItemOffset() {
            return psItemOffset() + 1;
        }

        public int validnItemOffset() {
            return hilightItemOffset() + 1;
        }

        /**
         * Where the {@code xxxO} payload item sits, seven bytes into the block.
         *
         * @return the absolute 0-based offset of the payload item
         */
        public int outputItemOffset() {
            return fieldOffset + ITEM_OVERHEAD_LENGTH;
        }

        /**
         * Where this field's block ends, which is where the next field's block begins.
         *
         * @return the offset one past this block - {@code fieldOffset + 7 + n}, equal to the next field's
         *     {@link #fieldOffset()}
         */
        public int endOffsetExclusive() {
            return outputItemOffset() + length;
        }

        /**
         * The leading {@code FILLER PICTURE X(3)} span, which overlays the input group's {@code xxxL} and
         * {@code xxxF} items.
         *
         * @return the three-byte filler span
         */
        public FieldSpan prefixFillerSpan() {
            return FieldSpan.filler(prefixFillerOffset(), FIELD_PREFIX_FILLER_LENGTH);
        }

        public FieldSpan colourItemSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourItemOffset(), 1);
        }

        public FieldSpan psItemSpan() {
            return FieldSpan.alphanumeric(psItemName(), psItemOffset(), 1);
        }

        public FieldSpan hilightItemSpan() {
            return FieldSpan.alphanumeric(hilightItemName(), hilightItemOffset(), 1);
        }

        public FieldSpan validnItemSpan() {
            return FieldSpan.alphanumeric(validnItemName(), validnItemOffset(), 1);
        }

        /**
         * The {@code xxxO} payload span, the only span of this block that carries screen content.
         *
         * @return the payload span, {@link #length()} bytes wide
         */
        public FieldSpan outputItemSpan() {
            return FieldSpan.alphanumeric(outputItemName(), outputItemOffset(), length);
        }

        /**
         * Renders this descriptor the way the copybook declares it, for an assertion message or a parity
         * trace.
         *
         * @return for example {@code "FKEYSCO PIC X(18) at offset 466 (app/cpy-bms/COCRDUP.CPY:224)"}
         */
        public String describe() {
            return outputItemName() + " PIC X(" + length + ") at offset " + outputItemOffset()
                    + " (app/cpy-bms/COCRDUP.CPY:" + copybookLine + ")";
        }
    }

    private static final List<ScreenField> FIELDS = declareFields();

    private static final Map<String, ScreenField> FIELDS_BY_NAME = indexByName(FIELDS);

    public static final RecordLayout OUTPUT_GROUP_LAYOUT = declareLayout(FIELDS);

    private static List<ScreenField> declareFields() {
        List<ScreenField> declared = new ArrayList<>(NAMED_FIELD_COUNT);
        int cursor = TIOAPFX_LENGTH;
        cursor = declare(declared, TRNNAME, TRNNAMEO_LENGTH, 128, cursor);
        cursor = declare(declared, TITLE01, TITLE01O_LENGTH, 134, cursor);
        cursor = declare(declared, CURDATE, CURDATEO_LENGTH, 140, cursor);
        cursor = declare(declared, PGMNAME, PGMNAMEO_LENGTH, 146, cursor);
        cursor = declare(declared, TITLE02, TITLE02O_LENGTH, 152, cursor);
        cursor = declare(declared, CURTIME, CURTIMEO_LENGTH, 158, cursor);
        cursor = declare(declared, ACCTSID, ACCTSIDO_LENGTH, 164, cursor);
        cursor = declare(declared, CARDSID, CARDSIDO_LENGTH, 170, cursor);
        cursor = declare(declared, CRDNAME, CRDNAMEO_LENGTH, 176, cursor);
        cursor = declare(declared, CRDSTCD, CRDSTCDO_LENGTH, 182, cursor);
        cursor = declare(declared, EXPMON, EXPMONO_LENGTH, 188, cursor);
        cursor = declare(declared, EXPYEAR, EXPYEARO_LENGTH, 194, cursor);
        cursor = declare(declared, EXPDAY, EXPDAYO_LENGTH, 200, cursor);
        cursor = declare(declared, INFOMSG, INFOMSGO_LENGTH, 206, cursor);
        cursor = declare(declared, ERRMSG, ERRMSGO_LENGTH, 212, cursor);
        cursor = declare(declared, FKEYS, FKEYSO_LENGTH, 218, cursor);
        cursor = declare(declared, FKEYSC, FKEYSCO_LENGTH, 224, cursor);

        if (declared.size() != NAMED_FIELD_COUNT) {
            throw new IllegalStateException("app/bms/COCRDUP.bms declares " + NAMED_FIELD_COUNT
                    + " name-labelled DFHMDF entries of " + DFHMDF_TOTAL_COUNT + " but "
                    + declared.size() + " were declared here");
        }
        if (cursor != GROUP_LENGTH) {
            throw new IllegalStateException("The 17 declared fields end at offset " + cursor
                    + " but 01 CCRDUPAO is " + GROUP_LENGTH + " bytes wide; check the width "
                    + "constants against app/cpy-bms/COCRDUP.CPY");
        }
        return List.copyOf(declared);
    }

    private static int declare(List<ScreenField> declared, String name, int length, int copybookLine,
            int fieldOffset) {
        ScreenField field = new ScreenField(name, length, copybookLine, fieldOffset);
        declared.add(field);
        return field.endOffsetExclusive();
    }

    private static Map<String, ScreenField> indexByName(List<ScreenField> fields) {
        Map<String, ScreenField> index = new LinkedHashMap<>(fields.size() * 2);
        for (ScreenField field : fields) {
            ScreenField clash = index.put(field.name(), field);
            if (clash != null) {
                throw new IllegalStateException("Two screen fields share the DFHMDF label '"
                        + field.name() + "'; app/bms/COCRDUP.bms declares 17 distinct labels, and "
                        + "FKEYS and FKEYSC are distinct among them");
            }
        }
        return Collections.unmodifiableMap(index);
    }

    private static RecordLayout declareLayout(List<ScreenField> fields) {
        List<FieldSpan> spans = new ArrayList<>(1 + fields.size() * 6);
        spans.add(FieldSpan.filler(0, TIOAPFX_LENGTH));
        for (ScreenField field : fields) {
            spans.add(field.prefixFillerSpan());
            spans.add(field.colourItemSpan());
            spans.add(field.psItemSpan());
            spans.add(field.hilightItemSpan());
            spans.add(field.validnItemSpan());
            spans.add(field.outputItemSpan());
        }
        return new RecordLayout(GROUP_LENGTH, spans);
    }

    /**
     * The four one-byte extended-attribute items of one screen field, in their declared byte order:
     * {@code xxxC} colour, {@code xxxP} programmed symbols, {@code xxxH} highlight, {@code xxxV}
     * validation.
     */
    public static final class FieldAttributes {
        public static final byte DEFAULT = (byte) 0x00;

        private byte colour;

        private byte ps;

        private byte hilight;

        private byte validn;

        /**
         * Creates a quad in its {@code LOW-VALUES} state: all four bytes {@link #DEFAULT}.
         */
        public FieldAttributes() {
            reset();
        }

        public FieldAttributes(byte colour, byte ps, byte hilight, byte validn) {
            this.colour = colour;
            this.ps = ps;
            this.hilight = hilight;
            this.validn = validn;
        }

        public FieldAttributes(FieldAttributes other) {
            Objects.requireNonNull(other, "A source quad is required to copy one");
            this.colour = other.colour;
            this.ps = other.ps;
            this.hilight = other.hilight;
            this.validn = other.validn;
        }

        /**
         * Restores all four bytes to {@link #DEFAULT}, reproducing the effect of
         * {@code MOVE LOW-VALUES TO CCRDUPAO} on this field's quad.
         */
        public void reset() {
            this.colour = DEFAULT;
            this.ps = DEFAULT;
            this.hilight = DEFAULT;
            this.validn = DEFAULT;
        }

        public byte getColour() {
            return colour;
        }

        /**
         * Moves an attribute byte into the {@code xxxC} colour item.
         *
         * @param colour any attribute byte - {@link BmsAttributes#DFHRED}, {@link BmsAttributes#DFHDFCOL}
         *     and {@link BmsAttributes#DFHBMDAR} are the three this program moves
         */
        public void setColour(byte colour) {
            this.colour = colour;
        }

        /**
         * The {@code xxxP} programmed-symbols byte currently held.
         *
         * @return the programmed-symbols byte
         */
        public byte getPs() {
            return ps;
        }

        public void setPs(byte ps) {
            this.ps = ps;
        }

        public byte getHilight() {
            return hilight;
        }

        public void setHilight(byte hilight) {
            this.hilight = hilight;
        }

        public byte getValidn() {
            return validn;
        }

        public void setValidn(byte validn) {
            this.validn = validn;
        }

        /**
         * Whether the colour item currently holds {@link BmsAttributes#DFHRED}, which is what
         * {@code CSSETATY} moves into a field in error.
         *
         * @return {@code true} when the field is painted red
         */
        public boolean isRed() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * Whether all four bytes are still {@link #DEFAULT}, that is whether nothing has been moved into
         * this quad since the group was set to {@code LOW-VALUES}.
         *
         * @return {@code true} when the quad is untouched
         */
        public boolean isDefault() {
            return colour == DEFAULT && ps == DEFAULT && hilight == DEFAULT && validn == DEFAULT;
        }

        /**
         * Renders the quad with the mnemonics {@link BmsAttributes} knows, for a log line or a parity
         * trace.
         *
         * @return for example {@code "C=DFHRED P=X'00' H=DFHDFHI V=X'00'"}
         */
        public String describe() {
            return "C=" + BmsAttributes.colourMnemonic(colour)
                    + " P=" + BmsAttributes.toHex(ps)
                    + " H=" + BmsAttributes.highlightMnemonic(hilight)
                    + " V=" + BmsAttributes.toHex(validn);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldAttributes quad)) {
                return false;
            }
            return colour == quad.colour && ps == quad.ps && hilight == quad.hilight
                    && validn == quad.validn;
        }

        @Override
        public int hashCode() {
            return Objects.hash(colour, ps, hilight, validn);
        }

        @Override
        public String toString() {
            return "FieldAttributes[" + describe() + "]";
        }
    }

    private final Map<String, String> payload;

    private final Map<String, FieldAttributes> attributes;

    private CommArea commArea;

    /**
     * The sealed form of {@link #commArea}, which is the only form that crosses the wire.
     *
     * <p>Two things in that area must not be readable or writable by a client. Its first byte,
     * {@code CCUP-CHANGE-ACTION}, records that this screen's four edits already passed, which is why
     * {@code app/cbl/COCRDUPC.cbl:685-693} skips them all and {@code :988-1001} writes on {@code PF5}. And
     * {@code CCUP-OLD-DETAILS} and {@code CARD-UPDATE-RECORD} both carry {@code CARD-CVV-CD} - a value that
     * appears on <strong>none</strong> of the seventeen {@code DFHMDF} fields of
     * {@code app/bms/COCRDUP.bms}, so a terminal is never shown it. An {@code EXEC CICS RETURN} state area
     * is not terminal screen data, and treating the two as equivalent is what made this look harmless.
     *
     * <p>It therefore travels as one opaque token from {@link ConversationStateSeal}: authenticated so it
     * cannot be altered, encrypted so the CVV cannot be read, and carrying exactly the
     * {@value CommArea#RECORD_LENGTH} bytes the COBOL area holds so parity is untouched.
     * {@link #getCommArea()} still answers the structured value to a parity case, which is where the byte
     * comparison belongs.
     *
     * <p>Empty when this path carried no area. Never {@code null}.
     */
    private String stateToken = "";

    /** The echoed {@code CVCRD01Y} work area ({@code COPY CVCRD01Y} at {@code COCRDUPC.cbl:268}). */
    private CardScreenState cardScreenState;

    private NavigationContext navigationContext;

    private String nextProgram;

    private String nextMapset;

    private String nextMap;

    /**
     * Creates a response in the state {@code 3100-SCREEN-INIT} establishes with its very first statement,
     * {@code MOVE LOW-VALUES TO CCRDUPAO} at {@code app/cbl/COCRDUPC.cbl:1053}.
     */
    public CardUpdateResponse() {
        this.payload = new LinkedHashMap<>(FIELDS.size() * 2);
        this.attributes = new LinkedHashMap<>(FIELDS.size() * 2);
        for (ScreenField field : FIELDS) {
            this.payload.put(field.name(), CardScreenState.lowValues(field.length()));
            this.attributes.put(field.name(), new FieldAttributes());
        }
        this.commArea = CommArea.initialised();
        this.stateToken = "";
        this.cardScreenState = new CardScreenState();
        this.navigationContext = NavigationContext.empty();
        this.nextProgram = CardScreenState.spaces(CardScreenState.CCARD_NEXT_PROG_LENGTH);
        this.nextMapset = CardScreenState.spaces(CardScreenState.CCARD_NEXT_MAPSET_LENGTH);
        this.nextMap = CardScreenState.spaces(CardScreenState.CCARD_NEXT_MAP_LENGTH);
    }

    /**
     * Copies an existing response, deeply enough that the copy can be mutated without touching the
     * original.
     *
     * @param other the response to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public CardUpdateResponse(CardUpdateResponse other) {
        Objects.requireNonNull(other, "A source response is required to copy one");
        this.payload = new LinkedHashMap<>(other.payload);
        this.attributes = new LinkedHashMap<>(other.attributes.size() * 2);
        for (Map.Entry<String, FieldAttributes> quad : other.attributes.entrySet()) {
            this.attributes.put(quad.getKey(), new FieldAttributes(quad.getValue()));
        }
        this.commArea = other.commArea;
        this.stateToken = other.stateToken;
        this.cardScreenState = new CardScreenState(other.cardScreenState);
        this.navigationContext = other.navigationContext;
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
    }

    /**
     * Reproduces {@code 3100-SCREEN-INIT} ({@code app/cbl/COCRDUPC.cbl:1052-1076}) in full: sets the whole
     * group to {@code LOW-VALUES}, then moves the four constants and the two clock values the paragraph
     * moves.
     *
     * @param dateHeader the captured date and time, supplying {@code mm/dd/yy} and {@code hh:mm:ss}
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void screenInit(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required: 3100-SCREEN-INIT moves "
                + "FUNCTION CURRENT-DATE into CURDATEO and CURTIMEO, so the clock reading must be "
                + "supplied rather than taken here");
        moveLowValuesToGroup();
        applyScreenTitles();
        setTrnnameo(TRANSACTION_ID);
        setPgmnameo(PROGRAM_NAME);
        applyDateTimeHeader(dateHeader);
    }

    /**
     * Reproduces {@code MOVE LOW-VALUES TO CCRDUPAO} ({@code app/cbl/COCRDUPC.cbl:1053}): every payload
     * item back to {@code LOW-VALUES} at its declared width, every attribute byte back to {@code 0x00}.
     */
    public void moveLowValuesToGroup() {
        for (ScreenField field : FIELDS) {
            payload.put(field.name(), CardScreenState.lowValues(field.length()));
            attributes.get(field.name()).reset();
        }
    }

    /**
     * Moves the two shared heading literals into {@code TITLE01O} and {@code TITLE02O}, as
     * {@code app/cbl/COCRDUPC.cbl:1058-1059} does with {@code CCDA-TITLE01} and {@code CCDA-TITLE02}.
     */
    public void applyScreenTitles() {
        setTitle01o(ScreenTitles.CCDA_TITLE01);
        setTitle02o(ScreenTitles.CCDA_TITLE02);
    }

    /**
     * Moves the formatted date and time into {@code CURDATEO} and {@code CURTIMEO}, as
     * {@code app/cbl/COCRDUPC.cbl:1068} and {@code :1075} do with {@code WS-CURDATE-MM-DD-YY} and
     * {@code WS-CURTIME-HH-MM-SS}.
     *
     * @param dateHeader the captured date and time
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    public void applyDateTimeHeader(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A DateHeader is required to fill CURDATEO and CURTIMEO");
        setCurdateo(dateHeader.wsCurdateMmDdYy());
        setCurtimeo(dateHeader.wsCurtimeHhMmSs());
    }

    // Each setter performs a COBOL PIC X MOVE: padded on the right with spaces when short, truncated on the
    // RIGHT when long, exactly as FixedWidthCodec#movePicX documents.

    /**
     * {@code TRNNAMEO PIC X(4)} - {@code DFHMDF TRNNAME}, {@code app/cpy-bms/COCRDUP.CPY:128}.
     *
     * @return the item at exactly {@value #TRNNAMEO_LENGTH} characters, untrimmed
     */
    @Size(max = TRNNAMEO_LENGTH)
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return payload.get(TRNNAME);
    }

    public void setTrnnameo(String value) {
        setOutputItem(TRNNAME, value);
    }

    /**
     * {@code TITLE01O PIC X(40)} - {@code DFHMDF TITLE01}, {@code app/cpy-bms/COCRDUP.CPY:134}.
     *
     * @return the item at exactly {@value #TITLE01O_LENGTH} characters, untrimmed
     */
    @Size(max = TITLE01O_LENGTH)
    @JsonProperty("title01")
    public String getTitle01o() {
        return payload.get(TITLE01);
    }

    public void setTitle01o(String value) {
        setOutputItem(TITLE01, value);
    }

    /**
     * {@code CURDATEO PIC X(8)} - {@code DFHMDF CURDATE}, {@code app/cpy-bms/COCRDUP.CPY:140}.
     *
     * @return the item at exactly {@value #CURDATEO_LENGTH} characters, untrimmed
     */
    @Size(max = CURDATEO_LENGTH)
    @JsonProperty("curdate")
    public String getCurdateo() {
        return payload.get(CURDATE);
    }

    public void setCurdateo(String value) {
        setOutputItem(CURDATE, value);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - {@code DFHMDF PGMNAME}, {@code app/cpy-bms/COCRDUP.CPY:146}.
     *
     * @return the item at exactly {@value #PGMNAMEO_LENGTH} characters, untrimmed
     */
    @Size(max = PGMNAMEO_LENGTH)
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return payload.get(PGMNAME);
    }

    public void setPgmnameo(String value) {
        setOutputItem(PGMNAME, value);
    }

    /**
     * {@code TITLE02O PIC X(40)} - {@code DFHMDF TITLE02}, {@code app/cpy-bms/COCRDUP.CPY:152}.
     *
     * @return the item at exactly {@value #TITLE02O_LENGTH} characters, untrimmed
     */
    @Size(max = TITLE02O_LENGTH)
    @JsonProperty("title02")
    public String getTitle02o() {
        return payload.get(TITLE02);
    }

    public void setTitle02o(String value) {
        setOutputItem(TITLE02, value);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - {@code DFHMDF CURTIME}, {@code app/cpy-bms/COCRDUP.CPY:158}.
     *
     * @return the item at exactly {@value #CURTIMEO_LENGTH} characters, untrimmed
     */
    @Size(max = CURTIMEO_LENGTH)
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return payload.get(CURTIME);
    }

    public void setCurtimeo(String value) {
        setOutputItem(CURTIME, value);
    }

    /**
     * {@code ACCTSIDO PIC X(11)} - {@code DFHMDF ACCTSID}, {@code app/cpy-bms/COCRDUP.CPY:164}.
     *
     * @return the item at exactly {@value #ACCTSIDO_LENGTH} characters, untrimmed
     */
    @Size(max = ACCTSIDO_LENGTH)
    @JsonProperty("acctsid")
    public String getAcctsido() {
        return payload.get(ACCTSID);
    }

    public void setAcctsido(String value) {
        setOutputItem(ACCTSID, value);
    }

    /**
     * {@code CARDSIDO PIC X(16)} - {@code DFHMDF CARDSID}, {@code app/cpy-bms/COCRDUP.CPY:170}.
     *
     * @return the item at exactly {@value #CARDSIDO_LENGTH} characters, untrimmed
     */
    @Size(max = CARDSIDO_LENGTH)
    @JsonProperty("cardsid")
    public String getCardsido() {
        return payload.get(CARDSID);
    }

    public void setCardsido(String value) {
        setOutputItem(CARDSID, value);
    }

    /**
     * {@code CRDNAMEO PIC X(50)} - {@code DFHMDF CRDNAME}, {@code app/cpy-bms/COCRDUP.CPY:176}.
     *
     * @return the item at exactly {@value #CRDNAMEO_LENGTH} characters, untrimmed
     */
    @Size(max = CRDNAMEO_LENGTH)
    @JsonProperty("crdname")
    public String getCrdnameo() {
        return payload.get(CRDNAME);
    }

    public void setCrdnameo(String value) {
        setOutputItem(CRDNAME, value);
    }

    /**
     * {@code CRDSTCDO PIC X(1)} - {@code DFHMDF CRDSTCD}, {@code app/cpy-bms/COCRDUP.CPY:182}.
     *
     * @return the item at exactly {@value #CRDSTCDO_LENGTH} character, untrimmed
     */
    @Size(max = CRDSTCDO_LENGTH)
    @JsonProperty("crdstcd")
    public String getCrdstcdo() {
        return payload.get(CRDSTCD);
    }

    public void setCrdstcdo(String value) {
        setOutputItem(CRDSTCD, value);
    }

    /**
     * {@code EXPMONO PIC X(2)} - {@code DFHMDF EXPMON}, {@code app/cpy-bms/COCRDUP.CPY:188}.
     *
     * @return the item at exactly {@value #EXPMONO_LENGTH} characters, untrimmed
     */
    @Size(max = EXPMONO_LENGTH)
    @JsonProperty("expmon")
    public String getExpmono() {
        return payload.get(EXPMON);
    }

    public void setExpmono(String value) {
        setOutputItem(EXPMON, value);
    }

    /**
     * {@code EXPYEARO PIC X(4)} - {@code DFHMDF EXPYEAR}, {@code app/cpy-bms/COCRDUP.CPY:194}.
     *
     * @return the item at exactly {@value #EXPYEARO_LENGTH} characters, untrimmed
     */
    @Size(max = EXPYEARO_LENGTH)
    @JsonProperty("expyear")
    public String getExpyearo() {
        return payload.get(EXPYEAR);
    }

    public void setExpyearo(String value) {
        setOutputItem(EXPYEAR, value);
    }

    /**
     * {@code EXPDAYO PIC X(2)} - {@code DFHMDF EXPDAY}, {@code app/cpy-bms/COCRDUP.CPY:200}.
     *
     * @return the item at exactly {@value #EXPDAYO_LENGTH} characters, untrimmed
     */
    @Size(max = EXPDAYO_LENGTH)
    @JsonProperty("expday")
    public String getExpdayo() {
        return payload.get(EXPDAY);
    }

    public void setExpdayo(String value) {
        setOutputItem(EXPDAY, value);
    }

    /**
     * {@code INFOMSGO PIC X(40)} - {@code DFHMDF INFOMSG}, {@code app/cpy-bms/COCRDUP.CPY:206}.
     *
     * @return the item at exactly {@value #INFOMSGO_LENGTH} characters, untrimmed
     */
    @Size(max = INFOMSGO_LENGTH)
    @JsonProperty("infomsg")
    public String getInfomsgo() {
        return payload.get(INFOMSG);
    }

    public void setInfomsgo(String value) {
        setOutputItem(INFOMSG, value);
    }

    /**
     * {@code ERRMSGO PIC X(80)} - {@code DFHMDF ERRMSG}, {@code app/cpy-bms/COCRDUP.CPY:212}.
     *
     * @return the item at exactly {@value #ERRMSGO_LENGTH} characters, untrimmed
     */
    @Size(max = ERRMSGO_LENGTH)
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return payload.get(ERRMSG);
    }

    public void setErrmsgo(String value) {
        setOutputItem(ERRMSG, value);
    }

    /**
     * {@code FKEYSO PIC X(21)} - {@code DFHMDF FKEYS}, {@code app/cpy-bms/COCRDUP.CPY:218}.
     *
     * <p>The first function-key line, whose mapset literal is {@code 'ENTER=Process F3=Exit'} - exactly
     * {@value #FKEYSO_LENGTH} characters ({@code app/bms/COCRDUP.bms:162}).
     *
     * @return the item at exactly {@value #FKEYSO_LENGTH} characters, untrimmed
     */
    @Size(max = FKEYSO_LENGTH)
    @JsonProperty("fkeys")
    public String getFkeyso() {
        return payload.get(FKEYS);
    }

    public void setFkeyso(String value) {
        setOutputItem(FKEYS, value);
    }

    /**
     * {@code FKEYSCO PIC X(18)} - {@code DFHMDF FKEYSC}, {@code app/cpy-bms/COCRDUP.CPY:224}.
     *
     * <p>The second function-key line, whose mapset literal is {@code 'F5=Save F12=Cancel'} - exactly
     * {@value #FKEYSCO_LENGTH} characters ({@code app/bms/COCRDUP.bms:167}).
     *
     * @return the item at exactly {@value #FKEYSCO_LENGTH} characters, untrimmed
     */
    @Size(max = FKEYSCO_LENGTH)
    @JsonProperty("fkeysc")
    public String getFkeysco() {
        return payload.get(FKEYSC);
    }

    public void setFkeysco(String value) {
        setOutputItem(FKEYSC, value);
    }

    // This is what makes the FKEYSC / FKEYSCC collision safe: a field is always reached by the DFHMDF label
    // the source declares, never by stripping a suffix off an item name.

    /**
     * The 17 field descriptors in copybook declaration order.
     *
     * @return an unmodifiable list; the same shared constant every instance sees
     */
    @JsonIgnore
    public static List<ScreenField> namedFields() {
        return FIELDS;
    }

    /**
     * The 17 {@code DFHMDF} labels in copybook declaration order:
     * {@code TRNNAME TITLE01 CURDATE PGMNAME TITLE02 CURTIME ACCTSID CARDSID CRDNAME CRDSTCD EXPMON EXPYEAR EXPDAY INFOMSG ERRMSG FKEYS FKEYSC}.
     *
     * @return an unmodifiable list of the labels, in order
     */
    @JsonIgnore
    public static List<String> namedFieldPrefixes() {
        List<String> names = new ArrayList<>(FIELDS.size());
        for (ScreenField field : FIELDS) {
            names.add(field.name());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * The descriptor of one field, by {@code DFHMDF} label.
     *
     * @param screenFieldName the label, for example {@link #FKEYSC}
     * @return the descriptor; never {@code null}
     * @throws NullPointerException if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    @JsonIgnore
    public static ScreenField fieldOf(String screenFieldName) {
        return requireField(screenFieldName);
    }

    public static boolean declaresField(String screenFieldName) {
        return screenFieldName != null && FIELDS_BY_NAME.containsKey(screenFieldName);
    }

    /**
     * The live attribute quad of one field, by {@code DFHMDF} label.
     *
     * <p>Returned by reference, deliberately: the caller mutates it, which is what
     * {@code MOVE DFHRED TO ACCTSIDC OF CCRDUPAO} means.
     *
     * @param screenFieldName the label, for example {@link #ACCTSID}
     * @return the mutable quad; never {@code null}
     * @throws NullPointerException if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    @JsonIgnore
    public FieldAttributes attributesOf(String screenFieldName) {
        return attributes.get(requireField(screenFieldName).name());
    }

    /**
     * The colour byte of one field, that is its {@code xxxC} item.
     *
     * @param screenFieldName the label
     * @return the attribute byte currently held
     * @throws NullPointerException if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public byte colourOf(String screenFieldName) {
        return attributesOf(screenFieldName).getColour();
    }

    /**
     * Reproduces {@code MOVE <attribute> TO <field>C OF CCRDUPAO}.
     *
     * @param screenFieldName the label
     * @param colour the attribute byte to move
     * @throws NullPointerException if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public void setColour(String screenFieldName, byte colour) {
        attributesOf(screenFieldName).setColour(colour);
    }

    /**
     * The payload item of one field, that is its {@code xxxO} item, by {@code DFHMDF} label.
     *
     * @param screenFieldName the label
     * @return the item at exactly its declared width, untrimmed
     * @throws NullPointerException if {@code screenFieldName} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public String outputItemOf(String screenFieldName) {
        return payload.get(requireField(screenFieldName).name());
    }

    /**
     * Reproduces {@code MOVE <value> TO <field>O OF CCRDUPAO}, applying the COBOL {@code PIC X} move rule:
     * padded on the right with spaces when the sender is shorter, and truncated on the right when it is
     * longer.
     *
     * <p>Right truncation is the rule for a {@code PIC X} receiver - the receiving field fills from its
     * leftmost character and the overflow is discarded - and it is the opposite of the {@code PIC 9} rule.
     *
     * @param screenFieldName the label
     * @param value the sending value; may be shorter or longer than the receiver, and may be empty, which
     *     blanks the field exactly as {@code MOVE SPACES} does
     * @throws NullPointerException if {@code screenFieldName} or {@code value} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public void setOutputItem(String screenFieldName, String value) {
        ScreenField field = requireField(screenFieldName);
        payload.put(field.name(), movePicX(value, field));
    }

    /**
     * Applies a {@code CSSETATY} decision to this map: {@code DFHRED} into the field's {@code xxxC} item
     * and, when the field was blank, {@code '*'} into its {@code xxxO} item.
     *
     * @param highlight the resolved decision
     * @throws NullPointerException if {@code highlight} is {@code null}
     * @throws IllegalArgumentException if the decision carries no field prefix, or one this map does not
     *     declare, or a map name other than {@link #MAP_NAME}
     */
    public void applyHighlight(FieldHighlight highlight) {
        Objects.requireNonNull(highlight, "A resolved FieldHighlight is required; call "
                + "FieldAttributeSetter.resolve(...) to obtain one");

        String prefix = highlight.screenFieldPrefix();
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("The highlight carries no (SCRNVAR2) field prefix, so "
                    + "there is no way to tell which of the " + NAMED_FIELD_COUNT + " fields of "
                    + OUTPUT_GROUP_NAME + " it applies to; resolve it with the four-argument "
                    + "FieldAttributeSetter.resolve(state, reenter, screenFieldPrefix, mapName)");
        }
        String mapName = highlight.mapName();
        if (!mapName.isEmpty() && !MAP_NAME.equals(mapName)) {
            throw new IllegalArgumentException("The highlight was resolved for map '" + mapName
                    + "' but this payload projects '" + MAP_NAME + "' (" + OUTPUT_GROUP_NAME + "); "
                    + "the three card screens declare different widths for the same field names, so a "
                    + "highlight must not be carried across them");
        }
        ScreenField field = requireField(prefix);

        if (highlight.colourItemAssigned()) {
            attributes.get(field.name()).setColour(highlight.colourItemValue());
        }
        if (highlight.outputItemAssigned()) {
            setOutputItem(field.name(), highlight.outputItemValue());
        }
    }

    /**
     * Resolves the {@code CSSETATY} rule for one field of this map and applies it in one step.
     *
     * @param screenFieldName the {@code DFHMDF} label of the field being edited
     * @param state the field's validation state, the {@code (TESTVAR1)} analogue
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *     {@code CDEMO-PGM-CONTEXT} is 1; the guard at {@code app/cpy/CSSETATY.cpy:20}
     * @return the decision that was applied, so a caller or a test can assert on it
     * @throws NullPointerException if {@code screenFieldName} or {@code state} is {@code null}
     * @throws IllegalArgumentException if no field carries that label
     */
    public FieldHighlight applyHighlight(String screenFieldName, FieldValidationState state,
            boolean reenter) {
        ScreenField field = requireField(screenFieldName);
        FieldHighlight highlight =
                FieldAttributeSetter.resolve(state, reenter, field.name(), MAP_NAME);
        applyHighlight(highlight);
        return highlight;
    }

    /**
     * The {@value CommArea#RECORD_LENGTH}-byte {@code WS-THIS-PROGCOMMAREA} the client must send back on
     * its next call ({@code app/cbl/COCRDUPC.cbl:274-321}).
     *
     * @return the commarea; never {@code null}
     */
    @JsonIgnore
    public CommArea getCommArea() {
        return commArea;
    }

    /**
     * The sealed {@code WS-THIS-PROGCOMMAREA} the client must send back on its next call.
     *
     * @return the token, empty when this path carried no area; never {@code null}
     */
    public String getStateToken() {
        return stateToken;
    }

    /**
     * Replaces the sealed program commarea.
     *
     * @param stateToken the token; {@code null} becomes empty
     */
    public void setStateToken(String stateToken) {
        this.stateToken = stateToken == null ? "" : stateToken;
    }

    /**
     * Replaces the echoed program commarea.
     *
     * @param commArea the commarea to echo
     * @throws NullPointerException if {@code commArea} is {@code null}; COBOL has no absent
     *                              commarea, and {@link CommArea#initialised()} is the empty state
     */
    @JsonIgnore
    public void setCommArea(CommArea commArea) {
        this.commArea = Objects.requireNonNull(commArea, "A program commarea is required; "
                + "CommArea.initialised() is the INITIALIZE WS-THIS-PROGCOMMAREA state");
    }

    /**
     * The {@code CVCRD01Y} card work area - the AID, the next-target trio, the two message lines and the
     * three identifier fields ({@code COPY CVCRD01Y} at {@code app/cbl/COCRDUPC.cbl:268}).
     *
     * @return the work area; never {@code null}
     */
    public CardScreenState getCardScreenState() {
        return cardScreenState;
    }

    public void setCardScreenState(CardScreenState cardScreenState) {
        this.cardScreenState = Objects.requireNonNull(cardScreenState,
                "A CVCRD01Y work area is required; new CardScreenState() is the INITIALIZE state");
    }

    /**
     * The {@code COCOM01Y} application commarea - the from and to transaction and program, the user
     * identity and type, the {@code ENTER}/{@code REENTER} context and the carried identifiers
     * ({@code COPY COCOM01Y} at {@code app/cbl/COCRDUPC.cbl:272}).
     *
     * @return the commarea; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A COCOM01Y commarea is required; NavigationContext.empty() is the empty state");
    }

    /**
     * The program the client should call next - the {@code XCTL} target of
     * {@code app/cbl/COCRDUPC.cbl:473-476}, where the COBOL transfers control itself.
     *
     * @return the token at exactly {@link CardScreenState#CCARD_NEXT_PROG_LENGTH} characters
     */
    @Size(max = CardScreenState.CCARD_NEXT_PROG_LENGTH)
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Names the next program, applying the {@code PIC X} move rule to the declared width.
     *
     * @param nextProgram the target program name
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        this.nextProgram = movePicX(nextProgram, CardScreenState.CCARD_NEXT_PROG_LENGTH,
                "CDEMO-TO-PROGRAM");
    }

    /**
     * The mapset the client should send next - seven characters, the width of
     * {@link CardScreenState#CCARD_NEXT_MAPSET_LENGTH}.
     *
     * @return the token at exactly {@link CardScreenState#CCARD_NEXT_MAPSET_LENGTH} characters
     */
    @Size(max = CardScreenState.CCARD_NEXT_MAPSET_LENGTH)
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Names the next mapset, applying the {@code PIC X} move rule to the declared width.
     *
     * @param nextMapset the target mapset name, for example {@link #MAPSET_NAME}
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = movePicX(nextMapset, CardScreenState.CCARD_NEXT_MAPSET_LENGTH,
                "CCARD-NEXT-MAPSET");
    }

    /**
     * The map the client should send next - seven characters, because a symbolic group name is a
     * seven-character map name plus an {@code I} or {@code O} suffix, as {@link #MAP_NAME} yields
     * {@link #INPUT_GROUP_NAME} and {@link #OUTPUT_GROUP_NAME}.
     *
     * @return the token at exactly {@link CardScreenState#CCARD_NEXT_MAP_LENGTH} characters
     */
    @Size(max = CardScreenState.CCARD_NEXT_MAP_LENGTH)
    public String getNextMap() {
        return nextMap;
    }

    /**
     * Names the next map, applying the {@code PIC X} move rule to the declared width.
     *
     * @param nextMap the target map name, for example {@link #MAP_NAME}
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        this.nextMap = movePicX(nextMap, CardScreenState.CCARD_NEXT_MAP_LENGTH, "CCARD-NEXT-MAP");
    }

    public void transferTo(String program, String mapset, String map) {
        setNextProgram(program);
        setNextMapset(mapset);
        setNextMap(map);
    }

    private static final byte LOW_VALUE_BYTE = (byte) 0x00;

    /**
     * Renders this payload as the {@link #GROUP_LENGTH}-byte {@code 01 CCRDUPAO} image.
     *
     * @param charset the code page of the image, named explicitly by the caller - {@code IBM037} for a
     *     mainframe capture, {@code US-ASCII} for a text fixture
     * @return a new array of exactly {@link #GROUP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the space and zero
     *     characters
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required: a symbolic map image is bytes in a "
                + "specific code page, and this type is bound to neither IBM037 nor US-ASCII");
        FixedWidthRecord record = new FixedWidthRecord(GROUP_LENGTH, charset);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes this payload into a caller-supplied {@link #GROUP_LENGTH}-byte record area, so a caller that
     * is assembling a larger buffer does not have to allocate twice.
     *
     * @param record the area to write into; its declared length must be {@link #GROUP_LENGTH}
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area is not exactly {@link #GROUP_LENGTH} bytes wide
     */
    public void writeInto(FixedWidthRecord record) {
        requireGroupWidth(record);

        record.fill(0, TIOAPFX_LENGTH, LOW_VALUE_BYTE);
        for (ScreenField field : FIELDS) {
            FieldAttributes quad = attributes.get(field.name());
            record.fill(field.prefixFillerOffset(), FIELD_PREFIX_FILLER_LENGTH, LOW_VALUE_BYTE);
            record.writeSpanBytes(field.colourItemSpan(), new byte[] {quad.getColour()});
            record.writeSpanBytes(field.psItemSpan(), new byte[] {quad.getPs()});
            record.writeSpanBytes(field.hilightItemSpan(), new byte[] {quad.getHilight()});
            record.writeSpanBytes(field.validnItemSpan(), new byte[] {quad.getValidn()});
            record.writeSpan(field.outputItemSpan(), payload.get(field.name()));
        }
    }

    /**
     * Decodes a captured {@link #GROUP_LENGTH}-byte {@code 01 CCRDUPAO} image into a payload, recovering
     * all 17 items and all 68 attribute bytes.
     *
     * @param image the captured bytes; exactly {@link #GROUP_LENGTH} of them
     * @param charset the code page of those bytes, named explicitly
     * @return the decoded payload
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #GROUP_LENGTH} bytes
     */
    public static CardUpdateResponse fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "An image is required to decode a symbolic map group");
        Objects.requireNonNull(charset, "A charset is required to decode a symbolic map group");
        if (image.length != GROUP_LENGTH) {
            throw new IllegalArgumentException("01 " + OUTPUT_GROUP_NAME + " is " + GROUP_LENGTH
                    + " byte(s) wide but " + image.length + " byte(s) were supplied; the group is "
                    + TIOAPFX_LENGTH + " + " + NAMED_FIELD_COUNT + " x " + ITEM_OVERHEAD_LENGTH
                    + " + " + PAYLOAD_LENGTH + " bytes");
        }
        return readFrom(FixedWidthRecord.copyOf(image, GROUP_LENGTH, charset));
    }

    /**
     * Decodes a payload from a record area already holding a {@code 01 CCRDUPAO} image.
     *
     * @param record the area to read; its declared length must be {@link #GROUP_LENGTH}
     * @return the decoded payload
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the area is not exactly {@link #GROUP_LENGTH} bytes wide
     */
    public static CardUpdateResponse readFrom(FixedWidthRecord record) {
        requireGroupWidth(record);

        CardUpdateResponse decoded = new CardUpdateResponse();
        for (ScreenField field : FIELDS) {
            decoded.payload.put(field.name(), record.readSpan(field.outputItemSpan()));
            FieldAttributes quad = decoded.attributes.get(field.name());
            quad.setColour(singleByteOf(record, field.colourItemSpan()));
            quad.setPs(singleByteOf(record, field.psItemSpan()));
            quad.setHilight(singleByteOf(record, field.hilightItemSpan()));
            quad.setValidn(singleByteOf(record, field.validnItemSpan()));
        }
        return decoded;
    }

    /**
     * The 17 {@code xxxO} items keyed by their copybook item names - {@code TRNNAMEO}, {@code TITLE01O},
     * ..., {@code FKEYSO}, {@code FKEYSCO} - in declaration order.
     *
     * @return an unmodifiable insertion-ordered map of 17 entries
     */
    @JsonIgnore
    public Map<String, String> fieldImages() {
        Map<String, String> images = new LinkedHashMap<>(FIELDS.size() * 2);
        for (ScreenField field : FIELDS) {
            images.put(field.outputItemName(), payload.get(field.name()));
        }
        return Collections.unmodifiableMap(images);
    }

    /**
     * The 68 attribute bytes keyed by their copybook item names - {@code TRNNAMEC}, {@code TRNNAMEP},
     * {@code TRNNAMEH}, {@code TRNNAMEV}, ..., {@code FKEYSCC}, {@code FKEYSCP}, {@code FKEYSCH},
     * {@code FKEYSCV} - each rendered by {@link BmsAttributes#toHex(byte)} in the {@code X'hh'} notation
     * the copybooks use, in declaration order.
     *
     * @return an unmodifiable insertion-ordered map of 68 entries
     */
    @JsonIgnore
    public Map<String, String> attributeImages() {
        Map<String, String> images = new LinkedHashMap<>(FIELDS.size() * 8);
        for (ScreenField field : FIELDS) {
            FieldAttributes quad = attributes.get(field.name());
            images.put(field.colourItemName(), BmsAttributes.toHex(quad.getColour()));
            images.put(field.psItemName(), BmsAttributes.toHex(quad.getPs()));
            images.put(field.hilightItemName(), BmsAttributes.toHex(quad.getHilight()));
            images.put(field.validnItemName(), BmsAttributes.toHex(quad.getValidn()));
        }
        return Collections.unmodifiableMap(images);
    }

    private static ScreenField requireField(String screenFieldName) {
        Objects.requireNonNull(screenFieldName, "A DFHMDF label is required to address a field of "
                + OUTPUT_GROUP_NAME);
        ScreenField field = FIELDS_BY_NAME.get(screenFieldName);
        if (field == null) {
            throw new IllegalArgumentException("'" + screenFieldName + "' is not one of the "
                    + NAMED_FIELD_COUNT + " name-labelled DFHMDF fields of " + MAPSET_NAME + "; they "
                    + "are " + FIELDS_BY_NAME.keySet() + ". Note that an item name is not a field "
                    + "name: 'FKEYSO' and 'FKEYSC OF " + OUTPUT_GROUP_NAME + "' both belong to the "
                    + "field 'FKEYS', and 'FKEYSC' is a separate 18-byte field");
        }
        return field;
    }

    private static String movePicX(String value, ScreenField field) {
        return movePicX(value, field.length(), field.outputItemName());
    }

    private static String movePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no null, "
                + "so pass an empty string or CardScreenState.spaces(" + length + ") for SPACES, or "
                + "CardScreenState.lowValues(" + length + ") for LOW-VALUES");
        return PICTURE_RULES.movePicX(value, length);
    }

    private static byte singleByteOf(FixedWidthRecord record, FieldSpan span) {
        return record.readSpanBytes(span)[0];
    }

    private static void requireGroupWidth(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read or write a symbolic map "
                + "group image");
        if (record.recordLength() != GROUP_LENGTH) {
            throw new IllegalArgumentException("01 " + OUTPUT_GROUP_NAME + " is " + GROUP_LENGTH
                    + " byte(s) wide but the record area is " + record.recordLength() + "; the group "
                    + "is " + TIOAPFX_LENGTH + " + " + NAMED_FIELD_COUNT + " x "
                    + ITEM_OVERHEAD_LENGTH + " + " + PAYLOAD_LENGTH + " bytes");
        }
    }

    /**
     * Field-for-field equality over all 17 payload items, all 68 attribute bytes, the program commarea, the
     * work area, the navigation commarea and the three {@code XCTL} targets.
     *
     * @param other the object to compare with
     * @return {@code true} when every one of those is equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardUpdateResponse response)) {
            return false;
        }
        return payload.equals(response.payload)
                && attributes.equals(response.attributes)
                && commArea.equals(response.commArea)
                && stateToken.equals(response.stateToken)
                && cardScreenState.equals(response.cardScreenState)
                && navigationContext.equals(response.navigationContext)
                && nextProgram.equals(response.nextProgram)
                && nextMapset.equals(response.nextMapset)
                && nextMap.equals(response.nextMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(payload, attributes, commArea, stateToken, cardScreenState,
                navigationContext,
                nextProgram, nextMapset, nextMap);
    }

    /**
     * A structural summary: which screen this is, how wide its group is, which change action it carries and
     * where it points next.
     *
     * @return a single-line summary
     */
    @Override
    public String toString() {
        return "CardUpdateResponse[" + MAPSET_NAME + "/" + OUTPUT_GROUP_NAME + " " + GROUP_LENGTH
                + "B, txn=" + TRANSACTION_ID + ", changeAction="
                + commArea.changeAction().describe() + ", next=" + nextProgram.strip() + "/"
                + nextMapset.strip() + "/" + nextMap.strip() + "]";
    }

}
