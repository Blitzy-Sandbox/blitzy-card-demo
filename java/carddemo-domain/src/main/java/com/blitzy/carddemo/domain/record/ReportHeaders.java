/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.domain.record;

import com.blitzy.carddemo.domain.annotation.CobolProgram;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reporting constants and edit masks translated from
 * {@code app/cpy/CVTRA07Y.cpy}. These define the fixed-format banner lines,
 * detail line layouts, and totals lines used by CBTRN03C (Transaction Report).
 *
 * <p>Unlike fixed-record domain types, this class holds report-formatting
 * scaffolding rather than a single fixed-width record.
 *
 * <pre>{@code
 * 01 REPORT-NAME-HEADER.
 *    05 REPT-SHORT-NAME       PIC X(38) VALUE 'DALYREPT'.
 *    05 REPT-LONG-NAME        PIC X(41) VALUE 'Daily Transaction Report'.
 *    05 REPT-DATE-HEADER      PIC X(12) VALUE 'Date Range: '.
 *    05 REPT-START-DATE       PIC X(10) VALUE SPACES.
 *    05 FILLER                PIC X(04) VALUE ' to '.
 *    05 REPT-END-DATE         PIC X(10) VALUE SPACES.
 *
 * 01 TRANSACTION-HEADER-1 ...
 * 01 TRANSACTION-HEADER-2 PIC X(133) VALUE ALL '-'.
 * 01 REPORT-PAGE-TOTALS ...
 * 01 REPORT-ACCOUNT-TOTALS ...
 * 01 REPORT-GRAND-TOTALS ...
 * }</pre>
 *
 * @see com.blitzy.carddemo.domain.util.Decimals#formatEditMask
 */
@CobolProgram(
        value = "CVTRA07Y",
        sourcePath = "app/cpy/CVTRA07Y.cpy",
        notes = "Report header constants and edit masks for transaction reports (CBTRN03C)"
)
public final class ReportHeaders {

    public static final int REPORT_LINE_WIDTH = 133;

    // REPT-SHORT-NAME / REPT-LONG-NAME defaults (CVTRA07Y values)
    public static final String DEFAULT_SHORT_NAME = "DALYREPT";
    public static final String DEFAULT_LONG_NAME = "Daily Transaction Report";
    public static final String DATE_HEADER_PREFIX = "Date Range: ";
    public static final String DATE_RANGE_SEPARATOR = " to ";

    // Field widths from REPORT-NAME-HEADER 01-level
    public static final int LEN_SHORT_NAME = 38;
    public static final int LEN_LONG_NAME = 41;
    public static final int LEN_DATE_HEADER = 12;
    public static final int LEN_REPORT_DATE = 10;
    public static final int LEN_DATE_SEPARATOR = 4;

    // TRANSACTION-DETAIL-REPORT field widths (per CVTRA07Y line layout)
    public static final int LEN_TRANS_ID = 16;
    public static final int LEN_ACCOUNT_ID = 11;
    public static final int LEN_TYPE_CD = 2;
    public static final int LEN_TYPE_DESC = 15;
    public static final int LEN_CAT_CD = 4;
    public static final int LEN_CAT_DESC = 29;
    public static final int LEN_SOURCE = 10;

    // Edit-mask templates per CVTRA07Y
    public static final String AMOUNT_EDIT_MASK_DETAIL = "-ZZZ,ZZZ,ZZZ.ZZ";   // TRAN-REPORT-AMT
    public static final String AMOUNT_EDIT_MASK_TOTAL = "+ZZZ,ZZZ,ZZZ.ZZ";    // REPT-PAGE-TOTAL etc.

    // Fixed banner labels
    public static final String LABEL_PAGE_TOTAL = "Page Total";
    public static final String LABEL_ACCOUNT_TOTAL = "Account Total";
    public static final String LABEL_GRAND_TOTAL = "Grand Total";

    // Header titles (column captions)
    public static final String HEADER_TRANSACTION_ID = "Transaction ID";
    public static final String HEADER_ACCOUNT_ID = "Account ID";
    public static final String HEADER_TRANSACTION_TYPE = "Transaction Type";
    public static final String HEADER_TRAN_CATEGORY = "Tran Category";
    public static final String HEADER_TRAN_SOURCE = "Tran Source";
    public static final String HEADER_AMOUNT = "        Amount";

    private ReportHeaders() {
        // Utility class
    }

    /** Builds the standard banner line (133 dashes). */
    public static String separatorLine() {
        return "-".repeat(REPORT_LINE_WIDTH);
    }

    /** Builds the "Page Total" / "Account Total" / "Grand Total" dotted lines. */
    public static String dottedLine(String label, int totalDots) {
        if (totalDots < 0) {
            throw new IllegalArgumentException("totalDots must be non-negative");
        }
        return label + ".".repeat(totalDots);
    }

    /**
     * Formats a monetary value using the negative-allowed detail edit mask:
     * {@code -ZZZ,ZZZ,ZZZ.ZZ}.
     */
    public static String formatDetailAmount(BigDecimal value) {
        return formatEditedAmount(value, false);
    }

    /**
     * Formats a monetary value using the positive-prefixed total edit mask:
     * {@code +ZZZ,ZZZ,ZZZ.ZZ}.
     */
    public static String formatTotalAmount(BigDecimal value) {
        return formatEditedAmount(value, true);
    }

    private static String formatEditedAmount(BigDecimal value, boolean showPositive) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_EVEN);
        boolean negative = scaled.signum() < 0;
        BigDecimal abs = scaled.abs();
        long whole = abs.toBigInteger().longValueExact();
        int cents = abs.subtract(BigDecimal.valueOf(whole)).movePointRight(2).intValueExact();

        StringBuilder sb = new StringBuilder();
        if (whole >= 1_000_000_000L) {
            // Per CVTRA07Y edit mask, three groups of 3 digits
            sb.append(whole / 1_000_000_000L).append(',');
            whole %= 1_000_000_000L;
            sb.append(String.format("%03d", whole / 1_000_000L)).append(',');
            whole %= 1_000_000L;
            sb.append(String.format("%03d", whole / 1_000L)).append(',');
            sb.append(String.format("%03d", whole % 1_000L));
        } else if (whole >= 1_000_000L) {
            sb.append(whole / 1_000_000L).append(',');
            whole %= 1_000_000L;
            sb.append(String.format("%03d", whole / 1_000L)).append(',');
            sb.append(String.format("%03d", whole % 1_000L));
        } else if (whole >= 1_000L) {
            sb.append(whole / 1_000L).append(',');
            sb.append(String.format("%03d", whole % 1_000L));
        } else {
            sb.append(whole);
        }
        sb.append('.').append(String.format("%02d", cents));

        if (negative) {
            return '-' + sb.toString();
        }
        return (showPositive ? "+" : " ") + sb.toString();
    }
}
