package com.cardemo.batch.processors;

import com.cardemo.exception.RecordNotFoundException;
import com.cardemo.model.dto.TransactionReportLine;
import com.cardemo.model.entity.CardCrossReference;
import com.cardemo.model.entity.Transaction;
import com.cardemo.model.entity.TransactionCategory;
import com.cardemo.model.entity.TransactionType;
import com.cardemo.model.key.TransactionCategoryId;
import com.cardemo.repository.CardCrossReferenceRepository;
import com.cardemo.repository.TransactionCategoryRepository;
import com.cardemo.repository.TransactionTypeRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Spring Batch {@link ItemProcessor} that reproduces the per-transaction
 * <strong>date-range filtering</strong> and <strong>reference-data
 * enrichment</strong> of the legacy AWS CardDemo batch program
 * <strong>{@code CBTRN03C}</strong> (Transaction Detail Report) in the greenfield
 * Java&nbsp;25 LTS + Spring Boot&nbsp;3.5.x migration.
 *
 * <h2>Provenance</h2>
 * <p>Translated from COBOL {@code app/cbl/CBTRN03C.cbl} at the frozen legacy
 * baseline commit SHA {@code 27d6c6f}. The COBOL source is <strong>read-only</strong>
 * reference material and is <strong>never copied</strong> into this repository;
 * traceability is by commit SHA only (AAP &sect;0.7.2). Per the <strong>Minimal
 * Change Clause</strong> (AAP &sect;0.7.1) this migration reproduces the COBOL
 * behaviour <em>exactly</em> &mdash; no business-rule "improvements", no extra
 * filtering, no feature additions &mdash; and documents every technology
 * substitution at its point of use. The application base package is
 * {@code com.cardemo} (decision D-006).
 *
 * <h2>What this processor reproduces ({@code CBTRN03C} main loop)</h2>
 * <p>{@code CBTRN03C} reads the {@code TRANSACT} file sequentially
 * ({@code 1000-TRANFILE-GET-NEXT}) and, for each transaction, applies the date
 * window predicate and &mdash; for the qualifying rows &mdash; the three keyed
 * enrichment lookups before writing a detail line. This processor performs the
 * <em>filter</em> and <em>enrich</em> halves of that loop and emits a
 * {@link TransactionReportLine} carrier for each qualifying row:</p>
 * <ol>
 *   <li><strong>Date-window filter (main-loop predicate).</strong> COBOL
 *       {@code IF TRAN-PROC-TS (1:10) >= WS-START-DATE AND TRAN-PROC-TS (1:10) <=
 *       WS-END-DATE} processes the row; otherwise {@code NEXT SENTENCE} skips it.
 *       See {@link #process(Transaction)}.</li>
 *   <li><strong>{@code 1500-A-LOOKUP-XREF}.</strong> Resolve the owning account id
 *       ({@code XREF-ACCT-ID}) by card number. COBOL {@code READ XREF-FILE ...
 *       INVALID KEY} &rarr; {@link CardCrossReferenceRepository#findById(Object)};
 *       a miss is a COBOL abend &rarr; {@link RecordNotFoundException}.</li>
 *   <li><strong>{@code 1500-B-LOOKUP-TRANTYPE}.</strong> Resolve the type
 *       description ({@code TRAN-TYPE-DESC}) by the two-byte type code. COBOL
 *       {@code READ TRANTYPE-FILE ... INVALID KEY} &rarr;
 *       {@link TransactionTypeRepository#findById(Object)}; a miss &rarr;
 *       {@link RecordNotFoundException}.</li>
 *   <li><strong>{@code 1500-C-LOOKUP-TRANCATG}.</strong> Resolve the category
 *       description ({@code TRAN-CAT-TYPE-DESC}) by the composite
 *       {@code (type, category)} key. COBOL {@code READ TRANCATG-FILE ... INVALID
 *       KEY} &rarr; {@link TransactionCategoryRepository#findById(Object)}; a miss
 *       &rarr; {@link RecordNotFoundException}.</li>
 *   <li><strong>{@code 1120-WRITE-DETAIL} field mapping.</strong> Build the
 *       {@link TransactionReportLine} with the eight detail fields in COBOL order
 *       (see {@link #buildReportLine}).</li>
 * </ol>
 *
 * <h2>"Filter &amp; skip" batch semantics (out-of-range &rarr; {@code null})</h2>
 * <p>Unlike the daily-posting processor &mdash; where a business reject must be
 * <em>carried</em> to the reject writer and therefore must never be dropped
 * &mdash; an out-of-window transaction here is simply <em>not reported</em>.
 * COBOL skips it with {@code NEXT SENTENCE}; the faithful Spring Batch equivalent
 * is to <strong>return {@code null}</strong> from {@link #process(Transaction)},
 * which filters the item out of the chunk so it never reaches the report writer.
 * Qualifying rows always produce a non-{@code null} line; only out-of-range rows
 * (and, defensively, a row with no processed timestamp) return {@code null}.</p>
 *
 * <h2>Enrichment misses are FATAL (not filtered)</h2>
 * <p>All three {@code 1500-*} lookups use {@code READ ... INVALID KEY} which, in
 * {@code CBTRN03C}, moves {@code 23} to {@code IO-STATUS} and calls
 * {@code 9999-ABEND-PROGRAM} ({@code CEE3ABD}) &mdash; the program aborts. The
 * faithful mapping is therefore to <strong>throw</strong>
 * {@link RecordNotFoundException} (the typed mapping of {@code FILE STATUS '23'},
 * AAP &sect;0.7.5), <em>not</em> to return {@code null}. A missing reference row
 * fails the step rather than silently dropping the transaction from the report.</p>
 *
 * <h2>Totals &amp; pagination are CROSS-ITEM state &mdash; owned by the writer</h2>
 * <p>{@code CBTRN03C} accumulates three running totals that span multiple records
 * and therefore <strong>cannot</strong> be owned by a stateless, chunk-oriented
 * per-item processor:</p>
 * <ul>
 *   <li><strong>{@code WS-PAGE-TOTAL}</strong> &mdash; the per-printed-page
 *       subtotal. On a page boundary ({@code FUNCTION MOD(WS-LINE-COUNTER,
 *       WS-PAGE-SIZE) = 0}, with {@code WS-PAGE-SIZE PIC 9(03) COMP-3 VALUE 20})
 *       COBOL writes the page total ({@code 1110-WRITE-PAGE-TOTALS}), <em>adds it
 *       to the grand total</em>, resets it to zero, and re-prints the column
 *       headers ({@code 1120-WRITE-HEADERS}).</li>
 *   <li><strong>{@code WS-ACCOUNT-TOTAL}</strong> &mdash; the per-account subtotal,
 *       written and reset on each card-number break
 *       ({@code WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM} &rarr;
 *       {@code 1120-WRITE-ACCOUNT-TOTALS}).</li>
 *   <li><strong>{@code WS-GRAND-TOTAL}</strong> &mdash; the report-wide total,
 *       written at end-of-file ({@code 1110-WRITE-GRAND-TOTALS}).</li>
 * </ul>
 * <p>These, together with header/page-break rendering, are the responsibility of
 * the {@code com.cardemo.batch.writers} report writer (or an aggregating step) in
 * {@code com.cardemo.batch.jobs}. This processor deliberately keeps
 * <strong>no</strong> report-wide running totals; it emits one
 * {@link TransactionReportLine} per qualifying row carrying the {@code accountId}
 * and {@code tranAmt} the writer needs to reproduce the per-page subtotal
 * (add-to-grand, reset, reprint header on page boundary), the per-card subtotal
 * (on card break) and the grand total (at end-of-file) faithfully.</p>
 *
 * <h2>Technology substitutions (documented per Minimal Change Clause)</h2>
 * <ul>
 *   <li><strong>VSAM keyed {@code READ ... INVALID KEY} &rarr;
 *       {@code JpaRepository.findById}.</strong> Each {@code 1500-*} keyed read
 *       becomes an {@code Optional}-returning {@code findById}; an empty
 *       {@code Optional} models the COBOL invalid-key path and is mapped to
 *       {@link RecordNotFoundException} ({@code FILE STATUS '23'}).</li>
 *   <li><strong>{@code DATEPARM} file &rarr; Spring Batch job parameters.</strong>
 *       COBOL reads {@code WS-START-DATE}/{@code WS-END-DATE} (both
 *       {@code PIC X(10)}, {@code yyyy-MM-dd}) from the {@code DATEPARM} sequential
 *       file ({@code 0550-DATEPARM-READ}). Here they arrive via Spring Batch late
 *       binding as the {@code startDate}/{@code endDate} job parameters (see
 *       {@link #setStartDate(String)} / {@link #setEndDate(String)}), which is why
 *       the bean is {@link StepScope step-scoped}.</li>
 *   <li><strong>{@code TRAN-PROC-TS (1:10)} substring &rarr;
 *       {@link LocalDate}-derived {@code yyyy-MM-dd} string.</strong> The migrated
 *       {@link Transaction} entity stores {@code TRAN-PROC-TS PIC X(26)} as a
 *       {@link LocalDateTime} (not the raw 26-byte text). The date-window predicate
 *       therefore derives the 10-character date prefix via
 *       {@code getTranProcTs().toLocalDate().toString()}, which yields exactly the
 *       same {@code yyyy-MM-dd} string the COBOL substring {@code (1:10)} produced.
 *       The comparison is preserved as an inclusive lexicographic
 *       {@link String#compareTo(String)} against the two bounds &mdash; never
 *       {@code equals} (AAP &sect;0.7.3) &mdash; and, because ISO date strings sort
 *       chronologically, the result is identical to the COBOL compare. No
 *       {@code CEEDAYS}/date math is introduced.</li>
 *   <li><strong>{@code COMP-3}/{@code PIC S9(09)V99} money &rarr;
 *       {@link java.math.BigDecimal}.</strong> {@code TRAN-AMT} is carried through
 *       as a {@link java.math.BigDecimal} on the emitted line; no
 *       {@code float}/{@code double} appears anywhere (AAP &sect;0.7.3).</li>
 *   <li><strong>{@code 9999-ABEND-PROGRAM} on a missing reference &rarr;
 *       {@link RecordNotFoundException}.</strong> See the FATAL note above.</li>
 *   <li><strong>{@code WS-PAGE-TOTAL}/{@code WS-ACCOUNT-TOTAL}/{@code WS-GRAND-TOTAL}
 *       &rarr; writer/step.</strong> See the totals note above.</li>
 * </ul>
 *
 * <h2>Reader coordination (defense-in-depth)</h2>
 * <p>The transaction reader for this report is expected to use
 * {@code TransactionRepository.findByProcessingDateRange(startDate, endDate)},
 * which already performs the date filter SQL-side and orders the result
 * {@code ORDER BY t.tranCardNum} (so card-number-grouped rows reach the writer in
 * the order COBOL relied on for its account-break subtotals). This processor
 * <strong>still</strong> implements the date predicate faithfully so behaviour is
 * correct regardless of how the reader is wired &mdash; the redundant check is
 * intentional (defense-in-depth and exact parity with the COBOL main loop).</p>
 *
 * <h2>Per-row XREF lookup vs. COBOL card-break optimization</h2>
 * <p>COBOL performs {@code 1500-A-LOOKUP-XREF} only on a card-number break
 * ({@code WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}) and reuses the resolved
 * {@code XREF-ACCT-ID} for subsequent rows of the same card. This processor
 * collapses that optimization to a per-row {@code findById} on the card number:
 * the account id depends <em>only</em> on the card number, so a per-row lookup
 * yields the identical value for every row of a given card &mdash; the report
 * output is unchanged. The processor is kept stateless (no same-card cache) so it
 * holds no cross-item state and makes no assumption about input ordering.</p>
 *
 * <h2>Scope &amp; thread-safety</h2>
 * <p>The class is {@link StepScope step-scoped} solely so the late-bound
 * {@code startDate}/{@code endDate} job parameters resolve at step-execution time
 * (the COBOL {@code DATEPARM} arrives the same way from JCL). Apart from those two
 * bound bounds the processor is stateless: its three repository collaborators are
 * {@code final} and injected, and all per-record working state is method-local, so
 * processing is free of cross-item side effects.</p>
 *
 * @see TransactionReportLine
 * @see RecordNotFoundException
 * @see ItemProcessor
 */
@Component
@StepScope
public class TransactionReportProcessor
        implements ItemProcessor<Transaction, TransactionReportLine> {

    /**
     * Entity-type label for the {@code TRANTYPE} not-found diagnostic, passed to
     * {@link RecordNotFoundException#RecordNotFoundException(String, String)} when
     * {@code 1500-B-LOOKUP-TRANTYPE} misses. Mirrors the COBOL
     * {@code 'INVALID TRANSACTION TYPE'} display without copying the COBOL text.
     */
    private static final String ENTITY_TRANSACTION_TYPE = "TransactionType";

    /**
     * Entity-type label for the {@code TRANCATG} not-found diagnostic, used when
     * {@code 1500-C-LOOKUP-TRANCATG} misses (COBOL {@code 'INVALID TRAN CATG KEY'}).
     */
    private static final String ENTITY_TRANSACTION_CATEGORY = "TransactionCategory";

    /**
     * Entity-type label for the {@code CARDXREF} not-found diagnostic, used when
     * {@code 1500-A-LOOKUP-XREF} misses (COBOL {@code 'INVALID CARD NUMBER'}).
     */
    private static final String ENTITY_CARD_CROSS_REFERENCE = "CardCrossReference";

    /**
     * Transaction-type reference repository &mdash; the JPA replacement for the
     * keyed {@code READ TRANTYPE-FILE} of {@code 1500-B-LOOKUP-TRANTYPE}. Typed
     * {@code JpaRepository<TransactionType, String>} because the type key is the
     * two-byte {@code TRAN-TYPE} code.
     */
    private final TransactionTypeRepository transactionTypeRepository;

    /**
     * Transaction-category reference repository &mdash; the JPA replacement for the
     * keyed {@code READ TRANCATG-FILE} of {@code 1500-C-LOOKUP-TRANCATG}. Typed
     * {@code JpaRepository<TransactionCategory, TransactionCategoryId>} over the
     * composite {@code (type, category)} key.
     */
    private final TransactionCategoryRepository transactionCategoryRepository;

    /**
     * Card cross-reference repository &mdash; the JPA replacement for the keyed
     * {@code READ XREF-FILE} of {@code 1500-A-LOOKUP-XREF}. Typed
     * {@code JpaRepository<CardCrossReference, String>} because the cross-reference
     * key is the 16-character card number; the resolved {@code XREF-ACCT-ID} is the
     * report's account id.
     */
    private final CardCrossReferenceRepository crossReferenceRepository;

    /**
     * Inclusive lower bound of the report date window, a {@code yyyy-MM-dd} string.
     * COBOL {@code WS-START-DATE PIC X(10)} read from the {@code DATEPARM} file;
     * here it is the {@code startDate} job parameter bound by Spring Batch late
     * binding (see {@link #setStartDate(String)}).
     */
    private String startDate;

    /**
     * Inclusive upper bound of the report date window, a {@code yyyy-MM-dd} string.
     * COBOL {@code WS-END-DATE PIC X(10)} read from the {@code DATEPARM} file; here
     * it is the {@code endDate} job parameter bound by Spring Batch late binding
     * (see {@link #setEndDate(String)}).
     */
    private String endDate;

    /**
     * Constructs the processor with its three reference/lookup repositories,
     * injected by Spring. All three are required and {@code final}; the date-window
     * bounds are supplied separately via late-bound job-parameter setters.
     *
     * @param transactionTypeRepository     the {@code TRANTYPE} repository
     *                                      ({@code 1500-B}); must not be {@code null}
     * @param transactionCategoryRepository the {@code TRANCATG} repository
     *                                      ({@code 1500-C}); must not be {@code null}
     * @param crossReferenceRepository      the {@code CARDXREF} repository
     *                                      ({@code 1500-A}); must not be {@code null}
     */
    @Autowired
    public TransactionReportProcessor(TransactionTypeRepository transactionTypeRepository,
                                      TransactionCategoryRepository transactionCategoryRepository,
                                      CardCrossReferenceRepository crossReferenceRepository) {
        this.transactionTypeRepository = transactionTypeRepository;
        this.transactionCategoryRepository = transactionCategoryRepository;
        this.crossReferenceRepository = crossReferenceRepository;
    }

    /**
     * Late-binding setter for the report window's inclusive <strong>start</strong>
     * date. Reproduces the COBOL {@code WS-START-DATE} that {@code CBTRN03C} reads
     * from the {@code DATEPARM} sequential file ({@code 0550-DATEPARM-READ}). The
     * SpEL {@code #{jobParameters['startDate']}} is resolved by Spring Batch at
     * step-execution time, which is why the bean is {@link StepScope step-scoped}.
     *
     * @param startDate the inclusive lower bound as a {@code yyyy-MM-dd} string
     */
    @Value("#{jobParameters['startDate']}")
    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    /**
     * Late-binding setter for the report window's inclusive <strong>end</strong>
     * date. Reproduces the COBOL {@code WS-END-DATE} that {@code CBTRN03C} reads
     * from the {@code DATEPARM} sequential file ({@code 0550-DATEPARM-READ}),
     * resolved via Spring Batch late binding for the same reason as
     * {@link #setStartDate(String)}.
     *
     * @param endDate the inclusive upper bound as a {@code yyyy-MM-dd} string
     */
    @Value("#{jobParameters['endDate']}")
    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    /**
     * Returns the bound inclusive start bound ({@code WS-START-DATE}), primarily
     * for tests and diagnostics.
     *
     * @return the start bound as a {@code yyyy-MM-dd} string, or {@code null} if not
     *         yet bound
     */
    public String getStartDate() {
        return startDate;
    }

    /**
     * Returns the bound inclusive end bound ({@code WS-END-DATE}), primarily for
     * tests and diagnostics.
     *
     * @return the end bound as a {@code yyyy-MM-dd} string, or {@code null} if not
     *         yet bound
     */
    public String getEndDate() {
        return endDate;
    }

    /**
     * Filters a single transaction to the requested date window and, for a
     * qualifying row, enriches it into a {@link TransactionReportLine}, reproducing
     * the {@code CBTRN03C} main-loop predicate, the {@code 1500-A/B/C} lookups and
     * the {@code 1120-WRITE-DETAIL} field mapping for one record.
     *
     * <p>An out-of-window transaction (and, defensively, a row with no processed
     * timestamp) returns {@code null}, which Spring Batch treats as "filter this
     * item out of the chunk" &mdash; exactly matching COBOL's {@code NEXT SENTENCE}
     * skip of a non-qualifying transaction. A qualifying row always produces a
     * non-{@code null} line. A missing reference row on any of the three enrichment
     * lookups is <strong>fatal</strong> (a {@link RecordNotFoundException}, the
     * typed mapping of the COBOL {@code INVALID KEY} abend), never a filtered
     * {@code null}.</p>
     *
     * @param item the transaction supplied by the reader (the sequential
     *             {@code TRANSACT} browse, {@code 1000-TRANFILE-GET-NEXT});
     *             non-{@code null} per the Spring Batch chunk contract
     * @return a populated {@link TransactionReportLine} for an in-window row, or
     *         {@code null} to filter an out-of-window row out of the report
     * @throws RecordNotFoundException if the card cross-reference
     *                                 ({@code 1500-A-LOOKUP-XREF}), the transaction
     *                                 type ({@code 1500-B-LOOKUP-TRANTYPE}) or the
     *                                 transaction category
     *                                 ({@code 1500-C-LOOKUP-TRANCATG}) cannot be
     *                                 resolved &mdash; the typed mapping of a COBOL
     *                                 {@code INVALID KEY} abend ({@code FILE STATUS
     *                                 '23'})
     */
    @Override
    public TransactionReportLine process(Transaction item) {
        // === Date-window filter (CBTRN03C main-loop predicate, source L173-178) ====
        // COBOL: IF TRAN-PROC-TS (1:10) >= WS-START-DATE
        //           AND TRAN-PROC-TS (1:10) <= WS-END-DATE   -> process
        //        ELSE NEXT SENTENCE                           -> skip the row.
        final LocalDateTime processedTimestamp = item.getTranProcTs();
        if (processedTimestamp == null) {
            // Defensive parity: COBOL TRAN-PROC-TS is a fixed PIC X(26) field; an
            // absent/space value's first 10 chars sort below any real yyyy-MM-dd
            // start bound and so could never fall within [startDate, endDate] -- it
            // would be skipped. A null timestamp is the relational analog, so the
            // row is filtered out (return null) rather than raising an NPE. In
            // practice the reader's findByProcessingDateRange never yields nulls.
            return null;
        }
        // The migrated Transaction stores TRAN-PROC-TS (PIC X(26)) as a LocalDateTime
        // rather than the raw 26-byte text, so we derive the 10-char date prefix from
        // its date component. LocalDate.toString() is ISO-8601 "yyyy-MM-dd", which is
        // byte-identical to the COBOL TRAN-PROC-TS (1:10) substring.
        final LocalDate procLocalDate = processedTimestamp.toLocalDate();
        final String procDate = procLocalDate.toString();
        // COBOL compares the raw 10-char date substring directly; no CEEDAYS/date
        // math -- preserved as String.compareTo (never equals; AAP 0.7.3). The window
        // is inclusive on BOTH ends (>= start AND <= end); because ISO date strings
        // sort chronologically, this lexicographic compare equals the COBOL compare.
        if (procDate.compareTo(startDate) < 0 || procDate.compareTo(endDate) > 0) {
            // Out of range -> filtered out. Returning null is the correct Spring Batch
            // ItemProcessor "drop this item" signal and exactly matches COBOL's
            // NEXT SENTENCE skip. (Contrast the posting processor, where rejects must
            // NOT be dropped and are carried on a result object instead.)
            return null;
        }

        // === Enrichment lookups (CBTRN03C 1500-A/B/C) -- FATAL on miss =============
        // Performed in the COBOL main-loop order: XREF (account id) -> TRANTYPE (type
        // description) -> TRANCATG (category description). Each maps a keyed VSAM
        // READ ... INVALID KEY (which COBOL handles by moving 23 to IO-STATUS and
        // calling 9999-ABEND-PROGRAM) onto findById(...).orElseThrow(...), so a
        // missing reference row throws RecordNotFoundException ("23") and fails the
        // step -- it is deliberately NOT filtered out like an out-of-range row.
        final Long accountId = lookupAccountId(item.getTranCardNum());
        final String tranTypeDesc = lookupTransactionTypeDesc(item.getTranTypeCd());
        final String tranCatTypeDesc =
                lookupTransactionCategoryDesc(item.getTranTypeCd(), item.getTranCatCd());

        // === Build the detail row (CBTRN03C 1120-WRITE-DETAIL) =====================
        return buildReportLine(item, accountId, tranTypeDesc, tranCatTypeDesc);
    }

    /**
     * Resolves the owning account id for a card number, reproducing
     * {@code 1500-A-LOOKUP-XREF} ({@code READ XREF-FILE ... INVALID KEY}).
     *
     * <p>COBOL performs this lookup only on a card-number break
     * ({@code WS-CURR-CARD-NUM NOT= TRAN-CARD-NUM}) and reuses the resolved
     * {@code XREF-ACCT-ID} for subsequent rows of the same card. This method is
     * called per row instead: the account id depends only on the card number, so a
     * per-row {@link CardCrossReferenceRepository#findById(Object)} yields the
     * identical value for every row of a given card and the report output is
     * unchanged &mdash; while keeping the processor stateless (no same-card cache,
     * no cross-item assumptions). A missing cross-reference is the COBOL
     * {@code INVALID KEY} abend, mapped to {@link RecordNotFoundException}.</p>
     *
     * @param cardNumber the transaction's card number ({@code TRAN-CARD-NUM})
     * @return the owning account id ({@code XREF-ACCT-ID})
     * @throws RecordNotFoundException if no cross-reference exists for the card
     *                                 number ({@code FILE STATUS '23'})
     */
    private Long lookupAccountId(String cardNumber) {
        final CardCrossReference xref = crossReferenceRepository.findById(cardNumber)
                .orElseThrow(() -> new RecordNotFoundException(
                        ENTITY_CARD_CROSS_REFERENCE, cardNumber));
        return xref.getXrefAcctId();
    }

    /**
     * Resolves the transaction-type description, reproducing
     * {@code 1500-B-LOOKUP-TRANTYPE} ({@code READ TRANTYPE-FILE ... INVALID KEY}).
     * A missing type is the COBOL {@code INVALID KEY} abend, mapped to
     * {@link RecordNotFoundException}.
     *
     * @param tranTypeCd the two-character transaction-type code
     *                   ({@code TRAN-TYPE-CD})
     * @return the transaction-type description ({@code TRAN-TYPE-DESC})
     * @throws RecordNotFoundException if no transaction type exists for the code
     *                                 ({@code FILE STATUS '23'})
     */
    private String lookupTransactionTypeDesc(String tranTypeCd) {
        final TransactionType type = transactionTypeRepository.findById(tranTypeCd)
                .orElseThrow(() -> new RecordNotFoundException(
                        ENTITY_TRANSACTION_TYPE, tranTypeCd));
        return type.getTranTypeDesc();
    }

    /**
     * Resolves the transaction-category description, reproducing
     * {@code 1500-C-LOOKUP-TRANCATG} ({@code READ TRANCATG-FILE ... INVALID KEY}).
     *
     * <p>The composite lookup key is built in the COBOL field order
     * ({@code TRAN-TYPE-CD} then {@code TRAN-CAT-CD}); the
     * {@link TransactionCategoryId#TransactionCategoryId(String, Integer)}
     * constructor mirrors that order exactly. A missing category is the COBOL
     * {@code INVALID KEY} abend, mapped to {@link RecordNotFoundException}.</p>
     *
     * @param tranTypeCd the two-character transaction-type code
     *                   ({@code TRAN-TYPE-CD})
     * @param tranCatCd  the numeric transaction-category code ({@code TRAN-CAT-CD})
     * @return the transaction-category description ({@code TRAN-CAT-TYPE-DESC})
     * @throws RecordNotFoundException if no transaction category exists for the
     *                                 composite key ({@code FILE STATUS '23'})
     */
    private String lookupTransactionCategoryDesc(String tranTypeCd, Integer tranCatCd) {
        final TransactionCategoryId categoryId =
                new TransactionCategoryId(tranTypeCd, tranCatCd);
        final TransactionCategory category =
                transactionCategoryRepository.findById(categoryId)
                        .orElseThrow(() -> new RecordNotFoundException(
                                ENTITY_TRANSACTION_CATEGORY, categoryId.toString()));
        return category.getTranCatTypeDesc();
    }

    /**
     * Assembles the {@link TransactionReportLine} detail row, reproducing the field
     * mapping of {@code CBTRN03C} paragraph {@code 1120-WRITE-DETAIL} in COBOL
     * order:
     * <pre>{@code
     *   TRAN-ID            -> tranId
     *   XREF-ACCT-ID       -> accountId        (from 1500-A; NOT a transaction field)
     *   TRAN-TYPE-CD       -> tranTypeCd
     *   TRAN-TYPE-DESC     -> tranTypeDesc      (from 1500-B)
     *   TRAN-CAT-CD        -> tranCatCd
     *   TRAN-CAT-TYPE-DESC -> tranCatTypeDesc   (from 1500-C)
     *   TRAN-SOURCE        -> tranSource
     *   TRAN-AMT           -> tranAmt           (BigDecimal; no float/double, AAP 0.7.3)
     * }</pre>
     *
     * <p>The page, account and grand totals ({@code WS-PAGE-TOTAL},
     * {@code WS-ACCOUNT-TOTAL}, {@code WS-GRAND-TOTAL}) and the pagination/header
     * re-printing are intentionally <strong>not</strong> computed here &mdash; they
     * are cross-record state owned by the report writer/step (see the class
     * Javadoc). This method only maps the single qualifying row.</p>
     *
     * @param item            the qualifying transaction
     * @param accountId       the owning account id resolved by the XREF lookup
     * @param tranTypeDesc    the type description resolved by the TRANTYPE lookup
     * @param tranCatTypeDesc the category description resolved by the TRANCATG lookup
     * @return the populated detail-line carrier for the report writer
     */
    private TransactionReportLine buildReportLine(Transaction item,
                                                  Long accountId,
                                                  String tranTypeDesc,
                                                  String tranCatTypeDesc) {
        return new TransactionReportLine(
                item.getTranId(),
                accountId,
                item.getTranTypeCd(),
                tranTypeDesc,
                item.getTranCatCd(),
                tranCatTypeDesc,
                item.getTranSource(),
                item.getTranAmt());
    }
}
