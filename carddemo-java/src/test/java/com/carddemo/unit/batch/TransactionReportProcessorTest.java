package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.processors.TransactionReportProcessor;
import com.carddemo.batch.processors.TransactionReportProcessor.ReportLine;
import com.carddemo.model.entity.CardCrossReference;
import com.carddemo.model.entity.Transaction;
import com.carddemo.model.entity.TransactionCategory;
import com.carddemo.model.entity.TransactionType;
import com.carddemo.model.key.TransactionCategoryId;
import com.carddemo.repository.CardCrossReferenceRepository;
import com.carddemo.repository.TransactionCategoryRepository;
import com.carddemo.repository.TransactionTypeRepository;
import com.carddemo.service.shared.DateValidationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionReportProcessor}.
 *
 * <p>Traceability (REFERENCE-ONLY, COBOL/JCL not copied; source commit {@code 27d6c6f}):
 * the processor re-platforms the per-record leg of the mainframe transaction-report
 * program {@code app/cbl/CBTRN03C.cbl}, run by {@code app/jcl/TRANREPT.jcl}. These tests
 * pin the three binding parities that operate on a single record:</p>
 * <ul>
 *   <li><strong>Inclusive date window</strong> &mdash; {@code CBTRN03C} lines 173-174
 *       keep a record when
 *       {@code TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE},
 *       inclusive on both bounds. A record whose processing date lies outside the window
 *       is filtered by returning {@code null}.</li>
 *   <li><strong>Enrichment lookups</strong> &mdash; {@code 1500-A/B/C} resolve the account
 *       id from the card cross-reference ({@code XREF-ACCT-ID}, line 364), the type
 *       description ({@code TRAN-TYPE-DESC}, line 366), and the category description
 *       ({@code TRAN-CAT-TYPE-DESC}, line 368). A missing reference row is lenient
 *       (blank description / {@code null} account id), never an abend.</li>
 *   <li><strong>Two-component category key</strong> &mdash; the category lookup is keyed by
 *       {@link TransactionCategoryId}, whose {@code CVTRA04Y} {@code TRAN-CAT-KEY} is exactly
 *       {@code TRAN-TYPE-CD} + {@code TRAN-CAT-CD} (no account id).</li>
 * </ul>
 *
 * <p>This is a pure-JVM unit test: the three Spring Data repositories and the
 * {@link DateValidationService} are Mockito mocks, the {@link SimpleMeterRegistry} is real,
 * and there is no Spring context, no database, and no Testcontainers. The monetary
 * {@code TRAN-AMT} field is verified by value with {@code compareTo} semantics and never by
 * {@code BigDecimal.equals} (AAP section 0.8.2).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor - CBTRN03C inclusive window + A/B/C enrichment + two-arg key")
class TransactionReportProcessorTest {

    /** Inclusive lower bound of the report window bound from the {@code startDate} job parameter. */
    private static final String WINDOW_START = "2022-07-01";

    /** Inclusive upper bound of the report window bound from the {@code endDate} job parameter. */
    private static final String WINDOW_END = "2022-07-31";

    /** Binding meter name; mirrors {@code MetricsConfig.BATCH_RECORDS_PROCESSED}. */
    private static final String RECORDS_PROCESSED_METRIC = "carddemo.batch.records.processed";

    /** Fixed {@code TRAN-ID} key used by the record builder so pass-through can be asserted. */
    private static final String DEFAULT_TRAN_ID = "00000000000000001";

    /** Fixed 16-digit {@code TRAN-CARD-NUM} used by the record builder. */
    private static final String DEFAULT_CARD = "1234567890123456";

    @Mock
    private CardCrossReferenceRepository xrefRepository;

    @Mock
    private TransactionTypeRepository typeRepository;

    @Mock
    private TransactionCategoryRepository categoryRepository;

    @Mock
    private DateValidationService dateValidationService;

