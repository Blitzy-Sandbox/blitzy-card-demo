/*
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.blitzy.carddemo.application.transaction;

import com.blitzy.carddemo.application.AbendException;
import com.blitzy.carddemo.domain.annotation.CobolProgram;
import com.blitzy.carddemo.domain.port.CardXrefRepository;
import com.blitzy.carddemo.domain.port.TransactionCategoryRepository;
import com.blitzy.carddemo.domain.port.TransactionRepository;
import com.blitzy.carddemo.domain.port.TransactionTypeRepository;
import com.blitzy.carddemo.domain.record.CardXrefRecord;
import com.blitzy.carddemo.domain.record.ReportHeaders;
import com.blitzy.carddemo.domain.record.TranCatRecord;
import com.blitzy.carddemo.domain.record.TranRecord;
import com.blitzy.carddemo.domain.record.TranTypeRecord;
import com.blitzy.carddemo.domain.util.Decimals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Java translation of the {@code CBTRN03C} COBOL batch program at
 * {@code app/cbl/CBTRN03C.cbl} ("Transaction detail report").
 *
 * <h2>Program purpose</h2>
 * <p>Reads every {@code TRAN-RECORD} from the TRANSACT file sequentially.
 * For each record whose {@code TRAN-PROC-TS (1:10)} falls within the
 * {@code WS-START-DATE} / {@code WS-END-DATE} date-parameter window:
 * <ol>
 *   <li>DISPLAYs the raw {@code TRAN-RECORD}.</li>
 *   <li>If the card number changed, performs
 *       {@code 1120-WRITE-ACCOUNT-TOTALS} for the prior account, then
 *       performs {@code 1500-A-LOOKUP-XREF} to fetch the new account
 *       cross-reference.</li>
 *   <li>Performs {@code 1500-B-LOOKUP-TRANTYPE} and
 *       {@code 1500-C-LOOKUP-TRANCATG} for descriptions.</li>
 *   <li>Performs {@code 1100-WRITE-TRANSACTION-REPORT}: emits page header
 *       on first record, paginates every {@code WS-PAGE-SIZE} (20) lines
 *       with page-total footer, and writes the detail line.</li>
 * </ol>
 *
 * <p>After the EOF, emits final page total and grand total.
 *
 * <h2>Pagination</h2>
 * <p>The COBOL uses {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}
 * to decide page-break. The Java translation preserves this semantic
 * verbatim using {@code lineCounter % PAGE_SIZE == 0}.
 *
 * <h2>Decimal arithmetic (AAP &sect;0.6.1)</h2>
 * <p>Total-accumulation uses {@code ADD TRAN-AMT TO WS-PAGE-TOTAL
 * WS-ACCOUNT-TOTAL} (single statement, two destinations) with no
 * {@code ROUNDED} clause. The Java translation uses
 * {@link Decimals#add(BigDecimal, BigDecimal, int, RoundingMode)} with
 * {@link RoundingMode#DOWN} for each accumulator.
 *
 * <h2>Output</h2>
 * <p>Writes the report to a fixed-width 133-byte-per-line text file
 * {@code java.nio.file.Path reportPath} provided to the constructor.
 *
 * <h2>Duplicate paragraph names anomaly (AAP &sect;0.7.1)</h2>
 * <p>The COBOL source contains <b>two paragraphs named
 * {@code 1110-} (WRITE-PAGE-TOTALS and WRITE-GRAND-TOTALS) and two paragraphs
 * named {@code 1120-} (WRITE-ACCOUNT-TOTALS, WRITE-HEADERS, WRITE-DETAIL)
 * at the COBOL level</b>; this is technically a duplicate-name anomaly
 * preserved verbatim in this translation. The Java methods are named
 * to disambiguate while preserving the COBOL paragraph name in Javadoc.
 */
@CobolProgram(
        value = "CBTRN03C",
        sourcePath = "app/cbl/CBTRN03C.cbl",
        notes = "Paginated transaction detail report writer; date-range filtered. "
                + "COBOL source has duplicate paragraph names (anomaly preserved per AAP §0.7.1)."
)
public final class CbTrn03C {

