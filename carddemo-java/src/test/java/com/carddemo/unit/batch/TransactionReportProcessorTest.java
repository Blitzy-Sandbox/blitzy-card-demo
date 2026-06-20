package com.carddemo.unit.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
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
 * Unit tests for {@link TransactionReportProcessor}, the per-record
 * {@link org.springframework.batch.item.ItemProcessor} that re-platforms the
 * transaction-report stage of AWS CardDemo program {@code CBTRN03C}
 * (REFERENCE-ONLY, COBOL not copied; source commit {@code 27d6c6f}).
 *
 * <p>The suite proves the three binding contracts of that stage:</p>
 * <ul>
 *   <li><strong>Inclusive date-window filter</strong> &mdash; the processing date
 *       {@code TRAN-PROC-TS(1:10)} is kept when {@code startDate <= procDate <=
 *       endDate} with <em>both</em> ends inclusive, faithful to {@code CBTRN03C}
 *       L173-174 ({@code >= WS-START-DATE AND <= WS-END-DATE}); a record outside
 *       the window is filtered, so {@link TransactionReportProcessor#process(Transaction)}
 *       returns {@code null} and short-circuits before any lookup.</li>
 *   <li><strong>Enrichment lookups with blank-on-miss</strong> &mdash; the ordered
 *       {@code 1500-A}/{@code 1500-B}/{@code 1500-C} chain resolves the account id
 *       (cross-reference), type description and category description; a type or
 *       category lookup miss yields a blank description rather than an exception
 *       (AAP &sect;0.8.4 lenient mapping), and the account id is {@code null} when
 *       the cross-reference is absent.</li>
 *   <li><strong>Two-argument category key</strong> &mdash; the category lookup key
 *       is built from exactly {@code (typeCode, categoryCode)} via
 *       {@link TransactionCategoryId#TransactionCategoryId(String, Integer)}, with
 *       <em>no</em> account-id component (distinct from
 *       {@code TransactionCategoryBalanceId}), mirroring copybook {@code CVTRA04Y}
 *       {@code TRAN-CAT-KEY}.</li>
 * </ul>
 *
 * <p>Pure-JVM test: the repositories and the {@link DateValidationService} are
 * Mockito mocks, the {@link SimpleMeterRegistry} is real, and there is no Spring
 * context, Testcontainers, or AWS dependency. The window bounds are supplied
 * directly through the processor's constructor (the production bean receives them
 * from the {@code startDate}/{@code endDate} job parameters), so no field
 * reflection is required.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor (CBTRN03C) — inclusive window, enrichment, two-arg key")
class TransactionReportProcessorTest {

    /** Inclusive lower window bound supplied to the processor under test. */
    private static final String START_DATE = "2022-07-01";

    /** Inclusive upper window bound supplied to the processor under test. */
    private static final String END_DATE = "2022-07-31";

    /** Canonical 16-character card number used across the enrichment tests. */
    private static final String CARD_NUMBER = "1234567890123456";

    /** Canonical two-character transaction-type code. */
    private static final String TYPE_CODE = "01";

    /** Canonical category code (the {@code Integer} component of the two-arg key). */
    private static final int CATEGORY_CODE = 5;

    /** Canonical transaction source. */
    private static final String SOURCE = "POS TERM";

    /** Deterministic 16-character transaction id assigned by the {@link #tx} helper. */
    private static final String TRAN_ID = "TRANRPT000000001";

    /** Counter (untagged) the processor increments once per emitted report line. */
    private static final String METRIC_PROCESSED = "carddemo.batch.records.processed";

    /** Counter the processor increments once per filtered record, tagged by reason. */
    private static final String METRIC_REJECTED = "carddemo.batch.records.rejected";

    /** Reject reason-tag key. */
    private static final String TAG_REASON = "reason";

    /** Reject reason-tag value for a record dropped by the date-window filter. */
    private static final String REASON_OUT_OF_WINDOW = "out_of_window";

    @Mock
    private CardCrossReferenceRepository xrefRepository;

    @Mock
    private TransactionTypeRepository typeRepository;

    @Mock
    private TransactionCategoryRepository categoryRepository;

    @Mock
    private DateValidationService dateValidationService;

    /** Real registry so the processed/rejected counters are exercised end-to-end. */
    private SimpleMeterRegistry registry;

    /** System under test, rebuilt per test with a fresh registry and the fixed window. */
    private TransactionReportProcessor processor;

    /**
     * Builds the processor with the mocked collaborators, a fresh
     * {@link SimpleMeterRegistry}, and the inclusive {@code [START_DATE, END_DATE]}
     * window, then stubs the date validator to accept every well-formed date.
     *
     * <p>The processor calls {@link DateValidationService#isValidDate(String, String)}
     * for every record (to validate {@code TRAN-PROC-TS(1:10)}) before applying the
     * window comparison, which it performs with {@link String#compareTo(String)} on
     * the {@code yyyy-MM-dd} strings. The stub is therefore lenient: it is a
     * cross-cutting prerequisite for reaching the window logic on every code path,
     * including the out-of-window paths that then return {@code null}.</p>
     */
    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        processor = new TransactionReportProcessor(
                xrefRepository,
                typeRepository,
                categoryRepository,
                dateValidationService,
                registry,
                START_DATE,
                END_DATE);
        lenient().when(dateValidationService.isValidDate(anyString(), anyString()))
                .thenReturn(true);
    }

    @Test
    @DisplayName("includes a record whose processing date equals the inclusive start bound")
    void include_whenDateEqualsStart() {
        givenAllLookupsResolve();

        final Transaction t = tx(START_DATE, CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "1.00");

        assertThat(processor.process(t)).isNotNull();
    }

    @Test
    @DisplayName("includes a record whose processing date equals the inclusive end bound")
    void include_whenDateEqualsEnd() {
        givenAllLookupsResolve();

        final Transaction t = tx(END_DATE, CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "1.00");

        assertThat(processor.process(t)).isNotNull();
    }

    @Test
    @DisplayName("includes a record whose processing date lies strictly inside the window")
    void include_whenDateInsideWindow() {
        givenAllLookupsResolve();

        final Transaction t = tx("2022-07-15", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "1.00");

        assertThat(processor.process(t)).isNotNull();
    }

    @Test
    @DisplayName("filters (returns null) a record dated one day before the start bound")
    void exclude_whenDateBeforeStart_returnsNull() {
        final Transaction t = tx("2022-06-30", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "1.00");

        assertThat(processor.process(t)).isNull();

        // The window filter short-circuits before the 1500-A/B/C lookups.
        verifyNoInteractions(xrefRepository, typeRepository, categoryRepository);
        assertThat(registry.get(METRIC_REJECTED).tags(TAG_REASON, REASON_OUT_OF_WINDOW)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("filters (returns null) a record dated one day after the end bound")
    void exclude_whenDateAfterEnd_returnsNull() {
        final Transaction t = tx("2022-08-01", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "1.00");

        assertThat(processor.process(t)).isNull();

        verifyNoInteractions(xrefRepository, typeRepository, categoryRepository);
        assertThat(registry.get(METRIC_REJECTED).tags(TAG_REASON, REASON_OUT_OF_WINDOW)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("enriches the report line from the XREF, TRANTYPE and TRANCATG lookups")
    void enrichesLine_fromLookups() {
        when(xrefRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(xrefWithAcct(98765L)));
        when(typeRepository.findById(TYPE_CODE))
                .thenReturn(Optional.of(typeDesc("Purchase")));
        when(categoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)))
                .thenReturn(Optional.of(catDesc("Retail")));

        final Transaction t =
                tx("2022-07-15", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "45.67");

        final ReportLine line = processor.process(t);

        assertThat(line).isNotNull();
        assertThat(line.tranId()).isEqualTo(t.getTranId());
        assertThat(line.cardNumber()).isEqualTo(CARD_NUMBER);
        assertThat(line.accountId()).isEqualTo(98765L);
        assertThat(line.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(line.typeDescription()).isEqualTo("Purchase");
        assertThat(line.categoryCode()).isEqualTo(CATEGORY_CODE);
        assertThat(line.categoryDescription()).isEqualTo("Retail");
        assertThat(line.source()).isEqualTo(SOURCE);
        // Money compared with compareTo semantics, never equals (AAP §0.8.2).
        assertThat(line.amount()).isEqualByComparingTo(new BigDecimal("45.67"));

        // Two-arg key proof: the category lookup uses exactly (typeCode, categoryCode),
        // with no account-id component. Relies on TransactionCategoryId.equals.
        verify(categoryRepository).findById(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE));
    }

    @Test
    @DisplayName("leaves type/category descriptions blank (not null, no exception) on a lookup miss")
    void blankDescriptions_onLookupMiss() {
        when(xrefRepository.findById(CARD_NUMBER))
                .thenReturn(Optional.of(xrefWithAcct(98765L)));
        when(typeRepository.findById(TYPE_CODE)).thenReturn(Optional.empty());
        when(categoryRepository.findById(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE)))
                .thenReturn(Optional.empty());

        final Transaction t =
                tx("2022-07-15", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "10.00");

        final ReportLine line = processor.process(t);

        assertThat(line).isNotNull();
        // COBOL leaves the description spaces on a miss; the Java port emits "".
        assertThat(line.typeDescription()).isEmpty();
        assertThat(line.categoryDescription()).isEmpty();
        // The present cross-reference still resolves the account id.
        assertThat(line.accountId()).isEqualTo(98765L);
    }

    @Test
    @DisplayName("increments the processed counter once per emitted report line")
    void incrementsProcessedCounter_forEmittedLine() {
        final Transaction t =
                tx("2022-07-15", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "12.34");

        processor.process(t);

        assertThat(registry.get(METRIC_PROCESSED).counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("is read-only: enriches without persisting through any repository")
    void readsOnly_noPersistence() {
        final Transaction t =
                tx("2022-07-15", CARD_NUMBER, TYPE_CODE, CATEGORY_CODE, SOURCE, "12.34");

        processor.process(t);

        // Enrichment is read-only; totals and pagination belong to the writer/job.
        verify(xrefRepository, never()).save(any());
        verify(typeRepository, never()).save(any());
        verify(categoryRepository, never()).save(any());
    }

    /**
     * Stubs the three enrichment lookups to resolve to present values. Used by the
     * in-window boundary tests, which always invoke all three lookups, so every
     * stub is exercised (no Mockito {@code UnnecessaryStubbingException}).
     */
    private void givenAllLookupsResolve() {
        when(xrefRepository.findById(anyString()))
                .thenReturn(Optional.of(xrefWithAcct(98765L)));
        when(typeRepository.findById(anyString()))
                .thenReturn(Optional.of(typeDesc("Purchase")));
        when(categoryRepository.findById(any(TransactionCategoryId.class)))
                .thenReturn(Optional.of(catDesc("Retail")));
    }

    /**
     * Builds a {@link Transaction} whose {@code TRAN-PROC-TS} is the supplied
     * {@code yyyy-MM-dd} date followed by a fixed time-of-day, so that the
     * processor's {@code substring(0, 10)} yields exactly {@code procDate}.
     *
     * @param procDate the intended {@code yyyy-MM-dd} processing date
     * @param cardNum  the 16-character card number ({@code TRAN-CARD-NUM})
     * @param typeCd   the two-character transaction-type code ({@code TRAN-TYPE-CD})
     * @param catCd    the category code ({@code TRAN-CAT-CD})
     * @param source   the transaction source ({@code TRAN-SOURCE})
     * @param amt      the transaction amount ({@code TRAN-AMT}) as a decimal string
     * @return a populated transaction instance
     */
    private Transaction tx(final String procDate, final String cardNum, final String typeCd,
            final int catCd, final String source, final String amt) {
        final Transaction t = new Transaction();
        t.setTranId(TRAN_ID);
        t.setTranProcTs(procDate + "-10.15.30.123456");
        t.setTranCardNum(cardNum);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd);
        t.setTranSource(source);
        t.setTranAmt(new BigDecimal(amt));
        return t;
    }

    /**
     * Builds a cross-reference whose {@code XREF-ACCT-ID} is {@code accountId}; the
     * processor reads only that field from the {@code 1500-A} result.
     *
     * @param accountId the account id to expose
     * @return a populated cross-reference instance
     */
    private static CardCrossReference xrefWithAcct(final long accountId) {
        final CardCrossReference xref = new CardCrossReference();
        xref.setXrefCardNum(CARD_NUMBER);
        xref.setXrefAcctId(accountId);
        return xref;
    }

    /**
     * Builds a transaction-type whose {@code TRAN-TYPE-DESC} is {@code description};
     * the processor reads only that field from the {@code 1500-B} result.
     *
     * @param description the type description to expose
     * @return a populated transaction-type instance
     */
    private static TransactionType typeDesc(final String description) {
        final TransactionType type = new TransactionType();
        type.setTranType(TYPE_CODE);
        type.setTranTypeDesc(description);
        return type;
    }

    /**
     * Builds a transaction-category whose {@code TRAN-CAT-TYPE-DESC} is
     * {@code description}; the processor reads only that field from the
     * {@code 1500-C} result.
     *
     * @param description the category description to expose
     * @return a populated transaction-category instance
     */
    private static TransactionCategory catDesc(final String description) {
        final TransactionCategory category = new TransactionCategory();
        category.setId(new TransactionCategoryId(TYPE_CODE, CATEGORY_CODE));
        category.setTranCatTypeDesc(description);
        return category;
    }
}
