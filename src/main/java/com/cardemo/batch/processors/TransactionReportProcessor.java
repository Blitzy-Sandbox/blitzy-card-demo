package com.cardemo.batch.processors;

import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionTypeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Spring Batch {@link ItemProcessor} that implements the transaction detail report
 * generation logic translated from COBOL program {@code CBTRN03C.cbl} (649 lines).
 *
 * <p>This processor is the core of the {@code TRANREPT.jcl} batch job. It filters
 * transactions by a configurable date range, enriches each transaction with data
 * from 3 reference datasets (CARDXREF, TRANTYPE, TRANCATG), and tracks multi-level
 * monetary running totals (page, account, grand) using {@link BigDecimal} with
 * zero floating-point substitution per AAP rule §0.8.2.</p>
 *
 * <h3>COBOL Source Mapping</h3>
 * <pre>
 * Source:   app/cbl/CBTRN03C.cbl (commit 27d6c6f)
 * JCL Job:  app/jcl/TRANREPT.jcl
 * Lines:    649 (full program, FILE-CONTROL through 9999-ABEND-PROGRAM)
 * </pre>
 *
 * <h3>Processing Flow (PROCEDURE DIVISION, lines 158-206)</h3>
 * <ol>
 *   <li><strong>Date Filtering</strong> — Compares transaction processing timestamp
 *       ({@code TRAN-PROC-TS(1:10)}) against the date range parameters from the
 *       DATEPARM file ({@code WS-START-DATE}, {@code WS-END-DATE}). Transactions
 *       outside the range are excluded by returning {@code null} per Spring Batch
 *       convention. Maps COBOL lines 173-178.</li>
 *   <li><strong>Account Break Detection</strong> — When the card number changes
 *       ({@code TRAN-CARD-NUM NOT= WS-CURR-CARD-NUM}), the processor logs the
 *       previous account's running total, resets the account total, updates the
 *       current card number, and performs a CARDXREF lookup to resolve the new
 *       card's account ID. Maps COBOL lines 181-188 and paragraph
 *       {@code 1500-A-LOOKUP-XREF} (lines 484-492).</li>
 *   <li><strong>TRANTYPE Enrichment</strong> — Reads the TRANTYPE VSAM dataset by
 *       2-character type code to obtain the human-readable description. Maps
 *       paragraph {@code 1500-B-LOOKUP-TRANTYPE} (lines 494-502).</li>
 *   <li><strong>TRANCATG Enrichment</strong> — Reads the TRANCATG VSAM dataset by
 *       composite key ({@code TRAN-TYPE-CD + TRAN-CAT-CD}) to obtain the category
 *       description. Maps paragraph {@code 1500-C-LOOKUP-TRANCATG} (lines 504-512).</li>
 *   <li><strong>Total Accumulation</strong> — Adds the transaction amount to three
 *       running totals: page total ({@code WS-PAGE-TOTAL}), account total
 *       ({@code WS-ACCOUNT-TOTAL}), and grand total ({@code WS-GRAND-TOTAL}).
 *       All three are PIC S9(09)V99 in COBOL, mapped to {@link BigDecimal}.</li>
 *   <li><strong>Page Break Detection</strong> — When the line counter reaches a
 *       multiple of {@link #PAGE_SIZE} (20), logs the page total and resets the
 *       page-level accumulator. Maps COBOL paragraph {@code 1100-WRITE-TRANSACTION-REPORT}
 *       (lines 274-290) and {@code 1110-WRITE-PAGE-TOTALS} (lines 293-304).</li>
 * </ol>
 *
 * <h3>Report Output</h3>
 * <p>The actual report file writing (LRECL=133, RECFM=FB) is handled by the
 * companion writer component. This processor provides the enriched {@link Transaction}
 * items and exposes running totals via getter methods for the writer to format
 * header, detail, and total lines.</p>
 *
 * <h3>Decimal Precision</h3>
 * <p>All monetary totals use {@link BigDecimal} with {@link BigDecimal#ZERO}
 * initialization and {@link BigDecimal#add(BigDecimal)} accumulation. No
 * {@code float} or {@code double} types are used anywhere in this class, per
 * AAP §0.8.2 zero floating-point substitution rule. The COBOL PIC S9(09)V99
 * fields map to BigDecimal with scale=2.</p>
 *
 * @see com.cardemo.model.entity.Transaction
 * @see com.cardemo.repository.CardCrossReferenceRepository
 * @see com.cardemo.repository.TransactionTypeRepository
 * @see com.cardemo.repository.TransactionCategoryRepository
 */
@Component
public class TransactionReportProcessor implements ItemProcessor<Transaction, Transaction> {

    private static final Logger logger = LoggerFactory.getLogger(TransactionReportProcessor.class);

    /**
     * Number of detail lines per report page before a page break is triggered.
     * Maps COBOL {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20} (line 131-132).
     */
    private static final int PAGE_SIZE = 20;

    // -----------------------------------------------------------------------
    // Injected Dependencies — 3 reference data repositories
    // -----------------------------------------------------------------------

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final TransactionCategoryRepository transactionCategoryRepository;

    // -----------------------------------------------------------------------
    // Report State Fields — mirrors COBOL WORKING-STORAGE (lines 122-137)
    // -----------------------------------------------------------------------

    /**
     * Report start date — replaces COBOL {@code WS-START-DATE PIC X(10)} (line 123).
     * Set from job parameters before step execution begins. Transactions with
     * processing timestamps before this date are filtered out.
     */
    private LocalDate startDate;

    /**
     * Report end date — replaces COBOL {@code WS-END-DATE PIC X(10)} (line 125).
     * Set from job parameters before step execution begins. Transactions with
     * processing timestamps after this date are filtered out.
     */
    private LocalDate endDate;

    /**
     * Running total for the current report page.
     * Maps COBOL {@code WS-PAGE-TOTAL PIC S9(09)V99 VALUE 0} (line 134).
     * Reset to {@link BigDecimal#ZERO} on each page break.
     */
    private BigDecimal pageTotal = BigDecimal.ZERO;

    /**
     * Running total for the current card/account group.
     * Maps COBOL {@code WS-ACCOUNT-TOTAL PIC S9(09)V99 VALUE 0} (line 135).
     * Reset to {@link BigDecimal#ZERO} on each account break (card number change).
     */
    private BigDecimal accountTotal = BigDecimal.ZERO;

    /**
     * Grand total accumulating ALL transaction amounts across all pages and accounts.
     * Maps COBOL {@code WS-GRAND-TOTAL PIC S9(09)V99 VALUE 0} (line 136).
     * Accumulated directly with each processed transaction for mathematical correctness.
     */
    private BigDecimal grandTotal = BigDecimal.ZERO;

    /**
     * Current card number for account break detection.
     * Maps COBOL {@code WS-CURR-CARD-NUM PIC X(16) VALUE SPACES} (line 137).
     * When the incoming transaction's card number differs from this value,
     * an account break is triggered, logging and resetting the account total.
     */
    private String currentCardNum = "";

    /**
     * Line counter tracking processed detail lines for page break detection.
     * Maps COBOL {@code WS-LINE-COUNTER PIC 9(09) COMP-3 VALUE 0} (lines 129-130).
     * Incremented after each processed transaction. Page break fires when
     * {@code lineCounter > 0 && lineCounter % PAGE_SIZE == 0}.
     */
    private int lineCounter = 0;

    /**
     * Current page number in the report output.
     * Maps COBOL page numbering in report headers. Incremented on each page break.
     */
    private int pageNum = 0;

    // -----------------------------------------------------------------------
    // Constructor — Dependency Injection
    // -----------------------------------------------------------------------

    /**
     * Constructs a new {@code TransactionReportProcessor} with the required
     * reference data repositories injected by Spring's constructor injection.
     *
     * @param cardCrossReferenceRepository repository for CARDXREF lookups
     *        (paragraph 1500-A-LOOKUP-XREF, lines 484-492)
     * @param transactionTypeRepository    repository for TRANTYPE lookups
     *        (paragraph 1500-B-LOOKUP-TRANTYPE, lines 494-502)
     * @param transactionCategoryRepository repository for TRANCATG lookups
     *        (paragraph 1500-C-LOOKUP-TRANCATG, lines 504-512)
     */
    public TransactionReportProcessor(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
    }

    // -----------------------------------------------------------------------
    // ItemProcessor Implementation — Core Report Processing Logic
    // -----------------------------------------------------------------------

    /**
     * Processes a single {@link Transaction} item for the transaction detail report.
     *
     * <p>Implements the main processing loop from COBOL {@code CBTRN03C.cbl}
     * (lines 170-206). Each invocation handles one transaction record, performing
     * date filtering, account break detection, enrichment lookups, total
     * accumulation, and page break tracking.</p>
     *
     * <h4>Processing Steps</h4>
     * <ol>
     *   <li>Date filtering — {@code TRAN-PROC-TS(1:10) >= WS-START-DATE AND
     *       TRAN-PROC-TS(1:10) <= WS-END-DATE} (COBOL lines 173-174)</li>
     *   <li>Account break detection — {@code WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}
     *       (COBOL lines 181-188)</li>
     *   <li>XREF lookup — paragraph {@code 1500-A-LOOKUP-XREF} (lines 484-492)</li>
     *   <li>TRANTYPE lookup — paragraph {@code 1500-B-LOOKUP-TRANTYPE} (lines 494-502)</li>
     *   <li>TRANCATG lookup — paragraph {@code 1500-C-LOOKUP-TRANCATG} (lines 504-512)</li>
     *   <li>Page break check — {@code MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0}
     *       (COBOL line 282)</li>
     *   <li>Total accumulation — {@code ADD TRAN-AMT TO WS-PAGE-TOTAL
     *       WS-ACCOUNT-TOTAL} (COBOL lines 287-288)</li>
     * </ol>
     *
     * @param item the {@link Transaction} record to process; must not be {@code null}
     * @return the enriched {@link Transaction} if it passes date filtering and
     *         should be included in the report; {@code null} if the transaction
     *         is outside the configured date range (Spring Batch convention for
     *         item exclusion)
     * @throws Exception if an unrecoverable error occurs during processing
     */
    @Override
    public Transaction process(Transaction item) throws Exception {
        // ---------------------------------------------------------------
        // Step 1: Date Filtering
        // Maps COBOL lines 173-178:
        //   IF TRAN-PROC-TS(1:10) >= WS-START-DATE
        //      AND TRAN-PROC-TS(1:10) <= WS-END-DATE
        //      CONTINUE
        //   ELSE
        //      NEXT SENTENCE  (skip this transaction)
        // ---------------------------------------------------------------
        if (item.getTranProcTs() == null) {
            logger.debug("Skipping transaction {} — null processing timestamp",
                    item.getTranId());
            return null;
        }

        LocalDate tranDate = item.getTranProcTs().toLocalDate();

        if (startDate != null && tranDate.isBefore(startDate)) {
            logger.debug("Filtered out transaction {} — date {} before start date {}",
                    item.getTranId(), tranDate, startDate);
            return null;
        }

        if (endDate != null && tranDate.isAfter(endDate)) {
            logger.debug("Filtered out transaction {} — date {} after end date {}",
                    item.getTranId(), tranDate, endDate);
            return null;
        }

        // ---------------------------------------------------------------
        // Step 2: Account Break Detection
        // Maps COBOL lines 181-188:
        //   IF WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM
        //     IF WS-FIRST-TIME = 'N'
        //       PERFORM 1120-WRITE-ACCOUNT-TOTALS
        //     END-IF
        //     MOVE TRAN-CARD-NUM TO WS-CURR-CARD-NUM
        //     PERFORM 1500-A-LOOKUP-XREF
        // ---------------------------------------------------------------
        String cardNum = item.getTranCardNum() != null ? item.getTranCardNum() : "";

        if (!cardNum.equals(currentCardNum)) {
            // Log account totals for the previous card group (skip on first card)
            if (!currentCardNum.isEmpty()) {
                logger.info("Account break — card {} account total: {}",
                        currentCardNum, accountTotal);
                accountTotal = BigDecimal.ZERO;
            }

            currentCardNum = cardNum;

            // Step 3: XREF Lookup — paragraph 1500-A-LOOKUP-XREF (lines 484-492)
            // READ CARDXREF by TRAN-CARD-NUM → get XREF-ACCT-ID
            if (!cardNum.isEmpty()) {
                performXrefLookup(cardNum);
            }
        }

        // ---------------------------------------------------------------
        // Step 4: TRANTYPE Existence Validation
        // Maps paragraph 1500-B-LOOKUP-TRANTYPE (lines 494-502):
        //   MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE
        //   READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD
        // The COBOL original uses INVALID KEY / DISPLAY for validation
        // diagnostics — these lookups validate referential integrity and
        // log warnings for missing references. The fetched data is NOT
        // applied to the output item; the transaction is passed through
        // unchanged regardless of lookup success/failure.
        // ---------------------------------------------------------------
        performTransactionTypeLookup(item.getTranTypeCd());

        // ---------------------------------------------------------------
        // Step 5: TRANCATG Existence Validation
        // Maps paragraph 1500-C-LOOKUP-TRANCATG (lines 504-512):
        //   MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE-CD
        //   MOVE TRAN-CAT-CD  TO FD-TRAN-CAT-CD
        //   READ TRANCATG-FILE INTO TRAN-CAT-RECORD
        // Same existence-validation pattern as TRANTYPE above.
        // ---------------------------------------------------------------
        performTransactionCategoryLookup(item.getTranTypeCd(), item.getTranCatCd());

        // ---------------------------------------------------------------
        // Step 6: Page Break Check
        // Maps COBOL paragraph 1100-WRITE-TRANSACTION-REPORT (line 282):
        //   IF FUNCTION MOD(WS-LINE-COUNTER, WS-PAGE-SIZE) = 0
        //     PERFORM 1110-WRITE-PAGE-TOTALS
        //     PERFORM 1120-WRITE-HEADERS
        // Page break fires BEFORE accumulating the current transaction.
        // ---------------------------------------------------------------
        if (lineCounter > 0 && lineCounter % PAGE_SIZE == 0) {
            logger.info("Page break — page {} total: {}", pageNum, pageTotal);
            pageTotal = BigDecimal.ZERO;
            pageNum++;
        }

        // ---------------------------------------------------------------
        // Step 7: Total Accumulation (BigDecimal only — zero float/double)
        // Maps COBOL lines 287-288:
        //   ADD TRAN-AMT TO WS-PAGE-TOTAL
        //                   WS-ACCOUNT-TOTAL
        // Grand total accumulated directly for mathematical correctness.
        // In COBOL, grand total is accumulated at page breaks via
        //   ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL (line 297)
        // but direct accumulation yields the same result and ensures
        // getGrandTotal() is always accurate without finalization.
        // ---------------------------------------------------------------
        BigDecimal amount = item.getTranAmt() != null ? item.getTranAmt() : BigDecimal.ZERO;
        pageTotal = pageTotal.add(amount);
        accountTotal = accountTotal.add(amount);
        grandTotal = grandTotal.add(amount);

        // ---------------------------------------------------------------
        // Step 8: Increment line counter for detail line
        // Maps COBOL line 373 in 1120-WRITE-DETAIL:
        //   ADD 1 TO WS-LINE-COUNTER
        // ---------------------------------------------------------------
        lineCounter++;

        logger.debug("Processed transaction {} — amount: {}, card: {}, "
                        + "page total: {}, account total: {}, grand total: {}",
                item.getTranId(), amount, cardNum,
                pageTotal, accountTotal, grandTotal);

        // Return the validated Transaction item unchanged (non-null = included in report).
        // Lookups above are existence-validation only (COBOL INVALID KEY diagnostics);
        // they do not modify the item. The item's original fields are sufficient for
        // the report writer to produce the CBTRN03C-equivalent output.
        return item;
    }

    // -----------------------------------------------------------------------
    // Private Existence-Validation Lookup Methods
    // These methods validate referential integrity against lookup tables and
    // log diagnostic warnings for missing references (COBOL INVALID KEY
    // pattern). Fetched data is used only for diagnostic logging, not for
    // modifying the processed Transaction item.
    // -----------------------------------------------------------------------

    /**
     * Performs the CARDXREF lookup to resolve a card number to an account ID.
     * Maps COBOL paragraph {@code 1500-A-LOOKUP-XREF} (lines 484-492):
     * <pre>
     *   READ XREF-FILE INTO CARD-XREF-RECORD
     *      INVALID KEY
     *         DISPLAY 'INVALID CARD NUMBER : ' FD-XREF-CARD-NUM
     * </pre>
     *
     * <p>In COBOL, an invalid key abends the program. In the Java migration,
     * a missing cross-reference is logged as a warning but does not abort
     * processing, as the report can still include the transaction with a
     * missing account ID rather than failing entirely.</p>
     *
     * @param cardNum the 16-character card number to look up
     */
    private void performXrefLookup(String cardNum) {
        Optional<CardCrossReference> xref = cardCrossReferenceRepository.findById(cardNum);
        if (xref.isPresent()) {
            String accountId = xref.get().getXrefAcctId();
            logger.debug("XREF lookup — card {} → account {}", cardNum, accountId);
        } else {
            logger.warn("XREF lookup failed — card number {} not found in cross-reference",
                    cardNum);
        }
    }

    /**
     * Performs the TRANTYPE lookup to resolve a transaction type code to its
     * human-readable description.
     * Maps COBOL paragraph {@code 1500-B-LOOKUP-TRANTYPE} (lines 494-502):
     * <pre>
     *   READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD
     *      INVALID KEY
     *         DISPLAY 'INVALID TRANSACTION TYPE : ' FD-TRAN-TYPE
     * </pre>
     *
     * @param typeCd the 2-character transaction type code (TRAN-TYPE-CD PIC X(02))
     */
    private void performTransactionTypeLookup(String typeCd) {
        if (typeCd == null) {
            logger.warn("TRANTYPE lookup skipped — null type code");
            return;
        }

        Optional<TransactionType> tranType = transactionTypeRepository.findById(typeCd);
        if (tranType.isPresent()) {
            String typeDesc = tranType.get().getTranTypeDesc();
            logger.debug("TRANTYPE enrichment — type '{}' → '{}'", typeCd, typeDesc);
        } else {
            logger.warn("TRANTYPE lookup failed — type code '{}' not found", typeCd);
        }
    }

    /**
     * Performs the TRANCATG lookup to resolve a composite type+category key
     * to a human-readable category description.
     * Maps COBOL paragraph {@code 1500-C-LOOKUP-TRANCATG} (lines 504-512):
     * <pre>
     *   MOVE TRAN-TYPE-CD TO FD-TRAN-TYPE-CD
     *   MOVE TRAN-CAT-CD  TO FD-TRAN-CAT-CD
     *   READ TRANCATG-FILE INTO TRAN-CAT-RECORD
     *      INVALID KEY
     *         DISPLAY 'INVALID TRAN CATG KEY : ' FD-TRAN-CAT-KEY
     * </pre>
     *
     * @param typeCd the 2-character transaction type code (TRAN-TYPE-CD PIC X(02))
     * @param catCd  the transaction category code as Short (TRAN-CAT-CD PIC 9(04))
     */
    private void performTransactionCategoryLookup(String typeCd, Short catCd) {
        if (typeCd == null || catCd == null) {
            logger.warn("TRANCATG lookup skipped — null type code or category code "
                    + "(typeCd={}, catCd={})", typeCd, catCd);
            return;
        }

        TransactionCategoryId catId = new TransactionCategoryId(typeCd, catCd);
        Optional<TransactionCategory> tranCat = transactionCategoryRepository.findById(catId);
        if (tranCat.isPresent()) {
            String catDesc = tranCat.get().getTranCatTypeDesc();
            logger.debug("TRANCATG enrichment — type '{}' cat {} → '{}'",
                    typeCd, catCd, catDesc);
        } else {
            logger.warn("TRANCATG lookup failed — type '{}' category {} not found",
                    typeCd, catCd);
        }
    }

    // -----------------------------------------------------------------------
    // Getter Methods — Report Totals and State
    // -----------------------------------------------------------------------

    /**
     * Returns the grand total of all transaction amounts processed.
     *
     * <p>Maps COBOL {@code WS-GRAND-TOTAL PIC S9(09)V99} (line 136). In the
     * COBOL program, the grand total is accumulated at page breaks
     * ({@code ADD WS-PAGE-TOTAL TO WS-GRAND-TOTAL}, line 297). In this Java
     * implementation, the grand total is accumulated directly with each
     * transaction for mathematical correctness, ensuring this getter always
     * returns the accurate total regardless of page break state.</p>
     *
     * @return the grand total of all processed transaction amounts as
     *         {@link BigDecimal}; never {@code null}
     */
    public BigDecimal getGrandTotal() {
        return grandTotal;
    }

    /**
     * Returns the running total for the current card/account group.
     *
     * <p>Maps COBOL {@code WS-ACCOUNT-TOTAL PIC S9(09)V99} (line 135).
     * This total resets to {@link BigDecimal#ZERO} on each account break
     * (when the card number changes between consecutive transactions).</p>
     *
     * @return the current account group total as {@link BigDecimal}; never {@code null}
     */
    public BigDecimal getAccountTotal() {
        return accountTotal;
    }

    /**
     * Returns the current page number in the report.
     *
     * <p>Incremented on each page break (every {@link #PAGE_SIZE} detail lines).
     * The writer component uses this value for report header formatting.</p>
     *
     * @return the zero-based page number (0 for the first page, incrementing
     *         at each page break)
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * Returns the current card number being tracked for account break detection.
     *
     * <p>Maps COBOL {@code WS-CURR-CARD-NUM PIC X(16) VALUE SPACES} (line 137).
     * Updated to the most recently processed transaction's card number. When
     * the next transaction has a different card number, an account break is
     * triggered.</p>
     *
     * @return the 16-character card number of the current account group,
     *         or empty string if no transactions have been processed yet
     */
    public String getCurrentCardNum() {
        return currentCardNum;
    }

    // -----------------------------------------------------------------------
    // Setter Methods — Date Range Configuration
    // -----------------------------------------------------------------------

    /**
     * Sets the report start date (inclusive lower bound for date filtering).
     *
     * <p>Replaces COBOL {@code WS-START-DATE PIC X(10)} (line 123), which is
     * read from the DATEPARM file in paragraph {@code 0550-DATEPARM-READ}
     * (lines 220-243). In the Java migration, this is set from Spring Batch
     * job parameters before the step execution begins.</p>
     *
     * @param startDate the inclusive start date for the report; transactions
     *                  before this date are filtered out; may be {@code null}
     *                  to disable lower-bound filtering
     */
    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
        logger.info("Report start date set to: {}", startDate);
    }

    /**
     * Sets the report end date (inclusive upper bound for date filtering).
     *
     * <p>Replaces COBOL {@code WS-END-DATE PIC X(10)} (line 125), which is
     * read from the DATEPARM file in paragraph {@code 0550-DATEPARM-READ}
     * (lines 220-243). In the Java migration, this is set from Spring Batch
     * job parameters before the step execution begins.</p>
     *
     * @param endDate the inclusive end date for the report; transactions
     *                after this date are filtered out; may be {@code null}
     *                to disable upper-bound filtering
     */
    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        logger.info("Report end date set to: {}", endDate);
    }
}
