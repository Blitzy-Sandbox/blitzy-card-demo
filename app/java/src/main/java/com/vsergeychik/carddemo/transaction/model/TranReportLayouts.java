package com.vsergeychik.carddemo.transaction.model;

import com.vsergeychik.carddemo.common.CobolDecimal;
import com.vsergeychik.carddemo.common.FixedWidthCodec;
import com.vsergeychik.carddemo.common.FixedWidthRecord;
import com.vsergeychik.carddemo.common.FixedWidthRecord.FieldSpan;
import com.vsergeychik.carddemo.common.FixedWidthRecord.RecordLayout;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * The five report line layouts of {@code app/cpy/CVTRA07Y.cpy}, the print image of the CardDemo daily
 * transaction report.
 *
 * <p>Columns below are 1-based, exactly as a COBOL programmer counts them; the offsets in the code are the
 * 0-based equivalents.
 */
public final class TranReportLayouts {
    /**
     * {@code REPORT-NAME-HEADER} width: {@code 38 + 41 + 12 + 10 + 4 + 10}.
     */
    public static final int REPORT_NAME_HEADER_LENGTH = 115;

    /**
     * {@code TRANSACTION-DETAIL-REPORT} width: eight named items plus eight {@code FILLER}s.
     */
    public static final int TRANSACTION_DETAIL_REPORT_LENGTH = 114;

    /**
     * {@code TRANSACTION-HEADER-1} width: {@code 17 + 12 + 19 + 35 + 14 + 1 + 16}.
     */
    public static final int TRANSACTION_HEADER_1_LENGTH = 114;

    /**
     * {@code TRANSACTION-HEADER-2} width: the elementary {@code PIC X(133)} itself.
     */
    public static final int TRANSACTION_HEADER_2_LENGTH = 133;

    /**
     * {@code REPORT-PAGE-TOTALS} width: {@code 11 + 86 + 15}.
     */
    public static final int REPORT_PAGE_TOTALS_LENGTH = 112;

    /**
     * {@code REPORT-ACCOUNT-TOTALS} width: {@code 13 + 84 + 15}.
     */
    public static final int REPORT_ACCOUNT_TOTALS_LENGTH = 112;

    /**
     * {@code REPORT-GRAND-TOTALS} width: {@code 11 + 86 + 15}.
     */
    public static final int REPORT_GRAND_TOTALS_LENGTH = 112;

    public static final int AMOUNT_COLUMN_START = 98;

    public static final int AMOUNT_COLUMN_END = 112;

    public static final int AMOUNT_OFFSET = 97;

    public static final int AMOUNT_MASK_WIDTH = 15;

    public static final int AMOUNT_INTEGER_DIGITS = 9;

    public static final int AMOUNT_FRACTION_DIGITS = 2;

    /**
     * {@code TRAN-REPORT-AMT}'s mask: a fixed minus, then all-{@code Z} suppression.
     */
    public static final String DETAIL_AMOUNT_MASK = "-ZZZ,ZZZ,ZZZ.ZZ";

    public static final String TOTAL_AMOUNT_MASK = "+ZZZ,ZZZ,ZZZ.ZZ";

    /**
     * {@code REPT-SHORT-NAME PIC X(38)}, columns 1-38.
     */
    public static final int REPT_SHORT_NAME_LENGTH = 38;

    /**
     * {@code REPT-SHORT-NAME VALUE 'DALYREPT'} — literal length 8, right-space-padded to 38.
     */
    public static final String REPT_SHORT_NAME_VALUE = "DALYREPT";

    /**
     * {@code REPT-LONG-NAME PIC X(41)}, columns 39-79.
     */
    public static final int REPT_LONG_NAME_LENGTH = 41;

    /**
     * {@code REPT-LONG-NAME VALUE 'Daily Transaction Report'} — literal length 24, right-space-padded to
     * 41.
     */
    public static final String REPT_LONG_NAME_VALUE = "Daily Transaction Report";

    /**
     * {@code REPT-DATE-HEADER PIC X(12)}, columns 80-91.
     */
    public static final int REPT_DATE_HEADER_LENGTH = 12;

    /**
     * {@code REPT-DATE-HEADER VALUE 'Date Range: '} — literal length 12, which exactly fills the field.
     */
    public static final String REPT_DATE_HEADER_VALUE = "Date Range: ";

    /**
     * {@code REPT-START-DATE PIC X(10) VALUE SPACES}, columns 92-101.
     */
    public static final int REPT_START_DATE_LENGTH = 10;

    /**
     * {@code FILLER PIC X(04)} between the two dates, columns 102-105.
     */
    public static final int DATE_RANGE_SEPARATOR_LENGTH = 4;

    /**
     * {@code FILLER PIC X(04) VALUE ' to '} — literal length 4, which exactly fills the field.
     */
    public static final String DATE_RANGE_SEPARATOR_VALUE = " to ";

    /**
     * {@code REPT-END-DATE PIC X(10) VALUE SPACES}, columns 106-115.
     */
    public static final int REPT_END_DATE_LENGTH = 10;

    /**
     * {@code TRAN-REPORT-TRANS-ID PIC X(16)}, columns 1-16.
     */
    public static final int TRAN_REPORT_TRANS_ID_LENGTH = 16;

    /**
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, columns 18-28.
     */
    public static final int TRAN_REPORT_ACCOUNT_ID_LENGTH = 11;

    /**
     * {@code TRAN-REPORT-TYPE-CD PIC X(02)}, columns 30-31.
     */
    public static final int TRAN_REPORT_TYPE_CD_LENGTH = 2;

    /**
     * {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, columns 33-47.
     */
    public static final int TRAN_REPORT_TYPE_DESC_LENGTH = 15;

    /**
     * {@code TRAN-REPORT-CAT-CD PIC 9(04)}, columns 49-52.
     */
    public static final int TRAN_REPORT_CAT_CD_LENGTH = 4;

    /**
     * {@code TRAN-REPORT-CAT-DESC PIC X(29)}, columns 54-82.
     */
    public static final int TRAN_REPORT_CAT_DESC_LENGTH = 29;

    /**
     * {@code TRAN-REPORT-SOURCE PIC X(10)}, columns 84-93.
     */
    public static final int TRAN_REPORT_SOURCE_LENGTH = 10;

    /**
     * The {@code FILLER PIC X(01) VALUE '-'} separators at columns 32 and 53.
     */
    public static final String DETAIL_SEPARATOR_VALUE = "-";

    /**
     * {@code FILLER PIC X(17) VALUE 'Transaction ID'}, columns 1-17; literal length 14.
     */
    public static final int HEADER_1_TRANSACTION_ID_LENGTH = 17;

    public static final String HEADER_1_TRANSACTION_ID_VALUE = "Transaction ID";

    /**
     * {@code FILLER PIC X(12) VALUE 'Account ID'}, columns 18-29; literal length 10.
     */
    public static final int HEADER_1_ACCOUNT_ID_LENGTH = 12;

    public static final String HEADER_1_ACCOUNT_ID_VALUE = "Account ID";

    /**
     * {@code FILLER PIC X(19) VALUE 'Transaction Type'}, columns 30-48; literal length 16.
     */
    public static final int HEADER_1_TRANSACTION_TYPE_LENGTH = 19;

    public static final String HEADER_1_TRANSACTION_TYPE_VALUE = "Transaction Type";

    /**
     * {@code FILLER PIC X(35) VALUE 'Tran Category'}, columns 49-83; literal length 13.
     */
    public static final int HEADER_1_TRAN_CATEGORY_LENGTH = 35;

    public static final String HEADER_1_TRAN_CATEGORY_VALUE = "Tran Category";

    /**
     * {@code FILLER PIC X(14) VALUE 'Tran Source'}, columns 84-97; literal length 11.
     */
    public static final int HEADER_1_TRAN_SOURCE_LENGTH = 14;

    public static final String HEADER_1_TRAN_SOURCE_VALUE = "Tran Source";

    /**
     * {@code FILLER PIC X VALUE SPACES} at column 98 — an implicit {@code X(01)}, written in the copybook
     * without a length.
     */
    public static final int HEADER_1_GAP_LENGTH = 1;

    /**
     * {@code FILLER PIC X(16) VALUE ' Amount'}, columns 99-114; literal length 14.
     */
    public static final int HEADER_1_AMOUNT_LENGTH = 16;

    public static final String HEADER_1_AMOUNT_VALUE = "        Amount";

    public static final int HEADER_1_AMOUNT_LEADING_SPACES = 8;

    /**
     * 1-based column at which the word {@code Amount} begins in {@code TRANSACTION-HEADER-1}.
     */
    public static final int HEADER_1_AMOUNT_WORD_COLUMN_START = 107;

    /**
     * The repeated character of {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}.
     */
    public static final char TRANSACTION_HEADER_2_RULE_CHARACTER = '-';

    /**
     * The full {@code TRANSACTION-HEADER-2} image: exactly {@value #TRANSACTION_HEADER_2_LENGTH} hyphens.
     */
    public static final String TRANSACTION_HEADER_2_IMAGE =
            String.valueOf(TRANSACTION_HEADER_2_RULE_CHARACTER).repeat(TRANSACTION_HEADER_2_LENGTH);

    public static final char TOTAL_LEADER_CHARACTER = '.';

    /**
     * {@code FILLER PIC X(11) VALUE 'Page Total'}, columns 1-11; literal length 10.
     */
    public static final int PAGE_TOTAL_LABEL_LENGTH = 11;

    public static final String PAGE_TOTAL_LABEL_VALUE = "Page Total";

