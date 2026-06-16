package com.carddemo.batch.processors;

import com.carddemo.exception.RecordNotFoundException;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionType;
import com.carddemo.model.key.TransactionCategoryId;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.service.shared.DateValidationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that enriches a single posted
 * {@link Transaction} into one transaction-report detail line, re-platforming the
 * per-record logic of the mainframe transaction-report program
 * {@code app/cbl/CBTRN03C.cbl} run by {@code app/jcl/TRANREPT.jcl} (source commit
 * {@code 27d6c6f}).
 *
 * <p>The component reproduces three pieces of {@code CBTRN03C} that operate on a
 * single record:</p>
 * <ol>
 *   <li><strong>Date-window filter</strong> (main loop, {@code TRAN-PROC-TS(1:10)}
 *       {@code >= WS-START-DATE AND <= WS-END-DATE}). The window is inclusive on
 *       both bounds; a record whose processing date is outside the window is
 *       filtered by returning {@code null}, which Spring Batch drops from the
 *       chunk.</li>
 *   <li><strong>Enrichment lookups</strong> performed in the source order
 *       A&rarr;B&rarr;C: {@code 1500-A-LOOKUP-XREF} resolves the account id from
 *       the card cross-reference, {@code 1500-B-LOOKUP-TRANTYPE} resolves the
 *       transaction-type description, and {@code 1500-C-LOOKUP-TRANCATG} resolves
 *       the category description through the two-component
 *       {@link TransactionCategoryId} key.</li>
 *   <li><strong>Detail mapping</strong> ({@code 1120-WRITE-DETAIL}, lines
 *       361&ndash;374), which copies the transaction and looked-up fields into the
 *       emitted {@link ReportLine}.</li>
 * </ol>
 *
 * <p>A reference lookup that finds no row is <strong>fatal</strong>, mirroring the
 * {@code CBTRN03C} {@code INVALID KEY} paths in {@code 1500-A}/{@code 1500-B}/
 * {@code 1500-C}, each of which {@code DISPLAY}s a diagnostic and performs
 * {@code 9999-ABEND-PROGRAM} (a {@code CEE3ABD} abend). The Java equivalent throws a
 * {@link RecordNotFoundException} (COBOL {@code FILE STATUS '23'}) so the report step
 * fails rather than emitting an incomplete detail row. The card number in the
 * cross-reference diagnostic is masked to its last four digits; the non-sensitive
 * transaction-type and category codes are shown verbatim as the COBOL {@code DISPLAY}
 * does. The processor performs reads only and never persists.</p>
 *
 * <p>This per-item processor cannot own the cross-item accumulations that
 * {@code CBTRN03C} also performs &mdash; page totals, per-account totals on the card
 * control break, the grand total, and the 20-line pagination. It therefore carries
 * the card number ({@code TRAN-CARD-NUM}) as the {@link ReportLine#cardNumber()}
 * control-break key and the untouched {@link ReportLine#amount()} so the downstream
 * report writer and job can group by card and compute those totals.</p>
 *
 * <p>The reporting window is supplied per job execution through the {@code startDate}
 * and {@code endDate} job parameters (format {@code yyyy-MM-dd}), which is why the
 * bean is {@link StepScope step scoped}. Both bounds are trimmed when the bean is
 * constructed.</p>
 */
@Component
@StepScope
public class TransactionReportProcessor
        implements ItemProcessor<Transaction, TransactionReportProcessor.ReportLine> {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionReportProcessor.class);

    /**
     * COBOL picture format for an ISO calendar date, passed to
     * {@link DateValidationService} so the processing date is validated with the
     * same {@code CEEDAYS}/{@code CSUTLDTC} semantics used across the application.
     */
    private static final String COBOL_DATE_FORMAT = "YYYY-MM-DD";

    /**
     * Number of leading characters of the 26-byte processing timestamp that hold
     * the {@code yyyy-MM-dd} calendar date, mirroring the COBOL reference
     * {@code TRAN-PROC-TS(1:10)}.
     */
    private static final int PROC_DATE_LENGTH = 10;

    /**
     * Number of trailing card-number digits preserved when masking the card number
     * in a cross-reference diagnostic, so the full PAN is never logged or surfaced.
     */
    private static final int MASK_VISIBLE_DIGITS = 4;

    /**
     * Fixed prefix prepended to the {@value #MASK_VISIBLE_DIGITS} visible digits when
     * masking a card number for diagnostics; also used alone when the value is too
     * short to safely reveal a suffix.
     */
    private static final String MASK_PREFIX = "****";

    /** Counter incremented once for every report line this processor emits. */
    private static final String RECORDS_PROCESSED_METRIC = "carddemo.batch.records.processed";

    /** Counter incremented for every record filtered before a line is emitted. */
    private static final String RECORDS_REJECTED_METRIC = "carddemo.batch.records.rejected";

    /** Tag key carrying the reason a record was filtered. */
    private static final String REASON_TAG = "reason";

    /** Reason tag value for a record whose processing date falls outside the window. */
    private static final String REASON_OUT_OF_WINDOW = "out_of_window";

    /** Reason tag value for a record whose processing timestamp is missing or unparseable. */
    private static final String REASON_MALFORMED_PROC_DATE = "malformed_proc_date";

    private final CardCrossReferenceRepository cardCrossReferenceRepository;
    private final TransactionTypeRepository transactionTypeRepository;
    private final TransactionCategoryRepository transactionCategoryRepository;
    private final DateValidationService dateValidationService;
    private final MeterRegistry meterRegistry;

    private final String startDate;
    private final String endDate;

    /**
     * Creates the step-scoped processor.
     *
     * @param cardCrossReferenceRepository  repository for {@code 1500-A-LOOKUP-XREF}
     *                                      (card-to-account cross-reference)
     * @param transactionTypeRepository     repository for {@code 1500-B-LOOKUP-TRANTYPE}
     *                                      (transaction-type descriptions)
     * @param transactionCategoryRepository repository for {@code 1500-C-LOOKUP-TRANCATG}
     *                                      (transaction-category descriptions)
     * @param dateValidationService         shared date service replacing the COBOL
     *                                      {@code CEEDAYS}/{@code CSUTLDTC} validation
     * @param meterRegistry                 Micrometer registry for processed and
     *                                      rejected counters; must not be {@code null}
     * @param startDate                     inclusive lower bound of the report window
     *                                      ({@code yyyy-MM-dd}), bound from the
     *                                      {@code startDate} job parameter
     * @param endDate                       inclusive upper bound of the report window
     *                                      ({@code yyyy-MM-dd}), bound from the
     *                                      {@code endDate} job parameter
     */
    public TransactionReportProcessor(
            CardCrossReferenceRepository cardCrossReferenceRepository,
            TransactionTypeRepository transactionTypeRepository,
            TransactionCategoryRepository transactionCategoryRepository,
            DateValidationService dateValidationService,
            MeterRegistry meterRegistry,
            @Value("#{jobParameters['startDate']}") String startDate,
            @Value("#{jobParameters['endDate']}") String endDate) {
        this.cardCrossReferenceRepository = cardCrossReferenceRepository;
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
        this.dateValidationService = dateValidationService;
        this.meterRegistry = meterRegistry;
        this.startDate = startDate == null ? null : startDate.trim();
        this.endDate = endDate == null ? null : endDate.trim();
    }

    /**
     * Enriches one posted transaction into a report detail line, or filters it.
     *
     * <p>The processing date is taken from the first {@value #PROC_DATE_LENGTH}
     * characters of {@link Transaction#getTranProcTs()} and validated with
     * {@link DateValidationService}. A missing, short, or invalid date is filtered
     * (counted under reason {@value #REASON_MALFORMED_PROC_DATE}); a valid date
     * outside the inclusive {@code [startDate, endDate]} window is filtered (counted
     * under reason {@value #REASON_OUT_OF_WINDOW}). Both filtered cases return
     * {@code null} so Spring Batch drops the record.</p>
     *
     * <p>For an in-window record the three enrichment lookups run in the source
     * order A&rarr;B&rarr;C and the {@code 1120-WRITE-DETAIL} field mapping builds
     * the {@link ReportLine}. The {@link Transaction#getTranAmt() amount} is copied
     * through unchanged so its {@link BigDecimal} scale is preserved for the
     * downstream totals.</p>
     *
     * @param transaction the posted transaction supplied by the step reader
     * @return the enriched {@link ReportLine}, or {@code null} when the record is
     *         filtered out of the report
     */
    @Override
    public ReportLine process(Transaction transaction) {
        String processingDate = extractProcessingDate(transaction.getTranProcTs());
        if (processingDate == null
                || !dateValidationService.isValidDate(processingDate, COBOL_DATE_FORMAT)) {
            reject(REASON_MALFORMED_PROC_DATE);
            return null;
        }
        if (!isWithinInclusiveWindow(processingDate)) {
            reject(REASON_OUT_OF_WINDOW);
            return null;
        }

        Long accountId = lookupAccountId(transaction.getTranCardNum());
        String typeDescription = lookupTypeDescription(transaction.getTranTypeCd());
        String categoryDescription =
                lookupCategoryDescription(transaction.getTranTypeCd(), transaction.getTranCatCd());

        ReportLine reportLine = new ReportLine(
                transaction.getTranId(),
                transaction.getTranCardNum(),
                accountId,
                transaction.getTranTypeCd(),
                typeDescription,
                transaction.getTranCatCd(),
                categoryDescription,
                transaction.getTranSource(),
                transaction.getTranAmt());

        meterRegistry.counter(RECORDS_PROCESSED_METRIC).increment();
        return reportLine;
    }

    /**
     * Extracts the {@code yyyy-MM-dd} calendar date from the processing timestamp,
     * defending against a {@code null} or too-short value (the COBOL reference is
     * {@code TRAN-PROC-TS(1:10)}).
     *
     * @param tranProcTs the raw processing timestamp
     * @return the 10-character calendar date, or {@code null} when it cannot be taken
     */
    private String extractProcessingDate(String tranProcTs) {
        if (tranProcTs == null || tranProcTs.length() < PROC_DATE_LENGTH) {
            return null;
        }
        return tranProcTs.substring(0, PROC_DATE_LENGTH);
    }

    /**
     * Tests the inclusive date window {@code startDate <= processingDate <= endDate}.
     * The arguments are zero-padded {@code yyyy-MM-dd} strings, so lexicographic
     * {@link String#compareTo(String)} ordering is identical to chronological
     * ordering and to the COBOL alphanumeric comparison. A missing bound cannot
     * define a window, so no record is considered in range.
     *
     * @param processingDate the validated 10-character processing date
     * @return {@code true} only when the date lies within both inclusive bounds
     */
    private boolean isWithinInclusiveWindow(String processingDate) {
        if (startDate == null || startDate.isEmpty() || endDate == null || endDate.isEmpty()) {
            return false;
        }
        return processingDate.compareTo(startDate) >= 0 && processingDate.compareTo(endDate) <= 0;
    }

    /**
     * {@code 1500-A-LOOKUP-XREF}: resolves the account id from the card
     * cross-reference. A missing cross-reference (COBOL {@code INVALID KEY}) is fatal:
     * the masked card-number diagnostic is logged and a {@link RecordNotFoundException}
     * is thrown, mirroring {@code DISPLAY 'INVALID CARD NUMBER : '} followed by
     * {@code 9999-ABEND-PROGRAM}. The card number is masked to its last
     * {@value #MASK_VISIBLE_DIGITS} digits so the full PAN is never logged or surfaced.
     *
     * @param cardNumber the transaction card number key
     * @return the cross-referenced account id
     * @throws RecordNotFoundException when the card number is absent or no
     *                                 cross-reference row exists for it
     */
    private Long lookupAccountId(String cardNumber) {
        if (cardNumber != null) {
            CardCrossReference xref = cardCrossReferenceRepository.findById(cardNumber).orElse(null);
            if (xref != null) {
                return xref.getXrefAcctId();
            }
        }
        String message = "INVALID CARD NUMBER : " + maskCardNumber(cardNumber);
        LOGGER.error(message);
        throw new RecordNotFoundException(message);
    }

    /**
     * {@code 1500-B-LOOKUP-TRANTYPE}: resolves the transaction-type description.
     * A missing type (COBOL {@code INVALID KEY}) is fatal: the diagnostic is logged
     * and a {@link RecordNotFoundException} is thrown, mirroring
     * {@code DISPLAY 'INVALID TRANSACTION TYPE : '} followed by
     * {@code 9999-ABEND-PROGRAM}. The transaction-type code is a non-sensitive
     * reference code and is shown verbatim, exactly as the COBOL {@code DISPLAY} does.
     *
     * @param typeCode the two-character transaction-type code
     * @return the type description
     * @throws RecordNotFoundException when the type code is absent or no
     *                                 transaction-type row exists for it
     */
    private String lookupTypeDescription(String typeCode) {
        if (typeCode != null) {
            TransactionType type = transactionTypeRepository.findById(typeCode).orElse(null);
            if (type != null) {
                return type.getTranTypeDesc();
            }
        }
        String message = "INVALID TRANSACTION TYPE : " + typeCode;
        LOGGER.error(message);
        throw new RecordNotFoundException(message);
    }

    /**
     * {@code 1500-C-LOOKUP-TRANCATG}: resolves the transaction-category description
     * using the two-component {@link TransactionCategoryId} (type code + category
     * code, no account id). A missing category (COBOL {@code INVALID KEY}) is fatal:
     * the diagnostic is logged and a {@link RecordNotFoundException} is thrown,
     * mirroring {@code DISPLAY 'INVALID TRAN CATG KEY : '} followed by
     * {@code 9999-ABEND-PROGRAM}. The type and category codes are non-sensitive
     * reference codes and are shown verbatim, exactly as the COBOL {@code DISPLAY} does.
     *
     * @param typeCode     the two-character transaction-type code
     * @param categoryCode the numeric category code
     * @return the category description
     * @throws RecordNotFoundException when either key component is absent or no
     *                                 transaction-category row exists for the key
     */
    private String lookupCategoryDescription(String typeCode, Integer categoryCode) {
        if (typeCode != null && categoryCode != null) {
            TransactionCategory category = transactionCategoryRepository
                    .findById(new TransactionCategoryId(typeCode, categoryCode))
                    .orElse(null);
            if (category != null) {
                return category.getTranCatTypeDesc();
            }
        }
        String message = "INVALID TRAN CATG KEY : " + typeCode + "/" + categoryCode;
        LOGGER.error(message);
        throw new RecordNotFoundException(message);
    }

    /**
     * Masks a card number to its last {@value #MASK_VISIBLE_DIGITS} digits for use in
     * the cross-reference diagnostic, so the full PAN is never logged or surfaced
     * through an exception message. A {@code null} value or one no longer than
     * {@value #MASK_VISIBLE_DIGITS} characters is fully masked to {@value #MASK_PREFIX}.
     *
     * @param cardNumber the raw card number, possibly {@code null}
     * @return {@value #MASK_PREFIX} followed by the last {@value #MASK_VISIBLE_DIGITS}
     *         digits, or {@value #MASK_PREFIX} alone when the value is absent or too short
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.length() <= MASK_VISIBLE_DIGITS) {
            return MASK_PREFIX;
        }
        return MASK_PREFIX + cardNumber.substring(cardNumber.length() - MASK_VISIBLE_DIGITS);
    }

    /**
     * Records a filtered record under the reason-tagged rejected counter.
     *
     * @param reason the reason tag describing why the record was filtered
     */
    private void reject(String reason) {
        meterRegistry.counter(RECORDS_REJECTED_METRIC, REASON_TAG, reason).increment();
    }

    /**
     * Immutable transaction-report detail line, the typed contract consumed by the
     * downstream report writer and job. Mirrors the {@code TRANSACTION-DETAIL-REPORT}
     * fields populated by {@code 1120-WRITE-DETAIL} and adds {@code cardNumber} as the
     * control-break key for card grouping and total accumulation.
     *
     * @param tranId              transaction id ({@code TRAN-REPORT-TRANS-ID})
     * @param cardNumber          card number control-break key ({@code TRAN-CARD-NUM})
     * @param accountId           cross-referenced account id ({@code TRAN-REPORT-ACCOUNT-ID});
     *                            always resolved because a missing cross-reference is fatal
     * @param typeCode            transaction-type code ({@code TRAN-REPORT-TYPE-CD})
     * @param typeDescription     transaction-type description ({@code TRAN-REPORT-TYPE-DESC});
     *                            always resolved because a missing type is fatal
     * @param categoryCode        transaction-category code ({@code TRAN-REPORT-CAT-CD})
     * @param categoryDescription transaction-category description ({@code TRAN-REPORT-CAT-DESC});
     *                            always resolved because a missing category is fatal
     * @param source              transaction source ({@code TRAN-REPORT-SOURCE})
     * @param amount              transaction amount ({@code TRAN-REPORT-AMT}), an
     *                            unscaled-preserving {@link BigDecimal}
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
