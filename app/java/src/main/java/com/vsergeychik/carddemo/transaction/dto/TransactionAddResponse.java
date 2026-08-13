package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
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
import com.vsergeychik.carddemo.transaction.dto.TransactionAddRequest.Ct01Info;
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
 * The outbound REST payload for CSD transaction {@code CT01}, program {@code COTRN01C}: a field-for-field
 * projection of the {@code xxxO} items of {@code 01 COTRN1AO REDEFINES COTRN1AI} in
 * {@code app/cpy-bms/COTRN01.CPY} (line 145) and their name-labelled {@code DFHMDF} definitions in
 * {@code app/bms/COTRN01.bms}.
 *
 * <p>Exactly one of its 21 named fields is {@code UNPROT} - {@code TRNIDIN}, at
 * {@code app/bms/COTRN01.bms:85}.
 */
@JsonPropertyOrder({
        "trnname", "title01", "curdate", "pgmname", "title02", "curtime", "trnidin", "trnid",
        "cardnum", "ttypcd", "tcatcd", "trnsrc", "tdesc", "trnamt", "torigdt", "tprocdt", "mid",
        "mname", "mcity", "mzip", "errmsg",
        "nextProgram", "nextMapset", "nextMap", "navigationContext", "ct01Info"})
public final class TransactionAddResponse {
    /**
     * The CSD transaction that reaches this screen: {@code CT01}, defined at
     * {@code app/csd/CARDDEMO.CSD:429-430} as {@code DEFINE TRANSACTION(CT01) PROGRAM(COTRN01C)}.
     */
    public static final String TRANSACTION_ID = "CT01";

    /**
     * The program this screen belongs to: {@code COTRN01C}, defined at {@code app/csd/CARDDEMO.CSD:264} and
     * declared as {@code 05 WS-PGMNAME PIC X(08) VALUE 'COTRN01C'} at {@code app/cbl/COTRN01C.cbl:36},
     * which is the value moved into {@link ScreenField#PGMNAMEO}.
     */
    public static final String PROGRAM_NAME = "COTRN01C";

    /**
     * The BMS mapset: {@code COTRN01}, defined at {@code app/csd/CARDDEMO.CSD:149} and named by
     * {@code MAPSET('COTRN01')} on the {@code SEND} and {@code RECEIVE} at {@code app/cbl/COTRN01C.cbl:221}
     * and {@code :233}.
     */
    public static final String MAPSET_NAME = "COTRN01";

    /**
     * The BMS map: {@code COTRN1A}, named by {@code MAP('COTRN1A')} on the same two CICS commands.
     */
    public static final String MAP_NAME = "COTRN1A";

    /**
     * The input symbolic-map group name, {@code COTRN1AI} - {@code app/cpy-bms/COTRN01.CPY:17}.
     */
    public static final String INPUT_MAP_GROUP_NAME = MAP_NAME + "I";

    /**
     * The output symbolic-map group name, {@code COTRN1AO} - {@code app/cpy-bms/COTRN01.CPY:145}.
     */
    public static final String OUTPUT_MAP_GROUP_NAME = MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX;

    /**
     * The leading {@code 02 FILLER PIC X(12)} at {@code app/cpy-bms/COTRN01.CPY:146}: the
     * {@code TIOAPFX=YES} prefix CICS reserves ahead of the first field.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that opens each field group in the output view.
     */
    public static final int FIELD_GROUP_FILLER_LENGTH = 3;

    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    public static final int FIELD_ATTRIBUTE_PREFIX_LENGTH =
            FIELD_GROUP_FILLER_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    public static final int PAYLOAD_FIELD_COUNT = 21;

    public static final int TOTAL_PAYLOAD_WIDTH = sumPayloadWidths();

    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH
                    + PAYLOAD_FIELD_COUNT * FIELD_ATTRIBUTE_PREFIX_LENGTH
                    + TOTAL_PAYLOAD_WIDTH;

    /**
     * The area {@code COTRN01C} passes on its {@code XCTL} and {@code RETURN}: {@code 160 + 58 =} 218 bytes
     * - the 160-byte {@code CARDDEMO-COMMAREA} from {@code app/cpy/COCOM01Y.cpy} followed by the 58-byte
     * {@code CDEMO-CT01-INFO} extension declared in place at {@code app/cbl/COTRN01C.cbl:53-61}.
     */
    public static final int PASSED_COMMAREA_LENGTH =
            NavigationContext.COMMAREA_LENGTH + Ct01Info.RECORD_LENGTH;

    /**
     * Width of {@code CDEMO-TO-PROGRAM}, {@code PIC X(08)} - {@code app/cpy/COCOM01Y.cpy:24}.
     */
    public static final int NEXT_PROGRAM_LENGTH = NavigationContext.TO_PROGRAM_LENGTH;

    /**
     * Width of {@code CDEMO-LAST-MAPSET}, {@code PIC X(7)} - {@code app/cpy/COCOM01Y.cpy:44}.
     */
    public static final int NEXT_MAPSET_LENGTH = NavigationContext.LAST_MAPSET_LENGTH;

    /**
     * Width of {@code CDEMO-LAST-MAP}, {@code PIC X(7)} - {@code app/cpy/COCOM01Y.cpy:43}.
     */
    public static final int NEXT_MAP_LENGTH = NavigationContext.LAST_MAP_LENGTH;

    /**
     * Width of {@code 05 WS-MESSAGE PIC X(80)} at {@code app/cbl/COTRN01C.cbl:38}, the field whose contents
     * line 217 moves into {@link ScreenField#ERRMSGO}.
     */
    public static final int WS_MESSAGE_LENGTH = 80;

    /**
     * The width of both title items, {@link ScreenField#TITLE01O} and {@link ScreenField#TITLE02O}:
     * {@link ScreenTitles#TITLE_LENGTH}, which is 40.
     *
     * <p>{@code POPULATE-HEADER-INFO} moves {@code CCDA-TITLE01} and {@code CCDA-TITLE02} into them at
     * {@code app/cbl/COTRN01C.cbl:247-248}, so the literals and the screen fields must agree exactly.
     */
    public static final int TITLE_ITEM_LENGTH = ScreenTitles.TITLE_LENGTH;

