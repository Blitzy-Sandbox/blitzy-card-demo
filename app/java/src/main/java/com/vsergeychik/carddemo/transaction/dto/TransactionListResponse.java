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
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;
import com.vsergeychik.carddemo.common.SystemMessages;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The outbound payload of {@code GET /api/transactions} - CICS transaction {@code CT00}, program
 * {@code app/cbl/COTRN00C.cbl} (699 lines), map {@code COTRN0A} of mapset {@code COTRN00}.
 *
 * <p>The record field {@code TRAN-AMT} is {@code PIC S9(09)V99} (11 bytes, nine integer digits) in
 * {@code app/cpy/CVTRA05Y.cpy}, but the screen carries {@code 05 WS-TRAN-AMT PIC +99999999.99}
 * [{@code COTRN00C:56}] - sign, eight integer digits, a point and two fraction digits, exactly twelve
 * characters.
 */
public final class TransactionListResponse {
    /**
     * {@code WS-TRANID PIC X(04) VALUE 'CT00'} [{@code COTRN00C:37}], which {@code POPULATE-HEADER-INFO}
     * moves into {@code TRNNAMEO} at line 573.
     */
    public static final String TRANSACTION_ID = "CT00";

    /**
     * {@code WS-PGMNAME PIC X(08) VALUE 'COTRN00C'} [{@code COTRN00C:36}], which
     * {@code POPULATE-HEADER-INFO} moves into {@code PGMNAMEO} at line 574.
     */
    public static final String PROGRAM_NAME = "COTRN00C";

    /**
     * The mapset, {@code COTRN00 DFHMSD} in {@code app/bms/COTRN00.bms} and {@code DEFINE MAPSET(COTRN00)}
     * in {@code app/csd/CARDDEMO.CSD:145}.
     */
    public static final String MAPSET_NAME = "COTRN00";

    /**
     * The map, {@code COTRN0A DFHMDI COLUMN=1 LINE=1 SIZE=(24,80)} in {@code app/bms/COTRN00.bms}, named in
     * every {@code SEND MAP('COTRN0A')} and {@code RECEIVE MAP('COTRN0A')}.
     */
    public static final String MAP_NAME = "COTRN0A";

    /**
     * The symbolic output group this type projects: {@code 01 COTRN0AO REDEFINES COTRN0AI}
     * [{@code app/cpy-bms/COTRN00.CPY:373}].
     */
    public static final String OUTPUT_MAP_GROUP_NAME =
            MAP_NAME + FieldAttributeSetter.OUTPUT_MAP_SUFFIX;

    public static final int PAGE_SIZE = 10;

    public static final int ROW_COUNT = PAGE_SIZE;

    public static final int FIRST_ROW = 1;

    public static final int LAST_ROW = ROW_COUNT;

    public static final int HEADER_FIELD_COUNT = 8;

    public static final int ROW_FIELD_COUNT = 5;

    public static final int ERROR_FIELD_COUNT = 1;

    /**
     * The total payload field count, {@link #FIELD_COUNT} - which is {@link #HEADER_FIELD_COUNT} +
     * {@link #ROW_COUNT} x {@link #ROW_FIELD_COUNT} + {@link #ERROR_FIELD_COUNT}.
     *
     * <p>{@code app/bms/COTRN00.bms} declares 89 {@code DFHMDF} entries of which exactly these 59 carry a
     * name; the other 30 are unlabelled literals and are not payload.
     */
    public static final int FIELD_COUNT =
            HEADER_FIELD_COUNT + ROW_COUNT * ROW_FIELD_COUNT + ERROR_FIELD_COUNT;

    /**
     * {@code TRNNAMEO PIC X(4)}; {@code DFHMDF LENGTH=4 POS=(1,7)}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01O PIC X(40)}; {@code DFHMDF LENGTH=40 POS=(1,21)}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(1,71)}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(2,7)}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02O PIC X(40)}; {@code DFHMDF LENGTH=40 POS=(2,21)}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(2,71)}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENUMO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(4,71)}.
     */
    public static final int PAGENUM_LENGTH = 8;

    /**
     * {@code TRNIDINO PIC X(16)}; {@code DFHMDF LENGTH=16 POS=(6,21)}, the browse-start key.
     */
    public static final int TRNIDIN_LENGTH = 16;

    /**
     * {@code SEL000nO PIC X(1)}; {@code DFHMDF LENGTH=1 POS=(9+n,3)}, the row selector.
     */
    public static final int SEL_LENGTH = 1;

    /**
     * {@code TRNIDnnO PIC X(16)}; {@code DFHMDF LENGTH=16 POS=(9+n,8)}.
     */
    public static final int TRNID_LENGTH = 16;

    /**
     * {@code TDATEnnO PIC X(8)}; {@code DFHMDF LENGTH=8 POS=(9+n,27)}.
     */
    public static final int TDATE_LENGTH = 8;

    /**
     * {@code TDESCnnO PIC X(26)}; {@code DFHMDF LENGTH=26 POS=(9+n,38)}.
     */
    public static final int TDESC_LENGTH = 26;

    /**
     * {@code TAMT00nO PIC X(12)}; {@code DFHMDF LENGTH=12 POS=(9+n,67)}.
     */
    public static final int TAMT_LENGTH = 12;

    /**
     * {@code ERRMSGO PIC X(78)}; {@code DFHMDF LENGTH=78 POS=(23,1) ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     */
    public static final int ERRMSG_LENGTH = 78;

    /**
     * The leading {@code 02 FILLER PIC X(12)} of both symbolic groups, present because every mapset
     * declares {@code TIOAPFX=YES}.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * The {@code 02 FILLER PICTURE X(3)} that opens each field group in the output view, aliasing the input
     * view's {@code xxxL COMP PIC S9(4)} (2 bytes) and {@code xxxF PICTURE X} (1 byte).
     */
    public static final int ATTRIBUTE_FILLER_LENGTH = 3;

    /**
     * Each of {@code xxxC}, {@code xxxP}, {@code xxxH} and {@code xxxV} is {@code PICTURE X}: 1 byte.
     */
    public static final int ATTRIBUTE_ITEM_LENGTH = 1;

    public static final int ATTRIBUTE_ITEM_COUNT = 4;

    public static final int FIELD_PREFIX_LENGTH =
            ATTRIBUTE_FILLER_LENGTH + ATTRIBUTE_ITEM_COUNT * ATTRIBUTE_ITEM_LENGTH;

    public static final int HEADER_WIDTH_TOTAL = TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH
            + PGMNAME_LENGTH + TITLE02_LENGTH + CURTIME_LENGTH + PAGENUM_LENGTH + TRNIDIN_LENGTH;

    public static final int ROW_WIDTH =
            SEL_LENGTH + TRNID_LENGTH + TDATE_LENGTH + TDESC_LENGTH + TAMT_LENGTH;

    public static final int ROW_BLOCK_WIDTH = ROW_COUNT * ROW_WIDTH;

    public static final int PAYLOAD_WIDTH_TOTAL = HEADER_WIDTH_TOTAL + ROW_BLOCK_WIDTH + ERRMSG_LENGTH;

    public static final int RECORD_LENGTH =
            TIOAPFX_PREFIX_LENGTH + FIELD_COUNT * FIELD_PREFIX_LENGTH + PAYLOAD_WIDTH_TOTAL;

    public static final String COLOUR_ITEM_SUFFIX = FieldAttributeSetter.COLOUR_ITEM_SUFFIX;

    public static final String PS_ITEM_SUFFIX = "P";

    public static final String HIGHLIGHT_ITEM_SUFFIX = "H";

    public static final String VALIDATION_ITEM_SUFFIX = "V";

    public static final String OUTPUT_ITEM_SUFFIX = FieldAttributeSetter.OUTPUT_ITEM_SUFFIX;

    private static final String FILLER_NAME = "FILLER";

    private static final String SPACE = " ";

    private static final char LOW_VALUE_CHARACTER = '\u0000';

    /**
     * Header field 1: the transaction identifier display, {@code TRNNAMEO PIC X(4)}.
     */
    public static final String TRNNAME = "TRNNAME";

    /**
     * Header field 2: the upper title line, {@code TITLE01O PIC X(40)}.
     */
    public static final String TITLE01 = "TITLE01";

    /**
     * Header field 3: the current date, {@code CURDATEO PIC X(8)}.
     */
    public static final String CURDATE = "CURDATE";

    /**
     * Header field 4: the program name display, {@code PGMNAMEO PIC X(8)}.
     */
    public static final String PGMNAME = "PGMNAME";

    /**
     * Header field 5: the lower title line, {@code TITLE02O PIC X(40)}.
     */
    public static final String TITLE02 = "TITLE02";

    /**
     * Header field 6: the current time, {@code CURTIMEO PIC X(8)}.
     */
    public static final String CURTIME = "CURTIME";

    /**
     * Header field 7: the displayed page number, {@code PAGENUMO PIC X(8)}.
     */
    public static final String PAGENUM = "PAGENUM";

    /**
     * Header field 8: the browse-start transaction identifier, {@code TRNIDINO PIC X(16)}.
     */
    public static final String TRNIDIN = "TRNIDIN";

    public static final String SEL0001 = "SEL0001";

    public static final String SEL0002 = "SEL0002";

    public static final String SEL0003 = "SEL0003";

    public static final String SEL0004 = "SEL0004";

    public static final String SEL0005 = "SEL0005";

    public static final String SEL0006 = "SEL0006";

    public static final String SEL0007 = "SEL0007";

    public static final String SEL0008 = "SEL0008";

    public static final String SEL0009 = "SEL0009";

    public static final String SEL0010 = "SEL0010";

    public static final String TRNID01 = "TRNID01";

    public static final String TRNID02 = "TRNID02";

    public static final String TRNID03 = "TRNID03";

    public static final String TRNID04 = "TRNID04";

    public static final String TRNID05 = "TRNID05";

    public static final String TRNID06 = "TRNID06";

    public static final String TRNID07 = "TRNID07";

    public static final String TRNID08 = "TRNID08";

    public static final String TRNID09 = "TRNID09";

    public static final String TRNID10 = "TRNID10";

    public static final String TDATE01 = "TDATE01";

    public static final String TDATE02 = "TDATE02";

    public static final String TDATE03 = "TDATE03";

    public static final String TDATE04 = "TDATE04";

    public static final String TDATE05 = "TDATE05";

    public static final String TDATE06 = "TDATE06";

    public static final String TDATE07 = "TDATE07";

    public static final String TDATE08 = "TDATE08";

    public static final String TDATE09 = "TDATE09";

    public static final String TDATE10 = "TDATE10";

    public static final String TDESC01 = "TDESC01";

    public static final String TDESC02 = "TDESC02";

    public static final String TDESC03 = "TDESC03";

    public static final String TDESC04 = "TDESC04";

    public static final String TDESC05 = "TDESC05";

    public static final String TDESC06 = "TDESC06";

    public static final String TDESC07 = "TDESC07";

    public static final String TDESC08 = "TDESC08";

    public static final String TDESC09 = "TDESC09";

    public static final String TDESC10 = "TDESC10";

    public static final String TAMT001 = "TAMT001";

    public static final String TAMT002 = "TAMT002";

    public static final String TAMT003 = "TAMT003";

    public static final String TAMT004 = "TAMT004";

    public static final String TAMT005 = "TAMT005";

    public static final String TAMT006 = "TAMT006";

    public static final String TAMT007 = "TAMT007";

    public static final String TAMT008 = "TAMT008";

    public static final String TAMT009 = "TAMT009";