    /**
     * {@code FILLER PIC X(86) VALUE ALL '.'}, columns 12-97.
     */
    public static final int PAGE_TOTAL_LEADER_LENGTH = 86;

    /**
     * {@code FILLER PIC X(13) VALUE 'Account Total'}, columns 1-13; literal length 13.
     */
    public static final int ACCOUNT_TOTAL_LABEL_LENGTH = 13;

    public static final String ACCOUNT_TOTAL_LABEL_VALUE = "Account Total";

    /**
     * {@code FILLER PIC X(84) VALUE ALL '.'}, columns 14-97.
     */
    public static final int ACCOUNT_TOTAL_LEADER_LENGTH = 84;

    /**
     * {@code FILLER PIC X(11) VALUE 'Grand Total'}, columns 1-11; literal length 11.
     */
    public static final int GRAND_TOTAL_LABEL_LENGTH = 11;

    public static final String GRAND_TOTAL_LABEL_VALUE = "Grand Total";

    /**
     * {@code FILLER PIC X(86) VALUE ALL '.'}, columns 12-97.
     */
    public static final int GRAND_TOTAL_LEADER_LENGTH = 86;

    /**
     * {@code REPORT-PAGE-TOTALS}' dot leader: exactly {@value #PAGE_TOTAL_LEADER_LENGTH} dots.
     */
    public static final String PAGE_TOTAL_LEADER_IMAGE =
            String.valueOf(TOTAL_LEADER_CHARACTER).repeat(PAGE_TOTAL_LEADER_LENGTH);

    /**
     * {@code REPORT-ACCOUNT-TOTALS}' dot leader: exactly {@value #ACCOUNT_TOTAL_LEADER_LENGTH} dots.
     */
    public static final String ACCOUNT_TOTAL_LEADER_IMAGE =
            String.valueOf(TOTAL_LEADER_CHARACTER).repeat(ACCOUNT_TOTAL_LEADER_LENGTH);

    /**
     * {@code REPORT-GRAND-TOTALS}' dot leader: exactly {@value #GRAND_TOTAL_LEADER_LENGTH} dots.
     */
    public static final String GRAND_TOTAL_LEADER_IMAGE =
            String.valueOf(TOTAL_LEADER_CHARACTER).repeat(GRAND_TOTAL_LEADER_LENGTH);

    public static final int REPT_SHORT_NAME_OFFSET = 0;

    public static final int REPT_LONG_NAME_OFFSET = REPT_SHORT_NAME_OFFSET + REPT_SHORT_NAME_LENGTH;

    public static final int REPT_DATE_HEADER_OFFSET = REPT_LONG_NAME_OFFSET + REPT_LONG_NAME_LENGTH;

    public static final int REPT_START_DATE_OFFSET =
            REPT_DATE_HEADER_OFFSET + REPT_DATE_HEADER_LENGTH;

    private static final int DATE_RANGE_SEPARATOR_OFFSET =
            REPT_START_DATE_OFFSET + REPT_START_DATE_LENGTH;

    public static final int REPT_END_DATE_OFFSET =
            DATE_RANGE_SEPARATOR_OFFSET + DATE_RANGE_SEPARATOR_LENGTH;

    public static final int TRAN_REPORT_TRANS_ID_OFFSET = 0;

    private static final int DETAIL_FILLER_AFTER_TRANS_ID_OFFSET =
            TRAN_REPORT_TRANS_ID_OFFSET + TRAN_REPORT_TRANS_ID_LENGTH;

    public static final int TRAN_REPORT_ACCOUNT_ID_OFFSET = DETAIL_FILLER_AFTER_TRANS_ID_OFFSET + 1;

    private static final int DETAIL_FILLER_AFTER_ACCOUNT_ID_OFFSET =
            TRAN_REPORT_ACCOUNT_ID_OFFSET + TRAN_REPORT_ACCOUNT_ID_LENGTH;

    public static final int TRAN_REPORT_TYPE_CD_OFFSET = DETAIL_FILLER_AFTER_ACCOUNT_ID_OFFSET + 1;

    private static final int DETAIL_TYPE_SEPARATOR_OFFSET =
            TRAN_REPORT_TYPE_CD_OFFSET + TRAN_REPORT_TYPE_CD_LENGTH;

    public static final int TRAN_REPORT_TYPE_DESC_OFFSET = DETAIL_TYPE_SEPARATOR_OFFSET + 1;

    private static final int DETAIL_FILLER_AFTER_TYPE_DESC_OFFSET =
            TRAN_REPORT_TYPE_DESC_OFFSET + TRAN_REPORT_TYPE_DESC_LENGTH;

    public static final int TRAN_REPORT_CAT_CD_OFFSET = DETAIL_FILLER_AFTER_TYPE_DESC_OFFSET + 1;

    private static final int DETAIL_CAT_SEPARATOR_OFFSET =
            TRAN_REPORT_CAT_CD_OFFSET + TRAN_REPORT_CAT_CD_LENGTH;

    public static final int TRAN_REPORT_CAT_DESC_OFFSET = DETAIL_CAT_SEPARATOR_OFFSET + 1;

    private static final int DETAIL_FILLER_AFTER_CAT_DESC_OFFSET =
            TRAN_REPORT_CAT_DESC_OFFSET + TRAN_REPORT_CAT_DESC_LENGTH;

    public static final int TRAN_REPORT_SOURCE_OFFSET = DETAIL_FILLER_AFTER_CAT_DESC_OFFSET + 1;

    private static final int DETAIL_FILLER_BEFORE_AMOUNT_OFFSET =
            TRAN_REPORT_SOURCE_OFFSET + TRAN_REPORT_SOURCE_LENGTH;

    private static final int DETAIL_FILLER_BEFORE_AMOUNT_LENGTH = 4;

    public static final int TRAN_REPORT_AMT_OFFSET =
            DETAIL_FILLER_BEFORE_AMOUNT_OFFSET + DETAIL_FILLER_BEFORE_AMOUNT_LENGTH;

    private static final int DETAIL_TRAILING_FILLER_OFFSET =
            TRAN_REPORT_AMT_OFFSET + AMOUNT_MASK_WIDTH;

    private static final int DETAIL_TRAILING_FILLER_LENGTH = 2;

    private static final int HEADER_1_TRANSACTION_ID_OFFSET = 0;

    private static final int HEADER_1_ACCOUNT_ID_OFFSET =
            HEADER_1_TRANSACTION_ID_OFFSET + HEADER_1_TRANSACTION_ID_LENGTH;

    private static final int HEADER_1_TRANSACTION_TYPE_OFFSET =
            HEADER_1_ACCOUNT_ID_OFFSET + HEADER_1_ACCOUNT_ID_LENGTH;

    private static final int HEADER_1_TRAN_CATEGORY_OFFSET =
            HEADER_1_TRANSACTION_TYPE_OFFSET + HEADER_1_TRANSACTION_TYPE_LENGTH;

    private static final int HEADER_1_TRAN_SOURCE_OFFSET =
            HEADER_1_TRAN_CATEGORY_OFFSET + HEADER_1_TRAN_CATEGORY_LENGTH;

    private static final int HEADER_1_GAP_OFFSET =
            HEADER_1_TRAN_SOURCE_OFFSET + HEADER_1_TRAN_SOURCE_LENGTH;

    private static final int HEADER_1_AMOUNT_OFFSET = HEADER_1_GAP_OFFSET + HEADER_1_GAP_LENGTH;

    /**
     * Column 1 — the elementary {@code TRANSACTION-HEADER-2} spans the whole line.
     */
    public static final int TRANSACTION_HEADER_2_OFFSET = 0;

    private static final int PAGE_TOTAL_LABEL_OFFSET = 0;

    private static final int PAGE_TOTAL_LEADER_OFFSET =
            PAGE_TOTAL_LABEL_OFFSET + PAGE_TOTAL_LABEL_LENGTH;

    public static final int REPT_PAGE_TOTAL_OFFSET =
            PAGE_TOTAL_LEADER_OFFSET + PAGE_TOTAL_LEADER_LENGTH;

    private static final int ACCOUNT_TOTAL_LABEL_OFFSET = 0;

    private static final int ACCOUNT_TOTAL_LEADER_OFFSET =
            ACCOUNT_TOTAL_LABEL_OFFSET + ACCOUNT_TOTAL_LABEL_LENGTH;

    public static final int REPT_ACCOUNT_TOTAL_OFFSET =
            ACCOUNT_TOTAL_LEADER_OFFSET + ACCOUNT_TOTAL_LEADER_LENGTH;

    private static final int GRAND_TOTAL_LABEL_OFFSET = 0;

    private static final int GRAND_TOTAL_LEADER_OFFSET =
            GRAND_TOTAL_LABEL_OFFSET + GRAND_TOTAL_LABEL_LENGTH;

    public static final int REPT_GRAND_TOTAL_OFFSET =
            GRAND_TOTAL_LEADER_OFFSET + GRAND_TOTAL_LEADER_LENGTH;

    // A numeric-edited item is character data once edited - it holds a sign, digits, commas and a decimal
    // point, is left-justified and space-padded for alignment, and never carries a zoned overpunch - so
    // ALPHANUMERIC is the faithful category for TRAN-REPORT-AMT and the three REPT-*-TOTAL fields.

    /**
     * {@code REPT-SHORT-NAME PIC X(38) VALUE 'DALYREPT'}, columns 1-38.
     */
    public static final FieldSpan REPT_SHORT_NAME =
            FieldSpan.alphanumeric("REPT-SHORT-NAME", REPT_SHORT_NAME_OFFSET, REPT_SHORT_NAME_LENGTH)
                    .withInitialValue(REPT_SHORT_NAME_VALUE);

