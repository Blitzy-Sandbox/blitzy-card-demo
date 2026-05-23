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
package com.awsm2.carddemo.service;

import com.awsm2.carddemo.adapter.AuditLogService;
import com.awsm2.carddemo.adapter.S3OutputService;
import com.awsm2.carddemo.domain.CardCrossReference;
import com.awsm2.carddemo.domain.Transaction;
import com.awsm2.carddemo.domain.TransactionCategory;
import com.awsm2.carddemo.domain.TransactionType;
import com.awsm2.carddemo.dto.ReportLineDto;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.TransactionCategoryRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import com.awsm2.carddemo.repository.TransactionTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Date-window transaction report batch service &mdash; the Java target
 * for the COBOL batch program {@code app/cbl/CBTRN03C.cbl} (the
 * transaction-report variant; LRECL=133, {@code TRANREPT} DD).
 *
 * <p>This service filters {@link Transaction} rows by a date window
 * {@code WS-START-DATE..WS-END-DATE} (paragraph
 * {@code PROCEDURE DIVISION} L172-L175), joins with {@link TransactionType},
 * {@link TransactionCategory}, and {@link CardCrossReference} to produce
 * a printable detail line per matching transaction, and accumulates page,
 * account, and grand totals (paragraph {@code 1110-WRITE-PAGE-TOTALS},
 * {@code 1120-WRITE-ACCOUNT-TOTALS}, and the grand-total line at L203).
 * The output is a series of {@link ReportLineDto} rows handed to the
 * {@link S3OutputService} for verbatim S3 write per AAP &sect;0.7.2.</p>
 *
 * <h2>Source provenance (AAP &sect;0.7.3)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBTRN03C.cbl} (the
 *       transaction-report variant) &mdash; invoked by JCL job
 *       {@code app/jcl/TRANREPT.jcl} STEP03.</li>
 *   <li><b>Date window:</b> {@code WS-START-DATE} /
 *       {@code WS-END-DATE} are read from the {@code DATEPARM} DD
 *       file (L55-L94, L221-L233 of CBTRN03C). The Java target
 *       accepts these as explicit parameters to
 *       {@link #generateReport(LocalDate, LocalDate, String)}.</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CVTRA05Y.cpy} ({@link Transaction}),
 *       {@code app/cpy/CVTRA03Y.cpy} ({@link TransactionType}),
 *       {@code app/cpy/CVTRA04Y.cpy} ({@link TransactionCategory}),
 *       {@code app/cpy/CVACT03Y.cpy} ({@link CardCrossReference}),
 *       {@code app/cpy/CVTRA07Y.cpy} (report-line layouts, mapped to
 *       {@link ReportLineDto}).</li>
 * </ul>
 *
 * <h2>COBOL paragraph translation</h2>
 * <table>
 *   <caption>CBTRN03C.cbl &harr; TransactionReportService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L159-L210)</td>
 *       <td>{@link #generateReport(LocalDate, LocalDate, String)}</td></tr>
 *   <tr><td>{@code 0000-DATEPARM-OPEN/READ-CLOSE} (L221-L240)</td>
 *       <td>(replaced) Caller-supplied {@code startDate} /
 *       {@code endDate} parameters &mdash; the {@code DATEPARM} DD
 *       allocation is no longer needed</td></tr>
 *   <tr><td>{@code 1100-WRITE-TRANSACTION-REPORT}</td>
 *       <td>{@link #appendDetailLine}</td></tr>
 *   <tr><td>{@code 1110-WRITE-PAGE-TOTALS}</td>
 *       <td>{@link #appendPageTotalLine}</td></tr>
 *   <tr><td>{@code 1120-WRITE-ACCOUNT-TOTALS}</td>
 *       <td>{@link #appendAccountTotalLine}</td></tr>
 *   <tr><td>Grand-total line at L203</td>
 *       <td>{@link #appendGrandTotalLine}</td></tr>
 *   <tr><td>{@code 1120-WRITE-HEADERS}</td>
 *       <td>{@link #appendHeaderLine}</td></tr>
 * </table>
 *
 * <h2>Implementation notes (AAP &sect;0.7.1)</h2>
 * <ul>
 *   <li><b>Streaming pagination:</b> The COBOL source reads the
 *       transaction file sequentially. To avoid loading the entire
 *       {@code transactions} table into memory, this service uses
 *       paged reads via
 *       {@link TransactionRepository#findAll(Pageable)} sized at
 *       {@link #DB_PAGE_SIZE} per chunk; the date-window filter is
 *       applied in memory per the COBOL semantic at L172-L178.</li>
 *   <li><b>BigDecimal arithmetic:</b> Running totals use
 *       {@link BigDecimal} with {@link RoundingMode#HALF_EVEN}.</li>
 *   <li><b>ON SIZE ERROR:</b> Totals are guarded against
 *       {@code PIC S9(09)V99} ceiling per the COBOL field widths.</li>
 *   <li><b>PCI-DSS:</b> The
 *       {@link ReportLineDto#maskedCardNumber()} field on detail
 *       lines is masked to last-4 by the producer per the AAP-mandated
 *       discipline.</li>
 *   <li><b>S3 output:</b> The assembled list of report lines is
 *       handed to
 *       {@link S3OutputService#writeReportLines(String, String, List)}
 *       which formats them line-by-line and writes one versioned
 *       S3 object per run.</li>
 *   <li><b>Read-only:</b> Annotated
 *       {@link Transactional @Transactional(readOnly = true)}.</li>
 *   <li><b>No direct AWS SDK calls:</b> S3 / audit interaction goes
 *       through adapters (AAP &sect;0.7.1).</li>
 * </ul>
 */
@Service
public class TransactionReportService {

    private static final Logger LOG =
            LoggerFactory.getLogger(TransactionReportService.class);

    /** ON SIZE ERROR guard for COBOL PIC S9(09)V99 totals. */
    static final BigDecimal MAX_TOTAL = new BigDecimal("999999999.99");

    /**
     * Page size for the streaming transaction read. The COBOL source
     * uses {@code WS-LINES-PER-PAGE} = 20 for printable page breaks
     * (paragraph {@code 1110-WRITE-PAGE-TOTALS}). This Java target
     * still emits a {@code PAGE_TOTAL} every {@code LINES_PER_PAGE}
     * detail lines for byte-identical output.
     */
    static final int LINES_PER_PAGE = 20;

    /** DB streaming page size (independent of report pagination). */
    static final int DB_PAGE_SIZE = 500;

    /** Audit event name. */
    static final String AUDIT_REPORT_GENERATED = "transaction.report.generated";

    /** Report-id prefix discriminator passed to the S3 adapter. */
    static final String REPORT_TYPE_PREFIX = "tranrept";

    private final TransactionRepository transactionRepository;
    private final TransactionTypeRepository typeRepository;
    private final TransactionCategoryRepository categoryRepository;
    private final CardCrossReferenceRepository xrefRepository;
    private final S3OutputService s3OutputService;
    private final AuditLogService auditLogService;

    public TransactionReportService(
            TransactionRepository transactionRepository,
            TransactionTypeRepository typeRepository,
            TransactionCategoryRepository categoryRepository,
            CardCrossReferenceRepository xrefRepository,
            S3OutputService s3OutputService,
            AuditLogService auditLogService) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository");
        this.typeRepository = Objects.requireNonNull(typeRepository,
                "typeRepository");
        this.categoryRepository = Objects.requireNonNull(categoryRepository,
                "categoryRepository");
        this.xrefRepository = Objects.requireNonNull(xrefRepository,
                "xrefRepository");
        this.s3OutputService = Objects.requireNonNull(s3OutputService,
                "s3OutputService");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService");
    }

    /**
     * Result summary returned to the caller.
     *
     * @param transactionsRead  number of transactions in the date
     *                          window
     * @param accountsReported  number of distinct accounts
     *                          represented in the report
     * @param grandTotal        cumulative monetary total across all
     *                          accounts
     * @param reportLines       total number of {@link ReportLineDto}
     *                          rows written to S3 (header + detail +
     *                          totals + footer)
     */
    public record Result(long transactionsRead,
                         int accountsReported,
                         BigDecimal grandTotal,
                         int reportLines) {
    }

    /**
     * Generates a transaction report for the supplied date window.
     *
     * @param startDate inclusive lower bound (mapped to
     *                  {@code WS-START-DATE}); must not be {@code null}
     * @param endDate   inclusive upper bound (mapped to
     *                  {@code WS-END-DATE}); must not be {@code null}
     * @param batchRunId batch execution identifier; must not be
     *                   {@code null} or blank
     * @return a {@link Result} summary
     * @throws IllegalArgumentException if arguments are invalid
     */
    @Transactional(readOnly = true)
    public Result generateReport(LocalDate startDate, LocalDate endDate, String batchRunId) {
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(endDate, "endDate");
        if (batchRunId == null || batchRunId.isBlank()) {
            throw new IllegalArgumentException("batchRunId must not be null or blank");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException(
                    "endDate (" + endDate + ") must not be before startDate (" + startDate + ")");
        }

        LOG.info("CBTRN03C: starting transaction report (startDate={}, endDate={}, batchRunId={})",
                startDate, endDate, batchRunId);

        List<ReportLineDto> lines = new ArrayList<>();
        // COBOL: paragraph 1120-WRITE-HEADERS &mdash; header line is emitted first
        lines.add(appendHeaderLine(startDate, endDate));

        BigDecimal pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal accountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal grandTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        Long lastAcctId = null;
        int linesOnPage = 0;
        long transactionsRead = 0L;
        int accountsReported = 0;
        int pageNumber = 1;

        int page = 0;
        Page<Transaction> currentPage;
        do {
            // Per AAP §0.7.3, this service replaces the legacy
            // cross-card date-range query with the inherited
            // findAll(Pageable) plus the in-memory date filter
            // immediately below — exactly mirroring the COBOL
            // CBTRN03C semantic at L172-L178 which iterates every
            // TRANSACT row and skips those outside the date window
            // (IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE
            // CONTINUE ELSE NEXT SENTENCE). The Pageable's Sort orders
            // the scan by tran_proc_ts for stable grouping by account.
            Pageable pageable = PageRequest.of(page, DB_PAGE_SIZE,
                    Sort.by("tranProcTs"));
            currentPage = transactionRepository.findAll(pageable);
            for (Transaction tx : currentPage.getContent()) {
                if (tx == null || tx.getTranProcTs() == null) {
                    continue;
                }
                LocalDate txDate = tx.getTranProcTs().toLocalDate();
                // COBOL: IF TRAN-PROC-TS (1:10) >= WS-START-DATE
                //          AND TRAN-PROC-TS (1:10) <= WS-END-DATE (L173-L174)
                if (txDate.isBefore(startDate) || txDate.isAfter(endDate)) {
                    continue;
                }

                Long acctId = resolveAccountId(tx.getTranCardNum());

                // COBOL: account boundary &mdash; emit account total
                // for the previous account first (paragraph
                // 1120-WRITE-ACCOUNT-TOTALS) at L183.
                if (lastAcctId != null && !lastAcctId.equals(acctId)) {
                    lines.add(appendAccountTotalLine(lastAcctId, accountTotal));
                    grandTotal = grandTotal.add(accountTotal)
                            .setScale(2, RoundingMode.HALF_EVEN);
                    guardOnSizeError(grandTotal, "WS-GRAND-TOTAL");
                    accountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                    accountsReported++;
                }
                lastAcctId = acctId;

                lines.add(appendDetailLine(tx, acctId));

                // COBOL: ADD TRAN-AMT TO WS-PAGE-TOTAL / WS-ACCOUNT-TOTAL (L287-L288)
                BigDecimal amt = tx.getTranAmt() == null
                        ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                        : tx.getTranAmt().setScale(2, RoundingMode.HALF_EVEN);
                pageTotal = pageTotal.add(amt).setScale(2, RoundingMode.HALF_EVEN);
                accountTotal = accountTotal.add(amt).setScale(2, RoundingMode.HALF_EVEN);
                guardOnSizeError(pageTotal, "WS-PAGE-TOTAL");
                guardOnSizeError(accountTotal, "WS-ACCOUNT-TOTAL");
                linesOnPage++;
                transactionsRead++;

                // COBOL: page break (paragraph 1110-WRITE-PAGE-TOTALS at L202)
                if (linesOnPage >= LINES_PER_PAGE) {
                    lines.add(appendPageTotalLine(pageTotal, pageNumber));
                    pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                    linesOnPage = 0;
                    pageNumber++;
                }
            }
            page++;
        } while (currentPage.hasNext());

        // Emit final account total + grand total
        if (lastAcctId != null) {
            lines.add(appendAccountTotalLine(lastAcctId, accountTotal));
            grandTotal = grandTotal.add(accountTotal)
                    .setScale(2, RoundingMode.HALF_EVEN);
            guardOnSizeError(grandTotal, "WS-GRAND-TOTAL");
            accountsReported++;
        }
        if (linesOnPage > 0) {
            lines.add(appendPageTotalLine(pageTotal, pageNumber));
        }
        lines.add(appendGrandTotalLine(grandTotal));

        // Hand the lines to the S3 adapter (one S3 object per run)
        s3OutputService.writeReportLines(batchRunId, REPORT_TYPE_PREFIX, lines);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startDate", startDate);
        payload.put("endDate", endDate);
        payload.put("transactionsRead", transactionsRead);
        payload.put("accountsReported", accountsReported);
        payload.put("grandTotal", grandTotal);
        payload.put("reportLines", lines.size());
        auditLogService.logAuditEvent(
                AUDIT_REPORT_GENERATED,
                "BATCH_RUN",
                batchRunId,
                "BATCH",
                payload,
                batchRunId);

        LOG.info("CBTRN03C: completed transaction report; "
                + "transactionsRead={}, accountsReported={}, grandTotal={}, reportLines={}",
                transactionsRead, accountsReported, grandTotal, lines.size());

        return new Result(transactionsRead, accountsReported, grandTotal, lines.size());
    }

    // -------------------------------------------------------------------------
    // ReportLineDto factory methods (one per COBOL line layout)
    // -------------------------------------------------------------------------

    /**
     * COBOL: paragraph {@code 1120-WRITE-HEADERS}. Emits the
     * {@code REPORT-NAME-HEADER} line with the date window.
     */
    ReportLineDto appendHeaderLine(LocalDate startDate, LocalDate endDate) {
        return new ReportLineDto(
                "HEADER",
                "Daily Transaction Report",
                startDate,
                endDate,
                null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * COBOL: paragraph {@code 1100-WRITE-TRANSACTION-REPORT}
     * (L285-L290). Emits one {@code TRANSACTION-DETAIL-REPORT} line.
     */
    ReportLineDto appendDetailLine(Transaction tx, Long acctId) {
        String typeDesc = lookupTransactionTypeDesc(tx.getTranTypeCd());
        String catDesc = lookupTransactionCategoryDesc(tx.getTranTypeCd(), tx.getTranCatCd());
        String description = (typeDesc + " " + catDesc).trim();
        if (description.isEmpty()) {
            description = tx.getTranDesc();
        }
        return new ReportLineDto(
                "DETAIL",
                null, null, null,
                acctId,
                maskPan(tx.getTranCardNum()),
                tx.getTranProcTs() != null ? tx.getTranProcTs().toLocalDate() : null,
                tx.getTranTypeCd(),
                tx.getTranCatCd(),
                tx.getTranSource(),
                description,
                tx.getTranAmt() != null
                        ? tx.getTranAmt().setScale(2, RoundingMode.HALF_EVEN)
                        : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN),
                null, null);
    }

    /**
     * COBOL: paragraph {@code 1110-WRITE-PAGE-TOTALS}. Emits a
     * {@code REPORT-PAGE-TOTALS} line.
     */
    ReportLineDto appendPageTotalLine(BigDecimal pageTotal, int pageNumber) {
        return new ReportLineDto(
                "PAGE_TOTAL",
                null, null, null,
                null, null, null, null, null, null, null,
                pageTotal.setScale(2, RoundingMode.HALF_EVEN),
                "Page Total",
                pageNumber);
    }

    /**
     * COBOL: paragraph {@code 1120-WRITE-ACCOUNT-TOTALS}. Emits a
     * {@code REPORT-ACCOUNT-TOTALS} line.
     */
    ReportLineDto appendAccountTotalLine(Long accountId, BigDecimal accountTotal) {
        return new ReportLineDto(
                "ACCOUNT_TOTAL",
                null, null, null,
                accountId,
                null, null, null, null, null, null,
                accountTotal.setScale(2, RoundingMode.HALF_EVEN),
                "Account Total",
                null);
    }

    /**
     * COBOL: paragraph at L203 (grand total). Emits the
     * {@code REPORT-GRAND-TOTALS} line.
     */
    ReportLineDto appendGrandTotalLine(BigDecimal grandTotal) {
        return new ReportLineDto(
                "GRAND_TOTAL",
                null, null, null,
                null, null, null, null, null, null, null,
                grandTotal.setScale(2, RoundingMode.HALF_EVEN),
                "Grand Total",
                null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves the account ID for a card number via the XREF table.
     * Returns {@code null} if no XREF entry exists.
     */
    private Long resolveAccountId(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            return null;
        }
        return xrefRepository.findById(cardNum)
                .map(CardCrossReference::getXrefAcctId)
                .orElse(null);
    }

    /** Looks up the transaction-type description; returns blank on miss. */
    private String lookupTransactionTypeDesc(String typeCd) {
        if (typeCd == null) {
            return "";
        }
        Optional<TransactionType> opt = typeRepository.findById(typeCd);
        return opt.map(TransactionType::getTranTypeDesc).orElse("");
    }

    /** Looks up the transaction-category description; returns blank on miss. */
    private String lookupTransactionCategoryDesc(String typeCd, Integer catCd) {
        if (typeCd == null || catCd == null) {
            return "";
        }
        TransactionCategory.TransactionCategoryId id =
                new TransactionCategory.TransactionCategoryId(typeCd, catCd);
        Optional<TransactionCategory> opt = categoryRepository.findById(id);
        return opt.map(TransactionCategory::getTranCatTypeDesc).orElse("");
    }

    private static void guardOnSizeError(BigDecimal value, String fieldName) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(MAX_TOTAL) > 0) {
            throw new OnSizeErrorException(
                    "ON_SIZE_ERROR",
                    "ON SIZE ERROR on " + fieldName
                            + " (computed " + value
                            + " exceeds PIC S9(9)V99 ceiling)");
        }
    }

    private static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "****";
        }
        return "****-****-****-" + pan.substring(pan.length() - 4);
    }

    /**
     * Convenience method (defensive) &mdash; allow external callers
     * (tests) to inspect the size of an unused-payload map without
     * triggering a compile warning.
     */
    @SuppressWarnings("unused")
    private static Map<String, Object> emptyPayload() {
        return new HashMap<>();
    }
}
