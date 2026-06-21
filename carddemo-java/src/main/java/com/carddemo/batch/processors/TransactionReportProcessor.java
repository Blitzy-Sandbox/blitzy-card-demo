package com.carddemo.batch.processors;

import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionType;
import com.carddemo.model.key.TransactionCategoryId;
import com.carddemo.observability.MetricsConfig;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.service.shared.DateValidationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-record {@link ItemProcessor} for the transaction-report stage of the batch
 * pipeline (lineage: AWS CardDemo program {@code CBTRN03C}, source commit
 * {@code 27d6c6f}).
 *
 * <p>{@code CBTRN03C} reads the date-filtered, card-ordered posted-transaction
 * stream produced by the {@code TRANREPT} job and prints a formatted transaction
 * detail report. This component models the program's <em>per-record</em>
 * responsibilities only:</p>
 * <ol>
 *   <li><strong>Date-window filter</strong> &mdash; the main loop keeps a record
 *       only when its processing date lies within the inclusive
 *       {@code [startDate, endDate]} window (COBOL
 *       {@code TRAN-PROC-TS(1:10) >= WS-START-DATE AND <= WS-END-DATE}). Records
 *       outside the window are filtered: {@link #process(Transaction)} returns
 *       {@code null}, which Spring Batch drops from the stream.</li>
 *   <li><strong>Enrichment lookups</strong> &mdash; the ordered chain
 *       {@code 1500-A-LOOKUP-XREF} (card&nbsp;&rarr;&nbsp;account id),
 *       {@code 1500-B-LOOKUP-TRANTYPE} (type code&nbsp;&rarr;&nbsp;description)
 *       and {@code 1500-C-LOOKUP-TRANCATG} (type+category
 *       key&nbsp;&rarr;&nbsp;description).</li>
 *   <li><strong>Detail mapping</strong> &mdash; {@code 1120-WRITE-DETAIL} builds
 *       one enriched {@link ReportLine} from the record and the resolved lookup
 *       values.</li>
 * </ol>
 *
 * <p>The cross-item concerns of {@code CBTRN03C} &mdash; page totals, per-card
 * (control-break) account totals, the grand total and 20-line pagination &mdash;
 * span multiple records and cannot be owned by a per-item processor. This
 * component therefore emits, alongside the report fields, the control-break key
 * ({@code cardNumber}) and the {@code amount} that the downstream report
 * writer/job needs to accumulate those totals and decide page breaks.</p>
 *
 * <p>The window bounds originate from the COBOL {@code DATEPARM} file
 * ({@code 0500-DATEPARM-OPEN}/{@code 0550-DATEPARM-READ}); here they are supplied
 * as the {@code startDate} and {@code endDate} job parameters (ISO
 * {@code yyyy-MM-dd}), which is why the bean is {@link StepScope step scoped}.
 * All lookups are read-only; no entity is persisted or mutated, and the
 * {@code amount} {@link BigDecimal} is carried through with its scale untouched.</p>
 */
@Component
@StepScope
public class TransactionReportProcessor
        implements ItemProcessor<Transaction, TransactionReportProcessor.ReportLine> {

    /** Reason tag value: the processing date is outside the inclusive report window. */
    private static final String REASON_OUT_OF_WINDOW = "out_of_window";

    /** Reason tag value: the processing timestamp is absent, too short, or not a valid date. */
    private static final String REASON_MALFORMED_DATE = "malformed_date";

    /** COBOL picture format passed to {@link DateValidationService} for the {@code yyyy-MM-dd} date. */
    private static final String COBOL_DATE_FORMAT = "YYYY-MM-DD";

    /** Count of leading characters of {@code TRAN-PROC-TS} holding the {@code yyyy-MM-dd} date. */
    private static final int DATE_LENGTH = 10;

    /** Description emitted when a reference lookup misses (the COBOL field is left spaces). */
    private static final String BLANK_DESCRIPTION = "";

    /** CARDXREF store resolving card number to account id ({@code 1500-A-LOOKUP-XREF}). */
    private final CardCrossReferenceRepository cardCrossReferenceRepository;

    /** TRANTYPE store resolving the transaction-type description ({@code 1500-B-LOOKUP-TRANTYPE}). */
    private final TransactionTypeRepository transactionTypeRepository;

    /** TRANCATG store resolving the transaction-category description ({@code 1500-C-LOOKUP-TRANCATG}). */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /** Shared date validator (replaces COBOL {@code CSUTLDTC}/{@code CEEDAYS}) used to parse/validate the processing date. */
    private final DateValidationService dateValidationService;

    /** Micrometer registry used to resolve and increment the processed/rejected counters. */
    private final MeterRegistry meterRegistry;

    /** Inclusive lower window bound ({@code yyyy-MM-dd}), or {@code null} for an open lower bound. */
    private final String startDate;

    /** Inclusive upper window bound ({@code yyyy-MM-dd}), or {@code null} for an open upper bound. */
    private final String endDate;

    /**
     * Creates the processor.
     *
     * @param cardCrossReferenceRepository  CARDXREF store for the {@code 1500-A} lookup; must not be {@code null}
     * @param transactionTypeRepository     TRANTYPE store for the {@code 1500-B} lookup; must not be {@code null}
     * @param transactionCategoryRepository TRANCATG store for the {@code 1500-C} lookup; must not be {@code null}
     * @param dateValidationService         shared date validator; must not be {@code null}
     * @param meterRegistry                 Micrometer registry for the processed/rejected counters; must not be {@code null}
     * @param startDate                     inclusive window lower bound ({@code yyyy-MM-dd}) from job parameters; may be {@code null} or blank
     * @param endDate                       inclusive window upper bound ({@code yyyy-MM-dd}) from job parameters; may be {@code null} or blank
     */
    public TransactionReportProcessor(
            final CardCrossReferenceRepository cardCrossReferenceRepository,
            final TransactionTypeRepository transactionTypeRepository,
            final TransactionCategoryRepository transactionCategoryRepository,
            final DateValidationService dateValidationService,
            final MeterRegistry meterRegistry,
            @Value("#{jobParameters['startDate']}") final String startDate,
            @Value("#{jobParameters['endDate']}") final String endDate) {
        this.cardCrossReferenceRepository = Objects.requireNonNull(
                cardCrossReferenceRepository, "cardCrossReferenceRepository must not be null");
        this.transactionTypeRepository = Objects.requireNonNull(
                transactionTypeRepository, "transactionTypeRepository must not be null");
        this.transactionCategoryRepository = Objects.requireNonNull(
                transactionCategoryRepository, "transactionCategoryRepository must not be null");
        this.dateValidationService = Objects.requireNonNull(
                dateValidationService, "dateValidationService must not be null");
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry, "meterRegistry must not be null");
        this.startDate = normalizeDate(startDate);
        this.endDate = normalizeDate(endDate);
    }

    /**
     * Enriches one posted transaction into a report line, or filters it.
     *
     * <p>Mirrors the {@code CBTRN03C} main loop for a single record: it applies
     * the inclusive date-window filter, then &mdash; for in-window records
     * &mdash; runs the ordered {@code 1500-A}/{@code 1500-B}/{@code 1500-C}
     * lookup chain and assembles the {@code 1120-WRITE-DETAIL} line. A record
     * outside the window (or carrying an unusable processing date) is dropped by
     * returning {@code null}.</p>
     *
     * @param tx the posted transaction to enrich; must not be {@code null}
     * @return the enriched {@link ReportLine}, or {@code null} when the record is
     *         filtered out of the report
     * @throws IllegalArgumentException if {@code tx} is {@code null}
     */
    @Override
    public ReportLine process(final Transaction tx) {
        if (tx == null) {
            throw new IllegalArgumentException("Transaction record must not be null");
        }

        final String processingDate = extractProcessingDate(tx.getTranProcTs());
        if (processingDate == null) {
            MetricsConfig.recordsRejected(meterRegistry, REASON_MALFORMED_DATE).increment();
            return null;
        }
        if (!isWithinWindow(processingDate)) {
            MetricsConfig.recordsRejected(meterRegistry, REASON_OUT_OF_WINDOW).increment();
            return null;
        }

        // 1500-A-LOOKUP-XREF: resolve the account id from the card cross-reference.
        final Long accountId = cardCrossReferenceRepository.findById(tx.getTranCardNum())
                .map(CardCrossReference::getXrefAcctId)
                .orElse(null);

        // 1500-B-LOOKUP-TRANTYPE: resolve the transaction-type description.
        final String typeDescription = transactionTypeRepository.findById(tx.getTranTypeCd())
                .map(TransactionType::getTranTypeDesc)
                .orElse(BLANK_DESCRIPTION);

        // 1500-C-LOOKUP-TRANCATG: resolve the category description from the (type, category) key.
        final TransactionCategoryId categoryKey =
                new TransactionCategoryId(tx.getTranTypeCd(), tx.getTranCatCd());
        final String categoryDescription = transactionCategoryRepository.findById(categoryKey)
                .map(TransactionCategory::getTranCatTypeDesc)
                .orElse(BLANK_DESCRIPTION);

        // 1120-WRITE-DETAIL: assemble the enriched detail line.
        final ReportLine reportLine = new ReportLine(
                tx.getTranId(),
                tx.getTranCardNum(),
                accountId,
                tx.getTranTypeCd(),
                typeDescription,
                tx.getTranCatCd(),
                categoryDescription,
                tx.getTranSource(),
                tx.getTranAmt());

        MetricsConfig.recordsProcessed(meterRegistry).increment();
        return reportLine;
    }

    /**
     * Extracts and validates the {@code yyyy-MM-dd} processing date from a
     * {@code TRAN-PROC-TS} timestamp.
     *
     * @param tranProcTs the processing timestamp (or {@code null})
     * @return the validated {@code yyyy-MM-dd} date, or {@code null} when the
     *         timestamp is absent, shorter than {@value #DATE_LENGTH} characters,
     *         or not a valid calendar date
     */
    private String extractProcessingDate(final String tranProcTs) {
        if (tranProcTs == null || tranProcTs.length() < DATE_LENGTH) {
            return null;
        }
        final String processingDate = tranProcTs.substring(0, DATE_LENGTH);
        if (!dateValidationService.isValidDate(processingDate, COBOL_DATE_FORMAT)) {
            return null;
        }
        return processingDate;
    }

    /**
     * Tests the inclusive report window, reproducing the COBOL alphanumeric
     * {@code >=}/{@code <=} comparison on the {@code PIC X(10)} date fields. For
     * {@code yyyy-MM-dd} values lexicographic ordering equals chronological
     * ordering, so {@link String#compareTo(String)} is the faithful equivalent. A
     * {@code null} bound denotes an open end of the window.
     *
     * @param processingDate the validated {@code yyyy-MM-dd} processing date
     * @return {@code true} when {@code startDate <= processingDate <= endDate}
     */
    private boolean isWithinWindow(final String processingDate) {
        if (startDate != null && processingDate.compareTo(startDate) < 0) {
            return false;
        }
        if (endDate != null && processingDate.compareTo(endDate) > 0) {
            return false;
        }
        return true;
    }

    /**
     * Normalizes an injected window bound: trims surrounding whitespace and
     * collapses an empty value to {@code null} (an open bound).
     *
     * @param date the raw job-parameter value (or {@code null})
     * @return the trimmed value, or {@code null} when {@code null} or blank
     */
    private static String normalizeDate(final String date) {
        if (date == null) {
            return null;
        }
        final String trimmed = date.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * One enriched transaction-report detail line ({@code 1120-WRITE-DETAIL}),
     * plus the {@code cardNumber} control-break key the downstream report
     * writer/job uses to group by card and accumulate the page, account, and
     * grand totals.
     *
     * @param tranId              transaction id ({@code TRAN-REPORT-TRANS-ID} &larr; {@code TRAN-ID})
     * @param cardNumber          card-number control-break key ({@code TRAN-CARD-NUM})
     * @param accountId           account id ({@code TRAN-REPORT-ACCOUNT-ID} &larr; {@code XREF-ACCT-ID}); {@code null} when the cross-reference is absent
     * @param typeCode            transaction type code ({@code TRAN-REPORT-TYPE-CD} &larr; {@code TRAN-TYPE-CD})
     * @param typeDescription     transaction type description ({@code TRAN-REPORT-TYPE-DESC} &larr; {@code TRAN-TYPE-DESC}); blank when absent
     * @param categoryCode        transaction category code ({@code TRAN-REPORT-CAT-CD} &larr; {@code TRAN-CAT-CD})
     * @param categoryDescription transaction category description ({@code TRAN-REPORT-CAT-DESC} &larr; {@code TRAN-CAT-TYPE-DESC}); blank when absent
     * @param source              transaction source ({@code TRAN-REPORT-SOURCE} &larr; {@code TRAN-SOURCE})
     * @param amount              transaction amount ({@code TRAN-REPORT-AMT} &larr; {@code TRAN-AMT}), preserved as a scale-2 {@link BigDecimal}
     */
    public record ReportLine(
            String tranId,
            String cardNumber,
            Long accountId,
            String typeCode,
            String typeDescription,
            Integer categoryCode,
            String categoryDescription,
            String source,
            BigDecimal amount) {
    }
}
