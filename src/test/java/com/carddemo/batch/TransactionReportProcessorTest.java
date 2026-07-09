package com.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.entity.CardXref;
import com.carddemo.entity.Transaction;
import com.carddemo.entity.TransactionCategoryType;
import com.carddemo.entity.TransactionCategoryTypeId;
import com.carddemo.entity.TransactionType;
import com.carddemo.repository.CardXrefRepository;
import com.carddemo.repository.TransactionCategoryTypeRepository;
import com.carddemo.repository.TransactionTypeRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure, fast unit tests for {@link TransactionReportProcessor} — the chunk-step {@code ItemProcessor}
 * that realises the per-record logic of the CardDemo transaction-detail report (processor #5 of 5 in
 * the batch pipeline, wired into {@code TransactionReportJob}).
 *
 * <p><strong>COBOL lineage (reference-only, frozen source SHA {@code 27d6c6f} — not copied into this
 * repository).</strong> The class under test is the Java translation of two collaborating legacy
 * artifacts, and these tests lock the behaviour that translation must preserve:</p>
 * <ul>
 *   <li>the DFSORT {@code INCLUDE COND} of {@code app/jcl/TRANREPT.jcl} / {@code app/proc/TRANREPT.prc}
 *       — {@code INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,TRAN-PROC-DT,LE,PARM-END-DATE)}
 *       with {@code PARM-START-DATE='2022-01-01'} and {@code PARM-END-DATE='2022-07-06'} — which keeps
 *       a transaction iff its processing date is <em>inside the inclusive window</em>; and</li>
 *   <li>the {@code 1120-WRITE-DETAIL} paragraph of {@code app/cbl/CBTRN03C.cbl} (with its enrichment
 *       reads {@code 1500-A-LOOKUP-XREF}, {@code 1500-B-LOOKUP-TRANTYPE} and
 *       {@code 1500-C-LOOKUP-TRANCATG}), whose eight {@code MOVE ... TO TRAN-REPORT-*} statements map
 *       one-to-one to the components of {@link ReportDetailLine}.</li>
 * </ul>
 *
 * <h2>What is asserted (and why it matters for parity)</h2>
 * <ol>
 *   <li><strong>Inclusive date-window filter.</strong> {@code CBTRN03C} guards each record with
 *       {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND <= WS-END-DATE}; the boundary dates are
 *       kept. A record outside the window is dropped by returning {@code null} (Spring Batch's
 *       "filter this item" signal), never by throwing. If the boundary semantics drifted by a single
 *       day the report row count would diverge from the mainframe baseline (Gate&nbsp;1 / Gate&nbsp;5).</li>
 *   <li><strong>Enrichment + decimal fidelity.</strong> A surviving record is joined to its owning
 *       account (via the card cross-reference), its transaction-type description and its
 *       transaction-category description, and the monetary amount is carried as a scale-2
 *       {@link BigDecimal} (COBOL {@code TRAN-AMT PIC S9(09)V99}; AAP §0.8.2). The writer's page /
 *       account / grand totals depend on every emitted line carrying a scale-2 amount.</li>
 *   <li><strong>Not-found parity refinement.</strong> The legacy lookups abend
 *       ({@code 9999-ABEND-PROGRAM}) on a missing key; the Java processor refines this to keep the
 *       report well-formed — a missing cross-reference <em>excludes</em> the row ({@code null}), while
 *       a missing type/category description resolves to the empty string (the COBOL
 *       {@code INITIALIZE} blank default). The rationale is recorded in {@code docs/decision-log.md};
 *       these tests assert the production (Java) behaviour, not the legacy abend.</li>
 * </ol>
 *
 * <h2>Test strategy</h2>
 * <p>The three collaborating repositories are supplied as Mockito mocks and the {@code @StepScope}
 * bean is constructed directly through its single constructor (the reporting window is passed as the
 * two {@code String} bounds the constructor accepts), so the suite loads no Spring context and
 * touches no database, Testcontainers, Docker or live AWS. It runs in milliseconds and feeds the
 * JaCoCo line-coverage gate (Gate&nbsp;8, &ge;80%). Mockito runs under its default
 * {@code STRICT_STUBS} strictness, so each test stubs only the lookups it exercises; the filter tests
 * additionally assert that short-circuited paths consult no repository. Monetary assertions use
 * {@link BigDecimal#compareTo(BigDecimal)} (value equality, scale-independent) plus an explicit
 * {@link BigDecimal#scale()} check — never {@code float}/{@code double}. The suite compiles clean
 * under {@code -Xlint:all} (Gate&nbsp;2). No COBOL source is reproduced here.</p>
 */
@DisplayName("TransactionReportProcessor — CBTRN03C INCLUDE-COND window + 1120-WRITE-DETAIL enrichment (SHA 27d6c6f)")
@ExtendWith(MockitoExtension.class)
class TransactionReportProcessorTest {

    // ---------------------------------------------------------------------------------------------
    // Fixture constants. The window matches the legacy JCL PARM defaults (2022-01-01 .. 2022-07-06)
    // and brackets the Gate-1 fixture date 2022-06-10.
    // ---------------------------------------------------------------------------------------------

    /** Inclusive window start — legacy {@code PARM-START-DATE} / {@code WS-START-DATE}. */
    private static final String START_DATE = "2022-01-01";

    /** Inclusive window end — legacy {@code PARM-END-DATE} / {@code WS-END-DATE}. */
    private static final String END_DATE = "2022-07-06";

    /** 16-character transaction id ({@code TRAN-ID PIC X(16)}). */
    private static final String TRAN_ID = "TRAN000000000001";

    /** 16-character card number ({@code TRAN-CARD-NUM PIC X(16)}); the cross-reference lookup key. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Owning account id resolved via the cross-reference ({@code XREF-ACCT-ID PIC 9(11)}). */
    private static final long ACCOUNT_ID = 12345678901L;

    /** Transaction-source text ({@code TRAN-SOURCE PIC X(10)}). */
    private static final String SOURCE = "POS";

    /** Transaction-type code ({@code TRAN-TYPE-CD PIC X(02)}). */
    private static final String TYPE_CODE = "PU";

    /** Transaction-category code ({@code TRAN-CAT-CD PIC 9(04)}). */
    private static final int CATEGORY_CODE = 5;

    /** Known transaction-type description returned by the type lookup ({@code TRAN-TYPE-DESC}). */
    private static final String TYPE_DESCRIPTION = "PURCHASE";

    /** Known category description returned by the category lookup ({@code TRAN-CAT-TYPE-DESC}). */
    private static final String CATEGORY_DESCRIPTION = "RETAIL PURCHASE";

    /**
     * Source amount carried at scale 1 on purpose: the processor emits it through
     * {@link ReportDetailLine}, whose canonical constructor re-normalizes to scale 2, so the
     * enrichment test can prove the emitted amount is value-equal <em>and</em> scale-2.
     */
    private static final String AMOUNT_IN = "1234.5";

    @Mock
    private CardXrefRepository cardXrefRepository;

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryTypeRepository transactionCategoryTypeRepository;

    /** The class under test, bound to the standard reporting window in {@link #setUp()}. */
    private TransactionReportProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TransactionReportProcessor(
                cardXrefRepository,
                transactionTypeRepository,
                transactionCategoryTypeRepository,
                START_DATE,
                END_DATE);
    }

    // ---------------------------------------------------------------------------------------------
    // Builders.
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds a {@link Transaction} for a test. Fields not relevant to the processor's decisions
     * ({@code TRAN-ID}, {@code TRAN-SOURCE}) are set to stable constants so mapping assertions have a
     * known expectation.
     *
     * @param tranProcTs the processing timestamp text ({@code TRAN-PROC-TS PIC X(26)}); the processor
     *                   reads only its first 10 characters as the processing date. May be {@code null}
     *                   or short to exercise the malformed-date guard.
     * @param cardNum    the card number ({@code TRAN-CARD-NUM}); may be {@code null} to exercise the
     *                   null-card guard.
     * @param typeCd     the transaction-type code ({@code TRAN-TYPE-CD}); may be {@code null}.
     * @param catCd      the transaction-category code ({@code TRAN-CAT-CD}) as text; parsed to an
     *                   {@link Integer}. May be {@code null}.
     * @param amount     the transaction amount ({@code TRAN-AMT}) as text; parsed to a
     *                   {@link BigDecimal}.
     * @return a populated {@link Transaction}
     */
    private static Transaction tx(String tranProcTs, String cardNum, String typeCd,
            String catCd, String amount) {
        Transaction t = new Transaction();
        t.setTranId(TRAN_ID);
        t.setTranProcTs(tranProcTs);
        t.setTranCardNum(cardNum);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd == null ? null : Integer.valueOf(catCd));
        t.setTranSource(SOURCE);
        t.setTranAmt(new BigDecimal(amount));
        return t;
    }

    /**
     * Expands a {@code yyyy-MM-dd} date into a full 26-character {@code TRAN-PROC-TS} timestamp
     * ({@code yyyy-mm-dd-hh.mm.ss.ffffff}); only the leading 10 characters (the date) are significant
     * to the processor, so the time portion is a fixed placeholder.
     *
     * @param date the {@code yyyy-MM-dd} processing date
     * @return a 26-character timestamp string beginning with {@code date}
     */
    private static String ts(String date) {
        return date + "-12.00.00.000000";
    }

    /**
     * Builds a {@link CardXref} whose {@code XREF-ACCT-ID} is {@link #ACCOUNT_ID}, matching what the
     * card cross-reference lookup ({@code 1500-A-LOOKUP-XREF}) returns for {@link #CARD_NUMBER}.
     *
     * @return a cross-reference stub carrying the owning account id
     */
    private static CardXref cardXref() {
        CardXref xref = new CardXref();
        xref.setXrefCardNum(CARD_NUMBER);
        xref.setXrefAcctId(ACCOUNT_ID);
        return xref;
    }

    /**
     * Builds a {@link TransactionType} carrying {@link #TYPE_DESCRIPTION} for
     * {@code 1500-B-LOOKUP-TRANTYPE}.
     *
     * @return a transaction-type stub with a known description
     */
    private static TransactionType transactionType() {
        TransactionType type = new TransactionType();
        type.setTranType(TYPE_CODE);
        type.setTranTypeDesc(TYPE_DESCRIPTION);
        return type;
    }

    /**
     * Builds a {@link TransactionCategoryType} carrying {@link #CATEGORY_DESCRIPTION} for
     * {@code 1500-C-LOOKUP-TRANCATG}.
     *
     * @return a transaction-category-type stub with a known description
     */
    private static TransactionCategoryType transactionCategoryType() {
        TransactionCategoryType category = new TransactionCategoryType();
        category.setId(new TransactionCategoryTypeId(TYPE_CODE, CATEGORY_CODE));
        category.setTranCatTypeDesc(CATEGORY_DESCRIPTION);
        return category;
    }

    // ---------------------------------------------------------------------------------------------
    // Constructor validation — fail-fast on an invalid or inverted reporting window.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Constructor rejects an inverted window (startDate after endDate)")
    void constructorRejectsInvertedWindow() {
        assertThatThrownBy(() -> new TransactionReportProcessor(
                cardXrefRepository,
                transactionTypeRepository,
                transactionCategoryTypeRepository,
                END_DATE,
                START_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inverted");
    }

    @Test
    @DisplayName("Constructor rejects a non-ISO start date")
    void constructorRejectsMalformedStartDate() {
        assertThatThrownBy(() -> new TransactionReportProcessor(
                cardXrefRepository,
                transactionTypeRepository,
                transactionCategoryTypeRepository,
                "not-a-date",
                END_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid ISO-8601");
    }

    @Test
    @DisplayName("Constructor rejects a null start date")
    void constructorRejectsNullStartDate() {
        assertThatThrownBy(() -> new TransactionReportProcessor(
                cardXrefRepository,
                transactionTypeRepository,
                transactionCategoryTypeRepository,
                null,
                END_DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    // ---------------------------------------------------------------------------------------------
    // Date-window filter (INCLUDE COND — inclusive boundaries).
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Date before the window start is filtered out (null) and consults no repository")
    void beforeWindowStartIsFiltered() {
        ReportDetailLine result = processor.process(
                tx(ts("2021-12-31"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verifyNoInteractions(cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository);
    }

    @Test
    @DisplayName("Date after the window end is filtered out (null) and consults no repository")
    void afterWindowEndIsFiltered() {
        ReportDetailLine result = processor.process(
                tx(ts("2022-07-07"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verifyNoInteractions(cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository);
    }

    @Test
    @DisplayName("Date exactly on the window start is included (inclusive lower bound)")
    void onWindowStartIsIncluded() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(cardXref()));

        ReportDetailLine result = processor.process(
                tx(ts(START_DATE), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @DisplayName("Date exactly on the window end is included (inclusive upper bound)")
    void onWindowEndIsIncluded() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(cardXref()));

        ReportDetailLine result = processor.process(
                tx(ts(END_DATE), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @DisplayName("Date mid-window is included (brackets the Gate-1 fixture date 2022-06-10)")
    void midWindowIsIncluded() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(cardXref()));

        ReportDetailLine result = processor.process(
                tx(ts("2022-06-10"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Processing-date extraction guards — a null, short or malformed TRAN-PROC-TS is treated as
    // "outside the window" and filtered out before any enrichment lookup.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Null processing timestamp is filtered out (null) and consults no repository")
    void nullProcessingTimestampIsFiltered() {
        ReportDetailLine result = processor.process(
                tx(null, CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verifyNoInteractions(cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository);
    }

    @Test
    @DisplayName("Too-short processing timestamp (< 10 chars) is filtered out (null)")
    void shortProcessingTimestampIsFiltered() {
        ReportDetailLine result = processor.process(
                tx("2022", CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verifyNoInteractions(cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository);
    }

    @Test
    @DisplayName("Malformed processing date (not a valid ISO date) is filtered out (null)")
    void malformedProcessingDateIsFiltered() {
        ReportDetailLine result = processor.process(
                tx(ts("2022-13-99"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verifyNoInteractions(cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository);
    }

    // ---------------------------------------------------------------------------------------------
    // Enrichment + decimal fidelity — an in-window record is joined to account, type and category,
    // and emits a ReportDetailLine whose eight fields map 1:1 to 1120-WRITE-DETAIL.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("In-window record is enriched with account, type/category descriptions and a scale-2 amount")
    void inWindowRecordIsFullyEnriched() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(cardXref()));
        when(transactionTypeRepository.findById(TYPE_CODE))
                .thenReturn(Optional.of(transactionType()));
        when(transactionCategoryTypeRepository.findById(
                new TransactionCategoryTypeId(TYPE_CODE, CATEGORY_CODE)))
                .thenReturn(Optional.of(transactionCategoryType()));

        ReportDetailLine result = processor.process(
                tx(ts("2022-06-10"), CARD_NUMBER, TYPE_CODE, "5", AMOUNT_IN));

        assertThat(result).isNotNull();
        // Direct MOVEs from the transaction (1120-WRITE-DETAIL).
        assertThat(result.transactionId()).isEqualTo(TRAN_ID);
        assertThat(result.source()).isEqualTo(SOURCE);
        assertThat(result.typeCode()).isEqualTo(TYPE_CODE);
        assertThat(result.categoryCode()).isEqualTo(CATEGORY_CODE);
        // Enrichment lookups (1500-A / 1500-B / 1500-C).
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(result.typeDescription()).isEqualTo(TYPE_DESCRIPTION);
        assertThat(result.categoryDescription()).isEqualTo(CATEGORY_DESCRIPTION);
        // Decimal fidelity (AAP §0.8.2): value-equal (compareTo) and normalized to scale 2.
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("1234.50"));
        assertThat(result.amount().scale()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------
    // Not-found parity refinement — the exact production fallbacks (read from the source, not
    // invented): missing cross-reference EXCLUDES the row; a missing type/category description
    // resolves to the empty string.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Missing card cross-reference excludes the row (null) and skips the description lookups")
    void missingCrossReferenceExcludesRow() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.empty());

        ReportDetailLine result = processor.process(
                tx(ts("2022-06-10"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verify(cardXrefRepository).findById(CARD_NUMBER);
        verifyNoInteractions(transactionTypeRepository, transactionCategoryTypeRepository);
    }

    @Test
    @DisplayName("Null card number excludes the row (null) before any repository is consulted")
    void nullCardNumberExcludesRow() {
        ReportDetailLine result = processor.process(
                tx(ts("2022-06-10"), null, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNull();
        verifyNoInteractions(cardXrefRepository, transactionTypeRepository,
                transactionCategoryTypeRepository);
    }

    @Test
    @DisplayName("Missing transaction-type description resolves to the empty string (COBOL INITIALIZE blank)")
    void missingTypeDescriptionResolvesToBlank() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(cardXref()));
        when(transactionTypeRepository.findById(TYPE_CODE)).thenReturn(Optional.empty());

        ReportDetailLine result = processor.process(
                tx(ts("2022-06-10"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNotNull();
        assertThat(result.typeDescription()).isEmpty();
        // The row still survives — only the description is blank.
        assertThat(result.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @DisplayName("Missing transaction-category description resolves to the empty string")
    void missingCategoryDescriptionResolvesToBlank() {
        when(cardXrefRepository.findById(CARD_NUMBER)).thenReturn(Optional.of(cardXref()));
        when(transactionTypeRepository.findById(TYPE_CODE))
                .thenReturn(Optional.of(transactionType()));
        when(transactionCategoryTypeRepository.findById(
                new TransactionCategoryTypeId(TYPE_CODE, CATEGORY_CODE)))
                .thenReturn(Optional.empty());

        ReportDetailLine result = processor.process(
                tx(ts("2022-06-10"), CARD_NUMBER, TYPE_CODE, "5", "10.00"));

        assertThat(result).isNotNull();
        assertThat(result.categoryDescription()).isEmpty();
        // The type description still resolves normally.
        assertThat(result.typeDescription()).isEqualTo(TYPE_DESCRIPTION);
    }
}