    /**
     * {@code REPT-LONG-NAME PIC X(41) VALUE 'Daily Transaction Report'}, columns 39-79.
     */
    public static final FieldSpan REPT_LONG_NAME =
            FieldSpan.alphanumeric("REPT-LONG-NAME", REPT_LONG_NAME_OFFSET, REPT_LONG_NAME_LENGTH)
                    .withInitialValue(REPT_LONG_NAME_VALUE);

    /**
     * {@code REPT-DATE-HEADER PIC X(12) VALUE 'Date Range: '}, columns 80-91.
     */
    public static final FieldSpan REPT_DATE_HEADER =
            FieldSpan.alphanumeric("REPT-DATE-HEADER", REPT_DATE_HEADER_OFFSET,
                            REPT_DATE_HEADER_LENGTH)
                    .withInitialValue(REPT_DATE_HEADER_VALUE);

    /**
     * {@code REPT-START-DATE PIC X(10) VALUE SPACES}, columns 92-101.
     */
    public static final FieldSpan REPT_START_DATE =
            FieldSpan.alphanumeric("REPT-START-DATE", REPT_START_DATE_OFFSET,
                    REPT_START_DATE_LENGTH);

    private static final FieldSpan DATE_RANGE_SEPARATOR = FieldSpan.filler(
            DATE_RANGE_SEPARATOR_OFFSET, DATE_RANGE_SEPARATOR_LENGTH, DATE_RANGE_SEPARATOR_VALUE);

    /**
     * {@code REPT-END-DATE PIC X(10) VALUE SPACES}, columns 106-115.
     */
    public static final FieldSpan REPT_END_DATE =
            FieldSpan.alphanumeric("REPT-END-DATE", REPT_END_DATE_OFFSET, REPT_END_DATE_LENGTH);

