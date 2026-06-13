package com.cardemo.unit.batch.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cardemo.batch.processors.TransactionReportProcessor;
import com.cardemo.exception.RecordNotFoundException;
// TransactionReportLine import package (com.cardemo.model.dto) confirmed from the production
// TransactionReportProcessor.process(..) return type; the carrier is owned by the model.dto layer
// (shared with the report writer/job), NOT by this test folder, and is not duplicated here.
import com.cardemo.model.dto.TransactionReportLine;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionTypeRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fast, fully-mocked unit test for {@link TransactionReportProcessor} &mdash; the Spring Batch
 * {@code ItemProcessor<Transaction, TransactionReportLine>} that reproduces the
 * <strong>inclusive processing-date-range filter</strong> (CBTRN03C main-loop predicate L173-174)
 * and the three <strong>fatal-on-miss enrichment lookups</strong> ({@code 1500-A-LOOKUP-XREF} /
 * {@code 1500-B-LOOKUP-TRANTYPE} / {@code 1500-C-LOOKUP-TRANCATG}, L187/L190) of the legacy AWS
 * CardDemo transaction-detail-report batch program {@code app/cbl/CBTRN03C.cbl}.
 *
 * <h2>Provenance / governance</h2>
 * <p>The COBOL source {@code app/cbl/CBTRN03C.cbl} is <strong>read-only reference</strong> material
 * at the frozen legacy baseline commit SHA {@code 27d6c6f}; it is <strong>never copied</strong> into
 * this repository and is referenced here only by SHA and paragraph/line locator. Per the Minimal
 * Change Clause (AAP &sect;0.7.1), the 100% behavioural-parity requirement (AAP &sect;0.7.2) and the
 * control-flow-preservation rules (AAP &sect;0.7.4) these tests assert COBOL-identical filtering,
 * enrichment ordering and field mapping and invent nothing. The application base package is
 * {@code com.cardemo} (decision D-006, <em>not</em> {@code com.carddemo}).</p>
 *
 * <h2>The two headline parity facts this test locks down</h2>
 * <ol>
 *   <li><strong>Out-of-range &rarr; {@code return null} (Spring Batch FILTER).</strong> The inclusive
 *       date window {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <=
 *       WS-END-DATE} (CBTRN03C L173-174) processes the row; otherwise COBOL {@code NEXT SENTENCE}
 *       skips it. The faithful Spring Batch equivalent is to <strong>return {@code null}</strong>,
 *       which filters the item out of the chunk so it never reaches the report writer. This is the
 *       deliberate <em>opposite</em> of the sibling {@code TransactionPostingProcessor}, which must
 *       <em>carry</em> a business reject on a non-{@code null} {@code PostedTransactionResult} (with a
 *       {@code RejectCode}) and therefore <em>never</em> returns {@code null} &mdash; a {@code null}
 *       return there would make Spring Batch silently drop the reject from the chunk. Filtering and
 *       carrying are opposite contracts; this processor filters.</li>
 *   <li><strong>Enrichment miss is FATAL (not filtered, not carried).</strong> Each {@code 1500-*}
 *       keyed {@code READ ... INVALID KEY} in CBTRN03C moves {@code 23} to {@code IO-STATUS} and calls
 *       {@code 9999-ABEND-PROGRAM} (the program aborts). The faithful mapping is therefore to
 *       <strong>throw</strong> {@link RecordNotFoundException} (the typed mapping of
 *       {@code FILE STATUS '23'}, AAP &sect;0.7.5), failing the step &mdash; <em>not</em> to return a
 *       filtered {@code null} and <em>not</em> to carry a reject.</li>
 * </ol>
 *
 * <h2>Totals &amp; pagination are NOT the processor's job</h2>
 * <p>CBTRN03C's {@code WS-PAGE-TOTAL} / {@code WS-ACCOUNT-TOTAL} / {@code WS-GRAND-TOTAL} and the
 * page-header re-printing (L134-136) are cross-record state that a chunk-oriented, per-item processor
 * cannot own; they belong to the {@code com.cardemo.batch.writers} report writer (exercised under
 * {@code integration/batch}). This processor emits exactly one {@link TransactionReportLine} per
 * in-range row and accumulates nothing &mdash; the {@code DeferredToWriter} group documents and pins
 * that boundary.</p>
 *
 * <h2>Test strategy</h2>
 * <p>This is a pure unit test: {@code @ExtendWith(MockitoExtension.class)} with {@code @Mock}
 * repositories and the processor instantiated directly &mdash; <strong>no</strong> Spring context,
 * database, AWS, Testcontainers or {@code spring-batch-test}, and no new dependencies (JUnit&nbsp;5,
 * Mockito and AssertJ all ship with {@code spring-boot-starter-test}). Mockito runs in its default
 * {@code STRICT_STUBS} mode; because the production {@code process(..)} <em>filters first and only
 * then enriches</em>, an out-of-range row reaches no repository, so those tests stub nothing and
 * assert {@code verify(repo, never()).findById(any())}, while each enrichment test stubs exactly the
 * lookups its path consumes in the COBOL order XREF&nbsp;&rarr;&nbsp;TRANTYPE&nbsp;&rarr;&nbsp;TRANCATG
 * &mdash; so <strong>no {@code lenient()} is required</strong>. The monetary {@code tranAmt} is a
 * {@link BigDecimal} built from a {@link String} literal and compared with
 * {@code isEqualByComparingTo}, never {@code equals} (AAP &sect;0.7.3).</p>
 *
 * <h2>Note on {@code java.time} field type (deviation rationale)</h2>
 * <p>COBOL {@code TRAN-PROC-TS} is {@code PIC X(26)} text, but the migrated {@link Transaction} stores
 * it as a {@link LocalDateTime} (matching the {@code processed_timestamp TIMESTAMP} column), and the
 * production filter derives the 10-character {@code yyyy-MM-dd} prefix via
 * {@code getTranProcTs().toLocalDate().toString()} &mdash; byte-identical to the COBOL
 * {@code TRAN-PROC-TS (1:10)} substring. The {@link #txn} helper therefore builds a
 * {@link LocalDateTime} processing timestamp rather than a 26-character string; the upper-bound test
 * deliberately uses {@code 23:59:59} to prove the time-of-day is dropped and the whole end day is
 * in range, exactly as the COBOL date-only compare behaves.</p>
 *
 * @see TransactionReportProcessor
 * @see TransactionReportLine
 * @see RecordNotFoundException
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionReportProcessor — CBTRN03C inclusive date filter + fatal enrichment (SHA 27d6c6f)")
class TransactionReportProcessorTest {

    // --- Shared fixture constants -------------------------------------------------------------------

    /** Inclusive report window lower bound ({@code WS-START-DATE PIC X(10)}), {@code yyyy-MM-dd}. */
    private static final String START_DATE = "2022-01-01";

    /** Inclusive report window upper bound ({@code WS-END-DATE PIC X(10)}), {@code yyyy-MM-dd}. */
    private static final String END_DATE = "2022-12-31";

    /** Card number used as the cross-reference key ({@code TRAN-CARD-NUM}); a clean, fake test PAN. */
    private static final String CARD_NUM = "4111111111111111";

    /** Owning account id resolved from the cross-reference ({@code XREF-ACCT-ID PIC 9(11)}). */
    private static final Long ACCT_ID = 12345678901L;

    /** Owning customer id carried on the cross-reference (not read by the processor; set for realism). */
    private static final Long CUST_ID = 100000001L;

    /** Two-character transaction-type code ({@code TRAN-TYPE-CD PIC X(02)}). */
    private static final String TYPE_CD = "01";

    /** Four-digit numeric transaction-category code ({@code TRAN-CAT-CD PIC 9(04)}). */
    private static final Integer CAT_CD = 5;

    /** Type description resolved from {@code TRANTYPE} ({@code TRAN-TYPE-DESC PIC X(50)}). */
    private static final String TYPE_DESC = "PURCHASE";

    /** Category description resolved from {@code TRANCATG} ({@code TRAN-CAT-TYPE-DESC PIC X(50)}). */
    private static final String CAT_DESC = "RETAIL";

    /** Provenance token copied straight through ({@code TRAN-SOURCE PIC X(10)}), space-padded to 10. */
    private static final String SOURCE = "POS TERM  ";

    /** Sixteen-character transaction id ({@code TRAN-ID PIC X(16)}). */
    private static final String TXN_ID = "0000000000000001";

    // --- Boundary processing timestamps (TRAN-PROC-TS stored as LocalDateTime) ----------------------

    /** Strictly inside the window ({@code 2022-06-15}); the row is reported. */
    private static final LocalDateTime IN_RANGE_TS = LocalDateTime.of(2022, 6, 15, 12, 30, 0);

    /** Date component equals {@link #START_DATE} ({@code 2022-01-01}); lower bound is inclusive. */
    private static final LocalDateTime LOWER_BOUND_TS = LocalDateTime.of(2022, 1, 1, 0, 0, 0);

    /**
     * Date component equals {@link #END_DATE} ({@code 2022-12-31}) with a late time-of-day; upper
     * bound is inclusive and {@code toLocalDate()} drops the {@code 23:59:59} so the whole end day
     * qualifies (COBOL compares the date prefix only).
     */
    private static final LocalDateTime UPPER_BOUND_TS = LocalDateTime.of(2022, 12, 31, 23, 59, 59);

    /** One day before the window ({@code 2021-12-31}); the row is filtered out. */
    private static final LocalDateTime BEFORE_START_TS = LocalDateTime.of(2021, 12, 31, 23, 59, 59);

    /** One day after the window ({@code 2023-01-01}); the row is filtered out. */
    private static final LocalDateTime AFTER_END_TS = LocalDateTime.of(2023, 1, 1, 0, 0, 0);

    @Mock
    private TransactionTypeRepository transactionTypeRepository;

    @Mock
    private TransactionCategoryRepository transactionCategoryRepository;

    @Mock
    private CardCrossReferenceRepository crossReferenceRepository;

    private TransactionReportProcessor processor;

    @BeforeEach
    void setUp() {
        // Constructor arg order mirrors the production @Autowired constructor exactly:
        // (TransactionTypeRepository, TransactionCategoryRepository, CardCrossReferenceRepository).
        processor = new TransactionReportProcessor(
                transactionTypeRepository, transactionCategoryRepository, crossReferenceRepository);
        // WS-START-DATE / WS-END-DATE arrive in production via Spring Batch late binding
        // (@Value("#{jobParameters['startDate']}")). The production class exposes the same setters
        // Spring calls, so the window is set directly here -- no Spring context, no ReflectionTestUtils.
        processor.setStartDate(START_DATE);
        processor.setEndDate(END_DATE);
    }

    // --- Builders -----------------------------------------------------------------------------------

    /**
     * Builds a {@link Transaction} carrying only the fields the processor reads: the id, the processing
     * timestamp (the date-window key), the type/category codes, the card number (the XREF key) and the
     * amount. {@code TRAN-SOURCE} is fixed to {@link #SOURCE}. The processing timestamp is a
     * {@link LocalDateTime} because the migrated entity stores {@code TRAN-PROC-TS PIC X(26)} that way
     * (see the class Javadoc deviation note).
     *
     * @param tranId  the transaction id ({@code TRAN-ID})
     * @param procTs  the processing timestamp ({@code TRAN-PROC-TS}); its date component drives the filter
     * @param typeCd  the transaction-type code ({@code TRAN-TYPE-CD})
     * @param catCd   the transaction-category code ({@code TRAN-CAT-CD})
     * @param cardNum the card number ({@code TRAN-CARD-NUM}); the XREF lookup key
     * @param amt     the transaction amount ({@code TRAN-AMT}) as a decimal string literal
     * @return a populated transaction fixture
     */
    private Transaction txn(String tranId, LocalDateTime procTs, String typeCd, Integer catCd,
                            String cardNum, String amt) {
        Transaction t = new Transaction();
        t.setTranId(tranId);
        t.setTranProcTs(procTs);
        t.setTranTypeCd(typeCd);
        t.setTranCatCd(catCd);
        t.setTranSource(SOURCE);
        t.setTranCardNum(cardNum);
        t.setTranAmt(new BigDecimal(amt));
        return t;
    }

    /** A {@code TRANTYPE} reference row ({@code 1500-B} target). */
    private TransactionType type(String code, String desc) {
        return new TransactionType(code, desc);
    }

    /** A {@code TRANCATG} reference row keyed on {@code (TYPE_CD, CAT_CD)} ({@code 1500-C} target). */
    private TransactionCategory cat(String desc) {
        return new TransactionCategory(new TransactionCategoryId(TYPE_CD, CAT_CD), desc);
    }

    /** A {@code CARDXREF} junction row resolving the card number to {@code acctId} ({@code 1500-A} target). */
    private CardCrossReference xref(Long acctId) {
        CardCrossReference x = new CardCrossReference();
        x.setXrefCardNum(CARD_NUM);
        x.setXrefCustId(CUST_ID);
        x.setXrefAcctId(acctId);
        return x;
    }

    /**
     * Stubs all three enrichment lookups to resolve successfully for the canonical fixture, in the
     * COBOL order the production processor consumes them: XREF (account id) &rarr; TRANTYPE (type
     * description) &rarr; TRANCATG (category description, keyed by the exact composite
     * {@code (TYPE_CD, CAT_CD)}). Every stub here lies on the consumed path for an in-range row, so it
     * is compatible with {@code STRICT_STUBS}.
     */
    private void stubEnrichmentPresent() {
        when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
        when(transactionTypeRepository.findById(TYPE_CD)).thenReturn(Optional.of(type(TYPE_CD, TYPE_DESC)));
        when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CD, CAT_CD)))
                .thenReturn(Optional.of(cat(CAT_DESC)));
    }

    /**
     * The inclusive processing-date-range filter &mdash; CBTRN03C main-loop predicate (L173-174):
     * {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <= WS-END-DATE} processes
     * the row, otherwise {@code NEXT SENTENCE} skips it. In Spring Batch the skip is a
     * {@code return null} (the item is filtered out of the chunk). Both bounds are inclusive and the
     * compare is lexicographic on the {@code yyyy-MM-dd} date prefix.
     */
    @Nested
    @DisplayName("Inclusive date-range filter (CBTRN03C L173-174: TRAN-PROC-TS(1:10) >= start AND <= end)")
    class InclusiveDateRangeFilter {

        @Test
        @DisplayName("in-range transaction produces a report line")
        void inRangeTransactionProducesReportLine() {
            stubEnrichmentPresent();

            TransactionReportLine line =
                    processor.process(txn(TXN_ID, IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "123.45"));

            assertThat(line).isNotNull();
        }

        @Test
        @DisplayName("lower bound is inclusive: procTs date == start date is included")
        void lowerBoundIsInclusive() {
            stubEnrichmentPresent();

            // COBOL '>= WS-START-DATE': equality on the lower bound qualifies.
            TransactionReportLine line =
                    processor.process(txn(TXN_ID, LOWER_BOUND_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00"));

            assertThat(line).isNotNull();
        }

        @Test
        @DisplayName("upper bound is inclusive: procTs date == end date is included (time-of-day dropped)")
        void upperBoundIsInclusive() {
            stubEnrichmentPresent();

            // UPPER_BOUND_TS is 2022-12-31T23:59:59; toLocalDate() yields 2022-12-31 == END_DATE, so the
            // whole end day qualifies under COBOL '<= WS-END-DATE' -- the time-of-day is irrelevant.
            TransactionReportLine line =
                    processor.process(txn(TXN_ID, UPPER_BOUND_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00"));

            assertThat(line).isNotNull();
        }

        @Test
        @DisplayName("out-of-range transaction is FILTERED via return null (contrast: posting carries rejects)")
        void beforeStartIsFilteredViaReturnNull() {
            // procTs 2021-12-31 < WS-START-DATE -> COBOL NEXT SENTENCE skip -> Spring Batch filter (null).
            // HEADLINE PARITY FACT: returning null is the Spring Batch ItemProcessor "drop this item"
            // signal, so the row never reaches the report writer. This is the deliberate OPPOSITE of the
            // sibling TransactionPostingProcessor, which carries a business reject on a non-null
            // PostedTransactionResult (with a RejectCode) and NEVER returns null -- a null return there
            // would make Spring Batch silently drop the reject from the chunk.
            assertThat(processor.process(txn(TXN_ID, BEFORE_START_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00")))
                    .isNull();

            // The filter short-circuits BEFORE any enrichment lookup (production filters first, then
            // enriches), so none of the three repositories is touched for a filtered row.
            verify(crossReferenceRepository, never()).findById(any());
            verify(transactionTypeRepository, never()).findById(any());
            verify(transactionCategoryRepository, never()).findById(any());
        }

        @Test
        @DisplayName("after-end transaction is FILTERED via return null")
        void afterEndIsFilteredViaReturnNull() {
            // procTs 2023-01-01 > WS-END-DATE -> filtered out (null); again, no enrichment is performed.
            assertThat(processor.process(txn(TXN_ID, AFTER_END_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00")))
                    .isNull();

            verify(crossReferenceRepository, never()).findById(any());
            verify(transactionTypeRepository, never()).findById(any());
            verify(transactionCategoryRepository, never()).findById(any());
        }

        @Test
        @DisplayName("missing processing timestamp is filtered via return null (defensive parity)")
        void nullProcessingTimestampIsFiltered() {
            // Defensive parity: COBOL TRAN-PROC-TS is a fixed PIC X(26) field whose spaces would sort
            // below any real start bound and so could never fall inside [start, end]; the relational
            // analog of an absent value is null, which the processor filters out (return null) rather
            // than raising an NPE -- still without performing any enrichment.
            assertThat(processor.process(txn(TXN_ID, null, TYPE_CD, CAT_CD, CARD_NUM, "10.00")))
                    .isNull();

            verify(crossReferenceRepository, never()).findById(any());
            verify(transactionTypeRepository, never()).findById(any());
            verify(transactionCategoryRepository, never()).findById(any());
        }
    }

    /**
     * Enrichment field mapping &mdash; the per-row half of CBTRN03C's main loop: resolve the owning
     * account id ({@code 1500-A-LOOKUP-XREF}), the type description ({@code 1500-B-LOOKUP-TRANTYPE}) and
     * the category description ({@code 1500-C-LOOKUP-TRANCATG}), then assemble the eight detail fields in
     * COBOL {@code 1120-WRITE-DETAIL} order.
     */
    @Nested
    @DisplayName("Enrichment field mapping (CBTRN03C 1500-A/B/C + 1120-WRITE-DETAIL)")
    class Enrichment {

        @Test
        @DisplayName("report line enriched with type/category descriptions and resolved account id")
        void reportLineEnrichedWithLookups() {
            stubEnrichmentPresent();

            TransactionReportLine line =
                    processor.process(txn(TXN_ID, IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "123.45"));

            assertThat(line).isNotNull();
            // Fields copied straight from the transaction (1120-WRITE-DETAIL order).
            assertThat(line.tranId()).isEqualTo(TXN_ID);
            assertThat(line.tranTypeCd()).isEqualTo(TYPE_CD);
            assertThat(line.tranCatCd()).isEqualTo(CAT_CD);
            assertThat(line.tranSource()).isEqualTo(SOURCE);
            // Monetary amount uses compareTo semantics, never equals (AAP 0.7.3).
            assertThat(line.tranAmt()).isEqualByComparingTo(new BigDecimal("123.45"));
            // Enriched/resolved fields.
            assertThat(line.accountId()).isEqualTo(ACCT_ID);          // XREF-ACCT-ID (1500-A)
            assertThat(line.tranTypeDesc()).isEqualTo(TYPE_DESC);     // TRAN-TYPE-DESC (1500-B)
            assertThat(line.tranCatTypeDesc()).isEqualTo(CAT_DESC);   // TRAN-CAT-TYPE-DESC (1500-C)
        }

        @Test
        @DisplayName("1500-C category lookup uses composite key new TransactionCategoryId(typeCd, catCd)")
        void categoryLookupUsesCompositeKeyInCobolFieldOrder() {
            stubEnrichmentPresent();

            processor.process(txn(TXN_ID, IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "123.45"));

            // Capture the actual key passed to the TRANCATG lookup and prove its components were built in
            // the COBOL TRAN-CAT-KEY field order (type code, then category code).
            ArgumentCaptor<TransactionCategoryId> keyCaptor =
                    ArgumentCaptor.forClass(TransactionCategoryId.class);
            verify(transactionCategoryRepository).findById(keyCaptor.capture());
            TransactionCategoryId usedKey = keyCaptor.getValue();
            assertThat(usedKey.getTypeCode()).isEqualTo(TYPE_CD);
            assertThat(usedKey.getCatCode()).isEqualTo(CAT_CD);
        }
    }

    /**
     * Enrichment misses are FATAL &mdash; each {@code 1500-*} keyed {@code READ ... INVALID KEY} in
     * CBTRN03C moves {@code 23} to {@code IO-STATUS} and calls {@code 9999-ABEND-PROGRAM} (the program
     * aborts). The faithful mapping throws {@link RecordNotFoundException} (the typed mapping of
     * {@code FILE STATUS '23'}, AAP &sect;0.7.5), failing the step &mdash; a missing reference row is
     * NOT silently filtered (return null) and NOT carried like a posting reject. Stubbing follows the
     * production lookup order XREF &rarr; TRANTYPE &rarr; TRANCATG, so each test stubs only up to (and
     * including) the lookup that misses.
     */
    @Nested
    @DisplayName("Enrichment miss is FATAL (CBTRN03C 1500-* INVALID KEY -> 9999-ABEND -> RecordNotFoundException)")
    class EnrichmentMissIsFatal {

        @Test
        @DisplayName("missing XREF (card cross-reference) is fatal (RecordNotFoundException), not a skip")
        void missingXrefIsFatal() {
            // XREF is the first enrichment lookup (1500-A); an empty Optional models READ ... INVALID KEY.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    processor.process(txn(TXN_ID, IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00")))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("missing TRANTYPE is fatal (RecordNotFoundException), not a skip")
        void missingTransactionTypeIsFatal() {
            // XREF (1500-A) must resolve before TRANTYPE (1500-B) is reached.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(transactionTypeRepository.findById(TYPE_CD)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    processor.process(txn(TXN_ID, IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00")))
                    .isInstanceOf(RecordNotFoundException.class);
        }

        @Test
        @DisplayName("missing TRANCATG (category) is fatal (RecordNotFoundException), not a skip")
        void missingTransactionCategoryIsFatal() {
            // XREF (1500-A) and TRANTYPE (1500-B) must resolve before TRANCATG (1500-C) is reached.
            when(crossReferenceRepository.findById(CARD_NUM)).thenReturn(Optional.of(xref(ACCT_ID)));
            when(transactionTypeRepository.findById(TYPE_CD))
                    .thenReturn(Optional.of(type(TYPE_CD, TYPE_DESC)));
            when(transactionCategoryRepository.findById(new TransactionCategoryId(TYPE_CD, CAT_CD)))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    processor.process(txn(TXN_ID, IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "10.00")))
                    .isInstanceOf(RecordNotFoundException.class);
            // Contrast: a missing reference row is FATAL (abend parity) -- it must NOT be silently
            // filtered like an out-of-range date (return null) and must NOT be carried like a posting
            // reject. Failing the step prevents bad reference data from corrupting the report.
        }
    }

    /**
     * Totals &amp; pagination are deferred to the writer &mdash; CBTRN03C's {@code WS-PAGE-TOTAL} /
     * {@code WS-ACCOUNT-TOTAL} / {@code WS-GRAND-TOTAL} and page-header re-printing (L134-136) are
     * cross-record state that a stateless, chunk-oriented per-item processor cannot own. This processor
     * emits exactly one {@link TransactionReportLine} per in-range row and accumulates nothing; the
     * totals/pagination live in the {@code com.cardemo.batch.writers} report writer and are exercised
     * under {@code integration/batch}.
     */
    @Nested
    @DisplayName("Totals & pagination deferred to the writer (CBTRN03C L134-136 are cross-record state)")
    class DeferredToWriter {

        @Test
        @DisplayName("processor emits one line per in-range row and accumulates no running totals")
        void processorEmitsOneLinePerRowWithoutAccumulation() {
            // Both rows share the same card/type/category, so the canonical stubs serve both calls.
            stubEnrichmentPresent();

            TransactionReportLine first = processor.process(
                    txn("0000000000000001", IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "100.00"));
            TransactionReportLine second = processor.process(
                    txn("0000000000000002", IN_RANGE_TS, TYPE_CD, CAT_CD, CARD_NUM, "250.00"));

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            // Each emitted line carries its OWN amount; the processor keeps no WS-PAGE-TOTAL /
            // WS-ACCOUNT-TOTAL / WS-GRAND-TOTAL running total, so the second line is 250.00, NOT
            // 100.00 + 250.00. The page/account/grand totals and header re-printing (L134-136) are
            // cross-record state owned by the report writer (com.cardemo.batch.writers) and exercised
            // under integration/batch -- never by this stateless per-item processor.
            assertThat(first.tranAmt()).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(second.tranAmt()).isEqualByComparingTo(new BigDecimal("250.00"));
            // The processor's sole output is a single carrier per row (one TransactionReportLine),
            // confirming it neither aggregates across rows nor exposes any totals API.
            assertThat(second.accountId()).isEqualTo(ACCT_ID);
        }
    }
}
