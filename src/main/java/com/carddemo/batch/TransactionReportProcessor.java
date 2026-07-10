package com.carddemo.batch;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryType;
import com.carddemo.entity.TransactionCategoryTypeId;
import com.carddemo.entity.TransactionType;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryTypeRepository;
import com.carddemo.repository.TransactionTypeRepository;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chunk-step {@link ItemProcessor} for the CardDemo transaction-detail report — processor
 * <strong>#5 of 5</strong> in the batch pipeline, wired into {@code TransactionReportJob}.
 *
 * <p><strong>COBOL lineage (reference-only, source SHA {@code 27d6c6f}).</strong> This processor
 * is the Java translation of two collaborating legacy artifacts:</p>
 * <ul>
 *   <li>The DFSORT step of {@code app/jcl/TRANREPT.jcl} / {@code app/proc/TRANREPT.prc}, whose
 *       {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}
 *       pre-filters the transaction file to the reporting date window
 *       ({@code PARM-START-DATE='2022-01-01'}, {@code PARM-END-DATE='2022-07-06'}); and</li>
 *   <li>The {@code 1120-WRITE-DETAIL} paragraph of the batch program {@code app/cbl/CBTRN03C.cbl}
 *       (with its enrichment reads {@code 1500-A-LOOKUP-XREF}, {@code 1500-B-LOOKUP-TRANTYPE} and
 *       {@code 1500-C-LOOKUP-TRANCATG}), which joins each surviving transaction to its owning
 *       account, transaction-type description and transaction-category description before emitting
 *       a formatted detail line.</li>
 * </ul>
 *
 * <h2>Two responsibilities</h2>
 * <ol>
 *   <li><strong>INCLUDE-COND date-window filter (AAP §0.8.5).</strong> A transaction whose
 *       processing date falls outside the inclusive window {@code [startDate, endDate]} is dropped
 *       by returning {@code null} — Spring Batch's idiomatic "filter this item out of the chunk"
 *       signal. This reproduces the DFSORT {@code INCLUDE COND} (keep iff
 *       {@code GE start AND LE end}) and the equivalent runtime guard in the {@code CBTRN03C}
 *       main loop ({@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE}). Filtering
 *       is always a {@code null} return, never a thrown exception.</li>
 *   <li><strong>Enrichment + detail-line build ({@code 1120-WRITE-DETAIL}).</strong> For a
 *       surviving transaction the processor resolves the account id (via the card cross-reference),
 *       the transaction-type description and the transaction-category description, then builds a
 *       structured {@link ReportDetailLine}. The COBOL {@code MOVE} sequence maps one-to-one:
 *       <pre>
 *         MOVE TRAN-ID            TO TRAN-REPORT-TRANS-ID    -&gt; transactionId
 *         MOVE XREF-ACCT-ID       TO TRAN-REPORT-ACCOUNT-ID  -&gt; accountId
 *         MOVE TRAN-TYPE-CD       TO TRAN-REPORT-TYPE-CD     -&gt; typeCode
 *         MOVE TRAN-TYPE-DESC     TO TRAN-REPORT-TYPE-DESC   -&gt; typeDescription
 *         MOVE TRAN-CAT-CD        TO TRAN-REPORT-CAT-CD      -&gt; categoryCode
 *         MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC    -&gt; categoryDescription
 *         MOVE TRAN-SOURCE        TO TRAN-REPORT-SOURCE      -&gt; source
 *         MOVE TRAN-AMT           TO TRAN-REPORT-AMT         -&gt; amount
 *       </pre></li>
 * </ol>
 *
 * <h2>What deliberately lives elsewhere</h2>
 * <p>The COBOL control-break totals — the page total ({@code WS-PAGE-TOTAL}), the per-account
 * total ({@code WS-ACCOUNT-TOTAL}) and the grand total ({@code WS-GRAND-TOTAL}) accumulated in
 * paragraphs {@code 1100}/{@code 1110}/{@code 1120-WRITE-ACCOUNT-TOTALS}) — together with header
 * emission and page-break formatting require cross-item state and therefore belong to the writer
 * ({@code TransactionReportItemWriter}), not to this stateless per-item processor. This class
 * computes no totals.</p>
 *
 * <h2>Not-found / parity semantics</h2>
 * <p>The legacy lookups abend ({@code 9999-ABEND-PROGRAM}) on a missing key. This processor
 * refines that behaviour to keep the report well-formed rather than terminate the batch:</p>
 * <ul>
 *   <li><strong>Missing card cross-reference</strong> — the transaction is <em>excluded</em>
 *       (returns {@code null}) and logged at {@code WARN} with the card number <em>masked</em> to
 *       its last four digits (CWE-532; see {@link #maskCardNumber(String)}). Emitting a detail line
 *       with no account id would corrupt the report and the writer's per-account control break, so
 *       the row is dropped instead (the chosen parity refinement is recorded in
 *       {@code docs/decision-log.md}).</li>
 *   <li><strong>Missing transaction-type or category-type description</strong> — resolves to the
 *       empty string, matching the COBOL {@code INITIALIZE TRANSACTION-DETAIL-REPORT} blank
 *       default that precedes the {@code MOVE}s.</li>
 *   <li><strong>Null / short / malformed processing date</strong> — treated as outside the window
 *       and excluded (returns {@code null}), logged at {@code DEBUG}.</li>
 * </ul>
 *
 * <h2>Decimal fidelity (AAP §0.8.2)</h2>
 * <p>The monetary {@code amount} is carried as {@link java.math.BigDecimal} end-to-end and is never
 * a {@code double}/{@code float}; the {@link ReportDetailLine} canonical constructor re-normalizes
 * it to scale {@code 2} ({@code TRAN-AMT PIC S9(09)V99}).</p>
 *
 * <h2>Scope and lifecycle</h2>
 * <p>The bean is {@link StepScope step-scoped} so the reporting date window can be read from job
 * parameters supplied per run (for the SQS-triggered launch, {@code ReportJobLauncher} passes
 * {@code startDate}/{@code endDate}); it falls back to configuration properties and finally to the
 * legacy JCL {@code PARM} defaults. All collaborators are supplied by constructor injection, and
 * the date window is parsed to {@link LocalDate} exactly once at construction. Per-item processing
 * holds no mutable state, so an instance is safe for the single step thread that owns it.</p>
 *
 * @see ReportDetailLine
 * @see CardXrefRepository
 * @see TransactionTypeRepository
 * @see TransactionCategoryTypeRepository
 */
@Component
@StepScope
public class TransactionReportProcessor implements ItemProcessor<Transaction, ReportDetailLine> {

    /** SLF4J logger for filter/enrichment diagnostics (structured JSON with MDC correlationId). */
    private static final Logger log = LoggerFactory.getLogger(TransactionReportProcessor.class);

    /**
     * Number of leading characters of {@code TRAN-PROC-TS} ({@code PIC X(26)},
     * {@code yyyy-mm-dd-hh.mm.ss.ffffff}) that constitute {@code TRAN-PROC-DT} — the
     * {@code yyyy-MM-dd} date the DFSORT {@code INCLUDE COND} compares (offset 305, length 10, CH).
     */
    private static final int PROC_DATE_LENGTH = 10;

    /**
     * Blank description default, mirroring the COBOL {@code INITIALIZE TRANSACTION-DETAIL-REPORT}
     * that clears the report group to spaces before the {@code 1120-WRITE-DETAIL} {@code MOVE}s;
     * used whenever a type/category description lookup finds no matching reference row.
     */
    private static final String NO_DESCRIPTION = "";

    /**
     * Character substituted for each concealed Primary Account Number (PAN) position when a card
     * number is written to a log line. Mirrors the PAN-masking convention of the response DTOs
     * ({@code TransactionViewResponse}, {@code StatementTransactionDto}).
     */
    private static final String MASK_CHARACTER = "*";

    /**
     * Number of trailing PAN digits left visible when a card number is masked for logging; every
     * earlier character is replaced with {@value #MASK_CHARACTER} (for example
     * {@code "4111111111111111"} becomes {@code "************1111"}).
     */
    private static final int VISIBLE_CARD_DIGITS = 4;

    /** Card cross-reference lookup ({@code 1500-A-LOOKUP-XREF}) resolving {@code XREF-ACCT-ID}. */
    private final CardXrefRepository cardXrefRepository;

    /** Transaction-type lookup ({@code 1500-B-LOOKUP-TRANTYPE}) resolving {@code TRAN-TYPE-DESC}. */
    private final TransactionTypeRepository transactionTypeRepository;

    /**
     * Transaction-category-type lookup ({@code 1500-C-LOOKUP-TRANCATG}) resolving
     * {@code TRAN-CAT-TYPE-DESC} by the composite key ({@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}).
     */
    private final TransactionCategoryTypeRepository transactionCategoryTypeRepository;

    /** Inclusive lower bound of the reporting window ({@code PARM-START-DATE} / {@code WS-START-DATE}). */
    private final LocalDate startDate;

    /** Inclusive upper bound of the reporting window ({@code PARM-END-DATE} / {@code WS-END-DATE}). */
    private final LocalDate endDate;

    /**
     * Creates a step-scoped processor bound to the reporting date window for the current run.
     *
     * <p>The window bounds originate from the legacy JCL symbols
     * {@code PARM-START-DATE='2022-01-01'} / {@code PARM-END-DATE='2022-07-06'}. Each is resolved
     * with a three-level fallback expressed in the SpEL/property placeholder: the job parameter of
     * the same name if present, otherwise the configuration property
     * {@code carddemo.batch.report.start-date} / {@code carddemo.batch.report.end-date}, otherwise
     * the JCL default baked into the placeholder. The strings are parsed to {@link LocalDate}
     * exactly once here (fail-fast: an invalid or reversed window raises
     * {@link IllegalArgumentException} at bean creation rather than per item).</p>
     *
     * @param cardXrefRepository                the card cross-reference repository (account lookup);
     *                                          must not be {@code null}
     * @param transactionTypeRepository         the transaction-type reference repository
     *                                          (type-description lookup); must not be {@code null}
     * @param transactionCategoryTypeRepository the transaction-category-type reference repository
     *                                          (category-description lookup); must not be {@code null}
     * @param startDate                         the inclusive window start ({@code yyyy-MM-dd}),
     *                                          resolved from the {@code startDate} job parameter,
     *                                          the {@code carddemo.batch.report.start-date} property,
     *                                          or the JCL default {@code 2022-01-01}
     * @param endDate                           the inclusive window end ({@code yyyy-MM-dd}),
     *                                          resolved from the {@code endDate} job parameter, the
     *                                          {@code carddemo.batch.report.end-date} property, or
     *                                          the JCL default {@code 2022-07-06}
     * @throws IllegalArgumentException if either bound is {@code null}, is not a valid ISO-8601
     *                                  ({@code yyyy-MM-dd}) date, or if {@code startDate} is after
     *                                  {@code endDate}
     */
    public TransactionReportProcessor(
            CardXrefRepository cardXrefRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryTypeRepository transactionCategoryTypeRepository,
            @Value("#{jobParameters['startDate'] ?: '${carddemo.batch.report.start-date:2022-01-01}'}")
            String startDate,
            @Value("#{jobParameters['endDate'] ?: '${carddemo.batch.report.end-date:2022-07-06}'}")
            String endDate) {
        this.cardXrefRepository = cardXrefRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryTypeRepository = transactionCategoryTypeRepository;
        this.startDate = parseWindowBound(startDate, "startDate");
        this.endDate = parseWindowBound(endDate, "endDate");
        if (this.startDate.isAfter(this.endDate)) {
            throw new IllegalArgumentException(
                    "Report date window is inverted: startDate (" + this.startDate
                            + ") must not be after endDate (" + this.endDate + ")");
        }
        log.debug("TransactionReportProcessor initialized for reporting window [{} .. {}]",
                this.startDate, this.endDate);
    }

    /**
     * Applies the INCLUDE-COND window filter and, for surviving rows, builds the enriched
     * transaction-detail report line ({@code 1120-WRITE-DETAIL}).
     *
     * <p>Processing steps, in order:</p>
     * <ol>
     *   <li>Derive the processing date {@code TRAN-PROC-DT} (first {@value #PROC_DATE_LENGTH}
     *       characters of {@code TRAN-PROC-TS}); a {@code null}, short or malformed timestamp is
     *       treated as outside the window and the item is filtered out ({@code null}).</li>
     *   <li>Filter: an item whose date is before {@link #startDate} or after {@link #endDate} is
     *       filtered out ({@code null}). Boundary dates equal to either bound are kept, matching
     *       {@code GE start AND LE end}.</li>
     *   <li>Enrich: resolve the account id from the card cross-reference (a missing cross-reference
     *       filters the item out), then the transaction-type and category-type descriptions
     *       (blank when the reference row is absent).</li>
     *   <li>Build and return a {@link ReportDetailLine} carrying the eight
     *       {@code TRAN-REPORT-*} fields; the amount stays a scale-2 {@link java.math.BigDecimal}.</li>
     * </ol>
     *
     * @param tx the transaction read from the (ordered) report input; guaranteed non-{@code null}
     *           by the Spring Batch chunk contract
     * @return the enriched {@link ReportDetailLine}, or {@code null} to filter the transaction out
     *         of the report (outside the date window, an unparseable processing date, or a missing
     *         card cross-reference)
     */
    @Override
    public ReportDetailLine process(Transaction tx) {
        // (1) INCLUDE-COND date-window filter (DFSORT INCLUDE / CBTRN03C main-loop guard).
        LocalDate procDate = extractProcessingDate(tx);
        if (procDate == null) {
            return null;
        }
        // Keep iff GE startDate AND LE endDate; the inclusive bounds themselves are retained.
        if (procDate.isBefore(startDate) || procDate.isAfter(endDate)) {
            log.debug("Excluding transaction {}: processing date {} is outside window [{} .. {}]",
                    tx.getTranId(), procDate, startDate, endDate);
            return null;
        }

        // (2) Enrichment — 1120-WRITE-DETAIL / 1500-A-LOOKUP-XREF: resolve XREF-ACCT-ID.
        String cardNumber = tx.getTranCardNum();
        if (cardNumber == null) {
            log.warn("Excluding transaction {}: card number is null, cannot resolve account id",
                    tx.getTranId());
            return null;
        }
        Optional<CardXref> crossReference = cardXrefRepository.findById(cardNumber);
        if (crossReference.isEmpty()) {
            // Security (CWE-532): the card number is a PAN — mask it to its last four digits before
            // logging so a full 16-digit card number is never written to a log line, consistent with
            // the PAN-masking convention applied across the response DTOs.
            log.warn("Excluding transaction {}: no card cross-reference for card number {}",
                    tx.getTranId(), maskCardNumber(cardNumber));
            return null;
        }
        Long accountId = crossReference.get().getXrefAcctId();

        // 1500-B-LOOKUP-TRANTYPE and 1500-C-LOOKUP-TRANCATG: descriptions (blank when absent).
        String typeCode = tx.getTranTypeCd();
        Integer categoryCode = tx.getTranCatCd();
        String typeDescription = resolveTypeDescription(typeCode);
        String categoryDescription = resolveCategoryDescription(typeCode, categoryCode);

        // (3) Build the structured detail line — component order matches ReportDetailLine's
        // canonical constructor. Totals/headers are the writer's responsibility, not this one's.
        ReportDetailLine detailLine = new ReportDetailLine(
                tx.getTranId(),
                accountId,
                typeCode,
                typeDescription,
                categoryCode,
                categoryDescription,
                tx.getTranSource(),
                tx.getTranAmt());

        log.trace("Built report detail line for transaction {} (account {})",
                tx.getTranId(), accountId);
        return detailLine;
    }

    /**
     * Extracts {@code TRAN-PROC-DT} (the {@code yyyy-MM-dd} processing date) from a transaction's
     * {@code TRAN-PROC-TS} timestamp text, returning {@code null} when it cannot be derived.
     *
     * <p>The date is the first {@value #PROC_DATE_LENGTH} characters of the 26-character timestamp.
     * A {@code null} or too-short timestamp, or a value whose leading characters are not a valid
     * ISO-8601 date, yields {@code null} — the caller treats that as "outside the window" and
     * filters the item out. Because ISO {@code yyyy-MM-dd} text sorts identically lexically and
     * chronologically, the {@link LocalDate} comparison faithfully reproduces the DFSORT
     * character ({@code CH}) comparison used by the legacy {@code INCLUDE COND}.</p>
     *
     * @param tx the transaction being processed
     * @return the parsed processing date, or {@code null} if it is absent, too short or malformed
     */
    private static LocalDate extractProcessingDate(Transaction tx) {
        String timestamp = tx.getTranProcTs();
        if (timestamp == null || timestamp.length() < PROC_DATE_LENGTH) {
            log.debug("Excluding transaction {}: processing timestamp is null or shorter than {} characters",
                    tx.getTranId(), PROC_DATE_LENGTH);
            return null;
        }
        String datePart = timestamp.substring(0, PROC_DATE_LENGTH);
        try {
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException ex) {
            log.debug("Excluding transaction {}: processing date '{}' is not a valid ISO-8601 (yyyy-MM-dd) date",
                    tx.getTranId(), datePart);
            return null;
        }
    }

    /**
     * Resolves the transaction-type description ({@code TRAN-TYPE-DESC}) for the report line,
     * reproducing {@code 1500-B-LOOKUP-TRANTYPE} followed by the {@code 1120-WRITE-DETAIL}
     * {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC}.
     *
     * @param tranTypeCd the transaction type code ({@code TRAN-TYPE-CD}); may be {@code null}
     * @return the type description, or the empty string when the code is {@code null} or no
     *         reference row exists (the COBOL {@code INITIALIZE} blank default)
     */
    private String resolveTypeDescription(String tranTypeCd) {
        if (tranTypeCd == null) {
            return NO_DESCRIPTION;
        }
        return transactionTypeRepository.findById(tranTypeCd)
                .map(TransactionType::getTranTypeDesc)
                .orElse(NO_DESCRIPTION);
    }

    /**
     * Resolves the transaction-category-type description ({@code TRAN-CAT-TYPE-DESC}) for the
     * report line, reproducing {@code 1500-C-LOOKUP-TRANCATG} (a keyed read of the composite
     * {@code TRAN-CAT-KEY} = {@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD}) followed by the
     * {@code 1120-WRITE-DETAIL} {@code MOVE TRAN-CAT-TYPE-DESC TO TRAN-REPORT-CAT-DESC}.
     *
     * @param tranTypeCd the transaction type code ({@code TRAN-TYPE-CD}); may be {@code null}
     * @param tranCatCd  the transaction category code ({@code TRAN-CAT-CD}); may be {@code null}
     * @return the category description, or the empty string when either key part is {@code null}
     *         or no reference row exists (the COBOL {@code INITIALIZE} blank default)
     */
    private String resolveCategoryDescription(String tranTypeCd, Integer tranCatCd) {
        if (tranTypeCd == null || tranCatCd == null) {
            return NO_DESCRIPTION;
        }
        TransactionCategoryTypeId key = new TransactionCategoryTypeId(tranTypeCd, tranCatCd);
        return transactionCategoryTypeRepository.findById(key)
                .map(TransactionCategoryType::getTranCatTypeDesc)
                .orElse(NO_DESCRIPTION);
    }

    /**
     * Masks a Primary Account Number (PAN) so that only the final {@value #VISIBLE_CARD_DIGITS}
     * characters remain visible, replacing every earlier character with {@value #MASK_CHARACTER}
     * (for example {@code "4111111111111111"} becomes {@code "************1111"}). This prevents a
     * full card number — CWE-532 sensitive data — from ever being written to a log line, mirroring
     * the PAN-masking convention already applied by the response DTOs ({@code TransactionViewResponse},
     * {@code StatementTransactionDto}).
     *
     * <p>Surrounding whitespace from fixed-width space padding is stripped first; a {@code null}
     * value, or one no longer than {@value #VISIBLE_CARD_DIGITS} characters, is returned unchanged
     * because there are no additional leading characters to conceal.</p>
     *
     * <p>Declared {@code static} because it depends on no instance state.</p>
     *
     * @param pan the raw card number ({@code TRAN-CARD-NUM}); may be {@code null}
     * @return the masked PAN, or the original value when there is nothing to mask
     */
    private static String maskCardNumber(String pan) {
        if (pan == null) {
            return null;
        }
        String normalized = pan.strip();
        int length = normalized.length();
        if (length <= VISIBLE_CARD_DIGITS) {
            return normalized;
        }
        return MASK_CHARACTER.repeat(length - VISIBLE_CARD_DIGITS)
                + normalized.substring(length - VISIBLE_CARD_DIGITS);
    }

    /**
     * Parses a reporting-window bound, failing fast with a clear message on invalid configuration.
     *
     * <p>Declared {@code static} so the constructor can call it without leaking {@code this}
     * (avoids the {@code -Xlint:this-escape} warning under the zero-warning build, Gate 2).</p>
     *
     * @param value         the raw bound value resolved from the job parameter, property, or JCL
     *                      default; leading/trailing whitespace is tolerated
     * @param parameterName the logical name of the bound ({@code "startDate"} / {@code "endDate"}),
     *                      used only for diagnostics
     * @return the parsed {@link LocalDate}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid ISO-8601
     *                                  ({@code yyyy-MM-dd}) date
     */
    private static LocalDate parseWindowBound(String value, String parameterName) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Report date-window bound '" + parameterName + "' must not be null");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "Report date-window bound '" + parameterName
                            + "' is not a valid ISO-8601 (yyyy-MM-dd) date: '" + value + "'", ex);
        }
    }
}