    /**
     * The width of {@link ScreenField#CURDATEO}: {@link DateHeader#WS_CURDATE_MM_DD_YY_LENGTH}, which is 8
     * - the width of {@code WS-CURDATE-MM-DD-YY}, moved in at {@code app/cbl/COTRN01C.cbl:258}.
     */
    public static final int CURDATE_ITEM_LENGTH = DateHeader.WS_CURDATE_MM_DD_YY_LENGTH;

    /**
     * The width of {@link ScreenField#CURTIMEO}: {@link DateHeader#WS_CURTIME_HH_MM_SS_LENGTH}, which is 8
     * - the width of {@code WS-CURTIME-HH-MM-SS}, moved in at {@code app/cbl/COTRN01C.cbl:262}.
     */
    public static final int CURTIME_ITEM_LENGTH = DateHeader.WS_CURTIME_HH_MM_SS_LENGTH;

    /**
     * The width of a standard message literal, {@link SystemMessages#MESSAGE_LENGTH}, which is 50.
     *
     * <p>Only a caller composing its own message longer than 78 characters is truncated, and then on the
     * right, as COBOL truncates.
     */
    public static final int STANDARD_MESSAGE_LENGTH = SystemMessages.MESSAGE_LENGTH;

    /**
     * The 21 payload fields of {@code COTRN1AO}, in declaration order.
     */
    public enum ScreenField {
        /**
         * {@code TRNNAMEO PIC X(4)} - copybook line 152.
         */
        TRNNAMEO("TRNNAME", 12, 4),

        /**
         * {@code TITLE01O PIC X(40)} - copybook line 158.
         */
        TITLE01O("TITLE01", 23, 40),

        /**
         * {@code CURDATEO PIC X(8)} - copybook line 164.
         */
        CURDATEO("CURDATE", 70, 8),

        /**
         * {@code PGMNAMEO PIC X(8)} - copybook line 170.
         */
        PGMNAMEO("PGMNAME", 85, 8),

        /**
         * {@code TITLE02O PIC X(40)} - copybook line 176.
         */
        TITLE02O("TITLE02", 100, 40),

        /**
         * {@code CURTIMEO PIC X(8)} - copybook line 182.
         */
        CURTIMEO("CURTIME", 147, 8),

        /**
         * {@code TRNIDINO PIC X(16)} - copybook line 188.
         */
        TRNIDINO("TRNIDIN", 162, 16),

        /**
         * {@code TRNIDO PIC X(16)} - copybook line 194.
         */
        TRNIDO("TRNID", 185, 16),

        /**
         * {@code CARDNUMO PIC X(16)} - copybook line 200.
         */
        CARDNUMO("CARDNUM", 208, 16),

        /**
         * {@code TTYPCDO PIC X(2)} - copybook line 206.
         */
        TTYPCDO("TTYPCD", 231, 2),

        /**
         * {@code TCATCDO PIC X(4)} - copybook line 212.
         */
        TCATCDO("TCATCD", 240, 4),

        /**
         * {@code TRNSRCO PIC X(10)} - copybook line 218.
         */
        TRNSRCO("TRNSRC", 251, 10),

        /**
         * {@code TDESCO PIC X(60)} - copybook line 224.
         */
        TDESCO("TDESC", 268, 60),

        /**
         * {@code TRNAMTO PIC X(12)} - copybook line 230.
         */
        TRNAMTO("TRNAMT", 335, 12),

        /**
         * {@code TORIGDTO PIC X(10)} - copybook line 236.
         */
        TORIGDTO("TORIGDT", 354, 10),

        /**
         * {@code TPROCDTO PIC X(10)} - copybook line 242.
         */
        TPROCDTO("TPROCDT", 371, 10),

        /**
         * {@code MIDO PIC X(9)} - copybook line 248.
         */
        MIDO("MID", 388, 9),

        /**
         * {@code MNAMEO PIC X(30)} - copybook line 254.
         */
        MNAMEO("MNAME", 404, 30),

        /**
         * {@code MCITYO PIC X(25)} - copybook line 260.
         */
        MCITYO("MCITY", 441, 25),

        /**
         * {@code MZIPO PIC X(10)} - copybook line 266.
         */
        MZIPO("MZIP", 473, 10),

        /**
         * {@code ERRMSGO PIC X(78)} - copybook line 272.
         */
        ERRMSGO("ERRMSG", 490, 78);

        private final String baseName;
        private final int groupOffset;
        private final int payloadLength;

        ScreenField(String baseName, int groupOffset, int payloadLength) {
            this.baseName = baseName;
            this.groupOffset = groupOffset;
            this.payloadLength = payloadLength;
        }

        /**
         * The base name the six items of this field share, for example {@code TRNNAME}, spelled exactly as
         * {@code app/cpy-bms/COTRN01.CPY} spells it and exactly as the label on the field's {@code DFHMDF}
         * in {@code app/bms/COTRN01.bms}.
         *
         * @return the verbatim base name; never {@code null} or empty
         */
        public String baseName() {
            return baseName;
        }

        /**
         * The verbatim name of the payload item: the base name followed by {@code O}, for example
         * {@code TRNNAMEO}.
         *
         * @return the {@code xxxO} item name; never {@code null}
         */
        public String outputItemName() {
            return baseName + FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;
        }

        /**
         * The verbatim name of the input alias: the base name followed by {@code I}, for example
         * {@code TRNNAMEI}.
         *
         * @return the {@code xxxI} item name; never {@code null}
         */
        public String inputItemName() {
            return baseName + "I";
        }

        /**
         * The verbatim name of the colour attribute item: the base name followed by {@code C}, for example
         * {@code TRNNAMEC}.
         *
         * @return the {@code xxxC} item name; never {@code null}
         */
        public String colourItemName() {
            return baseName + FieldAttributeSetter.COLOUR_ITEM_SUFFIX;
        }

        /**
         * The verbatim name of the programmed-symbols attribute item, the base name followed by {@code P}.
         *
         * @return the {@code xxxP} item name; never {@code null}
         */
        public String programmedSymbolsItemName() {
            return baseName + "P";
        }

        /**
         * The verbatim name of the highlight attribute item, the base name followed by {@code H}.
         *
         * @return the {@code xxxH} item name; never {@code null}
         */
        public String highlightItemName() {
            return baseName + "H";
        }