    public static final String TAMT010 = "TAMT010";

    /**
     * The error line, {@code ERRMSGO PIC X(78)}.
     */
    public static final String ERRMSG = "ERRMSG";

    /**
     * One field of the map: its verbatim prefix, its declared payload width, and the pair of functions that
     * read and write the flat member behind it.
     *
     * @param fieldPrefix the verbatim {@code DFHMDF} label, for example {@code TAMT001}
     * @param declaredLength the payload item's {@code PIC X(n)} width, which is also the
     *     {@code DFHMDF LENGTH=}
     * @param reader reads the flat member, returning the stored value untrimmed
     * @param writer writes the flat member, through the same validation the setter applies
     */
    private record PayloadField(String fieldPrefix,
                                int declaredLength,
                                Function<TransactionListResponse, String> reader,
                                BiConsumer<TransactionListResponse, String> writer) {
        String outputItemName() {
            return fieldPrefix + OUTPUT_ITEM_SUFFIX;
        }

        String colourItemName() {
            return fieldPrefix + COLOUR_ITEM_SUFFIX;
        }
    }

    private static final Map<String, PayloadField> PAYLOAD_FIELDS = buildPayloadFields();

    private static final List<String> FIELD_PREFIXES = List.copyOf(PAYLOAD_FIELDS.keySet());

    private static final List<String> PAYLOAD_FIELD_NAMES = PAYLOAD_FIELDS.values().stream()
            .map(PayloadField::outputItemName)
            .toList();

    public static final RecordLayout LAYOUT = buildLayout();

    private static Map<String, PayloadField> buildPayloadFields() {
        Map<String, PayloadField> fields = new LinkedHashMap<>();

        put(fields, TRNNAME, TRNNAME_LENGTH,
                TransactionListResponse::getTrnnameO, TransactionListResponse::setTrnnameO);
        put(fields, TITLE01, TITLE01_LENGTH,
                TransactionListResponse::getTitle01O, TransactionListResponse::setTitle01O);
        put(fields, CURDATE, CURDATE_LENGTH,
                TransactionListResponse::getCurdateO, TransactionListResponse::setCurdateO);
        put(fields, PGMNAME, PGMNAME_LENGTH,
                TransactionListResponse::getPgmnameO, TransactionListResponse::setPgmnameO);
        put(fields, TITLE02, TITLE02_LENGTH,
                TransactionListResponse::getTitle02O, TransactionListResponse::setTitle02O);
        put(fields, CURTIME, CURTIME_LENGTH,
                TransactionListResponse::getCurtimeO, TransactionListResponse::setCurtimeO);
        put(fields, PAGENUM, PAGENUM_LENGTH,
                TransactionListResponse::getPagenumO, TransactionListResponse::setPagenumO);
        put(fields, TRNIDIN, TRNIDIN_LENGTH,
                TransactionListResponse::getTrnidinO, TransactionListResponse::setTrnidinO);

        put(fields, SEL0001, SEL_LENGTH,
                TransactionListResponse::getSel0001O, TransactionListResponse::setSel0001O);
        put(fields, TRNID01, TRNID_LENGTH,
                TransactionListResponse::getTrnid01O, TransactionListResponse::setTrnid01O);
        put(fields, TDATE01, TDATE_LENGTH,
                TransactionListResponse::getTdate01O, TransactionListResponse::setTdate01O);
        put(fields, TDESC01, TDESC_LENGTH,
                TransactionListResponse::getTdesc01O, TransactionListResponse::setTdesc01O);
        put(fields, TAMT001, TAMT_LENGTH,
                TransactionListResponse::getTamt001O, TransactionListResponse::setTamt001O);

        put(fields, SEL0002, SEL_LENGTH,
                TransactionListResponse::getSel0002O, TransactionListResponse::setSel0002O);
        put(fields, TRNID02, TRNID_LENGTH,
                TransactionListResponse::getTrnid02O, TransactionListResponse::setTrnid02O);
        put(fields, TDATE02, TDATE_LENGTH,
                TransactionListResponse::getTdate02O, TransactionListResponse::setTdate02O);
        put(fields, TDESC02, TDESC_LENGTH,
                TransactionListResponse::getTdesc02O, TransactionListResponse::setTdesc02O);
        put(fields, TAMT002, TAMT_LENGTH,
                TransactionListResponse::getTamt002O, TransactionListResponse::setTamt002O);

        put(fields, SEL0003, SEL_LENGTH,
                TransactionListResponse::getSel0003O, TransactionListResponse::setSel0003O);
        put(fields, TRNID03, TRNID_LENGTH,
                TransactionListResponse::getTrnid03O, TransactionListResponse::setTrnid03O);
        put(fields, TDATE03, TDATE_LENGTH,
                TransactionListResponse::getTdate03O, TransactionListResponse::setTdate03O);
        put(fields, TDESC03, TDESC_LENGTH,
                TransactionListResponse::getTdesc03O, TransactionListResponse::setTdesc03O);
        put(fields, TAMT003, TAMT_LENGTH,
                TransactionListResponse::getTamt003O, TransactionListResponse::setTamt003O);

        put(fields, SEL0004, SEL_LENGTH,
                TransactionListResponse::getSel0004O, TransactionListResponse::setSel0004O);
        put(fields, TRNID04, TRNID_LENGTH,
                TransactionListResponse::getTrnid04O, TransactionListResponse::setTrnid04O);
        put(fields, TDATE04, TDATE_LENGTH,
                TransactionListResponse::getTdate04O, TransactionListResponse::setTdate04O);
        put(fields, TDESC04, TDESC_LENGTH,
                TransactionListResponse::getTdesc04O, TransactionListResponse::setTdesc04O);
        put(fields, TAMT004, TAMT_LENGTH,
                TransactionListResponse::getTamt004O, TransactionListResponse::setTamt004O);

        put(fields, SEL0005, SEL_LENGTH,
                TransactionListResponse::getSel0005O, TransactionListResponse::setSel0005O);
        put(fields, TRNID05, TRNID_LENGTH,
                TransactionListResponse::getTrnid05O, TransactionListResponse::setTrnid05O);
        put(fields, TDATE05, TDATE_LENGTH,
                TransactionListResponse::getTdate05O, TransactionListResponse::setTdate05O);
        put(fields, TDESC05, TDESC_LENGTH,
                TransactionListResponse::getTdesc05O, TransactionListResponse::setTdesc05O);
        put(fields, TAMT005, TAMT_LENGTH,
                TransactionListResponse::getTamt005O, TransactionListResponse::setTamt005O);

        put(fields, SEL0006, SEL_LENGTH,
                TransactionListResponse::getSel0006O, TransactionListResponse::setSel0006O);
        put(fields, TRNID06, TRNID_LENGTH,
                TransactionListResponse::getTrnid06O, TransactionListResponse::setTrnid06O);
        put(fields, TDATE06, TDATE_LENGTH,
                TransactionListResponse::getTdate06O, TransactionListResponse::setTdate06O);
        put(fields, TDESC06, TDESC_LENGTH,
                TransactionListResponse::getTdesc06O, TransactionListResponse::setTdesc06O);
        put(fields, TAMT006, TAMT_LENGTH,
                TransactionListResponse::getTamt006O, TransactionListResponse::setTamt006O);

        put(fields, SEL0007, SEL_LENGTH,
                TransactionListResponse::getSel0007O, TransactionListResponse::setSel0007O);
        put(fields, TRNID07, TRNID_LENGTH,
                TransactionListResponse::getTrnid07O, TransactionListResponse::setTrnid07O);
        put(fields, TDATE07, TDATE_LENGTH,
                TransactionListResponse::getTdate07O, TransactionListResponse::setTdate07O);
        put(fields, TDESC07, TDESC_LENGTH,
                TransactionListResponse::getTdesc07O, TransactionListResponse::setTdesc07O);
        put(fields, TAMT007, TAMT_LENGTH,
                TransactionListResponse::getTamt007O, TransactionListResponse::setTamt007O);

        put(fields, SEL0008, SEL_LENGTH,
                TransactionListResponse::getSel0008O, TransactionListResponse::setSel0008O);
        put(fields, TRNID08, TRNID_LENGTH,
                TransactionListResponse::getTrnid08O, TransactionListResponse::setTrnid08O);
        put(fields, TDATE08, TDATE_LENGTH,
                TransactionListResponse::getTdate08O, TransactionListResponse::setTdate08O);
        put(fields, TDESC08, TDESC_LENGTH,
                TransactionListResponse::getTdesc08O, TransactionListResponse::setTdesc08O);
        put(fields, TAMT008, TAMT_LENGTH,
                TransactionListResponse::getTamt008O, TransactionListResponse::setTamt008O);

        put(fields, SEL0009, SEL_LENGTH,
                TransactionListResponse::getSel0009O, TransactionListResponse::setSel0009O);
        put(fields, TRNID09, TRNID_LENGTH,
                TransactionListResponse::getTrnid09O, TransactionListResponse::setTrnid09O);
        put(fields, TDATE09, TDATE_LENGTH,
                TransactionListResponse::getTdate09O, TransactionListResponse::setTdate09O);
        put(fields, TDESC09, TDESC_LENGTH,
                TransactionListResponse::getTdesc09O, TransactionListResponse::setTdesc09O);
        put(fields, TAMT009, TAMT_LENGTH,
                TransactionListResponse::getTamt009O, TransactionListResponse::setTamt009O);

        put(fields, SEL0010, SEL_LENGTH,
                TransactionListResponse::getSel0010O, TransactionListResponse::setSel0010O);
        put(fields, TRNID10, TRNID_LENGTH,
                TransactionListResponse::getTrnid10O, TransactionListResponse::setTrnid10O);
        put(fields, TDATE10, TDATE_LENGTH,
                TransactionListResponse::getTdate10O, TransactionListResponse::setTdate10O);
        put(fields, TDESC10, TDESC_LENGTH,
                TransactionListResponse::getTdesc10O, TransactionListResponse::setTdesc10O);
        put(fields, TAMT010, TAMT_LENGTH,
                TransactionListResponse::getTamt010O, TransactionListResponse::setTamt010O);

        put(fields, ERRMSG, ERRMSG_LENGTH,
                TransactionListResponse::getErrmsgO, TransactionListResponse::setErrmsgO);

        if (fields.size() != FIELD_COUNT) {
            throw new IllegalStateException("The COTRN0AO projection declares " + fields.size()
                    + " field(s) but app/bms/COTRN00.bms carries " + FIELD_COUNT
                    + " name-labelled DFHMDF definitions; every one must be projected");
        }
        return Collections.unmodifiableMap(fields);
    }

    private static void put(Map<String, PayloadField> fields,
                            String fieldPrefix,
                            int declaredLength,
                            Function<TransactionListResponse, String> reader,
                            BiConsumer<TransactionListResponse, String> writer) {
        PayloadField previous = fields.put(fieldPrefix,
                new PayloadField(fieldPrefix, declaredLength, reader, writer));
        if (previous != null) {
            throw new IllegalStateException("Field prefix '" + fieldPrefix + "' is declared twice in "
                    + "the COTRN0AO projection; each of the " + FIELD_COUNT + " DFHMDF labels is "
                    + "unique in app/bms/COTRN00.bms");
        }
    }

    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>();
        int cursor = 0;

