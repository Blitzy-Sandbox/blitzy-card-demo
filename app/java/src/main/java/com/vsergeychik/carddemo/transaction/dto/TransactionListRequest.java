package com.vsergeychik.carddemo.transaction.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import com.vsergeychik.carddemo.common.NavigationContext;
import com.vsergeychik.carddemo.common.ResponseOnlyMembers;
import com.vsergeychik.carddemo.common.SensitiveDiagnostics;

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
 * The inbound REST payload of {@code GET /api/transactions} - CSD transaction {@code CT00}, program
 * {@code app/cbl/COTRN00C.cbl} (699 lines) - as a field-for-field projection of the {@code xxxI} items of
 * {@code 01 COTRN0AI} in {@code app/cpy-bms/COTRN00.CPY}.
 *
 * <p>{@code app/bms/COTRN00.bms} declares 89 {@code DFHMDF} entries of which exactly {@link #FIELD_COUNT}
 * carry a name label; only the labelled ones have a symbolic-map item and therefore only they become
 * payload members.
 */
@JsonIgnoreProperties({
        ResponseOnlyMembers.NEXT_PROGRAM,
        ResponseOnlyMembers.NEXT_MAPSET,
        ResponseOnlyMembers.NEXT_MAP,
        ResponseOnlyMembers.SCREEN_METADATA})
public final class TransactionListRequest {
    /**
     * CSD transaction identifier, {@code app/csd/CARDDEMO.CSD:419}; {@code WS-TRANID} at L37.
     */
    public static final String TRANSACTION_ID = "CT00";

    /**
     * Backing program, {@code app/csd/CARDDEMO.CSD:257}; {@code WS-PGMNAME} at L36.
     */
    public static final String PROGRAM_NAME = "COTRN00C";

    /**
     * BMS mapset, {@code app/bms/COTRN00.bms} {@code DFHMSD}; {@code app/csd/CARDDEMO.CSD:145}.
     */
    public static final String MAPSET_NAME = "COTRN00";

    /**
     * BMS map, the {@code DFHMDI} label; {@code SIZE=(24,80)}, {@code COLUMN=1}, {@code LINE=1}.
     */
    public static final String MAP_NAME = "COTRN0A";

    /**
     * The projected symbolic-map group, {@code app/cpy-bms/COTRN00.CPY:17}.
     */
    public static final String SYMBOLIC_MAP_INPUT_GROUP = "COTRN0AI";

    /**
     * Its {@code REDEFINES} alias, {@code app/cpy-bms/COTRN00.CPY:373} - the same bytes.
     */
    public static final String SYMBOLIC_MAP_OUTPUT_GROUP = "COTRN0AO";

    /**
     * The page size: exactly {@value #PAGE_SIZE} transactions per screen.
     *
     * <p>Making it tunable would let a caller produce a page this screen cannot render and a page count the
     * COBOL would never compute.
     */
    public static final int PAGE_SIZE = 10;

    public static final int ROW_COUNT = PAGE_SIZE;

    public static final int HEADER_FIELD_COUNT = 8;

    public static final int ROW_FIELD_COUNT = 5;

    public static final int ERROR_FIELD_COUNT = 1;

    /**
     * Total payload fields: {@link #FIELD_COUNT}, reconciling as {@value #HEADER_FIELD_COUNT} +
     * {@link #ROW_COUNT} x {@value #ROW_FIELD_COUNT} + {@value #ERROR_FIELD_COUNT}.
     */
    public static final int FIELD_COUNT =
            HEADER_FIELD_COUNT + ROW_COUNT * ROW_FIELD_COUNT + ERROR_FIELD_COUNT;

    /**
     * The value {@code MOVE -1 TO xxxL} places in a length item to ask CICS to position the cursor at that
     * field.
     */
    public static final short CURSOR_POSITION_REQUEST = -1;

    /**
     * {@code TRNNAMEI PIC X(4)}, {@code DFHMDF POS=(1,7)}.
     */
    public static final int TRNNAME_LENGTH = 4;

    /**
     * {@code TITLE01I PIC X(40)}, {@code DFHMDF POS=(1,21)}.
     */
    public static final int TITLE01_LENGTH = 40;

    /**
     * {@code CURDATEI PIC X(8)}, {@code DFHMDF POS=(1,71) INITIAL='mm/dd/yy'}.
     */
    public static final int CURDATE_LENGTH = 8;

    /**
     * {@code PGMNAMEI PIC X(8)}, {@code DFHMDF POS=(2,7)}.
     */
    public static final int PGMNAME_LENGTH = 8;

    /**
     * {@code TITLE02I PIC X(40)}, {@code DFHMDF POS=(2,21)}.
     */
    public static final int TITLE02_LENGTH = 40;

    /**
     * {@code CURTIMEI PIC X(8)}, {@code DFHMDF POS=(2,71) INITIAL='hh:mm:ss'}.
     */
    public static final int CURTIME_LENGTH = 8;

    /**
     * {@code PAGENUMI PIC X(8)}, {@code DFHMDF POS=(4,71)} - alphanumeric, see the class notes.
     */
    public static final int PAGENUM_LENGTH = 8;

    /**
     * {@code TRNIDINI PIC X(16)}, {@code DFHMDF POS=(6,21) ATTRB=(FSET,NORM,UNPROT)}.
     */
    public static final int TRNIDIN_LENGTH = 16;

    /**
     * {@code SEL000nI PIC X(1)}, column 3 - the row selector, {@code UNPROT}.
     */
    public static final int SELECTION_LENGTH = 1;

    /**
     * {@code TRNIDnnI PIC X(16)}, column 8 - matches {@code TRAN-ID PIC X(16)} exactly.
     */
    public static final int TRANSACTION_ID_LENGTH = 16;

    /**
     * {@code TDATEnnI PIC X(8)}, column 27 - {@code WS-TRAN-DATE}, {@code mm/dd/yy}.
     */
    public static final int TRANSACTION_DATE_LENGTH = 8;

    /**
     * {@code TDESCnnI PIC X(26)}, column 38 - {@code TRAN-DESC PIC X(100)} truncated on the right.
     */
    public static final int TRANSACTION_DESCRIPTION_LENGTH = 26;

    /**
     * {@code TAMT00nI PIC X(12)}, column 67 - the {@code +99999999.99} edit form.
     */
    public static final int TRANSACTION_AMOUNT_LENGTH = 12;

    /**
     * {@code ERRMSGI PIC X(78)}, {@code DFHMDF POS=(23,1) ATTRB=(ASKIP,BRT,FSET) COLOR=RED}.
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
     * The leading {@code 02 FILLER PIC X(12)} of the group - the {@code TIOAPFX=YES} prefix.
     */
    public static final int TIOAPFX_PREFIX_LENGTH = 12;

    /**
     * Metadata bytes preceding each payload item: {@value #FIELD_PREFIX_LENGTH}.
     */
    public static final int FIELD_PREFIX_LENGTH = 7;

    public static final int HEADER_PAYLOAD_LENGTH =
            TRNNAME_LENGTH + TITLE01_LENGTH + CURDATE_LENGTH + PGMNAME_LENGTH
                    + TITLE02_LENGTH + CURTIME_LENGTH + PAGENUM_LENGTH + TRNIDIN_LENGTH;

    public static final int HEADER_IMAGE_LENGTH =
            HEADER_PAYLOAD_LENGTH + HEADER_FIELD_COUNT * FIELD_PREFIX_LENGTH;

    public static final int ROW_PAYLOAD_LENGTH =
            SELECTION_LENGTH + TRANSACTION_ID_LENGTH + TRANSACTION_DATE_LENGTH
                    + TRANSACTION_DESCRIPTION_LENGTH + TRANSACTION_AMOUNT_LENGTH;

    public static final int ROW_IMAGE_LENGTH =
            ROW_PAYLOAD_LENGTH + ROW_FIELD_COUNT * FIELD_PREFIX_LENGTH;

    public static final int ROW_BLOCK_PAYLOAD_LENGTH = ROW_COUNT * ROW_PAYLOAD_LENGTH;

    public static final int ROW_BLOCK_IMAGE_LENGTH = ROW_COUNT * ROW_IMAGE_LENGTH;

    public static final int ERRMSG_IMAGE_LENGTH = ERRMSG_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int PAYLOAD_LENGTH =
            HEADER_PAYLOAD_LENGTH + ROW_BLOCK_PAYLOAD_LENGTH + ERRMSG_LENGTH;

    public static final int SYMBOLIC_MAP_LENGTH =
            TIOAPFX_PREFIX_LENGTH + FIELD_COUNT * FIELD_PREFIX_LENGTH + PAYLOAD_LENGTH;

    public static final String TRNNAME_FIELD = "TRNNAME";

    public static final String TITLE01_FIELD = "TITLE01";

    public static final String CURDATE_FIELD = "CURDATE";

    public static final String PGMNAME_FIELD = "PGMNAME";

    public static final String TITLE02_FIELD = "TITLE02";

    public static final String CURTIME_FIELD = "CURTIME";

    public static final String PAGENUM_FIELD = "PAGENUM";

    public static final String TRNIDIN_FIELD = "TRNIDIN";

    public static final String ERRMSG_FIELD = "ERRMSG";

    public static final String SELECTION_FIELD_PREFIX = "SEL";

    public static final String TRANSACTION_ID_FIELD_PREFIX = "TRNID";

    public static final String TRANSACTION_DATE_FIELD_PREFIX = "TDATE";

    public static final String TRANSACTION_DESCRIPTION_FIELD_PREFIX = "TDESC";

    public static final String TRANSACTION_AMOUNT_FIELD_PREFIX = "TAMT";

    public static final String INPUT_ITEM_SUFFIX = "I";

    public static final String LENGTH_ITEM_SUFFIX = "L";

    public static final String FLAG_ITEM_SUFFIX = "F";

    public static final String ATTRIBUTE_ITEM_SUFFIX = "A";

    /**
     * The {@link #FIELD_COUNT} base field names in copybook and mapset declaration order: the eight
     * header/control fields, then {@link #ROW_COUNT} rows of {@value #ROW_FIELD_COUNT}, then the error
     * line.
     */
    public static final List<String> FIELD_NAMES = buildFieldNames();

    public static final List<String> INPUT_ITEM_NAMES = buildInputItemNames();

    /**
     * The verbatim name of a row's selector field, with a four-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @return {@code SEL0001} for row 1 through {@code SEL0010} for row {@link #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public static String selectionFieldName(int oneBasedRow) {
        return SELECTION_FIELD_PREFIX + fourDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's transaction-identifier field, with a two-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @return {@code TRNID01} for row 1 through {@code TRNID10} for row {@link #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public static String transactionIdFieldName(int oneBasedRow) {
        return TRANSACTION_ID_FIELD_PREFIX + twoDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's date field, with a two-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @return {@code TDATE01} for row 1 through {@code TDATE10} for row {@link #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public static String transactionDateFieldName(int oneBasedRow) {
        return TRANSACTION_DATE_FIELD_PREFIX + twoDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's description field, with a two-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @return {@code TDESC01} for row 1 through {@code TDESC10} for row {@link #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public static String transactionDescriptionFieldName(int oneBasedRow) {
        return TRANSACTION_DESCRIPTION_FIELD_PREFIX + twoDigitRow(oneBasedRow);
    }

    /**
     * The verbatim name of a row's amount field, with a three-digit suffix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @return {@code TAMT001} for row 1 through {@code TAMT010} for row {@link #ROW_COUNT}
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public static String transactionAmountFieldName(int oneBasedRow) {
        return TRANSACTION_AMOUNT_FIELD_PREFIX + threeDigitRow(oneBasedRow);
    }

    /**
     * The symbolic-map payload item name of a base field name, that is the {@code xxxI} item.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code TAMT001}
     * @return the base name with {@link #INPUT_ITEM_SUFFIX} appended, for example {@code TAMT001I}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String inputItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + INPUT_ITEM_SUFFIX;
    }

    /**
     * The length metadata item name of a base field name, that is the {@code xxxL} item declared
     * {@code COMP PIC S9(4)}.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code TRNIDIN}
     * @return the base name with {@link #LENGTH_ITEM_SUFFIX} appended, for example {@code TRNIDINL}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String lengthItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + LENGTH_ITEM_SUFFIX;
    }

    /**
     * The flag metadata item name of a base field name, that is the {@code xxxF} item declared
     * {@code PICTURE X}.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code ERRMSG}
     * @return the base name with {@link #FLAG_ITEM_SUFFIX} appended, for example {@code ERRMSGF}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String flagItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + FLAG_ITEM_SUFFIX;
    }

    /**
     * The attribute metadata item name of a base field name, that is the {@code xxxA} item which
     * {@code REDEFINES} the flag byte and therefore shares its storage.
     *
     * @param baseFieldName a {@code DFHMDF} label, for example {@code ERRMSG}
     * @return the base name with {@link #ATTRIBUTE_ITEM_SUFFIX} appended, for example {@code ERRMSGA}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     */
    public static String attributeItemName(String baseFieldName) {
        return requireFieldName(baseFieldName) + ATTRIBUTE_ITEM_SUFFIX;
    }

    /**
     * Validates a COBOL screen row subscript.
     *
     * @param oneBasedRow the COBOL screen row
     * @return {@code oneBasedRow}, unchanged, so this reads naturally inline
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is below 1 or above {@link #ROW_COUNT}
     */
    public static int requireValidRow(int oneBasedRow) {
        if (oneBasedRow < 1 || oneBasedRow > ROW_COUNT) {
            throw new IndexOutOfBoundsException("Screen row " + oneBasedRow + " is outside 1.."
                    + ROW_COUNT + "; COBOL screen rows are 1-based, so the first row is row 1 and "
                    + "there is no row 0");
        }
        return oneBasedRow;
    }

    private static String twoDigitRow(int oneBasedRow) {
        return padRowNumber(requireValidRow(oneBasedRow), 2);
    }

    private static String threeDigitRow(int oneBasedRow) {
        return padRowNumber(requireValidRow(oneBasedRow), 3);
    }

    private static String fourDigitRow(int oneBasedRow) {
        return padRowNumber(requireValidRow(oneBasedRow), 4);
    }

    private static String padRowNumber(int oneBasedRow, int suffixWidth) {
        String digits = Integer.toString(oneBasedRow);
        StringBuilder padded = new StringBuilder(suffixWidth);
        for (int i = digits.length(); i < suffixWidth; i++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    private static String requireFieldName(String baseFieldName) {
        Objects.requireNonNull(baseFieldName, "A base field name is required; pass one of "
                + "FIELD_NAMES, or a name built by selectionFieldName, transactionIdFieldName, "
                + "transactionDateFieldName, transactionDescriptionFieldName or "
                + "transactionAmountFieldName");
        return baseFieldName;
    }

    private static List<String> buildFieldNames() {
        List<String> names = new ArrayList<>(FIELD_COUNT);
        names.add(TRNNAME_FIELD);
        names.add(TITLE01_FIELD);
        names.add(CURDATE_FIELD);
        names.add(PGMNAME_FIELD);
        names.add(TITLE02_FIELD);
        names.add(CURTIME_FIELD);
        names.add(PAGENUM_FIELD);
        names.add(TRNIDIN_FIELD);
        for (int row = 1; row <= ROW_COUNT; row++) {
            names.add(selectionFieldName(row));
            names.add(transactionIdFieldName(row));
            names.add(transactionDateFieldName(row));
            names.add(transactionDescriptionFieldName(row));
            names.add(transactionAmountFieldName(row));
        }
        names.add(ERRMSG_FIELD);
        if (names.size() != FIELD_COUNT) {
            throw new IllegalStateException("Built " + names.size() + " field name(s) for a map "
                    + "that declares " + FIELD_COUNT + "; the header, row and error blocks must "
                    + "reconcile as " + HEADER_FIELD_COUNT + " + " + ROW_COUNT + " x "
                    + ROW_FIELD_COUNT + " + " + ERROR_FIELD_COUNT);
        }
        return List.copyOf(names);
    }

    private static List<String> buildInputItemNames() {
        List<String> items = new ArrayList<>(FIELD_COUNT);
        for (String baseName : FIELD_NAMES) {
            items.add(inputItemName(baseName));
        }
        return List.copyOf(items);
    }

    public static final int TRNNAME_OFFSET = TIOAPFX_PREFIX_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int TITLE01_OFFSET = TRNNAME_OFFSET + TRNNAME_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int CURDATE_OFFSET = TITLE01_OFFSET + TITLE01_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int PGMNAME_OFFSET = CURDATE_OFFSET + CURDATE_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int TITLE02_OFFSET = PGMNAME_OFFSET + PGMNAME_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int CURTIME_OFFSET = TITLE02_OFFSET + TITLE02_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int PAGENUM_OFFSET = CURTIME_OFFSET + CURTIME_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int TRNIDIN_OFFSET = PAGENUM_OFFSET + PAGENUM_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int ROW_BLOCK_OFFSET = TRNIDIN_OFFSET + TRNIDIN_LENGTH;

    public static final int SELECTION_ROW_OFFSET = FIELD_PREFIX_LENGTH;

    public static final int TRANSACTION_ID_ROW_OFFSET =
            SELECTION_ROW_OFFSET + SELECTION_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int TRANSACTION_DATE_ROW_OFFSET =
            TRANSACTION_ID_ROW_OFFSET + TRANSACTION_ID_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int TRANSACTION_DESCRIPTION_ROW_OFFSET =
            TRANSACTION_DATE_ROW_OFFSET + TRANSACTION_DATE_LENGTH + FIELD_PREFIX_LENGTH;

    public static final int TRANSACTION_AMOUNT_ROW_OFFSET =
            TRANSACTION_DESCRIPTION_ROW_OFFSET + TRANSACTION_DESCRIPTION_LENGTH
                    + FIELD_PREFIX_LENGTH;

    public static final int ERRMSG_OFFSET =
            ROW_BLOCK_OFFSET + ROW_BLOCK_IMAGE_LENGTH + FIELD_PREFIX_LENGTH;

    /**
     * Every span of the {@code COTRN0AI} group image, in copybook declaration order, totalling
     * {@link #SYMBOLIC_MAP_LENGTH} bytes.
     */
    public static final RecordLayout LAYOUT = buildLayout();

    private static final FixedWidthCodec PICTURE_RULES =
            new FixedWidthCodec(StandardCharsets.US_ASCII);

    private static RecordLayout buildLayout() {
        List<FieldSpan> spans = new ArrayList<>(1 + 2 * FIELD_COUNT);
        spans.add(FieldSpan.filler(0, TIOAPFX_PREFIX_LENGTH));
        appendField(spans, TRNNAME_FIELD, TRNNAME_OFFSET, TRNNAME_LENGTH);
        appendField(spans, TITLE01_FIELD, TITLE01_OFFSET, TITLE01_LENGTH);
        appendField(spans, CURDATE_FIELD, CURDATE_OFFSET, CURDATE_LENGTH);
        appendField(spans, PGMNAME_FIELD, PGMNAME_OFFSET, PGMNAME_LENGTH);
        appendField(spans, TITLE02_FIELD, TITLE02_OFFSET, TITLE02_LENGTH);
        appendField(spans, CURTIME_FIELD, CURTIME_OFFSET, CURTIME_LENGTH);
        appendField(spans, PAGENUM_FIELD, PAGENUM_OFFSET, PAGENUM_LENGTH);
        appendField(spans, TRNIDIN_FIELD, TRNIDIN_OFFSET, TRNIDIN_LENGTH);
        for (int row = 1; row <= ROW_COUNT; row++) {
            int rowImageOffset = rowImageOffset(row);
            appendField(spans, selectionFieldName(row),
                    rowImageOffset + SELECTION_ROW_OFFSET, SELECTION_LENGTH);
            appendField(spans, transactionIdFieldName(row),
                    rowImageOffset + TRANSACTION_ID_ROW_OFFSET, TRANSACTION_ID_LENGTH);
            appendField(spans, transactionDateFieldName(row),
                    rowImageOffset + TRANSACTION_DATE_ROW_OFFSET, TRANSACTION_DATE_LENGTH);
            appendField(spans, transactionDescriptionFieldName(row),
                    rowImageOffset + TRANSACTION_DESCRIPTION_ROW_OFFSET,
                    TRANSACTION_DESCRIPTION_LENGTH);
            appendField(spans, transactionAmountFieldName(row),
                    rowImageOffset + TRANSACTION_AMOUNT_ROW_OFFSET, TRANSACTION_AMOUNT_LENGTH);
        }
        appendField(spans, ERRMSG_FIELD, ERRMSG_OFFSET, ERRMSG_LENGTH);
        return new RecordLayout(SYMBOLIC_MAP_LENGTH, spans);
    }

    private static void appendField(List<FieldSpan> spans, String baseFieldName,
                                    int payloadOffset, int payloadLength) {
        spans.add(FieldSpan.filler(payloadOffset - FIELD_PREFIX_LENGTH, FIELD_PREFIX_LENGTH));
        spans.add(FieldSpan.alphanumeric(inputItemName(baseFieldName), payloadOffset, payloadLength));
    }

    /**
     * The absolute 0-based offset of a row's image - the first byte of its selector's metadata prefix.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @return {@link #ROW_BLOCK_OFFSET} for row 1, advancing by {@link #ROW_IMAGE_LENGTH} per row
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public static int rowImageOffset(int oneBasedRow) {
        return FixedWidthRecord.occursElementOffsetOneBased(ROW_BLOCK_OFFSET, ROW_IMAGE_LENGTH,
                ROW_COUNT, oneBasedRow);
    }

    /**
     * The three symbolic-map metadata items of one screen field - {@code xxxL}, {@code xxxF} and
     * {@code xxxA} - as validation and highlight metadata that is deliberately not part of the JSON
     * payload.
     *
     * <p>Writing through {@link #setFlag(String)} is observable through {@link #getAttribute()} and the
     * reverse, exactly as it is in COBOL.
     */
    public static final class FieldMetadata {
        /**
         * Declared width of {@code xxxL}, a {@code COMP PIC S9(4)} binary halfword: 2 bytes.
         */
        public static final int LENGTH_ITEM_BYTES = 2;

        public static final int FLAG_ITEM_BYTES = 1;

        public static final short LENGTH_ITEM_NONE = 0;

        /**
         * The one-character {@code LOW-VALUES} figurative constant, {@code X'00'} - the state of the flag
         * byte after {@code MOVE LOW-VALUES TO COTRN0AO} at {@code app/cbl/COTRN00C.cbl:114}, which is what
         * the program does before painting a fresh screen.
         */
        public static final String FLAG_ITEM_LOW_VALUES = "\u0000";

        private final String baseFieldName;

        private short lengthItem;

        private String flagByte;

        /**
         * Creates metadata for one field in its initial state: no reported length and a {@code LOW-VALUES}
         * flag byte.
         *
         * @param baseFieldName the field's base name, verbatim
         * @throws NullPointerException if {@code baseFieldName} is {@code null}
         */
        public FieldMetadata(String baseFieldName) {
            this.baseFieldName = requireFieldName(baseFieldName);
            this.lengthItem = LENGTH_ITEM_NONE;
            this.flagByte = FLAG_ITEM_LOW_VALUES;
        }

        /**
         * Copies existing metadata, so a controller can echo what it received without mutating the
         * request's own instance.
         *
         * @param other the metadata to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public FieldMetadata(FieldMetadata other) {
            Objects.requireNonNull(other, "Source metadata is required to copy it");
            this.baseFieldName = other.baseFieldName;
            this.lengthItem = other.lengthItem;
            this.flagByte = other.flagByte;
        }

        /**
         * The base field name this metadata belongs to.
         *
         * @return the verbatim base name, never {@code null}
         */
        public String getBaseFieldName() {
            return baseFieldName;
        }

        public short getLengthItem() {
            return lengthItem;
        }

        public void setLengthItem(short lengthItem) {
            this.lengthItem = lengthItem;
        }

        /**
         * Reproduces {@code MOVE -1 TO xxxL}: asks CICS to place the cursor at this field.
         */
        public void requestCursorPosition() {
            this.lengthItem = CURSOR_POSITION_REQUEST;
        }

        /**
         * Whether this field currently carries the cursor-positioning request.
         *
         * @return {@code true} when the length item holds
         *     {@link TransactionListRequest#CURSOR_POSITION_REQUEST}
         */
        public boolean isCursorPositionRequested() {
            return lengthItem == CURSOR_POSITION_REQUEST;
        }

        /**
         * Whether the terminal reported any input for this field, that is a length item above zero.
         *
         * @return {@code true} only when the length item is strictly positive
         */
        public boolean hasReportedInput() {
            return lengthItem > 0;
        }

        public String getFlag() {
            return flagByte;
        }

        public void setFlag(String flag) {
            this.flagByte = requireFlagByte(flag, flagItemName(baseFieldName));
        }

        /**
         * The {@code xxxA} attribute byte - the same storage {@link #getFlag()} returns, read through the
         * {@code REDEFINES} view the program uses when setting field highlighting.
         *
         * @return exactly one character
         */
        public String getAttribute() {
            return flagByte;
        }

        public void setAttribute(String attribute) {
            this.flagByte = requireFlagByte(attribute, attributeItemName(baseFieldName));
        }

        private static String requireFlagByte(String value, String itemName) {
            Objects.requireNonNull(value, "A value is required for " + itemName
                    + ": COBOL has no null, so pass a space to clear the byte explicitly");
            if (value.length() > FLAG_ITEM_BYTES) {
                throw new IllegalArgumentException("Item " + itemName + " is declared PICTURE X - one "
                        + "byte, the single attribute byte CICS reports for a field - but was given "
                        + value.length() + " character(s)");
            }
            return value.isEmpty() ? " " : value;
        }

        /**
         * Whether the flag byte still holds {@code LOW-VALUES}.
         *
         * @return {@code true} when the byte is {@link #FLAG_ITEM_LOW_VALUES}
         */
        public boolean isFlagLowValues() {
            return FLAG_ITEM_LOW_VALUES.equals(flagByte);
        }

        /**
         * Restores the initial state: no reported length, {@code LOW-VALUES} flag byte.
         */
        public void reset() {
            this.lengthItem = LENGTH_ITEM_NONE;
            this.flagByte = FLAG_ITEM_LOW_VALUES;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FieldMetadata that)) {
                return false;
            }
            return lengthItem == that.lengthItem
                    && baseFieldName.equals(that.baseFieldName)
                    && flagByte.equals(that.flagByte);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseFieldName, lengthItem, flagByte);
        }

        @Override
        public String toString() {
            return "FieldMetadata[" + baseFieldName + ", length=" + lengthItem + ", flag=X'"
                    + String.format("%02X", (int) flagByte.charAt(0)) + "']";
        }
    }

    /**
     * {@code 05 CDEMO-CT00-INFO} - the {@link #CURSOR_LENGTH}-byte browse cursor that
     * {@code app/cbl/COTRN00C.cbl} appends to the shared communication area at lines 62 to 70, immediately
     * after {@code COPY COCOM01Y} at line 61. 16 + 16 + 8 + 1 + 1 + 16 = {@link #CURSOR_LENGTH}, so the
     * communication area {@code COTRN00C} actually passes is {@value NavigationContext#COMMAREA_LENGTH} +.
     */
    public static final class PaginationCursor {
        /**
         * {@code CDEMO-CT00-TRNID-FIRST PIC X(16)}.
         */
        public static final String TRNID_FIRST_FIELD = "CDEMO-CT00-TRNID-FIRST";

        /**
         * {@code CDEMO-CT00-TRNID-LAST PIC X(16)}.
         */
        public static final String TRNID_LAST_FIELD = "CDEMO-CT00-TRNID-LAST";

        /**
         * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)}.
         */
        public static final String PAGE_NUM_FIELD = "CDEMO-CT00-PAGE-NUM";

        /**
         * {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01) VALUE 'N'}.
         */
        public static final String NEXT_PAGE_FLG_FIELD = "CDEMO-CT00-NEXT-PAGE-FLG";

        /**
         * {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)}.
         */
        public static final String TRN_SEL_FLG_FIELD = "CDEMO-CT00-TRN-SEL-FLG";

        /**
         * {@code CDEMO-CT00-TRN-SELECTED PIC X(16)}.
         */
        public static final String TRN_SELECTED_FIELD = "CDEMO-CT00-TRN-SELECTED";

        /**
         * Declared width of {@code CDEMO-CT00-TRNID-FIRST}: {@value #TRNID_FIRST_LENGTH}.
         */
        public static final int TRNID_FIRST_LENGTH = 16;

        /**
         * Declared width of {@code CDEMO-CT00-TRNID-LAST}: {@value #TRNID_LAST_LENGTH}.
         */
        public static final int TRNID_LAST_LENGTH = 16;

        /**
         * Declared width of {@code CDEMO-CT00-PAGE-NUM}: {@value #PAGE_NUM_LENGTH} digits.
         */
        public static final int PAGE_NUM_LENGTH = 8;

        /**
         * Declared width of {@code CDEMO-CT00-NEXT-PAGE-FLG}: {@value #NEXT_PAGE_FLG_LENGTH}.
         */
        public static final int NEXT_PAGE_FLG_LENGTH = 1;

        /**
         * Declared width of {@code CDEMO-CT00-TRN-SEL-FLG}: {@value #TRN_SEL_FLG_LENGTH}.
         */
        public static final int TRN_SEL_FLG_LENGTH = 1;

        /**
         * Declared width of {@code CDEMO-CT00-TRN-SELECTED}: {@value #TRN_SELECTED_LENGTH}.
         */
        public static final int TRN_SELECTED_LENGTH = 16;

        /**
         * Offset of {@code CDEMO-CT00-TRNID-FIRST}: {@value #TRNID_FIRST_OFFSET}.
         */
        public static final int TRNID_FIRST_OFFSET = 0;

        /**
         * Offset of {@code CDEMO-CT00-TRNID-LAST}: {@link #TRNID_LAST_OFFSET}.
         */
        public static final int TRNID_LAST_OFFSET = TRNID_FIRST_OFFSET + TRNID_FIRST_LENGTH;

        /**
         * Offset of {@code CDEMO-CT00-PAGE-NUM}: {@link #PAGE_NUM_OFFSET}.
         */
        public static final int PAGE_NUM_OFFSET = TRNID_LAST_OFFSET + TRNID_LAST_LENGTH;

        /**
         * Offset of {@code CDEMO-CT00-NEXT-PAGE-FLG}: {@link #NEXT_PAGE_FLG_OFFSET}.
         */
        public static final int NEXT_PAGE_FLG_OFFSET = PAGE_NUM_OFFSET + PAGE_NUM_LENGTH;

        /**
         * Offset of {@code CDEMO-CT00-TRN-SEL-FLG}: {@link #TRN_SEL_FLG_OFFSET}.
         */
        public static final int TRN_SEL_FLG_OFFSET = NEXT_PAGE_FLG_OFFSET + NEXT_PAGE_FLG_LENGTH;

        /**
         * Offset of {@code CDEMO-CT00-TRN-SELECTED}: {@link #TRN_SELECTED_OFFSET}.
         */
        public static final int TRN_SELECTED_OFFSET = TRN_SEL_FLG_OFFSET + TRN_SEL_FLG_LENGTH;

        /**
         * Total width of {@code CDEMO-CT00-INFO}: {@link #CURSOR_LENGTH} bytes, being 16 + 16 + 8 + 1 + 1 +
         * 16.
         */
        public static final int CURSOR_LENGTH =
                TRNID_FIRST_LENGTH + TRNID_LAST_LENGTH + PAGE_NUM_LENGTH + NEXT_PAGE_FLG_LENGTH
                        + TRN_SEL_FLG_LENGTH + TRN_SELECTED_LENGTH;

        /**
         * The communication area {@code COTRN00C} passes on {@code XCTL}:
         * {@value NavigationContext#COMMAREA_LENGTH} bytes of {@code CARDDEMO-COMMAREA} plus this
         * {@link #CURSOR_LENGTH}-byte extension, that is {@link #COMMAREA_LENGTH} bytes.
         */
        public static final int COMMAREA_LENGTH = NavigationContext.COMMAREA_LENGTH + CURSOR_LENGTH;

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'} - a further page exists beyond the one displayed.
         */
        public static final String NEXT_PAGE_YES = "Y";

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'} - and the field's declared {@code VALUE 'N'}, so this is also
         * the initial state.
         */
        public static final String NEXT_PAGE_NO = "N";

        /**
         * The selector value that means "view this transaction".
         */
        public static final String SELECTION_VIEW = "S";

        /**
         * Layout of {@code CDEMO-CT00-INFO}.
         */
        public static final RecordLayout LAYOUT = RecordLayout.of(CURSOR_LENGTH,
                FieldSpan.alphanumeric(TRNID_FIRST_FIELD, TRNID_FIRST_OFFSET, TRNID_FIRST_LENGTH),
                FieldSpan.alphanumeric(TRNID_LAST_FIELD, TRNID_LAST_OFFSET, TRNID_LAST_LENGTH),
                FieldSpan.unsignedNumeric(PAGE_NUM_FIELD, PAGE_NUM_OFFSET, PAGE_NUM_LENGTH),
                FieldSpan.alphanumeric(NEXT_PAGE_FLG_FIELD, NEXT_PAGE_FLG_OFFSET,
                        NEXT_PAGE_FLG_LENGTH).withInitialValue(NEXT_PAGE_NO),
                FieldSpan.alphanumeric(TRN_SEL_FLG_FIELD, TRN_SEL_FLG_OFFSET, TRN_SEL_FLG_LENGTH),
                FieldSpan.alphanumeric(TRN_SELECTED_FIELD, TRN_SELECTED_OFFSET,
                        TRN_SELECTED_LENGTH));

        /**
         * The highest page number {@code PIC 9(08)} can hold: {@value #PAGE_NUM_MAX}.
         */
        public static final int PAGE_NUM_MAX = 99_999_999;

        private String trnidFirst;
        private String trnidLast;
        private int pageNum;
        private String nextPageFlg;
        private String trnSelFlg;
        private String trnSelected;

        /**
         * Creates a cursor in its declared initial state: both browse keys spaces, page number zero,
         * next-page flag {@link #NEXT_PAGE_NO} from the copybook's {@code VALUE 'N'}, and both selection
         * fields spaces.
         */
        public PaginationCursor() {
            this.trnidFirst = spaces(TRNID_FIRST_LENGTH);
            this.trnidLast = spaces(TRNID_LAST_LENGTH);
            this.pageNum = 0;
            this.nextPageFlg = NEXT_PAGE_NO;
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Copies an existing cursor, so a controller can echo the position it received into the response
         * without mutating the request's instance.
         *
         * @param other the cursor to copy
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public PaginationCursor(PaginationCursor other) {
            Objects.requireNonNull(other, "A source cursor is required to copy one");
            this.trnidFirst = other.trnidFirst;
            this.trnidLast = other.trnidLast;
            this.pageNum = other.pageNum;
            this.nextPageFlg = other.nextPageFlg;
            this.trnSelFlg = other.trnSelFlg;
            this.trnSelected = other.trnSelected;
        }

        public String getTrnidFirst() {
            return trnidFirst;
        }

        /**
         * Sets {@code CDEMO-CT00-TRNID-FIRST}, stored at exactly its declared width.
         *
         * @param trnidFirst the key; pass spaces to clear it
         * @throws NullPointerException if {@code trnidFirst} is {@code null}
         */
        public void setTrnidFirst(String trnidFirst) {
            this.trnidFirst = requirePicX(trnidFirst, TRNID_FIRST_LENGTH, TRNID_FIRST_FIELD);
        }

        public String getTrnidLast() {
            return trnidLast;
        }

        /**
         * Sets {@code CDEMO-CT00-TRNID-LAST}, stored at exactly its declared width.
         *
         * @param trnidLast the key; pass spaces to clear it
         * @throws NullPointerException if {@code trnidLast} is {@code null}
         */
        public void setTrnidLast(String trnidLast) {
            this.trnidLast = requirePicX(trnidLast, TRNID_LAST_LENGTH, TRNID_LAST_FIELD);
        }

        /**
         * {@code CDEMO-CT00-PAGE-NUM PIC 9(08)} - the current page number.
         *
         * @return the page number, 0 before the first page is built
         */
        public int getPageNum() {
            return pageNum;
        }

        /**
         * Sets {@code CDEMO-CT00-PAGE-NUM}.
         *
         * @param pageNum the page number; must fit the declared {@value #PAGE_NUM_LENGTH} digits
         * @throws IllegalArgumentException if {@code pageNum} is negative or above {@value #PAGE_NUM_MAX},
         *     since {@code PIC 9(08)} is unsigned and eight digits wide
         */
        public void setPageNum(int pageNum) {
            if (pageNum < 0) {
                throw new IllegalArgumentException("Page number " + pageNum + " is negative but "
                        + PAGE_NUM_FIELD + " is declared PIC 9(08), which is unsigned");
            }
            if (pageNum > PAGE_NUM_MAX) {
                throw new IllegalArgumentException("Page number " + pageNum + " exceeds "
                        + PAGE_NUM_MAX + ", the largest value " + PAGE_NUM_FIELD
                        + " can hold in its declared " + PAGE_NUM_LENGTH + " digits");
            }
            this.pageNum = pageNum;
        }

        /**
         * The {@code PIC 9(08)} storage image of the page number - eight digits, zero-filled on the left,
         * exactly as the field is stored and exactly what {@code MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI}
         * sends to the screen.
         *
         * @return exactly {@value #PAGE_NUM_LENGTH} digits
         */
        @JsonIgnore
        public String getPageNumImage() {
            return PICTURE_RULES.movePic9(pageNum, PAGE_NUM_LENGTH);
        }

        /**
         * {@code CDEMO-CT00-NEXT-PAGE-FLG PIC X(01)}.
         *
         * @return {@link #NEXT_PAGE_YES}, {@link #NEXT_PAGE_NO}, or whatever single character the payload
         *     carried
         */
        public String getNextPageFlg() {
            return nextPageFlg;
        }

        /**
         * Sets {@code CDEMO-CT00-NEXT-PAGE-FLG}, stored at exactly one character.
         *
         * @param nextPageFlg the flag byte
         * @throws NullPointerException if {@code nextPageFlg} is {@code null}
         */
        public void setNextPageFlg(String nextPageFlg) {
            this.nextPageFlg = requirePicX(nextPageFlg, NEXT_PAGE_FLG_LENGTH, NEXT_PAGE_FLG_FIELD);
        }

        /**
         * {@code 88 NEXT-PAGE-YES VALUE 'Y'}, as a named predicate.
         *
         * @return {@code true} when the flag is {@link #NEXT_PAGE_YES}
         */
        @JsonIgnore
        public boolean isNextPageYes() {
            return NEXT_PAGE_YES.equals(nextPageFlg);
        }

        /**
         * {@code 88 NEXT-PAGE-NO VALUE 'N'}, as a named predicate.
         *
         * @return {@code true} when the flag is {@link #NEXT_PAGE_NO}
         */
        @JsonIgnore
        public boolean isNextPageNo() {
            return NEXT_PAGE_NO.equals(nextPageFlg);
        }

        /**
         * Reproduces {@code SET NEXT-PAGE-YES TO TRUE}.
         */
        public void setNextPageYes() {
            this.nextPageFlg = NEXT_PAGE_YES;
        }

        /**
         * Reproduces {@code SET NEXT-PAGE-NO TO TRUE}.
         */
        public void setNextPageNo() {
            this.nextPageFlg = NEXT_PAGE_NO;
        }

        /**
         * {@code CDEMO-CT00-TRN-SEL-FLG PIC X(01)} - the selector character the user typed into the chosen
         * row, copied from the winning {@code SEL000nI} at {@code app/cbl/COTRN00C.cbl:150-180}.
         *
         * @return exactly one character
         */
        public String getTrnSelFlg() {
            return trnSelFlg;
        }

        /**
         * Sets {@code CDEMO-CT00-TRN-SEL-FLG}, stored at exactly one character.
         *
         * @param trnSelFlg the selector character; pass a space to clear it
         * @throws NullPointerException if {@code trnSelFlg} is {@code null}
         */
        public void setTrnSelFlg(String trnSelFlg) {
            this.trnSelFlg = requirePicX(trnSelFlg, TRN_SEL_FLG_LENGTH, TRN_SEL_FLG_FIELD);
        }

        /**
         * {@code CDEMO-CT00-TRN-SELECTED PIC X(16)} - the transaction identifier of the chosen row, copied
         * from that row's {@code TRNIDnnI}.
         *
         * @return exactly {@value #TRN_SELECTED_LENGTH} characters
         */
        public String getTrnSelected() {
            return trnSelected;
        }

        /**
         * Sets {@code CDEMO-CT00-TRN-SELECTED}, stored at exactly its declared width.
         *
         * @param trnSelected the chosen transaction identifier; pass spaces to clear it
         * @throws NullPointerException if {@code trnSelected} is {@code null}
         */
        public void setTrnSelected(String trnSelected) {
            this.trnSelected = requirePicX(trnSelected, TRN_SELECTED_LENGTH, TRN_SELECTED_FIELD);
        }

        /**
         * Reproduces the two {@code MOVE SPACES} statements of the {@code WHEN OTHER} branch at
         * {@code app/cbl/COTRN00C.cbl:180-181}, which clear the selection when no row was marked.
         */
        public void clearSelection() {
            this.trnSelFlg = spaces(TRN_SEL_FLG_LENGTH);
            this.trnSelected = spaces(TRN_SELECTED_LENGTH);
        }

        /**
         * Whether a row selection is present, that is whether both selection fields hold something other
         * than spaces.
         *
         * <p>Mirrors the compound guard at {@code app/cbl/COTRN00C.cbl:183-184}, which requires both fields
         * to differ from {@code SPACES} and {@code LOW-VALUES} before the selector is evaluated at all.
         *
         * @return {@code true} when both selection fields carry a value
         */
        @JsonIgnore
        public boolean isSelectionPresent() {
            return isPresent(trnSelFlg) && isPresent(trnSelected);
        }

        private static boolean isPresent(String value) {
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                if (character != ' ' && character != '\u0000') {
                    return true;
                }
            }
            return false;
        }

        /**
         * Renders this cursor as its {@link #CURSOR_LENGTH}-byte fixed-width image.
         *
         * @param charset the code page to encode into, named explicitly by the caller
         * @return a fresh array of exactly {@link #CURSOR_LENGTH} bytes
         * @throws NullPointerException if {@code charset} is {@code null}
         */
        public byte[] toFixedWidth(Charset charset) {
            Objects.requireNonNull(charset, "A charset is required to render CDEMO-CT00-INFO as "
                    + "bytes; a fixed-width image is bytes in a specific code page, so the code page "
                    + "is stated explicitly and never taken from the platform");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord record = codec.newRecord(LAYOUT);
            record.writeSpan(LAYOUT.span(TRNID_FIRST_FIELD), trnidFirst);
            record.writeSpan(LAYOUT.span(TRNID_LAST_FIELD), trnidLast);
            codec.writePic9(record, LAYOUT.span(PAGE_NUM_FIELD), pageNum);
            record.writeSpan(LAYOUT.span(NEXT_PAGE_FLG_FIELD), nextPageFlg);
            record.writeSpan(LAYOUT.span(TRN_SEL_FLG_FIELD), trnSelFlg);
            record.writeSpan(LAYOUT.span(TRN_SELECTED_FIELD), trnSelected);
            return record.toByteArray();
        }

        /**
         * Rebuilds a cursor from its fixed-width image.
         *
         * @param image exactly {@link #CURSOR_LENGTH} bytes
         * @param charset the code page the image is encoded in, named explicitly by the caller
         * @return the cursor the image carries
         * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
         * @throws IllegalArgumentException if {@code image} is not exactly {@link #CURSOR_LENGTH} bytes
         */
        public static PaginationCursor fromFixedWidth(byte[] image, Charset charset) {
            Objects.requireNonNull(image, "An image is required to rebuild CDEMO-CT00-INFO");
            Objects.requireNonNull(charset, "A charset is required to decode a CDEMO-CT00-INFO "
                    + "image; the code page is stated explicitly and never taken from the platform");
            FixedWidthCodec codec = new FixedWidthCodec(charset);
            FixedWidthRecord record = codec.wrap(image, LAYOUT);
            PaginationCursor cursor = new PaginationCursor();
            cursor.setTrnidFirst(record.readSpan(LAYOUT.span(TRNID_FIRST_FIELD)));
            cursor.setTrnidLast(record.readSpan(LAYOUT.span(TRNID_LAST_FIELD)));
            cursor.setPageNum(codec.readPic9AsInt(record, LAYOUT.span(PAGE_NUM_FIELD)));
            cursor.setNextPageFlg(record.readSpan(LAYOUT.span(NEXT_PAGE_FLG_FIELD)));
            cursor.setTrnSelFlg(record.readSpan(LAYOUT.span(TRN_SEL_FLG_FIELD)));
            cursor.setTrnSelected(record.readSpan(LAYOUT.span(TRN_SELECTED_FIELD)));
            return cursor;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof PaginationCursor that)) {
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
            return "PaginationCursor[page=" + pageNum
                    + ", first=" + SensitiveDiagnostics.maskIdentifier(trnidFirst).strip()
                    + ", last=" + SensitiveDiagnostics.maskIdentifier(trnidLast).strip()
                    + ", nextPage=" + nextPageFlg.strip() + "]";
        }
    }

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

    @Size(max = PAGENUM_LENGTH)
    private String pagenum;

    @Size(max = TRNIDIN_LENGTH)
    private String trnidin;

    @Size(max = SELECTION_LENGTH)
    private String sel0001;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid01;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate01;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc01;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt001;

    @Size(max = SELECTION_LENGTH)
    private String sel0002;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid02;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate02;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc02;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt002;

    @Size(max = SELECTION_LENGTH)
    private String sel0003;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid03;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate03;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc03;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt003;

    @Size(max = SELECTION_LENGTH)
    private String sel0004;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid04;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate04;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc04;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt004;

    @Size(max = SELECTION_LENGTH)
    private String sel0005;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid05;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate05;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc05;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt005;

    @Size(max = SELECTION_LENGTH)
    private String sel0006;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid06;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate06;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc06;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt006;

    @Size(max = SELECTION_LENGTH)
    private String sel0007;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid07;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate07;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc07;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt007;

    @Size(max = SELECTION_LENGTH)
    private String sel0008;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid08;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate08;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc08;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt008;

    @Size(max = SELECTION_LENGTH)
    private String sel0009;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid09;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate09;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc09;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt009;

    @Size(max = SELECTION_LENGTH)
    private String sel0010;

    @Size(max = TRANSACTION_ID_LENGTH)
    private String trnid10;

    @Size(max = TRANSACTION_DATE_LENGTH)
    private String tdate10;

    @Size(max = TRANSACTION_DESCRIPTION_LENGTH)
    private String tdesc10;

    @Size(max = TRANSACTION_AMOUNT_LENGTH)
    private String tamt010;

    @Size(max = ERRMSG_LENGTH)
    private String errmsg;

    @Size(max = AID_LENGTH)
    private String aid;

    private NavigationContext navigationContext;

    private PaginationCursor cursor;

    private final Map<String, FieldMetadata> fieldMetadata;

    /**
     * Creates a request in the state the program holds after {@code MOVE LOW-VALUES TO COTRN0AO} and its
     * subsequent {@code INITIALIZE-TRAN-DATA} loop: every one of the {@link #FIELD_COUNT} fields
     * space-filled to its declared width, an empty {@link NavigationContext}, a fresh
     * {@link PaginationCursor}, and one {@link FieldMetadata} per field.
     */
    public TransactionListRequest() {
        this.fieldMetadata = buildFieldMetadata();
        clearAllFields();
        this.navigationContext = null;
        this.aid = spaces(AID_LENGTH);
        this.cursor = new PaginationCursor();
    }

    /**
     * Copies an existing request field for field, including its carried state and every metadata carrier.
     *
     * @param other the request to copy
     * @throws NullPointerException if {@code other} is {@code null}
     */
    public TransactionListRequest(TransactionListRequest other) {
        Objects.requireNonNull(other, "A source request is required to copy one");
        this.fieldMetadata = new LinkedHashMap<>();
        for (Map.Entry<String, FieldMetadata> entry : other.fieldMetadata.entrySet()) {
            this.fieldMetadata.put(entry.getKey(), new FieldMetadata(entry.getValue()));
        }
        this.trnname = other.trnname;
        this.title01 = other.title01;
        this.curdate = other.curdate;
        this.pgmname = other.pgmname;
        this.title02 = other.title02;
        this.curtime = other.curtime;
        this.pagenum = other.pagenum;
        this.trnidin = other.trnidin;
        for (int row = 1; row <= ROW_COUNT; row++) {
            setSelection(row, other.getSelection(row));
            setTransactionId(row, other.getTransactionId(row));
            setTransactionDate(row, other.getTransactionDate(row));
            setTransactionDescription(row, other.getTransactionDescription(row));
            setTransactionAmount(row, other.getTransactionAmount(row));
        }
        this.errmsg = other.errmsg;
        this.navigationContext = other.navigationContext;
        this.aid = other.aid;
        this.cursor = new PaginationCursor(other.cursor);
    }

    /**
     * Reproduces the effect of {@code MOVE LOW-VALUES TO COTRN0AO} followed by the
     * {@code INITIALIZE-TRAN-DATA} loop: every one of the {@link #FIELD_COUNT} payload fields returns to
     * spaces at its declared width, and every metadata carrier returns to its initial state.
     *
     * <p>The selector is deliberately blanked here too: the program never leaves a stale selector on a
     * freshly painted screen, because the whole group was set to {@code LOW-VALUES} first.
     */
    public void clearAllFields() {
        this.trnname = spaces(TRNNAME_LENGTH);
        this.title01 = spaces(TITLE01_LENGTH);
        this.curdate = spaces(CURDATE_LENGTH);
        this.pgmname = spaces(PGMNAME_LENGTH);
        this.title02 = spaces(TITLE02_LENGTH);
        this.curtime = spaces(CURTIME_LENGTH);
        this.pagenum = spaces(PAGENUM_LENGTH);
        this.trnidin = spaces(TRNIDIN_LENGTH);
        for (int row = 1; row <= ROW_COUNT; row++) {
            clearRow(row);
        }
        this.errmsg = spaces(ERRMSG_LENGTH);
        for (FieldMetadata metadata : fieldMetadata.values()) {
            metadata.reset();
        }
    }

    /**
     * Reproduces one {@code WHEN} branch of {@code INITIALIZE-TRAN-DATA}
     * ({@code app/cbl/COTRN00C.cbl:450-505}): blanks a row's four data fields to spaces.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     */
    public void clearRow(int oneBasedRow) {
        requireValidRow(oneBasedRow);
        setSelection(oneBasedRow, spaces(SELECTION_LENGTH));
        setTransactionId(oneBasedRow, spaces(TRANSACTION_ID_LENGTH));
        setTransactionDate(oneBasedRow, spaces(TRANSACTION_DATE_LENGTH));
        setTransactionDescription(oneBasedRow, spaces(TRANSACTION_DESCRIPTION_LENGTH));
        setTransactionAmount(oneBasedRow, spaces(TRANSACTION_AMOUNT_LENGTH));
    }

    private static Map<String, FieldMetadata> buildFieldMetadata() {
        Map<String, FieldMetadata> metadata = new LinkedHashMap<>();
        for (String baseFieldName : FIELD_NAMES) {
            metadata.put(baseFieldName, new FieldMetadata(baseFieldName));
        }
        return metadata;
    }

    private static String requirePicX(String value, int length, String cobolName) {
        Objects.requireNonNull(value, "A value is required for " + cobolName + ": COBOL has no "
                + "null, so pass spaces(" + length + ") to blank the field explicitly");
        if (value.length() > length) {
            throw new IllegalArgumentException("Field " + cobolName + " of "
                    + SYMBOLIC_MAP_INPUT_GROUP + " is declared PIC X(" + length + ") but was given "
                    + value.length() + " character(s). This payload never truncates, so that the loss "
                    + "of a character is always a deliberate act rather than a silent one. To shorten "
                    + "the value, pass it through FixedWidthCodec.movePicX(value, " + length + "), "
                    + "which truncates on the right as a COBOL alphanumeric MOVE does");
        }
        return value;
    }

    private static String spaces(int length) {
        return " ".repeat(length);
    }

    // Every setter stores through the PIC X rule, so a field read back is always exactly its declared width
    // and its trailing spaces are preserved as the data they are.

    /**
     * {@code TRNNAMEI PIC X(4)}.
     *
     * @return exactly {@value #TRNNAME_LENGTH} characters
     */
    public String getTrnname() {
        return trnname;
    }

    public void setTrnname(String trnname) {
        this.trnname = requirePicX(trnname, TRNNAME_LENGTH, TRNNAME_FIELD);
    }

    /**
     * {@code TITLE01I PIC X(40)}.
     *
     * @return exactly {@value #TITLE01_LENGTH} characters
     */
    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = requirePicX(title01, TITLE01_LENGTH, TITLE01_FIELD);
    }

    /**
     * {@code CURDATEI PIC X(8)}.
     *
     * @return exactly {@value #CURDATE_LENGTH} characters
     */
    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = requirePicX(curdate, CURDATE_LENGTH, CURDATE_FIELD);
    }

    /**
     * {@code PGMNAMEI PIC X(8)}.
     *
     * @return exactly {@value #PGMNAME_LENGTH} characters
     */
    public String getPgmname() {
        return pgmname;
    }

    public void setPgmname(String pgmname) {
        this.pgmname = requirePicX(pgmname, PGMNAME_LENGTH, PGMNAME_FIELD);
    }

    /**
     * {@code TITLE02I PIC X(40)}.
     *
     * @return exactly {@value #TITLE02_LENGTH} characters
     */
    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = requirePicX(title02, TITLE02_LENGTH, TITLE02_FIELD);
    }

    /**
     * {@code CURTIMEI PIC X(8)}.
     *
     * @return exactly {@value #CURTIME_LENGTH} characters
     */
    public String getCurtime() {
        return curtime;
    }

    public void setCurtime(String curtime) {
        this.curtime = requirePicX(curtime, CURTIME_LENGTH, CURTIME_FIELD);
    }

    /**
     * {@code PAGENUMI PIC X(8)} - the displayed page number.
     *
     * @return exactly {@value #PAGENUM_LENGTH} characters
     */
    public String getPagenum() {
        return pagenum;
    }

    public void setPagenum(String pagenum) {
        this.pagenum = requirePicX(pagenum, PAGENUM_LENGTH, PAGENUM_FIELD);
    }

    /**
     * {@code TRNIDINI PIC X(16)} - the browse-start key typed by the user.
     *
     * <p>{@code app/cbl/COTRN00C.cbl:206-219} treats spaces or {@code LOW-VALUES} as "start at the
     * beginning", accepts a numeric value as the starting key, and rejects anything else with
     * {@code 'Tran ID must be Numeric ...'}.
     *
     * @return exactly {@value #TRNIDIN_LENGTH} characters
     */
    public String getTrnidin() {
        return trnidin;
    }

    public void setTrnidin(String trnidin) {
        this.trnidin = requirePicX(trnidin, TRNIDIN_LENGTH, TRNIDIN_FIELD);
    }

    /**
     * {@code ERRMSGI PIC X(78)} - the error line.
     *
     * @return exactly {@value #ERRMSG_LENGTH} characters
     */
    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = requirePicX(errmsg, ERRMSG_LENGTH, ERRMSG_FIELD);
    }

    /**
     * {@code CARDDEMO-COMMAREA} - the {@value NavigationContext#COMMAREA_LENGTH}-byte communication area,
     * carried in the payload, or {@code null} when none was passed.
     *
     * @return the context, or {@code null} for the {@code EIBCALEN = 0} cold start of
     *     {@code app/cbl/COTRN00C.cbl:107}
     */
    public NavigationContext getNavigationContext() {
        return navigationContext;
    }

    public void setNavigationContext(NavigationContext navigationContext) {
        this.navigationContext = navigationContext;
    }

    /**
     * Whether a communication area travelled with this request - the Java reading of {@code EIBCALEN} being
     * non-zero at {@code app/cbl/COTRN00C.cbl:107}.
     *
     * @return {@code true} when {@link #getNavigationContext()} is present
     */
    @JsonIgnore
    public boolean hasNavigationContext() {
        return navigationContext != null;
    }

    /**
     * The length CICS would report in {@code EIBCALEN}: {@value PaginationCursor#COMMAREA_LENGTH} when a
     * communication area travelled with this request, and {@code 0} when none did.
     *
     * @return {@value PaginationCursor#COMMAREA_LENGTH} or {@code 0}
     */
    @JsonIgnore
    public int commareaLength() {
        return hasNavigationContext() ? PaginationCursor.COMMAREA_LENGTH : 0;
    }

    /**
     * The resolved {@code EIBAID} key indication - the key the operator pressed, which
     * {@code app/cbl/COTRN00C.cbl:119} evaluates.
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
        this.aid = requirePicX(aid, AID_LENGTH, AID_FIELD);
    }

    /**
     * {@code CDEMO-CT00-INFO} - this screen's {@value PaginationCursor#CURSOR_LENGTH}-byte browse cursor.
     *
     * @return the cursor, never {@code null}
     */
    public PaginationCursor getCursor() {
        return cursor;
    }

    public void setCursor(PaginationCursor cursor) {
        this.cursor = Objects.requireNonNull(cursor, "A pagination cursor is required; pass a fresh "
                + "PaginationCursor() to start a browse rather than null");
    }

    // Exposed as named predicates that delegate to the carried communication area, deliberately NOT
    // duplicated as a field of this type: COBOL has exactly one CDEMO-PGM-CONTEXT, and a second copy could
    // disagree with the COMMAREA the client hands back.

    /**
     * {@code 88 CDEMO-PGM-ENTER VALUE 0} - first entry, so the screen is painted and nothing is validated.
     *
     * @return {@code true} when the carried context is in the enter state
     */
    @JsonIgnore
    public boolean isEnter() {
        return hasNavigationContext() && navigationContext.isEnter();
    }

    /**
     * {@code 88 CDEMO-PGM-REENTER VALUE 1} - re-entry, so what the user typed is received and validated,
     * and a failing field is highlighted.
     *
     * @return {@code true} when the carried context is in the re-enter state
     */
    @JsonIgnore
    public boolean isReenter() {
        return hasNavigationContext() && navigationContext.isReenter();
    }

    /**
     * {@code CDEMO-PGM-CONTEXT PIC 9(01)} as carried by the communication area.
     *
     * @return {@value NavigationContext#PGM_CONTEXT_ENTER} on first entry and when no area travelled,
     *     {@value NavigationContext#PGM_CONTEXT_REENTER} on re-entry
     */
    @JsonIgnore
    public int getPgmContext() {
        return hasNavigationContext()
                ? navigationContext.pgmContext()
                : NavigationContext.PGM_CONTEXT_ENTER;
    }

    public String getSel0001() {
        return sel0001;
    }

    public void setSel0001(String sel0001) {
        this.sel0001 = requirePicX(sel0001, SELECTION_LENGTH, selectionFieldName(1));
    }

    public String getTrnid01() {
        return trnid01;
    }

    public void setTrnid01(String trnid01) {
        this.trnid01 = requirePicX(trnid01, TRANSACTION_ID_LENGTH, transactionIdFieldName(1));
    }

    public String getTdate01() {
        return tdate01;
    }

    public void setTdate01(String tdate01) {
        this.tdate01 = requirePicX(tdate01, TRANSACTION_DATE_LENGTH, transactionDateFieldName(1));
    }

    public String getTdesc01() {
        return tdesc01;
    }

    public void setTdesc01(String tdesc01) {
        this.tdesc01 = requirePicX(tdesc01, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(1));
    }

    public String getTamt001() {
        return tamt001;
    }

    public void setTamt001(String tamt001) {
        this.tamt001 = requirePicX(tamt001, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(1));
    }

    public String getSel0002() {
        return sel0002;
    }

    public void setSel0002(String sel0002) {
        this.sel0002 = requirePicX(sel0002, SELECTION_LENGTH, selectionFieldName(2));
    }

    public String getTrnid02() {
        return trnid02;
    }

    public void setTrnid02(String trnid02) {
        this.trnid02 = requirePicX(trnid02, TRANSACTION_ID_LENGTH, transactionIdFieldName(2));
    }

    public String getTdate02() {
        return tdate02;
    }

    public void setTdate02(String tdate02) {
        this.tdate02 = requirePicX(tdate02, TRANSACTION_DATE_LENGTH, transactionDateFieldName(2));
    }

    public String getTdesc02() {
        return tdesc02;
    }

    public void setTdesc02(String tdesc02) {
        this.tdesc02 = requirePicX(tdesc02, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(2));
    }

    public String getTamt002() {
        return tamt002;
    }

    public void setTamt002(String tamt002) {
        this.tamt002 = requirePicX(tamt002, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(2));
    }

    public String getSel0003() {
        return sel0003;
    }

    public void setSel0003(String sel0003) {
        this.sel0003 = requirePicX(sel0003, SELECTION_LENGTH, selectionFieldName(3));
    }

    public String getTrnid03() {
        return trnid03;
    }

    public void setTrnid03(String trnid03) {
        this.trnid03 = requirePicX(trnid03, TRANSACTION_ID_LENGTH, transactionIdFieldName(3));
    }

    public String getTdate03() {
        return tdate03;
    }

    public void setTdate03(String tdate03) {
        this.tdate03 = requirePicX(tdate03, TRANSACTION_DATE_LENGTH, transactionDateFieldName(3));
    }

    public String getTdesc03() {
        return tdesc03;
    }

    public void setTdesc03(String tdesc03) {
        this.tdesc03 = requirePicX(tdesc03, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(3));
    }

    public String getTamt003() {
        return tamt003;
    }

    public void setTamt003(String tamt003) {
        this.tamt003 = requirePicX(tamt003, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(3));
    }

    public String getSel0004() {
        return sel0004;
    }

    public void setSel0004(String sel0004) {
        this.sel0004 = requirePicX(sel0004, SELECTION_LENGTH, selectionFieldName(4));
    }

    public String getTrnid04() {
        return trnid04;
    }

    public void setTrnid04(String trnid04) {
        this.trnid04 = requirePicX(trnid04, TRANSACTION_ID_LENGTH, transactionIdFieldName(4));
    }

    public String getTdate04() {
        return tdate04;
    }

    public void setTdate04(String tdate04) {
        this.tdate04 = requirePicX(tdate04, TRANSACTION_DATE_LENGTH, transactionDateFieldName(4));
    }

    public String getTdesc04() {
        return tdesc04;
    }

    public void setTdesc04(String tdesc04) {
        this.tdesc04 = requirePicX(tdesc04, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(4));
    }

    public String getTamt004() {
        return tamt004;
    }

    public void setTamt004(String tamt004) {
        this.tamt004 = requirePicX(tamt004, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(4));
    }

    public String getSel0005() {
        return sel0005;
    }

    public void setSel0005(String sel0005) {
        this.sel0005 = requirePicX(sel0005, SELECTION_LENGTH, selectionFieldName(5));
    }

    public String getTrnid05() {
        return trnid05;
    }

    public void setTrnid05(String trnid05) {
        this.trnid05 = requirePicX(trnid05, TRANSACTION_ID_LENGTH, transactionIdFieldName(5));
    }

    public String getTdate05() {
        return tdate05;
    }

    public void setTdate05(String tdate05) {
        this.tdate05 = requirePicX(tdate05, TRANSACTION_DATE_LENGTH, transactionDateFieldName(5));
    }

    public String getTdesc05() {
        return tdesc05;
    }

    public void setTdesc05(String tdesc05) {
        this.tdesc05 = requirePicX(tdesc05, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(5));
    }

    public String getTamt005() {
        return tamt005;
    }

    public void setTamt005(String tamt005) {
        this.tamt005 = requirePicX(tamt005, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(5));
    }

    public String getSel0006() {
        return sel0006;
    }

    public void setSel0006(String sel0006) {
        this.sel0006 = requirePicX(sel0006, SELECTION_LENGTH, selectionFieldName(6));
    }

    public String getTrnid06() {
        return trnid06;
    }

    public void setTrnid06(String trnid06) {
        this.trnid06 = requirePicX(trnid06, TRANSACTION_ID_LENGTH, transactionIdFieldName(6));
    }

    public String getTdate06() {
        return tdate06;
    }

    public void setTdate06(String tdate06) {
        this.tdate06 = requirePicX(tdate06, TRANSACTION_DATE_LENGTH, transactionDateFieldName(6));
    }

    public String getTdesc06() {
        return tdesc06;
    }

    public void setTdesc06(String tdesc06) {
        this.tdesc06 = requirePicX(tdesc06, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(6));
    }

    public String getTamt006() {
        return tamt006;
    }

    public void setTamt006(String tamt006) {
        this.tamt006 = requirePicX(tamt006, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(6));
    }

    public String getSel0007() {
        return sel0007;
    }

    public void setSel0007(String sel0007) {
        this.sel0007 = requirePicX(sel0007, SELECTION_LENGTH, selectionFieldName(7));
    }

    public String getTrnid07() {
        return trnid07;
    }

    public void setTrnid07(String trnid07) {
        this.trnid07 = requirePicX(trnid07, TRANSACTION_ID_LENGTH, transactionIdFieldName(7));
    }

    public String getTdate07() {
        return tdate07;
    }

    public void setTdate07(String tdate07) {
        this.tdate07 = requirePicX(tdate07, TRANSACTION_DATE_LENGTH, transactionDateFieldName(7));
    }

    public String getTdesc07() {
        return tdesc07;
    }

    public void setTdesc07(String tdesc07) {
        this.tdesc07 = requirePicX(tdesc07, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(7));
    }

    public String getTamt007() {
        return tamt007;
    }

    public void setTamt007(String tamt007) {
        this.tamt007 = requirePicX(tamt007, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(7));
    }

    public String getSel0008() {
        return sel0008;
    }

    public void setSel0008(String sel0008) {
        this.sel0008 = requirePicX(sel0008, SELECTION_LENGTH, selectionFieldName(8));
    }

    public String getTrnid08() {
        return trnid08;
    }

    public void setTrnid08(String trnid08) {
        this.trnid08 = requirePicX(trnid08, TRANSACTION_ID_LENGTH, transactionIdFieldName(8));
    }

    public String getTdate08() {
        return tdate08;
    }

    public void setTdate08(String tdate08) {
        this.tdate08 = requirePicX(tdate08, TRANSACTION_DATE_LENGTH, transactionDateFieldName(8));
    }

    public String getTdesc08() {
        return tdesc08;
    }

    public void setTdesc08(String tdesc08) {
        this.tdesc08 = requirePicX(tdesc08, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(8));
    }

    public String getTamt008() {
        return tamt008;
    }

    public void setTamt008(String tamt008) {
        this.tamt008 = requirePicX(tamt008, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(8));
    }

    public String getSel0009() {
        return sel0009;
    }

    public void setSel0009(String sel0009) {
        this.sel0009 = requirePicX(sel0009, SELECTION_LENGTH, selectionFieldName(9));
    }

    public String getTrnid09() {
        return trnid09;
    }

    public void setTrnid09(String trnid09) {
        this.trnid09 = requirePicX(trnid09, TRANSACTION_ID_LENGTH, transactionIdFieldName(9));
    }

    public String getTdate09() {
        return tdate09;
    }

    public void setTdate09(String tdate09) {
        this.tdate09 = requirePicX(tdate09, TRANSACTION_DATE_LENGTH, transactionDateFieldName(9));
    }

    public String getTdesc09() {
        return tdesc09;
    }

    public void setTdesc09(String tdesc09) {
        this.tdesc09 = requirePicX(tdesc09, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(9));
    }

    public String getTamt009() {
        return tamt009;
    }

    public void setTamt009(String tamt009) {
        this.tamt009 = requirePicX(tamt009, TRANSACTION_AMOUNT_LENGTH, transactionAmountFieldName(9));
    }

    public String getSel0010() {
        return sel0010;
    }

    public void setSel0010(String sel0010) {
        this.sel0010 = requirePicX(sel0010, SELECTION_LENGTH, selectionFieldName(ROW_COUNT));
    }

    public String getTrnid10() {
        return trnid10;
    }

    public void setTrnid10(String trnid10) {
        this.trnid10 = requirePicX(trnid10, TRANSACTION_ID_LENGTH, transactionIdFieldName(ROW_COUNT));
    }

    public String getTdate10() {
        return tdate10;
    }

    public void setTdate10(String tdate10) {
        this.tdate10 = requirePicX(tdate10, TRANSACTION_DATE_LENGTH,
                transactionDateFieldName(ROW_COUNT));
    }

    public String getTdesc10() {
        return tdesc10;
    }

    public void setTdesc10(String tdesc10) {
        this.tdesc10 = requirePicX(tdesc10, TRANSACTION_DESCRIPTION_LENGTH,
                transactionDescriptionFieldName(ROW_COUNT));
    }

    public String getTamt010() {
        return tamt010;
    }

    public void setTamt010(String tamt010) {
        this.tamt010 = requirePicX(tamt010, TRANSACTION_AMOUNT_LENGTH,
                transactionAmountFieldName(ROW_COUNT));
    }

    public String getSelection(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> sel0001;
            case 2 -> sel0002;
            case 3 -> sel0003;
            case 4 -> sel0004;
            case 5 -> sel0005;
            case 6 -> sel0006;
            case 7 -> sel0007;
            case 8 -> sel0008;
            case 9 -> sel0009;
            default -> sel0010;
        };
    }

    public void setSelection(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setSel0001(value);
            case 2 -> setSel0002(value);
            case 3 -> setSel0003(value);
            case 4 -> setSel0004(value);
            case 5 -> setSel0005(value);
            case 6 -> setSel0006(value);
            case 7 -> setSel0007(value);
            case 8 -> setSel0008(value);
            case 9 -> setSel0009(value);
            default -> setSel0010(value);
        }
    }

    public String getTransactionId(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> trnid01;
            case 2 -> trnid02;
            case 3 -> trnid03;
            case 4 -> trnid04;
            case 5 -> trnid05;
            case 6 -> trnid06;
            case 7 -> trnid07;
            case 8 -> trnid08;
            case 9 -> trnid09;
            default -> trnid10;
        };
    }

    /**
     * Sets a row's transaction identifier, {@code TRNIDnnI}.
     *
     * @param oneBasedRow the COBOL screen row, 1 to {@link #ROW_COUNT} inclusive
     * @param value the transaction identifier
     * @throws IndexOutOfBoundsException if {@code oneBasedRow} is outside 1..{@link #ROW_COUNT}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void setTransactionId(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTrnid01(value);
            case 2 -> setTrnid02(value);
            case 3 -> setTrnid03(value);
            case 4 -> setTrnid04(value);
            case 5 -> setTrnid05(value);
            case 6 -> setTrnid06(value);
            case 7 -> setTrnid07(value);
            case 8 -> setTrnid08(value);
            case 9 -> setTrnid09(value);
            default -> setTrnid10(value);
        }
    }

    public String getTransactionDate(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> tdate01;
            case 2 -> tdate02;
            case 3 -> tdate03;
            case 4 -> tdate04;
            case 5 -> tdate05;
            case 6 -> tdate06;
            case 7 -> tdate07;
            case 8 -> tdate08;
            case 9 -> tdate09;
            default -> tdate10;
        };
    }

    public void setTransactionDate(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTdate01(value);
            case 2 -> setTdate02(value);
            case 3 -> setTdate03(value);
            case 4 -> setTdate04(value);
            case 5 -> setTdate05(value);
            case 6 -> setTdate06(value);
            case 7 -> setTdate07(value);
            case 8 -> setTdate08(value);
            case 9 -> setTdate09(value);
            default -> setTdate10(value);
        }
    }

    public String getTransactionDescription(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> tdesc01;
            case 2 -> tdesc02;
            case 3 -> tdesc03;
            case 4 -> tdesc04;
            case 5 -> tdesc05;
            case 6 -> tdesc06;
            case 7 -> tdesc07;
            case 8 -> tdesc08;
            case 9 -> tdesc09;
            default -> tdesc10;
        };
    }

    public void setTransactionDescription(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTdesc01(value);
            case 2 -> setTdesc02(value);
            case 3 -> setTdesc03(value);
            case 4 -> setTdesc04(value);
            case 5 -> setTdesc05(value);
            case 6 -> setTdesc06(value);
            case 7 -> setTdesc07(value);
            case 8 -> setTdesc08(value);
            case 9 -> setTdesc09(value);
            default -> setTdesc10(value);
        }
    }

    public String getTransactionAmount(int oneBasedRow) {
        return switch (requireValidRow(oneBasedRow)) {
            case 1 -> tamt001;
            case 2 -> tamt002;
            case 3 -> tamt003;
            case 4 -> tamt004;
            case 5 -> tamt005;
            case 6 -> tamt006;
            case 7 -> tamt007;
            case 8 -> tamt008;
            case 9 -> tamt009;
            default -> tamt010;
        };
    }

    public void setTransactionAmount(int oneBasedRow, String value) {
        switch (requireValidRow(oneBasedRow)) {
            case 1 -> setTamt001(value);
            case 2 -> setTamt002(value);
            case 3 -> setTamt003(value);
            case 4 -> setTamt004(value);
            case 5 -> setTamt005(value);
            case 6 -> setTamt006(value);
            case 7 -> setTamt007(value);
            case 8 -> setTamt008(value);
            case 9 -> setTamt009(value);
            default -> setTamt010(value);
        }
    }

    /**
     * The payload value of a field, addressed by its verbatim base name.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}, for example {@code TAMT001}
     * @return the value at exactly that field's declared width
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the {@link #FIELD_COUNT}
     *     fields of this map
     */
    @JsonIgnore
    public String getPayloadValue(String baseFieldName) {
        String name = requireFieldName(baseFieldName);
        if (TRNNAME_FIELD.equals(name)) {
            return trnname;
        }
        if (TITLE01_FIELD.equals(name)) {
            return title01;
        }
        if (CURDATE_FIELD.equals(name)) {
            return curdate;
        }
        if (PGMNAME_FIELD.equals(name)) {
            return pgmname;
        }
        if (TITLE02_FIELD.equals(name)) {
            return title02;
        }
        if (CURTIME_FIELD.equals(name)) {
            return curtime;
        }
        if (PAGENUM_FIELD.equals(name)) {
            return pagenum;
        }
        if (TRNIDIN_FIELD.equals(name)) {
            return trnidin;
        }
        if (ERRMSG_FIELD.equals(name)) {
            return errmsg;
        }
        for (int row = 1; row <= ROW_COUNT; row++) {
            if (selectionFieldName(row).equals(name)) {
                return getSelection(row);
            }
            if (transactionIdFieldName(row).equals(name)) {
                return getTransactionId(row);
            }
            if (transactionDateFieldName(row).equals(name)) {
                return getTransactionDate(row);
            }
            if (transactionDescriptionFieldName(row).equals(name)) {
                return getTransactionDescription(row);
            }
            if (transactionAmountFieldName(row).equals(name)) {
                return getTransactionAmount(row);
            }
        }
        throw new IllegalArgumentException(unknownFieldMessage(name));
    }

    /**
     * Sets the payload value of a field, addressed by its verbatim base name.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}
     * @param value the value to store
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the {@link #FIELD_COUNT}
     *     fields of this map
     */
    public void setPayloadValue(String baseFieldName, String value) {
        String name = requireFieldName(baseFieldName);
        if (TRNNAME_FIELD.equals(name)) {
            setTrnname(value);
            return;
        }
        if (TITLE01_FIELD.equals(name)) {
            setTitle01(value);
            return;
        }
        if (CURDATE_FIELD.equals(name)) {
            setCurdate(value);
            return;
        }
        if (PGMNAME_FIELD.equals(name)) {
            setPgmname(value);
            return;
        }
        if (TITLE02_FIELD.equals(name)) {
            setTitle02(value);
            return;
        }
        if (CURTIME_FIELD.equals(name)) {
            setCurtime(value);
            return;
        }
        if (PAGENUM_FIELD.equals(name)) {
            setPagenum(value);
            return;
        }
        if (TRNIDIN_FIELD.equals(name)) {
            setTrnidin(value);
            return;
        }
        if (ERRMSG_FIELD.equals(name)) {
            setErrmsg(value);
            return;
        }
        for (int row = 1; row <= ROW_COUNT; row++) {
            if (selectionFieldName(row).equals(name)) {
                setSelection(row, value);
                return;
            }
            if (transactionIdFieldName(row).equals(name)) {
                setTransactionId(row, value);
                return;
            }
            if (transactionDateFieldName(row).equals(name)) {
                setTransactionDate(row, value);
                return;
            }
            if (transactionDescriptionFieldName(row).equals(name)) {
                setTransactionDescription(row, value);
                return;
            }
            if (transactionAmountFieldName(row).equals(name)) {
                setTransactionAmount(row, value);
                return;
            }
        }
        throw new IllegalArgumentException(unknownFieldMessage(name));
    }

    /**
     * All {@link #FIELD_COUNT} payload values keyed by verbatim base field name, in copybook declaration
     * order.
     *
     * @return an unmodifiable, declaration-ordered map of {@link #FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, String> getPayloadValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String baseFieldName : FIELD_NAMES) {
            values.put(baseFieldName, getPayloadValue(baseFieldName));
        }
        return Collections.unmodifiableMap(values);
    }

    private static String unknownFieldMessage(String name) {
        return "'" + name + "' is not one of the " + FIELD_COUNT + " fields of map " + MAP_NAME
                + "; the row suffixes are deliberately inconsistent, so check the spelling - the "
                + "selector takes four digits (SEL0001), the identifier, date and description take "
                + "two (TRNID01), and the amount takes three (TAMT001)";
    }

    @JsonIgnore
    public FieldMetadata getMetadata(String baseFieldName) {
        FieldMetadata metadata = fieldMetadata.get(requireFieldName(baseFieldName));
        if (metadata == null) {
            throw new IllegalArgumentException(unknownFieldMessage(baseFieldName));
        }
        return metadata;
    }

    /**
     * Every metadata carrier, keyed by verbatim base field name in declaration order.
     *
     * @return an unmodifiable view of {@link #FIELD_COUNT} entries
     */
    @JsonIgnore
    public Map<String, FieldMetadata> getFieldMetadata() {
        return Collections.unmodifiableMap(fieldMetadata);
    }

    /**
     * Reproduces {@code MOVE -1 TO xxxL}: asks CICS to place the cursor at the named field.
     *
     * @param baseFieldName one of {@link #FIELD_NAMES}
     * @throws NullPointerException if {@code baseFieldName} is {@code null}
     * @throws IllegalArgumentException if {@code baseFieldName} is not one of the {@link #FIELD_COUNT}
     *     fields of this map
     */
    public void positionCursorAt(String baseFieldName) {
        getMetadata(baseFieldName).requestCursorPosition();
    }

    /**
     * The field currently carrying the cursor-positioning request, if any.
     *
     * @return the verbatim base name of the field whose length item is {@link #CURSOR_POSITION_REQUEST}, or
     *     {@code null} when no field carries it
     */
    @JsonIgnore
    public String getCursorPositionField() {
        for (Map.Entry<String, FieldMetadata> entry : fieldMetadata.entrySet()) {
            if (entry.getValue().isCursorPositionRequested()) {
                return entry.getKey();
            }
        }
        return null;
    }

    // The REST wire format is JSON; this is the mainframe view of the same payload, provided so a parity
    // case can be expressed in the bytes the COBOL would have held and so the SYMBOLIC_MAP_LENGTH total is
    // exercised rather than merely asserted.

    /**
     * Renders the {@link #FIELD_COUNT} payload fields as the {@link #SYMBOLIC_MAP_LENGTH}-byte
     * {@code COTRN0AI} group image.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return a fresh array of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} does not encode the space to exactly one byte,
     *     since a fixed-width span is addressed by absolute byte offset
     */
    public byte[] toFixedWidth(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render " + SYMBOLIC_MAP_INPUT_GROUP
                + " as bytes; a fixed-width image is bytes in a specific code page, so the code page "
                + "is stated explicitly and never taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        FixedWidthRecord record = codec.newRecord(LAYOUT);
        writeInto(record);
        return record.toByteArray();
    }

    /**
     * Writes the {@link #FIELD_COUNT} payload fields into an existing record area, using that record's own
     * code page.
     *
     * @param record a record area of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    public void writeInto(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to write "
                + SYMBOLIC_MAP_INPUT_GROUP + " into");
        if (record.recordLength() != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("Record area is " + record.recordLength()
                    + " byte(s) but " + SYMBOLIC_MAP_INPUT_GROUP + " is " + SYMBOLIC_MAP_LENGTH);
        }
        for (String baseFieldName : FIELD_NAMES) {
            record.writeSpan(LAYOUT.span(inputItemName(baseFieldName)),
                    getPayloadValue(baseFieldName));
        }
    }

    /**
     * Rebuilds a request's {@link #FIELD_COUNT} payload fields from a {@link #SYMBOLIC_MAP_LENGTH}-byte
     * group image.
     *
     * @param image exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @param charset the code page the image is encoded in, named explicitly by the caller
     * @return a request holding the {@link #FIELD_COUNT} fields the image carries
     * @throws NullPointerException if {@code image} or {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code image} is not exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     */
    public static TransactionListRequest fromFixedWidth(byte[] image, Charset charset) {
        Objects.requireNonNull(image, "An image is required to rebuild "
                + SYMBOLIC_MAP_INPUT_GROUP);
        Objects.requireNonNull(charset, "A charset is required to decode a "
                + SYMBOLIC_MAP_INPUT_GROUP + " image; the code page is stated explicitly and never "
                + "taken from the platform");
        FixedWidthCodec codec = new FixedWidthCodec(charset);
        return readFrom(codec.wrap(image, LAYOUT));
    }

    /**
     * Reads the {@link #FIELD_COUNT} payload fields out of an existing record area, using that record's own
     * code page.
     *
     * @param record a record area of exactly {@link #SYMBOLIC_MAP_LENGTH} bytes
     * @return a request holding the {@link #FIELD_COUNT} fields the record carries
     * @throws NullPointerException if {@code record} is {@code null}
     * @throws IllegalArgumentException if the record's declared length is not {@link #SYMBOLIC_MAP_LENGTH}
     */
    public static TransactionListRequest readFrom(FixedWidthRecord record) {
        Objects.requireNonNull(record, "A record area is required to read "
                + SYMBOLIC_MAP_INPUT_GROUP + " from");
        if (record.recordLength() != SYMBOLIC_MAP_LENGTH) {
            throw new IllegalArgumentException("Record area is " + record.recordLength()
                    + " byte(s) but " + SYMBOLIC_MAP_INPUT_GROUP + " is " + SYMBOLIC_MAP_LENGTH);
        }
        TransactionListRequest request = new TransactionListRequest();
        for (String baseFieldName : FIELD_NAMES) {
            request.setPayloadValue(baseFieldName,
                    record.readSpan(LAYOUT.span(inputItemName(baseFieldName))));
        }
        return request;
    }

    /**
     * Renders the communication area {@code COTRN00C} actually passes on {@code XCTL}: the
     * {@value NavigationContext#COMMAREA_LENGTH}-byte {@code CARDDEMO-COMMAREA} immediately followed by
     * this screen's {@value PaginationCursor#CURSOR_LENGTH}-byte {@code CDEMO-CT00-INFO} extension, that is
     * {@value PaginationCursor#COMMAREA_LENGTH} bytes in total.
     *
     * @param charset the code page to encode into, named explicitly by the caller
     * @return a fresh array of exactly {@value PaginationCursor#COMMAREA_LENGTH} bytes
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalStateException if no communication area travelled with this request
     */
    public byte[] toCommareaImage(Charset charset) {
        Objects.requireNonNull(charset, "A charset is required to render the communication area; the "
                + "code page is stated explicitly and never taken from the platform");
        if (!hasNavigationContext()) {
            throw new IllegalStateException("No communication area travelled with this request, so "
                    + "there are no " + PaginationCursor.COMMAREA_LENGTH + " bytes to render: "
                    + "EIBCALEN is 0, which is the cold start COTRN00C.cbl:107 tests for and answers "
                    + "by transferring to COSGN00C. Test hasNavigationContext() first, or set an area "
                    + "with setNavigationContext");
        }
        byte[] contextImage = navigationContext.toFixedWidth(new FixedWidthCodec(charset));
        byte[] cursorImage = cursor.toFixedWidth(charset);
        byte[] commarea = new byte[PaginationCursor.COMMAREA_LENGTH];
        System.arraycopy(contextImage, 0, commarea, 0, contextImage.length);
        System.arraycopy(cursorImage, 0, commarea, contextImage.length, cursorImage.length);
        return commarea;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionListRequest that)) {
            return false;
        }
        return getPayloadValues().equals(that.getPayloadValues())
                && Objects.equals(navigationContext, that.navigationContext)
                && Objects.equals(aid, that.aid)
                && cursor.equals(that.cursor)
                && fieldMetadata.equals(that.fieldMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getPayloadValues(), navigationContext, cursor, fieldMetadata, aid);
    }

    /**
     * A diagnostic summary naming the screen, the browse position and the cursor field.
     *
     * @return for example
     *     {@code TransactionListRequest[CT00/COTRN00C, map=COTRN0A, fields=59, PaginationCursor[page=1, first=, last=, nextPage=N], context=ENTER]}
     */
    @Override
    public String toString() {
        return "TransactionListRequest[" + TRANSACTION_ID + "/" + PROGRAM_NAME
                + ", map=" + MAP_NAME
                + ", fields=" + FIELD_COUNT
                + ", " + cursor
                + ", " + AID_FIELD + "='" + aid + "'"
                + ", context=" + (hasNavigationContext()
                        ? (isReenter() ? "REENTER" : "ENTER")
                        : "none (EIBCALEN=0)")
                + "]";
    }
}