        /**
         * The verbatim name of the validation attribute item, the base name followed by {@code V}.
         *
         * @return the {@code xxxV} item name; never {@code null}
         */
        public String validationItemName() {
            return baseName + "V";
        }

        /**
         * This field's declared payload width in bytes, from the {@code xxxO} {@code PICTURE} clause.
         *
         * @return the width, at least 1
         */
        public int payloadLength() {
            return payloadLength;
        }

        /**
         * The absolute offset of this field's group - its leading {@code FILLER X(3)} - within the 575-byte
         * image.
         *
         * @return the group offset, at least {@link #TIOAPFX_PREFIX_LENGTH}
         */
        public int groupOffset() {
            return groupOffset;
        }

        /**
         * The absolute offset of the payload item: {@link #groupOffset()} plus the seven attribute-prefix
         * bytes.
         *
         * @return the payload offset
         */
        public int payloadOffset() {
            return groupOffset + FIELD_ATTRIBUTE_PREFIX_LENGTH;
        }

        /**
         * The absolute offset of the colour attribute item, three bytes past the group filler.
         *
         * @return the {@code xxxC} offset
         */
        public int colourItemOffset() {
            return groupOffset + FIELD_GROUP_FILLER_LENGTH;
        }

        /**
         * The absolute offset of the programmed-symbols attribute item.
         *
         * @return the {@code xxxP} offset
         */
        public int programmedSymbolsItemOffset() {
            return colourItemOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The absolute offset of the highlight attribute item.
         *
         * @return the {@code xxxH} offset
         */
        public int highlightItemOffset() {
            return programmedSymbolsItemOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The absolute offset of the validation attribute item.
         *
         * @return the {@code xxxV} offset
         */
        public int validationItemOffset() {
            return highlightItemOffset() + ATTRIBUTE_ITEM_LENGTH;
        }

        /**
         * The offset one past this field group's last byte, which is the next group's offset - or
         * {@link TransactionAddResponse#SYMBOLIC_MAP_LENGTH} for the last field.
         *
         * @return the exclusive end offset of the group
         */
        public int groupEndOffsetExclusive() {
            return groupOffset + FIELD_ATTRIBUTE_PREFIX_LENGTH + payloadLength;
        }

        /**
         * The payload item as a {@link FieldSpan} carrying its verbatim copybook name, for use with
         * {@link FixedWidthCodec}.
         *
         * @return the payload span; never {@code null}
         */
        public FieldSpan payloadSpan() {
            return FieldSpan.alphanumeric(outputItemName(), payloadOffset(), payloadLength);
        }

        /**
         * The colour attribute item as a {@link FieldSpan} carrying its verbatim copybook name.
         *
         * @return the {@code xxxC} span; never {@code null}
         */
        public FieldSpan colourSpan() {
            return FieldSpan.alphanumeric(colourItemName(), colourItemOffset(), ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The programmed-symbols attribute item as a {@link FieldSpan}.
         *
         * @return the {@code xxxP} span; never {@code null}
         */
        public FieldSpan programmedSymbolsSpan() {
            return FieldSpan.alphanumeric(programmedSymbolsItemName(), programmedSymbolsItemOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        public FieldSpan highlightSpan() {
            return FieldSpan.alphanumeric(highlightItemName(), highlightItemOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The validation attribute item as a {@link FieldSpan}.
         *
         * @return the {@code xxxV} span; never {@code null}
         */
        public FieldSpan validationSpan() {
            return FieldSpan.alphanumeric(validationItemName(), validationItemOffset(),
                    ATTRIBUTE_ITEM_LENGTH);
        }

        /**
         * The group's leading {@code FILLER X(3)} as a {@link FieldSpan}.
         *
         * @return the group filler span; never {@code null}
         */
        public FieldSpan groupFillerSpan() {
            return FieldSpan.filler(groupOffset, FIELD_GROUP_FILLER_LENGTH);
        }

        /**
         * A one-line description for diagnostics and for tracing a parity difference back to the copybook,
         * for example {@code TRNNAMEO PIC X(4) at 19 (group 12)}.
         *
         * @return the description; never {@code null}
         */
        public String describe() {
            return outputItemName() + " PIC X(" + payloadLength + ") at " + payloadOffset()
                    + " (group " + groupOffset + ")";
        }
    }

    /**
     * The complete {@code COTRN1AO} group layout: 106 spans describing all {@link #SYMBOLIC_MAP_LENGTH}
     * bytes - the leading {@code TIOAPFX} filler, then per field its {@code FILLER X(3)}, its four
     * single-byte attribute items and its payload item, in copybook declaration order.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    private static final FixedWidthCodec PICTURE_RULES = new FixedWidthCodec(StandardCharsets.US_ASCII);

    private final Map<ScreenField, String> payloadItems = new EnumMap<>(ScreenField.class);

    private final Map<ScreenField, AttributeQuad> attributeItems = new EnumMap<>(ScreenField.class);

    private String nextProgram;

    private String nextMapset;

    private String nextMap;

    private NavigationContext navigationContext;

    private Ct01Info ct01Info;

    /**
     * Creates a response in the state {@code COTRN01C} holds after {@code MOVE LOW-VALUES TO COTRN1AO}
     * ({@code app/cbl/COTRN01C.cbl:101}): every payload item carrying the unpainted image at its declared
     * width, every attribute item at its {@linkplain AttributeQuad#defaults() no-change default}, the
     * navigation targets set to this screen's own mapset and map, an empty {@link NavigationContext} and a
     * fresh.
     *
     * <p>An earlier revision of this constructor used the {@code INITIALIZE-ALL-FIELDS} image for both,
     * which conflated "never painted" with "painted blank".
     */
    public TransactionAddResponse() {
        for (ScreenField field : ScreenField.values()) {
            payloadItems.put(field, ScreenFieldImage.unpainted(field.payloadLength()));
            attributeItems.put(field, AttributeQuad.defaults());
        }
        this.nextProgram = spaces(NEXT_PROGRAM_LENGTH);
        this.nextMapset = movePicX(MAPSET_NAME, NEXT_MAPSET_LENGTH);
        this.nextMap = movePicX(MAP_NAME, NEXT_MAP_LENGTH);
        this.navigationContext = NavigationContext.empty();
        this.ct01Info = new Ct01Info();
    }

    // Each getter is a JSON member named after its xxxO item lowercased; each setter applies the PIC X move
    // rule, so an over-long value is truncated on the RIGHT exactly as COBOL truncates it and a short one
    // is padded on the right.

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier in the header.
     *
     * @return the value, always exactly 4 characters
     */
    @JsonProperty("trnname")
    public String getTrnnameo() {
        return payloadItems.get(ScreenField.TRNNAMEO);
    }

    /**
     * Sets {@code TRNNAMEO}, applying the {@code PIC X(4)} move rule.
     *
     * @param trnnameo the sending value; never {@code null}
     * @throws NullPointerException if {@code trnnameo} is {@code null}
     */
    public void setTrnnameo(String trnnameo) {
        setPayload(ScreenField.TRNNAMEO, trnnameo);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the first title line, {@link ScreenTitles#CCDA_TITLE01}.
     *
     * @return the value, always exactly 40 characters
     */
    @JsonProperty("title01")
    public String getTitle01o() {
        return payloadItems.get(ScreenField.TITLE01O);
    }

    /**
     * Sets {@code TITLE01O}, applying the {@code PIC X(40)} move rule.
     *
     * @param title01o the sending value; never {@code null}
     * @throws NullPointerException if {@code title01o} is {@code null}
     */
    public void setTitle01o(String title01o) {
        setPayload(ScreenField.TITLE01O, title01o);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code mm/dd/yy}.
     *
     * @return the value, always exactly 8 characters
     */
    @JsonProperty("curdate")
    public String getCurdateo() {
        return payloadItems.get(ScreenField.CURDATEO);
    }

    /**
     * Sets {@code CURDATEO}, applying the {@code PIC X(8)} move rule.
     *
     * @param curdateo the sending value; never {@code null}
     * @throws NullPointerException if {@code curdateo} is {@code null}
     */
    public void setCurdateo(String curdateo) {
        setPayload(ScreenField.CURDATEO, curdateo);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name in the header.
     *
     * @return the value, always exactly 8 characters
     */
    @JsonProperty("pgmname")
    public String getPgmnameo() {
        return payloadItems.get(ScreenField.PGMNAMEO);
    }

    /**
     * Sets {@code PGMNAMEO}, applying the {@code PIC X(8)} move rule.
     *
     * @param pgmnameo the sending value; never {@code null}
     * @throws NullPointerException if {@code pgmnameo} is {@code null}
     */
    public void setPgmnameo(String pgmnameo) {
        setPayload(ScreenField.PGMNAMEO, pgmnameo);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the second title line, {@link ScreenTitles#CCDA_TITLE02}.
     *
     * @return the value, always exactly 40 characters
     */
    @JsonProperty("title02")
    public String getTitle02o() {
        return payloadItems.get(ScreenField.TITLE02O);
    }

    /**
     * Sets {@code TITLE02O}, applying the {@code PIC X(40)} move rule.
     *
     * @param title02o the sending value; never {@code null}
     * @throws NullPointerException if {@code title02o} is {@code null}
     */
    public void setTitle02o(String title02o) {
        setPayload(ScreenField.TITLE02O, title02o);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code hh:mm:ss}.
     *
     * @return the value, always exactly 8 characters
     */
    @JsonProperty("curtime")
    public String getCurtimeo() {
        return payloadItems.get(ScreenField.CURTIMEO);
    }

    /**
     * Sets {@code CURTIMEO}, applying the {@code PIC X(8)} move rule.
     *
     * @param curtimeo the sending value; never {@code null}
     * @throws NullPointerException if {@code curtimeo} is {@code null}
     */
    public void setCurtimeo(String curtimeo) {
        setPayload(ScreenField.CURTIMEO, curtimeo);
    }

    /**
     * {@code TRNIDINO PIC X(16)} - the lookup key echoed back; the only enterable field on this screen.
     *
     * @return the value, always exactly 16 characters
     */
    @JsonProperty("trnidin")
    public String getTrnidino() {
        return payloadItems.get(ScreenField.TRNIDINO);
    }

    /**
     * Sets {@code TRNIDINO}, applying the {@code PIC X(16)} move rule.
     *
     * @param trnidino the sending value; never {@code null}
     * @throws NullPointerException if {@code trnidino} is {@code null}
     */
    public void setTrnidino(String trnidino) {
        setPayload(ScreenField.TRNIDINO, trnidino);
    }

    /**
     * {@code TRNIDO PIC X(16)} - the transaction identifier read back from the record.
     *
     * @return the value, always exactly 16 characters
     */
    @JsonProperty("trnid")
    public String getTrnido() {
        return payloadItems.get(ScreenField.TRNIDO);
    }

    /**
     * Sets {@code TRNIDO}, applying the {@code PIC X(16)} move rule.
     *
     * @param trnido the sending value; never {@code null}
     * @throws NullPointerException if {@code trnido} is {@code null}
     */
    public void setTrnido(String trnido) {
        setPayload(ScreenField.TRNIDO, trnido);
    }

    /**
     * {@code CARDNUMO PIC X(16)} - the card number, all sixteen digits, unmasked.
     *
     * @return the value, always exactly 16 characters
     */
    @JsonProperty("cardnum")
    public String getCardnumo() {
        return payloadItems.get(ScreenField.CARDNUMO);
    }

    /**
     * Sets {@code CARDNUMO}, applying the {@code PIC X(16)} move rule.
     *
     * @param cardnumo the sending value; never {@code null}
     * @throws NullPointerException if {@code cardnumo} is {@code null}
     */
    public void setCardnumo(String cardnumo) {
        setPayload(ScreenField.CARDNUMO, cardnumo);
    }

    /**
     * {@code TTYPCDO PIC X(2)} - the transaction type code.
     *
     * @return the value, always exactly 2 characters
     */
    @JsonProperty("ttypcd")
    public String getTtypcdo() {
        return payloadItems.get(ScreenField.TTYPCDO);
    }

    /**
     * Sets {@code TTYPCDO}, applying the {@code PIC X(2)} move rule.
     *
     * @param ttypcdo the sending value; never {@code null}
     * @throws NullPointerException if {@code ttypcdo} is {@code null}
     */
    public void setTtypcdo(String ttypcdo) {
        setPayload(ScreenField.TTYPCDO, ttypcdo);
    }

    /**
     * {@code TCATCDO PIC X(4)} - the transaction category code.
     *
     * @return the value, always exactly 4 characters
     */
    @JsonProperty("tcatcd")
    public String getTcatcdo() {
        return payloadItems.get(ScreenField.TCATCDO);
    }

    /**
     * Sets {@code TCATCDO}, applying the {@code PIC X(4)} move rule.
     *
     * @param tcatcdo the sending value; never {@code null}
     * @throws NullPointerException if {@code tcatcdo} is {@code null}
     */
    public void setTcatcdo(String tcatcdo) {
        setPayload(ScreenField.TCATCDO, tcatcdo);
    }

    /**
     * {@code TRNSRCO PIC X(10)} - the transaction source.
     *
     * @return the value, always exactly 10 characters
     */
    @JsonProperty("trnsrc")
    public String getTrnsrco() {
        return payloadItems.get(ScreenField.TRNSRCO);
    }

    /**
     * Sets {@code TRNSRCO}, applying the {@code PIC X(10)} move rule.
     *
     * @param trnsrco the sending value; never {@code null}
     * @throws NullPointerException if {@code trnsrco} is {@code null}
     */
    public void setTrnsrco(String trnsrco) {
        setPayload(ScreenField.TRNSRCO, trnsrco);
    }

    /**
     * {@code TDESCO PIC X(60)} - the transaction description.
     *
     * @return the value, always exactly 60 characters
     */
    @JsonProperty("tdesc")
    public String getTdesco() {
        return payloadItems.get(ScreenField.TDESCO);
    }

    /**
     * Sets {@code TDESCO}, applying the {@code PIC X(60)} move rule.
     *
     * @param tdesco the sending value; never {@code null}
     * @throws NullPointerException if {@code tdesco} is {@code null}
     */
    public void setTdesco(String tdesco) {
        setPayload(ScreenField.TDESCO, tdesco);
    }

    /**
     * {@code TRNAMTO PIC X(12)} - the edited amount, mask {@code +99999999.99}.
     *
     * <p>A {@code String}, never a {@code BigDecimal} and never a floating-point type: this is the
     * twelve-character edited image {@code WS-TRAN-AMT} produced, not the record's {@code PIC S9(09)V99}
     * value.
     *
     * @return the value, always exactly 12 characters
     */
    @JsonProperty("trnamt")
    public String getTrnamto() {
        return payloadItems.get(ScreenField.TRNAMTO);
    }

    /**
     * Sets {@code TRNAMTO}, applying the {@code PIC X(12)} move rule.
     *
     * @param trnamto the sending value, expected to carry the {@code +99999999.99} edit mask; never
     *     {@code null}
     * @throws NullPointerException if {@code trnamto} is {@code null}
     */
    public void setTrnamto(String trnamto) {
        setPayload(ScreenField.TRNAMTO, trnamto);
    }

    /**
     * {@code TORIGDTO PIC X(10)} - the origination date, the leading ten characters of the record's 26-byte
     * timestamp.
     *
     * @return the value, always exactly 10 characters
     */
    @JsonProperty("torigdt")
    public String getTorigdto() {
        return payloadItems.get(ScreenField.TORIGDTO);
    }

    /**
     * Sets {@code TORIGDTO}, applying the {@code PIC X(10)} move rule - which is what performs the
     * 26-into-10 right truncation when a full timestamp is supplied.
     *
     * @param torigdto the sending value; never {@code null}
     * @throws NullPointerException if {@code torigdto} is {@code null}
     */
    public void setTorigdto(String torigdto) {
        setPayload(ScreenField.TORIGDTO, torigdto);
    }

    /**
     * {@code TPROCDTO PIC X(10)} - the processing date, likewise the leading ten characters of a 26-byte
     * timestamp.
     *
     * @return the value, always exactly 10 characters
     */
    @JsonProperty("tprocdt")
    public String getTprocdto() {
        return payloadItems.get(ScreenField.TPROCDTO);
    }

    /**
     * Sets {@code TPROCDTO}, applying the {@code PIC X(10)} move rule.
     *
     * @param tprocdto the sending value; never {@code null}
     * @throws NullPointerException if {@code tprocdto} is {@code null}
     */
    public void setTprocdto(String tprocdto) {
        setPayload(ScreenField.TPROCDTO, tprocdto);
    }

    /**
     * {@code MIDO PIC X(9)} - the merchant identifier, at full width and unmasked for the same reason as
     * {@link #getCardnumo()}.
     *
     * @return the value, always exactly 9 characters
     */
    @JsonProperty("mid")
    public String getMido() {
        return payloadItems.get(ScreenField.MIDO);
    }

    /**
     * Sets {@code MIDO}, applying the {@code PIC X(9)} move rule.
     *
     * @param mido the sending value; never {@code null}
     * @throws NullPointerException if {@code mido} is {@code null}
     */
    public void setMido(String mido) {
        setPayload(ScreenField.MIDO, mido);
    }

    /**
     * {@code MNAMEO PIC X(30)} - the merchant name.
     *
     * @return the value, always exactly 30 characters
     */
    @JsonProperty("mname")
    public String getMnameo() {
        return payloadItems.get(ScreenField.MNAMEO);
    }

    /**
     * Sets {@code MNAMEO}, applying the {@code PIC X(30)} move rule.
     *
     * @param mnameo the sending value; never {@code null}
     * @throws NullPointerException if {@code mnameo} is {@code null}
     */
    public void setMnameo(String mnameo) {
        setPayload(ScreenField.MNAMEO, mnameo);
    }

    /**
     * {@code MCITYO PIC X(25)} - the merchant city.
     *
     * @return the value, always exactly 25 characters
     */
    @JsonProperty("mcity")
    public String getMcityo() {
        return payloadItems.get(ScreenField.MCITYO);
    }

    /**
     * Sets {@code MCITYO}, applying the {@code PIC X(25)} move rule.
     *
     * @param mcityo the sending value; never {@code null}
     * @throws NullPointerException if {@code mcityo} is {@code null}
     */
    public void setMcityo(String mcityo) {
        setPayload(ScreenField.MCITYO, mcityo);
    }

    /**
     * {@code MZIPO PIC X(10)} - the merchant postal code.
     *
     * @return the value, always exactly 10 characters
     */
    @JsonProperty("mzip")
    public String getMzipo() {
        return payloadItems.get(ScreenField.MZIPO);
    }

    /**
     * Sets {@code MZIPO}, applying the {@code PIC X(10)} move rule.
     *
     * @param mzipo the sending value; never {@code null}
     * @throws NullPointerException if {@code mzipo} is {@code null}
     */
    public void setMzipo(String mzipo) {
        setPayload(ScreenField.MZIPO, mzipo);
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line, fed by {@code WS-MESSAGE}.
     *
     * @return the value, always exactly 78 characters
     */
    @JsonProperty("errmsg")
    public String getErrmsgo() {
        return payloadItems.get(ScreenField.ERRMSGO);
    }

    /**
     * Sets {@code ERRMSGO}, applying the {@code PIC X(78)} move rule.
     *
     * @param errmsgo the sending value; never {@code null}
     * @throws NullPointerException if {@code errmsgo} is {@code null}
     */
    public void setErrmsgo(String errmsgo) {
        setPayload(ScreenField.ERRMSGO, errmsgo);
    }

    /**
     * Reads one payload item by field, untrimmed at its declared width.
     *
     * @param field which field to read; never {@code null}
     * @return the value, exactly {@code field.payloadLength()} characters
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public String payload(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read a payload item");
        return payloadItems.get(field);
    }

    /**
     * Writes one payload item by field, applying that field's {@code PIC X} move rule: padded on the right
     * when short, truncated on the right when long.
     *
     * @param field which field to write; never {@code null}
     * @param value the sending value; never {@code null}
     * @throws NullPointerException if {@code field} or {@code value} is {@code null}
     */
    public void setPayload(ScreenField field, String value) {
        Objects.requireNonNull(field, "A field is required to write a payload item");
        Objects.requireNonNull(value, "A sending value is required; to blank a screen field move an "
                + "empty string or spaces explicitly, because a COBOL alphanumeric item has no "
                + "absent state");
        payloadItems.put(field, movePicX(value, field.payloadLength()));
    }

    /**
     * The 21 payload items as an immutable map keyed by the geometry table, in declaration order - the form
     * the parity differ compares field by field.
     *
     * @return an unmodifiable snapshot; never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, String> payloadItems() {
        return Collections.unmodifiableMap(new EnumMap<>(payloadItems));
    }

    @JsonIgnore
    public AttributeQuad attributes(ScreenField field) {
        Objects.requireNonNull(field, "A field is required to read its attribute items");
        return attributeItems.get(field);
    }

    /**
     * Replaces the four attribute items of one field.
     *
     * @param field which field; never {@code null}
     * @param attributes the replacement quad; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void setAttributes(ScreenField field, AttributeQuad attributes) {
        Objects.requireNonNull(field, "A field is required to write its attribute items");
        Objects.requireNonNull(attributes, "An attribute quad is required");
        attributeItems.put(field, attributes);
    }

    /**
     * All 84 attribute items, as one immutable map of 21 quads in declaration order.
     *
     * @return an unmodifiable snapshot; never {@code null}
     */
    @JsonIgnore
    public Map<ScreenField, AttributeQuad> attributeItems() {
        return Collections.unmodifiableMap(new EnumMap<>(attributeItems));
    }

    /**
     * Applies a {@code CSSETATY} decision to one field: the reach {@code FieldAttributeSetter} needs into
     * both of the items the copybook writes.
     *
     * @param field the field the decision was made for; never {@code null}
     * @param highlight the decision, as returned by {@link FieldAttributeSetter}; never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public void applyHighlight(ScreenField field, FieldHighlight highlight) {
        Objects.requireNonNull(field, "A field is required to apply a highlight");
        Objects.requireNonNull(highlight, "A highlight decision is required; FieldAttributeSetter "
                + "returns FieldHighlight.none(..) rather than null when nothing is to be done");

        if (highlight.colourItemAssigned()) {
            attributeItems.put(field, attributes(field).withColour(highlight.colourItemValue()));
        }
        if (highlight.outputItemAssigned()) {
            setPayload(field, highlight.outputItemValue());
        }
    }

    /**
     * Resolves the {@code CSSETATY} decision for one field through {@link FieldAttributeSetter} and applies
     * it, carrying this screen's own field and map identity into the decision for diagnostics.
     *
     * @param field the field being validated; never {@code null}
     * @param notOk {@code true} when {@code FLG-<field>-NOT-OK} holds
     * @param blank {@code true} when {@code FLG-<field>-BLANK} holds
     * @param reenter {@code true} when {@code CDEMO-PGM-REENTER} holds, that is when
     *     {@code CDEMO-PGM-CONTEXT} is 1
     * @return the decision that was applied, so a caller or a test can inspect it; never {@code null}
     * @throws NullPointerException if {@code field} is {@code null}
     */
    public FieldHighlight highlightField(ScreenField field, boolean notOk, boolean blank,
            boolean reenter) {
        Objects.requireNonNull(field, "A field is required to highlight");
        FieldHighlight highlight = FieldAttributeSetter.resolveFromFlags(notOk, blank, reenter,
                field.baseName(), MAP_NAME);
        applyHighlight(field, highlight);
        return highlight;
    }

    /**
     * The program the client should call next - {@code CDEMO-TO-PROGRAM}, echoed from the
     * {@link NavigationContext} by the controller.
     *
     * @return the value, always exactly {@link #NEXT_PROGRAM_LENGTH} characters
     */
    public String getNextProgram() {
        return nextProgram;
    }

    /**
     * Sets the next program, applying the {@code PIC X(8)} move rule.
     *
     * @param nextProgram the program name; never {@code null}
     * @throws NullPointerException if {@code nextProgram} is {@code null}
     */
    public void setNextProgram(String nextProgram) {
        Objects.requireNonNull(nextProgram, "A next-program value is required; move spaces "
                + "explicitly to mean 'none'");
        this.nextProgram = movePicX(nextProgram, NEXT_PROGRAM_LENGTH);
    }

    /**
     * The mapset of the next screen.
     *
     * @return the value, always exactly {@link #NEXT_MAPSET_LENGTH} characters
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Sets the next mapset, applying the {@code PIC X(7)} move rule.
     *
     * @param nextMapset the mapset name; never {@code null}
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     */
    public void setNextMapset(String nextMapset) {
        Objects.requireNonNull(nextMapset, "A next-mapset value is required");
        this.nextMapset = movePicX(nextMapset, NEXT_MAPSET_LENGTH);
    }

    public String getNextMap() {
        return nextMap;
    }

    /**
     * Sets the next map, applying the {@code PIC X(7)} move rule.
     *
     * @param nextMap the map name; never {@code null}
     * @throws NullPointerException if {@code nextMap} is {@code null}
     */
    public void setNextMap(String nextMap) {
        Objects.requireNonNull(nextMap, "A next-map value is required");
        this.nextMap = movePicX(nextMap, NEXT_MAP_LENGTH);
    }

    /**
     * The 160-byte {@code CARDDEMO-COMMAREA}, echoed so the client can send it back on the next call.
     *
     * @return the context; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A navigation context is required; use NavigationContext.empty() for an unset "
                        + "commarea rather than null");
    }

    /**
     * The 58-byte {@code CDEMO-CT01-INFO} cursor, echoed so the client can send it back.
     *
     * @return the cursor; never {@code null}
     */
    public Ct01Info getCt01Info() {
        return ct01Info;
    }

    public void setCt01Info(Ct01Info ct01Info) {
        this.ct01Info = Objects.requireNonNull(ct01Info,
                "A CT01 cursor is required; use a fresh Ct01Info for an unset cursor rather "
                        + "than null");
    }

    /**
     * Renders this response as the {@link #SYMBOLIC_MAP_LENGTH}-byte {@code COTRN1AO} group image.
     *
     * <p>Every payload item is written into its declared span through the {@code PIC X} move rule and every
     * attribute item as the single raw byte it is, so the result is exactly 575 bytes with each field at
     * the offset {@code app/cpy-bms/COTRN01.CPY} gives it.
     *
     * @param charset the code page to encode into, stated explicitly by the caller
     * @return a fresh array of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode every digit, sign overpunch
     *     character and the space to exactly one byte
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render COTRN1AO as bytes: a "
                + "fixed-width image is bytes in a specific code page, so the code page must be "
                + "stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes this response into an existing record area, using that record's own code page.
     *
     * @param record a record area of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write COTRN1AO into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Rebuilds a response from a {@code COTRN1AO} group image.
     *
     * @param bytes the image, exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @param charset the code page the image is encoded in, stated explicitly by the caller
     * @return a response holding the 21 payload items and 84 attribute items the image carries
     * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    public static TransactionAddResponse fromFixedWidth(byte[] bytes, Charset charset) {
        Objects.requireNonNull(bytes, "An image is required to rebuild COTRN1AO");
        Objects.requireNonNull(charset, "A charset is required to decode a COTRN1AO image: the code "
                + "page must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(bytes, LAYOUT), codec);
    }

    /**
     * Reads a response out of an existing record area, using that record's own code page.
     *
     * @param record a record area of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @return a response holding the items the record carries
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    public static TransactionAddResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read COTRN1AO from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Renders the {@link #PASSED_COMMAREA_LENGTH}-byte area {@code COTRN01C} passes on its {@code XCTL} and
     * {@code RETURN}: the 160-byte {@code CARDDEMO-COMMAREA} followed immediately by the 58-byte
     * {@code CDEMO-CT01-INFO} extension, exactly as {@code app/cbl/COTRN01C.cbl:52-61} lays them out.
     *
     * @param charset the code page to encode into, stated explicitly by the caller
     * @return a fresh array of exactly {@link #PASSED_COMMAREA_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for the digits and the space
     */
    public byte[] toPassedCommarea(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render the passed commarea");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        byte[] commarea = navigationContext.toFixedWidth(codec);
        byte[] cursor = ct01Info.toFixedWidth(codec);
        byte[] passed = new byte[PASSED_COMMAREA_LENGTH];
        System.arraycopy(commarea, 0, passed, 0, NavigationContext.COMMAREA_LENGTH);
        System.arraycopy(cursor, 0, passed, NavigationContext.COMMAREA_LENGTH, cursor.length);
        return passed;
    }

    /**
     * Rebuilds the commarea and the cursor from a {@link #PASSED_COMMAREA_LENGTH}-byte passed area, leaving
     * every screen field untouched.
     *
     * @param passed the image, exactly {@link #PASSED_COMMAREA_LENGTH} bytes
     * @param charset the code page the image is encoded in, stated explicitly by the caller
     * @throws NullPointerException if {@code passed} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code passed.length} is not {@link #PASSED_COMMAREA_LENGTH}
     */
    public void readPassedCommarea(byte[] passed, Charset charset) {
        Objects.requireNonNull(passed, "A passed-commarea image is required");
        Objects.requireNonNull(charset, "A charset is required to decode the passed commarea");
        if (passed.length != PASSED_COMMAREA_LENGTH) {
            throw new IllegalArgumentException("A passed commarea is exactly "
                    + PASSED_COMMAREA_LENGTH + " bytes - " + NavigationContext.COMMAREA_LENGTH
                    + " of CARDDEMO-COMMAREA plus " + Ct01Info.RECORD_LENGTH
                    + " of CDEMO-CT01-INFO - but " + passed.length + " byte(s) were supplied");
        }
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        byte[] commarea = new byte[NavigationContext.COMMAREA_LENGTH];
        byte[] cursor = new byte[Ct01Info.RECORD_LENGTH];
        System.arraycopy(passed, 0, commarea, 0, commarea.length);
        System.arraycopy(passed, commarea.length, cursor, 0, cursor.length);
        this.navigationContext = NavigationContext.fromFixedWidth(codec, commarea);
        this.ct01Info = Ct01Info.fromFixedWidth(codec, cursor);
    }

    /**
     * A diagnostic rendering naming the screen, its geometry and the lookup key - enough to identify which
     * screen instance a parity difference came from without dumping 575 bytes into a log.
     *
     * @return the description; never {@code null}
     */
    @Override
    public String toString() {
        return "TransactionAddResponse[" + TRANSACTION_ID + '/' + PROGRAM_NAME
                + ", map=" + MAPSET_NAME + '.' + MAP_NAME
                + ", fields=" + PAYLOAD_FIELD_COUNT
                + ", image=" + SYMBOLIC_MAP_LENGTH + "B"
                + ", " + ScreenField.TRNIDINO.outputItemName() + "='" + getTrnidino() + '\''
                + ", nextProgram='" + nextProgram + '\''
                + ']';
    }

    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireSymbolicMapWidth(record);
        for (ScreenField field : ScreenField.values()) {
            codec.writePicX(record, field.payloadSpan(), payloadItems.get(field));
            AttributeQuad quad = attributeItems.get(field);
            record.writeSpanBytes(field.colourSpan(), new byte[] {quad.colour()});
            record.writeSpanBytes(field.programmedSymbolsSpan(), new byte[] {quad.programmedSymbols()});
            record.writeSpanBytes(field.highlightSpan(), new byte[] {quad.highlight()});
            record.writeSpanBytes(field.validationSpan(), new byte[] {quad.validation()});
        }
    }

    private static TransactionAddResponse readFrom(FixedWidthRecord record, FixedWidthCodec codec) {
        requireSymbolicMapWidth(record);
        TransactionAddResponse response = new TransactionAddResponse();
        for (ScreenField field : ScreenField.values()) {
            response.payloadItems.put(field, codec.readPicX(record, field.payloadSpan()));
            response.attributeItems.put(field, new AttributeQuad(
                    record.readSpanBytes(field.colourSpan())[0],
                    record.readSpanBytes(field.programmedSymbolsSpan())[0],
                    record.readSpanBytes(field.highlightSpan())[0],
                    record.readSpanBytes(field.validationSpan())[0]));
        }
        return response;
    }

    private static void requireSymbolicMapWidth(FixedWidthRecord record) {
        if (record.recordLength() != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("The COTRN1AO group is exactly "
                    + SYMBOLIC_MAP_LENGTH + " bytes - " + TIOAPFX_PREFIX_LENGTH + " of TIOAPFX "
                    + "prefix, " + PAYLOAD_FIELD_COUNT + " x " + FIELD_ATTRIBUTE_PREFIX_LENGTH
                    + " of attribute prefixes and " + TOTAL_PAYLOAD_WIDTH + " of payload - but the "
                    + "record area declares " + record.recordLength() + " byte(s)");
        }
    }

    private static int sumPayloadWidths() {
        int total = 0;
        for (ScreenField field : ScreenField.values()) {
            total += field.payloadLength();
        }
        return total;
    }

    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        spans.add(FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        for (ScreenField field : ScreenField.values()) {
            spans.add(field.groupFillerSpan());
            spans.add(field.colourSpan());
            spans.add(field.programmedSymbolsSpan());
            spans.add(field.highlightSpan());
            spans.add(field.validationSpan());
            spans.add(field.payloadSpan());
        }
        return new RecordLayout(SYMBOLIC_MAP_LENGTH, spans);
    }

    private static String movePicX(String value, int length) {
        Objects.requireNonNull(value, "A sending value is required for an alphanumeric MOVE");
        return PICTURE_RULES.movePicX(value, length);
    }

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    /**
     * The four attribute items of one screen field: {@code xxxC} (colour), {@code xxxP} (programmed
     * symbols), {@code xxxH} (highlight) and {@code xxxV} (validation), as declared for every field in
     * {@code 01 COTRN1AO} - for example {@code TRNNAMEC}, {@code TRNNAMEP}, {@code TRNNAMEH} and
     * {@code TRNNAMEV} at {@code app/cpy-bms/COTRN01.CPY:148-151}.
     *
     * @param colour the {@code xxxC} item - the extended colour; the item {@code CSSETATY} moves
     *     {@link BmsAttributes#DFHRED} into
     * @param programmedSymbols the {@code xxxP} item - the programmed-symbol set
     * @param highlight the {@code xxxH} item - the extended highlight
     * @param validation the {@code xxxV} item - the validation attribute
     */
    public record AttributeQuad(byte colour, byte programmedSymbols, byte highlight, byte validation) {
        /**
         * The value every attribute item holds until a program assigns one: {@code 0x00}.
         */
        public static final byte NO_CHANGE = BmsAttributes.DFHDFCOL;

        /**
         * A quad with all four items at {@link #NO_CHANGE} - the state a field's attributes are in before
         * any program touches them.
         *
         * @return the default quad; never {@code null}
         */
        public static AttributeQuad defaults() {
            return new AttributeQuad(NO_CHANGE, NO_CHANGE, NO_CHANGE, NO_CHANGE);
        }

        /**
         * This quad with a different colour item, the move {@code CSSETATY} performs at
         * {@code app/cpy/CSSETATY.cpy:L21-L22}.
         *
         * @param newColour the replacement {@code xxxC} value, {@link BmsAttributes#DFHRED} in the error
         *     case
         * @return a new quad; never {@code null}
         */
        public AttributeQuad withColour(byte newColour) {
            return new AttributeQuad(newColour, programmedSymbols, highlight, validation);
        }

        /**
         * This quad with a different programmed-symbols item.
         *
         * @param newProgrammedSymbols the replacement {@code xxxP} value
         * @return a new quad; never {@code null}
         */
        public AttributeQuad withProgrammedSymbols(byte newProgrammedSymbols) {
            return new AttributeQuad(colour, newProgrammedSymbols, highlight, validation);
        }

        public AttributeQuad withHighlight(byte newHighlight) {
            return new AttributeQuad(colour, programmedSymbols, newHighlight, validation);
        }

        public AttributeQuad withValidation(byte newValidation) {
            return new AttributeQuad(colour, programmedSymbols, highlight, newValidation);
        }

        /**
         * Whether the colour item carries {@link BmsAttributes#DFHRED}, that is whether this field is
         * currently highlighted as being in error.
         *
         * @return {@code true} when the colour item is {@code DFHRED}
         */
        public boolean colouredRed() {
            return colour == BmsAttributes.DFHRED;
        }

        public boolean unassigned() {
            return colour == NO_CHANGE && programmedSymbols == NO_CHANGE && highlight == NO_CHANGE
                    && validation == NO_CHANGE;
        }
    }
}
