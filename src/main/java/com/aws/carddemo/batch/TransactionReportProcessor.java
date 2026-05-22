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
package com.aws.carddemo.batch;

import com.aws.carddemo.entity.Transaction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Java migration of the COBOL batch program {@code CBTRN03C.cbl} (649
 * lines) — the transaction-detail report formatter.
 *
 * <h2>Source of Truth</h2>
 *
 * <p>{@code app/cbl/CBTRN03C.cbl} reads the TRANSACT master file plus
 * TRANCATG and TRANTYPE reference data, and emits a paginated report
 * to SYSOUT. Each page carries:
 * <ul>
 *   <li>A header (lines 1-4): title, run date, page number.</li>
 *   <li>The page-data rows (lines 5-58): one row per transaction with
 *       fixed-width columns for ID, type, category, amount, date.</li>
 *   <li>A trailer (line 66): page totals (count and amount).</li>
 * </ul>
 *
 * <p>The COBOL paginates at 66 lines per page (the standard 11-inch
 * green-bar mainframe form). The Java migration preserves this
 * pagination constant.
 *
 * <h2>Java Migration Shape</h2>
 *
 * <p>This class exposes pure-function seams for the unit tests:
 * <ul>
 *   <li>{@link #formatRecord(Transaction)} — formats one transaction
 *       as a fixed-width report line (COBOL DISPLAY → Java String).</li>
 *   <li>{@link #formatHeader(int, String)} — formats a page header
 *       given the page number and the run date.</li>
 *   <li>{@link #formatTrailer(int, BigDecimal)} — formats the
 *       per-page trailer (count + total amount).</li>
 *   <li>{@link #isPageBreak(int)} — returns {@code true} if the given
 *       line count requires a page break (every 66 lines = new page).</li>
 *   <li>{@link #accumulateTotal(BigDecimal, BigDecimal)} — adds a
 *       transaction amount to the running page total (preserves
 *       scale 2).</li>
 * </ul>
 *
 * @see com.aws.carddemo.batch.TransactionReportProcessorTest
 */
public class TransactionReportProcessor {

    /** Standard mainframe form size — 66 lines per page (11-inch green-bar). */
    public static final int LINES_PER_PAGE = 66;

    /** Number of header lines per page. */
    public static final int HEADER_LINES = 4;

    /** Number of trailer lines per page. */
    public static final int TRAILER_LINES = 1;

    /** Constructs a new processor. */
    public TransactionReportProcessor() {
        // No collaborators.
    }

    /**
     * Format one {@link Transaction} as a fixed-width report row.
     *
     * <p>Column layout (matches COBOL DISPLAY at lines 480-490 of
     * CBTRN03C.cbl):
     * <pre>
     *   Columns 1-16: TRAN-ID            (16 chars)
     *   Column  17  : (space)
     *   Columns 18-19: TRAN-TYPE-CD       (2 chars)
     *   Column  20  : (space)
     *   Columns 21-24: TRAN-CAT-CD        (4 chars)
     *   Column  25  : (space)
     *   Columns 26-37: TRAN-AMT           (12 chars, right-justified, sign first)
     *   Column  38  : (space)
     *   Columns 39-48: TRAN-DATE          (10 chars, from TRAN-ORIG-TS first 10)
     * </pre>
     *
     * @param transaction the transaction to format; must not be null
     * @return the fixed-width report row (47 chars, no newline)
     */
    public String formatRecord(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction must not be null");

        String id = padRight(nullSafe(transaction.getTransactionId()), 16);
        String type = padRight(nullSafe(transaction.getTransactionTypeCode()), 2);
        String cat = padRight(nullSafe(transaction.getTransactionCategoryCode()), 4);
        String amt = padLeft(formatAmount(transaction.getAmount()), 12);
        String date = padRight(extractDate(transaction.getOriginTimestamp()), 10);

        return id + " " + type + " " + cat + " " + amt + " " + date;
    }

    /**
     * Format a per-page header — Java equivalent of the COBOL
     * {@code WRITE REPT-PAGE-HEADER FROM PAGE-HEADER-REC} block.
     *
     * @param pageNumber 1-based page index
     * @param runDate    the run-date string (ISO YYYY-MM-DD)
     * @return a 4-line header string (newlines between lines, no trailing newline)
     */
    public String formatHeader(int pageNumber, String runDate) {
        Objects.requireNonNull(runDate, "runDate must not be null");
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1; got " + pageNumber);
        }
        return "TRANSACTION DETAIL REPORT" + "\n"
                + "Run date: " + runDate + "    Page: " + pageNumber + "\n"
                + "TRAN-ID          TYPE CAT  AMOUNT       DATE" + "\n"
                + "-------------------------------------------------";
    }

    /**
     * Format a per-page trailer — Java equivalent of the COBOL
     * {@code WRITE REPT-PAGE-TRAILER FROM PAGE-TRAILER-REC} block.
     *
     * @param recordCount number of transaction rows on this page
     * @param pageTotal   sum of transaction amounts on this page (scale 2)
     * @return the trailer string
     */
    public String formatTrailer(int recordCount, BigDecimal pageTotal) {
        Objects.requireNonNull(pageTotal, "pageTotal must not be null");
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount must be >= 0; got " + recordCount);
        }
        return "Page totals: " + recordCount + " records, "
                + pageTotal.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /**
     * Returns {@code true} when a page break is required — every
     * {@code LINES_PER_PAGE} lines (66 by default).
     *
     * @param lineCount the running line count (1-based)
     * @return {@code true} if {@code lineCount} is a multiple of LINES_PER_PAGE
     */
    public boolean isPageBreak(int lineCount) {
        if (lineCount < 0) {
            throw new IllegalArgumentException("lineCount must be >= 0; got " + lineCount);
        }
        return lineCount > 0 && lineCount % LINES_PER_PAGE == 0;
    }

    /**
     * Add one transaction amount to the running page total.
     *
     * @param runningTotal the existing total; must not be null
     * @param amount       the amount to add; must not be null
     * @return the new total at scale 2
     */
    public BigDecimal accumulateTotal(BigDecimal runningTotal, BigDecimal amount) {
        Objects.requireNonNull(runningTotal, "runningTotal must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        return runningTotal.add(amount).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String padRight(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(width);
        sb.append(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String padLeft(String value, int width) {
        if (value.length() >= width) {
            return value.substring(value.length() - width);
        }
        StringBuilder sb = new StringBuilder(width);
        while (sb.length() + value.length() < width) {
            sb.append(' ');
        }
        sb.append(value);
        return sb.toString();
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        return amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String extractDate(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return "";
        }
        return timestamp.substring(0, 10);
    }
}
