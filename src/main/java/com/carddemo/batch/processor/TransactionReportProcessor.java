/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.carddemo.batch.processor;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategory;
import com.carddemo.entity.TransactionCategoryId;
import com.carddemo.entity.TransactionType;
import com.carddemo.enums.TransactionTypeCode;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that renders the daily transaction detail
 * report, translating the COBOL batch program {@code CBTRN03C} (JCL
 * {@code app/jcl/TRANREPT.jcl}, report layout copybook {@code app/cpy/CVTRA07Y.cpy})
 * at source commit {@code 27d6c6f}.
 *
 * <p>The upstream reader supplies the date-windowed, card-number-ordered
 * {@link Transaction} rows (the migration of the legacy {@code SORT}
 * pre-step plus the {@code TRAN-PROC-TS(1:10)} {@code DATEPARM} window). For
 * every row this processor performs the three keyed enrichment reads of the
 * COBOL main loop, in the same order, and emits the fully formatted, fixed-width
 * {@code 133}-byte report lines:</p>
 *
 * <ul>
 *   <li>{@code 1500-A-LOOKUP-XREF} &rarr; {@link CardXrefRepository#findById(Object)}
 *       on {@code TRAN-CARD-NUM}, resolving the printed account id
 *       ({@code XREF-ACCT-ID}); performed only on a card-number control break and
 *       reused for the remaining rows of that card.</li>
 *   <li>{@code 1500-B-LOOKUP-TRANTYPE} &rarr;
 *       {@link TransactionTypeRepository#findById(Object)} on the two-character
 *       {@code TRAN-TYPE-CD}, resolving the {@code TRAN-TYPE-DESC} description.</li>
 *   <li>{@code 1500-C-LOOKUP-TRANCATG} &rarr;
 *       {@link TransactionCategoryRepository#findById(Object)} on the composite
 *       {@code TRAN-CAT-KEY} ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}),
 *       resolving the {@code TRAN-CAT-TYPE-DESC} description.</li>
 * </ul>
 *
 * <p>A missing reference record reproduces the COBOL {@code INVALID KEY} abend
 * ({@code CEE3ABD}) by raising an {@link IllegalStateException} that fails the
 * step, carrying the same diagnostic text the COBOL program displays.</p>
 *
 * <p><b>Output contract.</b> {@link #process(Transaction)} returns the ordered
 * list of report lines produced for that input row &mdash; any account-total
 * control-break block, any page-total/header block, and the detail line itself
 * &mdash; so a downstream {@code String} writer emits them verbatim. The
 * end-of-report total lines, which have no triggering input row, are obtained
 * once after the last item through {@link #getReportTrailerLines()} (wired by
 * {@code TransactionReportJobConfig}, for example through a writer footer
 * callback). Every returned line is exactly {@value #REPORT_RECORD_WIDTH}
 * characters, preserving the {@code FD-REPTFILE-REC PIC X(133)} interface
 * contract.</p>
 *
 * <p><b>Control breaks and paging.</b> A running line counter is incremented for
 * every physical line written (headers, details, totals, separators), exactly as
 * the COBOL {@code WS-LINE-COUNTER}. A page break ({@code WS-PAGE-SIZE = 20}) is
 * taken whenever that counter is a multiple of {@value #PAGE_SIZE} at the start of
 * a detail cycle: the page total is emitted, rolled into the grand total, and the
 * name/column headers are reprinted. A card-number change flushes the account
 * total. All monetary accumulators are {@link BigDecimal} with scale {@code 2}
 * and {@link RoundingMode#HALF_EVEN}; no binary floating point is used.</p>
 *
 * <p><b>State and scope.</b> Because Spring Batch presents items one at a time,
 * the control-break accumulators and counters are mutable instance fields. The
 * bean is {@link StepScope step-scoped} so each step execution receives a fresh
 * instance with its own state and job-parameter-resolved date range. The
 * processor is therefore <em>not</em> thread-safe and the owning step must run
 * single-threaded with the records in card-number order.</p>
 */
@Component
@StepScope
public class TransactionReportProcessor implements ItemProcessor<Transaction, List<String>> {

    /** Fixed output record width &mdash; COBOL {@code FD-REPTFILE-REC PIC X(133)}. */
    static final int REPORT_RECORD_WIDTH = 133;

    /** Lines per page before a page total and header reprint &mdash; {@code WS-PAGE-SIZE}. */
    static final int PAGE_SIZE = 20;

    // Detail line field widths (CVTRA07Y TRANSACTION-DETAIL-REPORT).
    private static final int TRANS_ID_WIDTH = 16;
    private static final int ACCOUNT_ID_WIDTH = 11;
    private static final int TYPE_CD_WIDTH = 2;
    private static final int TYPE_DESC_WIDTH = 15;
    private static final int CAT_CD_WIDTH = 4;
    private static final int CAT_DESC_WIDTH = 29;
    private static final int SOURCE_WIDTH = 10;

    // Column-header field widths (CVTRA07Y TRANSACTION-HEADER-1).
    private static final int HDR_TRANS_ID_WIDTH = 17;
    private static final int HDR_ACCOUNT_ID_WIDTH = 12;
    private static final int HDR_TRANS_TYPE_WIDTH = 19;
    private static final int HDR_TRAN_CATEGORY_WIDTH = 35;
    private static final int HDR_TRAN_SOURCE_WIDTH = 14;
    private static final int HDR_AMOUNT_WIDTH = 16;

    // Name-header field widths (CVTRA07Y REPORT-NAME-HEADER).
    private static final int NAME_SHORT_WIDTH = 38;
    private static final int NAME_LONG_WIDTH = 41;
    private static final int DATE_LABEL_WIDTH = 12;
    private static final int DATE_WIDTH = 10;

    // Totals-line label widths and dotted-leader lengths (CVTRA07Y REPORT-*-TOTALS).
    private static final int PAGE_LABEL_WIDTH = 11;
    private static final int PAGE_DOTS = 86;
    private static final int ACCOUNT_LABEL_WIDTH = 13;
    private static final int ACCOUNT_DOTS = 84;
    private static final int GRAND_LABEL_WIDTH = 11;
    private static final int GRAND_DOTS = 86;

    // Edited-amount geometry (PIC -ZZZ,ZZZ,ZZZ.ZZ and +ZZZ,ZZZ,ZZZ.ZZ).
    private static final int AMOUNT_INTEGER_WIDTH = 11;
    private static final int AMOUNT_SCALE = 2;
    private static final int AMOUNT_EDITED_WIDTH = 15;
    private static final char SIGN_NEGATIVE = '-';
    private static final char SIGN_DETAIL_NON_NEGATIVE = ' ';
    private static final char SIGN_TOTAL_NON_NEGATIVE = '+';

    // Literal text from CVTRA07Y, reproduced byte-for-byte.
    private static final String REPORT_SHORT_NAME = "DALYREPT";
    private static final String REPORT_LONG_NAME = "Daily Transaction Report";
    private static final String DATE_RANGE_LABEL = "Date Range: ";
    private static final String DATE_SEPARATOR = " to ";
    private static final String COL_TRANS_ID = "Transaction ID";
    private static final String COL_ACCOUNT_ID = "Account ID";
    private static final String COL_TRANS_TYPE = "Transaction Type";
    private static final String COL_TRAN_CATEGORY = "Tran Category";
    private static final String COL_TRAN_SOURCE = "Tran Source";
    private static final String COL_AMOUNT = "        Amount";
    private static final String PAGE_TOTAL_LABEL = "Page Total";
    private static final String ACCOUNT_TOTAL_LABEL = "Account Total";
    private static final String GRAND_TOTAL_LABEL = "Grand Total";
    private static final String SPACE = " ";
    private static final String DASH = "-";

    // Diagnostic text mirroring the COBOL INVALID KEY displays before abend.
    private static final String INVALID_CARD_MESSAGE = "INVALID CARD NUMBER : ";
    private static final String INVALID_TYPE_MESSAGE = "INVALID TRANSACTION TYPE : ";
    private static final String INVALID_CATEGORY_MESSAGE = "INVALID TRAN CATG KEY : ";

    private final CardXrefRepository cardXrefRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final TransactionCategoryRepository transactionCategoryRepository;
    private final String reportStartDate;
    private final String reportEndDate;

    /** Accumulated amount for the current page &mdash; {@code WS-PAGE-TOTAL}. */
    private BigDecimal pageTotal = BigDecimal.ZERO;

    /** Accumulated amount for the current account &mdash; {@code WS-ACCOUNT-TOTAL}. */
    private BigDecimal accountTotal = BigDecimal.ZERO;

    /** Accumulated amount for the whole report &mdash; {@code WS-GRAND-TOTAL}. */
    private BigDecimal grandTotal = BigDecimal.ZERO;

    /** Count of physical lines written so far &mdash; {@code WS-LINE-COUNTER}. */
    private long lineCounter;

    /** {@code true} until the first row is processed &mdash; {@code WS-FIRST-TIME}. */
    private boolean firstRow = true;

    /** Card number of the current control-break group &mdash; {@code WS-CURR-CARD-NUM}. */
    private String currentCardNum;

    /** Account id resolved for the current card &mdash; {@code XREF-ACCT-ID}. */
    private Long currentXrefAcctId;

    /**
     * Creates the processor for one step execution.
     *
     * @param cardXrefRepository            cross-reference repository for the
     *     {@code 1500-A} account-id lookup; must not be {@code null}
     * @param transactionTypeRepository     transaction-type repository for the
     *     {@code 1500-B} description lookup; must not be {@code null}
     * @param transactionCategoryRepository transaction-category repository for the
     *     {@code 1500-C} description lookup; must not be {@code null}
     * @param reportStartDate               the inclusive report window start date
     *     printed in the name header, bound from the {@code reportStartDate} job
     *     parameter (COBOL {@code DATEPARM} start); {@code null} renders as spaces
     * @param reportEndDate                 the inclusive report window end date
     *     printed in the name header, bound from the {@code reportEndDate} job
     *     parameter (COBOL {@code DATEPARM} end); {@code null} renders as spaces
     */
    public TransactionReportProcessor(
            CardXrefRepository cardXrefRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository,
            @Value("#{jobParameters['reportStartDate']}") String reportStartDate,
            @Value("#{jobParameters['reportEndDate']}") String reportEndDate) {
        this.cardXrefRepository =
                Objects.requireNonNull(cardXrefRepository, "cardXrefRepository must not be null");
        this.transactionTypeRepository =
                Objects.requireNonNull(transactionTypeRepository, "transactionTypeRepository must not be null");
        this.transactionCategoryRepository =
                Objects.requireNonNull(transactionCategoryRepository, "transactionCategoryRepository must not be null");
        this.reportStartDate = reportStartDate;
        this.reportEndDate = reportEndDate;
    }

    /**
     * Enriches one date-windowed transaction and returns the report lines it
     * produces, in emission order.
     *
     * <p>The returned list contains, in sequence: an account-total block when the
     * card number changes from the previous row (COBOL {@code 1120-WRITE-ACCOUNT-TOTALS});
     * the name/column header block on the first row and on each page break, with a
     * preceding page-total block on a page break (COBOL {@code 1100} header and
     * {@code 1110-WRITE-PAGE-TOTALS} logic); and finally the detail line for this
     * transaction (COBOL {@code 1120-WRITE-DETAIL}). The list is never {@code null}
     * and always contains at least the detail line, so no item is filtered.</p>
     *
     * @param transaction the in-window transaction to format; must not be {@code null}
     * @return the ordered, fixed-width report lines for this transaction
     * @throws IllegalStateException if a required cross-reference, transaction-type,
     *     or transaction-category record is absent (the COBOL {@code INVALID KEY} abend)
     */
    @Override
    public List<String> process(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");
        final List<String> lines = new ArrayList<>();

        final String cardNum = transaction.getCardNum();
        if (!Objects.equals(currentCardNum, cardNum)) {
            if (!firstRow) {
                lines.addAll(emitAccountTotals());
            }
            currentCardNum = cardNum;
            currentXrefAcctId = lookupAccountId(cardNum);
        }

        final TransactionTypeCode transactionType = transaction.getTransactionType();
        final String typeCode = (transactionType == null) ? null : transactionType.getCode();
        final String typeDesc = lookupTypeDescription(typeCode);
        final Integer categoryCode = transaction.getTranCatCd();
        final String categoryDesc = lookupCategoryDescription(typeCode, categoryCode);

        if (firstRow) {
            firstRow = false;
            lines.addAll(emitHeaders());
        }
        if (lineCounter % PAGE_SIZE == 0) {
            lines.addAll(emitPageTotals());
            lines.addAll(emitHeaders());
        }

        final BigDecimal amount =
                (transaction.getTranAmt() == null) ? BigDecimal.ZERO : transaction.getTranAmt();
        pageTotal = pageTotal.add(amount);
        accountTotal = accountTotal.add(amount);

        lines.add(buildDetailLine(transaction.getTranId(), currentXrefAcctId, typeCode, typeDesc,
                categoryCode, categoryDesc, transaction.getTranSource(), amount));
        lineCounter++;
        return lines;
    }

    /**
     * Returns the end-of-report total lines, to be written once after the last
     * input row has been processed.
     *
     * <p>The trailer flushes, in order, the final account total
     * ({@code REPORT-ACCOUNT-TOTALS}), the final page total
     * ({@code REPORT-PAGE-TOTALS}, which rolls the residual page amount into the
     * grand total), and the grand total ({@code REPORT-GRAND-TOTALS}). When no
     * in-window row was processed the report has no body and an empty list is
     * returned. This method mutates and consumes the control-break accumulators
     * and is intended to be invoked exactly once per step execution.</p>
     *
     * @return the ordered, fixed-width trailer lines, or an empty list when the
     *     report processed no rows
     */
    public List<String> getReportTrailerLines() {
        final List<String> lines = new ArrayList<>();
        if (firstRow) {
            return lines;
        }
        lines.addAll(emitAccountTotals());
        lines.addAll(emitPageTotals());
        lines.addAll(emitGrandTotals());
        return lines;
    }

    /**
     * Resolves the account id printed in the detail line from the card
     * cross-reference, reproducing {@code 1500-A-LOOKUP-XREF}.
     *
     * @param cardNum the transaction card number ({@code TRAN-CARD-NUM})
     * @return the owning account id ({@code XREF-ACCT-ID}), possibly {@code null}
     *     when the cross-reference record carries none
     * @throws IllegalStateException if no cross-reference record exists for the
     *     card number (the COBOL {@code INVALID KEY} abend)
     */
    private Long lookupAccountId(String cardNum) {
        if (cardNum == null) {
            throw new IllegalStateException(INVALID_CARD_MESSAGE + "null");
        }
        final CardXref xref = cardXrefRepository.findById(cardNum)
                .orElseThrow(() -> new IllegalStateException(INVALID_CARD_MESSAGE + cardNum));
        return xref.getXrefAcctId();
    }

    /**
     * Resolves the transaction-type description, reproducing
     * {@code 1500-B-LOOKUP-TRANTYPE}.
     *
     * @param typeCode the two-character transaction type code ({@code TRAN-TYPE-CD})
     * @return the type description ({@code TRAN-TYPE-DESC}), possibly {@code null}
     * @throws IllegalStateException if no transaction-type record exists for the
     *     code (the COBOL {@code INVALID KEY} abend)
     */
    private String lookupTypeDescription(String typeCode) {
        if (typeCode == null) {
            throw new IllegalStateException(INVALID_TYPE_MESSAGE + "null");
        }
        final TransactionType type = transactionTypeRepository.findById(typeCode)
                .orElseThrow(() -> new IllegalStateException(INVALID_TYPE_MESSAGE + typeCode));
        return type.getTranTypeDesc();
    }

    /**
     * Resolves the transaction-category description from the composite key,
     * reproducing {@code 1500-C-LOOKUP-TRANCATG}.
     *
     * @param typeCode     the two-character transaction type code ({@code TRAN-TYPE-CD})
     * @param categoryCode the four-digit transaction category code ({@code TRAN-CAT-CD})
     * @return the category description ({@code TRAN-CAT-TYPE-DESC}), possibly {@code null}
     * @throws IllegalStateException if no transaction-category record exists for the
     *     composite key (the COBOL {@code INVALID KEY} abend)
     */
    private String lookupCategoryDescription(String typeCode, Integer categoryCode) {
        if (typeCode == null || categoryCode == null) {
            throw new IllegalStateException(INVALID_CATEGORY_MESSAGE + typeCode + '/' + categoryCode);
        }
        final TransactionCategoryId key = new TransactionCategoryId(typeCode, categoryCode);
        final TransactionCategory category = transactionCategoryRepository.findById(key)
                .orElseThrow(() -> new IllegalStateException(
                        INVALID_CATEGORY_MESSAGE + typeCode + '/' + categoryCode));
        return category.getTranCatTypeDesc();
    }

    /**
     * Emits the four-line header block (name header, blank line, column header,
     * separator), reproducing {@code 1120-WRITE-HEADERS}, and advances the line
     * counter by four.
     *
     * @return the four header lines
     */
    private List<String> emitHeaders() {
        final List<String> lines = new ArrayList<>(4);
        lines.add(buildNameHeader());
        lineCounter++;
        lines.add(buildBlankLine());
        lineCounter++;
        lines.add(buildColumnHeader());
        lineCounter++;
        lines.add(buildSeparatorLine());
        lineCounter++;
        return lines;
    }

    /**
     * Emits the page-total line and trailing separator, rolls the page total into
     * the grand total, resets the page total, and advances the line counter by
     * two &mdash; reproducing {@code 1110-WRITE-PAGE-TOTALS}.
     *
     * @return the page-total line followed by a separator line
     */
    private List<String> emitPageTotals() {
        final List<String> lines = new ArrayList<>(2);
        lines.add(buildPageTotalLine());
        grandTotal = grandTotal.add(pageTotal);
        pageTotal = BigDecimal.ZERO;
        lineCounter++;
        lines.add(buildSeparatorLine());
        lineCounter++;
        return lines;
    }

    /**
     * Emits the account-total line and trailing separator, resets the account
     * total, and advances the line counter by two &mdash; reproducing
     * {@code 1120-WRITE-ACCOUNT-TOTALS}.
     *
     * @return the account-total line followed by a separator line
     */
    private List<String> emitAccountTotals() {
        final List<String> lines = new ArrayList<>(2);
        lines.add(buildAccountTotalLine());
        accountTotal = BigDecimal.ZERO;
        lineCounter++;
        lines.add(buildSeparatorLine());
        lineCounter++;
        return lines;
    }

    /**
     * Emits the grand-total line, reproducing {@code 1110-WRITE-GRAND-TOTALS}.
     * The COBOL paragraph does not advance the line counter, so neither does this.
     *
     * @return the single grand-total line
     */
    private List<String> emitGrandTotals() {
        final List<String> lines = new ArrayList<>(1);
        lines.add(buildGrandTotalLine());
        return lines;
    }

    /**
     * Builds the report name header ({@code REPORT-NAME-HEADER}) including the
     * inclusive date range.
     *
     * @return a 133-character name-header line
     */
    private String buildNameHeader() {
        final String content = padRight(REPORT_SHORT_NAME, NAME_SHORT_WIDTH)
                + padRight(REPORT_LONG_NAME, NAME_LONG_WIDTH)
                + padRight(DATE_RANGE_LABEL, DATE_LABEL_WIDTH)
                + padRight(reportStartDate, DATE_WIDTH)
                + DATE_SEPARATOR
                + padRight(reportEndDate, DATE_WIDTH);
        return toRecord(content);
    }

    /**
     * Builds the column header ({@code TRANSACTION-HEADER-1}).
     *
     * @return a 133-character column-header line
     */
    private String buildColumnHeader() {
        final String content = padRight(COL_TRANS_ID, HDR_TRANS_ID_WIDTH)
                + padRight(COL_ACCOUNT_ID, HDR_ACCOUNT_ID_WIDTH)
                + padRight(COL_TRANS_TYPE, HDR_TRANS_TYPE_WIDTH)
                + padRight(COL_TRAN_CATEGORY, HDR_TRAN_CATEGORY_WIDTH)
                + padRight(COL_TRAN_SOURCE, HDR_TRAN_SOURCE_WIDTH)
                + SPACE
                + padRight(COL_AMOUNT, HDR_AMOUNT_WIDTH);
        return toRecord(content);
    }

    /**
     * Builds the separator rule ({@code TRANSACTION-HEADER-2}): a full record of
     * hyphens.
     *
     * @return a line of {@value #REPORT_RECORD_WIDTH} hyphens
     */
    private String buildSeparatorLine() {
        return DASH.repeat(REPORT_RECORD_WIDTH);
    }

    /**
     * Builds the blank spacer line ({@code WS-BLANK-LINE}): a full record of
     * spaces.
     *
     * @return a line of {@value #REPORT_RECORD_WIDTH} spaces
     */
    private String buildBlankLine() {
        return SPACE.repeat(REPORT_RECORD_WIDTH);
    }

    /**
     * Builds a detail line ({@code TRANSACTION-DETAIL-REPORT}) from the
     * transaction and its enrichment values, preserving every {@code CVTRA07Y}
     * field width, filler, and edit mask.
     *
     * @param tranId       the transaction id ({@code TRAN-ID})
     * @param accountId    the enriched account id ({@code XREF-ACCT-ID})
     * @param typeCode     the transaction type code ({@code TRAN-TYPE-CD})
     * @param typeDesc     the enriched type description ({@code TRAN-TYPE-DESC})
     * @param categoryCode the transaction category code ({@code TRAN-CAT-CD})
     * @param categoryDesc the enriched category description ({@code TRAN-CAT-TYPE-DESC})
     * @param source       the transaction source ({@code TRAN-SOURCE})
     * @param amount       the transaction amount ({@code TRAN-AMT})
     * @return a 133-character detail line
     */
    private String buildDetailLine(String tranId, Long accountId, String typeCode, String typeDesc,
            Integer categoryCode, String categoryDesc, String source, BigDecimal amount) {
        final String content = padRight(tranId, TRANS_ID_WIDTH)
                + SPACE
                + formatAccountId(accountId)
                + SPACE
                + padRight(typeCode, TYPE_CD_WIDTH)
                + DASH
                + padRight(typeDesc, TYPE_DESC_WIDTH)
                + SPACE
                + formatCategoryCode(categoryCode)
                + DASH
                + padRight(categoryDesc, CAT_DESC_WIDTH)
                + SPACE
                + padRight(source, SOURCE_WIDTH)
                + SPACE.repeat(4)
                + formatAmount(amount, SIGN_DETAIL_NON_NEGATIVE)
                + SPACE.repeat(2);
        return toRecord(content);
    }

    /**
     * Builds the page-total line ({@code REPORT-PAGE-TOTALS}) from the current
     * page accumulator.
     *
     * @return a 133-character page-total line
     */
    private String buildPageTotalLine() {
        final String content = padRight(PAGE_TOTAL_LABEL, PAGE_LABEL_WIDTH)
                + ".".repeat(PAGE_DOTS)
                + formatAmount(pageTotal, SIGN_TOTAL_NON_NEGATIVE);
        return toRecord(content);
    }

    /**
     * Builds the account-total line ({@code REPORT-ACCOUNT-TOTALS}) from the
     * current account accumulator.
     *
     * @return a 133-character account-total line
     */
    private String buildAccountTotalLine() {
        final String content = padRight(ACCOUNT_TOTAL_LABEL, ACCOUNT_LABEL_WIDTH)
                + ".".repeat(ACCOUNT_DOTS)
                + formatAmount(accountTotal, SIGN_TOTAL_NON_NEGATIVE);
        return toRecord(content);
    }

    /**
     * Builds the grand-total line ({@code REPORT-GRAND-TOTALS}) from the current
     * grand accumulator.
     *
     * @return a 133-character grand-total line
     */
    private String buildGrandTotalLine() {
        final String content = padRight(GRAND_TOTAL_LABEL, GRAND_LABEL_WIDTH)
                + ".".repeat(GRAND_DOTS)
                + formatAmount(grandTotal, SIGN_TOTAL_NON_NEGATIVE);
        return toRecord(content);
    }

    /**
     * Formats the account id into the {@code TRAN-REPORT-ACCOUNT-ID X(11)} field,
     * zero-padded to eleven digits to mirror the COBOL {@code MOVE} of a
     * {@code PIC 9(11)} numeric into an alphanumeric field. A {@code null} id
     * (absent in the cross-reference) renders as spaces.
     *
     * @param value the account id, or {@code null}
     * @return an eleven-character field
     */
    private static String formatAccountId(Long value) {
        if (value == null) {
            return SPACE.repeat(ACCOUNT_ID_WIDTH);
        }
        return zeroPad(value, ACCOUNT_ID_WIDTH);
    }

    /**
     * Formats the category code into the {@code TRAN-REPORT-CAT-CD 9(04)} field,
     * zero-padded to four digits. A {@code null} code defaults to zero, matching
     * the COBOL numeric semantics.
     *
     * @param value the category code, or {@code null}
     * @return a four-character zero-padded field
     */
    private static String formatCategoryCode(Integer value) {
        final long code = (value == null) ? 0L : value.longValue();
        return zeroPad(code, CAT_CD_WIDTH);
    }

    /**
     * Renders a non-negative magnitude as a zero-padded decimal of exactly
     * {@code width} digits, keeping the rightmost digits on overflow (the COBOL
     * high-order truncation of a size error).
     *
     * @param value the value whose magnitude is rendered
     * @param width the exact field width in digits; positive
     * @return a {@code width}-character zero-padded numeric string
     */
    private static String zeroPad(long value, int width) {
        final String digits = Long.toString(Math.abs(value));
        if (digits.length() >= width) {
            return digits.substring(digits.length() - width);
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * Applies a COBOL numeric-edit mask of the form {@code s ZZZ,ZZZ,ZZZ.ZZ} to a
     * monetary value: a fixed leading sign, a comma-grouped and zero-suppressed
     * nine-digit integer part right-justified in eleven columns, a decimal point,
     * and two fraction digits. The value is first scaled to two fraction digits
     * with {@link RoundingMode#HALF_EVEN}.
     *
     * <p>Because every numeric position of the mask is a {@code Z} (zero
     * suppression), a value of exactly zero blanks the whole edited field &mdash;
     * sign, digits, grouping commas, and decimal point &mdash; to spaces, matching
     * the COBOL all-{@code Z} zero-suppression rule. For a non-zero value, leading
     * zeros and their grouping commas are suppressed to spaces up to the first
     * significant digit, the decimal point is always shown, and both fraction
     * digits are emitted.</p>
     *
     * @param value           the amount to edit; {@code null} is treated as zero
     * @param nonNegativeSign the sign character emitted for a non-negative value
     *     ({@code ' '} for the detail mask, {@code '+'} for the totals mask); a
     *     negative value always emits {@code '-'}
     * @return a fifteen-character edited amount
     */
    private static String formatAmount(BigDecimal value, char nonNegativeSign) {
        final BigDecimal scaled =
                (value == null ? BigDecimal.ZERO : value).setScale(AMOUNT_SCALE, RoundingMode.HALF_EVEN);
        if (scaled.signum() == 0) {
            return SPACE.repeat(AMOUNT_EDITED_WIDTH);
        }
        final char sign = (scaled.signum() < 0) ? SIGN_NEGATIVE : nonNegativeSign;
        final BigDecimal magnitude = scaled.abs();
        final BigInteger integerPart = magnitude.toBigInteger();
        final int fraction = magnitude.subtract(new BigDecimal(integerPart))
                .movePointRight(AMOUNT_SCALE)
                .intValueExact();
        final String integerField;
        if (integerPart.signum() == 0) {
            integerField = SPACE.repeat(AMOUNT_INTEGER_WIDTH);
        } else {
            String grouped = String.format(Locale.US, "%,d", integerPart);
            if (grouped.length() > AMOUNT_INTEGER_WIDTH) {
                grouped = grouped.substring(grouped.length() - AMOUNT_INTEGER_WIDTH);
            }
            integerField = leftPad(grouped, AMOUNT_INTEGER_WIDTH);
        }
        return sign + integerField + "." + String.format(Locale.US, "%02d", fraction);
    }

    /**
     * Coerces a value to exactly {@code width} characters by truncating or
     * right-padding with spaces &mdash; the byte-for-byte behaviour of a COBOL
     * {@code MOVE ... TO PIC X(width)}.
     *
     * @param value the value to coerce; may be {@code null} (treated as empty)
     * @param width the exact target width; positive
     * @return a left-justified string of exactly {@code width} characters
     */
    private static String padRight(String value, int width) {
        final String text = (value == null) ? "" : value;
        final int length = text.length();
        if (length == width) {
            return text;
        }
        if (length > width) {
            return text.substring(0, width);
        }
        return text + SPACE.repeat(width - length);
    }

    /**
     * Right-justifies a value within {@code width} characters by left-padding with
     * spaces, keeping the rightmost characters when the value is longer.
     *
     * @param value the value to justify; may be {@code null} (treated as empty)
     * @param width the exact target width; positive
     * @return a right-justified string of exactly {@code width} characters
     */
    private static String leftPad(String value, int width) {
        final String text = (value == null) ? "" : value;
        final int length = text.length();
        if (length == width) {
            return text;
        }
        if (length > width) {
            return text.substring(length - width);
        }
        return SPACE.repeat(width - length) + text;
    }

    /**
     * Coerces report-line content to the fixed {@value #REPORT_RECORD_WIDTH}-byte
     * record width, mirroring the COBOL {@code MOVE} of a working-storage report
     * group into {@code FD-REPTFILE-REC PIC X(133)}.
     *
     * @param content the assembled line content
     * @return a string of exactly {@value #REPORT_RECORD_WIDTH} characters
     */
    private static String toRecord(String content) {
        return padRight(content, REPORT_RECORD_WIDTH);
    }
}