    private SimpleMeterRegistry registry;
    private TransactionReportProcessor processor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // The window bounds are constructor parameters (bound from the startDate/endDate job
        // parameters on the @StepScope bean), not field-injected @Value properties.
        processor = new TransactionReportProcessor(
                xrefRepository,
                typeRepository,
                categoryRepository,
                dateValidationService,
                registry,
                WINDOW_START,
                WINDOW_END);
        // The processor gates the processing date through DateValidationService before the
        // inclusive String.compareTo window check. The mock returns false by default, which
        // would mark every record malformed; every test feeds a calendar-valid date, so the
        // gate is stubbed valid for all of them (used by every test, no lenient() required).
        when(dateValidationService.isValidDate(anyString(), anyString())).thenReturn(true);
    }

    @Test
    @DisplayName("process() includes a record whose date equals the inclusive start bound")
    void include_whenDateEqualsStart() {
        // CBTRN03C line 173 uses >= against WS-START-DATE, so the lower bound is in range.
        assertThat(processor.process(txOn(WINDOW_START))).isNotNull();
    }

    @Test
    @DisplayName("process() includes a record whose date equals the inclusive end bound")
    void include_whenDateEqualsEnd() {
        // CBTRN03C line 174 uses <= against WS-END-DATE, so the upper bound is in range.
        assertThat(processor.process(txOn(WINDOW_END))).isNotNull();
    }

    @Test
    @DisplayName("process() includes a record whose date is strictly inside the window")
    void include_whenDateInsideWindow() {
        assertThat(processor.process(txOn("2022-07-15"))).isNotNull();
    }

    @Test
    @DisplayName("process() filters (null) a record one day before the start bound")
    void exclude_whenDateBeforeStart_returnsNull() {
        Transaction transaction = txOn("2022-06-30");

        assertThat(processor.process(transaction)).isNull();
        // The window filter short-circuits before the A/B/C lookups, so no reference row is read.
        verifyNoInteractions(xrefRepository, typeRepository, categoryRepository);
    }

    @Test
    @DisplayName("process() filters (null) a record one day after the end bound")
    void exclude_whenDateAfterEnd_returnsNull() {
        Transaction transaction = txOn("2022-08-01");

        assertThat(processor.process(transaction)).isNull();
        verifyNoInteractions(xrefRepository, typeRepository, categoryRepository);
    }

    @Test
    @DisplayName("process() enriches the line from XREF, TRANTYPE and TRANCATG lookups")
    void enrichesLine_fromLookups() {
        Transaction transaction = tx("2022-07-15", DEFAULT_CARD, "01", 5, "POS TERM", "45.67");
        when(xrefRepository.findById(DEFAULT_CARD)).thenReturn(Optional.of(xrefWithAcct(98765L)));
        when(typeRepository.findById("01")).thenReturn(Optional.of(typeWithDesc("Purchase")));
        when(categoryRepository.findById(new TransactionCategoryId("01", 5)))
                .thenReturn(Optional.of(catWithDesc("Retail")));

        ReportLine line = processor.process(transaction);

        assertThat(line).isNotNull();
        assertThat(line.tranId()).isEqualTo(DEFAULT_TRAN_ID);
        assertThat(line.cardNumber()).isEqualTo(DEFAULT_CARD);
        assertThat(line.accountId()).isEqualTo(98765L);
        assertThat(line.typeCode()).isEqualTo("01");
        assertThat(line.typeDescription()).isEqualTo("Purchase");
        assertThat(line.categoryCode()).isEqualTo(5);
        assertThat(line.categoryDescription()).isEqualTo("Retail");
        assertThat(line.source()).isEqualTo("POS TERM");
        assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("45.67"));
        // Two-arg key proof: the category is resolved by exactly (typeCode, categoryCode),
        // never a three-component key carrying an account id.
        verify(categoryRepository).findById(new TransactionCategoryId("01", 5));
    }

    @Test
    @DisplayName("process() leaves type and category descriptions blank on a lookup miss")
    void blankDescriptions_onLookupMiss() {
        Transaction transaction = txOn("2022-07-15");
        when(xrefRepository.findById(DEFAULT_CARD)).thenReturn(Optional.of(xrefWithAcct(98765L)));
        // A missing reference row is lenient in CBTRN03C: the description stays spaces (blank
        // here), the line is still emitted, and no exception is raised.
        when(typeRepository.findById("01")).thenReturn(Optional.empty());
        when(categoryRepository.findById(new TransactionCategoryId("01", 5)))
                .thenReturn(Optional.empty());

        ReportLine line = processor.process(transaction);

        assertThat(line).isNotNull();
        assertThat(line.accountId()).isEqualTo(98765L);
        assertThat(line.typeDescription()).isBlank();
        assertThat(line.categoryDescription()).isBlank();
    }

    @Test
    @DisplayName("process() increments carddemo.batch.records.processed once per emitted line")
    void incrementsProcessedCounter_forEmittedLine() {
        processor.process(txOn("2022-07-15"));

        assertThat(registry.get(RECORDS_PROCESSED_METRIC).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("process() reads only and never persists (enrichment, not a write)")
    void readsOnly_noPersistence() {
        processor.process(txOn("2022-07-15"));

        // The processor enriches one record; totals, pagination, and writes belong to the
        // downstream report writer and job, never to this per-item processor.
        verify(xrefRepository, never()).save(any());
        verify(typeRepository, never()).save(any());
        verify(categoryRepository, never()).save(any());
    }

    /**
     * Builds a fully populated {@link Transaction} from the {@code CVTRA05Y} layout. The
     * {@code tranProcTs} is assembled as the 26-character {@code yyyy-MM-dd-HH.mm.ss.SSSSSS}
     * processing timestamp so its leading 10 characters ({@code TRAN-PROC-TS (1:10)}) are the
     * intended calendar date.
     *
     * @param procDate     the 10-character {@code yyyy-MM-dd} processing date
     * @param cardNumber   the 16-digit {@code TRAN-CARD-NUM} cross-reference key
     * @param typeCode     the two-character {@code TRAN-TYPE-CD}
     * @param categoryCode the numeric {@code TRAN-CAT-CD}
     * @param source       the {@code TRAN-SOURCE} classification
     * @param amount       the {@code TRAN-AMT} monetary value (scale preserved by the caller)
     * @return a new, fully populated transaction record
     */
    private Transaction tx(
            String procDate,
            String cardNumber,
            String typeCode,
            int categoryCode,
            String source,
            String amount) {
        Transaction transaction = new Transaction();
        transaction.setTranId(DEFAULT_TRAN_ID);
        transaction.setTranTypeCd(typeCode);
        transaction.setTranCatCd(categoryCode);
        transaction.setTranSource(source);
        transaction.setTranDesc("PURCHASE - GROCERY STORE");
        transaction.setTranAmt(new BigDecimal(amount));
        transaction.setTranMerchantId(1L);
        transaction.setTranMerchantName("ACME GROCERY");
        transaction.setTranMerchantCity("SEATTLE");
        transaction.setTranMerchantZip("98101-0000");
        transaction.setTranCardNum(cardNumber);
        transaction.setTranOrigTs(procDate + "-09.00.00.000000");
        transaction.setTranProcTs(procDate + "-10.15.30.123456");
        return transaction;
    }

    /**
     * Convenience builder for the date-window tests: a default in-/out-of-window record whose
     * only material field is the processing date.
     *
     * @param procDate the 10-character {@code yyyy-MM-dd} processing date
     * @return a fully populated transaction with default card, type, category, source, and amount
     */
    private Transaction txOn(String procDate) {
        return tx(procDate, DEFAULT_CARD, "01", 5, "POS TERM", "10.00");
    }

    /**
     * Builds a {@link CardCrossReference} carrying the account id resolved by
     * {@code 1500-A-LOOKUP-XREF}.
     *
     * @param accountId the {@code XREF-ACCT-ID} the lookup should resolve
     * @return a cross-reference row for the default card number
     */
    private CardCrossReference xrefWithAcct(long accountId) {
        CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(DEFAULT_CARD);
        xref.setXrefCustId(1L);
        xref.setXrefAcctId(accountId);
        return xref;
    }

    /**
     * Builds a {@link TransactionType} carrying the description resolved by
     * {@code 1500-B-LOOKUP-TRANTYPE}.
     *
     * @param description the {@code TRAN-TYPE-DESC} the lookup should resolve
     * @return a transaction-type reference row for type code {@code "01"}
     */
    private TransactionType typeWithDesc(String description) {
        TransactionType type = new TransactionType();
        type.setTranType("01");
        type.setTranTypeDesc(description);
        return type;
    }

    /**
     * Builds a {@link TransactionCategory} carrying the description resolved by
     * {@code 1500-C-LOOKUP-TRANCATG} through the two-component {@link TransactionCategoryId}.
     *
     * @param description the {@code TRAN-CAT-TYPE-DESC} the lookup should resolve
     * @return a transaction-category reference row for key {@code ("01", 5)}
     */
    private TransactionCategory catWithDesc(String description) {
        TransactionCategory category = new TransactionCategory();
        category.setId(new TransactionCategoryId("01", 5));
        category.setTranCatTypeDesc(description);
        return category;
    }
}