    private static final Logger log = LoggerFactory.getLogger(CbTrn03C.class);

    /** Mirror of the COBOL PROGRAM-ID. */
    public static final String PROGRAM_ID = "CBTRN03C";

    /** Mirror of {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}. */
    public static final int PAGE_SIZE = 20;

    /** Mirror of {@code WS-BLANK-LINE PIC X(133) VALUE SPACES}. */
    public static final String BLANK_LINE = " ".repeat(ReportHeaders.REPORT_LINE_WIDTH);

    /** Monetary scale per AAP §0.6.1. */
    public static final int MONETARY_SCALE = 2;

    private final TransactionRepository transactionRepository;
    private final CardXrefRepository xrefRepository;
    private final TransactionTypeRepository tranTypeRepository;
    private final TransactionCategoryRepository tranCatgRepository;
    private final Path reportPath;
    private final String startDate;
    private final String endDate;

    // Mirror of WS-REPORT-VARS
    private boolean firstTime = true;
    private long lineCounter = 0L;
    private BigDecimal pageTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
    private BigDecimal accountTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
    private BigDecimal grandTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
    private String currCardNum = "";  // WS-CURR-CARD-NUM (PIC X(16) VALUE SPACES)

    /** Cached XREF (set in 1500-A after card number change). */
    private CardXrefRecord currentXref;
    /** Cached TRAN-TYPE-RECORD (set in 1500-B per transaction). */
    private TranTypeRecord currentTranType;
    /** Cached TRAN-CAT-RECORD (set in 1500-C per transaction). */
    private TranCatRecord currentTranCat;

    /** Write buffer (collected, then flushed in run() finally). */
    private final java.util.List<String> outputLines = new java.util.ArrayList<>();