        spans.add(FieldSpan.filler(cursor, TIOAPFX_PREFIX_LENGTH));
        cursor += TIOAPFX_PREFIX_LENGTH;

        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            spans.add(FieldSpan.filler(cursor, ATTRIBUTE_FILLER_LENGTH));
            cursor += ATTRIBUTE_FILLER_LENGTH;

            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), COLOUR_ITEM_SUFFIX);
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), PS_ITEM_SUFFIX);
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), HIGHLIGHT_ITEM_SUFFIX);
            cursor = addAttributeItem(spans, cursor, field.fieldPrefix(), VALIDATION_ITEM_SUFFIX);

            spans.add(FieldSpan.alphanumeric(field.outputItemName(), cursor, field.declaredLength()));
            cursor += field.declaredLength();
        }

        return new RecordLayout(RECORD_LENGTH, spans);
    }

    private static int addAttributeItem(List<FieldSpan> spans, int offset, String prefix,
                                        String suffix) {
        spans.add(FieldSpan.alphanumeric(prefix + suffix, offset, ATTRIBUTE_ITEM_LENGTH));
        return offset + ATTRIBUTE_ITEM_LENGTH;
    }

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of one screen field: the extended
     * colour, the programmed-symbol set, the extended highlight and the validation byte.
     *
     * @param colour the {@code xxxC} extended-colour byte, {@link BmsAttributes#DFHRED} once a field is
     *     flagged in error
     * @param programmedSymbols the {@code xxxP} programmed-symbol byte
     * @param highlight the {@code xxxH} extended-highlight byte
     * @param validation the {@code xxxV} validation byte
     */
    public record FieldAttributes(byte colour,
                                  byte programmedSymbols,
                                  byte highlight,
                                  byte validation) {
        /**
         * The low value {@code MOVE LOW-VALUES TO COTRN0AO} writes into every attribute item, which is also
         * {@link BmsAttributes#DFHDFCOL}, the BMS default-colour mnemonic.
         */
        public static final byte LOW_VALUE = BmsAttributes.DFHDFCOL;

        /**
         * The quad as {@code MOVE LOW-VALUES} leaves it: all four items at {@link #LOW_VALUE}, meaning
         * "take the map's default".
         *
         * @return the initial quad; never {@code null}
         */
        public static FieldAttributes lowValues() {
            return new FieldAttributes(LOW_VALUE, LOW_VALUE, LOW_VALUE, LOW_VALUE);
        }

        /**
         * Returns a copy carrying a new extended colour: the
         * {@code MOVE DFHRED TO (SCRNVAR2)C OF (MAPNAME3)O} of {@code app/cpy/CSSETATY.cpy:21-22}.
         *
         * @param newColour the colour byte to store, normally {@link BmsAttributes#DFHRED}
         * @return a new quad; this one is unchanged
         */
        public FieldAttributes withColour(byte newColour) {
            return new FieldAttributes(newColour, programmedSymbols, highlight, validation);
        }

        /**
         * Whether all four items are still {@link #LOW_VALUE} - that is, whether nothing has been moved
         * into this field's attributes since the group was set to low values.
         *
         * @return {@code true} when the quad is untouched
         */
        public boolean isLowValues() {
            return colour == LOW_VALUE
                    && programmedSymbols == LOW_VALUE
                    && highlight == LOW_VALUE
                    && validation == LOW_VALUE;
        }

        /**
         * Whether the extended-colour item holds {@link BmsAttributes#DFHRED} - the state {@code CSSETATY}
         * leaves a field in once it has been flagged in error.
         *
         * @return {@code true} when the colour item is {@code DFHRED}
         */
        public boolean isColourRed() {
            return colour == BmsAttributes.DFHRED;
        }

        /**
         * The quad as the four consecutive bytes it occupies in the group image, in declaration order
         * {@code C}, {@code P}, {@code H}, {@code V}.
         *
         * @return a fresh four-byte array; never {@code null}
         */
        public byte[] toByteArray() {
            return new byte[] {colour, programmedSymbols, highlight, validation};
        }

        /**
         * A diagnostic rendering naming each item and reporting its byte in hexadecimal, with the colour
         * additionally resolved to its {@code DFHBMSCA} mnemonic.
         *
         * @return a single-line description; never {@code null}
         */
        public String describe() {
            return "C=" + BmsAttributes.toHex(colour)
                    + " (" + BmsAttributes.colourMnemonic(colour) + ")"
                    + " P=" + BmsAttributes.toHex(programmedSymbols)
                    + " H=" + BmsAttributes.toHex(highlight)
                    + " V=" + BmsAttributes.toHex(validation);
        }
    }

    /**
     * {@code 05 CDEMO-CT00-INFO}, the browse cursor {@code COTRN00C} appends to the communication area
     * [{@code app/cbl/COTRN00C.cbl:62-70}]: 16 + 16 + 8 + 1 + 1 + 16 = {@link #CURSOR_LENGTH} bytes, so the
     * commarea {@code COTRN00C} passes on {@code EXEC CICS RETURN} is 160 + {@link #CURSOR_LENGTH} =
     * {@link #COMMAREA_WITH_CURSOR_LENGTH} bytes.
     */
    public static final class TransactionListCursor {
        /**
         * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}: the key of the first row on the page just painted.
         */
        public static final int TRNID_FIRST_LENGTH = 16;

        /**
         * {@code CDEMO-CT00-TRNID-LAST PIC X(16)}: the key of the last row on the page just painted.
         */
        public static final int TRNID_LAST_LENGTH = 16;

        /**
         * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}: unsigned, scale-free, so an {@code int} models it.
         */
        public static final int PAGE_NUM_LENGTH = 8;

        /**
         * {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)}: the character the user typed in a row selector.
         */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /**
         * {@code CDEMO-CT00-TRN-SELECTED PIC X(16)}: the transaction identifier of the selected row.
         */
        public static final int TRN_SELECTED_LENGTH = 16;

        public static final int CURSOR_LENGTH = TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH
                + PAGE_NUM_LENGTH + NEXT_PAGE_FLG_LENGTH + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * The commarea {@code COTRN00C} actually passes: the 160-byte {@code CARDDEMO-COMMAREA} plus this
         * {@link #CURSOR_LENGTH}-byte extension = {@link #COMMAREA_WITH_CURSOR_LENGTH} bytes.
         */
        public static final int COMMAREA_WITH_CURSOR_LENGTH =
                NavigationContext.COMMAREA_LENGTH + CURSOR_LENGTH;

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, asserted at {@code COTRN00C:242} and {@code :310}.
         */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, asserted at {@code COTRN00C:99}, {@code :312} and
         * {@code :315}, and the {@code VALUE 'N'} the field is declared with.
         */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * The verbatim COBOL name of the first-key field.
         */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT00-TRNID-FIRST";

        /**
         * The verbatim COBOL name of the last-key field.
         */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT00-TRNID-LAST";

        /**
         * The verbatim COBOL name of the page-number field.
         */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT00-PAGE-NUM";

        /**
         * The verbatim COBOL name of the next-page flag.
         */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT00-NEXT-PAGE-FLG";

        /**
         * The verbatim COBOL name of the selection flag.
         */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT00-TRN-SEL-FLG";

        /**
         * The verbatim COBOL name of the selected-transaction field.
         */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT00-TRN-SELECTED";

        /**
         * Absolute offset of {@code CDEMO-CT00-TRNID-FIRST} within the extension.
         */
        public static final int TRNID_FIRST_OFFSET = 0;

        /**
         * Absolute offset of {@code CDEMO-CT00-TRNID-LAST}.
         */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;

        /**
         * Absolute offset of {@code CDEMO-CT00-PAGE-NUM}.
         */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;

        /**
         * Absolute offset of {@code CDEMO-CT00-NEXT-PAGE-FLG}.
         */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;

        /**
         * Absolute offset of {@code CDEMO-CT00-TRN-SEL-FLG}.
         */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;

        /**
         * Absolute offset of {@code CDEMO-CT00-TRN-SELECTED}.
         */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * The self-checking descriptor list of the extension: six storage spans, no {@code FILLER} - the
         * COBOL declares none - and no overlay, summing to {@link #CURSOR_LENGTH} bytes.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(CURSOR_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET,
                        NEXT_PAGE_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET,
                        TRN_SELECTED_LENGTH));

        private String trnidFirst = spaces(TRNID_FIRST_LENGTH);

        private String trnidLast = spaces(TRNID_LAST_LENGTH);

        private int pageNum;

        private String nextPageFlg = NEXT_PAGE_NO;

        private String trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);

        private String trnSelected = spaces(TRN_SELECTED_LENGTH);

        /**
         * A cursor in its declared initial state: both keys spaces, the page number zero, the next-page
         * flag at its {@code VALUE 'N'} default, and no selection.
         */
        public TransactionListCursor() {
        }

        public TransactionListCursor(TransactionListCursor other) {
            Objects.requireNonNull(other, "A cursor is required to copy CDEMO-CT00-INFO");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        /**
         * {@code CDEMO-CT00-TRNID-FIRST}, set from row one at {@code COTRN00C:393}.
         *
         * @return the first key on the current page, untrimmed; never {@code null}
         */
        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Stores {@code CDEMO-CT00-TRNID-FIRST}.
         *
         * @param trnidFirst at most {@value #TRNID_FIRST_LENGTH} characters
         * @throws NullPointerException if {@code trnidFirst} is {@code null}
         * @throws IllegalArgumentException if it is longer than its declared width
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = requireWidth(trnidFirst, TRNID_FIRST_LENGTH, TRNID_FIRST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRNID-LAST}, the key {@code PROCESS-PF8-KEY} browses forward from
         * [{@code COTRN00C:259-263}].
         *
         * @return the last key on the current page, untrimmed; never {@code null}
         */
        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Stores {@code CDEMO-CT00-TRNID-LAST}.
         *
         * @param trnidLast at most {@value #TRNID_LAST_LENGTH} characters
         * @throws NullPointerException if {@code trnidLast} is {@code null}
         * @throws IllegalArgumentException if it is longer than its declared width
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = requireWidth(trnidLast, TRNID_LAST_LENGTH, TRNID_LAST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-PAGE-NUM}, incremented at {@code COTRN00C:306} and {@code :317} and decremented
         * at {@code :364}.
         *
         * @return the current page number
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Stores {@code CDEMO-CT00-PAGE-NUM}.
         *
         * @param pageNum a value {@code PIC 9(08)} can hold: not negative, at most eight digits
         * @throws IllegalArgumentException if {@code pageNum} is negative or needs more than
         *     {@value #PAGE_NUM_LENGTH} digits
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(" + PAGE_NUM_LENGTH
                        + "), an unsigned picture with no sign position, so it cannot hold "
                        + pageNum);
            }
            if (String.valueOf(pageNum).length() > PAGE_NUM_LENGTH) {
                throw new IllegalArgumentException(PAGE_NUM_FIELD + " is PIC 9(" + PAGE_NUM_LENGTH
                        + ") and cannot hold " + pageNum + ", which needs "
                        + String.valueOf(pageNum).length() + " digits");
            }
            this.pageNum = pageNum;
        }

        /**
         * {@code CDEMO-CT00-NEXT-PAGE-FLG}.
         *
         * @return the flag character as a one-character string; never {@code null}
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Stores {@code CDEMO-CT00-NEXT-PAGE-FLG}.
         *
         * @param nextPageFlg at most {@value #NEXT_PAGE_FLG_LENGTH} character
         * @throws NullPointerException if {@code nextPageFlg} is {@code null}
         * @throws IllegalArgumentException if it is longer than one character
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg =
                    requireWidth(nextPageFlg, NEXT_PAGE_FLG_LENGTH, NEXT_PAGE_FLG_FIELD);
        }

        /**
         * {@code SET NEXT-PAGE-YES TO TRUE} - {@code COTRN00C:242} and {@code :310}.
         */
        @JsonIgnore
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /**
         * {@code SET NEXT-PAGE-NO TO TRUE} - {@code COTRN00C:99}, {@code :312} and {@code :315}.
         */
        @JsonIgnore
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * Whether {@code 88 NEXT-PAGE-YES VALUE 'Y'} holds - the condition {@code PROCESS-PF8-KEY} tests at
         * {@code COTRN00C:267} before browsing forward.
         *
         * <p>Deliberately not written as {@code !isNextPageNo()}: {@code PIC X(01)} can hold any character,
         * and a blank flag - which is what a commarea holds before the first page is painted - satisfies
         * neither condition.
         *
         * @return {@code true} only when the flag is exactly {@link #NEXT_PAGE_YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * Whether {@code 88 NEXT-PAGE-NO VALUE 'N'} holds.
         *
         * @return {@code true} only when the flag is exactly {@link #NEXT_PAGE_NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /**
         * {@code CDEMO-CT00-TRN-SEL-FLG}: the character the user typed into whichever row selector matched
         * first in the ordered {@code EVALUATE} at {@code COTRN00C:148-182}.
         *
         * @return the selection character as a one-character string; never {@code null}
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Stores {@code CDEMO-CT00-TRN-SEL-FLG}.
         *
         * @param trnSelFlg at most {@value #TRN_SEL_FLG_LENGTH} character
         * @throws NullPointerException if {@code trnSelFlg} is {@code null}
         * @throws IllegalArgumentException if it is longer than one character
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = requireWidth(trnSelFlg, TRN_SEL_FLG_LENGTH, TRN_SEL_FLG_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRN-SELECTED}: the transaction identifier of the selected row, handed to
         * {@code COTRN01C} through the commarea at {@code COTRN00C:188-195}.
         *
         * @return the selected transaction identifier, untrimmed; never {@code null}
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Stores {@code CDEMO-CT00-TRN-SELECTED}.
         *
         * @param trnSelected at most {@value #TRN_SELECTED_LENGTH} characters
         * @throws NullPointerException if {@code trnSelected} is {@code null}
         * @throws IllegalArgumentException if it is longer than its declared width
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = requireWidth(trnSelected, TRN_SELECTED_LENGTH, TRN_SELECTED_FIELD);
        }

        /**
         * Clears the selection, the {@code MOVE SPACES TO CDEMO-CT00-TRN-SEL-FLG} and
         * {@code MOVE SPACES TO CDEMO-CT00-TRN-SELECTED} of the {@code WHEN OTHER} arm at
         * {@code COTRN00C:179-181} - the arm taken when no row selector was typed in.
         */
        @JsonIgnore
        public void clearSelection() {
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Whether a row was selected: the guard at {@code COTRN00C:183-184}, which requires both the flag
         * and the identifier to be neither spaces nor low values before the selection is acted on.
         *
         * @return {@code true} when both fields carry something other than spaces or low values
         */
        @JsonIgnore
        public boolean isRowSelected() {
            return isPresent(trnSelFlg) && isPresent(trnSelected);
        }

        /**
         * Renders the extension as its {@link #CURSOR_LENGTH}-byte image: the two keys and the two flags
         * space-padded on the right, the page number zero-filled on the left.
         *
         * @param codec the codec for the target code page, chosen explicitly by the caller
         * @return exactly {@link #CURSOR_LENGTH} bytes
         * @throws NullPointerException if {@code codec} is {@code null}
         */
        public byte[] toFixedWidth(FixedWidthCodec codec) {
            Objects.requireNonNull(codec, "A codec is required to render CDEMO-CT00-INFO; the code "
                    + "page must be stated explicitly and is never taken from the platform");
            FixedWidthRecord record = codec.newRecord(LAYOUT);
            writeInto(record, codec);
            return record.toByteArray();
        }

        public static TransactionListCursor fromFixedWidth(byte[] bytes, Charset charset) {
            Objects.requireNonNull(bytes, "An image is required to rebuild CDEMO-CT00-INFO");
            Objects.requireNonNull(charset, "A charset is required to decode a CDEMO-CT00-INFO "
                    + "image; the code page must be stated explicitly");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            return readFrom(codec.wrap(bytes, LAYOUT), codec);
        }

        private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
            codec.writePicX(record, LAYOUT.span(TRNID_FIRST_FIELD), trnidFirst);
            codec.writePicX(record, LAYOUT.span(TRNID_LAST_FIELD), trnidLast);
            codec.writePic9(record, LAYOUT.span(PAGE_NUM_FIELD), pageNum);
            codec.writePicX(record, LAYOUT.span(NEXT_PAGE_FLG_FIELD), nextPageFlg);
            codec.writePicX(record, LAYOUT.span(TRN_SEL_FLG_FIELD), trnSelFlg);
            codec.writePicX(record, LAYOUT.span(TRN_SELECTED_FIELD), trnSelected);
        }

        private static TransactionListCursor readFrom(FixedWidthRecord record,
                                                      FixedWidthCodec codec) {
            TransactionListCursor cursor = new TransactionListCursor();
            cursor.setTrnidFirst(codec.readPicX(record, LAYOUT.span(TRNID_FIRST_FIELD)));
            cursor.setTrnidLast(codec.readPicX(record, LAYOUT.span(TRNID_LAST_FIELD)));
            cursor.setPageNum(codec.readPic9AsInt(record, LAYOUT.span(PAGE_NUM_FIELD)));
            cursor.setNextPageFlg(codec.readPicX(record, LAYOUT.span(NEXT_PAGE_FLG_FIELD)));
            cursor.setTrnSelFlg(codec.readPicX(record, LAYOUT.span(TRN_SEL_FLG_FIELD)));
            cursor.setTrnSelected(codec.readPicX(record, LAYOUT.span(TRN_SELECTED_FIELD)));
            return cursor;
        }

        /**
         * A diagnostic rendering naming each field by its verbatim COBOL name.
         *
         * @return a single-line description; never {@code null}
         */
        @Override
        public String toString() {
            return "CDEMO-CT00-INFO[" + TRNID_FIRST_FIELD + "='"
                    + SensitiveDiagnostics.maskIdentifier(trnidFirst) + "', "
                    + TRNID_LAST_FIELD + "='"
                    + SensitiveDiagnostics.maskIdentifier(trnidLast) + "', "
                    + PAGE_NUM_FIELD + "=" + pageNum + ", "
                    + NEXT_PAGE_FLG_FIELD + "='" + nextPageFlg + "', "
                    + TRN_SEL_FLG_FIELD + "='" + trnSelFlg + "', "
                    + TRN_SELECTED_FIELD + "='" + trnSelected + "']";
        }
    }

    private String trnnameO = ScreenFieldImage.unpainted(TRNNAME_LENGTH);

    private String title01O = ScreenFieldImage.unpainted(TITLE01_LENGTH);

    private String curdateO = ScreenFieldImage.unpainted(CURDATE_LENGTH);

    private String pgmnameO = ScreenFieldImage.unpainted(PGMNAME_LENGTH);

    private String title02O = ScreenFieldImage.unpainted(TITLE02_LENGTH);

    private String curtimeO = ScreenFieldImage.unpainted(CURTIME_LENGTH);

    private String pagenumO = ScreenFieldImage.unpainted(PAGENUM_LENGTH);

    private String trnidinO = ScreenFieldImage.unpainted(TRNIDIN_LENGTH);

    private String sel0001O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid01O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate01O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc01O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt001O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0002O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid02O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate02O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc02O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt002O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0003O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid03O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate03O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc03O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt003O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0004O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid04O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate04O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc04O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt004O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0005O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid05O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate05O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc05O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt005O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0006O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid06O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate06O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc06O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt006O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0007O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid07O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate07O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc07O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt007O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0008O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid08O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate08O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc08O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt008O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0009O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid09O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate09O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc09O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt009O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String sel0010O = ScreenFieldImage.unpainted(SEL_LENGTH);

    private String trnid10O = ScreenFieldImage.unpainted(TRNID_LENGTH);

    private String tdate10O = ScreenFieldImage.unpainted(TDATE_LENGTH);

    private String tdesc10O = ScreenFieldImage.unpainted(TDESC_LENGTH);

    private String tamt010O = ScreenFieldImage.unpainted(TAMT_LENGTH);

    private String errmsgO = ScreenFieldImage.unpainted(ERRMSG_LENGTH);

    private String nextProgram = spaces(NavigationContext.TO_PROGRAM_LENGTH);

    private String nextMapset = MAPSET_NAME;

    private String nextMap = MAP_NAME;

    private NavigationContext navigationContext = NavigationContext.empty();

    private TransactionListCursor cursor = new TransactionListCursor();

    private final Map<String, FieldAttributes> fieldAttributes = new LinkedHashMap<>();

    /**
     * A blank screen: every payload field carrying the unpainted image at its declared width, every
     * attribute quad at low values, the navigation targets defaulted to this map's own mapset and map, an
     * empty communication area and a cursor in its declared initial state.
     *
     * <p>{@code COTRN00C:102-103}'s {@code MOVE SPACES TO WS-MESSAGE, ERRMSGO} is a separate statement and
     * is deliberately not folded in here.
     */
    public TransactionListResponse() {
        for (String fieldPrefix : FIELD_PREFIXES) {
            fieldAttributes.put(fieldPrefix, FieldAttributes.lowValues());
        }
    }

    /**
     * A deep copy: the payload fields, the navigation targets, the attribute quads, the communication area
     * and the cursor.
     *
     * @param other the response to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public TransactionListResponse(TransactionListResponse other) {
        Objects.requireNonNull(other, "A response is required to copy the COTRN0AO projection");
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            field.writer().accept(this, field.reader().apply(other));
        }
        this.nextProgram = other.nextProgram;
        this.nextMapset = other.nextMapset;
        this.nextMap = other.nextMap;
        this.navigationContext = other.navigationContext;
        this.cursor = new TransactionListCursor(other.cursor);
        this.fieldAttributes.putAll(other.fieldAttributes);
    }

    /**
     * {@code TRNNAMEO PIC X(4)} - the transaction identifier shown in the header, moved from
     * {@code WS-TRANID} at {@code COTRN00C:573}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnname")
    public String getTrnnameO() {
        return trnnameO;
    }

    public void setTrnnameO(String trnnameO) {
        this.trnnameO = requireWidth(trnnameO, TRNNAME_LENGTH, TRNNAME + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TITLE01O PIC X(40)} - the upper title line, moved from {@code CCDA-TITLE01} at
     * {@code COTRN00C:571}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("title01")
    public String getTitle01O() {
        return title01O;
    }

    public void setTitle01O(String title01O) {
        this.title01O = requireWidth(title01O, TITLE01_LENGTH, TITLE01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code CURDATEO PIC X(8)} - the current date as {@code MM/DD/YY}, moved from
     * {@code WS-CURDATE-MM-DD-YY} at {@code COTRN00C:580}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("curdate")
    public String getCurdateO() {
        return curdateO;
    }

    public void setCurdateO(String curdateO) {
        this.curdateO = requireWidth(curdateO, CURDATE_LENGTH, CURDATE + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code PGMNAMEO PIC X(8)} - the program name shown in the header, moved from {@code WS-PGMNAME} at
     * {@code COTRN00C:574}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("pgmname")
    public String getPgmnameO() {
        return pgmnameO;
    }

    public void setPgmnameO(String pgmnameO) {
        this.pgmnameO = requireWidth(pgmnameO, PGMNAME_LENGTH, PGMNAME + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TITLE02O PIC X(40)} - the lower title line, moved from {@code CCDA-TITLE02} at
     * {@code COTRN00C:572}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("title02")
    public String getTitle02O() {
        return title02O;
    }

    public void setTitle02O(String title02O) {
        this.title02O = requireWidth(title02O, TITLE02_LENGTH, TITLE02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code CURTIMEO PIC X(8)} - the current time as {@code HH:MM:SS}, moved from
     * {@code WS-CURTIME-HH-MM-SS} at {@code COTRN00C:586}.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("curtime")
    public String getCurtimeO() {
        return curtimeO;
    }

    public void setCurtimeO(String curtimeO) {
        this.curtimeO = requireWidth(curtimeO, CURTIME_LENGTH, CURTIME + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code PAGENUMO PIC X(8)} - the displayed page number.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("pagenum")
    public String getPagenumO() {
        return pagenumO;
    }

    public void setPagenumO(String pagenumO) {
        this.pagenumO = requireWidth(pagenumO, PAGENUM_LENGTH, PAGENUM + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNIDINO PIC X(16)} - the browse-start key.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnidin")
    public String getTrnidinO() {
        return trnidinO;
    }

    public void setTrnidinO(String trnidinO) {
        this.trnidinO = requireWidth(trnidinO, TRNIDIN_LENGTH, TRNIDIN + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0001O PIC X(1)} - row 1 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0001")
    public String getSel0001O() {
        return sel0001O;
    }

    public void setSel0001O(String sel0001O) {
        this.sel0001O = requireWidth(sel0001O, SEL_LENGTH, SEL0001 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID01O PIC X(16)} - row 1 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid01")
    public String getTrnid01O() {
        return trnid01O;
    }

    public void setTrnid01O(String trnid01O) {
        this.trnid01O = requireWidth(trnid01O, TRNID_LENGTH, TRNID01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE01O PIC X(8)} - row 1 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate01")
    public String getTdate01O() {
        return tdate01O;
    }

    public void setTdate01O(String tdate01O) {
        this.tdate01O = requireWidth(tdate01O, TDATE_LENGTH, TDATE01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC01O PIC X(26)} - row 1 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc01")
    public String getTdesc01O() {
        return tdesc01O;
    }

    public void setTdesc01O(String tdesc01O) {
        this.tdesc01O = requireWidth(tdesc01O, TDESC_LENGTH, TDESC01 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT001O PIC X(12)} - row 1 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt001")
    public String getTamt001O() {
        return tamt001O;
    }

    public void setTamt001O(String tamt001O) {
        this.tamt001O = requireWidth(tamt001O, TAMT_LENGTH, TAMT001 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0002O PIC X(1)} - row 2 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0002")
    public String getSel0002O() {
        return sel0002O;
    }

    public void setSel0002O(String sel0002O) {
        this.sel0002O = requireWidth(sel0002O, SEL_LENGTH, SEL0002 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID02O PIC X(16)} - row 2 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid02")
    public String getTrnid02O() {
        return trnid02O;
    }

    public void setTrnid02O(String trnid02O) {
        this.trnid02O = requireWidth(trnid02O, TRNID_LENGTH, TRNID02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE02O PIC X(8)} - row 2 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate02")
    public String getTdate02O() {
        return tdate02O;
    }

    public void setTdate02O(String tdate02O) {
        this.tdate02O = requireWidth(tdate02O, TDATE_LENGTH, TDATE02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC02O PIC X(26)} - row 2 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc02")
    public String getTdesc02O() {
        return tdesc02O;
    }

    public void setTdesc02O(String tdesc02O) {
        this.tdesc02O = requireWidth(tdesc02O, TDESC_LENGTH, TDESC02 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT002O PIC X(12)} - row 2 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt002")
    public String getTamt002O() {
        return tamt002O;
    }

    public void setTamt002O(String tamt002O) {
        this.tamt002O = requireWidth(tamt002O, TAMT_LENGTH, TAMT002 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0003O PIC X(1)} - row 3 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0003")
    public String getSel0003O() {
        return sel0003O;
    }

    public void setSel0003O(String sel0003O) {
        this.sel0003O = requireWidth(sel0003O, SEL_LENGTH, SEL0003 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID03O PIC X(16)} - row 3 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid03")
    public String getTrnid03O() {
        return trnid03O;
    }

    public void setTrnid03O(String trnid03O) {
        this.trnid03O = requireWidth(trnid03O, TRNID_LENGTH, TRNID03 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE03O PIC X(8)} - row 3 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate03")
    public String getTdate03O() {
        return tdate03O;
    }

    public void setTdate03O(String tdate03O) {
        this.tdate03O = requireWidth(tdate03O, TDATE_LENGTH, TDATE03 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC03O PIC X(26)} - row 3 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc03")
    public String getTdesc03O() {
        return tdesc03O;
    }

    public void setTdesc03O(String tdesc03O) {
        this.tdesc03O = requireWidth(tdesc03O, TDESC_LENGTH, TDESC03 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT003O PIC X(12)} - row 3 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt003")
    public String getTamt003O() {
        return tamt003O;
    }

    public void setTamt003O(String tamt003O) {
        this.tamt003O = requireWidth(tamt003O, TAMT_LENGTH, TAMT003 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0004O PIC X(1)} - row 4 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0004")
    public String getSel0004O() {
        return sel0004O;
    }

    public void setSel0004O(String sel0004O) {
        this.sel0004O = requireWidth(sel0004O, SEL_LENGTH, SEL0004 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID04O PIC X(16)} - row 4 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid04")
    public String getTrnid04O() {
        return trnid04O;
    }

    public void setTrnid04O(String trnid04O) {
        this.trnid04O = requireWidth(trnid04O, TRNID_LENGTH, TRNID04 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE04O PIC X(8)} - row 4 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate04")
    public String getTdate04O() {
        return tdate04O;
    }

    public void setTdate04O(String tdate04O) {
        this.tdate04O = requireWidth(tdate04O, TDATE_LENGTH, TDATE04 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC04O PIC X(26)} - row 4 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc04")
    public String getTdesc04O() {
        return tdesc04O;
    }

    public void setTdesc04O(String tdesc04O) {
        this.tdesc04O = requireWidth(tdesc04O, TDESC_LENGTH, TDESC04 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT004O PIC X(12)} - row 4 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt004")
    public String getTamt004O() {
        return tamt004O;
    }

    public void setTamt004O(String tamt004O) {
        this.tamt004O = requireWidth(tamt004O, TAMT_LENGTH, TAMT004 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0005O PIC X(1)} - row 5 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0005")
    public String getSel0005O() {
        return sel0005O;
    }

    public void setSel0005O(String sel0005O) {
        this.sel0005O = requireWidth(sel0005O, SEL_LENGTH, SEL0005 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID05O PIC X(16)} - row 5 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid05")
    public String getTrnid05O() {
        return trnid05O;
    }

    public void setTrnid05O(String trnid05O) {
        this.trnid05O = requireWidth(trnid05O, TRNID_LENGTH, TRNID05 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE05O PIC X(8)} - row 5 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate05")
    public String getTdate05O() {
        return tdate05O;
    }

    public void setTdate05O(String tdate05O) {
        this.tdate05O = requireWidth(tdate05O, TDATE_LENGTH, TDATE05 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC05O PIC X(26)} - row 5 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc05")
    public String getTdesc05O() {
        return tdesc05O;
    }

    public void setTdesc05O(String tdesc05O) {
        this.tdesc05O = requireWidth(tdesc05O, TDESC_LENGTH, TDESC05 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT005O PIC X(12)} - row 5 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt005")
    public String getTamt005O() {
        return tamt005O;
    }

    public void setTamt005O(String tamt005O) {
        this.tamt005O = requireWidth(tamt005O, TAMT_LENGTH, TAMT005 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0006O PIC X(1)} - row 6 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0006")
    public String getSel0006O() {
        return sel0006O;
    }

    public void setSel0006O(String sel0006O) {
        this.sel0006O = requireWidth(sel0006O, SEL_LENGTH, SEL0006 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID06O PIC X(16)} - row 6 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid06")
    public String getTrnid06O() {
        return trnid06O;
    }

    public void setTrnid06O(String trnid06O) {
        this.trnid06O = requireWidth(trnid06O, TRNID_LENGTH, TRNID06 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE06O PIC X(8)} - row 6 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate06")
    public String getTdate06O() {
        return tdate06O;
    }

    public void setTdate06O(String tdate06O) {
        this.tdate06O = requireWidth(tdate06O, TDATE_LENGTH, TDATE06 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC06O PIC X(26)} - row 6 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc06")
    public String getTdesc06O() {
        return tdesc06O;
    }

    public void setTdesc06O(String tdesc06O) {
        this.tdesc06O = requireWidth(tdesc06O, TDESC_LENGTH, TDESC06 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT006O PIC X(12)} - row 6 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt006")
    public String getTamt006O() {
        return tamt006O;
    }

    public void setTamt006O(String tamt006O) {
        this.tamt006O = requireWidth(tamt006O, TAMT_LENGTH, TAMT006 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0007O PIC X(1)} - row 7 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0007")
    public String getSel0007O() {
        return sel0007O;
    }

    public void setSel0007O(String sel0007O) {
        this.sel0007O = requireWidth(sel0007O, SEL_LENGTH, SEL0007 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID07O PIC X(16)} - row 7 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid07")
    public String getTrnid07O() {
        return trnid07O;
    }

    public void setTrnid07O(String trnid07O) {
        this.trnid07O = requireWidth(trnid07O, TRNID_LENGTH, TRNID07 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE07O PIC X(8)} - row 7 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate07")
    public String getTdate07O() {
        return tdate07O;
    }

    public void setTdate07O(String tdate07O) {
        this.tdate07O = requireWidth(tdate07O, TDATE_LENGTH, TDATE07 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC07O PIC X(26)} - row 7 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc07")
    public String getTdesc07O() {
        return tdesc07O;
    }

    public void setTdesc07O(String tdesc07O) {
        this.tdesc07O = requireWidth(tdesc07O, TDESC_LENGTH, TDESC07 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT007O PIC X(12)} - row 7 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt007")
    public String getTamt007O() {
        return tamt007O;
    }

    public void setTamt007O(String tamt007O) {
        this.tamt007O = requireWidth(tamt007O, TAMT_LENGTH, TAMT007 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0008O PIC X(1)} - row 8 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0008")
    public String getSel0008O() {
        return sel0008O;
    }

    public void setSel0008O(String sel0008O) {
        this.sel0008O = requireWidth(sel0008O, SEL_LENGTH, SEL0008 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID08O PIC X(16)} - row 8 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid08")
    public String getTrnid08O() {
        return trnid08O;
    }

    public void setTrnid08O(String trnid08O) {
        this.trnid08O = requireWidth(trnid08O, TRNID_LENGTH, TRNID08 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE08O PIC X(8)} - row 8 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate08")
    public String getTdate08O() {
        return tdate08O;
    }

    public void setTdate08O(String tdate08O) {
        this.tdate08O = requireWidth(tdate08O, TDATE_LENGTH, TDATE08 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC08O PIC X(26)} - row 8 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc08")
    public String getTdesc08O() {
        return tdesc08O;
    }

    public void setTdesc08O(String tdesc08O) {
        this.tdesc08O = requireWidth(tdesc08O, TDESC_LENGTH, TDESC08 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT008O PIC X(12)} - row 8 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt008")
    public String getTamt008O() {
        return tamt008O;
    }

    public void setTamt008O(String tamt008O) {
        this.tamt008O = requireWidth(tamt008O, TAMT_LENGTH, TAMT008 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0009O PIC X(1)} - row 9 selector.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0009")
    public String getSel0009O() {
        return sel0009O;
    }

    public void setSel0009O(String sel0009O) {
        this.sel0009O = requireWidth(sel0009O, SEL_LENGTH, SEL0009 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID09O PIC X(16)} - row 9 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid09")
    public String getTrnid09O() {
        return trnid09O;
    }

    public void setTrnid09O(String trnid09O) {
        this.trnid09O = requireWidth(trnid09O, TRNID_LENGTH, TRNID09 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE09O PIC X(8)} - row 9 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate09")
    public String getTdate09O() {
        return tdate09O;
    }

    public void setTdate09O(String tdate09O) {
        this.tdate09O = requireWidth(tdate09O, TDATE_LENGTH, TDATE09 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC09O PIC X(26)} - row 9 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc09")
    public String getTdesc09O() {
        return tdesc09O;
    }

    public void setTdesc09O(String tdesc09O) {
        this.tdesc09O = requireWidth(tdesc09O, TDESC_LENGTH, TDESC09 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT009O PIC X(12)} - row 9 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt009")
    public String getTamt009O() {
        return tamt009O;
    }

    public void setTamt009O(String tamt009O) {
        this.tamt009O = requireWidth(tamt009O, TAMT_LENGTH, TAMT009 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code SEL0010O PIC X(1)} - row 10 selector, the last row on the page.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("sel0010")
    public String getSel0010O() {
        return sel0010O;
    }

    public void setSel0010O(String sel0010O) {
        this.sel0010O = requireWidth(sel0010O, SEL_LENGTH, SEL0010 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TRNID10O PIC X(16)} - row 10 transaction identifier.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("trnid10")
    public String getTrnid10O() {
        return trnid10O;
    }

    public void setTrnid10O(String trnid10O) {
        this.trnid10O = requireWidth(trnid10O, TRNID_LENGTH, TRNID10 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDATE10O PIC X(8)} - row 10 transaction date.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdate10")
    public String getTdate10O() {
        return tdate10O;
    }

    public void setTdate10O(String tdate10O) {
        this.tdate10O = requireWidth(tdate10O, TDATE_LENGTH, TDATE10 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TDESC10O PIC X(26)} - row 10 description.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tdesc10")
    public String getTdesc10O() {
        return tdesc10O;
    }

    public void setTdesc10O(String tdesc10O) {
        this.tdesc10O = requireWidth(tdesc10O, TDESC_LENGTH, TDESC10 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code TAMT010O PIC X(12)} - row 10 edited amount.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("tamt010")
    public String getTamt010O() {
        return tamt010O;
    }

    public void setTamt010O(String tamt010O) {
        this.tamt010O = requireWidth(tamt010O, TAMT_LENGTH, TAMT010 + OUTPUT_ITEM_SUFFIX);
    }

    /**
     * {@code ERRMSGO PIC X(78)} - the error line at screen position {@code (23,1)}, rendered
     * {@code ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
     *
     * <p>{@code COTRN00C} blanks it at line 103 and fills it from the 80-character {@code WS-MESSAGE} at
     * line 531, which truncates on the right.
     *
     * @return the stored value, untrimmed; never {@code null}
     */
    @JsonProperty("errmsg")
    public String getErrmsgO() {
        return errmsgO;
    }

    public void setErrmsgO(String errmsgO) {
        this.errmsgO = requireWidth(errmsgO, ERRMSG_LENGTH, ERRMSG + OUTPUT_ITEM_SUFFIX);
    }

    private static final int SELECTOR_COLUMN = 0;

    private static final int TRANSACTION_ID_COLUMN = 1;

    private static final int DATE_COLUMN = 2;

    private static final int DESCRIPTION_COLUMN = 3;

    private static final int AMOUNT_COLUMN = 4;

    private static final List<List<String>> ROW_FIELD_PREFIXES = List.of(
            List.of(SEL0001, TRNID01, TDATE01, TDESC01, TAMT001),
            List.of(SEL0002, TRNID02, TDATE02, TDESC02, TAMT002),
            List.of(SEL0003, TRNID03, TDATE03, TDESC03, TAMT003),
            List.of(SEL0004, TRNID04, TDATE04, TDESC04, TAMT004),
            List.of(SEL0005, TRNID05, TDATE05, TDESC05, TAMT005),
            List.of(SEL0006, TRNID06, TDATE06, TDESC06, TAMT006),
            List.of(SEL0007, TRNID07, TDATE07, TDESC07, TAMT007),
            List.of(SEL0008, TRNID08, TDATE08, TDESC08, TAMT008),
            List.of(SEL0009, TRNID09, TDATE09, TDESC09, TAMT009),
            List.of(SEL0010, TRNID10, TDATE10, TDESC10, TAMT010));

    /**
     * The five verbatim field prefixes of one screen row, in copybook order.
     *
     * @param oneBasedRow the screen row, {@value #FIRST_ROW} to {@link #LAST_ROW}
     * @return an immutable list of five prefixes, for example
     *     {@code [SEL0001, TRNID01, TDATE01, TDESC01, TAMT001]} for row 1
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    public static List<String> rowFieldPrefixes(int oneBasedRow) {
        requireRow(oneBasedRow);
        return ROW_FIELD_PREFIXES.get(oneBasedRow - 1);
    }

    /**
     * The five verbatim payload item names of one screen row, in copybook order.
     *
     * @param oneBasedRow the screen row, {@value #FIRST_ROW} to {@link #LAST_ROW}
     * @return an immutable list of five item names, for example
     *     {@code [SEL0010O, TRNID10O, TDATE10O, TDESC10O, TAMT010O]} for row 10
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    public static List<String> rowFieldNames(int oneBasedRow) {
        return rowFieldPrefixes(oneBasedRow).stream()
                .map(TransactionListResponse::outputItemName)
                .toList();
    }

    /**
     * The row selector of one screen row: {@code SEL0001O} for row 1 through {@code SEL0010O} for row 10.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowSelection(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(SELECTOR_COLUMN));
    }

    @JsonIgnore
    public void setRowSelection(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(SELECTOR_COLUMN), value);
    }

    /**
     * The transaction identifier of one screen row: {@code TRNID01O} through {@code TRNID10O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowTransactionId(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(TRANSACTION_ID_COLUMN));
    }

    /**
     * Stores the transaction identifier of one screen row.
     *
     * @param oneBasedRow the screen row
     * @param value at most {@value #TRNID_LENGTH} characters
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too wide
     * @throws NullPointerException if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowTransactionId(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(TRANSACTION_ID_COLUMN), value);
    }

    /**
     * The transaction date of one screen row: {@code TDATE01O} through {@code TDATE10O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowTransactionDate(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(DATE_COLUMN));
    }

    /**
     * Stores the transaction date of one screen row.
     *
     * @param oneBasedRow the screen row
     * @param value at most {@value #TDATE_LENGTH} characters
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page or the value is too wide
     * @throws NullPointerException if {@code value} is {@code null}
     */
    @JsonIgnore
    public void setRowTransactionDate(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(DATE_COLUMN), value);
    }

    /**
     * The description of one screen row: {@code TDESC01O} through {@code TDESC10O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowDescription(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(DESCRIPTION_COLUMN));
    }

    @JsonIgnore
    public void setRowDescription(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(DESCRIPTION_COLUMN), value);
    }

    /**
     * The edited amount of one screen row: {@code TAMT001O} through {@code TAMT010O}.
     *
     * @param oneBasedRow the screen row
     * @return the stored value, untrimmed; never {@code null}
     * @throws IllegalArgumentException if {@code oneBasedRow} is outside the page
     */
    @JsonIgnore
    public String getRowAmount(int oneBasedRow) {
        return payloadValue(rowFieldPrefixes(oneBasedRow).get(AMOUNT_COLUMN));
    }

    @JsonIgnore
    public void setRowAmount(int oneBasedRow, String value) {
        setPayloadValue(rowFieldPrefixes(oneBasedRow).get(AMOUNT_COLUMN), value);
    }

    /**
     * The payload item name of a field: its prefix plus {@code O}.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @return for example {@code TAMT001O}
     * @throws NullPointerException if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    public static String outputItemName(String fieldPrefix) {
        return requireField(fieldPrefix).outputItemName();
    }

    /**
     * The extended-colour item name of a field: its prefix plus {@code C}.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @return for example {@code TAMT001C}
     * @throws NullPointerException if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    public static String colourItemName(String fieldPrefix) {
        return requireField(fieldPrefix).colourItemName();
    }

    /**
     * A field's declared {@code PIC X(n)} width, which is also its {@code DFHMDF LENGTH=}.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @return the declared width in characters
     * @throws NullPointerException if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    public static int declaredLength(String fieldPrefix) {
        return requireField(fieldPrefix).declaredLength();
    }

    /**
     * The {@link #FIELD_COUNT} verbatim field prefixes in copybook order.
     *
     * @return an immutable list; never {@code null}
     */
    public static List<String> fieldPrefixes() {
        return FIELD_PREFIXES;
    }

    /**
     * The {@link #FIELD_COUNT} verbatim payload item names in copybook order - {@code TRNNAMEO} first,
     * {@code ERRMSGO} last.
     *
     * @return an immutable list; never {@code null}
     */
    public static List<String> payloadFieldNames() {
        return PAYLOAD_FIELD_NAMES;
    }

    /**
     * Reads any payload field by its verbatim prefix, untrimmed.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @return the stored value; never {@code null}
     * @throws NullPointerException if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public String payloadValue(String fieldPrefix) {
        return requireField(fieldPrefix).reader().apply(this);
    }

    /**
     * Writes any payload field by its verbatim prefix, through the same validation the flat setter applies.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @param value at most the field's declared width
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map, or the value is
     *     wider than the field
     */
    @JsonIgnore
    public void setPayloadValue(String fieldPrefix, String value) {
        requireField(fieldPrefix).writer().accept(this, value);
    }

    /**
     * Every payload field keyed by its verbatim item name, in copybook order - the fingerprint a
     * field-by-field diff compares.
     *
     * @return a new insertion-ordered map of {@link #FIELD_COUNT} entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, String> payloadFieldValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            values.put(field.outputItemName(), field.reader().apply(this));
        }
        return Collections.unmodifiableMap(values);
    }

    /**
     * The {@code xxxC} / {@code xxxP} / {@code xxxH} / {@code xxxV} quad of one field.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @return the field's attributes; never {@code null}, because every field is pre-populated
     * @throws NullPointerException if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public FieldAttributes attributesOf(String fieldPrefix) {
        return fieldAttributes.get(requireField(fieldPrefix).fieldPrefix());
    }

    @JsonIgnore
    public void putAttributes(String fieldPrefix, FieldAttributes attributes) {
        Objects.requireNonNull(attributes, "An attribute quad is required; call "
                + "FieldAttributes.lowValues() to restore the MOVE LOW-VALUES state");
        fieldAttributes.put(requireField(fieldPrefix).fieldPrefix(), attributes);
    }

    /**
     * Every field's attribute quad, keyed by verbatim prefix in copybook order.
     *
     * @return an unmodifiable view of {@link #FIELD_COUNT} entries; never {@code null}
     */
    @JsonIgnore
    public Map<String, FieldAttributes> allAttributes() {
        return Collections.unmodifiableMap(fieldAttributes);
    }

    /**
     * Restores every field's attribute quad to low values: the {@code MOVE LOW-VALUES TO COTRN0AO} of
     * {@code COTRN00C:114} as it applies to the attribute items.
     */
    @JsonIgnore
    public void resetAttributesToLowValues() {
        for (String fieldPrefix : FIELD_PREFIXES) {
            fieldAttributes.put(fieldPrefix, FieldAttributes.lowValues());
        }
    }

    /**
     * Applies a {@code CSSETATY} highlight decision to one field.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @param highlight the decision {@link FieldAttributeSetter} produced for this field
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public void applyHighlight(String fieldPrefix, FieldHighlight highlight) {
        PayloadField field = requireField(fieldPrefix);
        Objects.requireNonNull(highlight, "A highlight decision is required; call "
                + "FieldHighlight.none(prefix, map) to express 'change nothing'");
        if (highlight.untouched()) {
            return;
        }
        byte colour = highlight.colourItemValue();
        fieldAttributes.put(field.fieldPrefix(), attributesOf(field.fieldPrefix()).withColour(colour));
        if (highlight.outputItemAssigned()) {
            setPayloadValue(field.fieldPrefix(), highlight.outputItemValue());
        }
    }

    /**
     * Whether a field's colour item currently holds {@link BmsAttributes#DFHRED} - that is, whether it has
     * been flagged in error.
     *
     * @param fieldPrefix one of the {@link #FIELD_COUNT} verbatim prefixes
     * @return {@code true} when the field is highlighted red
     * @throws NullPointerException if {@code fieldPrefix} is {@code null}
     * @throws IllegalArgumentException if {@code fieldPrefix} is not a field of this map
     */
    @JsonIgnore
    public boolean isFieldHighlighted(String fieldPrefix) {
        return attributesOf(fieldPrefix).isColourRed();
    }

    // EXEC CICS XCTL becomes a response field the client acts on; the server never forwards.

    /**
     * The program the client should call next - the target of {@code EXEC CICS XCTL PROGRAM(...)}.
     *
     * <p>{@code COTRN00C} has exactly two transfer sites, at lines 193 and 519, and both are
     * {@code XCTL PROGRAM(CDEMO-TO-PROGRAM)} - the COMMAREA-driven shape.
     *
     * @return the next program name, untrimmed; never {@code null}
     */
    public String getNextProgram() {
        return nextProgram;
    }

    public void setNextProgram(String nextProgram) {
        this.nextProgram = requireWidth(nextProgram, NavigationContext.TO_PROGRAM_LENGTH,
                "CDEMO-TO-PROGRAM");
    }

    /**
     * The mapset the next screen belongs to.
     *
     * @return the mapset name; never {@code null}
     */
    public String getNextMapset() {
        return nextMapset;
    }

    /**
     * Stores the next mapset name.
     *
     * @param nextMapset at most {@value NavigationContext#LAST_MAPSET_LENGTH} characters
     * @throws NullPointerException if {@code nextMapset} is {@code null}
     * @throws IllegalArgumentException if it exceeds the declared width
     */
    public void setNextMapset(String nextMapset) {
        this.nextMapset = requireWidth(nextMapset, NavigationContext.LAST_MAPSET_LENGTH,
                "CDEMO-LAST-MAPSET");
    }

    public String getNextMap() {
        return nextMap;
    }

    public void setNextMap(String nextMap) {
        this.nextMap = requireWidth(nextMap, NavigationContext.LAST_MAP_LENGTH, "CDEMO-LAST-MAP");
    }

    /**
     * Echoes {@code CDEMO-TO-PROGRAM} out of the communication area as the next program, and blanks the
     * mapset and map - the stateless equivalent of both {@code EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)}
     * sites.
     *
     * <p>{@code COTRN00C} never writes {@code CDEMO-LAST-MAP} or {@code CDEMO-LAST-MAPSET} anywhere, so it
     * hands the next program no map or mapset at all; the target decides its own, exactly as this program
     * decided {@link #MAPSET_NAME} / {@link #MAP_NAME} for itself.
     *
     * @param context the communication area whose {@code CDEMO-TO-PROGRAM} names the target
     * @throws NullPointerException if {@code context} is {@code null}
     */
    @JsonIgnore
    public void echoTransferTarget(NavigationContext context) {
        Objects.requireNonNull(context, "A communication area is required to echo CDEMO-TO-PROGRAM");
        setNextProgram(context.toProgram());
        setNextMapset(spaces(NavigationContext.LAST_MAPSET_LENGTH));
        setNextMap(spaces(NavigationContext.LAST_MAP_LENGTH));
    }

    /**
     * The 160-byte {@code CARDDEMO-COMMAREA} this response echoes.
     *
     * @return the communication area; never {@code null}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = Objects.requireNonNull(navigationContext,
                "A communication area is required; call NavigationContext.empty() for the initial "
                        + "state. There is no null commarea");
    }

    /**
     * The 58-byte {@code CDEMO-CT00-INFO} browse cursor this response echoes.
     *
     * @return the cursor; never {@code null}
     */
    public TransactionListCursor getCursor() {
        return cursor;
    }

    public void setCursor(TransactionListCursor cursor) {
        Objects.requireNonNull(cursor, "A cursor is required; call new TransactionListCursor() for "
                + "the declared initial state");
        this.cursor = new TransactionListCursor(cursor);
    }

    // Each takes the codec where the source performs a cross-width MOVE, so the direction of the truncation
    // is chosen by the same one implementation of the PIC X rule that the rest of the module uses, and the
    // caller states the code page explicitly rather than inheriting the platform's.

    /**
     * {@code POPULATE-HEADER-INFO} [{@code COTRN00C:567-586}], statement for statement: Every sending field
     * is already exactly its receiver's width - the two titles are {@code PIC X(40)} and so are the two
     * title items, {@code WS-TRANID} is {@code X(04)} and so is {@code TRNNAMEO}, {@code WS-PGMNAME} is
     * {@code X(08)} and so is {@code PGMNAMEO}, and both edited date renderings are eight characters - so
     * no.
     *
     * @param dateHeader the captured date and time, whose {@code WS-CURDATE-MM-DD-YY} and
     *     {@code WS-CURTIME-HH-MM-SS} renderings fill the two clock fields
     * @throws NullPointerException if {@code dateHeader} is {@code null}
     */
    @JsonIgnore
    public void populateHeaderInfo(DateHeader dateHeader) {
        Objects.requireNonNull(dateHeader, "A date header is required to populate the heading lines; "
                + "COTRN00C:569 takes them from FUNCTION CURRENT-DATE");
        setTitle01O(ScreenTitles.CCDA_TITLE01);
        setTitle02O(ScreenTitles.CCDA_TITLE02);
        setTrnnameO(TRANSACTION_ID);
        setPgmnameO(PROGRAM_NAME);
        setCurdateO(dateHeader.wsCurdateMmDdYy());
        setCurtimeO(dateHeader.wsCurtimeHhMmSs());
    }

    /**
     * {@code POPULATE-TRAN-DATA} [{@code COTRN00C:381-445}] for one row: the four moves the
     * {@code EVALUATE WS-IDX} arm performs.
     *
     * @param codec the codec whose {@code PIC X} move rule pads and truncates each value
     * @param oneBasedRow the screen row; outside {@value #FIRST_ROW}..{@link #LAST_ROW} this call does
     *     nothing, reproducing {@code WHEN OTHER CONTINUE}
     * @param tranId {@code TRAN-ID}, the raw {@code X(16)} record field
     * @param tranDate {@code WS-TRAN-DATE}, the {@code MM/DD/YY} rendering built at lines 385-388
     * @param tranDesc {@code TRAN-DESC}, the raw {@code X(100)} record field
     * @param editedAmount {@code WS-TRAN-AMT}, the twelve-character edited amount
     * @throws NullPointerException if {@code codec} or any value is {@code null}
     */
    @JsonIgnore
    public void populateTranData(FixedWidthCodec codec,
                                 int oneBasedRow,
                                 String tranId,
                                 String tranDate,
                                 String tranDesc,
                                 String editedAmount) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC X move rule; the code "
                + "page must be stated explicitly and is never taken from the platform");
        if (!isRowOnPage(oneBasedRow)) {
            return;
        }
        List<String> prefixes = ROW_FIELD_PREFIXES.get(oneBasedRow - 1);
        setPayloadValue(prefixes.get(TRANSACTION_ID_COLUMN),
                codec.movePicX(tranId, TRNID_LENGTH));
        setPayloadValue(prefixes.get(DATE_COLUMN),
                codec.movePicX(tranDate, TDATE_LENGTH));
        setPayloadValue(prefixes.get(DESCRIPTION_COLUMN),
                codec.movePicX(tranDesc, TDESC_LENGTH));
        setPayloadValue(prefixes.get(AMOUNT_COLUMN),
                codec.movePicX(editedAmount, TAMT_LENGTH));
        if (oneBasedRow == FIRST_ROW) {
            cursor.setTrnidFirst(codec.movePicX(tranId,
                    TransactionListCursor.TRNID_FIRST_LENGTH));
        }
        if (oneBasedRow == LAST_ROW) {
            // Both are separate ifs rather than an if/else chain because the two arms are independent in
            // the source and a page of one row would legitimately be both the first and the last - which
            // cannot happen with PAGE_SIZE 10, but expressing it as an else would encode an assumption the
            // COBOL does not make.
            cursor.setTrnidLast(codec.movePicX(tranId,
                    TransactionListCursor.TRNID_LAST_LENGTH));
        }
    }

    /**
     * {@code INITIALIZE-TRAN-DATA} [{@code COTRN00C:450-505}] for one row: the four {@code MOVE SPACES}
     * statements the {@code EVALUATE WS-IDX} arm performs.
     *
     * @param oneBasedRow the screen row; outside {@value #FIRST_ROW}..{@link #LAST_ROW} this call does
     *     nothing
     */
    @JsonIgnore
    public void initializeTranData(int oneBasedRow) {
        if (!isRowOnPage(oneBasedRow)) {
            return;
        }
        List<String> prefixes = ROW_FIELD_PREFIXES.get(oneBasedRow - 1);
        setPayloadValue(prefixes.get(TRANSACTION_ID_COLUMN), spaces(TRNID_LENGTH));
        setPayloadValue(prefixes.get(DATE_COLUMN), spaces(TDATE_LENGTH));
        setPayloadValue(prefixes.get(DESCRIPTION_COLUMN), spaces(TDESC_LENGTH));
        setPayloadValue(prefixes.get(AMOUNT_COLUMN), spaces(TAMT_LENGTH));
    }

    /**
     * The whole-page form of {@link #initializeTranData(int)}: the
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX &gt; 10} loops at {@code COTRN00C:290-292} and
     * {@code :344-346}, which blank all ten rows before a page is filled.
     */
    @JsonIgnore
    public void initializeAllTranData() {
        for (int row = FIRST_ROW; row <= LAST_ROW; row++) {
            initializeTranData(row);
        }
    }

    /**
     * {@code MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI OF COTRN0AI} [{@code COTRN00C:324} and {@code :373}]: the
     * numeric {@code PIC 9(08)} cursor field moved into the alphanumeric {@code X(8)} screen item.
     *
     * @param codec the codec whose {@code PIC 9} move rule renders the digits
     * @param pageNum the page number, from {@link TransactionListCursor#getPageNum()}
     * @throws NullPointerException if {@code codec} is {@code null}
     * @throws IllegalArgumentException if {@code pageNum} is negative, since {@code PIC 9(08)} is unsigned
     *     and has no sign position
     */
    @JsonIgnore
    public void movePageNumberToScreen(FixedWidthCodec codec, int pageNum) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC 9 move rule");
        if (pageNum < 0) {
            throw new IllegalArgumentException(TransactionListCursor.PAGE_NUM_FIELD + " is PIC 9("
                    + TransactionListCursor.PAGE_NUM_LENGTH + "), an unsigned picture with no sign "
                    + "position, so " + pageNum + " has no representation in it");
        }
        setPagenumO(codec.movePic9(pageNum, PAGENUM_LENGTH));
    }

    /**
     * {@code MOVE WS-MESSAGE TO ERRMSGO OF COTRN0AO} [{@code COTRN00C:531}], the first statement of
     * {@code SEND-TRNLST-SCREEN} after the heading is populated.
     *
     * <p>{@code WS-MESSAGE} is {@code PIC X(80)} [{@code COTRN00C:38}] and {@code ERRMSGO} is
     * {@code X(78)}, so this move truncates two characters on the right.
     *
     * @param codec the codec whose {@code PIC X} move rule pads and truncates
     * @param message the message, of any length; {@code COTRN00C} passes an 80-character {@code WS-MESSAGE}
     * @throws NullPointerException if {@code codec} or {@code message} is {@code null}
     */
    @JsonIgnore
    public void moveMessageToErrorLine(FixedWidthCodec codec, String message) {
        Objects.requireNonNull(codec, "A codec is required to apply the PIC X move rule");
        setErrmsgO(codec.movePicX(message, ERRMSG_LENGTH));
    }

    /**
     * {@code MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE} [{@code COTRN00C:132}] followed by that message
     * reaching the error line at line 531 - the {@code WHEN OTHER} arm of the {@code EVALUATE EIBAID}
     * dispatch, taken when the key pressed is none of {@code DFHENTER}, {@code DFHPF3}, {@code DFHPF7} or
     * {@code DFHPF8}.
     *
     * @param codec the codec whose {@code PIC X} move rule pads the message
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    @JsonIgnore
    public void moveInvalidKeyMessageToErrorLine(FixedWidthCodec codec) {
        moveMessageToErrorLine(codec, SystemMessages.CCDA_MSG_INVALID_KEY);
    }

    /**
     * {@code MOVE SPACES TO ERRMSGO OF COTRN0AO} [{@code COTRN00C:103}], part of the very first statement
     * of {@code MAIN-PARA}: the error line is cleared before anything else happens.
     */
    @JsonIgnore
    public void clearErrorLine() {
        this.errmsgO = spaces(ERRMSG_LENGTH);
    }

    /**
     * {@code MOVE SPACE TO TRNIDINO OF COTRN0AO} [{@code COTRN00C:228} and {@code :325}]: the browse-start
     * key is cleared once a page has been painted, so the next {@code ENTER} browses on from the cursor
     * rather than restarting from the key the user originally typed.
     */
    @JsonIgnore
    public void clearTranIdInput() {
        this.trnidinO = spaces(TRNIDIN_LENGTH);
    }

    /**
     * Renders the group as its {@link #RECORD_LENGTH}-byte image: each payload item space-padded to its
     * declared width, each attribute item as its stored character, and every {@code FILLER} span emitted as
     * spaces.
     *
     * @param codec the codec for the target code page, chosen explicitly by the caller
     * @return exactly {@link #RECORD_LENGTH} bytes
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public byte[] toFixedWidth(FixedWidthCodec codec) {
        Objects.requireNonNull(codec, "A codec is required to render COTRN0AO; the code page must be "
                + "stated explicitly and is never taken from the platform");
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record, codec);
        return record.toByteArray();
    }

    /**
     * Writes the group into an existing record area of exactly {@link #RECORD_LENGTH} bytes, using that
     * record's own code page.
     *
     * @param record the record area to write into
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write COTRN0AO into");
        writeInto(record, new FixedWidthCodec(record.charset()));
    }

    /**
     * Rebuilds a response's screen fields from a group image.
     *
     * @param bytes the image, exactly {@link #RECORD_LENGTH} bytes
     * @param charset the code page the image is encoded in, named explicitly by the caller
     * @return a response carrying the 59 payload fields and 59 attribute quads the image holds
     * @throws NullPointerException if {@code bytes} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code bytes.length} is not {@link #RECORD_LENGTH}
     */
    public static TransactionListResponse fromFixedWidth(byte[] bytes, Charset charset) {
        Objects.requireNonNull(bytes, "An image is required to rebuild COTRN0AO");
        Objects.requireNonNull(charset, "A charset is required to decode a COTRN0AO image; the code "
                + "page must be stated explicitly and is never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(bytes, LAYOUT), codec);
    }

    /**
     * Reads a response's screen fields out of an existing record area, using that record's own code page.
     *
     * @param record a record area of exactly {@link #RECORD_LENGTH} bytes
     * @return a response carrying the fields the record holds
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #RECORD_LENGTH}
     */
    public static TransactionListResponse readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read COTRN0AO from");
        return readFrom(record, new FixedWidthCodec(record.charset()));
    }

    private void writeInto(FixedWidthRecord record, FixedWidthCodec codec) {
        requireGroupWidth(record);
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            FieldAttributes attributes = fieldAttributes.get(field.fieldPrefix());
            writeAttributeItem(record, field.fieldPrefix(), COLOUR_ITEM_SUFFIX,
                    attributes.colour());
            writeAttributeItem(record, field.fieldPrefix(), PS_ITEM_SUFFIX,
                    attributes.programmedSymbols());
            writeAttributeItem(record, field.fieldPrefix(), HIGHLIGHT_ITEM_SUFFIX,
                    attributes.highlight());
            writeAttributeItem(record, field.fieldPrefix(), VALIDATION_ITEM_SUFFIX,
                    attributes.validation());
            codec.writePicX(record, LAYOUT.span(field.outputItemName()),
                    field.reader().apply(this));
        }
    }

    private static TransactionListResponse readFrom(FixedWidthRecord record,
                                                    FixedWidthCodec codec) {
        requireGroupWidth(record);
        TransactionListResponse response = new TransactionListResponse();
        for (PayloadField field : PAYLOAD_FIELDS.values()) {
            response.putAttributes(field.fieldPrefix(), new FieldAttributes(
                    readAttributeItem(record, field.fieldPrefix(), COLOUR_ITEM_SUFFIX),
                    readAttributeItem(record, field.fieldPrefix(), PS_ITEM_SUFFIX),
                    readAttributeItem(record, field.fieldPrefix(), HIGHLIGHT_ITEM_SUFFIX),
                    readAttributeItem(record, field.fieldPrefix(), VALIDATION_ITEM_SUFFIX)));
            field.writer().accept(response,
                    codec.readPicX(record, LAYOUT.span(field.outputItemName())));
        }
        return response;
    }

    private static void writeAttributeItem(FixedWidthRecord record, String prefix, String suffix,
                                           byte value) {
        record.writeSpanBytes(LAYOUT.span(prefix + suffix), new byte[] {value});
    }

    private static byte readAttributeItem(FixedWidthRecord record, String prefix, String suffix) {
        return record.readSpanBytes(LAYOUT.span(prefix + suffix))[0];
    }

    /**
     * A diagnostic rendering: the header fields, the ten rows and the error line, each named by its
     * verbatim item name, followed by the navigation targets and the browse cursor.
     *
     * @return a multi-line description; never {@code null}
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(OUTPUT_MAP_GROUP_NAME).append("[\n");
        for (Map.Entry<String, String> entry : payloadFieldValues().entrySet()) {
            text.append("  ").append(entry.getKey()).append("='")
                    .append(SensitiveDiagnostics.render(disclosureOf(entry.getKey()),
                            entry.getValue()))
                    .append("'\n");
        }
        text.append("  nextProgram='").append(nextProgram).append("'\n")
                .append("  nextMapset='").append(nextMapset).append("'\n")
                .append("  nextMap='").append(nextMap).append("'\n")
                .append("  ").append(cursor).append('\n')
                .append(']');
        return text.toString();
    }

    static SensitiveDiagnostics.Disclosure disclosureOf(String itemName) {
        if (itemName == null) {
            return SensitiveDiagnostics.Disclosure.REDACTED_VALUE;
        }
        if (itemName.startsWith("TRNID")) {
            return SensitiveDiagnostics.Disclosure.IDENTIFIER;
        }
        return SensitiveDiagnostics.Disclosure.PLAIN;
    }

    private static String spaces(int count) {
        return SPACE.repeat(count);
    }

    private static String requireWidth(String value, int declaredWidth, String itemName) {
        Objects.requireNonNull(value, () -> "A value is required for " + itemName
                + "; there is no null in a COBOL record - move SPACES to blank the field");
        if (value.length() > declaredWidth) {
            throw new IllegalArgumentException(itemName + " is PIC X(" + declaredWidth + ") and "
                    + "cannot hold " + value.length() + " characters. This type never truncates "
                    + "silently: COBOL truncates a PIC X receiver on the right, so apply that rule "
                    + "deliberately through FixedWidthCodec.movePicX(value, " + declaredWidth + ")");
        }
        return value;
    }

    private static boolean isRowOnPage(int oneBasedRow) {
        return oneBasedRow >= FIRST_ROW && oneBasedRow <= LAST_ROW;
    }

    /**
     * Validates a screen row, for the accessors - which, unlike the paragraph reproductions, have no COBOL
     * {@code WHEN OTHER CONTINUE} to reproduce and so must reject an impossible row rather than silently
     * return the wrong field or nothing at all.
     *
     * @param oneBasedRow the row to validate
     * @throws IllegalArgumentException if the row is outside {@value #FIRST_ROW}..{@link #LAST_ROW}
     */
    private static void requireRow(int oneBasedRow) {
        if (!isRowOnPage(oneBasedRow)) {
            throw new IllegalArgumentException("Screen row " + oneBasedRow + " is not on the page: "
                    + MAP_NAME + " declares " + ROW_COUNT + " transaction rows and they are "
                    + "1-based, so the valid range is " + FIRST_ROW + " to " + LAST_ROW
                    + " inclusive");
        }
    }

    private static PayloadField requireField(String fieldPrefix) {
        Objects.requireNonNull(fieldPrefix, "A field prefix is required; the " + FIELD_COUNT
                + " valid prefixes are listed by fieldPrefixes()");
        PayloadField field = PAYLOAD_FIELDS.get(fieldPrefix);
        if (field == null) {
            throw new IllegalArgumentException("'" + fieldPrefix + "' is not a field of "
                    + OUTPUT_MAP_GROUP_NAME + ". The suffix widths differ between columns - SEL is "
                    + "four digits, TRNID, TDATE and TDESC are two, and TAMT is three - so check the "
                    + "spelling against app/cpy-bms/COTRN00.CPY; fieldPrefixes() lists all "
                    + FIELD_COUNT);
        }
        return field;
    }

    private static boolean isPresent(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ' ' && character != LOW_VALUE_CHARACTER) {
                return true;
            }
        }
        return false;
    }

    private static void requireGroupWidth(FixedWidthRecord record) {
        if (record.recordLength() != RECORD_LENGTH) {
            throw new IllegalArgumentException("A " + OUTPUT_MAP_GROUP_NAME + " record area is "
                    + RECORD_LENGTH + " bytes - 12 for the TIOAPFX prefix, " + FIELD_COUNT + " x "
                    + FIELD_PREFIX_LENGTH + " for the per-field attribute prefixes and "
                    + PAYLOAD_WIDTH_TOTAL + " of payload - but this one declares "
                    + record.recordLength());
        }
    }

}
