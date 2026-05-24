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
import com.awsm2.carddemo.domain.TransactionCategory.TransactionCategoryId;
import com.awsm2.carddemo.domain.TransactionType;
import com.awsm2.carddemo.exception.CardDemoException;
import com.awsm2.carddemo.exception.OnSizeErrorException;
import com.awsm2.carddemo.repository.CardCrossReferenceRepository;
import com.awsm2.carddemo.repository.TransactionCategoryRepository;
import com.awsm2.carddemo.repository.TransactionRepository;
import com.awsm2.carddemo.repository.TransactionTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Transaction detail report generator &mdash; the Java target for the
 * COBOL batch program {@code app/cbl/CBTRN03C.cbl} (the
 * transaction-detail-report variant invoked by JCL job
 * {@code app/jcl/TRANREPT.jcl}). Produces a paginated, fixed-width
 * text report filtered by a date window, with card-change subtotals,
 * page totals, and a grand total, then uploads the assembled report to
 * S3 via the {@link S3OutputService} adapter.
 *
 * <h2>COBOL provenance (per AAP &sect;0.7.3 refactor discipline)</h2>
 * <ul>
 *   <li><b>COBOL program:</b> {@code app/cbl/CBTRN03C.cbl} &mdash;
 *       reads {@code TRANSACT-FILE} sequentially, filters by
 *       {@code WS-START-DATE} / {@code WS-END-DATE}, joins with
 *       {@code XREF-FILE} (card &rarr; account lookup),
 *       {@code TRANTYPE-FILE} (transaction-type description), and
 *       {@code TRANCATG-FILE} (transaction-category description), and
 *       writes the paginated detail report to {@code REPORT-FILE}.</li>
 *   <li><b>Record layouts:</b>
 *       {@code app/cpy/CVTRA05Y.cpy} ({@link Transaction}),
 *       {@code app/cpy/CVTRA03Y.cpy} ({@link TransactionType}),
 *       {@code app/cpy/CVTRA04Y.cpy} ({@link TransactionCategory}),
 *       {@code app/cpy/CVACT03Y.cpy} ({@link CardCrossReference}),
 *       {@code app/cpy/CVTRA07Y.cpy} (report-line layouts).</li>
 *   <li><b>Date window:</b> {@code WS-START-DATE} /
 *       {@code WS-END-DATE} are sourced from the {@code DATEPARM} DD
 *       file in COBOL (paragraphs {@code 0500-DATEPARM-OPEN} and
 *       {@code 0550-DATEPARM-READ}, L466-L482 / L220-L243). In the
 *       Java target these become explicit method parameters &mdash;
 *       the {@code DATEPARM} DD allocation is replaced by caller-
 *       supplied values per AAP &sect;0.4.1.</li>
 * </ul>
 *
 * <h2>COBOL paragraph &harr; Java method translation</h2>
 * <table>
 *   <caption>CBTRN03C.cbl &harr; TransactionReportService</caption>
 *   <tr><th>COBOL paragraph</th><th>Java equivalent</th></tr>
 *   <tr><td>{@code PROCEDURE DIVISION} main loop (L159-L210)</td>
 *       <td>{@link #generateReport(LocalDate, LocalDate)}</td></tr>
 *   <tr><td>{@code 1100-WRITE-TRANSACTION-REPORT} (L274-L290)</td>
 *       <td>{@code generateReport} inner loop emitting detail lines</td></tr>
 *   <tr><td>{@code 1110-WRITE-PAGE-TOTALS} (L293-L304)</td>
 *       <td>page-total emission inside the main loop</td></tr>
 *   <tr><td>{@code 1110-WRITE-GRAND-TOTALS} (L318-L322)</td>
 *       <td>{@link #writeGrandTotal(StringBuilder, BigDecimal)}</td></tr>
 *   <tr><td>{@code 1120-WRITE-ACCOUNT-TOTALS} (L306-L316)</td>
 *       <td>{@link #writeAccountTotalLine(StringBuilder, String, BigDecimal)}</td></tr>
 *   <tr><td>{@code 1120-WRITE-HEADERS} (L324-L341)</td>
 *       <td>{@link #writePageHeader(StringBuilder, int, LocalDate, LocalDate)}</td></tr>
 *   <tr><td>{@code 1120-WRITE-DETAIL} (L361-L374)</td>
 *       <td>{@link #renderDetailLine(Transaction, TransactionType, TransactionCategory)}</td></tr>
 *   <tr><td>{@code 1500-A-LOOKUP-XREF} (L484-L492)</td>
 *       <td>{@code xrefRepository.findById(cardNumber)} with local
 *       {@link HashMap} cache</td></tr>
 *   <tr><td>{@code 1500-B-LOOKUP-TRANTYPE} (L494-L502)</td>
 *       <td>{@code typeRepository.findById(typeCd)} with local
 *       {@link HashMap} cache</td></tr>
 *   <tr><td>{@code 1500-C-LOOKUP-TRANCATG} (L504-L512)</td>
 *       <td>{@code categoryRepository.findById(compositeId)} with
 *       local {@link HashMap} cache</td></tr>
 *   <tr><td>{@code 1111-WRITE-REPORT-REC} (L343-L359)</td>
 *       <td>StringBuilder append + final
 *       {@link S3OutputService#writeReport(String, byte[])}</td></tr>
 * </table>
 *
 * <h2>Implementation rules (AAP &sect;0.7.1, agent_prompt)</h2>
 * <ul>
 *   <li><b>Layered architecture</b> &mdash; this service is the
 *       business-logic layer; it depends on Spring Data JPA
 *       repositories (data layer) and AWS adapters
 *       ({@link S3OutputService}, {@link AuditLogService}). No direct
 *       AWS SDK calls are made from this class.</li>
 *   <li><b>Constructor injection only</b> &mdash; final fields,
 *       Spring-managed. No {@code @Autowired} field injection per AAP
 *       &sect;0.3.3.</li>
 *   <li><b>{@code @Transactional(readOnly = true)}</b> on
 *       {@link #generateReport(LocalDate, LocalDate)} opens a
 *       read-only JPA transaction, hinting the JDBC connection as
 *       read-only and avoiding Hibernate dirty-checking overhead
 *       during the streaming scan across the {@code transactions}
 *       table.</li>
 *   <li><b>BigDecimal + HALF_EVEN</b> &mdash; every monetary
 *       accumulator ({@code accountTotal}, {@code pageTotal},
 *       {@code grandTotal}) is a {@link BigDecimal} with explicit
 *       {@code setScale(2, RoundingMode.HALF_EVEN)} (banker's
 *       rounding), mirroring COBOL {@code PIC S9(09)V99} semantics
 *       for {@code WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL}, and
 *       {@code WS-GRAND-TOTAL}. Zero {@code float}/{@code double}
 *       substitution for any monetary value (AAP &sect;0.6.1).</li>
 *   <li><b>ON SIZE ERROR</b> &mdash; the {@link #safeAdd(BigDecimal,
 *       BigDecimal, String)} helper wraps every accumulation in a
 *       try/catch that throws {@link OnSizeErrorException} on
 *       {@link ArithmeticException}, replicating COBOL
 *       {@code ON SIZE ERROR} semantics on {@code PIC S9(09)V99}
 *       overflow.</li>
 *   <li><b>PAN masking</b> &mdash; the report text NEVER contains a
 *       full 16-digit PAN; the {@link #maskPan(String)} helper emits
 *       only the last 4 digits prefixed with twelve asterisks per
 *       PCI-DSS scope (AAP &sect;0.6.6).</li>
 *   <li><b>Card-change subtotal pattern</b> &mdash; transactions are
 *       sorted by card number then by processing timestamp
 *       ({@code Comparator.comparing(Transaction::getTranCardNum)
 *       .thenComparing(Transaction::getTranProcTs)}). The
 *       {@code WS-CURR-CARD-NUM} tracker (COBOL L137) is replicated
 *       by a {@code currentCard} local variable initialized to
 *       {@code null} to suppress the account-total line on the first
 *       iteration (COBOL {@code WS-FIRST-TIME='Y'} semantic at
 *       L182-L184).</li>
 *   <li><b>Local repository caches</b> &mdash; XREF, transaction-type,
 *       and transaction-category lookups are cached in
 *       {@link HashMap} instances local to a single
 *       {@code generateReport} invocation. This eliminates N+1
 *       repository round trips when many transactions share the same
 *       type or category and matches the COBOL random-key VSAM read
 *       cost (one disk seek per distinct key).</li>
 *   <li><b>S3 output</b> &mdash; the assembled report text is
 *       UTF-8 encoded as bytes and handed to
 *       {@link S3OutputService#writeReport(String, byte[])} per AAP
 *       &sect;0.4.1 (Adapter Pattern). SSE-KMS encryption is
 *       performed inside the adapter.</li>
 *   <li><b>Audit log</b> &mdash; after a successful S3 upload,
 *       {@link AuditLogService#logAuditEvent(String, String, String,
 *       String, Map, String)} records the
 *       {@code REPORT_GENERATED} event with the date range,
 *       transaction count, page count, grand total, and S3 key for
 *       regulatory traceability (AAP &sect;0.6.6).</li>
 * </ul>
 */
@Service
public class TransactionReportService {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionReportService.class);

    /**
     * Page size in detail lines per page.
     *
     * <p>COBOL: {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20}
     * (CBTRN03C.cbl L131-L132). After every 20 detail lines emitted,
     * the service writes a page-total line and then a fresh page
     * header, mirroring the COBOL
     * {@code FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0} check
     * at L282.</p>
     */
    static final int PAGE_SIZE = 20;

    /**
     * Upper bound for the COBOL {@code PIC S9(09)V99} totals
     * ({@code WS-PAGE-TOTAL}, {@code WS-ACCOUNT-TOTAL},
     * {@code WS-GRAND-TOTAL} at L134-L136). Any accumulator that
     * exceeds this magnitude triggers an
     * {@link OnSizeErrorException} per AAP &sect;0.6.1 / &sect;0.7.1.
     */
    static final BigDecimal MAX_TOTAL = new BigDecimal("999999999.99");

    /**
     * S3 key prefix used by {@link S3OutputService#writeReport(String,
     * byte[])} when persisting the report. This must match the
     * {@code "tranrept"} literal embedded in
     * {@link S3OutputService#writeReport(String, byte[])} so that the
     * {@link #buildLogicalS3Key(String)} method can construct an
     * accurate {@code s3Key} value for the {@link ReportResult}.
     */
    static final String S3_KEY_PREFIX = "tranrept";

    /**
     * Audit event type recorded when a report is successfully
     * uploaded to S3.
     */
    static final String AUDIT_EVENT_REPORT_GENERATED = "REPORT_GENERATED";

    /**
     * Audit resource type for the {@link AuditLogService#logAuditEvent(
     * String, String, String, String, Map, String)} call. Identifies
     * the resource being acted on as a transaction report.
     */
    static final String AUDIT_RESOURCE_TYPE_REPORT = "TRANSACTION_REPORT";

    /**
     * Operator code recorded on the audit event &mdash; the COBOL
     * batch program identifier preserved verbatim per AAP
     * &sect;0.7.3 (refactor discipline).
     */
    static final String AUDIT_OPERATOR_CBTRN03C = "BATCH/CBTRN03C";

    private final TransactionRepository transactionRepository;
    private final CardCrossReferenceRepository xrefRepository;
    private final TransactionTypeRepository typeRepository;
    private final TransactionCategoryRepository categoryRepository;
    private final S3OutputService s3OutputService;
    private final AuditLogService auditLogService;

    /**
     * Constructs a {@code TransactionReportService} with the
     * dependencies it needs to read transactions, resolve lookups,
     * emit S3 output, and record audit events.
     *
     * <p>Constructor injection only (no field injection) per AAP
     * &sect;0.3.3. All injected dependencies are required and
     * validated for non-null via {@link Objects#requireNonNull(Object,
     * String)}.</p>
     *
     * @param transactionRepository  Spring Data JPA repository over
     *                               {@link Transaction}; replaces the
     *                               COBOL {@code TRANSACT-FILE} VSAM
     *                               sequential read in
     *                               {@code 1000-TRANFILE-GET-NEXT}
     * @param xrefRepository         repository over
     *                               {@link CardCrossReference};
     *                               replaces the COBOL
     *                               {@code XREF-FILE} indexed read in
     *                               {@code 1500-A-LOOKUP-XREF}
     * @param typeRepository         repository over
     *                               {@link TransactionType}; replaces
     *                               the COBOL {@code TRANTYPE-FILE}
     *                               indexed read in
     *                               {@code 1500-B-LOOKUP-TRANTYPE}
     * @param categoryRepository     repository over
     *                               {@link TransactionCategory};
     *                               replaces the COBOL
     *                               {@code TRANCATG-FILE} indexed
     *                               read in
     *                               {@code 1500-C-LOOKUP-TRANCATG}
     * @param s3OutputService        adapter that writes the rendered
     *                               report to S3; replaces the COBOL
     *                               {@code WRITE FD-REPTFILE-REC} on
     *                               the {@code TRANREPT} DD in
     *                               {@code 1111-WRITE-REPORT-REC}
     * @param auditLogService        adapter that records the
     *                               {@code REPORT_GENERATED} event
     *                               into OpenSearch + CloudWatch for
     *                               regulatory traceability
     */
    public TransactionReportService(
            TransactionRepository transactionRepository,
            CardCrossReferenceRepository xrefRepository,
            TransactionTypeRepository typeRepository,
            TransactionCategoryRepository categoryRepository,
            S3OutputService s3OutputService,
            AuditLogService auditLogService) {
        this.transactionRepository = Objects.requireNonNull(transactionRepository,
                "transactionRepository must not be null");
        this.xrefRepository = Objects.requireNonNull(xrefRepository,
                "xrefRepository must not be null");
        this.typeRepository = Objects.requireNonNull(typeRepository,
                "typeRepository must not be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository,
                "categoryRepository must not be null");
        this.s3OutputService = Objects.requireNonNull(s3OutputService,
                "s3OutputService must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService,
                "auditLogService must not be null");
    }

    /**
     * Result record returned by
     * {@link #generateReport(LocalDate, LocalDate)}.
     *
     * <p>Carries the count of transactions included in the report, the
     * total page count, the cumulative monetary {@code grandTotal},
     * and the S3 key under which the rendered report text was
     * persisted.</p>
     *
     * <p>COBOL provenance: replaces the {@code WS-GRAND-TOTAL}
     * (L136), {@code WS-LINE-COUNTER} (L129-L130), and
     * {@code FD-REPTFILE-REC} write semantics with a structured
     * return value for the Java caller.</p>
     *
     * @param transactionCount number of transactions that matched the
     *                         date window and were included in the
     *                         report; corresponds to the COBOL
     *                         read-and-write count
     * @param pageCount        total number of report pages produced
     *                         (at least 1 if any transactions
     *                         matched; 0 if none)
     * @param grandTotal       cumulative monetary total across all
     *                         pages; corresponds to COBOL
     *                         {@code WS-GRAND-TOTAL} (L136)
     * @param s3Key            S3 object key under which the rendered
     *                         report text was persisted by the
     *                         {@link S3OutputService}
     */
    public record ReportResult(int transactionCount,
                               int pageCount,
                               BigDecimal grandTotal,
                               String s3Key) {
    }

    /**
     * Generates the transaction detail report for the supplied date
     * window and uploads the rendered text to S3.
     *
     * <p>Mirrors the COBOL CBTRN03C {@code PROCEDURE DIVISION} main
     * loop (L159-L210):</p>
     * <ol>
     *   <li>Validate the date-range parameters (throws
     *       {@link CardDemoException} on null inputs or inverted
     *       range).</li>
     *   <li>Stream all transactions via
     *       {@link TransactionRepository#findAll()}; filter in memory
     *       to those whose {@code TRAN-PROC-TS}{@code (1:10)} falls
     *       within the inclusive {@code [startDate, endDate]} window
     *       (COBOL L173-L174).</li>
     *   <li>Sort by {@code TRAN-CARD-NUM} then by
     *       {@code TRAN-PROC-TS} so that the {@code WS-CURR-CARD-NUM}
     *       tracker (COBOL L181-L188) detects card boundaries
     *       monotonically.</li>
     *   <li>Iterate and emit a fixed-width detail line per
     *       transaction
     *       ({@code 1120-WRITE-DETAIL}, L361-L374); accumulate
     *       {@code accountTotal}, {@code pageTotal}, and
     *       {@code grandTotal}; on card boundary write an
     *       account-total line
     *       ({@code 1120-WRITE-ACCOUNT-TOTALS}, L306-L316); on every
     *       {@link #PAGE_SIZE} detail lines write a page-total line
     *       and a fresh page header
     *       ({@code 1110-WRITE-PAGE-TOTALS}, L293-L304).</li>
     *   <li>After the loop, emit the final account-total line, the
     *       final page-total line if any in-progress page exists,
     *       and the grand-total line
     *       ({@code 1110-WRITE-GRAND-TOTALS}, L318-L322).</li>
     *   <li>Upload the assembled report text to S3 via
     *       {@link S3OutputService#writeReport(String, byte[])}.</li>
     *   <li>Record the {@code REPORT_GENERATED} audit event via
     *       {@link AuditLogService#logAuditEvent(String, String,
     *       String, String, Map, String)}.</li>
     * </ol>
     *
     * @param startDate inclusive lower bound of the report date
     *                  window (replaces COBOL {@code WS-START-DATE}
     *                  PIC X(10), L123); must not be {@code null}
     * @param endDate   inclusive upper bound of the report date
     *                  window (replaces COBOL {@code WS-END-DATE}
     *                  PIC X(10), L125); must not be {@code null}
     * @return a {@link ReportResult} carrying the transaction count,
     *         page count, grand total, and S3 key
     * @throws CardDemoException   if {@code startDate} is {@code null},
     *                             {@code endDate} is {@code null}, or
     *                             {@code startDate} is after
     *                             {@code endDate}
     * @throws OnSizeErrorException if any monetary accumulator
     *                              ({@code accountTotal},
     *                              {@code pageTotal},
     *                              {@code grandTotal}) exceeds the
     *                              COBOL {@code PIC S9(09)V99}
     *                              precision ceiling
     */
    @Transactional(readOnly = true)
    public ReportResult generateReport(LocalDate startDate, LocalDate endDate) {
        // -----------------------------------------------------------
        // Step 1: input validation (replaces COBOL L168 DATEPARM read)
        // -----------------------------------------------------------
        validateDateRange(startDate, endDate);

        LOG.info("CBTRN03C: starting transaction report startDate={}, endDate={}",
                startDate, endDate);

        // -----------------------------------------------------------
        // Step 2: streaming read of all transactions and in-memory
        //         date-window filter (COBOL: 1000-TRANFILE-GET-NEXT
        //         + L173-L178 inclusion test)
        // -----------------------------------------------------------
        List<Transaction> allTransactions = transactionRepository.findAll();
        List<Transaction> inWindow = filterByDateWindow(allTransactions, startDate, endDate);

        LOG.info("CBTRN03C: transactions read={}, in date window={}",
                allTransactions.size(), inWindow.size());

        // -----------------------------------------------------------
        // Step 3: sort by card number then by processing timestamp
        //         so the WS-CURR-CARD-NUM tracker monotonically
        //         observes boundary changes (COBOL L181).
        //
        // Comparator.comparing accepts a Comparator.nullsFirst around
        // the key extractor to handle defensive nulls without an NPE.
        // -----------------------------------------------------------
        inWindow.sort(
                Comparator.comparing(Transaction::getTranCardNum,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Transaction::getTranProcTs,
                                Comparator.nullsFirst(Comparator.naturalOrder())));

        // -----------------------------------------------------------
        // Step 4: main emission loop (COBOL: 1100-WRITE-TRANSACTION-REPORT
        //         + 1110-WRITE-PAGE-TOTALS + 1120-WRITE-ACCOUNT-TOTALS).
        // -----------------------------------------------------------
        StringBuilder sb = new StringBuilder(Math.max(4096, inWindow.size() * 100));

        // Local caches replicate the random-key VSAM read cost of the
        // COBOL 1500-A/B/C lookups (1 disk seek per distinct key).
        Map<String, Long> xrefCache = new HashMap<>();
        Map<String, String> typeDescCache = new HashMap<>();
        Map<String, String> catDescCache = new HashMap<>();

        // COBOL: WS-CURR-CARD-NUM PIC X(16) VALUE SPACES (L137).
        // Java equivalent: initialise to null so the first-iteration
        // suppression of 1120-WRITE-ACCOUNT-TOTALS triggers via the
        // currentCard != null guard (replicates WS-FIRST-TIME='Y'
        // semantic at L182-L184).
        String currentCard = null;
        BigDecimal accountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal grandTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        int linesOnPage = 0;
        int pageNumber = 1;
        int transactionCount = 0;

        // COBOL: 1120-WRITE-HEADERS executed once at the start when
        // WS-FIRST-TIME='Y' (L275-L280). Java emits the first page
        // header up front so the report is well-formed even when no
        // transactions match.
        writePageHeader(sb, pageNumber, startDate, endDate);

        for (Transaction tx : inWindow) {
            // Defensive null guard &mdash; should not occur because
            // JpaRepository.findAll never returns nulls, but the
            // sort comparator already tolerated them so we are
            // consistent here.
            if (tx == null) {
                continue;
            }

            String txCard = tx.getTranCardNum();

            // -------------------------------------------------------
            // COBOL: card-boundary detection (L181-L188).
            //   IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM
            //     IF WS-FIRST-TIME = 'N'
            //       PERFORM 1120-WRITE-ACCOUNT-TOTALS
            //     END-IF
            //     MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM
            //     MOVE TRAN-CARD-NUM TO FD-XREF-CARD-NUM
            //     PERFORM 1500-A-LOOKUP-XREF
            //   END-IF
            //
            // Java equivalent: emit the account-total line for the
            // PREVIOUS card before resetting accountTotal.
            // -------------------------------------------------------
            if (currentCard != null && !currentCard.equals(txCard)) {
                // COBOL: 1120-WRITE-ACCOUNT-TOTALS emits the line and
                // ZEROS out WS-ACCOUNT-TOTAL but does NOT add it to
                // WS-GRAND-TOTAL (paragraph at L306-L316 of CBTRN03C).
                // The grand total is accumulated solely from the page
                // totals (paragraph 1110-WRITE-PAGE-TOTALS at L297:
                // "ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL"); since every
                // TRAN-AMT is added to both WS-PAGE-TOTAL and
                // WS-ACCOUNT-TOTAL at L287-L288, adding account totals
                // here would double-count.
                writeAccountTotalLine(sb, currentCard, accountTotal);
                accountTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                linesOnPage++;
                // Account-total line counts as a printed line for
                // page-size accounting (COBOL adds 1 to
                // WS-LINE-COUNTER at L311).
                if (linesOnPage >= PAGE_SIZE) {
                    writePageTotalLine(sb, pageTotal, pageNumber);
                    grandTotal = safeAdd(grandTotal, pageTotal, "WS-GRAND-TOTAL");
                    pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                    linesOnPage = 0;
                    pageNumber++;
                    writePageHeader(sb, pageNumber, startDate, endDate);
                }
            }
            currentCard = txCard;

            // -------------------------------------------------------
            // COBOL: 1500-A-LOOKUP-XREF, 1500-B-LOOKUP-TRANTYPE,
            //        1500-C-LOOKUP-TRANCATG (L484-L512). All three
            //        are cached locally to avoid N+1 repository
            //        round trips.
            // -------------------------------------------------------
            // Resolve the account ID for the current card via XREF
            // (acctId is not directly printed in this fixed-width
            // detail line, but the lookup is performed to match the
            // COBOL behaviour and to validate the XREF entry exists
            // for the card &mdash; CBTRN03C would ABEND on missing
            // XREF, but the Java target degrades gracefully and
            // continues with a null acctId so the report can still
            // be produced).
            resolveAccountId(txCard, xrefCache);

            String typeDesc = resolveTypeDesc(tx.getTranTypeCd(), typeDescCache);
            String catDesc = resolveCategoryDesc(tx.getTranTypeCd(), tx.getTranCatCd(),
                    catDescCache);

            // -------------------------------------------------------
            // COBOL: 1120-WRITE-DETAIL (L361-L374). Emit one detail
            //        line, then increment WS-LINE-COUNTER (L373).
            // -------------------------------------------------------
            sb.append(renderDetailLine(tx, typeDesc, catDesc));
            sb.append('\n');

            // Accumulate totals (COBOL: ADD TRAN-AMT TO
            // WS-PAGE-TOTAL / WS-ACCOUNT-TOTAL, L287-L288).
            BigDecimal amt = normaliseAmount(tx.getTranAmt());
            accountTotal = safeAdd(accountTotal, amt, "WS-ACCOUNT-TOTAL");
            pageTotal = safeAdd(pageTotal, amt, "WS-PAGE-TOTAL");

            transactionCount++;
            linesOnPage++;

            // -------------------------------------------------------
            // COBOL: IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE)
            //        = 0 (L282) &mdash; page break.
            // -------------------------------------------------------
            if (linesOnPage >= PAGE_SIZE) {
                writePageTotalLine(sb, pageTotal, pageNumber);
                grandTotal = safeAdd(grandTotal, pageTotal, "WS-GRAND-TOTAL");
                pageTotal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
                linesOnPage = 0;
                pageNumber++;
                writePageHeader(sb, pageNumber, startDate, endDate);
            }
        }

        // -----------------------------------------------------------
        // Step 5: final emissions after the main loop ends (COBOL
        //         L197-L204 ELSE branch on end-of-file).
        // -----------------------------------------------------------
        // Final account total (only if at least one transaction
        // contributed to the in-progress account). Per COBOL
        // 1120-WRITE-ACCOUNT-TOTALS this is a display-only emission
        // that ZEROs WS-ACCOUNT-TOTAL but does NOT add it to
        // WS-GRAND-TOTAL — that role is reserved for the page total
        // (see comment above the in-loop card-change branch).
        if (currentCard != null) {
            writeAccountTotalLine(sb, currentCard, accountTotal);
            linesOnPage++;
        }
        // Final page total (only if the in-progress page has at
        // least one line and was not just flushed by the modulus
        // check above). COBOL: 1110-WRITE-PAGE-TOTALS at L297 adds
        // WS-PAGE-TOTAL to WS-GRAND-TOTAL — this is the SOLE path by
        // which transactions accumulate into the grand total.
        if (linesOnPage > 0) {
            writePageTotalLine(sb, pageTotal, pageNumber);
            grandTotal = safeAdd(grandTotal, pageTotal, "WS-GRAND-TOTAL");
        }
        // Grand total (COBOL: 1110-WRITE-GRAND-TOTALS, L318-L322).
        writeGrandTotal(sb, grandTotal);

        // -----------------------------------------------------------
        // Step 6: upload to S3 via the adapter (COBOL:
        //         1111-WRITE-REPORT-REC + 9100-REPTFILE-CLOSE).
        // -----------------------------------------------------------
        String reportId = buildReportId(startDate, endDate);
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        s3OutputService.writeReport(reportId, bytes);
        String s3Key = buildLogicalS3Key(reportId);

        // -----------------------------------------------------------
        // Step 7: audit-log emission (AAP §0.6.6). The pageCount in
        //         the result reflects the number of pages that
        //         actually received content (at least one detail or
        //         total line).
        // -----------------------------------------------------------
        int pageCount = transactionCount == 0 ? 0 : pageNumber;
        emitAuditEvent(startDate, endDate, transactionCount, pageCount, grandTotal, s3Key);

        LOG.info("CBTRN03C: complete. transactionCount={}, pageCount={}, "
                        + "grandTotal={}, s3Key={}",
                transactionCount, pageCount, grandTotal, s3Key);

        return new ReportResult(transactionCount, pageCount, grandTotal, s3Key);
    }

    // =========================================================================
    // Validation helpers
    // =========================================================================

    /**
     * Validates the date-range parameters &mdash; replaces the COBOL
     * {@code 0550-DATEPARM-READ} paragraph (L220-L243) where the
     * COBOL source unconditionally accepted the file contents but
     * the Java target enforces non-null / ordered inputs.
     *
     * @param startDate the start date supplied by the caller
     * @param endDate   the end date supplied by the caller
     * @throws CardDemoException on null inputs or inverted range
     */
    private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null) {
            throw new CardDemoException("VALIDATION",
                    "startDate must not be null (replaces COBOL WS-START-DATE)");
        }
        if (endDate == null) {
            throw new CardDemoException("VALIDATION",
                    "endDate must not be null (replaces COBOL WS-END-DATE)");
        }
        if (startDate.isAfter(endDate)) {
            throw new CardDemoException("VALIDATION",
                    "startDate (" + startDate + ") must not be after endDate (" + endDate + ")");
        }
    }

    /**
     * Filters transactions to those whose
     * {@link Transaction#getTranProcTs()} date falls within
     * {@code [startDate, endDate]} inclusive &mdash; replaces COBOL
     * L173-L174:
     *
     * <pre>{@code
     *   IF TRAN-PROC-TS (1:10) >= WS-START-DATE
     *      AND TRAN-PROC-TS (1:10) <= WS-END-DATE
     *      CONTINUE
     *   ELSE
     *      NEXT SENTENCE
     *   END-IF
     * }</pre>
     *
     * <p>Transactions with a {@code null} {@code tranProcTs} are
     * silently excluded (the COBOL source would never encounter one
     * because the VSAM record layout fixes the field width).</p>
     *
     * @param all       all transactions read from the repository
     * @param startDate inclusive lower bound
     * @param endDate   inclusive upper bound
     * @return a new {@link ArrayList} of in-window transactions
     */
    private static List<Transaction> filterByDateWindow(List<Transaction> all,
                                                        LocalDate startDate,
                                                        LocalDate endDate) {
        List<Transaction> result = new ArrayList<>(all.size());
        for (Transaction tx : all) {
            if (tx == null) {
                continue;
            }
            LocalDateTime procTs = tx.getTranProcTs();
            if (procTs == null) {
                continue;
            }
            LocalDate txDate = procTs.toLocalDate();
            if (txDate.isBefore(startDate) || txDate.isAfter(endDate)) {
                continue;
            }
            result.add(tx);
        }
        return result;
    }

    // =========================================================================
    // Lookup helpers (COBOL 1500-A/B/C-LOOKUP-XREF/TRANTYPE/TRANCATG)
    // =========================================================================

    /**
     * Resolves the owning account ID for a card number via the XREF
     * table, with a local {@link HashMap} cache to avoid duplicate
     * lookups within the same report run.
     *
     * <p>COBOL: {@code 1500-A-LOOKUP-XREF} (L484-L492). The COBOL
     * source abends on a missing XREF entry; the Java target returns
     * {@code null} so the report can still be produced.</p>
     *
     * @param cardNum the card number to resolve
     * @param cache   local lookup cache keyed by card number
     * @return the owning account ID, or {@code null} if no XREF
     *         entry exists or {@code cardNum} is {@code null}/blank
     */
    private Long resolveAccountId(String cardNum, Map<String, Long> cache) {
        if (cardNum == null || cardNum.isBlank()) {
            return null;
        }
        if (cache.containsKey(cardNum)) {
            return cache.get(cardNum);
        }
        Optional<CardCrossReference> xref = xrefRepository.findById(cardNum);
        Long acctId = xref.map(CardCrossReference::getXrefAcctId).orElse(null);
        cache.put(cardNum, acctId);
        return acctId;
    }

    /**
     * Resolves the description for a transaction-type code via the
     * {@code tran_type} table, with a local {@link HashMap} cache.
     *
     * <p>COBOL: {@code 1500-B-LOOKUP-TRANTYPE} (L494-L502).</p>
     *
     * @param typeCd the 2-character transaction-type code
     * @param cache  local lookup cache keyed by type code
     * @return the description string, or an empty string if no entry
     *         exists or {@code typeCd} is {@code null}
     */
    private String resolveTypeDesc(String typeCd, Map<String, String> cache) {
        if (typeCd == null) {
            return "";
        }
        if (cache.containsKey(typeCd)) {
            return cache.get(typeCd);
        }
        Optional<TransactionType> type = typeRepository.findById(typeCd);
        String desc = type.map(TransactionType::getTranTypeDesc).orElse("");
        cache.put(typeCd, desc);
        return desc;
    }

    /**
     * Resolves the description for a transaction-category composite
     * key (type code + category code) via the {@code tran_category}
     * table, with a local {@link HashMap} cache.
     *
     * <p>COBOL: {@code 1500-C-LOOKUP-TRANCATG} (L504-L512).</p>
     *
     * @param typeCd the 2-character transaction-type code
     * @param catCd  the 4-digit transaction-category code
     * @param cache  local lookup cache keyed by the composite
     *               {@code typeCd + ":" + catCd}
     * @return the description string, or an empty string if no entry
     *         exists or either component is {@code null}
     */
    private String resolveCategoryDesc(String typeCd, Integer catCd,
                                       Map<String, String> cache) {
        if (typeCd == null || catCd == null) {
            return "";
        }
        String cacheKey = typeCd + ":" + catCd;
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        TransactionCategoryId id = new TransactionCategoryId(typeCd, catCd);
        Optional<TransactionCategory> cat = categoryRepository.findById(id);
        String desc = cat.map(TransactionCategory::getTranCatTypeDesc).orElse("");
        cache.put(cacheKey, desc);
        return desc;
    }

    // =========================================================================
    // Report-line rendering (COBOL 1120-WRITE-DETAIL, 1120-WRITE-HEADERS,
    // 1110-WRITE-PAGE-TOTALS, 1120-WRITE-ACCOUNT-TOTALS, 1110-WRITE-GRAND-TOTALS)
    // =========================================================================

    /**
     * Writes the 5-line page header (COBOL:
     * {@code 1120-WRITE-HEADERS}, L324-L341).
     *
     * <p>The header consists of:</p>
     * <ol>
     *   <li>A centered banner {@code *** TRANSACTION DETAIL REPORT ***}</li>
     *   <li>The date-range line with a 3-digit page number</li>
     *   <li>A divider line of 80 dashes</li>
     *   <li>Column headers (TranID, Card Number, Type, Cat,
     *       Description, Amount)</li>
     *   <li>A second divider line of 80 dashes</li>
     * </ol>
     *
     * @param sb        the report buffer being assembled
     * @param pageNum   the 1-based page number
     * @param startDate the start of the date window
     * @param endDate   the end of the date window
     */
    static void writePageHeader(StringBuilder sb, int pageNum,
                                LocalDate startDate, LocalDate endDate) {
        // Line 1: centered banner
        String banner = "*** TRANSACTION DETAIL REPORT ***";
        sb.append(centerIn(banner, 80)).append('\n');
        // Line 2: date range and page number
        sb.append(String.format("Date Range: %s to %s   Page %03d",
                startDate, endDate, pageNum)).append('\n');
        // Line 3: divider
        sb.append(repeatChar('-', 80)).append('\n');
        // Line 4: column headers
        sb.append(padRight("Tran ID", 16)).append(" | ")
                .append(padRight("Card Number", 16)).append(" | ")
                .append(padRight("Type", 4)).append(" | ")
                .append(padRight("Cat", 4)).append(" | ")
                .append(padRight("Description", 20)).append(" | ")
                .append(padRight("Amount", 15)).append('\n');
        // Line 5: divider
        sb.append(repeatChar('-', 80)).append('\n');
    }

    /**
     * Renders one fixed-width detail line for a single transaction
     * (COBOL: {@code 1120-WRITE-DETAIL}, L361-L374).
     *
     * <p>The line consists of, in order: 16-character padded
     * transaction ID, vertical pipe separator, 16-character masked
     * PAN, vertical pipe separator, 4-character type code, vertical
     * pipe separator, 4-character category code, vertical pipe
     * separator, 20-character description (truncated to fit if
     * longer), vertical pipe separator, 15-character right-aligned
     * monetary amount ({@code %15.2f}).</p>
     *
     * <p>The transaction's {@code TRAN-DESC} field (COBOL
     * {@code app/cpy/CVTRA05Y.cpy}) is used as a fallback description
     * when neither the type nor the category lookup yields a
     * non-empty value.</p>
     *
     * @param tx       the transaction being rendered
     * @param typeDesc the description resolved via the type lookup
     *                 (may be empty)
     * @param catDesc  the description resolved via the category
     *                 lookup (may be empty)
     * @return the fully formatted, fixed-width detail line (no
     *         trailing newline)
     */
    String renderDetailLine(Transaction tx, String typeDesc, String catDesc) {
        // Note: this method intentionally accepts String descriptions
        // (already resolved by the caller) rather than the full
        // TransactionType / TransactionCategory entities so that the
        // null-safe defaulting can be performed in one place.
        String description = pickDescription(typeDesc, catDesc, tx.getTranDesc());
        String tranId = tx.getTranId() == null ? "" : tx.getTranId();
        String typeCd = tx.getTranTypeCd() == null ? "" : tx.getTranTypeCd();
        String catCd = tx.getTranCatCd() == null ? "" : String.format("%04d", tx.getTranCatCd());
        BigDecimal amt = normaliseAmount(tx.getTranAmt());
        return padRight(tranId, 16)
                + " | " + padRight(maskPan(tx.getTranCardNum()), 16)
                + " | " + padRight(typeCd, 4)
                + " | " + padRight(catCd, 4)
                + " | " + padRight(description, 20)
                + " | " + String.format("%15.2f", amt);
    }

    /**
     * Convenience overload of {@link #renderDetailLine(Transaction,
     * String, String)} that accepts the full
     * {@link TransactionType} and {@link TransactionCategory}
     * entities. Used by tests and by code paths that want to avoid
     * re-resolving the descriptions.
     *
     * @param tx   the transaction being rendered
     * @param type the resolved {@link TransactionType}, or
     *             {@code null} if no row was found
     * @param cat  the resolved {@link TransactionCategory}, or
     *             {@code null} if no row was found
     * @return the fully formatted, fixed-width detail line (no
     *         trailing newline)
     */
    String renderDetailLine(Transaction tx, TransactionType type, TransactionCategory cat) {
        String typeDesc = (type == null || type.getTranTypeDesc() == null)
                ? "" : type.getTranTypeDesc();
        String catDesc = (cat == null || cat.getTranCatTypeDesc() == null)
                ? "" : cat.getTranCatTypeDesc();
        return renderDetailLine(tx, typeDesc, catDesc);
    }

    /**
     * Writes an account-total line for the supplied card (COBOL:
     * {@code 1120-WRITE-ACCOUNT-TOTALS}, L306-L316). The PAN is
     * masked to last-4 per AAP &sect;0.6.6.
     *
     * @param sb         the report buffer being assembled
     * @param cardNumber the card number whose account is being
     *                   totalled
     * @param acctTotal  the cumulative monetary total for that card
     */
    static void writeAccountTotalLine(StringBuilder sb, String cardNumber,
                                      BigDecimal acctTotal) {
        sb.append(String.format("Total for card %s : %15.2f",
                        maskPan(cardNumber),
                        acctTotal == null ? BigDecimal.ZERO : acctTotal))
                .append('\n');
    }

    /**
     * Writes a page-total line (COBOL:
     * {@code 1110-WRITE-PAGE-TOTALS}, L293-L304).
     *
     * @param sb         the report buffer being assembled
     * @param pageTotal  the cumulative monetary total for the page
     * @param pageNumber the 1-based page number
     */
    static void writePageTotalLine(StringBuilder sb, BigDecimal pageTotal, int pageNumber) {
        sb.append(String.format("Page %03d Total: %15.2f",
                        pageNumber,
                        pageTotal == null ? BigDecimal.ZERO : pageTotal))
                .append('\n');
    }

    /**
     * Writes the grand-total line (COBOL:
     * {@code 1110-WRITE-GRAND-TOTALS}, L318-L322).
     *
     * @param sb         the report buffer being assembled
     * @param grandTotal the cumulative monetary total across all
     *                   pages
     */
    static void writeGrandTotal(StringBuilder sb, BigDecimal grandTotal) {
        sb.append(repeatChar('-', 80)).append('\n');
        sb.append(String.format("GRAND TOTAL: %15.2f",
                        grandTotal == null ? BigDecimal.ZERO : grandTotal))
                .append('\n');
    }

    // =========================================================================
    // Audit-log helper
    // =========================================================================

    /**
     * Emits the {@code REPORT_GENERATED} audit event to the
     * {@link AuditLogService} adapter, carrying the structured
     * payload (start/end date, transaction count, page count, grand
     * total, S3 key) for downstream OpenSearch indexing and
     * CloudWatch metric counting (AAP &sect;0.6.6).
     *
     * @param startDate        the inclusive lower bound of the
     *                         report date window
     * @param endDate          the inclusive upper bound of the
     *                         report date window
     * @param transactionCount the number of transactions included
     * @param pageCount        the number of pages produced
     * @param grandTotal       the cumulative monetary total across
     *                         all pages
     * @param s3Key            the S3 key of the rendered report
     */
    private void emitAuditEvent(LocalDate startDate, LocalDate endDate,
                                int transactionCount, int pageCount,
                                BigDecimal grandTotal, String s3Key) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("startDate", startDate);
        payload.put("endDate", endDate);
        payload.put("transactionCount", transactionCount);
        payload.put("pageCount", pageCount);
        payload.put("grandTotal", grandTotal);
        payload.put("s3Key", s3Key);
        auditLogService.logAuditEvent(
                AUDIT_EVENT_REPORT_GENERATED,
                AUDIT_RESOURCE_TYPE_REPORT,
                s3Key,
                AUDIT_OPERATOR_CBTRN03C,
                payload,
                null);
    }

    // =========================================================================
    // String / numeric helpers
    // =========================================================================

    /**
     * Pads a string on the right with spaces to the supplied width,
     * or truncates to the width if longer.
     *
     * <p>This is the Java equivalent of the COBOL implicit right-pad
     * applied to fixed-width {@code PIC X(n)} fields whenever a
     * shorter literal is moved into the field. The
     * {@code String.format("%-Ns", v)} idiom returns the padded
     * result; the explicit {@code if/else} handles the truncation
     * case so callers get exactly {@code w} characters back even
     * for long inputs.</p>
     *
     * @param s the source string; may be {@code null} (treated as
     *          empty)
     * @param w the target width in characters; must be &gt;= 0
     * @return a string of exactly {@code w} characters
     */
    static String padRight(String s, int w) {
        String v = s == null ? "" : s;
        if (v.length() >= w) {
            return v.substring(0, w);
        }
        return String.format("%-" + w + "s", v);
    }

    /**
     * Returns a string of exactly {@code length} copies of
     * {@code c}. Used for divider lines and centering helpers.
     *
     * @param c      the character to repeat
     * @param length the number of times to repeat; must be &gt;= 0
     * @return the repeated string
     */
    static String repeatChar(char c, int length) {
        if (length <= 0) {
            return "";
        }
        char[] arr = new char[length];
        for (int i = 0; i < length; i++) {
            arr[i] = c;
        }
        return new String(arr);
    }

    /**
     * Centers a string within a fixed-width field by padding with
     * spaces on both sides. The returned string is exactly
     * {@code width} characters long. If the source string is wider
     * than the field, the source is returned unchanged (so callers
     * never receive a truncated value).
     *
     * <p>When the padding is odd, the extra space is placed on the
     * right (e.g., {@code centerIn("HI", 5) == " HI  "}) so that
     * left-leaning alignment of the printable text remains
     * consistent across page-banner emissions.
     *
     * @param s     the source string
     * @param width the target width in characters
     * @return the centered, exactly {@code width}-character string
     */
    static String centerIn(String s, int width) {
        String v = s == null ? "" : s;
        if (v.length() >= width) {
            return v;
        }
        int leftPad = (width - v.length()) / 2;
        int rightPad = width - v.length() - leftPad;
        return repeatChar(' ', leftPad) + v + repeatChar(' ', rightPad);
    }

    /**
     * Masks a card number to the last-4 format
     * {@code "************NNNN"} per PCI-DSS scope (AAP
     * &sect;0.6.6). Returns a 12-asterisk placeholder for null /
     * short inputs so the report layout remains aligned.
     *
     * @param pan the card number ({@code TRAN-CARD-NUM} from
     *            CVTRA05Y); may be {@code null}
     * @return the masked card number (always exactly 16 characters
     *         when input is 16 characters or longer; 12-asterisk
     *         placeholder otherwise)
     */
    static String maskPan(String pan) {
        if (pan == null || pan.length() < 4) {
            return "************";
        }
        return "************" + pan.substring(pan.length() - 4);
    }

    /**
     * Normalises a monetary amount to scale 2 with banker's
     * rounding, mapping {@code null} to {@code 0.00} per the COBOL
     * {@code PIC S9(09)V99 VALUE 0} default semantic.
     *
     * @param amount the source amount; may be {@code null}
     * @return a {@link BigDecimal} with scale 2
     */
    static BigDecimal normaliseAmount(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        }
        return amount.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Picks the most informative description string for a detail
     * line. Prefers the type description; falls back to the category
     * description; falls back to the transaction's own description.
     *
     * @param typeDesc the type-table description (may be empty)
     * @param catDesc  the category-table description (may be empty)
     * @param tranDesc the per-transaction description (may be
     *                 {@code null})
     * @return the chosen description; never {@code null}
     */
    private static String pickDescription(String typeDesc, String catDesc,
                                          String tranDesc) {
        if (typeDesc != null && !typeDesc.isBlank()) {
            return typeDesc;
        }
        if (catDesc != null && !catDesc.isBlank()) {
            return catDesc;
        }
        if (tranDesc != null && !tranDesc.isBlank()) {
            return tranDesc;
        }
        return "";
    }

    /**
     * Safely adds two {@link BigDecimal} values with banker's
     * rounding, throwing {@link OnSizeErrorException} on
     * {@link ArithmeticException} per AAP &sect;0.6.1
     * (replicating COBOL {@code ON SIZE ERROR} semantics on
     * {@code PIC S9(09)V99} overflow). Additionally enforces the
     * COBOL {@code PIC S9(09)V99} ceiling explicitly so that
     * arithmetically valid but PIC-overflowing results are also
     * detected.
     *
     * @param a       the first addend (may be {@code null}, treated
     *                as zero)
     * @param b       the second addend (may be {@code null}, treated
     *                as zero)
     * @param context a human-readable label for the field being
     *                accumulated (e.g.
     *                {@code "WS-PAGE-TOTAL"}); included in the
     *                exception message for diagnostic clarity
     * @return the sum, scaled to 2 decimal places with
     *         {@link RoundingMode#HALF_EVEN}
     * @throws OnSizeErrorException if the addition triggers an
     *                              {@link ArithmeticException} or
     *                              the result exceeds the COBOL
     *                              {@code PIC S9(09)V99} ceiling
     */
    static BigDecimal safeAdd(BigDecimal a, BigDecimal b, String context) {
        try {
            BigDecimal sum = (a == null ? BigDecimal.ZERO : a)
                    .add(b == null ? BigDecimal.ZERO : b)
                    .setScale(2, RoundingMode.HALF_EVEN);
            if (sum.abs().compareTo(MAX_TOTAL) > 0) {
                throw new OnSizeErrorException(
                        "ON SIZE ERROR in " + context
                                + " (computed " + sum
                                + " exceeds PIC S9(09)V99 ceiling)");
            }
            return sum;
        } catch (ArithmeticException e) {
            throw new OnSizeErrorException("ON SIZE ERROR in " + context, e);
        }
    }

    // =========================================================================
    // S3 key construction
    // =========================================================================

    /**
     * Builds the report identifier passed to
     * {@link S3OutputService#writeReport(String, byte[])}. The
     * identifier encodes the date range and a millisecond timestamp
     * for uniqueness across batch runs that may execute on the
     * same calendar day.
     *
     * <p>Format: {@code "yyyy-MM-dd_yyyy-MM-dd-{epoch-millis}"}.
     * Example: {@code "2024-01-01_2024-01-31-1714579200000"}.</p>
     *
     * @param startDate the inclusive lower bound
     * @param endDate   the inclusive upper bound
     * @return the report identifier
     */
    static String buildReportId(LocalDate startDate, LocalDate endDate) {
        return startDate.toString() + "_" + endDate.toString()
                + "-" + System.currentTimeMillis();
    }

    /**
     * Reconstructs the S3 key that
     * {@link S3OutputService#writeReport(String, byte[])} would
     * produce for the supplied {@code reportId}. Mirrors the
     * adapter's internal {@code buildKey} convention exactly:
     * {@code "tranrept/yyyy/MM/dd/{reportId}.rpt"} where the date
     * partition is the local date at upload time.
     *
     * <p>This is a logical reconstruction because the adapter's
     * {@code writeReport} method does not return the key it built;
     * the Java target needs the key value to populate
     * {@link ReportResult#s3Key()}. The computation here mirrors the
     * adapter's date-partition format byte-for-byte.</p>
     *
     * @param reportId the report identifier passed to the adapter
     * @return the logical S3 key
     */
    static String buildLogicalS3Key(String reportId) {
        LocalDate today = LocalDate.now();
        String datePartition = String.format("%04d/%02d/%02d",
                today.getYear(), today.getMonthValue(), today.getDayOfMonth());
        return S3_KEY_PREFIX + "/" + datePartition + "/" + reportId + ".rpt";
    }
}