    /**
     * {@code TRAN-REPORT-TRANS-ID PIC X(16)}, columns 1-16.
     */
    public static final FieldSpan TRAN_REPORT_TRANS_ID =
            FieldSpan.alphanumeric("TRAN-REPORT-TRANS-ID", TRAN_REPORT_TRANS_ID_OFFSET,
                    TRAN_REPORT_TRANS_ID_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_TRANS_ID =
            FieldSpan.filler(DETAIL_FILLER_AFTER_TRANS_ID_OFFSET, 1);

    /**
     * {@code TRAN-REPORT-ACCOUNT-ID PIC X(11)}, columns 18-28.
     */
    public static final FieldSpan TRAN_REPORT_ACCOUNT_ID =
            FieldSpan.alphanumeric("TRAN-REPORT-ACCOUNT-ID", TRAN_REPORT_ACCOUNT_ID_OFFSET,
                    TRAN_REPORT_ACCOUNT_ID_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_ACCOUNT_ID =
            FieldSpan.filler(DETAIL_FILLER_AFTER_ACCOUNT_ID_OFFSET, 1);

    /**
     * {@code TRAN-REPORT-TYPE-CD PIC X(02)}, columns 30-31.
     */
    public static final FieldSpan TRAN_REPORT_TYPE_CD =
            FieldSpan.alphanumeric("TRAN-REPORT-TYPE-CD", TRAN_REPORT_TYPE_CD_OFFSET,
                    TRAN_REPORT_TYPE_CD_LENGTH);

    private static final FieldSpan DETAIL_TYPE_SEPARATOR =
            FieldSpan.filler(DETAIL_TYPE_SEPARATOR_OFFSET, 1, DETAIL_SEPARATOR_VALUE);

    /**
     * {@code TRAN-REPORT-TYPE-DESC PIC X(15)}, columns 33-47.
     */
    public static final FieldSpan TRAN_REPORT_TYPE_DESC =
            FieldSpan.alphanumeric("TRAN-REPORT-TYPE-DESC", TRAN_REPORT_TYPE_DESC_OFFSET,
                    TRAN_REPORT_TYPE_DESC_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_TYPE_DESC =
            FieldSpan.filler(DETAIL_FILLER_AFTER_TYPE_DESC_OFFSET, 1);

    /**
     * {@code TRAN-REPORT-CAT-CD PIC 9(04)}, columns 49-52 — the layout's only numeric item.
     */
    public static final FieldSpan TRAN_REPORT_CAT_CD =
            FieldSpan.unsignedNumeric("TRAN-REPORT-CAT-CD", TRAN_REPORT_CAT_CD_OFFSET,
                    TRAN_REPORT_CAT_CD_LENGTH);

    private static final FieldSpan DETAIL_CAT_SEPARATOR =
            FieldSpan.filler(DETAIL_CAT_SEPARATOR_OFFSET, 1, DETAIL_SEPARATOR_VALUE);

    /**
     * {@code TRAN-REPORT-CAT-DESC PIC X(29)}, columns 54-82.
     */
    public static final FieldSpan TRAN_REPORT_CAT_DESC =
            FieldSpan.alphanumeric("TRAN-REPORT-CAT-DESC", TRAN_REPORT_CAT_DESC_OFFSET,
                    TRAN_REPORT_CAT_DESC_LENGTH);

    private static final FieldSpan DETAIL_FILLER_AFTER_CAT_DESC =
            FieldSpan.filler(DETAIL_FILLER_AFTER_CAT_DESC_OFFSET, 1);

    /**
     * {@code TRAN-REPORT-SOURCE PIC X(10)}, columns 84-93.
     */
    public static final FieldSpan TRAN_REPORT_SOURCE =
            FieldSpan.alphanumeric("TRAN-REPORT-SOURCE", TRAN_REPORT_SOURCE_OFFSET,
                    TRAN_REPORT_SOURCE_LENGTH);

    private static final FieldSpan DETAIL_FILLER_BEFORE_AMOUNT = FieldSpan.filler(
            DETAIL_FILLER_BEFORE_AMOUNT_OFFSET, DETAIL_FILLER_BEFORE_AMOUNT_LENGTH);

    /**
     * {@code TRAN-REPORT-AMT PIC -ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112 — numeric-edited, 15 bytes.
     */
    public static final FieldSpan TRAN_REPORT_AMT =
            FieldSpan.alphanumeric("TRAN-REPORT-AMT", TRAN_REPORT_AMT_OFFSET, AMOUNT_MASK_WIDTH);

    private static final FieldSpan DETAIL_TRAILING_FILLER =
            FieldSpan.filler(DETAIL_TRAILING_FILLER_OFFSET, DETAIL_TRAILING_FILLER_LENGTH);

    /**
     * {@code TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'} — an elementary {@code 01} item.
     */
    public static final FieldSpan TRANSACTION_HEADER_2 =
            FieldSpan.alphanumeric("TRANSACTION-HEADER-2", TRANSACTION_HEADER_2_OFFSET,
                            TRANSACTION_HEADER_2_LENGTH)
                    .withInitialValue(TRANSACTION_HEADER_2_IMAGE);

    /**
     * {@code REPT-PAGE-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112.
     */
    public static final FieldSpan REPT_PAGE_TOTAL =
            FieldSpan.alphanumeric("REPT-PAGE-TOTAL", REPT_PAGE_TOTAL_OFFSET, AMOUNT_MASK_WIDTH);

    /**
     * {@code REPT-ACCOUNT-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112.
     */
    public static final FieldSpan REPT_ACCOUNT_TOTAL = FieldSpan.alphanumeric("REPT-ACCOUNT-TOTAL",
            REPT_ACCOUNT_TOTAL_OFFSET, AMOUNT_MASK_WIDTH);

    /**
     * {@code REPT-GRAND-TOTAL PIC +ZZZ,ZZZ,ZZZ.ZZ}, columns 98-112.
     */
    public static final FieldSpan REPT_GRAND_TOTAL =
            FieldSpan.alphanumeric("REPT-GRAND-TOTAL", REPT_GRAND_TOTAL_OFFSET, AMOUNT_MASK_WIDTH);

    /**
     * {@code 01 REPORT-NAME-HEADER}, 115 bytes.
     */
    public static final RecordLayout REPORT_NAME_HEADER_LAYOUT =
            RecordLayout.of(REPORT_NAME_HEADER_LENGTH, REPT_SHORT_NAME, REPT_LONG_NAME,
                    REPT_DATE_HEADER, REPT_START_DATE, DATE_RANGE_SEPARATOR, REPT_END_DATE);

    /**
     * {@code 01 TRANSACTION-DETAIL-REPORT}, 114 bytes: eight named items and eight {@code FILLER}s.
     */
    public static final RecordLayout TRANSACTION_DETAIL_REPORT_LAYOUT = RecordLayout.of(
            TRANSACTION_DETAIL_REPORT_LENGTH, TRAN_REPORT_TRANS_ID, DETAIL_FILLER_AFTER_TRANS_ID,
            TRAN_REPORT_ACCOUNT_ID, DETAIL_FILLER_AFTER_ACCOUNT_ID, TRAN_REPORT_TYPE_CD,
            DETAIL_TYPE_SEPARATOR, TRAN_REPORT_TYPE_DESC, DETAIL_FILLER_AFTER_TYPE_DESC,
            TRAN_REPORT_CAT_CD, DETAIL_CAT_SEPARATOR, TRAN_REPORT_CAT_DESC,
            DETAIL_FILLER_AFTER_CAT_DESC, TRAN_REPORT_SOURCE, DETAIL_FILLER_BEFORE_AMOUNT,
            TRAN_REPORT_AMT, DETAIL_TRAILING_FILLER);

    /**
     * {@code 01 TRANSACTION-HEADER-1}, 114 bytes, every item a {@code FILLER}.
     */
    public static final RecordLayout TRANSACTION_HEADER_1_LAYOUT = RecordLayout.of(
            TRANSACTION_HEADER_1_LENGTH,
            FieldSpan.filler(HEADER_1_TRANSACTION_ID_OFFSET, HEADER_1_TRANSACTION_ID_LENGTH,
                    HEADER_1_TRANSACTION_ID_VALUE),
            FieldSpan.filler(HEADER_1_ACCOUNT_ID_OFFSET, HEADER_1_ACCOUNT_ID_LENGTH,
                    HEADER_1_ACCOUNT_ID_VALUE),
            FieldSpan.filler(HEADER_1_TRANSACTION_TYPE_OFFSET, HEADER_1_TRANSACTION_TYPE_LENGTH,
                    HEADER_1_TRANSACTION_TYPE_VALUE),
            FieldSpan.filler(HEADER_1_TRAN_CATEGORY_OFFSET, HEADER_1_TRAN_CATEGORY_LENGTH,
                    HEADER_1_TRAN_CATEGORY_VALUE),
            FieldSpan.filler(HEADER_1_TRAN_SOURCE_OFFSET, HEADER_1_TRAN_SOURCE_LENGTH,
                    HEADER_1_TRAN_SOURCE_VALUE),
            FieldSpan.filler(HEADER_1_GAP_OFFSET, HEADER_1_GAP_LENGTH),
            FieldSpan.filler(HEADER_1_AMOUNT_OFFSET, HEADER_1_AMOUNT_LENGTH,
                    HEADER_1_AMOUNT_VALUE));

    /**
     * {@code 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'}, 133 bytes.
     */
    public static final RecordLayout TRANSACTION_HEADER_2_LAYOUT =
            RecordLayout.of(TRANSACTION_HEADER_2_LENGTH, TRANSACTION_HEADER_2);

    /**
     * {@code 01 REPORT-PAGE-TOTALS}, 112 bytes: {@code 11 + 86 + 15}.
     */
    public static final RecordLayout REPORT_PAGE_TOTALS_LAYOUT = RecordLayout.of(
            REPORT_PAGE_TOTALS_LENGTH,
            FieldSpan.filler(PAGE_TOTAL_LABEL_OFFSET, PAGE_TOTAL_LABEL_LENGTH,
                    PAGE_TOTAL_LABEL_VALUE),
            FieldSpan.filler(PAGE_TOTAL_LEADER_OFFSET, PAGE_TOTAL_LEADER_LENGTH,
                    PAGE_TOTAL_LEADER_IMAGE),
            REPT_PAGE_TOTAL);

    /**
     * {@code 01 REPORT-ACCOUNT-TOTALS}, 112 bytes: {@code 13 + 84 + 15}.
     */
    public static final RecordLayout REPORT_ACCOUNT_TOTALS_LAYOUT = RecordLayout.of(
            REPORT_ACCOUNT_TOTALS_LENGTH,
            FieldSpan.filler(ACCOUNT_TOTAL_LABEL_OFFSET, ACCOUNT_TOTAL_LABEL_LENGTH,
                    ACCOUNT_TOTAL_LABEL_VALUE),
            FieldSpan.filler(ACCOUNT_TOTAL_LEADER_OFFSET, ACCOUNT_TOTAL_LEADER_LENGTH,
                    ACCOUNT_TOTAL_LEADER_IMAGE),
            REPT_ACCOUNT_TOTAL);

    /**
     * {@code 01 REPORT-GRAND-TOTALS}, 112 bytes: {@code 11 + 86 + 15}.
     */
    public static final RecordLayout REPORT_GRAND_TOTALS_LAYOUT = RecordLayout.of(
            REPORT_GRAND_TOTALS_LENGTH,
            FieldSpan.filler(GRAND_TOTAL_LABEL_OFFSET, GRAND_TOTAL_LABEL_LENGTH,
                    GRAND_TOTAL_LABEL_VALUE),
            FieldSpan.filler(GRAND_TOTAL_LEADER_OFFSET, GRAND_TOTAL_LEADER_LENGTH,
                    GRAND_TOTAL_LEADER_IMAGE),
            REPT_GRAND_TOTAL);

    private static final int SIGN_INDEX = 0;

    private static final int FIRST_INTEGER_DIGIT_INDEX = 1;

    private static final int DIGITS_PER_GROUP = 3;

    private static final int GROUP_STRIDE = DIGITS_PER_GROUP + 1;

    private static final int GROUP_SEPARATOR_COUNT = AMOUNT_INTEGER_DIGITS / DIGITS_PER_GROUP - 1;

    private static final int DECIMAL_POINT_INDEX =
            FIRST_INTEGER_DIGIT_INDEX + AMOUNT_INTEGER_DIGITS + GROUP_SEPARATOR_COUNT;

    private static final int FIRST_FRACTION_DIGIT_INDEX = DECIMAL_POINT_INDEX + 1;

    private static final int SENDING_DIGIT_COUNT = AMOUNT_INTEGER_DIGITS + AMOUNT_FRACTION_DIGITS;

    private static final char SPACE = ' ';

    private static final char ZERO_DIGIT = '0';

    private static final char GROUP_SEPARATOR = ',';

    private static final char DECIMAL_POINT = '.';

    private static final char MINUS_SIGN = '-';

    private static final char PLUS_SIGN = '+';

    private static final char DETAIL_POSITIVE_SIGN = SPACE;

    private static final char TOTAL_POSITIVE_SIGN = PLUS_SIGN;

    private static final String AMOUNT_ZERO_IMAGE = String.valueOf(SPACE).repeat(AMOUNT_MASK_WIDTH);

    static {
        verifyGeometry();
    }

    // Instance state: one record area per 01-level item, exactly as one COPY of CVTRA07Y gives CBTRN03C one
    // WORKING-STORAGE area apiece. Mutable, and therefore not thread-safe - give each report run its own
    // instance.

    private final FixedWidthCodec codec;

    private final FixedWidthRecord reportNameHeader;

    private final FixedWidthRecord transactionDetailReport;

    private final FixedWidthRecord transactionHeader1;

    private final FixedWidthRecord transactionHeader2;

    private final FixedWidthRecord reportPageTotals;

    private final FixedWidthRecord reportAccountTotals;

    private final FixedWidthRecord reportGrandTotals;

    /**
     * Allocates all seven record areas over the given charset, applying each item's declared {@code VALUE}
     * exactly as COBOL applies a {@code VALUE} clause on entry to {@code WORKING-STORAGE}.
     *
     * @param charset the charset the report's bytes are encoded in; must encode a space and a digit to a
     *     single byte each, which {@code US-ASCII} and {@code IBM037} both do
     * @throws NullPointerException if {@code charset} is {@code null}
     * @throws IllegalArgumentException if {@code charset} is not single-byte for digits and spaces
     */
    public TranReportLayouts(Charset charset) {
        this(new FixedWidthCodec(Objects.requireNonNull(charset,
                "A charset is required to render the transaction report; name US-ASCII or IBM037 "
                        + "explicitly rather than relying on a platform default")));
    }

    /**
     * Allocates all seven record areas using a codec the caller already holds.
     *
     * @param codec the fixed-width codec whose charset the report is encoded in
     * @throws NullPointerException if {@code codec} is {@code null}
     */
    public TranReportLayouts(FixedWidthCodec codec) {
        this.codec = Objects.requireNonNull(codec,
                "A FixedWidthCodec is required; it carries the explicitly chosen charset");
        this.reportNameHeader = codec.newRecord(REPORT_NAME_HEADER_LAYOUT);
        this.transactionDetailReport = codec.newRecord(TRANSACTION_DETAIL_REPORT_LAYOUT);
        this.transactionHeader1 = codec.newRecord(TRANSACTION_HEADER_1_LAYOUT);
        this.transactionHeader2 = codec.newRecord(TRANSACTION_HEADER_2_LAYOUT);
        this.reportPageTotals = codec.newRecord(REPORT_PAGE_TOTALS_LAYOUT);
        this.reportAccountTotals = codec.newRecord(REPORT_ACCOUNT_TOTALS_LAYOUT);
        this.reportGrandTotals = codec.newRecord(REPORT_GRAND_TOTALS_LAYOUT);
    }

    /**
     * The charset every record area of this instance is encoded in.
     *
     * @return the charset supplied at construction, never {@code null}
     */
    public Charset charset() {
        return codec.charset();
    }

    /**
     * {@code MOVE WS-START-DATE TO REPT-START-DATE} — {@code CBTRN03C:277}.
     *
     * @param startDate the report range's start date, at most 10 characters
     * @throws NullPointerException if {@code startDate} is {@code null}
     */
    public void moveReptStartDate(String startDate) {
        codec.writePicX(reportNameHeader, REPT_START_DATE, startDate);
    }

    /**
     * {@code MOVE WS-END-DATE TO REPT-END-DATE} — {@code CBTRN03C:278}.
     *
     * @param endDate the report range's end date, at most 10 characters
     * @throws NullPointerException if {@code endDate} is {@code null}
     */
    public void moveReptEndDate(String endDate) {
        codec.writePicX(reportNameHeader, REPT_END_DATE, endDate);
    }

    /**
     * {@code REPT-SHORT-NAME}, columns 1-38: {@code 'DALYREPT'} followed by 30 spaces.
     *
     * @return all 38 characters, untrimmed
     */
    public String reptShortName() {
        return codec.readPicX(reportNameHeader, REPT_SHORT_NAME);
    }

    /**
     * {@code REPT-LONG-NAME}, columns 39-79: {@code 'Daily Transaction Report'} followed by 17 spaces.
     *
     * @return all 41 characters, untrimmed
     */
    public String reptLongName() {
        return codec.readPicX(reportNameHeader, REPT_LONG_NAME);
    }

    /**
     * {@code REPT-DATE-HEADER}, columns 80-91: {@code 'Date Range: '}, all 12 bytes of it.
     *
     * @return all 12 characters, including the literal's trailing space
     */
    public String reptDateHeader() {
        return codec.readPicX(reportNameHeader, REPT_DATE_HEADER);
    }

    /**
     * {@code REPT-START-DATE}, columns 92-101.
     *
     * @return all 10 characters, untrimmed; spaces before the first {@link #moveReptStartDate(String)}
     */
    public String reptStartDate() {
        return codec.readPicX(reportNameHeader, REPT_START_DATE);
    }

    /**
     * {@code REPT-END-DATE}, columns 106-115.
     *
     * @return all 10 characters, untrimmed; spaces before the first {@link #moveReptEndDate(String)}
     */
    public String reptEndDate() {
        return codec.readPicX(reportNameHeader, REPT_END_DATE);
    }

    // The eight moves of CBTRN03C:363-370, each preserving the truncation direction its receiver's PICTURE
    // dictates, and the INITIALIZE of :362 that precedes them.

    /**
     * COBOL {@code INITIALIZE TRANSACTION-DETAIL-REPORT} — {@code CBTRN03C:362}, executed immediately
     * before the eight detail moves.
     */
    public void initializeTransactionDetailReport() {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TRANS_ID, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_ACCOUNT_ID, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_CD, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_DESC, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_CAT_DESC, "");
        codec.writePicX(transactionDetailReport, TRAN_REPORT_SOURCE, "");
        codec.writePic9(transactionDetailReport, TRAN_REPORT_CAT_CD, 0L);
        transactionDetailReport.writeSpan(TRAN_REPORT_AMT, AMOUNT_ZERO_IMAGE);
    }

    /**
     * {@code MOVE TRAN-ID TO TRAN-REPORT-TRANS-ID} — {@code CBTRN03C:363}.
     *
     * <p>{@code TRAN-ID} of {@code app/cpy/CVTRA05Y.cpy} is {@code PIC X(16)} and the receiver is
     * {@code PIC X(16)}, so the widths match and nothing is truncated.
     *
     * @param transactionId the transaction identifier, at most 16 characters
     * @throws NullPointerException if {@code transactionId} is {@code null}
     */
    public void moveTranReportTransId(String transactionId) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TRANS_ID, transactionId);
    }