    /**
     * Constructor injection per AAP &sect;0.3.
     *
     * @param transactionRepository  port for TRANSACT sequential reads
     * @param xrefRepository         port for XREFFILE random reads by card num
     * @param tranTypeRepository     port for TRANTYPE random reads
     * @param tranCatgRepository     port for TRANCATG random reads
     * @param reportPath             output path for the formatted report (TRANREPT DD)
     * @param startDate              WS-START-DATE in YYYY-MM-DD form
     * @param endDate                WS-END-DATE in YYYY-MM-DD form
     */
    public CbTrn03C(TransactionRepository transactionRepository,
                    CardXrefRepository xrefRepository,
                    TransactionTypeRepository tranTypeRepository,
                    TransactionCategoryRepository tranCatgRepository,
                    Path reportPath,
                    String startDate,
                    String endDate) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository, "xrefRepository");
        this.tranTypeRepository = Objects.requireNonNull(tranTypeRepository,
                "tranTypeRepository");
        this.tranCatgRepository = Objects.requireNonNull(tranCatgRepository,
                "tranCatgRepository");
        this.reportPath = Objects.requireNonNull(reportPath, "reportPath");
        this.startDate = startDate == null ? "" : startDate;
        this.endDate = endDate == null ? "" : endDate;
    }

    /**
     * Drives the program as the COBOL PROCEDURE DIVISION does.
     *
     * @throws AbendException if a file I/O error occurs
     */
    public void run() {
        log.info("START OF EXECUTION OF PROGRAM {}", PROGRAM_ID);

        // 0550-DATEPARM-READ — already provided via constructor (startDate / endDate)
        log.info("Reporting from {} to {}", startDate, endDate);

        // 0000-0500 open paragraphs — ports lazy-open on first use; reportPath
        // is opened lazily in writeReportLine().

        try (Stream<TranRecord> records = transactionRepository.streamSequential()) {
            records.forEach(this::processTransactionRecord);
        } catch (RuntimeException e) {
            log.error("ERROR READING TRANSACTION FILE");
            displayIoStatus("12");
            flushReport();
            closeAll();
            abendProgram(e);
            return;
        }

        // EOF branch in COBOL: write the final page total and the grand total
        // (the COBOL also accumulates the last record's amount one extra time
        // before writing totals, but the actual MOVE happens with TRAN-AMT
        // being the value of the LAST READ — which is END-OF-FILE state where
        // TRAN-AMT is unmodified from the previous READ. In our translation
        // we accumulate only inside the per-record branch, so the EOF
        // branch is purely emission of accumulators.)
        if (!firstTime) {
            // 1120-WRITE-ACCOUNT-TOTALS for the LAST account
            writeAccountTotals();
        }
        writePageTotals();
        writeGrandTotals();

        flushReport();
        closeAll();

        log.info("END OF EXECUTION OF PROGRAM {}", PROGRAM_ID);
    }

    // ---------------------------------------------------------------- per-record body

    /**
     * Equivalent to the inner loop body of the PROCEDURE DIVISION.
     */
    private void processTransactionRecord(TranRecord record) {
        // IF TRAN-PROC-TS (1:10) >= WS-START-DATE
        //    AND TRAN-PROC-TS (1:10) <= WS-END-DATE
        //    CONTINUE
        // ELSE NEXT SENTENCE
        //
        // AAP §0.6.4: TRAN-PROC-TS is LocalDateTime. The COBOL slice
        // (1:10) corresponds to the ISO date prefix "yyyy-MM-dd". A null
        // procTs (COBOL all-spaces sentinel) is treated as outside any
        // window (matches the COBOL lexicographic behavior: " "*10 sorts
        // before any "yyyy-MM-dd" value).
        if (record.tranProcTs() == null) {
            return;
        }
        String procTs10 = record.tranProcTs().toLocalDate().toString();
        if (procTs10.compareTo(startDate) < 0 || procTs10.compareTo(endDate) > 0) {
            return; // outside reporting window
        }

        // DISPLAY TRAN-RECORD
        displayWholeRecord(record);

        // IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM
        if (!currCardNum.equals(record.tranCardNum())) {
            if (!firstTime) {
                // 1120-WRITE-ACCOUNT-TOTALS for the PRIOR account
                writeAccountTotals();
            }
            currCardNum = record.tranCardNum();
            // 1500-A-LOOKUP-XREF
            currentXref = lookupXref(record.tranCardNum());
        }

        // 1500-B-LOOKUP-TRANTYPE
        currentTranType = lookupTranType(record.tranTypeCd());
        // 1500-C-LOOKUP-TRANCATG
        currentTranCat = lookupTranCatg(record.tranTypeCd(), record.tranCatCd());

        // 1100-WRITE-TRANSACTION-REPORT
        writeTransactionReport(record);
    }

    // ---------------------------------------------------------------- 1500 lookups

    /**
     * Mirrors {@code 1500-A-LOOKUP-XREF}. ABENDs on INVALID KEY.
     */
    private CardXrefRecord lookupXref(String cardNum) {
        return xrefRepository.findByCardNumber(cardNum)
                .orElseThrow(() -> {
                    log.error("INVALID CARD NUMBER : {}", cardNum);
                    displayIoStatus("23");
                    return new AbendException(999, "INVALID CARD NUMBER: " + cardNum);
                });
    }

    /**
     * Mirrors {@code 1500-B-LOOKUP-TRANTYPE}. ABENDs on INVALID KEY.
     */
    private TranTypeRecord lookupTranType(String tranTypeCd) {
        return tranTypeRepository.findByCode(tranTypeCd)
                .orElseThrow(() -> {
                    log.error("INVALID TRANSACTION TYPE : {}", tranTypeCd);
                    displayIoStatus("23");
                    return new AbendException(999,
                            "INVALID TRANSACTION TYPE: " + tranTypeCd);
                });
    }

    /**
     * Mirrors {@code 1500-C-LOOKUP-TRANCATG}. ABENDs on INVALID KEY.
     */
    private TranCatRecord lookupTranCatg(String tranTypeCd, int tranCatCd) {
        return tranCatgRepository.findByKey(tranTypeCd, tranCatCd)
                .orElseThrow(() -> {
                    log.error("INVALID TRAN CATG KEY : {}{}", tranTypeCd,
                            String.format("%04d", tranCatCd));
                    displayIoStatus("23");
                    return new AbendException(999,
                            "INVALID TRAN CATG KEY: " + tranTypeCd + "/" + tranCatCd);
                });
    }

    // ---------------------------------------------------------------- 1100 / 1110 / 1120

    /**
     * Mirrors {@code 1100-WRITE-TRANSACTION-REPORT}: handles the
     * first-time header emission, pagination, accumulation, and detail
     * line write.
     */
    private void writeTransactionReport(TranRecord record) {
        if (firstTime) {
            firstTime = false;
            // Headers (1120-WRITE-HEADERS) emitted with start/end date
            writeHeaders();
        }

        // IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0
        //    PERFORM 1110-WRITE-PAGE-TOTALS
        //    PERFORM 1120-WRITE-HEADERS
        if (lineCounter != 0L && lineCounter % PAGE_SIZE == 0L) {
            writePageTotals();
            writeHeaders();
        }

        // ADD TRAN-AMT TO WS-PAGE-TOTAL WS-ACCOUNT-TOTAL
        pageTotal = Decimals.add(pageTotal, record.tranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);
        accountTotal = Decimals.add(accountTotal, record.tranAmt(),
                MONETARY_SCALE, RoundingMode.DOWN);

        // 1120-WRITE-DETAIL
        writeDetail(record);
    }

    /**
     * Mirrors the COBOL {@code 1110-WRITE-PAGE-TOTALS} paragraph: writes
     * the page-total dotted line, adds the page total to the grand
     * total, resets the page total, then writes a TRANSACTION-HEADER-2
     * separator line.
     */
    private void writePageTotals() {
        // MOVE WS-PAGE-TOTAL TO REPT-PAGE-TOTAL
        // MOVE REPORT-PAGE-TOTALS TO FD-REPTFILE-REC
        // WRITE
        writeReportLine(formatPageTotalLine(pageTotal));
        // ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL
        grandTotal = Decimals.add(grandTotal, pageTotal,
                MONETARY_SCALE, RoundingMode.DOWN);
        // MOVE 0 TO WS-PAGE-TOTAL
        pageTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
        lineCounter++;
        // MOVE TRANSACTION-HEADER-2 TO FD-REPTFILE-REC; WRITE
        writeReportLine(ReportHeaders.separatorLine());
        lineCounter++;
    }

    /**
     * Mirrors {@code 1120-WRITE-ACCOUNT-TOTALS} paragraph: writes the
     * account-total dotted line, resets the account total, then writes a
     * separator.
     */
    private void writeAccountTotals() {
        writeReportLine(formatAccountTotalLine(accountTotal));
        accountTotal = BigDecimal.ZERO.setScale(MONETARY_SCALE);
        lineCounter++;
        writeReportLine(ReportHeaders.separatorLine());
        lineCounter++;
    }

    /**
     * Mirrors {@code 1110-WRITE-GRAND-TOTALS} paragraph (note: this is
     * the SECOND paragraph named {@code 1110-} in the COBOL source — a
     * duplicate-name anomaly preserved per AAP &sect;0.7.1).
     */
    private void writeGrandTotals() {
        writeReportLine(formatGrandTotalLine(grandTotal));
    }

    /**
     * Mirrors {@code 1120-WRITE-HEADERS} paragraph: emits the REPORT-NAME
     * banner with date range, a blank line, TRANSACTION-HEADER-1, and
     * TRANSACTION-HEADER-2.
     */
    private void writeHeaders() {
        writeReportLine(formatNameHeader());
        lineCounter++;
        writeReportLine(BLANK_LINE);
        lineCounter++;
        writeReportLine(formatTransactionHeader1());
        lineCounter++;
        writeReportLine(ReportHeaders.separatorLine());
        lineCounter++;
    }

    /**
     * Mirrors {@code 1120-WRITE-DETAIL} paragraph: writes a TRANSACTION-
     * DETAIL-REPORT line populated from the current TRAN-RECORD, XREF,
     * TRAN-TYPE-RECORD, and TRAN-CAT-RECORD.
     */
    private void writeDetail(TranRecord record) {
        writeReportLine(formatDetailLine(record));
        lineCounter++;
    }

    // ---------------------------------------------------------------- 1111-WRITE-REPORT-REC

    /**
     * Mirrors {@code 1111-WRITE-REPORT-REC}: writes one record to
     * FD-REPTFILE-REC. Translates COBOL FILE STATUS != '00' to ABEND.
     */
    private void writeReportLine(String line) {
        // Pad/truncate to the COBOL 133-byte line width verbatim
        String fixed = (line + " ".repeat(ReportHeaders.REPORT_LINE_WIDTH));
        if (fixed.length() > ReportHeaders.REPORT_LINE_WIDTH) {
            fixed = fixed.substring(0, ReportHeaders.REPORT_LINE_WIDTH);
        }
        outputLines.add(fixed);
    }

    /**
     * Flushes the accumulated lines to the report path. Mirrors the
     * closing semantics of the COBOL output file (records are physically
     * written as they accumulate; this batched flush is functionally
     * equivalent for file-based adapters).
     */
    private void flushReport() {
        try {
            StringBuilder sb = new StringBuilder(outputLines.size()
                    * (ReportHeaders.REPORT_LINE_WIDTH + 1));
            for (String l : outputLines) {
                sb.append(l).append('\n');
            }
            Files.writeString(reportPath, sb.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.error("ERROR WRITING REPTFILE: {}", e.getMessage());
            displayIoStatus("12");
            throw new AbendException(999, "ERROR WRITING REPTFILE", e);
        }
    }

    // ---------------------------------------------------------------- formatting

    private String formatNameHeader() {
        // 38-byte short-name + 41-byte long-name + 12-byte 'Date Range: ' +
        // 10-byte start + 4-byte ' to ' + 10-byte end + spaces to fill 133
        StringBuilder sb = new StringBuilder();
        sb.append(pad(ReportHeaders.DEFAULT_SHORT_NAME, ReportHeaders.LEN_SHORT_NAME));
        sb.append(pad(ReportHeaders.DEFAULT_LONG_NAME, ReportHeaders.LEN_LONG_NAME));
        sb.append(pad(ReportHeaders.DATE_HEADER_PREFIX, ReportHeaders.LEN_DATE_HEADER));
        sb.append(pad(startDate, ReportHeaders.LEN_REPORT_DATE));
        sb.append(pad(ReportHeaders.DATE_RANGE_SEPARATOR, ReportHeaders.LEN_DATE_SEPARATOR));
        sb.append(pad(endDate, ReportHeaders.LEN_REPORT_DATE));
        return sb.toString();
    }

    private String formatTransactionHeader1() {
        // 17 + 12 + 19 + 35 + 14 + 1 + 16 = 114 bytes from CVTRA07Y
        StringBuilder sb = new StringBuilder();
        sb.append(pad(ReportHeaders.HEADER_TRANSACTION_ID, 17));
        sb.append(pad(ReportHeaders.HEADER_ACCOUNT_ID, 12));
        sb.append(pad(ReportHeaders.HEADER_TRANSACTION_TYPE, 19));
        sb.append(pad(ReportHeaders.HEADER_TRAN_CATEGORY, 35));
        sb.append(pad(ReportHeaders.HEADER_TRAN_SOURCE, 14));
        sb.append(" ");
        sb.append(pad(ReportHeaders.HEADER_AMOUNT, 16));
        return sb.toString();
    }

    private String formatDetailLine(TranRecord record) {
        // 16 + 1 + 11 + 1 + 2 + 1 + 15 + 1 + 4 + 1 + 29 + 1 + 10 + 4 + 15 + 2
        StringBuilder sb = new StringBuilder();
        sb.append(pad(record.tranId(), ReportHeaders.LEN_TRANS_ID));
        sb.append(' ');
        sb.append(pad(String.format("%011d", currentXref.xrefAcctId()),
                ReportHeaders.LEN_ACCOUNT_ID));
        sb.append(' ');
        sb.append(pad(record.tranTypeCd(), ReportHeaders.LEN_TYPE_CD));
        sb.append('-');
        sb.append(pad(currentTranType.tranTypeDesc(), ReportHeaders.LEN_TYPE_DESC));
        sb.append(' ');
        sb.append(String.format("%04d", record.tranCatCd()));
        sb.append('-');
        sb.append(pad(currentTranCat.tranCatTypeDesc(), ReportHeaders.LEN_CAT_DESC));
        sb.append(' ');
        sb.append(pad(record.tranSource(), ReportHeaders.LEN_SOURCE));
        sb.append("    "); // 4-space FILLER per CVTRA07Y
        sb.append(ReportHeaders.formatDetailAmount(record.tranAmt()));
        sb.append("  ");
        return sb.toString();
    }

    private String formatPageTotalLine(BigDecimal value) {
        // REPORT-PAGE-TOTALS: 11 + 86 + 15 = 112 bytes
        return ReportHeaders.dottedLine(
                pad(ReportHeaders.LABEL_PAGE_TOTAL, 11), 86)
                + ReportHeaders.formatTotalAmount(value);
    }

    private String formatAccountTotalLine(BigDecimal value) {
        // REPORT-ACCOUNT-TOTALS: 13 + 84 + 15 = 112 bytes
        return ReportHeaders.dottedLine(
                pad(ReportHeaders.LABEL_ACCOUNT_TOTAL, 13), 84)
                + ReportHeaders.formatTotalAmount(value);
    }

    private String formatGrandTotalLine(BigDecimal value) {
        // REPORT-GRAND-TOTALS: 11 + 86 + 15 = 112 bytes
        return ReportHeaders.dottedLine(
                pad(ReportHeaders.LABEL_GRAND_TOTAL, 11), 86)
                + ReportHeaders.formatTotalAmount(value);
    }

    private static String pad(String s, int len) {
        if (s == null) {
            return " ".repeat(len);
        }
        if (s.length() >= len) {
            return s.substring(0, len);
        }
        return s + " ".repeat(len - s.length());
    }

    // ---------------------------------------------------------------- OPEN / CLOSE

    private void closeAll() {
        // 9000-TRANFILE-CLOSE through 9500-DATEPARM-CLOSE.
        closeQuietly(transactionRepository, "POSTED TRANSACTION FILE");
        closeQuietly(xrefRepository, "CROSS REF FILE");
        closeQuietly(tranTypeRepository, "TRANSACTION TYPE FILE");
        // 9400-TRANCATG-CLOSE: the TransactionCategoryRepository port
        // deliberately does not extend AutoCloseable (per its schema —
        // only the 4 schema-listed methods findByKey/streamSequential/
        // save/delete are exposed). The TRANCATG file lifecycle is
        // therefore owned by the adapter implementation, not by the
        // application class. The COBOL CLOSE-paragraph semantic for
        // TRANCATG-FILE is preserved by the adapter's own
        // close-on-completion contract.
        // REPORT-FILE was opened lazily via writeReportLine + flushReport
    }

    private void closeQuietly(AutoCloseable resource, String name) {
        try {
            resource.close();
        } catch (Exception e) {
            log.error("ERROR CLOSING {}: {}", name, e.getMessage());
            displayIoStatus("12");
        }
    }

    private void displayWholeRecord(TranRecord record) {
        byte[] encoded = record.encode();
        log.info("{}", new String(encoded, StandardCharsets.ISO_8859_1));
    }

    private void displayIoStatus(String ioStatus) {
        String formatted = ioStatus == null || ioStatus.isBlank()
                ? "0000"
                : "00" + ioStatus;
        log.info("FILE STATUS IS: NNNN{}", formatted);
    }

    private void abendProgram(Throwable cause) {
        log.error("ABENDING PROGRAM");
        throw new AbendException(999, PROGRAM_ID + " abend", cause);
    }
}