    /**
     * {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID} — {@code CBTRN03C:364}.
     *
     * @param accountIdDigits the sender's {@code PIC 9(11)} digit image, at most 11 characters
     * @throws NullPointerException if {@code accountIdDigits} is {@code null}
     */
    public void moveTranReportAccountId(String accountIdDigits) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_ACCOUNT_ID, accountIdDigits);
    }

    /**
     * {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID} from a numeric value, in two explicit steps so
     * that both halves of the move are visible: first the sender's own {@code PIC 9(11)} image is formed by
     * zero-filling on the left, then those characters are moved into the {@code PIC X(11)} receiver
     * left-justified.
     *
     * @param accountId the account identifier; must not be negative, because {@code PIC 9(11)} is unsigned
     * @throws IllegalArgumentException if {@code accountId} is negative
     */
    public void moveTranReportAccountId(long accountId) {
        moveTranReportAccountId(codec.movePic9(accountId, TRAN_REPORT_ACCOUNT_ID_LENGTH));
    }

    /**
     * {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD} — {@code CBTRN03C:365}.
     *
     * @param transactionTypeCode the two-character transaction type code
     * @throws NullPointerException if {@code transactionTypeCode} is {@code null}
     */
    public void moveTranReportTypeCd(String transactionTypeCode) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_CD, transactionTypeCode);
    }

    /**
     * {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC} — {@code CBTRN03C:366}.
     *
     * @param transactionTypeDescription the type description, of any length
     * @throws NullPointerException if {@code transactionTypeDescription} is {@code null}
     */
    public void moveTranReportTypeDesc(String transactionTypeDescription) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_TYPE_DESC, transactionTypeDescription);
    }

    /**
     * {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD} — {@code CBTRN03C:367}.
     *
     * @param transactionCategoryCode the category code; must not be negative
     * @throws IllegalArgumentException if {@code transactionCategoryCode} is negative
     */
    public void moveTranReportCatCd(long transactionCategoryCode) {
        codec.writePic9(transactionDetailReport, TRAN_REPORT_CAT_CD, transactionCategoryCode);
    }

    /**
     * {@code MOVE TRAN-CAT-CD OF TRAN-RECORD TO TRAN-REPORT-CAT-CD} from the sender's digit image, which is
     * the natural form when the transaction record was read from a fixed-width dataset.
     *
     * @param transactionCategoryCodeDigits the sender's digits, non-empty and all digits
     * @throws NullPointerException if {@code transactionCategoryCodeDigits} is {@code null}
     * @throws IllegalArgumentException if it is empty or holds a non-digit
     */
    public void moveTranReportCatCd(String transactionCategoryCodeDigits) {
        codec.writePic9(transactionDetailReport, TRAN_REPORT_CAT_CD, transactionCategoryCodeDigits);
    }

    /**
     * {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC} — {@code CBTRN03C:368}.
     *
     * @param transactionCategoryDescription the category description, of any length
     * @throws NullPointerException if {@code transactionCategoryDescription} is {@code null}
     */
    public void moveTranReportCatDesc(String transactionCategoryDescription) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_CAT_DESC,
                transactionCategoryDescription);
    }

    /**
     * {@code MOVE TRAN-SOURCE TO TRAN-REPORT-SOURCE} — {@code CBTRN03C:369}.
     *
     * @param transactionSource the transaction source, at most 10 characters
     * @throws NullPointerException if {@code transactionSource} is {@code null}
     */
    public void moveTranReportSource(String transactionSource) {
        codec.writePicX(transactionDetailReport, TRAN_REPORT_SOURCE, transactionSource);
    }

    /**
     * {@code MOVE TRAN-AMT TO TRAN-REPORT-AMT} — {@code CBTRN03C:370}.
     *
     * @param transactionAmount the transaction amount; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code transactionAmount} is {@code null}
     */
    public void moveTranReportAmt(BigDecimal transactionAmount) {
        transactionDetailReport.writeSpan(TRAN_REPORT_AMT, editDetailAmount(transactionAmount));
    }

    /**
     * {@code TRAN-REPORT-TRANS-ID}, columns 1-16.
     *
     * @return all 16 characters, untrimmed
     */
    public String tranReportTransId() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_TRANS_ID);
    }

    /**
     * {@code TRAN-REPORT-ACCOUNT-ID}, columns 18-28.
     *
     * @return all 11 characters, untrimmed
     */
    public String tranReportAccountId() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_ACCOUNT_ID);
    }

    /**
     * {@code TRAN-REPORT-TYPE-CD}, columns 30-31.
     *
     * @return both characters, untrimmed
     */
    public String tranReportTypeCd() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_TYPE_CD);
    }

    /**
     * {@code TRAN-REPORT-TYPE-DESC}, columns 33-47.
     *
     * @return all 15 characters, untrimmed
     */
    public String tranReportTypeDesc() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_TYPE_DESC);
    }

    /**
     * {@code TRAN-REPORT-CAT-CD}, columns 49-52, as its four stored digit characters.
     *
     * @return exactly four digit characters
     */
    public String tranReportCatCd() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_CAT_CD);
    }

    /**
     * {@code TRAN-REPORT-CAT-CD} decoded as a number, for callers that need the value rather than the
     * image.
     *
     * @return the category code the four stored digits denote
     * @throws IllegalArgumentException if the span holds a non-digit, which would mean a corrupted record
     *     rather than a legitimate value
     */
    public int tranReportCatCdValue() {
        return codec.readPic9AsInt(transactionDetailReport, TRAN_REPORT_CAT_CD);
    }

    /**
     * {@code TRAN-REPORT-CAT-DESC}, columns 54-82.
     *
     * @return all 29 characters, untrimmed
     */
    public String tranReportCatDesc() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_CAT_DESC);
    }

    /**
     * {@code TRAN-REPORT-SOURCE}, columns 84-93.
     *
     * @return all 10 characters, untrimmed
     */
    public String tranReportSource() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_SOURCE);
    }

    /**
     * {@code TRAN-REPORT-AMT}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters; all spaces when the amount is zero
     */
    public String tranReportAmt() {
        return codec.readPicX(transactionDetailReport, TRAN_REPORT_AMT);
    }

    /**
     * {@code MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL} — {@code CBTRN03C:294}.
     *
     * @param pageTotal the accumulated page total; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code pageTotal} is {@code null}
     */
    public void moveReptPageTotal(BigDecimal pageTotal) {
        reportPageTotals.writeSpan(REPT_PAGE_TOTAL, editTotalAmount(pageTotal));
    }

    /**
     * {@code MOVE WS-ACCOUNT-TOTAL TO REPT-ACCOUNT-TOTAL} — {@code CBTRN03C:307}.
     *
     * @param accountTotal the accumulated account total; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code accountTotal} is {@code null}
     */
    public void moveReptAccountTotal(BigDecimal accountTotal) {
        reportAccountTotals.writeSpan(REPT_ACCOUNT_TOTAL, editTotalAmount(accountTotal));
    }

    /**
     * {@code MOVE WS-GRAND-TOTAL TO REPT-GRAND-TOTAL} — {@code CBTRN03C:319}.
     *
     * @param grandTotal the accumulated grand total; scaled and truncated to 2 decimal places
     * @throws NullPointerException if {@code grandTotal} is {@code null}
     */
    public void moveReptGrandTotal(BigDecimal grandTotal) {
        reportGrandTotals.writeSpan(REPT_GRAND_TOTAL, editTotalAmount(grandTotal));
    }

    /**
     * {@code REPT-PAGE-TOTAL}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters; all spaces before the first move, and all
     *     spaces again whenever the total is zero
     */
    public String reptPageTotal() {
        return codec.readPicX(reportPageTotals, REPT_PAGE_TOTAL);
    }

    /**
     * {@code REPT-ACCOUNT-TOTAL}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     */
    public String reptAccountTotal() {
        return codec.readPicX(reportAccountTotals, REPT_ACCOUNT_TOTAL);
    }

    /**
     * {@code REPT-GRAND-TOTAL}, columns 98-112, as its edited image.
     *
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     */
    public String reptGrandTotal() {
        return codec.readPicX(reportGrandTotals, REPT_GRAND_TOTAL);
    }

    // Every method here returns the layout at its NATURAL width and never pads to 133.
    // transaction.TranReportWriter owns that normalisation, reproducing MOVE &lt;layout&gt; TO
    // FD-REPTFILE-REC PIC X(133) (CBTRN03C:85).

    /**
     * {@code REPORT-NAME-HEADER} as its {@value #REPORT_NAME_HEADER_LENGTH}-character print image — the
     * layout written at {@code CBTRN03C:325}.
     *
     * @return exactly {@value #REPORT_NAME_HEADER_LENGTH} characters, not padded to 133
     */
    public String renderReportNameHeader() {
        return image(reportNameHeader);
    }

    /**
     * {@code TRANSACTION-DETAIL-REPORT} as its {@value #TRANSACTION_DETAIL_REPORT_LENGTH}-character print
     * image — the layout written at {@code CBTRN03C:371}.
     *
     * @return exactly {@value #TRANSACTION_DETAIL_REPORT_LENGTH} characters, not padded to 133
     */
    public String renderTransactionDetailReport() {
        return image(transactionDetailReport);
    }

    /**
     * {@code TRANSACTION-HEADER-1} as its {@value #TRANSACTION_HEADER_1_LENGTH}-character print image — the
     * layout written at {@code CBTRN03C:333}.
     *
     * @return exactly {@value #TRANSACTION_HEADER_1_LENGTH} characters, not padded to 133
     */
    public String renderTransactionHeader1() {
        return image(transactionHeader1);
    }

    /**
     * {@code TRANSACTION-HEADER-2} as its {@value #TRANSACTION_HEADER_2_LENGTH}-character print image — the
     * rule line written at {@code CBTRN03C:300}, {@code :312} and {@code :337}.
     *
     * @return exactly {@value #TRANSACTION_HEADER_2_LENGTH} hyphens
     */
    public String renderTransactionHeader2() {
        return image(transactionHeader2);
    }

    /**
     * {@code REPORT-PAGE-TOTALS} as its {@value #REPORT_PAGE_TOTALS_LENGTH}-character print image — the
     * layout written at {@code CBTRN03C:295}.
     *
     * @return exactly {@value #REPORT_PAGE_TOTALS_LENGTH} characters, not padded to 133
     */
    public String renderReportPageTotals() {
        return image(reportPageTotals);
    }

    /**
     * {@code REPORT-ACCOUNT-TOTALS} as its {@value #REPORT_ACCOUNT_TOTALS_LENGTH}-character print image —
     * the layout written at {@code CBTRN03C:308}.
     *
     * @return exactly {@value #REPORT_ACCOUNT_TOTALS_LENGTH} characters, not padded to 133
     */
    public String renderReportAccountTotals() {
        return image(reportAccountTotals);
    }

    /**
     * {@code REPORT-GRAND-TOTALS} as its {@value #REPORT_GRAND_TOTALS_LENGTH}-character print image — the
     * layout written at {@code CBTRN03C:320}.
     *
     * @return exactly {@value #REPORT_GRAND_TOTALS_LENGTH} characters, not padded to 133
     */
    public String renderReportGrandTotals() {
        return image(reportGrandTotals);
    }

    /**
     * {@code REPORT-NAME-HEADER} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_NAME_HEADER_LENGTH} bytes
     */
    public byte[] renderReportNameHeaderBytes() {
        return reportNameHeader.toByteArray();
    }

    /**
     * {@code TRANSACTION-DETAIL-REPORT} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #TRANSACTION_DETAIL_REPORT_LENGTH} bytes
     */
    public byte[] renderTransactionDetailReportBytes() {
        return transactionDetailReport.toByteArray();
    }

    /**
     * {@code TRANSACTION-HEADER-1} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #TRANSACTION_HEADER_1_LENGTH} bytes
     */
    public byte[] renderTransactionHeader1Bytes() {
        return transactionHeader1.toByteArray();
    }

    /**
     * {@code TRANSACTION-HEADER-2} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #TRANSACTION_HEADER_2_LENGTH} bytes
     */
    public byte[] renderTransactionHeader2Bytes() {
        return transactionHeader2.toByteArray();
    }

    /**
     * {@code REPORT-PAGE-TOTALS} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_PAGE_TOTALS_LENGTH} bytes
     */
    public byte[] renderReportPageTotalsBytes() {
        return reportPageTotals.toByteArray();
    }

    /**
     * {@code REPORT-ACCOUNT-TOTALS} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_ACCOUNT_TOTALS_LENGTH} bytes
     */
    public byte[] renderReportAccountTotalsBytes() {
        return reportAccountTotals.toByteArray();
    }

    /**
     * {@code REPORT-GRAND-TOTALS} as encoded bytes, in this instance's charset.
     *
     * @return a fresh array of exactly {@value #REPORT_GRAND_TOTALS_LENGTH} bytes
     */
    public byte[] renderReportGrandTotalsBytes() {
        return reportGrandTotals.toByteArray();
    }

    private static String image(FixedWidthRecord record) {
        return record.readString(0, record.recordLength());
    }

    /**
     * Edits a value through {@link #DETAIL_AMOUNT_MASK}, the mask of {@code TRAN-REPORT-AMT}.
     *
     * @param value the sending {@code PIC S9(09)V99} value
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String editDetailAmount(BigDecimal value) {
        return editNumeric(value, DETAIL_POSITIVE_SIGN);
    }

    /**
     * Edits a value through {@link #TOTAL_AMOUNT_MASK}, the mask of {@code REPT-PAGE-TOTAL},
     * {@code REPT-ACCOUNT-TOTAL} and {@code REPT-GRAND-TOTAL}.
     *
     * @param value the sending {@code PIC S9(09)V99} value
     * @return exactly {@value #AMOUNT_MASK_WIDTH} characters
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static String editTotalAmount(BigDecimal value) {
        return editNumeric(value, TOTAL_POSITIVE_SIGN);
    }

    private static String editNumeric(BigDecimal value, char positiveSign) {
        Objects.requireNonNull(value, "An amount is required to edit a numeric-edited field; COBOL "
                + "has no null and the sending field is PIC S9(09)V99");
        BigDecimal stored =
                CobolDecimal.storeAtPicture(value, AMOUNT_INTEGER_DIGITS, AMOUNT_FRACTION_DIGITS);
        if (stored.signum() == 0) {
            return AMOUNT_ZERO_IMAGE;
        }

        String digits = leftPadWithZeros(stored.abs().unscaledValue().toString(),
                SENDING_DIGIT_COUNT);
        int suppressUntil = suppressionBoundary(digits);

        char[] edited = new char[AMOUNT_MASK_WIDTH];
        edited[SIGN_INDEX] = stored.signum() < 0 ? MINUS_SIGN : positiveSign;
        for (int digit = 0; digit < AMOUNT_INTEGER_DIGITS; digit++) {
            int position = integerDigitMaskIndex(digit);
            edited[position] = position < suppressUntil ? SPACE : digits.charAt(digit);
        }
        for (int group = 0; group < GROUP_SEPARATOR_COUNT; group++) {
            int position = groupSeparatorMaskIndex(group);
            edited[position] = position < suppressUntil ? SPACE : GROUP_SEPARATOR;
        }
        edited[DECIMAL_POINT_INDEX] = DECIMAL_POINT;
        for (int fraction = 0; fraction < AMOUNT_FRACTION_DIGITS; fraction++) {
            edited[FIRST_FRACTION_DIGIT_INDEX + fraction] =
                    digits.charAt(AMOUNT_INTEGER_DIGITS + fraction);
        }
        return new String(edited);
    }

    private static int suppressionBoundary(String digits) {
        for (int digit = 0; digit < AMOUNT_INTEGER_DIGITS; digit++) {
            if (digits.charAt(digit) != ZERO_DIGIT) {
                return integerDigitMaskIndex(digit);
            }
        }
        return DECIMAL_POINT_INDEX;
    }

    private static int integerDigitMaskIndex(int digitIndex) {
        return FIRST_INTEGER_DIGIT_INDEX + digitIndex / DIGITS_PER_GROUP * GROUP_STRIDE
                + digitIndex % DIGITS_PER_GROUP;
    }

    private static int groupSeparatorMaskIndex(int group) {
        return integerDigitMaskIndex((group + 1) * DIGITS_PER_GROUP - 1) + 1;
    }

    private static String leftPadWithZeros(String digits, int width) {
        int missing = width - digits.length();
        return missing <= 0 ? digits : String.valueOf(ZERO_DIGIT).repeat(missing) + digits;
    }

    /**
     * Re-proves every geometric and literal invariant of {@code app/cpy/CVTRA07Y.cpy} against the constants
     * declared above.
     *
     * @throws IllegalStateException if any invariant does not hold
     */
    static void verifyGeometry() {
        verifyLayoutWidths();
        verifyAmountColumn();
        verifyMasks();
        verifyLiterals();
        verifyMaskIndexArithmetic();
    }

    private static void verifyLayoutWidths() {
        requireGeometry(REPORT_NAME_HEADER_LENGTH, 115, "REPORT-NAME-HEADER's transcribed width");
        requireGeometry(TRANSACTION_DETAIL_REPORT_LENGTH, 114,
                "TRANSACTION-DETAIL-REPORT's transcribed width");
        requireGeometry(TRANSACTION_HEADER_1_LENGTH, 114,
                "TRANSACTION-HEADER-1's transcribed width");
        requireGeometry(TRANSACTION_HEADER_2_LENGTH, 133,
                "TRANSACTION-HEADER-2's transcribed width");
        requireGeometry(REPORT_PAGE_TOTALS_LENGTH, 112, "REPORT-PAGE-TOTALS' transcribed width");
        requireGeometry(REPORT_ACCOUNT_TOTALS_LENGTH, 112,
                "REPORT-ACCOUNT-TOTALS' transcribed width");
        requireGeometry(REPORT_GRAND_TOTALS_LENGTH, 112, "REPORT-GRAND-TOTALS' transcribed width");

        requireGeometry(REPORT_NAME_HEADER_LAYOUT.recordLength(), REPORT_NAME_HEADER_LENGTH,
                "REPORT-NAME-HEADER's item widths must sum to its declared width");
        requireGeometry(TRANSACTION_DETAIL_REPORT_LAYOUT.recordLength(),
                TRANSACTION_DETAIL_REPORT_LENGTH,
                "TRANSACTION-DETAIL-REPORT's item widths must sum to its declared width");
        requireGeometry(TRANSACTION_HEADER_1_LAYOUT.recordLength(), TRANSACTION_HEADER_1_LENGTH,
                "TRANSACTION-HEADER-1's item widths must sum to its declared width");
        requireGeometry(TRANSACTION_HEADER_2_LAYOUT.recordLength(), TRANSACTION_HEADER_2_LENGTH,
                "TRANSACTION-HEADER-2's single item must span its declared width");
        requireGeometry(REPORT_PAGE_TOTALS_LAYOUT.recordLength(), REPORT_PAGE_TOTALS_LENGTH,
                "REPORT-PAGE-TOTALS' item widths must sum to its declared width");
        requireGeometry(REPORT_ACCOUNT_TOTALS_LAYOUT.recordLength(), REPORT_ACCOUNT_TOTALS_LENGTH,
                "REPORT-ACCOUNT-TOTALS' item widths must sum to its declared width");
        requireGeometry(REPORT_GRAND_TOTALS_LAYOUT.recordLength(), REPORT_GRAND_TOTALS_LENGTH,
                "REPORT-GRAND-TOTALS' item widths must sum to its declared width");

        requireGeometry(REPORT_NAME_HEADER_LAYOUT.storageSpans().size(), 6,
                "REPORT-NAME-HEADER declares 5 named items and 1 FILLER");
        requireGeometry(TRANSACTION_DETAIL_REPORT_LAYOUT.storageSpans().size(), 16,
                "TRANSACTION-DETAIL-REPORT declares 8 named items and 8 FILLERs - six carrying "
                        + "SPACES and two carrying '-'");
        requireGeometry(TRANSACTION_HEADER_1_LAYOUT.storageSpans().size(), 7,
                "TRANSACTION-HEADER-1 declares 7 items, every one a FILLER");
        requireGeometry(TRANSACTION_HEADER_2_LAYOUT.storageSpans().size(), 1,
                "TRANSACTION-HEADER-2 is an elementary item with no sub-items");
        requireGeometry(REPORT_PAGE_TOTALS_LAYOUT.storageSpans().size(), 3,
                "REPORT-PAGE-TOTALS declares a label FILLER, a leader FILLER and one named amount");
        requireGeometry(REPORT_ACCOUNT_TOTALS_LAYOUT.storageSpans().size(), 3,
                "REPORT-ACCOUNT-TOTALS declares a label FILLER, a leader FILLER and one amount");
        requireGeometry(REPORT_GRAND_TOTALS_LAYOUT.storageSpans().size(), 3,
                "REPORT-GRAND-TOTALS declares a label FILLER, a leader FILLER and one amount");
    }

    private static void verifyAmountColumn() {
        requireGeometry(AMOUNT_OFFSET, AMOUNT_COLUMN_START - 1,
                "the amount mask's 0-based offset, one less than its 1-based column 98");
        requireGeometry(AMOUNT_COLUMN_END, AMOUNT_COLUMN_START + AMOUNT_MASK_WIDTH - 1,
                "the amount mask's last 1-based column, that is 98 + 15 - 1");
        requireAmountColumn(TRAN_REPORT_AMT);
        requireAmountColumn(REPT_PAGE_TOTAL);
        requireAmountColumn(REPT_ACCOUNT_TOTAL);
        requireAmountColumn(REPT_GRAND_TOTAL);

        requireGeometry(PAGE_TOTAL_LEADER_LENGTH, 86,
                "REPORT-PAGE-TOTALS' dot leader, which pairs with an 11-byte label");
        requireGeometry(ACCOUNT_TOTAL_LEADER_LENGTH, 84,
                "REPORT-ACCOUNT-TOTALS' dot leader - two shorter than the other two, compensating "
                        + "its two-byte-wider label. Never normalise it to 86");
        requireGeometry(GRAND_TOTAL_LEADER_LENGTH, 86,
                "REPORT-GRAND-TOTALS' dot leader, which pairs with an 11-byte label");
        requireGeometry(PAGE_TOTAL_LABEL_LENGTH + PAGE_TOTAL_LEADER_LENGTH, AMOUNT_OFFSET,
                "REPORT-PAGE-TOTALS' label plus leader, which must reach column 98");
        requireGeometry(ACCOUNT_TOTAL_LABEL_LENGTH + ACCOUNT_TOTAL_LEADER_LENGTH, AMOUNT_OFFSET,
                "REPORT-ACCOUNT-TOTALS' label plus leader, which must reach column 98");
        requireGeometry(GRAND_TOTAL_LABEL_LENGTH + GRAND_TOTAL_LEADER_LENGTH, AMOUNT_OFFSET,
                "REPORT-GRAND-TOTALS' label plus leader, which must reach column 98");
        requireGeometry(isRepetitionOf(PAGE_TOTAL_LEADER_IMAGE, TOTAL_LEADER_CHARACTER,
                        PAGE_TOTAL_LEADER_LENGTH),
                "REPORT-PAGE-TOTALS' leader is 86 '.' characters and nothing else");
        requireGeometry(isRepetitionOf(ACCOUNT_TOTAL_LEADER_IMAGE, TOTAL_LEADER_CHARACTER,
                        ACCOUNT_TOTAL_LEADER_LENGTH),
                "REPORT-ACCOUNT-TOTALS' leader is 84 '.' characters and nothing else");
        requireGeometry(isRepetitionOf(GRAND_TOTAL_LEADER_IMAGE, TOTAL_LEADER_CHARACTER,
                        GRAND_TOTAL_LEADER_LENGTH),
                "REPORT-GRAND-TOTALS' leader is 86 '.' characters and nothing else");
    }

    private static void requireAmountColumn(FieldSpan span) {
        requireGeometry(span.offset(), AMOUNT_OFFSET,
                span.name() + "'s 0-based offset, which must be column 98");
        requireGeometry(span.length(), AMOUNT_MASK_WIDTH,
                span.name() + "'s width, which must be the mask's 15 bytes");
    }

    private static void verifyMasks() {
        requireGeometry(DETAIL_AMOUNT_MASK.length(), AMOUNT_MASK_WIDTH,
                "the length of '-ZZZ,ZZZ,ZZZ.ZZ'");
        requireGeometry(TOTAL_AMOUNT_MASK.length(), AMOUNT_MASK_WIDTH,
                "the length of '+ZZZ,ZZZ,ZZZ.ZZ'");
        requireGeometry(DETAIL_AMOUNT_MASK.charAt(SIGN_INDEX), MINUS_SIGN,
                "the detail mask's fixed insertion sign, in position 1");
        requireGeometry(TOTAL_AMOUNT_MASK.charAt(SIGN_INDEX), PLUS_SIGN,
                "the total mask's fixed insertion sign, in position 1");
        requireGeometry(DETAIL_POSITIVE_SIGN, SPACE,
                "what a '-' mask renders in position 1 for a positive value: a space");
        requireGeometry(TOTAL_POSITIVE_SIGN, PLUS_SIGN,
                "what a '+' mask renders in position 1 for a positive value: a plus");
        requireGeometry(DETAIL_AMOUNT_MASK.substring(SIGN_INDEX + 1),
                TOTAL_AMOUNT_MASK.substring(SIGN_INDEX + 1),
                "the two masks past their sign character, which are identical");
        requireGeometry(SENDING_DIGIT_COUNT, 11,
                "the digit positions a PIC S9(09)V99 sender contributes");
        requireGeometry(countOf(DETAIL_AMOUNT_MASK, 'Z'), SENDING_DIGIT_COUNT,
                "the count of Z suppression symbols - all 11 of them, which is why a zero value "
                        + "blanks the whole item");
        requireGeometry(GROUP_SEPARATOR_COUNT, 2, "the number of ',' insertion characters");
        requireGeometry(countOf(DETAIL_AMOUNT_MASK, GROUP_SEPARATOR), GROUP_SEPARATOR_COUNT,
                "the commas actually present in the mask");
        requireGeometry(DECIMAL_POINT_INDEX, 12, "the '.' insertion character's mask index");
        requireGeometry(DETAIL_AMOUNT_MASK.charAt(DECIMAL_POINT_INDEX), DECIMAL_POINT,
                "the character at the decimal point's mask index");
        requireGeometry(FIRST_FRACTION_DIGIT_INDEX, 13, "the first fractional slot's mask index");
        requireGeometry(isRepetitionOf(AMOUNT_ZERO_IMAGE, SPACE, AMOUNT_MASK_WIDTH),
                "the edited image of zero is 15 spaces, not 0.00 and not .00");
    }

    private static void verifyLiterals() {
        requireGeometry(REPT_SHORT_NAME_VALUE, "DALYREPT", "REPT-SHORT-NAME's VALUE literal");
        requireGeometry(REPT_SHORT_NAME_VALUE.length(), 8,
                "'DALYREPT' is 8 characters, right-padded into X(38)");
        requireGeometry(REPT_SHORT_NAME_LENGTH, 38, "REPT-SHORT-NAME's field width");
        requireGeometry(REPT_LONG_NAME_VALUE, "Daily Transaction Report",
                "REPT-LONG-NAME's VALUE literal");
        requireGeometry(REPT_LONG_NAME_VALUE.length(), 24,
                "'Daily Transaction Report' is 24 characters, right-padded into X(41)");
        requireGeometry(REPT_LONG_NAME_LENGTH, 41, "REPT-LONG-NAME's field width");
        requireGeometry(REPT_DATE_HEADER_VALUE, "Date Range: ",
                "REPT-DATE-HEADER's VALUE literal, trailing space included");
        requireGeometry(REPT_DATE_HEADER_VALUE.length(), REPT_DATE_HEADER_LENGTH,
                "'Date Range: ' exactly fills X(12), so its trailing space is data not padding");
        requireGeometry(REPT_DATE_HEADER_VALUE.charAt(REPT_DATE_HEADER_LENGTH - 1), SPACE,
                "the last character of 'Date Range: '");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE, " to ",
                "the date-range separator's VALUE literal, both spaces included");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE.length(), DATE_RANGE_SEPARATOR_LENGTH,
                "' to ' exactly fills X(04), so both of its spaces are data not padding");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE.charAt(0), SPACE,
                "the first character of ' to '");
        requireGeometry(DATE_RANGE_SEPARATOR_VALUE.charAt(DATE_RANGE_SEPARATOR_LENGTH - 1), SPACE,
                "the last character of ' to '");
        requireGeometry(DETAIL_SEPARATOR_VALUE.length(), 1,
                "each detail separator FILLER holds a single character");
        requireGeometry(DETAIL_SEPARATOR_VALUE.charAt(0), MINUS_SIGN,
                "the detail separator character, at columns 32 and 53");

        requireGeometry(HEADER_1_TRANSACTION_ID_VALUE.length(), 14,
                "'Transaction ID' is 14 characters in an X(17) field");
        requireGeometry(HEADER_1_ACCOUNT_ID_VALUE.length(), 10,
                "'Account ID' is 10 characters in an X(12) field");
        requireGeometry(HEADER_1_TRANSACTION_TYPE_VALUE.length(), 16,
                "'Transaction Type' is 16 characters in an X(19) field");
        requireGeometry(HEADER_1_TRAN_CATEGORY_VALUE.length(), 13,
                "'Tran Category' is 13 characters in an X(35) field");
        requireGeometry(HEADER_1_TRAN_SOURCE_VALUE.length(), 11,
                "'Tran Source' is 11 characters in an X(14) field");
        requireGeometry(HEADER_1_AMOUNT_VALUE.length(), 14,
                "'        Amount' is 14 characters in an X(16) field");
        requireGeometry(HEADER_1_AMOUNT_LEADING_SPACES, 8,
                "the transcribed leading-space count of the amount caption");
        requireGeometry(countLeadingSpaces(HEADER_1_AMOUNT_VALUE), HEADER_1_AMOUNT_LEADING_SPACES,
                "the leading spaces actually present in '        Amount'");
        requireGeometry(HEADER_1_AMOUNT_WORD_COLUMN_START, 107,
                "the transcribed 1-based column at which the word 'Amount' begins");
        requireGeometry(HEADER_1_AMOUNT_OFFSET + HEADER_1_AMOUNT_LEADING_SPACES + 1,
                HEADER_1_AMOUNT_WORD_COLUMN_START,
                "where 'Amount' actually lands - columns 107-112, ending on the mask's last column");

        requireGeometry(PAGE_TOTAL_LABEL_VALUE, "Page Total", "REPORT-PAGE-TOTALS' label literal");
        requireGeometry(PAGE_TOTAL_LABEL_VALUE.length(), 10,
                "'Page Total' is 10 characters in an X(11) field, leaving one trailing space");
        requireGeometry(ACCOUNT_TOTAL_LABEL_VALUE, "Account Total",
                "REPORT-ACCOUNT-TOTALS' label literal");
        requireGeometry(ACCOUNT_TOTAL_LABEL_VALUE.length(), ACCOUNT_TOTAL_LABEL_LENGTH,
                "'Account Total' exactly fills X(13), with no trailing space");
        requireGeometry(GRAND_TOTAL_LABEL_VALUE, "Grand Total",
                "REPORT-GRAND-TOTALS' label literal");
        requireGeometry(GRAND_TOTAL_LABEL_VALUE.length(), GRAND_TOTAL_LABEL_LENGTH,
                "'Grand Total' exactly fills X(11), with no trailing space");
        requireGeometry(TRANSACTION_HEADER_2_RULE_CHARACTER, MINUS_SIGN,
                "TRANSACTION-HEADER-2's ALL literal character");
        requireGeometry(isRepetitionOf(TRANSACTION_HEADER_2_IMAGE,
                        TRANSACTION_HEADER_2_RULE_CHARACTER, TRANSACTION_HEADER_2_LENGTH),
                "TRANSACTION-HEADER-2 is 133 hyphens and nothing else");
    }

    private static void verifyMaskIndexArithmetic() {
        requireGeometry(SIGN_INDEX, 0, "the sign's mask index");
        requireGeometry(FIRST_INTEGER_DIGIT_INDEX, 1, "the first integer digit slot's mask index");
        requireGeometry(DIGITS_PER_GROUP, 3, "the digit slots between one comma and the next");
        requireGeometry(GROUP_STRIDE, 4, "the mask positions one digit group plus its comma spans");
        requireGeometry(integerDigitMaskIndex(0), 1, "integer digit 1's mask index");
        requireGeometry(integerDigitMaskIndex(2), 3, "integer digit 3's mask index");
        requireGeometry(integerDigitMaskIndex(3), 5, "integer digit 4's mask index");
        requireGeometry(integerDigitMaskIndex(5), 7, "integer digit 6's mask index");
        requireGeometry(integerDigitMaskIndex(6), 9, "integer digit 7's mask index");
        requireGeometry(integerDigitMaskIndex(8), 11, "integer digit 9's mask index");
        requireGeometry(groupSeparatorMaskIndex(0), 4, "the first ',' insertion character's index");
        requireGeometry(groupSeparatorMaskIndex(1), 8, "the second ',' insertion character's index");
        for (int digit = 0; digit < AMOUNT_INTEGER_DIGITS; digit++) {
            int position = integerDigitMaskIndex(digit);
            requireGeometry(DETAIL_AMOUNT_MASK.charAt(position), 'Z',
                    "the mask character at index " + position + ", which must be a Z digit slot");
        }
    }

    static void requireGeometry(boolean satisfied, String description) {
        if (!satisfied) {
            throw new IllegalStateException(
                    "app/cpy/CVTRA07Y.cpy transcription check failed: " + description);
        }
    }

    static void requireGeometry(int actual, int expected, String description) {
        if (actual != expected) {
            throw new IllegalStateException("app/cpy/CVTRA07Y.cpy transcription check failed: "
                    + description + " must be " + expected + " but is " + actual);
        }
    }

    static void requireGeometry(char actual, char expected, String description) {
        if (actual != expected) {
            throw new IllegalStateException("app/cpy/CVTRA07Y.cpy transcription check failed: "
                    + description + " must be '" + expected + "' but is '" + actual + "'");
        }
    }

    static void requireGeometry(String actual, String expected, String description) {
        requireGeometry(expected.equals(actual), description + " must be \"" + expected
                + "\" but is \"" + actual + '"');
    }

    static boolean isRepetitionOf(String candidate, char character, int length) {
        if (candidate.length() != length) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (candidate.charAt(index) != character) {
                return false;
            }
        }
        return true;
    }

    static int countOf(String candidate, char character) {
        int occurrences = 0;
        for (int index = 0; index < candidate.length(); index++) {
            if (candidate.charAt(index) == character) {
                occurrences++;
            }
        }
        return occurrences;
    }

    static int countLeadingSpaces(String candidate) {
        int spaces = 0;
        while (spaces < candidate.length() && candidate.charAt(spaces) == SPACE) {
            spaces++;
        }
        return spaces;
    }
}
